# 7. BLE protocol between the units and the app

- **Status:** Accepted
- **Date:** 2026-11-14

## Context

The units and the phone talk over Bluetooth Low Energy, with each unit a GATT peripheral and the phone the central ([ADR 0002](0002-ble-communication.md)). ADR 0002 left the services, characteristics and data format to be specified once in `doc/`. The app is written in Kotlin Multiplatform with Kable ([ADR 0006](0006-kotlin-multiplatform-app.md)), the firmware in MicroPython on the ESP32 ([ADR 0003](0003-device-development-toolchain.md)).

[app/features.md](../../app/features.md) sets what the protocol has to carry:

- 20 distance readings per second from each of up to 8 units, each with a status, reaching the screen within 100 ms.
- The battery level, the firmware version and the sensor's status.
- A passage setup that lives on the units, carries a version number and can be read by any phone, but changed only with a passage PIN, which travels unencrypted. A 2-minute window after a unit is switched on lets a forgotten PIN be replaced.
- An *identify* action, and the unit's own buzzer and red LED signalling the gap.
- One phone connected to a unit at a time, with other phones shown that the unit is in use.

MicroPython's `bluetooth` module shapes the design. A GATT write can't be rejected with an error, the firmware isn't told when a phone subscribes to notifications, a disconnect doesn't say why it happened, and a written value is cut to 20 bytes unless the firmware enlarges its buffer.

## Decision

The protocol is specified in [doc/ble-protocol.md](../ble-protocol.md), with its UUIDs, byte layouts and examples. This ADR records the choices behind it.

- **Advertising.** A unit advertises one 128-bit service UUID and 6 bytes of manufacturer data under company ID `0xFFFF`: the protocol version, its gate and side, a flags byte (in use, unlocked, PIN reset window, locked out, low battery, sensor fault) and the 4-byte passage ID. Its scan response carries the passage name, or `Safe Driveway` and the last 4 hex digits of its unit ID while it belongs to no passage. A phone can pick the passage, list unassigned units and see which units are in use without connecting.
- **One phone at a time.** While a phone is connected, the unit keeps advertising the same data with the *in use* flag set, but not connectably, so no second phone can connect, and only once a second instead of every 100 ms, so that the advertising doesn't compete with the readings for the radio.
- **One GATT service** with six characteristics: unit info, status, reading, setup, setup staging and a control point.
- **Binary encoding.** Every value has a fixed little-endian layout, with distances in millimetres and length-prefixed UTF-8 text. Each unit entry in the setup carries its own length. Compatible additions go at the end of a value or of a unit entry, and units keep and serve the setup byte for byte, including fields they don't know. The service UUID never changes; an incompatible change raises the protocol version that the advertisement carries.
- **Readings.** Each reading carries the sensor's raw distance in millimetres, a status from the protocol's own list (valid, too close, no target, unreliable, sensor fault) and a sequence number. The app subtracts the zero offset; only a valid reading has a gap. The firmware maps the VL53L1X's range status to the protocol's, and reports a signal failure right after a target as unreliable, because a dark or glossy car can read as one. A sensor that stops delivering is reported as a fault within 100 ms, and the readings keep coming 20 times per second.
- **Unit ID.** A unit is identified by the ESP32's factory MAC address, reported in the unit info characteristic, because iOS hides BLE addresses.
- **The whole passage on every unit.** Every unit of a passage holds the same passage record: passage ID, setup version, name, thresholds, gate positions, and every unit's ID, gate, side, zero offset, name and whether it signals the gap. A unit finds its own entry by its ID. Left and right are as seen from the end the gate positions are measured from. A unit that the newest record no longer lists is forgotten when a phone with the PIN next finds it.
- **Changing the setup.** The app writes a record to the setup staging characteristic, then sends *Commit* on the control point. The unit checks it, saves it atomically together with the PIN, locks and notifies the result. Every control point command is answered by a notification with a result code, which takes priority over readings. The app waits 2 s for an answer, and checks the unit's state instead of repeating an Unlock or a Commit.
- **PIN.** 4 to 8 digits, sent in plain text in an *Unlock* command. The unlock ends with a Commit, a *Lock* command or the disconnect. The PIN is stored apart from the record and can't be read back. After 5 wrong PINs in a row the unit refuses every PIN until it is switched off and on; a correct PIN resets the count, and a restart doesn't. For 2 minutes after the unit is switched on, a new PIN can be set without the old one; a restart after a crash, a watchdog reset or a brownout doesn't open that window. An unassigned unit needs a PIN before it accepts a record.
- **Units that are behind.** The app uses the record with the highest setup version. A phone that has had the PIN entered keeps it for that passage and writes the newest record to units that are behind.
- **Measuring only while connected.** The sensor runs, and the unit sends 20 readings per second, from the moment a phone connects until it disconnects. Before a deliberate disconnect the app sends *Release*, and the unit then stops at once. After any other disconnect, the unit keeps measuring and signalling until it has seen no target for 10 s, for at most 2 minutes. A unit doesn't signal the gap while unlocked, which is while its setup is being changed.

## Alternatives considered

