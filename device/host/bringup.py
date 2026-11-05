"""Guided bring-up test of a prototype unit.

Runs the checks in ../board/bringup.py on the board. It verifies what it can by
itself and asks the person at the computer about what only they can see or
hear. What each check expects is described in ../prototype-assembly.md, section
"Bring-up tests".

Usage: python -m host.bringup [CHECK ...]
"""

import argparse
import sys
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from host import board, console, ports, release

BOARD_CODE = release.DEVICE_DIR / "board" / "bringup.py"
DOC = "prototype-assembly.md"

# The distance sensor in the parts list, see ../../doc/adr/0004-vl53l1x-distance-sensor.md.
EXPECTED_SENSOR = "VL53L1X"


@dataclass
class Result:
    status: str
    detail: str
    hints: tuple[str, ...] = ()
    note: str = ""  # something the firmware has to know about this unit


class Unit:
    """The board, with ../board/bringup.py loaded into its RAM."""

    def __init__(self, transport: board.SerialTransport, named: set[str]) -> None:
        self.transport = transport
        self.named = named  # the checks the person asked for by name
        self.buzzer_silent = 1

    def call(self, expression: str) -> Any:
        return self.transport.eval(expression)


def ok(detail: str, note: str = "") -> Result:
    return Result(console.OK, detail, note=note)


def failed(detail: str, *hints: str) -> Result:
    return Result(console.FAIL, detail, hints)


def nobody() -> Result:
    return Result(console.SKIP, "needs a person at the computer to answer")


def check_board(unit: Unit) -> Result:
    info = unit.call("board()")
    detail = (
        f"MicroPython {info['release']} on {info['machine']}, {info['freq_mhz']} MHz, "
        f"ID {info['unique_id']}"
    )
    pinned = release.load().version
    if info["release"] != pinned:
        detail += f". MicroPython {pinned} is pinned: run `just flash` to update"
    return ok(detail)


def check_sensor(unit: Unit) -> Result:
    r = unit.call("sensor()")
    if 0x29 not in r["found"]:
        if 0x29 in (r["found_with_pull_up"] or []):
            return failed(
                "The sensor answers only with a pull-up on XSHUT: the module has none.",
                "Add a 10 kΩ resistor from XSHUT to 3V.",
            )
        if r["found"]:
            found = ", ".join(f"0x{a:02x}" for a in r["found"])
            return failed(f"Found {found} on I2C, but nothing at 0x29.")
        return failed(
            "Nothing answers on I2C.",
            "Check 3V, GND, and whether SCL (pin 18) and SDA (pin 19) are swapped.",
        )
    if r["found_while_off"]:
        return failed(
            "XSHUT doesn't switch the sensor off.", "Check the wire from pin 16 to XSHUT."
        )
    if r["chip"] != EXPECTED_SENSOR:
        chip = r["chip"] or f"an unknown device (model ID 0x{r['model_id']:02x})"
        return failed(
            f"The sensor at 0x29 is a {chip}, not the {EXPECTED_SENSOR} in the parts list. "
            "Its wiring and XSHUT work.",
            "Its registers and driver differ, so the firmware has to match the fitted sensor.",
        )
    return ok(f"{r['chip']} at 0x29, model ID 0x{r['model_id']:02x}, XSHUT switches it off and on")


LED_STEPS = [
    ("1, 0", "Is the green LED on and the red LED off?", "the green LED"),
    ("0, 1", "Is the red LED on and the green LED off?", "the red LED"),
    ("1, 1", "Are both LEDs on, about equally bright?", "both LEDs together"),
]


def check_leds(unit: Unit) -> Result:
    wrong = []
    try:
        for levels, question, what in LED_STEPS:
            unit.call(f"leds({levels})")
            answer = console.ask(question)
            if answer is None:
                return nobody()
            if not answer:
                wrong.append(what)
    finally:
        unit.call("leds(0, 0)")
    if not wrong:
        return ok("both LEDs switch on and off")
    return failed(
        f"Wrong: {', '.join(wrong)}.",
        "Check the wires to pins 26 (green) and 27 (red), the 220 Ω resistors, and that each "
        "LED's long leg (anode) is on the resistor side.",
        "If the green LED is much dimmer than the red one, swap its resistor for 100 Ω.",
    )


def check_buzzer(unit: Unit) -> Result:
    try:
        unit.call("buzzer(1)")
        high = console.ask("The buzzer pin is HIGH now. Is the buzzer sounding?")
        if high is None:
            return nobody()
        unit.call("buzzer(0)")
        low = console.ask("The buzzer pin is LOW now. Is the buzzer sounding?")
        if low is None:
            return nobody()
    finally:
        unit.call("buzzer(1)")
    if low and not high:
        return ok(
            "low-triggered, the usual YL-44",
            "Buzzer is low-triggered: LOW sounds it, HIGH silences it.",
        )
    if high and not low:
        unit.buzzer_silent = 0
        unit.call("buzzer(0)")
        return ok(
            "high-triggered variant",
            "Buzzer is high-triggered: HIGH sounds it, LOW silences it.",
        )
    if high and low:
        return failed(
            "The buzzer sounds at both levels.",
            "Power the YL-44 from 3V, never from USB 5 V or the battery.",
        )
    return failed(
        "The buzzer stays silent.",
        "Check the YL-44's VCC (splice S1), GND (splice S3) and I/O (pin 25).",
    )


