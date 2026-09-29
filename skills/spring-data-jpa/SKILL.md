---
name: spring-data-jpa
description: "Add JPA persistence to an existing Spring Boot 4 Maven project — entities, repositories, auditing, Flyway migrations, and Testcontainers integration tests. Use when asked to add a database, JPA entities or repositories, or Flyway migrations. Assumes a feature-sliced layout like spring-scaffold generates."
---

# Spring Data JPA Skill

Adds a production-ready persistence layer to an existing Spring Boot 4 project.

`SKILL_DIR` = directory containing this SKILL.md file.

Load `SKILL_DIR/references/jpa-conventions.md` before writing entities or migrations — it holds the
house rules on IDs, auditing, repository choice, migration discipline, and when to reach for
`JpaRepository`.

**Comments:** minimal, and only `WHY`. Generated code should be obvious from names and types. One short
line max — never a multi-line comment block.

---

## Step 0 — Gather inputs

| Field | Required | Notes |
|-------|----------|-------|
| `entities` | Yes | list of domain entities to add (e.g. `Order`, `OrderLine`) |
| `groupId` | No | project groupId; read from `pom.xml` if missing |
| `package` | No | base package; read from `pom.xml` if missing |
| `idType` | No | `Long` (default) or `UUID` |
| `auditing` | No | `true` (default) — add `@CreatedDate` / `@LastModifiedDate` |

---

## Step 1 — Read the project

```bash
cat pom.xml
ls src/main/java/<package>/
ls src/test/java/<package>/
cat src/main/resources/application.yml 2>/dev/null || cat src/main/resources/application.properties 2>/dev/null
```

Confirm:
- Spring Boot 4.x is in use.
- A `BaseIntegrationTest` exists in `src/test/java`.
- The project uses `application.yml` or `application.properties`.

If Boot is not 4.x, stop and say so — the property keys and Testcontainers shapes differ.

---

## Step 2 — Add dependencies

Add these to `pom.xml` if missing:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

Do **not** add `org.flywaydb:flyway-core` directly. The Boot starter owns it transitively and brings
the matching auto-configuration. `flyway-database-postgresql` is the Flyway 10+ module that knows the
Postgres dialect; without it Flyway silently fails to find migrations on Postgres.

If `spring-boot-starter-validation` is missing, add it — request DTOs need it.

---

## Step 3 — Add JPA config

Create `src/main/java/<package>/shared/config/JpaConfig.java`:

```java
package <package>.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
```

If the project already has a main class annotated with `@EnableJpaAuditing`, skip this file and say
why.

---

## Step 4 — Create entities

For each entity in `entities`, create `src/main/java/<package>/<entity>/<Entity>.java`.

Example for `Job` with `Long` IDs and auditing:

```java
package <package>.job;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@Table(name = "job")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
```

Rules from `references/jpa-conventions.md`:

- `Instant` for timestamps, never `java.util.Date`.
- `@Enumerated(EnumType.STRING)` for enums — never the default ordinal.
- Table names explicit with `@Table`, singular.
- `@Column(nullable = false)` matches the Flyway migration; no drift between code and schema.
- Lombok `@Getter`/`@Setter` on entities is allowed; no Lombok in controller/service layer.

If `idType=UUID`:

```java
@Id
@GeneratedValue(strategy = GenerationType.UUID)
private UUID id;
```

Boot 4.1 maps `GenerationType.UUID` to a UUID generator without a Hibernate extension.

---

## Step 5 — Create repositories

For each entity, create `src/main/java/<package>/<entity>/<Entity>Repository.java`:

```java
package <package>.job;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRepository extends JpaRepository<Job, Long> {
}
```

Use `JpaRepository` only when pagination or scrolling is needed. Otherwise prefer
`ListCrudRepository<Entity, ID>` — see `references/jpa-conventions.md` for the decision table.

---

## Step 6 — Write Flyway migrations

Write `src/main/resources/db/migration/V1__init.sql` with `CREATE TABLE` for every entity. Example:

```sql
CREATE TABLE job (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_job_status_created_at ON job (status, created_at);
```

Guidelines:
- Types: `BIGSERIAL` for `Long`, `UUID` for `UUID`, `TIMESTAMPTZ` for `Instant`, `VARCHAR(n)` with a
  real bound, `TEXT` for unbounded strings.
- Every migration is `V<N>__description.sql`, two underscores, no spaces.
- `ddl-auto: validate` in `application.yml` means the app refuses to start if entities and schema
  drift. Migrations are the source of truth.
- Index foreign keys and query columns; leave audit columns indexed only when queried.

If auditing is enabled, include `created_at`/`updated_at` columns in `V1__init.sql`. Do not split
auditing into a separate migration unless retrofitting an existing schema.

---

## Step 7 — Update application.yml

Add or merge:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:appdb}
    username: ${DB_USER:app}
    password: ${DB_PASSWORD:secret}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate:
        jdbc:
          time_zone: UTC
  flyway:
    enabled: true
    baseline-on-migrate: true
```

`ddl-auto: validate` is non-negotiable. `create`/`create-drop`/`update` are banned in every
environment except a throwaway local experiment — and even then Flyway owns the schema.

`open-in-view: false` prevents lazy-loading outside transactions; map to DTOs in the service layer.

---

## Step 8 — Write tests

If a `BaseIntegrationTest` exists, create repository integration tests next to it:

```java
package <package>.job;

import <package>.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

class JobRepositoryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JobRepository jobRepository;

    @Test
    @Sql("/sql/job-test-data.sql")
    void should_find_open_jobs() {
        var jobs = jobRepository.findAllByStatus(JobStatus.OPEN);

        assertThat(jobs).hasSize(2).allMatch(j -> j.getStatus() == JobStatus.OPEN);
    }
}
```

If no `BaseIntegrationTest` exists, create it and its container configuration first, using the
pattern from `spring-scaffold`:

```java
@TestConfiguration(proxyBeanMethods = false)
public class IntegrationTestContainers {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
    }
}
```

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IntegrationTestContainers.class)
public abstract class BaseIntegrationTest {
}
```

The container is a Spring bean, not a `static @Container` field: the JUnit extension would stop it
after the first test class while Spring keeps the cached context, and the next class would fail with
`Connection refused`.

Before writing, confirm `18-alpine` is still current:

```bash
curl -s "https://hub.docker.com/v2/repositories/library/postgres/tags/18-alpine" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['name'], d['last_updated'][:10])"
```

Also write `src/test/resources/sql/job-test-data.sql` with `INSERT` statements matching the migration.

---

## Step 9 — Run and report

```bash
./mvnw test
```

Report:

- Which dependencies were added
- Entities and repositories created
- Migration file path
- Whether `BaseIntegrationTest` was created or reused
- Test result summary
- Next step: add service/controller layers or run the `api-design` skill for OpenAPI contracts

If the app is meant to run locally, point the user at `devops-scaffold` to add a `docker-compose.yml`
service for Postgres.
