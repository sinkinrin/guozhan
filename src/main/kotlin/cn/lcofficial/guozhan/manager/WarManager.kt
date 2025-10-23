package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.config.DiplomacyConfig
import cn.lcofficial.guozhan.data.Country
import cn.lcofficial.guozhan.data.RelationType
import cn.lcofficial.guozhan.effect.WarEffects
import cn.lcofficial.guozhan.manager.UserManager.user
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.command.CommandSender
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import io.papermc.paper.threadedregions.scheduler.ScheduledTask

/**
 * 战争管理器，处理国家间战争状态的特殊逻辑
 * 
 * 主要功能：
 * - 战争状态管理（开始、结束、检查）
 * - 战争积分计算和显示
 * - 战争超时检查
 * - 战争伤害和击杀奖励
 * - GM命令支持
 */
object WarManager {
    // 战争状态缓存，记录战争开始时间 (战争ID -> 开始时间)
    private val warStartTimes = ConcurrentHashMap<String, Long>()
    
    // 战争积分计算任务 (战争ID -> 任务)
    private val warScoreCalculationTasks = ConcurrentHashMap<String, ScheduledTask>()
    
    // 战争状态冷却时间（毫秒）- 24小时
    private const val WAR_COOLDOWN = 24 * 60 * 60 * 1000L
    
    // 战争状态下的伤害倍率
    private const val WAR_DAMAGE_MULTIPLIER = 1.5
    
    /**
     * 初始化战争管理器
     */
    fun initialize() {
        Guozhan.instance.logger.info("正在初始化战争管理器...")
        loadWarStates()
        Guozhan.instance.logger.info("战争管理器初始化完成")
    }
    
    /**
     * 加载所有战争状态（服务器启动时调用）
     */
    private fun loadWarStates() {
        warStartTimes.clear()

        val countries = CountryManager.countries.values
        val processedWarIds = mutableSetOf<String>()

        for (country1 in countries) {
            for (country2 in countries) {
                if (country1.id == country2.id) continue

                val relation = DiplomacyManager.getRelation(country1, country2)
                if (relation.relationType != RelationType.WAR) continue

                val warId = getWarId(country1, country2)
                if (!processedWarIds.add(warId)) continue

                val warStartTime = relation.warStartTime ?: System.currentTimeMillis()
                warStartTimes[warId] = warStartTime

                if (relation.warStartTime == null) {
                    relation.warStartTime = warStartTime
                    relation.save()
                    Guozhan.instance.logger.warning("[战争系统] 战争 $warId 缺少开始时间，已设置为当前时间")
                }

                resumeWarSession(country1, country2, warStartTime)
            }
        }

        Guozhan.instance.logger.info("已加载 ${warStartTimes.size} 个活跃战争状态")
    }
    
    /**
     * 获取战争ID（用于缓存）
     * 确保ID顺序一致，避免重复
     */
    private fun getWarId(country1: Country, country2: Country): String {
        val ids = listOf(country1.id, country2.id).sorted()
        return "${ids[0]}_${ids[1]}"
    }
    
    /**
     * 恢复战争会话（服务器重启后恢复战争状态）
     */
    private fun resumeWarSession(country1: Country, country2: Country, warStartTime: Long) {
        WarScoreBossBarManager.startWarScoreDisplay(country1, country2, warStartTime)
        startWarScoreCalculationTask(country1, country2)
        Guozhan.instance.logger.info("[战争系统] 恢复战争会话: ${country1.name} vs ${country2.name}")
    }
    
    /**
     * 开始战争
     */
    fun startWar(country1: Country, country2: Country) {
        // 检查是否已经处于战争状态，避免递归调用
        val currentRelation = DiplomacyManager.getRelation(country1, country2)
        if (currentRelation.relationType == RelationType.WAR) {
            Guozhan.instance.logger.info("[战争系统] ${country1.name} 与 ${country2.name} 已经处于战争状态，跳过重复处理")
            return
        }

        val warId = getWarId(country1, country2)
        val warStartTime = System.currentTimeMillis()
        warStartTimes[warId] = warStartTime

        // 设置战争开始时间到外交关系
        currentRelation.warStartTime = warStartTime

        // 更新外交关系为战争状态
        DiplomacyManager.updateRelation(country1, country2, RelationType.WAR)

        // 启动战争积分显示和计算
        WarScoreBossBarManager.startWarScoreDisplay(country1, country2, warStartTime)
        startWarScoreCalculationTask(country1, country2)

        // 广播战争开始消息
        val message = "§c§l战争爆发! §f${country1.name} §c与 §f${country2.name} §c进入战争状态"
        Bukkit.getOnlinePlayers().forEach { player ->
            player.sendMessage(message)
        }

        Guozhan.instance.logger.info("[战争系统] 战争开始: ${country1.name} vs ${country2.name}, 开始时间: $warStartTime")
    }
    
