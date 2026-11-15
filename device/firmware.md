# Firmware

The firmware runs on a unit's ESP32, in MicroPython. It measures the distance to the car, talks to the app over Bluetooth Low Energy with the protocol in [doc/ble-protocol.md](../doc/ble-protocol.md), and shows what it is doing with the green LED, the red LED and the buzzer. Why it is built this way is recorded in [ADR 0010](../doc/adr/0010-device-firmware.md). This is firmware 0.1.0, for protocol version 1.

## Lights and sounds

Red and green are never on together. When several of these apply, the higher row wins:

| What the unit does | Green | Red and buzzer |
|---|---|---|
| Identify, for 5 s after the app asks | Alternates with red, 4 times a second | Alternates with green, a beep once a second |
| The gap is below the critical gap, or the car is closer than 4 cm | Off | Solid, continuous tone |
| The gap is below the warning gap | Off | A short flash with a beep, once a second at the warning gap and faster as the gap closes, up to about 6 times a second just above the critical gap |
| A car is in front of the sensor | Solid | Off |
| Measuring, with nothing in front | Two short flashes every 3 s | Off |
| Not measuring | One short flash every 3 s | Off |

- **Only some units signal the gap.** The red LED and the buzzer signal the gap only on a unit that belongs to a passage, has its buzzer and LEDs switched on in the passage's setup, and isn't being set up by the app at that moment. Other units show green while a car is in front.
- **Count the green flashes.** One means the unit is on and waits for a phone, two that it measures. Three flashes, in place of those, mean a sensor fault. Four mean the unit is locked out after 5 wrong PINs, until it is switched off and on. A sensor fault wins over a lockout.
- **Long green flashes,** instead of short ones, mean the first 2 minutes after switching on, when a forgotten PIN can be replaced.
- **Low battery** adds a short red flash just after each green group.
- **At switch-on**, green and then red light for a moment.
- **When the unit measures.** It measures while a phone is connected, for the first 10 minutes after it starts, and for 5 minutes after the connection to a phone is lost, so that it warns a driver with no phone or a flaky link. When the app disconnects on purpose, it stops at once. Otherwise it doesn't measure, and shows one green flash every 3 s.
- **Short dropouts don't break a warning.** When the sensor briefly loses a dark or glossy car, the red signal keeps going for up to half a second.

The table holds for every unit. The thresholds come from the passage's setup in the app.

## Installing

