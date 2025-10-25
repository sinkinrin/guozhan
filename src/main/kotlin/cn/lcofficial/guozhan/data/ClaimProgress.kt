package cn.lcofficial.guozhan.data

import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.manager.DataManager
import cn.lcofficial.guozhan.manager.TerritoryManager
import cn.lcofficial.guozhan.pluginLogger
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.replace
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture

/**
 * 占领进度表
 */
object ClaimProgresses : IdTable<String>("gz_claim_progress") {
    override val id = char("id", 36).uniqueIndex().entityId()
    val territoryId = char("territory_id", 36)
    val countryId = char("country_id", 36)
    val initiatorId = char("initiator_id", 36)
    val startTime = long("start_time")
    val targetTime = long("target_time")
    val participants = text("participants")
    val worldName = varchar("world_name", 64)
    val chunkX = integer("chunk_x")
    val chunkZ = integer("chunk_z")
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val updatedAt = long("updated_at").default(System.currentTimeMillis())
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

    private fun saveInternal() {
        val snapshot = participants.toList()
        transaction {
            ClaimProgresses.replace { row ->
                row[ClaimProgresses.id] = id.toString()
                row[ClaimProgresses.territoryId] = territoryId.toString()
                row[ClaimProgresses.countryId] = countryId.toString()
                row[ClaimProgresses.initiatorId] = initiatorId.toString()
                row[ClaimProgresses.startTime] = startTime
                row[ClaimProgresses.targetTime] = targetTime
                row[ClaimProgresses.participants] = snapshot.joinToString(",") { it.toString() }
                row[ClaimProgresses.worldName] = worldName
                row[ClaimProgresses.chunkX] = chunkX
                row[ClaimProgresses.chunkZ] = chunkZ
                row[ClaimProgresses.createdAt] = createdAt
                row[ClaimProgresses.updatedAt] = updatedAt
            }
        }
    }

    companion object {
        fun loadAll(): List<ClaimProgress> = transaction {
            ClaimProgresses.selectAll().mapNotNull { row ->
                try {
                    val id = UUID.fromString(row[ClaimProgresses.id].value)
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
