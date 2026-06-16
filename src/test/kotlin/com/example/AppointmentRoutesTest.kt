package com.example

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains

class AppointmentRoutesTest {

    @Test
    fun `GET appointments returns empty list initially`() = testApplication {
        application { module() }
        val response = client.get("/appointments")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("[ ]", response.bodyAsText().trim())
    }

    @Test
    fun `POST appointments creates and GET retrieves it`() = testApplication {
        application { module() }

        val body = """
            {
                "title": "Dentist",
                "description": "Annual checkup",
                "scheduledAt": "2026-07-01T09:00:00Z",
                "durationMinutes": 60,
                "attendee": "Alice"
            }
        """.trimIndent()

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
        application { module() }
        val response = client.get("/appointments/does-not-exist")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
