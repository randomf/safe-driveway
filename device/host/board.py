"""A session with MicroPython's raw REPL on the board, through mpremote."""

import ast
import io
import time
from collections.abc import Callable, Iterator
from contextlib import contextmanager, redirect_stdout, suppress
from typing import Any, NoReturn

from mpremote.transport import TransportError, TransportExecError
from mpremote.transport_serial import SerialTransport

from host import console, release

__all__ = [
    "BOARD_DIR",
    "SerialTransport",
    "TransportError",
    "TransportExecError",
    "connect",
    "error_line",
    "evaluate",
    "fail_unresponsive",
    "load_board_code",
    "micropython_version",
    "run",
]

BOARD_DIR = release.DEVICE_DIR / "board"

# MicroPython can't create module objects, but `import` takes whatever
# sys.modules holds, and `from module import name` only looks up attributes.
_LOADER = """
import sys
class _Module:
    pass
def _load(name, source):
    namespace = {"__name__": name}
    exec(source, namespace)
    module = _Module()
    for key, value in namespace.items():
        setattr(module, key, value)
    sys.modules[name] = module
"""


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
        # Best effort: the next session soft-resets the board anyway, and a
        # failure here mustn't hide the error that ended this one.
        with suppress(TransportError, OSError):
            transport.exit_raw_repl()
        transport.close()


def run(
    transport: SerialTransport,
    code: str,
    data_consumer: Callable[[bytes], None] | None = None,
    timeout: float | None = 10,
) -> bytes:
    """Run code on the board and return what it printed.

    `timeout` is how long the board may stay silent. Raises TransportExecError
    when the code raises. Ctrl-C on the computer stops the code on the board
    too, so that the board is ready for the next command.
    """
    try:
        transport.exec_raw_no_follow(code)
        out, err = transport.follow(timeout, data_consumer)
    except KeyboardInterrupt:
        transport.serial.write(b"\x03")
        with suppress(TransportError):
            transport.follow(timeout=5)
        raise
    if err:
        raise TransportExecError(out, err.decode())
    return out


def evaluate(transport: SerialTransport, expression: str) -> Any:
    """The value of a Python expression on the board, for values `repr` can rebuild."""
    return ast.literal_eval(run(transport, f"print(repr({expression}))").decode())


def load_board_code(transport: SerialTransport) -> None:
    """Load the sensor driver and the bring-up checks into the board's RAM.

    The driver becomes an importable module, and the checks become names in the
    session, the same as `from bringup import *`. Sending the source this way
    is faster than mounting ../board/, whose imports read small chunks over USB.
    """
    run(transport, _LOADER)
    driver = (BOARD_DIR / "lib" / "vl53l1x.py").read_text()
    run(transport, f"_load('vl53l1x', {driver!r})")
    run(transport, (BOARD_DIR / "bringup.py").read_text())


def error_line(error: TransportExecError) -> str:
    """The last line of the board's traceback: the exception and its message."""
    lines = error.error_output.strip().splitlines()
    return lines[-1] if lines else "an error"


def fail_unresponsive(error: TransportError) -> NoReturn:
    console.fail(f"The board doesn't respond ({error}). Run `just doctor`.")


def micropython_version(transport: SerialTransport) -> str:
    return str(evaluate(transport, "__import__('os').uname().release"))
