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
import kotlin.random.Random

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

    fun run() {
        val startTime = System.currentTimeMillis()
        var processedTerritories = 0
        var decreasedTerritories = 0
        var destroyedTerritories = 0

        try {
            // v1.3.18修复：从数据库查询所有国家，添加UUID验证
            val countries = transaction {
                Countries.selectAll().mapNotNull { row ->
                    try {
                        val idString = row[Countries.id].value
                        // 验证UUID格式
                        if (idString.length != 36) {
                            pluginLogger.warning("[忠诚度系统] 跳过无效的UUID格式: '$idString' (长度: ${idString.length})")
                            return@mapNotNull null
                        }
                        val uuid = UUID.fromString(idString)
                        CountryManager.getCountry(uuid)
                    } catch (e: IllegalArgumentException) {
                        pluginLogger.warning("[忠诚度系统] 跳过无效的UUID: '${row[Countries.id].value}' - ${e.message}")
                        null
                    } catch (e: Exception) {
                        pluginLogger.warning("[忠诚度系统] 获取国家时出错: ${e.message}")
                        null
                    }
                }.filterNotNull()
            }

            countries.forEach { country ->
                val territories = TerritoryManager.getTerritoriesByCountry(country)

                territories.forEach { territory ->
                    processedTerritories++

                    // 使用统一的忠诚度更新逻辑
                    val result = updateTerritoryLoyalty(territory)

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
            // 减少忠诚度
            val oldLoyalty = territory.loyalty
            territory.loyalty = (territory.loyalty - LOYALTY_DECAY_AMOUNT).coerceAtLeast(0)
            territory.lastLoyaltyUpdateTime = currentTime
            territory.save()

            pluginLogger.fine("领土(${territory.x}, ${territory.z})忠诚度从${oldLoyalty}降低到${territory.loyalty}")

            // 检查是否忠诚度归零
            if (territory.loyalty <= 0) {
                return LoyaltyUpdateResult.DESTROYED
            } else {
                return LoyaltyUpdateResult.DECREASED
            }
        } else {
            // 更新检查时间，但不减少忠诚度
            territory.lastLoyaltyUpdateTime = currentTime
            territory.save()
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
     */
    private fun handleTerritoryDestruction(territory: TerritoryBlock, country: cn.lcofficial.guozhan.data.Country) {
        if (territory.isCapital) {
            // 王城区块忠诚度为0，触发灭国
            pluginLogger.info("国家 ${country.name} 的王城忠诚度归零，触发灭国")
            territory.owner = null
            Bukkit.broadcastMessage("§c[国战] ${country.name} 已被灭国！")
        } else {
            // 普通区块忠诚度为0，变为无主区块
            pluginLogger.fine("领土(${territory.x}, ${territory.z})忠诚度归零，变为无主区块")
            territory.owner = null
        }
        territory.save()
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

                // 成本 = 该区块每小时收入 × 0.5小时
                val territoryCost = (totalIncomePerHour * 0.5).toInt()
                totalCost += territoryCost
            }
        }

        if (country.gold >= totalCost) {
            country.gold -= totalCost
            country.save()

            territories.forEach { territory ->
                territory.loyalty = 100
                territory.save()
            }

            Bukkit.broadcastMessage("§a[国战] ${country.name}已恢复所有领土忠诚度！")
        }
    }
}