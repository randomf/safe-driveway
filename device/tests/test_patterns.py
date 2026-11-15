"""What the LEDs and the buzzer show."""

from firmware import patterns
from firmware import protocol as p
from firmware.patterns import (
    CAR,
    CRITICAL,
    IDENTIFY,
    IDLE,
    MEASURING,
    POWER_ON,
    START,
    WARNING,
)
from firmware.sensing import CONTINUOUS

RUNNING = 10_000  # an uptime past the power-on lights


def play(frames, start=0, step=10, beat=START, uptime=RUNNING):
    """Show `frames` (identify, signal, car, flags, measuring), one per step.

    Returns [(mode, green, red, buzzer)].
    """
    shown = []
    for index, (identify, signal, car, flags, measuring) in enumerate(frames):
        now = start + index * step
        beat, mode, green, red, buzzer = patterns.show(
            beat, now, uptime + index * step, identify, signal, car, flags, measuring
        )
        shown.append((mode, green, red, buzzer))
    return shown


def steady(count, signal=None, car=False, flags=0, measuring=False):
    return [(None, signal, car, flags, measuring)] * count


def lit_ms(shown, index, step=10):
    """The times, in ms from the start, at which output `index` is on."""
    return [frame * step for frame, outputs in enumerate(shown) if outputs[index]]


def green_flashes(shown, step=10):
    """The green flashes as (start, length) in ms."""
    lit = set(lit_ms(shown, 1, step))
    starts = sorted(time for time in lit if time - step not in lit)
    return [
        (start, next(end for end in range(start, start + 10_000, step) if end not in lit) - start)
        for start in starts
    ]


def test_power_on_shows_green_then_red():
    shown = play(steady(45), uptime=0)
    assert shown[0] == (POWER_ON, True, False, False)
    assert shown[25] == (POWER_ON, False, True, False)
    assert shown[40][0] == IDLE


def test_identify_alternates_and_beeps_once_a_second():
    shown = play([(t, None, False, 0, True) for t in range(0, 5000, 10)])
    assert {mode for mode, *_ in shown} == {IDENTIFY}
    assert all(green != red for _, green, red, _ in shown)
    beeps = lit_ms(shown, 3)
    assert beeps[:10] == list(range(130, 230, 10))
    assert len(beeps) == 5 * 10


def test_identify_ends_after_five_seconds():
    assert play([(5000, None, False, 0, False)])[0][0] == IDLE


def test_critical_is_solid_red_with_a_tone():
    assert play(steady(1, CONTINUOUS)) == [(CRITICAL, False, True, True)]


def test_warning_flashes_red_with_a_beep():
    shown = play(steady(100, signal=500))
    assert {mode for mode, *_ in shown} == {WARNING}
    assert lit_ms(shown, 2) == list(range(0, 80, 10)) + list(range(500, 580, 10))
    assert lit_ms(shown, 3) == lit_ms(shown, 2)
    assert not lit_ms(shown, 1)


def test_a_closer_car_brings_the_next_flash_forward():
    frames = steady(30, signal=1000) + steady(30, signal=200)
    assert lit_ms(play(frames), 2)[8:16] == list(range(300, 380, 10))


def test_flickering_at_the_warning_gap_doesnt_flash_every_reading():
    # The gap jumps above and below the warning gap with every reading.
    frames = [(None, 990 if frame % 5 < 3 else None, True, 0, True) for frame in range(100)]
    red = lit_ms(play(frames), 2)
    assert red[0] == 0
    assert all(0 < time < 80 or time >= 990 for time in red[1:])


def test_a_car_in_front_is_solid_green():
    assert play(steady(1, car=True)) == [(CAR, True, False, False)]


def test_one_flash_every_three_seconds_while_not_measuring():
    shown = play(steady(700))
    assert {mode for mode, *_ in shown} == {IDLE}
    assert green_flashes(shown) == [(0, 100), (3000, 100), (6000, 100)]


def test_two_flashes_while_measuring_with_nothing_in_front():
    shown = play(steady(400, measuring=True))
    assert {mode for mode, *_ in shown} == {MEASURING}
    assert green_flashes(shown) == [(0, 100), (300, 100), (3000, 100), (3300, 100)]


def test_a_new_state_shows_from_the_next_group():
    shown = play(steady(100) + steady(300, measuring=True))
    assert green_flashes(shown) == [(0, 100), (3000, 100), (3300, 100)]


def test_faults_replace_the_count():
    def flashes(flags):
        return green_flashes(play(steady(250, flags=flags, measuring=True)))

    assert flashes(p.SENSOR_FAULT) == [(0, 100), (300, 100), (600, 100)]
    assert flashes(p.LOCKED_OUT) == [(0, 100), (300, 100), (600, 100), (900, 100)]
    assert flashes(p.SENSOR_FAULT | p.LOCKED_OUT) == flashes(p.SENSOR_FAULT)


def test_the_pin_reset_window_makes_the_flashes_long():
    def flashes(flags, measuring):
        return green_flashes(play(steady(250, flags=flags, measuring=measuring)))

    assert flashes(p.PIN_RESET_WINDOW, measuring=False) == [(0, 400)]
    assert flashes(p.PIN_RESET_WINDOW, measuring=True) == [(0, 400), (600, 400)]
    assert flashes(p.PIN_RESET_WINDOW | p.SENSOR_FAULT, measuring=True) == [
        (0, 400),
        (600, 400),
        (1200, 400),
    ]


def test_low_battery_adds_a_red_flash_after_the_green_group():
    shown = play(steady(100, flags=p.LOW_BATTERY, measuring=True))
    assert lit_ms(shown, 2) == [700, 710, 720, 730, 740]
    assert not lit_ms(shown, 3)


def test_every_group_fits_in_three_seconds():
    flags = p.LOCKED_OUT | p.PIN_RESET_WINDOW | p.LOW_BATTERY
    shown = play(steady(300, flags=flags))
    assert green_flashes(shown)[-1] == (1800, 400)
    assert lit_ms(shown, 2)[-1] < patterns.HEARTBEAT_MS


def test_red_and_green_are_never_on_together():
    frames = (
        steady(50, flags=p.LOW_BATTERY)
        + steady(50, car=True)
        + steady(100, signal=300, car=True)
        + steady(10, signal=CONTINUOUS)
        + steady(400, flags=p.LOW_BATTERY, measuring=True)
    )
    assert not any(green and red for _, green, red, _ in play(frames))


def test_old_anchors_are_forgotten():
    beat = patterns.Beat(0, 0, ((0, 100),), False)
    beat, *_ = patterns.show(beat, 5000, RUNNING, None, None, True, 0, True)
    assert (beat.warning_at, beat.heartbeat_at) == (None, None)
