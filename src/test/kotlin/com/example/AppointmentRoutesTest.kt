package com.example

import com.example.models.Appointment
import com.example.models.AppointmentRequest
import com.example.plugins.configureRouting
import com.example.plugins.configureSerialization
import com.example.plugins.configureStatusPages
import com.example.repository.AppointmentRepository
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

private class FakeAppointmentRepository : AppointmentRepository {
    private val store = ConcurrentHashMap<String, Appointment>()

    override suspend fun findAll() = store.values.toList()
    override suspend fun findById(id: String) = store[id]
    override suspend fun create(req: AppointmentRequest): Appointment {
        val a = Appointment(UUID.randomUUID().toString(), req.title, req.description, req.scheduledAt, req.durationMinutes, req.attendee)
        store[a.id] = a
        return a
    }
    override suspend fun update(id: String, req: AppointmentRequest): Appointment? {
        val existing = store[id] ?: return null
        val updated = existing.copy(title = req.title, description = req.description, scheduledAt = req.scheduledAt, durationMinutes = req.durationMinutes, attendee = req.attendee)
        store[id] = updated
        return updated
    }
    override suspend fun delete(id: String) = store.remove(id) != null
}

private fun ApplicationTestBuilder.setup() {
    application {
        configureSerialization()
        configureStatusPages()
        configureRouting(FakeAppointmentRepository())
    }
}

class AppointmentRoutesTest {

    @Test
    fun `GET appointments returns empty list initially`() = testApplication {
        setup()
        val response = client.get("/appointments")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("[]", response.bodyAsText().trim())
    }

    @Test
    fun `POST appointments creates and GET retrieves it`() = testApplication {
        setup()

        val body = """{"title":"Dentist","description":"Annual checkup","scheduledAt":"2026-07-01T09:00:00Z","durationMinutes":60,"attendee":"Alice"}"""

        val post = client.post("/appointments") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Created, post.status)
        assertContains(post.bodyAsText(), "Dentist")

        val list = client.get("/appointments")
        assertEquals(HttpStatusCode.OK, list.status)
        assertContains(list.bodyAsText(), "Dentist")
    }

    @Test
    fun `GET appointments by unknown id returns 404`() = testApplication {
        setup()
        val response = client.get("/appointments/does-not-exist")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST with blank title returns 400`() = testApplication {
        setup()
        val body = """{"title":"","description":"x","scheduledAt":"2026-07-01T09:00:00Z","durationMinutes":30,"attendee":"Bob"}"""
        val response = client.post("/appointments") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "title")
    }

    @Test
    fun `POST with invalid scheduledAt returns 400`() = testApplication {
        setup()
        val body = """{"title":"Meeting","description":"x","scheduledAt":"not-a-date","durationMinutes":30,"attendee":"Bob"}"""
        val response = client.post("/appointments") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `DELETE removes appointment and returns 204`() = testApplication {
        setup()
        val body = """{"title":"Meeting","description":"x","scheduledAt":"2026-07-01T09:00:00Z","durationMinutes":30,"attendee":"Bob"}"""
        val post = client.post("/appointments") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Created, post.status)
        val id = Regex(""""id"\s*:\s*"([^"]+)"""").find(post.bodyAsText())!!.groupValues[1]

        val del = client.delete("/appointments/$id")
        assertEquals(HttpStatusCode.NoContent, del.status)

        val get = client.get("/appointments/$id")
        assertEquals(HttpStatusCode.NotFound, get.status)
    }
}
