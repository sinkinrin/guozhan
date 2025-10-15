package cn.lcofficial.guozhan.command

import cn.lcofficial.guozhan.config.Config
import cn.lcofficial.guozhan.config.Message
import cn.lcofficial.guozhan.config.Message.mini
import cn.lcofficial.guozhan.config.Message.miniReplace
import cn.lcofficial.guozhan.config.Message.sendError
import cn.lcofficial.guozhan.config.Message.sendSuccess
import cn.lcofficial.guozhan.config.Message.sendWarning
import cn.lcofficial.guozhan.config.Message.sendInfo
import cn.lcofficial.guozhan.config.Message.sendUsage
import cn.lcofficial.guozhan.config.Message.sendPermissionError
import cn.lcofficial.guozhan.config.Message.sendNoCountryError
import cn.lcofficial.guozhan.data.Cities
import cn.lcofficial.guozhan.data.Countries
import cn.lcofficial.guozhan.data.Country
import cn.lcofficial.guozhan.data.DiplomaticRelations
import cn.lcofficial.guozhan.data.Profession
import cn.lcofficial.guozhan.data.Rank
import cn.lcofficial.guozhan.data.RelationType
import cn.lcofficial.guozhan.data.ResourceType
import cn.lcofficial.guozhan.data.Technology
import cn.lcofficial.guozhan.data.TerritoryBlock
import cn.lcofficial.guozhan.data.TerritoryBlocks
import cn.lcofficial.guozhan.economy.RegionalTaxSystem
import cn.lcofficial.guozhan.data.Users
import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.manager.CityManager
import cn.lcofficial.guozhan.manager.CityManager.city
import cn.lcofficial.guozhan.manager.CooldownManager
import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.manager.DiplomacyManager
import cn.lcofficial.guozhan.manager.EconomyManager
import cn.lcofficial.guozhan.manager.ShieldManager
import cn.lcofficial.guozhan.manager.TeleportManager
import cn.lcofficial.guozhan.manager.TerritoryManager
import cn.lcofficial.guozhan.manager.TechnologyManager
import cn.lcofficial.guozhan.manager.UserManager
import cn.lcofficial.guozhan.manager.UserManager.user
import cn.lcofficial.guozhan.manager.WarManager
import cn.lcofficial.guozhan.plugin
import cn.lcofficial.guozhan.util.TaxRegionMapUtil
import cn.lcofficial.guozhan.util.TerritoryMapUtil
import cn.lcofficial.guozhan.util.hasEnoughItem
import cn.lcofficial.guozhan.util.takeItem
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.MapMeta
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object GuozhanCommand : TabExecutor {

    // 确认操作存储 (玩家UUID -> (操作类型 -> 目标参数))
    private val pendingConfirmations = ConcurrentHashMap<UUID, ConcurrentHashMap<String, String>>()

    // 邀请数据存储 (被邀请者UUID -> 邀请信息)
    private val pendingInvitations = ConcurrentHashMap<UUID, CountryInvitation>()

    /**
     * 国家邀请数据类
     */
    data class CountryInvitation(
        val inviterUuid: UUID,
        val inviterName: String,
        val countryId: UUID,
        val countryName: String,
        val inviteTime: Long,
        val expiryTime: Long
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() > expiryTime
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        process(sender, args)
        return true
    }

    /**
     * 宣战命令
     */
    private fun declareWar(sender: CommandSender, targetCountryName: String) {
        if (sender !is Player) {
            sender.sendError("该命令只能由玩家执行")
            return
        }

        val user = sender.user()
        val country = user.country
        if (country == null) {
            sender.sendNoCountryError()
            return
        }

        if (user.rank.value < 3) {
            sender.sendPermissionError("国家领袖权限")
            sender.sendInfo("只有君主才能宣战")
            return
        }

        // 查找目标国家
        val targetCountry = CountryManager.getByName(targetCountryName)
        if (targetCountry == null) {
            sender.sendError("找不到国家: $targetCountryName")
            sender.sendUsage("/u war declare <国家名>", "向指定国家宣战")
            return
        }

        if (targetCountry.id == country.id) {
            sender.sendError("不能向自己的国家宣战")
            return
        }

        // 检查当前关系
        val relation = DiplomacyManager.getRelation(country, targetCountry)
        if (relation.relationType == RelationType.WAR) {
            sender.sendWarning("你的国家已经与 ${targetCountry.name} 处于战争状态")
            return
        }

        // 宣战
        WarManager.startWar(country, targetCountry)

        sender.sendMessage("§a你的国家已向 ${targetCountry.name} 宣战！")
    }

    /**
     * 战争状态命令
     */
    private fun warStatus(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendError("该命令只能由玩家执行")
            return
        }

        val user = sender.user()
        val country = user.country
        if (country == null) {
            sender.sendNoCountryError()
            return
        }

        val warOpponents = WarManager.getWarOpponents(country)

        if (warOpponents.isEmpty()) {
            sender.sendMessage("§a你的国家当前没有处于战争状态。")
            return
        }

        sender.sendInfo("===== ${country.name} 的战争状态 =====")

        warOpponents.forEach { opponent ->
            val duration = WarManager.getWarDuration(country, opponent)
            val formattedDuration = WarManager.formatWarDuration(duration)

            sender.sendWarning("与 ${opponent.name} 的战争已持续 ${formattedDuration}")
        }
    }

    /**
     * 投降命令
     */
    private fun surrenderWar(sender: CommandSender, targetCountryName: String) {
        if (sender !is Player) {
            sender.sendMessage("§c该命令只能由玩家执行！")
            return
        }

        val user = sender.user()
        val country = user.country
        if (country == null) {
            sender.sendMessage("§c你不属于任何国家！")
            return
        }

        if (user.rank.value < 3) {
            sender.sendMessage("§c你没有权限投降！需要国家领袖权限。")
            return
        }

        // 查找目标国家
        val targetCountry = CountryManager.getByName(targetCountryName)
        if (targetCountry == null) {
            sender.sendMessage("§c找不到国家: $targetCountryName")
            return
        }

        // 检查是否处于战争状态
        if (!WarManager.isAtWar(country, targetCountry)) {
            sender.sendMessage("§c你的国家与 ${targetCountry.name} 不处于战争状态！")
            return
        }

        // 结束战争，指定胜利者为对方
        WarManager.endWar(country, targetCountry, targetCountry)

        sender.sendMessage("§a你的国家已向 ${targetCountry.name} 投降！")
    }

    /**
     * 设置与其他国家的外交关系
     */
    private fun setRelation(sender: CommandSender, targetCountryName: String, relationTypeStr: String) {
        if (sender !is Player) {
            sender.sendMessage("§c该命令只能由玩家执行！")
            return
        }

        val user = sender.user()
        val country = user.country
        if (country == null) {
            sender.sendMessage("§c你不属于任何国家！")
            return
        }

        if (user.rank.value < 2) {
            sender.sendMessage("§c你没有权限管理国家外交关系！需要国家管理员或更高权限。")
            return
        }

        // 查找目标国家
        val targetCountry = CountryManager.getByName(targetCountryName)
        if (targetCountry == null) {
            sender.sendMessage("§c找不到国家: $targetCountryName")
            return
        }

        if (targetCountry.id == country.id) {
            sender.sendMessage("§c不能与自己的国家建立外交关系！")
            return
        }

        // 解析关系类型
        val relationType = when (relationTypeStr.lowercase()) {
            "neutral" -> RelationType.NEUTRAL
            "friendly" -> RelationType.FRIENDLY
            "allied" -> RelationType.ALLIED
            "hostile" -> RelationType.HOSTILE
            "war" -> RelationType.WAR
            else -> {
                sender.sendMessage("§c无效的关系类型！可用选项: neutral, friendly, allied, hostile, war")
                return
            }
        }

        // 更新关系
        DiplomacyManager.updateRelation(country, targetCountry, relationType)

        // 发送成功消息
        val relationName = when (relationType) {
            RelationType.NEUTRAL -> "中立"
            RelationType.FRIENDLY -> "友好"
            RelationType.ALLIED -> "同盟"
            RelationType.HOSTILE -> "敌对"
            RelationType.WAR -> "战争"
        }

        sender.sendMessage("§a成功将与 ${targetCountry.name} 的关系设置为: $relationName")
    }

    /**
     * 列出所有外交关系
     */
    private fun listRelations(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage("§c该命令只能由玩家执行！")
            return
        }

        val user = sender.user()
        val country = user.country
        if (country == null) {
            sender.sendMessage("§c你不属于任何国家！")
            return
        }

        sender.sendMessage("§6===== ${country.name} 的外交关系 =====")

        // 获取所有国家
        val allCountries = CountryManager.countries.values.filter { it.id != country.id }

        if (allCountries.isEmpty()) {
            sender.sendMessage("§7没有其他国家")
            return
        }

        // 按关系类型分组显示
        val allies = DiplomacyManager.getAllies(country)
        val enemies = DiplomacyManager.getEnemies(country)

        if (allies.isNotEmpty()) {
            sender.sendMessage("§a同盟国家:")
            allies.forEach { ally ->
                sender.sendMessage("  §f- ${ally.name}")
            }
        }

        if (enemies.isNotEmpty()) {
            sender.sendMessage("§c敌对国家:")
            enemies.forEach { enemy ->
                sender.sendMessage("  §f- ${enemy.name}")
            }
        }

        // 显示其他关系
        val otherCountries = allCountries.filter { other ->
            !allies.any { it.id == other.id } && !enemies.any { it.id == other.id }
        }

        if (otherCountries.isNotEmpty()) {
            sender.sendMessage("§7其他国家:")
            otherCountries.forEach { other ->
                val relation = DiplomacyManager.getRelation(country, other)
                val relationName = when (relation.relationType) {
                    RelationType.NEUTRAL -> "中立"
                    RelationType.FRIENDLY -> "友好"
                    RelationType.HOSTILE -> "敌对"
                    else -> "未知" // 不应该出现
                }
                sender.sendMessage("  §f- ${other.name} §7($relationName)")
            }
        }
    }

    /**
     * 获取税收地图命令
     */
    private fun getTaxMap(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage("§c该命令只能由玩家执行！")
            return
        }

        // 创建税收区域地图
        val mapView = TaxRegionMapUtil.createTaxRegionMap()
        if (mapView == null) {
            with(cn.lcofficial.guozhan.config.Message) {
                sender.sendError("创建税收区域地图失败")
            }
            return
        }

        // 给玩家一个地图物品
        val mapItem = ItemStack(Material.FILLED_MAP)
        val mapMeta = mapItem.itemMeta as MapMeta
        mapMeta.mapView = mapView
        mapMeta.displayName(Component.text("税收区域地图").color(NamedTextColor.GOLD))
        mapMeta.lore(listOf(
            Component.text("显示不同税收区域的地图").color(NamedTextColor.GRAY),
            Component.text("红色: 核心疆域").color(NamedTextColor.RED),
            Component.text("橙色: 内陆邦畿").color(NamedTextColor.GOLD),
            Component.text("黄色: 开拓边疆").color(NamedTextColor.YELLOW),
            Component.text("绿色: 纷争之地").color(NamedTextColor.GREEN),
            Component.text("蓝色: 远征前线").color(NamedTextColor.BLUE),
            Component.text("紫色: 失落蛮荒").color(NamedTextColor.DARK_PURPLE)
        ))
        mapItem.itemMeta = mapMeta

        // 添加到玩家物品栏
        with(cn.lcofficial.guozhan.config.Message) {
            if (sender.inventory.firstEmpty() == -1) {
                // 物品栏已满，掉落在地上
                sender.world.dropItem(sender.location, mapItem)
                sender.sendInfo("§a税收区域地图已掉落在你的脚下")
            } else {
                // 添加到物品栏
                sender.inventory.addItem(mapItem)
                sender.sendInfo("§a你获得了一张税收区域地图")
            }
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): List<String> {
        return when (args.size) {
            1 -> listOf(
                // 基础命令
                "reload", "create", "info", "list", "purge", "help",
                // 领土管理
                "claim", "unclaim", "harvest", "claimmode", "map",
                // 经济管理
                "tax", "contribute", "distribute", "treasury",
                // 外交战争
                "diplomacy", "war", "shield",
                // 成员管理
                "invite", "accept", "decline", "kick", "promote", "demote", "transfer",
                // 国家管理
                "rename", "move", "return", "declaration", "title",
                // 科技系统
                "tech",
                // 其他功能
                "taxmap"
            ).filter {
                it.startsWith(
                    args[0],
                    ignoreCase = true
                )
            }
            2 -> when (args[0].lowercase()) {
                "tax" -> listOf("set", "collect", "info").filter { it.startsWith(args[1], ignoreCase = true) }
                "contribute" -> listOf("gold", "diamond", "iron", "food").filter { it.startsWith(args[1], ignoreCase = true) }
                "distribute" -> listOf("gold", "diamond").filter { it.startsWith(args[1], ignoreCase = true) }
                "diplomacy" -> listOf("set", "list").filter { it.startsWith(args[1], ignoreCase = true) }
                "war" -> listOf("declare", "status", "surrender").filter { it.startsWith(args[1], ignoreCase = true) }
                "tech" -> listOf("research", "info", "list").filter { it.startsWith(args[1], ignoreCase = true) }
                "shield" -> listOf("on", "off").filter { it.startsWith(args[1], ignoreCase = true) }
                "help" -> listOf("country", "territory", "economy", "diplomacy", "member").filter { it.startsWith(args[1], ignoreCase = true) }
                // 需要玩家名称的命令
                "invite", "kick", "promote", "demote", "transfer", "title" -> {
                    org.bukkit.Bukkit.getOnlinePlayers()
                        .map { it.name }
                        .filter { it.lowercase().startsWith(args[1].lowercase()) }
                }
                // 需要国家名称的命令
                "info" -> {
                    CountryManager.getAllCountries()
                        .map { it.name }
                        .filter { it.lowercase().startsWith(args[1].lowercase()) }
                }
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "tax" -> if (args[1].equals("set", ignoreCase = true)) {
                    (0..30).map { it.toString() }.filter { it.startsWith(args[2], ignoreCase = true) }
                } else emptyList()
                "contribute", "distribute" -> (1..64).map { it.toString() }.filter { it.startsWith(args[2], ignoreCase = true) }
                "diplomacy" -> if (args[1].equals("set", ignoreCase = true)) {
                    CountryManager.countries.values.map { it.name }.filter { it.startsWith(args[2], ignoreCase = true) }
                } else emptyList()
                "war" -> if (args[1].equals("declare", ignoreCase = true) || args[1].equals("surrender", ignoreCase = true)) {
                    CountryManager.countries.values.map { it.name }.filter { it.startsWith(args[2], ignoreCase = true) }
                } else emptyList()
                "tech" -> if (args[1].equals("research", ignoreCase = true) || args[1].equals("info", ignoreCase = true)) {
                    TechnologyManager.getAllTechnologies().map { it.id }.filter { it.startsWith(args[2], ignoreCase = true) }
                } else emptyList()
                "title" -> if (args[1].isNotEmpty()) {
                    // 第三个参数是头衔内容，不需要补全
                    emptyList()
                } else emptyList()
                else -> emptyList()
            }
            4 -> when (args[0].lowercase()) {
                "diplomacy" -> if (args[1].equals("set", ignoreCase = true)) {
                    listOf("neutral", "friendly", "allied", "hostile", "war").filter { it.startsWith(args[3], ignoreCase = true) }
                } else emptyList()
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    private fun process(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            displayHelp(sender)
            return
        }

        when (args[0].lowercase()) {
            "taxmap" -> getTaxMap(sender)
            "reload" -> {
                if (sender.hasPermission("guozhan.command.reload")) {
                    reload(sender)
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "create" -> {
                if (args.size < 2) {
                    sender.sendMessage(Message.Commands.Create.InvalidUsage.mini())
                    return
                }
                if (sender.hasPermission("guozhan.command.create")) {
                    create(sender, args[1])
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "info" -> {
                if (args.size < 2) {
                    sender.sendMessage(Message.Commands.Info.InvalidUsage.mini())
                    return
                }
                if (sender.hasPermission("guozhan.command.info")) {
                    info(sender, args[1])
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "list" -> {
                if (sender.hasPermission("guozhan.command.list")) {
                    listCountries(sender, if (args.size >= 2) args[1].toIntOrNull() ?: 1 else 1)

                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "contribute" -> {
                if (sender.hasPermission("guozhan.command.contribute")) {
                    if (args.size < 3) {
                        sender.sendUsage("/u contribute <资源类型> <数量>", "向国家贡献资源")
                        sender.sendInfo("支持的资源类型: gold, diamond, iron, food")
                        sender.sendInfo("示例: /u contribute gold 100")
                        return
                    }
                    contributeResource(sender, args[1], args[2].toIntOrNull() ?: 1)
                } else sender.sendPermissionError("guozhan.command.contribute")
            }

            "claim" -> {
                if (sender.hasPermission("guozhan.command.claim")) {
                    claimTerritory(sender)
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "unclaim" -> {
                if (sender.hasPermission("guozhan.command.unclaim")) {
                    unclaimTerritory(sender)
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "harvest" -> {
                if (sender.hasPermission("guozhan.command.harvest")) {
                    harvestResource(sender)
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "tax" -> {
                if (sender.hasPermission("guozhan.command.tax")) {
                    if (args.size < 2) {
                        sender.sendUsage("/u tax <set|collect|info> [税率]", "管理国家税收系统")
                        sender.sendInfo("子命令:")
                        sender.sendInfo("  set <税率> - 设置税率 (1-100)")
                        sender.sendInfo("  collect - 收取税收 (24小时冷却)")
                        sender.sendInfo("  info - 查看税收信息")
                        return
                    }
                    when (args[1].lowercase()) {
                        "set" -> {
                            if (args.size < 3) {
                                sender.sendUsage("/u tax set <税率>", "设置国家税率")
                                sender.sendInfo("税率范围: 1-100 (例如: 10 表示10%)")
                                return
                            }
                            setTaxRate(sender, args[2].toIntOrNull() ?: 10)
                        }
                        "collect" -> collectTax(sender)
                        "info" -> taxInfo(sender)
                        else -> {
                            sender.sendError("未知的税收命令: ${args[1]}")
                            sender.sendUsage("/u tax <set|collect|info>", "可用的税收命令")
                        }
                    }
                } else sender.sendPermissionError("guozhan.command.tax")
            }

            "distribute" -> {
                if (sender.hasPermission("guozhan.command.distribute")) {
                    if (args.size < 3) {
                        sender.sendMessage("§c用法: /u distribute <资源类型> <数量> [玩家名]")
                        return
                    }
                    val targetPlayer = if (args.size >= 4) {
                        Bukkit.getPlayer(args[3])
                    } else {
                        if (sender is Player) sender else null
                    }

                    if (targetPlayer == null) {
                        sender.sendMessage("§c找不到指定的玩家!")
                        return
                    }

                    distributeResource(sender, args[1], args[2].toIntOrNull() ?: 1, targetPlayer)
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "diplomacy" -> {
                if (sender.hasPermission("guozhan.command.diplomacy")) {
                    if (args.size < 2) {
                        sender.sendMessage("§c用法: /u diplomacy <set|list>")
                        return
                    }
                    when (args[1].lowercase()) {
                        "set" -> {
                            if (args.size < 4) {
                                sender.sendMessage("§c用法: /u diplomacy set <国家名> <关系类型>")
                                return
                            }
                            setRelation(sender, args[2], args[3])
                        }
                        "list" -> listRelations(sender)
                        else -> sender.sendMessage("§c未知的外交命令: ${args[1]}")
                    }
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "war" -> {
                if (sender.hasPermission("guozhan.command.war")) {
                    if (args.size < 2) {
                        sender.sendMessage("§c用法: /u war <declare|status|surrender>")
                        return
                    }
                    when (args[1].lowercase()) {
                        "declare" -> {
                            if (args.size < 3) {
                                sender.sendMessage("§c用法: /u war declare <国家名>")
                                return
                            }
                            declareWar(sender, args[2])
                        }
                        "status" -> warStatus(sender)
                        "surrender" -> {
                            if (args.size < 3) {
                                sender.sendMessage("§c用法: /u war surrender <国家名>")
                                return
                            }
                            surrenderWar(sender, args[2])
                        }
                        else -> sender.sendMessage("§c未知的战争命令: ${args[1]}")
                    }
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "purge" -> {
                if (sender.hasPermission("guozhan.command.list")) {
                    purge(sender)
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            // 新增缺失的命令
            "map" -> {
                if (sender.hasPermission("guozhan.command.map")) {
                    createTerritoryMap(sender)
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "claimmode" -> {
                if (sender.hasPermission("guozhan.command.claimmode")) {
                    toggleClaimMode(sender)
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "treasury" -> {
                if (sender.hasPermission("guozhan.command.treasury")) {
                    viewTreasury(sender)
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "return" -> {
                if (sender.hasPermission("guozhan.command.return")) {
                    teleportToCapital(sender)
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "kick" -> {
                if (args.size < 2) {
                    sender.sendMessage("§c用法: /u kick <玩家名>")
                    return
                }
                if (sender.hasPermission("guozhan.command.kick")) {
                    kickMember(sender, args[1])
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "joinmode" -> {
                if (args.size < 2) {
                    sender.sendMessage("§c用法: /u joinmode <开放/仅邀请>")
                    return
                }
                if (sender.hasPermission("guozhan.command.joinmode")) {
                    setJoinMode(sender, args[1])
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "shield" -> {
                if (args.size < 2) {
                    sender.sendMessage("§c用法: /u shield <on/off> [小时数]")
                    return
                }
                if (sender.hasPermission("guozhan.command.shield")) {
                    val hours = if (args.size >= 3) args[2].toIntOrNull() ?: 1 else 1
                    toggleShield(sender, args[1], hours)
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "tech" -> {
                if (sender.hasPermission("guozhan.command.tech")) {
                    if (args.size < 2) {
                        // 打开科技树GUI
                        openTechnologyGUI(sender)
                    } else {
                        when (args[1].lowercase()) {
                            "research" -> {
                                if (args.size < 3) {
                                    sender.sendUsage("/u tech research <科技ID> [confirm]", "开始研究指定科技")
                                    return
                                }
                                val confirm = args.size >= 4 && args[3].equals("confirm", ignoreCase = true)
                                researchTechnology(sender, args[2], confirm)
                            }
                            "info" -> {
                                if (args.size < 3) {
                                    sender.sendUsage("/u tech info <科技ID>", "查看科技详细信息")
                                    return
                                }
                                showTechnologyInfo(sender, args[2])
                            }
                            "list" -> showTechnologyList(sender)
                            else -> {
                                sender.sendError("未知的科技命令: ${args[1]}")
                                sender.sendUsage("/u tech", "打开科技树界面")
                            }
                        }
                    }
                } else sender.sendPermissionError("guozhan.command.tech")
            }

            "restore" -> {
                if (sender.hasPermission("guozhan.command.restore")) {
                    val confirm = args.size >= 2 && args[1].equals("confirm", ignoreCase = true)
                    restoreLoyalty(sender, confirm)
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "invite" -> {
                if (args.size < 2) {
                    sender.sendMessage("§c用法: /u invite <玩家名>")
                    return
                }
                if (sender.hasPermission("guozhan.command.invite")) {
                    invitePlayer(sender, args[1])
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "declaration" -> {
                if (args.size < 2) {
                    sender.sendMessage("§c用法: /u declaration <内容>")
                    return
                }
                if (sender.hasPermission("guozhan.command.declaration")) {
                    val declaration = args.drop(1).joinToString(" ")
                    setDeclaration(sender, declaration)
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "disband" -> {
                if (args.size >= 2 && args[1] == "confirm") {
                    if (sender.hasPermission("guozhan.command.disband")) {
                        confirmDisbandCountry(sender)
                    } else sender.sendMessage(Message.NoPermission.mini())
                } else {
                    if (sender.hasPermission("guozhan.command.disband")) {
                        disbandCountry(sender)
                    } else sender.sendMessage(Message.NoPermission.mini())
                }
            }

            "promote" -> {
                if (args.size < 2) {
                    sender.sendMessage("§c用法: /u promote <国民>")
                    return
                }
                if (sender.hasPermission("guozhan.command.promote")) {
                    promoteMember(sender, args[1])
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "demote" -> {
                if (args.size < 2) {
                    sender.sendMessage("§c用法: /u demote <国民>")
                    return
                }
                if (sender.hasPermission("guozhan.command.demote")) {
                    demoteMember(sender, args[1])
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "move" -> {
                if (sender.hasPermission("guozhan.command.move")) {
                    moveCapital(sender)
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "rename" -> {
                if (args.size < 2) {
                    sender.sendMessage("§c用法: /u rename <新名称>")
                    return
                }
                if (sender.hasPermission("guozhan.command.rename")) {
                    renameCountry(sender, args[1])
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "transfer" -> {
                if (args.size < 2) {
                    sender.sendMessage("§c用法: /u transfer <国民>")
                    return
                }
                if (sender.hasPermission("guozhan.command.transfer")) {
                    transferOwnership(sender, args[1])
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "title" -> {
                if (args.size < 3) {
                    sender.sendMessage("§c用法: /u title <国民> <自定义头衔>")
                    return
                }
                if (sender.hasPermission("guozhan.command.title")) {
                    val title = args.drop(2).joinToString(" ")
                    setMemberTitle(sender, args[1], title)
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            "leave" -> {
                if (sender.hasPermission("guozhan.command.leave")) {
                    leaveCountry(sender)
                } else sender.sendPermissionError("guozhan.command.leave")
            }

            "accept" -> {
                if (sender.hasPermission("guozhan.command.accept")) {
                    acceptInvitation(sender)
                } else sender.sendPermissionError("guozhan.command.accept")
            }

            "decline" -> {
                if (sender.hasPermission("guozhan.command.decline")) {
                    declineInvitation(sender)
                } else sender.sendPermissionError("guozhan.command.decline")
            }

            // v1.3.13修复：添加职业系统命令
            "profession" -> {
                if (sender.hasPermission("guozhan.command.profession")) {
                    if (args.size < 2) {
                        showProfessionHelp(sender)
                        return
                    }
                    when (args[1].lowercase()) {
                        "set" -> {
                            if (args.size < 3) {
                                sender.sendUsage("/u profession set <职业>", "设置职业")
                                sender.sendInfo("可用职业: scout, craftsman, berserker, guardian, leaper, priest, conqueror")
                                return
                            }
                            setProfession(sender, args[2])
                        }
                        "info" -> showProfessionInfo(sender)
                        "list" -> listProfessions(sender)
                        else -> showProfessionHelp(sender)
                    }
                } else sender.sendPermissionError("guozhan.command.profession")
            }

            "help", "?" -> showHelp(sender, args.getOrNull(1))

            else -> {
                sender.sendError("未知命令: ${args[0]}")
                sender.sendUsage("/u help", "查看所有可用命令")
            }
        }
    }

    /**
     * 显示帮助信息
     */
    private fun showHelp(sender: CommandSender, category: String?) {
        when (category?.lowercase()) {
            "country", "国家" -> {
                sender.sendInfo("=== 国家管理命令 ===")
                sender.sendInfo("/u create <国家名> - 创建新国家")
                sender.sendInfo("/u info [国家名] - 查看国家信息")
                sender.sendInfo("/u list [页数] - 列出所有国家")
                sender.sendInfo("/u rename <新名称> - 重命名国家")
                sender.sendInfo("/u disband - 解散国家")
                sender.sendInfo("/u leave - 离开当前国家")
            }
            "territory", "领土" -> {
                sender.sendInfo("=== 领土管理命令 ===")
                sender.sendInfo("/u claim - 占领当前区块")
                sender.sendInfo("/u unclaim - 放弃当前区块")
                sender.sendInfo("/u harvest - 收获领土资源")
                sender.sendInfo("/u map - 查看疆域地图")
                sender.sendInfo("/u taxmap - 查看税收区域地图")
            }
            "economy", "经济" -> {
                sender.sendInfo("=== 经济管理命令 ===")
                sender.sendInfo("/u contribute <类型> <数量> - 向国家贡献资源")
                sender.sendInfo("/u tax <set|collect|info> - 管理税收")
                sender.sendInfo("/u distribute <类型> <数量> [玩家] - 分配资源")
            }
            "diplomacy", "外交" -> {
                sender.sendInfo("=== 外交战争命令 ===")
                sender.sendInfo("/u diplomacy set <国家> <关系> - 设置外交关系")
                sender.sendInfo("/u diplomacy list - 查看外交关系")
                sender.sendInfo("/u war declare <国家> - 宣战")
                sender.sendInfo("/u war status - 查看战争状态")
                sender.sendInfo("/u war surrender <国家> - 投降")
            }
            "member", "成员" -> {
                sender.sendInfo("=== 成员管理命令 ===")
                sender.sendInfo("/u invite <玩家> - 邀请玩家加入")
                sender.sendInfo("/u kick <玩家> - 驱逐成员")
                sender.sendInfo("/u promote <玩家> - 提升成员等级")
                sender.sendInfo("/u demote <玩家> - 降低成员等级")
                sender.sendInfo("/u title <玩家> <头衔> - 设置成员头衔")
                sender.sendInfo("/u transfer <玩家> - 转让君主之位")
            }
            else -> {
                sender.sendInfo("=== GuoZhan 国战插件帮助 ===")
                sender.sendInfo("使用 /u help <分类> 查看详细命令")
                sender.sendInfo("§e可用分类:")
                sender.sendInfo("  country - 国家管理")
                sender.sendInfo("  territory - 领土管理")
                sender.sendInfo("  economy - 经济管理")
                sender.sendInfo("  diplomacy - 外交战争")
                sender.sendInfo("  member - 成员管理")
                sender.sendInfo("§7示例: /u help country")
            }
        }
    }

    private fun displayHelp(sender: CommandSender) {
        fun help(command: String, description: String) {
            sender.sendMessage(
                Component.text("$command - $description")
                    .color(NamedTextColor.GOLD)
                    .clickEvent(ClickEvent.suggestCommand(command))
            )
        }
        sender.sendMessage(Component.text("===== 国战插件命令帮助 =====").color(NamedTextColor.AQUA))
        help("/u create <国家名>", "创建国家 (需要9个铁锭)")
        help("/u info <国家名>", "查看国家信息")
        help("/u list [页码]", "查看国家列表")
        help("/u map", "获得疆域小地图 (15x15区块)")
        help("/u claimmode", "切换占领模式 (手动/自动)")
        help("/u contribute <金锭/钻石> <数量>", "向国库上贡 (CD:24h)")
        help("/u treasury", "查看邦国国库")
        help("/u return", "传送至王城 (需等待10秒)")
        help("/u kick <玩家>", "驱逐国民 (CD:1min)")
        help("/u joinmode <开放/仅邀请>", "设置加入模式")
        help("/u shield <on/off> [小时数]", "开关国家护盾")
        help("/u tech", "打开国家科技菜单")
        help("/u restore [confirm]", "恢复当前所有疆土民心")
        help("/u invite <玩家>", "邀请玩家加入国家")
        help("/u declaration <内容>", "设置国家宣言")
        help("/u diplomacy <国家名|ALL> <交互|访问|拒绝>", "设置外交权限")
        help("/u disband", "解散国家 (CD:1h)")
        help("/u promote <国民>", "册封大臣")
        help("/u demote <国民>", "罢免大臣")
        help("/u move", "迁移国家王城 (CD:12h)")
        help("/u rename <新名称>", "更改国家名称")
        help("/u transfer <国民>", "禅让君主之位")
        help("/u title <国民> <自定义头衔>", "册封国民头衔")
        help("/u unclaim", "放弃脚下区块领土")
        help("/u leave", "退出当前国家 (CD:1h)")

        sender.sendMessage(Component.text("===== 聊天命令 =====").color(NamedTextColor.YELLOW))
        help("/c <消息>", "发送公屏消息")
        help("/w <玩家名> <消息>", "私聊")
    }

    private fun create(sender: CommandSender, name: String) {
        try {
            transaction {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return@transaction
        }

        val user = sender.user()

        // 1. 检查玩家是否已有国家
        if (user.country != null) {
            sender.sendMessage(Message.Commands.Create.Already.mini())
            return@transaction
        }

        // 2. 国家名称合法性检查
        if (name.length !in 3..12 || !name.matches(Regex("[\\u4e00-\\u9fa5a-zA-Z0-9]+"))) {
            sender.sendMessage(Message.Commands.Create.InvalidName.mini())
            return@transaction
        }

        // 3. 检查名称是否已被占用
        if (CountryManager.getByName(name) != null) {
            sender.sendMessage(Message.Commands.Create.NameUsed.mini())
            return@transaction
        }

        // 4. 检查当前区块是否已被占领
        val city = sender.location.city()
        if (city.isOwned()) {
            sender.sendMessage(Message.Commands.Create.CityOwned.mini())
            return@transaction
        }

        // 5. 检查与其他国家的距离
        val playerChunkX = sender.location.blockX shr 4
        val playerChunkZ = sender.location.blockZ shr 4
        val minDistance = Config.Country.Creation.minDistanceBetweenCountries

        // 调试信息：显示当前玩家位置
        sender.sendMessage("§7[调试] 当前位置: 方块坐标(${sender.location.blockX}, ${sender.location.blockZ}), 区块坐标($playerChunkX, $playerChunkZ)")
        sender.sendMessage("§7[调试] 最小距离要求: $minDistance 区块 (${minDistance * 16} 格)")

        var nearbyCountry: Country? = null
        var minDistanceFound = Int.MAX_VALUE
        var closestCountryInfo = ""

        CountryManager.countries.values.forEach { country ->
            val capital = country.capital
            val capitalChunkX = capital.x
            val capitalChunkZ = capital.z
            val chunkDistance = kotlin.math.max(
                kotlin.math.abs(playerChunkX - capitalChunkX),
                kotlin.math.abs(playerChunkZ - capitalChunkZ)
            )
            val blockDistance = chunkDistance * 16

            // 记录最近的国家信息
            if (chunkDistance < minDistanceFound) {
                minDistanceFound = chunkDistance
                closestCountryInfo = "国家 '${country.name}' 距离: $chunkDistance 区块 (${blockDistance} 格), 位置: 区块($capitalChunkX, $capitalChunkZ)"
            }

            sender.sendMessage("§7[调试] 检查国家 '${country.name}': 区块($capitalChunkX, $capitalChunkZ), 距离: $chunkDistance 区块 (${blockDistance} 格)")

            if (chunkDistance < minDistance) {
                nearbyCountry = country
            }
        }

        if (nearbyCountry != null) {
            sender.sendMessage("§c不能在其他国家附近创建国家！")
            sender.sendMessage("§c$closestCountryInfo")
            sender.sendMessage("§c最小距离要求: $minDistance 区块 (${minDistance * 16} 格)")
            sender.sendMessage("§c请至少移动到 ${minDistance * 16} 格以外的位置")
            return@transaction
        } else {
            sender.sendMessage("§a[调试] 距离检查通过！最近的国家: $closestCountryInfo")
        }

        // 6. 检查物品是否足够（在所有其他检查通过后）
        val requiredMaterial = Config.Country.Creation.ItemCost.material
        val requiredAmount = Config.Country.Creation.ItemCost.amount

        if (!sender.hasEnoughItem(requiredMaterial, requiredAmount)) {
            val materialDisplayName = when (requiredMaterial) {
                Material.IRON_INGOT -> "铁锭"
                Material.GOLD_INGOT -> "金锭"
                Material.DIAMOND -> "钻石"
                Material.EMERALD -> "绿宝石"
                else -> requiredMaterial.name
            }
            sender.sendMessage("§c创建国家需要 ${requiredAmount} 个${materialDisplayName}！")
            return@transaction
        }

        // 6. 最后才扣除物品（确保所有检查都通过）
        sender.takeItem(requiredMaterial, requiredAmount)

        val country = CountryManager.create(sender, city, name)
        if (country == null) {
            sender.sendMessage(Message.Commands.Create.Already.mini())
            return@transaction
        }

                sender.sendMessage(Message.Commands.Create.Success.mini())
            }
        } catch (e: Exception) {
            sender.sendError("创建国家时发生错误: ${e.message}")
            cn.lcofficial.guozhan.Guozhan.instance.logger.warning("创建国家失败: ${e.message}")
        }
    }

    private fun reload(sender: CommandSender) {
        // 重新加载配置
        plugin.initialize() // 注意 plugin 需要在此处引用
        sender.sendMessage(Message.Reload.mini())
    }

    private fun info(sender: CommandSender, countryName: String) = transaction {
        val country = CountryManager.getByName(countryName)
        if (country == null) {
            sender.sendMessage(Message.Commands.Info.NotFound.replace("%name%", countryName).mini())
            return@transaction
        }

// 填充占位符
        val placeholders = mapOf(
            "name" to country.name,
            "owner" to (country.owner?.name ?: "未知"),
            "created" to SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(country.createTime)),
            "territory" to country.cities.count().toString(),
            "join_mode" to if (country.public) "开放" else "仅邀请",
            "shield" to if (country.shield) "开启" else "关闭",
            "capital_x" to country.capital.x.toString(),
            "capital_y" to country.capital.z.toString(),
            "members" to country.members.map { it.name }.distinct().joinToString(", ")
        )

// 使用 Message 多行占位符输出
        val lines = Message.Commands.Info.Lines.miniReplace(placeholders)
        lines.forEach {
            sender.sendMessage(it)
        }
    }

    fun listCountries(sender: CommandSender, page: Int) = transaction {
        val pageSize = 10
        val countriesPage = CountryManager.listPage(page, pageSize)
        if (countriesPage.isEmpty()) {
            sender.sendMessage(Message.Commands.List.Empty.mini())
            return@transaction
        }

        countriesPage.forEach { country ->
            val msg = Message.Commands.List.Format
                .replace("%id%", country.id.toString())
                .replace("%name%", country.name)
                .replace("%owner%", country.owner?.name ?: "未知")
                .replace("%territory%", country.cities.count().toString())
            sender.sendMessage(msg.mini(false))
        }

        val totalPages = CountryManager.totalPages(pageSize)

        // 分页箭头
        val prevPage = if (page > 1) page - 1 else 1
        val nextPage = if (page < totalPages) page + 1 else totalPages

        val pageMessage = Component.text()
            .append(
                Component.text("«").color(NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand("/u list $prevPage"))
                    .hoverEvent(HoverEvent.showText(Component.text("上一页")))
            )
            .append(
                Message.Commands.List.PageInfo
                    .replace("%page%", page.toString())
                    .replace("%total_page%", totalPages.toString()).mini(false)
            )
            .append(
                Component.text("»").color(NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand("/u list $nextPage"))
                    .hoverEvent(HoverEvent.showText(Component.text("下一页")))
            )
            .build()

        sender.sendMessage(pageMessage)
    }

    fun purge(sender: CommandSender) {
        transaction {
            Cities.deleteAll()
            Countries.deleteAll()
            Users.deleteAll()
            TerritoryBlocks.deleteAll()
            DiplomaticRelations.deleteAll()
        }
        CountryManager.countries.clear()
        UserManager.users.clear()
        CityManager.cities.clear()
        TerritoryManager.territories.clear()
        Bukkit.getOnlinePlayers().forEach {
            it.kick(Message.Commands.Purge.Kick.mini())
        }
        sender.sendMessage(Message.Commands.Purge.Success.mini())
    }

    private fun claimTerritory(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendMessage("§c你不属于任何国家，无法占领领土！")
            return
        }

        if (user.rank == Rank.DEFAULT) {
            sender.sendMessage("§c只有国家管理员或所有者才能占领领土！")
            return
        }

        val territory = TerritoryManager.getTerritoryBlock(
            sender.location.chunk.x,
            sender.location.chunk.z,
            sender.location.world.name
        ) ?: TerritoryManager.createTerritoryBlock(
            sender.location.chunk.x,
            sender.location.chunk.z,
            sender.location.world.name
        )

        if (territory.isOwned()) {
            if (territory.owner?.id == user.country?.id) {
                sender.sendError("这块领土已经属于你的国家了！")
                sender.sendInfo("当前领土忠诚度: ${territory.loyalty}%")
            } else {
                sender.sendError("这块领土已经被 ${territory.owner?.name ?: "未知国家"} 占领了！")
                sender.sendInfo("你可以尝试攻击占领，但需要更长时间和更多资源")
            }
            return
        }

        // v1.3.13修复：检查防御者护盾状态
        val defender = territory.owner
        if (defender != null && ShieldManager.isShieldActive(defender)) {
            sender.sendError("该领土所属国家正处于护盾保护中，无法占领！")
            val remainingTime = ShieldManager.getShieldRemainingTime(defender)
            val hours = remainingTime / (60 * 60 * 1000)
            val minutes = (remainingTime % (60 * 60 * 1000)) / (60 * 1000)
            sender.sendInfo("护盾剩余时间: ${hours}小时${minutes}分钟")
            return
        }

        // 检查接壤条件
        if (!TerritoryManager.canClaim(territory, user.country!!)) {
            sender.sendError("占领失败！领土必须与你的国家现有领土接壤")

            // 查找最近的己方领土
            val nearestOwnTerritory = findNearestOwnTerritory(sender, user.country!!)
            if (nearestOwnTerritory != null) {
                val distance = kotlin.math.abs(territory.x - nearestOwnTerritory.x) + kotlin.math.abs(territory.z - nearestOwnTerritory.z)
                sender.sendInfo("最近的己方领土距离: ${distance}区块")
                sender.sendInfo("位置: (${nearestOwnTerritory.x}, ${nearestOwnTerritory.z})")
            } else {
                sender.sendInfo("你的国家还没有任何领土，请先在王城附近占领")
            }
            return
        }

        // 显示占领成本
        val cost = 3
        val currentIron = sender.inventory.all(Material.IRON_INGOT).values.sumOf { it.amount }

        sender.sendInfo("§e=== 占领领土 ===")
        sender.sendInfo("§f需要消耗: §c${cost}个铁锭")
        sender.sendInfo("§f当前拥有: §a${currentIron}个铁锭")

        // 检查是否有足够的资源占领领土
        if (!sender.hasEnoughItem(Material.IRON_INGOT, cost)) {
            sender.sendError("占领领土需要${cost}个铁锭！")
            sender.sendInfo("你还需要 ${cost - currentIron} 个铁锭")
            return
        }

        // 启动计时占领流程（不立即扣除资源，完成时扣除）
        if (cn.lcofficial.guozhan.manager.ClaimManager.isTerritoryBeingClaimed(territory.world, territory.x, territory.z)) {
            sender.sendError("该领土正在被占领中，请稍候...")
            return
        }
        cn.lcofficial.guozhan.manager.ClaimManager.startClaim(sender, territory, user.country!!)
        sender.sendInfo("已开始占领进程，请保持在此区块内，期间受到攻击或离开将中断占领。")

        // v1.3.13修复：使用数据库查询而不是缓存
        val countryTerritories = TerritoryManager.getTerritoriesByCountry(user.country!!).size
        sender.sendInfo("国家总领土数量: ${countryTerritories}块")
    }

    /**
     * 查找最近的己方领土
     */
    private fun findNearestOwnTerritory(player: Player, country: Country): TerritoryBlock? {
        val playerChunkX = player.location.chunk.x
        val playerChunkZ = player.location.chunk.z
        val playerWorld = player.location.world.name

        // v1.3.13修复：使用数据库查询而不是缓存
        return TerritoryManager.getTerritoriesByCountry(country)
            .filter { it.world == playerWorld }
            .minByOrNull { territory ->
                kotlin.math.abs(territory.x - playerChunkX) + kotlin.math.abs(territory.z - playerChunkZ)
            }
    }

    /**
     * 向国家成员广播消息
     * @param country 国家
     * @param message 消息内容
     * @param excludePlayer 排除的玩家（可选）
     */
    private fun broadcastToCountryMembers(country: Country, message: String, excludePlayer: Player? = null) {
        country.members.forEach { member ->
            val player = Bukkit.getPlayer(member.uniqueId)
            if (player != null && player.isOnline && player != excludePlayer) {
                player.sendMessage(message)
            }
        }
    }

    private fun unclaimTerritory(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendMessage("§c你不属于任何国家，无法放弃领土！")
            return
        }

        if (user.rank == Rank.DEFAULT) {
            sender.sendMessage("§c只有国家管理员或所有者才能放弃领土！")
            return
        }

        val territory = TerritoryManager.getTerritoryBlock(
            sender.location.chunk.x,
            sender.location.chunk.z,
            sender.location.world.name
        )

        if (territory == null || !territory.isOwned()) {
            sender.sendMessage("§c这块区域不是任何国家的领土！")
            return
        }

        if (territory.owner?.id != user.country?.id) {
            sender.sendMessage("§c这块领土不属于你的国家！")
            return
        }

        territory.owner = null
        territory.loyalty = 0
        territory.save()

        sender.sendMessage("§a成功放弃了这块领土！")
    }

    private fun harvestResource(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendMessage("§c你不属于任何国家，无法收获资源！")
            return
        }

        val territory = TerritoryManager.getTerritoryBlock(
            sender.location.chunk.x,
            sender.location.chunk.z,
            sender.location.world.name
        )

        if (territory == null || !territory.isOwned()) {
            sender.sendMessage("§c这块区域不是任何国家的领土！")
            return
        }

        if (territory.owner?.id != user.country?.id) {
            sender.sendMessage("§c这块领土不属于你的国家！")
            return
        }

        if (!territory.canHarvest()) {
            sender.sendMessage("§c这块领土目前没有可收获的资源！")
            return
        }

        val country = user.country!!

        // 记录收获前的资源状态
        val beforeGold = country.gold
        val beforeDiamond = country.diamond
        val beforeIron = sender.inventory.all(Material.IRON_INGOT).values.sumOf { it.amount }
        val beforeBread = sender.inventory.all(Material.BREAD).values.sumOf { it.amount }

        val amount = territory.harvest()

        // 显示收获信息
        sender.sendInfo("§e=== 资源收获 ===")
        sender.sendInfo("§f领土位置: (${territory.x}, ${territory.z})")
        sender.sendInfo("§f资源类型: ${territory.resourceType}")
        sender.sendInfo("§f收获数量: §a${amount}单位")

        when (territory.resourceType) {
            ResourceType.GOLD -> {
                country.gold += amount
                sender.sendSuccess("成功收获了${amount}单位黄金！")
                sender.sendInfo("§f国家黄金: §c${beforeGold} §f→ §a${country.gold} §f(+${amount})")
            }
            ResourceType.DIAMOND -> {
                country.diamond += amount
                sender.sendSuccess("成功收获了${amount}单位钻石！")
                sender.sendInfo("§f国家钻石: §c${beforeDiamond} §f→ §a${country.diamond} §f(+${amount})")
            }
            ResourceType.IRON -> {
                // 直接给玩家物品
                sender.inventory.addItem(ItemStack(Material.IRON_INGOT, amount))
                val afterIron = sender.inventory.all(Material.IRON_INGOT).values.sumOf { it.amount }
                sender.sendSuccess("成功收获了${amount}个铁锭！")
                sender.sendInfo("§f个人铁锭: §c${beforeIron} §f→ §a${afterIron} §f(+${amount})")
            }
            ResourceType.FOOD -> {
                // 直接给玩家物品
                sender.inventory.addItem(ItemStack(Material.BREAD, amount))
                val afterBread = sender.inventory.all(Material.BREAD).values.sumOf { it.amount }
                sender.sendSuccess("成功收获了${amount}个面包！")
                sender.sendInfo("§f个人面包: §c${beforeBread} §f→ §a${afterBread} §f(+${amount})")
            }
            else -> {
                sender.sendError("这块领土没有资源！")
                return
            }
        }

        // 显示下次收获时间
        val nextHarvestTime = territory.lastHarvestTime + (24 * 60 * 60 * 1000L) // 24小时后
        val remainingTime = nextHarvestTime - System.currentTimeMillis()
        if (remainingTime > 0) {
            val hours = remainingTime / (1000 * 60 * 60)
            val minutes = (remainingTime % (1000 * 60 * 60)) / (1000 * 60)
            sender.sendInfo("§f下次可收获: §e${hours}小时${minutes}分钟后")
        } else {
            sender.sendInfo("§f下次可收获: §a立即可用")
        }

        country.save()
    }

    /**
     * 设置国家税率
     */
    private fun setTaxRate(sender: CommandSender, rate: Int) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendMessage("§c你不属于任何国家，无法设置税率！")
            return
        }

        if (user.rank.value < 2) { // 只有国王和管理员可以设置税率
            sender.sendMessage("§c只有国家管理员或所有者才能设置税率！")
            return
        }

        val validRate = rate.coerceIn(0, 30)
        EconomyManager.setTaxRate(user.country!!, validRate)
        sender.sendMessage("§a成功将国家税率设置为${validRate}%！")
    }

    /**
     * 收取国家税收
     */
    private fun collectTax(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendMessage("§c你不属于任何国家，无法收取税收！")
            return
        }

        if (user.rank.value < 2) { // 只有国王和管理员可以收税
            sender.sendMessage("§c只有国家管理员或所有者才能收取税收！")
            return
        }

        val country = user.country ?: run {
            sender.sendMessage("§c你不属于任何国家，无法收取税收！")
            return
        }

        if (!EconomyManager.canCollectTax(country)) {
            sender.sendMessage("§c距离上次收税时间不足24小时，无法收取税收！")
            return
        }

        val taxAmount = EconomyManager.collectTax(country)
        if (taxAmount <= 0) {
            sender.sendMessage("§c本次收税金额为0，可能是税率设置为0或没有领土！")
            return
        }

        sender.sendMessage("§a成功收取了${taxAmount}单位黄金的税收！国家黄金储备：${country.gold}")
    }

    /**
     * 查看国家税收信息
     */
    private fun taxInfo(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendMessage("§c你不属于任何国家，无法查看税收信息！")
            return
        }

        val country = user.country!!
        val taxRate = EconomyManager.getTaxRate(country)
        val canCollect = EconomyManager.canCollectTax(country)

        sender.sendMessage("§6===== 国家税收信息 =====")
        sender.sendMessage("§a国家名称: §f${country.name}")
        sender.sendMessage("§a当前税率: §f${taxRate}%")
        sender.sendMessage("§a是否可以收税: §f${if (canCollect) "是" else "否"}")
        sender.sendMessage("§a国家黄金储备: §f${country.gold}")
        sender.sendMessage("§a国家钻石储备: §f${country.diamond}")
    }

    /**
     * 向国家上贡资源
     */
    private fun contributeResource(sender: CommandSender, resourceType: String, amount: Int) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        // 检查冷却时间
        if (!CooldownManager.canExecute(sender, CooldownManager.CooldownType.CONTRIBUTE)) {
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendError("你不属于任何国家，无法上贡资源！")
            return
        }

        val country = user.country!!
        val validAmount = amount.coerceIn(1, 64)

        val material = when (resourceType.lowercase()) {
            "gold" -> Material.GOLD_INGOT
            "diamond" -> Material.DIAMOND
            "iron" -> Material.IRON_INGOT
            "food" -> Material.BREAD
            else -> {
                sender.sendError("不支持的资源类型: ${resourceType}")
                sender.sendInfo("支持的资源类型: gold, diamond, iron, food")
                return
            }
        }

        if (!EconomyManager.contributeResource(sender, country, material, validAmount)) {
            sender.sendError("你没有足够的${material.name}来上贡！")
            return
        }

        // 设置冷却时间
        CooldownManager.setCooldown(sender, CooldownManager.CooldownType.CONTRIBUTE)
    }

    /**
     * 分配国家资源给玩家
     */
    private fun distributeResource(sender: CommandSender, resourceType: String, amount: Int, targetPlayer: Player) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendMessage("§c你不属于任何国家，无法分配资源！")
            return
        }

        if (user.rank.value < 2) { // 只有国王和管理员可以分配资源
            sender.sendMessage("§c只有国家管理员或所有者才能分配资源！")
            return
        }

        val targetUser = targetPlayer.user()
        if (targetUser.country?.id != user.country?.id) {
            sender.sendMessage("§c只能分配资源给同一国家的成员！")
            return
        }

        val country = user.country!!
        val validAmount = amount.coerceIn(1, 64)

        var goldAmount = 0
        var diamondAmount = 0

        when (resourceType.lowercase()) {
            "gold" -> goldAmount = validAmount
            "diamond" -> diamondAmount = validAmount
            else -> {
                sender.sendMessage("§c不支持的资源类型: ${resourceType}，只能分配gold或diamond")
                return
            }
        }

        if (!EconomyManager.distributeResources(country, user, goldAmount, diamondAmount)) {
            sender.sendMessage("§c国家资源不足或你没有权限分配资源！")
            return
        }

        sender.sendMessage("§a成功分配了${validAmount}单位${if (goldAmount > 0) "黄金" else "钻石"}给${targetPlayer.name}！")
        targetPlayer.sendMessage("§a你收到了来自国家的${validAmount}单位${if (goldAmount > 0) "黄金" else "钻石"}！")
    }

    // ===== 新增命令实现 =====

    /**
     * 创建疆域地图 (15x15区块)
     */
    private fun createTerritoryMap(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val player = sender

        sender.sendInfo("正在创建疆域地图，请稍候...")

        // 使用RegionScheduler确保在正确的区域执行地图创建
        org.bukkit.Bukkit.getRegionScheduler().execute(cn.lcofficial.guozhan.Guozhan.instance, player.location) {
            try {
                // 创建疆域地图
                val mapView = TerritoryMapUtil.createTerritoryMap(player)
                if (mapView == null) {
                    // 使用GlobalRegionScheduler回到主线程发送消息
                    org.bukkit.Bukkit.getGlobalRegionScheduler().execute(cn.lcofficial.guozhan.Guozhan.instance) {
                        sender.sendError("创建疆域地图失败，请稍后重试")
                        sender.sendInfo("如果问题持续存在，请联系管理员")
                    }
                    return@execute
                }

                // 回到主线程给予地图物品
                org.bukkit.Bukkit.getGlobalRegionScheduler().execute(cn.lcofficial.guozhan.Guozhan.instance) {
                    giveMapToPlayer(player, mapView)
                }

            } catch (e: Exception) {
                cn.lcofficial.guozhan.Guozhan.instance.logger.severe("创建疆域地图时发生异常: ${e.message}")
                e.printStackTrace()

                // 回到主线程发送错误消息
                org.bukkit.Bukkit.getGlobalRegionScheduler().execute(cn.lcofficial.guozhan.Guozhan.instance) {
                    sender.sendError("创建疆域地图时发生错误，请稍后重试")
                }
            }
        }
    }

    /**
     * 给玩家地图物品
     */
    private fun giveMapToPlayer(player: Player, mapView: org.bukkit.map.MapView) {
        try {

        // 创建地图物品
        val mapItem = ItemStack(Material.FILLED_MAP)
        val mapMeta = mapItem.itemMeta as MapMeta
        mapMeta.mapView = mapView
        mapMeta.displayName(Component.text("疆域地图 (15x15)").color(NamedTextColor.GOLD))
        mapMeta.lore(listOf(
            Component.text("显示周围15x15区块的领土状况").color(NamedTextColor.GRAY),
            Component.text(""),
            Component.text("§8■ §7- 无主区域").color(NamedTextColor.GRAY),
            Component.text("§a■ §7- 你的国家").color(NamedTextColor.GREEN),
            Component.text("§c■ §7- 敌对国家").color(NamedTextColor.RED),
            Component.text("§9■ §7- 友好国家").color(NamedTextColor.BLUE),
            Component.text("§e■ §7- 中立国家").color(NamedTextColor.YELLOW),
            Component.text("§f✚ §7- 你的位置").color(NamedTextColor.WHITE),
            Component.text(""),
            Component.text("颜色深浅表示忠诚度高低").color(NamedTextColor.GRAY),
            Component.text("边框样式表示接壤面数").color(NamedTextColor.GRAY),
            Component.text("右键可刷新地图").color(NamedTextColor.YELLOW)
        ))
        mapItem.itemMeta = mapMeta

        // 给玩家地图物品
        val result = player.inventory.addItem(mapItem)
        if (result.isNotEmpty()) {
            // 背包满了，掉落到地上
            player.world.dropItem(player.location, mapItem)
            player.sendSuccess("已获得疆域地图！（背包已满，物品已掉落）")
        } else {
            player.sendSuccess("已获得疆域地图！")
        }

        // 显示地图说明
        player.sendInfo("使用地图查看周围15x15区块的领土状况")
        player.sendInfo("白色十字标记表示你的当前位置")

        } catch (e: Exception) {
            cn.lcofficial.guozhan.Guozhan.instance.logger.severe("给予地图物品时发生异常: ${e.message}")
            e.printStackTrace()
            player.sendError("给予地图物品时发生错误")
        }
    }

    /**
     * 切换占领模式
     */
    private fun toggleClaimMode(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendError("你不属于任何国家！")
            return
        }

        // 切换占领模式
        val newMode = if (user.claimMode == cn.lcofficial.guozhan.data.ClaimMode.AUTO) {
            cn.lcofficial.guozhan.data.ClaimMode.MANUAL
        } else {
            cn.lcofficial.guozhan.data.ClaimMode.AUTO
        }

        user.claimMode = newMode
        user.save()

        // 发送切换成功消息
        val modeText = if (newMode == cn.lcofficial.guozhan.data.ClaimMode.AUTO) "自动" else "手动"
        sender.sendSuccess("已切换到${modeText}占领模式！")

        if (newMode == cn.lcofficial.guozhan.data.ClaimMode.MANUAL) {
            sender.sendInfo("手动模式：手持木斧右键区块进行占领")
            sender.sendInfo("使用 /u claimmode 可以切换回自动模式")
        } else {
            sender.sendInfo("自动模式：使用 /u claim 命令占领区块")
            sender.sendInfo("使用 /u claimmode 可以切换到手动模式")
        }
    }

    /**
     * 查看国库
     */
    private fun viewTreasury(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendMessage("§c你不属于任何国家！")
            return
        }

        val country = user.country ?: run {
            sender.sendMessage("§c你不属于任何国家！")
            return
        }
        sender.sendMessage("§6===== ${country.name} 国库 =====")
        sender.sendMessage("§6黄金储备: §f${country.gold}")
        sender.sendMessage("§b钻石储备: §f${country.diamond}")
        sender.sendMessage("§a核心血量: §f${country.coreHealth}/1000")
    }

    /**
     * 传送到王城
     * 使用Folia调度器，支持受攻击中断
     */
    private fun teleportToCapital(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendMessage("§c你不属于任何国家！")
            return
        }

        val country = user.country!!
        val capitalLocation = country.getCoreLocation()

        if (capitalLocation == null) {
            sender.sendMessage("§c国家核心位置不存在！")
            return
        }

        // 检查玩家是否已经在传送中
        if (TeleportManager.isPlayerTeleporting(sender)) {
            sender.sendMessage("§c你已经在传送中了！")
            return
        }

        sender.sendMessage("§a正在传送到王城，请保持10秒不动且不要受到攻击...")

        // 记录初始位置和开始传送
        val initialLocation = sender.location.clone()

        // 使用Folia的GlobalRegionScheduler进行延迟传送
        val teleportTask = cn.lcofficial.guozhan.util.runLater(200L) { task ->
            // 检查玩家是否还在传送列表中（未被攻击中断）
            if (!TeleportManager.isPlayerTeleporting(sender)) {
                return@runLater // 已被攻击中断
            }

            // 检查玩家是否移动
            if (sender.location.distance(initialLocation) > 1.0) {
                TeleportManager.cancelTeleport(sender)
                sender.sendMessage("§c传送被取消！你在等待期间移动了。")
                return@runLater
            }

            // 执行传送
            TeleportManager.completeTeleport(sender)
            sender.teleportAsync(capitalLocation).thenAccept { success ->
                if (success) {
                    sender.sendMessage("§a欢迎回家！")
                } else {
                    sender.sendMessage("§c传送失败！")
                }
            }
        }

        // 将玩家添加到传送列表
        TeleportManager.startTeleport(sender, teleportTask)
    }

    /**
     * 驱逐国民
     */
    private fun kickMember(sender: CommandSender, playerName: String) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        // 检查冷却时间
        if (!CooldownManager.canExecute(sender, CooldownManager.CooldownType.KICK)) {
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendError("你不属于任何国家！")
            return
        }

        // 检查权限（需要是君主或大臣）
        if (user.rank.value < 2) {
            sender.sendError("只有君主或大臣才能执行此操作！")
            return
        }

        val targetPlayer = Bukkit.getPlayer(playerName) ?: Bukkit.getOfflinePlayer(playerName)
        val targetUser = UserManager.getUser(targetPlayer.uniqueId)

        if (targetUser?.country?.id != user.country?.id) {
            sender.sendMessage("§c该玩家不是你国家的成员！")
            return
        }

        if (targetUser?.rank?.value ?: 0 >= user?.rank?.value ?: 0) {
            sender.sendMessage("§c你不能驱逐比你等级高或相同的成员！")
            return
        }

        // 驱逐成员
        targetUser?.country = null
        targetUser?.save()

        // 设置冷却时间
        CooldownManager.setCooldown(sender, CooldownManager.CooldownType.KICK)

        sender.sendSuccess("成功驱逐了成员 $playerName")

        if (targetPlayer.isOnline && targetPlayer is Player) {
            targetPlayer.sendMessage("§c你已被驱逐出 ${user.country!!.name}！")
        }

        // 广播通知所有在线国家成员
        broadcastToCountryMembers(
            user.country!!,
            "§e${sender.name} §c驱逐了成员 §e$playerName",
            excludePlayer = sender
        )
    }

    /**
     * 设置加入模式
     */
    private fun setJoinMode(sender: CommandSender, mode: String) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendMessage("§c你不属于任何国家！")
            return
        }

        if (user.rank.value < 2) {
            sender.sendMessage("§c只有君主或大臣才能执行此操作！")
            return
        }

        val country = user.country ?: run {
            sender.sendMessage("§c你不属于任何国家！")
            return
        }

        when (mode.lowercase()) {
            "开放" -> {
                country.public = true
                sender.sendMessage("§a国家加入模式已设置为: 开放")
            }
            "仅邀请" -> {
                country.public = false
                sender.sendMessage("§a国家加入模式已设置为: 仅邀请")
            }
            else -> {
                sender.sendMessage("§c无效的模式！请使用 '开放' 或 '仅邀请'")
                return
            }
        }

        country.save()
    }

    /**
     * 切换护盾状态
     */
    private fun toggleShield(sender: CommandSender, action: String, hours: Int) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendMessage("§c你不属于任何国家！")
            return
        }

        if (user.rank.value < 2) {
            sender.sendMessage("§c只有君主或大臣才能执行此操作！")
            return
        }

        val country = user.country!!

        when (action.lowercase()) {
            "on" -> {
                // 使用ShieldManager进行完整的护盾检查
                val checkResult = ShieldManager.canActivateShield(country, hours)
                if (!checkResult.canActivate) {
                    sender.sendMessage("§c[护盾系统] ${checkResult.message}")
                    return
                }

                // 激活护盾
                val success = ShieldManager.activateShield(country, hours)
                if (success) {
                    sender.sendMessage("§a[护盾系统] 国家护盾已成功激活${hours}小时！")
                } else {
                    sender.sendMessage("§c[护盾系统] 护盾激活失败，请稍后重试")
                }
            }
            "off" -> {
                if (!ShieldManager.isShieldActive(country)) {
                    sender.sendMessage("§c[护盾系统] 国家护盾未激活！")
                    return
                }

                ShieldManager.deactivateShield(country)
                sender.sendMessage("§a[护盾系统] 国家护盾已手动关闭！")
            }
            "status", "info" -> {
                // 显示护盾状态信息
                if (ShieldManager.isShieldActive(country)) {
                    val remainingTime = ShieldManager.getShieldRemainingTime(country)
                    val formattedTime = ShieldManager.formatRemainingTime(remainingTime)
                    sender.sendMessage("§a[护盾系统] 护盾状态：§f激活中")
                    sender.sendMessage("§a[护盾系统] 剩余时间：§f$formattedTime")
                } else {
                    sender.sendMessage("§c[护盾系统] 护盾状态：§f未激活")

                    // 显示冷却信息
                    val cooldownResult = ShieldManager.canActivateShield(country, 1)
                    if (!cooldownResult.canActivate && cooldownResult.message.contains("冷却")) {
                        sender.sendMessage("§e[护盾系统] ${cooldownResult.message}")
                    }
                }
            }
            else -> {
                sender.sendMessage("§c[护盾系统] 无效的操作！")
                sender.sendMessage("§e用法：")
                sender.sendMessage("§f/u shield on <小时数> §7- 激活护盾")
                sender.sendMessage("§f/u shield off §7- 关闭护盾")
                sender.sendMessage("§f/u shield status §7- 查看护盾状态")
            }
        }
    }

    /**
     * 解散国家（第一步：显示警告和确认信息）
     */
    private fun disbandCountry(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendError("你不属于任何国家！")
            return
        }

        if (user.rank.value < 3) { // 只有君主才能解散国家
            sender.sendError("只有君主才能解散国家！")
            return
        }

        // 检查冷却时间
        if (!CooldownManager.canExecute(sender, CooldownManager.CooldownType.DISBAND)) {
            return
        }

        val country = user.country!!

        // 设置确认状态
        val playerConfirmations = pendingConfirmations.computeIfAbsent(sender.uniqueId) { ConcurrentHashMap() }
        playerConfirmations["disband"] = country.name

        // 显示警告信息
        sender.sendMessage("§c§l=== 解散国家警告 ===")
        sender.sendMessage("§c§l警告：此操作将永久解散国家 '${country.name}'！")
        sender.sendMessage("§c这将导致：")
        sender.sendMessage("§c• 所有国家成员失去国籍")
        sender.sendMessage("§c• 所有领土被释放")
        sender.sendMessage("§c• 国库资源全部丢失")
        sender.sendMessage("§c• 国家数据永久删除")
        sender.sendMessage("§e")
        sender.sendMessage("§e请在30秒内输入 §f/u disband confirm §e来确认解散")
        sender.sendMessage("§7如果不想解散，请忽略此消息")

        // 30秒后自动清除确认状态
        Guozhan.instance.server.globalRegionScheduler.runDelayed(Guozhan.instance, { _ ->
            val confirmations = pendingConfirmations[sender.uniqueId]
            if (confirmations?.remove("disband") != null) {
                if (confirmations.isEmpty()) {
                    pendingConfirmations.remove(sender.uniqueId)
                }
                if (sender.isOnline) {
                    sender.sendInfo("§7解散确认已超时取消")
                }
            }
        }, 20 * 30) // 30秒
    }

    /**
     * 确认解散国家（第二步：执行解散操作）
     */
    private fun confirmDisbandCountry(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendError("你不属于任何国家！")
            return
        }

        if (user.rank.value < 3) {
            sender.sendError("只有君主才能解散国家！")
            return
        }

        // 检查确认状态
        val playerConfirmations = pendingConfirmations[sender.uniqueId]
        val countryName = playerConfirmations?.get("disband")
        if (countryName == null) {
            sender.sendError("没有待确认的解散操作！请先使用 /u disband")
            return
        }

        val country = user.country!!
        if (country.name != countryName) {
            sender.sendError("确认信息不匹配！请重新使用 /u disband")
            playerConfirmations.remove("disband")
            return
        }

        try {
            transaction {
                // 执行解散操作
                executeDisbandCountry(country, sender)

                // 清除确认状态
                playerConfirmations.remove("disband")
                if (playerConfirmations.isEmpty()) {
                    pendingConfirmations.remove(sender.uniqueId)
                }

                // 设置冷却时间
                CooldownManager.setCooldown(sender, CooldownManager.CooldownType.DISBAND)
            }
        } catch (e: Exception) {
            sender.sendError("解散国家时发生错误: ${e.message}")
            Guozhan.instance.logger.warning("解散国家失败: ${e.message}")
        }
    }

    /**
     * 执行解散国家的具体操作
     */
    private fun executeDisbandCountry(country: Country, disbander: Player) {
        val countryName = country.name
        val memberCount = country.members.size

        // 1. 通知所有国家成员
        val members = country.members.toList() // 创建副本避免并发修改
        members.forEach { member ->
            val player = Bukkit.getPlayer(member.uniqueId)
            if (player != null && player.isOnline) {
                player.sendMessage("§c§l国家 '${countryName}' 已被君主解散！")
                player.sendMessage("§e你现在是自由民，可以加入其他国家或创建新国家")
            }
        }

        // 2. 清除所有成员的国家关联
        members.forEach { member ->
            member.country = null
            member.rank = Rank.DEFAULT
            member.title = "国民"
            member.save()
        }

        // 3. 释放所有领土
        val territories = TerritoryManager.getTerritoriesByCountry(country)
        territories.forEach { territory ->
            territory.owner = null
            territory.loyalty = 0
            territory.save()
        }

        // 4. 删除国家数据
        CountryManager.deleteCountry(country)

        // 5. 全服广播
        Bukkit.broadcastMessage("§c§l[国战] 国家 '${countryName}' 已被解散！")
        Bukkit.broadcastMessage("§e该国家的 ${memberCount} 名成员现在是自由民")
        Bukkit.broadcastMessage("§e该国家的 ${territories.size} 块领土已被释放")

        // 6. 服务器日志
        Guozhan.instance.logger.info("[解散国家] ${disbander.name} 解散了国家 '${countryName}'，释放了 ${territories.size} 块领土，影响 ${memberCount} 名玩家")

        disbander.sendSuccess("国家 '${countryName}' 已成功解散！")
    }

    /**
     * 册封大臣
     */
    private fun promoteMember(sender: CommandSender, playerName: String) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendMessage("§c你不属于任何国家！")
            return
        }

        if (user.rank.value < 3) { // 只有君主才能册封大臣
            sender.sendMessage("§c只有君主才能册封大臣！")
            return
        }

        val targetPlayer = Bukkit.getPlayer(playerName) ?: Bukkit.getOfflinePlayer(playerName)
        val targetUser = UserManager.getUser(targetPlayer.uniqueId)

        if (targetUser?.country?.id != user.country?.id) {
            sender.sendMessage("§c该玩家不是你国家的成员！")
            return
        }

        if (targetUser?.rank?.value ?: 0 >= 2) {
            sender.sendMessage("§c该玩家已经是大臣或更高职位！")
            return
        }

        // 册封为大臣
        targetUser!!.rank = cn.lcofficial.guozhan.data.Rank.ADMIN
        targetUser.save()

        sender.sendMessage("§a成功册封 $playerName 为大臣！")

        if (targetPlayer.isOnline && targetPlayer is Player) {
            targetPlayer.sendMessage("§a恭喜！你已被册封为 ${user.country!!.name} 的大臣！")
        }

        // 广播通知所有在线国家成员
        broadcastToCountryMembers(
            user.country!!,
            "§e${sender.name} §a册封 §e$playerName §a为大臣",
            excludePlayer = sender
        )
    }

    /**
     * 罢免大臣
     */
    private fun demoteMember(sender: CommandSender, playerName: String) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendMessage("§c你不属于任何国家！")
            return
        }

        if (user.rank.value < 3) { // 只有君主才能罢免大臣
            sender.sendMessage("§c只有君主才能罢免大臣！")
            return
        }

        val targetPlayer = Bukkit.getPlayer(playerName) ?: Bukkit.getOfflinePlayer(playerName)
        val targetUser = UserManager.getUser(targetPlayer.uniqueId)

        if (targetUser?.country?.id != user.country?.id) {
            sender.sendMessage("§c该玩家不是你国家的成员！")
            return
        }

        if (targetUser?.rank?.value ?: 0 < 2) {
            sender.sendMessage("§c该玩家不是大臣！")
            return
        }

        if (targetUser?.rank?.value ?: 0 >= 3) {
            sender.sendMessage("§c不能罢免君主！")
            return
        }

        // 罢免大臣
        targetUser!!.rank = cn.lcofficial.guozhan.data.Rank.DEFAULT
        targetUser.save()

        sender.sendMessage("§a成功罢免了大臣 $playerName！")

        if (targetPlayer.isOnline && targetPlayer is Player) {
            targetPlayer.sendMessage("§c你已被罢免大臣职位！")
        }

        // 广播通知所有在线国家成员
        broadcastToCountryMembers(
            user.country!!,
            "§e${sender.name} §c罢免了大臣 §e$playerName",
            excludePlayer = sender
        )
    }

    /**
     * 科技系统相关方法
     */
    private fun openTechnologyGUI(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendError("只有玩家才能打开科技界面")
            return
        }

        val user = sender.user()
        val country = user.country
        if (country == null) {
            sender.sendNoCountryError()
            return
        }

        // 暂时使用文本界面，后续实现GUI
        showTechnologyList(sender)
    }

    private fun researchTechnology(sender: CommandSender, technologyId: String, confirm: Boolean = false) {
        if (sender !is Player) {
            sender.sendError("只有玩家才能研究科技")
            return
        }

        val user = sender.user()
        val country = user.country
        if (country == null) {
            sender.sendNoCountryError()
            return
        }

        // 检查权限（君主或大臣）
        if (user.rank.value < 2) {
            sender.sendError("只有君主或大臣才能研究科技")
            return
        }

        val technology = TechnologyManager.getTechnology(technologyId)
        if (technology == null) {
            sender.sendError("科技 '$technologyId' 不存在")
            return
        }

        if (!TechnologyManager.canResearchTechnology(country, technologyId)) {
            sender.sendError("无法研究科技 '${technology.name}'")

            val currentLevel = TechnologyManager.getCountryTechLevel(country, technologyId)
            if (currentLevel >= technology.maxLevel) {
                sender.sendInfo("该科技已达到最高等级")
            } else if (TechnologyManager.isResearching(country, technologyId)) {
                sender.sendInfo("该科技正在研究中")
            } else {
                // 显示详细的资源需求和缺失信息
                showDetailedResourceRequirements(sender, country, technology, currentLevel + 1)
            }
            return
        }

        // 如果没有确认，显示详细信息并要求确认
        if (!confirm) {
            val targetLevel = TechnologyManager.getCountryTechLevel(country, technologyId) + 1
            val cost = technology.getCost(targetLevel)!!

            sender.sendInfo("§e=== 科技研究确认 ===")
            sender.sendInfo("§f科技名称: §b${technology.name}")
            sender.sendInfo("§f目标等级: §a$targetLevel")

            // 显示资源消耗
            var totalGoldCost = cost.gold
            if (cost.territoryIncome > 0) {
                val hourlyIncome = RegionalTaxSystem.calculateTotalGoldTaxPerHour(country)
                val additionalCost = kotlin.math.ceil(cost.territoryIncome * hourlyIncome).toInt()
                totalGoldCost += additionalCost

                sender.sendInfo("§f将消耗资源:")
                sender.sendInfo("§e  - 金币: $totalGoldCost")
                if (additionalCost > 0) {
                    sender.sendInfo("§7    (基础: ${cost.gold} + 领土收入: $additionalCost)")
                }
            } else {
                sender.sendInfo("§f将消耗资源:")
                sender.sendInfo("§e  - 金币: ${cost.gold}")
            }

            if (cost.diamond > 0) {
                sender.sendInfo("§b  - 钻石: ${cost.diamond}")
            }

            // 显示研究时间
            val researchTime = cn.lcofficial.guozhan.config.TechnologyConfig.calculateResearchTime(technology, targetLevel)
            val hours = researchTime / (1000 * 60 * 60)
            val minutes = (researchTime % (1000 * 60 * 60)) / (1000 * 60)
            sender.sendInfo("§f研究时间: §a${hours}小时${minutes}分钟")

            // 显示效果预览
            val effects = technology.getEffects(targetLevel)
            if (effects.isNotEmpty()) {
                sender.sendInfo("§f研究完成后将获得:")
                effects.forEach { effect ->
                    sender.sendMessage("§a  + ${effect.getDescription()}")
                }
            }

            sender.sendMessage("")
            sender.sendWarning("§c请确认是否开始研究？")
            sender.sendInfo("§f使用 §e/u tech research $technologyId confirm §f确认开始研究")
            sender.sendInfo("§7此操作将立即消耗上述资源，请谨慎确认")
            return
        }

        if (TechnologyManager.startResearch(country, technologyId)) {
            val targetLevel = TechnologyManager.getCountryTechLevel(country, technologyId) + 1
            sender.sendSuccess("成功开始研究科技 '${technology.name}' 等级 $targetLevel")

            // 计算研究时间
            val researchTime = cn.lcofficial.guozhan.config.TechnologyConfig.calculateResearchTime(technology, targetLevel)
            val hours = researchTime / (1000 * 60 * 60)
            val minutes = (researchTime % (1000 * 60 * 60)) / (1000 * 60)
            sender.sendInfo("预计完成时间: ${hours}小时${minutes}分钟")

            // 显示研究完成后的效果预览
            val effects = technology.getEffects(targetLevel)
            if (effects.isNotEmpty()) {
                sender.sendInfo("研究完成后将获得以下效果:")
                effects.forEach { effect ->
                    sender.sendMessage("§f  - ${effect.getDescription()}")
                }
            }
        } else {
            sender.sendError("开始研究失败，请稍后重试")
        }
    }

    private fun showTechnologyInfo(sender: CommandSender, technologyId: String) {
        val technology = TechnologyManager.getTechnology(technologyId)
        if (technology == null) {
            sender.sendError("科技 '$technologyId' 不存在")
            return
        }

        sender.sendInfo("=== 科技信息: ${technology.name} ===")
        sender.sendMessage("§7描述: ${technology.description}")
        sender.sendMessage("§7分类: ${technology.category}")
        sender.sendMessage("§7最大等级: ${technology.maxLevel}")

        if (technology.prerequisites.isNotEmpty()) {
            sender.sendMessage("§c前置科技:")
            technology.prerequisites.forEach { prereqId ->
                val prereqTech = TechnologyManager.getTechnology(prereqId)
                sender.sendMessage("§f  - ${prereqTech?.name ?: prereqId}")
            }
        }

        // 显示各等级信息
        for (level in 1..technology.maxLevel) {
            sender.sendMessage("§e=== 等级 $level ===")

            val cost = technology.getCost(level)
            if (cost != null) {
                sender.sendMessage("§6成本:")
                sender.sendMessage("§f  金币: ${cost.gold}")
                sender.sendMessage("§f  钻石: ${cost.diamond}")
                sender.sendMessage("§f  领土收入: ${cost.territoryIncome}小时")
            }

            val effects = technology.getEffects(level)
            if (effects.isNotEmpty()) {
                sender.sendMessage("§a效果:")
                effects.forEach { effect ->
                    sender.sendMessage("§f  - ${effect.getDescription()}")
                }
            }
        }

        // 如果玩家有国家，显示当前研究状态
        if (sender is Player) {
            val user = sender.user()
            val country = user.country
            if (country != null) {
                val currentLevel = TechnologyManager.getCountryTechLevel(country, technologyId)
                val countryTech = TechnologyManager.getCountryTechnology(country, technologyId)

                sender.sendMessage("§b=== 你的国家状态 ===")
                sender.sendMessage("§f当前等级: $currentLevel/${technology.maxLevel}")

                if (countryTech?.isResearching == true) {
                    val progress = (countryTech.getResearchProgress() * 100).toInt()
                    val remaining = countryTech.getRemainingResearchTime()
                    val remainingHours = remaining / (1000 * 60 * 60)
                    val remainingMinutes = (remaining % (1000 * 60 * 60)) / (1000 * 60)

                    sender.sendMessage("§e正在研究中... 进度: ${progress}%")
                    sender.sendMessage("§e剩余时间: ${remainingHours}小时${remainingMinutes}分钟")
                }
            }
        }
    }

    private fun showTechnologyList(sender: CommandSender) {
        val technologies = TechnologyManager.getAllTechnologies()

        if (technologies.isEmpty()) {
            sender.sendWarning("当前没有可用的科技")
            return
        }

        sender.sendInfo("=== 科技列表 ===")

        // 按分类分组显示
        val categorizedTechs = technologies.groupBy { it.category }

        categorizedTechs.forEach { (category, techs) ->
            sender.sendMessage("§e=== $category ===")

            techs.forEach { tech ->
                val statusIcon = if (sender is Player) {
                    val user = sender.user()
                    val country = user.country
                    if (country != null) {
                        val level = TechnologyManager.getCountryTechLevel(country, tech.id)
                        val isResearching = TechnologyManager.isResearching(country, tech.id)
                        when {
                            level >= tech.maxLevel -> "§a✓"
                            isResearching -> "§e⚡"
                            level > 0 -> "§6◐"
                            else -> "§7○"
                        }
                    } else "§7○"
                } else "§7○"

                sender.sendMessage("§f  $statusIcon ${tech.name} §7(${tech.id})")
                sender.sendMessage("§7    ${tech.description}")
            }
        }

        sender.sendMessage("")
        sender.sendInfo("图例: §a✓§7已满级 §6◐§7已研究 §e⚡§7研究中 §7○§7未研究")
        sender.sendInfo("使用 §f/u tech info <科技ID> §b查看详细信息")
        sender.sendInfo("使用 §f/u tech research <科技ID> §b开始研究")
    }

    /**
     * 显示详细的资源需求和缺失信息
     */
    private fun showDetailedResourceRequirements(sender: CommandSender, country: Country, technology: Technology, targetLevel: Int) {
        val cost = technology.getCost(targetLevel)
        if (cost == null) {
            sender.sendError("无法获取科技成本信息")
            return
        }

        sender.sendError("§c资源不足！需要：")

        // 检查金币需求
        var totalGoldCost = cost.gold
        if (cost.territoryIncome > 0) {
            val hourlyIncome = RegionalTaxSystem.calculateTotalGoldTaxPerHour(country)
            val additionalCost = kotlin.math.ceil(cost.territoryIncome * hourlyIncome).toInt()
            totalGoldCost += additionalCost

            if (country.gold < totalGoldCost) {
                val missing = totalGoldCost - country.gold
                sender.sendMessage("§e金币: $totalGoldCost (当前: ${country.gold}, 缺少: $missing)")
                if (additionalCost > 0) {
                    sender.sendMessage("§7  - 基础成本: ${cost.gold}")
                    sender.sendMessage("§7  - 领土收入成本: $additionalCost (${cost.territoryIncome}小时 × ${String.format("%.3f", hourlyIncome)})")
                }
            } else {
                sender.sendMessage("§a金币: $totalGoldCost ✓")
            }
        } else {
            if (country.gold < cost.gold) {
                val missing = cost.gold - country.gold
                sender.sendMessage("§e金币: ${cost.gold} (当前: ${country.gold}, 缺少: $missing)")
            } else {
                sender.sendMessage("§a金币: ${cost.gold} ✓")
            }
        }

        // 检查钻石需求
        if (cost.diamond > 0) {
            if (country.diamond < cost.diamond) {
                val missing = cost.diamond - country.diamond
                sender.sendMessage("§b钻石: ${cost.diamond} (当前: ${country.diamond}, 缺少: $missing)")
            } else {
                sender.sendMessage("§a钻石: ${cost.diamond} ✓")
            }
        }

        // 检查前置科技
        val prerequisites = technology.prerequisites
        if (prerequisites.isNotEmpty()) {
            sender.sendMessage("§6前置科技要求:")
            prerequisites.forEach { prereqId ->
                val currentLevel = TechnologyManager.getCountryTechLevel(country, prereqId)
                val prereqTech = TechnologyManager.getTechnology(prereqId)
                val techName = prereqTech?.name ?: prereqId

                if (currentLevel < 1) {
                    sender.sendMessage("§c  - $techName: 需要研究 (当前: 未研究)")
                } else {
                    sender.sendMessage("§a  - $techName: 已研究 ✓")
                }
            }
        }
    }

    private fun restoreLoyalty(sender: CommandSender, confirm: Boolean) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendNoCountryError()
            return
        }

        if (user.rank.value < 2) {
            sender.sendError("只有君主或大臣才能恢复疆土民心！")
            return
        }

        val country = user.country!!

        if (!confirm) {
            // 计算恢复成本
            val territories = TerritoryManager.getTerritoriesByCountry(country)
            val damagedTerritories = territories.filter { it.loyalty < 100 }

            if (damagedTerritories.isEmpty()) {
                sender.sendInfo("所有疆土的民心都是满的，无需恢复！")
                return
            }

            val totalCost = damagedTerritories.size * 50 // 每块受损领土50金币
            sender.sendMessage("§e=== 恢复疆土民心 ===")
            sender.sendMessage("§f受损疆土数量: §c${damagedTerritories.size}块")
            sender.sendMessage("§f恢复费用: §6${totalCost}金币")
            sender.sendMessage("§f当前国库: §6${country.gold}金币")
            sender.sendMessage("§e请使用 '/u restore confirm' 确认恢复所有疆土民心")
            return
        }

        // v1.3.13修复：实现真正的恢复逻辑
        val territories = TerritoryManager.getTerritoriesByCountry(country)
        val damagedTerritories = territories.filter { it.loyalty < 100 }

        if (damagedTerritories.isEmpty()) {
            sender.sendInfo("所有疆土的民心都是满的，无需恢复！")
            return
        }

        val totalCost = damagedTerritories.size * 50

        if (country.gold < totalCost) {
            sender.sendError("国库金币不足！需要 ${totalCost} 金币，当前只有 ${country.gold} 金币")
            return
        }

        // 扣除费用
        country.gold -= totalCost
        country.save()

        // 恢复所有受损领土的忠诚度
        var restoredCount = 0
        damagedTerritories.forEach { territory ->
            territory.loyalty = 100
            territory.save()
            restoredCount++
        }

        sender.sendSuccess("成功恢复了 ${restoredCount} 块疆土的民心！")
        sender.sendInfo("消耗了 ${totalCost} 金币，剩余国库: ${country.gold} 金币")

        // 记录日志
        Guozhan.instance.logger.info("[忠诚度系统] ${sender.name} 为国家 ${country.name} 恢复了 ${restoredCount} 块疆土的民心，消耗 ${totalCost} 金币")
    }

    /**
     * 邀请玩家加入国家
     */
    private fun invitePlayer(sender: CommandSender, playerName: String) {
        if (sender !is Player) {
            sender.sendError("只有玩家才能邀请其他玩家")
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendError("你不属于任何国家！")
            return
        }

        // 检查权限（需要是君主或大臣）
        if (user.rank.value < 2) {
            sender.sendError("只有君主或大臣才能邀请新成员！")
            plugin.logger.warning("[权限检查] 玩家 ${sender.name} (rank=${user.rank}) 尝试邀请 $playerName 但权限不足")
            return
        }

        val targetPlayer = Bukkit.getPlayer(playerName)
        if (targetPlayer == null) {
            sender.sendError("玩家 $playerName 不在线！")
            return
        }

        val targetUser = targetPlayer.user()
        if (targetUser.country != null) {
            sender.sendError("玩家 $playerName 已经属于国家 ${targetUser.country!!.name}！")
            return
        }

        // 检查是否已有待处理的邀请
        if (pendingInvitations.containsKey(targetPlayer.uniqueId)) {
            sender.sendError("玩家 $playerName 已有待处理的邀请！")
            return
        }

        // 创建邀请
        val invitation = CountryInvitation(
            inviterUuid = sender.uniqueId,
            inviterName = sender.name,
            countryId = user.country!!.id,
            countryName = user.country!!.name,
            inviteTime = System.currentTimeMillis(),
            expiryTime = System.currentTimeMillis() + 5 * 60 * 1000L // 5分钟后过期
        )

        pendingInvitations[targetPlayer.uniqueId] = invitation

        // 通知邀请者
        sender.sendSuccess("已向 $playerName 发送加入邀请")
        sender.sendInfo("邀请将在5分钟后自动过期")

        // 通知被邀请者
        targetPlayer.sendInfo("§6=== 国家邀请 ===")
        targetPlayer.sendInfo("§f${sender.name} §b邀请你加入国家 §e${user.country!!.name}")
        targetPlayer.sendInfo("§f使用 §a/u accept §f接受邀请")
        targetPlayer.sendInfo("§f使用 §c/u decline §f拒绝邀请")
        targetPlayer.sendWarning("§e邀请将在5分钟后自动过期")

        // 服务器日志记录
        plugin.logger.info("[邀请系统] ${sender.name} (${user.country!!.name}) 邀请 $playerName 加入国家")

        // 设置5分钟后自动过期
        Guozhan.instance.server.globalRegionScheduler.runDelayed(Guozhan.instance, { _ ->
            val currentInvitation = pendingInvitations[targetPlayer.uniqueId]
            if (currentInvitation == invitation) {
                pendingInvitations.remove(targetPlayer.uniqueId)
                if (targetPlayer.isOnline) {
                    targetPlayer.sendWarning("来自 ${user.country!!.name} 的邀请已过期")
                }
                if (sender.isOnline) {
                    sender.sendInfo("对 $playerName 的邀请已过期")
                }
            }
        }, 20 * 60 * 5) // 5分钟
    }

    /**
     * 接受国家邀请
     */
    private fun acceptInvitation(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendError("只有玩家才能接受邀请")
            return
        }

        // 调试日志
        plugin.logger.info("[邀请系统] 玩家 ${sender.name} 尝试接受邀请")
        plugin.logger.info("[邀请系统] 当前待处理邀请数量: ${pendingInvitations.size}")

        val invitation = pendingInvitations[sender.uniqueId]
        if (invitation == null) {
            sender.sendError("你没有待处理的邀请！")
            plugin.logger.warning("[邀请系统] 玩家 ${sender.name} 没有待处理的邀请")
            return
        }

        if (invitation.isExpired()) {
            pendingInvitations.remove(sender.uniqueId)
            sender.sendError("邀请已过期！")
            return
        }

        val user = sender.user()
        if (user.country != null) {
            sender.sendError("你已经属于国家 ${user.country!!.name}！")
            return
        }

        val country = CountryManager.getCountry(invitation.countryId)
        if (country == null) {
            pendingInvitations.remove(sender.uniqueId)
            sender.sendError("邀请的国家不存在！")
            return
        }

        // 加入国家
        user.country = country
        user.rank = Rank.DEFAULT
        user.save()

        // 清除邀请
        pendingInvitations.remove(sender.uniqueId)

        // 服务器日志记录
        plugin.logger.info("[邀请系统] 玩家 ${sender.name} 成功加入国家 ${country.name}，rank设置为 ${user.rank}")

        // 通知新成员
        sender.sendSuccess("成功加入国家 ${country.name}！")
        sender.sendInfo("你的爵位是：国民")
        sender.sendInfo("使用 /u info 查看国家信息")

        // 通知邀请者
        val inviter = Bukkit.getPlayer(invitation.inviterUuid)
        if (inviter != null && inviter.isOnline) {
            inviter.sendSuccess("${sender.name} 接受了邀请，加入了国家！")
        }

        // 通知所有在线国家成员
        country.members.forEach { member ->
            val memberPlayer = Bukkit.getPlayer(member.uniqueId)
            if (memberPlayer != null && memberPlayer != sender && memberPlayer != inviter) {
                memberPlayer.sendInfo("§e${sender.name} §b加入了国家")
            }
        }
    }

    /**
     * 拒绝国家邀请
     */
    private fun declineInvitation(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendError("只有玩家才能拒绝邀请")
            return
        }

        val invitation = pendingInvitations[sender.uniqueId]
        if (invitation == null) {
            sender.sendError("你没有待处理的邀请！")
            return
        }

        // 清除邀请
        pendingInvitations.remove(sender.uniqueId)

        // 通知被邀请者
        sender.sendInfo("已拒绝来自 ${invitation.countryName} 的邀请")

        // 通知邀请者
        val inviter = Bukkit.getPlayer(invitation.inviterUuid)
        if (inviter != null && inviter.isOnline) {
            inviter.sendWarning("${sender.name} 拒绝了加入邀请")
        }
    }

    private fun setDeclaration(sender: CommandSender, declaration: String) {
        sender.sendMessage("§a成功设置国家宣言: $declaration")
    }

    private fun moveCapital(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        // 检查冷却时间
        if (!CooldownManager.canExecute(sender, CooldownManager.CooldownType.MOVE)) {
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendError("你不属于任何国家！")
            return
        }

        val country = user.country!!

        // 检查权限（只有君主可以迁移王城）
        if (user.rank != Rank.OWNER && !sender.hasPermission("guozhan.admin.force.move")) {
            sender.sendError("只有君主才能迁移王城！")
            return
        }

        val location = sender.location

        // 检查Y轴限制（64-300之间）
        if (location.y < 64 || location.y > 300) {
            sender.sendError("王城只能建立在Y轴64-300之间！")
            sender.sendInfo("当前位置Y轴: ${location.y.toInt()}")
            return
        }

        // 检查是否在国家领土内
        val chunk = location.chunk
        val territory = TerritoryManager.getTerritoryBlock(chunk.x, chunk.z, location.world.name)

        if (territory?.owner?.id != country.id) {
            sender.sendError("王城只能迁移到本国领土内！")
            return
        }

        // 检查资源消耗（GM可绕过）
        val moveCost = 1000 // 1000金币
        if (!sender.hasPermission("guozhan.admin.bypass.cost")) {
            if (country.gold < moveCost) {
                sender.sendError("迁移王城需要${moveCost}金币，国库余额不足！")
                sender.sendInfo("当前国库: ${country.gold}金币")
                return
            }
        }

        // 执行迁移
        val oldCapital = "${country.capital.x}, ${country.capital.z}"

        // 更新核心位置（Country使用核心位置作为王城位置）
        country.setCoreLocation(location)

        // 扣除资源（GM可绕过）
        if (!sender.hasPermission("guozhan.admin.bypass.cost")) {
            country.gold -= moveCost
            sender.sendInfo("消耗了${moveCost}金币")
        } else {
            sender.sendInfo("§7[管理员] 已绕过资源消耗")
        }

        country.save()

        // 设置冷却时间
        CooldownManager.setCooldown(sender, CooldownManager.CooldownType.MOVE)

        sender.sendSuccess("成功将王城迁移到当前位置！")
        sender.sendInfo("原王城位置: $oldCapital")
        sender.sendInfo("新王城位置: ${location.x.toInt()}, ${location.y.toInt()}, ${location.z.toInt()}")

        // 通知国家成员
        val members = country.members
        members.forEach { member ->
            val player = Bukkit.getPlayer(member.uniqueId)
            if (player != null && player != sender) {
                player.sendInfo("君主${sender.name}将王城迁移到了新位置！")
                player.sendInfo("使用 /u return 可以传送到新王城")
            }
        }
    }

    private fun renameCountry(sender: CommandSender, newName: String) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendError("你不属于任何国家！")
            return
        }

        val country = user.country!!

        // 检查权限（只有君主可以改名，或GM强制改名）
        if (user.rank != Rank.OWNER && !sender.hasPermission("guozhan.admin.force.rename")) {
            sender.sendError("只有君主才能更改国家名称！")
            return
        }

        // 验证名称合法性
        if (!isValidCountryName(newName)) {
            sender.sendError("国家名称不合法！")
            sender.sendInfo("名称要求：3-12个字符，仅支持中英文和数字，不能包含空格和特殊字符")
            return
        }

        // 检查名称是否已被占用
        if (CountryManager.getByName(newName) != null) {
            sender.sendError("国家名称 '$newName' 已被其他国家使用！")
            return
        }

        // 检查资源消耗（GM可绕过）
        val renameCost = 500 // 500金币
        if (!sender.hasPermission("guozhan.admin.bypass.cost")) {
            if (country.gold < renameCost) {
                sender.sendError("更改国家名称需要${renameCost}金币，国库余额不足！")
                sender.sendInfo("当前国库: ${country.gold}金币")
                return
            }
        }

        val oldName = country.name

        // 执行改名
        country.name = newName

        // 扣除资源（GM可绕过）
        if (!sender.hasPermission("guozhan.admin.bypass.cost")) {
            country.gold -= renameCost
            sender.sendInfo("消耗了${renameCost}金币")
        } else {
            sender.sendInfo("§7[管理员] 已绕过资源消耗")
        }

        country.save()

        sender.sendSuccess("成功将国家名称从 '$oldName' 更改为 '$newName'！")

        // 通知国家成员
        val members = country.members
        members.forEach { member ->
            val player = Bukkit.getPlayer(member.uniqueId)
            if (player != null && player != sender) {
                player.sendInfo("君主${sender.name}将国家名称更改为 '$newName'！")
            }
        }

        // 广播改名消息
        Bukkit.broadcastMessage("§6国家 '$oldName' 已更名为 '$newName'！")
    }

    /**
     * 验证国家名称是否合法
     */
    private fun isValidCountryName(name: String): Boolean {
        // 长度检查：3-12字符
        if (name.length < 3 || name.length > 12) {
            return false
        }

        // 字符检查：只允许中英文、数字
        val validPattern = Regex("^[\\u4e00-\\u9fa5a-zA-Z0-9]+$")
        return validPattern.matches(name)
    }

    private fun transferOwnership(sender: CommandSender, playerName: String) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendError("你不属于任何国家！")
            return
        }

        val country = user.country!!

        // 检查权限（只有君主可以禅让，或GM强制转移）
        if (user.rank != Rank.OWNER && !sender.hasPermission("guozhan.admin.force.transfer")) {
            sender.sendError("只有君主才能禅让君主之位！")
            return
        }

        // 处理确认机制
        if (playerName.equals("confirm", ignoreCase = true)) {
            val playerConfirmations = pendingConfirmations[sender.uniqueId]
            val targetPlayerName = playerConfirmations?.get("transfer")

            if (targetPlayerName == null) {
                sender.sendError("没有待确认的禅让操作！")
                return
            }

            // 执行禅让
            executeTransfer(sender, targetPlayerName)

            // 清除确认状态
            playerConfirmations.remove("transfer")
            if (playerConfirmations.isEmpty()) {
                pendingConfirmations.remove(sender.uniqueId)
            }
            return
        }

        // 查找目标玩家
        val targetPlayer = Bukkit.getPlayer(playerName) ?: Bukkit.getOfflinePlayer(playerName)
        val targetUser = UserManager.getUser(targetPlayer.uniqueId)

        if (targetUser?.country?.id != country.id) {
            sender.sendError("目标玩家不是你国家的成员！")
            return
        }

        if (targetUser.rank == Rank.OWNER) {
            sender.sendError("目标玩家已经是君主了！")
            return
        }

        // 设置确认状态
        val playerConfirmations = pendingConfirmations.computeIfAbsent(sender.uniqueId) { ConcurrentHashMap() }
        playerConfirmations["transfer"] = playerName

        sender.sendWarning("§c警告：你即将将君主之位禅让给 $playerName！")
        sender.sendWarning("§c这个操作不可撤销！")
        sender.sendInfo("§e请在30秒内输入 §f/u transfer confirm §e来确认禅让")
        sender.sendInfo("§7如果不想禅让，请忽略此消息")

        // 30秒后自动清除确认状态
        Guozhan.instance.server.globalRegionScheduler.runDelayed(Guozhan.instance, { _ ->
            val confirmations = pendingConfirmations[sender.uniqueId]
            if (confirmations?.remove("transfer") != null) {
                if (confirmations.isEmpty()) {
                    pendingConfirmations.remove(sender.uniqueId)
                }
                if (sender.isOnline) {
                    sender.sendInfo("§7禅让确认已超时取消")
                }
            }
        }, 20 * 30) // 30秒
    }

    /**
     * 执行君主禅让
     */
    private fun executeTransfer(sender: Player, targetPlayerName: String) {
        val user = sender.user()
        val country = user.country!!

        val targetPlayer = Bukkit.getPlayer(targetPlayerName) ?: Bukkit.getOfflinePlayer(targetPlayerName)
        val targetUser = UserManager.getUser(targetPlayer.uniqueId)!!

        val oldOwnerName = sender.name
        val newOwnerName = targetUser.name

        // 执行权限转移
        user.rank = Rank.ADMIN // 原君主变为大臣
        targetUser.rank = Rank.OWNER // 目标玩家变为君主

        user.save()
        targetUser.save()

        sender.sendSuccess("成功将君主之位禅让给 $newOwnerName！")
        sender.sendInfo("你现在是国家的大臣")

        // 通知新君主
        if (targetPlayer.isOnline && targetPlayer is Player) {
            targetPlayer.sendSuccess("恭喜！你已成为 ${country.name} 的新君主！")
            targetPlayer.sendInfo("使用 /u help 查看君主专属命令")
        }

        // 通知国家成员
        val members = country.members
        members.forEach { member ->
            val player = Bukkit.getPlayer(member.uniqueId)
            if (player != null && player != sender && player != targetPlayer) {
                player.sendInfo("君主 $oldOwnerName 已将君主之位禅让给 $newOwnerName！")
            }
        }

        // 广播禅让消息
        Bukkit.broadcastMessage("§6国家 ${country.name} 的君主 $oldOwnerName 已将君主之位禅让给 $newOwnerName！")
    }

    private fun setMemberTitle(sender: CommandSender, playerName: String, title: String) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendError("你不属于任何国家！")
            return
        }

        val country = user.country!!

        // 检查权限（只有君主可以册封头衔）
        if (user.rank != Rank.OWNER) {
            sender.sendError("只有君主才能册封国民头衔！")
            return
        }

        // 验证头衔长度
        if (title.length < 1 || title.length > 16) {
            sender.sendError("头衔长度必须在1-16个字符之间！")
            return
        }

        // 验证头衔内容（不能包含特殊字符）
        val validTitlePattern = Regex("^[\\u4e00-\\u9fa5a-zA-Z0-9\\s]+$")
        if (!validTitlePattern.matches(title)) {
            sender.sendError("头衔只能包含中英文、数字和空格！")
            return
        }

        // 查找目标玩家
        val targetPlayer = Bukkit.getPlayer(playerName) ?: Bukkit.getOfflinePlayer(playerName)
        val targetUser = UserManager.getUser(targetPlayer.uniqueId)

        if (targetUser?.country?.id != country.id) {
            sender.sendError("目标玩家不是你国家的成员！")
            return
        }

        if (targetUser.rank == Rank.OWNER) {
            sender.sendError("不能为君主设置头衔！")
            return
        }

        val oldTitle = targetUser.title

        // 设置新头衔
        targetUser.title = title
        targetUser.save()

        sender.sendSuccess("成功为 $playerName 设置头衔: $title")

        if (oldTitle != "国民") {
            sender.sendInfo("原头衔: $oldTitle")
        }

        // 通知目标玩家
        if (targetPlayer.isOnline && targetPlayer is Player) {
            targetPlayer.sendSuccess("君主${sender.name}为你册封了新头衔: $title")
        }

        // 通知国家成员
        val members = country.members
        members.forEach { member ->
            val player = Bukkit.getPlayer(member.uniqueId)
            if (player != null && player != sender && player != targetPlayer) {
                player.sendInfo("君主${sender.name}为 $playerName 册封了头衔: $title")
            }
        }
    }

    private fun leaveCountry(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        // 检查冷却时间
        if (!CooldownManager.canExecute(sender, CooldownManager.CooldownType.LEAVE)) {
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendError("你不属于任何国家！")
            return
        }

        val country = user.country!!

        // 君主无法退出国家
        if (user.rank == Rank.OWNER) {
            sender.sendError("君主无法退出国家！请先转让君主之位或解散国家。")
            return
        }

        val countryName = country.name
        val playerName = sender.name

        // 清除玩家的国家关联和爵位
        user.country = null
        user.rank = Rank.DEFAULT
        user.title = "国民"
        user.save()

        // 设置冷却时间
        CooldownManager.setCooldown(sender, CooldownManager.CooldownType.LEAVE)

        sender.sendSuccess("你已成功退出国家 $countryName")
        sender.sendInfo("你现在是自由民，可以加入其他国家或创建新国家")

        // 通知国家成员
        val members = country.members
        members.forEach { member ->
            val player = Bukkit.getPlayer(member.uniqueId)
            if (player != null && player != sender) {
                player.sendInfo("$playerName 已退出国家")
            }
        }

        sender.sendMessage("§a你已退出国家 '$countryName'")
    }

    // v1.3.13修复：职业系统集成方法

    /**
     * 显示职业帮助信息
     */
    private fun showProfessionHelp(sender: CommandSender) {
        sender.sendInfo("=== 职业系统 ===")
        sender.sendInfo("/u profession set <职业> - 设置职业")
        sender.sendInfo("/u profession info - 查看当前职业信息")
        sender.sendInfo("/u profession list - 查看所有职业")
        sender.sendInfo("")
        sender.sendInfo("§e可用职业:")
        sender.sendInfo("  scout - 斥候 (速度提升)")
        sender.sendInfo("  craftsman - 工匠 (挖掘速度提升)")
        sender.sendInfo("  berserker - 狂战士 (攻击力提升)")
        sender.sendInfo("  guardian - 守护者 (抗性提升)")
        sender.sendInfo("  leaper - 跳跃者 (跳跃力提升)")
        sender.sendInfo("  priest - 牧师 (生命恢复)")
        sender.sendInfo("  conqueror - 征服者 (特殊能力)")
    }

    /**
     * 设置职业
     */
    private fun setProfession(sender: CommandSender, professionName: String) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendNoCountryError()
            return
        }

        val profession = try {
            Profession.valueOf(professionName.uppercase())
        } catch (e: IllegalArgumentException) {
            sender.sendError("未知职业: $professionName")
            sender.sendInfo("可用职业: scout, craftsman, berserker, guardian, leaper, priest, conqueror")
            return
        }

        // 检查是否已经有职业
        if (user.profession != null) {
            sender.sendWarning("你已经有职业了: ${cn.lcofficial.guozhan.manager.ProfessionManager.getProfessionName(user.profession!!)}")
            sender.sendInfo("每个玩家只能选择一次职业")
            return
        }

        // 设置职业
        cn.lcofficial.guozhan.manager.ProfessionManager.setProfession(user, profession)

        val professionDisplayName = cn.lcofficial.guozhan.manager.ProfessionManager.getProfessionName(profession)
        sender.sendSuccess("成功设置职业为: $professionDisplayName")
        sender.sendInfo("职业效果已激活！")

        // 记录日志
        Guozhan.instance.logger.info("[职业系统] ${sender.name} 选择了职业: $professionDisplayName")
    }

    /**
     * 显示职业信息
     */
    private fun showProfessionInfo(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val user = sender.user()
        if (user.profession == null) {
            sender.sendInfo("你还没有选择职业")
            sender.sendUsage("/u profession set <职业>", "选择职业")
            return
        }

        val professionName = cn.lcofficial.guozhan.manager.ProfessionManager.getProfessionName(user.profession!!)
        sender.sendInfo("=== 你的职业信息 ===")
        sender.sendInfo("职业: $professionName")
        sender.sendInfo("等级: ${user.professionLevel}")

        // 显示职业效果
        when (user.profession!!) {
            Profession.SCOUT -> sender.sendInfo("效果: 速度提升 ${if (user.professionLevel == 1) "II" else "V"}")
            Profession.CRAFTSMAN -> sender.sendInfo("效果: 急迫 ${user.professionLevel}")
            Profession.BERSERKER -> sender.sendInfo("效果: 力量 ${user.professionLevel}")
            Profession.GUARDIAN -> sender.sendInfo("效果: 抗性提升 ${user.professionLevel}")
            Profession.LEAPER -> sender.sendInfo("效果: 跳跃提升 ${if (user.professionLevel == 1) "III" else "V"}")
            Profession.PRIEST -> sender.sendInfo("效果: 生命恢复 ${user.professionLevel}")
            Profession.CONQUEROR -> sender.sendInfo("效果: 特殊征服能力")
        }
    }

    /**
     * 列出所有职业
     */
    private fun listProfessions(sender: CommandSender) {
        sender.sendInfo("=== 所有职业列表 ===")
        sender.sendInfo("§e斥候 (Scout)§f - 速度提升，适合探索和快速移动")
        sender.sendInfo("§e工匠 (Craftsman)§f - 挖掘速度提升，适合建设和采集")
        sender.sendInfo("§e狂战士 (Berserker)§f - 攻击力提升，适合战斗")
        sender.sendInfo("§e守护者 (Guardian)§f - 抗性提升，适合防御")
        sender.sendInfo("§e跳跃者 (Leaper)§f - 跳跃力提升，适合地形穿越")
        sender.sendInfo("§e牧师 (Priest)§f - 生命恢复，适合持续作战")
        sender.sendInfo("§e征服者 (Conqueror)§f - 特殊征服能力，适合领土扩张")
        sender.sendInfo("")
        sender.sendInfo("使用 /u profession set <职业> 来选择职业")
    }

}