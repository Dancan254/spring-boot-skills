# Kafka conventions

Event-driven patterns with Spring Kafka on Spring Boot 4.

---

## Topic naming

Use past-tense, domain-event style names:

- `job.created`
- `job.updated`
- `application.received`
- `payment.processed`

Avoid command-style names (`createJob`) and generic names (`events`).

---

## Consumer groups

- One consumer group per application/service.
- Multiple instances of the same service share the group ID for horizontal scaling.
- Different services that need the same event use different group IDs.

Default group ID: the application name from `spring.application.name`.

---

## Serialization

- Keys: `StringSerializer` / `StringDeserializer`.
- Values: `JsonSerializer` / `JsonDeserializer`.
- Trust package must include the base package: `spring.json.trusted.packages`.

For Avro or schema registry, use a dedicated skill.

---

## Idempotency

Every consumer should be idempotent. Strategies:

1. **Natural idempotency:** the operation is safe to repeat (e.g. updating a status to the same value).
2. **Idempotency key:** store processed event IDs and skip duplicates.
3. **Database unique constraints:** rely on a unique business key in the event.

Never assume "exactly-once" without an explicit design.

---

## Retry and dead-letter topics

Use `@RetryableTopic` for transient failures:

```java
@RetryableTopic(
    attempts = "3",
    backoff = @Backoff(delay = 1000, multiplier = 2),
    include = {TransientDataAccessException.class},
    dltStrategy = DltStrategy.FAIL_ON_ERROR
)
```

- Retry only transient errors (network, DB lock timeout).
- Send poison messages to the DLT after retries.
- Monitor DLT lag and alert.

---

## Producer reliability

Always set on producers:

- `acks=all`
- `enable.idempotence=true`
- `retries=3` (or rely on idempotency)

This gives at-least-once delivery with ordering preserved per partition.

---

## Testing

| Layer | Approach |
|-------|----------|
| Producer unit | Mock `KafkaTemplate` |
| Consumer unit | Mock listener method args |
| Integration | `Testcontainers` Kafka container + real template/consumer |

Use Awaitility for asynchronous consumer assertions. Never `Thread.sleep()` in tests.

---

## Container class

Testcontainers 2.x uses `org.testcontainers.kafka.KafkaContainer` from
`org.testcontainers:testcontainers-kafka`. The old `org.testcontainers.containers.KafkaContainer` is
a deprecated shim.

```java
@Bean
@ServiceConnection
KafkaContainer kafkaContainer() {
    return new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));
}
```

Declared in `IntegrationTestContainers`; `@ServiceConnection` wires `spring.kafka.bootstrap-servers`.
