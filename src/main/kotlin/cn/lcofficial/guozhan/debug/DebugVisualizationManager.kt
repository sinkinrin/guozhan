package cn.lcofficial.guozhan.debug

import cn.lcofficial.guozhan.config.Config
import cn.lcofficial.guozhan.economy.RegionalTaxSystem
import cn.lcofficial.guozhan.economy.TaxSystem
import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.manager.DataManager
import cn.lcofficial.guozhan.manager.EconomyManager
import cn.lcofficial.guozhan.manager.GMLogger
import cn.lcofficial.guozhan.manager.ProfessionManager
import cn.lcofficial.guozhan.manager.ShieldManager
import cn.lcofficial.guozhan.manager.TechnologyManager
import cn.lcofficial.guozhan.manager.TerritoryManager
import cn.lcofficial.guozhan.manager.UserManager
import cn.lcofficial.guozhan.manager.UserManager.user
import cn.lcofficial.guozhan.manager.WarManager
import cn.lcofficial.guozhan.manager.WarScoreBossBarManager
import cn.lcofficial.guozhan.pluginLogger
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.math.max

object DebugVisualizationManager {

    enum class ResultStatus {
        SUCCESS,
        PLANNED,
        ERROR
    }

    enum class ImplementationStatus {
        READY,
        PLANNED
    }

    data class DebugVisualizationRequest(
        val sender: CommandSender,
        val args: List<String> = emptyList(),
        val rawCategoryToken: String = ""
    )

    data class DebugVisualizationFrame(
        val title: String,
        val lines: List<String>,
        val warnings: List<String> = emptyList()
    )

    data class DebugVisualizationResult(
        val status: ResultStatus,
        val lines: List<String>,
        val warnings: List<String> = emptyList(),
        val durationMillis: Long = 0
    )

    data class VisualizationDefinition(
        val key: String,
        val title: String,
        val description: String,
        val aliases: Set<String> = emptySet(),
        val status: ImplementationStatus = ImplementationStatus.PLANNED,
        val requiresArgument: Boolean = false,
        val argumentHint: String? = null,
        val handler: ((DebugVisualizationRequest) -> CompletableFuture<DebugVisualizationFrame>)? = null
    )

