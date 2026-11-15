package io.github.randomf.safedriveway.protocol

import io.github.randomf.safedriveway.passage.Limits
import io.github.randomf.safedriveway.passage.PassageId
import io.github.randomf.safedriveway.passage.PassageSetup
import io.github.randomf.safedriveway.passage.Side
import io.github.randomf.safedriveway.passage.UnitId
import io.github.randomf.safedriveway.passage.UnitSetup

/** The passage record's format (doc/ble-protocol.md#passage-record). */
private const val RECORD_FORMAT = 1

/** The fields of a unit entry after its length byte, without the name. */
private const val ENTRY_FIXED = 10

/** The setup as the passage record that every unit of the passage stores. */
fun encodePassageRecord(setup: PassageSetup): ByteArray = u8Bytes(RECORD_FORMAT) +
    u32Bytes(setup.id.value) +
    u32Bytes(setup.version) +
    u16Bytes(setup.warningGapMm) +
    u16Bytes(setup.criticalGapMm) +
    u8Bytes(setup.gateCount) +
    u8Bytes(setup.units.size) +
    textBytes(setup.name) +
    setup.gatePositionsMm.fold(ByteArray(0)) { bytes, mm -> bytes + u16Bytes(mm) } +
    setup.units.fold(ByteArray(0)) { bytes, unit -> bytes + encodeEntry(unit) }

private fun encodeEntry(unit: UnitSetup): ByteArray {
    val placement = unit.gate or
        (if (unit.side == Side.Right) 0x04 else 0) or
        (if (unit.signals) 0x08 else 0)
    val fields = hexBytes(unit.id.hex) +
        u8Bytes(placement) +
        u16Bytes(unit.zeroOffsetMm) +
        textBytes(unit.name)
    return u8Bytes(fields.size) + fields
}

/**
 * The setup in a passage record, or null when the record isn't valid: no bytes, as an unassigned
 * unit holds, or a record that breaks the protocol's rules. Bytes after the known fields of the
 * record and of each entry are allowed.
 */
fun decodePassageRecord(bytes: ByteArray): PassageSetup? {
    if (bytes.size < 16 || bytes.u8(0) != RECORD_FORMAT) return null
    val gateCount = bytes.u8(13)
    val unitCount = bytes.u8(14)
    val nameLength = bytes.u8(15)
    if (gateCount !in 1..Limits.MAX_GATES || unitCount !in 1..Limits.MAX_UNITS) return null
    if (nameLength !in 1..Limits.PASSAGE_NAME_BYTES) return null
    val gatesAt = 16 + nameLength
    val entriesAt = gatesAt + 2 * gateCount
    if (entriesAt > bytes.size) return null
    val name = bytes.text(16, nameLength) ?: return null
    val gates = (0 until gateCount).map { bytes.u16(gatesAt + 2 * it) }

    val (units, _) = bytes.items(unitCount, entriesAt) { decodeEntry(bytes, it) } ?: return null

    val setup = PassageSetup(
        id = PassageId(bytes.u32(1)),
        name = name,
        version = bytes.u32(5),
        warningGapMm = bytes.u16(9),
        criticalGapMm = bytes.u16(11),
        gatePositionsMm = gates,
        units = units
    )
    return setup.takeIf { it.isValidRecord() }
}

/** The entry at [at], and where the next one starts. */
private fun decodeEntry(bytes: ByteArray, at: Int): Pair<UnitSetup, Int>? {
    if (at >= bytes.size) return null
    val length = bytes.u8(at)
    val next = at + 1 + length
    if (length < ENTRY_FIXED || next > bytes.size) return null
    val placement = bytes.u8(at + 7)
    val nameLength = bytes.u8(at + 10)
    if (nameLength > Limits.UNIT_NAME_BYTES || ENTRY_FIXED + nameLength > length) return null
    val name = bytes.text(at + 11, nameLength) ?: return null
    return UnitSetup(
        id = UnitId(bytes.hex(at + 1, at + 7)),
        name = name,
        gate = placement and 0x03,
        side = if (placement and 0x04 != 0) Side.Right else Side.Left,
        zeroOffsetMm = bytes.u16(at + 8),
        signals = placement and 0x08 != 0
    ) to next
}

private fun PassageSetup.isValidRecord(): Boolean = id.value != 0L &&
    criticalGapMm < warningGapMm &&
    gatePositionsMm.zipWithNext().all { (a, b) -> a < b } &&
    units.all { it.gate < gateCount } &&
    units.distinctBy { it.id }.size == units.size &&
    units.distinctBy { it.gate to it.side }.size == units.size
