package com.example.worker

import com.example.external.NotificationClient
import com.example.models.Appointment
import com.example.models.ReminderJob
import com.example.models.ReminderJobStatus
import com.example.repository.AppointmentRepository
import com.example.repository.ReminderJobRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class ReminderWorkerTest {

    private val jobRepo: ReminderJobRepository = mockk(relaxed = true)
    private val appointmentRepo: AppointmentRepository = mockk(relaxed = true)
    private val notificationClient: NotificationClient = mockk()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun worker(maxAttempts: Int = 3) = ReminderWorker(
        scope = scope,
        reminderJobRepo = jobRepo,
        appointmentRepo = appointmentRepo,
        notificationClient = notificationClient,
        pollMs = 20,
        batchSize = 10,
        maxAttempts = maxAttempts,
    )

    // Hand the worker exactly one batch, then nothing, so each test drains a single
    // pass deterministically and we can verify the outcome with a mockk timeout.
    private fun serveOnce(vararg jobs: ReminderJob) {
        coEvery { jobRepo.claimBatch(any(), any(), any()) } returnsMany listOf(jobs.toList(), emptyList())
    }

    @AfterEach
    fun tearDown() = scope.cancel()

    @Test
    fun `successful send marks the job done`() {
        val job = job()
        serveOnce(job)
        coEvery { appointmentRepo.findById(job.appointmentId) } returns appointment()
        coEvery { notificationClient.notify(any()) } just Runs

        worker().start()

        coVerify(timeout = 2_000, exactly = 1) { jobRepo.markDone(job.id) }
        coVerify(exactly = 0) { jobRepo.markFailed(any(), any(), any()) }
    }

    @Test
    fun `failed send with attempts remaining requeues the job`() {
        val job = job(attempts = 0)
        serveOnce(job)
        coEvery { appointmentRepo.findById(job.appointmentId) } returns appointment()
        coEvery { notificationClient.notify(any()) } throws RuntimeException("boom")

        worker(maxAttempts = 3).start()

        coVerify(timeout = 2_000, exactly = 1) { jobRepo.markFailed(job.id, any(), requeue = true) }
    }

    @Test
    fun `failed send on the final attempt parks the job as failed`() {
        val job = job(attempts = 2) // next attempt is the 3rd and last
        serveOnce(job)
        coEvery { appointmentRepo.findById(job.appointmentId) } returns appointment()
        coEvery { notificationClient.notify(any()) } throws RuntimeException("boom")

        worker(maxAttempts = 3).start()

        coVerify(timeout = 2_000, exactly = 1) { jobRepo.markFailed(job.id, any(), requeue = false) }
    }

    @Test
    fun `job for a vanished appointment is failed without retry and never notifies`() {
        val job = job()
        serveOnce(job)
        coEvery { appointmentRepo.findById(job.appointmentId) } returns null

        worker().start()

        coVerify(timeout = 2_000, exactly = 1) { jobRepo.markFailed(job.id, any(), requeue = false) }
        coVerify(exactly = 0) { notificationClient.notify(any()) }
    }

    @Test
    fun `an empty queue does no work`() {
        coEvery { jobRepo.claimBatch(any(), any(), any()) } returns emptyList()

        worker().start()

        coVerify(timeout = 1_000, exactly = 0) { notificationClient.notify(any()) }
        coVerify(exactly = 0) { jobRepo.markDone(any()) }
        coVerify(exactly = 0) { jobRepo.markFailed(any(), any(), any()) }
    }

    private fun job(attempts: Int = 0) = ReminderJob(
        id = "job-1",
        appointmentId = "appt-1",
        status = ReminderJobStatus.PROCESSING,
        attempts = attempts,
        scheduledFor = "2020-01-01T00:00:00Z",
        lockedAt = "2020-01-01T00:00:00Z",
        lastError = null,
        createdAt = "2020-01-01T00:00:00Z",
        updatedAt = "2020-01-01T00:00:00Z",
    )

    private fun appointment() = Appointment(
        id = "appt-1",
        title = "Team sync",
        description = "Weekly",
        scheduledAt = "2026-07-01T10:00:00Z",
        durationMinutes = 30,
        attendee = "alice@example.com",
    )
}
