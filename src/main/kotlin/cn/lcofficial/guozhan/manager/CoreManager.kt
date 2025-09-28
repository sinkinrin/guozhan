package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.config.Config
import cn.lcofficial.guozhan.data.Country
import cn.lcofficial.guozhan.data.RelationType
import cn.lcofficial.guozhan.pluginLogger
import org.bukkit.Bukkit
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * BossBar 显示信息
 */
data class BossBarDisplayInfo(
    val country: Country,
    val attackerCountry: Country?,
    val lastUpdateTime: Long = System.currentTimeMillis(),
    val displayPlayers: Set<UUID> = emptySet()
)

/**
 * 国家核心管理器
 * 负责管理国家核心的生命值、攻击和显示
 */
object CoreManager {
    
    // 玩家攻击核心的冷却时间 (毫秒)
    private const val ATTACK_COOLDOWN = 500L
    
    // 每次攻击扣除的血量
    private const val DAMAGE_PER_ATTACK = 5
    
    // 记录玩家上次攻击核心的时间
    private val lastAttackTime = ConcurrentHashMap<UUID, Long>()
    
    // 每个国家的BossBar显示
    private val countryBossBars = ConcurrentHashMap<UUID, BossBar>()

    // BossBar 显示信息缓存
    private val bossBarDisplayCache = ConcurrentHashMap<UUID, BossBarDisplayInfo>()

    // 核心血量回复任务
    private var regenTask: BukkitTask? = null
    
    /**
     * 初始化核心管理器
     */
    fun initialize() {
        startHealthRegenTask()
        pluginLogger.info("核心管理器已初始化")
    }
    
    /**
     * 启动核心血量回复任务
     */
    private fun startHealthRegenTask() {
        regenTask?.cancel()
        
        // 每分钟执行一次血量回复
        regenTask = Bukkit.getScheduler().runTaskTimer(Guozhan.instance, Runnable {
            CountryManager.countries.values.forEach { country ->
                country.regenHealth()
                updateBossBar(country)
            }
        }, 20L * 60L, 20L * 60L) // 每60秒执行一次
    }
    
    /**
     * 玩家攻击核心
     */
    fun attackCore(player: Player, country: Country): Boolean {
        val playerId = player.uniqueId
        val currentTime = System.currentTimeMillis()
        
        // 检查冷却时间
        val lastAttack = lastAttackTime[playerId] ?: 0L
        if (currentTime - lastAttack < ATTACK_COOLDOWN) {
            return false
        }
        
        // 检查玩家是否可以攻击这个国家的核心
        if (!canAttackCore(player, country)) {
            player.sendMessage("§c你不能攻击这个国家的核心！")
            return false
        }
        
        // 记录攻击时间
        lastAttackTime[playerId] = currentTime
        
        // 对核心造成伤害
        val destroyed = country.damageCore(DAMAGE_PER_ATTACK)

        // 获取攻击者国家
        val attackerUser = UserManager.getUser(player.uniqueId)
        val attackerCountry = attackerUser?.country

        // 更新BossBar显示（传入攻击者国家信息）
        updateBossBar(country, attackerCountry)

        // 向相关玩家发送消息
        val attackerMessage = "§c你攻击了 ${country.name} 的核心！剩余血量：${country.coreHealth}/1000"
        player.sendMessage(attackerMessage)
        
        // 通知被攻击国家的成员
        country.members.forEach { member ->
            val memberPlayer = Bukkit.getPlayer(member.uniqueId)
            if (memberPlayer != null && memberPlayer.isOnline) {
                memberPlayer.sendMessage("§c警告！你的国家核心正在被 ${player.name} 攻击！剩余血量：${country.coreHealth}/1000")
            }
        }
        
        // 如果核心被摧毁
        if (destroyed) {
            onCoreDestroyed(country, player)
        }
        
        return true
    }
    
    /**
     * 检查玩家是否可以攻击核心
     */
    private fun canAttackCore(player: Player, country: Country): Boolean {
        val attackerUser = UserManager.getUser(player.uniqueId)
        
        // 不能攻击自己国家的核心
        if (attackerUser?.country?.id == country.id) {
            return false
        }
        
        // 检查是否处于战争状态或敌对关系
        if (attackerUser?.country != null) {
            val relation = DiplomacyManager.getRelation(attackerUser.country!!, country)
            if (relation.relationType != cn.lcofficial.guozhan.data.RelationType.WAR && 
                relation.relationType != cn.lcofficial.guozhan.data.RelationType.HOSTILE) {
                return false
            }
        }
        
        return true
    }
    
    /**
     * 核心被摧毁时的处理
     */
    private fun onCoreDestroyed(country: Country, destroyer: Player) {
        // 广播核心被摧毁的消息
        val message = "§c${country.name} 的核心已被 ${destroyer.name} 摧毁！该国家现在可以被占领！"
        Bukkit.broadcastMessage(message)
        
        // 移除BossBar
        removeBossBar(country)
        
        pluginLogger.info("国家 ${country.name} 的核心被玩家 ${destroyer.name} 摧毁")
    }
    
