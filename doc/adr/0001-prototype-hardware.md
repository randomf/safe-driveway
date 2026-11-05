# 1. Prototype hardware for the remote device

- **Status:** Accepted. The distance sensor is superseded by [ADR 0004](0004-vl53l1x-distance-sensor.md)
- **Date:** 2026-11-01

## Context

The first prototype of the remote device has to be built quickly from cheap, off-the-shelf parts. It sits at the side of a narrow passage, measures the gap between the passage wall and the side of the car, signals that distance locally and reports it to the phone in the car.

A single unit has to:

1. communicate with the phone over Bluetooth,
2. measure the distance to the car with a laser sensor,
3. signal the distance with a buzzer,
4. show a ready state with a green LED,
5. signal the distance with a red LED,
6. be switched on and off with a power switch.

It runs from a battery, is charged over USB, and its firmware is written in MicroPython. The first prototype is used indoors or in a garage, out of the rain.

## Decision

The unit is built from these parts:

- **Microcontroller:** an ESP32 development board compatible with the WEMOS LOLIN32 Lite, with a USB-C port, a CH340 USB-to-serial chip, a TP4054-class Li-Po charger (marked "LTH7", about 400 mA) and a JST-PH 2 mm battery connector ([dratek.cz 232739](https://dratek.cz/arduino-platforma/232739-vyvojova-deska-esp32-s-bms-pro-lipol-baterii-wi-fi-bluetooth-micropython-usb-c.html)). Its built-in radio provides Bluetooth Low Energy (see [ADR 0002](0002-ble-communication.md)).
- **Distance sensor:** a VL53L0X laser time-of-flight sensor on a breakout module with its own regulator and I2C pull-ups, powered from 3.3 V.
- **Buzzer:** a YL-44 active buzzer module ([dratek.cz 849](https://dratek.cz/arduino-platforma/849-eses-akusticky-bzucak-pro-jednodeskove-pocitace.html)), powered from 3.3 V so that the ESP32's 3.3 V logic can switch it fully off.
- **Indicators:** one green and one red 5 mm LED, each driven straight from a GPIO through a 220 Ω resistor.
- **Battery:** an 803450 Li-Po cell, 3.7 V, 1500 mAh, plugged into the board's JST-PH connector through an extension lead.
- **Power switch:** a latching 19 mm switch with a 6 V white LED ring, in series with the battery's positive lead. The ring is wired across the switched battery line and works as a power light.
- **Battery monitoring:** optional. A 100 kΩ / 100 kΩ divider from the switched battery line to GPIO35. The firmware must work without it.
- **Construction:** wires soldered point to point, with no carrier board.
- **Enclosure:** a 120 × 80 × 50 mm IP65 PS/ABS box ([dratek.cz 1442](https://dratek.cz/arduino-platforma/1442-plastova-prumyslova-krabicka-ip65.html)) for the first prototype, with a USB-C panel-mount extension for charging and flashing. A 3D-printed enclosure with a USB-C slot follows later.
- **Left and right units:** there is one build. The sensor looks out through the lid, the LEDs and the buzzer sit on the end wall facing the approaching car, and the switch and USB-C sit on the opposite end wall. A unit for the right-hand wall is a left-hand unit mounted upside down. Which side a unit is on is a software setting, not a hardware variant.

The pin assignments, wiring and assembly steps are in [device/prototype-assembly.md](../../device/prototype-assembly.md).

## Alternatives considered

- **Switching the ESP32's EN pin instead of the battery lead.** This would let the battery charge while the unit is off, but the regulator and the sensor would keep drawing current. Cutting the battery lead draws nothing when off.
- **A passive buzzer.** It can change pitch, but needs PWM and a separate driver transistor. The active YL-44 module was already on hand and is enough for beep-rate signalling.
- **A sealed sensor window.** A window in front of the VL53L0X adds crosstalk that needs calibration. The first prototype stays indoors, so an open aperture is enough.
- **A perfboard carrier.** Easier to repair, but larger. Point-to-point wiring was preferred for the first unit.
- **Mirrored left and right builds, or a hardware jumper for the side.** Both add variants to build and stock. A single build that is flipped for the other side, with the side set in software, keeps every unit interchangeable.

## Consequences

- The board does not expose GPIO21, so the sensor's I2C bus uses GPIO18 (SCL) and GPIO19 (SDA), which are MicroPython's defaults for `I2C(0)`.
- The board has no battery voltage divider and no 5 V or battery pin on its header. Without the optional divider, the unit cannot report its battery level.
- The battery charges only while the switch is on. With the switch off and USB connected, the board still runs from USB, and the power ring may glow because the charger drives the empty battery terminal.
- With the radio and the sensor running continuously, a full battery is expected to last roughly a day. The power switch is what keeps the unit from draining between uses, until the firmware gains a low-power mode.
- The VL53L0X reads reliably from a few centimetres to about 1.2 m indoors. It gets weaker on dark or glossy paint, and readings closer than about 3 cm are unreliable.
- The YL-44 is quieter at 3.3 V than at 5 V.
- The open sensor aperture makes the unit unsuitable for outdoor use. Moving outdoors needs a new decision on sealing the sensor.
