package com.krstech.dockers.ui.screens.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.composables.icons.lucide.*
import com.krstech.dockers.AppContainer
import com.krstech.dockers.engine.AppSettings
import com.krstech.dockers.ui.LocalAppContainer
import com.krstech.dockers.ui.appViewModel
import com.krstech.dockers.ui.components.*
import com.krstech.dockers.ui.screens.ErrorBanner
import com.krstech.dockers.ui.screens.LogView
import com.krstech.dockers.ui.theme.ShadTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class LegacyTerminalViewModel(private val app: AppContainer, private val containerId: String?) : ViewModel() {
    var session: LineSession? by mutableStateOf(null)
        private set
    var mode = if (containerId != null) "container" else "shell"
        private set

    fun open(mode: String, user: String? = null) {
        session?.close()
        this.mode = mode
        val s: LineSession = when (mode) {
            "container" -> {
                val client = app.engine.client ?: return
                LineSession.Exec(viewModelScope, client, containerId!!, app.distros.shellCommand(), user)
            }
            "root" -> LineSession.Local(viewModelScope, root = true)
            else -> LineSession.Local(viewModelScope, root = false)
        }
        session = s
        viewModelScope.launch { s.open() }
    }

    override fun onCleared() { session?.close() }
}

@Composable
fun LegacyTerminalScreen(nav: NavHostController, containerId: String?, title: String?) {
    val vm = appViewModel(key = "legacy-terminal-${containerId ?: "local"}") { LegacyTerminalViewModel(it, containerId) }
    val settings by LocalAppContainer.current.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val c = ShadTheme.colors
    var mode by remember { mutableStateOf(if (containerId != null) "container" else "shell") }
    var input by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(listOf<String>()) }
    var historyIdx by remember { mutableStateOf(-1) }

    LaunchedEffect(mode) { if (vm.session == null || vm.mode != mode) vm.open(mode) }
    DisposableEffect(Unit) { onDispose { } }
    val session = vm.session
    val stateFlow = remember(session) { session?.state ?: MutableStateFlow(LineSession.State()) }
    val state by stateFlow.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().imePadding()) {
        ShadTopBar(title ?: if (containerId != null) "Terminal" else "Device shell", subtitle = when (mode) { "container" -> "docker exec -it"; "root" -> "su"; else -> "/system/bin/sh" }, onBack = { nav.popBackStack() }) {
            ShadBadge(if (state.exited) "exited" else if (state.connected) "connected" else "connecting", if (state.exited) BadgeVariant.Secondary else if (state.connected) BadgeVariant.Success else BadgeVariant.Warning, dot = true)
            Spacer(Modifier.width(4.dp))
            ShadMenu(buildList {
                add(MenuAction("Reconnect", Lucide.RefreshCw) { vm.open(mode) })
                add(MenuAction("Clear", Lucide.Eraser) { session?.clear() })
                if (containerId != null) add(MenuAction("Reconnect as root (uid 0)", Lucide.Shield) { vm.open("container", user = "0") })
                else { add(MenuAction("Normal shell", Lucide.Terminal) { mode = "shell"; vm.open("shell") }); add(MenuAction("Root shell (su)", Lucide.Shield) { mode = "root"; vm.open("root") }) }
            }, icon = Lucide.EllipsisVertical)
        }
        ErrorBanner(state.error, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LogView(state.output, Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp), fontSize = settings.terminalFontSize)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyButton("Ctrl+C") { session?.sendCtrlC() }
            KeyButton("Ctrl+D") { session?.sendCtrlD() }
            KeyButton("Tab") { session?.sendTab() }
            KeyButton("Esc") { session?.sendEsc() }
            KeyButton("↑") { session?.sendArrow('A') }
            KeyButton("↓") { session?.sendArrow('B') }
            KeyButton("←") { session?.sendArrow('D') }
            KeyButton("→") { session?.sendArrow('C') }
            KeyButton("clear") { session?.sendLine("clear") }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            ShadInput(
                input, { input = it }, modifier = Modifier.weight(1f), placeholder = "Type a command…", monospace = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSend = { session?.sendLine(input); history = (listOf(input) + history).take(100); historyIdx = -1; input = "" }),
                enabled = state.connected,
            )
            Spacer(Modifier.width(8.dp))
            ShadIconButton(Lucide.CornerDownLeft, "Send", variant = ButtonVariant.Default, enabled = state.connected, onClick = {
                session?.sendLine(input); history = (listOf(input) + history).take(100); historyIdx = -1; input = ""
            })
        }
        if (history.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                history.take(8).forEach { h -> ShadChip(h.take(24), false, { input = h }) }
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, onClick: () -> Unit) {
    ShadButton(onClick = onClick, variant = ButtonVariant.Outline, size = ButtonSize.Sm) { ButtonText(label) }
}
