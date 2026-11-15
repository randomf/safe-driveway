# 11. The app's connection to the units, its storage and its sounds

- **Status:** Accepted
- **Date:** 2026-11-15

## Context

The app's screens run behind one interface, `Backend`, which a demo backend implemented first ([ADR 0009](0009-app-screens-and-navigation.md)). The units' firmware implements the protocol ([ADR 0007](0007-ble-protocol.md), [ADR 0010](0010-device-firmware.md)). The app now needs the rest of [app/features.md](../../app/features.md): the connections to the units, what the phone stores, the alarm sounds, a screen that stays on, and letting go of the units when the app leaves the screen or no car comes.

The app is built for Android only for now ([ADR 0008](0008-app-development-toolchain.md)), but its shared code has to build for iOS too. Kable covers BLE on both ([ADR 0006](0006-kotlin-multiplatform-app.md)). Storage, sound, the screen and the app's lifecycle are each platform's own.

## Decision

- **One state, and a shell around it.** `LiveBackend` keeps everything it knows in one immutable `LiveState`, which it changes on the main thread only. The state holds what the phone stores, the units it hears, its link to each unit and the open passage. Pure functions turn it into what the screens see, and decide which units the phone connects to. Around it, the shell runs the scan, a `Link` per unit, the writes and the PIN resets. The protocol's byte layouts are pure functions in the `protocol` package, tested against the examples in [doc/ble-protocol.md](../ble-protocol.md).
- **Scanning.** While the app is in the foreground with Bluetooth ready, it scans for the service UUID in Android's low-latency mode, so that a unit is heard within a second. A unit not heard for 4 s is out of range.
- **Which unit is which.** An ESP32's Bluetooth address is its factory MAC address plus 2, so on Android the app knows each unit's ID from its advertisement, without connecting. It tells which units of a passage are in range or in use, and lists new units by their short ID. Unit info confirms the ID once the app connects. iOS doesn't show addresses: there, the app will connect to read Unit info.
- **A link per unit the app needs.** The app connects to the open passage's units and to the units that advertise its ID, which may hold a newer setup. It also connects to the units that a setup screen, a write, Identify or a PIN reset needs. A link waits for the unit's advertisement, and shows *in use* while another phone holds the unit. A unit goes on advertising as in use for a few seconds after this phone lets go of it or loses the link, so for 6 s after, the app counts the flag as its own: in the passages list, in the link, and in a write. It connects with a 10 s timeout and subscribes before connecting, so Kable enables the notifications during the connection and again after each reconnection. It then reads Unit info, Status and Setup. It connects again whenever the link drops, waiting 1 to 5 s after failures. It asks Android for an MTU of 256, and keeps Android's connection interval, about 49 ms with the first unit, which carries 20 readings a second. The app lets go of a link when nothing needs the unit any more.
- **The phone's connection limit.** Android doesn't say why a connection failed. A connection that fails while the phone already holds 6 or more is shown as the phone's limit, and tried again every 10 s.
- **Commands.** One at a time, with 2 s for the answer. After an unanswered Unlock, the app reads Status; after an unanswered Commit, it reads Setup and compares the record. A unit the app unlocked is locked again whenever a later step fails, and after *Update*, which unlocks them all.
- **PINs.** The phone keeps the PIN before the last change it made. A unit that refuses the current PIN is unlocked with that one, which also clears its count of wrong PINs, and gets the current PIN with the next setup it takes. A unit that refuses both makes the phone forget its PINs, so that bringing the unit up to date on each connection doesn't lock it out. A typed PIN that no unit answers is reported as unchecked, not taken.
- **Storage.** One file, `phone.bin`, in the app's private storage. It holds the passages the phone has used, each with its newest setup as the protocol's passage record, the driver's preferences, and the PIN and the one before it, and the driver's choices among passages. A passage whose record can't be read back is left out, and the others kept. Its format, with a version byte in front, is pure code in the shared module. The file is encrypted with AES-256-GCM under a key that the Android Keystore holds and never lets out, and replaced whole with an `AtomicFile`, at most every 0.3 s while it keeps changing, as when a slider is dragged. A file that can't be read or decrypted is replaced by an empty one. iOS will keep the same bytes in its Keychain.
- **Sounds.** The app makes its tones in code and plays them through an `AudioTrack` with the alarm usage, so they play while the phone is on silent, and through Do Not Disturb unless the driver blocked alarms there:
  - below the warning gap, 70 ms beeps at 1.5 kHz, once a second at the warning gap and up to about 6 times a second just above the critical gap, as the units' own buzzers do
  - below the critical gap, a continuous 1.5 kHz tone
  - when a unit stops sending readings within 5 s of a car being seen, a falling two-tone at 1.2 and 0.8 kHz

  The passage's volume setting scales the sound within the phone's alarm volume. The app never changes the phone's volume, and the passage settings say when the alarm volume is low. While a car was seen in the last 5 s, the app asks the other audio to duck. Nothing sounds while the passage's sound is off, while its units are unlocked for a change of their setup, as the units themselves don't signal then, or while the app isn't on screen.
