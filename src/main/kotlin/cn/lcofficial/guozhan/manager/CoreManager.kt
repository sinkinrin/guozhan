package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.data.Country
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
        
        // 更新BossBar显示
        updateBossBar(country)
        
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
     */
    fun updateBossBar(country: Country) {
        val bossBar = getOrCreateBossBar(country)
        
        // 更新标题和进度
        bossBar.setTitle("${country.name} 核心血量")
        bossBar.progress = country.coreHealth.toDouble() / 1000.0
        
        // 根据血量调整颜色
        bossBar.color = when {
            country.coreHealth > 700 -> BarColor.GREEN
            country.coreHealth > 300 -> BarColor.YELLOW
            else -> BarColor.RED
        }
        
        // 显示给相关玩家
        updateBossBarPlayers(country, bossBar)
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
     * 更新BossBar的显示对象
     */
    private fun updateBossBarPlayers(country: Country, bossBar: BossBar) {
        // 清除所有玩家
        bossBar.removeAll()
        
        // 如果核心已被摧毁，不显示BossBar
        if (country.isCoreDestroyed()) {
            return
        }
        
        // 显示给国家成员
        country.members.forEach { member ->
            val player = Bukkit.getPlayer(member.uniqueId)
            if (player != null && player.isOnline) {
                bossBar.addPlayer(player)
            }
        }
        
        // 显示给附近的敌对玩家（在核心附近的玩家）
        val coreLocation = country.getCoreLocation()
        if (coreLocation != null) {
            coreLocation.world.players.forEach { player ->
                if (player.location.distance(coreLocation) <= 50) { // 50格范围内
                    val playerUser = UserManager.getUser(player.uniqueId)
                    if (playerUser?.country?.id != country.id) {
                        bossBar.addPlayer(player)
                    }
                }
            }
        }
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
        lastAttackTime.clear()
    }
}