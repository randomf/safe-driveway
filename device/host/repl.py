"""Open the MicroPython REPL on the board, with ../board/ mounted. Leave with Ctrl-] or Ctrl-X.

In the REPL, `from bringup import *` loads the bring-up checks and the sensor driver.
"""

import subprocess
import sys

from host import board, console, ports


def main() -> int:
    try:
        port = ports.find_port()
    except ports.PortError as e:
        console.fail(str(e))
    command = ["connect", port, "mount", str(board.BOARD_DIR), "exec", board.LIB_PATH, "repl"]
    return subprocess.run([sys.executable, "-m", "mpremote", *command], check=False).returncode


if __name__ == "__main__":
    sys.exit(main())
