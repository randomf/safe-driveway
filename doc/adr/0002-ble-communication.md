# 2. Bluetooth Low Energy between the device and the phone

- **Status:** Accepted
- **Date:** 2026-11-01

## Context

Each remote device at the passage has to send its distance readings to a mobile phone inside the car, several times per second, over a few metres. One passage can have more than one device, typically one per side, and the phone has to receive from all of them at once. The app has to work on both Android and iOS.

The device firmware is written in MicroPython on an ESP32 (see [ADR 0001](0001-prototype-hardware.md)). The ESP32's built-in radio supports Wi-Fi, Classic Bluetooth and Bluetooth Low Energy (BLE), so no extra radio hardware is needed.

## Decision

The device and the phone communicate over **Bluetooth Low Energy** using GATT. Each device is a BLE peripheral that advertises and accepts a connection. The phone is the central and connects to every device at the passage.

The GATT services, characteristics and data format will be specified once in `doc/` when the firmware and the app are written, and referenced from `device/` and `app/`.

## Alternatives considered

- **Classic Bluetooth with the Serial Port Profile.** MicroPython's `bluetooth` module supports only BLE. iOS does not let ordinary apps use the Serial Port Profile without the MFi programme.
- **Wi-Fi.** The phone would have to join a network provided by the device, losing its internet connection and possibly conflicting with Android Auto or CarPlay over Wi-Fi. Several devices per passage would make this worse, and Wi-Fi draws more power than BLE.
- **ESP-NOW.** Phones cannot use it.

## Consequences

- No extra hardware: the radio is built into the ESP32 board. The enclosure must not block it, so it is plastic and the board's antenna is kept clear of the battery and wiring.
- Android and iOS both support BLE natively and can hold connections to several peripherals at once, which covers one device per side.
- The firmware can use MicroPython's `bluetooth` module or the `aioble` library.
- BLE has low bandwidth, but distance readings are a few bytes each, so this is no limit.
- On iOS, an app running in the background is restricted in what BLE it can do. The app is expected to run in the foreground while the car passes through.
