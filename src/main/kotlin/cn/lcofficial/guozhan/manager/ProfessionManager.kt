package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.data.Profession
import cn.lcofficial.guozhan.data.User
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
    
    fun setProfession(user: User, profession: Profession) {
        user.profession = profession
        user.professionLevel = 1
        user.save()
        
        val player = Bukkit.getPlayer(user.uniqueId)
        player?.let { applyProfessionEffects(it, profession, 1) }
    }
    
    fun upgradeProfession(user: User) {
        if (user.profession == null) return
        
        if (user.professionLevel < 2) {
            user.professionLevel = 2
            user.save()
            
            val player = Bukkit.getPlayer(user.uniqueId)
            player?.let { applyProfessionEffects(it, user.profession!!, 2) }
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