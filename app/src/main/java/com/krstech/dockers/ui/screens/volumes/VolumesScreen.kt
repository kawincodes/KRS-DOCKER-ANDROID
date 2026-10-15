package com.krstech.dockers.ui.screens.volumes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.composables.icons.lucide.*
import com.krstech.dockers.AppContainer
import com.krstech.dockers.engine.EngineState
import com.krstech.dockers.engine.model.Volume
import com.krstech.dockers.ui.appViewModel
import com.krstech.dockers.ui.components.*
import com.krstech.dockers.ui.navigation.Routes
import com.krstech.dockers.ui.screens.BottomPadding
import com.krstech.dockers.ui.screens.ConnectionGate
import com.krstech.dockers.ui.screens.ErrorBanner
import com.krstech.dockers.ui.screens.JsonViewer
import com.krstech.dockers.ui.theme.ShadTheme
import com.krstech.dockers.util.Format
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

data class VolumesUiState(val volumes: List<Volume> = emptyList(), val usedBy: Map<String, Int> = emptyMap(), val loading: Boolean = true, val error: String? = null, val query: String = "") {
    val filtered get() = volumes.filter { query.isBlank() || it.name.contains(query, true) }.sortedBy { it.name.lowercase() }
}

@OptIn(FlowPreview::class)
class VolumesViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(VolumesUiState())
    val state: StateFlow<VolumesUiState> = _state.asStateFlow()
    init {
        viewModelScope.launch { app.engine.state.collect { if (it is EngineState.Connected) load() } }
        viewModelScope.launch { app.engine.events.filter { it.type == "volume" || it.type == "container" }.debounce(500).collect { load(true) } }
    }
    fun load(silent: Boolean = false) {
        val client = app.engine.client ?: return
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = true) }
            runCatching {
                val vols = client.listVolumes()
                val containers = runCatching { client.listContainers(all = true) }.getOrDefault(emptyList())
                val used = containers.flatMap { c -> c.mounts.mapNotNull { it.name } }.groupingBy { it }.eachCount()
                vols to used
            }.onSuccess { (v, u) -> _state.update { it.copy(volumes = v, usedBy = u, loading = false, error = null) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }
    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun create(name: String, driver: String, onDone: (String) -> Unit) {
        val client = app.engine.client ?: return
        viewModelScope.launch { runCatching { client.createVolume(name, driver) }.onSuccess { onDone("Created $name"); load(true) }.onFailure { onDone(it.message ?: "Failed") } }
    }
    fun remove(name: String, force: Boolean, onDone: (String) -> Unit) {
        val client = app.engine.client ?: return
        viewModelScope.launch { runCatching { client.removeVolume(name, force) }.onSuccess { onDone("Removed $name"); load(true) }.onFailure { onDone(it.message ?: "Failed") } }
    }
    fun prune(onDone: (String) -> Unit) {
        val client = app.engine.client ?: return
        viewModelScope.launch { runCatching { client.pruneVolumes() }.onSuccess { onDone("Removed ${it.volumesDeleted?.size ?: 0} volumes, reclaimed ${Format.bytes(it.spaceReclaimed)}"); load(true) }.onFailure { onDone(it.message ?: "Failed") } }
    }
    suspend fun inspectRaw(name: String): JsonElement? = app.engine.client?.let { runCatching { it.inspectVolumeRaw(name) }.getOrNull() }
}

