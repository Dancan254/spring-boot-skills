# Redis conventions

Caching and rate-limiting patterns with Spring Data Redis on Spring Boot 4.

---

## Serialization — the gotcha that matters

Spring Data Redis defaults to `JdkSerializationRedisSerializer` for cache values. What that means in
production:

- Values are opaque binary blobs — unreadable with `redis-cli`, unreadable from any non-JVM client.
- They embed the class's `serialVersionUID` contract: rename a field on a record, redeploy, and
  every cached entry throws on deserialization.
- Deserializing arbitrary Java is a security surface.

The fix is explicit JSON. On Spring Boot 4 / Spring Data Redis 4.x:

| Class | Status |
|-------|--------|
| `GenericJacksonJsonRedisSerializer` (`org.springframework.data.redis.serializer`) | Current — Jackson 3 (`tools.jackson.databind`) |
| `JacksonJsonRedisSerializer<T>` | Typed variant, one known value type, no `@class` hints |
| `GenericJackson2JsonRedisSerializer` | Removed with Jackson 2 — any snippet using it is stale |

Without default typing, a cached value deserializes to `LinkedHashMap`, not your record. Enable it:

```java
GenericJacksonJsonRedisSerializer.builder()
    .enableDefaultTyping(BasicPolymorphicTypeValidator.builder()
        .allowIfSubType("<base-package>")   // never the unsafe variant in internet-facing services
        .build())
    .build();
```

- `enableDefaultTyping(validator)` embeds a `@class` property and restricts which classes may be
  deserialized. Restrict to the app's base package.
- `enableUnsafeDefaultTyping()` accepts any class — fine for an internal cache, a bad habit anywhere
  an attacker can influence cached content.
- `enableSpringCacheNullValueSupport()` teaches the mapper to round-trip Spring's `NullValue` —
  required if (and only if) you cache nulls.

Keys: always `StringRedisSerializer`. A `toString()` on a non-String key type gives you key names
you can't predict in `redis-cli`.

---

## TTL strategy

