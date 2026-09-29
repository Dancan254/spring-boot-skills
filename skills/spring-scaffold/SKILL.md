---
name: spring-scaffold
description: "Scaffold a new Spring Boot 4 Maven project — feature-sliced packages, ProblemDetail error handling, Testcontainers, Dockerfile, GitHub Actions CI, AGENTS.md, and README. Opinionated: Lombok, PostgreSQL, and OpenTelemetry by default. Use when asked to create, scaffold, or bootstrap a new Spring Boot project. Not for existing projects."
---

# Spring Boot Scaffold Skill

Generates a complete, convention-compliant Spring Boot 4 project in one shot.

`SKILL_DIR` = directory containing this SKILL.md file.

**Comments in generated code:** minimal, and only where genuinely needed. Never comment WHAT the code
does — names should already make that obvious. Only comment WHY, when there's a hidden constraint or
non-obvious reasoning (e.g. why a table needs an explicit name, why a transaction needs REQUIRES_NEW).
One short line max — never a multi-line comment block. Most generated files need zero comments.

**Templates:** every generated file with fixed content lives in `SKILL_DIR/assets/templates/`. Copy
each one to the stated path, substitute the `<placeholders>`, and add the `package` declaration and
imports to Java files. The steps below say which template goes where and why it is shaped that way.

**Pagination:** the scaffold always generates reusable primitives in `shared/pagination/`
(keyset-first). When you wire an actual listing endpoint to them, load `references/pagination.md` in
`SKILL_DIR` for the house pattern (keyset vs offset, the repository/service/controller shape, the
index requirement, and tests).

---

## Step 0 — Gather inputs

Collect from the user's request (ask only for what's missing):

| Field | Required | Example |
|-------|----------|---------|
| `name` | Yes | `job-board` |
| `groupId` | No | `com.example` (default) |
| `description` | Yes | "REST API for job listings and applications" |
| `what_it_is_not` | No | "No frontend. No email. Auth handled upstream." |
| `entities` | No | `["Job", "Application"]` — main domain concepts |
| `extras` | No | additional Spring dependencies (e.g. `kafka`, `redis`, `security`) |
| `outputDir` | No | defaults to `./<name>` under the current directory |
| `lombok` | No | `true` (default) — `false` drops Lombok; entities get explicit constructors and accessors |
| `otel` | No | `true` (default) — `false` drops OpenTelemetry and skips every OTel-only step below |

**Opinionated defaults.** State them to the user before generating: Lombok on entities, PostgreSQL
with Flyway, and OpenTelemetry export with a local Grafana LGTM stack. Lombok and OTel can be switched
off with the flags above. PostgreSQL is not optional in this scaffold — if the user wants another
database or none, say so and stop rather than generating a half-fitting project.

With `otel: false`: drop `opentelemetry` from the Step 1 dependencies, skip Step 4b and Step 5b,
remove the `properties` block from `BaseIntegrationTest` and the OTLP/tracing keys from
`application.yml`, drop the LGTM container from `TestcontainersConfiguration`, and remove the
Grafana/OTel mentions from the AGENTS.md, README, and report.

---

## Step 1 — Bootstrap via Spring Initializr

```bash
curl -s "https://start.spring.io/starter.zip" \
  -d type=maven-project \
  -d language=java \
  -d bootVersion=4.1.1 \
  -d groupId=<groupId> \
  -d artifactId=<name> \
  -d name=<name> \
  -d description="<description>" \
  -d packageName=<groupId>.<name_as_package> \
  -d javaVersion=25 \
  -d dependencies=web,data-jpa,postgresql,validation,actuator,opentelemetry,flyway,testcontainers,lombok \
  -o /tmp/<name>.zip

unzip /tmp/<name>.zip -d <outputDir>
```

Append any `extras` to the `dependencies` param (comma-separated). Remove `lombok` when `lombok: false`
and `opentelemetry` when `otel: false`.

`<name_as_package>` = `name` lowercased with hyphens/spaces stripped (e.g. `job-board` → `jobboard`) —
Java package segments can't contain hyphens. `artifactId`/`name` keep the hyphen; only the package is stripped.

Before running, confirm `4.1.1` is still the latest stable Boot GA and bump if a newer one exists:

```bash
curl -s -H 'Accept: application/json' https://start.spring.io/metadata/client \
  | python3 -c "
import json,sys
vs = [v['id'] for v in json.load(sys.stdin)['bootVersion']['values']]
print([v for v in vs if v.endswith('.RELEASE')])
"
```

