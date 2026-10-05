package dev.claudecodevoice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Continuous recognition in ONE segmented session (Android 13+): the app records the microphone itself and streams
 * PCM into the recognizer through a pipe (EXTRA_AUDIO_SOURCE). The recognizer returns one result per utterance
 * (onSegmentResults) without restarting, so nothing is cut off and no start/stop beeps are played between utterances.
 *
 * While [silenced] is set (e.g. during speech output) silence is streamed instead of the microphone signal, which keeps
 * the session alive without transcribing the app's own voice.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class ContinuousStt(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onResult: (text: String, meta: String) -> Unit,
    private val onError: (String) -> Unit,
    /** True while the microphone delivers no sound at all (e.g. headset muted). */
    onInputSilent: (Boolean) -> Unit = {},
) {
    private val deadInput = DeadInputDetector(onInputSilent)

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_BYTES = 3_200 // 100 ms of 16-bit mono
        val SWITCH_LANGUAGES = arrayListOf("de-DE", "en-US")
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var audioJob: Job? = null
    private var readSide: ParcelFileDescriptor? = null
    private var active = false
    private var scope: CoroutineScope? = null
    private var language = "de-DE"
    private var languageSwitch = false
    private var detectedLanguage: String? = null
    private var consecutiveErrors = 0

    @Volatile var silenced = false

    val onDevice get() = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun createRecognizer(): SpeechRecognizer =
        if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)

    private fun baseIntent(language: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
    }

    /** Asks the recognizer which languages it supports and which are installed on the device. */
    fun checkSupport(language: String, callback: (String) -> Unit) {
        val probe = createRecognizer()
        probe.checkRecognitionSupport(baseIntent(language), { main.post(it) }, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                callback(describe(support, language))
                probe.destroy()
            }

            override fun onError(error: Int) {
                callback("Abfrage nicht möglich (Fehler $error). Auf dem Gerät: ${if (onDevice) "ja" else "nein"}")
                probe.destroy()
            }
        })
    }

    private fun describe(s: RecognitionSupport, language: String): String {
        fun List<String>.relevant() = filter { it.startsWith("de") || it.startsWith("en") }.joinToString().ifEmpty { "–" }
        val state = when {
            s.installedOnDeviceLanguages.any { it.equals(language, true) } -> "installiert ✓"
            s.pendingOnDeviceLanguages.any { it.equals(language, true) } -> "wird heruntergeladen …"
            s.supportedOnDeviceLanguages.any { it.equals(language, true) } -> "unterstützt, aber nicht installiert"
            else -> "auf dem Gerät nicht verfügbar"
        }
        return buildString {
            append("Erkennung auf dem Gerät: ${if (onDevice) "ja" else "nein (Online-Dienst)"}\n")
            append("$language: $state\n")
            append("Installiert: ${s.installedOnDeviceLanguages.relevant()}\n")
            append("Unterstützt: ${s.supportedOnDeviceLanguages.relevant()}")
        }
    }

    /** Starts the system download of the on-device model for [language]. */
    fun downloadLanguage(language: String) {
        val r = createRecognizer()
        r.triggerModelDownload(baseIntent(language))
        main.postDelayed({ r.destroy() }, 2_000)
    }

    fun start(scope: CoroutineScope, language: String, languageSwitch: Boolean) {
        consecutiveErrors = 0
        this.scope = scope
        this.language = language
        this.languageSwitch = languageSwitch
        active = true
        startSession()
    }

    fun stop() {
        active = false
        endSession()
        onPartial("")
    }

    private fun startSession() {
        if (!active) return
        val (read, write) = ParcelFileDescriptor.createPipe()
        readSide = read
        val r = createRecognizer().also { recognizer = it }
        r.setRecognitionListener(listener)

        val intent = baseIntent(language).apply {
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // The session lasts as long as our audio stream; results come per segment.
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
            if (languageSwitch && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, SWITCH_LANGUAGES)
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, SWITCH_LANGUAGES)
            }
        }
        r.startListening(intent)
        audioJob = scope?.launch(Dispatchers.IO) { pump(write) }
    }

    private fun endSession() {
        audioJob?.cancel()
        audioJob = null
        recognizer?.destroy()
        recognizer = null
        readSide?.close()
        readSide = null
    }

    private fun restartLater() {
        endSession()
        if (active) main.postDelayed({ if (active && recognizer == null) startSession() }, 500)
    }

    @SuppressLint("MissingPermission") // checked by the caller
    private fun CoroutineScope.pump(write: ParcelFileDescriptor) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            HeadsetRoute.recordSource, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK_BYTES * 4),
        )
        val effects = listOfNotNull(
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId) else null,
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId) else null,
        )
        effects.forEach { it.enabled = true }
        HeadsetRoute.input?.let { record.setPreferredDevice(it) }
        val buffer = ByteArray(CHUNK_BYTES)
        val silence = ByteArray(CHUNK_BYTES)
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                record.startRecording()
                while (isActive) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n <= 0) break
                    deadInput.feed(buffer, n, n * 1000 / (SAMPLE_RATE * 2))
                    out.write(if (silenced) silence else buffer, 0, n)
                }
            }
        } catch (e: IOException) {
            // Recognizer closed its end of the pipe; the listener handles restarting.
        } finally {
            record.stop()
            record.release()
            effects.forEach { it.release() }
        }
    }

    private fun firstResult(bundle: Bundle?) =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun emit(text: String) {
        if (text.isBlank()) return
        consecutiveErrors = 0
        val lang = detectedLanguage?.let { " · Sprache: $it" }.orEmpty()
        onResult(text, "Durchgehend · ${if (onDevice) "auf dem Gerät" else "online"}$lang")
        onPartial("")
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) = onPartial(firstResult(partialResults))

        override fun onSegmentResults(segmentResults: Bundle) = emit(firstResult(segmentResults))

        // Not expected in a segmented session, but some recognizers end with a final result.
        override fun onResults(results: Bundle?) {
            emit(firstResult(results))
            restartLater()
        }

        override fun onEndOfSegmentedSession() = restartLater()

        override fun onLanguageDetection(results: Bundle) {
            detectedLanguage = results.getString(SpeechRecognizer.DETECTED_LANGUAGE) ?: detectedLanguage
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Unit
                SpeechRecognizer.ERROR_CLIENT -> if (!active) return
                else -> onError(errorText(error))
            }
            val fatal = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
            if (fatal || ++consecutiveErrors >= 3) {
                if (!fatal) onError("Erkennung nach wiederholten Fehlern gestoppt")
                stop()
            } else {
                restartLater()
            }
        }
    }

    private fun errorText(error: Int) = when (error) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Netzwerkfehler"
        SpeechRecognizer.ERROR_AUDIO -> "Audiofehler"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Keine Mikrofon-Berechtigung"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Erkennung belegt"
        SpeechRecognizer.ERROR_SERVER -> "Serverfehler"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Sprache nicht unterstützt"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Sprachpaket nicht installiert – bitte über „Sprachpaket laden“ installieren"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Zu viele Anfragen"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Erkennungsdienst getrennt"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "Unterstützung nicht prüfbar"
        else -> "Fehler $error"
    }
}
