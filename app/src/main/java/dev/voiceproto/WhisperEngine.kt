package dev.voiceproto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object WhisperNative {
    init {
        System.loadLibrary("whisper_jni")
    }

    @JvmStatic external fun init(modelPath: String): Long
    @JvmStatic external fun free(ctx: Long)
    @JvmStatic external fun transcribe(
        ctx: Long, audio: FloatArray, language: String, prompt: String, threads: Int,
    ): ByteArray
    @JvmStatic external fun systemInfo(): String
}

enum class WhisperModel(val label: String, val fileName: String, val sizeMb: Int) {
    BASE("whisper-base (57 MB)", "ggml-base-q5_1.bin", 57),
    SMALL("whisper-small (181 MB)", "ggml-small-q5_1.bin", 181),
    ;

    val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}

/** Downloads ggml models and runs whisper.cpp; all native calls happen on one dedicated thread. */
class WhisperEngine(private val modelDir: File) {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var ctx = 0L
    private var loaded: WhisperModel? = null

    val loadedModel get() = loaded

    fun isDownloaded(model: WhisperModel) = File(modelDir, model.fileName).exists()

    suspend fun download(model: WhisperModel, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val target = File(modelDir, model.fileName)
        if (target.exists()) return@withContext
        val part = File(modelDir, model.fileName + ".part")
        val conn = URL(model.url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            if (!part.renameTo(target)) error("Konnte Modelldatei nicht speichern")
        } finally {
            conn.disconnect()
            part.delete()
        }
    }

    suspend fun load(model: WhisperModel) = withContext(dispatcher) {
        if (loaded == model) return@withContext
        if (ctx != 0L) WhisperNative.free(ctx)
        ctx = 0L
        loaded = null
        val ptr = WhisperNative.init(File(modelDir, model.fileName).absolutePath)
        if (ptr == 0L) error("Modell konnte nicht geladen werden")
        ctx = ptr
        loaded = model
    }

    suspend fun transcribe(audio: FloatArray, language: String, prompt: String): String = withContext(dispatcher) {
        check(ctx != 0L) { "Kein Modell geladen" }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        String(WhisperNative.transcribe(ctx, audio, language, prompt, threads), Charsets.UTF_8).trim()
    }

    fun systemInfo(): String = WhisperNative.systemInfo()
}
