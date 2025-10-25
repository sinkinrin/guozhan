package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.config.Config
import cn.lcofficial.guozhan.pluginLogger
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections
import kotlin.random.Random

/**
 * 随机出生管理器
 * 负责为新玩家寻找安全的随机出生点
 */
object RandomSpawnManager {

    // 防抖：防止同一玩家在短时间内被重复随机传送
    private val teleporting: MutableSet<UUID> = Collections.newSetFromMap(ConcurrentHashMap<UUID, Boolean>())
    private val lastTeleportAt: MutableMap<UUID, Long> = ConcurrentHashMap()
    private const val TELEPORT_COOLDOWN_MS: Long = 15_000

    /**
     * 为玩家寻找随机出生点并传送
     * @param player 需要传送的玩家
     * @return 是否成功找到并传送到安全位置
     * 🔧 v1.3.54: 修复问题3 (Medium) - 冷却期内返回false并通知玩家，而不是返回true
     */
    fun teleportPlayerToRandomSpawn(player: Player): CompletableFuture<Boolean> {
        // 防抖：同一玩家15秒内仅允许一次随机出生传送
        val now = System.currentTimeMillis()
        val uuid = player.uniqueId
        lastTeleportAt[uuid]?.let { last ->
            if (now - last < TELEPORT_COOLDOWN_MS) {
                val remainingSeconds = (TELEPORT_COOLDOWN_MS - (now - last)) / 1000
                player.sendMessage("§c随机传送冷却中，请等待 ${remainingSeconds} 秒")
                pluginLogger.warning("[随机出生] 忽略重复请求：玩家 ${player.name} 在冷却期内 (${now - last}ms) ")
                return CompletableFuture.completedFuture(false)
            }
        }
        if (!teleporting.add(uuid)) {
            player.sendMessage("§c随机传送进行中，请稍候")
            pluginLogger.warning("[随机出生] 玩家 ${player.name} 已有随机出生进行中，忽略重复调用")
            return CompletableFuture.completedFuture(false)
        }

        if (!Config.RandomSpawn.enabled) {
            pluginLogger.info("随机出生系统已禁用，玩家 ${player.name} 将使用默认出生点")
            teleporting.remove(uuid)
            return CompletableFuture.completedFuture(false)
        }

        val world = getValidWorld(player.world) ?: run {
            pluginLogger.info("无法为玩家 ${player.name} 找到有效的世界")
            teleporting.remove(uuid)
            return CompletableFuture.completedFuture(false)
        }

        return findRandomSpawnLocationAsync(world).thenApply { location ->
            if (location != null) {
                // 使用Folia的RegionScheduler在正确的区域执行传送
                val server = Bukkit.getServer()
                val regionScheduler = server.regionScheduler

                regionScheduler.execute(Guozhan.instance, location) {
                    // 使用teleportAsync避免Folia线程检查错误
                    player.teleportAsync(location).thenAccept { success ->
                        try {
                            if (success) {
                                // 给予更长时间的摔落保护
                                giveFallDamageProtection(player)
                                // 记录落点地面方块，便于诊断
                                val ground = location.clone().add(0.0, -1.0, 0.0).block.type
                                player.sendMessage("§a欢迎来到国战服务器！你已被传送到安全的出生点。")
                                pluginLogger.info("玩家 ${player.name} 已传送到随机出生点: ${location.x}, ${location.y}, ${location.z} | ground=${ground}")
                            } else {
                                pluginLogger.severe("为玩家 ${player.name} 传送到随机出生点失败")
                            }
                        } finally {
                            lastTeleportAt[uuid] = System.currentTimeMillis()
                            teleporting.remove(uuid)
                        }
                    }
                }
                true
            } else {
                // 传送失败，使用默认出生点
                val server = Bukkit.getServer()
                val regionScheduler = server.regionScheduler
                val spawnLocation = world.spawnLocation

                regionScheduler.execute(Guozhan.instance, spawnLocation) {
                    // 使用teleportAsync避免Folia线程检查错误
                    player.teleportAsync(spawnLocation).thenAccept { success ->
                        try {
                            if (success) {
                                player.sendMessage("§e无法找到合适的随机出生点，已传送到默认位置。")
                                pluginLogger.warning("为玩家 ${player.name} 查找随机出生点失败，使用默认出生点")
                            } else {
                                pluginLogger.severe("为玩家 ${player.name} 传送到默认出生点失败")
                            }
                        } finally {
                            lastTeleportAt[uuid] = System.currentTimeMillis()
                            teleporting.remove(uuid)
                        }
                    }
                }
                false
            }
        }
    }
    
