package cn.lcofficial.guozhan.economy

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.config.DiplomacyConfig
import cn.lcofficial.guozhan.data.Country
import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.manager.EconomyManager
import cn.lcofficial.guozhan.manager.UserManager.user
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.*

/**
 * 资源上贡系统，处理高级上贡功能和国家间的资源贡献
 */
object TributeSystem {
    
    // 记录国家间的贡献关系
    private val tributeRelations = mutableMapOf<String, TributeRelation>()
    
    // 记录每个国家的贡献历史
    private val tributeHistory = mutableMapOf<UUID, MutableList<TributeRecord>>()
    
    /**
     * 初始化上贡系统
     */
    fun initialize() {
        Guozhan.instance.logger.info("正在初始化资源上贡系统...")
        loadTributeRelations()
        
        // 设置定时任务，每小时处理一次自动贡献
        Bukkit.getScheduler().runTaskTimerAsynchronously(Guozhan.instance, Runnable {
            try {
                val processedCount = processAutomaticTributes()
                Guozhan.instance.logger.info("自动贡献定时任务完成，处理了$processedCount个贡献关系")
            } catch (e: Exception) {
                Guozhan.instance.logger.severe("自动贡献定时任务出错: ${e.message}")
                e.printStackTrace()
            }
        }, 20 * 60 * 10, 20 * 60 * 60) // 10分钟后开始，每小时执行一次
    }
    
    /**
     * 加载所有贡献关系
     */
    private fun loadTributeRelations() {
        // 这里可以从数据库或配置文件加载贡献关系
        // 目前使用内存存储，后续可扩展为持久化存储
        tributeRelations.clear()
        tributeHistory.clear()
    }
    
    /**
     * 建立贡献关系
     * @param tributeCountry 进贡国家
     * @param receivingCountry 接受国家
     * @param tributeRate 贡献比率（百分比）
     * @return 是否成功建立关系
     */
    fun establishTributeRelation(tributeCountry: Country, receivingCountry: Country, tributeRate: Int): Boolean {
        // 检查是否已存在贡献关系
        val relationId = getTributeRelationId(tributeCountry, receivingCountry)
        if (tributeRelations.containsKey(relationId)) {
            return false
        }
        
        // 限制贡献比率在5-30%之间
        val validRate = tributeRate.coerceIn(5, 30)
        
        // 创建新的贡献关系
        val relation = TributeRelation(
            tributeCountryId = tributeCountry.id,
            receivingCountryId = receivingCountry.id,
            tributeRate = validRate,
            establishedTime = System.currentTimeMillis()
        )
        
        tributeRelations[relationId] = relation
        
        // 广播贡献关系建立消息
        val message = "§6${tributeCountry.name} §e与 §6${receivingCountry.name} §e建立了贡献关系，贡献比率为 §6${validRate}%§e！"
        Bukkit.getOnlinePlayers().forEach { player ->
            player.sendMessage(message)
        }
        
        return true
    }
    
    /**
     * 解除贡献关系
     * @param tributeCountry 进贡国家
     * @param receivingCountry 接受国家
     * @return 是否成功解除关系
     */
    fun removeTributeRelation(tributeCountry: Country, receivingCountry: Country): Boolean {
        val relationId = getTributeRelationId(tributeCountry, receivingCountry)
        val removed = tributeRelations.remove(relationId) != null
        
        if (removed) {
            // 广播贡献关系解除消息
            val message = "§6${tributeCountry.name} §e与 §6${receivingCountry.name} §e的贡献关系已解除！"
            Bukkit.getOnlinePlayers().forEach { player ->
                player.sendMessage(message)
            }
        }
        
        return removed
    }
    
    /**
     * 获取贡献关系ID
     * @param tributeCountry 贡献国家
     * @param receivingCountry 接收国家
     * @return 贡献关系ID
     */
    private fun getTributeRelationId(tributeCountry: Country, receivingCountry: Country): String {
        return "${tributeCountry.id}_${receivingCountry.id}"
    }
    
