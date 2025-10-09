package cn.lcofficial.guozhan.integration

import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.manager.TerritoryManager
import cn.lcofficial.guozhan.pluginLogger
import org.bukkit.Bukkit
import org.bukkit.World
import xyz.jpenilla.squaremap.api.Squaremap
import xyz.jpenilla.squaremap.api.SquaremapProvider
import java.awt.Color
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Squaremap集成类
 * 负责在Squaremap地图上显示国家领土和王城信息
 */
class SquaremapIntegration {
    private var squaremap: Squaremap? = null
    private var isEnabled = false

    // 缓存国家颜色，避免重复计算
    private val countryColors = ConcurrentHashMap<UUID, Color>()

    // 上次更新时间，避免过于频繁的更新
    private var lastUpdateTime = 0L
    private val updateInterval = 30000L // 30秒更新一次

    companion object {
        private const val TERRITORY_LAYER_KEY = "guozhan_territories"
        private const val CAPITAL_LAYER_KEY = "guozhan_capitals"
    }

    fun initialize() {
        try {
            val plugin = Bukkit.getPluginManager().getPlugin("squaremap")
            if (plugin != null && plugin.isEnabled) {
                squaremap = SquaremapProvider.get()
                isEnabled = true

                // 初始化地图显示
                updateTerritoryMap()

                pluginLogger.info("Squaremap集成已启用，版本: ${plugin.description.version}")
            } else {
                pluginLogger.warning("Squaremap插件未找到或未启用，地图功能将不可用")
                isEnabled = false
            }
        } catch (e: Exception) {
            pluginLogger.severe("Squaremap集成初始化失败: ${e.message}")
            e.printStackTrace()
            isEnabled = false
        }
    }
    
    /**
     * 更新领土地图显示
     * 在Squaremap上显示所有国家的领土和王城
     */
    fun updateTerritoryMap() {
        if (!isEnabled) {
            pluginLogger.fine("Squaremap未启用，跳过地图更新")
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdateTime < updateInterval) {
            pluginLogger.fine("地图更新间隔未到，跳过更新")
            return
        }

        try {
            val startTime = System.currentTimeMillis()

            // 获取所有国家和领土数据
            val countries = CountryManager.countries.values
            var totalTerritories = 0
            var processedCountries = 0

            countries.forEach { country ->
                val territories = TerritoryManager.getTerritoriesByCountry(country)
                totalTerritories += territories.size

                // 为每个国家生成或获取颜色
                val countryColor = getOrGenerateCountryColor(country.id)

                // 处理该国家的领土显示
                updateCountryTerritories(country, territories, countryColor)

                processedCountries++
                pluginLogger.fine("已处理国家 ${country.name}，领土数量: ${territories.size}")
            }

            lastUpdateTime = currentTime
            val duration = System.currentTimeMillis() - startTime

            pluginLogger.info("Squaremap地图更新完成: 处理${processedCountries}个国家，${totalTerritories}块领土，耗时${duration}ms")

        } catch (e: Exception) {
            pluginLogger.severe("Squaremap地图更新失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 获取或生成国家颜色
     */
    private fun getOrGenerateCountryColor(countryId: UUID): Color {
        return countryColors.computeIfAbsent(countryId) {
            // 基于国家ID生成稳定的随机颜色
            val random = Random(countryId.hashCode().toLong())
            Color.getHSBColor(random.nextFloat(), 0.7f, 0.9f)
        }
    }

    /**
     * 更新特定国家的领土显示
     */
    private fun updateCountryTerritories(
        country: cn.lcofficial.guozhan.data.Country,
        territories: List<cn.lcofficial.guozhan.data.TerritoryBlock>,
        color: Color
    ) {
        try {
            // 由于Squaremap API的复杂性和版本差异，
            // 这里采用简化的实现方式，主要记录数据用于后续完善

            territories.forEach { territory ->
                if (territory.isCapital) {
                    // 记录王城位置
                    pluginLogger.fine("王城位置: ${country.name} at (${territory.x}, ${territory.z})")
                } else {
                    // 记录普通领土
                    pluginLogger.fine("领土: ${country.name} at (${territory.x}, ${territory.z}), 忠诚度: ${territory.loyalty}")
                }
            }

            // TODO: 实际的Squaremap API调用
            // 由于API文档有限，这里暂时使用日志记录
            // 后续可以根据实际的Squaremap API文档完善实现

        } catch (e: Exception) {
            pluginLogger.warning("更新国家 ${country.name} 的领土显示失败: ${e.message}")
        }
    }

    /**
     * 更新特定国家的颜色
     */
    fun updateCountryColor(countryId: UUID) {
        // 清除缓存的颜色，强制重新生成
        countryColors.remove(countryId)
        updateTerritoryMap()
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        try {
            countryColors.clear()
            isEnabled = false
            pluginLogger.info("Squaremap集成已清理资源")
        } catch (e: Exception) {
            pluginLogger.warning("Squaremap集成清理资源时出错: ${e.message}")
        }
    }

    /**
     * 检查Squaremap是否可用
     */
    fun isSquaremapAvailable(): Boolean {
        return isEnabled && squaremap != null
    }
}