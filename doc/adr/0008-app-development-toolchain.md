# 8. Development toolchain for the app

- **Status:** Accepted
- **Date:** 2026-11-14

## Context

The app is written in Kotlin Multiplatform for Android and iOS ([ADR 0006](0006-kotlin-multiplatform-app.md)). Building it takes a JDK, Gradle and the Android SDK, and for iOS a Mac with Xcode. Several people will work on it, on Linux, macOS or Windows, and they need the same versions of all of these. The device already has a toolchain of its own: uv, `just` tasks and a `just doctor` that says what's missing ([ADR 0003](0003-device-development-toolchain.md)).

Nobody working on the project has a Mac yet, so the iOS app can't be run or debugged.

## Decision

- **Build:** the Gradle wrapper in `app/`, pinned to one Gradle release and its checksum. Every library and plugin version is set in the version catalog, `app/gradle/libs.versions.toml`.
- **JDK:** the build runs on JDK 21, set in `app/gradle/gradle-daemon-jvm.properties`. Gradle downloads it if the computer doesn't have it. Any JDK 17 or later can start Gradle.
- **Project layout:** two Gradle modules. `shared` is a Kotlin Multiplatform module with the code for both platforms: the logic, the BLE connections and the Compose UI, with its strings. It's built with the Android Gradle plugin's plugin for Kotlin Multiplatform libraries. `androidApp` is the Android application around it, without Compose code of its own. The iOS app will be an Xcode project, `iosApp`, next to it.
- **Android first:** the first builds are for Android only. `shared` has only the Android target. The iOS targets and `iosApp` come when someone can build and test them on a Mac. Until then, code in `shared/src/commonMain` uses no Android API, so that it builds for iOS later.
- **Editor:** Android Studio. Its setup wizard installs the Android SDK.
- **Tasks:** `app/justfile` has the tasks `doctor`, `build`, `run`, `test`, `lint` and `fmt`, run from `app/` like the device's from `device/`. Each is a thin wrapper around the Gradle wrapper, so Android Studio and the tasks build the same way.
- **Host check:** `just doctor` checks the JDK, the Android SDK, adb and the phone, and prints the fixes without applying them. It's one Java file, `app/tools/Doctor.java`, which `java` runs from source, so it works before Gradle can build.
- **Code style:** ktlint, in Android Studio's style, through its Gradle plugin, and Android Lint in every module. `just lint` runs both, and `just fmt` fixes what ktlint can.
- **Tests:** unit tests in `shared/src/commonTest`, which `just test` runs on the JVM.
- **CI:** a GitHub Actions workflow, `.github/workflows/app.yml`, runs `just lint`, `just test` and `just build` for every pull request and every push to `main` that changes `app/`, and keeps the debug APK for 14 days.
- **Platforms:** setup is documented and tested on Linux. macOS and Windows are documented but untested.

## Alternatives considered

- **Gradle tasks only, without `just`.** Android developers know them, and Android Studio has them as buttons. But someone working on both the device and the app would have two ways of working, and there would be no `doctor`.
- **The doctor as a Gradle task.** It could be in Kotlin, but Gradle can't configure the Android modules until it finds the SDK, which is one of the things the doctor checks.
- **The doctor as a shell script.** It wouldn't run on Windows, where `just` uses PowerShell.
- **One `composeApp` module** with the Android application and the shared code, the older layout of JetBrains' project wizard. The Android Gradle plugin 9 deprecates the Kotlin Multiplatform plugin and the Android application plugin in one module.
- **The iOS target now, built in CI on a macOS runner.** CI would check that the shared code builds for iOS, but nobody could run or debug the iOS app, and every change would wait for a slower build.
- **Whatever JDK `JAVA_HOME` or Android Studio provides.** Simpler, but builds could differ between computers.
- **ktlint's own style.** It differs from how Android Studio formats code, so code reformatted in the editor would fail `just lint`.
- **detekt as well as ktlint.** Its code-smell rules need tuning before they help. It can be added later.
- **IntelliJ IDEA.** JetBrains recommends it for Kotlin Multiplatform, but the Android SDK would have to be installed separately.

## Consequences

- A new contributor installs Android Studio and `just`, runs `just doctor` in `app/` and fixes what it reports.
- Changing a version is a reviewed change to one file. Kable upgrades are tested against a real unit ([ADR 0006](0006-kotlin-multiplatform-app.md)).
- JetBrains publishes Material 3 for Compose Multiplatform only as alpha releases, each paired with a Compose Multiplatform release. The app uses the paired one.
- All Compose code is in `shared`, which also sets the screen of `androidApp`'s activity. `androidApp` doesn't use Compose itself. Otherwise the Android Gradle plugin 9.5 would create a Compose Preview task for its release build, which fails because that build has no unit tests.
- Android Lint run from `androidApp` doesn't check `shared`, so `shared` applies the Android Lint plugin itself.
- Development builds run on a phone with USB debugging turned on. The emulator has no real Bluetooth, so BLE has to be tested on a phone.
- Linux distributions package their own adb. When it isn't the SDK's version, the two keep restarting each other's server and the phone disconnects. The tasks use the SDK's adb, and `just doctor` warns about the other one.
- Nothing checks that the shared code builds for iOS until the iOS targets are added. They're added before any work that is specific to iOS.
- The repository gets its first CI workflow. The device has none, because its checks need a person and a board ([ADR 0003](0003-device-development-toolchain.md)).
