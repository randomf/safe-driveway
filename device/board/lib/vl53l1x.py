"""Driver for the ST VL53L1X laser time-of-flight distance sensor, in MicroPython.

Written from ST's VL53L1X Ultra Lite Driver (ULD), as published in ST's
stm32duino port, https://github.com/stm32duino/VL53L1X. The configuration
table and the distance-mode and timing values are ST's, under the notice at
the end of this file.

    tof = VL53L1X(I2C(0, scl=Pin(18), sda=Pin(19), freq=400_000))
    tof.configure(short=True, timing_budget_ms=50)
    tof.start()
    distance_mm, status = tof.read()  # a real target when status is in VALID
    tof.stop()
"""

import time

from micropython import const

MODEL_ID = const(0xEACC)

_VHV_CONFIG__TIMEOUT_MACROP_LOOP_BOUND = const(0x0008)
_GPIO_HV_MUX__CTRL = const(0x0030)
_GPIO__TIO_HV_STATUS = const(0x0031)
_PHASECAL_CONFIG__TIMEOUT_MACROP = const(0x004B)
_RANGE_CONFIG__TIMEOUT_MACROP_A_HI = const(0x005E)
_RANGE_CONFIG__VCSEL_PERIOD_A = const(0x0060)
_RANGE_CONFIG__TIMEOUT_MACROP_B_HI = const(0x0061)
_RANGE_CONFIG__VCSEL_PERIOD_B = const(0x0063)
_RANGE_CONFIG__VALID_PHASE_HIGH = const(0x0069)
_SYSTEM__INTERMEASUREMENT_PERIOD = const(0x006C)
_SD_CONFIG__WOI_SD0 = const(0x0078)
_SD_CONFIG__INITIAL_PHASE_SD0 = const(0x007A)
_SYSTEM__INTERRUPT_CLEAR = const(0x0086)
_SYSTEM__MODE_START = const(0x0087)
_RESULT__RANGE_STATUS = const(0x0089)
_RESULT__FINAL_CROSSTALK_CORRECTED_RANGE_MM_SD0 = const(0x0096)
_RESULT__OSC_CALIBRATE_VAL = const(0x00DE)
_FIRMWARE__SYSTEM_STATUS = const(0x00E5)
_IDENTIFICATION__MODEL_ID = const(0x010F)

# ULD VL51L1X_DEFAULT_CONFIGURATION: registers 0x2D to 0x87, ranging stopped.
_DEFAULT_CONFIGURATION = bytes(
    [
        0x00, 0x00, 0x00, 0x01, 0x02, 0x00, 0x02, 0x08,  # 0x2D
        0x00, 0x08, 0x10, 0x01, 0x01, 0x00, 0x00, 0x00,  # 0x35
        0x00, 0xFF, 0x00, 0x0F, 0x00, 0x00, 0x00, 0x00,  # 0x3D
        0x00, 0x20, 0x0B, 0x00, 0x00, 0x02, 0x0A, 0x21,  # 0x45
        0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x00, 0xC8,  # 0x4D
        0x00, 0x00, 0x38, 0xFF, 0x01, 0x00, 0x08, 0x00,  # 0x55
        0x00, 0x01, 0xCC, 0x0F, 0x01, 0xF1, 0x0D, 0x01,  # 0x5D
        0x68, 0x00, 0x80, 0x08, 0xB8, 0x00, 0x00, 0x00,  # 0x65
        0x00, 0x0F, 0x89, 0x00, 0x00, 0x00, 0x00, 0x00,  # 0x6D
        0x00, 0x00, 0x01, 0x0F, 0x0D, 0x0E, 0x0E, 0x00,  # 0x75
        0x00, 0x02, 0xC7, 0xFF, 0x9B, 0x00, 0x00, 0x00,  # 0x7D
        0x01, 0x00, 0x00,  # 0x85
    ]
)  # fmt: skip

