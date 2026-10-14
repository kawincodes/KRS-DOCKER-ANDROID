package com.krstech.dockers.ui.screens.containers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.krstech.dockers.engine.model.ContainerInspect
import com.krstech.dockers.engine.model.ContainerStats
import com.krstech.dockers.engine.model.ContainerTop
import com.krstech.dockers.ui.appViewModel
import com.krstech.dockers.ui.components.*
import com.krstech.dockers.ui.navigation.Routes
import com.krstech.dockers.ui.screens.ErrorBanner
import com.krstech.dockers.ui.screens.JsonViewer
import com.krstech.dockers.ui.screens.LogView
import com.krstech.dockers.ui.theme.MonoStyle
import com.krstech.dockers.ui.theme.ShadTheme
import com.krstech.dockers.util.Format
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

data class ContainerDetailState(
    val inspect: ContainerInspect? = null,
    val raw: JsonElement? = null,
    val logs: String = "",
    val stats: ContainerStats? = null,
    val top: ContainerTop? = null,
    val error: String? = null,
    val busy: Boolean = false,
)

class ContainerDetailViewModel(private val app: AppContainer, private val id: String) : ViewModel() {
    private val _state = MutableStateFlow(ContainerDetailState())
    val state: StateFlow<ContainerDetailState> = _state.asStateFlow()
    private var logJob: Job? = null
    private var statsJob: Job? = null
    private val logBuffer = StringBuilder()

    init { refresh(); viewModelScope.launch { app.engine.events.collect { if (it.actor.id == id || it.actor.id.startsWith(id) || id.startsWith(it.actor.id)) refresh() } } }

