package dev.voiceproto

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Continuous recognition with the built-in Android SpeechRecognizer: after every result or timeout it restarts
 * until [stop] is called. Must be used from the main thread.
 */
class AndroidStt(
    private val context: Context,
    private val onSpeaking: (Boolean) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onResult: (text: String, latencyMs: Long) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var language = "de-DE"
    private var preferOffline = false
    private var speechEndAt = 0L

    /** While paused (e.g. during speech output) listening is not restarted. */
    var paused = false
        set(value) {
            if (field == value) return
            field = value
            if (value) recognizer?.cancel() else if (active) listen()
        }

    val isRunning get() = active

    fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(language: String, preferOffline: Boolean) {
        this.language = language
        this.preferOffline = preferOffline
        active = true
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply { setRecognitionListener(listener) }
        }
        listen()
    }

    fun stop() {
        active = false
        recognizer?.destroy()
        recognizer = null
        onSpeaking(false)
    }

    private fun listen() {
        if (!active || paused) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        recognizer?.startListening(intent)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() = onSpeaking(true)
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            speechEndAt = System.currentTimeMillis()
            onSpeaking(false)
        }

        override fun onError(error: Int) {
            onSpeaking(false)
            when (error) {
                // Nothing said: just listen again.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listen()
                SpeechRecognizer.ERROR_CLIENT -> Unit // caused by cancel(), e.g. when pausing
                else -> {
                    onError(errorText(error))
                    if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) listen()
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) onResult(text, System.currentTimeMillis() - speechEndAt)
            onPartial("")
            listen()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            onPartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun errorText(error: Int) = when (error) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Netzwerkfehler"
        SpeechRecognizer.ERROR_AUDIO -> "Audiofehler"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Keine Mikrofon-Berechtigung"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Erkennung belegt"
        SpeechRecognizer.ERROR_SERVER -> "Serverfehler"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Sprache nicht unterstützt"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Sprache (offline) nicht verfügbar"
        else -> "Fehler $error"
    }
}
