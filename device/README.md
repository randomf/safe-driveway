# Remote device

This folder holds everything needed to build the remote device that is installed at the passage: how to wire and assemble it, and the tools to flash and test it from a computer.

| Path | What it is |
|---|---|
| [prototype-assembly.md](prototype-assembly.md) | Parts, wiring, assembly and bring-up tests of the prototype unit |
| [justfile](justfile) | The tasks listed under [Tasks](#tasks) |
| [host/](host/) | Code that runs on the computer: flashing, `doctor` and the guided bring-up test |
| [board/](board/) | Code that runs on the board, in MicroPython: the bring-up checks, and in [board/lib/](board/lib/) libraries such as the VL53L1X sensor driver. The firmware will go in `board/firmware/` |
| [micropython.toml](micropython.toml) | The MicroPython release that every unit runs |
| `pyproject.toml`, `uv.lock`, `.python-version` | The Python environment for the code in `host/` |

Why the tools were chosen is recorded in [ADR 0003](../doc/adr/0003-device-development-toolchain.md).

## Setup

You need a computer with Linux, macOS or Windows, and a USB cable that carries data, not a charge-only one. Linux is tested (Pop!_OS 24.04); macOS and Windows should work but are untested.

1. Install [uv](https://docs.astral.sh/uv/getting-started/installation/). If you already have pipx, `pipx install uv` works too.
2. Install [just](https://just.systems) through uv:

   ```bash
   uv tool install rust-just
   ```

   If your shell then can't find `just`, run `uv tool update-shell` and open a new terminal.
3. Plug in the board, go to this folder and run the checks:

   ```bash
   cd device
   just doctor
   ```

   The first run creates the Python environment in `device/.venv`, with the versions of mpremote, esptool and ruff pinned in `uv.lock`. `doctor` then checks the tools, finds the board's serial port, checks that you may open it and asks the board for its MicroPython version. It says how to fix anything that's wrong, and never changes your system itself.
4. If you'll edit code: run `just stubs` and open [safe-driveway.code-workspace](../safe-driveway.code-workspace). See [Editing the code](#editing-the-code).

### Serial port on Linux

The board's USB chip is a CH340, which the `ch341` kernel driver handles. With the board plugged in, it shows up as `/dev/ttyUSB0`.

- **You can't open the port.** It belongs to the `dialout` group (`uucp` on some distributions; `doctor` names the right one). Join it with `sudo usermod -aG dialout $USER`, then log out and back in.
- **There is no `/dev/ttyUSB0`, but `lsusb` lists a "QinHeng Electronics CH340".** The driver isn't loaded. Run `sudo modprobe ch341` and replug the board.
- **`modprobe` fails with `Invalid argument`.** The kernel was updated since you started the computer, and the running kernel rejects the new driver files. Restart the computer.
- **brltty has claimed the port.** On some Ubuntu-based systems, brltty (braille display support) takes over CH340 chips. If `doctor` reports this and you don't use a braille display, run `sudo apt remove brltty` and replug the board.

### Serial port on macOS (untested)

macOS may already have a driver for the CH340; the port then appears as `/dev/cu.usbserial-…` or `/dev/cu.wchusbserial…`. If no port appears, install the CH34x driver from the chip maker, [WCH](https://www.wch-ic.com/downloads/CH34XSER_MAC_ZIP.html).

### Serial port on Windows (untested)

Windows usually installs the CH340 driver by itself through Windows Update. If the board doesn't show up under *Ports (COM & LPT)* in Device Manager, install the driver from [WCH](https://www.wch-ic.com/downloads/CH341SER_EXE.html). The tasks run in PowerShell, where you choose a port with `$env:DEVICE_PORT = "COM5"`. A firmware file for `just flash` can't have spaces in its path there.

## Tasks

Run the tasks from the `device/` folder or any folder below it. `just` on its own lists them.

| Task | What it does |
|---|---|
| `just doctor` | Checks that this computer is ready to work with the board, and says how to fix what isn't |
| `just flash` | Shows the board's chip and MAC address, asks before erasing it, writes the MicroPython release from [micropython.toml](micropython.toml) and checks that it starts. `--yes` skips the question; a file name writes that image instead |
| `just repl` | Opens the MicroPython REPL on the board, with `board/` mounted as the current directory, so that `from bringup import *` loads the checks and they find the sensor driver. Leave it with Ctrl-] or Ctrl-X |
| `just distance` | Shows the distance the sensor measures, live, until Ctrl-C |
| `just bringup` | Runs the guided bring-up test, see [Testing a unit](#testing-a-unit). Name checks to run only those: `board`, `sensor`, `distance`, `view`, `leds`, `buzzer`, `battery` |
| `just stubs` | Installs the MicroPython type stubs that editors use for the code in `board/` |
| `just lint` | Checks the code with ruff, without changing anything |
| `just fmt` | Formats the code and fixes what ruff can fix by itself |

The serial port is found automatically. When more than one USB serial port is connected, the tasks stop and list them; choose one with `DEVICE_PORT`:

```bash
DEVICE_PORT=/dev/ttyUSB1 just bringup
```

## Testing a unit

- **A new board, before any soldering** (step 1 of [the assembly](prototype-assembly.md#1-bench-check-the-board)): `just flash`, then `just bringup board`.
- **While wiring:** test each part as soon as it's connected, for example `just bringup sensor` or `just bringup leds buzzer`.
- **The finished unit** (step 9): `just bringup`, then the power and charging checks by hand.

The board and sensor checks run by themselves. For the distance readings and the sensor's clear view, the run asks you to move your hand or aim the unit; for the LEDs and the buzzer, it asks what you see and hear. It goes on after a failed check, and ends with a summary, any notes the firmware needs (such as the buzzer's polarity) and the checks still to do by hand. The exit code is non-zero if any check failed.

```
Bring-up test on /dev/ttyUSB0

✓ board    MicroPython 1.29.0 on Generic ESP32 module with ESP32, 160 MHz, ID 08:ad:0a:5b:9f:b8
✓ sensor   VL53L1X at 0x29, model ID 0xeacc, XSHUT switches it off and on
  leds     Watch the LEDs on wall A.
  Is the green LED on and the red LED off? [y/n] y
  …
```

What each check expects, and what to do when one fails, is in [Bring-up tests](prototype-assembly.md#bring-up-tests). The checks themselves are in [board/bringup.py](board/bringup.py), and the code that runs them from the computer is in [host/bringup.py](host/bringup.py). The run sends the checks and the sensor driver into the board's RAM, so nothing is written to the board's filesystem.

## Editing the code

Open [safe-driveway.code-workspace](../safe-driveway.code-workspace) in VS Code (*File > Open Workspace from File…*) and install the recommended extensions. The workspace has three folders, so that each kind of code gets the right checks:

- **Safe Driveway**: the documentation.
- **device**: the host tools, checked against normal Python in `device/.venv`.
- **device/board (MicroPython)**: the board code, checked against the MicroPython stubs. Run `just stubs` first.

Other editors that use pyright or basedpyright read the same settings: open `device/` for the host tools and `device/board/` for the board code. Run `just lint` before committing.

## Changing the MicroPython version

Every unit runs the release pinned in [micropython.toml](micropython.toml). To change it:

1. Find the release on [micropython.org/download/ESP32_GENERIC](https://micropython.org/download/ESP32_GENERIC/). Its file is named `ESP32_GENERIC-<date>-v<version>.bin`.
2. Download it and compute its SHA-256 (`sha256sum`, or `shasum -a 256` on macOS):

   ```bash
   curl -LO https://micropython.org/resources/firmware/ESP32_GENERIC-<date>-v<version>.bin
   ```

3. Set `version`, `date` and `sha256` in `micropython.toml`.
4. Update mpremote to the matching version with `uv lock --upgrade-package mpremote`.
5. Run `just stubs`, then `just flash` and `just bringup` on a test unit.
6. Commit `micropython.toml` and `uv.lock` together.

To update the other host tools, run `uv lock --upgrade`, test with `just doctor` and `just bringup`, and commit `uv.lock`.
