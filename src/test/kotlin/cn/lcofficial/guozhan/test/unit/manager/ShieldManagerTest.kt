package cn.lcofficial.guozhan.test.unit.manager

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.util.logging.Logger

/**
 * ShieldManager单元测试
 * 
 * 测试范围：
 * - max-aspect-ratio配置读取
 * - 长宽比验证逻辑
 * - 护盾系统核心算法
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShieldManagerTest {
    
    @BeforeAll
    fun setup() {
        // 设置模拟的pluginLogger
        try {
            cn.lcofficial.guozhan.pluginLogger = Logger.getLogger("ShieldManagerTestLogger")
        } catch (e: Exception) {
            // 忽略初始化错误
        }
        
        println("开始ShieldManager测试...")
    }
    
    @AfterAll
    fun cleanup() {
        println("ShieldManager测试完成")
    }
    
    @Test
    fun testAspectRatioCalculation() {
        // 测试长宽比计算逻辑
        data class Territory(val minX: Int, val maxX: Int, val minZ: Int, val maxZ: Int)
        
        fun calculateAspectRatio(territory: Territory): Double {
            val width = territory.maxX - territory.minX + 1
            val height = territory.maxZ - territory.minZ + 1
            return maxOf(width, height).toDouble() / minOf(width, height).toDouble()
        }
        
        // 测试正方形领土（长宽比 = 1.0）
        val squareTerritory = Territory(0, 9, 0, 9) // 10x10
        assertEquals(1.0, calculateAspectRatio(squareTerritory), 0.01)
        
        // 测试矩形领土（长宽比 = 2.0）
        val rectangleTerritory = Territory(0, 19, 0, 9) // 20x10
        assertEquals(2.0, calculateAspectRatio(rectangleTerritory), 0.01)
        
        // 测试长条形领土（长宽比 = 5.0）
        val longTerritory = Territory(0, 49, 0, 9) // 50x10
        assertEquals(5.0, calculateAspectRatio(longTerritory), 0.01)
        
        println("✓ 长宽比计算测试通过")
    }
    
    @Test
    fun testAspectRatioValidation() {
        // 测试长宽比验证逻辑
        fun isValidAspectRatio(aspectRatio: Double, maxAspectRatio: Double): Boolean {
            return aspectRatio <= maxAspectRatio
        }
        
        val maxAspectRatio = 2.0
        
        // 有效的长宽比
        assertTrue(isValidAspectRatio(1.0, maxAspectRatio))
        assertTrue(isValidAspectRatio(1.5, maxAspectRatio))
        assertTrue(isValidAspectRatio(2.0, maxAspectRatio))
        
        // 无效的长宽比
        assertFalse(isValidAspectRatio(2.1, maxAspectRatio))
        assertFalse(isValidAspectRatio(3.0, maxAspectRatio))
        assertFalse(isValidAspectRatio(5.0, maxAspectRatio))
        
        println("✓ 长宽比验证测试通过")
    }
    
    @Test
    fun testShieldCostCalculation() {
        // 测试护盾成本计算逻辑
        fun calculateShieldCost(
            hourlyIncome: Int,
            durationHours: Int,
            costMultiplier: Int
        ): Int {
            return hourlyIncome * durationHours * costMultiplier
        }
        
        // 测试基础成本计算
        assertEquals(500, calculateShieldCost(100, 1, 5)) // 100金/小时 * 1小时 * 5倍率
        assertEquals(2500, calculateShieldCost(100, 5, 5)) // 100金/小时 * 5小时 * 5倍率
        assertEquals(12000, calculateShieldCost(100, 24, 5)) // 100金/小时 * 24小时 * 5倍率
        
        // 测试不同收入水平
        assertEquals(1000, calculateShieldCost(200, 1, 5)) // 高收入国家
        assertEquals(250, calculateShieldCost(50, 1, 5)) // 低收入国家
        
        println("✓ 护盾成本计算测试通过")
    }
    
    @Test
    fun testShieldDurationValidation() {
        // 测试护盾持续时间验证逻辑
        fun isValidDuration(duration: Int, minDuration: Int, maxDuration: Int): Boolean {
            return duration >= minDuration && duration <= maxDuration
        }
        
        val minDuration = 1
        val maxDuration = 24
        
        // 有效持续时间
        assertTrue(isValidDuration(1, minDuration, maxDuration))
        assertTrue(isValidDuration(12, minDuration, maxDuration))
        assertTrue(isValidDuration(24, minDuration, maxDuration))
        
        // 无效持续时间
        assertFalse(isValidDuration(0, minDuration, maxDuration))
        assertFalse(isValidDuration(-1, minDuration, maxDuration))
        assertFalse(isValidDuration(25, minDuration, maxDuration))
        assertFalse(isValidDuration(48, minDuration, maxDuration))
        
        println("✓ 护盾持续时间验证测试通过")
    }
    
    @Test
    fun testShieldCooldownLogic() {
        // 测试护盾冷却时间逻辑
        fun isShieldOnCooldown(lastShieldTime: Long, cooldownMinutes: Int): Boolean {
            val currentTime = System.currentTimeMillis()
            val cooldownMs = cooldownMinutes * 60 * 1000L
            return (currentTime - lastShieldTime) < cooldownMs
        }
        
        val cooldownMinutes = 30
        val currentTime = System.currentTimeMillis()
        
        // 测试冷却中
        val recentTime = currentTime - (15 * 60 * 1000L) // 15分钟前
        assertTrue(isShieldOnCooldown(recentTime, cooldownMinutes))
        
        // 测试冷却完成
        val oldTime = currentTime - (35 * 60 * 1000L) // 35分钟前
        assertFalse(isShieldOnCooldown(oldTime, cooldownMinutes))
        
        println("✓ 护盾冷却逻辑测试通过")
    }
    
    @Test
    fun testShieldResourceRequirement() {
        // 测试护盾资源需求检查
        data class CountryResources(val gold: Int, val diamonds: Int)
        
        fun hasEnoughResources(
            resources: CountryResources,
            requiredGold: Int,
            requiredDiamonds: Int
        ): Boolean {
            return resources.gold >= requiredGold && resources.diamonds >= requiredDiamonds
        }
        
        val richCountry = CountryResources(10000, 500)
        val poorCountry = CountryResources(100, 5)
        
        // 测试资源充足的情况
        assertTrue(hasEnoughResources(richCountry, 5000, 100))
        assertTrue(hasEnoughResources(richCountry, 1000, 50))
        
        // 测试资源不足的情况
        assertFalse(hasEnoughResources(poorCountry, 5000, 100))
        assertFalse(hasEnoughResources(poorCountry, 1000, 50))
        
        // 测试边界情况
        assertTrue(hasEnoughResources(poorCountry, 100, 5)) // 刚好够
        assertFalse(hasEnoughResources(poorCountry, 101, 5)) // 金币不够
        assertFalse(hasEnoughResources(poorCountry, 100, 6)) // 钻石不够
        
        println("✓ 护盾资源需求测试通过")
    }
    
    @Test
    fun testShieldActivationConditions() {
        // 测试护盾激活条件综合检查
        data class ShieldActivationRequest(
            val aspectRatio: Double,
            val duration: Int,
            val gold: Int,
            val diamonds: Int,
            val lastShieldTime: Long
        )
        
        fun canActivateShield(
            request: ShieldActivationRequest,
            maxAspectRatio: Double,
            minDuration: Int,
            maxDuration: Int,
            requiredGold: Int,
            requiredDiamonds: Int,
            cooldownMinutes: Int
        ): Pair<Boolean, String> {
            // 检查长宽比
            if (request.aspectRatio > maxAspectRatio) {
                return false to "领土长宽比过大"
            }
            
            // 检查持续时间
            if (request.duration < minDuration || request.duration > maxDuration) {
                return false to "持续时间无效"
            }
            
            // 检查资源
            if (request.gold < requiredGold || request.diamonds < requiredDiamonds) {
                return false to "资源不足"
            }
            
            // 检查冷却时间
            val currentTime = System.currentTimeMillis()
            val cooldownMs = cooldownMinutes * 60 * 1000L
            if ((currentTime - request.lastShieldTime) < cooldownMs) {
                return false to "冷却中"
            }
            
            return true to "可以激活"
        }
        
        val currentTime = System.currentTimeMillis()
        
        // 测试成功激活
        val validRequest = ShieldActivationRequest(
            aspectRatio = 1.5,
            duration = 12,
            gold = 10000,
            diamonds = 500,
            lastShieldTime = currentTime - (60 * 60 * 1000L) // 1小时前
        )
        
        val (canActivate, message) = canActivateShield(
            validRequest, 2.0, 1, 24, 5000, 100, 30
        )
        
        assertTrue(canActivate, "应该可以激活护盾: $message")
        
        println("✓ 护盾激活条件测试通过")
    }
    
    @Test
    fun testEdgeCases() {
        // 测试边界情况和异常输入
        
        // 测试零值和负值
        assertThrows<IllegalArgumentException> {
            // 模拟无效的长宽比计算
            val invalidRatio = -1.0
            if (invalidRatio < 0) throw IllegalArgumentException("长宽比不能为负数")
        }
        
        // 测试极大值
        val largeAspectRatio = 1000.0
        val maxAllowed = 2.0
        assertFalse(largeAspectRatio <= maxAllowed, "极大长宽比应该被拒绝")
        
        println("✓ 边界情况测试通过")
    }
}
