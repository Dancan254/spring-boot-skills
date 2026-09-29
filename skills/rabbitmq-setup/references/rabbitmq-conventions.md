# RabbitMQ conventions

AMQP patterns with Spring AMQP on Spring Boot 4.

---

## Exchange and queue naming

- Exchanges: `domain.events` (e.g. `job.events`, `order.events`).
- Queues: `event.queue` or `service.event.queue` when multiple consumers exist
  (e.g. `notifications.job.created.queue`).
- Routing keys: `entity.action` (e.g. `job.created`, `order.shipped`).

Avoid generic names like `events` or `queue`.

---

## Exchange types

| Type | Use case |
|------|----------|
| `topic` | Default for event-driven routing with wildcard patterns |
| `direct` | Command-style routing to a specific queue |
| `fanout` | Broadcast to all bound queues |

Use `topic` unless you have a specific reason not to.

---

## Dead-letter exchange (DLX)

Every durable queue should declare a DLX:

```java
QueueBuilder.durable("job.created.queue")
    .withArgument("x-dead-letter-exchange", "job.events.dlx")
    .withArgument("x-message-ttl", 30000)
    .build();
```

Rules:
- The DLX is a separate exchange, usually of the same type as the main exchange.
- The DLQ uses the same routing key as the original message.
- Set `default-requeue-rejected: false` so failed messages go to the DLX instead of looping.
- Monitor DLQ length and alert.

---

## Message conversion

- Use `Jackson2JsonMessageConverter` for JSON payloads.
- Configure it on both `RabbitTemplate` and the listener container factory.
- Trust the type by whitelisting packages, or use event records with a `__TypeId__` header.

---

## Idempotency

Every consumer must be idempotent. Strategies:

1. Natural idempotency (safe to repeat).
2. Store processed message IDs and skip duplicates.
3. Rely on a unique business key in the database.

Never assume exactly-once delivery without an explicit design.

---

## Concurrency

Start with `concurrency: 1` and `max-concurrency: 5`. Increase based on observed throughput and
ordering requirements. Higher concurrency removes ordering guarantees within a queue.

---

## Testing

| Layer | Approach |
|-------|----------|
| Publisher unit | Mock `RabbitTemplate` |
| Consumer unit | Mock listener method args |
| Integration | `RabbitMQContainer` + real template/consumer |

Use Awaitility for asynchronous assertions. Never `Thread.sleep()` in tests.

---

## Container class

Testcontainers 2.x uses `org.testcontainers.rabbitmq.RabbitMQContainer` from
`org.testcontainers:testcontainers-rabbitmq`.

```java
@Bean
@ServiceConnection
RabbitMQContainer rabbitmqContainer() {
    return new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management-alpine"));
}
```

Declared in `IntegrationTestContainers`; `@ServiceConnection` wires `spring.rabbitmq.*`.
