"""The setup, the PIN, locking and the control point commands.

See ../../../doc/ble-protocol.md, sections "Control point", "PIN and locking"
and "Saving". The shell keeps an Access value, passes every control point
command to `command` and carries out the effect it returns.
"""

from collections import namedtuple

from firmware import protocol, record

MAX_WRONG_PINS = 5
PIN_RESET_WINDOW_MS = 120_000

# What `command` asks the shell to do. Saving or forgetting can fail; the shell
# then answers SAVE_FAILED and keeps the Access it had.
SAVE = "save"
FORGET = "forget"
IDENTIFY = "identify"

# The committed setup: the record byte for byte and parsed, this unit's entry
# in it, and the PIN. A unit that belongs to no passage has no record and no
# entry, and may already have a PIN.
Setup = namedtuple("Setup", ("raw", "record", "entry", "pin"))

UNASSIGNED = Setup(b"", None, None, b"")

# The setup, what the current connection has done, and the wrong PINs in a row.
# `unlocked` is the unlock of an assigned unit; an unassigned one is always unlocked.
Access = namedtuple("Access", ("setup", "unlocked", "staged", "released", "wrong_pins"))

# The setup file: format, PIN length, PIN, then the record.
_FILE_FORMAT = 1


def setup_of(raw, pin, unit_id):
    """The setup for a record and a PIN, unassigned unless the record is valid and lists this unit."""
    parsed = record.parse(raw) if raw else None
    entry = record.entry_for(parsed, unit_id) if parsed else None
    if entry is None:
        return Setup(b"", None, None, pin)
    return Setup(bytes(raw), parsed, entry, pin)


def stored(setup):
    """The contents of the setup file."""
    return bytes((_FILE_FORMAT, len(setup.pin))) + setup.pin + setup.raw


def restored(data, unit_id):
    """The setup in a setup file, or UNASSIGNED if the file can't be read."""
    if len(data) < 2 or data[0] != _FILE_FORMAT or 2 + data[1] > len(data):
        return UNASSIGNED
    pin = bytes(data[2 : 2 + data[1]])
    if pin and not valid_pin(pin):
        return UNASSIGNED
    return setup_of(bytes(data[2 + data[1] :]), pin, unit_id)


def valid_pin(pin):
    return 4 <= len(pin) <= 8 and all(0x30 <= digit <= 0x39 for digit in pin)


def assigned(access):
    return access.setup.entry is not None


def unlocked(access):
    return access.unlocked or not assigned(access)


def locked_out(access):
    return access.wrong_pins >= MAX_WRONG_PINS


def signals(access):
    """Whether the unit's own buzzer and red LED signal the gap now."""
    return assigned(access) and access.setup.entry.signals and not unlocked(access)


def flags(access, connected, pin_reset_window):
    """The flags this module knows: in use, unlocked, PIN reset window and locked out."""
    return (
        (protocol.IN_USE if connected else 0)
        | (protocol.UNLOCKED if connected and unlocked(access) else 0)
        | (protocol.PIN_RESET_WINDOW if pin_reset_window else 0)
        | (protocol.LOCKED_OUT if locked_out(access) else 0)
    )


def started(setup, wrong_pins):
    """The Access of a unit that has just started, with no phone connected."""
    return Access(setup, False, b"", False, wrong_pins)


def session_ended(access):
    """A new connection, or the end of one: locked, with nothing staged and no Release."""
    return Access(access.setup, False, b"", False, access.wrong_pins)


def staged(access, data):
    """A record written to the setup staging characteristic, kept until Commit."""
    return Access(access.setup, access.unlocked, bytes(data), access.released, access.wrong_pins)


def _unlock(access, pin):
    if not assigned(access):
        return access, protocol.DONE, None
    if locked_out(access):
        return access, protocol.LOCKED_OUT_RESULT, None
    if not valid_pin(pin):
        return access, protocol.INVALID_PARAMETERS, None
    if pin == access.setup.pin:
        return Access(access.setup, True, access.staged, access.released, 0), protocol.DONE, None
    wrong_pins = access.wrong_pins + 1
    result = protocol.LOCKED_OUT_RESULT if wrong_pins >= MAX_WRONG_PINS else protocol.WRONG_PIN
    return Access(access.setup, False, access.staged, access.released, wrong_pins), result, None


def _set_pin(access, pin, pin_reset_window):
    if not (unlocked(access) or pin_reset_window):
        return access, protocol.NOT_ALLOWED, None
    if not valid_pin(pin):
        return access, protocol.INVALID_PARAMETERS, None
    setup = Setup(access.setup.raw, access.setup.record, access.setup.entry, pin)
    return (
        Access(setup, True, access.staged, access.released, access.wrong_pins),
        protocol.DONE,
        SAVE,
    )


def _commit(access, unit_id):
    if not unlocked(access) or not access.setup.pin:
        return access, protocol.NOT_ALLOWED, None
    parsed = record.parse(access.staged) if access.staged else None
    if parsed is None:
        return access, protocol.INVALID_PARAMETERS, None
    entry = record.entry_for(parsed, unit_id)
    if entry is None:
        return access, protocol.NOT_LISTED, None
    setup = Setup(access.staged, parsed, entry, access.setup.pin)
    return Access(setup, False, b"", access.released, access.wrong_pins), protocol.DONE, SAVE


def _forget(access):
    if not unlocked(access):
        return access, protocol.NOT_ALLOWED, None
    return Access(UNASSIGNED, False, b"", access.released, access.wrong_pins), protocol.DONE, FORGET


def _lock(access):
    return Access(access.setup, False, access.staged, access.released, access.wrong_pins)


def _release(access):
    return Access(access.setup, access.unlocked, access.staged, True, access.wrong_pins)


def command(access, data, unit_id, pin_reset_window):
    """Carry out a control point command: (new Access, result code, effect or None).

    The new Access holds only once the shell has carried out the effect.
    """
    opcode = data[0] if data else 0
    parameters = bytes(data[1:])
    if opcode == protocol.UNLOCK:
        return _unlock(access, parameters)
    if opcode == protocol.SET_PIN:
        return _set_pin(access, parameters, pin_reset_window)
    if opcode == protocol.COMMIT:
        return _commit(access, unit_id)
    if opcode == protocol.IDENTIFY:
        return access, protocol.DONE, IDENTIFY
    if opcode == protocol.FORGET:
        return _forget(access)
    if opcode == protocol.LOCK:
        return _lock(access), protocol.DONE, None
    if opcode == protocol.RELEASE:
        return _release(access), protocol.DONE, None
    return access, protocol.UNKNOWN_OPCODE, None