    /**
     * 在Folia区域线程中查找随机出生位置
     * @param world 目标世界
     * @return 安全的位置，如果找不到则返回null
     */
    private fun findRandomSpawnLocationAsync(world: World): CompletableFuture<Location?> {
        val future = CompletableFuture<Location?>()
        val startTime = System.currentTimeMillis()
        var attempts = 0

        // 简化的随机出生点查找逻辑，避免Folia线程问题
        fun tryNextLocation() {
            if (attempts >= Config.RandomSpawn.maxAttempts) {
                val duration = System.currentTimeMillis() - startTime
                pluginLogger.warning("随机出生点查找失败，耗时: ${duration}ms, 尝试次数: $attempts")

                // 使用世界出生点作为回退
                val worldSpawn = world.spawnLocation
                val isFlat = isLikelyFlatWorld(world)

                val fallbackLocation = worldSpawn.clone().apply {
                    // 根据世界类型调整高度
                    y = if (isFlat) {
                        // 平坦世界：使用世界出生点的实际高度，确保在地面上
                        maxOf(worldSpawn.y, 5.0)
                    } else {
                        // 普通世界：使用安全高度
                        maxOf(worldSpawn.y, 70.0)
                    }
                }

                pluginLogger.warning("[随机出生] 使用回退机制: 世界出生点 (${worldSpawn.x}, ${worldSpawn.y}, ${worldSpawn.z}) -> 调整后 (${fallbackLocation.x}, ${fallbackLocation.y}, ${fallbackLocation.z}), 世界类型: ${if (isFlat) "平坦" else "普通"}")
                future.complete(fallbackLocation)
                return
            }

            attempts++

            // 生成随机坐标
            val radius = Config.RandomSpawn.spawnRadius
            val minDistance = Config.RandomSpawn.minDistanceFromSpawn
            val spawnLocation = world.spawnLocation

            val angle = Math.random() * 2 * Math.PI
            val distance = minDistance + Math.random() * (radius - minDistance)
            val x = spawnLocation.x + distance * Math.cos(angle)
            val z = spawnLocation.z + distance * Math.sin(angle)

            // 在候选坐标所在区域线程中计算真实地表高度并进行安全检测
            val candidate = Location(world, x, 0.0, z)
            Bukkit.getRegionScheduler().execute(Guozhan.instance, candidate) {
                try {
                    val xi = x.toInt()
                    val zi = z.toInt()

                    // 🔧 修复：寻找真正的固体地面，而不是最高方块
                    val safeGroundY = findSafeGroundLevel(world, xi, zi)
                    if (safeGroundY == null) {
                        pluginLogger.fine("[随机出生] 位置 ($xi, $zi) 未找到安全地面")
                        // 切回全局调度器进行下一次尝试
                        Bukkit.getGlobalRegionScheduler().execute(Guozhan.instance) {
                            tryNextLocation()
                        }
                        return@execute
                    }

                    // 在安全地面上方1格的位置传送玩家
                    val finalY = safeGroundY + 1
                    val finalLocation = Location(world, x, finalY.toDouble(), z)

                    // 1) 领土必须为空置；2) 必须通过完整安全检查（非水/岩浆、脚下为固体、头顶有空间、周围无危险方块等）
                    if (isTerritoryFree(finalLocation) && isLocationSafeSync(finalLocation)) {
                        val duration = System.currentTimeMillis() - startTime
                        val groundBlock = world.getBlockAt(xi, safeGroundY, zi)
                        pluginLogger.info("随机出生点查找成功，耗时: ${duration}ms, 尝试次数: $attempts, 地面: ${groundBlock.type} at Y=$safeGroundY")
                        future.complete(finalLocation)
                    } else {
                        pluginLogger.fine("[随机出生] 位置 ($xi, $finalY, $zi) 未通过安全检查或领土检查")
                        // 切回全局调度器进行下一次尝试，避免在区域线程中递归
                        Bukkit.getGlobalRegionScheduler().execute(Guozhan.instance) {
                            tryNextLocation()
                        }
                    }
                } catch (e: Exception) {
                    pluginLogger.fine("[随机出生] 在区域线程计算高度失败: ${e.message}")
                    Bukkit.getGlobalRegionScheduler().execute(Guozhan.instance) { tryNextLocation() }
                }
            }
        }

        // 在全局调度器中开始查找
        Bukkit.getGlobalRegionScheduler().execute(Guozhan.instance) {
            tryNextLocation()
        }

        return future
    }

