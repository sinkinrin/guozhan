package cn.lcofficial.guozhan.config

import cn.lcofficial.guozhan.Guozhan
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * 外交系统配置类
 */
object DiplomacyConfig {
    private lateinit var config: YamlConfiguration
    private lateinit var plugin: Guozhan
    
    /**
     * 初始化配置
     */
    fun init(plugin: Guozhan) {
        this.plugin = plugin
        reload()
    }
    
    /**
     * 初始化配置
     */
    fun initialize() {
        val configFile = File(plugin.dataFolder, "diplomacy.yml")
        
        if (!configFile.exists()) {
            plugin.saveResource("diplomacy.yml", false)
        }
        
        config = YamlConfiguration.loadConfiguration(configFile)
    }
    
    /**
     * 重载配置
     */
    fun reload() {
        initialize()
    }
    
    /**
     * 获取关系变化冷却时间（秒）
     */
    fun getRelationCooldown(): Int {
        return config.getInt("relations.cooldown", 3600)
    }
    
    /**
     * 获取默认关系类型
     */
    fun getDefaultRelation(): String {
        return config.getString("relations.default", "NEUTRAL") ?: "NEUTRAL"
    }
    
    /**
     * 获取战争持续时间（小时）
     */
    fun getWarDuration(): Int {
        return config.getInt("war.duration", 24)
    }
    
    /**
     * 获取战争伤害倍率
     */
    fun getWarDamageMultiplier(): Double {
        return config.getDouble("war.damage_multiplier", 1.5)
    }
    
    /**
     * 获取战争击杀奖励（经济点数）
     */
    fun getWarKillReward(): Int {
        return config.getInt("war.kill_reward", 50)
    }
    
    /**
     * 获取战争胜利奖励（经济点数）
     */
    fun getWarVictoryReward(): Int {
        return config.getInt("war.victory_reward", 500)
    }
    
    /**
     * 获取战争失败惩罚（经济点数）
     */
    fun getWarDefeatPenalty(): Int {
        return config.getInt("war.defeat_penalty", 200)
    }
    
    /**
     * 获取胜利效果持续时间（分钟）
     */
    fun getVictoryEffectDuration(): Int {
        return config.getInt("war.effects.victory_duration", 30)
    }
    
    /**
     * 获取失败效果持续时间（分钟）
     */
    fun getDefeatEffectDuration(): Int {
        return config.getInt("war.effects.defeat_duration", 15)
    }
    
    /**
     * 获取领土战斗效果持续时间（秒）
     */
    fun getTerritoryCombatEffectDuration(): Int {
        return config.getInt("war.effects.territory_combat_duration", 10)
    }
    
    /**
     * 获取家园领土抗性效果等级
     */
    fun getHomeTerritoryResistanceLevel(): Int {
        return config.getInt("war.effects.home_territory.resistance_level", 0)
    }
    
    /**
     * 获取家园领土生命恢复效果等级
     */
    fun getHomeTerritoryRegenerationLevel(): Int {
        return config.getInt("war.effects.home_territory.regeneration_level", 0)
    }
    
    /**
     * 获取敌对领土虚弱效果等级
     */
    fun getEnemyTerritoryWeaknessLevel(): Int {
        return config.getInt("war.effects.enemy_territory.weakness_level", 0)
    }
    
    /**
     * 获取敌对领土缓慢效果等级
     */
    fun getEnemyTerritorySlownessLevel(): Int {
        return config.getInt("war.effects.enemy_territory.slowness_level", 0)
    }
    
    /**
     * 获取胜利力量效果等级
     */
    fun getVictoryStrengthLevel(): Int {
        return config.getInt("war.effects.victory.strength_level", 0)
    }
    
    /**
     * 获取胜利抗性效果等级
     */
    fun getVictoryResistanceLevel(): Int {
        return config.getInt("war.effects.victory.resistance_level", 0)
    }
    
    /**
     * 获取胜利生命恢复效果等级
     */
    fun getVictoryRegenerationLevel(): Int {
        return config.getInt("war.effects.victory.regeneration_level", 1)
    }
    
    /**
     * 获取失败虚弱效果等级
     */
    fun getDefeatWeaknessLevel(): Int {
        return config.getInt("war.effects.defeat.weakness_level", 0)
    }
    
    /**
     * 获取家园战斗力量效果等级
     */
    fun getHomeCombatStrengthLevel(): Int {
        return config.getInt("war.effects.combat.home_strength_level", 0)
    }
    
    /**
     * 获取家园战斗抗性效果等级
     */
    fun getHomeCombatResistanceLevel(): Int {
        return config.getInt("war.effects.combat.home_resistance_level", 0)
    }
}