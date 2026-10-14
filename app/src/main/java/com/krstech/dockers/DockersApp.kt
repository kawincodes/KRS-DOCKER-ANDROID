package com.krstech.dockers

import android.app.Application
import com.krstech.dockers.engine.EngineManager
import com.krstech.dockers.engine.SettingsRepository
import com.krstech.dockers.engine.compose.ComposeRunner
import com.krstech.dockers.engine.distro.DistroManager
import com.krstech.dockers.engine.local.LocalEngine
import com.krstech.dockers.engine.profile.ProfileRepository

/** Manual dependency container (kept deliberately simple: no DI framework). */
class AppContainer(app: Application) {
    val settings = SettingsRepository(app)
    val profiles = ProfileRepository(app)
    val engine = EngineManager(profiles)
    val localEngine = LocalEngine(app)
    val compose = ComposeRunner(app)
    val distros = DistroManager()
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
