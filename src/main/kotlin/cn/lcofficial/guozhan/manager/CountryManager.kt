package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.data.Cities
import cn.lcofficial.guozhan.data.City
import cn.lcofficial.guozhan.data.Countries
import cn.lcofficial.guozhan.data.Country
import cn.lcofficial.guozhan.data.ResourceType
import cn.lcofficial.guozhan.data.TerritoryBlock
import cn.lcofficial.guozhan.data.TerritoryBlocks
import cn.lcofficial.guozhan.data.Users
import cn.lcofficial.guozhan.manager.UserManager.user
import cn.lcofficial.guozhan.pluginLogger
import org.bukkit.entity.Player
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object CountryManager {
    val countries = ConcurrentHashMap<UUID, Country>()

    // 🔧 v1.3.21: 成员缓存 - 避免频繁查询数据库
    // Key: 国家ID, Value: 成员UUID集合
    // 🔧 v1.3.28: 改为 internal 以允许 DataManager 在清空数据时清理缓存
    internal val memberCache = ConcurrentHashMap<UUID, MutableSet<UUID>>()
    /**
     * 获取国家
     * 🔧 v1.3.22: 修复缓存命中仍开启事务的问题 - 先检查缓存，未命中才开启事务
     * 🔧 v1.3.52: 修复Critical问题C2 - 优化城市数据加载，解决N+1查询问题
     *
     * ⚠️ 重要提示：修改Country对象后必须立即调用country.save()确保数据一致性！
     */
    fun getCountry(uniqueId: UUID): Country? {
        // 先检查缓存，命中直接返回（无数据库操作）
        countries[uniqueId]?.let { return it }

        // 缓存未命中，才开启事务查询数据库
        return transaction {
            Countries.selectAll().where { Countries.id eq uniqueId.toString() }.firstOrNull()?.let { row ->
                try {
                    Country(
                        UUID.fromString(row[Countries.id].value),
                        UUID.fromString(row[Countries.owner].value),
                        row[Countries.name],
                        row[Countries.createTime],
                        row[Countries.public],
                        row[Countries.shield],
                        row[Countries.gold],
                        row[Countries.diamond],
                        row[Countries.economyPoints],
                        UUID.fromString(row[Countries.capital].value),
                        row[Countries.declaration], // v1.3.19新增：加载国家宣言
                        row[Countries.shieldEndTime],
                        row[Countries.shieldCooldownEnd],
                        row[Countries.coreHealth],
                        row[Countries.coreLocationX],
                        row[Countries.coreLocationY],
                        row[Countries.coreLocationZ],
                        row[Countries.coreWorld],
                        row[Countries.lastHealthRegenTime],
                        // 🔧 v1.3.50: 修复国家税收元数据加载缺失 - 添加缺失的字段加载
                        row[Countries.taxRate],
                        row[Countries.accumulatedGoldTax],
                        row[Countries.accumulatedDiamondTax],
                        row[Countries.lastManualTaxTime],
                        row[Countries.lastAutoTaxTime],
                        row[Countries.mapColor]
                    )
                } catch (e: IllegalArgumentException) {
                    pluginLogger.warning("[CountryManager] 跳过无效的UUID数据: 国家ID='${row[Countries.id].value}', 所有者ID='${row[Countries.owner].value}', 首都ID='${row[Countries.capital].value}' - ${e.message}")
                    null
                }
            }?.also { country ->
                // 将查询结果加入缓存
                countries[uniqueId] = country

                // 🔧 v1.3.52: 修复Critical问题C2 - 优化城市数据加载，批量查询避免N+1问题
                // 先批量查询所有城市ID
                val cityIds = Cities.select(Cities.id)
                    .where { Cities.owner eq country.id.toString() }
                    .map { UUID.fromString(it[Cities.id].value) }

                // 批量加载城市数据（CityManager内部会使用缓存）
                cityIds.forEach { cityId ->
                    try {
                        val city = CityManager.getCity(cityId)
                        if (city != null) {
                            country.cities.add(city)
                        }
                    } catch (e: IllegalArgumentException) {
                        pluginLogger.warning("[CountryManager] 跳过无效的城市UUID: '$cityId' - ${e.message}")
                    }
                }
            }
        }
    }

    fun getByName(name: String): Country? = transaction {
        var country = countries.values.firstOrNull { it.name == name }
        if (country == null) {
            country =
                Countries.select(listOf(Countries.id, Countries.name)).where { Countries.name eq name }.firstOrNull()
                    ?.let { row ->
                        try {
                            getCountry(UUID.fromString(row[Countries.id].value))
                        } catch (e: IllegalArgumentException) {
                            pluginLogger.warning("[CountryManager] 跳过无效的国家UUID: '${row[Countries.id].value}' - ${e.message}")
                            null
                        }
                    }
        }
        country
    }

    /**
     * 根据名称获取国家（别名方法）
     */
    fun getCountryByName(name: String): Country? = getByName(name)

    /**
     * 根据ID获取国家（别名方法）
     */
    fun getCountryById(id: UUID): Country? = getCountry(id)

    /**
     * 获取所有国家
     */
    fun getAllCountries(): Collection<Country> = countries.values

    /**
     * 预热缓存：从数据库加载所有国家数据到内存缓存
     * 用于插件启动时确保缓存已填充，避免其他Manager初始化时遍历空缓存
     */
    fun reloadAll() = transaction {
        val startTime = System.currentTimeMillis()
        var loadedCount = 0
        var skippedCount = 0

        pluginLogger.info("[CountryManager] 开始预热缓存：从数据库加载所有国家...")

        Countries.selectAll().forEach { row ->
            try {
                val countryId = UUID.fromString(row[Countries.id].value)

                // 如果缓存中已存在，跳过
                if (countries.containsKey(countryId)) {
                    return@forEach
                }

                val country = Country(
                    countryId,
                    UUID.fromString(row[Countries.owner].value),
                    row[Countries.name],
                    row[Countries.createTime],
                    row[Countries.public],
                    row[Countries.shield],
                    row[Countries.gold],
                    row[Countries.diamond],
                    row[Countries.economyPoints],
                    UUID.fromString(row[Countries.capital].value),
                    row[Countries.declaration], // v1.3.19新增：加载国家宣言
                    row[Countries.shieldEndTime],
                    row[Countries.shieldCooldownEnd],
                    row[Countries.coreHealth],
                    row[Countries.coreLocationX],
                    row[Countries.coreLocationY],
                    row[Countries.coreLocationZ],
                    row[Countries.coreWorld],
                    row[Countries.lastHealthRegenTime],
                    // 🔧 v1.3.50: 修复国家税收元数据加载缺失 - 添加缺失的字段加载
                    row[Countries.taxRate],
                    row[Countries.accumulatedGoldTax],
                    row[Countries.accumulatedDiamondTax],
                    row[Countries.lastManualTaxTime],
                    row[Countries.lastAutoTaxTime],
                    row[Countries.mapColor]
                )

                // 🔧 v1.3.66: 修复High问题1 - 服务器重启后国家城市缓存未填充
                // 加载国家的城市数据并添加到country.cities列表
                val cityIds = Cities.select(Cities.id)
                    .where { Cities.owner eq country.id.toString() }
                    .map { UUID.fromString(it[Cities.id].value) }

                // 批量加载城市数据（CityManager内部会使用缓存）
                cityIds.forEach { cityId ->
                    try {
                        val city = CityManager.getCity(cityId)
                        if (city != null) {
                            country.cities.add(city)
                        }
                    } catch (e: IllegalArgumentException) {
                        pluginLogger.warning("[CountryManager] 跳过无效的城市UUID: '$cityId' - ${e.message}")
                    }
                }

                countries[countryId] = country
                loadedCount++

            } catch (e: IllegalArgumentException) {
                pluginLogger.warning("[CountryManager] 跳过无效的UUID数据: 国家ID='${row[Countries.id].value}', 所有者ID='${row[Countries.owner].value}', 首都ID='${row[Countries.capital].value}' - ${e.message}")
                skippedCount++
            } catch (e: Exception) {
                pluginLogger.severe("[CountryManager] 加载国家数据时发生错误: ${e.message}")
                e.printStackTrace()
                skippedCount++
            }
        }

        val duration = System.currentTimeMillis() - startTime
        pluginLogger.info("[CountryManager] 缓存预热完成：加载了 $loadedCount 个国家，跳过 $skippedCount 个无效记录，耗时 ${duration}ms")
        pluginLogger.info("[CountryManager] 当前缓存大小：${countries.size} 个国家")
    }

    /**
     * 强制重载所有国家数据
     * 🔧 v1.3.51: 修复重载不刷新缓存问题 - 添加强制刷新方法
     */
    fun forceReloadAll() = transaction {
        val startTime = System.currentTimeMillis()
        var loadedCount = 0
        var skippedCount = 0

        pluginLogger.info("[CountryManager] 开始强制重载缓存：从数据库重新加载所有国家...")

        Countries.selectAll().forEach { row ->
            try {
                val countryId = UUID.fromString(row[Countries.id].value)

                val country = Country(
                    countryId,
                    UUID.fromString(row[Countries.owner].value),
                    row[Countries.name],
                    row[Countries.createTime],
                    row[Countries.public],
                    row[Countries.shield],
                    row[Countries.gold],
                    row[Countries.diamond],
                    row[Countries.economyPoints],
                    UUID.fromString(row[Countries.capital].value),
                    row[Countries.declaration],
                    row[Countries.shieldEndTime],
                    row[Countries.shieldCooldownEnd],
                    row[Countries.coreHealth],
                    row[Countries.coreLocationX],
                    row[Countries.coreLocationY],
                    row[Countries.coreLocationZ],
                    row[Countries.coreWorld],
                    row[Countries.lastHealthRegenTime],
                    row[Countries.taxRate],
                    row[Countries.accumulatedGoldTax],
                    row[Countries.accumulatedDiamondTax],
                    row[Countries.lastManualTaxTime],
                    row[Countries.lastAutoTaxTime],
                    row[Countries.mapColor]
                )

                // 🔧 v1.3.67: 修复Medium问题3 - forceReloadAll()也要加载城市列表
                // 加载国家的城市数据并添加到country.cities列表
                val cityIds = Cities.select(Cities.id)
                    .where { Cities.owner eq country.id.toString() }
                    .map { UUID.fromString(it[Cities.id].value) }

                // 批量加载城市数据（CityManager内部会使用缓存）
                cityIds.forEach { cityId ->
                    try {
                        val city = CityManager.getCity(cityId)
                        if (city != null) {
                            country.cities.add(city)
                        }
                    } catch (e: IllegalArgumentException) {
                        pluginLogger.warning("[CountryManager] 跳过无效的城市UUID: '$cityId' - ${e.message}")
                    }
                }

                // 强制更新缓存
                countries[countryId] = country
                loadedCount++

            } catch (e: IllegalArgumentException) {
                pluginLogger.warning("[CountryManager] 跳过无效的UUID数据: 国家ID='${row[Countries.id].value}', 所有者ID='${row[Countries.owner].value}' - ${e.message}")
                skippedCount++
            } catch (e: Exception) {
                pluginLogger.severe("[CountryManager] 强制重载国家数据时发生错误: ${e.message}")
                e.printStackTrace()
                skippedCount++
            }
        }

        val duration = System.currentTimeMillis() - startTime
        pluginLogger.info("[CountryManager] 强制重载完成：重载了 $loadedCount 个国家，跳过 $skippedCount 个无效记录，耗时 ${duration}ms")
        pluginLogger.info("[CountryManager] 当前缓存大小：${countries.size} 个国家")
    }

    fun create(player: Player, city: City, name: String): Country? = transaction {
        val user = player.user()
        if (user.country != null) {
            return@transaction null
        }
        val country = Country(
            UUID.randomUUID(), user.uniqueId, name,
            createTime = System.currentTimeMillis(),
            public = true,
            shield = false,
            gold = 0,
            diamond = 0,
            economyPoints = 0,
            capitalId = city.id
        )

        Countries.insert {
            it[Countries.id] = country.id.toString()
            it[Countries.owner] = EntityID(user.uniqueId.toString(), Users)
            it[Countries.name] = country.name
            it[Countries.capital] = EntityID(country.capitalId.toString(), Cities)
            it[Countries.createTime] = country.createTime
            it[Countries.public] = country.public
            it[Countries.shield] = country.shield
            it[Countries.gold] = country.gold
            it[Countries.diamond] = country.diamond
            it[Countries.economyPoints] = country.economyPoints
            it[Countries.declaration] = country.declaration // v1.3.19新增：初始化国家宣言
            it[Countries.coreHealth] = country.coreHealth
            it[Countries.coreLocationX] = country.coreLocationX
            it[Countries.coreLocationY] = country.coreLocationY
            it[Countries.coreLocationZ] = country.coreLocationZ
            it[Countries.coreWorld] = country.coreWorld
            it[Countries.lastHealthRegenTime] = country.lastHealthRegenTime
        }

        // 创建国家核心
        country.createCore(player.location)

        // 🔧 v1.3.16: 修复核心领地未写入数据库的Bug - 创建3x3核心领地块
        val centerX = city.x
        val centerZ = city.z
        val worldName = player.location.world?.name ?: "world"

        // 创建3x3核心领地区域 - 直接在当前事务中创建，避免嵌套事务
        // 🔧 v1.3.27: 修复首都领土缓存未填充的关键Bug
        var coreBlocksCreated = 0
        for (dx in -1..1) {
            for (dz in -1..1) {
                val blockX = centerX + dx
                val blockZ = centerZ + dz
                try {
                    // 🔧 v1.3.19: 修复首都标记和核心血量初始化Bug
                    // 只有中心区块(0,0)应该被标记为首都，周围8个区块为普通领土
                    val isCenterBlock = (dx == 0 && dz == 0)

                    // 直接创建TerritoryBlock而不调用TerritoryManager的事务方法
                    val territoryBlock = TerritoryBlock(
                        id = UUID.randomUUID(),
                        x = blockX,
                        z = blockZ,
                        world = worldName,
                        loyalty = 100,
                        ownerId = country.id, // 需要提供ownerId参数
                        resourceType = ResourceType.NONE,
                        resourceAmount = 0,
                        lastHarvestTime = 0L,
                        lastLoyaltyUpdateTime = System.currentTimeMillis(),
                        isCapital = isCenterBlock, // 只有中心区块是首都
                        coreHealth = if (isCenterBlock) cn.lcofficial.guozhan.config.Config.Country.coreHealthInitial else 0, // 首都区块使用配置的初始血量，其他为0
                        lastCoreHealthUpdateTime = System.currentTimeMillis()
                    )

                    // 直接插入数据库，避免嵌套事务
                    TerritoryBlocks.insert {
                        it[TerritoryBlocks.id] = territoryBlock.id.toString()
                        it[TerritoryBlocks.x] = territoryBlock.x
                        it[TerritoryBlocks.z] = territoryBlock.z
                        it[TerritoryBlocks.world] = territoryBlock.world
                        it[TerritoryBlocks.loyalty] = territoryBlock.loyalty
                        it[TerritoryBlocks.owner] = EntityID(country.id.toString(), Countries)
                        it[TerritoryBlocks.resourceType] = territoryBlock.resourceType
                        it[TerritoryBlocks.resourceAmount] = territoryBlock.resourceAmount
                        it[TerritoryBlocks.lastHarvestTime] = territoryBlock.lastHarvestTime
                        it[TerritoryBlocks.lastLoyaltyUpdateTime] = territoryBlock.lastLoyaltyUpdateTime
                        it[TerritoryBlocks.isCapital] = territoryBlock.isCapital
                        it[TerritoryBlocks.coreHealth] = territoryBlock.coreHealth
                        it[TerritoryBlocks.lastCoreHealthUpdateTime] = territoryBlock.lastCoreHealthUpdateTime
                    }

                    // 🔧 v1.3.27: 关键修复 - 将创建的领土区块添加到缓存中
                    // 这样可以防止后续查询时缓存未命中导致创建重复记录
                    TerritoryManager.territories[territoryBlock.id] = territoryBlock
                    val coordKey = "${territoryBlock.world}:${territoryBlock.x}:${territoryBlock.z}"
                    TerritoryManager.territoryByCoords[coordKey] = territoryBlock

                    coreBlocksCreated++
                    val blockType = if (isCenterBlock) "首都核心" else "周围领土"
                    pluginLogger.info("[核心领地] 创建${blockType}块(${blockX}, ${blockZ}) 属于国家 ${country.name}，血量: ${territoryBlock.coreHealth}，已添加到缓存")
                } catch (e: Exception) {
                    pluginLogger.warning("[核心领地] 创建核心领地块(${blockX}, ${blockZ}) 失败: ${e.message}")
                }
            }
        }
        pluginLogger.info("[核心领地] 国家 ${country.name} 的3x3核心领地已写入数据库，中心坐标(${centerX}, ${centerZ})，成功创建${coreBlocksCreated} 个领地块，其中1个首都核心块")

        // 设置创建者为君主
        // 🔧 v1.3.32: 修复新创建国家的玩家权限问题 - 立即更新缓存并同步保存关键数据
        user.rank = cn.lcofficial.guozhan.data.Rank.OWNER
        user.country = country

        // 立即更新用户缓存，确保权限检查能立即生效
        UserManager.users[user.uniqueId] = user

        // 同步保存用户数据，确保权限立即生效
        try {
            val rowsUpdated = Users.update({ Users.id eq user.uniqueId.toString() }) {
                it[Users.rank] = user.rank
                it[Users.countryId] = EntityID(country.id.toString(), Countries)
            }

            if (rowsUpdated == 0) {
                try {
                    Users.insert {
                        it[Users.id] = user.uniqueId.toString()
                        it[Users.name] = user.name
                        it[Users.countryId] = EntityID(country.id.toString(), Countries)
                        it[Users.rank] = user.rank
                        it[Users.title] = user.title
                        it[Users.profession] = user.profession
                        it[Users.professionLevel] = user.professionLevel
                        it[Users.professionSetTime] = user.professionSetTime
                        it[Users.claimMode] = user.claimMode
                    }
                    pluginLogger.info("[权限设置] 首次创建用户记录，已将${user.name} 保存为君主")
                } catch (insertException: Exception) {
                    val message = insertException.message ?: ""
                    val isDuplicate = message.contains("UNIQUE constraint failed") ||
                        message.contains("Duplicate entry")

                    if (!isDuplicate) {
                        pluginLogger.severe("[权限设置] 插入用户权限记录失败: ${insertException.message}")
                        insertException.printStackTrace()
                    }

                    // 即使插入失败也继续异步保存一次，确保最终一致
                    user.save()
                }
            } else {
                pluginLogger.info("[权限设置] 国家创建后${user.name} 的权限已同步更新为君主")
            }
        } catch (e: Exception) {
            pluginLogger.severe("[权限设置] 同步更新用户权限失败: ${e.message}")
            // 如果同步失败，仍然异步保存
            user.save()
        }

        // 🔧 v1.3.15: 修复王城所有权问题 - 设置城市所有者
        city.owner = country
        city.save()
        pluginLogger.info("[王城所有权] 国家 ${country.name} 的王城所有权已设置到坐标 (${city.x}, ${city.z})")

        // 添加到缓存
        countries[country.id] = country

        // 🔧 v1.3.58: 修复测试环境资源持久化问题 - 在事务内保存资源
        // 测试环境：给予国家启动资源
        val resourcesGiven = cn.lcofficial.guozhan.manager.TestEnvironmentManager.giveCountryStartupResources(country, player)
        if (resourcesGiven) {
            // 在同一个事务中更新国家资源到数据库
            Countries.update({ Countries.id eq country.id.toString() }) {
                it[Countries.gold] = country.gold
                it[Countries.diamond] = country.diamond
                it[Countries.economyPoints] = country.economyPoints
            }
            pluginLogger.info("[测试环境] 国家 ${country.name} 的启动资源已在事务中保存: 金币=${country.gold}, 钻石=${country.diamond}, 经济点数=${country.economyPoints}")
        }

        country
    }

    fun listPage(page: Int, pageSize: Int): List<Country> = transaction {
        Countries.selectAll().offset((page - 1L) * pageSize).limit(pageSize).orderBy(Countries.name).map {
            Country(
                UUID.fromString(it[Countries.id].value),
                UUID.fromString(it[Countries.owner].value),
                it[Countries.name],
                it[Countries.createTime],
                it[Countries.public],
                it[Countries.shield],
                it[Countries.gold],
                it[Countries.diamond],
                it[Countries.economyPoints],
                UUID.fromString(it[Countries.capital].value),
                it[Countries.declaration], // v1.3.19新增：加载国家宣言
                it[Countries.shieldEndTime],
                it[Countries.shieldCooldownEnd],
                it[Countries.coreHealth],
                it[Countries.coreLocationX],
                it[Countries.coreLocationY],
                it[Countries.coreLocationZ],
                it[Countries.coreWorld],
                it[Countries.lastHealthRegenTime],
                // 🔧 v1.3.50: 修复国家税收元数据加载缺失 - 添加缺失的字段加载
                it[Countries.taxRate],
                it[Countries.accumulatedGoldTax],
                it[Countries.accumulatedDiamondTax],
                it[Countries.lastManualTaxTime],
                it[Countries.lastAutoTaxTime],
                it[Countries.mapColor]
            )
        }.toList()
    }

    fun totalPages(pageSize: Int): Long = transaction {
        val count = Countries.selectAll().count()
        if (count == 0L) 0L else (count + pageSize - 1) / pageSize // 向上取整
    }

    /**
     * 删除国家
     * @param country 要删除的国家
     *
     * 🔧 v1.3.21: 修复级联删除问题 - 完整清理所有依赖数据
     * - 清理领土块（territory_blocks）的 owner 引用
     * - 清理领土（territories）的 country_id 引用
     * - 清理用户（users）的 country_id 引用
     * - 删除外交关系（diplomatic_relations）
     * - 删除科技研究（country_technologies）
     * - 删除城市（cities）
     * - 清除内存缓存（boss bar、成员缓存等）
     */
    fun deleteCountry(country: Country) = transaction {
        try {
            pluginLogger.info("[删除国家] 开始删除国家'${country.name}' (ID: ${country.id})")

            // 1. 清理领土块（TerritoryBlocks），将 owner 设为 null
            // 🔧 v1.3.27: 关键修复 - 在删除数据库记录前，先收集需要清除的缓存条目
            val territoryBlocksToRemove = TerritoryBlocks.selectAll()
                .where { TerritoryBlocks.owner eq country.id.toString() }
                .map { row ->
                    Triple(
                        UUID.fromString(row[TerritoryBlocks.id].value),
                        row[TerritoryBlocks.x],
                        Triple(row[TerritoryBlocks.z], row[TerritoryBlocks.world], row[TerritoryBlocks.world])
                    )
                }

            val territoryBlockCount = territoryBlocksToRemove.size
            if (territoryBlockCount > 0) {
                TerritoryBlocks.deleteWhere { TerritoryBlocks.owner eq country.id.toString() }

                // 🔧 v1.3.27: 关键修复 - 从缓存中移除已删除的领土块
                var removedFromCache = 0
                territoryBlocksToRemove.forEach { (id, x, zWorldPair) ->
                    val (z, world, _) = zWorldPair
                    // 从 UUID 索引中移除
                    TerritoryManager.territories.remove(id)
                    // 从坐标索引中移除
                    val coordKey = "$world:$x:$z"
                    TerritoryManager.territoryByCoords.remove(coordKey)
                    removedFromCache++
                }

                pluginLogger.info("[删除国家] 已删除${territoryBlockCount} 个领土块，并从缓存中移除 ${removedFromCache} 个条目")
            }

            // 2. 清理领土（Territories），将 country_id 设为 null
            val territoryCount = cn.lcofficial.guozhan.data.Territories.selectAll()
                .where { cn.lcofficial.guozhan.data.Territories.countryId eq country.id.toString() }
                .count()
            if (territoryCount > 0) {
                cn.lcofficial.guozhan.data.Territories.deleteWhere {
                    cn.lcofficial.guozhan.data.Territories.countryId eq country.id.toString()
                }
                pluginLogger.info("[删除国家] 已删除${territoryCount} 个领土")
            }

            // 3. 清理用户（Users），将 country_id 设为 null（保留玩家数据）
            // 🔧 v1.3.22: 修复玩家数据丢失问题 - 使用 update 而非 deleteWhere
            val memberCount = Users.update({ Users.countryId eq EntityID(country.id.toString(), Countries) }) {
                it[countryId] = null
            }
            if (memberCount > 0) {
                pluginLogger.info("[删除国家] 已清理${memberCount} 个成员的国家关联（保留玩家数据：头衔/职业/贡献等）")

                // 刷新成员缓存 - 清除这些玩家的国家引用
                UserManager.users.values.forEach { user ->
                    if (user.countryId == country.id) {
                        user.countryId = null
                    }
                }
            }

            // 4. 删除外交关系（DiplomaticRelations）
            val diplomaticCount1 = cn.lcofficial.guozhan.data.DiplomaticRelations.selectAll()
                .where { cn.lcofficial.guozhan.data.DiplomaticRelations.country1Id eq country.id }
                .count()
            val diplomaticCount2 = cn.lcofficial.guozhan.data.DiplomaticRelations.selectAll()
                .where { cn.lcofficial.guozhan.data.DiplomaticRelations.country2Id eq country.id }
                .count()
            if (diplomaticCount1 > 0 || diplomaticCount2 > 0) {
                cn.lcofficial.guozhan.data.DiplomaticRelations.deleteWhere {
                    (cn.lcofficial.guozhan.data.DiplomaticRelations.country1Id eq country.id) or
                    (cn.lcofficial.guozhan.data.DiplomaticRelations.country2Id eq country.id)
                }
                pluginLogger.info("[删除国家] 已删除${diplomaticCount1 + diplomaticCount2} 条外交关系")
            }

            // 5. 删除科技研究（CountryTechnologies）
            val techCount = cn.lcofficial.guozhan.data.CountryTechnologies.selectAll()
                .where { cn.lcofficial.guozhan.data.CountryTechnologies.countryId eq country.id.toString() }
                .count()
            if (techCount > 0) {
                cn.lcofficial.guozhan.data.CountryTechnologies.deleteWhere {
                    cn.lcofficial.guozhan.data.CountryTechnologies.countryId eq country.id.toString()
                }
                pluginLogger.info("[删除国家] 已删除${techCount} 条科技研究记录")
            }

            // 6. 清空城市所有者（Cities），而不是删除城市记录
            // 🔧 v1.3.64: 修复灭国后无法在原地重建的问题 - 清空所有者而非删除城市
            val cityCount = Cities.update({ Cities.owner eq country.id.toString() }) {
                it[owner] = null
            }
            if (cityCount > 0) {
                // 同时清理缓存中的城市所有者
                CityManager.cities.values.forEach { city ->
                    if (city.owner?.id == country.id) {
                        city.owner = null
                    }
                }
                pluginLogger.info("[删除国家] 已清空${cityCount} 个城市的所有者，现在可以在这些位置重新建国")
            }

            // 7. 删除国家记录
            Countries.deleteWhere { Countries.id eq country.id.toString() }
            pluginLogger.info("[删除国家] 已删除国家主记录")

            // 8. 清除内存缓存
            countries.remove(country.id)

            // 9. 清除 Boss Bar 缓存（如果存在）
            // 🔧 v1.3.30: 关键修复 - 使用单国家清理方法，而不是全局清理
            // 避免删除一个国家时停掉所有国家的核心系统
            try {
                // 清除该国家的核心血量 BossBar 和相关资源
                CoreManager.cleanupCountry(country.id)
                pluginLogger.info("[删除国家] 已清除国家${country.name} 的 Boss Bar 缓存")
            } catch (e: Exception) {
                pluginLogger.warning("[删除国家] 清除 Boss Bar 缓存时出错: ${e.message}")
            }

            // 10. 清除成员缓存（如果存在）
            try {
                // 从 UserManager 缓存中移除该国家的成员引用
                UserManager.users.values.forEach { user ->
                    if (user.country?.id == country.id) {
                        user.country = null
                    }
                }
                pluginLogger.info("[删除国家] 已清除成员缓存")
            } catch (e: Exception) {
                pluginLogger.warning("[删除国家] 清除成员缓存时出错: ${e.message}")
            }

            // 🔧 代码审查修复: 触发地图更新以立即移除领土标记
            try {
                cn.lcofficial.guozhan.Guozhan.instance.squaremapIntegration.triggerMapUpdate()
                pluginLogger.info("[删除国家] 已触发地图更新")
            } catch (e: Exception) {
                pluginLogger.warning("[删除国家] 触发地图更新时出错: ${e.message}")
            }

            pluginLogger.info("[删除国家] 国家 '${country.name}' (ID: ${country.id}) 已完全删除，所有依赖数据已清理")

        } catch (e: Exception) {
            pluginLogger.severe("[删除国家] 删除国家 '${country.name}' 时发生错误: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    // ==================== 成员缓存管理 ====================

    /**
     * 获取国家成员列表（从缓存）
     * 🔧 v1.3.21: 使用内存缓存避免频繁查询数据库
     *
     * @param countryId 国家ID
     * @return 成员UUID列表
     */
    fun getCountryMembers(countryId: UUID): List<UUID> {
        // 如果缓存中没有，从数据库加载
        if (!memberCache.containsKey(countryId)) {
            refreshMemberCache(countryId)
        }
        return memberCache[countryId]?.toList() ?: emptyList()
    }

    /**
     * 刷新国家成员缓存
     * 🔧 v1.3.52: 修复线程安全问题 - 使用 ConcurrentHashMap.newKeySet() 确保并发安全
     *
     * @param countryId 国家ID
     */
    fun refreshMemberCache(countryId: UUID) {
        transaction {
            // 🔧 使用线程安全的集合，避免 ConcurrentModificationException
            val members = ConcurrentHashMap.newKeySet<UUID>()
            Users.selectAll()
                .where { Users.countryId eq EntityID(countryId.toString(), Countries) }
                .forEach { row ->
                    members.add(UUID.fromString(row[Users.id].value))
                }

            memberCache[countryId] = members
            pluginLogger.info("[成员缓存] 已刷新国家${countryId} 的成员缓存，共${members.size} 人")
        }
    }

    /**
     * 添加成员到缓存
     *
     * @param countryId 国家ID
     * @param userId 用户ID
     */
    fun addMemberToCache(countryId: UUID, userId: UUID) {
        memberCache.computeIfAbsent(countryId) { ConcurrentHashMap.newKeySet() }.add(userId)
        pluginLogger.info("[成员缓存] 已添加成员${userId} 到国家${countryId}")
    }

    /**
     * 从缓存中移除成员
     *
     * @param countryId 国家ID
     * @param userId 用户ID
     */
    fun removeMemberFromCache(countryId: UUID, userId: UUID) {
        memberCache[countryId]?.remove(userId)
        pluginLogger.info("[成员缓存] 已从国家 ${countryId} 移除成员 ${userId}")
    }

    /**
     * 清除国家的成员缓存
     *
     * @param countryId 国家ID
     */
    fun clearMemberCache(countryId: UUID) {
        memberCache.remove(countryId)
        pluginLogger.info("[成员缓存] 已清除国家${countryId} 的成员缓存")
    }

    /**
     * 初始化所有国家的成员缓存
     * 应在插件启动时调用
     */
    fun initializeMemberCache() {
        transaction {
            val allCountries = Countries.selectAll().map { UUID.fromString(it[Countries.id].value) }
            allCountries.forEach { countryId ->
                refreshMemberCache(countryId)
            }
            pluginLogger.info("[成员缓存] 已初始化 ${allCountries.size} 个国家的成员缓存")
        }
    }

    fun clearCaches() {
        countries.clear()
        memberCache.clear()
    }

}
