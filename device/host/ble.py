"""Act as the phone: talk to units over Bluetooth Low Energy from this computer.

Uses the computer's Bluetooth through bleak, and the byte layouts of the
firmware itself, from ../board/firmware/, so that the firmware can be tested
before the app talks to real units. Every command but `scan` connects to one
unit and sends Release before it disconnects, as the app does.

Usage: python -m host.ble COMMAND [UNIT] [options]; see `just ble --help`.
"""

import argparse
import asyncio
import secrets
import sys
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field

from bleak import BleakClient, BleakScanner
from bleak.backends.characteristic import BleakGATTCharacteristic
from bleak.backends.device import BLEDevice
from bleak.backends.scanner import AdvertisementData
from bleak.exc import BleakError

from host import console
from host.board import BOARD_DIR

sys.path.insert(0, str(BOARD_DIR))
import itertools

from firmware import protocol, record

ANSWER_TIMEOUT_S = 2  # as long as the app waits, see doc/ble-protocol.md

FLAG_NAMES = {
    protocol.IN_USE: "in use",
    protocol.UNLOCKED: "unlocked",
    protocol.PIN_RESET_WINDOW: "PIN reset window",
    protocol.LOCKED_OUT: "locked out",
    protocol.LOW_BATTERY: "low battery",
    protocol.SENSOR_FAULT: "sensor fault",
}


def flag_names(flags: int) -> str:
    return ", ".join(name for flag, name in FLAG_NAMES.items() if flags & flag) or "none"


def battery_text(battery: int) -> str:
    return "no battery reading" if battery == protocol.NO_BATTERY else f"battery {battery} %"


@dataclass(frozen=True)
class Found:
    """A unit seen advertising."""

    device: BLEDevice
    name: str
    rssi: int
    version: int
    gate: int
    side: int
    flags: int
    passage_id: int

    def describe(self) -> str:
        where = (
            f"passage {self.passage_id:08x}, gate {self.gate} {'right' if self.side else 'left'}"
            if self.passage_id
            else "unassigned"
        )
        return (
            f"{self.name:<24} {self.device.address}  RSSI {self.rssi:4}  {where}, "
            f"flags: {flag_names(self.flags)}"
        )


def found(device: BLEDevice, advertisement: AdvertisementData) -> Found | None:
    payload = advertisement.manufacturer_data.get(protocol.COMPANY_ID)
    if payload is None or len(payload) < 6:
        return None
    version, gate, side, flags, passage_id = protocol.decode_manufacturer_payload(payload)
    name = advertisement.local_name or device.name or "?"
    return Found(device, name, advertisement.rssi, version, gate, side, flags, passage_id)


async def scan(seconds: float) -> list[Found]:
    seen = await BleakScanner.discover(
        timeout=seconds, return_adv=True, service_uuids=[protocol.SERVICE_UUID]
    )
    units = [unit for device, adv in seen.values() if (unit := found(device, adv))]
    return sorted(units, key=lambda unit: -unit.rssi)


async def unit_id_of(unit: Found) -> bytes:
    async with BleakClient(unit.device, timeout=10) as client:
        info = await client.read_gatt_char(protocol.UNIT_INFO_UUID)
    return protocol.decode_unit_info(info)[1]


async def choose(wanted: str | None, seconds: float) -> Found:
    """The unit named by its name, Bluetooth address or short ID, or the only one in range."""
    units = await scan(seconds)
    if not units:
        console.fail("No unit found. Is it switched on, and is a phone connected to it?")
    if wanted is None:
        matches = units
    else:
        key = wanted.lower()
        matches = [
            unit
            for unit in units
            if key in (unit.name.lower(), unit.device.address.lower())
            or unit.name.lower().endswith(f" {key}")
        ]
        if not matches and len(wanted) == 4:
            # An assigned unit advertises its passage's name: ask each unit for its ID.
            ids = [protocol.short_id(await unit_id_of(unit)) for unit in units]
            matches = [
                unit for unit, short in zip(units, ids, strict=True) if short == wanted.upper()
            ]
    if len(matches) == 1:
        return matches[0]
    listing = "\n".join(f"    {unit.describe()}" for unit in units)
    if not matches:
        console.fail(f"No unit matches {wanted!r}. In range:\n{listing}")
    console.fail(f"More than one unit is in range. Name one by its name or address:\n{listing}")


