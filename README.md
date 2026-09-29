# Kimi Spring Boot Skills

Kimi Code CLI skills for building production-ready Spring Boot / Java backends.

This repo ports the engineering-focused skills from `your-javaguy-skills` and re-brands them for Kimi Code CLI. The scope is the code-and-ship loop: scaffolding, persistence, caching, messaging, DevOps, testing, and observability — plus AI engineering with Spring AI.

---

## The skills

| Skill | What it does |
|-------|--------------|
| `spring-scaffold` | Scaffold a convention-compliant Spring Boot 4 project (pom, feature slices, global exception handling, ProblemDetail, Testcontainers, Dockerfile, GitHub Actions CI, project `AGENTS.md`, README). |
| `devops-scaffold` | Add or update `Dockerfile`, `docker-compose.yml`, and GitHub Actions CI for an existing project. |
| `spring-testing` | Write and repair tests — Testcontainers 2.x setup, integration vs unit routing, and the Boot 4 test API (`@MockitoBean`, `MockMvcTester`, `RestTestClient`). |
| `otel-setup` | Wire OpenTelemetry end to end — OTLP export, the Logback appender Boot does not ship, a local Grafana LGTM backend, and a runbook that proves all three signals land. |
| `spring-data-jpa` | Add JPA persistence — entities, repositories, auditing, Flyway migrations, and integration tests. |
| `redis-setup` | Add Redis caching and rate limiting — Jackson 3 JSON serialization, per-cache TTLs, graceful cache failure, Bucket4j rate limiting, and a test that proves the cache intercepts calls. |
| `spring-security` | Add JWT resource-server security — config, claim mapping, method security, and tests. |
| `api-design` | Add OpenAPI/SpringDoc docs, API versioning, and consistent `ProblemDetail` error schemas. |
| `kafka-setup` | Add Kafka producers/consumers — Spring Kafka config, JSON events, DLT handling, and Testcontainers tests. |
| `rabbitmq-setup` | Add RabbitMQ producers/consumers — Spring AMQP config, JSON events, DLX handling, and Testcontainers tests. |
| `security-hardening` | Add DevSecOps hardening — OWASP dependency check, secrets scanning, container scanning, and SBOM. |
| `spring-ai-rag` | Add a RAG pipeline with Spring AI — PgVector with Flyway-owned DDL and HNSW index, Tika ingestion with token-aware chunking, a retrieval advisor with a similarity floor, and an end-to-end proof that answers are grounded in ingested documents. |
| `mcp-server` | Expose a Spring Boot service as an MCP server — streamable HTTP transport, use-case-shaped `@McpTool` tools with description/validation/error discipline, honest read-only/destructive hints, endpoint security, and verification with a real MCP client. |
| `legacy-migration` | Migrate Spring Boot 2.x/3.x apps to Boot 4.x — audit-first, OpenRewrite-led, one green build per hop (2.7 → 3.5 Jakarta jump → 4.0 Jackson 3 + modular starters → 4.1), with a symptom table for the changes that compile but break at runtime. |

---

## Install

Kimi Code CLI discovers skills from these tiers (more specific scopes take priority):

| Scope | Paths scanned |
|-------|---------------|
| Project | `.kimi-code/skills/<name>/SKILL.md` or `.agents/skills/<name>/SKILL.md` |
| User | `~/.kimi-code/skills/<name>/SKILL.md` or `~/.agents/skills/<name>/SKILL.md` |
| Plugin | Skills declared by enabled plugins |

The default user path follows `$KIMI_CODE_HOME` when that variable is set; otherwise it is
`~/.kimi-code`.

### Option A — Plugin install (recommended)

Install this repo as a plugin from a local path or from GitHub, then reload. These are slash
commands inside Kimi Code CLI, not shell commands:

```text
/plugins install /path/to/spring-boot-skills
# or, from GitHub:
/plugins install https://github.com/Dancan254/spring-boot-skills
```

After installation completes, run `/reload` or start a new session (`/new`). The plugin manifest in
`.kimi-plugin/plugin.json` declares `"skills": "./skills/"`, so all skills are registered
automatically.

