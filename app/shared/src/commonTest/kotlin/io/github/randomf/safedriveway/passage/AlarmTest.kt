package io.github.randomf.safedriveway.passage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class AlarmTest {
    private val clock = TestTimeSource()
    private val left = UnitId("000000000001")
    private val right = UnitId("000000000002")

    /** One gate with a unit on each side, 20 mm zero offsets. */
    private val setup = PassageSetup(
        id = PassageId(1),
        name = "Garage",
        version = 1,
        warningGapMm = 150,
        criticalGapMm = 50,
        gatePositionsMm = listOf(0),
        units = listOf(
            UnitSetup(left, "", 0, Side.Left, 20, signals = true),
            UnitSetup(right, "", 0, Side.Right, 20, signals = true)
        )
    )

    private fun live(mm: Int?, status: ReadingStatus = ReadingStatus.Valid) = UnitLive(
        Connection.Connected,
        reading = Reading(
            mm ?: 0,
            if (mm ==
                null
            ) {
                ReadingStatus.NoTarget
            } else {
                status
            },
            clock.markNow()
        )
    )

    private fun alarm(
        left: UnitLive,
        right: UnitLive,
        carSeenAt: kotlin.time.ComparableTimeMark? = null
    ) = alarmOf(setup, mapOf(this.left to left, this.right to right), clock.markNow(), carSeenAt)

    @Test
    fun noAlarmWithRoomOnBothSides() {
        assertNull(alarm(live(400), live(300)))
        assertNull(alarm(live(null), live(null)))
    }

    @Test
    fun beepsFasterAsTheGapCloses() {
        // Gaps are readings minus the 20 mm offset.
        assertEquals(Alarm.Warning(991), alarm(live(169), live(300)))
        assertNull(alarm(live(170), live(300)))
        assertEquals(Alarm.Warning(580), alarm(live(120), live(300)))
        assertEquals(Alarm.Warning(160), alarm(live(300), live(70)))
    }

    @Test
    fun aContinuousToneBelowTheCriticalGap() {
        assertEquals(Alarm.Critical, alarm(live(69), live(300)))
        assertEquals(Alarm.Critical, alarm(live(10, ReadingStatus.TooClose), live(300)))
    }

    @Test
    fun aStaleReadingSoundsNothing() {
        val old = live(30)
        clock += 600.milliseconds
        assertNull(alarm(old, live(null)))
    }

    @Test
    fun aUnitThatGoesSilentWhileTheCarIsThereSounds() {
        val seen = clock.markNow()
        val old = live(300)
        clock += 1.seconds
        assertEquals(Alarm.Silent, alarm(old, live(null), seen))
        assertEquals(
            Alarm.Silent,
            alarm(UnitLive(Connection.Reconnecting), live(null), seen)
        )
        clock += Alarm.CAR_HOLD
        assertNull(alarm(old, live(null), seen))
    }

    @Test
    fun aGapBeatsASilentUnit() {
        val seen = clock.markNow()
        assertEquals(Alarm.Warning(991), alarm(UnitLive(Connection.Reconnecting), live(169), seen))
    }

    @Test
    fun theCarIsSeenWhileAUnitHasAGap() {
        val units = mapOf(left to live(400), right to live(null))
        assertTrue(carSeen(setup, units, clock.markNow()))
        assertFalse(carSeen(setup, mapOf(left to live(null)), clock.markNow()))
    }
}
