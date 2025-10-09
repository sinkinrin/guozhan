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
            user = Users.selectAll().where { Users.id eq uniqueId.toString() }.firstOrNull()?.let { row ->
                User(
                    uniqueId = UUID.fromString(row[Users.id].value),
                    name = row[Users.name],
                    countryId = row[Users.countryId]?.value?.let { UUID.fromString(it) },
                    rank = row[Users.rank],
                    title = row[Users.title],
                    profession = row[Users.profession],
                    professionLevel = row[Users.professionLevel],
                    claimMode = row[Users.claimMode]
                )
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
            it[Users.claimMode] = user.claimMode
        }
        user
    }

    fun Player.user(): User {
        return getUser(uniqueId) ?: createUser(uniqueId, name)
    }
}