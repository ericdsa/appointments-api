package com.example.repository

import com.example.models.ReminderJob

interface ReminderJobRepository {
    suspend fun enqueue(appointmentId: String, scheduledFor: String): ReminderJob

    // Enqueues one due reminder job per *upcoming* appointment (scheduled_at in the
    // future) in a single set-based statement, and returns how many were created.
    // Idempotent: an appointment that already has an in-flight (PENDING/PROCESSING)
    // job is skipped, so calling this repeatedly never double-sends. [now] is both
    // the cutoff for "upcoming" and the time the new jobs become due.
    suspend fun enqueueDueForUpcoming(now: String): Int

    // Atomically claims up to [limit] jobs, flipping them to PROCESSING so a
    // concurrent worker (or a second poll) never picks up the same row. A job is
    // claimable if it is due and PENDING, or if it is stuck in PROCESSING with a
    // [staleBefore] lock — i.e. a worker claimed it and then died before finishing.
    // Reclaiming stale rows is what keeps a crash from orphaning in-flight jobs.
    suspend fun claimBatch(now: String, staleBefore: String, limit: Int): List<ReminderJob>

    suspend fun markDone(id: String)

    // Records the failure. When [requeue] is true the job returns to PENDING for
    // another attempt; otherwise it is parked as FAILED.
    suspend fun markFailed(id: String, error: String, requeue: Boolean)
}
