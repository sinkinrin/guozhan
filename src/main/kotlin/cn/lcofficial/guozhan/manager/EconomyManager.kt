package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.data.Country
import cn.lcofficial.guozhan.data.ResourceType
import cn.lcofficial.guozhan.data.User
import cn.lcofficial.guozhan.pluginLogger
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * 经济管理器，负责处理国家经济、税收和资源上贡
 */
object EconomyManager {

    // 🔧 v1.3.37: 移除税率内存缓存 - 税率现在直接存储在Country对象中并持久化到数据库
    // private val taxRates = ConcurrentHashMap<UUID, Int>() // 已移除

    // 🔧 v1.3.42: 移除税收时间内存缓存 - 税收时间现在直接存储在Country对象中并持久化到数据库
    // private val lastTaxCollectionTime = ConcurrentHashMap<UUID, Long>() // 已移除
    
    // 税收周期（毫秒），默认24小时
    const val TAX_CYCLE = 24 * 60 * 60 * 1000L
    
    // 每个资源类型的基础价值
    val resourceValues = mapOf(
        ResourceType.GOLD to 10,
        ResourceType.DIAMOND to 20,
        ResourceType.IRON to 5,
        ResourceType.FOOD to 3,
        ResourceType.NONE to 0
    )
    
    /**
     * 获取国家的税率
     * 🔧 v1.3.37: 修复税率持久化缺失 - 直接从Country对象读取税率
     * @param country 国家
     * @return 税率（百分比）
     */
    fun getTaxRate(country: Country): Int {
        return country.taxRate
    }

    /**
     * 设置国家的税率
     * 🔧 v1.3.37: 修复税率持久化缺失 - 直接设置Country对象的税率并保存到数据库
     * @param country 国家
     * @param rate 税率（百分比，0-30）
     */
    fun setTaxRate(country: Country, rate: Int) {
        // 限制税率在0-30%之间
        val validRate = rate.coerceIn(0, 30)
        country.taxRate = validRate
        country.save() // 🔧 v1.3.37: 立即保存到数据库，确保持久化
    }
    
    /**
     * 检查国家是否可以收税
     * 🔧 v1.3.42: 修复税收冷却时间不持久化 - 从数据库读取上次收税时间
     * @param country 国家
     * @return 是否可以收税
     */
    fun canCollectTax(country: Country): Boolean {
        // 从数据库字段读取上次收税时间，而不是内存Map
        val lastCollection = country.lastManualTaxTime
        return System.currentTimeMillis() - lastCollection >= TAX_CYCLE
    }
    
    /**
     * 收取国家税收
     * @param country 国家
     * @return 收取的税收总额
     */
    fun collectTax(country: Country): Int {
        if (!canCollectTax(country)) {
            return 0
        }
        
        val taxRate = getTaxRate(country)
        if (taxRate <= 0) {
            return 0
        }
        
        var totalTax = 0
        
        // 从每个领土收取税收
        val territories = TerritoryManager.getTerritoriesByCountry(country)
        for (territory in territories) {
            // 根据领土忠诚度和资源类型计算税收
            val resourceValue = resourceValues[territory.resourceType] ?: 0
            val loyaltyFactor = territory.loyalty / 100.0
            val territoryTax = (resourceValue * loyaltyFactor * taxRate / 100.0).toInt()
            
            totalTax += territoryTax
        }
        
        // 🔧 v1.3.42: 修复税收冷却时间不持久化 - 更新数据库字段而不是内存Map
        // 更新收税时间到数据库
        country.lastManualTaxTime = System.currentTimeMillis()

        // 将税收加入国家金库
        country.gold += totalTax
        country.save() // 保存包括lastManualTaxTime在内的所有字段
        
        return totalTax
    }
    