**GA only — never scaffold onto a SNAPSHOT, RC, or milestone.** Initializr lists in-flight versions
(`4.1.2.BUILD-SNAPSHOT`) alongside released ones; the filter above keeps only `.RELEASE` entries.
Strip the suffix for the `-d bootVersion=` param — `4.1.1.RELEASE` in the metadata is `4.1.1` on the
request.

### Verify the generated pom before moving on

Boot 4.1 changed what several of these dependency ids resolve to. Open the generated `pom.xml` and
confirm:

| Must be present | Must NOT be present |
|-----------------|---------------------|
| `spring-boot-starter-flyway` (+ `flyway-database-postgresql`) | a bare `org.flywaydb:flyway-core` dependency |
| `spring-boot-starter-opentelemetry` | the community `io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter` |
| `org.testcontainers:testcontainers-postgresql`, `-junit-jupiter`, `-grafana` (test scope) | the unprefixed 1.x coordinates `org.testcontainers:postgresql` / `:junit-jupiter` / `:grafana` |
| `spring-boot-starter-*-test` slices (`-webmvc-test`, `-data-jpa-test`, `-flyway-test`, `-opentelemetry-test`) | a direct `spring-boot-starter-test` declaration (the slices pull it in transitively in 4.1) |

Flyway is wired through the **Boot starter**, never `flyway-core` directly — the starter is what brings
in Boot's Flyway auto-configuration and the matching `flyway-database-postgresql` module for the
selected database. If a newer Flyway is needed, override `flyway.version` in `<properties>`; never
add `flyway-core` by hand.

`testcontainers-grafana` arrives because `opentelemetry` is selected — it's the Grafana **LGTM** stack
container, wired up in Step 4b.

Every Testcontainers module carries a `testcontainers-` prefix since 2.0. The old unprefixed
coordinates still resolve — they just stop at 1.21.4 forever, which is how a project ends up silently
a major version behind. Boot 4.1's BOM pins Testcontainers **2.0.5**; never override
`testcontainers.version` by hand. The `spring-testing` skill's `SKILL_DIR/../spring-testing/references/containers.md` holds the
full 1.x → 2.x coordinate map and the commands that verify it.

---

## Step 2 — Restructure packages into feature slices

Delete the default generated main package contents (keep only the main class). Leave `src/test/java/`
alone for now — `TestcontainersConfiguration` and `Test<MainClass>Application` are kept and edited in
Step 4b.

Create the following structure under `src/main/java/<groupId>/<name>/`:

```
<name>/
├── <entity1>/              # one package per domain entity
│   ├── <Entity1>.java             (JPA @Entity — Lombok-annotated unless `lombok: false`)
│   ├── <Entity1>Controller.java
│   ├── <Entity1>Service.java
│   ├── <Entity1>Repository.java   (extends ListCrudRepository; JpaRepository if it paginates)
│   └── dto/
│       ├── <Entity1>Request.java   (record + validation annotations)
│       └── <Entity1>Response.java  (record)
├── shared/
│   ├── exception/
│   │   ├── ErrorKind.java              (transport-neutral failure categories)
│   │   ├── BaseException.java          (carries kind + title; every domain exception extends it)
│   │   ├── ResourceNotFoundException.java
│   │   ├── InvalidCursorException.java
│   │   └── GlobalExceptionHandler.java
│   ├── pagination/        # reusable paging primitives (keyset-first) — always generated
│   │   ├── CursorPage.java        (keyset response record)
│   │   ├── PagedResponse.java     (offset response record — fallback)
│   │   └── PageCursor.java        (opaque cursor codec)
│   └── config/            # cross-cutting @Configuration — starts empty, add only when needed
└── <MainClass>Application.java
```

If no entities given, create a `shared/` package only and leave feature slices for the user.

---

## Step 3 — Write the shared files

**The rule:** one `@ExceptionHandler` for the whole domain hierarchy, never one per concrete
exception. A new exception must be handled correctly by virtue of extending `BaseException` — nothing
else to remember. Per-type handler methods mean a forgotten registration turns an ordinary business
error into a 500 that compiles, passes tests, and only shows up in production.

| Template | Written to `src/main/java/<package>/shared/exception/` |
|----------|------------------|
| `java/ErrorKind.java` | `ErrorKind.java` |
| `java/BaseException.java` | `BaseException.java` |
| `java/ResourceNotFoundException.java` | `ResourceNotFoundException.java` |
| `java/InvalidCursorException.java` | `InvalidCursorException.java` |
| `java/GlobalExceptionHandler.java` | `GlobalExceptionHandler.java` |

