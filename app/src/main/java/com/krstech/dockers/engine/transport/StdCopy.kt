package com.krstech.dockers.engine.transport

import java.io.InputStream

/**
 * Demultiplexer for Docker's stdcopy stream format (used by logs/attach/exec when `Tty=false`).
 * Each frame: 1 byte stream type (0 stdin, 1 stdout, 2 stderr), 3 bytes padding, 4 bytes big-endian size.
 */
object StdCopy {
    data class Frame(val stream: Int, val data: ByteArray)

    /** Reads one frame, or returns null on EOF. */
    fun readFrame(input: InputStream): Frame? {
        val header = ByteArray(8)
        if (!readFully(input, header)) return null
        val size = ((header[4].toInt() and 0xff) shl 24) or
                ((header[5].toInt() and 0xff) shl 16) or
                ((header[6].toInt() and 0xff) shl 8) or
                (header[7].toInt() and 0xff)
        val data = ByteArray(size)
        if (size > 0 && !readFully(input, data)) return null
        return Frame(header[0].toInt(), data)
    }

    /** Streams either multiplexed frames or raw (tty) bytes as text chunks. */
    fun chunks(input: InputStream, multiplexed: Boolean): Sequence<Pair<Int, String>> = sequence {
        if (multiplexed) {
            while (true) {
                val frame = readFrame(input) ?: break
                yield(frame.stream to String(frame.data, Charsets.UTF_8))
            }
        } else {
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) yield(1 to String(buf, 0, n, Charsets.UTF_8))
            }
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
