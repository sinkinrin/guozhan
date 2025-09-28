package cn.lcofficial.guozhan.data

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.*

object TerritoryBlocks : IdTable<String>("gz_territory_blocks") {
    override val id = char("id", 36).uniqueIndex().entityId()
    val owner = optReference("owner", Countries)
    val x = integer("x")
    val z = integer("z")
    val world = varchar("world", 50)
    val loyalty = integer("loyalty").default(100)
    val resourceType = enumerationByName<ResourceType>("resource_type", 20).default(ResourceType.NONE)
    val resourceAmount = integer("resource_amount").default(0)
    val lastHarvestTime = long("last_harvest_time").default(0)
    val lastLoyaltyUpdateTime = long("last_loyalty_update_time").default(0)
    val isCapital = bool("is_capital").default(false)
    val coreHealth = integer("core_health").default(0)
    val lastCoreHealthUpdateTime = long("last_core_health_update_time").default(0)
}

enum class ResourceType {
    NONE, GOLD, DIAMOND, IRON, FOOD
}

class TerritoryBlock(
    val id: UUID,
    val x: Int,
    val z: Int,
    val world: String,
    var loyalty: Int,
    private var ownerId: UUID?,
    var resourceType: ResourceType = ResourceType.NONE,
    var resourceAmount: Int = 0,
    var lastHarvestTime: Long = 0,
    var lastLoyaltyUpdateTime: Long = 0,
    var isCapital: Boolean = false,
    var coreHealth: Int = 0,
    var lastCoreHealthUpdateTime: Long = 0
) {
    var owner: Country?
        get() {
            return if (ownerId == null) null else cn.lcofficial.guozhan.manager.CountryManager.getCountry(ownerId!!)
        }
        set(value) {
            ownerId = value?.id
        }

    fun isOwned(): Boolean = ownerId != null

    fun canHarvest(): Boolean {
        if (resourceType == ResourceType.NONE || resourceAmount <= 0) return false
        val currentTime = System.currentTimeMillis()
        // 每24小时可以收获一次资源
        return currentTime - lastHarvestTime >= 24 * 60 * 60 * 1000
    }

    fun harvest(): Int {
        if (!canHarvest()) return 0
        val amount = resourceAmount
        lastHarvestTime = System.currentTimeMillis()
        save()
        return amount
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TerritoryBlock
        return x == other.x && z == other.z && world == other.world
    }

    override fun hashCode(): Int {
        var result = x
        result = 31 * result + z
        result = 31 * result + world.hashCode()
        return result
    }

    /**
     * 计算区块的接壤面数
     * @return 返回与其他区块接壤的面数
     */
    fun calculateAdjacentFaces(): Int {
        var adjacentFaces = 0
        val territoryManager = cn.lcofficial.guozhan.manager.TerritoryManager
        
        // 检查上下左右四个方向是否有同国家的区块
        val directions = arrayOf(
            Pair(x + 1, z), // 右
            Pair(x - 1, z), // 左
            Pair(x, z + 1), // 上
            Pair(x, z - 1)  // 下
        )
        
        for ((adjX, adjZ) in directions) {
            val adjacentBlock = territoryManager.getTerritoryBlock(adjX, adjZ, world)
            if (adjacentBlock != null && adjacentBlock.ownerId == ownerId) {
                adjacentFaces++
            }
        }
        
        return adjacentFaces
    }
    
    /**
     * 更新区块忠诚度
     * 根据接壤面数减少忠诚度
     * @return 更新后的忠诚度
     */
    fun updateLoyalty(): Int {
        if (ownerId == null || isCapital) return loyalty // 无主区块或首都区块不减少忠诚度
        
        val currentTime = System.currentTimeMillis()
        // 每5分钟检查一次忠诚度
        if (currentTime - lastLoyaltyUpdateTime < 5 * 60 * 1000) return loyalty
        
        val adjacentFaces = calculateAdjacentFaces()
        val loyaltyReductionChance = when (adjacentFaces) {
            0 -> 0.0 // 完全接壤，不减少
            1 -> 0.1 // 1个面不接壤，10%概率减少
            2 -> 0.35 // 2个面不接壤，35%概率减少
            3 -> 0.75 // 3个面不接壤，75%概率减少
            else -> 1.0 // 4个面都不接壤，100%概率减少
        }
        
        // 随机决定是否减少忠诚度
        if (Random().nextDouble() < loyaltyReductionChance) {
            // 每5分钟减少4%的忠诚度
            loyalty -= 4
            if (loyalty < 0) loyalty = 0
            
            // 如果忠诚度为0，处理相应逻辑
            if (loyalty == 0) {
                if (isCapital) {
                    // 王城区块忠诚度为0，触发灭国
                    val country = owner
                    if (country != null) {
                        // 在实际实现中，这里应该调用灭国的方法
                        // 暂时先清除所有权
                        ownerId = null
                    }
                } else {
                    // 普通区块忠诚度为0，变为无主区块
                    ownerId = null
                }
            }
        }
        
        lastLoyaltyUpdateTime = currentTime
        save()
        return loyalty
    }
    
    /**
     * 更新核心生命值
     * 每分钟增加1点生命值，最大1000生命值
     */
    fun updateCoreHealth() {
        if (!isCapital || ownerId == null) return // 只有首都区块才有核心
        
        val currentTime = System.currentTimeMillis()
        // 每分钟增加1点生命值
        val minutesPassed = (currentTime - lastCoreHealthUpdateTime) / (60 * 1000)
        if (minutesPassed > 0) {
            coreHealth += minutesPassed.toInt()
            if (coreHealth > 1000) coreHealth = 1000 // 最大1000生命值
            lastCoreHealthUpdateTime = currentTime
            save()
        }
    }
    
    /**
     * 攻击核心，减少生命值
     * @param damage 伤害值
     * @return 剩余生命值
     */
    fun damageCoreHealth(damage: Int): Int {
        if (!isCapital) return 0
        coreHealth -= damage
        if (coreHealth < 0) coreHealth = 0
        save()
        return coreHealth
    }
    
    fun save() = transaction {
        TerritoryBlocks.update({ TerritoryBlocks.id eq id.toString() }) {
            it[TerritoryBlocks.loyalty] = loyalty
            it[TerritoryBlocks.owner] = ownerId?.toString()
            it[TerritoryBlocks.resourceType] = resourceType
            it[TerritoryBlocks.resourceAmount] = resourceAmount
            it[TerritoryBlocks.lastHarvestTime] = lastHarvestTime
            it[TerritoryBlocks.lastLoyaltyUpdateTime] = lastLoyaltyUpdateTime
            it[TerritoryBlocks.isCapital] = isCapital
            it[TerritoryBlocks.coreHealth] = coreHealth
            it[TerritoryBlocks.lastCoreHealthUpdateTime] = lastCoreHealthUpdateTime
        }
    }
}