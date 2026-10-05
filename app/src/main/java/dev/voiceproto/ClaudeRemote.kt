package dev.voiceproto

import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

data class ServerConfig(
    val host: String,
    val port: Int,
    val user: String,
    /** Directory whose subdirectories are offered as projects, e.g. "~/Projects". */
    val projectRoot: String,
    /** Comma-separated tools Claude may use without asking, e.g. "Read,Glob,Grep". */
    val allowedTools: String,
    /** Optional model alias or id; empty = Claude Code's default. */
    val model: String,
)

/** Instructions appended to Claude Code's system prompt for voice conversations. */
val voiceSystemPrompt =
    "You are talking to the user through a voice interface: their speech is transcribed (German, often mixed " +
        "with English terms) and your reply is read aloud by a text-to-speech voice. Each user message starts with a " +
        "bracketed hint naming the reply language, e.g. [Antwortsprache: Deutsch] or [Reply language: English]; always " +
        "answer in that language, even if earlier replies in this conversation were in another language. " +
        "Keep answers short and conversational, like spoken language. Do not use Markdown, lists, tables, code " +
        "blocks, URLs or emoji unless the user explicitly asks for them; describe code in words instead. " +
        "The user only hears your reply and cannot do anything with technical details: never mention file paths, " +
        "file names, function or variable names, commands, version numbers or other identifiers. Say in plain words " +
        "what you did, found or plan, for example 'the file for the speech output' instead of its name. When answering " +
        "in German, avoid English technical terms and use common German words, paraphrasing where needed. Only use " +
        "complete, natural sentences that make sense when heard. " +
        "Transcription errors are likely, so infer what the user most plausibly meant. " +
        "Every action that changes something (editing files, running commands) is shown to the user by the app, read " +
        "aloud and needs their spoken approval. Before a larger change, briefly say what you are about to do. " +
        "The app reads the description parameter of every command aloud with a voice for the reply language. Write it " +
        "as one short, plain sentence in the reply language that a non-programmer understands when hearing it, e.g. " +
        "'Ich baue die App neu.' Never put English words, technical terms, file names or command text into it. " +
        "The user may send addenda while you are working; take them into account."

/** Selectable Claude models: Claude Code alias ("" = Claude Code's default) and spoken name. */
val claudeModels = listOf(
    "" to "Standard",
    "fable" to "Fable",
    "opus" to "Opus",
    "sonnet" to "Sonnet",
    "haiku" to "Haiku",
)

/** A tool call Claude wants to make that needs the user's approval. */
data class PermissionRequest(val id: String, val tool: String, val input: JSONObject, val toolUseId: String = "")

/** A tool call Claude makes (top-level conversation only), used for spoken progress updates. */
data class ToolStep(val id: String, val tool: String, val input: JSONObject)

/**
 * Where Claude runs for a project: directly on the server, or inside the project's dev container (projects with a
 * `.devcontainer/devcontainer.json`, see the base-devcontainer project). A container is found by its
 * `devcontainer.local_folder` label, not by an SSH alias, whose port may meanwhile belong to another container.
 */
data class Target(val project: String, val container: Boolean)

/** A stored Claude Code conversation on the server. */
data class SessionInfo(val id: String, val modifiedEpochSec: Long, val title: String)

/** Events parsed from Claude Code's stream-json output. */
sealed interface ClaudeEvent {
    data class Session(val id: String) : ClaudeEvent
    data class TextDelta(val text: String) : ClaudeEvent
    data class ToolUse(val name: String) : ClaudeEvent
    data class Done(val text: String, val durationMs: Long, val isError: Boolean) : ClaudeEvent
    data class Permission(val request: PermissionRequest) : ClaudeEvent
    data class Steps(val steps: List<ToolStep>) : ClaudeEvent
    /** The connection to the server broke; Claude keeps running there and the app reconnects. */
    data object ConnectionLost : ClaudeEvent
    data object Reconnected : ClaudeEvent
    /** Claude withdrew a pending permission request (e.g. after an interrupt). */
    data class PermissionCancelled(val id: String) : ClaudeEvent
    data class Error(val message: String) : ClaudeEvent
    /** The Claude process ended; [error] is set if it ended unexpectedly. */
    data class Closed(val error: String?) : ClaudeEvent
}

