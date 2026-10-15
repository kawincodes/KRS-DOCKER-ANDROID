package com.krstech.dockers.engine.vm

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.krstech.dockers.BuildConfig
import com.krstech.dockers.engine.SettingsRepository
import com.krstech.dockers.engine.local.EngineService
import com.krstech.dockers.engine.transport.Tar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Runs Docker on the phone **without root**: boots a small Alpine Linux VM (kernel + initramfs +
 * qcow2 disk with Docker preinstalled) in QEMU's TCG emulator. QEMU ships inside the APK as
 * `libqemu.so`, the only kind of file Android lets an app execute. The Docker API inside the VM is
 * forwarded to `tcp://127.0.0.1:[apiPort]`, and container ports published with `-p` are forwarded
 * to the phone automatically through QMP, like Docker Desktop does.
 */
class VmManager(private val context: Context, private val settings: SettingsRepository) {

    enum class Phase { UNSUPPORTED, NOT_INSTALLED, DOWNLOADING, INSTALLING, STOPPED, STARTING, RUNNING, STOPPING, ERROR }

    data class PortForward(val proto: String, val guestPort: Int, val hostPort: Int, val error: String? = null) {
        val url: String get() = "http://127.0.0.1:$hostPort"
    }

    data class Status(
        val phase: Phase = Phase.NOT_INSTALLED,
        val message: String? = null,
        val progress: Float? = null,
        val manifest: VmManifest? = null,
        val dockerVersion: String? = null,
        val ports: List<PortForward> = emptyList(),
        val startedAt: Long? = null,
        val readyAfterMs: Long? = null,
        val diskBytes: Long = 0,
        val unsupportedReason: String? = null,
    ) {
        val installed: Boolean get() = manifest != null
        val busy: Boolean get() = phase in setOf(Phase.DOWNLOADING, Phase.INSTALLING, Phase.STARTING, Phase.STOPPING)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _console = MutableStateFlow("")
    val console: StateFlow<String> = _console.asStateFlow()
    private val consoleBuf = StringBuilder()

    /** Called once the Docker API inside the VM answers. */
    var onReady: (() -> Unit)? = null
    /** Called when the VM stops (cleanly or not). */
    var onStopped: (() -> Unit)? = null

    private var process: Process? = null
    private var monitorJob: Job? = null
    private var downloadJob: Job? = null
    private var qmp: QmpClient? = null
    private var triedSplitWx = false

    val arch: String? = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "x86_64" -> "x86_64"
        else -> null
    }
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    val qemuBinary = File(nativeDir, "libqemu.so")
    private val vmRoot get() = File(context.filesDir, "vm")
    val vmDir get() = File(vmRoot, arch ?: "unknown")
    private val firmwareDir get() = File(vmRoot, "firmware")
    private val qmpSocket get() = File(vmDir, "qmp.sock")
    private val downloadFile get() = File(context.cacheDir, "krs-vm-$arch.tar.part")
    val diskFile get() = File(vmDir, "disk.qcow2")

    init { refresh() }

    fun imageUrl(custom: String?): String =
        custom?.takeIf { it.isNotBlank() } ?: "${BuildConfig.VM_IMAGE_BASE_URL}/krs-vm-$arch.tar"

    fun refresh() {
        val reason = when {
            arch == null -> "This phone's CPU (${Build.SUPPORTED_ABIS.firstOrNull()}) isn't supported. The VM needs a 64-bit ARM or x86_64 device."
            !qemuBinary.exists() -> "This build of the app doesn't include QEMU (libqemu.so). Install the official release APK for ${Build.SUPPORTED_ABIS.first()}."
            else -> null
        }
        val manifest = readManifest()
        _status.update { s ->
            val phase = when {
                reason != null -> Phase.UNSUPPORTED
                s.phase in setOf(Phase.DOWNLOADING, Phase.INSTALLING, Phase.STARTING, Phase.RUNNING, Phase.STOPPING) -> s.phase
                manifest == null -> Phase.NOT_INSTALLED
                s.phase == Phase.ERROR -> Phase.ERROR
                else -> Phase.STOPPED
            }
            s.copy(phase = phase, manifest = manifest, unsupportedReason = reason, diskBytes = diskUsage())
        }
    }

    private fun readManifest(): VmManifest? {
        val f = File(vmDir, "manifest.json")
        if (!f.exists() || !File(vmDir, "vmlinuz").exists() || !diskFile.exists()) return null
        return runCatching { json.decodeFromString(VmManifest.serializer(), f.readText()) }.getOrNull()
    }

