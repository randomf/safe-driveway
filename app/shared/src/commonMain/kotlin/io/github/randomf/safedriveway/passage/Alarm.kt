package io.github.randomf.safedriveway.passage

import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.seconds

/** What the phone sounds (app/features.md#alarms-and-suggestions). */
sealed interface Alarm {
    /** A short beep every [periodMs], faster as the gap closes. */
    data class Warning(val periodMs: Int) : Alarm

    /** A continuous tone, below the critical gap. */
    data object Critical : Alarm

    /** A unit went silent while the car was in the passage: the driver has lost a gap. */
    data object Silent : Alarm

    companion object {
        /** The beeps' period at the warning gap and just above the critical gap, as on the units. */
        const val SLOWEST_PERIOD_MS = 1000
        const val FASTEST_PERIOD_MS = 160

        /** The car counts as in the passage for this long after a unit last had a gap. */
        val CAR_HOLD = 5.seconds
    }
}

/** Whether any unit has a gap: a car is in front of it. */
fun carSeen(setup: PassageSetup, units: Map<UnitId, UnitLive>, now: ComparableTimeMark): Boolean =
    setup.units.any { unit -> gapFor(setup, unit, units[unit.id], now).mm != null }

/**
 * The alarm for these readings: the critical tone below the critical gap, else beeps below the
 * warning gap, else the silent-unit sound while the car is in the passage, else none. [carSeenAt]
 * is when a unit last had a gap.
 */
fun alarmOf(
    setup: PassageSetup,
    units: Map<UnitId, UnitLive>,
    now: ComparableTimeMark,
    carSeenAt: ComparableTimeMark?
): Alarm? {
    val gaps = setup.units.map { gapFor(setup, it, units[it.id], now) }
    val warning = gaps.filter { it.level == GapLevel.Warning }.mapNotNull { it.mm }.minOrNull()
    val carInPassage = carSeenAt != null && now - carSeenAt < Alarm.CAR_HOLD
    return when {
        gaps.any { it.level == GapLevel.Critical } -> Alarm.Critical

        warning != null -> Alarm.Warning(
            warningPeriodMs(warning, setup.warningGapMm, setup.criticalGapMm)
        )

        carInPassage && setup.units.any { units[it.id].isSilent(now) } -> Alarm.Silent

        else -> null
    }
}

/** Once a second at the warning gap, faster as the gap closes, as the units' own buzzers do. */
fun warningPeriodMs(gapMm: Int, warningGapMm: Int, criticalGapMm: Int): Int {
    val span = Alarm.SLOWEST_PERIOD_MS - Alarm.FASTEST_PERIOD_MS
    val room = (gapMm - criticalGapMm).coerceIn(0, warningGapMm - criticalGapMm)
    return Alarm.FASTEST_PERIOD_MS + span * room / (warningGapMm - criticalGapMm)
}

private fun gapFor(setup: PassageSetup, unit: UnitSetup, live: UnitLive?, now: ComparableTimeMark) =
    if (live?.connection == Connection.Connected) {
        gapOf(live.reading, unit.zeroOffsetMm, setup.warningGapMm, setup.criticalGapMm, now)
    } else {
        Gap.NoData
    }

/** The unit was connected and has stopped sending readings: lost, or its sensor failed. */
private fun UnitLive?.isSilent(now: ComparableTimeMark): Boolean = when (this?.connection) {
    Connection.Reconnecting -> true

    Connection.Connected ->
        reading == null || reading.isStale(now) || reading.status == ReadingStatus.SensorFault

    else -> false
}
