package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.util.MigrationUtils
import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.config.Config
import cn.lcofficial.guozhan.data.Cities
import cn.lcofficial.guozhan.data.Countries
import cn.lcofficial.guozhan.data.CountryTechnologies
import cn.lcofficial.guozhan.data.DiplomaticRelations
import cn.lcofficial.guozhan.data.Technologies
import cn.lcofficial.guozhan.data.Territories
import cn.lcofficial.guozhan.data.TerritoryBlocks
import cn.lcofficial.guozhan.data.Users
import cn.lcofficial.guozhan.pluginLogger
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.*
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
                    Users, Countries, Cities, TerritoryBlocks, Territories,
                    DiplomaticRelations, Technologies, CountryTechnologies
                ).forEach(::exec)
            }
            pluginLogger.info("连接成功")

            // 执行数据完整性检查和修复
            performDataIntegrityCheck()
        } catch (e: Exception) {
            pluginLogger.log(Level.SEVERE, "数据库连接失败", e)
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

        // 清空内存缓存
        UserManager.users.clear()
        CountryManager.countries.clear()
        CityManager.cities.clear()
        TerritoryManager.territories.clear()

        pluginLogger.warning("数据库已清空！所有数据已删除")
    }
}