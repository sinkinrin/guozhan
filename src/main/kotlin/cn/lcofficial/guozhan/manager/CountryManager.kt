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
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object CountryManager {
    val countries = ConcurrentHashMap<UUID, Country>()
    fun getCountry(uniqueId: UUID): Country? = transaction {
        var country = countries[uniqueId]
        if (country == null) {
            country = Countries.selectAll().where { Countries.id eq uniqueId.toString() }.firstOrNull()?.let { row ->
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
                        row[Countries.lastHealthRegenTime]
                    )
                } catch (e: IllegalArgumentException) {
                    pluginLogger.warning("[CountryManager] 跳过无效的UUID数据: 国家ID='${row[Countries.id].value}', 所有者ID='${row[Countries.owner].value}', 首都ID='${row[Countries.capital].value}' - ${e.message}")
                    null
                }
            }
            if (country != null) {
                countries[uniqueId] = country
                Cities.select(
                    Cities.id, Cities.owner
                ).where { Cities.owner eq country.id.toString() }.forEach { row ->
                    try {
                        val cityId = UUID.fromString(row[Cities.id].value)
                        val city = CityManager.getCity(cityId)
                        if (city != null) {
                            country.cities.add(city)
                        }
                    } catch (e: IllegalArgumentException) {
                        pluginLogger.warning("[CountryManager] 跳过无效的城市UUID: '${row[Cities.id].value}' - ${e.message}")
                    }
                }
            }
        }
        country
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
                    row[Countries.lastHealthRegenTime]
                )

                // 加载国家的城市数据
                Cities.select(
                    Cities.id, Cities.owner
                ).where { Cities.owner eq country.id.toString() }.forEach { cityRow ->
                    try {
                        val cityId = UUID.fromString(cityRow[Cities.id].value)
                        // 注意：这里只是预热缓存，不需要完整加载城市对象
                        // 城市对象会在实际使用时通过CityManager懒加载
                    } catch (e: IllegalArgumentException) {
                        pluginLogger.warning("[CountryManager] 跳过无效的城市UUID: '${cityRow[Cities.id].value}' - ${e.message}")
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
                    coreBlocksCreated++
                    val blockType = if (isCenterBlock) "首都核心" else "周围领土"
                    pluginLogger.info("[核心领地] 创建${blockType}块 (${blockX}, ${blockZ}) 属于国家 ${country.name}，血量: ${territoryBlock.coreHealth}")
                } catch (e: Exception) {
                    pluginLogger.warning("[核心领地] 创建核心领地块 (${blockX}, ${blockZ}) 失败: ${e.message}")
                }
            }
        }
        pluginLogger.info("[核心领地] 国家 ${country.name} 的3x3核心领地已写入数据库，中心坐标 (${centerX}, ${centerZ})，成功创建 ${coreBlocksCreated} 个领地块，其中1个首都核心块")

        // 设置创建者为君主
        user.rank = cn.lcofficial.guozhan.data.Rank.OWNER
        user.country = country
        user.save()

        // 🔧 v1.3.15: 修复王城所有权问题 - 设置城市所有者
        city.owner = country
        city.save()
        pluginLogger.info("[王城所有权] 国家 ${country.name} 的王城所有权已设置到坐标 (${city.x}, ${city.z})")

        // 添加到缓存
        countries[country.id] = country

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
                it[Countries.lastHealthRegenTime]
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
     */
    fun deleteCountry(country: Country) = transaction {
        try {
            // 1. 删除国家相关的所有数据
            // 注意：由于外键约束，需要按照依赖关系的逆序删除

            // 删除国家的所有城市
            Cities.deleteWhere { Cities.owner eq country.id.toString() }

            // 删除国家记录
            Countries.deleteWhere { Countries.id eq country.id.toString() }

            // 从内存缓存中移除
            countries.remove(country.id)

            pluginLogger.info("[删除国家] 国家 '${country.name}' (ID: ${country.id}) 已从数据库中删除")

        } catch (e: Exception) {
            pluginLogger.severe("[删除国家] 删除国家 '${country.name}' 时发生错误: ${e.message}")
            throw e
        }
    }

}