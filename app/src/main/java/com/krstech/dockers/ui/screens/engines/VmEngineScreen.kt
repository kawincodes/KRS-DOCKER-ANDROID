package com.krstech.dockers.ui.screens.engines

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.composables.icons.lucide.*
import com.krstech.dockers.engine.AppSettings
import com.krstech.dockers.engine.vm.VmManager.Phase
import com.krstech.dockers.ui.LocalAppContainer
import com.krstech.dockers.ui.components.*
import com.krstech.dockers.ui.navigation.Routes
import com.krstech.dockers.ui.screens.ErrorBanner
import com.krstech.dockers.ui.screens.InfoBanner
import com.krstech.dockers.ui.screens.LogView
import com.krstech.dockers.ui.theme.MonoStyle
import com.krstech.dockers.ui.theme.ShadTheme
import com.krstech.dockers.util.Format
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** "Docker on this phone": the no-root engine (Alpine + Docker in a QEMU VM). */
@Composable
fun VmEngineScreen(nav: NavHostController) {
    val app = LocalAppContainer.current
    val vm = app.vm
    val status by vm.status.collectAsStateWithLifecycle()
    val console by vm.console.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = ShadTheme.colors
    var showConsole by remember { mutableStateOf(false) }
    var consoleInput by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var adbResult by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    fun update(block: (AppSettings) -> AppSettings) = scope.launch { app.settings.update(block) }

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(status.phase) { while (status.phase == Phase.STARTING) { now = System.currentTimeMillis(); delay(1000) } }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) context.contentResolver.openInputStream(uri)?.let { vm.installFrom(it) }
    }
    fun startVm() {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else vm.start()
    }

    Column(Modifier.fillMaxSize()) {
        ShadTopBar("Docker on this phone", subtitle = "Built-in Linux VM · no root needed", onBack = { nav.popBackStack() }) {
            ShadIconButton(Lucide.SquareTerminal, "Console", onClick = { showConsole = !showConsole })
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (status.phase == Phase.UNSUPPORTED) {
                ErrorBanner(status.unsupportedReason)
                ShadCard {
                    CardHeader("Other ways to run Docker", "Everything else in the app works with these engines.")
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ShadButton(onClick = { nav.navigate(Routes.LOCAL_ENGINE) }, variant = ButtonVariant.Outline, leadingIcon = Lucide.Shield, modifier = Modifier.weight(1f)) { ButtonText("Root engine") }
                        ShadButton(onClick = { nav.navigate(Routes.ENGINES) }, leadingIcon = Lucide.Server, modifier = Modifier.weight(1f)) { ButtonText("Remote / WSL") }
                    }
                }
            }

            // ------------------------------------------------------------------ hero
            ShadCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (icon, tint) = when (status.phase) {
                        Phase.RUNNING -> Lucide.CircleCheck to c.success
                        Phase.ERROR -> Lucide.CircleX to c.destructive
                        Phase.STARTING, Phase.STOPPING, Phase.DOWNLOADING, Phase.INSTALLING -> Lucide.LoaderCircle to c.info
                        else -> Lucide.Container to c.mutedForeground
                    }
                    Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (status.phase) {
                                Phase.RUNNING -> "Docker is running"
                                Phase.STARTING -> "Starting Docker…"
                                Phase.STOPPING -> "Stopping…"
                                Phase.DOWNLOADING -> "Downloading Docker VM"
                                Phase.INSTALLING -> "Installing…"
                                Phase.STOPPED -> "Docker is stopped"
                                Phase.ERROR -> "Docker VM stopped unexpectedly"
                                Phase.NOT_INSTALLED -> "Set up Docker on this phone"
                                Phase.UNSUPPORTED -> "Not available on this phone"
                            },
                            style = MaterialTheme.typography.titleMedium, color = c.foreground,
                        )
                        val detail = when (status.phase) {
                            Phase.STARTING -> status.startedAt?.let { "Booting Linux… ${(now - it) / 1000}s (first boot takes 1–3 min)" }
                            Phase.RUNNING -> listOfNotNull(status.dockerVersion?.let { "Docker $it" }, status.readyAfterMs?.let { "ready in ${it / 1000}s" }, "tcp://127.0.0.1:${settings.vmApiPort}").joinToString(" · ")
                            Phase.NOT_INSTALLED -> "One-time download of a ~190 MB Alpine Linux + Docker image."
                            else -> status.message
                        }
                        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
                    }
                    if (status.phase == Phase.RUNNING) ShadBadge("online", BadgeVariant.Success, dot = true)
                }
                if (status.phase == Phase.DOWNLOADING || status.phase == Phase.INSTALLING) {
                    Spacer(Modifier.height(10.dp))
                    status.progress?.let { ShadProgress(it) } ?: ShadProgress(0.15f)
                    status.message?.let { Spacer(Modifier.height(4.dp)); Text(it, style = MaterialTheme.typography.labelSmall, color = c.mutedForeground) }
                }
                if (status.phase == Phase.ERROR || (status.phase == Phase.NOT_INSTALLED && status.message != null)) {
                    status.message?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = c.destructive) }
                }
                status.manifest?.let { m ->
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ShadBadge("Alpine ${m.alpine}", BadgeVariant.Secondary)
                        ShadBadge("Docker ${m.docker}", BadgeVariant.Secondary)
                        ShadBadge(m.arch, BadgeVariant.Outline)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (status.phase) {
                        Phase.NOT_INSTALLED -> {
                            ShadButton(onClick = { vm.download() }, leadingIcon = Lucide.Download, modifier = Modifier.weight(1f)) { ButtonText("Download & install") }
                            ShadButton(onClick = { importLauncher.launch(arrayOf("application/x-tar", "application/octet-stream", "*/*")) }, variant = ButtonVariant.Outline, leadingIcon = Lucide.FolderOpen) { ButtonText("From file") }
                        }
                        Phase.DOWNLOADING -> ShadButton(onClick = { vm.cancelDownload() }, variant = ButtonVariant.Outline, leadingIcon = Lucide.Pause, modifier = Modifier.weight(1f)) { ButtonText("Pause download") }
                        Phase.STOPPED, Phase.ERROR -> ShadButton(onClick = { startVm() }, leadingIcon = Lucide.Play, modifier = Modifier.weight(1f)) { ButtonText("Start Docker") }
                        Phase.STARTING -> {
                            ShadButton(onClick = { showConsole = true }, variant = ButtonVariant.Outline, leadingIcon = Lucide.ScrollText, modifier = Modifier.weight(1f)) { ButtonText("Show boot log") }
                            ShadButton(onClick = { vm.forceStop() }, variant = ButtonVariant.Ghost, leadingIcon = Lucide.X) { ButtonText("Cancel") }
                        }
                        Phase.RUNNING -> {
                            ShadButton(onClick = { nav.navigate(Routes.CONTAINERS) }, leadingIcon = Lucide.Container, modifier = Modifier.weight(1f)) { ButtonText("Containers") }
                            ShadButton(onClick = { nav.navigate(Routes.terminal()) }, variant = ButtonVariant.Secondary, leadingIcon = Lucide.SquareTerminal) { ButtonText("CLI") }
                            ShadButton(onClick = { vm.stop() }, variant = ButtonVariant.Destructive, leadingIcon = Lucide.Square) { ButtonText("Stop") }
                        }
                        Phase.STOPPING -> ShadButton(onClick = { vm.forceStop() }, variant = ButtonVariant.Destructive, leadingIcon = Lucide.Zap, modifier = Modifier.weight(1f)) { ButtonText("Force stop") }
                        Phase.INSTALLING, Phase.UNSUPPORTED -> {}
                    }
                }
            }

            // ------------------------------------------------------------------ console
            if (showConsole || status.phase == Phase.ERROR) {
                ShadCard {
                    CardHeader("VM console", "Serial console of the VM. You are root inside it.")
                    Spacer(Modifier.height(8.dp))
                    LogView(console, Modifier.height(300.dp), fontSize = 11)
                    if (status.phase == Phase.RUNNING || status.phase == Phase.STARTING) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ShadInput(consoleInput, { consoleInput = it }, Modifier.weight(1f), placeholder = "e.g. docker info, df -h, top -n1", monospace = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                                keyboardActions = KeyboardActions(onSend = { vm.sendConsole(consoleInput); consoleInput = "" }))
                            Spacer(Modifier.width(8.dp))
                            ShadIconButton(Lucide.CornerDownLeft, "Send", variant = ButtonVariant.Default, onClick = { vm.sendConsole(consoleInput); consoleInput = "" })
                        }
                    }
                }
            }

            // ------------------------------------------------------------------ ports
            if (status.phase == Phase.RUNNING) {
                ShadCard {
                    CardHeader("Port forwarding", "Ports published with -p are forwarded to this phone automatically.")
                    Spacer(Modifier.height(8.dp))
                    if (status.ports.isEmpty()) Text("No published ports yet. Try: docker run -d -p 8080:80 nginx", style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
                    status.ports.forEach { p ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${p.guestPort}/${p.proto}", style = MonoStyle, color = c.foreground, modifier = Modifier.width(90.dp))
                            Icon(Lucide.ArrowRight, null, tint = c.mutedForeground, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (p.error != null) p.error else "${if (settings.vmExposePortsToLan) "0.0.0.0" else "localhost"}:${p.hostPort}", style = MonoStyle, color = if (p.error != null) c.destructive else c.foreground, modifier = Modifier.weight(1f))
                            if (p.error == null && p.proto == "tcp") ShadIconButton(Lucide.ExternalLink, "Open", onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url))) } })
                        }
                    }
                    if (status.ports.any { it.guestPort < 1024 }) Text("Android doesn't let apps use ports below 1024, so those are mapped to 10000 + port.", style = MaterialTheme.typography.labelSmall, color = c.mutedForeground)
                }
            }

            // ------------------------------------------------------------------ resources
            if (status.phase != Phase.UNSUPPORTED) ShadCard {
                CardHeader("Resources", "Applied the next time Docker starts.")
                Spacer(Modifier.height(10.dp))
                val cores = Runtime.getRuntime().availableProcessors()
                val cpuOptions = listOf(0) + (1..cores.coerceAtMost(8)).toList()
                ShadSelect(settings.vmCpus, cpuOptions, { v -> update { it.copy(vmCpus = v) } }, label = "CPUs", text = { if (it == 0) "Auto (${vm.defaultCpus()})" else "$it" })
                Spacer(Modifier.height(10.dp))
                val maxMem = (vm.deviceMemoryMb() * 3 / 4).coerceAtLeast(1024)
                val memOptions = listOf(0) + listOf(1024, 1536, 2048, 3072, 4096, 6144, 8192).filter { it <= maxMem }
                ShadSelect(settings.vmMemoryMb, memOptions, { v -> update { it.copy(vmMemoryMb = v) } }, label = "Memory", text = { if (it == 0) "Auto (${vm.defaultMemoryMb()} MB)" else "$it MB" })
                Spacer(Modifier.height(4.dp))
                SwitchRow("Start Docker when the app opens", checked = settings.vmAutoStart, onCheckedChange = { v -> update { it.copy(vmAutoStart = v) } })
                SwitchRow("Expose published ports to Wi-Fi", "Lets other devices on your network open containers running on this phone.", checked = settings.vmExposePortsToLan, onCheckedChange = { v -> update { it.copy(vmExposePortsToLan = v) } })
                SwitchRow("Compatibility mode (split W^X JIT)", "Turn on if the VM fails to start on your phone. Slightly slower.", checked = settings.vmSplitWx, onCheckedChange = { v -> update { it.copy(vmSplitWx = v) } })
            }

            // ------------------------------------------------------------------ storage
            if (status.installed) ShadCard {
                CardHeader("Storage", "Images, containers and volumes live in the VM disk.")
                Spacer(Modifier.height(6.dp))
                KeyValueRow("Disk used", Format.bytes(status.diskBytes))
                KeyValueRow("Disk max size", status.manifest?.diskSize ?: "–")
                KeyValueRow("Kernel", status.manifest?.kernel ?: "–")
                Spacer(Modifier.height(8.dp))
                ShadButton(onClick = { confirmDelete = true }, variant = ButtonVariant.Outline, enabled = status.phase == Phase.STOPPED || status.phase == Phase.ERROR, leadingIcon = Lucide.Trash2) { ButtonText("Delete VM and all data") }
            }

            // ------------------------------------------------------------------ tips
            if (status.phase != Phase.UNSUPPORTED) ShadCard {
                CardHeader("Keep Docker running in the background")
                Spacer(Modifier.height(6.dp))
                val pm = context.getSystemService(PowerManager::class.java)
                if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                    Text("Battery optimization may pause the VM when the screen is off.", style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
                    Spacer(Modifier.height(6.dp))
                    ShadButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }
                    }, variant = ButtonVariant.Outline, size = ButtonSize.Sm, leadingIcon = Lucide.BatteryCharging) { ButtonText("Allow background running") }
                    Spacer(Modifier.height(10.dp))
                }
                Text("Android 12+ may kill busy background processes (\"phantom process killer\"). The built-in ADB connector can switch that off for you, without root or a computer.", style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
                Spacer(Modifier.height(6.dp))
                val adbStatus by app.adb.status.collectAsStateWithLifecycle()
                if (adbStatus.ready) {
                    ShadButton(onClick = {
                        scope.launch {
                            val tasks = com.krstech.dockers.engine.adb.AdbTasks.builtIn(context.packageName).filter { it.id == "keep_vm_alive" || it.id == "background" }.filter { Build.VERSION.SDK_INT >= it.minSdk }
                            val out = tasks.map { t -> runCatching { app.adb.exec(t.command) }.fold({ "${t.title}: ${if (it.ok) "done" else "exit ${it.exitCode}"}" }, { e -> "${t.title}: ${e.message}" }) }
                            adbResult = out.joinToString("\n")
                        }
                    }, variant = ButtonVariant.Outline, size = ButtonSize.Sm, leadingIcon = Lucide.ShieldCheck) { ButtonText("Keep VM alive (via ADB)") }
                    adbResult?.let { Spacer(Modifier.height(6.dp)); Text(it, style = MonoStyle, color = c.foreground) }
                } else {
                    ShadButton(onClick = { nav.navigate(Routes.ADB) }, variant = ButtonVariant.Outline, size = ButtonSize.Sm, leadingIcon = Lucide.ShieldCheck) { ButtonText("Set up built-in ADB") }
                    Spacer(Modifier.height(6.dp))
                    Text("Or from a computer: adb shell settings put global settings_enable_monitor_phantom_procs false", style = MonoStyle, color = c.mutedForeground)
                }
                Spacer(Modifier.height(10.dp))
                Text("The VM emulates its CPU in software, so containers run slower than on a PC. Prefer small images (alpine, *-slim). Rooted phones can use the native root engine for full speed.", style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
            }
            if (status.phase == Phase.RUNNING) InfoBanner("The app is connected to this engine. Containers, Images, Stacks, Distros and the terminal's docker command all use it.")
            Spacer(Modifier.height(40.dp))
        }
    }

    if (confirmDelete) ShadDialog(onDismiss = { confirmDelete = false }, title = "Delete the Docker VM?", description = "All images, containers and volumes stored in the VM will be erased. You can download a fresh VM afterwards.", confirmText = "Delete", destructive = true,
        onConfirm = { confirmDelete = false; vm.uninstall() })
}
