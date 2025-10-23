package cn.lcofficial.guozhan.data

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.replace
import org.jetbrains.exposed.sql.transactions.transaction
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
     * 注意：忠诚度系统已统一到LoyaltySystem.kt中管理
     * 此方法保留用于兼容性，但实际逻辑已移至统一系统
     * @return 更新后的忠诚度
     */
    @Deprecated("忠诚度更新已统一到LoyaltySystem.kt中管理", ReplaceWith("LoyaltySystem"))
    fun updateLoyalty(): Int {
        // 忠诚度更新逻辑已移至LoyaltySystem.kt中统一管理
        // 此方法保留用于向后兼容，但不再执行实际的更新逻辑
        // 如需手动更新忠诚度，请使用LoyaltySystem类

        // 只更新时间戳，避免重复处理
        lastLoyaltyUpdateTime = System.currentTimeMillis()
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
    
    /**
     * 保存领土区块数据到数据库
     * 🔧 v1.3.25: 改为异步执行，避免阻塞 region 线程
     * 🔧 v1.3.48: 修复数据丢失风险 - 添加同步保存选项和返回值
     */
    fun save(async: Boolean = true): Boolean {
        return if (async) {
            // 异步保存，返回true表示任务已提交（不保证成功）
            cn.lcofficial.guozhan.util.async { _ ->
                try {
                    saveInTransaction()
                } catch (e: Exception) {
                    cn.lcofficial.guozhan.Guozhan.instance.logger.severe(
                        "异步保存领土区块失败 (${id}): ${e.message}"
                    )
                    e.printStackTrace()
                }
            }
            true
        } else {
            // 同步保存，返回实际保存结果
            try {
                saveInTransaction()
                true
            } catch (e: Exception) {
                cn.lcofficial.guozhan.Guozhan.instance.logger.severe(
                    "同步保存领土区块失败 (${id}): ${e.message}"
                )
                e.printStackTrace()
                false
            }
        }
    }

    /**
     * 🔧 v1.3.48: 新增事务内保存方法，供同步和异步保存共用
     * 设为internal以便LoyaltySystem等内部类调用
     */
    internal fun saveInTransaction() {
        transaction {
            val ownerEntityId = ownerId?.let { EntityID(it.toString(), Countries) }

            TerritoryBlocks.replace { row ->
                row[TerritoryBlocks.id] = this@TerritoryBlock.id.toString()
                row[TerritoryBlocks.x] = this@TerritoryBlock.x
                row[TerritoryBlocks.z] = this@TerritoryBlock.z
                row[TerritoryBlocks.world] = this@TerritoryBlock.world
                row[TerritoryBlocks.loyalty] = this@TerritoryBlock.loyalty
                row[TerritoryBlocks.owner] = ownerEntityId
                row[TerritoryBlocks.resourceType] = this@TerritoryBlock.resourceType
                row[TerritoryBlocks.resourceAmount] = this@TerritoryBlock.resourceAmount
                row[TerritoryBlocks.lastHarvestTime] = this@TerritoryBlock.lastHarvestTime
                row[TerritoryBlocks.lastLoyaltyUpdateTime] = this@TerritoryBlock.lastLoyaltyUpdateTime
                row[TerritoryBlocks.isCapital] = this@TerritoryBlock.isCapital
                row[TerritoryBlocks.coreHealth] = this@TerritoryBlock.coreHealth
                row[TerritoryBlocks.lastCoreHealthUpdateTime] = this@TerritoryBlock.lastCoreHealthUpdateTime
            }
        }
    }
}