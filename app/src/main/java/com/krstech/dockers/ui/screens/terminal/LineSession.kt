package com.krstech.dockers.ui.screens.terminal

import com.krstech.dockers.engine.DockerClient
import com.krstech.dockers.engine.transport.HijackedConnection
import com.krstech.dockers.util.Ansi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * A line-oriented terminal session. Two backends:
 *  - [Exec]: `docker exec -it` via the hijacked HTTP connection (remote echo, real TTY).
 *  - [Local]: a process on the phone (`/system/bin/sh` or `su`) through plain pipes.
 */
abstract class LineSession(protected val scope: CoroutineScope) {
    data class State(val output: String = "", val connected: Boolean = false, val exited: Boolean = false, val error: String? = null)

    protected val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val buffer = StringBuilder()
    protected var readJob: Job? = null
    protected var out: OutputStream? = null

    abstract suspend fun open()
    open suspend fun resize(rows: Int, cols: Int) {}

    fun send(text: String) {
        val o = out ?: return
        scope.launch(Dispatchers.IO) { runCatching { o.write(text.toByteArray()); o.flush() }.onFailure { e -> _state.update { it.copy(error = e.message) } } }
    }

    fun sendLine(line: String) = send(line + "\n")
    fun sendCtrlC() = send("\u0003")
    fun sendCtrlD() = send("\u0004")
    fun sendTab() = send("\t")
    fun sendEsc() = send("\u001B")
    fun sendArrow(dir: Char) = send("\u001B[$dir")

    protected fun pump(input: InputStream) {
        readJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(4096)
            try {
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    append(String(buf, 0, n, Charsets.UTF_8))
                }
            } catch (_: Throwable) { }
            _state.update { it.copy(connected = false, exited = true) }
        }
    }

    protected fun append(raw: String) {
        val clean = Ansi.strip(raw).replace("\r\n", "\n").replace('\r', '\n')
        synchronized(buffer) {
            buffer.append(clean)
            if (buffer.length > 200_000) buffer.delete(0, buffer.length - 150_000)
            _state.update { it.copy(output = buffer.toString()) }
        }
    }

    fun clear() { synchronized(buffer) { buffer.clear(); _state.update { it.copy(output = "") } } }

    open fun close() { readJob?.cancel(); runCatching { out?.close() } }

    class Exec(scope: CoroutineScope, private val client: DockerClient, private val containerId: String, private val cmd: List<String>, private val user: String? = null) : LineSession(scope) {
        private var conn: HijackedConnection? = null
        private var execId: String? = null
        override suspend fun open() {
            try {
                val id = client.execCreate(containerId, cmd, tty = true, user = user, env = listOf("TERM=xterm", "COLUMNS=100", "LINES=40"))
                execId = id
                val c = client.execStart(id, tty = true)
                conn = c; out = c.output
                _state.update { it.copy(connected = true, error = null) }
                pump(c.input)
                runCatching { client.execResize(id, 40, 100) }
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.message ?: "Failed to open exec session", exited = true) }
            }
        }
        override suspend fun resize(rows: Int, cols: Int) { execId?.let { client.execResize(it, rows, cols) } }
        override fun close() { super.close(); runCatching { conn?.close() } }
    }

    class Local(scope: CoroutineScope, private val root: Boolean) : LineSession(scope) {
        private var process: Process? = null
        override suspend fun open() = withContext(Dispatchers.IO) {
            try {
                val pb = if (root) ProcessBuilder("su") else ProcessBuilder("/system/bin/sh", "-i")
                pb.redirectErrorStream(true)
                pb.environment()["TERM"] = "dumb"
                pb.environment()["PS1"] = "$ "
                val p = pb.start()
                process = p; out = p.outputStream
                _state.update { it.copy(connected = true, error = null) }
                append(if (root) "# root shell (su)\n" else "$ /system/bin/sh (no pty: line mode)\n")
                pump(p.inputStream)
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.message ?: "Failed to start shell", exited = true) }
            }
        }
        override fun close() { super.close(); runCatching { process?.destroy() } }
    }

    /** Generic stream-backed session (e.g. the ADB / shell-service `sh -i`). */
    class Stream(scope: CoroutineScope, private val opener: suspend () -> Pair<InputStream, OutputStream>, private val banner: String? = null) : LineSession(scope) {
        private var input: InputStream? = null
        override suspend fun open() {
            try {
                val (i, o) = opener()
                input = i; out = o
                _state.update { it.copy(connected = true, error = null) }
                banner?.let { append(it + "\n") }
                pump(i)
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.message ?: "Failed to open shell", exited = true) }
            }
        }
        override fun close() { super.close(); runCatching { input?.close() } }
    }
}
