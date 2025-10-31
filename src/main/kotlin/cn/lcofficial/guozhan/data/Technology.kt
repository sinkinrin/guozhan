package cn.lcofficial.guozhan.data

import org.bukkit.Material
import org.bukkit.potion.PotionEffectType
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Table
import java.util.*

/**
 * 科技数据表
 * 🔧 v1.3.55: 修复主键约束问题 - 改为普通Table并显式定义主键
 */
object Technologies : Table("gz_technologies") {
    val id = varchar("id", 32)
    val name = varchar("name", 64)
    val description = text("description")
    val icon = varchar("icon", 32)
    val maxLevel = integer("max_level").default(1)
    val prerequisites = text("prerequisites") // JSON格式存储前置科技ID列表
    val costs = text("costs")                 // JSON格式存储每级成本
    val effects = text("effects")             // JSON格式存储每级效果
    val category = varchar("category", 32).default("basic") // 科技分类
    val enabled = bool("enabled").default(true) // 是否启用

    override val primaryKey = PrimaryKey(id)
}

/**
 * 国家科技研究状态表
 */
object CountryTechnologies : Table("gz_country_technologies") {
    val countryId = varchar("country_id", 36).references(Countries.id)
    val technologyId = varchar("technology_id", 32).references(Technologies.id)
    val level = integer("level").default(0)
    val researchStartTime = long("research_start_time").nullable()
    val researchEndTime = long("research_end_time").nullable()
    val isResearching = bool("is_researching").default(false)
    
    override val primaryKey = PrimaryKey(countryId, technologyId)
}

/**
 * 科技数据类
 * @param id 科技唯一标识符
 * @param name 科技名称
 * @param description 科技描述
 * @param icon GUI中显示的图标
 * @param maxLevel 最大等级
 * @param prerequisites 前置科技ID列表
 * @param costs 每级的研发成本
 * @param effects 每级的效果列表
 * @param category 科技分类
 * @param enabled 是否启用
 */
data class Technology(
    val id: String,
    val name: String,
    val description: String,
    val icon: Material,
    val maxLevel: Int,
    val prerequisites: List<String>,
    val costs: Map<Int, TechCost>,
    val effects: Map<Int, List<TechEffect>>,
    val category: String = "basic",
    val enabled: Boolean = true
) {
    /**
     * 获取指定等级的研发成本
     * @param level 目标等级
     * @return 研发成本，如果等级无效返回null
     */
    fun getCost(level: Int): TechCost? {
        return costs[level]
    }
    
    /**
     * 获取指定等级的效果列表
     * @param level 目标等级
     * @return 效果列表，如果等级无效返回空列表
     */
    fun getEffects(level: Int): List<TechEffect> {
        return effects[level] ?: emptyList()
    }
    
    /**
     * 检查是否为有效等级
     * @param level 要检查的等级
     * @return 是否为有效等级
     */
    fun isValidLevel(level: Int): Boolean {
        return level in 1..maxLevel
    }
    
    /**
     * 获取总研发成本（从1级到指定等级）
     * @param targetLevel 目标等级
     * @return 总成本
     */
    fun getTotalCost(targetLevel: Int): TechCost {
        var totalGold = 0
        var totalDiamond = 0
        var totalTerritoryIncome = 0
        
        for (level in 1..targetLevel) {
            val cost = getCost(level)
            if (cost != null) {
                totalGold += cost.gold
                totalDiamond += cost.diamond
                totalTerritoryIncome += cost.territoryIncome
            }
        }
        
        return TechCost(totalGold, totalDiamond, totalTerritoryIncome)
    }
}

/**
 * 科技研发成本
 * @param gold 金币消耗
 * @param diamond 钻石消耗
 * @param territoryIncome 领土收入小时数
 */
data class TechCost(
    val gold: Int,
    val diamond: Int,
    val territoryIncome: Int
) {
    /**
     * 检查成本是否为零
     */
    fun isZero(): Boolean {
        return gold == 0 && diamond == 0 && territoryIncome == 0
    }
    
    /**
     * 成本相加
     */
    operator fun plus(other: TechCost): TechCost {
        return TechCost(
            gold + other.gold,
            diamond + other.diamond,
            territoryIncome + other.territoryIncome
        )
    }
    
    /**
     * 成本相减
     */
    operator fun minus(other: TechCost): TechCost {
        return TechCost(
            gold - other.gold,
            diamond - other.diamond,
            territoryIncome - other.territoryIncome
        )
    }
    
    /**
     * 成本倍数
     */
    operator fun times(multiplier: Int): TechCost {
        return TechCost(
            gold * multiplier,
            diamond * multiplier,
            territoryIncome * multiplier
        )
    }
}

