package io.github.randomf.safedriveway.live

import com.juul.kable.Advertisement
import io.github.randomf.safedriveway.SecureStorage
import io.github.randomf.safedriveway.backend.Backend
import io.github.randomf.safedriveway.backend.DriverPreferences
import io.github.randomf.safedriveway.backend.Nearby
import io.github.randomf.safedriveway.backend.OpenPassage
import io.github.randomf.safedriveway.backend.PinResetProgress
import io.github.randomf.safedriveway.backend.PinResetState
import io.github.randomf.safedriveway.backend.RememberedPassage
import io.github.randomf.safedriveway.backend.Stored
import io.github.randomf.safedriveway.backend.UnitWrite
import io.github.randomf.safedriveway.backend.UnlockResult
import io.github.randomf.safedriveway.backend.WriteProgress
import io.github.randomf.safedriveway.backend.WriteState
import io.github.randomf.safedriveway.backend.decodeStored
import io.github.randomf.safedriveway.backend.encodeStored
import io.github.randomf.safedriveway.passage.Alarm
import io.github.randomf.safedriveway.passage.Connection
import io.github.randomf.safedriveway.passage.PassageId
import io.github.randomf.safedriveway.passage.PassageSetup
import io.github.randomf.safedriveway.passage.Reading
import io.github.randomf.safedriveway.passage.ReadingStatus
import io.github.randomf.safedriveway.passage.UnitId
import io.github.randomf.safedriveway.passage.carSeen
import io.github.randomf.safedriveway.protocol.Command
import io.github.randomf.safedriveway.protocol.CommandResult
import io.github.randomf.safedriveway.protocol.Protocol
import io.github.randomf.safedriveway.protocol.decodeAdvertised
import io.github.randomf.safedriveway.protocol.encodePassageRecord
import io.github.randomf.safedriveway.protocol.unitIdOfAddress
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The backend on the real units: BLE with Kable, as in doc/ble-protocol.md, and the phone's
 * storage (ADR 0011). Everything it knows is one [LiveState], which it changes on the main thread
 * only; around it, the shell runs the scan, a [Link] per unit it needs, and the writes and PIN
 * resets. [setActive] starts and stops it with the app's foreground and Bluetooth.
 */
class LiveBackend(private val storage: SecureStorage) : Backend {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clock = TimeSource.Monotonic

    private val state = MutableStateFlow(
        LiveState(stored = storage.load()?.let(::decodeStored) ?: Stored())
    )

    private fun update(change: (LiveState) -> LiveState) = state.update(change)

    private val host = object : LinkHost {
        override val state: StateFlow<LiveState> = this@LiveBackend.state

        override fun update(change: (LiveState) -> LiveState) = this@LiveBackend.update(change)

        override fun onConnected(link: Link) = this@LiveBackend.onConnected(link)
    }

    private val _nearby = MutableStateFlow(Nearby())
    override val nearby: StateFlow<Nearby> = _nearby

    override val remembered: StateFlow<List<RememberedPassage>> =
        state.map { it.stored }.distinctUntilChanged().map { it.remembered() }
            .stateIn(scope, SharingStarted.Eagerly, state.value.stored.remembered())

