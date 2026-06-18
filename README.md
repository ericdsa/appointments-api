# Appointments API

A REST API for managing appointments, built with Kotlin, Ktor, and PostgreSQL.

## Stack

- **Kotlin** + **Ktor 3** (Netty engine)
- **Exposed** — SQL DSL / ORM
- **HikariCP** — connection pooling
- **Flyway** — database migrations
- **Koin** — dependency injection
- **kotlinx.serialization** — JSON

## Prerequisites

- JDK 17+
- PostgreSQL running locally (default: `localhost:5432/appointments`)

## Configuration

All settings can be overridden with environment variables:

| Env var | Default | Description |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/appointments` | JDBC URL |
| `DATABASE_USER` | `edsa` | DB username |
| `DATABASE_PASSWORD` | *(empty)* | DB password |
| `API_KEY` | `dev-api-key` | API key for auth |

## Running

```bash
./gradlew run
```

Flyway migrations run automatically on startup. The server listens on port 8080 by default.

## Authentication

Every request must include the `X-Api-Key` header:

```
X-Api-Key: dev-api-key
```

Missing or invalid keys return `401` / `403`.

## API

All endpoints are under `/appointments`.

### List all appointments

```
GET /appointments
```

### Get an appointment

```
GET /appointments/{id}
```

### Create an appointment

```
POST /appointments
Content-Type: application/json

{
  "title": "Team sync",
  "description": "Weekly team standup",
  "scheduledAt": "2026-06-20T10:00:00Z",
  "durationMinutes": 30,
  "attendee": "alice@example.com"
}
```

Returns `201 Created` with the new appointment (including its generated `id`).

### Update an appointment

```
PUT /appointments/{id}
Content-Type: application/json

{ ...same body as POST... }
```

### Delete an appointment

```
DELETE /appointments/{id}
```

Returns `204 No Content` on success.

### Appointment fields

| Field | Type | Notes |
|---|---|---|
| `title` | string | Required, non-blank |
| `description` | string | |
| `scheduledAt` | string | ISO 8601 instant, e.g. `2026-06-20T10:00:00Z` |
| `durationMinutes` | int | Must be > 0 |
| `attendee` | string | Required, non-blank |

## Testing

```bash
./gradlew test
```

## Project structure

```
src/main/kotlin/com/example/
├── Application.kt          # Entry point, plugin wiring
├── db/                     # Database connection + table definitions
├── di/                     # Koin module
├── models/                 # Appointment data classes + validation
├── plugins/                # Ktor plugins (auth, routing, serialization, status pages)
├── repository/             # AppointmentRepository interface + Exposed implementation
└── routes/                 # Route definitions

src/main/resources/
├── application.conf        # HOCON config
└── db/migration/           # Flyway SQL migrations
```
