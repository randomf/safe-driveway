package io.github.randomf.safedriveway.demo

import io.github.randomf.safedriveway.passage.Limits
import io.github.randomf.safedriveway.passage.PassageId
import io.github.randomf.safedriveway.passage.PassageSetup
import io.github.randomf.safedriveway.passage.Side
import io.github.randomf.safedriveway.passage.UnitId
import io.github.randomf.safedriveway.passage.UnitSetup

/** The passages and units the demo pretends exist. */
internal object DemoWorld {
    val gate1Left = UnitId("08ad0a5b9fb8")
    val gate1Right = UnitId("08ad0a5b4c21")
    val pillar = UnitId("08ad0a5b77e0")
    val bins = UnitId("08ad0a5b1d03")
    val frontLeft = UnitId("08ad0a5b3e5a")
    val frontRight = UnitId("08ad0a5b6b90")

    /** Units that belong to no passage yet, with their signal strength. */
    val spare = listOf(
        UnitId("08ad0a5bc4f1") to -58,
        UnitId("08ad0a5b2a7d") to -71,
        UnitId("08ad0a5b5e19") to -80
    )

    val garage = PassageSetup(
        id = PassageId(0x5A170001),
        name = "Garage",
        version = 3,
        warningGapMm = Limits.DEFAULT_WARNING_GAP_MM,
        criticalGapMm = Limits.DEFAULT_CRITICAL_GAP_MM,
        gatePositionsMm = listOf(0, 2500),
        units = listOf(
            UnitSetup(gate1Left, "", 0, Side.Left, 65, signals = true),
            UnitSetup(gate1Right, "", 0, Side.Right, 70, signals = true),
            UnitSetup(pillar, "Pillar", 1, Side.Left, 120, signals = false),
            UnitSetup(bins, "Bins", 1, Side.Right, 80, signals = true)
        )
    )

    val frontGate = PassageSetup(
        id = PassageId(0x5A170002),
        name = "Front gate",
        version = 1,
        warningGapMm = 200,
        criticalGapMm = 80,
        gatePositionsMm = listOf(0),
        units = listOf(
            UnitSetup(frontLeft, "", 0, Side.Left, 60, signals = true),
            UnitSetup(frontRight, "", 0, Side.Right, 60, signals = true)
        )
    )

    /** Half the passage's width at each gate, from line to line, in mm. */
    fun halfWidthsMm(setup: PassageSetup): List<Int> = when (setup.id) {
        garage.id -> listOf(1130, 1110)
        frontGate.id -> listOf(1250)
        else -> setup.gatePositionsMm.map { 1150 }
    }

    /** Units with the battery divider, and their charge. */
    val battery = mapOf(
        gate1Left to 87,
        gate1Right to 64,
        bins to 91,
        frontLeft to 72,
        frontRight to 70
    )

    const val LOW_BATTERY = 12

    /**
     * What a unit reads with a board held on the line: its true zero offset. For units with a
     * setup it's the offset they have; for new ones it's made up from their ID.
     */
    fun boardDistanceMm(unit: UnitId, setups: List<PassageSetup>): Int =
        setups.firstNotNullOfOrNull { it.unit(unit) }?.zeroOffsetMm
            ?: (45 + unit.hex.takeLast(2).toInt(16) % 60)
}
