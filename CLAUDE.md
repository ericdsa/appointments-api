# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./gradlew run              # Run the server (port 8080); Flyway migrates on startup
./gradlew test             # Run all tests
./gradlew build            # Compile + test + jar
./gradlew test --tests "com.example.service.AppointmentServiceTest"          # Single class
./gradlew test --tests "com.example.AppointmentRoutesTest.POST appointments creates and GET retrieves it"  # Single test
```

- JDK 17+ required. Integration/repository tests spin up Postgres via **Testcontainers**, so a running Docker daemon is needed for the full suite. Pure unit tests (service, routes, notification client) do not need Docker.
- Local run needs a Postgres at `localhost:5432/appointments` (see README for env-var overrides: `DATABASE_URL`, `API_KEY`, `NOTIFICATION_BASE_URL`, etc.).
- Every request requires the `X-Api-Key` header (default `dev-api-key`). Missing → 401, wrong → 403, enforced in `plugins/Authentication.kt`.

## Architecture

Kotlin + Ktor 3 (Netty) REST API. Layered: **routes → service → repository → Exposed/Postgres**, wired by Koin (`di/AppModule.kt`). Wiring happens in `Application.module()`; `connectDatabase()` runs Flyway then connects Exposed.

### Request flow & error handling
Routes (`routes/AppointmentRoutes.kt`) are thin: they parse input, call the service, and translate the result. The service layer never throws for expected failures — it returns a `ServiceResult` sealed type (`Success` / `NotFound` / `ValidationError` / `ExternalApiError`). Routes map these to HTTP via the `respondError` helper (NotFound→404, ValidationError→400, ExternalApiError→**502**). `plugins/StatusPages.kt` is the catch-all for unexpected throwables (malformed JSON → 400, anything else → 500). When adding a failure mode, add a `ServiceResult` variant and handle it in `respondError` — don't throw from the service.

### Transactions
DB writes go through the injectable `TransactionRunner` (defined in `AppointmentServiceImpl.kt`), not raw `newSuspendedTransaction`. This lets unit tests substitute a pass-through runner with mocked repositories (see `AppointmentServiceTest`). `reschedule` relies on this to delete + re-insert (with a **new** id) atomically in one transaction.

### Reminder queue (durable, not fire-and-forget)
`POST /appointments/reminders` does **not** send notifications inline. It enqueues one row per appointment into the `reminder_jobs` table and returns **202** immediately. A single long-lived `ReminderWorker` coroutine (started on `ApplicationStarted`, on a dedicated `SupervisorJob` scope independent of requests) polls and drains the queue:
- `claimBatch` uses raw SQL `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING ...` — the canonical Postgres job-queue claim. Exposed's DSL can't express `SKIP LOCKED`, so this one statement drops to raw SQL. Multiple worker instances are therefore safe.
- Jobs flow PENDING → PROCESSING → DONE/FAILED; failures requeue (back to PENDING) up to `maxAttempts` (3), then park as FAILED.
- `NotificationClient` itself retries with exponential backoff (3 attempts) per send, independent of the job-level retry.

### Conventions
- Timestamps are stored as ISO-8601 **strings** (`VARCHAR`), not SQL timestamp types — see migrations and `ReminderJob`/`Appointment`.
- Validation lives on the request model (`AppointmentRequest.validate()` throws `IllegalArgumentException`); the service catches it and returns `ValidationError`.
- Repositories expose an interface + an `Exposed*` implementation; bind new ones in `di/AppModule.kt`.
- DB schema changes go in new `src/main/resources/db/migration/V{n}__*.sql` files (Flyway, applied on startup and in tests).
