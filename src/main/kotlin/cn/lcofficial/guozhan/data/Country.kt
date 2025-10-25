package cn.lcofficial.guozhan.data

import cn.lcofficial.guozhan.manager.CityManager
import cn.lcofficial.guozhan.manager.UserManager
import org.bukkit.Location
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.*

object Countries : IdTable<String>("gz_countries") {
    override val id = char("id", 36).uniqueIndex().entityId()
    val owner = reference("owner", Users)
    val name = varchar("name", 32)
    val capital = reference("capital", Cities) // 非空首都
    val createTime = long("create_time")
    val public = bool("public")
    val shield = bool("shield")
    val gold = integer("gold")
    val diamond = integer("diamond")
    val economyPoints = integer("economy_points").default(0) // 经济点数
    // v1.3.19新增：国家宣言字段
    val declaration = text("declaration").nullable() // 国家宣言
    // 护盾系统持久化字段
    val shieldEndTime = long("shield_end_time").nullable() // 护盾结束时间戳
    val shieldCooldownEnd = long("shield_cooldown_end").nullable() // 护盾冷却结束时间戳
    // 核心系统相关字段
    val coreHealth = integer("core_health").default(1000)
    val coreLocationX = integer("core_location_x")
    val coreLocationY = integer("core_location_y")
    val coreLocationZ = integer("core_location_z")
    val coreWorld = varchar("core_world", 64)
    val lastHealthRegenTime = long("last_health_regen_time").default(System.currentTimeMillis())
    // 🔧 v1.3.37: 新增税率持久化字段
    val taxRate = integer("tax_rate").default(10) // 税率（百分比），默认10%
    // 🔧 v1.3.40: 修复区域税收被截断 - 添加累计税收持久化字段
    val accumulatedGoldTax = double("accumulated_gold_tax").default(0.0) // 累计金币税收
    val accumulatedDiamondTax = double("accumulated_diamond_tax").default(0.0) // 累计钻石税收
    // 🔧 v1.3.42: 修复税收冷却时间不持久化 - 添加手动收税时间字段
    val lastManualTaxTime = long("last_manual_tax_time").default(0L) // 上次手动收税时间戳
    // 🔧 v1.3.42: 修复税收状态分裂 - 添加自动收税时间字段
    val lastAutoTaxTime = long("last_auto_tax_time").default(0L) // 上次自动收税时间戳
    // 🔧 v1.3.48: 修复问题1 - 添加国家固定颜色字段
    val mapColor = integer("map_color").default(0) // 地图颜色（RGB整数值），0表示未设置
}

