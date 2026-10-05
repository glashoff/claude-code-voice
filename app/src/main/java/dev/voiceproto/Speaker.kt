package dev.voiceproto

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** "de", "en" or another code; normalizes three-letter codes ("deu", "eng") that some engines report. */
val Voice.lang: String
    get() = when (runCatching { locale.isO3Language }.getOrDefault(locale.language)) {
        "deu", "ger" -> "de"
        "eng" -> "en"
        else -> locale.language
    }

/**
 * Wraps an Android TextToSpeech engine or the in-app Piper voice ([PiperSpeaker.ENGINE]). The engine can be switched
 * at runtime ([setEngine]); an empty package name means the system default engine.
 *
 * Bluetooth headsets often need a moment to wake up their audio path after a pause, which clips the first syllable.
 * Before each reply a short stretch of silence (leadInMs) is therefore played on the same audio usage.
 *
 * A reply can be streamed: [startReply], then [enqueue] sentence by sentence as they arrive, then [finishReply].
 */
class Speaker(
    private val context: Context,
    enginePackage: String,
    /** Called with the package of the engine that just became ready and its usable voices. */
    private val onReady: (String, List<Voice>) -> Unit,
    private val onSpeaking: (Boolean) -> Unit,
    /** Status of the in-app voice (download/loading progress, errors); null when idle. */
    private val onStatus: (String?) -> Unit = {},
) {
    // Built on each use: the usage follows the headset routing (call mode for a Bluetooth headset microphone).
    private val attributes get() = AudioAttributes.Builder()
        .setUsage(HeadsetRoute.playbackUsage)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val worker = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()
    private val pending = AtomicInteger() // utterances handed to the TTS engine and not finished yet
    @Volatile private var replyOpen = false
    @Volatile private var speaking = false
    @Volatile private var voice: Voice? = null
    @Volatile private var rate = 1f

    /** A sentence handed to [enqueue] that has not been spoken completely yet. */
    private class Item(val text: String)
    private val unfinished = java.util.Collections.synchronizedList(mutableListOf<Item>())
    private val itemsByUtterance = java.util.concurrent.ConcurrentHashMap<String, Item>()

    /** Package of the engine in use ("" = system default). */
    @Volatile var enginePackage: String = enginePackage
        private set
    // Opened once the current engine has initialized; utterances wait for it so nothing is lost while switching.
    @Volatile private var ready = CountDownLatch(1)
    private val piper by lazy { PiperSpeaker(context) { attributes } }
    private val usePiper get() = enginePackage == PiperSpeaker.ENGINE
    // The Android engine stays alive while Piper is used: it lists the installed engines.
    private var androidPackage = if (enginePackage == PiperSpeaker.ENGINE) "" else enginePackage
    @Volatile private var tts: TextToSpeech = create(androidPackage, reportReady = !usePiper)

    init {
        if (usePiper) preparePiper()
    }

    private fun create(pkg: String, reportReady: Boolean = true): TextToSpeech {
        val latch = CountDownLatch(1)
        if (reportReady) ready = latch
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(context, { status ->
            latch.countDown()
            if (!reportReady) return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) onReady(pkg, availableVoices(engine))
            else onReady(pkg, emptyList())
        }, pkg.ifEmpty { null })
        engine.setAudioAttributes(attributes)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                itemsByUtterance.remove(utteranceId)?.let { unfinished.remove(it) }
                utteranceFinished()
            }
            // Stopped utterances stay in [unfinished]: they may be resumed (see stopAndTakeRemaining).
            override fun onStop(utteranceId: String?, interrupted: Boolean) = utteranceFinished()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                itemsByUtterance.remove(utteranceId)?.let { unfinished.remove(it) }
                utteranceFinished()
            }
        })
        return engine
    }

    /** Installed TTS engines as (package, label), plus the in-app Piper voice. */
    fun engines(): List<Pair<String, String>> =
        tts.engines.map { it.name to it.label } + (PiperSpeaker.ENGINE to PiperSpeaker.LABEL)

    /** Package of the system default engine. */
    fun defaultEngine(): String = tts.defaultEngine ?: ""

    /** Switches to another installed engine; [onReady] fires again with its voices. */
    fun setEngine(pkg: String) {
        if (pkg == enginePackage) return
        stop()
        enginePackage = pkg
        if (pkg == PiperSpeaker.ENGINE) {
            preparePiper()
            return
        }
        onStatus(null)
        if (pkg == androidPackage && ready.count == 0L) {
            // The Android engine kept running while Piper was used; just report its voices again.
            onReady(pkg, availableVoices(tts))
            return
        }
        val old = tts
        androidPackage = pkg
        tts = create(pkg)
        old.shutdown()
    }

    private fun preparePiper() {
        val latch = CountDownLatch(1)
        ready = latch
        Thread {
            try {
                piper.prepare { onStatus(it) }
                onStatus(null)
                if (usePiper) onReady(PiperSpeaker.ENGINE, listOf(PiperSpeaker.voice))
            } catch (e: Exception) {
                onStatus("Thorsten nicht verfügbar: ${e.message ?: e.javaClass.simpleName}")
                if (usePiper) onReady(PiperSpeaker.ENGINE, emptyList())
            } finally {
                latch.countDown()
            }
        }.start()
    }

    private fun availableVoices(engine: TextToSpeech): List<Voice> =
        (runCatching { engine.voices }.getOrNull() ?: emptySet())
            .filter { it.lang in setOf("en", "de") && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareBy({ it.lang != "en" }, { it.locale.toLanguageTag() }, { it.isNetworkConnectionRequired }, { it.name }))

    /** Speaks a complete text (with lead-in). */
    fun speak(text: String, voice: Voice?, rate: Float, leadInMs: Int) {
        startReply(voice, rate, leadInMs)
        enqueue(text)
        finishReply()
    }

    /** Opens a reply; with [interrupt] = false, speech that is still playing is kept and the reply queues behind it. */
    fun startReply(voice: Voice?, rate: Float, leadInMs: Int, interrupt: Boolean = true) {
        if (interrupt) stop()
        this.voice = voice
        this.rate = rate
        replyOpen = true
        updateSpeaking()
        val gen = generation.get()
        worker.execute { if (leadInMs > 0 && generation.get() == gen) playSilence(leadInMs) }
    }

    fun enqueue(text: String) {
        if (text.isBlank()) return
        val gen = generation.get()
        val item = Item(text)
        unfinished.add(item)
        worker.execute {
            if (generation.get() != gen) return@execute // stopped in the meantime
            // The engine may still be starting after a switch (Piper: possibly downloading, so wait longer).
            ready.await(if (usePiper) 300 else 5, TimeUnit.SECONDS)
            if (generation.get() != gen) return@execute
            if (usePiper) {
                pending.incrementAndGet()
                updateSpeaking()
                try {
                    piper.speak(text, rate) { generation.get() != gen }
                    if (generation.get() == gen) unfinished.remove(item)
                } catch (e: Exception) {
                    unfinished.remove(item)
                    onStatus("Thorsten-Fehler: ${e.message ?: e.javaClass.simpleName}")
                } finally {
                    utteranceFinished()
                }
                return@execute
            }
            val tts = tts
            tts.setAudioAttributes(attributes)
            voice?.let { tts.voice = it }
            tts.setSpeechRate(rate)
            pending.incrementAndGet()
            val id = UUID.randomUUID().toString()
            itemsByUtterance[id] = item
            if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) {
                itemsByUtterance.remove(id)
                unfinished.remove(item)
                utteranceFinished()
            }
        }
    }

    fun finishReply() {
        val gen = generation.get()
        worker.execute {
            if (generation.get() == gen) {
                replyOpen = false
                updateSpeaking()
            }
        }
    }

    private fun utteranceFinished() {
        pending.updateAndGet { maxOf(0, it - 1) }
        updateSpeaking()
    }

    private fun updateSpeaking() {
        val now = replyOpen || pending.get() > 0
        if (now != speaking) {
            speaking = now
            onSpeaking(now)
        }
    }

    private fun playSilence(ms: Int) {
        val sampleRate = 24_000
        val samples = sampleRate * ms / 1000
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples * 2)
            .build()
        try {
            track.write(ShortArray(samples), 0, samples)
            track.play()
            Thread.sleep(ms.toLong())
            track.stop()
        } finally {
            track.release()
        }
    }

    /** Stops output and returns the sentences not spoken completely yet (the interrupted one first), to resume later. */
    fun stopAndTakeRemaining(): List<String> {
        val rest = synchronized(unfinished) { unfinished.map { it.text } }
        stop()
        return rest
    }

    fun stop() {
        unfinished.clear()
        itemsByUtterance.clear()
        generation.incrementAndGet()
        replyOpen = false
        tts.stop()
        if (usePiper) piper.stop()
        pending.set(0)
        updateSpeaking()
    }

    fun shutdown() {
        worker.shutdownNow()
        tts.shutdown()
        if (usePiper) piper.release()
    }
}
