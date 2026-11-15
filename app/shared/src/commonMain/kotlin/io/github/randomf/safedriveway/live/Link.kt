package io.github.randomf.safedriveway.live

import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import io.github.randomf.safedriveway.passage.Connection
import io.github.randomf.safedriveway.passage.Reading
import io.github.randomf.safedriveway.passage.UnitId
import io.github.randomf.safedriveway.protocol.Answer
import io.github.randomf.safedriveway.protocol.Command
import io.github.randomf.safedriveway.protocol.CommandResult
import io.github.randomf.safedriveway.protocol.Protocol
import io.github.randomf.safedriveway.protocol.decodeAnswer
import io.github.randomf.safedriveway.protocol.decodePassageRecord
import io.github.randomf.safedriveway.protocol.decodeReading
import io.github.randomf.safedriveway.protocol.decodeStatus
import io.github.randomf.safedriveway.protocol.decodeUnitInfo
import io.github.randomf.safedriveway.protocol.encodeCommand
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What a [Link] needs from the backend that runs it. */
internal interface LinkHost {
    val state: StateFlow<LiveState>

    fun update(change: (LiveState) -> LiveState)

    /** The link has connected and read the unit's info, status and setup. */
    fun onConnected(link: Link)
}

/**
 * The phone's connection to one unit, while the app needs the unit: it waits for the unit to
 * advertise, connects, subscribes and reads it, and connects again whenever the link drops. Its
 * state lives in the host's [LiveState]. A link is used once: [stop] ends it, and the backend
 * starts a new one when it needs the unit again.
 */
