package com.krstech.dockers.ui.screens.stacks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.composables.icons.lucide.*
import com.krstech.dockers.AppContainer
import com.krstech.dockers.engine.EngineState
import com.krstech.dockers.engine.compose.ComposeRunner
import com.krstech.dockers.ui.appViewModel
import com.krstech.dockers.ui.components.*
import com.krstech.dockers.ui.navigation.Routes
import com.krstech.dockers.ui.screens.BottomPadding
import com.krstech.dockers.ui.screens.ConnectionGate
import com.krstech.dockers.ui.screens.ErrorBanner
import com.krstech.dockers.ui.screens.InfoBanner
import com.krstech.dockers.ui.screens.LogView
import com.krstech.dockers.ui.theme.ShadTheme
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StacksUiState(
    val stacks: List<ComposeRunner.Stack> = emptyList(),
    val saved: List<String> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val output: String? = null,
    val running: Boolean = false,
)

@OptIn(FlowPreview::class)
class StacksViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(StacksUiState())
    val state: StateFlow<StacksUiState> = _state.asStateFlow()
    init {
        viewModelScope.launch { app.engine.state.collect { if (it is EngineState.Connected) load() } }
        viewModelScope.launch { app.engine.events.filter { it.type == "container" }.debounce(600).collect { load(true) } }
    }
    fun load(silent: Boolean = false) {
        val client = app.engine.client ?: return
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = true) }
            runCatching { app.compose.groupStacks(client.listContainers(all = true)) }
                .onSuccess { s -> _state.update { it.copy(stacks = s, saved = app.compose.savedStacks(), loading = false, error = null) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }
    fun savedYaml(name: String) = app.compose.savedFile(name)
    fun deleteSaved(name: String) { app.compose.deleteSaved(name); load(true) }

    fun compose(project: String, yaml: String, args: List<String>, save: Boolean = true) {
        val conn = app.engine.state.value as? EngineState.Connected ?: return
        viewModelScope.launch {
            if (save) app.compose.save(project, yaml)
            _state.update { it.copy(output = "", running = true) }
            runCatching {
                app.compose.run(conn.client, conn.profile.hostSocketPath, project, yaml, args).collect { line -> _state.update { it.copy(output = (it.output ?: "") + line + "\n") } }
            }.onFailure { e -> _state.update { it.copy(output = (it.output ?: "") + "\n✖ ${e.message}\n") } }
            _state.update { it.copy(running = false) }
            load(true)
        }
    }

    /** Fallback for stacks without a saved compose file: act on containers directly. */
    fun containersAction(stack: ComposeRunner.Stack, action: String, onDone: (String) -> Unit) {
        val client = app.engine.client ?: return
        viewModelScope.launch {
            runCatching {
                stack.containers.forEach { c ->
                    when (action) { "start" -> client.startContainer(c.id); "stop" -> client.stopContainer(c.id); "restart" -> client.restartContainer(c.id); "remove" -> client.removeContainer(c.id, force = true) }
                }
            }.onSuccess { onDone("Stack ${stack.name}: $action ✓"); load(true) }.onFailure { onDone(it.message ?: "Failed") }
        }
    }
    fun clearOutput() = _state.update { it.copy(output = null) }
}

