package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.data.Cities
import cn.lcofficial.guozhan.data.City
import cn.lcofficial.guozhan.data.Countries
import cn.lcofficial.guozhan.data.Country
import cn.lcofficial.guozhan.manager.UserManager.user
import org.bukkit.entity.Player
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

object CountryManager {
    val countries = mutableMapOf<UUID, Country>()
    fun getCountry(uniqueId: UUID): Country? = transaction {
        var country = countries[uniqueId]
        if (country == null) {
            country = Countries.selectAll().where { Countries.id eq uniqueId.toString() }.firstOrNull()?.let {
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
                    it[Countries.coreHealth],
                    it[Countries.coreLocationX],
                    it[Countries.coreLocationY],
                    it[Countries.coreLocationZ],
                    it[Countries.coreWorld],
                    it[Countries.lastHealthRegenTime]
                )
            }
            if (country != null) {
                countries[uniqueId] = country
                Cities.select(
                    Cities.owner
                ).where { Cities.owner eq country.id.toString() }.forEach {
                    country.cities.add(CityManager.getCity(UUID.fromString(it[Cities.id].value))!!)
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
                    ?.let {
                        getCountry(UUID.fromString(it[Countries.id].value))
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
            it[Countries.owner] = user.uniqueId.toString()
            it[Countries.name] = country.name
            it[Countries.capital] = country.capitalId.toString()
            it[Countries.createTime] = country.createTime
            it[Countries.public] = country.public
            it[Countries.shield] = country.shield
            it[Countries.gold] = country.gold
            it[Countries.diamond] = country.diamond
            it[Countries.economyPoints] = country.economyPoints
            it[Countries.coreHealth] = country.coreHealth
            it[Countries.coreLocationX] = country.coreLocationX
            it[Countries.coreLocationY] = country.coreLocationY
            it[Countries.coreLocationZ] = country.coreLocationZ
            it[Countries.coreWorld] = country.coreWorld
            it[Countries.lastHealthRegenTime] = country.lastHealthRegenTime
        }
        
        // 创建国家核心
        country.createCore(player.location)
        
        user.country = country
        user.save()
        
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
        (Countries.selectAll().count() / pageSize) + 1
    }

}