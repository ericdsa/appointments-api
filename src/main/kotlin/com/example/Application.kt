package com.example

import com.example.db.connectDatabase
import com.example.di.appointmentModule
import com.example.plugins.ApiKeyAuth
import com.example.plugins.configureRouting
import com.example.plugins.configureSerialization
import com.example.plugins.configureStatusPages
import com.example.worker.ReminderWorker
import io.ktor.server.application.*
import io.ktor.server.netty.*
import kotlinx.coroutines.runBlocking
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module() {
    val database = connectDatabase()
    val apiKey = environment.config.property("auth.apiKey").getString()
    val notificationBaseUrl = environment.config.propertyOrNull("notification.baseUrl")?.getString()
        ?: "http://localhost:9000"

    install(Koin) {
        slf4jLogger()
        modules(appointmentModule(database, notificationBaseUrl))
    }

    install(ApiKeyAuth) {
        this.apiKey = apiKey
    }

    configureSerialization()
    configureStatusPages()
    configureRouting()

    // Drain the reminder queue for the lifetime of the application.
    val reminderWorker by inject<ReminderWorker>()
    monitor.subscribe(ApplicationStarted) { reminderWorker.start() }
    monitor.subscribe(ApplicationStopping) { runBlocking { reminderWorker.stop() } }
}
