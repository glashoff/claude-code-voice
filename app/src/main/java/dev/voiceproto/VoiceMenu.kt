package dev.voiceproto

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Voice-controlled settings menu, evaluated locally by the app (nothing is sent to Claude).
 * Connection settings are deliberately not changeable here.
 * [handle] returns the German sentence the app speaks as feedback.
 */
class VoiceMenu(private val vm: MainViewModel) {

    val help = "Du kannst sagen: Pause und eine Zahl in Sekunden, oder Pause aus. " +
        "Schlüsselwort und ein Wort, oder Schlüsselwort aus. Tempo und eine Zahl, schneller oder langsamer. " +
        "Vorlauf und Millisekunden. Deutsch oder Englisch für die Sprache von Claudes Antworten. Sprachausgabe und ein Name. Stimme nächste, Stimme und ein Name. Projekte, Projekt und ein Name. " +
        "Sitzungen, Sitzung und eine Nummer, Neues Gespräch, Sitzung beenden. Modell und ein Name, möglich sind: ${claudeModels.joinToString(", ") { it.second }}. Claude an oder aus. " +
        "Mikrofon pausieren an oder aus. Zwischenschritte an oder aus. Sprachwechsel an oder aus. Denkgeräusch an, aus, lauter oder leiser. " +
        "Status. Und Fertig zum Verlassen. Außerhalb des Menüs stoppt Stopp die Ausgabe und Claudes Antwort. " +
        "Während ein Entwurf wartet, hält Warte das Absenden an, bis du weitersprichst, und Löschen verwirft nur den Entwurf."

    fun isExit(text: String) = normalize(text) in setOf("fertig", "ende", "zurück", "schließen", "menü beenden", "menü schließen", "exit")

