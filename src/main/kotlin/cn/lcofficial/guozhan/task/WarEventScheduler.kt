package cn.lcofficial.guozhan.task

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.config.Config
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

    // 🔧 v1.3.31: 移除硬编码常量，改为从配置文件读取
    // 战争时间配置现在从 Config.War 对象读取
    
    fun run() {
        val now = LocalDateTime.now()

        if (!isWarActive && shouldStartWar(now)) {
            startWar()
        } else if (isWarActive && shouldEndWar(now)) {
            endWar()
        }

        // 🔧 v1.3.31: 使用配置文件中的战争时间设置
        if (isWarActive && now.toLocalTime().isAfter(LocalTime.of(Config.War.startHour, Config.War.startMinute))) {
            updateWarScores()
        }
    }

    private fun shouldStartWar(now: LocalDateTime): Boolean {
        val currentTime = now.toLocalTime()
        val startTime = LocalTime.of(Config.War.prepareHour, Config.War.prepareMinute)
        val endTime = startTime.plusMinutes(1) // 1分钟的时间窗口

        return now.dayOfWeek == DayOfWeek.of(Config.War.day) &&
               !currentTime.isBefore(startTime) && currentTime.isBefore(endTime)
    }

    private fun shouldEndWar(now: LocalDateTime): Boolean {
        val currentTime = now.toLocalTime()
        val endTime = LocalTime.of(Config.War.endHour, Config.War.endMinute)
        val windowEnd = endTime.plusMinutes(1) // 1分钟的时间窗口

        return now.dayOfWeek == DayOfWeek.of(Config.War.day) &&
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
            // 改为“累计积分”而不是覆盖：每次更新都在已有基础上累加
            val current = warScores.getOrDefault(country.id, 0)
            warScores[country.id] = current + score
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
    
    fun distributeRewards(winners: Map<UUID, Int>) {
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
                    // 🔧 修复：使用 Region Scheduler 在正确的区域线程中执行区块操作
                    Bukkit.getRegionScheduler().execute(Guozhan.instance, location) {
                        try {
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

                            Guozhan.instance.logger.info("[王战奖励] 已为国家 ${country.name} 生成奖励箱: ${goldReward}金 ${diamondReward}钻")
                        } catch (e: Exception) {
                            Guozhan.instance.logger.severe("[王战奖励] 为国家 ${country.name} 生成奖励箱时出错: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
    }
    
    fun isWarTime(): Boolean = isWarActive
    
    fun isPreparationTime(): Boolean {
        val now = LocalDateTime.now()
        // 🔧 v1.3.31: 使用配置文件中的战争时间设置
        return now.dayOfWeek == DayOfWeek.of(Config.War.day) &&
               now.toLocalTime().isAfter(LocalTime.of(Config.War.prepareHour, Config.War.prepareMinute)) &&
               now.toLocalTime().isBefore(LocalTime.of(Config.War.startHour, Config.War.startMinute))
    }
    
    fun isCoreWarZone(x: Int, z: Int): Boolean {
        // 🔧 v1.3.31: 使用配置文件中的战争领土范围
        val range = Config.War.warTerritoryRange
        val minX = if (range.size >= 2) range[0] else -128
        val maxX = if (range.size >= 2) range[1] else 127
        return isWarTime() && x in minX..maxX && z in minX..maxX
    }
}