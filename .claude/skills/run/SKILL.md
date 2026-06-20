---
name: run
description: Run the appointments-api server locally with Gradle. Use when asked to run, start, or launch the app/server.
---

# Run

Start the Ktor server for this project.

```bash
./gradlew run
```

- Listens on port **8080** by default (override with `PORT`).
- Flyway migrations run automatically on startup.

## Prerequisites

- **JDK 17+**.
- A Postgres reachable at `localhost:5432/appointments`. Override connection
  settings with env vars: `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`.

## Calling the API

Every request requires the `X-Api-Key` header (default `dev-api-key`, override
with `API_KEY`). Missing → 401, wrong → 403.

```bash
curl -H "X-Api-Key: dev-api-key" http://localhost:8080/appointments
```

Other overrides: `NOTIFICATION_BASE_URL` for the external notification service.
