package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.config.TechnologyConfig
import cn.lcofficial.guozhan.data.*
import cn.lcofficial.guozhan.economy.RegionalTaxSystem
import cn.lcofficial.guozhan.util.runRepeat
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Connection
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.ceil

/**
 * 科技系统管理器
 * 负责科技数据管理、研究状态跟踪、效果应用等核心功能
 */
object TechnologyManager {

    // 国家科技状态缓存(国家ID -> (科技ID -> 科技状态))
    private val countryTechnologies = ConcurrentHashMap<UUID, ConcurrentHashMap<String, CountryTechnology>>()

    // 🔧 v1.3.43: 修复科技研究在区域线程中直接修改共享状态 - 使用线程安全的Set
    // 正在研究的科技 (国家ID -> 科技ID列表)
    private val researchingTechnologies = ConcurrentHashMap<UUID, MutableSet<String>>()
    private val progressNotificationTasks = ConcurrentHashMap<Pair<UUID, String>, MutableList<ScheduledTask>>()
    private var researchCompletionTask: ScheduledTask? = null

    /**
     * 初始化科技管理器
     */
    fun initialize() {
        Guozhan.instance.logger.info("正在初始化科技管理器...")

        // 🔧 v1.3.51: 修复科技研发系统数据库错误 - 添加数据库健康检查和修复
        performDatabaseHealthCheck()
        cancelResearchSchedulers()
        resetCaches()


        // 创建数据库表
        transaction {
            SchemaUtils.createMissingTablesAndColumns(Technologies, CountryTechnologies)
        }

        ensureTechnologyTablePrimaryKey()

        // 🔧 v1.3.49: 修复科技研发系统外键约束错误 - 插入科技数据到数据库
        insertTechnologiesToDatabase()

        // 加载所有国家的科技状态
        loadAllCountryTechnologies()

        // 启动研究完成检查任务
        startResearchCompletionTask()

        Guozhan.instance.logger.info("科技管理器初始化完成")
    }

    /**
     * 加载所有国家的科技状态
     */
    private fun loadAllCountryTechnologies() = transaction {
        countryTechnologies.clear()
        researchingTechnologies.clear()

        val results = CountryTechnologies.selectAll()

        results.forEach { row ->
            val countryId = UUID.fromString(row[CountryTechnologies.countryId])
            val technologyId = row[CountryTechnologies.technologyId]
            val level = row[CountryTechnologies.level]
            val researchStartTime = row[CountryTechnologies.researchStartTime]
            val researchEndTime = row[CountryTechnologies.researchEndTime]
            val isResearching = row[CountryTechnologies.isResearching]

            val countryTech = CountryTechnology(
                countryId = countryId,
                technologyId = technologyId,
                level = level,
                researchStartTime = researchStartTime,
                researchEndTime = researchEndTime,
                isResearching = isResearching
            )

            // 添加到缓存
            countryTechnologies.computeIfAbsent(countryId) { ConcurrentHashMap() }[technologyId] = countryTech

            // 如果正在研究，添加到研究列表
            if (isResearching) {
                // 🔧 v1.3.45: 修复科技研究缓存使用非线程安全集合 - 使用ConcurrentHashMap.newKeySet()
                researchingTechnologies.computeIfAbsent(countryId) { ConcurrentHashMap.newKeySet() }.add(technologyId)
            }
        }

        Guozhan.instance.logger.info("已加载 ${results.count()} 条科技研究记录")
    }

    /**
     * 🔧 v1.3.49: 修复科技研发系统外键约束错误 - 插入科技数据到数据库
     */
    private fun insertTechnologiesToDatabase() = transaction {
        val configTechnologies = TechnologyConfig.getAllTechnologies()

        configTechnologies.forEach { (techId, technology) ->
            try {
                // 检查科技是否已存在
                val existingTech = Technologies.selectAll().where { Technologies.id eq techId }.singleOrNull()

                if (existingTech == null) {
                    // 插入新科技
                    Technologies.insert {
                        it[id] = techId
                        it[name] = technology.name
                        it[description] = technology.description
                        it[icon] = technology.icon.name
                        it[maxLevel] = technology.maxLevel
                        it[prerequisites] = technology.prerequisites.joinToString(",")
                        it[costs] = serializeCosts(technology.costs)
                        it[effects] = serializeEffects(technology.effects)
                        it[category] = technology.category
                        it[enabled] = technology.enabled
                    }
                    Guozhan.instance.logger.info("🔧 [科技数据库] 已插入科技: ${technology.name} (${techId})")
                } else {
                    // 更新现有科技
                    Technologies.update({ Technologies.id eq techId }) {
                        it[name] = technology.name
                        it[description] = technology.description
                        it[icon] = technology.icon.name
                        it[maxLevel] = technology.maxLevel
                        it[prerequisites] = technology.prerequisites.joinToString(",")
                        it[costs] = serializeCosts(technology.costs)
                        it[effects] = serializeEffects(technology.effects)
                        it[category] = technology.category
                        it[enabled] = technology.enabled
                    }
                    Guozhan.instance.logger.info("🔧 [科技数据库] 已更新科技: ${technology.name} (${techId})")
                }
                val triggersToDrop = mutableListOf<String>()
                exec("SELECT name FROM sqlite_master WHERE type='trigger' AND sql LIKE '%backup%'") { rs ->
                    while (rs.next()) {
                        triggersToDrop.add(rs.getString("name"))
                    }
                }
                triggersToDrop.forEach { triggerName ->
                    try {
                        exec("DROP TRIGGER IF EXISTS \"$triggerName\"")
                        Guozhan.instance.logger.info("🔧 [数据库清理] 已删除触发器: $triggerName")
                    } catch (e: Exception) {
                        Guozhan.instance.logger.warning("🔧 [数据库清理] 删除触发器 $triggerName 失败: ${e.message}")
                    }
                }

                val viewsToDrop = mutableListOf<String>()
                exec("SELECT name FROM sqlite_master WHERE type='view' AND sql LIKE '%backup%'") { rs ->
                    while (rs.next()) {
                        viewsToDrop.add(rs.getString("name"))
                    }
                }
                viewsToDrop.forEach { viewName ->
                    try {
                        exec("DROP VIEW IF EXISTS \"$viewName\"")
                        Guozhan.instance.logger.info("🔧 [数据库清理] 已删除视图: $viewName")
                    } catch (e: Exception) {
                        Guozhan.instance.logger.warning("🔧 [数据库清理] 删除视图 $viewName 失败: ${e.message}")
                    }
                }

            } catch (e: Exception) {
                Guozhan.instance.logger.severe("插入/更新科技 ${techId} 到数据库时出错: ${e.message}")
                e.printStackTrace()
            }
        }

        Guozhan.instance.logger.info("🔧 [科技数据库] 科技数据同步完成，共处理 ${configTechnologies.size} 个科技")
    }

