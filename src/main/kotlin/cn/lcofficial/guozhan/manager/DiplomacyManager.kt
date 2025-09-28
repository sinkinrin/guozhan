package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.data.Country
import cn.lcofficial.guozhan.data.DiplomaticRelation
import cn.lcofficial.guozhan.data.DiplomaticRelations
import cn.lcofficial.guozhan.data.RelationType
import cn.lcofficial.guozhan.event.DiplomaticRelationChangeEvent
import org.bukkit.Bukkit
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

/**
 * 外交关系管理器
 */
object DiplomacyManager {
    
    // 缓存所有外交关系
    private val relations = mutableMapOf<Pair<UUID, UUID>, DiplomaticRelation>()
    
    init {
        // 从数据库加载所有外交关系
        loadAllRelations()
    }
    
    /**
     * 从数据库加载所有外交关系
     */
    private fun loadAllRelations() {
        transaction {
            DiplomaticRelations.selectAll().forEach { row ->
                val relation = DiplomaticRelation(row)
                val key = if (relation.country1Id < relation.country2Id) {
                    Pair(relation.country1Id, relation.country2Id)
                } else {
                    Pair(relation.country2Id, relation.country1Id)
                }
                relations[key] = relation
            }
        }
        Guozhan.instance.logger.info("已加载 ${relations.size} 条外交关系")
    }
    
    /**
     * 获取两个国家之间的外交关系
     * 如果不存在则创建一个中立关系
     */
    fun getRelation(country1: Country, country2: Country): DiplomaticRelation {
        // 确保国家ID的顺序一致，以便正确缓存
        val key = if (country1.id < country2.id) {
            Pair(country1.id, country2.id)
        } else {
            Pair(country2.id, country1.id)
        }
        
        return relations[key] ?: run {
            // 创建新的中立关系
            val newRelation = DiplomaticRelation.create(country1.id, country2.id)
            relations[key] = newRelation
            newRelation
        }
    }
    
    /**
     * 更新两个国家之间的外交关系
     */
    fun updateRelation(country1: Country, country2: Country, relationType: RelationType): DiplomaticRelation {
        val relation = getRelation(country1, country2)
        val oldRelationType = relation.relationType
        relation.updateRelationType(relationType)
        
        // 通知两个国家的在线成员
        notifyRelationChange(country1, country2, relationType)
        
        // 触发关系变化事件
        val event = DiplomaticRelationChangeEvent(country1, country2, oldRelationType, relationType)
        Bukkit.getPluginManager().callEvent(event)
        
        return relation
    }
    
    /**
     * 检查两个国家是否为同盟关系
     */
    fun areAllied(country1: Country, country2: Country): Boolean {
        return getRelation(country1, country2).isAllied()
    }
    
    /**
     * 检查两个国家是否为敌对关系
     */
    fun areHostile(country1: Country, country2: Country): Boolean {
        return getRelation(country1, country2).isHostile()
    }
    
    /**
     * 检查两个国家是否处于战争状态
     */
    fun areAtWar(country1: Country, country2: Country): Boolean {
        return getRelation(country1, country2).isAtWar()
    }
    
    /**
     * 获取与指定国家有特定关系的所有国家
     */
    fun getCountriesWithRelation(country: Country, relationType: RelationType): List<Country> {
        val result = mutableListOf<Country>()
        
        for ((key, relation) in relations) {
            if (relation.relationType != relationType) continue
            
            if (key.first == country.id) {
                CountryManager.getCountry(key.second)?.let { result.add(it) }
            } else if (key.second == country.id) {
                CountryManager.getCountry(key.first)?.let { result.add(it) }
            }
        }
        
        return result
    }
    
    /**
     * 获取国家的所有同盟
     */
    fun getAllies(country: Country): List<Country> {
        return getCountriesWithRelation(country, RelationType.ALLIED)
    }
    
    /**
     * 获取与国家处于战争状态的所有国家
     */
    fun getEnemies(country: Country): List<Country> {
        return getCountriesWithRelation(country, RelationType.WAR)
    }
    
    /**
     * 通知两个国家的在线成员关系变化
     */
    private fun notifyRelationChange(country1: Country, country2: Country, relationType: RelationType) {
        // 获取两个国家的所有在线成员
        val country1Members = Bukkit.getOnlinePlayers().filter { player ->
            val user = player.user()
            user.country?.id == country1.id
        }
        
        val country2Members = Bukkit.getOnlinePlayers().filter { player ->
            val user = player.user()
            user.country?.id == country2.id
        }
        
        // 构建消息
        val relationName = when (relationType) {
            RelationType.NEUTRAL -> "中立"
            RelationType.FRIENDLY -> "友好"
            RelationType.ALLIED -> "同盟"
            RelationType.HOSTILE -> "敌对"
            RelationType.WAR -> "战争"
        }
        
        // 通知国家1的成员
        country1Members.forEach { player ->
            player.sendMessage("§6[外交关系] §a您的国家与 §f${country2.name} §a的关系变为: §f$relationName")
        }
        
        // 通知国家2的成员
        country2Members.forEach { player ->
            player.sendMessage("§6[外交关系] §a您的国家与 §f${country1.name} §a的关系变为: §f$relationName")
        }
    }
}

/**
 * 获取玩家的用户数据
 */
private fun org.bukkit.entity.Player.user() = UserManager.getUser(this)