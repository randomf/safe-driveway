package io.github.randomf.safedriveway.backend

import io.github.randomf.safedriveway.passage.Direction
import io.github.randomf.safedriveway.passage.PassageId
import io.github.randomf.safedriveway.passage.PassageSetup
import io.github.randomf.safedriveway.passage.Reading
import io.github.randomf.safedriveway.passage.UnitId
import io.github.randomf.safedriveway.passage.UnitLive
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the screens need from below them: the units, over BLE, and what the phone stores.
 * LiveBackend implements it on the real units; DemoBackend fakes it for demo mode (ADR 0009).
 */
interface Backend {
    /** What the phone sees in the units' advertising. */
    val nearby: StateFlow<Nearby>

    /** The passages this phone has used. */
    val remembered: StateFlow<List<RememberedPassage>>

    /** The passage the app is connected to, if any. */
    val open: StateFlow<OpenPassage?>

    /** Readings from units being set up, which may belong to no passage yet. */
    val setupReadings: StateFlow<Map<UnitId, Reading>>

    /** The last write of a setup to the units, while its screen shows it. */
    val writing: StateFlow<WriteProgress?>

    /** The last reset of a forgotten PIN, while its screen shows it. */
    val pinReset: StateFlow<PinResetProgress?>

    /** Connects to every unit of the passage, and keeps them connected until [closePassage]. */
    fun openPassage(id: PassageId)

    fun closePassage()

    fun connect(unit: UnitId)

    fun disconnect(unit: UnitId)

    /** Connects every unit again: those the driver disconnected, and after a pause. */
    fun reconnectAll()

    /** Makes the unit blink and beep for 5 s. */
    fun identify(unit: UnitId)

    /** The passage the driver chose the last time these passages were in range together. */
    fun chosenAmong(passages: Set<PassageId>): PassageId?

    fun choose(passages: Set<PassageId>, chosen: PassageId)

    fun setPreferences(id: PassageId, preferences: DriverPreferences)

    /** The phone forgets the passage, its PIN and the driver's preferences. The units keep it. */
    fun removeFromPhone(id: PassageId)

    /**
     * Unlocks the open passage's units to change their setup. With no [pin], it uses the PIN the
     * phone remembers. A correct PIN is remembered.
     */
    suspend fun unlock(id: PassageId, pin: String?): UnlockResult

    /** Locks the units again, unchanged. */
    fun lock()

    /** Starts readings from units being set up, into [setupReadings], until [stopSetupReadings]. */
    fun startSetupReadings(units: List<UnitId>)

    fun stopSetupReadings()

    /**
     * Writes [setup] to its units, with [pin] as the passage's new PIN if it's set, and resets
     * the [forget] units to belong to no passage. Progress goes to [writing].
     */
    fun write(setup: PassageSetup, pin: String?, forget: List<UnitId>)

    /** Writes the newest setup to the open passage's units that are out of date. */
    fun bringUpToDate()

    /** Resets every unit of the passage in reach, and the phone forgets the passage. */
    fun deletePassage(id: PassageId)

    /** Writes again to the units that failed. */
    fun retryWrite()

    fun clearWrite()

    /** Sets [pin] on each unit of the passage as it comes back in its PIN reset window. */
    fun startPinReset(id: PassageId, pin: String)

    fun clearPinReset()
}

data class Nearby(
    /** False until the first scan has had time to hear the units in range. */
    val settled: Boolean = false,
    val passages: List<NearbyPassage> = emptyList(),
    /** Units that belong to no passage yet. */
    val unassigned: List<NearbyUnit> = emptyList()
)

data class NearbyPassage(
    val id: PassageId,
    val name: String,
    /** Another phone is connected to it. */
    val inUse: Boolean
)

data class NearbyUnit(val id: UnitId, val rssi: Int)

/** What the driver chose for a passage, kept on this phone only. */
data class DriverPreferences(
    val usualDirection: Direction = Direction.Forwards,
    val sound: Boolean = true,
    /** 0 to 1. */
    val volume: Float = 0.8f
)

data class RememberedPassage(
    val setup: PassageSetup,
    val preferences: DriverPreferences,
    val knowsPin: Boolean
)

/**
 * The passage the app is connected to: its newest setup and every unit's live state. [paused]:
 * the app let go of the units after minutes with no car, until the driver reconnects them.
 * [unlocked]: the units are unlocked for a change of their setup, and don't signal the gap.
 */
data class OpenPassage(
    val setup: PassageSetup,
    val units: Map<UnitId, UnitLive>,
    val paused: Boolean = false,
    val unlocked: Boolean = false
)

enum class UnlockResult {
    Unlocked,
    WrongPin,
    LockedOut,

    /** No unit answered, so the PIN couldn't be checked. */
    Unchecked
}

enum class WriteState { Waiting, Writing, Done, Failed, OutOfReach }

data class UnitWrite(val id: UnitId, val state: WriteState)

data class WriteProgress(val units: List<UnitWrite>, val deleting: Boolean = false) {
    val finished: Boolean
        get() = units.none { it.state == WriteState.Waiting || it.state == WriteState.Writing }
    val failed: Boolean get() = units.any { it.state == WriteState.Failed }

    /** The units that didn't take the write: failed, out of reach or not tried yet. */
    val missed: Set<UnitId> get() = units.filter {
        it.state != WriteState.Done
    }.map { it.id }.toSet()

    fun with(id: UnitId, state: WriteState): WriteProgress =
        copy(units = units.map { if (it.id == id) it.copy(state = state) else it })

    /** The failed units wait to be written again. */
    fun retried(): WriteProgress = copy(
        units = units.map {
            if (it.state == WriteState.Failed) it.copy(state = WriteState.Waiting) else it
        }
    )
}

sealed interface PinResetState {
    /** The driver hasn't switched the unit off and on yet. */
    data object WaitingForRestart : PinResetState

    data class Setting(val secondsLeft: Int) : PinResetState

    data object Done : PinResetState

    /** The window closed before the phone reached the unit. */
    data object Missed : PinResetState
}

data class PinResetProgress(val units: List<Pair<UnitId, PinResetState>>) {
    val finished: Boolean get() = units.all { it.second == PinResetState.Done }

    fun with(id: UnitId, state: PinResetState): PinResetProgress =
        copy(units = units.map { if (it.first == id) id to state else it })
}
