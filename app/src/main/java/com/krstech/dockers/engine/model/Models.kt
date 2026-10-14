package com.krstech.dockers.engine.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// ---------------------------------------------------------------------------------------------
// Docker Engine API (v1.4x) models. Every field is optional/defaulted so the client tolerates
// older/newer daemons. Unknown keys are ignored by the Json instance in DockerClient.
// ---------------------------------------------------------------------------------------------

@Serializable
data class Port(
    @SerialName("IP") val ip: String? = null,
    @SerialName("PrivatePort") val privatePort: Int = 0,
    @SerialName("PublicPort") val publicPort: Int? = null,
    @SerialName("Type") val type: String = "tcp",
)

@Serializable
data class MountPoint(
    @SerialName("Type") val type: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Source") val source: String? = null,
    @SerialName("Destination") val destination: String? = null,
    @SerialName("Mode") val mode: String? = null,
    @SerialName("RW") val rw: Boolean = true,
)

@Serializable
data class ContainerSummary(
    @SerialName("Id") val id: String,
    @SerialName("Names") val names: List<String> = emptyList(),
    @SerialName("Image") val image: String = "",
    @SerialName("ImageID") val imageId: String = "",
    @SerialName("Command") val command: String = "",
    @SerialName("Created") val created: Long = 0,
    @SerialName("State") val state: String = "",
    @SerialName("Status") val status: String = "",
    @SerialName("Ports") val ports: List<Port> = emptyList(),
    @SerialName("Labels") val labels: Map<String, String> = emptyMap(),
    @SerialName("Mounts") val mounts: List<MountPoint> = emptyList(),
) {
    val name: String get() = names.firstOrNull()?.removePrefix("/") ?: id.take(12)
    val shortId: String get() = id.take(12)
    val isRunning: Boolean get() = state == "running"
    val isPaused: Boolean get() = state == "paused"
    val composeProject: String? get() = labels["com.docker.compose.project"]
    val composeService: String? get() = labels["com.docker.compose.service"]
}

@Serializable
data class ContainerState(
    @SerialName("Status") val status: String = "",
    @SerialName("Running") val running: Boolean = false,
    @SerialName("Paused") val paused: Boolean = false,
    @SerialName("Restarting") val restarting: Boolean = false,
    @SerialName("OOMKilled") val oomKilled: Boolean = false,
    @SerialName("Dead") val dead: Boolean = false,
    @SerialName("Pid") val pid: Int = 0,
    @SerialName("ExitCode") val exitCode: Int = 0,
    @SerialName("Error") val error: String = "",
    @SerialName("StartedAt") val startedAt: String = "",
    @SerialName("FinishedAt") val finishedAt: String = "",
)

@Serializable
data class RestartPolicy(
    @SerialName("Name") val name: String = "no",
    @SerialName("MaximumRetryCount") val maximumRetryCount: Int = 0,
)

@Serializable
data class HostConfig(
    @SerialName("Binds") val binds: List<String>? = null,
    @SerialName("NetworkMode") val networkMode: String? = null,
    @SerialName("RestartPolicy") val restartPolicy: RestartPolicy? = null,
    @SerialName("Privileged") val privileged: Boolean = false,
    @SerialName("AutoRemove") val autoRemove: Boolean = false,
    @SerialName("PortBindings") val portBindings: JsonObject? = null,
    @SerialName("Memory") val memory: Long = 0,
    @SerialName("NanoCpus") val nanoCpus: Long = 0,
    @SerialName("CapAdd") val capAdd: List<String>? = null,
)

@Serializable
data class ContainerConfig(
    @SerialName("Hostname") val hostname: String? = null,
    @SerialName("User") val user: String? = null,
    @SerialName("Env") val env: List<String>? = null,
    @SerialName("Cmd") val cmd: List<String>? = null,
    @SerialName("Entrypoint") val entrypoint: List<String>? = null,
    @SerialName("Image") val image: String? = null,
    @SerialName("WorkingDir") val workingDir: String? = null,
    @SerialName("Labels") val labels: Map<String, String>? = null,
    @SerialName("Tty") val tty: Boolean = false,
    @SerialName("OpenStdin") val openStdin: Boolean = false,
    @SerialName("ExposedPorts") val exposedPorts: JsonObject? = null,
)

@Serializable
data class EndpointSettings(
    @SerialName("NetworkID") val networkId: String? = null,
    @SerialName("IPAddress") val ipAddress: String? = null,
    @SerialName("Gateway") val gateway: String? = null,
    @SerialName("MacAddress") val macAddress: String? = null,
    @SerialName("Aliases") val aliases: List<String>? = null,
)

