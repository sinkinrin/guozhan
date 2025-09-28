package cn.lcofficial.guozhan.command

import cn.lcofficial.guozhan.data.TerritoryBlock
import cn.lcofficial.guozhan.economy.RegionalTaxSystem
import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.manager.TerritoryManager
import cn.lcofficial.guozhan.manager.UserManager
import cn.lcofficial.guozhan.config.Message
import cn.lcofficial.guozhan.config.Message.sendError
import cn.lcofficial.guozhan.config.Message.sendInfo
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.text.DecimalFormat

/**
 * 税收命令处理器
 */
class TaxCommand : CommandExecutor, TabCompleter {
    
    private val decimalFormat = DecimalFormat("#0.000")
    
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§c该命令只能由玩家执行")
            return true
        }
        
        if (args.isEmpty()) {
            showTaxInfo(sender)
            return true
        }
        
        when (args[0].lowercase()) {
            "region" -> showRegionInfo(sender)
            "country" -> showCountryTaxInfo(sender)
            "help" -> showHelp(sender)
            else -> showHelp(sender)
        }
        
        return true
    }
    
    /**
     * 显示税收帮助信息
     */
    private fun showHelp(player: Player) {
        player.sendInfo("§6===== §f税收系统帮助 §6=====")
        player.sendInfo("§7/tax §f- 显示当前区块的税收信息")
        player.sendInfo("§7/tax region §f- 显示所有税收区域信息")
        player.sendInfo("§7/tax country §f- 显示你所在国家的税收信息")
        player.sendInfo("§7/tax help §f- 显示此帮助信息")
    }
    
    /**
     * 显示当前区块的税收信息
     */
    private fun showTaxInfo(player: Player) {
        val chunk = player.location.chunk
        val territory = TerritoryManager.getTerritoryBlock(chunk.x, chunk.z, chunk.world.name)
        
        if (territory == null) {
            player.sendInfo( "§c当前区块未被任何国家占领，无税收信息")
            return
        }
        
        val region = RegionalTaxSystem.getTerritoryRegion(territory)
        val goldRate = region.goldRate
        val diamondRate = region.diamondRate
        
        player.sendInfo( "§6===== §f区块税收信息 §6=====")
        player.sendInfo( "§7区块坐标: §f${territory.x}, ${territory.z} (${territory.world})")
        player.sendInfo( "§7所属国家: §f${territory.owner?.name ?: "无"}")
        player.sendInfo( "§7税收区域: §f${region.displayName}")
        player.sendInfo( "§7金锭税率: §6${decimalFormat.format(goldRate)} §7/ 小时")
        player.sendInfo( "§7钻石税率: §b${decimalFormat.format(diamondRate)} §7/ 小时")
    }
    
    /**
     * 显示所有税收区域信息
     */
    private fun showRegionInfo(player: Player) {
        player.sendInfo( "§6===== §f税收区域信息 §6=====")
        
        for (region in RegionalTaxSystem.TaxRegion.values()) {
            player.sendInfo( "§7${region.displayName} §f(半径 ${region.range} 格):")
            player.sendInfo( "  §7金锭税率: §6${decimalFormat.format(region.goldRate)} §7/ 小时")
            player.sendInfo( "  §7钻石税率: §b${decimalFormat.format(region.diamondRate)} §7/ 小时")
        }
    }
    
    /**
     * 显示国家税收信息
     */
    private fun showCountryTaxInfo(player: Player) {
        val user = UserManager.getUser(player.uniqueId)
        
        if (user == null || user.countryId == null) {
            player.sendError( "你没有加入任何国家")
            return
        }
        
        val country = CountryManager.getCountry(user.countryId!!)
        
        if (country == null) {
            player.sendError( "无法获取国家信息")
            return
        }
        
        val goldTaxPerHour = RegionalTaxSystem.calculateTotalGoldTaxPerHour(country)
        val diamondTaxPerHour = RegionalTaxSystem.calculateTotalDiamondTaxPerHour(country)
        
        val territories = TerritoryManager.getTerritoriesByCountry(country)
        val regionCounts = mutableMapOf<RegionalTaxSystem.TaxRegion, Int>()
        
        // 统计各区域的区块数量
        for (territory in territories) {
            val region = RegionalTaxSystem.getTerritoryRegion(territory)
            regionCounts[region] = (regionCounts[region] ?: 0) + 1
        }
        
        player.sendInfo( "§6===== §f国家税收信息 §6=====")
        player.sendInfo( "§7国家: §f${country.name}")
        player.sendInfo( "§7总区块数: §f${territories.size}")
        player.sendInfo( "§7每小时金锭收入: §6${decimalFormat.format(goldTaxPerHour)}")
        player.sendInfo( "§7每小时钻石收入: §b${decimalFormat.format(diamondTaxPerHour)}")
        player.sendInfo( "§7每日金锭收入: §6${decimalFormat.format(goldTaxPerHour * 24)}")
        player.sendInfo( "§7每日钻石收入: §b${decimalFormat.format(diamondTaxPerHour * 24)}")
        
        player.sendInfo( "§7区域分布:")
        for (region in RegionalTaxSystem.TaxRegion.values()) {
            val count = regionCounts[region] ?: 0
            if (count > 0) {
                player.sendInfo( "  §7${region.displayName}: §f${count} 区块")
            }
        }
    }
    
    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (sender !is Player) return emptyList()
        
        if (args.size == 1) {
            val subCommands = listOf("region", "country", "help")
            return subCommands.filter { it.startsWith(args[0].lowercase()) }
        }
        
        return emptyList()
    }
}