def check_battery(unit: Unit) -> Result:
    if "battery" not in unit.named:
        fitted = console.ask("Is the optional battery divider fitted?", default=False)
        if fitted is None:
            return nobody()
        if not fitted:
            return Result(console.SKIP, "no battery divider fitted")
    if not console.interactive():
        return nobody()
    input("  Connect the battery and switch the unit on, then press Enter. ")
    volts = unit.call("battery()")
    if volts < 0.5:
        return failed(
            "Pin 35 reads about 0 V.",
            "Is the unit switched on? The divider's top must go to the switch's LED + terminal.",
        )
    if not 2.9 <= volts <= 4.3:
        return failed(f"{volts:.2f} V is outside 3.0–4.2 V.", "Check both 100 kΩ resistors.")
    detail = f"{volts:.2f} V"
    reading = input("  Your multimeter reading across the battery in V (Enter to skip): ")
    try:
        offset = volts - float(reading.replace(",", "."))
    except ValueError:
        return ok(detail)
    return ok(
        f"{detail}, offset {offset:+.2f} V from the multimeter",
        f"Battery reading is {offset:+.2f} V off the multimeter.",
    )


# name: (check, section of DOC, what to do before it starts)
CHECKS: dict[str, tuple[Callable[[Unit], Result], str, str]] = {
    "board": (check_board, "board", ""),
    "sensor": (check_sensor, "laser-sensor", ""),
    "leds": (check_leds, "leds", "Watch the LEDs on wall A."),
    "buzzer": (check_buzzer, "buzzer", "Listen to the buzzer."),
    "battery": (check_battery, "battery-voltage-optional", ""),
}


def show(name: str, anchor: str, result: Result) -> None:
    print(f"{result.status} {name:<8} {result.detail}")
    if result.status == console.FAIL:
        for hint in (*result.hints, f"See {DOC}#{anchor}"):
            print(f"  {'':<8} {hint}")


def run(unit: Unit, names: list[str]) -> list[tuple[str, Result]]:
    results = []
    for name in names:
        check, anchor, intro = CHECKS[name]
        if intro and console.interactive():
            print(f"  {name:<8} {intro}")
        try:
            result = check(unit)
        except board.TransportExecError as e:
            result = failed(f"The board raised {e.error_output.strip().splitlines()[-1]}")
        show(name, anchor, result)
        results.append((name, result))
    return results


def summarize(results: list[tuple[str, Result]], all_checks: bool) -> int:
    counts = {
        s: sum(r.status == s for _, r in results) for s in (console.OK, console.FAIL, console.SKIP)
    }
    print(
        f"\n{counts[console.OK]} passed, {counts[console.FAIL]} failed, "
        f"{counts[console.SKIP]} skipped."
    )
    notes = [r.note for _, r in results if r.note]
    if notes:
        print("Note for the firmware:")
        for note in notes:
            print(f"  - {note}")
    if all_checks:
        print(f"Still to check by hand: power and charging, see {DOC}#power-and-charging")
    return 1 if counts[console.FAIL] else 0


def main() -> int:
    parser = argparse.ArgumentParser(
        prog="just bringup", description="Guided bring-up test of a prototype unit."
    )
    parser.add_argument("checks", nargs="*", metavar="CHECK", help=", ".join(CHECKS))
    args = parser.parse_args()
    unknown = set(args.checks) - set(CHECKS)
    if unknown:
        parser.error(f"unknown check {', '.join(sorted(unknown))}; choose from {', '.join(CHECKS)}")
    # The board check always runs first: nothing else works without the board.
    names = ["board", *(n for n in args.checks or CHECKS if n != "board")]

    try:
        port = ports.find_port()
    except ports.PortError as e:
        console.fail(str(e))
    print(f"Bring-up test on {port}\n")
    try:
        with board.connect(port) as transport:
            transport.exec(BOARD_CODE.read_bytes())
            unit = Unit(transport, named=set(args.checks))
            try:
                results = run(unit, names)
            finally:
                try:
                    unit.call(f"safe({unit.buzzer_silent})")
                except (board.TransportError, OSError):
                    print(
                        f"{console.WARN} Couldn't reset the pins. Press the board's reset button."
                    )
    except board.TransportError as e:
        console.fail(f"The board doesn't respond ({e}). Run `just doctor`.")
    except KeyboardInterrupt:
        print("\nStopped.")
        return 1
    return summarize(results, all_checks=not args.checks)


if __name__ == "__main__":
    sys.exit(main())
