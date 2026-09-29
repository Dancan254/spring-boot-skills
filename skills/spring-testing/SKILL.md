---
name: spring-testing
description: "Write, repair, and modernise tests in an existing Spring Boot 4 project (Maven or Gradle) — Testcontainers 2.x, integration vs unit routing, and the Boot 4 test API (@MockitoBean, MockMvcTester, RestTestClient). Use when asked to add or fix tests, set up Testcontainers, or migrate off Testcontainers 1.x or @MockBean."
---

# Spring Testing Skill

Owns the testing story for a project that already exists. `spring-scaffold` generates a
`BaseIntegrationTest` for new projects and stops there — this skill is what you run afterwards, on
every project, for the rest of its life.

`SKILL_DIR` = the directory containing this SKILL.md.

**Load before doing anything:**

- `SKILL_DIR/references/containers.md` — Testcontainers 2.x coordinates, class packages, pinned
  images, and the commands that verify them. The single source of truth for versions.
- `SKILL_DIR/references/conventions.md` — what gets a unit vs integration vs slice test, the Boot 4
  API replacements, mocking rules, isolation, flakiness.

Never add a test dependency without naming it and saying why. AssertJ, Mockito, JUnit 5 and
Awaitility already arrive with the Boot test starters — check before reaching for anything.

---

## Step 0 — Read the project

```bash
ls pom.xml build.gradle build.gradle.kts 2>/dev/null
grep -m1 -A1 'spring-boot-starter-parent' pom.xml
find src/test -name '*.java' | head -30
```

Establish: build tool, Boot version, whether a `BaseIntegrationTest` (or equivalent) already exists,
and what the existing tests actually do. Match what's there — do not restructure someone's suite as a
side effect of adding one test.

If the project is not on Boot 4, say so and stop before applying Boot 4 API guidance; the
replacements in `conventions.md` do not exist on 3.x.

---

## Step 1 — Audit before writing

Run all four. Each hit is a finding to report, and the first two are the difference between a suite
that is on Testcontainers 2.x and one that only looks like it.

```bash
# 1. Testcontainers 1.x coordinates (they resolve, they just stop at 1.21.4 forever)
grep -n -A1 'org.testcontainers' pom.xml \
  | grep -E '<artifactId>(junit-jupiter|postgresql|kafka|rabbitmq|mongodb|localstack|grafana|mysql|elasticsearch)</artifactId>' || true

# 2. deprecated 1.x container imports still compiling against the 2.x shims
grep -rn --include=*.java 'org.testcontainers.containers.[A-Z]' src/test/java \
  | grep -v GenericContainer || true

# 3. APIs removed in Boot 4
grep -rn --include=*.java -e '@MockBean' -e '@SpyBean' -e 'boot.test.web.client.TestRestTemplate' src/test/java || true

# 4. unpinned or floating images
grep -rn --include=*.java -e ':latest' -e 'DockerImageName.parse("[a-z/]*")' src/test/java || true
```

Then confirm the resolved Testcontainers version against the BOM using the commands in
`references/containers.md` — never pin `testcontainers.version` by hand to fix a mismatch; that is a
Boot upgrade.

Report the findings as a short list before touching code. Fix them as part of the work only if the
user asked for tests in that area; otherwise flag and leave them.

---

## Step 2 — Route the work

Use the routing table in `references/conventions.md`. The short version:

- touches the database, a broker, or S3 → **integration test with a real container**
- pure logic, no IO → **unit test, no Spring context**
- HTTP contract only → **`@WebMvcTest` slice with `MockMvcTester`**
- outbound HTTP client → **`@RestClientTest` slice with `MockRestServiceServer`**

State which kind you're writing and why in one line, then write it.

---

## Step 3 — Ensure a base class exists

Every integration test extends one class holding one static container. If the project has none,
create it next to the application class in `src/test/java`:

```java
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "management.otlp.metrics.export.enabled=false",
        "management.tracing.export.enabled=false",
        "management.logging.export.otlp.enabled=false"
    })
@Testcontainers
public abstract class BaseIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
        new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
}
```

