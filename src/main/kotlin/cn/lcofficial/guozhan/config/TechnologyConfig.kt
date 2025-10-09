package cn.lcofficial.guozhan.config

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.data.*
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * 科技系统配置管理器
 */
object TechnologyConfig : Configuration("technology.yml") {
    
    // 科技系统设置
    internal object Settings : StaticLazy {
        var researchTimeBase by long("settings.research_time_base", 3600000L) // 1小时
        var researchTimeMultiplier by double("settings.research_time_multiplier", 1.5)
        var maxConcurrentResearch by int("settings.max_concurrent_research", 1)
        var effectUpdateInterval by int("settings.effect_update_interval", 20) // tick - 最小值为1
        var enableResearchQueue by bool("settings.enable_research_queue", false)
        var autoCompleteResearch by bool("settings.auto_complete_research", true)
    }
    
    // 科技数据缓存
    private val technologiesCache = mutableMapOf<String, Technology>()
    
    override fun init(plugin: Guozhan) {
        Settings.init()
        super.init(plugin)
        loadTechnologies()
    }
    
    /**
     * 从配置文件加载所有科技数据
     */
    private fun loadTechnologies() {
        technologiesCache.clear()
        
        val technologiesSection = config.getConfigurationSection("technologies")
        if (technologiesSection == null) {
            Guozhan.instance.logger.warning("未找到科技配置节点，将创建默认科技")
            createDefaultTechnologies()
            return
        }
        
        for (techId in technologiesSection.getKeys(false)) {
            try {
                val techSection = technologiesSection.getConfigurationSection(techId)
                if (techSection != null) {
                    val technology = parseTechnology(techId, techSection)
                    technologiesCache[techId] = technology
                    Guozhan.instance.logger.info("已加载科技: ${technology.name} (${techId})")
                }
            } catch (e: Exception) {
                Guozhan.instance.logger.severe("加载科技 $techId 时出错: ${e.message}")
                e.printStackTrace()
            }
        }
        
        Guozhan.instance.logger.info("共加载了 ${technologiesCache.size} 个科技")
    }
    
    /**
     * 解析单个科技配置
     */
    private fun parseTechnology(techId: String, section: org.bukkit.configuration.ConfigurationSection): Technology {
        val name = section.getString("name") ?: techId
        val description = section.getString("description") ?: "无描述"
        val iconName = section.getString("icon") ?: "BOOK"
        val icon = try {
            Material.valueOf(iconName.uppercase())
        } catch (e: IllegalArgumentException) {
            Guozhan.instance.logger.warning("科技 $techId 的图标 $iconName 无效，使用默认图标 BOOK")
            Material.BOOK
        }
        
        val maxLevel = section.getInt("max_level", 1)
        val category = section.getString("category") ?: "basic"
        val enabled = section.getBoolean("enabled", true)
        
        // 解析前置科技
        val prerequisites = section.getStringList("prerequisites")
        
        // 解析成本
        val costs = mutableMapOf<Int, TechCost>()
        val costsSection = section.getConfigurationSection("costs")
        if (costsSection != null) {
            for (level in 1..maxLevel) {
                val levelSection = costsSection.getConfigurationSection(level.toString())
                if (levelSection != null) {
                    val gold = levelSection.getInt("gold", 0)
                    val diamond = levelSection.getInt("diamond", 0)
                    val territoryIncome = levelSection.getInt("territory_income", 0)
                    costs[level] = TechCost(gold, diamond, territoryIncome)
                }
            }
        }
        
        // 解析效果
        val effects = mutableMapOf<Int, List<TechEffect>>()
        val effectsSection = section.getConfigurationSection("effects")
        if (effectsSection != null) {
            for (level in 1..maxLevel) {
                val levelEffects = mutableListOf<TechEffect>()
                val levelSection = effectsSection.getConfigurationSection(level.toString())
                if (levelSection != null) {
                    for (effectKey in levelSection.getKeys(false)) {
                        val effectSection = levelSection.getConfigurationSection(effectKey)
                        if (effectSection != null) {
                            val effect = parseEffect(effectSection)
                            if (effect != null) {
                                levelEffects.add(effect)
                            }
                        }
                    }
                }
                if (levelEffects.isNotEmpty()) {
                    effects[level] = levelEffects
                }
            }
        }
        
        return Technology(
            id = techId,
            name = name,
            description = description,
            icon = icon,
            maxLevel = maxLevel,
            prerequisites = prerequisites,
            costs = costs,
            effects = effects,
            category = category,
            enabled = enabled
        )
    }
    
    /**
     * 解析效果配置
     */
    private fun parseEffect(section: org.bukkit.configuration.ConfigurationSection): TechEffect? {
        val typeString = section.getString("type") ?: return null
        val type = try {
            TechEffectType.valueOf(typeString.uppercase())
        } catch (e: IllegalArgumentException) {
            Guozhan.instance.logger.warning("无效的效果类型: $typeString")
            return null
        }
        
        val value = section.getDouble("value", 0.0)
        val duration = section.getLong("duration", -1L)
        
        // 解析额外数据
        val data = mutableMapOf<String, Any>()
        for (key in section.getKeys(false)) {
            if (key !in listOf("type", "value", "duration")) {
                val dataValue = section.get(key)
                if (dataValue != null) {
                    data[key] = dataValue
                }
            }
        }
        
        return TechEffect(type, value, duration, data)
    }
    
