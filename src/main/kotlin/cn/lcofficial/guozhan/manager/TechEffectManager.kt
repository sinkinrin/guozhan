package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.config.TechnologyConfig
import cn.lcofficial.guozhan.data.*
import cn.lcofficial.guozhan.manager.UserManager.user
import cn.lcofficial.guozhan.util.runRepeat
import org.bukkit.Bukkit
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 科技效果管理器
 * 负责科技效果的应用、移除和管理
 */
object TechEffectManager {
    
    // 国家科技效果缓存 (国家ID -> 效果列表)
    private val countryEffectsCache = ConcurrentHashMap<UUID, List<TechEffect>>()
    
    // 玩家属性修改器缓存 (玩家UUID -> (属性类型 -> 修改器UUID))
    private val playerAttributeModifiers = ConcurrentHashMap<UUID, MutableMap<Attribute, UUID>>()
    
    // 效果更新任务间隔（tick）- 确保最小值为1
    private val EFFECT_UPDATE_INTERVAL = maxOf(1L, TechnologyConfig.Settings.effectUpdateInterval.toLong())
    
    // 科技效果的命名空间前缀
    private const val TECH_EFFECT_NAMESPACE = "guozhan_tech"
    
    /**
     * 初始化科技效果管理器
     */
    fun initialize() {
        Guozhan.instance.logger.info("正在初始化科技效果管理器...")
        
        // 启动效果更新任务
        startEffectUpdateTask()
        
        Guozhan.instance.logger.info("科技效果管理器初始化完成")
    }
    
