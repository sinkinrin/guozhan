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
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 战争管理器，处理国家间战争状态的特殊逻辑
 */
object WarManager {
    // 战争状态缓存，记录战争开始时间
    private val warStartTimes = ConcurrentHashMap<String, Long>()
    
    // 战争状态冷却时间（毫秒）
    private const val WAR_COOLDOWN = 24 * 60 * 60 * 1000L // 24小时
    
    // 战争状态下的伤害倍率
    private const val WAR_DAMAGE_MULTIPLIER = 1.5
    
    /**
     * 初始化战争管理器
     */
    fun initialize() {
        Guozhan.instance.logger.info("正在初始化战争管理器...")
        loadWarStates()
    }
    
    /**
     * 加载所有战争状态
     */
    private fun loadWarStates() {
        // 清空缓存
        warStartTimes.clear()

        // 获取所有国家
        val countries = CountryManager.countries.values

        // 遍历所有国家对，检查战争状态
        for (country1 in countries) {
            for (country2 in countries) {
                if (country1.id != country2.id) {
                    val relation = DiplomacyManager.getRelation(country1, country2)
                    if (relation.relationType == RelationType.WAR) {
                        // 🔧 修复问题2：从数据库读取战争开始时间
                        val warId = getWarId(country1, country2)
                        val warStartTime = relation.warStartTime ?: System.currentTimeMillis()
                        warStartTimes[warId] = warStartTime

                        // 如果数据库中没有战争开始时间，设置并保存
                        if (relation.warStartTime == null) {
                            relation.warStartTime = warStartTime
                            relation.save()
                            Guozhan.instance.logger.warning("[战争系统] 战争 $warId 缺少开始时间，已设置为当前时间")
                        }
                    }
                }
            }
        }

        Guozhan.instance.logger.info("已加载 ${warStartTimes.size} 个战争状态")
    }
    
    /**
     * 获取战争ID（用于缓存）
     */
    private fun getWarId(country1: Country, country2: Country): String {
        // 确保ID顺序一致，避免重复
        val ids = listOf(country1.id, country2.id).sorted()
        return "${ids[0]}_${ids[1]}"
    }
    
    /**
     * 开始战争
     */
    fun startWar(country1: Country, country2: Country) {
        // 🔧 修复问题1：检查是否已经处于战争状态，避免递归调用
        val currentRelation = DiplomacyManager.getRelation(country1, country2)
        if (currentRelation.relationType == RelationType.WAR) {
            Guozhan.instance.logger.info("[战争系统] ${country1.name} 与 ${country2.name} 已经处于战争状态，跳过重复处理")
            return
        }

        val warId = getWarId(country1, country2)
        val warStartTime = System.currentTimeMillis()
        warStartTimes[warId] = warStartTime

        // 🔧 修复问题2：设置战争开始时间到外交关系
        currentRelation.warStartTime = warStartTime

        // 更新外交关系为战争状态
        DiplomacyManager.updateRelation(country1, country2, RelationType.WAR)

        // 广播战争开始消息
        val message = "§c§l战争爆发! §f${country1.name} §c与 §f${country2.name} §c进入战争状态!"
        Bukkit.getOnlinePlayers().forEach { player ->
            player.sendMessage(message)
        }

        Guozhan.instance.logger.info("[战争系统] 战争开始: ${country1.name} vs ${country2.name}, 开始时间: $warStartTime")
    }
    
