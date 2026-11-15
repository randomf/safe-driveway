"""Show what the firmware prints over USB, without stopping or resetting it.

Usage: python -m host.monitor [--reset] [--seconds N]
"""

import argparse
import sys
import time

import serial

from host import console, ports


def open_port(port: str) -> serial.Serial:
    # DTR and RTS both stay asserted, as the port opens. On these boards, the
    # ESP32's EN pin goes low, and the board resets, only while RTS is asserted
    # without DTR.
    return serial.Serial(port, baudrate=115200, timeout=0.2)


def reset(link: serial.Serial) -> None:
    """Pull EN low and release it: the ESP32 starts as if switched on."""
    link.dtr = False
    time.sleep(0.1)
    link.dtr = True


def main() -> int:
    parser = argparse.ArgumentParser(
        prog="just monitor", description="Show what the firmware prints over USB. Stop with Ctrl-C."
    )
    parser.add_argument(
        "--reset",
        action="store_true",
        help="first reset the board through its EN pin, which the firmware takes for switching on",
    )
    parser.add_argument("--seconds", type=float, help="stop after this many seconds")
    args = parser.parse_args()
    port = ports.require_port()
    try:
        link = open_port(port)
    except serial.SerialException as e:
        console.fail(f"Can't open {port}: {e}. Run `just doctor`.")
    print(f"Output of the board on {port}. Stop with Ctrl-C.", flush=True)
    end = None if args.seconds is None else time.monotonic() + args.seconds
    try:
        with link:
            if args.reset:
                reset(link)
            while end is None or time.monotonic() < end:
                console.stream(link.read(link.in_waiting or 1))
    except KeyboardInterrupt:
        print()
    except serial.SerialException as e:
        console.fail(f"Lost the connection to {port}: {e}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