@Serializable
data class NetworkSettings(
    @SerialName("IPAddress") val ipAddress: String? = null,
    @SerialName("Networks") val networks: Map<String, EndpointSettings>? = null,
    @SerialName("Ports") val ports: JsonObject? = null,
)

@Serializable
data class ContainerInspect(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Created") val created: String = "",
    @SerialName("Path") val path: String = "",
    @SerialName("Args") val args: List<String> = emptyList(),
    @SerialName("Image") val image: String = "",
    @SerialName("Platform") val platform: String? = null,
    @SerialName("RestartCount") val restartCount: Int = 0,
    @SerialName("Driver") val driver: String? = null,
    @SerialName("State") val state: ContainerState = ContainerState(),
    @SerialName("Config") val config: ContainerConfig = ContainerConfig(),
    @SerialName("HostConfig") val hostConfig: HostConfig = HostConfig(),
    @SerialName("NetworkSettings") val networkSettings: NetworkSettings = NetworkSettings(),
    @SerialName("Mounts") val mounts: List<MountPoint> = emptyList(),
) {
    val displayName: String get() = name.removePrefix("/")
}

@Serializable
data class ContainerCreateResponse(
    @SerialName("Id") val id: String,
    @SerialName("Warnings") val warnings: List<String> = emptyList(),
)

@Serializable
data class ContainerTop(
    @SerialName("Titles") val titles: List<String> = emptyList(),
    @SerialName("Processes") val processes: List<List<String>> = emptyList(),
)

@Serializable
data class WaitResponse(
    @SerialName("StatusCode") val statusCode: Int = 0,
)

@Serializable
data class ImageSummary(
    @SerialName("Id") val id: String,
    @SerialName("ParentId") val parentId: String = "",
    @SerialName("RepoTags") val repoTags: List<String>? = null,
    @SerialName("RepoDigests") val repoDigests: List<String>? = null,
    @SerialName("Created") val created: Long = 0,
    @SerialName("Size") val size: Long = 0,
    @SerialName("SharedSize") val sharedSize: Long = 0,
    @SerialName("Containers") val containers: Int = -1,
    @SerialName("Labels") val labels: Map<String, String>? = null,
) {
    val shortId: String get() = id.removePrefix("sha256:").take(12)
    val primaryTag: String get() = repoTags?.firstOrNull { it != "<none>:<none>" } ?: "<none>"
    val repository: String get() = primaryTag.substringBeforeLast(':', primaryTag)
    val tag: String get() = if (primaryTag.contains(':')) primaryTag.substringAfterLast(':') else "<none>"
    val isDangling: Boolean get() = repoTags.isNullOrEmpty() || repoTags.all { it == "<none>:<none>" }
}

@Serializable
data class ImageInspect(
    @SerialName("Id") val id: String,
    @SerialName("RepoTags") val repoTags: List<String>? = null,
    @SerialName("RepoDigests") val repoDigests: List<String>? = null,
    @SerialName("Created") val created: String? = null,
    @SerialName("Author") val author: String? = null,
    @SerialName("Architecture") val architecture: String? = null,
    @SerialName("Os") val os: String? = null,
    @SerialName("Size") val size: Long = 0,
    @SerialName("Config") val config: ContainerConfig? = null,
)

@Serializable
data class ImageHistoryEntry(
    @SerialName("Id") val id: String = "",
    @SerialName("Created") val created: Long = 0,
    @SerialName("CreatedBy") val createdBy: String = "",
    @SerialName("Size") val size: Long = 0,
    @SerialName("Comment") val comment: String = "",
)

@Serializable
data class ImageSearchResult(
    @SerialName("name") val name: String = "",
    @SerialName("description") val description: String = "",
    @SerialName("star_count") val starCount: Int = 0,
    @SerialName("is_official") val isOfficial: Boolean = false,
    @SerialName("is_automated") val isAutomated: Boolean = false,
)

@Serializable
data class ImageDeleteItem(
    @SerialName("Untagged") val untagged: String? = null,
    @SerialName("Deleted") val deleted: String? = null,
)

@Serializable
data class ProgressDetail(
    @SerialName("current") val current: Long = 0,
    @SerialName("total") val total: Long = 0,
)

@Serializable
data class PullProgress(
    @SerialName("status") val status: String = "",
    @SerialName("id") val id: String? = null,
    @SerialName("progress") val progress: String? = null,
    @SerialName("progressDetail") val progressDetail: ProgressDetail? = null,
    @SerialName("error") val error: String? = null,
    @SerialName("stream") val stream: String? = null,
)