# Distance mode registers: (phase cal timeout, VCSEL period A, VCSEL period B,
# valid phase high, WOI SD0, initial phase SD0).
_SHORT_MODE = (0x14, 0x07, 0x05, 0x38, 0x0705, 0x0606)
_LONG_MODE = (0x0A, 0x0F, 0x0D, 0xB8, 0x0F0D, 0x0E0E)

# Timing budget in ms -> (timeout macro period A, timeout macro period B).
_SHORT_TIMING = {
    15: (0x001D, 0x0027),
    20: (0x0051, 0x006E),
    33: (0x00D6, 0x006E),
    50: (0x01AE, 0x01E8),
    100: (0x02E1, 0x0388),
    200: (0x03E1, 0x0496),
    500: (0x0591, 0x05C1),
}
_LONG_TIMING = {
    20: (0x001E, 0x0022),
    33: (0x0060, 0x006E),
    50: (0x00AD, 0x00C6),
    100: (0x01CC, 0x01EA),
    200: (0x02D9, 0x02F8),
    500: (0x048F, 0x04A4),
}

# The sensor's raw range status -> the ULD's status number. 0 means valid.
_RANGE_STATUS = {
    9: 0,
    6: 1,
    4: 2,
    8: 3,
    5: 4,
    3: 5,
    19: 6,
    7: 7,
    12: 9,
    18: 10,
    22: 11,
    23: 12,
    13: 13,
}

# The ULD's status numbers, named after ST's VL53L1_RANGESTATUS_* constants.
STATUS_TEXT = {
    0: "valid",
    1: "sigma failure",
    2: "signal failure",
    3: "valid, below minimum range",
    4: "out of bounds",
    5: "hardware failure",
    6: "valid, no wraparound check",
    7: "wraparound",
    9: "crosstalk signal failure",
    10: "first measurement",
    11: "valid, merged pulse",
    12: "target present, lack of signal",
    13: "below minimum range",
}

# Statuses whose distance belongs to a real target.
VALID = (0, 3, 6, 11)

# Statuses that mean a target closer than the 4 cm the sensor guarantees.
TOO_CLOSE = (3, 13)


def status_text(status):
    return STATUS_TEXT.get(status, f"status {status}")


