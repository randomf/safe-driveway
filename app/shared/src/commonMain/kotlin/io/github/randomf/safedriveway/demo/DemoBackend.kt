package io.github.randomf.safedriveway.demo

import io.github.randomf.safedriveway.backend.Backend
import io.github.randomf.safedriveway.backend.DriverPreferences
import io.github.randomf.safedriveway.backend.Nearby
import io.github.randomf.safedriveway.backend.NearbyPassage
import io.github.randomf.safedriveway.backend.NearbyUnit
import io.github.randomf.safedriveway.backend.OpenPassage
import io.github.randomf.safedriveway.backend.PinResetProgress
import io.github.randomf.safedriveway.backend.PinResetState
import io.github.randomf.safedriveway.backend.RememberedPassage
import io.github.randomf.safedriveway.backend.UnitWrite
import io.github.randomf.safedriveway.backend.UnlockResult
import io.github.randomf.safedriveway.backend.WriteProgress
import io.github.randomf.safedriveway.backend.WriteState
import io.github.randomf.safedriveway.passage.Connection
import io.github.randomf.safedriveway.passage.Direction
import io.github.randomf.safedriveway.passage.PassageId
import io.github.randomf.safedriveway.passage.PassageSetup
import io.github.randomf.safedriveway.passage.Reading
import io.github.randomf.safedriveway.passage.ReadingStatus
import io.github.randomf.safedriveway.passage.UnitId
import io.github.randomf.safedriveway.passage.UnitLive
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The prototype's backend: no Bluetooth, only a pretend world that the demo panel sets up through
 * [settings], and a simulated car that [playPass] drives through the open passage. It runs on
 * [scope]'s thread, the main thread, so its state needs no locking.
 */
class DemoBackend(private val scope: CoroutineScope) : Backend {
    private val _settings = MutableStateFlow(DemoSettings())
    val settings: StateFlow<DemoSettings> = _settings

    /** What the units store, by passage: the demo's passages and those set up in it. */
    private val stored = mutableMapOf(
        DemoWorld.garage.id to DemoWorld.garage,
        DemoWorld.frontGate.id to DemoWorld.frontGate
    )

    /** Units reset to belong to no passage, which then advertise as spare. */
    private val released = mutableMapOf<UnitId, Int>()
    private val choices = mutableMapOf<Set<PassageId>, PassageId>()
    private val learnedPins = mutableSetOf<PassageId>()

    /** Units that missed the last change of their passage's setup, so they hold an older one. */
    private val behind = mutableSetOf<UnitId>()

    /** The PIN each passage's units hold; the demo's own passages have [DEMO_PIN]. */
    private val pins = mutableMapOf<PassageId, String>()
    private var wrongPins = 0

    private val _nearby = MutableStateFlow(Nearby())
    override val nearby: StateFlow<Nearby> = _nearby

    private val _remembered = MutableStateFlow(
        listOf(
            RememberedPassage(DemoWorld.garage, DriverPreferences(), knowsPin = false),
            RememberedPassage(
                DemoWorld.frontGate,
                DriverPreferences(usualDirection = Direction.Reversing),
                knowsPin = false
            )
        )
    )
    override val remembered: StateFlow<List<RememberedPassage>> = _remembered

    private val _open = MutableStateFlow<OpenPassage?>(null)
    override val open: StateFlow<OpenPassage?> = _open

    private val _setupReadings = MutableStateFlow<Map<UnitId, Reading>>(emptyMap())
    override val setupReadings: StateFlow<Map<UnitId, Reading>> = _setupReadings

    private val _writing = MutableStateFlow<WriteProgress?>(null)
    override val writing: StateFlow<WriteProgress?> = _writing

    private val _pinReset = MutableStateFlow<PinResetProgress?>(null)
    override val pinReset: StateFlow<PinResetProgress?> = _pinReset

    private var scanJob: Job? = null
    private var openJob: Job? = null

    /** Each unit's connection in progress, so that closing or disconnecting can stop it. */
    private val connecting = mutableMapOf<UnitId, Job>()
    private var setupReadingsJob: Job? = null
    private var writeJob: Job? = null
    private var pendingWrite: PendingWrite? = null
    private var pinResetJob: Job? = null
    private var pass: Pair<DemoPass, TimeSource.Monotonic.ValueTimeMark>? = null

