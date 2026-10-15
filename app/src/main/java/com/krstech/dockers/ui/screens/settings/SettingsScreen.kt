package com.krstech.dockers.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.krstech.dockers.BuildConfig
import com.krstech.dockers.engine.AppSettings
import com.krstech.dockers.engine.ThemeMode
import com.krstech.dockers.ui.LocalAppContainer
import com.krstech.dockers.ui.components.*
import com.krstech.dockers.ui.theme.ShadTheme
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(nav: NavHostController) {
    val app = LocalAppContainer.current
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    fun update(block: (AppSettings) -> AppSettings) = scope.launch { app.settings.update(block) }
    val c = ShadTheme.colors

    Column(Modifier.fillMaxSize()) {
        ShadTopBar("Settings", onBack = { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ShadCard {
                CardHeader("Appearance")
                Spacer(Modifier.height(10.dp))
                ShadTabs(listOf("System", "Light", "Dark"), settings.theme.ordinal, { i -> update { it.copy(theme = ThemeMode.entries[i]) } })
            }
            ShadCard {
                CardHeader("Logs")
                ShadSelect(settings.logTail, listOf(100, 250, 500, 1000, 5000), { v -> update { it.copy(logTail = v) } }, label = "Initial tail lines", text = { "$it lines" })
                SwitchRow("Show timestamps", checked = settings.logTimestamps, onCheckedChange = { v -> update { it.copy(logTimestamps = v) } })
            }
            ShadCard {
                CardHeader("Terminal")
                ShadSelect(settings.terminalFontSize, listOf(10, 11, 12, 13, 14, 16, 18), { v -> update { it.copy(terminalFontSize = v) } }, label = "Font size", text = { "$it sp" })
            }
            ShadCard {
                CardHeader("Behaviour")
                SwitchRow("Confirm destructive actions", checked = settings.confirmDestructive, onCheckedChange = { v -> update { it.copy(confirmDestructive = v) } })
                SwitchRow("Show helper containers", "Compose helper containers created by this app.", checked = settings.showHelperContainers, onCheckedChange = { v -> update { it.copy(showHelperContainers = v) } })
            }
            ShadCard(onClick = { nav.navigate(com.krstech.dockers.ui.navigation.Routes.ABOUT) }) {
                CardHeader("About KRS Dockers", "Version ${BuildConfig.VERSION_NAME}")
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
fun AboutScreen(nav: NavHostController) {
    val c = ShadTheme.colors
    Column(Modifier.fillMaxSize()) {
        ShadTopBar("About", onBack = { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ShadCard {
                CardHeader("KRS Dockers", "v${BuildConfig.VERSION_NAME} · ${BuildConfig.APPLICATION_ID}")
                Spacer(Modifier.height(8.dp))
                Text("A Docker Desktop-style manager for Android by KRS Tech Solutions. Runs Docker on the phone in a built-in Linux VM (no root), natively on rooted devices, or connects to any Docker Engine over the network, including Docker Desktop's WSL2 backend on Windows.", style = MaterialTheme.typography.bodyMedium, color = c.mutedForeground)
            }
            ShadCard {
                CardHeader("Built with")
                KeyValueRow("UI", "Jetpack Compose · shadcn/ui-style components · Lucide icons")
                KeyValueRow("Engine", "Docker Engine API v1.43 over OkHttp (TCP, TLS, Unix sockets)")
                KeyValueRow("No-root engine", "QEMU (GPL-2.0, Termux build) running Alpine Linux + Docker Engine")
                KeyValueRow("Terminal", "Termux terminal-emulator/view (Apache-2.0) · Docker CLI (Apache-2.0)")
                KeyValueRow("Compose", "docker:cli helper container")
                KeyValueRow("Distros", "WSL-style containers with persistent home volumes")
            }
            ShadCard {
                CardHeader("Disclaimer")
                Text("Docker and the Docker logo are trademarks of Docker, Inc. This app is not affiliated with Docker, Inc. Running a daemon on-device requires root and a compatible kernel; you do so at your own risk.", style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
            }
        }
    }
}