/**
 * Runs Claude Code on a server over SSH. Nothing needs to be installed there besides sshd and `claude`:
 * the app lists projects with `find` and runs one `claude` process per conversation detached from the connection
 * ([start], [attach]), talking to it in stream-json through a named pipe and an output file. A lost connection does
 * not stop Claude; the app reconnects and continues. Tool calls beyond the allowed read-only tools come back as
 * permission requests.
 *
 * Authentication uses an Ed25519 key generated on first use and stored in app-private storage. The server's host key
 * is pinned on first connect (trust on first use); a changed host key is rejected.
 */
class ClaudeRemote(private val keyDir: File, private val pinnedHostKey: () -> String, private val pinHostKey: (String) -> Unit) {
    private val jsch = JSch()
    private var session: Session? = null
    private var sessionConfig: ServerConfig? = null

    private val privateKey get() = File(keyDir, "id_ed25519")
    private val publicKey get() = File(keyDir, "id_ed25519.pub")

    /** Returns the public key line for authorized_keys, generating the key pair if needed. */
    fun publicKeyLine(): String {
        if (privateKey.length() == 0L || publicKey.length() == 0L) {
            keyDir.mkdirs()
            val pair = KeyPair.genKeyPair(jsch, KeyPair.ED25519)
            pair.writeOpenSSHv1PrivateKey(privateKey.absolutePath) // Ed25519 only supports the OpenSSH v1 format
            pair.writePublicKey(publicKey.absolutePath, "voice-app")
            pair.dispose()
        }
        return publicKey.readText().trim().also { Log.i("VoiceProto", "SSH public key: $it") }
    }

    @Synchronized
    private fun connect(config: ServerConfig): Session {
        session?.let { if (it.isConnected && sessionConfig == config) return it }
        disconnect()
        publicKeyLine()
        if (jsch.identityNames.isEmpty()) jsch.addIdentity(privateKey.absolutePath)
        val s = jsch.getSession(config.user, config.host, config.port).apply {
            // Host key is verified manually below (pinning), not via a known_hosts file.
            setConfig("StrictHostKeyChecking", "no")
            setConfig("PreferredAuthentications", "publickey")
            setServerAliveInterval(15_000)
            timeout = 10_000
        }
        s.connect(10_000)
        val fingerprint = s.hostKey.getFingerPrint(jsch)
        val pinned = pinnedHostKey()
        if (pinned.isEmpty()) {
            pinHostKey(fingerprint)
        } else if (pinned != fingerprint) {
            s.disconnect()
            error("Host-Schlüssel des Servers hat sich geändert! Erwartet $pinned, bekommen $fingerprint")
        }
        session = s
        sessionConfig = config
        return s
    }

    fun disconnect() {
        session?.disconnect()
        session = null
        sessionConfig = null
    }

    /** Runs a command and returns its stdout lines; throws with stderr if it fails. */
    suspend fun run(config: ServerConfig, command: String): List<String> = withContext(Dispatchers.IO) {
        val lines = ArrayList<String>()
        exec(config, command, null) { lines.add(it) }?.let { error(it) }
        lines
    }

    suspend fun projects(config: ServerConfig): List<String> =
        run(config, "cd ${shellPath(config.projectRoot)} && find . -mindepth 1 -maxdepth 1 -type d ! -name '.*' -printf '%f\\n' | sort")
            .filter { it.isNotBlank() }