class Country(
    val id: UUID,
    var ownerId: UUID,
    var name: String,
    var createTime: Long,
    var public: Boolean,
    var shield: Boolean,
    var gold: Int,
    var diamond: Int,
    var economyPoints: Int = 0, // 经济点数
    var capitalId: UUID,
    // v1.3.19新增：国家宣言属性
    var declaration: String? = null, // 国家宣言
    // 护盾系统持久化属性
    var shieldEndTime: Long? = null, // 护盾结束时间戳
    var shieldCooldownEnd: Long? = null, // 护盾冷却结束时间戳
    // 核心系统相关属性
    var coreHealth: Int = 1000,
    var coreLocationX: Int = 0,
    var coreLocationY: Int = 0,
    var coreLocationZ: Int = 0,
    var coreWorld: String = "world",
    var lastHealthRegenTime: Long = System.currentTimeMillis(),
    // 🔧 v1.3.37: 新增税率属性
    var taxRate: Int = 10, // 税率（百分比），默认10%
    // 🔧 v1.3.40: 修复区域税收被截断 - 添加累计税收字段
    var accumulatedGoldTax: Double = 0.0, // 累计金币税收（小数部分）
    var accumulatedDiamondTax: Double = 0.0, // 累计钻石税收（小数部分）
    // 🔧 v1.3.42: 修复税收冷却时间不持久化 - 添加手动收税时间属性
    var lastManualTaxTime: Long = 0L, // 上次手动收税时间戳
    // 🔧 v1.3.42: 修复税收状态分裂 - 添加自动收税时间属性
    var lastAutoTaxTime: Long = 0L, // 上次自动收税时间戳
    // 🔧 v1.3.48: 修复问题1 - 添加国家固定颜色字段
    var mapColor: Int = 0 // 地图颜色（RGB整数值），0表示未设置
) {
    var capital: City
        get() {
            return CityManager.getCity(capitalId) ?: throw IllegalStateException("Capital city not found for country $name")
        }
        set(value) {
            capitalId = value.id
        }
    var owner: User?
        get() {
            return UserManager.getUser(ownerId)
        }
        set(value) {
            ownerId = value?.uniqueId ?: throw IllegalArgumentException("Owner cannot be null")
        }
    val cities = mutableListOf<City>()

    /**
     * 获取国家成员列表
     * 🔧 v1.3.21: 使用 CountryManager 的成员缓存，避免频繁查询数据库
     *
     * 性能优化：
     * - 旧实现：每次访问都执行 SELECT * FROM gz_users WHERE country_id = ?
     * - 新实现：从内存缓存读取，仅在缓存失效时查询数据库
     * - 影响场景：聊天分发、Boss bar 刷新、经济任务等高频调用
     */
    val members: List<User>
        get() {
            // 使用 CountryManager 的成员缓存
            val memberIds = cn.lcofficial.guozhan.manager.CountryManager.getCountryMembers(id)
            return memberIds.mapNotNull { UserManager.getUser(it) }
        }

    /**
     * 保存国家数据到数据库
     *
     * ⚠️ 重要提示（v1.3.52修复Critical问题C2）：
     * 任何修改Country对象属性的操作后，都必须立即调用此方法确保缓存与数据库同步！
     *
     * 示例：
     * ```kotlin
     * country.gold += 100
     * country.save()  // 必须立即保存！
     * ```
     *
     * 如果不立即保存，可能导致：
     * 1. 数据丢失（服务器崩溃或重启时）
     * 2. 缓存与数据库不一致
     * 3. 并发场景下的数据竞态条件
     */
    fun save() = transaction {
        Countries.update({ Countries.id eq id }) {
            it[name] = name
            it[owner] = EntityID(ownerId.toString(), Users)
            it[capital] = EntityID(capitalId.toString(), Cities)
            it[createTime] = createTime
            it[public] = public
            it[shield] = shield
            it[gold] = gold
            it[diamond] = diamond
            it[economyPoints] = economyPoints
            it[declaration] = declaration // v1.3.19新增：保存国家宣言
            it[shieldEndTime] = shieldEndTime
            it[shieldCooldownEnd] = shieldCooldownEnd
            it[coreHealth] = coreHealth
            it[coreLocationX] = coreLocationX
            it[coreLocationY] = coreLocationY
            it[coreLocationZ] = coreLocationZ
            it[coreWorld] = coreWorld
            it[lastHealthRegenTime] = lastHealthRegenTime
            it[taxRate] = taxRate // 🔧 v1.3.37: 保存税率字段
            it[accumulatedGoldTax] = accumulatedGoldTax // 🔧 v1.3.40: 保存累计金币税收
            it[accumulatedDiamondTax] = accumulatedDiamondTax // 🔧 v1.3.40: 保存累计钻石税收
            it[lastManualTaxTime] = lastManualTaxTime // 🔧 v1.3.42: 保存手动收税时间
            it[lastAutoTaxTime] = lastAutoTaxTime // 🔧 v1.3.42: 保存自动收税时间
            it[mapColor] = mapColor // 🔧 v1.3.48: 保存地图颜色
        }

        // 触发经济BossBar更新
        try {
            cn.lcofficial.guozhan.manager.EconomyBossBarManager.forceUpdate(this@Country)
        } catch (e: Exception) {
            // 忽略BossBar更新错误，避免影响数据保存
        }
    }
    
    /**
     * 获取核心位置
     */
    fun getCoreLocation(): Location? {
        val world = Bukkit.getWorld(coreWorld) ?: return null
        return Location(world, coreLocationX.toDouble(), coreLocationY.toDouble(), coreLocationZ.toDouble())
    }
    
    /**
     * 设置核心位置
     */
    fun setCoreLocation(location: Location) {
        coreLocationX = location.blockX
        coreLocationY = location.blockY
        coreLocationZ = location.blockZ
        coreWorld = location.world.name
    }
    
    /**
     * 创建国家核心（信标+玻璃保护）
     */
    fun createCore(baseLocation: Location) {
        val coreLocation = baseLocation.clone().add(0.0, 9.0, 0.0)
        setCoreLocation(coreLocation)
        
        // 放置信标
        coreLocation.block.type = Material.BEACON
        
        // 用玻璃围一圈保护
        for (x in -1..1) {
            for (z in -1..1) {
                for (y in -1..1) {
                    if (x == 0 && y == 0 && z == 0) continue // 跳过信标位置
                    val glassLocation = coreLocation.clone().add(x.toDouble(), y.toDouble(), z.toDouble())
                    glassLocation.block.type = Material.GLASS
                }
            }
        }
        
        coreHealth = cn.lcofficial.guozhan.config.Config.Country.coreHealthInitial // 使用配置的初始血量
        lastHealthRegenTime = System.currentTimeMillis()
        save()
    }
    
    /**
     * 核心受伤
     */
    fun damageCore(damage: Int): Boolean {
        coreHealth = (coreHealth - damage).coerceAtLeast(0)
        save()
        return coreHealth <= 0
    }

    /**
     * 🔧 v1.3.48: 修复问题1 - 获取或生成国家固定颜色
     * 基于国家ID生成稳定的颜色，确保每个国家都有唯一且固定的颜色
     */
    fun getOrGenerateMapColor(): java.awt.Color {
        if (mapColor == 0) {
            // 基于国家ID生成稳定的随机颜色
            val random = kotlin.random.Random(id.hashCode().toLong())
            val hue = random.nextFloat()
            val saturation = 0.7f + random.nextFloat() * 0.3f // 0.7-1.0，确保颜色鲜艳
            val brightness = 0.8f + random.nextFloat() * 0.2f // 0.8-1.0，确保颜色明亮

            val color = java.awt.Color.getHSBColor(hue, saturation, brightness)
            mapColor = color.rgb

            // 异步保存到数据库
            try {
                save()
                cn.lcofficial.guozhan.Guozhan.instance.logger.info("🔧 [地图颜色] 为国家 ${name} 生成固定颜色: RGB(${color.red}, ${color.green}, ${color.blue})")
            } catch (e: Exception) {
                cn.lcofficial.guozhan.Guozhan.instance.logger.warning("🔧 [地图颜色] 保存国家 ${name} 的颜色失败: ${e.message}")
            }
        }

        return java.awt.Color(mapColor)
    }
    
    /**
     * 检查是否可以恢复血量
     */
    fun canRegenHealth(): Boolean {
        val regenInterval = cn.lcofficial.guozhan.config.Config.Country.coreRegenInterval * 1000L // 转换为毫秒
        return System.currentTimeMillis() - lastHealthRegenTime >= regenInterval
    }
    
    /**
     * 恢复核心血量
     * 🔧 v1.3.35: 修复已摧毁核心仍然回复血量的问题
     */
    fun regenHealth() {
        // 如果核心已被摧毁，不进行血量回复
        if (isCoreDestroyed()) return

        val maxHealth = cn.lcofficial.guozhan.config.Config.Country.coreHealthMax
        if (canRegenHealth() && coreHealth < maxHealth) {
            coreHealth = (coreHealth + cn.lcofficial.guozhan.config.Config.Country.coreRegenAmount).coerceAtMost(maxHealth)
            lastHealthRegenTime = System.currentTimeMillis()
            save()
        }
    }
    
    /**
     * 核心是否被摧毁
     */
    fun isCoreDestroyed(): Boolean {
        return coreHealth <= 0
    }
}