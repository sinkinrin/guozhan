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
import java.util.concurrent.atomic.AtomicBoolean

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

    // 🔧 v1.3.66: 修复High问题2 - 税收任务竞态条件锁
    // 确保同一时间只有一个税收任务在执行，防止累积值被覆盖
    internal val taxCollectionLock = AtomicBoolean(false)
    
    /**
     * 启动所有经济相关的定时任务
     * 使用Folia的GlobalRegionScheduler进行全局定时任务
     */
    fun startTasks() {
        val plugin = Guozhan.instance

        // 🔧 v1.3.68: 修复Critical问题1 - 移除每日自动收税任务，避免与小时任务重复结算
        // 每日任务直接按24小时计算税收，无视lastAutoTaxTime，与小时任务叠加导致重复结算
        // 现在只保留小时任务（TaxCollectionTask），确保税收计算基于真实时间戳
        // runRepeat(AUTO_TAX_INTERVAL.toLong(), AUTO_TAX_INTERVAL.toLong()) { task ->
        //     try {
        //         collectTaxes()
        //     } catch (e: Exception) {
        //         plugin.logger.severe("自动收税任务执行出错: ${e.message}")
        //         e.printStackTrace()
        //     }
        // }

        // 启动区域税收任务（每小时）
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

        plugin.logger.info("🔧 v1.3.68: 经济系统定时任务已启动 - 仅启用小时税收任务，已移除每日重复结算任务")
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
     * 🔧 v1.3.52: 修复数据竞态 - 在主线程创建不可变快照，避免异步线程访问可变Country字段
     * 🔧 v1.3.66: 修复High问题2 - 添加锁机制防止与TaxCollectionTask竞态
     */
    private fun collectTaxes() {
        val plugin = Guozhan.instance

        // 🔧 v1.3.66: 检查是否有其他税收任务正在执行
        if (!taxCollectionLock.compareAndSet(false, true)) {
            plugin.logger.warning("🔧 [税收系统] 每日税收任务跳过：检测到其他税收任务正在执行，避免竞态条件")
            return
        }

        val startTime = System.currentTimeMillis()

        // 🔧 v1.3.52: 在主线程创建Country数据的不可变快照
        data class CountryTaxSnapshot(
            val id: UUID,
            val name: String,
            val taxRate: Int,
            val accumulatedGoldTax: Double,
            val accumulatedDiamondTax: Double
        )

        val countrySnapshots = CountryManager.countries.values.map { country ->
            CountryTaxSnapshot(
                id = country.id,
                name = country.name,
                taxRate = EconomyManager.getTaxRate(country),
                accumulatedGoldTax = country.accumulatedGoldTax,
                accumulatedDiamondTax = country.accumulatedDiamondTax
            )
        }

        // 🔧 v1.3.52: 修复问题2 (High) - 在主线程快照在线玩家数据，避免异步线程调用Bukkit API
        data class PlayerSnapshot(
            val uuid: UUID,
            val name: String,
            val countryId: UUID?
        )

        val onlinePlayerSnapshots = Bukkit.getOnlinePlayers().mapNotNull { player ->
            val user = player.user()
            PlayerSnapshot(
                uuid = player.uniqueId,
                name = player.name,
                countryId = user?.country?.id
            )
        }

        // 在异步线程执行计算操作（只读快照数据）
        async { _ ->
            try {
                plugin.logger.info("正在执行自动收税...")

                // 收集税收计算结果
                val taxResults = mutableMapOf<UUID, Int>()
                // 🔧 v1.3.50: 存储完整的税收计算结果用于应用累积
                val taxCalculationResults = mutableMapOf<UUID, cn.lcofficial.guozhan.economy.TaxResult>()

                for (snapshot in countrySnapshots) {
                    // 跳过税率为0的国家
                    if (snapshot.taxRate <= 0) continue

                    // 🔧 v1.3.52: 使用快照数据计算税收，避免访问可变Country对象
                    // 从CountryManager获取Country对象用于计算（在主线程会重新获取）
                    val country = CountryManager.getCountry(snapshot.id) ?: continue

                    // 🔧 v1.3.50: 修复每日自动税收从不累积小额国家的小数收入
                    // 始终调用calculateTaxWithAccumulation()并存储余数，不再基于taxAmount > 0判断
                    val taxResult = RegionalTaxSystem.calculateTaxWithAccumulation(country, 24.0) // 24小时

                    // 即使整数部分为0，也要记录结果以便累积小数部分
                    taxResults[snapshot.id] = taxResult.goldTax + (taxResult.diamondTax * 10)

                    // 存储完整的税收结果用于后续应用
                    taxCalculationResults[snapshot.id] = taxResult
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

                    // 🔧 v1.3.52: 修复问题2 (High) - 使用快照数据过滤在线成员，避免异步线程调用Bukkit API
                    // 通知在线成员
                    for ((countryId, taxAmount) in notifications) {
                        // 使用快照数据过滤该国家的在线成员
                        val countryOnlineMembers = onlinePlayerSnapshots.filter { it.countryId == countryId }

                        // 🔧 v1.3.42: 修复税收通知违反Folia线程规则 - 使用EntityScheduler发送玩家消息
                        // 通知在线成员
                        for (playerSnapshot in countryOnlineMembers) {
                            val player = Bukkit.getPlayer(playerSnapshot.uuid) ?: continue
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
            } finally {
                // 🔧 v1.3.66: 释放税收任务锁
                taxCollectionLock.set(false)
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
     * 🔧 v1.3.52: 修复数据竞态 - 在主线程创建不可变快照，避免异步线程访问可变TerritoryBlock字段
     */
    private fun generateResources() {
        val plugin = Guozhan.instance
        val startTime = System.currentTimeMillis()

        // 🔧 v1.3.52: 在主线程创建TerritoryBlock数据的不可变快照
        data class TerritorySnapshot(
            val ownerId: UUID?,
            val resourceType: ResourceType?
        )

        val territorySnapshots = TerritoryManager.territories.values.map { territory ->
            TerritorySnapshot(
                ownerId = territory.owner?.id,
                resourceType = territory.resourceType
            )
        }

        // 在异步线程执行计算操作（只读快照数据）
        async { _ ->
            try {
                plugin.logger.info("正在生成资源...")

                // 按国家分组（使用快照数据）
                val territoriesByCountry = territorySnapshots
                    .filter { it.ownerId != null }
                    .groupBy { it.ownerId!! }

                // 收集资源生成结果
                val resourceResults = mutableMapOf<UUID, Pair<Int, Int>>() // countryId -> (gold, diamond)

                for ((countryId, countryTerritories) in territoriesByCountry) {
                    // 计算每种资源的领土数量（使用快照数据）
                    val resourceCounts = countryTerritories.groupBy { it.resourceType }.mapValues { it.value.size }

                    // 计算生成的资源
                    val goldGenerated = (resourceCounts[ResourceType.GOLD] ?: 0) * 5 // 每个金矿领土生成5金币
                    val diamondGenerated = (resourceCounts[ResourceType.DIAMOND] ?: 0) * 2 // 每个钻石领土生成2钻石

                    if (goldGenerated > 0 || diamondGenerated > 0) {
                        resourceResults[countryId] = Pair(goldGenerated, diamondGenerated)
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
