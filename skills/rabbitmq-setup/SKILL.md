---
name: rabbitmq-setup
description: "Add RabbitMQ producers and consumers to an existing Spring Boot 4 Maven project — Spring AMQP config, JSON messages, dead-letter exchanges, Testcontainers tests, compose wiring. Use when asked to add RabbitMQ, AMQP, or work queues. If messaging is requested without naming a broker, ask Kafka or RabbitMQ first."
---

# RabbitMQ Setup Skill

Adds AMQP messaging to an existing Spring Boot 4 project using Spring AMQP and Testcontainers.

`SKILL_DIR` = directory containing this SKILL.md file.

Load `SKILL_DIR/references/rabbitmq-conventions.md` before writing producers/consumers — it covers
exchange/queue naming, routing keys, TTL/DLX patterns, and the Testcontainers RabbitMQ class.

---

## Step 0 — Gather inputs

If the user asked for messaging without naming a broker, ask Kafka or RabbitMQ before going further.

| Field | Required | Notes |
|-------|----------|-------|
| `exchanges` | Yes | list of exchanges, e.g. `job.events` |
| `queues` | Yes | list of queues with bindings, e.g. `job.created.queue` bound to `job.events` with rk `job.created` |
| `dlx` | No | `true` (default) — add dead-letter exchange and TTL retry queues |

---

## Step 1 — Read the project

```bash
cat pom.xml
find src/main/java -name '*Controller.java' | head -10
ls src/test/java
```

Confirm Spring Boot 4.x and a web dependency.

---

## Step 2 — Add dependencies

Add to `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-rabbitmq</artifactId>
    <scope>test</scope>
</dependency>
```

Spring Boot 4.1's BOM pins Spring AMQP and Testcontainers 2.x. Never override
`testcontainers.version` or `spring-amqp.version` by hand.

---

## Step 3 — RabbitMQ config

Create `src/main/java/<package>/shared/config/RabbitmqConfig.java`:

```java
package <package>.shared.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitmqConfig {

    public static final String JOB_EVENTS_EXCHANGE = "job.events";
    public static final String JOB_CREATED_QUEUE = "job.created.queue";
    public static final String JOB_CREATED_DLQ = "job.created.dlq";
    public static final String JOB_EVENTS_DLX = "job.events.dlx";

    @Bean
    public TopicExchange jobEventsExchange() {
        return ExchangeBuilder.topicExchange(JOB_EVENTS_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue jobCreatedQueue() {
        return QueueBuilder.durable(JOB_CREATED_QUEUE)
            .withArgument("x-dead-letter-exchange", JOB_EVENTS_DLX)
            .withArgument("x-message-ttl", 30000)
            .build();
    }

    @Bean
    public Binding jobCreatedBinding() {
        return BindingBuilder
            .bind(jobCreatedQueue())
            .to(jobEventsExchange())
            .with("job.created");
    }

    @Bean
    public TopicExchange jobEventsDlx() {
        return ExchangeBuilder.topicExchange(JOB_EVENTS_DLX).durable(true).build();
    }

    @Bean
    public Queue jobCreatedDlq() {
        return QueueBuilder.durable(JOB_CREATED_DLQ).build();
    }

    @Bean
    public Binding jobCreatedDlqBinding() {
        return BindingBuilder
            .bind(jobCreatedDlq())
            .to(jobEventsDlx())
            .with("job.created");
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new Jackson2JsonMessageConverter());
        return template;
    }
}
```

Adjust exchange type (`direct`, `topic`, `fanout`) per domain need. Topic is the default for
event-driven routing.

---

## Step 4 — Producer service

Create `src/main/java/<package>/shared/rabbitmq/RabbitmqPublisher.java`:

```java
package <package>.shared.rabbitmq;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
public class RabbitmqPublisher {

    private final RabbitTemplate rabbitTemplate;

    public RabbitmqPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(String exchange, String routingKey, Object payload) {
        rabbitTemplate.convertAndSend(exchange, routingKey, payload);
    }
}
```

Wrap in domain publishers: `JobEventPublisher.publishJobCreated(JobCreatedEvent event)`.