    /**
     * 结束战争
     * @param winnerCountry 胜利者国家，null表示平局
     */
    fun endWar(country1: Country, country2: Country, winnerCountry: Country?) {
        // 检查是否处于战争状态，避免递归调用
        val currentRelation = DiplomacyManager.getRelation(country1, country2)
        if (currentRelation.relationType != RelationType.WAR) {
            Guozhan.instance.logger.info("[战争系统] ${country1.name} 与 ${country2.name} 不处于战争状态，跳过重复处理")
            return
        }

        val warId = getWarId(country1, country2)
        warStartTimes.remove(warId)

        // 停止战争积分显示和计算
        val warScoreResult = WarScoreBossBarManager.endWarScoreDisplay(country1, country2)
        stopWarScoreCalculationTask(country1, country2)
        
        // 更新外交关系为敌对状态
        DiplomacyManager.updateRelation(country1, country2, RelationType.HOSTILE)

        // 确定胜利者（优先使用传入的胜利者，否则使用积分结果）
        val resolvedWinner = winnerCountry ?: warScoreResult?.winner
        
        // 广播战争结束消息
        val message = if (resolvedWinner != null) {
            val loserName = if (resolvedWinner.id == country1.id) country2.name else country1.name
            "§6战争结束! §f${resolvedWinner.name} §6战胜 §f$loserName"
        } else {
            "§6战争结束! §f${country1.name} §6与 §f${country2.name} §6的战争以平局告终"
        }

        // 应用战争效果和奖励
        Bukkit.getOnlinePlayers().forEach { player ->
            player.sendMessage(message)

            val user = player.user()
            val playerCountry = user.country

            if (playerCountry != null && resolvedWinner != null) {
                if (playerCountry.id == resolvedWinner.id) {
                    WarEffects.applyVictoryEffects(player)
                } else if (playerCountry.id == (if (resolvedWinner.id == country1.id) country2.id else country1.id)) {
                    WarEffects.applyDefeatEffects(player)
                }
            }
        }

        // 给予胜利者国家经济奖励
        if (resolvedWinner != null) {
            val reward = DiplomacyConfig.getWarVictoryReward()
            resolvedWinner.economyPoints += reward
            
            try {
                resolvedWinner.save()
                Guozhan.instance.logger.info("[战争系统] ${resolvedWinner.name} 获得战争胜利奖励: $reward 经济点数（已持久化）")
            } catch (e: Exception) {
                Guozhan.instance.logger.severe("[战争系统] 保存战争奖励失败: ${e.message}")
                e.printStackTrace()
            }
        }

        Guozhan.instance.logger.info("[战争系统] 战争结束: ${country1.name} vs ${country2.name}, 胜利者: ${resolvedWinner?.name ?: "无"}")
    }
    
    /**
     * 检查两个国家是否处于战争状态
     */
    fun isAtWar(country1: Country, country2: Country): Boolean {
        return DiplomacyManager.getRelation(country1, country2).relationType == RelationType.WAR
    }
    
    /**
     * 检查两个玩家是否处于战争状态
     */
    fun isAtWar(player1: Player, player2: Player): Boolean {
        val user1 = player1.user()
        val user2 = player2.user()
        
        val country1 = user1.country ?: return false
        val country2 = user2.country ?: return false
        
        return isAtWar(country1, country2)
    }
    
    /**
     * 处理玩家伤害事件，在战争状态下增加伤害
     */
    fun handleDamageEvent(event: EntityDamageByEntityEvent) {
        if (event.damager !is Player || event.entity !is Player) return
        
        val attacker = event.damager as Player
        val victim = event.entity as Player
        
        if (isAtWar(attacker, victim)) {
            // 战争状态下增加伤害
            event.damage = event.damage * DiplomacyConfig.getWarDamageMultiplier()
        }
    }
    
