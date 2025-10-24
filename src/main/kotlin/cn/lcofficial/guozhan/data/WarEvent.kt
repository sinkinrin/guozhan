package cn.lcofficial.guozhan.data

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.util.*

/**
 * 战争事件表
 * 🔧 v1.3.52: 用于持久化战争状态，支持服务器重启后恢复战争
 */
object WarEvents : IdTable<String>("gz_war_events") {
    override val id = char("id", 36).uniqueIndex().entityId() // 战争ID (UUID)
    val startTime = long("start_time") // 战争开始时间戳
    val isActive = bool("is_active").default(true) // 是否激活
    val warScores = text("war_scores") // 战争积分 JSON (格式: {"countryId1": score1, "countryId2": score2, ...})
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val updatedAt = long("updated_at").default(System.currentTimeMillis())
}

/**
 * 战争事件数据类
 * 🔧 v1.3.52: 封装战争状态数据
 */
data class WarEvent(
    val id: UUID,
    val startTime: Long,
    var isActive: Boolean,
    var warScores: MutableMap<UUID, Int>,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * 保存战争事件到数据库
     * 🔧 v1.3.52: 持久化战争状态
     */
    fun save() = transaction {
        val scoresJson = warScores.entries.joinToString(",") { (countryId, score) ->
            "\"$countryId\":$score"
        }.let { "{$it}" }
        
        val exists = WarEvents.selectAll()
            .where { WarEvents.id eq id.toString() }
            .count() > 0
        
        if (exists) {
            WarEvents.update({ WarEvents.id eq id.toString() }) {
                it[WarEvents.isActive] = this@WarEvent.isActive
                it[WarEvents.warScores] = scoresJson
                it[WarEvents.updatedAt] = System.currentTimeMillis()
            }
        } else {
            WarEvents.insert {
                it[WarEvents.id] = this@WarEvent.id.toString()
                it[WarEvents.startTime] = this@WarEvent.startTime
                it[WarEvents.isActive] = this@WarEvent.isActive
                it[WarEvents.warScores] = scoresJson
                it[WarEvents.createdAt] = this@WarEvent.createdAt
                it[WarEvents.updatedAt] = System.currentTimeMillis()
            }
        }
    }
    
    /**
     * 删除战争事件
     * 🔧 v1.3.52: 战争结束后清理数据库记录
     */
    fun delete() = transaction {
        WarEvents.deleteWhere { WarEvents.id eq id.toString() }
    }
    
    companion object {
        /**
         * 从数据库加载战争事件
         * 🔧 v1.3.52: 服务器重启后恢复战争状态
         */
        fun load(id: UUID): WarEvent? = transaction {
            WarEvents.selectAll()
                .where { WarEvents.id eq id.toString() }
                .map { row ->
                    val scoresJson = row[WarEvents.warScores]
                    val scores = parseWarScores(scoresJson)
                    
                    WarEvent(
                        id = UUID.fromString(row[WarEvents.id].value),
                        startTime = row[WarEvents.startTime],
                        isActive = row[WarEvents.isActive],
                        warScores = scores,
                        createdAt = row[WarEvents.createdAt],
                        updatedAt = row[WarEvents.updatedAt]
                    )
                }
                .firstOrNull()
        }
        
        /**
         * 加载所有激活的战争事件
         * 🔧 v1.3.52: 服务器启动时恢复所有进行中的战争
         */
        fun loadAllActive(): List<WarEvent> = transaction {
            WarEvents.selectAll()
                .where { WarEvents.isActive eq true }
                .map { row ->
                    val scoresJson = row[WarEvents.warScores]
                    val scores = parseWarScores(scoresJson)
                    
                    WarEvent(
                        id = UUID.fromString(row[WarEvents.id].value),
                        startTime = row[WarEvents.startTime],
                        isActive = row[WarEvents.isActive],
                        warScores = scores,
                        createdAt = row[WarEvents.createdAt],
                        updatedAt = row[WarEvents.updatedAt]
                    )
                }
        }
        
        /**
         * 解析战争积分 JSON
         * 🔧 v1.3.52: 从 JSON 字符串解析积分数据
         */
        private fun parseWarScores(json: String): MutableMap<UUID, Int> {
            val scores = mutableMapOf<UUID, Int>()
            
            try {
                // 简单的 JSON 解析 (格式: {"uuid1":score1,"uuid2":score2})
                val content = json.trim().removeSurrounding("{", "}")
                if (content.isNotEmpty()) {
                    content.split(",").forEach { entry ->
                        val parts = entry.split(":")
                        if (parts.size == 2) {
                            val countryId = UUID.fromString(parts[0].trim().removeSurrounding("\""))
                            val score = parts[1].trim().toInt()
                            scores[countryId] = score
                        }
                    }
                }
            } catch (e: Exception) {
                cn.lcofficial.guozhan.Guozhan.instance.logger.severe("[战争系统] 解析战争积分失败: ${e.message}")
                e.printStackTrace()
            }
            
            return scores
        }
        
        /**
         * 清理所有战争事件
         * 🔧 v1.3.52: 用于测试或重置
         */
        fun deleteAll() = transaction {
            WarEvents.deleteAll()
        }
    }
}

