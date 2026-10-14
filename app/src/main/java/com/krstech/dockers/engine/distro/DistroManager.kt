package com.krstech.dockers.engine.distro

import com.krstech.dockers.engine.DockerClient
import com.krstech.dockers.engine.model.ContainerSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * WSL-style "distros": long-lived Linux containers with a persistent home volume that you can
 * open a shell into, export to a tarball (`wsl --export`) and import back (`wsl --import`).
 */
class DistroManager {

    data class Base(val id: String, val name: String, val image: String, val shell: String, val description: String)

    data class Distro(val container: ContainerSummary) {
        val name: String get() = container.labels[LABEL_NAME] ?: container.name.removePrefix(PREFIX)
        val base: String get() = container.labels[LABEL_BASE] ?: container.image
        val isDefault: Boolean get() = container.labels[LABEL_DEFAULT] == "true"
        val running: Boolean get() = container.isRunning
    }

    val bases = listOf(
        Base("ubuntu", "Ubuntu 24.04 LTS", "ubuntu:24.04", "bash", "The default WSL distro. apt, systemd-less."),
        Base("ubuntu22", "Ubuntu 22.04 LTS", "ubuntu:22.04", "bash", "Long-term support release."),
        Base("debian", "Debian 12 (bookworm)", "debian:bookworm", "bash", "Stable and minimal."),
        Base("alpine", "Alpine Linux", "alpine:latest", "sh", "Tiny (~8 MB). Uses apk + busybox."),
        Base("fedora", "Fedora", "fedora:latest", "bash", "Bleeding edge, dnf."),
        Base("arch", "Arch Linux", "archlinux:latest", "bash", "Rolling release, pacman."),
        Base("kali", "Kali Linux", "kalilinux/kali-rolling", "bash", "Security tooling."),
        Base("opensuse", "openSUSE Tumbleweed", "opensuse/tumbleweed", "bash", "zypper."),
    )

    fun fromContainers(containers: List<ContainerSummary>): List<Distro> =
        containers.filter { it.labels.containsKey(LABEL_NAME) }.map(::Distro).sortedBy { it.name.lowercase() }

    /** Creates (and starts) a distro container. Emits progress lines. */
    fun create(client: DockerClient, name: String, image: String, makeDefault: Boolean = false, privileged: Boolean = false): Flow<String> = flow {
        val safe = name.lowercase().replace(Regex("[^a-z0-9_.-]"), "-")
        emit("Pulling $image…")
        var last = ""
        client.pull(image).collect { p -> if (p.status != last) { last = p.status; emit("  ${p.status}${p.id?.let { " ($it)" } ?: ""}") } }
        emit("Creating volume ${volumeName(safe)}…")
        runCatching { client.createVolume(volumeName(safe), labels = mapOf(LABEL_NAME to safe)) }
        emit("Creating container $PREFIX$safe…")
        val spec = buildJsonObject {
            put("Image", image)
            put("Hostname", safe)
            put("Tty", true); put("OpenStdin", true)
            putJsonArray("Cmd") { add("/bin/sh"); add("-c"); add("exec tail -f /dev/null") }
            putJsonArray("Env") { add("TERM=xterm-256color"); add("HOME=/root"); add("KRS_DISTRO=$safe") }
            putJsonObject("Labels") { put(LABEL_NAME, safe); put(LABEL_BASE, image); put(LABEL_DEFAULT, makeDefault.toString()) }
            putJsonObject("HostConfig") {
                putJsonArray("Binds") { add("${volumeName(safe)}:/root") }
                putJsonObject("RestartPolicy") { put("Name", "unless-stopped") }
                put("Privileged", privileged)
                put("NetworkMode", "bridge")
            }
        }
        val created = client.createContainer("$PREFIX$safe", spec)
        client.startContainer(created.id)
        emit("✔ $safe is ready. Open a terminal to start using it.")
    }

    /** Command used to open an interactive shell: prefers bash, falls back to sh. */
    fun shellCommand(): List<String> = listOf("/bin/sh", "-c", "cd ~ 2>/dev/null; if command -v bash >/dev/null 2>&1; then exec bash -l; else exec sh -l; fi")

    fun volumeName(name: String) = "krs-distro-$name"

    companion object {
        const val PREFIX = "krs-distro-"
        const val LABEL_NAME = "com.krstech.dockers.distro"
        const val LABEL_BASE = "com.krstech.dockers.distro.base"
        const val LABEL_DEFAULT = "com.krstech.dockers.distro.default"
    }
}
