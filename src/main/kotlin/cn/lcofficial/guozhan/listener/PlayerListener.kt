package cn.lcofficial.guozhan.listener

import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.manager.UserManager
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerRespawnEvent
import kotlin.random.Random

object PlayerListener : Listener {
    
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        
        // 首次进入游戏或没有国家时随机传送
        val user = UserManager.getUser(player.uniqueId)
        if (user == null || user.country == null) {
            val spawnLocation = findRandomSpawnLocation()
            if (spawnLocation != null) {
                player.teleport(spawnLocation)
            }
        }
    }
    
    @EventHandler
    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        
        // 无国家死亡时随机传送
        val user = UserManager.getUser(player.uniqueId)
        if (user == null || user.country == null) {
            val spawnLocation = findRandomSpawnLocation()
            if (spawnLocation != null) {
                event.respawnLocation = spawnLocation
            }
        }
    }
    
    private fun findRandomSpawnLocation(): Location? {
        val world = Bukkit.getWorld("world") ?: return null
        
        // 在直径15000内随机生成坐标
        val maxDistance = 7500
        var attempts = 0
        val maxAttempts = 100
        
        while (attempts < maxAttempts) {
            val x = Random.nextInt(-maxDistance, maxDistance)
            val z = Random.nextInt(-maxDistance, maxDistance)
            
            // 获取最高方块
            val block = world.getHighestBlockAt(x, z)
            
            // 检查是否在水中
            if (block.type == Material.WATER || block.type == Material.LAVA) {
                attempts++
                continue
            }
            
            // 检查是否在已有国家领土内
            val territory = cn.lcofficial.guozhan.manager.TerritoryManager.getTerritory(
                block.x shr 4, block.z shr 4
            )
            
            if (territory == null) {
                // 找到无主区域
                return block.location.add(0.5, 1.0, 0.5)
            }
            
            attempts++
        }
        
        return null
    }
}