package io.github.randomf.safedriveway.passage

import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.milliseconds

/** The phone's connection to one unit. */
enum class Connection {
    Searching,
    Connecting,
    Connected,
    Reconnecting,

    /** The phone holds as many BLE connections as it allows. */
    PhoneLimit,

    /** Another phone is connected to the unit. */
    InUse,

    /** The unit speaks a newer protocol version than the app knows. */
    NeedsNewerApp,

    /** The driver disconnected it. */
    Disconnected
}

/** A reading's status, as in the protocol. */
enum class ReadingStatus { Valid, TooClose, NoTarget, Unreliable, SensorFault }

/** One measurement from a unit. [distanceMm] is from the sensor, before the zero offset. */
data class Reading(
    val distanceMm: Int,
    val status: ReadingStatus,
    val measuredAt: ComparableTimeMark
) {
    /** A reading older than this shows as no data, never as a gap. */
    fun isStale(now: ComparableTimeMark): Boolean = now - measuredAt > STALE_AFTER

    companion object {
        val STALE_AFTER = 500.milliseconds
    }
}

/** What the phone knows about a unit right now. */
data class UnitLive(
    val connection: Connection,
    /** Null on a unit without the battery divider. */
    val batteryPercent: Int? = null,
    val sensorFault: Boolean = false,
    /** The unit holds an older setup than the passage's newest. */
    val outOfDate: Boolean = false,
    val firmware: String? = null,
    /** Signal strength in dBm. */
    val rssi: Int? = null,
    val reading: Reading? = null
) {
    val lowBattery: Boolean get() = batteryPercent != null && batteryPercent < LOW_BATTERY_PERCENT

    companion object {
        /** The units flag a low battery below this. */
        const val LOW_BATTERY_PERCENT = 20
    }
}

/** The one status a unit shows, the most important first (app/features.md). */
enum class UnitStatus {
    Searching,
    Connecting,
    Connected,
    Reconnecting,
    PhoneLimit,
    InUse,
    NeedsNewerApp,
    Disconnected,
    LowBattery,
    SensorFault
}

fun UnitLive?.status(): UnitStatus = when {
    this == null -> UnitStatus.Searching
    connection == Connection.Searching -> UnitStatus.Searching
    connection == Connection.Connecting -> UnitStatus.Connecting
    connection == Connection.Reconnecting -> UnitStatus.Reconnecting
    connection == Connection.PhoneLimit -> UnitStatus.PhoneLimit
    connection == Connection.InUse -> UnitStatus.InUse
    connection == Connection.NeedsNewerApp -> UnitStatus.NeedsNewerApp
    connection == Connection.Disconnected -> UnitStatus.Disconnected
    sensorFault -> UnitStatus.SensorFault
    lowBattery -> UnitStatus.LowBattery
    else -> UnitStatus.Connected
}
