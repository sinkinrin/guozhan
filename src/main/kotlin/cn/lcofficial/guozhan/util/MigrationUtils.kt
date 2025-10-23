package cn.lcofficial.guozhan.util

import cn.lcofficial.guozhan.pluginLogger
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 数据库迁移工具
 * 🔧 修复问题3：使用 createMissingTablesAndColumns 自动处理 schema 变更
 */
object MigrationUtils {

    /**
     * 获取创建表和缺失列所需的SQL语句
     * 🔧 修复：从 createStatements 改为 statementsRequiredToActualizeScheme
     *
     * 原问题：createStatements 只会生成 CREATE TABLE 语句，不会生成 ALTER TABLE 语句
     * 修复后：statementsRequiredToActualizeScheme 会自动检测并生成缺失的列的 ALTER TABLE 语句
     *
     * @param tables 需要创建/更新的表
     * @return SQL语句列表
     */
    fun statementsRequiredForDatabaseMigration(vararg tables: Table): List<String> = transaction {
        try {
            // 使用 statementsRequiredToActualizeScheme 自动生成表和列的创建/修改语句
            val statements = SchemaUtils.statementsRequiredToActualizeScheme(*tables)

            if (statements.isNotEmpty()) {
                pluginLogger.info("[数据库迁移] 检测到 ${statements.size} 个数据库结构变更")
                statements.forEachIndexed { index, sql ->
                    pluginLogger.info("[数据库迁移] 变更 ${index + 1}: ${sql.take(100)}${if (sql.length > 100) "..." else ""}")
                }
            } else {
                pluginLogger.info("[数据库迁移] 数据库结构已是最新，无需变更")
            }

            statements
        } catch (e: Exception) {
            pluginLogger.severe("[数据库迁移] 生成迁移语句时发生错误: ${e.message}")
            e.printStackTrace()
            emptyList<String>()
        }
    }
}