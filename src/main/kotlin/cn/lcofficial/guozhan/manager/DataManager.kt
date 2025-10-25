package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.util.MigrationUtils
import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.config.Config
import cn.lcofficial.guozhan.data.Cities
import cn.lcofficial.guozhan.data.ClaimProgresses
import cn.lcofficial.guozhan.data.Countries
import cn.lcofficial.guozhan.data.CountryTechnologies
import cn.lcofficial.guozhan.data.DiplomaticRelations
import cn.lcofficial.guozhan.data.Technologies
import cn.lcofficial.guozhan.data.Territories
import cn.lcofficial.guozhan.data.TerritoryBlocks
import cn.lcofficial.guozhan.data.Users
import cn.lcofficial.guozhan.data.WarEvents
import cn.lcofficial.guozhan.pluginLogger
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.*
import java.io.File
import java.util.logging.Level
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections

object DataManager {
    lateinit var dataSource: HikariDataSource

    // 🔧 v1.3.52: 跟踪所有异步保存任务，防止服务器关闭时数据丢失
    private val pendingAsyncTasks = Collections.newSetFromMap(ConcurrentHashMap<CompletableFuture<Void>, Boolean>())

    fun init(plugin: Guozhan) {
        // 🔧 v1.3.51: 修复热重载问题 - 关闭数据源前等待异步操作完成
        if (::dataSource.isInitialized) {
            // 等待所有异步保存操作完成
            waitForAsyncOperations()
            dataSource.close()
        }
        dataSource = HikariDataSource()
        when (Config.Database.type) {
            Config.Database.Type.MYSQL -> {
                // 🔧 修复问题1：添加字符编码参数确保中文字段正确存储
                dataSource.jdbcUrl =
                    "jdbc:mysql://${Config.Database.host}:${Config.Database.port}/${Config.Database.database}?useSSL=false&serverTimezone=UTC&useUnicode=true&characterEncoding=UTF-8"
                dataSource.username = Config.Database.username
                dataSource.password = Config.Database.password
                dataSource.driverClassName = "com.mysql.cj.jdbc.Driver"
                // MySQL 连接池配置
                dataSource.maximumPoolSize = 10
                dataSource.minimumIdle = 5
                dataSource.connectionTimeout = 30000
                dataSource.idleTimeout = 600000
                dataSource.maxLifetime = 1800000
                dataSource.connectionTestQuery = "SELECT 1"
                pluginLogger.info("正在连接数据库MySQL: ${Config.Database.host}:${Config.Database.port}/${Config.Database.database}")
                pluginLogger.info("MySQL 连接池配置: maximumPoolSize=10, minimumIdle=5")
            }

            else -> {
                // 🔧 修复问题1：SQLite 连接池配置 - 强制单连接避免 database is locked 错误
                // 🔧 修复问题2：启用外键约束确保数据完整性
                dataSource.jdbcUrl =
                    "jdbc:sqlite:${plugin.dataFolder.path}${File.separator}guozhan.db?journal_mode=WAL&foreign_keys=on"
                dataSource.driverClassName = "org.sqlite.JDBC"
                // SQLite 必须使用单连接池，避免并发写入冲突
                dataSource.maximumPoolSize = 1
                dataSource.minimumIdle = 1
                pluginLogger.info("SQLite 外键约束已启用 (foreign_keys=on)")
                dataSource.connectionTimeout = 30000
                dataSource.idleTimeout = 600000
                dataSource.maxLifetime = 1800000
                dataSource.leakDetectionThreshold = 0 // 关闭连接泄漏检测
                dataSource.connectionTestQuery = "SELECT 1"
                pluginLogger.info("正在连接数据库SQLite: ${plugin.dataFolder.path}${File.separator}guozhan.db")
                pluginLogger.info("SQLite 连接池配置: maximumPoolSize=1 (单连接模式，避免并发冲突)")
            }
        }
        try {
            Database.connect(dataSource)
            transaction {
                // 🔧 v1.3.52: 添加 WarEvents 表到数据库迁移
                MigrationUtils.statementsRequiredForDatabaseMigration(
                    Users, Countries, Cities, TerritoryBlocks, Territories,
                    DiplomaticRelations, Technologies, CountryTechnologies, ClaimProgresses, WarEvents
                ).forEach(::exec)
            }
            pluginLogger.info("连接成功")

            // 执行数据完整性检查和修复
            performDataIntegrityCheck()
        } catch (e: Exception) {
            // 🔧 v1.3.52: 修复数据库连接失败后插件仍继续运行 - 抛出异常终止插件启动
            pluginLogger.log(Level.SEVERE, "数据库连接失败，插件将被禁用", e)
            pluginLogger.severe("=".repeat(60))
            pluginLogger.severe("数据库连接失败！请检查以下配置：")
            pluginLogger.severe("1. 数据库类型：${Config.Database.type}")
            if (Config.Database.type == Config.Database.Type.MYSQL) {
                pluginLogger.severe("2. MySQL 主机：${Config.Database.host}:${Config.Database.port}")
                pluginLogger.severe("3. MySQL 数据库名：${Config.Database.database}")
                pluginLogger.severe("4. MySQL 用户名：${Config.Database.username}")
                pluginLogger.severe("5. 请确认 MySQL 服务已启动且用户名密码正确")
            } else {
                pluginLogger.severe("2. SQLite 数据库文件：${plugin.dataFolder.path}${File.separator}guozhan.db")
                pluginLogger.severe("3. 请确认插件有读写权限")
            }
            pluginLogger.severe("=".repeat(60))

            // 抛出异常，让 Guozhan.onEnable() 捕获并禁用插件
            throw IllegalStateException("数据库连接失败，无法启动插件", e)
        }
    }

