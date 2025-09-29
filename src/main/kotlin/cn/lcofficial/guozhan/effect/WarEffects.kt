package cn.lcofficial.guozhan.effect

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.config.DiplomacyConfig
import cn.lcofficial.guozhan.data.Country
import cn.lcofficial.guozhan.manager.UserManager.user
import cn.lcofficial.guozhan.manager.WarManager
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

/**
 * 战争效果系统，处理战争期间的特殊效果
 */
object WarEffects {
    // 战争状态下的增益效果持续时间（秒）
    private const val WAR_BUFF_DURATION = 30
    
    // 战争状态下的增益效果检查间隔（ticks）
    private const val WAR_BUFF_CHECK_INTERVAL = 20 * 60 // 1分钟 = 20 ticks/s * 60s
    
    /**
     * 初始化战争效果系统
     */
    fun initialize() {
        Guozhan.instance.logger.info("正在初始化战争效果系统...")
        startWarEffectsTask()
    }
    
    /**
     * 启动战争效果任务
     */
    private fun startWarEffectsTask() {
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(Guozhan.instance, { _ ->
            applyWarEffectsToAllPlayers()
        }, WAR_BUFF_CHECK_INTERVAL.toLong(), WAR_BUFF_CHECK_INTERVAL.toLong())
    }
    
    /**
     * 为所有处于战争状态的玩家应用战争效果
     */
    private fun applyWarEffectsToAllPlayers() {
        for (player in Bukkit.getOnlinePlayers()) {
            val user = player.user()
            val country = user.country ?: continue
            
            // 检查玩家的国家是否处于战争状态
            val warOpponents = WarManager.getWarOpponents(country)
            if (warOpponents.isNotEmpty()) {
                applyWarEffects(player, country)
            }
        }
    }
    
    /**
     * 为玩家应用战争效果
     */
    fun applyWarEffects(player: Player, country: Country) {
        // 在自己的领土上获得增益效果
        val location = player.location
        val chunk = location.chunk
        val territory = cn.lcofficial.guozhan.manager.TerritoryManager.getTerritoryBlock(chunk.x, chunk.z, chunk.world.name)
        val territoryOwner = territory?.owner

        if (territoryOwner?.id == country.id) {
            // 在自己的领土上获得增益效果
            applyHomeTerrainBuffs(player)
        } else {
            // 检查是否在敌对领土上
            val warOpponents = WarManager.getWarOpponents(country)
            if (territoryOwner != null && warOpponents.any { it.id == territoryOwner.id }) {
                // 在敌对领土上获得特殊效果
                applyEnemyTerrainEffects(player)
            }
        }
    }
    
    /**
     * 在自己的领土上应用增益效果
     */
    private fun applyHomeTerrainBuffs(player: Player) {
        // 在自己的领土上获得力量和抗性效果
        player.addPotionEffect(PotionEffect(PotionEffectType.STRENGTH, WAR_BUFF_DURATION * 20, 0, false, true, true))
        player.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, WAR_BUFF_DURATION * 20, 0, false, true, true))

        // 通知玩家（仅在首次获得效果时）
        if (!player.hasPotionEffect(PotionEffectType.STRENGTH)) {
            player.sendMessage("§a你在自己的领土上获得了战争增益效果！")
        }
    }
    
    /**
     * 在敌对领土上应用特殊效果
     */
    private fun applyEnemyTerrainEffects(player: Player) {
        // 在敌对领土上获得速度效果，但也会获得虚弱效果
        player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, WAR_BUFF_DURATION * 20, 0, false, true, true))
        player.addPotionEffect(PotionEffect(PotionEffectType.WEAKNESS, WAR_BUFF_DURATION * 20, 0, false, true, true))
        
        // 通知玩家（仅在首次获得效果时）
        if (!player.hasPotionEffect(PotionEffectType.WEAKNESS)) {
            player.sendMessage("§c你正在敌对领土上，获得了速度但也受到了虚弱效果！")
        }
    }
    
    /**
     * 应用战争胜利效果
     */
    fun applyVictoryEffects(player: Player) {
        // 胜利方获得积极效果
        val strengthLevel = DiplomacyConfig.getVictoryStrengthLevel()
        val resistanceLevel = DiplomacyConfig.getVictoryResistanceLevel()
        val regenerationLevel = DiplomacyConfig.getVictoryRegenerationLevel()
        val duration = 20 * 60 * DiplomacyConfig.getVictoryEffectDuration() // 配置的分钟数
        
        if (strengthLevel > 0) {
            player.addPotionEffect(PotionEffect(PotionEffectType.STRENGTH, duration, strengthLevel - 1, false, true))
        }

        if (resistanceLevel > 0) {
            player.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, duration, resistanceLevel - 1, false, true))
        }
        
        if (regenerationLevel > 0) {
            player.addPotionEffect(PotionEffect(PotionEffectType.REGENERATION, duration / 10, regenerationLevel - 1, false, true)) // 持续时间为胜利效果的1/10
        }
        
        // 播放胜利音效
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f)
        
        // 发送胜利消息
        player.sendMessage("§a§l战争胜利! §f你的国家赢得了战争，获得了临时增益效果!")
        
        // 给予经济奖励
        val user = player.user()
        val country = user.country
        if (country != null) {
            country.economyPoints += DiplomacyConfig.getWarVictoryReward()
        }
    }
    
    /**
     * 应用战争失败效果
     */
    fun applyDefeatEffects(player: Player) {
        // 失败方获得较轻的负面效果
        val weaknessLevel = DiplomacyConfig.getDefeatWeaknessLevel()
        val duration = 20 * 60 * DiplomacyConfig.getDefeatEffectDuration() // 配置的分钟数
        
        if (weaknessLevel > 0) {
            player.addPotionEffect(PotionEffect(PotionEffectType.WEAKNESS, duration, weaknessLevel - 1, false, true))
        }
        
        // 添加轻微的缓慢效果
        player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, duration / 2, 0, false, true)) // 持续时间为失败效果的一半
        
        // 播放失败音效
        player.playSound(player.location, Sound.ENTITY_WITHER_DEATH, 0.5f, 0.8f)
        
        // 发送失败消息
        player.sendMessage("§c§l战争失败! §f你的国家在战争中失败，将承受短暂的负面效果。")
        
        // 扣除经济惩罚
        val user = player.user()
        val country = user.country
        if (country != null) {
            country.economyPoints -= DiplomacyConfig.getWarDefeatPenalty()
            if (country.economyPoints < 0) {
                country.economyPoints = 0
            }
        }
    }
}