    /**
     * 🔧 v1.3.51: 修复科技研发系统数据库错误 - 数据库健康检查和修复
     */
    private fun performDatabaseHealthCheck() {
        try {
            Guozhan.instance.logger.info("🔧 [数据库健康检查] 开始检查科技系统数据库完整性...")

            // 1. 清理孤立的备份表
            cleanupOrphanedBackupTables()

            // 2. 验证表存在性和结构
            validateTableStructure()

            // 3. 验证外键约束
            validateForeignKeyConstraints()

            Guozhan.instance.logger.info("🔧 [数据库健康检查] 科技系统数据库健康检查完成")
        } catch (e: Exception) {
            Guozhan.instance.logger.severe("🔧 [数据库健康检查] 数据库健康检查失败，尝试自动修复: ${e.message}")
            e.printStackTrace()
            attemptAutoRepair()
        }
    }

    /**
     * 🔧 v1.3.51: 清理孤立的备份表
     */
    private fun cleanupOrphanedBackupTables() {
        transaction {
            try {
                // 检查并删除所有可能的备份表
                val backupTables = listOf(
                    "gz_technologies_backup",
                    "gz_country_technologies_backup",
                    "gz_technologies_temp"
                )

                backupTables.forEach { tableName ->
                    try {
                        exec("DROP TABLE IF EXISTS $tableName")
                        Guozhan.instance.logger.info("🔧 [清理备份表] 已清理备份表: $tableName")
                    } catch (e: Exception) {
                        Guozhan.instance.logger.warning("⚠️ [清理备份表] 清理备份表$tableName 失败: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Guozhan.instance.logger.warning("⚠️ [清理备份表] 清理备份表过程中出错: ${e.message}")
            }
        }
    }

    /**
     * 🔧 v1.3.51: 验证表结构
     */
    private fun validateTableStructure() {
        transaction {
            try {
                // 检查 gz_technologies 表是否存在且结构正确
                val techTableExists = exec("SELECT name FROM sqlite_master WHERE type='table' AND name='gz_technologies'") { rs ->
                    rs.next()
                } ?: false

                if (!techTableExists) {
                    Guozhan.instance.logger.warning("⚠️ [表结构验证] gz_technologies 表不存在，将重新创建")
                    SchemaUtils.create(Technologies)
                }

                // 检查 gz_country_technologies 表是否存在且结构正确
                val countryTechTableExists = exec("SELECT name FROM sqlite_master WHERE type='table' AND name='gz_country_technologies'") { rs ->
                    rs.next()
                } ?: false

                if (!countryTechTableExists) {
                    Guozhan.instance.logger.warning("⚠️ [表结构验证] gz_country_technologies 表不存在，将重新创建")
                    SchemaUtils.create(CountryTechnologies)
                }

                Guozhan.instance.logger.info("🔧 [表结构验证] 表结构验证完成")
            } catch (e: Exception) {
                Guozhan.instance.logger.severe("🔧 [表结构验证] 表结构验证失败: ${e.message}")
                throw e
            }
        }
    }

    /**
     * 🔧 v1.3.51: 验证外键约束
     */
    private fun validateForeignKeyConstraints(): Boolean {
        return transaction {
            try {
                // 检查外键约束状态
                val foreignKeysEnabled = exec("PRAGMA foreign_keys") { rs ->
                    if (rs.next()) rs.getInt(1) == 1 else false
                } ?: false

                if (!foreignKeysEnabled) {
                    Guozhan.instance.logger.warning("⚠️ [外键约束验证] 外键约束未启用，正在启用...")
                    exec("PRAGMA foreign_keys=on")
                }

                // 检查外键约束违规
                val violations = exec("PRAGMA foreign_key_check") { rs ->
                    val violations = mutableListOf<String>()
                    while (rs.next()) {
                        violations.add("表: ${rs.getString(1)}, 行ID: ${rs.getString(2)}, 父表: ${rs.getString(3)}, 外键ID: ${rs.getString(4)}")
                    }
                    violations
                } ?: emptyList()

                if (violations.isNotEmpty()) {
                    Guozhan.instance.logger.severe("🔧 [外键约束验证] 发现外键约束违规:")
                    violations.forEach { violation ->
                        Guozhan.instance.logger.severe("  - $violation")
                    }
                    return@transaction false
                }

                Guozhan.instance.logger.info("🔧 [外键约束验证] 外键约束验证通过")
                true
            } catch (e: Exception) {
                Guozhan.instance.logger.severe("🔧 [外键约束验证] 外键约束验证失败: ${e.message}")
                false
            }
        }
    }

    /**
     * 🔧 v1.3.51: 尝试自动修复数据库问题
     * 🔧 v1.3.52: 修复数据丢失风险 - 只有在备份数据完整可用时才执行删除和重建操作
     */
    private fun attemptAutoRepair() {
        try {
            Guozhan.instance.logger.info("🔧 [自动修复] 开始尝试自动修复数据库问题...")

            transaction {
                // 1. 关闭外键约束
                exec("PRAGMA foreign_keys=off")

                try {
                    // 2. 备份现有数据
                    val existingTechData = try {
                        Technologies.selectAll().toList()
                    } catch (e: Exception) {
                        Guozhan.instance.logger.warning("⚠️ [自动修复] 无法读取现有科技数据: ${e.message}")
                        null // 🔧 使用 null 表示备份失败
                    }

                    val existingResearchData = try {
                        CountryTechnologies.selectAll().toList()
                    } catch (e: Exception) {
                        Guozhan.instance.logger.warning("⚠️ [自动修复] 无法读取现有研究数据: ${e.message}")
                        null // 🔧 使用 null 表示备份失败
                    }

                    // 🔧 v1.3.52: 安全检查 - 只有在备份数据完整可用时才执行删除操作
                    if (existingTechData == null || existingResearchData == null) {
                        Guozhan.instance.logger.severe("❌ [自动修复] 备份数据不完整，终止自动修复流程以避免数据丢失")
                        Guozhan.instance.logger.severe("❌ [自动修复] 建议人工干预：")
                        Guozhan.instance.logger.severe("   1. 检查数据库文件是否损坏")
                        Guozhan.instance.logger.severe("   2. 尝试手动备份数据库文件")
                        Guozhan.instance.logger.severe("   3. 联系管理员进行数据恢复")
                        return@transaction // 🔧 立即终止修复流程
                    }

                    // 🔧 v1.3.52: 验证备份数据的完整性
                    val techDataCount = existingTechData.size
                    val researchDataCount = existingResearchData.size
                    Guozhan.instance.logger.info("🔧 [自动修复] 备份数据完整性检查：科技数据 ${techDataCount} 条，研究数据 ${researchDataCount} 条")

                    // 3. 删除所有相关表（只有在备份数据可用时才执行）
                    try {
                        SchemaUtils.drop(CountryTechnologies)
                        Guozhan.instance.logger.info("🔧 [自动修复] 已删除 CountryTechnologies 表")
                    } catch (e: Exception) {
                        Guozhan.instance.logger.severe("❌ [自动修复] 删除 CountryTechnologies 表失败: ${e.message}")
                        throw e // 🔧 抛出异常，触发回滚
                    }

                    try {
                        SchemaUtils.drop(Technologies)
                        Guozhan.instance.logger.info("🔧 [自动修复] 已删除 Technologies 表")
                    } catch (e: Exception) {
                        Guozhan.instance.logger.severe("❌ [自动修复] 删除 Technologies 表失败: ${e.message}")
                        throw e // 🔧 抛出异常，触发回滚
                    }

                    // 4. 重新创建表
                    SchemaUtils.create(Technologies, CountryTechnologies)
                    Guozhan.instance.logger.info("🔧 [自动修复] 已重新创建表结构")

                    // 5. 恢复科技数据
                    var techRestoreSuccess = 0
                    var techRestoreFailed = 0
                    existingTechData.forEach { row ->
                        try {
                            Technologies.insert {
                                it[Technologies.id] = row[Technologies.id]
                                it[Technologies.name] = row[Technologies.name]
                                it[Technologies.description] = row[Technologies.description]
                                it[Technologies.icon] = row[Technologies.icon]
                                it[Technologies.maxLevel] = row[Technologies.maxLevel]
                                it[Technologies.prerequisites] = row[Technologies.prerequisites]
                                it[Technologies.costs] = row[Technologies.costs]
                                it[Technologies.effects] = row[Technologies.effects]
                                it[Technologies.category] = row[Technologies.category]
                                it[Technologies.enabled] = row[Technologies.enabled]
                            }
                            techRestoreSuccess++
                        } catch (e: Exception) {
                            techRestoreFailed++
                            Guozhan.instance.logger.warning("⚠️ [自动修复] 恢复科技数据失败 (${row[Technologies.id]}): ${e.message}")
                        }
                    }
                    Guozhan.instance.logger.info("🔧 [自动修复] 科技数据恢复完成：成功 ${techRestoreSuccess} 条，失败 ${techRestoreFailed} 条")

                    // 6. 恢复研究数据
                    var researchRestoreSuccess = 0
                    var researchRestoreFailed = 0
                    existingResearchData.forEach { row ->
                        try {
                            CountryTechnologies.insert {
                                it[CountryTechnologies.countryId] = row[CountryTechnologies.countryId]
                                it[CountryTechnologies.technologyId] = row[CountryTechnologies.technologyId]
                                it[CountryTechnologies.level] = row[CountryTechnologies.level]
                                it[CountryTechnologies.researchStartTime] = row[CountryTechnologies.researchStartTime]
                                it[CountryTechnologies.researchEndTime] = row[CountryTechnologies.researchEndTime]
                                it[CountryTechnologies.isResearching] = row[CountryTechnologies.isResearching]
                            }
                            researchRestoreSuccess++
                        } catch (e: Exception) {
                            researchRestoreFailed++
                            Guozhan.instance.logger.warning("⚠️ [自动修复] 恢复研究数据失败: ${e.message}")
                        }
                    }
                    Guozhan.instance.logger.info("🔧 [自动修复] 研究数据恢复完成：成功 ${researchRestoreSuccess} 条，失败 ${researchRestoreFailed} 条")

                    // 🔧 v1.3.52: 验证恢复结果
                    if (techRestoreFailed > 0 || researchRestoreFailed > 0) {
                        Guozhan.instance.logger.warning("⚠️ [自动修复] 部分数据恢复失败，请检查日志并考虑人工干预")
                    }

                } finally {
                    // 7. 重新启用外键约束
                    exec("PRAGMA foreign_keys=on")
                }
            }

            Guozhan.instance.logger.info("🔧 [自动修复] 数据库自动修复完成")
        } catch (e: Exception) {
            Guozhan.instance.logger.severe("🔧 [自动修复] 数据库自动修复失败: ${e.message}")
            Guozhan.instance.logger.severe("❌ [自动修复] 建议人工干预：")
            Guozhan.instance.logger.severe("   1. 停止服务器")
            Guozhan.instance.logger.severe("   2. 备份数据库文件 (plugins/Guozhan/guozhan.db)")
            Guozhan.instance.logger.severe("   3. 检查数据库完整性")
            Guozhan.instance.logger.severe("   4. 联系管理员进行数据恢复")
            e.printStackTrace()
        }
    }

    private fun ensureTechnologyTablePrimaryKey() {
        transaction {
            val columnInfo = exec("PRAGMA table_info('gz_technologies')") { rs ->
                val info = mutableListOf<Pair<String, Int>>()
                while (rs.next()) {
                    info += rs.getString("name") to rs.getInt("pk")
                }
                info
            } ?: emptyList()

            val hasPrimaryKey = columnInfo.any { (name, isPk) -> name.equals("id", ignoreCase = true) && isPk > 0 }

            if (!hasPrimaryKey && columnInfo.isNotEmpty()) {
                Guozhan.instance.logger.warning("⚠️ 检测到 gz_technologies 表缺少主键，将自动重建以修复外键约束")
                rebuildTechnologyTableSafely()
            }
        }
    }

    /**
     * 🔧 v1.3.51: 安全地重建科技表
     */
    private fun rebuildTechnologyTableSafely() {
        transaction {
            val startTime = System.currentTimeMillis()
            val jdbcConnection = this.connection.connection as Connection

            fun execute(sql: String) {
                jdbcConnection.createStatement().use { stmt ->
                    stmt.execute(sql)
                }
            }

            // 使用时间戳创建唯一的临时表名，避免冲突
            val tempTableName = "gz_technologies_temp_${System.currentTimeMillis()}"

            try {
                // 1. 清理任何可能存在的备份表
                execute("DROP TABLE IF EXISTS gz_technologies_backup")
                execute("DROP TABLE IF EXISTS $tempTableName")

                // 2. 关闭外键约束
                execute("PRAGMA foreign_keys=off")

                // 3. 创建临时表并复制数据
                execute("CREATE TABLE $tempTableName AS SELECT * FROM gz_technologies")

                // 4. 删除原表
                execute("DROP TABLE gz_technologies")

                // 5. 重新创建表（使用Exposed的schema）
                SchemaUtils.create(Technologies)

                // 6. 从临时表恢复数据
                execute(
                    """
                    INSERT INTO gz_technologies (id, name, description, icon, max_level, prerequisites, costs, effects, category, enabled)
                    SELECT id, name, description, icon, max_level, prerequisites, costs, effects, category, enabled
                    FROM $tempTableName
                    """.trimIndent()
                )

                val duration = System.currentTimeMillis() - startTime
                Guozhan.instance.logger.info("🔧 已安全重建 gz_technologies 表，耗时 ${duration}ms")

            } catch (e: Exception) {
                Guozhan.instance.logger.severe("🔧 安全重建 gz_technologies 表失败: ${e.message}")
                e.printStackTrace()

                // 尝试恢复：如果临时表存在，尝试恢复数据
                try {
                    execute("DROP TABLE IF EXISTS gz_technologies")
                    execute("ALTER TABLE $tempTableName RENAME TO gz_technologies")
                    Guozhan.instance.logger.info("🔧 已从临时表恢复 gz_technologies 表")
                } catch (recoverException: Exception) {
                    Guozhan.instance.logger.severe("🔧 从临时表恢复失败: ${recoverException.message}")
                }
            } finally {
                // 7. 清理临时表并重新启用外键约束
                try {
                    execute("DROP TABLE IF EXISTS $tempTableName")
                } catch (e: Exception) {
                    Guozhan.instance.logger.warning("⚠️ 清理临时表失败: ${e.message}")
                }

                execute("PRAGMA foreign_keys=on")
            }
        }
    }

    /**
     * 🔧 v1.3.49: 序列化科技成本数据
     */
    private fun serializeCosts(costs: Map<Int, TechCost>): String {
        return costs.entries.joinToString("|") { (level, cost) ->
            "${level}:${cost.gold},${cost.diamond},${cost.territoryIncome}"
        }
    }

    /**
     * 🔧 v1.3.49: 序列化科技效果数据
     */
    private fun serializeEffects(effects: Map<Int, List<TechEffect>>): String {
        return effects.entries.joinToString("|") { (level, effectList) ->
            val effectsStr = effectList.joinToString(";") { effect ->
                "${effect.type.name}:${effect.value}:${effect.duration}:${effect.data.entries.joinToString(",") { "${it.key}=${it.value}" }}"
            }
            "${level}:${effectsStr}"
        }
    }

    /**
     * 获取所有可用科技
     */
    fun getAllTechnologies(): List<Technology> {
        return TechnologyConfig.getAllTechnologies().values.filter { it.enabled }
    }
    
    /**
     * 获取指定科技
     */
    fun getTechnology(id: String): Technology? {
        return TechnologyConfig.getTechnology(id)?.takeIf { it.enabled }
    }
    
    /**
     * 获取国家的科技等级
     * @param country 国家
     * @param technologyId 科技ID
     * @return 科技等级(0表示未研究)
     */
    fun getCountryTechLevel(country: Country, technologyId: String): Int {
        return countryTechnologies[country.id]?.get(technologyId)?.level ?: 0
    }

    /**
     * 获取国家的科技状态
     * @param country 国家
     * @param technologyId 科技ID
     * @return 科技状态，如果不存在返回null
     */
    fun getCountryTechnology(country: Country, technologyId: String): CountryTechnology? {
        return countryTechnologies[country.id]?.get(technologyId)
    }

    /**
     * 🔧 v1.3.48: 修复Critical问题4.3 - 计算科技研究时间
     * 优先使用TechnologyConfig配置，支持全局覆写
     * @param technology 科技
     * @param level 目标等级
     * @return 研究时间（毫秒）
     */
    private fun calculateResearchTime(technology: Technology, level: Int): Long {
        // 检查config.yml中的全局覆写设置
        val globalOverride = cn.lcofficial.guozhan.config.Config.Technology.researchDuration
        if (globalOverride > 0) {
            // 全局覆写：所有科技使用相同的研究时间
            val researchTime = globalOverride * 1000L
            cn.lcofficial.guozhan.Guozhan.instance.logger.info("🔧 [科技研究时间] 使用全局覆写时间: ${globalOverride}秒(${researchTime}ms)")
            return researchTime
        }

        // 使用TechnologyConfig中的per-tech配置
        val researchTime = cn.lcofficial.guozhan.config.TechnologyConfig.calculateResearchTime(technology, level)
        cn.lcofficial.guozhan.Guozhan.instance.logger.info("🔧 [科技研究时间] 科技 ${technology.name} 等级 $level 研究时间: ${researchTime}ms (${researchTime/1000}秒)")
        return researchTime
    }

    /**
     * 检查国家是否可以研究指定科技
     * @param country 国家
     * @param technologyId 科技ID
     * @return 是否可以研究
     */
    fun canResearchTechnology(country: Country, technologyId: String): Boolean {
        val technology = getTechnology(technologyId) ?: return false
        val currentLevel = getCountryTechLevel(country, technologyId)

        // 检查是否已达到最高等级
        if (currentLevel >= technology.maxLevel) return false

        // 检查是否正在研究
        if (isResearching(country, technologyId)) return false

        // 检查是否达到最大同时研究数量
        val currentResearching = researchingTechnologies[country.id]?.size ?: 0
        if (currentResearching >= TechnologyConfig.Settings.maxConcurrentResearch) return false

        // 检查前置科技
        if (!checkPrerequisites(country, technology)) return false

        // 检查资源
        val targetLevel = currentLevel + 1
        if (!checkResources(country, technology, targetLevel)) return false

        return true
    }

    /**
     * 检查前置科技条件
     */
    private fun checkPrerequisites(country: Country, technology: Technology): Boolean {
        return technology.prerequisites.all { prereqId ->
            val prereqLevel = getCountryTechLevel(country, prereqId)
            prereqLevel > 0 // 前置科技至少要有1级
        }
    }

    /**
     * 检查资源是否充足
     * 修复：合并资源检查逻辑，确保总成本检查的原子性，防止金币变为负数
     */
    private fun checkResources(country: Country, technology: Technology, level: Int): Boolean {
        val cost = technology.getCost(level) ?: return false

        // 检查钻石
        if (country.diamond < cost.diamond) return false

        // 计算总金币成本（基础成本 + 领土收入成本）
        var totalGoldCost = cost.gold
        if (cost.territoryIncome > 0) {
            val hourlyIncome = RegionalTaxSystem.calculateTotalGoldTaxPerHour(country)
            // 修复：使用向上取整确保即使小额收入也会被正确计算
            val additionalCost = ceil(cost.territoryIncome * hourlyIncome).toInt()
            totalGoldCost += additionalCost
        }

        // 一次性检查总金币成本，确保不会导致负数
        if (country.gold < totalGoldCost) return false

        return true
    }

    /**
     * 开始研究科技
     * 🔧 v1.3.47: 修复科技研究线程阻塞问题 - 完全异步化，避免使用CompletableFuture.get()阻塞线程
     * @param country 国家
     * @param technologyId 科技ID
     * @param callback 完成回调，传入是否成功开始研究
     */
    fun startResearch(country: Country, technologyId: String, callback: (Boolean) -> Unit = {}) {
        val technology = getTechnology(technologyId)
        if (technology == null) {
            callback(false)
            return
        }

        if (!canResearchTechnology(country, technologyId)) {
            callback(false)
            return
        }

        val currentLevel = getCountryTechLevel(country, technologyId)
        val targetLevel = currentLevel + 1
        val cost = technology.getCost(targetLevel)
        if (cost == null) {
            callback(false)
            return
        }

        // 计算总金币成本（基础成本 + 领土收入成本）
        var totalGoldCost = cost.gold
        if (cost.territoryIncome > 0) {
            val hourlyIncome = RegionalTaxSystem.calculateTotalGoldTaxPerHour(country)
            // 修复：使用向上取整确保即使小额收入也会被正确计算
            val additionalCost = ceil(cost.territoryIncome * hourlyIncome).toInt()
            totalGoldCost += additionalCost
        }

        // 检查资源是否充足
        if (country.gold < totalGoldCost || country.diamond < cost.diamond) {
            callback(false)
            return
        }

        // 🔧 v1.3.47: 完全异步化，在GlobalRegionScheduler中执行状态修改
        cn.lcofficial.guozhan.util.run {
            try {
                // 🔧 v1.3.51: 修复科技研发系统数据库错误 - 预检查数据库完整性
                if (!validateTechnologyDatabaseIntegrity(technologyId)) {
                    Guozhan.instance.logger.warning("科技研究开始前数据库完整性检查失败：科技 ${technologyId}")
                    callback(false)
                    return@run
                }

                // 🔧 v1.3.42: 修复科技研究资源扣费竞态 - 再次验证资源是否充足
                if (country.gold < totalGoldCost || country.diamond < cost.diamond) {
                    Guozhan.instance.logger.warning("科技研究开始时资源不足（竞态检查）：国家${country.name}，需要金币${totalGoldCost}，钻石${cost.diamond}，当前金币${country.gold}，钻石${country.diamond}")
                    callback(false)
                    return@run
                }

                // 再次检查是否已在研究
                if (isResearching(country, technologyId)) {
                    Guozhan.instance.logger.warning("科技研究开始时发现已在研究：国家${country.name}，科技 ${technologyId}")
                    callback(false)
                    return@run
                }

                // 🔧 v1.3.49: 修复科技研究失败时资源永久扣除 - 将所有状态修改包装在单个transaction
                val researchTime = calculateResearchTime(technology, targetLevel)
                val startTime = System.currentTimeMillis()
                val endTime = startTime + researchTime

                // 创建或更新科技状态
                val countryTech = CountryTechnology(
                    countryId = country.id,
                    technologyId = technologyId,
                    level = currentLevel,
                    researchStartTime = startTime,
                    researchEndTime = endTime,
                    isResearching = true
                )

                // 🔧 v1.3.51: 修复科技研发系统数据库错误 - 增强的事务处理和错误恢复
                transaction {
                    // 🔧 v1.3.50: 在事务内部验证科技记录是否存在，确保数据一致性
                    val techExists = Technologies.selectAll().where { Technologies.id eq technologyId }.count() > 0
                    if (!techExists) {
                        throw IllegalStateException("科技记录不存在: $technologyId，无法开始研究")
                    }

                    // 扣除资源
                    country.diamond -= cost.diamond
                    country.gold -= totalGoldCost

                    // 扣费后验证资源是否充足，如果不足则抛出异常回滚整个transaction
                    if (country.gold < 0 || country.diamond < 0) {
                        throw IllegalStateException("科技研究扣费后资源不足：国家 ${country.name}，金币${country.gold}，钻石${country.diamond}")
                    }

                    // 保存国家资源变更
                    country.save()

                    // 🔧 v1.3.51: 修复科技研发失败 - 使用安全的插入/更新方式替代replace操作
                    // 避免SQLite在外键约束检查时引用不存在的备份表

                    // 先删除现有记录（如果存在）
                    CountryTechnologies.deleteWhere {
                        (CountryTechnologies.countryId eq country.id.toString()) and
                        (CountryTechnologies.technologyId eq technologyId)
                    }

                    // 然后插入新记录
                    CountryTechnologies.insert {
                        it[CountryTechnologies.countryId] = country.id.toString()
                        it[CountryTechnologies.technologyId] = technologyId
                        it[CountryTechnologies.level] = currentLevel
                        it[CountryTechnologies.researchStartTime] = startTime
                        it[CountryTechnologies.researchEndTime] = endTime
                        it[CountryTechnologies.isResearching] = true
                    }
                }

                // 🔧 v1.3.50: 事务成功提交后再更新缓存，避免事务回滚时缓存不一致
                // 更新缓存（使用线程安全的操作）
                countryTechnologies.computeIfAbsent(country.id) { ConcurrentHashMap() }[technologyId] = countryTech
                researchingTechnologies.computeIfAbsent(country.id) { ConcurrentHashMap.newKeySet() }.add(technologyId)

                Guozhan.instance.logger.info("国家 ${country.name} 开始研究科技 ${technology.name} 等级 $targetLevel")

                // 通知国家成员研究开始
                notifyResearchStart(country, technology, targetLevel, researchTime)

                // 🔧 v1.3.41: 修复科技研究永久卡在"研究中"状态
                if (researchTime == 0L) {
                    // 瞬间完成：立即调用completeResearch()
                    Guozhan.instance.logger.info("科技研究瞬间完成：国家${country.name} 的科技 ${technology.name} 等级 $targetLevel")
                    completeResearch(country, technologyId)
                } else {
                    // 需要时间：设置延迟任务自动完成研究
                    val delayTicks = (researchTime / 50L).coerceAtLeast(1L) // 转换为ticks，最少1tick
                    cn.lcofficial.guozhan.util.runLater(delayTicks) { _ ->
                        try {
                            // 检查研究状态是否仍然有效（防止中途取消）
                            if (isResearching(country, technologyId)) {
                                Guozhan.instance.logger.info("科技研究时间到达：自动完成国家${country.name} 的科技 ${technology.name} 等级 $targetLevel")
                                completeResearch(country, technologyId)
                            }
                        } catch (e: Exception) {
                            Guozhan.instance.logger.severe("自动完成科技研究时出错：国家 ${country.name}，科技 ${technologyId} - ${e.message}")
                            e.printStackTrace()
                        }
                    }

                    // 如果启用进度通知，启动进度通知
                    if (cn.lcofficial.guozhan.config.Config.Technology.enableProgressNotifications) {
                        startProgressNotifications(country, technology, targetLevel, startTime, endTime)
                    }
                }

                callback(true)
            } catch (e: Exception) {
                // 🔧 v1.3.51: 修复科技研发系统数据库错误 - 增强的错误处理和自动恢复
                when {
                    e.message?.contains("gz_technologies_backup") == true -> {
                        Guozhan.instance.logger.severe("🔧 [科技研究错误] 检测到备份表问题，执行紧急修复: ${e.message}")
                        handleBackupTableError(country, technologyId, callback)
                        return@run
                    }
                    e.message?.contains("foreign key") == true -> {
                        Guozhan.instance.logger.severe("🔧 [科技研究错误] 检测到外键约束问题，执行数据库修复: ${e.message}")
                        handleForeignKeyError(country, technologyId, callback)
                        return@run
                    }
                    e.message?.contains("no such table") == true -> {
                        Guozhan.instance.logger.severe("🔧 [科技研究错误] 检测到表缺失问题，执行表重建: ${e.message}")
                        handleMissingTableError(country, technologyId, callback)
                        return@run
                    }
                    else -> {
                        Guozhan.instance.logger.severe("科技研究过程中出错: ${e.message}")
                        e.printStackTrace()
                    }
                }

                // 清理可能的缓存状态（transaction已自动回滚数据库和资源）
                try {
                    countryTechnologies[country.id]?.remove(technologyId)
                    researchingTechnologies[country.id]?.remove(technologyId)
                    Guozhan.instance.logger.info("🔧 [科技研究] 已清理国家${country.name} 科技 ${technologyId} 的缓存状态")
                } catch (cleanupException: Exception) {
                    Guozhan.instance.logger.warning("清理科技研究缓存状态时出错: ${cleanupException.message}")
                }

                callback(false)
            }
        }
    }

    /**
     * 🔧 v1.3.51: 验证科技数据库完整性
     */
    private fun validateTechnologyDatabaseIntegrity(technologyId: String): Boolean {
        return try {
            transaction {
                // 检查科技表是否存在
                val techTableExists = exec("SELECT name FROM sqlite_master WHERE type='table' AND name='gz_technologies'") { rs ->
                    rs.next()
                } ?: false

                if (!techTableExists) {
                    Guozhan.instance.logger.warning("⚠️ [数据库完整性] gz_technologies 表不存在")
                    return@transaction false
                }

                // 检查科技记录是否存在
                val techExists = Technologies.selectAll().where { Technologies.id eq technologyId }.count() > 0
                if (!techExists) {
                    Guozhan.instance.logger.warning("⚠️ [数据库完整性] 科技记录不存在: $technologyId")
                    return@transaction false
                }

                // 检查外键约束状态
                val foreignKeysEnabled = exec("PRAGMA foreign_keys") { rs ->
                    if (rs.next()) rs.getInt(1) == 1 else false
                } ?: false

                if (!foreignKeysEnabled) {
                    Guozhan.instance.logger.warning("⚠️ [数据库完整性] 外键约束未启用")
                    exec("PRAGMA foreign_keys=on")
                }

                true
            }
        } catch (e: Exception) {
            Guozhan.instance.logger.severe("🔧 [数据库完整性] 验证失败: ${e.message}")
            false
        }
    }

    /**
     * 🔧 v1.3.51: 处理备份表错误
     */
    private fun handleBackupTableError(country: Country, technologyId: String, callback: (Boolean) -> Unit) {
        try {
            Guozhan.instance.logger.info("🔧 [错误恢复] 开始处理备份表错误...")

            // 清理备份表并重新初始化
            cleanupOrphanedBackupTables()
            validateTableStructure()
            insertTechnologiesToDatabase()

            // 重试科技研究
            Guozhan.instance.logger.info("🔧 [错误恢复] 备份表问题已修复，重试科技研究...")
            startResearchRetry(country, technologyId, callback)

        } catch (e: Exception) {
            Guozhan.instance.logger.severe("🔧 [错误恢复] 处理备份表错误失败: ${e.message}")
            callback(false)
        }
    }

    /**
     * 🔧 v1.3.51: 处理外键约束错误
     */
    private fun handleForeignKeyError(country: Country, technologyId: String, callback: (Boolean) -> Unit) {
        try {
            Guozhan.instance.logger.info("🔧 [错误恢复] 开始处理外键约束错误...")

            // 验证并修复外键约束
            if (!validateForeignKeyConstraints()) {
                attemptAutoRepair()
            }

            // 确保科技数据存在
            insertTechnologiesToDatabase()

            // 重试科技研究
            Guozhan.instance.logger.info("🔧 [错误恢复] 外键约束问题已修复，重试科技研究...")
            startResearchRetry(country, technologyId, callback)

        } catch (e: Exception) {
            Guozhan.instance.logger.severe("🔧 [错误恢复] 处理外键约束错误失败: ${e.message}")
            callback(false)
        }
    }

    /**
     * 🔧 v1.3.51: 处理表缺失错误
     */
    private fun handleMissingTableError(country: Country, technologyId: String, callback: (Boolean) -> Unit) {
        try {
            Guozhan.instance.logger.info("🔧 [错误恢复] 开始处理表缺失错误...")

            // 重新创建表
            transaction {
                SchemaUtils.createMissingTablesAndColumns(Technologies, CountryTechnologies)
            }

            // 插入科技数据
            insertTechnologiesToDatabase()

            // 重试科技研究
            Guozhan.instance.logger.info("🔧 [错误恢复] 表缺失问题已修复，重试科技研究...")
            startResearchRetry(country, technologyId, callback)

        } catch (e: Exception) {
            Guozhan.instance.logger.severe("🔧 [错误恢复] 处理表缺失错误失败: ${e.message}")
            callback(false)
        }
    }

    /**
     * 🔧 v1.3.51: 重试科技研究（简化版本，避免无限递归）
     */
    private fun startResearchRetry(country: Country, technologyId: String, callback: (Boolean) -> Unit) {
        try {
            val technology = getTechnology(technologyId)
            if (technology == null) {
                callback(false)
                return
            }

            val currentLevel = getCountryTechLevel(country, technologyId)
            val targetLevel = currentLevel + 1
            val cost = technology.getCost(targetLevel)
            if (cost == null) {
                callback(false)
                return
            }

            // 计算总金币成本
            var totalGoldCost = cost.gold
            if (cost.territoryIncome > 0) {
                val hourlyIncome = RegionalTaxSystem.calculateTotalGoldTaxPerHour(country)
                val additionalCost = ceil(cost.territoryIncome * hourlyIncome).toInt()
                totalGoldCost += additionalCost
            }

            // 检查资源是否充足
            if (country.gold < totalGoldCost || country.diamond < cost.diamond) {
                cancelProgressNotifications(country.id, technologyId)
                callback(false)
                return
            }

            // 简化的研究逻辑（不再递归调用startResearch）
            val researchTime = calculateResearchTime(technology, targetLevel)
            val startTime = System.currentTimeMillis()
            val endTime = startTime + researchTime

            val countryTech = CountryTechnology(
                countryId = country.id,
                technologyId = technologyId,
                level = currentLevel,
                researchStartTime = startTime,
                researchEndTime = endTime,
                isResearching = true
            )

            transaction {
                // 验证科技存在
                val techExists = Technologies.selectAll().where { Technologies.id eq technologyId }.count() > 0
                if (!techExists) {
                    throw IllegalStateException("重试时科技记录仍不存在: $technologyId")
                }

                // 扣除资源
                country.diamond -= cost.diamond
                country.gold -= totalGoldCost
                country.save()

                // 🔧 v1.3.51: 修复科技研发失败 - 使用安全的插入/更新方式替代replace操作
                // 避免SQLite在外键约束检查时引用不存在的备份表

                // 先删除现有记录（如果存在）
                CountryTechnologies.deleteWhere {
                    (CountryTechnologies.countryId eq country.id.toString()) and
                    (CountryTechnologies.technologyId eq technologyId)
                }

                // 然后插入新记录
                CountryTechnologies.insert {
                    it[CountryTechnologies.countryId] = country.id.toString()
                    it[CountryTechnologies.technologyId] = technologyId
                    it[CountryTechnologies.level] = currentLevel
                    it[CountryTechnologies.researchStartTime] = startTime
                    it[CountryTechnologies.researchEndTime] = endTime
                    it[CountryTechnologies.isResearching] = true
                }
            }

            // 更新缓存
            countryTechnologies.computeIfAbsent(country.id) { ConcurrentHashMap() }[technologyId] = countryTech
            researchingTechnologies.computeIfAbsent(country.id) { ConcurrentHashMap.newKeySet() }.add(technologyId)

            Guozhan.instance.logger.info("🔧 [错误恢复] 国家 ${country.name} 成功重试研究科技 ${technology.name} 等级 $targetLevel")

            // 设置研究完成任务
            if (researchTime == 0L) {
                completeResearch(country, technologyId)
            } else {
                val delayTicks = (researchTime / 50L).coerceAtLeast(1L)
                cn.lcofficial.guozhan.util.runLater(delayTicks) { _ ->
                    if (isResearching(country, technologyId)) {
                        completeResearch(country, technologyId)
                    }
                }
            }

            callback(true)

        } catch (e: Exception) {
            Guozhan.instance.logger.severe("🔧 [错误恢复] 重试科技研究失败: ${e.message}")
            callback(false)
        }
    }

    /**
     * 完成科技研究
     * @param country 国家
     * @param technologyId 科技ID
     * @return 是否成功完成研究
     */
    fun completeResearch(country: Country, technologyId: String): Boolean {
        val countryTech = getCountryTechnology(country, technologyId) ?: return false

        if (!countryTech.isResearching) return false

        val newLevel = countryTech.level + 1

        // 更新科技状态
        val updatedTech = countryTech.copy(
            level = newLevel,
            researchStartTime = null,
            researchEndTime = null,
            isResearching = false
        )

        // 更新缓存
        countryTechnologies[country.id]?.set(technologyId, updatedTech)
        researchingTechnologies[country.id]?.remove(technologyId)

        // 更新数据库
        transaction {
            CountryTechnologies.update({
                (CountryTechnologies.countryId eq country.id.toString()) and
                (CountryTechnologies.technologyId eq technologyId)
            }) {
                it[level] = newLevel
                it[researchStartTime] = null
                it[researchEndTime] = null
                it[isResearching] = false
            }
        }

        val technology = getTechnology(technologyId)
        Guozhan.instance.logger.info("国家 ${country.name} 完成了科技 ${technology?.name ?: technologyId} 等级 $newLevel 的研究")

        // 通知所有在线的国家成员
        notifyResearchCompletion(country, technology, newLevel)

        // 应用科技效果
        applyTechnologyEffects(country)
        cancelProgressNotifications(country.id, technologyId)

        return true
    }
    
    /**
     * 检查科技是否正在研究
     */
    fun isResearching(country: Country, technologyId: String): Boolean {
        return researchingTechnologies[country.id]?.contains(technologyId) == true
    }

    /**
     * 获取国家正在研究的科技列表
     */
    fun getResearchingTechnologies(country: Country): List<String> {
        return researchingTechnologies[country.id]?.toList() ?: emptyList()
    }

    /**
     * 通知科技研究开始
     * @param country 国家
     * @param technology 开始研究的科技
     * @param targetLevel 目标等级
     * @param researchTime 研究时间（毫秒）
     */
    private fun notifyResearchStart(country: Country, technology: Technology, targetLevel: Int, researchTime: Long) {
        // 🔧 v1.3.40: 修复科技通知Folia线程违规 - 使用EntityScheduler发送玩家消息
        country.members.forEach { member ->
            val player = org.bukkit.Bukkit.getPlayer(member.uniqueId)
            if (player != null && player.isOnline) {
                // 使用EntityScheduler确保在正确的线程中发送消息
                player.scheduler.run(Guozhan.instance, { _ ->
                    with(cn.lcofficial.guozhan.config.Message) {
                        if (researchTime == 0L) {
                            // 瞬间完成
                            player.sendSuccess("科技研究已开始：${technology.name} 等级 $targetLevel")
                            player.sendInfo("研究将瞬间完成")
                        } else {
                            // 需要时间
                            val hours = researchTime / (1000 * 60 * 60)
                            val minutes = (researchTime % (1000 * 60 * 60)) / (1000 * 60)
                            val timeString = when {
                                hours > 0 && minutes > 0 -> "${hours}小时${minutes}分钟"
                                hours > 0 -> "${hours}小时"
                                minutes > 0 -> "${minutes}分钟"
                                else -> "不到1分钟"
                            }
                            player.sendSuccess("科技研究已开始：${technology.name} 等级 $targetLevel")
                            player.sendInfo("预计完成时间: $timeString")
                            if (cn.lcofficial.guozhan.config.Config.Technology.enableProgressNotifications) {
                                val interval = cn.lcofficial.guozhan.config.Config.Technology.progressNotificationInterval
                                val intervalHours = interval / 3600
                                player.sendInfo("将每${intervalHours}小时发送进度通知")
                            }
                        }
                    }
                }, null)
            }
        }
    }

    /**
     * 启动进度通知
     * @param country 国家
     * @param technology 研究的科技
     * @param targetLevel 目标等级
     * @param startTime 开始时间
     * @param endTime 结束时间
     */
    private fun startProgressNotifications(country: Country, technology: Technology, targetLevel: Int, startTime: Long, endTime: Long) {
        val interval = cn.lcofficial.guozhan.config.Config.Technology.progressNotificationInterval * 1000L // 转换为毫秒
        val totalDuration = endTime - startTime

        // 🔧 v1.3.42: 修复科技进度通知任务数量过多 - 限制最小通知间隔
        // 限制最小通知间隔(15分钟=900000毫秒），避免创建过多任务
        val minNotificationInterval = 15 * 60 * 1000L // 15分钟
        val actualInterval = maxOf(interval, minNotificationInterval)

        // 计算需要发送多少次通知（使用实际间隔）
        val notificationCount = (totalDuration / actualInterval).toInt()
        if (notificationCount <= 0) return

        // 记录通知任务创建情况
        Guozhan.instance.logger.info("科技进度通知：将创建 $notificationCount 个通知任务，间隔${actualInterval / 1000} 秒")

        // 使用Folia调度器定期发送进度通知
        for (i in 1..notificationCount) {
            val delay = (actualInterval * i) / 50L // 转换为tick (1秒= 20tick)，使用实际间隔
            org.bukkit.Bukkit.getGlobalRegionScheduler().runDelayed(Guozhan.instance, { _ ->
                val currentTime = System.currentTimeMillis()
                // 🔧 v1.3.45: 修复科技进度通知未检查研究状态 - 检查科技是否仍在研究
                if (currentTime < endTime && isResearching(country, technology.id)) {
                    val elapsed = currentTime - startTime
                    val remaining = endTime - currentTime
                    val progress = (elapsed.toDouble() / totalDuration * 100).toInt()

                    // 🔧 v1.3.41: 修复科技进度通知违反Folia线程规则 - 使用EntityScheduler发送玩家消息
                    // 通知国家君主或所有在线成员
                    country.members.forEach { member ->
                        val player = org.bukkit.Bukkit.getPlayer(member.uniqueId)
                        if (player != null && player.isOnline) {
                            // 使用EntityScheduler确保在正确的线程中发送消息
                            player.scheduler.run(Guozhan.instance, { _ ->
                                with(cn.lcofficial.guozhan.config.Message) {
                                    player.sendInfo("§6=== 科技研究进度 ===")
                                    player.sendInfo("§f科技: ${technology.name} 等级 $targetLevel")
                                    player.sendInfo("§f进度: ${progress}% 完成")

                                    val remainingHours = remaining / (1000 * 60 * 60)
                                    val remainingMinutes = (remaining % (1000 * 60 * 60)) / (1000 * 60)
                                    val remainingTimeString = when {
                                        remainingHours > 0 && remainingMinutes > 0 -> "${remainingHours}小时${remainingMinutes}分钟"
                                        remainingHours > 0 -> "${remainingHours}小时"
                                        remainingMinutes > 0 -> "${remainingMinutes}分钟"
                                        else -> "不到1分钟"
                                    }
                                    player.sendInfo("§f剩余时间: $remainingTimeString")
                                }
                            }, null)
                        }
                    }
                } else {
                    // 🔧 v1.3.45: 科技已完成或被取消，停止进度通知
                    if (!isResearching(country, technology.id)) {
                        Guozhan.instance.logger.fine("🔧 [科技进度] 科技 ${technology.name} 已完成或被取消，停止进度通知")
                    }
                }
            }, delay)
        }
    }

    /**
     * 通知科技研究完成
     * @param country 国家
     * @param technology 完成的科技
     * @param newLevel 新等级
     */
    private fun notifyResearchCompletion(country: Country, technology: Technology?, newLevel: Int) {
        val techName = technology?.name ?: "未知科技"

        // 🔧 v1.3.40: 修复科技通知Folia线程违规 - 使用EntityScheduler发送玩家消息
        // 通知所有在线的国家成员
        country.members.forEach { member ->
            val player = org.bukkit.Bukkit.getPlayer(member.uniqueId)
            if (player != null && player.isOnline) {
                // 使用EntityScheduler确保在正确的线程中发送消息
                player.scheduler.run(Guozhan.instance, { _ ->
                    // 使用Message.kt扩展函数发送消息
                    with(cn.lcofficial.guozhan.config.Message) {
                        player.sendSuccess("科技研究完成: $techName 等级 $newLevel")

                        // 显示新获得的效果
                        val effects = technology?.getEffects(newLevel) ?: emptyList()
                        if (effects.isNotEmpty()) {
                            player.sendInfo("新获得的科技效果:")
                            effects.forEach { effect ->
                                player.sendMessage("§a  + ${effect.getDescription()}")
                            }
                        }

                        player.sendInfo("科技效果已自动应用到所有国家成员")
                    }
                }, null)
            }
        }

        // 如果有离线成员，记录到日志供后续处理
        val offlineMembers = country.members.filter { member ->
            val player = org.bukkit.Bukkit.getPlayer(member.uniqueId)
            player == null || !player.isOnline
        }

        if (offlineMembers.isNotEmpty()) {
            Guozhan.instance.logger.info("科技完成通知: ${offlineMembers.size}名离线成员将在下次登录时收到通知")
        }
    }

    /**
     * 应用科技效果（由TechEffectManager处理）
     * 🔧 v1.3.37: 修复Folia线程安全 - updatePlayerEffects已改为自动调度到EntityScheduler
     */
    fun applyTechnologyEffects(country: Country) {
        // 刷新国家的科技效果缓存
        TechEffectManager.refreshCountryEffectsCache(country)

        // 🔧 v1.3.37: 为该国家的所有在线成员更新效果
        // updatePlayerEffects方法已修改为自动调度到EntityScheduler，确保线程安全
        org.bukkit.Bukkit.getOnlinePlayers().forEach { player ->
            val user = UserManager.getUser(player.uniqueId)
            if (user?.country?.id == country.id) {
                TechEffectManager.updatePlayerEffects(player) // 此方法现在是线程安全的
            }
        }

        Guozhan.instance.logger.info("已为国家 ${country.name} 的在线成员应用科技效果")
    }

    /**
     * 启动研究完成检查任务
     * 🔧 v1.3.41: 修复科技研究永久卡住 - 实现真正的研究完成检查逻辑
     */
    private fun startResearchCompletionTask() {
        if (!TechnologyConfig.Settings.autoCompleteResearch) return

        // 使用Folia调度器每分钟检查一次研究完成情况
        runRepeat(20L * 60L, 20L * 60L) { task ->
            try {
                val currentTime = System.currentTimeMillis()
                var completedCount = 0

                // 检查所有正在研究的科技
                for ((countryId, techIds) in researchingTechnologies.toMap()) {
                    val country = CountryManager.getCountry(countryId) ?: continue

                    // 创建副本避免并发修改
                    val techIdsToCheck = techIds.toList()

                    for (technologyId in techIdsToCheck) {
                        val countryTech = getCountryTechnology(country, technologyId)
                        if (countryTech != null && countryTech.isResearching) {
                            val endTime = countryTech.researchEndTime
                            if (endTime != null && currentTime >= endTime) {
                                // 研究时间已到，自动完成
                                val technology = getTechnology(technologyId)
                                Guozhan.instance.logger.info("定期检查发现研究完成：国家 ${country.name} 的科技 ${technology?.name ?: technologyId}")
                                completeResearch(country, technologyId)
                                completedCount++
                            }
                        }
                    }
                }

                if (completedCount > 0) {
                    Guozhan.instance.logger.info("科技研究完成检查：自动完成了 $completedCount 个科技研究")
                } else {
                    Guozhan.instance.logger.fine("科技研究完成检查：没有需要完成的研究")
                }
            } catch (e: Exception) {
                Guozhan.instance.logger.severe("检查科技研究完成状态时出错: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun shutdown() {
        cancelResearchSchedulers()
        resetCaches()
    }

    fun resetCaches() {
        countryTechnologies.clear()
        researchingTechnologies.clear()
    }

    /**
     * 清空所有缓存
     * 🔧 v1.3.51: 修复热重载问题 - 添加缓存清理方法
     */
    fun clearCache() {
        countryTechnologies.clear()
        researchingTechnologies.clear()
        progressNotificationTasks.values.forEach { tasks ->
            tasks.forEach { task ->
                if (!task.isCancelled) {
                    task.cancel()
                }
            }
        }
        progressNotificationTasks.clear()
        Guozhan.instance.logger.info("科技管理器缓存已清空")
    }

    private fun cancelProgressNotifications(countryId: UUID, technologyId: String) {
        progressNotificationTasks.remove(countryId to technologyId)?.forEach { task ->
            try {
                task.cancel()
            } catch (_: Exception) {
            }
        }
    }

    private fun cancelResearchSchedulers() {
        researchCompletionTask?.cancel()
        researchCompletionTask = null
        progressNotificationTasks.forEach { (_, tasks) ->
            tasks.forEach { task ->
                try {
                    task.cancel()
                } catch (_: Exception) {
                }
            }
        }
        progressNotificationTasks.clear()
    }

    /**
     * 获取正在进行的科技研究数量
     * 🔧 v1.3.52: 用于热重载前检查是否有活跃的科技研究
     */
    fun getResearchingCount(): Int {
        return researchingTechnologies.values.sumOf { it.size }
    }

}

