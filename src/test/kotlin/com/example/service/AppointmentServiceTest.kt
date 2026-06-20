package com.example.service

import com.example.external.NotificationClient
import com.example.models.Appointment
import com.example.models.AppointmentRequest
import com.example.models.EnqueueSummary
import com.example.models.PageRequest
import com.example.models.ServiceResult
import com.example.repository.AppointmentRepository
import com.example.repository.ReminderJobRepository
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals

class AppointmentServiceTest {

    @MockK lateinit var repo: AppointmentRepository
    @MockK lateinit var reminderJobRepo: ReminderJobRepository
    @MockK lateinit var notificationClient: NotificationClient
    @MockK lateinit var tx: TransactionRunner

    private lateinit var service: AppointmentServiceImpl

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
        service = AppointmentServiceImpl(repo, reminderJobRepo, notificationClient, tx)
    }

    // --- getPage ---

    @Test
    fun `getPage returns a populated page with computed totalPages`() = runTest {
        val items = listOf(appointment("a1"), appointment("a2"))
        coEvery { repo.count() } returns 5
        coEvery { repo.findPage(2, 0) } returns items

        val result = service.getPage(PageRequest(page = 1, size = 2))

        val page = assertIs<ServiceResult.Success<com.example.models.Page<Appointment>>>(result).value
        assertEquals(items, page.items)
        assertEquals(5, page.total)
        assertEquals(3, page.totalPages) // ceil(5 / 2)
    }

    @Test
    fun `getPage computes offset from page and size`() = runTest {
        coEvery { repo.count() } returns 25
        coEvery { repo.findPage(10, 20) } returns emptyList()

        service.getPage(PageRequest(page = 3, size = 10))

        coVerify(exactly = 1) { repo.findPage(10, 20) }
    }

    @Test
    fun `getPage returns ValidationError for page below 1`() = runTest {
        assertIs<ServiceResult.ValidationError>(service.getPage(PageRequest(page = 0, size = 10)))
    }

    @Test
    fun `getPage returns ValidationError for size above the max`() = runTest {
        val tooBig = PageRequest.MAX_SIZE + 1
        assertIs<ServiceResult.ValidationError>(service.getPage(PageRequest(page = 1, size = tooBig)))
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

    // --- enqueueReminders ---

    @Test
    fun `enqueueReminders enqueues one job per appointment and counts them`() = runTest {
        val appts = listOf(appointment("a1"), appointment("a2"), appointment("a3"))
        coEvery { repo.findAll() } returns appts
        coEvery { reminderJobRepo.enqueue(any(), any()) } returns mockk()

        val result = service.enqueueReminders()

        assertEquals(ServiceResult.Success(EnqueueSummary(enqueued = 3)), result)
        coVerify(exactly = 1) { reminderJobRepo.enqueue("a1", any()) }
        coVerify(exactly = 1) { reminderJobRepo.enqueue("a2", any()) }
        coVerify(exactly = 1) { reminderJobRepo.enqueue("a3", any()) }
        // Dispatch is the worker's job now, not the service's.
        coVerify(exactly = 0) { notificationClient.notify(any()) }
    }

    @Test
    fun `enqueueReminders returns zero when there are no appointments`() = runTest {
        coEvery { repo.findAll() } returns emptyList()

        val result = service.enqueueReminders()

        assertEquals(ServiceResult.Success(EnqueueSummary(enqueued = 0)), result)
        coVerify(exactly = 0) { reminderJobRepo.enqueue(any(), any()) }
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
