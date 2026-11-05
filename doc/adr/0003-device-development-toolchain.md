# 3. Development toolchain for the device

- **Status:** Accepted
- **Date:** 2026-11-03

## Context

Building a unit of the remote device means flashing MicroPython onto its ESP32 board (see [ADR 0001](0001-prototype-hardware.md)), running code on it, and testing the unit once it's wired. Several people will build units, on Linux, macOS or Windows. They need the same tool versions and the same MicroPython release, and a test that says clearly whether a unit works.

Until now, the bring-up tests in [device/prototype-assembly.md](../../device/prototype-assembly.md) were REPL snippets typed in by hand, with the results judged by eye. Nothing recorded which level sounds the buzzer, which the firmware has to know.

## Decision

- **Python environment:** [uv](https://docs.astral.sh/uv/) manages Python and the host tools (mpremote, esptool, pyserial, ruff) in a project in `device/`, with versions locked in `device/uv.lock`.
- **Tasks:** [just](https://just.systems) runs the tasks in `device/justfile`, from the `device/` folder. It's installed with `uv tool install rust-just`, so uv is the only prerequisite.
- **MicroPython release:** every unit runs the `ESP32_GENERIC` release pinned in `device/micropython.toml`, with the SHA-256 of its image. This is the only place the version is set.
- **Code layout:** code that runs on the computer goes in `device/host/`, code that runs on the board in `device/board/`. The firmware will go in `device/board/firmware/`.
- **Bring-up test:** `just bringup` runs a guided test from the computer. It loads `device/board/bringup.py` into the board's RAM and calls its checks over one raw-REPL session, using mpremote's Python transport classes. It verifies what it can by itself, asks the person at the computer yes/no questions about the LEDs and the buzzer, and works out the buzzer's polarity from the answers. A failed check doesn't stop the run. It ends with a summary and prints the notes the firmware needs, but stores nothing.
- **Host check:** `just doctor` diagnoses the computer's setup, such as the serial driver and port permissions, and prints the fixes without applying them.
- **Editor:** VS Code opens `safe-driveway.code-workspace`, a multi-root workspace with the repository, `device/` and `device/board/` as separate folders. MicroPython type stubs are installed into `device/.stubs/` by `just stubs` and apply only to `device/board/`.
- **Platforms:** setup is documented and tested on Linux. macOS and Windows are documented but untested.

## Alternatives considered

- **Poetry, or tools installed globally with pipx.** Poetry doesn't manage the Python version and is slower. Global installs have no lockfile, so versions drift between contributors.
- **poethepoet, Taskfile or make as the task runner.** poethepoet needs no extra install, but every call is `uv run poe …`. Taskfile adds YAML. make handles arguments poorly and doesn't fit Windows.
- **A `mpremote exec` call per check.** It uses only mpremote's public command line, but each call reconnects and soft-resets the board, taking about a second and losing the board's state between steps.
- **A script that runs on the board by itself.** It needs no host code, but `mpremote run` doesn't pass keyboard input to the board, so it can't ask the person anything. The LED and buzzer checks would have no verdict.
- **Writing the results to a configuration file on the board.** The firmware will need the buzzer's polarity and the battery offset, but writing a file now would fix the firmware's configuration format before the firmware exists.
- **Stubs in the main Python environment.** MicroPython's versions of standard modules would then apply to the host tools too, hiding real errors such as `time.sleep_ms` in code that runs on the computer.
- **A `.vscode/` folder at the repository root.** Editors read Python settings from the root of the opened folder, so the device's settings would have to sit at the repository root.

## Consequences

- A new contributor installs uv and just, runs `just doctor` in `device/` and fixes what it reports.
- mpremote's transport classes are not a documented, stable API. `uv.lock` pins mpremote, and upgrading it needs a `just bringup` run on a real unit.
- Changing the MicroPython release is a reviewed change to one file, and `just stubs` follows it.
- The checks that need a person (LEDs, buzzer, battery) can't run unattended, for example in CI. Power and charging are still checked by hand, because the board can't be tested over USB while USB is unplugged.
- The tasks run only from inside `device/`. The app will get its own toolchain in `app/`.
