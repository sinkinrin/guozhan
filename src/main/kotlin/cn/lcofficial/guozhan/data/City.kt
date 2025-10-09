package cn.lcofficial.guozhan.data

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.*

object Cities : IdTable<String>("gz_cities") {
    override val id = char("id", 36).uniqueIndex().entityId()
    val owner = optReference("owner", Countries)
    val x = integer("x")
    val z = integer("z")
    val loyalty = integer("loyalty").default(100)
}

class City(
    val id: UUID,
    val x: Int,
    val z: Int,
    var loyalty: Int,
    private var ownerId: UUID?,
) {
    var owner: Country?
        get() {
            return if (ownerId == null) null else cn.lcofficial.guozhan.manager.CountryManager.getCountry(ownerId!!)
        }
        set(value) {
            if (value == null) {
                ownerId = null
                return
            }
            ownerId = value.id
        }

    fun isOwned(): Boolean = ownerId != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as City
        return x == other.x && z == other.z
    }

    override fun hashCode(): Int {
        var result = x
        result = 31 * result + z
        return result
    }

    fun save() = transaction {
        Cities.update({ Cities.id eq id }) {
            it[Cities.loyalty] = loyalty
            // 🔧 v1.3.15: 修复王城所有权问题 - 持久化所有者字段
            if (ownerId != null) {
                it[Cities.owner] = org.jetbrains.exposed.dao.id.EntityID(ownerId.toString(), Countries)
            } else {
                it[Cities.owner] = null
            }
        }
    }
}