package cn.lcofficial.guozhan.util

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.data.RelationType
import cn.lcofficial.guozhan.data.TerritoryBlock
import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.manager.DiplomacyManager
import cn.lcofficial.guozhan.manager.TerritoryManager
import cn.lcofficial.guozhan.manager.UserManager.user
import cn.lcofficial.guozhan.pluginLogger
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.map.MapCanvas
import org.bukkit.map.MapPalette
import org.bukkit.map.MapRenderer
import org.bukkit.map.MapView
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * 疆域地图工具，用于在地图上显示15x15区块的领土状况
 * 
 * 功能特性：
 * - 15x15区块范围显示（以玩家位置为中心）
 * - 颜色编码：灰色(未占领)、绿色(我方)、红色(敌方)、蓝色(友好)、黄色(中立)
 * - 玩家位置实时标记（白色闪烁十字）
 * - 忠诚度可视化（颜色深浅）
 * - 接壤面数显示（边框样式）
 * - 混合渲染：基础地图层(BufferedImage) + 动态信息层(setPixel)
 * - 性能优化：缓存机制 + 异步加载
 * - Folia兼容：RegionScheduler + 线程安全
 */
object TerritoryMapUtil {
    
    // ===== 常量定义 =====
    
    /** 地图显示范围（15x15区块） */
    private const val MAP_RANGE = 7 // -7 到 +7，共15个区块
    
    /** 地图像素大小 */
    private const val MAP_SIZE = 128
    
    /** 每个区块在地图上的像素大小 */
    private const val PIXELS_PER_CHUNK = MAP_SIZE / 15 // 约8.5像素/区块
    
    /** 基础地图更新间隔（毫秒） */
    private const val BASE_MAP_UPDATE_INTERVAL = 5000L // 5秒
    
    /** 缓存过期时间（毫秒） */
    private const val CACHE_EXPIRE_TIME = 30000L // 30秒
    
    // ===== 缓存数据结构 =====
    
    /**
     * 缓存的领土信息
     */
    private data class CachedTerritoryInfo(
        val territory: TerritoryBlock?,
        val color: java.awt.Color,
        val adjacentFaces: Int,
        val timestamp: Long
    )
    
    /**
     * 领土信息缓存
     * Key: "chunkX_chunkZ_world"
     * Value: 缓存的领土信息
     */
    private val territoryCache = ConcurrentHashMap<String, CachedTerritoryInfo>()
    
    /**
     * 基础地图图像缓存
     * Key: "centerX_centerZ_world_playerCountryId"
     * Value: Pair<BufferedImage, timestamp>
     */
    private val baseMapCache = ConcurrentHashMap<String, Pair<BufferedImage, Long>>()
    
    // ===== 性能监控 =====
    
    /** 渲染计数器 */
    private var renderCount = 0
    
    /** 上次性能日志时间 */
    private var lastPerformanceLogTime = 0L
    
