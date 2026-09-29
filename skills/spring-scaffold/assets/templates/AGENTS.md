# <name>

## What this is
<description>

## What it is NOT
<what_it_is_not>

## Package structure
<groupId>.<name>/
├── <entity1>/
│   ├── <Entity1>            (JPA @Entity)
│   ├── <Entity1>Controller
│   ├── <Entity1>Service
│   ├── <Entity1>Repository
│   └── dto/
├── shared/
│   ├── exception/
│   └── config/
└── <MainClass>Application

## Key domain concepts
<list entities and their definitions>

## How to run
./mvnw spring-boot:test-run   # preferred — starts Postgres + Grafana LGTM via Testcontainers
./mvnw spring-boot:run        # app on :8080, expects Postgres and an OTLP collector already running

## External dependencies
- PostgreSQL 18 (local via Testcontainers)
- Grafana LGTM stack (local via Testcontainers) — OTLP metrics, traces, logs
