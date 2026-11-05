"""A session with MicroPython's raw REPL on the board, through mpremote."""

import io
import time
from collections.abc import Iterator
from contextlib import contextmanager, redirect_stdout

from mpremote.transport import TransportError, TransportExecError
from mpremote.transport_serial import SerialTransport

from host import release

__all__ = [
    "BOARD_DIR",
    "SerialTransport",
    "TransportError",
    "TransportExecError",
    "connect",
    "load_board_code",
    "micropython_version",
]

BOARD_DIR = release.DEVICE_DIR / "board"

# Run on the board after mounting BOARD_DIR, which mpremote mounts at /remote.
# Puts its lib/ on the import path, as /lib is on the board's own.
LIB_PATH = "import sys; sys.path.append('/remote/lib')"


def _open(port: str, attempts: int) -> SerialTransport:
    while True:
        attempts -= 1
        transport = None
        try:
            transport = SerialTransport(port, baudrate=115200)
            # mpremote prints the raw bytes it got when this fails; the caller explains instead.
            with redirect_stdout(io.StringIO()):
                transport.enter_raw_repl(soft_reset=True)
            return transport
        except TransportError:
            if transport is not None:
                transport.close()
            if attempts <= 0:
                raise
            time.sleep(1)


@contextmanager
def connect(port: str, attempts: int = 1) -> Iterator[SerialTransport]:
    """Soft-reset the board and open its raw REPL.

    Retries once a second when `attempts` > 1, for a board that is still booting.
    """
    transport = _open(port, attempts)
    try:
        yield transport
    finally:
        try:
            if transport.mounted:
                transport.umount_local()
            transport.exit_raw_repl()
        finally:
            transport.close()


def load_board_code(transport: SerialTransport) -> None:
    """Mount ../board/ on the board and import its bring-up checks into RAM."""
    transport.mount_local(str(BOARD_DIR))
    transport.exec(f"{LIB_PATH}\nfrom bringup import *")


def micropython_version(transport: SerialTransport) -> str:
    return str(transport.eval("__import__('os').uname().release"))