    suspend fun handle(text: String): String {
        val t = normalize(text)
        val rest = { prefix: String -> t.removePrefix(prefix).trim() }
        return when {
            t in setOf("hilfe", "was kann ich sagen", "befehle") -> help
            t == "status" -> status()

            t.startsWith("pause") || t.startsWith("wartezeit") || t.startsWith("sendepause") -> {
                val arg = t.substringAfter(' ', "")
                if (arg.startsWith("aus")) {
                    vm.sendPauseSec = 0f
                    "Senden nach Pause ist aus. Es wird nur noch per Schlüsselwort gesendet."
                } else {
                    val n = parseNumber(arg) ?: return "Wie viele Sekunden? Sag zum Beispiel: Pause drei."
                    vm.sendPauseSec = n.toFloat().coerceIn(0.5f, 30f)
                    "Gesendet wird nach ${spoken(vm.sendPauseSec)} Sekunden Pause."
                }
            }

            t.startsWith("schlüsselwort") || t.startsWith("stichwort") || t.startsWith("codewort") -> {
                val arg = t.substringAfter(' ', "").trim()
                when {
                    arg.isEmpty() -> if (vm.sendKeyword.isBlank()) "Es ist kein Schlüsselwort gesetzt." else "Das Schlüsselwort ist ${vm.sendKeyword}."
                    arg == "aus" -> { vm.sendKeyword = ""; "Schlüsselwort ausgeschaltet." }
                    else -> { vm.sendKeyword = arg; "Neues Schlüsselwort: $arg." }
                }
            }

            t.startsWith("tempo") || t.startsWith("geschwindigkeit") || t == "schneller" || t == "langsamer" -> {
                val rate = when {
                    t == "schneller" || t.endsWith("schneller") -> vm.speechRate + 0.1f
                    t == "langsamer" || t.endsWith("langsamer") -> vm.speechRate - 0.1f
                    else -> parseNumber(t.substringAfter(' ', ""))?.toFloat() ?: return "Welches Tempo? Zum Beispiel: Tempo eins komma zwei."
                }
                vm.speechRate = rate.coerceIn(0.5f, 2f)
                "Tempo ist jetzt ${spoken(vm.speechRate)}."
            }

            t.startsWith("vorlauf") -> {
                val n = parseNumber(t.substringAfter(' ', "")) ?: return "Wie viele Millisekunden Vorlauf?"
                vm.leadInMs = n.toInt().coerceIn(0, 3000)
                "Vorlauf ist jetzt ${vm.leadInMs} Millisekunden."
            }

            t in setOf("deutsch", "sprache deutsch", "auf deutsch", "antworte auf deutsch", "german") -> language("de")
            t in setOf("englisch", "english", "sprache englisch", "auf englisch", "antworte auf englisch") -> language("en")
            t.startsWith("sprachausgabe") -> engine(rest("sprachausgabe"))
            t.startsWith("stimme") -> voice(rest("stimme"))

            t == "projekte" -> {
                if (vm.projects.isEmpty()) vm.loadProjectsAndWait()
                if (vm.projects.isEmpty()) "Ich habe keine Projekte gefunden." else "Projekte: ${vm.projects.joinToString(", ")}."
            }
            t.startsWith("projekt ") -> {
                if (vm.projects.isEmpty()) vm.loadProjectsAndWait()
                val match = bestMatch(rest("projekt"), vm.projects) ?: return "Dieses Projekt kenne ich nicht. Sag Projekte für eine Liste."
                vm.selectProject(match)
                "Projekt $match ausgewählt."
            }

            t == "modelle" || t == "modell" -> "Aktuelles Modell: ${modelName()}. Möglich sind: ${claudeModels.joinToString(", ") { it.second }}."
            t.startsWith("modell ") -> {
                val wanted = compact(rest("modell"))
                    .replace("sonett", "sonnet").replace("sonnett", "sonnet").replace("haiko", "haiku").replace("heiku", "haiku")
                val match = claudeModels.firstOrNull { compact(it.second) == wanted || (it.first.isNotEmpty() && wanted.startsWith(it.first)) }
                    ?: return "Dieses Modell kenne ich nicht. Möglich sind: ${claudeModels.joinToString(", ") { it.second }}."
                vm.claudeModel = match.first
                "Modell ${match.second} gewählt. Gilt ab der nächsten Nachricht, das Gespräch geht weiter."
            }

            t in setOf("sitzungen", "sessions", "sitzungen anzeigen", "letzte sitzungen") -> {
                val list = vm.loadSessionsAndWait()
                if (list.isEmpty()) "Für dieses Projekt gibt es keine gespeicherten Sitzungen."
                else list.take(5).mapIndexed { i, s -> "${i + 1}: ${s.title}, ${age(s.modifiedEpochSec)}" }
                    .joinToString(". ", postfix = ". Sag zum Beispiel: Sitzung eins.")
            }
            t.startsWith("sitzung ") || t.startsWith("session ") -> {
                val n = parseNumber(t.substringAfter(' ', ""))?.toInt() ?: return "Welche Nummer? Zum Beispiel: Sitzung zwei."
                val list = vm.sessions.ifEmpty { vm.loadSessionsAndWait() }
                val session = list.getOrNull(n - 1) ?: return "Eine Sitzung $n gibt es nicht."
                vm.resumeSession(session)
                "Sitzung ${session.title} geladen."
            }
            t in setOf("sitzung beenden", "claude beenden", "beenden") -> {
                vm.endRun()
                "Claude auf dem Rechner beendet. Das Gespräch bleibt erhalten."
            }
            t in setOf("neues gespräch", "neue sitzung", "neue session") -> {
                vm.newConversation()
                "Neues Gespräch gestartet."
            }

            t.startsWith("claude") -> onOff(t) { on -> vm.sendToClaude = on; "Senden an Claude ist ${if (on) "an" else "aus"}." }
            t.startsWith("mikrofon pausieren") -> onOff(t) { on -> vm.muteWhileSpeaking = on; "Mikrofon pausieren während der Ausgabe ist ${if (on) "an" else "aus"}." }
            t.startsWith("denkgeräusch") || t.startsWith("denkgeräusche") -> when {
                t.endsWith("lauter") -> { vm.thinkingVolume = (vm.thinkingVolume + 0.1f).coerceAtMost(1f); "Denkgeräusch lauter." }
                t.endsWith("leiser") -> { vm.thinkingVolume = (vm.thinkingVolume - 0.1f).coerceAtLeast(0.05f); "Denkgeräusch leiser." }
                else -> onOff(t) { on -> vm.thinkingSoundEnabled = on; "Denkgeräusch ist ${if (on) "an" else "aus"}." }
            }
            t.startsWith("zwischenschritt") -> onOff(t) { on -> vm.announceSteps = on; "Zwischenschritte vorlesen ist ${if (on) "an" else "aus"}." }
            t.startsWith("sprachwechsel") -> onOff(t) { on -> vm.languageSwitch = on; "Sprachwechsel ist ${if (on) "an" else "aus"}. Gilt ab dem nächsten Mikrofonstart." }

            else -> "Das habe ich nicht verstanden. Sag Hilfe für eine Liste."
        }
    }

    private fun language(code: String): String {
        vm.switchLanguage(code)
        return if (code == "de") "Claude antwortet jetzt auf Deutsch." else "Claude antwortet jetzt auf Englisch."
    }

    /** Selects the TTS engine for the active language by (part of) its name. */
    private fun engine(arg: String): String {
        val language = if (vm.outputLanguage == "de") "Deutsch" else "Englisch"
        val engines = vm.ttsEngines
        if (arg.isEmpty()) {
            val current = engines.firstOrNull { it.first == vm.ttsEngine }?.second ?: "Standard"
            return "Sprachausgabe für $language: $current. Verfügbar: ${engines.joinToString(", ") { it.second }}."
        }
        val match = bestMatch(arg, engines.map { it.second })
            ?: engines.firstOrNull { compact(it.first).contains(compact(arg)) }?.second
            ?: return "Diese Sprachausgabe habe ich nicht gefunden. Verfügbar: ${engines.joinToString(", ") { it.second }}."
        vm.selectEngine(engines.first { it.second == match }.first)
        return "Sprachausgabe für $language ist jetzt $match."
    }

