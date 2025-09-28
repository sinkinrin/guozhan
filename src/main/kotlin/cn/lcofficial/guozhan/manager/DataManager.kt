package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.util.MigrationUtils
import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.config.Config
import cn.lcofficial.guozhan.data.Cities
import cn.lcofficial.guozhan.data.Countries
import cn.lcofficial.guozhan.data.DiplomaticRelations
import cn.lcofficial.guozhan.data.TerritoryBlocks
import cn.lcofficial.guozhan.data.Users
import cn.lcofficial.guozhan.pluginLogger
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.util.logging.Level

object DataManager {
    lateinit var dataSource: HikariDataSource

    fun init(plugin: Guozhan) {
        if (::dataSource.isInitialized) dataSource.close()
        dataSource = HikariDataSource()
        when (Config.Database.type) {
            Config.Database.Type.MYSQL -> {
                dataSource.jdbcUrl =
                    "jdbc:mysql://${Config.Database.host}:${Config.Database.port}/${Config.Database.database}?useSSL=false&serverTimezone=UTC"
                dataSource.username = Config.Database.username
                dataSource.password = Config.Database.password
                dataSource.driverClassName = "com.mysql.cj.jdbc.Driver"
                pluginLogger.info("正在连接数据库MySQL: ${Config.Database.host}:${Config.Database.port}/${Config.Database.database}")
            }

            else -> {
                dataSource.jdbcUrl =
                    "jdbc:sqlite:${plugin.dataFolder.path}${File.separator}guozhan.db"
                dataSource.driverClassName = "org.sqlite.JDBC"
                pluginLogger.info("正在连接数据库SQLite: ${plugin.dataFolder.path}${File.separator}guozhan.db")
            }
        }
        try {
            Database.connect(dataSource)
            transaction {
                MigrationUtils.statementsRequiredForDatabaseMigration(
                    Users, Countries, Cities, TerritoryBlocks
                ).forEach(::exec)
            }
            pluginLogger.info("连接成功")
        } catch (e: Exception) {
            pluginLogger.log(Level.SEVERE, "数据库连接失败", e)
        }
    }
}