class VL53L1X:
    def __init__(self, i2c, address=0x29):
        self.i2c = i2c
        self.address = address
        self._wait_for_boot()
        model_id = self._read16(_IDENTIFICATION__MODEL_ID)
        if model_id != MODEL_ID:
            raise OSError(f"not a VL53L1X: model ID 0x{model_id:04x}")
        self._init()

    def _write8(self, reg, value):
        self.i2c.writeto_mem(self.address, reg, bytes([value]), addrsize=16)

    def _write16(self, reg, value):
        self.i2c.writeto_mem(self.address, reg, value.to_bytes(2, "big"), addrsize=16)

    def _write32(self, reg, value):
        self.i2c.writeto_mem(self.address, reg, value.to_bytes(4, "big"), addrsize=16)

    def _read8(self, reg):
        return self.i2c.readfrom_mem(self.address, reg, 1, addrsize=16)[0]

    def _read16(self, reg):
        return int.from_bytes(self.i2c.readfrom_mem(self.address, reg, 2, addrsize=16), "big")

    def _wait_for_boot(self, timeout_ms=100):
        start = time.ticks_ms()
        while not self._read8(_FIRMWARE__SYSTEM_STATUS) & 0x01:
            if time.ticks_diff(time.ticks_ms(), start) > timeout_ms:
                raise OSError("VL53L1X didn't boot")
            time.sleep_ms(1)

    def _init(self):
        # ULD VL53L1X_SensorInit: load the configuration, then range once so
        # the sensor calibrates its VHV, and start later ones from that value.
        self.i2c.writeto_mem(self.address, 0x2D, _DEFAULT_CONFIGURATION, addrsize=16)
        self.start()
        self._wait_for_data()
        self._clear_interrupt()
        self.stop()
        self._write8(_VHV_CONFIG__TIMEOUT_MACROP_LOOP_BOUND, 0x09)
        self._write8(0x000B, 0x00)

    def configure(self, short=True, timing_budget_ms=50):
        """Set the distance mode and the time each measurement takes.

        Short mode reaches about 1.3 m and holds that in ambient light; long
        mode reaches up to 3.6 m in the dark. A longer timing budget gives
        steadier readings. Measurements follow each other without a pause.
        """
        timing = _SHORT_TIMING if short else _LONG_TIMING
        if timing_budget_ms not in timing:
            raise ValueError(f"timing budget must be one of {sorted(timing)} ms")
        phasecal, period_a, period_b, phase_high, woi, initial_phase = (
            _SHORT_MODE if short else _LONG_MODE
        )
        self._write8(_PHASECAL_CONFIG__TIMEOUT_MACROP, phasecal)
        self._write8(_RANGE_CONFIG__VCSEL_PERIOD_A, period_a)
        self._write8(_RANGE_CONFIG__VCSEL_PERIOD_B, period_b)
        self._write8(_RANGE_CONFIG__VALID_PHASE_HIGH, phase_high)
        self._write16(_SD_CONFIG__WOI_SD0, woi)
        self._write16(_SD_CONFIG__INITIAL_PHASE_SD0, initial_phase)
        timeout_a, timeout_b = timing[timing_budget_ms]
        self._write16(_RANGE_CONFIG__TIMEOUT_MACROP_A_HI, timeout_a)
        self._write16(_RANGE_CONFIG__TIMEOUT_MACROP_B_HI, timeout_b)
        # ULD VL53L1X_SetInterMeasurementInMs, with no gap after each measurement.
        clock_pll = self._read16(_RESULT__OSC_CALIBRATE_VAL) & 0x3FF
        self._write32(_SYSTEM__INTERMEASUREMENT_PERIOD, int(clock_pll * timing_budget_ms * 1.075))

    def start(self):
        self._write8(_SYSTEM__MODE_START, 0x40)

    def stop(self):
        self._write8(_SYSTEM__MODE_START, 0x00)

    def data_ready(self):
        active_high = not self._read8(_GPIO_HV_MUX__CTRL) & 0x10
        return (self._read8(_GPIO__TIO_HV_STATUS) & 0x01) == active_high

    def _wait_for_data(self, timeout_ms=1000):
        start = time.ticks_ms()
        while not self.data_ready():
            if time.ticks_diff(time.ticks_ms(), start) > timeout_ms:
                raise OSError("VL53L1X timed out")
            time.sleep_ms(1)

    def _clear_interrupt(self):
        self._write8(_SYSTEM__INTERRUPT_CLEAR, 0x01)

    def read(self):
        """Wait for the next measurement: (distance in mm, status). See VALID and TOO_CLOSE."""
        self._wait_for_data()
        raw_status = self._read8(_RESULT__RANGE_STATUS) & 0x1F
        distance = self._read16(_RESULT__FINAL_CROSSTALK_CORRECTED_RANGE_MM_SD0)
        self._clear_interrupt()
        return distance, _RANGE_STATUS.get(raw_status, 255)


# The configuration table, the distance-mode and timing values and the range
# status mapping above come from ST's VL53L1X Ultra Lite Driver:
#
# COPYRIGHT(c) 2018 STMicroelectronics
#
# Redistribution and use in source and binary forms, with or without modification,
# are permitted provided that the following conditions are met:
#   1. Redistributions of source code must retain the above copyright notice,
#      this list of conditions and the following disclaimer.
#   2. Redistributions in binary form must reproduce the above copyright notice,
#      this list of conditions and the following disclaimer in the documentation
#      and/or other materials provided with the distribution.
#   3. Neither the name of STMicroelectronics nor the names of its contributors
#      may be used to endorse or promote products derived from this software
#      without specific prior written permission.
#
# THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
# AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
# IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
# DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
# FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
# DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
# SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
# CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
# OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
# OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
