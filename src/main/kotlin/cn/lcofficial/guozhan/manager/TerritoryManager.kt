package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.data.*
import org.bukkit.Chunk
import org.bukkit.Location
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

object TerritoryManager {

    val territories = mutableMapOf<UUID, TerritoryBlock>()

    fun getTerritoryBlock(id: UUID): TerritoryBlock? = transaction {
        var territory = territories[id]
        if (territory == null) {
            territory = TerritoryBlocks.selectAll().where { TerritoryBlocks.id eq id.toString() }.firstOrNull()?.let {
                TerritoryBlock(
                    UUID.fromString(it[TerritoryBlocks.id].value),
                    it[TerritoryBlocks.x],
                    it[TerritoryBlocks.z],
                    it[TerritoryBlocks.world],
                    it[TerritoryBlocks.loyalty],
                    it[TerritoryBlocks.owner]?.let { owner -> UUID.fromString(owner.value) },
                    it[TerritoryBlocks.resourceType],
                    it[TerritoryBlocks.resourceAmount],
                    it[TerritoryBlocks.lastHarvestTime],
                    it[TerritoryBlocks.lastLoyaltyUpdateTime],
                    it[TerritoryBlocks.isCapital],
                    it[TerritoryBlocks.coreHealth],
                    it[TerritoryBlocks.lastCoreHealthUpdateTime]
                )
            }
            if (territory != null) territories[id] = territory
        }
        territory
    }

    class TerritoryAlreadyExistsException(x: Int, z: Int, world: String) : 
        Exception("Territory at $x, $z in world $world already exists")

    fun createTerritoryBlock(x: Int, z: Int, world: String, isCapital: Boolean = false): TerritoryBlock = transaction {
        val territory = TerritoryBlock(
            UUID.randomUUID(), x, z, world, 100, null, 
            ResourceType.NONE, 0, 0, 
            System.currentTimeMillis(), isCapital, 
            if (isCapital) cn.lcofficial.guozhan.config.Config.Country.coreHealthInitial else 0, // 新建核心使用配置的初始血量，普通区块为0
            System.currentTimeMillis()
        )
        if (territories.values.any { it == territory }) throw TerritoryAlreadyExistsException(x, z, world)
        territories[territory.id] = territory
        TerritoryBlocks.insert {
            it[TerritoryBlocks.id] = territory.id.toString()
            it[TerritoryBlocks.x] = territory.x
            it[TerritoryBlocks.z] = territory.z
            it[TerritoryBlocks.world] = territory.world
            it[TerritoryBlocks.loyalty] = territory.loyalty
            it[TerritoryBlocks.owner] = null
            it[TerritoryBlocks.resourceType] = territory.resourceType
            it[TerritoryBlocks.resourceAmount] = territory.resourceAmount
            it[TerritoryBlocks.lastHarvestTime] = territory.lastHarvestTime
            it[TerritoryBlocks.lastLoyaltyUpdateTime] = territory.lastLoyaltyUpdateTime
            it[TerritoryBlocks.isCapital] = territory.isCapital
            it[TerritoryBlocks.coreHealth] = territory.coreHealth
            it[TerritoryBlocks.lastCoreHealthUpdateTime] = territory.lastCoreHealthUpdateTime
        }
        territory
    }
    
    /**
     * 创建国家核心
     * 在指定位置创建一个国家核心区块
     * @param x 区块X坐标
     * @param z 区块Z坐标
     * @param world 世界名称
     * @param country 所属国家
     * @return 创建的核心区块
     */
    fun createCountryCore(x: Int, z: Int, world: String, country: Country): TerritoryBlock = transaction {
        val territory = createTerritoryBlock(x, z, world, true)
        territory.owner = country
        territory.isCapital = true
        territory.coreHealth = 0 // 初始生命值为0，会随时间增长
        territory.lastCoreHealthUpdateTime = System.currentTimeMillis()
        territory.save()
        
        // 在游戏中实际创建信标和玻璃保护层的代码应该在监听器中实现
        // 这里只处理数据部分
        
        territory
    }

