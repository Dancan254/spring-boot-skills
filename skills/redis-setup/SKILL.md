---
name: redis-setup
description: "Add Redis caching and rate limiting to an existing Spring Boot 4 Maven project — Spring Cache on Redis with per-cache TTLs, graceful cache failure, Redis-backed rate limiting, Testcontainers tests, compose wiring. Use when asked to add Redis, cache an endpoint or query, or rate limit an API."
---

# Redis Setup Skill

Adds Redis-backed caching and rate limiting to an existing Spring Boot 4 project — and proves the
cache actually intercepts calls before saying it's done. Scope: caching + rate limiting — this
skill does **not** cover Spring Session or Redis as a message broker.

`SKILL_DIR` = the directory containing this SKILL.md.

**Load `SKILL_DIR/references/redis-conventions.md` before writing anything** — serialization deep
dive, TTL strategy, cache patterns, stampede mitigation, and failure modes.

The default `RedisCacheManager` serializes values with JDK serialization (opaque binary blobs that
break on schema changes) and gives keys no TTL (a slow memory leak). Step 3 is the part everyone
skips, and skipping it is why "we added Redis" ends in an eviction fire drill six months later.

---

## Step 0 — Gather inputs

| Field | Required | Notes |
|-------|----------|-------|
| `caches` | Yes | cache names with TTLs, e.g. `jobs: 10m`, `job-details: 5m` |
| `defaultTtl` | No | default: `30m` — every key gets a TTL, no exceptions |
| `rateLimit` | No | `false` (default) — add Bucket4j interceptor; if true, get limits per tier |
| `cacheNullValues` | No | `false` (default) — see the reference for the trade-off |

---

## Step 1 — Read the project

```bash
grep -m1 -A1 'spring-boot-starter-parent' pom.xml
grep -n 'data-redis\|bucket4j\|testcontainers' pom.xml
grep -rn 'EnableCaching\|@Cacheable' src/main/java | head -10
ls src/main/resources/application.y*ml docker-compose.y*ml compose.y*ml 2>/dev/null
find src/test/java -name 'Base*IntegrationTest.java'
```

Confirm Spring Boot 4.x and note the base package. If Redis or caching config already exists, extend
it — do not generate a second `CacheManager` bean.

---

## Step 2 — Add dependencies

Add to `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>com.redis</groupId>
    <artifactId>testcontainers-redis</artifactId>
    <version>2.2.4</version>
    <scope>test</scope>
</dependency>
```

The starter is BOM-managed, no version. `testcontainers-redis` is not in Boot's BOM — pin it, and
confirm `2.2.4` is still the latest before writing:

```bash
curl -s "https://central.sonatype.com/solrsearch/select?q=g:com.redis+AND+a:testcontainers-redis&rows=3&core=gav" \
  | python3 -c "import json,sys; print(sorted(d['v'] for d in json.load(sys.stdin)['response']['docs']))"
```

If `rateLimit` is true, also add (not BOM-managed — pin both):

```xml
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j_jdk17-core</artifactId>
    <version>8.20.0</version>
</dependency>
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j_jdk17-lettuce</artifactId>
    <version>8.20.0</version>
</dependency>
```

Confirm `8.20.0` is still the latest `jdk17` line before writing:

```bash
curl -s "https://central.sonatype.com/solrsearch/select?q=g:com.bucket4j+AND+a:bucket4j_jdk17-lettuce&rows=3&core=gav" \
  | python3 -c "import json,sys; print(sorted(d['v'] for d in json.load(sys.stdin)['response']['docs']))"
```

---

## Step 3 — Cache config (the part everyone skips)

Create `src/main/java/<package>/shared/config/CacheConfig.java`. Imports worth noting:
`GenericJacksonJsonRedisSerializer` from `org.springframework.data.redis.serializer` (Jackson 3),
`BasicPolymorphicTypeValidator` from `tools.jackson.databind.jsontype`, `SerializationPair` from
`org.springframework.data.redis.serializer.RedisSerializationContext`.