    private val definitions = listOf(
        VisualizationDefinition(
            key = "country-overview",
            title = "国家系统数据 - 总览",
            description = "汇总国家数量、成员、领土与国库资源，便于快速体检整体状态。",
            aliases = setOf("countries", "country"),
            status = ImplementationStatus.READY,
            handler = ::handleCountryOverview
        ),
        VisualizationDefinition(
            key = "country-detail",
            title = "国家系统数据 - 详情",
            description = "查看指定国家的核心位置、护盾、税率、忠诚度等详细数据。",
            aliases = setOf("country-info", "cinfo"),
            status = ImplementationStatus.PLANNED,
            requiresArgument = true,
            argumentHint = "<countryId|name>"
        ),
        VisualizationDefinition(
            key = "country-relations",
            title = "国家系统数据 - 关系图",
            description = "展示外交关系网、战争状态与联盟结构。",
            aliases = setOf("relations", "diplomacy"),
            status = ImplementationStatus.PLANNED
        ),
        VisualizationDefinition(
            key = "territory-overview",
            title = "领土系统数据 - 分布统计",
            description = "统计各国领土、荒野领土与世界分布情况。",
            aliases = setOf("territories", "territory"),
            status = ImplementationStatus.READY,
            handler = ::handleTerritoryOverview
        ),
        VisualizationDefinition(
            key = "territory-detail",
            title = "领土系统数据 - 详情",
            description = "查看指定领土的所有者、类型、资源与占领进度。",
            aliases = setOf("territory-info", "tinfo"),
            status = ImplementationStatus.PLANNED,
            requiresArgument = true,
            argumentHint = "<x,z|id>"
        ),
        VisualizationDefinition(
            key = "territory-claiming",
            title = "领土系统数据 - 占领进度",
            description = "列出正在占领的领土、进度百分比与剩余时间。",
            aliases = setOf("territory-progress"),
            status = ImplementationStatus.PLANNED
        ),
        VisualizationDefinition(
            key = "player-online",
            title = "玩家系统数据 - 在线概览",
            description = "列出在线玩家的国家、职业、忠诚度与活跃增益。",
            aliases = setOf("players", "online"),
            status = ImplementationStatus.READY,
            handler = ::handlePlayerOnline
        ),
        VisualizationDefinition(
            key = "player-detail",
            title = "玩家系统数据 - 详情",
            description = "查询指定玩家的职业等级、科技增益与药水效果。",
            aliases = setOf("player-info", "pinfo"),
            status = ImplementationStatus.PLANNED,
            requiresArgument = true,
            argumentHint = "<player|uuid>"
        ),
        VisualizationDefinition(
            key = "player-offline",
            title = "玩家系统数据 - 离线查询",
            description = "读取离线玩家的缓存数据，验证持久化状态。",
            aliases = setOf("offline-player"),
            status = ImplementationStatus.PLANNED,
            requiresArgument = true,
            argumentHint = "<player|uuid>"
        ),
        VisualizationDefinition(
            key = "economy-tax",
            title = "经济系统数据 - 税收状态",
            description = "展示各国税率、收税时间与累计税收。",
            aliases = setOf("tax", "economy-tax"),
            status = ImplementationStatus.READY,
            handler = ::handleEconomyTax
        ),
        VisualizationDefinition(
            key = "economy-treasury",
            title = "经济系统数据 - 国库资源",
            description = "统计各国铁锭、钻石、绿宝石与贡品记录。",
            aliases = setOf("treasury", "economy"),
            status = ImplementationStatus.READY,
            handler = ::handleEconomyTreasury
        ),
        VisualizationDefinition(
            key = "war-status",
            title = "战争系统数据 - 实时状态",
            description = "查看战争开关、参战国家与实时积分榜。",
            aliases = setOf("war", "war-overview"),
            status = ImplementationStatus.READY,
            handler = ::handleWarStatus
        ),
        VisualizationDefinition(
            key = "war-history",
            title = "战争系统数据 - 历史记录",
            description = "查询历史战争列表与核心疆域统计。",
            aliases = setOf("war-log"),
            status = ImplementationStatus.PLANNED
        ),
        VisualizationDefinition(
            key = "technology-progress",
            title = "科技系统数据 - 研究进度",
            description = "展示各国当前研究、进度百分比与剩余时间。",
            aliases = setOf("tech-progress"),
            status = ImplementationStatus.READY,
            handler = ::handleTechnologyProgress
        ),
        VisualizationDefinition(
            key = "technology-effect",
            title = "科技系统数据 - 效果应用",
            description = "列出玩家所拥有的科技增益与生效范围。",
            aliases = setOf("tech-effect"),
            status = ImplementationStatus.PLANNED
        ),
        VisualizationDefinition(
            key = "profession-overview",
            title = "职业系统数据 - 分布统计",
            description = "统计职业人数、升级冷却与职业效果状态。",
            aliases = setOf("professions"),
            status = ImplementationStatus.READY,
            handler = ::handleProfessionOverview
        ),
        VisualizationDefinition(
            key = "shield-status",
            title = "护盾系统数据 - 状态一览",
            description = "列出正在激活护盾的国家、剩余时间与成员。",
            aliases = setOf("shield"),
            status = ImplementationStatus.READY,
            handler = ::handleShieldStatus
        ),
        VisualizationDefinition(
            key = "runtime-state",
            title = "运行时数据 - 数据库与缓存",
            description = "查看异步任务队列、缓存大小以及连接池状态。",
            aliases = setOf("runtime", "cache"),
            status = ImplementationStatus.READY,
            handler = ::handleRuntimeState
        ),
        VisualizationDefinition(
            key = "scheduler-state",
            title = "调度器与任务状态",
            description = "列出活跃调度器、下次执行时间与搜索任务状态。",
            aliases = setOf("scheduler", "tasks"),
            status = ImplementationStatus.PLANNED
        )
    )

    private val definitionLookup = buildMap {
        definitions.forEach { definition ->
            put(definition.key.lowercase(), definition)
            definition.aliases.forEach { alias ->
                put(alias.lowercase(), definition)
            }
        }
    }

    fun isEnabled(): Boolean = Config.Debug.DataVisualization.enabled

    fun listDefinitions(): List<VisualizationDefinition> = definitions

    fun resolveDefinition(token: String?): VisualizationDefinition? {
        if (token.isNullOrBlank()) return null
        return definitionLookup[token.lowercase()]
    }

    fun buildCategoryList(): List<String> {
        val lines = mutableListOf<String>()
        lines += DebugVisualizationFormatter.header("数据可视化类别列表")
        definitions.forEachIndexed { index, definition ->
            val statusText = when (definition.status) {
                ImplementationStatus.READY -> "§a可用"
                ImplementationStatus.PLANNED -> "§e规划中"
            }
            val aliases = if (definition.aliases.isEmpty()) {
                "§7无别名"
            } else {
                "§7别名: §f" + definition.aliases.joinToString(", ")
            }
            val requirements = if (definition.requiresArgument) {
                val hint = definition.argumentHint ?: "<参数>"
                "§c需参数 $hint"
            } else {
                "§7无需额外参数"
            }

            lines += DebugVisualizationFormatter.bulletLine(index + 1, definition.key, definition.title)
            lines += "  §7状态: $statusText"
            lines += "  §7说明: §f${definition.description}"
            lines += "  $aliases"
            lines += "  $requirements"
        }
        lines += DebugVisualizationFormatter.footer()
        return lines
    }

