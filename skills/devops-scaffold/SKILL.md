---
name: devops-scaffold
description: "Add or update Dockerfile, docker-compose.yml, and GitHub Actions CI for an existing Spring Boot Maven project; reads the project first. Use when asked to dockerize a project, add CI or GitHub Actions, or set up DevOps. Not for security scanning — use security-hardening. Not for new projects — use spring-scaffold."
---

# DevOps Scaffold Skill

Reads an existing project and generates or updates:

- `Dockerfile` (multi-stage, non-root, Temurin 25)
- `docker-compose.yml` (app + dependencies with healthchecks)
- `.github/workflows/ci.yml` (test → build, Maven cache, pinned actions)

Never overwrites without showing a diff first if the file already exists.

`SKILL_DIR` = directory containing this SKILL.md file.

For running one or many agents against a project (worktree pooling for fast isolated runs, guardrails
for long autonomous jobs), see `SKILL_DIR/references/parallel-agent-runs.md`. That's workflow guidance,
not project config — don't generate it into the target repo.

**Comments in generated files:** minimal, and only where genuinely needed. Don't narrate what a
Dockerfile stage or CI step obviously does — the instruction already says that. Only comment WHY, when
there's a non-obvious reason (e.g. why KRaft instead of Zookeeper, why a specific health check
command). One short line max — never a multi-line comment block. Most generated files need zero
comments.

---

## Step 0 — Gather inputs

| Field | Required | Notes |
|-------|----------|-------|
| `projectDir` | No | defaults to current directory |
| `extras` | No | additional compose services needed (e.g. `kafka`, `redis`) |

---

## Step 1 — Read the project

```bash
cat pom.xml                          # Java version, Spring Boot version, dependencies
ls src/main/resources/               # application.yml / application.properties
cat docker-compose.yml 2>/dev/null   # existing compose file if any
ls .github/workflows/ 2>/dev/null    # existing CI if any
```

From `pom.xml`, extract:

- Java version (use 25 if not specified)
- Spring Boot version
- Dependencies that imply infrastructure: `postgresql`, `redis`, `kafka`, `rabbitmq`, `mongodb`
- `spring-boot-starter-opentelemetry` → the app exports OTLP and needs somewhere to send it; add the
  Grafana LGTM service (Step 3)

These inferred dependencies drive the compose services.

Also check `application.yml` for how the OTLP endpoint is configured. The house scaffold reads
`${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}`, which the compose app service overrides by
env var. If the endpoints are hardcoded instead, say so in the report rather than editing
`application.yml` — that's app config, not DevOps config.

---

## Step 2 — Generate Dockerfile

**Always multi-stage. Always non-root. Always Temurin.**

```dockerfile
# Build stage
FROM eclipse-temurin:<javaVersion>-jdk-alpine AS builder
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -q
COPY src ./src
RUN ./mvnw package -DskipTests -q

# Runtime stage
FROM eclipse-temurin:<javaVersion>-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

If a Dockerfile already exists, show a diff and ask before overwriting.

---

## Step 3 — Generate docker-compose.yml

Always include the app service. Add infrastructure services based on inferred dependencies.

**App service:**

```yaml
app:
  build: .
  ports:
    - "8080:8080"
  environment:
    SPRING_PROFILES_ACTIVE: docker
    OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-lgtm:4318   # only when the otel-lgtm service is present
  depends_on:
    <dependency>:
      condition: service_healthy
```

The app talks to the collector over the compose network, so the endpoint is the **service name**, not
`localhost`. Don't add `depends_on` for `otel-lgtm` — a missing collector only produces export
warnings, and gating startup on a 1 GB image makes every `docker compose up` slow for no benefit.

**Infrastructure service templates:**

PostgreSQL:

```yaml
postgres:
  image: postgres:18-alpine
  environment:
    POSTGRES_DB: ${DB_NAME:-appdb}
    POSTGRES_USER: ${DB_USER:-app}
    POSTGRES_PASSWORD: ${DB_PASSWORD:-secret}
  ports:
    - "5432:5432"
  volumes:
    - postgres_data:/var/lib/postgresql/data
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER}"]
    interval: 5s
    timeout: 5s
    retries: 5
```

Redis:

```yaml
redis:
  image: redis:8-alpine
  ports:
    - "6379:6379"
  healthcheck:
    test: ["CMD", "redis-cli", "ping"]
    interval: 5s
    timeout: 3s
    retries: 5
