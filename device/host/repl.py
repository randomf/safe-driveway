"""Open the MicroPython REPL on the board. Leave it with Ctrl-] or Ctrl-X."""

import subprocess
import sys

from host import console, ports


def main() -> int:
    try:
        port = ports.find_port()
    except ports.PortError as e:
        console.fail(str(e))
    return subprocess.run(
        [sys.executable, "-m", "mpremote", "connect", port, "repl"], check=False
    ).returncode


if __name__ == "__main__":
    sys.exit(main())
