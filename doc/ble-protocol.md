# BLE protocol between the units and the app

This is how a unit and the app talk to each other over Bluetooth Low Energy. The unit's firmware in `device/board/firmware/` and the app's shared module in `app/` both implement it. Why it looks this way is recorded in [ADR 0007](adr/0007-ble-protocol.md); what the app does with it is in [app/features.md](../app/features.md).

This document describes protocol version **1**.

## Conventions

- Multi-byte numbers are unsigned and little-endian: `u8`, `u16`, `u32`.
- Distances are in millimetres.
- Text is UTF-8, preceded by its length in bytes as a `u8`, with no terminator.
- Bits are numbered from 0, the least significant.
- Writers set reserved bits and bytes to 0. Readers ignore bytes after the fields they know, and flag bits they don't know. The app treats a reading status it doesn't know as *unreliable* and a result code it doesn't know as a failure.
- A compatible change adds fields at the end of a value or of a unit entry, new commands, new flag bits or new status values, and keeps the protocol version. A change that older readers would misread raises the protocol version and needs a new ADR.

### Units, passages, gates and sides

- **Unit ID:** the ESP32's 6-byte factory MAC address, as `machine.unique_id()` returns it. It is written as 12 hex digits, and its **short ID** is the last 4 in upper case: `08ad0a5b9fb8` is `9FB8`. `just bringup board` prints it.
- **End 0:** the end of the passage that gate positions are measured from. The app makes it the end the driver usually drives in from.
- **Gate index:** gates are numbered 0 to 3 in order of their position, gate 0 nearest end 0.
- **Side:** 0 is left, 1 is right, as seen standing at end 0 and looking along the passage. The app turns this into the driver's left and right from the end the car enters by and whether it is reversing.
- **Gap:** a *valid* reading's distance minus the unit's zero offset. It can be negative. Readings with any other status have no gap.
- **Switched on:** a power-on start, from the power switch or from plugging in USB. The board's reset button and a brownout count too, because the ESP32 starts from them the same way and MicroPython can't tell them apart. A restart after a crash or a watchdog reset doesn't count.

## Advertising

A unit always advertises the same data:

- With no phone connected, every 100 ms and connectably, so that phones find it quickly.
- While a phone is connected, once a second, scannable but not connectable, with the *in use* flag set. No second phone can connect, and the slower advertising leaves the radio to the connection.

### Advertisement

The advertisement fills all 31 bytes:

| Bytes | AD type | Content |
|---|---|---|
| 3 | `0x01` Flags | `0x06`: LE General Discoverable, BR/EDR not supported |
| 18 | `0x07` Complete list of 128-bit service UUIDs | The service UUID, `9f050001-a55b-4698-ae01-042514884edf`, in on-air (reversed) byte order |
| 10 | `0xFF` Manufacturer specific data | Company ID `0xFFFF`, then the 6-byte payload below |

The payload:

| Offset | Type | Field |
|---|---|---|
| 0 | `u8` | **Info.** Bits 7–4: protocol version. Bit 3: reserved. Bit 2: side. Bits 1–0: gate index. Gate and side are 0 on an unassigned unit |
| 1 | `u8` | **Flags**, see below |
| 2 | `u32` | **Passage ID.** 0 means the unit is unassigned: it belongs to no passage |