- Keys with no TTL are a slow memory leak. Redis fills, then either evicts under pressure (with a
  policy you didn't choose) or starts rejecting writes.
- Set a **default TTL** on the base `RedisCacheConfiguration`, plus **per-cache overrides** for
  anything with a different freshness need. Never one global no-TTL cache.
- TTL should track change frequency, not guess "long": reference data (hours), entity lookups
  (minutes), anything user-mutable (seconds to minutes, or evict on write).
- Add jitter (`ttl + random(0..10%)`) for caches populated by many keys at once — a million keys
  written in a batch with the same TTL expire together and stampede the database. See stampede
  below.
- `allkeys-lru` maxmemory policy for a pure cache node; `volatile-lru` if the same instance holds
  keys that must never be evicted (sessions, rate-limit state you can't lose).

The skill pins TTLs in code. For TTLs that must change without a redeploy, bind them:

```java
@ConfigurationProperties(prefix = "app.cache")
public record CacheTtlProperties(Duration defaultTtl, Map<String, Duration> caches) {
    public CacheTtlProperties {
        if (defaultTtl == null) defaultTtl = Duration.ofMinutes(30);
        if (caches == null) caches = Map.of();
    }
}
```

Wire with `@EnableConfigurationProperties(CacheTtlProperties.class)` on the cache config, build the
per-cache map from `caches()`, and set matching keys under `app.cache.caches` in `application.yml`.

---

## Cache patterns

| Pattern | How | When |
|---------|-----|------|
| Cache-aside (lazy) | `@Cacheable` — the default | Almost everything: read-heavy, tolerant of a miss hitting the DB |
| Read-through | `CacheLoader` / dedicated `CacheResolver` | You want the load logic centralized, e.g. shared by several callers |
| Write-through | `@CachePut` on the write path | Cache must never be stale for reads-after-write |
| Evict-on-write | `@CacheEvict` | Cheap correctness when writes are rare |

Skip caching entirely when: the data is write-heavy and read-rarely, the query is already fast and
cheap, results must be strongly consistent (stock levels, balances), or the keyspace is unbounded
(per-user ephemeral data — your cache becomes a second database with worse tooling).

---

## Stampede and hot keys

When a hot key expires, N concurrent requests all miss and all hit the database at once. Mitigations,
in order of effort:

1. **Jitter TTLs** — cheap, prevents synchronized expiry.
2. **Short TTLs + tolerate staleness** — often the stampede matters less than the freshness.
3. **Per-key locking** — `sync = true` on `@Cacheable` makes concurrent calls for the same key wait
   on one computation (JVM-local only; it does not coordinate across instances).
4. **Refresh-ahead** — proactive refresh before expiry. Not built into Spring Cache; reach for
   Caffeine/Redisson if this becomes a real requirement.

Hot keys (one key read 10k×/s) are a different problem: replicate reads across instances with a local
Caffeine layer in front, or accept that Redis can take it — a single key on one shard is usually fine
until it isn't.

---

## Caching nulls

Default in this skill: `disableCachingNullValues()`.

- **Cache them** when absent-but-queried keys are common and expensive, or under cache-penetration
  attack (requests for IDs that don't exist, each one hitting the DB). Requires
  `enableSpringCacheNullValueSupport()` on the serializer and a short TTL — a cached null for a key
  that later gets created is a stale lie.
- **`unless`** on `@Cacheable` can skip caching per-result (`unless = "#result == null"`) — same
  trade-off, decided per method.

---

## Failure modes

Redis is a single point of failure until you decide otherwise. The default `SimpleCacheErrorHandler`
rethrows — one Redis outage takes down every cached endpoint. The skill's `CacheErrorHandler` logs
and proceeds: a cache miss is a slow request, a cache error must not be a 500.

- Fail-open is right for read caches. It is **not** right for rate limiting, auth state, or quotas —
  decide those per use case. A rate limiter that fails open stops limiting; one that fails closed
  stops the API. For most APIs, fail-open with an alert beats fail-closed.
- Set `spring.data.redis.timeout` explicitly — the alternative is threads parked on a dead socket
  for the TCP default.
- Monitor `keyspace_hits` / `keyspace_misses`. A cache with a 5% hit rate is a tax, not an
  optimization.

---

## AOP gotchas

- **Self-invocation silently bypasses the cache.** `this.getJob(id)` inside the same bean never
  touches the proxy. Move the cached method to a collaborator bean or self-inject.
- Only public, non-final, non-static methods on Spring beans are proxied.
- `@CacheEvict(allEntries = true)` on a per-write method is a stampede generator — evict by key.

---

## Rate limiting notes

- Why Bucket4j over `INCR` + `EXPIRE`: fixed-window counters allow 2x the limit at the window
  boundary and need a Lua script to be atomic; token buckets bound bursts, refill smoothly, and
  Bucket4j's Lettuce `ProxyManager` does atomic compare-and-swap across all app instances.
- `expirationAfterWrite(...)` is mandatory — without it every caller leaves a bucket key forever.
  `basedOnTimeForRefillingBucketUpToMax` expires idle buckets exactly when expiry is
  indistinguishable from a fresh bucket.
- Key tiers by identity: API key when present, IP as the fallback. `X-Forwarded-For` is only
  trustworthy from your own proxy — treat per-IP as abuse dampening, not identity.
- Always answer 429 with `Retry-After` and `X-Rate-Limit-Remaining` on the happy path. A 429 with
  no hint is a retry stampede invitation.
- One Redis round trip per request on the happy path — fine for APIs, worth measuring before putting
  it on a hot internal path.

---

## Testing

- Container: `com.redis.testcontainers.RedisContainer` from `com.redis:testcontainers-redis` — not
  in Boot's BOM, pin the version. A plain `GenericContainer("redis:...")` also works, but
  `@ServiceConnection` matches it only by image name, so a mirrored image breaks it; `RedisContainer`
  is matched by type.
- Evict or clear caches between tests (`cacheManager.getCache(...).clear()` in `@BeforeEach`) when
  test data overlaps — cached state leaking between tests produces flakes that look like
  serialization bugs.
- The proof test is the mock-verification test: two service calls, one repository call. Asserting
  "value is in Redis" with `redis-cli` or a template check proves storage, not interception.

---

## Docker Compose service connection

With `spring-boot-docker-compose` on the classpath, Boot matches the `redis` image in
`docker-compose.yml` and produces `RedisConnectionDetails` automatically (standalone mode) — no
`spring.data.redis.host/port` needed, and mapped ephemeral ports just work. Keep the yml properties
only as the fallback for running without compose. Compose support is off in tests by default; the
Testcontainers base class owns the test wiring.
