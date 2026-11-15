"""The firmware's imperative shell: the hardware, Bluetooth, files and the clock.

`just install` puts this package in /lib/firmware on the board, with a
/main.py that calls run() at every start. The pure modules next to this one
decide what the unit does; this module feeds them readings, commands and the
time, and carries out what they return. ../../firmware.md describes what a
unit does and the log it prints over USB.
"""

import asyncio
import json
import os
import sys
import time

import bluetooth
import machine
from machine import ADC, I2C, Pin
from micropython import const
from vl53l1x import VL53L1X

from firmware import control, patterns, protocol, sensing

FIRMWARE_VERSION = (0, 1, 0)

# Pin assignments: ../../prototype-assembly.md, section "Pin assignments".
_SCL = const(18)
_SDA = const(19)
_XSHUT = const(16)
_BUZZER = const(25)
_GREEN = const(26)
_RED = const(27)
_BATTERY = const(35)

SETUP_FILE = "/passage.bin"  # the record and the PIN, see control.stored
UNIT_FILE = "/unit.json"  # the notes `just bringup` saves about this unit's hardware

_IRQ_CENTRAL_CONNECT = const(1)
_IRQ_CENTRAL_DISCONNECT = const(2)
_IRQ_GATTS_WRITE = const(3)
_IRQ_MTU_EXCHANGED = const(21)

_FAST_ADVERTISING_US = const(100_000)
_SLOW_ADVERTISING_US = const(1_000_000)

_POLL_MS = const(5)
_LATE_MS = const(100)  # no measurement this long after the previous reading is a fault
_FAULT_REPEAT_MS = const(50)
_SENSOR_RETRY_MS = const(1000)
_BATTERY_EVERY_MS = const(10_000)
# NimBLE stops advertising when a connection fails to be established, and
# MicroPython reports no event for it: advertise again this often meanwhile.
_READVERTISE_EVERY_S = const(2)

# RTC memory survives restarts but not switching off: the wrong PIN count goes there.
_RTC_MAGIC = b"SD"

_SERVICE = (
    bluetooth.UUID(protocol.SERVICE_UUID),
    (
        (bluetooth.UUID(protocol.UNIT_INFO_UUID), bluetooth.FLAG_READ),
        (bluetooth.UUID(protocol.STATUS_UUID), bluetooth.FLAG_READ | bluetooth.FLAG_NOTIFY),
        (bluetooth.UUID(protocol.READING_UUID), bluetooth.FLAG_NOTIFY),
        (bluetooth.UUID(protocol.SETUP_UUID), bluetooth.FLAG_READ),
        (bluetooth.UUID(protocol.SETUP_STAGING_UUID), bluetooth.FLAG_WRITE),
        (bluetooth.UUID(protocol.CONTROL_POINT_UUID), bluetooth.FLAG_WRITE | bluetooth.FLAG_NOTIFY),
    ),
)

_FLAG_NAMES = (
    (protocol.IN_USE, "in use"),
    (protocol.UNLOCKED, "unlocked"),
    (protocol.PIN_RESET_WINDOW, "PIN reset window"),
    (protocol.LOCKED_OUT, "locked out"),
    (protocol.LOW_BATTERY, "low battery"),
    (protocol.SENSOR_FAULT, "sensor fault"),
)


def _flag_names(flags):
    return ", ".join(name for flag, name in _FLAG_NAMES if flags & flag) or "none"


def _unit_notes():
    """(buzzer level that silences it, battery divider fitted, battery offset in V)."""
    try:
        with open(UNIT_FILE) as file:
            notes = json.load(file)
    except (OSError, ValueError):
        notes = {}
    return (
        notes.get("buzzer_silent", 1),
        notes.get("battery_divider", False),
        notes.get("battery_offset_v", 0.0),
    )


def _load_setup(unit_id):
    try:
        with open(SETUP_FILE, "rb") as file:
            return control.restored(file.read(), unit_id)
    except OSError:
        return control.UNASSIGNED


def _save_setup(setup):
    # A new file renamed over the old one, so that switching off leaves one or the other.
    try:
        with open(SETUP_FILE + ".new", "wb") as file:
            file.write(control.stored(setup))
        os.rename(SETUP_FILE + ".new", SETUP_FILE)
        return True
    except OSError:
        return False


