"""Bring-up checks for a prototype unit. Runs on the board, in MicroPython.

`just bringup` sends this file and the sensor driver in lib/ to the board's
RAM, calls the functions below one at a time and asks the person at the
computer about what only they can see or hear. Nothing is written to the
board's filesystem. Only the distance checks import the driver, so a problem
with it leaves the other checks working.

To run a check by hand, open the REPL with `just repl`, which mounts this folder
as the current directory, then `from bringup import *` and call the functions
yourself, for example `sensor()` or `live(None)`.

Pins and expected results: ../prototype-assembly.md, sections "Pin assignments"
and "Bring-up tests".
"""

import os
import sys
import time

import machine
from machine import ADC, I2C, Pin

# With this folder mounted as the current directory (`just repl`), its lib/
# holds the sensor driver, as /lib does on the board.
if "lib" not in sys.path:
    sys.path.append("lib")

SCL = 18
SDA = 19
XSHUT = 16
BUZZER = 25
GREEN = 26
RED = 27
BATTERY = 35

SENSOR = 0x29  # I2C address of both the VL53L0X and the VL53L1X
VL53L0X_MODEL_ID_REG = 0xC0  # 8-bit register, reads 0xEE on a VL53L0X
VL53L1X_MODEL_ID_REG = 0x010F  # 16-bit register, reads 0xEACC on a VL53L1X


def board():
    """MicroPython version, chip, CPU frequency and the board's unique ID."""
    uid = machine.unique_id()
    return {
        "release": os.uname().release,
        "machine": os.uname().machine,
        "freq_mhz": machine.freq() // 1_000_000,
        "unique_id": ":".join(f"{b:02x}" for b in uid),
    }


def _xshut(released, pull_up=False):
    # Open-drain: low switches the sensor off, released lets it run. Never drive
    # it high, because the sensor's logic runs at 2.8 V. pull=None removes the
    # pull-up; the stubs don't know that None is allowed.
    pull = Pin.PULL_UP if pull_up else None
    Pin(XSHUT, Pin.OPEN_DRAIN, value=1 if released else 0, pull=pull)  # pyright: ignore[reportArgumentType]
    time.sleep_ms(10)


def _model_id(i2c):
    model_id = i2c.readfrom_mem(SENSOR, VL53L0X_MODEL_ID_REG, 1)[0]
    if model_id == 0xEE:
        return "VL53L0X", model_id
    # Only now try the VL53L1X's 16-bit register: on a VL53L0X, this read
    # would write a byte into its register 0x01.
    raw = i2c.readfrom_mem(SENSOR, VL53L1X_MODEL_ID_REG, 2, addrsize=16)
    model_id = raw[0] << 8 | raw[1]
    return ("VL53L1X" if model_id == 0xEACC else None), model_id


def sensor():
    """Find the sensor on I2C, identify it and switch it off and on with XSHUT."""
    i2c = I2C(0, scl=Pin(SCL), sda=Pin(SDA), freq=400_000)
    _xshut(released=True)
    found = i2c.scan()
    chip = model_id = found_while_off = found_with_pull_up = None
    if SENSOR in found:
        chip, model_id = _model_id(i2c)
        # Switching the sensor off and on also resets it to its defaults.
        _xshut(released=False)
        found_while_off = i2c.scan()
        _xshut(released=True)
    else:
        # Some modules have no pull-up on XSHUT, so releasing it leaves the
        # sensor off. Try again with the ESP32's weak internal pull-up.
        _xshut(released=True, pull_up=True)
        found_with_pull_up = i2c.scan()
        _xshut(released=True)
    return {
        "found": found,
        "chip": chip,
        "model_id": model_id,
        "found_while_off": found_while_off,
        "found_with_pull_up": found_with_pull_up,
    }


def _ranging():
    """The VL53L1X, measuring continuously in short distance mode, 20 times a second."""
    from vl53l1x import VL53L1X

    _xshut(released=True)
    tof = VL53L1X(I2C(0, scl=Pin(SCL), sda=Pin(SDA), freq=400_000), SENSOR)
    tof.configure(short=True, timing_budget_ms=50)
    tof.start()
    return tof


def live(seconds=10):
    """Show the distance on one line as it changes, for `seconds`, or until Ctrl-C with None."""
    from vl53l1x import TOO_CLOSE, VALID, status_text

    tof = _ranging()
    start = time.ticks_ms()
    try:
        while seconds is None or time.ticks_diff(time.ticks_ms(), start) < seconds * 1000:
            distance, status = tof.read()
            if status in TOO_CLOSE:
                text = "closer than 4 cm"
            elif status in VALID:
                text = f"{distance} mm"
            else:
                text = f"no reading ({status_text(status)})"
            print(f"\r  {text:<40}", end="")
    finally:
        tof.stop()
        print()


def clear_view(within_mm, samples=20):
    """Measure with nothing in front of the sensor.

    Returns the number of measurements and the distances of any targets seen
    closer than `within_mm`, including ones too close to measure.
    """
    from vl53l1x import TOO_CLOSE, VALID

    tof = _ranging()
    seen = []
    try:
        for _ in range(samples):
            distance, status = tof.read()
            if status in TOO_CLOSE or (status in VALID and distance < within_mm):
                seen.append(distance)
    finally:
        tof.stop()
    return {"measurements": samples, "seen": seen}


def leds(green, red):
    """Switch the LEDs: 1 = on, 0 = off."""
    Pin(GREEN, Pin.OUT, value=green)
    Pin(RED, Pin.OUT, value=red)


def buzzer(level):
    """Set the buzzer pin to 1 (HIGH) or 0 (LOW), or release it with None.

    On the usual YL-44, LOW sounds the buzzer and HIGH silences it, but some
    modules work the other way round. Release the pin while that's unknown.
    """
    if level is None:
        Pin(BUZZER, Pin.IN)
    else:
        Pin(BUZZER, Pin.OUT, value=level)


def battery(samples=16):
    """Battery voltage in volts, through the optional divider on pin 35."""
    adc = ADC(Pin(BATTERY), atten=ADC.ATTN_11DB)
    total = 0
    for _ in range(samples):
        total += adc.read_uv()
    return total / samples * 2 / 1_000_000


def safe(buzzer_silent=None):
    """Leave the unit quiet and dark. Without the buzzer's silent level, release its pin."""
    leds(0, 0)
    buzzer(buzzer_silent)
