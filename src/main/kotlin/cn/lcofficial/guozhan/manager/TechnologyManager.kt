package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.config.TechnologyConfig
import cn.lcofficial.guozhan.data.*
import cn.lcofficial.guozhan.economy.RegionalTaxSystem
import cn.lcofficial.guozhan.util.runRepeat
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * 科技系统管理器
 * 负责科技数据管理、研究状态跟踪、效果应用等核心功能
 */
object TechnologyManager {
    
    // 国家科技状态缓存 (国家ID -> (科技ID -> 科技状态))
    private val countryTechnologies = ConcurrentHashMap<UUID, ConcurrentHashMap<String, CountryTechnology>>()
    
    // 正在研究的科技 (国家ID -> 科技ID列表)
    private val researchingTechnologies = ConcurrentHashMap<UUID, MutableSet<String>>()
    
    /**
     * 初始化科技管理器
     */
    fun initialize() {
        Guozhan.instance.logger.info("正在初始化科技管理器...")
        
        // 创建数据库表
        transaction {
            SchemaUtils.createMissingTablesAndColumns(Technologies, CountryTechnologies)
        }
        
        // 加载所有国家的科技状态
        loadAllCountryTechnologies()
        
        // 启动研究完成检查任务
        startResearchCompletionTask()
        
        Guozhan.instance.logger.info("科技管理器初始化完成")
    }
    
    /**
     * 加载所有国家的科技状态
     */
    private fun loadAllCountryTechnologies() = transaction {
        val results = CountryTechnologies.selectAll()
        
        results.forEach { row ->
            val countryId = UUID.fromString(row[CountryTechnologies.countryId])
            val technologyId = row[CountryTechnologies.technologyId]
            val level = row[CountryTechnologies.level]
            val researchStartTime = row[CountryTechnologies.researchStartTime]
            val researchEndTime = row[CountryTechnologies.researchEndTime]
            val isResearching = row[CountryTechnologies.isResearching]
            
            val countryTech = CountryTechnology(
                countryId = countryId,
                technologyId = technologyId,
                level = level,
                researchStartTime = researchStartTime,
                researchEndTime = researchEndTime,
                isResearching = isResearching
            )
            
            // 添加到缓存
            countryTechnologies.computeIfAbsent(countryId) { ConcurrentHashMap() }[technologyId] = countryTech
            
            // 如果正在研究，添加到研究列表
            if (isResearching) {
                researchingTechnologies.computeIfAbsent(countryId) { mutableSetOf() }.add(technologyId)
            }
        }
        
        Guozhan.instance.logger.info("已加载 ${results.count()} 条科技研究记录")
    }
    
    /**
     * 获取所有可用科技
     */
    fun getAllTechnologies(): List<Technology> {
        return TechnologyConfig.getAllTechnologies().values.filter { it.enabled }
    }
    
    /**
     * 获取指定科技
     */
    fun getTechnology(id: String): Technology? {
        return TechnologyConfig.getTechnology(id)?.takeIf { it.enabled }
    }
    
    /**
     * 获取国家的科技等级
     * @param country 国家
     * @param technologyId 科技ID
     * @return 科技等级，0表示未研究
     */
    fun getCountryTechLevel(country: Country, technologyId: String): Int {
        return countryTechnologies[country.id]?.get(technologyId)?.level ?: 0
    }
    
    /**
     * 获取国家的科技状态
     * @param country 国家
     * @param technologyId 科技ID
     * @return 科技状态，如果不存在返回null
     */
    fun getCountryTechnology(country: Country, technologyId: String): CountryTechnology? {
        return countryTechnologies[country.id]?.get(technologyId)
    }
    
    /**
     * 检查国家是否可以研究指定科技
     * @param country 国家
     * @param technologyId 科技ID
     * @return 是否可以研究
     */
    fun canResearchTechnology(country: Country, technologyId: String): Boolean {
        val technology = getTechnology(technologyId) ?: return false
        val currentLevel = getCountryTechLevel(country, technologyId)
        
        // 检查是否已达到最高等级
        if (currentLevel >= technology.maxLevel) return false
        
        // 检查是否正在研究
        if (isResearching(country, technologyId)) return false
        
        // 检查是否达到最大同时研究数量
        val currentResearching = researchingTechnologies[country.id]?.size ?: 0
        if (currentResearching >= TechnologyConfig.Settings.maxConcurrentResearch) return false
        
        // 检查前置科技
        if (!checkPrerequisites(country, technology)) return false
        
        // 检查资源
        val targetLevel = currentLevel + 1
        if (!checkResources(country, technology, targetLevel)) return false
        
        return true
    }
    
