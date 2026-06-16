package com.example.models

import kotlinx.serialization.Serializable

@Serializable
data class Appointment(
    val id: String,
    val title: String,
    val description: String,
    val scheduledAt: String,
    val durationMinutes: Int,
    val attendee: String,
)

@Serializable
data class AppointmentRequest(
    val title: String,
    val description: String,
    val scheduledAt: String,
    val durationMinutes: Int,
    val attendee: String,
) {
    fun validate() {
        require(title.isNotBlank()) { "title must not be blank" }
        require(attendee.isNotBlank()) { "attendee must not be blank" }
        require(durationMinutes > 0) { "durationMinutes must be positive" }
        try {
            java.time.Instant.parse(scheduledAt)
        } catch (_: Exception) {
            throw IllegalArgumentException("scheduledAt must be a valid ISO 8601 instant (e.g. 2026-06-15T10:00:00Z)")
        }
    }
}
