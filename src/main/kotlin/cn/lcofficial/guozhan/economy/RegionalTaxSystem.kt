package cn.lcofficial.guozhan.economy

import cn.lcofficial.guozhan.config.Config
import cn.lcofficial.guozhan.data.Country
import cn.lcofficial.guozhan.data.TerritoryBlock
import cn.lcofficial.guozhan.manager.TerritoryManager
import kotlin.math.abs
import kotlin.math.max

/**
 * 🔧 v1.3.48: 修复Critical问题4.2 - 税收计算结果数据类
 * 用于在线程间安全传递税收计算结果
 */
data class TaxResult(
    val goldTax: Int,
    val diamondTax: Int,
    val newAccumulatedGoldTax: Double,
    val newAccumulatedDiamondTax: Double
)

/**
 * 区域税收系统，根据区块到出生点的距离计算不同的税率
 * 🔧 v1.3.31: 修复税收区域调整被绕过的问题 - 从配置文件读取税收区域设置
 */
object RegionalTaxSystem {

    // 缓存的税收区域配置
    private var taxRegions: List<Config.Tax.TaxRegionConfig> = emptyList()

    /**
     * 初始化税收区域配置
     */
    fun initialize() {
        taxRegions = Config.Tax.loadTaxRegions()
    }

    /**
     * 根据区块坐标获取对应的税收区域
     * @param x 区块X坐标
     * @param z 区块Z坐标
     * @return 税收区域配置
     */
    internal fun getRegionByCoordinates(x: Int, z: Int): Config.Tax.TaxRegionConfig {
        // 计算到出生点(0,0)的距离（使用曼哈顿距离或欧几里得距离）
        val distance = max(abs(x), abs(z))

        // 从小到大检查区域范围
        return taxRegions.firstOrNull { distance <= it.range }
            ?: taxRegions.lastOrNull()
            ?: Config.Tax.TaxRegionConfig("失落蛮荒", 20000, 0.004, 0.002)
    }

    // 保持向后兼容的枚举类型（已弃用，但保留以防其他代码使用）
    @Deprecated("使用 getRegionByCoordinates() 和 Config.Tax.TaxRegionConfig 替代")
    enum class TaxRegion(val displayName: String, val range: Int, val goldRate: Double, val diamondRate: Double) {
        CORE_TERRITORY("核心疆域", 1300, 0.024, 0.024),
        INLAND_TERRITORY("内陆邦畿", 2500, 0.020, 0.016),
        FRONTIER_TERRITORY("开拓边疆", 5000, 0.016, 0.012),
        DISPUTED_TERRITORY("纷争之地", 9000, 0.012, 0.008),
        EXPEDITION_TERRITORY("远征前线", 14000, 0.008, 0.004),
        WILDERNESS_TERRITORY("失落蛮荒", 20000, 0.004, 0.002);

