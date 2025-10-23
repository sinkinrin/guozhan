package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.config.Config
import cn.lcofficial.guozhan.data.Profession
import cn.lcofficial.guozhan.data.User
import cn.lcofficial.guozhan.pluginLogger
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

object ProfessionManager {
    private val professionEffects = mapOf(
        Profession.SCOUT to PotionEffectType.SPEED,
        Profession.CRAFTSMAN to PotionEffectType.HASTE,
        Profession.BERSERKER to PotionEffectType.STRENGTH,
        Profession.GUARDIAN to PotionEffectType.RESISTANCE,
        Profession.LEAPER to PotionEffectType.JUMP_BOOST,
        Profession.PRIEST to PotionEffectType.REGENERATION,
        Profession.CONQUEROR to null // 特殊处理
    )

    // 🔧 v1.3.49: 修复职业amplifier溢出问题 - 使用独立存储跟踪职业效果
    private val playerProfessionEffects = ConcurrentHashMap<UUID, Set<PotionEffectType>>()

    /**
     * 检查玩家是否可以设置职业
     * @param user 用户
     * @return 是否可以设置
     */
    fun canSetProfession(user: User): Boolean {
        val country = user.country ?: return false
        val unlockDelayMs = Config.Profession.unlockDelayHours * 60 * 60 * 1000L
        // v1.3.19修复：createTime是非空Long类型，移除不必要的Elvis操作符
        val countryCreateTime = country.createTime

        return System.currentTimeMillis() - countryCreateTime >= unlockDelayMs
    }

    /**
     * 检查玩家是否可以升级职业
     * @param user 用户
     * @return 是否可以升级
     */
    fun canUpgradeProfession(user: User): Boolean {
        // 检查是否有职业
        if (user.profession == null) return false

        // 检查是否已达到最高等级
        if (user.professionLevel >= 2) return false

        // 检查是否已设置职业时间戳
        val professionSetTime = user.professionSetTime
        if (professionSetTime == null) {
            // 如果没有时间戳，说明是旧数据，允许升级但记录警告
            pluginLogger.warning("玩家 ${user.name} 的职业设置时间为空，允许升级但建议检查数据")
            return true
        }

        // 检查是否已过24小时冷却时间
        val upgradeDelayMs = Config.Profession.upgradeDelayHours * 60 * 60 * 1000L
        val currentTime = System.currentTimeMillis()
        val timeSinceSet = currentTime - professionSetTime

        return timeSinceSet >= upgradeDelayMs
    }

    /**
     * 获取职业升级剩余冷却时间（毫秒）
     * @param user 用户
     * @return 剩余冷却时间，如果可以升级则返回0
     */
    fun getUpgradeCooldownRemaining(user: User): Long {
        val professionSetTime = user.professionSetTime ?: return 0L
        val upgradeDelayMs = Config.Profession.upgradeDelayHours * 60 * 60 * 1000L
        val currentTime = System.currentTimeMillis()
        val timeSinceSet = currentTime - professionSetTime
        val remaining = upgradeDelayMs - timeSinceSet

        return if (remaining > 0) remaining else 0L
    }

    /**
     * 获取职业升级成本
     * @return 升级成本（钻石数量）
     */
    fun getUpgradeCost(): Int {
        return Config.Profession.upgradeCost
    }
    
    fun setProfession(user: User, profession: Profession) {
        user.profession = profession
        user.professionLevel = 1
        user.professionSetTime = System.currentTimeMillis() // 记录设置时间
        user.save()

        val player = Bukkit.getPlayer(user.uniqueId)
        player?.let { applyProfessionEffects(it, profession, 1) }

        pluginLogger.info("玩家 ${user.name} 设置职业为 ${profession.name}，24小时后可升级")
    }

