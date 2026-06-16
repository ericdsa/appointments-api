package com.example.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

fun Application.connectDatabase(): Database {
    val url = environment.config.property("database.url").getString()
    val user = environment.config.property("database.user").getString()
    val password = environment.config.property("database.password").getString()

    val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = url
        username = user
        this.password = password
        // pool sizing: keep a small idle floor, cap at 10 for a single-instance API
        maximumPoolSize = 10
        minimumIdle = 2
        // how long a connection can sit idle before being retired
        idleTimeout = 600_000
        // how long a caller waits for a connection before an exception is thrown
        connectionTimeout = 30_000
        // max lifetime of a connection regardless of activity (keeps the pool fresh)
        maxLifetime = 1_800_000
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