    /**
     * 检查前置科技条件
     */
    private fun checkPrerequisites(country: Country, technology: Technology): Boolean {
        return technology.prerequisites.all { prereqId ->
            val prereqLevel = getCountryTechLevel(country, prereqId)
            prereqLevel > 0 // 前置科技至少要有1级
        }
    }
    
    /**
     * 检查资源是否足够
     * 修复：合并资源检查逻辑，确保总成本检查的原子性，防止金币变为负数
     */
    private fun checkResources(country: Country, technology: Technology, level: Int): Boolean {
        val cost = technology.getCost(level) ?: return false

        // 检查钻石
        if (country.diamond < cost.diamond) return false

        // 计算总金币成本（基础成本 + 领土收入成本）
        var totalGoldCost = cost.gold
        if (cost.territoryIncome > 0) {
            val hourlyIncome = RegionalTaxSystem.calculateTotalGoldTaxPerHour(country)
            // 修复：使用向上取整确保即使小额收入也会被正确计算
            val additionalCost = ceil(cost.territoryIncome * hourlyIncome).toInt()
            totalGoldCost += additionalCost
        }

        // 一次性检查总金币成本，确保不会导致负数
        if (country.gold < totalGoldCost) return false

        return true
    }
    
    /**
     * 开始研究科技
     * @param country 国家
     * @param technologyId 科技ID
     * @return 是否成功开始研究
     */
    fun startResearch(country: Country, technologyId: String): Boolean {
        val technology = getTechnology(technologyId) ?: return false
        
        if (!canResearchTechnology(country, technologyId)) return false
        
        val currentLevel = getCountryTechLevel(country, technologyId)
        val targetLevel = currentLevel + 1
        val cost = technology.getCost(targetLevel) ?: return false
        
        // 修复：计算总成本并一次性扣除，与checkResources()逻辑保持一致
        // 扣除钻石
        country.diamond -= cost.diamond

        // 计算并扣除总金币成本（基础成本 + 领土收入成本）
        var totalGoldCost = cost.gold
        if (cost.territoryIncome > 0) {
            val hourlyIncome = RegionalTaxSystem.calculateTotalGoldTaxPerHour(country)
            // 修复：使用向上取整确保即使小额收入也会被正确计算
            val additionalCost = ceil(cost.territoryIncome * hourlyIncome).toInt()
            totalGoldCost += additionalCost
        }
        country.gold -= totalGoldCost
        
        country.save()
        
        // 使用配置的研究时间
        val configuredDuration = cn.lcofficial.guozhan.config.Config.Technology.researchDuration
        val researchTime = if (configuredDuration > 0) {
            configuredDuration * 1000L // 转换为毫秒
        } else {
            0L // 瞬间完成
        }
        val startTime = System.currentTimeMillis()
        val endTime = startTime + researchTime
        
        // 创建或更新科技状态
        val countryTech = CountryTechnology(
            countryId = country.id,
            technologyId = technologyId,
            level = currentLevel,
            researchStartTime = startTime,
            researchEndTime = endTime,
            isResearching = true
        )
        
        // 更新缓存
        countryTechnologies.computeIfAbsent(country.id) { ConcurrentHashMap() }[technologyId] = countryTech
        researchingTechnologies.computeIfAbsent(country.id) { mutableSetOf() }.add(technologyId)
        
        // 保存到数据库
        transaction {
            CountryTechnologies.replace {
                it[CountryTechnologies.countryId] = country.id.toString()
                it[CountryTechnologies.technologyId] = technologyId
                it[CountryTechnologies.level] = currentLevel
                it[CountryTechnologies.researchStartTime] = startTime
                it[CountryTechnologies.researchEndTime] = endTime
                it[CountryTechnologies.isResearching] = true
            }
        }
        
        Guozhan.instance.logger.info("国家 ${country.name} 开始研究科技 ${technology.name} 等级 $targetLevel")

        // 通知国家成员研究开始
        notifyResearchStart(country, technology, targetLevel, researchTime)

        // 如果研究时间大于0，启动进度通知
        if (researchTime > 0 && cn.lcofficial.guozhan.config.Config.Technology.enableProgressNotifications) {
            startProgressNotifications(country, technology, targetLevel, startTime, endTime)
        }

        return true
    }
    
