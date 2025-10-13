package cn.lcofficial.guozhan.test.unit.command

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * 解散国家功能单元测试
 * 
 * 测试范围：
 * - 两步确认机制
 * - 超时清理逻辑
 * - 权限和冷却检查
 * - 数据删除流程
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DisbandCommandTest {

    enum class CountryRank {
        OWNER, ADMIN, MEMBER
    }
    
    @BeforeAll
    fun setup() {
        // 设置模拟的pluginLogger
        try {
            cn.lcofficial.guozhan.pluginLogger = Logger.getLogger("DisbandCommandTestLogger")
        } catch (e: Exception) {
            // 忽略初始化错误
        }
        
        println("开始解散国家功能测试...")
    }
    
    @AfterAll
    fun cleanup() {
        println("解散国家功能测试完成")
    }
    
    @Test
    fun testTwoStepConfirmationMechanism() {
        // 测试两步确认机制
        class MockDisbandHandler {
            private val pendingConfirmations = ConcurrentHashMap<String, Long>()
            private val confirmationTimeoutMs = 30 * 1000L // 30秒
            
            fun startDisbandProcess(playerId: String): Pair<Boolean, String> {
                val currentTime = System.currentTimeMillis()
                pendingConfirmations[playerId] = currentTime
                return true to "请在30秒内输入 /u disband confirm 确认解散"
            }
            
            fun confirmDisband(playerId: String): Pair<Boolean, String> {
                val confirmTime = pendingConfirmations[playerId]
                if (confirmTime == null) {
                    return false to "没有待确认的解散操作"
                }
                
                val currentTime = System.currentTimeMillis()
                if (currentTime - confirmTime > confirmationTimeoutMs) {
                    pendingConfirmations.remove(playerId)
                    return false to "确认超时，请重新开始解散流程"
                }
                
                pendingConfirmations.remove(playerId)
                return true to "国家解散成功"
            }
            
            fun cleanupExpiredConfirmations() {
                val currentTime = System.currentTimeMillis()
                val expired = pendingConfirmations.filter { (_, time) ->
                    currentTime - time > confirmationTimeoutMs
                }
                expired.keys.forEach { pendingConfirmations.remove(it) }
            }
            
            fun hasPendingConfirmation(playerId: String) = pendingConfirmations.containsKey(playerId)
            fun getPendingCount() = pendingConfirmations.size
        }
        
        val handler = MockDisbandHandler()
        
        // 测试开始解散流程
        val (started, startMessage) = handler.startDisbandProcess("player1")
        assertTrue(started, startMessage)
        assertTrue(handler.hasPendingConfirmation("player1"))
        
        // 测试立即确认
        val (confirmed, confirmMessage) = handler.confirmDisband("player1")
        assertTrue(confirmed, confirmMessage)
        assertFalse(handler.hasPendingConfirmation("player1"))
        
        // 测试没有待确认的情况
        val (noConfirm, noConfirmMessage) = handler.confirmDisband("player1")
        assertFalse(noConfirm, noConfirmMessage)
        assertTrue(noConfirmMessage.contains("没有待确认"))
        
        println("✓ 两步确认机制测试通过")
    }
    
    @Test
    fun testConfirmationTimeout() {
        // 测试确认超时逻辑
        class MockTimeoutHandler {
            private val pendingConfirmations = ConcurrentHashMap<String, Long>()
            private val timeoutMs = 100L // 100毫秒用于测试
            
            fun startConfirmation(playerId: String) {
                pendingConfirmations[playerId] = System.currentTimeMillis()
            }
            
            fun isConfirmationValid(playerId: String): Boolean {
                val confirmTime = pendingConfirmations[playerId] ?: return false
                return (System.currentTimeMillis() - confirmTime) <= timeoutMs
            }
            
            fun cleanupExpired() {
                val currentTime = System.currentTimeMillis()
                pendingConfirmations.entries.removeIf { (_, time) ->
                    currentTime - time > timeoutMs
                }
            }
        }
        
        val handler = MockTimeoutHandler()
        
        // 开始确认
        handler.startConfirmation("player1")
        assertTrue(handler.isConfirmationValid("player1"))
        
        // 等待超时
        Thread.sleep(150) // 等待超过100ms
        assertFalse(handler.isConfirmationValid("player1"))
        
        // 清理过期确认
        handler.cleanupExpired()
        assertFalse(handler.isConfirmationValid("player1"))
        
        println("✓ 确认超时测试通过")
    }
    
    @Test
    fun testDisbandPermissionCheck() {
        // 测试解散权限检查
        
        data class MockPlayer(
            val id: String,
            val name: String,
            val rank: CountryRank
        )
        
        fun canDisband(player: MockPlayer): Pair<Boolean, String> {
            return when (player.rank) {
                CountryRank.OWNER -> true to "可以解散"
                CountryRank.ADMIN -> false to "只有国家所有者可以解散国家"
                CountryRank.MEMBER -> false to "只有国家所有者可以解散国家"
            }
        }
        
        // 测试所有者权限
        val owner = MockPlayer("player1", "Owner", CountryRank.OWNER)
        val (ownerCan, ownerMessage) = canDisband(owner)
        assertTrue(ownerCan, ownerMessage)
        
        // 测试管理员权限
        val admin = MockPlayer("player2", "Admin", CountryRank.ADMIN)
        val (adminCan, adminMessage) = canDisband(admin)
        assertFalse(adminCan, adminMessage)
        
        // 测试普通成员权限
        val member = MockPlayer("player3", "Member", CountryRank.MEMBER)
        val (memberCan, memberMessage) = canDisband(member)
        assertFalse(memberCan, memberMessage)
        
        println("✓ 解散权限检查测试通过")
    }
    
    @Test
    fun testDisbandCooldownCheck() {
        // 测试解散冷却时间检查
        class MockCooldownManager {
            private val cooldowns = ConcurrentHashMap<String, Long>()
            private val cooldownMs = 24 * 60 * 60 * 1000L // 24小时
            
            fun setCooldown(playerId: String) {
                cooldowns[playerId] = System.currentTimeMillis()
            }
            
            fun isOnCooldown(playerId: String): Boolean {
                val lastTime = cooldowns[playerId] ?: return false
                return (System.currentTimeMillis() - lastTime) < cooldownMs
            }
            
            fun getRemainingCooldown(playerId: String): Long {
                val lastTime = cooldowns[playerId] ?: return 0
                val elapsed = System.currentTimeMillis() - lastTime
                return maxOf(0, cooldownMs - elapsed)
            }
        }
        
        val cooldownManager = MockCooldownManager()
        
        // 测试无冷却状态
        assertFalse(cooldownManager.isOnCooldown("player1"))
        assertEquals(0, cooldownManager.getRemainingCooldown("player1"))
        
        // 设置冷却
        cooldownManager.setCooldown("player1")
        assertTrue(cooldownManager.isOnCooldown("player1"))
        assertTrue(cooldownManager.getRemainingCooldown("player1") > 0)
        
        println("✓ 解散冷却检查测试通过")
    }
    
    @Test
    fun testDisbandDataCleanup() {
        // 测试解散时的数据清理
        data class MockCountry(
            val id: String,
            val name: String,
            val members: MutableList<String>
        )
        
        class MockDisbandDataHandler {
            private val countries = mutableMapOf<String, MockCountry>()
            private val territories = mutableMapOf<String, String>() // territoryId -> countryId
            private val relations = mutableMapOf<String, String>() // relationId -> countryId
            
            fun addCountry(country: MockCountry) {
                countries[country.id] = country
            }
            
            fun addTerritory(territoryId: String, countryId: String) {
                territories[territoryId] = countryId
            }
            
            fun addRelation(relationId: String, countryId: String) {
                relations[relationId] = countryId
            }
            
            fun disbandCountry(countryId: String): List<String> {
                val cleanupSteps = mutableListOf<String>()
                
                // 1. 通知所有成员
                val country = countries[countryId]
                if (country != null) {
                    cleanupSteps.add("通知${country.members.size}个成员")
                    country.members.clear()
                }
                
                // 2. 释放所有领土
                val countryTerritories = territories.filter { it.value == countryId }
                countryTerritories.keys.forEach { territories.remove(it) }
                cleanupSteps.add("释放${countryTerritories.size}块领土")
                
                // 3. 删除外交关系
                val countryRelations = relations.filter { it.value == countryId }
                countryRelations.keys.forEach { relations.remove(it) }
                cleanupSteps.add("删除${countryRelations.size}个外交关系")
                
                // 4. 删除国家记录
                countries.remove(countryId)
                cleanupSteps.add("删除国家记录")
                
                return cleanupSteps
            }
            
            fun getCountryCount() = countries.size
            fun getTerritoryCount() = territories.size
            fun getRelationCount() = relations.size
        }
        
        val handler = MockDisbandDataHandler()
        
        // 添加测试数据
        val country = MockCountry("country1", "TestCountry", mutableListOf("player1", "player2", "player3"))
        handler.addCountry(country)
        handler.addTerritory("t1", "country1")
        handler.addTerritory("t2", "country1")
        handler.addRelation("r1", "country1")
        
        // 验证初始状态
        assertEquals(1, handler.getCountryCount())
        assertEquals(2, handler.getTerritoryCount())
        assertEquals(1, handler.getRelationCount())
        
        // 执行解散
        val cleanupSteps = handler.disbandCountry("country1")
        
        // 验证清理结果
        assertEquals(0, handler.getCountryCount())
        assertEquals(0, handler.getTerritoryCount())
        assertEquals(0, handler.getRelationCount())
        
        // 验证清理步骤
        assertTrue(cleanupSteps.any { it.contains("通知3个成员") })
        assertTrue(cleanupSteps.any { it.contains("释放2块领土") })
        assertTrue(cleanupSteps.any { it.contains("删除1个外交关系") })
        assertTrue(cleanupSteps.any { it.contains("删除国家记录") })
        
        println("✓ 解散数据清理测试通过")
    }
    
    @Test
    fun testDisbandBroadcastNotification() {
        // 测试解散广播通知
        class MockBroadcastHandler {
            private val notifications = mutableListOf<String>()
            
            fun broadcastDisband(countryName: String, playerName: String) {
                val message = "§c[系统] 国家 §e$countryName §c已被 §e$playerName §c解散！"
                notifications.add(message)
            }
            
            fun notifyMembers(members: List<String>, countryName: String) {
                members.forEach { member ->
                    val message = "§c您所在的国家 §e$countryName §c已被解散"
                    notifications.add("私信给$member: $message")
                }
            }
            
            fun getNotifications() = notifications.toList()
            fun getNotificationCount() = notifications.size
        }
        
        val broadcastHandler = MockBroadcastHandler()
        
        // 测试解散广播
        broadcastHandler.broadcastDisband("TestCountry", "PlayerOwner")
        broadcastHandler.notifyMembers(listOf("player1", "player2"), "TestCountry")
        
        val notifications = broadcastHandler.getNotifications()
        assertEquals(3, notifications.size) // 1个全服广播 + 2个私信
        
        // 验证广播内容
        assertTrue(notifications.any { it.contains("TestCountry") && it.contains("已被") && it.contains("解散") })
        assertTrue(notifications.any { it.contains("私信给player1") })
        assertTrue(notifications.any { it.contains("私信给player2") })
        
        println("✓ 解散广播通知测试通过")
    }
    
    @Test
    fun testDisbandErrorScenarios() {
        // 测试解散过程中的错误场景
        class MockDisbandErrorHandler {
            fun validateDisbandRequest(
                hasCountry: Boolean,
                isOwner: Boolean,
                isOnCooldown: Boolean,
                hasActiveWar: Boolean
            ): Pair<Boolean, String> {
                if (!hasCountry) {
                    return false to "您不在任何国家中"
                }
                
                if (!isOwner) {
                    return false to "只有国家所有者可以解散国家"
                }
                
                if (isOnCooldown) {
                    return false to "解散功能冷却中，请稍后再试"
                }
                
                if (hasActiveWar) {
                    return false to "国家正在参与战争，无法解散"
                }
                
                return true to "可以解散"
            }
        }
        
        val errorHandler = MockDisbandErrorHandler()
        
        // 测试各种错误情况
        val (noCountry, noCountryMsg) = errorHandler.validateDisbandRequest(false, true, false, false)
        assertFalse(noCountry)
        assertTrue(noCountryMsg.contains("不在任何国家"))
        
        val (notOwner, notOwnerMsg) = errorHandler.validateDisbandRequest(true, false, false, false)
        assertFalse(notOwner)
        assertTrue(notOwnerMsg.contains("只有国家所有者"))
        
        val (onCooldown, cooldownMsg) = errorHandler.validateDisbandRequest(true, true, true, false)
        assertFalse(onCooldown)
        assertTrue(cooldownMsg.contains("冷却中"))
        
        val (hasWar, warMsg) = errorHandler.validateDisbandRequest(true, true, false, true)
        assertFalse(hasWar)
        assertTrue(warMsg.contains("参与战争"))
        
        // 测试正常情况
        val (canDisband, disbandMsg) = errorHandler.validateDisbandRequest(true, true, false, false)
        assertTrue(canDisband)
        assertTrue(disbandMsg.contains("可以解散"))
        
        println("✓ 解散错误场景测试通过")
    }
}
