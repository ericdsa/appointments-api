package com.example.routes

import com.example.models.AppointmentRequest
import com.example.models.AppointmentStore
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.appointmentRoutes() {
    route("/appointments") {
        get {
            call.respond(AppointmentStore.all())
        }

        get("/{id}") {
            val id = call.parameters["id"]!!
            val appointment = AppointmentStore.find(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Appointment not found"))
            call.respond(appointment)
        }

        post {
            val req = call.receive<AppointmentRequest>()
            val created = AppointmentStore.create(req)
            call.respond(HttpStatusCode.Created, created)
        }

        put("/{id}") {
            val id = call.parameters["id"]!!
            val req = call.receive<AppointmentRequest>()
            val updated = AppointmentStore.update(id, req)
                ?: return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to "Appointment not found"))
            call.respond(updated)
        }

        delete("/{id}") {
            val id = call.parameters["id"]!!
            if (AppointmentStore.delete(id)) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Appointment not found"))
            }
        }
    }
}
