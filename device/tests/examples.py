"""The passage record example from doc/ble-protocol.md, shared by the tests."""

from firmware.record import Entry, Record

PILLAR = bytes.fromhex("08ad0a5b9fb8")
OTHER = bytes.fromhex("08ad0a5b1234")

EXAMPLE = bytes.fromhex(
    "01 4d 3c 2b 1a 03 00 00 00 96 00 32 00 01 02"
    "07 47 61 72 c3 a1 c5 be"
    "b0 04"
    "10 08 ad 0a 5b 9f b8 08 3c 00 06 50 69 6c 6c 61 72"
    "0a 08 ad 0a 5b 12 34 0c 37 00 00"
)

PARSED = Record(
    passage_id=0x1A2B3C4D,
    setup_version=3,
    warning_gap=150,
    critical_gap=50,
    name="Garáž".encode(),
    gates=(1200,),
    units=(
        Entry(PILLAR, gate=0, side=0, signals=True, zero_offset=60, name=b"Pillar"),
        Entry(OTHER, gate=0, side=1, signals=True, zero_offset=55, name=b""),
    ),
)
