package com.example.external

import com.example.models.Appointment
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import java.io.IOException

@Serializable
data class NotificationPayload(
    val attendee: String,
    val title: String,
    val scheduledAt: String,
)

class NotificationClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) {
    suspend fun notify(appointment: Appointment) {
        val payload = NotificationPayload(appointment.attendee, appointment.title, appointment.scheduledAt)
        var lastError: Exception? = null

        for (attempt in 0 until MAX_ATTEMPTS) {
            try {
                val response: HttpResponse = httpClient.post("$baseUrl/notifications") {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
                if (response.status.isSuccess()) return
                lastError = IOException("Notification service returned ${response.status.value}")
            } catch (e: Exception) {
                lastError = e
            }
            // exponential backoff: 500 ms, 1000 ms
            if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MS shl attempt)
        }

        throw lastError!!
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 500L
    }
}
