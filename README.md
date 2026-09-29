# Spring Boot Skills

Agent skills for building production-ready Spring Boot 4 / Java backends. They work in Claude Code,
Codex, and Kimi Code CLI.

The scope is the code-and-ship loop: scaffolding, persistence, caching, messaging, DevOps, testing,
and observability, plus AI engineering with Spring AI. Every skill is a plain `SKILL.md` folder under
`skills/`. The three tools share that folder; only the thin manifests differ.

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

**Build tool:** the skills target Maven projects (`pom.xml`, `./mvnw`). `spring-testing` and
`legacy-migration` also handle Gradle; the rest do not yet.

---

## Install

### Claude Code

Install as a plugin from this repo's marketplace. Inside a session:

```text
/plugin marketplace add Dancan254/spring-boot-skills
/plugin install spring-boot-skills@dancan254
```

Or from your shell: `claude plugin marketplace add Dancan254/spring-boot-skills`, then
`claude plugin install spring-boot-skills@dancan254`. Skills are namespaced under the plugin, e.g.
`spring-boot-skills:spring-scaffold`. Run `/reload-plugins` if a session was already open.

Without the plugin, copy or symlink the skill folders into `~/.claude/skills/` (user) or
`.claude/skills/` (project).

### Codex

Codex scans `.agents/skills/` in every directory from the working directory up to the repo root,
and `~/.agents/skills/` for the user. Symlink each skill folder into the user directory:

```bash
cd /path/to/spring-boot-skills
mkdir -p ~/.agents/skills
for d in skills/*/; do
  ln -s "$PWD/$d" ~/.agents/skills/"$(basename "$d")"
done
```

The repo root also carries a portable `plugin.json` (Agent Plugins 1.0.0 schema) for plugin-based
distribution.

### Kimi Code CLI

Install as a plugin. These are slash commands inside Kimi Code CLI:

```text
/plugins install https://github.com/Dancan254/spring-boot-skills
```

Then `/reload` or start a new session (`/new`). `.kimi-plugin/plugin.json` declares
`"skills": "./skills/"`, so every skill registers automatically.

Kimi also scans `.kimi-code/skills/` or `.agents/skills/` in the project, and `~/.kimi-code/skills/`
or `~/.agents/skills/` for the user (`$KIMI_CODE_HOME` overrides `~/.kimi-code`). The Codex symlink
loop above into `~/.agents/skills/` covers both Codex and Kimi at once.

---

## How to use

Describe the outcome you want in plain language; each skill's `description` is its trigger:

```
Scaffold a new Spring Boot project called order-service that manages Orders and Customers.
```

The matching skill (`spring-scaffold`) runs and generates the project. You rarely invoke a skill by
name.

---

## Layout

```
.
├── .claude-plugin/        # Claude Code plugin + marketplace manifests
├── .kimi-plugin/          # Kimi Code CLI plugin manifest
├── plugin.json            # Portable Agent Plugins manifest (Codex)
├── AGENTS.md              # Contributor notes (CLAUDE.md imports it)
├── CHANGELOG.md
├── README.md
└── skills/
    ├── spring-scaffold/
    │   ├── SKILL.md
    │   ├── assets/
    │   │   └── templates/     # Java, YAML, and doc templates the scaffold copies
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
