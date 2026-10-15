package com.krstech.dockers.ui.screens.terminal

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.composables.icons.lucide.*
import com.krstech.dockers.engine.AppSettings
import com.krstech.dockers.ui.LocalAppContainer
import com.krstech.dockers.ui.components.*
import com.krstech.dockers.ui.theme.ShadTheme
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.launch

/**
 * Full terminal backed by a real PTY. Opens a phone shell by default, or `docker exec -it` into a
 * container when [containerId] is given. Sessions persist while the app runs and show up as tabs.
 */
@Composable
fun TerminalScreen(nav: NavHostController, containerId: String?, title: String?) {
    val app = LocalAppContainer.current
    val sessions = app.terminals
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val c = ShadTheme.colors
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var fontPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    var viewRef by remember { mutableStateOf<TerminalView?>(null) }
    var fallback by remember { mutableStateOf(false) }

    // Open the requested session once.
    LaunchedEffect(containerId) {
        if (containerId != null) {
            val existing = sessions.entries.firstOrNull { it.kind == TerminalSessions.Kind.CONTAINER && it.title == (title ?: containerId) && !it.finished }
            if (existing != null) sessions.select(existing)
            else if (sessions.openContainer(containerId, title ?: containerId.take(12)) == null) fallback = true
        } else if (sessions.entries.isEmpty()) {
            sessions.openShell(root = false)
        }
    }

    if (fallback && containerId != null) {
        // This build has no bundled Docker CLI: use the line-mode exec terminal instead.
        LegacyTerminalScreen(nav, containerId, title)
        return
    }

    val current = sessions.current
    LaunchedEffect(current?.id, viewRef) {
        val v = viewRef ?: return@LaunchedEffect
        val s = current?.session ?: return@LaunchedEffect
        if (v.currentSession !== s) { v.attachSession(s); v.onScreenUpdated() }
        v.requestFocus()
    }
    LaunchedEffect(settings.terminalFontSize) { fontPx = (settings.terminalFontSize * density.fontScale * density.density).toInt() }
    LaunchedEffect(fontPx, viewRef) { if (fontPx > 0) viewRef?.setTextSize(fontPx) }

    Column(Modifier.fillMaxSize().imePadding()) {
        ShadTopBar(current?.title ?: "Terminal", subtitle = when (current?.kind) {
            TerminalSessions.Kind.CONTAINER -> if (current.finished) "exited (${current.exitCode})" else "docker exec -it"
            TerminalSessions.Kind.ROOT -> "root shell (su)"
            TerminalSessions.Kind.DOCKER -> "docker"
            else -> if (current?.finished == true) "exited" else "phone shell · docker CLI on PATH"
        }, onBack = { nav.popBackStack() }) {
            ShadMenu(buildList {
                add(MenuAction("New phone shell", Lucide.SquareTerminal) { scope.launch { sessions.openShell(false) } })
                add(MenuAction("New root shell (su)", Lucide.Shield) { scope.launch { sessions.openShell(true) } })
                if (sessions.dockerCli != null) add(MenuAction("docker run -it alpine", Lucide.Container) { scope.launch { sessions.openDocker("alpine", "run", "-it", "--rm", "alpine:latest", "sh") } })
                current?.let { cur -> add(MenuAction("Close session", Lucide.X, destructive = true) { sessions.close(cur) }) }
                add(MenuAction("Paste", Lucide.ClipboardPaste) {
                    val text = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (!text.isNullOrEmpty()) current?.session?.emulator?.paste(text)
                })
            }, icon = Lucide.EllipsisVertical)
        }
        if (sessions.entries.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sessions.entries.forEach { e -> ShadChip(e.title + if (e.finished) " ✕" else "", e.id == current?.id, { sessions.select(e) }) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            if (current == null) {
                EmptyState("No sessions", "Open a phone shell or a container terminal.", icon = Lucide.SquareTerminal, action = {
                    ShadButton(onClick = { scope.launch { sessions.openShell(false) } }, leadingIcon = Lucide.Plus) { ButtonText("New shell") }
                })
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    factory = { ctx ->
                        TerminalView(ctx, null).apply {
                            // setTextSize must come first: it creates the renderer (default monospace).
                            setTextSize(if (fontPx > 0) fontPx else (settings.terminalFontSize * ctx.resources.displayMetrics.scaledDensity).toInt())
                            isFocusable = true
                            isFocusableInTouchMode = true
                            keepScreenOn = true
                            setTerminalViewClient(object : TerminalViewClient {
                                override fun onScale(scale: Float): Float {
                                    if (scale < 0.9f || scale > 1.1f) {
                                        val next = (fontPx * scale).toInt().coerceIn(12, 80)
                                        fontPx = next; setTextSize(next)
                                        return 1f
                                    }
                                    return scale
                                }
                                override fun onSingleTapUp(e: MotionEvent?) {
                                    requestFocus()
                                    ctx.getSystemService(InputMethodManager::class.java).showSoftInput(this@apply, InputMethodManager.SHOW_IMPLICIT)
                                }
                                override fun shouldBackButtonBeMappedToEscape() = false
                                override fun shouldEnforceCharBasedInput() = true
                                override fun shouldUseCtrlSpaceWorkaround() = false
                                override fun isTerminalViewSelected() = true
                                override fun copyModeChanged(copyMode: Boolean) {}
                                override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
                                    if (keyCode == KeyEvent.KEYCODE_ENTER && session?.isRunning == false) {
                                        sessions.current?.let { sessions.close(it) }; return true
                                    }
                                    return false
                                }
                                override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
                                override fun onLongPress(event: MotionEvent?) = false
                                override fun readControlKey() = ctrl
                                override fun readAltKey() = alt
                                override fun readShiftKey() = false
                                override fun readFnKey() = false
                                override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
                                    if (ctrl || alt) { ctrl = false; alt = false }
                                    return false
                                }
                                override fun onEmulatorSet() {}
                                override fun logError(tag: String?, message: String?) {}
                                override fun logWarn(tag: String?, message: String?) {}
                                override fun logInfo(tag: String?, message: String?) {}
                                override fun logDebug(tag: String?, message: String?) {}
                                override fun logVerbose(tag: String?, message: String?) {}
                                override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
                                override fun logStackTrace(tag: String?, e: Exception?) {}
                            })
                            viewRef = this
                            sessions.attachView(this)
                        }
                    },
                    onRelease = { sessions.attachView(null); viewRef = null },
                )
            }
        }
        // Extra keys, like Termux.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ExtraKey("ESC") { viewRef.key(KeyEvent.KEYCODE_ESCAPE) }
            ExtraKey("TAB") { viewRef.key(KeyEvent.KEYCODE_TAB) }
            ExtraKey("CTRL", active = ctrl) { ctrl = !ctrl }
            ExtraKey("ALT", active = alt) { alt = !alt }
            ExtraKey("↑") { viewRef.key(KeyEvent.KEYCODE_DPAD_UP) }
            ExtraKey("↓") { viewRef.key(KeyEvent.KEYCODE_DPAD_DOWN) }
            ExtraKey("←") { viewRef.key(KeyEvent.KEYCODE_DPAD_LEFT) }
            ExtraKey("→") { viewRef.key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            ExtraKey("HOME") { viewRef.key(KeyEvent.KEYCODE_MOVE_HOME) }
            ExtraKey("END") { viewRef.key(KeyEvent.KEYCODE_MOVE_END) }
            ExtraKey("PGUP") { viewRef.key(KeyEvent.KEYCODE_PAGE_UP) }
            ExtraKey("PGDN") { viewRef.key(KeyEvent.KEYCODE_PAGE_DOWN) }
            listOf("-", "/", "|", "~", "*", "&").forEach { k -> ExtraKey(k) { current?.session?.write(k) } }
        }
        Spacer(Modifier.navigationBarsPadding())
        if (current?.finished == true) {
            Text("Process exited with code ${current.exitCode}. Press Enter to close.", style = MaterialTheme.typography.bodySmall, color = c.mutedForeground, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
}

/** Sends a special key through the emulator (respects cursor/keypad application modes). */
private fun TerminalView?.key(keyCode: Int) {
    val v = this ?: return
    if (v.currentSession?.emulator == null) return
    v.handleKeyCode(keyCode, 0)
}

@Composable
private fun ExtraKey(label: String, active: Boolean = false, onClick: () -> Unit) {
    ShadButton(onClick = onClick, variant = if (active) ButtonVariant.Default else ButtonVariant.Outline, size = ButtonSize.Sm, modifier = Modifier.width(if (label.length <= 2) 44.dp else 60.dp)) {
        ButtonText(label)
    }
}
