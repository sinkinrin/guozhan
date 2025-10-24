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
import cn.lcofficial.guozhan.manager.UserManager
import cn.lcofficial.guozhan.manager.WarManager
import cn.lcofficial.guozhan.manager.WarScoreBossBarManager
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
        // 🔧 v1.3.52: 修复数据库连接失败后插件仍继续运行 - 捕获初始化异常并禁用插件
        try {
            initialize()
        } catch (e: IllegalStateException) {
            logger.severe("插件初始化失败：${e.message}")
            logger.severe("插件将被禁用，请修复配置后重新启动服务器")
            server.pluginManager.disablePlugin(this)
            return
        } catch (e: Exception) {
            logger.severe("插件初始化时发生未知错误：${e.message}")
            e.printStackTrace()
            server.pluginManager.disablePlugin(this)
            return
        }

        // 注册主命�?
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
        
        // 注册监听�?
        server.pluginManager.registerEvents(PlayerListener, this) // 修复：object类型直接传�?
        TerritoryListener.register() // 修复：object类型直接调用
        EconomyListener().register()
        DiplomacyListener().register()
        WarListener().register()
        TaxRegionListener().register() // 修复：class类型需要实例化

        // 注册监听器（避免重复注册�?
        RandomSpawnListener().register()
        cn.lcofficial.guozhan.listener.CoreListener().register()

        // 🔧 v1.3.28: 注册世界加载/卸载监听器，支持动态加载的世界
        server.pluginManager.registerEvents(cn.lcofficial.guozhan.listener.WorldListener(), this)
        
        // 启动经济任务
        EconomyTasks.startTasks()
        
        // 启动王战定时�?- 使用Folia调度�?
        runRepeat(20L, 1200L) { task ->
            try {
                warScheduler.run()
            } catch (e: Exception) {
                logger.severe("王战定时器执行出�? ${e.message}")
                e.printStackTrace()
            }
        }

        // 启动忠诚度系�?- 使用Folia调度�?
        runRepeat(20L, 6000L) { task ->
            try {
                loyaltySystem.run()
            } catch (e: Exception) {
                logger.severe("忠诚度系统执行出�? ${e.message}")
                e.printStackTrace()
            }
        }

        // 启动护盾过期检查任�?- �?分钟检查一�?
        runRepeat(20L * 60L * 5L, 20L * 60L * 5L) { task ->
            try {
                cn.lcofficial.guozhan.manager.ShieldManager.checkExpiredShields()
            } catch (e: Exception) {
                logger.severe("护盾过期检查任务执行出�? ${e.message}")
                e.printStackTrace()
            }
        }

        // 启动疆域地图缓存清理任务
        // 🔧 v1.3.31: 修复领土地图缓存清理间隔错误 - 使用正确的刻�?
        // 1200L �?= 60 �?= 1 分钟�?0�?= 1秒）
        runRepeat(1200L, 1200L) { // 每分钟清理一次缓�?
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

        // 🔧 v1.3.18修复：预热缓�?- 在所有Manager初始化前加载数据到内存缓�?
        pluginLogger.info("正在预热缓存...")
        cn.lcofficial.guozhan.manager.CountryManager.reloadAll()
        cn.lcofficial.guozhan.manager.TerritoryManager.loadAll()

        // 🔧 v1.3.21: 初始化成员缓�?- 避免频繁查询数据�?
        pluginLogger.info("正在初始化成员缓�?..")
        cn.lcofficial.guozhan.manager.CountryManager.initializeMemberCache()

        // 🔧 v1.3.25: 预加载领土和用户数据 - 避免运行时数据库阻塞
        pluginLogger.info("正在预加载领土和用户数据...")
        cn.lcofficial.guozhan.manager.TerritoryManager.preloadTerritories()
        cn.lcofficial.guozhan.manager.UserManager.preloadUsers()

        // 初始化领土管理器
        pluginLogger.info("正在初始化领土管理系�?..")
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
        
        // 初始化战争效果系�?
        pluginLogger.info("正在初始化战争效果系�?..")
        WarEffects
        
        // 初始化核心管理器
        pluginLogger.info("正在初始化核心管理器...")
        cn.lcofficial.guozhan.manager.CoreManager.initialize()

        // 初始化经济BossBar管理�?
        pluginLogger.info("正在初始化经济BossBar管理�?..")
        cn.lcofficial.guozhan.manager.EconomyBossBarManager.initialize()

        // 初始化聊天管理器
        pluginLogger.info("正在初始化聊天管理器...")
        cn.lcofficial.guozhan.manager.ChatManager.initialize()
        
        // 初始化贡献系�?
        pluginLogger.info("正在初始化贡献系�?..")
        cn.lcofficial.guozhan.economy.TributeSystem.initialize()
        
        // 初始化税收系�?
        // 🔧 v1.3.31: 修复税收区域调整被绕过的问题 - 初始化税收区域配�?
        pluginLogger.info("正在初始化区域税收系�?..")
        cn.lcofficial.guozhan.economy.RegionalTaxSystem.initialize()

        // v1.3.13修复：初始化高级税收系统
        pluginLogger.info("正在初始化高级税收系�?..")
        cn.lcofficial.guozhan.economy.TaxSystem.initialize()
        
        // 初始化税收区域地�?
        pluginLogger.info("正在初始化税收区域地�?..")
        cn.lcofficial.guozhan.util.TaxRegionMapUtil.initialize()

        // 初始化疆域地�?
        pluginLogger.info("正在初始化疆域地�?..")
        cn.lcofficial.guozhan.util.TerritoryMapUtil.initialize()

        // 初始化科技系统
        pluginLogger.info("正在初始化科技系统...")
        cn.lcofficial.guozhan.manager.TechnologyManager.initialize()

        // 初始化科技效果管理�?
        pluginLogger.info("正在初始化科技效果管理�?..")
        cn.lcofficial.guozhan.manager.TechEffectManager.initialize()

        // 🔧 v1.3.48: 恢复占领进度
        pluginLogger.info("正在恢复占领进度...")
        cn.lcofficial.guozhan.manager.ClaimManager.restoreClaimProgress()

        // 🔧 v1.3.52: 初始化战争调度器（恢复战争状态）
        pluginLogger.info("正在初始化战争调度器...")
        warScheduler.initialize()

        // 初始化Squaremap集成
        squaremapIntegration.initialize()
        
        // 初始化PlaceholderAPI集成
        if (server.pluginManager.getPlugin("PlaceholderAPI") != null) {
            pluginLogger.info("PlaceholderAPI detected, registering expansion...")
            cn.lcofficial.guozhan.integration.GuozhanPlaceholderExpansion().register()
            pluginLogger.info("PlaceholderAPI integration enabled")
        } else {
            pluginLogger.warning("PlaceholderAPI plugin not found; placeholders disabled")
        }

    }
    override fun onDisable() {
        // 🔧 v1.3.48: 强制保存所有待保存数据，防止数据丢�?
        logger.info("正在强制保存所有待保存数据...")

        try {
            // 保存所有活跃的占领进度
            cn.lcofficial.guozhan.manager.ClaimManager.getAllActiveClaims().forEach { claim ->
                claim.claimProgress.save(async = false)
            }

            // 强制保存所有待保存的领土数据（同步保存�?
            loyaltySystem.pendingSaveTerritories.forEach { territory ->
                try {
                    territory.save(async = false)
                } catch (e: Exception) {
                    logger.severe("强制保存领土数据失败: ${e.message}")
                }
            }

            // 额外确保所有领土和国家数据已写入磁盘，防止重启丢失
            cn.lcofficial.guozhan.manager.TerritoryManager.territories.values.forEach { territory ->
                try {
                    territory.save(async = false)
                } catch (e: Exception) {
                    logger.severe("保存领土缓存到数据库失败: ${e.message}")
                }
            }

            cn.lcofficial.guozhan.manager.CountryManager.countries.values.forEach { country ->
                try {
                    country.save()
                } catch (e: Exception) {
                    logger.severe("保存国家 ${country.name} 数据失败: ${e.message}")
                }
            }

            UserManager.users.values.forEach { user ->
                if (!user.save(async = false)) {
                    logger.severe("保存玩家 ${user.name} 数据失败")
                }
            }

            logger.info("数据强制保存完成")
        } catch (e: Exception) {
            logger.severe("强制保存数据时出�? ${e.message}")
            e.printStackTrace()
        }

        // 清理核心管理器资�?
        cn.lcofficial.guozhan.manager.CoreManager.cleanup()
        // 清理聊天管理器资�?
        cn.lcofficial.guozhan.manager.ChatManager.cleanup()
        // 清理传送管理器资源
        cn.lcofficial.guozhan.manager.TeleportManager.cleanup()

        // 🔧 v1.3.24: 清理 Squaremap 集成资源
        squaremapIntegration.cleanup()

        // 🔧 v1.3.39: 修复资源清理不完�?- 添加缺失的cleanup调用
        // 清理经济BossBar管理�?
        cn.lcofficial.guozhan.manager.EconomyBossBarManager.cleanup()
        WarScoreBossBarManager.cleanup()

        // 停止税收任务
        cn.lcofficial.guozhan.task.EconomyTasks.stopTasks()

        // 关闭数据库连接池
        cn.lcofficial.guozhan.manager.DataManager.shutdown()

        // 注意：warScheduler现在使用Folia调度器，不需要手动停�?
        // 定时任务会在插件禁用时自动停�?

        // 注意：loyaltySystem现在使用Folia调度器，不需要手动停�?
        // 定时任务会在插件禁用时自动停�?

        logger.info("国战插件已关闭！")
    }

    fun Listener.register() = server.pluginManager.registerEvents(this, this@Guozhan)
}
