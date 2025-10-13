package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.config.Config
import cn.lcofficial.guozhan.data.Country
import cn.lcofficial.guozhan.manager.UserManager.user
import cn.lcofficial.guozhan.util.runRepeat
import org.bukkit.Bukkit
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 经济BossBar管理器
 * 负责显示国家经济信息，包括国库余额、收入速率等
 */
object EconomyBossBarManager {
    
    // 每个国家的经济BossBar
    private val economyBossBars = ConcurrentHashMap<UUID, BossBar>()
    
    // 上次经济数据记录（用于计算收入速率）
    private val lastEconomyData = ConcurrentHashMap<UUID, EconomySnapshot>()
    
    // 更新任务
    private var updateTask: io.papermc.paper.threadedregions.scheduler.ScheduledTask? = null
    
    // 更新间隔（秒）
    private const val UPDATE_INTERVAL = 10L
    
    /**
     * 经济快照数据
     */
    data class EconomySnapshot(
        val gold: Int,
        val diamond: Int,
        val economyPoints: Int,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * 初始化经济BossBar管理器
     */
    fun initialize() {
        startUpdateTask()
        Guozhan.instance.logger.info("经济BossBar管理器已初始化")
    }
    
    /**
     * 启动更新任务
     */
    private fun startUpdateTask() {
        updateTask = runRepeat(20L * UPDATE_INTERVAL, 20L * UPDATE_INTERVAL) { task ->
            try {
                updateAllEconomyBossBars()
            } catch (e: Exception) {
                Guozhan.instance.logger.severe("经济BossBar更新任务执行出错: ${e.message}")
                e.printStackTrace()
            }
        }
        Guozhan.instance.logger.info("经济BossBar更新任务已启动，更新间隔: ${UPDATE_INTERVAL}秒")
    }
    
    /**
     * 更新所有国家的经济BossBar
     */
    private fun updateAllEconomyBossBars() {
        CountryManager.countries.values.forEach { country ->
            updateEconomyBossBar(country)
        }
    }
    
    /**
     * 更新指定国家的经济BossBar
     */
    fun updateEconomyBossBar(country: Country) {
        // 检查是否有在线成员
        val onlineMembers = getOnlineMembers(country)
        if (onlineMembers.isEmpty()) {
            removeEconomyBossBar(country)
            return
        }
        
        val bossBar = getOrCreateEconomyBossBar(country)
        
        // 计算收入速率
        val currentSnapshot = EconomySnapshot(country.gold, country.diamond, country.economyPoints)
        val incomeRate = calculateIncomeRate(country, currentSnapshot)
        
        // 更新BossBar标题
        val title = formatEconomyTitle(country, incomeRate)
        bossBar.setTitle(title)
        
        // 设置进度条（基于经济点数，最大1000）
        val progress = (country.economyPoints.toDouble() / 1000.0).coerceIn(0.0, 1.0)
        bossBar.progress = progress
        
        // 根据经济状况设置颜色
        bossBar.color = when {
            country.economyPoints >= 500 -> BarColor.GREEN
            country.economyPoints >= 200 -> BarColor.YELLOW
            else -> BarColor.RED
        }
        
        // 更新显示的玩家
        updateBossBarPlayers(bossBar, onlineMembers)
        
        // 保存当前快照
        lastEconomyData[country.id] = currentSnapshot
    }
    
    /**
     * 计算收入速率
     */
    private fun calculateIncomeRate(country: Country, currentSnapshot: EconomySnapshot): IncomeRate {
        val lastSnapshot = lastEconomyData[country.id]
        
        if (lastSnapshot == null) {
            return IncomeRate(0, 0, 0)
        }
        
        val timeDiff = (currentSnapshot.timestamp - lastSnapshot.timestamp) / 1000.0 // 秒
        if (timeDiff <= 0) {
            return IncomeRate(0, 0, 0)
        }
        
        // 计算每分钟收入
        val goldRate = ((currentSnapshot.gold - lastSnapshot.gold) * 60.0 / timeDiff).toInt()
        val diamondRate = ((currentSnapshot.diamond - lastSnapshot.diamond) * 60.0 / timeDiff).toInt()
        val economyRate = ((currentSnapshot.economyPoints - lastSnapshot.economyPoints) * 60.0 / timeDiff).toInt()
        
        return IncomeRate(goldRate, diamondRate, economyRate)
    }
    
    /**
     * 收入速率数据
     */
    data class IncomeRate(
        val goldPerMinute: Int,
        val diamondPerMinute: Int,
        val economyPerMinute: Int
    )
    
    /**
     * 格式化经济标题
     */
    private fun formatEconomyTitle(country: Country, incomeRate: IncomeRate): String {
        val goldIcon = "§6⚜"
        val diamondIcon = "§b◆"
        val economyIcon = "§a⚡"
        
        return "§f${country.name} §7| " +
                "${goldIcon}§6${country.gold} §7(§6+${incomeRate.goldPerMinute}/min§7) " +
                "${diamondIcon}§b${country.diamond} §7(§b+${incomeRate.diamondPerMinute}/min§7) " +
                "${economyIcon}§a${country.economyPoints} §7(§a+${incomeRate.economyPerMinute}/min§7)"
    }
    
    /**
     * 获取或创建经济BossBar
     */
    private fun getOrCreateEconomyBossBar(country: Country): BossBar {
        return economyBossBars.computeIfAbsent(country.id) {
            Bukkit.createBossBar(
                "${country.name} 经济状况",
                BarColor.GREEN,
                BarStyle.SEGMENTED_10
            )
        }
    }
    
    /**
     * 更新BossBar显示的玩家
     */
    private fun updateBossBarPlayers(bossBar: BossBar, players: List<Player>) {
        // 移除所有当前玩家
        bossBar.removeAll()
        
        // 添加新的玩家
        players.forEach { player ->
            if (player.isOnline) {
                bossBar.addPlayer(player)
            }
        }
    }
    
    /**
     * 获取国家的在线成员
     */
    private fun getOnlineMembers(country: Country): List<Player> {
        return country.members.mapNotNull { member ->
            Bukkit.getPlayer(member.uniqueId)?.takeIf { it.isOnline }
        }
    }
    
    /**
     * 移除国家的经济BossBar
     */
    private fun removeEconomyBossBar(country: Country) {
        economyBossBars[country.id]?.let { bossBar ->
            bossBar.removeAll()
            economyBossBars.remove(country.id)
        }
        lastEconomyData.remove(country.id)
    }
    
    /**
     * 手动触发更新（用于重要经济事件）
     */
    fun forceUpdate(country: Country) {
        updateEconomyBossBar(country)
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        updateTask?.cancel()
        economyBossBars.values.forEach { it.removeAll() }
        economyBossBars.clear()
        lastEconomyData.clear()
        Guozhan.instance.logger.info("经济BossBar管理器已清理资源")
    }
}
