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
import org.jetbrains.exposed.sql.insert
import org.slf4j.LoggerFactory
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

    private val log = LoggerFactory.getLogger(AppointmentServiceImpl::class.java)

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

    // Updates the appointment in place, preserving its id. An in-place UPDATE is
    // atomic on its own, so there is no window where the appointment is missing,
    // and (unlike a delete + re-insert) it keeps the id stable and leaves the
    // appointment's queued reminder_jobs — which FK to it — intact.
    override suspend fun reschedule(id: String, req: AppointmentRequest): ServiceResult<Appointment> {
        try { req.validate() } catch (e: IllegalArgumentException) {
            return ServiceResult.ValidationError(e.message ?: "Invalid request")
        }
        val rescheduled = repo.update(id, req) ?: return ServiceResult.NotFound
        return notifyThenWrap(rescheduled)
    }

    override suspend fun delete(id: String): ServiceResult<Unit> =
        if (repo.delete(id)) ServiceResult.Success(Unit) else ServiceResult.NotFound

    // Durably enqueues a reminder for every upcoming appointment in one set-based
    // statement and returns immediately; the ReminderWorker drains the queue and
    // sends the notifications. Idempotent (skips appointments with an in-flight job)
    // and bounded — it never loads the table into memory the way the old per-row
    // fan-out did.
    override suspend fun enqueueReminders(): ServiceResult<EnqueueSummary> {
        val enqueued = reminderJobRepo.enqueueDueForUpcoming(Instant.now().toString())
        return ServiceResult.Success(EnqueueSummary(enqueued = enqueued))
    }

    // The appointment is already persisted by the time we get here, so a failed
    // notification must not fail the write — otherwise the client sees an error for
    // a record that exists and retries, creating duplicates. Notification is a
    // best-effort side effect: log the failure and still return the saved record.
    private suspend fun notifyThenWrap(appointment: Appointment): ServiceResult<Appointment> {
        try {
            notificationClient.notify(appointment)
        } catch (e: Exception) {
            log.warn("Notification failed for appointment ${appointment.id}; write succeeded", e)
        }
        return ServiceResult.Success(appointment)
    }
}
