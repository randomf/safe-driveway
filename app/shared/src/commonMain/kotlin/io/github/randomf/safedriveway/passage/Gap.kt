package io.github.randomf.safedriveway.passage

import kotlin.math.roundToInt
import kotlin.time.ComparableTimeMark

/** How close a gap is: green, orange, red, or grey for no data. */
enum class GapLevel { Clear, Warning, Critical, NoData }

/**
 * What the app shows for one unit. [mm] is null when there is no gap to show. A *too close*
 * reading has no accurate distance: [mm] is then the most the gap can be.
 */
data class Gap(val mm: Int?, val tooClose: Boolean, val level: GapLevel) {
    companion object {
        val NoData = Gap(null, tooClose = false, level = GapLevel.NoData)
    }
}

/** Below this distance the VL53L1X reports *too close* (ADR 0004). */
const val SENSOR_MINIMUM_MM = 40

fun gapOf(
    reading: Reading?,
    zeroOffsetMm: Int,
    warningGapMm: Int,
    criticalGapMm: Int,
    now: ComparableTimeMark
): Gap {
    if (reading == null || reading.isStale(now)) return Gap.NoData
    return when (reading.status) {
        ReadingStatus.Valid -> {
            val mm = reading.distanceMm - zeroOffsetMm
            val level = when {
                mm < criticalGapMm -> GapLevel.Critical
                mm < warningGapMm -> GapLevel.Warning
                else -> GapLevel.Clear
            }
            Gap(mm, tooClose = false, level = level)
        }

        ReadingStatus.TooClose ->
            Gap(SENSOR_MINIMUM_MM - zeroOffsetMm, tooClose = true, level = GapLevel.Critical)

        else -> Gap.NoData
    }
}

/**
 * Gaps are shown in 0.5 cm steps: the number of half centimetres, rounded down, so that the app
 * never shows more room than there is. Thresholds are in the same steps, so a gap below one also
 * reads below it.
 */
fun halfCentimetres(mm: Int): Int = mm.floorDiv(5)

/** [mm] in cm with one decimal, in 0.5 cm steps, such as "12.5" or "-1.0". */
fun formatHalfCentimetres(mm: Int, decimalSeparator: String): String {
    val halves = halfCentimetres(mm)
    val sign = if (halves < 0) "-" else ""
    val abs = kotlin.math.abs(halves)
    return "$sign${abs / 2}$decimalSeparator${if (abs % 2 == 0) "0" else "5"}"
}

/** [mm] in cm with one decimal, such as "6.4", for zero offsets and live readings. */
fun formatMillimetresAsCentimetres(mm: Int, decimalSeparator: String): String {
    val sign = if (mm < 0) "-" else ""
    val abs = kotlin.math.abs(mm)
    return "$sign${abs / 10}$decimalSeparator${abs % 10}"
}

/** Reads "12.5" or "12,5" as millimetres, or null. */
fun parseCentimetres(text: String): Int? {
    val number = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
    return (number * 10).roundToInt()
}