```java
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        var typeValidator = BasicPolymorphicTypeValidator.builder()
            .allowIfSubType("<package>")   // restrict @class hints to the app's own types
            .build();

        // JDK serialization (the default) writes opaque blobs that break on record/schema changes
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(30))
            .disableCachingNullValues()
            .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer()))
            .serializeValuesWith(SerializationPair.fromSerializer(
                GenericJacksonJsonRedisSerializer.builder()
                    .enableDefaultTyping(typeValidator)
                    .build()));

        // one entry per cache from Step 0 — never a cache without a TTL
        return RedisCacheManager.builder(factory)
            .cacheDefaults(defaults)
            .withInitialCacheConfigurations(Map.of(
                "jobs", defaults.entryTtl(Duration.ofMinutes(10)),
                "job-details", defaults.entryTtl(Duration.ofMinutes(5))))
            .build();
    }

    // a cache miss is a slow request; a cache error must not be a 500
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override public void handleCacheGetError(RuntimeException e, Cache c, Object k) { warn("GET", c, k); }
            @Override public void handleCachePutError(RuntimeException e, Cache c, Object k, Object v) { warn("PUT", c, k); }
            @Override public void handleCacheEvictError(RuntimeException e, Cache c, Object k) { warn("EVICT", c, k); }
            @Override public void handleCacheClearError(RuntimeException e, Cache c) { warn("CLEAR", c, null); }

            private void warn(String op, Cache cache, Object key) {
                log.warn("Cache {} failed, proceeding without cache: cache={} key={}", op, cache.getName(), key);
            }
        };
    }
}
```

The old Jackson-2 `GenericJackson2JsonRedisSerializer` is gone in Spring Data Redis 4.x — any guide
or autocomplete that produces it is stale. Default typing embeds a `@class` hint so cached values
deserialize back to their real type instead of `LinkedHashMap`. For TTLs that must change without
redeploy, bind them with `@ConfigurationProperties` — pattern in the reference.

---

## Step 4 — Update application.yml

Add:

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 2s
```

`spring.redis.*` is dead — Boot 4 only reads `spring.data.redis.*`. Skip the host/port entirely if
the project uses `spring-boot-docker-compose` (Step 9 wires the connection itself).

---

## Step 5 — Apply caching

On the service method, not the controller:

```java
@Cacheable(cacheNames = "job-details", key = "#id")
public JobResponse getJob(Long id) { ... }

@CachePut(cacheNames = "job-details", key = "#result.id")
public JobResponse updateJob(Long id, UpdateJobRequest request) { ... }

@CacheEvict(cacheNames = "job-details", key = "#id")
public void deleteJob(Long id) { ... }
```

Silent failures to check before declaring done: self-invocation (calling the method from another
method in the same class bypasses the Spring AOP proxy — the annotation does nothing) and
non-public/final methods (not proxied). Move cached methods onto their own bean.

Cache-aside (what the abstraction gives you) is the right default. Read-through, and when to skip
caching entirely, are covered in the reference.

---

## Step 6 — Rate limiting (only if `rateLimit` is true)

Bucket4j over a hand-rolled `INCR`+`EXPIRE`: token buckets bound bursts, have no fixed-window
boundary exploit (2x the limit fires at the window edge), and the Lettuce proxy manager updates
bucket state atomically across instances.

Create `src/main/java/<package>/shared/config/RateLimitConfig.java` (`Bucket4jLettuce` is in
`io.github.bucket4j.redis.lettuce`):

```java
@Configuration
public class RateLimitConfig {

    @Bean(destroyMethod = "shutdown")
    RedisClient rateLimitRedisClient(@Value("${spring.data.redis.host:localhost}") String host,
                                     @Value("${spring.data.redis.port:6379}") int port) {
        return RedisClient.create("redis://%s:%d".formatted(host, port));
    }

