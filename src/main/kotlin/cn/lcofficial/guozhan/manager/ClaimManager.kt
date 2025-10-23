package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.data.ClaimProgress
import cn.lcofficial.guozhan.data.Country
import cn.lcofficial.guozhan.data.TerritoryBlock
import cn.lcofficial.guozhan.pluginLogger
import cn.lcofficial.guozhan.manager.UserManager.user
import cn.lcofficial.guozhan.util.hasEnoughItem
import cn.lcofficial.guozhan.util.takeItem
import org.bukkit.Bukkit
import org.bukkit.boss.BossBar
import org.bukkit.Location
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 领土占领计时与进度管理
 * - 启动/取消占领
 * - BossBar 进度
 * - 多人协作加速
 * - 被攻击/离开区域中断
 */
object ClaimManager {
    data class ActiveClaim(
        val key: String,
        val territory: TerritoryBlock,
        val country: Country,
        val initiator: UUID,
        var startTimeMs: Long,
        var targetTimeMs: Long,
        val participants: MutableSet<UUID> = Collections.newSetFromMap(ConcurrentHashMap<UUID, Boolean>()),
        val bossBar: BossBar,
        // 🔧 v1.3.48: 新增占领进度持久化支持
        val claimProgress: ClaimProgress
    )

    private val activeClaims: MutableMap<String, ActiveClaim> = ConcurrentHashMap()

    private fun chunkKey(worldName: String, x: Int, z: Int): String = "$worldName:$x:$z"

    fun isClaiming(player: Player): Boolean = activeClaims.values.any { it.participants.contains(player.uniqueId) }

    fun getClaimKeyByPlayer(player: Player): String? = activeClaims.values.firstOrNull { it.participants.contains(player.uniqueId) }?.key

    fun isTerritoryBeingClaimed(worldName: String, x: Int, z: Int): Boolean = activeClaims.containsKey(chunkKey(worldName, x, z))

