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

    // 🔧 v1.3.27: 改为支持多世界的图层提供者映射
    // 图层提供者 - 每个世界一组图层
    private val territoryLayerProviders = ConcurrentHashMap<String, SimpleLayerProvider>()
    private val capitalLayerProviders = ConcurrentHashMap<String, SimpleLayerProvider>()
    private val shieldLayerProviders = ConcurrentHashMap<String, SimpleLayerProvider>()

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

                pluginLogger.info("Squaremap集成已启用，版本: ${plugin.pluginMeta.version}")
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
     * 🔧 v1.3.27: 支持多世界，为每个启用的世界创建图层
     */
    private fun initializeLayers() {
        val squaremapInstance = squaremap ?: return

        try {
            var totalLayersCreated = 0

            // 🔧 v1.3.27: 遍历所有世界，为每个启用的世界创建图层
            Bukkit.getWorlds().forEach { world ->
                try {
                    val worldIdentifier = BukkitAdapter.worldIdentifier(world)
                    val worldMapOptional = squaremapInstance.getWorldIfEnabled(worldIdentifier)

                    if (!worldMapOptional.isPresent) {
                        pluginLogger.fine("世界 ${world.name} 未在 Squaremap 中启用，跳过")
                        return@forEach
                    }

                    val worldMap = worldMapOptional.get()
                    val worldName = world.name

                    // 创建领土图层
                    val territoryProvider = SimpleLayerProvider.builder("国家领土")
                        .showControls(true)
                        .defaultHidden(false)
                        .layerPriority(5)
                        .zIndex(100)
                        .build()
                    val territoryKey = Key.of("${TERRITORY_LAYER_KEY}_${worldName}")
                    worldMap.layerRegistry().register(territoryKey, territoryProvider)
                    territoryLayerProviders[worldName] = territoryProvider

                    // 创建首都图层
                    val capitalProvider = SimpleLayerProvider.builder("国家首都")
                        .showControls(true)
                        .defaultHidden(false)
                        .layerPriority(10)
                        .zIndex(200)
                        .build()
                    val capitalKey = Key.of("${CAPITAL_LAYER_KEY}_${worldName}")
                    worldMap.layerRegistry().register(capitalKey, capitalProvider)
                    capitalLayerProviders[worldName] = capitalProvider

                    // 创建护盾图层
                    val shieldProvider = SimpleLayerProvider.builder("护盾状态")
                        .showControls(true)
                        .defaultHidden(false)
                        .layerPriority(15)
                        .zIndex(300)
                        .build()
                    val shieldKey = Key.of("${SHIELD_LAYER_KEY}_${worldName}")
                    worldMap.layerRegistry().register(shieldKey, shieldProvider)
                    shieldLayerProviders[worldName] = shieldProvider

                    totalLayersCreated += 3
                    pluginLogger.info("为世界 ${worldName} 创建了 Squaremap 图层")
                } catch (e: Exception) {
                    pluginLogger.warning("为世界 ${world.name} 初始化 Squaremap 图层失败: ${e.message}")
                }
            }

            pluginLogger.info("Squaremap 图层初始化完成，共创建 ${totalLayersCreated} 个图层")
        } catch (e: Exception) {
            pluginLogger.warning("Squaremap 图层初始化失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 为单个世界注册图层
     * 🔧 v1.3.28: 支持运行时动态加载的世界
     */
    fun registerWorldLayers(world: World) {
        val squaremapInstance = squaremap ?: return

        try {
            val worldIdentifier = BukkitAdapter.worldIdentifier(world)
            val worldMapOptional = squaremapInstance.getWorldIfEnabled(worldIdentifier)

            if (!worldMapOptional.isPresent) {
                pluginLogger.fine("世界 ${world.name} 未在 Squaremap 中启用，跳过")
                return
            }

            val worldMap = worldMapOptional.get()
            val worldName = world.name

            // 检查是否已经注册
            if (territoryLayerProviders.containsKey(worldName)) {
                pluginLogger.fine("世界 ${worldName} 的图层已存在，跳过注册")
                return
            }

            // 创建领土图层
            val territoryProvider = SimpleLayerProvider.builder("国家领土")
                .showControls(true)
                .defaultHidden(false)
                .layerPriority(5)
                .zIndex(100)
                .build()
            val territoryKey = Key.of("${TERRITORY_LAYER_KEY}_${worldName}")
            worldMap.layerRegistry().register(territoryKey, territoryProvider)
            territoryLayerProviders[worldName] = territoryProvider

            // 创建首都图层
            val capitalProvider = SimpleLayerProvider.builder("国家首都")
                .showControls(true)
                .defaultHidden(false)
                .layerPriority(10)
                .zIndex(200)
                .build()
            val capitalKey = Key.of("${CAPITAL_LAYER_KEY}_${worldName}")
            worldMap.layerRegistry().register(capitalKey, capitalProvider)
            capitalLayerProviders[worldName] = capitalProvider

            // 创建护盾图层
            val shieldProvider = SimpleLayerProvider.builder("护盾状态")
                .showControls(true)
                .defaultHidden(false)
                .layerPriority(15)
                .zIndex(300)
                .build()
            val shieldKey = Key.of("${SHIELD_LAYER_KEY}_${worldName}")
            worldMap.layerRegistry().register(shieldKey, shieldProvider)
            shieldLayerProviders[worldName] = shieldProvider

            pluginLogger.info("为世界 ${worldName} 注册了 Squaremap 图层")
        } catch (e: Exception) {
            pluginLogger.warning("为世界 ${world.name} 注册 Squaremap 图层失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 注销单个世界的图层
     * 🔧 v1.3.28: 支持运行时动态卸载的世界
     */
    fun unregisterWorldLayers(world: World) {
        val squaremapInstance = squaremap ?: return

        try {
            val worldIdentifier = BukkitAdapter.worldIdentifier(world)
            val worldMapOptional = squaremapInstance.getWorldIfEnabled(worldIdentifier)

            if (worldMapOptional.isPresent) {
                val worldMap = worldMapOptional.get()
                val layerRegistry = worldMap.layerRegistry()
                val worldName = world.name

                // 注销该世界的所有图层
                layerRegistry.unregister(Key.of("${TERRITORY_LAYER_KEY}_${worldName}"))
                layerRegistry.unregister(Key.of("${CAPITAL_LAYER_KEY}_${worldName}"))
                layerRegistry.unregister(Key.of("${SHIELD_LAYER_KEY}_${worldName}"))

                pluginLogger.info("已注销世界 ${worldName} 的 Squaremap 图层")
            }

            // 清理引用
            val worldName = world.name
            territoryLayerProviders.remove(worldName)
            capitalLayerProviders.remove(worldName)
            shieldLayerProviders.remove(worldName)
        } catch (e: Exception) {
            pluginLogger.warning("注销世界 ${world.name} 的 Squaremap 图层时出错: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 更新领土地图显示
     * 在Squaremap上显示所有国家的领土和王城
     * 🔧 v1.3.24: 添加 forceUpdate 参数以支持立即更新
     */
    fun updateTerritoryMap(forceUpdate: Boolean = false) {
        if (!isEnabled) {
            pluginLogger.fine("Squaremap未启用，跳过地图更新")
            return
        }

        val currentTime = System.currentTimeMillis()
        if (!forceUpdate && currentTime - lastUpdateTime < updateInterval) {
            pluginLogger.fine("地图更新间隔未到，跳过更新")
            return
        }

        try {
            val startTime = System.currentTimeMillis()

            // 🔧 v1.3.27: 清除所有世界的所有图层的旧标记
            territoryLayerProviders.values.forEach { it.clearMarkers() }
            capitalLayerProviders.values.forEach { it.clearMarkers() }
            shieldLayerProviders.values.forEach { it.clearMarkers() }

            // 获取所有国家和领土数据
            val countries = CountryManager.countries.values
            var totalTerritories = 0
            var processedCountries = 0

            countries.forEach { country ->
                val territories = TerritoryManager.getTerritoriesByCountry(country)
                totalTerritories += territories.size

                // 🔧 v1.3.48: 修复问题1 - 使用国家的固定颜色，而不是动态生成
                val countryColor = country.getOrGenerateMapColor()

                // 🔧 v1.3.27: 按世界分组处理领土
                val territoriesByWorld = territories.groupBy { it.world }
                territoriesByWorld.forEach { (worldName, worldTerritories) ->
                    updateCountryTerritories(country, worldTerritories, countryColor, worldName)
                }

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
     * 🔧 v1.3.48: 修复问题1 - 已移除旧的颜色生成方法，现在使用Country.getOrGenerateMapColor()
     * 获取或生成国家颜色（已弃用，保留用于兼容性）
     */
    @Deprecated("使用 Country.getOrGenerateMapColor() 替代", ReplaceWith("country.getOrGenerateMapColor()"))
    private fun getOrGenerateCountryColor(countryId: UUID): Color {
        // 这个方法已弃用，但保留以防有其他地方调用
        val country = cn.lcofficial.guozhan.manager.CountryManager.getCountry(countryId)
        return country?.getOrGenerateMapColor() ?: Color.GRAY
    }

    /**
     * 更新特定国家的领土显示
     * v1.3.18实现：使用Squaremap API绘制领土多边形和首都标记
     * 🔧 v1.3.27: 添加 worldName 参数以支持多世界
     */
    private fun updateCountryTerritories(
        country: cn.lcofficial.guozhan.data.Country,
        territories: List<cn.lcofficial.guozhan.data.TerritoryBlock>,
        color: Color,
        worldName: String
    ) {
        try {
            // 🔧 v1.3.27: 获取该世界的图层提供者
            val territoryProvider = territoryLayerProviders[worldName] ?: return
            val capitalProvider = capitalLayerProviders[worldName] ?: return
            val shieldProvider = shieldLayerProviders[worldName] ?: return

            if (territories.isEmpty()) return

            // 绘制领土多边形
            drawTerritoryPolygons(territoryProvider, country, territories, color)

            // 绘制首都标记
            drawCapitalMarkers(capitalProvider, country, territories, color)

            // 绘制护盾状态
            drawShieldOverlays(shieldProvider, country, territories)

            pluginLogger.fine("已更新国家 ${country.name} 在世界 ${worldName} 的地图显示: ${territories.size}块领土")

        } catch (e: Exception) {
            pluginLogger.warning("更新国家 ${country.name} 在世界 ${worldName} 的领土显示失败: ${e.message}")
        }
    }

    /**
     * 绘制领土多边形
     * 🔧 v1.3.30: 添加世界名称到标记键，避免多世界环境下的键冲突
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

                // 🔧 v1.3.30: 包含世界名称，避免多世界环境下的键冲突
                val markerKey = "guozhan_territory_${territory.world}_${territory.x}_${territory.z}"
                provider.addMarker(Key.of(markerKey), polygon)
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

                // 🔧 v1.3.35: 修复Squaremap图标键格式问题 - 使用简单的图标名称
                // Squaremap Key不允许冒号字符，直接使用图标名称
                val iconKey = try {
                    Key.of("greenflag")
                } catch (e: Exception) {
                    // 如果 greenflag 不可用，尝试使用其他内置图标
                    pluginLogger.warning("无法使用 greenflag 图标，尝试使用 redflag: ${e.message}")
                    try {
                        Key.of("redflag")
                    } catch (e2: Exception) {
                        // 如果都不可用，使用默认的marker图标
                        pluginLogger.warning("无法使用 redflag 图标，使用默认图标: ${e2.message}")
                        Key.of("marker")
                    }
                }

                val marker = Marker.icon(Point.of(chunkX.toDouble(), chunkZ.toDouble()), iconKey, 32)
                    .markerOptions(
                        MarkerOptions.builder()
                            .clickTooltip("${country.name} - 首都")
                            .hoverTooltip("国家: ${country.name}<br>首都位置: (${capital.x}, ${capital.z})<br>核心血量: ${country.coreHealth}")
                            .build()
                    )

                provider.addMarker(Key.of("guozhan_capital_${country.id}"), marker)
                pluginLogger.fine("已为国家 ${country.name} 添加首都标记，位置: (${capital.x}, ${capital.z})")
            }
        } catch (e: Exception) {
            pluginLogger.warning("绘制首都标记失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 绘制护盾状态叠加层
     * 🔧 v1.3.30: 添加世界名称到标记键，避免多世界环境下的键冲突
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

                // 🔧 v1.3.30: 包含世界名称，避免多世界环境下的键冲突
                val markerKey = "guozhan_shield_${territory.world}_${territory.x}_${territory.z}"
                provider.addMarker(Key.of(markerKey), shieldPolygon)
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
        updateTerritoryMap(forceUpdate = true)
    }

    /**
     * 触发地图更新（在主线程执行）
     * 🔧 v1.3.24: 新增方法，用于在游戏事件中触发地图更新
     * 🔧 v1.3.30: 关键修复 - 改为在主线程执行，避免 Squaremap API 的并发问题
     */
    fun triggerMapUpdate() {
        if (!isEnabled) return

        // 🔧 v1.3.30: 使用主线程执行地图更新，确保 Squaremap API 的线程安全
        // Squaremap API 依赖 Bukkit 对象，这些对象通常不是线程安全的
        cn.lcofficial.guozhan.util.run { _ ->
            try {
                updateTerritoryMap(forceUpdate = true)
            } catch (e: Exception) {
                pluginLogger.warning("地图更新失败: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * 清理资源
     * 🔧 v1.3.26: 正确注销 Squaremap 图层，防止重载时注册失败
     * 🔧 v1.3.27: 支持多世界，注销所有世界的图层
     */
    fun cleanup() {
        try {
            // 🔧 v1.3.27: 清理所有世界的图层标记
            territoryLayerProviders.values.forEach { it.clearMarkers() }
            capitalLayerProviders.values.forEach { it.clearMarkers() }
            shieldLayerProviders.values.forEach { it.clearMarkers() }

            // 🔧 v1.3.27: 从 Squaremap 注销所有世界的图层
            val squaremapInstance = squaremap
            if (squaremapInstance != null) {
                var totalLayersUnregistered = 0

                Bukkit.getWorlds().forEach { world ->
                    try {
                        val worldIdentifier = BukkitAdapter.worldIdentifier(world)
                        val worldMapOptional = squaremapInstance.getWorldIfEnabled(worldIdentifier)

                        if (worldMapOptional.isPresent) {
                            val worldMap = worldMapOptional.get()
                            val layerRegistry = worldMap.layerRegistry()
                            val worldName = world.name

                            // 注销该世界的所有图层
                            layerRegistry.unregister(Key.of("${TERRITORY_LAYER_KEY}_${worldName}"))
                            layerRegistry.unregister(Key.of("${CAPITAL_LAYER_KEY}_${worldName}"))
                            layerRegistry.unregister(Key.of("${SHIELD_LAYER_KEY}_${worldName}"))

                            totalLayersUnregistered += 3
                            pluginLogger.fine("已从世界 ${worldName} 注销 Squaremap 图层")
                        }
                    } catch (e: Exception) {
                        pluginLogger.warning("注销世界 ${world.name} 的 Squaremap 图层时出错: ${e.message}")
                    }
                }

                pluginLogger.info("已从 Squaremap 注销 ${totalLayersUnregistered} 个图层")
            }

            // 清理引用
            territoryLayerProviders.clear()
            capitalLayerProviders.clear()
            shieldLayerProviders.clear()
            squaremap = null

            countryColors.clear()
            isEnabled = false
            pluginLogger.info("Squaremap集成已清理资源")
        } catch (e: Exception) {
            pluginLogger.warning("Squaremap集成清理资源时出错: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 检查Squaremap是否可用
     */
    fun isSquaremapAvailable(): Boolean {
        return isEnabled && squaremap != null
    }
}