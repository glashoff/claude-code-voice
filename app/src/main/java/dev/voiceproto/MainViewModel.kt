package dev.voiceproto

import android.app.Application
import android.os.Build
import android.speech.tts.Voice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SttEngine(val label: String) { WHISPER("Whisper"), ANDROID("Android"), CONTINUOUS("Durchgehend") }

enum class SttLanguage(val label: String, val whisperCode: String, val androidTag: String) {
    GERMAN("Deutsch", "de", "de-DE"),
    ENGLISH("Englisch", "en", "en-US"),
    AUTO("Automatisch (Whisper) / Deutsch", "auto", "de-DE"),
}

enum class Role { USER, CLAUDE, ERROR }

data class TranscriptEntry(val text: String, val meta: String, val role: Role = Role.USER) {
    val isError get() = role == Role.ERROR
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val whisper = WhisperEngine(app.filesDir)
    private val utterances = Channel<FloatArray>(Channel.UNLIMITED)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.GERMANY)
    private val settings = Settings(app)

    // --- Speech recognition state ---
    var engine by settings.enum("engine", SttEngine.CONTINUOUS)
        private set
    var language by settings.enum("language", SttLanguage.GERMAN)
    var whisperModel by settings.enum("whisperModel", WhisperModel.SMALL)
    var vocabularyPrompt by settings.string("vocabularyPrompt", "Commit, Pull Request, Deployment, Branch, Container, Claude")
    var preferOffline by settings.bool("preferOffline", false)
    var languageSwitch by settings.bool("languageSwitch", true)
    var continuousSupport by mutableStateOf("")
    var modelStatus by mutableStateOf("Kein Modell geladen.")
    var modelBusy by mutableStateOf(false)
    var listening by mutableStateOf(false)
    var userSpeaking by mutableStateOf(false)
    var pendingCount by mutableStateOf(0)
    var partialText by mutableStateOf("")
    val transcript = mutableStateListOf<TranscriptEntry>()

    // The chat survives app restarts: the newest entries are kept in app-private storage.
    private val transcriptFile = java.io.File(app.filesDir, "transcript.json")
    private val maxSavedEntries = 300

    init {
        runCatching {
            val saved = org.json.JSONArray(transcriptFile.readText())
            for (i in 0 until saved.length()) {
                val e = saved.getJSONObject(i)
                transcript += TranscriptEntry(e.getString("text"), e.getString("meta"), Role.valueOf(e.getString("role")))
            }
        }
        // Save shortly after the chat changed (a streaming reply changes it many times per second).
        viewModelScope.launch {
            androidx.compose.runtime.snapshotFlow { transcript.toList() }.collectLatest { entries ->
                kotlinx.coroutines.delay(1_000)
                withContext(Dispatchers.IO) { saveTranscript(entries) }
            }
        }
    }

    private fun saveTranscript(entries: List<TranscriptEntry>) {
        val json = org.json.JSONArray()
        entries.takeLast(maxSavedEntries).forEach {
            json.put(org.json.JSONObject().put("text", it.text).put("meta", it.meta).put("role", it.role.name))
        }
        // Write a temporary file and rename it, so a crash never leaves a half-written chat behind.
        val tmp = java.io.File(transcriptFile.path + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(transcriptFile)
    }

    // --- Speech output state ---
    var ttsText by settings.string("ttsText", "Hi! This is how a reply from Claude would sound. I just pushed the commit and opened a pull request.")
    var voices by mutableStateOf<List<Voice>>(emptyList())
    var selectedVoice by mutableStateOf<Voice?>(null)
        private set
    // Last chosen voice and TTS engine per language, so switching languages restores both.
    private var voiceNameEn by settings.string("voiceNameEn", "")
    private var voiceNameDe by settings.string("voiceNameDe", "")
    private var ttsEngineEn by settings.string("ttsEngineEn", "")
    private var ttsEngineDe by settings.string("ttsEngineDe", "")
    /** Active output language, "en" or "de". */
    var outputLanguage by settings.string("outputLanguage", "en")
        private set
    /** Installed TTS engines as (package, label); the first entry ("") stands for the system default. */
    var ttsEngines by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set
    /** Download/loading status of the in-app Thorsten voice; null when idle. */
    var ttsStatus by mutableStateOf<String?>(null)
        private set
    /** Engine package for the active language ("" = system default). */
    val ttsEngine get() = if (outputLanguage == "de") ttsEngineDe else ttsEngineEn

    /** Claude answers in the active output language. */
    val replyLanguage get() = if (outputLanguage == "de") "German" else "English"
    var speechRate by settings.float("speechRate", 1.0f)
    var muteWhileSpeaking by settings.bool("muteWhileSpeaking", false)
    var leadInMs by settings.int("leadInMs", 600)
    var outputSpeaking by mutableStateOf(false)

    // --- Claude over SSH ---
    var serverHost by settings.string("serverHost", BuildConfig.DEFAULT_SERVER_HOST)
    var serverPort by settings.string("serverPort", "22")
    var serverUser by settings.string("serverUser", BuildConfig.DEFAULT_SERVER_USER)
    var projectRoot by settings.string("projectRoot", "~/Projects")
    var allowedTools by settings.string("allowedTools", "Read,Glob,Grep,WebSearch,WebFetch")
    var claudeModel by settings.string("claudeModel", "")
    var project by settings.string("project", "")
        private set
    var sendToClaude by settings.bool("sendToClaude", true)
    var hostKeyFingerprint by settings.string("hostKeyFingerprint", "")
        private set
    var projects by mutableStateOf<List<String>>(emptyList())
    var publicKey by mutableStateOf("")
    var claudeStatus by mutableStateOf("")
    var claudeBusy by mutableStateOf(false)
    var sessions by mutableStateOf<List<SessionInfo>>(emptyList())
    var currentSessionTitle by mutableStateOf("")
    // One persistent Claude process per conversation; messages (also addenda while it works) go straight in.
    private var conversation: ClaudeRemote.Conversation? = null
    private var conversationKey = ""
    private var conversationSession: String? = null
    private val conversationLock = kotlinx.coroutines.sync.Mutex()
    /** Incremented whenever a process is started or closed; events of older processes are ignored. */
    private var conversationGeneration = 0

    // --- Permission requests: Claude asks, the app reads them aloud, the user decides by voice or button ---
    /** Pending requests, oldest first; the first one is shown and read aloud. */
    val permissionRequests = mutableStateListOf<PermissionRequest>()
    private var permissionExpiry: kotlinx.coroutines.Job? = null
    /** "Alles erlauben": approve every request until the current turn ends. */
    private var allowAllThisTurn = false
    /** Read each of Claude's tool calls aloud as a short progress update. */
    var announceSteps by settings.bool("announceSteps", true)
    /** Use the microphone of a connected headset (Bluetooth: call mode, see HeadsetRoute). */
    var headsetMic by settings.bool("headsetMic", true)
    /** Stop reading aloud as soon as the user starts speaking (Claude keeps working). */
    var bargeIn by settings.bool("bargeIn", true)
    /**
     * The user started speaking while a reply was read aloud: the sentences not spoken yet wait here and are read to
     * the end once the user has been silent for a moment. New sentences of the reply are collected here meanwhile.
     */
    private var pausedSpeech: MutableList<String>? = null
    private var resumeSpeechJob: kotlinx.coroutines.Job? = null
    /** Tool calls already announced in this turn, so a following permission question can stay short. */
    private val announcedToolUses = HashSet<String>()
    /** Request for which an unclear short answer already led to one follow-up question. */
    private var repromptedPermission: String? = null

    // --- Stop word and thinking sound ---
    var stopWords by settings.string("stopWords", "stopp, stop, halt")
    var thinkingSoundEnabled by settings.bool("thinkingSound", true)
    var thinkingVolume by settings.float("thinkingVolume", 0.25f)
    private val thinking = ThinkingSound()
    /** Set when a stop word was already handled from a partial result, so the final result is not handled twice. */
    private var stopHandled = false

    // --- Sending: utterances collect in a draft until a pause or the keyword ---
    var sendPauseSec by settings.float("sendPauseSec", 3f)
    var sendKeyword by settings.string("sendKeyword", "abschicken")
    var menuKeyword by settings.string("menuKeyword", "Menü")
    var draft by mutableStateOf("")
    var draftSecondsLeft by mutableStateOf<Float?>(null)
    /** "warte": the draft is not sent after the pause; it waits until the user speaks again. */
    var draftHeld by mutableStateOf(false)
        private set
    private var draftTimer: kotlinx.coroutines.Job? = null

    // --- Voice menu ---
    var menuActive by mutableStateOf(false)
    private val menu = VoiceMenu(this)
    private var menuTimeout: kotlinx.coroutines.Job? = null
    // Menu announcements always mute the mic (also "menu closed", spoken after leaving the menu).
    private var menuSpeech = false
    private var menuSpeechStarted = false
    private val remote = ClaudeRemote(
        keyDir = java.io.File(app.filesDir, "ssh"),
        pinnedHostKey = { hostKeyFingerprint },
        pinHostKey = { fp -> viewModelScope.launch { hostKeyFingerprint = fp } },
    )

    /** The microphone delivers no sound at all, e.g. a headset muted by flipping up its boom. */
    var micSilent by mutableStateOf(false)
        private set

    private val recorder = VoiceRecorder(
        onInputSilent = { silent -> viewModelScope.launch { micSilent = silent } },
        onSpeaking = { speaking -> viewModelScope.launch { userSpeaking = speaking; if (speaking) onUserActivity() } },
        onUtterance = { audio ->
            viewModelScope.launch { pendingCount++ }
            utterances.trySend(audio)
        },
    )

    private val androidStt = AndroidStt(
        context = app,
        onSpeaking = { userSpeaking = it },
        onPartial = { partialText = it; if (it.isNotEmpty()) { checkStopPartial(it); onUserActivity() } },
        onResult = { text, latency -> onRecognized(text, "Android · Ergebnis ${latency} ms nach Sprechende") },
        onError = { addEntry(it, "Android-Erkennung", isError = true) },
    )

    private val continuousStt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContinuousStt(
            context = app,
            onPartial = { text ->
                partialText = text
                userSpeaking = text.isNotEmpty()
                if (text.isNotEmpty()) { checkStopPartial(text); onUserActivity() }
            },
            onResult = { text, meta -> onRecognized(text, meta) },
            onError = { addEntry(it, "Durchgehend", isError = true) },
            onInputSilent = { silent -> viewModelScope.launch { micSilent = silent } },
        )
    } else null

    /** Quick settings (project, model, language, sessions) expanded on the main screen. */
    var quickSettingsOpen by settings.bool("quickSettingsOpen", true)

    /** Set once the microphone was started automatically on app launch (once per app process). */
    var autoStartDone = false
    private var startupAnnounced = false

    private val speaker: Speaker = Speaker(
        context = app,
        enginePackage = if (outputLanguage == "de") ttsEngineDe else ttsEngineEn,
        onReady = { pkg, list ->
            viewModelScope.launch {
                if (pkg != speaker.enginePackage) return@launch // a newer engine switch superseded this one
                val default = speaker.defaultEngine()
                ttsEngines = listOf("" to "Standard") + speaker.engines()
                    .map { (p, label) -> p to if (p == default) "$label (Standard)" else label }
                voices = list
                selectedVoice = pickVoice(outputLanguage)
                if (!startupAnnounced && list.isNotEmpty()) {
                    startupAnnounced = true
                    announceProject()
                }
            }
        },
        onSpeaking = { speaking -> viewModelScope.launch { onOutputSpeaking(speaking) } },
        onStatus = { status -> viewModelScope.launch { ttsStatus = status } },
    )

    init {
        // Transcribe recorded utterances one after another.
        viewModelScope.launch {
            for (audio in utterances) {
                val seconds = audio.size / VoiceRecorder.SAMPLE_RATE.toFloat()
                val start = System.currentTimeMillis()
                try {
                    val text = whisper.transcribe(audio, language.whisperCode, vocabularyPrompt.trim())
                    val ms = System.currentTimeMillis() - start
                    if (text.isNotBlank()) {
                        onRecognized(text, "Whisper · %.1f s Audio · erkannt in %.1f s".format(Locale.GERMANY, seconds, ms / 1000f))
                    }
                } catch (e: Exception) {
                    addEntry(e.message ?: e.toString(), "Whisper", isError = true)
                } finally {
                    pendingCount--
                }
            }
        }
        viewModelScope.launch {
            publicKey = try {
                withContext(Dispatchers.IO) { remote.publicKeyLine() }
            } catch (e: Exception) {
                "Fehler beim Erzeugen des Schlüssels: ${e.message ?: e}"
            }
        }
        if (whisper.isDownloaded(whisperModel)) loadModel()
        if (engine == SttEngine.CONTINUOUS) checkContinuousSupport()
        // Attach to a Claude run that kept working while the app was closed; it reports what happened meanwhile.
        if (project.isNotEmpty() && savedRun() != null) viewModelScope.launch { ensureConversation() }
        currentSessionTitle = settings.getRaw(sessionTitleKey())
    }

    private fun addEntry(text: String, meta: String, isError: Boolean = false) {
        val role = if (isError) Role.ERROR else Role.USER
        transcript.add(TranscriptEntry(text, "${timeFormat.format(Date())} · $meta", role))
    }

    /** Every recognized utterance, from any engine, ends up here. */
    private fun onRecognized(text: String, meta: String) {
        if (isStopWord(text)) {
            addEntry(text, "Stopp · $meta")
            if (!stopHandled) stopEverything(discardDraftIfIdle = true)
            stopHandled = false
            return
        }
        stopHandled = false
        val request = permissionRequests.firstOrNull()
        if (request != null && !menuActive) {
            when (permissionAnswer(text)) {
                PermissionAnswer.ALLOW -> { addEntry(text, "Erlaubt · $meta"); allowPermission(request); return }
                PermissionAnswer.ALLOW_ALL -> { addEntry(text, "Alles erlaubt · $meta"); allowAll(); return }
                PermissionAnswer.DENY -> { addEntry(text, "Abgelehnt · $meta"); denyPermission(request); return }
                PermissionAnswer.OTHER -> if (text.trim().split(Regex("\\s+")).size <= 2 && repromptedPermission != request.id) {
                    // Short and unclear (e.g. "ja" recognized as "hier"): ask once more instead of declining.
                    repromptedPermission = request.id
                    addEntry(text, "Unklar, frage nach · $meta")
                    speakQueued(listOf(if (replyLanguage == "German") "Erlauben, ja oder nein? Am sichersten sagst du: bestätige."
                        else "Allow it, yes or no? Saying confirm works best."), force = true)
                    speaker.finishReply()
                    replySpeaking = false
                    return
                }
                // Anything else is not an answer: it goes into the draft like all speech and respects the send pause;
                // sending the draft then declines the pending request (see sendDraft).
            }
        }
        if (!menuActive && draft.isNotEmpty()) {
            val spoken = VoiceMenu.normalize(text)
            if (spoken in setOf("warte", "warten", "warte mal", "wart mal", "pause", "moment", "moment mal", "einen moment")) {
                // Hold the draft: no countdown until the user continues speaking.
                draftTimer?.cancel()
                draftSecondsLeft = null
                draftHeld = true
                return
            }
            if (spoken in setOf("löschen", "lösch das", "lösche das", "lösch", "entwurf löschen", "alles löschen")) {
                // Only the draft goes; Claude's running work and speech output continue.
                discardDraft()
                claudeStatus = "Entwurf gelöscht."
                return
            }
        }
        when {
            menuActive -> {
                addEntry(text, "Menü · $meta")
                handleMenuCommand(text)
            }
            VoiceMenu.normalize(text) == VoiceMenu.normalize(menuKeyword) -> {
                addEntry(text, "Menü · $meta")
                enterMenu()
            }
            !sendToClaude || project.isEmpty() -> addEntry(text, meta)
            else -> appendToDraft(text)
        }
    }

    // --- Stop ---

    private fun isStopWord(text: String): Boolean {
        val words = stopWords.split(',').map { VoiceMenu.normalize(it) }.filter { it.isNotEmpty() }.toSet()
        val spoken = VoiceMenu.normalize(text).split(' ').filter { it.isNotEmpty() }
        // "Stopp", "stop stop", "Stopp!" – but not sentences that merely contain the word.
        return spoken.isNotEmpty() && spoken.size <= 3 && spoken.all { it in words }
    }

    /** React to a stop word already in the live preview, so speech output stops without waiting for the final result. */
    private fun checkStopPartial(text: String) {
        if (stopHandled || !(outputSpeaking || claudeBusy)) return
        if (isStopWord(text)) {
            stopHandled = true
            stopEverything(discardDraftIfIdle = false)
        }
    }

    /** Stops speech output and the thinking sound, declines pending requests and interrupts Claude's current turn. */
    fun stopEverything(discardDraftIfIdle: Boolean = false) {
        val wasActive = outputSpeaking || claudeBusy
        speaker.stop()
        thinking.stop()
        replySpeaking = false
        pausedSpeech = null
        resumeSpeechJob?.cancel()
        permissionRequests.toList().forEach { conversation?.deny(it, "The user stopped this.") }
        permissionRequests.clear()
        permissionExpiry?.cancel()
        if (claudeBusy) {
            interrupted = true
            conversation?.interrupt()
        }
        if (!wasActive && discardDraftIfIdle && draft.isNotEmpty()) {
            discardDraft()
            claudeStatus = "Entwurf verworfen."
        } else if (wasActive) {
            claudeStatus = "Gestoppt."
        }
    }

    // --- Draft ---

    private fun appendToDraft(text: String) {
        draftHeld = false
        draft = (draft + " " + text).trim()
        val keyword = sendKeyword.trim()
        if (keyword.isNotEmpty()) {
            val pattern = Regex("""(?i)[\s,.]*\b${Regex.escape(keyword)}\W*$""")
            if (pattern.containsMatchIn(draft)) {
                draft = draft.replace(pattern, "").trim()
                sendDraft()
                return
            }
        }
        restartDraftTimer()
    }

    /** The user is (still) speaking: postpone sending. */
    private fun onUserActivity() {
        if (bargeIn && !menuSpeech && (outputSpeaking || pausedSpeech != null)) {
            if (outputSpeaking) {
                val rest = speaker.stopAndTakeRemaining()
                replySpeaking = false
                pausedSpeech = (rest + pausedSpeech.orEmpty()).toMutableList()
            }
            // Resume once the user has been quiet for a moment; every new bit of speech postpones it.
            resumeSpeechJob?.cancel()
            resumeSpeechJob = viewModelScope.launch {
                kotlinx.coroutines.delay(1_500)
                resumePausedSpeech()
            }
        }
        // While held, only a recognized utterance (appendToDraft) resumes the countdown, not noise in the preview.
        if (draft.isNotEmpty() && !menuActive && !draftHeld) restartDraftTimer()
    }

    private fun restartDraftTimer() {
        draftTimer?.cancel()
        draftSecondsLeft = null
        if (sendPauseSec <= 0f || draft.isEmpty()) return
        draftTimer = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + (sendPauseSec * 1000).toLong()
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                draftSecondsLeft = left / 1000f
                kotlinx.coroutines.delay(100)
            }
            draftSecondsLeft = null
            sendDraft()
        }
    }

    fun sendDraft() {
        draftHeld = false
        draftTimer?.cancel()
        draftSecondsLeft = null
        val text = draft.trim()
        draft = ""
        if (text.isEmpty()) return
        if (permissionRequests.isNotEmpty()) {
            // Claude is waiting for an answer: the message declines the pending actions and is passed on as well.
            permissionRequests.toList().forEach { conversation?.deny(it, "The user did not approve this action and said instead: $text") }
            permissionRequests.clear()
            permissionExpiry?.cancel()
            addEntry(text, "an Claude gesendet (Erlaubnis abgelehnt)")
        } else {
            addEntry(text, if (claudeBusy) "an Claude gesendet (Nachtrag)" else "an Claude gesendet")
        }
        sendToClaude(text)
    }

    /** The draft was edited with the keyboard: hold it (no countdown) until it is sent or continued by voice. */
    fun editDraft(text: String) {
        draft = text
        draftTimer?.cancel()
        draftSecondsLeft = null
        draftHeld = text.isNotEmpty()
    }

    fun discardDraft() {
        draftHeld = false
        draftTimer?.cancel()
        draftSecondsLeft = null
        draft = ""
    }

    // --- Voice menu ---

    private fun enterMenu() {
        draftTimer?.cancel()
        draftSecondsLeft = null
        menuActive = true
        speakMenu("Menü. Was möchtest du ändern? Sag Hilfe für eine Liste, oder Fertig.")
        restartMenuTimeout()
    }

    fun exitMenu(spokenFeedback: Boolean = true) {
        menuActive = false
        menuTimeout?.cancel()
        if (spokenFeedback) speakMenu("Menü geschlossen.")
        restartDraftTimer()
    }

    private fun handleMenuCommand(text: String) {
        restartMenuTimeout()
        if (menu.isExit(text)) return exitMenu()
        viewModelScope.launch {
            val answer = try {
                menu.handle(text)
            } catch (e: Exception) {
                "Fehler: ${e.message ?: e}"
            }
            speakMenu(answer)
        }
    }

    private fun restartMenuTimeout() {
        menuTimeout?.cancel()
        menuTimeout = viewModelScope.launch {
            kotlinx.coroutines.delay(60_000)
            if (menuActive) exitMenu()
        }
    }

    /** Menu feedback uses a German voice, independent of the (English) voice used for Claude. */
    private fun speakMenu(text: String) {
        val german = voices.firstOrNull { it.lang == "de" && !it.isNetworkConnectionRequired }
            ?: voices.firstOrNull { it.lang == "de" }
        addEntry(text, "Menü-Antwort")
        menuSpeech = true
        menuSpeechStarted = false
        speaker.speak(text, german ?: selectedVoice, 1.0f, leadInMs)
    }

    fun loadModel() {
        if (modelBusy) return
        val model = whisperModel
        modelBusy = true
        viewModelScope.launch {
            try {
                if (!whisper.isDownloaded(model)) {
                    whisper.download(model) { p ->
                        viewModelScope.launch { modelStatus = "Lade ${model.label} … ${(p * 100).toInt()} %" }
                    }
                }
                modelStatus = "Initialisiere ${model.label} …"
                whisper.load(model)
                val cpu = withContext(Dispatchers.Default) { whisper.systemInfo() }
                modelStatus = "${model.label} bereit. ${cpuFeatures(cpu)}"
            } catch (e: Exception) {
                modelStatus = "Fehler: ${e.message}"
            } finally {
                modelBusy = false
            }
        }
    }

    private fun cpuFeatures(info: String) =
        listOf("NEON", "DOTPROD", "FP16_VA").filter { "$it = 1" in info }.joinToString(prefix = "CPU: ").ifEmpty { "" }

    fun canListen() = when (engine) {
        SttEngine.WHISPER -> whisper.loadedModel != null
        SttEngine.ANDROID -> androidStt.isAvailable()
        SttEngine.CONTINUOUS -> continuousStt != null
    }

    fun checkContinuousSupport() {
        val stt = continuousStt ?: run { continuousSupport = "Benötigt Android 13 oder neuer."; return }
        continuousSupport = "Prüfe …"
        stt.checkSupport(language.androidTag) { continuousSupport = it }
    }

    fun downloadContinuousLanguage() {
        continuousStt?.downloadLanguage(language.androidTag)
        continuousSupport = "Download angestoßen – Android zeigt ggf. einen Dialog. Danach erneut prüfen."
    }

    fun startListening() {
        // Foreground service keeps mic, CPU and Wi-Fi available while the screen is locked.
        ListeningService.onStopRequested = { viewModelScope.launch { stopListening() } }
        ListeningService.start(getApplication())
        val headset = if (headsetMic) HeadsetRoute.enable(getApplication()) else null
        if (headset != null) addEntry("Mikrofon des Headsets: $headset", "App")
        listening = true
        viewModelScope.launch {
            // The Bluetooth voice link needs a moment before its microphone delivers audio.
            if (HeadsetRoute.callMode) kotlinx.coroutines.delay(700)
            if (listening) startRecognition()
        }
    }

    private fun startRecognition() {
        when (engine) {
            SttEngine.WHISPER -> {
                recorder.muted = outputSpeaking && muteWhileSpeaking
                recorder.start(viewModelScope)
            }
            SttEngine.ANDROID -> {
                androidStt.paused = outputSpeaking && muteWhileSpeaking
                androidStt.start(language.androidTag, preferOffline)
            }
            SttEngine.CONTINUOUS -> continuousStt?.let {
                it.silenced = outputSpeaking && muteWhileSpeaking
                it.start(viewModelScope, language.androidTag, languageSwitch)
            }
        }
    }

    fun stopListening() {
        ListeningService.stop(getApplication())
        HeadsetRoute.disable(getApplication())
        micSilent = false
        recorder.stop()
        androidStt.stop()
        continuousStt?.stop()
        listening = false
        userSpeaking = false
        partialText = ""
    }

    fun switchEngine(newEngine: SttEngine) {
        if (newEngine == engine) return
        val wasListening = listening
        stopListening()
        engine = newEngine
        if (newEngine == SttEngine.CONTINUOUS) checkContinuousSupport()
        if (wasListening && canListen()) startListening()
    }

    fun clearTranscript() = transcript.clear()

    fun speak() {
        val text = ttsText.trim()
        if (text.isNotEmpty()) speaker.speak(text, selectedVoice, speechRate, leadInMs)
    }

    fun stopSpeaking() = speaker.stop()

    // --- Claude over SSH ---

    private fun serverConfig() = ServerConfig(
        host = serverHost.trim(), port = serverPort.trim().toIntOrNull() ?: 22, user = serverUser.trim(),
        projectRoot = projectRoot.trim(), allowedTools = allowedTools, model = claudeModel,
    )

    fun loadProjects() {
        claudeStatus = "Verbinde …"
        viewModelScope.launch {
            try {
                projects = remote.projects(serverConfig())
                claudeStatus = "Verbunden. ${projects.size} Projekte gefunden."
                if (project !in projects) project = ""
            } catch (e: Exception) {
                remote.disconnect()
                claudeStatus = "Fehler: ${e.message ?: e}"
            }
        }
    }

    /** Says once at start-up which project is loaded (the last one used is remembered). */
    private fun announceProject() {
        val de = replyLanguage == "German"
        // Hyphens and underscores read oddly; speak the name as words.
        val name = project.replace('-', ' ').replace('_', ' ')
        val text = when {
            project.isEmpty() -> if (de) "Kein Projekt gewählt." else "No project selected."
            de -> "Projekt $name geladen."
            else -> "Project $name loaded."
        }
        addEntry(text, "App")
        speaker.speak(text, selectedVoice, speechRate, leadInMs)
    }

    fun selectProject(name: String) {
        closeConversation()
        project = name
        sessions = emptyList()
        currentSessionTitle = settings.getRaw(sessionTitleKey())
    }

    /** Used by the voice menu: loads projects and waits for the result. */
    suspend fun loadProjectsAndWait() {
        try {
            projects = remote.projects(serverConfig())
        } catch (e: Exception) {
            remote.disconnect()
            claudeStatus = "Fehler: ${e.message ?: e}"
        }
    }

    private fun sessionKey() = "session:$serverHost:$project"
    /** Id of the conversation continued in the current project; empty for a new one. */
    fun currentSessionId() = settings.getRaw(sessionKey())
    private fun sessionTitleKey() = "sessionTitle:$serverHost:$project"

    fun newConversation() {
        closeConversation()
        stopSavedRun()
        settings.putRaw(sessionKey(), "")
        settings.putRaw(sessionTitleKey(), "")
        currentSessionTitle = ""
        claudeStatus = "Neues Gespräch in $project."
    }

    fun loadSessions() {
        viewModelScope.launch { loadSessionsAndWait() }
    }

    suspend fun loadSessionsAndWait(): List<SessionInfo> {
        if (project.isEmpty()) return emptyList()
        claudeStatus = "Lade Sitzungen …"
        try {
            sessions = remote.sessions(serverConfig(), project)
            claudeStatus = "${sessions.size} Sitzungen gefunden."
        } catch (e: Exception) {
            remote.disconnect()
            claudeStatus = "Fehler: ${e.message ?: e}"
        }
        return sessions
    }

    fun resumeSession(session: SessionInfo) {
        closeConversation()
        stopSavedRun()
        settings.putRaw(sessionKey(), session.id)
        settings.putRaw(sessionTitleKey(), session.title)
        currentSessionTitle = session.title
        claudeStatus = "Sitzung „${session.title}“ geladen."
    }

    fun forgetHostKey() {
        closeConversation()
        hostKeyFingerprint = ""
        remote.disconnect()
    }

    fun sendTypedText() {
        val text = ttsText.trim()
        if (text.isNotEmpty() && project.isNotEmpty()) {
            transcript.add(TranscriptEntry(text, "${timeFormat.format(Date())} · getippt", Role.USER))
            sendToClaude(text)
        }
    }

    // --- Claude conversation ---

    // State of the turn currently being answered.
    private var replyEntry = -1
    private val reply = StringBuilder()
    private val pendingSpeech = StringBuilder()
    /** A spoken reply is open in the speaker (sentences are queued into it). */
    private var replySpeaking = false
    private var turnStartedAt = 0L
    private var interrupted = false

    /** Sends [text] to Claude, starting (or reusing) the conversation's process. */
    private fun sendToClaude(text: String) {
        if (project.isEmpty()) return
        val hint = if (replyLanguage == "German") "[Antwortsprache: Deutsch]" else "[Reply language: English]"
        if (!claudeBusy) beginTurn()
        viewModelScope.launch {
            val c = ensureConversation() ?: run { endTurn(); return@launch }
            c.send("$hint $text")
        }
    }

    // The Claude run of the current project survives lost connections and app restarts: its id, the processed output
    // position and the settings it was started with are stored, so the app can attach to it again.
    private fun runKey() = "run:$serverHost:$project"
    private var conversationRunKey = ""
    private data class SavedRun(val id: String, val offset: Long, val configKey: String)
    private fun savedRun(): SavedRun? = settings.getRaw(runKey()).split('|').takeIf { it.size == 3 }
        ?.let { SavedRun(it[0], it[1].toLongOrNull() ?: 0L, it[2]) }

    private suspend fun ensureConversation(): ClaudeRemote.Conversation? = conversationLock.withLock {
        val config = serverConfig()
        val savedSession = settings.getRaw(sessionKey()).ifEmpty { null }
        val key = "$config|$project"
        conversation?.let { c ->
            if (c.isOpen && conversationKey == key && conversationSession == savedSession) return@withLock c
        }
        closeConversation(endBusyTurn = false)
        try {
            val generation = ++conversationGeneration
            val onEvent: suspend (ClaudeEvent) -> Unit = { event ->
                withContext(Dispatchers.Main) { if (generation == conversationGeneration) onClaudeEvent(event) }
            }
            val rk = runKey()
            val configKey = key.hashCode().toString()
            val target = remote.target(config, project)
            if (target.container) {
                claudeStatus = "Starte den Container des Projekts …"
                remote.ensureContainer(config, target)
            }
            val saved = savedRun()
            val (id, offset) = if (saved != null && saved.configKey == configKey && remote.isAlive(config, target, saved.id)) {
                claudeStatus = "Verbinde mit der laufenden Sitzung …"
                saved.id to saved.offset
            } else {
                // A stale run (other settings, or already ended) is cleaned up first.
                saved?.let { runCatching { remote.stop(config, target, it.id) } }
                claudeStatus = if (target.container) "Starte Claude im Container …" else "Starte Claude …"
                remote.start(config, target, savedSession) to 0L
            }
            settings.putRaw(rk, "$id|$offset|$configKey")
            val c = remote.attach(viewModelScope, config, target, id, offset, onEvent) { off ->
                // Only while this run is still the stored one (not after it was closed).
                if (settings.getRaw(rk).startsWith("$id|")) settings.putRaw(rk, "$id|$off|$configKey")
            }
            if (generation != conversationGeneration) return@withLock null // ended right away (see Closed)
            conversation = c
            conversationKey = key
            conversationRunKey = rk
            conversationSession = savedSession
            c
        } catch (e: Exception) {
            remote.disconnect()
            addEntry(e.message ?: e.toString(), "Claude/SSH", isError = true)
            claudeStatus = "Fehler."
            null
        }
    }

    /** Ends the Claude process (session or project change, app end). */
    private fun closeConversation(endBusyTurn: Boolean = true) {
        val c = conversation ?: return
        conversation = null
        conversationGeneration++
        conversationKey = ""
        settings.putRaw(conversationRunKey, "")
        permissionRequests.clear()
        permissionExpiry?.cancel()
        if (endBusyTurn && claudeBusy) endTurn()
        Thread { c.close() }.start()
    }

    /** "Sitzung beenden": stops Claude on the computer; the conversation is kept and continues with the next message. */
    fun endRun() {
        closeConversation()
        stopSavedRun()
        claudeStatus = "Claude auf dem Rechner beendet."
    }

    /** Ends a run of the current project that the app is not attached to (e.g. left over from before an app restart). */
    private fun stopSavedRun() {
        val saved = savedRun() ?: return
        settings.putRaw(runKey(), "")
        val config = serverConfig()
        val project = project
        viewModelScope.launch { runCatching { remote.stop(config, remote.target(config, project), saved.id) } }
    }

    /** A short notice from the app itself: shown in the chat and read aloud. */
    private fun notify(text: String) {
        addEntry(text, "App")
        speakQueued(listOf(text), force = true)
        if (!claudeBusy) {
            speaker.finishReply()
            replySpeaking = false
        }
    }

    /** Reads the rest of an interrupted reply to the end. */
    private fun resumePausedSpeech() {
        val rest = pausedSpeech ?: return
        pausedSpeech = null
        speakQueued(rest)
        // If Claude is done, nothing else will close this reply.
        if (!claudeBusy && replySpeaking) {
            speaker.finishReply()
            replySpeaking = false
        }
    }

    private fun beginTurn() {
        claudeBusy = true
        interrupted = false
        allowAllThisTurn = false
        turnStartedAt = System.currentTimeMillis()
        claudeStatus = "Claude denkt nach …"
        if (thinkingSoundEnabled && !outputSpeaking) thinking.start(thinkingVolume)
    }

    private fun endTurn() {
        thinking.stop()
        if (replySpeaking) {
            speaker.finishReply()
            replySpeaking = false
        }
        replyEntry = -1
        reply.clear()
        pendingSpeech.clear()
        allowAllThisTurn = false
        announcedToolUses.clear()
        claudeBusy = false
    }

    private fun updateReplyEntry(meta: String) {
        if (replyEntry in transcript.indices) {
            transcript[replyEntry] = TranscriptEntry(reply.toString().trim().ifEmpty { "…" }, meta, Role.CLAUDE)
        }
    }

    /** Queues [sentences] for speech; opens a reply without cutting off speech that is still playing. */
    /** [force]: questions the user must hear (permission requests) are spoken even while the reply is paused. */
    private fun speakQueued(sentences: List<String>, force: Boolean = false) {
        if (sentences.isEmpty()) return
        pausedSpeech?.let { if (!force) { it.addAll(sentences); return } }
        if (!replySpeaking) {
            thinking.stop()
            // The thinking sound already woke up the headset, so the lead-in can be skipped then.
            speaker.startReply(selectedVoice, speechRate, if (thinkingSoundEnabled || outputSpeaking) 0 else leadInMs, interrupt = false)
            replySpeaking = true
        }
        sentences.forEach { speaker.enqueue(it) }
    }

    private fun onClaudeEvent(event: ClaudeEvent) {
        when (event) {
            is ClaudeEvent.Session -> {
                conversationSession = event.id
                if (settings.getRaw(sessionKey()) != event.id) {
                    settings.putRaw(sessionKey(), event.id)
                    if (currentSessionTitle.isEmpty()) {
                        val title = transcript.lastOrNull { it.role == Role.USER }?.text?.take(60) ?: ""
                        settings.putRaw(sessionTitleKey(), title)
                        currentSessionTitle = title
                    }
                }
            }
            is ClaudeEvent.TextDelta -> {
                if (interrupted) return
                if (!claudeBusy) beginTurn() // Claude continues on its own, e.g. after a background task finished
                if (replyEntry !in transcript.indices) {
                    transcript.add(TranscriptEntry("…", "${timeFormat.format(Date())} · Claude", Role.CLAUDE))
                    replyEntry = transcript.lastIndex
                }
                reply.append(event.text)
                pendingSpeech.append(event.text)
                speakQueued(splitSpeakable(pendingSpeech, final = false))
                updateReplyEntry("${timeFormat.format(Date())} · Claude schreibt …")
                claudeStatus = "Claude antwortet …"
            }
            is ClaudeEvent.ToolUse -> {
                claudeStatus = "Claude nutzt ${event.name} …"
                // Separate text before and after a tool call in the transcript.
                if (reply.isNotEmpty() && !reply.endsWith("\n")) reply.append("\n")
            }
            is ClaudeEvent.Permission -> onPermissionRequest(event.request)
            is ClaudeEvent.Steps -> if (announceSteps && !interrupted) {
                val texts = event.steps.mapNotNull { step -> describeStep(step)?.also { announcedToolUses += step.id } }
                if (texts.isNotEmpty()) {
                    // Keep the order: text Claude wrote before the tool call is spoken first.
                    speakQueued(splitSpeakable(pendingSpeech, final = true) + texts)
                    // Close the reply so the thinking sound can resume once the update has been spoken.
                    speaker.finishReply()
                    replySpeaking = false
                }
            }
            is ClaudeEvent.PermissionCancelled -> {
                permissionRequests.removeAll { it.id == event.id }
                if (permissionRequests.isEmpty()) permissionExpiry?.cancel()
            }
            is ClaudeEvent.Done -> {
                if (!interrupted) {
                    if (reply.isEmpty() && event.text.isNotEmpty() && !event.isError) {
                        reply.append(event.text)
                        pendingSpeech.append(event.text)
                        transcript.add(TranscriptEntry("…", "", Role.CLAUDE))
                        replyEntry = transcript.lastIndex
                    }
                    speakQueued(splitSpeakable(pendingSpeech, final = true))
                }
                val secs = (System.currentTimeMillis() - turnStartedAt) / 1000f
                updateReplyEntry("${timeFormat.format(Date())} · Claude · " +
                    if (interrupted) "abgebrochen" else "%.1f s".format(Locale.GERMANY, secs))
                claudeStatus = when {
                    interrupted -> "Gestoppt."
                    event.isError -> "Claude meldet einen Fehler."
                    else -> "Bereit."
                }
                permissionRequests.clear()
                permissionExpiry?.cancel()
                endTurn()
                interrupted = false
            }
            is ClaudeEvent.Error -> {
                addEntry(event.message, "Claude", isError = true)
                claudeStatus = "Fehler."
            }
            is ClaudeEvent.ConnectionLost -> {
                thinking.stop()
                claudeStatus = "Verbindung verloren – verbinde neu …"
                notify(if (replyLanguage == "German") "Verbindung unterbrochen. Ich arbeite auf dem Rechner weiter."
                    else "Connection lost. I keep working on the computer.")
            }
            is ClaudeEvent.Reconnected -> {
                claudeStatus = if (claudeBusy) "Claude arbeitet …" else "Bereit."
                notify(if (replyLanguage == "German") "Verbindung wieder da." else "Connection is back.")
                // A question asked while the connection was gone is asked again.
                permissionRequests.firstOrNull()?.let { announcePermission(it) }
                    ?: run { if (claudeBusy && thinkingSoundEnabled && !outputSpeaking) thinking.start(thinkingVolume) }
            }
            is ClaudeEvent.Closed -> {
                // Also reached if the process ends before attach() returned; then conversation is still null.
                settings.putRaw(conversationRunKey.ifEmpty { runKey() }, "")
                // Not stopped by the app: e.g. with the stop script at the computer.
                if (event.error == null) claudeStatus = "Claude wurde auf dem Rechner beendet."
                conversation = null
                conversationGeneration++
                conversationKey = ""
                permissionRequests.clear()
                if (claudeBusy) endTurn()
                event.error?.let {
                    remote.disconnect()
                    addEntry(it, "Claude/SSH", isError = true)
                    claudeStatus = "Fehler."
                }
            }
        }
    }

    // --- Permission requests ---

    private enum class PermissionAnswer { ALLOW, ALLOW_ALL, DENY, OTHER }

    private fun permissionAnswer(text: String): PermissionAnswer {
        val words = VoiceMenu.normalize(text).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 4) return PermissionAnswer.OTHER
        val filler = setOf("ich", "bitte", "okay", "ok", "please", "das", "es", "diese", "aktion", "für", "den", "auftrag")
        fun all(predicate: (String) -> Boolean) = words.all { predicate(it) || it in filler }
        val allWords = setOf("alles", "alle", "all")
        val yes = { w: String -> w.startsWith("bestätig") || w.startsWith("confirm") || w.startsWith("erlaub") || w == "ja" || w == "yes" || w == "allow" }
        val no = { w: String -> w == "nein" || w == "no" || w.startsWith("ablehn") || w == "deny" || w.startsWith("verbiet") }
        return when {
            words.any { it in allWords } && all { yes(it) || it in allWords } -> PermissionAnswer.ALLOW_ALL
            words.any(no) && all(no) -> PermissionAnswer.DENY
            words.any(yes) && all(yes) -> PermissionAnswer.ALLOW
            else -> PermissionAnswer.OTHER
        }
    }

    private fun onPermissionRequest(request: PermissionRequest) {
        if (allowAllThisTurn) {
            conversation?.allow(request)
            addEntry(describePermission(request, forSpeech = false), "Automatisch erlaubt (alles erlaubt)")
            return
        }
        permissionRequests.add(request)
        if (permissionRequests.size == 1) announcePermission(request)
    }

    /** Reads the request aloud; the 3-minute answer window starts once speech output has finished. */
    private fun announcePermission(request: PermissionRequest) {
        thinking.stop()
        claudeStatus = "Wartet auf deine Erlaubnis."
        if (!interrupted) speakQueued(splitSpeakable(pendingSpeech, final = true))
        val ask = if (replyLanguage == "German") "Erlauben?" else "Allow it?"
        // If the step was just announced, the question alone is enough.
        val question = if (request.toolUseId in announcedToolUses) ask else describePermission(request, forSpeech = true) + " " + ask
        speakQueued(listOf(question), force = true)
        // Close the reply so that "speaking" ends after the question; further text opens a new one.
        speaker.finishReply()
        replySpeaking = false
        permissionExpiry?.cancel()
        permissionExpiry = viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            androidx.compose.runtime.snapshotFlow { outputSpeaking }.first { !it }
            kotlinx.coroutines.delay(180_000)
            if (permissionRequests.firstOrNull()?.id == request.id) {
                addEntry("Keine Antwort innerhalb von 3 Minuten.", "Abgelehnt")
                denyPermission(request, "The user did not answer within 3 minutes.")
            }
        }
    }

    fun allowPermission(request: PermissionRequest = permissionRequests.first()) {
        conversation?.allow(request)
        nextPermission(request)
    }

    fun denyPermission(request: PermissionRequest = permissionRequests.first(), message: String = "The user declined this action.") {
        conversation?.deny(request, message)
        nextPermission(request)
    }

    fun allowAll() {
        allowAllThisTurn = true
        permissionRequests.toList().forEach { conversation?.allow(it) }
        permissionRequests.clear()
        permissionExpiry?.cancel()
        afterPermissionsAnswered()
    }

    private fun nextPermission(answered: PermissionRequest) {
        permissionRequests.removeAll { it.id == answered.id }
        permissionExpiry?.cancel()
        permissionRequests.firstOrNull()?.let { announcePermission(it) } ?: afterPermissionsAnswered()
    }

    private fun afterPermissionsAnswered() {
        if (claudeBusy) {
            claudeStatus = "Claude arbeitet …"
            if (thinkingSoundEnabled && !outputSpeaking) thinking.start(thinkingVolume)
        }
    }

    /** Spoken progress update for a tool call, in the reply language; null for steps not worth announcing. */
    private fun describeStep(step: ToolStep): String? {
        val de = replyLanguage == "German"
        val input = step.input
        val file = input.optString("file_path").ifEmpty { input.optString("notebook_path") }.substringAfterLast('/')
        return when (step.tool) {
            // No file names: they are useless when heard (the transcript shows the details).
            "Read" -> if (de) "Ich lese eine Datei." else "Reading a file."
            "Write" -> if (de) "Ich schreibe eine Datei." else "Writing a file."
            "Edit", "MultiEdit", "NotebookEdit" -> if (de) "Ich ändere eine Datei." else "Editing a file."
            "Glob", "Grep" -> if (de) "Ich suche im Projekt." else "Searching the project."
            "Bash" -> input.optString("description").takeIf { it.isNotBlank() }?.let { (if (de) "Befehl: " else "Command: ") + it + "." }
                ?: if (de) "Ich führe einen Befehl aus." else "Running a command."
            "WebSearch" -> if (de) "Ich suche im Netz." else "Searching the web."
            "WebFetch" -> if (de) "Ich rufe eine Webseite ab." else "Fetching a web page."
            "Task", "Agent" -> if (de) "Ich starte einen Hilfsagenten." else "Starting a helper agent."
            "TodoWrite", "TaskCreate", "TaskUpdate", "ToolSearch" -> null
            else -> if (de) "Ich arbeite weiter." else "Still working."
        }
    }

    /** A short description of what Claude wants to do: spoken, or with details for the transcript and the card. */
    fun describePermission(request: PermissionRequest, forSpeech: Boolean): String {
        val german = replyLanguage == "German"
        val input = request.input
        val file = input.optString("file_path").ifEmpty { input.optString("notebook_path") }
        // Spoken: no paths or file names; the card shows them.
        val target = if (forSpeech) (if (german) "eine Datei" else "a file") else (if (german) "die Datei $file" else "the file $file")
        return when (request.tool) {
            "Write" -> if (german) "Claude will $target schreiben." else "Claude wants to write $target."
            "Edit", "MultiEdit", "NotebookEdit" -> if (german) "Claude will $target ändern." else "Claude wants to edit $target."
            "Bash" -> {
                val description = input.optString("description")
                val command = input.optString("command")
                when {
                    !forSpeech -> (if (german) "Claude will ausführen: " else "Claude wants to run: ") + command
                    description.isNotEmpty() -> (if (german) "Claude will einen Befehl ausführen: " else "Claude wants to run a command: ") + description + "."
                    else -> if (german) "Claude will einen Befehl ausführen." else "Claude wants to run a command."
                }
            }
            else -> if (!forSpeech) (if (german) "Claude will das Werkzeug ${request.tool} benutzen." else "Claude wants to use the tool ${request.tool}.")
                else if (german) "Claude will ein weiteres Werkzeug benutzen." else "Claude wants to use another tool."
        }
    }

    /**
     * Takes complete sentences out of [buffer] for speech output (all remaining text if [final]) and strips
     * Markdown symbols a TTS engine would read aloud.
     */
    private fun splitSpeakable(buffer: StringBuilder, final: Boolean): List<String> {
        val result = ArrayList<String>()
        val boundary = Regex("""(?<=[.!?:;])\s+|\n+""")
        while (true) {
            val match = boundary.find(buffer) ?: break
            result += buffer.substring(0, match.range.first)
            buffer.delete(0, match.range.last + 1)
        }
        if (final && buffer.isNotBlank()) {
            result += buffer.toString()
            buffer.clear()
        }
        return result
            .map { it.replace(Regex("""[*_#`>|]+"""), "").trim() }
            .filter { it.isNotEmpty() }
    }

    /** The remembered voice for [language] in the current engine, else its first (preferably offline) voice. */
    private fun pickVoice(language: String): Voice? {
        val remembered = if (language == "de") voiceNameDe else voiceNameEn
        return voices.firstOrNull { it.name == remembered }
            ?: voices.firstOrNull { it.lang == language && !it.isNetworkConnectionRequired }
            ?: voices.firstOrNull { it.lang == language }
            ?: voices.firstOrNull()
    }

    /**
     * Switches the output (and thereby reply) language to [language] ("de"/"en") and restores the engine and voice
     * last used for it. If the engine changes, the voice is picked once the engine is ready.
     */
    fun switchLanguage(language: String) {
        outputLanguage = language
        if (ttsEngine != speaker.enginePackage) {
            voices = emptyList()
            selectedVoice = null
            speaker.setEngine(ttsEngine)
        } else {
            selectedVoice = pickVoice(language)
        }
    }

    /** Selects the TTS engine for the active language. */
    fun selectEngine(pkg: String) {
        if (outputLanguage == "de") ttsEngineDe = pkg else ttsEngineEn = pkg
        if (pkg != speaker.enginePackage) {
            voices = emptyList()
            selectedVoice = null
            speaker.setEngine(pkg)
        }
    }

    fun selectVoice(voice: Voice) {
        val language = voice.lang
        if (language == "de" || language == "en") {
            outputLanguage = language
            if (language == "de") {
                voiceNameDe = voice.name
                ttsEngineDe = speaker.enginePackage
            } else {
                voiceNameEn = voice.name
                ttsEngineEn = speaker.enginePackage
            }
        }
        selectedVoice = voice
    }

    private fun onOutputSpeaking(speaking: Boolean) {
        outputSpeaking = speaking
        if (menuSpeech) {
            if (speaking) menuSpeechStarted = true else if (menuSpeechStarted) menuSpeech = false
        }
        val mute = speaking && (muteWhileSpeaking || menuActive || menuSpeech)
        recorder.muted = mute
        continuousStt?.silenced = mute
        androidStt.paused = mute && engine == SttEngine.ANDROID && listening
        // Claude is still working after a spoken progress update: resume the thinking sound.
        if (!speaking && claudeBusy && thinkingSoundEnabled && permissionRequests.isEmpty() && !replySpeaking) {
            thinking.start(thinkingVolume)
        }
    }

    override fun onCleared() {
        runCatching { saveTranscript(transcript.toList()) }
        thinking.stop()
        // Claude keeps running on the server; the next app start attaches to it again.
        conversation?.detach()
        Thread { remote.disconnect() }.start()
        stopListening()
        speaker.shutdown()
    }
}