    /**
     * 🔧 v1.3.47: 修复职业升级线程阻塞问题 - 完全异步化，避免使用CompletableFuture.get()阻塞线程
     */
    fun upgradeProfession(user: User, callback: (Boolean) -> Unit = {}) {
        if (user.profession == null) {
            callback(false)
            return
        }
        if (user.professionLevel >= 2) {
            callback(false)
            return
        }

        // 🔧 v1.3.40: 修复职业升级未扣费漏洞 - 添加资源扣除逻辑
        val country = user.country
        if (country == null) {
            pluginLogger.warning("玩家 ${user.name} 尝试升级职业但没有国家")
            callback(false)
            return
        }

        // 获取升级成本（钻石）
        val upgradeCost = getUpgradeCost()

        // 检查国库钻石是否足够
        if (country.diamond < upgradeCost) {
            pluginLogger.info("玩家 ${user.name} 升级职业失败：国库钻石不足（需要${upgradeCost}，当前${country.diamond}）")
            callback(false)
            return
        }

        // 🔧 v1.3.47: 完全异步化，在GlobalRegionScheduler中执行状态修改

        cn.lcofficial.guozhan.util.run {
            try {
                // 再次检查资源（防止竞态条件）
                if (country.diamond < upgradeCost) {
                    pluginLogger.info("玩家 ${user.name} 升级职业失败：国库钻石不足（竞态检查，需要${upgradeCost}，当前${country.diamond}）")
                    callback(false)
                    return@run
                }

                // 再次检查职业状态（防止重复升级）
                if (user.professionLevel >= 2) {
                    pluginLogger.info("玩家 ${user.name} 升级职业失败：已达到最高等级")
                    callback(false)
                    return@run
                }

                // 扣除国库钻石
                country.diamond -= upgradeCost
                country.save()

                // 升级职业
                user.professionLevel = 2
                user.professionSetTime = System.currentTimeMillis() // 更新升级时间
                user.save()

                // 在EntityScheduler中应用药水效果
                val player = Bukkit.getPlayer(user.uniqueId)
                player?.let { applyProfessionEffects(it, user.profession!!, 2) }

                pluginLogger.info("玩家 ${user.name} 升级职业 ${user.profession!!.name} 至2级，消耗${upgradeCost}钻石")
                callback(true)
            } catch (e: Exception) {
                pluginLogger.severe("职业升级过程中出错: ${e.message}")
                e.printStackTrace()
                callback(false)
            }
        }
    }
    
    /**
     * 应用职业效果（线程安全版本）
     * 🔧 v1.3.38修复：Folia线程安全 - 将药水效果操作包装在EntityScheduler中
     * @param player 玩家
     * @param profession 职业
     * @param level 职业等级
     */
    fun applyProfessionEffects(player: Player, profession: Profession, level: Int) {
        // 🔧 v1.3.38修复：使用EntityScheduler确保药水效果操作在正确的实体线程中执行
        // 参考TechEffectManager的实现模式
        player.scheduler.run(cn.lcofficial.guozhan.Guozhan.instance, { _ ->
            try {
                applyProfessionEffectsSafe(player, profession, level)
            } catch (e: Exception) {
                pluginLogger.warning("应用玩家 ${player.name} 的职业效果时出错: ${e.message}")
            }
        }, null)
    }

    /**
     * 线程安全的职业效果应用方法
     * 🔧 v1.3.49: 修复职业amplifier溢出问题 - 使用独立存储跟踪职业效果
     * @param player 玩家
     * @param profession 职业
     * @param level 职业等级
     */
    private fun applyProfessionEffectsSafe(player: Player, profession: Profession, level: Int) {
        // 🔧 v1.3.49: 修复职业amplifier溢出问题 - 先清除之前的职业效果
        clearProfessionEffectsSafe(player)

        // 🔧 v1.3.49: 修复职业amplifier溢出问题 - 应用新的职业效果
        val effectType = professionEffects[profession] ?: return

        val duration = Int.MAX_VALUE // 永久效果
        val amplifier = when (profession) {
            Profession.SCOUT -> if (level == 1) 1 else 4
            Profession.LEAPER -> if (level == 1) 2 else 4
            else -> level - 1
        }

        // 🔧 v1.3.49: 修复职业amplifier溢出问题 - 使用原始amplifier值，不再使用+100标记
        val existingEffect = player.getPotionEffect(effectType)
        val finalAmplifier = if (existingEffect != null) {
            // 已有科技效果，合并amplifier（科技效果 + 职业效果）
            maxOf(existingEffect.amplifier, amplifier)
        } else {
            // 没有科技效果，使用职业amplifier
            amplifier
        }

        player.addPotionEffect(PotionEffect(effectType, duration, finalAmplifier, false, false))

        // 🔧 v1.3.49: 记录职业效果到独立存储
        val currentEffects = playerProfessionEffects[player.uniqueId] ?: emptySet()
        playerProfessionEffects[player.uniqueId] = currentEffects + effectType

        cn.lcofficial.guozhan.Guozhan.instance.logger.info("🔧 [职业效果] 已为玩家 ${player.name} 应用职业 ${profession.name} 等级 $level 的效果 ${effectType.name} (amplifier: $finalAmplifier)")
    }

