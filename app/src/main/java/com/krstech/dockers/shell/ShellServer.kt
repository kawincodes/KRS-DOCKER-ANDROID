package com.krstech.dockers.shell

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Shizuku-style privileged helper. Started once over ADB with
 * `CLASSPATH=<apk> app_process /system/bin --nice-name=krs_shell com.krstech.dockers.shell.ShellServer`,
 * it runs as the **shell user (uid 2000)** until reboot, even after Wireless debugging is turned off.
 *
 * It listens on 127.0.0.1 only and every request must carry the random token the app passed in the
 * environment (KRS_TOKEN). Requests: `ping`, `exec <cmd>` (framed output + exit code), `shell`
 * (raw interactive /system/bin/sh) and `exit`.
 *
 * Frames sent back: one type byte ('o' output, 'x' exit code, 'p' pong, 'e' error) + int length + bytes.
 * This file must not touch Android app APIs: it runs outside any app process.
 */
object ShellServer {
    const val VERSION = 1

    @JvmStatic
    fun main(args: Array<String>) {
        val token = System.getenv("KRS_TOKEN") ?: args.getOrNull(0) ?: error("KRS_TOKEN missing")
        val port = (System.getenv("KRS_PORT") ?: args.getOrNull(1))?.toIntOrNull() ?: error("KRS_PORT missing")
        val apk = System.getenv("CLASSPATH")?.split(':')?.firstOrNull()
        // Exit when the app is updated or uninstalled (its APK path disappears).
        if (apk != null) thread(isDaemon = true, name = "apk-watch") {
            while (true) { Thread.sleep(15_000); if (!File(apk).exists()) exitProcess(0) }
        }
        serve(ServerSocket(port, 16, InetAddress.getByName("127.0.0.1")), token)
    }

    /** Accept loop; public so it can be exercised in JVM unit tests. */
    fun serve(server: ServerSocket, token: String) {
        val expected = sha(token)
        while (true) {
            val s = server.accept()
            thread(isDaemon = true, name = "krs-shell-client") { runCatching { handle(s, expected) }; runCatching { s.close() } }
        }
    }

    private fun handle(s: Socket, expected: ByteArray) {
        s.soTimeout = 0
        val input = DataInputStream(s.getInputStream())
        val out = DataOutputStream(s.getOutputStream())
        if (!MessageDigest.isEqual(sha(input.readUTF()), expected)) { frame(out, 'e', "unauthorized"); return }
        when (val op = input.readUTF()) {
            "ping" -> frame(out, 'p', "uid=${uid()} pid=${pid()} version=$VERSION")
            "exec" -> {
                val cmd = input.readUTF()
                val p = ProcessBuilder(shell(), "-c", cmd).redirectErrorStream(true).start()
                p.outputStream.close()
                val buf = ByteArray(8192)
                p.inputStream.use { inp -> while (true) { val n = inp.read(buf); if (n < 0) break; frame(out, 'o', buf, n) } }
                val code = p.waitFor()
                out.writeByte('x'.code); out.writeInt(4); out.writeInt(code); out.flush()
            }
            "shell" -> {
                frame(out, 'p', "ok")
                val pb = ProcessBuilder(shell(), "-i").redirectErrorStream(true)
                pb.environment()["TERM"] = "dumb"
                pb.environment()["PS1"] = "adb\$ "
                val p = pb.start()
                val a = thread(isDaemon = true) { copy(input, p.outputStream, flush = true) }
                copy(p.inputStream, out, flush = true)
                p.waitFor(); a.interrupt()
            }
            "exit" -> { frame(out, 'p', "bye"); out.flush(); exitProcess(0) }
            else -> frame(out, 'e', "unknown op $op")
        }
    }

    private fun copy(i: InputStream, o: OutputStream, flush: Boolean) {
        val buf = ByteArray(8192)
        runCatching { while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); if (flush) o.flush() } }
        runCatching { o.close() }
    }

    private fun frame(out: DataOutputStream, type: Char, text: String) = frame(out, type, text.toByteArray(), text.toByteArray().size)
    private fun frame(out: DataOutputStream, type: Char, data: ByteArray, len: Int) {
        synchronized(out) { out.writeByte(type.code); out.writeInt(len); out.write(data, 0, len); out.flush() }
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    private fun shell() = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
    private fun uid(): Int = runCatching { android.os.Process.myUid() }.getOrElse { -1 }
    private fun pid(): String = runCatching { File("/proc/self").canonicalFile.name }.getOrDefault("?")
}

/** App-side client for [ShellServer]. */
class ShellClient(private val port: Int, private val token: String, private val host: String = "127.0.0.1") {
    data class Result(val exitCode: Int, val output: String)

    private fun open(op: String, timeoutMs: Int = 3000): Pair<Socket, DataInputStream> {
        val s = Socket()
        s.connect(java.net.InetSocketAddress(host, port), timeoutMs)
        val out = DataOutputStream(s.getOutputStream())
        out.writeUTF(token); out.writeUTF(op); out.flush()
        return s to DataInputStream(s.getInputStream())
    }

    /** Returns "uid=2000 pid=… version=…" when the service is alive, null otherwise. */
    fun ping(): String? = runCatching {
        val (s, i) = open("ping", 1000)
        s.use { readFrame(i).takeIf { it.first == 'p' }?.second?.let { String(it) } }
    }.getOrNull()

    fun exec(cmd: String, onOutput: ((String) -> Unit)? = null): Result {
        val (s, i) = open("exec")
        s.use {
            DataOutputStream(s.getOutputStream()).apply { writeUTF(cmd); flush() }
            val sb = StringBuilder()
            while (true) {
                val (type, data) = readFrame(i)
                when (type) {
                    'o' -> { val t = String(data); sb.append(t); onOutput?.invoke(t) }
                    'x' -> return Result(java.nio.ByteBuffer.wrap(data).int, sb.toString())
                    'e' -> throw IllegalStateException(String(data))
                }
            }
        }
    }

    /** Opens an interactive `sh -i` running as the shell user. Caller owns the socket. */
    fun openShell(): Socket {
        val (s, i) = open("shell")
        val (type, data) = readFrame(i)
        if (type != 'p') { s.close(); throw IllegalStateException(String(data)) }
        return s
    }

    fun shutdown() = runCatching { val (s, i) = open("exit", 1000); s.use { readFrame(i) } }

    private fun readFrame(i: DataInputStream): Pair<Char, ByteArray> {
        val type = i.readUnsignedByte().toChar()
        val len = i.readInt()
        require(len in 0..(16 shl 20)) { "bad frame" }
        val data = ByteArray(len); i.readFully(data)
        return type to data
    }
}
