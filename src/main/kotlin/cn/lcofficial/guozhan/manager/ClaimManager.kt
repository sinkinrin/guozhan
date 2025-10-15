package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.data.Country
import cn.lcofficial.guozhan.data.TerritoryBlock
import cn.lcofficial.guozhan.pluginLogger
import cn.lcofficial.guozhan.manager.UserManager.user
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
    private data class ActiveClaim(
        val key: String,
        val territory: TerritoryBlock,
        val country: Country,
        val initiator: UUID,
        var startTimeMs: Long,
        var targetTimeMs: Long,
        val participants: MutableSet<UUID> = Collections.synchronizedSet(mutableSetOf()),
        val bossBar: BossBar,
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

        val claim = ActiveClaim(
            key = key,
            territory = territory,
            country = country,
            initiator = initiator.uniqueId,
            startTimeMs = System.currentTimeMillis(),
            targetTimeMs = baseMs,
            bossBar = bossBar,
        )
        claim.participants.add(initiator.uniqueId)
        activeClaims[key] = claim

        initiator.sendMessage("§a开始占领该区块，预计耗时 ${baseMs / 1000}s，请保持在该区块内并注意安全！")

        // 每秒更新一次进度与参与者
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(Guozhan.instance, { _ ->
            val current = activeClaims[key] ?: return@runAtFixedRate

            // 动态统计参与者：同一国家、位于同一区块的玩家
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

            // 参与者系数：1人=1.0倍，之后每多1人+25%速度，加速上限2.0倍
            val speedMultiplier = (1.0 + 0.25 * (current.participants.size - 1)).coerceAtMost(2.0)

            // 计算已用时间并换算为等效进度（加速）
            val elapsed = System.currentTimeMillis() - current.startTimeMs
            val effectiveElapsed = (elapsed * speedMultiplier)
            val progress = (effectiveElapsed / current.targetTimeMs).coerceIn(0.0, 1.0)

            current.bossBar.progress = progress
            current.bossBar.setTitle("§e占领进度: §a${(progress * 100).toInt()}% §7(参与者: ${current.participants.size})")

            // 中断条件：发起者不在区块内或无参与者
            val initiatorOnline = Bukkit.getPlayer(current.initiator)
            val initiatorInChunk = initiatorOnline != null && initiatorOnline.world.name == worldName &&
                initiatorOnline.location.chunk.x == territory.x && initiatorOnline.location.chunk.z == territory.z
            if (!initiatorInChunk || current.participants.isEmpty()) {
                cancelClaimInternal(key, reason = "§c占领已中断（发起者离开或无人参与）")
                return@runAtFixedRate
            }

            if (progress >= 1.0) {
                // 完成，占领写入需要在目标区块的区域线程中执行
                val world = Bukkit.getWorld(worldName) ?: run {
                    cancelClaimInternal(key, "§c占领失败：世界不存在")
                    return@runAtFixedRate
                }
                val claimLoc = Location(world, (territory.x shl 4) + 8.0, 64.0, (territory.z shl 4) + 8.0)
                Bukkit.getRegionScheduler().execute(Guozhan.instance, claimLoc) {
                    val player = Bukkit.getPlayer(current.initiator)
                    if (player == null) {
                        cancelClaimInternal(key, "§c占领失败：玩家离线")
                        return@execute
                    }

                    // 完成时再扣除资源（3个铁锭）
                    if (!player.hasEnoughItem(org.bukkit.Material.IRON_INGOT, 3)) {
                        cancelClaimInternal(key, "§c占领失败：材料不足（需要3个铁锭）")
                        return@execute
                    }
                    player.takeItem(org.bukkit.Material.IRON_INGOT, 3)

                    territory.owner = current.country
                    territory.loyalty = 100
                    territory.save()
                    TerritoryManager.generateRandomResource(territory)

                    // 通知所有参与者
                    current.participants.forEach { uuid ->
                        Bukkit.getPlayer(uuid)?.sendMessage("§a占领成功！领土已归属 ${current.country.name}")
                    }

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
        pluginLogger.info("Claim cancelled: $key, reason=$reason")
    }
}

