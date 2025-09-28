package cn.lcofficial.guozhan.listener

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.manager.CoreManager
import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.manager.SpawnManager
import cn.lcofficial.guozhan.manager.UserManager.user
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerRespawnEvent

/**
 * 核心交互监听器
 * 处理玩家与国家核心的交互以及出生相关事件
 */
class CoreListener : Listener {
    
    /**
     * 处理玩家点击事件
     */
    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        // 只处理左键点击方块
        if (event.action != Action.LEFT_CLICK_BLOCK) return
        
        val block = event.clickedBlock ?: return
        val player = event.player
        
        // 检查是否点击的是玻璃
        if (block.type != Material.GLASS) return
        
        // 检查是否在国家核心范围内
        val coreCountry = findCoreCountry(block.location) ?: return
        
        // 尝试攻击核心
        val success = CoreManager.attackCore(player, coreCountry)
        
        if (success) {
            // 取消事件，防止破坏方块
            event.isCancelled = true
        }
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
            org.bukkit.Bukkit.getScheduler().runTaskLater(Guozhan.instance, Runnable {
                SpawnManager.teleportToSafeSpawn(player)
            }, 20L)
        }
    }
    
    /**
     * 处理玩家重生事件
     */
    @EventHandler
    fun onPlayerRespawn(event: PlayerRespawnEvent) {\n        val player = event.player\n        \n        // 检查玩家是否有国家\n        if (SpawnManager.shouldTeleportToSpawn(player)) {\n            // 设置重生点为安全出生点\n            val safeLocation = SpawnManager.findSafeSpawnLocation(player.world)\n            if (safeLocation != null) {\n                event.respawnLocation = safeLocation\n                player.sendMessage(\"§a你已重生至安全区域\")\n            }\n        }\n    }\n    \n    /**\n     * 查找指定位置是否属于某个国家核心的保护区域\n     */\n    private fun findCoreCountry(location: org.bukkit.Location): cn.lcofficial.guozhan.data.Country? {\n        return CountryManager.countries.values.find { country ->\n            val coreLocation = country.getCoreLocation()\n            if (coreLocation != null) {\n                // 检查是否在核心3x3x3范围内\n                val dx = kotlin.math.abs(location.blockX - coreLocation.blockX)\n                val dy = kotlin.math.abs(location.blockY - coreLocation.blockY)\n                val dz = kotlin.math.abs(location.blockZ - coreLocation.blockZ)\n                \n                dx <= 1 && dy <= 1 && dz <= 1\n            } else {\n                false\n            }\n        }\n    }\n    \n    /**\n     * 注册监听器\n     */\n    fun register() {\n        org.bukkit.Bukkit.getPluginManager().registerEvents(this, Guozhan.instance)\n    }\n}", "original_text": "package cn.lcofficial.guozhan.listener\n\nimport cn.lcofficial.guozhan.Guozhan\nimport cn.lcofficial.guozhan.manager.CoreManager\nimport cn.lcofficial.guozhan.manager.CountryManager\nimport cn.lcofficial.guozhan.manager.SpawnManager\nimport cn.lcofficial.guozhan.manager.UserManager.user\nimport org.bukkit.Material\nimport org.bukkit.event.EventHandler\nimport org.bukkit.event.Listener\nimport org.bukkit.event.block.Action\nimport org.bukkit.event.player.PlayerInteractEvent\nimport org.bukkit.event.player.PlayerJoinEvent\nimport org.bukkit.event.player.PlayerRespawnEvent\n\n/**\n * 核心交互监听器\n * 处理玩家与国家核心的交互以及出生相关事件\n */\nclass CoreListener : Listener {\n    \n    /**\n     * 处理玩家点击事件\n     */\n    @EventHandler\n    fun onPlayerInteract(event: PlayerInteractEvent) {\n        // 只处理左键点击方块\n        if (event.action != Action.LEFT_CLICK_BLOCK) return\n        \n        val block = event.clickedBlock ?: return\n        val player = event.player\n        \n        // 检查是否点击的是玻璃\n        if (block.type != Material.GLASS) return\n        \n        // 检查是否在国家核心范围内\n        val coreCountry = findCoreCountry(block.location) ?: return\n        \n        // 尝试攻击核心\n        val success = CoreManager.attackCore(player, coreCountry)\n        \n        if (success) {\n            // 取消事件，防止破坏方块\n            event.isCancelled = true\n        }\n    }\n    \n    /**\n     * 处理玩家加入事件\n     */\n    @EventHandler\n    fun onPlayerJoin(event: PlayerJoinEvent) {\n        val player = event.player\n        \n        // 检查是否需要传送到安全出生点\n        if (SpawnManager.shouldTeleportToSpawn(player)) {\n            // 延迟1秒执行传送，确保玩家完全加载\n            org.bukkit.Bukkit.getScheduler().runTaskLater(Guozhan.instance, Runnable {\n                SpawnManager.teleportToSafeSpawn(player)\n            }, 20L)\n        }\n    }\n    \n    /**\n     * 处理玩家重生事件\n     */\n    @EventHandler\n    fun onPlayerRespawn(event: PlayerRespawnEvent) {", "replace_all": false}]