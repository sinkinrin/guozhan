package cn.lcofficial.guozhan.listener

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.economy.RegionalTaxSystem
import cn.lcofficial.guozhan.manager.TerritoryManager
import cn.lcofficial.guozhan.util.Message
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent

/**
 * 税收区域监听器，用于在玩家进入不同税收区域时通知玩家
 */
class TaxRegionListener : Listener {
    
    // 记录玩家当前所在的税收区域
    private val playerRegions = mutableMapOf<Player, RegionalTaxSystem.TaxRegion>()
    
    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        val player = event.player
        
        // 只在玩家跨区块移动时检查
        if (event.from.blockX shr 4 == event.to.blockX shr 4 && 
            event.from.blockZ shr 4 == event.to.blockZ shr 4) {
            return
        }
        
        // 获取玩家当前所在区块
        val chunk = player.location.chunk
        val territory = TerritoryManager.getTerritoryBlock(chunk)
        
        // 获取当前区域
        val currentRegion = if (territory != null) {
            RegionalTaxSystem.getTerritoryRegion(territory)
        } else {
            // 对于未被占领的区块，根据坐标计算区域
            RegionalTaxSystem.TaxRegion.getRegionByCoordinates(chunk.x, chunk.z)
        }
        
        // 检查玩家是否进入了新的税收区域
        val previousRegion = playerRegions[player]
        if (previousRegion != currentRegion) {
            // 更新玩家当前区域记录
            playerRegions[player] = currentRegion
            
            // 通知玩家进入新区域
            Message.sendInfo(player, "§7你已进入 §f${currentRegion.displayName} §7区域")
            
            // 显示该区域的税率信息
            Message.sendInfo(player, "§7该区域金锭税率: §6${currentRegion.goldRate} §7/ 小时")
            Message.sendInfo(player, "§7该区域钻石税率: §b${currentRegion.diamondRate} §7/ 小时")
        }
    }
    
    /**
     * 注册监听器
     */
    fun register() {
        Guozhan.instance.server.pluginManager.registerEvents(this, Guozhan.instance)
        Guozhan.pluginLogger.info("税收区域监听器已注册")
    }
}