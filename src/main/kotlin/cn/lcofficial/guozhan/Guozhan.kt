package cn.lcofficial.guozhan

import cn.lcofficial.guozhan.command.GuozhanCommand
import cn.lcofficial.guozhan.config.Config
import cn.lcofficial.guozhan.config.DiplomacyConfig
import cn.lcofficial.guozhan.config.Message
import cn.lcofficial.guozhan.effect.WarEffects
import cn.lcofficial.guozhan.integration.SquaremapIntegration
import cn.lcofficial.guozhan.listener.DiplomacyListener
import cn.lcofficial.guozhan.listener.EconomyListener
import cn.lcofficial.guozhan.listener.PlayerListener
import cn.lcofficial.guozhan.listener.WarListener
import cn.lcofficial.guozhan.manager.DataManager
import cn.lcofficial.guozhan.manager.DiplomacyManager
import cn.lcofficial.guozhan.manager.EconomyManager
import cn.lcofficial.guozhan.manager.TerritoryManager
import cn.lcofficial.guozhan.manager.WarManager
import cn.lcofficial.guozhan.task.EconomyTasks
import cn.lcofficial.guozhan.task.LoyaltySystem
import cn.lcofficial.guozhan.task.WarEventScheduler
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Logger

lateinit var pluginLogger: Logger
lateinit var plugin: Guozhan

class Guozhan : JavaPlugin() {
    val squaremapIntegration = SquaremapIntegration()
    val warScheduler = WarEventScheduler()
    val loyaltySystem = LoyaltySystem()
    
    companion object {
        lateinit var instance: Guozhan
            private set
    }
    
    init {
        pluginLogger = logger
        plugin = this
        instance = this
    }
    
    override fun onEnable() {
        initialize()
        // 注册主命令
        getCommand("u")!!.setExecutor(GuozhanCommand)
        getCommand("u")!!.tabCompleter = GuozhanCommand
        
        // 注册贡献命令
        val tributeCommand = cn.lcofficial.guozhan.command.TributeCommand()
        getCommand("tribute")!!.setExecutor(tributeCommand)
        getCommand("tribute")!!.tabCompleter = tributeCommand
        
        // 注册税收命令
        val taxCommand = cn.lcofficial.guozhan.command.TaxCommand()
        getCommand("tax")!!.setExecutor(taxCommand)
        getCommand("tax")!!.tabCompleter = taxCommand
        
        // 注册聊天命令
        getCommand("c")!!.setExecutor(cn.lcofficial.guozhan.command.GlobalChatCommand())
        val whisperCommand = cn.lcofficial.guozhan.command.WhisperCommand()
        getCommand("w")!!.setExecutor(whisperCommand)
        getCommand("w")!!.tabCompleter = whisperCommand
        
        // 注册监听器
        PlayerListener().register()
        cn.lcofficial.guozhan.listener.TerritoryListener().register()
        EconomyListener().register()
        DiplomacyListener().register()
        WarListener().register()
        cn.lcofficial.guozhan.listener.TaxRegionListener().register()
        cn.lcofficial.guozhan.listener.CoreListener().register()
        
        // 启动经济任务
        EconomyTasks.startTasks()
        
        // 启动王战定时器
        warScheduler.runTaskTimer(this, 20L, 1200L) // 每分钟检查一次
        
        // 启动忠诚度系统
        loyaltySystem.runTaskTimer(this, 20L, 6000L) // 每5分钟检查一次
        
        logger.info("国战插件已启动！")
    }

    fun initialize() {
        pluginLogger.info("初始化中...")
        if (!dataFolder.exists()) dataFolder.mkdir()
        Message.init(this)
        Config.init(this)
        DiplomacyConfig.init(this)
        DataManager.init(this)
        
        // 初始化领土管理器
        pluginLogger.info("正在初始化领土管理系统...")
        TerritoryManager
        
        // 初始化经济管理器
        pluginLogger.info("正在初始化经济管理器...")
        EconomyManager
        
        // 初始化外交管理器
        pluginLogger.info("正在初始化外交管理器...")
        DiplomacyManager
        
        // 初始化战争管理器
        pluginLogger.info("正在初始化战争管理器...")
        WarManager
        
        // 初始化战争效果系统
        pluginLogger.info("正在初始化战争效果系统...")
        WarEffects
        
        // 初始化核心管理器
        pluginLogger.info("正在初始化核心管理器...")
        cn.lcofficial.guozhan.manager.CoreManager.initialize()
        
        // 初始化聊天管理器
        pluginLogger.info("正在初始化聊天管理器...")
        cn.lcofficial.guozhan.manager.ChatManager.initialize()
        
        // 初始化贡献系统
        pluginLogger.info("正在初始化贡献系统...")
        cn.lcofficial.guozhan.economy.TributeSystem.initialize()
        
        // 初始化税收系统
        pluginLogger.info("正在初始化区域税收系统...")
        cn.lcofficial.guozhan.economy.RegionalTaxSystem
        
        // 初始化税收区域地图
        pluginLogger.info("正在初始化税收区域地图...")
        cn.lcofficial.guozhan.util.TaxRegionMapUtil.initialize()
        
        // 初始化Squaremap集成
        squaremapIntegration.initialize()
        
        // 初始化PlaceholderAPI集成
        if (server.pluginManager.getPlugin("PlaceholderAPI") != null) {
            pluginLogger.info("正在初始化PlaceholderAPI集成...")
            cn.lcofficial.guozhan.integration.GuozhanPlaceholderExpansion().register()
            pluginLogger.info("PlaceholderAPI集成已启用")
        } else {
            pluginLogger.warning("PlaceholderAPI插件未找到，占位符功能将不可用")
        }
    }

    override fun onDisable() {
        // 清理核心管理器资源
        cn.lcofficial.guozhan.manager.CoreManager.cleanup()
        // 清理聊天管理器资源
        cn.lcofficial.guozhan.manager.ChatManager.cleanup()
        
        // 停止定时任务
        warScheduler.cancel()
        loyaltySystem.cancel()
        
        logger.info("国战插件已关闭！")
    }

    fun Listener.register() = server.pluginManager.registerEvents(this, this@Guozhan)
}