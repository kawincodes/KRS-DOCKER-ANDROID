package com.krstech.dockers.ui.screens.adb

import android.Manifest
import android.content.Intent
import android.os.Build
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.composables.icons.lucide.*
import com.krstech.dockers.AppContainer
import com.krstech.dockers.engine.adb.AdbManager.Phase
import com.krstech.dockers.engine.adb.AdbPairingService
import com.krstech.dockers.engine.adb.AdbTask
import com.krstech.dockers.engine.adb.AdbTasks
import com.krstech.dockers.ui.appViewModel
import com.krstech.dockers.ui.components.*
import com.krstech.dockers.ui.navigation.Routes
import com.krstech.dockers.ui.screens.ErrorBanner
import com.krstech.dockers.ui.screens.InfoBanner
import com.krstech.dockers.ui.screens.LogView
import com.krstech.dockers.ui.theme.MonoStyle
import com.krstech.dockers.ui.theme.ShadTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TaskRun(val title: String, val command: String, val output: String = "", val exitCode: Int? = null, val running: Boolean = true, val error: String? = null)

class AdbViewModel(private val app: AppContainer) : ViewModel() {
    private val ctx get() = app.appContext
    val builtIn = AdbTasks.builtIn(ctx.packageName).filter { Build.VERSION.SDK_INT >= it.minSdk }
    private val _custom = MutableStateFlow(AdbTasks.loadCustom(ctx))
    val custom: StateFlow<List<AdbTask>> = _custom.asStateFlow()
    private val _run = MutableStateFlow<TaskRun?>(null)
    val run: StateFlow<TaskRun?> = _run.asStateFlow()

    init { app.adb.refresh() }

    fun runTask(title: String, command: String) {
        if (_run.value?.running == true) return
        _run.value = TaskRun(title, command)
        viewModelScope.launch {
            runCatching { app.adb.exec(command) { chunk -> _run.update { it?.copy(output = it.output + chunk) } } }
                .onSuccess { r -> _run.update { it?.copy(output = r.output, exitCode = r.exitCode, running = false) } }
                .onFailure { e -> _run.update { it?.copy(running = false, error = e.message ?: e.javaClass.simpleName) } }
        }
    }

    fun clearRun() { _run.value = null }

    fun connect(port: Int?) = viewModelScope.launch { if (app.adb.connect(port).isSuccess) app.adb.startService() }
    fun pair(port: Int, code: String) = viewModelScope.launch { if (app.adb.pair(port, code).isSuccess && app.adb.connect().isSuccess) app.adb.startService() }
    fun startService() = viewModelScope.launch { app.adb.startService() }

    fun saveTask(name: String, command: String) {
        val t = AdbTask("custom_${System.currentTimeMillis()}", name, command = command)
        _custom.value = _custom.value + t
        AdbTasks.saveCustom(ctx, _custom.value)
    }

    fun deleteTask(t: AdbTask) {
        _custom.value = _custom.value - t
        AdbTasks.saveCustom(ctx, _custom.value)
    }
}

