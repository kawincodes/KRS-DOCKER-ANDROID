package com.krstech.dockers.engine

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.krstech.dockers.engine.transport.PemUtils
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** Client-side TLS material for a `tcp://` host secured with Docker's `--tlsverify` setup. */
data class TlsConfig(
    val caPem: String? = null,
    val certPem: String? = null,
    val keyPem: String? = null,
    val verifyHostname: Boolean = false,
) {
    val isEmpty: Boolean get() = caPem.isNullOrBlank() && certPem.isNullOrBlank() && keyPem.isNullOrBlank()
}

/** Where a Docker daemon lives and how to reach it. */
sealed class DockerHost {
    abstract val displayUrl: String

    data class Tcp(val host: String, val port: Int, val tls: TlsConfig? = null) : DockerHost() {
        val scheme: String get() = if (tls != null) "https" else "http"
        override val displayUrl: String get() = "tcp://$host:$port"
        val baseUrl: String get() = "$scheme://$host:$port"
    }

    data class Unix(val path: String) : DockerHost() {
        override val displayUrl: String get() = "unix://$path"
    }

    /** Opens a raw bidirectional stream to the daemon (used for hijacked exec/attach). */
    fun openRaw(connectTimeoutMs: Int = 10_000): RawSocket = when (this) {
        is Tcp -> {
            if (tls != null) {
                val sslCtx = PemUtils.buildSslContext(tls)
                val s = sslCtx.first.socketFactory.createSocket() as javax.net.ssl.SSLSocket
                s.connect(InetSocketAddress(host, port), connectTimeoutMs)
                s.startHandshake()
                RawSocket.Tcp(s)
            } else {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), connectTimeoutMs)
                RawSocket.Tcp(s)
            }
        }
        is Unix -> {
            val ls = LocalSocket()
            ls.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
            RawSocket.Local(ls)
        }
    }

    fun tlsSocketFactory(): Pair<SSLSocketFactory, X509TrustManager>? {
        val tcp = this as? Tcp ?: return null
        val tls = tcp.tls ?: return null
        val (ctx, tm) = PemUtils.buildSslContext(tls)
        return ctx.socketFactory to tm
    }

    companion object {
        fun parse(url: String, tls: TlsConfig? = null): DockerHost {
            val trimmed = url.trim()
            return when {
                trimmed.startsWith("unix://") -> Unix(trimmed.removePrefix("unix://"))
                trimmed.startsWith("tcp://") || trimmed.startsWith("http://") || trimmed.startsWith("https://") -> {
                    val rest = trimmed.substringAfter("://").trimEnd('/')
                    val host = rest.substringBeforeLast(':')
                    val port = rest.substringAfterLast(':', "").toIntOrNull() ?: if (tls != null) 2376 else 2375
                    Tcp(host, port, tls?.takeUnless { it.isEmpty })
                }
                trimmed.startsWith("/") -> Unix(trimmed)
                else -> {
                    val host = trimmed.substringBefore(':')
                    val port = trimmed.substringAfter(':', "").toIntOrNull() ?: if (tls != null) 2376 else 2375
                    Tcp(host, port, tls?.takeUnless { it.isEmpty })
                }
            }
        }
    }
}

/** Minimal stream pair abstraction over TCP / TLS / Unix sockets. */
sealed class RawSocket : Closeable {
    abstract val input: InputStream
    abstract val output: OutputStream

    class Tcp(private val socket: Socket) : RawSocket() {
        override val input: InputStream = socket.getInputStream()
        override val output: OutputStream = socket.getOutputStream()
        override fun close() = runCatching { socket.close() }.let { }
    }

    class Local(private val socket: LocalSocket) : RawSocket() {
        override val input: InputStream = socket.inputStream
        override val output: OutputStream = socket.outputStream
        override fun close() = runCatching { socket.close() }.let { }
    }
}

class DockerException(message: String, val statusCode: Int = -1, cause: Throwable? = null) :
    Exception(message, cause)
