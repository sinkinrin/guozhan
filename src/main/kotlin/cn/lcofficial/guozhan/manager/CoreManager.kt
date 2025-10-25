package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.config.Config
import cn.lcofficial.guozhan.data.Country
import cn.lcofficial.guozhan.data.RelationType
import cn.lcofficial.guozhan.pluginLogger
import cn.lcofficial.guozhan.util.runRepeat
import cn.lcofficial.guozhan.util.async
import cn.lcofficial.guozhan.util.run
import org.bukkit.Bukkit
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.Material

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
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
    private var regenTask: ScheduledTask? = null

    // BossBar更新频率限制（避免过于频繁的更新）
    private const val BOSSBAR_UPDATE_INTERVAL = 1000L // 1秒最多更新一次

    // 上次BossBar更新时间
    private val lastBossBarUpdateTime = ConcurrentHashMap<UUID, Long>()

    /**
     * 初始化核心管理器
     */
    fun initialize() {
        startHealthRegenTask()
        // 启动时校验并恢复所有国家的核心方块与保护玻璃
        try {
            restoreAllCoresOnStartup()
        } catch (e: Exception) {
            pluginLogger.severe("启动时恢复核心方块失败: ${e.message}")
            e.printStackTrace()
        }
        pluginLogger.info("核心管理器已初始化")
    }

    /**
     * 启动核心血量回复任务
     * 使用Folia的GlobalRegionScheduler进行全局定时任务
     */
    private fun startHealthRegenTask() {
        regenTask?.cancel()

        // 每分钟执行一次血量回复
        // 使用Folia的GlobalRegionScheduler，适合全局性的定时任务
        regenTask = runRepeat(20L * 60L, 20L * 60L) { task ->
            try {
                val startTime = System.currentTimeMillis()
                var processedCountries = 0

                CountryManager.countries.values.forEach { country ->
                    country.regenHealth()
                    updateBossBar(country)
                    processedCountries++
                }

                val duration = System.currentTimeMillis() - startTime
                if (duration > 100) { // 如果处理时间超过100ms，记录警告
                    pluginLogger.warning("核心血量回复任务耗时较长: ${duration}ms，处理了${processedCountries}个国家")
                }

                pluginLogger.fine("核心血量回复任务完成: 处理${processedCountries}个国家，耗时${duration}ms")

            } catch (e: Exception) {
                pluginLogger.severe("核心血量回复任务执行出错: ${e.message}")
                e.printStackTrace()
                // 如果出现严重错误，取消任务
                task.cancel()
            }
        }

        pluginLogger.info("核心血量回复任务已启动 (Folia GlobalRegionScheduler)")
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

        // 计算实际伤害（考虑离线保护）
        val actualDamage = calculateActualDamage(country, DAMAGE_PER_ATTACK)

        // 对核心造成伤害
        val destroyed = country.damageCore(actualDamage)

        // 获取攻击者国家
        val attackerUser = UserManager.getUser(player.uniqueId)
        val attackerCountry = attackerUser?.country

        // 更新BossBar显示（传入攻击者国家信息）
        updateBossBar(country, attackerCountry)

        // 向相关玩家发送消息
    val maxHealth = Config.Country.coreHealthMax
        val attackerMessage = if (actualDamage < DAMAGE_PER_ATTACK) {
            "§c你攻击了 ${country.name} 的核心！造成 ${actualDamage} 点伤害（离线保护减伤）剩余血量：${country.coreHealth}/${maxHealth}"
        } else {
            "§c你攻击了 ${country.name} 的核心！造成 ${actualDamage} 点伤害，剩余血量：${country.coreHealth}/${maxHealth}"
        }
        player.sendMessage(attackerMessage)

        // 通知被攻击国家的成员
        country.members.forEach { member ->
            val memberPlayer = Bukkit.getPlayer(member.uniqueId)
            if (memberPlayer != null && memberPlayer.isOnline) {
                val defenderMessage = if (actualDamage < DAMAGE_PER_ATTACK) {
                    "§c警告！你的国家核心正在被 ${player.name} 攻击！受到 ${actualDamage} 点伤害（离线保护减伤），剩余血量：${country.coreHealth}/${maxHealth}"
                } else {
                    "§c警告！你的国家核心正在被 ${player.name} 攻击！受到 ${actualDamage} 点伤害，剩余血量：${country.coreHealth}/${maxHealth}"
                }
                memberPlayer.sendMessage(defenderMessage)
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
     * 🔧 v1.3.32: 修复护盾系统异常 - 添加护盾状态检查
     */
    private fun canAttackCore(player: Player, country: Country): Boolean {
        val attackerUser = UserManager.getUser(player.uniqueId)

        // 不能攻击自己国家的核心
        if (attackerUser?.country?.id == country.id) {
            return false
        }

        // 🔧 v1.3.32: 关键修复 - 检查护盾状态
        // 如果目标国家开启了护盾，则无法攻击核心（除非在王战期间）
        if (ShieldManager.isShieldActive(country)) {
            // 🔧 v1.3.48: 修复问题3 - 重用Guozhan.instance.warScheduler实例，避免每次创建新实例导致isWarTime()始终返回false
            // 检查是否在王战期间，王战期间护盾不生效
            if (!Guozhan.instance.warScheduler.isWarTime()) {
                val remainingTime = ShieldManager.getShieldRemainingTime(country)
                val remainingHours = remainingTime / (60 * 60 * 1000)
                val remainingMinutes = (remainingTime % (60 * 60 * 1000)) / (60 * 1000)

                player.sendMessage("§c该国家已开启护盾保护，无法攻击核心！")
                player.sendMessage("§c护盾剩余时间: ${remainingHours}小时${remainingMinutes}分钟")
                player.sendMessage("§7护盾在王战期间不生效，请等待王战时间或护盾到期")
                return false
            } else {
                player.sendMessage("§e王战期间，护盾不生效！")
            }
        }

        // 检查是否需要宣战
        if (cn.lcofficial.guozhan.config.Config.Country.CoreProtection.requireWarDeclaration) {
            if (attackerUser?.country != null) {
                val relation = DiplomacyManager.getRelation(attackerUser.country!!, country)
                if (relation.relationType != cn.lcofficial.guozhan.data.RelationType.WAR &&
                    relation.relationType != cn.lcofficial.guozhan.data.RelationType.HOSTILE) {
                    player.sendMessage("§c你必须先向 ${country.name} 宣战才能攻击其核心！使用 /u war declare <国家名> 宣战")
                    return false
                }
            }
        }

        // 检查离线保护
        if (cn.lcofficial.guozhan.config.Config.Country.CoreProtection.offlineProtection) {
            val onlineMembers = getOnlineMembers(country)
            val minOnlineMembers = cn.lcofficial.guozhan.config.Config.Country.CoreProtection.minOnlineMembers

            Guozhan.instance.logger.info("[调试] 离线保护检查 - 国家: ${country.name}")
            Guozhan.instance.logger.info("[调试] 在线成员数: ${onlineMembers.size}, 最少需要: ${minOnlineMembers}")

            if (onlineMembers.size < minOnlineMembers) {
                player.sendMessage("§c该国家当前没有足够的在线成员，核心受到离线保护！")
                player.sendMessage("§c需要至少 ${minOnlineMembers} 名成员在线才能攻击核心")
                player.sendMessage("§c当前在线成员: ${onlineMembers.size}/${minOnlineMembers}")
                return false
            } else {
                Guozhan.instance.logger.info("[调试] 离线保护检查通过，允许攻击")
            }
        }

        return true
    }

    /**
     * 获取国家的在线成员列表
     */
    private fun getOnlineMembers(country: Country): List<Player> {
        val members = country.members
        val onlineMembers = members.mapNotNull { member ->
            org.bukkit.Bukkit.getPlayer(member.uniqueId)?.takeIf { it.isOnline }
        }

        // 调试信息
        Guozhan.instance.logger.info("[调试] 国家 '${country.name}' 成员检查:")
        Guozhan.instance.logger.info("[调试] 总成员数: ${members.size}")
        members.forEach { member ->
            val player = org.bukkit.Bukkit.getPlayer(member.uniqueId)
            val isOnline = player?.isOnline ?: false
            Guozhan.instance.logger.info("[调试] 成员 ${member.name} (${member.uniqueId}): 在线=$isOnline")
        }
        Guozhan.instance.logger.info("[调试] 在线成员数: ${onlineMembers.size}")

        return onlineMembers
    }

    /**
     * 计算实际伤害（考虑离线保护等因素）
     */
    private fun calculateActualDamage(country: Country, baseDamage: Int): Int {
        var damage = baseDamage.toDouble()

        // 检查是否有足够的在线成员
        val onlineMembers = getOnlineMembers(country)
        val minOnlineMembers = cn.lcofficial.guozhan.config.Config.Country.CoreProtection.minOnlineMembers

        if (onlineMembers.size < minOnlineMembers) {
            // 离线攻击伤害减少
            val reduction = cn.lcofficial.guozhan.config.Config.Country.CoreProtection.offlineAttackDamageReduction
            damage *= (1.0 - reduction)
        }

        return damage.toInt().coerceAtLeast(1) // 至少造成1点伤害
    }

    /**
     * 核心被摧毁时的处理
     * 🔧 v1.3.35: 修复核心摧毁后无法占领的问题 - 删除国家并清理领土所有权
     * 🔧 v1.3.48: 修复问题2 - 核心摧毁后删除视觉效果（信标和保护玻璃）
     */
    private fun onCoreDestroyed(country: Country, destroyer: Player) {
        // 广播核心被摧毁的消息
        val message = "§c${country.name} 的核心已被 ${destroyer.name} 摧毁！该国家现在可以被占领！"
        // 🔧 v1.3.35: 修复弃用警告 - 使用现代API发送广播消息
        Bukkit.getOnlinePlayers().forEach { player ->
            player.sendMessage(message)
        }

        // 🔧 v1.3.48: 修复问题2 - 删除核心视觉效果（信标和保护玻璃）
        val coreLocation = country.getCoreLocation()
        if (coreLocation != null) {
            // 使用RegionScheduler在正确的线程中删除方块
            Bukkit.getRegionScheduler().run(Guozhan.instance, coreLocation) { _ ->
                try {
                    pluginLogger.info("🔧 [核心摧毁] 开始删除国家 ${country.name} 的核心视觉效果")

                    // 删除中心信标
                    if (coreLocation.block.type == Material.BEACON) {
                        coreLocation.block.type = Material.AIR
                        pluginLogger.info("🔧 [核心摧毁] 已删除核心信标 at ${coreLocation.blockX}, ${coreLocation.blockY}, ${coreLocation.blockZ}")
                    }

                    // 删除周围的保护玻璃（3x3x3立方体，除了中心）
                    var glassRemoved = 0
                    for (x in -1..1) {
                        for (y in -1..1) {
                            for (z in -1..1) {
                                if (x == 0 && y == 0 && z == 0) continue // 跳过中心位置（已处理信标）
                                val glassLocation = coreLocation.clone().add(x.toDouble(), y.toDouble(), z.toDouble())
                                if (glassLocation.block.type == Material.GLASS) {
                                    glassLocation.block.type = Material.AIR
                                    glassRemoved++
                                }
                            }
                        }
                    }

                    pluginLogger.info("🔧 [核心摧毁] 已删除 ${glassRemoved} 个保护玻璃方块")
                    pluginLogger.info("🔧 [核心摧毁] 国家 ${country.name} 的核心视觉效果删除完成")

                } catch (e: Exception) {
                    pluginLogger.severe("🔧 [核心摧毁] 删除国家 ${country.name} 的核心视觉效果时出错: ${e.message}")
                    e.printStackTrace()
                }
            }
        } else {
            pluginLogger.warning("🔧 [核心摧毁] 国家 ${country.name} 没有核心位置信息，无法删除视觉效果")
        }

        // 移除BossBar
        removeBossBar(country)

        pluginLogger.info("国家 ${country.name} 的核心被玩家 ${destroyer.name} 摧毁")

        // 🔧 v1.3.35: 关键修复 - 删除被摧毁的国家，这会自动清理所有领土所有权
        // 🔧 v1.3.54: 修复问题2 (High) - 在GlobalRegionScheduler中执行删除，避免异步线程调用Bukkit API
        try {
            // 在GlobalRegionScheduler中执行删除操作，确保Bukkit API调用在正确的线程
            cn.lcofficial.guozhan.util.run { _ ->
                try {
                    CountryManager.deleteCountry(country)
                    pluginLogger.info("[核心摧毁] 已删除国家 ${country.name}，所有领土现在可以被占领")

                    // 通知所有在线玩家
                    val message = "§a${country.name} 的所有领土现在可以被占领！"
                    Bukkit.getOnlinePlayers().forEach { player ->
                        player.sendMessage(message)
                    }
                } catch (e: Exception) {
                    pluginLogger.severe("[核心摧毁] 删除国家 ${country.name} 时出错: ${e.message}")
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            pluginLogger.severe("[核心摧毁] 启动异步删除任务时出错: ${e.message}")
            e.printStackTrace()
        }
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

        // 频率限制：避免过于频繁的更新
        val currentTime = System.currentTimeMillis()
        val lastUpdate = lastBossBarUpdateTime[country.id] ?: 0L
        if (currentTime - lastUpdate < BOSSBAR_UPDATE_INTERVAL) {
            return // 跳过过于频繁的更新
        }
        lastBossBarUpdateTime[country.id] = currentTime

        // 检查是否应该显示 BossBar
        if (!shouldDisplayBossBar(country, attackerCountry)) {
            removeBossBar(country)
            return
        }

        val bossBar = getOrCreateBossBar(country)

        // 更新标题和进度
        val titleFormat = Config.BossBar.titleFormat.replace("{country}", country.name)
        bossBar.setTitle(titleFormat)
        val maxHealth = cn.lcofficial.guozhan.config.Config.Country.coreHealthMax
        bossBar.progress = (country.coreHealth.toDouble() / maxHealth.toDouble()).coerceIn(0.0, 1.0)

        // 根据血量调整颜色（按比例）
        val healthRatio = country.coreHealth.toDouble() / maxHealth.toDouble()
        bossBar.color = when {
            healthRatio > 0.7 -> BarColor.GREEN
            healthRatio > 0.3 -> BarColor.YELLOW
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
     * 启动时恢复所有国家核心的方块与保护玻璃
     */
    private fun restoreAllCoresOnStartup() {
        CountryManager.countries.values.forEach { country ->
            val coreLoc = country.getCoreLocation()
            if (coreLoc == null) {
                pluginLogger.warning("[核心恢复] 国家 ${country.name} 未设置核心位置，跳过")
                return@forEach
            }
            // 在对应区域线程执行方块操作
            Bukkit.getRegionScheduler().run(Guozhan.instance, coreLoc) { _ ->
                try {
                    // 恢复中心信标
                    if (coreLoc.block.type != Material.BEACON) {
                        coreLoc.block.type = Material.BEACON
                    }
                    // 恢复保护玻璃立方体
                    for (x in -1..1) {
                        for (y in -1..1) {
                            for (z in -1..1) {
                                if (x == 0 && y == 0 && z == 0) continue
                                val p = coreLoc.clone().add(x.toDouble(), y.toDouble(), z.toDouble())
                                // 只要不是信标，统一恢复为玻璃
                                if (p.block.type != Material.GLASS) {
                                    p.block.type = Material.GLASS
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    pluginLogger.severe("[核心恢复] 恢复国家 ${country.name} 的核心方块失败: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
        pluginLogger.info("[核心恢复] 启动时已校验并恢复所有国家的核心方块与保护玻璃")
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
     * 使用Folia的AsyncScheduler进行异步处理
     * 🔧 v1.3.52: 修复问题3 (High) - 在主线程快照玩家数据，避免异步线程调用Bukkit API
     */
    private fun updateBossBarPlayersAsync(country: Country, attackerCountry: Country?, bossBar: BossBar) {
        // 🔧 v1.3.52: 在主线程获取显示玩家列表，避免异步线程调用Bukkit.getPlayer()
        val displayPlayers = getDisplayPlayers(country, attackerCountry)

        // 使用Folia的AsyncScheduler进行异步处理
        async { _ ->
            // 回到主线程更新 BossBar
            // 使用GlobalRegionScheduler执行主线程任务
            run { _ ->
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
     * 优化：只更新变化的玩家，避免不必要的removeAll()操作
     */
    private fun updateBossBarSync(country: Country, bossBar: BossBar, players: List<Player>) {
        val newPlayerIds = players.map { it.uniqueId }.toSet()

        // 获取当前显示的玩家
        val cachedInfo = bossBarDisplayCache[country.id]
        val currentPlayerIds = cachedInfo?.displayPlayers ?: emptySet()

        // 计算需要添加和移除的玩家
        val playersToAdd = newPlayerIds - currentPlayerIds
        val playersToRemove = currentPlayerIds - newPlayerIds

        // 只有在有变化时才进行更新
        if (playersToAdd.isNotEmpty() || playersToRemove.isNotEmpty()) {
            // 移除不再需要显示的玩家
            playersToRemove.forEach { playerId ->
                val player = Bukkit.getPlayer(playerId)
                if (player != null) {
                    bossBar.removePlayer(player)
                }
            }

            // 添加新的玩家
            playersToAdd.forEach { playerId ->
                val player = Bukkit.getPlayer(playerId)
                if (player != null && player.isOnline) {
                    bossBar.addPlayer(player)
                }
            }

            pluginLogger.fine("BossBar更新: 国家${country.name}, 添加${playersToAdd.size}个玩家, 移除${playersToRemove.size}个玩家")
        }

        // 更新缓存
        val displayInfo = BossBarDisplayInfo(
            country = country,
            attackerCountry = null, // 这里可以根据需要存储攻击者信息
            lastUpdateTime = System.currentTimeMillis(),
            displayPlayers = newPlayerIds
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
     * 清理单个国家的核心相关资源
     * 🔧 v1.3.30: 新增方法，用于删除国家时清理该国家的资源，而不影响其他国家
     * @param countryId 要清理的国家ID
     */
    fun cleanupCountry(countryId: UUID) {
        // 移除该国家的 BossBar
        countryBossBars[countryId]?.let { bossBar ->
            bossBar.removeAll()
            countryBossBars.remove(countryId)
            pluginLogger.info("已移除国家 $countryId 的 BossBar")
        }

        // 清除该国家的 BossBar 显示缓存
        bossBarDisplayCache.remove(countryId)

        // 清除该国家的上次更新时间
        lastBossBarUpdateTime.remove(countryId)

        pluginLogger.info("已清理国家 $countryId 的核心相关资源")
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
        lastBossBarUpdateTime.clear()
        pluginLogger.info("核心管理器已清理资源")
    }
}