    /**
     * 更新国家的BossBar显示
     * @param country 被攻击的国家
     * @param attackerCountry 攻击者国家（可选）
     */
    fun updateBossBar(country: Country, attackerCountry: Country? = null) {
        // 检查是否启用 BossBar
        if (!Config.BossBar.enabled) {
            return
        }

        // 检查是否应该显示 BossBar
        if (!shouldDisplayBossBar(country, attackerCountry)) {
            removeBossBar(country)
            return
        }

        val bossBar = getOrCreateBossBar(country)

        // 更新标题和进度
        val titleFormat = Config.BossBar.titleFormat.replace("{country}", country.name)
        bossBar.setTitle(titleFormat)
        bossBar.progress = country.coreHealth.toDouble() / 1000.0

        // 根据血量调整颜色
        bossBar.color = when {
            country.coreHealth > 700 -> BarColor.GREEN
            country.coreHealth > 300 -> BarColor.YELLOW
            else -> BarColor.RED
        }

        // 异步更新显示玩家
        updateBossBarPlayersAsync(country, attackerCountry, bossBar)
    }
    
    /**
     * 获取或创建国家的BossBar
     */
    private fun getOrCreateBossBar(country: Country): BossBar {
        return countryBossBars.computeIfAbsent(country.id) {
            Bukkit.createBossBar(
                "${country.name} 核心血量",
                BarColor.GREEN,
                BarStyle.SOLID
            )
        }
    }
    
    /**
     * 检查是否应该显示 BossBar
     * @param country 被攻击的国家
     * @param attackerCountry 攻击者国家
     * @return 是否显示
     */
    private fun shouldDisplayBossBar(country: Country, attackerCountry: Country?): Boolean {
        // 如果核心已被摧毁，不显示BossBar
        if (country.isCoreDestroyed()) {
            return false
        }

        // 如果没有攻击者国家，只显示给被攻击国家成员
        if (attackerCountry == null) {
            return Config.BossBar.showToDefenderCountry
        }

        // 检查是否处于战争或敌对状态
        val relation = DiplomacyManager.getRelation(country, attackerCountry)
        return relation.relationType == RelationType.WAR || relation.relationType == RelationType.HOSTILE
    }

    /**
     * 异步更新BossBar的显示对象
     */
    private fun updateBossBarPlayersAsync(country: Country, attackerCountry: Country?, bossBar: BossBar) {
        Bukkit.getScheduler().runTaskAsynchronously(Guozhan.instance) {
            val displayPlayers = getDisplayPlayers(country, attackerCountry)

            // 回到主线程更新 BossBar
            Bukkit.getScheduler().runTask(Guozhan.instance) {
                updateBossBarSync(country, bossBar, displayPlayers)
            }
        }
    }

    /**
     * 获取应该显示 BossBar 的玩家列表
     * @param country 被攻击的国家
     * @param attackerCountry 攻击者国家
     * @return 玩家列表
     */
    private fun getDisplayPlayers(country: Country, attackerCountry: Country?): List<Player> {
        val players = mutableListOf<Player>()

        // 显示给被攻击国家成员
        if (Config.BossBar.showToDefenderCountry) {
            country.members.forEach { member ->
                val player = Bukkit.getPlayer(member.uniqueId)
                if (player != null && player.isOnline) {
                    players.add(player)
                }
            }
        }

        // 显示给攻击者国家成员
        if (attackerCountry != null && Config.BossBar.showToAttackerCountry) {
            attackerCountry.members.forEach { member ->
                val player = Bukkit.getPlayer(member.uniqueId)
                if (player != null && player.isOnline) {
                    players.add(player)
                }
            }
        }

        return players.distinctBy { it.uniqueId }
    }

    /**
     * 同步更新BossBar显示
     */
    private fun updateBossBarSync(country: Country, bossBar: BossBar, players: List<Player>) {
        // 清除所有玩家
        bossBar.removeAll()

        // 添加应该显示的玩家
        players.forEach { player ->
            bossBar.addPlayer(player)
        }

        // 更新缓存
        val displayInfo = BossBarDisplayInfo(
            country = country,
            attackerCountry = null, // 这里可以根据需要存储攻击者信息
            lastUpdateTime = System.currentTimeMillis(),
            displayPlayers = players.map { it.uniqueId }.toSet()
        )
        bossBarDisplayCache[country.id] = displayInfo
    }
    
    /**
     * 移除国家的BossBar
     */
    private fun removeBossBar(country: Country) {
        countryBossBars[country.id]?.let { bossBar ->
            bossBar.removeAll()
            countryBossBars.remove(country.id)
        }
    }
    
    /**
     * 查找核心所属的国家
     */
    fun findCoreCountry(location: org.bukkit.Location): Country? {
        return CountryManager.countries.values.find { country ->
            val coreLocation = country.getCoreLocation()
            coreLocation != null && 
            coreLocation.world.name == location.world.name &&
            coreLocation.distance(location) <= 2.0 // 2格范围内认为是核心区域
        }
    }
    
    /**
     * 插件卸载时清理资源
     */
    fun cleanup() {
        regenTask?.cancel()
        countryBossBars.values.forEach { it.removeAll() }
        countryBossBars.clear()
        bossBarDisplayCache.clear()
        lastAttackTime.clear()
        pluginLogger.info("核心管理器已清理资源")
    }
}