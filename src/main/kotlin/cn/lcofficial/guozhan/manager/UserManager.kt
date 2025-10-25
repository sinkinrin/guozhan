package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.data.User
import cn.lcofficial.guozhan.data.Users
import org.bukkit.entity.Player
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object UserManager {
    // 🔧 修复问题2：使用 ConcurrentHashMap 确保 Folia 多线程环境下的线程安全
    val users = ConcurrentHashMap<UUID, User>()

    // 🔧 v1.3.25: 预加载状态标记
    @Volatile
    private var isPreloaded = false

    /**
     * 预加载所有用户数据到缓存（同步执行）
     * 🔧 v1.3.25: 在插件启动时调用，避免运行时数据库阻塞
     * 🔧 v1.3.26: 改为同步执行，确保在监听器注册前完成，防止缓存未命中
     */
    fun preloadUsers() {
        try {
            val startTime = System.currentTimeMillis()
            val loadedUsers = transaction {
                Users.selectAll().map { row ->
                    User(
                        uniqueId = UUID.fromString(row[Users.id].value),
                        name = row[Users.name],
                        countryId = row[Users.countryId]?.value?.let { UUID.fromString(it) },
                        rank = row[Users.rank],
                        title = row[Users.title],
                        profession = row[Users.profession],
                        professionLevel = row[Users.professionLevel],
                        professionSetTime = row[Users.professionSetTime],
                        claimMode = row[Users.claimMode]
                    )
                }
            }

            // 直接更新缓存（同步）
            loadedUsers.forEach { user ->
                users[user.uniqueId] = user
            }
            isPreloaded = true
            val duration = System.currentTimeMillis() - startTime
            cn.lcofficial.guozhan.Guozhan.instance.logger.info(
                "预加载了 ${loadedUsers.size} 个用户数据，耗时 ${duration}ms"
            )
        } catch (e: Exception) {
            cn.lcofficial.guozhan.Guozhan.instance.logger.severe("预加载用户数据失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 获取用户（只从缓存读取）
     * 🔧 v1.3.25: 移除数据库查询，只从缓存获取，避免阻塞 region 线程
     */
    fun getUser(uniqueId: UUID): User? {
        return users[uniqueId]
    }

    /**
     * 创建用户（先创建缓存对象，然后异步保存）
     * 🔧 v1.3.25: 避免在 region 线程中执行数据库操作
     * 🔧 v1.3.26: 添加防护措施，防止在预加载期间创建重复用户
     * 🔧 v1.3.54: 修复问题1 (High) - 注册异步任务到DataManager，防止关闭时数据丢失
     */
    fun createUser(uniqueId: UUID, name: String): User {
        // 🔧 v1.3.26: 双重检查，防止并发创建
        users[uniqueId]?.let { return it }

        val user = User(uniqueId, name)
        users[uniqueId] = user

        // 异步保存到数据库，并注册到DataManager
        val future = java.util.concurrent.CompletableFuture.runAsync {
            try {
                transaction {
                    // 检查数据库中是否已存在（防止重复插入）
                    val exists = Users.selectAll()
                        .where { Users.id eq uniqueId.toString() }
                        .count() > 0

                    if (!exists) {
                        Users.insert {
                            it[Users.id] = user.uniqueId.toString()
                            it[Users.name] = user.name
                            it[Users.claimMode] = user.claimMode
                        }
                    }
                }
            } catch (e: Exception) {
                // 忽略主键重复错误（说明已存在）
                val message = e.message ?: ""
                val isDuplicateError = message.contains("UNIQUE constraint failed") ||
                                      message.contains("Duplicate entry")

                if (!isDuplicateError) {
                    cn.lcofficial.guozhan.Guozhan.instance.logger.severe(
                        "异步保存用户失败 ($uniqueId): ${e.message}"
                    )
                    e.printStackTrace()
                }
            }
        }.thenApply { null as Void? } as java.util.concurrent.CompletableFuture<Void>

        DataManager.registerAsyncTask(future)

        return user
    }

    fun Player.user(): User {
        return getUser(uniqueId) ?: createUser(uniqueId, name)
    }
    fun clearCache() {
        users.clear()
        isPreloaded = false
    }

}