    /**
     * 🔧 v1.3.49: 修复职业amplifier溢出问题 - 清除玩家的职业效果（线程安全版本）
     * 此方法必须在EntityScheduler中调用，确保线程安全
     * @param player 玩家
     */
    fun clearProfessionEffects(player: Player) {
        player.scheduler.run(cn.lcofficial.guozhan.Guozhan.instance, { _ ->
            try {
                clearProfessionEffectsSafe(player)
            } catch (e: Exception) {
                pluginLogger.warning("清除玩家 ${player.name} 的职业效果时出错: ${e.message}")
            }
        }, null)
    }

    /**
     * 🔧 v1.3.49: 修复职业amplifier溢出问题 - 线程安全的职业效果清除方法
     * @param player 玩家
     */
    private fun clearProfessionEffectsSafe(player: Player) {
        // 🔧 v1.3.49: 修复职业amplifier溢出问题 - 使用独立存储跟踪职业效果
        val professionEffectsForPlayer = playerProfessionEffects[player.uniqueId] ?: emptySet()

        professionEffectsForPlayer.forEach { effectType ->
            try {
                // 检查玩家是否有该效果
                val existingEffect = player.getPotionEffect(effectType)
                if (existingEffect != null && existingEffect.duration == Int.MAX_VALUE) {
                    // 移除当前效果
                    player.removePotionEffect(effectType)

                    // 🔧 v1.3.49: 检查是否需要恢复科技效果
                    // 通过TechEffectManager获取科技效果的amplifier
                    val techAmplifier = getTechEffectAmplifier(player, effectType)
                    if (techAmplifier >= 0) {
                        // 恢复科技效果
                        player.addPotionEffect(PotionEffect(effectType, Int.MAX_VALUE, techAmplifier, false, false))
                        cn.lcofficial.guozhan.Guozhan.instance.logger.info("🔧 [职业效果] 恢复玩家 ${player.name} 的科技效果: ${effectType.name} (amplifier: $techAmplifier)")
                    }

                    cn.lcofficial.guozhan.Guozhan.instance.logger.info("🔧 [职业效果] 清除玩家 ${player.name} 的职业效果: ${effectType.name}")
                }
            } catch (e: Exception) {
                cn.lcofficial.guozhan.Guozhan.instance.logger.warning("清除玩家 ${player.name} 的职业药水效果 ${effectType.name} 时出错: ${e.message}")
            }
        }

        // 清除职业效果记录
        playerProfessionEffects.remove(player.uniqueId)
    }

    /**
     * 🔧 v1.3.49: 获取科技效果的amplifier值
     * @param player 玩家
     * @param effectType 效果类型
     * @return 科技效果的amplifier值，如果没有科技效果返回-1
     */
    private fun getTechEffectAmplifier(player: Player, effectType: PotionEffectType): Int {
        // 通过TechEffectManager获取科技效果
        return try {
            TechEffectManager.getTechEffectAmplifier(player, effectType)
        } catch (e: Exception) {
            -1 // 如果获取失败，返回-1表示没有科技效果
        }
    }

    fun getProfessionName(profession: Profession): String {
        return when (profession) {
            Profession.SCOUT -> "斥候"
            Profession.CRAFTSMAN -> "工匠"
            Profession.BERSERKER -> "狂战士"
            Profession.GUARDIAN -> "守护者"
            Profession.LEAPER -> "飞跃者"
            Profession.PRIEST -> "牧师"
            Profession.CONQUEROR -> "征服者"
        }
    }
    
    fun getProfessionDescription(profession: Profession): String {
        return when (profession) {
            Profession.SCOUT -> "获得迅捷效果"
            Profession.CRAFTSMAN -> "获得急迫效果"
            Profession.BERSERKER -> "获得力量效果"
            Profession.GUARDIAN -> "获得抗性提升效果"
            Profession.LEAPER -> "获得跳跃提升效果"
            Profession.PRIEST -> "获得生命恢复效果"
            Profession.CONQUEROR -> "圈地速度提升"
        }
    }
    
    // 🔧 v1.3.41: 修复职业解锁延迟硬编码 - 使用配置值而不是硬编码的2小时
    fun canSetProfession(country: cn.lcofficial.guozhan.data.Country): Boolean {
        val hoursSinceCreation = (System.currentTimeMillis() - country.createTime) / (1000 * 60 * 60)
        return hoursSinceCreation >= Config.Profession.unlockDelayHours
    }
    
    /**
     * 获取职业升级成本（按等级）
     * 🔧 v1.3.40: 修复职业成本不一致 - 从配置读取升级成本
     * @param level 当前等级
     * @return 升级成本（钻石数量）
     */
    fun getUpgradeCost(level: Int): Int {
        return when (level) {
            1 -> Config.Profession.upgradeCost // 升级到2级需要的钻石数（从配置读取）
            else -> 0 // 其他等级暂不支持升级
        }
    }
}