    fun startClaim(initiator: Player, territory: TerritoryBlock, country: Country) {
        val worldName = territory.world
        val key = chunkKey(worldName, territory.x, territory.z)
        if (activeClaims.containsKey(key)) {
            initiator.sendMessage("§e该区块正在被占领中，请稍候...")
            return
        }

        // 计算基础时长（毫秒）
        val baseMs = TerritoryManager.calculateClaimTime(territory, initiator, country)
        val bossBar = Bukkit.createBossBar("§e占领进度: §a0%", BarColor.YELLOW, BarStyle.SOLID)
        bossBar.addPlayer(initiator)

        val participantSet = Collections.newSetFromMap(ConcurrentHashMap<UUID, Boolean>()).apply {
            add(initiator.uniqueId)
        }

        // 🔧 v1.3.48: 创建占领进度持久化对象
        val claimProgress = ClaimProgress(
            id = UUID.randomUUID(),
            territoryId = territory.id,
            countryId = country.id,
            initiatorId = initiator.uniqueId,
            startTime = System.currentTimeMillis(),
            targetTime = baseMs,
            initialParticipants = participantSet,
            worldName = worldName,
            chunkX = territory.x,
            chunkZ = territory.z,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        val claim = ActiveClaim(
            key = key,
            territory = territory,
            country = country,
            initiator = initiator.uniqueId,
            startTimeMs = System.currentTimeMillis(),
            targetTimeMs = baseMs,
            bossBar = bossBar,
            claimProgress = claimProgress
        )
        claim.participants.addAll(participantSet)
        activeClaims[key] = claim

        // 🔧 v1.3.48: 保存占领进度到数据库
        if (!claimProgress.save(async = false)) {
            initiator.sendMessage("§c占领失败：无法保存进度，请重试")
            return
        }

        initiator.sendMessage("§a开始占领该区块，预计耗时 ${baseMs / 1000}s，请保持在该区块内并注意安全！")

        // 每秒更新一次进度与参与者
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(Guozhan.instance, { _ ->
            val current = activeClaims[key] ?: return@runAtFixedRate

            // 🔧 v1.3.36: 修复Folia线程安全问题 - 在目标区块的区域线程中检查参与者
            val world = Bukkit.getWorld(worldName) ?: run {
                cancelClaimInternal(key, "§c占领失败：世界不存在")
                return@runAtFixedRate
            }
            val claimLoc = Location(world, (territory.x shl 4) + 8.0, 64.0, (territory.z shl 4) + 8.0)

            Bukkit.getRegionScheduler().execute(Guozhan.instance, claimLoc) {
                // 在正确的区域线程中动态统计参与者：同一国家、位于同一区块的玩家
                val participantsNow = Bukkit.getOnlinePlayers()
                    .filter { p ->
                        val u = p.user()
                        u?.country?.id == current.country.id &&
                        p.world.name == worldName && p.location.chunk.x == territory.x && p.location.chunk.z == territory.z
                    }
                    .map { it.uniqueId }
                    .toSet()

                current.participants.clear()
                current.participants.addAll(participantsNow)

                // 🔧 v1.3.48: 更新持久化进度中的参与者
                current.claimProgress.participants.clear()
                current.claimProgress.participants.addAll(participantsNow)

                // 参与者系数：1人=1.0倍，之后每多1人+25%速度，加速上限2.0倍
                val speedMultiplier = (1.0 + 0.25 * (current.participants.size - 1)).coerceAtMost(2.0)

                // 计算已用时间并换算为等效进度（加速）
                val elapsed = System.currentTimeMillis() - current.startTimeMs
                val effectiveElapsed = (elapsed * speedMultiplier)
                val progress = (effectiveElapsed / current.targetTimeMs).coerceIn(0.0, 1.0)

                current.bossBar.progress = progress
                current.bossBar.setTitle("§e占领进度: §a${(progress * 100).toInt()}% §7(参与者: ${current.participants.size})")

                // 🔧 v1.3.48: 每10秒保存一次占领进度到数据库
                val currentTime = System.currentTimeMillis()
                if (currentTime - current.claimProgress.updatedAt >= 10000) { // 10秒
                    current.claimProgress.save()
                }

                // 中断条件：发起者不在区块内或无参与者
                val initiatorOnline = Bukkit.getPlayer(current.initiator)
                val initiatorInChunk = initiatorOnline != null && initiatorOnline.world.name == worldName &&
                    initiatorOnline.location.chunk.x == territory.x && initiatorOnline.location.chunk.z == territory.z
                if (!initiatorInChunk || current.participants.isEmpty()) {
                    cancelClaimInternal(key, reason = "§c占领已中断（发起者离开或无人参与）")
                    return@execute
                }

                // 检查是否完成
                if (progress >= 1.0) {
                    // 完成时再扣除资源（3个铁锭）
                    val player = Bukkit.getPlayer(current.initiator)
                    if (player == null) {
                        cancelClaimInternal(key, "§c占领失败：玩家离线")
                        return@execute
                    }

                    if (!player.hasEnoughItem(org.bukkit.Material.IRON_INGOT, 3)) {
                        cancelClaimInternal(key, "§c占领失败：材料不足（需要3个铁锭）")
                        return@execute
                    }
                    player.takeItem(org.bukkit.Material.IRON_INGOT, 3)

                    territory.owner = current.country
                    territory.loyalty = 100
                    // 🔧 v1.3.48: 修复数据丢失风险 - 占领完成时使用同步保存确保数据安全
                    val saveSuccess = territory.save(async = false)
                    if (!saveSuccess) {
                        cancelClaimInternal(key, "§c占领失败：数据保存失败，请重试")
                        return@execute
                    }
                    TerritoryManager.generateRandomResource(territory)

                    // 通知所有参与者
                    current.participants.forEach { uuid ->
                        Bukkit.getPlayer(uuid)?.sendMessage("§a占领成功！领土已归属 ${current.country.name}")
                    }

                    // 🔧 v1.3.24: 触发地图更新
                    Guozhan.instance.squaremapIntegration.triggerMapUpdate()

                    // 🔧 v1.3.48: 占领完成后删除持久化进度
                    current.claimProgress.delete()

                    // 清理
                    current.bossBar.removeAll()
                    activeClaims.remove(key)
                }
            }


        }, 20L, 20L)
    }

    fun cancelClaimByDamage(player: Player) {
        val key = getClaimKeyByPlayer(player) ?: return
        cancelClaimInternal(key, "§c占领已中断：你受到攻击！")
    }

    fun cancelClaimByMove(player: Player) {
        val key = getClaimKeyByPlayer(player) ?: return
        cancelClaimInternal(key, "§c占领已中断：你离开了占领区域！")
    }

    private fun cancelClaimInternal(key: String, reason: String) {
        val claim = activeClaims.remove(key) ?: return
        claim.participants.forEach { uuid -> Bukkit.getPlayer(uuid)?.sendMessage(reason) }
        claim.bossBar.removeAll()

        // 🔧 v1.3.48: 占领取消时删除持久化进度
        claim.claimProgress.delete()

        pluginLogger.info("Claim cancelled: $key, reason=$reason")
    }

    /**
     * 🔧 v1.3.48: 获取所有活跃占领进度（用于服务器关闭时保存）
     */
    fun getAllActiveClaims(): Collection<ActiveClaim> = activeClaims.values

    /**
     * 🔧 v1.3.48: 服务器启动时恢复占领进度
     */
    fun restoreClaimProgress() {
        try {
            val savedProgresses = ClaimProgress.loadAll()
            var restoredCount = 0
            var expiredCount = 0

            for (progress in savedProgresses) {
                // 检查进度是否过期（超过1小时）
                val oneHourAgo = System.currentTimeMillis() - (60 * 60 * 1000)
                if (progress.updatedAt < oneHourAgo) {
                    progress.delete()
                    expiredCount++
                    continue
                }

                // 检查是否已完成
                if (progress.isCompleted()) {
                    progress.delete()
                    expiredCount++
                    continue
                }

                // 获取相关对象
                val territory = progress.getTerritory()
                val country = progress.getCountry()
                val initiator = Bukkit.getPlayer(progress.initiatorId)

                if (territory == null || country == null) {
                    progress.delete()
                    expiredCount++
                    continue
                }

                // 如果发起者在线，恢复占领进度
                if (initiator != null) {
                    val key = chunkKey(progress.worldName, progress.chunkX, progress.chunkZ)

                    // 检查是否已经有占领在进行
                    if (activeClaims.containsKey(key)) {
                        progress.delete()
                        continue
                    }

                    val bossBar = Bukkit.createBossBar("§e占领进度: §a恢复中...", BarColor.YELLOW, BarStyle.SOLID)
                    bossBar.addPlayer(initiator)

                    val claim = ActiveClaim(
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
                    activeClaims[key] = claim

                    initiator.sendMessage("§a占领进度已恢复！当前进度: ${(progress.calculateProgress() * 100).toInt()}%")
                    restoredCount++
                } else {
                    // 发起者不在线，删除进度
                    progress.delete()
                    expiredCount++
                }
            }

            if (restoredCount > 0 || expiredCount > 0) {
                pluginLogger.info("🔧 v1.3.48: 占领进度恢复完成 - 恢复${restoredCount}个，清理${expiredCount}个过期进度")
            }

            // 清理过期进度
            ClaimProgress.cleanupExpired()

        } catch (e: Exception) {
            pluginLogger.severe("恢复占领进度失败: ${e.message}")
            e.printStackTrace()
        }
    }
}