@Serializable
data class Volume(
    @SerialName("Name") val name: String,
    @SerialName("Driver") val driver: String = "local",
    @SerialName("Mountpoint") val mountpoint: String = "",
    @SerialName("CreatedAt") val createdAt: String? = null,
    @SerialName("Labels") val labels: Map<String, String>? = null,
    @SerialName("Scope") val scope: String = "local",
    @SerialName("Options") val options: Map<String, String>? = null,
    @SerialName("UsageData") val usageData: VolumeUsage? = null,
)

@Serializable
data class VolumeUsage(
    @SerialName("Size") val size: Long = -1,
    @SerialName("RefCount") val refCount: Long = -1,
)

@Serializable
data class VolumeListResponse(
    @SerialName("Volumes") val volumes: List<Volume>? = emptyList(),
    @SerialName("Warnings") val warnings: List<String>? = null,
)

@Serializable
data class IpamConfig(
    @SerialName("Subnet") val subnet: String? = null,
    @SerialName("Gateway") val gateway: String? = null,
    @SerialName("IPRange") val ipRange: String? = null,
)

@Serializable
data class Ipam(
    @SerialName("Driver") val driver: String? = null,
    @SerialName("Config") val config: List<IpamConfig>? = null,
)

@Serializable
data class NetworkContainer(
    @SerialName("Name") val name: String = "",
    @SerialName("IPv4Address") val ipv4Address: String = "",
    @SerialName("MacAddress") val macAddress: String = "",
)

@Serializable
data class Network(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Created") val created: String? = null,
    @SerialName("Scope") val scope: String = "local",
    @SerialName("Driver") val driver: String = "",
    @SerialName("EnableIPv6") val enableIpv6: Boolean = false,
    @SerialName("Internal") val internal: Boolean = false,
    @SerialName("Attachable") val attachable: Boolean = false,
    @SerialName("IPAM") val ipam: Ipam? = null,
    @SerialName("Containers") val containers: Map<String, NetworkContainer>? = null,
    @SerialName("Labels") val labels: Map<String, String>? = null,
) {
    val shortId: String get() = id.take(12)
    val isBuiltin: Boolean get() = name in setOf("bridge", "host", "none")
}

@Serializable
data class SystemInfo(
    @SerialName("ID") val id: String = "",
    @SerialName("Containers") val containers: Int = 0,
    @SerialName("ContainersRunning") val containersRunning: Int = 0,
    @SerialName("ContainersPaused") val containersPaused: Int = 0,
    @SerialName("ContainersStopped") val containersStopped: Int = 0,
    @SerialName("Images") val images: Int = 0,
    @SerialName("Driver") val driver: String = "",
    @SerialName("KernelVersion") val kernelVersion: String = "",
    @SerialName("OperatingSystem") val operatingSystem: String = "",
    @SerialName("OSType") val osType: String = "",
    @SerialName("Architecture") val architecture: String = "",
    @SerialName("NCPU") val ncpu: Int = 0,
    @SerialName("MemTotal") val memTotal: Long = 0,
    @SerialName("Name") val name: String = "",
    @SerialName("ServerVersion") val serverVersion: String = "",
    @SerialName("DockerRootDir") val dockerRootDir: String = "",
    @SerialName("CgroupDriver") val cgroupDriver: String = "",
    @SerialName("CgroupVersion") val cgroupVersion: String = "",
    @SerialName("Warnings") val warnings: List<String>? = null,
)

@Serializable
data class VersionInfo(
    @SerialName("Version") val version: String = "",
    @SerialName("ApiVersion") val apiVersion: String = "",
    @SerialName("MinAPIVersion") val minApiVersion: String = "",
    @SerialName("GitCommit") val gitCommit: String = "",
    @SerialName("GoVersion") val goVersion: String = "",
    @SerialName("Os") val os: String = "",
    @SerialName("Arch") val arch: String = "",
    @SerialName("KernelVersion") val kernelVersion: String = "",
    @SerialName("BuildTime") val buildTime: String = "",
)

@Serializable
data class SystemDf(
    @SerialName("LayersSize") val layersSize: Long = 0,
    @SerialName("Images") val images: List<ImageSummary>? = null,
    @SerialName("Containers") val containers: List<ContainerSummary>? = null,
    @SerialName("Volumes") val volumes: List<Volume>? = null,
)

@Serializable
data class EventActor(
    @SerialName("ID") val id: String = "",
    @SerialName("Attributes") val attributes: Map<String, String> = emptyMap(),
)

@Serializable
data class DockerEvent(
    @SerialName("Type") val type: String = "",
    @SerialName("Action") val action: String = "",
    @SerialName("Actor") val actor: EventActor = EventActor(),
    @SerialName("time") val time: Long = 0,
    @SerialName("timeNano") val timeNano: Long = 0,
) {
    val name: String get() = actor.attributes["name"] ?: actor.id.take(12)
}

