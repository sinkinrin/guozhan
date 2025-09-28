package cn.lcofficial.guozhan.config

import cn.lcofficial.guozhan.Guozhan

object Config : Configuration("config.yml") {
    internal object Database : StaticLazy {
        enum class Type {
            SQLITE, MYSQL
        }
        var type by enum("database.type", Type.SQLITE)
        var host by string("database.host", "127.0.0.1")
        var port by int("database.port", 3306)
        var username by string("database.username", "minecraft")
        var password by string("database.password", "minecraft")
        var database by string("database.database", "minecraft")
    }

    internal object World : StaticLazy {
        var spawnRadius by int("world.spawnRadius", 100)
    }

    override fun init(plugin: Guozhan) {
        Database.init()
        World.init()
        super.init(plugin)
    }
}