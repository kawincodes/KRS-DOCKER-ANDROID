package com.krstech.dockers.engine

import com.krstech.dockers.engine.model.DockerEvent
import com.krstech.dockers.engine.model.SystemInfo
import com.krstech.dockers.engine.model.VersionInfo
import com.krstech.dockers.engine.profile.EngineProfile
import com.krstech.dockers.engine.profile.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

sealed class EngineState {
    data object Disconnected : EngineState()
    data class Connecting(val profile: EngineProfile) : EngineState()
    data class Connected(val profile: EngineProfile, val client: DockerClient, val version: VersionInfo, val info: SystemInfo) : EngineState()
    data class Error(val profile: EngineProfile, val message: String) : EngineState()

    val profileOrNull: EngineProfile? get() = when (this) {
        is Connecting -> profile; is Connected -> profile; is Error -> profile; Disconnected -> null
    }
}

/** Owns the active Docker connection and a shared event stream. */
class EngineManager(
    private val profiles: ProfileRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow<EngineState>(EngineState.Disconnected)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<DockerEvent>(replay = 0, extraBufferCapacity = 256)
    val events: SharedFlow<DockerEvent> = _events.asSharedFlow()

    /** Last ~200 events kept for the Events screen. */
    private val _recentEvents = MutableStateFlow<List<DockerEvent>>(emptyList())
    val recentEvents: StateFlow<List<DockerEvent>> = _recentEvents.asStateFlow()

    private var eventJob: Job? = null
    private var connectJob: Job? = null

    val client: DockerClient? get() = (_state.value as? EngineState.Connected)?.client
    val activeProfile: EngineProfile? get() = _state.value.profileOrNull

    fun requireClient(): DockerClient = client ?: throw DockerException("Not connected to a Docker engine")

    fun connect(profile: EngineProfile) {
        connectJob?.cancel()
        connectJob = scope.launch {
            disconnectInternal()
            _state.value = EngineState.Connecting(profile)
            val client = DockerClient(profile.toHost(), profile.apiVersion)
            try {
                val (version, info) = withTimeout(20_000) {
                    val v = client.version()
                    val i = client.info()
                    v to i
                }
                // Negotiate: if daemon is older than our requested API, retry with its version.
                val negotiated = if (version.apiVersion.isNotBlank() && compareApi(version.apiVersion, profile.apiVersion) < 0) {
                    client.close(); DockerClient(profile.toHost(), version.apiVersion)
                } else client
                _state.value = EngineState.Connected(profile, negotiated, version, info)
                profiles.setActive(profile.id)
                startEventLoop(negotiated)
            } catch (t: Throwable) {
                client.close()
                _state.value = EngineState.Error(profile, friendly(t))
            }
        }
    }

    /** Reconnects to the saved active profile (or first auto-connect profile). */
    fun autoConnect() {
        scope.launch {
            val list = profiles.current()
            val active = profiles.activeId.first()
            val target = list.firstOrNull { it.id == active } ?: list.firstOrNull { it.autoConnect } ?: return@launch
            // The on-device engines connect themselves once they are up.
            if (target.type == com.krstech.dockers.engine.profile.EngineType.VM) return@launch
            if (target.type == com.krstech.dockers.engine.profile.EngineType.LOCAL && !target.autoConnect) return@launch
            connect(target)
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        scope.launch { disconnectInternal(); _state.value = EngineState.Disconnected }
    }

    suspend fun refreshInfo() {
        val s = _state.value as? EngineState.Connected ?: return
        runCatching { _state.value = s.copy(info = s.client.info()) }
    }

    private suspend fun disconnectInternal() {
        eventJob?.cancel(); eventJob = null
        (_state.value as? EngineState.Connected)?.client?.close()
    }

    private fun startEventLoop(client: DockerClient) {
        eventJob?.cancel()
        eventJob = scope.launch {
            while (isActive) {
                client.events()
                    .catch { delay(3000) }
                    .collect { ev ->
                        _events.tryEmit(ev)
                        _recentEvents.value = (listOf(ev) + _recentEvents.value).take(200)
                    }
                delay(2000)
            }
        }
    }

    private fun compareApi(a: String, b: String): Int {
        val (a1, a2) = a.split('.').map { it.toIntOrNull() ?: 0 }.let { (it.getOrElse(0) { 0 }) to (it.getOrElse(1) { 0 }) }
        val (b1, b2) = b.split('.').map { it.toIntOrNull() ?: 0 }.let { (it.getOrElse(0) { 0 }) to (it.getOrElse(1) { 0 }) }
        return if (a1 != b1) a1.compareTo(b1) else a2.compareTo(b2)
    }

    private fun friendly(t: Throwable): String = when (t) {
        is DockerException -> t.message ?: "Docker error"
        is kotlinx.coroutines.TimeoutCancellationException -> "Timed out connecting. Is the daemon reachable and listening on that port?"
        is javax.net.ssl.SSLException -> "TLS error: ${t.message}"
        is java.net.ConnectException -> "Connection refused. Check the host/port and that dockerd exposes a tcp:// endpoint."
        is java.net.UnknownHostException -> "Unknown host: ${t.message}"
        else -> t.message ?: t.javaClass.simpleName
    }
}
