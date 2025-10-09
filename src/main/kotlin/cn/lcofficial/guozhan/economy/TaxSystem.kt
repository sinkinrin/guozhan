package cn.lcofficial.guozhan.economy

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.config.DiplomacyConfig
import cn.lcofficial.guozhan.data.Countries
import cn.lcofficial.guozhan.data.Country
import cn.lcofficial.guozhan.data.ResourceType
import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.manager.EconomyManager
import cn.lcofficial.guozhan.manager.TerritoryManager
import cn.lcofficial.guozhan.manager.UserManager.user
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

/**
 * 高级税收系统，处理国家税收、税率调整和税收分配
 */
object TaxSystem {
    
    // 记录每个国家的税率策略
    private val taxPolicies = mutableMapOf<UUID, TaxPolicy>()
    
    // 记录每个国家的税收历史
    private val taxHistory = mutableMapOf<UUID, MutableList<TaxRecord>>()
    
    // 税收周期（毫秒），默认24小时
    const val TAX_CYCLE = 24 * 60 * 60 * 1000L
    
    // 最大税率
    const val MAX_TAX_RATE = 30
    
    // 最小税率
    const val MIN_TAX_RATE = 0
    
    /**
     * 初始化税收系统
     */
    fun initialize() {
        Guozhan.instance.logger.info("正在初始化高级税收系统...")
        loadTaxPolicies()
    }
    
    /**
     * 加载所有税收策略
     */
    private fun loadTaxPolicies() {
        // 这里可以从数据库或配置文件加载税收策略
        // 目前使用内存存储，后续可扩展为持久化存储
        taxPolicies.clear()
        taxHistory.clear()
        
        // 为所有国家设置默认税收策略
        for (country in CountryManager.countries.values) {
            val currentRate = EconomyManager.getTaxRate(country)
            taxPolicies[country.id] = TaxPolicy(
                baseRate = currentRate,
                resourceMultipliers = mapOf(
                    ResourceType.GOLD to 1.5f,
                    ResourceType.DIAMOND to 2.0f,
                    ResourceType.IRON to 1.0f,
                    ResourceType.FOOD to 0.5f,
                    ResourceType.NONE to 0.25f
                ),
                loyaltyMultiplier = true,
                progressiveTax = false,
                lastCollectionTime = System.currentTimeMillis() - TAX_CYCLE // 设置为可以立即收税
            )
        }
    }
    
    /**
     * 设置国家的税收策略
     * @param country 国家
     * @param policy 税收策略
     */
    fun setTaxPolicy(country: Country, policy: TaxPolicy) {
        // 确保基础税率在合理范围内
        val validPolicy = policy.copy(
            baseRate = policy.baseRate.coerceIn(MIN_TAX_RATE, MAX_TAX_RATE)
        )
        
        taxPolicies[country.id] = validPolicy
        
        // 同步到EconomyManager
        EconomyManager.setTaxRate(country, validPolicy.baseRate)
    }
    
    /**
     * 获取国家的税收策略
     * @param country 国家
     * @return 税收策略
     */
    fun getTaxPolicy(country: Country): TaxPolicy {
        return taxPolicies[country.id] ?: TaxPolicy(
            baseRate = EconomyManager.getTaxRate(country),
            resourceMultipliers = mapOf(
                ResourceType.GOLD to 1.5f,
                ResourceType.DIAMOND to 2.0f,
                ResourceType.IRON to 1.0f,
                ResourceType.FOOD to 0.5f,
                ResourceType.NONE to 0.25f
            ),
            loyaltyMultiplier = true,
            progressiveTax = false,
            lastCollectionTime = System.currentTimeMillis() - TAX_CYCLE
        )
    }
    
    /**
     * 检查国家是否可以收税
     * @param country 国家
     * @return 是否可以收税
     */
    fun canCollectTax(country: Country): Boolean {
        val policy = getTaxPolicy(country)
        return System.currentTimeMillis() - policy.lastCollectionTime >= TAX_CYCLE
    }
    
    /**
     * 收取国家税收
     * @param country 国家
     * @param notify 是否通知在线成员
     * @return 收取的税收总额
     */
    fun collectTax(country: Country, notify: Boolean = true): Int {
        if (!canCollectTax(country)) {
            return 0
        }
        
        val policy = getTaxPolicy(country)
        if (policy.baseRate <= 0) {
            return 0
        }
        
        var totalTax = 0
        val taxDetails = mutableMapOf<ResourceType, Int>()
        
        // 从每个领土收取税收
        val territories = TerritoryManager.getTerritoriesByCountry(country)
        for (territory in territories) {
            // 根据领土忠诚度和资源类型计算税收
            val resourceType = territory.resourceType
            val resourceMultiplier = policy.resourceMultipliers[resourceType] ?: 1.0f
            val baseValue = EconomyManager.resourceValues[resourceType] ?: 0
            
            // 计算忠诚度因子
            val loyaltyFactor = if (policy.loyaltyMultiplier) {
                territory.loyalty / 100.0
            } else {
                1.0
            }
            
            // 计算该领土的税收
            val territoryTax = (baseValue * loyaltyFactor * resourceMultiplier * policy.baseRate / 100.0).toInt()
            
            totalTax += territoryTax
            
            // 记录各资源类型的税收
            taxDetails[resourceType] = (taxDetails[resourceType] ?: 0) + territoryTax
        }
        
        // 更新收税时间
        val updatedPolicy = policy.copy(lastCollectionTime = System.currentTimeMillis())
        taxPolicies[country.id] = updatedPolicy
        
        // 将税收加入国家金库
        country.gold += totalTax
        country.save()
        
        // 记录税收历史
        recordTaxCollection(country, totalTax, taxDetails)
        
        // 通知在线成员
        if (notify) {
            notifyTaxCollection(country, totalTax, taxDetails)
        }
        
        return totalTax
    }
    
