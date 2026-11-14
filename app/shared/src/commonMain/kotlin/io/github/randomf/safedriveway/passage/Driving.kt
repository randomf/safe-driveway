package io.github.randomf.safedriveway.passage

import kotlin.math.abs
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.seconds

/** Whether the driver is driving forwards or reversing: the switch on the main screen. */
enum class Direction { Forwards, Reversing }

/** The end the car came in by: end 0, where the driver usually drives in, or the far end. */
enum class End { Usual, Far }

/** Which way to turn the steering wheel. */
enum class Steer { Left, Straight, Right }

/** A unit as the main screen shows it. */
data class UnitOnScreen(val unit: UnitSetup, val live: UnitLive?, val gap: Gap)

/**
 * A gate as the main screen shows it. [y] goes from 0 at the bottom of the passage view to 1 at
 * the top. [left] and [right] are the driver's.
 */
data class GateOnScreen(
    val index: Int,
    val positionMm: Int,
    val y: Float,
    val left: UnitOnScreen?,
    val right: UnitOnScreen?
) {
    val units: List<UnitOnScreen> get() = listOfNotNull(left, right)

    /** The gate sees the car while one of its units has a gap. */
    val seesCar: Boolean get() = units.any { it.gap.mm != null }
}

/**
 * The car on the main screen. Heights are like [GateOnScreen.y]. [xAtRear] and [xAtFront] say
 * where the car is across the passage: 0 against the driver's left wall, 1 against the right.
 */
data class CarOnScreen(val rearY: Float, val frontY: Float, val xAtRear: Float, val xAtFront: Float)

/** Everything the main screen shows. */
data class DrivingView(
    /** Bottom to top. */
    val gates: List<GateOnScreen>,
    val car: CarOnScreen?,
    /** Null while no gate sees the car. */
    val steer: Steer?,
    val smallest: UnitOnScreen?,
    val readyUnits: Int,
    val totalUnits: Int
) {
    val critical: Boolean get() = smallest?.gap?.level == GapLevel.Critical
}

/**
 * Turns the setup and the latest readings into what the main screen shows. The screen's left is
 * the driver's left, and the car's front points up: it moves up when driving forwards and down
 * when reversing (app/features.md).
 */
fun drivingView(
    setup: PassageSetup,
    units: Map<UnitId, UnitLive>,
    direction: Direction,
    entry: End,
    now: ComparableTimeMark
): DrivingView {
    // The driver faces along the passage, from end 0 towards the far end, when they came in by
    // end 0 forwards, or by the far end reversing.
    val facingAlong = (entry == End.Usual) == (direction == Direction.Forwards)
    val first = setup.gatePositionsMm.first()
    val span = setup.gatePositionsMm.last() - first

    fun onScreen(gate: Int, side: Side): UnitOnScreen? = setup.unitAt(gate, side)?.let { unit ->
        val live = units[unit.id]
        val gap = if (live?.connection == Connection.Connected) {
            gapOf(live.reading, unit.zeroOffsetMm, setup.warningGapMm, setup.criticalGapMm, now)
        } else {
            Gap.NoData
        }
        UnitOnScreen(unit, live, gap)
    }

    val gates = setup.gatePositionsMm.mapIndexed { index, position ->
        val along = if (span == 0) 0.5f else MARGIN + (1 - 2 * MARGIN) * (position - first) / span
        val left = onScreen(index, Side.Left)
        val right = onScreen(index, Side.Right)
        GateOnScreen(
            index = index,
            positionMm = position,
            y = if (facingAlong) along else 1 - along,
            left = if (facingAlong) left else right,
            right = if (facingAlong) right else left
        )
    }.sortedBy { it.y }

    val all = gates.flatMap { it.units }
    val ready = all.count { (_, live, _) ->
        live?.connection == Connection.Connected &&
            !live.sensorFault &&
            live.reading?.let { !it.isStale(now) && it.status != ReadingStatus.SensorFault } == true
    }
    return DrivingView(
        gates = gates,
        car = carOnScreen(gates, setup.warningGapMm),
        steer = steer(gates, direction, setup.warningGapMm),
        smallest = all.filter { it.gap.mm != null }.minByOrNull { it.gap.mm!! },
        readyUnits = ready,
        totalUnits = setup.units.size
    )
}

/** The room kept above the top gate and below the bottom one, as part of the view's height. */
private const val MARGIN = 0.15f

/** How much a predicted gap may be smaller than the other before the arrow says to steer. */
const val STEER_MARGIN_MM = 20

