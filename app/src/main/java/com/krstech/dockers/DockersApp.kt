package com.krstech.dockers

import android.app.Application
import com.krstech.dockers.engine.EngineManager
import com.krstech.dockers.engine.SettingsRepository
import com.krstech.dockers.engine.compose.ComposeRunner
import com.krstech.dockers.engine.distro.DistroManager
import com.krstech.dockers.engine.local.LocalEngine
import com.krstech.dockers.engine.profile.ProfileRepository
import com.krstech.dockers.engine.vm.VmManager
import com.krstech.dockers.ui.screens.terminal.TerminalSessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Manual dependency container (kept deliberately simple: no DI framework). */
class AppContainer(app: Application) {
    val appContext: android.content.Context = app
    val settings = SettingsRepository(app)
    val profiles = ProfileRepository(app)
    val engine = EngineManager(profiles)
    val localEngine = LocalEngine(app)
    val compose = ComposeRunner(app)
    val distros = DistroManager()
    val vm = VmManager(app, settings)
    val terminals = TerminalSessions(app, this)
    val adb = com.krstech.dockers.engine.adb.AdbManager(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // A shell service started in an earlier session keeps running until reboot; pick it up.
        adb.refresh()
        // When the VM's Docker API answers, connect to it (Docker Desktop-style "it just works").
        vm.onReady = {
            scope.launch {
                val port = settings.settings.first().vmApiPort
                val profile = profiles.current().firstOrNull { it.id == ProfileRepository.VM_PROFILE_ID }?.copy(url = "tcp://127.0.0.1:$port")
                    ?: profiles.vmProfile(port)
                profiles.upsert(profile)
                engine.connect(profile)
            }
        }
        vm.onStopped = {
            if (engine.activeProfile?.id == ProfileRepository.VM_PROFILE_ID) engine.disconnect()
        }
    }
}

class DockersApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.engine.autoConnect()
    }
}
