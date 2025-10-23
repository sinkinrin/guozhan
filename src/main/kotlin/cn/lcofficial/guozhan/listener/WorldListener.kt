package cn.lcofficial.guozhan.listener

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.pluginLogger
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldUnloadEvent

/**
 * 世界加载/卸载监听器
 * 🔧 v1.3.28: 处理运行时动态加载的世界，确保 Squaremap 图层正确注册
 */
class WorldListener : Listener {
    
    /**
     * 监听世界加载事件
     * 当新世界加载时，为其注册 Squaremap 图层
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldLoad(event: WorldLoadEvent) {
        try {
            val world = event.world
            pluginLogger.info("检测到世界加载: ${world.name}")
            
            // 为新加载的世界注册 Squaremap 图层
            Guozhan.instance.squaremapIntegration.registerWorldLayers(world)
            
            // 触发地图更新以显示该世界的领土
            Guozhan.instance.squaremapIntegration.triggerMapUpdate()
        } catch (e: Exception) {
            pluginLogger.warning("处理世界加载事件时出错: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 监听世界卸载事件
     * 当世界卸载时，注销其 Squaremap 图层
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldUnload(event: WorldUnloadEvent) {
        try {
            val world = event.world
            pluginLogger.info("检测到世界卸载: ${world.name}")
            
            // 注销该世界的 Squaremap 图层
            Guozhan.instance.squaremapIntegration.unregisterWorldLayers(world)
        } catch (e: Exception) {
            pluginLogger.warning("处理世界卸载事件时出错: ${e.message}")
            e.printStackTrace()
        }
    }
}

