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
| Redis | `redis:8.10.2-alpine` | `com.redis.testcontainers.RedisContainer` (`com.redis:testcontainers-redis`) | automatic, matched by type |
| Kafka | `apache/kafka:4.3.1` | `org.testcontainers.kafka.KafkaContainer` | automatic |
| RabbitMQ | `rabbitmq:4-management-alpine` | `org.testcontainers.rabbitmq.RabbitMQContainer` | automatic |
| MongoDB | `mongo:8.3.11` | `org.testcontainers.mongodb.MongoDBContainer` | automatic |
| AWS via Floci (S3, SQS, DynamoDB…) | `floci/floci:2.1.0` | `io.floci.testcontainers.FlociContainer` | automatic for Spring Cloud AWS clients, via `spring-boot-testcontainers-floci` |
| Grafana LGTM | `grafana/otel-lgtm:0.34.0` | `org.testcontainers.grafana.LgtmStackContainer` | automatic (metrics + traces + logs) |

Boot 4.1 ships the matching connection-details factories in the feature module, not in
`spring-boot-testcontainers` — `spring-boot-data-redis` carries `RedisContainerConnectionDetailsFactory`,
`spring-boot-kafka` carries `ApacheKafkaContainerConnectionDetailsFactory` (plus a Confluent one), and
`spring-boot-amqp` carries `RabbitContainerConnectionDetailsFactory`. Floci's factory is third-party and
lives in `io.floci:spring-boot-testcontainers-floci`. If `@ServiceConnection` does nothing, the feature
starter (or that module) is missing, not the container.

---

## Snippets

**Postgres — the default, in `BaseIntegrationTest`:**

```java
@Container
@ServiceConnection
static PostgreSQLContainer postgres =
    new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
```

**Redis** — Testcontainers 2.x has no official Redis module; use Redis's own
`com.redis:testcontainers-redis` (not in Boot's BOM, so pin it). Boot's
`RedisContainerConnectionDetailsFactory` accepts `RedisContainer` by type, so `@ServiceConnection`
works even when the image comes from a mirror — a `GenericContainer` only matches on the image name.
This is the same container `redis-setup` uses.

```xml
<dependency>
    <groupId>com.redis</groupId>
    <artifactId>testcontainers-redis</artifactId>
    <version>2.2.4</version>
    <scope>test</scope>
</dependency>
```

```java
@Container
@ServiceConnection
static RedisContainer redis =
    new RedisContainer(DockerImageName.parse("redis:8.10.2-alpine"));
```

**Kafka** — `KafkaContainer` is the KRaft `apache/kafka` image; `ConfluentKafkaContainer` is the
`confluentinc/cp-kafka` one. Use Apache unless the project already depends on Confluent tooling:

```java
@Container
@ServiceConnection
static KafkaContainer kafka =
    new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));
```

**AWS (S3, SQS, SNS, DynamoDB…)** — use Floci, not LocalStack. LocalStack retired its Community image
in March 2026 and now needs an auth token on every run; Floci is MIT-licensed, token-free, and serves
the same port (4566) with one container for every service. Two test-scoped dependencies, neither in
Boot's BOM, so pin both to the same release:

```xml
<dependency>
    <groupId>io.floci</groupId>
    <artifactId>testcontainers-floci</artifactId>
    <version>2.16.1</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>io.floci</groupId>
    <artifactId>spring-boot-testcontainers-floci</artifactId>
    <version>2.16.1</version>
    <scope>test</scope>
</dependency>
```

```java
@Container
@ServiceConnection
static FlociContainer floci =
    new FlociContainer(DockerImageName.parse("floci/floci:2.1.0"));
```

`@ServiceConnection` sets endpoint, region, and credentials on Spring Cloud AWS clients (`S3Client`,
`SqsAsyncClient`, …). Never use the no-arg constructor — it silently pulls the `latest` tag. If the
project builds raw AWS SDK clients instead of Spring Cloud AWS, drop the Spring module and point the
client at `floci.getEndpoint()` / `getRegion()` / `getAccessKey()` / `getSecretKey()` with
`forcePathStyle(true)` for S3.

Keep LocalStack only when a project already depends on it or needs emulation Floci stubs (Textract,
Transcribe); its auth token then goes in a CI secret, never in the repo.

Verify both pins before writing:

```bash
curl -s "https://hub.docker.com/v2/repositories/floci/floci/tags?page_size=50&ordering=last_updated" \
  | python3 -c "import json,sys,re; print([t['name'] for t in json.load(sys.stdin)['results'] if re.fullmatch(r'[\d.]+', t['name'])][:3])"
curl -s "https://repo1.maven.org/maven2/io/floci/spring-boot-testcontainers-floci/maven-metadata.xml" \
  | grep -oE '<release>[^<]+'
```

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
