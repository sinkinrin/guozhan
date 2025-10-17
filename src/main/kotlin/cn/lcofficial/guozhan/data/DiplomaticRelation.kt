package cn.lcofficial.guozhan.data

import cn.lcofficial.guozhan.manager.DataManager
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

/**
 * 外交关系表
 */
object DiplomaticRelations : Table("gz_diplomatic_relations") {
    val id = uuid("id")
    val country1Id = uuid("country1_id")
    val country2Id = uuid("country2_id")
    val relationType = enumeration<RelationType>("relation_type")
    val friendliness = integer("friendliness").default(50) // 友好度，0-100
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    // 🔧 修复问题2：添加战争开始时间字段
    val warStartTime = long("war_start_time").nullable() // 战争开始时间戳（仅在WAR状态时有值）

    override val primaryKey = PrimaryKey(id)
}

/**
 * 外交关系类型
 */
enum class RelationType {
    NEUTRAL,    // 中立
    FRIENDLY,   // 友好
    ALLIED,     // 同盟
    HOSTILE,    // 敌对
    WAR         // 战争
}

/**
 * 外交关系实体类
 */
class DiplomaticRelation(
    val id: UUID,
    val country1Id: UUID,
    val country2Id: UUID,
    var relationType: RelationType,
    var friendliness: Int = 50, // 友好度，0-100
    val createdAt: Long,
    var updatedAt: Long,
    // 🔧 修复问题2：添加战争开始时间字段
    var warStartTime: Long? = null // 战争开始时间戳（仅在WAR状态时有值）
) {

    /**
     * 构造函数，从数据库行创建
     */
    constructor(row: ResultRow) : this(
        id = row[DiplomaticRelations.id],
        country1Id = row[DiplomaticRelations.country1Id],
        country2Id = row[DiplomaticRelations.country2Id],
        relationType = row[DiplomaticRelations.relationType],
        friendliness = row[DiplomaticRelations.friendliness],
        createdAt = row[DiplomaticRelations.createdAt],
        updatedAt = row[DiplomaticRelations.updatedAt],
        warStartTime = row[DiplomaticRelations.warStartTime]
    )
    
    /**
     * 保存外交关系到数据库
     */
    fun save() {
        transaction {
            DiplomaticRelations.update({ DiplomaticRelations.id eq this@DiplomaticRelation.id }) {
                it[relationType] = this@DiplomaticRelation.relationType
                it[friendliness] = this@DiplomaticRelation.friendliness
                it[updatedAt] = System.currentTimeMillis()
                // 🔧 修复问题2：保存战争开始时间
                it[warStartTime] = this@DiplomaticRelation.warStartTime
            }
            this@DiplomaticRelation.updatedAt = System.currentTimeMillis()
        }
    }
    
    /**
     * 更新关系类型
     */
    fun updateRelationType(newType: RelationType) {
        relationType = newType
        save()
    }
    
    /**
     * 检查是否为同盟关系
     */
    fun isAllied(): Boolean {
        return relationType == RelationType.ALLIED
    }
    
    /**
     * 检查是否为敌对关系
     */
    fun isHostile(): Boolean {
        return relationType == RelationType.HOSTILE || relationType == RelationType.WAR
    }
    
    /**
     * 检查是否处于战争状态
     */
    fun isAtWar(): Boolean {
        return relationType == RelationType.WAR
    }
    
    companion object {
        /**
         * 创建新的外交关系
         */
        fun create(country1Id: UUID, country2Id: UUID, relationType: RelationType = RelationType.NEUTRAL): DiplomaticRelation {
            val id = UUID.randomUUID()
            val now = System.currentTimeMillis()

            return transaction {
                DiplomaticRelations.insert {
                    it[DiplomaticRelations.id] = id
                    it[DiplomaticRelations.country1Id] = country1Id
                    it[DiplomaticRelations.country2Id] = country2Id
                    it[DiplomaticRelations.relationType] = relationType
                    it[createdAt] = now
                    it[updatedAt] = now
                    // 🔧 修复问题2：如果是战争状态，设置战争开始时间
                    it[warStartTime] = if (relationType == RelationType.WAR) now else null
                }

                DiplomaticRelation(
                    id = id,
                    country1Id = country1Id,
                    country2Id = country2Id,
                    relationType = relationType,
                    createdAt = now,
                    updatedAt = now,
                    warStartTime = if (relationType == RelationType.WAR) now else null
                )
            }
        }
        
        /**
         * 获取两个国家之间的外交关系
         */
        fun getRelation(country1Id: UUID, country2Id: UUID): DiplomaticRelation? {
            return transaction {
                // 尝试按照正向顺序查找
                val relation = DiplomaticRelations.selectAll().where {
                    (DiplomaticRelations.country1Id eq country1Id) and (DiplomaticRelations.country2Id eq country2Id)
                }.firstOrNull()
                
                // 如果没找到，尝试按照反向顺序查找
                if (relation == null) {
                    DiplomaticRelations.selectAll().where {
                        (DiplomaticRelations.country1Id eq country2Id) and (DiplomaticRelations.country2Id eq country1Id)
                    }.firstOrNull()?.let { DiplomaticRelation(it) }
                } else {
                    DiplomaticRelation(relation)
                }
            }
        }
    }
}