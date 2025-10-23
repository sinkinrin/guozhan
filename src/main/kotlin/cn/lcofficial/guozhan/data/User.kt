package cn.lcofficial.guozhan.data

import cn.lcofficial.guozhan.manager.CountryManager
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.insert
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
            // 🔧 v1.3.21: 更新成员缓存
            val oldCountryId = countryId

            if (value == null) {
                // 离开国家 - 从旧国家的缓存中移除
                if (oldCountryId != null) {
                    CountryManager.removeMemberFromCache(oldCountryId, uniqueId)
                }
                countryId = null
                return
            }

            // 加入新国家
            val newCountryId = value.id

            // 如果之前属于其他国家，从旧国家缓存中移除
            if (oldCountryId != null && oldCountryId != newCountryId) {
                CountryManager.removeMemberFromCache(oldCountryId, uniqueId)
            }

            // 添加到新国家缓存
            CountryManager.addMemberToCache(newCountryId, uniqueId)
            countryId = newCountryId
        }

    /**
     * 获取在线玩家实例
     */
    val player: Player?
        get() = Bukkit.getPlayer(uniqueId)

    /**
     * 保存用户数据到数据库
     * 🔧 v1.3.25: 默认异步执行，避免阻塞 region 线程
     * 🔧 v1.3.51: 支持同步保存，用于插件关闭时确保数据落盘
     */
    fun save(async: Boolean = true): Boolean {
        return if (async) {
            cn.lcofficial.guozhan.util.async { _ ->
                try {
                    transaction { persistUser() }
                } catch (e: Exception) {
                    cn.lcofficial.guozhan.Guozhan.instance.logger.severe(
                        "异步保存用户失败 ($uniqueId): ${e.message}"
                    )
                    e.printStackTrace()
                }
            }
            true
        } else {
            try {
                transaction { persistUser() }
                true
            } catch (e: Exception) {
                cn.lcofficial.guozhan.Guozhan.instance.logger.severe(
                    "同步保存用户失败 ($uniqueId): ${e.message}"
                )
                e.printStackTrace()
                false
            }
        }
    }

    private fun persistUser() {
        val updatedRows = Users.update({ Users.id eq uniqueId.toString() }) {
            it[name] = name
            it[countryId] = this@User.countryId?.let { id -> EntityID(id.toString(), Countries) }
            it[rank] = rank
            it[title] = title
            it[profession] = profession
            it[professionLevel] = professionLevel
            it[professionSetTime] = this@User.professionSetTime
            it[claimMode] = this@User.claimMode
        }

        if (updatedRows == 0) {
            try {
                Users.insert {
                    it[Users.id] = uniqueId.toString()
                    it[Users.name] = name
                    it[Users.countryId] = this@User.countryId?.let { id -> EntityID(id.toString(), Countries) }
                    it[Users.rank] = rank
                    it[Users.title] = title
                    it[Users.profession] = profession
                    it[Users.professionLevel] = professionLevel
                    it[Users.professionSetTime] = this@User.professionSetTime
                    it[Users.claimMode] = this@User.claimMode
                }
            } catch (insertException: Exception) {
                val message = insertException.message ?: ""
                val isDuplicate = message.contains("UNIQUE constraint failed") ||
                    message.contains("Duplicate entry")

                if (!isDuplicate) {
                    throw insertException
                }
            }
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
            "manage_diplomacy" -> rank.value >= 2 // 修复：管理员及以上可以管理外交
            "manage_war" -> rank.value >= 3 // 仅国王
            "admin" -> rank.value >= 2 // 管理员及以上
            "owner" -> rank.value >= 3 // 仅国王
            else -> rank.value >= 1 // 默认权限，所有成员
        }
    }
}
