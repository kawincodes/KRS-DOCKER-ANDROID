package com.krstech.dockers.engine.transport

import com.krstech.dockers.engine.DockerException
import com.krstech.dockers.engine.DockerHost
import com.krstech.dockers.engine.RawSocket
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * Hand-rolled HTTP/1.1 request that upgrades the connection to a raw TCP stream, as used by
 * `POST /exec/{id}/start` and `POST /containers/{id}/attach`.
 */
class HijackedConnection private constructor(
    private val socket: RawSocket,
    val input: InputStream,
    val output: OutputStream,
    val statusCode: Int,
) : Closeable {

    override fun close() = socket.close()

    companion object {
        fun open(host: DockerHost, apiVersion: String, path: String, jsonBody: String): HijackedConnection {
            val socket = host.openRaw()
            try {
                val hostHeader = when (host) {
                    is DockerHost.Tcp -> "${host.host}:${host.port}"
                    is DockerHost.Unix -> "docker"
                }
                val body = jsonBody.toByteArray()
                val req = buildString {
                    append("POST /v$apiVersion$path HTTP/1.1\r\n")
                    append("Host: $hostHeader\r\n")
                    append("User-Agent: KRS-Dockers\r\n")
                    append("Content-Type: application/json\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Upgrade: tcp\r\n")
                    append("Content-Length: ${body.size}\r\n")
                    append("\r\n")
                }
                socket.output.write(req.toByteArray())
                socket.output.write(body)
                socket.output.flush()

                // Read status line + headers (until blank line).
                val headerBytes = StringBuilder()
                var prev = 0
                var consecutive = 0
                while (true) {
                    val b = socket.input.read()
                    if (b < 0) throw DockerException("Connection closed before upgrade response")
                    headerBytes.append(b.toChar())
                    if (b == '\n'.code && prev == '\r'.code) consecutive++ else if (b != '\r'.code) consecutive = 0
                    if (headerBytes.endsWith("\r\n\r\n")) break
                    prev = b
                    if (headerBytes.length > 65536) throw DockerException("Upgrade response headers too large")
                }
                val headers = headerBytes.toString()
                val statusLine = headers.lineSequence().first()
                val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
                if (code != 101 && code != 200) {
                    // Try to read an error body (Content-Length or chunked is uncommon here; best effort).
                    val lenHeader = Regex("(?i)content-length:\\s*(\\d+)").find(headers)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val errBody = if (lenHeader > 0) {
                        val buf = ByteArray(lenHeader)
                        var off = 0
                        while (off < lenHeader) {
                            val n = socket.input.read(buf, off, lenHeader - off); if (n < 0) break; off += n
                        }
                        String(buf, 0, off)
                    } else ""
                    socket.close()
                    throw DockerException("Upgrade failed: $statusLine ${errBody.trim()}", code)
                }
                return HijackedConnection(socket, socket.input, socket.output, code)
            } catch (t: Throwable) {
                runCatching { socket.close() }
                throw t
            }
        }
    }
}
