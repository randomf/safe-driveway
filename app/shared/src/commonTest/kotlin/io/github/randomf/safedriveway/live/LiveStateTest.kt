package io.github.randomf.safedriveway.live

import io.github.randomf.safedriveway.passage.Connection
import io.github.randomf.safedriveway.passage.PassageId
import io.github.randomf.safedriveway.passage.PassageSetup
import io.github.randomf.safedriveway.passage.Side
import io.github.randomf.safedriveway.passage.UnitId
import io.github.randomf.safedriveway.passage.UnitSetup
import io.github.randomf.safedriveway.protocol.Advertised
import io.github.randomf.safedriveway.protocol.StatusPacket
import io.github.randomf.safedriveway.protocol.UnitFlags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class LiveStateTest {
    private val clock = TestTimeSource()
    private val left = UnitId("08ad0a5b9fb8")
    private val right = UnitId("08ad0a5b1234")
    private val spare = UnitId("08ad0a5bc4f1")
    private val stranger = UnitId("08ad0a5b7777")
    private val garage = PassageSetup(
        id = PassageId(7),
        name = "Garage",
        version = 3,
        warningGapMm = 150,
        criticalGapMm = 50,
        gatePositionsMm = listOf(0),
        units = listOf(
            UnitSetup(left, "", 0, Side.Left, 0, signals = true),
            UnitSetup(right, "", 0, Side.Right, 0, signals = true)
        )
    )

    private fun sighting(unit: UnitId, passage: PassageId?, name: String?, inUse: Boolean = false) =
        Sighting(
            address = unit.hex,
            unit = unit,
            advertised = Advertised(1, 0, Side.Left, UnitFlags(inUse = inUse), passage),
            name = name,
            rssi = -60,
            at = clock.markNow()
        )

    @Test
    fun groupsTheUnitsInRangeByPassage() {
        val nearby = nearbyOf(
            listOf(
                sighting(left, garage.id, "Garage"),
                sighting(right, garage.id, null),
                sighting(spare, null, "Safe Driveway C4F1")
            ),
            inUseByAnother = { it.advertised.flags.inUse },
            settled = true
        )
        assertEquals(listOf("Garage"), nearby.passages.map { it.name })
        assertFalse(nearby.passages.single().inUse)
        assertEquals(listOf(spare), nearby.unassigned.map { it.id })
    }

    @Test
    fun aPassageShowsOnceItsNameIsHeard() {
        val nearby = nearbyOf(listOf(sighting(left, garage.id, null)), { false }, settled = true)
        assertTrue(nearby.passages.isEmpty())
    }

    @Test
    fun onlyAnotherPhoneMakesAPassageInUse() {
        val sighting = sighting(left, garage.id, "Garage", inUse = true)
        val state = LiveState(sightings = mapOf(sighting.address to sighting))
        assertTrue(nearbyOf(listOf(sighting), state::inUseByAnother, true).passages.single().inUse)
        val connected = state.copy(links = mapOf(left to LinkState(Connection.Connected)))
        assertFalse(
            nearbyOf(listOf(sighting), connected::inUseByAnother, true).passages.single().inUse
        )
    }

    @Test
    fun aUnitThisPhoneJustHeldStillAdvertisesAsInUseByIt() {
        val released = LiveState().released(left, clock.markNow())
        clock += 2.seconds
        assertFalse(released.inUseByAnother(sighting(left, garage.id, "Garage", inUse = true)))
        clock += Sighting.HELD_FOR
        assertTrue(released.inUseByAnother(sighting(left, garage.id, "Garage", inUse = true)))
        assertFalse(released.inUseByAnother(sighting(left, garage.id, "Garage", inUse = false)))
    }

    @Test
    fun connectsToTheOpenPassageAndToUnitsThatAdvertiseIt() {
        val state = LiveState(
            sightings = listOf(sighting(stranger, garage.id, "Garage")).associateBy { it.address },
            open = OpenState(garage.id, garage, activeAt = clock.markNow()),
            setupReadingUnits = listOf(spare),
            active = true
        )
        assertEquals(setOf(left, right, stranger, spare), state.wantedLinks())
        assertEquals(
            setOf(left, spare),
            state.withOpen { it.copy(disconnected = setOf(right), ignored = setOf(stranger)) }
                .wantedLinks()
        )
        assertEquals(setOf(spare), state.withOpen { it.copy(paused = true) }.wantedLinks())
        assertEquals(emptySet(), state.copy(active = false).wantedLinks())
    }

    @Test
    fun aUnitIsKnownOnlyWhileConnected() {
        val link = LinkState(
            Connection.Connected,
            status = StatusPacket(UnitFlags(sensorFault = true), 76),
            recordRead = true,
            record = garage.copy(version = 2)
        )
        val live = unitLive(link.copy(rssi = -61), garage, disconnected = false)
        assertEquals(76, live.batteryPercent)
        assertTrue(live.sensorFault)
        assertTrue(live.outOfDate)
        assertEquals(-61, live.rssi)
        val lost = unitLive(link.copy(connection = Connection.Reconnecting), garage, false)
        assertNull(lost.batteryPercent)
        assertFalse(lost.outOfDate)
        assertEquals(
            Connection.Disconnected,
            unitLive(link, garage, disconnected = true).connection
        )
        assertEquals(Connection.Searching, unitLive(null, garage, false).connection)
    }

    @Test
    fun anotherPassagesRecordIsntOutOfDate() {
        val other = garage.copy(id = PassageId(8), version = 1)
        assertFalse(LinkState(recordRead = true, record = other).isOutOfDate(garage))
        assertTrue(LinkState(recordRead = true, record = null).isOutOfDate(garage))
        assertFalse(LinkState(recordRead = false, record = null).isOutOfDate(garage))
    }
}
