# MCP reference — Spring AI MCP server on Spring Boot 4

Version and artifact matrix, property map, transport choice, the tool API in full, the validation
and error contract, auth status, test setup, and Inspector usage for the `mcp-server` skill.

---

## Version and compatibility matrix

| Spring AI | Spring Boot | MCP Java SDK (transitive) | Status |
|---|---|---|---|
| 2.0.1 | 4.0.x / 4.1.x | `io.modelcontextprotocol.sdk:mcp-core:2.0.0` | current stable — this skill targets it |
| 2.1.0-M1 | 4.1.x | milestone line | do not pin |
| 1.x | 3.x | 0.x / 1.x SDK | different artifacts/packages everywhere — out of scope |

The MCP Java SDK speaks protocol revisions up to **2025-11-25** (constant in
`io.modelcontextprotocol.spec.ProtocolVersions`). The SDK version is managed transitively by the
Spring AI BOM — never declare or override it by hand; Spring AI and the SDK move in lockstep and a
hand override is the classic skew break.

Re-verify before writing anything:

```bash
# current stable Spring AI (excludes milestones/RCs)
curl -s "https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/maven-metadata.xml" \
  | grep -o '<version>[^<]*' | sed 's/<version>//' | grep -v '\-M\|\-RC' | tail -1

# does a specific artifact exist at that version? (200 = yes, 404 = renamed or dead)
curl -s -o /dev/null -w "%{http_code}\n" \
  "https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-starter-mcp-server-webmvc/2.0.1/"

# MCP Inspector version pin
curl -s "https://registry.npmjs.org/@modelcontextprotocol/inspector/latest" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['version'])"
```

## Artifact renames 1.x → 2.0 (verified against repo1 at 2.0.1)

| 1.x / community (dead) | 2.0.x (current) |
|---|---|
| `spring-ai-mcp-server-spring-boot-starter` | `spring-ai-starter-mcp-server` (STDIO) |
| `spring-ai-mcp-server-webmvc-spring-boot-starter` | `spring-ai-starter-mcp-server-webmvc` |
| `spring-ai-mcp-server-webflux-spring-boot-starter` | `spring-ai-starter-mcp-server-webflux` |
| `org.springaicommunity:mcp-annotations` | `org.springframework.ai:spring-ai-mcp-annotations` (pulled by every server starter) |
| `org.springaicommunity.mcp.annotation.*` | `org.springframework.ai.mcp.annotation.*` |
| `io.modelcontextprotocol.sdk:mcp-spring-webmvc` | `org.springframework.ai:mcp-spring-webmvc` (transports moved into Spring AI; classes now in `org.springframework.ai.mcp.server.webmvc.transport`) |

All three server starters verified present at 2.0.1; all three 1.x names return 404. Do not copy
coordinates from pre-GA tutorials — most of them show the dead names.

What the webmvc starter pulls (verified from its 2.0.1 POM): `spring-boot-starter-web`,
`spring-ai-autoconfigure-mcp-server-webmvc`, `spring-ai-mcp`, `spring-ai-mcp-annotations`,
`mcp-spring-webmvc` → `io.modelcontextprotocol.sdk:mcp-core`. No model/chat dependencies — an MCP
server needs no LLM provider.

---

## Transport choice

| | Streamable HTTP (default) | STDIO |
|---|---|---|
| Starter | `spring-ai-starter-mcp-server-webmvc` (or `-webflux`) | `spring-ai-starter-mcp-server` |
| Endpoint | `POST /mcp` (`spring.ai.mcp.server.streamable-http.mcp-endpoint`) | stdin/stdout of the spawned process |
| Clients connect to | a URL | a command they spawn |
| Use when | **the service already runs as a web app** — the norm in this repo | a local CLI tool a host (Claude Desktop, an IDE) spawns per-session |
| Auth | normal HTTP — Spring Security resource server | ambient — the host controls the process; network auth does not apply |
| Sessions | stateful (STREAMABLE) or stateless (STATELESS) | one session per process |

Two older transports exist in config for compatibility: SSE (`spring.ai.mcp.server.protocol: sse`,
deprecated by the spec in favor of streamable HTTP) and STATELESS (no session state, no
bidirectional operations — pick it only for simple request/response tools behind a load balancer
without sticky sessions).

### STDIO rules (when STDIO is genuinely the right call)

A STDIO server speaks JSON-RPC on stdout. Anything else on stdout corrupts the stream:

