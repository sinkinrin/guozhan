package cn.lcofficial.guozhan.util

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 数据库迁移工具
 */
object MigrationUtils {
    
    /**
     * 获取创建表所需的SQL语句
     * @param tables 需要创建的表
     * @return SQL语句列表
     */
    fun statementsRequiredForDatabaseMigration(vararg tables: IdTable<*>): List<String> = transaction {
        SchemaUtils.createStatements(*tables)
    }
}