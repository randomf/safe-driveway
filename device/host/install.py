"""Install the firmware on the board, so that it starts whenever the unit is switched on.

Compiles ../board/firmware/ and the sensor driver with mpy-cross, copies them
to /lib on the board, writes a /main.py that starts the firmware and restarts
the board. The setup and the bring-up notes on the board stay as they are.

Usage: python -m host.install
"""

import argparse
import importlib.metadata
import re
import subprocess
import sys
import tempfile
from pathlib import Path

from host import board, console, ports, release

FIRMWARE_DIR = board.BOARD_DIR / "firmware"
DRIVER = board.BOARD_DIR / "lib" / "vl53l1x.py"

MAIN = """\
# Written by `just install`: starts the Safe Driveway firmware in /lib/firmware.
import firmware.main

firmware.main.run()
"""


def firmware_version() -> str:
    source = (FIRMWARE_DIR / "main.py").read_text()
    match = re.search(r"FIRMWARE_VERSION = \((\d+), (\d+), (\d+)\)", source)
    return ".".join(match.groups()) if match else "unknown"


def check_mpy_cross(pinned: str) -> None:
    """mpy-cross has to match the MicroPython release, or the board can't load what it writes."""
    version = importlib.metadata.version("mpy-cross")
    if not (version == pinned or version.startswith(f"{pinned}.")):
        console.fail(
            f"mpy-cross {version} doesn't match MicroPython {pinned} in micropython.toml. "
            f'Run `uv lock --upgrade-package "mpy-cross=={pinned}.*"`, '
            "see README.md#changing-the-micropython-version."
        )


def compile_to(source: Path, target: Path, name: str) -> None:
    command = [sys.executable, "-m", "mpy_cross", "-s", name, "-o", str(target), str(source)]
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        console.fail(f"mpy-cross couldn't compile {name}:\n{result.stderr}")


def compiled(out: Path) -> dict[str, bytes]:
    """The board path and contents of every compiled file."""
    files = {f"lib/firmware/{p.stem}.mpy": p for p in sorted(FIRMWARE_DIR.glob("*.py"))}
    files["lib/vl53l1x.mpy"] = DRIVER
    for path, source in files.items():
        target = out / path.replace("/", "_")
        compile_to(source, target, path.removesuffix(".mpy").removeprefix("lib/") + ".py")
    return {path: (out / path.replace("/", "_")).read_bytes() for path in files}


def install(transport: board.SerialTransport, files: dict[str, bytes]) -> None:
    for directory in ("/lib", "/lib/firmware"):
        if not transport.fs_exists(directory):
            transport.fs_mkdir(directory)
    # Files of an older firmware that this one no longer has.
    wanted = {f"/{path}" for path in files}
    for name in transport.fs_listdir("/lib/firmware"):
        path = f"/lib/firmware/{name.name}"
        if path not in wanted:
            transport.fs_rmfile(path)
    for path, data in files.items():
        print(f"  /{path}")
        transport.fs_writefile(f"/{path}", data)
    transport.fs_writefile("/main.py", MAIN.encode())
    print("  /main.py")


def main() -> int:
    parser = argparse.ArgumentParser(
        prog="just install", description="Install the firmware and restart the board."
    )
    parser.parse_args()
    pinned = release.load().version
    check_mpy_cross(pinned)
    port = ports.require_port()
    version = firmware_version()
    with tempfile.TemporaryDirectory() as out:
        files = compiled(Path(out))
    print(f"Installing firmware {version} on {port}")
    try:
        with board.connect(port, attempts=3) as transport:
            running = board.micropython_version(transport)
            if running != pinned:
                console.fail(
                    f"The board runs MicroPython {running}, not {pinned}: run `just flash`."
                )
            install(transport, files)
            # Loading the modules checks that the board can run what mpy-cross wrote.
            board.run(transport, "import firmware.main")
            transport.exec_raw_no_follow("import machine; machine.reset()")
    except board.TransportExecError as e:
        console.fail(f"The board can't load the firmware: {board.error_line(e)}")
    except board.TransportError as e:
        board.fail_unresponsive(e)
    print(f"{console.OK} Installed. The board restarted; watch it with `just monitor`.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
