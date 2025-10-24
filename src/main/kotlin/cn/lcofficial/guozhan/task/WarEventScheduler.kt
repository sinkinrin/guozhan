package cn.lcofficial.guozhan.task

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.config.Config
import cn.lcofficial.guozhan.data.WarEvent
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

    // 🔧 v1.3.52: 战争状态持久化 - 当前战争事件
    private var currentWarEvent: WarEvent? = null

    // 🔧 v1.3.52: 时间窗口容错 - 记录上次状态检查时间
    private var lastStateCheckTime: LocalDateTime = LocalDateTime.now()

    // 🔧 v1.3.31: 移除硬编码常量，改为从配置文件读取
    // 战争时间配置现在从 Config.War 对象读取

    /**
     * 初始化战争调度器
     * 🔧 v1.3.52: 从数据库恢复战争状态（如果服务器在战争期间重启）
     */
    fun initialize() {
        try {
            val activeWars = WarEvent.loadAllActive()
            if (activeWars.isNotEmpty()) {
                val warEvent = activeWars.first()
                currentWarEvent = warEvent
                isWarActive = true
                warStartTime = LocalDateTime.ofEpochSecond(
                    warEvent.startTime / 1000,
                    (warEvent.startTime % 1000).toInt() * 1000000,
                    java.time.ZoneOffset.systemDefault().rules.getOffset(java.time.Instant.now())
                )
                warScores.clear()
                warScores.putAll(warEvent.warScores)

                Guozhan.instance.logger.info("[王战系统] 从数据库恢复战争状态: 战争ID=${warEvent.id}, 开始时间=${warStartTime}, 积分数=${warScores.size}")
                Bukkit.broadcastMessage("§6[王战] §e服务器重启后战争继续进行！")
            } else {
                Guozhan.instance.logger.info("[王战系统] 初始化完成，当前无进行中的战争")
            }
        } catch (e: Exception) {
            Guozhan.instance.logger.severe("[王战系统] 初始化失败: ${e.message}")
            e.printStackTrace()
        }
    }

    fun run() {
        val now = LocalDateTime.now()

        // 🔧 v1.3.52: 检查是否应该开始战争（包括错过的时间窗口）
        if (!isWarActive && shouldStartWar(now)) {
            startWar()
        } else if (!isWarActive && missedWarStart(now)) {
            Guozhan.instance.logger.warning("[王战系统] 检测到错过战争开始时间，立即开始战争")
            startWar()
        }

        // 🔧 v1.3.52: 检查是否应该结束战争（包括错过的时间窗口）
        if (isWarActive && shouldEndWar(now)) {
            endWar()
        } else if (isWarActive && missedWarEnd(now)) {
            Guozhan.instance.logger.warning("[王战系统] 检测到错过战争结束时间，立即结束战争")
            endWar()
        }

        // 🔧 v1.3.31: 使用配置文件中的战争时间设置
        if (isWarActive && now.toLocalTime().isAfter(LocalTime.of(Config.War.startHour, Config.War.startMinute))) {
            updateWarScores()
        }

        // 🔧 v1.3.52: 更新上次检查时间
        lastStateCheckTime = now
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

    /**
     * 检查是否错过了战争开始时间
     * 🔧 v1.3.52: 容错机制 - 如果上次检查时间在开始窗口之前，当前时间在开始窗口之后，则认为错过了
     */
    private fun missedWarStart(now: LocalDateTime): Boolean {
        if (isWarActive) return false

        val startTime = LocalTime.of(Config.War.prepareHour, Config.War.prepareMinute)
        val windowEnd = startTime.plusMinutes(1)
        val warEndTime = LocalTime.of(Config.War.endHour, Config.War.endMinute)

        return now.dayOfWeek == DayOfWeek.of(Config.War.day) &&
               lastStateCheckTime.toLocalTime().isBefore(startTime) &&
               now.toLocalTime().isAfter(windowEnd) &&
               now.toLocalTime().isBefore(warEndTime)
    }

    /**
     * 检查是否错过了战争结束时间
     * 🔧 v1.3.52: 容错机制 - 如果上次检查时间在结束窗口之前，当前时间在结束窗口之后，则认为错过了
     */
    private fun missedWarEnd(now: LocalDateTime): Boolean {
        if (!isWarActive) return false

        val endTime = LocalTime.of(Config.War.endHour, Config.War.endMinute)
        val windowEnd = endTime.plusMinutes(1)

        return now.dayOfWeek == DayOfWeek.of(Config.War.day) &&
               lastStateCheckTime.toLocalTime().isBefore(endTime) &&
               now.toLocalTime().isAfter(windowEnd)
    }
    
    private fun startWar() {
        isWarActive = true
        warStartTime = LocalDateTime.now()
        warScores.clear()

        // 🔧 v1.3.52: 持久化战争状态到数据库
        try {
            val warEvent = WarEvent(
                id = UUID.randomUUID(),
                startTime = System.currentTimeMillis(),
                isActive = true,
                warScores = warScores
            )
            warEvent.save()
            currentWarEvent = warEvent
            Guozhan.instance.logger.info("[王战系统] 战争状态已保存到数据库: 战争ID=${warEvent.id}")
        } catch (e: Exception) {
            Guozhan.instance.logger.severe("[王战系统] 保存战争状态失败: ${e.message}")
            e.printStackTrace()
        }

        Bukkit.getOnlinePlayers().forEach { player ->
            player.sendTitle("§c国战开始", "§e准备时间: 19:00-19:20", 10, 70, 20)
        }

        Bukkit.broadcastMessage("§6[国战] §c国战准备阶段开始！正式战斗将在20分钟后开始")
    }
    
    private fun endWar() {
        isWarActive = false

        val winners = calculateWinners()
        distributeRewards(winners)

        // 🔧 v1.3.52: 清理数据库中的战争状态
        try {
            currentWarEvent?.let { warEvent ->
                warEvent.isActive = false
                warEvent.delete()
                Guozhan.instance.logger.info("[王战系统] 战争状态已从数据库删除: 战争ID=${warEvent.id}")
            }
            currentWarEvent = null
        } catch (e: Exception) {
            Guozhan.instance.logger.severe("[王战系统] 删除战争状态失败: ${e.message}")
            e.printStackTrace()
        }

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

        // 🔧 v1.3.52: 更新数据库中的战争积分
        try {
            currentWarEvent?.let { warEvent ->
                warEvent.warScores.clear()
                warEvent.warScores.putAll(warScores)
                warEvent.save()
            }
        } catch (e: Exception) {
            Guozhan.instance.logger.severe("[王战系统] 更新战争积分失败: ${e.message}")
            e.printStackTrace()
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