package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.data.Country
import cn.lcofficial.guozhan.data.ResourceType
import cn.lcofficial.guozhan.data.User
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.*
import kotlin.math.min

/**
 * 经济管理器，负责处理国家经济、税收和资源上贡
 */
object EconomyManager {
    
    // 记录每个国家的税率，默认为10%
    private val taxRates = mutableMapOf<UUID, Int>()
    
    // 记录每个国家的上次收税时间
    private val lastTaxCollectionTime = mutableMapOf<UUID, Long>()
    
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
     * @param country 国家
     * @return 税率（百分比）
     */
    fun getTaxRate(country: Country): Int {
        return taxRates.getOrDefault(country.id, 10)
    }
    
    /**
     * 设置国家的税率
     * @param country 国家
     * @param rate 税率（百分比，0-30）
     */
    fun setTaxRate(country: Country, rate: Int) {
        // 限制税率在0-30%之间
        val validRate = rate.coerceIn(0, 30)
        taxRates[country.id] = validRate
    }
    
    /**
     * 检查国家是否可以收税
     * @param country 国家
     * @return 是否可以收税
     */
    fun canCollectTax(country: Country): Boolean {
        val lastCollection = lastTaxCollectionTime.getOrDefault(country.id, 0L)
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
        
        // 更新收税时间
        lastTaxCollectionTime[country.id] = System.currentTimeMillis()
        
        // 将税收加入国家金库
        country.gold += totalTax
        country.save()
        
        return totalTax
    }
    
    /**
     * 玩家向国家上贡资源
     * @param player 玩家
     * @param country 国家
     * @param material 物品类型
     * @param amount 数量
     * @return 是否成功上贡
     */
    fun contributeResource(player: Player, country: Country, material: Material, amount: Int): Boolean {
        // 检查玩家是否有足够的物品
        if (!player.hasEnoughItem(material, amount)) {
            return false
        }
        
        // 扣除玩家物品
        player.takeItem(material, amount)
        
        // 根据物品类型增加国家资源
        when (material) {
            Material.GOLD_INGOT -> {
                country.gold += amount * 2
                player.sendMessage("§a成功向国家上贡了${amount}个金锭，国家获得了${amount * 2}单位黄金！")
            }
            Material.DIAMOND -> {
                country.diamond += amount * 3
                player.sendMessage("§a成功向国家上贡了${amount}个钻石，国家获得了${amount * 3}单位钻石！")
            }
            Material.IRON_INGOT -> {
                country.gold += amount
                player.sendMessage("§a成功向国家上贡了${amount}个铁锭，国家获得了${amount}单位黄金！")
            }
            Material.BREAD, Material.WHEAT -> {
                // 增加领土忠诚度
                val territories = TerritoryManager.getTerritoriesByCountry(country)
                val loyaltyIncrease = min(amount, 10)
                
                var affectedTerritories = 0
                for (territory in territories) {
                    if (territory.loyalty < 100) {
                        territory.loyalty = min(100, territory.loyalty + loyaltyIncrease)
                        territory.save()
                        affectedTerritories++
                    }
                    
                    // 最多影响10个领土
                    if (affectedTerritories >= 10) {
                        break
                    }
                }
                
                player.sendMessage("§a成功向国家上贡了${amount}个${if (material == Material.BREAD) "面包" else "小麦"}，提高了${affectedTerritories}块领土的忠诚度！")
            }
            else -> {
                // 其他物品按1:1转换为黄金
                country.gold += amount
                player.sendMessage("§a成功向国家上贡了${amount}个${material.name}，国家获得了${amount}单位黄金！")
            }
        }
        
        country.save()
        return true
    }
    
    /**
     * 分配国家资源给玩家
     * @param country 国家
     * @param user 用户
     * @param goldAmount 黄金数量
     * @param diamondAmount 钻石数量
     * @return 是否成功分配
     */
    fun distributeResources(country: Country, user: User, goldAmount: Int, diamondAmount: Int): Boolean {
        // 检查权限
        if (user.rank.value < 2) { // 只有国王和管理员可以分配资源
            return false
        }
        
        // 检查资源是否足够
        if (country.gold < goldAmount || country.diamond < diamondAmount) {
            return false
        }
        
        // 扣除国家资源
        country.gold -= goldAmount
        country.diamond -= diamondAmount
        country.save()
        
        // 获取玩家实例并给予物品
        val player = user.player
        if (player != null) {
            if (goldAmount > 0) {
                player.inventory.addItem(ItemStack(Material.GOLD_INGOT, goldAmount / 2))
            }
            if (diamondAmount > 0) {
                player.inventory.addItem(ItemStack(Material.DIAMOND, diamondAmount / 3))
            }
        }
        
        return true
    }
}

/**
 * Player扩展方法：检查玩家是否有足够的物品
 */
fun Player.hasEnoughItem(material: Material, amount: Int): Boolean {
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
 * Player扩展方法：从玩家背包中扣除指定数量的物品
 */
fun Player.takeItem(material: Material, amount: Int): Boolean {
    if (!hasEnoughItem(material, amount)) {
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