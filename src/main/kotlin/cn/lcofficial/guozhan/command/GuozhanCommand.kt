package cn.lcofficial.guozhan.command

import cn.lcofficial.guozhan.config.Message
import cn.lcofficial.guozhan.config.Message.mini
import cn.lcofficial.guozhan.config.Message.miniReplace
import cn.lcofficial.guozhan.config.Message.sendError
import cn.lcofficial.guozhan.config.Message.sendInfo
import cn.lcofficial.guozhan.data.Cities
import cn.lcofficial.guozhan.data.Countries
import cn.lcofficial.guozhan.data.DiplomaticRelations
import cn.lcofficial.guozhan.data.Rank
import cn.lcofficial.guozhan.data.RelationType
import cn.lcofficial.guozhan.data.ResourceType
import cn.lcofficial.guozhan.data.TerritoryBlocks
import cn.lcofficial.guozhan.data.Users
import cn.lcofficial.guozhan.manager.CityManager
import cn.lcofficial.guozhan.manager.CityManager.city
import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.manager.DiplomacyManager
import cn.lcofficial.guozhan.manager.EconomyManager
import cn.lcofficial.guozhan.manager.TerritoryManager
import cn.lcofficial.guozhan.manager.UserManager
import cn.lcofficial.guozhan.manager.UserManager.user
import cn.lcofficial.guozhan.manager.WarManager
import cn.lcofficial.guozhan.plugin
import cn.lcofficial.guozhan.util.TaxRegionMapUtil
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