---

## Step 5 — Consumer template

Create `src/main/java/<package>/job/JobEventConsumer.java`:

```java
package <package>.job;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
public class JobEventConsumer {

    @RabbitListener(queues = RabbitmqConfig.JOB_CREATED_QUEUE)
    public void handleJobCreated(@Payload JobCreatedEvent event) {
        // idempotent handler
    }
}
```

For retries with DLX: the queue declaration above already routes failed messages to the DLX after
TTL. For explicit NACK to DLX, throw `AmqpRejectAndDontRequeueException`.

---

## Step 6 — Event records

Create event records in the domain package:

```java
package <package>.job;

import java.time.Instant;

public record JobCreatedEvent(Long jobId, String title, Instant occurredAt) {
}
```

Event records should be versioned for long-term storage. Include `schemaVersion` if events persist.

---

## Step 7 — Update application.yml

Add:

```yaml
spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASSWORD:guest}
    listener:
      simple:
        default-requeue-rejected: false
        concurrency: 1
        max-concurrency: 5
```

`default-requeue-rejected: false` ensures failed messages go to the DLX instead of being requeued
indefinitely.

---

## Step 8 — Testcontainers base test

If `BaseIntegrationTest` exists, add a RabbitMQ bean to the `IntegrationTestContainers` configuration
it imports:

```java
@Bean
@ServiceConnection
RabbitMQContainer rabbitmqContainer() {
    return new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management-alpine"));
}
```

`RabbitMQContainer` is `org.testcontainers.rabbitmq.RabbitMQContainer`; Boot's
`RabbitContainerConnectionDetailsFactory` sets host, port, and credentials.

Tests extend `BaseIntegrationTest` as before — no separate base class, no
`@DynamicPropertySource`, and no `static {}` start. If the project's base class still declares a
`static @Container`, move that container into `IntegrationTestContainers` first (see
`spring-testing` Step 3): the JUnit extension stops it after the first test class while Spring keeps
the cached context.

Before writing, confirm `4-management-alpine` is still current:

```bash
curl -s "https://hub.docker.com/v2/repositories/library/rabbitmq/tags/4-management-alpine" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['name'], d['last_updated'][:10])"
```

If `BaseIntegrationTest` does not exist, create one following `spring-scaffold` first.

---

## Step 9 — Write tests

Create `src/test/java/<package>/job/JobEventConsumerIntegrationTest.java`:

```java
package <package>.job;

import <package>.BaseIntegrationTest;
import <package>.shared.config.RabbitmqConfig;
import <package>.shared.rabbitmq.RabbitmqPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.awaitility.Awaitility.await;

class JobEventConsumerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private RabbitmqPublisher publisher;

    @Test
    void should_consume_job_created_event() {
        publisher.publish(RabbitmqConfig.JOB_EVENTS_EXCHANGE, "job.created",
            new JobCreatedEvent(1L, "Backend Engineer", Instant.now()));

        await().atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> {
                // assert side effect or spy on handler
            });
    }
}
```

---

## Step 10 — docker-compose service (optional)

If `docker-compose.yml` exists, add a RabbitMQ service:

```yaml
rabbitmq:
  image: rabbitmq:4-management-alpine
  ports:
    - "5672:5672"
    - "15672:15672"
  environment:
    RABBITMQ_DEFAULT_USER: ${RABBITMQ_USER:-guest}
    RABBITMQ_DEFAULT_PASS: ${RABBITMQ_PASSWORD:-guest}
  volumes:
    - rabbitmq_data:/var/lib/rabbitmq
  healthcheck:
    test: ["CMD", "rabbitmq-diagnostics", "ping"]
    interval: 10s
    timeout: 5s
    retries: 5
```

Add `rabbitmq_data:` to the top-level `volumes` block.

---

## Step 11 — Run and report

```bash
./mvnw test
```

Report:

- Dependencies added
- `RabbitmqConfig`, `RabbitmqPublisher`, example consumer/event created
- Exchanges, queues, bindings, and DLX configured
- Test result summary
- Next step: add `api-design` to document event schemas or `spring-security` to secure AMQP flows
