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
                // 在主线程中执行传送
                Bukkit.getScheduler().runTask(Guozhan.instance, Runnable {
                    player.teleport(location)
                    player.sendMessage("§a欢迎来到国战服务器！你已被传送到安全的出生点。")
                    pluginLogger.info("玩家 ${player.name} 已传送到随机出生点: ${location.x}, ${location.y}, ${location.z}")
                })
                true
            } else {
                // 传送失败，使用默认出生点
                Bukkit.getScheduler().runTask(Guozhan.instance, Runnable {
                    val spawnLocation = world.spawnLocation
                    player.teleport(spawnLocation)
                    player.sendMessage("§e无法找到合适的随机出生点，已传送到默认位置。")
                    pluginLogger.warn("为玩家 ${player.name} 查找随机出生点失败，使用默认出生点")
                })
                false
            }
        }
    }
    
    /**
     * 异步查找随机出生位置
     * @param world 目标世界
     * @return 安全的位置，如果找不到则返回null
     */
    private fun findRandomSpawnLocationAsync(world: World): CompletableFuture<Location?> {
        return CompletableFuture.supplyAsync {
            val startTime = System.currentTimeMillis()
            var attempts = 0
            
            repeat(Config.RandomSpawn.maxAttempts) {
                attempts++
                val location = generateRandomLocation(world)
                
                if (isLocationSafe(location) && isTerritoryFree(location)) {
                    val duration = System.currentTimeMillis() - startTime
                    pluginLogger.info("随机出生点查找成功，耗时: ${duration}ms, 尝试次数: $attempts")
                    return@supplyAsync location
                }
            }

            val duration = System.currentTimeMillis() - startTime
            pluginLogger.info("随机出生点查找失败，耗时: ${duration}ms, 尝试次数: $attempts")
            null
        }
    }
    
    /**
     * 生成随机位置
     * @param world 目标世界
     * @return 随机生成的位置
     */
    private fun generateRandomLocation(world: World): Location {
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
        
        // 获取地面高度
        val y = world.getHighestBlockYAt(x.toInt(), z.toInt()).toDouble() + 1
        
        return Location(world, x, y, z)
    }
    
    /**
     * 检查位置是否安全
     * @param location 要检查的位置
     * @return 是否安全
     */
    private fun isLocationSafe(location: Location): Boolean {
        // 1. 检查Y坐标范围
        if (location.y < Config.RandomSpawn.minYLevel || 
            location.y > Config.RandomSpawn.maxYLevel) {
            return false
        }
        
        // 2. 检查脚下是否有固体方块
        val groundBlock = location.clone().add(0.0, -1.0, 0.0).block
        if (!groundBlock.type.isSolid) {
            return false
        }
        
        // 3. 检查头顶是否有足够空间
        val headBlock = location.clone().add(0.0, 1.0, 0.0).block
        val headBlock2 = location.clone().add(0.0, 2.0, 0.0).block
        if (!headBlock.type.isAir || !headBlock2.type.isAir) {
            return false
        }
        
        // 4. 检查周围是否有危险方块
        val safetyRadius = Config.RandomSpawn.safetyCheckRadius
        for (x in -safetyRadius..safetyRadius) {
            for (z in -safetyRadius..safetyRadius) {
                val checkLocation = location.clone().add(x.toDouble(), 0.0, z.toDouble())
                val block = checkLocation.block
                
                if (Config.RandomSpawn.unsafeBlocks.contains(block.type.name)) {
                    return false
                }
                
                // 检查是否是危险的方块类型
                if (block.type == Material.LAVA || 
                    block.type == Material.FIRE ||
                    block.type == Material.MAGMA_BLOCK ||
                    block.type == Material.CACTUS) {
                    return false
                }
            }
        }
        
        return true
    }
    
    /**
     * 检查位置是否未被国家占领
     * @param location 要检查的位置
     * @return 是否为自由领土
     */
    private fun isTerritoryFree(location: Location): Boolean {
        try {
            // 尝试检查领土管理器
            // 注意：由于现有代码有编译错误，这里使用安全的方式检查
            // 如果 TerritoryManager 不可用，默认认为是自由领土
            
            // TODO: 当 TerritoryManager 修复后，取消注释以下代码
            // val territory = TerritoryManager.getTerritory(location)
            // return territory == null || territory.country == null
            
            // 临时实现：检查区块是否在世界出生点保护范围内
            val spawnLocation = location.world.spawnLocation
            val distance = location.distance(spawnLocation)
            
            // 如果距离出生点太近，可能被保护，认为不安全
            if (distance < 500) {
                return false
            }
            
            return true
        } catch (e: Exception) {
            pluginLogger.info("检查领土状态时发生错误: ${e.message}")
            return true // 发生错误时默认认为是自由领土
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
