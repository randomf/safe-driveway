package io.github.randomf.safedriveway.protocol

import io.github.randomf.safedriveway.passage.PassageId
import io.github.randomf.safedriveway.passage.PassageSetup
import io.github.randomf.safedriveway.passage.ReadingStatus
import io.github.randomf.safedriveway.passage.Side
import io.github.randomf.safedriveway.passage.UnitId
import io.github.randomf.safedriveway.passage.UnitSetup
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The examples in doc/ble-protocol.md, which the firmware's tests decode too. */
class ProtocolTest {
    private fun bytes(hex: String) = hexBytes(hex.filterNot { it.isWhitespace() })

    private val garage = PassageSetup(
        id = PassageId(0x1a2b3c4d),
        name = "Garáž",
        version = 3,
        warningGapMm = 150,
        criticalGapMm = 50,
        gatePositionsMm = listOf(1200),
        units = listOf(
            UnitSetup(UnitId("08ad0a5b9fb8"), "Pillar", 0, Side.Left, 60, signals = true),
            UnitSetup(UnitId("08ad0a5b1234"), "", 0, Side.Right, 55, signals = true)
        )
    )

    private val garageRecord = bytes(
        """
        01 4d 3c 2b 1a 03 00 00 00 96 00 32 00 01 02
        07 47 61 72 c3 a1 c5 be
        b0 04
        10 08 ad 0a 5b 9f b8 08 3c 00 06 50 69 6c 6c 61 72
        0a 08 ad 0a 5b 12 34 0c 37 00 00
        """
    )

    @Test
    fun theCharacteristicsShareTheServiceUuid() {
        assertEquals("9f050007-a55b-4698-ae01-042514884edf", Protocol.CONTROL_POINT.toString())
    }

    @Test
    fun decodesTheAdvertisement() {
        // The manufacturer data after the company ID.
        val advertised = decodeAdvertised(bytes("15 00 4d 3c 2b 1a"))
        assertEquals(
            Advertised(1, gate = 1, side = Side.Right, UnitFlags(), PassageId(0x1a2b3c4d)),
            advertised
        )
        val connected = decodeAdvertised(bytes("15 03 4d 3c 2b 1a"))
        assertEquals(UnitFlags(inUse = true, unlocked = true), connected?.flags)
    }

    @Test
    fun anUnassignedUnitAdvertisesNoPassage() {
        assertNull(decodeAdvertised(bytes("10 04 00 00 00 00"))?.passage)
        assertEquals(true, decodeAdvertised(bytes("10 04 00 00 00 00"))?.flags?.pinResetWindow)
    }

    @Test
    fun decodesUnitInfo() {
        assertEquals(
            UnitInfo(1, UnitId("08ad0a5b9fb8"), "0.1.0", batteryDivider = true),
            decodeUnitInfo(bytes("01 08 ad 0a 5b 9f b8 00 01 00 01"))
        )
    }

    @Test
    fun decodesStatus() {
        assertEquals(StatusPacket(UnitFlags(inUse = true), 76), decodeStatus(bytes("01 4c")))
        assertNull(decodeStatus(bytes("01 ff"))?.batteryPercent)
    }

    @Test
    fun decodesReadings() {
        assertEquals(
            ReadingPacket(42, 187, ReadingStatus.Valid),
            decodeReading(bytes("2a bb 00 00"))
        )
        assertEquals(
            ReadingPacket(43, 31, ReadingStatus.TooClose),
            decodeReading(bytes("2b 1f 00 01"))
        )
        assertEquals(ReadingStatus.Unreliable, decodeReading(bytes("2c 00 00 09"))?.status)
    }

    @Test
    fun encodesCommandsAndDecodesAnswers() {
        assertContentEquals(bytes("01 31 32 33 34"), encodeCommand(Command.Unlock, "1234"))
        assertContentEquals(bytes("06"), encodeCommand(Command.Lock))
        assertEquals(Answer(0x01, CommandResult.WrongPin), decodeAnswer(bytes("01 04")))
        assertEquals(Answer(0x06, CommandResult.Done), decodeAnswer(bytes("06 00")))
        assertEquals(CommandResult.Unknown, decodeAnswer(bytes("03 42"))?.result)
    }

    @Test
    fun encodesThePassageRecord() {
        assertContentEquals(garageRecord, encodePassageRecord(garage))
    }

    @Test
    fun decodesThePassageRecord() {
        assertEquals(garage, decodePassageRecord(garageRecord))
    }

    @Test
    fun allowsBytesAfterTheKnownFields() {
        assertEquals(garage, decodePassageRecord(garageRecord + bytes("ff ff")))
    }

    @Test
    fun skipsFieldsAddedToAnEntry() {
        val longer = encodePassageRecord(garage.copy(units = garage.units.take(1)))
            .let { it.copyOf(14) + bytes("01") + it.copyOfRange(15, 25) } +
            bytes("11 08 ad 0a 5b 9f b8 08 3c 00 06 50 69 6c 6c 61 72 ee")
        assertEquals(garage.units.first(), decodePassageRecord(longer)?.units?.single())
    }

    @Test
    fun anUnassignedUnitHoldsNoRecord() {
        assertNull(decodePassageRecord(ByteArray(0)))
    }

    @Test
    fun rejectsAnInvalidRecord() {
        assertNull(decodePassageRecord(garageRecord.copyOf(garageRecord.size - 1)))
        assertNull(decodePassageRecord(encodePassageRecord(garage.copy(criticalGapMm = 150))))
        assertNull(decodePassageRecord(encodePassageRecord(garage.copy(id = PassageId(0)))))
        val sameSide = garage.copy(units = garage.units.map { it.copy(side = Side.Left) })
        assertNull(decodePassageRecord(encodePassageRecord(sameSide)))
    }

    @Test
    fun theUnitIdIsTheBluetoothAddressMinusTwo() {
        assertEquals(UnitId("08ad0a5b9fb8"), unitIdOfAddress("08:AD:0A:5B:9F:BA"))
        assertEquals(UnitId("08ad0a5b9eff"), unitIdOfAddress("08:AD:0A:5B:9F:01"))
        assertNull(unitIdOfAddress("5C9B2E0A-0000-0000-0000-000000000000"))
    }
}
