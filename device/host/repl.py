"""Open the MicroPython REPL on the board, with ../board/ mounted. Leave with Ctrl-] or Ctrl-X.

In the REPL, `from bringup import *` loads the bring-up checks; they find the
sensor driver in the mounted lib/ folder, also after a soft reset with Ctrl-D.
"""

import subprocess
import sys

from host import board, ports


def main() -> int:
    port = ports.require_port()
    command = ["connect", port, "mount", str(board.BOARD_DIR), "repl"]
    return subprocess.run([sys.executable, "-m", "mpremote", *command], check=False).returncode


if __name__ == "__main__":
    sys.exit(main())