```

RabbitMQ:

```yaml
rabbitmq:
  image: rabbitmq:4-management-alpine
  ports:
    - "5672:5672"
    - "15672:15672"
  healthcheck:
    test: ["CMD", "rabbitmq-diagnostics", "ping"]
    interval: 10s
    timeout: 5s
    retries: 5
```

Kafka (KRaft only — ZooKeeper was removed outright in Kafka 4.0):

```yaml
kafka:
  image: apache/kafka:4.3.1
  ports:
    - "9092:9092"
  environment:
    KAFKA_NODE_ID: 1
    KAFKA_PROCESS_ROLES: broker,controller
    KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
    KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
    KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
    KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
    KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
    KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
  healthcheck:
    test: ["CMD-SHELL", "kafka-topics.sh --bootstrap-server localhost:9092 --list"]
    interval: 10s
    timeout: 10s
    retries: 5
```

`KAFKA_CONTROLLER_LISTENER_NAMES` is not optional — a node with `controller` in its process roles
refuses to start without it. Skip the `-rc` tags on Docker Hub; take the newest plain release.

Grafana LGTM (add whenever `spring-boot-starter-opentelemetry` is on the classpath):

```yaml
otel-lgtm:
  image: grafana/otel-lgtm:0.33.1
  ports:
    - "3000:3000"   # Grafana
    - "4317:4317"   # OTLP gRPC
    - "4318:4318"   # OTLP HTTP
  volumes:
    - lgtm_data:/data
```

One container running Loki (logs), Grafana (dashboards), Tempo (traces), and Prometheus (metrics)
behind an OpenTelemetry Collector on 4317/4318. No `healthcheck` block: the image ships its own
`HEALTHCHECK`, and adding one here would just shadow it. Every backend writes under `/data`, so a
single named volume persists the lot.

Grafana is at <http://localhost:3000>, default login `admin` / `admin` — fine for a dev compose file,
never for anything reachable off the machine.

Before writing, confirm `0.33.1` is still the newest tag:

```bash
curl -s "https://hub.docker.com/v2/repositories/grafana/otel-lgtm/tags?page_size=5&ordering=last_updated" \
  | python3 -c "import json,sys; print([t['name'] for t in json.load(sys.stdin)['results']])"
```

Always add named volumes for stateful services:

```yaml
volumes:
  postgres_data:
  lgtm_data:
```

If a docker-compose.yml already exists, merge carefully — add missing services, don't remove existing ones.

**Optional, mention it — don't add it silently:** if the user runs the app from the IDE or
`./mvnw spring-boot:run` against these compose services rather than as a container, the
`spring-boot-docker-compose` dev dependency makes Boot start the compose file itself and auto-detect
services. Boot 4.1 recognises the `grafana/otel-lgtm` image and wires the OTLP metrics, tracing, and
logging endpoints with no properties at all. It's a change to `pom.xml`, so propose it and let the
user decide.

---

## Step 4 — Generate GitHub Actions CI

`.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      - uses: actions/setup-java@v6
        with:
          java-version: '<javaVersion>'
          distribution: 'temurin'
          cache: maven

      - name: Run tests
        run: ./mvnw test

  build:
    needs: test
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      - uses: actions/setup-java@v6
        with:
          java-version: '<javaVersion>'
          distribution: 'temurin'
          cache: maven

      - name: Build JAR
        run: ./mvnw package -DskipTests

      - uses: actions/upload-artifact@v7
        with:
          name: app-jar
          path: target/*.jar
```

Before writing, confirm these are still the newest major tags — action majors move faster than the
images above, and a stale pin stays invisible until a runner deprecates it:

```bash
for r in actions/checkout actions/setup-java actions/upload-artifact; do
  printf '%s: ' "$r"
  curl -s "https://api.github.com/repos/$r/releases/latest" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['tag_name'])"
done
```

Pin the **major** only (`@v7`, not `@v7.0.1`) — still a pin, but it picks up security patches without
a manual bump.

If a CI workflow already exists, show what would change before modifying.

---

## Step 5 — Report

Tell the user:

- Which files were created vs updated
- Which infrastructure services were inferred and why
- Any assumptions made (e.g. "assumed PostgreSQL from spring-data-jpa + postgresql dependency")
- If the LGTM service was added: Grafana on <http://localhost:3000> (`admin`/`admin`), and that the
  app ships traces, metrics, and logs there via `OTEL_EXPORTER_OTLP_ENDPOINT`
- What to do next: `docker compose up -d` to verify