`@Testcontainers` is the JUnit 5 extension that starts the static `@Container`; `@ServiceConnection`
only wires connection details and starts nothing. Both are required.

The `properties` disable OTLP export so tests don't warn against a collector that isn't running.
Telemetry is exercised in dev via `./mvnw spring-boot:test-run`, not in the test suite — the Grafana
LGTM container never belongs in the base class.

For any other backing service, take the class, artifact, and pinned tag from
`references/containers.md`. One base class per project; a second container declared in a subclass
starts a second database.

---

## Step 4 — Write the tests

**Repository / persistence:**

```java
class JobRepositoryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JobRepository jobRepository;

    @Test
    @Sql("/sql/three-open-jobs.sql")
    void should_return_only_open_jobs_when_filtering_by_status() {
        var jobs = jobRepository.findByStatus(JobStatus.OPEN);

        assertThat(jobs).hasSize(3).allMatch(job -> job.getStatus() == JobStatus.OPEN);
    }
}
```

Field injection is banned in `src/main`; `@Autowired` on a test field is the accepted exception —
JUnit constructs the test class, so there is no constructor to inject through.

**HTTP contract, no database — `MockMvcTester`, not raw `MockMvc` matchers:**

```java
@WebMvcTest(JobController.class)
class JobControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private JobService jobService;

    @Test
    void should_return_400_with_problem_detail_when_title_is_blank() {
        assertThat(mvc.post().uri("/api/jobs").contentType(APPLICATION_JSON).content("""
                {"title": "", "description": "x"}
                """))
            .hasStatus(HttpStatus.BAD_REQUEST)
            .bodyJson().extractingPath("$.title").isEqualTo("Validation failed");
    }
}
```

**Full flow over real HTTP — `RestTestClient`, the `RestClient`-era replacement for
`TestRestTemplate`:**

```java
@AutoConfigureRestTestClient
class JobApiIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private RestTestClient client;

    @Test
    void should_persist_and_return_created_job_when_request_is_valid() {
        client.post().uri("/api/jobs")
            .contentType(APPLICATION_JSON)
            .body(new JobRequest("Backend Engineer", "Spring Boot 4"))
            .exchange()
            .expectStatus().isCreated()
            .expectBody().jsonPath("$.title").isEqualTo("Backend Engineer");
    }
}
```

Rules that apply to all three: `should_<expected>_when_<condition>` names, AssertJ assertions, one
behaviour per test, no `@Transactional` on tests that go over HTTP, and error paths assert the
`ProblemDetail` fields — status, title, detail — not just that something threw.

---

## Step 5 — Run and report

```bash
./mvnw test -Dtest='<NewTest>'   # the new tests alone first
./mvnw test                       # then the whole suite — new tests must not break neighbours
```

First run pulls images; say so rather than letting it look hung. If Docker isn't running, stop and
say exactly that — `Docker daemon not reachable; Testcontainers cannot start` — never fall back to an
embedded database to make the run go green.

Lead with the verdict, then the board:

```
3 tests added · suite green · 1 setup finding

━━━ SPRING-TESTING ━━━━━━━━━━━━━━━━━━━━━━━━━
Setup audit ........... ⚠  1  pom.xml:71 org.testcontainers:junit-jupiter → testcontainers-junit-jupiter
Tests written ......... 3  (1 integration, 2 unit)
New tests ............. ✅ 3 passing
Full suite ............ ✅ 47 passing · 0 failed · 0 skipped
Runtime ............... 18s (was 11s — one new container class)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Next: fix the 1.x coordinate in pom.xml:71, then run a ship-check.
```

On failure show the failing assertion and its file:line, not the log — `… +180 more lines`, and say
the full log is one `./mvnw test` away. Never report a suite green without having run it.

Fix a failing test only when the test is what's wrong. A test that caught a real bug is the test
doing its job — say so, name the bug, and let the user decide.
