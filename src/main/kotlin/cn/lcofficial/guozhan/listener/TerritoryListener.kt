package cn.lcofficial.guozhan.listener

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.config.Config
import cn.lcofficial.guozhan.config.Message
import cn.lcofficial.guozhan.config.Message.sendError
import cn.lcofficial.guozhan.config.Message.sendSuccess
import cn.lcofficial.guozhan.config.Message.sendInfo
import cn.lcofficial.guozhan.data.ClaimMode
import cn.lcofficial.guozhan.data.Rank
import cn.lcofficial.guozhan.data.ResourceType
import cn.lcofficial.guozhan.data.TerritoryBlock
import cn.lcofficial.guozhan.data.User
import cn.lcofficial.guozhan.manager.TerritoryManager
import cn.lcofficial.guozhan.manager.TerritoryManager.territoryBlock
import cn.lcofficial.guozhan.manager.UserManager.user
import cn.lcofficial.guozhan.manager.WarManager
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
    // Disallow high-risk grief blocks when siege bypass is active
    private val siegeRestrictedBlocks = setOf(
        Material.TNT,
        Material.LAVA,
        Material.WATER,
        Material.FIRE,
        Material.SOUL_FIRE
    )

    private fun canBypassCapitalProtection(user: User, territory: TerritoryBlock?): Boolean {
        if (!Config.Country.CoreProtection.allowSiegeBuilding) return false
        if (territory == null || !territory.isCapital) return false
        val attackerCountry = user.country ?: return false
        val defenderCountry = territory.owner ?: return false
        if (attackerCountry.id == defenderCountry.id) return false
        return WarManager.isAtWar(attackerCountry, defenderCountry)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val user = player.user()
        val territory = event.block.location.territoryBlock()

        // 🔧 v1.3.34: 添加调试日志来追踪权限检查
        val blockLocation = event.block.location
        val chunkCoords = "(${blockLocation.chunk.x}, ${blockLocation.chunk.z})"

        if (territory == null) {
            player.sendMessage("§7[调试] 无领土区块 $chunkCoords - 允许破坏")
            return
        }

        if (!territory.isOwned()) {
            player.sendMessage("§7[调试] 无主领土 $chunkCoords - 允许破坏")
            return
        }

        val ownerName = territory.owner?.name ?: "未知"
        val playerCountryName = user.country?.name ?: "无国家"
        player.sendMessage("§7[调试] 有主领土 $chunkCoords - 所有者: $ownerName, 玩家国家: $playerCountryName")

        // OP玩家有特殊权限，可以在任何地方破坏方块
        if (player.isOp) {
            player.sendMessage("§7[调试] OP权限 - 允许破坏")
            return
        }

        if (canBypassCapitalProtection(user, territory)) {
            player.sendMessage("§7[调试] 战时首都领土 - 允许破坏（配置开启）")
            return
        }

        // 如果玩家不属于任何国家，或者不是领土所属国家的成员，取消事件
        if (user.country == null || user.country?.id != territory.owner?.id) {
            event.isCancelled = true
            player.sendMessage("§7[调试] 权限检查失败 - 禁止破坏")
            notifyPlayerIfNeeded(player, "§c你不能在其他国家的领土上破坏方块！")
            return
        }

        // 修复权限逻辑：只有在领土忠诚度极低（<20）且玩家是普通成员时才限制
        // 这样可以确保国家成员在正常情况下都能建造
        if (user.rank == Rank.DEFAULT && territory.loyalty < 20) {
            event.isCancelled = true
            player.sendMessage("§7[调试] 忠诚度过低 (${territory.loyalty}%) - 禁止破坏")
            notifyPlayerIfNeeded(player, "§c这块领土的忠诚度极低（${territory.loyalty}%），只有管理员或国家所有者才能在此操作！")
            return
        }

        player.sendMessage("§7[调试] 权限检查通过 - 允许破坏")
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        val user = player.user()
        val territory = event.block.location.territoryBlock()

        // 🔧 代码审查修复: 如果领土区块不存在或没有所有者，允许任何人放置
        if (territory == null || !territory.isOwned()) return

        // OP玩家有特殊权限，可以在任何地方放置方块
        if (player.isOp) return

        if (canBypassCapitalProtection(user, territory)) {
            val placedType = event.blockPlaced.type
            if (placedType in siegeRestrictedBlocks) {
                event.isCancelled = true
                notifyPlayerIfNeeded(player, "§c战时攻城不允许放置危险方块！")
                return
            }

            player.sendMessage("§7[调试] 战时首都领土 - 允许建造（配置开启）")
            return
        }

        // 如果玩家不属于任何国家，或者不是领土所属国家的成员，取消事件
        if (user.country == null || user.country?.id != territory.owner?.id) {
            event.isCancelled = true
            notifyPlayerIfNeeded(player, "§c你不能在其他国家的领土上放置方块！")
            return
        }

        // 修复权限逻辑：只有在领土忠诚度极低（<20）且玩家是普通成员时才限制
        // 这样可以确保国家成员在正常情况下都能建造
        if (user.rank == Rank.DEFAULT && territory.loyalty < 20) {
            event.isCancelled = true
            notifyPlayerIfNeeded(player, "§c这块领土的忠诚度极低（${territory.loyalty}%），只有管理员或国家所有者才能在此操作！")
            return
        }
    }

    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        // 只检查玩家是否进入了新的区块
        if (event.from.chunk.x == event.to.chunk.x && event.from.chunk.z == event.to.chunk.z) return

        val player = event.player
        val territory = event.to.territoryBlock()

        // 🔧 代码审查修复: 检查领土区块是否存在且有所有者
        if (territory != null && territory.isOwned()) {
            val countryName = territory.owner?.name ?: "未知国家"
            val loyaltyStatus = when {
                territory.loyalty >= 80 -> "§a稳固"
                territory.loyalty >= 50 -> "§e中立"
                else -> "§c动荡"
            }
            notifyPlayerIfNeeded(player, "§6你进入了 §b$countryName §6的领土，忠诚度：$loyaltyStatus")

            // 如果领土有资源且可以收获，通知玩家
            if (territory.canHarvest()) {
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

        // 🔧 代码审查修复: 如果领土区块不存在或没有所有者，允许任何人交互
        if (territory == null || !territory.isOwned()) return

        // 检查是否是容器类方块
        val isContainer = when (block.type) {
            Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL,
            Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER,
            Material.HOPPER, Material.DISPENSER, Material.DROPPER,
            Material.BREWING_STAND, Material.SHULKER_BOX -> true
            else -> false
        }

        // OP玩家有特殊权限，可以使用任何容器
        if (player.isOp) return

        if (isContainer && canBypassCapitalProtection(user, territory)) {
            player.sendMessage("§7[调试] 战时首都领土 - 允许容器交互（配置开启）")
            return
        }

        // 如果是容器且玩家不是领土所属国家的成员，取消事件
        if (isContainer && (user.country == null || user.country?.id != territory.owner?.id)) {
            event.isCancelled = true
            notifyPlayerIfNeeded(player, "§c你不能使用其他国家领土上的容器！")
            return
        }

        // 修复权限逻辑：只有在领土忠诚度极低（<20）且玩家是普通成员时才限制容器使用
        if (isContainer && user.rank == Rank.DEFAULT && territory.loyalty < 20) {
            event.isCancelled = true
            notifyPlayerIfNeeded(player, "§c这块领土的忠诚度极低（${territory.loyalty}%），只有管理员或国家所有者才能使用容器！")
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

        // 🔧 代码审查修复: 使用显式创建版本，仅在占领操作时创建数据库记录
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

        // 检查资源消耗（3个铁锭），仅校验不扣除，完成时再扣
        if (!player.hasEnoughItem(Material.IRON_INGOT, 3)) {
            player.sendError("占领领土需要3个铁锭！")
            return
        }

        // 启动计时占领流程
        if (cn.lcofficial.guozhan.manager.ClaimManager.isTerritoryBeingClaimed(worldName, chunkX, chunkZ)) {
            player.sendError("该领土正在被占领中，请稍后再试")
            return
        }
        cn.lcofficial.guozhan.manager.ClaimManager.startClaim(player, territory, user.country!!)
        player.sendInfo("已开始占领，保持在该区块内，期间若被攻击或离开将会中断")

        player.sendInfo("继续手持木斧右键其他区块来占领更多领土")
        player.sendInfo("使用 /u claimmode 可以切换回自动模式")
    }

    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    fun onClaimPlayerDamaged(event: org.bukkit.event.entity.EntityDamageEvent) {
        val player = event.entity as? org.bukkit.entity.Player ?: return
        if (cn.lcofficial.guozhan.manager.ClaimManager.isClaiming(player)) {
            cn.lcofficial.guozhan.manager.ClaimManager.cancelClaimByDamage(player)
        }
    }

    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    fun onClaimPlayerMove(event: org.bukkit.event.player.PlayerMoveEvent) {
        val player = event.player
        if (!cn.lcofficial.guozhan.manager.ClaimManager.isClaiming(player)) return
        val from = event.from.chunk
        val to = event.to?.chunk ?: return
        if (from.x != to.x || from.z != to.z || from.world.name != to.world.name) {
            cn.lcofficial.guozhan.manager.ClaimManager.cancelClaimByMove(player)
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