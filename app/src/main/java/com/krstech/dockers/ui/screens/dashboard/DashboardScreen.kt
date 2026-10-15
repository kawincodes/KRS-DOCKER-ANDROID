package com.krstech.dockers.ui.screens.dashboard

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import com.krstech.dockers.engine.model.ContainerSummary
import com.krstech.dockers.engine.model.SystemDf
import com.krstech.dockers.engine.profile.EngineProfile
import com.krstech.dockers.engine.profile.EngineType
import com.krstech.dockers.engine.vm.VmManager.Phase as P
import com.krstech.dockers.ui.LocalAppContainer
import com.krstech.dockers.ui.appViewModel
import com.krstech.dockers.ui.components.*
import com.krstech.dockers.ui.navigation.Routes
import com.krstech.dockers.ui.screens.BottomPadding
import com.krstech.dockers.ui.screens.ErrorBanner
import com.krstech.dockers.ui.screens.containers.RunContainerSheet
import com.krstech.dockers.ui.theme.ShadTheme
import com.krstech.dockers.util.Format
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardUiState(val df: SystemDf? = null, val containers: List<ContainerSummary> = emptyList(), val loading: Boolean = false, val error: String? = null)

@OptIn(FlowPreview::class)
class DashboardViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()
    init {
        viewModelScope.launch { app.engine.state.collect { if (it is EngineState.Connected) load() else _state.update { s -> s.copy(df = null, containers = emptyList()) } } }
        viewModelScope.launch { app.engine.events.debounce(800).collect { load(); app.engine.refreshInfo() } }
    }
    fun load() {
        val client = app.engine.client ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            runCatching { client.listContainers(all = true) to runCatching { client.df() }.getOrNull() }
                .onSuccess { (cs, df) -> _state.update { it.copy(containers = cs, df = df, loading = false, error = null) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }
}

@Composable
fun DashboardScreen(nav: NavHostController) {
    val app = LocalAppContainer.current
    val vm = appViewModel { DashboardViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val engine by app.engine.state.collectAsStateWithLifecycle()
    val profiles by app.profiles.profiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val localStatus by app.localEngine.status.collectAsStateWithLifecycle()
    val vmStatus by app.vm.status.collectAsStateWithLifecycle()
    val toast = LocalToast.current
    val c = ShadTheme.colors
    var showRun by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ShadTopBar("KRS Dockers", subtitle = when (val e = engine) { is EngineState.Connected -> "${e.profile.name} · Docker ${e.version.version}"; is EngineState.Connecting -> "Connecting…"; is EngineState.Error -> "Connection failed"; else -> "No engine connected" }) {
            ShadIconButton(Lucide.RefreshCw, "Refresh", onClick = { vm.load() })
            ShadIconButton(Lucide.Server, "Engines", onClick = { nav.navigate(Routes.ENGINES) })
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (val e = engine) {
                is EngineState.Connected -> {
                    ShadCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(when (e.profile.type) { EngineType.VM -> Lucide.Container; EngineType.LOCAL -> Lucide.Smartphone; EngineType.WSL -> Lucide.Monitor; EngineType.REMOTE -> Lucide.Server }, null, tint = c.success, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.profile.name, style = MaterialTheme.typography.titleMedium, color = c.foreground)
                                Text("${e.info.operatingSystem} · ${e.info.architecture} · ${e.info.ncpu} CPU · ${Format.bytes(e.info.memTotal)} RAM", style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
                                Text("Engine ${e.version.version} · API ${e.version.apiVersion} · ${e.info.driver} · cgroup v${e.info.cgroupVersion}", style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
                            }
                            ShadBadge("online", BadgeVariant.Success, dot = true)
                        }
                        e.info.warnings?.takeIf { it.isNotEmpty() }?.let { w -> Spacer(Modifier.height(8.dp)); Text(w.joinToString("\n"), style = MaterialTheme.typography.labelSmall, color = c.warning) }
                    }
                    if (e.profile.type == EngineType.VM && vmStatus.ports.isNotEmpty()) ShadCard(onClick = { nav.navigate(Routes.VM_ENGINE) }) {
                        CardHeader("Forwarded ports", vmStatus.ports.joinToString("  ") { "${it.guestPort}→:${it.hostPort}" })
                    }
                    ErrorBanner(state.error)
                    val running = state.containers.count { it.isRunning }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Running", running.toString(), Lucide.Play, Modifier.weight(1f), c.success)
                        StatTile("Stopped", (state.containers.size - running).toString(), Lucide.Square, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Images", (state.df?.images?.size ?: e.info.images).toString(), Lucide.Layers, Modifier.weight(1f), c.info)
                        StatTile("Volumes", (state.df?.volumes?.size ?: 0).toString(), Lucide.HardDrive, Modifier.weight(1f))
                    }
                    state.df?.let { df ->
                        val imgSize = df.images?.sumOf { it.size } ?: 0L
                        val volSize = df.volumes?.sumOf { it.usageData?.size?.takeIf { s -> s > 0 } ?: 0L } ?: 0L
                        val ctSize = df.layersSize
                        ShadCard {
                            CardHeader("Disk usage", "docker system df")
                            Spacer(Modifier.height(8.dp))
                            KeyValueRow("Images", Format.bytes(imgSize))
                            KeyValueRow("Volumes", Format.bytes(volSize))
                            KeyValueRow("Layers", Format.bytes(ctSize))
                        }
                    }
                    ShadCard {
                        CardHeader("Quick actions")
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ShadButton(onClick = { showRun = true }, leadingIcon = Lucide.Play, modifier = Modifier.weight(1f)) { ButtonText("Run") }
                            ShadButton(onClick = { nav.navigate(Routes.IMAGES) }, variant = ButtonVariant.Secondary, leadingIcon = Lucide.Download, modifier = Modifier.weight(1f)) { ButtonText("Pull") }
                            ShadButton(onClick = { nav.navigate(Routes.STACKS) }, variant = ButtonVariant.Outline, leadingIcon = Lucide.Boxes, modifier = Modifier.weight(1f)) { ButtonText("Compose") }
                        }
                    }
                    val recent = state.containers.sortedByDescending { it.created }.take(5)
                    if (recent.isNotEmpty()) ShadCard {
                        CardHeader("Recent containers", trailing = { ShadButton(onClick = { nav.navigate(Routes.CONTAINERS) }, variant = ButtonVariant.Link, size = ButtonSize.Sm) { ButtonText("See all") } })
                        Spacer(Modifier.height(6.dp))
                        recent.forEach { ct ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { nav.navigate(Routes.containerDetail(ct.id)) }, verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(ct.state); Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(ct.name, style = MaterialTheme.typography.titleSmall, color = c.foreground, maxLines = 1)
                                    Text(ct.image, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground, maxLines = 1)
                                }
                                Text(ct.status, style = MaterialTheme.typography.labelSmall, color = c.mutedForeground, maxLines = 1)
                            }
                        }
                    }
                }
                is EngineState.Connecting -> ShadCard { Row(verticalAlignment = Alignment.CenterVertically) { androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.foreground); Spacer(Modifier.width(12.dp)); Text("Connecting to ${e.profile.name}…", color = c.foreground) } }
                is EngineState.Error -> {
                    if (e.profile.type == EngineType.VM) VmHeroCard(vmStatus, onOpen = { nav.navigate(Routes.VM_ENGINE) }, onStart = { app.vm.start() })
                    ErrorBanner("${e.profile.name}: ${e.message}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ShadButton(onClick = { app.engine.connect(e.profile) }, variant = ButtonVariant.Outline, leadingIcon = Lucide.RefreshCw, modifier = Modifier.weight(1f)) { ButtonText("Retry") }
                        ShadButton(onClick = { nav.navigate(Routes.ENGINES) }, leadingIcon = Lucide.Server, modifier = Modifier.weight(1f)) { ButtonText("Engines") }
                    }
                }
                EngineState.Disconnected -> {
                    VmHeroCard(vmStatus, onOpen = { nav.navigate(Routes.VM_ENGINE) }, onStart = { app.vm.start() })
                    ShadCard {
                        CardHeader("Welcome to KRS Dockers", "Docker Desktop, in your pocket. Pick where your Docker engine runs.")
                        Spacer(Modifier.height(12.dp))
                        EngineChoice(Lucide.Container, "This phone (no root)", "Docker in a built-in Linux VM. Works on any 64-bit phone.") { nav.navigate(Routes.VM_ENGINE) }
                        EngineChoice(Lucide.Smartphone, "This phone (root)", if (localStatus.running) "dockerd is running" else "Native dockerd at full speed. Needs root + compatible kernel.") { nav.navigate(Routes.LOCAL_ENGINE) }
                        EngineChoice(Lucide.Monitor, "Windows PC with WSL2", "Connect to Docker Desktop's WSL2 backend over your LAN.") { nav.navigate(Routes.engineEdit(type = EngineType.WSL.name)) }
                        EngineChoice(Lucide.Server, "Remote host", "Any tcp:// Docker daemon, optionally with TLS certificates.") { nav.navigate(Routes.engineEdit(type = EngineType.REMOTE.name)) }
                    }
                    if (profiles.isNotEmpty()) ShadCard {
                        CardHeader("Saved engines")
                        Spacer(Modifier.height(6.dp))
                        profiles.forEach { p: EngineProfile ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text(p.name, style = MaterialTheme.typography.titleSmall, color = c.foreground); Text(p.url, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground) }
                                ShadButton(onClick = { when { p.type == EngineType.VM -> nav.navigate(Routes.VM_ENGINE); p.type == EngineType.LOCAL && !localStatus.running -> nav.navigate(Routes.LOCAL_ENGINE); else -> app.engine.connect(p) } }, size = ButtonSize.Sm, variant = ButtonVariant.Outline, leadingIcon = Lucide.Plug) { ButtonText("Connect") }
                            }
                        }
                    }
                }
            }
            BottomPadding()
        }
    }
    if (showRun) RunContainerSheet(onDismiss = { showRun = false }, onCreated = { showRun = false; toast("Container started"); vm.load() })
}

