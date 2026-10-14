package com.krstech.dockers.ui.screens.containers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krstech.dockers.AppContainer
import com.krstech.dockers.engine.EngineState
import com.krstech.dockers.engine.compose.ComposeRunner
import com.krstech.dockers.engine.model.ContainerSummary
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ContainersUiState(
    val containers: List<ContainerSummary> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val query: String = "",
    val filter: String = "all",
    val busyIds: Set<String> = emptySet(),
    val showHelpers: Boolean = false,
) {
    val filtered: List<ContainerSummary>
        get() = containers
            .filter { showHelpers || it.labels[ComposeRunner.HELPER_LABEL] == null }
            .filter { when (filter) { "running" -> it.isRunning; "stopped" -> !it.isRunning; else -> true } }
            .filter { query.isBlank() || it.name.contains(query, true) || it.image.contains(query, true) || it.id.startsWith(query) }
            .sortedWith(compareByDescending<ContainerSummary> { it.isRunning }.thenBy { it.name.lowercase() })
    val runningCount: Int get() = containers.count { it.isRunning }
    val stoppedCount: Int get() = containers.count { !it.isRunning }
}

@OptIn(FlowPreview::class)
class ContainersViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ContainersUiState())
    val state: StateFlow<ContainersUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { app.engine.state.collect { if (it is EngineState.Connected) load() else _state.update { s -> s.copy(containers = emptyList(), loading = it is EngineState.Connecting) } } }
        viewModelScope.launch { app.engine.events.filter { it.type == "container" }.debounce(400).collect { load(silent = true) } }
        viewModelScope.launch { app.settings.settings.collect { s -> _state.update { it.copy(showHelpers = s.showHelperContainers) } } }
    }

    fun load(silent: Boolean = false) {
        val client = app.engine.client ?: return
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = true) }
            runCatching { client.listContainers(all = true) }
                .onSuccess { list -> _state.update { it.copy(containers = list, loading = false, error = null) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun setFilter(f: String) = _state.update { it.copy(filter = f) }

    fun action(id: String, action: String, onResult: (String) -> Unit) {
        val client = app.engine.client ?: return
        viewModelScope.launch {
            _state.update { it.copy(busyIds = it.busyIds + id) }
            val r = runCatching {
                when (action) {
                    "start" -> client.startContainer(id)
                    "stop" -> client.stopContainer(id)
                    "restart" -> client.restartContainer(id)
                    "kill" -> client.killContainer(id)
                    "pause" -> client.pauseContainer(id)
                    "unpause" -> client.unpauseContainer(id)
                    "remove" -> client.removeContainer(id, force = true)
                    "remove_volumes" -> client.removeContainer(id, force = true, volumes = true)
                }
            }
            _state.update { it.copy(busyIds = it.busyIds - id) }
            r.onSuccess { onResult("Container ${action.replace('_', ' ')} ✓"); load(silent = true) }
                .onFailure { onResult(it.message ?: "Failed") }
        }
    }

    fun pruneStopped(onResult: (String) -> Unit) {
        val client = app.engine.client ?: return
        viewModelScope.launch {
            runCatching { client.pruneContainers() }
                .onSuccess { onResult("Removed ${it.deletedCount} stopped containers"); load(silent = true) }
                .onFailure { onResult(it.message ?: "Prune failed") }
        }
    }
}