    /**
     * 完成科技研究
     * @param country 国家
     * @param technologyId 科技ID
     * @return 是否成功完成研究
     */
    fun completeResearch(country: Country, technologyId: String): Boolean {
        val countryTech = getCountryTechnology(country, technologyId) ?: return false
        
        if (!countryTech.isResearching) return false
        
        val newLevel = countryTech.level + 1
        
        // 更新科技状态
        val updatedTech = countryTech.copy(
            level = newLevel,
            researchStartTime = null,
            researchEndTime = null,
            isResearching = false
        )
        
        // 更新缓存
        countryTechnologies[country.id]?.set(technologyId, updatedTech)
        researchingTechnologies[country.id]?.remove(technologyId)
        
        // 更新数据库
        transaction {
            CountryTechnologies.update({
                (CountryTechnologies.countryId eq country.id.toString()) and
                (CountryTechnologies.technologyId eq technologyId)
            }) {
                it[level] = newLevel
                it[researchStartTime] = null
                it[researchEndTime] = null
                it[isResearching] = false
            }
        }
        
        val technology = getTechnology(technologyId)
        Guozhan.instance.logger.info("国家 ${country.name} 完成了科技 ${technology?.name ?: technologyId} 等级 $newLevel 的研究")

        // 通知所有在线的国家成员
        notifyResearchCompletion(country, technology, newLevel)

        // 应用科技效果
        applyTechnologyEffects(country)

        return true
    }
    
    /**
     * 检查科技是否正在研究中
     */
    fun isResearching(country: Country, technologyId: String): Boolean {
        return researchingTechnologies[country.id]?.contains(technologyId) == true
    }
    
    /**
     * 获取国家正在研究的科技列表
     */
    fun getResearchingTechnologies(country: Country): List<String> {
        return researchingTechnologies[country.id]?.toList() ?: emptyList()
    }
    