@Composable
private fun EngineChoice(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val c = ShadTheme.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.foreground, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall, color = c.foreground); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground) }
        Icon(Lucide.ChevronRight, null, tint = c.mutedForeground, modifier = Modifier.size(16.dp))
    }
}

/** Big "Start Docker" card for the built-in VM, shown while no engine is connected. */
@Composable
private fun VmHeroCard(status: com.krstech.dockers.engine.vm.VmManager.Status, onOpen: () -> Unit, onStart: () -> Unit) {
    val c = ShadTheme.colors
    if (status.phase == P.UNSUPPORTED) return
    ShadCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Container, null, tint = if (status.phase == P.RUNNING) c.success else c.foreground, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Run Docker on this phone", style = MaterialTheme.typography.titleMedium, color = c.foreground)
                Text(
                    when (status.phase) {
                        P.NOT_INSTALLED -> "No root needed. One-time ~190 MB download."
                        P.DOWNLOADING -> status.message ?: "Downloading…"
                        P.INSTALLING -> "Installing…"
                        P.STARTING -> "Starting Docker… this takes a minute or two."
                        P.RUNNING -> "Running · connecting…"
                        P.STOPPING -> "Stopping…"
                        P.ERROR -> status.message ?: "Stopped unexpectedly"
                        else -> "Ready to start"
                    },
                    style = MaterialTheme.typography.bodySmall, color = c.mutedForeground,
                )
            }
        }
        if (status.phase == P.DOWNLOADING) { Spacer(Modifier.height(10.dp)); ShadProgress(status.progress ?: 0f) }
        Spacer(Modifier.height(12.dp))
        when (status.phase) {
            P.STOPPED, P.ERROR -> ShadButton(onClick = onStart, leadingIcon = Lucide.Play, modifier = Modifier.fillMaxWidth()) { ButtonText("Start Docker") }
            P.NOT_INSTALLED -> ShadButton(onClick = onOpen, leadingIcon = Lucide.Download, modifier = Modifier.fillMaxWidth()) { ButtonText("Set up Docker") }
            else -> ShadButton(onClick = onOpen, variant = ButtonVariant.Outline, leadingIcon = Lucide.ScrollText, modifier = Modifier.fillMaxWidth()) { ButtonText("Details") }
        }
    }
}
