package cn.lcofficial.guozhan.data

import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.manager.DataManager
import cn.lcofficial.guozhan.manager.TerritoryManager
import cn.lcofficial.guozhan.pluginLogger
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture

/**
 * 占领进度表
 * 🔧 v1.3.57: 修复字段长度问题 - 增加字段长度到64确保兼容性
 * 🔧 v1.3.61: 移除default()避免Exposed ORM生成错误SQL
 */
object ClaimProgresses : Table("gz_claim_progress") {
    val id = varchar("id", 64)  // 🔧 v1.3.57: 从36增加到64，确保兼容任何UUID格式
    val territoryId = varchar("territory_id", 64)
    val countryId = varchar("country_id", 64)
    val initiatorId = varchar("initiator_id", 64)
    val startTime = long("start_time")
    val targetTime = long("target_time")
    val participants = text("participants")
    val worldName = varchar("world_name", 64)
    val chunkX = integer("chunk_x")
    val chunkZ = integer("chunk_z")
    val createdAt = long("created_at")  // 🔧 v1.3.61: 移除default()，在代码中手动设置
    val updatedAt = long("updated_at")  // 🔧 v1.3.61: 移除default()，在代码中手动设置

    override val primaryKey = PrimaryKey(id)
}

class ClaimProgress(
    val id: UUID,
    val territoryId: UUID,
    val countryId: UUID,
    val initiatorId: UUID,
    var startTime: Long,
    var targetTime: Long,
    initialParticipants: Collection<UUID>,
    val worldName: String,
    val chunkX: Int,
    val chunkZ: Int,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {

    val participants: MutableSet<UUID> =
        Collections.newSetFromMap(ConcurrentHashMap<UUID, Boolean>()).apply { addAll(initialParticipants) }

    fun getTerritory(): TerritoryBlock? {
        TerritoryManager.getTerritoryBlock(territoryId)?.let { return it }
        return TerritoryManager.getTerritoryBlock(chunkX, chunkZ, worldName)
    }

    fun getCountry(): Country? = CountryManager.getCountry(countryId)

    fun calculateProgress(): Double {
        val elapsed = System.currentTimeMillis() - startTime
        return (elapsed.toDouble() / targetTime).coerceIn(0.0, 1.0)
    }

    fun isCompleted(): Boolean = calculateProgress() >= 1.0

    fun save(async: Boolean = true): Boolean {
        updatedAt = System.currentTimeMillis()
        return if (async) {
            // 🔧 v1.3.52: 修复问题1 (High) - 注册异步任务到DataManager，防止关闭时数据丢失
            @Suppress("UNCHECKED_CAST")
            val future = CompletableFuture.runAsync {
                try {
                    saveInternal()
                } catch (e: Exception) {
                    pluginLogger.severe("保存占领进度失败 ($id): ${e.message}")
                    e.printStackTrace()
                }
            }.thenApply { null as Void? } as CompletableFuture<Void>
            DataManager.registerAsyncTask(future)
            true
        } else {
            try {
                saveInternal()
                true
            } catch (e: Exception) {
                pluginLogger.severe("保存占领进度失败 ($id): ${e.message}")
                e.printStackTrace()
                false
            }
        }
    }

    fun delete(): Boolean {
        return try {
            transaction { ClaimProgresses.deleteWhere { ClaimProgresses.id eq id.toString() } }
            true
        } catch (e: Exception) {
            pluginLogger.severe("删除占领进度失败 ($id): ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * 🔧 v1.3.57: 修复占领进度保存失败问题
     * 问题：使用IdTable<String>时，entityId()字段需要使用EntityID类型，而非直接String
     * 解决：将char字段改为varchar，避免使用entityId()
     * 🔧 v1.3.61: 修复Exposed ORM名称冲突 - 使用局部变量避免属性名与表字段名冲突
     */
    private fun saveInternal() {
        pluginLogger.info("[占领进度保存] v1.3.61: 使用deleteWhere+insert方法保存占领进度 (ID: $id)")
        // 🔧 v1.3.61: 使用局部变量避免Exposed ORM将实例属性误认为表字段引用
        val idValue = id.toString()
        val territoryIdValue = territoryId.toString()
        val countryIdValue = countryId.toString()
        val initiatorIdValue = initiatorId.toString()
        val startTimeValue = this.startTime
        val targetTimeValue = this.targetTime
        val participantsValue = participants.joinToString(",") { it.toString() }
        val worldNameValue = this.worldName
        val chunkXValue = this.chunkX
        val chunkZValue = this.chunkZ
        val createdAtValue = this.createdAt
        val updatedAtValue = this.updatedAt

        transaction {
            // 🔧 v1.3.60: 修复replace()方法的SQL错误
            // replace()在SQLite中会生成错误的SQL（使用表名引用而非参数占位符）
            // 改为先删除再插入的安全方式
            ClaimProgresses.deleteWhere { ClaimProgresses.id eq idValue }
            ClaimProgresses.insert { row ->
                row[ClaimProgresses.id] = idValue
                row[ClaimProgresses.territoryId] = territoryIdValue
                row[ClaimProgresses.countryId] = countryIdValue
                row[ClaimProgresses.initiatorId] = initiatorIdValue
                row[ClaimProgresses.startTime] = startTimeValue
                row[ClaimProgresses.targetTime] = targetTimeValue
                row[ClaimProgresses.participants] = participantsValue
                row[ClaimProgresses.worldName] = worldNameValue
                row[ClaimProgresses.chunkX] = chunkXValue
                row[ClaimProgresses.chunkZ] = chunkZValue
                row[ClaimProgresses.createdAt] = createdAtValue
                row[ClaimProgresses.updatedAt] = updatedAtValue
            }
        }
    }

    companion object {
        fun loadAll(): List<ClaimProgress> = transaction {
            ClaimProgresses.selectAll().mapNotNull { row ->
                try {
                    // 🔧 v1.3.57: 直接读取String，不使用EntityID
                    val id = UUID.fromString(row[ClaimProgresses.id])
                    val territoryId = UUID.fromString(row[ClaimProgresses.territoryId])
                    val countryId = UUID.fromString(row[ClaimProgresses.countryId])
                    val initiatorId = UUID.fromString(row[ClaimProgresses.initiatorId])
                    val participants = row[ClaimProgresses.participants]
                        .takeIf { it.isNotBlank() }
                        ?.split(",")
                        ?.mapNotNull {
                            runCatching { UUID.fromString(it) }.getOrNull()
                        } ?: emptyList()

                    ClaimProgress(
                        id = id,
                        territoryId = territoryId,
                        countryId = countryId,
                        initiatorId = initiatorId,
                        startTime = row[ClaimProgresses.startTime],
                        targetTime = row[ClaimProgresses.targetTime],
                        initialParticipants = participants,
                        worldName = row[ClaimProgresses.worldName],
                        chunkX = row[ClaimProgresses.chunkX],
                        chunkZ = row[ClaimProgresses.chunkZ],
                        createdAt = row[ClaimProgresses.createdAt],
                        updatedAt = row[ClaimProgresses.updatedAt]
                    )
                } catch (e: Exception) {
                    pluginLogger.warning("加载占领进度记录失败: ${e.message}")
                    null
                }
            }
        }

        fun cleanupExpired(): Int {
            val threshold = System.currentTimeMillis() - 60 * 60 * 1000L
            return try {
                transaction { ClaimProgresses.deleteWhere { updatedAt less threshold } }
            } catch (e: Exception) {
                pluginLogger.severe("清理过期占领进度失败: ${e.message}")
                e.printStackTrace()
                0
            }
        }
    }
}
