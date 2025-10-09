package cn.lcofficial.guozhan.task

import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.manager.TerritoryManager
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.block.Chest
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.*

class WarEventScheduler {
    private var isWarActive = false
    private var warStartTime: LocalDateTime? = null
    private val warScores = mutableMapOf<UUID, Int>()
    
    companion object {
        const val WAR_DAY = 6 // 周六
        const val PREPARE_HOUR = 19
        const val PREPARE_MINUTE = 0
        const val WAR_START_HOUR = 19
        const val WAR_START_MINUTE = 20
        const val WAR_END_HOUR = 22
        const val WAR_END_MINUTE = 0
    }
    
    fun run() {
        val now = LocalDateTime.now()

        if (!isWarActive && shouldStartWar(now)) {
            startWar()
        } else if (isWarActive && shouldEndWar(now)) {
            endWar()
        }

        if (isWarActive && now.toLocalTime().isAfter(LocalTime.of(WAR_START_HOUR, WAR_START_MINUTE))) {
            updateWarScores()
        }
    }
    
    private fun shouldStartWar(now: LocalDateTime): Boolean {
        val currentTime = now.toLocalTime()
        val startTime = LocalTime.of(PREPARE_HOUR, PREPARE_MINUTE)
        val endTime = startTime.plusMinutes(1) // 1分钟的时间窗口

        return now.dayOfWeek == DayOfWeek.of(WAR_DAY) &&
               !currentTime.isBefore(startTime) && currentTime.isBefore(endTime)
    }

    private fun shouldEndWar(now: LocalDateTime): Boolean {
        val currentTime = now.toLocalTime()
        val endTime = LocalTime.of(WAR_END_HOUR, WAR_END_MINUTE)
        val windowEnd = endTime.plusMinutes(1) // 1分钟的时间窗口

        return now.dayOfWeek == DayOfWeek.of(WAR_DAY) &&
               !currentTime.isBefore(endTime) && currentTime.isBefore(windowEnd)
    }
    
    private fun startWar() {
        isWarActive = true
        warStartTime = LocalDateTime.now()
        warScores.clear()
        
        Bukkit.getOnlinePlayers().forEach { player ->
            player.sendTitle("§c国战开始", "§e准备时间: 19:00-19:20", 10, 70, 20)
        }
        
        Bukkit.broadcastMessage("§6[国战] §c国战准备阶段开始！正式战斗将在20分钟后开始")
    }
    
    private fun endWar() {
        isWarActive = false
        
        val winners = calculateWinners()
        distributeRewards(winners)
        
        Bukkit.getOnlinePlayers().forEach { player ->
            player.sendTitle("§a国战结束", "§e查看奖励箱获取战利品", 10, 70, 20)
        }
        
        Bukkit.broadcastMessage("§6[国战] §a国战结束！奖励已发放至各国王城")
    }
    
    private fun updateWarScores() {
        val countries = CountryManager.countries.values
        
        countries.forEach { country ->
            val coreTerritories = getCoreTerritories(country)
            val score = coreTerritories.size
            warScores[country.id] = score
        }
    }
    
    private fun getCoreTerritories(country: cn.lcofficial.guozhan.data.Country): List<cn.lcofficial.guozhan.data.TerritoryBlock> {
        // 获取核心疆域 [-64,63] 范围内的领土
        return TerritoryManager.getTerritoriesByCountry(country).filter { territory ->
            territory.x in -64..63 && territory.z in -64..63
        }
    }
    
    private fun calculateWinners(): Map<UUID, Int> {
        return warScores.toMap()
    }
    
    private fun distributeRewards(winners: Map<UUID, Int>) {
        val totalScore = winners.values.sum()
        if (totalScore == 0) return
        
        winners.forEach { (countryId, score) ->
            val country = CountryManager.getCountry(countryId)
            if (country != null) {
                val rewardPercentage = score.toDouble() / totalScore
                val goldReward = (1000 * rewardPercentage).toInt()
                val diamondReward = (100 * rewardPercentage).toInt()
                
                // 在王城附近生成奖励箱
                val location = country.getCoreLocation()?.clone()?.add(0.0, 1.0, 0.0)
                if (location != null) {
                    val block = location.block
                    block.type = Material.CHEST
                    val chest = block.state as Chest
                    
                    // 添加奖励物品
                    val inventory = chest.inventory
                    repeat(goldReward) {
                        inventory.addItem(org.bukkit.inventory.ItemStack(Material.GOLD_INGOT))
                    }
                    repeat(diamondReward) {
                        inventory.addItem(org.bukkit.inventory.ItemStack(Material.DIAMOND))
                    }
                    
                    // 添加粒子效果
                    location.world.spawnParticle(
                        Particle.FIREWORK,
                        location.clone().add(0.5, 1.0, 0.5),
                        50,
                        0.5,
                        0.5,
                        0.5,
                        0.1
                    )
                }
            }
        }
    }
    
    fun isWarTime(): Boolean = isWarActive
    
    fun isPreparationTime(): Boolean {
        val now = LocalDateTime.now()
        return now.dayOfWeek == DayOfWeek.of(WAR_DAY) && 
               now.toLocalTime().isAfter(LocalTime.of(PREPARE_HOUR, PREPARE_MINUTE)) &&
               now.toLocalTime().isBefore(LocalTime.of(WAR_START_HOUR, WAR_START_MINUTE))
    }
    
    fun isCoreWarZone(x: Int, z: Int): Boolean {
        return isWarTime() && x in -128..127 && z in -128..127
    }
}