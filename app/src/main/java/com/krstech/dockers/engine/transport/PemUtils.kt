package com.krstech.dockers.engine.transport

import android.util.Base64
import com.krstech.dockers.engine.TlsConfig
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** PEM parsing helpers for Docker TLS (`ca.pem`, `cert.pem`, `key.pem`). */
object PemUtils {

    fun parseCertificates(pem: String): List<X509Certificate> {
        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificates(ByteArrayInputStream(pem.toByteArray()))
            .map { it as X509Certificate }
    }

    fun parsePrivateKey(pem: String): PrivateKey {
        val block = Regex("-----BEGIN ([A-Z ]+)-----([\\s\\S]*?)-----END \\1-----").find(pem)
            ?: throw IllegalArgumentException("No PEM private key block found")
        val type = block.groupValues[1]
        val der = Base64.decode(block.groupValues[2].replace(Regex("\\s"), ""), Base64.DEFAULT)
        return when (type) {
            "PRIVATE KEY" -> decodePkcs8(der)
            "RSA PRIVATE KEY" -> decodePkcs8(wrapPkcs1InPkcs8(der))
            "EC PRIVATE KEY" -> throw IllegalArgumentException(
                "EC keys in SEC1 format are not supported; convert with: openssl pkcs8 -topk8 -nocrypt -in key.pem"
            )
            else -> throw IllegalArgumentException("Unsupported key type: $type")
        }
    }

    private fun decodePkcs8(der: ByteArray): PrivateKey {
        val spec = PKCS8EncodedKeySpec(der)
        for (alg in listOf("RSA", "EC")) {
            runCatching { return KeyFactory.getInstance(alg).generatePrivate(spec) }
        }
        throw IllegalArgumentException("Could not decode PKCS#8 private key")
    }

    /** Wraps a PKCS#1 RSAPrivateKey DER blob into a PKCS#8 PrivateKeyInfo structure. */
    private fun wrapPkcs1InPkcs8(pkcs1: ByteArray): ByteArray {
        val version = byteArrayOf(0x02, 0x01, 0x00)
        // AlgorithmIdentifier: SEQUENCE { OID 1.2.840.113549.1.1.1, NULL }
        val algId = byteArrayOf(
            0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d,
            0x01, 0x01, 0x01, 0x05, 0x00
        )
        val octet = der(0x04, pkcs1)
        return der(0x30, version + algId + octet)
    }

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val len = content.size
        val lenBytes = when {
            len < 0x80 -> byteArrayOf(len.toByte())
            len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
            len < 0x10000 -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
            else -> byteArrayOf(0x83.toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte())
        }
        return byteArrayOf(tag.toByte()) + lenBytes + content
    }

    /** Builds an SSLContext trusting `caPem` and (optionally) presenting a client cert. */
    fun buildSslContext(tls: TlsConfig): Pair<SSLContext, X509TrustManager> {
        val trustManager: X509TrustManager = if (!tls.caPem.isNullOrBlank()) {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
            parseCertificates(tls.caPem).forEachIndexed { i, c -> ks.setCertificateEntry("ca$i", c) }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(ks)
            tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        } else {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        }

        val keyManagers = if (!tls.certPem.isNullOrBlank() && !tls.keyPem.isNullOrBlank()) {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
            val chain = parseCertificates(tls.certPem).toTypedArray()
            ks.setKeyEntry("client", parsePrivateKey(tls.keyPem), CharArray(0), chain)
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, CharArray(0))
            kmf.keyManagers
        } else null

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(keyManagers, arrayOf(trustManager), null)
        return ctx to trustManager
    }
}
