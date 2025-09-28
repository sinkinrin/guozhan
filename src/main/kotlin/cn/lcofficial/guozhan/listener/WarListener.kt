package cn.lcofficial.guozhan.listener

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.data.RelationType
import cn.lcofficial.guozhan.effect.WarEffects
import cn.lcofficial.guozhan.event.DiplomaticRelationChangeEvent
import cn.lcofficial.guozhan.manager.UserManager.user
import cn.lcofficial.guozhan.manager.WarManager
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent

/**
 * 战争监听器，处理战争相关的事件
 */
class WarListener : Listener {
    
    /**
     * 注册监听器
     */
    fun register() {
        Bukkit.getPluginManager().registerEvents(this, Guozhan.instance)
        Guozhan.instance.logger.info("已注册战争监听器")
        
        // 启动战争超时检查任务
        startWarTimeoutTask()
    }
    
    /**
     * 启动战争超时检查任务
     */
    private fun startWarTimeoutTask() {
        // 每小时检查一次战争超时
        Bukkit.getScheduler().runTaskTimer(Guozhan.instance, Runnable {
            WarManager.checkWarTimeout()
        }, 20 * 60 * 60, 20 * 60 * 60) // 1小时 = 20 ticks/s * 60s * 60min
    }
    
    /**
     * 处理外交关系变化事件，特别关注战争状态的变化
     */
    @EventHandler
    fun onDiplomaticRelationChange(event: DiplomaticRelationChangeEvent) {
        val country1 = event.country1
        val country2 = event.country2
        val newRelation = event.newRelationType
        val oldRelation = event.oldRelationType
        
        // 如果新关系是战争，但旧关系不是，则开始战争
        if (newRelation == RelationType.WAR && oldRelation != RelationType.WAR) {
            WarManager.startWar(country1, country2)
        }
        // 如果旧关系是战争，但新关系不是，则结束战争
        else if (oldRelation == RelationType.WAR && newRelation != RelationType.WAR) {
            // 这里没有指定胜利者，因为这是通过外交手段结束的战争
            WarManager.endWar(country1, country2, null)
        }
    }
    
    /**
     * 处理玩家伤害事件，在战争状态下增加伤害
     */
    @EventHandler(priority = EventPriority.NORMAL)
    fun onEntityDamage(event: EntityDamageByEntityEvent) {
        WarManager.handleDamageEvent(event)
        
        // 处理玩家之间的伤害
        if (event.entity is Player && event.damager is Player) {
            val damaged = event.entity as Player
            val damager = event.damager as Player
            
            val damagedUser = damaged.user()
            val damagerUser = damager.user()
            
            val damagedCountry = damagedUser.country
            val damagerCountry = damagerUser.country
            
            // 如果任一玩家不属于国家，则不处理
            if (damagedCountry == null || damagerCountry == null) return
            
            // 检查两个国家是否处于战争状态
            if (WarManager.isAtWar(damagedCountry, damagerCountry)) {
                // 检查是否在领土上，应用额外战争效果
                WarEffects.applyWarCombatEffects(damager, damaged)
            }
        }
    }
    
    /**
     * 处理玩家死亡事件，记录战争中的击杀
     */
    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        // 处理玩家死亡事件，包括战争击杀奖励
        // WarManager.handlePlayerDeath已经处理了战争击杀奖励和消息广播
        WarManager.handlePlayerDeath(event)
    }
    
    /**
     * 处理玩家加入事件，向玩家发送战争状态信息
     */
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        val user = player.user()
        val country = user.country ?: return
        
        // 延迟3秒发送战争信息，确保玩家已完全加载
        Bukkit.getScheduler().runTaskLater(Guozhan.instance, Runnable {
            if (player.isOnline) {
                sendWarStatus(player, country)
                
                // 应用战争效果
                WarEffects.applyWarEffects(player)
            }
        }, 60L) // 60 ticks = 3 seconds
    }
    
    /**
     * 向玩家发送战争状态信息
     */
    private fun sendWarStatus(player: Player, country: cn.lcofficial.guozhan.data.Country) {
        val warOpponents = WarManager.getWarOpponents(country)
        
        if (warOpponents.isEmpty()) return
        
        player.sendMessage("§c§l警告! 你的国家正处于战争状态!")
        
        warOpponents.forEach { opponent ->
            val duration = WarManager.getWarDuration(country, opponent)
            val formattedDuration = WarManager.formatWarDuration(duration)
            
            player.sendMessage("§c- 与 §f${opponent.name} §c的战争已持续 §f${formattedDuration}")
        }
    }
}