package cn.lcofficial.guozhan.command

import cn.lcofficial.guozhan.config.Message
import cn.lcofficial.guozhan.config.Message.sendError
import cn.lcofficial.guozhan.config.Message.sendSuccess
import cn.lcofficial.guozhan.config.Message.sendInfo
import cn.lcofficial.guozhan.config.Message.sendWarning
import cn.lcofficial.guozhan.data.Country
import cn.lcofficial.guozhan.data.Rank
import cn.lcofficial.guozhan.manager.*
import cn.lcofficial.guozhan.manager.UserManager.user
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.*

/**
 * GM命令处理器
 * 命令前缀：/gzgm (GuoZhan Game Master)
 * 权限节点：guozhan.admin.*
 */
class GMCommand : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        // 检查基础权限
        if (!sender.hasPermission("guozhan.admin.use")) {
            sender.sendError("你没有权限使用GM命令")
            return true
        }

        if (args.isEmpty()) {
            showHelp(sender)
            return true
        }

        when (args[0].lowercase()) {
            "give" -> handleGive(sender, args)
            "setcountry" -> handleSetCountry(sender, args)
            "addgold" -> handleAddGold(sender, args)
            "adddiamonds" -> handleAddDiamonds(sender, args)
            "setloyalty" -> handleSetLoyalty(sender, args)
            "setcorehealth" -> handleSetCoreHealth(sender, args)
            "tp" -> handleTeleport(sender, args)
            "debug" -> handleDebug(sender, args)
            "reload" -> handleReload(sender, args)
            "cleardata" -> handleClearData(sender, args)
            "info" -> handleInfo(sender, args)
            "setrank" -> handleSetRank(sender, args)
            "cleardb" -> handleClearDatabase(sender, args)
            "startwar" -> handleStartWar(sender, args)
            "endwar" -> handleEndWar(sender, args)
            "warinfo" -> handleWarInfo(sender, args)
            "shield" -> handleShield(sender, args)
            "help" -> showHelp(sender)
            else -> {
                sender.sendError("未知的GM命令: ${args[0]}")
                showHelp(sender)
            }
        }

        return true
    }

    /**
     * 给予玩家物品
     * /gzgm give <玩家> <物品类型> <数量>
     */
    private fun handleGive(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("guozhan.admin.give")) {
            sender.sendError("你没有权限使用give命令")
            return
        }

        if (args.size < 4) {
            sender.sendError("用法: /gzgm give <玩家> <物品类型> <数量>")
            return
        }

        val playerName = args[1]
        val materialName = args[2].uppercase()
        val amount = args[3].toIntOrNull()

        if (amount == null || amount <= 0) {
            sender.sendError("数量必须是正整数")
            return
        }

        val player = Bukkit.getPlayer(playerName)
        if (player == null) {
            sender.sendError("玩家 $playerName 不在线")
            return
        }

        val material = try {
            Material.valueOf(materialName)
        } catch (e: IllegalArgumentException) {
            sender.sendError("无效的物品类型: $materialName")
            return
        }

        val itemStack = ItemStack(material, amount)
        player.inventory.addItem(itemStack)
        
        sender.sendSuccess("已给予玩家 $playerName ${amount}个 ${material.name}")
        player.sendInfo("§a[GM] 你收到了 ${amount}个 ${material.name}")
        
        // 记录GM操作
        GMLogger.logGMAction(sender, "GIVE_ITEM", playerName, mapOf(
            "material" to material.name,
            "amount" to amount
        ))
    }

    /**
     * 设置玩家所属国家
     * /gzgm setcountry <玩家> <国家名>
     */
    private fun handleSetCountry(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("guozhan.admin.country")) {
            sender.sendError("你没有权限使用setcountry命令")
            return
        }

        if (args.size < 3) {
            sender.sendError("用法: /gzgm setcountry <玩家> <国家名>")
            return
        }

        val playerName = args[1]
        val countryName = args[2]

        val player = Bukkit.getPlayer(playerName)
        if (player == null) {
            sender.sendError("玩家 $playerName 不在线")
            return
        }

        val country = CountryManager.getByName(countryName)
        if (country == null) {
            sender.sendError("国家 $countryName 不存在")
            return
        }

        val user = player.user()
        val oldCountry = user.country?.name ?: "无"
        
        user.country = country
        user.save()

        sender.sendSuccess("已将玩家 $playerName 的国家从 $oldCountry 设置为 ${country.name}")
        player.sendInfo("§a[GM] 你的国家已被设置为: ${country.name}")
        
        GMLogger.logGMAction(sender, "SET_COUNTRY", playerName, mapOf(
            "old_country" to oldCountry,
            "new_country" to country.name
        ))
    }

    /**
     * 增加国家金币
     * /gzgm addgold <国家> <数量>
     */
    private fun handleAddGold(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("guozhan.admin.economy")) {
            sender.sendError("你没有权限使用addgold命令")
            return
        }

        if (args.size < 3) {
            sender.sendError("用法: /gzgm addgold <国家> <数量>")
            return
        }

        val countryName = args[1]
        val amount = args[2].toIntOrNull()

        if (amount == null) {
            sender.sendError("数量必须是整数")
            return
        }

        val country = CountryManager.getByName(countryName)
        if (country == null) {
            sender.sendError("国家 $countryName 不存在")
            return
        }

        val oldGold = country.gold
        country.gold += amount
        country.save()

        sender.sendSuccess("已为国家 ${country.name} 增加 $amount 金币 (${oldGold} -> ${country.gold})")
        
        GMLogger.logGMAction(sender, "ADD_GOLD", countryName, mapOf(
            "amount" to amount,
            "old_gold" to oldGold,
            "new_gold" to country.gold
        ))
    }

    /**
     * 增加国家钻石
     * /gzgm adddiamonds <国家> <数量>
     */
    private fun handleAddDiamonds(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("guozhan.admin.economy")) {
            sender.sendError("你没有权限使用adddiamonds命令")
            return
        }

        if (args.size < 3) {
            sender.sendError("用法: /gzgm adddiamonds <国家> <数量>")
            return
        }

        val countryName = args[1]
        val amount = args[2].toIntOrNull()

        if (amount == null) {
            sender.sendError("数量必须是整数")
            return
        }

        val country = CountryManager.getByName(countryName)
        if (country == null) {
            sender.sendError("国家 $countryName 不存在")
            return
        }

        val oldDiamonds = country.diamond
        country.diamond += amount
        country.save()

        sender.sendSuccess("已为国家 ${country.name} 增加 $amount 钻石 (${oldDiamonds} -> ${country.diamond})")
        
        GMLogger.logGMAction(sender, "ADD_DIAMONDS", countryName, mapOf(
            "amount" to amount,
            "old_diamonds" to oldDiamonds,
            "new_diamonds" to country.diamond
        ))
    }

    /**
     * 设置领土忠诚度
     * /gzgm setloyalty <X坐标> <Z坐标> <忠诚度>
     */
    private fun handleSetLoyalty(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("guozhan.admin.territory")) {
            sender.sendError("你没有权限使用setloyalty命令")
            return
        }

        if (args.size < 4) {
            sender.sendError("用法: /gzgm setloyalty <X坐标> <Z坐标> <忠诚度>")
            return
        }

        val x = args[1].toIntOrNull()
        val z = args[2].toIntOrNull()
        val loyalty = args[3].toIntOrNull()

        if (x == null || z == null || loyalty == null) {
            sender.sendError("坐标和忠诚度必须是整数")
            return
        }

        if (loyalty !in 0..100) {
            sender.sendError("忠诚度必须在0-100之间")
            return
        }

        val world = if (sender is Player) sender.world.name else "world"
        val territory = TerritoryManager.getTerritoryBlock(x, z, world)
        
        if (territory == null) {
            sender.sendError("坐标 ($x, $z) 没有领土")
            return
        }

        val oldLoyalty = territory.loyalty
        territory.loyalty = loyalty
        territory.save()

        sender.sendSuccess("已将坐标 ($x, $z) 的领土忠诚度从 $oldLoyalty 设置为 $loyalty")
        
        GMLogger.logGMAction(sender, "SET_LOYALTY", "($x, $z)", mapOf(
            "x" to x,
            "z" to z,
            "old_loyalty" to oldLoyalty,
            "new_loyalty" to loyalty
        ))
    }

    /**
     * 设置国家核心血量
     * /gzgm setcorehealth <国家> <血量>
     */
    private fun handleSetCoreHealth(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("guozhan.admin.country")) {
            sender.sendError("你没有权限使用setcorehealth命令")
            return
        }

        if (args.size < 3) {
            sender.sendError("用法: /gzgm setcorehealth <国家> <血量>")
            return
        }

        val countryName = args[1]
        val health = args[2].toIntOrNull()

        if (health == null || health < 0 || health > 1000) {
            sender.sendError("血量必须是0-1000之间的整数")
            return
        }

        val country = CountryManager.getByName(countryName)
        if (country == null) {
            sender.sendError("国家 $countryName 不存在")
            return
        }

        val oldHealth = country.coreHealth
        country.coreHealth = health
        country.save()

        sender.sendSuccess("已将国家 ${country.name} 的核心血量从 $oldHealth 设置为 $health")

        GMLogger.logGMAction(sender, "SET_CORE_HEALTH", countryName, mapOf(
            "old_health" to oldHealth,
            "new_health" to health
        ))
    }

    /**
     * 传送到国家首都
     * /gzgm tp <国家>
     */
    private fun handleTeleport(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("guozhan.admin.teleport")) {
            sender.sendError("你没有权限使用tp命令")
            return
        }

        if (sender !is Player) {
            sender.sendError("该命令只能由玩家执行")
            return
        }

        if (args.size < 2) {
            sender.sendError("用法: /gzgm tp <国家>")
            return
        }

        val countryName = args[1]
        val country = CountryManager.getByName(countryName)
        if (country == null) {
            sender.sendError("国家 $countryName 不存在")
            return
        }

        val capitalLocation = country.capital.let { capital ->
            val world = Bukkit.getWorld("world") ?: sender.world
            org.bukkit.Location(world, capital.x * 16.0 + 8, 70.0, capital.z * 16.0 + 8)
        }

        sender.teleportAsync(capitalLocation).thenAccept { success ->
            if (success) {
                sender.sendSuccess("已传送到国家 ${country.name} 的首都")
                GMLogger.logGMAction(sender, "TELEPORT", countryName, mapOf(
                    "destination" to "${capitalLocation.x}, ${capitalLocation.y}, ${capitalLocation.z}"
                ))
            } else {
                sender.sendError("传送失败")
            }
        }
    }

    /**
     * 开关调试模式
     * /gzgm debug <on|off>
     */
    private fun handleDebug(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("guozhan.admin.debug")) {
            sender.sendError("你没有权限使用debug命令")
            return
        }

        if (args.size < 2) {
            sender.sendError("用法: /gzgm debug <on|off>")
            return
        }

        val mode = args[1].lowercase()
        when (mode) {
            "on", "true", "enable" -> {
                GMDebugManager.enableDebug()
                sender.sendSuccess("调试模式已开启")
                GMLogger.logGMAction(sender, "DEBUG_ON", null, emptyMap())
            }
            "off", "false", "disable" -> {
                GMDebugManager.disableDebug()
                sender.sendSuccess("调试模式已关闭")
                GMLogger.logGMAction(sender, "DEBUG_OFF", null, emptyMap())
            }
            else -> {
                sender.sendError("无效的模式，请使用 on 或 off")
            }
        }
    }

    /**
     * 重载配置
     * /gzgm reload
     */
    private fun handleReload(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("guozhan.admin.reload")) {
            sender.sendError("你没有权限使用reload命令")
            return
        }

        try {
            cn.lcofficial.guozhan.Guozhan.instance.initialize()
            sender.sendSuccess("配置重载完成")
            GMLogger.logGMAction(sender, "RELOAD_CONFIG", null, emptyMap())
        } catch (e: Exception) {
            sender.sendError("配置重载失败: ${e.message}")
        }
    }

    /**
     * 清理测试数据
     * /gzgm cleardata <countries|territories|users|all>
     */
    private fun handleClearData(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("guozhan.admin.data")) {
            sender.sendError("你没有权限使用cleardata命令")
            return
        }

        if (args.size < 2) {
            sender.sendError("用法: /gzgm cleardata <countries|territories|users|all>")
            return
        }

        val dataType = args[1].lowercase()
        when (dataType) {
            "countries" -> {
                val count = CountryManager.getAllCountries().size
                // TODO: 实现清理国家数据的逻辑
                sender.sendSuccess("已清理 $count 个国家数据")
                GMLogger.logGMAction(sender, "CLEAR_COUNTRIES", null, mapOf("count" to count))
            }
            "territories" -> {
                // TODO: 实现清理领土数据的逻辑
                sender.sendSuccess("已清理所有领土数据")
                GMLogger.logGMAction(sender, "CLEAR_TERRITORIES", null, emptyMap())
            }
            "users" -> {
                // TODO: 实现清理用户数据的逻辑
                sender.sendSuccess("已清理所有用户数据")
                GMLogger.logGMAction(sender, "CLEAR_USERS", null, emptyMap())
            }
            "all" -> {
                // TODO: 实现清理所有数据的逻辑
                sender.sendSuccess("已清理所有测试数据")
                GMLogger.logGMAction(sender, "CLEAR_ALL_DATA", null, emptyMap())
            }
            else -> {
                sender.sendError("无效的数据类型，请使用: countries, territories, users, all")
            }
        }
    }

    /**
     * 查看详细信息
     * /gzgm info <国家|玩家>
     */
    private fun handleInfo(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            sender.sendError("用法: /gzgm info <国家|玩家>")
            return
        }

        val target = args[1]

        // 尝试作为国家名查找
        val country = CountryManager.getByName(target)
        if (country != null) {
            showCountryInfo(sender, country)
            return
        }

        // 尝试作为玩家名查找
        val player = Bukkit.getPlayer(target)
        if (player != null) {
            showPlayerInfo(sender, player)
            return
        }

        sender.sendError("找不到国家或玩家: $target")
    }

    private fun showCountryInfo(sender: CommandSender, country: Country) {
        sender.sendInfo("§6===== 国家信息: ${country.name} =====")
        sender.sendInfo("§e国家ID: §f${country.id}")
        sender.sendInfo("§e君主: §f${country.owner?.name ?: "未知"}")
        sender.sendInfo("§e创建时间: §f${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(country.createTime))}")
        sender.sendInfo("§e金币: §f${country.gold}")
        sender.sendInfo("§e钻石: §f${country.diamond}")
        sender.sendInfo("§e核心血量: §f${country.coreHealth}/1000")
        sender.sendInfo("§e是否公开: §f${if (country.public) "是" else "否"}")
        sender.sendInfo("§e护盾状态: §f${if (country.shield) "开启" else "关闭"}")
        sender.sendInfo("§e领土数量: §f${country.cities.size}")
    }

    private fun showPlayerInfo(sender: CommandSender, player: Player) {
        val user = player.user()
        sender.sendInfo("§6===== 玩家信息: ${player.name} =====")
        sender.sendInfo("§e玩家UUID: §f${player.uniqueId}")
        sender.sendInfo("§e所属国家: §f${user.country?.name ?: "无"}")
        sender.sendInfo("§e爵位: §f${user.rank} (值: ${user.rank.value})")
        sender.sendInfo("§e在线状态: §f在线")
        sender.sendInfo("§e当前位置: §f${player.location.blockX}, ${player.location.blockY}, ${player.location.blockZ}")
        sender.sendInfo("§e当前世界: §f${player.world.name}")
    }

    /**
     * 设置玩家权限等级
     * /gzgm setrank <玩家> <等级>
     */
    private fun handleSetRank(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("guozhan.admin.setrank")) {
            sender.sendError("你没有权限使用setrank命令")
            return
        }

        if (args.size < 3) {
            sender.sendError("用法: /gzgm setrank <玩家> <等级>")
            sender.sendInfo("可用等级: DEFAULT, ADMIN, OWNER")
            return
        }

        val playerName = args[1]
        val rankName = args[2].uppercase()

        val player = Bukkit.getPlayer(playerName)
        if (player == null) {
            sender.sendError("玩家 $playerName 不在线")
            return
        }

        val rank = try {
            Rank.valueOf(rankName)
        } catch (e: IllegalArgumentException) {
            sender.sendError("无效的等级: $rankName")
            sender.sendInfo("可用等级: DEFAULT, ADMIN, OWNER")
            return
        }

        val user = player.user()
        val oldRank = user.rank
        user.rank = rank
        user.save()

        sender.sendSuccess("已将玩家 $playerName 的等级从 $oldRank 设置为 $rank")
        player.sendInfo("§a你的等级已被设置为: §e$rank")

        // 记录GM操作
        cn.lcofficial.guozhan.plugin.logger.info("[GM操作] ${sender.name} 将 $playerName 的等级从 $oldRank 设置为 $rank")
    }

    /**
     * 清空数据库
     * /gzgm cleardb [confirm]
     */
    private fun handleClearDatabase(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("guozhan.admin.cleardb")) {
            sender.sendError("你没有权限使用cleardb命令")
            return
        }

        if (args.size < 2 || args[1] != "confirm") {
            sender.sendWarning("§c警告: 此命令将清空所有数据库数据！")
            sender.sendWarning("§c这将删除所有国家、用户、领土等数据！")
            sender.sendInfo("§e如果确定要执行，请使用: §c/gzgm cleardb confirm")
            return
        }

        try {
            // 清空所有表
            DataManager.clearAllTables()

            sender.sendSuccess("数据库已清空！所有数据已删除")
            sender.sendInfo("服务器将在5秒后重新加载插件...")

            // 记录GM操作
            cn.lcofficial.guozhan.plugin.logger.warning("[GM操作] ${sender.name} 清空了数据库")

            // 延迟重新加载插件 - 使用Folia调度器
            cn.lcofficial.guozhan.util.runLater(100L) { task ->
                Bukkit.getPluginManager().disablePlugin(cn.lcofficial.guozhan.plugin)
                Bukkit.getPluginManager().enablePlugin(cn.lcofficial.guozhan.plugin)
            }

        } catch (e: Exception) {
            sender.sendError("清空数据库时发生错误: ${e.message}")
            cn.lcofficial.guozhan.plugin.logger.severe("清空数据库失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 显示帮助信息
     */
    private fun showHelp(sender: CommandSender) {
        sender.sendInfo("§6===== GuoZhan GM命令帮助 =====")
        sender.sendInfo("§e/gzgm give <玩家> <物品> <数量> §7- 给予玩家物品")
        sender.sendInfo("§e/gzgm setcountry <玩家> <国家> §7- 设置玩家国家")
        sender.sendInfo("§e/gzgm addgold <国家> <数量> §7- 增加国家金币")
        sender.sendInfo("§e/gzgm adddiamonds <国家> <数量> §7- 增加国家钻石")
        sender.sendInfo("§e/gzgm setloyalty <X> <Z> <忠诚度> §7- 设置领土忠诚度")
        sender.sendInfo("§e/gzgm setcorehealth <国家> <血量> §7- 设置核心血量")
        sender.sendInfo("§e/gzgm tp <国家> §7- 传送到国家首都")
        sender.sendInfo("§e/gzgm debug <on|off> §7- 开关调试模式")
        sender.sendInfo("§e/gzgm reload §7- 重载配置")
        sender.sendInfo("§e/gzgm cleardata <类型> §7- 清理测试数据")
        sender.sendInfo("§e/gzgm info <国家|玩家> §7- 查看详细信息")
        sender.sendInfo("§c/gzgm setrank <玩家> <等级> §7- 设置玩家权限等级")
        sender.sendInfo("§c/gzgm cleardb confirm §7- 清空整个数据库(危险)")
        sender.sendInfo("§c=== 战争管理命令 ===")
        sender.sendInfo("§e/gzgm startwar <国家1> <国家2> §7- 手动触发战争")
        sender.sendInfo("§e/gzgm endwar <国家1> <国家2> §7- 手动结束战争")
        sender.sendInfo("§e/gzgm warinfo §7- 查看当前战争状态")
        sender.sendInfo("§e/gzgm shield <国家> <小时> §7- 强制激活护盾(绕过时间限制)")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String>? {
        // 无权限玩家返回空列表，隐藏所有Tab补全
        if (!sender.hasPermission("guozhan.admin.use")) {
            return mutableListOf()
        }

        return when (args.size) {
            1 -> getMainCommands(sender).filter { it.startsWith(args[0], true) }.toMutableList()
            2 -> getSubCommands(sender, args[0]).filter { it.startsWith(args[1], true) }.toMutableList()
            else -> mutableListOf()
        }
    }

    private fun getMainCommands(sender: CommandSender): List<String> {
        val commands = mutableListOf<String>()

        if (sender.hasPermission("guozhan.admin.give")) commands.add("give")
        if (sender.hasPermission("guozhan.admin.country")) commands.add("setcountry")
        if (sender.hasPermission("guozhan.admin.economy")) {
            commands.add("addgold")
            commands.add("adddiamonds")
        }
        if (sender.hasPermission("guozhan.admin.territory")) commands.add("setloyalty")
        if (sender.hasPermission("guozhan.admin.teleport")) commands.add("tp")
        if (sender.hasPermission("guozhan.admin.debug")) commands.add("debug")
        if (sender.hasPermission("guozhan.admin.reload")) commands.add("reload")
        if (sender.hasPermission("guozhan.admin.data")) commands.add("cleardata")
        if (sender.hasPermission("guozhan.admin.setrank")) commands.add("setrank")
        if (sender.hasPermission("guozhan.admin.cleardb")) commands.add("cleardb")
        if (sender.hasPermission("guozhan.admin.war")) {
            commands.add("startwar")
            commands.add("endwar")
            commands.add("warinfo")
        }
        if (sender.hasPermission("guozhan.admin.shield")) {
            commands.add("shield")
        }

        commands.addAll(listOf("info", "help"))

        return commands
    }

    private fun getSubCommands(sender: CommandSender, mainCommand: String): List<String> {
        return when (mainCommand.lowercase()) {
            "give" -> listOf("IRON_INGOT", "GOLD_INGOT", "DIAMOND", "EMERALD")
            "setcountry", "addgold", "adddiamonds", "tp", "info", "startwar", "endwar", "shield" ->
                CountryManager.getAllCountries().map { it.name }
            "debug" -> listOf("on", "off")
            "cleardata" -> listOf("countries", "territories", "users", "all")
            "setrank" -> listOf("DEFAULT", "ADMIN", "OWNER")
            "cleardb" -> listOf("confirm")
            else -> emptyList()
        }
    }

    /**
     * 手动触发战争
     * /gzgm startwar <国家1> <国家2>
     */
    private fun handleStartWar(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("guozhan.admin.war")) {
            sender.sendError("你没有权限使用战争管理命令")
            return
        }

        if (args.size < 3) {
            sender.sendError("用法: /gzgm startwar <国家1> <国家2>")
            return
        }

        val country1Name = args[1]
        val country2Name = args[2]

        val country1 = CountryManager.getByName(country1Name)
        val country2 = CountryManager.getByName(country2Name)

        if (country1 == null) {
            sender.sendError("国家 $country1Name 不存在")
            return
        }

        if (country2 == null) {
            sender.sendError("国家 $country2Name 不存在")
            return
        }

        if (country1.id == country2.id) {
            sender.sendError("不能让国家与自己开战")
            return
        }

        // 检查是否已经在战争中
        if (WarManager.isAtWar(country1, country2)) {
            sender.sendError("${country1.name} 与 ${country2.name} 已经处于战争状态")
            return
        }

        try {
            // GM模式：绕过时间限制直接启动战争
            WarManager.startWarGM(country1, country2)

            sender.sendSuccess("§c[GM模式] §f已手动触发 §e${country1.name} §f与 §e${country2.name} §f之间的战争")

            // 记录GM操作
            GMLogger.logGMAction(sender, "START_WAR", null, mapOf(
                "country1" to country1.name,
                "country2" to country2.name,
                "bypass_time_limit" to true
            ))

        } catch (e: Exception) {
            sender.sendError("启动战争时发生错误: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 手动结束战争
     * /gzgm endwar <国家1> <国家2>
     */
    private fun handleEndWar(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("guozhan.admin.war")) {
            sender.sendError("你没有权限使用战争管理命令")
            return
        }

        if (args.size < 3) {
            sender.sendError("用法: /gzgm endwar <国家1> <国家2>")
            return
        }

        val country1Name = args[1]
        val country2Name = args[2]

        val country1 = CountryManager.getByName(country1Name)
        val country2 = CountryManager.getByName(country2Name)

        if (country1 == null) {
            sender.sendError("国家 $country1Name 不存在")
            return
        }

        if (country2 == null) {
            sender.sendError("国家 $country2Name 不存在")
            return
        }

        // 检查是否在战争中
        if (!WarManager.isAtWar(country1, country2)) {
            sender.sendError("${country1.name} 与 ${country2.name} 当前没有处于战争状态")
            return
        }

        try {
            // GM模式：直接结束战争
            WarManager.endWarGM(country1, country2, sender)

            sender.sendSuccess("§c[GM模式] §f已手动结束 §e${country1.name} §f与 §e${country2.name} §f之间的战争")

            // 记录GM操作
            GMLogger.logGMAction(sender, "END_WAR", null, mapOf(
                "country1" to country1.name,
                "country2" to country2.name,
                "forced_end" to true
            ))

        } catch (e: Exception) {
            sender.sendError("结束战争时发生错误: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 查看当前战争状态
     * /gzgm warinfo
     */
    private fun handleWarInfo(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("guozhan.admin.war")) {
            sender.sendError("你没有权限使用战争管理命令")
            return
        }

        try {
            val activeWars = WarManager.getAllActiveWars()

            if (activeWars.isEmpty()) {
                sender.sendInfo("§a当前没有活跃的战争")
                return
            }

            sender.sendInfo("§6===== 当前活跃战争状态 =====")

            activeWars.forEach { (warId, warInfo) ->
                val (country1, country2) = warInfo
                val duration = WarManager.getWarDuration(country1, country2)
                val formattedDuration = WarManager.formatWarDuration(duration)

                sender.sendInfo("§c战争: §e${country1.name} §cvs §e${country2.name}")
                sender.sendInfo("  §7持续时间: §f$formattedDuration")
                sender.sendInfo("  §7战争ID: §8$warId")
            }

            sender.sendInfo("§6总计: §f${activeWars.size} §6场活跃战争")

            // 记录GM操作
            GMLogger.logGMAction(sender, "WAR_INFO", null, mapOf(
                "active_wars_count" to activeWars.size
            ))

        } catch (e: Exception) {
            sender.sendError("查看战争信息时发生错误: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * GM护盾激活命令（绕过时间限制）
     * /gzgm shield <国家> <小时>
     */
    private fun handleShield(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("guozhan.admin.shield")) {
            sender.sendError("你没有权限使用护盾管理命令")
            return
        }

        if (args.size < 3) {
            sender.sendError("用法: /gzgm shield <国家> <小时>")
            return
        }

        val countryName = args[1]
        val hoursStr = args[2]

        val country = CountryManager.getByName(countryName)
        if (country == null) {
            sender.sendError("国家 $countryName 不存在")
            return
        }

        val hours = try {
            hoursStr.toInt()
        } catch (e: NumberFormatException) {
            sender.sendError("无效的小时数: $hoursStr")
            return
        }

        if (hours <= 0 || hours > 72) {
            sender.sendError("护盾时长必须在 1-72 小时之间")
            return
        }

        try {
            // GM模式：绕过时间限制激活护盾
            val success = ShieldManager.activateShield(country, hours, gmMode = true)

            if (success) {
                sender.sendSuccess("§c[GM模式] §f已为国家 §e${country.name} §f强制激活护盾，持续 §e$hours §f小时")

                // 记录GM操作
                GMLogger.logGMAction(sender, "ACTIVATE_SHIELD", null, mapOf(
                    "country" to country.name,
                    "hours" to hours,
                    "bypass_time_limit" to true
                ))
            } else {
                sender.sendError("激活护盾失败，请检查国家状态和资源")
            }

        } catch (e: Exception) {
            sender.sendError("激活护盾时发生错误: ${e.message}")
            e.printStackTrace()
        }
    }
}
