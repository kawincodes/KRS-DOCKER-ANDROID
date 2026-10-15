package com.krstech.dockers.engine.local

import android.content.Context
import android.content.Intent
import android.os.Build
import com.krstech.dockers.engine.transport.Tar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Manages a dockerd instance running *on this device* (root required). Handles: requirement checks,
 * downloading the static Docker binaries, bootstrapping cgroups and launching/stopping the daemon.
 */
class LocalEngine(private val context: Context) {

    data class Check(val name: String, val ok: Boolean, val detail: String, val required: Boolean = true)

    data class Status(
        val rootAvailable: Boolean? = null,
        val binariesInstalled: Boolean = false,
        val installedVersion: String? = null,
        val running: Boolean = false,
        val pid: Int? = null,
        val checks: List<Check> = emptyList(),
        val busy: Boolean = false,
        val progress: String? = null,
        val lastError: String? = null,
        /** Docker API answered on 127.0.0.1:<port> (null = not checked / not running). */
        val apiOk: Boolean? = null,
        /** False when the kernel lacks features runc needs to create any container. Null = unknown. */
        val containersSupported: Boolean? = null,
        val missingCritical: List<String> = emptyList(),
        val overlaySupported: Boolean? = null,
    )

    data class Options(
        val port: Int = DEFAULT_PORT,
        val storageDriver: String = "overlay2",
        val iptables: Boolean = true,
        val extraArgs: String = "",
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    val binDir: File get() = File(context.filesDir, "bin")
    val dataRoot: File get() = File(context.filesDir, "docker-data")
    val execRoot: File get() = File(context.filesDir, "docker-exec")
    val logFile: File get() = File(context.filesDir, "logs/dockerd.log")
    val pidFile: File get() = File(context.filesDir, "dockerd.pid")
    val bootstrapScript: File get() = File(context.filesDir, "dockerd-bootstrap.sh")
    val socketFile: File get() = File(socketPath(context))
    private val versionFile: File get() = File(binDir, ".version")

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()

    val arch: String = when {
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } -> "aarch64"
        Build.SUPPORTED_ABIS.any { it == "x86_64" } -> "x86_64"
        Build.SUPPORTED_ABIS.any { it == "armeabi-v7a" } -> "armhf"
        else -> "aarch64"
    }

    suspend fun refresh(port: Int = DEFAULT_PORT) {
        val root = RootShell.isAvailable()
        val installed = File(binDir, "dockerd").exists()
        val running = isRunning()
        _status.value = _status.value.copy(
            rootAvailable = root,
            binariesInstalled = installed,
            installedVersion = versionFile.takeIf { it.exists() }?.readText()?.trim(),
            running = running.first,
            pid = running.second,
            apiOk = if (running.first) pingApi(port) else null,
        )
    }

