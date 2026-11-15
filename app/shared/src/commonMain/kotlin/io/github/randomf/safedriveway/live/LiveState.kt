package io.github.randomf.safedriveway.live

import io.github.randomf.safedriveway.backend.OpenPassage
import io.github.randomf.safedriveway.backend.RememberedPassage
import io.github.randomf.safedriveway.backend.Stored
import io.github.randomf.safedriveway.passage.Connection
import io.github.randomf.safedriveway.passage.PassageId
import io.github.randomf.safedriveway.passage.PassageSetup
import io.github.randomf.safedriveway.passage.Reading
import io.github.randomf.safedriveway.passage.UnitId
import io.github.randomf.safedriveway.passage.UnitLive
import io.github.randomf.safedriveway.protocol.StatusPacket
import io.github.randomf.safedriveway.protocol.UnitInfo
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.minutes

/** What the phone knows of its connection to one unit. */
data class LinkState(
    val connection: Connection = Connection.Searching,
    val info: UnitInfo? = null,
    val status: StatusPacket? = null,
    val reading: Reading? = null,
    /** Whether the Setup characteristic has been read since the unit connected. */
    val recordRead: Boolean = false,
    /** The passage record the unit holds; null on an unassigned unit. */
    val record: PassageSetup? = null
)

/** The passage the driver opened. */
data class OpenState(
    val id: PassageId,
    /** The newest setup the phone has seen, or null until it has read one. */
    val setup: PassageSetup? = null,
    /** The units the driver disconnected. */
    val disconnected: Set<UnitId> = emptySet(),
    /** Units that carry the passage's ID but aren't in its newest setup: forgotten or replaced. */
    val ignored: Set<UnitId> = emptySet(),
    val paused: Boolean = false,
    /** The PIN the units were unlocked with, while their setup is being changed. */
    val unlockedWith: String? = null,
    /** When the passage was opened or reconnected, or a unit last had a gap. */
    val activeAt: ComparableTimeMark,
    /** When a unit last had a gap: the car was in front of it. */
    val carSeenAt: ComparableTimeMark? = null
) {
    /** No car for this long pauses the passage (app/features.md). */
    fun idle(now: ComparableTimeMark): Boolean = now - activeAt > PAUSE_AFTER

    companion object {
        val PAUSE_AFTER = 5.minutes
    }
}

/** Everything the live backend knows, in one value that only it changes. */
data class LiveState(
    val stored: Stored = Stored(),
    /** By the units' addresses. */
    val sightings: Map<String, Sighting> = emptyMap(),
    val links: Map<UnitId, LinkState> = emptyMap(),
    val open: OpenState? = null,
    /** Units whose readings a setup screen shows. */
    val setupReadingUnits: List<UnitId> = emptyList(),
    /** Units that a write, an Identify or a PIN reset needs connected, once for each. */
    val busy: List<UnitId> = emptyList(),
    /** The app is in the foreground with Bluetooth ready, and not in demo mode. */
    val active: Boolean = false
) {
    fun link(id: UnitId): LinkState? = links[id]

    fun connected(id: UnitId): Boolean = links[id]?.connection == Connection.Connected

    fun withLink(id: UnitId, change: (LinkState) -> LinkState): LiveState =
        links[id]?.let { copy(links = links + (id to change(it))) } ?: this

    fun withOpen(change: (OpenState) -> OpenState): LiveState = copy(open = open?.let(change))
}

/**
 * The units the phone should be connected to: the open passage's, those that advertise its ID
 * and may hold a newer setup, and those a setup screen, a write, an Identify or a PIN reset needs.
 */
fun LiveState.wantedLinks(): Set<UnitId> {
    if (!active) return emptySet()
    val passage = open?.takeUnless { it.paused }?.let { open ->
        val listed = open.setup?.units?.map { it.id }.orEmpty()
        val advertising = sightings.values.ofPassage(open.id).mapNotNull { it.unit }
        (listed + advertising).toSet() - open.disconnected - open.ignored
    }.orEmpty()
    return passage + setupReadingUnits + busy
}

/** What the screens see of the open passage, once the phone knows its setup. */
fun LiveState.openPassage(): OpenPassage? {
    val open = open ?: return null
    val setup = open.setup ?: return null
    return OpenPassage(
        setup,
        setup.units.associate { unit ->
            unit.id to unitLive(
                links[unit.id],
                sightings.values.of(unit.id),
                setup,
                disconnected = unit.id in open.disconnected || open.paused
            )
        },
        paused = open.paused,
        unlocked = open.unlockedWith != null
    )
}

fun LiveState.remembered(): List<RememberedPassage> =
    stored.passages.map { RememberedPassage(it.setup, it.preferences, knowsPin = it.pin != null) }

/** One unit as the screens see it. Battery, sensor and setup are known only while connected. */
fun unitLive(
    link: LinkState?,
    sighting: Sighting?,
    newest: PassageSetup,
    disconnected: Boolean
): UnitLive {
    val connection = if (disconnected) Connection.Disconnected else link?.connection
    val connected = connection == Connection.Connected
    return UnitLive(
        connection = connection ?: Connection.Searching,
        batteryPercent = link?.status?.batteryPercent?.takeIf { connected },
        sensorFault = connected && link?.status?.flags?.sensorFault == true,
        outOfDate = connected && link?.isOutOfDate(newest) == true,
        firmware = link?.info?.firmware,
        rssi = sighting?.rssi,
        reading = link?.reading.takeIf { connected }
    )
}

/**
 * The unit holds an older record of the passage than [newest], or none at all. A unit that holds
 * another passage's record isn't this passage's to update.
 */
fun LinkState.isOutOfDate(newest: PassageSetup): Boolean = recordRead &&
    (record == null || (record.id == newest.id && record.version < newest.version))
