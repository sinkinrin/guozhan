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

    /**
     * 处理玩家重生事件
     * 注意：玩家加入事件的随机出生逻辑已移至RandomSpawnListener
     */
    @EventHandler
    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        val player = event.player

        // 无国家死亡时随机传送 - 委托给RandomSpawnManager处理
        val user = UserManager.getUser(player.uniqueId)
        if (user == null || user.country == null) {
            // 使用Folia的RegionScheduler进行异步处理
            val server = Bukkit.getServer()
            val regionScheduler = server.regionScheduler

            regionScheduler.execute(
                cn.lcofficial.guozhan.Guozhan.instance,
                player.location
            ) {
                cn.lcofficial.guozhan.manager.RandomSpawnManager.teleportPlayerToRandomSpawn(player)
                    .thenAccept { success ->
                        if (!success) {
                            // 如果随机出生失败，使用世界默认出生点
                            val world = player.world
                            event.respawnLocation = world.spawnLocation
                        }
                    }
            }
        }
    }
}