"""Show the distance the sensor measures, live, until Ctrl-C."""

import sys

from host import board, console, ports


def main() -> int:
    port = ports.require_port()
    try:
        with board.connect(port) as transport:
            board.load_board_code(transport)
            print("Distance from the sensor in short distance mode. Stop with Ctrl-C.")
            board.run(transport, "live(None)", data_consumer=console.stream, timeout=None)
    except KeyboardInterrupt:
        print()
        return 0
    except board.TransportExecError as e:
        console.fail(
            f"The board raised {board.error_line(e)}. Run `just bringup sensor` to check the sensor."
        )
    except board.TransportError as e:
        board.fail_unresponsive(e)
    return 0


if __name__ == "__main__":
    sys.exit(main())
