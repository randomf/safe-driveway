package io.github.randomf.safedriveway.demo

import io.github.randomf.safedriveway.passage.Direction
import io.github.randomf.safedriveway.passage.End
import io.github.randomf.safedriveway.passage.PassageSetup
import io.github.randomf.safedriveway.passage.ReadingStatus
import io.github.randomf.safedriveway.passage.SENSOR_MINIMUM_MM
import io.github.randomf.safedriveway.passage.Side
import io.github.randomf.safedriveway.passage.UnitId
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A simulated car driving through a passage, for the demo. It works in the frame of travel: u
 * along the passage in the direction the car moves, from the end it came in by, and v across it,
 * positive to the right of someone facing the way the car moves.
 */
internal class DemoPass(
    private val setup: PassageSetup,
    private val halfWidthsMm: List<Int>,
    direction: Direction,
    private val entry: End,
    private val course: DemoCourse
) {
    private val span = setup.gatePositionsMm.last() - setup.gatePositionsMm.first()
    private val gateU = setup.gatePositionsMm.map { if (entry == End.Usual) it else span - it }

    // The car is level with every gate when its front is between the last gate and the first
    // gate plus the car's length: that's where it drifts most.
    private val middle = (gateU.max() + gateU.min() + CAR_LENGTH_MM) / 2.0
    private val free = halfWidthsMm.min() - CAR_HALF_WIDTH_MM

    // Reversing, the rear leads and the car goes in more slowly.
    private val speedMmPerS = if (direction == Direction.Forwards) 700.0 else 500.0
    private val startU = -1200.0
    private val endU = gateU.max() + CAR_LENGTH_MM + 600.0

    val duration: Duration = ((endU - startU) / speedMmPerS * 1000).toLong().milliseconds

    /** The distance each unit measures at [progress], from 0 to 1, or null with no car in front. */
    fun distancesAt(progress: Double): Map<UnitId, Pair<Int, ReadingStatus>> {
        val lead = startU + (endU - startU) * progress
        val centre = lead - CAR_LENGTH_MM / 2
        // The car drifts across as it goes, turned a little the way it drifts.
        val v = drift(lead)
        val slope = (drift(lead + 1) - drift(lead - 1)) / 2 * TURN
        return setup.units.associate { unit ->
            val u = gateU[unit.gate].toDouble()
            val reading = if (u in (lead - CAR_LENGTH_MM)..lead) {
                val across = v + (u - centre) * slope
                // The passage's left wall is on the car's left when it came in by end 0.
                val onNegativeSide = (unit.side == Side.Left) == (entry == End.Usual)
                val roomPerSide = halfWidthsMm[unit.gate] - CAR_HALF_WIDTH_MM
                val gap = if (onNegativeSide) roomPerSide + across else roomPerSide - across
                val distance = (gap + unit.zeroOffsetMm).toInt()
                if (distance < SENSOR_MINIMUM_MM) {
                    distance.coerceAtLeast(0) to ReadingStatus.TooClose
                } else {
                    distance to ReadingStatus.Valid
                }
            } else {
                0 to ReadingStatus.NoTarget
            }
            unit.id to reading
        }
    }

    /** The car's offset across the passage, when its front is at [u] along it. */
    private fun drift(u: Double): Double {
        val bump = exp(-((u - middle) / 2500).let { it * it })
        return when (course) {
            DemoCourse.Clear -> 25 * sin(u / 1300) + 10 * cos(u / 700)
            DemoCourse.Tight -> -(free - 100) * bump
            DemoCourse.Critical -> -(free - 45) * bump
        }
    }

    companion object {
        const val CAR_LENGTH_MM = 4500
        const val CAR_HALF_WIDTH_MM = 900

        /** How much the car turns with its drift: 1 would be all turning, 0 all sliding. */
        private const val TURN = 0.3
    }
}
