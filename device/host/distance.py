"""Show the distance the sensor measures, live, until Ctrl-C."""

import sys

from host import board, console, ports


def main() -> int:
    try:
        port = ports.find_port()
    except ports.PortError as e:
        console.fail(str(e))
    try:
        with board.connect(port) as transport:
            board.load_board_code(transport)
            print("Distance from the sensor in short distance mode. Stop with Ctrl-C.")
            transport.exec_raw_no_follow("live(None)")
            try:
                transport.follow(timeout=None, data_consumer=console.stream)
            except KeyboardInterrupt:
                # Stop the loop on the board too; it stops the sensor on its way out.
                transport.serial.write(b"\x03")
                transport.follow(timeout=5)
                print()
    except board.TransportError as e:
        console.fail(f"The board doesn't respond ({e}). Run `just doctor`.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