    fun execute(definition: VisualizationDefinition, request: DebugVisualizationRequest): CompletableFuture<DebugVisualizationResult> {
        val handler = definition.handler
        if (definition.status != ImplementationStatus.READY || handler == null) {
            val frame = DebugVisualizationFrame(
                title = definition.title,
                lines = listOf(DebugVisualizationFormatter.warn("该类别的可视化仍在规划中。")),
                warnings = emptyList()
            )
            val result = frame.toResult(ResultStatus.PLANNED, durationMillis = 0)
            logUsage(definition, request, result)
            return CompletableFuture.completedFuture(result)
        }

        val startedAt = System.currentTimeMillis()
        return handler.invoke(request).handle { frame, throwable ->
            val duration = System.currentTimeMillis() - startedAt
            val result = if (throwable != null) {
                pluginLogger.severe("[DebugVisualization] 执行 ${definition.key} 失败: ${throwable.message}")
                throwable.printStackTrace()
                DebugVisualizationResult(
                    status = ResultStatus.ERROR,
                    lines = buildErrorLines(definition, throwable),
                    durationMillis = duration
                )
            } else {
                frame.toResult(ResultStatus.SUCCESS, duration)
            }
            logUsage(definition, request, result)
            result
        }
    }

    private fun DebugVisualizationFrame.toResult(status: ResultStatus, durationMillis: Long): DebugVisualizationResult {
        val composed = mutableListOf<String>()
        composed += DebugVisualizationFormatter.header(title)
        composed += lines
        composed += "§8处理耗时: §f${durationMillis}ms"
        composed += DebugVisualizationFormatter.footer()
        return DebugVisualizationResult(
            status = status,
            lines = composed,
            warnings = warnings,
            durationMillis = durationMillis
        )
    }

    private fun buildErrorLines(definition: VisualizationDefinition, throwable: Throwable): List<String> {
        return buildList {
            add(DebugVisualizationFormatter.header(definition.title))
            add(DebugVisualizationFormatter.error("可视化处理失败: ${throwable.message ?: throwable::class.java.simpleName}"))
            add(DebugVisualizationFormatter.footer())
        }
    }

    private fun logUsage(definition: VisualizationDefinition, request: DebugVisualizationRequest, result: DebugVisualizationResult) {
        val argsJoined = if (request.args.isEmpty()) "无" else request.args.joinToString(" ")
        val details = mapOf(
            "category" to definition.key,
            "status" to result.status.name,
            "durationMs" to result.durationMillis,
            "args" to argsJoined
        )
        GMLogger.logGMAction(
            operator = request.sender,
            action = "DEBUG_VISUALIZATION",
            target = definition.key,
            details = details
        )
    }

    private fun handleCountryOverview(request: DebugVisualizationRequest): CompletableFuture<DebugVisualizationFrame> {
        val now = System.currentTimeMillis()
        val countries = CountryManager.countries.values.map { country ->
            CountrySnapshot(
                id = country.id,
                name = country.name,
                gold = country.gold,
                diamond = country.diamond,
                economyPoints = country.economyPoints,
                coreHealth = country.coreHealth,
                shield = country.shield,
                shieldRemaining = if (country.shield && country.shieldEndTime != null) {
                    max(0L, country.shieldEndTime!! - now)
                } else {
                    0L
                },
                mapColor = country.mapColor
            )
        }
        val memberCounts = countries.associate { it.id to CountryManager.getCountryMembers(it.id).size }
        val territorySnapshot = TerritoryManager.territories.values.map { block ->
            TerritorySnapshot(ownerId = block.owner?.id, world = block.world)
        }
        val totalUsers = UserManager.users.size

        return CompletableFuture.supplyAsync {
            val territoryCountByCountry = territorySnapshot.groupingBy { it.ownerId }.eachCount()
            val totalTerritories = territorySnapshot.size
            val lines = mutableListOf<String>()
            lines += DebugVisualizationFormatter.summaryLine("§e总国家数", "§f${countries.size}")
            lines += DebugVisualizationFormatter.summaryLine("§e总领土数", "§f$totalTerritories")
            lines += DebugVisualizationFormatter.summaryLine("§e总玩家数", "§f$totalUsers")
            lines += ""
            lines += DebugVisualizationFormatter.section("国家列表 (最多显示50条)")

            val sorted = countries.sortedWith(
                compareByDescending<CountrySnapshot> { territoryCountByCountry[it.id] ?: 0 }
                    .thenByDescending { memberCounts[it.id] ?: 0 }
                    .thenBy { it.name }
            )

            val warnings = mutableListOf<String>()
            if (sorted.size > 50) {
                warnings += "仅展示前50个国家，剩余 ${sorted.size - 50} 个已被省略。"
            }

            sorted.take(50).forEachIndexed { index, snapshot ->
                val territoryCount = territoryCountByCountry[snapshot.id] ?: 0
                val members = memberCounts[snapshot.id] ?: 0
                lines += DebugVisualizationFormatter.bulletLine(index + 1, snapshot.name, DebugVisualizationFormatter.maskUuid(snapshot.id))
                lines += DebugVisualizationFormatter.detailLine("   成员", "§f$members 人")
                lines += DebugVisualizationFormatter.detailLine("   领土", "§f$territoryCount 块")
                lines += DebugVisualizationFormatter.detailLine(
                    "   国库",
                    "§f铁锭×${snapshot.gold}, 钻石×${snapshot.diamond}, 经济点数×${snapshot.economyPoints}"
                )
                val shieldStatus = if (snapshot.shield) {
                    "§a激活中 (${formatDuration(snapshot.shieldRemaining)})"
                } else {
                    "§c未激活"
                }
                val colorHex = if (snapshot.mapColor != 0) "§f#${snapshot.mapColor.toString(16).padStart(6, '0')}" else "§7未配置"
                lines += DebugVisualizationFormatter.detailLine("   护盾", shieldStatus)
                lines += DebugVisualizationFormatter.detailLine("   Core 生命值", "§f${snapshot.coreHealth}")
                lines += DebugVisualizationFormatter.detailLine("   地图颜色", colorHex)
            }

            DebugVisualizationFrame(
                title = "国家系统数据 - 总览",
                lines = lines,
                warnings = warnings
            )
        }
    }