    /**
     * 执行数据完整性检查和修复
     */
    private fun performDataIntegrityCheck() {
        try {
            pluginLogger.info("开始执行数据完整性检查...")

            var fixedCount = 0

            transaction {
                // 查找所有国家所有者的rank不是OWNER的情况
                val problematicOwners = (Countries innerJoin Users)
                    .select(Countries.id, Countries.name, Users.id, Users.name, Users.rank)
                    .where { Countries.owner eq Users.id and (Users.rank neq cn.lcofficial.guozhan.data.Rank.OWNER) }
                    .toList()

                if (problematicOwners.isNotEmpty()) {
                    pluginLogger.warning("发现 ${problematicOwners.size} 个国家所有者的rank不正确，正在修复...")

                    for (row in problematicOwners) {
                        val countryId = row[Countries.id].value
                        val countryName = row[Countries.name]
                        val userId = row[Users.id].value
                        val userName = row[Users.name]
                        val currentRank = row[Users.rank]

                        // 修复rank为OWNER
                        Users.update({ Users.id eq userId }) {
                            it[Users.rank] = cn.lcofficial.guozhan.data.Rank.OWNER
                        }

                        fixedCount++
                        pluginLogger.info("已修复国家 '$countryName' 的所有者 '$userName' 的rank: $currentRank -> OWNER")
                    }
                } else {
                    pluginLogger.info("所有国家所有者的rank都正确")
                }
            }

            if (fixedCount > 0) {
                pluginLogger.info("数据完整性检查完成，共修复了 $fixedCount 个问题")
            } else {
                pluginLogger.info("数据完整性检查完成，未发现问题")
            }

        } catch (e: Exception) {
            pluginLogger.severe("数据完整性检查失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 清空所有数据库表
     * 警告：此操作将删除所有数据！
     * 🔧 v1.3.28: 同时清空所有缓存，包括坐标索引和成员缓存
     */
    fun clearAllTables() {
        transaction {
            // 按照外键依赖关系的逆序删除
            CountryTechnologies.deleteAll()
            Technologies.deleteAll()
            DiplomaticRelations.deleteAll()
            TerritoryBlocks.deleteAll()
            Territories.deleteAll()
            Cities.deleteAll()
            Users.deleteAll()
            Countries.deleteAll()
        }

        // 🔧 v1.3.28: 清空所有内存缓存，确保与数据库状态一致
        UserManager.users.clear()
        CountryManager.countries.clear()
        CityManager.cities.clear()
        TerritoryManager.territories.clear()

        // 🔧 v1.3.28: 关键修复 - 清空坐标索引缓存
        TerritoryManager.territoryByCoords.clear()

        // 🔧 v1.3.28: 清空国家成员缓存
        CountryManager.memberCache.clear()

        // 🔧 v1.3.51: 修复科技缓存不重置问题 - 清空科技管理器缓存
        cn.lcofficial.guozhan.manager.TechnologyManager.clearCache()
        cn.lcofficial.guozhan.manager.TechEffectManager.stopEffectUpdateTask()

        pluginLogger.warning("数据库已清空！所有数据和缓存已删除")
    }

    /**
     * 关闭数据库连接池
     * 🔧 v1.3.39: 修复数据库连接池清理缺失 - 插件卸载时调用
     * 🔧 v1.3.52: 修复问题1 (High) - 关闭前等待所有异步操作完成，防止数据丢失
     */
    fun shutdown() {
        if (::dataSource.isInitialized && !dataSource.isClosed) {
            // 等待所有异步保存操作完成
            waitForAsyncOperations()
            dataSource.close()
            pluginLogger.info("数据库连接池已关闭")
        }
    }

    /**
     * 注册异步任务
     * 🔧 v1.3.52: 用于跟踪所有异步数据库写入任务，防止服务器关闭时数据丢失
     */
    fun registerAsyncTask(task: CompletableFuture<Void>) {
        pendingAsyncTasks.add(task)
        task.whenComplete { _, _ ->
            pendingAsyncTasks.remove(task)
        }
    }

    fun getPendingAsyncTaskCount(): Int = pendingAsyncTasks.size

    /**
     * 等待异步操作完成
     * 🔧 v1.3.52: 修复数据丢失风险 - 等待所有异步任务完成或超时
     */
    private fun waitForAsyncOperations() {
        try {
            val startTime = System.currentTimeMillis()
            val timeout = 10000L // 10秒超时

            if (pendingAsyncTasks.isEmpty()) {
                Guozhan.instance.logger.info("[数据库] 无待处理的异步保存任务")
                return
            }

            Guozhan.instance.logger.info("[数据库] 等待 ${pendingAsyncTasks.size} 个异步保存任务完成...")

            while (pendingAsyncTasks.isNotEmpty()) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed > timeout) {
                    Guozhan.instance.logger.warning("[数据库] 等待异步任务超时，仍有 ${pendingAsyncTasks.size} 个任务未完成")
                    break
                }
                Thread.sleep(100)

                // 每秒输出一次进度
                if (elapsed % 1000 < 100) {
                    Guozhan.instance.logger.info("[数据库] 等待中... 剩余 ${pendingAsyncTasks.size} 个任务，已等待 ${elapsed}ms")
                }
            }

            if (pendingAsyncTasks.isEmpty()) {
                Guozhan.instance.logger.info("[数据库] 所有异步保存任务已完成")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Guozhan.instance.logger.warning("[数据库] 等待异步操作时被中断")
        }
    }
}