    /**
     * 给予玩家短暂的摔落伤害保护
     * @param player 需要保护的玩家
     */
    private fun giveFallDamageProtection(player: Player) {
        // 给予15秒的缓降与短暂抗性，避免出生后因延迟加载/微高差而致死
        player.addPotionEffect(
            org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.SLOW_FALLING,
                300, // 15秒 (300 ticks)
                0,
                false,
                false
            )
        )
        player.addPotionEffect(
            PotionEffect(
                PotionEffectType.RESISTANCE,
                100, // 5秒
                0,
                false,
                false
            )
        )

        // 额外的安全措施：重置摔落距离
        player.fallDistance = 0.0f

        pluginLogger.fine("为玩家 ${player.name} 提供了摔落伤害保护")
    }

    /**
     * 寻找安全的地面高度
     * @param world 世界
     * @param x X坐标
     * @param z Z坐标
     * @return 安全地面的Y坐标，如果找不到则返回null
     */
    private fun findSafeGroundLevel(world: World, x: Int, z: Int): Int? {
        try {
            // 从最高方块开始向下搜索
            val startY = world.getHighestBlockYAt(x, z)
            val minY = Config.RandomSpawn.minYLevel
            val maxY = Config.RandomSpawn.maxYLevel

            // 定义安全的地面方块类型
            val safeGroundBlocks = setOf(
                org.bukkit.Material.GRASS_BLOCK,
                org.bukkit.Material.DIRT,
                org.bukkit.Material.STONE,
                org.bukkit.Material.COBBLESTONE,
                org.bukkit.Material.SAND,
                org.bukkit.Material.SANDSTONE,
                org.bukkit.Material.GRAVEL,
                org.bukkit.Material.DEEPSLATE,
                org.bukkit.Material.ANDESITE,
                org.bukkit.Material.DIORITE,
                org.bukkit.Material.GRANITE
            )

            // 从最高点向下搜索安全地面
            for (y in startY downTo minY) {
                if (y > maxY) continue

                val block = world.getBlockAt(x, y, z)
                val blockType = block.type

                // 检查是否是安全的地面方块
                if (blockType in safeGroundBlocks) {
                    // 确保上方有足够空间（至少2格空气）
                    val aboveBlock1 = world.getBlockAt(x, y + 1, z)
                    val aboveBlock2 = world.getBlockAt(x, y + 2, z)

                    if (aboveBlock1.type == org.bukkit.Material.AIR &&
                        aboveBlock2.type == org.bukkit.Material.AIR) {
                        pluginLogger.fine("[随机出生] 找到安全地面: $blockType at ($x, $y, $z)")
                        return y
                    }
                }
            }

            pluginLogger.fine("[随机出生] 在 ($x, $z) 未找到安全地面，搜索范围: $minY-$maxY")
            return null
        } catch (e: Exception) {
            pluginLogger.warning("[随机出生] 搜索安全地面时发生错误: ${e.message}")
            return null
        }
    }

    /**
     * 检测是否为平坦世界
     * @param world 要检测的世界
     * @return 是否为平坦世界
     */
    private fun isLikelyFlatWorld(world: World): Boolean {
        return try {
            val spawnLocation = world.spawnLocation
            val spawnY = spawnLocation.y

            // 简化检测：平坦世界的出生点通常在Y=4-10之间
            val isFlat = spawnY <= 10.0

            pluginLogger.info("[平坦世界检测] 世界: ${world.name}, 出生点Y坐标: ${spawnY}, 检测结果: ${if (isFlat) "平坦世界" else "普通世界"}")

            isFlat
        } catch (e: Exception) {
            pluginLogger.warning("[平坦世界检测] 检测失败: ${e.message}")
            // 如果检测失败，假设不是平坦世界
            false
        }
    }


    /**
     * 检查位置是否安全（Folia兼容版本，包含方块类型检查）
     * @param location 要检查的位置
     * @return 是否安全
     */
    private fun isLocationSafeSync(location: Location): Boolean {
        try {
            // 1. 检查Y坐标范围
            if (location.y < Config.RandomSpawn.minYLevel ||
                location.y > Config.RandomSpawn.maxYLevel) {
                return false
            }

            // 2. 检查世界边界（避免生成在世界边缘）
            val worldBorder = location.world.worldBorder
            val borderSize = worldBorder.size / 2.0
            val centerX = worldBorder.center.x
            val centerZ = worldBorder.center.z

            if (location.x < centerX - borderSize + 100 ||
                location.x > centerX + borderSize - 100 ||
                location.z < centerZ - borderSize + 100 ||
                location.z > centerZ + borderSize - 100) {
                return false
            }

            // 3. 避免在极端坐标生成（可能是海洋或虚空）
            val x = location.blockX
            val z = location.blockZ
            val y = location.blockY

            // 🔧 v1.3.29: 关键修复 - 使用相对于出生点的坐标进行距离判定
            // 避免在过于偏远的位置生成
            val spawnLocation = location.world.spawnLocation
            val spawnX = spawnLocation.blockX
            val spawnZ = spawnLocation.blockZ
            val relativeX = x - spawnX
            val relativeZ = z - spawnZ
            val distanceSquared = relativeX * relativeX + relativeZ * relativeZ
            val radiusSquared = Config.RandomSpawn.spawnRadius * Config.RandomSpawn.spawnRadius

            if (distanceSquared > radiusSquared) {
                pluginLogger.fine(
                    "[随机出生] 位置 ($x, $z) 距离出生点 ($spawnX, $spawnZ) 过远: " +
                    "距离=${Math.sqrt(distanceSquared.toDouble()).toInt()}, 最大半径=${Config.RandomSpawn.spawnRadius}"
                )
                return false
            }

            // 根据世界类型调整高度检查范围
            val (minY, maxY) = if (isLikelyFlatWorld(location.world)) {
                // 平坦世界：地面在Y=4-7，允许Y=5-15的范围
                Pair(5, 15)
            } else {
                // 普通世界：使用原有范围
                Pair(60, 120)
            }

            if (y < minY || y > maxY) {
                return false
            }

            // 4. 🔧 v1.3.17: 修复随机出生点方块类型检查 - 确保不在水中、岩浆中或空中出生
            val world = location.world
            val spawnBlock = world.getBlockAt(x, y, z)
            val groundBlock = world.getBlockAt(x, y - 1, z)
            val headBlock = world.getBlockAt(x, y + 1, z)

            // 🔧 v1.3.19: 增强地面方块类型检查，确保不在水中或危险环境出生
            // 检查出生点下方必须是固体地面，且不能是危险方块
            if (!groundBlock.type.isSolid) {
                pluginLogger.fine("[随机出生] 位置 ($x, $y, $z) 被拒绝: 下方不是固体地面 (${groundBlock.type})")
                return false
            }

            // 特别检查地面不能是水或岩浆（即使它们在某些情况下被认为是"固体"）
            if (groundBlock.type == org.bukkit.Material.WATER ||
                groundBlock.type == org.bukkit.Material.LAVA) {
                pluginLogger.fine("[随机出生] 位置 ($x, $y, $z) 被拒绝: 地面是水或岩浆 (${groundBlock.type})")
                return false
            }

            // 检查出生点和头部位置必须是空气（确保玩家有站立空间）
            if (spawnBlock.type != org.bukkit.Material.AIR) {
                pluginLogger.fine("[随机出生] 位置 ($x, $y, $z) 被拒绝: 出生点不是空气 (${spawnBlock.type})")
                return false
            }
            if (headBlock.type != org.bukkit.Material.AIR) {
                pluginLogger.fine("[随机出生] 位置 ($x, $y, $z) 被拒绝: 头部空间不是空气 (${headBlock.type})")
                return false
            }

            // 检查危险方块类型 - 拒绝水、岩浆等危险环境
            val dangerousBlocks = setOf(
                org.bukkit.Material.WATER,
                org.bukkit.Material.LAVA,
                org.bukkit.Material.FIRE,
                org.bukkit.Material.SOUL_FIRE,
                org.bukkit.Material.MAGMA_BLOCK,
                org.bukkit.Material.CACTUS,
                org.bukkit.Material.SWEET_BERRY_BUSH,
                org.bukkit.Material.WITHER_ROSE,
                // 添加更多危险方块
                org.bukkit.Material.POWDER_SNOW,
                org.bukkit.Material.COBWEB,
                org.bukkit.Material.POINTED_DRIPSTONE
            )

            // 检查出生点周围3x3区域是否有危险方块
            for (dx in -1..1) {
                for (dz in -1..1) {
                    val checkBlock = world.getBlockAt(x + dx, y, z + dz)
                    val checkGroundBlock = world.getBlockAt(x + dx, y - 1, z + dz)

                    if (checkBlock.type in dangerousBlocks || checkGroundBlock.type in dangerousBlocks) {
                        pluginLogger.fine("[随机出生] 位置 ($x, $y, $z) 被拒绝: 周围有危险方块 (${checkBlock.type} 或 ${checkGroundBlock.type})")
                        return false
                    }
                }
            }

            // 5. 基于坐标的启发式检查（避免明显不安全的位置）
            // 避免在坐标为0的位置（通常是出生点附近）
            if (x in -5..5 && z in -5..5) {
                return false
            }

            // 6. 简单的分布检查（避免过于聚集）
            // 使用坐标哈希来分散生成点
            val hash = (x * 31 + z) % 100
            if (hash < 20) { // 只有20%的位置被认为是"好"的
                return false
            }

            pluginLogger.fine("[随机出生] 位置 ($x, $y, $z) 通过安全检查: 地面=${groundBlock.type}, 出生点=${spawnBlock.type}, 头部=${headBlock.type}")
            return true
        } catch (e: Exception) {
            // 任何异常，认为不安全
            pluginLogger.warning("[随机出生] 位置安全检查失败: ${e.message}")
            return false
        }
    }
    
    /**
     * 检查位置是否未被国家占领
     * @param location 要检查的位置
     * @return 是否为自由领土
     */
    private fun isTerritoryFree(location: Location): Boolean {
        try {
            // 使用正确的TerritoryManager API检查领土状态
            val chunkX = location.blockX shr 4
            val chunkZ = location.blockZ shr 4
            val worldName = location.world.name

            val territory = cn.lcofficial.guozhan.manager.TerritoryManager.getTerritoryBlock(
                chunkX, chunkZ, worldName
            )

            // 如果没有领土记录或者领土没有所有者，则认为是自由的
            val isFree = territory == null || territory.owner == null

            if (!isFree) {
                pluginLogger.info("位置 ($chunkX, $chunkZ) 已被国家 ${territory?.owner?.name} 占领")
            }

            return isFree
        } catch (e: Exception) {
            pluginLogger.warning("检查领土状态时发生错误: ${e.message}")
            // 发生错误时，检查是否距离出生点太近
            val spawnLocation = location.world.spawnLocation
            val distance = location.distance(spawnLocation)

            // 如果距离出生点太近，可能被保护，认为不安全
            return distance >= 500
        }
    }
    
    /**
     * 获取有效的世界
     * @param preferredWorld 首选世界
     * @return 有效的世界，如果首选世界无效则返回配置中的第一个世界
     */
    private fun getValidWorld(preferredWorld: World): World? {
        // 检查首选世界是否在允许列表中
        if (Config.RandomSpawn.allowedWorlds.contains(preferredWorld.name)) {
            return preferredWorld
        }
        
        // 尝试获取配置中的第一个世界
        val firstAllowedWorld = Config.RandomSpawn.allowedWorlds.firstOrNull()
        if (firstAllowedWorld != null) {
            return Bukkit.getWorld(firstAllowedWorld)
        }
        
        // 如果都失败了，返回默认世界
        return Bukkit.getWorlds().firstOrNull()
    }
    
    /**
     * 检查玩家是否是第一次加入
     * @param player 玩家
     * @return 是否是第一次加入
     */
    fun isFirstTimePlayer(player: Player): Boolean {
        // 检查玩家是否有游戏时间记录
        return !player.hasPlayedBefore()
    }
    
    /**
     * 管理员命令：手动为玩家寻找随机出生点
     * @param player 目标玩家
     * @param sender 命令发送者
     */
    fun adminRandomSpawn(player: Player, sender: org.bukkit.command.CommandSender) {
        teleportPlayerToRandomSpawn(player).thenAccept { success ->
            if (success) {
                sender.sendMessage("§a成功为玩家 ${player.name} 寻找到随机出生点")
            } else {
                sender.sendMessage("§c为玩家 ${player.name} 寻找随机出生点失败")
            }
        }
    }
}