    fun refresh() {
        val client = app.engine.client ?: return
        viewModelScope.launch {
            runCatching { client.inspectContainer(id) to client.inspectContainerRaw(id) }
                .onSuccess { (i, r) -> _state.update { it.copy(inspect = i, raw = r, error = null) } }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun startLogs(tail: Int, timestamps: Boolean) {
        if (logJob?.isActive == true) return
        val client = app.engine.client ?: return
        val tty = _state.value.inspect?.config?.tty ?: false
        logBuffer.clear()
        logJob = viewModelScope.launch {
            client.logs(id, tty = tty, follow = true, tail = tail, timestamps = timestamps)
                .catch { e -> _state.update { it.copy(error = e.message) } }
                .collect { chunk ->
                    logBuffer.append(chunk)
                    if (logBuffer.length > 400_000) logBuffer.delete(0, logBuffer.length - 300_000)
                    _state.update { it.copy(logs = logBuffer.toString()) }
                }
        }
    }

    fun stopLogs() { logJob?.cancel(); logJob = null }

    fun startStats() {
        if (statsJob?.isActive == true) return
        val client = app.engine.client ?: return
        statsJob = viewModelScope.launch {
            client.stats(id).catch { }.collect { s -> _state.update { it.copy(stats = s) } }
        }
        viewModelScope.launch { runCatching { client.top(id) }.onSuccess { t -> _state.update { it.copy(top = t) } } }
    }

    fun stopStats() { statsJob?.cancel(); statsJob = null }

    fun action(action: String, onResult: (String) -> Unit, onRemoved: () -> Unit = {}) {
        val client = app.engine.client ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runCatching {
                when (action) {
                    "start" -> client.startContainer(id); "stop" -> client.stopContainer(id); "restart" -> client.restartContainer(id)
                    "kill" -> client.killContainer(id); "pause" -> client.pauseContainer(id); "unpause" -> client.unpauseContainer(id)
                    "remove" -> client.removeContainer(id, force = true)
                }
            }.onSuccess { onResult("Done: $action"); if (action == "remove") onRemoved() else refresh() }
                .onFailure { onResult(it.message ?: "Failed") }
            _state.update { it.copy(busy = false) }
        }
    }

    fun rename(name: String, onResult: (String) -> Unit) {
        val client = app.engine.client ?: return
        viewModelScope.launch { runCatching { client.renameContainer(id, name) }.onSuccess { onResult("Renamed"); refresh() }.onFailure { onResult(it.message ?: "Rename failed") } }
    }

    override fun onCleared() { stopLogs(); stopStats() }
}

@Composable
fun ContainerDetailScreen(nav: NavHostController, id: String) {
    val vm = appViewModel(key = "container-$id") { ContainerDetailViewModel(it, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by com.krstech.dockers.ui.LocalAppContainer.current.settings.settings.collectAsStateWithLifecycle(initialValue = com.krstech.dockers.engine.AppSettings())
    val toast = LocalToast.current
    var tab by remember { mutableStateOf(0) }
    var confirmRemove by remember { mutableStateOf(false) }
    var renameTo by remember { mutableStateOf<String?>(null) }
    val c = ShadTheme.colors
    val ins = state.inspect

    androidx.compose.runtime.LaunchedEffect(tab, ins?.id) {
        if (ins == null) return@LaunchedEffect
        if (tab == 0) vm.startLogs(settings.logTail, settings.logTimestamps) else vm.stopLogs()
        if (tab == 2) vm.startStats() else vm.stopStats()
    }

    Column(Modifier.fillMaxSize()) {
        ShadTopBar(ins?.displayName ?: id.take(12), subtitle = ins?.config?.image, onBack = { nav.popBackStack() }) {
            if (ins != null) {
                if (ins.state.running) ShadIconButton(Lucide.Terminal, "Terminal", onClick = { nav.navigate(Routes.terminal(ins.id, ins.displayName)) })
                ShadMenu(buildList {
                    if (ins.state.running) {
                        add(MenuAction("Stop", Lucide.Square) { vm.action("stop", toast) })
                        add(MenuAction("Restart", Lucide.RotateCw) { vm.action("restart", toast) })
                        add(MenuAction(if (ins.state.paused) "Unpause" else "Pause", Lucide.Pause) { vm.action(if (ins.state.paused) "unpause" else "pause", toast) })
                        add(MenuAction("Kill", Lucide.Zap, destructive = true) { vm.action("kill", toast) })
                    } else add(MenuAction("Start", Lucide.Play) { vm.action("start", toast) })
                    add(MenuAction("Rename", Lucide.Pencil) { renameTo = ins.displayName })
                    add(MenuAction("Remove", Lucide.Trash2, destructive = true) { confirmRemove = true })
                }, icon = Lucide.EllipsisVertical)
            }
        }
        ErrorBanner(state.error, Modifier.padding(16.dp))
        if (ins != null) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StateBadge(ins.state.status)
                Text(ins.shortIdText(), style = MonoStyle, color = c.mutedForeground)
                ins.networkSettings.ipAddress?.takeIf { it.isNotBlank() }?.let { ShadBadge(it, BadgeVariant.Outline) }
                ins.hostConfig.restartPolicy?.name?.takeIf { it != "no" && it.isNotBlank() }?.let { ShadBadge("restart: $it", BadgeVariant.Secondary) }
            }
            ShadTabs(listOf("Logs", "Inspect", "Stats", "Details"), tab, { tab = it }, Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(10.dp))
            when (tab) {
                0 -> LogView(state.logs, Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp))
                1 -> JsonViewer(state.raw, Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp))
                2 -> StatsTab(state.stats, state.top, ins.state.running)
                3 -> DetailsTab(ins)
            }
        }
    }

    if (confirmRemove) ShadDialog(onDismiss = { confirmRemove = false }, title = "Remove container?", description = "This force-removes ${ins?.displayName}.", confirmText = "Remove", destructive = true, onConfirm = { confirmRemove = false; vm.action("remove", toast) { nav.popBackStack() } })
    renameTo?.let { current ->
        var v by remember { mutableStateOf(current) }
        ShadDialog(onDismiss = { renameTo = null }, title = "Rename container", confirmText = "Rename", confirmEnabled = v.isNotBlank(), onConfirm = { renameTo = null; vm.rename(v.trim(), toast) }) {
            ShadInput(v, { v = it }, label = "New name")
        }
    }
}

private fun ContainerInspect.shortIdText() = id.take(12)