    /**
     * 进行资源上贡
     * @param player 玩家
     * @param targetCountry 目标国家
     * @param material 物品类型
     * @param amount 数量
     * @return 是否成功上贡
     */
    fun tributeResource(player: Player, targetCountry: Country, material: Material, amount: Int): Boolean {
        val user = player.user()
        val sourceCountry = user.country ?: return false
        
        // 不能向自己的国家上贡
        if (sourceCountry.id == targetCountry.id) {
            player.sendMessage("§c你不能向自己的国家上贡！")
            return false
        }
        
        // 检查玩家是否有足够的物品
        if (!player.hasEnoughItem(material, amount)) {
            player.sendMessage("§c你没有足够的${material.name}来上贡！")
            return false
        }
        
        // 扣除玩家物品
        player.takeItem(material, amount)
        
        // 计算资源价值
        val resourceValue = calculateResourceValue(material, amount)
        
        // 增加目标国家资源
        when (material) {
            Material.GOLD_INGOT -> targetCountry.gold += resourceValue
            Material.DIAMOND -> targetCountry.diamond += resourceValue / 2
            Material.NETHERITE_INGOT -> {
                // 下界合金是高价值资源，同时增加黄金和钻石
                targetCountry.gold += resourceValue / 2
                targetCountry.diamond += resourceValue / 4
            }
            Material.EMERALD -> {
                // 绿宝石主要增加黄金
                targetCountry.gold += resourceValue
            }
            else -> targetCountry.gold += resourceValue / 3
        }
        
        // 记录贡献历史
        recordTribute(sourceCountry.id, targetCountry.id, material, amount, resourceValue)
        
        // 增加外交关系友好度，根据资源价值提供额外加成
        val relationBonus = when {
            resourceValue >= 100 -> 3 // 大量高价值资源提供额外友好度
            resourceValue >= 50 -> 2 // 中等价值资源提供小额额外友好度
            else -> 1 // 普通上贡
        }
        
        // 多次调用以提供额外友好度加成
        for (i in 0 until relationBonus) {
            improveDiplomaticRelations(sourceCountry, targetCountry)
        }
        
        // 保存目标国家数据
        targetCountry.save()
        
        // 通知玩家
        player.sendMessage("§a成功向 §6${targetCountry.name} §a上贡了 §f${amount} §a个 §f${material.name}§a，价值 §f${resourceValue} §a单位资源！")
        if (relationBonus > 1) {
            player.sendMessage("§6由于贡品价值丰厚，获得了额外的外交关系提升！")
        }
        
        // 通知目标国家在线成员
        val targetMembers = Bukkit.getOnlinePlayers().filter { p ->
            val u = p.user()
            u.country?.id == targetCountry.id
        }
        
        targetMembers.forEach { p ->
            p.sendMessage("§6${sourceCountry.name} §a的 §f${player.name} §a向你们国家上贡了 §f${amount} §a个 §f${material.name}§a，价值 §f${resourceValue} §a单位资源！")
            if (relationBonus > 1) {
                p.sendMessage("§6由于贡品价值丰厚，双方关系得到了显著提升！")
            }
        }
        
        return true
    }
    
    /**
     * 计算资源价值
     * 根据不同资源类型和数量计算上贡价值
     * @param material 物品类型
     * @param amount 数量
     * @return 计算后的资源价值
     */
    private fun calculateResourceValue(material: Material, amount: Int): Int {
        // 基础价值
        val baseValue = when (material) {
            // 金属和矿物
            Material.GOLD_INGOT -> 2
            Material.GOLD_BLOCK -> 18
            Material.DIAMOND -> 4
            Material.DIAMOND_BLOCK -> 36
            Material.IRON_INGOT -> 1
            Material.IRON_BLOCK -> 9
            Material.EMERALD -> 3
            Material.EMERALD_BLOCK -> 27
            Material.NETHERITE_INGOT -> 10
            Material.NETHERITE_BLOCK -> 90
            Material.ANCIENT_DEBRIS -> 5
            
            // 食物资源
            Material.BREAD -> 1
            Material.WHEAT -> 1
            Material.COOKED_BEEF -> 2
            Material.GOLDEN_APPLE -> 8
            Material.ENCHANTED_GOLDEN_APPLE -> 50
            
            // 稀有资源
            Material.BEACON -> 80
            Material.DRAGON_EGG -> 100
            Material.ELYTRA -> 60
            Material.NETHER_STAR -> 40
            Material.TOTEM_OF_UNDYING -> 30
            
            // 其他资源
            Material.EXPERIENCE_BOTTLE -> 2
            Material.ENDER_PEARL -> 3
            Material.BLAZE_ROD -> 2
            Material.GHAST_TEAR -> 4
            
            // 默认值
            else -> amount / 2
        }
        
        // 计算总价值
        val totalValue = baseValue * amount
        
        // 大量资源提供额外价值加成
        val bonusMultiplier = when {
            amount >= 64 -> 1.5 // 整组资源提供50%额外价值
            amount >= 32 -> 1.2 // 半组资源提供20%额外价值
            amount >= 16 -> 1.1 // 四分之一组提供10%额外价值
            else -> 1.0
        }
        
        return (totalValue * bonusMultiplier).toInt()
    }
    
