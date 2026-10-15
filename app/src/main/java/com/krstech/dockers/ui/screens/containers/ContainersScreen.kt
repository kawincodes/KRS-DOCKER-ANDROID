package com.krstech.dockers.ui.screens.containers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.composables.icons.lucide.*
import com.krstech.dockers.engine.model.ContainerSummary
import com.krstech.dockers.ui.appViewModel
import com.krstech.dockers.ui.components.*
import com.krstech.dockers.ui.navigation.Routes
import com.krstech.dockers.ui.screens.BottomPadding
import com.krstech.dockers.ui.screens.ConnectionGate
import com.krstech.dockers.ui.screens.ErrorBanner
import com.krstech.dockers.ui.theme.ShadTheme
import com.krstech.dockers.util.Format

@Composable
fun ContainersScreen(nav: NavHostController) {
    val vm = appViewModel { ContainersViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val toast = LocalToast.current
    var showRun by remember { mutableStateOf(false) }
    var confirmPrune by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<ContainerSummary?>(null) }
    val c = ShadTheme.colors

    Column(Modifier.fillMaxSize()) {
        ShadTopBar("Containers", subtitle = "${state.runningCount} running · ${state.stoppedCount} stopped") {
            ShadIconButton(Lucide.RefreshCw, "Refresh", onClick = { vm.load() })
            ShadMenu(listOf(
                MenuAction("Run a container", Lucide.Play) { showRun = true },
                MenuAction("Prune stopped", Lucide.Trash2, destructive = true) { confirmPrune = true },
            ), icon = Lucide.EllipsisVertical)
        }
        ConnectionGate(onOpenEngines = { nav.navigate(Routes.ENGINES) }, onOpenVm = { nav.navigate(Routes.VM_ENGINE) }) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    ShadInput(state.query, vm::setQuery, placeholder = "Search by name, image or id", leadingIcon = Lucide.Search)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ShadChip("All", state.filter == "all", { vm.setFilter("all") }, state.containers.size)
                        ShadChip("Running", state.filter == "running", { vm.setFilter("running") }, state.runningCount)
                        ShadChip("Stopped", state.filter == "stopped", { vm.setFilter("stopped") }, state.stoppedCount)
                    }
                }
                ErrorBanner(state.error, Modifier.padding(horizontal = 16.dp))
                if (state.loading && state.containers.isEmpty()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { repeat(5) { ShadSkeleton(Modifier.fillMaxWidth().height(72.dp)) } }
                } else if (state.filtered.isEmpty()) {
                    EmptyState("No containers", if (state.query.isBlank()) "Run a container to get started." else "Nothing matches “${state.query}”.", icon = Lucide.Container,
                        action = { ShadButton(onClick = { showRun = true }, leadingIcon = Lucide.Play) { ButtonText("Run a container") } })
                } else {
                    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.filtered, key = { it.id }) { ct ->
                            ContainerRow(
                                ct, busy = ct.id in state.busyIds,
                                onOpen = { nav.navigate(Routes.containerDetail(ct.id)) },
                                onAction = { a -> if (a == "remove") confirmRemove = ct else vm.action(ct.id, a, toast) },
                                onTerminal = { nav.navigate(Routes.terminal(ct.id, ct.name)) },
                            )
                        }
                        item { BottomPadding() }
                    }
                }
            }
        }
    }

    if (showRun) RunContainerSheet(onDismiss = { showRun = false }, onCreated = { showRun = false; toast("Container started"); vm.load(silent = true) })
    if (confirmPrune) ShadDialog(onDismiss = { confirmPrune = false }, title = "Prune stopped containers?", description = "All stopped containers will be removed. This cannot be undone.", confirmText = "Prune", destructive = true, onConfirm = { confirmPrune = false; vm.pruneStopped(toast) })
    confirmRemove?.let { ct ->
        var withVolumes by remember { mutableStateOf(false) }
        ShadDialog(onDismiss = { confirmRemove = null }, title = "Remove ${ct.name}?", description = "The container will be force-removed.", confirmText = "Remove", destructive = true,
            onConfirm = { confirmRemove = null; vm.action(ct.id, if (withVolumes) "remove_volumes" else "remove", toast) }) {
            SwitchRow("Also remove anonymous volumes", checked = withVolumes, onCheckedChange = { withVolumes = it })
        }
    }
}

@Composable
fun ContainerRow(ct: ContainerSummary, busy: Boolean, onOpen: () -> Unit, onAction: (String) -> Unit, onTerminal: () -> Unit) {
    val c = ShadTheme.colors
    ShadCard(onClick = onOpen, contentPadding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(ct.state)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(ct.name, style = MaterialTheme.typography.titleSmall, color = c.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ct.image, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ct.status, style = MaterialTheme.typography.labelSmall, color = c.mutedForeground, maxLines = 1)
                    val ports = ct.ports.filter { it.publicPort != null }.distinctBy { it.publicPort }.take(3)
                    ports.forEach { p -> ShadBadge("${p.publicPort}→${p.privatePort}", BadgeVariant.Outline) }
                    ct.composeProject?.let { ShadBadge(it, BadgeVariant.Info) }
                }
            }
            if (busy) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp, color = c.mutedForeground)
                Spacer(Modifier.width(8.dp))
            } else {
                if (ct.isRunning) ShadIconButton(Lucide.Square, "Stop", onClick = { onAction("stop") }, size = 34.dp)
                else ShadIconButton(Lucide.Play, "Start", onClick = { onAction("start") }, size = 34.dp, tint = c.success)
            }
            ShadMenu(buildList {
                if (ct.isRunning) {
                    add(MenuAction("Open terminal", Lucide.Terminal, onClick = onTerminal))
                    add(MenuAction("Restart", Lucide.RotateCw) { onAction("restart") })
                    add(MenuAction(if (ct.isPaused) "Unpause" else "Pause", Lucide.Pause) { onAction(if (ct.isPaused) "unpause" else "pause") })
                    add(MenuAction("Kill", Lucide.Zap, destructive = true) { onAction("kill") })
                }
                add(MenuAction("Remove", Lucide.Trash2, destructive = true) { onAction("remove") })
            }, icon = Lucide.EllipsisVertical)
        }
    }
}

