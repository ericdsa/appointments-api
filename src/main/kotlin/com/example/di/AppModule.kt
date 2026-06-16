package com.example.di

import com.example.repository.AppointmentRepository
import com.example.repository.ExposedAppointmentRepository
import org.jetbrains.exposed.sql.Database
import org.koin.dsl.module

fun appointmentModule(database: Database) = module {
    single<AppointmentRepository> { ExposedAppointmentRepository(database) }
}
