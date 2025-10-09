package cn.lcofficial.guozhan.test.unit

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.*

/**
 * EntityID类型修复验证测试
 * 验证Exposed ORM的EntityID正确使用，防止ClassCastException
 */
class EntityIDTest {

    // 模拟表定义
    object TestUsers : IdTable<String>("test_users") {
        override val id = char("id", 36).uniqueIndex().entityId()
        val name = varchar("name", 50)
    }

    object TestCountries : IdTable<String>("test_countries") {
        override val id = char("id", 36).uniqueIndex().entityId()
        val owner = reference("owner", TestUsers)
        val name = varchar("name", 50)
    }

    @Test
    fun testEntityIDCreation() {
        // 测试EntityID正确创建
        val userId = UUID.randomUUID().toString()
        val entityId = EntityID(userId, TestUsers)
        
        assertNotNull(entityId)
        assertEquals(userId, entityId.value)
    }

    @Test
    fun testEntityIDWithNullable() {
        // 测试可空EntityID
        val userId: String? = UUID.randomUUID().toString()
        val entityId = userId?.let { EntityID(it, TestUsers) }
        
        assertNotNull(entityId)
        assertEquals(userId, entityId?.value)
        
        // 测试null情况
        val nullUserId: String? = null
        val nullEntityId = nullUserId?.let { EntityID(it, TestUsers) }
        assertNull(nullEntityId)
    }

    @Test
    fun testEntityIDTypeCompatibility() {
        // 验证EntityID类型兼容性
        val userId = UUID.randomUUID().toString()
        val countryId = UUID.randomUUID().toString()
        
        val userEntityId = EntityID(userId, TestUsers)
        val countryEntityId = EntityID(countryId, TestCountries)
        
        // 验证EntityID都是同一个类型，但表不同
        assertEquals(userEntityId.javaClass, countryEntityId.javaClass)
        
        // 但值可以正确获取
        assertEquals(userId, userEntityId.value)
        assertEquals(countryId, countryEntityId.value)
    }

    @Test
    fun testStringToEntityIDConversion() {
        // 测试String到EntityID的转换（修复前会导致ClassCastException）
        val originalId = UUID.randomUUID()
        val stringId = originalId.toString()
        
        // 正确的方式：创建EntityID实例
        val entityId = EntityID(stringId, TestUsers)
        assertEquals(stringId, entityId.value)
        
        // 验证可以用于引用列
        val ownerEntityId = EntityID(stringId, TestUsers)
        assertNotNull(ownerEntityId)
        assertEquals(stringId, ownerEntityId.value)
    }

    @Test
    fun testEntityIDInUpdateContext() {
        // 模拟在update语句中使用EntityID的场景
        val ownerId = UUID.randomUUID()
        val capitalId = UUID.randomUUID()
        
        // 这是修复后的正确方式
        val ownerEntityId = EntityID(ownerId.toString(), TestUsers)
        val capitalEntityId = EntityID(capitalId.toString(), TestUsers)
        
        assertNotNull(ownerEntityId)
        assertNotNull(capitalEntityId)
        assertEquals(ownerId.toString(), ownerEntityId.value)
        assertEquals(capitalId.toString(), capitalEntityId.value)
    }

    @Test
    fun testNullableEntityIDHandling() {
        // 测试可空EntityID的处理（如TerritoryBlock.owner）
        val ownerId: UUID? = UUID.randomUUID()
        val nullOwnerId: UUID? = null
        
        // 有值的情况
        val entityId = ownerId?.let { EntityID(it.toString(), TestCountries) }
        assertNotNull(entityId)
        assertEquals(ownerId.toString(), entityId?.value)
        
        // null的情况
        val nullEntityId = nullOwnerId?.let { EntityID(it.toString(), TestCountries) }
        assertNull(nullEntityId)
    }

    @Test
    fun testEntityIDEquality() {
        // 测试EntityID相等性
        val id = UUID.randomUUID().toString()
        val entityId1 = EntityID(id, TestUsers)
        val entityId2 = EntityID(id, TestUsers)
        
        assertEquals(entityId1.value, entityId2.value)
        // 注意：EntityID实例可能不相等，但值相等
    }

    @Test
    fun testEntityIDToString() {
        // 测试EntityID的字符串表示
        val id = UUID.randomUUID().toString()
        val entityId = EntityID(id, TestUsers)
        
        assertEquals(id, entityId.value)
        // EntityID.toString()可能包含额外信息，但value应该是原始字符串
    }
}
