# AGENTS.md

Safe Driveway is a hardware and software solution that prevents car scratches in narrow driveways and garage entrances. Small remote devices installed at the passage report the car's position to a mobile phone inside the car. See [README.md](README.md) for the problem statement and the prototype hardware, and [doc/](doc/) for the architecture and the decisions behind it.

## Repository layout

The repository has three top-level areas, and `.github/` for continuous integration. Put every file in the one it belongs to.

### `device/` — the remote device

Everything needed to build the physical device that is installed at the passage:

- Code that runs on the board, in MicroPython on an ESP32, in `device/board/`. The firmware goes in `device/board/firmware/`, and libraries it shares with the bring-up checks, such as the sensor driver, in `device/board/lib/`. [device/firmware.md](device/firmware.md) describes the firmware
- Tests of the firmware's pure modules, which run on the computer with pytest, in `device/tests/`
- Code that runs on the computer to flash and test the board, in `device/host/`, run as `just` tasks from `device/`. [device/README.md](device/README.md) covers the setup and the tasks
- Wiring diagrams, schematics and pin assignments
- Bill of materials and parts lists
- The 3D-printed enclosure, in `device/enclosure/`: its build123d model, `model.py`, and the print files and pictures that `just enclosure` writes from it. [device/enclosure/README.md](device/enclosure/README.md) covers printing it and fitting the parts
- Assembly and construction instructions
- Calibration and test notes for the device

### `app/` — the mobile app

Everything for the mobile app that runs on the phone in the car. `app/` is the root of its Gradle project:

- Code shared by Android and iOS, in Kotlin Multiplatform with Compose Multiplatform, in `app/shared/`: platform-independent code in `src/commonMain/`, Android code in `src/androidMain/`, and the texts, in English and Czech, in `src/commonMain/composeResources/`
- The Android application around it, with its manifest and icon, in `app/androidApp/`. It has no Compose code: `shared` sets its screen. The iOS app will go in `app/iosApp/`
- Tasks to build, run and check the app, run as `just` tasks from `app/`. [app/README.md](app/README.md) covers the setup and the tasks
- App configuration, dependencies and build files: the Gradle build and its wrapper, `.editorconfig` and `app/gradle/libs.versions.toml`
- App assets (icons, images, sounds, fonts, translations). The master artwork, the app's icon and the README's header, is in `app/artwork/`; [app/artwork/README.md](app/artwork/README.md) says how the Android icon follows it
- App tests, next to the code they test, such as `app/shared/src/commonTest/`
- App documentation, such as [app/features.md](app/features.md) and [app/screens.md](app/screens.md), and the screenshots they show, in `app/screenshots/`

### `doc/` — shared documentation and architecture decisions

Documentation that concerns the project as a whole or both sides of it:

- Architecture overviews and system design
- Architecture decision records (ADRs) in `doc/adr/`, one file per decision, numbered in order (for example `0001-short-title.md`). Do not rewrite an accepted decision; add a new one that supersedes it.
- Specifications shared by the device and the app, such as the communication protocol between them
- Project-wide guides and notes

Documentation that only concerns one side stays next to that side, in `device/` or `app/`.

### `.github/` — continuous integration

GitHub Actions workflows in `.github/workflows/`, one per area that CI checks: `app.yml` for the app and `device.yml` for the device.

## Rules

- Files for building the device go in `device/`. Never put them in `app/` or at the repository root.
- Mobile app code and assets go in `app/`. Never put them in `device/` or at the repository root.
- Shared documentation and architecture decisions go in `doc/`. Anything shared by both sides, such as the communication protocol between device and phone, is documented once there and referenced from `device/` and `app/` rather than copied.
- Record significant design choices (hardware selection, communication method, app platform) as an ADR in `doc/adr/`.
- Ask before creating any other top-level directory.
- Keep the repository root for project-level files only: `README.md`, `AGENTS.md`, `LICENSE`, the VS Code workspace `safe-driveway.code-workspace` and similar. `device/` and `app/` are folders of their own in the workspace.
- Add every device task to `device/justfile` as a `just` task. Set the MicroPython version only in `device/micropython.toml`.
- Add every app task to `app/justfile` as a `just` task. Set the app's library and plugin versions, and the Android versions it supports, only in `app/gradle/libs.versions.toml`. The exceptions are the ones [app/README.md](app/README.md#versions) lists: Gradle, the JDK, and the toolchain resolver plugin, which `app/settings.gradle.kts` has to name before the catalog is available.
- Write code in a functional style wherever the language allows: immutable data, pure functions that return new values, and `map`, `filter` and folds over loops that mutate. Arrange it as a functional core and an imperative shell: side effects such as Bluetooth, storage, hardware, the clock and UI state stay in a thin shell around pure logic, as the app's `passage` package and the firmware's `main.py` show. On the board, a loop that runs many times a second may reuse its buffers to spare MicroPython's garbage collector.
- The firmware's pure modules, every module in `device/board/firmware/` but `main.py`, run in both MicroPython and CPython: use only what both have, and nothing from `machine`, `bluetooth` or `micropython`.
- Code in `app/shared/src/commonMain/` has to build for iOS too: put anything that uses an Android API in `src/androidMain/`. Put every text the app shows in its string resources, in English and Czech.
- Run `just lint` and `just test` in `device/` before finishing a change to device code, and `just lint` in `app/` before finishing a change to app code. After a change to the enclosure's model, run `just enclosure` in `device/` and commit the print files and pictures it writes.
- After a change to how the app talks to the units, its storage or its sounds, replay the affected tests in [app/e2e-tests.md](app/e2e-tests.md) when a phone and a unit are connected, and report which ones you replayed.
- The project is licensed under the license in [LICENSE](LICENSE). Do not modify it.
- Keep documentation up to date. Update the affected docs in the same change as the code, hardware or design they describe, never as a later follow-up. This covers [README.md](README.md), the files in `doc/`, and the documentation in `device/` and `app/`, and includes this file when the repository layout or rules change.
- Retake the screenshots in `app/screenshots/` in the same change when the app's screens visibly change: a layout, colours, texts or what a screenshotted screen shows. Retake every screenshot of an affected screen, in the same state as before, so that the docs never show a screen the app no longer has. [app/README.md](app/README.md#screenshots) says how.
- If a change contradicts an accepted ADR, add a new ADR that supersedes it instead of leaving the old one stale.
- Before finishing a task, check whether any documentation mentions what you changed (hardware list, pin assignments, protocol, setup steps) and fix what no longer matches. Pin assignments and the expected sensor also live in the bring-up checks, `device/board/bringup.py` and `device/host/bringup.py`, and the pin assignments in the firmware, `device/board/firmware/main.py`. The lights and sounds are described in `device/firmware.md` and ADR 0010. The sizes of the parts, and where they sit in the enclosure, also live in the enclosure's model, `device/enclosure/model.py`.
