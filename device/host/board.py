"""A session with MicroPython's raw REPL on the board, through mpremote."""

import io
import time
from collections.abc import Iterator
from contextlib import contextmanager, redirect_stdout

from mpremote.transport import TransportError, TransportExecError
from mpremote.transport_serial import SerialTransport

__all__ = [
    "SerialTransport",
    "TransportError",
    "TransportExecError",
    "connect",
    "micropython_version",
]


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
            transport.exit_raw_repl()
        finally:
            transport.close()


def micropython_version(transport: SerialTransport) -> str:
    return str(transport.eval("__import__('os').uname().release"))
