package com.krstech.dockers.ui.screens.engines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
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
import com.krstech.dockers.engine.AppSettings
import com.krstech.dockers.engine.EngineState
import com.krstech.dockers.engine.local.LocalEngine
import com.krstech.dockers.engine.profile.ProfileRepository
import com.krstech.dockers.ui.appViewModel
import com.krstech.dockers.ui.components.*
import com.krstech.dockers.ui.screens.ErrorBanner
import com.krstech.dockers.ui.screens.InfoBanner
import com.krstech.dockers.ui.screens.LogView
import com.krstech.dockers.ui.theme.ShadTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LocalEngineViewModel(private val app: AppContainer) : ViewModel() {
    val status: StateFlow<LocalEngine.Status> = app.localEngine.status
    private val _versions = MutableStateFlow<List<String>>(emptyList())
    val versions: StateFlow<List<String>> = _versions.asStateFlow()
    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init { refresh() }

    fun refresh() { viewModelScope.launch { app.localEngine.refresh(); _log.value = app.localEngine.readLog() } }
    fun runChecks() { viewModelScope.launch { runCatching { app.localEngine.runChecks() }.onFailure { _error.value = it.message } } }
    fun loadVersions() { viewModelScope.launch { runCatching { app.localEngine.availableVersions() }.onSuccess { _versions.value = it }.onFailure { _error.value = "Could not list versions: ${it.message}" } } }
    fun install(version: String) { viewModelScope.launch { _error.value = null; runCatching { app.localEngine.install(version) }.onFailure { _error.value = it.message }; refresh() } }
    fun uninstall() { app.localEngine.uninstallBinaries(); refresh() }
    fun start() {
        viewModelScope.launch {
            _error.value = null
            val s = app.settings.settings.first()
            runCatching { app.localEngine.start(LocalEngine.Options(s.localPort, s.localStorageDriver, s.localIptables, s.localExtraArgs)) }
                .onSuccess {
                    if (app.localEngine.status.value.running) {
                        val profile = app.profiles.current().firstOrNull { it.id == ProfileRepository.LOCAL_PROFILE_ID }?.copy(url = "tcp://127.0.0.1:${s.localPort}")
                        if (profile != null) { app.profiles.upsert(profile); app.engine.connect(profile) }
                    }
                }
                .onFailure { _error.value = it.message }
            refresh()
        }
    }
    fun stop() { viewModelScope.launch { if ((app.engine.state.value as? EngineState.Connected)?.profile?.id == ProfileRepository.LOCAL_PROFILE_ID) app.engine.disconnect(); runCatching { app.localEngine.stop() }.onFailure { _error.value = it.message }; refresh() } }
    fun clearLog() { app.localEngine.clearLog(); _log.value = "" }
    fun updateSettings(block: (AppSettings) -> AppSettings) { viewModelScope.launch { app.settings.update(block) } }
}

