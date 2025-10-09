package cn.lcofficial.guozhan.config

import cn.lcofficial.guozhan.Guozhan
import org.bukkit.Material

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

    internal object Country : StaticLazy {
        var maxNameLength by int("country.max-name-length", 12)
        var minNameLength by int("country.min-name-length", 3)
        var coreHealthMax by int("country.core-health-max", 1000)
        var coreRegenInterval by int("country.core-regen-interval", 60)
        var coreRegenAmount by int("country.core-regen-amount", 1)

        object Creation : StaticLazy {
            object ItemCost : StaticLazy {
                private var materialString by string("country.creation.item_cost.material", "IRON_INGOT")
                var amount by int("country.creation.item_cost.amount", 64)

                val material: Material
                    get() = try {
                        Material.valueOf(materialString.uppercase())
                    } catch (e: IllegalArgumentException) {
                        Guozhan.instance.logger.warning("无效的物品类型配置: $materialString，使用默认值 IRON_INGOT")
                        Material.IRON_INGOT
                    }
            }
        }
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

    internal object Technology : StaticLazy {
        var researchDuration by int("technology.research_duration", 0)
        var enableProgressNotifications by bool("technology.enable_progress_notifications", true)
        var progressNotificationInterval by int("technology.progress_notification_interval", 3600)
    }

    override fun init(plugin: Guozhan) {
        Database.init()
        World.init()
        Country.init()
        Country.Creation.init()
        Country.Creation.ItemCost.init()
        BossBar.init()
        RandomSpawn.init()
        Technology.init()
        super.init(plugin)
    }
}