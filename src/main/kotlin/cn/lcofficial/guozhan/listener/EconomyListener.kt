package cn.lcofficial.guozhan.listener

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.data.ResourceType
import cn.lcofficial.guozhan.manager.TerritoryManager
import cn.lcofficial.guozhan.manager.UserManager
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.scheduler.BukkitRunnable
import kotlin.math.max

/**
 * 经济系统监听器，处理经济相关事件和定时任务
 */
class EconomyListener : Listener {
    
    companion object {
        // 注意：忠诚度系统已统一到LoyaltySystem.kt中
        // 此处不再处理忠诚度衰减逻辑
    }
    
    /**
     * 注册监听器
     * 注意：忠诚度定时任务已移至LoyaltySystem.kt统一管理
     */
    fun register() {
        val plugin = Guozhan.instance
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // 忠诚度系统已统一到LoyaltySystem.kt中，此处不再启动重复的定时任务
        plugin.logger.info("经济监听器已注册（忠诚度系统已统一管理）")
    }
    
    /**
     * 玩家加入服务器事件
     */
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        // 获取玩家的用户数据
        val user = UserManager.getUser(event.player.uniqueId) ?: return
        
        // 如果玩家属于某个国家，发送国家经济状况信息
        val country = user.country
        if (country != null) {
            event.player.sendMessage("§6===== 国家经济状况 =====")
            event.player.sendMessage("§a国家名称: §f${country.name}")
            event.player.sendMessage("§a黄金储备: §f${country.gold}")
            event.player.sendMessage("§a钻石储备: §f${country.diamond}")
            
            // 如果玩家是国家管理员或所有者，显示更多信息
            if (user.rank.value >= 2) {
                // 获取国家领土数量
                val territories = TerritoryManager.getTerritoriesByCountry(country)
                val resourceCounts = territories.groupBy { it.resourceType }.mapValues { it.value.size }
                
                event.player.sendMessage("§a领土数量: §f${territories.size}")
                event.player.sendMessage("§a资源分布:")
                event.player.sendMessage("  §a- 黄金: §f${resourceCounts[ResourceType.GOLD] ?: 0}")
                event.player.sendMessage("  §a- 钻石: §f${resourceCounts[ResourceType.DIAMOND] ?: 0}")
                event.player.sendMessage("  §a- 铁矿: §f${resourceCounts[ResourceType.IRON] ?: 0}")
                event.player.sendMessage("  §a- 食物: §f${resourceCounts[ResourceType.FOOD] ?: 0}")
                event.player.sendMessage("  §a- 无资源: §f${resourceCounts[ResourceType.NONE] ?: 0}")
            }
        }
    }
    
    /**
     * 玩家退出服务器事件
     */
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        // 可以在这里添加玩家退出时的经济相关处理
    }
    
    // 注意：decreaseLoyalty()方法已移除
    // 忠诚度系统已统一到LoyaltySystem.kt中管理
    // 如需手动触发忠诚度更新，请使用LoyaltySystem类
}