    /** Actual bytes used on flash by the (sparse/growing) disk image. */
    private fun diskUsage(): Long = runCatching {
        android.system.Os.stat(diskFile.absolutePath).let { it.st_blocks * 512 }
    }.getOrDefault(diskFile.length())

    // ------------------------------------------------------------------------------ download

    fun download() {
        if (downloadJob?.isActive == true || arch == null) return
        downloadJob = scope.launch {
            val s = settings.settings.first()
            val url = imageUrl(s.vmImageUrl)
            try {
                _status.update { it.copy(phase = Phase.DOWNLOADING, progress = 0f, message = "Connecting…") }
                val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
                val part = downloadFile
                var have = if (part.exists()) part.length() else 0L
                val req = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
                http.newCall(req).execute().use { resp ->
                    if (resp.code == 416) { have = part.length() } else {
                        if (!resp.isSuccessful) throw IllegalStateException("Download failed: HTTP ${resp.code} for $url")
                        if (resp.code != 206) { have = 0; part.delete() }
                        val body = resp.body!!
                        val total = if (body.contentLength() > 0) have + body.contentLength() else -1L
                        RandomAccessFile(part, "rw").use { raf ->
                            raf.seek(have)
                            val buf = ByteArray(1 shl 16)
                            var done = have
                            var lastEmit = 0L
                            body.byteStream().use { input ->
                                while (true) {
                                    ensureActive()
                                    val n = input.read(buf); if (n < 0) break
                                    raf.write(buf, 0, n); done += n
                                    val now = System.currentTimeMillis()
                                    if (now - lastEmit > 300) {
                                        lastEmit = now
                                        _status.update { it.copy(progress = if (total > 0) done.toFloat() / total else null, message = "Downloading Docker VM… ${mb(done)}${if (total > 0) " of ${mb(total)}" else ""}") }
                                    }
                                }
                            }
                        }
                    }
                }
                // Verify checksum when the publisher provides one.
                _status.update { it.copy(phase = Phase.INSTALLING, progress = null, message = "Verifying download…") }
                val expected = runCatching {
                    http.newCall(Request.Builder().url("$url.sha256").build()).execute().use { r -> if (r.isSuccessful) r.body?.string()?.trim()?.split(Regex("\\s+"))?.firstOrNull() else null }
                }.getOrNull()
                if (expected != null && expected.length == 64) {
                    val actual = sha256(part)
                    if (!actual.equals(expected, ignoreCase = true)) { part.delete(); throw IllegalStateException("Checksum mismatch: the download was corrupted. Please try again.") }
                }
                install(part)
                part.delete()
                _status.update { it.copy(phase = Phase.STOPPED, progress = null, message = "Docker VM installed") }
                refresh()
            } catch (c: kotlinx.coroutines.CancellationException) {
                // Paused by the user: keep the partial file so the next download resumes.
                throw c
            } catch (t: Throwable) {
                _status.update { it.copy(phase = if (readManifest() != null) Phase.STOPPED else Phase.NOT_INSTALLED, progress = null, message = "Download failed: ${t.message}") }
            }
        }
    }

    fun cancelDownload() { downloadJob?.cancel(); _status.update { it.copy(phase = Phase.NOT_INSTALLED, progress = null, message = "Download paused. It will resume where it left off.") } }

    /** Installs a krs-vm tar from a local file (e.g. picked with the file chooser). */
    fun installFrom(input: java.io.InputStream) {
        scope.launch {
            try {
                _status.update { it.copy(phase = Phase.INSTALLING, message = "Copying image…", progress = null) }
                val tmp = downloadFile
                tmp.outputStream().use { out -> input.use { it.copyTo(out, 1 shl 16) } }
                install(tmp); tmp.delete()
                _status.update { it.copy(phase = Phase.STOPPED, message = "Docker VM installed") }
                refresh()
            } catch (t: Throwable) {
                _status.update { it.copy(phase = Phase.NOT_INSTALLED, message = "Install failed: ${t.message}") }
            }
        }
    }

