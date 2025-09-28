package cn.lcofficial.guozhan.listener

import cn.lcofficial.guozhan.config.Config
import cn.lcofficial.guozhan.manager.RandomSpawnManager
import cn.lcofficial.guozhan.pluginLogger
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

/**
 * 随机出生监听器
 * 处理玩家加入事件，为新玩家提供随机出生点
 */
class RandomSpawnListener : Listener {

    /**
     * 注册监听器
     */
    fun register() {
        val plugin = cn.lcofficial.guozhan.Guozhan.instance
        org.bukkit.Bukkit.getPluginManager().registerEvents(this, plugin)
        pluginLogger.info("随机出生监听器已注册")
    }
    
    /**
     * 处理玩家加入事件
     * 为第一次加入的玩家提供随机出生点
     */
    @EventHandler(priority = EventPriority.HIGH)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        
        // 检查随机出生系统是否启用
        if (!Config.RandomSpawn.enabled) {
            return
        }
        
        // 检查是否是第一次加入的玩家
        if (!RandomSpawnManager.isFirstTimePlayer(player)) {
            pluginLogger.debug("玩家 ${player.name} 不是第一次加入，跳过随机出生")
            return
        }
        
        // 为新玩家寻找随机出生点
        pluginLogger.info("检测到新玩家 ${player.name}，开始寻找随机出生点")
        
        // 延迟执行，确保玩家完全加载
        org.bukkit.Bukkit.getScheduler().runTaskLater(
            cn.lcofficial.guozhan.Guozhan.instance,
            Runnable {
                RandomSpawnManager.teleportPlayerToRandomSpawn(player).thenAccept { success ->
                    if (success) {
                        // 发送欢迎消息
                        sendWelcomeMessage(player)
                    } else {
                        // 发送备用消息
                        sendFallbackMessage(player)
                    }
                }
            },
            20L // 延迟1秒执行
        )
    }
    
    /**
     * 发送欢迎消息给新玩家
     * @param player 新玩家
     */
    private fun sendWelcomeMessage(player: Player) {
        val messages = listOf(
            "§6===========================================",
            "§a§l欢迎来到国战服务器！",
            "§7",
            "§e你已被传送到一个安全的随机位置。",
            "§e在这里你可以：",
            "§7• §f建立自己的国家",
            "§7• §f与其他玩家结盟或战斗", 
            "§7• §f占领领土扩张势力",
            "§7• §f参与激烈的国战",
            "§7",
            "§b输入 §f/u help §b查看所有可用命令",
            "§b输入 §f/u create <国家名> §b创建你的国家",
            "§7",
            "§d祝你游戏愉快！",
            "§6==========================================="
        )
        
        // 延迟发送消息，确保玩家能看到
        org.bukkit.Bukkit.getScheduler().runTaskLater(
            cn.lcofficial.guozhan.Guozhan.instance,
            Runnable {
                messages.forEach { message ->
                    player.sendMessage(message)
                }
            },
            40L // 延迟2秒发送
        )
    }
    
    /**
     * 发送备用消息（当随机出生失败时）
     * @param player 玩家
     */
    private fun sendFallbackMessage(player: Player) {
        val messages = listOf(
            "§6===========================================",
            "§a§l欢迎来到国战服务器！",
            "§7",
            "§e你当前在世界出生点。",
            "§e建议你尽快离开此区域寻找合适的地方建立基地。",
            "§7",
            "§b输入 §f/u help §b查看所有可用命令",
            "§b输入 §f/u create <国家名> §b创建你的国家",
            "§7",
            "§d祝你游戏愉快！",
            "§6==========================================="
        )
        
        // 延迟发送消息
        org.bukkit.Bukkit.getScheduler().runTaskLater(
            cn.lcofficial.guozhan.Guozhan.instance,
            Runnable {
                messages.forEach { message ->
                    player.sendMessage(message)
                }
            },
            40L // 延迟2秒发送
        )
    }
}