    @Bean
    ProxyManager<byte[]> rateLimitProxyManager(RedisClient rateLimitRedisClient) {
        // without expiration, every client that ever calls leaves a bucket key forever
        return Bucket4jLettuce.casBasedBuilder(rateLimitRedisClient)
            .expirationAfterWrite(ExpirationAfterWriteStrategy
                .basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(10)))
            .build();
    }
}
```

Create `src/main/java/<package>/shared/web/RateLimitInterceptor.java`:

```java
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final ProxyManager<byte[]> proxyManager;

    public RateLimitInterceptor(ProxyManager<byte[]> proxyManager) {
        this.proxyManager = proxyManager;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        BucketConfiguration config = BucketConfiguration.builder()
            .addLimit(Bandwidth.builder().capacity(20).refillGreedy(20, Duration.ofMinutes(1)).build())
            .build();

        ConsumptionProbe probe = proxyManager.builder()
            .build(("rl:ip:" + request.getRemoteAddr()).getBytes(StandardCharsets.UTF_8), () -> config)
            .tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.setHeader("X-Rate-Limit-Remaining", String.valueOf(probe.getRemainingTokens()));
            return true;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(probe.getNanosToWaitForRefill() / 1_000_000_000 + 1));
        return false;
    }
}
```

Register it on a `WebMvcConfigurer` with `addPathPatterns("/api/**")` and adjust capacity/refill per
the limits from Step 0. Key by API key when present, IP as fallback — the reference covers the
two-tier shape and when to trust `X-Forwarded-For`.

---

## Step 7 — Testcontainers base test

If `BaseIntegrationTest` exists (from `spring-scaffold` or `spring-testing`), add a Redis bean to
the `IntegrationTestContainers` configuration it imports:

```java
@Bean
@ServiceConnection
RedisContainer redisContainer() {
    return new RedisContainer(DockerImageName.parse("redis:8.10.2-alpine"));
}
```

Boot's `RedisContainerConnectionDetailsFactory` matches `RedisContainer` by type and sets the host and
port.

Tests extend `BaseIntegrationTest` as before — no separate base class, no
`@DynamicPropertySource`, and no `static {}` start. If the project's base class still declares a
`static @Container`, move that container into `IntegrationTestContainers` first (see
`spring-testing` Step 3): the JUnit extension stops it after the first test class while Spring keeps
the cached context.

`RedisContainer` is `com.redis.testcontainers.RedisContainer` from the Step 2 dependency. Before
writing, confirm `8.10.2-alpine` still exists:

```bash
curl -s "https://hub.docker.com/v2/repositories/library/redis/tags/8.10.2-alpine" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['name'], d['last_updated'][:10])"
```

If `BaseIntegrationTest` does not exist, create one following `spring-scaffold` first.

---

## Step 8 — Prove the cache works

A cache nobody verified is a rumor. Create
`src/test/java/<package>/job/JobServiceCacheIntegrationTest.java`:

```java
class JobServiceCacheIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JobService jobService;

    @MockitoBean
    private JobRepository jobRepository;

    @Test
    void should_serve_second_call_from_cache() {
        when(jobRepository.findById(1L)).thenReturn(Optional.of(new Job(1L, "Backend Engineer")));

        jobService.getJob(1L);
        jobService.getJob(1L);

        verify(jobRepository, times(1)).findById(1L);
    }
}
```

Two calls in, one call through — that is the only acceptable proof. `@MockitoBean` is the Boot 4
test API (see `spring-testing`). If the assertion fails with `times(2)`, the usual suspects are
self-invocation (Step 5) or a cache name with no matching `@Cacheable`. If rate limiting was added,
add a boundary test too: fire `capacity + 5` requests, assert exactly `capacity` pass and 5 come
back 429 with a `Retry-After` header.

---

## Step 9 — docker-compose service

If `docker-compose.yml` exists, add a Redis service:

```yaml
redis:
  image: redis:8.10.2-alpine   # pinned; never :latest
  ports:
    - "6379:6379"
  command: ["redis-server", "--maxmemory", "256mb", "--maxmemory-policy", "allkeys-lru"]
  healthcheck:
    test: ["CMD", "redis-cli", "ping"]
    interval: 10s
    timeout: 3s
    retries: 5
```

`allkeys-lru` because a cache should evict cold keys under pressure, not start refusing writes.

If the project has `spring-boot-docker-compose` on the classpath, Boot detects the `redis` image
and wires `RedisConnectionDetails` itself — the Step 4 host/port becomes a fallback for running
without compose, and no `depends_on` is needed.

---

## Step 10 — Run and report

```bash
./mvnw test
```

Lead with the verdict, then the board:

```
Redis wired · cache hit verified (2 calls in, 1 through)

━━━ REDIS-SETUP ━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Starter ............... ✅ spring-boot-starter-data-redis (BOM-managed)
Serialization ......... ✅ GenericJacksonJsonRedisSerializer (Jackson 3, typed)
TTLs .................. ✅ default 30m + per-cache overrides
Failure mode .......... ✅ CacheErrorHandler logs and proceeds
Rate limiting ......... ✅ Bucket4j 8.20.0 via Lettuce — 429 boundary tested
Testcontainers ........ ✅ RedisContainer redis:8.10.2-alpine
Compose ............... ✅ redis:8.10.2-alpine, allkeys-lru, healthcheck
Cache proof ........... ✅ repository hit once for two service calls
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Next: tune per-cache TTLs against real hit rates, then add otel-setup to watch hit/miss and latency.
```

Mark a row ⚠ if it was configured but not observed (Docker unavailable, test skipped) and say why.
Never report the cache green from configuration alone — Step 8 is the evidence.