    /**
     * 玩家向国家上贡资源
     * 🔧 v1.3.46: 修复上贡功能线程阻塞问题 - 完全异步化，避免使用CompletableFuture.get()阻塞线程
     * @param player 玩家
     * @param country 国家
     * @param material 物品类型
     * @param amount 数量
     * @param callback 完成回调，传入是否成功
     */
    fun contributeResource(player: Player, country: Country, material: Material, amount: Int, callback: (Boolean) -> Unit = {}) {
        // 🔧 v1.3.46: 完全异步化，在EntityScheduler中完成背包操作
        player.scheduler.run(cn.lcofficial.guozhan.Guozhan.instance, { _ ->
            try {
                // 检查玩家是否有足够的物品
                if (!player.hasEnoughItemSafely(material, amount)) {
                    player.sendMessage("§c你没有足够的${material.name}来上贡！")
                    callback(false)
                    return@run
                }

                // 扣除玩家物品
                if (!player.takeItemSafely(material, amount)) {
                    player.sendMessage("§c扣除物品失败！")
                    callback(false)
                    return@run
                }

                // 准备上贡数据
                val contributionData = when (material) {
                    Material.GOLD_INGOT -> ContributionData(
                        goldIncrease = amount * 2,
                        diamondIncrease = 0,
                        loyaltyTerritories = emptyList(),
                        successMessage = "§a成功向国家上贡了${amount}个金锭，国家获得了${amount * 2}单位黄金！"
                    )
                    Material.DIAMOND -> ContributionData(
                        goldIncrease = 0,
                        diamondIncrease = amount * 3,
                        loyaltyTerritories = emptyList(),
                        successMessage = "§a成功向国家上贡了${amount}个钻石，国家获得了${amount * 3}单位钻石！"
                    )
                    Material.IRON_INGOT -> ContributionData(
                        goldIncrease = amount,
                        diamondIncrease = 0,
                        loyaltyTerritories = emptyList(),
                        successMessage = "§a成功向国家上贡了${amount}个铁锭，国家获得了${amount}单位黄金！"
                    )
                    Material.BREAD, Material.WHEAT -> {
                        // 准备忠诚度提升的领土列表
                        val territories = TerritoryManager.getTerritoriesByCountry(country)
                        val loyaltyIncrease = kotlin.math.min(amount, 10)
                        val affectedTerritories = territories.filter { it.loyalty < 100 }.take(10)

                        ContributionData(
                            goldIncrease = 0,
                            diamondIncrease = 0,
                            loyaltyTerritories = affectedTerritories.map { it.id to loyaltyIncrease },
                            successMessage = "§a成功向国家上贡了${amount}个${if (material == Material.BREAD) "面包" else "小麦"}，提高了${affectedTerritories.size}块领土的忠诚度！"
                        )
                    }
                    else -> ContributionData(
                        goldIncrease = amount,
                        diamondIncrease = 0,
                        loyaltyTerritories = emptyList(),
                        successMessage = "§a成功向国家上贡了${amount}个${material.name}，国家获得了${amount}单位黄金！"
                    )
                }

                // 🔧 v1.3.46: 直接在GlobalRegionScheduler中完成国家资源修改，无需等待
                cn.lcofficial.guozhan.util.run {
                    try {

                        // 🔧 v1.3.46: 修复上贡功能的国库资源更新问题 - 添加详细日志记录
                        val oldGold = country.gold
                        val oldDiamond = country.diamond

                        cn.lcofficial.guozhan.Guozhan.instance.logger.info("🔧 [上贡] 开始处理玩家 ${player.name} 的上贡：${amount} 个 ${material.name}")
                        cn.lcofficial.guozhan.Guozhan.instance.logger.info("🔧 [上贡] 上贡前国库状态 - 金币: $oldGold, 钻石: $oldDiamond")
                        cn.lcofficial.guozhan.Guozhan.instance.logger.info("🔧 [上贡] 计算的资源增加 - 金币: +${contributionData.goldIncrease}, 钻石: +${contributionData.diamondIncrease}")

                        // 更新国家资源
                        country.gold += contributionData.goldIncrease
                        country.diamond += contributionData.diamondIncrease

                        cn.lcofficial.guozhan.Guozhan.instance.logger.info("🔧 [上贡] 更新后国库状态 - 金币: ${country.gold}, 钻石: ${country.diamond}")

                        // 更新领土忠诚度
                        for ((territoryId, loyaltyIncrease) in contributionData.loyaltyTerritories) {
                            val territory = TerritoryManager.getTerritoryBlock(territoryId)
                            if (territory != null && territory.loyalty < 100) {
                                val oldLoyalty = territory.loyalty
                                territory.loyalty = kotlin.math.min(100, territory.loyalty + loyaltyIncrease)
                                territory.save()
                                cn.lcofficial.guozhan.Guozhan.instance.logger.info("🔧 [上贡] 领土 (${territory.x}, ${territory.z}) 忠诚度从 $oldLoyalty 提升到 ${territory.loyalty}")
                            }
                        }

                        // 保存国家数据到数据库
                        country.save()
                        cn.lcofficial.guozhan.Guozhan.instance.logger.info("🔧 [上贡] 国家数据已保存到数据库")

                        // 发送成功消息
                        player.scheduler.run(cn.lcofficial.guozhan.Guozhan.instance, { _ ->
                            player.sendMessage(contributionData.successMessage)
                            // 额外发送国库状态信息
                            player.sendMessage("§7当前国库：金币 ${country.gold}, 钻石 ${country.diamond}")
                        }, null)

                        // 记录成功日志
                        cn.lcofficial.guozhan.Guozhan.instance.logger.info("✅ [上贡] 玩家 ${player.name} 向国家 ${country.name} 上贡成功：${amount} 个 ${material.name} → 金币 +${contributionData.goldIncrease}, 钻石 +${contributionData.diamondIncrease}")

                        callback(true)
                    } catch (e: Exception) {
                        cn.lcofficial.guozhan.Guozhan.instance.logger.severe("❌ [上贡] 国家资源更新出错: ${e.message}")
                        e.printStackTrace()

                        // 发送错误消息给玩家
                        player.scheduler.run(cn.lcofficial.guozhan.Guozhan.instance, { _ ->
                            player.sendMessage("§c上贡过程中发生错误，请联系管理员！")
                        }, null)

                        callback(false)
                    }
                }

            } catch (e: Exception) {
                cn.lcofficial.guozhan.Guozhan.instance.logger.severe("❌ [上贡] 玩家背包操作出错: ${e.message}")
                e.printStackTrace()
                player.sendMessage("§c上贡失败，请重试！")
                callback(false)
            }
        }, null)
    }

