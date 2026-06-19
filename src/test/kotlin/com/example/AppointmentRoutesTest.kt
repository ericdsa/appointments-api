package com.example

import com.example.models.Appointment
import com.example.models.AppointmentRequest
import com.example.models.ServiceResult
import com.example.plugins.ApiKeyAuth
import com.example.plugins.configureRouting
import com.example.plugins.configureSerialization
import com.example.plugins.configureStatusPages
import com.example.service.AppointmentService
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
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin

private const val TEST_API_KEY = "test-key"

private class FakeAppointmentService : AppointmentService {
    private val store = ConcurrentHashMap<String, Appointment>()

    override suspend fun getAll() = ServiceResult.Success(store.values.toList())

    override suspend fun getById(id: String) =
        store[id]?.let { ServiceResult.Success(it) } ?: ServiceResult.NotFound

    override suspend fun create(req: AppointmentRequest): ServiceResult<Appointment> {
        if (req.title.isBlank()) return ServiceResult.ValidationError("title must not be blank")
        return try {
            req.validate()
            val a = Appointment(UUID.randomUUID().toString(), req.title, req.description, req.scheduledAt, req.durationMinutes, req.attendee)
            store[a.id] = a
            ServiceResult.Success(a)
        } catch (e: IllegalArgumentException) {
            ServiceResult.ValidationError(e.message ?: "Invalid request")
        }
    }

    override suspend fun update(id: String, req: AppointmentRequest): ServiceResult<Appointment> {
        val existing = store[id] ?: return ServiceResult.NotFound
        return try {
            req.validate()
            val updated = existing.copy(title = req.title, description = req.description, scheduledAt = req.scheduledAt, durationMinutes = req.durationMinutes, attendee = req.attendee)
            store[id] = updated
            ServiceResult.Success(updated)
        } catch (e: IllegalArgumentException) {
            ServiceResult.ValidationError(e.message ?: "Invalid request")
        }
    }

    override suspend fun reschedule(id: String, req: AppointmentRequest): ServiceResult<Appointment> {
        store.remove(id) ?: return ServiceResult.NotFound
        return create(req)
    }

    override suspend fun delete(id: String) =
        if (store.remove(id) != null) ServiceResult.Success(Unit) else ServiceResult.NotFound

    override suspend fun sendReminders(): ServiceResult<com.example.models.ReminderSummary> {
        val total = store.size
        return ServiceResult.Success(com.example.models.ReminderSummary(total, total, 0))
    }
}

private fun ApplicationTestBuilder.setup() {
    application {
        install(Koin) {
            modules(module { single<AppointmentService> { FakeAppointmentService() } })
        }
        install(ApiKeyAuth) { apiKey = TEST_API_KEY }
        configureSerialization()
        configureStatusPages()
        configureRouting()
    }
}

private fun HttpRequestBuilder.apiKey() = header("X-Api-Key", TEST_API_KEY)

class AppointmentRoutesTest {

    @Test
    fun `GET appointments returns empty list initially`() = testApplication {
        setup()
        val response = client.get("/appointments") { apiKey() }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("[]", response.bodyAsText().trim())
    }

    @Test
    fun `POST appointments creates and GET retrieves it`() = testApplication {
        setup()
        val body = """{"title":"Dentist","description":"Annual checkup","scheduledAt":"2026-07-01T09:00:00Z","durationMinutes":60,"attendee":"Alice"}"""

        val post = client.post("/appointments") {
            apiKey()
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Created, post.status)
        assertContains(post.bodyAsText(), "Dentist")

        val list = client.get("/appointments") { apiKey() }
        assertEquals(HttpStatusCode.OK, list.status)
        assertContains(list.bodyAsText(), "Dentist")
    }

    @Test
    fun `GET appointments by unknown id returns 404`() = testApplication {
        setup()
        val response = client.get("/appointments/does-not-exist") { apiKey() }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `missing API key returns 401`() = testApplication {
        setup()
        val response = client.get("/appointments")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `wrong API key returns 403`() = testApplication {
        setup()
        val response = client.get("/appointments") { header("X-Api-Key", "wrong") }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `POST with blank title returns 400`() = testApplication {
        setup()
        val body = """{"title":"","description":"x","scheduledAt":"2026-07-01T09:00:00Z","durationMinutes":30,"attendee":"Bob"}"""
        val response = client.post("/appointments") {
            apiKey()
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
            apiKey()
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
            apiKey()
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Created, post.status)
        val id = Regex(""""id"\s*:\s*"([^"]+)"""").find(post.bodyAsText())!!.groupValues[1]

        val del = client.delete("/appointments/$id") { apiKey() }
        assertEquals(HttpStatusCode.NoContent, del.status)

        val get = client.get("/appointments/$id") { apiKey() }
        assertEquals(HttpStatusCode.NotFound, get.status)
    }

    @Test
    fun `POST reminders returns 202 Accepted`() = testApplication {
        setup()
        val response = client.post("/appointments/reminders") { apiKey() }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST reschedule returns rescheduled appointment`() = testApplication {
        setup()
        val body = """{"title":"Meeting","description":"x","scheduledAt":"2026-07-01T09:00:00Z","durationMinutes":30,"attendee":"Bob"}"""
        val post = client.post("/appointments") {
            apiKey()
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val id = Regex(""""id"\s*:\s*"([^"]+)"""").find(post.bodyAsText())!!.groupValues[1]

        val rescheduleBody = """{"title":"Meeting","description":"x","scheduledAt":"2026-08-01T10:00:00Z","durationMinutes":30,"attendee":"Bob"}"""
        val reschedule = client.post("/appointments/$id/reschedule") {
            apiKey()
            contentType(ContentType.Application.Json)
            setBody(rescheduleBody)
        }
        assertEquals(HttpStatusCode.OK, reschedule.status)
        assertContains(reschedule.bodyAsText(), "2026-08-01T10:00:00Z")
    }
}
