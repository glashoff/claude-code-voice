package dev.claudecodevoice.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.claudecodevoice.MainViewModel
import dev.claudecodevoice.SttEngine
import dev.claudecodevoice.SttLanguage
import dev.claudecodevoice.WhisperModel

/** Everything that is set up once and rarely changed; the main screen keeps only what is used while talking. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                actions = { TextButton(onClick = onClose) { Text("Fertig") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RecognitionSettings(vm)
            SendingSettings(vm)
            SpeechOutputSettings(vm)
            ConnectionSettings(vm)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun CheckRow(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

@Composable
private fun RecognitionSettings(vm: MainViewModel) = Section("Spracherkennung") {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SttEngine.entries.forEachIndexed { i, e ->
            SegmentedButton(
                selected = vm.engine == e,
                onClick = { vm.switchEngine(e) },
                shape = SegmentedButtonDefaults.itemShape(i, SttEngine.entries.size),
            ) { Text(e.label) }
        }
    }
    Dropdown("Sprache", SttLanguage.entries, vm.language, { it.label }) { vm.language = it }
    when (vm.engine) {
        SttEngine.WHISPER -> {
            Dropdown("Modell", WhisperModel.entries, vm.whisperModel, { it.label }) { vm.whisperModel = it }
            OutlinedTextField(
                value = vm.vocabularyPrompt,
                onValueChange = { vm.vocabularyPrompt = it },
                label = { Text("Begriffe-Hinweis für Whisper") },
                modifier = Modifier.fillMaxWidth(),
            )
            FilledTonalButton(onClick = vm::loadModel, enabled = !vm.modelBusy) { Text("Modell laden") }
            if (vm.modelBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(vm.modelStatus, style = MaterialTheme.typography.bodySmall)
        }
        SttEngine.CONTINUOUS -> {
            CheckRow(vm.languageSwitch, "Zwischen Deutsch und Englisch automatisch umschalten (Android 14+)") { vm.languageSwitch = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::checkContinuousSupport) { Text("Unterstützung prüfen") }
                OutlinedButton(onClick = vm::downloadContinuousLanguage) { Text("Sprachpaket laden") }
            }
            if (vm.continuousSupport.isNotEmpty()) Text(vm.continuousSupport, style = MaterialTheme.typography.bodySmall)
        }
        SttEngine.ANDROID -> CheckRow(vm.preferOffline, "Offline bevorzugen (falls Sprachpaket installiert)") { vm.preferOffline = it }
    }
    CheckRow(vm.muteWhileSpeaking, "Mikrofon während der Ausgabe pausieren") { vm.muteWhileSpeaking = it }
    CheckRow(vm.headsetMic, "Mikrofon des Headsets verwenden (Bluetooth: Wiedergabe in Telefonqualität, gilt ab nächstem Mikrofonstart)") {
        vm.headsetMic = it
    }
}

@Composable
private fun SendingSettings(vm: MainViewModel) = Section("Senden an Claude") {
    Text(
        if (vm.sendPauseSec > 0f) "Senden nach %.1f s Pause".format(vm.sendPauseSec) else "Senden nach Pause: aus",
        style = MaterialTheme.typography.bodySmall,
    )
    Slider(value = vm.sendPauseSec, onValueChange = { vm.sendPauseSec = (it * 2).toInt() / 2f }, valueRange = 0f..10f, steps = 19)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(vm.sendKeyword, { vm.sendKeyword = it }, label = { Text("Schlüsselwort") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(vm.menuKeyword, { vm.menuKeyword = it }, label = { Text("Menü-Wort") }, singleLine = true, modifier = Modifier.weight(1f))
    }
    OutlinedTextField(vm.stopWords, { vm.stopWords = it }, label = { Text("Stopp-Wörter (Komma-getrennt)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    CheckRow(vm.announceSteps, "Zwischenschritte vorlesen") { vm.announceSteps = it }
    CheckRow(vm.bargeIn, "Vorlesen unterbrechen, sobald ich spreche") { vm.bargeIn = it }
    CheckRow(vm.thinkingSoundEnabled, "Denkgeräusch, solange Claude arbeitet") { vm.thinkingSoundEnabled = it }
    if (vm.thinkingSoundEnabled) {
        Text("Lautstärke Denkgeräusch: ${(vm.thinkingVolume * 100).toInt()} %", style = MaterialTheme.typography.bodySmall)
        Slider(value = vm.thinkingVolume, onValueChange = { vm.thinkingVolume = it }, valueRange = 0.05f..1f)
    }
}

@Composable
private fun SpeechOutputSettings(vm: MainViewModel) = Section("Sprachausgabe") {
    // Engine and voice are remembered per language; this switch also changes the reply language.
    Text("Einstellungen für die Antwortsprache:", style = MaterialTheme.typography.bodySmall)
    LanguageToggle(vm)
    if (vm.ttsEngines.isNotEmpty()) {
        val engine = vm.ttsEngines.firstOrNull { it.first == vm.ttsEngine } ?: vm.ttsEngines.first()
        Dropdown("Sprachausgabe", vm.ttsEngines, engine, { it.second }) { vm.selectEngine(it.first) }
    }
    vm.ttsStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (vm.voices.isEmpty()) {
        Text("Keine Stimmen gefunden (oder Sprachausgabe startet noch).", style = MaterialTheme.typography.bodySmall)
    } else {
        Dropdown("Stimme", vm.voices, vm.selectedVoice ?: vm.voices.first(), { v ->
            "${v.locale.toLanguageTag()} · ${v.name}" + if (v.isNetworkConnectionRequired) " (online)" else ""
        }) { vm.selectVoice(it) }
    }
    Text("Tempo: %.2f×".format(vm.speechRate), style = MaterialTheme.typography.bodySmall)
    Slider(value = vm.speechRate, onValueChange = { vm.speechRate = it }, valueRange = 0.6f..1.6f, steps = 9)
    Text("Vorlauf für Headset: ${vm.leadInMs} ms", style = MaterialTheme.typography.bodySmall)
    Slider(
        value = vm.leadInMs.toFloat(),
        onValueChange = { vm.leadInMs = (it / 100).toInt() * 100 },
        valueRange = 0f..1500f,
        steps = 14,
    )
    Text("Testen:", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(value = vm.ttsText, onValueChange = { vm.ttsText = it }, modifier = Modifier.fillMaxWidth(), minLines = 3)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = vm::speak) { Text("Vorlesen") }
        OutlinedButton(onClick = vm::stopSpeaking) { Text("Stopp") }
        OutlinedButton(onClick = vm::sendTypedText, enabled = vm.project.isNotEmpty()) { Text("An Claude") }
    }
}

@Composable
private fun ConnectionSettings(vm: MainViewModel) = Section("Verbindung") {
    val clipboard = LocalClipboardManager.current
    OutlinedTextField(vm.serverHost, { vm.serverHost = it }, label = { Text("Server (Host/IP)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            vm.serverPort, { vm.serverPort = it }, label = { Text("Port") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
        )
        OutlinedTextField(vm.serverUser, { vm.serverUser = it }, label = { Text("Benutzer") }, singleLine = true, modifier = Modifier.weight(2f))
    }
    OutlinedTextField(vm.projectRoot, { vm.projectRoot = it }, label = { Text("Projektordner") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(vm.allowedTools, { vm.allowedTools = it }, label = { Text("Erlaubte Werkzeuge (ohne Rückfrage)") }, modifier = Modifier.fillMaxWidth())
    Text("Öffentlicher Schlüssel der App – in ~/.ssh/authorized_keys auf dem Server eintragen:", style = MaterialTheme.typography.bodySmall)
    SelectionContainer {
        Text(vm.publicKey.ifEmpty { "wird erzeugt …" }, style = MaterialTheme.typography.labelSmall)
    }
    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(vm.publicKey)) }, enabled = vm.publicKey.isNotEmpty()) {
        Text("Schlüssel kopieren")
    }
    Text(
        if (vm.hostKeyFingerprint.isEmpty()) "Host-Schlüssel: wird bei der ersten Verbindung gespeichert"
        else "Host-Schlüssel: ${vm.hostKeyFingerprint}",
        style = MaterialTheme.typography.labelSmall,
    )
    if (vm.hostKeyFingerprint.isNotEmpty()) {
        TextButton(onClick = vm::forgetHostKey) { Text("Host-Schlüssel vergessen") }
    }
}