@dataclass
class Session:
    """A connection to one unit, with its notifications."""

    client: BleakClient
    unit_id: bytes = b""
    zero_offset: int | None = None
    answers: asyncio.Queue[bytes] = field(default_factory=asyncio.Queue)
    readings: list[tuple[float, int, int, int]] = field(default_factory=list)
    show_status: bool = False

    async def start(self) -> None:
        """Subscribe to the notifications and show what the unit tells about itself."""
        client = self.client
        await client.start_notify(protocol.CONTROL_POINT_UUID, self._answer)
        await client.start_notify(protocol.READING_UUID, self._reading)
        await client.start_notify(protocol.STATUS_UUID, self._status)
        version, unit_id, firmware, divider = protocol.decode_unit_info(
            await client.read_gatt_char(protocol.UNIT_INFO_UUID)
        )
        self.unit_id = unit_id
        flags, battery = protocol.decode_status(await client.read_gatt_char(protocol.STATUS_UUID))
        print(
            f"Unit {unit_id.hex()} ({protocol.short_id(unit_id)}), protocol {version}, "
            f"firmware {'.'.join(map(str, firmware))}, battery divider "
            + ("fitted" if divider else "not fitted")
        )
        print(f"Status: flags: {flag_names(flags)}, {battery_text(battery)}")
        self.show_setup(await self.setup())

    async def setup(self) -> record.Record | None:
        raw = await self.client.read_gatt_char(protocol.SETUP_UUID)
        return record.parse(bytes(raw)) if raw else None

    def show_setup(self, passage: record.Record | None) -> None:
        if passage is None:
            print("Setup: unassigned")
            self.zero_offset = None
            return
        entry = record.entry_for(passage, self.unit_id)
        self.zero_offset = entry.zero_offset if entry else None
        print(
            f"Setup: passage {passage.name.decode()!r}, ID {passage.passage_id:08x}, "
            f"version {passage.setup_version}, {len(passage.units)} units, "
            f"warning {passage.warning_gap} mm, critical {passage.critical_gap} mm"
        )
        if entry:
            print(
                f"  this unit: gate {entry.gate} {'right' if entry.side else 'left'}, "
                f"zero offset {entry.zero_offset} mm, "
                + ("signals the gap" if entry.signals else "doesn't signal the gap")
            )

    def _answer(self, _: BleakGATTCharacteristic, data: bytearray) -> None:
        self.answers.put_nowait(bytes(data))

    def _reading(self, _: BleakGATTCharacteristic, data: bytearray) -> None:
        self.readings.append((time.monotonic(), *protocol.decode_reading(bytes(data))))

    def _status(self, _: BleakGATTCharacteristic, data: bytearray) -> None:
        if self.show_status:
            flags, battery = protocol.decode_status(bytes(data))
            print(f"Status: flags: {flag_names(flags)}, {battery_text(battery)}")

    async def command(self, opcode: int, parameters: bytes = b"") -> int:
        """Send a control point command and wait for its answer: the result code."""
        while not self.answers.empty():
            self.answers.get_nowait()
        data = bytes((opcode,)) + parameters
        await self.client.write_gatt_char(protocol.CONTROL_POINT_UUID, data, response=True)
        try:
            answer = await asyncio.wait_for(self.answers.get(), ANSWER_TIMEOUT_S)
        except TimeoutError:
            console.fail(
                f"No answer to {protocol.COMMAND_NAMES[opcode]} within {ANSWER_TIMEOUT_S} s."
            )
        if answer[0] != opcode:
            console.fail(f"Answer {answer.hex()} is for another command.")
        print(f"{protocol.COMMAND_NAMES[opcode]}: {protocol.result_name(answer[1])}")
        return answer[1]

    async def require(self, opcode: int, parameters: bytes = b"") -> None:
        if await self.command(opcode, parameters) != protocol.DONE:
            raise SystemExit(1)


