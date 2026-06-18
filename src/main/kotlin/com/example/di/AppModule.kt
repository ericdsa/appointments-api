package com.example.di

import com.example.external.NotificationClient
import com.example.repository.AppointmentRepository
import com.example.repository.ExposedAppointmentRepository
import com.example.service.AppointmentService
import com.example.service.AppointmentServiceImpl
import com.example.service.TransactionRunner
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.koin.dsl.module

fun appointmentModule(database: Database, notificationBaseUrl: String) = module {
    single<AppointmentRepository> { ExposedAppointmentRepository(database) }

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

    single<AppointmentService> { AppointmentServiceImpl(get(), get(), get()) }
}