    /**
     * 结束战争
     */
    fun endWar(country1: Country, country2: Country, winnerCountry: Country?) {
        // 🔧 修复问题1：检查是否处于战争状态，避免递归调用
        val currentRelation = DiplomacyManager.getRelation(country1, country2)
        if (currentRelation.relationType != RelationType.WAR) {
            Guozhan.instance.logger.info("[战争系统] ${country1.name} 与 ${country2.name} 不处于战争状态，跳过重复处理")
            return
        }

        val warId = getWarId(country1, country2)
        warStartTimes.remove(warId)

        // 🔧 修复问题2：清除战争开始时间
        currentRelation.warStartTime = null

        // 更新外交关系为敌对状态
        DiplomacyManager.updateRelation(country1, country2, RelationType.HOSTILE)

        // 广播战争结束消息
        val message = if (winnerCountry != null) {
            "§6战争结束! §f${winnerCountry.name} §6战胜了 §f${if (winnerCountry.id == country1.id) country2.name else country1.name}§6!"
        } else {
            "§6战争结束! §f${country1.name} §6与 §f${country2.name} §6的战争已经结束!"
        }

        Bukkit.getOnlinePlayers().forEach { player ->
            player.sendMessage(message)

            // 应用战争胜利/失败效果
            if (winnerCountry != null) {
                val user = player.user()
                val playerCountry = user.country

                if (playerCountry != null) {
                    if (playerCountry.id == winnerCountry.id) {
                        // 胜利方玩家获得胜利效果
                        WarEffects.applyVictoryEffects(player)
                    } else if (playerCountry.id == (if (winnerCountry.id == country1.id) country2.id else country1.id)) {
                        // 失败方玩家获得失败效果
                        WarEffects.applyDefeatEffects(player)
                    }
                }
            }
        }

        Guozhan.instance.logger.info("[战争系统] 战争结束: ${country1.name} vs ${country2.name}, 胜利者: ${winnerCountry?.name ?: "无"}")
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
     * 处理玩家死亡事件
     */
    fun handlePlayerDeath(event: PlayerDeathEvent) {
        val killed = event.entity
        val killer = killed.killer
        
        // 如果不是玩家击杀，则不处理
        if (killer == null || killer !is Player) return
        
        val killedUser = killed.user()
        val killerUser = killer.user()
        
        val killedCountry = killedUser.country
        val killerCountry = killerUser.country
        
        // 如果任一玩家不属于国家，则不处理
        if (killedCountry == null || killerCountry == null) return
        
        // 检查两个国家是否处于战争状态
        if (isAtWar(killedCountry, killerCountry)) {
            // 在战争中的击杀，给予击杀者国家经济奖励
            val reward = DiplomacyConfig.getWarKillReward()
            killerCountry.economyPoints += reward

            // 🔧 修复问题3：持久化经济点数到数据库
            try {
                killerCountry.save()
                Guozhan.instance.logger.info("[战争击杀] ${killerCountry.name} 击杀 ${killedCountry.name} 成员，获得 ${reward} 经济点数（已持久化）")
            } catch (e: Exception) {
                Guozhan.instance.logger.severe("[战争击杀] 保存经济点数失败: ${e.message}")
                e.printStackTrace()
            }

            // 通知击杀者
            killer.sendMessage("§6战争击杀! §f你击杀了敌对国家的 §e${killed.name}§f, 你的国家获得了 §a$reward §f经济点数奖励!")

            // 广播战争击杀消息
            val message = "§c战争消息: §f${killerCountry.name} §f的 §e${killer.name} §f在战争中击杀了 §f${killedCountry.name} §f的 §e${killed.name}§f!"
            Bukkit.getOnlinePlayers().forEach { player ->
                player.sendMessage(message)
            }
        }
    }
    
    /**
     * 检查战争是否已超时（超过冷却时间）
     */
    fun checkWarTimeout() {
        val currentTime = System.currentTimeMillis()
        val expiredWars = mutableListOf<String>()
        
        // 检查所有战争状态
        for ((warId, startTime) in warStartTimes) {
            if (currentTime - startTime > WAR_COOLDOWN) {
                expiredWars.add(warId)
            }
        }
        
        // 结束超时的战争
        for (warId in expiredWars) {
            try {
                val countryIds = warId.split("_")
                if (countryIds.size != 2) {
                    Guozhan.instance.logger.warning("无效的战争ID格式: $warId")
                    continue
                }

                val country1Id = UUID.fromString(countryIds[0])
                val country2Id = UUID.fromString(countryIds[1])
                val country1 = CountryManager.getCountryById(country1Id)
                val country2 = CountryManager.getCountryById(country2Id)

                if (country1 != null && country2 != null) {
                    endWar(country1, country2, null)
                } else {
                    Guozhan.instance.logger.warning("无法找到战争中的国家: $warId")
                }
            } catch (e: IllegalArgumentException) {
                Guozhan.instance.logger.warning("解析战争ID失败: $warId - ${e.message}")
            } catch (e: Exception) {
                Guozhan.instance.logger.warning("处理超时战争时发生错误: $warId - ${e.message}")
            }
        }
    }
    
    /**
     * 获取国家的所有战争对手
     */
    fun getWarOpponents(country: Country): List<Country> {
        return CountryManager.countries.values.filter { other ->
            other.id != country.id && isAtWar(country, other)
        }
    }
    
    /**
     * 获取战争持续时间（毫秒）
     */
    fun getWarDuration(country1: Country, country2: Country): Long {
        val warId = getWarId(country1, country2)
        val startTime = warStartTimes[warId] ?: return 0
        return System.currentTimeMillis() - startTime
    }
    
    /**
     * 格式化战争持续时间
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
     * GM模式：手动触发战争（绕过时间限制）
     */
    fun startWarGM(country1: Country, country2: Country) {
        val warId = getWarId(country1, country2)
        val warStartTime = System.currentTimeMillis()
        warStartTimes[warId] = warStartTime

        // 🔧 修复问题2：设置战争开始时间到外交关系
        val relation = DiplomacyManager.getRelation(country1, country2)
        relation.warStartTime = warStartTime

        // 更新外交关系为战争状态
        DiplomacyManager.updateRelation(country1, country2, RelationType.WAR)

        // 广播战争开始消息（带GM标识）
        val message = "§c§l[GM模式] 战争爆发! §f${country1.name} §c与 §f${country2.name} §c进入战争状态!"
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

        // 🔧 修复问题2：清除战争开始时间
        val relation = DiplomacyManager.getRelation(country1, country2)
        relation.warStartTime = null

        // 更新外交关系为敌对状态
        DiplomacyManager.updateRelation(country1, country2, RelationType.HOSTILE)

        // 广播战争结束消息（带GM标识）
        val message = "§6[GM模式] 战争结束! §f${country1.name} §6与 §f${country2.name} §6的战争已被管理员强制结束!"
        Bukkit.getOnlinePlayers().forEach { player ->
            player.sendMessage(message)
        }

        val gmName = gmSender?.name ?: "系统"
        Guozhan.instance.logger.info("[GM模式] 管理员 $gmName 强制结束战争: ${country1.name} vs ${country2.name}")
    }

    /**
     * 获取所有活跃战争
     */
    fun getAllActiveWars(): Map<String, Pair<Country, Country>> {
        val activeWars = mutableMapOf<String, Pair<Country, Country>>()

        for ((warId, _) in warStartTimes) {
            try {
                val countryIds = warId.split("_")
                if (countryIds.size != 2) continue

                val country1Id = UUID.fromString(countryIds[0])
                val country2Id = UUID.fromString(countryIds[1])
                val country1 = CountryManager.getCountryById(country1Id)
                val country2 = CountryManager.getCountryById(country2Id)

                if (country1 != null && country2 != null) {
                    activeWars[warId] = Pair(country1, country2)
                }
            } catch (e: Exception) {
                // 跳过无效的战争ID
                continue
            }
        }

        return activeWars
    }
}