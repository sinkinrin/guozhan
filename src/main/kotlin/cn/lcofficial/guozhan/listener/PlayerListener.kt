package cn.lcofficial.guozhan.listener

import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.manager.TechEffectManager
import cn.lcofficial.guozhan.manager.TeleportManager
import cn.lcofficial.guozhan.manager.UserManager
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import kotlin.random.Random

object PlayerListener : Listener {

    /**
     * 处理玩家加入事件
     * 为玩家应用科技效果
     */
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player

        // 延迟1秒应用科技效果和职业效果，确保玩家完全加载
        Bukkit.getGlobalRegionScheduler().runDelayed(
            cn.lcofficial.guozhan.Guozhan.instance,
            { _ ->
                TechEffectManager.updatePlayerEffects(player)

                // v1.3.13修复：重新应用职业效果
                val user = UserManager.getUser(player.uniqueId)
                if (user?.profession != null) {
                    cn.lcofficial.guozhan.manager.ProfessionManager.applyProfessionEffects(
                        player,
                        user.profession!!,
                        user.professionLevel
                    )
                }
            },
            20L // 1秒 = 20 ticks
        )
    }

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

                        // 重生后应用科技效果和职业效果
                        Bukkit.getGlobalRegionScheduler().runDelayed(
                            cn.lcofficial.guozhan.Guozhan.instance,
                            { _ ->
                                TechEffectManager.updatePlayerEffects(player)

                                // v1.3.13修复：重新应用职业效果
                                val user = UserManager.getUser(player.uniqueId)
                                if (user?.profession != null) {
                                    cn.lcofficial.guozhan.manager.ProfessionManager.applyProfessionEffects(
                                        player,
                                        user.profession!!,
                                        user.professionLevel
                                    )
                                }
                            },
                            20L // 1秒后应用效果
                        )
                    }
            }
        }
    }

    /**
     * 处理玩家受到伤害事件
     * 如果玩家正在传送，则取消传送
     */
    @EventHandler
    fun onPlayerDamage(event: EntityDamageEvent) {
        val entity = event.entity
        if (entity is Player) {
            TeleportManager.onPlayerDamaged(entity)
        }
    }

    /**
     * 处理玩家离线事件
     * 清理玩家的传送状态和科技效果
     */
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        TeleportManager.onPlayerQuit(player)
        TechEffectManager.removePlayerEffects(player)
    }
}