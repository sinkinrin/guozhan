package cn.lcofficial.guozhan.data

import cn.lcofficial.guozhan.manager.DataManager
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

/**
 * 外交请求表
 * 🔧 v1.3.63: 实现外交系统双方确认机制
 */
object DiplomaticRequests : Table("gz_diplomatic_requests") {
    val id = uuid("id")
    val initiatorCountryId = uuid("initiator_country_id")
    val targetCountryId = uuid("target_country_id")
    val requestType = enumeration<RelationType>("request_type")
    val status = enumeration<RequestStatus>("status")
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    
    override val primaryKey = PrimaryKey(id)
}

/**
 * 外交请求状态枚举
 */
enum class RequestStatus {
    PENDING,    // 待确认
    ACCEPTED,   // 已接受
    REJECTED,   // 已拒绝
    EXPIRED     // 已过期
}

/**
 * 外交请求实体类
 */
class DiplomaticRequest(
    val id: UUID,
    val initiatorCountryId: UUID,
    val targetCountryId: UUID,
    val requestType: RelationType,
    var status: RequestStatus,
    val createdAt: Long,
    val expiresAt: Long
) {
    
    /**
     * 构造函数，从数据库行创建
     */
    constructor(row: ResultRow) : this(
        id = row[DiplomaticRequests.id],
        initiatorCountryId = row[DiplomaticRequests.initiatorCountryId],
        targetCountryId = row[DiplomaticRequests.targetCountryId],
        requestType = row[DiplomaticRequests.requestType],
        status = row[DiplomaticRequests.status],
        createdAt = row[DiplomaticRequests.createdAt],
        expiresAt = row[DiplomaticRequests.expiresAt]
    )
    
    /**
     * 保存外交请求到数据库
     */
    fun save() {
        transaction {
            DiplomaticRequests.update({ DiplomaticRequests.id eq this@DiplomaticRequest.id }) {
                it[status] = this@DiplomaticRequest.status
            }
        }
    }
    
    /**
     * 检查请求是否已过期
     */
    fun isExpired(): Boolean {
        return System.currentTimeMillis() > expiresAt
    }
    
    /**
     * 检查请求是否有效（未过期且状态为待确认）
     */
    fun isValid(): Boolean {
        return status == RequestStatus.PENDING && !isExpired()
    }
    
    /**
     * 获取剩余有效时间（毫秒）
     */
    fun getRemainingTime(): Long {
        return maxOf(0L, expiresAt - System.currentTimeMillis())
    }
    
    /**
     * 获取剩余有效时间（小时）
     */
    fun getRemainingHours(): Long {
        return getRemainingTime() / (1000 * 60 * 60)
    }
    
    /**
     * 删除请求
     */
    fun delete() {
        transaction {
            DiplomaticRequests.deleteWhere { DiplomaticRequests.id eq this@DiplomaticRequest.id }
        }
    }
    
    companion object {
        /**
         * 创建新的外交请求
         */
        fun create(
            initiatorCountryId: UUID,
            targetCountryId: UUID,
            requestType: RelationType,
            expirationHours: Long = 24
        ): DiplomaticRequest {
            val id = UUID.randomUUID()
            val now = System.currentTimeMillis()
            val expiresAt = now + (expirationHours * 60 * 60 * 1000)
            
            transaction {
                DiplomaticRequests.insert {
                    it[DiplomaticRequests.id] = id
                    it[DiplomaticRequests.initiatorCountryId] = initiatorCountryId
                    it[DiplomaticRequests.targetCountryId] = targetCountryId
                    it[DiplomaticRequests.requestType] = requestType
                    it[DiplomaticRequests.status] = RequestStatus.PENDING
                    it[DiplomaticRequests.createdAt] = now
                    it[DiplomaticRequests.expiresAt] = expiresAt
                }
            }
            
            return DiplomaticRequest(
                id = id,
                initiatorCountryId = initiatorCountryId,
                targetCountryId = targetCountryId,
                requestType = requestType,
                status = RequestStatus.PENDING,
                createdAt = now,
                expiresAt = expiresAt
            )
        }
        
        /**
         * 根据ID加载请求
         */
        fun load(id: UUID): DiplomaticRequest? {
            return transaction {
                DiplomaticRequests.selectAll()
                    .where { DiplomaticRequests.id eq id }
                    .map { DiplomaticRequest(it) }
                    .firstOrNull()
            }
        }
        
        /**
         * 加载所有待处理的请求
         */
        fun loadAllPending(): List<DiplomaticRequest> {
            return transaction {
                DiplomaticRequests.selectAll()
                    .where { DiplomaticRequests.status eq RequestStatus.PENDING }
                    .map { DiplomaticRequest(it) }
            }
        }
        
        /**
         * 加载指定国家收到的所有待处理请求
         */
        fun loadPendingForTarget(targetCountryId: UUID): List<DiplomaticRequest> {
            return transaction {
                DiplomaticRequests.selectAll()
                    .where { 
                        (DiplomaticRequests.targetCountryId eq targetCountryId) and
                        (DiplomaticRequests.status eq RequestStatus.PENDING)
                    }
                    .map { DiplomaticRequest(it) }
            }
        }
        
        /**
         * 加载指定国家发起的所有待处理请求
         */
        fun loadPendingFromInitiator(initiatorCountryId: UUID): List<DiplomaticRequest> {
            return transaction {
                DiplomaticRequests.selectAll()
                    .where { 
                        (DiplomaticRequests.initiatorCountryId eq initiatorCountryId) and
                        (DiplomaticRequests.status eq RequestStatus.PENDING)
                    }
                    .map { DiplomaticRequest(it) }
            }
        }
        
        /**
         * 检查是否存在重复的待处理请求
         */
        fun hasPendingRequest(
            initiatorCountryId: UUID,
            targetCountryId: UUID,
            requestType: RelationType
        ): Boolean {
            return transaction {
                DiplomaticRequests.selectAll()
                    .where { 
                        (DiplomaticRequests.initiatorCountryId eq initiatorCountryId) and
                        (DiplomaticRequests.targetCountryId eq targetCountryId) and
                        (DiplomaticRequests.requestType eq requestType) and
                        (DiplomaticRequests.status eq RequestStatus.PENDING)
                    }
                    .count() > 0
            }
        }
        
        /**
         * 清理过期的请求
         */
        fun cleanupExpiredRequests(): Int {
            val now = System.currentTimeMillis()
            return transaction {
                // 先标记为过期
                val expiredCount = DiplomaticRequests.update({
                    (DiplomaticRequests.status eq RequestStatus.PENDING) and
                    (DiplomaticRequests.expiresAt less now)
                }) {
                    it[status] = RequestStatus.EXPIRED
                }
                
                // 删除超过7天的已处理请求
                val sevenDaysAgo = now - (7 * 24 * 60 * 60 * 1000)
                DiplomaticRequests.deleteWhere {
                    (DiplomaticRequests.status neq RequestStatus.PENDING) and
                    (DiplomaticRequests.createdAt less sevenDaysAgo)
                }
                
                expiredCount.toInt()
            }
        }
    }
}

