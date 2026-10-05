package dev.voiceproto

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.Voice
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * The German Piper voice "Thorsten (medium)", synthesized in the app with sherpa-onnx.
 *
 * The model is not shipped in the APK: [prepare] downloads it once from the sherpa-onnx releases into app-private
 * storage, checks its SHA-256 and unpacks it. [speak] blocks while a text is synthesized and played.
 */
class PiperSpeaker(context: Context, private val attributes: () -> AudioAttributes) {
    companion object {
        const val ENGINE = "app:piper-thorsten"
        const val LABEL = "Thorsten (in der App)"
        private const val MODEL = "vits-piper-de_DE-thorsten-medium"
        private const val URL_ = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$MODEL.tar.bz2"
        private const val SHA256 = "50487d9c95fdf2191f31d2588569381063ba1591dcd4c7d4bdd30f12b2191714"

        val voice = Voice("thorsten-medium", Locale.GERMANY, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, emptySet<String>())
    }

    private val baseDir = File(context.filesDir, "piper")
    private val modelDir = File(baseDir, MODEL)
    private val doneMarker = File(modelDir, ".complete")
    private var tts: OfflineTts? = null
    @Volatile private var track: AudioTrack? = null

    /** Downloads (if needed) and loads the model. Reports progress as a short German status text. Blocking. */
    @Synchronized
    fun prepare(onProgress: (String) -> Unit) {
        if (tts != null) return
        if (!doneMarker.exists()) download(onProgress)
        onProgress("Thorsten wird geladen …")
        tts = OfflineTts(
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = File(modelDir, "de_DE-thorsten-medium.onnx").path,
                        tokens = File(modelDir, "tokens.txt").path,
                        dataDir = File(modelDir, "espeak-ng-data").path,
                    ),
                    numThreads = 4,
                ),
            ),
        )
    }

    private fun download(onProgress: (String) -> Unit) {
        baseDir.mkdirs()
        val archive = File(baseDir, "$MODEL.tar.bz2.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = URL(URL_).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        try {
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                archive.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(if (percent >= 0) "Thorsten wird heruntergeladen: $percent %" else "Thorsten wird heruntergeladen: ${done / 1_000_000} MB")
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (hash != SHA256) {
            archive.delete()
            error("Prüfsumme des Thorsten-Modells stimmt nicht.")
        }
        onProgress("Thorsten wird entpackt …")
        modelDir.deleteRecursively()
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
            val root = baseDir.canonicalFile
            while (true) {
                val entry = tar.nextEntry ?: break
                val target = File(baseDir, entry.name).canonicalFile
                require(target.path.startsWith(root.path + File.separator)) { "Ungültiger Pfad im Archiv: ${entry.name}" }
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { tar.copyTo(it) }
                }
            }
        }
        archive.delete()
        doneMarker.createNewFile()
    }

    /**
     * Synthesizes [text] and plays it; returns when playback has finished or [isCancelled] became true.
     * Audio is streamed into the AudioTrack as soon as the first chunk is synthesized.
     */
    fun speak(text: String, rate: Float, isCancelled: () -> Boolean): Boolean {
        val engine = synchronized(this) { tts } ?: return false
        val sampleRate = engine.sampleRate()
        val t = AudioTrack.Builder()
            .setAudioAttributes(attributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT) * 4)
            .build()
        track = t
        var written = 0
        try {
            t.play()
            // An explicit class, not a lambda: sherpa-onnx's JNI code looks up "invoke([F)Ljava/lang/Integer;", which
            // Kotlin 2 lambdas (compiled via invokedynamic) don't have, so a lambda aborts the process.
            val callback = object : Function1<FloatArray, Int> {
                override fun invoke(samples: FloatArray): Int {
                    if (isCancelled()) return 0
                    t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                    written += samples.size
                    return if (isCancelled()) 0 else 1
                }
            }
            engine.generateWithCallback(text, sid = 0, speed = rate, callback = callback)
            // Trailing silence: a very short utterance (e.g. a single word) may not fill the track's start threshold
            // and would be cut off when the track is stopped; the padding also lets the last syllable ring out.
            if (!isCancelled()) {
                val pad = FloatArray(sampleRate * 400 / 1000)
                t.write(pad, 0, pad.size, AudioTrack.WRITE_BLOCKING)
                written += pad.size
            }
            // Wait until everything written has actually been played (bounded, in case the track never starts).
            val deadline = System.currentTimeMillis() + written * 1000L / sampleRate + 1_000
            while (!isCancelled() && t.playState == AudioTrack.PLAYSTATE_PLAYING && t.playbackHeadPosition < written &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(20)
            }
            return true
        } finally {
            track = null
            runCatching { t.stop() }
            t.release()
        }
    }

    /** Interrupts playback immediately (the speaking thread notices cancellation itself). */
    fun stop() {
        track?.let { runCatching { it.pause(); it.flush(); it.stop() } }
    }

    @Synchronized
    fun release() {
        tts?.release()
        tts = null
    }
}
