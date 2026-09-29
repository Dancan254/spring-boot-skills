---
name: kafka-setup
description: "Add Apache Kafka producers and consumers to an existing Spring Boot 4 Maven project — Spring Kafka config, JSON events, dead-letter topics, Testcontainers tests, compose wiring. Use when asked to add Kafka, Kafka topics, or event streaming. If messaging is requested without naming a broker, ask Kafka or RabbitMQ first."
---

# Kafka Setup Skill

Adds event-driven messaging to an existing Spring Boot 4 project using Spring Kafka and
Testcontainers.

`SKILL_DIR` = directory containing this SKILL.md file.

Load `SKILL_DIR/references/kafka-conventions.md` before writing producers/consumers — it covers
topic naming, serialization, consumer groups, idempotency, retry/DLT patterns, and the Testcontainers
Kafka class.

---

## Step 0 — Gather inputs

If the user asked for messaging without naming a broker, ask Kafka or RabbitMQ before going further.

| Field | Required | Notes |
|-------|----------|-------|
| `topics` | Yes | list of topics, e.g. `job.created`, `application.received` |
| `consumerGroup` | No | default: `<app-name>` read from `pom.xml` |
| `schema` | No | `json` (default) or `avro` — this skill covers JSON only |
| `retryTopic` | No | `true` (default) — add `@RetryableTopic` DLT handling |

---

## Step 1 — Read the project

```bash
cat pom.xml
find src/main/java -name '*Controller.java' | head -10
ls src/test/java
```

Confirm Spring Boot 4.x. If Avro is requested, stop — this skill is JSON-only.

---

## Step 2 — Add dependencies

Add to `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-kafka</artifactId>
    <scope>test</scope>
</dependency>
```

Spring Boot 4.1's BOM pins Spring Kafka and Testcontainers 2.x. Never override
`testcontainers.version` or `spring-kafka.version` by hand.

---

## Step 3 — Kafka config

Create `src/main/java/<package>/shared/config/KafkaConfig.java`:

```java
package <package>.shared.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    public NewTopic jobCreatedTopic() {
        return TopicBuilder.name("job.created")
            .partitions(3)
            .replicas(1)
            .build();
    }
}
```

Create one `NewTopic` bean per topic in `topics`. Adjust partitions to 3 by default; raise for
high-throughput topics.

---

## Step 4 — Producer service

Create `src/main/java/<package>/shared/kafka/KafkaProducerService.java`:

```java
package <package>.shared.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class KafkaProducerService {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaProducerService(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void send(String topic, Object payload) {
        kafkaTemplate.send(topic, payload);
    }

    public void send(String topic, String key, Object payload) {
        kafkaTemplate.send(topic, key, payload);
    }
}
```

Use domain-specific wrappers rather than calling `KafkaProducerService` directly from controllers.
Example: `JobEventPublisher.publishJobCreated(jobResponse)`.

---

## Step 5 — Consumer template

Create `src/main/java/<package>/job/JobEventConsumer.java` as an example:

```java
package <package>.job;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
public class JobEventConsumer {

    @KafkaListener(topics = "job.created", groupId = "${spring.kafka.consumer.group-id}")
    public void handleJobCreated(@Payload JobCreatedEvent event) {
        // idempotent handler
    }
}
```

If `retryTopic` is true, add `@RetryableTopic`:

```java
@RetryableTopic(
    attempts = "3",
    backoff = @Backoff(delay = 1000, multiplier = 2),
    include = {RetryableException.class},
    dltStrategy = DltStrategy.FAIL_ON_ERROR
)
@KafkaListener(topics = "job.created", groupId = "${spring.kafka.consumer.group-id}")
public void handleJobCreated(@Payload JobCreatedEvent event) {
}
```

Enable retry topics with `@EnableKafkaRetry` on a config class.

---

## Step 6 — Event records

Create event records in the domain package, e.g. `src/main/java/<package>/job/JobCreatedEvent.java`:

```java
package <package>.job;

import java.time.Instant;

public record JobCreatedEvent(Long jobId, String title, Instant occurredAt) {
}
```

Event records should be versioned. Include a `schemaVersion` field if events are stored long-term.

---

## Step 7 — Update application.yml

Add:

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      group-id: ${KAFKA_CONSUMER_GROUP_ID:<app-name>}
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "<package>"
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all
      retries: 3
      properties:
        enable.idempotence: true
```

Replace `<package>` with the project's base package.

---

## Step 8 — Testcontainers base test

If `BaseIntegrationTest` exists, add a Kafka bean to the `IntegrationTestContainers` configuration it
imports:

```java
@Bean
@ServiceConnection
KafkaContainer kafkaContainer() {
    return new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));
}
```

`KafkaContainer` is `org.testcontainers.kafka.KafkaContainer`; Boot's
`ApacheKafkaContainerConnectionDetailsFactory` sets `spring.kafka.bootstrap-servers`.

Tests extend `BaseIntegrationTest` as before — no separate base class, no
`@DynamicPropertySource`, and no `static {}` start. If the project's base class still declares a
`static @Container`, move that container into `IntegrationTestContainers` first (see
`spring-testing` Step 3): the JUnit extension stops it after the first test class while Spring keeps
the cached context.

Before writing, confirm `4.3.1` is still current:

```bash
curl -s "https://hub.docker.com/v2/repositories/apache/kafka/tags?page_size=5&ordering=last_updated" \
  | python3 -c "import json,sys; print([t['name'] for t in json.load(sys.stdin)['results']])"
```

If `BaseIntegrationTest` does not exist, create one following `spring-scaffold` first.

---

## Step 9 — Write tests

Create `src/test/java/<package>/job/JobEventConsumerIntegrationTest.java`:

```java
package <package>.job;

import <package>.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;

import static org.awaitility.Awaitility.await;

class JobEventConsumerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void should_consume_job_created_event() {
        kafkaTemplate.send("job.created", new JobCreatedEvent(1L, "Backend Engineer", Instant.now()));

        await().atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> {
                // assert side effect or spy on handler
            });
    }
}
```

---

## Step 10 — docker-compose service (optional)

If `docker-compose.yml` exists, add a Kafka service:

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

---

## Step 11 — Run and report

```bash
./mvnw test
```

Report:

- Dependencies added
- `KafkaConfig`, `KafkaProducerService`, example consumer/event created
- Topics and consumer group configured
- Whether DLT/retry was added
- Test result summary
- Next step: add `api-design` to document event schemas or `spring-security` to secure event-driven flows
