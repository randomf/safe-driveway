package io.github.randomf.safedriveway.passage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class GapTest {
    private val clock = TestTimeSource()

    private fun gap(distanceMm: Int, status: ReadingStatus = ReadingStatus.Valid) = gapOf(
        Reading(distanceMm, status, clock.markNow()),
        zeroOffsetMm = 60,
        warningGapMm = 150,
        criticalGapMm = 50,
        now = clock.markNow()
    )

    @Test
    fun subtractsTheZeroOffset() {
        assertEquals(Gap(140, tooClose = false, level = GapLevel.Warning), gap(200))
    }

    @Test
    fun aGapAtTheWarningGapIsClear() {
        assertEquals(GapLevel.Clear, gap(210).level)
        assertEquals(GapLevel.Warning, gap(209).level)
    }

    @Test
    fun belowTheCriticalGapIsCritical() {
        assertEquals(GapLevel.Warning, gap(110).level)
        assertEquals(GapLevel.Critical, gap(109).level)
    }

    @Test
    fun aTooCloseReadingIsCriticalAndAtMostTheSensorsMinimum() {
        assertEquals(
            Gap(-20, tooClose = true, level = GapLevel.Critical),
            gap(0, ReadingStatus.TooClose)
        )
    }

    @Test
    fun readingsWithoutADistanceAreNoData() {
        listOf(
            ReadingStatus.NoTarget,
            ReadingStatus.Unreliable,
            ReadingStatus.SensorFault
        ).forEach {
            assertEquals(Gap.NoData, gap(300, it))
        }
    }

    @Test
    fun aReadingOlderThanHalfASecondIsNoData() {
        val reading = Reading(300, ReadingStatus.Valid, clock.markNow())
        clock += 501.milliseconds
        assertEquals(Gap.NoData, gapOf(reading, 60, 150, 50, clock.markNow()))
    }

    @Test
    fun showsGapsInHalfCentimetres() {
        assertEquals("12.5", formatHalfCentimetres(127, "."))
        assertEquals("12,0", formatHalfCentimetres(124, ","))
        assertEquals("-0.5", formatHalfCentimetres(-2, "."))
        assertEquals("-1.5", formatHalfCentimetres(-15, "."))
    }

    @Test
    fun aGapJustBelowAThresholdReadsBelowIt() {
        // Rounded to the nearest step, 148 mm would read 15.0, the warning gap itself.
        assertEquals("14.5", formatHalfCentimetres(148, "."))
        assertEquals("15.0", formatHalfCentimetres(150, "."))
    }

    @Test
    fun readsCentimetresWithEitherSeparator() {
        assertEquals(65, parseCentimetres("6.5"))
        assertEquals(65, parseCentimetres(" 6,5 "))
        assertEquals(null, parseCentimetres("six"))
        assertTrue(formatMillimetresAsCentimetres(65, ",") == "6,5")
    }
}
