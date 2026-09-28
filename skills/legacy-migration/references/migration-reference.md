# Migration reference — Spring Boot 2.x/3.x → 4.x

Lookup tables for `legacy-migration`. Load before touching code. Sources: the official
[Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide),
the [Boot 3.0 guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Migration-Guide),
and the recipe YAML inside `rewrite-spring-6.37.1.jar` (verified against Maven Central, Sep 2026).

---

## Recipe map per hop

Run from the command line, `dryRun` first, four recipe artifacts on the plugin classpath
(`rewrite-spring`, `rewrite-migrate-java`, `rewrite-hibernate`, `rewrite-testing-frameworks` — pins
and verification curls are in the SKILL). A "recipe not found" error means a missing artifact, not
a typo in the recipe name.

| Hop | Recipe | Chains / covers | Does NOT cover |
|---|---|---|---|
| A → 2.7.18 | `org.openrewrite.java.spring.boot2.UpgradeSpringBoot_2_7` | The full 2.0→2.7 line, JUnit 4→5 assist | Circular-ref fixes, PathPatternParser behavior |
| B → 3.0 | `org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_0` | `UpgradeToJava17`, `JakartaEE10` (all javax imports), Framework 6, Security 6 API, Hibernate 6.1, Boot 3.0 properties | javax in strings/XML, security config semantics, third-party starters |
| B tail → 3.5.16 | version bump by hand | Deprecations removed in 4.0 are visible as warnings here — fix them now | — |
| C → 4.0.x | `org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0` | Boot 3.5 chain, Spring Cloud 2025.1, Framework 7, Security 7, Batch 5→6, `SpringBootProperties_4_0`, `ReplaceMockBeanAndSpyBean`, `RelocateWebServerClasses`, Hibernate 7.1, `Testcontainers2Migration`, SpringDoc 3.0, `MigrateToModularStarters` | Jackson 3 app-code surfaces, `@Configuration`-declared mocks, behavior changes |
| D → 4.1.1 | version bump + `org.openrewrite.java.spring.boot4.SpringBootProperties_4_1` | 4.1 property renames (incl. the flat OTLP keys) | There is no `UpgradeSpringBoot_4_1` — everything else is a bump |

Within-line minor bumps (2.7.x→2.7.18, 3.5.x→3.5.16) are version changes, not recipe runs.

---

## javax → jakarta

Move at hop B. Recipes rewrite imports and `pom.xml` coordinates; everything else is the string sweep.

**Moves** (`javax.<x>` → `jakarta.<x>`, same sub-package):

| javax | jakarta | Where it hides besides imports |
|---|---|---|
| `persistence` | `jakarta.persistence` | `persistence.xml` schema + provider class, `@PersistenceUnit` strings |
| `servlet` | `jakarta.servlet` | `web.xml`, filter/listener class names in XML and annotations |
| `validation` | `jakarta.validation` | `META-INF/validation.xml`, custom constraint messages referencing classes |
| `annotation` | `jakarta.annotation` (`@PostConstruct`, `@PreDestroy`, `@Resource`) | nothing sneaky — but `javax.annotation.Nonnull` is `javax.annotation` **processing**, see below |
| `inject` | `jakarta.inject` | — |
| `mail` | `jakarta.mail` | `spring.mail.properties.mail.smtp.*` stay — those are property keys, not packages |
| `websocket` | `jakarta.websocket` | `ServerEndpointConfig` strings |
| `jms` | `jakarta.jms` | — |
| `transaction` | `jakarta.transaction` | — |
| `ws.rs` | `jakarta.ws.rs` | Jersey config |
| `el` | `jakarta.el` | JSP/Facelets only |

**Stays — do not "migrate" these:**

- `javax.sql`, `javax.naming`, `javax.net`, `javax.crypto`, `javax.security.auth`, `javax.management`,
  `javax.swing`, `javax.xml.*` (JAXP) — part of the **JDK**, not Java EE. Rewriting them to
  `jakarta.*` breaks against the JDK.
- `java.*` obviously.
- `javax.annotation.processing` — JDK. (`javax.annotation.Nonnull` from JSR-305: no jakarta
  replacement; use `org.jspecify.annotations` — Boot 4 is JSpecify-annotated throughout.)

**String-sweep greps (run after the recipe):**

