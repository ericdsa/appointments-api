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
    fun `create still succeeds when notification fails after the write`() = runTest {
        val appt = appointment()
        coEvery { tx.execute<Appointment>(any()) } returns appt
        coEvery { notificationClient.notify(any()) } throws RuntimeException("timeout")

        // The row is already persisted; a failed notification must not surface as an
        // error, or the client retries and creates a duplicate.
        assertEquals(ServiceResult.Success(appt), service.create(validRequest()))
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
    fun `update still succeeds when notification fails after the write`() = runTest {
        val appt = appointment()
        coEvery { repo.update(appt.id, any()) } returns appt
        coEvery { notificationClient.notify(any()) } throws RuntimeException("timeout")

        assertEquals(ServiceResult.Success(appt), service.update(appt.id, validRequest()))
    }

    // --- reschedule ---

    @Test
    fun `reschedule returns ValidationError for negative duration`() = runTest {
        val result = service.reschedule("id", request(durationMinutes = -1))
        assertIs<ServiceResult.ValidationError>(result)
    }

    @Test
    fun `reschedule returns NotFound when repo finds no record`() = runTest {
        coEvery { repo.update(any(), any()) } returns null
        assertEquals(ServiceResult.NotFound, service.reschedule("missing", validRequest()))
    }

    @Test
    fun `reschedule updates in place preserving id and sends notification`() = runTest {
        val appt = appointment()
        coEvery { repo.update(appt.id, any()) } returns appt
        coEvery { notificationClient.notify(appt) } just Runs

        val result = service.reschedule(appt.id, validRequest())

        // Same id is returned: reschedule is an in-place update, not a re-create.
        assertEquals(ServiceResult.Success(appt), result)
        coVerify(exactly = 1) { repo.update(appt.id, any()) }
        coVerify(exactly = 1) { notificationClient.notify(appt) }
    }

    @Test
    fun `reschedule still succeeds when notification fails after the write`() = runTest {
        val appt = appointment()
        coEvery { repo.update(appt.id, any()) } returns appt
        coEvery { notificationClient.notify(any()) } throws RuntimeException("network error")

        assertEquals(ServiceResult.Success(appt), service.reschedule(appt.id, validRequest()))
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
    fun `enqueueReminders reports the count from the set-based enqueue and dispatches nothing`() = runTest {
        coEvery { reminderJobRepo.enqueueDueForUpcoming(any()) } returns 3

        val result = service.enqueueReminders()

        assertEquals(ServiceResult.Success(EnqueueSummary(enqueued = 3)), result)
        coVerify(exactly = 1) { reminderJobRepo.enqueueDueForUpcoming(any()) }
        // The service no longer reads the appointments table itself...
        coVerify(exactly = 0) { repo.findAll() }
        // ...and dispatch is the worker's job, not the service's.
        coVerify(exactly = 0) { notificationClient.notify(any()) }
    }

    @Test
    fun `enqueueReminders returns zero when nothing was enqueued`() = runTest {
        coEvery { reminderJobRepo.enqueueDueForUpcoming(any()) } returns 0

        val result = service.enqueueReminders()

        assertEquals(ServiceResult.Success(EnqueueSummary(enqueued = 0)), result)
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
