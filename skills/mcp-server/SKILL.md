---
name: mcp-server
description: "Expose an existing Spring Boot 4 Maven service as an MCP server with Spring AI — transport choice, @McpTool tools, tool hints, endpoint security, and verification with a real MCP client. Use when asked to add MCP, expose tools to an LLM or agent, or let an AI call my backend. Not for RAG — use spring-ai-rag."
---

# MCP Server Skill

Takes an existing Spring Boot 4 web service and exposes its use cases as MCP tools an LLM agent can
discover and call — and proves it with a real MCP client before saying it's done.

`SKILL_DIR` = the directory containing this SKILL.md.

**Load `SKILL_DIR/references/mcp-reference.md` before writing anything** — version and artifact
matrix, property map, tool API details, the error contract, test setup, symptom → cause → fix.

Two rules frame everything below:

- **A tool is a prompt-driven public API.** The model selects tools by name and description alone
  and calls them with arguments it generates. Vague descriptions get mis-selected; unvalidated
  arguments get abused; raw exceptions leave the model blind. Steps 4–5 earn this skill its keep.
- **Never expose CRUD-by-default.** One deliberate tool per use case, shaped like the use case
  (`search_open_jobs`), not like the repository (`create_job`, `delete_job`).

---

## Step 0 — Gather inputs

| Field | Required | Notes |
|-------|----------|-------|
| `transport` | No | `http` (default — the app already runs as a web service) or `stdio` |
| `serverName` | No | default: `<app-name>` from `pom.xml` — clients display it |
| `useCases` | Yes | the 2–5 operations to expose, in user language ("search jobs by keyword", "submit an application") — NOT "all endpoints" |
| `auth` | No | `true` (default when a resource server already exists) — secure the MCP endpoint |

If the answer to `useCases` is "everything the API does", push back — that answer is how
CRUD-by-default happens.

---

## Step 1 — Read the project

```bash
grep -m1 -A2 'spring-boot-starter-parent' pom.xml
grep -n 'spring-ai\|spring-boot-starter-web\|spring-boot-starter-webflux\|oauth2-resource-server' pom.xml
ls src/main/java/<base-package>/
grep -rn 'SecurityFilterChain' src/main/java --include='*.java' -l
find src/test/java -name 'BaseIntegrationTest.java'
```

Establish: Boot version, servlet vs reactive stack (decides the starter), which domain services the
`useCases` map to, whether a security config exists (Step 6), whether `BaseIntegrationTest` exists
(Step 7).

**Spring AI 2.0.x supports Spring Boot 4.0.x and 4.1.x only.** On Boot 3.x, stop — the 1.x line has
different artifact names, packages, and properties everywhere, and this skill does not generate it.

---

## Step 2 — Verify pins, add BOM and starter

Artifact coordinates changed across the Spring AI milestones and GA — the 1.x names
(`spring-ai-mcp-server-webmvc-spring-boot-starter`) are dead, and the community
`org.springaicommunity:mcp-annotations` moved into Spring AI proper. Never write one from memory
(as of this writing: `2.0.1`):

```bash
# current stable Spring AI (excludes milestones/RCs); artifact-existence check in the reference
curl -s "https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/maven-metadata.xml" \
  | grep -o '<version>[^<]*' | sed 's/<version>//' | grep -v '\-M\|\-RC' | tail -1
```

Import the BOM in `dependencyManagement` if the project doesn't have it yet, then one starter — no
version, the BOM owns it:

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-bom</artifactId>
    <version>2.0.1</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
    <!-- or -webflux on a reactive stack — match the app's existing web starter, never mix -->
</dependency>
```

The starter pulls the MCP Java SDK transitively (`io.modelcontextprotocol.sdk:mcp-core`, BOM-managed
— 2.0.0 at Spring AI 2.0.1). **Never override the SDK version by hand** — Spring AI and the SDK move
in lockstep, and a hand override is the classic version-skew break.

**STDIO branch** (only when the deliverable is a CLI-side tool a host like Claude Desktop spawns —
not for a running web service): use `spring-ai-starter-mcp-server` and read the STDIO section of the
reference first. A STDIO server must not log to stdout — one `System.out.println` or console log
line corrupts the JSON-RPC stream.

---

## Step 3 — application.yml

```yaml
spring:
  ai:
    mcp:
      server:
        name: ${MCP_SERVER_NAME:<app-name>}
        version: ${MCP_SERVER_VERSION:0.1.0}
        instructions: >
          Tools for <what this service does, one sentence>. <Any routing hint a model needs,
          e.g. "search before you create — duplicates are rejected">.
        protocol: streamable            # explicit for visibility — also the default; SSE is legacy