    private fun install(tar: File) {
        _status.update { it.copy(phase = Phase.INSTALLING, message = "Unpacking VM image…") }
        val staging = File(vmRoot, "$arch.new").apply { deleteRecursively(); mkdirs() }
        tar.inputStream().buffered(1 shl 16).use { Tar.extract(it, staging) }
        val m = json.decodeFromString(VmManifest.serializer(), File(staging, "manifest.json").readText())
        require(m.arch == arch) { "Image is for ${m.arch}, this phone needs $arch" }
        require(File(staging, "vmlinuz").exists() && File(staging, "disk.qcow2").exists()) { "Image is incomplete" }
        vmDir.deleteRecursively()
        check(staging.renameTo(vmDir)) { "Could not move VM into place" }
    }

    fun uninstall() {
        if (process != null) return
        vmDir.deleteRecursively(); downloadFile.delete()
        _status.update { Status() }
        refresh()
    }

    // ------------------------------------------------------------------------------ run

    @Synchronized
    fun start() {
        val st = _status.value
        if (process != null || st.busy || st.phase == Phase.RUNNING || st.manifest == null || st.unsupportedReason != null) return
        _status.update { it.copy(phase = Phase.STARTING, message = "Preparing…", startedAt = System.currentTimeMillis()) }
        scope.launch { launch(splitWxOverride = null) }
    }

    private suspend fun launch(splitWxOverride: Boolean?) {
        val manifest = readManifest() ?: run { _status.update { it.copy(phase = Phase.NOT_INSTALLED) }; return }
        val s = settings.settings.first()
        val splitWx = splitWxOverride ?: s.vmSplitWx
        prepareFirmware()
        qmpSocket.delete()
        clearConsole()
        val cmd = buildCommand(manifest, s.vmCpus.takeIf { it > 0 } ?: defaultCpus(), s.vmMemoryMb.takeIf { it > 0 } ?: defaultMemoryMb(), s.vmApiPort, splitWx)
        appendConsole("$ ${cmd.joinToString(" ") { it.substringAfterLast('/') }}\n")
        _status.update { it.copy(phase = Phase.STARTING, message = "Booting Linux…", startedAt = System.currentTimeMillis(), readyAfterMs = null, ports = emptyList(), dockerVersion = null) }
        startKeepAlive()
        val p = try {
            ProcessBuilder(cmd).directory(vmDir).redirectErrorStream(true).apply {
                environment()["LD_LIBRARY_PATH"] = nativeDir.absolutePath
                environment()["TMPDIR"] = context.cacheDir.absolutePath
                environment()["HOME"] = context.filesDir.absolutePath
            }.start()
        } catch (t: Throwable) {
            _status.update { it.copy(phase = Phase.ERROR, message = "Could not start QEMU: ${t.message}") }
            stopKeepAlive(); return
        }
        process = p
        val started = System.currentTimeMillis()
        monitorJob = scope.launch { readConsole(p) }
        scope.launch { waitForDocker(s.vmApiPort, started) }
        val code = withContext(Dispatchers.IO) { p.waitFor() }
        process = null
        qmp?.close(); qmp = null
        val wasReady = _status.value.phase == Phase.RUNNING || _status.value.phase == Phase.STOPPING
        val tail = consoleTail().substringAfter("\n")
        if (!wasReady && !splitWx && !triedSplitWx && looksLikeJitFailure(tail)) {
            // Some kernels/SELinux policies forbid RWX memory: retry with QEMU's split W^X JIT.
            triedSplitWx = true
            appendConsole("\n[KRS] Retrying with split W^X JIT (compatibility mode)…\n")
            settings.update { it.copy(vmSplitWx = true) }
            launch(splitWxOverride = true)
            return
        }
        stopKeepAlive()
        _status.update {
            if (it.phase == Phase.STOPPING || code == 0) it.copy(phase = Phase.STOPPED, message = "VM stopped", ports = emptyList(), startedAt = null, diskBytes = diskUsage())
            else it.copy(phase = Phase.ERROR, message = "QEMU exited with code $code. ${tail.lines().lastOrNull { l -> l.isNotBlank() }?.take(200) ?: ""}", ports = emptyList(), startedAt = null)
        }
        onStopped?.invoke()
    }

    private fun looksLikeJitFailure(text: String): Boolean {
        val t = text.lowercase()
        return listOf("translation buffer", "mmap", "tcg", "permission denied", "cannot allocate").any { it in t } && "kernel" !in t.takeLast(400)
    }

