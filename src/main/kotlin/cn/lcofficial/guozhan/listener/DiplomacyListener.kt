package cn.lcofficial.guozhan.listener

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.data.RelationType
import cn.lcofficial.guozhan.event.DiplomaticRelationChangeEvent
import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.manager.DiplomacyManager
import cn.lcofficial.guozhan.manager.UserManager
import cn.lcofficial.guozhan.manager.UserManager.user
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

/**
 * 外交关系监听器，处理国家间关系变化的事件和通知
 */
class DiplomacyListener : Listener {
    
    /**
     * 注册监听器
     */
    fun register() {
        Bukkit.getPluginManager().registerEvents(this, Guozhan.instance)
        Guozhan.instance.logger.info("已注册外交关系监听器")
    }
    
    /**
     * 处理玩家加入事件，向玩家发送其国家的外交状态信息
     */
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        val user = player.user()
        val country = user.country ?: return
        
        // 延迟2秒发送外交信息，确保玩家已完全加载
        Bukkit.getScheduler().runTaskLater(Guozhan.instance, Runnable {
            if (player.isOnline) {
                sendDiplomacyStatus(player, country)
            }
        }, 40L) // 40 ticks = 2 seconds
    }
    
    /**
     * 处理外交关系变化事件
     */
    @EventHandler
    fun onDiplomaticRelationChange(event: DiplomaticRelationChangeEvent) {
        val country1 = event.country1
        val country2 = event.country2
        val newRelation = event.newRelationType
        val oldRelation = event.oldRelationType
        
        // 如果关系没有变化，不做处理
        if (newRelation == oldRelation) return
        
        // 获取关系名称
        val relationName = when (newRelation) {
            RelationType.NEUTRAL -> "中立"
            RelationType.FRIENDLY -> "友好"
            RelationType.ALLIED -> "同盟"
            RelationType.HOSTILE -> "敌对"
            RelationType.WAR -> "战争"
        }
        
        // 通知两个国家的所有在线成员
        val message = Component.text("§6外交关系变更: §f${country1.name} §6与 §f${country2.name} §6的关系变为 ")
            .append(getRelationColoredText(newRelation, relationName))
        
        // 通知国家1的成员
        notifyCountryMembers(country1, message)
        
        // 通知国家2的成员
        notifyCountryMembers(country2, message)
        
        // 如果是战争状态，发送特殊警告
        if (newRelation == RelationType.WAR) {
            val warMessage = Component.text("§c§l警告! §f${country1.name} §c与 §f${country2.name} §c进入战争状态!")
            
            // 通知所有在线玩家
            Bukkit.getOnlinePlayers().forEach { player ->
                player.sendMessage(warMessage)
            }
        }
    }
    
    /**
     * 向玩家发送其国家的外交状态信息
     */
    private fun sendDiplomacyStatus(player: Player, country: cn.lcofficial.guozhan.data.Country) {
        val allies = DiplomacyManager.getAllies(country)
        val enemies = DiplomacyManager.getEnemies(country)
        
        if (allies.isEmpty() && enemies.isEmpty()) return
        
        player.sendMessage("§6===== ${country.name} 的外交状态 =====")
        
        if (allies.isNotEmpty()) {
            player.sendMessage("§a同盟国家: §f${allies.joinToString(", ") { it.name }}")
        }
        
        if (enemies.isNotEmpty()) {
            player.sendMessage("§c敌对国家: §f${enemies.joinToString(", ") { it.name }}")
            
            // 如果有战争状态的国家，特别提醒
            val warCountries = enemies.filter { enemy ->
                DiplomacyManager.getRelation(country, enemy).relationType == RelationType.WAR
            }
            
            if (warCountries.isNotEmpty()) {
                player.sendMessage("§c§l警告! 你的国家正与以下国家处于战争状态: §f${warCountries.joinToString(", ") { it.name }}")
            }
        }
    }
    
    /**
     * 通知国家的所有在线成员
     */
    private fun notifyCountryMembers(country: cn.lcofficial.guozhan.data.Country, message: Component) {
        val members = UserManager.users.values.filter { it.country?.id == country.id }
        
        members.forEach { member ->
            val player = Bukkit.getPlayer(member.uuid)
            if (player != null && player.isOnline) {
                player.sendMessage(message)
            }
        }
    }
    
    /**
     * 获取带颜色的关系文本
     */
    private fun getRelationColoredText(relationType: RelationType, relationName: String): Component {
        return when (relationType) {
            RelationType.NEUTRAL -> Component.text(relationName).color(NamedTextColor.GRAY)
            RelationType.FRIENDLY -> Component.text(relationName).color(NamedTextColor.GREEN)
            RelationType.ALLIED -> Component.text(relationName).color(NamedTextColor.AQUA)
            RelationType.HOSTILE -> Component.text(relationName).color(NamedTextColor.GOLD)
            RelationType.WAR -> Component.text(relationName).color(NamedTextColor.RED)
        }
    }
}