# App screens

What each screen of the app shows, and how the driver moves between them. [features.md](features.md) says what the app does; this file says how it looks. The code is in [shared/src/commonMain/kotlin/](shared/src/commonMain/kotlin/io/github/randomf/safedriveway/), one package per group of screens below.

For now the screens are a working prototype on a demo backend ([ADR 0009](../doc/adr/0009-app-screens-and-navigation.md)). Every screen works, but the units are pretend, there is no sound and nothing is stored when the app closes. In a debug build, the **Demo** tab on the right edge of every screen opens the [demo panel](#demo-panel).

## Map

```
open the app
  │
  ├─► Driving-aid notice ──► Bluetooth check, while Bluetooth isn't ready
  │
  ▼
Scanning
  ├─ one passage in range ─────► Main screen
  ├─ several, chosen before ───► Main screen
  ├─ several ──────────────────► Which passage? ──► Main screen
  └─ none ─────────────────────► Passages, which opens the first passage to come in range,
                                   until the driver moves on from it

Passages ──► Main screen ──► Passage settings ──► PIN ──► Setup ──► a setup section
   │             │                  │                       └─► Saving the setup
   │             │                  └─► Reset the PIN
   │             ├─► Unit details ──► PIN ──► Replace the unit ──► Saving the setup
   │             │                      └───► Forget the unit ────► Saving the setup
   │             └─► Notices ──► PIN ──► Saving the setup (brings units up to date)
   ├─► New-passage wizard: Name ► Units and gates ► Gate positions ► Zero offsets
   │                       ► Thresholds ► PIN ► Saving the setup ► Main screen
   └─► About
```

Back on the main screen goes to the passages list, and back on the passages list closes the app. Every screen below the main screen keeps its passage connected, so the units' readings keep coming while the driver changes the setup.

The app works in portrait and landscape. Turning the phone keeps the screen and its state.

## Starting

### Driving-aid notice

Shown every time the app opens: Safe Driveway is a driving aid, the driver stays responsible for the car, and the app can be late or wrong. *I understand* goes on.

### Bluetooth

Shown instead of everything else while Bluetooth isn't ready: the phone has no Bluetooth Low Energy, the app has no permission, or Bluetooth is off. Where the app can help, a button asks for the permission or opens the app's settings. When Bluetooth comes back, the screen underneath is as it was.

### Scanning and *Which passage?*

*Looking for your passage…* while the app listens to the units' advertising for a moment. Then:

- One passage in range opens its main screen.
- Several passages in range ask *Which passage?*, with one large button per passage. The app remembers the choice for those passages, and next time opens the chosen one straight away. The passage's name on the main screen leads back to the list, to choose another.
- With none in range, the app shows the passages list, and opens the first passage that comes in range, as long as the driver hasn't moved on from the list.

### Passages

Every passage this phone has used, and every passage in range that it hasn't, each with *In range*, *In use by another phone* or *Not in range*, and how many units and gates it has. Tapping one opens its main screen; a passage out of range shows its units as searching.

Below the list: how many new units, units that belong to no passage, are nearby, and *Set up a new passage*. The *i* in the top bar opens [About](#about).

## Main screen

Read at arm's length in a phone holder: large digits, high contrast, dark.

```
┌─────────────────────────────────┐
│←  Garage                   ⚙    │  top bar: the name leads back to the passages list
│▲ Gate 1 left: sensor fault +2 › │  notice strip, only when there is something to say
│      ↱            12.5          │  the arrow and the smallest gap
│  Steer right       cm           │
│   [ Forwards | Reversing ]      │
│        ┃           ┃            │
│  14.0 ▐╂───────────╂▌ 31.5      │  gate 2
│        ┃  ┌─────┐  ┃            │
│  12.5 ▐╂──│ car │──╂▌ 33.0      │  gate 1
│        ┃  └─────┘  ┃            │
│[● Pillar  87 %][● Bins   91 %]  │  chips: the driver's left wall left, the right wall right
│[● Gate 1 left ][● Gate 1 right] │
└─────────────────────────────────┘
```

- **Before the car arrives**, the arrow's place says how many units are ready, *4 of 4 units ready*.
- **The arrow** shows while a gate sees the car: a curved arrow to the left or right, or straight up, with *Steer left*, *Keep straight* or *Steer right* under it. It says which way to turn the steering wheel, forwards and reversing alike.
- **The smallest gap** of all units, in large digits, in its colour.
- **The switch** between forwards and reversing starts at the driver's usual direction for the passage.
- **The passage** is drawn from above, with the car's front up: it moves up the screen when driving forwards and down when reversing, and the screen's left is the driver's left. Both walls are drawn, and each gate is a dashed line across at its real spacing. Each unit is a block on its wall at its gate, and its gap is written outside the wall: green, orange below the warning gap, red below the critical gap, or a grey dash for no data. A *too close* reading shows as the most its gap can be, such as *< 0.0*. The car is a rectangle between the gates that see it, nearer the smaller gap, and tilted when two gates see it.
- **Below the critical gap**, the screen's border flashes red.
- **The chips**, one per unit, sit where the units are drawn: a row per gate, the top gate first. Each has a status dot, the unit's name and its battery, or its status when it isn't connected. Tapping one opens the unit's details.
- **The notice strip** shows the most urgent problem in one line, and how many more there are: a unit lost while connected, a sensor fault, a unit that needs a newer app, in use by another phone, beyond the phone's connection limit, with an older setup, or with a low battery. Tapping it opens the notices, each with what to do; an older setup has *Update*, which asks for the PIN. A phone that knows the PIN brings out-of-date units up to date by itself.

In landscape, the passage takes the left half, and everything else the right half, top to bottom: the top bar, the notice strip, the arrow and the smallest gap, the switch and the chips.

### Unit details

A sheet over the lower part of the main screen: the unit's name and status, its gate and side, the live reading and gap, zero offset, battery, signal, firmware, sensor, whether its setup is up to date, and its unit ID. *Identify* makes it blink and beep. *Connect* or *Disconnect*. *Replace…* and *Forget…* ask for the PIN; *Forget…* first asks to confirm, and isn't offered for a passage's only unit, which goes with *Delete the passage* instead.

## Passage settings

The ⚙ on the main screen.

- **On this phone:** *I usually drive in* forwards or reversing, which is where the main screen's switch starts; the alarm sound on or off; the alarm volume.
- **Units:** *Reconnect all units*.
- **Setup:** *Change the setup*, which asks for the PIN, and *Forgot the PIN?*.
- **Remove from this phone**, after a confirmation: the phone forgets the passage, its PIN and the driver's preferences. The units keep their setup.

## PIN

### PIN dialog

Asked before the setup changes, unless this phone already knows the PIN. A correct PIN is remembered. A wrong one says that after 5 wrong PINs in a row the units refuse every PIN until they're switched off and on; once they do, the dialog says so. *Forgot the PIN?* opens the reset.

### Reset the PIN

The driver types the new PIN twice and taps *Start*, then switches each unit off and on. The screen lists the units: *Switch it off and on*, *Setting the PIN, 1:58 left*, *New PIN set*, or *Missed*, to switch that unit off and on again. *Done* once every unit has the new PIN.

## Setting up a passage

### New-passage wizard

*Set up a new passage* on the passages list. Each step shows *Step 2 of 6* and has *Back* and *Next*; *Next* waits until the step is complete.

1. **Name**, up to 29 bytes, with a byte count. The units broadcast it, so anyone nearby can read it.
2. **Units and gates:** the passage board. The driver stands at the end they usually drive in from, looking along the passage: that is end 0, at the bottom of the board. Each gate is a row with a slot on the left wall and one on the right, gate 1 nearest the driver. An empty slot's *Add* lists the new units nearby, each by the last 4 characters of its unit ID, with its signal and *Identify*. A filled slot opens the unit: its name, up to 16 bytes, which defaults to its gate and side, such as *Gate 1 left*; whether its own buzzer and LEDs signal the gap; *Identify*; and *Take it off this gate*. *Add a gate* adds a row at the top, up to 4, and any empty gate can be removed, unless it's the only one; the gates after it move down. Every gate needs at least one unit, and a passage has at most 8.
3. **Gate positions**, only with more than one gate: for each gate after the first, its distance from the gate before it, in cm, measured along the passage from sensor to sensor. Gate 1 is at 0.
4. **Zero offsets:** a card per unit with its live reading and the gap it gives, the offset in cm, and *Set zero*, which takes the current reading. With a flat board held on the line, the gap then reads 0.0.
5. **Thresholds:** the warning and critical gaps, 15.0 and 5.0 cm to start with, in 0.5 cm steps with − and +. The critical gap has to be smaller.
6. **PIN**, 4 to 8 digits, typed twice.

The last *Next* writes the setup to the units and then opens the new passage's main screen.

### Setup

*Change the setup*, after the PIN, opens a list of the same sections: name, units and gates, gate positions, zero offsets, thresholds and *Change the PIN*. Each opens the wizard's screen for it, with *Done* instead of *Next*. On the units board, a unit the passage already has also offers *Replace…*, and taking it off its gate resets it when the setup is saved; new units nearby can be added to any gate.

Nothing reaches the units until *Save*. *Discard*, or back, asks before throwing changes away and leaves the units as they were. *Delete the passage*, after a confirmation, resets every unit in reach to belong to no passage and removes the passage from this phone. A unit out of reach keeps the passage, which shows up again when that unit is back in range.

### Replace the unit

From a unit's details: the new units nearby, each with *Identify*. The chosen one takes over the old unit's gate, side, name and zero offset; the old one is reset if it's in reach.

### Saving the setup

Each unit in turn: *Waiting*, *Saving…*, *Saved* (*Reset* when deleting), *Failed* or *Out of reach*. *Try again* writes again to the units that failed. Units out of reach get a changed setup the next time a phone that knows the PIN finds them. *Done* once every unit has been tried: a unit that failed keeps what it had, shows as out of date, and is brought up to date later; after a delete, it keeps the passage.

## About

The app's version; the driving-aid notice again; the privacy note: nothing leaves the phone; the AGPL licence, with a link to the source code; and the open-source libraries with their licences.

## Demo panel

Only in debug builds, from the **Demo** tab. It sets the world the prototype pretends to be in:

- **Simulated pass:** forwards or reversing, from the usual end or the far end, with plenty of room, tight or too close. *Play a pass* drives a car through the open passage at walking pace.
- **In range:** the garage, the garage and the front gate, nothing, or new units only.
- **Problems:** low battery, sensor fault, setup out of date, the garage in use by another phone, the phone's connection limit, a unit that needs a newer app, a unit lost during a pass, and saving that fails once.
- **This phone knows the PINs.** Otherwise the demo's own passages have the PIN 1234, and a passage set up in the demo has the PIN given to it.
- **Bluetooth:** the phone's own, or pretend it's off, without permission or not supported.

## Numbers and names

- Gaps are shown in cm in 0.5 cm steps, rounded down so that the app never shows more room than there is, and zero offsets and readings in cm with one decimal, with the decimal separator of the app's language: *12.5* in English, *12,5* in Czech.
- Gates are numbered from 1, from end 0, the end the driver usually drives in from. A unit without a name is called by its gate and side as seen from there, such as *Gate 1 left*.