```bash
grep -rn 'javax\.' src/main/resources src/main/webapp src/test/resources 2>/dev/null
grep -rn --include=*.java 'javax\.' src | grep -v '^\s*import' | grep -vE 'javax\.(sql|naming|net|crypto|management|xml)'
grep -rn -e 'Class.forName' -e '@Qualifier("' -e '@Named("' src/main | grep -i 'javax\|jakarta'
find src -name 'persistence.xml' -o -name 'web.xml' -o -name 'orm.xml' -o -name 'validation.xml'
```

---

## Property renames (the common ones)

`spring-boot-properties-migrator` (runtime scope, hop B or C, **removed after**) prints the full
list for the actual app. Boot 4.0 renames are also applied by `SpringBootProperties_4_0`; 4.1
renames by `SpringBootProperties_4_1`.

| Old key | New key | Hop |
|---|---|---|
| `server.max-http-header-size` | `server.max-http-request-header-size` | B |
| `spring.mvc.pathmatch.matching-strategy` | still exists — default is `path-pattern-parser` since Boot 2.6; do not set `ant_path_matcher` | A |
| `PathMatchConfigurer.setUseTrailingSlashMatch(true)` | deprecated since Framework 6 (Boot 3.0), **removed in Framework 7** — use `UrlHandlerFilter` | C |
| `spring.dao.exceptiontranslation.enabled` | `spring.persistence.exceptiontranslation.enabled` | C |
| `spring.session.redis.*` | `spring.session.data.redis.*` | C |
| `spring.session.mongodb.*` | `spring.session.data.mongodb.*` | C |
| `spring.data.mongodb.{host,port,uri,database,...}` | `spring.mongodb.*` (only the driver-level keys move; `spring.data.mongodb.auto-index-creation` etc. stay) | C |
| `management.health.mongo.enabled` | `management.health.mongodb.enabled` | C |
| `spring.jackson.read.*` / `spring.jackson.write.*` | `spring.jackson.json.read.*` / `spring.jackson.json.write.*` | C |
| `spring.jackson.parser.*` | `spring.jackson.json.read.*` where a `JsonReadFeature` exists, else `JsonMapperBuilderCustomizer` | C |
| `spring.kafka.retry.topic.backoff.random` | `spring.kafka.retry.topic.backoff.jitter` | C |
| `management.otlp.tracing.*` / `management.otlp.logging.*` | `management.opentelemetry.tracing.*` / `management.opentelemetry.logging.*` | D |

Defaults that changed without a rename — no warning anywhere, this is why the runtime checks exist:

- `spring.main.allow-circular-references` — **false** since Boot 2.6
- trailing-slash matching — **off** since Boot 3.0 (Framework 6)
- `spring.jackson.find-and-add-modules` — **true** in Boot 4 (all classpath modules registered;
  Boot 3 registered only well-known ones)
- `management.endpoint.health.probes.enabled` — **true** in Boot 4 (liveness/readiness groups
  exposed by default)
- `spring.devtools.livereload.enabled` — **false** in Boot 4

---

## Starter and dependency swaps

Boot 4 renamed/removed starters. The recipe applies these; the table is for reviewing the diff and
for dependencies the recipe can't classify.

