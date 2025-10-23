package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.data.*
import cn.lcofficial.guozhan.pluginLogger
import cn.lcofficial.guozhan.Guozhan
import org.bukkit.Chunk
import org.bukkit.Location
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object TerritoryManager {

    // 🔧 修复问题2：使用 ConcurrentHashMap 确保 Folia 多线程环境下的线程安全
    val territories = ConcurrentHashMap<UUID, TerritoryBlock>()

    // 🔧 v1.3.25: 添加坐标索引以加速查询
    // 🔧 v1.3.27: 改为 internal 以允许 CountryManager 在创建首都时填充缓存
    internal val territoryByCoords = ConcurrentHashMap<String, TerritoryBlock>()

    // 🔧 v1.3.25: 预加载状态标记
    @Volatile
    private var isPreloaded = false

    /**
     * 预加载所有领土数据到缓存（异步补充）
     * 🔧 v1.3.25: 在插件启动时调用，避免运行时数据库阻塞
     * 🔧 v1.3.26: 由于 loadAll() 已经同步填充了缓存，此方法现在只是验证和补充
     */
    fun preloadTerritories() {
        // 🔧 v1.3.26: 如果已经通过 loadAll() 预加载，跳过异步预加载
        if (isPreloaded && territories.isNotEmpty() && territoryByCoords.isNotEmpty()) {
            cn.lcofficial.guozhan.Guozhan.instance.logger.info(
                "领土缓存已通过 loadAll() 预加载完成，跳过异步预加载"
            )
            return
        }

        cn.lcofficial.guozhan.util.async { _ ->
            try {
                val startTime = System.currentTimeMillis()
                val loadedTerritories = transaction {
                    TerritoryBlocks.selectAll().map { row ->
                        TerritoryBlock(
                            UUID.fromString(row[TerritoryBlocks.id].value),
                            row[TerritoryBlocks.x],
                            row[TerritoryBlocks.z],
                            row[TerritoryBlocks.world],
                            row[TerritoryBlocks.loyalty],
                            row[TerritoryBlocks.owner]?.let { owner -> UUID.fromString(owner.value) },
                            row[TerritoryBlocks.resourceType],
                            row[TerritoryBlocks.resourceAmount],
                            row[TerritoryBlocks.lastHarvestTime],
                            row[TerritoryBlocks.lastLoyaltyUpdateTime],
                            row[TerritoryBlocks.isCapital],
                            row[TerritoryBlocks.coreHealth],
                            row[TerritoryBlocks.lastCoreHealthUpdateTime]
                        )
                    }
                }

                // 在主线程中更新缓存
                cn.lcofficial.guozhan.util.run { _ ->
                    loadedTerritories.forEach { territory ->
                        territories[territory.id] = territory
                        val coordKey = coordKey(territory.x, territory.z, territory.world)
                        territoryByCoords[coordKey] = territory
                    }
                    isPreloaded = true
                    val duration = System.currentTimeMillis() - startTime
                    cn.lcofficial.guozhan.Guozhan.instance.logger.info(
                        "异步预加载了 ${loadedTerritories.size} 个领土区块，耗时 ${duration}ms"
                    )
                }
            } catch (e: Exception) {
                cn.lcofficial.guozhan.Guozhan.instance.logger.severe("异步预加载领土数据失败: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * 生成坐标键
     */
    private fun coordKey(x: Int, z: Int, world: String): String = "$world:$x:$z"

    /**
     * 通过 UUID 获取领土区块（只从缓存读取）
     * 🔧 v1.3.25: 移除数据库查询，只从缓存获取
     */
    fun getTerritoryBlock(id: UUID): TerritoryBlock? {
        return territories[id]
    }

    class TerritoryAlreadyExistsException(x: Int, z: Int, world: String) : 
        Exception("Territory at $x, $z in world $world already exists")

    /**
     * 创建领土区块（先创建缓存对象，然后异步保存）
     * 🔧 v1.3.25: 避免在 region 线程中执行数据库操作
     */
    fun createTerritoryBlock(x: Int, z: Int, world: String, isCapital: Boolean = false): TerritoryBlock {
        // 检查是否已存在
        val coordKey = coordKey(x, z, world)
        territoryByCoords[coordKey]?.let {
            throw TerritoryAlreadyExistsException(x, z, world)
        }

        // 在主线程中创建对象并加入缓存
        val territory = TerritoryBlock(
            UUID.randomUUID(), x, z, world, 100, null,
            ResourceType.NONE, 0, 0,
            System.currentTimeMillis(), isCapital,
            if (isCapital) cn.lcofficial.guozhan.config.Config.Country.coreHealthInitial else 0,
            System.currentTimeMillis()
        )

        territories[territory.id] = territory
        territoryByCoords[coordKey] = territory

        if (!territory.save(async = false)) {
            Guozhan.instance.logger.severe("同步保存领土区块失败 ($x, $z, $world)")
        }

        return territory
    }
    
    /**
     * 创建国家核心
     * 在指定位置创建一个国家核心区域
     * 🔧 v1.3.25: 移除 transaction 包裹，使用异步保存
     * @param x 区块X坐标
     * @param z 区块Z坐标
     * @param world 世界名称
     * @param country 所属国家
     * @return 创建的核心区域
     */
    fun createCountryCore(x: Int, z: Int, world: String, country: Country): TerritoryBlock {
        val territory = createTerritoryBlock(x, z, world, true)
        territory.owner = country
        territory.isCapital = true
        territory.coreHealth = 0 // 初始生命值为0，会随时间增长
        territory.lastCoreHealthUpdateTime = System.currentTimeMillis()
    territory.save(async = false)

        // 在游戏中实际创建信标和玻璃保护层的代码应该在监听器中实现
        // 这里只处理数据部分
        return territory
    }

    /**
     * 获取领土区块（只从缓存读取）
     * 🔧 v1.3.25: 移除数据库查询，只从缓存获取，避免阻塞 region 线程
     */
    fun getTerritoryBlock(x: Int, z: Int, world: String): TerritoryBlock? {
        val coordKey = coordKey(x, z, world)
        return territoryByCoords[coordKey]
    }

    /**
     * 获取位置的领土区块（只读取，不自动创建）
     * 🔧 代码审查修复: 修改为返回null而不是自动创建数据库记录
     * 这避免了玩家探索时自动创建大量无用的数据库记录
     */
    fun Location.territoryBlock(): TerritoryBlock? =
        getTerritoryBlock(chunk.x, chunk.z, world.name)

    /**
     * 获取区块的领土区块（只读取，不自动创建）
     * 🔧 代码审查修复: 修改为返回null而不是自动创建数据库记录
     */
    fun Chunk.territoryBlock(): TerritoryBlock? =
        getTerritoryBlock(x, z, world.name)

    /**
     * 获取或创建位置的领土区块（显式创建版本）
     * 仅在需要实际创建领土记录时使用（如占领操作）
     */
    fun Location.getOrCreateTerritoryBlock(): TerritoryBlock =
        getTerritoryBlock(chunk.x, chunk.z, world.name) ?: createTerritoryBlock(chunk.x, chunk.z, world.name)

    /**
     * 获取或创建区块的领土区块（显式创建版本）
     * 仅在需要实际创建领土记录时使用（如占领操作）
     */
    fun Chunk.getOrCreateTerritoryBlock(): TerritoryBlock =
        getTerritoryBlock(x, z, world.name) ?: createTerritoryBlock(x, z, world.name)

    /**
     * 获取指定国家的所有领土（从缓存中过滤）
     * 🔧 v1.3.28: 改为从内存缓存过滤，避免频繁的数据库查询
     */
    fun getTerritoriesByCountry(country: Country): List<TerritoryBlock> {
        // 🔧 v1.3.28: 直接从缓存中过滤，不再执行数据库查询
        return territories.values.filter { it.owner?.id == country.id }
    }

    /**
     * 🔧 v1.3.38修复：获取需要忠诚度检查的领土（性能优化）
     * 只返回已到检查时间的有主领土，大幅减少需要处理的数据量
     * @param checkInterval 检查间隔（毫秒）
     * @return 需要检查的领土列表
     */
    fun getTerritoriesNeedingLoyaltyCheck(checkInterval: Long): List<TerritoryBlock> {
        val currentTime = System.currentTimeMillis()
        val cutoffTime = currentTime - checkInterval

        // 🔧 v1.3.38修复：性能优化 - 只筛选需要检查的领土
        return territories.values.filter { territory ->
            // 只处理有主的非首都领土，且已到检查时间
            territory.isOwned() &&
            !territory.isCapital &&
            territory.lastLoyaltyUpdateTime <= cutoffTime
        }
    }

    /**
     * 🔧 v1.3.38修复：按国家分组获取需要忠诚度检查的领土（性能优化）
     * 返回按国家分组的需要检查的领土，避免重复的国家查询
     * @param checkInterval 检查间隔（毫秒）
     * @return 按国家分组的领土Map
     */
    fun getTerritoriesNeedingLoyaltyCheckByCountry(checkInterval: Long): Map<Country, List<TerritoryBlock>> {
        val territoriesNeedingCheck = getTerritoriesNeedingLoyaltyCheck(checkInterval)

        // 按国家分组，避免重复的国家查询
        return territoriesNeedingCheck.groupBy { it.owner!! }
    }

    /**
     * 预热缓存：从数据库加载所有领土数据到内存缓存
     * 用于插件启动时确保缓存已填充，避免其他Manager初始化时遍历空缓存
     * 🔧 v1.3.26: 同时填充坐标缓存，防止缓存未命中导致重复创建领土
     */
    fun loadAll() = transaction {
        val startTime = System.currentTimeMillis()
        var loadedCount = 0
        var skippedCount = 0

        pluginLogger.info("[TerritoryManager] 开始预热缓存：从数据库加载所有领土...")

        TerritoryBlocks.selectAll().forEach { row ->
            try {
                val territoryId = UUID.fromString(row[TerritoryBlocks.id].value)

                // 如果缓存中已存在，跳过
                if (territories.containsKey(territoryId)) {
                    return@forEach
                }

                val territory = TerritoryBlock(
                    territoryId,
                    row[TerritoryBlocks.x],
                    row[TerritoryBlocks.z],
                    row[TerritoryBlocks.world],
                    row[TerritoryBlocks.loyalty],
                    row[TerritoryBlocks.owner]?.value?.let { UUID.fromString(it) },
                    row[TerritoryBlocks.resourceType],
                    row[TerritoryBlocks.resourceAmount],
                    row[TerritoryBlocks.lastHarvestTime],
                    row[TerritoryBlocks.lastLoyaltyUpdateTime],
                    row[TerritoryBlocks.isCapital],
                    row[TerritoryBlocks.coreHealth],
                    row[TerritoryBlocks.lastCoreHealthUpdateTime]
                )

                // 🔧 v1.3.26: 同时填充 UUID 缓存和坐标缓存
                territories[territoryId] = territory
                val coordKey = coordKey(territory.x, territory.z, territory.world)
                territoryByCoords[coordKey] = territory
                loadedCount++

            } catch (e: IllegalArgumentException) {
                pluginLogger.warning("[TerritoryManager] 跳过无效的UUID数据: 领土ID='${row[TerritoryBlocks.id].value}', 所有者ID='${row[TerritoryBlocks.owner]?.value}' - ${e.message}")
                skippedCount++
            } catch (e: Exception) {
                pluginLogger.severe("[TerritoryManager] 加载领土数据时发生错误: ${e.message}")
                e.printStackTrace()
                skippedCount++
            }
        }

        val duration = System.currentTimeMillis() - startTime
        pluginLogger.info("[TerritoryManager] 缓存预热完成：加载了 $loadedCount 个领土，跳过 $skippedCount 个无效记录，耗时 ${duration}ms")
        pluginLogger.info("[TerritoryManager] 当前缓存大小：${territories.size} 个领土（UUID索引）、${territoryByCoords.size} 个领土（坐标索引）")

        // 🔧 v1.3.26: 标记同步加载完成
        isPreloaded = true
    }

    /**
     * 强制重载所有领土数据
     * 🔧 v1.3.51: 修复重载不刷新缓存问题 - 添加强制刷新方法
     */
    fun forceLoadAll() = transaction {
        val startTime = System.currentTimeMillis()
        var loadedCount = 0
        var skippedCount = 0

        pluginLogger.info("[TerritoryManager] 开始强制重载缓存：从数据库重新加载所有领土...")

        TerritoryBlocks.selectAll().forEach { row ->
            try {
                val territoryId = UUID.fromString(row[TerritoryBlocks.id].value)

                val territory = TerritoryBlock(
                    territoryId,
                    row[TerritoryBlocks.x],
                    row[TerritoryBlocks.z],
                    row[TerritoryBlocks.world],
                    row[TerritoryBlocks.loyalty],
                    row[TerritoryBlocks.owner]?.value?.let { UUID.fromString(it) },
                    row[TerritoryBlocks.resourceType],
                    row[TerritoryBlocks.resourceAmount],
                    row[TerritoryBlocks.lastHarvestTime],
                    row[TerritoryBlocks.lastLoyaltyUpdateTime],
                    row[TerritoryBlocks.isCapital],
                    row[TerritoryBlocks.coreHealth],
                    row[TerritoryBlocks.lastCoreHealthUpdateTime]
                )

                // 强制更新缓存
                territories[territoryId] = territory
                val coordKey = coordKey(territory.x, territory.z, territory.world)
                territoryByCoords[coordKey] = territory
                loadedCount++

            } catch (e: IllegalArgumentException) {
                pluginLogger.warning("[TerritoryManager] 跳过无效的UUID数据: 领土ID='${row[TerritoryBlocks.id].value}', 所有者ID='${row[TerritoryBlocks.owner]?.value}' - ${e.message}")
                skippedCount++
            } catch (e: Exception) {
                pluginLogger.severe("[TerritoryManager] 强制重载领土数据时发生错误: ${e.message}")
                e.printStackTrace()
                skippedCount++
            }
        }

        val duration = System.currentTimeMillis() - startTime
        pluginLogger.info("[TerritoryManager] 强制重载完成：重载了 $loadedCount 个领土，跳过 $skippedCount 个无效记录，耗时 ${duration}ms")
        pluginLogger.info("[TerritoryManager] 当前缓存大小：${territories.size} 个领土（UUID索引）+ ${territoryByCoords.size} 个领土（坐标索引）")

        // 标记同步加载完成
        isPreloaded = true
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

        // 🔧 v1.3.48: 修复中立领土占领加速漏洞 - 计算与攻击方国家相邻的面数
        val adjacentFaces = if (territory.isOwned() && territory.owner?.id != country.id) {
            // 占领敌方领土时：计算与当前领土owner相同的相邻面数（原逻辑保持不变）
            territory.calculateAdjacentFaces()
        } else {
            // 占领无主领土时：计算与攻击方国家相邻的面数（修复漏洞）
            calculateAdjacentFacesForCountry(territory, country)
        }

        pluginLogger.info("🔧 [占领时间] 玩家 ${player.name} 占领 (${territory.x}, ${territory.z})：相邻面数=${adjacentFaces}，基础时间=${baseTime}s")

        // 如果是占领对方领土
        if (territory.isOwned() && territory.owner?.id != country.id) {
            // 根据接壤面数增加占领时间
            when {
                adjacentFaces >= 3 -> {
                    baseTime *= 2.0 // 增加100%
                    pluginLogger.info("🔧 [占领时间] 敌方领土，相邻面≥3，占领时间增加100%")
                }
                adjacentFaces == 2 -> {
                    baseTime *= 1.5 // 增加50%
                    pluginLogger.info("🔧 [占领时间] 敌方领土，相邻面=2，占领时间增加50%")
                }
            }

            // 如果是占领王城区块，额外增加1000%占领时长
            if (territory.isCapital) {
                baseTime *= 11.0 // 增加1000%
                pluginLogger.info("🔧 [占领时间] 敌方首都，占领时间增加1000%")
            }
        }
        // 如果是占领无主区域
        else if (!territory.isOwned()) {
            // 🔧 v1.3.48: 修复漏洞 - 只有与己方领土真正相邻时才给予加速
            when {
                adjacentFaces >= 3 -> {
                    baseTime /= 3.0 // 提升200%速度
                    pluginLogger.info("🔧 [占领时间] 无主领土，与己方相邻面≥3，占领速度提升200%")
                }
                adjacentFaces == 2 -> {
                    baseTime /= 2.0 // 提升100%速度
                    pluginLogger.info("🔧 [占领时间] 无主领土，与己方相邻面=2，占领速度提升100%")
                }
                adjacentFaces == 1 -> {
                    baseTime /= 1.5 // 提升50%速度
                    pluginLogger.info("🔧 [占领时间] 无主领土，与己方相邻面=1，占领速度提升50%")
                }
                else -> {
                    pluginLogger.info("🔧 [占领时间] 无主领土，与己方无相邻面，无加速效果")
                }
            }
        }

        // 征服者职业加成：圈地更快
        val user = UserManager.getUser(player.uniqueId)
        if (user?.profession == cn.lcofficial.guozhan.data.Profession.CONQUEROR) {
            // 根据职业等级应用不同的速度加成
            val speedBonus = when (user.professionLevel) {
                1 -> 0.75 // 1级：占领时间减少25%（速度提升33%）
                2 -> 0.50 // 2级：占领时间减少50%（速度提升100%）
                else -> 1.0 // 无加成
            }
            baseTime *= speedBonus
            pluginLogger.info("征服者职业加成：玩家 ${player.name} (等级${user.professionLevel}) 占领时间减少至${(speedBonus * 100).toInt()}%")
        }

        // 多人占领相同区块会触发占领速度提升
        // 这部分逻辑应该在实际占领过程中动态计算，这里只是示例
        // 每多1人速度提升25%，最多提升100%

        // 转换为毫秒
        return (baseTime * 1000).toLong()
    }

    /**
     * 🔧 v1.3.48: 新增方法 - 计算指定领土与指定国家相邻的面数
     * 修复中立领土占领加速漏洞：只计算与攻击方国家真正相邻的面数
     * @param territory 要检查的领土
     * @param country 攻击方国家
     * @return 与指定国家相邻的面数
     */
    private fun calculateAdjacentFacesForCountry(territory: TerritoryBlock, country: Country): Int {
        var adjacentFaces = 0

        // 检查上下左右四个方向是否有指定国家的区块
        val directions = arrayOf(
            Pair(territory.x + 1, territory.z), // 东
            Pair(territory.x - 1, territory.z), // 西
            Pair(territory.x, territory.z + 1), // 南
            Pair(territory.x, territory.z - 1)  // 北
        )

        for ((adjX, adjZ) in directions) {
            val adjacentBlock = getTerritoryBlock(adjX, adjZ, territory.world)
            // 🔧 v1.3.48: 关键修复 - 只有当邻居领土属于指定国家时才计算为相邻
            // 忽略 ownerId == null 的荒野领土，防止荒野领土被误计算为相邻面
            if (adjacentBlock != null && adjacentBlock.owner?.id == country.id) {
                adjacentFaces++
                pluginLogger.info("🔧 [相邻面计算] 发现相邻己方领土：(${adjX}, ${adjZ}) 属于国家 ${country.name}")
            }
        }

        pluginLogger.info("🔧 [相邻面计算] 领土 (${territory.x}, ${territory.z}) 与国家 ${country.name} 的相邻面数：${adjacentFaces}")
        return adjacentFaces
    }

    /**
     * 检查区块是否可以被占领
     * @param territory 要检查的区块
     * @param country 尝试占领的国家
     * @return 是否可以占领
     * v1.3.13修复：检查防御者（领土所有者）的护盾，而不是攻击者的护盾
     * v1.3.18修复：战争时间内核心区域忽略护盾
     */
    fun canClaim(territory: TerritoryBlock, country: Country): Boolean {
        // v1.3.18修复：战争时间内核心区域忽略护盾
        val defender = territory.owner
        if (defender != null && cn.lcofficial.guozhan.manager.ShieldManager.isShieldActive(defender)) {
            // 检查是否在战争时间的核心区域内
            val warScheduler = Guozhan.instance.warScheduler
            val isInCoreWarZone = warScheduler.isCoreWarZone(territory.x, territory.z)

            if (!isInCoreWarZone) {
                // 不在核心战争区域或不在战争时间，护盾生效
                return false
            }
            // 在战争时间的核心区域内，忽略护盾继续检查其他条件
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
            Pair(territory.x + 1, territory.z), // 东
            Pair(territory.x - 1, territory.z), // 西
            Pair(territory.x, territory.z + 1), // 南
            Pair(territory.x, territory.z - 1)  // 北
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
    territory.save(async = false)

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
    territory.save(async = false)

        // 🔧 代码审查修复: 触发地图更新以立即移除领土标记
        cn.lcofficial.guozhan.Guozhan.instance.squaremapIntegration.triggerMapUpdate()

        return true
    }

    /**
     * 恢复区块忠诚度
     * 🔧 v1.3.38修复：恢复忠诚度时同时更新lastLoyaltyUpdateTime，防止立即再次衰减
     * @param territory 要恢复的区块
     * @param amount 恢复的忠诚度百分比
     * @return 恢复后的忠诚度
     */
    fun restoreLoyalty(territory: TerritoryBlock, amount: Int): Int {
        if (!territory.isOwned()) return 0

        val oldLoyalty = territory.loyalty
        territory.loyalty += amount
        if (territory.loyalty > 100) territory.loyalty = 100

        // 🔧 v1.3.38修复：关键修复 - 更新时间戳，确保恢复后不会立即进入衰减检查
        territory.lastLoyaltyUpdateTime = System.currentTimeMillis()
        territory.save()

        pluginLogger.fine("恢复领土(${territory.x}, ${territory.z})忠诚度从${oldLoyalty}到${territory.loyalty}，更新时间戳")

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
    fun clearCaches() {
        territories.clear()
        territoryByCoords.clear()
        isPreloaded = false
    }

}