@Composable
fun StacksScreen(nav: NavHostController) {
    val vm = appViewModel { StacksViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val toast = LocalToast.current
    val c = ShadTheme.colors
    var editor by remember { mutableStateOf<Pair<String, String>?>(null) } // project -> yaml
    var removeTarget by remember { mutableStateOf<ComposeRunner.Stack?>(null) }

    Column(Modifier.fillMaxSize()) {
        ShadTopBar("Stacks", subtitle = "Docker Compose projects", onBack = { nav.popBackStack() }) {
            ShadIconButton(Lucide.RefreshCw, "Refresh", onClick = { vm.load() })
            ShadIconButton(Lucide.Plus, "New stack", onClick = { editor = "my-stack" to ComposeRunner.DEFAULT_TEMPLATE })
        }
        ConnectionGate(onOpenEngines = { nav.navigate(Routes.ENGINES) }, onOpenVm = { nav.navigate(Routes.VM_ENGINE) }) {
            Column(Modifier.fillMaxSize()) {
                ErrorBanner(state.error, Modifier.padding(16.dp))
                state.output?.let { out ->
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (state.running) "Running compose…" else "Compose output", style = MaterialTheme.typography.titleSmall, color = c.foreground, modifier = Modifier.weight(1f))
                            if (!state.running) ShadIconButton(Lucide.X, "Close", onClick = { vm.clearOutput() })
                        }
                        LogView(out, Modifier.height(220.dp))
                    }
                }
                val savedOnly = state.saved.filterNot { s -> state.stacks.any { it.name == s } }
                if (state.loading && state.stacks.isEmpty()) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { repeat(3) { ShadSkeleton(Modifier.fillMaxWidth().height(90.dp)) } }
                else if (state.stacks.isEmpty() && savedOnly.isEmpty()) EmptyState("No stacks", "Deploy a compose.yaml to create a multi-container stack. The compose CLI runs in a helper container (docker:cli) on the engine host.", icon = Lucide.Boxes,
                    action = { ShadButton(onClick = { editor = "my-stack" to ComposeRunner.DEFAULT_TEMPLATE }, leadingIcon = Lucide.Plus) { ButtonText("New stack") } })
                else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { InfoBanner("Stacks are grouped by the com.docker.compose.project label, so projects deployed from any machine show up here. Up/Down requires the compose file saved in this app.") }
                    items(state.stacks, key = { it.name }) { stack ->
                        val saved = vm.savedYaml(stack.name)
                        ShadCard(contentPadding = PaddingValues(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stack.name, style = MaterialTheme.typography.titleSmall, color = c.foreground)
                                    Text("${stack.running}/${stack.containers.size} services running", style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
                                }
                                ShadBadge(if (stack.running == stack.containers.size) "running" else if (stack.running == 0) "stopped" else "partial", if (stack.running == stack.containers.size) BadgeVariant.Success else if (stack.running == 0) BadgeVariant.Secondary else BadgeVariant.Warning, dot = true)
                                ShadMenu(buildList {
                                    if (saved != null) {
                                        add(MenuAction("Up (recreate)", Lucide.Play) { vm.compose(stack.name, saved, listOf("up", "-d", "--remove-orphans")) })
                                        add(MenuAction("Pull & up", Lucide.Download) { vm.compose(stack.name, saved, listOf("up", "-d", "--pull", "always")) })
                                        add(MenuAction("Edit compose file", Lucide.Pencil) { editor = stack.name to saved })
                                        add(MenuAction("Down (remove)", Lucide.Trash2, destructive = true) { vm.compose(stack.name, saved, listOf("down", "--remove-orphans")) })
                                    }
                                    add(MenuAction("Start containers", Lucide.Play) { vm.containersAction(stack, "start", toast) })
                                    add(MenuAction("Stop containers", Lucide.Square) { vm.containersAction(stack, "stop", toast) })
                                    add(MenuAction("Restart containers", Lucide.RotateCw) { vm.containersAction(stack, "restart", toast) })
                                    add(MenuAction("Remove containers", Lucide.Trash2, destructive = true) { removeTarget = stack })
                                }, icon = Lucide.EllipsisVertical)
                            }
                            Spacer(Modifier.height(8.dp))
                            stack.containers.forEach { ct ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { nav.navigate(Routes.containerDetail(ct.id)) }, verticalAlignment = Alignment.CenterVertically) {
                                    StatusDot(ct.state); Spacer(Modifier.width(8.dp))
                                    Text(ct.composeService ?: ct.name, style = MaterialTheme.typography.bodySmall, color = c.foreground, modifier = Modifier.weight(1f))
                                    Text(ct.image, style = MaterialTheme.typography.labelSmall, color = c.mutedForeground, maxLines = 1)
                                }
                            }
                        }
                    }
                    if (savedOnly.isNotEmpty()) item { SectionTitle("Saved, not deployed") }
                    items(savedOnly, key = { "saved-$it" }) { name ->
                        ShadCard(contentPadding = PaddingValues(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text(name, style = MaterialTheme.typography.titleSmall, color = c.foreground); Text("compose file saved", style = MaterialTheme.typography.bodySmall, color = c.mutedForeground) }
                                ShadButton(onClick = { vm.savedYaml(name)?.let { vm.compose(name, it, listOf("up", "-d")) } }, size = ButtonSize.Sm, leadingIcon = Lucide.Play) { ButtonText("Up") }
                                Spacer(Modifier.width(4.dp))
                                ShadMenu(listOf(MenuAction("Edit", Lucide.Pencil) { editor = name to (vm.savedYaml(name) ?: "") }, MenuAction("Delete file", Lucide.Trash2, destructive = true) { vm.deleteSaved(name) }), icon = Lucide.EllipsisVertical)
                            }
                        }
                    }
                    item { BottomPadding() }
                }
            }
        }
    }

    editor?.let { (initialName, initialYaml) ->
        var name by remember { mutableStateOf(initialName) }
        var yaml by remember { mutableStateOf(initialYaml) }
        ShadSheet(onDismiss = { editor = null }, title = "Compose stack", description = "Paste a compose.yaml; it is saved in the app and deployed with `docker compose up -d`.") {
          Column(Modifier.verticalScroll(rememberScrollState())) {
            ShadInput(name, { name = it }, label = "Project name", placeholder = "my-stack")
            Spacer(Modifier.height(10.dp))
            ShadInput(yaml, { yaml = it }, label = "compose.yaml", singleLine = false, minLines = 10, monospace = true)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShadButton(onClick = { vm.compose(name.trim(), yaml, listOf("config", "--quiet"), save = false); }, variant = ButtonVariant.Outline, modifier = Modifier.weight(1f), enabled = name.isNotBlank() && yaml.isNotBlank()) { ButtonText("Validate") }
                ShadButton(onClick = { editor = null; vm.compose(name.trim(), yaml, listOf("up", "-d", "--remove-orphans")) }, modifier = Modifier.weight(1f), enabled = name.isNotBlank() && yaml.isNotBlank(), leadingIcon = Lucide.Rocket) { ButtonText("Deploy") }
            }
          }
        }
    }
    removeTarget?.let { s -> ShadDialog(onDismiss = { removeTarget = null }, title = "Remove stack ${s.name}?", description = "All ${s.containers.size} containers will be force-removed (volumes are kept).", confirmText = "Remove", destructive = true, onConfirm = { removeTarget = null; vm.containersAction(s, "remove", toast) }) }
}
