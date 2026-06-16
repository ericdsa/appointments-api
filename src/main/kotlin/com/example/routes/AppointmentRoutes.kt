package com.example.routes

import com.example.models.AppointmentRequest
import com.example.repository.AppointmentRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.appointmentRoutes(repo: AppointmentRepository) {
    route("/appointments") {
        get {
            call.respond(repo.findAll())
        }

        get("/{id}") {
            val id = call.parameters["id"]!!
            val appointment = repo.findById(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Appointment not found"))
            call.respond(appointment)
        }

        post {
            val req = call.receive<AppointmentRequest>()
            req.validate()
            call.respond(HttpStatusCode.Created, repo.create(req))
        }

        put("/{id}") {
            val id = call.parameters["id"]!!
            val req = call.receive<AppointmentRequest>()
            req.validate()
            val updated = repo.update(id, req)
                ?: return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to "Appointment not found"))
            call.respond(updated)
        }

        delete("/{id}") {
            val id = call.parameters["id"]!!
            if (repo.delete(id)) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Appointment not found"))
            }
        }
    }
}
