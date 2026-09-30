---
name: spring-ai-chat
description: "Add LLM chat to an existing Spring Boot 4 Maven project with Spring AI — ChatClient with system prompting, structured output to records, @Tool tool calling, Ollama or OpenAI. Use when asked to add an AI chat endpoint or call an LLM. Not for document Q&A — use spring-ai-rag. Not for exposing tools to agents — use mcp-server."
---

# Spring AI Chat Skill

Adds direct LLM interaction to an existing Spring Boot 4 project: a `ChatClient` endpoint,
structured output, and tool calling. Document retrieval is a different skill (`spring-ai-rag`).

`SKILL_DIR` = directory containing this SKILL.md file.

---

## Step 0 — Gather inputs

| Field | Required | Notes |
|-------|----------|-------|
| `provider` | No | `ollama` (default, local) or `openai` |
| `model` | No | `qwen3:4b` for Ollama, `gpt-5-mini` for OpenAI |
| `useCase` | Yes | what the chat endpoint is for (drives system prompt and tools) |

For OpenAI the user needs `OPENAI_API_KEY` in the environment. Default to Ollama when unsure.

Next: dependencies.

---

## Step 1 — Dependencies

Add the BOM to `pom.xml` `<dependencyManagement>` (skip if `spring-ai-rag` already added it):

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-bom</artifactId>
    <version>2.0.1</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

Then one starter, by provider:

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-ollama</artifactId>
</dependency>
<!-- or -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

Before writing, confirm `2.0.1` is still the latest:

```bash
curl -s "https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/maven-metadata.xml" \
  | python3 -c "import sys,re; print(re.findall(r'<version>(.*?)</version>', sys.stdin.read())[-1])"
```

Ollama runs via Testcontainers in tests (`ollama/ollama:0.35.0`) — verify the tag before writing:

```bash
curl -s "https://hub.docker.com/v2/repositories/ollama/ollama/tags/0.35.0" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['name'], d['last_updated'][:10])"
```

Next: the chat service.

---

## Step 2 — ChatClient with a system prompt

One `ChatClient` per use case, built with its system prompt — not a shared raw `ChatModel`:

```java
package com.example.support;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
class SupportChatService {

    private final ChatClient chat;

    SupportChatService(ChatClient.Builder builder) {
        this.chat = builder
                .defaultSystem("You are the support assistant for an order service. "
                        + "Answer briefly. If you do not know, say so.")
                .build();
    }

    String ask(String question) {
        return chat.prompt().user(question).call().content();
    }
}
```

Rules:
- System prompt lives at client construction, not concatenated into every user message.
- One service per use case; never inject `ChatModel` directly.

Next: structured output.

---

## Step 3 — Structured output

Map responses straight to a record:

```java
record Triage(String category, int urgency, String reply) {}

Triage triage(String message) {
    return chat.prompt().user(message).call().entity(Triage.class);
}
```

If the record has no usable constructor mapping (nested generics), add `@JsonPropertyDescription`
on the record components so the model gets a real schema.

Next: tool calling.

---

## Step 4 — Tool calling

Expose application methods as tools the model can call:

```java
package com.example.support;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
class OrderTools {

    private final OrderRepository orders;

    OrderTools(OrderRepository orders) {
        this.orders = orders;
    }

    @Tool(description = "Look up an order's status by its id")
    String orderStatus(long orderId) {
        return orders.findById(orderId)
                .map(o -> "status: " + o.getStatus())
                .orElse("no order with id " + orderId);
    }
}
```

Wire it onto the client call:

```java
chat.prompt().user(question).tools(orderTools).call().content();
```

Rules:
- Tool descriptions tell the model WHEN to call, not just what it does.
- Tools are read-only by default here; a tool that mutates state needs the user's explicit sign-off.
- Return plain strings with the answer embedded — exceptions become confusing model input.

Next: test it.

---

## Step 5 — Test

Unit-test the service with a stubbed `ChatClient` (no model call in CI). For a live smoke test,
run Ollama via the compose service or Testcontainers — the same pattern `spring-ai-rag` uses, so
apply its container setup rather than duplicating it here.

Assert behavior, not prose: structured output deserializes, tool gets called when the question
requires it (assert repository interaction), unknown questions get an "I don't know".

Next: report.

---

## Step 6 — Report

Report:

- Provider and model chosen, starter added (verified version)
- Services created and their system prompts
- Records used for structured output
- Tools exposed, and confirmation each is read-only
- Test approach and result
- Next step: if the answers need your documents, apply `spring-ai-rag`; if external agents should
  call these tools, apply `mcp-server`.