    private fun handleTerritoryOverview(request: DebugVisualizationRequest): CompletableFuture<DebugVisualizationFrame> {
        val territorySnapshot = TerritoryManager.territories.values.map { block ->
            TerritorySnapshot(ownerId = block.owner?.id, world = block.world)
        }
        val countries = CountryManager.countries.values.associateBy { it.id }

        return CompletableFuture.supplyAsync {
            val total = territorySnapshot.size
            val wild = territorySnapshot.count { it.ownerId == null }
            val byCountry = territorySnapshot.groupingBy { it.ownerId }.eachCount()
            val byWorld = territorySnapshot.groupingBy { it.world }.eachCount()

            val lines = mutableListOf<String>()
            lines += DebugVisualizationFormatter.summaryLine("§e总领土数", "§f$total")
            lines += DebugVisualizationFormatter.summaryLine("§e荒野领土", "§f$wild")
            lines += ""
            lines += DebugVisualizationFormatter.section("按国家统计 (最多显示50条)")

            val countryEntries = byCountry
                .filterKeys { it != null }
                .map { (countryId, count) ->
                    val country = countries[countryId]
                    CountryTerritorySummary(countryId!!, country?.name ?: "未知国家", count)
                }
                .sortedByDescending { it.count }

            val warnings = mutableListOf<String>()
            if (countryEntries.size > 50) {
                warnings += "国家领土统计仅展示前50条，剩余 ${countryEntries.size - 50} 条已省略。"
            }

            countryEntries.take(50).forEachIndexed { index, summary ->
                lines += DebugVisualizationFormatter.bulletLine(index + 1, summary.name, DebugVisualizationFormatter.maskUuid(summary.id))
                lines += DebugVisualizationFormatter.detailLine("   领土", "§f${summary.count} 块")
            }

            lines += ""
            lines += DebugVisualizationFormatter.section("按世界统计")
            byWorld.entries.sortedByDescending { it.value }.forEach { (world, count) ->
                lines += DebugVisualizationFormatter.detailLine("   $world", "§f$count 块")
            }

            DebugVisualizationFrame(
                title = "领土系统数据 - 分布统计",
                lines = lines,
                warnings = warnings
            )
        }
    }

    private fun handlePlayerOnline(request: DebugVisualizationRequest): CompletableFuture<DebugVisualizationFrame> {
        val onlinePlayers = Bukkit.getOnlinePlayers().sortedBy { it.name }
        val warnings = mutableListOf<String>()
        if (onlinePlayers.size > 50) {
            warnings += "在线玩家超过50人，仅展示前50条。"
        }

        val lines = mutableListOf<String>()
        lines += DebugVisualizationFormatter.summaryLine("§e在线玩家", "§f${onlinePlayers.size}")
        lines += ""
        lines += DebugVisualizationFormatter.section("玩家列表")

        onlinePlayers.take(50).forEachIndexed { index, player ->
        val user = player.user()
            val countryName = user.country?.name ?: "§7无"
            val profession = user.profession?.name ?: "§7未设置"
            val professionInfo = if (user.profession != null) {
                "$profession §fLv.${user.professionLevel}"
            } else {
                profession
            }
            lines += DebugVisualizationFormatter.bulletLine(index + 1, player.name, DebugVisualizationFormatter.maskUuid(player.uniqueId))
            lines += DebugVisualizationFormatter.detailLine("   国家", countryName)
            lines += DebugVisualizationFormatter.detailLine("   职业", professionInfo)
            lines += DebugVisualizationFormatter.detailLine("   Claim 模式", "§f${user.claimMode.name}")
        }

        val frame = DebugVisualizationFrame(
            title = "玩家系统数据 - 在线概览",
            lines = lines,
            warnings = warnings
        )
        return CompletableFuture.completedFuture(frame)
    }

    private fun formatDuration(millis: Long): String {
        if (millis <= 0) return "§7N/A"
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val parts = mutableListOf<String>()
        if (hours > 0) parts += "${hours}h"
        if (minutes > 0) parts += "${minutes}m"
        if (seconds > 0 && parts.isEmpty()) parts += "${seconds}s"
        return "§f" + parts.joinToString(" ")
    }

