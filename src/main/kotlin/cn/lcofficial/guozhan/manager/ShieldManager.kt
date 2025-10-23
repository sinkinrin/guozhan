package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.config.Config
import cn.lcofficial.guozhan.data.Country
import cn.lcofficial.guozhan.data.Countries
import cn.lcofficial.guozhan.data.User
import cn.lcofficial.guozhan.data.Users
import cn.lcofficial.guozhan.economy.RegionalTaxSystem
import cn.lcofficial.guozhan.pluginLogger
import cn.lcofficial.guozhan.task.WarEventScheduler
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 护盾管理器
 * 管理国家护盾系统，包括限制条件检查、持续时间管理和可视化反馈
 * v1.3.13: 修复护盾状态持久化问题，现在使用数据库存储护盾状态
 */
object ShieldManager {

    // 🔧 v1.3.15: 修复护盾系统配置化问题 - 从配置文件读取参数

    /**
     * 获取护盾冷却时间（毫秒）
     */
    private fun getShieldCooldownTime(): Long {
        return Config.Shield.cooldownMinutes * 60 * 1000L
    }

    /**
     * 获取护盾最大持续时间（毫秒）
     */
    private fun getMaxShieldDuration(): Long {
        return Config.Shield.maxDurationHours * 60 * 60 * 1000L
    }

    /**
     * 获取护盾最小持续时间（毫秒）
     */
    private fun getMinShieldDuration(): Long {
        return Config.Shield.minDurationHours * 60 * 60 * 1000L
    }

    /**
     * 获取护盾成本倍数
     */
    private fun getShieldCostMultiplier(): Int {
        return Config.Shield.costPerHour
    }

    /**
     * 获取钻石到金币的转换率
     */
    private fun getDiamondToGoldRate(): Int {
        return Config.Shield.diamondToGoldRate
    }

    /**
     * 获取最大长宽比
     */
    private fun getMaxAspectRatio(): Double {
        return Config.Shield.maxAspectRatio
    }
    
    /**
     * 检查国家是否可以激活护盾
     * @param country 国家
     * @param hours 护盾持续小时数
     * @param gmMode GM模式，绕过时间限制
     * @return 检查结果，包含是否可以激活和错误信息
     */
    fun canActivateShield(country: Country, hours: Int, gmMode: Boolean = false): ShieldCheckResult {
        // 0. 检查护盾时长参数（v1.3.15修复：从配置读取限制）
        val minHours = Config.Shield.minDurationHours
        val maxHours = Config.Shield.maxDurationHours
        if (hours < minHours || hours > maxHours) {
            return ShieldCheckResult(false, "护盾时长必须在 $minHours-$maxHours 小时之间！")
        }

        // 1. 检查护盾是否已经激活
        if (isShieldActive(country)) {
            return ShieldCheckResult(false, "护盾已经激活中！")
        }

        // 2. 检查冷却时间
        val cooldownResult = checkCooldown(country)
        if (!cooldownResult.canActivate) {
            return cooldownResult
        }
        
        // 3. 检查成员数量限制
        val memberResult = checkMemberLimit(country)
        if (!memberResult.canActivate) {
            return memberResult
        }
        
        // 4. 检查领土长宽比
        val aspectRatioResult = checkAspectRatio(country)
        if (!aspectRatioResult.canActivate) {
            return aspectRatioResult
        }
        
        // 5. 检查王战时间段（GM模式跳过）
        if (!gmMode) {
            val warTimeResult = checkWarTime()
            if (!warTimeResult.canActivate) {
                return warTimeResult
            }
        }
        
        // 6. 检查资源是否足够
        val resourceResult = checkResources(country, hours)
        if (!resourceResult.canActivate) {
            return resourceResult
        }
        
        return ShieldCheckResult(true, "可以激活护盾")
    }
    
