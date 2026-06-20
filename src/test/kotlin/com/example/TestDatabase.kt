package com.example

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.testcontainers.containers.PostgreSQLContainer

// Standalone DB bootstrap for integration tests. Mirrors db/DatabaseFactory.kt but
// takes a Testcontainers container instead of Ktor's Application config (which
// connectDatabase depends on), so the same Flyway migrations run against real
// Postgres before Exposed connects.
object TestDatabase {
    fun connect(container: PostgreSQLContainer<*>): Database {
        val dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = container.jdbcUrl
            username = container.username
            password = container.password
            maximumPoolSize = 5
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        })

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        return Database.connect(dataSource)
    }
}