@Serializable
data class CpuUsage(
    @SerialName("total_usage") val totalUsage: Long = 0,
    @SerialName("percpu_usage") val perCpuUsage: List<Long>? = null,
)

@Serializable
data class CpuStats(
    @SerialName("cpu_usage") val cpuUsage: CpuUsage = CpuUsage(),
    @SerialName("system_cpu_usage") val systemCpuUsage: Long = 0,
    @SerialName("online_cpus") val onlineCpus: Int = 0,
)

@Serializable
data class MemoryStats(
    @SerialName("usage") val usage: Long = 0,
    @SerialName("limit") val limit: Long = 0,
    @SerialName("stats") val stats: Map<String, Long>? = null,
)

@Serializable
data class NetStats(
    @SerialName("rx_bytes") val rxBytes: Long = 0,
    @SerialName("tx_bytes") val txBytes: Long = 0,
)

@Serializable
data class BlkioEntry(
    @SerialName("op") val op: String = "",
    @SerialName("value") val value: Long = 0,
)

@Serializable
data class BlkioStats(
    @SerialName("io_service_bytes_recursive") val ioServiceBytes: List<BlkioEntry>? = null,
)

@Serializable
data class ContainerStats(
    @SerialName("read") val read: String = "",
    @SerialName("cpu_stats") val cpuStats: CpuStats = CpuStats(),
    @SerialName("precpu_stats") val preCpuStats: CpuStats = CpuStats(),
    @SerialName("memory_stats") val memoryStats: MemoryStats = MemoryStats(),
    @SerialName("networks") val networks: Map<String, NetStats>? = null,
    @SerialName("blkio_stats") val blkioStats: BlkioStats? = null,
) {
    /** CPU % the same way `docker stats` computes it. */
    val cpuPercent: Double
        get() {
            val cpuDelta = (cpuStats.cpuUsage.totalUsage - preCpuStats.cpuUsage.totalUsage).toDouble()
            val sysDelta = (cpuStats.systemCpuUsage - preCpuStats.systemCpuUsage).toDouble()
            val cpus = if (cpuStats.onlineCpus > 0) cpuStats.onlineCpus else (cpuStats.cpuUsage.perCpuUsage?.size ?: 1)
            return if (sysDelta > 0 && cpuDelta > 0) (cpuDelta / sysDelta) * cpus * 100.0 else 0.0
        }

    /** Memory usage minus page cache (matches `docker stats`). */
    val memUsage: Long
        get() {
            val cache = memoryStats.stats?.get("inactive_file") ?: memoryStats.stats?.get("cache") ?: 0
            return (memoryStats.usage - cache).coerceAtLeast(0)
        }
    val memLimit: Long get() = memoryStats.limit
    val memPercent: Double get() = if (memLimit > 0) memUsage.toDouble() / memLimit * 100.0 else 0.0
    val netRx: Long get() = networks?.values?.sumOf { it.rxBytes } ?: 0
    val netTx: Long get() = networks?.values?.sumOf { it.txBytes } ?: 0
    val blockRead: Long get() = blkioStats?.ioServiceBytes?.filter { it.op.equals("read", true) }?.sumOf { it.value } ?: 0
    val blockWrite: Long get() = blkioStats?.ioServiceBytes?.filter { it.op.equals("write", true) }?.sumOf { it.value } ?: 0
}

@Serializable
data class ExecCreateResponse(@SerialName("Id") val id: String)

@Serializable
data class ExecInspect(
    @SerialName("ID") val id: String = "",
    @SerialName("Running") val running: Boolean = false,
    @SerialName("ExitCode") val exitCode: Int? = null,
    @SerialName("Pid") val pid: Int = 0,
)

@Serializable
data class PruneResponse(
    @SerialName("ContainersDeleted") val containersDeleted: List<String>? = null,
    @SerialName("ImagesDeleted") val imagesDeleted: List<ImageDeleteItem>? = null,
    @SerialName("VolumesDeleted") val volumesDeleted: List<String>? = null,
    @SerialName("NetworksDeleted") val networksDeleted: List<String>? = null,
    @SerialName("SpaceReclaimed") val spaceReclaimed: Long = 0,
) {
    val deletedCount: Int
        get() = (containersDeleted?.size ?: 0) + (imagesDeleted?.size ?: 0) +
                (volumesDeleted?.size ?: 0) + (networksDeleted?.size ?: 0)
}

@Serializable
data class ErrorResponse(@SerialName("message") val message: String = "")

@Serializable
data class RegistryAuth(
    val username: String = "",
    val password: String = "",
    val serveraddress: String = "https://index.docker.io/v1/",
)

/** Raw JSON passthrough for the inspect viewer. */
typealias RawJson = JsonElement