    /**
     * 🔧 v1.3.43: 上贡数据类，用于在线程间传递上贡信息
     */
    private data class ContributionData(
        val goldIncrease: Int,
        val diamondIncrease: Int,
        val loyaltyTerritories: List<Pair<UUID, Int>>, // 领土ID到忠诚度增加值的映射
        val successMessage: String
    )
    
    /**
     * 分配国家资源给玩家
     * 🔧 v1.3.48: 修复Critical问题4.1 - 移除CompletableFuture.get()阻塞，改为完全异步回调模式
     * 🔧 v1.3.45: 修复国库分配功能资源丢失问题 - 处理玩家离线和背包满溢出情况
     * @param country 国家
     * @param user 用户
     * @param goldAmount 黄金数量
     * @param diamondAmount 钻石数量
     * @param callback 完成回调，传入是否成功分配
     */
    fun distributeResources(country: Country, user: User, goldAmount: Int, diamondAmount: Int, callback: (Boolean) -> Unit = {}) {
        // 检查权限
        if (user.rank.value < 2) { // 只有国王和管理员可以分配资源
            callback(false)
            return
        }

        // 检查资源是否足够
        if (country.gold < goldAmount || country.diamond < diamondAmount) {
            callback(false)
            return
        }

        // 🔧 v1.3.48: 修复Critical问题4.1 - 移除CompletableFuture，改为完全异步回调模式

        cn.lcofficial.guozhan.util.run {
            try {
                // 再次检查资源是否足够（原子性检查）
                if (country.gold < goldAmount || country.diamond < diamondAmount) {
                    cn.lcofficial.guozhan.Guozhan.instance.logger.warning("🔧 [国库分配] 资源不足（竞态检查）：需要金币 $goldAmount，钻石 $diamondAmount，当前金币 ${country.gold}，钻石 ${country.diamond}")
                    callback(false)
                    return@run
                }

                val oldGold = country.gold
                val oldDiamond = country.diamond

                // 获取玩家实例
                val player = user.player
                if (player == null) {
                    // 🔧 v1.3.45: 玩家离线时不扣除资源，避免资源丢失
                    cn.lcofficial.guozhan.Guozhan.instance.logger.warning("🔧 [国库分配] 玩家 ${user.name} 不在线，取消分配操作以避免资源丢失")
                    callback(false)
                    return@run
                }

                // 先扣除国家资源
                country.gold -= goldAmount
                country.diamond -= diamondAmount
                country.save()

                cn.lcofficial.guozhan.Guozhan.instance.logger.info("🔧 [国库分配] 国库资源扣除成功：金币 $oldGold → ${country.gold} (-$goldAmount)，钻石 $oldDiamond → ${country.diamond} (-$diamondAmount)")

                // 使用EntityScheduler确保在正确的线程中操作背包
                player.scheduler.run(cn.lcofficial.guozhan.Guozhan.instance, { _ ->
                    try {
                        var refundGold = 0
                        var refundDiamond = 0

                        // 🔧 v1.3.45: 检查addItem返回值，处理背包满溢出的物品
                        if (goldAmount > 0) {
                            val goldStack = ItemStack(Material.GOLD_INGOT, goldAmount)
                            val remainingGold = player.inventory.addItem(goldStack)
                            if (remainingGold.isNotEmpty()) {
                                // 背包满了，计算溢出的金币数量
                                refundGold = remainingGold.values.sumOf { it.amount }
                                cn.lcofficial.guozhan.Guozhan.instance.logger.warning("🔧 [国库分配] 玩家 ${user.name} 背包已满，金币溢出 $refundGold 个")

                                // 将溢出的金币掉落到玩家位置
                                remainingGold.values.forEach { item ->
                                    player.world.dropItemNaturally(player.location, item)
                                }
                                cn.lcofficial.guozhan.Guozhan.instance.logger.info("🔧 [国库分配] 已将溢出的 $refundGold 个金币掉落到玩家 ${user.name} 位置")
                            }
                            val actualGold = goldAmount - refundGold
                            if (actualGold > 0) {
                                cn.lcofficial.guozhan.Guozhan.instance.logger.info("🔧 [国库分配] 向玩家 ${user.name} 发放了 $actualGold 金币")
                            }
                        }

                        if (diamondAmount > 0) {
                            val diamondStack = ItemStack(Material.DIAMOND, diamondAmount)
                            val remainingDiamond = player.inventory.addItem(diamondStack)
                            if (remainingDiamond.isNotEmpty()) {
                                // 背包满了，计算溢出的钻石数量
                                refundDiamond = remainingDiamond.values.sumOf { it.amount }
                                cn.lcofficial.guozhan.Guozhan.instance.logger.warning("🔧 [国库分配] 玩家 ${user.name} 背包已满，钻石溢出 $refundDiamond 个")

                                // 将溢出的钻石掉落到玩家位置
                                remainingDiamond.values.forEach { item ->
                                    player.world.dropItemNaturally(player.location, item)
                                }
                                cn.lcofficial.guozhan.Guozhan.instance.logger.info("🔧 [国库分配] 已将溢出的 $refundDiamond 个钻石掉落到玩家 ${user.name} 位置")
                            }
                            val actualDiamond = diamondAmount - refundDiamond
                            if (actualDiamond > 0) {
                                cn.lcofficial.guozhan.Guozhan.instance.logger.info("🔧 [国库分配] 向玩家 ${user.name} 发放了 $actualDiamond 钻石")
                            }
                        }

                        // 发送成功消息
                        val actualGold = goldAmount - refundGold
                        val actualDiamond = diamondAmount - refundDiamond
                        player.sendMessage("§a成功从国库获得：金币 $actualGold，钻石 $actualDiamond")

                        if (refundGold > 0 || refundDiamond > 0) {
                            player.sendMessage("§e背包已满，部分物品已掉落到您的位置：金币 $refundGold，钻石 $refundDiamond")
                        }

                        // 🔧 v1.3.48: 修复High问题4 - 资源分配回调时机错误
                        // 成功完成背包操作后才调用callback
                        callback(true)
                        cn.lcofficial.guozhan.Guozhan.instance.logger.info("✅ [国库分配] 国家 ${country.name} 成功分配资源：金币 $goldAmount，钻石 $diamondAmount 给玩家 ${user.name}")

                    } catch (e: Exception) {
                        cn.lcofficial.guozhan.Guozhan.instance.logger.severe("❌ [国库分配] 背包操作出错: ${e.message}")
                        e.printStackTrace()

                        // 🔧 v1.3.45: 背包操作失败时，退回资源到国库
                        cn.lcofficial.guozhan.util.run {
                            country.gold += goldAmount
                            country.diamond += diamondAmount
                            country.save()
                            cn.lcofficial.guozhan.Guozhan.instance.logger.info("🔧 [国库分配] 背包操作失败，已退回资源到国库：金币 +$goldAmount，钻石 +$diamondAmount")
                        }

                        // 🔧 v1.3.48: 背包操作失败时回调false
                        callback(false)
                    }
                }, null)
            } catch (e: Exception) {
                cn.lcofficial.guozhan.Guozhan.instance.logger.severe("❌ [国库分配] 资源分配出错: ${e.message}")
                e.printStackTrace()
                callback(false)
            }
        }
    }
}

