package cn.lcofficial.guozhan.task

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.economy.RegionalTaxSystem
import cn.lcofficial.guozhan.manager.CountryManager
import cn.lcofficial.guozhan.config.Message
import cn.lcofficial.guozhan.pluginLogger
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import java.util.*

/**
 * 税收收集任务，定期为各国家收集税收
 */
class TaxCollectionTask : BukkitRunnable() {
    
    // 上次收税时间记录
    private val lastTaxCollection = mutableMapOf<UUID, Long>()
    
    // 税收周期（毫秒），默认1小时
    private val taxCycle = 60 * 60 * 1000L
    
    override fun run() {
        val currentTime = System.currentTimeMillis()
        
        // 遍历所有国家
        for (country in CountryManager.countries.values) {
            val lastCollection = lastTaxCollection[country.id] ?: 0L
            
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
        
        // 发送税收通知
        for (player in onlineMembers) {
            player.sendMessage("§a国家税收: §6+$goldTax 金锡 §b+$diamondTax 钻石")
        }
    }
    
    /**
     * 启动税收任务
     */
    fun start() {
        // 每分钟检查一次
        this.runTaskTimer(Guozhan.instance, 20L * 60L, 20L * 60L)
        pluginLogger.info("税收系统已启动，税收周期: ${taxCycle / (60 * 1000)} 分钟")
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
        
        // 计算经过的小时数
        val hours = (currentTime - lastCollection) / (60.0 * 60.0 * 1000.0)
        
        // 收集税收
        val result = RegionalTaxSystem.collectTax(country, hours)
        
        // 记录本次收税时间
        lastTaxCollection[country.id] = currentTime
        
        return result
    }
}