/**
 * 科技效果
 * @param type 效果类型
 * @param value 效果数值
 * @param duration 持续时间（毫秒，-1表示永久）
 * @param data 额外数据（用于存储特定效果的参数）
 */
data class TechEffect(
    val type: TechEffectType,
    val value: Double,
    val duration: Long = -1L,
    val data: Map<String, Any> = emptyMap()
) {
    /**
     * 是否为永久效果
     */
    fun isPermanent(): Boolean {
        return duration == -1L
    }
    
    /**
     * 获取效果描述
     */
    fun getDescription(): String {
        return when (type) {
            TechEffectType.POTION_EFFECT -> {
                val effectName = data["effect"] as? String ?: "未知"
                val amplifier = (data["amplifier"] as? Number)?.toInt() ?: 0
                "$effectName ${amplifier + 1}级"
            }
            TechEffectType.ATTRIBUTE_MODIFIER -> {
                val attribute = data["attribute"] as? String ?: "未知属性"
                "$attribute +${value}"
            }
            TechEffectType.PASSIVE_ABILITY -> {
                val ability = data["ability"] as? String ?: "未知能力"
                "$ability +${(value * 100).toInt()}%"
            }
            TechEffectType.SPECIAL_ABILITY -> {
                val ability = data["ability"] as? String ?: "未知特殊能力"
                ability
            }
        }
    }
}

/**
 * 科技效果类型枚举
 */
enum class TechEffectType {
    /**
     * 药水效果（如力量、速度、饱和等）
     */
    POTION_EFFECT,
    
    /**
     * 属性修改（如攻击力、防御力、移动速度等）
     */
    ATTRIBUTE_MODIFIER,
    
    /**
     * 被动能力（如经验加成、掉落加成、收入加成等）
     */
    PASSIVE_ABILITY,
    
    /**
     * 特殊能力（如传送、飞行、特殊技能等）
     */
    SPECIAL_ABILITY
}

/**
 * 科技分类枚举
 */
enum class TechCategory(val displayName: String, val description: String) {
    BASIC("基础科技", "基础的科技分支，无前置要求"),
    MILITARY("军事科技", "提升战斗能力的科技"),
    ECONOMIC("经济科技", "提升经济收益的科技"),
    SOCIAL("社会科技", "提升社会发展的科技"),
    ADVANCED("高级科技", "需要前置科技的高级分支"),
    LEGENDARY("传奇科技", "最高级的科技，需要大量资源")
}

/**
 * 国家科技研究状态
 * @param countryId 国家ID
 * @param technologyId 科技ID
 * @param level 当前等级
 * @param researchStartTime 研究开始时间
 * @param researchEndTime 研究结束时间
 * @param isResearching 是否正在研究中
 */
data class CountryTechnology(
    val countryId: UUID,
    val technologyId: String,
    val level: Int,
    val researchStartTime: Long?,
    val researchEndTime: Long?,
    val isResearching: Boolean
) {
    /**
     * 检查研究是否已完成
     */
    fun isResearchCompleted(): Boolean {
        return researchEndTime != null && System.currentTimeMillis() >= researchEndTime
    }
    
    /**
     * 获取研究剩余时间（毫秒）
     */
    fun getRemainingResearchTime(): Long {
        if (!isResearching || researchEndTime == null) return 0L
        val remaining = researchEndTime - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0L
    }
    
    /**
     * 获取研究进度（0.0-1.0）
     */
    fun getResearchProgress(): Double {
        if (!isResearching || researchStartTime == null || researchEndTime == null) return 0.0
        
        val totalTime = researchEndTime - researchStartTime
        val elapsedTime = System.currentTimeMillis() - researchStartTime
        
        return (elapsedTime.toDouble() / totalTime).coerceIn(0.0, 1.0)
    }
}
