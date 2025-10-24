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
            var allowSiegeBuilding by bool("country.core-protection.allow-siege-building", true)
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

    internal object TestEnvironment : StaticLazy {
        var enabled by bool("test-environment.enabled", false)
        var autoGivePlayerResources by bool("test-environment.auto-give-player-resources", true)
        var autoGiveCountryResources by bool("test-environment.auto-give-country-resources", true)

        object PlayerResources : StaticLazy {
            // 🔧 v1.3.32: 修复测试环境资源配置过量问题 - 调整为合理数量
            var gold by int("test-environment.player-resources.gold", 100)
            var diamond by int("test-environment.player-resources.diamond", 50)
            var ironIngot by int("test-environment.player-resources.iron-ingot", 128)
            var emerald by int("test-environment.player-resources.emerald", 32)
            var dirt by int("test-environment.player-resources.dirt", 64) // 🔧 v1.3.34: 添加泥土方块
        }

        object CountryResources : StaticLazy {
            // 🔧 v1.3.32: 修复测试环境资源配置过量问题 - 调整为合理数量
            var gold by int("test-environment.country-resources.gold", 5000)
            var diamond by int("test-environment.country-resources.diamond", 500)
            var economyPoints by int("test-environment.country-resources.economy-points", 1000)
        }
    }

    internal object Debug : StaticLazy {
        object DataVisualization : StaticLazy {
            var enabled by bool("debug.data-visualization.enabled", false)
        }
    }

    internal object Tax : StaticLazy {
        // 🔧 v1.3.31: 修复税收区域调整被绕过的问题
        // 从配置文件读取税收区域设置，而不是使用硬编码值
        var baseRate by double("tax.base-rate", 0.1)
        var maxRate by double("tax.max-rate", 0.5)
        var collectionInterval by int("tax.collection-interval", 3600)

        /**
         * 税收区域数据类
         */
        data class TaxRegionConfig(
            val name: String,
            val range: Int,
            val goldRate: Double,
            val diamondRate: Double
        )

        /**
         * 从配置文件加载税收区域设置
         */
        fun loadTaxRegions(): List<TaxRegionConfig> {
            val regions = mutableListOf<TaxRegionConfig>()

            try {
                val config = Guozhan.instance.config
                val taxSection = config.getConfigurationSection("tax.regions")

                if (taxSection != null) {
                    for (regionName in taxSection.getKeys(false)) {
                        val regionSection = taxSection.getConfigurationSection(regionName)
                        if (regionSection != null) {
                            val range = regionSection.getInt("range", 20000)
                            val goldRate = regionSection.getDouble("gold-rate", 0.004)
                            val diamondRate = regionSection.getDouble("diamond-rate", 0.002)

                            regions.add(TaxRegionConfig(regionName, range, goldRate, diamondRate))
                        }
                    }
                }

                // 按范围从小到大排序
                regions.sortBy { it.range }

                if (regions.isEmpty()) {
                    // 如果配置为空，使用默认值
                    Guozhan.instance.logger.warning("税收区域配置为空，使用默认值")
                    return getDefaultTaxRegions()
                }

                Guozhan.instance.logger.info("已加载 ${regions.size} 个税收区域配置")
                return regions

            } catch (e: Exception) {
                Guozhan.instance.logger.warning("加载税收区域配置失败: ${e.message}，使用默认值")
                return getDefaultTaxRegions()
            }
        }

        /**
         * 获取默认的税收区域配置
         */
        private fun getDefaultTaxRegions(): List<TaxRegionConfig> {
            return listOf(
                TaxRegionConfig("核心疆域", 1300, 0.024, 0.024),
                TaxRegionConfig("内陆邦畿", 2500, 0.020, 0.016),
                TaxRegionConfig("开拓边疆", 5000, 0.016, 0.012),
                TaxRegionConfig("纷争之地", 9000, 0.012, 0.008),
                TaxRegionConfig("远征前线", 14000, 0.008, 0.004),
                TaxRegionConfig("失落蛮荒", 20000, 0.004, 0.002)
            )
        }
    }

    internal object War : StaticLazy {
        // 🔧 v1.3.31: 修复战争调度配置被忽略的问题
        // 从配置文件读取战争时间设置，而不是使用硬编码值
        var day by int("war.day", 6) // 周六 (1-7)
        var prepareHour by int("war.prepare-hour", 19)
        var prepareMinute by int("war.prepare-minute", 0)
        var startHour by int("war.start-hour", 19)
        var startMinute by int("war.start-minute", 20)
        var endHour by int("war.end-hour", 22)
        var endMinute by int("war.end-minute", 0)
        var coreTerritoryRange by intList("war.core-territory-range", listOf(-64, 63))
        var warTerritoryRange by intList("war.war-territory-range", listOf(-128, 127))

        // 额外的战争配置
        var damageMultiplier by double("war.damage-multiplier", 1.5)
        var killReward by int("war.kill-reward", 10)

        /**
         * 验证战争时间配置的合理性
         */
        fun validateTimeSettings(): Boolean {
            if (day !in 1..7) {
                Guozhan.instance.logger.warning("战争日期配置无效: $day，应在 1-7 之间")
                return false
            }
            if (prepareHour !in 0..23 || startHour !in 0..23 || endHour !in 0..23) {
                Guozhan.instance.logger.warning("战争小时配置无效，应在 0-23 之间")
                return false
            }
            if (prepareMinute !in 0..59 || startMinute !in 0..59 || endMinute !in 0..59) {
                Guozhan.instance.logger.warning("战争分钟配置无效，应在 0-59 之间")
                return false
            }
            return true
        }
    }

    internal object Territory : StaticLazy {
        var claimCost by int("territory.claim-cost", 3)
        var maxClaims by int("territory.max-claims", 100)
        var loyaltyDecayRate by double("territory.loyalty-decay-rate", 0.1)
        var loyaltyDecayInterval by int("territory.loyalty-decay-interval", 3600)
        var adjacencyRequired by bool("territory.adjacency-required", true)
    }

    override fun init(plugin: Guozhan) {
        // 🔧 v1.3.33: 先加载配置文件，再初始化配置对象
        super.init(plugin)

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
        Debug.init()
        Debug.DataVisualization.init()
        Tax.init()
        War.init()
        Territory.init()
        TestEnvironment.init()
        TestEnvironment.PlayerResources.init()
        TestEnvironment.CountryResources.init()

        // 🔧 v1.3.33: 添加测试环境配置加载日志
        plugin.logger.info("[测试环境] 调试: 开始检查配置值")
        plugin.logger.info("[测试环境] 调试: TestEnvironment.enabled = ${TestEnvironment.enabled}")
        plugin.logger.info("[测试环境] 调试: 配置文件路径 = ${plugin.dataFolder}/config.yml")

        if (TestEnvironment.enabled) {
            plugin.logger.info("[测试环境] 测试环境已启用")
            plugin.logger.info("[测试环境] 玩家资源自动发放: ${TestEnvironment.autoGivePlayerResources}")
            plugin.logger.info("[测试环境] 国家资源自动发放: ${TestEnvironment.autoGiveCountryResources}")
            plugin.logger.info("[测试环境] 玩家资源配置: 金币=${TestEnvironment.PlayerResources.gold}, 钻石=${TestEnvironment.PlayerResources.diamond}, 铁锭=${TestEnvironment.PlayerResources.ironIngot}, 绿宝石=${TestEnvironment.PlayerResources.emerald}, 泥土=${TestEnvironment.PlayerResources.dirt}")
            plugin.logger.info("[测试环境] 国家资源配置: 金币=${TestEnvironment.CountryResources.gold}, 钻石=${TestEnvironment.CountryResources.diamond}, 经济点数=${TestEnvironment.CountryResources.economyPoints}")
        } else {
            plugin.logger.info("[测试环境] 测试环境已禁用")
        }
    }
}