async def connected(
    unit: Found, work: Callable[[Session], Awaitable[None]], release: bool = True
) -> None:
    print(f"Connecting to {unit.name} ({unit.device.address})")
    async with BleakClient(unit.device, timeout=15) as client:
        session = Session(client)
        await session.start()
        try:
            await work(session)
        finally:
            if release and client.is_connected:
                await session.command(protocol.RELEASE)


def summarize(readings: list[tuple[float, int, int, int]], zero_offset: int | None) -> str:
    if not readings:
        return "no readings"
    sequences = [sequence for _, sequence, _, _ in readings]
    lost = sum((later - earlier - 1) & 0xFF for earlier, later in itertools.pairwise(sequences))
    times = [at for at, _, _, _ in readings]
    longest = max((later - earlier for earlier, later in itertools.pairwise(times)), default=0)
    _, _, distance, status = readings[-1]
    text = (
        f"{len(readings)} readings, {lost} lost, longest pause {longest * 1000:.0f} ms, "
        f"last {distance} mm {protocol.status_name(status)}"
    )
    if zero_offset is not None and status == protocol.VALID:
        text += f", gap {distance - zero_offset} mm"
    return text


async def watch(session: Session, seconds: float | None, every: bool) -> None:
    session.show_status = True
    start = time.monotonic()
    shown = 0
    while seconds is None or time.monotonic() - start < seconds:
        await asyncio.sleep(1)
        batch = session.readings[shown:]
        shown = len(session.readings)
        if every:
            for at, sequence, distance, status in batch:
                print(
                    f"{at - start:7.3f} s  #{sequence:<3} {distance:5} mm  "
                    + protocol.status_name(status)
                )
        print(f"{time.monotonic() - start:5.0f} s  {summarize(batch, session.zero_offset)}")


def pin_bytes(pin: str) -> bytes:
    data = pin.encode()
    if not 4 <= len(data) <= 8 or not pin.isdigit():
        console.fail("A PIN has 4 to 8 digits.")
    return data


def made_up_units(gate_count: int, taken: tuple[int, int]) -> tuple[record.Entry, ...]:
    """Units with long names in every other gate and side, to make the record as long as it gets."""
    slots = [(gate, side) for gate in range(gate_count) for side in (0, 1) if (gate, side) != taken]
    return tuple(
        record.Entry(
            bytes((0xFE, 0, 0, 0, 0, number)), gate, side, False, 0, b"made-up unit %3d" % number
        )
        for number, (gate, side) in enumerate(slots)
    )


async def set_up(session: Session, args: argparse.Namespace) -> None:
    current = await session.setup()
    pin = pin_bytes(args.pin)
    if current is None:
        await session.require(protocol.SET_PIN, pin)
    else:
        await session.require(protocol.UNLOCK, pin)
    gate_count = record.MAX_GATES if args.fill else args.gate + 1
    name = b"this unit".ljust(record.MAX_UNIT_NAME, b".") if args.fill else b""
    side = 1 if args.side == "right" else 0
    entry = record.Entry(session.unit_id, args.gate, side, not args.quiet, args.zero, name)
    units = (entry, *made_up_units(gate_count, (args.gate, entry.side))) if args.fill else (entry,)
    passage = record.Record(
        passage_id=current.passage_id if current else secrets.randbits(32) or 1,
        setup_version=current.setup_version + 1 if current else 1,
        warning_gap=args.warning,
        critical_gap=args.critical,
        name=(args.name.ljust(record.MAX_PASSAGE_NAME, ".") if args.fill else args.name).encode(),
        gates=tuple(1000 * gate for gate in range(gate_count)),
        units=units,
    )
    data = record.encode(passage)
    if record.parse(data) is None:
        console.fail("That setup isn't valid: check the thresholds and the name's length.")
    print(f"Writing a record of {len(data)} bytes")
    await session.client.write_gatt_char(protocol.SETUP_STAGING_UUID, data, response=True)
    await session.require(protocol.COMMIT)
    stored = await session.setup()
    if stored is None or record.encode(stored) != data:
        console.fail("The unit's Setup characteristic doesn't hold the record just written.")
    session.show_setup(stored)


