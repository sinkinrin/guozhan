package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.Guozhan
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 传送管理器
 * 管理玩家传送状态，支持受攻击中断机制
 */
object TeleportManager {
    
    // 正在传送的玩家列表 (玩家UUID -> 传送任务)
    private val teleportingPlayers = ConcurrentHashMap<UUID, ScheduledTask>()
    
    /**
     * 开始传送
     * @param player 玩家
     * @param teleportTask 传送任务
     */
    fun startTeleport(player: Player, teleportTask: ScheduledTask) {
        val playerId = player.uniqueId
        
        // 如果玩家已经在传送中，取消之前的传送
        cancelTeleport(player)
        
        // 添加到传送列表
        teleportingPlayers[playerId] = teleportTask
        
        Guozhan.instance.logger.fine("玩家 ${player.name} 开始传送倒计时")
    }
    
    /**
     * 取消传送
     * @param player 玩家
     */
    fun cancelTeleport(player: Player) {
        val playerId = player.uniqueId
        val task = teleportingPlayers.remove(playerId)
        
        if (task != null) {
            task.cancel()
            Guozhan.instance.logger.fine("玩家 ${player.name} 的传送已被取消")
        }
    }
    
    /**
     * 完成传送
     * @param player 玩家
     */
    fun completeTeleport(player: Player) {
        val playerId = player.uniqueId
        teleportingPlayers.remove(playerId)
        
        Guozhan.instance.logger.fine("玩家 ${player.name} 传送完成")
    }
    
    /**
     * 检查玩家是否正在传送
     * @param player 玩家
     * @return 是否正在传送
     */
    fun isPlayerTeleporting(player: Player): Boolean {
        return teleportingPlayers.containsKey(player.uniqueId)
    }
    
    /**
     * 处理玩家受到攻击事件
     * 如果玩家正在传送，则取消传送
     * @param player 受攻击的玩家
     */
    fun onPlayerDamaged(player: Player) {
        if (isPlayerTeleporting(player)) {
            cancelTeleport(player)
            player.sendMessage("§c传送被取消！你在等待期间受到了攻击。")
        }
    }
    
    /**
     * 处理玩家离线事件
     * 清理玩家的传送状态
     * @param player 离线的玩家
     */
    fun onPlayerQuit(player: Player) {
        cancelTeleport(player)
    }
    
    /**
     * 获取正在传送的玩家数量
     * @return 正在传送的玩家数量
     */
    fun getTeleportingPlayerCount(): Int {
        return teleportingPlayers.size
    }
    
    /**
     * 清理所有传送状态
     * 插件卸载时调用
     */
    fun cleanup() {
        teleportingPlayers.values.forEach { task ->
            task.cancel()
        }
        teleportingPlayers.clear()
        Guozhan.instance.logger.info("传送管理器已清理所有传送状态")
    }
}
