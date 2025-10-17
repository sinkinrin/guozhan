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
        var coreHealthInitial by int("country.core-health-initial", 50)
        var coreRegenInterval by int("country.core-regen-interval", 60)
        var coreRegenAmount by int("country.core-regen-amount", 1)

        internal object CoreProtection : StaticLazy {
            var offlineProtection by bool("country.core-protection.offline-protection", true)
            var minOnlineMembers by int("country.core-protection.min-online-members", 1)
            var requireWarDeclaration by bool("country.core-protection.require-war-declaration", true)
            var offlineAttackDamageReduction by double("country.core-protection.offline-attack-damage-reduction", 0.5)
        }

        object Creation : StaticLazy {
            var minDistanceBetweenCountries by int("country.creation.min-distance-between-countries", 50)

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

    internal object Shield : StaticLazy {
        var costPerHour by int("shield.cost-per-hour", 5)
        var cooldownMinutes by int("shield.cooldown-minutes", 30)
        var maxDurationHours by int("shield.max-duration-hours", 24)
        var minDurationHours by int("shield.min-duration-hours", 1)
        var maxAspectRatio by double("shield.max-aspect-ratio", 2.0)
        var diamondToGoldRate by int("shield.diamond-to-gold-rate", 10)
        var maxMembers by int("shield.max-members", 50) // 🔧 修复：从配置读取成员上限
    }

    internal object Profession : StaticLazy {
        var unlockDelayHours by int("profession.unlock-delay-hours", 2)
        var upgradeDelayHours by int("profession.upgrade-delay-hours", 24)
        var upgradeCost by int("profession.upgrade-cost", 50)
    }

    internal object Tax : StaticLazy {
        var baseRate by double("tax.base-rate", 0.1)
        var maxRate by double("tax.max-rate", 0.5)
        var collectionInterval by int("tax.collection-interval", 3600)
        var regions by stringList("tax.regions", listOf("spawn", "inner", "middle", "outer", "border", "wilderness"))
    }

    internal object War : StaticLazy {
        var startTime by string("war.start-time", "19:20")
        var endTime by string("war.end-time", "22:00")
        var dayOfWeek by int("war.day-of-week", 6) // 6 = Saturday
        var preparationMinutes by int("war.preparation-minutes", 20)
        var damageMultiplier by double("war.damage-multiplier", 1.5)
        var killReward by int("war.kill-reward", 10)
    }

    internal object Territory : StaticLazy {
        var claimCost by int("territory.claim-cost", 3)
        var maxClaims by int("territory.max-claims", 100)
        var loyaltyDecayRate by double("territory.loyalty-decay-rate", 0.1)
        var loyaltyDecayInterval by int("territory.loyalty-decay-interval", 3600)
        var adjacencyRequired by bool("territory.adjacency-required", true)
    }

    override fun init(plugin: Guozhan) {
        Database.init()
        World.init()
        Country.init()
        Country.Creation.init()
        Country.Creation.ItemCost.init()
        Country.CoreProtection.init()
        BossBar.init()
        RandomSpawn.init()
        Technology.init()
        Shield.init()
        Profession.init()
        Tax.init()
        War.init()
        Territory.init()
        super.init(plugin)
    }
}