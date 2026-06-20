package com.example.repository

import com.example.TestDatabase
import com.example.db.AppointmentsTable
import com.example.db.ReminderJobsTable
import com.example.external.NotificationClient
import com.example.models.AppointmentRequest
import com.example.models.ReminderJobStatus
import com.example.worker.ReminderWorker
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppointmentRepositoryIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    private lateinit var database: Database
    private lateinit var appointments: ExposedAppointmentRepository
    private lateinit var jobs: ExposedReminderJobRepository

    @BeforeAll
    fun setUp() {
        database = TestDatabase.connect(postgres)
        appointments = ExposedAppointmentRepository(database)
        jobs = ExposedReminderJobRepository(database)
    }

    @BeforeEach
    fun clean() {
        // Tests share one container, so reset state between them. reminder_jobs has
        // an ON DELETE CASCADE FK to appointments, so clear jobs first.
        transaction(database) {
            ReminderJobsTable.deleteAll()
            AppointmentsTable.deleteAll()
        }
    }

    @AfterAll
    fun tearDown() {
        // Container itself is torn down by Testcontainers.
        transaction(database) {
            ReminderJobsTable.deleteAll()
            AppointmentsTable.deleteAll()
        }
    }

    private fun request(scheduledAt: String, attendee: String = "alice@example.com") =
        AppointmentRequest(
            title = "Visit",
            description = "desc",
            scheduledAt = scheduledAt,
            durationMinutes = 30,
            attendee = attendee,
        )

    @Test
    fun `findPage returns ordered slices and count reflects the whole table`() = runBlocking {
        // 25 appointments with increasing scheduled_at so ordering is observable.
        repeat(25) { i ->
            appointments.create(request(scheduledAt = "2026-07-%02dT10:00:00Z".format(i + 1)))
        }

        val firstPage = appointments.findPage(limit = 10, offset = 0)
        val lastPage = appointments.findPage(limit = 10, offset = 20)

        assertEquals(10, firstPage.size)
        assertEquals(5, lastPage.size)
        assertEquals(25, appointments.count())
        // Ascending scheduled_at order across the page boundary.
        assertEquals(firstPage.map { it.scheduledAt }.sorted(), firstPage.map { it.scheduledAt })
        assertTrue(firstPage.last().scheduledAt < lastPage.first().scheduledAt)
    }

    @Test
    fun `claimBatch claims only due pending jobs and never claims them twice`() = runBlocking {
        val appt = appointments.create(request(scheduledAt = "2026-09-01T10:00:00Z"))
        val past = Instant.parse("2020-01-01T00:00:00Z").toString()
        val future = Instant.parse("2999-01-01T00:00:00Z").toString()

        val due1 = jobs.enqueue(appt.id, past)
        val due2 = jobs.enqueue(appt.id, past)
        val notYet = jobs.enqueue(appt.id, future)

        val now = Instant.now().toString()
        val claimed = jobs.claimBatch(now, limit = 10)

        val claimedIds = claimed.map { it.id }.toSet()
        assertEquals(setOf(due1.id, due2.id), claimedIds)
        assertTrue(notYet.id !in claimedIds)
        assertTrue(claimed.all { it.status == ReminderJobStatus.PROCESSING })

        // Already claimed -> a second poll finds nothing new.
        assertTrue(jobs.claimBatch(now, limit = 10).isEmpty())

        jobs.markDone(due1.id)
        assertEquals(ReminderJobStatus.DONE, statusOf(due1.id))
    }

    @Test
    fun `worker drains a due job end to end to DONE`() = runBlocking {
        val appt = appointments.create(request(scheduledAt = "2026-10-01T10:00:00Z"))
        val job = jobs.enqueue(appt.id, Instant.parse("2020-01-01T00:00:00Z").toString())

        val mockEngine = MockEngine {
            respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val notificationClient = NotificationClient(HttpClient(mockEngine), "http://notifications.test")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val worker = ReminderWorker(scope, jobs, appointments, notificationClient, pollMs = 50, batchSize = 10)
        worker.start()
        try {
            withTimeout(5_000) {
                while (statusOf(job.id) != ReminderJobStatus.DONE) delay(50)
            }
        } finally {
            worker.stop()
        }

        assertEquals(ReminderJobStatus.DONE, statusOf(job.id))
    }

    private fun statusOf(jobId: String): ReminderJobStatus = transaction(database) {
        ReminderJobsTable.selectAll()
            .where { ReminderJobsTable.id eq jobId }
            .single()[ReminderJobsTable.status]
            .let { ReminderJobStatus.valueOf(it) }
    }
}
