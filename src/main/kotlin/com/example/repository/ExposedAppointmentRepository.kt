package com.example.repository

import com.example.db.AppointmentsTable
import com.example.models.Appointment
import com.example.models.AppointmentRequest
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.util.UUID

class ExposedAppointmentRepository(private val database: Database) : AppointmentRepository {

    private suspend fun <T> query(block: () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }

    private fun ResultRow.toAppointment() = Appointment(
        id = this[AppointmentsTable.id],
        title = this[AppointmentsTable.title],
        description = this[AppointmentsTable.description],
        scheduledAt = this[AppointmentsTable.scheduledAt],
        durationMinutes = this[AppointmentsTable.durationMinutes],
        attendee = this[AppointmentsTable.attendee],
    )

    override suspend fun findAll(): List<Appointment> = query {
        AppointmentsTable.selectAll().map { it.toAppointment() }
    }

    // Stable ordering so paging is deterministic: scheduled_at is ISO-8601 text and
    // therefore sorts chronologically, with id as a tie-breaker for equal instants.
    override suspend fun findPage(limit: Int, offset: Long): List<Appointment> = query {
        AppointmentsTable.selectAll()
            .orderBy(
                AppointmentsTable.scheduledAt to SortOrder.ASC,
                AppointmentsTable.id to SortOrder.ASC,
            )
            .limit(limit, offset)
            .map { it.toAppointment() }
    }

    override suspend fun count(): Long = query {
        AppointmentsTable.selectAll().count()
    }

    override suspend fun findById(id: String): Appointment? = query {
        AppointmentsTable.selectAll()
            .where { AppointmentsTable.id eq id }
            .singleOrNull()
            ?.toAppointment()
    }

    override suspend fun create(req: AppointmentRequest): Appointment {
        val id = UUID.randomUUID().toString()
        query {
            AppointmentsTable.insert {
                it[AppointmentsTable.id] = id
                it[title] = req.title
                it[description] = req.description
                it[scheduledAt] = req.scheduledAt
                it[durationMinutes] = req.durationMinutes
                it[attendee] = req.attendee
            }
        }
        return Appointment(id, req.title, req.description, req.scheduledAt, req.durationMinutes, req.attendee)
    }

    override suspend fun update(id: String, req: AppointmentRequest): Appointment? {
        val updated = query {
            AppointmentsTable.update({ AppointmentsTable.id eq id }) {
                it[title] = req.title
                it[description] = req.description
                it[scheduledAt] = req.scheduledAt
                it[durationMinutes] = req.durationMinutes
                it[attendee] = req.attendee
            }
        }
        return if (updated == 0) null
        else Appointment(id, req.title, req.description, req.scheduledAt, req.durationMinutes, req.attendee)
    }

    override suspend fun delete(id: String): Boolean = query {
        AppointmentsTable.deleteWhere { AppointmentsTable.id eq id } > 0
    }
}