    /**
     * Lists the newest Claude Code sessions of [project]. Claude stores them as
     * ~/.claude/projects/<cwd with non-alphanumerics replaced by '-'>/<session-id>.jsonl; the title is the last
     * "ai-title" record, falling back to the first user prompt.
     */
    suspend fun sessions(config: ServerConfig, project: String, limit: Int = 15): List<SessionInfo> {
        val target = target(config, project)
        val command = inTarget(config, target, "cd ${workDir(config, target)} && " +
            "d=\"\$HOME/.claude/projects/\$(pwd -P | sed 's/[^a-zA-Z0-9]/-/g')\" && " +
            "ls -t \"\$d\"/*.jsonl 2>/dev/null | head -n $limit | while read -r f; do " +
            "printf '##SESSION %s %s\\n' \"\$(basename \"\$f\" .jsonl)\" \"\$(stat -c %Y \"\$f\")\"; " +
            "grep '\"type\":\"ai-title\"' \"\$f\" | tail -n 1; " +
            "grep -m 1 '\"type\":\"user\"' \"\$f\" | cut -c 1-4000; " +
            "done")
        val result = ArrayList<SessionInfo>()
        var id = ""
        var modified = 0L
        var title: String? = null
        var firstPrompt: String? = null
        fun flush() {
            if (id.isNotEmpty()) result += SessionInfo(id, modified, (title ?: firstPrompt ?: "(ohne Titel)").take(120))
        }
        for (line in run(config, command)) {
            if (line.startsWith("##SESSION ")) {
                flush()
                val parts = line.split(' ')
                id = parts.getOrElse(1) { "" }
                modified = parts.getOrElse(2) { "0" }.toLongOrNull() ?: 0L
                title = null
                firstPrompt = null
            } else if ("\"ai-title\"" in line) {
                title = jsonField(line, "aiTitle")
            } else if (firstPrompt == null) {
                firstPrompt = jsonField(line, "content")?.replace(Regex("\\s+"), " ")?.trim()
            }
        }
        flush()
        return result
    }

    /** Extracts a string field; tolerant of lines truncated by `cut`, where full JSON parsing fails. */
    private fun jsonField(line: String, field: String): String? {
        try {
            val obj = JSONObject(line)
            if (field != "content") return obj.optString(field).takeIf { it.isNotEmpty() }
            // A user prompt is either a plain string or a list of content blocks.
            val content = obj.optJSONObject("message")?.opt("content")
            return when (content) {
                is String -> content
                is org.json.JSONArray -> (0 until content.length())
                    .mapNotNull { content.optJSONObject(it)?.optString("text")?.takeIf { t -> t.isNotEmpty() } }
                    .firstOrNull()
                else -> null
            }
        } catch (e: Exception) {
            val key = if (field == "content") "(?:content|text)" else field
            val match = Regex("\"$key\":\"((?:[^\"\\\\]|\\\\.)*)").find(line) ?: return null
            return match.groupValues[1].replace("\\n", " ").replace("\\\"", "\"")
        }
    }

    /** Shell expression for the project folder on the server. */
    private fun projectDir(config: ServerConfig, project: String) = shellPath(config.projectRoot) + "/" + shellQuote(project)

    /** Whether [project] runs in a dev container. */
    suspend fun target(config: ServerConfig, project: String): Target = withContext(Dispatchers.IO) {
        requireValidProject(project)
        val dir = projectDir(config, project)
        Target(project, "yes" in run(config, "test -f $dir/.devcontainer/devcontainer.json && echo yes || true"))
    }

    /** Finds the project's container in the shell variable `c`; exits with 4 if it is not running. */
    private fun findContainer(config: ServerConfig, project: String) =
        "p=\"\$(cd ${projectDir(config, project)} && pwd -P)\" && " +
            "c=\"\$(docker ps -q --filter \"label=devcontainer.local_folder=\$p\")\" && [ -n \"\$c\" ] || exit 4; "

