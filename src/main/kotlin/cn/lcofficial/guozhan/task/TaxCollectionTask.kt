package cn.lcofficial.guozhan.task

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.economy.RegionalTaxSystem
import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.data.Countries
import cn.lcofficial.guozhan.config.Message
import cn.lcofficial.guozhan.pluginLogger
import cn.lcofficial.guozhan.util.runRepeat
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

/**
 * 税收收集任务，定期为各国家收集税收
 * 使用Folia调度器进行定时执行
 */
class TaxCollectionTask {

    // 上次收税时间记录
    private val lastTaxCollection = mutableMapOf<UUID, Long>()

    // 税收周期（毫秒），默认1小时
    private val taxCycle = 60 * 60 * 1000L

    // Folia调度任务
    private var scheduledTask: ScheduledTask? = null
    
    /**
     * 执行税收收集逻辑
     */
    private fun executeTaxCollection() {
        try {
            val currentTime = System.currentTimeMillis()
            var processedCountries = 0

            // v1.3.18修复：从数据库查询所有国家，添加UUID验证
            val countries = transaction {
                Countries.selectAll().mapNotNull { row ->
                    try {
                        val idString = row[Countries.id].value
                        // 验证UUID格式
                        if (idString.length != 36) {
                            pluginLogger.warning("[税收系统] 跳过无效的UUID格式: '$idString' (长度: ${idString.length})")
                            return@mapNotNull null
                        }
                        val uuid = UUID.fromString(idString)
                        CountryManager.getCountry(uuid)
                    } catch (e: IllegalArgumentException) {
                        pluginLogger.warning("[税收系统] 跳过无效的UUID: '${row[Countries.id].value}' - ${e.message}")
                        null
                    } catch (e: Exception) {
                        pluginLogger.warning("[税收系统] 获取国家时出错: ${e.message}")
                        null
                    }
                }.filterNotNull()
            }

            for (country in countries) {
                val lastCollection = lastTaxCollection[country.id] ?: 0L

                // 修复：如果是首次收税（lastCollection为0L），初始化为当前时间并跳过本次收税
                // 这样避免了从1970年1月1日开始计算税收，防止经济崩溃
                if (lastCollection == 0L) {
                    lastTaxCollection[country.id] = currentTime
                    continue
                }

                // 检查是否到达收税时间
                if (currentTime - lastCollection >= taxCycle) {
                    // 计算经过的小时数
                    val hours = (currentTime - lastCollection) / (60.0 * 60.0 * 1000.0)

                    // 收集税收
                    val (goldTax, diamondTax) = RegionalTaxSystem.collectTax(country, hours)

                    // 记录本次收税时间
                    lastTaxCollection[country.id] = currentTime

                    // 通知在线国民
                    notifyTaxCollection(country.id, goldTax, diamondTax)

                    // 记录日志
                    pluginLogger.info("为国家 ${country.name} 收集税收: $goldTax 金锡, $diamondTax 钻石")
                    processedCountries++
                }
            }

            if (processedCountries > 0) {
                pluginLogger.info("税收收集完成，处理了 $processedCountries 个国家")
            }

        } catch (e: Exception) {
            pluginLogger.severe("税收收集任务执行出错: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 通知国家在线成员税收情况
     */
    private fun notifyTaxCollection(countryId: UUID, goldTax: Int, diamondTax: Int) {
        if (goldTax <= 0 && diamondTax <= 0) return
        
        // 获取国家在线成员
        val country = CountryManager.getCountry(countryId) ?: return
        val onlineMembers = country.members.mapNotNull { Bukkit.getPlayer(it.uniqueId) }
        
        // 发送税收通知
        for (player in onlineMembers) {
            player.sendMessage("§a国家税收: §6+$goldTax 金锡 §b+$diamondTax 钻石")
        }
    }
    
    /**
     * 启动税收任务
     * 使用Folia的GlobalRegionScheduler
     */
    fun start() {
        // 停止现有任务
        scheduledTask?.cancel()

        // 每分钟检查一次 - 使用Folia调度器
        scheduledTask = runRepeat(20L * 60L, 20L * 60L) { task ->
            executeTaxCollection()
        }

        pluginLogger.info("税收系统已启动 (Folia GlobalRegionScheduler)，税收周期: ${taxCycle / (60 * 1000)} 分钟")
    }

    /**
     * 停止税收任务
     */
    fun stop() {
        scheduledTask?.cancel()
        scheduledTask = null
        pluginLogger.info("税收系统已停止")
    }
    
    /**
     * 手动触发税收收集
     * @param countryId 国家ID
     * @return Pair(收集的金锭, 收集的钻石)
     */
    fun forceTaxCollection(countryId: UUID): Pair<Int, Int> {
        val country = CountryManager.getCountry(countryId) ?: return Pair(0, 0)
        val currentTime = System.currentTimeMillis()
        val lastCollection = lastTaxCollection[country.id] ?: 0L

        // 修复：如果是首次收税（lastCollection为0L），初始化为当前时间并返回0收入
        // 这样避免了从1970年1月1日开始计算税收，防止经济崩溃
        if (lastCollection == 0L) {
            lastTaxCollection[country.id] = currentTime
            return Pair(0, 0)
        }

        // 计算经过的小时数
        val hours = (currentTime - lastCollection) / (60.0 * 60.0 * 1000.0)
        
        // 收集税收
        val result = RegionalTaxSystem.collectTax(country, hours)
        
        // 记录本次收税时间
        lastTaxCollection[country.id] = currentTime
        
        return result
    }
}