    suspend fun pingApi(port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url("http://127.0.0.1:$port/_ping").build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun runChecks(): List<Check> {
        val checks = mutableListOf<Check>()
        val root = RootShell.isAvailable()
        checks += Check("Root access (su)", root, if (root) "Granted" else "Denied or no su binary. Root (Magisk/KernelSU) is mandatory for a local engine.")
        if (!root) { _status.value = _status.value.copy(rootAvailable = false, checks = checks); return checks }

        val kernel = RootShell.run("uname -r")?.stdout?.trim().orEmpty()
        checks += Check("Kernel", kernel.isNotBlank(), kernel.ifBlank { "unknown" }, required = false)

        val fsList = RootShell.run("cat /proc/filesystems")?.stdout.orEmpty()
        val overlay = fsList.contains("overlay")
        checks += Check("overlayfs", overlay, if (overlay) "Available (overlay2 storage driver)" else "Missing: the vfs storage driver will be used (slow, uses more space).", required = false)
        checks += Check("cgroup filesystem", fsList.contains("cgroup"), if (fsList.contains("cgroup2")) "cgroup v2" else if (fsList.contains("cgroup")) "cgroup v1" else "No cgroup support in kernel.")

        val cfg = RootShell.run("if [ -f /proc/config.gz ]; then gzip -dc /proc/config.gz 2>/dev/null || zcat /proc/config.gz; fi", 20_000)?.stdout.orEmpty()
        var critical: List<String>? = null
        if (cfg.isBlank()) {
            checks += Check("Kernel config", false, "/proc/config.gz unavailable; cannot verify namespaces. Will try anyway.", required = false)
        } else {
            val required = listOf("CONFIG_NAMESPACES", "CONFIG_NET_NS", "CONFIG_PID_NS", "CONFIG_IPC_NS", "CONFIG_UTS_NS", "CONFIG_CGROUPS", "CONFIG_CGROUP_CPUACCT", "CONFIG_CGROUP_DEVICE", "CONFIG_CGROUP_FREEZER", "CONFIG_CGROUP_SCHED", "CONFIG_CPUSETS", "CONFIG_MEMCG", "CONFIG_VETH", "CONFIG_BRIDGE", "CONFIG_NETFILTER_XT_MATCH_ADDRTYPE", "CONFIG_NETFILTER_XT_MATCH_CONNTRACK", "CONFIG_NF_NAT", "CONFIG_POSIX_MQUEUE", "CONFIG_KEYS")
            val missing = required.filterNot { Regex("^$it=[ym]", RegexOption.MULTILINE).containsMatchIn(cfg) }
            // runc creates mount, PID, IPC, UTS and network namespaces for every container and needs
            // a device controller (cgroup v1 "devices" or cgroup v2 + BPF). Without them no container starts.
            val hasDeviceControl = "CONFIG_CGROUP_DEVICE" !in missing || Regex("^CONFIG_CGROUP_BPF=y", RegexOption.MULTILINE).containsMatchIn(cfg)
            critical = missing.filter { it == "CONFIG_NAMESPACES" || it.endsWith("_NS") || it == "CONFIG_CGROUPS" } +
                (if (hasDeviceControl) emptyList() else listOf("CONFIG_CGROUP_DEVICE"))
            checks += Check("Kernel namespaces & netfilter", missing.isEmpty(), if (missing.isEmpty()) "All required options enabled" else "Missing: ${missing.joinToString(", ")}", required = critical.isNotEmpty())
            val optional = listOf("CONFIG_USER_NS", "CONFIG_SECCOMP", "CONFIG_CGROUP_PIDS", "CONFIG_BLK_CGROUP", "CONFIG_OVERLAY_FS", "CONFIG_IP_NF_TARGET_MASQUERADE", "CONFIG_IP_VS")
            val missingOpt = optional.filterNot { Regex("^$it=[ym]", RegexOption.MULTILINE).containsMatchIn(cfg) }
            checks += Check("Optional kernel features", missingOpt.isEmpty(), if (missingOpt.isEmpty()) "All optional features present" else "Missing: ${missingOpt.joinToString(", ")}", required = false)
        }

        val ipt = RootShell.run("command -v iptables || ls /system/bin/iptables")?.ok == true
        checks += Check("iptables", ipt, if (ipt) "Found (container NAT networking)" else "Not found; start with iptables disabled (no outbound NAT).", required = false)

        val exec = RootShell.run("touch ${binDir.absolutePath}/.x && chmod 755 ${binDir.absolutePath}/.x && ${binDir.absolutePath}/.x; echo \$?")
        checks += Check("Executable app storage", true, "Binaries run from ${binDir.absolutePath}", required = false)
        runCatching { File(binDir, ".x").delete() }
        checks += Check("Architecture", true, "${Build.SUPPORTED_ABIS.first()} -> docker static build '$arch'", required = false)

        _status.value = _status.value.copy(
            rootAvailable = root, checks = checks, overlaySupported = overlay,
            containersSupported = critical?.isEmpty(), missingCritical = critical.orEmpty(),
        )
        return checks
    }

    /** Lists available static Docker versions for this arch from download.docker.com. */
    suspend fun availableVersions(): List<String> = withContext(Dispatchers.IO) {
        val html = http.newCall(Request.Builder().url("$STATIC_BASE/$arch/").build()).execute().use { it.body?.string().orEmpty() }
        Regex("docker-(\\d+\\.\\d+\\.\\d+)\\.tgz").findAll(html).map { it.groupValues[1] }.toSet()
            .sortedWith(compareByDescending { v -> v.split('.').map { it.toInt() }.let { (a, b, c) -> a * 1_000_000 + b * 1000 + c } })
    }

    suspend fun install(version: String, onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        _status.value = _status.value.copy(busy = true, progress = "Downloading docker-$version ($arch)…", lastError = null)
        try {
            binDir.mkdirs()
            val url = "$STATIC_BASE/$arch/docker-$version.tgz"
            val tmp = File(context.cacheDir, "docker-$version.tgz")
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("Download failed: HTTP ${resp.code} for $url")
                val total = resp.body!!.contentLength()
                resp.body!!.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16); var done = 0L; var lastPct = -1
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else -1
                            if (pct != lastPct) { lastPct = pct; val msg = "Downloading docker-$version… ${if (pct >= 0) "$pct%" else "${done / 1_048_576} MB"}"; onProgress(msg); _status.value = _status.value.copy(progress = msg) }
                        }
                    }
                }
            }
            onProgress("Extracting…"); _status.value = _status.value.copy(progress = "Extracting…")
            GZIPInputStream(tmp.inputStream().buffered()).use { gz -> Tar.extract(gz, binDir, stripComponents = 1) { onProgress("Extracted $it") } }
            tmp.delete()
            binDir.listFiles()?.forEach { it.setExecutable(true, false) }
            versionFile.writeText(version)
            installBootstrap()
            _status.value = _status.value.copy(busy = false, progress = null, binariesInstalled = true, installedVersion = version)
        } catch (t: Throwable) {
            _status.value = _status.value.copy(busy = false, progress = null, lastError = t.message)
            throw t
        }
    }

    fun uninstallBinaries() {
        binDir.deleteRecursively()
        _status.value = _status.value.copy(binariesInstalled = false, installedVersion = null)
    }

    private fun installBootstrap() {
        context.assets.open("dockerd-bootstrap.sh").use { input -> bootstrapScript.outputStream().use { input.copyTo(it) } }
        bootstrapScript.setExecutable(true, false)
    }

    /** Launches dockerd through the foreground [EngineService]. */
    suspend fun start(options: Options) {
        if (!File(binDir, "dockerd").exists()) throw IllegalStateException("Docker binaries are not installed")
        if (!RootShell.isAvailable()) throw IllegalStateException("Root access denied")
        installBootstrap()
        logFile.parentFile?.mkdirs()
        _status.value = _status.value.copy(busy = true, progress = "Starting dockerd…", lastError = null)
        val intent = Intent(context, EngineService::class.java).apply {
            action = EngineService.ACTION_START
            putExtra(EngineService.EXTRA_COMMAND, buildCommand(options))
            putExtra(EngineService.EXTRA_LOG, logFile.absolutePath)
        }
        context.startForegroundService(intent)
        // Poll until the Docker API answers (a pid alone doesn't mean the daemon initialised).
        var up = false
        var pid: Int? = null
        for (attempt in 0 until 90) {
            kotlinx.coroutines.delay(1000)
            val r = isRunning()
            pid = r.second
            if (r.first && pingApi(options.port)) { up = true; break }
            if (!r.first && attempt > 10) break
        }
        val running = isRunning()
        _status.value = _status.value.copy(
            busy = false, progress = null, running = running.first, pid = running.second ?: pid, apiOk = up,
            lastError = when {
                up -> null
                running.first -> "dockerd is running but its API never answered. Check the daemon log."
                else -> "dockerd exited during startup. See the daemon log."
            },
        )
    }

    suspend fun stop() {
        _status.value = _status.value.copy(busy = true, progress = "Stopping dockerd…")
        val pid = pidFile.takeIf { it.exists() }?.readText()?.trim()
        RootShell.run("if [ -n \"$pid\" ]; then kill $pid; fi; pkill -f ${binDir.absolutePath}/dockerd; pkill -f ${binDir.absolutePath}/containerd; sleep 1; pkill -9 -f ${binDir.absolutePath}/dockerd", 20_000)
        context.startService(Intent(context, EngineService::class.java).apply { action = EngineService.ACTION_STOP })
        _status.value = _status.value.copy(busy = false, progress = null, running = false, pid = null)
    }

    suspend fun isRunning(): Pair<Boolean, Int?> {
        val pid = pidFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
        if (pid != null && File("/proc/$pid").exists()) return true to pid
        val r = RootShell.run("pidof dockerd || pgrep -f ${binDir.absolutePath}/dockerd", 8_000)
        val p = r?.stdout?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()
        return (p != null) to p
    }

    fun readLog(maxLines: Int = 400): String {
        if (!logFile.exists()) return ""
        return runCatching { logFile.readLines().takeLast(maxLines).joinToString("\n") }.getOrDefault("")
    }

    fun clearLog() { runCatching { logFile.writeText("") } }

    private fun buildCommand(o: Options): String {
        val driver = o.storageDriver.ifBlank { "overlay2" }
        return "sh ${bootstrapScript.absolutePath} ${binDir.absolutePath} ${dataRoot.absolutePath} ${execRoot.absolutePath} ${o.port} ${socketFile.absolutePath} ${pidFile.absolutePath} ${logFile.absolutePath} $driver ${if (o.iptables) 1 else 0} ${o.extraArgs}"
    }

    companion object {
        const val DEFAULT_PORT = 2375
        private const val STATIC_BASE = "https://download.docker.com/linux/static/stable"
        fun socketPath(context: Context): String = File(context.filesDir, "docker.sock").absolutePath
    }
}
