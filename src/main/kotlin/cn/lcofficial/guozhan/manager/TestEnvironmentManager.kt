package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.config.Config
import cn.lcofficial.guozhan.data.Country
import cn.lcofficial.guozhan.manager.UserManager.user
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 测试环境管理器
 * 负责在测试环境中自动分配资源，提高测试效率
 */
object TestEnvironmentManager {
    
    /**
     * 检查是否启用了测试环境
     */
    fun isTestEnvironmentEnabled(): Boolean {
        return Config.TestEnvironment.enabled
    }
    
    /**
     * 当玩家首次进入服务器时给予资源
     */
    fun givePlayerStartupResources(player: Player) {
        if (!isTestEnvironmentEnabled() || !Config.TestEnvironment.autoGivePlayerResources) {
            return
        }
        
        val user = player.user()
        
        // 检查玩家是否已经获得过启动资源（通过检查是否有足够的资源）
        val hasEnoughResources = player.inventory.contains(Material.IRON_INGOT, 64) &&
                                 player.inventory.contains(Material.GOLD_INGOT, 100) &&
                                 player.inventory.contains(Material.DIAMOND, 50)
        
        if (hasEnoughResources) {
            return // 玩家已经有足够资源，不重复给予
        }
        
        // 给予个人资源
        val resources = mapOf(
            Material.IRON_INGOT to Config.TestEnvironment.PlayerResources.ironIngot,
            Material.GOLD_INGOT to Config.TestEnvironment.PlayerResources.gold,
            Material.DIAMOND to Config.TestEnvironment.PlayerResources.diamond,
            Material.EMERALD to Config.TestEnvironment.PlayerResources.emerald,
            Material.DIRT to Config.TestEnvironment.PlayerResources.dirt // 🔧 v1.3.34: 添加泥土方块
        )

        resources.forEach { (material, amount) ->
            giveItemSafely(player, material, amount)
        }

        player.sendMessage("§a[测试环境] 已自动给予启动资源！")
        player.sendMessage("§7- 铁锭: ${Config.TestEnvironment.PlayerResources.ironIngot}")
        player.sendMessage("§7- 金锭: ${Config.TestEnvironment.PlayerResources.gold}")
        player.sendMessage("§7- 钻石: ${Config.TestEnvironment.PlayerResources.diamond}")
        player.sendMessage("§7- 绿宝石: ${Config.TestEnvironment.PlayerResources.emerald}")
        player.sendMessage("§7- 泥土: ${Config.TestEnvironment.PlayerResources.dirt}")
    }
    
    /**
     * 当玩家创建国家时给予国家资源
     * 🔧 v1.3.51: 修复国库资源重复发放问题 - 添加检查机制防止重复给予启动资源
     */
    fun giveCountryStartupResources(country: Country, creator: Player) {
        if (!isTestEnvironmentEnabled() || !Config.TestEnvironment.autoGiveCountryResources) {
            return
        }

        // 🔧 v1.3.51: 检查国家是否已经获得过启动资源
        // 通过检查国家资源是否已经达到或超过启动资源配置值来判断
        val hasEnoughGold = country.gold >= Config.TestEnvironment.CountryResources.gold
        val hasEnoughDiamond = country.diamond >= Config.TestEnvironment.CountryResources.diamond
        val hasEnoughEconomyPoints = country.economyPoints >= Config.TestEnvironment.CountryResources.economyPoints

        if (hasEnoughGold && hasEnoughDiamond && hasEnoughEconomyPoints) {
            // 国家已经有足够资源，不重复给予
            creator.sendMessage("§e[测试环境] 国家已拥有足够的启动资源，跳过发放")
            return
        }

        // 给予国家资源（只给予缺少的部分）
        val goldToGive = maxOf(0, Config.TestEnvironment.CountryResources.gold - country.gold)
        val diamondToGive = maxOf(0, Config.TestEnvironment.CountryResources.diamond - country.diamond)
        val economyPointsToGive = maxOf(0, Config.TestEnvironment.CountryResources.economyPoints - country.economyPoints)

        country.gold += goldToGive
        country.diamond += diamondToGive
        country.economyPoints += economyPointsToGive
        country.save()

        creator.sendMessage("§a[测试环境] 国家已自动获得启动资源！")
        if (goldToGive > 0) creator.sendMessage("§7- 金币: +${goldToGive}")
        if (diamondToGive > 0) creator.sendMessage("§7- 钻石: +${diamondToGive}")
        if (economyPointsToGive > 0) creator.sendMessage("§7- 经济点数: +${economyPointsToGive}")

        // 记录日志
        cn.lcofficial.guozhan.Guozhan.instance.logger.info("[测试环境] 为国家 ${country.name} 发放启动资源: 金币+${goldToGive}, 钻石+${diamondToGive}, 经济点数+${economyPointsToGive}")
    }
    
