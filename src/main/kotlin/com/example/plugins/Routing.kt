package com.example.plugins

import com.example.repository.AppointmentRepository
import com.example.routes.appointmentRoutes
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Application.configureRouting() {
    val repo by inject<AppointmentRepository>()
    routing {
        appointmentRoutes(repo)
    }
}
