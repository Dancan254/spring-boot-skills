---
name: spring-security
description: "Add JWT resource-server security to an existing Spring Boot 4 Maven project — security config, claim-to-role mapping, method security, and tests. Use when asked to secure an API, add JWT or OAuth2 resource server auth, or add roles and permissions. Not for dependency, secret, or image scanning — use security-hardening."
---

# Spring Security Skill

Adds JWT resource-server security to an existing Spring Boot 4 API.

`SKILL_DIR` = directory containing this SKILL.md file.

Load `SKILL_DIR/references/security-conventions.md` before writing security config — it covers JWT
claim mapping, authority naming, public-path rules, and the testing patterns used here.

This skill focuses on API/JWT resource server security. Form login and session-based auth are out of
scope.

---

## Step 0 — Gather inputs

| Field | Required | Notes |
|-------|----------|-------|
| `issuerUri` | Yes | JWT issuer URI, e.g. `https://auth.example.com/realms/app` |
| `publicPaths` | No | list of public ant patterns, e.g. `/actuator/health`, `/api/public/**` |
| `rolesClaim` | No | claim that holds roles, e.g. `roles` (default) or `realm_access.roles` |
| `rolePrefix` | No | `ROLE_` (default) or empty string |

---

## Step 1 — Read the project

```bash
cat pom.xml
ls src/main/java/<package>/
ls src/main/java/<package>/shared/
cat src/main/resources/application.yml 2>/dev/null || cat src/main/resources/application.properties 2>/dev/null
```

Confirm Spring Boot 4.x and a web dependency (`spring-boot-starter-web`). Every dependency this skill
adds is managed by the Boot BOM, so the only pin to verify is the Boot version itself — compare the
project's parent against the latest 4.x GA:

```bash
curl -s "https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml" \
  | grep -oE '<version>4\.[0-9]+\.[0-9]+</version>' | tail -3
```

If the project is behind, flag it and suggest `legacy-migration` rather than bumping it here.

---

## Step 2 — Add dependencies

Add to `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

Also add for tests:

```xml
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-test</artifactId>
    <scope>test</scope>
</dependency>
```

---

## Step 3 — Security config

Create `src/main/java/<package>/shared/config/SecurityConfig.java`:

```java
package <package>.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthConverter jwtAuthConverter;

    public SecurityConfig(JwtAuthConverter jwtAuthConverter) {
        this.jwtAuthConverter = jwtAuthConverter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/info", "/api/public/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/**").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/**").hasRole("USER")
                .requestMatchers(HttpMethod.PUT, "/api/**").hasRole("USER")
                .requestMatchers(HttpMethod.DELETE, "/api/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter))
            );

        return http.build();
    }
}
```

Replace the path/role rules with what the user actually needs. Never leave `.anyRequest().permitAll()`
in a generated config.

---

## Step 4 — JWT claim converter

Create `src/main/java/<package>/shared/security/JwtAuthConverter.java`:

```java
package <package>.shared.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class JwtAuthConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String ROLES_CLAIM = "roles";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = extractAuthorities(jwt);
        return new JwtAuthenticationToken(jwt, authorities);
    }

    private Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(ROLES_CLAIM);
        if (roles == null) {
            return List.of();
        }
        return roles.stream()
            .map(role -> "ROLE_" + role.toUpperCase())
            .map(SimpleGrantedAuthority::new)
            .collect(Collectors.toList());
    }
}
```

If the roles live inside a nested claim (e.g. `realm_access.roles`), read the nested map in
`extractAuthorities`. See `references/security-conventions.md` for examples.

---

## Step 5 — Current user / AuditorAware

Create `src/main/java/<package>/shared/security/CurrentUser.java`:

```java
package <package>.shared.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;

public final class CurrentUser {

    private CurrentUser() {}

    public static Optional<String> id() {
        return principal().map(Jwt::getSubject);
    }

    public static Optional<String> email() {
        return principal().map(jwt -> jwt.getClaimAsString("email"));
    }

    private static Optional<Jwt> principal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        return Optional.of(jwt);
    }
}
```

If `spring-data-jpa` auditing is present, wire an `AuditorAware<String>` bean that reads the subject
from `CurrentUser.id()`.

---

## Step 6 — Update application.yml

Add:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${JWT_ISSUER_URI:https://auth.example.com/realms/app}
```

The `issuer-uri` lets Spring Security download the JWKS endpoint automatically. If the issuer cannot
be reached at startup (common in tests), switch to a local `JwtDecoder` bean with a hardcoded public
key — see the test example below.

---

## Step 7 — Tests

Create `src/test/java/<package>/shared/config/SecurityConfigTest.java`:

```java
package <package>.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void should_permit_public_health_endpoint() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk());
    }

    @Test
    void should_reject_unauthenticated_request_to_api() throws Exception {
        mockMvc.perform(get("/api/jobs"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void should_allow_authenticated_user_to_read() throws Exception {
        mockMvc.perform(get("/api/jobs"))
            .andExpect(status().isOk());
    }
}
```

For tests that exercise JWT decoding without an issuer, add a test-only `JwtDecoder` bean:

```java
@TestConfiguration
public class TestJwtDecoderConfig {

    @Bean
    public JwtDecoder jwtDecoder() {
        // return a mock or NimbusJwtDecoder with a local key
    }
}
```

---

## Step 8 — Run and report

```bash
./mvnw test
```

Report:

- Dependencies added
- `SecurityConfig`, `JwtAuthConverter`, `CurrentUser` created
- Public paths configured
- Example tests and result summary
- Reminder to set `JWT_ISSUER_URI` in production
- Next step: annotate service methods with `@PreAuthorize` or add user/role entities via `spring-data-jpa`
