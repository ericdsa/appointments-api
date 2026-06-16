package com.example.plugins

import com.example.repository.AppointmentRepository
import com.example.routes.appointmentRoutes
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRouting(repo: AppointmentRepository) {
    routing {
        appointmentRoutes(repo)
    }
}
