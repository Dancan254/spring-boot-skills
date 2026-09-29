---
name: api-design
description: "Add OpenAPI docs and REST conventions to an existing Spring Boot 4 Maven project — SpringDoc, API versioning, ProblemDetail error schemas, DTO conventions. Use when asked to add OpenAPI or Swagger, document or version an API, or define REST conventions. Not for auth — use spring-security."
---

# API Design Skill

Adds OpenAPI/SpringDoc documentation and REST conventions to an existing Spring Boot 4 project.

`SKILL_DIR` = directory containing this SKILL.md file.

Load `SKILL_DIR/references/openapi-conventions.md` before annotating controllers — it covers
versioning strategy, annotation style, DTO naming, and the error schema used here.

---

## Step 0 — Gather inputs

| Field | Required | Notes |
|-------|----------|-------|
| `title` | No | API title; defaults to project name from `pom.xml` |
| `version` | No | API version; defaults to `1.0.0` |
| `description` | No | short API description |
| `basePath` | No | `/api` (default) or `/api/v1` |
| `annotateControllers` | No | `false` (default) — only annotate if user explicitly asks |

---

## Step 1 — Read the project

```bash
cat pom.xml
find src/main/java -name '*Controller.java' | head -20
ls src/main/resources/
```

Confirm Spring Boot 4.x and web dependency.

---

## Step 2 — Add dependency

Add to `pom.xml`:

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>3.1.1</version>
</dependency>
```

Before writing, verify the latest SpringDoc version:

```bash
curl -s "https://repo1.maven.org/maven2/org/springdoc/springdoc-openapi-starter-webmvc-ui/maven-metadata.xml" \
  | python3 -c "import sys,re; print(re.findall(r'<version>(.*?)</version>', sys.stdin.read())[-1])"
```

SpringDoc 2.x is for Spring Boot 3+/SpringDoc 2.x APIs. Do not use `springdoc-openapi-ui` (the 1.x
artifact) on Boot 4.

---

## Step 3 — OpenAPI config

Create `src/main/java/<package>/shared/config/OpenApiConfig.java`:

```java
package <package>.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI(
            @Value("${info.app.title:API}") String title,
            @Value("${info.app.version:1.0.0}") String version,
            @Value("${info.app.description:}") String description) {

        return new OpenAPI()
            .info(new Info()
                .title(title)
                .version(version)
                .description(description))
            .servers(List.of(
                new Server().url("/").description("Default")
            ));
    }
}
```

---

## Step 4 — Update application.yml

Add:

```yaml
springdoc:
  api-docs:
    path: /api-docs
  swagger-ui:
    path: /swagger-ui.html
    operations-sorter: method
    tags-sorter: alpha
  default-produces-media-type: application/json

info:
  app:
    title: ${APP_TITLE:<project name>}
    version: ${APP_VERSION:1.0.0}
    description: ${APP_DESCRIPTION:}
```

If `spring-security` is present, permit `/swagger-ui/**`, `/api-docs/**`, and `/v3/api-docs/**`.

---

## Step 5 — Global error schema

Add `src/main/java/<package>/shared/api/ProblemDetailSchema.java` to document the `ProblemDetail`
responses globally:

```java
package <package>.shared.api;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.ProblemDetail;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@ApiResponse(responseCode = "400", description = "Bad request",
    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "404", description = "Not found",
    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "409", description = "Conflict",
    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "500", description = "Internal server error",
    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
public @interface ProblemDetailSchema {
}
```

Use it on controllers:

```java
@ProblemDetailSchema
@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {
}
```

---

## Step 6 — API versioning convention

Create `src/main/java/<package>/shared/api/ApiVersion.java`:

```java
package <package>.shared.api;

public final class ApiVersion {

    private ApiVersion() {}

    public static final String V1 = "/api/v1";
}
```

Use `ApiVersion.V1` in `@RequestMapping` on controllers. Do not hardcode `/api/v1` in multiple
controllers.

---

## Step 7 — Annotate controllers (optional)

Only if `annotateControllers` is `true`. Add `@Tag`, `@Operation`, `@Schema`, and example DTO fields.
Example:

```java
@Tag(name = "Jobs", description = "Job listing management")
@RestController
@RequestMapping(ApiVersion.V1 + "/jobs")
public class JobController {

    @Operation(summary = "Create a job", description = "Creates a new job listing")
    @PostMapping
    public ResponseEntity<JobResponse> create(@Valid @RequestBody JobRequest request) { ... }
}
```

Never annotate internal/admin-only endpoints in a way that exposes them to public docs if they should
not be public. Use `@Hidden` for internal endpoints.

---

## Step 8 — API conventions doc

Create `docs/api-conventions.md`:

```markdown
# API conventions

## Base path
All endpoints are under `/api/v1` unless marked otherwise.

## Content type
JSON only. Request bodies must include `Content-Type: application/json`.

## Errors
Errors follow RFC 9457 `ProblemDetail`:

```json
{
  "type": "about:blank",
  "title": "Resource Not Found",
  "status": 404,
  "detail": "Job not found: 42"
}
```

## Pagination
- Keyset/cursor pagination is the default for user-facing lists.
- Offset pagination is available only for admin/reporting endpoints.
- See `docs/pagination.md` for implementation details.

## Versioning
URL path versioning (`/api/v1/`). A new breaking version gets a new path (`/api/v2/`).
```

---

## Step 9 — Run and report

```bash
./mvnw test
```

Then verify the docs render at:

```
http://localhost:8080/swagger-ui.html
http://localhost:8080/api-docs
```

Report:

- Dependency and version added
- `OpenApiConfig`, `ProblemDetailSchema`, `ApiVersion` created
- `application.yml` updated
- Whether controllers were annotated
- `docs/api-conventions.md` created
- Next step: add `spring-security` to protect documented endpoints
