package com.krstech.dockers.engine.adb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The app's ADB identity: an RSA-2048 key and a self-signed X.509 certificate. Wireless debugging
 * pins this certificate when you pair, so it must stay the same across launches.
 */
class AdbKeys(private val dir: File) {
    class Material(val privateKey: PrivateKey, val certificate: X509Certificate)

    @Synchronized
    fun load(): Material {
        dir.mkdirs()
        val keyFile = File(dir, "adbkey.pk8")
        val certFile = File(dir, "adbkey.crt")
        if (keyFile.exists() && certFile.exists()) {
            runCatching {
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certFile.readBytes())) as X509Certificate
                return Material(key, cert)
            }
        }
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
        val cert = CertBuilder.selfSigned(kp, "KRS Dockers")
        keyFile.writeBytes(kp.private.encoded)
        certFile.writeBytes(cert.encoded)
        return Material(kp.private, cert)
    }

    fun reset() { dir.deleteRecursively() }
}

/** Minimal DER encoder for a self-signed X.509 v3 certificate (SHA256withRSA). */
object CertBuilder {
    private val SHA256_RSA = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)
    private val OID_CN = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)

    fun selfSigned(kp: KeyPair, commonName: String, years: Int = 30, now: Date = Date()): X509Certificate {
        val sigAlg = seq(SHA256_RSA, byteArrayOf(0x05, 0x00))
        val name = seq(set(seq(OID_CN, tlv(0x0C, commonName.toByteArray(Charsets.UTF_8)))))
        val notAfter = Date(now.time + years * 365L * 24 * 3600 * 1000)
        val validity = seq(time(Date(now.time - 24 * 3600 * 1000L)), time(notAfter))
        val serial = BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE)
        val tbs = seq(
            tlv(0xA0, integer(BigInteger.valueOf(2))), // v3
            integer(serial),
            sigAlg,
            name,
            validity,
            name,
            kp.public.encoded, // SubjectPublicKeyInfo
        )
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(kp.private); update(tbs); sign() }
        val der = seq(tbs, sigAlg, tlv(0x03, byteArrayOf(0) + sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    private fun time(d: Date): ByteArray {
        val utc = TimeZone.getTimeZone("UTC")
        val year = java.util.Calendar.getInstance(utc).apply { time = d }.get(java.util.Calendar.YEAR)
        return if (year < 2050) tlv(0x17, SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = utc }.format(d).toByteArray())
        else tlv(0x18, SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US).apply { timeZone = utc }.format(d).toByteArray())
    }

    private fun integer(v: BigInteger) = tlv(0x02, v.toByteArray())
    private fun seq(vararg parts: ByteArray) = tlv(0x30, concat(parts))
    private fun set(vararg parts: ByteArray) = tlv(0x31, concat(parts))
    private fun concat(parts: Array<out ByteArray>) = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = content.size
        when {
            len < 0x80 -> out.write(len)
            len < 0x100 -> { out.write(0x81); out.write(len) }
            len < 0x10000 -> { out.write(0x82); out.write(len shr 8); out.write(len and 0xFF) }
            else -> { out.write(0x83); out.write(len shr 16); out.write((len shr 8) and 0xFF); out.write(len and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }
}
