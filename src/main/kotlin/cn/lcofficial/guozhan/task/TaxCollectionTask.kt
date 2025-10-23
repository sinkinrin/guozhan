package cn.lcofficial.guozhan.task

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.economy.RegionalTaxSystem
import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.data.Countries
import cn.lcofficial.guozhan.config.Message
import cn.lcofficial.guozhan.pluginLogger
import cn.lcofficial.guozhan.util.runRepeat
import cn.lcofficial.guozhan.util.async
import cn.lcofficial.guozhan.util.run
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 税收收集任务，定期为各国家收集税收
 * 使用Folia调度器进行定时执行
 */
class TaxCollectionTask {

    // 🔧 v1.3.42: 修复税收状态分裂 - 移除内存Map，使用Country.lastAutoTaxTime数据库字段
    // private val lastTaxCollection = ConcurrentHashMap<UUID, Long>() // 已移除

    // 税收周期（毫秒），默认1小时
    private val taxCycle = 60 * 60 * 1000L

    // Folia调度任务
    private var scheduledTask: ScheduledTask? = null
    
    /**
     * 执行税收收集逻辑
     * 🔧 v1.3.23: 修复数据竞争问题 - 在异步线程中只计算和查询，在主线程中修改Country对象
     */
    private fun executeTaxCollection() {
        val startTime = System.currentTimeMillis()

        // 在异步线程执行数据库查询和计算操作
        async { _ ->
            try {
                val currentTime = System.currentTimeMillis()

                // 🔧 v1.3.48: 修复Critical问题4.2 - 使用新的TaxResult数据类
                val taxResults = mutableMapOf<UUID, cn.lcofficial.guozhan.economy.TaxResult>() // countryId -> TaxResult
                val taxHours = mutableMapOf<UUID, Double>() // countryId -> hours

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

                // 🔧 v1.3.43: 修复税收系统对零领土国家执行冗余计算 - 添加领土数量检查
                var processedCountries = 0
                var skippedZeroTerritoryCountries = 0

                for (country in countries) {
                    // 检查国家是否有领土，没有领土的国家跳过税收计算
                    val territories = cn.lcofficial.guozhan.manager.TerritoryManager.getTerritoriesByCountry(country)
                    if (territories.isEmpty()) {
                        skippedZeroTerritoryCountries++
                        continue
                    }

                    processedCountries++

                    // 🔧 v1.3.42: 修复税收状态分裂 - 使用Country.lastAutoTaxTime数据库字段
                    val lastCollection = country.lastAutoTaxTime

                    // 修复：如果是首次收税（lastCollection为0L），初始化为当前时间并跳过本次收税
                    // 这样避免了从1970年1月1日开始计算税收，防止经济崩溃
                    if (lastCollection == 0L) {
                        // 🔧 v1.3.45: 修复税收收集任务在异步线程中修改共享状态 - 在GlobalRegionScheduler中保存
                        cn.lcofficial.guozhan.util.run {
                            country.lastAutoTaxTime = currentTime
                            country.save()
                        }
                        continue
                    }

                    // 检查是否到达收税时间
                    if (currentTime - lastCollection >= taxCycle) {
                        // 计算经过的小时数
                        val hours = (currentTime - lastCollection) / (60.0 * 60.0 * 1000.0)

                        // 🔧 v1.3.43: 修复税收收集任务在异步线程中直接修改共享状态 - 使用线程安全的计算方法
                        val taxResult = RegionalTaxSystem.calculateTaxWithAccumulation(country, hours)

                        if (taxResult.goldTax > 0 || taxResult.diamondTax > 0) {
                            taxResults[country.id] = taxResult
                            taxHours[country.id] = hours
                        }
                    }
                }

                // 记录性能优化统计
                if (skippedZeroTerritoryCountries > 0) {
                    pluginLogger.info("🔧 v1.3.43: 跳过${skippedZeroTerritoryCountries}个零领土国家，处理${processedCountries}个有领土国家（性能优化：减少冗余计算）")
                }

                val duration = System.currentTimeMillis() - startTime
                if (taxResults.isNotEmpty()) {
                    pluginLogger.info("税收计算完成，共计算 ${taxResults.size} 个国家，耗时 ${duration}ms")
                }

                // 🔧 v1.3.43: 修复税收收集任务在异步线程中直接修改共享状态 - 在GlobalRegionScheduler中应用税收
                if (taxResults.isNotEmpty()) {
                    run { _ ->
                        var processedCountries = 0
                        val notifications = mutableListOf<Triple<UUID, Int, Int>>()

                        for ((countryId, taxResult) in taxResults) {
                            val country = CountryManager.getCountry(countryId) ?: continue

                            // 应用税收计算结果到国家
                            cn.lcofficial.guozhan.economy.RegionalTaxSystem.applyTax(country, taxResult)

                            // 🔧 v1.3.48: 修复问题4 - 更新数据库字段和TaxSystem的taxPolicies时间戳
                            country.lastAutoTaxTime = currentTime
                            country.save()

                            // 🔧 v1.3.48: 修复问题4 - 将新的时间戳推送回TaxSystem.taxPolicies
                            cn.lcofficial.guozhan.economy.TaxSystem.updateLastCollectionTime(country.id, currentTime)

                            // 🔧 v1.3.38: 触发BossBar实时更新，显示税收收入增量
                            cn.lcofficial.guozhan.manager.EconomyBossBarManager.recordTaxIncome(country, taxResult.goldTax, taxResult.diamondTax)

                            // 记录详细日志
                            pluginLogger.info("🔧 [税收收集] 国家 ${country.name} 收集税收: ${taxResult.goldTax} 金币, ${taxResult.diamondTax} 钻石")
                            processedCountries++

                            notifications.add(Triple(countryId, taxResult.goldTax, taxResult.diamondTax))
                        }

                        pluginLogger.info("税收收集完成，处理了 $processedCountries 个国家")

                        // 通知在线成员
                        for ((countryId, goldTax, diamondTax) in notifications) {
                            notifyTaxCollection(countryId, goldTax, diamondTax)
                        }
                    }
                }

            } catch (e: Exception) {
                pluginLogger.severe("税收收集任务执行出错: ${e.message}")
                e.printStackTrace()
            }
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
        
        // 🔧 v1.3.42: 修复税收通知违反Folia线程规则 - 使用EntityScheduler发送玩家消息
        // 发送税收通知
        for (player in onlineMembers) {
            // 使用EntityScheduler确保在正确的线程中发送消息
            player.scheduler.run(cn.lcofficial.guozhan.Guozhan.instance, { _ ->
                player.sendMessage("§a国家税收: §6+$goldTax 金锡 §b+$diamondTax 钻石")
            }, null)
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
        // 🔧 v1.3.42: 修复税收状态分裂 - 使用Country.lastAutoTaxTime数据库字段
        val lastCollection = country.lastAutoTaxTime

        // 修复：如果是首次收税（lastCollection为0L），初始化为当前时间并返回0收入
        // 这样避免了从1970年1月1日开始计算税收，防止经济崩溃
        if (lastCollection == 0L) {
            // 🔧 v1.3.45: 修复税收收集任务在异步线程中修改共享状态 - 在GlobalRegionScheduler中保存
            cn.lcofficial.guozhan.util.run {
                country.lastAutoTaxTime = currentTime
                country.save()
            }
            return Pair(0, 0)
        }

        // 计算经过的小时数
        val hours = (currentTime - lastCollection) / (60.0 * 60.0 * 1000.0)

        // 收集税收
        val result = RegionalTaxSystem.collectTax(country, hours)

        // 🔧 v1.3.48: 修复问题4 - 手动收税时也需要同步时间戳到TaxSystem
        cn.lcofficial.guozhan.util.run {
            // 记录本次收税时间到数据库
            country.lastAutoTaxTime = currentTime
            country.save()

            // 🔧 v1.3.48: 修复问题4 - 将新的时间戳推送回TaxSystem.taxPolicies
            cn.lcofficial.guozhan.economy.TaxSystem.updateLastCollectionTime(country.id, currentTime)

            // 🔧 v1.3.38: 手动收税时也触发BossBar实时更新
            if (result.first > 0 || result.second > 0) {
                cn.lcofficial.guozhan.manager.EconomyBossBarManager.recordTaxIncome(country, result.first, result.second)
            }
        }

        return result
    }
}