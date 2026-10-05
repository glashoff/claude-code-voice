package dev.claudecodevoice.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.claudecodevoice.MainViewModel
import dev.claudecodevoice.claudeModels
import dev.claudecodevoice.Role
import dev.claudecodevoice.SessionInfo
import dev.claudecodevoice.SttEngine
import dev.claudecodevoice.SttLanguage
import dev.claudecodevoice.WhisperModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val context = LocalContext.current
    // Microphone is required; notifications only make the "listening" notification visible.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) vm.startListening()
    }
    fun toggleListening() {
        if (vm.listening) return vm.stopListening()
        val needed = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_PHONE_STATE).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) vm.startListening() else permissionLauncher.launch(needed.toTypedArray())
    }
    // Start listening right away when the app opens (once per app process, not after every screen rotation).
    LaunchedEffect(Unit) {
        if (!vm.autoStartDone && vm.canListen()) {
            vm.autoStartDone = true
            if (!vm.listening) toggleListening()
        }
    }

    val listState = rememberLazyListState()
    // Follow the end of the chat while it is scrolled to the bottom; after the user scrolled up to read, stay put
    // until they scroll back down. Nothing moves while a finger holds the list.
    var followChat by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) followChat = !listState.canScrollForward
        }
    }
    LaunchedEffect(
        vm.transcript.size, vm.transcript.lastOrNull()?.text?.length, vm.partialText, vm.draft,
        vm.permissionRequests.size, vm.menuActive, vm.quickSettingsOpen,
    ) {
        // Wait one frame so a changed list height (quick settings opened or closed) is measured first.
        withFrameNanos { }
        val last = listState.layoutInfo.totalItemsCount - 1
        // A large offset reaches the bottom of the last item even if it is taller than the screen; it is clamped.
        if (followChat && !listState.isScrollInProgress && last >= 0) listState.scrollToItem(last, Int.MAX_VALUE / 2)
    }

    var showSettings by rememberSaveable { mutableStateOf(false) }
    if (showSettings) {
        SettingsScreen(vm, onClose = { showSettings = false })
        return
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Claude Code Voice") },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                        }
                    },
                )
                StatusBar(vm, ::toggleListening)
            }
        },
    ) { padding ->
      // The quick settings unfold inside the status bar above: the chat area gets smaller and its bottom edge stays.
      // imePadding: the input field at the bottom moves above the keyboard.
      Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // --- Transcript header (item 2) ---
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Chat", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::clearTranscript) { Text("Leeren") }
                }
                if (vm.transcript.isEmpty()) {
                    Text("Noch nichts gesagt.", style = MaterialTheme.typography.bodySmall)
                }
            }

            // --- Transcript entries (items 3..) ---
            items(vm.transcript) { entry ->
                val isClaude = entry.role == Role.CLAUDE
                Column(
                    Modifier.fillMaxWidth()
                        .then(if (isClaude) Modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.secondaryContainer).padding(8.dp) else Modifier),
                ) {
                    Text(
                        entry.text,
                        color = when {
                            entry.isError -> MaterialTheme.colorScheme.error
                            isClaude -> MaterialTheme.colorScheme.onSecondaryContainer
                            else -> Color.Unspecified
                        },
                    )
                    Text(entry.meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (vm.menuActive) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Sprachmenü aktiv – sag „Hilfe“ oder „Fertig“", modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.exitMenu() }) { Text("Schließen") }
                        }
                    }
                }
            }
            vm.permissionRequests.firstOrNull()?.let { request ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val more = vm.permissionRequests.size - 1
                            Text(
                                "Claude bittet um Erlaubnis" + if (more > 0) " (+$more weitere)" else "",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(vm.describePermission(request, forSpeech = false))
                            Text(
                                "Sag „ja“ oder „bestätige“, „alles erlauben“ (bis Claude fertig ist) oder „nein“. " +
                                    "Alles andere lehnt ab und geht als Nachtrag an Claude. Ohne Antwort nach 3 Minuten abgelehnt.",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            // Wraps onto a second line on narrow screens instead of squeezing the last button.
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.allowPermission(request) }) { Text("Erlauben") }
                                OutlinedButton(onClick = vm::allowAll) { Text("Alles erlauben") }
                                OutlinedButton(onClick = { vm.denyPermission(request) }) { Text("Ablehnen") }
                            }
                        }
                    }
                }
            }

        }
        // Draft and live preview: a fixed input field at the bottom, like in a messenger.
        DraftBox(vm)
      }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> Dropdown(
    label: String, options: List<T>, selected: T, text: (T) -> String, onOpen: () -> Unit = {}, onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it; if (it) onOpen() }) {
        OutlinedTextField(
            value = text(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(text(option)) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

/**
 * The draft for Claude: filled by voice, editable with the keyboard (typing holds the countdown), scrolls within
 * itself when long. The live preview of what is being said appears below it.
 */
@Composable
private fun DraftBox(vm: MainViewModel) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = vm.draft,
                    onValueChange = vm::editDraft,
                    placeholder = { Text("Sprechen oder tippen …") },
                    maxLines = 5,
                    trailingIcon = if (vm.draft.isNotEmpty()) {
                        { IconButton(onClick = vm::discardDraft) { Icon(Icons.Filled.Clear, contentDescription = "Entwurf löschen") } }
                    } else null,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = vm::sendDraft, enabled = vm.draft.isNotBlank()) { Text("Senden") }
            }
            if (vm.partialText.isNotEmpty()) {
                Text(vm.partialText, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Always visible below the title: microphone on/off and state, what Claude is doing, and a stop button. */
@Composable
private fun StatusBar(vm: MainViewModel, toggleListening: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Toggle: highlighted while muted (not listening), outlined otherwise.
            if (vm.listening) {
                OutlinedButton(onClick = toggleListening) { Text("Stumm") }
            } else {
                Button(onClick = toggleListening, enabled = vm.canListen()) { Text("Stumm") }
            }
            Spacer(Modifier.width(12.dp))
            val dotColor = when {
                vm.listening && vm.micSilent -> Color(0xFFEF4444)
                vm.userSpeaking -> Color(0xFFF97316)
                vm.pendingCount > 0 -> Color(0xFFFBBF24)
                vm.listening -> Color(0xFF22C55E)
                else -> Color.Gray
            }
            Box(Modifier.size(12.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    vm.listening && vm.micSilent -> "Mikrofon stumm"
                    vm.userSpeaking -> "Sprache erkannt"
                    vm.pendingCount > 0 -> "erkenne …"
                    vm.listening -> "hört zu"
                    else -> "aus"
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (vm.claudeBusy || vm.outputSpeaking) {
                Button(onClick = { vm.stopEverything() }) { Text("Stopp") }
            }
        }
        val left = vm.draftSecondsLeft
        val status = when {
            left != null -> "Wird in %.1f s gesendet".format(left)
            vm.draftHeld -> "Entwurf angehalten – sprich einfach weiter"
            vm.draft.isNotEmpty() && vm.sendKeyword.isNotBlank() -> "Entwurf wartet auf „${vm.sendKeyword}“"
            else -> vm.claudeStatus.ifEmpty { if (vm.project.isEmpty()) "Kein Projekt gewählt." else "Projekt ${vm.project}" }
        }
        Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        if (vm.claudeBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        // The bar grows downwards to show the quick settings; the toggle sits on its bottom edge.
        AnimatedVisibility(visible = vm.quickSettingsOpen, enter = expandVertically(), exit = shrinkVertically()) {
            QuickSettings(vm)
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            IconButton(onClick = { vm.quickSettingsOpen = !vm.quickSettingsOpen }, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (vm.quickSettingsOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (vm.quickSettingsOpen) "Schnelleinstellungen zuklappen" else "Schnelleinstellungen aufklappen",
                )
            }
        }
    }
}

/** Reply language (and thereby output engine and voice, which are remembered per language). */
@Composable
internal fun LanguageToggle(vm: MainViewModel) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf("en" to "Englisch", "de" to "Deutsch").forEachIndexed { i, (code, label) ->
            SegmentedButton(
                selected = vm.outputLanguage == code,
                onClick = { vm.switchLanguage(code) },
                shape = SegmentedButtonDefaults.itemShape(i, 2),
            ) { Text(label) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickSettings(vm: MainViewModel) {
    run {
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val projectOptions = (vm.projects + vm.project).filter { it.isNotEmpty() }.distinct()
            Dropdown("Projekt", projectOptions, vm.project, { it.ifEmpty { "Projekt wählen" } }, onOpen = vm::loadProjects) {
                vm.selectProject(it)
            }
            if (vm.project.isNotEmpty()) {
                // null stands for "new conversation".
                val currentId = vm.currentSessionId()
                val current = vm.sessions.firstOrNull { it.id == currentId }
                    ?: currentId.takeIf { it.isNotEmpty() }?.let { SessionInfo(it, 0, vm.currentSessionTitle.ifEmpty { "Sitzung" }) }
                Dropdown<SessionInfo?>(
                    "Sitzung", listOf<SessionInfo?>(null) + vm.sessions, current,
                    { session ->
                        when {
                            session == null -> "Neues Gespräch"
                            session.modifiedEpochSec == 0L -> session.title
                            else -> session.title + " · " + java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMANY)
                                .format(java.util.Date(session.modifiedEpochSec * 1000))
                        }
                    },
                    onOpen = vm::loadSessions,
                ) { session -> if (session == null) vm.newConversation() else vm.resumeSession(session) }
            }
            if (vm.project.isNotEmpty()) {
                TextButton(onClick = vm::endRun) { Text("Sitzung beenden (Claude auf dem Rechner stoppen)") }
            }
            val model = claudeModels.firstOrNull { it.first == vm.claudeModel.trim() } ?: (vm.claudeModel to vm.claudeModel)
            Dropdown("Modell", claudeModels, model, { it.second }) { vm.claudeModel = it.first }
            Text("Antwortsprache", style = MaterialTheme.typography.bodySmall)
            LanguageToggle(vm)
        }
    }
}
