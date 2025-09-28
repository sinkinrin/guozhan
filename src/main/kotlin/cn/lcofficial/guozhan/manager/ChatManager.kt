package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.manager.UserManager.user
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import java.util.*

/**
 * 聊天系统管理器
 * 处理国家聊天、全球聊天和私聊功能
 */
object ChatManager : Listener {
    
    // 私聊冷却时间 (毫秒)
    private const val PRIVATE_MESSAGE_COOLDOWN = 1000L
    
    // 记录玩家上次私聊时间
    private val lastPrivateMessageTime = mutableMapOf<UUID, Long>()
    
    /**
     * 初始化聊天管理器
     */
    fun initialize() {
        Bukkit.getPluginManager().registerEvents(this, Guozhan.instance)
    }
    
    /**
     * 处理玩家聊天事件
     * 默认聊天仅国家成员可见
     */
    @EventHandler(priority = EventPriority.HIGH)
    fun onPlayerChat(event: AsyncPlayerChatEvent) {
        val player = event.player
        val user = player.user()
        
        // 如果玩家没有国家，使用全服聊天
        if (user.country == null) {
            handleGlobalChat(player, event.message, event)
            return
        }
        
        // 默认为国家聊天
        handleCountryChat(player, event.message, event)
    }
    
    /**
     * 处理国家聊天
     */
    private fun handleCountryChat(player: Player, message: String, event: AsyncPlayerChatEvent) {
        val user = player.user()
        val country = user.country ?: return
        
        // 设置聊天格式
        val format = "§6[${country.name}] §f${player.displayName}§7: §f${message}"
        
        // 只显示给同国家的玩家
        val recipients = mutableSetOf<Player>()
        country.members.forEach { member ->
            val memberPlayer = Bukkit.getPlayer(member.uniqueId)
            if (memberPlayer != null && memberPlayer.isOnline) {
                recipients.add(memberPlayer)
            }
        }
        
        // 取消原事件，手动发送消息
        event.isCancelled = true
        
        // 发送给所有国家成员
        recipients.forEach { recipient ->
            recipient.sendMessage(format)
        }
        
        // 记录到控制台
        Guozhan.instance.logger.info("[国家聊天][${country.name}] ${player.name}: $message")
    }
    
    /**
     * 处理全球聊天 (/c 命令)
     */
    fun handleGlobalChat(player: Player, message: String, event: AsyncPlayerChatEvent? = null) {
        val user = player.user()
        
        // 设置聊天格式
        val countryTag = if (user.country != null) {
            "§8[§6${user.country!!.name}§8]"
        } else {
            "§8[§7流民§8]"
        }
        
        val format = "$countryTag §f${player.displayName}§7: §f$message"
        
        // 取消原事件（如果存在）
        event?.isCancelled = true
        
        // 广播给所有玩家
        Bukkit.broadcastMessage(format)
        
        // 记录到控制台
        Guozhan.instance.logger.info("[全球聊天] ${player.name}: $message")
    }
    
    /**
     * 处理私聊消息 (/w 命令)
     */
    fun handlePrivateMessage(sender: Player, targetName: String, message: String): Boolean {
        val senderId = sender.uniqueId
        val currentTime = System.currentTimeMillis()
        
        // 检查冷却时间
        val lastMessageTime = lastPrivateMessageTime[senderId] ?: 0L
        if (currentTime - lastMessageTime < PRIVATE_MESSAGE_COOLDOWN) {
            sender.sendMessage("§c发送私聊消息过于频繁，请稍后再试！")
            return false
        }
        
        // 查找目标玩家
        val target = Bukkit.getPlayer(targetName)
        if (target == null || !target.isOnline) {
            sender.sendMessage("§c玩家 $targetName 不在线！")
            return false
        }
        
        if (target.uniqueId == sender.uniqueId) {
            sender.sendMessage("§c你不能给自己发送私聊消息！")
            return false
        }
        
        // 记录发送时间
        lastPrivateMessageTime[senderId] = currentTime
        
        // 发送私聊消息
        val senderMessage = "§d[私聊] §f你 -> ${target.displayName}§7: §f$message"
        val targetMessage = "§d[私聊] §f${sender.displayName} -> 你§7: §f$message"
        
        sender.sendMessage(senderMessage)
        target.sendMessage(targetMessage)
        
        // 记录到控制台
        Guozhan.instance.logger.info("[私聊] ${sender.name} -> ${target.name}: $message")
        
        return true
    }
    
    /**
     * 清理冷却时间记录
     */
    fun cleanup() {
        lastPrivateMessageTime.clear()
    }
}