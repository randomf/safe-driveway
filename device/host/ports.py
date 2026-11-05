"""Find the serial port the board is connected to."""

import os

from serial.tools import list_ports
from serial.tools.list_ports_common import ListPortInfo

from host import console

# The environment variable that names the port when more than one is connected.
PORT_VARIABLE = "DEVICE_PORT"

# USB-to-serial chips found on ESP32 boards: (vendor ID, product ID) -> (name, Linux driver)
KNOWN_BRIDGES = {
    (0x1A86, 0x7523): ("CH340", "ch341"),
    (0x1A86, 0x55D4): ("CH9102", "cdc_acm"),
    (0x10C4, 0xEA60): ("CP210x", "cp210x"),
    (0x0403, 0x6001): ("FT232R", "ftdi_sio"),
    (0x0403, 0x6015): ("FT231X", "ftdi_sio"),
    (0x303A, 0x1001): ("ESP32 USB-Serial/JTAG", "cdc_acm"),
}


class PortError(Exception):
    pass


def usb_ports() -> list[ListPortInfo]:
    """All serial ports that sit on USB, which rules out the computer's own UARTs."""
    return sorted((p for p in list_ports.comports() if p.vid is not None), key=lambda p: p.device)


def describe(port: ListPortInfo) -> str:
    name = KNOWN_BRIDGES.get((port.vid, port.pid), (port.description, None))[0]
    return f"{port.device} ({name}, {port.vid:04x}:{port.pid:04x})"


def find_port() -> str:
    """The port named in $DEVICE_PORT, or the only USB serial port. Never guesses between several."""
    port = os.environ.get(PORT_VARIABLE)
    if port:
        return port
    ports = usb_ports()
    if len(ports) == 1:
        return ports[0].device
    if not ports:
        raise PortError("No board found on any USB serial port. Run `just doctor` to see why.")
    listing = "\n".join(f"    {describe(p)}" for p in ports)
    raise PortError(
        f"More than one USB serial port found. Choose one with {PORT_VARIABLE}, "
        f"for example `{PORT_VARIABLE}={ports[0].device} just ...`:\n{listing}"
    )


def require_port() -> str:
    """The board's port, or stop the task and say why there is none."""
    try:
        return find_port()
    except PortError as e:
        console.fail(str(e))
