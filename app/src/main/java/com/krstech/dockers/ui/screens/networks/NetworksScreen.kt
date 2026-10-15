package com.krstech.dockers.ui.screens.networks

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.composables.icons.lucide.*
import com.krstech.dockers.AppContainer
import com.krstech.dockers.engine.EngineState
import com.krstech.dockers.engine.model.Network
import com.krstech.dockers.ui.appViewModel
import com.krstech.dockers.ui.components.*
import com.krstech.dockers.ui.navigation.Routes
import com.krstech.dockers.ui.screens.BottomPadding
import com.krstech.dockers.ui.screens.ConnectionGate
import com.krstech.dockers.ui.screens.ErrorBanner
import com.krstech.dockers.ui.screens.JsonViewer
import com.krstech.dockers.ui.theme.MonoStyle
import com.krstech.dockers.ui.theme.ShadTheme
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

data class NetworksUiState(val networks: List<Network> = emptyList(), val loading: Boolean = true, val error: String? = null)

@OptIn(FlowPreview::class)
class NetworksViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(NetworksUiState())
    val state: StateFlow<NetworksUiState> = _state.asStateFlow()
    init {
        viewModelScope.launch { app.engine.state.collect { if (it is EngineState.Connected) load() } }
        viewModelScope.launch { app.engine.events.filter { it.type == "network" }.debounce(500).collect { load(true) } }
    }
    fun load(silent: Boolean = false) {
        val client = app.engine.client ?: return
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = true) }
            runCatching { client.listNetworks().map { n -> runCatching { client.inspectNetwork(n.id) }.getOrDefault(n) } }
                .onSuccess { l -> _state.update { it.copy(networks = l.sortedBy { n -> n.name }, loading = false, error = null) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }
    fun create(name: String, driver: String, internal: Boolean, subnet: String, gateway: String, onDone: (String) -> Unit) {
        val client = app.engine.client ?: return
        viewModelScope.launch { runCatching { client.createNetwork(name, driver, internal, true, subnet.ifBlank { null }, gateway.ifBlank { null }) }.onSuccess { onDone("Created $name"); load(true) }.onFailure { onDone(it.message ?: "Failed") } }
    }
    fun remove(id: String, onDone: (String) -> Unit) {
        val client = app.engine.client ?: return
        viewModelScope.launch { runCatching { client.removeNetwork(id) }.onSuccess { onDone("Removed"); load(true) }.onFailure { onDone(it.message ?: "Failed") } }
    }
    fun prune(onDone: (String) -> Unit) {
        val client = app.engine.client ?: return
        viewModelScope.launch { runCatching { client.pruneNetworks() }.onSuccess { onDone("Removed ${it.networksDeleted?.size ?: 0} networks"); load(true) }.onFailure { onDone(it.message ?: "Failed") } }
    }
    suspend fun inspectRaw(id: String): JsonElement? = app.engine.client?.let { runCatching { it.inspectNetworkRaw(id) }.getOrNull() }
}

@Composable
fun NetworksScreen(nav: NavHostController) {
    val vm = appViewModel { NetworksViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val toast = LocalToast.current
    val c = ShadTheme.colors
    var showCreate by remember { mutableStateOf(false) }
    var confirmPrune by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<Network?>(null) }
    var inspectTarget by remember { mutableStateOf<Network?>(null) }

    Column(Modifier.fillMaxSize()) {
        ShadTopBar("Networks", subtitle = "${state.networks.size} networks", onBack = { nav.popBackStack() }) {
            ShadIconButton(Lucide.Plus, "Create", onClick = { showCreate = true })
            ShadMenu(listOf(MenuAction("Refresh", Lucide.RefreshCw) { vm.load() }, MenuAction("Prune unused", Lucide.Trash2, destructive = true) { confirmPrune = true }), icon = Lucide.EllipsisVertical)
        }
        ConnectionGate(onOpenEngines = { nav.navigate(Routes.ENGINES) }, onOpenVm = { nav.navigate(Routes.VM_ENGINE) }) {
            Column(Modifier.fillMaxSize()) {
                ErrorBanner(state.error, Modifier.padding(16.dp))
                if (state.loading && state.networks.isEmpty()) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { repeat(4) { ShadSkeleton(Modifier.fillMaxWidth().height(64.dp)) } }
                else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.networks, key = { it.id }) { n ->
                        val subnet = n.ipam?.config?.firstOrNull()?.subnet
                        ShadCard(contentPadding = PaddingValues(12.dp), onClick = { inspectTarget = n }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(n.name, style = MaterialTheme.typography.titleSmall, color = c.foreground)
                                    Text(listOfNotNull(subnet, n.ipam?.config?.firstOrNull()?.gateway?.let { "gw $it" }).joinToString(" · ").ifBlank { n.shortId }, style = MonoStyle, color = c.mutedForeground)
                                    Spacer(Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        ShadBadge(n.driver, BadgeVariant.Secondary)
                                        ShadBadge(n.scope, BadgeVariant.Outline)
                                        if (n.internal) ShadBadge("internal", BadgeVariant.Warning)
                                        val count = n.containers?.size ?: 0
                                        if (count > 0) ShadBadge("$count attached", BadgeVariant.Success)
                                    }
                                }
                                ShadMenu(listOf(MenuAction("Inspect", Lucide.FileJson) { inspectTarget = n }, MenuAction("Remove", Lucide.Trash2, destructive = true, enabled = !n.isBuiltin) { removeTarget = n }), icon = Lucide.EllipsisVertical)
                            }
                        }
                    }
                    item { BottomPadding() }
                }
            }
        }
    }
    if (showCreate) {
        var name by remember { mutableStateOf("") }; var driver by remember { mutableStateOf("bridge") }; var internal by remember { mutableStateOf(false) }
        var subnet by remember { mutableStateOf("") }; var gateway by remember { mutableStateOf("") }
        ShadDialog(onDismiss = { showCreate = false }, title = "Create network", confirmText = "Create", confirmEnabled = name.isNotBlank(), onConfirm = { showCreate = false; vm.create(name.trim(), driver, internal, subnet.trim(), gateway.trim(), toast) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ShadInput(name, { name = it }, label = "Name", placeholder = "backend")
                ShadSelect(driver, listOf("bridge", "overlay", "macvlan", "ipvlan", "host", "none"), { driver = it }, label = "Driver")
                ShadInput(subnet, { subnet = it }, label = "Subnet (optional)", placeholder = "172.28.0.0/16")
                ShadInput(gateway, { gateway = it }, label = "Gateway (optional)", placeholder = "172.28.0.1")
                SwitchRow("Internal", "No external connectivity.", checked = internal, onCheckedChange = { internal = it })
            }
        }
    }
    if (confirmPrune) ShadDialog(onDismiss = { confirmPrune = false }, title = "Prune unused networks?", confirmText = "Prune", destructive = true, onConfirm = { confirmPrune = false; vm.prune(toast) })
    removeTarget?.let { n -> ShadDialog(onDismiss = { removeTarget = null }, title = "Remove ${n.name}?", confirmText = "Remove", destructive = true, onConfirm = { removeTarget = null; vm.remove(n.id, toast) }) }
    inspectTarget?.let { n ->
        var raw by remember { mutableStateOf<JsonElement?>(null) }
        androidx.compose.runtime.LaunchedEffect(n.id) { raw = vm.inspectRaw(n.id) }
        ShadSheet(onDismiss = { inspectTarget = null }, title = n.name, description = n.shortId) { JsonViewer(raw, Modifier.height(440.dp)) }
    }
}
