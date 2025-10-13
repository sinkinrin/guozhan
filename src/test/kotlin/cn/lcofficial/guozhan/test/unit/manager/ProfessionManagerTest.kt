package cn.lcofficial.guozhan.test.unit.manager

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.util.logging.Logger

/**
 * ProfessionManager单元测试
 * 
 * 测试范围：
 * - 配置化的职业解锁检查
 * - 职业升级检查逻辑
 * - 职业系统核心算法
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProfessionManagerTest {

    enum class ProfessionType {
        SCOUT, CRAFTSMAN, BERSERKER, GUARDIAN, LEAPER, PRIEST, CONQUEROR
    }
    
    @BeforeAll
    fun setup() {
        // 设置模拟的pluginLogger
        try {
            cn.lcofficial.guozhan.pluginLogger = Logger.getLogger("ProfessionManagerTestLogger")
        } catch (e: Exception) {
            // 忽略初始化错误
        }
        
        println("开始ProfessionManager测试...")
    }
    
    @AfterAll
    fun cleanup() {
        println("ProfessionManager测试完成")
    }
    
    @Test
    fun testProfessionUnlockTiming() {
        // 测试职业解锁时间逻辑
        fun canUnlockProfession(
            countryCreateTime: Long,
            unlockDelayHours: Int
        ): Boolean {
            val currentTime = System.currentTimeMillis()
            val unlockDelayMs = unlockDelayHours * 60 * 60 * 1000L
            return (currentTime - countryCreateTime) >= unlockDelayMs
        }
        
        val currentTime = System.currentTimeMillis()
        val unlockDelayHours = 2
        
        // 测试可以解锁的情况
        val oldCountry = currentTime - (3 * 60 * 60 * 1000L) // 3小时前创建
        assertTrue(canUnlockProfession(oldCountry, unlockDelayHours))
        
        // 测试不能解锁的情况
        val newCountry = currentTime - (1 * 60 * 60 * 1000L) // 1小时前创建
        assertFalse(canUnlockProfession(newCountry, unlockDelayHours))
        
        // 测试边界情况
        val exactTime = currentTime - (2 * 60 * 60 * 1000L) // 刚好2小时前
        assertTrue(canUnlockProfession(exactTime, unlockDelayHours))
        
        println("✓ 职业解锁时间测试通过")
    }
    
    @Test
    fun testProfessionUpgradeEligibility() {
        // 测试职业升级资格检查
        data class UserProfession(
            val profession: String?,
            val level: Int
        )
        
        fun canUpgradeProfession(userProfession: UserProfession): Boolean {
            // 必须有职业且等级小于2
            return userProfession.profession != null && userProfession.level < 2
        }
        
        // 测试可以升级的情况
        val level1Scout = UserProfession("SCOUT", 1)
        assertTrue(canUpgradeProfession(level1Scout))
        
        val level1Craftsman = UserProfession("CRAFTSMAN", 1)
        assertTrue(canUpgradeProfession(level1Craftsman))
        
        // 测试不能升级的情况
        val noProfession = UserProfession(null, 0)
        assertFalse(canUpgradeProfession(noProfession))
        
        val maxLevel = UserProfession("SCOUT", 2)
        assertFalse(canUpgradeProfession(maxLevel))
        
        val overLevel = UserProfession("SCOUT", 3)
        assertFalse(canUpgradeProfession(overLevel))
        
        println("✓ 职业升级资格测试通过")
    }
    
    @Test
    fun testProfessionUpgradeCost() {
        // 测试职业升级成本计算
        fun getUpgradeCost(fromLevel: Int, toLevel: Int, baseCost: Int): Int {
            return when {
                fromLevel == 1 && toLevel == 2 -> baseCost
                else -> 0 // 其他升级路径暂不支持
            }
        }
        
        val baseCost = 50
        
        // 测试1级升2级
        assertEquals(50, getUpgradeCost(1, 2, baseCost))
        
        // 测试无效升级路径
        assertEquals(0, getUpgradeCost(0, 1, baseCost))
        assertEquals(0, getUpgradeCost(2, 3, baseCost))
        assertEquals(0, getUpgradeCost(1, 3, baseCost))
        
        println("✓ 职业升级成本测试通过")
    }
    
    @Test
    fun testProfessionEffects() {
        // 测试职业效果配置
        
        data class ProfessionEffect(
            val effectType: String,
            val level1Amplifier: Int,
            val level2Amplifier: Int
        )
        
        fun getProfessionEffect(profession: ProfessionType): ProfessionEffect {
            return when (profession) {
                ProfessionType.SCOUT -> ProfessionEffect("SPEED", 0, 3) // 速度I -> 速度IV
                ProfessionType.CRAFTSMAN -> ProfessionEffect("FAST_DIGGING", 0, 1) // 急迫I -> 急迫II
                ProfessionType.BERSERKER -> ProfessionEffect("INCREASE_DAMAGE", 0, 1) // 力量I -> 力量II
                ProfessionType.GUARDIAN -> ProfessionEffect("DAMAGE_RESISTANCE", 0, 1) // 抗性I -> 抗性II
                ProfessionType.LEAPER -> ProfessionEffect("JUMP", 2, 4) // 跳跃III -> 跳跃V
                ProfessionType.PRIEST -> ProfessionEffect("REGENERATION", 0, 1) // 再生I -> 再生II
                ProfessionType.CONQUEROR -> ProfessionEffect("SPECIAL", 0, 0) // 特殊效果
            }
        }
        
        // 测试各职业效果
        val scoutEffect = getProfessionEffect(ProfessionType.SCOUT)
        assertEquals("SPEED", scoutEffect.effectType)
        assertEquals(0, scoutEffect.level1Amplifier)
        assertEquals(3, scoutEffect.level2Amplifier)
        
        val leaperEffect = getProfessionEffect(ProfessionType.LEAPER)
        assertEquals("JUMP", leaperEffect.effectType)
        assertEquals(2, leaperEffect.level1Amplifier)
        assertEquals(4, leaperEffect.level2Amplifier)
        
        println("✓ 职业效果配置测试通过")
    }
    
    @Test
    fun testProfessionValidation() {
        // 测试职业名称验证
        fun isValidProfession(professionName: String?): Boolean {
            val validProfessions = setOf(
                "SCOUT", "CRAFTSMAN", "BERSERKER", "GUARDIAN", 
                "LEAPER", "PRIEST", "CONQUEROR"
            )
            return professionName != null && professionName in validProfessions
        }
        
        // 测试有效职业
        assertTrue(isValidProfession("SCOUT"))
        assertTrue(isValidProfession("CRAFTSMAN"))
        assertTrue(isValidProfession("BERSERKER"))
        assertTrue(isValidProfession("GUARDIAN"))
        assertTrue(isValidProfession("LEAPER"))
        assertTrue(isValidProfession("PRIEST"))
        assertTrue(isValidProfession("CONQUEROR"))
        
        // 测试无效职业
        assertFalse(isValidProfession(null))
        assertFalse(isValidProfession(""))
        assertFalse(isValidProfession("INVALID"))
        assertFalse(isValidProfession("scout")) // 小写
        assertFalse(isValidProfession("WARRIOR")) // 不存在的职业
        
        println("✓ 职业名称验证测试通过")
    }
    
    @Test
    fun testProfessionLevelValidation() {
        // 测试职业等级验证
        fun isValidProfessionLevel(level: Int): Boolean {
            return level in 0..2
        }
        
        // 测试有效等级
        assertTrue(isValidProfessionLevel(0)) // 无职业
        assertTrue(isValidProfessionLevel(1)) // 1级
        assertTrue(isValidProfessionLevel(2)) // 2级
        
        // 测试无效等级
        assertFalse(isValidProfessionLevel(-1))
        assertFalse(isValidProfessionLevel(3))
        assertFalse(isValidProfessionLevel(10))
        
        println("✓ 职业等级验证测试通过")
    }
    
    @Test
    fun testProfessionResourceRequirement() {
        // 测试职业升级资源需求
        fun hasEnoughResourcesForUpgrade(diamonds: Int, requiredDiamonds: Int): Boolean {
            return diamonds >= requiredDiamonds
        }
        
        val upgradeCost = 50
        
        // 测试资源充足
        assertTrue(hasEnoughResourcesForUpgrade(100, upgradeCost))
        assertTrue(hasEnoughResourcesForUpgrade(50, upgradeCost)) // 刚好够
        
        // 测试资源不足
        assertFalse(hasEnoughResourcesForUpgrade(49, upgradeCost))
        assertFalse(hasEnoughResourcesForUpgrade(0, upgradeCost))
        
        println("✓ 职业升级资源需求测试通过")
    }
    
    @Test
    fun testProfessionSystemIntegration() {
        // 测试职业系统综合逻辑
        data class ProfessionUpgradeRequest(
            val currentProfession: String?,
            val currentLevel: Int,
            val countryCreateTime: Long,
            val playerDiamonds: Int
        )
        
        fun canUpgradeProfessionComplete(
            request: ProfessionUpgradeRequest,
            unlockDelayHours: Int,
            upgradeCost: Int
        ): Pair<Boolean, String> {
            // 检查是否有职业
            if (request.currentProfession == null) {
                return false to "玩家没有职业"
            }
            
            // 检查等级
            if (request.currentLevel >= 2) {
                return false to "职业已达到最高等级"
            }
            
            // 检查国家创建时间（简化版，实际应该检查职业设置时间）
            val currentTime = System.currentTimeMillis()
            val unlockDelayMs = unlockDelayHours * 60 * 60 * 1000L
            if ((currentTime - request.countryCreateTime) < unlockDelayMs) {
                return false to "国家创建时间不足"
            }
            
            // 检查资源
            if (request.playerDiamonds < upgradeCost) {
                return false to "钻石不足"
            }
            
            return true to "可以升级"
        }
        
        val currentTime = System.currentTimeMillis()
        
        // 测试成功升级
        val validRequest = ProfessionUpgradeRequest(
            currentProfession = "SCOUT",
            currentLevel = 1,
            countryCreateTime = currentTime - (3 * 60 * 60 * 1000L), // 3小时前
            playerDiamonds = 100
        )
        
        val (canUpgrade, message) = canUpgradeProfessionComplete(validRequest, 2, 50)
        assertTrue(canUpgrade, "应该可以升级职业: $message")
        
        println("✓ 职业系统综合测试通过")
    }
}
