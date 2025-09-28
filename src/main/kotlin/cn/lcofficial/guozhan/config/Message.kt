package cn.lcofficial.guozhan.config

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.regex.Pattern

object Message : Configuration("message.yml") {

    var Prefix by string("prefix", "&8[&b国战&8] &r")
    var Reload by string("reload", "&a配置文件已重载")
    var NoPermission by string("no-permission", "&c你没有权限执行此命令")
    var NotOnline by string("not-online", "&c玩家不在线")
    var OnlyPlayer by string("only-player", "&c此命令仅限玩家执行")

    internal object Commands {
        object Create : StaticLazy {
            var Success by string("commands.create.success", "&a成功创建国家")
            var NotEnough by string("commands.create.not-enough", "&c你没有足够的物品")
            var Already by string("commands.create.already", "&c你已经有国家了")
            var NameUsed by string("commands.create.name-used", "&c该名称已被使用")
            var InvalidName by string(
                "commands.create.invalid-name",
                "&c国家名称长度需为3~12个字符，只能包含中英文和数字"
            )
            var InvalidUsage by string("commands.create.invalid-usage", "&c请提供国家名称，例如: /u create 国名>")
            var CityOwned by string("commands.create.city-owned", "&c当前位置区块已被占用，无法创建国家")
        }

        object Info : StaticLazy {
            var NotFound by string("commands.info.not-found", "&c未找到国家 &e%name%")
            var Lines by stringList(
                "commands.info.lines", listOf(
                    "&6国家信息 - &e%name%",
                    "&a君主: &f%owner%",
                    "&a创建时间: &f%created%",
                    "&a疆土区块: &f%territory%",
                    "&a加入方式: &f%join_mode%",
                    "&a邦国护盾: &f%shield%",
                    "&a王城坐标: &f%capital_x%,%capital_y%",
                    "&a国民列表: &f%members%"
                )
            )
            var InvalidUsage by string("commands.info.invalid-usage", "&c请提供国家名称，例如: /u info 国名>")
        }

        object List : StaticLazy {
            var Empty by string("commands.list.empty", "&c当前没有任何国家")
            var Format by string("commands.list.format", "&e[%id%] %name% &7- 国主: %owner% &7城市数: %territory%")
            var PageInfo by string("commands.list.page-info", "&7第 %page% / %total_page% 页")
        }

        object Purge : StaticLazy {
            var Success by string("commands.purge.success", "&a成功清空数据库")
            var Kick by string("commands.purge.kick", "&c数据库已清空，请重新进入服务器")
        }
    }

    init {
        Commands.Create.init()
        Commands.Info.init()
        Commands.List.init()
        Commands.Purge.init()
    }



    val LOOKS_LIKE_MM: Pattern = Pattern.compile(".*<[/a-zA-Z#][^>]*>.*")
    fun String.mini(prefix: Boolean = true): Component {
        if (this.isEmpty()) return Component.empty()


        // 优先：像是 MiniMessage 的就按 MM 解析
        if (LOOKS_LIKE_MM.matcher(this).matches()) {
            try {
                return MiniMessage.miniMessage().deserialize(if (prefix) "$Prefix$this" else this)
            } catch (ignored: Exception) {
                // 解析失败则回退到 legacy
            }
        }
        return LegacyComponentSerializer.legacyAmpersand().deserialize(if (prefix) "$Prefix$this" else this)
    }

    fun List<String>.miniReplace(placeholders: Map<String, String>, prefix: Boolean = true): List<Component> {
        return this.map { line ->
            var msg = line
            placeholders.forEach { (key, value) ->
                msg = msg.replace("%$key%", value)
            }
            msg.mini(prefix)
        }
    }
    
    /**
     * 发送错误消息
     */
    fun CommandSender.sendError(message: String) {
        this.sendMessage("§c$message")
    }
    
    /**
     * 发送信息
     */
    fun CommandSender.sendInfo(message: String) {
        this.sendMessage("§a$message")
    }
    
    /**
     * 发送错误消息
     */
    fun Player.sendError(message: String) {
        this.sendMessage("§c$message")
    }
    
    /**
     * 发送信息
     */
    fun Player.sendInfo(message: String) {
        this.sendMessage("§a$message")
    }

}