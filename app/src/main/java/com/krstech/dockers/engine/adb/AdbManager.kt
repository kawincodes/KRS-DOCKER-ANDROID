package com.krstech.dockers.engine.adb

import android.content.Context
import android.os.Build
import com.krstech.dockers.shell.ShellClient
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Built-in ADB connector (like Shizuku): pairs with this phone's own *Wireless debugging*, connects
 * to adbd over localhost and gains **shell (uid 2000) privileges without root**. From there it starts
 * [com.krstech.dockers.shell.ShellServer] with `app_process`, which keeps running as the shell user
 * after Wireless debugging is switched off, so tasks keep working until the next reboot.
 */
class AdbManager(private val context: Context) {

    enum class Phase { DISCONNECTED, PAIRING, CONNECTING, CONNECTED, SERVICE_RUNNING, ERROR }

    data class Status(
        val phase: Phase = Phase.DISCONNECTED,
        val message: String? = null,
        val paired: Boolean = false,
        val adbConnected: Boolean = false,
        val service: String? = null,         // "uid=2000 pid=… version=…" when the shell service answers
        val pairingPort: Int? = null,         // discovered via mDNS while the pairing dialog is open
        val wirelessSupported: Boolean = Build.VERSION.SDK_INT >= 30,
    ) {
        val ready: Boolean get() = service != null || adbConnected
        val privileged: Boolean get() = service != null
    }

    data class ExecResult(val exitCode: Int, val output: String) { val ok get() = exitCode == 0 }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("adb", Context.MODE_PRIVATE)
    private val keys = AdbKeys(File(context.filesDir, "adb"))
    private val lock = Mutex()
    private var conn: Connection? = null
    private var pairingMdns: AdbMdns? = null

    private val _status = MutableStateFlow(Status(paired = prefs.getBoolean(KEY_PAIRED, false)))
    val status: StateFlow<Status> = _status.asStateFlow()

    private inner class Connection : AbsAdbConnectionManager() {
        private val material = keys.load()
        init {
            api = Build.VERSION.SDK_INT
            setTimeout(20, TimeUnit.SECONDS)
            hostAddress = "127.0.0.1"
        }
        override fun getPrivateKey() = material.privateKey
        override fun getCertificate() = material.certificate
        override fun getDeviceName() = "KRS Dockers"
    }

    private fun connection(): Connection = conn ?: Connection().also { conn = it }

    /** Checks whether the shell service from an earlier session is still alive. */
    fun refresh() {
        scope.launch {
            val info = serviceClient()?.ping()
            _status.update {
                it.copy(
                    service = info,
                    adbConnected = conn?.isConnected == true,
                    phase = when {
                        info != null -> Phase.SERVICE_RUNNING
                        conn?.isConnected == true -> Phase.CONNECTED
                        it.phase == Phase.PAIRING || it.phase == Phase.CONNECTING -> it.phase
                        else -> Phase.DISCONNECTED
                    },
                )
            }
        }
    }

    // ------------------------------------------------------------------ pairing

