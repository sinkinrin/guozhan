# Exposed ORM 参考文档

## 概述

Exposed是JetBrains开发的轻量级Kotlin SQL ORM框架，提供类型安全的DSL和DAO API，支持JDBC和R2DBC驱动。

## 核心概念

### 事务管理

#### 基本事务
```kotlin
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

transaction {
    // DSL/DAO操作
}
```

#### 配置事务
```kotlin
transaction(Connection.TRANSACTION_SERIALIZABLE, true, db = db) {
    // 指定隔离级别、只读模式和数据库
}
```

#### 异步事务
```kotlin
suspend fun <T> newSuspendedTransaction(
    context: CoroutineContext? = null,
    db: Database? = null,
    transactionIsolation: Int? = null,
    readOnly: Boolean? = null,
    statement: suspend JdbcTransaction.() -> T
): T
```

### 表定义

#### DSL方式
```kotlin
object Cities : Table() {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 50)
    override val primaryKey = PrimaryKey(id)
}

object Users : Table() {
    val id = varchar("id", 10)
    val name = varchar("name", length = 50)
    val cityId = integer("city_id").references(Cities.id).nullable()
    override val primaryKey = PrimaryKey(id, name = "PK_User_ID")
}
```

#### DAO方式
```kotlin
object Cities: IntIdTable() {
    val name = varchar("name", 50)
}

object Users : IntIdTable() {
    val name = varchar("name", length = 50).index()
    val city = reference("city", Cities)
    val age = integer("age")
}
```

### 实体类定义

```kotlin
class City(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<City>(Cities)
    
    var name by Cities.name
    val users by User referrersOn Users.city
}

class User(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<User>(Users)
    
    var name by Users.name
    var city by City referencedOn Users.city
    var age by Users.age
}
```

## CRUD操作

### 插入数据
```kotlin
// DSL方式
val saintPetersburgId = Cities.insert {
    it[name] = "St. Petersburg"
} get Cities.id

// DAO方式
val saintPetersburg = City.new {
    name = "St. Petersburg"
}
```

### 查询数据
```kotlin
// DSL方式
Cities.selectAll().where { Cities.id eq pragueId }

// 复杂查询
(Users innerJoin Cities)
    .select(Users.name, Cities.name)
    .where {
        (Users.id.eq("andrey") or Users.name.eq("Sergey")) and
        Users.id.eq("sergey") and Users.cityId.eq(Cities.id)
    }

// DAO方式
val adults = User.find { Users.age greaterEq 18 }
```

### 更新数据
```kotlin
// DSL方式
Users.update(where = { Users.id eq "alex" }) {
    it[name] = "Alexey"
}

// DAO方式
alex.name = "Alexey" // 自动在事务结束时更新
```

### 删除数据
```kotlin
// DSL方式
Users.deleteWhere { Users.name like "%thing" }

// DAO方式
user.delete()
```

## GuoZhan项目中的应用

### 数据模型
```kotlin
// 用户表
object Users : Table("gz_users") {
    val id = uuid("id")
    val name = varchar("name", 50)
    val countryId = integer("country_id").nullable()
    val rank = varchar("rank", 20).nullable()
    val title = varchar("title", 50).nullable()
    val profession = varchar("profession", 30).nullable()
    val professionLevel = integer("profession_level").default(1)
    
    override val primaryKey = PrimaryKey(id)
}

// 国家表
object Countries : Table("gz_countries") {
    val id = integer("id").autoIncrement()
    val owner = uuid("owner").references(Users.id)
    val name = varchar("name", 50).uniqueIndex()
    val capital = varchar("capital", 100).nullable()
    val createTime = long("create_time")
    val public = bool("public").default(false)
    val shield = bool("shield").default(false)
    val gold = integer("gold").default(0)
    val diamond = integer("diamond").default(0)
    val coreHealth = integer("core_health").default(1000)
    // ... 更多字段
    
    override val primaryKey = PrimaryKey(id)
}
```

### 数据访问层
```kotlin
object UserManager {
    fun getUser(uuid: UUID): User? = transaction {
        Users.select { Users.id eq uuid }.singleOrNull()?.let {
            User(
                id = it[Users.id],
                name = it[Users.name],
                countryId = it[Users.countryId],
                // ... 映射其他字段
            )
        }
    }
    
    fun createUser(user: User): Unit = transaction {
        Users.insert {
            it[id] = user.id
            it[name] = user.name
            it[countryId] = user.countryId
            // ... 设置其他字段
        }
    }
}
```

### 异步数据库操作
```kotlin
// 在Folia环境中，数据库操作应该异步执行
suspend fun updateUserAsync(userId: UUID, updates: UserUpdates) {
    newSuspendedTransaction {
        Users.update({ Users.id eq userId }) {
            updates.name?.let { name -> it[Users.name] = name }
            updates.countryId?.let { countryId -> it[Users.countryId] = countryId }
            // ... 其他更新
        }
    }
}
```

## 最佳实践

### 1. 事务管理
- 保持事务简短，避免长时间持有连接
- 在Folia环境中使用异步事务
- 合理设置事务隔离级别

### 2. 查询优化
- 使用索引优化查询性能
- 避免N+1查询问题
- 合理使用JOIN操作

### 3. 连接池配置
```kotlin
Database.connect(
    url = "jdbc:mysql://localhost:3306/guozhan",
    driver = "com.mysql.cj.jdbc.Driver",
    user = "username",
    password = "password"
) {
    // HikariCP配置
    maximumPoolSize = 10
    minimumIdle = 5
    connectionTimeout = 30000
    idleTimeout = 600000
    maxLifetime = 1800000
}
```

### 4. 错误处理
```kotlin
try {
    transaction {
        // 数据库操作
    }
} catch (e: SQLException) {
    logger.error("数据库操作失败", e)
    // 错误处理逻辑
}
```

## 性能优化

1. **批量操作**：使用batchInsert进行批量插入
2. **连接池调优**：根据服务器负载调整连接池参数
3. **查询缓存**：对频繁查询的数据进行缓存
4. **索引优化**：为常用查询字段添加索引
5. **分页查询**：对大量数据使用limit和offset

---

*参考来源: Exposed ORM 官方文档*
