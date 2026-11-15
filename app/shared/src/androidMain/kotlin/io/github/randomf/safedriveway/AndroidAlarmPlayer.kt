package io.github.randomf.safedriveway

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import io.github.randomf.safedriveway.passage.Alarm
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays the alarms as tones the app makes itself, through an AudioTrack used as an alarm: they
 * play while the phone is on silent, and through Do Not Disturb unless the driver blocked alarms
 * there (ADR 0011). A thread feeds the track in short chunks while an alarm plays, so that a new
 * alarm is heard within about 20 ms, and lets the track go after a second of quiet.
 */
class AndroidAlarmPlayer(context: Context) : AlarmPlayer {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    @Volatile private var alarm: Alarm? = null

    @Volatile private var volume = 1f
    private var thread: Thread? = null
    private var ducking = false

    @Synchronized
    override fun play(alarm: Alarm?, volume: Float) {
        this.alarm = alarm
        this.volume = volume
        if (alarm != null && thread?.isAlive != true) {
            thread = Thread(::feed, "alarm").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        }
    }

    override fun duck(on: Boolean) {
        if (on == ducking) return
        ducking = on
        if (on) audio.requestAudioFocus(focus) else audio.abandonAudioFocusRequest(focus)
    }

    override fun phoneVolume(): Float = audio.getStreamVolume(AudioManager.STREAM_ALARM).toFloat() /
        audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)

    override fun release() {
        play(null, 0f)
        duck(false)
    }

    /** Runs on the alarm thread until a second has passed with no alarm. */
    private fun feed() {
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val tones = Tones()
        val chunk = ShortArray(CHUNK)
        var quiet = 0
        try {
            track.play()
            while (quiet < RATE) {
                val current = alarm
                quiet = if (current == null) quiet + CHUNK else 0
                track.setVolume(volume)
                tones.fill(chunk, current)
                track.write(chunk, 0, chunk.size)
            }
        } finally {
            track.stop()
            track.release()
        }
    }

    /**
     * The alarms' sound, sample by sample. Beeps of [BEEP] at [TONE], one every warning period, as
     * on the units; a continuous [TONE] below the critical gap; and a falling two-tone for a unit
     * that went silent. Every tone fades in and out over a few milliseconds, so that it doesn't
     * click.
     */
    private class Tones {
        private var sample = 0L
        private var beepAt = Long.MIN_VALUE / 2
        private var last: Alarm? = null

        fun fill(chunk: ShortArray, alarm: Alarm?) {
            // A warning that starts now beeps at once.
            if (alarm is Alarm.Warning && last !is Alarm.Warning) beepAt = Long.MIN_VALUE / 2
            last = alarm
            for (i in chunk.indices) {
                chunk[i] = (AMPLITUDE * level(alarm)).toInt().toShort()
                sample++
            }
        }

        private fun level(alarm: Alarm?): Double = when (alarm) {
            null -> 0.0

            Alarm.Critical -> sine(TONE)

            is Alarm.Warning -> {
                if (sample - beepAt >= alarm.periodMs * RATE / 1000) beepAt = sample
                val into = sample - beepAt
                if (into < BEEP) sine(TONE) * fade(into, BEEP) else 0.0
            }

            Alarm.Silent -> {
                val into = sample % SILENT_PERIOD
                when {
                    into < SILENT_TONE -> sine(SILENT_HIGH) * fade(into, SILENT_TONE)

                    into < 2 * SILENT_TONE -> sine(SILENT_LOW) *
                        fade(into - SILENT_TONE, SILENT_TONE)

                    else -> 0.0
                }
            }
        }

        private fun sine(hz: Int) = sin(2 * PI * hz * sample / RATE)

        /** 1 in the middle of a tone of [length] samples, ramping over [FADE] at both ends. */
        private fun fade(into: Long, length: Long): Double =
            minOf(1.0, into.toDouble() / FADE, (length - into).toDouble() / FADE)
    }

    private companion object {
        const val RATE = 48_000
        const val CHUNK = RATE / 100
        const val AMPLITUDE = 0.8 * Short.MAX_VALUE
        const val FADE = RATE * 4L / 1000
        const val TONE = 1500
        const val BEEP = RATE * 70L / 1000
        const val SILENT_HIGH = 1200
        const val SILENT_LOW = 800
        const val SILENT_TONE = RATE * 150L / 1000
        const val SILENT_PERIOD = RATE * 600L / 1000
    }
}
