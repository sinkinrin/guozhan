package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.data.*
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.*

/**
 * 外交请求管理器
 * 🔧 v1.3.63: 实现外交系统双方确认机制
 */
object DiplomaticRequestManager {
    
    private val pluginLogger = Guozhan.instance.logger
    
    /**
     * 发起外交请求
     * @return 成功返回请求对象，失败返回null
     */
    fun createRequest(
        initiatorCountry: Country,
        targetCountry: Country,
        requestType: RelationType
    ): DiplomaticRequest? {
        // 检查是否有重复的待处理请求
        if (DiplomaticRequest.hasPendingRequest(
                initiatorCountry.id,
                targetCountry.id,
                requestType
            )) {
            pluginLogger.info("[外交请求] 已存在待处理的请求: ${initiatorCountry.name} -> ${targetCountry.name} ($requestType)")
            return null
        }
        
        // 创建请求
        val request = DiplomaticRequest.create(
            initiatorCountryId = initiatorCountry.id,
            targetCountryId = targetCountry.id,
            requestType = requestType,
            expirationHours = 24
        )
        
        pluginLogger.info("[外交请求] 创建请求: ${initiatorCountry.name} -> ${targetCountry.name} ($requestType), ID: ${request.id}")
        
        // 通知目标国家的在线成员
        notifyTargetCountry(request, initiatorCountry, targetCountry)
        
        return request
    }
    
    /**
     * 接受外交请求
     */
    fun acceptRequest(request: DiplomaticRequest, acceptingCountry: Country): Boolean {
        // 验证请求是否有效
        if (!request.isValid()) {
            pluginLogger.warning("[外交请求] 请求无效或已过期: ${request.id}")
            return false
        }
        
        // 验证接受方是否是目标国家
        if (request.targetCountryId != acceptingCountry.id) {
            pluginLogger.warning("[外交请求] 接受方不是目标国家: ${request.id}")
            return false
        }
        
        // 获取发起国家
        val initiatorCountry = CountryManager.getCountry(request.initiatorCountryId)
        if (initiatorCountry == null) {
            pluginLogger.warning("[外交请求] 发起国家不存在: ${request.initiatorCountryId}")
            return false
        }
        
        // 更新请求状态
        request.status = RequestStatus.ACCEPTED
        request.save()
        
        // 执行外交关系变更
        DiplomacyManager.updateRelation(initiatorCountry, acceptingCountry, request.requestType)
        
        pluginLogger.info("[外交请求] 请求已接受: ${initiatorCountry.name} <-> ${acceptingCountry.name} (${request.requestType})")
        
        // 通知双方国家成员
        notifyRequestAccepted(request, initiatorCountry, acceptingCountry)
        
        return true
    }
    
    /**
     * 拒绝外交请求
     */
    fun rejectRequest(request: DiplomaticRequest, rejectingCountry: Country): Boolean {
        // 验证请求是否有效
        if (!request.isValid()) {
            pluginLogger.warning("[外交请求] 请求无效或已过期: ${request.id}")
            return false
        }
        
        // 验证拒绝方是否是目标国家
        if (request.targetCountryId != rejectingCountry.id) {
            pluginLogger.warning("[外交请求] 拒绝方不是目标国家: ${request.id}")
            return false
        }
        
        // 获取发起国家
        val initiatorCountry = CountryManager.getCountry(request.initiatorCountryId)
        if (initiatorCountry == null) {
            pluginLogger.warning("[外交请求] 发起国家不存在: ${request.initiatorCountryId}")
            return false
        }
        
        // 更新请求状态
        request.status = RequestStatus.REJECTED
        request.save()
        
        pluginLogger.info("[外交请求] 请求已拒绝: ${initiatorCountry.name} -> ${rejectingCountry.name} (${request.requestType})")
        
        // 通知发起国家成员
        notifyRequestRejected(request, initiatorCountry, rejectingCountry)
        
        return true
    }
    
    /**
     * 获取国家收到的所有待处理请求
     */
    fun getReceivedRequests(country: Country): List<DiplomaticRequest> {
        return DiplomaticRequest.loadPendingForTarget(country.id)
    }
    
    /**
     * 获取国家发起的所有待处理请求
     */
    fun getSentRequests(country: Country): List<DiplomaticRequest> {
        return DiplomaticRequest.loadPendingFromInitiator(country.id)
    }
    