    fun getTerritoryBlock(x: Int, z: Int, world: String): TerritoryBlock? = transaction {
        var territory = territories.values.firstOrNull { it.x == x && it.z == z && it.world == world }
        if (territory == null) {
            territory = TerritoryBlocks.selectAll().where { 
                (TerritoryBlocks.x eq x) and (TerritoryBlocks.z eq z) and (TerritoryBlocks.world eq world) 
            }.firstOrNull()?.let {
                TerritoryBlock(
                    UUID.fromString(it[TerritoryBlocks.id].value),
                    it[TerritoryBlocks.x],
                    it[TerritoryBlocks.z],
                    it[TerritoryBlocks.world],
                    it[TerritoryBlocks.loyalty],
                    it[TerritoryBlocks.owner]?.let { owner -> UUID.fromString(owner.value) },
                    it[TerritoryBlocks.resourceType],
                    it[TerritoryBlocks.resourceAmount],
                    it[TerritoryBlocks.lastHarvestTime],
                    it[TerritoryBlocks.lastLoyaltyUpdateTime],
                    it[TerritoryBlocks.isCapital],
                    it[TerritoryBlocks.coreHealth],
                    it[TerritoryBlocks.lastCoreHealthUpdateTime]
                )
            }
            if (territory != null) territories[territory.id] = territory
        }
        territory
    }

    fun Location.territoryBlock(): TerritoryBlock = 
        getTerritoryBlock(chunk.x, chunk.z, world.name) ?: createTerritoryBlock(chunk.x, chunk.z, world.name)
    
    fun Chunk.territoryBlock(): TerritoryBlock = 
        getTerritoryBlock(x, z, world.name) ?: createTerritoryBlock(x, z, world.name)

    fun getTerritoriesByCountry(country: Country): List<TerritoryBlock> = transaction {
        TerritoryBlocks.selectAll().where { TerritoryBlocks.owner eq country.id.toString() }.map {
            TerritoryBlock(
                UUID.fromString(it[TerritoryBlocks.id].value),
                it[TerritoryBlocks.x],
                it[TerritoryBlocks.z],
                it[TerritoryBlocks.world],
                it[TerritoryBlocks.loyalty],
                UUID.fromString(it[TerritoryBlocks.owner]!!.value),
                it[TerritoryBlocks.resourceType],
                it[TerritoryBlocks.resourceAmount],
                it[TerritoryBlocks.lastHarvestTime],
                it[TerritoryBlocks.lastLoyaltyUpdateTime],
                it[TerritoryBlocks.isCapital],
                it[TerritoryBlocks.coreHealth],
                it[TerritoryBlocks.lastCoreHealthUpdateTime]
            )
        }.toList()
    }

    /**
     * 计算占领区块所需时间
     * @param territory 要占领的区块
     * @param player 占领的玩家
     * @param country 占领的国家
     * @return 占领所需时间（毫秒）
     */
    fun calculateClaimTime(territory: TerritoryBlock, player: org.bukkit.entity.Player, country: Country): Long {
        // 基础占领时间（秒）
        var baseTime = 5.0
        
        // 计算与王城的距离
        val capital = country.capital
        val distanceX = Math.abs(territory.x - capital.x)
        val distanceZ = Math.abs(territory.z - capital.z)
        val distance = Math.sqrt((distanceX * distanceX + distanceZ * distanceZ).toDouble()).toInt()
        
        // 每增加1个区块的距离，基础占领时长增加0.25秒
        baseTime += distance * 0.25
        
        // 计算接壤面数
        val adjacentFaces = territory.calculateAdjacentFaces()
        
        // 如果是占领对方领地
        if (territory.isOwned() && territory.owner?.id != country.id) {
            // 根据接壤面数增加占领时间
            when {
                adjacentFaces >= 3 -> baseTime *= 2.0 // 增加100%
                adjacentFaces == 2 -> baseTime *= 1.5 // 增加50%
            }
            
            // 如果是占领王城区块，额外增加1000%占领时长
            if (territory.isCapital) {
                baseTime *= 11.0 // 增加1000%
            }
        } 
        // 如果是占领无主区块
        else if (!territory.isOwned()) {
            // 根据接壤面数减少占领时间
            when {
                adjacentFaces >= 3 -> baseTime /= 3.0 // 提升200%速度
                adjacentFaces == 2 -> baseTime /= 2.0 // 提升100%速度
            }
        }
        
        // 多人占领相同区块会触发占领速度提升
        // 这部分逻辑应该在实际占领过程中动态计算，这里只是示例
        // 每多1人速度提升25%，最多提升100%
        
        // 转换为毫秒
        return (baseTime * 1000).toLong()
    }
    
    /**
     * 检查区块是否可以被占领
     * @param territory 要检查的区块
     * @param country 尝试占领的国家
     * @return 是否可以占领
     * v1.3.13修复：检查防御者（领土所有者）的护盾，而不是攻击者的护盾
     */
    fun canClaim(territory: TerritoryBlock, country: Country): Boolean {
        // v1.3.13修复：如果领土所有者开启了护盾，不能被占领
        val defender = territory.owner
        if (defender != null && cn.lcofficial.guozhan.manager.ShieldManager.isShieldActive(defender)) {
            return false
        }

        // 检查是否与现有领土接壤
        val adjacentToOwn = hasAdjacentTerritory(territory, country)
        if (!adjacentToOwn) return false

        return true
    }
    
