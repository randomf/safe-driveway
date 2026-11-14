package io.github.randomf.safedriveway.passage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetupTest {
    private val a = UnitId("08ad0a5b9fb8")
    private val b = UnitId("08ad0a5b4c21")
    private val c = UnitId("08ad0a5b77e0")
    private val setup = PassageSetup(
        id = PassageId(1),
        name = "Garage",
        version = 3,
        warningGapMm = 150,
        criticalGapMm = 50,
        gatePositionsMm = listOf(0, 1200, 2500),
        units = listOf(
            UnitSetup(a, "", 0, Side.Left, 60, signals = true),
            UnitSetup(b, "Pillar", 1, Side.Left, 60, signals = true),
            UnitSetup(c, "", 2, Side.Right, 60, signals = true)
        )
    )

    @Test
    fun theShortIdIsTheLastFourDigits() {
        assertEquals("9FB8", a.short)
    }

    @Test
    fun forgettingAGatesOnlyUnitRemovesTheGate() {
        val without = setup.withoutUnit(b)
        assertEquals(listOf(0, 2500), without.gatePositionsMm)
        assertEquals(1, without.unit(c)?.gate)
    }

    @Test
    fun replacingKeepsThePlacement() {
        val new = UnitId("08ad0a5bc4f1")
        val replaced = setup.withReplaced(b, new)
        assertEquals(setup.unit(b)?.copy(id = new), replaced.unit(new))
    }

    @Test
    fun namesAreLimitedInBytes() {
        assertEquals(7, "Garáž".utf8Size())
    }

    @Test
    fun aPinIsFourToEightDigits() {
        assertTrue(isValidPin("1234"))
        assertTrue(isValidPin("12345678"))
        assertFalse(isValidPin("123"))
        assertFalse(isValidPin("123456789"))
        assertFalse(isValidPin("12a4"))
    }

    @Test
    fun noticesPutTheMostUrgentFirst() {
        val units = mapOf(
            a to UnitLive(Connection.Connected, batteryPercent = 12),
            b to UnitLive(Connection.Reconnecting),
            c to UnitLive(Connection.Connected, sensorFault = true)
        )
        assertEquals(
            listOf(NoticeKind.Lost, NoticeKind.SensorFault, NoticeKind.LowBattery),
            notices(setup, units).map { it.kind }
        )
    }
}
