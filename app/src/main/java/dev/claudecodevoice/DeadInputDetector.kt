package dev.claudecodevoice

import kotlin.math.abs

/**
 * Notices a microphone that delivers no sound at all, e.g. a headset muted by flipping up its boom. A working
 * microphone always picks up some noise, so a stretch of (near) digital silence means "muted".
 */
class DeadInputDetector(private val onChange: (silent: Boolean) -> Unit) {
    companion object {
        private const val SILENT_AFTER_MS = 800
        private const val MAX_PCM16 = 4 // peak at or below this counts as silence
        private const val MAX_FLOAT = 0.0002f
    }

    private var quietMs = 0
    private var silent = false

    fun feed(pcm16: ByteArray, length: Int, durationMs: Int) {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (pcm16[i].toInt() and 0xff) or (pcm16[i + 1].toInt() shl 8)
            peak = maxOf(peak, abs(sample.toShort().toInt()))
            i += 2
        }
        update(peak <= MAX_PCM16, durationMs)
    }

    fun feed(frame: FloatArray, durationMs: Int) {
        var peak = 0f
        for (v in frame) peak = maxOf(peak, abs(v))
        update(peak <= MAX_FLOAT, durationMs)
    }

    private fun update(quiet: Boolean, durationMs: Int) {
        quietMs = if (quiet) quietMs + durationMs else 0
        val now = quietMs >= SILENT_AFTER_MS
        if (now != silent) {
            silent = now
            onChange(now)
        }
    }
}
