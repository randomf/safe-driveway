"""Erase the board and write the pinned MicroPython release, or another firmware image.

Usage: python -m host.flash [--yes] [FIRMWARE]
"""

import argparse
import hashlib
import subprocess
import sys
import urllib.request
from pathlib import Path

from host import board, console, ports, release

# Where the MicroPython image starts on an original ESP32 (not on S2, S3 or C3 chips).
FLASH_OFFSET = "0x1000"
FLASH_BAUD = "460800"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def pinned_firmware(rel: release.Release) -> Path:
    """The pinned image from the cache, downloading it first if needed."""
    path = rel.cache_path
    if not path.exists():
        print(f"Downloading {rel.url}")
        path.parent.mkdir(parents=True, exist_ok=True)
        partial = path.with_suffix(".part")
        try:
            urllib.request.urlretrieve(rel.url, partial)
        except OSError as e:  # URLError and HTTPError are OSErrors
            partial.unlink(missing_ok=True)
            console.fail(
                f"Couldn't download MicroPython ({getattr(e, 'reason', e)}). Check the internet "
                "connection, or pass a firmware file you already have: `just flash FILE`."
            )
        partial.rename(path)
    digest = sha256(path)
    if digest != rel.sha256:
        path.unlink()
        console.fail(
            f"{path.name} has SHA-256 {digest}, but micropython.toml expects {rel.sha256}. "
            "The download was deleted; run `just flash` again. If it fails again, "
            "the file on micropython.org has changed and must be checked by hand."
        )
    return path


def esptool(port: str, *args: str) -> int:
    sys.stdout.flush()
    return subprocess.run(
        [sys.executable, "-m", "esptool", "--port", port, *args], check=False
    ).returncode


def main() -> int:
    parser = argparse.ArgumentParser(
        prog="just flash", description="Erase the board and write MicroPython."
    )
    parser.add_argument("firmware", nargs="?", type=Path, help="image to write instead")
    parser.add_argument("--yes", action="store_true", help="don't ask before erasing")
    args = parser.parse_args()

    rel = release.load()
    port = ports.require_port()

    if args.firmware:
        if not args.firmware.is_file():
            console.fail(f"{args.firmware} doesn't exist.")
        firmware, label = args.firmware, args.firmware.name
    else:
        firmware, label = pinned_firmware(rel), f"MicroPython {rel.version} ({rel.board})"

    print(f"Reading the board on {port}:")
    if esptool(port, "read-mac") != 0:
        console.fail(
            "esptool can't talk to the board. Run `just doctor`. If the port is fine, hold "
            "the board's BOOT button (if it has one) while flashing, or try another USB cable."
        )

    if not args.yes:
        answer = console.ask(f"Erase the whole flash of this board and write {label}?", False)
        if answer is None:
            console.fail("Nobody to confirm erasing the board. Add --yes to flash without asking.")
        if not answer:
            print("Nothing was changed.")
            return 1

    if esptool(port, "erase-flash") != 0:
        console.fail("Erasing failed.")
    if esptool(port, "--baud", FLASH_BAUD, "write-flash", FLASH_OFFSET, str(firmware)) != 0:
        console.fail("Writing failed. Try again; if it keeps failing, try another USB cable.")

    print("Waiting for MicroPython to start…")
    try:
        with board.connect(port, attempts=15) as transport:
            version = board.micropython_version(transport)
    except board.TransportError as e:
        console.fail(f"The board was flashed, but MicroPython didn't answer: {e}")
    print(f"{console.OK} MicroPython {version} is running on {port}.")
    if not args.firmware and version != rel.version:
        print(f"{console.WARN} Expected {rel.version}. Check micropython.toml.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