    /**
     * 激活国家护盾
     * 🔧 v1.3.47: 修复护盾激活线程阻塞问题 - 完全异步化，避免使用CompletableFuture.get()阻塞线程
     * @param country 国家
     * @param hours 护盾持续小时数
     * @param gmMode GM模式，绕过时间限制
     * @param callback 完成回调，传入是否成功激活
     */
    fun activateShield(country: Country, hours: Int, gmMode: Boolean = false, callback: (Boolean) -> Unit = {}) {
        val checkResult = canActivateShield(country, hours, gmMode)
        if (!checkResult.canActivate) {
            callback(false)
            return
        }

        // 计算护盾成本
        val cost = calculateShieldCost(country, hours)

        // 检查资源是否充足
        if (country.gold < cost) {
            callback(false)
            return
        }

        // 🔧 v1.3.47: 完全异步化，在GlobalRegionScheduler中执行状态修改

        cn.lcofficial.guozhan.util.run {
            try {
                // 再次检查资源（防止竞态条件）
                if (country.gold < cost) {
                    callback(false)
                    return@run
                }

                // 再次检查护盾状态（防止重复激活）
                if (isShieldActive(country)) {
                    callback(false)
                    return@run
                }

                // 扣除资源并激活护盾
                country.gold -= cost
                country.shield = true

                // v1.3.13修复：使用数据库持久化护盾状态
                val durationMs = hours * 60 * 60 * 1000L
                val currentTime = System.currentTimeMillis()
                val shieldEndTime = currentTime + durationMs
                country.shieldEndTime = shieldEndTime
                // 🔧 v1.3.41: 修复护盾冷却时间在激活时就已过期 - 冷却时间应该从护盾结束后开始计算
                country.shieldCooldownEnd = shieldEndTime + getShieldCooldownTime()

                country.save()

                // 广播护盾激活消息
                broadcastShieldActivation(country, hours, gmMode)

                val modeText = if (gmMode) "[GM模式] " else ""
                val cooldownHours = getShieldCooldownTime() / (60 * 60 * 1000)
                Guozhan.instance.logger.info("${modeText}国家 ${country.name} 激活了护盾，持续 $hours 小时，消耗 $cost 金币")
                Guozhan.instance.logger.info("${modeText}护盾将在 ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(shieldEndTime))} 结束")
                Guozhan.instance.logger.info("${modeText}冷却时间将在 ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(country.shieldCooldownEnd!!))} 结束（护盾结束后 $cooldownHours 小时）")

                // 🔧 v1.3.24: 触发地图更新
                Guozhan.instance.squaremapIntegration.triggerMapUpdate()

                callback(true)
            } catch (e: Exception) {
                Guozhan.instance.logger.severe("护盾激活过程中出错: ${e.message}")
                e.printStackTrace()
                callback(false)
            }
        }
    }
    
    /**
     * 检查护盾是否激活
     * @param country 国家
     * @return 是否激活
     * v1.3.13修复：使用数据库持久化的护盾状态
     * 🔧 v1.3.38修复：Folia线程安全 - 将护盾失效操作调度到GlobalRegionScheduler
     */
    fun isShieldActive(country: Country): Boolean {
        val endTime = country.shieldEndTime ?: return false
        val currentTime = System.currentTimeMillis()

        if (currentTime >= endTime) {
            // 🔧 v1.3.38修复：护盾已过期，使用Folia调度器安全地清理状态
            // 将deactivateShield调用包装在GlobalRegionScheduler中，确保线程安全
            cn.lcofficial.guozhan.util.run { _ ->
                try {
                    // 再次检查护盾状态，避免重复处理（防止竞争条件）
                    val currentEndTime = country.shieldEndTime
                    if (currentEndTime != null && System.currentTimeMillis() >= currentEndTime) {
                        deactivateShield(country)
                        Guozhan.instance.logger.fine("护盾过期自动失效：国家 ${country.name}")
                    }
                } catch (e: Exception) {
                    Guozhan.instance.logger.warning("护盾自动失效处理出错：国家 ${country.name} - ${e.message}")
                }
            }
            return false
        }

        return country.shield
    }
    
    /**
     * 获取护盾剩余时间
     * @param country 国家
     * @return 剩余时间（毫秒），如果护盾未激活返回0
     * v1.3.13修复：使用数据库持久化的护盾状态
     */
    fun getShieldRemainingTime(country: Country): Long {
        if (!isShieldActive(country)) return 0L

        val endTime = country.shieldEndTime ?: return 0L
        val currentTime = System.currentTimeMillis()
        val remaining = endTime - currentTime

        return if (remaining > 0) remaining else 0L
    }
    
