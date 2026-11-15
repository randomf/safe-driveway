package io.github.randomf.safedriveway.backend

import io.github.randomf.safedriveway.passage.Direction
import io.github.randomf.safedriveway.passage.PassageId
import io.github.randomf.safedriveway.passage.PassageSetup
import io.github.randomf.safedriveway.passage.Side
import io.github.randomf.safedriveway.passage.UnitId
import io.github.randomf.safedriveway.passage.UnitSetup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StoredTest {
    private fun setup(id: Long, name: String) = PassageSetup(
        id = PassageId(id),
        name = name,
        version = 4,
        warningGapMm = 150,
        criticalGapMm = 50,
        gatePositionsMm = listOf(0, 1200),
        units = listOf(
            UnitSetup(UnitId("08ad0a5b9fb8"), "Pillar", 0, Side.Left, 60, signals = true),
            UnitSetup(UnitId("08ad0a5b1234"), "", 1, Side.Right, 55, signals = false)
        )
    )

    private val garage = setup(0x1a2b3c4d, "Garáž")
    private val gate = setup(0x0badcafe, "Front gate")

    private val stored = Stored(
        passages = listOf(
            StoredPassage(
                garage,
                DriverPreferences(Direction.Reversing, false, 0.35f),
                pin = "5678",
                previousPin = "1234"
            ),
            StoredPassage(gate)
        ),
        choices = mapOf(setOf(garage.id, gate.id) to gate.id)
    )

    @Test
    fun readsBackWhatItWrote() {
        assertEquals(stored, decodeStored(encodeStored(stored)))
        assertEquals(Stored(), decodeStored(encodeStored(Stored())))
    }

    @Test
    fun startsAfreshFromBytesItCantRead() {
        assertNull(decodeStored(ByteArray(0)))
        assertNull(decodeStored(byteArrayOf(9)))
        val bytes = encodeStored(stored)
        assertNull(decodeStored(bytes.copyOf(bytes.size - 3)))
    }

    @Test
    fun keepsTheOtherPassagesWhenOnesRecordIsntValid() {
        // A setup that no record can hold: the critical gap isn't below the warning gap.
        val broken = setup(9, "Broken").copy(criticalGapMm = 150)
        val bytes = encodeStored(stored.copy(passages = stored.passages + StoredPassage(broken)))
        assertEquals(stored, decodeStored(bytes))
    }

    @Test
    fun addsAPassageTheFirstTimeItIsUsed() {
        val third = setup(7, "Barn")
        val added = stored.withPassage(third.id, third) { it.copy(pin = "5678") }
        assertEquals(StoredPassage(third, pin = "5678"), added.passages.last())
        assertEquals(stored, stored.withPassage(third.id) { it.copy(pin = "5678") })
    }

    @Test
    fun forgettingAPassageForgetsItsChoices() {
        val without = stored.withoutPassage(gate.id)
        assertEquals(listOf(garage.id), without.passages.map { it.setup.id })
        assertEquals(emptyMap(), without.choices)
    }
}
