---
name: otel-setup
description: "Wire OpenTelemetry end to end into an existing Spring Boot 4 Maven project — OTLP export for traces, metrics, and logs, the Logback appender Boot doesn't ship, a local Grafana LGTM backend, and a runbook proving all three signals land. Use when asked to add observability or OpenTelemetry, or when logs never reach the backend."
---

# OpenTelemetry Setup Skill

Takes a Spring Boot 4 app from no telemetry to traces, metrics, and logs landing in a backend you can
open in a browser — and proves it before saying it's done.

`SKILL_DIR` = the directory containing this SKILL.md.

**Load `SKILL_DIR/references/otel-reference.md` before writing anything** — property map, the
appender version matrix, production knobs, and the symptom → cause → fix table.

The starter exports what Micrometer instruments; it does **not** instrument anything itself, and it
does **not** ship a Logback appender. Step 3 is the one everyone skips, and it is why an app can have
a log-export endpoint configured and export zero logs.

---

## Step 0 — Read the project

```bash
grep -m1 -A1 'spring-boot-starter-parent' pom.xml
grep -rn 'opentelemetry\|otlp\|management:' src/main/resources/application.y*ml | head -20
ls src/main/resources/logback-spring.xml compose.yaml 2>/dev/null
grep -rn 'spring-boot-docker-compose\|spring-boot-starter-actuator\|logback-appender' pom.xml
```

Establish the Boot version, what telemetry config already exists, whether a Logback config file is
already in play, and which backend the project has (compose, Testcontainers, or an external
collector).

**Boot 4.0.1 is the floor.** On 4.0.0 the OTLP logging auto-configuration lived in the actuator
module; if the project is on 4.0.0, say so and stop at Step 2 — log export there needs
`spring-boot-starter-actuator` and the fix is a patch upgrade, not a workaround.

---

## Step 1 — Add the starter

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-opentelemetry</artifactId>
</dependency>
```

One dependency, no version — the BOM owns it. Do not add the community
`io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter`; it is a different artifact that
pulls alpha dependencies to do the same job.

Actuator is a separate decision: the starter gives telemetry, Actuator gives `/actuator/health` and
the readiness/liveness probes. Add it too when the app runs in Kubernetes — the reference explains the
split.

---

## Step 2 — Configure the three export paths

```yaml
spring:
  application:
    name: <app-name>          # becomes service.name — without it, Tempo shows unknown_service:java

management:
  otlp:
    metrics:
      export:
        url: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/metrics
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/traces
    logging:
      export:
        otlp:
          endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/logs
  tracing:
    sampling:
      probability: 1.0        # explicit — the default is 0.1 and silently drops 90% of traces
```

Metrics go through Micrometer's registry, traces and logs through the OTel SDK; that is why there are
two prefixes for three signals. The pre-4.1 flat keys (`management.otlp.tracing.*`,
`management.otlp.logging.*`) are deprecated — never generate them.

Skip these endpoint properties entirely when the project uses Docker Compose or Testcontainers
service connections (Step 4) — the connection details win, and a hardcoded endpoint alongside them is
dead config that misleads the next reader.

---

## Step 3 — Wire log export (the part Boot does not ship)

Boot auto-configures an OTLP log **exporter**, but nothing feeds application logs into it. Three
pieces, all required — with only two of them, the endpoint sits there exporting nothing.

**3a. The appender dependency**, version-matched to the OTel API that Boot pins — on Boot 4.1.1
(`opentelemetry.version` 1.62.0) that is `2.28.1-alpha`. Recompute with the matrix commands in
`references/otel-reference.md` for any other Boot version, and pin the match rather than the newest.

```xml
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-logback-appender-1.0</artifactId>
    <version>2.28.1-alpha</version>
</dependency>
```

Every release of this artifact carries `-alpha` — there is no stable line, so that suffix is not a
reason to avoid it or to reach for something older.

**3b. `src/main/resources/logback-spring.xml`:**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/base.xml"/>

    <appender name="OTEL" class="io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender"/>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="OTEL"/>
    </root>
</configuration>
```

