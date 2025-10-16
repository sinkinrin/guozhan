package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.config.Config
import cn.lcofficial.guozhan.data.Profession
import cn.lcofficial.guozhan.data.User
import cn.lcofficial.guozhan.pluginLogger
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

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

    /**
     * 检查玩家是否可以设置职业
     * @param user 用户
     * @return 是否可以设置
     */
    fun canSetProfession(user: User): Boolean {
        val country = user.country ?: return false
        val unlockDelayMs = Config.Profession.unlockDelayHours * 60 * 60 * 1000L
        val countryCreateTime = country.createTime ?: return false

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

    fun upgradeProfession(user: User) {
        if (user.profession == null) return

        if (user.professionLevel < 2) {
            user.professionLevel = 2
            user.professionSetTime = System.currentTimeMillis() // 更新升级时间
            user.save()

            val player = Bukkit.getPlayer(user.uniqueId)
            player?.let { applyProfessionEffects(it, user.profession!!, 2) }

            pluginLogger.info("玩家 ${user.name} 升级职业 ${user.profession!!.name} 至2级")
        }
    }
    
    fun applyProfessionEffects(player: Player, profession: Profession, level: Int) {
        // 清除之前的职业效果
        player.activePotionEffects.forEach { effect ->
            professionEffects.values.forEach { effectType ->
                if (effectType != null && effect.type == effectType) {
                    player.removePotionEffect(effectType)
                }
            }
        }
        
        // 应用新的职业效果
        val effectType = professionEffects[profession] ?: return
        
        val duration = Int.MAX_VALUE // 永久效果
        val amplifier = when (profession) {
            Profession.SCOUT -> if (level == 1) 1 else 4
            Profession.LEAPER -> if (level == 1) 2 else 4
            else -> level - 1
        }
        
        player.addPotionEffect(PotionEffect(effectType, duration, amplifier, false, false))
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
    
    fun canSetProfession(country: cn.lcofficial.guozhan.data.Country): Boolean {
        val hoursSinceCreation = (System.currentTimeMillis() - country.createTime) / (1000 * 60 * 60)
        return hoursSinceCreation >= 2
    }
    
    fun getUpgradeCost(level: Int): Int {
        return when (level) {
            1 -> 50 // 升级到2级需要50钻石
            else -> 0
        }
    }
}