    /**
     * 处理玩家死亡事件，在战争中击杀给予奖励
     */
    fun handlePlayerDeath(event: PlayerDeathEvent) {
        val killed = event.entity
        val killer = killed.killer ?: return
        
        val killedUser = killed.user()
        val killerUser = killer.user()
        
        val killedCountry = killedUser.country ?: return
        val killerCountry = killerUser.country ?: return
        
        // 检查两个国家是否处于战争状态
        if (isAtWar(killedCountry, killerCountry)) {
            // 在战争中的击杀，给予击杀者国家经济奖励
            val reward = DiplomacyConfig.getWarKillReward()
            killerCountry.economyPoints += reward

            // 持久化经济点数到数据库
            try {
                killerCountry.save()
                Guozhan.instance.logger.info("[战争击杀] ${killerCountry.name} 击杀 ${killedCountry.name} 成员，获得 $reward 经济点数（已持久化）")
            } catch (e: Exception) {
                Guozhan.instance.logger.severe("[战争击杀] 保存经济点数失败: ${e.message}")
                e.printStackTrace()
            }

            // 通知击杀者
            killer.sendMessage("§6战争击杀! §f你击杀了敌对国家的 §e${killed.name}§f, 你的国家获得了 §a$reward §f经济点数奖励!")

            // 广播战争击杀消息
            val broadcastMessage = "§c战争消息: §f${killerCountry.name} §f的 §e${killer.name} §f在战争中击杀了 §f${killedCountry.name} §f的 §e${killed.name}§f!"
            Bukkit.getOnlinePlayers().forEach { player ->
                player.sendMessage(broadcastMessage)
            }
        }
    }

    /**
     * 检查所有战争是否超时，超时则自动结束
     */
    fun checkWarTimeout() {
        val currentTime = System.currentTimeMillis()
        val timeoutWars = mutableListOf<Pair<Country, Country>>()

        // 检查所有战争状态
        for ((warId, startTime) in warStartTimes) {
            // 检查是否超时
            if (currentTime - startTime > WAR_COOLDOWN) {
                // 解析战争ID获取国家
                val countryIds = warId.split("_")
                if (countryIds.size != 2) {
                    Guozhan.instance.logger.warning("[战争系统] 无效的战争ID: $warId")
                    continue
                }

                val country1 = CountryManager.getCountryById(UUID.fromString(countryIds[0]))
                val country2 = CountryManager.getCountryById(UUID.fromString(countryIds[1]))

                if (country1 == null || country2 == null) {
                    Guozhan.instance.logger.warning("[战争系统] 无法找到战争 $warId 的国家")
                    continue
                }

                timeoutWars.add(Pair(country1, country2))
            }
        }

        // 结束超时的战争
        for ((country1, country2) in timeoutWars) {
            endWar(country1, country2, null)
            Guozhan.instance.logger.info("[战争系统] 战争超时自动结束: ${country1.name} vs ${country2.name}")
        }
    }

    /**
     * 获取指定国家的所有战争对手
     */
    fun getWarOpponents(country: Country): List<Country> {
        val opponents = mutableListOf<Country>()

        for (otherCountry in CountryManager.countries.values) {
            if (otherCountry.id == country.id) continue
            if (isAtWar(country, otherCountry)) {
                opponents.add(otherCountry)
            }
        }

        return opponents
    }

    /**
     * 获取战争持续时间（毫秒）
     */
    fun getWarDuration(country1: Country, country2: Country): Long {
        val warId = getWarId(country1, country2)
        val startTime = warStartTimes[warId] ?: return 0L
        return System.currentTimeMillis() - startTime
    }

