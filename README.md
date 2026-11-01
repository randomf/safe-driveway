# Safe Driveway

Hardware and software solution to prevent car scratches in narrow driveways and garage entrances

## The problem

Some passages leave almost no room for error: a garage entrance, a gate, a driveway between two walls. When the opening is only 5–30 cm wider than the car, a few centimetres of misjudgement on either side means damaged side panels and scuffed bumpers.

In these situations the usual driver aids often don't help:

- **Judging the distance by eye is unreliable.** From the driver's seat you can't see how much space is left on each side, especially when the car isn't perfectly centred.
- **Side distance sensors may be missing.** Many cars only have parking sensors at the front and rear.
- **Side sensors that are present may be too coarse.** Their resolution is often too low to tell 5 cm from 15 cm, and they typically warn only when you are already too close.
- **The rearview mirrors have to be folded in.** In a tight passage there is simply no room for them, so the driver loses the view along the sides of the car.
- **360° cameras don't solve it either.** Where a car has them, they depend on the mirror-mounted cameras. With the mirrors folded, those cameras no longer see the sides of the car.

The result is that the driver inches through the passage, often with a passenger standing outside to give directions, or stops and gets out repeatedly to check the gap.

## The idea

Instead of relying on what the car can see, **put the sensing at the passage itself.**

You place one or more small remote devices at the narrow spot. They communicate with a mobile phone inside the car and report the car's exact position relative to the passage. The phone shows the driver how far the car is from each side, so they can steer through with confidence.

## Prototype hardware

The first prototype of the remote device is built from off-the-shelf parts:

- **ESP32 development board** compatible with the WEMOS LOLIN32 Lite, with a built-in Li-Po charger, Wi-Fi, Bluetooth, MicroPython support and a USB-C connector
- **VL53L0X laser time-of-flight (ToF) distance sensor**
- **YL-44 active buzzer module** for audible signalling
- **Li-Po battery 803450**, 3.7 V, 1500 mAh
- **Waterproof 19 mm latching switch** with white LED backlight, used as the power switch and power light
- **Green and red 5 mm LED** for visual status
- **IP65 plastic box**, 120 × 80 × 50 mm, to house the electronics at the passage. A 3D-printed enclosure will follow.

The device talks to the phone over Bluetooth Low Energy. [device/prototype-assembly.md](device/prototype-assembly.md) describes how to wire and assemble a unit, and [doc/adr/](doc/adr/) records the decisions behind the hardware and the communication method.
