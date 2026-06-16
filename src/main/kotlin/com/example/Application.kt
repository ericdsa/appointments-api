package com.example

import com.example.db.connectDatabase
import com.example.plugins.configureRouting
import com.example.plugins.configureSerialization
import com.example.plugins.configureStatusPages
import com.example.repository.ExposedAppointmentRepository
import io.ktor.server.application.*
import io.ktor.server.netty.*

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module() {
    configureSerialization()
    configureStatusPages()
    val database = connectDatabase()
    configureRouting(ExposedAppointmentRepository(database))
}
