package com.krstech.dockers.engine

import android.util.Base64
import com.krstech.dockers.engine.model.*
import com.krstech.dockers.engine.transport.HijackedConnection
import com.krstech.dockers.engine.transport.StdCopy
import com.krstech.dockers.engine.transport.UnixSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.InputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Docker Engine REST API client. Covers everything the UI needs: containers, images, volumes,
 * networks, system, events, stats, logs, exec and archive upload.
 */
class DockerClient(
    val host: DockerHost,
    val apiVersion: String = DEFAULT_API_VERSION,
    private val connectTimeoutSec: Long = 10,
) {
    companion object {
        const val DEFAULT_API_VERSION = "1.43"
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
            encodeDefaults = true
        }
        private val JSON_MEDIA = "application/json".toMediaType()
        private val TAR_MEDIA = "application/x-tar".toMediaType()
        private val OCTET_MEDIA = "application/octet-stream".toMediaType()
    }

    private val baseUrl: String = when (host) {
        is DockerHost.Tcp -> host.baseUrl
        is DockerHost.Unix -> "http://docker"
    }

    private val http: OkHttpClient = OkHttpClient.Builder().apply {
        connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
        readTimeout(60, TimeUnit.SECONDS)
        writeTimeout(60, TimeUnit.SECONDS)
        retryOnConnectionFailure(true)
        when (host) {
            is DockerHost.Unix -> {
                socketFactory(UnixSocketFactory(host.path))
                dns { listOf(InetAddress.getByName("127.0.0.1")) }
            }
            is DockerHost.Tcp -> host.tlsSocketFactory()?.let { (sf, tm) ->
                sslSocketFactory(sf, tm)
                if (host.tls?.verifyHostname != true) hostnameVerifier { _, _ -> true }
            }
        }
    }.build()

    /** Long-lived client for streaming endpoints (logs/events/stats/pull). */
    private val streaming: OkHttpClient = http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun close() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        streaming.connectionPool.evictAll()
    }

    // ------------------------------------------------------------------ low level helpers

    private fun url(path: String, query: Map<String, String?> = emptyMap()): String {
        val b = "$baseUrl/v$apiVersion$path".toHttpUrl().newBuilder()
        query.forEach { (k, v) -> if (v != null) b.addQueryParameter(k, v) }
        return b.build().toString()
    }

    private fun Request.Builder.jsonBody(body: String?) = apply {
        if (body != null) post(body.toRequestBody(JSON_MEDIA)) else post("".toRequestBody(null))
    }

    private suspend fun <T> call(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun execute(req: Request, client: OkHttpClient = http): Response {
        val resp = try {
            client.newCall(req).execute()
        } catch (e: java.io.IOException) {
            throw DockerException(e.message ?: "I/O error talking to ${host.displayUrl}", cause = e)
        }
        if (!resp.isSuccessful) {
            val bodyText = runCatching { resp.body?.string() }.getOrNull().orEmpty()
            resp.close()
            val msg = runCatching { json.decodeFromString<ErrorResponse>(bodyText).message }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: bodyText.ifBlank { resp.message }
            throw DockerException("${resp.code}: $msg", resp.code)
        }
        return resp
    }

    private inline fun <reified T> get(path: String, query: Map<String, String?> = emptyMap()): T {
        execute(Request.Builder().url(url(path, query)).get().build()).use { resp ->
            return json.decodeFromString(resp.body!!.string())
        }
    }

    private fun getText(path: String, query: Map<String, String?> = emptyMap()): String =
        execute(Request.Builder().url(url(path, query)).get().build()).use { it.body?.string().orEmpty() }

    private inline fun <reified T> post(path: String, body: String? = null, query: Map<String, String?> = emptyMap(), headers: Map<String, String> = emptyMap()): T {
        val rb = Request.Builder().url(url(path, query)).jsonBody(body)
        headers.forEach { (k, v) -> rb.header(k, v) }
        execute(rb.build()).use { resp ->
            val text = resp.body?.string().orEmpty()
            @Suppress("UNCHECKED_CAST")
            return if (T::class == Unit::class) Unit as T else json.decodeFromString(text)
        }
    }

    private fun postNoContent(path: String, body: String? = null, query: Map<String, String?> = emptyMap()) {
        execute(Request.Builder().url(url(path, query)).jsonBody(body).build()).close()
    }

    private fun delete(path: String, query: Map<String, String?> = emptyMap()): String =
        execute(Request.Builder().url(url(path, query)).delete().build()).use { it.body?.string().orEmpty() }

    private fun streamLines(path: String, query: Map<String, String?>, method: String = "GET", body: String? = null, headers: Map<String, String> = emptyMap()): Flow<String> = flow {
        val rb = Request.Builder().url(url(path, query))
        if (method == "POST") rb.jsonBody(body) else rb.get()
        headers.forEach { (k, v) -> rb.header(k, v) }
        execute(rb.build(), streaming).use { resp ->
            val src = resp.body!!.source()
            while (!src.exhausted()) {
                val line = src.readUtf8Line() ?: break
                if (line.isNotBlank()) emit(line)
            }
        }
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------ system

    suspend fun ping(): Boolean = call {
        execute(Request.Builder().url("$baseUrl/_ping").get().build()).use { it.body?.string()?.trim() == "OK" }
    }

    suspend fun version(): VersionInfo = call { get("/version") }
    suspend fun info(): SystemInfo = call { get("/info") }
    suspend fun df(): SystemDf = call { get("/system/df") }

    fun events(filters: Map<String, List<String>> = emptyMap()): Flow<DockerEvent> = flow {
        val q = if (filters.isEmpty()) emptyMap() else mapOf("filters" to filtersJson(filters))
        streamLines("/events", q).collect { line ->
            runCatching { json.decodeFromString<DockerEvent>(line) }.getOrNull()?.let { emit(it) }
        }
    }

    // ------------------------------------------------------------------ containers

    suspend fun listContainers(all: Boolean = true, filters: Map<String, List<String>> = emptyMap()): List<ContainerSummary> = call {
        get("/containers/json", mapOf("all" to all.toString(), "size" to "false", "filters" to filters.takeIf { it.isNotEmpty() }?.let(::filtersJson)))
    }

    suspend fun inspectContainer(id: String): ContainerInspect = call { get("/containers/$id/json") }
    suspend fun inspectContainerRaw(id: String): JsonElement = call { json.parseToJsonElement(getText("/containers/$id/json")) }
    suspend fun top(id: String): ContainerTop = call { get("/containers/$id/top", mapOf("ps_args" to "-eo pid,user,%cpu,%mem,etime,args")) }

    suspend fun startContainer(id: String) = call { postNoContent("/containers/$id/start") }
    suspend fun stopContainer(id: String, timeoutSec: Int = 10) = call { postNoContent("/containers/$id/stop", query = mapOf("t" to timeoutSec.toString())) }
    suspend fun restartContainer(id: String, timeoutSec: Int = 10) = call { postNoContent("/containers/$id/restart", query = mapOf("t" to timeoutSec.toString())) }
    suspend fun killContainer(id: String, signal: String = "SIGKILL") = call { postNoContent("/containers/$id/kill", query = mapOf("signal" to signal)) }
    suspend fun pauseContainer(id: String) = call { postNoContent("/containers/$id/pause") }
    suspend fun unpauseContainer(id: String) = call { postNoContent("/containers/$id/unpause") }
    suspend fun renameContainer(id: String, name: String) = call { postNoContent("/containers/$id/rename", query = mapOf("name" to name)) }
    suspend fun removeContainer(id: String, force: Boolean = false, volumes: Boolean = false) = call {
        delete("/containers/$id", mapOf("force" to force.toString(), "v" to volumes.toString())); Unit
    }
    suspend fun waitContainer(id: String): WaitResponse = call {
        val rb = Request.Builder().url(url("/containers/$id/wait")).jsonBody(null)
        execute(rb.build(), streaming).use { json.decodeFromString(it.body!!.string()) }
    }
    suspend fun pruneContainers(): PruneResponse = call { post("/containers/prune") }

    suspend fun createContainer(name: String?, spec: JsonObject): ContainerCreateResponse = call {
        post("/containers/create", json.encodeToString(JsonObject.serializer(), spec), mapOf("name" to name?.takeIf { it.isNotBlank() }))
    }

    /** Streams container logs. Pass [tty] from the container config so frames are demuxed correctly. */
    fun logs(id: String, tty: Boolean, follow: Boolean = true, tail: Int = 500, timestamps: Boolean = false): Flow<String> = flow {
        val q = mapOf("stdout" to "1", "stderr" to "1", "follow" to if (follow) "1" else "0", "tail" to tail.toString(), "timestamps" to if (timestamps) "1" else "0")
        execute(Request.Builder().url(url("/containers/$id/logs", q)).get().build(), streaming).use { resp ->
            val stream = resp.body!!.byteStream()
            for ((_, text) in StdCopy.chunks(stream, multiplexed = !tty)) emit(text)
        }
    }.flowOn(Dispatchers.IO)

    fun stats(id: String): Flow<ContainerStats> = flow {
        streamLines("/containers/$id/stats", mapOf("stream" to "1")).collect { line ->
            runCatching { json.decodeFromString<ContainerStats>(line) }.getOrNull()?.let { emit(it) }
        }
    }

    suspend fun statsOnce(id: String): ContainerStats = call { get("/containers/$id/stats", mapOf("stream" to "false", "one-shot" to "true")) }

    /** Uploads a tar archive into the container at [path] (PUT /containers/{id}/archive). */
    suspend fun putArchive(id: String, path: String, tar: ByteArray) = call {
        val req = Request.Builder().url(url("/containers/$id/archive", mapOf("path" to path)))
            .put(tar.toRequestBody(TAR_MEDIA)).build()
        execute(req).close()
    }

    /** Streams `GET /containers/{id}/export` (a tarball of the filesystem) into [sink]. */
    suspend fun exportContainer(id: String, sink: java.io.OutputStream, onProgress: ((Long) -> Unit)? = null) = call {
        execute(Request.Builder().url(url("/containers/$id/export")).get().build(), streaming).use { resp ->
            resp.body!!.byteStream().use { input ->
                val buf = ByteArray(65536); var total = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    sink.write(buf, 0, n); total += n; onProgress?.invoke(total)
                }
                sink.flush()
            }
        }
    }

    // ------------------------------------------------------------------ exec

    suspend fun execCreate(containerId: String, cmd: List<String>, tty: Boolean = true, user: String? = null, workingDir: String? = null, env: List<String> = emptyList()): String = call {
        val body = buildJsonObject {
            put("AttachStdin", true); put("AttachStdout", true); put("AttachStderr", true)
            put("Tty", tty)
            putJsonArray("Cmd") { cmd.forEach { add(it) } }
            if (env.isNotEmpty()) putJsonArray("Env") { env.forEach { add(it) } }
            if (!user.isNullOrBlank()) put("User", user)
            if (!workingDir.isNullOrBlank()) put("WorkingDir", workingDir)
        }
        post<ExecCreateResponse>("/containers/$containerId/exec", body.toString()).id
    }

    /** Starts an exec session and returns the hijacked bidirectional stream. */
    suspend fun execStart(execId: String, tty: Boolean = true): HijackedConnection = call {
        val body = buildJsonObject { put("Detach", false); put("Tty", tty) }.toString()
        HijackedConnection.open(host, apiVersion, "/exec/$execId/start", body)
    }

    suspend fun execResize(execId: String, rows: Int, cols: Int) = call {
        runCatching { postNoContent("/exec/$execId/resize", query = mapOf("h" to rows.toString(), "w" to cols.toString())) }
    }

    suspend fun execInspect(execId: String): ExecInspect = call { get("/exec/$execId/json") }

    /** Runs a non-interactive command and returns (exitCode, combined output). */
    suspend fun execRun(containerId: String, cmd: List<String>, timeoutMs: Long = 60_000): Pair<Int, String> {
        val execId = execCreate(containerId, cmd, tty = false)
        val out = StringBuilder()
        val conn = execStart(execId, tty = false)
        withContext(Dispatchers.IO) {
            conn.use { c ->
                for ((_, text) in StdCopy.chunks(c.input, multiplexed = true)) out.append(text)
            }
        }
        val code = execInspect(execId).exitCode ?: -1
        return code to out.toString()
    }

    // ------------------------------------------------------------------ images

    suspend fun listImages(all: Boolean = false): List<ImageSummary> = call { get("/images/json", mapOf("all" to all.toString())) }
    suspend fun inspectImage(ref: String): ImageInspect = call { get("/images/$ref/json") }
    suspend fun inspectImageRaw(ref: String): JsonElement = call { json.parseToJsonElement(getText("/images/$ref/json")) }
    suspend fun imageHistory(ref: String): List<ImageHistoryEntry> = call { get("/images/$ref/history") }
    suspend fun searchImages(term: String, limit: Int = 25): List<ImageSearchResult> = call { get("/images/search", mapOf("term" to term, "limit" to limit.toString())) }
    suspend fun tagImage(ref: String, repo: String, tag: String) = call { postNoContent("/images/$ref/tag", query = mapOf("repo" to repo, "tag" to tag)) }
    suspend fun removeImage(ref: String, force: Boolean = false, noPrune: Boolean = false): List<ImageDeleteItem> = call {
        json.decodeFromString(delete("/images/$ref", mapOf("force" to force.toString(), "noprune" to noPrune.toString())))
    }
    suspend fun pruneImages(dangling: Boolean = true): PruneResponse = call {
        post("/images/prune", query = mapOf("filters" to filtersJson(mapOf("dangling" to listOf(dangling.toString())))))
    }

    /** Pulls an image, emitting progress lines. [auth] (optional) is sent as X-Registry-Auth. */
    fun pull(reference: String, auth: RegistryAuth? = null, platform: String? = null): Flow<PullProgress> = flow {
        val (image, tag) = splitReference(reference)
        val headers = auth?.let { mapOf("X-Registry-Auth" to encodeAuth(it)) } ?: emptyMap()
        streamLines("/images/create", mapOf("fromImage" to image, "tag" to tag, "platform" to platform), method = "POST", headers = headers).collect { line ->
            val p = runCatching { json.decodeFromString<PullProgress>(line) }.getOrNull() ?: return@collect
            if (p.error != null) throw DockerException(p.error)
            emit(p)
        }
    }

    /** Imports a filesystem tarball as an image (`docker import`). Used for WSL-style distro import. */
    suspend fun importImage(tar: InputStream, repo: String, tag: String = "latest", message: String? = null): String = call {
        val body = object : RequestBody() {
            override fun contentType() = OCTET_MEDIA
            override fun writeTo(sink: BufferedSink) { tar.use { sink.writeAll(it.source()) } }
        }
        val req = Request.Builder().url(url("/images/create", mapOf("fromSrc" to "-", "repo" to repo, "tag" to tag, "message" to message))).post(body).build()
        execute(req, streaming).use { it.body?.string().orEmpty() }
    }

    // ------------------------------------------------------------------ volumes

    suspend fun listVolumes(): List<Volume> = call { get<VolumeListResponse>("/volumes").volumes.orEmpty() }
    suspend fun inspectVolume(name: String): Volume = call { get("/volumes/$name") }
    suspend fun inspectVolumeRaw(name: String): JsonElement = call { json.parseToJsonElement(getText("/volumes/$name")) }
    suspend fun createVolume(name: String, driver: String = "local", labels: Map<String, String> = emptyMap(), options: Map<String, String> = emptyMap()): Volume = call {
        val body = buildJsonObject {
            put("Name", name); put("Driver", driver)
            putJsonObject("Labels") { labels.forEach { (k, v) -> put(k, v) } }
            putJsonObject("DriverOpts") { options.forEach { (k, v) -> put(k, v) } }
        }
        post("/volumes/create", body.toString())
    }
    suspend fun removeVolume(name: String, force: Boolean = false) = call { delete("/volumes/$name", mapOf("force" to force.toString())); Unit }
    suspend fun pruneVolumes(): PruneResponse = call { post("/volumes/prune") }

    // ------------------------------------------------------------------ networks

    suspend fun listNetworks(): List<Network> = call { get("/networks") }
    suspend fun inspectNetwork(id: String): Network = call { get("/networks/$id") }
    suspend fun inspectNetworkRaw(id: String): JsonElement = call { json.parseToJsonElement(getText("/networks/$id")) }
    suspend fun createNetwork(name: String, driver: String = "bridge", internal: Boolean = false, attachable: Boolean = true, subnet: String? = null, gateway: String? = null): String = call {
        val body = buildJsonObject {
            put("Name", name); put("Driver", driver); put("Internal", internal); put("Attachable", attachable)
            put("CheckDuplicate", true)
            if (!subnet.isNullOrBlank()) putJsonObject("IPAM") {
                put("Driver", "default")
                putJsonArray("Config") { addJsonObject { put("Subnet", subnet); if (!gateway.isNullOrBlank()) put("Gateway", gateway) } }
            }
        }
        post<JsonObject>("/networks/create", body.toString())["Id"]?.jsonPrimitive?.content ?: ""
    }
    suspend fun removeNetwork(id: String) = call { delete("/networks/$id"); Unit }
    suspend fun pruneNetworks(): PruneResponse = call { post("/networks/prune") }
    suspend fun connectNetwork(networkId: String, containerId: String) = call {
        postNoContent("/networks/$networkId/connect", buildJsonObject { put("Container", containerId) }.toString())
    }
    suspend fun disconnectNetwork(networkId: String, containerId: String, force: Boolean = false) = call {
        postNoContent("/networks/$networkId/disconnect", buildJsonObject { put("Container", containerId); put("Force", force) }.toString())
    }

    // ------------------------------------------------------------------ helpers

    private fun filtersJson(filters: Map<String, List<String>>): String = buildJsonObject {
        filters.forEach { (k, v) -> putJsonArray(k) { v.forEach { add(it) } } }
    }.toString()

    private fun encodeAuth(auth: RegistryAuth): String =
        Base64.encodeToString(json.encodeToString(RegistryAuth.serializer(), auth).toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)

    private fun splitReference(ref: String): Pair<String, String> {
        // registry.host:5000/ns/name:tag  -> careful: port colon vs tag colon
        val lastSlash = ref.lastIndexOf('/')
        val lastColon = ref.lastIndexOf(':')
        return if (lastColon > lastSlash && !ref.substring(lastColon + 1).contains('/')) {
            ref.substring(0, lastColon) to ref.substring(lastColon + 1)
        } else ref to "latest"
    }
}
