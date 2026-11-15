package io.github.randomf.safedriveway.protocol

/** Unsigned little-endian numbers, as the protocol has them (doc/ble-protocol.md). */
internal fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

internal fun ByteArray.u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)

internal fun ByteArray.u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)

internal fun u8Bytes(value: Int): ByteArray = byteArrayOf(value.toByte())

internal fun u16Bytes(value: Int): ByteArray = byteArrayOf(value.toByte(), (value shr 8).toByte())

internal fun u32Bytes(value: Long): ByteArray =
    u16Bytes((value and 0xFFFF).toInt()) + u16Bytes(((value shr 16) and 0xFFFF).toInt())

/** Text preceded by its length in bytes, with no terminator. */
internal fun textBytes(text: String): ByteArray {
    val bytes = text.encodeToByteArray()
    return u8Bytes(bytes.size) + bytes
}

/** The text of [length] bytes from [at], or null when they aren't UTF-8. */
internal fun ByteArray.text(at: Int, length: Int): String? = try {
    decodeToString(at, at + length, throwOnInvalidSequence = true)
} catch (_: CharacterCodingException) {
    null
}

/**
 * [count] items one after another from [at], each read by [decode] from where the one before
 * ended, and where the last ends. Null when one of them doesn't fit.
 */
internal fun <T> ByteArray.items(
    count: Int,
    at: Int,
    decode: (Int) -> Pair<T, Int>?
): Pair<List<T>, Int>? {
    if (count == 0) return emptyList<T>() to at
    val items = generateSequence(decode(at)) { (_, next) -> decode(next) }.take(count).toList()
    return if (items.size == count) items.map { it.first } to items.last().second else null
}

internal fun ByteArray.hex(from: Int = 0, to: Int = size): String =
    (from until to).joinToString("") { u8(it).toString(16).padStart(2, '0') }

internal fun hexBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
