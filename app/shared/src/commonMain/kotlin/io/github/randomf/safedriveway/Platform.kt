package io.github.randomf.safedriveway

import androidx.compose.runtime.Composable
import io.github.randomf.safedriveway.passage.Alarm

/** What the app needs from the platform beyond Bluetooth, which Kable covers. */
class Platform(val storage: SecureStorage, val alarms: AlarmPlayer)

/** One file of what the phone stores, kept encrypted with a key the platform guards. */
interface SecureStorage {
    /** The bytes last saved, or null when there are none or they can't be decrypted. */
    fun load(): ByteArray?

    /** Replaces what is stored, whole or not at all. */
    fun save(bytes: ByteArray)
}

/** The phone's alarm sounds. */
interface AlarmPlayer {
    /**
     * Plays [alarm] until the next call, or nothing when it's null. [volume], 0 to 1, is a part of
     * the phone's alarm volume, which the app never changes.
     */
    fun play(alarm: Alarm?, volume: Float)

    /** Turns other audio, such as music or navigation, down while [on]. */
    fun duck(on: Boolean)

    /** The phone's alarm volume, 0 to 1. */
    fun phoneVolume(): Float

    fun release()
}

/** Keeps the screen on while this is shown. */
@Composable
expect fun KeepScreenOn()

/** Whether the app is in the foreground: started and on screen. */
@Composable
expect fun isInForeground(): Boolean
