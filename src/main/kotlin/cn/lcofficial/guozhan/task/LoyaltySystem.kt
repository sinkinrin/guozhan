package cn.lcofficial.guozhan.task

import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.manager.TerritoryManager
import cn.lcofficial.guozhan.data.TerritoryBlock
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitRunnable

class LoyaltySystem : BukkitRunnable() {
    override fun run() {
        val countries = CountryManager.countries.values
        
        countries.forEach { country ->
            val territories = TerritoryManager.getTerritoriesByCountry(country)
            
            territories.forEach { territory ->
                // 计算不接壤面数
                val unconnectedSides = calculateUnconnectedSides(territory)
                
                // 根据不接壤面数减少忠诚度
                when (unconnectedSides) {
                    1 -> reduceLoyalty(territory, 4)
                    2 -> reduceLoyalty(territory, 4)
                    3 -> reduceLoyalty(territory, 4)
                    4 -> reduceLoyalty(territory, 4)
                }
                
                // 检查忠诚度是否为0
                if (territory.loyalty <= 0) {
                    if (territory.isCapital) {
                        // 灭国 - 简化处理，移除国家所有权
                        territory.owner = null
                        Bukkit.broadcastMessage("§c[国战] ${country.name}已被灭国！")
                    } else {
                        // 变为无主区块
                        territory.owner = null
                    }
                }
            }
        }
    }
    
    private fun calculateUnconnectedSides(territory: TerritoryBlock): Int {
        var unconnected = 0
        
        // 检查四个方向是否接壤
        val directions = listOf(
            Pair(1, 0),   // 东
            Pair(-1, 0),  // 西
            Pair(0, 1),   // 南
            Pair(0, -1)   // 北
        )
        
        directions.forEach { (dx, dz) ->
            val neighbor = TerritoryManager.getTerritoryBlock(territory.x + dx, territory.z + dz, territory.world)
            if (neighbor == null || neighbor.owner?.id != territory.owner?.id) {
                unconnected++
            }
        }
        
        return unconnected
    }
    
    private fun reduceLoyalty(territory: TerritoryBlock, percentage: Int) {
        territory.loyalty = (territory.loyalty - percentage).coerceAtLeast(0)
        territory.save()
    }
    
    fun restoreLoyalty(country: cn.lcofficial.guozhan.data.Country) {
        val territories = TerritoryManager.getTerritoriesByCountry(country)
        var totalCost = 0
        
        territories.forEach { territory ->
            val missingLoyalty = 100 - territory.loyalty
            if (missingLoyalty > 0) {
                totalCost += (missingLoyalty * 0.5).toInt()
            }
        }
        
        if (country.gold >= totalCost) {
            country.gold -= totalCost
            country.save()
            
            territories.forEach { territory ->
                territory.loyalty = 100
                territory.save()
            }
            
            Bukkit.broadcastMessage("§a[国战] ${country.name}已恢复所有领土忠诚度！")
        }
    }
}