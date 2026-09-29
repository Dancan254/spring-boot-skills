---
name: spring-ai-rag
description: "Add a RAG pipeline to an existing Spring Boot 4 Maven project with Spring AI — PgVector store, Ollama or OpenAI embeddings, document ingestion and chunking, a retrieval advisor, and tests proving answers are grounded. Use when asked to add RAG, semantic or vector search, embeddings, or chat with my documents. Not for MCP — use mcp-server."
---

# Spring AI RAG Skill

Takes a Spring Boot 4 app from nothing to: ingest documents → embed them into PgVector → answer
questions grounded in those documents — and proves the loop end to end before saying it's done.

`SKILL_DIR` = the directory containing this SKILL.md.

**Load `SKILL_DIR/references/rag-reference.md` before writing anything** — property map, version and
artifact matrix, embedding-model dimensions, chunking guidance, and the symptom → cause → fix table.

The #1 silent failure in this stack is an **embedding-dimension mismatch**: a model that emits
768-float vectors writing into a `vector(1536)` column (or vice versa). Symptoms range from obscure
SQL errors at insert time to a table quietly created for the wrong model. Dimensions are pinned
explicitly on both sides in Steps 3–4. Never leave them implicit.

---

## Step 0 — Gather inputs

| Field | Required | Notes |
|-------|----------|-------|
| `provider` | Yes | `ollama` (default, self-hosted) or `openai` |
| `embeddingModel` | No | default `nomic-embed-text` (Ollama, 768 dims) / `text-embedding-3-small` (OpenAI, 1536 dims) |
| `chatModel` | No | default `llama3.2` (Ollama) / `gpt-4o-mini` (OpenAI) |
| `docSources` | Yes | where documents come from — upload endpoint, classpath dir, or both |
| `dimensions` | No | derived from `embeddingModel`; must match the Flyway DDL. Table in `references/rag-reference.md` |

---

## Step 1 — Read the project

```bash
grep -m1 -A2 'spring-boot-starter-parent' pom.xml
grep -n 'flyway\|spring-ai' pom.xml
ls src/main/resources/db/migration 2>/dev/null | tail -5
grep -rn 'datasource' src/main/resources/application.y*ml | head -5
find src/test/java -name 'BaseIntegrationTest.java'
ls compose.yaml docker-compose.yml 2>/dev/null
```

Establish: Boot version, whether Flyway already owns the schema (decides Step 4), whether
`BaseIntegrationTest` exists (Step 8), and whether a compose file exists (Step 9).

**Spring AI 2.0.x supports Spring Boot 4.0.x and 4.1.x only.** On Boot 3.x, stop — the 1.x line has
different artifact names, packages, and properties everywhere, and this skill does not generate it.

---

## Step 2 — Verify pins, add BOM and starters

Artifact coordinates changed across the Spring AI milestones and GA. Never write one from memory —
re-check the current stable version (as of this writing: `2.0.1`):

```bash
curl -s "https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/maven-metadata.xml" \
  | grep -o '<version>[^<]*' | sed 's/<version>//' | grep -v '\-M\|\-RC' | tail -1
```