### Option B — Project-local (try it out)

Clone the repo, then expose the `skills/` directory to Kimi Code CLI's project-level scanner:

```bash
git clone https://github.com/Dancan254/spring-boot-skills.git
cd spring-boot-skills
mkdir -p .kimi-code
ln -s "$PWD/skills" .kimi-code/skills
```

Alternatively copy the skill directories into `.kimi-code/skills/` (or `.agents/skills/`). Then
start Kimi Code CLI from inside the repo.

### Option C — User/global (always available)

Make the skills available from any directory by placing each skill folder directly under a user
skills directory:

```bash
cd /path/to/spring-boot-skills
mkdir -p ~/.kimi-code/skills
for d in skills/*/; do
  ln -s "$PWD/$d" ~/.kimi-code/skills/"$(basename "$d")"
done
```

Or copy instead of symlink:

```bash
cp -r /path/to/spring-boot-skills/skills/* ~/.kimi-code/skills/
```

You can use `~/.agents/skills/` instead of `~/.kimi-code/skills/` if you prefer. Restart or reload
Kimi Code CLI if it was already running.

---

## How to use

Each skill is a directory under `skills/<name>/` containing a `SKILL.md` file and optional `references/`.

Kimi Code CLI loads skills automatically when they are discoverable from the project
(`.kimi-code/skills/` or `.agents/skills/`), from a user skills directory
(`~/.kimi-code/skills/` or `~/.agents/skills/`), or through an enabled plugin. Describe what you
want in plain language; the skill description acts as the trigger. Example:

```
Scaffold a new Spring Boot project called order-service that manages Orders and Customers.
```

The matching skill (`spring-scaffold`) will run and generate the project.

You rarely invoke a skill by name; just describe the outcome you want.

---

## Layout

```
.
├── README.md
├── AGENTS.md
└── skills/
    ├── spring-scaffold/
    │   ├── SKILL.md
    │   └── references/
    │       └── pagination.md
    ├── devops-scaffold/
    │   ├── SKILL.md
    │   └── references/
    │       └── parallel-agent-runs.md
    ├── spring-testing/
    │   ├── SKILL.md
    │   └── references/
    │       ├── containers.md
    │       └── conventions.md
    ├── otel-setup/
    │   ├── SKILL.md
    │   └── references/
    │       └── otel-reference.md
    ├── spring-data-jpa/
    │   ├── SKILL.md
    │   └── references/
    │       └── jpa-conventions.md
    ├── redis-setup/
    │   ├── SKILL.md
    │   └── references/
    │       └── redis-conventions.md
    ├── spring-security/
    │   ├── SKILL.md
    │   └── references/
    │       └── security-conventions.md
    ├── api-design/
    │   ├── SKILL.md
    │   └── references/
    │       └── openapi-conventions.md
    ├── kafka-setup/
    │   ├── SKILL.md
    │   └── references/
    │       └── kafka-conventions.md
    ├── rabbitmq-setup/
    │   ├── SKILL.md
    │   └── references/
    │       └── rabbitmq-conventions.md
    ├── security-hardening/
    │   ├── SKILL.md
    │   └── references/
    │       └── hardening-checklist.md
    ├── spring-ai-rag/
    │   ├── SKILL.md
    │   └── references/
    │       └── rag-reference.md
    ├── mcp-server/
    │   ├── SKILL.md
    │   └── references/
    │       └── mcp-reference.md
    └── legacy-migration/
        ├── SKILL.md
        └── references/
            └── migration-reference.md
```

---

## Version policy

Skills are pinned to **Spring Boot 4.1.1** by default. Other pins (Temurin JDK, PostgreSQL, Grafana LGTM, Testcontainers, GitHub Actions) are current as of the last sweep. Each skill includes the exact `curl` command to re-verify a pin before writing it, so the scaffold never ships a stale default.

Do not override `testcontainers.version` or `opentelemetry.version` by hand — the Spring Boot BOM owns them. If a newer release exists, that is a Boot upgrade, not a property change.

---

## License

MIT
