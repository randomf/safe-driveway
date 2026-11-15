# 10. Firmware of the remote device

- **Status:** Accepted
- **Date:** 2026-11-15

## Context

A unit runs MicroPython on its ESP32 ([ADR 0003](0003-device-development-toolchain.md)), measures with a VL53L1X through the driver of [ADR 0005](0005-vl53l1x-driver.md) and talks to the app over the BLE protocol of [ADR 0007](0007-ble-protocol.md), which leaves the unit's blink and beep patterns to the firmware. [ADR 0001](0001-prototype-hardware.md) gave the green LED a "ready" state and the red LED and the buzzer the gap, without saying how they look or sound.

The firmware has to:

- implement the whole protocol: advertising, the six characteristics, the setup record with its PIN, lockout and saving, Identify, Release and the link-loss rule,
- show a driver what the unit is doing with two LEDs and a buzzer, at a glance and from the driver's seat,
- know each unit's hardware: which level silences its buzzer, and whether it has the battery divider and how far that reads off. The bring-up test finds these, but ADR 0003 had it store nothing, because the firmware didn't exist yet,
- be tested, although only some of it can run on a computer, and the app can't talk to real units yet.

## Decision

**Structure.** The firmware is the package `device/board/firmware/`, a functional core and an imperative shell. The core, in `protocol.py`, `record.py`, `control.py`, `sensing.py` and `patterns.py`, is pure and uses no MicroPython modules: byte layouts, the passage record, the PIN and the control point commands, what a reading means, and what the LEDs and the buzzer show at a given moment. The shell, `main.py`, owns the pins, the sensor, Bluetooth, the files and the clock, on MicroPython's asyncio: one task each for the readings, the lights, the Bluetooth events and a once-a-second refresh. It feeds the core and carries out what the core returns.

**Lights and sounds.** Red and green are never on together. In order of precedence:

| State | Green | Red and buzzer |
|---|---|---|
| Identify, for 5 s | Alternates with red at 4 Hz | Alternates with green, a beep once a second |
| Gap below the critical gap, or *too close* | Off | Solid, continuous tone |
| Gap below the warning gap | Off | An 80 ms flash with a beep; the period shortens with the gap, from 1000 ms at the warning gap to 160 ms at the critical gap |
| A car in front: a *valid* or *too close* reading in the last second | Solid | Off |
| No car, measuring: a phone connected, the first 10 minutes after starting, or 5 minutes after a lost link | 2 flashes every 3 s | Off |
| No car, not measuring | 1 flash every 3 s | Off |

Green tells the state by its number of flashes, which are 100 ms long and 200 ms apart. A sensor fault replaces the count with 3 flashes, and a lockout with 4, the fault first. During the PIN reset window the flashes are 400 ms long. Low battery adds a 50 ms red flash 300 ms after the green group. When the firmware starts, green and then red light for 200 ms each. The red LED and the buzzer signal the gap only on an assigned, locked unit whose placement has bit 3 set, as the protocol says.

A new warning flash starts once the period for the latest gap has passed since the last one began. A gap that changes, or jitters, moves the next flash but never cuts one short, and a gap that keeps crossing the warning gap can't make red flash with every reading.

**Holding the signal.** The signal keeps the last *valid* or *too close* reading for up to 0.5 s of readings with any other status, the same 0.5 s after which the app shows no data. A dark or glossy car that drops out for a few readings doesn't make the warning stutter.

**Battery.** A divider reading below 2.5 V means that no battery is connected: the unit runs from USB with its switch off. The unit then reports 255, as without the divider, and no low battery. The level in % comes from a table of Li-Po voltages.

**Bring-up saves its notes.** At the end of `just bringup`, the test offers to save the buzzer's silent level, whether the battery divider is fitted and its offset from the multimeter to `/unit.json` on the board, over any notes saved before. The firmware reads them when it starts. Without the file it takes a low-triggered buzzer and no divider. This supersedes ADR 0003's decision that bring-up stores nothing.

**Switched on.** The firmware takes a power-on reset, as `machine.reset_cause()` reports it, for switching on. That opens the PIN reset window and clears the wrong-PIN count, which the firmware keeps in RTC memory, so that restarts keep it. The ESP32's EN pin, which the board's reset button and the USB chip pull low, and a brownout give a power-on reset too.

**Bluetooth details.** The firmware answers a control point command as soon as it arrives, so its answer goes out ahead of the readings that follow. Its preferred ATT MTU stays at MicroPython's 256 bytes. While no phone is connected, it starts advertising again every 2 s.

**Installing.** `just install` compiles the firmware and the driver with mpy-cross, which uv.lock pins to the MicroPython release, copies them to `/lib` on the board and writes a `/main.py` that starts the firmware. After a crash the firmware prints the error and restarts the board 5 s later; Ctrl-C stops it.