    private data class CountrySnapshot(
        val id: UUID,
        val name: String,
        val gold: Int,
        val diamond: Int,
        val economyPoints: Int,
        val coreHealth: Int,
        val shield: Boolean,
        val shieldRemaining: Long,
        val mapColor: Int
    )

    private data class TerritorySnapshot(
        val ownerId: UUID?,
        val world: String
    )

    private data class CountryTerritorySummary(
        val id: UUID,
        val name: String,
        val count: Int
    )

    // ========== 新增的可视化Handler ==========

    private fun handleEconomyTax(request: DebugVisualizationRequest): CompletableFuture<DebugVisualizationFrame> {
        // 🔧 修复问题3 (Medium): 在创建快照时就计算好canCollectTax状态，避免异步处理时NPE
        val countries = CountryManager.countries.values.map { country ->
            EconomyTaxSnapshot(
                id = country.id,
                name = country.name,
                taxRate = EconomyManager.getTaxRate(country),
                lastManualTaxTime = country.lastManualTaxTime,
                lastAutoTaxTime = country.lastAutoTaxTime,
                goldPerHour = RegionalTaxSystem.calculateTotalGoldTaxPerHour(country),
                diamondPerHour = RegionalTaxSystem.calculateTotalDiamondTaxPerHour(country),
                canCollectTax = EconomyManager.canCollectTax(country)  // 在快照时计算
            )
        }

        return CompletableFuture.supplyAsync {
            val lines = mutableListOf<String>()
            lines += DebugVisualizationFormatter.summaryLine("§e总国家数", "§f${countries.size}")
            lines += ""
            lines += DebugVisualizationFormatter.section("税收状态 (最多显示50条)")

            val sorted = countries.sortedWith(
                compareByDescending<EconomyTaxSnapshot> { it.goldPerHour + it.diamondPerHour * 10 }
                    .thenBy { it.name }
            )

            val warnings = mutableListOf<String>()
            if (sorted.size > 50) {
                warnings += "仅展示前50个国家，剩余 ${sorted.size - 50} 个已被省略。"
            }

            sorted.take(50).forEachIndexed { index, snapshot ->
                lines += DebugVisualizationFormatter.bulletLine(index + 1, snapshot.name, DebugVisualizationFormatter.maskUuid(snapshot.id))
                lines += DebugVisualizationFormatter.detailLine("   税率", "§f${snapshot.taxRate}%")
                lines += DebugVisualizationFormatter.detailLine("   每小时收入", "§f金币×${snapshot.goldPerHour}, 钻石×${snapshot.diamondPerHour}")

                // 使用快照数据，避免重新查询可能已被删除的国家
                val manualTaxStatus = if (snapshot.canCollectTax) {
                    "§a可收税"
                } else {
                    val remaining = EconomyManager.TAX_CYCLE - (System.currentTimeMillis() - snapshot.lastManualTaxTime)
                    "§c冷却中 (${formatDuration(remaining)})"
                }
                lines += DebugVisualizationFormatter.detailLine("   手动收税", manualTaxStatus)
                lines += DebugVisualizationFormatter.detailLine("   上次手动收税", DebugVisualizationFormatter.formatTimestamp(snapshot.lastManualTaxTime))
                lines += DebugVisualizationFormatter.detailLine("   上次自动收税", DebugVisualizationFormatter.formatTimestamp(snapshot.lastAutoTaxTime))
            }

            DebugVisualizationFrame(
                title = "经济系统数据 - 税收状态",
                lines = lines,
                warnings = warnings
            )
        }
    }

    private fun handleEconomyTreasury(request: DebugVisualizationRequest): CompletableFuture<DebugVisualizationFrame> {
        val countries = CountryManager.countries.values.map { country ->
            EconomyTreasurySnapshot(
                id = country.id,
                name = country.name,
                gold = country.gold,
                diamond = country.diamond,
                economyPoints = country.economyPoints
            )
        }

        return CompletableFuture.supplyAsync {
            val lines = mutableListOf<String>()
            val totalGold = countries.sumOf { it.gold }
            val totalDiamond = countries.sumOf { it.diamond }
            val totalEconomyPoints = countries.sumOf { it.economyPoints }

            lines += DebugVisualizationFormatter.summaryLine("§e总国家数", "§f${countries.size}")
            lines += DebugVisualizationFormatter.summaryLine("§e总金币", "§f$totalGold")
            lines += DebugVisualizationFormatter.summaryLine("§e总钻石", "§f$totalDiamond")
            lines += DebugVisualizationFormatter.summaryLine("§e总经济点数", "§f$totalEconomyPoints")
            lines += ""
            lines += DebugVisualizationFormatter.section("国库资源 (最多显示50条)")

            val sorted = countries.sortedWith(
                compareByDescending<EconomyTreasurySnapshot> { it.gold + it.diamond * 10 + it.economyPoints }
                    .thenBy { it.name }
            )

            val warnings = mutableListOf<String>()
            if (sorted.size > 50) {
                warnings += "仅展示前50个国家，剩余 ${sorted.size - 50} 个已被省略。"
            }

            sorted.take(50).forEachIndexed { index, snapshot ->
                lines += DebugVisualizationFormatter.bulletLine(index + 1, snapshot.name, DebugVisualizationFormatter.maskUuid(snapshot.id))
                lines += DebugVisualizationFormatter.detailLine("   金币", "§f${snapshot.gold}")
                lines += DebugVisualizationFormatter.detailLine("   钻石", "§f${snapshot.diamond}")
                lines += DebugVisualizationFormatter.detailLine("   经济点数", "§f${snapshot.economyPoints}")
            }

            DebugVisualizationFrame(
                title = "经济系统数据 - 国库资源",
                lines = lines,
                warnings = warnings
            )
        }
    }

