package cn.lcofficial.guozhan.listener

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.data.ResourceType
import cn.lcofficial.guozhan.extensions.user
import cn.lcofficial.guozhan.manager.TerritoryManager
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
        // 领土忠诚度降低的间隔（ticks）
        private const val LOYALTY_DECAY_INTERVAL = 20 * 60 * 60 // 1小时
        
        // 每次降低的忠诚度
        private const val LOYALTY_DECAY_AMOUNT = 5
    }
    
    /**
     * 注册监听器和定时任务
     */
    fun register() {
        val plugin = Guozhan.instance
        Bukkit.getPluginManager().registerEvents(this, plugin)
        
        // 启动领土忠诚度降低的定时任务
        object : BukkitRunnable() {
            override fun run() {
                decreaseLoyalty()
            }
        }.runTaskTimer(plugin, LOYALTY_DECAY_INTERVAL.toLong(), LOYALTY_DECAY_INTERVAL.toLong())
    }
    
    /**
     * 玩家加入服务器事件
     */
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        // 获取玩家的用户数据
        val user = event.player.user()
        
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
    
    /**
     * 降低所有领土的忠诚度
     */
    private fun decreaseLoyalty() {
        // 获取所有领土
        val allTerritories = TerritoryManager.territories.values.toList()
        
        var decreasedCount = 0
        for (territory in allTerritories) {
            // 只处理有所有者的领土
            if (territory.isOwned() && territory.loyalty > 0) {
                territory.loyalty = max(0, territory.loyalty - LOYALTY_DECAY_AMOUNT)
                territory.save()
                decreasedCount++
            }
        }
        
        // 记录日志
        if (decreasedCount > 0) {
            Guozhan.instance.logger.info("已降低${decreasedCount}块领土的忠诚度")
        }
    }
}