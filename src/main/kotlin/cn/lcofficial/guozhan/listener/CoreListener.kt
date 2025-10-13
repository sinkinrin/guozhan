package cn.lcofficial.guozhan.listener

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.manager.CoreManager
import cn.lcofficial.guozhan.manager.SpawnManager
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerRespawnEvent

/**
 * 核心交互监听器
 * 处理玩家与国家核心的交互、保护核心方块和玻璃
 */
class CoreListener : Listener {

    /**
     * 处理方块破坏事件 - 保护核心和保护玻璃
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val block = event.block
        val player = event.player
        val location = block.location

        // 检查是否在核心保护范围内
        val coreCountry = CoreManager.findCoreCountry(location) ?: return
        val coreLocation = coreCountry.getCoreLocation() ?: return

        // 检查是否是核心方块（信标）
        if (block.type == Material.BEACON && location.distance(coreLocation) < 1.0) {
            event.isCancelled = true
            player.sendMessage("§c核心方块无法被破坏！只能通过攻击来摧毁核心。")
            return
        }

        // 检查是否是保护玻璃
        if (block.type == Material.GLASS && location.distance(coreLocation) <= 2.0) {
            event.isCancelled = true
            player.sendMessage("§c核心保护玻璃无法被破坏！请通过左键点击来攻击核心。")

            // 立即恢复被破坏的玻璃
            scheduleGlassRestore(location)
            return
        }
    }

    /**
     * 处理玩家点击事件 - 核心攻击机制
     */
    @EventHandler(priority = EventPriority.HIGH)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        // 只处理左键点击方块
        if (event.action != Action.LEFT_CLICK_BLOCK) return

        val block = event.clickedBlock ?: return
        val player = event.player

        // 检查是否点击的是玻璃
        if (block.type != Material.GLASS) return

        // 检查是否在国家核心范围内
        val coreCountry = CoreManager.findCoreCountry(block.location) ?: return

        // 尝试攻击核心
        val success = CoreManager.attackCore(player, coreCountry)

        if (success) {
            // 取消事件，防止破坏方块
            event.isCancelled = true
        }
    }

    /**
     * 安排玻璃恢复任务
     */
    private fun scheduleGlassRestore(location: Location) {
        // 使用Folia的RegionScheduler在正确的区域中恢复玻璃
        Bukkit.getRegionScheduler().runDelayed(
            Guozhan.instance,
            location,
            { _ ->
                if (location.block.type != Material.GLASS) {
                    location.block.type = Material.GLASS
                }
            },
            5L // 延迟0.25秒恢复
        )
    }
    
    /**
     * 处理玩家加入事件
     */
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        
        // 检查是否需要传送到安全出生点
        if (SpawnManager.shouldTeleportToSpawn(player)) {
            // 延迟1秒执行传送，确保玩家完全加载
            // 使用Folia的EntityScheduler进行玩家相关操作
            player.scheduler.runDelayed(Guozhan.instance, {
                SpawnManager.teleportToSafeSpawn(player)
            }, null, 20L)
        }
    }
    
    /**
     * 处理玩家重生事件
     */
    @EventHandler
    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        
        // 检查玩家是否有国家
        if (SpawnManager.shouldTeleportToSpawn(player)) {
            // 设置重生点为安全出生点
            val safeLocation = SpawnManager.getWorldSpawn(player.world)
            event.respawnLocation = safeLocation
            player.sendMessage("§a你已重生至安全区域")
        }
    }
    
    /**
     * 注册监听器
     */
    fun register() {
        org.bukkit.Bukkit.getPluginManager().registerEvents(this, Guozhan.instance)
    }
}