@Composable
fun VolumesScreen(nav: NavHostController) {
    val vm = appViewModel { VolumesViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val toast = LocalToast.current
    val c = ShadTheme.colors
    var showCreate by remember { mutableStateOf(false) }
    var confirmPrune by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<Volume?>(null) }
    var inspectTarget by remember { mutableStateOf<Volume?>(null) }

    Column(Modifier.fillMaxSize()) {
        ShadTopBar("Volumes", subtitle = "${state.volumes.size} volumes", onBack = { nav.popBackStack() }) {
            ShadIconButton(Lucide.Plus, "Create", onClick = { showCreate = true })
            ShadMenu(listOf(MenuAction("Refresh", Lucide.RefreshCw) { vm.load() }, MenuAction("Prune unused", Lucide.Trash2, destructive = true) { confirmPrune = true }), icon = Lucide.EllipsisVertical)
        }
        ConnectionGate(onOpenEngines = { nav.navigate(Routes.ENGINES) }, onOpenVm = { nav.navigate(Routes.VM_ENGINE) }) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { ShadInput(state.query, vm::setQuery, placeholder = "Filter volumes", leadingIcon = Lucide.Search) }
                ErrorBanner(state.error, Modifier.padding(horizontal = 16.dp))
                if (state.loading && state.volumes.isEmpty()) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { repeat(4) { ShadSkeleton(Modifier.fillMaxWidth().height(64.dp)) } }
                else if (state.filtered.isEmpty()) EmptyState("No volumes", "Volumes persist data outside containers.", icon = Lucide.HardDrive, action = { ShadButton(onClick = { showCreate = true }, leadingIcon = Lucide.Plus) { ButtonText("Create volume") } })
                else LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.filtered, key = { it.name }) { v ->
                        val used = state.usedBy[v.name] ?: 0
                        ShadCard(contentPadding = PaddingValues(12.dp), onClick = { inspectTarget = v }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(v.name, style = MaterialTheme.typography.titleSmall, color = c.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(v.mountpoint, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        ShadBadge(v.driver, BadgeVariant.Secondary)
                                        ShadBadge(if (used > 0) "in use · $used" else "unused", if (used > 0) BadgeVariant.Success else BadgeVariant.Outline)
                                        v.createdAt?.let { ShadBadge(Format.relativeIso(it), BadgeVariant.Outline) }
                                    }
                                }
                                ShadMenu(listOf(MenuAction("Inspect", Lucide.FileJson) { inspectTarget = v }, MenuAction("Remove", Lucide.Trash2, destructive = true) { removeTarget = v }), icon = Lucide.EllipsisVertical)
                            }
                        }
                    }
                    item { BottomPadding() }
                }
            }
        }
    }
    if (showCreate) {
        var name by remember { mutableStateOf("") }; var driver by remember { mutableStateOf("local") }
        ShadDialog(onDismiss = { showCreate = false }, title = "Create volume", confirmText = "Create", confirmEnabled = name.isNotBlank(), onConfirm = { showCreate = false; vm.create(name.trim(), driver, toast) }) {
            ShadInput(name, { name = it }, label = "Name", placeholder = "app-data"); Spacer(Modifier.height(10.dp))
            ShadInput(driver, { driver = it }, label = "Driver", placeholder = "local")
        }
    }
    if (confirmPrune) ShadDialog(onDismiss = { confirmPrune = false }, title = "Prune unused volumes?", description = "All volumes not used by at least one container will be deleted. Data cannot be recovered.", confirmText = "Prune", destructive = true, onConfirm = { confirmPrune = false; vm.prune(toast) })
    removeTarget?.let { v ->
        var force by remember { mutableStateOf(false) }
        ShadDialog(onDismiss = { removeTarget = null }, title = "Remove ${v.name}?", description = "All data in the volume will be lost.", confirmText = "Remove", destructive = true, onConfirm = { removeTarget = null; vm.remove(v.name, force, toast) }) {
            SwitchRow("Force", checked = force, onCheckedChange = { force = it })
        }
    }
    inspectTarget?.let { v ->
        var raw by remember { mutableStateOf<JsonElement?>(null) }
        androidx.compose.runtime.LaunchedEffect(v.name) { raw = vm.inspectRaw(v.name) }
        ShadSheet(onDismiss = { inspectTarget = null }, title = v.name, description = v.mountpoint) { JsonViewer(raw, Modifier.height(440.dp)) }
    }
}
