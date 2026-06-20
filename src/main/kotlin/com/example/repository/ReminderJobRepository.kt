package com.example.repository

import com.example.models.ReminderJob

interface ReminderJobRepository {
    suspend fun enqueue(appointmentId: String, scheduledFor: String): ReminderJob

    // Atomically claims up to [limit] due, PENDING jobs, flipping them to PROCESSING
    // so a concurrent worker (or a second poll) never picks up the same row.
    suspend fun claimBatch(now: String, limit: Int): List<ReminderJob>

    suspend fun markDone(id: String)

    // Records the failure. When [requeue] is true the job returns to PENDING for
    // another attempt; otherwise it is parked as FAILED.
    suspend fun markFailed(id: String, error: String, requeue: Boolean)
}
