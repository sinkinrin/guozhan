package cn.lcofficial.guozhan.task

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.data.Country
import cn.lcofficial.guozhan.data.ResourceType
import cn.lcofficial.guozhan.economy.RegionalTaxSystem
import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.manager.EconomyManager
import cn.lcofficial.guozhan.manager.TerritoryManager
import cn.lcofficial.guozhan.util.runRepeat
import cn.lcofficial.guozhan.util.async
import cn.lcofficial.guozhan.util.run
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import java.util.UUID

/**
 * 经济系统相关的定时任务
 */
object EconomyTasks {
    
    // 自动收税间隔（ticks）
    private const val AUTO_TAX_INTERVAL = 20 * 60 * 60 * 24 // 24小时
    
    // 区域税收间隔（ticks）
    private const val REGIONAL_TAX_INTERVAL = 20 * 60 * 60 // 1小时
    
    // 资源生成间隔（ticks）
    private const val RESOURCE_GENERATION_INTERVAL = 20 * 60 * 60 * 3 // 3小时
    
    // 区域税收任务实例
    private lateinit var taxCollectionTask: TaxCollectionTask
    
    /**
     * 启动所有经济相关的定时任务
     * 使用Folia的GlobalRegionScheduler进行全局定时任务
     */
    fun startTasks() {
        val plugin = Guozhan.instance

        // 启动自动收税任务 - 使用Folia调度器
        runRepeat(AUTO_TAX_INTERVAL.toLong(), AUTO_TAX_INTERVAL.toLong()) { task ->
            try {
                collectTaxes()
            } catch (e: Exception) {
                plugin.logger.severe("自动收税任务执行出错: ${e.message}")
                e.printStackTrace()
            }
        }

        // 启动区域税收任务
        taxCollectionTask = TaxCollectionTask()
        taxCollectionTask.start()

        // 启动资源生成任务 - 使用Folia调度器
        runRepeat(RESOURCE_GENERATION_INTERVAL.toLong(), RESOURCE_GENERATION_INTERVAL.toLong()) { task ->
            try {
                generateResources()
            } catch (e: Exception) {
                plugin.logger.severe("资源生成任务执行出错: ${e.message}")
                e.printStackTrace()
            }
        }

        plugin.logger.info("经济系统定时任务已启动(Folia GlobalRegionScheduler)")
    }

    /**
     * 停止所有经济相关的定时任务
     * 🔧 v1.3.39: 修复资源清理不完整 - 插件卸载时调用
     */
    fun stopTasks() {
        if (::taxCollectionTask.isInitialized) {
            taxCollectionTask.stop()
        }
        Guozhan.instance.logger.info("经济系统定时任务已停止")
    }
    