    /**
     * 检查区块是否与指定国家的领土接壤
     * @param territory 要检查的区块
     * @param country 指定的国家
     * @return 是否接壤
     */
    fun hasAdjacentTerritory(territory: TerritoryBlock, country: Country): Boolean {
        val directions = arrayOf(
            Pair(territory.x + 1, territory.z), // 右
            Pair(territory.x - 1, territory.z), // 左
            Pair(territory.x, territory.z + 1), // 上
            Pair(territory.x, territory.z - 1)  // 下
        )
        
        for ((adjX, adjZ) in directions) {
            val adjacentBlock = getTerritoryBlock(adjX, adjZ, territory.world)
            if (adjacentBlock != null && adjacentBlock.owner?.id == country.id) {
                return true
            }
        }
        
        return false
    }
    
    /**
     * 占领区块
     * @param territory 要占领的区块
     * @param country 占领的国家
     * @return 是否成功占领
     */
    fun claimTerritory(territory: TerritoryBlock, country: Country): Boolean {
        if (!canClaim(territory, country)) return false
        
        // 设置区块所有者
        territory.owner = country
        territory.loyalty = 100 // 重置忠诚度
        territory.lastLoyaltyUpdateTime = System.currentTimeMillis()
        territory.save()
        
        return true
    }
    
    /**
     * 放弃区块
     * @param territory 要放弃的区块
     * @return 是否成功放弃
     */
    fun unclaimTerritory(territory: TerritoryBlock): Boolean {
        if (!territory.isOwned()) return false
        
        // 如果是首都区块，不能放弃
        if (territory.isCapital) return false
        
        territory.owner = null
        territory.loyalty = 100 // 重置忠诚度
        territory.save()
        
        return true
    }
    
    /**
     * 恢复区块忠诚度
     * @param territory 要恢复的区块
     * @param amount 恢复的忠诚度百分比
     * @return 恢复后的忠诚度
     */
    fun restoreLoyalty(territory: TerritoryBlock, amount: Int): Int {
        if (!territory.isOwned()) return 0
        
        territory.loyalty += amount
        if (territory.loyalty > 100) territory.loyalty = 100
        territory.save()
        
        return territory.loyalty
    }
    
    /**
     * 计算领土系数
     * @param country 国家
     * @return 领土系数
     */
    fun calculateTerritoryCoefficent(country: Country): Double {
        val territories = getTerritoriesByCountry(country)
        if (territories.isEmpty()) return 1.0
        
        // 计算领土的边界
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minZ = Int.MAX_VALUE
        var maxZ = Int.MIN_VALUE
        
        territories.forEach { territory ->
            if (territory.x < minX) minX = territory.x
            if (territory.x > maxX) maxX = territory.x
            if (territory.z < minZ) minZ = territory.z
            if (territory.z > maxZ) maxZ = territory.z
        }
        
        val width = maxX - minX + 1
        val height = maxZ - minZ + 1
        
        // 计算领土面积
        val area: Int
        val ratio = Math.max(width, height).toDouble() / Math.min(width, height).toDouble()
        
        area = if (ratio <= 2.0) {
            // 长宽比 <= 2:1
            width * height
        } else {
            // 长宽比 > 2:1
            val longSide = Math.max(width, height)
            (longSide * (longSide / 2)).toInt()
        }
        
        // 领土系数 = 领土面积 / 领土实际占领区块数
        return area.toDouble() / territories.size.toDouble()
    }
    
    fun generateRandomResource(territory: TerritoryBlock) {
        if (territory.resourceType != ResourceType.NONE) return
        
        val random = Random()
        val resourceTypeRoll = random.nextInt(100)
        
        territory.resourceType = when {
            resourceTypeRoll < 5 -> ResourceType.DIAMOND
            resourceTypeRoll < 20 -> ResourceType.GOLD
            resourceTypeRoll < 50 -> ResourceType.IRON
            resourceTypeRoll < 80 -> ResourceType.FOOD
            else -> ResourceType.NONE
        }
        
        if (territory.resourceType != ResourceType.NONE) {
            territory.resourceAmount = when (territory.resourceType) {
                ResourceType.DIAMOND -> random.nextInt(5) + 1
                ResourceType.GOLD -> random.nextInt(10) + 5
                ResourceType.IRON -> random.nextInt(20) + 10
                ResourceType.FOOD -> random.nextInt(30) + 20
                else -> 0
            }
        }
        
        territory.save()
    }
}