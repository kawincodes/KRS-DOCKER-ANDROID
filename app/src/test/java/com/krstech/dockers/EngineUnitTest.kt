package com.krstech.dockers

import com.krstech.dockers.engine.DockerClient
import com.krstech.dockers.engine.DockerHost
import com.krstech.dockers.engine.model.ContainerStats
import com.krstech.dockers.engine.model.ContainerSummary
import com.krstech.dockers.engine.transport.StdCopy
import com.krstech.dockers.engine.transport.Tar
import com.krstech.dockers.util.Ansi
import com.krstech.dockers.util.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class EngineUnitTest {
    @Test
    fun parsesTcpAndUnixHosts() {
        val tcp = DockerHost.parse("tcp://192.168.1.5:2375") as DockerHost.Tcp
        assertEquals("192.168.1.5", tcp.host); assertEquals(2375, tcp.port)
        val bare = DockerHost.parse("myhost") as DockerHost.Tcp
        assertEquals(2375, bare.port)
        val unix = DockerHost.parse("unix:///var/run/docker.sock") as DockerHost.Unix
        assertEquals("/var/run/docker.sock", unix.path)
    }

    @Test
    fun decodesContainerSummary() {
        val json = """[{"Id":"abc123def456789","Names":["/web"],"Image":"nginx:alpine","State":"running","Status":"Up 2 minutes","Ports":[{"PrivatePort":80,"PublicPort":8080,"Type":"tcp"}],"Labels":{"com.docker.compose.project":"demo"}}]"""
        val list = DockerClient.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ContainerSummary.serializer()), json)
        assertEquals("web", list[0].name); assertTrue(list[0].isRunning); assertEquals("demo", list[0].composeProject); assertEquals(8080, list[0].ports[0].publicPort)
    }

    @Test
    fun computesCpuPercentLikeDockerStats() {
        val json = """{"cpu_stats":{"cpu_usage":{"total_usage":2000},"system_cpu_usage":20000,"online_cpus":4},"precpu_stats":{"cpu_usage":{"total_usage":1000},"system_cpu_usage":10000},"memory_stats":{"usage":1048576,"limit":2097152,"stats":{"inactive_file":0}}}"""
        val s = DockerClient.json.decodeFromString(ContainerStats.serializer(), json)
        assertEquals(40.0, s.cpuPercent, 0.001); assertEquals(50.0, s.memPercent, 0.001)
    }

    @Test
    fun demuxesStdcopyFrames() {
        val payload = "hello".toByteArray()
        val frame = byteArrayOf(1, 0, 0, 0, 0, 0, 0, payload.size.toByte()) + payload
        val chunks = StdCopy.chunks(ByteArrayInputStream(frame + frame), multiplexed = true).toList()
        assertEquals(listOf(1 to "hello", 1 to "hello"), chunks)
    }

    @Test
    fun tarRoundTrip() {
        val tar = Tar.build(mapOf("compose.yaml" to "services: {}".toByteArray()))
        assertEquals(0, tar.size % 512)
        val dir = File(System.getProperty("java.io.tmpdir"), "krs-tar-${System.nanoTime()}").apply { mkdirs() }
        Tar.extract(ByteArrayInputStream(tar), dir)
        assertEquals("services: {}", File(dir, "compose.yaml").readText())
        dir.deleteRecursively()
    }

    @Test
    fun stripsAnsiAndFormatsBytes() {
        assertEquals("ok", Ansi.strip("\u001B[32mok\u001B[0m"))
        assertEquals("1.5 KB", Format.bytes(1536)); assertEquals("100 MB", Format.bytes(100L * 1024 * 1024))
    }
}
