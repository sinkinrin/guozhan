package cn.lcofficial.guozhan.task

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.manager.DiplomaticRequestManager
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit

/**
 * 外交请求清理任务
 * 🔧 v1.3.63: 定期清理过期的外交请求
 * 🔧 v1.3.64: 修复Folia线程安全问题 - 使用GlobalRegionScheduler
 */
object DiplomaticRequestCleanupTask {

    private val plugin = Guozhan.instance
    private val pluginLogger = plugin.logger
    private var task: ScheduledTask? = null

    /**
     * 启动定时任务
     * 每小时执行一次清理
     */
    fun start() {
        if (task != null) {
            pluginLogger.warning("[外交请求清理] 任务已经在运行中")
            return
        }

        // 🔧 v1.3.64: 使用Folia的GlobalRegionScheduler，每小时执行一次
        // 72000 ticks = 1小时 (20 ticks/秒 * 60秒 * 60分钟)
        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { _ ->
            try {
                // 清理过期的请求
                val expiredCount = DiplomaticRequestManager.cleanupExpiredRequests()
                if (expiredCount > 0) {
                    pluginLogger.info("[外交请求清理] 清理了 $expiredCount 个过期请求")
                }

                // 检查并通知即将过期的请求
                DiplomaticRequestManager.checkExpiringRequests()

            } catch (e: Exception) {
                pluginLogger.severe("[外交请求清理] 清理任务执行失败: ${e.message}")
                e.printStackTrace()
            }
        }, 72000L, 72000L) // 1小时后首次执行，然后每小时执行一次

        pluginLogger.info("[外交请求清理] 定时任务已启动（每小时执行一次，使用Folia GlobalRegionScheduler）")
    }

    /**
     * 停止定时任务
     */
    fun stop() {
        task?.cancel()
        task = null
        pluginLogger.info("[外交请求清理] 定时任务已停止")
    }
    
    /**
     * 手动执行一次清理
     */
    fun runNow() {
        try {
            val expiredCount = DiplomaticRequestManager.cleanupExpiredRequests()
            pluginLogger.info("[外交请求清理] 手动清理了 $expiredCount 个过期请求")
        } catch (e: Exception) {
            pluginLogger.severe("[外交请求清理] 手动清理失败: ${e.message}")
            e.printStackTrace()
        }
    }
}