    override val open: StateFlow<OpenPassage?> =
        state.map { it.openPassage() }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Only valid readings: Set zero takes the distance, which other statuses don't measure. */
    override val setupReadings: StateFlow<Map<UnitId, Reading>> = state.map { s ->
        s.setupReadingUnits.mapNotNull { id ->
            s.link(id)?.takeIf { it.connection == Connection.Connected }?.reading
                ?.takeIf { it.status == ReadingStatus.Valid }?.let { id to it }
        }.toMap()
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val _writing = MutableStateFlow<WriteProgress?>(null)
    override val writing: StateFlow<WriteProgress?> = _writing

    private val _pinReset = MutableStateFlow<PinResetProgress?>(null)
    override val pinReset: StateFlow<PinResetProgress?> = _pinReset

    private val links = mutableMapOf<UnitId, Link>()

    /** Each unit's last link while it lets go, so that the next waits for it. */
    private val stopping = mutableMapOf<UnitId, Job>()

    private var scanJob: Job? = null
    private var signalsAt: ComparableTimeMark? = null
    private var scanStarted: ComparableTimeMark? = null
    private var goingInactive: Job? = null
    private var writeJob: Job? = null
    private var pendingWrite: PendingWrite? = null
    private var pinResetJob: Job? = null

    /** When each unit being reset was first seen in its PIN reset window. */
    private val windowSeenAt = mutableMapOf<UnitId, ComparableTimeMark>()

    /** What the phone stored last, so that closing saves only a change. */
    private var saved = state.value.stored

    init {
        // Saves what the phone stores when it changes, the latest only, at most every SAVE_EVERY:
        // a slider being dragged would otherwise save dozens of times a second.
        scope.launch {
            state.map { it.stored }.distinctUntilChanged().drop(1).conflate().collect { stored ->
                save(stored)
                delay(SAVE_EVERY)
            }
        }
        scope.launch {
            while (true) {
                tick()
                delay(TICK)
            }
        }
    }

    /**
     * Scans and connects while [active]: the app is in the foreground with Bluetooth ready and
     * not in demo mode. A write under way finishes before the app lets go of the units.
     */
    fun setActive(active: Boolean) {
        if (active) {
            goingInactive?.cancel()
            goingInactive = null
            if (state.value.active) return
            val now = clock.markNow()
            update { s -> s.copy(active = true).withOpen { it.copy(activeAt = now) } }
            startScanning()
            reconcile()
        } else if (state.value.active && goingInactive == null) {
            goingInactive = scope.launch {
                writeJob?.join()
                scanJob?.cancel()
                scanJob = null
                update { it.copy(active = false, sightings = emptyMap()) }
                _nearby.value = Nearby()
                reconcile()
                goingInactive = null
            }
        }
    }

    private suspend fun save(stored: Stored) {
        saved = stored
        val bytes = encodeStored(stored)
        withContext(Dispatchers.Default) { storage.save(bytes) }
    }

    /**
     * Lets go of every unit, as when the app goes to the background, saves what is left to save,
     * and stops for good. A write under way stops with it.
     */
    fun close() {
        val keep = keepWarning()
        listOf(goingInactive, writeJob, pinResetJob, scanJob).forEach { it?.cancel() }
        // Nothing is wanted any more, so the tick starts no link while these stop.
        update { it.copy(active = false, links = emptyMap()) }
        val all = links.toMap()
        links.clear()
        scope.launch {
            withContext(NonCancellable) {
                all.map { (id, link) -> launch { link.stop(release = id !in keep) } }.joinAll()
                state.value.stored.takeIf { it != saved }?.let { save(it) }
            }
            scope.cancel()
        }
    }

    private fun startScanning() {
        scanStarted = clock.markNow()
        scanJob = scope.launch {
            while (true) {
                try {
                    unitScanner().advertisements.collect(::onAdvertisement)
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    log("scan failed: $e")
                }
                // Android blocks an app that starts more than 5 scans in 30 s.
                delay(SCAN_RETRY)
            }
        }
    }

    private fun onAdvertisement(advertisement: Advertisement) {
        val payload = advertisement.manufacturerData(Protocol.COMPANY_ID) ?: return
        val advertised = decodeAdvertised(payload) ?: return
        val address = advertisement.identifier.toString()
        update { s ->
            // The name comes in the scan response, which not every result carries.
            val name = advertisement.name ?: s.sightings[address]?.name
            val sighting = Sighting(
                address,
                unitIdOfAddress(address),
                advertised,
                name,
                advertisement.rssi,
                clock.markNow()
            )
            s.copy(sightings = s.sightings + (address to sighting))
        }
    }

    /** Four times a second: forgets units out of range, follows the car, and connects what's needed. */
    private fun tick() {
        val now = clock.markNow()
        update { s -> s.copy(sightings = s.sightings.filterValues { it.isFresh(now) }) }
        // Every advertisement carries a signal strength; the screens take it once a second.
        if (signalsAt?.let { now - it >= SIGNALS_EVERY } != false) {
            signalsAt = now
            update { it.withSignals() }
        }
        val s = state.value
        if (s.active) {
            val settled = scanStarted?.let { now - it > SCAN_SETTLES } == true
            _nearby.value = nearbyOf(s.sightings.values, s::inUseByAnother, settled)
        }
        val passage = s.openPassage()
        if (passage != null && carSeen(passage.setup, passage.units, now)) {
            update { it.withOpen { open -> open.copy(carSeenAt = now, activeAt = now) } }
        }
        val open = state.value.open
        val busy = open?.unlockedWith != null || writeJob?.isActive == true ||
            pinResetJob?.isActive == true
        if (s.active && open != null && !open.paused && !busy && open.idle(now)) {
            log("no car for ${OpenState.PAUSE_AFTER}: pausing")
            update { it.withOpen { open -> open.copy(paused = true) } }
            reconcile(keep = emptySet())
        } else {
            reconcile()
        }
        if (_pinReset.value != null) followPinResetWindows(now)
    }

    /**
     * The open passage's units, if a unit had a gap a moment ago: the car may be between the walls,
     * so the app lets go of them without Release, and they keep warning for 5 minutes.
     */
    private fun keepWarning(): Set<UnitId> {
        val open = state.value.open ?: return emptySet()
        val recent = open.carSeenAt?.let { clock.markNow() - it < Alarm.CAR_HOLD } == true
        return if (recent) open.setup?.units?.map { it.id }?.toSet().orEmpty() else emptySet()
    }

    /** Starts a link for every unit the app needs, and stops the others. */
    private fun reconcile(keep: Set<UnitId> = keepWarning()) {
        val s = state.value
        val wanted = s.wantedLinks()
        (links.keys - wanted).forEach { id ->
            val link = links.remove(id) ?: return@forEach
            update { it.copy(links = it.links - id) }
            stopping[id] = scope.launch { link.stop(release = id !in keep) }
        }
        (wanted - links.keys).forEach { id ->
            val link = Link(id, host, scope)
            links[id] = link
            update { it.copy(links = it.links + (id to LinkState())) }
            link.start(after = stopping[id])
        }
    }

    /** Starts the links to [units] afresh, so that one waiting out a failure tries at once. */
    private fun restart(units: Collection<UnitId>) {
        units.mapNotNull { id -> links.remove(id)?.let { id to it } }.forEach { (id, link) ->
            update { it.copy(links = it.links - id) }
            stopping[id] = scope.launch { link.stop(release = true) }
        }
        reconcile()
    }

    /**
     * A unit has connected (doc/ble-protocol.md#connecting-to-a-passage). Its record may be newer
     * than the phone's; if it holds an older one, a phone that knows the PIN brings it up to date;
     * if the newest record doesn't list it, it was forgotten or replaced while out of reach, and a
     * phone that knows the PIN resets it. Units being changed get their unlock back.
     */
    private fun onConnected(link: Link) {
        val s = state.value
        val open = s.open ?: return
        val record = s.link(link.id)?.record
        if (record != null && record.id == open.id &&
            record.version > (open.setup?.version ?: -1)
        ) {
            adopt(record)
        }
        val newest = state.value.open?.setup ?: return
        val pins = pinsOf(open.id)
        val setUp = link.id in s.busy || link.id in s.setupReadingUnits
        val linkState = state.value.link(link.id) ?: return
        scope.launch {
            when {
                newest.unit(link.id) == null && record?.id == open.id && !setUp -> {
                    log("${link.id} isn't in setup version ${newest.version}: forgetting it")
                    if (pins.isNotEmpty()) forget(link, pins)
                    update { it.withOpen { o -> o.copy(ignored = o.ignored + link.id) } }
                    reconcile(keep = emptySet())
                    return@launch
                }

                linkState.isOutOfDate(newest) && record != null && pins.isNotEmpty() &&
                    writeJob?.isActive != true -> {
                    log("${link.id} holds setup version ${record.version}: updating")
                    bringLinkUpToDate(link, newest, pins)
                }
            }
            open.unlockedWith?.let { link.unlock(it) }
        }
    }

    /**
     * Writes [newest] to a unit that holds an older setup. One that refuses every PIN the phone
     * knows has a PIN this phone never had: the phone forgets its PINs rather than spend another
     * of the unit's 5 tries on each connection, and asks the driver next time.
     */
    private suspend fun bringLinkUpToDate(link: Link, newest: PassageSetup, pins: List<String>) {
        val (answer, took) = unlockWithAny(link, pins)
        when {
            took != null ->
                commit(link, newest, listOf(took), newPin = pins.first().takeIf { it != took })

            answer == CommandResult.WrongPin -> {
                log("${link.id} refuses the PINs this phone knows: forgetting them")
                update { s ->
                    s.copy(
                        stored = s.stored.withPassage(newest.id) {
                            it.copy(pin = null, previousPin = null)
                        }
                    )
                }
            }
        }
    }

    /** Takes [setup] as the open passage's newest, and remembers it. */
    private fun adopt(setup: PassageSetup) {
        log("setup version ${setup.version} of ${setup.name}")
        update { s ->
            s.withOpen {
                it.copy(
                    setup = setup,
                    ignored =
                        it.ignored - setup.units.map { u -> u.id }.toSet()
                )
            }
                .copy(stored = s.stored.withPassage(setup.id, setup) { it.copy(setup = setup) })
        }
    }

    override fun openPassage(id: PassageId) {
        if (state.value.open?.id == id) return
        closePassage()
        val setup = state.value.stored.passage(id)?.setup
        update { it.copy(open = OpenState(id, setup, activeAt = clock.markNow())) }
        reconcile()
    }

    override fun closePassage() {
        val keep = keepWarning()
        update { it.copy(open = null) }
        reconcile(keep)
    }

    override fun connect(unit: UnitId) {
        update { it.withOpen { open -> open.copy(disconnected = open.disconnected - unit) } }
        reconcile()
    }

    override fun disconnect(unit: UnitId) {
        update { it.withOpen { open -> open.copy(disconnected = open.disconnected + unit) } }
        reconcile(keep = emptySet())
    }

    override fun reconnectAll() {
        val now = clock.markNow()
        update {
            it.withOpen { open ->
                open.copy(disconnected = emptySet(), paused = false, activeAt = now)
            }
        }
        val s = state.value
        restart(s.open?.setup?.units.orEmpty().map { it.id }.filterNot { s.connected(it) })
    }

    override fun identify(unit: UnitId) {
        scope.launch {
            val result = withUnit(unit, IDENTIFY_WAIT) { it.command(Command.Identify) }
            if (result != CommandResult.Done) log("$unit: couldn't identify")
        }
    }

    /**
     * Runs [block] on [unit]'s link once it's connected, and keeps the link until [block] ends.
     * Null when the unit didn't connect within [wait].
     */
    private suspend fun <T> withUnit(unit: UnitId, wait: Duration, block: suspend (Link) -> T): T? {
        update { it.copy(busy = it.busy + unit) }
        reconcile()
        try {
            withTimeoutOrNull(wait) { state.first { it.connected(unit) } } ?: return null
            return links[unit]?.let { block(it) }
        } finally {
            update { it.copy(busy = it.busy - unit) }
            reconcile()
        }
    }

    override fun chosenAmong(passages: Set<PassageId>): PassageId? =
        state.value.stored.choices[passages]

    override fun choose(passages: Set<PassageId>, chosen: PassageId) = update {
        it.copy(stored = it.stored.copy(choices = it.stored.choices + (passages to chosen)))
    }

    override fun setPreferences(id: PassageId, preferences: DriverPreferences) = update { s ->
        s.copy(stored = s.stored.withPassage(id) { it.copy(preferences = preferences) })
    }

    override fun removeFromPhone(id: PassageId) =
        update { it.copy(stored = it.stored.withoutPassage(id)) }

    /** The open passage's units that are connected now. */
    private fun connectedUnits(id: PassageId): List<Link> {
        val s = state.value
        val setup = s.open?.takeIf { it.id == id }?.setup ?: return emptyList()
        return setup.units.filter { s.connected(it.id) }.mapNotNull { links[it.id] }
    }

    /**
     * Unlocks the open passage's connected units with [pin], or with the PINs the phone knows. A
     * typed PIN that no unit answered is unchecked; one the phone knows is trusted, and each unit
     * checks it again when the setup is written.
     */
    override suspend fun unlock(id: PassageId, pin: String?): UnlockResult {
        val pins = pin?.let(::listOf) ?: pinsOf(id).ifEmpty { return UnlockResult.WrongPin }
        val results = coroutineScope {
            connectedUnits(id).map { link -> async { link to unlockWithAny(link, pins) } }
                .awaitAll()
        }
        val answers = results.map { (_, result) -> result.first }
        val outcome = when {
            CommandResult.LockedOut in answers -> UnlockResult.LockedOut
            CommandResult.WrongPin in answers -> UnlockResult.WrongPin
            pin != null && CommandResult.Done !in answers -> UnlockResult.Unchecked
            else -> UnlockResult.Unlocked
        }
        if (outcome != UnlockResult.Unlocked) {
            results.filter { (_, result) -> result.first == CommandResult.Done }
                .forEach { (link, _) -> link.command(Command.Lock) }
            return outcome
        }
        // A typed PIN that a unit took is remembered.
        update { s ->
            val stored = pin?.let { typed -> s.stored.withPassage(id) { it.copy(pin = typed) } }
            s.withOpen { it.copy(unlockedWith = pins.first()) }.copy(stored = stored ?: s.stored)
        }
        return UnlockResult.Unlocked
    }

    override fun lock() {
        val open = state.value.open ?: return
        if (open.unlockedWith == null) return
        update { it.withOpen { o -> o.copy(unlockedWith = null) } }
        val units = connectedUnits(open.id)
        scope.launch { units.forEach { it.command(Command.Lock) } }
    }

    override fun startSetupReadings(units: List<UnitId>) {
        update { it.copy(setupReadingUnits = units) }
        reconcile()
    }

    override fun stopSetupReadings() {
        update { it.copy(setupReadingUnits = emptyList()) }
        reconcile()
    }

    /** What a write does to one unit. */
    private sealed interface UnitOp {
        data class Commit(val setup: PassageSetup, val pins: List<String>, val newPin: String?) :
            UnitOp

        data class Forget(val pins: List<String>) : UnitOp
    }

    /** A write in progress. [onDone] gets the units that didn't take it: failed or out of reach. */
    private class PendingWrite(
        val ops: Map<UnitId, UnitOp>,
        val onDone: (missed: Set<UnitId>) -> Unit
    )

    /**
     * The PINs the passage's units may hold, the current first: the remembered one, the one before
     * it, and the one the units were just unlocked with.
     */
    private fun pinsOf(id: PassageId): List<String> {
        val stored = state.value.stored.passage(id)
        val unlockedWith = state.value.open?.takeIf { it.id == id }?.unlockedWith
        return listOfNotNull(stored?.pin, stored?.previousPin, unlockedWith).distinct()
    }

    override fun write(setup: PassageSetup, pin: String?, forget: List<UnitId>) {
        val pins = pinsOf(setup.id)
        val current = pins.firstOrNull()
        val ops = setup.units.associate { it.id to UnitOp.Commit(setup, pins, pin) as UnitOp } +
            forget.associateWith { UnitOp.Forget(pins) }
        startWrite(ops, deleting = false) { _ ->
            // The phone takes the setup as the newest even if some units missed it: it brings them
            // up to date when it next finds them, with the PIN they still hold.
            update { s ->
                val stored = s.stored.withPassage(setup.id, setup) {
                    val changed = pin != null && pin != current
                    it.copy(
                        setup = setup,
                        pin = pin ?: current,
                        previousPin = if (changed) current else it.previousPin
                    )
                }
                s.copy(stored = stored).withOpen { open ->
                    if (open.id != setup.id) {
                        open
                    } else {
                        open.copy(
                            setup = setup,
                            unlockedWith = null,
                            ignored =
                                open.ignored + forget
                        )
                    }
                }
            }
            reconcile()
        }
    }

    /**
     * Writes the newest setup to the units that are behind. The driver's PIN unlocked them all, so
     * the units that were up to date are locked again afterwards, and the phone's alarm sounds again.
     */
    override fun bringUpToDate() {
        val passage = open.value ?: return
        val pins = pinsOf(passage.setup.id)
        val outOfDate = passage.units.filterValues { it.outOfDate }.keys
        startWrite(
            outOfDate.associateWith { UnitOp.Commit(passage.setup, pins, null) },
            deleting = false
        ) { lock() }
    }

    override fun deletePassage(id: PassageId) {
        val setup = state.value.open?.takeIf { it.id == id }?.setup
            ?: state.value.stored.passage(id)?.setup ?: return
        val pins = pinsOf(id)
        startWrite(setup.units.associate { it.id to UnitOp.Forget(pins) }, deleting = true) {
            removeFromPhone(id)
            if (state.value.open?.id == id) closePassage()
        }
    }

    private fun startWrite(
        ops: Map<UnitId, UnitOp>,
        deleting: Boolean,
        onDone: (Set<UnitId>) -> Unit
    ) {
        pendingWrite = PendingWrite(ops, onDone)
        _writing.value = WriteProgress(ops.keys.map { UnitWrite(it, WriteState.Waiting) }, deleting)
        runWrite()
    }

    /** Writes to each waiting unit in turn (doc/ble-protocol.md#changing-the-setup). */
    private fun runWrite() {
        val pending = pendingWrite ?: return
        writeJob?.cancel()
        writeJob = scope.launch {
            pending.ops.forEach { (id, op) ->
                if (_writing.value?.units?.find { it.id == id }?.state != WriteState.Waiting) {
                    return@forEach
                }
                setWriteState(id, WriteState.Writing)
                setWriteState(id, writeUnit(id, op))
            }
            if (_writing.value?.failed == false) finishWrite()
        }
    }

    private suspend fun writeUnit(id: UnitId, op: UnitOp): WriteState {
        val sighting = state.value.sightings.values.of(id)
        if (!state.value.connected(id)) {
            if (sighting == null) return WriteState.OutOfReach
            if (state.value.inUseByAnother(sighting)) return WriteState.Failed
        }
        val done = withUnit(id, CONNECT_WAIT) { link ->
            when (op) {
                is UnitOp.Commit -> commit(link, op.setup, op.pins, op.newPin)
                is UnitOp.Forget -> forget(link, op.pins)
            }
        }
        return when {
            done == true -> WriteState.Done
            done == null && state.value.sightings.values.of(id) == null -> WriteState.OutOfReach
            else -> WriteState.Failed
        }
    }

    /**
     * Unlocks the unit with the first of [pins] it takes, the current PIN first: a unit that missed
     * a change of the PIN still holds the one before, and a correct PIN clears its count of wrong
     * ones. The answer to the last Unlock, and the PIN it took.
     */
    private suspend fun unlockWithAny(
        link: Link,
        pins: List<String>
    ): Pair<CommandResult?, String?> {
        val pin = pins.firstOrNull() ?: return null to null
        val answer = link.unlock(pin)
        return when {
            answer == CommandResult.Done -> answer to pin
            answer == CommandResult.WrongPin && pins.size > 1 -> unlockWithAny(link, pins.drop(1))
            else -> answer to null
        }
    }

    /**
     * Commits [setup] to the unit: unlocks it with one of [pins] if it belongs to a passage, and
     * sets [newPin], the passage's PIN on a new unit, or the current PIN on a unit that took an
     * older one. Then it stages the record and commits it, which locks the unit. A unit it unlocked
     * is locked again if anything after fails.
     */
    private suspend fun commit(
        link: Link,
        setup: PassageSetup,
        pins: List<String>,
        newPin: String?
    ): Boolean {
        val assigned = state.value.link(link.id)?.record != null
        val took = if (assigned) unlockWithAny(link, pins).second ?: return false else null
        val pin = newPin ?: pins.firstOrNull()
        val setsPin = newPin != null || !assigned || took != pins.firstOrNull()
        val pinSet = !setsPin ||
            (pin != null && link.command(Command.SetPin, pin) == CommandResult.Done)
        val done = pinSet && stageAndCommit(link, setup)
        if (done) {
            update { s -> s.withLink(link.id) { it.copy(record = setup, recordRead = true) } }
        } else {
            link.command(Command.Lock)
        }
        return done
    }

    /**
     * Stages the record and commits it. A Commit with no answer may still have taken: the unit's
     * record tells.
     */
    private suspend fun stageAndCommit(link: Link, setup: PassageSetup): Boolean {
        val record = encodePassageRecord(setup)
        val result = if (link.stage(record)) link.command(Command.Commit) else null
        return result == CommandResult.Done ||
            (result == null && link.readSetup()?.contentEquals(record) == true)
    }

    /**
     * Unlocks the unit and forgets its setup and PIN: it then belongs to no passage. A unit it
     * unlocked is locked again if Forget fails.
     */
    private suspend fun forget(link: Link, pins: List<String>): Boolean {
        if (state.value.link(link.id)?.record == null) return true
        unlockWithAny(link, pins).second ?: return false
        val done = link.command(Command.Forget) == CommandResult.Done
        if (done) {
            update { s -> s.withLink(link.id) { it.copy(record = null) } }
        } else {
            link.command(Command.Lock)
        }
        return done
    }

    private fun setWriteState(id: UnitId, state: WriteState) =
        _writing.update { it?.with(id, state) }

    override fun retryWrite() {
        _writing.update { it?.retried() }
        runWrite()
    }

    /** The units that took the write keep it, also when the driver leaves after a failure. */
    private fun finishWrite() {
        val pending = pendingWrite ?: return
        pendingWrite = null
        pending.onDone(_writing.value?.missed.orEmpty())
    }

    override fun clearWrite() {
        if (_writing.value?.finished == true) finishWrite()
        _writing.value = null
    }

    /**
     * Sets [pin] on each of the passage's units as it comes back in its PIN reset window, after the
     * driver switches it off and on (doc/ble-protocol.md#resetting-a-forgotten-pin).
     */
    override fun startPinReset(id: PassageId, pin: String) {
        val setup = state.value.open?.takeIf { it.id == id }?.setup
            ?: state.value.stored.passage(id)?.setup ?: return
        val units = setup.units.map { it.id }
        pinResetJob?.cancel()
        windowSeenAt.clear()
        _pinReset.value = PinResetProgress(units.map { it to PinResetState.WaitingForRestart })
        update { it.copy(busy = it.busy + units) }
        reconcile()
        pinResetJob = scope.launch {
            units.map { unit -> launch { resetUnit(unit, pin) } }.joinAll()
            update { s -> s.copy(stored = s.stored.withPassage(id, setup) { it.copy(pin = pin) }) }
        }
    }

    private suspend fun resetUnit(unit: UnitId, pin: String) {
        while (true) {
            state.first { s ->
                s.connected(unit) && s.link(unit)?.status?.flags?.pinResetWindow == true
            }
            val link = links[unit]
            if (link == null) {
                delay(TICK)
                continue
            }
            setPinResetState(unit, PinResetState.Setting(secondsLeft(unit)))
            if (link.command(Command.SetPin, pin) == CommandResult.Done) {
                link.command(Command.Lock)
                setPinResetState(unit, PinResetState.Done)
                return
            }
            setPinResetState(unit, PinResetState.Missed)
            // The next switch-on opens a new window.
            state.first { it.link(unit)?.status?.flags?.pinResetWindow != true }
        }
    }

    /** Follows when each unit's window opened, from its advertising or its status. */
    private fun followPinResetWindows(now: ComparableTimeMark) {
        val s = state.value
        _pinReset.value?.units?.forEach { (unit, resetState) ->
            val open = s.link(unit)?.status?.flags?.pinResetWindow == true ||
                s.sightings.values.of(unit)?.advertised?.flags?.pinResetWindow == true
            if (!open) windowSeenAt.remove(unit) else windowSeenAt.getOrPut(unit) { now }
            if (resetState is PinResetState.Setting) {
                setPinResetState(unit, PinResetState.Setting(secondsLeft(unit)))
            }
        }
    }

    private fun secondsLeft(unit: UnitId): Int {
        val elapsed = windowSeenAt[unit]?.elapsedNow() ?: Duration.ZERO
        return (PIN_RESET_WINDOW - elapsed).inWholeSeconds.toInt().coerceAtLeast(0)
    }

    private fun setPinResetState(id: UnitId, state: PinResetState) =
        _pinReset.update { it?.with(id, state) }

    override fun clearPinReset() {
        pinResetJob?.cancel()
        pinResetJob = null
        val units = _pinReset.value?.units?.map { it.first }.orEmpty()
        _pinReset.value = null
        update { s -> s.copy(busy = units.fold(s.busy) { busy, unit -> busy - unit }) }
        reconcile()
    }

    private companion object {
        val TICK = 250.milliseconds
        val SIGNALS_EVERY = 1.seconds
        val SAVE_EVERY = 300.milliseconds
        val SCAN_SETTLES = 1500.milliseconds
        val SCAN_RETRY = 7.seconds

        /** How long a write waits for a unit to connect, and Identify for one that isn't. */
        val CONNECT_WAIT = 20.seconds
        val IDENTIFY_WAIT = 15.seconds

        /** A unit's PIN reset window after it is switched on. */
        val PIN_RESET_WINDOW = 2.minutes
    }
}