```yaml
spring:
  main:
    web-application-type: none   # no embedded server in a STDIO tool
    banner-mode: off
  ai:
    mcp:
      server:
        stdio: true
logging:
  pattern:
    console:                     # empty — silence console logging; log to a file instead
```

A single `System.out.println`, startup banner, or console log line = a broken protocol stream and a
client that hangs on initialize. A STDIO server and a web app do not mix in one process.

---

## Property map (from `spring-configuration-metadata.json`, 2.0.1)

| Property | Default | Notes |
|---|---|---|
| `spring.ai.mcp.server.enabled` | `true` | |
| `spring.ai.mcp.server.name` | `mcp-server` | clients display it — set it |
| `spring.ai.mcp.server.version` | `1.0.0` | server version, not protocol version |
| `spring.ai.mcp.server.instructions` | empty | injected into the model's context — write it like a system prompt for tool selection |
| `spring.ai.mcp.server.type` | `SYNC` | `SYNC` or `ASYNC` — ASYNC registers only reactive (`Mono`/`Flux`) tool methods |
| `spring.ai.mcp.server.protocol` | `STREAMABLE` | `STREAMABLE` / `SSE` / `STATELESS` |
| `spring.ai.mcp.server.stdio` | `false` | STDIO transport switch — see rules above |
| `spring.ai.mcp.server.streamable-http.mcp-endpoint` | `/mcp` | |
| `spring.ai.mcp.server.streamable-http.keep-alive-interval` | off | |
| `spring.ai.mcp.server.sse-endpoint` | `/sse` | legacy SSE protocol only |
| `spring.ai.mcp.server.sse-message-endpoint` | `/mcp/message` | legacy SSE protocol only |
| `spring.ai.mcp.server.request-timeout` | `20s` | raise deliberately for slow tools |
| `spring.ai.mcp.server.capabilities.{tool,resource,prompt,completion}` | `true` | |
| `spring.ai.mcp.server.{tool,resource,prompt}-change-notification` | `true` | |
| `spring.ai.mcp.server.tool-response-mime-type.<tool>` | none | per-tool response MIME override |
| `spring.ai.mcp.server.annotation-scanner.enabled` | `true` | picks up `@McpTool`/`@McpResource`/`@McpPrompt` beans |
| `spring.ai.mcp.server.expose-mcp-client-tools` | `false` | re-expose downstream MCP client tools — a proxy; leave false |

---

## The tool API in full

### `@McpTool` (recommended for a dedicated MCP server)

From `spring-ai-mcp-annotations` (pulled by the starter; `org.springframework.ai.mcp.annotation`).
Auto-config scans Spring beans and registers annotated methods — no provider bean needed.

| Attribute | Default | Notes |
|---|---|---|
| `name` | method name | always set it — verb-first snake_case |
| `description` | method name | the model's selection signal — see below |
| `title` | `""` | UI display name |
| `generateOutputSchema` | `false` | generates a JSON output schema for non-primitive returns |
| `annotations` | — | `@McpTool.McpAnnotations(...)` — the hints below |
| `metaProvider` | default | `_meta` field supplier — rarely needed |

Parameter annotation `@McpToolParam(description, required)` — describe every parameter with an
example value.

Tool annotation hints (`@McpTool.McpAnnotations`, all verified in the 2.0.1 jar):

| Hint | Default | Meaning |
|---|---|---|
| `readOnlyHint` | `false` | tool does not modify its environment |
| `destructiveHint` | `true` | may perform destructive updates (only meaningful when not read-only) |
| `idempotentHint` | `false` | same args twice = no additional effect |
| `openWorldHint` | `true` | interacts with external entities; set `false` for closed-domain DB tools |

Clients use these for permission prompts and auto-approval decisions — set them honestly. A
destructive tool marked read-only is exactly the lie a confused-deputy attack needs.

Method filtering (warned in logs at startup): SYNC servers register only non-reactive return types;
ASYNC only `Mono`/`Flux`; STATELESS additionally drops methods with `McpSyncRequestContext` /
`McpSyncServerExchange` parameters (no bidirectional ops — roots, elicitation, sampling). A tool
that silently doesn't appear in `tools/list` is almost always a filtering mismatch — check the
startup warnings.

Special parameters a tool method may declare: `McpSyncRequestContext` (progress, logging
notifications, ping, elicitation, sampling), `McpTransportContext` (lightweight, stateless-safe),
`CallToolRequest` (raw arguments for dynamic schemas), `McpMeta`, `@McpProgressToken`.

### The `@Tool` / `ToolCallback` path

