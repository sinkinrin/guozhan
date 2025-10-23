package cn.lcofficial.guozhan.listener

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.config.Config
import cn.lcofficial.guozhan.economy.RegionalTaxSystem
import cn.lcofficial.guozhan.manager.TerritoryManager
import cn.lcofficial.guozhan.config.Message
import cn.lcofficial.guozhan.pluginLogger
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent

/**
 * 税收区域监听器，用于在玩家进入不同税收区域时通知玩家
 * 🔧 v1.3.31: 更新为使用新的税收区域配置
 */
class TaxRegionListener : Listener {

    // 记录玩家当前所在的税收区域
    private val playerRegions = mutableMapOf<Player, Config.Tax.TaxRegionConfig>()
    
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
        val territory = TerritoryManager.getTerritoryBlock(chunk.x, chunk.z, chunk.world.name)
        
        // 获取当前区域
        // 🔧 v1.3.31: 统一使用新的税收区域配置
        val currentRegion = if (territory != null) {
            RegionalTaxSystem.getTerritoryRegion(territory)
        } else {
            // 对于未被占领的区块，根据坐标计算区域
            RegionalTaxSystem.getRegionByCoordinates(chunk.x, chunk.z)
        }

        // 检查玩家是否进入了新的税收区域
        val previousRegion = playerRegions[player]
        if (previousRegion != currentRegion) {
            // 更新玩家当前区域记录
            playerRegions[player] = currentRegion

            // 通知玩家进入新区域 - 使用扩展函数的正确方式
            with(Message) {
                player.sendInfo("§7你已进入 §f${currentRegion.name} §7区域")

                // 显示该区域的税率信息
                player.sendInfo("§7该区域金锭税率: §6${currentRegion.goldRate} §7/ 小时")
                player.sendInfo("§7该区域钻石税率: §b${currentRegion.diamondRate} §7/ 小时")
            }
        }
    }
    
    /**
     * 注册监听器
     */
    fun register() {
        Guozhan.instance.server.pluginManager.registerEvents(this, Guozhan.instance)
        pluginLogger.info("税收区域监听器已注册")
    }
}