    /**
     * 停用护盾
     * 🔧 v1.3.43: 修复护盾激活/停用在玩家命令线程中直接修改共享状态 - 使用GlobalRegionScheduler
     * 🔧 v1.3.48: 修复问题5 - 添加useScheduler参数，避免在已经在GlobalRegionScheduler中时额外跳转
     * @param country 国家
     * @param useScheduler 是否使用GlobalRegionScheduler（默认true，当已经在GlobalRegionScheduler中时设为false）
     * v1.3.13修复：清理数据库中的护盾状态
     */
    fun deactivateShield(country: Country, useScheduler: Boolean = true) {
        val deactivateAction = {
            try {
                country.shield = false
                country.shieldEndTime = null
                country.save()

                // 广播护盾失效消息
                broadcastShieldDeactivation(country)

                Guozhan.instance.logger.info("国家 ${country.name} 的护盾已失效")

                // 🔧 v1.3.24: 触发地图更新
                Guozhan.instance.squaremapIntegration.triggerMapUpdate()
            } catch (e: Exception) {
                Guozhan.instance.logger.severe("护盾停用过程中出错: ${e.message}")
                e.printStackTrace()
            }
        }

        if (useScheduler) {
            // 🔧 v1.3.43: 修复护盾停用在玩家命令线程中直接修改共享状态 - 在GlobalRegionScheduler中执行状态修改
            cn.lcofficial.guozhan.util.run { _ -> deactivateAction() }
        } else {
            // 🔧 v1.3.48: 修复问题5 - 直接执行，避免额外的调度器跳转
            deactivateAction()
        }
    }
    
    /**
     * 检查冷却时间
     * v1.3.13修复：使用数据库持久化的冷却状态
     */
    private fun checkCooldown(country: Country): ShieldCheckResult {
        val cooldownEnd = country.shieldCooldownEnd ?: 0L
        val currentTime = System.currentTimeMillis()

        if (currentTime < cooldownEnd) {
            val remainingTime = cooldownEnd - currentTime
            val remainingMinutes = remainingTime / (60 * 1000)
            return ShieldCheckResult(false, "护盾冷却中，还需等待 $remainingMinutes 分钟")
        }

        return ShieldCheckResult(true, "冷却时间检查通过")
    }
    
    /**
     * 检查成员数量限制
     */
    private fun checkMemberLimit(country: Country): ShieldCheckResult {
        val members = getCountryMembers(country)
        // 🔧 修复：从配置文件读取成员上限，而非硬编码
        val maxMembers = Config.Shield.maxMembers

        if (members.size > maxMembers) {
            return ShieldCheckResult(false, "国家成员数量超过限制（${members.size}/$maxMembers），无法激活护盾")
        }

        return ShieldCheckResult(true, "成员数量检查通过")
    }
    
    /**
     * 检查领土长宽比
     * 🔧 v1.3.45: 修复护盾长宽比检查忽略世界维度 - 按世界分区计算长宽比
     */
    private fun checkAspectRatio(country: Country): ShieldCheckResult {
        val territories = TerritoryManager.getTerritoriesByCountry(country)
        if (territories.isEmpty()) {
            return ShieldCheckResult(false, "国家没有领土，无法激活护盾")
        }

        // 🔧 v1.3.45: 按世界分组计算长宽比，避免多世界领土被错误拒绝
        val territoriesByWorld = territories.groupBy { it.world }
        val maxAspectRatio = getMaxAspectRatio()

        for ((worldName, worldTerritories) in territoriesByWorld) {
            if (worldTerritories.isEmpty()) continue

            // 计算该世界的领土边界
            var minX = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var minZ = Int.MAX_VALUE
            var maxZ = Int.MIN_VALUE

            worldTerritories.forEach { territory ->
                if (territory.x < minX) minX = territory.x
                if (territory.x > maxX) maxX = territory.x
                if (territory.z < minZ) minZ = territory.z
                if (territory.z > maxZ) maxZ = territory.z
            }

            val width = maxX - minX + 1
            val height = maxZ - minZ + 1
            val aspectRatio = Math.max(width, height).toDouble() / Math.min(width, height).toDouble()

            if (aspectRatio > maxAspectRatio) {
                return ShieldCheckResult(false, "世界 $worldName 中的领土长宽比过大（${String.format("%.1f", aspectRatio)}:1 > ${String.format("%.1f", maxAspectRatio)}:1），无法激活护盾")
            }

            cn.lcofficial.guozhan.Guozhan.instance.logger.fine("🔧 [护盾检查] 世界 $worldName 长宽比检查通过：${String.format("%.1f", aspectRatio)}:1 (${worldTerritories.size} 个领土)")
        }

        return ShieldCheckResult(true, "所有世界的领土长宽比检查通过")
    }
    
