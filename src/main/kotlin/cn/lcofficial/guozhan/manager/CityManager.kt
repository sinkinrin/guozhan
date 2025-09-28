package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.data.Cities
import cn.lcofficial.guozhan.data.City
import org.bukkit.Location
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

object CityManager {

    val cities = mutableMapOf<UUID, City>()

    fun getCity(id: UUID): City? = transaction {
        var city = cities[id]
        if (city == null) {
            city = Cities.selectAll().where { Cities.id eq id.toString() }.firstOrNull()?.let {
                City(
                    UUID.fromString(it[Cities.id].value),
                    it[Cities.x],
                    it[Cities.z],
                    it[Cities.loyalty],
                    UUID.fromString(it[Cities.owner]?.value)
                )
            }
            if (city != null) cities[id] = city
        }
        city
    }

    class CityAlreadyExistsException(x: Int, z: Int) : Exception("City at $x, $z already exists")


    fun createCity(x: Int, z: Int): City = transaction {
        val city = City(UUID.randomUUID(), x, z, 0, null)
        if (cities.values.any { it == city }) throw CityAlreadyExistsException(x, z)
        cities[city.id] = city
        Cities.insert {
            it[Cities.id] = city.id.toString()
            it[Cities.x] = city.x
            it[Cities.z] = city.z
            it[Cities.loyalty] = city.loyalty
            it[Cities.owner] = null
        }
        city
    }

    fun getCity(x: Int, z: Int): City? = transaction {
        var city = cities.values.firstOrNull { it.x == x && it.z == z }
        if (city == null) {
            city = Cities.selectAll().where { (Cities.x eq x) and (Cities.z eq z) }.firstOrNull()?.let {
                City(
                    UUID.fromString(it[Cities.id].value),
                    it[Cities.x],
                    it[Cities.z],
                    it[Cities.loyalty],
                    UUID.fromString(it[Cities.owner]?.value)
                )
            }
            if (city != null) cities[city.id] = city
        }
        city
    }

    fun Location.city(): City = getCity(chunk.x, chunk.z) ?: createCity(chunk.x, chunk.z)
}