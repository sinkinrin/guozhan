package cn.lcofficial.guozhan.data

import cn.lcofficial.guozhan.manager.CountryManager
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.*

object Users : IdTable<String>("gz_users") {
    override val id = char("id", 36).uniqueIndex().entityId() // UUID
    val name = varchar("name", 20)
    val countryId = optReference("country_id", Countries) // 可为空
    val rank = enumerationByName<Rank>("rank", 7).default(Rank.DEFAULT)
    val title = varchar("title", 32).default("国民")
    val profession = enumerationByName<Profession>("profession", 10).nullable()
    val professionLevel = integer("profession_level").default(1)
}

enum class Rank(val value: Int) { 
    OWNER(3), ADMIN(2), DEFAULT(1) 
}

class User(
    val uniqueId: UUID,
    var name: String,
    var countryId: UUID? = null,
    var rank: Rank = Rank.DEFAULT,
    var title: String = "国民",
    var profession: Profession? = null,
    var professionLevel: Int = 1
) {
    var country: Country?
        get() {
            countryId?.let { return CountryManager.getCountry(it) }
            return null
        }
        set(value) {
            if (value == null) {
                countryId = null
                return
            }
            countryId = value.id
        }

    fun save() = transaction {
        Users.update({ Users.id eq uniqueId.toString() }) {
            it[name] = name
            it[countryId] = this@User.countryId?.toString()
            it[rank] = rank
            it[title] = title
            it[profession] = profession
            it[professionLevel] = professionLevel
        }
    }
}