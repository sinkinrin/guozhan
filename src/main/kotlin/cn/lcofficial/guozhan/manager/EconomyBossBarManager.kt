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

    // 🔧 v1.3.38: 新增税收增量显示功能 - 记录最近的税收收入用于实时反馈
    private val recentTaxIncome = ConcurrentHashMap<UUID, TaxIncomeHighlight>()

    // 更新任务
    private var updateTask: io.papermc.paper.threadedregions.scheduler.ScheduledTask? = null

    // 更新间隔（秒）
    private const val UPDATE_INTERVAL = 10L

    // 税收高亮显示持续时间（毫秒）
    private const val TAX_HIGHLIGHT_DURATION = 5000L
    
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
     * 🔧 v1.3.38: 税收收入高亮显示数据
     * 用于在BossBar中显示最近的税收收入增量
     */
    data class TaxIncomeHighlight(
        val goldIncome: Int,
        val diamondIncome: Int,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        /**
         * 检查高亮是否已过期
         */
        fun isExpired(): Boolean {
            return System.currentTimeMillis() - timestamp > TAX_HIGHLIGHT_DURATION
        }

        /**
         * 获取剩余显示时间（秒）
         */
        fun getRemainingSeconds(): Int {
            val remaining = TAX_HIGHLIGHT_DURATION - (System.currentTimeMillis() - timestamp)
            return (remaining / 1000).toInt().coerceAtLeast(0)
        }
    }

    /**
     * 初始化经济BossBar管理器
     */
    fun initialize() {
        if (updateTask != null) {
            cleanup()
        }
        startUpdateTask()
        Guozhan.instance.logger.info("经济BossBar管理器已初始化")
    }
    
    /**
     * 启动更新任务
     */
    private fun startUpdateTask() {
        // 🔧 v1.3.51: 修复热重载问题 - 启动新任务前先取消旧任务
        stopUpdateTask()

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
     * 停止更新任务
     * 🔧 v1.3.51: 修复热重载问题 - 添加任务清理方法
     */
    private fun stopUpdateTask() {
        updateTask?.let { task ->
            if (!task.isCancelled) {
                task.cancel()
                Guozhan.instance.logger.info("经济BossBar更新任务已停止")
            }
        }
        updateTask = null
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
     * 🔧 v1.3.38: 增强税收实时反馈 - 支持显示最近的税收收入增量
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

        // 🔧 v1.3.38: 检查是否有最近的税收收入需要高亮显示
        val taxHighlight = recentTaxIncome[country.id]
        val shouldShowTaxHighlight = taxHighlight != null && !taxHighlight.isExpired()

        // 更新BossBar标题（包含税收增量显示）
        val title = if (shouldShowTaxHighlight && taxHighlight != null) {
            formatEconomyTitleWithTaxHighlight(country, incomeRate, taxHighlight)
        } else {
            formatEconomyTitle(country, incomeRate)
        }
        bossBar.setTitle(title)

        // 设置进度条（基于经济点数，最大1000点）
        val progress = (country.economyPoints.toDouble() / 1000.0).coerceIn(0.0, 1.0)
        bossBar.progress = progress

        // 🔧 v1.3.38: 税收收入时使用特殊颜色提供视觉反馈
        bossBar.color = if (shouldShowTaxHighlight) {
            BarColor.BLUE // 税收收入时使用蓝色高亮
        } else {
            // 根据经济状况设置颜色
            when {
                country.economyPoints >= 500 -> BarColor.GREEN
                country.economyPoints >= 200 -> BarColor.YELLOW
                else -> BarColor.RED
            }
        }

        // 更新显示的玩家
        updateBossBarPlayers(bossBar, onlineMembers)

        // 保存当前快照
        lastEconomyData[country.id] = currentSnapshot

        // 🔧 v1.3.38: 清理过期的税收高亮
        if (taxHighlight != null && taxHighlight.isExpired()) {
            recentTaxIncome.remove(country.id)
        }
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
        val goldIcon = "§6⛃"
        val diamondIcon = "§b◆"
        val economyIcon = "§a⚙"

        return "§f${country.name} §7| " +
                "${goldIcon}§6${country.gold} §7(§6+${incomeRate.goldPerMinute}/min§7) " +
                "${diamondIcon}§b${country.diamond} §7(§b+${incomeRate.diamondPerMinute}/min§7) " +
                "${economyIcon}§a${country.economyPoints} §7(§a+${incomeRate.economyPerMinute}/min§7)"
    }

    /**
     * 🔧 v1.3.38: 格式化带税收高亮的经济标题
     * 在正常显示基础上添加税收收入的实时反馈
     */
    private fun formatEconomyTitleWithTaxHighlight(country: Country, incomeRate: IncomeRate, taxHighlight: TaxIncomeHighlight): String {
        val goldIcon = "§6⛃"
        val diamondIcon = "§b◆"
        val economyIcon = "§a⚙"
        val taxIcon = "§e💰" // 税收图标

        // 构建税收增量显示
        val taxIncomeText = buildString {
            if (taxHighlight.goldIncome > 0) {
                append("§e+${taxHighlight.goldIncome}${goldIcon}")
            }
            if (taxHighlight.diamondIncome > 0) {
                if (taxHighlight.goldIncome > 0) append(" ")
                append("§e+${taxHighlight.diamondIncome}${diamondIcon}")
            }
        }

        // 剩余显示时间
        val remainingSeconds = taxHighlight.getRemainingSeconds()

        return "§f${country.name} §7| " +
                "${goldIcon}§6${country.gold} §7(§6+${incomeRate.goldPerMinute}/min§7) " +
                "${diamondIcon}§b${country.diamond} §7(§b+${incomeRate.diamondPerMinute}/min§7) " +
                "${economyIcon}§a${country.economyPoints} §7| " +
                "${taxIcon}§e税收: ${taxIncomeText} §7(${remainingSeconds}s)"
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
     * 🔧 v1.3.38: 记录税收收入并触发BossBar实时更新
     * 当税收系统收取税金时调用此方法，提供即时的视觉反馈
     * @param country 收税的国家
     * @param goldIncome 金锭收入
     * @param diamondIncome 钻石收入
     */
    fun recordTaxIncome(country: Country, goldIncome: Int, diamondIncome: Int) {
        if (goldIncome <= 0 && diamondIncome <= 0) return

        // 记录税收收入高亮
        recentTaxIncome[country.id] = TaxIncomeHighlight(goldIncome, diamondIncome)

        // 立即更新BossBar以显示税收收入
        forceUpdate(country)

        // 记录日志
        Guozhan.instance.logger.fine("为国家 ${country.name} 记录税收收入: +${goldIncome}金锭 +${diamondIncome}钻石，BossBar已更新")
    }
    
    /**
     * 清理资源
     * 🔧 v1.3.51: 修复热重载问题 - 改进清理逻辑
     */
    fun cleanup() {
        stopUpdateTask()
        economyBossBars.values.forEach { it.removeAll() }
        economyBossBars.clear()
        lastEconomyData.clear()
        recentTaxIncome.clear() // 🔧 v1.3.38: 清理税收高亮数据
        Guozhan.instance.logger.info("经济BossBar管理器已清理资源")
    }
}
