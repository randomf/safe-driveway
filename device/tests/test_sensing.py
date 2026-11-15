"""What the unit makes of its readings and its battery."""

import pytest

from firmware import protocol as p
from firmware import sensing
from firmware.sensing import CONTINUOUS, Sight

WARNING = 150
CRITICAL = 50
ZERO = 60


def signal_at(distance, status=p.VALID, age=0):
    return sensing.signal(Sight(1000, distance, status), 1000 + age, WARNING, CRITICAL, ZERO)


def test_seen_keeps_the_last_target():
    sight = sensing.seen(None, p.VALID, 200, now=10)
    assert sight == Sight(10, 200, p.VALID)
    assert sensing.seen(sight, p.UNRELIABLE, 0, now=20) is sight
    assert sensing.seen(sight, p.TOO_CLOSE, 30, now=30) == Sight(30, 30, p.TOO_CLOSE)


def test_no_signal_at_or_above_the_warning_gap():
    assert signal_at(ZERO + WARNING) is None
    assert signal_at(1200) is None


def test_warning_gets_faster_as_the_gap_closes():
    periods = [signal_at(ZERO + gap) for gap in (149, 125, 100, 75, 50)]
    assert periods[0] == 991
    assert periods[-1] == sensing.FASTEST_PERIOD_MS
    assert periods == sorted(periods, reverse=True)
    assert len(set(periods)) == len(periods)


def test_continuous_below_the_critical_gap_and_too_close():
    assert signal_at(ZERO + 49) == CONTINUOUS
    assert signal_at(ZERO - 10) == CONTINUOUS
    assert signal_at(31, p.TOO_CLOSE) == CONTINUOUS


def test_the_signal_holds_for_half_a_second():
    assert signal_at(ZERO + 100, age=500) is not None
    assert signal_at(ZERO + 100, age=501) is None
    assert sensing.signal(None, 0, WARNING, CRITICAL, ZERO) is None


def test_car_in_front_for_a_second_after_a_target():
    sight = Sight(0, 300, p.VALID)
    assert sensing.car_in_front(sight, 999)
    assert not sensing.car_in_front(sight, 1000)
    assert not sensing.car_in_front(None, 0)


def test_measures_for_ten_minutes_after_starting():
    assert sensing.open_windows(0, None, 599_999) == (0, None)
    assert sensing.open_windows(0, None, 600_000) == (None, None)


def test_measures_for_five_minutes_after_a_lost_link():
    assert sensing.open_windows(None, 1_000, 300_999) == (None, 1_000)
    assert sensing.open_windows(None, 1_000, 301_000) == (None, None)


def test_the_windows_close_independently():
    assert sensing.open_windows(0, 500_000, 700_000) == (None, 500_000)
    assert sensing.open_windows(0, 1_000, 400_000) == (0, None)


@pytest.mark.parametrize(
    ("volts", "percent"),
    [(0.0, 255), (2.49, 255), (2.5, 0), (3.3, 0), (3.73, 20), (3.75, 25), (4.2, 100), (4.35, 100)],
)
def test_battery_percent(volts, percent):
    assert sensing.battery_percent(volts) == percent


def test_low_battery():
    assert sensing.low_battery(19)
    assert not sensing.low_battery(20)
    assert not sensing.low_battery(p.NO_BATTERY)
