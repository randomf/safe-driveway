# AGENTS.md

Safe Driveway is a hardware and software solution that prevents car scratches in narrow driveways and garage entrances. Small remote devices installed at the passage report the car's position to a mobile phone inside the car. See [README.md](README.md) for the problem statement and the prototype hardware, and [doc/](doc/) for the architecture and the decisions behind it.

## Repository layout

The repository has three top-level areas. Put every file in the one it belongs to.

### `device/` — the remote device

Everything needed to build the physical device that is installed at the passage:

- Firmware and device-side code (the prototype targets an ESP32 with MicroPython)
- Wiring diagrams, schematics and pin assignments
- Bill of materials and parts lists
- Enclosure designs (CAD files, 3D-print models, drawings)
- Assembly and construction instructions
- Calibration and test notes for the device

### `app/` — the mobile app

Everything for the mobile app that runs on the phone in the car:

- App source code
- App assets (icons, images, sounds, fonts, translations)
- App configuration, dependencies and build files
- App-specific tests and documentation

### `doc/` — shared documentation and architecture decisions

Documentation that concerns the project as a whole or both sides of it:

- Architecture overviews and system design
- Architecture decision records (ADRs) in `doc/adr/`, one file per decision, numbered in order (for example `0001-short-title.md`). Do not rewrite an accepted decision; add a new one that supersedes it.
- Specifications shared by the device and the app, such as the communication protocol between them
- Project-wide guides and notes

Documentation that only concerns one side stays next to that side, in `device/` or `app/`.

## Rules

- Files for building the device go in `device/`. Never put them in `app/` or at the repository root.
- Mobile app code and assets go in `app/`. Never put them in `device/` or at the repository root.
- Shared documentation and architecture decisions go in `doc/`. Anything shared by both sides, such as the communication protocol between device and phone, is documented once there and referenced from `device/` and `app/` rather than copied.
- Record significant design choices (hardware selection, communication method, app platform) as an ADR in `doc/adr/`.
- Ask before creating any other top-level directory.
- Keep the repository root for project-level files only: `README.md`, `AGENTS.md`, `LICENSE` and similar.
- The project is licensed under the license in [LICENSE](LICENSE). Do not modify it.
- Keep documentation up to date. Update the affected docs in the same change as the code, hardware or design they describe, never as a later follow-up. This covers [README.md](README.md), the files in `doc/`, and the documentation in `device/` and `app/`, and includes this file when the repository layout or rules change.
- If a change contradicts an accepted ADR, add a new ADR that supersedes it instead of leaving the old one stale.
- Before finishing a task, check whether any documentation mentions what you changed (hardware list, pin assignments, protocol, setup steps) and fix what no longer matches.
