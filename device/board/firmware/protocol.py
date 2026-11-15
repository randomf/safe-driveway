"""Byte layouts and values of the BLE protocol, version 1: ../../../doc/ble-protocol.md.

The firmware encodes what a unit sends; the host tools decode it and encode
what a phone sends. The passage record is in record.py.
"""

import struct

VERSION = 1

SERVICE_UUID = "9f050001-a55b-4698-ae01-042514884edf"


def _characteristic_uuid(number):
    # The service's UUID with the last 4 hex digits of its first group changed.
    return f"9f05{number:04x}{SERVICE_UUID[8:]}"


UNIT_INFO_UUID = _characteristic_uuid(2)
STATUS_UUID = _characteristic_uuid(3)
READING_UUID = _characteristic_uuid(4)
SETUP_UUID = _characteristic_uuid(5)
SETUP_STAGING_UUID = _characteristic_uuid(6)
CONTROL_POINT_UUID = _characteristic_uuid(7)

# Reserved by the Bluetooth SIG for testing; replaced before any public release (ADR 0007).
COMPANY_ID = 0xFFFF

# The flags byte, in the advertisement and the status characteristic.
IN_USE = 0x01
UNLOCKED = 0x02
PIN_RESET_WINDOW = 0x04
LOCKED_OUT = 0x08
LOW_BATTERY = 0x10
SENSOR_FAULT = 0x20

# Battery level when the unit has no battery reading.
NO_BATTERY = 255

# Reading statuses.
VALID = 0
TOO_CLOSE = 1
NO_TARGET = 2
UNRELIABLE = 3
FAULT = 4

STATUS_NAMES = ("valid", "too close", "no target", "unreliable", "sensor fault")

# Control point opcodes.
UNLOCK = 0x01
SET_PIN = 0x02
COMMIT = 0x03
IDENTIFY = 0x04
FORGET = 0x05
LOCK = 0x06
RELEASE = 0x07

COMMAND_NAMES = {
    UNLOCK: "Unlock",
    SET_PIN: "Set PIN",
    COMMIT: "Commit",
    IDENTIFY: "Identify",
    FORGET: "Forget",
    LOCK: "Lock",
    RELEASE: "Release",
}

# Control point results.
DONE = 0x00
UNKNOWN_OPCODE = 0x01
INVALID_PARAMETERS = 0x02
NOT_ALLOWED = 0x03
WRONG_PIN = 0x04
LOCKED_OUT_RESULT = 0x05
NOT_LISTED = 0x06
SAVE_FAILED = 0x07

RESULT_NAMES = (
    "done",
    "unknown opcode",
    "invalid parameters",
    "not allowed",
    "wrong PIN",
    "locked out",
    "not listed",
    "save failed",
)

# How long after a valid or too close reading a signal failure counts as unreliable.
UNRELIABLE_AFTER_TARGET_MS = 1000

_SERVICE_ON_AIR = bytes(reversed(bytes.fromhex(SERVICE_UUID.replace("-", ""))))


def status_name(status):
    return STATUS_NAMES[status] if status < len(STATUS_NAMES) else f"status {status}"


def result_name(result):
    return RESULT_NAMES[result] if result < len(RESULT_NAMES) else f"result {result}"


def short_id(unit_id):
    """The last 4 hex digits of the unit ID, in upper case: 9FB8."""
    return unit_id[-2:].hex().upper()


def unassigned_name(unit_id):
    """The name of a unit that belongs to no passage: b"Safe Driveway 9FB8"."""
    return b"Safe Driveway " + short_id(unit_id).encode()


def manufacturer_payload(gate, side, flags, passage_id):
    return struct.pack("<BBI", VERSION << 4 | side << 2 | gate, flags, passage_id)


def decode_manufacturer_payload(payload):
    """(protocol version, gate, side, flags, passage ID) from the 6-byte payload."""
    info, flags, passage_id = struct.unpack_from("<BBI", payload)
    return info >> 4, info & 0x03, info >> 2 & 0x01, flags, passage_id


def advertisement(gate, side, flags, passage_id):
    """The 31 bytes a unit advertises."""
    return (
        b"\x02\x01\x06"
        + b"\x11\x07"
        + _SERVICE_ON_AIR
        + b"\x09\xff"
        + struct.pack("<H", COMPANY_ID)
        + manufacturer_payload(gate, side, flags, passage_id)
    )


def scan_response(name):
    """The scan response: the name, as a Complete Local Name."""
    return bytes((len(name) + 1, 0x09)) + name


def unit_info(unit_id, firmware, divider):
    return bytes((VERSION,)) + unit_id + bytes(firmware) + bytes((1 if divider else 0,))


def decode_unit_info(data):
    """(protocol version, unit ID, firmware version, battery divider fitted)."""
    return data[0], bytes(data[1:7]), tuple(data[7:10]), bool(data[10] & 0x01)


def status_value(flags, battery):
    """The status characteristic: flags and battery level."""
    return bytes((flags, battery))


def decode_status(data):
    """(flags, battery level)."""
    return data[0], data[1]


def reading(sequence, distance, status):
    return struct.pack("<BHB", sequence & 0xFF, min(max(distance, 0), 0xFFFF), status)


def decode_reading(data):
    """(sequence number, distance in mm, status)."""
    return struct.unpack_from("<BHB", data)


def answer(opcode, result):
    return bytes((opcode, result))


def reading_status(range_status, since_target_ms):
    """The protocol status of a measurement, from the driver's range status.

    `since_target_ms` is the time since the last valid or too close reading, or
    None if there was none: a signal failure right after a target is unreliable,
    because a dark or glossy car can read as one.
    """
    if range_status in (0, 6, 11):
        return VALID
    if range_status in (3, 13):
        return TOO_CLOSE
    if range_status == 2:
        recent = since_target_ms is not None and since_target_ms < UNRELIABLE_AFTER_TARGET_MS
        return UNRELIABLE if recent else NO_TARGET
    if range_status == 4:
        return NO_TARGET
    if range_status == 5:
        return FAULT
    return UNRELIABLE
