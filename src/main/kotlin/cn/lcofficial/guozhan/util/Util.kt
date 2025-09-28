package cn.lcofficial.guozhan.util

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player


fun Player.hasEnoughItem(material: Material, amount: Int): Boolean {
    var total = 0
    for (item in inventory.contents) {
        if (item != null && item.type == material) {
            total += item.amount
            if (total >= amount) return true
        }
    }
    return false
}

fun Player.takeItem(material: Material, amount: Int): Boolean {
    var remaining = amount

    // 先检查是否足够
    var total = 0
    for (item in inventory.contents) {
        if (item != null && item.type == material) {
            total += item.amount
            if (total >= amount) break
        }
    }
    if (total < amount) return false

    // 扣除
    for (slot in inventory.contents.indices) {
        val item = inventory.getItem(slot) ?: continue
        if (item.type != material) continue

        val stackAmount = item.amount
        if (stackAmount <= remaining) {
            inventory.clear(slot)
            remaining -= stackAmount
        } else {
            item.amount = stackAmount - remaining
            inventory.setItem(slot, item)
            break
        }
        if (remaining <= 0) break
    }

    updateInventory()
    return true
}

fun isSafeHighestBlock(world: World, x: Int, z: Int): Location? {
    // 获取最高方块（忽略树叶等透明方块）
    val highestBlock: Block = world.getHighestBlockAt(x, z)


    // 方块类型不安全（可根据需求扩展）
    val type: Material? = highestBlock.type
    if (type == Material.LAVA || type == Material.WATER || type == Material.MAGMA_BLOCK || type == Material.CACTUS || type == Material.FIRE) {
        return null
    }


    // 检查玩家站立空间
    val above1: Block = highestBlock.getRelative(BlockFace.UP)
    val above2: Block = above1.getRelative(BlockFace.UP)
    if (above1.type !== Material.AIR || above2.type !== Material.AIR) {
        return null
    }

    return highestBlock.location.clone().add(0.0, 1.0, 0.0)
}
