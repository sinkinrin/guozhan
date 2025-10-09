package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.config.Message.sendError
import cn.lcofficial.guozhan.config.Message.sendInfo
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 冷却时间管理器
 * 统一管理所有命令的冷却时间，支持GM权限绕过
 */
object CooldownManager {
    
    // 冷却数据存储 (玩家UUID -> (命令类型 -> 冷却结束时间))
    private val cooldowns = ConcurrentHashMap<UUID, ConcurrentHashMap<CooldownType, Long>>()
    
    /**
     * 冷却类型枚举
     */
    enum class CooldownType(val displayName: String, val durationMillis: Long) {
        KICK("驱逐国民", 60_000L),                    // 1分钟
        CONTRIBUTE("向国库上贡", 24 * 60 * 60_000L),   // 24小时
        DISBAND("解散国家", 60 * 60_000L),            // 1小时
        MOVE("迁移王城", 12 * 60 * 60_000L),          // 12小时
        LEAVE("退出国家", 60 * 60_000L)               // 1小时
    }
    
    /**
     * 检查玩家是否可以执行命令（包含GM权限绕过检查）
     * @param sender 命令发送者
     * @param type 冷却类型
     * @return 是否可以执行命令
     */
    fun canExecute(sender: CommandSender, type: CooldownType): Boolean {
        if (sender !is Player) return true
        
        // 检查GM绕过权限
        if (sender.hasPermission("guozhan.admin.bypass.cooldown")) {
            sender.sendInfo("§7[管理员] 已绕过冷却时间限制")
            return true
        }
        
        val playerCooldowns = cooldowns[sender.uniqueId] ?: return true
        val cooldownEnd = playerCooldowns[type] ?: return true
        val currentTime = System.currentTimeMillis()
        
        if (currentTime >= cooldownEnd) {
            // 冷却已结束，清理过期数据
            playerCooldowns.remove(type)
            if (playerCooldowns.isEmpty()) {
                cooldowns.remove(sender.uniqueId)
            }
            return true
        }
        
        // 仍在冷却中，发送友好提示
        val remainingTime = cooldownEnd - currentTime
        val timeString = formatTime(remainingTime)
        sender.sendError("${type.displayName}冷却中，还需等待 $timeString")
        return false
    }
    
    /**
     * 设置玩家的冷却时间
     * @param player 玩家
     * @param type 冷却类型
     */
    fun setCooldown(player: Player, type: CooldownType) {
        // GM权限用户不设置冷却
        if (player.hasPermission("guozhan.admin.bypass.cooldown")) {
            return
        }
        
        val playerCooldowns = cooldowns.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }
        val cooldownEnd = System.currentTimeMillis() + type.durationMillis
        playerCooldowns[type] = cooldownEnd
    }
    
    /**
     * 获取玩家的剩余冷却时间
     * @param player 玩家
     * @param type 冷却类型
     * @return 剩余冷却时间（毫秒），如果没有冷却则返回0
     */
    fun getRemainingCooldown(player: Player, type: CooldownType): Long {
        if (player.hasPermission("guozhan.admin.bypass.cooldown")) {
            return 0L
        }
        
        val playerCooldowns = cooldowns[player.uniqueId] ?: return 0L
        val cooldownEnd = playerCooldowns[type] ?: return 0L
        val currentTime = System.currentTimeMillis()
        
        return if (currentTime >= cooldownEnd) 0L else cooldownEnd - currentTime
    }
    
    /**
     * 清除玩家的特定冷却
     * @param player 玩家
     * @param type 冷却类型
     */
    fun clearCooldown(player: Player, type: CooldownType) {
        val playerCooldowns = cooldowns[player.uniqueId] ?: return
        playerCooldowns.remove(type)
        if (playerCooldowns.isEmpty()) {
            cooldowns.remove(player.uniqueId)
        }
    }
    
    /**
     * 清除玩家的所有冷却
     * @param player 玩家
     */
    fun clearAllCooldowns(player: Player) {
        cooldowns.remove(player.uniqueId)
    }
    
    /**
     * 格式化时间显示
     * @param millis 毫秒数
     * @return 格式化的时间字符串
     */
    private fun formatTime(millis: Long): String {
        val seconds = millis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        
        return when {
            days > 0 -> {
                val remainingHours = hours % 24
                val remainingMinutes = minutes % 60
                when {
                    remainingHours > 0 && remainingMinutes > 0 -> "${days}天${remainingHours}小时${remainingMinutes}分钟"
                    remainingHours > 0 -> "${days}天${remainingHours}小时"
                    remainingMinutes > 0 -> "${days}天${remainingMinutes}分钟"
                    else -> "${days}天"
                }
            }
            hours > 0 -> {
                val remainingMinutes = minutes % 60
                if (remainingMinutes > 0) "${hours}小时${remainingMinutes}分钟" else "${hours}小时"
            }
            minutes > 0 -> {
                val remainingSeconds = seconds % 60
                if (remainingSeconds > 0) "${minutes}分钟${remainingSeconds}秒" else "${minutes}分钟"
            }
            else -> "${seconds}秒"
        }
    }
    
    /**
     * 清理过期的冷却数据（定期任务调用）
     */
    fun cleanupExpiredCooldowns() {
        val currentTime = System.currentTimeMillis()
        val playersToRemove = mutableSetOf<UUID>()
        
        cooldowns.forEach { (playerId, playerCooldowns) ->
            val typesToRemove = mutableSetOf<CooldownType>()
            
            playerCooldowns.forEach { (type, cooldownEnd) ->
                if (currentTime >= cooldownEnd) {
                    typesToRemove.add(type)
                }
            }
            
            typesToRemove.forEach { playerCooldowns.remove(it) }
            
            if (playerCooldowns.isEmpty()) {
                playersToRemove.add(playerId)
            }
        }
        
        playersToRemove.forEach { cooldowns.remove(it) }
    }
    
    /**
     * 获取当前冷却数据统计（用于调试）
     */
    fun getCooldownStats(): String {
        val totalPlayers = cooldowns.size
        val totalCooldowns = cooldowns.values.sumOf { it.size }
        return "冷却数据统计: $totalPlayers 个玩家，$totalCooldowns 个活跃冷却"
    }
}
