package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.data.User
import cn.lcofficial.guozhan.data.Users
import org.bukkit.entity.Player
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

object UserManager {
    val users = mutableMapOf<UUID, User>()

    fun getUser(uniqueId: UUID): User? = transaction{
        var user = users[uniqueId]
        if (user == null) {
            user = Users.selectAll().where { Users.id eq uniqueId.toString() }.firstOrNull()?.let {
                User(UUID.fromString(it[Users.id].value), it[Users.name])
            }
            if (user != null) users[uniqueId] = user
        }

        user
    }

    fun createUser(uniqueId: UUID, name: String): User = transaction{
        val user = User(uniqueId, name)
        users[uniqueId] = user
        Users.insert {
            it[Users.id] = user.uniqueId.toString()
            it[Users.name] = user.name
        }
        user
    }

    fun Player.user(): User {
        return getUser(uniqueId) ?: createUser(uniqueId, name)
    }
}