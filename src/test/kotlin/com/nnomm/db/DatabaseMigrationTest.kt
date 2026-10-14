package com.nnomm.db

import com.nnomm.config.DatabaseFactory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@Testcontainers
class DatabaseMigrationTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:15-alpine").apply {
            withDatabaseName("nnomm_deals_test")
            withUsername("test_user")
            withPassword("test_pass")
        }

        @BeforeAll
        @JvmStatic
        fun setup() {
            postgres.start()
            DatabaseFactory.init(
                jdbcUrl = postgres.jdbcUrl,
                username = postgres.username,
                password = postgres.password
            )
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            DatabaseFactory.close()
            postgres.stop()
        }
    }

    @Test
    fun `flyway applies all migrations and creates required tables`() = runBlocking {
        val tables = DatabaseFactory.query { conn ->
            val sql = """
                SELECT table_name 
                FROM information_schema.tables 
                WHERE table_schema = 'public';
            """.trimIndent()

            val tableList = mutableListOf<String>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(sql)
                while (rs.next()) {
                    tableList.add(rs.getString("table_name").lowercase())
                }
            }
            tableList
        }

        assertTrue(tables.contains("restaurants"), "Table 'restaurants' should exist")
        assertTrue(tables.contains("deals"), "Table 'deals' should exist")
        assertTrue(tables.contains("claims"), "Table 'claims' should exist")
        assertTrue(tables.contains("idempotency_records"), "Table 'idempotency_records' should exist")
        assertTrue(tables.contains("processed_webhooks"), "Table 'processed_webhooks' should exist")
    }

    @Test
    fun `can insert and query a restaurant successfully`() = runBlocking {
        val restId = UUID.randomUUID()
        val restName = "Paradise Biryani"

        val insertedName = DatabaseFactory.query { conn ->
            conn.prepareStatement("INSERT INTO restaurants (id, name) VALUES (?, ?) RETURNING name").use { stmt ->
                stmt.setObject(1, restId)
                stmt.setString(2, restName)
                val rs = stmt.executeQuery()
                rs.next()
                rs.getString("name")
            }
        }

        assertTrue(insertedName == restName)
    }
}