The server auto-config also consumes `ToolCallback` and `ToolCallbackProvider` beans
(`ToolCallbackConverterAutoConfiguration`, verified in 2.0.1 sources) — i.e.
`org.springframework.ai.tool.annotation.@Tool` methods registered via
`MethodToolCallbackProvider.builder().toolObjects(...).build()`. Use it only when the same callback
is shared with a `ChatClient` in the same app. It carries no MCP hint annotations and no
`@McpToolParam` descriptions, so for an MCP-first server `@McpTool` is the better default.

### Description writing — the rules that matter

The name + description pair is a prompt. The model has nothing else to select or call by.

- **Verb-first and specific.** `search_open_jobs` selects well; `jobs` and `handle_data` do not.
- **State what, when, and limits.** "Search currently open job postings by keyword and location.
  Returns at most 20 matches. Use this before recommending or applying to any job."
- **Route between tools.** "Requires the job id from search_open_jobs" teaches the model the
  intended call sequence — without it, models guess ids.
- **Every parameter gets an example.** `query: "Keywords to match, e.g. 'senior java backend'"`.
- **Vague = mis-selected.** "does stuff with jobs" gets called for anything job-shaped and skipped
  when actually needed. Both failure modes are silent.

---

## Validation and the error contract

**Structural validation is on by default.** The MCP Java SDK 2.0.0 validates incoming arguments
against the tool's generated JSON schema before invoking the handler; failures return
`CallToolResult` with `isError=true` and a descriptive message. `@McpTool`-generated schemas are
compatible out of the box. Keep it on.

**Bean Validation is not applied.** Nothing in `spring-ai-mcp-annotations` integrates
`jakarta.validation` — `@NotNull`/`@Size` on a parameter record are dead annotations there. Semantic
validation is your code, in the tool method:

```java
if (query == null || query.isBlank()) {
    throw new IllegalArgumentException("query must not be blank — pass keywords like 'senior java backend'");
}
```

**The error contract (verified against the 2.0.1 sources, `SyncMcpToolMethodCallback` /
`AbstractSyncMcpToolMethodCallback`):**

- A `RuntimeException` from the tool method → `CallToolResult(isError=true)`, content = the
  exception message plus the root-cause message. The connection survives; the model reads the
  message and can retry or correct.
- An `io.modelcontextprotocol.spec.McpError` → propagates as a protocol-level JSON-RPC error. Throw
  it only for protocol semantics (e.g. URL elicitation), not domain failures.

Consequences:

- **Throw for domain failures, with model-readable messages.** "Job 42 not found — run
  search_open_jobs to get a current id" beats "Job 42 not found". The message is the model's only
  error signal.
- **Never leak internals in the message.** No stack traces, SQL, file paths, or credentials — the
  text lands in the model's context and from there potentially in the user's chat. Translate
  persistence/messaging exceptions into clean messages at the tool boundary.
- Clients surface `isError=true` as a failed call (the Inspector CLI exits 5). Do not return error
  text as a normal 200-style result — the model cannot tell it apart from success.

---

## Auth — the real story

- The MCP authorization spec (revision **2025-06-18** and later; the SDK speaks up to 2025-11-25)
  defines a protected MCP server as an **OAuth 2.1 resource server**: it validates access tokens,
  serves Protected Resource Metadata (RFC 9728), and clients bind tokens to it via Resource
  Indicators (RFC 8707). The authorization server is a separate role, out of scope for the server.
- **Spring AI ships no MCP-specific authorization support.** What exists today is: the MCP endpoint
  is a normal HTTP endpoint in your app, secured by the same Spring Security resource-server filter
  chain as everything else (the `spring-security` skill). That covers bearer-token validation on
  `/mcp` with one line of config.
- What that DIY approach does **not** give you: RFC 9728 metadata endpoints, the OAuth
  discovery/dance MCP clients perform when challenged, per-user tool authorization. If a client
  needs the full spec flow, that is custom filter/endpoint work — say so plainly rather than
  pretending the starter provides it.
- STDIO servers skip all of this: the host spawns and trusts the process.
- Tutorials that demo MCP over open HTTP are everywhere. An unauthenticated MCP endpoint is an
  unauthenticated API with a model driving it — treat Step 6 of the skill as required outside a
  laptop demo.

---

## Test setup

No live LLM anywhere: an MCP client invokes tools over JSON-RPC directly.

### Wire test (recommended)

`@SpringBootTest` on a random port + the MCP Java SDK sync client (already on the classpath via the
starter — no extra dependency):

