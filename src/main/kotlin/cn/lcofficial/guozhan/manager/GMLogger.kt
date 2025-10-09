package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.Guozhan
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * GM操作日志管理器
 * 记录所有GM操作到日志文件和控制台
 */
object GMLogger {
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    private val logQueue = ConcurrentLinkedQueue<GMLogEntry>()
    private val logFile = File(Guozhan.instance.dataFolder, "gm-operations.log")
    
    init {
        // 确保日志文件存在
        if (!logFile.exists()) {
            logFile.parentFile.mkdirs()
            logFile.createNewFile()
            
            // 写入日志文件头部
            FileWriter(logFile, true).use { writer ->
                writer.appendLine("# GuoZhan GM Operations Log")
                writer.appendLine("# Format: [TIMESTAMP] OPERATOR ACTION TARGET DETAILS")
                writer.appendLine("# Started at: ${dateFormat.format(Date())}")
                writer.appendLine("")
            }
        }
    }
    
    /**
     * 记录GM操作
     * @param operator 操作者
     * @param action 操作类型
     * @param target 操作目标（可选）
     * @param details 操作详情
     */
    fun logGMAction(
        operator: CommandSender,
        action: String,
        target: String?,
        details: Map<String, Any>
    ) {
        val logEntry = GMLogEntry(
            timestamp = System.currentTimeMillis(),
            operator = operator.name,
            operatorType = if (operator is Player) "PLAYER" else "CONSOLE",
            operatorUUID = if (operator is Player) operator.uniqueId.toString() else "CONSOLE",
            action = action,
            target = target,
            details = details,
            serverTime = dateFormat.format(Date())
        )
        
        // 添加到队列
        logQueue.offer(logEntry)
        
        // 立即写入日志文件
        writeToLogFile(logEntry)
        
        // 输出到控制台
        logToConsole(logEntry)
        
        // 如果是重要操作，额外通知在线管理员
        if (isImportantAction(action)) {
            notifyOnlineAdmins(logEntry)
        }
    }
    
    /**
     * 写入日志文件
     */
    private fun writeToLogFile(entry: GMLogEntry) {
        try {
            FileWriter(logFile, true).use { writer ->
                val detailsStr = if (entry.details.isNotEmpty()) {
                    entry.details.entries.joinToString(", ") { "${it.key}=${it.value}" }
                } else {
                    "无"
                }
                
                val logLine = "[${entry.serverTime}] ${entry.operator}(${entry.operatorType}) " +
                        "${entry.action} ${entry.target ?: "N/A"} {$detailsStr}"
                
                writer.appendLine(logLine)
            }
        } catch (e: Exception) {
            Guozhan.instance.logger.warning("写入GM日志失败: ${e.message}")
        }
    }
    
    /**
     * 输出到控制台
     */
    private fun logToConsole(entry: GMLogEntry) {
        val message = "§6[GM操作] §f${entry.operator} §e${entry.action} §f${entry.target ?: ""}"
        Guozhan.instance.logger.info(message.replace("§[0-9a-fk-or]".toRegex(), ""))
    }
    
    /**
     * 通知在线管理员
     */
    private fun notifyOnlineAdmins(entry: GMLogEntry) {
        val message = "§6[GM] §f${entry.operator} §e${entry.action} §f${entry.target ?: ""}"
        
        org.bukkit.Bukkit.getOnlinePlayers()
            .filter { it.hasPermission("guozhan.admin.notify") && it.name != entry.operator }
            .forEach { admin ->
                admin.sendMessage(message)
            }
    }
    
    /**
     * 判断是否为重要操作
     */
    private fun isImportantAction(action: String): Boolean {
        return when (action) {
            "CLEAR_ALL_DATA", "CLEAR_COUNTRIES", "CLEAR_TERRITORIES", "CLEAR_USERS",
            "SET_CORE_HEALTH", "ADD_GOLD", "ADD_DIAMONDS", "SET_COUNTRY" -> true
            else -> false
        }
    }
    
    /**
     * 获取最近的GM操作记录
     * @param limit 限制数量
     * @return 操作记录列表
     */
    fun getRecentOperations(limit: Int = 50): List<GMLogEntry> {
        return logQueue.toList().takeLast(limit)
    }
    
    /**
     * 清理旧的日志记录（保留最近1000条）
     */
    fun cleanupOldLogs() {
        while (logQueue.size > 1000) {
            logQueue.poll()
        }
    }
    
    /**
     * 获取操作统计
     */
    fun getOperationStats(): Map<String, Int> {
        val stats = mutableMapOf<String, Int>()
        logQueue.forEach { entry ->
            stats[entry.action] = stats.getOrDefault(entry.action, 0) + 1
        }
        return stats
    }
}

/**
 * GM操作日志条目
 */
data class GMLogEntry(
    val timestamp: Long,
    val operator: String,
    val operatorType: String,
    val operatorUUID: String,
    val action: String,
    val target: String?,
    val details: Map<String, Any>,
    val serverTime: String
)
