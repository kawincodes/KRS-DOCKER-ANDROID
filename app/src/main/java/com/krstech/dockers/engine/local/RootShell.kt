package com.krstech.dockers.engine.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Thin helper around `su -c`. */
object RootShell {
    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0
        val output: String get() = (stdout + if (stderr.isNotBlank()) "\n$stderr" else "").trim()
    }

    suspend fun isAvailable(): Boolean = run("id", timeoutMs = 8_000)?.stdout?.contains("uid=0") == true

    suspend fun run(command: String, timeoutMs: Long = 30_000): Result? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs) {
            runCatching {
                val p = ProcessBuilder("su", "-c", command).redirectErrorStream(false).start()
                val out = p.inputStream.bufferedReader().readText()
                val err = p.errorStream.bufferedReader().readText()
                val code = p.waitFor()
                Result(code, out, err)
            }.getOrElse { Result(-1, "", it.message ?: "su failed") }
        }
    }

    suspend fun runPlain(command: String, timeoutMs: Long = 30_000): Result = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs) {
            runCatching {
                val p = ProcessBuilder("/system/bin/sh", "-c", command).start()
                val out = p.inputStream.bufferedReader().readText()
                val err = p.errorStream.bufferedReader().readText()
                Result(p.waitFor(), out, err)
            }.getOrElse { Result(-1, "", it.message ?: "sh failed") }
        } ?: Result(-1, "", "timeout")
    }

    /** Starts a long-running root process (not awaited). */
    fun start(command: String, logFile: File? = null): Process {
        val pb = ProcessBuilder("su", "-c", command)
        if (logFile != null) {
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile))
        } else pb.redirectErrorStream(true)
        return pb.start()
    }
}
