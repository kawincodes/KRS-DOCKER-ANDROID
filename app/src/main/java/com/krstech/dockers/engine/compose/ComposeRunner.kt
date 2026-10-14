package com.krstech.dockers.engine.compose

import android.content.Context
import com.krstech.dockers.engine.DockerClient
import com.krstech.dockers.engine.DockerException
import com.krstech.dockers.engine.model.ContainerSummary
import com.krstech.dockers.engine.transport.Tar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import java.io.File

/**
 * Docker Compose support without a compose binary on the phone: runs the official `docker:cli`
 * image (which bundles the compose plugin) as a short-lived helper container with the daemon
 * socket mounted, uploads the compose file via the archive API and streams the output back.
 */
class ComposeRunner(private val context: Context) {

    data class Stack(val name: String, val containers: List<ContainerSummary>, val configFiles: String?, val workingDir: String?) {
        val running: Int get() = containers.count { it.isRunning }
        val hasSavedFile: Boolean get() = false
    }

    private val stacksDir: File get() = File(context.filesDir, "stacks").apply { mkdirs() }

    fun savedStacks(): List<String> = stacksDir.listFiles()?.filter { it.extension == "yaml" }?.map { it.nameWithoutExtension }.orEmpty()
    fun savedFile(name: String): String? = File(stacksDir, "$name.yaml").takeIf { it.exists() }?.readText()
    fun save(name: String, yaml: String) = File(stacksDir, "$name.yaml").writeText(yaml)
    fun deleteSaved(name: String) = File(stacksDir, "$name.yaml").delete()

    fun groupStacks(containers: List<ContainerSummary>): List<Stack> =
        containers.filter { it.composeProject != null }
            .groupBy { it.composeProject!! }
            .map { (name, list) ->
                Stack(name, list.sortedBy { it.composeService }, list.firstNotNullOfOrNull { it.labels["com.docker.compose.project.config_files"] }, list.firstNotNullOfOrNull { it.labels["com.docker.compose.project.working_dir"] })
            }.sortedBy { it.name }

    /** Runs `docker compose -p <project> <args>` with the given compose file; emits output lines. */
    fun run(client: DockerClient, hostSocketPath: String, project: String, composeYaml: String, args: List<String>): Flow<String> = flow {
        emit("$ docker compose -p $project ${args.joinToString(" ")}")
        // 1. Make sure the helper image exists.
        val hasImage = runCatching { client.inspectImage(HELPER_IMAGE) }.isSuccess
        if (!hasImage) {
            emit("Pulling helper image $HELPER_IMAGE…")
            var last = ""
            client.pull(HELPER_IMAGE).collect { p -> val s = p.status; if (s != last) { last = s; emit("  $s") } }
        }
        // 2. Create helper container.
        val spec = buildJsonObject {
            put("Image", HELPER_IMAGE)
            putJsonArray("Cmd") { add("compose"); add("-p"); add(project); add("-f"); add("/work/compose.yaml"); args.forEach { add(it) } }
            put("WorkingDir", "/work")
            putJsonArray("Env") { add("DOCKER_HOST=unix:///var/run/docker.sock"); add("COMPOSE_PROJECT_NAME=$project") }
            putJsonObject("Labels") { put(HELPER_LABEL, "compose"); put("com.krstech.dockers.hidden", "true") }
            putJsonObject("HostConfig") {
                putJsonArray("Binds") { add("$hostSocketPath:/var/run/docker.sock") }
                put("AutoRemove", false)
            }
        }
        val created = client.createContainer("krs-compose-${System.currentTimeMillis()}", spec)
        try {
            client.putArchive(created.id, "/work", Tar.build(mapOf("compose.yaml" to composeYaml.toByteArray())))
            client.startContainer(created.id)
            client.logs(created.id, tty = false, follow = true, tail = 10_000).collect { chunk ->
                chunk.lines().filter { it.isNotBlank() }.forEach { emit(it) }
            }
            val code = client.waitContainer(created.id).statusCode
            emit(if (code == 0) "✔ compose ${args.firstOrNull()} finished" else "✖ compose exited with code $code")
            if (code != 0) throw DockerException("compose exited with code $code")
        } finally {
            runCatching { client.removeContainer(created.id, force = true) }
        }
    }

    companion object {
        const val HELPER_IMAGE = "docker:cli"
        const val HELPER_LABEL = "com.krstech.dockers.helper"
        val DEFAULT_TEMPLATE = """
            services:
              web:
                image: nginx:alpine
                ports:
                  - "8080:80"
                restart: unless-stopped
        """.trimIndent()
    }
}
