package cn.lcofficial.guozhan.command

import cn.lcofficial.guozhan.manager.ChatManager
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * 全球聊天命令 (/c)
 */
class GlobalChatCommand : CommandExecutor, TabCompleter {
    
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§c该命令只能由玩家执行！")
            return true
        }
        
        if (args.isEmpty()) {
            sender.sendMessage("§c用法: /c <消息内容>")
            return true
        }
        
        val message = args.joinToString(" ")
        ChatManager.handleGlobalChat(sender, message)
        
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): List<String> {
        // /c 命令不需要 tab 补全，因为是自由输入消息内容
        return emptyList()
    }
}

/**
 * 私聊命令 (/w)
 */
class WhisperCommand : CommandExecutor, TabCompleter {
    
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§c该命令只能由玩家执行！")
            return true
        }
        
        if (args.size < 2) {
            sender.sendMessage("§c用法: /w <玩家名> <消息内容>")
            return true
        }
        
        val targetName = args[0]
        val message = args.drop(1).joinToString(" ")
        
        ChatManager.handlePrivateMessage(sender, targetName, message)
        
        return true
    }
    
    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): List<String> {
        if (args.size == 1) {
            // 自动补全在线玩家名称
            return org.bukkit.Bukkit.getOnlinePlayers()
                .map { it.name }
                .filter { it.lowercase().startsWith(args[0].lowercase()) }
        }
        return emptyList()
    }
}