package com.krstech.dockers.ui.screens.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.system.Os
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.krstech.dockers.AppContainer
import com.krstech.dockers.engine.DockerHost
import com.krstech.dockers.engine.EngineState
import com.krstech.dockers.engine.profile.EngineProfile
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.net.InetAddress

/**
 * Owns the app's terminal sessions so they survive navigation (like tabs in Termux).
 * Every session runs in a real pseudo-terminal (Termux's PTY JNI), so job control, colors,
 * full-screen programs (vi, top, htop) and `docker run -it` behave like on a desktop.
 */
class TerminalSessions(private val context: Context, private val app: AppContainer) {
    enum class Kind { SHELL, ROOT, CONTAINER, DOCKER }

    class Entry(val id: Int, val title: String, val kind: Kind, val session: TerminalSession) {
        var finished by mutableStateOf(false)
        var exitCode by mutableStateOf(0)
    }

    val entries = mutableStateListOf<Entry>()
    var currentId by mutableStateOf<Int?>(null)
    private var nextId = 1
    private var view: WeakReference<TerminalView>? = null

    private val nativeDir get() = File(context.applicationInfo.nativeLibraryDir)
    val dockerCli: File? get() = File(nativeDir, "libdocker.so").takeIf { it.exists() }
    val home: File get() = File(context.filesDir, "home").apply { mkdirs() }
    private val binDir: File get() = File(context.filesDir, "usr/bin").apply { mkdirs() }

    val current: Entry? get() = entries.firstOrNull { it.id == currentId } ?: entries.lastOrNull()

    fun attachView(v: TerminalView?) { view = v?.let { WeakReference(it) } }

    /** A login shell on the phone, with `docker` on PATH pointing at the connected engine. */
    suspend fun openShell(root: Boolean): Entry {
        val env = environment()
        return withContext(Dispatchers.Main) {
            val args = if (root) arrayOf("sh", "-c", ROOT_WRAPPER) else arrayOf("-sh")
            add(if (root) "root" else "shell", if (root) Kind.ROOT else Kind.SHELL, "/system/bin/sh", args, env)
        }
    }

    /** `docker exec -it <container> <shell>` via the bundled Docker CLI. Null if the CLI isn't bundled. */
    suspend fun openContainer(containerId: String, title: String, user: String? = null): Entry? {
        val cli = dockerCli ?: return null
        val env = environment()
        val args = buildList {
            add("docker"); add("exec"); add("-it")
            add("-e"); add("TERM=xterm-256color")
            if (!user.isNullOrBlank()) { add("-u"); add(user) }
            add(containerId)
            add("/bin/sh"); add("-c"); add(CONTAINER_SHELL)
        }.toTypedArray()
        return withContext(Dispatchers.Main) { add(title, Kind.CONTAINER, cli.absolutePath, args, env) }
    }

    /** Runs an arbitrary docker CLI command in a terminal (e.g. `docker run -it ubuntu`). */
    suspend fun openDocker(title: String, vararg dockerArgs: String): Entry? {
        val cli = dockerCli ?: return null
        val env = environment()
        return withContext(Dispatchers.Main) { add(title, Kind.DOCKER, cli.absolutePath, arrayOf("docker", *dockerArgs), env) }
    }

    fun close(entry: Entry) {
        entry.session.finishIfRunning()
        entries.remove(entry)
        if (currentId == entry.id) currentId = entries.lastOrNull()?.id
    }

    fun select(entry: Entry) { currentId = entry.id }

