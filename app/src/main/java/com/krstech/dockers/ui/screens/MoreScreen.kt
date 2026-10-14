package com.krstech.dockers.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.composables.icons.lucide.*
import com.krstech.dockers.engine.EngineState
import com.krstech.dockers.ui.LocalAppContainer
import com.krstech.dockers.ui.components.*
import com.krstech.dockers.ui.navigation.Routes
import com.krstech.dockers.ui.theme.ShadTheme

@Composable
fun MoreScreen(nav: NavHostController) {
    val app = LocalAppContainer.current
    val state by app.engine.state.collectAsStateWithLifecycle()
    val c = ShadTheme.colors
    Column(Modifier.fillMaxSize()) {
        ShadTopBar("More", subtitle = (state as? EngineState.Connected)?.let { "${it.profile.name} · Docker ${it.version.version}" } ?: "Not connected")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Resources")
            MoreItem("Volumes", "Persistent data", Lucide.HardDrive) { nav.navigate(Routes.VOLUMES) }
            MoreItem("Networks", "Bridges, overlays, macvlan", Lucide.Network) { nav.navigate(Routes.NETWORKS) }
            MoreItem("Stacks", "Docker Compose projects", Lucide.Boxes) { nav.navigate(Routes.STACKS) }
            MoreItem("Events", "Live daemon event stream", Lucide.Activity) { nav.navigate(Routes.EVENTS) }
            SectionTitle("Tools")
            MoreItem("Device shell", "sh / su on this phone", Lucide.SquareTerminal) { nav.navigate(Routes.terminal()) }
            MoreItem("Engines", "Local, remote and WSL2 daemons", Lucide.Server) { nav.navigate(Routes.ENGINES) }
            MoreItem("Local engine", "Install & run dockerd (root)", Lucide.Smartphone) { nav.navigate(Routes.LOCAL_ENGINE) }
            SectionTitle("App")
            MoreItem("Settings", "Theme, logs, terminal", Lucide.Settings) { nav.navigate(Routes.SETTINGS) }
            MoreItem("About", "KRS Dockers", Lucide.Info) { nav.navigate(Routes.ABOUT) }
            BottomPadding()
        }
    }
}

@Composable
private fun MoreItem(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    val c = ShadTheme.colors
    ShadCard(onClick = onClick, contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = c.foreground, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = c.foreground)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
            }
            Icon(Lucide.ChevronRight, null, tint = c.mutedForeground, modifier = Modifier.size(16.dp))
        }
    }
}
