package com.krstech.dockers.engine.profile

import com.krstech.dockers.engine.DockerHost
import com.krstech.dockers.engine.TlsConfig
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class EngineType(val label: String, val description: String) {
    LOCAL("Local engine (root)", "dockerd running on this device. Requires root and a kernel with namespaces, cgroups and overlayfs."),
    REMOTE("Remote host", "Any Docker daemon reachable over tcp:// (optionally with TLS client certificates)."),
    WSL("Windows / WSL2 (Docker Desktop)", "Docker Desktop or dockerd inside a WSL2 distro, exposed on tcp://<pc-ip>:2375 or :2376 with TLS."),
}

@Serializable
data class EngineProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val type: EngineType = EngineType.REMOTE,
    val url: String = "tcp://192.168.1.10:2375",
    val caPem: String? = null,
    val certPem: String? = null,
    val keyPem: String? = null,
    val verifyHostname: Boolean = false,
    val apiVersion: String = "1.43",
    /** Path of the daemon's unix socket *on the docker host*; mounted into helper containers (compose). */
    val hostSocketPath: String = "/var/run/docker.sock",
    val autoConnect: Boolean = true,
) {
    val tls: TlsConfig? get() = TlsConfig(caPem, certPem, keyPem, verifyHostname).takeUnless { it.isEmpty }
    fun toHost(): DockerHost = DockerHost.parse(url, tls)
    val isTls: Boolean get() = tls != null
}
