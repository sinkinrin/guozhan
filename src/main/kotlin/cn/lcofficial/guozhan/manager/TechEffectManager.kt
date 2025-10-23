package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.config.TechnologyConfig
import cn.lcofficial.guozhan.data.*
import cn.lcofficial.guozhan.manager.UserManager.user
import cn.lcofficial.guozhan.util.runRepeat
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
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

    // 🔧 v1.3.38修复：添加科技药水效果跟踪 - 记录玩家的科技药水效果类型
    private val playerTechPotionEffects = ConcurrentHashMap<UUID, MutableSet<PotionEffectType>>()
    private var effectUpdateTask: ScheduledTask? = null

    // 效果更新任务间隔（tick），确保最小值为1
    private val EFFECT_UPDATE_INTERVAL = maxOf(1L, TechnologyConfig.Settings.effectUpdateInterval.toLong())

    // 科技效果的命名空间前缀
    private const val TECH_EFFECT_NAMESPACE = "guozhan_tech"

    /**
     * 初始化科技效果管理器
     */
    fun initialize() {
        Guozhan.instance.logger.info("正在初始化科技效果管理器..")

        // 启动效果更新任务
        startEffectUpdateTask()

        Guozhan.instance.logger.info("科技效果管理器初始化完成")
    }

    /**
     * 启动效果更新任务
     * 🔧 v1.3.37: 修复Folia线程安全违规 - 改为为每个玩家单独调度实体任务
     */
    private fun startEffectUpdateTask() {
        // 🔧 v1.3.37: 将更新间隔改为5分钟，避免过度频繁的效果更新
        val safeInterval = maxOf(6000L, EFFECT_UPDATE_INTERVAL) // 6000 ticks = 5分钟
        Guozhan.instance.logger.info("科技效果更新间隔: $safeInterval ticks (${safeInterval/1200.0}分钟)")

        // 🔧 v1.3.37: 使用GlobalRegionScheduler调度玩家遍历，然后为每个玩家单独调度实体任务
        // 🔧 v1.3.51: 修复热重载问题 - 启动新任务前先停止旧任务
        stopEffectUpdateTask()

        effectUpdateTask = runRepeat(safeInterval, safeInterval) { task ->
            try {
                schedulePlayerEffectsUpdate()
            } catch (e: Exception) {
                Guozhan.instance.logger.severe("科技效果更新任务执行出错: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * 停止效果更新任务
     * 🔧 v1.3.51: 修复热重载问题 - 添加任务清理方法
     */
    fun stopEffectUpdateTask() {
        effectUpdateTask?.let { task ->
            if (!task.isCancelled) {
                task.cancel()
                Guozhan.instance.logger.info("科技效果更新任务已停止")
            }
        }
        effectUpdateTask = null
    }
    
    /**
     * 🔧 v1.3.37: 新增方法 - 为所有在线玩家调度科技效果更新任务
     * 使用EntityScheduler确保每个玩家的效果更新在正确的实体线程中执行
     */
    private fun schedulePlayerEffectsUpdate() {
        val startTime = System.currentTimeMillis()
        var scheduledPlayers = 0

        for (player in Bukkit.getOnlinePlayers()) {
            try {
                // 🔧 v1.3.37: 为每个玩家单独调度实体任务，确保线程安全
                player.scheduler.run(Guozhan.instance, { _ ->
                    try {
                        updatePlayerEffectsSafe(player)
                    } catch (e: Exception) {
                        Guozhan.instance.logger.warning("更新玩家 ${player.name} 的科技效果时出错：${e.message}")
                    }
                }, null)
                scheduledPlayers++
            } catch (e: Exception) {
                Guozhan.instance.logger.warning("为玩家 ${player.name} 调度科技效果更新任务时出错：${e.message}")
            }
        }

        val duration = System.currentTimeMillis() - startTime
        Guozhan.instance.logger.fine("科技效果更新任务调度完成：为 ${scheduledPlayers} 个玩家调度了更新任务，耗时${duration}ms")
    }

    /**
     * 🔧 v1.3.48: 修复离线玩家科技效果丢失问题 - 确保每次都获取最新科技状态
     * 此方法必须在玩家的EntityScheduler中调用，确保线程安全
     */
    private fun updatePlayerEffectsSafe(player: Player) {
        val user = player.user()
        val country = user.country ?: return

        // 🔧 v1.3.48: 刷新国家科技效果缓存，确保获得最新的科技状态
        // 这样可以保证返回的玩家永远不会错过新解锁的科技
        refreshCountryEffectsCache(country)

        // 获取国家的所有科技效果（现在是最新的）
        val effects = getCountryTechnologyEffects(country)

        // 应用药水效果
        applyPotionEffects(player, effects)

        // 应用属性修改器
        applyAttributeModifiers(player, effects)

        // 应用被动能力和特殊能力
        applyPassiveAndSpecialAbilities(player, effects)

        Guozhan.instance.logger.fine("🔧 [科技效果更新] 玩家 ${player.name} 已应用了 ${effects.size} 个科技效果")
    }

    /**
     * 🔧 v1.3.48: 修复离线玩家科技效果丢失问题 - 确保每次都获取最新科技状态
     * 更新单个玩家的科技效果（线程安全版本）
     */
    fun updatePlayerEffects(player: Player) {
        // 🔧 v1.3.48: 确保在EntityScheduler中执行，避免Folia线程安全违规
        player.scheduler.run(Guozhan.instance, { _ ->
            updatePlayerEffectsSafe(player)
        }, null)
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
     * 🔧 v1.3.38修复：完善药水效果清理机制，防止永久Buff残留
     */
    private fun applyPotionEffects(player: Player, effects: List<TechEffect>) {
        // 🔧 v1.3.38修复：首先清除旧的科技药水效果
        clearPlayerTechPotionEffects(player)

        val potionEffects = effects.filter { it.type == TechEffectType.POTION_EFFECT }
        val currentTechEffects = mutableSetOf<PotionEffectType>()

        for (effect in potionEffects) {
            val effectName = effect.data["effect"] as? String ?: continue
            val amplifier = (effect.data["amplifier"] as? Number)?.toInt() ?: 0

            try {
                val potionEffectType = PotionEffectType.getByName(effectName.uppercase())
                if (potionEffectType != null) {
                    // 🔧 v1.3.38修复：应用新的科技效果
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

                    // 🔧 v1.3.38修复：记录这个效果类型，用于后续清理
                    currentTechEffects.add(potionEffectType)

                    Guozhan.instance.logger.fine("为玩家 ${player.name} 应用科技药水效果: $effectName (等级 $amplifier)")
                }
            } catch (e: Exception) {
                Guozhan.instance.logger.warning("应用药水效果 $effectName 时出错：${e.message}")
            }
        }

        // 🔧 v1.3.38修复：更新玩家的科技药水效果跟踪
        if (currentTechEffects.isNotEmpty()) {
            playerTechPotionEffects[player.uniqueId] = currentTechEffects
        } else {
            playerTechPotionEffects.remove(player.uniqueId)
        }
    }
    
    /**
     * 应用属性修改器
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
                Guozhan.instance.logger.warning("应用属性修改器 $attributeName 时出错：${e.message}")
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
                Guozhan.instance.logger.warning("清除玩家 ${player.name} 的属性修改器时出错：${e.message}")
            }
        }

        modifiers.clear()
    }

    /**
     * 🔧 v1.3.38修复：清除玩家的科技药水效果
     * 只清除由科技系统添加的药水效果，保留其他来源的效果
     */
    private fun clearPlayerTechPotionEffects(player: Player) {
        val techEffects = playerTechPotionEffects[player.uniqueId] ?: return

        for (effectType in techEffects) {
            try {
                // 检查玩家是否有该效果，且该效果是永久的（科技效果特征）
                val existingEffect = player.getPotionEffect(effectType)
                if (existingEffect != null && existingEffect.duration == Int.MAX_VALUE) {
                    // 只移除永久效果（科技效果），保留临时效果（信标、药水等）
                    player.removePotionEffect(effectType)
                    Guozhan.instance.logger.fine("清除玩家 ${player.name} 的科技药水效果: ${effectType.name}")
                }
            } catch (e: Exception) {
                Guozhan.instance.logger.warning("清除玩家 ${player.name} 的科技药水效果 ${effectType.name} 时出错：${e.message}")
            }
        }

        techEffects.clear()
    }

    /**
     * 移除玩家的所有科技效果
     * 🔧 v1.3.38修复：完整清理药水效果，确保在EntityScheduler中安全执行
     */
    fun removePlayerEffects(player: Player) {
        // 🔧 v1.3.38修复：确保在EntityScheduler中执行，避免Folia线程安全违规
        player.scheduler.run(Guozhan.instance, { _ ->
            try {
                removePlayerEffectsSafe(player)
            } catch (e: Exception) {
                Guozhan.instance.logger.warning("移除玩家 ${player.name} 的科技效果时出错：${e.message}")
            }
        }, null)
    }

    /**
     * 🔧 v1.3.38修复：线程安全的科技效果移除方法
     * 此方法必须在EntityScheduler中调用，确保线程安全
     * 🔧 v1.3.38修复：改为内部公开，供PlayerListener等需要直接调用的地方使用
     */
    internal fun removePlayerEffectsSafe(player: Player) {
        // 清除属性修改器
        clearPlayerAttributeModifiers(player)
        playerAttributeModifiers.remove(player.uniqueId)

        // 🔧 v1.3.38修复：清除科技相关的药水效果
        clearPlayerTechPotionEffects(player)
        playerTechPotionEffects.remove(player.uniqueId)

        Guozhan.instance.logger.fine("已清除玩家 ${player.name} 的所有科技效果")
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

    /**
     * 🔧 v1.3.49: 获取玩家指定效果类型的科技效果amplifier值
     * @param player 玩家
     * @param effectType 效果类型
     * @return 科技效果的amplifier值，如果没有科技效果返回-1
     */
    fun getTechEffectAmplifier(player: Player, effectType: PotionEffectType): Int {
        val user = player.user()
        val country = user.country ?: return -1

        val effects = getCountryTechnologyEffects(country)
        val potionEffects = effects.filter { it.type == TechEffectType.POTION_EFFECT }

        for (effect in potionEffects) {
            val effectName = effect.data["effect"] as? String ?: continue
            val amplifier = (effect.data["amplifier"] as? Number)?.toInt() ?: 0

            try {
                val potionEffectType = PotionEffectType.getByName(effectName.uppercase())
                if (potionEffectType == effectType) {
                    return amplifier
                }
            } catch (e: Exception) {
                // 忽略错误，继续查找
            }
        }

        return -1 // 没有找到对应的科技效果
    }
    fun shutdown() {
        effectUpdateTask?.cancel()
        effectUpdateTask = null
        Bukkit.getOnlinePlayers().forEach { player ->
            try {
                removePlayerEffects(player)
            } catch (_: Exception) {
            }
        }
        countryEffectsCache.clear()
        playerAttributeModifiers.clear()
        playerTechPotionEffects.clear()
    }

}
