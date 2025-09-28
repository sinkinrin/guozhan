package cn.lcofficial.guozhan.util

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.economy.RegionalTaxSystem
import cn.lcofficial.guozhan.pluginLogger
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.map.MapCanvas
import org.bukkit.map.MapPalette
import org.bukkit.map.MapRenderer
import org.bukkit.map.MapView
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 税收区域地图工具，用于在地图上显示税收区域
 */
object TaxRegionMapUtil {
    
    // 区域颜色映射
    private val regionColors = mapOf(
        RegionalTaxSystem.TaxRegion.CORE_TERRITORY to Color.fromRGB(255, 0, 0),      // 红色
        RegionalTaxSystem.TaxRegion.INLAND_TERRITORY to Color.fromRGB(255, 165, 0),  // 橙色
        RegionalTaxSystem.TaxRegion.FRONTIER_TERRITORY to Color.fromRGB(255, 255, 0), // 黄色
        RegionalTaxSystem.TaxRegion.DISPUTED_TERRITORY to Color.fromRGB(0, 255, 0),   // 绿色
        RegionalTaxSystem.TaxRegion.EXPEDITION_TERRITORY to Color.fromRGB(0, 0, 255), // 蓝色
        RegionalTaxSystem.TaxRegion.WILDERNESS_TERRITORY to Color.fromRGB(128, 0, 128) // 紫色
    )
    
    // 缓存的区域地图图像
    private var cachedMapImage: BufferedImage? = null
    
    /**
     * 初始化税收区域地图
     */
    fun initialize() {
        try {
            // 生成区域地图图像
            generateRegionMapImage()
            
            pluginLogger.info("税收区域地图已初始化")
        } catch (e: Exception) {
            pluginLogger.severe("初始化税收区域地图失败: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 生成区域地图图像
     */
    private fun generateRegionMapImage() {
        val size = 512 // 地图大小
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        
        // 绘制背景
        g.color = java.awt.Color.WHITE
        g.fillRect(0, 0, size, size)
        
        // 绘制区域
        val center = size / 2
        val pixelsPerBlock = size / (RegionalTaxSystem.TaxRegion.WILDERNESS_TERRITORY.range * 2)
        
        // 从外到内绘制区域
        for (region in RegionalTaxSystem.TaxRegion.values().reversed()) {
            val radius = (region.range * pixelsPerBlock).coerceAtMost(center)
            val color = regionColors[region]?.let { java.awt.Color(it.red, it.green, it.blue) } 
                ?: java.awt.Color.GRAY
            
            g.color = color
            g.fillOval(center - radius, center - radius, radius * 2, radius * 2)
        }
        
        // 绘制中心点
        g.color = java.awt.Color.BLACK
        g.fillOval(center - 5, center - 5, 10, 10)
        
        // 绘制坐标轴
        g.drawLine(0, center, size, center)
        g.drawLine(center, 0, center, size)
        
        g.dispose()
        
        // 保存图像
        cachedMapImage = image
        
        // 保存到文件（调试用）
        try {
            val file = File(Guozhan.instance.dataFolder, "tax_regions_map.png")
            ImageIO.write(image, "PNG", file)
        } catch (e: Exception) {
            pluginLogger.warning("保存税收区域地图图像失败: ${e.message}")
        }
    }
    
    /**
     * 创建税收区域地图
     * @return 地图视图
     */
    fun createTaxRegionMap(): MapView? {
        try {
            val mapView = Bukkit.createMap(Bukkit.getWorlds()[0])
            mapView.renderers.clear()
            mapView.addRenderer(TaxRegionMapRenderer())
            mapView.isTrackingPosition = true
            mapView.isUnlimitedTracking = true
            
            return mapView
        } catch (e: Exception) {
            pluginLogger.severe("创建税收区域地图失败: ${e.message}")
            e.printStackTrace()
            return null
        }
    }
    
    /**
     * 税收区域地图渲染器
     */
    private class TaxRegionMapRenderer : MapRenderer() {
        
        private var rendered = false
        
        override fun render(mapView: MapView, canvas: MapCanvas, player: Player) {
            if (rendered && !mapView.isTrackingPosition) return
            
            try {
                // 绘制基础地图
                val image = cachedMapImage
                if (image != null) {
                    for (x in 0 until 128) {
                        for (y in 0 until 128) {
                            val imgX = (x * image.width / 128).coerceIn(0, image.width - 1)
                            val imgY = (y * image.height / 128).coerceIn(0, image.height - 1)
                            val rgb = image.getRGB(imgX, imgY)
                            val color = MapPalette.matchColor(
                                (rgb shr 16) and 0xFF,
                                (rgb shr 8) and 0xFF,
                                rgb and 0xFF
                            )
                            canvas.setPixel(x, y, color)
                        }
                    }
                }
                
                // 如果是跟踪位置，绘制玩家位置
                if (mapView.isTrackingPosition) {
                    val playerLoc = player.location
                    val world = playerLoc.world
                    
                    // 只在主世界显示玩家位置
                    if (world.environment == World.Environment.NORMAL) {
                        // 计算玩家在地图上的位置
                        val mapSize = RegionalTaxSystem.TaxRegion.WILDERNESS_TERRITORY.range * 2
                        val x = ((playerLoc.blockX + mapSize / 2) * 128 / mapSize).coerceIn(0, 127)
                        val z = ((playerLoc.blockZ + mapSize / 2) * 128 / mapSize).coerceIn(0, 127)
                        
                        // 绘制玩家位置标记
                        canvas.setPixel(x, z, MapPalette.DARK_BROWN)
                        if (x > 0) canvas.setPixel(x - 1, z, MapPalette.DARK_BROWN)
                        if (x < 127) canvas.setPixel(x + 1, z, MapPalette.DARK_BROWN)
                        if (z > 0) canvas.setPixel(x, z - 1, MapPalette.DARK_BROWN)
                        if (z < 127) canvas.setPixel(x, z + 1, MapPalette.DARK_BROWN)
                    }
                }
                
                rendered = true
            } catch (e: Exception) {
                pluginLogger.severe("渲染税收区域地图失败: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    /**
     * 获取指定位置的税收区域
     * @param location 位置
     * @return 税收区域
     */
    fun getRegionAtLocation(location: Location): RegionalTaxSystem.TaxRegion {
        val x = location.blockX shr 4 // 区块X坐标
        val z = location.blockZ shr 4 // 区块Z坐标
        return RegionalTaxSystem.TaxRegion.getRegionByCoordinates(x, z)
    }
}