package cn.lcofficial.guozhan.data

import cn.lcofficial.guozhan.manager.CountryManager
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.EntityID
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
    val professionSetTime = long("profession_set_time").nullable() // 职业设置/升级时间戳
    val claimMode = enumerationByName<ClaimMode>("claim_mode", 10).default(ClaimMode.AUTO)
}

enum class Rank(val value: Int) {
    OWNER(3), ADMIN(2), DEFAULT(1)
}

enum class ClaimMode {
    AUTO,    // 自动占领模式
    MANUAL   // 手动占领模式（需要木斧右键）
}

class User(
    val uniqueId: UUID,
    var name: String,
    var countryId: UUID? = null,
    var rank: Rank = Rank.DEFAULT,
    var title: String = "国民",
    var profession: Profession? = null,
    var professionLevel: Int = 1,
    var professionSetTime: Long? = null, // 职业设置/升级时间戳
    var claimMode: ClaimMode = ClaimMode.AUTO
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

    /**
     * 获取在线玩家实例
     */
    val player: Player?
        get() = Bukkit.getPlayer(uniqueId)

    fun save() = transaction {
        Users.update({ Users.id eq uniqueId.toString() }) {
            it[name] = name
            it[countryId] = this@User.countryId?.let { EntityID(it.toString(), Countries) }
            it[rank] = rank
            it[title] = title
            it[profession] = profession
            it[professionLevel] = professionLevel
            it[professionSetTime] = this@User.professionSetTime
            it[claimMode] = this@User.claimMode
        }
    }

    /**
     * 检查用户是否有指定的国家权限
     * @param permission 权限名称
     * @return 是否有权限
     */
    fun hasCountryPermission(permission: String): Boolean {
        // 如果用户不属于任何国家，没有权限
        if (country == null) return false

        // 根据权限类型和用户等级判断
        return when (permission) {
            "manage_tribute" -> rank.value >= 2 // 管理员及以上
            "manage_economy" -> rank.value >= 2 // 管理员及以上
            "manage_territory" -> rank.value >= 2 // 管理员及以上
            "manage_diplomacy" -> rank.value >= 3 // 仅国王
            "manage_war" -> rank.value >= 3 // 仅国王
            "admin" -> rank.value >= 2 // 管理员及以上
            "owner" -> rank.value >= 3 // 仅国王
            else -> rank.value >= 1 // 默认权限，所有成员
        }
    }
}