```

`${ENV_VAR:default}` pattern, as elsewhere in the repo. The full property map is in
`references/mcp-reference.md`; defaults are sane — set only to deviate. `instructions` is not
decoration: clients inject it into the model's context, and it is the only server-level guidance the
model gets. Write it like a system prompt for tool selection.

---

## Step 4 — Expose tools

Create a dedicated tools class per domain that **wraps** the existing service — never annotate the
domain service itself. The wrapper owns the MCP concerns: names, descriptions, validation, error
translation. The domain service stays unaware of MCP.

`src/main/java/<package>/job/JobMcpTools.java`:

```java
package <package>.job;

@Component
public class JobMcpTools {

    private final JobService jobService;

    public JobMcpTools(JobService jobService) {
        this.jobService = jobService;
    }

    @McpTool(
        name = "search_open_jobs",
        description = "Search currently open job postings by keyword and optional location. "
            + "Returns at most 20 matches with id, title, company, location, and salary range. "
            + "Use this before recommending or applying to any job.",
        annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true))
    public List<JobSummary> searchOpenJobs(
            @McpToolParam(description = "Keywords to match against title and description, e.g. 'senior java backend'", required = true) String query,
            @McpToolParam(description = "City or 'remote'; omit for any location", required = false) String location) {
        return jobService.searchOpen(query, location);
    }

    @McpTool(
        name = "apply_to_job",
        description = "Submit an application for a candidate to a specific open job. "
            + "Requires the job id from search_open_jobs and the candidate's email. "
            + "Applying twice to the same job returns the existing application instead of failing.",
        annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true))
    public ApplicationResult applyToJob(
            @McpToolParam(description = "Job id from search_open_jobs", required = true) long jobId,
            @McpToolParam(description = "Candidate email address", required = true) String candidateEmail) {
        if (candidateEmail == null || !candidateEmail.contains("@")) {
            throw new IllegalArgumentException("candidateEmail must be a valid email address");
        }
        return jobService.apply(jobId, candidateEmail);
    }
}
```

Imports: `org.springframework.ai.mcp.annotation.{McpTool, McpToolParam}`,
`org.springframework.stereotype.Component`.

Why this shape: the starter scans beans for `@McpTool` and registers them (no config class); the
wrapper returns DTOs, never entities — results land in the model's context, and entities leak
lazy-loading explosions and internal fields. The `@Tool` + `ToolCallbackProvider` path also exists
(the auto-config picks up `ToolCallback` beans) — use it only when a callback is shared with a
`ChatClient` in the same app; it has no MCP hint annotations (details in the reference).

---

## Step 5 — Tool design discipline

**Name and description are the model's only selection signal.** There is no type system guiding the
LLM — it picks a tool by reading these two strings:

| Rule | Bad | Good |
|------|-----|------|
| Verb-first, snake_case, specific | `jobs`, `handle_data` | `search_open_jobs`, `apply_to_job` |
| Description says what + when + limits | "does stuff with jobs" | "Search currently open job postings by keyword… Use this before recommending or applying" |
| Params described with examples | `query: "the query"` | `query: "Keywords…, e.g. 'senior java backend'"` |
| Cross-tool routing stated | (nothing) | "Requires the job id from search_open_jobs" |

Write descriptions like prompts — that is what they are.

**Validation.** The server validates arguments against the generated JSON schema before invocation
(types, required fields) — on by default, keep it on. Bean Validation annotations on parameter
records are **not** enforced, and schema validation knows nothing about your semantics: validate
inside the tool and throw `IllegalArgumentException` with a message the model can act on.

**Errors.** A `RuntimeException` from a `@McpTool` method becomes a `CallToolResult` with
`isError=true` and the exception message as content — the connection survives and the model sees the
message (the full contract, verified against the 2.0.1 sources, is in the reference). So: throw
exceptions with model-readable messages ("Job 42 not found — search again with search_open_jobs"),
and never let messages leak stack traces, SQL, or credentials.

**Read vs write.** Set the hint annotations honestly — clients use them for permission prompts and
retry behavior:

| Hint | Set `true` when |
|------|-----------------|
| `readOnlyHint` | the tool changes nothing (search, get, list) |
| `idempotentHint` | same args twice = no extra effect (upsert, dedupe) |
| `destructiveHint` | the tool can delete or overwrite — expect clients to confirm with the user |
| `openWorldHint` | the tool talks to external systems (default true; false for pure DB reads) |

**One tool per use case.** If you find yourself generating `get_all_X`/`create_X`/`update_X`/
`delete_X` per entity, stop — any prompt that reaches the model can now rewrite your database.
Expose the handful of operations from Step 0 and nothing else.

---

## Step 6 — Secure the endpoint

The MCP spec (2025-06-18 revision and later) treats a protected MCP server as an OAuth 2.1
**resource server**. Spring AI ships no MCP-specific auth — the honest story today: the MCP endpoint
is a normal HTTP endpoint, secured with the same Spring Security setup as the rest of the API.

If the project already ran the `spring-security` skill, extend its `SecurityFilterChain` to cover
the MCP endpoint — do not create a second chain (no security config yet and `auth` was true in
Step 0? Run `spring-security` first, then add this line):

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/mcp/**").authenticated()
    // ...existing rules
)
```