private fun carOnScreen(gates: List<GateOnScreen>, warningGapMm: Int): CarOnScreen? {
    val seeing = gates.filter { it.seesCar }
    if (seeing.isEmpty()) return null
    val bottom = seeing.first()
    val top = seeing.last()
    // The car reaches halfway into the segments next to the gates that see it: the app knows
    // which segment the front is in, but no more.
    val below = gates.lastOrNull { it.y < bottom.y }
    val above = gates.firstOrNull { it.y > top.y }
    val rearY = below?.let { (it.y + bottom.y) / 2 } ?: (bottom.y - MARGIN)
    val frontY = above?.let { (it.y + top.y) / 2 } ?: (top.y + MARGIN)
    val xBottom = bottom.carX(warningGapMm)
    val xTop = top.carX(warningGapMm)
    val slope = if (top === bottom) 0f else (xTop - xBottom) / (top.y - bottom.y)
    fun xAt(y: Float) = (xBottom + slope * (y - bottom.y)).coerceIn(0f, 1f)
    return CarOnScreen(rearY, frontY, xAt(rearY), xAt(frontY))
}

/**
 * Where the car is across the passage at this gate. With both gaps known, it's their ratio. With
 * one, the car is drawn nearer that wall only once the gap is below the warning gap: the app
 * doesn't know the passage's width.
 */
private fun GateOnScreen.carX(warningGapMm: Int): Float {
    val l = left?.gap?.mm?.coerceAtLeast(0)
    val r = right?.gap?.mm?.coerceAtLeast(0)
    return when {
        l != null && r != null -> if (l + r == 0) 0.5f else l.toFloat() / (l + r)
        l != null -> 0.5f * (l.toFloat() / warningGapMm).coerceAtMost(1f)
        r != null -> 1 - 0.5f * (r.toFloat() / warningGapMm).coerceAtMost(1f)
        else -> 0.5f
    }
}

/**
 * The steering suggestion. With two gates seeing the car, the app projects its heading to the
 * next gate ahead; with one, it compares the gaps there. At a gate with a unit on one side only,
 * it says to steer away from that wall once the gap is below the warning gap. When reversing, the
 * rear of the car moves the way the wheel is turned, so the advice is the same.
 */
private fun steer(gates: List<GateOnScreen>, direction: Direction, warningGapMm: Int): Steer? {
    val seeing = gates.filter { it.seesCar }
    if (seeing.isEmpty()) return null
    val forwards = direction == Direction.Forwards
    val lead = if (forwards) seeing.last() else seeing.first()
    val trail = seeing.getOrNull(seeing.indexOf(lead) + if (forwards) -1 else 1)
    val next = if (forwards) {
        gates.firstOrNull { it.y > lead.y }
    } else {
        gates.lastOrNull { it.y < lead.y }
    }

    val leadLeft = lead.left?.gap?.mm
    val leadRight = lead.right?.gap?.mm
    val trailLeft = trail?.left?.gap?.mm
    val trailRight = trail?.right?.gap?.mm
    val behind = trail?.let { abs(lead.positionMm - it.positionMm) } ?: 0
    val ahead = next?.let { abs(it.positionMm - lead.positionMm) } ?: 0
    // Both sides are projected or neither, so that the two gaps compared are alike.
    val projects = next != null && behind > 0 &&
        (leadLeft == null || trailLeft != null) &&
        (leadRight == null || trailRight != null)

    fun project(leadGap: Int?, trailGap: Int?): Int? =
        if (projects && leadGap != null && trailGap != null) {
            leadGap + (leadGap - trailGap) * ahead / behind
        } else {
            leadGap
        }

    val left = project(leadLeft, trailLeft)
    val right = project(leadRight, trailRight)
    return when {
        left != null && right != null -> when {
            left < right - STEER_MARGIN_MM -> Steer.Right
            right < left - STEER_MARGIN_MM -> Steer.Left
            else -> Steer.Straight
        }

        left != null -> if (left < warningGapMm) Steer.Right else Steer.Straight

        right != null -> if (right < warningGapMm) Steer.Left else Steer.Straight

        else -> Steer.Straight
    }
}

/**
 * Which end the car came in by, the end nearer the first gate that saw it, and when a gate last saw
 * it. With one gate the app can't tell, and takes the usual end.
 */
data class Entry(val end: End = End.Usual, val lastSeen: ComparableTimeMark? = null) {
    /** The entry after these readings. Once no gate has seen the car for [FORGET_AFTER], it starts over. */
    fun next(setup: PassageSetup, units: Map<UnitId, UnitLive>, now: ComparableTimeMark): Entry {
        val seeing = (0 until setup.gateCount).filter { gate ->
            setup.units.any { unit ->
                unit.gate == gate &&
                    gapOf(units[unit.id]?.reading, unit.zeroOffsetMm, 0, 0, now).mm != null
            }
        }
        val farOnly = setup.gateCount > 1 && seeing.minOrNull() == setup.gateCount - 1
        return when {
            seeing.isNotEmpty() && lastSeen == null -> Entry(
                if (farOnly) End.Far else End.Usual,
                now
            )

            seeing.isNotEmpty() -> copy(lastSeen = now)

            lastSeen != null && now - lastSeen > FORGET_AFTER -> Entry()

            else -> this
        }
    }

    companion object {
        val FORGET_AFTER = 3.seconds
    }
}
