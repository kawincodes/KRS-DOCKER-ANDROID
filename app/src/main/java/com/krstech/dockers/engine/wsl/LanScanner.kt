package com.krstech.dockers.engine.wsl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Finds Docker daemons on the local network (e.g. Docker Desktop on a Windows PC with
 * "Expose daemon on tcp://localhost:2375" + port-proxy, or `dockerd -H tcp://0.0.0.0:2375` in WSL2).
 */
object LanScanner {
    data class Hit(val ip: String, val port: Int, val tls: Boolean) {
        val url: String get() = "tcp://$ip:$port"
    }

    fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }?.hostAddress
    }.getOrNull()

    /** Scans the /24 of the current IPv4 address for ports 2375 and 2376. */
    suspend fun scan(subnetOverride: String? = null, ports: List<Int> = listOf(2375, 2376), onProgress: (Int) -> Unit = {}): List<Hit> = withContext(Dispatchers.IO) {
        val local = subnetOverride ?: localIpv4() ?: return@withContext emptyList()
        val prefix = local.substringBeforeLast('.')
        val sem = Semaphore(96)
        var done = 0
        coroutineScope {
            (1..254).flatMap { h -> ports.map { p -> "$prefix.$h" to p } }.map { (ip, port) ->
                async {
                    sem.withPermit {
                        val open = runCatching { Socket().use { it.connect(InetSocketAddress(ip, port), 350); true } }.getOrDefault(false)
                        synchronized(sem) { done++; if (done % 32 == 0) onProgress(done * 100 / (254 * ports.size)) }
                        if (open) Hit(ip, port, tls = port == 2376) else null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }
}