| Old (Boot 3) | New (Boot 4) | Notes |
|---|---|---|
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` | old id still resolves, deprecated |
| `spring-boot-starter-web-services` | `spring-boot-starter-webservices` | — |
| `spring-boot-starter-aop` | `spring-boot-starter-aspectj` | check it's actually needed — Micrometer `@Timed`/`@Counted` annotations trigger it transitively |
| `spring-boot-starter-oauth2-{client,resource-server,authorization-server}` | `spring-boot-starter-security-oauth2-*` | Authorization Server is part of Spring Security now; its version override moves to `spring-security.version` |
| `spring-boot-starter-test` (direct) | test slices: `spring-boot-starter-webmvc-test`, `-data-jpa-test`, … | slices pull `spring-boot-starter-test` transitively; the direct declaration is redundant in 4.x |
| bare `org.flywaydb:flyway-core` / `org.liquibase:liquibase-core` | `spring-boot-starter-flyway` / `spring-boot-starter-liquibase` | the third-party dep alone no longer triggers auto-config |
| `org.testcontainers:{postgresql,kafka,junit-jupiter,...}` | `org.testcontainers:testcontainers-{postgresql,kafka,junit-jupiter,...}` | 1.x coordinates stop at 1.21.4 forever; Boot 4.1 BOM pins 2.0.5 — never override `testcontainers.version` |
| `spring-retry` (managed) | explicit `<version>` required | management removed; prefer Spring Framework's retry where possible |
| `hibernate-jpamodelgen` | `hibernate-processor` | — |
| `springdoc-openapi-starter-webmvc-ui` 2.x | 3.x | recipe chains `UpgradeSpringDoc_3_0` |
| `spring-boot-starter-undertow` | **removed** — move to `spring-boot-starter-tomcat` or `-jetty` | Servlet 6.1 floor; Undertow is incompatible |
| any starter, interim | `spring-boot-starter-classic` / `spring-boot-starter-test-classic` | Boot-3-style all-in classpath as a migration bridge only — decompose before calling hop C done |

Removed from Boot 4 with no replacement: reactive Pulsar auto-config, uber-jar launch scripts,
Spring Session Hazelcast/MongoDB (now community-led), Spock integration, classic uber-jar loader
(`<loaderImplementation>CLASSIC</loaderImplementation>` — delete it).

---

## API replacements

| Old | New | Hop |
|---|---|---|
| `WebSecurityConfigurerAdapter` | `SecurityFilterChain` `@Bean`s — target conventions in the **`spring-security`** skill | B |
| `WebMvcConfigurerAdapter`, `WebFluxConfigurerAdapter` | implement the interface directly | B |
| `spring.factories` auto-config registration | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | B |
| `@MockBean` / `@SpyBean` | `@MockitoBean` / `@MockitoSpyBean` — **removed** in Boot 4, not deprecated | C |
| `@MockBean` on `@Configuration` classes | class-level `@MockitoBean(types = {...})` or a composed annotation | C |
| `@SpringBootTest` providing MockMvc/`WebClient`/`TestRestTemplate` | add `@AutoConfigureMockMvc` / `@AutoConfigureRestTestClient` / `@AutoConfigureTestRestTemplate` | C |
| `org.springframework.boot.test.web.client.TestRestTemplate` | `org.springframework.boot.resttestclient.TestRestTemplate` (artifact `spring-boot-resttestclient`) — or move to `RestTestClient` | C |
| `com.fasterxml.jackson.*` (databind, core, dataformat) | `tools.jackson.*` | C |
| `com.fasterxml.jackson.annotation` | **stays** — annotations are shared by Jackson 2 and 3 | — |
| `ObjectMapper` bean to replace the mapper | `JsonMapper` / `XmlMapper` bean | C |
| `Jackson2ObjectMapperBuilderCustomizer` | `JsonMapperBuilderCustomizer` | C |
| `@JsonComponent` / `@JsonMixin` | `@JacksonComponent` / `@JacksonMixin` | C |
| `JsonObjectSerializer` / `JsonValueDeserializer` | `ObjectValueSerializer` / `ObjectValueDeserializer` | C |
| `@EntityScan` (`...boot.autoconfigure`) | `org.springframework.boot.persistence.autoconfigure.EntityScan` | C |
| `EnvironmentPostProcessor` (`...boot.env`), `BootstrapRegistry` (`...boot`) | `org.springframework.boot`, `org.springframework.boot.bootstrap` | C |
| `PropertyMapper` `alwaysApplyingWhenNonNull()` | removed — non-null is the default; `always()` to map nulls | C |
| `HttpMessageConverters` bean / converter beans contributed directly | `ClientHttpMessageConvertersCustomizer` / `ServerHttpMessageConvertersCustomizer` | C |
| Boot's `StreamBuilderFactoryBeanCustomizer` | Spring Kafka's `StreamsBuilderFactoryBeanConfigurer` | C |
| `RabbitRetryTemplateCustomizer` | `RabbitTemplateRetrySettingsCustomizer` / `RabbitListenerRetrySettingsCustomizer` | C |
| `MockitoTestExecutionListener`-driven `@Mock`/`@Captor` | `MockitoExtension` from Mockito itself | C |
| `spring.jackson.use-jackson2-defaults=true`, `spring-boot-jackson2` | Jackson 2 stop-gaps — time-buyers, both flagged for removal; never a finished state | C |

---

## Symptom → cause → fix

The "green build, broken app" table. Check every row that applies after each hop — at runtime, not
by reading the diff.

| Symptom | Cause | Fix |
|---|---|---|
| `BeanCurrentlyInCreationException` / circular-reference error at startup | Circular refs refused by default since Boot 2.6 | Break the cycle (constructor redesign, `@Lazy` on one side); `allow-circular-references=true` is a diagnostic, not a fix |
| 404s on routes ending in `/` after hop B | Trailing-slash matching off by default since Boot 3.0 | Fix callers, or register a `UrlHandlerFilter` forwarding `/x/` → `/x`; the old `setUseTrailingSlashMatch(true)` is gone in Boot 4 |
| 404s on `**` patterns or ambiguous mappings after hop B | PathPatternParser semantics (default since 2.6): no suffix matching, stricter `**` placement | Rewrite the patterns; there is no switch back |
| `ClassNotFoundException: javax.*` at runtime despite clean imports | javax in a string, XML, `persistence.xml`, or reflection | the string-sweep greps above |
| JSON dates changed format (`2026-01-01T00:00:00.000+00:00` → timestamps or vice versa) | Jackson 3 default changes; Boot 4 registers **all** classpath modules | Pin the format: `spring.jackson.json.write.*` or a `JsonMapperBuilderCustomizer`; `spring.jackson.use-jackson2-defaults=true` to triage |
| `NoSuchMethodError` / weird serialization after hop C | A library still shading Jackson 2 next to Jackson 3 | keep the Jackson 2 dep for that library only (management remains), never mix mappers for one payload |
| Tests fail: `@MockBean` unresolvable, or mocks in `@Configuration` silently not applied | `@MockBean` removed; `@MockitoBean` is field/class-level only | field → `@MockitoBean`; `@Configuration` mocks → class-level `@MockitoBean(types=...)` |
| `@SpringBootTest` web tests fail with "no MockMvc/TestRestTemplate bean" | Boot 4 no longer auto-provides them | `@AutoConfigureMockMvc` / `@AutoConfigureRestTestClient` |
| Tests still on Testcontainers 1.x coordinates | old ids resolve but stop at 1.21.4 | `testcontainers-` prefixed artifacts, container classes from `org.testcontainers.<module>` (no `<?>`), pinned `DockerImageName` |
| Kubernetes probes flap after hop C | liveness/readiness groups exposed by default in Boot 4 | align the probe paths, or `management.endpoint.health.probes.enabled=false` |
| Uber jar missing classes at runtime, fine in tests | optional dependencies no longer included in Boot 4 uber jars | `<includeOptional>true</includeOptional>` or make the dependency non-optional |
| Live reload stopped working | off by default in Boot 4 | `spring.devtools.livereload.enabled=true` |
| `@Observed`-style auto-config silently inert after hop C | a technology lost its plain dependency trigger (Flyway, Liquibase) | the `spring-boot-starter-<technology>` starter, per the swap table |
| Property warning flood at startup | stale keys after a hop | `spring-boot-properties-migrator` (runtime scope) → read warnings → rename → **remove the migrator** |
| App works locally, fails on a WAR deploy behind a proxy | `server.forward-headers-strategy` is inert for external containers in Boot 4 | register a `ForwardedHeaderFilter` bean (guide shows the exact bean) |

---

## Pin verification (run before writing versions into anything)

```bash
# Boot GA lines
curl -s -H 'Accept: application/json' https://start.spring.io/metadata/client \
  | python3 -c "import json,sys; print([v['id'] for v in json.load(sys.stdin)['bootVersion']['values']])"

