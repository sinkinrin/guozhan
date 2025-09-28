package cn.lcofficial.guozhan.integration

import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.manager.TerritoryManager
import org.bukkit.Bukkit
import xyz.jpenilla.squaremap.api.Squaremap
import xyz.jpenilla.squaremap.api.SquaremapProvider
import xyz.jpenilla.squaremap.api.WorldIdentifier
import java.awt.Color
import java.util.*

class SquaremapIntegration {
    private var squaremap: Squaremap? = null
    
    fun initialize() {
        val plugin = Bukkit.getPluginManager().getPlugin("squaremap")
        if (plugin != null) {
            squaremap = SquaremapProvider.get()
            updateTerritoryMap()
            Bukkit.getLogger().info("Squaremap集成已启用")
        } else {
            Bukkit.getLogger().warning("Squaremap插件未找到，地图功能将不可用")
        }
    }
    
    fun updateTerritoryMap() {
        squaremap?.let { map ->
            val world = Bukkit.getWorld("world") ?: return
            val worldId = WorldIdentifier.of(world.uid)
            
            val api = map.getWorldIfEnabled(worldId) ?: return
            
            // 清除现有标记
            api.layerRegistry().get("guozhan_territories")?.let { layer ->
                layer.clearMarkers()
            }
            
            // 添加国家领土标记
            val countries = CountryManager.getAllCountries()
            countries.forEach { country ->
                val territories = TerritoryManager.getTerritoriesByCountry(country.id)
                
                territories.forEach { territory ->
                    val x = territory.x * 16
                    val z = territory.z * 16
                    
                    // 随机颜色
                    val color = Color.getHSBColor(Random(country.id.hashCode().toLong()).nextFloat(), 0.7f, 0.9f)
                    
                    // 添加区块边框
                    api.layerRegistry().get("guozhan_territories")?.let { layer ->
                        layer.addRectangle(
                            "territory_${territory.x}_${territory.z}",
                            x.toDouble(),
                            z.toDouble(),
                            (x + 16).toDouble(),
                            (z + 16).toDouble(),
                            color
                        )
                    }
                    
                    // 如果是王城，添加特殊标记
                    if (territory.isCapital) {
                        api.layerRegistry().get("guozhan_capitals")?.let { layer ->
                            layer.addIcon(
                                "capital_${country.id}",
                                x + 8.0,
                                z + 8.0,
                                "crown",
                                "王城: ${country.name}"
                            )
                        }
                    }
                }
            }
        }
    }
    
    fun updateCountryColor(countryId: UUID) {
        updateTerritoryMap()
    }
}