- **The screen** stays on while the main screen shows, until the app pauses.
- **Letting go.** When the app leaves the screen, it lets a setup write that is under way finish, then stops scanning and lets go of every unit. When it comes back, it stays on the same screen, connects again, and unlocks the units again if their setup was being changed. Before letting go of a unit on purpose, it sends Release. The exception is a unit of the open passage when a unit had a gap in the last 5 s: the car may be between the walls, so the units go on warning for 5 minutes, as after a lost link ([ADR 0007](0007-ble-protocol.md)).
- **The idle pause.** After 5 minutes with no unit seeing a car, and no change of the setup, write or PIN reset under way, the app sends Release, lets go of the units and lets the screen sleep. The main screen offers *Reconnect*.
- **One activity.** Android starts the app's activity as a single instance, so a second launch can't start a second set of connections that compete for the units.

## Alternatives considered

- **State in each link, or a flow per unit.** Fewer copies with each reading, but the decisions that span the units, such as which to connect, which setup is the newest and when the car was last seen, would read several sources at different moments.
- **Connecting to every unit in range to read its ID.** It works on every platform. But connecting switches a unit's sensor on, and the passages list would hold connections to units the driver never chose.
- **EncryptedSharedPreferences, or DataStore with kotlinx.serialization.** The first is deprecated. The second adds two libraries and a Gradle plugin to store a few hundred bytes, and the PIN would still need its own encryption.
- **Sound files, played with SoundPool.** Easier to swap for nicer sounds, but they need files under a licence that fits the AGPL, and beeps whose pace follows the gap would be stitched from short clips.
- **Setting the phone's alarm volume while a passage is open.** The same loudness on every phone, but an app that is killed before it restores the volume can leave the driver's morning alarm quiet.
- **Asking for a high connection priority.** Connection intervals of about 15 ms and less delay, but up to 8 units share the phone's radio, and the units' batteries pay for it.
- **Release before every deliberate disconnect,** as ADR 0007 first had it. A phone that locks while the car is between the walls would silence the units' own warning too.

## Consequences

- **Tried on a unit.** On a Pixel 9 Pro with Android 17 and one unit, firmware 0.1.0:
  - finding a passage and connecting, in 1 to 3 s, with the first attempt often refused by Android and the next one succeeding
  - readings, gaps, the steering arrow, and the beeps and the continuous tone
  - the sound for a unit that goes silent, Identify, *in use by another phone* and taking over when it's free
  - unlocking with a remembered PIN, a change of the setup, resetting a forgotten PIN in the unit's window, deleting a passage, and setting up a new one with *Set zero*
  - changing the PIN, and unlocking a unit that still holds the PIN before, which then gets the new one
  - Release, and its absence while a car is in front
  - the idle pause after 5 minutes, and *Reconnect*
  - storage across a reinstall, and demo mode
- **Not yet tried:** more than one unit, the phone's connection limit, a unit out of date or forgotten while out of reach, and iOS.
- **After the app is killed while connected,** as by an install, Android can take about 25 s to connect to the unit again.
- **An empty passage reads as a car** when the opposite wall is within the sensor's range, on the screen and on the units alike, and the app then doesn't pause.
- **iOS** needs the Keychain for storage, its own `AlarmPlayer`, Unit info to tell units apart, and Core Bluetooth's background rules, which this decision doesn't cover.