    private val _passing = MutableStateFlow(false)

    /** Whether the simulated car is driving through. */
    val passing: StateFlow<Boolean> = _passing

    init {
        rescan()
    }

    fun changeSettings(change: (DemoSettings) -> DemoSettings) {
        val old = _settings.value
        val new = change(old)
        _settings.value = new
        if (new.pinKnown != old.pinKnown) {
            _remembered.update { list -> list.map { it.copy(knowsPin = knowsPin(it.setup.id)) } }
        }
        if (new.inRange != old.inRange || new.problems != old.problems) rescan()
        val open = _open.value
        if (open != null && (new.inRange != old.inRange || new.problems != old.problems)) {
            openPassage(open.setup.id)
        }
    }

    /** Drives the simulated car through the open passage, as the demo panel sets it. */
    fun playPass() {
        val open = _open.value ?: return
        val settings = _settings.value
        pass = DemoPass(
            open.setup,
            DemoWorld.halfWidthsMm(open.setup),
            settings.direction,
            settings.entry,
            settings.course
        ) to clock.markNow()
        _passing.value = true
    }

    private fun knowsPin(id: PassageId) = _settings.value.pinKnown || id in learnedPins

    private fun inRange(id: PassageId): Boolean = when (_settings.value.inRange) {
        DemoInRange.Garage -> id !in demoPassages || id == DemoWorld.garage.id
        DemoInRange.GarageAndFrontGate -> true
        DemoInRange.Nothing -> false
        DemoInRange.NewUnits -> id !in demoPassages
    } && id in stored

    private val demoPassages = setOf(DemoWorld.garage.id, DemoWorld.frontGate.id)

    private fun spareInRange(): List<NearbyUnit> {
        if (_settings.value.inRange == DemoInRange.Nothing) return emptyList()
        val assigned = stored.values.flatMap { setup -> setup.units.map { it.id } }.toSet()
        return (DemoWorld.spare + released.toList())
            .filter { (id, _) -> id !in assigned }
            .distinctBy { it.first }
            .map { (id, rssi) -> NearbyUnit(id, rssi + Random.nextInt(-3, 4)) }
    }

    private fun currentNearby(settled: Boolean) = Nearby(
        settled = settled,
        passages = stored.values.filter { inRange(it.id) }.map {
            NearbyPassage(
                it.id,
                it.name,
                inUse = it.id == DemoWorld.garage.id && DemoProblem.InUse in problems
            )
        },
        unassigned = spareInRange()
    )

    private val problems get() = _settings.value.problems

    /** Starts listening again: the first results come in after a moment. */
    private fun rescan() {
        scanJob?.cancel()
        _nearby.value = Nearby(settled = false)
        scanJob = scope.launch {
            delay(SCAN_SETTLES)
            _nearby.value = currentNearby(settled = true)
        }
    }

    private fun refreshNearby() {
        if (_nearby.value.settled) _nearby.value = currentNearby(settled = true)
    }

    override fun openPassage(id: PassageId) {
        val setup = stored[id] ?: _remembered.value.find { it.setup.id == id }?.setup ?: return
        openJob?.cancel()
        stopConnecting()
        pass = null
        _passing.value = false
        rememberPassage(setup)
        _open.value =
            OpenPassage(setup, setup.units.associate { it.id to UnitLive(Connection.Searching) })
        openJob = scope.launch {
            if (inRange(id)) {
                setup.units.forEachIndexed { i, unit ->
                    connectLater(unit.id, i * 150L)
                }
            }
            while (isActive) {
                tick()
                delay(READING_INTERVAL)
            }
        }
    }

    override fun closePassage() {
        openJob?.cancel()
        stopConnecting()
        _open.value = null
        pass = null
        _passing.value = false
    }

    private fun updateUnit(id: UnitId, change: (UnitLive) -> UnitLive) {
        _open.update { open ->
            val live = open?.units?.get(id) ?: return@update open
            open.copy(units = open.units + (id to change(live)))
        }
    }

