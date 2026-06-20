package com.example.routes

import com.example.models.AppointmentRequest
import com.example.models.PageRequest
import com.example.models.ServiceResult
import com.example.service.AppointmentService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.appointmentRoutes(service: AppointmentService) {
    route("/appointments") {
        get {
            when (val r = service.getPage(call.request.queryParameters.pageRequest())) {
                is ServiceResult.Success -> call.respond(r.value)
                else -> call.respondError(r)
            }
        }

        get("/{id}") {
            val id = call.parameters["id"]!!
            when (val r = service.getById(id)) {
                is ServiceResult.Success -> call.respond(r.value)
                else -> call.respondError(r)
            }
        }

        post {
            val req = call.receive<AppointmentRequest>()
            when (val r = service.create(req)) {
                is ServiceResult.Success -> call.respond(HttpStatusCode.Created, r.value)
                else -> call.respondError(r)
            }
        }

        put("/{id}") {
            val id = call.parameters["id"]!!
            val req = call.receive<AppointmentRequest>()
            when (val r = service.update(id, req)) {
                is ServiceResult.Success -> call.respond(r.value)
                else -> call.respondError(r)
            }
        }

        post("/reminders") {
            // Durably enqueue one reminder job per appointment and return 202
            // immediately. A background worker drains the queue and performs the
            // notifications, so the work survives a restart instead of being lost
            // with an in-process fire-and-forget coroutine.
            when (val r = service.enqueueReminders()) {
                is ServiceResult.Success -> call.respond(HttpStatusCode.Accepted, r.value)
                else -> call.respondError(r)
            }
        }

        post("/{id}/reschedule") {
            val id = call.parameters["id"]!!
            val req = call.receive<AppointmentRequest>()
            when (val r = service.reschedule(id, req)) {
                is ServiceResult.Success -> call.respond(r.value)
                else -> call.respondError(r)
            }
        }

        delete("/{id}") {
            val id = call.parameters["id"]!!
            when (service.delete(id)) {
                is ServiceResult.Success -> call.respond(HttpStatusCode.NoContent)
                is ServiceResult.NotFound -> call.respond(
                    HttpStatusCode.NotFound, mapOf("error" to "Appointment not found")
                )
                else -> {}
            }
        }
    }
}

// Parses ?page= and ?size= with sane defaults. Out-of-range or non-numeric values
// fall back to the defaults here; the service enforces the hard bounds and returns
// a ValidationError for anything it rejects.
private fun Parameters.pageRequest(): PageRequest =
    PageRequest(
        page = this["page"]?.toIntOrNull() ?: PageRequest.DEFAULT_PAGE,
        size = this["size"]?.toIntOrNull() ?: PageRequest.DEFAULT_SIZE,
    )

private suspend fun ApplicationCall.respondError(result: ServiceResult<*>) {
    when (result) {
        is ServiceResult.NotFound ->
            respond(HttpStatusCode.NotFound, mapOf("error" to "Appointment not found"))
        is ServiceResult.ValidationError ->
            respond(HttpStatusCode.BadRequest, mapOf("error" to result.message))
        is ServiceResult.ExternalApiError ->
            respond(HttpStatusCode.BadGateway, mapOf("error" to result.message))
        is ServiceResult.Success -> {}
    }
}
