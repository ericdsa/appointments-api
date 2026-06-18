package com.example.service

import com.example.db.AppointmentsTable
import com.example.external.NotificationClient
import com.example.models.Appointment
import com.example.models.AppointmentRequest
import com.example.models.ServiceResult
import com.example.repository.AppointmentRepository
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import java.util.UUID

// Wraps a block in a DB transaction. Injectable so unit tests can substitute a
// pass-through that never touches a real database.
interface TransactionRunner {
    suspend fun <T> execute(block: suspend () -> T): T
}

class AppointmentServiceImpl(
    private val repo: AppointmentRepository,
    private val notificationClient: NotificationClient,
    private val tx: TransactionRunner,
) : AppointmentService {

    override suspend fun getAll(): ServiceResult<List<Appointment>> =
        ServiceResult.Success(repo.findAll())

    override suspend fun getById(id: String): ServiceResult<Appointment> =
        repo.findById(id)?.let { ServiceResult.Success(it) } ?: ServiceResult.NotFound

    override suspend fun create(req: AppointmentRequest): ServiceResult<Appointment> {
        try { req.validate() } catch (e: IllegalArgumentException) {
            return ServiceResult.ValidationError(e.message ?: "Invalid request")
        }
        val appointment: Appointment = tx.execute {
            val id = UUID.randomUUID().toString()
            AppointmentsTable.insert {
                it[AppointmentsTable.id] = id
                it[title] = req.title
                it[description] = req.description
                it[scheduledAt] = req.scheduledAt
                it[durationMinutes] = req.durationMinutes
                it[attendee] = req.attendee
            }
            Appointment(id, req.title, req.description, req.scheduledAt, req.durationMinutes, req.attendee)
        }
        return notifyThenWrap(appointment)
    }

    override suspend fun update(id: String, req: AppointmentRequest): ServiceResult<Appointment> {
        try { req.validate() } catch (e: IllegalArgumentException) {
            return ServiceResult.ValidationError(e.message ?: "Invalid request")
        }
        val updated = repo.update(id, req) ?: return ServiceResult.NotFound
        return notifyThenWrap(updated)
    }

    // Deletes the existing record and inserts the replacement in one transaction,
    // so a crash mid-way cannot leave the attendee with zero appointments.
    override suspend fun reschedule(id: String, req: AppointmentRequest): ServiceResult<Appointment> {
        try { req.validate() } catch (e: IllegalArgumentException) {
            return ServiceResult.ValidationError(e.message ?: "Invalid request")
        }
        val newId = UUID.randomUUID().toString()
        val rescheduled = tx.execute<Appointment?> {
            val deleted = AppointmentsTable.deleteWhere { AppointmentsTable.id eq id }
            if (deleted == 0) return@execute null
            AppointmentsTable.insert {
                it[AppointmentsTable.id] = newId
                it[title] = req.title
                it[description] = req.description
                it[scheduledAt] = req.scheduledAt
                it[durationMinutes] = req.durationMinutes
                it[attendee] = req.attendee
            }
            Appointment(newId, req.title, req.description, req.scheduledAt, req.durationMinutes, req.attendee)
        } ?: return ServiceResult.NotFound

        return notifyThenWrap(rescheduled)
    }

    override suspend fun delete(id: String): ServiceResult<Unit> =
        if (repo.delete(id)) ServiceResult.Success(Unit) else ServiceResult.NotFound

    private suspend fun notifyThenWrap(appointment: Appointment): ServiceResult<Appointment> =
        try {
            notificationClient.notify(appointment)
            ServiceResult.Success(appointment)
        } catch (e: Exception) {
            ServiceResult.ExternalApiError("Notification failed: ${e.message}")
        }
}