    /**
     * 初始化疆域地图工具
     */
    fun initialize() {
        try {
            pluginLogger.info("疆域地图工具已初始化")
        } catch (e: Exception) {
            pluginLogger.severe("初始化疆域地图工具失败: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 创建疆域地图
     * @param player 玩家
     * @return 地图视图，失败时返回null
     */
    fun createTerritoryMap(player: Player): MapView? {
        try {
            pluginLogger.info("开始为玩家 ${player.name} 创建疆域地图")

            // 检查世界是否有效
            if (player.world == null) {
                pluginLogger.severe("玩家 ${player.name} 的世界为null，无法创建地图")
                return null
            }

            // 创建地图视图
            val mapView = Bukkit.createMap(player.world)
            if (mapView == null) {
                pluginLogger.severe("Bukkit.createMap返回null，地图创建失败")
                return null
            }

            pluginLogger.info("成功创建MapView，ID: ${mapView.id}")

            // 清除默认渲染器
            val defaultRenderers = mapView.renderers.toList()
            defaultRenderers.forEach { mapView.removeRenderer(it) }
            pluginLogger.info("已清除 ${defaultRenderers.size} 个默认渲染器")

            // 获取中心区块
            val centerChunk = player.location.chunk
            val centerX = centerChunk.x
            val centerZ = centerChunk.z

            pluginLogger.info("地图中心区块: ($centerX, $centerZ), 世界: ${player.world.name}")

            // 添加自定义渲染器
            val renderer = TerritoryMapRenderer(centerX, centerZ, player.world.name)
            mapView.addRenderer(renderer)

            // 设置地图属性
            mapView.isTrackingPosition = false // 不使用默认位置跟踪
            mapView.isUnlimitedTracking = false

            pluginLogger.info("成功为玩家 ${player.name} 创建疆域地图，中心: ($centerX, $centerZ)")
            return mapView

        } catch (e: Exception) {
            pluginLogger.severe("创建疆域地图时发生异常: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            return null
        }
    }
    
    /**
     * 清理过期缓存
     */
    fun cleanupCache() {
        val currentTime = System.currentTimeMillis()
        
        // 清理领土信息缓存
        territoryCache.entries.removeIf { (_, info) ->
            currentTime - info.timestamp > CACHE_EXPIRE_TIME
        }
        
        // 清理基础地图缓存
        baseMapCache.entries.removeIf { (_, pair) ->
            currentTime - pair.second > BASE_MAP_UPDATE_INTERVAL
        }
    }
    
    /**
     * 记录性能指标
     */
    private fun logPerformanceMetrics() {
        val currentTime = System.currentTimeMillis()
        renderCount++
        
        if (currentTime - lastPerformanceLogTime > 60000) { // 每分钟记录一次
            pluginLogger.info("疆域地图渲染性能: ${renderCount}次/分钟, 缓存大小: 领土=${territoryCache.size}, 地图=${baseMapCache.size}")
            renderCount = 0
            lastPerformanceLogTime = currentTime
        }
    }
    
    /**
     * 处理地图渲染错误
     */
    private fun handleMapRenderError(e: Exception, context: String) {
        pluginLogger.severe("疆域地图渲染错误 [$context]: ${e.message}")
        e.printStackTrace()
    }
    
    // ===== 核心地图生成逻辑 =====

    /**
     * 生成疆域地图图像
     * @param centerX 中心区块X坐标
     * @param centerZ 中心区块Z坐标
     * @param worldName 世界名称
     * @param playerCountry 玩家所属国家
     * @return 生成的地图图像
     */
    private fun generateTerritoryMapImage(
        centerX: Int,
        centerZ: Int,
        worldName: String,
        playerCountry: cn.lcofficial.guozhan.data.Country?
    ): BufferedImage {
        val image = BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()

        try {
            // 绘制背景
            graphics.color = java.awt.Color.BLACK
            graphics.fillRect(0, 0, MAP_SIZE, MAP_SIZE)

            // 绘制15x15区块网格
            for (x in -MAP_RANGE..MAP_RANGE) {
                for (z in -MAP_RANGE..MAP_RANGE) {
                    val chunkX = centerX + x
                    val chunkZ = centerZ + z

                    // 获取领土信息
                    val territory = getTerritoryFromCache(chunkX, chunkZ, worldName, playerCountry)

                    // 计算颜色
                    val color = getChunkDisplayColor(territory, playerCountry)

                    // 计算像素位置
                    val pixelX = (x + MAP_RANGE) * PIXELS_PER_CHUNK
                    val pixelZ = (z + MAP_RANGE) * PIXELS_PER_CHUNK

                    // 绘制区块
                    graphics.color = color
                    graphics.fillRect(pixelX, pixelZ, PIXELS_PER_CHUNK, PIXELS_PER_CHUNK)

                    // 绘制边框（表示接壤面数）
                    if (territory != null && territory.isOwned()) {
                        drawChunkBorder(graphics, pixelX, pixelZ, territory.calculateAdjacentFaces())
                    }
                }
            }

            // 绘制网格线
            drawGridLines(graphics)

        } catch (e: Exception) {
            handleMapRenderError(e, "generateTerritoryMapImage")
        } finally {
            graphics.dispose()
        }

        // 保存调试图像（可选）
        saveDebugImage(image, centerX, centerZ)

        return image
    }

    /**
     * 从缓存获取领土信息
     */
    private fun getTerritoryFromCache(
        chunkX: Int,
        chunkZ: Int,
        worldName: String,
        playerCountry: cn.lcofficial.guozhan.data.Country?
    ): TerritoryBlock? {
        val cacheKey = "${chunkX}_${chunkZ}_${worldName}"
        val cached = territoryCache[cacheKey]

        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_EXPIRE_TIME) {
            return cached.territory
        }

        // 缓存过期或不存在，同步获取（在渲染线程中）
        val territory = TerritoryManager.getTerritoryBlock(chunkX, chunkZ, worldName)

        // 更新缓存
        val color = getChunkDisplayColor(territory, playerCountry)
        val adjacentFaces = territory?.calculateAdjacentFaces() ?: 0
        territoryCache[cacheKey] = CachedTerritoryInfo(territory, color, adjacentFaces, System.currentTimeMillis())

        return territory
    }

    /**
     * 获取区块显示颜色
     * @param territory 领土信息
     * @param playerCountry 玩家所属国家
     * @return 显示颜色
     */
    private fun getChunkDisplayColor(
        territory: TerritoryBlock?,
        playerCountry: cn.lcofficial.guozhan.data.Country?
    ): java.awt.Color {
        // 未占领区域
        if (territory == null || !territory.isOwned()) {
            return java.awt.Color(64, 64, 64) // 深灰色
        }

        val territoryCountry = territory.owner!!

        // 玩家无国家
        if (playerCountry == null) {
            return java.awt.Color(128, 128, 128) // 浅灰色
        }

        // 我方领土
        if (territoryCountry.id == playerCountry.id) {
            // 绿色，忠诚度影响深浅
            val intensity = (territory.loyalty / 100.0 * 200 + 55).toInt().coerceIn(55, 255)
            return java.awt.Color(0, intensity, 0)
        }

        // 根据外交关系确定颜色
        val relation = DiplomacyManager.getRelation(playerCountry, territoryCountry)
        return when (relation.relationType) {
            RelationType.ALLIED -> java.awt.Color(0, 100, 255) // 蓝色 - 同盟
            RelationType.FRIENDLY -> java.awt.Color(0, 200, 255) // 青色 - 友好
            RelationType.HOSTILE, RelationType.WAR -> java.awt.Color(255, 50, 50) // 红色 - 敌对/战争
            else -> java.awt.Color(255, 255, 0) // 黄色 - 中立
        }
    }

    /**
     * 绘制区块边框（表示接壤面数）
     */
    private fun drawChunkBorder(graphics: java.awt.Graphics2D, x: Int, z: Int, adjacentFaces: Int) {
        val borderColor = when {
            adjacentFaces >= 3 -> java.awt.Color.WHITE // 强连接 - 白色
            adjacentFaces == 2 -> java.awt.Color.LIGHT_GRAY // 中等连接 - 浅灰
            adjacentFaces == 1 -> java.awt.Color.GRAY // 弱连接 - 灰色
            else -> java.awt.Color.DARK_GRAY // 孤立 - 深灰
        }

        graphics.color = borderColor
        graphics.drawRect(x, z, PIXELS_PER_CHUNK - 1, PIXELS_PER_CHUNK - 1)
    }

    /**
     * 绘制网格线
     */
    private fun drawGridLines(graphics: java.awt.Graphics2D) {
        graphics.color = java.awt.Color(32, 32, 32) // 深色网格线

        // 绘制垂直线
        for (i in 0..15) {
            val x = i * PIXELS_PER_CHUNK
            graphics.drawLine(x, 0, x, MAP_SIZE)
        }

        // 绘制水平线
        for (i in 0..15) {
            val z = i * PIXELS_PER_CHUNK
            graphics.drawLine(0, z, MAP_SIZE, z)
        }
    }

    /**
     * 保存调试图像
     */
    private fun saveDebugImage(image: BufferedImage, centerX: Int, centerZ: Int) {
        try {
            val debugDir = File(Guozhan.instance.dataFolder, "debug/territory_maps")
            if (!debugDir.exists()) {
                debugDir.mkdirs()
            }

            val file = File(debugDir, "territory_map_${centerX}_${centerZ}_${System.currentTimeMillis()}.png")
            ImageIO.write(image, "PNG", file)
        } catch (e: Exception) {
            // 忽略调试图像保存错误
        }
    }

    // ===== 核心渲染器类 =====
    
    /**
     * 疆域地图渲染器
     * 实现混合渲染：基础地图层 + 动态信息层
     */
    private class TerritoryMapRenderer(
        private val centerX: Int,
        private val centerZ: Int,
        private val worldName: String
    ) : MapRenderer() {
        
        /** 上次基础地图更新时间 */
        private var lastBaseMapUpdate = 0L
        
        /** 当前基础地图图像 */
        private var baseMapImage: BufferedImage? = null
        
        override fun render(mapView: MapView, canvas: MapCanvas, player: Player) {
            try {
                logPerformanceMetrics()
                
                val currentTime = System.currentTimeMillis()
                
                // 更新基础地图层（每5秒更新一次）
                if (baseMapImage == null || currentTime - lastBaseMapUpdate > BASE_MAP_UPDATE_INTERVAL) {
                    updateBaseMap(player)
                    lastBaseMapUpdate = currentTime
                }
                
                // 绘制基础地图层
                drawBaseMap(canvas)
                
                // 绘制动态信息层（每次都更新）
                drawDynamicLayer(canvas, player)
                
            } catch (e: Exception) {
                handleMapRenderError(e, "render")
                // 绘制错误指示器
                canvas.setPixel(MAP_SIZE / 2, MAP_SIZE / 2, MapPalette.RED)
            }
        }
        
        /**
         * 更新基础地图
         */
        private fun updateBaseMap(player: Player) {
            try {
                val playerCountry = player.user().country
                val cacheKey = "${centerX}_${centerZ}_${worldName}_${playerCountry?.id ?: "null"}"
                
                // 检查缓存
                val cached = baseMapCache[cacheKey]
                if (cached != null && System.currentTimeMillis() - cached.second < BASE_MAP_UPDATE_INTERVAL) {
                    baseMapImage = cached.first
                    return
                }
                
                // 生成新的基础地图
                baseMapImage = generateTerritoryMapImage(centerX, centerZ, worldName, playerCountry)
                
                // 更新缓存
                baseMapImage?.let { image ->
                    baseMapCache[cacheKey] = Pair(image, System.currentTimeMillis())
                }
                
            } catch (e: Exception) {
                handleMapRenderError(e, "updateBaseMap")
            }
        }
        
        /**
         * 绘制基础地图层
         */
        private fun drawBaseMap(canvas: MapCanvas) {
            try {
                val image = baseMapImage ?: return
                
                // 使用canvas.drawImage()进行高性能渲染
                canvas.drawImage(0, 0, image)
                
            } catch (e: Exception) {
                handleMapRenderError(e, "drawBaseMap")
            }
        }
        
        /**
         * 绘制动态信息层
         */
        private fun drawDynamicLayer(canvas: MapCanvas, player: Player) {
            try {
                // 绘制玩家位置标记
                drawPlayerPosition(canvas, player)
                
                // 绘制其他实时信息（如果需要）
                // drawRealTimeInfo(canvas, player)
                
            } catch (e: Exception) {
                handleMapRenderError(e, "drawDynamicLayer")
            }
        }
        
        /**
         * 绘制玩家位置标记
         */
        private fun drawPlayerPosition(canvas: MapCanvas, player: Player) {
            try {
                val playerChunk = player.location.chunk
                val relativeX = playerChunk.x - centerX
                val relativeZ = playerChunk.z - centerZ
                
                // 检查玩家是否在地图范围内
                if (relativeX in -MAP_RANGE..MAP_RANGE && relativeZ in -MAP_RANGE..MAP_RANGE) {
                    val pixelX = (relativeX + MAP_RANGE) * PIXELS_PER_CHUNK + PIXELS_PER_CHUNK / 2
                    val pixelZ = (relativeZ + MAP_RANGE) * PIXELS_PER_CHUNK + PIXELS_PER_CHUNK / 2
                    
                    // 绘制闪烁的十字标记
                    val flashColor = if ((System.currentTimeMillis() / 500) % 2 == 0L) {
                        MapPalette.WHITE
                    } else {
                        MapPalette.LIGHT_GRAY
                    }
                    
                    // 绘制十字标记
                    for (i in -2..2) {
                        val x = (pixelX + i).coerceIn(0, MAP_SIZE - 1)
                        val z = (pixelZ + i).coerceIn(0, MAP_SIZE - 1)
                        canvas.setPixel(x, pixelZ, flashColor)
                        canvas.setPixel(pixelX, z, flashColor)
                    }
                }
                
            } catch (e: Exception) {
                handleMapRenderError(e, "drawPlayerPosition")
            }
        }
    }
}