Install the firmware once the unit has passed its [bring-up tests](prototype-assembly.md#bring-up-tests), in the `device/` folder:

```bash
just install
```

It compiles the firmware with mpy-cross, copies it to the board and restarts the board, which then starts the firmware whenever it is switched on. Run it again after changing the code. A unit's setup and its notes stay on the board.

The board's filesystem then holds:

| Path | What it is |
|---|---|
| `/main.py` | Starts the firmware. Written by `just install` |
| `/lib/firmware/`, `/lib/vl53l1x.mpy` | The firmware and the sensor driver, compiled |
| `/unit.json` | The bring-up notes: which level silences the buzzer, whether the battery divider is fitted and its offset from a multimeter. Written by `just bringup` |
| `/passage.bin` | The passage record and the PIN, written when the app commits a setup |

- **Save the bring-up notes first.** Without `/unit.json`, the firmware takes a low-triggered buzzer and no battery divider. A high-triggered buzzer then sounds whenever it should be silent. `just bringup buzzer` finds out which level silences it and offers to save that.
- **`just flash` erases all of it.** After flashing, run the bring-up test and `just install` again.
- **The dev tasks stop the firmware.** `just bringup`, `just distance` and `just repl` stop the firmware to use the board. It starts again when the board is reset or switched off and on.

## The log over USB

The firmware prints what it does over USB. Watch it with:

```bash
just monitor
```

It doesn't stop or reset the board. `just monitor --reset` restarts the board first, which the firmware takes for switching it on. `--seconds 20` stops after 20 seconds. Each line starts with the seconds since the firmware started:

```
    0.103  lights: power-on
    0.507  lights: not measuring
    0.511  Safe Driveway firmware 0.1.0, unit 08ad0a5b9fb8 (9FB8), switched on
    0.513  buzzer silent at LOW, battery divider not fitted
    0.516  passage 'Bench', ID 12b3c933, setup version 5, gate 0, left, zero offset 0 mm, warning 150 mm, critical 50 mm, signals the gap
    0.666  sensor answers
    1.010  flags: PIN reset window
    1.018  advertising, and measuring for the first 10 minutes
    1.155  sensor on
    1.161  lights: measuring
    2.164  readings: 21 no target; last 1840 mm no target
    8.248  lights: car in front
    9.281  readings: 21 valid; last 157 mm valid, gap 157 mm
    9.654  lights: warning
   21.362  phone connected
   21.365  flags: in use, PIN reset window
```

There is a line for every event: a phone connecting and disconnecting, each command and its result, a change of the flags or of what the lights do, and the sensor switching on and off. While the sensor runs, a line every second counts the readings of each status and shows the last one. The PIN never appears in the log.

## Testing without the app

`just ble` acts as the phone, with this computer's Bluetooth. Every command but `scan` connects to one unit and sends Release before it disconnects, as the app does. With one unit in range, you don't have to name it. Otherwise give its name, its short ID or its Bluetooth address.

| Command | What it does |
|---|---|
| `just ble scan` | Lists the units in range, with their passage, gate, side and flags |
| `just ble watch` | Shows the unit's info, status and setup, then a summary of its readings every second. `--every` shows every reading, `--seconds 10` stops after 10 s, `--no-release` disconnects as a lost link does |
| `just ble setup --pin 1234` | Makes the unit the only unit of a passage, or changes that passage: sets the PIN on an unassigned unit or unlocks with it, then writes and commits the record. `--warning`, `--critical` and `--zero` set the gaps in mm, `--gate` and `--side` its place, `--quiet` stops it signalling the gap, `--fill` makes the record as long as it gets |
| `just ble identify` | Blinks the LEDs and sounds the buzzer for 5 s |
| `just ble unlock --pin 1234` | Tries a PIN, then locks again |
| `just ble set-pin --pin 5678 --old 1234` | Sets a new PIN. Leave out `--old` on an unassigned unit or in the PIN reset window |
| `just ble forget --pin 1234` | Unlocks and forgets the setup and the PIN |

`just ble --help` and `just ble watch --help` list every option.

On Linux, `just ble` uses BlueZ, which most distributions run, and needs no permissions beyond those of a desktop user. Switch Bluetooth on first. It's untested on macOS and Windows.

## The code

The firmware is in [board/firmware/](board/firmware/), a functional core with an imperative shell around it:

| Module | What it does |
|---|---|
| [protocol.py](board/firmware/protocol.py) | The protocol's byte layouts and values, and the mapping of the sensor's range status |
| [record.py](board/firmware/record.py) | Parsing and checking the passage record |
| [control.py](board/firmware/control.py) | The setup, the PIN, locking and the control point commands |
| [sensing.py](board/firmware/sensing.py) | What the readings mean: a car in front, the gap signal, when the sensor runs without a phone, the battery level |
| [patterns.py](board/firmware/patterns.py) | What the LEDs and the buzzer show at a given moment |
| [main.py](board/firmware/main.py) | The shell: pins, sensor, Bluetooth, files, the clock and the log |

The modules other than `main.py` are pure and use no MicroPython modules. That lets them run in CPython too:

- **Tests:** [tests/](tests/) tests them with pytest. Run the tests with `just test`, and `just lint` before committing.
- **Host tools:** `just ble` imports their byte layouts, so the test phone and the firmware can't disagree about them.

Code in them may use only what both runtimes have. MicroPython has no `namedtuple._replace`, no `zip(strict=...)` and no `itertools`.

## Known limitations

- **No watchdog.** A unit that hangs stays hung until it is switched off and on. After a crash, the firmware prints the error and restarts the board 5 s later.
- **A brownout and the reset button count as switching on.** Either opens the PIN reset window and clears the count of wrong PINs, because MicroPython reports them as a power-on.
- **Advertising restarts every 2 s** while no phone is connected. When a connection fails to be established, the ESP32's Bluetooth stack stops advertising without telling the firmware. The restart puts the unit back on the air within 2 s.
- **The battery level is an estimate** from a generic Li-Po table, under the radio's load.