/** "ADB & shell": built-in ADB connector with Shizuku-style privileged tasks (no root). */
@Composable
fun AdbScreen(nav: NavHostController) {
    val vm = appViewModel { AdbViewModel(it) }
    val app = com.krstech.dockers.ui.LocalAppContainer.current
    val status by app.adb.status.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()
    val custom by vm.custom.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = ShadTheme.colors
    var manualPort by remember { mutableStateOf("") }
    var pairPort by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var showSave by remember { mutableStateOf(false) }

    LaunchedEffect(status.pairingPort) { status.pairingPort?.let { pairPort = it.toString() } }

    fun openWirelessDebugging() {
        val i = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        runCatching { context.startActivity(i) }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
    fun startPairing() {
        context.startForegroundService(Intent(context, AdbPairingService::class.java).setAction(AdbPairingService.ACTION_START))
        openWirelessDebugging()
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startPairing() }
    fun pairWithNotification() {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startPairing()
    }

    Column(Modifier.fillMaxSize()) {
        ShadTopBar("ADB & shell", subtitle = "Shell privileges without root, like Shizuku", onBack = { nav.popBackStack() }) {
            ShadIconButton(Lucide.RefreshCw, "Refresh", onClick = { app.adb.refresh() })
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // ------------------------------------------------------------------ status
            ShadCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (icon, tint) = when {
                        status.privileged -> Lucide.ShieldCheck to c.success
                        status.adbConnected -> Lucide.Plug to c.info
                        status.phase == Phase.ERROR -> Lucide.CircleX to c.destructive
                        status.phase == Phase.PAIRING || status.phase == Phase.CONNECTING -> Lucide.LoaderCircle to c.info
                        else -> Lucide.Unplug to c.mutedForeground
                    }
                    Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                status.privileged -> "Shell service running"
                                status.adbConnected -> "Connected over ADB"
                                status.phase == Phase.PAIRING -> "Pairing…"
                                status.phase == Phase.CONNECTING -> "Connecting…"
                                status.paired -> "Paired · not connected"
                                else -> "Not set up"
                            },
                            style = MaterialTheme.typography.titleMedium, color = c.foreground,
                        )
                        Text(status.service ?: status.message ?: "Uses this phone's Wireless debugging to get shell (uid 2000) access.", style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
                    }
                    if (status.privileged) ShadBadge("uid 2000", BadgeVariant.Success, dot = true)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        status.privileged -> {
                            ShadButton(onClick = { nav.navigate(Routes.ADB_SHELL) }, leadingIcon = Lucide.SquareTerminal, modifier = Modifier.weight(1f)) { ButtonText("ADB shell") }
                            ShadButton(onClick = { app.adb.stopService() }, variant = ButtonVariant.Outline, leadingIcon = Lucide.Square) { ButtonText("Stop") }
                        }
                        status.adbConnected -> {
                            ShadButton(onClick = { vm.startService() }, leadingIcon = Lucide.Play, modifier = Modifier.weight(1f)) { ButtonText("Start shell service") }
                            ShadButton(onClick = { nav.navigate(Routes.ADB_SHELL) }, variant = ButtonVariant.Outline, leadingIcon = Lucide.SquareTerminal) { ButtonText("Shell") }
                        }
                        status.paired || !status.wirelessSupported -> {
                            ShadButton(onClick = { vm.connect(if (status.wirelessSupported) null else 5555) }, loading = status.phase == Phase.CONNECTING, leadingIcon = Lucide.Plug, modifier = Modifier.weight(1f)) { ButtonText("Connect & start") }
                            if (status.wirelessSupported) ShadButton(onClick = { pairWithNotification() }, variant = ButtonVariant.Outline, leadingIcon = Lucide.Link) { ButtonText("Re-pair") }
                        }
                        else -> ShadButton(onClick = { pairWithNotification() }, loading = status.phase == Phase.PAIRING, leadingIcon = Lucide.Link, modifier = Modifier.weight(1f)) { ButtonText("Pair with Wireless debugging") }
                    }
                }
            }

            // ------------------------------------------------------------------ setup guide
            if (!status.privileged) {
                ShadCard {
                    CardHeader("How to set up", if (status.wirelessSupported) "One time per phone. Takes a minute." else "Android 10 and older need a computer once.")
                    Spacer(Modifier.height(8.dp))
                    if (status.wirelessSupported) {
                        Step(1, "Turn on Developer options: Settings → About phone → tap Build number 7 times.")
                        Step(2, "Connect to Wi-Fi, then tap \"Pair with Wireless debugging\" above. Turn on Wireless debugging in the screen that opens.")
                        Step(3, "Tap \"Pair device with pairing code\". Type the 6-digit code into KRS Dockers' notification (or below, in split screen).")
                        Step(4, "Done. The app connects and starts its shell service. It keeps working until you reboot.")
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                            ShadInput(pairPort, { pairPort = it.filter(Char::isDigit) }, Modifier.weight(1f), label = "Pairing port", placeholder = "e.g. 37123", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            ShadInput(pairCode, { pairCode = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f), label = "Pairing code", placeholder = "123456", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                        }
                        Spacer(Modifier.height(8.dp))
                        ShadButton(onClick = { vm.pair(pairPort.toInt(), pairCode) }, enabled = pairPort.isNotBlank() && pairCode.length == 6, variant = ButtonVariant.Secondary, modifier = Modifier.fillMaxWidth()) { ButtonText("Pair with this code") }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            ShadInput(manualPort, { manualPort = it.filter(Char::isDigit) }, Modifier.weight(1f), label = "Or connect to a port", placeholder = "Wireless debugging port", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            Spacer(Modifier.width(8.dp))
                            ShadButton(onClick = { vm.connect(manualPort.toIntOrNull()) }, enabled = manualPort.isNotBlank(), variant = ButtonVariant.Outline) { ButtonText("Connect") }
                        }
                    } else {
                        Step(1, "Enable USB debugging in Developer options and connect the phone to a computer.")
                        Step(2, "On the computer run:  adb tcpip 5555")
                        Step(3, "Unplug, then tap \"Connect & start\" above and allow the debugging prompt.")
                    }
                }
            }
            ErrorBanner(status.message.takeIf { status.phase == Phase.ERROR })

            // ------------------------------------------------------------------ output
            run?.let { r ->
                ShadCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = MaterialTheme.typography.titleSmall, color = c.foreground)
                            Text(when { r.running -> "Running…"; r.error != null -> "Failed"; else -> "Exit code ${r.exitCode}" }, style = MaterialTheme.typography.bodySmall,
                                color = if (r.error != null || (r.exitCode ?: 0) != 0) c.destructive else c.mutedForeground)
                        }
                        ShadIconButton(Lucide.X, "Close", onClick = { vm.clearRun() })
                    }
                    Spacer(Modifier.height(8.dp))
                    LogView(r.error ?: r.output, Modifier.height(240.dp), fontSize = 11)
                }
            }

            // ------------------------------------------------------------------ tasks
            ShadCard {
                CardHeader("Tasks", "Run as the shell user. Needs the shell service or an ADB connection.")
                Spacer(Modifier.height(6.dp))
                (vm.builtIn + custom).forEach { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = MaterialTheme.typography.titleSmall, color = c.foreground)
                            Text(t.description.ifBlank { t.command }, style = if (t.builtIn) MaterialTheme.typography.bodySmall else MonoStyle, color = c.mutedForeground, maxLines = 2)
                        }
                        if (!t.builtIn) ShadIconButton(Lucide.Trash2, "Delete", onClick = { vm.deleteTask(t) }, size = 32.dp)
                        ShadButton(onClick = { vm.runTask(t.title, t.command) }, size = ButtonSize.Sm, variant = ButtonVariant.Outline, enabled = status.ready && run?.running != true, leadingIcon = Lucide.Play) { ButtonText("Run") }
                    }
                }
            }

            // ------------------------------------------------------------------ command runner
            ShadCard {
                CardHeader("Run a command", "e.g. pm list packages -3, dumpsys battery, settings list global")
                Spacer(Modifier.height(8.dp))
                ShadInput(command, { command = it }, placeholder = "shell command", monospace = true, singleLine = false, minLines = 2)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShadButton(onClick = { vm.runTask(command.lineSequence().first().take(40), command) }, enabled = status.ready && command.isNotBlank() && run?.running != true, leadingIcon = Lucide.Play, modifier = Modifier.weight(1f)) { ButtonText("Run") }
                    ShadButton(onClick = { showSave = true }, enabled = command.isNotBlank(), variant = ButtonVariant.Outline, leadingIcon = Lucide.Save) { ButtonText("Save as task") }
                }
            }

            InfoBanner("The shell user can manage apps, settings and permissions, but it can't create containers: Docker still runs in the Docker VM. ADB keeps the VM from being killed in the background.")
            Spacer(Modifier.height(40.dp))
        }
    }

    if (showSave) {
        var name by remember { mutableStateOf("") }
        ShadDialog(onDismiss = { showSave = false }, title = "Save task", confirmText = "Save", confirmEnabled = name.isNotBlank(),
            onConfirm = { vm.saveTask(name.trim(), command.trim()); showSave = false }) {
            ShadInput(name, { name = it }, label = "Task name", placeholder = "Clear app cache")
            Spacer(Modifier.height(8.dp))
            Text(command, style = MonoStyle, color = c.mutedForeground)
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    val c = ShadTheme.colors
    Row(Modifier.padding(vertical = 4.dp)) {
        ShadBadge(n.toString(), BadgeVariant.Secondary)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.foreground, modifier = Modifier.weight(1f))
    }
}
