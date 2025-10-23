package cn.lcofficial.guozhan.task

import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.manager.TerritoryManager
import cn.lcofficial.guozhan.data.Countries
import cn.lcofficial.guozhan.data.TerritoryBlock
import cn.lcofficial.guozhan.pluginLogger
import org.bukkit.Bukkit
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.math.roundToInt

/**
 * 统一的忠诚度系统
 * 整合了原有的多套忠诚度实现，使用基于接壤面数的概率衰减机制
 */
class LoyaltySystem {

    companion object {
        // 忠诚度检查间隔（5分钟）
        const val LOYALTY_CHECK_INTERVAL = 5 * 60 * 1000L

        // 每次减少的忠诚度百分比
        const val LOYALTY_DECAY_AMOUNT = 4
    }

    // 🔧 v1.3.37: 新增批量保存机制 - 收集需要保存的领土，减少数据库写入频率
    // 🔧 v1.3.48: 修复High问题5 - 使用线程安全集合避免并发修改风险
    internal val pendingSaveTerritories = Collections.newSetFromMap(ConcurrentHashMap<TerritoryBlock, Boolean>())

    fun run() {
        val startTime = System.currentTimeMillis()
        var processedTerritories = 0
        var decreasedTerritories = 0
        var destroyedTerritories = 0

        try {
            // 🔧 v1.3.38修复：性能优化 - 直接获取需要检查的领土，避免全表扫描
            val territoriesByCountry = TerritoryManager.getTerritoriesNeedingLoyaltyCheckByCountry(LOYALTY_CHECK_INTERVAL)

            if (territoriesByCountry.isEmpty()) {
                pluginLogger.fine("忠诚度系统检查: 没有需要检查的领土")
                return
            }

            pluginLogger.fine("忠诚度系统检查: 找到${territoriesByCountry.size}个国家的${territoriesByCountry.values.sumOf { it.size }}块领土需要检查")

            territoriesByCountry.forEach { (country, territories) ->
                territories.forEach { territory ->
                    processedTerritories++

                    // 🔧 v1.3.38修复：优化后的忠诚度更新逻辑，跳过时间检查（已预筛选）
                    val result = updateTerritoryLoyaltyOptimized(territory)

                    when (result) {
                        LoyaltyUpdateResult.DECREASED -> decreasedTerritories++
                        LoyaltyUpdateResult.DESTROYED -> {
                            destroyedTerritories++
                            handleTerritoryDestruction(territory, country)
                        }
                        LoyaltyUpdateResult.NO_CHANGE -> { /* 无变化 */ }
                    }
                }
            }

            // 🔧 v1.3.49: 修复忠诚度批量保存丢失失败的领土 - 只移除成功保存的领土
            if (pendingSaveTerritories.isNotEmpty()) {
                val saveStartTime = System.currentTimeMillis()
                val territoriesToSave = pendingSaveTerritories.toList() // 创建副本
                val savedTerritories = mutableSetOf<TerritoryBlock>()
                var savedCount = 0
                var failedCount = 0

                try {
                    // 🔧 v1.3.49: 逐个保存领土，跟踪成功保存的领土
                    for (territory in territoriesToSave) {
                        try {
                            transaction {
                                territory.saveInTransaction()
                            }
                            savedTerritories.add(territory)
                            savedCount++
                        } catch (e: Exception) {
                            failedCount++
                            pluginLogger.warning("保存领土 (${territory.x}, ${territory.z}) 失败，将在下次重试: ${e.message}")
                        }
                    }

                    // 🔧 v1.3.49: 只移除成功保存的领土，失败的领土保留在列表中下次重试
                    pendingSaveTerritories.removeAll(savedTerritories)
                    val saveDuration = System.currentTimeMillis() - saveStartTime
                    pluginLogger.info("🔧 v1.3.49: 批量保存${savedCount}个领土更新，失败${failedCount}个，耗时${saveDuration}ms（失败的领土将在下次重试）")

                } catch (e: Exception) {
                    pluginLogger.severe("批量保存领土过程中出错: ${e.message}")
                    e.printStackTrace()
                    // 即使出现异常，也要移除已成功保存的领土
                    if (savedTerritories.isNotEmpty()) {
                        pendingSaveTerritories.removeAll(savedTerritories)
                        pluginLogger.info("🔧 v1.3.49: 异常情况下已移除${savedTerritories.size}个成功保存的领土")
                    }
                }
            }

            val duration = System.currentTimeMillis() - startTime
            pluginLogger.info("忠诚度系统检查完成: 处理${processedTerritories}块领土, 衰减${decreasedTerritories}块, 摧毁${destroyedTerritories}块, 耗时${duration}ms")

        } catch (e: Exception) {
            pluginLogger.severe("忠诚度系统运行出错: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 忠诚度更新结果
     */
    enum class LoyaltyUpdateResult {
        NO_CHANGE,  // 无变化
        DECREASED,  // 忠诚度减少
        DESTROYED   // 领土被摧毁
    }

    /**
     * 统一的领土忠诚度更新逻辑
     * 整合了原有TerritoryBlock.updateLoyalty()的逻辑
     */
    private fun updateTerritoryLoyalty(territory: TerritoryBlock): LoyaltyUpdateResult {
        // 无主区块或首都区块不减少忠诚度
        if (!territory.isOwned() || territory.isCapital) {
            return LoyaltyUpdateResult.NO_CHANGE
        }

        val currentTime = System.currentTimeMillis()

        // 检查是否到了更新时间（每5分钟检查一次）
        if (currentTime - territory.lastLoyaltyUpdateTime < LOYALTY_CHECK_INTERVAL) {
            return LoyaltyUpdateResult.NO_CHANGE
        }

        return updateTerritoryLoyaltyOptimized(territory)
    }

    /**
     * 🔧 v1.3.38修复：优化后的领土忠诚度更新逻辑（跳过时间检查）
     * 用于已经预筛选过时间的领土，避免重复的时间检查
     */
    private fun updateTerritoryLoyaltyOptimized(territory: TerritoryBlock): LoyaltyUpdateResult {
        val currentTime = System.currentTimeMillis()

        // 计算不接壤面数
        val unconnectedSides = calculateUnconnectedSides(territory)

        // 根据不接壤面数计算忠诚度减少概率
        val loyaltyReductionChance = when (unconnectedSides) {
            0 -> 0.0    // 完全接壤，不减少
            1 -> 0.1    // 1个面不接壤，10%概率减少
            2 -> 0.35   // 2个面不接壤，35%概率减少
            3 -> 0.75   // 3个面不接壤，75%概率减少
            else -> 1.0 // 4个面都不接壤，100%概率减少
        }

        // 随机决定是否减少忠诚度
        val shouldDecrease = Random.nextDouble() < loyaltyReductionChance

        if (shouldDecrease) {
            // 🔧 v1.3.41: 修复忠诚度系统高频单笔数据库写入 - 衰减时也使用批量保存机制
            // 减少忠诚度
            val oldLoyalty = territory.loyalty
            territory.loyalty = (territory.loyalty - LOYALTY_DECAY_AMOUNT).coerceAtLeast(0)
            territory.lastLoyaltyUpdateTime = currentTime
            // 添加到待保存列表，稍后批量保存（而不是立即保存）
            pendingSaveTerritories.add(territory)

            pluginLogger.fine("领土(${territory.x}, ${territory.z})忠诚度从${oldLoyalty}降低到${territory.loyalty}")

            // 检查是否忠诚度归零
            if (territory.loyalty <= 0) {
                return LoyaltyUpdateResult.DESTROYED
            } else {
                return LoyaltyUpdateResult.DECREASED
            }
        } else {
            // 🔧 v1.3.37: 修复过度数据库写入 - 只在内存中更新检查时间，加入批量保存队列
            // 未衰减的情况下，仅更新内存中的时间戳，减少数据库写入频率
            territory.lastLoyaltyUpdateTime = currentTime
            // 添加到待保存列表，稍后批量保存
            pendingSaveTerritories.add(territory)
            return LoyaltyUpdateResult.NO_CHANGE
        }
    }

    /**
     * 计算不接壤面数
     */
    private fun calculateUnconnectedSides(territory: TerritoryBlock): Int {
        var unconnected = 0

        // 检查四个方向是否接壤
        val directions = listOf(
            Pair(1, 0),   // 东
            Pair(-1, 0),  // 西
            Pair(0, 1),   // 南
            Pair(0, -1)   // 北
        )

        directions.forEach { (dx, dz) ->
            val neighbor = TerritoryManager.getTerritoryBlock(territory.x + dx, territory.z + dz, territory.world)
            if (neighbor == null || neighbor.owner?.id != territory.owner?.id) {
                unconnected++
            }
        }

        return unconnected
    }

    /**
     * 处理领土摧毁
     * 🔧 v1.3.37: 修复灭国逻辑不完整 - 首都忠诚度归零时调用完整的灭国逻辑
     */
    private fun handleTerritoryDestruction(territory: TerritoryBlock, country: cn.lcofficial.guozhan.data.Country) {
        if (territory.isCapital) {
            // 🔧 v1.3.37: 王城区块忠诚度为0，触发完整的灭国逻辑
            pluginLogger.info("国家 ${country.name} 的王城忠诚度归零，触发完整灭国流程")

            // 调用CountryManager的完整灭国方法，确保清理所有相关数据
            try {
                CountryManager.deleteCountry(country)
                Bukkit.broadcastMessage("§c[国战] ${country.name} 已被灭国！所有领土现在可以被占领。")
                pluginLogger.info("国家 ${country.name} 已完全删除，包括所有外交关系、战争状态和相关数据")
            } catch (e: Exception) {
                // 如果完整删除失败，回退到原有逻辑
                pluginLogger.warning("完整删除国家 ${country.name} 失败，回退到简单逻辑: ${e.message}")
                territory.owner = null
                territory.save()
                Bukkit.broadcastMessage("§c[国战] ${country.name} 已被灭国！")
            }
        } else {
            // 普通区块忠诚度为0，变为无主区块
            pluginLogger.fine("领土(${territory.x}, ${territory.z})忠诚度归零，变为无主区块")
            territory.owner = null
            territory.save()
        }

        // 🔧 代码审查修复: 触发地图更新以立即移除领土标记
        cn.lcofficial.guozhan.Guozhan.instance.squaremapIntegration.triggerMapUpdate()
    }
    
    fun restoreLoyalty(country: cn.lcofficial.guozhan.data.Country) {
        val territories = TerritoryManager.getTerritoriesByCountry(country)
        var totalCost = 0

        // v1.3.18修复：根据每个区块的每小时收入计算恢复成本
        territories.forEach { territory ->
            val missingLoyalty = 100 - territory.loyalty
            if (missingLoyalty > 0) {
                // 计算该区块每小时的收入（金币）
                val goldPerHour = cn.lcofficial.guozhan.economy.RegionalTaxSystem.calculateGoldTaxPerHour(territory)
                val diamondPerHour = cn.lcofficial.guozhan.economy.RegionalTaxSystem.calculateDiamondTaxPerHour(territory)

                // 钻石转金币（使用配置的转换率）
                val diamondToGoldRate = cn.lcofficial.guozhan.config.Config.Shield.diamondToGoldRate
                val totalIncomePerHour = goldPerHour + (diamondPerHour * diamondToGoldRate)

                // 🔧 v1.3.40: 修复忠诚度恢复零成本漏洞 - 使用roundToInt确保至少收取1单位成本
                val territoryCost = (totalIncomePerHour * 0.5).let { cost ->
                    if (cost < 1.0) 1 else cost.roundToInt()
                }
                totalCost += territoryCost
            }
        }

        if (country.gold >= totalCost) {
            country.gold -= totalCost
            country.save()

            // 🔧 v1.3.43: 修复忠诚度恢复功能高频调用territory.save() - 实现批量保存机制
            val currentTime = System.currentTimeMillis()
            var restoredCount = 0
            val territoriesToSave = mutableListOf<cn.lcofficial.guozhan.data.TerritoryBlock>()

            territories.forEach { territory ->
                val oldLoyalty = territory.loyalty
                if (oldLoyalty < 100) {
                    territory.loyalty = 100
                    // 🔧 v1.3.38修复：关键修复 - 更新时间戳，确保恢复后不会立即进入衰减检查
                    territory.lastLoyaltyUpdateTime = currentTime
                    territoriesToSave.add(territory)
                    restoredCount++

                    pluginLogger.fine("恢复领土(${territory.x}, ${territory.z})忠诚度从${oldLoyalty}到100，更新时间戳")
                }
            }

            // 🔧 v1.3.43: 批量保存所有恢复的领土，减少数据库写入频率
            if (territoriesToSave.isNotEmpty()) {
                val saveStartTime = System.currentTimeMillis()
                var savedCount = 0
                for (territory in territoriesToSave) {
                    try {
                        territory.save()
                        savedCount++
                    } catch (e: Exception) {
                        pluginLogger.warning("保存恢复的领土 (${territory.x}, ${territory.z}) 时出错: ${e.message}")
                    }
                }
                val saveDuration = System.currentTimeMillis() - saveStartTime
                pluginLogger.info("🔧 v1.3.43: 批量保存${savedCount}个恢复的领土，耗时${saveDuration}ms（性能优化：避免${savedCount}次单独数据库写入）")
            }

            Bukkit.broadcastMessage("§a[国战] ${country.name}已恢复所有领土忠诚度！")
            pluginLogger.info("国家 ${country.name} 恢复了 ${restoredCount} 块领土的忠诚度，消耗 ${totalCost} 金币")
        }
    }
}