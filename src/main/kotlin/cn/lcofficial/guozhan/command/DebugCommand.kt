package cn.lcofficial.guozhan.command

import cn.lcofficial.guozhan.config.Message.sendError
import cn.lcofficial.guozhan.config.Message.sendInfo
import cn.lcofficial.guozhan.config.Message.sendPermissionError
import cn.lcofficial.guozhan.config.Message.sendUsage
import cn.lcofficial.guozhan.debug.DebugVisualizationFormatter
import cn.lcofficial.guozhan.debug.DebugVisualizationManager
import cn.lcofficial.guozhan.debug.DebugVisualizationManager.DebugVisualizationRequest
import cn.lcofficial.guozhan.debug.DebugVisualizationManager.ResultStatus
import cn.lcofficial.guozhan.debug.DebugVisualizationManager.VisualizationDefinition
import cn.lcofficial.guozhan.util.run
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor

class DebugCommand : TabExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("guozhan.admin.debug")) {
            sender.sendPermissionError("guozhan.admin.debug")
            return true
        }

        if (!DebugVisualizationManager.isEnabled()) {
            sender.sendError("数据可视化调试已在配置中禁用，请启用 config.yml 内的 debug.data-visualization.enabled 后重载插件。")
            return true
        }

        if (args.isEmpty()) {
            sendUsage(sender)
            return true
        }

        val primary = args[0].lowercase()
        when (primary) {
            "list" -> {
                dispatchList(sender)
                return true
            }

            "debug", "visualize" -> {
                if (args.size == 1) {
                    sendUsage(sender)
                    return true
                }
                val secondary = args[1].lowercase()
                if (secondary == "list") {
                    dispatchList(sender)
                    return true
                }
                executeCategory(sender, secondary, args.drop(2))
                return true
            }

            else -> {
                // Allow shorthand `/gz <category>` for convenience.
                executeCategory(sender, primary, args.drop(1))
                return true
            }
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        if (!sender.hasPermission("guozhan.admin.debug")) {
            return mutableListOf()
        }

        if (args.isEmpty()) return mutableListOf()

        return when (args.size) {
            1 -> listOf("debug", "visualize", "list")
                .filter { it.startsWith(args[0], ignoreCase = true) }
                .toMutableList()

            2 -> {
                val primary = args[0].lowercase()
                if (primary == "debug" || primary == "visualize") {
                    val options = mutableListOf("list")
                    DebugVisualizationManager.listDefinitions().forEach { definition ->
                        options += definition.key
                        options += definition.aliases
                    }
                    options.filter { it.startsWith(args[1], ignoreCase = true) }.distinct().toMutableList()
                } else {
                    mutableListOf()
                }
            }

            else -> mutableListOf()
        }
    }

    private fun executeCategory(sender: CommandSender, token: String, arguments: List<String>) {
        val definition = DebugVisualizationManager.resolveDefinition(token)
        if (definition == null) {
            sender.sendError("未知的可视化类别: $token")
            sender.sendInfo("使用 /gz debug list 查看支持的类别。")
            return
        }

        if (definition.requiresArgument && arguments.isEmpty()) {
            val hint = definition.argumentHint ?: "<参数>"
            sender.sendUsage("/gz debug ${definition.key} $hint")
            return
        }

        sendInfoHeader(sender, definition)

        val request = DebugVisualizationRequest(
            sender = sender,
            args = arguments,
            rawCategoryToken = token
        )

        DebugVisualizationManager.execute(definition, request).thenAccept { result ->
            run { _ ->
                result.lines.forEach(sender::sendMessage)
                result.warnings.forEach { warning ->
                    sender.sendMessage(DebugVisualizationFormatter.warn(warning))
                }
                when (result.status) {
                    ResultStatus.PLANNED -> sender.sendInfo("该数据视图仍在规划中，详见文档获取最新进度。")
                    ResultStatus.ERROR -> sender.sendError("执行过程中出现异常，详情请查看后台日志。")
                    else -> Unit
                }
            }
        }
    }

    private fun dispatchList(sender: CommandSender) {
        DebugVisualizationManager.buildCategoryList().forEach(sender::sendMessage)
    }

    private fun sendUsage(sender: CommandSender) {
        sender.sendUsage("/gz debug <category>", "执行指定类别的数据可视化，使用 /gz debug list 查看所有类别。")
    }

    private fun sendInfoHeader(sender: CommandSender, definition: VisualizationDefinition) {
        sender.sendInfo("正在生成 ${definition.title} ...")
    }
}
