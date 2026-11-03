"""Check that this computer is ready to flash and test the board, and say how to fix what isn't."""

import os
import shutil
import sys
from importlib.metadata import version
from pathlib import Path

from host import board, console, ports, release

LINUX = sys.platform.startswith("linux")
failures = 0


def report(symbol: str, message: str, *fixes: str) -> None:
    global failures
    if symbol == console.FAIL:
        failures += 1
    print(f"{symbol} {message}")
    for fix in fixes:
        print(f"    {fix}")


def check_tools() -> None:
    for tool in ("uv", "just"):
        if shutil.which(tool):
            report(console.OK, f"{tool} is installed")
        else:
            report(console.FAIL, f"{tool} isn't on PATH", "See README.md, section Setup.")
    report(console.OK, f"mpremote {version('mpremote')} and esptool {version('esptool')} are ready")


def unbound_bridges_linux() -> list[tuple[str, str, str]]:
    """USB-serial chips that are plugged in but have no serial driver: (name, driver, bound to)."""
    found = []
    for device in Path("/sys/bus/usb/devices").glob("*"):
        try:
            ids = (
                int((device / "idVendor").read_text(), 16),
                int((device / "idProduct").read_text(), 16),
            )
        except (OSError, ValueError):
            continue
        if ids not in ports.KNOWN_BRIDGES:
            continue
        name, driver = ports.KNOWN_BRIDGES[ids]
        for interface in device.glob("*:*"):
            bound = (interface / "driver").resolve().name if (interface / "driver").exists() else ""
            if bound != driver:
                found.append((name, driver, bound))
    return found


def brltty_running() -> bool:
    return any(comm.read_text().strip() == "brltty" for comm in Path("/proc").glob("[0-9]*/comm"))


def explain_missing_port() -> None:
    if LINUX:
        for name, driver, bound in unbound_bridges_linux():
            if bound == "usbfs" and brltty_running():
                report(
                    console.FAIL,
                    f"A {name} is plugged in, but brltty (braille display support) has claimed it",
                    "If you don't use a braille display: `sudo apt remove brltty`, then replug the board.",
                )
            else:
                report(
                    console.FAIL,
                    f"A {name} is plugged in, but the `{driver}` serial driver isn't attached to it",
                    f"Load the driver: `sudo modprobe {driver}`, then replug the board.",
                    "If modprobe says `Invalid argument`, the kernel was updated since you started "
                    "the computer. Restart it.",
                )
            return
    report(
        console.FAIL,
        "No board found on USB",
        "Check that the board is plugged in and its power LED is on.",
        "Use a cable that carries data, not a charge-only one. If a USB-C to USB-C cable doesn't "
        "work, try USB-A to USB-C.",
        "On macOS and Windows, see README.md for the CH340 driver.",
    )


def check_access(port: str) -> bool:
    if os.name != "posix" or os.access(port, os.R_OK | os.W_OK):
        return True
    import grp
    import pwd

    group = grp.getgrgid(os.stat(port).st_gid)
    user = pwd.getpwuid(os.getuid()).pw_name
    if user in group.gr_mem:
        report(
            console.FAIL,
            f"You were added to the `{group.gr_name}` group, but this login doesn't have it yet",
            "Log out and back in, or restart the computer.",
        )
    else:
        report(
            console.FAIL,
            f"You can't open {port}: it belongs to the `{group.gr_name}` group",
            f"Join the group: `sudo usermod -aG {group.gr_name} $USER`, then log out and back in.",
        )
    return False


def check_port() -> str | None:
    try:
        port = ports.find_port()
    except ports.PortError as e:
        if ports.usb_ports():
            report(console.FAIL, str(e))
        else:
            explain_missing_port()
        return None
    if not Path(port).exists() and os.name == "posix":
        report(console.FAIL, f"{port} (from PORT) doesn't exist")
        return None
    report(console.OK, f"Board found on {port}")
    return port if check_access(port) else None


def check_micropython(port: str) -> None:
    pinned = release.load().version
    try:
        with board.connect(port) as transport:
            running = board.micropython_version(transport)
    except board.TransportError as e:
        if "failed to access" in str(e):
            report(
                console.FAIL,
                f"{port} can't be opened",
                "Close any other program using it (a REPL, a serial monitor) and try again.",
            )
        else:
            report(
                console.WARN,
                "MicroPython doesn't answer on the board",
                "A new board needs `just flash` first.",
            )
        return
    if running == pinned:
        report(console.OK, f"MicroPython {running} is running on the board")
    else:
        report(
            console.WARN,
            f"The board runs MicroPython {running}, but {pinned} is pinned",
            "Run `just flash` to update it.",
        )


def check_stubs() -> None:
    pinned = release.load().version
    if list(release.STUBS_DIR.glob(f"micropython_esp32_stubs-{pinned}*.dist-info")):
        report(console.OK, f"Editor stubs for MicroPython {pinned} are installed")
    else:
        report(
            console.WARN,
            f"Editor stubs for MicroPython {pinned} are missing",
            "Run `just stubs` if you edit board code.",
        )


def main() -> int:
    check_tools()
    port = check_port()
    if port:
        check_micropython(port)
    check_stubs()
    if failures:
        print(f"\n{failures} problem(s) to fix before working with the board.")
        return 1
    print("\nReady.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