internal class Link(val id: UnitId, private val host: LinkHost, scope: CoroutineScope) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val linkScope = CoroutineScope(scope.coroutineContext + job)
    private var peripheral: Peripheral? = null
    private val answers = MutableSharedFlow<Answer>(extraBufferCapacity = 8)

    /** The app sends one command at a time and waits for its answer. */
    private val commands = Mutex()

    /** Starts once [after], the previous link to the same unit, has let it go. */
    fun start(after: Job?) {
        linkScope.launch {
            after?.join()
            run()
        }
    }

    private val connected: Peripheral? get() = peripheral?.takeIf {
        it.state.value is State.Connected
    }

    private fun set(change: (LinkState) -> LinkState) = host.update { it.withLink(id, change) }

    private fun setConnection(connection: Connection) =
        set { if (it.connection == connection) it else it.copy(connection = connection) }

    private suspend fun run() {
        var everConnected = false
        var failures = 0
        var phoneLimit = false
        while (true) {
            val sighting = host.state
                .onEach { state -> state.waiting(everConnected)?.let(::setConnection) }
                .first { it.waiting(everConnected) == null }
                .sightings.values.of(id)!!
            setConnection(
                when {
                    phoneLimit -> Connection.PhoneLimit
                    everConnected -> Connection.Reconnecting
                    else -> Connection.Connecting
                }
            )
            val p = peripheral ?: unitPeripheral(sighting.address).also {
                peripheral = it
                observe(it)
            }
            val info = connectAndRead(p)
            if (info == null) {
                failures++
                phoneLimit = connections() >= PHONE_LIMIT
                if (phoneLimit) setConnection(Connection.PhoneLimit)
                delay(if (phoneLimit) PHONE_LIMIT_RETRY else RETRY * failures.coerceAtMost(5))
                continue
            }
            if (info.protocolVersion > Protocol.VERSION) {
                setConnection(Connection.NeedsNewerApp)
                command(Command.Release)
                attempt("disconnecting") { p.disconnect() }
                awaitCancellation()
            }
            everConnected = true
            failures = 0
            phoneLimit = false
            setConnection(Connection.Connected)
            log("$id: connected, firmware ${info.firmware}")
            host.onConnected(this)
            p.state.first { it is State.Disconnected }
            log("$id: disconnected")
            // Its Status is stale from now on, and for a moment it still advertises as in use.
            host.update { state ->
                state.withLink(id) { it.copy(status = null, reading = null, recordRead = false) }
                    .released(id, clock.markNow())
            }
        }
    }

    /** What the link shows while it can't connect yet, or null when it can try. */
    private fun LiveState.waiting(everConnected: Boolean): Connection? {
        val sighting = sightings.values.of(id)
        return when {
            sighting == null -> if (everConnected) Connection.Reconnecting else Connection.Searching
            sighting.advertised.protocolVersion > Protocol.VERSION -> Connection.NeedsNewerApp
            inUseByAnother(sighting) -> Connection.InUse
            else -> null
        }
    }

    private fun connections() = host.state.value.links.values.count {
        it.connection == Connection.Connected
    }

    /** Connects and reads Unit info, Status and Setup; the unit's info, or null when it failed. */
    private suspend fun connectAndRead(p: Peripheral) = attempt("connecting") {
        val started = clock.markNow()
        withTimeoutOrNull(CONNECT_TIMEOUT) { p.connect() } ?: error("timed out")
        log("$id: link up after ${started.elapsedNow()}")
        val info = decodeUnitInfo(p.read(UNIT_INFO)) ?: error("unreadable unit info")
        log("$id: info read after ${started.elapsedNow()}")
        check(info.id == id) { "it is ${info.id}" }
        val status = decodeStatus(p.read(STATUS))
        val record = decodePassageRecord(p.read(SETUP))
        set { it.copy(info = info, status = status, record = record, recordRead = true) }
        info
    } ?: run {
        attempt("disconnecting") { p.disconnect() }
        null
    }

    /**
     * Subscribes before connecting, so that Kable enables the notifications while it connects and
     * keeps them across reconnections.
     */
    private fun observe(p: Peripheral) {
        linkScope.launch(start = CoroutineStart.UNDISPATCHED) {
            p.observe(READING).collect { bytes ->
                val packet = decodeReading(bytes) ?: return@collect
                val reading = Reading(packet.distanceMm, packet.status, clock.markNow())
                set { it.copy(reading = reading) }
            }
        }
        linkScope.launch(start = CoroutineStart.UNDISPATCHED) {
            p.observe(STATUS).collect { bytes ->
                val status = decodeStatus(bytes) ?: return@collect
                set { it.copy(status = status) }
            }
        }
        linkScope.launch(start = CoroutineStart.UNDISPATCHED) {
            p.observe(CONTROL_POINT).collect { bytes ->
                decodeAnswer(bytes)?.let { answers.emit(it) }
            }
        }
    }

    /**
     * Sends [command] and waits for its answer. Null when the unit isn't connected, the write
     * failed or no answer came within 2 s.
     */
    suspend fun command(command: Command, pin: String? = null): CommandResult? = commands.withLock {
        val p = connected ?: return@withLock null
        attempt(command.name) {
            coroutineScope {
                val answer = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(ANSWER_TIMEOUT) {
                        answers.first { it.opcode == command.opcode }
                    }
                }
                p.write(CONTROL_POINT, encodeCommand(command, pin), WriteType.WithResponse)
                answer.await()?.result
            }
        }.also { log("$id: ${command.name} -> $it") }
    }

    /**
     * Unlocks with [pin]. An Unlock that isn't answered isn't sent again, which would count a
     * wrong PIN twice: the unit's status says whether it took.
     */
    suspend fun unlock(pin: String): CommandResult? = command(Command.Unlock, pin) ?: run {
        val status = connected?.let { p ->
            attempt("reading status") { decodeStatus(p.read(STATUS)) }
        }
        if (status?.flags?.unlocked == true) CommandResult.Done else null
    }

    /** The unit's Setup characteristic, or null when it couldn't be read. */
    suspend fun readSetup(): ByteArray? = connected?.let { p ->
        attempt("reading setup") { p.read(SETUP) }
    }

    /** Writes [record] to Setup staging, as a long write when it doesn't fit one packet. */
    suspend fun stage(record: ByteArray): Boolean = connected?.let { p ->
        attempt("staging") { p.write(SETUP_STAGING, record, WriteType.WithResponse) }
    } != null

    /**
     * Ends the link: sends Release first if [release], so that the unit stops measuring at once,
     * then disconnects. Without Release, the unit keeps warning for 5 minutes, as after a lost link.
     */
    suspend fun stop(release: Boolean) = withContext(NonCancellable) {
        val p = peripheral
        val wasConnected = p?.state?.value is State.Connected
        if (release && wasConnected) command(Command.Release)
        job.cancel()
        if (p != null) {
            attempt("disconnecting") { withTimeoutOrNull(DISCONNECT_TIMEOUT) { p.disconnect() } }
            p.close()
            if (wasConnected) host.update { it.released(id, clock.markNow()) }
        }
        log("$id: let go${if (release) " after Release" else ""}")
    }

    /** Runs [block], turning a failure into null. Cancelling the link still cancels it. */
    private suspend fun <T> attempt(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        log("$id: $what failed: $e")
        null
    }

    private companion object {
        val clock = TimeSource.Monotonic
        val UNIT_INFO = characteristicOf(Protocol.SERVICE, Protocol.UNIT_INFO)
        val STATUS = characteristicOf(Protocol.SERVICE, Protocol.STATUS)
        val READING = characteristicOf(Protocol.SERVICE, Protocol.READING)
        val SETUP = characteristicOf(Protocol.SERVICE, Protocol.SETUP)
        val SETUP_STAGING = characteristicOf(Protocol.SERVICE, Protocol.SETUP_STAGING)
        val CONTROL_POINT = characteristicOf(Protocol.SERVICE, Protocol.CONTROL_POINT)

        val CONNECT_TIMEOUT = 10.seconds
        val DISCONNECT_TIMEOUT = 3.seconds
        val ANSWER_TIMEOUT = 2.seconds

        /** After a failed connection, the link waits this long times the failures in a row, up to 5. */
        val RETRY = 1.seconds

        /**
         * A connection that fails while the phone holds this many is taken for the phone's limit.
         * Android doesn't say why a connection failed, and phones differ (app/features.md#risks).
         */
        const val PHONE_LIMIT = 6
        val PHONE_LIMIT_RETRY = 10.seconds
    }
}