# 2.7.x / 3.5.x maintenance lines
curl -s "https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml" \
  | grep -oE '<version>(2\.7|3\.5)\.[0-9]+</version>' | tail -5

# OpenRewrite artifacts on Maven Central (newer releases are behind Moderne's gated repo — Central pins suffice)
for a in rewrite-spring rewrite-migrate-java rewrite-hibernate rewrite-testing-frameworks; do
  printf '%s: ' "$a"
  curl -s "https://repo1.maven.org/maven2/org/openrewrite/recipe/$a/maven-metadata.xml" \
    | grep -o '<latest>[^<]*' | sed 's/<latest>//'
done
curl -s "https://repo1.maven.org/maven2/org/openrewrite/maven/rewrite-maven-plugin/maven-metadata.xml" \
  | grep -o '<latest>[^<]*' | sed 's/<latest>//'

# what the Boot 4.1 BOM pins for the test stack
curl -s "https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom" \
  | grep -oE '<(testcontainers|junit-jupiter|mockito|assertj)\.version>[^<]*'
```

Verified pins as of Sep 2026: Boot **4.1.1** GA (4.0.8 on the 4.0 line; 4.2.0.M2 in flight),
3.5.16, 2.7.18; rewrite-spring 6.37.1, rewrite-migrate-java 3.42.1, rewrite-hibernate 2.25.0,
rewrite-testing-frameworks 3.44.0, rewrite-maven-plugin 6.46.1. Boot 4.1.1 BOM: Testcontainers
2.0.5, JUnit Jupiter 6.0.3, Mockito 5.23.0, AssertJ 3.27.7 — the migration lands the project on
these; anything older pinned by hand in the pom is a finding.
