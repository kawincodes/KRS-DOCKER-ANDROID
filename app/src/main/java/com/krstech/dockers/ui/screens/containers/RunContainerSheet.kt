package com.krstech.dockers.ui.screens.containers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.krstech.dockers.ui.LocalAppContainer
import com.krstech.dockers.ui.components.*
import com.krstech.dockers.ui.screens.ErrorBanner
import com.krstech.dockers.ui.theme.ShadTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** `docker run` as a form. Mirrors Docker Desktop's "Run a new container" dialog. */
@Composable
fun RunContainerSheet(onDismiss: () -> Unit, onCreated: () -> Unit, initialImage: String = "") {
    val app = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var image by remember { mutableStateOf(initialImage) }
    var name by remember { mutableStateOf("") }
    var ports by remember { mutableStateOf("") }
    var env by remember { mutableStateOf("") }
    var volumes by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var restart by remember { mutableStateOf("no") }
    var network by remember { mutableStateOf("bridge") }
    var tty by remember { mutableStateOf(false) }
    var privileged by remember { mutableStateOf(false) }
    var autoRemove by remember { mutableStateOf(false) }
    var pullFirst by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    ShadSheet(onDismiss = onDismiss, title = "Run a new container", description = "Equivalent of `docker run -d …`") {
        Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ShadInput(image, { image = it }, label = "Image", placeholder = "nginx:alpine")
            ShadInput(name, { name = it }, label = "Container name (optional)", placeholder = "my-nginx")
            ShadInput(ports, { ports = it }, label = "Ports", placeholder = "8080:80, 8443:443/tcp", description = "host:container[/proto], comma separated")
            ShadInput(env, { env = it }, label = "Environment", placeholder = "KEY=value", description = "One per line", singleLine = false, minLines = 2, monospace = true)
            ShadInput(volumes, { volumes = it }, label = "Volumes / binds", placeholder = "data:/var/lib/data", description = "name-or-path:container-path[:ro], one per line", singleLine = false, minLines = 2, monospace = true)
            ShadInput(command, { command = it }, label = "Command (optional)", placeholder = "sh -c \"…\"", monospace = true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShadSelect(restart, listOf("no", "always", "unless-stopped", "on-failure"), { restart = it }, Modifier.weight(1f), label = "Restart policy")
                ShadSelect(network, listOf("bridge", "host", "none"), { network = it }, Modifier.weight(1f), label = "Network")
            }
            SwitchRow("Pull image before running", checked = pullFirst, onCheckedChange = { pullFirst = it })
            SwitchRow("Interactive TTY (-it)", checked = tty, onCheckedChange = { tty = it })
            SwitchRow("Privileged", "Grants all capabilities to the container.", checked = privileged, onCheckedChange = { privileged = it })
            SwitchRow("Remove on exit (--rm)", checked = autoRemove, onCheckedChange = { autoRemove = it })
            progress?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ShadTheme.colors.mutedForeground) }
            ErrorBanner(error)
        }
        Spacer(Modifier.height(16.dp))
        ShadButton(
            onClick = {
                val client = app.engine.client ?: return@ShadButton
                busy = true; error = null
                scope.launch {
                    runCatching {
                        if (pullFirst) {
                            var last = ""
                            client.pull(image.trim()).collect { p -> if (p.status != last) { last = p.status; progress = "${p.status}${p.progress?.let { " $it" } ?: ""}" } }
                        }
                        progress = "Creating container…"
                        val spec = buildRunSpec(image.trim(), ports, env, volumes, command, restart, network, tty, privileged, autoRemove)
                        val created = client.createContainer(name.trim().ifBlank { null }, spec)
                        client.startContainer(created.id)
                    }.onSuccess { onCreated() }.onFailure { error = it.message; busy = false; progress = null }
                }
            },
            modifier = Modifier.fillMaxWidth(), enabled = image.isNotBlank(), loading = busy, leadingIcon = Lucide.Play,
        ) { ButtonText("Run") }
    }
}

internal fun buildRunSpec(image: String, ports: String, env: String, volumes: String, command: String, restart: String, network: String, tty: Boolean, privileged: Boolean, autoRemove: Boolean) = buildJsonObject {
    put("Image", image)
    if (tty) { put("Tty", true); put("OpenStdin", true) }
    val envList = env.lines().map { it.trim() }.filter { it.contains('=') }
    if (envList.isNotEmpty()) putJsonArray("Env") { envList.forEach { add(it) } }
    val cmd = command.trim()
    if (cmd.isNotBlank()) putJsonArray("Cmd") { splitCommand(cmd).forEach { add(it) } }
    val portSpecs = ports.split(',').map { it.trim() }.filter { it.isNotBlank() }.mapNotNull { spec ->
        val proto = spec.substringAfter('/', "tcp")
        val body = spec.substringBefore('/')
        val parts = body.split(':')
        when (parts.size) {
            1 -> Triple("", parts[0], proto)
            2 -> Triple(parts[0], parts[1], proto)
            3 -> Triple(parts[1], parts[2], proto) // ip:host:container -> ignore ip
            else -> null
        }
    }
    if (portSpecs.isNotEmpty()) putJsonObject("ExposedPorts") { portSpecs.forEach { (_, c, p) -> putJsonObject("$c/$p") {} } }
    putJsonObject("HostConfig") {
        putJsonObject("RestartPolicy") { put("Name", restart) }
        put("NetworkMode", network)
        put("Privileged", privileged)
        put("AutoRemove", autoRemove)
        val binds = volumes.lines().map { it.trim() }.filter { it.contains(':') }
        if (binds.isNotEmpty()) putJsonArray("Binds") { binds.forEach { add(it) } }
        if (portSpecs.isNotEmpty()) putJsonObject("PortBindings") {
            portSpecs.groupBy { "${it.second}/${it.third}" }.forEach { (key, list) ->
                putJsonArray(key) { list.forEach { (h, _, _) -> add(buildJsonObject { put("HostIp", ""); put("HostPort", h) }) } }
            }
        }
    }
}

/** Shell-ish split that honours double/single quotes. */
internal fun splitCommand(s: String): List<String> {
    val out = mutableListOf<String>(); val cur = StringBuilder(); var quote: Char? = null
    for (ch in s) {
        when {
            quote != null -> if (ch == quote) quote = null else cur.append(ch)
            ch == '"' || ch == '\'' -> quote = ch
            ch.isWhitespace() -> if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
            else -> cur.append(ch)
        }
    }
    if (cur.isNotEmpty()) out += cur.toString()
    return out
}