    /**
     * 创建默认科技配置
     */
    private fun createDefaultTechnologies() {
        // 基础农业科技
        config.set("technologies.farming_basic.name", "基础农业")
        config.set("technologies.farming_basic.description", "提升农作物产量和食物效果")
        config.set("technologies.farming_basic.icon", "WHEAT")
        config.set("technologies.farming_basic.max_level", 3)
        config.set("technologies.farming_basic.category", "basic")
        config.set("technologies.farming_basic.prerequisites", emptyList<String>())
        
        // 成本配置
        config.set("technologies.farming_basic.costs.1.gold", 500)
        config.set("technologies.farming_basic.costs.1.diamond", 5)
        config.set("technologies.farming_basic.costs.1.territory_income", 2)
        config.set("technologies.farming_basic.costs.2.gold", 1000)
        config.set("technologies.farming_basic.costs.2.diamond", 10)
        config.set("technologies.farming_basic.costs.2.territory_income", 4)
        config.set("technologies.farming_basic.costs.3.gold", 2000)
        config.set("technologies.farming_basic.costs.3.diamond", 20)
        config.set("technologies.farming_basic.costs.3.territory_income", 8)
        
        // 效果配置
        config.set("technologies.farming_basic.effects.1.saturation.type", "POTION_EFFECT")
        config.set("technologies.farming_basic.effects.1.saturation.effect", "SATURATION")
        config.set("technologies.farming_basic.effects.1.saturation.amplifier", 0)
        config.set("technologies.farming_basic.effects.1.saturation.value", 1.0)
        config.set("technologies.farming_basic.effects.1.saturation.duration", -1)
        
        config.set("technologies.farming_basic.effects.2.saturation.type", "POTION_EFFECT")
        config.set("technologies.farming_basic.effects.2.saturation.effect", "SATURATION")
        config.set("technologies.farming_basic.effects.2.saturation.amplifier", 1)
        config.set("technologies.farming_basic.effects.2.saturation.value", 2.0)
        config.set("technologies.farming_basic.effects.2.saturation.duration", -1)
        
        config.set("technologies.farming_basic.effects.3.food_bonus.type", "PASSIVE_ABILITY")
        config.set("technologies.farming_basic.effects.3.food_bonus.ability", "FOOD_BONUS")
        config.set("technologies.farming_basic.effects.3.food_bonus.value", 0.25)
        
        // 基础军事科技
        config.set("technologies.military_basic.name", "基础军事")
        config.set("technologies.military_basic.description", "提升战斗能力和装备效果")
        config.set("technologies.military_basic.icon", "IRON_SWORD")
        config.set("technologies.military_basic.max_level", 3)
        config.set("technologies.military_basic.category", "basic")
        config.set("technologies.military_basic.prerequisites", emptyList<String>())
        
        config.set("technologies.military_basic.costs.1.gold", 800)
        config.set("technologies.military_basic.costs.1.diamond", 8)
        config.set("technologies.military_basic.costs.1.territory_income", 3)
        
        config.set("technologies.military_basic.effects.1.strength.type", "POTION_EFFECT")
        config.set("technologies.military_basic.effects.1.strength.effect", "STRENGTH")
        config.set("technologies.military_basic.effects.1.strength.amplifier", 0)
        config.set("technologies.military_basic.effects.1.strength.value", 1.0)
        config.set("technologies.military_basic.effects.1.strength.duration", -1)
        
        // 基础采矿科技
        config.set("technologies.mining_basic.name", "基础采矿")
        config.set("technologies.mining_basic.description", "提升挖矿效率和矿物产量")
        config.set("technologies.mining_basic.icon", "DIAMOND_PICKAXE")
        config.set("technologies.mining_basic.max_level", 3)
        config.set("technologies.mining_basic.category", "basic")
        config.set("technologies.mining_basic.prerequisites", emptyList<String>())
        
        config.set("technologies.mining_basic.costs.1.gold", 600)
        config.set("technologies.mining_basic.costs.1.diamond", 6)
        config.set("technologies.mining_basic.costs.1.territory_income", 2)
        
        config.set("technologies.mining_basic.effects.1.haste.type", "POTION_EFFECT")
        config.set("technologies.mining_basic.effects.1.haste.effect", "HASTE")
        config.set("technologies.mining_basic.effects.1.haste.amplifier", 0)
        config.set("technologies.mining_basic.effects.1.haste.value", 1.0)
        config.set("technologies.mining_basic.effects.1.haste.duration", -1)
        
        // 系统设置
        config.set("settings.research_time_base", 3600000L)
        config.set("settings.research_time_multiplier", 1.5)
        config.set("settings.max_concurrent_research", 1)
        config.set("settings.effect_update_interval", 20)
        config.set("settings.enable_research_queue", false)
        config.set("settings.auto_complete_research", true)
        
        save()
        Guozhan.instance.logger.info("已创建默认科技配置")
        
        // 重新加载科技
        loadTechnologies()
    }
    
    /**
     * 获取所有科技
     */
    fun getAllTechnologies(): Map<String, Technology> {
        return technologiesCache.toMap()
    }
    
    /**
     * 获取指定科技
     */
    fun getTechnology(id: String): Technology? {
        return technologiesCache[id]
    }
    
    /**
     * 获取指定分类的科技
     */
    fun getTechnologiesByCategory(category: String): List<Technology> {
        return technologiesCache.values.filter { it.category == category }
    }
    
    /**
     * 重新加载科技配置
     */
    fun reloadTechnologies() {
        config.load(file)
        loadTechnologies()
    }
    
    /**
     * 计算研究时间
     * @param technology 科技
     * @param level 目标等级
     * @return 研究时间（毫秒）
     */
    fun calculateResearchTime(technology: Technology, level: Int): Long {
        val baseTime = Settings.researchTimeBase
        val multiplier = Math.pow(Settings.researchTimeMultiplier, (level - 1).toDouble())
        return (baseTime * multiplier).toLong()
    }
}