Tutorials leave `/mcp` open constantly; that is fine for a laptop demo and nowhere else — an
unauthenticated MCP endpoint is an unauthenticated API with a model driving it.

---

## Step 7 — Tests

**No live LLM calls in CI.** An MCP client calls tools directly over JSON-RPC — the model is not
part of the loop. Two deterministic layers:

1. **Unit tests on the tools class** — instantiate `JobMcpTools` with a mocked `JobService`; assert
   delegation and that validation throws carry readable messages. No Spring context.
2. **Wire test with a real MCP client** — `@SpringBootTest` on a random port plus the MCP Java SDK's
   sync client (BOM-managed, on the classpath via the starter). Full code in the reference:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpServerIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void lists_and_calls_tools() {
        var transport = HttpClientStreamableHttpTransport
            .builder("http://localhost:" + port + "/mcp").build();
        try (var client = McpClient.sync(transport).build()) {
            client.initialize();
            assertThat(client.listTools().tools())
                .extracting(McpSchema.Tool::name)
                .contains("search_open_jobs", "apply_to_job");

            var result = client.callTool(new McpSchema.CallToolRequest(
                "search_open_jobs", Map.of("query", "java")));
            assertThat(result.isError()).isFalse();
        }
    }
}
```

Assert the refusal path too: call a tool with invalid args and require `isError=true` — a smoke
test that only proves the happy path misses the day a tool stops refusing.

---

## Step 8 — docker-compose: nothing to add

Verdict: **no compose service.** The MCP server is the app itself; the debugging tool is the MCP
Inspector, which runs via `npx` on the developer's machine, not in Docker. (Calling other MCP
servers — `spring-ai-starter-mcp-client` — is out of scope here.)

---

## Step 9 — Run, verify with a real client, report

```bash
./mvnw test
./mvnw spring-boot:run
```

Probe the running server with the MCP Inspector CLI (Node ≥ 22.19; pin the version — latest
verified 2.8.0):

```bash
# pin check: https://registry.npmjs.org/@modelcontextprotocol/inspector/latest
npx --yes @modelcontextprotocol/inspector@2.8.0 --cli \
  --transport http --server-url http://localhost:8080/mcp \
  --method tools/list --format json

npx --yes @modelcontextprotocol/inspector@2.8.0 --cli \
  --transport http --server-url http://localhost:8080/mcp \
  --method tools/call --tool-name search_open_jobs \
  --tool-args-json '{"query":"java"}' --format json
```

Proof required before reporting done: `tools/list` returns every tool from Step 4 with its
description, and one `tools/call` returns real JSON from the domain. For interactive probing run
`npx @modelcontextprotocol/inspector` with no flags and connect the web UI to
`http://localhost:8080/mcp`.

Report — verdict first, then the board:

```
MCP server live · 2 tools exposed · verified via Inspector CLI

━━━ MCP-SERVER ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Spring AI ............. ✅ 2.0.1 (BOM) — verified against Maven Central
Starter ............... ✅ spring-ai-starter-mcp-server-webmvc
Transport ............. ✅ streamable HTTP on /mcp
Tools ................. ✅ 2 exposed (1 read-only, 1 write+idempotent) — use-case-shaped, no CRUD
Validation ............ ✅ JSON-schema (default) + semantic checks in tools
Errors ................ ✅ isError=true contract — model-readable messages
Security .............. ✅ /mcp requires JWT (existing resource server)
Tests ................. ✅ SDK client wire test — no live LLM
Inspector ............. ✅ tools/list + tools/call verified @2.8.0
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Next: run `api-design` to document the tool contracts for human consumers,
or `otel-setup` so tool calls show up in traces.
```

Mark a row ⚠ when it was configured but not observed — "Inspector call not run, Node unavailable"
beats a green board nobody checked. Never report the Inspector row green from configuration alone.
