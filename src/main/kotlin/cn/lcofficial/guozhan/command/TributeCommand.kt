package cn.lcofficial.guozhan.command

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.data.Country
import cn.lcofficial.guozhan.economy.TributeSystem
import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.manager.UserManager
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.text.SimpleDateFormat
import java.util.*

/**
 * 贡献系统命令处理类
 */
class TributeCommand : CommandExecutor, TabCompleter {
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")
    
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sendHelpMessage(sender)
            return true
        }
        
        when (args[0].lowercase()) {
            "help" -> sendHelpMessage(sender)
            "list" -> listTributeRelations(sender)
            "history" -> showTributeHistory(sender, args)
            "establish" -> establishTributeRelation(sender, args)
            "remove" -> removeTributeRelation(sender, args)
            "process" -> processTributes(sender)
            else -> {
                sender.sendMessage("§c未知的子命令: ${args[0]}")
                sendHelpMessage(sender)
            }
        }
        
        return true
    }
    
    /**
     * 发送帮助信息
     */
    private fun sendHelpMessage(sender: CommandSender) {
        sender.sendMessage("§8§l[ §e贡献系统命令帮助 §8§l]")
        sender.sendMessage("§f/tribute help §7- 显示此帮助信息")
        sender.sendMessage("§f/tribute list §7- 列出所有贡献关系")
        sender.sendMessage("§f/tribute history [国家名] §7- 查看国家的贡献历史")
        sender.sendMessage("§f/tribute establish <贡献国> <接收国> <贡献率> §7- 建立贡献关系")
        sender.sendMessage("§f/tribute remove <贡献国> <接收国> §7- 解除贡献关系")
        
        // 管理员命令
        if (sender.hasPermission("guozhan.admin")) {
            sender.sendMessage("§c管理员命令:")
            sender.sendMessage("§f/tribute process §7- 手动处理所有贡献关系")
        }
    }
    
    /**
     * 列出所有贡献关系
     */
    private fun listTributeRelations(sender: CommandSender) {
        val relations = TributeSystem.getAllTributeRelations()
        
        if (relations.isEmpty()) {
            sender.sendMessage("§e[贡献系统] §f当前没有任何贡献关系")
            return
        }
        
        sender.sendMessage("§8§l[ §e贡献关系列表 §8§l]")
        sender.sendMessage("§f共有 §e${relations.size} §f个贡献关系:")
        
        for (relation in relations) {
            val tributeCountry = CountryManager.getCountry(relation.tributeCountryId)
            val receivingCountry = CountryManager.getCountry(relation.receivingCountryId)
            
            if (tributeCountry != null && receivingCountry != null) {
                sender.sendMessage("§f- §6${tributeCountry.name} §f→ §6${receivingCountry.name} §f(贡献率: §e${relation.tributeRate}%§f)")
            }
        }
    }
    
    /**
     * 显示贡献历史
     */
    private fun showTributeHistory(sender: CommandSender, args: Array<out String>) {
        // 确定要查看的国家
        val countryName = if (args.size > 1) args[1] else {
            if (sender is Player) {
                val user = UserManager.getUser(sender.uniqueId)
                if (user?.country == null) {
                    sender.sendMessage("§c你不属于任何国家，请指定要查看的国家名称")
                    return
                }
                user.country!!.name
            } else {
                sender.sendMessage("§c请指定要查看的国家名称")
                return
            }
        }
        
        // 获取国家
        val country = CountryManager.getByName(countryName)
        if (country == null) {
            sender.sendMessage("§c找不到名为 '$countryName' 的国家")
            return
        }
        
        // 检查权限
        if (sender is Player) {
            val user = UserManager.getUser(sender.uniqueId)
            if (user?.country?.id != country.id && !sender.hasPermission("guozhan.admin")) {
                sender.sendMessage("§c你没有权限查看其他国家的贡献历史")
                return
            }
        }
        
        // 获取历史记录
        val history = TributeSystem.getTributeHistory(country)
        if (history.isEmpty()) {
            sender.sendMessage("§e[贡献系统] §f国家 §6${country.name} §f没有任何贡献历史记录")
            return
        }
        
        // 显示历史记录
        sender.sendMessage("§8§l[ §e${country.name} 的贡献历史 §8§l]")
        sender.sendMessage("§f共有 §e${history.size} §f条记录:")
        
        // 按时间倒序排序
        val sortedHistory = history.sortedByDescending { it.timestamp }
        
        // 显示最近的10条记录
        val displayCount = Math.min(10, sortedHistory.size)
        for (i in 0 until displayCount) {
            val record = sortedHistory[i]
            val sourceCountry = CountryManager.getCountry(record.sourceCountryId)
            val targetCountry = CountryManager.getCountry(record.targetCountryId)
            
            if (sourceCountry != null && targetCountry != null) {
                val date = Date(record.timestamp)
                val formattedDate = dateFormat.format(date)
                
                val direction = if (record.sourceCountryId == country.id) "→" else "←"
                val otherCountry = if (record.sourceCountryId == country.id) targetCountry.name else sourceCountry.name
                
                val materialText = if (record.material == "AUTOMATIC") "自动贡献" else record.material
                
                sender.sendMessage("§f${formattedDate} §7- §f${country.name} §6$direction §f${otherCountry} §7- §f${materialText} x${record.amount} §7(价值: §e${record.value}§7)")
            }
        }
        
        if (sortedHistory.size > 10) {
            sender.sendMessage("§7...还有 §f${sortedHistory.size - 10} §7条记录未显示")
        }
    }
    
    /**
     * 建立贡献关系
     */
    private fun establishTributeRelation(sender: CommandSender, args: Array<out String>) {
        // 检查权限
        if (!checkPermission(sender)) return
        
        // 检查参数
        if (args.size < 4) {
            sender.sendMessage("§c用法: /tribute establish <贡献国> <接收国> <贡献率>")
            return
        }
        
        val tributeCountryName = args[1]
        val receivingCountryName = args[2]
        val tributeRate = args[3].toIntOrNull()
        
        if (tributeRate == null) {
            sender.sendMessage("§c贡献率必须是一个有效的数字")
            return
        }
        
        // 获取国家
        val tributeCountry = CountryManager.getCountryByName(tributeCountryName)
        if (tributeCountry == null) {
            sender.sendMessage("§c找不到名为 '$tributeCountryName' 的国家")
            return
        }
        
        val receivingCountry = CountryManager.getCountryByName(receivingCountryName)
        if (receivingCountry == null) {
            sender.sendMessage("§c找不到名为 '$receivingCountryName' 的国家")
            return
        }

        // v1.3.19修复：防止国家对自己建立进贡关系
        if (tributeCountry.id == receivingCountry.id) {
            sender.sendMessage("§c国家不能对自己建立贡献关系")
            return
        }

        // v1.3.19修复：在建立关系前验证税率范围，提供准确的错误消息
        if (tributeRate !in 5..30) {
            sender.sendMessage("§c贡献率必须在 §e5-30% §c之间")
            return
        }

        // 建立贡献关系
        val result = TributeSystem.establishTributeRelation(tributeCountry, receivingCountry, tributeRate)
        if (result) {
            sender.sendMessage("§a成功建立贡献关系: §f${tributeCountry.name} §a将向 §f${receivingCountry.name} §a贡献 §f${tributeRate}% §a的资源")
        } else {
            sender.sendMessage("§c建立贡献关系失败，可能这两个国家之间已存在贡献关系")
        }
    }
    
    /**
     * 解除贡献关系
     */
    private fun removeTributeRelation(sender: CommandSender, args: Array<out String>) {
        // 检查权限
        if (!checkPermission(sender)) return
        
        // 检查参数
        if (args.size < 3) {
            sender.sendMessage("§c用法: /tribute remove <贡献国> <接收国>")
            return
        }
        
        val tributeCountryName = args[1]
        val receivingCountryName = args[2]
        
        // 获取国家
        val tributeCountry = CountryManager.getCountryByName(tributeCountryName)
        if (tributeCountry == null) {
            sender.sendMessage("§c找不到名为 '$tributeCountryName' 的国家")
            return
        }
        
        val receivingCountry = CountryManager.getCountryByName(receivingCountryName)
        if (receivingCountry == null) {
            sender.sendMessage("§c找不到名为 '$receivingCountryName' 的国家")
            return
        }
        
        // 解除贡献关系
        val result = TributeSystem.removeTributeRelation(tributeCountry, receivingCountry)
        if (result) {
            sender.sendMessage("§a成功解除贡献关系: §f${tributeCountry.name} §a不再向 §f${receivingCountry.name} §a贡献资源")
        } else {
            sender.sendMessage("§c解除贡献关系失败，可能这两个国家之间不存在贡献关系")
        }
    }
    
    /**
     * 手动处理所有贡献关系
     */
    private fun processTributes(sender: CommandSender) {
        // 检查权限
        if (!sender.hasPermission("guozhan.admin")) {
            sender.sendMessage("§c你没有权限执行此命令")
            return
        }
        
        // 处理贡献
        TributeSystem.manualProcessTributes(sender)
    }
    
    /**
     * 检查权限
     */
    private fun checkPermission(sender: CommandSender): Boolean {
        if (sender is Player) {
            val user = UserManager.getUser(sender.uniqueId)
            if (user?.country == null) {
                sender.sendMessage("§c你不属于任何国家")
                return false
            }
            
            if (!user.hasCountryPermission("manage_tribute") && !sender.hasPermission("guozhan.admin")) {
                sender.sendMessage("§c你没有管理贡献关系的权限")
                return false
            }
        }
        return true
    }
    
    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String>? {
        if (args.size == 1) {
            val subCommands = mutableListOf("help", "list", "history")
            
            // 检查权限添加管理命令
            if (sender is Player) {
                val user = UserManager.getUser(sender.uniqueId)
                if (user?.hasCountryPermission("manage_tribute") == true || sender.hasPermission("guozhan.admin")) {
                    subCommands.addAll(listOf("establish", "remove"))
                }
            } else {
                subCommands.addAll(listOf("establish", "remove"))
            }
            
            // 管理员命令
            if (sender.hasPermission("guozhan.admin")) {
                subCommands.add("process")
            }
            
            return subCommands.filter { it.startsWith(args[0].lowercase()) }
        }
        
        // 国家名称补全
        if ((args[0].equals("history", ignoreCase = true) && args.size == 2) ||
            (args[0].equals("establish", ignoreCase = true) && (args.size == 2 || args.size == 3)) ||
            (args[0].equals("remove", ignoreCase = true) && (args.size == 2 || args.size == 3))) {
            
            val countries = CountryManager.getAllCountries().map { it.name }
            return countries.filter { it.lowercase().startsWith(args[args.size - 1].lowercase()) }
        }
        
        // 贡献率补全
        if (args[0].equals("establish", ignoreCase = true) && args.size == 4) {
            return listOf("5", "10", "15", "20", "25", "30")
        }
        
        return emptyList()
    }
}