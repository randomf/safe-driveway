# 4. VL53L1X distance sensor

- **Status:** Accepted
- **Date:** 2026-11-05
- **Supersedes:** the distance sensor in [ADR 0001](0001-prototype-hardware.md)

## Context

[ADR 0001](0001-prototype-hardware.md) chose a VL53L0X laser time-of-flight sensor. The bring-up test of the first unit (`just bringup sensor`, see [ADR 0003](0003-device-development-toolchain.md)) found that its module carries a VL53L1X instead. The chip at I2C address `0x29` reads `0xEACC` from the VL53L1X's 16-bit model ID register `0x010F`, where a VL53L0X reads `0xEE` from its 8-bit register `0xC0`.

Both chips come on breakout modules with the same pins (VIN, GND, SCL, SDA, XSHUT and GPIO1) and answer at the same address. The unit's wiring is therefore right as built, and the sensor's I2C bus and XSHUT passed their tests.

The unit measures the gap between the passage wall and the side of the car, typically 5–30 cm, several times per second, in a garage where light from outside may fall on the sensor.

## Decision

The distance sensor is a **VL53L1X** laser time-of-flight sensor on a breakout module with its own regulator and I2C pull-ups, powered from 3.3 V. It replaces the VL53L0X of ADR 0001. Everything else in ADR 0001 stands, including the wiring and the pin assignments.

## Alternatives considered

- **Fitting a VL53L0X, as ADR 0001 planned.** It would also cover the gap to the car, but it means ordering and fitting a new module on a unit whose wiring has already passed its tests. The VL53L1X also holds its range better in ambient light.

## Consequences

All figures are from ST's VL53L1X datasheet, DS12385 Rev 8.

- **Driver.** The VL53L1X uses 16-bit register addresses and a different driver from the VL53L0X. MicroPython includes neither, so the firmware needs a VL53L1X driver. [ADR 0005](0005-vl53l1x-driver.md) records how it was written.
- **Range.** In short distance mode the VL53L1X reaches 136 cm in the dark and 135 cm under strong ambient light. Long distance mode reaches 360 cm in the dark but only 73 cm under strong ambient light. Short mode covers the gap to the car with margin, whatever the light. Below 4 cm, the minimum ST guarantees, the sensor detects a target but its distance isn't accurate.
- **Rate.** Up to 50 Hz, with a 20 ms timing budget in short distance mode.
- **Field of view.** 27°, which a programmable region of interest can narrow to 15°. The sensor aperture must clear 27° instead of the VL53L0X's 25°; the 8 mm hole in the lid stays.
- **Logic levels.** When a module runs the chip in 2.8 V mode, the high level on XSHUT, SDA, SCL and GPIO1 must equal 2.8 V. The firmware keeps driving XSHUT open-drain and never drives it high.
- **Telling the chips apart.** Modules sold as VL53L0X and VL53L1X look alike. `just bringup sensor` expects the VL53L1X and names the chip it finds, so every new module gets checked.
- **Sensor window.** Still none. Like the VL53L0X, the VL53L1X needs crosstalk calibration behind a cover window, so adding one needs a new decision.
