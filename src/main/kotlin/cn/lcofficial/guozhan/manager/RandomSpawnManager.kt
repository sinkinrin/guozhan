package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.config.Config
import cn.lcofficial.guozhan.pluginLogger
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture
import kotlin.random.Random

/**
 * 随机出生管理器
 * 负责为新玩家寻找安全的随机出生点
 */
object RandomSpawnManager {
    
    /**
     * 为玩家寻找随机出生点并传送
     * @param player 需要传送的玩家
     * @return 是否成功找到并传送到安全位置
     */
    fun teleportPlayerToRandomSpawn(player: Player): CompletableFuture<Boolean> {
        if (!Config.RandomSpawn.enabled) {
            pluginLogger.info("随机出生系统已禁用，玩家 ${player.name} 将使用默认出生点")
            return CompletableFuture.completedFuture(false)
        }

        val world = getValidWorld(player.world) ?: run {
            pluginLogger.info("无法为玩家 ${player.name} 找到有效的世界")
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
                        if (success) {
                            player.sendMessage("§a欢迎来到国战服务器！你已被传送到安全的出生点。")
                            pluginLogger.info("玩家 ${player.name} 已传送到随机出生点: ${location.x}, ${location.y}, ${location.z}")
                        } else {
                            pluginLogger.severe("为玩家 ${player.name} 传送到随机出生点失败")
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
                        if (success) {
                            player.sendMessage("§e无法找到合适的随机出生点，已传送到默认位置。")
                            pluginLogger.warning("为玩家 ${player.name} 查找随机出生点失败，使用默认出生点")
                        } else {
                            pluginLogger.severe("为玩家 ${player.name} 传送到默认出生点失败")
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

        // 在主世界出生点区域执行，确保在正确的Folia区域线程中
        val spawnLocation = world.spawnLocation
        Bukkit.getRegionScheduler().execute(Guozhan.instance, spawnLocation) {
            repeat(Config.RandomSpawn.maxAttempts) {
                attempts++
                val location = generateRandomLocationSync(world)

                if (isLocationSafeSync(location) && isTerritoryFree(location)) {
                    val duration = System.currentTimeMillis() - startTime
                    pluginLogger.info("随机出生点查找成功，耗时: ${duration}ms, 尝试次数: $attempts")
                    future.complete(location)
                    return@execute
                }
            }

            val duration = System.currentTimeMillis() - startTime
            pluginLogger.warning("随机出生点查找失败，耗时: ${duration}ms, 尝试次数: $attempts")

            // 🔧 v1.3.17: 回退机制 - 使用世界出生点作为最后的安全选择
            val fallbackLocation = world.spawnLocation.clone().apply {
                y = world.getHighestBlockYAt(this).toDouble() + 1
            }
            pluginLogger.warning("[随机出生] 使用回退机制: 世界出生点 (${fallbackLocation.blockX}, ${fallbackLocation.blockY}, ${fallbackLocation.blockZ})")
            future.complete(fallbackLocation)
        }

        return future
    }
    
    /**
     * 生成随机位置（同步版本，在Folia区域线程中调用）
     * @param world 目标世界
     * @return 随机生成的位置
     */
    private fun generateRandomLocationSync(world: World): Location {
        val spawnLocation = world.spawnLocation
        val radius = Config.RandomSpawn.spawnRadius
        val minDistance = Config.RandomSpawn.minDistanceFromSpawn
        
        // 生成随机坐标，确保距离出生点足够远
        var x: Double
        var z: Double
        
        do {
            x = spawnLocation.x + Random.nextInt(-radius, radius + 1)
            z = spawnLocation.z + Random.nextInt(-radius, radius + 1)
            val distance = kotlin.math.sqrt((x - spawnLocation.x) * (x - spawnLocation.x) + 
                                          (z - spawnLocation.z) * (z - spawnLocation.z))
        } while (distance < minDistance)
        
        // 🔧 v1.3.19: 改进高度获取逻辑，避免在水中生成
        val y = try {
            // 优先尝试获取真实地面高度
            if (x.toInt() in -1000..1000 && z.toInt() in -1000..1000) {
                try {
                    val highestY = world.getHighestBlockYAt(x.toInt(), z.toInt())
                    val groundBlock = world.getBlockAt(x.toInt(), highestY, z.toInt())

                    // 检查最高点是否为固体地面
                    if (groundBlock.type.isSolid &&
                        groundBlock.type != org.bukkit.Material.WATER &&
                        groundBlock.type != org.bukkit.Material.LAVA) {
                        // 找到了安全的固体地面
                        pluginLogger.fine("[随机出生] 在 (${x.toInt()}, ${z.toInt()}) 找到固体地面，高度: $highestY")
                        highestY.toDouble() + 1
                    } else {
                        // 最高点不是安全地面，使用保守的高度
                        pluginLogger.fine("[随机出生] 在 (${x.toInt()}, ${z.toInt()}) 最高点不安全: ${groundBlock.type}")
                        null // 标记需要重新尝试
                    }
                } catch (e: IllegalStateException) {
                    // Folia线程检查失败
                    pluginLogger.fine("[随机出生] Folia线程检查失败，位置: (${x.toInt()}, ${z.toInt()})")
                    null // 标记需要重新尝试
                }
            } else {
                // 坐标超出安全范围
                pluginLogger.fine("[随机出生] 坐标超出安全范围: (${x.toInt()}, ${z.toInt()})")
                null // 标记需要重新尝试
            }
        } catch (e: Exception) {
            pluginLogger.fine("[随机出生] 获取地面高度异常: ${e.message}")
            null // 标记需要重新尝试
        }

        // 如果无法获取安全高度，返回null让调用者重新尝试其他坐标
        if (y == null) {
            pluginLogger.fine("[随机出生] 无法为坐标 (${x.toInt()}, ${z.toInt()}) 找到安全高度，需要重新尝试")
            // 使用一个明显不安全的高度，让isLocationSafeSync拒绝它
            return Location(world, x, -64.0, z) // 使用虚空高度，确保被拒绝
        }
        
        return Location(world, x, y, z)
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

            // 避免在过于偏远的位置生成
            if (x * x + z * z > Config.RandomSpawn.spawnRadius * Config.RandomSpawn.spawnRadius) {
                return false
            }

            // 避免在过低或过高的位置生成
            if (y < 60 || y > 120) {
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
