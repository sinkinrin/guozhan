package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.data.Territory
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Biome
import kotlin.random.Random

/**
 * SpawnManager - 出生点管理器
 *
 * 注意：随机出生功能已迁移到RandomSpawnManager
 * 此类保留用于其他出生点相关的功能
 */
object SpawnManager {

    /**
     * 检查玩家是否需要传送到出生点
     * @param player 玩家
     * @return 是否需要传送
     */
    fun shouldTeleportToSpawn(player: org.bukkit.entity.Player): Boolean {
        // 检查玩家是否是第一次加入且没有国家
        val user = cn.lcofficial.guozhan.manager.UserManager.getUser(player.uniqueId)
        return user == null || user.country == null
    }

    /**
     * 传送玩家到安全出生点
     * 委托给RandomSpawnManager处理
     * @param player 玩家
     */
    fun teleportToSafeSpawn(player: org.bukkit.entity.Player) {
        // 委托给RandomSpawnManager处理随机出生
        cn.lcofficial.guozhan.manager.RandomSpawnManager.teleportPlayerToRandomSpawn(player)
    }

    /**
     * 获取世界的默认出生点
     * @param world 世界
     * @return 出生点位置
     */
    fun getWorldSpawn(world: org.bukkit.World): org.bukkit.Location {
        return world.spawnLocation
    }
}