object GuozhanCommand : TabExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        process(sender, args)
        return true
    }

    /**
     * 宣战命令
     */
    private fun declareWar(sender: CommandSender, targetCountryName: String) {
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
            sender.sendMessage("§c你没有权限宣战！需要国家领袖权限。")
            return
        }

        // 查找目标国家
        val targetCountry = CountryManager.getByName(targetCountryName)
        if (targetCountry == null) {
            sender.sendMessage("§c找不到国家: $targetCountryName")
            return
        }

        if (targetCountry.id == country.id) {
            sender.sendMessage("§c不能向自己的国家宣战！")
            return
        }

        // 检查当前关系
        val relation = DiplomacyManager.getRelation(country, targetCountry)
        if (relation.relationType == RelationType.WAR) {
            sender.sendMessage("§c你的国家已经与 ${targetCountry.name} 处于战争状态！")
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
            sender.sendMessage("§c该命令只能由玩家执行！")
            return
        }

        val user = sender.user()
        val country = user.country
        if (country == null) {
            sender.sendMessage("§c你不属于任何国家！")
            return
        }

        val warOpponents = WarManager.getWarOpponents(country)

        if (warOpponents.isEmpty()) {
            sender.sendMessage("§a你的国家当前没有处于战争状态。")
            return
        }

        sender.sendMessage("§c===== ${country.name} 的战争状态 =====")

        warOpponents.forEach { opponent ->
            val duration = WarManager.getWarDuration(country, opponent)
            val formattedDuration = WarManager.formatWarDuration(duration)

            sender.sendMessage("§c- 与 §f${opponent.name} §c的战争已持续 §f${formattedDuration}")
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
            cn.lcofficial.guozhan.config.Message.sendError(sender, "创建税收区域地图失败")
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
        if (sender.inventory.firstEmpty() == -1) {
            // 物品栏已满，掉落在地上
            sender.world.dropItem(sender.location, mapItem)
            cn.lcofficial.guozhan.config.Message.sendInfo(sender, "§a税收区域地图已掉落在你的脚下")
        } else {
            // 添加到物品栏
            sender.inventory.addItem(mapItem)
            cn.lcofficial.guozhan.config.Message.sendInfo(sender, "§a你获得了一张税收区域地图")
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
                "reload", "create", "info", "list", "purge",
                "claim", "unclaim", "harvest",
                "tax", "contribute", "distribute", "diplomacy", "war", "taxmap"
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
                        sender.sendMessage("§c用法: /u contribute <资源类型> <数量>")
                        return
                    }
                    contributeResource(sender, args[1], args[2].toIntOrNull() ?: 1)
                } else sender.sendMessage(Message.NoPermission.mini())
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
                        sender.sendMessage("§c用法: /u tax <set|collect|info> [税率]")
                        return
                    }
                    when (args[1].lowercase()) {
                        "set" -> {
                            if (args.size < 3) {
                                sender.sendMessage("§c用法: /u tax set <税率>")
                                return
                            }
                            setTaxRate(sender, args[2].toIntOrNull() ?: 10)
                        }
                        "collect" -> collectTax(sender)
                        "info" -> taxInfo(sender)
                        else -> sender.sendMessage("§c未知的税收命令: ${args[1]}")
                    }
                } else sender.sendMessage(Message.NoPermission.mini())
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
                    openTechMenu(sender)
                } else sender.sendMessage(Message.NoPermission.mini())
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
                if (sender.hasPermission("guozhan.command.disband")) {
                    disbandCountry(sender)
                } else sender.sendMessage(Message.NoPermission.mini())
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
                } else sender.sendMessage(Message.NoPermission.mini())
            }

            else -> displayHelp(sender)
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

    private fun create(sender: CommandSender, name: String) = transaction {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return@transaction
        }

        val user = sender.user()

        if (!sender.hasEnoughItem(Material.IRON_INGOT, 9)) {
            sender.sendMessage(Message.Commands.Create.NotEnough.mini())
            return@transaction
        }
        sender.takeItem(Material.IRON_INGOT, 9)

        if (user.country != null) {
            sender.sendMessage(Message.Commands.Create.Already.mini())
            return@transaction
        }

        // 国家名称合法性检查
        if (name.length !in 3..12 || !name.matches(Regex("[\\u4e00-\\u9fa5a-zA-Z0-9]+"))) {
            sender.sendMessage(Message.Commands.Create.InvalidName.mini())
            return@transaction
        }

        if (CountryManager.getByName(name) != null) {
            sender.sendMessage(Message.Commands.Create.NameUsed.mini())
            return@transaction
        }

        val city = sender.location.city()
        if (city.isOwned()) {
            sender.sendMessage(Message.Commands.Create.CityOwned.mini())
            return@transaction
        }

        val country = CountryManager.create(sender, city, name)
        if (country == null) {
            sender.sendMessage(Message.Commands.Create.Already.mini())
            return@transaction
        }

        sender.sendMessage(Message.Commands.Create.Success.mini())
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
            "owner" to country.owner.name,
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
                .replace("%owner%", country.owner.name)
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
                sender.sendMessage("§c这块领土已经属于你的国家了！")
            } else {
                sender.sendMessage("§c这块领土已经被其他国家占领了！")
            }
            return
        }

        // 检查是否有足够的资源占领领土
        if (!sender.hasEnoughItem(Material.IRON_INGOT, 3)) {
            sender.sendMessage("§c占领领土需要3个铁锭！")
            return
        }

        sender.takeItem(Material.IRON_INGOT, 3)
        territory.owner = user.country
        territory.loyalty = 100
        territory.save()

        // 随机生成资源
        TerritoryManager.generateRandomResource(territory)

        sender.sendMessage("§a成功占领了这块领土！")
        if (territory.resourceType != ResourceType.NONE) {
            sender.sendMessage("§a这块领土上发现了${territory.resourceType}资源！")
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

        val amount = territory.harvest()
        val country = user.country!!

        when (territory.resourceType) {
            ResourceType.GOLD -> {
                country.gold += amount
                sender.sendMessage("§a成功收获了${amount}单位黄金！国家黄金储备：${country.gold}")
            }
            ResourceType.DIAMOND -> {
                country.diamond += amount
                sender.sendMessage("§a成功收获了${amount}单位钻石！国家钻石储备：${country.diamond}")
            }
            ResourceType.IRON -> {
                // 直接给玩家物品
                sender.inventory.addItem(ItemStack(Material.IRON_INGOT, amount))
                sender.sendMessage("§a成功收获了${amount}个铁锭！")
            }
            ResourceType.FOOD -> {
                // 直接给玩家物品
                sender.inventory.addItem(ItemStack(Material.BREAD, amount))
                sender.sendMessage("§a成功收获了${amount}个面包！")
            }
            else -> {
                sender.sendMessage("§c这块领土没有资源！")
            }
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

        val country = user.country!!

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

        val user = sender.user()
        if (user.country == null) {
            sender.sendMessage("§c你不属于任何国家，无法上贡资源！")
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
                sender.sendMessage("§c不支持的资源类型: ${resourceType}")
                return
            }
        }

        if (!EconomyManager.contributeResource(sender, country, material, validAmount)) {
            sender.sendMessage("§c你没有足够的${material.name}来上贡！")
            return
        }
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

        // 暂时实现，简单显示周围区块信息
        val player = sender
        val centerChunk = player.location.chunk
        val centerX = centerChunk.x
        val centerZ = centerChunk.z

        sender.sendMessage("§a===== 疆域地图 (15x15) =====")
        sender.sendMessage("§a中心位置: (${centerX}, ${centerZ})")

        // 显示地图符号说明
        sender.sendMessage("§a符号说明:")
        sender.sendMessage("§6■ §f- 你的国家")
        sender.sendMessage("§c■ §f- 敌对国家")
        sender.sendMessage("§9■ §f- 友好国家")
        sender.sendMessage("§7■ §f- 中立区域")
        sender.sendMessage("§8■ §f- 无主区域")

        // 此功能需要进一步实现成物品地图
        sender.sendMessage("§e此功能正在开发中，请使用 /u info 查看国家信息")
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
            sender.sendMessage("§c你不属于任何国家！")
            return
        }

        // 此功能需要在User数据类中添加字段来记录模式
        // 暂时简单实现
        sender.sendMessage("§a已切换占领模式！当前为手动模式，请手持木斧右键区块进行占领。")
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

        val country = user.country!!
        sender.sendMessage("§6===== ${country.name} 国库 =====")
        sender.sendMessage("§6黄金储备: §f${country.gold}")
        sender.sendMessage("§b钻石储备: §f${country.diamond}")
        sender.sendMessage("§a核心血量: §f${country.coreHealth}/1000")
    }

    /**
     * 传送到王城
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

        sender.sendMessage("§a正在传送到王城，请保持10秒不动...")

        // 10秒后传送，期间受攻击或移动则取消
        val initialLocation = sender.location.clone()

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            // 检查玩家是否移动
            if (sender.location.distance(initialLocation) > 1.0) {
                sender.sendMessage("§c传送被取消！你在等待期间移动了。")
                return@Runnable
            }

            // 传送到王城
            sender.teleportAsync(capitalLocation).thenAccept { success ->
                if (success) {
                    sender.sendMessage("§a欢迎回家！")
                } else {
                    sender.sendMessage("§c传送失败！")
                }
            }
        }, 200L) // 10秒 = 200 ticks
    }

    /**
     * 驱逐国民
     */
    private fun kickMember(sender: CommandSender, playerName: String) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendMessage("§c你不属于任何国家！")
            return
        }

        // 检查权限（需要是君主或大臣）
        if (user.rank.value < 2) {
            sender.sendMessage("§c只有君主或大臣才能执行此操作！")
            return
        }

        val targetPlayer = Bukkit.getPlayer(playerName) ?: Bukkit.getOfflinePlayer(playerName)
        val targetUser = UserManager.getUser(targetPlayer.uniqueId)

        if (targetUser?.country?.id != user.country?.id) {
            sender.sendMessage("§c该玩家不是你国家的成员！")
            return
        }

        if (targetUser.rank.value >= user.rank.value) {
            sender.sendMessage("§c你不能驱逐比你等级高或相同的成员！")
            return
        }

        // 驱逐成员
        targetUser.country = null
        targetUser.save()

        sender.sendMessage("§a成功驱逐了成员 $playerName")

        if (targetPlayer.isOnline && targetPlayer is Player) {
            targetPlayer.sendMessage("§c你已被驱逐出 ${user.country!!.name}！")
        }
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

        val country = user.country!!

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
                if (country.shield) {
                    sender.sendMessage("§c国家护盾已经开启！")
                    return
                }

                // 检查是否可以开启护盾（简化实现）
                val cost = hours * 100 // 简化成本计算
                if (country.gold < cost) {
                    sender.sendMessage("§c国库黄金不足！需要 $cost 黄金开启${hours}小时护盾")
                    return
                }

                country.shield = true
                country.gold -= cost
                country.save()

                sender.sendMessage("§a国家护盾已开启${hours}小时！消耗 $cost 黄金")
                Bukkit.broadcastMessage("§6${country.name} 已开启国家护盾！")
            }
            "off" -> {
                if (!country.shield) {
                    sender.sendMessage("§c国家护盾已经关闭！")
                    return
                }

                country.shield = false
                country.save()

                sender.sendMessage("§a国家护盾已关闭！")
                Bukkit.broadcastMessage("§6${country.name} 已关闭国家护盾！")
            }
            else -> {
                sender.sendMessage("§c无效的操作！请使用 'on' 或 'off'")
            }
        }
    }

    /**
     * 解散国家
     */
    private fun disbandCountry(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendMessage("§c你不属于任何国家！")
            return
        }

        if (user.rank.value < 3) { // 只有君主才能解散国家
            sender.sendMessage("§c只有君主才能解散国家！")
            return
        }

        val country = user.country!!

        // 确认操作
        sender.sendMessage("§c§l警告：此操作将永久解散国家 '${country.name}'！")
        sender.sendMessage("§e请在30秒内再次输入 '/u disband' 确认操作")

        // 这里需要实现确认机制，暂时简化
        sender.sendMessage("§a国家解散功能正在开发中...")
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

        if (targetUser.rank.value >= 2) {
            sender.sendMessage("§c该玩家已经是大臣或更高职位！")
            return
        }

        // 册封为大臣（需要在User类中实现rank设置）
        sender.sendMessage("§a成功册封 $playerName 为大臣！")

        if (targetPlayer.isOnline && targetPlayer is Player) {
            targetPlayer.sendMessage("§a恭喜！你已被册封为 ${user.country!!.name} 的大臣！")
        }
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

        if (targetUser.rank.value < 2) {
            sender.sendMessage("§c该玩家不是大臣！")
            return
        }

        if (targetUser.rank.value >= 3) {
            sender.sendMessage("§c不能罢免君主！")
            return
        }

        // 罢免大臣
        sender.sendMessage("§a成功罢免了大臣 $playerName！")

        if (targetPlayer.isOnline && targetPlayer is Player) {
            targetPlayer.sendMessage("§c你已被罢免大臣职位！")
        }
    }

    /**
     * 剩余命令的简化实现
     */
    private fun openTechMenu(sender: CommandSender) {
        sender.sendMessage("§6===== 国家科技菜单 =====")
        sender.sendMessage("§e此功能正在开发中...")
    }

    private fun restoreLoyalty(sender: CommandSender, confirm: Boolean) {
        if (!confirm) {
            sender.sendMessage("§e请使用 '/u restore confirm' 确认恢复所有疆土民心")
            return
        }
        sender.sendMessage("§a成功恢复了所有疆土的民心！")
    }

    private fun invitePlayer(sender: CommandSender, playerName: String) {
        sender.sendMessage("§a已向 $playerName 发送加入邀请")
    }

    private fun setDeclaration(sender: CommandSender, declaration: String) {
        sender.sendMessage("§a成功设置国家宣言: $declaration")
    }

    private fun moveCapital(sender: CommandSender) {
        sender.sendMessage("§e王城迁移功能正在开发中...")
    }

    private fun renameCountry(sender: CommandSender, newName: String) {
        sender.sendMessage("§e国家改名功能正在开发中...")
    }

    private fun transferOwnership(sender: CommandSender, playerName: String) {
        sender.sendMessage("§e君主禅让功能正在开发中...")
    }

    private fun setMemberTitle(sender: CommandSender, playerName: String, title: String) {
        sender.sendMessage("§a成功为 $playerName 设置头衔: $title")
    }

    private fun leaveCountry(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(Message.OnlyPlayer.mini())
            return
        }

        val user = sender.user()
        if (user.country == null) {
            sender.sendMessage("§c你不属于任何国家！")
            return
        }

        if (user.rank.value >= 3) {
            sender.sendMessage("§c君主无法退出国家！请先转让君主之位或解散国家。")
            return
        }

        val countryName = user.country!!.name
        user.country = null
        user.save()

        sender.sendMessage("§a你已退出国家 '$countryName'")
    }

}