    /**
     * 记录税收历史
     */
    private fun recordTaxCollection(country: Country, totalAmount: Int, details: Map<ResourceType, Int>) {
        val record = TaxRecord(
            countryId = country.id,
            amount = totalAmount,
            details = details.mapKeys { it.key.name },
            timestamp = System.currentTimeMillis()
        )
        
        if (!taxHistory.containsKey(country.id)) {
            taxHistory[country.id] = mutableListOf()
        }
        
        taxHistory[country.id]!!.add(record)
        
        // 限制历史记录数量，最多保留10条
        if (taxHistory[country.id]!!.size > 10) {
            taxHistory[country.id] = taxHistory[country.id]!!.sortedByDescending { it.timestamp }.take(10).toMutableList()
        }
    }
    
    /**
     * 通知国家成员税收情况
     */
    private fun notifyTaxCollection(country: Country, totalAmount: Int, details: Map<ResourceType, Int>) {
        val onlineMembers = Bukkit.getOnlinePlayers().filter { player ->
            val user = player.user()
            user.country?.id == country.id
        }
        
        if (onlineMembers.isEmpty()) return
        
        for (player in onlineMembers) {
            player.sendMessage("§6[国家税收] §a您的国家已收取税款: §f$totalAmount §a金币")
            
            // 如果是国家管理员或所有者，显示详细信息
            val user = player.user()
            if (user.rank.value >= 2) {
                player.sendMessage("§6[税收详情]")
                details.forEach { (resourceType, amount) ->
                    if (amount > 0) {
                        player.sendMessage("  §a- ${resourceType.name}: §f$amount §a金币")
                    }
                }
            }
        }
    }
    
    /**
     * 获取国家的税收历史
     * @param country 国家
     * @return 税收历史记录列表
     */
    fun getTaxHistory(country: Country): List<TaxRecord> {
        return taxHistory[country.id] ?: emptyList()
    }
    
    /**
     * 显示税收详情给玩家
     * @param player 玩家
     * @param country 国家
     */
    fun showTaxInfo(player: Player, country: Country) {
        val policy = getTaxPolicy(country)
        val canCollect = canCollectTax(country)
        
        player.sendMessage("§6===== 国家税收信息 =====")
        player.sendMessage("§a国家名称: §f${country.name}")
        player.sendMessage("§a基础税率: §f${policy.baseRate}%")
        player.sendMessage("§a是否可以收税: §f${if (canCollect) "是" else "否"}")
        
        // 显示资源乘数
        player.sendMessage("§a资源税率乘数:")
        policy.resourceMultipliers.forEach { (resourceType, multiplier) ->
            player.sendMessage("  §a- ${resourceType.name}: §fx${multiplier}")
        }
        
        // 显示其他税收策略
        player.sendMessage("§a忠诚度影响: §f${if (policy.loyaltyMultiplier) "启用" else "禁用"}")
        player.sendMessage("§a累进税制: §f${if (policy.progressiveTax) "启用" else "禁用"}")
        
        // 显示国家资源
        player.sendMessage("§a国家黄金储备: §f${country.gold}")
        player.sendMessage("§a国家钻石储备: §f${country.diamond}")
        
        // 显示最近的税收历史
        val history = getTaxHistory(country).sortedByDescending { it.timestamp }.take(3)
        if (history.isNotEmpty()) {
            player.sendMessage("§a最近税收记录:")
            history.forEach { record ->
                val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(record.timestamp))
                player.sendMessage("  §a- $date: §f${record.amount} §a金币")
            }
        }
    }
    
    /**
     * 自动收税任务
     */
    fun processAutomaticTaxCollection() {
        Guozhan.instance.logger.info("正在执行自动收税...")
        
        var totalCollected = 0
        
        // v1.3.13修复：从数据库查询所有国家，而不是依赖缓存
        val countries = transaction {
            Countries.selectAll().map { row ->
                CountryManager.getCountry(UUID.fromString(row[Countries.id].value))
            }.filterNotNull()
        }
        
        for (country in countries) {
            // 跳过税率为0的国家
            val policy = getTaxPolicy(country)
            if (policy.baseRate <= 0) continue
            
            // 获取国家的所有在线成员
            val onlineMembers = Bukkit.getOnlinePlayers().filter { player ->
                val user = player.user()
                user.country?.id == country.id
            }
            
            // 如果没有在线成员，跳过
            if (onlineMembers.isEmpty()) continue
            
            // 收税
            val taxCollected = collectTax(country, true)
            totalCollected += taxCollected
        }
        
        Guozhan.instance.logger.info("自动收税完成，共收取 $totalCollected 金币")
    }
}

/**
 * 税收策略数据类
 */
data class TaxPolicy(
    val baseRate: Int, // 基础税率（百分比）
    val resourceMultipliers: Map<ResourceType, Float>, // 各资源类型的税率乘数
    val loyaltyMultiplier: Boolean, // 是否考虑忠诚度
    val progressiveTax: Boolean, // 是否使用累进税制
    val lastCollectionTime: Long // 上次收税时间
)

/**
 * 税收记录数据类
 */
data class TaxRecord(
    val countryId: UUID,
    val amount: Int,
    val details: Map<String, Int>, // 各资源类型的税收详情
    val timestamp: Long
)