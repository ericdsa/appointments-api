package com.example.plugins

import com.example.routes.appointmentRoutes
import com.example.service.AppointmentService
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Application.configureRouting() {
    val service by inject<AppointmentService>()
    routing {
        appointmentRoutes(service)
    }
}
