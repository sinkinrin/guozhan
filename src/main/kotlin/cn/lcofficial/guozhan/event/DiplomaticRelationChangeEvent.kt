package cn.lcofficial.guozhan.event

import cn.lcofficial.guozhan.data.Country
import cn.lcofficial.guozhan.data.RelationType
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * 外交关系变化事件
 * 当两个国家之间的外交关系发生变化时触发
 */
class DiplomaticRelationChangeEvent(
    val country1: Country,
    val country2: Country,
    val oldRelationType: RelationType,
    val newRelationType: RelationType
) : Event() {
    
    companion object {
        private val HANDLERS = HandlerList()
        
        @JvmStatic
        fun getHandlerList(): HandlerList {
            return HANDLERS
        }
    }
    
    override fun getHandlers(): HandlerList {
        return HANDLERS
    }
}