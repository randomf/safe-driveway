# 6. Kotlin Multiplatform for the mobile app

- **Status:** Accepted
- **Date:** 2026-11-08

## Context

The app runs on the phone in the car and has to work on both Android and iOS ([ADR 0002](0002-ble-communication.md)). Bluetooth Low Energy is at its core. As the GATT central, it connects to every unit at the passage: up to 8, in up to 4 gates along the passage, each with a unit on one or both walls. Each unit sends 20 distance readings per second, and a reading has to reach the screen within 100 ms; the VL53L1X can measure at up to 50 Hz ([ADR 0004](0004-vl53l1x-distance-sensor.md)). From those readings the app shows the driver how far the car is from each wall, where it is in the passage and which way to steer, and sounds an alarm when it gets too close. It runs in the foreground while the car passes through. [app/features.md](../../app/features.md) describes the features in full.

The project is small, so writing and maintaining the app twice, once per platform, would cost too much. The project is licensed under the AGPL-3.0, so every library the app ships must have a compatible licence.

## Decision

The app is written in **Kotlin Multiplatform** (KMP), as one project in `app/`:

- **Shared code:** one Kotlin module, used by both Android and iOS, holds the BLE connections, the decoding of the readings and the logic that turns them into what the driver sees and hears.
- **BLE:** [Kable](https://github.com/JuulLabs/kable) by JUUL Labs, under the Apache-2.0 licence. It puts Android's BLE API and Apple's Core Bluetooth behind one coroutine-based API, and delivers each peripheral's notifications as a Kotlin `Flow`.
- **UI:** [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) on both Android and iOS.
- **Platform code:** what Kable doesn't cover goes in each platform's own source set, written in Kotlin against the native API.
- **Android version:** Android 12 (API level 31) and later. From Android 12, an app that declares it never uses Bluetooth to find the phone's location needs only the Bluetooth permissions, and no location permission or location services.

## Alternatives considered

- **Flutter.** It has the largest cross-platform ecosystem and fast UI iteration. Its most used BLE plugin, flutter_blue_plus, has moved to its own licence, whose recent versions require a paid licence for any for-profit organisation, even during development. The app would also reach the platform's BLE API only through a plugin, so anything the plugin lacks means writing a native extension in both Kotlin and Swift.
- **React Native.** Its main BLE library, react-native-ble-plx, is part-way through a rewrite for React Native's New Architecture, and it is unclear whether it stays maintained upstream. It has the same plugin layer between the app and the platform's BLE API as Flutter.
- **Two native apps, in Kotlin and Swift.** The best fit for each platform, but everything, including the BLE handling, is written and tested twice.
- **.NET MAUI or Capacitor.** Both have BLE plugins, but far fewer apps use them for BLE.

## Consequences

- Building and signing the iOS app needs a Mac with Xcode. This would hold for every cross-platform option. The Android app builds on Linux, macOS or Windows.
- The app is built with Gradle and a JDK, not with the device's uv ([ADR 0003](0003-device-development-toolchain.md)). Like the device, it has `just` tasks of its own, in `app/` ([ADR 0008](0008-app-development-toolchain.md)).
- Kable's API has not reached 1.0, so an upgrade can break the app. The app pins its version, and an upgrade is tested against a real device.
- Phones limit how many BLE connections they hold at once, and 8 units is at that limit. Up to Android 14 the limit is 7, and from Android 15 it is 8 by default, shared with any watch or earbuds already connected; phone makers can change it. iOS has a limit that Apple doesn't publish and that varies by model. The app connects to as many units as the phone allows and shows the ones it couldn't connect. Before the first release, 8 units are tested on an Android 13 or 14 phone, an Android 15 or later phone and an iPhone. If that fails, a new ADR has the units relay each other's readings over ESP-NOW, so that the phone needs fewer connections.
- 20 readings per second from each unit fit the connection interval both platforms use, about 30 ms. iOS chooses the interval itself; on Android the app can ask for a shorter one through Kable. MicroPython can't ask for a different interval from the unit's side.
- Compose Multiplatform is younger on iOS than on Android. If the iOS screens need to look more native, they can be rewritten in SwiftUI while the shared module stays.
- The decoding of the readings and the logic are written and tested once, in the shared module.
- Each platform still needs its own Bluetooth setup. On Android the app asks the user for the `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` permissions, and declares `BLUETOOTH_SCAN` with `neverForLocation`. iOS needs `NSBluetoothAlwaysUsageDescription` in the app's `Info.plist`.
- Phones with Android 8 to 11 can't run the app. Supporting them would mean asking for the location permission and having the driver turn on location services, as a second way through the app's start.
- The usual editors for KMP are Android Studio and IntelliJ IDEA. VS Code's Kotlin support is limited, so the VS Code workspace from ADR 0003 helps less with the app than with the device.