    private fun stopConnecting() {
        connecting.values.forEach { it.cancel() }
        connecting.clear()
    }

    private fun connectLater(id: UnitId, after: Long) {
        connecting.remove(id)?.cancel()
        connecting[id] = scope.launch {
            delay(300 + after)
            val setupId = _open.value?.setup?.id ?: return@launch
            if (setupId == DemoWorld.garage.id && DemoProblem.InUse in problems) {
                updateUnit(id) { it.copy(connection = Connection.InUse) }
                return@launch
            }
            updateUnit(id) { it.copy(connection = Connection.Connecting) }
            delay(400L + Random.nextLong(500))
            val outcome = when {
                id == DemoWorld.gate1Right && DemoProblem.NeedsNewerApp in problems ->
                    Connection.NeedsNewerApp

                id == DemoWorld.bins && DemoProblem.PhoneLimit in problems -> Connection.PhoneLimit

                else -> Connection.Connected
            }
            val outOfDate =
                id in behind || (id == DemoWorld.pillar && DemoProblem.OutOfDate in problems)
            updateUnit(id) {
                it.copy(
                    connection = outcome,
                    batteryPercent = if (outcome != Connection.Connected) {
                        null
                    } else if (id == DemoWorld.gate1Right && DemoProblem.LowBattery in problems) {
                        DemoWorld.LOW_BATTERY
                    } else {
                        DemoWorld.battery[id]
                    },
                    sensorFault = id == DemoWorld.gate1Left && DemoProblem.SensorFault in problems,
                    outOfDate = outOfDate,
                    firmware = "1.0.0",
                    rssi = -55 - Random.nextInt(20)
                )
            }
            // A phone that knows the PIN brings an out-of-date unit up to date by itself.
            if (outOfDate && knowsPin(setupId)) {
                delay(1500)
                behind -= id
                updateUnit(id) { it.copy(outOfDate = false) }
            }
        }
    }

    /** Sends every connected unit its next reading. */
    private fun tick() {
        val open = _open.value ?: return
        val now = clock.markNow()
        val current = pass
        val progress = current?.let { (simulation, start) ->
            (start.elapsedNow() / simulation.duration).coerceAtMost(1.0)
        }
        val distances = current?.first?.distancesAt(progress!!)
        if (progress != null && progress >= 1.0) {
            pass = null
            _passing.value = false
        }
        val lost =
            progress != null && progress in 0.45..0.8 && DemoProblem.LostDuringPass in problems
        _open.value = open.copy(
            units = open.units.mapValues { (id, live) ->
                when {
                    id == DemoWorld.bins && lost && live.connection == Connection.Connected ->
                        live.copy(connection = Connection.Reconnecting)

                    id == DemoWorld.bins && !lost && live.connection == Connection.Reconnecting ->
                        live.copy(connection = Connection.Connected)

                    live.connection != Connection.Connected -> live

                    live.sensorFault -> live.copy(
                        reading = Reading(0, ReadingStatus.SensorFault, now)
                    )

                    else -> {
                        val (distance, status) = distances?.get(id) ?: (0 to ReadingStatus.NoTarget)
                        val noisy = if (status ==
                            ReadingStatus.Valid
                        ) {
                            distance + noise()
                        } else {
                            distance
                        }
                        live.copy(reading = Reading(noisy, status, now))
                    }
                }
            }
        )
    }

    private fun noise() = Random.nextInt(-3, 4)

    override fun connect(unit: UnitId) {
        updateUnit(unit) { it.copy(connection = Connection.Searching, reading = null) }
        connectLater(unit, 0)
    }

    override fun disconnect(unit: UnitId) {
        connecting.remove(unit)?.cancel()
        updateUnit(unit) { it.copy(connection = Connection.Disconnected, reading = null) }
    }

    override fun reconnectAll() {
        val open = _open.value ?: return
        open.units.filterValues {
            it.connection != Connection.Connected
        }.keys.forEachIndexed { i, id ->
            updateUnit(id) { it.copy(connection = Connection.Searching, reading = null) }
            connectLater(id, i * 150L)
        }
    }

