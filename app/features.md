# Mobile app features

The app runs on a phone in the car. It connects to the units at a passage, shows the driver how far the car is from each wall and which way to steer, and sounds an alarm when the car gets too close. It is written in Kotlin Multiplatform for Android and iOS ([ADR 0006](../doc/adr/0006-kotlin-multiplatform-app.md)) and talks to the units over Bluetooth Low Energy ([ADR 0002](../doc/adr/0002-ble-communication.md)).

This file describes what the first version supports. The communication protocol between the app and the units will be specified in `doc/`.

## Principles

- **A driving aid.** The driver stays responsible for the car. The app says so when it is first opened.
- **Foreground only.** The app works while it is open on screen and does nothing in the background.
- **One phone per passage at a time.** Several phones can use the same passage, but only one is connected to it at a time.
- **Nothing leaves the phone.** There are no accounts, no cloud, no analytics and no crash reporting.
- **Languages.** English and Czech. Every text the app shows lives in resource files from the start.

## Concepts

- **Unit:** one remote device ([README](../README.md#prototype-hardware)). It measures the sideways distance to the car while the car's body is in front of its sensor.
- **Passage:** a narrow place the car drives through, such as a garage entrance or a driveway between two walls. A passage has up to 4 gates and up to 8 units.
- **Gate:** a point along the passage with a unit on the left wall, the right wall or both, facing each other. Its position is its distance from one end of the passage.
- **Segment:** the stretch of the passage between two neighbouring gates.
- **Zero offset:** the distance from a unit's sensor to the line the car must not cross, such as the wall face, or a door frame or pillar that sticks out further. The gap shown is the reading minus the zero offset. It also lets the sensor sit at least 4 cm behind that line, so it never has to measure below the VL53L1X's 4 cm minimum ([ADR 0004](../doc/adr/0004-vl53l1x-distance-sensor.md)).
- **Thresholds:** a warning gap and a critical gap, set per passage in 0.5 cm steps. They default to 15 cm and 5 cm. A tight passage needs smaller values, or the warning sounds all the time.

## Features

### Units and connections

- The app connects straight to every unit of the passage: up to 8, or as many as the phone allows (see [Risks](#risks)).
- Each unit shows one status: searching, connecting, connected, reconnecting, couldn't connect (phone limit), in use by another phone, low battery or sensor fault.
- The driver can connect or disconnect a unit, reconnect all units, forget a unit, or replace a broken unit with a new one that takes over its setup.
- While the app is open, it reconnects lost units by itself.
- Each unit sends 20 readings per second. A reading reaches the screen and the sound within 100 ms of being measured.
- A reading older than 0.5 s shows as "no data", never as a gap. If a unit goes silent while the car is in the passage, the app plays a distinct warning sound.
- The battery level shows as a percentage on units fitted with the battery divider ([ADR 0001](../doc/adr/0001-prototype-hardware.md)). When the passage is opened, the app warns about units with a low battery.

### Starting and ending a pass

- The driver opens the app before driving into the passage. It picks the passage whose units it can see, connects to all of them and keeps the screen on.
- If units from more than one passage are in range, the app asks which passage the driver means and remembers the answer for those units.
- The app disconnects from the units when it goes to the background, or after a few minutes with no car at any gate.

### Setting up and sharing a passage

The passage's setup is stored on its units, with a version number, so that any phone can read it. Each phone stores only its driver's preferences: whether they drive into the passage forwards or reverse in, sound on or off, and the volume.

The first phone sets up a passage like this:

1. Create the passage and name it.
2. The app lists nearby units that don't belong to a passage yet.
3. Tap *identify* on a unit to make it blink and beep, to tell which unit is which.
4. Assign each unit a gate and a side, and choose whether its own buzzer and LEDs signal the gap.
5. Enter each gate's position along the passage.
6. Set each unit's zero offset, either by typing it in or by holding a flat board on the line and tapping *set zero*. Live readings show the result.
7. Set the thresholds and the passage PIN. The app writes the setup to every unit.

After that:

- Any phone with the app can connect to the passage and show its readings. A second phone opened near the units finds the passage and works straight away.
- Changing the setup needs the passage PIN. The PIN guards against accidents and casual mischief, not against a determined attacker, because it travels over BLE unencrypted. A forgotten PIN is reset by switching each unit off and on and setting a new PIN within 2 minutes.
- If a unit was switched off while the setup changed, the units disagree. The app uses the setup with the highest version number and writes it to the units that are behind.
- The app remembers every passage it has used, for example a garage and a gate.
- Each unit's details show its name, gate, side, zero offset, firmware version, battery, signal strength and sensor status.

### Main screen

One screen shows everything the driver needs while driving through. It is meant to be read at arm's length, in a phone holder: large digits, high contrast and a dark theme by default.

- **Top:** the steering suggestion as a large arrow, and the forwards/reversing switch.
- **Middle:** the passage seen from above. Both walls are drawn, and each gate is a line across the passage at its real spacing. Each unit's gap is shown in 0.5 cm steps, in green, in orange below the warning threshold, in red below the critical threshold, or in grey as "no data".
- **Bottom:** one status chip per unit, showing its connection and battery. Tapping a chip opens the unit's details.

What the screen shows:

- The car is a rectangle at the gates that see it. When two gates see it at once, it is tilted by its angle in the passage. Its position along the passage is approximate: the app knows which gates the car is at and which segment its front is in, but no more. The app needs no car dimensions.
- The screen's left is always the driver's left. The car moves up the screen when driving forwards and down when reversing.
- The app tells which end the car enters from by which gate sees it first. Whether the car is reversing comes from the switch, which starts at the driver's default for the passage.
- Before the car arrives, the screen shows how many units are ready, for example "4 of 4 units ready".
- Below the critical threshold, the screen's border flashes red.
- The screen works in portrait and landscape. In landscape, the passage view takes the left half, and the arrow and the smallest gap the right half.

### Alarms and suggestions

- Between the warning and the critical threshold, the phone beeps, faster as the gap closes. Below the critical threshold, it plays a continuous tone.
- Alarms play even when the phone is on silent or Do Not Disturb, at a volume set in the app. Other audio, such as music or navigation, is turned down while the car is in the passage.
- The sound goes through the phone's current audio output. Bluetooth car audio commonly adds 100–200 ms of delay, so the screen and the units' own buzzers are the fastest warnings.
- The units' own buzzers and red LEDs signal the gap too, unless the passage's setup switches them off.
- The suggestion arrow says how to turn the steering wheel: left, right or keep straight. It shows only while a gate sees the car.
  - When two gates see the car, the app projects the car's current heading to the next gate ahead. If one predicted gap there is smaller than the other by more than a small margin, it suggests turning the wheel to even them out.
  - When one gate sees the car, it suggests moving towards the bigger gap.
  - At a gate with a unit on one side only, it suggests steering away from that wall once the gap is below the warning threshold.
  - When reversing, the advice is still given for the wheel: the rear of the car moves the way the wheel is turned.

## Out of scope for the first version

- Running in the background, or opening by itself when the car approaches
- CarPlay and Android Auto
- Firmware updates over BLE
- A history of passes
- Accounts and cloud sync
- Spoken prompts
- A sensor pointing along the passage, for a smooth position along it
- Analytics and crash reporting

## Needs from the units

What the app needs from the units' firmware. The protocol spec in `doc/` will define how.

- Distance readings, 20 per second, each with a status that says whether it is valid.
- The battery level, on units fitted with the battery divider.
- The firmware version and the sensor's status.
- The stored setup: the passage's ID, name, setup version and PIN, the positions of all its gates and its thresholds, and the unit's own name, gate, side, zero offset and whether its buzzer and LEDs signal the gap. A unit accepts changes only with the PIN.
- A 2-minute window after the unit is switched on, during which a new PIN can be set without the old one.
- An *identify* action that blinks the LEDs and sounds the buzzer.
- The unit's own buzzer and red LED signal the gap using the passage's thresholds and the unit's zero offset.
- One phone connected at a time. While connected, the unit keeps advertising with an "in use" flag, so that other phones show "in use by another phone" instead of not finding it. The ESP32 stops advertising when a phone connects, so the firmware has to restart it.

## Risks

- **Number of connections.** Phones limit how many BLE connections they hold at once, and 8 units is at that limit ([ADR 0006](../doc/adr/0006-kotlin-multiplatform-app.md#consequences)). Before the first release, 8 units are tested on an Android 13 or 14 phone, an Android 15 or later phone and an iPhone. Most passages need 1 or 2 gates, so 2 to 4 units, well within the limit.
- **Accuracy.** Gaps are shown in 0.5 cm steps. The VL53L1X may not be that accurate on every car's paint or in strong light, so the display steps are checked against real readings before the first release.