    /**
     * 通知目标国家收到新请求
     */
    private fun notifyTargetCountry(
        request: DiplomaticRequest,
        initiatorCountry: Country,
        targetCountry: Country
    ) {
        val relationName = getRelationName(request.requestType)
        val message = "§6[外交请求] §f${initiatorCountry.name} §6向你的国家发起了§e${relationName}§6请求！"
        val hint = "§7使用 /u diplomacy requests 查看详情，/u diplomacy accept ${initiatorCountry.name} 接受"
        
        // 通知目标国家的所有在线成员
        Bukkit.getOnlinePlayers().forEach { player ->
            val user = UserManager.getUser(player.uniqueId)
            if (user?.country?.id == targetCountry.id) {
                // 使用EntityScheduler确保线程安全
                player.scheduler.run(Guozhan.instance, { _ ->
                    player.sendMessage(message)
                    player.sendMessage(hint)
                }, null)
            }
        }
    }
    
    /**
     * 通知请求被接受
     */
    private fun notifyRequestAccepted(
        request: DiplomaticRequest,
        initiatorCountry: Country,
        targetCountry: Country
    ) {
        val relationName = getRelationName(request.requestType)
        val message = "§a[外交请求] §f${targetCountry.name} §a接受了你的§e${relationName}§a请求！"
        
        // 通知发起国家的所有在线成员
        Bukkit.getOnlinePlayers().forEach { player ->
            val user = UserManager.getUser(player.uniqueId)
            if (user?.country?.id == initiatorCountry.id) {
                player.scheduler.run(Guozhan.instance, { _ ->
                    player.sendMessage(message)
                }, null)
            }
        }
    }
    
    /**
     * 通知请求被拒绝
     */
    private fun notifyRequestRejected(
        request: DiplomaticRequest,
        initiatorCountry: Country,
        targetCountry: Country
    ) {
        val relationName = getRelationName(request.requestType)
        val message = "§c[外交请求] §f${targetCountry.name} §c拒绝了你的§e${relationName}§c请求。"
        
        // 通知发起国家的所有在线成员
        Bukkit.getOnlinePlayers().forEach { player ->
            val user = UserManager.getUser(player.uniqueId)
            if (user?.country?.id == initiatorCountry.id) {
                player.scheduler.run(Guozhan.instance, { _ ->
                    player.sendMessage(message)
                }, null)
            }
        }
    }
    
    /**
     * 获取关系类型的中文名称
     */
    private fun getRelationName(relationType: RelationType): String {
        return when (relationType) {
            RelationType.NEUTRAL -> "中立"
            RelationType.FRIENDLY -> "友好"
            RelationType.ALLIED -> "结盟"
            RelationType.HOSTILE -> "敌对"
            RelationType.WAR -> "宣战"
        }
    }
    
    /**
     * 清理过期的请求
     */
    fun cleanupExpiredRequests(): Int {
        val expiredCount = DiplomaticRequest.cleanupExpiredRequests()
        if (expiredCount > 0) {
            pluginLogger.info("[外交请求] 清理了 $expiredCount 个过期请求")
        }
        return expiredCount
    }
    
    /**
     * 检查并通知即将过期的请求（剩余1小时）
     */
    fun checkExpiringRequests() {
        val allPending = DiplomaticRequest.loadAllPending()
        val oneHour = 60 * 60 * 1000L
        
        allPending.forEach { request ->
            val remaining = request.getRemainingTime()
            if (remaining in 1..oneHour) {
                // 获取双方国家
                val initiatorCountry = CountryManager.getCountry(request.initiatorCountryId)
                val targetCountry = CountryManager.getCountry(request.targetCountryId)
                
                if (initiatorCountry != null && targetCountry != null) {
                    val relationName = getRelationName(request.requestType)
                    val hours = request.getRemainingHours()
                    val message = "§e[外交请求] §f${initiatorCountry.name} §e与 §f${targetCountry.name} §e的§f${relationName}§e请求即将在 ${hours} 小时后过期！"
                    
                    // 通知双方国家的在线成员
                    Bukkit.getOnlinePlayers().forEach { player ->
                        val user = UserManager.getUser(player.uniqueId)
                        if (user?.country?.id == initiatorCountry.id || user?.country?.id == targetCountry.id) {
                            player.scheduler.run(Guozhan.instance, { _ ->
                                player.sendMessage(message)
                            }, null)
                        }
                    }
                }
            }
        }
    }
}