    private fun buildCommand(m: VmManifest, cpus: Int, memMb: Int, apiPort: Int, splitWx: Boolean): List<String> {
        val accel = buildString {
            append("tcg,thread=multi,tb-size=256")
            if (splitWx) append(",split-wx=on")
        }
        val common = listOf(
            qemuBinary.absolutePath,
            "-L", firmwareDir.absolutePath,
            "-smp", cpus.toString(),
            "-m", "${memMb}M",
            "-accel", accel,
            "-kernel", File(vmDir, "vmlinuz").absolutePath,
            "-initrd", File(vmDir, "initramfs").absolutePath,
            "-append", m.cmdline,
            "-drive", "file=${diskFile.absolutePath},if=none,id=hd0,format=qcow2,discard=unmap,detect-zeroes=unmap,cache=writeback",
            "-device", "virtio-blk-pci,drive=hd0",
            "-netdev", "user,id=net0,hostfwd=tcp:127.0.0.1:$apiPort-:2375",
            "-device", "virtio-net-pci,netdev=net0,romfile=",
            "-device", "virtio-rng-pci",
            "-display", "none",
            "-serial", "stdio",
            "-monitor", "none",
            "-parallel", "none",
            "-qmp", "unix:${qmpSocket.absolutePath},server,nowait",
        )
        return when (arch) {
            "aarch64" -> common + listOf("-M", "virt", "-cpu", "cortex-a72")
            else -> common + listOf("-M", "q35", "-cpu", "max", "-vga", "none")
        }
    }

    private fun readConsole(p: Process) {
        val buf = ByteArray(4096)
        val line = StringBuilder()
        p.inputStream.use { input ->
            while (true) {
                val n = runCatching { input.read(buf) }.getOrDefault(-1)
                if (n < 0) break
                val text = String(buf, 0, n, Charsets.UTF_8)
                appendConsole(text)
                for (ch in text) {
                    if (ch == '\n') { handleConsoleLine(line.toString()); line.clear() } else if (ch != '\r') line.append(ch)
                }
            }
        }
    }

    private fun handleConsoleLine(raw: String) {
        val l = raw.substringAfter("KRS-", "").let { if (it.isEmpty()) "" else "KRS-$it" }
        when {
            l.startsWith("KRS-DOCKER-READY") -> markReady(l.removePrefix("KRS-DOCKER-READY").trim())
            l.startsWith("KRS-DOCKER-FAILED") -> _status.update { it.copy(message = "Docker failed to start inside the VM. See the console.") }
            l.startsWith("KRS-PORTS") -> scope.launch { syncPorts(l.removePrefix("KRS-PORTS").trim()) }
        }
    }

