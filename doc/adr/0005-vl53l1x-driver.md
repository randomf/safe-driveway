# 5. VL53L1X driver written from ST's Ultra Lite Driver

- **Status:** Accepted
- **Date:** 2026-11-05

## Context

The distance sensor is a VL53L1X ([ADR 0004](0004-vl53l1x-distance-sensor.md)). MicroPython has no driver for it, and both the bring-up test and the firmware need one. The VL53L1X can't be driven from its datasheet alone: ST publishes how to configure it only as driver code, the Ultra Lite Driver (ULD), under a BSD-3-Clause licence. This project is licensed under the AGPL-3.0, which can include BSD-3-Clause code as long as its notice is kept.

The unit needs the short distance mode, a set timing budget, continuous measurement, and a status that says whether each distance is valid.

## Decision

The driver is [device/board/lib/vl53l1x.py](../../device/board/lib/vl53l1x.py), written for this project from ST's ULD as published in ST's stm32duino port. It takes ST's configuration table, distance-mode and timing values and range-status mapping, under ST's notice, which the file keeps. It covers initialisation, short and long distance mode, the timing budgets ST defines, continuous measurement, and reading the distance with its status. The bring-up test and the firmware both use it, from `device/board/lib/`, which maps to `/lib` on the board.

## Alternatives considered

- **drakxtwo/vl53l1x_pico (MIT).** Small, but it can't set the distance mode, doesn't wait for a measurement to be ready, and calls `machine.lightsleep`, which pauses the ESP32. Last updated in 2021.
- **SparkFun qwiic_vl53l1x (MIT).** A complete ULD port, but about 50 KB plus SparkFun's own I2C layer, which is heavy to load into the board's RAM for every bring-up run.

## Consequences

- The driver is ours to maintain. Features the ULD has and the driver lacks, such as offset and crosstalk calibration, the region of interest and distance thresholds, get added from the same source when the firmware needs them.
- ST's notice stays in the file, and anything copied from the ULD later goes under it.
- The checks import the driver by name, the same way the firmware will. `just bringup` and `just distance` send it into the board's RAM as a module; `just repl` mounts `device/board/`, and the checks add its `lib/` to the import path.