    /**
     * 记录贡献历史
     */
    private fun recordTribute(sourceCountryId: UUID, targetCountryId: UUID, material: Material, amount: Int, value: Int) {
        val record = TributeRecord(
            sourceCountryId = sourceCountryId,
            targetCountryId = targetCountryId,
            material = material.name,
            amount = amount,
            value = value,
            timestamp = System.currentTimeMillis()
        )
        
        // 添加到源国家的贡献历史
        if (!tributeHistory.containsKey(sourceCountryId)) {
            tributeHistory[sourceCountryId] = mutableListOf()
        }
        tributeHistory[sourceCountryId]!!.add(record)
        
        // 添加到目标国家的贡献历史
        if (!tributeHistory.containsKey(targetCountryId)) {
            tributeHistory[targetCountryId] = mutableListOf()
        }
        tributeHistory[targetCountryId]!!.add(record)
    }
    
    /**
     * 提高外交关系友好度
     * 根据上贡价值提高两国关系
     */
    private fun improveDiplomaticRelations(sourceCountry: Country, targetCountry: Country) {
        // 获取当前关系
        val relation = cn.lcofficial.guozhan.manager.DiplomacyManager.getRelation(sourceCountry, targetCountry)
        
        // 如果已经是同盟关系，不需要再提升
        if (relation.relationType == cn.lcofficial.guozhan.data.RelationType.ALLIANCE) {
            return
        }
        
        // 根据当前关系类型决定是否提升关系
        when (relation.relationType) {
            cn.lcofficial.guozhan.data.RelationType.HOSTILE -> {
                // 敌对关系需要多次上贡才能改善
                relation.friendliness += 5
                if (relation.friendliness >= 50) {
                    // 提升为中立关系
                    cn.lcofficial.guozhan.manager.DiplomacyManager.updateRelation(
                        sourceCountry, 
                        targetCountry, 
                        cn.lcofficial.guozhan.data.RelationType.NEUTRAL
                    )
                }
            }
            cn.lcofficial.guozhan.data.RelationType.NEUTRAL -> {
                // 中立关系较容易提升
                relation.friendliness += 10
                if (relation.friendliness >= 75) {
                    // 提升为友好关系
                    cn.lcofficial.guozhan.manager.DiplomacyManager.updateRelation(
                        sourceCountry, 
                        targetCountry, 
                        cn.lcofficial.guozhan.data.RelationType.FRIENDLY
                    )
                }
            }
            cn.lcofficial.guozhan.data.RelationType.FRIENDLY -> {
                // 友好关系需要持续上贡才能达到同盟
                relation.friendliness += 5
                if (relation.friendliness >= 90) {
                    // 提升为同盟关系
                    cn.lcofficial.guozhan.manager.DiplomacyManager.updateRelation(
                        sourceCountry, 
                        targetCountry, 
                        cn.lcofficial.guozhan.data.RelationType.ALLIANCE
                    )
                }
            }
            else -> {
                // 其他关系类型（如战争）暂不处理
                // 战争状态下的上贡可能需要特殊处理
            }
        }
        
        // 保存关系变更
        relation.save()
    }
    
    /**
     * 获取国家的贡献历史
     */
    fun getTributeHistory(country: Country): List<TributeRecord> {
        return tributeHistory[country.id] ?: emptyList()
    }
    
    /**
     * 手动处理所有贡献关系
     * 管理员命令调用
     * @param sender 命令发送者
     */
    fun manualProcessTributes(sender: CommandSender) {
        sender.sendMessage("§e[国家贡献] §f正在处理所有贡献关系...")
        
        // 使用异步任务处理贡献，避免卡服
        Bukkit.getScheduler().runTaskAsynchronously(Guozhan.instance, Runnable {
            try {
                val startTime = System.currentTimeMillis()
                val processedCount = processAutomaticTributes()
                val endTime = System.currentTimeMillis()
                
                // 计算处理时间
                val processingTime = (endTime - startTime) / 1000.0
                
                // 通知结果
                Bukkit.getScheduler().runTask(Guozhan.instance, Runnable {
                    sender.sendMessage("§e[国家贡献] §f处理完成！共处理了 §6$processedCount §f个贡献关系，耗时 §6$processingTime §f秒")
                })
            } catch (e: Exception) {
                Guozhan.instance.logger.severe("手动处理贡献关系出错: ${e.message}")
                e.printStackTrace()
                
                // 通知错误
                Bukkit.getScheduler().runTask(Guozhan.instance, Runnable {
                    sender.sendMessage("§c[国家贡献] §f处理过程中出现错误: §c${e.message}")
                })
            }
        })
    }
    
