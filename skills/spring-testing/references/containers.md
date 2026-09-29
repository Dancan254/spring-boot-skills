# Testcontainers reference — 2.x on Spring Boot 4

Single source of truth for container versions, artifact coordinates, and class packages.
Re-run the checks in "Version guard" before trusting the pins.

---

## Version guard

**Never pin `testcontainers.version` by hand.** The Spring Boot BOM owns it — Boot 4.1.1 pins
Testcontainers **2.0.5**. Overriding it decouples the containers from Boot's
`@ServiceConnection` factories, which is how projects end up half on 1.x.

Confirm what the project actually resolves:

```bash
./mvnw -q dependency:list -Dincludes=org.testcontainers | grep testcontainers
```

Confirm the BOM's pin is still the newest release:

```bash
curl -s "https://repo1.maven.org/maven2/org/testcontainers/testcontainers/maven-metadata.xml" \
  | python3 -c "import sys,re; print(re.findall(r'<version>(.*?)</version>', sys.stdin.read())[-1])"
```

If the newest release is ahead of the BOM, that's a **Boot upgrade**, not a version override.

Docker tags drift faster than anything else here:

```bash
# any Docker Hub official image
curl -s "https://hub.docker.com/v2/repositories/library/postgres/tags/18-alpine" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['name'], d['last_updated'][:10])"
# newest tags for a vendor image
curl -s "https://hub.docker.com/v2/repositories/grafana/otel-lgtm/tags?page_size=5&ordering=last_updated" \
  | python3 -c "import json,sys; print([t['name'] for t in json.load(sys.stdin)['results']])"
```

---

## The 1.x → 2.x trap

Testcontainers 2.0 renamed **every** module artifact with a `testcontainers-` prefix and moved
each container class out of `org.testcontainers.containers` into its own package. The old
coordinates still resolve — they just stop at **1.21.4** forever. A pom carrying an old
coordinate compiles, runs, and silently sits a major version behind, which is exactly how a
project "using the previous versions" looks.

| 1.x coordinate (dead end at 1.21.4) | 2.x coordinate | Class in 2.x |
|---|---|---|
| `org.testcontainers:junit-jupiter` | `org.testcontainers:testcontainers-junit-jupiter` | `org.testcontainers.junit.jupiter.{Testcontainers,Container}` (unchanged) |
| `org.testcontainers:postgresql` | `org.testcontainers:testcontainers-postgresql` | `org.testcontainers.postgresql.PostgreSQLContainer` |
| `org.testcontainers:kafka` | `org.testcontainers:testcontainers-kafka` | `org.testcontainers.kafka.KafkaContainer` · `ConfluentKafkaContainer` |
| `org.testcontainers:rabbitmq` | `org.testcontainers:testcontainers-rabbitmq` | `org.testcontainers.rabbitmq.RabbitMQContainer` |
| `org.testcontainers:mongodb` | `org.testcontainers:testcontainers-mongodb` | `org.testcontainers.mongodb.MongoDBContainer` |
| `org.testcontainers:localstack` | `org.testcontainers:testcontainers-localstack` | `org.testcontainers.localstack.LocalStackContainer` |
| `org.testcontainers:grafana` | `org.testcontainers:testcontainers-grafana` | `org.testcontainers.grafana.LgtmStackContainer` |
| `org.testcontainers:mysql` | `org.testcontainers:testcontainers-mysql` | `org.testcontainers.mysql.MySQLContainer` |
| `org.testcontainers:elasticsearch` | `org.testcontainers:testcontainers-elasticsearch` | `org.testcontainers.elasticsearch.ElasticsearchContainer` |

Detect the trap in one line:

```bash
grep -n -A1 'org.testcontainers' pom.xml | grep -E '<artifactId>(junit-jupiter|postgresql|kafka|rabbitmq|mongodb|localstack|grafana|mysql|elasticsearch)</artifactId>'
```

Any hit is a 1.x leftover — replace the coordinate, then delete the `org.testcontainers.containers.*`
import it was feeding.

**What did NOT move** — leave these imports alone:

- `org.testcontainers.junit.jupiter.Testcontainers` / `.Container`
- `org.testcontainers.utility.DockerImageName`
- `org.testcontainers.containers.GenericContainer`

