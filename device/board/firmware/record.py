"""The passage record: ../../../doc/ble-protocol.md, section "Passage record".

A unit keeps and serves the record byte for byte as it was written, and only
parses it to read its own entry and the thresholds. Names stay bytes: the unit
broadcasts them as they are, and the host tools decode them.
"""

import struct
from collections import namedtuple

FORMAT = 1
MAX_GATES = 4
MAX_UNITS = 8
MAX_PASSAGE_NAME = 29
MAX_UNIT_NAME = 16

# Placement bit 3: the unit's own buzzer and red LED signal the gap.
_SIGNALS = 0x08

Record = namedtuple(
    "Record",
    ("passage_id", "setup_version", "warning_gap", "critical_gap", "name", "gates", "units"),
)

Entry = namedtuple("Entry", ("unit_id", "gate", "side", "signals", "zero_offset", "name"))


def _entry(data, pos):
    # The unit entry at `pos` and the position after it, or None if it doesn't fit.
    if pos >= len(data):
        return None, pos
    length = data[pos]
    end = pos + 1 + length
    if length < 10 or end > len(data):
        return None, end
    name_length = data[pos + 10]
    if name_length > MAX_UNIT_NAME or 10 + name_length > length:
        return None, end
    placement = data[pos + 7]
    entry = Entry(
        unit_id=bytes(data[pos + 1 : pos + 7]),
        gate=placement & 0x03,
        side=placement >> 2 & 0x01,
        signals=bool(placement & _SIGNALS),
        zero_offset=struct.unpack_from("<H", data, pos + 8)[0],
        name=bytes(data[pos + 11 : pos + 11 + name_length]),
    )
    return entry, end


def _entries(data, pos, count):
    # Yields the unit entries, ending with None at the first one that doesn't fit.
    for _ in range(count):
        entry, pos = _entry(data, pos)
        yield entry
        if entry is None:
            return


def parse(data):
    """The record in `data`, or None if it isn't a valid record of format 1."""
    if len(data) < 16 or data[0] != FORMAT:
        return None
    passage_id, version, warning, critical, gate_count, unit_count, name_length = (
        struct.unpack_from("<IIHHBBB", data, 1)
    )
    if passage_id == 0 or critical >= warning:
        return None
    if not (1 <= gate_count <= MAX_GATES and 1 <= unit_count <= MAX_UNITS):
        return None
    if not 1 <= name_length <= MAX_PASSAGE_NAME:
        return None
    gates_at = 16 + name_length
    entries_at = gates_at + 2 * gate_count
    if entries_at > len(data):
        return None
    gates = struct.unpack_from("<" + "H" * gate_count, data, gates_at)
    if any(later <= earlier for earlier, later in zip(gates, gates[1:])):
        return None
    units = tuple(_entries(data, entries_at, unit_count))
    if len(units) < unit_count or units[-1] is None:
        return None
    if any(entry.gate >= gate_count for entry in units):
        return None
    if len({entry.unit_id for entry in units}) < unit_count:
        return None
    if len({(entry.gate, entry.side) for entry in units}) < unit_count:
        return None
    name = bytes(data[16 : 16 + name_length])
    return Record(passage_id, version, warning, critical, name, gates, units)


def entry_for(record, unit_id):
    """The record's entry for this unit, or None if it doesn't list it."""
    matches = [entry for entry in record.units if entry.unit_id == unit_id]
    return matches[0] if matches else None


def _encode_entry(entry):
    placement = entry.gate | entry.side << 2 | (_SIGNALS if entry.signals else 0)
    return (
        bytes((10 + len(entry.name),))
        + entry.unit_id
        + bytes((placement,))
        + struct.pack("<HB", entry.zero_offset, len(entry.name))
        + entry.name
    )


def encode(record):
    """The bytes of a record, as the app writes them."""
    head = struct.pack(
        "<BIIHHBBB",
        FORMAT,
        record.passage_id,
        record.setup_version,
        record.warning_gap,
        record.critical_gap,
        len(record.gates),
        len(record.units),
        len(record.name),
    )
    gates = struct.pack("<" + "H" * len(record.gates), *record.gates)
    return head + record.name + gates + b"".join(_encode_entry(entry) for entry in record.units)