### `ErrorKind`

Failure categories in domain terms, not HTTP terms — services throw these without knowing they're
being called over HTTP, and the same exception still means something from a Kafka consumer or a
scheduled job. `GlobalExceptionHandler` is the only place that translates a kind into a status code.
Add constants as the domain needs them; a kind that genuinely doesn't fit gets its own handler method
(see the escape hatch below).

### `GlobalExceptionHandler`

Uses `ProblemDetail` (RFC 9457) — no custom error POJOs.

Notes on the shape, so it doesn't get "improved" back:

- **`extends ResponseEntityExceptionHandler` is load-bearing.** Without it, the catch-all
  `@ExceptionHandler(Exception.class)` wins over Spring's own exception handling —
  `ExceptionHandlerExceptionResolver` runs before `DefaultHandlerExceptionResolver` — and malformed
  JSON returns 500 instead of 400, an unknown URL 500 instead of 404, a wrong method 500 instead of
  405, each with a spurious ERROR log line. The base class declares `@ExceptionHandler` for 18
  specific Spring MVC types, which beat `Exception.class` on specificity and each get their correct
  status as a `ProblemDetail`.
- Validation is an **`@Override`**, not a second `@ExceptionHandler`. Declaring both a standalone
  `@ExceptionHandler(MethodArgumentNotValidException.class)` and inheriting the base class's mapping
  is an ambiguous mapping and fails at startup. The override also brings
  `HandlerMethodValidationException` — what Boot 4 throws for `@Valid` on `@RequestParam` and
  `@PathVariable` — along for free.
- `handleExceptionInternal` is what returns the body, so async dispatch and the base class's response
  bookkeeping stay intact. Don't hand-roll `ResponseEntity.status(status).body(pd)`.
- Do not set `spring.mvc.problemdetails.enabled` alongside this. Boot's own
  `ProblemDetailsExceptionHandler` is `@ConditionalOnMissingBean(ResponseEntityExceptionHandler.class)`,
  so it already backs off — the property is a no-op here and only confuses the next reader.
- `handleGeneric` returns a **fixed** detail string and logs the real exception. Never put
  `ex.getMessage()` in the response for an unexpected failure — that's how SQL fragments, constraint
  names, and internal paths reach the client.
- Explicit SLF4J `Logger`, not Lombok's `@Slf4j` — house rule is no Lombok on controller-layer classes.
- The `switch` is exhaustive over the enum with no `default`, so adding an `ErrorKind` constant without
  mapping it is a compile error rather than a runtime surprise.
- **Escape hatch:** Spring dispatches to the most specific `@ExceptionHandler`, so a single exception
  that needs a bespoke response (extra `ProblemDetail` properties, a non-standard status) can still get
  its own method without disturbing this one.

---

## Step 3b — Write the pagination primitives (`shared/pagination/`)

Always generate these three files — they're generic and cost nothing unused. Keyset is the default
style; `PagedResponse` is the offset fallback. See `references/pagination.md` for how to wire an
endpoint to them.

| Template | Written to `src/main/java/<package>/shared/pagination/` |
|----------|------------------|
| `java/CursorPage.java` | `CursorPage.java` |
| `java/PagedResponse.java` | `PagedResponse.java` |
| `java/PageCursor.java` | `PageCursor.java` |

`ScrollPosition`, `Window`, and `KeysetScrollPosition` are `org.springframework.data.domain.*` —
first-class keyset scrolling, no third-party pagination library needed.

---

## Step 4 — Write the Testcontainers base class

Write `assets/templates/java/BaseIntegrationTest.java` to `src/test/java/<package>/BaseIntegrationTest.java`.

Before writing the file, confirm `18-alpine` is still the current Postgres major:

```bash
curl -s "https://hub.docker.com/v2/repositories/library/postgres/tags/18-alpine" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['name'], d['last_updated'][:10])"
```

`@Testcontainers` is required — it's the JUnit 5 extension that actually starts the static
`@Container` before the test run; `@ServiceConnection` only wires the connection details, it does
not start anything. All integration tests extend this class.

The `properties` disable OTLP export so tests don't spam connection warnings against a collector that
isn't running, and so no test pays for starting the LGTM image. These are the Boot 4.1 keys, verified
against the 4.1.1 configuration metadata — the pre-4.1 spellings `management.otlp.tracing.export.enabled`
and `management.otlp.logging.export.enabled` are deprecated and must not be used. Observability is
exercised in dev via `spring-boot:test-run` (Step 4b), not in the integration test suite.