    /**
     * 启动效果更新任务
     */
    private fun startEffectUpdateTask() {
        // 调试信息：打印实际的间隔值
        Guozhan.instance.logger.info("科技效果更新间隔: $EFFECT_UPDATE_INTERVAL ticks")

        // 确保间隔值至少为1
        val safeInterval = maxOf(1L, EFFECT_UPDATE_INTERVAL)
        Guozhan.instance.logger.info("使用安全间隔: $safeInterval ticks")

        runRepeat(safeInterval, safeInterval) { task ->
            try {
                updateAllPlayersEffects()
            } catch (e: Exception) {
                Guozhan.instance.logger.severe("科技效果更新任务执行出错: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    /**
     * 为所有在线玩家更新科技效果
     */
    private fun updateAllPlayersEffects() {
        val startTime = System.currentTimeMillis()
        var processedPlayers = 0
        
        for (player in Bukkit.getOnlinePlayers()) {
            try {
                updatePlayerEffects(player)
                processedPlayers++
            } catch (e: Exception) {
                Guozhan.instance.logger.warning("更新玩家 ${player.name} 的科技效果时出错: ${e.message}")
            }
        }
        
        val duration = System.currentTimeMillis() - startTime
        if (duration > 50) { // 如果处理时间超过50ms，记录警告
            Guozhan.instance.logger.warning("科技效果更新任务耗时较长: ${duration}ms，处理了${processedPlayers}个玩家")
        }
    }
    
    /**
     * 更新单个玩家的科技效果
     */
    fun updatePlayerEffects(player: Player) {
        val user = player.user()
        val country = user.country ?: return
        
        // 获取国家的所有科技效果
        val effects = getCountryTechnologyEffects(country)
        
        // 应用药水效果
        applyPotionEffects(player, effects)
        
        // 应用属性修改
        applyAttributeModifiers(player, effects)
        
        // 应用被动能力和特殊能力
        applyPassiveAndSpecialAbilities(player, effects)
    }
    
    /**
     * 获取国家的所有科技效果
     */
    fun getCountryTechnologyEffects(country: Country): List<TechEffect> {
        // 先检查缓存
        val cached = countryEffectsCache[country.id]
        if (cached != null) {
            return cached
        }
        
        // 计算国家的所有科技效果
        val effects = mutableListOf<TechEffect>()
        
        // 获取国家已研究的所有科技
        val technologies = TechnologyManager.getAllTechnologies()
        
        for (technology in technologies) {
            val level = TechnologyManager.getCountryTechLevel(country, technology.id)
            if (level > 0) {
                // 添加该科技当前等级的所有效果
                effects.addAll(technology.getEffects(level))
            }
        }
        
        // 缓存结果
        countryEffectsCache[country.id] = effects
        
        return effects
    }
    
    /**
     * 应用药水效果
     */
    private fun applyPotionEffects(player: Player, effects: List<TechEffect>) {
        val potionEffects = effects.filter { it.type == TechEffectType.POTION_EFFECT }
        
        for (effect in potionEffects) {
            val effectName = effect.data["effect"] as? String ?: continue
            val amplifier = (effect.data["amplifier"] as? Number)?.toInt() ?: 0
            
            try {
                val potionEffectType = PotionEffectType.getByName(effectName.uppercase())
                if (potionEffectType != null) {
                    // 检查玩家是否已有该效果
                    val existingEffect = player.getPotionEffect(potionEffectType)
                    val shouldApply = existingEffect == null || 
                                    existingEffect.amplifier < amplifier ||
                                    existingEffect.duration < 200 // 如果剩余时间少于10秒，重新应用
                    
                    if (shouldApply) {
                        val duration = if (effect.isPermanent()) Int.MAX_VALUE else effect.duration.toInt()
                        val potionEffect = PotionEffect(
                            potionEffectType,
                            duration,
                            amplifier,
                            false, // ambient
                            true,  // particles
                            true   // icon
                        )
                        player.addPotionEffect(potionEffect)
                    }
                }
            } catch (e: Exception) {
                Guozhan.instance.logger.warning("应用药水效果 $effectName 时出错: ${e.message}")
            }
        }
    }
    
    /**
     * 应用属性修改
     */
    private fun applyAttributeModifiers(player: Player, effects: List<TechEffect>) {
        val attributeEffects = effects.filter { it.type == TechEffectType.ATTRIBUTE_MODIFIER }
        
        // 清除旧的科技属性修改器
        clearPlayerAttributeModifiers(player)
        
        for (effect in attributeEffects) {
            val attributeName = effect.data["attribute"] as? String ?: continue
            val value = effect.value
            
            try {
                val attribute = when (attributeName.uppercase()) {
                    "ATTACK_DAMAGE" -> Attribute.ATTACK_DAMAGE
                    "ATTACK_SPEED" -> Attribute.ATTACK_SPEED
                    "MOVEMENT_SPEED" -> Attribute.MOVEMENT_SPEED
                    "MAX_HEALTH" -> Attribute.MAX_HEALTH
                    "ARMOR" -> Attribute.ARMOR
                    "ARMOR_TOUGHNESS" -> Attribute.ARMOR_TOUGHNESS
                    "KNOCKBACK_RESISTANCE" -> Attribute.KNOCKBACK_RESISTANCE
                    else -> continue
                }
                
                val attributeInstance = player.getAttribute(attribute)
                if (attributeInstance != null) {
                    val modifierUUID = UUID.randomUUID()
                    val modifier = AttributeModifier(
                        modifierUUID,
                        "$TECH_EFFECT_NAMESPACE.$attributeName",
                        value,
                        AttributeModifier.Operation.ADD_NUMBER
                    )
                    
                    attributeInstance.addModifier(modifier)
                    
                    // 记录修改器以便后续清除
                    playerAttributeModifiers.computeIfAbsent(player.uniqueId) { mutableMapOf() }[attribute] = modifierUUID
                }
            } catch (e: Exception) {
                Guozhan.instance.logger.warning("应用属性修改 $attributeName 时出错: ${e.message}")
            }
        }
    }
    
    /**
     * 应用被动能力和特殊能力
     */
    private fun applyPassiveAndSpecialAbilities(player: Player, effects: List<TechEffect>) {
        val passiveEffects = effects.filter { it.type == TechEffectType.PASSIVE_ABILITY }
        val specialEffects = effects.filter { it.type == TechEffectType.SPECIAL_ABILITY }
        
        // 被动能力处理（如经验加成、掉落加成等）
        for (effect in passiveEffects) {
            val ability = effect.data["ability"] as? String ?: continue
            // 这里可以根据具体的被动能力类型进行处理
            // 目前暂时记录，后续可以扩展具体的被动能力实现
        }
        
        // 特殊能力处理（如传送、飞行等）
        for (effect in specialEffects) {
            val ability = effect.data["ability"] as? String ?: continue
            // 这里可以根据具体的特殊能力类型进行处理
            // 目前暂时记录，后续可以扩展具体的特殊能力实现
        }
    }
    
    /**
     * 清除玩家的科技属性修改器
     */
    private fun clearPlayerAttributeModifiers(player: Player) {
        val modifiers = playerAttributeModifiers[player.uniqueId] ?: return
        
        for ((attribute, modifierUUID) in modifiers) {
            try {
                val attributeInstance = player.getAttribute(attribute)
                attributeInstance?.modifiers?.forEach { modifier ->
                    if (modifier.uniqueId == modifierUUID) {
                        attributeInstance.removeModifier(modifier)
                    }
                }
            } catch (e: Exception) {
                Guozhan.instance.logger.warning("清除玩家 ${player.name} 的属性修改器时出错: ${e.message}")
            }
        }
        
        modifiers.clear()
    }
    
    /**
     * 移除玩家的所有科技效果
     */
    fun removePlayerEffects(player: Player) {
        // 清除属性修改器
        clearPlayerAttributeModifiers(player)
        playerAttributeModifiers.remove(player.uniqueId)
        
        // 清除科技相关的药水效果
        // 注意：这里不能简单地清除所有药水效果，因为可能有其他来源的效果
        // 目前暂时不处理药水效果的清除，因为它们会自然过期
    }
    
    /**
     * 刷新国家的科技效果缓存
     */
    fun refreshCountryEffectsCache(country: Country) {
        countryEffectsCache.remove(country.id)
        // 下次获取时会重新计算
    }
    
    /**
     * 刷新所有国家的科技效果缓存
     */
    fun refreshAllEffectsCache() {
        countryEffectsCache.clear()
    }
    
    /**
     * 获取玩家当前的科技效果数量（用于调试）
     */
    fun getPlayerEffectCount(player: Player): Int {
        val user = player.user()
        val country = user.country ?: return 0
        return getCountryTechnologyEffects(country).size
    }
}
