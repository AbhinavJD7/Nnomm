package com.nnomm.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.sql.Connection
import javax.sql.DataSource

object DatabaseFactory {
    private var dataSource: DataSource? = null

    fun init(
        jdbcUrl: String = AppConfig.databaseUrl,
        username: String = AppConfig.databaseUser,
        password: String = AppConfig.databasePassword,
        customDataSource: DataSource? = null
    ) {
        val ds = customDataSource ?: createHikariDataSource(jdbcUrl, username, password)
        dataSource = ds
        runFlyway(ds)
    }

    fun init(config: AppConfig) {
        init(config.databaseUrl, config.databaseUser, config.databasePassword)
    }

    private fun createHikariDataSource(jdbcUrl: String, username: String, password: String): HikariDataSource {
        val hikariConfig = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 25
            minimumIdle = 5
            idleTimeout = 30000
            connectionTimeout = 10000
            maxLifetime = 600000
            isAutoCommit = false // Manual transaction control for atomic row-level locks
        }
        return HikariDataSource(hikariConfig)
    }

    private fun runFlyway(dataSource: DataSource) {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
        flyway.migrate()
    }

    suspend fun <T> query(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        val ds = checkNotNull(dataSource) { "DatabaseFactory has not been initialized." }
        ds.connection.use { conn ->
            try {
                val result = block(conn)
                conn.commit()
                result
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    fun close() {
        (dataSource as? HikariDataSource)?.close()
        dataSource = null
    }
}