def parser() -> argparse.ArgumentParser:
    main = argparse.ArgumentParser(
        prog="just ble", description="Act as the phone: talk to units over Bluetooth."
    )
    main.add_argument("--scan", type=float, default=4, metavar="S", help="seconds to scan")
    commands = main.add_subparsers(dest="command", required=True)

    def command(name: str, text: str, pin: str | None = None) -> argparse.ArgumentParser:
        sub = commands.add_parser(name, help=text, description=text)
        sub.add_argument(
            "unit",
            nargs="?",
            help="the unit's name, its short ID or its Bluetooth address; "
            "not needed with only one unit in range",
        )
        if pin:
            sub.add_argument("--pin", required=True, help=pin)
        return sub

    commands.add_parser("scan", help="list the units in range")
    watch_command = command("watch", "connect and show the unit's readings and status")
    watch_command.add_argument("--seconds", type=float, help="stop after this many seconds")
    watch_command.add_argument("--every", action="store_true", help="show every reading")
    watch_command.add_argument(
        "--no-release",
        action="store_true",
        help="disconnect without Release, as a lost link does",
    )
    setup_command = command(
        "setup",
        "make the unit the only unit of a passage, or change that passage",
        pin="the passage PIN: set on an unassigned unit, unlocks an assigned one",
    )
    setup_command.add_argument("--name", default="Bench", help="passage name")
    setup_command.add_argument("--warning", type=int, default=150, help="warning gap, mm")
    setup_command.add_argument("--critical", type=int, default=50, help="critical gap, mm")
    setup_command.add_argument("--zero", type=int, default=0, help="zero offset, mm")
    setup_command.add_argument("--gate", type=int, default=0, choices=range(4))
    setup_command.add_argument("--side", choices=("left", "right"), default="left")
    setup_command.add_argument(
        "--quiet", action="store_true", help="the unit's buzzer and red LED don't signal the gap"
    )
    setup_command.add_argument(
        "--fill",
        action="store_true",
        help="add made-up units and a long name, for a record of the largest size",
    )
    command("identify", "blink the unit's LEDs and sound its buzzer for 5 s")
    command("unlock", "try a PIN, then lock again", pin="the PIN to try")
    set_pin_command = command("set-pin", "set a new PIN", pin="the new PIN")
    set_pin_command.add_argument(
        "--old", help="the current PIN; not needed unassigned or in the PIN reset window"
    )
    command("forget", "unlock and forget the setup and the PIN", pin="the passage PIN")
    return main


async def run(args: argparse.Namespace) -> None:
    if args.command == "scan":
        units = await scan(args.scan)
        for unit in units:
            print(unit.describe())
        print(f"{len(units)} units in range.")
        return
    unit = await choose(args.unit, args.scan)
    if args.command == "watch":
        await connected(
            unit, lambda s: watch(s, args.seconds, args.every), release=not args.no_release
        )
    elif args.command == "setup":
        await connected(unit, lambda s: set_up(s, args))
    elif args.command == "identify":
        await connected(unit, lambda s: s.require(protocol.IDENTIFY))
    elif args.command == "unlock":
        await connected(unit, lambda s: unlock_and_lock(s, pin_bytes(args.pin)))
    elif args.command == "set-pin":
        await connected(unit, lambda s: set_pin(s, args.old, pin_bytes(args.pin)))
    elif args.command == "forget":
        await connected(unit, lambda s: forget(s, pin_bytes(args.pin)))


async def unlock_and_lock(session: Session, pin: bytes) -> None:
    if await session.command(protocol.UNLOCK, pin) == protocol.DONE:
        await session.command(protocol.LOCK)


async def set_pin(session: Session, old: str | None, pin: bytes) -> None:
    if old is not None:
        await session.require(protocol.UNLOCK, pin_bytes(old))
    await session.require(protocol.SET_PIN, pin)
    await session.command(protocol.LOCK)


async def forget(session: Session, pin: bytes) -> None:
    await session.require(protocol.UNLOCK, pin)
    await session.require(protocol.FORGET)


def main() -> int:
    args = parser().parse_args()
    try:
        asyncio.run(run(args))
    except KeyboardInterrupt:
        print()
    except BleakError as e:
        console.fail(f"Bluetooth: {e}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