    /**
     * 格式化战争持续时间为可读字符串
     */
    fun formatWarDuration(duration: Long): String {
        val seconds = duration / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 0 -> "${days}天${hours % 24}小时"
            hours > 0 -> "${hours}小时${minutes % 60}分钟"
            minutes > 0 -> "${minutes}分钟${seconds % 60}秒"
            else -> "${seconds}秒"
        }
    }

    /**
     * GM模式：手动开始战争
     */
    fun startWarGM(country1: Country, country2: Country) {
        val warId = getWarId(country1, country2)
        val warStartTime = System.currentTimeMillis()

        warStartTimes[warId] = warStartTime

        // 设置战争开始时间到外交关系
        val relation = DiplomacyManager.getRelation(country1, country2)
        relation.warStartTime = warStartTime

        // 更新外交关系为战争状态
        DiplomacyManager.updateRelation(country1, country2, RelationType.WAR)

        // 启动战争积分显示和计算
        WarScoreBossBarManager.startWarScoreDisplay(country1, country2, warStartTime)
        startWarScoreCalculationTask(country1, country2)

        // 广播战争开始消息（带GM标识）
        val message = "§c§l[GM模式] 战争爆发! §f${country1.name} §c与 §f${country2.name} §c进入战争状态"
        Bukkit.getOnlinePlayers().forEach { player ->
            player.sendMessage(message)
        }

        Guozhan.instance.logger.info("[GM模式] 手动触发战争: ${country1.name} vs ${country2.name}, 开始时间: $warStartTime")
    }

    /**
     * GM模式：手动结束战争
     */
    fun endWarGM(country1: Country, country2: Country, gmSender: CommandSender?) {
        val warId = getWarId(country1, country2)
        warStartTimes.remove(warId)

        // 停止战争积分显示和计算
        val warScoreResult = WarScoreBossBarManager.endWarScoreDisplay(country1, country2)
        stopWarScoreCalculationTask(country1, country2)

        // 更新外交关系为敌对状态
        DiplomacyManager.updateRelation(country1, country2, RelationType.HOSTILE)

        // 确定胜利者（使用积分结果）
        val winner = warScoreResult?.winner

        // 给予胜利者国家经济奖励
        if (winner != null) {
            val reward = DiplomacyConfig.getWarVictoryReward()
            winner.economyPoints += reward

            try {
                winner.save()
                Guozhan.instance.logger.info("[GM模式] ${winner.name} 获得战争胜利奖励: $reward 经济点数（已持久化）")
            } catch (e: Exception) {
                Guozhan.instance.logger.severe("[GM模式] 保存战争奖励失败: ${e.message}")
                e.printStackTrace()
            }
        }

        // 广播战争结束消息（带GM标识）
        val message = if (winner != null) {
            val loserName = if (winner.id == country1.id) country2.name else country1.name
            "§6§l[GM模式] 战争结束! §f${winner.name} §6战胜 §f$loserName"
        } else {
            "§6§l[GM模式] 战争结束! §f${country1.name} §6与 §f${country2.name} §6的战争以平局告终"
        }

        Bukkit.getOnlinePlayers().forEach { player ->
            player.sendMessage(message)
        }

        gmSender?.sendMessage("§a已手动结束战争: ${country1.name} vs ${country2.name}")
        Guozhan.instance.logger.info("[GM模式] 手动结束战争: ${country1.name} vs ${country2.name}, 胜利者: ${winner?.name ?: "无"}")
    }

    /**
     * 获取所有活跃战争
     * @return Map<战争ID, Pair<国家1, 国家2>>
     */
    fun getAllActiveWars(): Map<String, Pair<Country, Country>> {
        val activeWars = mutableMapOf<String, Pair<Country, Country>>()

        for ((warId, _) in warStartTimes) {
            val countryIds = warId.split("_")
            if (countryIds.size != 2) continue

            val country1 = CountryManager.getCountryById(UUID.fromString(countryIds[0]))
            val country2 = CountryManager.getCountryById(UUID.fromString(countryIds[1]))

            if (country1 != null && country2 != null) {
                activeWars[warId] = Pair(country1, country2)
            }
        }

        return activeWars
    }

    /**
     * 启动战争积分计算任务
     */
    private fun startWarScoreCalculationTask(country1: Country, country2: Country) {
        val warId = getWarId(country1, country2)
        stopWarScoreCalculationTask(country1, country2)

        val task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            Guozhan.instance,
            { _ -> calculateWarScore(country1, country2) },
            1200L, // 1分钟延迟
            1200L  // 每1分钟执行一次
        )
        warScoreCalculationTasks[warId] = task

        Guozhan.instance.logger.info("[战争系统] 启动战争积分计算任务: $warId")
    }

    /**
     * 停止战争积分计算任务
     */
    private fun stopWarScoreCalculationTask(country1: Country, country2: Country) {
        val warId = getWarId(country1, country2)
        val task = warScoreCalculationTasks.remove(warId)

        if (task != null && !task.isCancelled) {
            task.cancel()
            Guozhan.instance.logger.info("[战争系统] 停止战争积分计算任务: $warId")
        }
    }

    /**
     * 计算战争积分（基于核心区域领土控制）
     * 核心区域定义：X和Z坐标都在 [-64, 63] 范围内
     */
    private fun calculateWarScore(country1: Country, country2: Country) {
        val coreRegionMinX = -64
        val coreRegionMaxX = 63
        val coreRegionMinZ = -64
        val coreRegionMaxZ = 63

        var country1Score = 0
        var country2Score = 0

        for (territory in TerritoryManager.territories.values) {
            if (territory.x in coreRegionMinX..coreRegionMaxX &&
                territory.z in coreRegionMinZ..coreRegionMaxZ) {
                when (territory.owner?.id) {
                    country1.id -> country1Score++
                    country2.id -> country2Score++
                }
            }
        }

        WarScoreBossBarManager.updateWarScore(country1, country2, country1Score - country2Score)
    }
}
