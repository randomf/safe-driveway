"""The protocol's byte layouts, checked against the examples in doc/ble-protocol.md."""

import pytest

from firmware import protocol as p

UNIT = bytes.fromhex("08ad0a5b9fb8")


def test_characteristic_uuids_share_the_service_uuid():
    assert p.UNIT_INFO_UUID == "9f050002-a55b-4698-ae01-042514884edf"
    assert p.CONTROL_POINT_UUID == "9f050007-a55b-4698-ae01-042514884edf"


def test_advertisement_example():
    expected = bytes.fromhex("0201061107df4e8814250401ae98465ba50100059f09ffffff15004d3c2b1a")
    assert p.advertisement(gate=1, side=1, flags=0, passage_id=0x1A2B3C4D) == expected
    assert len(expected) == 31


def test_advertisement_while_connected_and_unlocked():
    flags = p.IN_USE | p.UNLOCKED
    data = p.advertisement(gate=1, side=1, flags=flags, passage_id=0x1A2B3C4D)
    assert data[-8:] == bytes.fromhex("ffff15034d3c2b1a")


def test_manufacturer_payload_round_trip():
    payload = p.manufacturer_payload(gate=3, side=0, flags=p.LOCKED_OUT, passage_id=7)
    assert p.decode_manufacturer_payload(payload) == (1, 3, 0, p.LOCKED_OUT, 7)


def test_scan_response_examples():
    assert p.scan_response("Garáž".encode()) == bytes.fromhex("08 09 47 61 72 c3 a1 c5 be")
    assert p.scan_response(p.unassigned_name(UNIT)) == bytes.fromhex(
        "13 09 53 61 66 65 20 44 72 69 76 65 77 61 79 20 39 46 42 38"
    )


def test_short_id():
    assert p.short_id(UNIT) == "9FB8"


def test_unit_info_example():
    data = p.unit_info(UNIT, (0, 1, 0), divider=True)
    assert data == bytes.fromhex("0108ad0a5b9fb800010001")
    assert p.decode_unit_info(data) == (1, UNIT, (0, 1, 0), True)


def test_status_example():
    assert p.status_value(p.IN_USE, 76) == bytes.fromhex("014c")


def test_reading_examples():
    assert p.reading(42, 187, p.VALID) == bytes.fromhex("2abb0000")
    assert p.reading(43, 31, p.TOO_CLOSE) == bytes.fromhex("2b1f0001")
    assert p.decode_reading(bytes.fromhex("2abb0000")) == (42, 187, p.VALID)


def test_reading_sequence_wraps():
    assert p.reading(256, 0, p.NO_TARGET)[0] == 0


def test_answer_examples():
    assert p.answer(p.UNLOCK, p.DONE) == bytes.fromhex("0100")
    assert p.answer(p.UNLOCK, p.WRONG_PIN) == bytes.fromhex("0104")
    assert p.answer(p.LOCK, p.DONE) == bytes.fromhex("0600")


@pytest.mark.parametrize(
    ("range_status", "since_target", "expected"),
    [
        (0, None, p.VALID),
        (6, None, p.VALID),
        (11, None, p.VALID),
        (3, None, p.TOO_CLOSE),
        (13, None, p.TOO_CLOSE),
        (2, 999, p.UNRELIABLE),
        (2, 1000, p.NO_TARGET),
        (2, None, p.NO_TARGET),
        (4, 10, p.NO_TARGET),
        (1, None, p.UNRELIABLE),
        (7, None, p.UNRELIABLE),
        (9, None, p.UNRELIABLE),
        (10, None, p.UNRELIABLE),
        (12, None, p.UNRELIABLE),
        (255, None, p.UNRELIABLE),
        (5, None, p.FAULT),
    ],
)
def test_reading_status_mapping(range_status, since_target, expected):
    assert p.reading_status(range_status, since_target) == expected
