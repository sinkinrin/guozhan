package cn.lcofficial.guozhan.extensions

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 检查玩家是否有足够的物品
 * @param material 物品类型
 * @param amount 需要的数量
 * @return 是否有足够的物品
 */
fun Player.hasEnoughItem(material: Material, amount: Int): Boolean {
    var count = 0
    for (item in inventory.contents) {
        if (item != null && item.type == material) {
            count += item.amount
            if (count >= amount) {
                return true
            }
        }
    }
    return false
}

/**
 * 从玩家背包中扣除指定数量的物品
 * @param material 物品类型
 * @param amount 需要扣除的数量
 * @return 是否成功扣除
 */
fun Player.takeItem(material: Material, amount: Int): Boolean {
    if (!hasEnoughItem(material, amount)) {
        return false
    }
    
    var remaining = amount
    val contents = inventory.contents
    
    for (i in contents.indices) {
        val item = contents[i] ?: continue
        if (item.type == material) {
            if (item.amount <= remaining) {
                remaining -= item.amount
                inventory.setItem(i, null)
            } else {
                item.amount -= remaining
                inventory.setItem(i, item)
                remaining = 0
            }
            
            if (remaining <= 0) {
                break
            }
        }
    }
    
    return true
}