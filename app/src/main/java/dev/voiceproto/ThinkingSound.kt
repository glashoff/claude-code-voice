package dev.voiceproto

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Soft looping "thinking" sound played while Claude works on a reply: two gentle sine blips, then a pause.
 * Generated in code, played on the same audio usage as speech output (which also keeps a Bluetooth headset awake).
 */
class ThinkingSound {
    companion object {
        private const val SAMPLE_RATE = 24_000
        private const val LOOP_MS = 1_600
    }

    // Built on each use: the usage follows the headset routing (call mode for a Bluetooth headset microphone).
    private val attributes get() = AudioAttributes.Builder()
        .setUsage(HeadsetRoute.playbackUsage)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val loop: ShortArray = buildLoop()
    private var track: AudioTrack? = null

    private fun buildLoop(): ShortArray {
        val samples = ShortArray(SAMPLE_RATE * LOOP_MS / 1000)
        fun blip(startMs: Int, freq: Double, lengthMs: Int) {
            val start = SAMPLE_RATE * startMs / 1000
            val length = SAMPLE_RATE * lengthMs / 1000
            for (i in 0 until length) {
                val t = i.toDouble() / SAMPLE_RATE
                // Quick fade-in, exponential decay: a soft "bloop" instead of a hard beep.
                val envelope = minOf(1.0, i / (SAMPLE_RATE * 0.008)) * exp(-t * 18)
                val value = sin(2 * PI * freq * t) * envelope * 0.5
                samples[start + i] = (value * Short.MAX_VALUE).toInt().toShort()
            }
        }
        blip(0, 660.0, 180)
        blip(220, 880.0, 180)
        return samples
    }

    @Synchronized
    fun start(volume: Float) {
        if (track != null) return
        val t = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(loop.size * 2)
            .build()
        t.write(loop, 0, loop.size)
        t.setLoopPoints(0, loop.size, -1)
        t.setVolume(volume.coerceIn(0f, 1f))
        t.play()
        track = t
    }

    @Synchronized
    fun stop() {
        track?.let {
            it.stop()
            it.release()
        }
        track = null
    }
}