    private data class EconomyTaxSnapshot(
        val id: UUID,
        val name: String,
        val taxRate: Int,
        val lastManualTaxTime: Long,
        val lastAutoTaxTime: Long,
        val goldPerHour: Double,
        val diamondPerHour: Double,
        val canCollectTax: Boolean  // 🔧 修复问题3 (Medium): 在快照时就计算好canCollectTax状态，避免异步处理时NPE
    )

    private data class EconomyTreasurySnapshot(
        val id: UUID,
        val name: String,
        val gold: Int,
        val diamond: Int,
        val economyPoints: Int
    )

    private fun handleWarStatus(request: DebugVisualizationRequest): CompletableFuture<DebugVisualizationFrame> {
        val activeWars = WarManager.getAllActiveWars()

        return CompletableFuture.supplyAsync {
            val lines = mutableListOf<String>()
            lines += DebugVisualizationFormatter.summaryLine("§e活跃战争数", "§f${activeWars.size}")
            lines += ""

            if (activeWars.isEmpty()) {
                lines += DebugVisualizationFormatter.section("当前无活跃战争")
            } else {
                lines += DebugVisualizationFormatter.section("活跃战争列表")

                activeWars.entries.forEachIndexed { index, (warId, pair) ->
                    val (country1, country2) = pair
                    val duration = WarManager.getWarDuration(country1, country2)
                    val scoreResult = WarScoreBossBarManager.getWarScoreResult(country1, country2)

                    lines += DebugVisualizationFormatter.bulletLine(index + 1, "${country1.name} vs ${country2.name}", warId)
                    lines += DebugVisualizationFormatter.detailLine("   持续时间", formatDuration(duration))

                    if (scoreResult != null) {
                        lines += DebugVisualizationFormatter.detailLine("   积分", "§f${scoreResult.score1} : ${scoreResult.score2}")
                        val leader = when {
                            scoreResult.score1 > scoreResult.score2 -> "§a${country1.name} 领先"
                            scoreResult.score2 > scoreResult.score1 -> "§a${country2.name} 领先"
                            else -> "§e平局"
                        }
                        lines += DebugVisualizationFormatter.detailLine("   状态", leader)
                    } else {
                        lines += DebugVisualizationFormatter.detailLine("   积分", "§7未启用积分系统")
                    }
                }
            }

            DebugVisualizationFrame(
                title = "战争系统数据 - 实时状态",
                lines = lines,
                warnings = emptyList()
            )
        }
    }

    private fun handleTechnologyProgress(request: DebugVisualizationRequest): CompletableFuture<DebugVisualizationFrame> {
        val countries = CountryManager.countries.values.toList()

        return CompletableFuture.supplyAsync {
            val lines = mutableListOf<String>()
            var totalResearching = 0
            val researchingCountries = mutableListOf<TechnologyProgressSnapshot>()

            for (country in countries) {
                val researchingTechs = TechnologyManager.getResearchingTechnologies(country)
                if (researchingTechs.isNotEmpty()) {
                    totalResearching += researchingTechs.size
                    researchingCountries.add(TechnologyProgressSnapshot(
                        countryId = country.id,
                        countryName = country.name,
                        researchingTechs = researchingTechs.mapNotNull { techId ->
                            val countryTech = TechnologyManager.getCountryTechnology(country, techId)
                            val technology = TechnologyManager.getTechnology(techId)
                            if (countryTech != null && technology != null) {
                                TechResearchInfo(
                                    techId = techId,
                                    techName = technology.name,
                                    startTime = countryTech.researchStartTime ?: 0L,
                                    endTime = countryTech.researchEndTime ?: 0L,
                                    currentLevel = countryTech.level,
                                    targetLevel = countryTech.level + 1
                                )
                            } else null
                        }
                    ))
                }
            }

            lines += DebugVisualizationFormatter.summaryLine("§e总国家数", "§f${countries.size}")
            lines += DebugVisualizationFormatter.summaryLine("§e正在研究科技的国家", "§f${researchingCountries.size}")
            lines += DebugVisualizationFormatter.summaryLine("§e正在研究的科技总数", "§f$totalResearching")
            lines += ""

            if (researchingCountries.isEmpty()) {
                lines += DebugVisualizationFormatter.section("当前无国家正在研究科技")
            } else {
                lines += DebugVisualizationFormatter.section("科技研究进度")

                researchingCountries.forEachIndexed { index, snapshot ->
                    lines += DebugVisualizationFormatter.bulletLine(index + 1, snapshot.countryName, DebugVisualizationFormatter.maskUuid(snapshot.countryId))

                    snapshot.researchingTechs.forEach { techInfo ->
                        val currentTime = System.currentTimeMillis()
                        val totalDuration = techInfo.endTime - techInfo.startTime
                        val elapsed = currentTime - techInfo.startTime
                        val remaining = techInfo.endTime - currentTime
                        val progress = if (totalDuration > 0) {
                            ((elapsed.toDouble() / totalDuration) * 100).toInt().coerceIn(0, 100)
                        } else 0

                        lines += DebugVisualizationFormatter.detailLine("   科技", "§f${techInfo.techName} (Lv.${techInfo.currentLevel} → Lv.${techInfo.targetLevel})")
                        lines += DebugVisualizationFormatter.detailLine("   进度", "§f$progress% (剩余 ${formatDuration(remaining)})")
                    }
                }
            }

            DebugVisualizationFrame(
                title = "科技系统数据 - 研究进度",
                lines = lines,
                warnings = emptyList()
            )
        }
    }