    /** Runs [command] where Claude lives for [target]: on the server, or in the container as its remote user. */
    internal fun inTarget(config: ServerConfig, target: Target, command: String) =
        if (!target.container) command
        // bash -l: the login profile puts the container's tools (node, claude) on the PATH.
        else findContainer(config, target.project) + "exec docker exec -i -u vscode \"\$c\" bash -lc ${shellQuote(command)}"

    /** Folder of the project where Claude runs (in a container: its workspace folder). */
    private fun workDir(config: ServerConfig, target: Target) =
        if (target.container) "/workspaces/" + shellQuote(target.project) else projectDir(config, target.project)

    /**
     * Starts the project's container if it is not running, with the dev container's own start script (without
     * opening an editor). Can take a while; blocking.
     */
    suspend fun ensureContainer(config: ServerConfig, target: Target) = withContext(Dispatchers.IO) {
        if (!target.container) return@withContext
        val command = "export PATH=\"\$HOME/.local/bin:\$PATH\"; p=\"\$(cd ${projectDir(config, target.project)} && pwd -P)\" && " +
            "{ [ -n \"\$(docker ps -q --filter \"label=devcontainer.local_folder=\$p\")\" ] || " +
            "devcontainer-start --no-open --yes \"\$p\" < /dev/null > /dev/null 2>&1; } && " +
            "[ -n \"\$(docker ps -q --filter \"label=devcontainer.local_folder=\$p\")\" ] && echo running || true"
        if ("running" !in run(config, command)) error("Der Container des Projekts konnte nicht gestartet werden.")
    }

    private fun requireValidProject(project: String) =
        require(project.isNotEmpty() && '/' !in project && project != "..") { "Ungültiger Projektname" }

    /** Script the app puts into the project folder, so the run can also be stopped at the computer. */
    private val stopScriptName = "stop-voice-claude.sh"

    private fun stopScript(id: String, container: Boolean): String {
        val kill = """
            |d="${'$'}HOME/.cache/voice-app/$id"
            |kill "${'$'}(cat "${'$'}d/holder" 2>/dev/null)" 2>/dev/null
            |p="${'$'}(cat "${'$'}d/pid" 2>/dev/null)"
            |for i in 1 2 3 4 5 6 7 8 9 10; do kill -0 "${'$'}p" 2>/dev/null || break; sleep 0.5; done
            |kill -0 "${'$'}p" 2>/dev/null && { pkill -TERM -P "${'$'}p"; kill -TERM "${'$'}p"; }
            |rm -rf "${'$'}d"
            |""".trimMargin()
        val body = if (!container) kill else """
            |# Claude runs inside this project's dev container: find it by this folder and stop Claude there.
            |p="${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd -P)"
            |c="${'$'}(docker ps -q --filter "label=devcontainer.local_folder=${'$'}p")"
            |[ -n "${'$'}c" ] && docker exec -u vscode "${'$'}c" sh -c ${shellQuote(kill)}
            |""".trimMargin()
        return "#!/bin/sh\n" +
            "# Stops the Claude instance that the voice app started in this project. It keeps running on this computer even\n" +
            "# when the phone disconnects; stop it before continuing the conversation here. Removes itself afterwards.\n" +
            body + "\nrm -f \"${'$'}0\"\necho \"Claude der Sprach-App beendet.\"\n"
    }