    override fun identify(unit: UnitId) {
        // A real unit blinks and beeps for 5 s. The demo has nothing to blink.
    }

    override fun chosenAmong(passages: Set<PassageId>): PassageId? = choices[passages]

    override fun choose(passages: Set<PassageId>, chosen: PassageId) {
        choices[passages] = chosen
    }

    override fun setPreferences(id: PassageId, preferences: DriverPreferences) {
        _remembered.update { list ->
            list.map { if (it.setup.id == id) it.copy(preferences = preferences) else it }
        }
    }

    override fun removeFromPhone(id: PassageId) {
        learnedPins -= id
        _remembered.update { list -> list.filter { it.setup.id != id } }
    }

    /** Opening a passage the phone hasn't used before remembers it. */
    private fun rememberPassage(setup: PassageSetup) {
        _remembered.update { list ->
            if (list.any { it.setup.id == setup.id }) {
                list.map {
                    if (it.setup.id ==
                        setup.id
                    ) {
                        it.copy(setup = setup, knowsPin = knowsPin(setup.id))
                    } else {
                        it
                    }
                }
            } else {
                list + RememberedPassage(setup, DriverPreferences(), knowsPin(setup.id))
            }
        }
    }

    override suspend fun unlock(id: PassageId, pin: String?): UnlockResult {
        delay(400)
        if (wrongPins >= WRONG_PINS_TO_LOCK_OUT) return UnlockResult.LockedOut
        val right = if (pin == null) knowsPin(id) else pin == (pins[id] ?: DEMO_PIN)
        if (!right) {
            if (pin != null) wrongPins++
            return if (wrongPins >=
                WRONG_PINS_TO_LOCK_OUT
            ) {
                UnlockResult.LockedOut
            } else {
                UnlockResult.WrongPin
            }
        }
        wrongPins = 0
        learnedPins += id
        stored[id]?.let(::rememberPassage)
        return UnlockResult.Unlocked
    }

    override fun lock() {
        // The units lock again; nothing to do in the demo.
    }

    override fun startSetupReadings(units: List<UnitId>) {
        setupReadingsJob?.cancel()
        setupReadingsJob = scope.launch {
            while (isActive) {
                val now = clock.markNow()
                _setupReadings.value = units.associateWith { id ->
                    val distance = DemoWorld.boardDistanceMm(id, stored.values.toList()) + noise()
                    Reading(distance, ReadingStatus.Valid, now)
                }
                delay(READING_INTERVAL * 2)
            }
        }
    }

    override fun stopSetupReadings() {
        setupReadingsJob?.cancel()
        _setupReadings.value = emptyMap()
    }

    /** A write in progress. [onDone] gets the units that didn't take it: failed or out of reach. */
    private class PendingWrite(val units: List<UnitId>, val onDone: (missed: Set<UnitId>) -> Unit)

    private fun reachable(unit: UnitId): Boolean = _nearby.value.unassigned.any { it.id == unit } ||
        stored.values.any { setup -> setup.unit(unit) != null && inRange(setup.id) }

    override fun write(setup: PassageSetup, pin: String?, forget: List<UnitId>) {
        startWrite(setup.units.map { it.id } + forget, deleting = false) { missed ->
            stored[setup.id] = setup
            if (pin != null) {
                learnedPins += setup.id
                pins[setup.id] = pin
            }
            forget.filterNot { it in missed }.forEach { released[it] = -65 }
            val units = setup.units.map { it.id }
            behind += units.filter { it in missed }
            behind -= units.filterNot { it in missed }.toSet()
            rememberPassage(setup)
            if (_open.value?.setup?.id == setup.id) openPassage(setup.id)
            refreshNearby()
        }
    }

    override fun bringUpToDate() {
        val open = _open.value ?: return
        val outOfDate = open.units.filterValues { it.outOfDate }.keys.toList()
        startWrite(outOfDate, deleting = false) { missed ->
            val updated = outOfDate.filterNot { it in missed }
            this.behind -= updated.toSet()
            updated.forEach { id -> updateUnit(id) { it.copy(outOfDate = false) } }
        }
    }