    private data class TechnologyProgressSnapshot(
        val countryId: UUID,
        val countryName: String,
        val researchingTechs: List<TechResearchInfo>
    )

    private data class TechResearchInfo(
        val techId: String,
        val techName: String,
        val startTime: Long,
        val endTime: Long,
        val currentLevel: Int,
        val targetLevel: Int
    )

    private fun handleProfessionOverview(request: DebugVisualizationRequest): CompletableFuture<DebugVisualizationFrame> {
        val users = UserManager.users.values.toList()

        return CompletableFuture.supplyAsync {
            val lines = mutableListOf<String>()
            val professionCounts = mutableMapOf<String, Int>()
            val professionLevelCounts = mutableMapOf<String, MutableMap<Int, Int>>()
            var noProfessionCount = 0

            for (user in users) {
                if (user.profession == null) {
                    noProfessionCount++
                } else {
                    val professionName = ProfessionManager.getProfessionName(user.profession!!)
                    professionCounts[professionName] = (professionCounts[professionName] ?: 0) + 1

                    val levelMap = professionLevelCounts.getOrPut(professionName) { mutableMapOf() }
                    levelMap[user.professionLevel] = (levelMap[user.professionLevel] ?: 0) + 1
                }
            }

            lines += DebugVisualizationFormatter.summaryLine("§e总玩家数", "§f${users.size}")
            lines += DebugVisualizationFormatter.summaryLine("§e无职业玩家", "§f$noProfessionCount")
            lines += DebugVisualizationFormatter.summaryLine("§e有职业玩家", "§f${users.size - noProfessionCount}")
            lines += ""
            lines += DebugVisualizationFormatter.section("职业分布统计")

            professionCounts.entries.sortedByDescending { it.value }.forEach { (professionName, count) ->
                lines += DebugVisualizationFormatter.bulletLine(0, professionName, null)
                lines += DebugVisualizationFormatter.detailLine("   总人数", "§f$count")

                val levelMap = professionLevelCounts[professionName] ?: emptyMap()
                levelMap.entries.sortedBy { it.key }.forEach { (level, levelCount) ->
                    lines += DebugVisualizationFormatter.detailLine("   Lv.$level", "§f$levelCount 人")
                }
            }

            DebugVisualizationFrame(
                title = "职业系统数据 - 分布统计",
                lines = lines,
                warnings = emptyList()
            )
        }
    }

