package com.example.service

import com.example.db.AppointmentsTable
import com.example.external.NotificationClient
import com.example.models.Appointment
import com.example.models.AppointmentRequest
import com.example.models.EnqueueSummary
import com.example.models.Page
import com.example.models.PageRequest
import com.example.models.ServiceResult
import com.example.repository.AppointmentRepository
import com.example.repository.ReminderJobRepository
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import java.time.Instant
import java.util.UUID

// Wraps a block in a DB transaction. Injectable so unit tests can substitute a
// pass-through that never touches a real database.
interface TransactionRunner {
    suspend fun <T> execute(block: suspend () -> T): T
}

class AppointmentServiceImpl(
    private val repo: AppointmentRepository,
    private val reminderJobRepo: ReminderJobRepository,
    private val notificationClient: NotificationClient,
    private val tx: TransactionRunner,
) : AppointmentService {

    override suspend fun getAll(): ServiceResult<List<Appointment>> =
        ServiceResult.Success(repo.findAll())

    override suspend fun getPage(req: PageRequest): ServiceResult<Page<Appointment>> {
        if (req.page < 1) return ServiceResult.ValidationError("page must be >= 1")
        if (req.size !in 1..PageRequest.MAX_SIZE)
            return ServiceResult.ValidationError("size must be between 1 and ${PageRequest.MAX_SIZE}")

        val offset = (req.page - 1).toLong() * req.size
        val total = repo.count()
        val items = repo.findPage(req.size, offset)
        val totalPages = if (total == 0L) 0L else (total + req.size - 1) / req.size
        return ServiceResult.Success(
            Page(items = items, page = req.page, size = req.size, total = total, totalPages = totalPages)
        )
    }

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

    // Places one durable reminder job per appointment on the queue and returns
    // immediately. The ReminderWorker drains the queue and performs the actual
    // notification, so a crash mid-dispatch never loses reminders the way the old
    // in-process fan-out did.
    override suspend fun enqueueReminders(): ServiceResult<EnqueueSummary> {
        val appointments = repo.findAll()
        val now = Instant.now().toString()
        appointments.forEach { reminderJobRepo.enqueue(it.id, now) }
        return ServiceResult.Success(EnqueueSummary(enqueued = appointments.size))
    }

    private suspend fun notifyThenWrap(appointment: Appointment): ServiceResult<Appointment> =
        try {
            notificationClient.notify(appointment)
            ServiceResult.Success(appointment)
        } catch (e: Exception) {
            ServiceResult.ExternalApiError("Notification failed: ${e.message}")
        }
}
