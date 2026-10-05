package dev.claudecodevoice

import java.io.File
import org.junit.Test

/** Writes the shell commands the app sends, so they can be run on a real server by a test script. */
class CommandDump {
    @Test
    fun dump() {
        val out = File(System.getProperty("dumpDir") ?: "build/commands").apply { mkdirs() }
        val remote = ClaudeRemote(File(out, "keys"), { "" }, {})
        val config = ServerConfig("h", 22, "u", "~/Projects", "Read", "haiku")
        for ((name, target) in listOf("host" to Target("claude-voice-web", false), "container" to Target("server", true))) {
            val id = "00000000-0000-4000-8000-0000000000" + if (target.container) "02" else "01"
            File(out, "$name.id").writeText(id)
            File(out, "$name.start").writeText(remote.startCommand(config, target, id, null))
            File(out, "$name.send").writeText(remote.sendCommand(config, target, id,
                """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"Answer only with: it's OK"}]}}"""))
            File(out, "$name.init").writeText(remote.sendCommand(config, target, id,
                """{"type":"control_request","request_id":"init","request":{"subtype":"initialize"}}"""))
            File(out, "$name.tail").writeText(remote.tailCommand(config, target, id, 0))
            File(out, "$name.stop").writeText(remote.stopCommand(config, target, id))
        }
    }
}
