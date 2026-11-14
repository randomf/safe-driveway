package io.github.randomf.safedriveway.passage

import kotlin.jvm.JvmInline

/** A passage's ID, chosen at random when the passage is created. Never 0 (doc/ble-protocol.md). */
@JvmInline
value class PassageId(val value: Long)

/** A unit's ID: the ESP32's factory MAC address, as 12 hex digits. */
@JvmInline
value class UnitId(val hex: String) {
    /** The last 4 digits in upper case, as in "Safe Driveway 9FB8". */
    val short: String get() = hex.takeLast(4).uppercase()
}

/**
 * A wall of the passage, as seen standing at end 0 and looking along it. End 0 is the end the
 * driver usually drives in from, so this is also the driver's side when they drive in forwards.
 */
enum class Side { Left, Right }

/** One unit's entry in the passage's setup. */
data class UnitSetup(
    val id: UnitId,
    /** Empty: the app names the unit by its gate and side. */
    val name: String,
    /** 0 is the gate nearest end 0. */
    val gate: Int,
    val side: Side,
    val zeroOffsetMm: Int,
    /** Whether the unit's own buzzer and red LED signal the gap. */
    val signals: Boolean
)

/** The setup that every unit of a passage stores, as in the protocol's passage record. */
data class PassageSetup(
    val id: PassageId,
    val name: String,
    val version: Long,
    val warningGapMm: Int,
    val criticalGapMm: Int,
    /** In mm from end 0, increasing. The app puts the first gate at 0. */
    val gatePositionsMm: List<Int>,
    val units: List<UnitSetup>
) {
    val gateCount: Int get() = gatePositionsMm.size

    fun unit(id: UnitId): UnitSetup? = units.find { it.id == id }

    fun unitAt(gate: Int, side: Side): UnitSetup? =
        units.find { it.gate == gate && it.side == side }

    /**
     * The setup without [id]. A gate left with no unit goes too, and the gates after it move
     * down by one.
     */
    fun withoutUnit(id: UnitId): PassageSetup {
        val removed = unit(id) ?: return this
        val rest = copy(units = units - removed)
        return if (rest.units.any {
                it.gate == removed.gate
            }
        ) {
            rest
        } else {
            rest.withoutGate(removed.gate)
        }
    }

    /**
     * The setup without [gate], which has to be empty. The gates after it move down by one, and the
     * first gate stays at 0.
     */
    fun withoutGate(gate: Int): PassageSetup {
        val positions = gatePositionsMm.filterIndexed { i, _ -> i != gate }
        return copy(
            gatePositionsMm = positions.map { it - positions.first() },
            units = units.map { if (it.gate > gate) it.copy(gate = it.gate - 1) else it }
        )
    }

    /** The setup with [new] in the place of [old], with the old unit's name and placement. */
    fun withReplaced(old: UnitId, new: UnitId): PassageSetup =
        copy(units = units.map { if (it.id == old) it.copy(id = new) else it })
}

/** The limits of the protocol's passage record and of the PIN. */
object Limits {
    const val MAX_GATES = 4
    const val MAX_UNITS = 8
    const val PASSAGE_NAME_BYTES = 29
    const val UNIT_NAME_BYTES = 16
    const val PIN_MIN_DIGITS = 4
    const val PIN_MAX_DIGITS = 8
    const val DEFAULT_WARNING_GAP_MM = 150
    const val DEFAULT_CRITICAL_GAP_MM = 50

    /** Thresholds are set in 0.5 cm steps. */
    const val THRESHOLD_STEP_MM = 5
}

/** Names in the passage record are limited in UTF-8 bytes, not in characters. */
fun String.utf8Size(): Int = encodeToByteArray().size

fun isValidPin(pin: String): Boolean =
    pin.length in Limits.PIN_MIN_DIGITS..Limits.PIN_MAX_DIGITS && pin.all { it in '0'..'9' }
