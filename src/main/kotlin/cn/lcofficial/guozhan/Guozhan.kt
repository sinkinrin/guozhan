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
import cn.lcofficial.guozhan.listener.RandomSpawnListener
import cn.lcofficial.guozhan.listener.TaxRegionListener
import cn.lcofficial.guozhan.listener.TerritoryListener
import cn.lcofficial.guozhan.listener.WarListener
import cn.lcofficial.guozhan.manager.DataManager
import cn.lcofficial.guozhan.manager.DiplomacyManager
import cn.lcofficial.guozhan.manager.EconomyManager
import cn.lcofficial.guozhan.manager.TerritoryManager
import cn.lcofficial.guozhan.manager.WarManager
import cn.lcofficial.guozhan.task.EconomyTasks
import cn.lcofficial.guozhan.task.LoyaltySystem
import cn.lcofficial.guozhan.task.WarEventScheduler
import cn.lcofficial.guozhan.util.runRepeat
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
        val globalChatCommand = cn.lcofficial.guozhan.command.GlobalChatCommand()
        getCommand("c")!!.setExecutor(globalChatCommand)
        getCommand("c")!!.tabCompleter = globalChatCommand
        val whisperCommand = cn.lcofficial.guozhan.command.WhisperCommand()
        getCommand("w")!!.setExecutor(whisperCommand)
        getCommand("w")!!.tabCompleter = whisperCommand

        // 注册GM命令
        val gmCommand = cn.lcofficial.guozhan.command.GMCommand()
        getCommand("gzgm")!!.setExecutor(gmCommand)
        getCommand("gzgm")!!.tabCompleter = gmCommand
        
        // 注册监听器
        server.pluginManager.registerEvents(PlayerListener, this) // 修复：object类型直接传递
        TerritoryListener.register() // 修复：object类型直接调用
        EconomyListener().register()
        DiplomacyListener().register()
        WarListener().register()
        TaxRegionListener().register() // 修复：class类型需要实例化

        // 注册监听器（避免重复注册）
        RandomSpawnListener().register()
        cn.lcofficial.guozhan.listener.CoreListener().register()
        
        // 启动经济任务
        EconomyTasks.startTasks()
        
        // 启动王战定时器 - 使用Folia调度器
        runRepeat(20L, 1200L) { task ->
            try {
                warScheduler.run()
            } catch (e: Exception) {
                logger.severe("王战定时器执行出错: ${e.message}")
                e.printStackTrace()
            }
        }

        // 启动忠诚度系统 - 使用Folia调度器
        runRepeat(20L, 6000L) { task ->
            try {
                loyaltySystem.run()
            } catch (e: Exception) {
                logger.severe("忠诚度系统执行出错: ${e.message}")
                e.printStackTrace()
            }
        }

        // 启动护盾过期检查任务 - 每5分钟检查一次
        runRepeat(20L * 60L * 5L, 20L * 60L * 5L) { task ->
            try {
                cn.lcofficial.guozhan.manager.ShieldManager.checkExpiredShields()
            } catch (e: Exception) {
                logger.severe("护盾过期检查任务执行出错: ${e.message}")
                e.printStackTrace()
            }
        }

        // 启动疆域地图缓存清理任务
        runRepeat(60000L, 60000L) { // 每分钟清理一次缓存
            cn.lcofficial.guozhan.util.TerritoryMapUtil.cleanupCache()
        }
        
        logger.info("国战插件已启动！")
    }

    fun initialize() {
        pluginLogger.info("初始化中...")
        if (!dataFolder.exists()) dataFolder.mkdir()
        Message.init(this)
        Config.init(this)
        DiplomacyConfig.init(this)
        cn.lcofficial.guozhan.config.TechnologyConfig.init(this)
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

        // v1.3.13修复：初始化高级税收系统
        pluginLogger.info("正在初始化高级税收系统...")
        cn.lcofficial.guozhan.economy.TaxSystem.initialize()
        
        // 初始化税收区域地图
        pluginLogger.info("正在初始化税收区域地图...")
        cn.lcofficial.guozhan.util.TaxRegionMapUtil.initialize()

        // 初始化疆域地图
        pluginLogger.info("正在初始化疆域地图...")
        cn.lcofficial.guozhan.util.TerritoryMapUtil.initialize()

        // 初始化科技系统
        pluginLogger.info("正在初始化科技系统...")
        cn.lcofficial.guozhan.manager.TechnologyManager.initialize()

        // 初始化科技效果管理器
        pluginLogger.info("正在初始化科技效果管理器...")
        cn.lcofficial.guozhan.manager.TechEffectManager.initialize()

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
        // 清理传送管理器资源
        cn.lcofficial.guozhan.manager.TeleportManager.cleanup()

        // 注意：warScheduler现在使用Folia调度器，不需要手动停止
        // 定时任务会在插件禁用时自动停止

        // 注意：loyaltySystem现在使用Folia调度器，不需要手动停止
        // 定时任务会在插件禁用时自动停止

        logger.info("国战插件已关闭！")
    }

    fun Listener.register() = server.pluginManager.registerEvents(this, this@Guozhan)
}