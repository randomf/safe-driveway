"""Bring-up checks for a prototype unit. Runs on the board, in MicroPython.

`just bringup` sends this file to the board's RAM, calls the functions below one
at a time and asks the person at the computer about what only they can see or
hear. Nothing is written to the board's filesystem.

To run a check by hand, open the REPL with `just repl`, press Ctrl-E, paste this
file, press Ctrl-D, and call the functions yourself, for example `sensor()`.

Pins and expected results: ../prototype-assembly.md, sections "Pin assignments"
and "Bring-up tests".
"""

import os
import time

import machine
from machine import ADC, I2C, Pin

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
