---
name: build
description: Build the appointments-api project with Gradle (compile, test, jar). Use when asked to build or package the app.
---

# Build

Compile, test, and package the project.

```bash
./gradlew build
```

This runs the test suite as part of the build, so the same requirements apply:

- **JDK 17+**.
- A running **Docker daemon** (integration/repository tests use Testcontainers
  Postgres). To package without running tests, use `./gradlew build -x test`.

This produces a thin jar under `build/libs/` (`appointments-api-0.0.1.jar`)
without bundled dependencies — it won't run via `java -jar` on its own. To run
the app, use `./gradlew run`; for a standalone runnable artifact, build the fat
jar with `./gradlew buildFatJar` (output `build/libs/*-all.jar`).
