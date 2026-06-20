package com.example.worker

import com.example.external.NotificationClient
import com.example.repository.AppointmentRepository
import com.example.repository.ReminderJobRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant

// Drains the reminder_jobs queue: claims a batch of due jobs, sends each attendee
// their notification, and marks the job done (or requeues / fails it). Runs as a
// single long-lived coroutine on the application scope; multiple instances are
// safe because claimBatch uses FOR UPDATE SKIP LOCKED.
class ReminderWorker(
    private val scope: CoroutineScope,
    private val reminderJobRepo: ReminderJobRepository,
    private val appointmentRepo: AppointmentRepository,
    private val notificationClient: NotificationClient,
    private val pollMs: Long = 1_000,
    private val batchSize: Int = 20,
    private val maxAttempts: Int = 3,
    // A PROCESSING job whose lock is older than this is treated as orphaned (its
    // worker died) and reclaimed by the next poll.
    private val staleLockMs: Long = 5 * 60 * 1_000,
) {
    private val log = LoggerFactory.getLogger(ReminderWorker::class.java)
    private var loop: Job? = null

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            log.info("ReminderWorker started (poll=${pollMs}ms, batch=$batchSize)")
            while (isActive) {
                val processed = runCatching { drainOnce() }
                    .onFailure { log.error("ReminderWorker poll failed", it) }
                    .getOrDefault(0)
                // Only back off when the queue was empty; otherwise keep draining.
                if (processed == 0) delay(pollMs)
            }
        }
    }

    suspend fun stop() {
        // Join so we don't return while a job is mid-send: an abandoned PROCESSING
        // row would otherwise sit until the staleness reaper picks it up.
        loop?.cancelAndJoin()
        loop = null
    }

    private suspend fun drainOnce(): Int {
        val now = Instant.now()
        val jobs = reminderJobRepo.claimBatch(
            now = now.toString(),
            staleBefore = now.minusMillis(staleLockMs).toString(),
            limit = batchSize,
        )
        for (job in jobs) {
            val appointment = appointmentRepo.findById(job.appointmentId)
            if (appointment == null) {
                // Appointment vanished (e.g. deleted); nothing to send, so retire the job.
                reminderJobRepo.markFailed(job.id, "appointment ${job.appointmentId} not found", requeue = false)
                continue
            }
            try {
                notificationClient.notify(appointment)
                reminderJobRepo.markDone(job.id)
            } catch (e: Exception) {
                val requeue = job.attempts + 1 < maxAttempts
                log.warn("Reminder job ${job.id} failed (attempt ${job.attempts + 1}), requeue=$requeue", e)
                reminderJobRepo.markFailed(job.id, e.message ?: "unknown error", requeue)
            }
        }
        return jobs.size
    }
}
