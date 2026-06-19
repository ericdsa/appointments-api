package com.example.service

import com.example.external.NotificationClient
import com.example.models.Appointment
import com.example.models.AppointmentRequest
import com.example.models.ReminderSummary
import com.example.models.ServiceResult
import com.example.repository.AppointmentRepository
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals

class AppointmentServiceTest {

    @MockK lateinit var repo: AppointmentRepository
    @MockK lateinit var notificationClient: NotificationClient
    @MockK lateinit var tx: TransactionRunner

    private lateinit var service: AppointmentServiceImpl

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
        service = AppointmentServiceImpl(repo, notificationClient, tx)
    }

    // --- getAll ---

    @Test
    fun `getAll returns Success with list from repo`() = runTest {
        val appointments = listOf(appointment())
        coEvery { repo.findAll() } returns appointments
        val result = service.getAll()
        assertEquals(ServiceResult.Success(appointments), result)
    }

    // --- getById ---

    @Test
    fun `getById returns Success when appointment exists`() = runTest {
        val appt = appointment()
        coEvery { repo.findById(appt.id) } returns appt
        assertEquals(ServiceResult.Success(appt), service.getById(appt.id))
    }

    @Test
    fun `getById returns NotFound when appointment is missing`() = runTest {
        coEvery { repo.findById(any()) } returns null
        assertEquals(ServiceResult.NotFound, service.getById("missing"))
    }

    // --- create ---

    @Test
    fun `create returns ValidationError for blank title`() = runTest {
        val result = service.create(request(title = ""))
        assertIs<ServiceResult.ValidationError>(result)
    }

    @Test
    fun `create returns ValidationError for bad scheduledAt`() = runTest {
        val result = service.create(request(scheduledAt = "not-a-date"))
        assertIs<ServiceResult.ValidationError>(result)
    }

    @Test
    fun `create succeeds and sends notification`() = runTest {
        val appt = appointment()
        // tx.execute returns the appointment without running the Exposed DSL block
        coEvery { tx.execute<Appointment>(any()) } returns appt
        coEvery { notificationClient.notify(appt) } just Runs

        val result = service.create(validRequest())

        assertEquals(ServiceResult.Success(appt), result)
        coVerify(exactly = 1) { notificationClient.notify(appt) }
    }

    @Test
    fun `create returns ExternalApiError when notification fails`() = runTest {
        val appt = appointment()
        coEvery { tx.execute<Appointment>(any()) } returns appt
        coEvery { notificationClient.notify(any()) } throws RuntimeException("timeout")

        val result = service.create(validRequest())

        assertIs<ServiceResult.ExternalApiError>(result)
    }

    // --- update ---

    @Test
    fun `update returns ValidationError for blank attendee`() = runTest {
        val result = service.update("id", request(attendee = ""))
        assertIs<ServiceResult.ValidationError>(result)
    }

    @Test
    fun `update returns NotFound when repo finds nothing`() = runTest {
        coEvery { repo.update(any(), any()) } returns null
        assertEquals(ServiceResult.NotFound, service.update("missing", validRequest()))
    }

    @Test
    fun `update returns Success and sends notification`() = runTest {
        val appt = appointment()
        coEvery { repo.update(appt.id, any()) } returns appt
        coEvery { notificationClient.notify(appt) } just Runs

        val result = service.update(appt.id, validRequest())

        assertEquals(ServiceResult.Success(appt), result)
        coVerify(exactly = 1) { notificationClient.notify(appt) }
    }

    @Test
    fun `update returns ExternalApiError when notification fails`() = runTest {
        val appt = appointment()
        coEvery { repo.update(appt.id, any()) } returns appt
        coEvery { notificationClient.notify(any()) } throws RuntimeException("timeout")

        val result = service.update(appt.id, validRequest())

        assertIs<ServiceResult.ExternalApiError>(result)
    }

    // --- reschedule ---

    @Test
    fun `reschedule returns ValidationError for negative duration`() = runTest {
        val result = service.reschedule("id", request(durationMinutes = -1))
        assertIs<ServiceResult.ValidationError>(result)
    }

    @Test
    fun `reschedule returns NotFound when tx block finds no record`() = runTest {
        coEvery { tx.execute<Appointment?>(any()) } returns null
        assertEquals(ServiceResult.NotFound, service.reschedule("missing", validRequest()))
    }

    @Test
    fun `reschedule returns Success and sends notification`() = runTest {
        val appt = appointment()
        coEvery { tx.execute<Appointment?>(any()) } returns appt
        coEvery { notificationClient.notify(appt) } just Runs

        val result = service.reschedule(appt.id, validRequest())

        assertEquals(ServiceResult.Success(appt), result)
        coVerify(exactly = 1) { notificationClient.notify(appt) }
    }

    @Test
    fun `reschedule returns ExternalApiError when notification fails`() = runTest {
        val appt = appointment()
        coEvery { tx.execute<Appointment?>(any()) } returns appt
        coEvery { notificationClient.notify(any()) } throws RuntimeException("network error")

        val result = service.reschedule(appt.id, validRequest())

        assertIs<ServiceResult.ExternalApiError>(result)
    }

    // --- delete ---

    @Test
    fun `delete returns Success when repo deletes the record`() = runTest {
        coEvery { repo.delete("id") } returns true
        assertEquals(ServiceResult.Success(Unit), service.delete("id"))
    }

    @Test
    fun `delete returns NotFound when record does not exist`() = runTest {
        coEvery { repo.delete(any()) } returns false
        assertEquals(ServiceResult.NotFound, service.delete("missing"))
    }

    // --- sendReminders ---

    @Test
    fun `sendReminders notifies every attendee and counts successes`() = runTest {
        val appts = listOf(appointment("a1"), appointment("a2"), appointment("a3"))
        coEvery { repo.findAll() } returns appts
        coEvery { notificationClient.notify(any()) } just Runs

        val result = service.sendReminders()

        assertEquals(ServiceResult.Success(ReminderSummary(total = 3, sent = 3, failed = 0)), result)
        coVerify(exactly = 3) { notificationClient.notify(any()) }
    }

    @Test
    fun `sendReminders counts a failed notification without aborting the rest`() = runTest {
        val ok1 = appointment("a1")
        val bad = appointment("a2")
        val ok2 = appointment("a3")
        coEvery { repo.findAll() } returns listOf(ok1, bad, ok2)
        coEvery { notificationClient.notify(ok1) } just Runs
        coEvery { notificationClient.notify(ok2) } just Runs
        coEvery { notificationClient.notify(bad) } throws RuntimeException("boom")

        val result = service.sendReminders()

        assertEquals(ServiceResult.Success(ReminderSummary(total = 3, sent = 2, failed = 1)), result)
        coVerify(exactly = 1) { notificationClient.notify(ok1) }
        coVerify(exactly = 1) { notificationClient.notify(ok2) }
    }

    @Test
    fun `sendReminders returns an empty summary when there are no appointments`() = runTest {
        coEvery { repo.findAll() } returns emptyList()

        val result = service.sendReminders()

        assertEquals(ServiceResult.Success(ReminderSummary(total = 0, sent = 0, failed = 0)), result)
        coVerify(exactly = 0) { notificationClient.notify(any()) }
    }

    // --- helpers ---

    private fun appointment(id: String = "a1") = Appointment(
        id = id,
        title = "Team sync",
        description = "Weekly",
        scheduledAt = "2026-07-01T10:00:00Z",
        durationMinutes = 30,
        attendee = "alice@example.com",
    )

    private fun validRequest() = request()

    private fun request(
        title: String = "Team sync",
        description: String = "Weekly",
        scheduledAt: String = "2026-07-01T10:00:00Z",
        durationMinutes: Int = 30,
        attendee: String = "alice@example.com",
    ) = AppointmentRequest(title, description, scheduledAt, durationMinutes, attendee)
}