**Three shape changes in 2.x:**

1. Container classes are no longer self-generic — write `PostgreSQLContainer`, never `PostgreSQLContainer<?>`.
2. The `String` constructor is gone from the house pattern; construct with `DockerImageName.parse(...)`.
3. The deprecated `org.testcontainers.containers.*` shims still ship in the 2.x module jars, so a
   stale import compiles. Grep for it rather than trusting the build to fail.

---

## Pinned images

Never `:latest` — Initializr writes `:latest` into the generated `TestcontainersConfiguration`, and
that is a bug to fix on sight.

| Service | Image | Container class | `@ServiceConnection` |
|---|---|---|---|
| PostgreSQL | `postgres:18-alpine` | `org.testcontainers.postgresql.PostgreSQLContainer` | automatic |
| Redis | `redis:8.8.3-alpine` | `org.testcontainers.containers.GenericContainer` (no Redis module exists) | matched by image name; add `name = "redis"` if pulled from a mirror |
| Kafka | `apache/kafka:4.3.1` | `org.testcontainers.kafka.KafkaContainer` | automatic |
| RabbitMQ | `rabbitmq:4-management-alpine` | `org.testcontainers.rabbitmq.RabbitMQContainer` | automatic |
| MongoDB | `mongo:8` | `org.testcontainers.mongodb.MongoDBContainer` | automatic |
| LocalStack (S3, SQS…) | `localstack/localstack:4` | `org.testcontainers.localstack.LocalStackContainer` | automatic |
| Grafana LGTM | `grafana/otel-lgtm:0.33.1` | `org.testcontainers.grafana.LgtmStackContainer` | automatic (metrics + traces + logs) |

Boot 4.1 ships the matching connection-details factories in the feature module, not in
`spring-boot-testcontainers` — `spring-boot-data-redis` carries `RedisContainerConnectionDetailsFactory`,
`spring-boot-kafka` carries `ApacheKafkaContainerConnectionDetailsFactory` (plus a Confluent one), and
`spring-boot-amqp` carries `RabbitContainerConnectionDetailsFactory`. If `@ServiceConnection` does
nothing, the feature starter is missing, not the container.

---

## Snippets

**Postgres — the default, in `BaseIntegrationTest`:**

```java
@Container
@ServiceConnection
static PostgreSQLContainer postgres =
    new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
```

**Redis** — there is no Redis module in Testcontainers 2.x; a `GenericContainer` is the supported
path and Boot matches it on image name:

```java
@Container
@ServiceConnection
static GenericContainer redis =
    new GenericContainer(DockerImageName.parse("redis:8.8.3-alpine")).withExposedPorts(6379);
```

**Kafka** — `KafkaContainer` is the KRaft `apache/kafka` image; `ConfluentKafkaContainer` is the
`confluentinc/cp-kafka` one. Use Apache unless the project already depends on Confluent tooling:

```java
@Container
@ServiceConnection
static KafkaContainer kafka =
    new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));
```

**LocalStack** — one container per service set, and the client is built from its endpoint:

```java
@Container
static LocalStackContainer localstack =
    new LocalStackContainer(DockerImageName.parse("localstack/localstack:4"))
        .withServices(LocalStackContainer.Service.S3);
```

There is no `@ServiceConnection` for AWS clients — register the endpoint with
`@DynamicPropertySource` and point the SDK client at `localstack.getEndpoint()`.

**Grafana LGTM** belongs in `TestcontainersConfiguration` for `./mvnw spring-boot:test-run`, never
in `BaseIntegrationTest` — integration tests assert behaviour, not telemetry, and the image is ~1 GB.

---

## Startup cost

One static container per class, shared across its methods — that is what `@Testcontainers` +
`static @Container` buys. Two rules keep the suite fast:

- **One base class, every integration test extends it.** A second container declaration in a
  subclass starts a second database.
- **Reuse across runs is opt-in and local only.** `.testcontainers.properties` with
  `testcontainers.reuse.enable=true` plus `.withReuse(true)` keeps the container alive between
  runs on your machine. Never commit `withReuse(true)` as the default — CI has no reuse daemon and
  a reused container carries dirty state between runs.