    /**
     * 自动收税任务
     * 🔧 v1.3.23: 修复数据竞争问题 - 在异步线程中只计算，在主线程中修改Country对象
     */
    private fun collectTaxes() {
        val plugin = Guozhan.instance
        val startTime = System.currentTimeMillis()

        // 在异步线程执行计算操作（只读）
        async { _ ->
            try {
                plugin.logger.info("正在执行自动收税...")

                // 获取所有国家（从缓存，无数据库操作）
                val countries = CountryManager.countries.values.toList()

                // 收集税收计算结果
                val taxResults = mutableMapOf<UUID, Int>()
                // 🔧 v1.3.50: 存储完整的税收计算结果用于应用累积
                val taxCalculationResults = mutableMapOf<UUID, cn.lcofficial.guozhan.economy.TaxResult>()

                for (country in countries) {
                    // 跳过税率为0的国家
                    if (EconomyManager.getTaxRate(country) <= 0) continue

                    // 🔧 v1.3.50: 修复每日自动税收从不累积小额国家的小数收入
                    // 始终调用calculateTaxWithAccumulation()并存储余数，不再基于taxAmount > 0判断
                    val taxResult = RegionalTaxSystem.calculateTaxWithAccumulation(country, 24.0) // 24小时

                    // 即使整数部分为0，也要记录结果以便累积小数部分
                    taxResults[country.id] = taxResult.goldTax + (taxResult.diamondTax * 10)

                    // 存储完整的税收结果用于后续应用
                    taxCalculationResults[country.id] = taxResult
                }

                val duration = System.currentTimeMillis() - startTime
                plugin.logger.info("自动收税计算完成，共计算 ${taxResults.size} 个国家，耗时 ${duration}ms")

                // 🔧 v1.3.50: 始终处理税收结果，即使整数部分为0也要累积小数部分
                run { _ ->
                    var totalCollected = 0
                    val notifications = mutableListOf<Pair<UUID, Int>>()

                    for ((countryId, taxResult) in taxCalculationResults) {
                        val country = CountryManager.getCountry(countryId) ?: continue

                        // 🔧 v1.3.50: 使用预计算的税收结果，确保小数累积正确应用
                        RegionalTaxSystem.applyTax(country, taxResult)

                        val actualTaxAmount = taxResult.goldTax + (taxResult.diamondTax * 10)
                        totalCollected += actualTaxAmount

                        // 只有实际收取税收时才发送通知
                        if (actualTaxAmount > 0) {
                            notifications.add(countryId to actualTaxAmount)
                        }

                        // 记录详细日志（包括小数累积情况）
                        plugin.logger.info("自动收税：国家${country.name} 收取24小时税收，金币${taxResult.goldTax}，钻石${taxResult.diamondTax}，累积金币${taxResult.newAccumulatedGoldTax}，累积钻石${taxResult.newAccumulatedDiamondTax}")
                    }

                    plugin.logger.info("自动收税完成，共收取 $totalCollected 金币，处理${taxCalculationResults.size} 个国家，通知 ${notifications.size} 个国家")

                    // 通知在线成员
                    for ((countryId, taxAmount) in notifications) {
                        val country = CountryManager.getCountry(countryId) ?: continue

                        // 获取国家的所有在线成员
                        val onlineMembers = Bukkit.getOnlinePlayers().filter { player ->
                            val user = player.user()
                            user?.country?.id == countryId
                        }

                        // 🔧 v1.3.42: 修复税收通知违反Folia线程规则 - 使用EntityScheduler发送玩家消息
                        // 通知在线成员
                        for (player in onlineMembers) {
                            // 使用EntityScheduler确保在正确的线程中发送消息
                            player.scheduler.run(cn.lcofficial.guozhan.Guozhan.instance, { _ ->
                                player.sendMessage("§6[国家税收] §a您的国家已自动收取税收 §f$taxAmount §a金币")
                            }, null)
                        }
                    }
                }

            } catch (e: Exception) {
                plugin.logger.severe("自动收税任务执行出错: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * 计算国家的税收金额（只读操作，不修改Country对象）
     * 🔧 v1.3.38修复：统一税收系统 - 使用RegionalTaxSystem进行统一计算
     */
    private fun calculateTaxAmount(country: Country): Int {
        // 🔧 v1.3.38修复：使用RegionalTaxSystem进行统一的税收计算
        // 计算1小时的税收作为基础
        val (goldTax, diamondTax) = RegionalTaxSystem.calculateTax(country, 1.0)

        // 返回总税收（钻石10:1转换为金币等价值）
        return goldTax + (diamondTax * 10)
    }
    
    /**
     * 资源生成任务
     * 🔧 v1.3.23: 修复数据竞争问题 - 在异步线程中只计算，在主线程中修改Country对象
     */
    private fun generateResources() {
        val plugin = Guozhan.instance
        val startTime = System.currentTimeMillis()

        // 在异步线程执行计算操作（只读）
        async { _ ->
            try {
                plugin.logger.info("正在生成资源...")

                // 获取所有领土（从缓存，无数据库操作）
                val territories = TerritoryManager.territories.values.toList()

                // 按国家分组
                val territoriesByCountry = territories.filter { it.isOwned() }.groupBy { it.owner!! }

                // 收集资源生成结果
                val resourceResults = mutableMapOf<UUID, Pair<Int, Int>>() // countryId -> (gold, diamond)

                for ((country, countryTerritories) in territoriesByCountry) {
                    // 计算每种资源的领土数量
                    val resourceCounts = countryTerritories.groupBy { it.resourceType }.mapValues { it.value.size }

                    // 计算生成的资源
                    val goldGenerated = (resourceCounts[ResourceType.GOLD] ?: 0) * 5 // 每个金矿领土生成5金币
                    val diamondGenerated = (resourceCounts[ResourceType.DIAMOND] ?: 0) * 2 // 每个钻石领土生成2钻石

                    if (goldGenerated > 0 || diamondGenerated > 0) {
                        resourceResults[country.id] = Pair(goldGenerated, diamondGenerated)
                    }
                }

                val duration = System.currentTimeMillis() - startTime
                plugin.logger.info("资源生成计算完成，共计算 ${resourceResults.size} 个国家，耗时 ${duration}ms")

                // 将修改操作投递回主线程
                if (resourceResults.isNotEmpty()) {
                    run { _ ->
                        var totalGoldGenerated = 0
                        var totalDiamondGenerated = 0
                        val notifications = mutableListOf<Triple<UUID, Int, Int>>()

                        for ((countryId, resources) in resourceResults) {
                            val country = CountryManager.getCountry(countryId) ?: continue
                            val (goldGenerated, diamondGenerated) = resources

                            // 在主线程中修改Country对象并保存
                            country.gold += goldGenerated
                            country.diamond += diamondGenerated
                            country.save()

                            totalGoldGenerated += goldGenerated
                            totalDiamondGenerated += diamondGenerated
                            notifications.add(Triple(countryId, goldGenerated, diamondGenerated))
                        }

                        plugin.logger.info("资源生成完成，共生成 $totalGoldGenerated 金币，$totalDiamondGenerated 钻石")

                        // 通知在线成员
                        for ((countryId, goldGenerated, diamondGenerated) in notifications) {
                            // 获取国家的所有在线成员
                            val onlineMembers = Bukkit.getOnlinePlayers().filter { player ->
                                val user = player.user()
                                user?.country?.id == countryId
                            }

                            for (player in onlineMembers) {
                                player.sendMessage("§6[资源生成] §a您的国家领土生成了资源")
                                if (goldGenerated > 0) {
                                    player.sendMessage("  §e- 黄金: §f$goldGenerated")
                                }
                                if (diamondGenerated > 0) {
                                    player.sendMessage("  §b- 钻石: §f$diamondGenerated")
                                }
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                plugin.logger.severe("资源生成任务执行出错: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}

/**
 * 获取玩家的用户数据
 */
private fun org.bukkit.entity.Player.user() = cn.lcofficial.guozhan.manager.UserManager.getUser(this.uniqueId)
