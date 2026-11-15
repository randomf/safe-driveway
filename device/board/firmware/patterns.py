"""What the green LED, the red LED and the buzzer show: ../../firmware.md, section "Lights and sounds".

The shell calls `show` about every 10 ms. Blinks are anchored to the start of
their last flash, kept in a Beat, so that a new flash starts once the current
period has passed. A gap that changes, or jitters, between readings changes
when the next flash comes, but never cuts one short.
"""

from collections import namedtuple

from firmware import protocol
from firmware.sensing import CONTINUOUS, SLOWEST_PERIOD_MS
from firmware.ticks import ticks_diff

IDENTIFY_MS = 5000

POWER_ON_MS = 200  # green, then red, when the firmware starts

WARNING_FLASH_MS = 80

# With no car in front, green flashes a group every HEARTBEAT_MS. The number
# of flashes tells what the unit does, or what is wrong with it.
HEARTBEAT_MS = 3000
IDLE_FLASHES = 1  # not measuring: the sensor is off
MEASURING_FLASHES = 2  # measuring, nothing in front
SENSOR_FAULT_FLASHES = 3
LOCKED_OUT_FLASHES = 4

# Flashes are short, or long during the PIN reset window, with a gap between them.
SHORT_FLASH_MS = 100
LONG_FLASH_MS = 400
FLASH_GAP_MS = 200

# Low battery adds a red flash this long after the green group ends.
LOW_BATTERY_DELAY_MS = 300
LOW_BATTERY_FLASH_MS = 50

# What the lights do, for the log.
POWER_ON = "power-on"
IDENTIFY = "identify"
CRITICAL = "critical"
WARNING = "warning"
CAR = "car in front"
MEASURING = "measuring"
IDLE = "not measuring"

# When the last warning flash and the last heartbeat started, the heartbeat's
# shape, as (on, off) times in ms from its start, and whether it shows low
# battery. None for a flash long gone.
Beat = namedtuple("Beat", ("warning_at", "heartbeat_at", "shape", "low_battery"))

START = Beat(None, None, (), False)


def heartbeat_shape(flags, measuring):
    """The green group: its number of flashes, and how long they are."""
    if flags & protocol.SENSOR_FAULT:
        count = SENSOR_FAULT_FLASHES
    elif flags & protocol.LOCKED_OUT:
        count = LOCKED_OUT_FLASHES
    else:
        count = MEASURING_FLASHES if measuring else IDLE_FLASHES
    length = LONG_FLASH_MS if flags & protocol.PIN_RESET_WINDOW else SHORT_FLASH_MS
    step = length + FLASH_GAP_MS
    return tuple((n * step, n * step + length) for n in range(count))


def _lit(shape, elapsed):
    return any(on <= elapsed < off for on, off in shape)


def _due(at, now, period):
    return at is None or ticks_diff(now, at) >= period


def _forget_old(beat, now):
    # Forgets an anchor once even the slowest blink would be due, so that a
    # long pause can't wrap the board's ticks around it.
    warning_at = None if _due(beat.warning_at, now, SLOWEST_PERIOD_MS) else beat.warning_at
    heartbeat_at = beat.heartbeat_at
    if _due(heartbeat_at, now, HEARTBEAT_MS):
        heartbeat_at = None
    if warning_at is beat.warning_at and heartbeat_at is beat.heartbeat_at:
        return beat
    return Beat(warning_at, heartbeat_at, beat.shape, beat.low_battery)


def _warning(beat, now, period):
    if _due(beat.warning_at, now, period):
        beat = Beat(now, beat.heartbeat_at, beat.shape, beat.low_battery)
    return beat, ticks_diff(now, beat.warning_at) < WARNING_FLASH_MS


def _heartbeat(beat, now, flags, measuring):
    if _due(beat.heartbeat_at, now, HEARTBEAT_MS):
        low = bool(flags & protocol.LOW_BATTERY)
        beat = Beat(beat.warning_at, now, heartbeat_shape(flags, measuring), low)
    elapsed = ticks_diff(now, beat.heartbeat_at)
    green = _lit(beat.shape, elapsed)
    red_from = beat.shape[-1][1] + LOW_BATTERY_DELAY_MS
    red = beat.low_battery and red_from <= elapsed < red_from + LOW_BATTERY_FLASH_MS
    return beat, green, red


def show(beat, now, uptime, identify, signal, car, flags, measuring):
    """(Beat, what the lights do, green, red, buzzer) at `now`.

    `uptime` is the time since the firmware started, `identify` the time since
    the last Identify or None, `signal` the gap signal from sensing.signal, or
    None while the unit doesn't signal, `car` whether a car is in front, and
    `measuring` whether the sensor runs.
    """
    if uptime < 2 * POWER_ON_MS:
        green = uptime < POWER_ON_MS
        return beat, POWER_ON, green, not green, False
    if identify is not None and identify < IDENTIFY_MS:
        green = identify % 250 < 125
        beep = 125 <= identify % 1000 < 225
        return beat, IDENTIFY, green, not green, beep
    beat = _forget_old(beat, now)
    if signal == CONTINUOUS:
        return beat, CRITICAL, False, True, True
    if signal is not None:
        beat, on = _warning(beat, now, signal)
        return beat, WARNING, False, on, on
    if car:
        return beat, CAR, True, False, False
    beat, green, red = _heartbeat(beat, now, flags, measuring)
    return beat, MEASURING if measuring else IDLE, green, red, False