    /**
     * 安全地给予玩家物品，如果背包满了则掉落到地上
     * 🔧 v1.3.32: 修复测试环境资源配置过量问题 - 改善资源给予方式
     */
    private fun giveItemSafely(player: Player, material: Material, amount: Int) {
        val maxStackSize = material.maxStackSize
        var remainingAmount = amount
        var droppedItems = 0

        while (remainingAmount > 0) {
            val stackSize = minOf(remainingAmount, maxStackSize)
            val itemStack = ItemStack(material, stackSize)

            val leftOver = player.inventory.addItem(itemStack)
            if (leftOver.isNotEmpty()) {
                // 背包满了，掉落到地上
                leftOver.values.forEach { item ->
                    player.world.dropItemNaturally(player.location, item)
                    droppedItems += item.amount
                }
            }

            remainingAmount -= stackSize
        }

        // 如果有物品掉落，提醒玩家
        if (droppedItems > 0) {
            player.sendMessage("§e[测试环境] 背包空间不足，${droppedItems} 个 ${material.name} 已掉落到地上！")
            player.sendMessage("§7建议清理背包后再获取资源，或使用 /u contribute 将多余物品贡献给国家")
        }
    }
    
    /**
     * 为GM提供的快速资源补充命令
     */
    fun giveTestResources(player: Player, resourceType: String, amount: Int): Boolean {
        if (!isTestEnvironmentEnabled()) {
            player.sendMessage("§c测试环境未启用！")
            return false
        }
        
        val material = when (resourceType.lowercase()) {
            "iron", "铁", "铁锭" -> Material.IRON_INGOT
            "gold", "金", "金锭" -> Material.GOLD_INGOT
            "diamond", "钻石" -> Material.DIAMOND
            "emerald", "绿宝石" -> Material.EMERALD
            "coal", "煤炭" -> Material.COAL
            "redstone", "红石" -> Material.REDSTONE
            else -> {
                player.sendMessage("§c未知的资源类型: $resourceType")
                return false
            }
        }
        
        giveItemSafely(player, material, amount)
        player.sendMessage("§a[测试环境] 已给予 ${amount} 个 ${material.name}")
        return true
    }
    
    /**
     * 为国家补充资源（GM命令）
     */
    fun giveCountryTestResources(country: Country, resourceType: String, amount: Int, executor: Player): Boolean {
        if (!isTestEnvironmentEnabled()) {
            executor.sendMessage("§c测试环境未启用！")
            return false
        }
        
        when (resourceType.lowercase()) {
            "gold", "金币" -> {
                country.gold += amount
                country.save()
                executor.sendMessage("§a[测试环境] 已为国家 ${country.name} 增加 ${amount} 金币")
            }
            "diamond", "钻石" -> {
                country.diamond += amount
                country.save()
                executor.sendMessage("§a[测试环境] 已为国家 ${country.name} 增加 ${amount} 钻石")
            }
            "economy", "经济点数" -> {
                country.economyPoints += amount
                country.save()
                executor.sendMessage("§a[测试环境] 已为国家 ${country.name} 增加 ${amount} 经济点数")
            }
            else -> {
                executor.sendMessage("§c未知的国家资源类型: $resourceType")
                return false
            }
        }
        
        return true
    }
    
    /**
     * 显示测试环境状态
     */
    fun showTestEnvironmentStatus(player: Player) {
        player.sendMessage("§6===== 测试环境状态 =====")
        player.sendMessage("§7启用状态: ${if (isTestEnvironmentEnabled()) "§a启用" else "§c禁用"}")
        
        if (isTestEnvironmentEnabled()) {
            player.sendMessage("§7自动给予玩家资源: ${if (Config.TestEnvironment.autoGivePlayerResources) "§a启用" else "§c禁用"}")
            player.sendMessage("§7自动给予国家资源: ${if (Config.TestEnvironment.autoGiveCountryResources) "§a启用" else "§c禁用"}")
            
            player.sendMessage("§6玩家启动资源:")
            player.sendMessage("§7- 铁锭: ${Config.TestEnvironment.PlayerResources.ironIngot}")
            player.sendMessage("§7- 金锭: ${Config.TestEnvironment.PlayerResources.gold}")
            player.sendMessage("§7- 钻石: ${Config.TestEnvironment.PlayerResources.diamond}")
            player.sendMessage("§7- 绿宝石: ${Config.TestEnvironment.PlayerResources.emerald}")
            
            player.sendMessage("§6国家启动资源:")
            player.sendMessage("§7- 金币: ${Config.TestEnvironment.CountryResources.gold}")
            player.sendMessage("§7- 钻石: ${Config.TestEnvironment.CountryResources.diamond}")
            player.sendMessage("§7- 经济点数: ${Config.TestEnvironment.CountryResources.economyPoints}")
        }
    }
}
