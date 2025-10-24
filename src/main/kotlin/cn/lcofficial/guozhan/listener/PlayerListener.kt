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
     * 🔧 v1.3.52: 修复服务器重启时占领进度丢失 - 玩家上线时恢复占领进度
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

                // 🔧 v1.3.52: 恢复玩家参与的占领进度
                restorePlayerClaimProgress(player)

                cn.lcofficial.guozhan.Guozhan.instance.logger.info("🔧 [玩家加入] ${player.name} 已重新应用科技效果和职业效果")
            },
            20L // 1秒 = 20 ticks
        )
    }

    /**
     * 🔧 v1.3.52: 恢复玩家参与的占领进度
     */
    private fun restorePlayerClaimProgress(player: Player) {
        try {
            val playerId = player.uniqueId
            val savedProgresses = cn.lcofficial.guozhan.data.ClaimProgress.loadAll()

            for (progress in savedProgresses) {
                // 检查玩家是否是参与者
                if (!progress.participants.contains(playerId)) {
                    continue
                }

                // 检查进度是否过期
                val oneHourAgo = System.currentTimeMillis() - (60 * 60 * 1000)
                if (progress.updatedAt < oneHourAgo || progress.isCompleted()) {
                    continue
                }

                // 获取相关对象
                val territory = progress.getTerritory()
                val country = progress.getCountry()
                if (territory == null || country == null) {
                    continue
                }

                val key = cn.lcofficial.guozhan.manager.ClaimManager.chunkKey(
                    progress.worldName,
                    progress.chunkX,
                    progress.chunkZ
                )

                // 检查是否已经有占领在进行
                val existingClaim = cn.lcofficial.guozhan.manager.ClaimManager.activeClaims[key]
                if (existingClaim != null) {
                    // 已有占领进度，只需添加玩家到 BossBar
                    existingClaim.bossBar.addPlayer(player)
                    player.sendMessage("§a占领进度已恢复！当前进度: ${(progress.calculateProgress() * 100).toInt()}%")
                    cn.lcofficial.guozhan.Guozhan.instance.logger.info("🔧 [占领恢复] ${player.name} 加入现有占领进度 - 领土(${progress.chunkX}, ${progress.chunkZ})")
                } else {
                    // 没有占领进度，创建新的
                    val bossBar = Bukkit.createBossBar("§e占领进度: §a恢复中...", org.bukkit.boss.BarColor.YELLOW, org.bukkit.boss.BarStyle.SOLID)
                    bossBar.addPlayer(player)

                    val claim = cn.lcofficial.guozhan.manager.ClaimManager.ActiveClaim(
                        key = key,
                        territory = territory,
                        country = country,
                        initiator = progress.initiatorId,
                        startTimeMs = progress.startTime,
                        targetTimeMs = progress.targetTime,
                        bossBar = bossBar,
                        claimProgress = progress
                    )

                    // 恢复参与者
                    claim.participants.addAll(progress.participants)
                    cn.lcofficial.guozhan.manager.ClaimManager.activeClaims[key] = claim

                    player.sendMessage("§a占领进度已恢复！当前进度: ${(progress.calculateProgress() * 100).toInt()}%")
                    cn.lcofficial.guozhan.Guozhan.instance.logger.info("🔧 [占领恢复] ${player.name} 恢复占领进度 - 领土(${progress.chunkX}, ${progress.chunkZ})")
                }
            }
        } catch (e: Exception) {
            cn.lcofficial.guozhan.Guozhan.instance.logger.severe("恢复玩家占领进度失败: ${e.message}")
            e.printStackTrace()
        }
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