```java
package <package>;

import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

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
                .contains("search_open_jobs");

            var result = client.callTool(
                new McpSchema.CallToolRequest("search_open_jobs", Map.of("query", "java")));
            assertThat(result.isError()).isFalse();
        }
    }

    @Test
    void invalid_arguments_return_error_result() {
        var transport = HttpClientStreamableHttpTransport
            .builder("http://localhost:" + port + "/mcp").build();
        try (var client = McpClient.sync(transport).build()) {
            client.initialize();

            var result = client.callTool(
                new McpSchema.CallToolRequest("apply_to_job", Map.of("jobId", -1, "candidateEmail", "not-an-email")));
            assertThat(result.isError()).isTrue();   // a refusal must stay a refusal
        }
    }
}
```

Extend the project's `BaseIntegrationTest` instead of plain `@SpringBootTest` when the tools hit a
database — same pattern as the other skills. When the endpoint is secured (Step 6), pass a test JWT
via the transport's request customizer or keep a test profile that permits `/mcp` — and say which in
the report.

### Unit tests

Instantiate the tools class with a mocked domain service (no Spring context): assert delegation,
and assert validation messages are model-readable. This is where message quality gets tested —
the wire test only checks `isError`.

---

## MCP Inspector

The current debugging tool is `@modelcontextprotocol/inspector` (npm; requires Node ≥ 22.19; latest
verified **2.8.0**). Two modes:

```bash
# web UI — interactive probing
npx @modelcontextprotocol/inspector
# open the printed URL, transport "Streamable HTTP", URL http://localhost:8080/mcp

# CLI — scriptable, machine-readable
npx --yes @modelcontextprotocol/inspector@2.8.0 --cli \
  --transport http --server-url http://localhost:8080/mcp \
  --method tools/list --format json

npx --yes @modelcontextprotocol/inspector@2.8.0 --cli \
  --transport http --server-url http://localhost:8080/mcp \
  --method tools/call --tool-name search_open_jobs \
  --tool-args-json '{"query":"java"}' --format json
```

- Pin the exact version (`@2.8.0`, plus `--yes`) anywhere unattended; bare `npx` resolves latest.
- `--tool-args-json` passes arguments verbatim; `--tool-arg k=v` JSON-coerces values (`zip=012`
  arrives as a number trap). Prefer `--tool-args-json`.
- Exit codes: 0 success, 3 auth required, 4 unreachable, **5 tool returned `isError:true`** — a
  failed tool call never silently passes an `&&` chain.
- Against a secured endpoint: `--header 'Authorization: Bearer <token>'`.

---

## Symptom → cause → fix

| Symptom | Cause | Fix |
|---|---|---|
| `tools/list` is empty or missing a tool | Method filtered by server type (reactive return on SYNC server, context param on STATELESS) | Read the startup warnings; align return type / protocol with `spring.ai.mcp.server.type` |
| 404 on `/mcp` | Wrong starter (STDIO starter in a web app) or changed endpoint property | Use `-webmvc`/`-webflux` starter; check `streamable-http.mcp-endpoint` |
| Client hangs on initialize against a STDIO server | Something wrote to stdout — banner, console log, `println` | `web-application-type: none`, `banner-mode: off`, empty console pattern |
| Model picks the wrong tool or invents arguments | Vague name/description; no cross-tool routing | Rewrite descriptions per the rules; add "use X before Y" routing |
| Tool returns an error the model ignores | Error returned as normal content instead of `isError` | Throw a `RuntimeException` with a readable message — the callback maps it to `isError=true` |
| `isError=true` with a schema message before the method runs | JSON-schema input validation (default on) rejecting args | Fix the client args or the generated schema; do not disable validation |
| Bean Validation annotations on tool records do nothing | No jakarta.validation integration in the annotations module | Validate semantically inside the tool method |
| Exception message leaks SQL/stack into the chat | Raw exceptions crossing the tool boundary | Translate to clean messages at the tool; the message lands in model context |
| `ClassNotFoundException: io.modelcontextprotocol...transport.WebMvc...` | Pre-2.0 imports — transports moved to `org.springframework.ai.mcp.server.*.transport` | Update imports; auto-config users need no code change |
| Build pulls a second, older MCP SDK | Hand-declared `io.modelcontextprotocol.sdk` version overriding the BOM | Remove the override — the BOM owns it |
| Old tutorial coordinates 404 | 1.x artifact names are dead (`spring-ai-mcp-server-*-spring-boot-starter`) | Use `spring-ai-starter-mcp-server[-webmvc/-webflux]` |
| Tests time out or need an API key | A live LLM crept into the tool path | MCP tests call tools directly — no model; keep it that way |
