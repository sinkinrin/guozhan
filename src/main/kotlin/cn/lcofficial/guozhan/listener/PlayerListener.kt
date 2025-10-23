package cn.lcofficial.guozhan.listener

import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.manager.TechEffectManager
import cn.lcofficial.guozhan.manager.TeleportManager
import cn.lcofficial.guozhan.manager.TestEnvironmentManager
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
     * 🔧 v1.3.48: 修复离线玩家科技效果丢失问题 - 确保玩家登录时获得最新科技效果
     */
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player

        // 🔧 v1.3.48: 延迟1秒应用科技效果和职业效果，确保玩家完全加载
        // 这样可以覆盖玩家离线期间完成的科技研究
        Bukkit.getGlobalRegionScheduler().runDelayed(
            cn.lcofficial.guozhan.Guozhan.instance,
            { _ ->
                // 🔧 v1.3.48: 重新应用所有科技效果，确保获得离线期间完成的科技
                TechEffectManager.updatePlayerEffects(player)

                // 🔧 v1.3.48: 重新应用职业效果
                val user = UserManager.getUser(player.uniqueId)
                if (user?.profession != null) {
                    cn.lcofficial.guozhan.manager.ProfessionManager.applyProfessionEffects(
                        player,
                        user.profession!!,
                        user.professionLevel
                    )
                }

                // 测试环境：给予玩家启动资源
                TestEnvironmentManager.givePlayerStartupResources(player)

                // 🔧 v1.3.51: 修复玩家背包中的疆域地图渲染器
                cn.lcofficial.guozhan.util.TerritoryMapUtil.restorePlayerTerritoryMaps(player)

                cn.lcofficial.guozhan.Guozhan.instance.logger.info("🔧 [玩家加入] ${player.name} 已重新应用科技效果和职业效果")
            },
            20L // 1秒 = 20 ticks
        )
    }

    /**
     * 处理玩家重生事件
     * 🔧 v1.3.48: 修复离线玩家科技效果丢失问题 - 确保所有玩家重生时都重新应用科技效果
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

                        // 🔧 v1.3.48: 重生后应用科技效果和职业效果
                        applyPlayerEffectsAfterRespawn(player)
                    }
            }
        } else {
            // 🔧 v1.3.48: 有国家的玩家重生时也需要重新应用科技效果
            applyPlayerEffectsAfterRespawn(player)
        }
    }

    /**
     * 🔧 v1.3.48: 玩家重生后应用科技效果和职业效果的通用方法
     * 确保玩家死亡重生后科技效果不会丢失
     */
    private fun applyPlayerEffectsAfterRespawn(player: Player) {
        Bukkit.getGlobalRegionScheduler().runDelayed(
            cn.lcofficial.guozhan.Guozhan.instance,
            { _ ->
                // 🔧 v1.3.48: 重新应用科技效果，确保重生后不丢失
                TechEffectManager.updatePlayerEffects(player)

                // 🔧 v1.3.48: 重新应用职业效果
                val user = UserManager.getUser(player.uniqueId)
                if (user?.profession != null) {
                    cn.lcofficial.guozhan.manager.ProfessionManager.applyProfessionEffects(
                        player,
                        user.profession!!,
                        user.professionLevel
                    )
                }

                cn.lcofficial.guozhan.Guozhan.instance.logger.info("🔧 [玩家重生] ${player.name} 已重新应用科技效果和职业效果")
            },
            20L // 1秒后应用效果
        )
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
     * 🔧 v1.3.48: 修复离线玩家科技效果丢失问题 - 清理效果并标记需要刷新
     */
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player

        // 清理传送状态（不涉及玩家实体操作，线程安全）
        TeleportManager.onPlayerQuit(player)

        // 🔧 v1.3.48: 在EntityScheduler中安全清理所有玩家效果
        player.scheduler.run(cn.lcofficial.guozhan.Guozhan.instance, { _ ->
            try {
                // 🔧 v1.3.48: 清理科技效果，确保下次登录时干净地重新应用
                TechEffectManager.removePlayerEffectsSafe(player)

                // 🔧 v1.3.48: 清理职业效果
                val user = cn.lcofficial.guozhan.manager.UserManager.getUser(player.uniqueId)
                if (user?.profession != null) {
                    // 清除职业药水效果
                    cn.lcofficial.guozhan.manager.ProfessionManager.clearProfessionEffects(player)
                }

                cn.lcofficial.guozhan.Guozhan.instance.logger.info("🔧 [玩家退出] ${player.name} 已清理所有效果，下次登录将重新应用")
            } catch (e: Exception) {
                cn.lcofficial.guozhan.Guozhan.instance.logger.warning("清理玩家 ${player.name} 退出时的效果出错: ${e.message}")
            }
        }, null)
    }
}