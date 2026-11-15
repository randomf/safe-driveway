# End-to-end tests on a phone and a unit

These tests check the app against a real unit: the connections, the commands, the sounds and what the units do when the app lets go ([ADR 0011](../doc/adr/0011-app-connections-storage-and-sounds.md)). The unit tests in `shared/src/commonTest/` cover the pure logic; only these cover the radio. Replay them after a change to how the app talks to the units, its storage or its sounds, and after a Kable upgrade ([ADR 0006](../doc/adr/0006-kotlin-multiplatform-app.md#consequences)).

They were first run on 2026-11-15 on a Pixel 9 Pro with Android 17 and one unit with firmware 0.1.0, and all passed. Test 15 was added with the fixes from the PR's review, and passed too.

## Setup

1. **The phone** is on USB with USB debugging on ([README](README.md#setup)), and `adb devices` lists it. Ask the person at the bench to unlock it, and to turn on *Developer options > Stay awake*, so it doesn't lock again while it charges. An agent can't unlock the phone.
2. **The unit** is on this computer's other USB port with the firmware installed ([device/firmware.md](../device/firmware.md#installing)), and `just monitor` in `device/` shows its log.
3. **This computer's Bluetooth** is on, so `just ble` in `device/` can act as a second phone.
4. **The app** is installed with `just run` in `app/`.
5. **A person at the bench** can hold a hand or a board in front of the sensor when asked. Tests 3, 4, 8 and 13 need them. Ask before each test that needs them, and offer to skip it.
6. **The sensor's view is clear:** the unit log's `readings:` lines say `no target`. Any valid reading counts as a car, even a desk 20 cm away or a wall within 4 m. A car in view keeps the app from pausing, and makes it let go without Release.

Setup is done when the phone is unlocked, the unit's log runs into a file in the background, and the app's log is clear.

## Tools

The commands below use the Android SDK's adb, with the phone's serial from `adb devices`:

```bash
ADB="$HOME/Android/Sdk/platform-tools/adb -s <serial>"
```

- **Screen:** `$ADB exec-out screencap -p > shot.png`. Read a copy at half size, which costs a quarter of the tokens, and double what it shows for tap coordinates, which are in the phone's own pixels:

  ```bash
  python3 -c "from PIL import Image; i = Image.open('shot.png'); i.resize((i.width // 2, i.height // 2)).save('shot-small.png')"
  ```

- **Input:** `$ADB shell input tap <x> <y>`, `$ADB shell input text 1234`, and `$ADB shell input keyevent KEYCODE_BACK` or `KEYCODE_HOME`. While the keyboard is open, Back closes it and stays on the screen. To open the app as its launcher icon does, use `$ADB shell monkey -p io.github.randomf.safedriveway -c android.intent.category.LAUNCHER 1`.
- **Texts** follow the phone's language. This doc names them in English; on a Czech phone, [values-cs/strings.xml](shared/src/commonMain/composeResources/values-cs/strings.xml) has them under the same names as [values/strings.xml](shared/src/commonMain/composeResources/values/strings.xml).
- **The app's log:** clear it with `$ADB logcat -c` before a test, then read it with `$ADB logcat -d | grep SafeDriveway`. It has a line for each connection, command, Release and pause, written by `log()` in [live/](shared/src/commonMain/kotlin/io/github/randomf/safedriveway/live/).
- **The unit's log:** run `just monitor > unit.log` in `device/` in the background, and read it with `grep -vE "readings:|lights:" unit.log | tail`. The `readings:` and `lights:` lines show what the sensor sees and what the unit signals.
- **The serial port** takes one program at a time. Stop the monitor before `just monitor --reset`, and start it again after. Stop it with `pkill -f "host[.]monitor"`: the brackets keep the pattern from matching the shell that runs `pkill`, which would kill it too.
- **Switching the unit on:** `just monitor --reset --seconds 20 > reset.log` restarts the board as switching it on does. That opens its 2-minute PIN reset window.
- **A second phone:** `just ble watch --seconds 25` holds the unit for 25 s.
- **A watcher** is a loop, run in the background, that acts as soon as the unit's log shows what the person was asked to do. Start it before asking, so that the action lands while their hand is still there. For example, to press Home 2 s after the unit sees something:

  ```bash
  until tail -3 unit.log | grep -qE "lights: (car in front|warning|critical)"; do sleep 0.2; done
  sleep 2; $ADB shell input keyevent KEYCODE_HOME
  ```

  To wait for a valid reading instead, use `grep -qE "readings: .*valid; last [0-9]+ mm valid"`.
- **Installing again** kills the app. If it's connected at the time, Android can take about 25 s to connect to the unit again, so go back to the passages list first.
- **The first connection attempt** is often refused by Android: the app's log says `connecting failed: ... Disconnect detected`, and the next attempt succeeds. A test passes when the unit connects within about 10 s.

## Tests

Run them in this order: each starts from the state the one before leaves. The PIN in the tests is the demo's, given in [README.md](README.md#demo-mode).

### 1. Connecting

From a fresh install, accept the driving-aid notice.

**Passes when** the one passage in range opens by itself, the app's log shows `link up after`, `info read after` and `connected, firmware 0.1.0`, the unit's log shows `phone connected` and `MTU 256`, and the main screen says *1 of 1 units ready* or shows a gap.

### 2. A unit's details and Identify

Tap the unit's chip, then *Identify*.

**Passes when** the details show *Connected*, a reading, the signal in dBm, firmware 0.1.0, the sensor as OK, the setup as up to date and the unit ID, and the unit's log shows `Identify: done`.

### 3. Gaps and alarms

Needs the person. Close the details sheet. Record a screenshot a second in the background, and ask the person to hold a hand about 12 cm from the sensor for 5 s, then about 3 cm for 5 s, then take it away.

**Passes when** the screenshots show an orange gap that falls, with *Steer right* for a unit on the left wall, and the person heard beeps that quickened, then a continuous tone, then quiet. The screen's border flashes red below the critical gap.

### 4. A unit that goes silent

Needs an object about 10 cm in front of the sensor, so that the phone beeps. Stop the monitor and restart the unit with `just monitor --reset`.

**Passes when** the person heard the beeps stop, a falling two-tone for a few seconds, and the beeps come back, and the app's log shows `connected` again within about 10 s.

### 5. Resetting a forgotten PIN

Open the passage settings with the ⚙, then *Forgot the PIN?*. Type the new PIN in both fields, close the keyboard, tap *Start*, and restart the unit with `just monitor --reset`.

**Passes when** the unit's log shows `flags: PIN reset window`, then `Set PIN: done` and `Lock: done`, and the screen shows *New PIN set* for the unit. Tap *Done*. The phone now knows the PIN.

### 6. Changing the setup

From the passage settings, tap *Change the setup*. No PIN dialog should appear. Open *Thresholds*, tap + twice on the warning gap, then *Done*, then *Save*.

**Passes when** the unit's log shows `Unlock: done`, `staged a record of`, `Commit: done`, and the passage line with the setup version one higher and a warning gap 10 mm larger, and the screen says *Saved*. Tap *Done*.

### 7. Leaving with no car in front

With the main screen open and the sensor's view clear, press Home. Then open the app from its launcher.

**Passes when** the unit's log shows `Release: done` and `phone disconnected after Release`, and the app's log shows `let go after Release`. After you reopen it, the app is on the same screen and connects again.

### 8. Leaving with a car in front

Needs the person. Start a watcher that presses Home 2 s after the unit sees something, then ask the person to hold a hand about 10 cm from the sensor for about 10 s.

**Passes when** the unit's log shows `link lost: measuring for 5 more minutes` and no `Release`, the app's log shows `let go` without `after Release`, and the person heard the unit's buzzer go on after the phone left the app. Leaving the main screen for the passages list with a car in front gives the same lines.

### 9. In use by another phone

Go back to the passages list, and take a screenshot at once. Run `just ble watch --seconds 25` in the background. About 10 s later, take a screenshot, then open the passage.

**Passes when** the card first says *In range*, though the unit still advertises as in use by this phone for a few seconds. Then it says *In use by another phone*, and the main screen's notice strip and the unit's chip say the unit is in use. After the computer lets go, the unit's log shows `sensor off` and then `phone connected`, and the app connects by itself within a few seconds.

### 10. Demo mode

Go back to the passages list, tap the *i*, scroll to the bottom of About and switch *Demo mode* on. Then go back to About and switch it off.

**Passes when** switching it on opens the demo's *Garage*, with 4 units and the **Demo** tab on the right edge. Switching it off starts the app over on the real unit, and it opens the real passage again.

### 11. What the phone stores

Go back to the passages list, wait a few seconds, install the app again, and open it from its launcher.

**Passes when** the passage opens at once with its unit *Connecting*, before the app has read its setup. *Change the setup* again asks no PIN.

### 12. Deleting the passage

From *Change the setup*, tap *Delete the passage*, then confirm.

**Passes when** the unit's log shows `Unlock: done`, `unassigned` and `Release: done`, and the screen says *Reset*. After *Done*, the passages list shows no passage and *1 new unit nearby*.

### 13. Setting up a new passage

Needs the person. Tap *Set up a new passage*, and type a name.

1. On *Units and gates*, tap *Add* on gate 1's left wall. The list shows *Safe Driveway* and the unit's short ID, with its signal. Tap *Identify* on it, then the unit itself.
2. On *Zero offsets*, the reading shows a dash while nothing is in front, and *Set zero* is off. Start a watcher that taps *Set zero* 1.5 s after a valid reading, then ask the person to hold a board or a hand about 5 cm from the sensor for about 5 s. Then type 0 as the offset again, so that later tests see real distances.
3. Keep the thresholds. Type the PIN in both fields, and tap *Next*.

**Passes when** step 1 gives `Identify: done` and then `Release: done` in the unit's log, step 2 gives an offset that matches the reading and a gap near 0, and saving gives `Set PIN: done`, `staged a record of`, `Commit: done` and the new passage at setup version 1 in the unit's log. *Done* then opens the new passage's main screen, connected.

### 14. The idle pause

Ask the person to keep the sensor's view clear for 6 minutes, and leave the main screen open. Do other work meanwhile.

**Passes when**, 5 minutes after the last reading of a car, the app's log shows `no car for 5m: pausing`, the unit's log shows `Release: done`, and the main screen shows the pause message with *Reconnect*. Tapping *Reconnect* connects again within about 10 s.

### 15. A unit that missed a change of the PIN

Open the passage, then *Change the setup* and *Change the PIN*. Type a second test PIN in both fields, then *Done* and *Save*. Go back to the passages list, wait a few seconds, and put the old PIN back on the unit from this computer, as a unit that was off during the change still has it:

```bash
just ble set-pin --pin <old PIN> --old <new PIN>
```

Open the passage and *Change the setup* again, lower the warning gap a step, then *Done* and *Save*. Finally, change the PIN back to the demo's.

**Passes when** the change gives `Set PIN: done` in the unit's log. The second *Change the setup* opens with no PIN dialog, after `Unlock: wrong PIN` and `Unlock: done` in the unit's log: the phone fell back to the PIN before. Saving then gives `Set PIN: done` before `Commit: done`, so the unit holds the current PIN again.

## Report

Say which tests passed, and for each one that failed or was skipped, what happened and why. Note what the tests can't reach with one unit: several units, the phone's connection limit, a unit that was forgotten while out of reach, and *Update* in the notices, which needs a unit that holds an older setup. After test 15, the unit belongs to the passage set up in test 13, with the demo's PIN, which the phone remembers.
