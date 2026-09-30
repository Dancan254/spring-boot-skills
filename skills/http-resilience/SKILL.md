---
name: http-resilience
description: "Make outbound HTTP calls resilient in an existing Spring Boot 4 Maven project — circuit breakers, retries, and timeouts with RestClient and Resilience4j, plus tests that prove failures degrade gracefully. Use when asked to add retries or a circuit breaker, call external APIs reliably, or protect against a flaky downstream. Not for inbound rate limiting — use redis-setup."
---

# HTTP Resilience Skill

Adds outbound-call resilience to an existing Spring Boot 4 project: RestClient with explicit
timeouts and Resilience4j fault tolerance around every downstream call.

`SKILL_DIR` = directory containing this SKILL.md file.

---

## Step 0 — Gather inputs

| Field | Required | Notes |
|-------|----------|-------|
| `service` | Yes | the downstream being called (e.g. `payments`, base URL) |
| `endpoints` | Yes | which calls need protection |
| `failureMode` | No | fallback value vs fail-fast (default: fail-fast with 502) |

Next: read the project.

---

## Step 1 — Read the project

```bash
cat pom.xml
grep -rn "RestClient\|RestTemplate\|WebClient\|@FeignClient" src/main/java/ | head -10
ls src/main/java/**/*client* 2>/dev/null
```

Confirm: Maven project on Boot 4. Find existing HTTP call sites — retrofit them rather than adding
a parallel client.

Next: add Resilience4j.

---

## Step 2 — Dependencies

Add to `pom.xml`:

```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot4</artifactId>
    <version>2.4.0</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aspectj</artifactId>
</dependency>
```

The starter needs AspectJ on the classpath — Boot no longer pulls it transitively. (Boot 4 renamed
`spring-boot-starter-aop` to `spring-boot-starter-aspectj`; the old name stopped at 4.0.0-M2.)

Before writing, verify `2.4.0` is still the latest:

```bash
curl -s "https://repo1.maven.org/maven2/io/github/resilience4j/resilience4j-spring-boot4/maven-metadata.xml" \
  | python3 -c "import sys,re; print(re.findall(r'<version>(.*?)</version>', sys.stdin.read())[-1])"
```

Next: the client.

---

## Step 3 — RestClient with explicit timeouts

Create or refactor the client (example: `payment/PaymentClient.java`):

```java
package com.example.payment;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
class PaymentClientConfig {

    @Bean
    RestClient paymentRestClient(RestClient.Builder builder) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return builder.baseUrl("http://localhost:9090").requestFactory(factory).build();
    }
}
```

Rules:
- Every client gets connect AND read timeouts. No timeout is the default outage amplifier.
- One `RestClient` bean per downstream, named after it.
- Base URL comes from config (Step 4), not a literal — shown inline here for shape only.

Next: wrap the calls.

---

## Step 4 — Circuit breaker, retry, config

Annotate the calling methods:

```java
@CircuitBreaker(name = "payments", fallbackMethod = "fallbackCharge")
@Retry(name = "payments")
public ChargeResult charge(ChargeRequest request) {
    return paymentRestClient.post().uri("/charges").body(request).retrieve()
            .body(ChargeResult.class);
}
```

Externalize in `application.yml`:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      payments:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
  retry:
    instances:
      payments:
        max-attempts: 3
        wait-duration: 500ms
        enable-exponential-backoff: true
```

Rules:
- Retry only idempotent calls (GET, or POST with an idempotency key). Say so per call.
- Fallbacks return a domain value or throw a 502-mapped exception — never swallow silently.
- `@TimeLimiter` needs a `CompletableFuture` return type; skip it unless the user asks for async.

Next: prove it degrades.

---

## Step 5 — Test the failure path

Integration test with a dead downstream (no mocking of Resilience4j itself):

```java
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

@SpringBootTest
class PaymentClientResilienceIT {

    @Autowired PaymentClient client;

    @Test
    void opensCircuitAfterFailures() {
        // downstream unreachable: connection refused
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> client.charge(new ChargeRequest(100)))
                    .isInstanceOf(Exception.class);
        }
        assertThatThrownBy(() -> client.charge(new ChargeRequest(100)))
                .isInstanceOf(CallNotPermittedException.class); // circuit open, call not attempted
    }
}
```

The assertion that matters: once the circuit is open, calls fail fast with
`CallNotPermittedException` instead of waiting on timeouts.

Next: report.

---

## Step 6 — Report

Report:

- Dependencies added (with verified versions)
- Clients created/refactored and their timeouts
- Resilience4j instances configured and their policies
- Which calls are retried and why they are idempotent
- Test result: circuit opens and fails fast
- Next step: expose breaker state on `/actuator/health` by adding `management.health.circuitbreakers.enabled: true`
