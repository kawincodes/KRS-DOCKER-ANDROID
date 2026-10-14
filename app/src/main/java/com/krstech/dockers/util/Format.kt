package com.krstech.dockers.util

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {
    fun bytes(b: Long): String {
        if (b < 0) return "–"
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b.toDouble(); var i = -1
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    fun percent(p: Double): String = String.format(Locale.US, "%.1f%%", p)

    fun relative(epochSeconds: Long): String {
        if (epochSeconds <= 0) return "–"
        val diff = (System.currentTimeMillis() / 1000) - epochSeconds
        return when {
            diff < 60 -> "just now"
            diff < 3600 -> "${diff / 60} min ago"
            diff < 86400 -> "${diff / 3600} h ago"
            diff < 86400 * 30 -> "${diff / 86400} d ago"
            diff < 86400 * 365 -> "${diff / (86400 * 30)} mo ago"
            else -> "${diff / (86400 * 365)} y ago"
        }
    }

    fun relativeIso(iso: String?): String {
        if (iso.isNullOrBlank() || iso.startsWith("0001")) return "–"
        return runCatching { relative(OffsetDateTime.parse(iso).toEpochSecond()) }.getOrDefault(iso)
    }

    fun dateTime(epochSeconds: Long): String =
        runCatching { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneId.systemDefault()).format(Instant.ofEpochSecond(epochSeconds)) }.getOrDefault("")

    fun shortDigest(s: String): String = s.removePrefix("sha256:").take(12)
}
