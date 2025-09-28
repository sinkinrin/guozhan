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

    internal object BossBar : StaticLazy {
        var enabled by bool("bossbar.enabled", true)
        var displayRange by int("bossbar.display-range", 100)
        var updateInterval by long("bossbar.update-interval", 20L)
        var titleFormat by string("bossbar.title-format", "{country} 核心血量")
        var showToAttackerCountry by bool("bossbar.show-to-attacker-country", true)
        var showToDefenderCountry by bool("bossbar.show-to-defender-country", true)
        var cacheDuration by long("bossbar.cache-duration", 30L)
    }

    internal object RandomSpawn : StaticLazy {
        var enabled by bool("random-spawn.enabled", true)
        var spawnRadius by int("random-spawn.spawn-radius", 5000)
        var maxAttempts by int("random-spawn.max-attempts", 50)
        var safetyCheckRadius by int("random-spawn.safety-check-radius", 3)
        var minDistanceFromSpawn by int("random-spawn.min-distance-from-spawn", 1000)
        var allowedWorlds by stringList("random-spawn.allowed-worlds", listOf("world"))
        var unsafeBlocks by stringList("random-spawn.unsafe-blocks", listOf("LAVA", "WATER", "FIRE"))
        var minYLevel by int("random-spawn.min-y-level", 60)
        var maxYLevel by int("random-spawn.max-y-level", 120)
    }

    override fun init(plugin: Guozhan) {
        Database.init()
        World.init()
        BossBar.init()
        RandomSpawn.init()
        super.init(plugin)
    }
}