- **Protocol details in the ADR itself.** Every new field or command would need a superseding ADR. The spec file can grow compatibly while this ADR stands.
- **Each unit storing only its own setup.** A phone couldn't tell how many units a passage has, or which one is missing, before it had found them all, and replacing a unit would mean re-entering its setup.
- **A challenge–response PIN.** It hides the PIN from a casual listener, but a 4–8 digit PIN falls to an offline search of one recorded exchange. It adds code on both sides for little gain over the plain PIN that features.md already accepts.
- **BLE pairing.** It would encrypt the link, but every phone would get a system pairing dialog for every unit, and the ESP32 would have to keep bonds for several phones.
- **JSON for the setup.** Readable and easy to extend, but a passage with 8 named units can pass BLE's 512-byte attribute limit and would need its own chunking.
- **Advertising only the service UUID.** A phone would have to connect to every unit to learn its passage, holding units of a neighbouring passage in use while it does.
- **Service data instead of manufacturer data.** It needs no company ID, but iOS may not match a service UUID scan filter on service data.
- **Staying connectable while in use and dropping a second phone.** It costs the second phone and the unit a connection attempt for nothing.
- **Measuring whenever the unit is switched on.** Drivers without the app would get a warning, but the units would beep at everything that passes them, and the battery would run down a little faster.
- **Standard Battery and Device Information services.** Generic BLE apps would show them, but each unit would have more to discover, which counts when the app connects to 8 at once.
- **Sending the gap instead of the raw distance.** Setting the zero offset would then need a separate raw mode, and a reading would depend on which setup version the unit holds.
- **Passing ST's range status through.** The app would depend on the VL53L1X's numbering, and a different sensor would change the protocol.
- **A record that applies as soon as it is written.** A half-written or rejected record could be read by another phone before the unit had checked it.
- **Accepting a newer record without the PIN.** Any phone could bring a unit up to date, but anyone could also overwrite the setup by raising the version, so the PIN would protect nothing.
- **An unlock that lasts until the disconnect.** Simpler, but a unit the app had just brought up to date before a pass would stay unlocked, and so silent, for the whole pass.
- **Start and stop commands for the readings.** A phone could stay connected with the sensor off, but a missing start command would look like a dead unit.
- **The link-loss rule after every disconnect, without Release.** Simpler, but a driver who switches the app to the background with the car stopped in front of a unit would leave it beeping for up to 2 minutes.
- **A new service UUID for every incompatible version.** An old app would find no units at all, instead of telling the driver to update.

## Consequences

- **Firmware.** Because MicroPython can't reject a write, every result comes back through the control point. The firmware enlarges the setup staging buffer to 512 bytes with `gatts_set_buffer`, restarts advertising after every connect and disconnect, and sends notifications with `gatts_write(..., send_update=True)` so that they reach only a subscribed phone. It starts the sensor on connect, because it can't tell when the phone subscribes. Because it can't tell why a link dropped, the app announces a deliberate disconnect with Release.
- **Restarts.** The firmware tells a power-on from a restart with `machine.reset_cause()`, and keeps the wrong-PIN count where a restart doesn't clear it, such as the ESP32's RTC memory.
- **Sensor driver.** The driver's `read()` waits up to 1 s for a measurement. The firmware needs a shorter wait, through `data_ready()` or a timeout parameter added to the driver, to report a fault within 100 ms.
- **Saving.** The record and the PIN live in one file, replaced by writing a new file and renaming it over the old one. A unit whose file can't be read starts unassigned.
- **Long writes.** The record is longer than one packet at small MTUs. The app writes it with response and lets the phone's stack split it into a long write. Long writes into MicroPython's buffer are checked on a unit when the firmware is written.
- **Company ID.** `0xFFFF` is reserved by the Bluetooth SIG for testing and must not ship in a product. Before any public release, a new ADR replaces it with a registered company ID.
- **The passage name is broadcast.** Anyone scanning nearby can read it, so the app says so when the passage is named.
- **The setup is public.** Any phone in range can read the record, including the zero offsets and the gate positions. Only the PIN is kept back, and it can be recorded off the air.
- **Claiming units.** Anyone in range can set up an unassigned unit, or set a new PIN on a unit within 2 minutes of it being switched on.
- **A prankster can lock a unit out** with 5 wrong PINs in a row. The owner then switches it off and on before changing the setup. Driving is unaffected.
- **The phone keeps the PIN.** The app encrypts it with a key held in the Android Keystore, or stores it in the iOS Keychain. A unit that was off during a PIN change keeps the old PIN, and is unlocked with the old PIN or reset in its PIN reset window.
- **The app has to lock.** Every unit the app unlocks stays silent until a Commit, a Lock or the disconnect, so the app locks each one as soon as it is done with it.
- **No phone, no warning.** A driver without the app gets no signal from the units. After a dropped link, a unit keeps signalling for up to 2 minutes while a car stays in front of it.
- **Signal failures.** A car that fades into a signal failure shows as no data for its first second, and as an empty gate after that. Whether dark and glossy cars read that way is checked against real cars before the first release.
- **Battery.** Switching the sensor off while no phone is connected saves only the sensor's share of the current. The board and its radio, which keep advertising, draw most of it, so a battery still lasts about a day, as [ADR 0001](0001-prototype-hardware.md) expects. A longer life needs a low-power mode, which is a decision of its own.
- **Limits fixed by the format.** A passage has up to 4 gates and 8 units, a passage name up to 29 bytes and a unit name up to 16 bytes of UTF-8. A record of format 1 is at most 269 bytes.
- **Tests.** The spec's examples are byte-exact, and the firmware's and the app's tests decode and encode them.
