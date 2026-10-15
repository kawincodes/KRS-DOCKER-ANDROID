package com.krstech.dockers.engine.vm

import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader

/**
 * Minimal QEMU Machine Protocol client over the unix socket QEMU creates with
 * `-qmp unix:<path>,server,nowait`. Used for live port forwarding and shutdown.
 */
class QmpClient private constructor(private val socket: LocalSocket) : Closeable {
    private val reader = BufferedReader(InputStreamReader(socket.inputStream))
    private val out = socket.outputStream
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun execute(command: String, arguments: JsonObject? = null): JsonObject {
        val req = buildJsonObject {
            put("execute", command)
            if (arguments != null) put("arguments", arguments)
        }
        out.write((req.toString() + "\n").toByteArray())
        out.flush()
        while (true) {
            val line = reader.readLine() ?: throw IllegalStateException("QMP connection closed")
            val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            if (obj.containsKey("event")) continue // async events are not replies
            if (obj.containsKey("error")) {
                val desc = obj["error"]?.jsonObject?.get("desc")?.jsonPrimitive?.contentOrNull
                throw IllegalStateException(desc ?: obj.toString())
            }
            return obj
        }
    }

    /** Runs a human-monitor (HMP) command such as `hostfwd_add`; returns its text output. */
    fun hmp(commandLine: String): String {
        val r = execute("human-monitor-command", buildJsonObject { put("command-line", commandLine) })
        return r["return"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
    }

    override fun close() { runCatching { socket.close() } }

    companion object {
        fun connect(path: String): QmpClient {
            val s = LocalSocket()
            s.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
            s.soTimeout = 15_000
            val c = QmpClient(s)
            c.reader.readLine() ?: throw IllegalStateException("No QMP greeting")
            c.execute("qmp_capabilities")
            return c
        }

    }
}
