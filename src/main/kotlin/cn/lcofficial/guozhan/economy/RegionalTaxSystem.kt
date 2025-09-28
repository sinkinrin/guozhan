package cn.lcofficial.guozhan.economy

import cn.lcofficial.guozhan.data.Country
import cn.lcofficial.guozhan.data.TerritoryBlock
import cn.lcofficial.guozhan.manager.TerritoryManager
import kotlin.math.abs
import kotlin.math.max

/**
 * 区域税收系统，根据区块到出生点的距离计算不同的税率
 */
object RegionalTaxSystem {
    
    // 区域定义和对应的税率
    enum class TaxRegion(val displayName: String, val range: Int, val goldRate: Double, val diamondRate: Double) {
        CORE_TERRITORY("核心疆域", 1300, 0.024, 0.024),
        INLAND_TERRITORY("内陆邦畿", 2500, 0.020, 0.016),
        FRONTIER_TERRITORY("开拓边疆", 5000, 0.016, 0.012),
        DISPUTED_TERRITORY("纷争之地", 9000, 0.012, 0.008),
        EXPEDITION_TERRITORY("远征前线", 14000, 0.008, 0.004),
        WILDERNESS_TERRITORY("失落蛮荒", 20000, 0.004, 0.002);
        
        companion object {
            /**
             * 根据区块坐标获取对应的税收区域
             * @param x 区块X坐标
             * @param z 区块Z坐标
             * @return 税收区域
             */
            fun getRegionByCoordinates(x: Int, z: Int): TaxRegion {
                // 计算到出生点(0,0)的距离（使用曼哈顿距离或欧几里得距离）
                val distance = max(abs(x), abs(z))
                
                // 从小到大检查区域范围
                return values().firstOrNull { distance <= it.range } ?: WILDERNESS_TERRITORY
            }
        }
    }
    
    /**
     * 计算区块每小时的金锭税收
     * @param territory 区块
     * @return 每小时金锭税收
     */
    fun calculateGoldTaxPerHour(territory: TerritoryBlock): Double {
        val region = TaxRegion.getRegionByCoordinates(territory.x, territory.z)
        return region.goldRate
    }
    
    /**
     * 计算区块每小时的钻石税收
     * @param territory 区块
     * @return 每小时钻石税收
     */
    fun calculateDiamondTaxPerHour(territory: TerritoryBlock): Double {
        val region = TaxRegion.getRegionByCoordinates(territory.x, territory.z)
        return region.diamondRate
    }
    
    /**
     * 计算国家每小时的总金锭税收
     * @param country 国家
     * @return 每小时总金锭税收
     */
    fun calculateTotalGoldTaxPerHour(country: Country): Double {
        val territories = TerritoryManager.getTerritoriesByCountry(country)
        return territories.sumOf { calculateGoldTaxPerHour(it) }
    }
    
    /**
     * 计算国家每小时的总钻石税收
     * @param country 国家
     * @return 每小时总钻石税收
     */
    fun calculateTotalDiamondTaxPerHour(country: Country): Double {
        val territories = TerritoryManager.getTerritoriesByCountry(country)
        return territories.sumOf { calculateDiamondTaxPerHour(it) }
    }
    
    /**
     * 收集国家的税收
     * @param country 国家
     * @param hours 经过的小时数
     * @return Pair(收集的金锭, 收集的钻石)
     */
    fun collectTax(country: Country, hours: Double): Pair<Int, Int> {
        val goldTax = (calculateTotalGoldTaxPerHour(country) * hours).toInt()
        val diamondTax = (calculateTotalDiamondTaxPerHour(country) * hours).toInt()
        
        // 更新国家资源
        country.gold += goldTax
        country.diamond += diamondTax
        country.save()
        
        return Pair(goldTax, diamondTax)
    }
    
    /**
     * 获取区块所在的税收区域
     * @param territory 区块
     * @return 税收区域
     */
    fun getTerritoryRegion(territory: TerritoryBlock): TaxRegion {
        return TaxRegion.getRegionByCoordinates(territory.x, territory.z)
    }
    
    /**
     * 获取区域名称
     * @param territory 区块
     * @return 区域名称
     */
    fun getRegionName(territory: TerritoryBlock): String {
        return getTerritoryRegion(territory).displayName
    }
}