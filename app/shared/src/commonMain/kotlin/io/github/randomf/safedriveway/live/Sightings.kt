package io.github.randomf.safedriveway.live

import io.github.randomf.safedriveway.backend.Nearby
import io.github.randomf.safedriveway.backend.NearbyPassage
import io.github.randomf.safedriveway.backend.NearbyUnit
import io.github.randomf.safedriveway.passage.PassageId
import io.github.randomf.safedriveway.passage.UnitId
import io.github.randomf.safedriveway.protocol.Advertised
import io.github.randomf.safedriveway.protocol.Protocol
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.seconds

/** A unit as the phone last heard it advertise. */
data class Sighting(
    /** The platform's identifier for it: its Bluetooth address on Android. */
    val address: String,
    /** Null where the address doesn't tell, see [io.github.randomf.safedriveway.protocol.unitIdOfAddress]. */
    val unit: UnitId?,
    val advertised: Advertised,
    /** The scan response's name: the passage's, or "Safe Driveway 9FB8" on an unassigned unit. */
    val name: String?,
    val rssi: Int,
    val at: ComparableTimeMark
) {
    fun isFresh(now: ComparableTimeMark): Boolean = now - at < LASTS

    companion object {
        /**
         * A unit advertises every 100 ms, and once a second while a phone is connected. One not
         * heard for this long has gone out of range.
         */
        val LASTS = 4.seconds
    }
}

/**
 * What the screens see of the units in range. A unit this phone is connected to, or has just let
 * go of, advertises as in use, so these [ours] don't make their passage in use by another phone.
 */
fun nearbyOf(sightings: Collection<Sighting>, ours: Set<UnitId>, settled: Boolean): Nearby {
    val passages = sightings.filter { it.advertised.passage != null }
        .groupBy { it.advertised.passage!! }
        .mapNotNull { (id, units) ->
            // The name comes in the scan response, which may follow the advertisement.
            val name = units.firstNotNullOfOrNull { it.name } ?: return@mapNotNull null
            NearbyPassage(
                id,
                name,
                inUse = units.any { it.advertised.flags.inUse && it.unit !in ours }
            )
        }
        .sortedBy { it.name }
    val unassigned = sightings.filter {
        it.advertised.passage == null &&
            it.unit != null &&
            it.advertised.protocolVersion <= Protocol.VERSION
    }.map { NearbyUnit(it.unit!!, it.rssi) }.sortedByDescending { it.rssi }
    return Nearby(settled, passages, unassigned)
}

/** The sighting of [unit], if it is in range. */
fun Collection<Sighting>.of(unit: UnitId): Sighting? = find { it.unit == unit }

/** The units in range that advertise [passage]. */
fun Collection<Sighting>.ofPassage(passage: PassageId): List<Sighting> =
    filter { it.advertised.passage == passage }
