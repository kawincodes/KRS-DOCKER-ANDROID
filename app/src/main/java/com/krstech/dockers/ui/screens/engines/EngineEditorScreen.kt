package com.krstech.dockers.ui.screens.engines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.composables.icons.lucide.*
import com.krstech.dockers.engine.DockerClient
import com.krstech.dockers.engine.profile.EngineProfile
import com.krstech.dockers.engine.profile.EngineType
import com.krstech.dockers.engine.wsl.LanScanner
import com.krstech.dockers.ui.LocalAppContainer
import com.krstech.dockers.ui.components.*
import com.krstech.dockers.ui.screens.ErrorBanner
import com.krstech.dockers.ui.screens.InfoBanner
import com.krstech.dockers.ui.theme.ShadTheme
import kotlinx.coroutines.launch

@Composable
fun EngineEditorScreen(nav: NavHostController, id: String?, typeName: String?) {
    val app = LocalAppContainer.current
    val profiles by app.profiles.profiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val existing = profiles.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val c = ShadTheme.colors
    val type = existing?.type ?: runCatching { EngineType.valueOf(typeName ?: "") }.getOrDefault(EngineType.REMOTE)

    var name by remember(existing) { mutableStateOf(existing?.name ?: if (type == EngineType.WSL) "Docker Desktop (WSL2)" else "Remote host") }
    var url by remember(existing) { mutableStateOf(existing?.url ?: "tcp://192.168.1.100:2375") }
    var useTls by remember(existing) { mutableStateOf(existing?.isTls ?: false) }
    var ca by remember(existing) { mutableStateOf(existing?.caPem ?: "") }
    var cert by remember(existing) { mutableStateOf(existing?.certPem ?: "") }
    var key by remember(existing) { mutableStateOf(existing?.keyPem ?: "") }
    var verifyHost by remember(existing) { mutableStateOf(existing?.verifyHostname ?: false) }
    var apiVersion by remember(existing) { mutableStateOf(existing?.apiVersion ?: "1.43") }
    var socketPath by remember(existing) { mutableStateOf(existing?.hostSocketPath ?: "/var/run/docker.sock") }
    var autoConnect by remember(existing) { mutableStateOf(existing?.autoConnect ?: true) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testError by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableStateOf(0) }
    var hits by remember { mutableStateOf<List<LanScanner.Hit>>(emptyList()) }

    fun build() = (existing ?: EngineProfile(type = type)).copy(
        name = name.trim(), url = url.trim(), caPem = ca.takeIf { useTls && it.isNotBlank() }, certPem = cert.takeIf { useTls && it.isNotBlank() },
        keyPem = key.takeIf { useTls && it.isNotBlank() }, verifyHostname = verifyHost, apiVersion = apiVersion.trim().ifBlank { "1.43" },
        hostSocketPath = socketPath.trim().ifBlank { "/var/run/docker.sock" }, autoConnect = autoConnect, type = type,
    )

    Column(Modifier.fillMaxSize()) {
        ShadTopBar(if (existing != null) "Edit engine" else "New engine", subtitle = type.label, onBack = { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (type == EngineType.WSL) InfoBanner("Docker Desktop with the WSL2 backend listens on tcp://localhost:2375 when “Expose daemon” is enabled; forward it to your LAN with netsh portproxy or run dockerd inside WSL with -H tcp://0.0.0.0:2375. Use Scan LAN to find the PC.")
            ShadInput(name, { name = it }, label = "Name")
            ShadInput(url, { url = it }, label = "Docker host", placeholder = "tcp://192.168.1.100:2375", description = "tcp://host:port (or unix:///path on this device)", monospace = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShadButton(onClick = {
                    scanning = true; hits = emptyList(); scanProgress = 0
                    scope.launch { hits = LanScanner.scan(onProgress = { scanProgress = it }); scanning = false; if (hits.isEmpty()) toast("No Docker daemons found on ${LanScanner.localIpv4()?.substringBeforeLast('.')}.0/24") }
                }, variant = ButtonVariant.Outline, loading = scanning, leadingIcon = Lucide.Radar, modifier = Modifier.weight(1f)) { ButtonText(if (scanning) "Scanning $scanProgress%" else "Scan LAN") }
                ShadButton(onClick = {
                    testing = true; testResult = null; testError = null
                    scope.launch {
                        runCatching { val p = build(); val cl = DockerClient(p.toHost(), p.apiVersion); val v = cl.version(); cl.close(); v }
                            .onSuccess { testResult = "OK: Docker ${it.version} (${it.os}/${it.arch}, API ${it.apiVersion})" }
                            .onFailure { testError = it.message ?: "Failed" }
                        testing = false
                    }
                }, variant = ButtonVariant.Secondary, loading = testing, leadingIcon = Lucide.Activity, modifier = Modifier.weight(1f)) { ButtonText("Test") }
            }
            if (hits.isNotEmpty()) ShadCard {
                CardHeader("Found on your network", "Tap to use")
                Spacer(Modifier.height(6.dp))
                hits.forEach { h -> ShadButton(onClick = { url = h.url; useTls = h.tls; if (name.isBlank()) name = h.ip }, variant = ButtonVariant.Ghost, modifier = Modifier.fillMaxWidth(), leadingIcon = if (h.tls) Lucide.Lock else Lucide.Server) { ButtonText("${h.url}${if (h.tls) " (TLS)" else ""}") } }
            }
            testResult?.let { InfoBanner(it) }
            ErrorBanner(testError)
            ShadSeparator()
            SwitchRow("TLS (--tlsverify)", "Use certificates generated for the daemon (ca.pem, cert.pem, key.pem).", checked = useTls, onCheckedChange = { useTls = it })
            if (useTls) {
                ShadInput(ca, { ca = it }, label = "CA certificate (ca.pem)", singleLine = false, minLines = 3, monospace = true, placeholder = "-----BEGIN CERTIFICATE-----")
                ShadInput(cert, { cert = it }, label = "Client certificate (cert.pem)", singleLine = false, minLines = 3, monospace = true, placeholder = "-----BEGIN CERTIFICATE-----")
                ShadInput(key, { key = it }, label = "Client key (key.pem)", singleLine = false, minLines = 3, monospace = true, placeholder = "-----BEGIN PRIVATE KEY-----", description = "PKCS#8 or PKCS#1 RSA. EC keys: convert with openssl pkcs8 -topk8 -nocrypt.")
                SwitchRow("Verify hostname", "Require the server cert to match the host.", checked = verifyHost, onCheckedChange = { verifyHost = it })
            }
            ShadSeparator()
            ShadInput(apiVersion, { apiVersion = it }, label = "API version", description = "Negotiated down automatically if the daemon is older.")
            ShadInput(socketPath, { socketPath = it }, label = "Daemon socket path on the host", description = "Mounted into helper containers for Compose. Docker Desktop/WSL: /var/run/docker.sock", monospace = true)
            SwitchRow("Connect automatically on launch", checked = autoConnect, onCheckedChange = { autoConnect = it })
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShadButton(onClick = { scope.launch { app.profiles.upsert(build()); toast("Saved"); nav.popBackStack() } }, modifier = Modifier.weight(1f), enabled = name.isNotBlank() && url.isNotBlank(), leadingIcon = Lucide.Save) { ButtonText("Save") }
                ShadButton(onClick = { scope.launch { val p = build(); app.profiles.upsert(p); app.engine.connect(p); nav.popBackStack() } }, modifier = Modifier.weight(1f), variant = ButtonVariant.Secondary, enabled = name.isNotBlank() && url.isNotBlank(), leadingIcon = Lucide.Plug) { ButtonText("Save & connect") }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}
