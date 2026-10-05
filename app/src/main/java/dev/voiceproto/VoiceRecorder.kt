package dev.voiceproto

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Records 16 kHz mono audio and cuts it into utterances with a simple energy-based voice activity detector:
 * an adaptive noise floor, a speech threshold above it, and a hangover of [SILENCE_MS] before an utterance ends.
 */
class VoiceRecorder(
    private val onSpeaking: (Boolean) -> Unit,
    private val onUtterance: (FloatArray) -> Unit,
    /** True while the microphone delivers no sound at all (e.g. headset muted). */
    onInputSilent: (Boolean) -> Unit = {},
) {
    private val deadInput = DeadInputDetector(onInputSilent)

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val FRAME = 480 // 30 ms
        private const val PRE_ROLL_FRAMES = 10 // 300 ms kept before speech onset
        private const val START_FRAMES = 3 // 90 ms above threshold to start
        private const val SILENCE_MS = 900
        private const val MIN_UTTERANCE_MS = 400
        private const val MAX_UTTERANCE_MS = 30_000
    }

    /** While muted (e.g. during speech output) frames are discarded. */
    @Volatile var muted = false

    private var job: Job? = null

    val isRunning get() = job?.isActive == true

    @SuppressLint("MissingPermission") // checked by the caller
    fun start(scope: CoroutineScope) {
        if (isRunning) return
        job = scope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val record = AudioRecord(
                HeadsetRoute.recordSource, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, max(minBuf, FRAME * 4 * 8),
            )
            val effects = listOfNotNull(
                if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId) else null,
                if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId) else null,
            )
            effects.forEach { it.enabled = true }
            HeadsetRoute.input?.let { record.setPreferredDevice(it) }
            try {
                record.startRecording()
                loop(record)
            } finally {
                record.stop()
                record.release()
                effects.forEach { it.release() }
                onSpeaking(false)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun CoroutineScope.loop(record: AudioRecord) {
        val frame = FloatArray(FRAME)
        val preRoll = ArrayDeque<FloatArray>()
        val utterance = ArrayList<FloatArray>()
        var noiseFloor = 0.003f
        var speaking = false
        var aboveCount = 0
        var silenceFrames = 0
        val silenceLimit = SILENCE_MS / 30
        val maxFrames = MAX_UTTERANCE_MS / 30

        fun finish() {
            // Drop most of the trailing silence, keep ~300 ms.
            val keep = (utterance.size - silenceFrames + 10).coerceAtMost(utterance.size)
            val frames = utterance.subList(0, keep)
            if (frames.size * 30 >= MIN_UTTERANCE_MS) {
                val out = FloatArray(frames.size * FRAME)
                frames.forEachIndexed { i, f -> f.copyInto(out, i * FRAME) }
                onUtterance(out)
            }
            utterance.clear()
            speaking = false
            silenceFrames = 0
            aboveCount = 0
            onSpeaking(false)
        }

        while (isActive) {
            var read = 0
            while (read < FRAME) {
                val n = record.read(frame, read, FRAME - read, AudioRecord.READ_BLOCKING)
                if (n < 0) return
                read += n
            }
            deadInput.feed(frame, 30)
            if (muted) {
                if (speaking) { utterance.clear(); speaking = false; onSpeaking(false) }
                preRoll.clear()
                aboveCount = 0
                continue
            }

            var sum = 0f
            for (s in frame) sum += s * s
            val rms = sqrt(sum / FRAME)
            val threshold = max(noiseFloor * 3f, 0.01f)
            val copy = frame.copyOf()

            if (!speaking) {
                // Track the background level only while nobody speaks.
                noiseFloor = 0.95f * noiseFloor + 0.05f * rms
                preRoll.addLast(copy)
                if (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                aboveCount = if (rms > threshold) aboveCount + 1 else 0
                if (aboveCount >= START_FRAMES) {
                    speaking = true
                    silenceFrames = 0
                    utterance.addAll(preRoll)
                    preRoll.clear()
                    onSpeaking(true)
                }
            } else {
                utterance.add(copy)
                silenceFrames = if (rms < threshold * 0.7f) silenceFrames + 1 else 0
                if (silenceFrames >= silenceLimit || utterance.size >= maxFrames) finish()
            }
        }
    }
}