Testcontainers 2.x shapes: `PostgreSQLContainer` comes from `org.testcontainers.postgresql` (the
`org.testcontainers.containers.*` classes are deprecated shims), the container classes are no longer
self-generic — no `<?>` — and they take a `DockerImageName`, never a raw `String`.

This is the whole testing surface the scaffold generates: one base class, one container. Everything
past it — Redis/Kafka/RabbitMQ/LocalStack containers, unit vs integration routing, the Boot 4 test
API (`@MockitoBean`, `MockMvcTester`, `RestTestClient`), and writing the tests themselves — belongs
to the **`spring-testing`** skill. Point the user there in the Step 10 report rather than growing
this step.

---

## Step 4b — Wire the Grafana LGTM stack for local dev

Initializr generates `TestcontainersConfiguration` and `Test<MainClass>Application` in `src/test/java/`.
**Keep both** — they're how `./mvnw spring-boot:test-run` gives you Postgres plus a full observability
backend with zero config. Pin the image tags in `TestcontainersConfiguration` (Initializr writes
`:latest`, which violates the pin rule) by replacing the generated file's body with
`assets/templates/java/TestcontainersConfiguration.java`.

`LgtmStackContainer` (`org.testcontainers.grafana`) runs the whole Grafana LGTM stack in one
container — Loki (logs), Grafana (dashboards), Tempo (traces), and Prometheus (metrics, standing in
for Mimir), fronted by an OpenTelemetry Collector on 4317/4318. Boot 4.1 ships three
connection-details factories for it
(`GrafanaOpenTelemetryMetricsContainerConnectionDetailsFactory`, the tracing equivalent, and
`GrafanaOtlpLoggingContainerConnectionDetailsFactory`), so a single `@ServiceConnection` points
metrics, traces, **and** logs at the container's mapped OTLP port. Do not set any
`management.*.otlp.*` endpoint property in a dev profile — the service connection wins and hardcoding
one breaks it.

Before writing the file, confirm `0.34.0` is still the newest `grafana/otel-lgtm` tag:

```bash
curl -s "https://hub.docker.com/v2/repositories/grafana/otel-lgtm/tags?page_size=5&ordering=last_updated" \
  | python3 -c "import json,sys; print([t['name'] for t in json.load(sys.stdin)['results']])"
```

Run it with:

```bash
./mvnw spring-boot:test-run
```

The container logs `Access to the Grafana dashboard: http://localhost:<mapped-port>` on startup —
open that for live traces, metrics, and logs from the running app. The image is ~1 GB on first pull.

This container is deliberately **not** in `BaseIntegrationTest` — integration tests assert behaviour,
not telemetry, and shouldn't pay a 1 GB image start.

---

## Step 5 — Write `application.yml`

Replace the generated `application.properties` with `assets/templates/application.yml`, substituting
`<name>`.

The OTLP endpoints default to a local collector (`localhost:4318`) and are overridable via the
`OTEL_EXPORTER_OTLP_ENDPOINT` env var — point it at the LGTM stack, a standalone collector, or a
vendor. With nothing listening the app still starts; the exporters just log periodic connection
warnings, so this is safe as a default. `spring-boot-starter-opentelemetry` exports what Micrometer
already instruments (HTTP, JDBC, and logs carrying trace/span IDs).

**Three prefixes, three export paths — these are the Boot 4.1 spellings:**

| Signal | Property | Path |
|--------|----------|------|
| Metrics | `management.otlp.metrics.export.url` | Micrometer `OtlpMeterRegistry` |
| Traces | `management.opentelemetry.tracing.export.otlp.endpoint` | OTel SDK |
| Logs | `management.opentelemetry.logging.export.otlp.endpoint` | OTel SDK |

The flat `management.otlp.tracing.*` and `management.otlp.logging.*` keys are deprecated in 4.1 —
never generate them. To switch a signal off entirely: `management.otlp.metrics.export.enabled`,
`management.tracing.export.enabled`, `management.logging.export.otlp.enabled`.

`ddl-auto: validate` does not create tables — it only checks that entity mappings match an existing
schema. **Flyway** owns the schema: the `flyway` dependency in Step 1 resolves to
`spring-boot-starter-flyway` on Boot 4.1 and, because Postgres is selected, adds
`flyway-database-postgresql`. Flyway runs migrations on startup; Hibernate then validates the entities
against the resulting tables. Never add `org.flywaydb:flyway-core` directly — the starter owns that
transitively along with the auto-configuration.

