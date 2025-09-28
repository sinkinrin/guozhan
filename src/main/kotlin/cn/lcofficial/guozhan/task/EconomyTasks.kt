package cn.lcofficial.guozhan.task

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.data.ResourceType
import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.manager.EconomyManager
import cn.lcofficial.guozhan.manager.TerritoryManager
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitRunnable

/**
 * 经济系统相关的定时任务
 */
object EconomyTasks {
    
    // 自动收税间隔（ticks）
    private const val AUTO_TAX_INTERVAL = 20 * 60 * 60 * 24 // 24小时
    
    // 区域税收间隔（ticks）
    private const val REGIONAL_TAX_INTERVAL = 20 * 60 * 60 // 1小时
    
    // 资源生成间隔（ticks）
    private const val RESOURCE_GENERATION_INTERVAL = 20 * 60 * 60 * 3 // 3小时
    
    // 区域税收任务实例
    private lateinit var taxCollectionTask: TaxCollectionTask
    
    /**
     * 启动所有经济相关的定时任务
     */
    fun startTasks() {
        val plugin = Guozhan.instance
        
        // 启动自动收税任务
        object : BukkitRunnable() {
            override fun run() {
                collectTaxes()
            }
        }.runTaskTimer(plugin, AUTO_TAX_INTERVAL.toLong(), AUTO_TAX_INTERVAL.toLong())
        
        // 启动区域税收任务
        taxCollectionTask = TaxCollectionTask()
        taxCollectionTask.start()
        
        // 启动资源生成任务
        object : BukkitRunnable() {
            override fun run() {
                generateResources()
            }
        }.runTaskTimer(plugin, RESOURCE_GENERATION_INTERVAL.toLong(), RESOURCE_GENERATION_INTERVAL.toLong())
        
        plugin.logger.info("经济系统定时任务已启动")
    }
    
    /**
     * 自动收税任务
     */
    private fun collectTaxes() {
        val plugin = Guozhan.instance
        plugin.logger.info("正在执行自动收税...")
        
        var totalCollected = 0
        
        // 获取所有国家
        val countries = CountryManager.countries.values.toList()
        
        for (country in countries) {
            // 跳过税率为0的国家
            if (country.taxRate <= 0) continue
            
            // 获取国家的所有在线成员
            val onlineMembers = Bukkit.getOnlinePlayers().filter { player ->
                val user = player.user()
                user.country?.id == country.id
            }
            
            // 如果没有在线成员，跳过
            if (onlineMembers.isEmpty()) continue
            
            // 收税
            val taxCollected = EconomyManager.collectTax(country, true)
            totalCollected += taxCollected
            
            // 通知在线成员
            if (taxCollected > 0) {
                for (player in onlineMembers) {
                    player.sendMessage("§6[国家税收] §a您的国家已自动收取税款: §f$taxCollected §a金币")
                }
            }
        }
        
        plugin.logger.info("自动收税完成，共收取 $totalCollected 金币")
    }
    
    /**
     * 资源生成任务
     */
    private fun generateResources() {
        val plugin = Guozhan.instance
        plugin.logger.info("正在生成资源...")
        
        // 获取所有领土
        val territories = TerritoryManager.territories.values.toList()
        
        // 按国家分组
        val territoriesByCountry = territories.filter { it.isOwned() }.groupBy { it.owner!! }
        
        var totalGoldGenerated = 0
        var totalDiamondGenerated = 0
        
        for ((country, countryTerritories) in territoriesByCountry) {
            // 计算每种资源的领土数量
            val resourceCounts = countryTerritories.groupBy { it.resourceType }.mapValues { it.value.size }
            
            // 生成资源
            val goldGenerated = (resourceCounts[ResourceType.GOLD] ?: 0) * 5 // 每个金矿领土生成5金币
            val diamondGenerated = (resourceCounts[ResourceType.DIAMOND] ?: 0) * 2 // 每个钻石领土生成2钻石
            
            // 更新国家资源
            if (goldGenerated > 0 || diamondGenerated > 0) {
                country.gold += goldGenerated
                country.diamond += diamondGenerated
                country.save()
                
                totalGoldGenerated += goldGenerated
                totalDiamondGenerated += diamondGenerated
                
                // 通知在线成员
                val onlineMembers = Bukkit.getOnlinePlayers().filter { player ->
                    val user = player.user()
                    user.country?.id == country.id
                }
                
                for (player in onlineMembers) {
                    player.sendMessage("§6[资源生成] §a您的国家领土生成了资源:")
                    if (goldGenerated > 0) {
                        player.sendMessage("  §e- 黄金: §f$goldGenerated")
                    }
                    if (diamondGenerated > 0) {
                        player.sendMessage("  §b- 钻石: §f$diamondGenerated")
                    }
                }
            }
        }
        
        plugin.logger.info("资源生成完成，共生成 $totalGoldGenerated 金币和 $totalDiamondGenerated 钻石")
    }
}

/**
 * 获取玩家的用户数据
 */
private fun org.bukkit.entity.Player.user() = cn.lcofficial.guozhan.manager.UserManager.getUser(this)