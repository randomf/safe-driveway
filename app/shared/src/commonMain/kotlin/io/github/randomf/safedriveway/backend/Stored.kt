package io.github.randomf.safedriveway.backend

import io.github.randomf.safedriveway.passage.Direction
import io.github.randomf.safedriveway.passage.PassageId
import io.github.randomf.safedriveway.passage.PassageSetup
import io.github.randomf.safedriveway.protocol.decodePassageRecord
import io.github.randomf.safedriveway.protocol.encodePassageRecord
import io.github.randomf.safedriveway.protocol.items
import io.github.randomf.safedriveway.protocol.text
import io.github.randomf.safedriveway.protocol.textBytes
import io.github.randomf.safedriveway.protocol.u16
import io.github.randomf.safedriveway.protocol.u16Bytes
import io.github.randomf.safedriveway.protocol.u32
import io.github.randomf.safedriveway.protocol.u32Bytes
import io.github.randomf.safedriveway.protocol.u8
import io.github.randomf.safedriveway.protocol.u8Bytes
import kotlin.math.roundToInt

/** Everything the phone keeps between runs (app/features.md): nothing else is stored. */
data class Stored(
    /** The passages this phone has used, in the order it first used them. */
    val passages: List<StoredPassage> = emptyList(),
    /** Which passage the driver chose when these passages were in range together. */
    val choices: Map<Set<PassageId>, PassageId> = emptyMap()
) {
    fun passage(id: PassageId): StoredPassage? = passages.find { it.setup.id == id }

    /** With [id]'s entry changed, or added from [setup] when the phone hasn't used it before. */
    fun withPassage(
        id: PassageId,
        setup: PassageSetup? = null,
        change: (StoredPassage) -> StoredPassage
    ): Stored {
        val old = passage(id)
        return when {
            old != null -> copy(passages = passages.map { if (it === old) change(it) else it })
            setup != null -> copy(passages = passages + change(StoredPassage(setup)))
            else -> this
        }
    }

    fun withoutPassage(id: PassageId): Stored = copy(
        passages = passages.filter { it.setup.id != id },
        choices = choices.filter { (among, chosen) -> chosen != id && id !in among }
    )
}

data class StoredPassage(
    /** The newest setup the phone has seen. */
    val setup: PassageSetup,
    val preferences: DriverPreferences = DriverPreferences(),
    /** The passage's PIN, once it has been entered on this phone. */
    val pin: String? = null
)

/** The version of the stored format, its first byte. */
private const val STORED_FORMAT = 1

/**
 * The stored state as bytes: the format, the passages, each with its setup as the protocol's
 * passage record, then the choices. The platform encrypts them before they reach the disk.
 */
fun encodeStored(stored: Stored): ByteArray = u8Bytes(STORED_FORMAT) +
    u8Bytes(stored.passages.size) +
    stored.passages.fold(ByteArray(0)) { bytes, passage -> bytes + encodePassage(passage) } +
    u8Bytes(stored.choices.size) +
    stored.choices.entries.fold(ByteArray(0)) { bytes, (among, chosen) ->
        bytes + u8Bytes(among.size) +
            among.fold(ByteArray(0)) { ids, id -> ids + u32Bytes(id.value) } +
            u32Bytes(chosen.value)
    }

private fun encodePassage(passage: StoredPassage): ByteArray {
    val record = encodePassageRecord(passage.setup)
    val preferences = passage.preferences
    return u16Bytes(record.size) + record +
        u8Bytes(preferences.usualDirection.ordinal) +
        u8Bytes(if (preferences.sound) 1 else 0) +
        u8Bytes((preferences.volume * 100).roundToInt()) +
        textBytes(passage.pin.orEmpty())
}

/** The stored state, or null when the bytes can't be read, and the phone starts afresh. */
fun decodeStored(bytes: ByteArray): Stored? = try {
    if (bytes.u8(0) != STORED_FORMAT) {
        null
    } else {
        bytes.items(bytes.u8(1), 2) { decodePassage(bytes, it) }?.let { (passages, choicesAt) ->
            bytes.items(bytes.u8(choicesAt), choicesAt + 1) { decodeChoice(bytes, it) }
                ?.let { (choices, _) -> Stored(passages, choices.toMap()) }
        }
    }
} catch (_: IndexOutOfBoundsException) {
    null
}

private fun decodePassage(bytes: ByteArray, at: Int): Pair<StoredPassage, Int>? {
    val length = bytes.u16(at)
    val after = at + 2 + length
    val setup = decodePassageRecord(bytes.copyOfRange(at + 2, after)) ?: return null
    val pinLength = bytes.u8(after + 3)
    val pin = bytes.text(after + 4, pinLength) ?: return null
    val preferences = DriverPreferences(
        usualDirection = Direction.entries.getOrNull(bytes.u8(after)) ?: Direction.Forwards,
        sound = bytes.u8(after + 1) != 0,
        volume = bytes.u8(after + 2).coerceIn(0, 100) / 100f
    )
    return StoredPassage(setup, preferences, pin.ifEmpty { null }) to after + 4 + pinLength
}

private fun decodeChoice(bytes: ByteArray, at: Int): Pair<Pair<Set<PassageId>, PassageId>, Int>? {
    val count = bytes.u8(at)
    val among = (0 until count).map { PassageId(bytes.u32(at + 1 + 4 * it)) }.toSet()
    val chosenAt = at + 1 + 4 * count
    return (among to PassageId(bytes.u32(chosenAt))) to chosenAt + 4
}
