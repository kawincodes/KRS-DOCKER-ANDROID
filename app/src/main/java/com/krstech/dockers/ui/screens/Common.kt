package com.krstech.dockers.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plug
import com.composables.icons.lucide.TriangleAlert
import com.krstech.dockers.engine.EngineState
import com.krstech.dockers.ui.LocalAppContainer
import com.krstech.dockers.ui.components.ButtonText
import com.krstech.dockers.ui.components.ButtonVariant
import com.krstech.dockers.ui.components.EmptyState
import com.krstech.dockers.ui.components.ShadButton
import com.krstech.dockers.ui.theme.MonoStyle
import com.krstech.dockers.ui.theme.ShadRadius
import com.krstech.dockers.ui.theme.ShadTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Shows [content] only when an engine is connected; otherwise a call-to-action to the Engines screen. */
@Composable
fun ConnectionGate(onOpenEngines: () -> Unit, onOpenVm: (() -> Unit)? = null, content: @Composable (EngineState.Connected) -> Unit) {
    val engine = LocalAppContainer.current.engine
    val state by engine.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is EngineState.Connected -> content(s)
        is EngineState.Connecting -> EmptyState("Connecting to ${s.profile.name}…", s.profile.url, icon = Lucide.Plug)
        is EngineState.Error -> EmptyState("Couldn't connect to ${s.profile.name}", s.message, icon = Lucide.TriangleAlert, action = {
            Row {
                ShadButton(onClick = { engine.connect(s.profile) }, variant = ButtonVariant.Outline) { ButtonText("Retry") }
                Spacer(Modifier.width(8.dp))
                ShadButton(onClick = onOpenEngines) { ButtonText("Engines") }
            }
        })
        EngineState.Disconnected -> EmptyState("Docker isn't running", "Start Docker on this phone (no root needed), or connect to a remote host or Docker Desktop on Windows via WSL2.", icon = Lucide.Plug, action = {
            Row {
                ShadButton(onClick = onOpenVm ?: onOpenEngines) { ButtonText("Start Docker") }
                Spacer(Modifier.width(8.dp))
                ShadButton(onClick = onOpenEngines, variant = ButtonVariant.Outline) { ButtonText("Remote") }
            }
        })
    }
}

private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

@Composable
fun JsonViewer(element: JsonElement?, modifier: Modifier = Modifier) {
    val c = ShadTheme.colors
    val text = element?.let { runCatching { pretty.encodeToString(JsonElement.serializer(), it) }.getOrDefault(it.toString()) } ?: "…"
    Box(modifier.fillMaxSize().clip(ShadRadius.md).background(c.muted).border(1.dp, c.border, ShadRadius.md)) {
        SelectionContainer {
            Text(text, style = MonoStyle, color = c.foreground, modifier = Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp))
        }
    }
}

@Composable
fun ErrorBanner(message: String?, modifier: Modifier = Modifier) {
    if (message == null) return
    val c = ShadTheme.colors
    Row(
        modifier.fillMaxWidth().clip(ShadRadius.md).background(c.destructive.copy(alpha = 0.12f)).border(1.dp, c.destructive.copy(alpha = 0.4f), ShadRadius.md).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.TriangleAlert, null, tint = c.destructive, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = c.foreground, modifier = Modifier.weight(1f))
    }
}

@Composable
fun InfoBanner(message: String, modifier: Modifier = Modifier) {
    val c = ShadTheme.colors
    Row(modifier.fillMaxWidth().clip(ShadRadius.md).background(c.info.copy(alpha = 0.10f)).border(1.dp, c.info.copy(alpha = 0.35f), ShadRadius.md).padding(12.dp)) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = c.foreground)
    }
}

@Composable
fun LogView(text: String, modifier: Modifier = Modifier, fontSize: Int = 12) {
    val c = ShadTheme.colors
    val scroll = rememberScrollState()
    androidx.compose.runtime.LaunchedEffect(text.length) { scroll.scrollTo(scroll.maxValue) }
    Box(modifier.fillMaxSize().clip(ShadRadius.md).background(if (c.isDark) c.background else c.foreground).border(1.dp, c.border, ShadRadius.md)) {
        SelectionContainer {
            Text(
                text.ifBlank { "No output yet." }, style = MonoStyle.copy(fontSize = androidx.compose.ui.unit.TextUnit(fontSize.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)),
                color = if (c.isDark) c.foreground else c.background,
                modifier = Modifier.fillMaxSize().verticalScroll(scroll).horizontalScroll(rememberScrollState()).padding(12.dp),
            )
        }
    }
}

@Composable
fun ListSpacer() = Spacer(Modifier.height(8.dp))

@Composable
fun BottomPadding() = Spacer(Modifier.height(96.dp))

@Composable
fun RowActions(content: @Composable () -> Unit) = Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