Import the BOM in `dependencyManagement`, then the starters — no versions, the BOM owns them:

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
    <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-ollama</artifactId>
    <!-- or spring-ai-starter-model-openai — one provider, not both -->
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-vector-store-advisor</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-tika-document-reader</artifactId>
</dependency>
```

The 1.x names are dead — `spring-ai-openai-spring-boot-starter`,
`spring-ai-pgvector-store-spring-boot-starter`, and the advisor module
`spring-ai-advisors-vector-store` all stopped at the 2.0 milestones. Do not copy them from old
tutorials; the coordinate-existence check curl is in `references/rag-reference.md`.

---

## Step 3 — application.yml

Ollama branch (default):

```yaml
spring:
  ai:
    model:
      chat: ollama
      embedding: ollama
    ollama:
      base-url: ${OLLAMA_BASE_URL:http://localhost:11434}
      embedding:
        options:
          model: ${OLLAMA_EMBEDDING_MODEL:nomic-embed-text}   # 768 dimensions
      chat:
        options:
          model: ${OLLAMA_CHAT_MODEL:llama3.2}
    vectorstore:
      pgvector:
        initialize-schema: false        # Flyway owns the DDL — Step 4
        schema-validation: true         # fail fast at startup if table dims don't match the model
        dimensions: 768                 # MUST equal the embedding model's output width
        index-type: HNSW
        distance-type: COSINE_DISTANCE
```

OpenAI branch: swap `spring.ai.model.chat`/`embedding` to `openai`, set
`spring.ai.openai.api-key: ${OPENAI_API_KEY}` (env var only — never hardcode; hosted APIs see every
chunk you embed, so no PII/secrets-bearing docs without a data decision) and
`spring.ai.openai.embedding.options.model: text-embedding-3-small` (1536 dims — change `dimensions`
and the DDL with it). Full block in `references/rag-reference.md`.

Rules, non-negotiable:

- **One dimension value, three places**: `spring.ai.vectorstore.pgvector.dimensions`, the Flyway
  `vector(N)` column, and the model's real output width. Change the model → change all three →
  re-ingest every document.
- `dimensions` defaults to `-1` (auto-derive). Explicit beats derived — auto-derive silently bakes
  whatever model is configured into the table on first write.

---

## Step 4 — Who owns the vector table DDL

Verdict: **if Flyway manages the schema (the norm in this repo), Flyway owns the vector table too.**
One owner, reviewable DDL, reproducible environments.

`src/main/resources/db/migration/V<next>__vector_store.sql`:

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE vector_store (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content   TEXT,
    metadata  JSON,
    embedding vector(768)     -- one dimension value, three places — keep in sync with the model
);

CREATE INDEX spring_ai_vector_index
    ON vector_store USING hnsw (embedding vector_cosine_ops);
```

- The HNSW index is part of the DDL, not an optimization for later. Without it every similarity query
  is a sequential scan — invisible at 100 rows, silent degradation at 100k. Step 10 verifies with
  `EXPLAIN`.
- `initialize-schema: true` **and** Flyway together is the classic bug: double DDL, ordering races,
  `dimensions` drift. Pick one owner. Only with **no** Flyway: set `initialize-schema: true`, skip the
  migration — Spring AI's DDL creates extension, table, and HNSW index. Never run both.
- `schema-validation: true` (Step 3) turns future dimension drift into a startup failure instead of
  garbage retrieval. Keep it on.

---

## Step 5 — Ingestion pipeline

Create `src/main/java/<package>/rag/IngestionService.java`:

```java
package <package>.rag;

@Service
public class IngestionService {

    private final VectorStore vectorStore;

    public IngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void ingest(Resource resource, String sourceName) {
        List<Document> pages = new TikaDocumentReader(resource).get();
        TokenTextSplitter splitter = TokenTextSplitter.builder()
            .withChunkSize(600)
            .withMinChunkSizeChars(350)
            .withKeepSeparator(true)
            .build();
        List<Document> chunks = splitter.apply(pages);
        chunks.forEach(d -> d.getMetadata().put("source", sourceName));
        vectorStore.add(chunks); // batches internally — max-document-batch-size bounds each round trip
    }
}
```

Imports: `org.springframework.ai.reader.tika.TikaDocumentReader`,
`org.springframework.ai.transformer.splitter.TokenTextSplitter`, `org.springframework.ai.vectorstore.*`.

Why 600: chunks too large dilute the embedding (one vector averages away the relevant passage); too
small lose the context the LLM needs. 500–800 tokens is the working range, bounded above by the
embedding model's context window (`nomic-embed-text` truncates past 8192 tokens). Full reasoning and
the "no overlap knob" caveat are in `references/rag-reference.md`. The `source` metadata is what
Step 6 filters on — set it on every chunk.

---

## Step 6 — Retrieval + generation

Create `src/main/java/<package>/rag/RagController.java`:

```java
package <package>.rag;

@RestController
@RequestMapping("/rag")
public class RagController {

    private final ChatClient chatClient;
    private final IngestionService ingestionService;

    public RagController(ChatClient.Builder builder, VectorStore vectorStore,
                         IngestionService ingestionService) {
        this.chatClient = builder
            .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                    .topK(6)
                    .similarityThreshold(0.7)  // the quality floor — see below
                    .build())
                .build())
            .build();
        this.ingestionService = ingestionService;
    }

    @PostMapping("/ingest")
    public void ingest(@RequestParam("file") MultipartFile file) {
        ingestionService.ingest(file.getResource(), file.getOriginalFilename());
    }

    @GetMapping("/ask")
    public String ask(@RequestParam String q) {
        return chatClient.prompt().user(q).call().content();
    }
}
```

- `similarityThreshold` is the quality floor. Without it, every question retrieves *something* — and
  the LLM answers confidently from irrelevant chunks. 0.7 is the starting point; treat a rising
  empty-answer rate as a retrieval problem, not a prompt problem.
- `topK` 4–8 is the working range. Past ~8 you're paying tokens to dilute the prompt, not help it.
- Metadata filtering per request:
  `chatClient.prompt().advisors(a -> a.param(QuestionAnswerAdvisor.FILTER_EXPRESSION, "source == 'handbook.pdf'"))`
- Need query rewriting, multi-query expansion, or post-retrieval ranking? That's
  `RetrievalAugmentationAdvisor` from the `spring-ai-rag` module — snippet in
  `references/rag-reference.md`. Don't reach for it until `QuestionAnswerAdvisor` demonstrably
  under-retrieves.

---

## Step 7 — Smoke evaluation

`RelevancyEvaluator` (`org.springframework.ai.chat.evaluation`) judges whether the answer is relevant
to the retrieved context, using a second LLM call as the judge:

```java
RelevancyEvaluator evaluator = new RelevancyEvaluator(chatClientBuilder);
EvaluationResponse verdict = evaluator.evaluate(new EvaluationRequest(question, retrievedChunks, answer));
assertThat(verdict.isPass()).isTrue();
```

Honest framing: this is a **smoke test, not an eval harness**. It needs a live chat model, so keep it
out of the default CI suite (local run or a dedicated profile). It catches "the loop is broken"; it
does not catch "retrieval quality regressed 3%". Real evaluation is a dataset, a rubric, and a
nightly job — flag that as a follow-up, don't pretend the smoke test is it.

---

## Step 8 — Tests

`BaseIntegrationTest` (from `spring-scaffold`) runs plain `postgres:18-alpine`, which has no pgvector
extension. Swap the image — pgvector's image is a drop-in Postgres, so one container still serves the
whole suite:

```java
@Container
@ServiceConnection
static PostgreSQLContainer postgres = new PostgreSQLContainer(
    DockerImageName.parse("pgvector/pgvector:0.8.6-pg18").asCompatibleSubstituteFor("postgres"));
```

Confirm the tag before writing:

```bash
curl -s "https://hub.docker.com/v2/repositories/pgvector/pgvector/tags/0.8.6-pg18" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['name'], d['last_updated'][:10])"
```

**No live LLM calls in CI.** Deterministic strategy: a fixed-vector `EmbeddingModel` stub (full code
in `references/rag-reference.md` — same text always embeds to the same vector, no model server) plus
`@MockitoBean` for the chat model. The stub's dimension count must equal
`spring.ai.vectorstore.pgvector.dimensions` — same rule, third place.

```java
class RagRetrievalIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private VectorStore vectorStore;

    @MockitoBean
    private ChatModel chatModel; // generation mocked; retrieval is what we prove

    @Test
    void ingested_chunk_is_retrievable() {
        vectorStore.add(List.of(
            new Document("The refund window is 30 days.", Map.of("source", "policy.pdf"))));

        List<Document> results = vectorStore.similaritySearch(
            SearchRequest.builder().query("refund window").topK(1).build());

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getMetadata()).containsEntry("source", "policy.pdf");
    }
}
```

Full-fidelity alternative (Testcontainers Ollama with a real model) is in the reference too.

---

## Step 9 — docker-compose services

If `docker-compose.yml`/`compose.yaml` exists, add (plus `ollama-models:` under top-level `volumes:`):

```yaml
postgres:
  image: pgvector/pgvector:0.8.6-pg18   # pinned; never :latest
  environment:
    POSTGRES_USER: rag
    POSTGRES_PASSWORD: rag
    POSTGRES_DB: ragdb
  ports:
    - "5432:5432"
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U rag -d ragdb"]
    interval: 5s
    retries: 10

ollama:                                  # only for the Ollama branch
  image: ollama/ollama:0.35.0            # pinned; verify below
  ports:
    - "11434:11434"
  volumes:
    - ollama-models:/root/.ollama
```

Verify the ollama tag, then pull the models once after `docker compose up -d`:

```bash
curl -s "https://hub.docker.com/v2/repositories/ollama/ollama/tags?page_size=8&ordering=last_updated" \
  | python3 -c "import json,sys; print([t['name'] for t in json.load(sys.stdin)['results']])"

docker compose exec ollama ollama pull nomic-embed-text
docker compose exec ollama ollama pull llama3.2
```

---

## Step 10 — Run, verify end to end, report

```bash
./mvnw test
docker compose up -d
./mvnw spring-boot:run
```

Prove the loop with a real document (`sample-policy.pdf` stating e.g. "the refund window is 30
days"):

```bash
curl -F "file=@sample-policy.pdf" localhost:8080/rag/ingest
curl "localhost:8080/rag/ask?q=What%20is%20the%20refund%20window"
```

The answer must cite the document's content (30 days), not general knowledge. Then prove the index:

```sql
EXPLAIN SELECT id FROM vector_store
ORDER BY embedding <=> (SELECT embedding FROM vector_store LIMIT 1) LIMIT 5;
-- want: Index Scan using spring_ai_vector_index
-- Seq Scan means the HNSW index is missing — back to Step 4
```

Report — verdict first, then the board:

```
RAG wired · ingest → embed → retrieve → answer verified end to end

━━━ SPRING-AI-RAG ━━━━━━━━━━━━━━━━━━━━━━━━━━━
Spring AI ............. ✅ 2.0.1 (BOM) — verified against Maven Central
Vector store .......... ✅ PgVector dims=768 · DDL owned by Flyway V2__
Models ................ ✅ Ollama nomic-embed-text (768) + llama3.2 — local, no key
Ingestion ............. ✅ Tika + TokenTextSplitter(600)
Retrieval ............. ✅ QuestionAnswerAdvisor · topK=6 · threshold=0.7
HNSW index ............ ✅ EXPLAIN shows Index Scan
Tests ................. ✅ pgvector container · no live LLM calls
E2E curl .............. ✅ answer grounded in ingested document
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Next: add `api-design` to document the /rag endpoints, or run `security-hardening`
before ingesting documents that contain PII.
```

Mark a row ⚠ when it was configured but not observed — "Ollama model pull still running, E2E answer
not verified" beats a green board nobody checked. Never report the E2E row green from config alone.
