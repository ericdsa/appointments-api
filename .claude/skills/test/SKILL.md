---
name: test
description: Run the appointments-api test suite with Gradle, including single-test runs. Use when asked to run or check tests.
---

# Test

Run tests for this project.

```bash
./gradlew test                 # All tests
```

Run a single class or a single test:

```bash
./gradlew test --tests "com.example.service.AppointmentServiceTest"
./gradlew test --tests "com.example.AppointmentRoutesTest.POST appointments creates and GET retrieves it"
```

## Requirements

- **JDK 17+**.
- Integration and repository tests spin up Postgres via **Testcontainers**, so a
  running **Docker daemon** is required for the full suite.
- Pure unit tests (service, routes, notification client) do not need Docker.