    /**
     * 检查王战时间段
     */
    private fun checkWarTime(): ShieldCheckResult {
        val warScheduler = Guozhan.instance.warScheduler
        
        if (warScheduler.isWarTime() || warScheduler.isPreparationTime()) {
            return ShieldCheckResult(false, "王战时间段内无法激活护盾")
        }
        
        return ShieldCheckResult(true, "王战时间检查通过")
    }
    
    /**
     * 检查资源是否足够
     */
    private fun checkResources(country: Country, hours: Int): ShieldCheckResult {
        val cost = calculateShieldCost(country, hours)
        
        if (country.gold < cost) {
            return ShieldCheckResult(false, "金币不足，需要 $cost 金币，当前只有 ${country.gold} 金币")
        }
        
        return ShieldCheckResult(true, "资源检查通过")
    }
    
    /**
     * 计算护盾成本（基于领土收入）
     * 🔧 v1.3.15: 修复护盾成本计算，符合规范要求
     */
    private fun calculateShieldCost(country: Country, hours: Int): Int {
        // 计算国家每小时的总收入
        val goldPerHour = RegionalTaxSystem.calculateTotalGoldTaxPerHour(country)
        val diamondPerHour = RegionalTaxSystem.calculateTotalDiamondTaxPerHour(country)

        // 从配置读取参数
        val costMultiplier = getShieldCostMultiplier() // 默认5小时收入
        val diamondToGoldRate = getDiamondToGoldRate() // 默认1钻石=10金币

        // 护盾成本 = 区域收入/小时 × 护盾小时数 × 成本倍数
        // 规范要求：每个护盾小时消耗五小时的区域收入
        val totalIncomePerHour = goldPerHour + (diamondPerHour * diamondToGoldRate)
        val cost = totalIncomePerHour * hours * costMultiplier

        // 最小成本100金币，最大成本50000金币（提高上限以适应高收入国家）
        val finalCost = cost.coerceIn(100.0, 50000.0).toInt()

        pluginLogger.info("[护盾成本] 国家 ${country.name} 护盾成本计算：" +
                "金币收入/小时=$goldPerHour, 钻石收入/小时=$diamondPerHour, " +
                "总收入/小时=$totalIncomePerHour, 护盾小时数=$hours, " +
                "成本倍数=$costMultiplier, 最终成本=$finalCost")

        return finalCost
    }
    
    /**
     * 广播护盾激活消息
     * 🔧 v1.3.40: 修复护盾通知Folia线程违规 - 使用EntityScheduler发送玩家消息
     */
    private fun broadcastShieldActivation(country: Country, hours: Int, gmMode: Boolean = false) {
        val modePrefix = if (gmMode) "§c[GM模式] " else ""
        val message = "${modePrefix}§6[护盾系统] §e国家 §f${country.name} §e激活了护盾，持续 §f$hours §e小时！"
        Bukkit.broadcastMessage(message)

        // 通知国家成员 - 使用线程安全的消息发送
        val members = getCountryMembers(country)
        members.forEach { user ->
            val player = user.player
            if (player != null && player.isOnline) {
                sendMessageSafely(player, "§a[护盾系统] §f您的国家护盾已激活，在护盾期间无法扩张领土但也不会被攻击！")
            }
        }
    }
    
    /**
     * 广播护盾失效消息
     * 🔧 v1.3.40: 修复护盾通知Folia线程违规 - 使用EntityScheduler发送玩家消息
     */
    private fun broadcastShieldDeactivation(country: Country) {
        val message = "§6[护盾系统] §c国家 §f${country.name} §c的护盾已失效！"
        Bukkit.broadcastMessage(message)

        // 通知国家成员 - 使用线程安全的消息发送
        val members = getCountryMembers(country)
        members.forEach { user ->
            val player = user.player
            if (player != null && player.isOnline) {
                sendMessageSafely(player, "§c[护盾系统] §f您的国家护盾已失效，现在可以正常进行领土扩张和战斗！")
            }
        }
    }
    
    /**
     * 格式化剩余时间
     */
    fun formatRemainingTime(remainingTimeMs: Long): String {
        val totalMinutes = remainingTimeMs / (60 * 1000)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        
        return when {
            hours > 0 && minutes > 0 -> "${hours}小时${minutes}分钟"
            hours > 0 -> "${hours}小时"
            minutes > 0 -> "${minutes}分钟"
            else -> "不到1分钟"
        }
    }
    