The flags byte is the same here and in the [status](#status) characteristic:

| Bit | Flag | Set while |
|---|---|---|
| 0 | In use | A phone is connected |
| 1 | Unlocked | The connected phone may change the setup, see [PIN and locking](#pin-and-locking) |
| 2 | PIN reset window | The unit was switched on less than 2 minutes ago |
| 3 | Locked out | 5 wrong PINs in a row have been sent |
| 4 | Low battery | The battery is below 20 %. Only on units with a battery reading |
| 5 | Sensor fault | The sensor didn't answer when the unit started, or its last measurement failed |
| 6–7 | Reserved | |

The app scans for the service UUID and ignores manufacturer data under any other company ID. A unit with a higher protocol version than the app knows is shown as needing a newer app.

### Scan response

The scan response holds one Complete Local Name (AD type `0x09`): the passage name, or `Safe Driveway ` and the short ID on an unassigned unit, such as `Safe Driveway 9FB8`. The unit's GAP device name is the same. With it, the app can ask which passage the driver means without connecting to any unit.

## GATT service

A unit has one primary service, `9f050001-a55b-4698-ae01-042514884edf`. Its characteristics share the service's UUID, with the last 4 hex digits of its first group changed:

| UUID | Characteristic | Properties |
|---|---|---|
| `9f050002-a55b-4698-ae01-042514884edf` | [Unit info](#unit-info) | Read |
| `9f050003-a55b-4698-ae01-042514884edf` | [Status](#status) | Read, notify |
| `9f050004-a55b-4698-ae01-042514884edf` | [Reading](#reading) | Notify |
| `9f050005-a55b-4698-ae01-042514884edf` | [Setup](#setup) | Read |
| `9f050006-a55b-4698-ae01-042514884edf` | [Setup staging](#setup-staging) | Write |
| `9f050007-a55b-4698-ae01-042514884edf` | [Control point](#control-point) | Write, notify |

Nothing needs an ATT MTU above the default 23 bytes. The setup record is longer than one packet at small MTUs, so it is read and written as a long read and a long write, which both phone platforms and the ESP32's BLE stack handle by themselves. On Android, the app may ask for a larger MTU to make that faster; iOS chooses its own.

### Unit info

Read-only, fixed while the unit runs.

| Offset | Type | Field |
|---|---|---|
| 0 | `u8` | Protocol version |
| 1 | 6 bytes | Unit ID |
| 7 | 3 × `u8` | Firmware version: major, minor, patch |
| 10 | `u8` | Hardware. Bit 0: the battery divider is fitted. Bits 1–7: reserved |

### Status

Notified whenever a field changes.

| Offset | Type | Field |
|---|---|---|
| 0 | `u8` | Flags, as in the [advertisement](#advertisement) |
| 1 | `u8` | Battery level in %, 0–100, or 255 when the unit has no battery reading: it has no battery divider, or the divider sees no battery because the unit runs from USB with its switch off |

### Reading

The unit measures 20 times per second while the sensor runs, see [Measuring and signalling](#measuring-and-signalling). While a phone is connected, it notifies every measurement as soon as it has it, including those that see no target. If the sensor hasn't delivered a measurement 100 ms after the previous reading, the unit sends a reading with status *sensor fault* instead, and another every 50 ms while the fault lasts. A unit that sends nothing for 0.5 s has gone silent.

| Offset | Type | Field |
|---|---|---|
| 0 | `u8` | Sequence number. Goes up by one with every reading, and wraps from 255 to 0. A jump means readings were lost |
| 1 | `u16` | Distance from the sensor, in mm. Meaningful only when the status is *valid* or *too close* |
| 3 | `u8` | Status, see below |

| Status | Name | Meaning |
|---|---|---|
| 0 | Valid | A target at the given distance |
| 1 | Too close | A target closer than the sensor's guaranteed minimum (4 cm on the VL53L1X). The distance isn't accurate. Both the app and the unit's own signalling treat it as below the critical threshold |
| 2 | No target | Nothing in range: no car in front of the unit |
| 3 | Unreliable | Something may be there, but the sensor can't measure it, for example in strong light, or a dark or glossy car that returns too little light. The app shows it as no data |
| 4 | Sensor fault | The sensor didn't deliver a measurement |

The firmware maps the VL53L1X's range status, as numbered by ST's Ultra Lite Driver ([device/board/lib/vl53l1x.py](../device/board/lib/vl53l1x.py)), like this:

| Range status | Protocol status |
|---|---|
| 0, 6, 11 | Valid |
| 3, 13 | Too close |
| 2, within 1 s of the last *valid* or *too close* reading | Unreliable |
| 2 otherwise, and 4 | No target |
| 1, 7, 9, 10, 12 and any other | Unreliable |
| 5, an I2C error, or no measurement 100 ms after the previous reading | Sensor fault |

An empty passage reads as a signal failure (2), but so can the side of a dark or glossy car, which reflects too little of the sensor's light back. Right after a target, a signal failure is therefore reported as *unreliable*, so that a car that fades out shows as no data rather than as an empty gate. The mapping is checked against real cars before the first release.

### Setup

Read-only. The passage record the unit has committed, byte for byte as it was written, or no bytes at all on an unassigned unit. Every unit of a passage holds the same record, so a phone can read the whole setup from any one of them.

#### Passage record

| Offset | Type | Field |
|---|---|---|
| 0 | `u8` | Record format, 1 |
| 1 | `u32` | Passage ID, chosen at random by the app when the passage is created. Never 0 |
| 5 | `u32` | Setup version |
| 9 | `u16` | Warning gap, mm |
| 11 | `u16` | Critical gap, mm |
| 13 | `u8` | Gate count *G*, 1–4 |
| 14 | `u8` | Unit count *U*, 1–8 |
| 15 | `u8` + text | Passage name, 1–29 bytes |
| | *G* × `u16` | Gate positions, in mm from end 0, in increasing order |
| | *U* × unit entry | One entry per unit, see below |

A unit entry:

| Offset | Type | Field |
|---|---|---|
| 0 | `u8` | Entry length: the number of bytes in the entry after this one, at least 10 |
| 1 | 6 bytes | Unit ID |
| 7 | `u8` | **Placement.** Bits 1–0: gate index. Bit 2: side. Bit 3: the unit's own buzzer and red LED signal the gap. Bits 7–4: reserved |
| 8 | `u16` | Zero offset, mm |
| 10 | `u8` + text | Unit name, 0–16 bytes. Empty means the app names it by its gate and side |

A reader moves from one entry to the next by the entry length, so a later version can add fields at the end of every entry. Fields added for the passage as a whole go after the last entry.

A record of format 1 is at most 269 bytes. It is valid when its format is 1, the fields a reader knows fit inside it and inside each entry's length, the counts and name lengths are in range, the passage ID isn't 0, the critical gap is below the warning gap, the gate positions increase, every unit's gate index is below the gate count, and no two entries share a unit ID or a gate and side. Bytes after the fields a reader knows are allowed.

The PIN is not part of the record and can't be read back.

### Setup staging

Write-only, with response. The app writes a whole passage record here, then sends [Commit](#control-point). The unit doesn't act on the staged record until then, and discards it when the phone disconnects. A record that doesn't fit one packet goes as a long write.

### Control point

The app writes a command, an opcode followed by its parameters, with response. The unit answers every command with exactly one notification on the same characteristic: the opcode, then a result code. The app sends one command at a time and waits for its answer.

| Opcode | Command | Parameters | Allowed |
|---|---|---|---|
| `0x01` | **Unlock** | The PIN, 4–8 ASCII digits | Always. On an unassigned unit it succeeds and changes nothing |
| `0x02` | **Set PIN** | The new PIN, 4–8 ASCII digits | When unlocked, or within the PIN reset window. Unlocks the unit |
| `0x03` | **Commit** | None | When unlocked and the unit has a PIN. Applies the staged record, then locks the unit |
| `0x04` | **Identify** | None | Always. Blinks both LEDs and sounds the buzzer for 5 s. Sending it again restarts the 5 s |
| `0x05` | **Forget** | None | When unlocked. Deletes the record and the PIN: the unit becomes unassigned |
| `0x06` | **Lock** | None | Always. Ends the unlock. Changes nothing on an unassigned unit |
| `0x07` | **Release** | None | Always. Tells the unit that the phone is about to disconnect on purpose, see [Measuring and signalling](#measuring-and-signalling) |

| Result | Meaning |
|---|---|
| `0x00` | Done |
| `0x01` | Unknown opcode |
| `0x02` | Invalid parameters, such as a PIN that isn't 4–8 digits, or an invalid or missing staged record |
| `0x03` | Not allowed: the unit isn't unlocked, or has no PIN yet |
| `0x04` | Wrong PIN |
| `0x05` | Locked out: the unit refuses every PIN until it is switched off and on |
| `0x06` | The staged record doesn't list this unit |
| `0x07` | The unit couldn't save to its flash |

On Commit, the unit checks the record, [saves](#saving) it, updates its Setup characteristic, its advertisement, scan response and GAP name, locks, and answers. It doesn't compare setup versions; choosing the newest record is the app's job.

An answer takes priority over readings: when the unit can't send it at once, it holds back readings until the answer has gone out. If no answer arrives within 2 s, the app doesn't repeat Unlock, which would count a wrong PIN twice, but reads Status to see whether the unit is unlocked. After a Commit with no answer, it reads Setup to see whether the record took. Every other command is safe to send again.

## Unit behaviour

### PIN and locking

- A unit is **unlocked** for the current connection when it is unassigned, after a correct Unlock, or after a Set PIN. It locks again after a Commit or a Lock, and when the phone disconnects. The app locks every unit it has unlocked as soon as it is done with it, because a unit doesn't signal the gap while unlocked.
- Each wrong PIN counts, and a correct one resets the count. After 5 wrong PINs in a row, the unit is **locked out**: it answers every Unlock with `0x05` until it is switched off and on. The count survives restarts, so a crash or a watchdog reset doesn't clear it.
- For 2 minutes after the unit is switched on, the **PIN reset window**, Set PIN works without the old PIN. This is how a forgotten PIN is replaced. A restart doesn't open the window.
- An unassigned unit is unlocked. It must be given a PIN with Set PIN before it accepts a Commit, so a unit never belongs to a passage without a PIN.
- The PIN travels in plain text. It guards against accidents and casual mischief, not against someone recording the radio traffic.

### Saving

The unit keeps its record and its PIN together in one file on its flash. It saves by writing a new file and renaming it over the old one, so switching the unit off mid-save leaves either the old or the new setup, never a mix. Forget deletes the file. A unit that can't read the file when it starts runs as unassigned.

### Measuring and signalling

- The sensor runs while a phone is connected, for the first 10 minutes after the unit starts, whether it was switched on or restarted, and for 5 minutes after a connection is lost. A unit with no phone, or with a flaky link, still warns a driver. Otherwise the unit only advertises.
- While the sensor runs, a unit whose placement has bit 3 set signals with its own buzzer and red LED. A *valid* reading gives a warning while its gap is below the warning gap, and the critical signal while its gap is below the critical gap. A *too close* reading gives the critical signal. Readings with any other status keep the signal of the last *valid* or *too close* reading for up to 0.5 s, so that a dark car that drops out for a few readings doesn't break the warning, and give no signal after that. The firmware defines the patterns, see [device/firmware.md](../device/firmware.md#lights-and-sounds).
- A unit doesn't signal the gap while it is unlocked, so that a board held on the line while setting the zero offset doesn't sound the alarm. Identify still works.
- **When the phone disconnects after Release**, the unit switches the sensor off at once, also within its first 10 minutes.
- **After any other disconnect**, such as a lost link or an app that was closed without sending Release, the unit keeps measuring and signalling for 5 minutes, whatever is in front of it, or until its first 10 minutes are over if that is later. Then it switches the sensor off. A phone that connects in the meantime takes over as usual.

## Sequences

### Connecting to a passage

1. Scan for the service UUID. Group the units by passage ID and name the passages from the scan response.
2. Connect to every unit of the chosen passage that isn't in use.
3. Subscribe to Reading, Status and Control point, then read Unit info, Status and Setup.
4. Take the record with the highest setup version as the passage's setup.
5. Check each unit's ID against that record. A unit it doesn't list was forgotten or replaced while out of reach, and only still carries the passage's ID. The app disconnects from it, after an Unlock and a Forget if it knows the PIN, so that it doesn't take up one of the phone's connections.
6. [Bring up to date](#bringing-a-unit-up-to-date) the listed units that hold an older record.

Readings flow from the moment a unit is connected; the app receives them once it has subscribed.

### Disconnecting

Before it disconnects from a unit on purpose, for example when it goes to the background, the app sends Release.

### Setting up a new passage

For every unit, which is unassigned:

1. Set PIN with the passage's PIN.
2. Write the passage record, with setup version 1, to Setup staging, then Commit, which locks the unit.

While the setup is being made, Identify tells the units apart, and readings let the app set each zero offset from a board held on the line. The app works out the zero offset itself; the unit has no command for it.

### Changing the setup

For every unit: Unlock with the PIN, write the record with a setup version one above the highest the app has seen, and Commit. If the change is abandoned instead, Lock.

### Bringing a unit up to date

A unit that was switched off during a change still holds an older record. A phone that knows the PIN updates it as in [Changing the setup](#changing-the-setup), with the newest record unchanged. A phone without the PIN uses the newest record for display and shows the unit as out of date.

### Changing the PIN

For every unit: Unlock with the old PIN, Set PIN with the new one, then Lock. A unit that was switched off meanwhile keeps the old PIN, and is unlocked with the old PIN or reset in its PIN reset window.

### Resetting a forgotten PIN

Switch the unit off and on, connect within 2 minutes, Set PIN with the new PIN, then Lock. Then do the same for the passage's other units.

### Replacing a unit

1. On the new, unassigned unit, Set PIN with the passage's PIN.
2. In the record, replace the old unit's ID with the new unit's, raise the setup version, and commit it to every unit, the new one included.
3. If the old unit can still be reached, Unlock and Forget it. If it can't, the app forgets it when it next finds it, see [Connecting to a passage](#connecting-to-a-passage).

### Adding a unit

1. On the new, unassigned unit, Set PIN with the passage's PIN.
2. Add its entry to the record, with a new gate if it needs one, raise the setup version, and commit it to every unit, the new one included.

### Forgetting a unit

Commit a record without the unit, with a raised setup version, to the passage's other units. If the forgotten unit can be reached, Unlock and Forget it.

### Deleting a passage

Unlock and Forget every unit of the passage in reach. A unit out of reach keeps its record, since no other record says the passage is gone: when it comes back, the passage shows up again with that unit only, and deleting it again resets it.

## Examples

All bytes in hex. The firmware's and the app's tests decode and encode these.

**Advertisement** of a unit at gate index 1 on the right, protocol version 1, no flags, passage ID `0x1a2b3c4d`:

```
02 01 06
11 07 df 4e 88 14 25 04 01 ae 98 46 5b a5 01 00 05 9f
09 ff ff ff 15 00 4d 3c 2b 1a
```

The same unit while a phone is connected and has unlocked it: the manufacturer data becomes `ff ff 15 03 4d 3c 2b 1a`.

**Scan response** for the passage "Garáž": `08 09 47 61 72 c3 a1 c5 be`. For an unassigned unit with short ID `9FB8`: `13 09 53 61 66 65 20 44 72 69 76 65 77 61 79 20 39 46 42 38`.

**Unit info** of unit `08ad0a5b9fb8` with firmware 0.1.0 and the battery divider: `01 08 ad 0a 5b 9f b8 00 01 00 01`.

**Status** while connected, battery at 76 %: `01 4c`.

**Reading** number 42, 187 mm, valid: `2a bb 00 00`. Number 43, 31 mm, too close: `2b 1f 00 01`.

**Passage record** for "Garáž": ID `0x1a2b3c4d`, setup version 3, warning gap 150 mm, critical gap 50 mm, one gate at 1200 mm. Unit `08ad0a5b9fb8` named "Pillar" is on the left with a 60 mm zero offset; unit `08ad0a5b1234`, unnamed, is on the right with 55 mm. Both signal the gap. 53 bytes:

```
01 4d 3c 2b 1a 03 00 00 00 96 00 32 00 01 02
07 47 61 72 c3 a1 c5 be
b0 04
10 08 ad 0a 5b 9f b8 08 3c 00 06 50 69 6c 6c 61 72
0a 08 ad 0a 5b 12 34 0c 37 00 00
```

**Unlock** with PIN 1234: the app writes `01 31 32 33 34` to the control point. The unit notifies `01 00` if the PIN is right, `01 04` if it is wrong. **Lock** is `06`, answered by `06 00`.
