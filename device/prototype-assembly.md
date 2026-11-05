# Prototype unit: wiring and assembly

This document describes how to wire and assemble one unit of the first Safe Driveway prototype. Why these parts were chosen is recorded in [ADR 0001](../doc/adr/0001-prototype-hardware.md), and the choice of Bluetooth Low Energy in [ADR 0002](../doc/adr/0002-ble-communication.md).

This document covers hardware only. The blink and beep patterns, the distance thresholds and what "ready" means are defined by the firmware and will be documented with it. How to set up a computer to flash and test a unit is in [README.md](README.md).

## What each part does

| Part | Job |
|---|---|
| ESP32 board | Runs the firmware, talks to the phone over BLE using its built-in radio, and charges the battery over USB-C |
| VL53L1X laser sensor | Measures the distance from the unit to the side of the car |
| YL-44 buzzer | Signals the distance with sound |
| Green LED | Shows that the unit is ready |
| Red LED | Signals the distance with light |
| 19 mm switch | Switches the unit on and off. Its white ring is lit while the unit is on |
| Battery divider (optional) | Lets the firmware measure the battery voltage |

## Parts list

| Part | Qty | Notes |
|---|---|---|
| ESP32 development board with Li-Po charger and USB-C | 1 | [dratek.cz 232739](https://dratek.cz/arduino-platforma/232739-vyvojova-deska-esp32-s-bms-pro-lipol-baterii-wi-fi-bluetooth-micropython-usb-c.html). A WEMOS LOLIN32 Lite clone: CH340, TP4054-class charger, JST-PH 2 mm battery socket, 51 × 26 mm |
| VL53L1X laser time-of-flight module | 1 | Any breakout with VIN, GND, SCL, SDA, XSHUT and GPIO1 pins and an onboard regulator works. VL53L0X modules look the same, so check the chip with `just bringup sensor`. See [ADR 0004](../doc/adr/0004-vl53l1x-distance-sensor.md) |
| YL-44 active buzzer module | 1 | [dratek.cz 849](https://dratek.cz/arduino-platforma/849-eses-akusticky-bzucak-pro-jednodeskove-pocitace.html). Pins VCC, I/O, GND |
| Li-Po battery 803450, 3.7 V, 1500 mAh | 1 | With a JST-PH 2.0 plug and a built-in protection circuit |
| 19 mm latching switch with 6 V white LED ring | 1 | Terminals C, NO, NC and the two LED terminals |
| 5 mm LED, green | 1 | |
| 5 mm LED, red | 1 | |
| Resistor 220 Ω, ¼ W | 2 | One per LED |
| 5 mm LED bezel or panel holder | 2 | |
| JST-PH 2.0 two-pin extension lead, 10–20 cm | 1 | Plug on one end, socket on the other. The switch is spliced into it so the battery leads are never cut |
| USB-C panel-mount extension, 20–30 cm | 1 | Male plug to panel-mount female socket. Must carry the USB 2.0 data lines, not just power, or firmware can't be flashed through it |
| IP65 plastic box, 120 × 80 × 50 mm | 1 | [dratek.cz 1442](https://dratek.cz/arduino-platforma/1442-plastova-prumyslova-krabicka-ip65.html) |
| Stranded hook-up wire, 24–26 AWG, several colours | | Silicone insulation is easiest to route |
| Heat-shrink tubing, assorted | | |
| Double-sided foam tape, hot glue, M2 and M3 screws | | For fixing the board, battery, sensor and buzzer |
| *Optional:* resistor 100 kΩ | 2 | Battery divider |
| *Optional:* ceramic capacitor 100 nF | 1 | Battery divider |

## Tools

- Soldering iron and solder
- Multimeter with continuity test
- Wire stripper and side cutters
- Heat gun or lighter for heat-shrink
- Drill with 3 mm and 8 mm bits, a step drill up to 19 mm, and small files
- Hot glue gun
- Computer with a USB cable that carries data, set up as described in [README.md](README.md#setup), for flashing and testing the board

## Pin assignments

Pins are referred to by the label printed on the board. Clone boards place the pins in different orders, so go by the label, never by position.

| Board pin | Connects to | Notes |
|---|---|---|
| `3V` | VL53L1X `VIN`, YL-44 `VCC` | Via splice S1 |
| `GND` | VL53L1X `GND`, wall-A ground lead, divider (optional) | Via splice S2 |
| `18` | VL53L1X `SCL` | I2C clock |
| `19` | VL53L1X `SDA` | I2C data |
| `16` | VL53L1X `XSHUT` | Sensor shutdown. Firmware drives it open-drain: low = off, released = on |
| `17` | VL53L1X `GPIO1` | Sensor interrupt. Firmware enables the internal pull-up |
| `25` | YL-44 `I/O` | Low-triggered: LOW = sound, HIGH = silent. Check yours, see [Bring-up tests](#bring-up-tests) |
| `26` | Green LED anode, through 220 Ω | HIGH = on |
| `27` | Red LED anode, through 220 Ω | HIGH = on |
| `35` | Battery divider midpoint (optional) | Input only, on ADC1, which still works while the radio is on |

Leave the other pins unconnected. In particular:

- `0`, `2`, `5`, `12` and `15` are strapping pins. Anything connected to them can stop the board from booting or flashing. `12` is the most sensitive: if it is high at boot, the flash memory runs at the wrong voltage.
- `22` drives the board's own blue LED. Firmware can use it for debugging.
- `34`, `35`, `VP` (36) and `VN` (39) are inputs only.

## Wiring

### Power harness

The switch is spliced into the red wire of the JST-PH extension lead, between the battery and the board. The battery's own leads are never cut, and the battery can be unplugged at any time.

```
 battery plug          JST-PH extension lead               board battery
 (803450)                                                  socket (JST-PH)

  + red   ──────────────►  C ┐
                             │  19 mm latching switch
                             └ NO ────────┬──────────────────────►  +
                                          │
                                   LED +  ┤
                                          │  white ring (6 V,
                                   LED −  ┤  no extra resistor)
                                          │
  − black ────────────────────────────────┴──────────────────────►  −
```

- The NC terminal stays unconnected.
- The ring is rated for 6 V and has its own internal resistor. From a 3.0–4.2 V cell it glows a little dimmer than at 6 V, which is expected.

### Battery divider (optional)

Fit this if you have the parts. It lets the firmware measure the battery voltage. Without it, everything else works the same, and the firmware must treat the measurement as optional.

```
 switch "LED +" terminal ── 100 kΩ ──┬── 100 kΩ ──► splice S2 (GND)
 (switched battery +)                │
                                     ├── 100 nF ──► splice S2 (GND)
                                     │
                                     └────────────► pin 35
```

Pin 35 sees half the battery voltage: 1.5–2.1 V for a 3.0–4.2 V cell. That is within the ESP32 ADC's range at 11 dB attenuation. The divider draws about 20 µA, and only while the switch is on.

### Signal wiring

Every signal wire runs straight from a board pin to its part. The board has only one `3V` pin and one `GND` pin, so wires that share them are joined in insulated solder splices instead of being stacked on the pin:

- **S1** (near the board): board `3V`, VL53L1X `VIN`, YL-44 `VCC`.
- **S2** (near the board): board `GND`, VL53L1X `GND`, the wall-A ground lead, and the battery divider's ground (optional).
- **S3** (at wall A): the wall-A ground lead, YL-44 `GND`, green LED cathode, red LED cathode.

```
 board pin                                          part
 3V   ──[S1]──┬───────────────────────────────────►  VL53L1X VIN
              └───────────────────────────────────►  YL-44 VCC
 GND  ──[S2]──┬───────────────────────────────────►  VL53L1X GND
              ├── wall-A ground lead ──[S3]──┬────►  YL-44 GND
              │                              ├────►  green LED cathode
              │                              └────►  red LED cathode
              └ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ►  divider ground (optional)
 18   ────────────────────────────────────────────►  VL53L1X SCL
 19   ────────────────────────────────────────────►  VL53L1X SDA
 16   ────────────────────────────────────────────►  VL53L1X XSHUT
 17   ◄───────────────────────────────────────────   VL53L1X GPIO1
 25   ────────────────────────────────────────────►  YL-44 I/O
 26   ────── 220 Ω ───────────────────────────────►  green LED anode
 27   ────── 220 Ω ───────────────────────────────►  red LED anode
 35   ◄ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─   divider midpoint (optional)
```

Notes:

- **Power the YL-44 from `3V`, never from USB 5 V or the battery.** It switches the buzzer with a PNP transistor, and a 3.3 V HIGH from the ESP32 can only turn that transistor fully off if the module's VCC is also 3.3 V.
- **The VL53L1X module needs no extra pull-up resistors.** Its I2C pull-ups and its regulator are on the module.
- **LED legs:** the anode is the long leg; the cathode is the short leg, on the flat side of the rim. Solder each 220 Ω resistor directly to the anode leg and cover it with heat-shrink.
- **If the green LED is much dimmer than the red one,** it is probably a 3 V type. Swap its resistor for 100 Ω.

### Suggested wire colours

| Colour | Signal |
|---|---|
| Red | `3V` |
| Black | `GND` |
| Yellow | `18` SCL |
| Blue | `19` SDA |
| White | `16` XSHUT |
| Grey | `17` GPIO1 |
| Orange | `25` buzzer |
| Green | `26` green LED |
| Purple | `27` red LED |
| Brown | `35` battery sense |

## Enclosure

### Face layout

The same build serves both sides of the passage. The box is mounted with its base against the passage wall and its lid facing the car:

| Face | Size (outside) | What goes there |
|---|---|---|
| Lid | 120 × 80 mm | Laser sensor aperture, centred |
| End wall A | 80 × 50 mm | Green and red LEDs, buzzer sound holes. Faces the approaching car |
| End wall B | 80 × 50 mm | Power switch, USB-C panel socket |
| Long walls | 120 × 50 mm | Nothing |
| Base | 120 × 80 mm | Mounted against the passage wall |

View from the passage, looking at the lid of a unit on the left-hand wall:

```
              long wall: keep empty
         ┌────────────────────────────────────┐
  wall A │                                    │ wall B
  LEDs   │                                    │ switch
  buzzer │            lid   ( ◎ )  laser      │ USB-C
         │                                    │
         └────────────────────────────────────┘
              long wall: keep empty

   ◄── car approaches from this side
```

A unit for the right-hand wall is the same unit turned upside down. Wall A still faces the approaching car and the lid still faces the passage. The long walls are kept empty so that this works. On both sides the green LED is nearer the passage wall and the red LED nearer the passage. Which side a unit is on is set in software, not in hardware.

### Openings in the standard box

The seller lists only the outside dimensions, so check every position against the actual box. Keep holes at least 12 mm from the box edges to clear the lid screw bosses and the gasket.

- **Lid, sensor aperture:** an 8 mm round hole in the centre of the lid. Mount the VL53L1X module on the inside of the lid with its sensor chip (the small black package with two windows) centred in the hole and as close to the lid as possible. Fix it with M2 screws through the module's holes, or with hot glue on the module's edges. Keep glue off the chip, and peel off the thin protective film if there is one over the chip. The aperture must not cut into the sensor's 27° field of view, and nothing shiny may sit inside that cone.
- **Wall A, LEDs:** two holes sized for your bezels (typically 6–8 mm), halfway between the two long walls and about 15 mm apart along the depth. The green LED goes nearer the base, the red one nearer the lid.
- **Wall A, buzzer:** three or four 3 mm sound holes. Fix the YL-44 behind them with its M3 hole, or with hot glue, so the buzzer faces the holes as closely as possible.
- **Wall B, switch:** a 19 mm hole. The switch body reaches about 30–40 mm into the box, so keep that space clear.
- **Wall B, USB-C socket:** cut to the template of your panel-mount extension (usually a rectangular slot plus two M3 screw holes). The extension's plug goes into the board inside the box.
- **Base, mounting:** use the box's own mounting holes if it has them. Otherwise drill two holes in the base, clear of the electronics.

### Inside the box

- **Battery:** fix it flat on the base with double-sided foam tape, away from the switch terminals and any sharp edges.
- **Board:** fix it to the base with foam tape, with its USB-C end toward wall B.
- **Antenna:** the board's PCB antenna (the zig-zag copper trace at the end opposite the USB-C port) must stay at least 10 mm from the battery and the wiring, with nothing metal over it.
- **Sensor wires:** leave about 15 cm of slack so the lid can be opened without pulling on the joints.
- **Strain relief:** secure the wires near the board with a dab of hot glue or a cable tie, so no solder joint carries the load.

## Assembly steps

### 1. Bench-check the board

Do this before soldering anything.

1. Connect the board to the computer by USB. No battery yet. If a USB-C to USB-C cable doesn't power the board, use a USB-A to USB-C cable; some clones lack the resistors that USB-C chargers look for.
2. In the `device/` folder, run `just doctor` and fix what it reports. See [README.md](README.md#setup) for setting up the computer.
3. Flash MicroPython with `just flash`. It shows the board's chip and MAC address, asks before erasing the board, and writes the release pinned in [micropython.toml](micropython.toml).
4. Check that the board responds with `just bringup board`. See [Board](#board).

### 2. Check battery polarity

JST-PH plugs are not wired the same way by every seller. A reversed battery can destroy the board.

1. Find the `+` and `−` marks next to the board's battery socket. If there are none, use the continuity test: the socket pin that connects to the board's `GND` pin is `−`.
2. With the multimeter, find which contact of the battery plug is `+` (red probe on it gives a positive reading).
3. Check the extension lead the same way, end to end.
4. If the polarity doesn't match, move the crimp contacts in the extension lead's housing: lift the small latch with a needle and swap them. Never modify the battery's plug.

### 3. Build the power harness

1. Find the switch's terminals with the continuity test: C–NC is closed when the switch is off, C–NO when it's on. The LED terminals are usually marked `+` and `−`.
2. Cut the red wire of the extension lead. Solder the battery-side end to C and the board-side end to NO.
3. Solder a short wire from NO to the ring's `LED +` terminal.
4. Splice a wire from the black wire of the extension lead to the ring's `LED −` terminal. The black wire stays continuous.
5. Insulate every joint with heat-shrink.
6. Test it before connecting the board: plug in the battery. With the switch off, the board end of the lead reads 0 V and the ring is dark. With the switch on, it reads the battery voltage (3.0–4.2 V) and the ring lights.

### 4. Prepare the box

Mark and drill all openings as described in [Openings in the standard box](#openings-in-the-standard-box). Deburr the holes.

### 5. Mount the panel parts

Fit the switch, the LEDs in their bezels, the USB-C panel socket, the buzzer behind wall A and the sensor on the lid.

### 6. Wire wall A

1. Solder a 220 Ω resistor to the anode leg of each LED.
2. Join the two LED cathodes, the YL-44 `GND` and the wall-A ground lead in splice S3.
3. Solder signal wires to the free ends of the resistors (green → pin `26`, red → pin `27`) and to the YL-44 `I/O` (→ pin `25`) and `VCC` (→ S1).

### 7. Wire the board

1. Solder the wires straight into the board's holes. Don't fit the pin headers that come with the board.
2. Make splices S1 and S2 close to the board, with one wire from each splice into the board's `3V` and `GND` holes.
3. Wire the sensor: `VIN` → S1, `GND` → S2, `SCL` → `18`, `SDA` → `19`, `XSHUT` → `16`, `GPIO1` → `17`.
4. Optional: build the battery divider. Solder the resistors and the capacitor in-line close to pin `35`, with the top of the divider wired to the switch's `LED +` terminal.

### 8. Fit everything into the box

Fix the board and the battery, route the wires, add strain relief and plug the USB-C extension into the board. See [Inside the box](#inside-the-box).

### 9. Run the bring-up tests

Run `just bringup` and answer its questions, then check power and charging by hand. See [Bring-up tests](#bring-up-tests). Close the lid only after every test passes.

## Bring-up tests

Run them with the board connected over USB, through the panel socket. The battery doesn't have to be switched on, except for the battery voltage test. In the `device/` folder:

```bash
just bringup
```

The run tests the board, the laser sensor, its distance readings and clear view, the LEDs, the buzzer and, if fitted, the battery divider. It verifies the board and the sensor by itself, and asks you to move your hand or aim the unit and to answer yes/no questions about what you see and hear. A failed test prints a hint and a link to its section below, and the run goes on with the next one. At the end it prints a summary, the notes the firmware needs and the tests still to do by hand.

To run only some tests, name them, for example while wiring: `just bringup sensor leds`. The board test always runs first. The code of each test is in [board/bringup.py](board/bringup.py), which also says how to run a test by hand in the REPL.

### Board

The board answers over USB and prints its MicroPython version, CPU frequency and unique ID. The unique ID is also the board's MAC address, which tells units apart.

- **The board doesn't respond:** run `just doctor`. A new board needs `just flash` first.
- **It runs a different MicroPython version than the one pinned in [micropython.toml](micropython.toml):** run `just flash`.

### Laser sensor

The sensor answers at I2C address `0x29`, identifies itself as a VL53L1X (model ID `0xEACC` in 16-bit register `0x010F`), disappears from the bus while XSHUT is pulled low, and comes back when XSHUT is released.

- **Nothing found with XSHUT released:** the run tries again with the ESP32's weak internal pull-up on XSHUT. If the sensor then answers, the module has no pull-up on XSHUT. Add a 10 kΩ resistor from XSHUT to `3V`.
- **Nothing found at all:** check `3V`, `GND`, and whether SCL and SDA are swapped.
- **The sensor stays on with XSHUT low:** check the wire from pin `16` to XSHUT.
- **The sensor is a VL53L0X:** VL53L0X modules have the same pins and answer at the same address, so the wiring tests pass. The run recognises one by its model ID, `0xEE` in register `0xC0`. Its registers and driver differ from the VL53L1X's, so replace the module.

### Distance readings

The run measures in short distance mode, about 20 times a second, and shows the distance on one line for 10 seconds while you move your hand in front of the sensor, between 5 and 50 cm. It then asks whether the readings followed your hand. Without a target in range, the line shows "no target" and the sensor's reason.

- **The readings don't change:** check that nothing covers the sensor chip: its protective film, glue, or the lid.

To watch the readings for longer, for example while aiming a unit at the passage, run `just distance` and stop it with Ctrl-C. The driver is [board/lib/vl53l1x.py](board/lib/vl53l1x.py), see [ADR 0005](../doc/adr/0005-vl53l1x-driver.md).

### Clear view

When asked, point the sensor at open space, with nothing within 1.5 m. The run takes 20 measurements and passes if none sees a target closer than 1 m.

- **It sees something close:** something sits in the sensor's 27° field of view, such as the edge of the aperture, glue, a wire or the chip's protective film. See [Openings in the standard box](#openings-in-the-standard-box).

### LEDs

The run lights the green LED, then the red one, then both, and asks each time whether that is what you see.

- **An LED stays dark:** check its wire (green to pin `26`, red to pin `27`), its 220 Ω resistor, and that its long leg, the anode, is on the resistor side.
- **The green LED is much dimmer than the red one:** it is probably a 3 V type. Swap its resistor for 100 Ω.

### Buzzer

The run sets pin `25` HIGH, then LOW, and asks each time whether the buzzer sounds. From the answers it tells which level sounds the buzzer.

- On the usual low-triggered YL-44, LOW sounds the buzzer and HIGH silences it. If yours does the opposite, it's a high-triggered variant. The run says so under "Note for the firmware". Note it down, since the firmware has to know.
- **It sounds at both levels:** the YL-44 is probably powered from USB 5 V or the battery. Power it from `3V`, see [Signal wiring](#signal-wiring).
- **It stays silent:** check the YL-44's `VCC` (splice S1), `GND` (splice S3) and `I/O` (pin `25`).
- **It chirps or hums faintly while the board boots:** add a 10 kΩ resistor from pin `25` to `3V`.

### Battery voltage (optional)

Only for units with the battery divider. The full run asks whether it is fitted; `just bringup battery` doesn't ask. When the run asks, connect the battery and switch the unit on.

The run reads the battery voltage on pin `35`, which should be about 3.0–4.2 V. It then asks for a multimeter reading across the battery and prints the offset between the two. Note the offset down.

- **About 0 V:** check that the unit is switched on and that the top of the divider is wired to the switch's `LED +` terminal.
- **Outside 3.0–4.2 V:** check both 100 kΩ resistors.

### Power and charging

These can't be tested over USB, so check them by hand:

1. Unplug USB and switch on. The ring lights, and the board's `3V` pin reads about 3.3 V.
2. Plug in USB with the switch on. The board's charge LED lights while the battery charges.
3. Switch off with USB still plugged in. The battery stops charging, but the board keeps running from USB.

## Known limitations

- **Charging only while switched on.** The switch disconnects the battery from the charger as well as from the board.
- **The ring may glow when switched off.** With the switch off and USB connected, the ring can glow, because the charger drives the empty battery terminal. This is expected.
- **Battery life.** With the radio and the sensor running continuously, expect roughly 15–25 hours from a full charge. Charging from empty takes about 4–5 hours at the charger's 400 mA. Both are estimates; measure them and record the results here.
- **Indoor use only.** The sensor aperture is open, so the unit must stay out of rain.
- **Sensor range.** In short distance mode, the VL53L1X reaches about 1.3 m, even in strong ambient light. Long distance mode reaches up to 3.6 m in the dark, but only about 0.7 m in strong light. Dark or glossy paint weakens the signal. Below 4 cm, the sensor still detects a target, but the distance isn't accurate.

## Notes for the firmware

- Set pin `25` to the buzzer's "silent" level as early as possible at start-up.
- Drive `16` (XSHUT) open-drain. Never push it high, since a module may run the sensor's logic at 2.8 V.
- Read `17` (GPIO1) with the internal pull-up enabled.
- Treat battery monitoring as optional and configurable, because units without the divider read nothing useful on pin `35`.
- Store which side of the passage the unit is on as a setting. The hardware is the same for both sides.

## Requirements for the 3D-printed enclosure

The 3D-printed enclosure that replaces the standard box keeps the same face layout and adds:

- **USB-C slot:** in the end wall with the switch, aligned with the board's USB-C port. The board sits in a cradle that holds it in place when a plug is pushed in. The slot must admit a typical USB-C plug overmould, about 13 × 7 mm. This replaces the panel-mount extension.
- **Sensor:** the sensor chip flush behind an aperture of at least 8 mm, with the wall no thicker than 2 mm around the aperture and a matte inside surface. No window: adding one needs crosstalk calibration and a new decision.
- **Battery pocket:** about 52 × 36 × 10 mm, with retention, at least 10 mm from the board's antenna.
- **Antenna:** keep the area around it free of metal. Print in PLA, PETG or ASA, not in carbon-filled filament, which can block the radio.
- **Switch:** a 19 mm hole in a wall no thicker than the switch's thread allows.
- **LEDs and buzzer:** holes for the LED bezels and the buzzer's sound holes, as in the standard box.
- **Mounting:** holes or keyhole slots on the base.
- **Symmetry:** no openings on the long walls, so one print serves both sides of the passage.