    /** Where a detached Claude run keeps its input pipe, output, error log and process ids on the server. */
    private fun runDir(id: String): String {
        require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "Ungültige Lauf-ID" }
        return "\"\$HOME/.cache/voice-app/$id\""
    }

    /**
     * Starts Claude in [project] detached from the SSH connection, resuming [sessionId] if given, and returns the run
     * id. Its input is a named pipe and its output goes to a file, so a lost connection does not stop it; [attach]
     * continues reading where the app left off. A sleeping helper keeps the pipe open for writing, so Claude never
     * sees the end of its input between messages; ending the helper ends Claude ([stop]).
     */
    suspend fun start(config: ServerConfig, target: Target, sessionId: String?): String = withContext(Dispatchers.IO) {
        requireValidProject(target.project)
        require(sessionId == null || sessionId.matches(Regex("[0-9a-fA-F-]{36}"))) { "Ungültige Session-ID" }
        val id = UUID.randomUUID().toString()
        run(config, startCommand(config, target, id, sessionId))
        if (!send(config, target, id, JSONObject().put("type", "control_request").put("request_id", "init")
                .put("request", JSONObject().put("subtype", "initialize")).toString())) {
            error("Claude konnte nicht gestartet werden.")
        }
        id
    }

    /** Shell command that starts run [id] (see [start]); internal for tests. */
    internal fun startCommand(config: ServerConfig, target: Target, id: String, sessionId: String?): String {
        val claude = buildString {
            append("claude -p --input-format stream-json --output-format stream-json --verbose --include-partial-messages")
            append(" --permission-prompt-tool stdio --permission-mode default")
            append(" --append-system-prompt ").append(shellQuote(voiceSystemPrompt))
            if (config.allowedTools.isNotBlank()) append(" --allowedTools ").append(shellQuote(config.allowedTools.trim()))
            if (config.model.isNotBlank()) append(" --model ").append(shellQuote(config.model.trim()))
            if (sessionId != null) append(" --resume ").append(sessionId)
        }
        // Runs where Claude lives (on the server or in the project's container).
        val launch = buildString {
            append("d=").append(runDir(id)).append("; mkdir -p \"\$d\" && mkfifo \"\$d/in\" && : > \"\$d/out.jsonl\" && ")
            append("cd ").append(workDir(config, target)).append(" && ")
            append("{ setsid sh -c 'exec sleep 2147483647 > \"\$0\"' \"\$d/in\" < /dev/null > /dev/null 2>&1 & echo \$! > \"\$d/holder\"; } && ")
            append("{ setsid ").append(claude).append(" < \"\$d/in\" > \"\$d/out.jsonl\" 2> \"\$d/err.log\" & echo \$! > \"\$d/pid\"; } && ")
            append("pwd > \"\$d/project\"")
        }
        // Runs on the server: the stop script for the computer, kept out of version control locally (not via the
        // shared ignore list).
        val script = "cd ${projectDir(config, target.project)} && printf '%s' ${shellQuote(stopScript(id, target.container))} > $stopScriptName && " +
            "chmod +x $stopScriptName && " +
            "{ [ ! -d .git/info ] || grep -qx $stopScriptName .git/info/exclude 2>/dev/null || echo $stopScriptName >> .git/info/exclude; }"
        // Non-interactive SSH commands get a minimal PATH; user-local installs live in ~/.local/bin.
        return "export PATH=\"\$HOME/.local/bin:\$PATH\"; " +
            if (target.container) findContainer(config, target.project) +
                "docker exec -i -u vscode \"\$c\" bash -lc ${shellQuote(launch)} && $script"
            else "$launch && $script"
    }

    /** Whether the Claude process of run [id] is still running. */
    suspend fun isAlive(config: ServerConfig, target: Target, id: String): Boolean = withContext(Dispatchers.IO) {
        // Fails (no container running) count as "not alive".
        runCatching {
            "alive" in run(config, inTarget(config, target,
                "d=${runDir(id)}; kill -0 \"\$(cat \"\$d/pid\" 2>/dev/null)\" 2>/dev/null && echo alive || true"))
        }.getOrDefault(false)
    }

    /** Ends run [id]: closing its input makes Claude exit; if it does not, its process tree is terminated. */
    suspend fun stop(config: ServerConfig, target: Target, id: String) = withContext(Dispatchers.IO) {
        run(config, stopCommand(config, target, id))
    }

    /** Shell command that ends run [id] (see [stop]); internal for tests. */
    internal fun stopCommand(config: ServerConfig, target: Target, id: String) = inTarget(config, target, "d=${runDir(id)}; kill \"\$(cat \"\$d/holder\" 2>/dev/null)\" 2>/dev/null; p=\"\$(cat \"\$d/pid\" 2>/dev/null)\"; " +
            "for i in 1 2 3 4 5 6 7 8 9 10; do kill -0 \"\$p\" 2>/dev/null || break; sleep 0.5; done; " +
            "kill -0 \"\$p\" 2>/dev/null && { pkill -TERM -P \"\$p\"; kill -TERM \"\$p\"; }; " +
            // Remove the stop script only if it still belongs to this run (a newer run may have replaced it).
            "s=\"\$(cat \"\$d/project\" 2>/dev/null)/$stopScriptName\"; grep -qs '$id' \"\$s\" && rm -f \"\$s\"; rm -rf \"\$d\"; true")

    /** Writes one JSON line into the input pipe of run [id]; false if that failed (e.g. no connection). */
    /** Shell command that writes [line] into the input pipe of run [id]; internal for tests. */
    internal fun sendCommand(config: ServerConfig, target: Target, id: String, line: String) = inTarget(config, target,
        "d=${runDir(id)}; timeout 10 sh -c 'printf \"%s\\n\" \"\$1\" > \"\$2\"' _ ${shellQuote(line)} \"\$d/in\"")

    /** Shell command that follows the output of run [id] from byte [offset]; internal for tests. */
    internal fun tailCommand(config: ServerConfig, target: Target, id: String, offset: Long) = inTarget(config, target,
        "d=${runDir(id)}; test -f \"\$d/pid\" || exit 3; exec tail --pid=\"\$(cat \"\$d/pid\")\" -c +${offset + 1} -f \"\$d/out.jsonl\"")

    private fun send(config: ServerConfig, target: Target, id: String, line: String): Boolean = try {
        val channel = connect(config).openChannel("exec") as ChannelExec
        // The timeout guards against a pipe without reader (Claude gone), where opening it would block forever.
        channel.setCommand(sendCommand(config, target, id, line))
        channel.connect(10_000)
        var waited = 0
        while (!channel.isClosed && waited < 15_000) { Thread.sleep(20); waited += 20 }
        val ok = channel.exitStatus == 0
        channel.disconnect()
        ok
    } catch (e: Exception) {
        Log.w("VoiceProto", "write to Claude failed", e)
        false
    }

    /**
     * Follows the output of run [id] from byte [offset] on and delivers parsed events to [onEvent]. A lost connection
     * is reported ([ClaudeEvent.ConnectionLost]) and retried until it works again ([ClaudeEvent.Reconnected]); Claude
     * keeps running on the server meanwhile. [onOffset] reports the processed position so it can be stored.
     */
    fun attach(
        scope: CoroutineScope, config: ServerConfig, target: Target, id: String, offset: Long,
        onEvent: suspend (ClaudeEvent) -> Unit, onOffset: (Long) -> Unit,
    ): Conversation = Conversation(scope, config, target, id, offset, onEvent, onOffset).also { it.startReading() }

    /** One Claude run on the server. Writes go through a single thread, in order; failed ones wait in an outbox. */
    inner class Conversation internal constructor(
        private val scope: CoroutineScope,
        private val config: ServerConfig,
        private val target: Target,
        val id: String,
        startOffset: Long,
        private val onEvent: suspend (ClaudeEvent) -> Unit,
        private val onOffset: (Long) -> Unit,
    ) {
        /** Bytes of Claude's output processed so far. */
        @Volatile var offset = startOffset
            private set
        @Volatile private var finished = false // detached or closed by the app, or Claude ended
        @Volatile private var connectionLost = false
        private val writer = Executors.newSingleThreadExecutor()
        private val outbox = ConcurrentLinkedQueue<String>()
        private var reader: Job? = null
        val isOpen get() = !finished

        internal fun startReading() {
            reader = scope.launch(Dispatchers.IO) {
                while (!finished) {
                    val status = try { followOutput() } catch (e: Exception) { -1 }
                    if (finished) break
                    when (status) {
                        0 -> { // Claude ended on its own
                            finished = true
                            val err = runCatching { run(config, inTarget(config, target, "tail -n 5 ${runDir(id)}/err.log 2>/dev/null || true")) }
                                .getOrNull()?.joinToString("\n")?.trim()
                            onEvent(ClaudeEvent.Closed(err?.ifEmpty { null }))
                        }
                        3 -> {
                            finished = true
                            onEvent(ClaudeEvent.Closed("Die laufende Sitzung auf dem Rechner gibt es nicht mehr."))
                        }
                        4 -> {
                            finished = true
                            onEvent(ClaudeEvent.Closed("Der Container des Projekts läuft nicht mehr."))
                        }
                        else -> {
                            if (!connectionLost) {
                                connectionLost = true
                                onEvent(ClaudeEvent.ConnectionLost)
                            }
                            disconnect() // the next attempt opens a fresh SSH connection
                            delay(5_000)
                        }
                    }
                }
                onOffset(offset)
            }
        }

        /** Reads the output until Claude ends (0), the run is unknown (3), the container is gone (4) or the connection breaks (-1). */
        private suspend fun followOutput(): Int {
            val channel = connect(config).openChannel("exec") as ChannelExec
            channel.setCommand(tailCommand(config, target, id, offset))
            val input = channel.inputStream
            channel.connect(10_000)
            if (connectionLost) {
                connectionLost = false
                flushOutbox()
                onEvent(ClaudeEvent.Reconnected)
            }
            try {
                val line = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var lastSaved = System.currentTimeMillis()
                while (!finished) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    for (k in 0 until n) {
                        val b = buffer[k]
                        if (b != '\n'.code.toByte()) { line.write(b.toInt()); continue }
                        val bytes = line.toByteArray()
                        line.reset()
                        offset += bytes.size + 1
                        parse(String(bytes, Charsets.UTF_8))?.let { onEvent(it) }
                    }
                    if (System.currentTimeMillis() - lastSaved > 1_000) {
                        onOffset(offset)
                        lastSaved = System.currentTimeMillis()
                    }
                }
                repeat(50) { if (!channel.isClosed) Thread.sleep(20) }
                return if (channel.isClosed) channel.exitStatus else -1
            } finally {
                channel.disconnect()
            }
        }

        internal fun write(obj: JSONObject) {
            val line = obj.toString()
            writer.execute {
                // Keep the order: once something waits in the outbox, later messages queue behind it.
                if (outbox.isNotEmpty() || !send(config, target, id, line)) outbox.add(line)
            }
        }

        private fun flushOutbox() {
            writer.execute {
                while (true) {
                    val next = outbox.peek() ?: break
                    if (!send(config, target, id, next)) break
                    outbox.poll()
                }
            }
        }

        /** Sends a user message; while Claude is working it is taken into account in the running turn. */
        fun send(text: String) = write(
            JSONObject().put("type", "user").put("message", JSONObject().put("role", "user")
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))),
        )

        fun allow(request: PermissionRequest) = respond(request.id, JSONObject().put("behavior", "allow").put("updatedInput", request.input))

        fun deny(request: PermissionRequest, message: String) = respond(request.id, JSONObject().put("behavior", "deny").put("message", message))

        private fun respond(id: String, response: JSONObject) = write(
            JSONObject().put("type", "control_response").put("response",
                JSONObject().put("subtype", "success").put("request_id", id).put("response", response)),
        )

        /** Aborts the current turn; the process keeps running for further messages. */
        fun interrupt() = write(
            JSONObject().put("type", "control_request").put("request_id", "interrupt-${System.nanoTime()}")
                .put("request", JSONObject().put("subtype", "interrupt")),
        )

        /** Stops following the run but leaves Claude running on the server (app closed). */
        fun detach() {
            finished = true
            reader?.cancel()
            writer.shutdown()
            onOffset(offset)
        }

        /** Ends Claude on the server. Blocking; call off the main thread. */
        fun close() {
            detach()
            runCatching { kotlinx.coroutines.runBlocking { stop(config, target, id) } }
        }
    }

    /** Executes [command], feeding [stdin]; returns null on success or an error message. */
    private suspend fun exec(config: ServerConfig, command: String, stdin: String?, onLine: suspend (String) -> Unit): String? {
        val channel = connect(config).openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val input = channel.inputStream
        val stderr = ByteArrayOutputStream()
        channel.setErrStream(stderr)
        val output = channel.outputStream
        channel.connect(10_000)
        try {
            output.use { if (stdin != null) it.write(stdin.toByteArray(Charsets.UTF_8)) }
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    coroutineContext.ensureActive()
                    onLine(line)
                }
            }
            // The exit status arrives shortly after the end of stdout.
            repeat(50) { if (!channel.isClosed) Thread.sleep(20) }
            val status = channel.exitStatus
            return if (status > 0) {
                stderr.toString().trim().lines().takeLast(5).joinToString("\n").ifEmpty { "Befehl fehlgeschlagen (Exit-Code $status)" }
            } else null
        } finally {
            channel.disconnect()
        }
    }

    private fun parse(line: String): ClaudeEvent? {
        val obj = try { JSONObject(line) } catch (e: Exception) { return null }
        return when (obj.optString("type")) {
            "system" -> obj.optString("session_id").takeIf { it.isNotEmpty() && obj.optString("subtype") == "init" }
                ?.let { ClaudeEvent.Session(it) }
            "stream_event" -> {
                // Only the top-level conversation, not subagents.
                if (!obj.isNull("parent_tool_use_id")) return null
                val event = obj.getJSONObject("event")
                when (event.optString("type")) {
                    "content_block_delta" -> event.getJSONObject("delta").takeIf { it.optString("type") == "text_delta" }
                        ?.let { ClaudeEvent.TextDelta(it.getString("text")) }
                    "content_block_start" -> event.getJSONObject("content_block").takeIf { it.optString("type") == "tool_use" }
                        ?.let { ClaudeEvent.ToolUse(it.optString("name")) }
                    else -> null
                }
            }
            // Resuming emits an empty result (no turns) right at start; it does not end a turn of ours.
            "result" -> if (obj.optInt("num_turns", 1) == 0 && obj.optString("result").isEmpty()) null
                else ClaudeEvent.Done(obj.optString("result"), obj.optLong("duration_ms"), obj.optBoolean("is_error"))
            "control_request" -> obj.optJSONObject("request")?.takeIf { it.optString("subtype") == "can_use_tool" }?.let {
                ClaudeEvent.Permission(PermissionRequest(obj.optString("request_id"), it.optString("tool_name"),
                    it.optJSONObject("input") ?: JSONObject(), it.optString("tool_use_id")))
            }
            "assistant" -> {
                if (!obj.isNull("parent_tool_use_id")) return null
                val content = obj.optJSONObject("message")?.optJSONArray("content") ?: return null
                val steps = (0 until content.length()).mapNotNull { content.optJSONObject(it) }
                    .filter { it.optString("type") == "tool_use" }
                    .map { ToolStep(it.optString("id"), it.optString("name"), it.optJSONObject("input") ?: JSONObject()) }
                if (steps.isEmpty()) null else ClaudeEvent.Steps(steps)
            }
            "control_cancel_request" -> ClaudeEvent.PermissionCancelled(obj.optString("request_id"))
            "error" -> ClaudeEvent.Error(obj.optString("message"))
            else -> null
        }
    }

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Quotes a path but keeps a leading "~/" unquoted so the remote shell expands it. */
    private fun shellPath(path: String): String {
        val p = path.trim().trimEnd('/')
        return when {
            p == "~" -> "~"
            p.startsWith("~/") -> "~/" + shellQuote(p.removePrefix("~/"))
            else -> shellQuote(p)
        }
    }
}