    override fun deletePassage(id: PassageId) {
        val setup = stored[id] ?: return
        startWrite(setup.units.map { it.id }, deleting = true) { missed ->
            val (kept, reset) = setup.units.partition { it.id in missed }
            reset.forEach { released[it.id] = -65 }
            // A unit that missed the reset keeps the passage, which shows up again with it.
            if (kept.isEmpty()) {
                stored.remove(id)
            } else {
                stored[id] = reset.fold(setup) { rest, unit -> rest.withoutUnit(unit.id) }
            }
            removeFromPhone(id)
            if (_open.value?.setup?.id == id) closePassage()
            refreshNearby()
        }
    }

    private fun startWrite(units: List<UnitId>, deleting: Boolean, onDone: (Set<UnitId>) -> Unit) {
        pendingWrite = PendingWrite(units, onDone)
        _writing.value = WriteProgress(
            units.map {
                UnitWrite(it, if (reachable(it)) WriteState.Waiting else WriteState.OutOfReach)
            },
            deleting
        )
        runWrite(failOnce = DemoProblem.WriteFails in problems)
    }

    private fun runWrite(failOnce: Boolean) {
        val pending = pendingWrite ?: return
        writeJob?.cancel()
        writeJob = scope.launch {
            val failing = if (failOnce) pending.units.lastOrNull(::reachable) else null
            for (id in pending.units) {
                val state = _writing.value?.units?.find { it.id == id }?.state
                if (state != WriteState.Waiting) continue
                setWriteState(id, WriteState.Writing)
                delay(600)
                setWriteState(id, if (id == failing) WriteState.Failed else WriteState.Done)
            }
            if (_writing.value?.failed == false) finishWrite()
        }
    }

    private fun setWriteState(id: UnitId, state: WriteState) {
        _writing.update { progress ->
            progress?.copy(
                units = progress.units.map {
                    if (it.id ==
                        id
                    ) {
                        it.copy(state = state)
                    } else {
                        it
                    }
                }
            )
        }
    }

    override fun retryWrite() {
        _writing.update { progress ->
            progress?.copy(
                units = progress.units.map {
                    if (it.state == WriteState.Failed) it.copy(state = WriteState.Waiting) else it
                }
            )
        }
        runWrite(failOnce = false)
    }

    /** The units that took the write keep it, also when the driver leaves after a failure. */
    private fun finishWrite() {
        val pending = pendingWrite ?: return
        pendingWrite = null
        val missed = _writing.value?.units.orEmpty().filter { it.state != WriteState.Done }
        pending.onDone(missed.map { it.id }.toSet())
    }

    override fun clearWrite() {
        if (_writing.value?.finished == true) finishWrite()
        _writing.value = null
    }

    override fun startPinReset(id: PassageId, pin: String) {
        val setup = stored[id] ?: return
        pinResetJob?.cancel()
        _pinReset.value =
            PinResetProgress(setup.units.map { it.id to PinResetState.WaitingForRestart })
        pinResetJob = scope.launch {
            // The demo pretends the driver switches the units off and on one after another.
            setup.units.forEachIndexed { i, unit ->
                launch {
                    delay(2500L + i * 2000L)
                    setPinResetState(unit.id, PinResetState.Setting(secondsLeft = 118))
                    delay(1200)
                    setPinResetState(unit.id, PinResetState.Done)
                    if (_pinReset.value?.finished == true) {
                        pins[id] = pin
                        learnedPins += id
                        wrongPins = 0
                        rememberPassage(setup)
                    }
                }
            }
        }
    }

    private fun setPinResetState(id: UnitId, state: PinResetState) {
        _pinReset.update { progress ->
            progress?.copy(units = progress.units.map { if (it.first == id) id to state else it })
        }
    }

    override fun clearPinReset() {
        pinResetJob?.cancel()
        _pinReset.value = null
    }

    companion object {
        private val clock = TimeSource.Monotonic
        private val SCAN_SETTLES = 1200.milliseconds

        /** 20 readings per second. */
        private val READING_INTERVAL = 50.milliseconds
        private const val WRONG_PINS_TO_LOCK_OUT = 5
    }
}