/**
 * Player扩展方法：检查玩家是否有足够的物品（线程安全版本）
 * 🔧 v1.3.42: 修复玩家背包操作违反Folia线程规则 - 确保在EntityScheduler中调用
 */
fun Player.hasEnoughItemSafely(material: Material, amount: Int): Boolean {
    var totalAmount = 0
    for (item in inventory.contents) {
        if (item != null && item.type == material) {
            totalAmount += item.amount
            if (totalAmount >= amount) {
                return true
            }
        }
    }
    return false
}

/**
 * Player扩展方法：从玩家背包中扣除指定数量的物品（线程安全版本）
 * 🔧 v1.3.42: 修复玩家背包操作违反Folia线程规则 - 确保在EntityScheduler中调用
 */
fun Player.takeItemSafely(material: Material, amount: Int): Boolean {
    if (!hasEnoughItemSafely(material, amount)) {
        return false
    }

    var remainingAmount = amount
    val contents = inventory.contents

    for (i in contents.indices) {
        val item = contents[i]
        if (item != null && item.type == material) {
            val itemAmount = item.amount
            if (itemAmount <= remainingAmount) {
                // 完全移除这个物品堆
                contents[i] = null
                remainingAmount -= itemAmount
            } else {
                // 部分移除
                item.amount = itemAmount - remainingAmount
                remainingAmount = 0
            }

            if (remainingAmount == 0) {
                break
            }
        }
    }

    inventory.contents = contents
    return true
}

/**
 * Player扩展方法：检查玩家是否有足够的物品（已弃用，使用hasEnoughItemSafely）
 * @deprecated 使用hasEnoughItemSafely替代，确保在EntityScheduler中调用
 */
@Deprecated("使用hasEnoughItemSafely替代，确保在EntityScheduler中调用")
fun Player.hasEnoughItem(material: Material, amount: Int): Boolean {
    return hasEnoughItemSafely(material, amount)
}

/**
 * Player扩展方法：从玩家背包中扣除指定数量的物品（已弃用，使用takeItemSafely）
 * @deprecated 使用takeItemSafely替代，确保在EntityScheduler中调用
 */
@Deprecated("使用takeItemSafely替代，确保在EntityScheduler中调用")
fun Player.takeItem(material: Material, amount: Int): Boolean {
    return takeItemSafely(material, amount)
}