def _delete_setup():
    try:
        os.remove(SETUP_FILE)
    except OSError as error:
        return error.errno == 2  # ENOENT: already gone
    return True


def _carry_out(effect, setup):
    """Save or delete the setup file as `effect` asks: False if that failed."""
    if effect == control.SAVE:
        return _save_setup(setup)
    if effect == control.FORGET:
        return _delete_setup()
    return True


def _restored_wrong_pins(switched_on):
    memory = machine.RTC().memory()
    if switched_on or len(memory) < 3 or memory[:2] != _RTC_MAGIC:
        return 0
    return memory[2]


def _keep_wrong_pins(count):
    machine.RTC().memory(_RTC_MAGIC + bytes((count,)))


class Unit:
    def __init__(self):
        self.started = time.ticks_ms()
        silent, divider, battery_offset = _unit_notes()
        # The buzzer first, so that it is quiet as early as possible.
        self.silent = silent
        self.buzzer = Pin(_BUZZER, Pin.OUT, value=silent)
        self.green = Pin(_GREEN, Pin.OUT, value=0)
        self.red = Pin(_RED, Pin.OUT, value=0)
        # Open-drain: the sensor's logic may run at 2.8 V. Low keeps it off.
        self.xshut = Pin(_XSHUT, Pin.OPEN_DRAIN, value=0)
        self.i2c = I2C(0, scl=Pin(_SCL), sda=Pin(_SDA), freq=400_000)
        self.adc = ADC(Pin(_BATTERY), atten=ADC.ATTN_11DB) if divider else None
        self.battery_offset = battery_offset
        self.unit_id = machine.unique_id()
        self.switched_on = machine.reset_cause() == machine.PWRON_RESET
        wrong_pins = _restored_wrong_pins(self.switched_on)
        self.access = control.started(_load_setup(self.unit_id), wrong_pins)
        self.ble = bluetooth.BLE()
        self.events = []  # from the Bluetooth IRQ, for _handle_events
        self.wake = asyncio.ThreadSafeFlag()
        self.connection = None
        # Without a phone, the sensor runs for a while after these, see sensing.open_windows.
        self.measuring_from = self.started  # None once a phone sent Release, or later
        self.lost_at = None  # when the link to the phone was lost
        self.sensor_on = False
        self.tof = None  # the driver, while the sensor answers
        self.retry_at = 0
        self.due = 0  # when a missing measurement becomes a fault reading
        self.sequence = 0
        self.sight = None
        self.sensor_fault = False
        self.battery = protocol.NO_BATTERY
        self.identify_at = None
        self.flags = 0
        self.status = None  # the status value last sent
        self.advertised = None  # (advertisement, connected) last advertised
        self.lights = None  # what the lights do, for the log
        self.readings = [0] * len(protocol.STATUS_NAMES)  # by status, since the last summary
        self.last_reading = None

    def log(self, text):
        print(f"{time.ticks_diff(time.ticks_ms(), self.started) / 1000:9.3f}  {text}")

    def _pin_reset_window(self, now):
        uptime = time.ticks_diff(now, self.started)
        return self.switched_on and uptime < control.PIN_RESET_WINDOW_MS

    def _name(self):
        record = self.access.setup.record
        return record.name if record else protocol.unassigned_name(self.unit_id)

    def _describe(self):
        self.log(
            f"Safe Driveway firmware {'.'.join(str(n) for n in FIRMWARE_VERSION)}, "
            f"unit {self.unit_id.hex()} ({protocol.short_id(self.unit_id)}), "
            + ("switched on" if self.switched_on else "restarted")
        )
        self.log(
            f"buzzer silent at {'HIGH' if self.silent else 'LOW'}, battery divider "
            + ("fitted" if self.adc else "not fitted")
        )
        self._describe_setup()

    def _describe_setup(self):
        setup = self.access.setup
        if setup.entry is None:
            self.log("unassigned" + (", has a PIN" if setup.pin else ""))
            return
        record, entry = setup.record, setup.entry
        self.log(
            f"passage {record.name.decode()!r}, ID {record.passage_id:08x}, "
            f"setup version {record.setup_version}, gate {entry.gate}, "
            f"{'right' if entry.side else 'left'}, zero offset {entry.zero_offset} mm, "
            f"warning {record.warning_gap} mm, critical {record.critical_gap} mm, "
            + ("signals the gap" if entry.signals else "doesn't signal the gap")
        )

    # Bluetooth

    def _start_bluetooth(self):
        ble = self.ble
        ble.active(True)
        ble.irq(self._irq)
        (
            (
                self.h_info,
                self.h_status,
                self.h_reading,
                self.h_setup,
                self.h_staging,
                self.h_control,
            ),
        ) = ble.gatts_register_services((_SERVICE,))
        # A whole record fits the staging buffer, also as a long write.
        ble.gatts_set_buffer(self.h_staging, 512)
        info = protocol.unit_info(self.unit_id, FIRMWARE_VERSION, self.adc is not None)
        ble.gatts_write(self.h_info, info)
        self._publish_setup()

    def _irq(self, event, data):
        # Runs as a scheduled callback: only queue the event for _handle_events.
        if event == _IRQ_GATTS_WRITE:
            handle = data[1]
            self.events.append((event, handle, self.ble.gatts_read(handle)))
        elif event == _IRQ_CENTRAL_CONNECT or event == _IRQ_CENTRAL_DISCONNECT:
            self.events.append((event, data[0], None))
        elif event == _IRQ_MTU_EXCHANGED:
            self.events.append((event, data[1], None))
        else:
            return
        self.wake.set()

    def _notify(self, handle, value):
        # Sets the value, and notifies the phone if it has subscribed.
        self.ble.gatts_write(handle, value, self.connection is not None)

    def _publish_setup(self):
        self.ble.gatts_write(self.h_setup, self.access.setup.raw)
        self.ble.config(gap_name=self._name())
        self.advertised = None  # the scan response has changed

    def _refresh(self):
        """Send the status and advertise again when something they carry has changed."""
        now = time.ticks_ms()
        connected = self.connection is not None
        flags = (
            control.flags(self.access, connected, self._pin_reset_window(now))
            | (protocol.LOW_BATTERY if sensing.low_battery(self.battery) else 0)
            | (protocol.SENSOR_FAULT if self.sensor_fault else 0)
        )
        status = protocol.status_value(flags, self.battery)
        if status != self.status:
            if flags != self.flags:
                self.log(f"flags: {_flag_names(flags)}")
            self._notify(self.h_status, status)
            self.status, self.flags = status, flags
        setup = self.access.setup
        entry = setup.entry
        advertisement = protocol.advertisement(
            entry.gate if entry else 0,
            entry.side if entry else 0,
            flags,
            setup.record.passage_id if entry else 0,
        )
        if (advertisement, connected) != self.advertised:
            self.ble.gap_advertise(
                _SLOW_ADVERTISING_US if connected else _FAST_ADVERTISING_US,
                adv_data=advertisement,
                resp_data=protocol.scan_response(self._name()),
                connectable=not connected,
            )
            self.advertised = (advertisement, connected)

    async def _handle_events(self):
        while True:
            await self.wake.wait()
            while self.events:
                event, value, data = self.events.pop(0)
                if event == _IRQ_CENTRAL_CONNECT:
                    self._connected(value)
                elif event == _IRQ_CENTRAL_DISCONNECT:
                    self._disconnected(value)
                elif event == _IRQ_GATTS_WRITE:
                    self._written(value, data)
                elif event == _IRQ_MTU_EXCHANGED:
                    self.log(f"MTU {value}")

    def _connected(self, connection):
        if self.connection is not None:
            self.ble.gap_disconnect(connection)
            self.log("a second phone connected, disconnected it")
            return
        self.connection = connection
        self.lost_at = None
        self.access = control.session_ended(self.access)
        self.log("phone connected")
        self._refresh()

    def _disconnected(self, connection):
        if connection != self.connection:
            return
        released = self.access.released
        self.connection = None
        self.access = control.session_ended(self.access)
        if released:
            self.measuring_from = self.lost_at = None
            self.log("phone disconnected after Release")
        else:
            self.lost_at = time.ticks_ms()
            self.log("link lost: measuring for 5 more minutes")
        self._refresh()

    def _written(self, handle, data):
        if handle == self.h_staging:
            self.access = control.staged(self.access, data)
            self.log(f"staged a record of {len(data)} bytes")
        elif handle == self.h_control:
            self._command(data)

    def _command(self, data):
        now = time.ticks_ms()
        before = self.access
        after, result, effect = control.command(
            before, data, self.unit_id, self._pin_reset_window(now)
        )
        if not _carry_out(effect, after.setup):
            after, result = before, protocol.SAVE_FAILED
        if effect == control.IDENTIFY:
            self.identify_at = now
        if after.wrong_pins != before.wrong_pins:
            _keep_wrong_pins(after.wrong_pins)
        self.access = after
        if after.setup is not before.setup:
            self._publish_setup()
        opcode = data[0] if data else 0
        self._notify(self.h_control, protocol.answer(opcode, result))
        name = protocol.COMMAND_NAMES.get(opcode, f"opcode {opcode}")
        self.log(f"{name}: {protocol.result_name(result)}")
        if after.setup is not before.setup:
            self._describe_setup()
        self._refresh()

    # Sensor

    def _start_sensor(self, now):
        # Switching it off and on also resets a sensor that stopped answering.
        self.xshut.value(0)
        time.sleep_ms(2)
        self.xshut.value(1)
        time.sleep_ms(10)
        try:
            tof = VL53L1X(self.i2c)
            tof.configure(short=True, timing_budget_ms=50)
            tof.start()
            self.tof = tof
        except OSError as error:
            self.tof = None
            self.retry_at = time.ticks_add(now, _SENSOR_RETRY_MS)
            self.log(f"sensor didn't start: {error}")

    def _stop_sensor(self):
        if self.tof is not None:
            try:
                self.tof.stop()
            except OSError:
                pass
        self.tof = None
        self.xshut.value(0)

    def _check_sensor(self):
        self._start_sensor(time.ticks_ms())
        self.sensor_fault = self.tof is None
        self.log("sensor fault" if self.sensor_fault else "sensor answers")
        self._stop_sensor()

    def _keeps_measuring(self, now):
        measuring_from, lost_at = sensing.open_windows(self.measuring_from, self.lost_at, now)
        if measuring_from is None and self.measuring_from is not None:
            self.log("the first 10 minutes after starting are over")
        if lost_at is None and self.lost_at is not None:
            self.log("the 5 minutes after the lost link are over")
        self.measuring_from, self.lost_at = measuring_from, lost_at
        return self.connection is not None or measuring_from is not None or lost_at is not None

    def _switch_sensor(self, on):
        self.sensor_on = on
        self.sight = None
        self.readings = [0] * len(protocol.STATUS_NAMES)
        self.last_reading = None
        if on:
            self.sequence = 0
            self._start_sensor(time.ticks_ms())
            self.due = time.ticks_add(time.ticks_ms(), _LATE_MS)
        else:
            self._stop_sensor()
        self.log("sensor on" if on else "sensor off")

    def _poll(self, now):
        if self.tof is None and time.ticks_diff(now, self.retry_at) >= 0:
            self._start_sensor(now)
        if self.tof is not None:
            try:
                if self.tof.data_ready():
                    distance, range_status = self.tof.result()
                    since = sensing.since_target(self.sight, now)
                    self._take(now, distance, protocol.reading_status(range_status, since))
                    self.due = time.ticks_add(now, _LATE_MS)
                    return
            except OSError as error:
                # _start_sensor tries again from scratch.
                self.log(f"sensor error: {error}")
                self.tof = None
                self.retry_at = time.ticks_add(now, _SENSOR_RETRY_MS)
        if time.ticks_diff(now, self.due) >= 0:
            self._take(now, 0, protocol.FAULT)
            self.due = time.ticks_add(now, _FAULT_REPEAT_MS)

    def _take(self, now, distance, status):
        self.sight = sensing.seen(self.sight, status, distance, now)
        if self.connection is not None:
            self._notify(self.h_reading, protocol.reading(self.sequence, distance, status))
        self.sequence = (self.sequence + 1) & 0xFF
        self.readings[status] += 1
        self.last_reading = (distance, status)
        fault = status == protocol.FAULT
        if fault != self.sensor_fault:
            self.sensor_fault = fault
            self._refresh()

    async def _measure(self):
        while True:
            now = time.ticks_ms()
            measuring = self._keeps_measuring(now)
            if measuring != self.sensor_on:
                self._switch_sensor(measuring)
            if measuring:
                self._poll(now)
                await asyncio.sleep_ms(_POLL_MS)
            else:
                await asyncio.sleep_ms(20)

    # Lights and sounds

    async def _show(self):
        beat = patterns.START
        shown = None
        started = time.ticks_ms()
        while True:
            now = time.ticks_ms()
            identify = None
            if self.identify_at is not None:
                identify = time.ticks_diff(now, self.identify_at)
                if identify >= patterns.IDENTIFY_MS:
                    self.identify_at = identify = None
            signal = None
            setup = self.access.setup
            if self.sensor_on and control.signals(self.access):
                record = setup.record
                signal = sensing.signal(
                    self.sight,
                    now,
                    record.warning_gap,
                    record.critical_gap,
                    setup.entry.zero_offset,
                )
            car = self.sensor_on and sensing.car_in_front(self.sight, now)
            uptime = time.ticks_diff(now, started)
            beat, lights, green, red, buzzer = patterns.show(
                beat, now, uptime, identify, signal, car, self.flags, self.sensor_on
            )
            if (green, red, buzzer) != shown:
                self.green.value(green)
                self.red.value(red)
                self.buzzer.value(1 - self.silent if buzzer else self.silent)
                shown = (green, red, buzzer)
            if lights != self.lights:
                self.log(f"lights: {lights}")
                self.lights = lights
            await asyncio.sleep_ms(10)

    # Battery, timeouts and the log

    def _battery_volts(self):
        total = sum(self.adc.read_uv() for _ in range(16))
        # The offset is how far the reading was above the multimeter's at bring-up.
        return total / 16 * 2 / 1_000_000 - self.battery_offset

    def _summary(self):
        if self.last_reading is None:
            self.log("no readings")
            return
        distance, status = self.last_reading
        counts = ", ".join(
            f"{count} {protocol.status_name(kind)}"
            for kind, count in enumerate(self.readings)
            if count
        )
        text = f"readings: {counts}; last {distance} mm {protocol.status_name(status)}"
        entry = self.access.setup.entry
        if entry is not None and status == protocol.VALID:
            text += f", gap {distance - entry.zero_offset} mm"
        self.log(text)
        self.readings = [0] * len(protocol.STATUS_NAMES)

    async def _every_second(self):
        battery_at = None
        seconds = 0
        while True:
            await asyncio.sleep_ms(1000)
            seconds += 1
            now = time.ticks_ms()
            if self.connection is None and seconds % _READVERTISE_EVERY_S == 0:
                self.advertised = None
            if self.adc and (
                battery_at is None or time.ticks_diff(now, battery_at) >= _BATTERY_EVERY_MS
            ):
                self.battery = sensing.battery_percent(self._battery_volts())
                battery_at = now
            self._refresh()
            if self.sensor_on:
                self._summary()

    async def main(self):
        asyncio.create_task(self._show())
        await asyncio.sleep_ms(2 * patterns.POWER_ON_MS)
        self._describe()
        self._check_sensor()
        if self.adc:
            self.battery = sensing.battery_percent(self._battery_volts())
        self._start_bluetooth()
        self._refresh()
        self.log("advertising, and measuring for the first 10 minutes")
        asyncio.create_task(self._measure())
        asyncio.create_task(self._every_second())
        await self._handle_events()

    def close(self):
        """Leave the unit dark, quiet and off the air."""
        try:
            self.ble.active(False)
        except OSError:
            pass
        self.green.value(0)
        self.red.value(0)
        self.buzzer.value(self.silent)
        self.xshut.value(0)


def run():
    """Run the firmware until Ctrl-C. After a crash, restart the board in 5 s."""
    unit = None
    crashed = False
    try:
        unit = Unit()
        asyncio.run(unit.main())
    except KeyboardInterrupt:
        print("Firmware stopped. Reset the board, or switch it off and on, to start it again.")
    except Exception as error:  # noqa: BLE001 - any crash restarts the unit
        sys.print_exception(error)
        crashed = True
    finally:
        if unit is not None:
            unit.close()
        asyncio.new_event_loop()
    if crashed:
        print("Restarting in 5 s. Ctrl-C stops the firmware.")
        time.sleep(5)
        machine.reset()