        companion object {
            @Deprecated("使用 RegionalTaxSystem.getRegionByCoordinates() 替代")
            fun getRegionByCoordinates(x: Int, z: Int): TaxRegion {
                val distance = max(abs(x), abs(z))
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
        // 🔧 v1.3.31: 使用配置文件中的税收区域设置
        val region = getRegionByCoordinates(territory.x, territory.z)
        return region.goldRate
    }

    /**
     * 计算区块每小时的钻石税收
     * @param territory 区块
     * @return 每小时钻石税收
     */
    fun calculateDiamondTaxPerHour(territory: TerritoryBlock): Double {
        // 🔧 v1.3.31: 使用配置文件中的税收区域设置
        val region = getRegionByCoordinates(territory.x, territory.z)
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
     * 计算国家的税收（只计算，不修改Country对象）
     * 🔧 v1.3.40: 修复区域税收被截断 - 使用累计机制避免小数截断
     * @param country 国家
     * @param hours 经过的小时数
     * @return Pair(计算的金锭, 计算的钻石)
     */
    fun calculateTax(country: Country, hours: Double): Pair<Int, Int> {
        // 计算精确的税收（保留小数）
        val exactGoldTax = calculateTotalGoldTaxPerHour(country) * hours
        val exactDiamondTax = calculateTotalDiamondTaxPerHour(country) * hours

        // 累加到现有的累计值
        val totalGoldTax = country.accumulatedGoldTax + exactGoldTax
        val totalDiamondTax = country.accumulatedDiamondTax + exactDiamondTax

        // 提取整数部分作为实际收取的税收
        val goldTax = totalGoldTax.toInt()
        val diamondTax = totalDiamondTax.toInt()

        return Pair(goldTax, diamondTax)
    }

    /**
     * 🔧 v1.3.48: 修复Critical问题4.2 - 计算税收但不直接修改Country对象
     * 计算国家的税收累计值（纯计算方法，线程安全）
     * @param country 国家
     * @param hours 经过的小时数
     * @return TaxResult 包含税收数据的结果对象
     */
    fun calculateTaxWithAccumulation(country: Country, hours: Double): TaxResult {
        // 计算精确的税收（保留小数）
        val exactGoldTax = calculateTotalGoldTaxPerHour(country) * hours
        val exactDiamondTax = calculateTotalDiamondTaxPerHour(country) * hours

        // 计算新的累计值（不修改原对象）
        val newAccumulatedGoldTax = country.accumulatedGoldTax + exactGoldTax
        val newAccumulatedDiamondTax = country.accumulatedDiamondTax + exactDiamondTax

        // 提取整数部分作为实际收取的税收
        val goldTax = newAccumulatedGoldTax.toInt()
        val diamondTax = newAccumulatedDiamondTax.toInt()

        // 计算剩余的小数部分
        val remainingGoldTax = newAccumulatedGoldTax - goldTax
        val remainingDiamondTax = newAccumulatedDiamondTax - diamondTax

        // 🔧 v1.3.48: 修复Critical问题4.2 - 返回计算结果，不直接修改Country对象
        return TaxResult(
            goldTax = goldTax,
            diamondTax = diamondTax,
            newAccumulatedGoldTax = remainingGoldTax,
            newAccumulatedDiamondTax = remainingDiamondTax
        )
    }

    /**
     * 🔧 v1.3.48: 修复Critical问题4.2 - 在GlobalRegionScheduler中应用税收结果
     * 此方法必须在GlobalRegionScheduler中调用，确保线程安全
     * @param country 国家
     * @param taxResult 税收计算结果
     */
    fun applyTax(country: Country, taxResult: TaxResult) {
        // 更新累计值
        country.accumulatedGoldTax = taxResult.newAccumulatedGoldTax
        country.accumulatedDiamondTax = taxResult.newAccumulatedDiamondTax

        // 更新国家资源
        country.gold += taxResult.goldTax
        country.diamond += taxResult.diamondTax

        // 保存到数据库
        country.save()

        cn.lcofficial.guozhan.Guozhan.instance.logger.info("🔧 [税收应用] 国家 ${country.name} 收取税收：金币 +${taxResult.goldTax}，钻石 +${taxResult.diamondTax}")
    }

    /**
     * 🔧 v1.3.48: 修复Critical问题4.2 - 保持向后兼容的旧方法
     * @deprecated 使用 calculateTaxWithAccumulation() 和 applyTax() 替代，确保线程安全
     */
    @Deprecated("使用 calculateTaxWithAccumulation() 和 applyTax() 替代，确保线程安全")
    fun collectTax(country: Country, hours: Double): Pair<Int, Int> {
        val taxResult = calculateTaxWithAccumulation(country, hours)

        // 🔧 v1.3.48: 必须在GlobalRegionScheduler中调用applyTax
        cn.lcofficial.guozhan.util.run {
            applyTax(country, taxResult)
        }

        return Pair(taxResult.goldTax, taxResult.diamondTax)
    }

    /**
     * 🔧 v1.3.43: 计算税收并更新累计值（线程安全版本）
     * 此方法只进行计算和累计值更新，不修改国家资源，可以在任何线程调用
     * @param country 国家
     * @param hours 经过的小时数
     * @return TaxCalculationResult 税收计算结果
     */
    fun calculateTaxWithAccumulationOld(country: Country, hours: Double): TaxCalculationResult {
        // 计算精确的税收（保留小数）
        val exactGoldTax = calculateTotalGoldTaxPerHour(country) * hours
        val exactDiamondTax = calculateTotalDiamondTaxPerHour(country) * hours

        // 累加到现有的累计值
        val newAccumulatedGoldTax = country.accumulatedGoldTax + exactGoldTax
        val newAccumulatedDiamondTax = country.accumulatedDiamondTax + exactDiamondTax

        // 提取整数部分作为实际收取的税收
        val goldTax = newAccumulatedGoldTax.toInt()
        val diamondTax = newAccumulatedDiamondTax.toInt()

        // 计算剩余的小数部分
        val remainingGoldTax = newAccumulatedGoldTax - goldTax
        val remainingDiamondTax = newAccumulatedDiamondTax - diamondTax

        return TaxCalculationResult(
            goldTax = goldTax,
            diamondTax = diamondTax,
            newAccumulatedGoldTax = remainingGoldTax,
            newAccumulatedDiamondTax = remainingDiamondTax,
            exactGoldTax = exactGoldTax,
            exactDiamondTax = exactDiamondTax
        )
    }

    /**
     * 🔧 v1.3.43: 应用税收计算结果到国家（必须在GlobalRegionScheduler中调用）
     * @param country 国家
     * @param result 税收计算结果
     */
    fun applyTax(country: Country, result: TaxCalculationResult) {
        // 更新累计税收值
        country.accumulatedGoldTax = result.newAccumulatedGoldTax
        country.accumulatedDiamondTax = result.newAccumulatedDiamondTax

        // 更新国家资源
        country.gold += result.goldTax
        country.diamond += result.diamondTax

        // 保存国家数据
        country.save()
    }

    /**
     * 🔧 v1.3.43: 税收计算结果数据类
     */
    data class TaxCalculationResult(
        val goldTax: Int,           // 实际收取的金币税收
        val diamondTax: Int,        // 实际收取的钻石税收
        val newAccumulatedGoldTax: Double,    // 新的累计金币税收（小数部分）
        val newAccumulatedDiamondTax: Double, // 新的累计钻石税收（小数部分）
        val exactGoldTax: Double,   // 精确的金币税收（用于日志）
        val exactDiamondTax: Double // 精确的钻石税收（用于日志）
    )
    
    /**
     * 获取区块所在的税收区域
     * @param territory 区块
     * @return 税收区域配置
     */
    internal fun getTerritoryRegion(territory: TerritoryBlock): Config.Tax.TaxRegionConfig {
        // 🔧 v1.3.31: 使用配置文件中的税收区域设置
        return getRegionByCoordinates(territory.x, territory.z)
    }

    /**
     * 获取区域名称
     * @param territory 区块
     * @return 区域名称
     */
    fun getRegionName(territory: TerritoryBlock): String {
        return getTerritoryRegion(territory).name
    }

    /**
     * 获取区块所在的税收区域（向后兼容方法）
     * @param territory 区块
     * @return 税收区域枚举
     */
    @Deprecated("使用 getTerritoryRegion() 替代")
    fun getTerritoryRegionEnum(territory: TerritoryBlock): TaxRegion {
        return TaxRegion.getRegionByCoordinates(territory.x, territory.z)
    }
}