**Developing and testing.**

- The tests of the core run on the computer with pytest, `just test`, and in CI: the protocol's examples byte for byte, the record's validity rules, every command, the signal and the patterns.
- `just monitor` shows the firmware's log over USB: a line for every event and, while measuring, a summary every second.
- `just ble` acts as the phone through the computer's Bluetooth, with bleak and the core's own byte layouts. It scans, watches readings, sets up a unit as the only unit of a passage, identifies, tries PINs, sets a PIN and forgets.

**No watchdog** for now.

## Alternatives considered

- **Everything in one module on the board.** Less code to load, but nothing could be tested off the board, and the host tools would need their own copy of every byte layout.
- **Testing on the board, or in MicroPython's Unix port.** It's the real runtime, but needs the board connected, or a MicroPython build in CI. The core uses nothing CPython lacks, so the tests run in the environment uv already sets up.
- **A main loop instead of asyncio.** Fewer moving parts, but the sensor's polling, the 10 ms lights, the events from Bluetooth and the 1 s refresh would share one hand-written schedule.
- **Telling the states apart by how often green flashes,** once a second while a phone is connected and once every 3 s without, with double and triple flashes for faults. It was the first design, tried on the first unit: without both side by side, a flash every second was hard to tell from one every 3 s. A number of flashes can be counted at a glance.
- **Green filling red's dark phases during a warning.** The unit is never dark, but at a fast rate the LEDs flicker between colours and look like Identify, and the red rhythm is harder to read.
- **A blink rate in steps, or with equal on and off times.** Steps jump between rates; equal times make the slow end a long, lazy blink. A fixed short flash with a shorter pause is what parking sensors do.
- **A fixed range of raw distance for the warning.** It would ignore the thresholds and the zero offset the passage sets in the app, so the unit and the phone would warn at different gaps.
- **The buzzer only below the critical gap.** Quieter, but with no sound before the critical gap.
- **Following the protocol's letter on dropouts.** Each non-valid reading would stop the signal at once, and the warning would stutter on dark cars.
- **Reporting 0 % with no battery.** Units on a bench, switched off and on USB, would show low battery all the time.
- **Writing the notes from `just install`, or detecting them.** Install would have to ask for what bring-up already knows. A floating pin 35 can read like a divider, and a high-triggered buzzer would need a code change.
- **A watchdog.** On the ESP32, MicroPython can't stop the watchdog once started, so it would reset the board in the middle of `just bringup`, `just repl` or `just install` after they interrupt the firmware.
- **Plain `.py` files on the board.** Nothing to compile, but the board would compile the firmware at every start, slower and with less RAM left next to the Bluetooth stack.
- **nRF Connect or a similar app as the test phone.** Nothing to write, but a record or a PIN typed in hex, by hand, every time.

## Consequences

- **The protocol spec changes in two places, compatibly:** the 0.5 s hold, and 255 also meaning a battery that isn't connected. Trying the first unit also changed when a unit measures: for the first 10 minutes after it starts and for 5 minutes after a lost link, with or without a phone. ADR 0007 and the spec are updated in place for that, since no app talked to a unit yet. A brownout and the reset button count as switching on, which ADR 0007 and the spec are updated for: MicroPython can't tell a brownout from a power-on, so it opens the PIN reset window and clears the wrong-PIN count. Someone who can make a unit brown out can as well switch it off and on.
- **Units need their notes.** A unit with a high-triggered buzzer sounds it whenever it should be silent until bring-up has saved the notes, as happened on the first unit. `just flash` erases them, and bring-up has to save them again.
- **The dev tasks stop the firmware.** `just bringup`, `just distance`, `just repl` and `just install` interrupt it. It starts again when the board is reset or switched off and on; `just install` restarts the board by itself.
- **Verified on the first unit:** the readings at 20 per second with none lost, a 269-byte record written as a long write and read back byte for byte at the MTU of 256, Unlock, Set PIN, Commit, Lock and Release, lockout surviving a restart and cleared by switching on, Set PIN in the PIN reset window, measuring without a phone in the first 10 minutes, and every light and sound but the sensor fault, locked out and low battery shapes, which only the tests cover. The 5 minutes after a lost link were tried only with the earlier rule, which ended when the car had gone; the tests cover the new one. A connection that failed to be established left the unit silently not advertising until the 2 s restart of advertising was added.
- **No watchdog:** a unit that hangs stays hung until it is switched off and on. A crash restarts it.
- **Battery levels are estimates** from a generic Li-Po table, measured under the load of the radio.
- **The core runs on two runtimes.** It may use only what both CPython and MicroPython have: no `namedtuple._replace`, no `zip(strict=...)`, no `itertools`. The tests run it on CPython only.