    /**
     * 获取国家的贡献关系
     */
    fun getTributeRelations(country: Country): List<TributeRelation> {
        return tributeRelations.values.filter { relation ->
            relation.tributeCountryId == country.id || relation.receivingCountryId == country.id
        }
    }
    
    /**
     * 获取国家的贡献关系
     * @param countryId 国家ID
     * @return 与该国家相关的所有贡献关系
     */
    fun getCountryTributeRelations(countryId: UUID): List<TributeRelation> {
        return tributeRelations.values.filter { it.tributeCountryId == countryId || it.receivingCountryId == countryId }
    }
    
    /**
     * 获取所有贡献关系
     * @return 所有贡献关系的列表
     */
    fun getAllTributeRelations(): List<TributeRelation> {
        return tributeRelations.values.toList()
    }
    
    /**
     * 自动处理贡献
     * 定时任务调用，处理所有贡献关系的资源转移
     * @return 处理的贡献关系数量
     */
    fun processAutomaticTributes(): Int {
        var processedCount = 0
        Guozhan.instance.logger.info("正在处理自动贡献关系...")
        
        for ((relationId, relation) in tributeRelations) {
            val tributeCountry = CountryManager.getCountryById(relation.tributeCountryId)
            val receivingCountry = CountryManager.getCountryById(relation.receivingCountryId)
            
            if (tributeCountry != null && receivingCountry != null) {
                // 检查两国关系，如果处于战争状态，暂停贡献
                if (cn.lcofficial.guozhan.manager.DiplomacyManager.areAtWar(tributeCountry, receivingCountry)) {
                    Guozhan.instance.logger.info("${tributeCountry.name}与${receivingCountry.name}处于战争状态，暂停贡献关系")
                    continue
                }
                
                // 计算需要贡献的资源
                val goldAmount = (tributeCountry.gold * relation.tributeRate / 100).toInt()
                val diamondAmount = (tributeCountry.diamond * relation.tributeRate / 100).toInt()
                
                // 确保不会贡献过多导致国家资源为负
                val safeGoldAmount = Math.min(goldAmount, tributeCountry.gold)
                val safeDiamondAmount = Math.min(diamondAmount, tributeCountry.diamond)
                
                if (safeGoldAmount > 0 || safeDiamondAmount > 0) {
                    processedCount++
                    
                    // 扣除贡献国家资源
                    tributeCountry.gold -= safeGoldAmount
                    tributeCountry.diamond -= safeDiamondAmount
                    
                    // 增加接收国家资源
                    receivingCountry.gold += safeGoldAmount
                    receivingCountry.diamond += safeDiamondAmount
                    
                    // 保存国家数据
                    tributeCountry.save()
                    receivingCountry.save()
                    
                    // 计算总价值
                    val totalValue = safeGoldAmount + (safeDiamondAmount * 2)
                    
                    // 记录贡献
                    val record = TributeRecord(
                        sourceCountryId = tributeCountry.id,
                        targetCountryId = receivingCountry.id,
                        material = "AUTOMATIC",
                        amount = 0,
                        value = totalValue,
                        timestamp = System.currentTimeMillis()
                    )
                    
                    // 添加到贡献历史
                    if (!tributeHistory.containsKey(tributeCountry.id)) {
                        tributeHistory[tributeCountry.id] = mutableListOf()
                    }
                    tributeHistory[tributeCountry.id]!!.add(record)
                    
                    if (!tributeHistory.containsKey(receivingCountry.id)) {
                        tributeHistory[receivingCountry.id] = mutableListOf()
                    }
                    tributeHistory[receivingCountry.id]!!.add(record)
                    
                    // 通知在线成员
                    notifyCountryMembers(tributeCountry, receivingCountry, safeGoldAmount, safeDiamondAmount)
                    
                    // 提高外交关系
                    if (totalValue > 0) {
                        improveDiplomaticRelations(tributeCountry, receivingCountry)
                    }
                    
                    Guozhan.instance.logger.info("处理了${tributeCountry.name}向${receivingCountry.name}的贡献：${safeGoldAmount}金币，${safeDiamondAmount}钻石")
                }
            } else {
                // 如果国家不存在，移除贡献关系
                if (tributeCountry == null || receivingCountry == null) {
                    Guozhan.instance.logger.warning("贡献关系中的国家不存在，移除关系ID: $relationId")
                    tributeRelations.remove(relationId)
                }
            }
        }
        
        Guozhan.instance.logger.info("自动贡献处理完成，共处理了$processedCount个贡献关系")
        return processedCount
    }
    