@Composable
private fun StatsTab(stats: ContainerStats?, top: ContainerTop?, running: Boolean) {
    val c = ShadTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!running) { Text("Container is not running; live stats unavailable.", color = c.mutedForeground, style = MaterialTheme.typography.bodyMedium); return }
        if (stats == null) { repeat(3) { ShadSkeleton(Modifier.fillMaxWidth().height(80.dp)) }; return }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("CPU", Format.percent(stats.cpuPercent), Lucide.Cpu, Modifier.weight(1f), c.info)
            StatTile("Memory", Format.bytes(stats.memUsage), Lucide.MemoryStick, Modifier.weight(1f), c.success)
        }
        ShadCard {
            CardHeader("Memory", "${Format.bytes(stats.memUsage)} of ${Format.bytes(stats.memLimit)} (${Format.percent(stats.memPercent)})")
            Spacer(Modifier.height(10.dp))
            ShadProgress((stats.memPercent / 100).toFloat(), color = c.success)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Net I/O", "${Format.bytes(stats.netRx)} / ${Format.bytes(stats.netTx)}", Lucide.Network, Modifier.weight(1f))
            StatTile("Block I/O", "${Format.bytes(stats.blockRead)} / ${Format.bytes(stats.blockWrite)}", Lucide.HardDrive, Modifier.weight(1f))
        }
        if (top != null && top.processes.isNotEmpty()) {
            ShadCard {
                CardHeader("Processes", "${top.processes.size} running")
                Spacer(Modifier.height(8.dp))
                Text(top.titles.joinToString("  "), style = MonoStyle, color = c.mutedForeground)
                top.processes.forEach { Text(it.joinToString("  "), style = MonoStyle, color = c.foreground, maxLines = 1) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DetailsTab(ins: ContainerInspect) {
    val c = ShadTheme.colors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            ShadCard {
                CardHeader("General")
                KeyValueRow("ID", ins.id.take(24), mono = true)
                KeyValueRow("Image", ins.config.image ?: "–")
                KeyValueRow("Created", Format.relativeIso(ins.created))
                KeyValueRow("Started", Format.relativeIso(ins.state.startedAt))
                KeyValueRow("Exit code", ins.state.exitCode.toString())
                KeyValueRow("Restarts", ins.restartCount.toString())
                KeyValueRow("Platform", ins.platform ?: "–")
                KeyValueRow("Command", (listOfNotNull(ins.path) + ins.args).joinToString(" "), mono = true)
                KeyValueRow("Working dir", ins.config.workingDir.orEmpty().ifBlank { "/" }, mono = true)
                KeyValueRow("Privileged", ins.hostConfig.privileged.toString())
                KeyValueRow("Network mode", ins.hostConfig.networkMode ?: "–")
            }
        }
        item {
            ShadCard {
                CardHeader("Networks")
                val nets = ins.networkSettings.networks.orEmpty()
                if (nets.isEmpty()) Text("None", color = c.mutedForeground, style = MaterialTheme.typography.bodySmall)
                nets.forEach { (name, ep) -> KeyValueRow(name, listOfNotNull(ep.ipAddress, ep.gateway?.let { "gw $it" }).joinToString(" · ").ifBlank { "–" }, mono = true) }
            }
        }
        item {
            ShadCard {
                CardHeader("Ports")
                val ports = ins.networkSettings.ports?.entries.orEmpty()
                if (ports.isEmpty()) Text("No published ports", color = c.mutedForeground, style = MaterialTheme.typography.bodySmall)
                ports.forEach { (k, v) -> KeyValueRow(k, v.toString().take(80), mono = true) }
            }
        }
        item {
            ShadCard {
                CardHeader("Mounts")
                if (ins.mounts.isEmpty()) Text("None", color = c.mutedForeground, style = MaterialTheme.typography.bodySmall)
                ins.mounts.forEach { m -> KeyValueRow(m.destination ?: "?", "${m.type ?: ""} ${m.name ?: m.source ?: ""} ${if (m.rw) "rw" else "ro"}".trim(), mono = true) }
            }
        }
        item {
            ShadCard {
                CardHeader("Environment")
                val env = ins.config.env.orEmpty()
                if (env.isEmpty()) Text("None", color = c.mutedForeground, style = MaterialTheme.typography.bodySmall)
                env.forEach { Text(it, style = MonoStyle, color = c.foreground) }
            }
        }
        item {
            ShadCard {
                CardHeader("Labels")
                val labels = ins.config.labels.orEmpty()
                if (labels.isEmpty()) Text("None", color = c.mutedForeground, style = MaterialTheme.typography.bodySmall)
                labels.forEach { (k, v) -> KeyValueRow(k, v, mono = true) }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

