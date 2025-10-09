package cn.lcofficial.guozhan.data

import cn.lcofficial.guozhan.manager.CountryManager
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.*

object Territories : IdTable<String>("gz_territories") {
    override val id = char("id", 36).uniqueIndex().entityId()
    val x = integer("x")
    val z = integer("z")
    val countryId = optReference("country_id", Countries)
    val loyalty = integer("loyalty").default(100)
    val isCapital = bool("is_capital").default(false)
}

class Territory(
    val id: UUID,
    val x: Int,
    val z: Int,
    var countryId: UUID?,
    var loyalty: Int = 100,
    var isCapital: Boolean = false
) {
    var country: Country?
        get() = countryId?.let { CountryManager.getCountry(it) }
        set(value) {
            countryId = value?.id
        }

    fun save() = transaction {
        Territories.update({ Territories.id eq id.toString() }) {
            it[countryId] = this@Territory.countryId?.let { EntityID(it.toString(), Countries) }
            it[loyalty] = loyalty
            it[isCapital] = isCapital
        }
    }
}