    /**
     * 通知国家成员自动贡献情况
     * @param tributeCountry 贡献国家
     * @param receivingCountry 接收国家
     * @param goldAmount 贡献的金币数量
     * @param diamondAmount 贡献的钻石数量
     */
    private fun notifyCountryMembers(tributeCountry: Country, receivingCountry: Country, goldAmount: Int, diamondAmount: Int) {
        // 获取两国之间的外交关系
        val relation = cn.lcofficial.guozhan.manager.DiplomacyManager.getRelation(tributeCountry, receivingCountry)
        val relationTypeText = when (relation?.relationType) {
            cn.lcofficial.guozhan.data.RelationType.FRIENDLY -> "§a友好"
            cn.lcofficial.guozhan.data.RelationType.NEUTRAL -> "§f中立"
            cn.lcofficial.guozhan.data.RelationType.HOSTILE -> "§c敌对"
            cn.lcofficial.guozhan.data.RelationType.ALLIANCE -> "§b同盟"
            cn.lcofficial.guozhan.data.RelationType.WAR -> "§4战争"
            else -> "§f未知"
        }
        
        // 计算总价值
        val totalValue = goldAmount + (diamondAmount * 2)
        
        // 获取贡献关系
        val relationId = getTributeRelationId(tributeCountry, receivingCountry)
        val tributeRelation = tributeRelations[relationId]
        val tributeRateText = if (tributeRelation != null) {
            "§e${tributeRelation.tributeRate}%"
        } else {
            "§c未知"
        }
        
        // 通知贡献国家成员
        val tributeMembers = Bukkit.getOnlinePlayers().filter { player ->
            val user = player.user()
            user.country?.id == tributeCountry.id
        }
        
        tributeMembers.forEach { player ->
            player.sendMessage("§8§l[ §e国家贡献 §8§l]")
            player.sendMessage("§f你的国家向 §6${receivingCountry.name} §f贡献了:")
            player.sendMessage("§f - §6${goldAmount} §f单位黄金")
            player.sendMessage("§f - §b${diamondAmount} §f单位钻石")
            player.sendMessage("§f总价值: §e${totalValue} §f(贡献率: ${tributeRateText})")
            player.sendMessage("§f当前外交关系: ${relationTypeText}")
            player.sendMessage("§7贡献有助于提升两国外交关系")
        }
        
        // 通知接收国家成员
        val receivingMembers = Bukkit.getOnlinePlayers().filter { player ->
            val user = player.user()
            user.country?.id == receivingCountry.id
        }
        
        receivingMembers.forEach { player ->
            player.sendMessage("§8§l[ §e国家贡献 §8§l]")
            player.sendMessage("§f你的国家收到了来自 §6${tributeCountry.name} §f的贡献:")
            player.sendMessage("§f - §6${goldAmount} §f单位黄金")
            player.sendMessage("§f - §b${diamondAmount} §f单位钻石")
            player.sendMessage("§f总价值: §e${totalValue} §f(贡献率: ${tributeRateText})")
            player.sendMessage("§f当前外交关系: ${relationTypeText}")
        }
        
        // 记录到日志
        Guozhan.instance.logger.info("${tributeCountry.name}向${receivingCountry.name}贡献了${goldAmount}金币和${diamondAmount}钻石，总价值${totalValue}，当前关系${relation?.relationType}")
    }
}

/**
 * 贡献关系数据类
 */
data class TributeRelation(
    val tributeCountryId: UUID,
    val receivingCountryId: UUID,
    val tributeRate: Int,
    val establishedTime: Long
)

/**
 * 贡献记录数据类
 */
data class TributeRecord(
    val sourceCountryId: UUID,
    val targetCountryId: UUID,
    val material: String,
    val amount: Int,
    val value: Int,
    val timestamp: Long
)

/**
 * 检查玩家是否有足够的物品
 */
fun Player.hasEnoughItem(material: Material, amount: Int): Boolean {
    var count = 0
    for (item in inventory.contents) {
        if (item != null && item.type == material) {
            count += item.amount
            if (count >= amount) return true
        }
    }
    return false
}

/**
 * 从玩家背包中扣除物品
 */
fun Player.takeItem(material: Material, amount: Int): Boolean {
    var remaining = amount
    
    // 先检查是否有足够的物品
    if (!hasEnoughItem(material, amount)) return false
    
    // 从背包中扣除物品
    val contents = inventory.contents
    for (i in contents.indices) {
        val item = contents[i] ?: continue
        if (item.type == material) {
            if (item.amount <= remaining) {
                remaining -= item.amount
                inventory.setItem(i, null)
            } else {
                item.amount -= remaining
                remaining = 0
            }
            
            if (remaining <= 0) break
        }
    }
    
    return true
}