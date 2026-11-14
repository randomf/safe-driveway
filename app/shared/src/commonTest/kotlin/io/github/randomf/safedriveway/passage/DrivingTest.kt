package io.github.randomf.safedriveway.passage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class DrivingTest {
    private val clock = TestTimeSource()

    private val left0 = UnitId("000000000001")
    private val right0 = UnitId("000000000002")
    private val left1 = UnitId("000000000003")
    private val right1 = UnitId("000000000004")

    /** Two gates 2.5 m apart, with a unit on each side, and no zero offset. */
    private val setup = PassageSetup(
        id = PassageId(1),
        name = "Garage",
        version = 1,
        warningGapMm = 150,
        criticalGapMm = 50,
        gatePositionsMm = listOf(0, 2500),
        units = listOf(
            UnitSetup(left0, "", 0, Side.Left, 0, signals = true),
            UnitSetup(right0, "", 0, Side.Right, 0, signals = true),
            UnitSetup(left1, "", 1, Side.Left, 0, signals = true),
            UnitSetup(right1, "", 1, Side.Right, 0, signals = true)
        )
    )

    private fun reading(mm: Int?) = UnitLive(
        Connection.Connected,
        reading = Reading(
            mm ?: 0,
            if (mm ==
                null
            ) {
                ReadingStatus.NoTarget
            } else {
                ReadingStatus.Valid
            },
            clock.markNow()
        )
    )

    private fun view(
        gaps: Map<UnitId, Int?>,
        direction: Direction = Direction.Forwards,
        entry: End = End.Usual
    ) = drivingView(
        setup,
        setup.units.associate { it.id to reading(gaps[it.id]) },
        direction,
        entry,
        clock.markNow()
    )

    @Test
    fun drivingInForwardsByTheUsualEndShowsGate1AtTheBottomAndLeftOnTheLeft() {
        val view = view(emptyMap())
        assertEquals(listOf(0, 1), view.gates.map { it.index })
        assertEquals(left0, view.gates.first().left?.unit?.id)
    }

    @Test
    fun reversingInByTheUsualEndTurnsThePassageRound() {
        // The driver faces end 0 while reversing in from it: gate 1 is at the top, and the
        // passage's left wall is on the driver's right.
        val view = view(emptyMap(), Direction.Reversing)
        assertEquals(listOf(1, 0), view.gates.map { it.index })
        assertEquals(right0, view.gates.last().left?.unit?.id)
    }

    @Test
    fun drivingInForwardsByTheFarEndTurnsThePassageRoundToo() {
        val view = view(emptyMap(), entry = End.Far)
        assertEquals(listOf(1, 0), view.gates.map { it.index })
        assertEquals(right1, view.gates.first().left?.unit?.id)
    }

    @Test
    fun noArrowUntilAGateSeesTheCar() {
        val view = view(emptyMap())
        assertNull(view.steer)
        assertNull(view.car)
        assertEquals(4, view.readyUnits)
    }

    @Test
    fun oneGateSteersTowardsTheBiggerGap() {
        assertEquals(Steer.Right, view(mapOf(left0 to 100, right0 to 300)).steer)
        assertEquals(Steer.Left, view(mapOf(left0 to 300, right0 to 100)).steer)
        assertEquals(Steer.Straight, view(mapOf(left0 to 200, right0 to 210)).steer)
    }

    @Test
    fun twoGatesProjectTheHeadingToTheNextGate() {
        // Even at the front gate, but drifting left: the left gap shrank from 260 to 200.
        val setup3 = setup.copy(gatePositionsMm = listOf(0, 2500, 5000))
        val view = drivingView(
            setup3,
            mapOf(
                left0 to reading(260),
                right0 to reading(140),
                left1 to reading(200),
                right1 to reading(200)
            ),
            Direction.Forwards,
            End.Usual,
            clock.markNow()
        )
        assertEquals(Steer.Right, view.steer)
    }

    @Test
    fun withASideMissingBehindNeitherSideIsProjected() {
        // Gate 1 has no right unit, so the right gap can't be projected: the left isn't either,
        // and the arrow compares the gaps at gate 2 as they are.
        val setup3 = setup.copy(
            gatePositionsMm = listOf(0, 2500, 5000),
            units = setup.units.filter { it.id != right0 }
        )
        val view = drivingView(
            setup3,
            mapOf(left0 to reading(400), left1 to reading(200), right1 to reading(200)),
            Direction.Forwards,
            End.Usual,
            clock.markNow()
        )
        assertEquals(Steer.Straight, view.steer)
    }

    @Test
    fun reversingSteersByTheDriversSides() {
        // Reversing in by end 0, the driver's left is the passage's right, where the gap is
        // smaller: turning the wheel right moves the rear away from it.
        val view = view(mapOf(left0 to 300, right0 to 100), Direction.Reversing)
        assertEquals(Steer.Right, view.steer)
    }

    @Test
    fun aOneSidedGateSteersAwayFromItsWallOnlyBelowTheWarningGap() {
        val oneSided = setup.copy(gatePositionsMm = listOf(0), units = setup.units.take(1))
        fun steer(mm: Int) = drivingView(
            oneSided,
            mapOf(left0 to reading(mm)),
            Direction.Forwards,
            End.Usual,
            clock.markNow()
        ).steer
        assertEquals(Steer.Straight, steer(200))
        assertEquals(Steer.Right, steer(120))
    }

    @Test
    fun theSmallestGapDecidesTheBorder() {
        val view = view(mapOf(left0 to 40, right0 to 400))
        assertEquals(40, view.smallest?.gap?.mm)
        assertTrue(view.critical)
    }

    @Test
    fun theCarIsDrawnNearerTheSmallerGap() {
        val car = view(mapOf(left0 to 100, right0 to 300)).car!!
        assertEquals(0.25f, car.xAtRear)
    }

    @Test
    fun aDisconnectedUnitShowsNoGap() {
        val units = setup.units.associate { it.id to reading(200) } +
            (left0 to UnitLive(Connection.Disconnected))
        val view = drivingView(setup, units, Direction.Forwards, End.Usual, clock.markNow())
        assertEquals(Gap.NoData, view.gates.first().left?.gap)
        assertEquals(3, view.readyUnits)
    }

    @Test
    fun theFirstGateToSeeTheCarTellsTheEnd() {
        fun seeing(vararg gates: Int) =
            setup.units.associate { it.id to reading(if (it.gate in gates) 200 else null) }
        val farGateFirst = Entry().next(setup, seeing(1), clock.markNow())
        assertEquals(End.Far, farGateFirst.end)
        // The car reaches gate 1 too: still the far end.
        val bothGates = farGateFirst.next(setup, seeing(0, 1), clock.markNow())
        assertEquals(End.Far, bothGates.end)
        // A moment with no car keeps it; once the passage has been empty for a while, the next
        // car starts over.
        val gone = bothGates.next(setup, seeing(), clock.markNow())
        assertEquals(End.Far, gone.end)
        clock += 4.seconds
        assertEquals(Entry(), gone.next(setup, seeing(), clock.markNow()))
    }

    @Test
    fun oneGateCannotTellTheEnd() {
        val oneGate = setup.copy(gatePositionsMm = listOf(0), units = setup.units.take(2))
        val units = oneGate.units.associate { it.id to reading(200) }
        assertEquals(End.Usual, Entry().next(oneGate, units, clock.markNow()).end)
    }
}