    private fun handleShieldStatus(request: DebugVisualizationRequest): CompletableFuture<DebugVisualizationFrame> {
        val countries = CountryManager.countries.values.toList()

        return CompletableFuture.supplyAsync {
            val lines = mutableListOf<String>()
            val activeShields = mutableListOf<ShieldStatusSnapshot>()
            val cooldownShields = mutableListOf<ShieldStatusSnapshot>()

            for (country in countries) {
                val isActive = ShieldManager.isShieldActive(country)
                val remainingTime = ShieldManager.getShieldRemainingTime(country)
                val cooldownEnd = country.shieldCooldownEnd ?: 0L
                val memberCount = CountryManager.getCountryMembers(country.id).size

                if (isActive) {
                    activeShields.add(ShieldStatusSnapshot(
                        countryId = country.id,
                        countryName = country.name,
                        isActive = true,
                        remainingTime = remainingTime,
                        cooldownEnd = cooldownEnd,
                        memberCount = memberCount
                    ))
                } else if (cooldownEnd > System.currentTimeMillis()) {
                    cooldownShields.add(ShieldStatusSnapshot(
                        countryId = country.id,
                        countryName = country.name,
                        isActive = false,
                        remainingTime = 0L,
                        cooldownEnd = cooldownEnd,
                        memberCount = memberCount
                    ))
                }
            }

            lines += DebugVisualizationFormatter.summaryLine("§e总国家数", "§f${countries.size}")
            lines += DebugVisualizationFormatter.summaryLine("§e激活护盾的国家", "§f${activeShields.size}")
            lines += DebugVisualizationFormatter.summaryLine("§e冷却中的国家", "§f${cooldownShields.size}")
            lines += ""

            if (activeShields.isNotEmpty()) {
                lines += DebugVisualizationFormatter.section("激活中的护盾")
                activeShields.sortedByDescending { it.remainingTime }.forEachIndexed { index, snapshot ->
                    lines += DebugVisualizationFormatter.bulletLine(index + 1, snapshot.countryName, DebugVisualizationFormatter.maskUuid(snapshot.countryId))
                    lines += DebugVisualizationFormatter.detailLine("   剩余时间", formatDuration(snapshot.remainingTime))
                    lines += DebugVisualizationFormatter.detailLine("   成员数量", "§f${snapshot.memberCount} 人")
                }
                lines += ""
            }

            if (cooldownShields.isNotEmpty()) {
                lines += DebugVisualizationFormatter.section("冷却中的护盾")
                cooldownShields.sortedBy { it.cooldownEnd }.take(20).forEachIndexed { index, snapshot ->
                    val cooldownRemaining = snapshot.cooldownEnd - System.currentTimeMillis()
                    lines += DebugVisualizationFormatter.bulletLine(index + 1, snapshot.countryName, DebugVisualizationFormatter.maskUuid(snapshot.countryId))
                    lines += DebugVisualizationFormatter.detailLine("   冷却剩余", formatDuration(cooldownRemaining))
                }

                if (cooldownShields.size > 20) {
                    lines += DebugVisualizationFormatter.warn("仅展示前20个冷却中的国家，剩余 ${cooldownShields.size - 20} 个已省略。")
                }
            }

            if (activeShields.isEmpty() && cooldownShields.isEmpty()) {
                lines += DebugVisualizationFormatter.section("当前无国家使用护盾")
            }

            DebugVisualizationFrame(
                title = "护盾系统数据 - 状态一览",
                lines = lines,
                warnings = emptyList()
            )
        }
    }

    private data class ShieldStatusSnapshot(
        val countryId: UUID,
        val countryName: String,
        val isActive: Boolean,
        val remainingTime: Long,
        val cooldownEnd: Long,
        val memberCount: Int
    )

    private fun handleRuntimeState(request: DebugVisualizationRequest): CompletableFuture<DebugVisualizationFrame> {
        // 🔧 修复问题2 (High): 在主线程先快照Bukkit API数据，避免异步线程访问Bukkit API
        val onlinePlayersSnapshot = Bukkit.getOnlinePlayers().size
        val maxPlayersSnapshot = Bukkit.getMaxPlayers()

        return CompletableFuture.supplyAsync {
            val lines = mutableListOf<String>()

            // 异步任务队列状态
            val pendingAsyncTasks = DataManager.getPendingAsyncTaskCount()
            lines += DebugVisualizationFormatter.section("异步任务队列")
            lines += DebugVisualizationFormatter.detailLine("   待处理任务数", "§f$pendingAsyncTasks")
            lines += ""

            // 缓存大小统计
            lines += DebugVisualizationFormatter.section("缓存状态")
            lines += DebugVisualizationFormatter.detailLine("   国家缓存", "§f${CountryManager.countries.size} 条")
            lines += DebugVisualizationFormatter.detailLine("   领土缓存", "§f${TerritoryManager.territories.size} 条")
            lines += DebugVisualizationFormatter.detailLine("   用户缓存", "§f${UserManager.users.size} 条")
            lines += ""

            // 在线玩家统计（使用快照数据）
            lines += DebugVisualizationFormatter.section("服务器状态")
            lines += DebugVisualizationFormatter.detailLine("   在线玩家", "§f$onlinePlayersSnapshot 人")
            lines += DebugVisualizationFormatter.detailLine("   最大玩家数", "§f$maxPlayersSnapshot 人")
            lines += ""

            // 内存使用情况
            val runtime = Runtime.getRuntime()
            val maxMemory = runtime.maxMemory() / 1024 / 1024
            val totalMemory = runtime.totalMemory() / 1024 / 1024
            val freeMemory = runtime.freeMemory() / 1024 / 1024
            val usedMemory = totalMemory - freeMemory
            val usagePercent = (usedMemory.toDouble() / maxMemory * 100).toInt()

            lines += DebugVisualizationFormatter.section("内存使用")
            lines += DebugVisualizationFormatter.detailLine("   已用内存", "§f${usedMemory}MB / ${maxMemory}MB (${usagePercent}%)")
            lines += DebugVisualizationFormatter.detailLine("   可用内存", "§f${freeMemory}MB")
            lines += DebugVisualizationFormatter.detailLine("   总分配内存", "§f${totalMemory}MB")

            val warnings = mutableListOf<String>()
            if (usagePercent > 90) {
                warnings += "内存使用率超过90%，建议检查内存泄漏或增加堆内存。"
            }
            if (pendingAsyncTasks > 100) {
                warnings += "待处理异步任务数超过100，可能存在性能问题。"
            }

            DebugVisualizationFrame(
                title = "运行时数据 - 数据库与缓存",
                lines = lines,
                warnings = warnings
            )
        }
    }
}