    private fun add(title: String, kind: Kind, exe: String, args: Array<String>, env: Array<String>): Entry {
        val id = nextId++
        lateinit var entry: Entry
        val client = object : TerminalSessionClient {
            override fun onTextChanged(s: TerminalSession) { if (current?.session === s) view?.get()?.onScreenUpdated() }
            override fun onTitleChanged(s: TerminalSession) {}
            override fun onSessionFinished(s: TerminalSession) {
                entry.finished = true; entry.exitCode = s.exitStatus
                if (current?.session === s) view?.get()?.onScreenUpdated()
            }
            override fun onCopyTextToClipboard(s: TerminalSession, text: String?) {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("terminal", text ?: ""))
            }
            override fun onPasteTextFromClipboard(s: TerminalSession?) {
                val clip = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
                s?.emulator?.paste(clip)
            }
            override fun onBell(s: TerminalSession) {}
            override fun onColorsChanged(s: TerminalSession) {}
            override fun onTerminalCursorStateChange(state: Boolean) {}
            override fun getTerminalCursorStyle(): Int? = null
            override fun logError(tag: String?, message: String?) {}
            override fun logWarn(tag: String?, message: String?) {}
            override fun logInfo(tag: String?, message: String?) {}
            override fun logDebug(tag: String?, message: String?) {}
            override fun logVerbose(tag: String?, message: String?) {}
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
            override fun logStackTrace(tag: String?, e: Exception?) {}
        }
        val session = TerminalSession(exe, home.absolutePath, args, env, 5000, client)
        entry = Entry(id, title, kind, session)
        entries.add(entry)
        currentId = id
        return entry
    }

    /** Builds the process environment: HOME/TMPDIR in app storage, docker CLI wired to the engine. */
    private suspend fun environment(): Array<String> = withContext(Dispatchers.IO) {
        linkCommands()
        writeRc()
        val env = LinkedHashMap(System.getenv())
        env["HOME"] = home.absolutePath
        env["TMPDIR"] = context.cacheDir.absolutePath
        env["PATH"] = "${binDir.absolutePath}:${System.getenv("PATH") ?: "/system/bin:/system/xbin"}"
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["LANG"] = "en_US.UTF-8"
        env["ENV"] = File(home, ".krsrc").absolutePath
        env["DOCKER_CONFIG"] = File(home, ".docker").apply { mkdirs() }.absolutePath
        env["DOCKER_CLI_HINTS"] = "false"
        val state = app.engine.state.value
        if (state is EngineState.Connected) dockerEnv(state.profile, env)
        env.map { (k, v) -> "$k=$v" }.toTypedArray()
    }

    private fun dockerEnv(p: EngineProfile, env: MutableMap<String, String>) {
        when (val h = p.toHost()) {
            is DockerHost.Unix -> env["DOCKER_HOST"] = "unix://${h.path}"
            is DockerHost.Tcp -> {
                // The Docker CLI is a static Linux binary: without /etc/resolv.conf it can't resolve
                // names on Android, so resolve here when hostname verification isn't needed.
                val host = if (h.tls?.verifyHostname == true) h.host
                else runCatching { InetAddress.getByName(h.host).hostAddress }.getOrNull() ?: h.host
                env["DOCKER_HOST"] = "tcp://$host:${h.port}"
                if (h.tls != null) {
                    val dir = File(home, ".docker/certs/${p.id}").apply { mkdirs() }
                    h.tls.caPem?.let { File(dir, "ca.pem").writeText(it) }
                    h.tls.certPem?.let { File(dir, "cert.pem").writeText(it) }
                    h.tls.keyPem?.let { File(dir, "key.pem").writeText(it) }
                    env["DOCKER_TLS_VERIFY"] = "1"
                    env["DOCKER_CERT_PATH"] = dir.absolutePath
                }
            }
        }
        env["KRS_ENGINE"] = p.name
    }

    /** `docker` -> nativeLibraryDir/libdocker.so (re-created each time; the path changes on update). */
    private fun linkCommands() {
        val cli = dockerCli
        val link = File(binDir, "docker")
        runCatching { link.delete() }
        if (cli != null) runCatching { Os.symlink(cli.absolutePath, link.absolutePath) }
    }

    private fun writeRc() {
        File(home, ".krsrc").writeText(
            """
            # KRS Dockers shell profile (mksh). Edit freely; it is regenerated only when missing markers change.
            PS1='krs:${'$'}{PWD##*/}${'$'} '
            alias ll='ls -la'
            alias la='ls -A'
            if [ -n "${'$'}KRS_ENGINE" ] && [ -z "${'$'}KRS_GREETED" ]; then
              export KRS_GREETED=1
              echo "docker -> ${'$'}KRS_ENGINE (${'$'}DOCKER_HOST)"
            fi
            """.trimIndent() + "\n"
        )
    }

    companion object {
        private const val ROOT_WRAPPER =
            "for s in /system/bin/su /system/xbin/su /sbin/su /debug_ramdisk/su /su/bin/su; do [ -x \"\$s\" ] && exec \"\$s\"; done; " +
                "command -v su >/dev/null 2>&1 && exec su; echo 'su not found: this phone is not rooted.'; exit 1"
        private const val CONTAINER_SHELL =
            "cd 2>/dev/null; if command -v bash >/dev/null 2>&1; then exec bash -l; elif command -v ash >/dev/null 2>&1; then exec ash -l; else exec sh -l; fi"
    }
}
