package com.krstech.dockers.ui.screens.distros

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.composables.icons.lucide.*
import com.krstech.dockers.AppContainer
import com.krstech.dockers.engine.EngineState
import com.krstech.dockers.engine.distro.DistroManager
import com.krstech.dockers.ui.appViewModel
import com.krstech.dockers.ui.components.*
import com.krstech.dockers.ui.navigation.Routes
import com.krstech.dockers.ui.screens.BottomPadding
import com.krstech.dockers.ui.screens.ConnectionGate
import com.krstech.dockers.ui.screens.ErrorBanner
import com.krstech.dockers.ui.screens.InfoBanner
import com.krstech.dockers.ui.screens.LogView
import com.krstech.dockers.ui.theme.ShadTheme
import com.krstech.dockers.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DistrosUiState(val distros: List<DistroManager.Distro> = emptyList(), val loading: Boolean = true, val error: String? = null, val log: String? = null, val busy: Boolean = false)

@OptIn(FlowPreview::class)
class DistrosViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(DistrosUiState())
    val state: StateFlow<DistrosUiState> = _state.asStateFlow()
    val bases get() = app.distros.bases
    init {
        viewModelScope.launch { app.engine.state.collect { if (it is EngineState.Connected) load() } }
        viewModelScope.launch { app.engine.events.filter { it.type == "container" }.debounce(500).collect { load(true) } }
    }
    fun load(silent: Boolean = false) {
        val client = app.engine.client ?: return
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = true) }
            runCatching { app.distros.fromContainers(client.listContainers(all = true)) }
                .onSuccess { d -> _state.update { it.copy(distros = d, loading = false, error = null) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }
    fun install(name: String, image: String, privileged: Boolean, onDone: (String) -> Unit) {
        val client = app.engine.client ?: return
        viewModelScope.launch {
            _state.update { it.copy(log = "", busy = true) }
            runCatching { app.distros.create(client, name, image, privileged = privileged).collect { l -> _state.update { it.copy(log = (it.log ?: "") + l + "\n") } } }
                .onSuccess { onDone("Installed $name") }.onFailure { e -> _state.update { it.copy(log = (it.log ?: "") + "✖ ${e.message}\n") }; onDone(e.message ?: "Install failed") }
            _state.update { it.copy(busy = false) }
            load(true)
        }
    }
    fun action(d: DistroManager.Distro, action: String, onDone: (String) -> Unit) {
        val client = app.engine.client ?: return
        viewModelScope.launch {
            runCatching {
                when (action) {
                    "start" -> client.startContainer(d.container.id)
                    "stop" -> client.stopContainer(d.container.id)
                    "restart" -> client.restartContainer(d.container.id)
                    "unregister" -> { client.removeContainer(d.container.id, force = true); runCatching { client.removeVolume(app.distros.volumeName(d.name)) } }
                }
            }.onSuccess { onDone("${d.name}: $action ✓"); load(true) }.onFailure { onDone(it.message ?: "Failed") }
        }
    }
    fun export(d: DistroManager.Distro, uri: Uri, context: android.content.Context, onDone: (String) -> Unit) {
        val client = app.engine.client ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, log = "Exporting ${d.name}…\n") }
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)!!.use { out -> client.exportContainer(d.container.id, out) { b -> _state.update { it.copy(log = "Exporting ${d.name}… ${Format.bytes(b)}\n") } } }
                }
            }.onSuccess { onDone("Exported ${d.name}") }.onFailure { onDone(it.message ?: "Export failed") }
            _state.update { it.copy(busy = false, log = null) }
        }
    }
    fun import(name: String, uri: Uri, context: android.content.Context, onDone: (String) -> Unit) {
        val client = app.engine.client ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, log = "Importing tarball as krs-distro/$name…\n") }
            runCatching {
                val repo = "krs-distro/$name"
                withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { client.importImage(it, repo, "latest", "Imported by KRS Dockers") } }
                app.distros.create(client, name, "$repo:latest").collect { l -> _state.update { it.copy(log = (it.log ?: "") + l + "\n") } }
            }.onSuccess { onDone("Imported $name") }.onFailure { e -> _state.update { it.copy(log = (it.log ?: "") + "✖ ${e.message}\n") }; onDone(e.message ?: "Import failed") }
            _state.update { it.copy(busy = false) }
            load(true)
        }
    }
    fun shellCommand() = app.distros.shellCommand()
}

