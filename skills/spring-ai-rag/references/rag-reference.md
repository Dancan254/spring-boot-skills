# RAG reference — Spring AI on Spring Boot 4

Property map, version and artifact matrix, embedding dimensions, chunking guidance, test strategy,
and troubleshooting for the `spring-ai-rag` skill.

---

## Version and compatibility matrix

| Spring AI | Spring Boot | Status |
|---|---|---|
| 2.0.1 | 4.0.x / 4.1.x | current stable — this skill targets it |
| 2.1.0-M1 | 4.1.x | milestone — do not pin |
| 1.x | 3.x | different artifacts/packages everywhere — out of scope |

Compatibility per the [Spring AI getting-started docs](https://docs.spring.io/spring-ai/reference/getting-started.html)
and the [2.0.0 GA announcement](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now).

Re-verify before writing anything:

```bash
# current stable Spring AI (excludes milestones/RCs)
curl -s "https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/maven-metadata.xml" \
  | grep -o '<version>[^<]*' | sed 's/<version>//' | grep -v '\-M\|\-RC' | tail -1

# does a specific artifact exist at that version? (200 = yes, 404 = renamed or dead)
curl -s -o /dev/null -w "%{http_code}\n" \
  "https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-starter-vector-store-pgvector/2.0.1/"

# docker image pins
curl -s "https://hub.docker.com/v2/repositories/pgvector/pgvector/tags/0.8.6-pg18" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['name'], d['last_updated'][:10])"
curl -s "https://hub.docker.com/v2/repositories/ollama/ollama/tags?page_size=8&ordering=last_updated" \
  | python3 -c "import json,sys; print([t['name'] for t in json.load(sys.stdin)['results']])"
```

## Artifact renames 1.x → 2.0 (verified against repo1 at 2.0.1)

| 1.x (dead) | 2.0.x (current) |
|---|---|
| `spring-ai-openai-spring-boot-starter` | `spring-ai-starter-model-openai` |
| `spring-ai-ollama-spring-boot-starter` | `spring-ai-starter-model-ollama` |
| `spring-ai-pgvector-store-spring-boot-starter` | `spring-ai-starter-vector-store-pgvector` |
| `spring-ai-advisors-vector-store` (died at 2.0.0-M8) | `spring-ai-vector-store-advisor` |
| `PgVectorVectorStore` | `PgVectorStore` (`org.springframework.ai.vectorstore.pgvector`) |
| `org.springframework.ai.evaluation.RelevancyEvaluator` | `org.springframework.ai.chat.evaluation.RelevancyEvaluator` (in `spring-ai-client-chat`) |

`spring-ai-rag` (the module) holds the modular RAG API: `RetrievalAugmentationAdvisor`,
`VectorStoreDocumentRetriever`, query transformers/expanders, document post-processors.

---

## Property map

| Property | Default | Notes |
|---|---|---|
| `spring.ai.model.chat` / `spring.ai.model.embedding` | auto | provider selector — set both explicitly |
| `spring.ai.ollama.base-url` | `http://localhost:11434` | |
| `spring.ai.ollama.embedding.options.model` | none | **required** — 2.0 has no default embedding model |
| `spring.ai.ollama.chat.options.model` | none | set explicitly |
| `spring.ai.openai.api-key` | none | startup fails without it; env var only |
| `spring.ai.openai.embedding.options.model` | `text-embedding-ada-002` | the built-in default is ada-002, not 3-small |
| `spring.ai.vectorstore.pgvector.initialize-schema` | `false` | `true` only when Flyway does not own the schema |
| `spring.ai.vectorstore.pgvector.dimensions` | `-1` (derive) | set explicitly — the three-places rule |
| `spring.ai.vectorstore.pgvector.index-type` | `HNSW` | applied by Spring AI DDL / your migration |
| `spring.ai.vectorstore.pgvector.distance-type` | `COSINE_DISTANCE` | determines the index opclass (`vector_cosine_ops`) |
| `spring.ai.vectorstore.pgvector.table-name` | `vector_store` | must match the Flyway migration |
| `spring.ai.vectorstore.pgvector.schema-name` | `public` | |
| `spring.ai.vectorstore.pgvector.schema-validation` | `false` | set `true` — startup fails on dimension drift |
| `spring.ai.vectorstore.pgvector.max-document-batch-size` | `10000` | bounds each embedding/insert round trip |
| `spring.ai.vectorstore.pgvector.remove-existing-vector-store-table` | `false` | never `true` outside throwaway experiments |

OpenAI branch, full block (profile `openai` or swapped in):

```yaml
spring:
  ai:
    model:
      chat: openai
      embedding: openai
    openai:
      api-key: ${OPENAI_API_KEY}
      # Hosted APIs see every chunk you embed — no PII/secrets-bearing docs without a data decision.
      embedding:
        options:
          model: text-embedding-3-small   # 1536 dims
      chat:
        options:
          model: gpt-4o-mini
    vectorstore:
      pgvector:
        dimensions: 1536
```

---

## Embedding dimensions — the three-places rule

The dimension value lives in three places that must always agree:
`spring.ai.vectorstore.pgvector.dimensions`, the Flyway `vector(N)` column, and the model's real
output width. Mismatch = `ERROR: expected 768 dimensions, not 1536` at insert, or a table built for
the wrong model. Changing models means new dimensions **and re-ingesting everything** — vectors from
different models are not comparable, so old rows become garbage that still retrieves.

| Model | Dims | Context (tokens) | Notes |
|---|---|---|---|
| `nomic-embed-text` (Ollama) | 768 | 8192 | house default — local, no key, no data leaves the box |
| `mxbai-embed-large` (Ollama) | 1024 | 512 | small context — watch chunk size |
| `text-embedding-ada-002` (OpenAI) | 1536 | 8191 | Spring AI's built-in default |
| `text-embedding-3-small` (OpenAI) | 1536 | 8191 | cheaper, better; the skill's OpenAI default |
| `text-embedding-3-large` (OpenAI) | 3072 | 8191 | |

---

## Chunking guidance

- **Start at 600 tokens, 500–800 is the working range.** Too large: one embedding averages the whole
  chunk, so the relevant passage drowns in the irrelevant majority — retrieval precision drops. Too
  small: the LLM gets fragments without the surrounding context it needs to answer — answers get
  vague even when retrieval hits.
- **Chunk size is bounded above by the embedding model's context window.** Tokens past the window are
  silently truncated before embedding — a 2000-token chunk into `mxbai-embed-large` (512) embeds only
  its first quarter. Keep chunks well under the window; the bound is the model, not the document.
- **`TokenTextSplitter` has no overlap knob** (builder: `withChunkSize`, `withMinChunkSizeChars`,
  `withMinChunkLengthToEmbed`, `withMaxNumChunks`, `withKeepSeparator`, `withEncodingType`,
  `withPunctuationMarks`). Sentence-continuity overlap (the classic 10–15%) requires a custom
  splitter or a post-pass that carries the tail of chunk *n* into chunk *n+1*. `withKeepSeparator(true)`
  plus sensible boundaries covers most corpus types; reach for custom overlap only when answers
  visibly break at chunk edges.
- `withMinChunkSizeChars(350)` discards trailing runt chunks (a dangling header line) that would
  embed as noise.
- `vectorStore.add(chunks)` batches internally; `max-document-batch-size` bounds each round trip.
  For multi-MB documents, ingest in a background thread and report progress — the HTTP request
  should not wait for 500 embedding calls.

---

## Advisors: which one

| | `QuestionAnswerAdvisor` | `RetrievalAugmentationAdvisor` |
|---|---|---|
| Artifact | `spring-ai-vector-store-advisor` | `spring-ai-rag` (add it) |
| Model | retrieve → stuff prompt → answer | pipeline: transform query → retrieve → join → post-process → augment |
| Use when | default; single vector store | need query rewriting/expansion, multi-store, reranking, empty-context handling |

Modular path:

```java
var retriever = VectorStoreDocumentRetriever.builder()
    .vectorStore(vectorStore)
    .similarityThreshold(0.7)
    .topK(6)
    .build();

var advisor = RetrievalAugmentationAdvisor.builder()
    .documentRetriever(retriever)
    .queryAugmenter(ContextualQueryAugmenter.builder()
        .allowEmptyContext(false)   // refuse to answer from nothing instead of hallucinating
        .build())
    .build();
```

Per-request metadata filter (works with both advisors):

```java
chatClient.prompt()
    .advisors(a -> a.param(QuestionAnswerAdvisor.FILTER_EXPRESSION, "source == 'handbook.pdf'"))
    .user(q).call().content();
```

Filter expressions use Spring AI's DSL: `==`, `!=`, `>`, `&&`, `||`, `in` — they compile to SQL
against the `metadata` json column in PgVector.

---

## Test strategy

**No live LLM calls in CI — two deterministic substitutes:**

1. **Embedding**: a fixed-vector stub as a `@Primary` bean in the test class. Same input text → same
   vector, every run, no model server. Dimension count must match
   `spring.ai.vectorstore.pgvector.dimensions`.

```java
@TestConfiguration
static class StubEmbeddingModel {

    @Bean
    @Primary
    EmbeddingModel embeddingModel() {
        return new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                List<Embedding> out = new ArrayList<>();
                for (int i = 0; i < request.getInstructions().size(); i++) {
                    out.add(new Embedding(vector(request.getInstructions().get(i)), i));
                }
                return new EmbeddingResponse(out);
            }

            @Override
            public float[] embed(Document document) {
                return vector(document.getText());
            }

            private float[] vector(String text) {
                // deterministic 768-dim vector — same text, same vector, no model server
                float[] v = new float[768];
                int h = text.hashCode();
                for (int i = 0; i < v.length; i++) v[i] = ((h + i) % 100) / 100f;
                return v;
            }
        };
    }
}
```

Imports: `org.springframework.ai.embedding.{EmbeddingModel, EmbeddingRequest, EmbeddingResponse,
Embedding}`, `org.springframework.ai.document.Document`.

2. **Generation**: `@MockitoBean ChatModel` — stub `call(...)` to return a fixed string when the
   answer text itself is under test. Retrieval tests don't need it at all.

**Full-fidelity alternative** (local, heavier): Testcontainers Ollama.

- Dependencies: `org.testcontainers:testcontainers-ollama` (test) and
  `org.springframework.ai:spring-ai-spring-boot-testcontainers` (test) — the latter carries the
  `OllamaContainer` `@ServiceConnection` factory, so `spring.ai.ollama.base-url` wires itself.
- Pull the model inside the container before tests:
  `ollama.execInContainer("ollama", "pull", "nomic-embed-text")` in a static block.
- Minutes of model download per CI run — acceptable only if retrieval quality itself is under test.

---

## Symptom → cause → fix

| Symptom | Cause | Fix |
|---|---|---|
| `ERROR: expected 768 dimensions, not 1536` on insert | Model emits 1536, column is `vector(768)` (or reverse) | Align the three places; drop and recreate the table; re-ingest |
| Startup fails in `PgVectorSchemaValidator` | `schema-validation: true` caught a drift — working as intended | Same as above; do not silence the validator |
| Retrieval returns nothing after a model swap | Old rows embedded by the previous model — vectors not comparable | Re-ingest every document; consider a new table per model |
| Queries slow; `EXPLAIN` shows Seq Scan | HNSW index missing | Add the index (Step 4 migration or `initialize-schema`); re-check `EXPLAIN` |
| Double DDL / `extension "vector" already exists` noise | `initialize-schema: true` alongside Flyway-managed schema | One owner: Flyway migration + `initialize-schema: false` |
| `relation "vector_store" does not exist` at startup | Migration missing, or Flyway disabled in that profile | Write the migration; check `spring.flyway.enabled` per profile |
| Confident answers from irrelevant documents | No similarity floor — top-k always returns *something* | `similarityThreshold(0.7)`; tune on real questions |
| Empty answers for questions that should hit | Threshold too high, or ingest/query used different embedding models | Lower threshold in 0.05 steps; verify one embedding model everywhere |
| Ollama 404 `model "nomic-embed-text" not found` | Model never pulled | `docker compose exec ollama ollama pull nomic-embed-text` |
| Startup fails demanding an OpenAI key (Ollama profile) | Both providers on the classpath, selector unset | One provider per profile; set `spring.ai.model.chat`/`embedding` |
| Answers break off mid-thought at chunk edges | Chunks split mid-sentence; no overlap | `withKeepSeparator(true)`; consider custom overlap (above) |
| Huge docs time out on `/rag/ingest` | Embedding is synchronous per batch | Ingest async; raise `max-document-batch-size` deliberately |
| CI flakes / surprise API bills | Tests calling a real LLM | Stub `EmbeddingModel`, `@MockitoBean ChatModel` (above) |
