package com.example.repository

import com.example.db.ReminderJobsTable
import com.example.models.ReminderJob
import com.example.models.ReminderJobStatus
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.IntegerColumnType
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

class ExposedReminderJobRepository(private val database: Database) : ReminderJobRepository {

    private suspend fun <T> query(block: () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }

    override suspend fun enqueue(appointmentId: String, scheduledFor: String): ReminderJob {
        val now = Instant.now().toString()
        val job = ReminderJob(
            id = UUID.randomUUID().toString(),
            appointmentId = appointmentId,
            status = ReminderJobStatus.PENDING,
            attempts = 0,
            scheduledFor = scheduledFor,
            lockedAt = null,
            lastError = null,
            createdAt = now,
            updatedAt = now,
        )
        query {
            ReminderJobsTable.insert {
                it[id] = job.id
                it[ReminderJobsTable.appointmentId] = job.appointmentId
                it[status] = job.status.name
                it[attempts] = job.attempts
                it[ReminderJobsTable.scheduledFor] = job.scheduledFor
                it[createdAt] = job.createdAt
                it[updatedAt] = job.updatedAt
            }
        }
        return job
    }

    // One statement, set-based: insert a PENDING job for every future appointment
    // that doesn't already have an in-flight one. gen_random_uuid()::text supplies
    // the id (Postgres 13+ built-in); the timestamps are passed in so they keep the
    // app's ISO-8601 'Z' format that the queue's lexicographic ordering relies on.
    // RETURNING id lets us count the rows actually inserted.
    override suspend fun enqueueDueForUpcoming(now: String): Int = query {
        val sql = """
            INSERT INTO reminder_jobs
                (id, appointment_id, status, attempts, scheduled_for, created_at, updated_at)
            SELECT gen_random_uuid()::text, a.id, 'PENDING', 0, ?, ?, ?
            FROM appointments a
            WHERE a.scheduled_at > ?
              AND NOT EXISTS (
                  SELECT 1 FROM reminder_jobs r
                  WHERE r.appointment_id = a.id
                    AND r.status IN ('PENDING', 'PROCESSING')
              )
            RETURNING id
        """.trimIndent()

        val args = listOf(
            VarCharColumnType() to now, // scheduled_for: due immediately
            VarCharColumnType() to now, // created_at
            VarCharColumnType() to now, // updated_at
            VarCharColumnType() to now, // scheduled_at > now: upcoming only
        )

        org.jetbrains.exposed.sql.transactions.TransactionManager.current()
            .exec(sql, args, explicitStatementType = StatementType.SELECT) { rs ->
                var count = 0
                while (rs.next()) count++
                count
            } ?: 0
    }

    // Single round-trip claim using FOR UPDATE SKIP LOCKED inside a subquery, then
    // UPDATE ... RETURNING to flip the rows to PROCESSING and read them back. This
    // is the canonical Postgres job-queue pattern; the Exposed DSL cannot express
    // SKIP LOCKED, so we drop to raw SQL for just this statement.
    override suspend fun claimBatch(now: String, staleBefore: String, limit: Int): List<ReminderJob> = query {
        val sql = """
            UPDATE reminder_jobs
            SET status = 'PROCESSING', locked_at = ?, updated_at = ?
            WHERE id IN (
                SELECT id FROM reminder_jobs
                WHERE (status = 'PENDING' AND scheduled_for <= ?)
                   OR (status = 'PROCESSING' AND locked_at < ?)
                ORDER BY scheduled_for
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            RETURNING id, appointment_id, status, attempts, scheduled_for,
                      locked_at, last_error, created_at, updated_at
        """.trimIndent()

        val args = listOf(
            VarCharColumnType() to now,
            VarCharColumnType() to now,
            VarCharColumnType() to now,
            VarCharColumnType() to staleBefore,
            IntegerColumnType() to limit,
        )

        org.jetbrains.exposed.sql.transactions.TransactionManager.current()
            .exec(sql, args, explicitStatementType = StatementType.SELECT) { rs ->
                rs.toReminderJobs()
            } ?: emptyList()
    }

    // Scoped to PROCESSING so a job that was reclaimed (because this worker's lock
    // went stale) and is now owned by another worker isn't clobbered by this one.
    override suspend fun markDone(id: String) = query {
        ReminderJobsTable.update({ (ReminderJobsTable.id eq id) and (ReminderJobsTable.status eq ReminderJobStatus.PROCESSING.name) }) {
            it[status] = ReminderJobStatus.DONE.name
            it[updatedAt] = Instant.now().toString()
        }
        Unit
    }

    override suspend fun markFailed(id: String, error: String, requeue: Boolean) = query {
        val nextStatus = if (requeue) ReminderJobStatus.PENDING else ReminderJobStatus.FAILED
        ReminderJobsTable.update({ (ReminderJobsTable.id eq id) and (ReminderJobsTable.status eq ReminderJobStatus.PROCESSING.name) }) {
            it[status] = nextStatus.name
            with(SqlExpressionBuilder) { it[attempts] = attempts + 1 }
            it[lastError] = error
            it[lockedAt] = null
            it[updatedAt] = Instant.now().toString()
        }
        Unit
    }

    private fun ResultSet.toReminderJobs(): List<ReminderJob> {
        val jobs = mutableListOf<ReminderJob>()
        while (next()) {
            jobs += ReminderJob(
                id = getString("id"),
                appointmentId = getString("appointment_id"),
                status = ReminderJobStatus.valueOf(getString("status")),
                attempts = getInt("attempts"),
                scheduledFor = getString("scheduled_for"),
                lockedAt = getString("locked_at"),
                lastError = getString("last_error"),
                createdAt = getString("created_at"),
                updatedAt = getString("updated_at"),
            )
        }
        return jobs
    }
}
