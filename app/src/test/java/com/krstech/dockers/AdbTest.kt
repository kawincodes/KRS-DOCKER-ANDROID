package com.krstech.dockers

import com.krstech.dockers.engine.adb.AdbManager
import com.krstech.dockers.engine.adb.CertBuilder
import com.krstech.dockers.shell.ShellClient
import com.krstech.dockers.shell.ShellServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyPairGenerator

class AdbTest {
    @Test
    fun selfSignedCertificateVerifies() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert = CertBuilder.selfSigned(kp, "KRS Dockers")
        cert.verify(kp.public)
        cert.checkValidity()
        assertEquals(3, cert.version)
        assertTrue(cert.subjectX500Principal.name.contains("CN=KRS Dockers"))
        assertEquals("SHA256withRSA", cert.sigAlgName)
    }

    @Test
    fun exitCodeWrapperRoundTripsThroughShell() {
        val wrapped = AdbManager.wrapForExit("echo hello; echo oops >&2; exit 7")
        val p = ProcessBuilder("/bin/sh", "-c", wrapped).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor()
        val (code, text) = AdbManager.parseExit(out)
        assertEquals(7, code)
        assertEquals("hello\noops", text.trim())
    }

    @Test
    fun shellServiceProtocol() {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val token = "secret-token"
        Thread { runCatching { ShellServer.serve(server, token) } }.apply { isDaemon = true }.start()
        val client = ShellClient(server.localPort, token)

        val pong = client.ping()
        assertNotNull(pong); assertTrue(pong!!.contains("version=${ShellServer.VERSION}"))

        val chunks = StringBuilder()
        val r = client.exec("echo one; echo two; exit 3") { chunks.append(it) }
        assertEquals(3, r.exitCode)
        assertEquals("one\ntwo\n", r.output)
        assertEquals(r.output, chunks.toString())

        // Wrong token is rejected.
        try { ShellClient(server.localPort, "nope").exec("id"); fail("expected rejection") } catch (e: IllegalStateException) { assertEquals("unauthorized", e.message) }
        assertEquals(null, ShellClient(server.localPort, "nope").ping())

        // Interactive shell.
        client.openShell().use { s ->
            s.getOutputStream().apply { write("echo interactive-ok\nexit\n".toByteArray()); flush() }
            val text = s.getInputStream().bufferedReader().readText()
            assertTrue(text, text.contains("interactive-ok"))
        }
        server.close()
    }
}
