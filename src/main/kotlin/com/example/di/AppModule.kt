package com.example.di

import com.example.external.NotificationClient
import com.example.repository.AppointmentRepository
import com.example.repository.ExposedAppointmentRepository
import com.example.repository.ExposedReminderJobRepository
import com.example.repository.ReminderJobRepository
import com.example.service.AppointmentService
import com.example.service.AppointmentServiceImpl
import com.example.service.TransactionRunner
import com.example.worker.ReminderWorker
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.koin.dsl.module

fun appointmentModule(database: Database, notificationBaseUrl: String) = module {
    single<AppointmentRepository> { ExposedAppointmentRepository(database) }
    single<ReminderJobRepository> { ExposedReminderJobRepository(database) }

    single {
        HttpClient(CIO) {
            install(HttpTimeout) { requestTimeoutMillis = 3_000 }
            install(ContentNegotiation) { json() }
        }
    }

    single { NotificationClient(get(), notificationBaseUrl) }

    single<TransactionRunner> {
        object : TransactionRunner {
            override suspend fun <T> execute(block: suspend () -> T): T =
                newSuspendedTransaction(Dispatchers.IO, database) { block() }
        }
    }

    single<AppointmentService> { AppointmentServiceImpl(get(), get(), get(), get()) }

    // Dedicated scope so the worker's lifetime is independent of any single request.
    single { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    single { ReminderWorker(get(), get(), get(), get()) }
}
