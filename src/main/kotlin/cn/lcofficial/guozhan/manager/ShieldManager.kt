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
     * @param country 国家
     * @param hours 护盾持续小时数
     * @param gmMode GM模式，绕过时间限制
     * @return 是否成功激活
     */
    fun activateShield(country: Country, hours: Int, gmMode: Boolean = false): Boolean {
        val checkResult = canActivateShield(country, hours, gmMode)
        if (!checkResult.canActivate) {
            return false
        }
        
        // 计算护盾成本
        val cost = calculateShieldCost(country, hours)
        
        // 扣除资源
        if (country.gold < cost) {
            return false
        }
        
        country.gold -= cost
        country.shield = true

        // v1.3.13修复：使用数据库持久化护盾状态
        val durationMs = hours * 60 * 60 * 1000L
        val currentTime = System.currentTimeMillis()
        country.shieldEndTime = currentTime + durationMs
        country.shieldCooldownEnd = currentTime + getShieldCooldownTime()

        country.save()
        
        // 广播护盾激活消息
        broadcastShieldActivation(country, hours, gmMode)

        val modeText = if (gmMode) "[GM模式] " else ""
        Guozhan.instance.logger.info("${modeText}国家 ${country.name} 激活了护盾，持续 $hours 小时，消耗 $cost 金币")
        
        return true
    }
    
    /**
     * 检查护盾是否激活
     * @param country 国家
     * @return 是否激活
     * v1.3.13修复：使用数据库持久化的护盾状态
     */
    fun isShieldActive(country: Country): Boolean {
        val endTime = country.shieldEndTime ?: return false
        val currentTime = System.currentTimeMillis()

        if (currentTime >= endTime) {
            // 护盾已过期，清理状态
            deactivateShield(country)
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
     * @param country 国家
     * v1.3.13修复：清理数据库中的护盾状态
     */
    fun deactivateShield(country: Country) {
        country.shield = false
        country.shieldEndTime = null
        country.save()

        // 广播护盾失效消息
        broadcastShieldDeactivation(country)

        Guozhan.instance.logger.info("国家 ${country.name} 的护盾已失效")
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
     */
    private fun checkAspectRatio(country: Country): ShieldCheckResult {
        val territories = TerritoryManager.getTerritoriesByCountry(country)
        if (territories.isEmpty()) {
            return ShieldCheckResult(false, "国家没有领土，无法激活护盾")
        }
        
        // 计算领土边界
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minZ = Int.MAX_VALUE
        var maxZ = Int.MIN_VALUE
        
        territories.forEach { territory ->
            if (territory.x < minX) minX = territory.x
            if (territory.x > maxX) maxX = territory.x
            if (territory.z < minZ) minZ = territory.z
            if (territory.z > maxZ) maxZ = territory.z
        }
        
        val width = maxX - minX + 1
        val height = maxZ - minZ + 1
        val aspectRatio = Math.max(width, height).toDouble() / Math.min(width, height).toDouble()
        val maxAspectRatio = getMaxAspectRatio()

        if (aspectRatio > maxAspectRatio) {
            return ShieldCheckResult(false, "领土长宽比过大（${String.format("%.1f", aspectRatio)}:1 > ${String.format("%.1f", maxAspectRatio)}:1），无法激活护盾")
        }
        
        return ShieldCheckResult(true, "领土长宽比检查通过")
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
     */
    private fun broadcastShieldActivation(country: Country, hours: Int, gmMode: Boolean = false) {
        val modePrefix = if (gmMode) "§c[GM模式] " else ""
        val message = "${modePrefix}§6[护盾系统] §e国家 §f${country.name} §e激活了护盾，持续 §f$hours §e小时！"
        Bukkit.broadcastMessage(message)

        // 通知国家成员
        val members = getCountryMembers(country)
        members.forEach { user ->
            val player = user.player
            player?.sendMessage("§a[护盾系统] §f您的国家护盾已激活，在护盾期间无法扩张领土但也不会被攻击！")
        }
    }
    
    /**
     * 广播护盾失效消息
     */
    private fun broadcastShieldDeactivation(country: Country) {
        val message = "§6[护盾系统] §c国家 §f${country.name} §c的护盾已失效！"
        Bukkit.broadcastMessage(message)

        // 通知国家成员
        val members = getCountryMembers(country)
        members.forEach { user ->
            val player = user.player
            player?.sendMessage("§c[护盾系统] §f您的国家护盾已失效，现在可以正常进行领土扩张和战斗！")
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
     */
    fun checkExpiredShields() {
        val currentTime = System.currentTimeMillis()

        try {
            transaction {
                // 查询所有有护盾且护盾已过期的国家
                Countries.selectAll()
                    .where { (Countries.shield eq true) and (Countries.shieldEndTime lessEq currentTime) }
                    .forEach { row ->
                        try {
                            val countryId = UUID.fromString(row[Countries.id].value)
                            val country = CountryManager.getCountry(countryId)
                            if (country != null) {
                                deactivateShield(country)
                                Guozhan.instance.logger.info("护盾过期检查：国家 ${country.name} 的护盾已自动失效")
                            }
                        } catch (e: IllegalArgumentException) {
                            Guozhan.instance.logger.warning("护盾过期检查：跳过无效的国家UUID: '${row[Countries.id].value}' - ${e.message}")
                        }
                    }
            }
        } catch (e: Exception) {
            Guozhan.instance.logger.severe("护盾过期检查失败: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 获取国家成员列表
     */
    private fun getCountryMembers(country: Country): List<User> = transaction {
        Users.selectAll().where { Users.countryId eq country.id.toString() }.mapNotNull { row ->
            try {
                User(
                    uniqueId = UUID.fromString(row[Users.id].value),
                    name = row[Users.name],
                    countryId = country.id,
                    rank = row[Users.rank],
                    title = row[Users.title],
                    profession = row[Users.profession],
                    professionLevel = row[Users.professionLevel]
                )
            } catch (e: IllegalArgumentException) {
                Guozhan.instance.logger.warning("护盾系统：跳过无效的用户UUID: '${row[Users.id].value}' - ${e.message}")
                null
            }
        }
    }

    /**
     * 护盾检查结果
     */
    data class ShieldCheckResult(
        val canActivate: Boolean,
        val message: String
    )
}