    private fun status() = buildString {
        append("Projekt: ${vm.project.ifEmpty { "keines" }}. ")
        append("Sitzung: ${vm.currentSessionTitle.ifEmpty { "neu" }}. ")
        append(if (vm.sendPauseSec > 0) "Senden nach ${spoken(vm.sendPauseSec)} Sekunden Pause. " else "Senden nach Pause ist aus. ")
        append(if (vm.sendKeyword.isNotBlank()) "Schlüsselwort: ${vm.sendKeyword}. " else "Kein Schlüsselwort. ")
        append("Tempo ${spoken(vm.speechRate)}. Vorlauf ${vm.leadInMs} Millisekunden. ")
        append("Antwortsprache: ${if (vm.replyLanguage == "German") "Deutsch" else "Englisch"}. ")
        append("Stimme: ${vm.selectedVoice?.name ?: "Standard"}. ")
        append("Modell: ${modelName()}.")
    }

    private fun modelName() = claudeModels.firstOrNull { it.first == vm.claudeModel.trim() }?.second ?: vm.claudeModel.ifBlank { "Standard" }

    private fun voice(arg: String): String {
        val current = vm.selectedVoice
        val sameLanguage = vm.voices.filter { it.lang == (current?.lang ?: vm.outputLanguage) }
        val next = when {
            arg.isEmpty() -> return "Aktuelle Stimme: ${current?.name ?: "Standard"}."
            arg.startsWith("nächste") || arg.startsWith("weiter") -> sameLanguage.getOrNull((sameLanguage.indexOf(current) + 1) % sameLanguage.size.coerceAtLeast(1))
            arg.startsWith("vorherige") || arg.startsWith("zurück") -> sameLanguage.getOrNull((sameLanguage.indexOf(current) - 1 + sameLanguage.size) % sameLanguage.size.coerceAtLeast(1))
            else -> vm.voices.firstOrNull { compact(it.name).contains(compact(arg)) }
        } ?: return "Diese Stimme habe ich nicht gefunden."
        vm.selectVoice(next)
        return "Stimme ${next.name}."
    }

    private inline fun onOff(t: String, apply: (Boolean) -> String): String = when {
        t.endsWith(" an") || t.endsWith(" ein") -> apply(true)
        t.endsWith(" aus") -> apply(false)
        else -> "Bitte sag an oder aus."
    }

    private fun bestMatch(spokenName: String, options: List<String>): String? {
        val wanted = compact(spokenName)
        if (wanted.isEmpty()) return null
        return options.firstOrNull { compact(it) == wanted }
            ?: options.firstOrNull { compact(it).startsWith(wanted) }
            ?: options.firstOrNull { compact(it).contains(wanted) }
    }

    private fun age(epochSec: Long): String {
        val minutes = (System.currentTimeMillis() / 1000 - epochSec) / 60
        return when {
            minutes < 60 -> "vor $minutes Minuten"
            minutes < 24 * 60 -> "vor ${minutes / 60} Stunden"
            else -> "am " + SimpleDateFormat("d. MMMM", Locale.GERMANY).format(Date(epochSec * 1000))
        }
    }

    companion object {
        fun normalize(text: String) =
            text.lowercase(Locale.GERMANY).replace(Regex("[.!?,;:]+(?=\\s|$)"), "").replace(Regex("\\s+"), " ").trim()

        private fun compact(s: String) = s.lowercase(Locale.GERMANY).replace(Regex("[^a-z0-9äöüß]"), "")

        private fun spoken(f: Float) = if (f % 1f == 0f) f.toInt().toString() else "%.1f".format(Locale.GERMANY, f)

        private val numberWords = mapOf(
            "null" to 0, "ein" to 1, "eins" to 1, "eine" to 1, "einer" to 1, "zwei" to 2, "drei" to 3, "vier" to 4,
            "fünf" to 5, "sechs" to 6, "sieben" to 7, "acht" to 8, "neun" to 9, "zehn" to 10, "elf" to 11,
            "zwölf" to 12, "fünfzehn" to 15, "zwanzig" to 20, "dreißig" to 30, "hundert" to 100,
            "zweihundert" to 200, "dreihundert" to 300, "fünfhundert" to 500, "tausend" to 1000,
        )

        /** Parses "3", "1,5", "1.5", "drei", "eins komma zwei", "eineinhalb" (first number in the text). */
        fun parseNumber(text: String): Double? {
            Regex("\\d+(?:[.,]\\d+)?").find(text)?.let { return it.value.replace(',', '.').toDouble() }
            val words = text.lowercase(Locale.GERMANY).split(Regex("\\s+"))
            if ("eineinhalb" in words) return 1.5
            val i = words.indexOfFirst { it in numberWords }
            if (i < 0) return null
            var value = numberWords.getValue(words[i]).toDouble()
            if (words.getOrNull(i + 1) == "komma") numberWords[words.getOrNull(i + 2)]?.let { value += it / 10.0 }
            return value
        }
    }
}