@Composable
fun DistrosScreen(nav: NavHostController) {
    val vm = appViewModel { DistrosViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val toast = LocalToast.current
    val context = LocalContext.current
    val c = ShadTheme.colors
    var showInstall by remember { mutableStateOf(false) }
    var exportTarget by remember { mutableStateOf<DistroManager.Distro?>(null) }
    var importName by remember { mutableStateOf<String?>(null) }
    var unregisterTarget by remember { mutableStateOf<DistroManager.Distro?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-tar")) { uri -> val d = exportTarget; exportTarget = null; if (uri != null && d != null) vm.export(d, uri, context, toast) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> val n = importName; importName = null; if (uri != null && n != null) vm.import(n, uri, context, toast) }

    Column(Modifier.fillMaxSize()) {
        ShadTopBar("Distros", subtitle = "WSL-style Linux environments") {
            ShadIconButton(Lucide.RefreshCw, "Refresh", onClick = { vm.load() })
            ShadMenu(listOf(
                MenuAction("Install a distro", Lucide.Plus) { showInstall = true },
                MenuAction("Import from tarball", Lucide.Import) { importName = "" },
            ), icon = Lucide.EllipsisVertical)
        }
        ConnectionGate(onOpenEngines = { nav.navigate(Routes.ENGINES) }, onOpenVm = { nav.navigate(Routes.VM_ENGINE) }) {
            Column(Modifier.fillMaxSize()) {
                ErrorBanner(state.error, Modifier.padding(16.dp))
                state.log?.let { LogView(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).height(160.dp)) }
                if (state.loading && state.distros.isEmpty()) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { repeat(3) { ShadSkeleton(Modifier.fillMaxWidth().height(80.dp)) } }
                else if (state.distros.isEmpty()) EmptyState("No distros installed", "Install Ubuntu, Debian, Alpine, Fedora, Arch or Kali as a persistent environment with its own home directory, then open a terminal: just like `wsl --install`.", icon = Lucide.Terminal,
                    action = { ShadButton(onClick = { showInstall = true }, leadingIcon = Lucide.Plus) { ButtonText("Install a distro") } })
                else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { InfoBanner("Each distro is a container with a persistent /root volume. Export/Import mirror `wsl --export` / `wsl --import`.") }
                    items(state.distros, key = { it.container.id }) { d ->
                        ShadCard(contentPadding = PaddingValues(12.dp), onClick = { if (d.running) nav.navigate(Routes.terminal(d.container.id, d.name)) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(d.container.state); Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(d.name, style = MaterialTheme.typography.titleSmall, color = c.foreground)
                                    Text(d.base, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
                                    Spacer(Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        StateBadge(d.container.state)
                                        if (d.isDefault) ShadBadge("default", BadgeVariant.Info)
                                        ShadBadge(d.container.status, BadgeVariant.Outline)
                                    }
                                }
                                if (d.running) ShadButton(onClick = { nav.navigate(Routes.terminal(d.container.id, d.name)) }, size = ButtonSize.Sm, leadingIcon = Lucide.Terminal) { ButtonText("Open") }
                                else ShadButton(onClick = { vm.action(d, "start", toast) }, size = ButtonSize.Sm, variant = ButtonVariant.Outline, leadingIcon = Lucide.Play) { ButtonText("Start") }
                                ShadMenu(buildList {
                                    if (d.running) { add(MenuAction("Stop", Lucide.Square) { vm.action(d, "stop", toast) }); add(MenuAction("Restart", Lucide.RotateCw) { vm.action(d, "restart", toast) }) }
                                    add(MenuAction("Export (.tar)", Lucide.Download) { exportTarget = d; exportLauncher.launch("${d.name}.tar") })
                                    add(MenuAction("Container details", Lucide.Info) { nav.navigate(Routes.containerDetail(d.container.id)) })
                                    add(MenuAction("Unregister (delete)", Lucide.Trash2, destructive = true) { unregisterTarget = d })
                                }, icon = Lucide.EllipsisVertical)
                            }
                        }
                    }
                    item { BottomPadding() }
                }
            }
        }
    }

    if (showInstall) {
        var base by remember { mutableStateOf(vm.bases.first()) }
        var name by remember { mutableStateOf(base.id) }
        var custom by remember { mutableStateOf("") }
        var privileged by remember { mutableStateOf(false) }
        ShadDialog(onDismiss = { showInstall = false }, title = "Install a distro", description = "Pick a base image. The first start pulls the image.", confirmText = "Install", confirmEnabled = name.isNotBlank() && !state.busy, loading = state.busy,
            onConfirm = { showInstall = false; vm.install(name.trim(), custom.trim().ifBlank { base.image }, privileged, toast) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ShadSelect(base, vm.bases, { base = it; name = it.id }, label = "Base", text = { it.name })
                Text(base.description, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
                ShadInput(name, { name = it }, label = "Distro name", placeholder = "ubuntu")
                ShadInput(custom, { custom = it }, label = "Custom image (optional)", placeholder = "ghcr.io/org/image:tag", description = "Overrides the base image.")
                SwitchRow("Privileged", "Needed for nested docker, systemd, mounting, etc.", checked = privileged, onCheckedChange = { privileged = it })
            }
        }
    }
    importName?.let { n ->
        var v by remember { mutableStateOf(n) }
        ShadDialog(onDismiss = { importName = null }, title = "Import distro", description = "Pick a root filesystem tarball (e.g. one exported here or from `wsl --export`).", confirmText = "Choose file", confirmEnabled = v.isNotBlank(),
            onConfirm = { importName = v.trim(); importLauncher.launch(arrayOf("application/x-tar", "application/octet-stream", "*/*")) }) {
            ShadInput(v, { v = it }, label = "Distro name", placeholder = "ubuntu-imported")
        }
    }
    unregisterTarget?.let { d -> ShadDialog(onDismiss = { unregisterTarget = null }, title = "Unregister ${d.name}?", description = "The container and its home volume are deleted permanently.", confirmText = "Unregister", destructive = true, onConfirm = { unregisterTarget = null; vm.action(d, "unregister", toast) }) }
}