Including Boot's `base.xml` is what keeps the console pattern — and its trace/span IDs — intact. Both
appenders on the root logger: console for humans, OTEL for the backend.

**3c. The install bean** — the appender needs the `OpenTelemetry` instance, and only Spring has it:

```java
@Component
class InstallOpenTelemetryAppender implements InitializingBean {

    private final OpenTelemetry openTelemetry;

    InstallOpenTelemetryAppender(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterPropertiesSet() {
        OpenTelemetryAppender.install(this.openTelemetry);
    }
}
```

`InitializingBean` over `@PostConstruct` here for ordering: the appender must not be installed before
the auto-configured `OpenTelemetry` bean exists, or early logs export without trace context.

---

## Step 4 — Give it a backend

**Docker Compose** (`spring-boot-docker-compose` on the classpath) — Boot detects the container and
configures all three endpoints itself:

```yaml
services:
  otel-lgtm:
    image: grafana/otel-lgtm:0.34.0   # pin it; never :latest
    ports:
      - "3000:3000"   # Grafana
      - "4317:4317"   # OTLP gRPC
      - "4318:4318"   # OTLP HTTP
```

**Testcontainers** — `LgtmStackContainer` with `@ServiceConnection` in `TestcontainersConfiguration`,
run via `./mvnw spring-boot:test-run`. Details in the `spring-testing` skill.

**Anything else** — set `OTEL_EXPORTER_OTLP_ENDPOINT` and let Step 2's config read it.

Never gate app startup on the collector: a missing backend produces export warnings, not a failure,
and `depends_on` a 1 GB image makes every `docker compose up` slow for nothing.

---

## Step 5 — Verify all three signals

Configuration that has never been observed working is not done. Run the app, generate traffic, then
check each signal:

```bash
./mvnw spring-boot:run
curl localhost:8080/<an-endpoint>   # a few times, including one that logs
```

| Signal | Where | What proves it |
|---|---|---|
| Traces | Grafana → Explore → **Tempo** → Search, service = `<app-name>` | A trace with spans for the endpoint |
| Metrics | Grafana → Explore → **Prometheus** → `http_server_requests_seconds_count` | A non-zero count for the route |
| Logs | Grafana → Explore → **Loki** → `{service_name="<app-name>"}` | Your log lines, each carrying a trace ID |
| Correlation | Click a log's trace ID | It opens the matching trace in Tempo |

Grafana is on `http://localhost:3000`; under `spring-boot:test-run` the container logs the mapped URL
at startup instead.

The console line proves trace context locally — `[traceId-spanId]` between the thread name and the
logger:

```
2026-08-18T11:30:05.801  INFO 13165 --- [ot] [nio-8080-exec-2] [f64d5e13e35ac042-2d46fc21f9ddfb2d] c.y.HomeController : Greeting user: World
```

Logs in Loki that carry **no** trace ID mean the appender was installed too early — check 3c.

---

## Step 6 — Report

Lead with the verdict, then the board:

```
OTel wired · 3/3 signals verified in Grafana

━━━ OTEL-SETUP ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Starter ............... ✅ spring-boot-starter-opentelemetry (BOM-managed)
Export config ......... ✅ metrics · traces · logs — sampling 1.0 (explicit)
Log appender .......... ✅ 2.28.1-alpha (matched to OTel API 1.62.0)
Backend ............... ✅ grafana/otel-lgtm:0.34.0 via compose
Traces ................ ✅ visible in Tempo, service=job-board
Metrics ............... ✅ http_server_requests_seconds_count = 7
Logs .................. ✅ visible in Loki, trace IDs present
Correlation ........... ✅ log → trace jump works
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Next: set sampling.probability and deployment.environment for prod before deploying.
```

Mark a signal ⚠ if it was configured but not observed, and say why — "Docker unavailable, backend not
started" beats a green board that nobody checked. Never report a signal green from configuration
alone.