    /**
     * 定时检查护盾过期
     * v1.3.13修复：从数据库查询所有有护盾的国家，而不是依赖内存缓存
     * 🔧 v1.3.23: 修复数据竞争问题 - 在异步线程中只查询，在主线程中修改Country对象
     */
    fun checkExpiredShields() {
        val currentTime = System.currentTimeMillis()
        val startTime = System.currentTimeMillis()

        // 在异步线程执行数据库查询操作
        cn.lcofficial.guozhan.util.async { _ ->
            try {
                // 查询所有有护盾且护盾已过期的国家ID
                val expiredCountryIds = transaction {
                    Countries.selectAll()
                        .where { (Countries.shield eq true) and (Countries.shieldEndTime lessEq currentTime) }
                        .mapNotNull { row ->
                            try {
                                UUID.fromString(row[Countries.id].value)
                            } catch (e: IllegalArgumentException) {
                                Guozhan.instance.logger.warning("护盾过期检查：跳过无效的国家UUID: '${row[Countries.id].value}' - ${e.message}")
                                null
                            }
                        }
                }

                val duration = System.currentTimeMillis() - startTime
                if (expiredCountryIds.isNotEmpty()) {
                    Guozhan.instance.logger.info("护盾过期查询完成，找到 ${expiredCountryIds.size} 个过期国家，耗时 ${duration}ms")
                }

                // 🔧 v1.3.48: 修复问题5 - 将修改操作投递回GlobalRegionScheduler，避免额外的调度器跳转
                if (expiredCountryIds.isNotEmpty()) {
                    cn.lcofficial.guozhan.util.run { _ ->
                        var processedCount = 0
                        for (countryId in expiredCountryIds) {
                            val country = CountryManager.getCountry(countryId) ?: continue

                            // 🔧 v1.3.48: 修复问题5 - 直接在当前GlobalRegionScheduler上下文中执行状态更新，避免额外调度器跳转
                            try {
                                country.shield = false
                                country.shieldEndTime = null
                                country.save()

                                // 广播护盾失效消息
                                broadcastShieldDeactivation(country)

                                Guozhan.instance.logger.info("护盾过期检查：国家 ${country.name} 的护盾已自动失效")

                                // 🔧 v1.3.24: 触发地图更新
                                Guozhan.instance.squaremapIntegration.triggerMapUpdate()

                                processedCount++
                            } catch (e: Exception) {
                                Guozhan.instance.logger.severe("护盾过期处理失败：国家 ${country.name} - ${e.message}")
                                e.printStackTrace()
                            }
                        }

                        Guozhan.instance.logger.info("护盾过期处理完成，处理了 $processedCount 个国家")
                    }
                }

            } catch (e: Exception) {
                Guozhan.instance.logger.severe("护盾过期检查失败: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    /**
     * 获取国家成员列表
     * 🔧 v1.3.37: 修复低效成员查询 - 优先使用Country.members缓存，避免频繁数据库查询
     */
    private fun getCountryMembers(country: Country): List<User> {
        // 🔧 v1.3.37: 优先使用Country对象的members属性，该属性使用CountryManager的缓存
        // 这避免了每次护盾操作都进行数据库查询，显著提升性能
        return try {
            country.members
        } catch (e: Exception) {
            // 🔧 v1.3.48: 修复Medium问题6 - 护盾系统成员查询回退错误
            // 如果缓存访问失败，刷新缓存而不是使用错误的数据库查询
            Guozhan.instance.logger.warning("护盾系统：使用缓存获取成员失败，尝试刷新缓存: ${e.message}")
            try {
                CountryManager.refreshMemberCache(country.id)
                country.members
            } catch (refreshException: Exception) {
                Guozhan.instance.logger.severe("护盾系统：刷新成员缓存也失败，返回空列表: ${refreshException.message}")
                emptyList()
            }
        }
    }

    /**
     * 线程安全的消息发送工具方法
     * 🔧 v1.3.40: 修复护盾通知Folia线程违规 - 封装EntityScheduler消息发送
     */
    private fun sendMessageSafely(player: org.bukkit.entity.Player, message: String) {
        player.scheduler.run(Guozhan.instance, { _ ->
            player.sendMessage(message)
        }, null)
    }

    /**
     * 护盾检查结果
     */
    data class ShieldCheckResult(
        val canActivate: Boolean,
        val message: String
    )
}
