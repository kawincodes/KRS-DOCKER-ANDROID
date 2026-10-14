package com.krstech.dockers.engine.transport

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * Lets OkHttp talk to a Unix domain socket (`unix:///var/run/docker.sock`) by wrapping Android's
 * [LocalSocket] in a [java.net.Socket] facade.
 */
class UnixSocketFactory(private val path: String) : SocketFactory() {
    override fun createSocket(): Socket = UnixSocket(path)
    override fun createSocket(host: String?, port: Int): Socket = createSocket()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = createSocket()
    override fun createSocket(host: InetAddress?, port: Int): Socket = createSocket()
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = createSocket()
}

class UnixSocket(private val path: String) : Socket() {
    private val local = LocalSocket()
    @Volatile private var connected = false
    @Volatile private var closed = false
    private var timeout = 0

    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)
    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        local.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
        connected = true
    }

    override fun getInputStream(): InputStream = local.inputStream
    override fun getOutputStream(): OutputStream = local.outputStream
    override fun isConnected(): Boolean = connected
    override fun isClosed(): Boolean = closed
    override fun isBound(): Boolean = connected
    override fun close() {
        if (closed) return
        closed = true
        runCatching { local.close() }
    }

    override fun setSoTimeout(timeout: Int) { this.timeout = timeout; runCatching { local.soTimeout = timeout } }
    override fun getSoTimeout(): Int = timeout
    override fun shutdownInput() { runCatching { local.shutdownInput() } }
    override fun shutdownOutput() { runCatching { local.shutdownOutput() } }
    override fun isInputShutdown(): Boolean = false
    override fun isOutputShutdown(): Boolean = false
    override fun setTcpNoDelay(on: Boolean) {}
    override fun getTcpNoDelay(): Boolean = true
    override fun setKeepAlive(on: Boolean) {}
    override fun setReceiveBufferSize(size: Int) { runCatching { local.receiveBufferSize = size } }
    override fun setSendBufferSize(size: Int) { runCatching { local.sendBufferSize = size } }
    override fun getInetAddress(): InetAddress? = InetAddress.getLoopbackAddress()
    override fun getLocalAddress(): InetAddress? = InetAddress.getLoopbackAddress()
    override fun getPort(): Int = 0
    override fun getLocalPort(): Int = 0
    override fun getRemoteSocketAddress(): SocketAddress? = InetSocketAddress(InetAddress.getLoopbackAddress(), 0)
    override fun getLocalSocketAddress(): SocketAddress? = InetSocketAddress(InetAddress.getLoopbackAddress(), 0)
    override fun toString(): String = "UnixSocket($path)"
}
