"""The passage record, checked against the example in doc/ble-protocol.md."""

import pytest

from examples import EXAMPLE, OTHER, PARSED, PILLAR
from firmware import record
from firmware.record import Entry, Record


def entry(unit_id, gate=0, side=0, name=b""):
    return Entry(unit_id, gate, side, signals=True, zero_offset=0, name=name)


def with_(base, **changes):
    return base._replace(**changes)


def test_example_parses():
    assert len(EXAMPLE) == 53
    assert record.parse(EXAMPLE) == PARSED


def test_example_encodes():
    assert record.encode(PARSED) == EXAMPLE


def test_entry_for():
    assert record.entry_for(PARSED, OTHER) == PARSED.units[1]
    assert record.entry_for(PARSED, bytes(6)) is None


def test_the_largest_record_is_269_bytes():
    units = tuple(
        entry(bytes((0, 0, 0, 0, 0, number)), gate=number // 2, side=number % 2, name=b"u" * 16)
        for number in range(8)
    )
    largest = Record(1, 1, 150, 50, b"p" * 29, (100, 200, 300, 400), units)
    data = record.encode(largest)
    assert len(data) == 269
    assert record.parse(data) == largest


def test_bytes_after_known_fields_are_allowed():
    assert record.parse(EXAMPLE + b"\x00\x01") == PARSED


def test_an_entry_may_be_longer_than_its_known_fields():
    data = record.encode(with_(PARSED, units=PARSED.units[:1]))
    data = data[:-17] + bytes((data[-17] + 2,)) + data[-16:] + b"\xaa\xbb"
    assert record.parse(data) == with_(PARSED, units=PARSED.units[:1])


@pytest.mark.parametrize(
    "broken",
    [
        with_(PARSED, passage_id=0),
        with_(PARSED, critical_gap=150),
        with_(PARSED, gates=(1200, 1200)),
        with_(PARSED, gates=(1200, 600)),
        with_(PARSED, gates=(100, 200, 300, 400, 500)),
        with_(PARSED, name=b""),
        with_(PARSED, name=b"n" * 30),
        with_(PARSED, units=()),
        with_(
            PARSED,
            units=tuple(entry(bytes((0, 0, 0, 0, 0, n)), n % 4 // 2, n % 2) for n in range(9)),
        ),
        with_(PARSED, units=(entry(PILLAR, gate=1),)),
        with_(PARSED, units=(entry(PILLAR), entry(PILLAR, side=1))),
        with_(PARSED, units=(entry(PILLAR), entry(OTHER))),
        with_(PARSED, units=(entry(PILLAR, name=b"n" * 17),)),
    ],
)
def test_invalid_records(broken):
    assert record.parse(record.encode(broken)) is None


def test_wrong_format():
    assert record.parse(b"\x02" + EXAMPLE[1:]) is None


@pytest.mark.parametrize("length", [0, 1, 15, 16, 24, 25, 30, 41, 52])
def test_truncated_record(length):
    assert record.parse(EXAMPLE[:length]) is None


def test_entry_length_below_ten():
    data = bytearray(EXAMPLE)
    data[42] = 9  # the second entry's length
    assert record.parse(bytes(data)) is None
