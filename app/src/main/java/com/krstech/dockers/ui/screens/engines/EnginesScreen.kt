package com.krstech.dockers.ui.screens.engines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.composables.icons.lucide.*
import com.krstech.dockers.engine.EngineState
import com.krstech.dockers.engine.profile.EngineProfile
import com.krstech.dockers.engine.profile.EngineType
import com.krstech.dockers.ui.LocalAppContainer
import com.krstech.dockers.ui.components.*
import com.krstech.dockers.ui.navigation.Routes
import com.krstech.dockers.ui.screens.BottomPadding
import com.krstech.dockers.ui.theme.ShadTheme
import kotlinx.coroutines.launch

@Composable
fun EnginesScreen(nav: NavHostController) {
    val app = LocalAppContainer.current
    val profiles by app.profiles.profiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val engineState by app.engine.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val c = ShadTheme.colors
    var deleteTarget by remember { mutableStateOf<EngineProfile?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ShadTopBar("Engines", subtitle = "Docker daemons this app can talk to", onBack = { nav.popBackStack() }) {
            ShadIconButton(Lucide.Plus, "Add", onClick = { showAdd = true })
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                when (val s = engineState) {
                    is EngineState.Connected -> ShadCard(contentPadding = PaddingValues(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Lucide.CircleCheck, null, tint = c.success, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Connected to ${s.profile.name}", style = MaterialTheme.typography.titleSmall, color = c.foreground)
                                Text("Docker ${s.version.version} · API ${s.version.apiVersion} · ${s.info.operatingSystem}", style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
                            }
                            ShadButton(onClick = { app.engine.disconnect() }, variant = ButtonVariant.Outline, size = ButtonSize.Sm) { ButtonText("Disconnect") }
                        }
                    }
                    is EngineState.Error -> com.krstech.dockers.ui.screens.ErrorBanner("${s.profile.name}: ${s.message}")
                    is EngineState.Connecting -> com.krstech.dockers.ui.screens.InfoBanner("Connecting to ${s.profile.name}…")
                    EngineState.Disconnected -> com.krstech.dockers.ui.screens.InfoBanner("Not connected. Pick an engine below.")
                }
            }
            items(profiles, key = { it.id }) { p ->
                val active = engineState.profileOrNull?.id == p.id
                val connected = active && engineState is EngineState.Connected
                ShadCard(contentPadding = PaddingValues(12.dp), onClick = { if (p.type == EngineType.LOCAL) nav.navigate(Routes.LOCAL_ENGINE) else app.engine.connect(p) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(when (p.type) { EngineType.LOCAL -> Lucide.Smartphone; EngineType.WSL -> Lucide.Monitor; EngineType.REMOTE -> Lucide.Server }, null, tint = if (connected) c.success else c.mutedForeground, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall, color = c.foreground)
                            Text(p.url, style = com.krstech.dockers.ui.theme.MonoStyle, color = c.mutedForeground)
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ShadBadge(p.type.label.substringBefore(" ("), BadgeVariant.Secondary)
                                if (p.isTls) ShadBadge("TLS", BadgeVariant.Info)
                                if (connected) ShadBadge("connected", BadgeVariant.Success, dot = true)
                            }
                        }
                        ShadMenu(buildList {
                            add(MenuAction("Connect", Lucide.Plug) { app.engine.connect(p) })
                            if (p.type == EngineType.LOCAL) add(MenuAction("Manage local engine", Lucide.Settings2) { nav.navigate(Routes.LOCAL_ENGINE) })
                            add(MenuAction("Edit", Lucide.Pencil) { nav.navigate(Routes.engineEdit(p.id)) })
                            add(MenuAction("Delete", Lucide.Trash2, destructive = true) { deleteTarget = p })
                        }, icon = Lucide.EllipsisVertical)
                    }
                }
            }
            item {
                ShadCard(contentPadding = PaddingValues(14.dp)) {
                    CardHeader("Windows / WSL2 quick guide", "How to reach Docker Desktop from this phone")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "1. Docker Desktop → Settings → General → enable “Expose daemon on tcp://localhost:2375 without TLS”.\n" +
                        "2. In PowerShell (admin) forward the port to your LAN:\n   netsh interface portproxy add v4tov4 listenport=2375 listenaddress=0.0.0.0 connectport=2375 connectaddress=127.0.0.1\n   New-NetFirewallRule -DisplayName \"Docker API\" -Direction Inbound -LocalPort 2375 -Protocol TCP -Action Allow\n" +
                        "3. Or inside a WSL2 distro: sudo dockerd -H unix:///var/run/docker.sock -H tcp://0.0.0.0:2375 (then forward with netsh/mirrored networking).\n" +
                        "4. Add an engine of type “Windows / WSL2” and use “Scan LAN” to find your PC.",
                        style = MaterialTheme.typography.bodySmall, color = c.mutedForeground,
                    )
                }
            }
            item { BottomPadding() }
        }
    }

    if (showAdd) {
        ShadDialog(onDismiss = { showAdd = false }, title = "Add engine", description = "Where does the Docker daemon run?") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EngineType.entries.forEach { t ->
                    ShadCard(contentPadding = PaddingValues(12.dp), onClick = { showAdd = false; if (t == EngineType.LOCAL) nav.navigate(Routes.LOCAL_ENGINE) else nav.navigate(Routes.engineEdit(type = t.name)) }) {
                        Text(t.label, style = MaterialTheme.typography.titleSmall, color = c.foreground)
                        Text(t.description, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
                    }
                }
            }
        }
    }
    deleteTarget?.let { p ->
        ShadDialog(onDismiss = { deleteTarget = null }, title = "Delete ${p.name}?", confirmText = "Delete", destructive = true, onConfirm = { deleteTarget = null; scope.launch { app.profiles.delete(p.id); toast("Deleted") } })
    }
}
