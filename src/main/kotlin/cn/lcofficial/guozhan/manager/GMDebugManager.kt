package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.Guozhan
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

/**
 * GM调试管理器
 * 管理调试模式和调试信息输出
 */
object GMDebugManager {
    
    private var debugEnabled = false
    private val debugPlayers = ConcurrentHashMap<String, Boolean>()
    
    /**
     * 启用调试模式
     */
    fun enableDebug() {
        debugEnabled = true
        Guozhan.instance.logger.info("GM调试模式已启用")
        
        // 通知所有在线管理员
        Bukkit.getOnlinePlayers()
            .filter { it.hasPermission("guozhan.admin.debug") }
            .forEach { player ->
                player.sendMessage("§6[调试] §a调试模式已启用")
            }
    }
    
    /**
     * 禁用调试模式
     */
    fun disableDebug() {
        debugEnabled = false
        debugPlayers.clear()
        Guozhan.instance.logger.info("GM调试模式已禁用")
        
        // 通知所有在线管理员
        Bukkit.getOnlinePlayers()
            .filter { it.hasPermission("guozhan.admin.debug") }
            .forEach { player ->
                player.sendMessage("§6[调试] §c调试模式已禁用")
            }
    }
    
    /**
     * 检查调试模式是否启用
     */
    fun isDebugEnabled(): Boolean = debugEnabled
    
    /**
     * 为特定玩家启用调试信息
     */
    fun enableDebugForPlayer(player: Player) {
        debugPlayers[player.name] = true
        player.sendMessage("§6[调试] §a你的调试信息已启用")
    }
    
    /**
     * 为特定玩家禁用调试信息
     */
    fun disableDebugForPlayer(player: Player) {
        debugPlayers.remove(player.name)
        player.sendMessage("§6[调试] §c你的调试信息已禁用")
    }
    
    /**
     * 检查玩家是否启用了调试信息
     */
    fun isDebugEnabledForPlayer(player: Player): Boolean {
        return debugEnabled && (debugPlayers[player.name] == true || player.hasPermission("guozhan.admin.debug"))
    }
    
    /**
     * 发送调试信息
     */
    fun debug(message: String) {
        if (!debugEnabled) return
        
        val formattedMessage = "§8[DEBUG] §7$message"
        
        // 输出到控制台
        Guozhan.instance.logger.info("[DEBUG] $message")
        
        // 发送给启用调试的玩家
        Bukkit.getOnlinePlayers()
            .filter { isDebugEnabledForPlayer(it) }
            .forEach { player ->
                player.sendMessage(formattedMessage)
            }
    }
    
    /**
     * 发送调试信息给特定玩家
     */
    fun debugToPlayer(player: Player, message: String) {
        if (!isDebugEnabledForPlayer(player)) return
        
        player.sendMessage("§8[DEBUG] §7$message")
    }
    
    /**
     * 记录性能调试信息
     */
    fun debugPerformance(operation: String, timeMs: Long) {
        if (!debugEnabled) return
        
        val level = when {
            timeMs > 100 -> "§c[SLOW]"
            timeMs > 50 -> "§e[MEDIUM]"
            else -> "§a[FAST]"
        }
        
        debug("$level $operation took ${timeMs}ms")
    }
    
    /**
     * 记录数据库操作调试信息
     */
    fun debugDatabase(operation: String, affectedRows: Int = 0, timeMs: Long = 0) {
        if (!debugEnabled) return
        
        val message = if (timeMs > 0) {
            "DB: $operation (${affectedRows} rows, ${timeMs}ms)"
        } else {
            "DB: $operation (${affectedRows} rows)"
        }
        
        debug(message)
    }
    
    /**
     * 记录Folia调度器调试信息
     */
    fun debugScheduler(schedulerType: String, location: String? = null) {
        if (!debugEnabled) return
        
        val message = if (location != null) {
            "SCHEDULER: $schedulerType at $location"
        } else {
            "SCHEDULER: $schedulerType"
        }
        
        debug(message)
    }
    
    /**
     * 记录领土操作调试信息
     */
    fun debugTerritory(operation: String, x: Int, z: Int, world: String, details: String = "") {
        if (!debugEnabled) return
        
        val message = "TERRITORY: $operation at ($x, $z) in $world" + 
                     if (details.isNotEmpty()) " - $details" else ""
        
        debug(message)
    }
    
    /**
     * 记录国家操作调试信息
     */
    fun debugCountry(operation: String, countryName: String, details: String = "") {
        if (!debugEnabled) return
        
        val message = "COUNTRY: $operation for '$countryName'" + 
                     if (details.isNotEmpty()) " - $details" else ""
        
        debug(message)
    }
    
    /**
     * 获取调试状态信息
     */
    fun getDebugStatus(): Map<String, Any> {
        return mapOf(
            "enabled" to debugEnabled,
            "debug_players" to debugPlayers.keys.toList(),
            "online_debug_players" to Bukkit.getOnlinePlayers()
                .filter { isDebugEnabledForPlayer(it) }
                .map { it.name }
        )
    }
}
