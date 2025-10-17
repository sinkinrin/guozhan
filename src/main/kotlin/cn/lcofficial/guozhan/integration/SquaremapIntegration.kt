package cn.lcofficial.guozhan.integration

import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.manager.TerritoryManager
import cn.lcofficial.guozhan.manager.ShieldManager
import cn.lcofficial.guozhan.pluginLogger
import org.bukkit.Bukkit
import org.bukkit.World
import xyz.jpenilla.squaremap.api.Squaremap
import xyz.jpenilla.squaremap.api.SquaremapProvider
import xyz.jpenilla.squaremap.api.SimpleLayerProvider
import xyz.jpenilla.squaremap.api.marker.Marker
import xyz.jpenilla.squaremap.api.marker.MarkerOptions
import xyz.jpenilla.squaremap.api.Point
import xyz.jpenilla.squaremap.api.Key
import xyz.jpenilla.squaremap.api.WorldIdentifier
import xyz.jpenilla.squaremap.api.BukkitAdapter
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

    // 图层提供者
    private var territoryLayerProvider: SimpleLayerProvider? = null
    private var capitalLayerProvider: SimpleLayerProvider? = null
    private var shieldLayerProvider: SimpleLayerProvider? = null

    companion object {
        private const val TERRITORY_LAYER_KEY = "guozhan_territories"
        private const val CAPITAL_LAYER_KEY = "guozhan_capitals"
        private const val SHIELD_LAYER_KEY = "guozhan_shields"
    }

    fun initialize() {
        try {
            val plugin = Bukkit.getPluginManager().getPlugin("squaremap")
            if (plugin != null && plugin.isEnabled) {
                squaremap = SquaremapProvider.get()
                isEnabled = true

                // 初始化图层
                initializeLayers()

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
     * 初始化Squaremap图层
     */
    private fun initializeLayers() {
        val squaremapInstance = squaremap ?: return

        try {
            // 获取主世界
            val world = Bukkit.getWorlds().firstOrNull() ?: return
            val worldIdentifier = BukkitAdapter.worldIdentifier(world)
            val worldMapOptional = squaremapInstance.getWorldIfEnabled(worldIdentifier)
            if (!worldMapOptional.isPresent) return
            val worldMap = worldMapOptional.get()

            // 创建领土图层
            territoryLayerProvider = SimpleLayerProvider.builder("国家领土")
                .showControls(true)
                .defaultHidden(false)
                .layerPriority(5)
                .zIndex(100)
                .build()
            val territoryKey = Key.of(TERRITORY_LAYER_KEY)
            val layerReg = worldMap.layerRegistry()
            layerReg.register(territoryKey, territoryLayerProvider!!)

            // 创建首都图层
            capitalLayerProvider = SimpleLayerProvider.builder("国家首都")
                .showControls(true)
                .defaultHidden(false)
                .layerPriority(10)
                .zIndex(200)
                .build()
            val capitalKey = Key.of(CAPITAL_LAYER_KEY)
            val capitalReg = worldMap.layerRegistry()
            capitalReg.register(capitalKey, capitalLayerProvider!!)

            // 创建护盾图层
            shieldLayerProvider = SimpleLayerProvider.builder("护盾状态")
                .showControls(true)
                .defaultHidden(false)
                .layerPriority(15)
                .zIndex(300)
                .build()
            val shieldKey = Key.of(SHIELD_LAYER_KEY)
            val shieldReg = worldMap.layerRegistry()
            shieldReg.register(shieldKey, shieldLayerProvider!!)

            pluginLogger.info("Squaremap图层初始化完成")
        } catch (e: Exception) {
            pluginLogger.warning("Squaremap图层初始化失败: ${e.message}")
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

            // 在开始更新前清除所有图层的旧标记
            val territoryProvider = territoryLayerProvider
            val capitalProvider = capitalLayerProvider
            val shieldProvider = shieldLayerProvider

            territoryProvider?.clearMarkers()
            capitalProvider?.clearMarkers()
            shieldProvider?.clearMarkers()

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
     * v1.3.18实现：使用Squaremap API绘制领土多边形和首都标记
     */
    private fun updateCountryTerritories(
        country: cn.lcofficial.guozhan.data.Country,
        territories: List<cn.lcofficial.guozhan.data.TerritoryBlock>,
        color: Color
    ) {
        try {
            val territoryProvider = territoryLayerProvider ?: return
            val capitalProvider = capitalLayerProvider ?: return
            val shieldProvider = shieldLayerProvider ?: return

            if (territories.isEmpty()) return

            // 绘制领土多边形
            drawTerritoryPolygons(territoryProvider, country, territories, color)

            // 绘制首都标记
            drawCapitalMarkers(capitalProvider, country, territories, color)

            // 绘制护盾状态
            drawShieldOverlays(shieldProvider, country, territories)

            pluginLogger.fine("已更新国家 ${country.name} 的地图显示: ${territories.size}块领土")

        } catch (e: Exception) {
            pluginLogger.warning("更新国家 ${country.name} 的领土显示失败: ${e.message}")
        }
    }

    /**
     * 绘制领土多边形
     */
    private fun drawTerritoryPolygons(
        provider: SimpleLayerProvider,
        country: cn.lcofficial.guozhan.data.Country,
        territories: List<cn.lcofficial.guozhan.data.TerritoryBlock>,
        color: Color
    ) {
        try {
            // 为每个领土区块创建一个小方块多边形
            territories.forEach { territory ->
                val chunkX = territory.x * 16
                val chunkZ = territory.z * 16

                // 创建区块边界的四个点（16x16区块）
                val points = listOf(
                    Point.of(chunkX.toDouble(), chunkZ.toDouble()),
                    Point.of((chunkX + 16).toDouble(), chunkZ.toDouble()),
                    Point.of((chunkX + 16).toDouble(), (chunkZ + 16).toDouble()),
                    Point.of(chunkX.toDouble(), (chunkZ + 16).toDouble())
                )

                // 创建多边形标记
                val polygon = Marker.polygon(points)
                    .markerOptions(
                        MarkerOptions.builder()
                            .strokeColor(color)
                            .strokeWeight(2)
                            .strokeOpacity(0.8)
                            .fillColor(color)
                            .fillOpacity(0.3)
                            .clickTooltip("${country.name} - 忠诚度: ${territory.loyalty}%")
                            .build()
                    )

                provider.addMarker(Key.of("guozhan_territory_${territory.x}_${territory.z}"), polygon)
            }
        } catch (e: Exception) {
            pluginLogger.warning("绘制领土多边形失败: ${e.message}")
        }
    }

    /**
     * 绘制首都标记
     */
    private fun drawCapitalMarkers(
        provider: SimpleLayerProvider,
        country: cn.lcofficial.guozhan.data.Country,
        territories: List<cn.lcofficial.guozhan.data.TerritoryBlock>,
        color: Color
    ) {
        try {
            territories.filter { it.isCapital }.forEach { capital ->
                val chunkX = capital.x * 16 + 8 // 区块中心
                val chunkZ = capital.z * 16 + 8

                // 🔧 修复：使用 Squaremap 内置图标而非未注册的自定义图标
                // Squaremap 1.2.0 内置图标：使用 "greenflag" 作为首都标记
                val marker = Marker.icon(Point.of(chunkX.toDouble(), chunkZ.toDouble()), Key.of("greenflag"), 32)
                    .markerOptions(
                        MarkerOptions.builder()
                            .clickTooltip("${country.name} - 首都")
                            .hoverTooltip("国家: ${country.name}<br>首都位置: (${capital.x}, ${capital.z})<br>核心血量: ${country.coreHealth}")
                            .build()
                    )

                provider.addMarker(Key.of("guozhan_capital_${country.id}"), marker)
            }
        } catch (e: Exception) {
            pluginLogger.warning("绘制首都标记失败: ${e.message}")
        }
    }

    /**
     * 绘制护盾状态叠加层
     */
    private fun drawShieldOverlays(
        provider: SimpleLayerProvider,
        country: cn.lcofficial.guozhan.data.Country,
        territories: List<cn.lcofficial.guozhan.data.TerritoryBlock>
    ) {
        try {
            if (!ShieldManager.isShieldActive(country)) return

            // 为有护盾的国家绘制特殊边框
            territories.forEach { territory ->
                val chunkX = territory.x * 16
                val chunkZ = territory.z * 16

                val points = listOf(
                    Point.of(chunkX.toDouble(), chunkZ.toDouble()),
                    Point.of((chunkX + 16).toDouble(), chunkZ.toDouble()),
                    Point.of((chunkX + 16).toDouble(), (chunkZ + 16).toDouble()),
                    Point.of(chunkX.toDouble(), (chunkZ + 16).toDouble())
                )

                val shieldPolygon = Marker.polygon(points)
                    .markerOptions(
                        MarkerOptions.builder()
                            .strokeColor(Color.CYAN)
                            .strokeWeight(3)
                            .strokeOpacity(1.0)
                            .fillOpacity(0.0) // 只显示边框
                            .clickTooltip("${country.name} - 护盾保护中")
                            .build()
                    )

                provider.addMarker(Key.of("guozhan_shield_${territory.x}_${territory.z}"), shieldPolygon)
            }
        } catch (e: Exception) {
            pluginLogger.warning("绘制护盾叠加层失败: ${e.message}")
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
            // 清理图层
            territoryLayerProvider?.clearMarkers()
            capitalLayerProvider?.clearMarkers()
            shieldLayerProvider?.clearMarkers()

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