    /** Listens for the "Pair device with pairing code" dialog via mDNS and reports its port. */
    fun startPairingDiscovery(onPort: (Int) -> Unit = {}) {
        stopPairingDiscovery()
        _status.update { it.copy(phase = Phase.PAIRING, message = "Open Wireless debugging → Pair device with pairing code", pairingPort = null) }
        pairingMdns = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host: InetAddress?, port: Int ->
            if (port > 0 && (host == null || host.isLoopbackAddress || isLocalAddress(host))) {
                _status.update { it.copy(pairingPort = port, message = "Pairing service found on port $port. Enter the 6-digit code.") }
                onPort(port)
            }
        }.also { it.start() }
    }

    fun stopPairingDiscovery() { runCatching { pairingMdns?.stop() }; pairingMdns = null }

    suspend fun pair(port: Int, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                _status.update { it.copy(phase = Phase.PAIRING, message = "Pairing…") }
                val ok = connection().pair("127.0.0.1", port, code.trim())
                check(ok) { "Pairing was rejected. Check the code and try again." }
                prefs.edit().putBoolean(KEY_PAIRED, true).apply()
                stopPairingDiscovery()
                _status.update { it.copy(paired = true, phase = Phase.DISCONNECTED, message = "Paired with this phone", pairingPort = null) }
            }.onFailure { e -> _status.update { it.copy(phase = Phase.ERROR, message = "Pairing failed: ${e.message ?: e.javaClass.simpleName}") } }
        }
    }

    // ------------------------------------------------------------------ connect

    /**
     * Connects to adbd. With [port] it connects to 127.0.0.1:[port]; otherwise it discovers the
     * Wireless debugging port via mDNS (Android 11+) or falls back to classic `adb tcpip 5555`.
     */
    suspend fun connect(port: Int? = null): Result<Unit> = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                _status.update { it.copy(phase = Phase.CONNECTING, message = "Connecting to adbd…") }
                val c = connection()
                if (c.isConnected) return@runCatching
                val ok = when {
                    port != null -> c.connect("127.0.0.1", port)
                    else -> c.autoConnect(context, 15_000)
                }
                check(ok) { "adbd didn't accept the connection. Is Wireless debugging on?" }
                prefs.edit().putBoolean(KEY_PAIRED, true).apply()
                _status.update { it.copy(phase = Phase.CONNECTED, adbConnected = true, paired = true, message = "Connected to adbd") }
            }.onFailure { e ->
                runCatching { conn?.close() }; conn = null
                val msg = when (e) {
                    is io.github.muntashirakon.adb.AdbPairingRequiredException -> "This phone isn't paired yet. Pair with a code first."
                    is java.net.ConnectException -> "No adbd on that port. Turn on Wireless debugging (or run adb tcpip 5555 once from a PC)."
                    else -> e.message ?: e.javaClass.simpleName
                }
                if (e is io.github.muntashirakon.adb.AdbPairingRequiredException) prefs.edit().putBoolean(KEY_PAIRED, false).apply()
                _status.update { it.copy(phase = Phase.ERROR, adbConnected = false, message = msg, paired = prefs.getBoolean(KEY_PAIRED, false)) }
            }
        }
    }

    fun disconnect() {
        scope.launch { lock.withLock { runCatching { conn?.disconnect(); conn?.close() }; conn = null }; refresh() }
    }

    // ------------------------------------------------------------------ shell service (Shizuku-style)

    private fun serviceClient(): ShellClient? {
        val port = prefs.getInt(KEY_SVC_PORT, 0)
        val token = prefs.getString(KEY_SVC_TOKEN, null)
        return if (port > 0 && token != null) ShellClient(port, token) else null
    }

    /** Starts the shell-user helper through ADB. Requires an ADB connection. */
    suspend fun startService(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            check(conn?.isConnected == true) { "Connect to ADB first" }
            serviceClient()?.shutdown()
            val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            prefs.edit().putInt(KEY_SVC_PORT, port).putString(KEY_SVC_TOKEN, token).apply()
            val apk = context.applicationInfo.sourceDir
            val script = buildString {
                append("for p in $(pidof $NICE_NAME); do kill \$p; done; ")
                append("export CLASSPATH='$apk'; export KRS_TOKEN='$token'; export KRS_PORT=$port; ")
                append("S=; command -v setsid >/dev/null 2>&1 && S=setsid; ")
                append("\$S nohup /system/bin/app_process /system/bin --nice-name=$NICE_NAME com.krstech.dockers.shell.ShellServer >/dev/null 2>&1 & ")
                append("sleep 1; echo started")
            }
            adbExec(script)
            var info: String? = null
            for (i in 0 until 20) { info = ShellClient(port, token).ping(); if (info != null) break; delay(500) }
            checkNotNull(info) { "The shell service didn't start. Check that Wireless debugging is still on." }
            _status.update { it.copy(phase = Phase.SERVICE_RUNNING, service = info, message = "Shell service running ($info)") }
            info
        }.onFailure { e -> _status.update { it.copy(message = "Couldn't start shell service: ${e.message}") } }
    }

    fun stopService() {
        scope.launch {
            serviceClient()?.shutdown()
            prefs.edit().remove(KEY_SVC_PORT).remove(KEY_SVC_TOKEN).apply()
            refresh()
        }
    }

    // ------------------------------------------------------------------ run commands

    /** Runs [command] as the shell user: through the service if it's up, else directly over ADB. */
    suspend fun exec(command: String, onOutput: ((String) -> Unit)? = null): ExecResult = withContext(Dispatchers.IO) {
        serviceClient()?.let { c ->
            runCatching { c.exec(command, onOutput) }.getOrNull()?.let { return@withContext ExecResult(it.exitCode, it.output) }
        }
        if (conn?.isConnected != true) {
            if (prefs.getBoolean(KEY_PAIRED, false)) connect()
            check(conn?.isConnected == true) { "Not connected. Open ADB & shell and connect first." }
        }
        adbExec(command, onOutput)
    }

    private fun adbExec(command: String, onOutput: ((String) -> Unit)? = null): ExecResult {
        val c = conn ?: error("Not connected")
        val stream = c.openStream("shell:${wrapForExit(command)}")
        val sb = StringBuilder()
        stream.openInputStream().use { inp ->
            val buf = ByteArray(8192)
            while (true) {
                val n = runCatching { inp.read(buf) }.getOrDefault(-1)
                if (n < 0) break
                val t = String(buf, 0, n)
                sb.append(t)
                if (!t.contains(EXIT_MARKER)) onOutput?.invoke(t) else onOutput?.invoke(t.substringBefore("\n$EXIT_MARKER").substringBefore(EXIT_MARKER))
            }
        }
        runCatching { stream.close() }
        val (code, output) = parseExit(sb.toString())
        return ExecResult(code, output)
    }

    /** Interactive shell streams (service `sh -i`, or ADB `shell:` stream). */
    suspend fun openInteractive(): Pair<InputStream, OutputStream> = withContext(Dispatchers.IO) {
        serviceClient()?.let { c -> runCatching { c.openShell() }.getOrNull()?.let { s -> return@withContext s.getInputStream() to s.getOutputStream() } }
        if (conn?.isConnected != true) connect()
        val c = conn ?: error("Not connected")
        val stream = c.openStream("shell:")
        stream.openInputStream() to stream.openOutputStream()
    }

    private fun isLocalAddress(a: InetAddress) = runCatching { java.net.NetworkInterface.getByInetAddress(a) != null }.getOrDefault(false)

    companion object {
        private const val KEY_PAIRED = "paired"
        private const val KEY_SVC_PORT = "svc_port"
        private const val KEY_SVC_TOKEN = "svc_token"
        const val NICE_NAME = "krs_shell"
        private const val EXIT_MARKER = "__KRS_EXIT__:"

        /** Wraps a command so its exit status is printed after its output (ADB v1 shell has no exit codes). */
        fun wrapForExit(command: String) = "($command) 2>&1; rc=\$?; printf '\\n$EXIT_MARKER%s\\n' \"\$rc\""

        /** Splits the output of [wrapForExit] into (exit code, output). */
        fun parseExit(text: String): Pair<Int, String> {
            val code = text.substringAfterLast(EXIT_MARKER, "").trim().toIntOrNull() ?: -1
            return code to text.substringBeforeLast("\n$EXIT_MARKER").substringBeforeLast(EXIT_MARKER)
        }
    }
}
