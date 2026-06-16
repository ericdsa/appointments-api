package com.example.models

import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class Appointment(
    val id: String,
    val title: String,
    val description: String,
    val scheduledAt: String,   // ISO 8601, e.g. "2026-06-15T10:00:00Z"
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
)

object AppointmentStore {
    private val store = ConcurrentHashMap<String, Appointment>()

    fun all(): List<Appointment> = store.values.toList()

    fun find(id: String): Appointment? = store[id]

    fun create(req: AppointmentRequest): Appointment {
        val appointment = Appointment(
            id = UUID.randomUUID().toString(),
            title = req.title,
            description = req.description,
            scheduledAt = req.scheduledAt,
            durationMinutes = req.durationMinutes,
            attendee = req.attendee,
        )
        store[appointment.id] = appointment
        return appointment
    }

    fun update(id: String, req: AppointmentRequest): Appointment? {
        val existing = store[id] ?: return null
        val updated = existing.copy(
            title = req.title,
            description = req.description,
            scheduledAt = req.scheduledAt,
            durationMinutes = req.durationMinutes,
            attendee = req.attendee,
        )
        store[id] = updated
        return updated
    }

    fun delete(id: String): Boolean = store.remove(id) != null
}
