package io.github.randomf.safedriveway.protocol

import io.github.randomf.safedriveway.passage.PassageId
import io.github.randomf.safedriveway.passage.ReadingStatus
import io.github.randomf.safedriveway.passage.Side
import io.github.randomf.safedriveway.passage.UnitId
import kotlin.uuid.Uuid

/** The values a unit and the app exchange over BLE, as in doc/ble-protocol.md. */
object Protocol {
    /** The protocol version this app speaks. */
    const val VERSION = 1

    /** The company ID in the advertisement's manufacturer data. */
    const val COMPANY_ID = 0xFFFF

    val SERVICE: Uuid = Uuid.parse("9f050001-a55b-4698-ae01-042514884edf")
    val UNIT_INFO: Uuid = characteristic(0x0002)
    val STATUS: Uuid = characteristic(0x0003)
    val READING: Uuid = characteristic(0x0004)
    val SETUP: Uuid = characteristic(0x0005)
    val SETUP_STAGING: Uuid = characteristic(0x0006)
    val CONTROL_POINT: Uuid = characteristic(0x0007)

    /** A characteristic's UUID: the service's, with the last 4 hex digits of its first group changed. */
    private fun characteristic(number: Int): Uuid =
        Uuid.parse("9f05" + number.toString(16).padStart(4, '0') + "-a55b-4698-ae01-042514884edf")
}

/** The flags byte of the advertisement and the Status characteristic. */
data class UnitFlags(
    val inUse: Boolean = false,
    val unlocked: Boolean = false,
    val pinResetWindow: Boolean = false,
    val lockedOut: Boolean = false,
    val lowBattery: Boolean = false,
    val sensorFault: Boolean = false
) {
    companion object {
        fun of(byte: Int) = UnitFlags(
            inUse = byte and 0x01 != 0,
            unlocked = byte and 0x02 != 0,
            pinResetWindow = byte and 0x04 != 0,
            lockedOut = byte and 0x08 != 0,
            lowBattery = byte and 0x10 != 0,
            sensorFault = byte and 0x20 != 0
        )
    }
}

/** What a unit says in its advertisement's manufacturer data. */
data class Advertised(
    val protocolVersion: Int,
    val gate: Int,
    val side: Side,
    val flags: UnitFlags,
    /** Null on an unassigned unit. */
    val passage: PassageId?
)

/** The manufacturer data's payload, after the company ID, or null when it is too short. */
fun decodeAdvertised(payload: ByteArray): Advertised? {
    if (payload.size < 6) return null
    val info = payload.u8(0)
    val passage = payload.u32(2)
    return Advertised(
        protocolVersion = info shr 4,
        gate = info and 0x03,
        side = if (info and 0x04 != 0) Side.Right else Side.Left,
        flags = UnitFlags.of(payload.u8(1)),
        passage = if (passage == 0L) null else PassageId(passage)
    )
}

/** The Unit info characteristic. */
data class UnitInfo(
    val protocolVersion: Int,
    val id: UnitId,
    /** Such as "0.1.0". */
    val firmware: String,
    val batteryDivider: Boolean
)

fun decodeUnitInfo(bytes: ByteArray): UnitInfo? {
    if (bytes.size < 11) return null
    return UnitInfo(
        protocolVersion = bytes.u8(0),
        id = UnitId(bytes.hex(1, 7)),
        firmware = "${bytes.u8(7)}.${bytes.u8(8)}.${bytes.u8(9)}",
        batteryDivider = bytes.u8(10) and 0x01 != 0
    )
}

/** The Status characteristic. [batteryPercent] is null when the unit has no battery reading. */
data class StatusPacket(val flags: UnitFlags, val batteryPercent: Int?)

fun decodeStatus(bytes: ByteArray): StatusPacket? {
    if (bytes.size < 2) return null
    val battery = bytes.u8(1)
    return StatusPacket(UnitFlags.of(bytes.u8(0)), battery.takeIf { it <= 100 })
}

/** One notification of the Reading characteristic. */
data class ReadingPacket(val sequence: Int, val distanceMm: Int, val status: ReadingStatus)

/** A status the app doesn't know reads as unreliable. */
fun decodeReading(bytes: ByteArray): ReadingPacket? {
    if (bytes.size < 4) return null
    return ReadingPacket(
        sequence = bytes.u8(0),
        distanceMm = bytes.u16(1),
        status = ReadingStatus.entries.getOrNull(bytes.u8(3)) ?: ReadingStatus.Unreliable
    )
}

/** The control point's commands. */
enum class Command(val opcode: Int) {
    Unlock(0x01),
    SetPin(0x02),
    Commit(0x03),
    Identify(0x04),
    Forget(0x05),
    Lock(0x06),
    Release(0x07)
}

/** The control point's answers. A result the app doesn't know counts as a failure. */
enum class CommandResult(val code: Int) {
    Done(0x00),
    UnknownOpcode(0x01),
    InvalidParameters(0x02),
    NotAllowed(0x03),
    WrongPin(0x04),
    LockedOut(0x05),
    NotListed(0x06),
    SaveFailed(0x07),
    Unknown(-1)
}

/** A command as written to the control point: Unlock and Set PIN take the PIN. */
fun encodeCommand(command: Command, pin: String? = null): ByteArray =
    u8Bytes(command.opcode) + (pin?.encodeToByteArray() ?: ByteArray(0))

/** An answer notified on the control point: the opcode it answers, and its result. */
data class Answer(val opcode: Int, val result: CommandResult)

fun decodeAnswer(bytes: ByteArray): Answer? {
    if (bytes.size < 2) return null
    val code = bytes.u8(1)
    return Answer(
        bytes.u8(0),
        CommandResult.entries.find { it.code == code } ?: CommandResult.Unknown
    )
}

/**
 * The unit ID of the unit at a Bluetooth address, such as "08:AD:0A:5B:9F:BA". The ESP32's
 * Bluetooth address is its factory MAC address plus 2, so the unit ID is the address minus 2.
 * Null when [address] isn't a MAC address, as on iOS, where only Unit info tells.
 */
fun unitIdOfAddress(address: String): UnitId? {
    val digits = address.replace(":", "")
    if (digits.length != 12 || digits.any { it.lowercaseChar() !in "0123456789abcdef" }) return null
    val mac = (digits.toLong(16) - 2) and 0xFFFF_FFFF_FFFFL
    return UnitId(mac.toString(16).padStart(12, '0'))
}
