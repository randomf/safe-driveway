package io.github.randomf.safedriveway.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.github.randomf.safedriveway.AlarmPlayer
import io.github.randomf.safedriveway.backend.Backend
import io.github.randomf.safedriveway.backend.DriverPreferences
import io.github.randomf.safedriveway.passage.Alarm
import io.github.randomf.safedriveway.passage.alarmOf
import io.github.randomf.safedriveway.passage.carSeen
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

/** How often the alarm follows the readings: a reading reaches the sound within 100 ms. */
private val ALARM_TICK = 25.milliseconds

/**
 * Sounds the open passage's alarm while [enabled], and turns other audio down while the car is in
 * the passage (app/features.md#alarms-and-suggestions). It stays quiet while the driver has the
 * passage's sound off, and while the units are unlocked for a change of their setup, when a board
 * held on the line would sound it.
 */
@Composable
fun AlarmEffect(backend: Backend, player: AlarmPlayer, enabled: Boolean) {
    LaunchedEffect(backend, player, enabled) {
        if (!enabled) return@LaunchedEffect
        var carSeenAt: ComparableTimeMark? = null
        try {
            while (true) {
                val now = TimeSource.Monotonic.markNow()
                val open = backend.open.value?.takeUnless { it.unlocked }
                val preferences = open?.let { passage ->
                    backend.remembered.value.find { it.setup.id == passage.setup.id }?.preferences
                } ?: DriverPreferences()
                carSeenAt = when {
                    open == null -> null
                    carSeen(open.setup, open.units, now) -> now
                    else -> carSeenAt
                }
                val sound = open != null && preferences.sound
                player.play(
                    open?.takeIf { sound }?.let { alarmOf(it.setup, it.units, now, carSeenAt) },
                    preferences.volume
                )
                player.duck(sound && carSeenAt?.let { now - it < Alarm.CAR_HOLD } == true)
                delay(ALARM_TICK)
            }
        } finally {
            player.play(null, 0f)
            player.duck(false)
        }
    }
}
