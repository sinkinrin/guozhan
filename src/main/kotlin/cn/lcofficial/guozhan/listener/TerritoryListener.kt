package cn.lcofficial.guozhan.listener

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.config.Message
import cn.lcofficial.guozhan.config.Message.sendError
import cn.lcofficial.guozhan.config.Message.sendSuccess
import cn.lcofficial.guozhan.config.Message.sendInfo
import cn.lcofficial.guozhan.data.ClaimMode
import cn.lcofficial.guozhan.data.Rank
import cn.lcofficial.guozhan.data.ResourceType
import cn.lcofficial.guozhan.manager.TerritoryManager
import cn.lcofficial.guozhan.manager.TerritoryManager.territoryBlock
import cn.lcofficial.guozhan.manager.UserManager.user
import cn.lcofficial.guozhan.util.hasEnoughItem
import cn.lcofficial.guozhan.util.takeItem
// 移除不存在的Scheduler导入，使用Folia调度器
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
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

    /**
     * 手动占领监听器 - 处理木斧右键占领
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onManualClaim(event: PlayerInteractEvent) {
        // 只处理右键点击方块
        if (event.action != Action.RIGHT_CLICK_BLOCK) return

        val player = event.player
        val user = player.user()
        val clickedBlock = event.clickedBlock ?: return

        // 检查是否为手动模式
        if (user.claimMode != ClaimMode.MANUAL) return

        // 检查是否手持木斧
        if (player.inventory.itemInMainHand.type != Material.WOODEN_AXE) return

        // 检查玩家是否属于国家
        if (user.country == null) {
            player.sendError("你不属于任何国家，无法占领领土！")
            return
        }

        // 检查权限
        if (user.rank == Rank.DEFAULT) {
            player.sendError("只有国家管理员或所有者才能占领领土！")
            return
        }

        // 取消事件，防止其他交互
        event.isCancelled = true

        // 获取点击的区块
        val chunkX = clickedBlock.chunk.x
        val chunkZ = clickedBlock.chunk.z
        val worldName = clickedBlock.world.name

        // 获取或创建领土区块
        val territory = TerritoryManager.getTerritoryBlock(chunkX, chunkZ, worldName)
            ?: TerritoryManager.createTerritoryBlock(chunkX, chunkZ, worldName)

        // 检查是否已被占领
        if (territory.isOwned()) {
            if (territory.owner?.id == user.country?.id) {
                player.sendError("这块领土已经属于你的国家了！")
            } else {
                player.sendError("这块领土已经被其他国家占领了！")
                player.sendInfo("你可以尝试攻击占领，但需要更长时间")
            }
            return
        }

        // 检查是否与现有领土接壤
        if (!TerritoryManager.canClaim(territory, user.country!!)) {
            player.sendError("占领失败！领土必须与你的国家现有领土接壤")
            return
        }

        // 检查资源消耗（3个铁锭）
        if (!player.hasEnoughItem(Material.IRON_INGOT, 3)) {
            player.sendError("占领领土需要3个铁锭！")
            return
        }

        // 扣除资源
        player.takeItem(Material.IRON_INGOT, 3)

        // 执行占领
        territory.owner = user.country
        territory.loyalty = 100
        territory.save()

        // 随机生成资源
        TerritoryManager.generateRandomResource(territory)

        // 发送成功消息
        player.sendSuccess("成功占领了这块领土！")
        if (territory.resourceType != ResourceType.NONE) {
            player.sendInfo("这块领土上发现了${territory.resourceType}资源！")
        }

        player.sendInfo("继续手持木斧右键其他区块来占领更多领土")
        player.sendInfo("使用 /u claimmode 可以切换回自动模式")
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