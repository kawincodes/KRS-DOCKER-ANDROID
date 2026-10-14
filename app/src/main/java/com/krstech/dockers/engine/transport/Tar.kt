package com.krstech.dockers.engine.transport

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Tiny ustar reader/writer: enough for docker static tarballs and PUT /containers/{id}/archive. */
object Tar {

    data class Entry(val name: String, val size: Long, val type: Char, val mode: Int)

    /** Extracts regular files/dirs from a (possibly gzip'd, handled by caller) tar stream into [dest]. */
    fun extract(input: InputStream, dest: File, stripComponents: Int = 0, onFile: ((String) -> Unit)? = null) {
        val header = ByteArray(512)
        while (true) {
            if (!readFully(input, header)) return
            if (header.all { it == 0.toByte() }) return
            val name = cstr(header, 0, 100)
            val prefix = cstr(header, 345, 155)
            val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
            val mode = cstr(header, 100, 8).trim().toIntOrNull(8) ?: 420
            val size = cstr(header, 124, 12).trim().toLongOrNull(8) ?: 0L
            val type = header[156].toInt().toChar()
            val parts = fullName.split('/').filter { it.isNotEmpty() }
            val relParts = parts.drop(stripComponents)
            val target = if (relParts.isEmpty()) null else File(dest, relParts.joinToString("/"))
            // Zip-slip guard
            if (target != null && !target.canonicalPath.startsWith(dest.canonicalPath)) {
                skip(input, size); continue
            }
            when (type) {
                '5' -> { target?.mkdirs(); skip(input, size) }
                '0', '\u0000', '7' -> {
                    if (target == null) { skip(input, size) } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> copy(input, out, size) }
                        if (mode and 0b001_001_001 != 0) target.setExecutable(true, false)
                        onFile?.invoke(target.name)
                        skipPadding(input, size)
                    }
                }
                else -> skip(input, size) // symlinks, pax headers, etc. ignored
            }
        }
    }

    /** Builds an in-memory tar containing the given files (name -> bytes). */
    fun build(files: Map<String, ByteArray>, mode: Int = 420): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, data) in files) writeEntry(out, name, data, mode)
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun writeEntry(out: OutputStream, name: String, data: ByteArray, mode: Int) {
        val h = ByteArray(512)
        putStr(h, 0, 100, name)
        putStr(h, 100, 8, String.format("%07o", mode))
        putStr(h, 108, 8, "0000000")
        putStr(h, 116, 8, "0000000")
        putStr(h, 124, 12, String.format("%011o", data.size))
        putStr(h, 136, 12, String.format("%011o", System.currentTimeMillis() / 1000))
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        h[156] = '0'.code.toByte()
        putStr(h, 257, 6, "ustar")
        h[263] = '0'.code.toByte(); h[264] = '0'.code.toByte()
        var sum = 0
        for (b in h) sum += (b.toInt() and 0xff)
        putStr(h, 148, 7, String.format("%06o", sum) + "\u0000")
        out.write(h)
        out.write(data)
        val pad = (512 - (data.size % 512)) % 512
        if (pad > 0) out.write(ByteArray(pad))
    }

    private fun putStr(h: ByteArray, off: Int, len: Int, s: String) {
        val b = s.toByteArray(Charsets.US_ASCII)
        System.arraycopy(b, 0, h, off, minOf(len, b.size))
    }

    private fun cstr(h: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && h[end] != 0.toByte()) end++
        return String(h, off, end - off, Charsets.US_ASCII)
    }

    private fun copy(input: InputStream, out: OutputStream, size: Long) {
        val buf = ByteArray(65536)
        var remaining = size
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) throw IllegalStateException("Unexpected EOF in tar")
            out.write(buf, 0, n); remaining -= n
        }
    }

    private fun skip(input: InputStream, size: Long) {
        val total = size + ((512 - (size % 512)) % 512)
        var remaining = total
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) return
            remaining -= n
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = ((512 - (size % 512)) % 512)
        if (pad > 0) skipBytes(input, pad)
    }

    private fun skipBytes(input: InputStream, n: Long) {
        var remaining = n
        while (remaining > 0) {
            val r = input.read(ByteArray(remaining.toInt()))
            if (r < 0) return
            remaining -= r
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }
}