    /**
     * 通知科技研究开始
     * @param country 国家
     * @param technology 开始研究的科技
     * @param targetLevel 目标等级
     * @param researchTime 研究时间（毫秒）
     */
    private fun notifyResearchStart(country: Country, technology: Technology, targetLevel: Int, researchTime: Long) {
        org.bukkit.Bukkit.getGlobalRegionScheduler().execute(Guozhan.instance) {
            country.members.forEach { member ->
                val player = org.bukkit.Bukkit.getPlayer(member.uniqueId)
                if (player != null && player.isOnline) {
                    with(cn.lcofficial.guozhan.config.Message) {
                        if (researchTime == 0L) {
                            // 瞬间完成
                            player.sendSuccess("科技研究已开始：${technology.name} 等级 $targetLevel")
                            player.sendInfo("研究将瞬间完成")
                        } else {
                            // 需要时间
                            val hours = researchTime / (1000 * 60 * 60)
                            val minutes = (researchTime % (1000 * 60 * 60)) / (1000 * 60)
                            val timeString = when {
                                hours > 0 && minutes > 0 -> "${hours}小时${minutes}分钟"
                                hours > 0 -> "${hours}小时"
                                minutes > 0 -> "${minutes}分钟"
                                else -> "不到1分钟"
                            }
                            player.sendSuccess("科技研究已开始：${technology.name} 等级 $targetLevel")
                            player.sendInfo("预计完成时间：$timeString")
                            if (cn.lcofficial.guozhan.config.Config.Technology.enableProgressNotifications) {
                                val interval = cn.lcofficial.guozhan.config.Config.Technology.progressNotificationInterval
                                val intervalHours = interval / 3600
                                player.sendInfo("将每${intervalHours}小时发送进度通知")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 启动进度通知
     * @param country 国家
     * @param technology 研究的科技
     * @param targetLevel 目标等级
     * @param startTime 开始时间
     * @param endTime 结束时间
     */
    private fun startProgressNotifications(country: Country, technology: Technology, targetLevel: Int, startTime: Long, endTime: Long) {
        val interval = cn.lcofficial.guozhan.config.Config.Technology.progressNotificationInterval * 1000L // 转换为毫秒
        val totalDuration = endTime - startTime

        // 计算需要发送多少次通知
        val notificationCount = (totalDuration / interval).toInt()
        if (notificationCount <= 0) return

        // 使用Folia调度器定期发送进度通知
        for (i in 1..notificationCount) {
            val delay = (interval * i) / 50L // 转换为tick (1秒 = 20tick)

            org.bukkit.Bukkit.getGlobalRegionScheduler().runDelayed(Guozhan.instance, { _ ->
                val currentTime = System.currentTimeMillis()
                if (currentTime < endTime) {
                    val elapsed = currentTime - startTime
                    val remaining = endTime - currentTime
                    val progress = (elapsed.toDouble() / totalDuration * 100).toInt()

                    // 通知国家君主或所有在线成员
                    country.members.forEach { member ->
                        val player = org.bukkit.Bukkit.getPlayer(member.uniqueId)
                        if (player != null && player.isOnline) {
                            with(cn.lcofficial.guozhan.config.Message) {
                                player.sendInfo("§6=== 科技研究进度 ===")
                                player.sendInfo("§f科技：${technology.name} 等级 $targetLevel")
                                player.sendInfo("§f进度：${progress}% 完成")

                                val remainingHours = remaining / (1000 * 60 * 60)
                                val remainingMinutes = (remaining % (1000 * 60 * 60)) / (1000 * 60)
                                val remainingTimeString = when {
                                    remainingHours > 0 && remainingMinutes > 0 -> "${remainingHours}小时${remainingMinutes}分钟"
                                    remainingHours > 0 -> "${remainingHours}小时"
                                    remainingMinutes > 0 -> "${remainingMinutes}分钟"
                                    else -> "不到1分钟"
                                }
                                player.sendInfo("§f剩余时间：$remainingTimeString")
                            }
                        }
                    }
                }
            }, delay)
        }
    }

    /**
     * 通知科技研究完成
     * @param country 国家
     * @param technology 完成的科技
     * @param newLevel 新等级
     */
    private fun notifyResearchCompletion(country: Country, technology: Technology?, newLevel: Int) {
        val techName = technology?.name ?: "未知科技"

        // 使用GlobalRegionScheduler确保在主线程执行消息发送
        org.bukkit.Bukkit.getGlobalRegionScheduler().execute(Guozhan.instance) {
            // 通知所有在线的国家成员
            country.members.forEach { member ->
                val player = org.bukkit.Bukkit.getPlayer(member.uniqueId)
                if (player != null && player.isOnline) {
                    // 使用Message.kt扩展函数发送消息
                    with(cn.lcofficial.guozhan.config.Message) {
                        player.sendSuccess("科技研究完成！$techName 等级 $newLevel")

                        // 显示新获得的效果
                        val effects = technology?.getEffects(newLevel) ?: emptyList()
                        if (effects.isNotEmpty()) {
                            player.sendInfo("新获得的科技效果:")
                            effects.forEach { effect ->
                                player.sendMessage("§a  + ${effect.getDescription()}")
                            }
                        }

                        player.sendInfo("科技效果已自动应用到所有国家成员")
                    }
                }
            }

            // 如果有离线成员，记录到日志供后续处理
            val offlineMembers = country.members.filter { member ->
                val player = org.bukkit.Bukkit.getPlayer(member.uniqueId)
                player == null || !player.isOnline
            }

            if (offlineMembers.isNotEmpty()) {
                Guozhan.instance.logger.info("科技完成通知：${offlineMembers.size}名离线成员将在下次登录时收到通知")
            }
        }
    }

    /**
     * 应用科技效果（由TechEffectManager处理）
     */
    fun applyTechnologyEffects(country: Country) {
        // 刷新国家的科技效果缓存
        TechEffectManager.refreshCountryEffectsCache(country)

        // 为该国家的所有在线成员更新效果
        org.bukkit.Bukkit.getOnlinePlayers().forEach { player ->
            val user = UserManager.getUser(player.uniqueId)
            if (user?.country?.id == country.id) {
                TechEffectManager.updatePlayerEffects(player)
            }
        }

        Guozhan.instance.logger.info("已为国家 ${country.name} 的在线成员应用科技效果")
    }
    
    /**
     * 启动研究完成检查任务
     */
    private fun startResearchCompletionTask() {
        if (!TechnologyConfig.Settings.autoCompleteResearch) return
        
        // 使用Folia调度器每分钟检查一次研究完成情况
        runRepeat(20L * 60L, 20L * 60L) { task ->
            try {
                checkResearchCompletion()
            } catch (e: Exception) {
                Guozhan.instance.logger.severe("检查科技研究完成状态时出错: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    /**
     * 检查研究完成情况
     */
    private fun checkResearchCompletion() {
        val completedResearch = mutableListOf<Pair<UUID, String>>()
        
        researchingTechnologies.forEach { (countryId, techIds) ->
            techIds.forEach { techId ->
                val countryTech = countryTechnologies[countryId]?.get(techId)
                if (countryTech?.isResearchCompleted() == true) {
                    completedResearch.add(countryId to techId)
                }
            }
        }
        
        // 完成已完成的研究
        completedResearch.forEach { (countryId, techId) ->
            val country = CountryManager.getCountry(countryId)
            if (country != null) {
                completeResearch(country, techId)
            }
        }
        
        if (completedResearch.isNotEmpty()) {
            Guozhan.instance.logger.info("自动完成了 ${completedResearch.size} 项科技研究")
        }
    }
}
