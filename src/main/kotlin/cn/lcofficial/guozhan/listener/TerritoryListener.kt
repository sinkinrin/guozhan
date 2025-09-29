package cn.lcofficial.guozhan.listener

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.config.Message
import cn.lcofficial.guozhan.data.Rank
import cn.lcofficial.guozhan.manager.TerritoryManager
import cn.lcofficial.guozhan.manager.TerritoryManager.territoryBlock
import cn.lcofficial.guozhan.manager.UserManager.user
// 移除不存在的Scheduler导入，使用Folia调度器
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent

object TerritoryListener : Listener {

    fun register() = Guozhan.instance.server.pluginManager.registerEvents(this, Guozhan.instance)

    private val lastNotifyTime = mutableMapOf<Player, Long>()
    private val notifyCooldown = 3000L // 3秒冷却时间

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val user = player.user()
        val territory = event.block.location.territoryBlock()

        // 如果领土没有所有者，允许任何人破坏
        if (!territory.isOwned()) return

        // 如果玩家不属于任何国家，或者不是领土所属国家的成员，取消事件
        if (user.country == null || user.country?.id != territory.owner?.id) {
            event.isCancelled = true
            notifyPlayerIfNeeded(player, "§c你不能在其他国家的领土上破坏方块！")
            return
        }

        // 如果玩家是普通成员，且领土忠诚度低于50，不允许破坏
        if (user.rank == Rank.DEFAULT && territory.loyalty < 50) {
            event.isCancelled = true
            notifyPlayerIfNeeded(player, "§c这块领土的忠诚度太低，只有管理员或国家所有者才能在此操作！")
            return
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        val user = player.user()
        val territory = event.block.location.territoryBlock()

        // 如果领土没有所有者，允许任何人放置
        if (!territory.isOwned()) return

        // 如果玩家不属于任何国家，或者不是领土所属国家的成员，取消事件
        if (user.country == null || user.country?.id != territory.owner?.id) {
            event.isCancelled = true
            notifyPlayerIfNeeded(player, "§c你不能在其他国家的领土上放置方块！")
            return
        }

        // 如果玩家是普通成员，且领土忠诚度低于50，不允许放置
        if (user.rank == Rank.DEFAULT && territory.loyalty < 50) {
            event.isCancelled = true
            notifyPlayerIfNeeded(player, "§c这块领土的忠诚度太低，只有管理员或国家所有者才能在此操作！")
            return
        }
    }

    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        // 只检查玩家是否进入了新的区块
        if (event.from.chunk.x == event.to.chunk.x && event.from.chunk.z == event.to.chunk.z) return

        val player = event.player
        val territory = event.to.territoryBlock()

        // 如果领土有所有者，通知玩家
        if (territory.isOwned()) {
            val countryName = territory.owner?.name ?: "未知国家"
            val loyaltyStatus = when {
                territory.loyalty >= 80 -> "§a稳固"
                territory.loyalty >= 50 -> "§e中立"
                else -> "§c动荡"
            }
            notifyPlayerIfNeeded(player, "§6你进入了 §b$countryName §6的领土，忠诚度：$loyaltyStatus")

            // 如果领土有资源且可以收获，通知玩家
            if (territory.canHarvest() && territory.resourceType != null) {
                val user = player.user()
                if (user.country?.id == territory.owner?.id) {
                    // 使用Folia的RegionScheduler在正确的区域执行
                    val server = org.bukkit.Bukkit.getServer()
                    val regionScheduler = server.regionScheduler
                    regionScheduler.execute(Guozhan.instance, player.location) {
                        player.sendMessage("§a这块领土有可收获的${territory.resourceType}资源！使用 /u harvest 来收获。")
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        val player = event.player
        val user = player.user()
        val territory = block.location.territoryBlock()

        // 如果领土没有所有者，允许任何人交互
        if (!territory.isOwned()) return

        // 检查是否是容器类方块
        val isContainer = when (block.type) {
            Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL,
            Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER,
            Material.HOPPER, Material.DISPENSER, Material.DROPPER,
            Material.BREWING_STAND, Material.SHULKER_BOX -> true
            else -> false
        }

        // 如果是容器且玩家不是领土所属国家的成员，取消事件
        if (isContainer && (user.country == null || user.country?.id != territory.owner?.id)) {
            event.isCancelled = true
            notifyPlayerIfNeeded(player, "§c你不能使用其他国家领土上的容器！")
            return
        }

        // 如果玩家是普通成员，且领土忠诚度低于50，不允许使用容器
        if (isContainer && user.rank == Rank.DEFAULT && territory.loyalty < 50) {
            event.isCancelled = true
            notifyPlayerIfNeeded(player, "§c这块领土的忠诚度太低，只有管理员或国家所有者才能使用容器！")
            return
        }
    }

    private fun notifyPlayerIfNeeded(player: Player, message: String) {
        val currentTime = System.currentTimeMillis()
        val lastTime = lastNotifyTime[player] ?: 0L

        if (currentTime - lastTime > notifyCooldown) {
            // 使用Folia的RegionScheduler在正确的区域执行
            val server = org.bukkit.Bukkit.getServer()
            val regionScheduler = server.regionScheduler
            regionScheduler.execute(Guozhan.instance, player.location) {
                player.sendMessage(message)
            }
            lastNotifyTime[player] = currentTime
        }
    }
}