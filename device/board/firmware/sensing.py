"""What the unit makes of its readings and its battery, and when its sensor runs.

Times are ticks in ms: time.ticks_ms() on the board, plain numbers in the tests.
"""

from collections import namedtuple

from firmware import protocol
from firmware.ticks import ticks_diff

# A target seen this recently means a car in front: solid green.
CAR_HOLD_MS = 1000

# The signal keeps the last valid gap through other readings for this long.
SIGNAL_HOLD_MS = 500

# The warning blinks once per period: slowest at the warning gap, fastest just
# above the critical gap. Below it, the signal is continuous.
SLOWEST_PERIOD_MS = 1000
FASTEST_PERIOD_MS = 160
CONTINUOUS = 0

# Without a phone, the sensor runs for the first START_WINDOW_MS after the
# firmware starts, and for LINK_LOSS_MS after a link was lost without Release,
# so that a unit warns with no phone, or with a flaky link.
START_WINDOW_MS = 600_000
LINK_LOSS_MS = 300_000

# The last valid or too close reading: when it came, its distance and status.
Sight = namedtuple("Sight", ("at", "distance", "status"))

# Li-Po open-circuit voltage to charge in %, from empty to full.
_LIPO = (
    (3.30, 0),
    (3.61, 5),
    (3.69, 10),
    (3.73, 20),
    (3.77, 30),
    (3.80, 40),
    (3.84, 50),
    (3.87, 60),
    (3.95, 70),
    (4.02, 80),
    (4.11, 90),
    (4.20, 100),
)

# Below this, pin 35 sees no battery: the unit runs from USB with its switch off.
NO_BATTERY_BELOW_V = 2.5

LOW_BATTERY_PERCENT = 20


def seen(sight, status, distance, now):
    """The last target after a reading."""
    if status == protocol.VALID or status == protocol.TOO_CLOSE:
        return Sight(now, distance, status)
    return sight


def since_target(sight, now):
    """Milliseconds since the last target, or None if there was none."""
    return None if sight is None else ticks_diff(now, sight.at)


def car_in_front(sight, now):
    return sight is not None and ticks_diff(now, sight.at) < CAR_HOLD_MS


def signal(sight, now, warning_gap, critical_gap, zero_offset):
    """The gap signal: None, CONTINUOUS below the critical gap, or the warning's period in ms."""
    if sight is None or ticks_diff(now, sight.at) > SIGNAL_HOLD_MS:
        return None
    if sight.status == protocol.TOO_CLOSE:
        return CONTINUOUS
    gap = sight.distance - zero_offset
    if gap < critical_gap:
        return CONTINUOUS
    if gap >= warning_gap:
        return None
    span = SLOWEST_PERIOD_MS - FASTEST_PERIOD_MS
    return FASTEST_PERIOD_MS + span * (gap - critical_gap) // (warning_gap - critical_gap)


def open_windows(started_at, lost_at, now):
    """The times measuring without a phone started, for the windows still open at `now`.

    `started_at` is when the firmware started and `lost_at` when the link was
    lost; each is None once its window has closed, and stays so.
    """
    if started_at is not None and ticks_diff(now, started_at) >= START_WINDOW_MS:
        started_at = None
    if lost_at is not None and ticks_diff(now, lost_at) >= LINK_LOSS_MS:
        lost_at = None
    return started_at, lost_at


def battery_percent(volts):
    """The battery level in %, or NO_BATTERY when pin 35 sees no battery."""
    if volts < NO_BATTERY_BELOW_V:
        return protocol.NO_BATTERY
    if volts <= _LIPO[0][0]:
        return 0
    if volts >= _LIPO[-1][0]:
        return 100
    (low_v, low_percent), (high_v, high_percent) = next(
        pair for pair in zip(_LIPO, _LIPO[1:]) if pair[0][0] <= volts < pair[1][0]
    )
    return low_percent + int((volts - low_v) / (high_v - low_v) * (high_percent - low_percent))


def low_battery(percent):
    return percent != protocol.NO_BATTERY and percent < LOW_BATTERY_PERCENT