### Initial migration

Write `src/main/resources/db/migration/V1__init.sql` with `CREATE TABLE` DDL matching each entity's
fields (types, `NOT NULL`, PK, unique constraints). This is what makes `validate` pass and the app
start. If no entities were given, create the empty `db/migration/` directory and leave the first
migration to the user.

---

## Step 5b — Wire OTLP log export

The `logging.export.otlp.endpoint` above configures an exporter, but Boot ships **no Logback
appender** — without it the log endpoint exports nothing while traces and metrics work fine.

Apply **Step 3 (3a–3c) of the `otel-setup` skill** (`SKILL_DIR/../otel-setup/SKILL.md`): the
version-matched `opentelemetry-logback-appender-1.0` dependency, `logback-spring.xml`, and the
`InstallOpenTelemetryAppender` bean in `shared/config/`. That skill owns the version-matching rule, so
the scaffold never carries a second copy of the pin.

Anything past this — resource attributes, vendor auth headers, `@Observed`, production sampling, and
the runbook that proves all three signals actually land in Grafana — belongs to the **`otel-setup`**
skill.

---

## Step 6 — Write Dockerfile and CI

Apply **Steps 2 and 4 of the `devops-scaffold` skill** (`SKILL_DIR/../devops-scaffold/SKILL.md`) with
`javaVersion` = 25: the multi-stage, non-root Temurin `Dockerfile` and `.github/workflows/ci.yml`
(test → build, Maven cache, pinned action majors). Run its action-tag check before writing. Skip its
compose step — `spring-boot:test-run` covers local infrastructure for a fresh scaffold; the user can
run `devops-scaffold` later when they want a `docker-compose.yml`.

---

## Step 7 — Write project AGENTS.md

Write `assets/templates/AGENTS.md` to the project root, filling in every `<placeholder>`.

---

## Step 8 — Write architecture decision records (docs/adr/)

Record *why* the project is shaped the way it is, so decisions live next to the code and don't rot in
a wiki. Each ADR is one short [MADR](https://adr.github.io/madr/)-style file. Generate two files.

- `assets/templates/docs/adr/0001-package-by-feature-monolith.md` → `docs/adr/0001-package-by-feature-monolith.md`,
  pre-written to reflect the actual scaffold decision. Fill `<today>` with the current date.
- `assets/templates/docs/adr/template.md` → `docs/adr/template.md`, a blank skeleton so every later ADR
  stays consistent.

---

## Step 9 — Write README.md

`AGENTS.md` (Step 7) is for AI context — implementation gaps, package layout, dev detail. `README.md`
is the human-facing front door: what a hiring manager, collaborator, or video viewer sees first on
GitHub. Keep them distinct — don't just duplicate AGENTS.md's content into README.md.

The section order below is deliberate — problem → mental model → run it → understand it → decisions —
so the README also reads as the outline for a teaching video.

Write `assets/templates/README.md` to the project root.

Fill in the real entity names everywhere before writing the file — substitute `<Entity>`,
`<entity-plural>`, and the API DTO fields with the actual values from Step 2/3. Don't leave any
`<entity>`/`<entity-plural>` literal placeholders in the written file. If there are multiple entities,
draw all of them as sibling controller→service→repository lanes in Diagram 1, and pick one entity for
the Diagram 2 sequence. Label every arrow with what flows across it, and keep each diagram to one
concern — component structure in Diagram 1, request timing in Diagram 2.

---

## Step 10 — Report

Tell the user:

- Full path to the generated project
- Package structure created
- That `docs/adr/` seeds the first decision record, and the README ships with two Mermaid diagrams
  that render on GitHub
- That `./mvnw spring-boot:test-run` boots the app with Postgres and the Grafana LGTM stack attached,
  and the Grafana URL is printed in the startup logs
- What to do next: add domain logic to feature slices, run `./mvnw test` to verify Testcontainers works
- That log export depends on the Logback appender wired in Step 5b — traces and metrics work without it,
  logs do not
- That the **`otel-setup`** skill verifies all three signals actually land in Grafana, and owns
  resource attributes, vendor auth, and production sampling
- That the **`spring-testing`** skill takes over from here — it writes the actual tests, adds any
  container beyond Postgres, and audits the suite against the Boot 4 test API