@Composable
fun LocalEngineScreen(nav: NavHostController) {
    val vm = appViewModel { LocalEngineViewModel(it) }
    val app = com.krstech.dockers.ui.LocalAppContainer.current
    val status by vm.status.collectAsStateWithLifecycle()
    val versions by vm.versions.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val c = ShadTheme.colors
    var showInstall by remember { mutableStateOf(false) }
    var selectedVersion by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.runChecks() }

    Column(Modifier.fillMaxSize()) {
        ShadTopBar("Local engine", subtitle = "dockerd on this device (root)", onBack = { nav.popBackStack() }) {
            ShadIconButton(Lucide.RefreshCw, "Refresh", onClick = { vm.refresh(); vm.runChecks() })
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoBanner("Running Docker natively on Android needs a rooted device (Magisk/KernelSU) and a kernel built with namespaces, cgroups, overlayfs and netfilter. Most stock kernels lack some of these; custom kernels (e.g. with docker patches) work best. If this device isn't rooted, use a Remote or WSL2 engine instead.")
            ErrorBanner(error)
            ShadCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (status.running) Lucide.CircleCheck else Lucide.CircleStop, null, tint = if (status.running) c.success else c.mutedForeground, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (status.running) "Engine running" else "Engine stopped", style = MaterialTheme.typography.titleMedium, color = c.foreground)
                        Text(listOfNotNull(status.installedVersion?.let { "docker $it" }, status.pid?.let { "pid $it" }, "tcp://127.0.0.1:${settings.localPort}").joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
                    }
                }
                status.progress?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = c.info) }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status.running) ShadButton(onClick = { vm.stop() }, variant = ButtonVariant.Destructive, loading = status.busy, leadingIcon = Lucide.Square, modifier = Modifier.weight(1f)) { ButtonText("Stop") }
                    else ShadButton(onClick = { vm.start() }, enabled = status.binariesInstalled && status.rootAvailable == true, loading = status.busy, leadingIcon = Lucide.Play, modifier = Modifier.weight(1f)) { ButtonText("Start engine") }
                    ShadButton(onClick = { showLog = !showLog }, variant = ButtonVariant.Outline, leadingIcon = Lucide.ScrollText, modifier = Modifier.weight(1f)) { ButtonText("Daemon log") }
                }
                if (showLog) { Spacer(Modifier.height(10.dp)); LogView(log, Modifier.height(260.dp)); Spacer(Modifier.height(6.dp)); Row { ShadButton(onClick = { vm.refresh() }, variant = ButtonVariant.Ghost, size = ButtonSize.Sm) { ButtonText("Reload") }; ShadButton(onClick = { vm.clearLog() }, variant = ButtonVariant.Ghost, size = ButtonSize.Sm) { ButtonText("Clear") } } }
            }

            ShadCard {
                CardHeader("Docker binaries", if (status.binariesInstalled) "Installed: docker ${status.installedVersion} (${app.localEngine.arch})" else "Static build from download.docker.com (${app.localEngine.arch})")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShadButton(onClick = { showInstall = true; vm.loadVersions() }, variant = if (status.binariesInstalled) ButtonVariant.Outline else ButtonVariant.Default, enabled = !status.busy, leadingIcon = Lucide.Download, modifier = Modifier.weight(1f)) { ButtonText(if (status.binariesInstalled) "Change version" else "Install") }
                    if (status.binariesInstalled) ShadButton(onClick = { vm.uninstall() }, variant = ButtonVariant.Ghost, enabled = !status.busy && !status.running, leadingIcon = Lucide.Trash2) { ButtonText("Remove") }
                }
            }

            ShadCard {
                CardHeader("Requirements", "Checked with root")
                Spacer(Modifier.height(6.dp))
                if (status.checks.isEmpty()) ShadSkeleton(Modifier.fillMaxWidth().height(60.dp))
                status.checks.forEach { ch ->
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        Icon(if (ch.ok) Lucide.CircleCheck else if (ch.required) Lucide.CircleX else Lucide.TriangleAlert, null, tint = if (ch.ok) c.success else if (ch.required) c.destructive else c.warning, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                        Spacer(Modifier.width(10.dp))
                        Column { Text(ch.name, style = MaterialTheme.typography.titleSmall, color = c.foreground); Text(ch.detail, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground) }
                    }
                }
            }

            ShadCard {
                CardHeader("Daemon options", "Applied on next start")
                Spacer(Modifier.height(8.dp))
                var port by remember(settings.localPort) { mutableStateOf(settings.localPort.toString()) }
                ShadInput(port, { port = it; it.toIntOrNull()?.let { p -> vm.updateSettings { s -> s.copy(localPort = p) } } }, label = "TCP port (127.0.0.1)", keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                Spacer(Modifier.height(10.dp))
                ShadSelect(settings.localStorageDriver, listOf("overlay2", "vfs", "fuse-overlayfs"), { d -> vm.updateSettings { it.copy(localStorageDriver = d) } }, label = "Storage driver")
                Spacer(Modifier.height(4.dp))
                SwitchRow("iptables NAT", "Disable if the kernel lacks netfilter (containers lose outbound NAT).", checked = settings.localIptables, onCheckedChange = { v -> vm.updateSettings { it.copy(localIptables = v) } })
                var extra by remember(settings.localExtraArgs) { mutableStateOf(settings.localExtraArgs) }
                ShadInput(extra, { extra = it; vm.updateSettings { s -> s.copy(localExtraArgs = it) } }, label = "Extra dockerd flags", placeholder = "--debug --dns 1.1.1.1", monospace = true)
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (showInstall) {
        ShadDialog(onDismiss = { showInstall = false }, title = "Install Docker static binaries", description = "Downloads docker-<version>.tgz for ${app.localEngine.arch} (~70 MB) into the app's private storage.", confirmText = "Install", confirmEnabled = selectedVersion != null,
            onConfirm = { showInstall = false; selectedVersion?.let { vm.install(it) } }) {
            if (versions.isEmpty()) Text("Fetching versions…", style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
            else { if (selectedVersion == null) selectedVersion = versions.first(); ShadSelect(selectedVersion ?: versions.first(), versions.take(25), { selectedVersion = it }, label = "Version") }
        }
    }
}
