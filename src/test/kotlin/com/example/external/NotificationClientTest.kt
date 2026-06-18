package com.example.external

import com.example.models.Appointment
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class NotificationClientTest {

    private fun makeClient(engine: MockEngine) = HttpClient(engine) {
        install(ContentNegotiation) { json() }
    }

    @Test
    fun `notify succeeds on first attempt`() = runTest {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, jsonContentType()) }
        val client = NotificationClient(makeClient(engine), "http://test")

        client.notify(testAppointment)

        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun `notify retries on server error and succeeds on third attempt`() = runTest {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount < NotificationClient.MAX_ATTEMPTS)
                respond("unavailable", HttpStatusCode.ServiceUnavailable)
            else
                respond("{}", HttpStatusCode.OK, jsonContentType())
        }
        val client = NotificationClient(makeClient(engine), "http://test")

        client.notify(testAppointment)

        assertEquals(NotificationClient.MAX_ATTEMPTS, engine.requestHistory.size)
    }

    @Test
    fun `notify throws after all retries exhausted`() = runTest {
        val engine = MockEngine { respond("error", HttpStatusCode.ServiceUnavailable) }
        val client = NotificationClient(makeClient(engine), "http://test")

        assertThrows<Exception> { client.notify(testAppointment) }
        assertEquals(NotificationClient.MAX_ATTEMPTS, engine.requestHistory.size)
    }

    @Test
    fun `notify sends correct payload`() = runTest {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, jsonContentType()) }
        val client = NotificationClient(makeClient(engine), "http://test")

        client.notify(testAppointment)

        val body = engine.requestHistory.single().body.toByteArray().decodeToString()
        assert(body.contains(testAppointment.attendee))
        assert(body.contains(testAppointment.title))
        assert(body.contains(testAppointment.scheduledAt))
    }

    private fun jsonContentType() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private val testAppointment = Appointment(
        id = "a1",
        title = "Checkup",
        description = "Annual",
        scheduledAt = "2026-07-01T09:00:00Z",
        durationMinutes = 60,
        attendee = "bob@example.com",
    )
}
