package cn.lcofficial.guozhan.debug

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Simple helpers for building colorized debug output.
 */
object DebugVisualizationFormatter {
    private val divider = "§6===================================="
    private val headerTemplate = "§6========== [%s] =========="
    private val timestampFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    fun header(title: String): String = headerTemplate.format(title)

    fun footer(): String = divider

    fun summaryLine(label: String, value: String, labelColor: String = "§e", valueColor: String = "§f"): String {
        return "$labelColor$label: $valueColor$value"
    }

    fun bulletLine(index: Int, name: String, id: String? = null, nameColor: String = "§b"): String {
        val base = "§f${index}. $nameColor$name"
        return if (id.isNullOrBlank()) base else "$base §7(ID: $id)"
    }

    fun detailLine(prefix: String, value: String, prefixColor: String = "§7", valueColor: String = "§f"): String {
        return "$prefixColor$prefix: $valueColor$value"
    }

    fun section(title: String): String = "§a$title"

    fun warn(message: String): String = "§e⚠ §f$message"

    fun error(message: String): String = "§c✖ §f$message"

    fun maskUuid(uuid: UUID): String = uuid.toString().substring(0, 8)

    fun formatTimestamp(millis: Long?): String {
        if (millis == null || millis <= 0) return "§7N/A"
        return "§f" + timestampFormatter.format(Instant.ofEpochMilli(millis))
    }
}
