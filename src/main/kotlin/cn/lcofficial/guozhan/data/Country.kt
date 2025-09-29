package cn.lcofficial.guozhan.data

import cn.lcofficial.guozhan.manager.CityManager
import cn.lcofficial.guozhan.manager.UserManager
import org.bukkit.Location
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.jetbrains.exposed.dao.id.IdTable
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
    // 核心系统相关字段
    val coreHealth = integer("core_health").default(1000)
    val coreLocationX = integer("core_location_x")
    val coreLocationY = integer("core_location_y")
    val coreLocationZ = integer("core_location_z")
    val coreWorld = varchar("core_world", 64)
    val lastHealthRegenTime = long("last_health_regen_time").default(System.currentTimeMillis())
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
    // 核心系统相关属性
    var coreHealth: Int = 1000,
    var coreLocationX: Int = 0,
    var coreLocationY: Int = 0,
    var coreLocationZ: Int = 0,
    var coreWorld: String = "world",
    var lastHealthRegenTime: Long = System.currentTimeMillis()
) {
    var capital: City
        get() {
            return CityManager.getCity(capitalId)!!
        }
        set(value) {
            capitalId = value.id
        }
    var owner: User
        get() {
            return UserManager.getUser(ownerId)!!
        }
        set(value) {
            ownerId = value.uniqueId
        }
    val cities = mutableListOf<City>()
    val members: List<User>
        get() = transaction {
            Users.select(listOf(Users.id, Users.countryId)).where { Users.countryId eq id }.map {
                UserManager.getUser(UUID.fromString(it[Users.id].value))!!
            }.toList()
        }

    fun save() = transaction {
        Countries.update({ Countries.id eq id }) {
            it[name] = name
            it[owner] = ownerId.toString()
            it[capital] = capitalId.toString()
            it[createTime] = createTime
            it[public] = public
            it[shield] = shield
            it[gold] = gold
            it[diamond] = diamond
            it[economyPoints] = economyPoints
            it[coreHealth] = coreHealth
            it[coreLocationX] = coreLocationX
            it[coreLocationY] = coreLocationY
            it[coreLocationZ] = coreLocationZ
            it[coreWorld] = coreWorld
            it[lastHealthRegenTime] = lastHealthRegenTime
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
        
        coreHealth = 1000 // 初始化核心血量
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
     * 检查是否可以恢复血量（每分钟1点）
     */
    fun canRegenHealth(): Boolean {
        return System.currentTimeMillis() - lastHealthRegenTime >= 60000 // 60秒
    }
    
    /**
     * 恢复核心血量
     */
    fun regenHealth() {
        if (canRegenHealth() && coreHealth < 1000) {
            coreHealth = (coreHealth + 1).coerceAtMost(1000)
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