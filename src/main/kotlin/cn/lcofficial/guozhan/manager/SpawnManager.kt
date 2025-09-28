package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.data.Territory
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Biome
import kotlin.random.Random

object SpawnManager {
    private const val SPAWN_RADIUS = 7500
    private const val MIN_Y = 64
    private const val MAX_Y = 100
    
    fun findRandomSpawnLocation(world: World): Location? {
        var attempts = 0
        val maxAttempts = 100
        
        while (attempts < maxAttempts) {
            val x = Random.nextInt(-SPAWN_RADIUS, SPAWN_RADIUS)
            val z = Random.nextInt(-SPAWN_RADIUS, SPAWN_RADIUS)
            
            // 检查是否在已有领土范围内
            if (isInTerritory(world, x, z)) {
                attempts++
                continue
            }
            
            // 找到合适的Y坐标
            val y = findSafeY(world, x, z)
            if (y == -1) {
                attempts++
                continue
            }
            
            val location = Location(world, x.toDouble(), y.toDouble(), z.toDouble())
            
            // 检查生物群系
            val biome = world.getBiome(x, y, z)
            if (isValidBiome(biome)) {
                return location
            }
            
            attempts++
        }
        
        return null
    }
    
    private fun findSafeY(world: World, x: Int, z: Int): Int {
        val chunk = world.getChunkAt(x shr 4, z shr 4)
        val blockX = x and 15
        val blockZ = z and 15
        
        for (y in MAX_Y downTo MIN_Y) {
            val block = chunk.getBlock(blockX, y, blockZ)
            val below = chunk.getBlock(blockX, y - 1, blockZ)
            
            if (block.type == Material.AIR && 
                below.type.isSolid && 
                below.type != Material.LAVA && 
                below.type != Material.WATER) {
                return y
            }
        }
        
        return -1
    }
    
    private fun isInTerritory(world: World, x: Int, z: Int): Boolean {
        // 检查该坐标是否属于任何国家的领土
        return false // 实际实现需要查询TerritoryManager
    }
    
    private fun isValidBiome(biome: Biome): Boolean {
        // 排除海洋类生物群系
        return when (biome) {
            Biome.OCEAN, Biome.DEEP_OCEAN, Biome.COLD_OCEAN, 
            Biome.DEEP_COLD_OCEAN, Biome.LUKEWARM_OCEAN, Biome.DEEP_LUKEWARM_OCEAN,
            Biome.WARM_OCEAN, Biome.DEEP_WARM_OCEAN, Biome.FROZEN_OCEAN,
            Biome.DEEP_FROZEN_OCEAN, Biome.RIVER -> false
            else -> true
        }
    }
    
    fun teleportToRandomSpawn(player: org.bukkit.entity.Player) {
        val location = findRandomSpawnLocation(player.world)
        if (location != null) {
            player.teleport(location)
            player.sendMessage("§a已为你随机传送到安全区域！")
        } else {
            player.sendMessage("§c无法找到合适的出生点，请稍后再试")
        }
    }
}