    private suspend fun waitForDocker(port: Int, started: Long) {
        while (process != null && _status.value.phase == Phase.STARTING) {
            delay(2000)
            val ok = runCatching { Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", port), 1000); true } }.getOrDefault(false)
            if (ok && pingDocker(port)) { markReady(null); return }
            val secs = (System.currentTimeMillis() - started) / 1000
            _status.update { if (it.phase == Phase.STARTING) it.copy(message = "Booting Linux and starting Docker… ${secs}s") else it }
        }
    }

    private fun pingDocker(port: Int): Boolean = runCatching {
        OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url("http://127.0.0.1:$port/_ping").build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    @Synchronized
    private fun markReady(version: String?) {
        val s = _status.value
        if (s.phase != Phase.STARTING) { if (version != null) _status.update { it.copy(dockerVersion = version) }; return }
        val after = s.startedAt?.let { System.currentTimeMillis() - it }
        _status.update { it.copy(phase = Phase.RUNNING, message = "Docker is running", readyAfterMs = after, dockerVersion = version ?: it.dockerVersion) }
        triedSplitWx = false
        updateKeepAliveText("Docker VM running")
        onReady?.invoke()
    }

    /** Applies `KRS-PORTS tcp:8080 udp:53` announcements as QEMU host forwards. */
    private suspend fun syncPorts(spec: String) {
        val s = settings.settings.first()
        val bind = if (s.vmExposePortsToLan) "" else "127.0.0.1"
        val wanted = parsePortSpec(spec)
        val current = _status.value.ports
        val client = qmpClient() ?: return
        val result = mutableListOf<PortForward>()
        // Remove forwards that are gone.
        for (pf in current) {
            if ((pf.proto to pf.guestPort) !in wanted) runCatching { client.hmp("hostfwd_remove net0 ${pf.proto}:$bind:${pf.hostPort}") }
            else result += pf
        }
        // Add new ones. Android apps can't bind ports below 1024, so those move to 10000+port.
        for ((proto, port) in wanted) {
            if (result.any { it.proto == proto && it.guestPort == port }) continue
            val host = hostPortFor(port)
            val out = runCatching { client.hmp("hostfwd_add net0 $proto:$bind:$host-:$port") }
            val err = out.exceptionOrNull()?.message ?: out.getOrNull()?.takeIf { it.isNotBlank() }
            result += PortForward(proto, port, host, err)
        }
        _status.update { it.copy(ports = result.sortedBy { p -> p.guestPort }) }
    }

    private fun qmpClient(): QmpClient? {
        qmp?.let { return it }
        return runCatching { QmpClient.connect(qmpSocket.absolutePath) }.getOrNull()?.also { qmp = it }
    }

    /** Sends a line to the VM's serial console (an autologin root shell). */
    fun sendConsole(text: String) {
        val p = process ?: return
        scope.launch { runCatching { p.outputStream.write((text + "\n").toByteArray()); p.outputStream.flush() } }
    }

    fun stop() {
        val p = process ?: run { _status.update { if (it.phase == Phase.ERROR) it.copy(phase = Phase.STOPPED) else it }; return }
        _status.update { it.copy(phase = Phase.STOPPING, message = "Shutting down…") }
        scope.launch {
            sendConsole("poweroff")
            repeat(45) { if (process == null) return@launch; delay(1000) }
            runCatching { qmpClient()?.execute("quit") }
            delay(3000)
            if (process != null) p.destroyForcibly()
        }
    }

    fun forceStop() {
        val p = process ?: return
        _status.update { it.copy(phase = Phase.STOPPING, message = "Stopping…") }
        p.destroyForcibly()
    }

    val isRunning: Boolean get() = process != null

    // ------------------------------------------------------------------------------ helpers

    private fun prepareFirmware() {
        firmwareDir.mkdirs()
        val names = runCatching { context.assets.list("qemu")?.toList() }.getOrNull().orEmpty()
        for (n in names) {
            val dst = File(firmwareDir, n)
            runCatching { context.assets.open("qemu/$n").use { i -> dst.outputStream().use { i.copyTo(it) } } }
        }
    }

    fun defaultCpus(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    fun deviceMemoryMb(): Int {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return (mi.totalMem / 1_048_576).toInt()
    }

    fun defaultMemoryMb(): Int = ((deviceMemoryMb() / 4) / 256 * 256).coerceIn(1024, 4096)

    private fun appendConsole(text: String) {
        synchronized(consoleBuf) {
            consoleBuf.append(com.krstech.dockers.util.Ansi.strip(text).replace("\r", ""))
            if (consoleBuf.length > 120_000) consoleBuf.delete(0, consoleBuf.length - 90_000)
            _console.value = consoleBuf.toString()
        }
    }

    private fun clearConsole() { synchronized(consoleBuf) { consoleBuf.clear(); _console.value = "" } }
    private fun consoleTail(): String = synchronized(consoleBuf) { consoleBuf.takeLast(2000).toString() }

    private fun startKeepAlive() {
        runCatching {
            context.startForegroundService(Intent(context, EngineService::class.java).setAction(EngineService.ACTION_KEEPALIVE).putExtra(EngineService.EXTRA_TEXT, "Starting Docker VM…"))
        }
    }
    private fun updateKeepAliveText(text: String) {
        runCatching { context.startService(Intent(context, EngineService::class.java).setAction(EngineService.ACTION_KEEPALIVE).putExtra(EngineService.EXTRA_TEXT, text)) }
    }
    private fun stopKeepAlive() {
        runCatching { context.startService(Intent(context, EngineService::class.java).setAction(EngineService.ACTION_RELEASE_VM)) }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun mb(b: Long) = "%.0f MB".format(b / 1_048_576.0)

    companion object {
        const val DEFAULT_API_PORT = 23750

        /** Parses the VM's `KRS-PORTS tcp:8080 udp:53` announcement (without the prefix). */
        fun parsePortSpec(spec: String): Set<Pair<String, Int>> =
            spec.split(' ', '\t').filter { it.contains(':') }.mapNotNull {
                val (proto, port) = it.split(':', limit = 2)
                val p = port.toIntOrNull() ?: return@mapNotNull null
                if (proto !in setOf("tcp", "udp") || p !in 1..65535 || p == 2375) null else proto to p
            }.toSet()

        /** Android apps can't bind ports below 1024, so those are moved to 10000 + port. */
        fun hostPortFor(guestPort: Int): Int = if (guestPort < 1024) guestPort + 10000 else guestPort
    }
}
