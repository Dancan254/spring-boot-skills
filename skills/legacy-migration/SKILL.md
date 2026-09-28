---
name: legacy-migration
description: "Migrate an existing Spring Boot 2.x or 3.x application to Spring Boot 4.x — audit first, then phased version hops (2.7 → 3.5 → 4.0 → 4.1) led by OpenRewrite recipes for the mechanical work, hand-sweeps for the semantic remainder, and a verification ladder after every hop. Covers the Jakarta namespace jump, Jackson 3, modularized starters, property renames, and the behavior changes that compile green but break at runtime. Use when asked to upgrade Spring Boot, migrate Boot 2 to 4, do a Jakarta namespace migration, modernize a legacy Spring app, or fix javax imports after an upgrade."
---

# Legacy Migration Skill

Takes a Spring Boot 2.x/3.x app to Boot 4.x without losing the plot: **assess → automate the
mechanical 80% → hand-sweep the semantic 20% → prove every hop works before the next one.**

`SKILL_DIR` = the directory containing this SKILL.md.

**Load `SKILL_DIR/references/migration-reference.md` before writing anything** — the per-hop recipe
map, the javax→jakarta table, property renames, starter/API swaps, and the symptom → cause → fix
table for the changes that compile fine and break at runtime.

Two rules everything else hangs on:

1. **Never squash hops.** Each major version is its own migration with its own green build and its
   own commit. A 2.x → 4.x jump in one step makes every failure unattributable — you will not know
   whether the Jakarta jump, the Security 6 rewrite, or Jackson 3 broke the thing that broke.
2. **The audit comes before any edit.** You cannot size a migration you are guessing at, and a
   single third-party starter that hasn't shipped Boot 4 support blocks the whole last hop — that is
   something to learn from a grep in Step 1, not from a failed build in Step 5.

---

## The path

| Hop | From → To | Java floor | What breaks here |
|---|---|---|---|
| A | 2.x → **2.7.18** | 8/11 (unchanged) | Deprecations surface; circular references refused at startup since 2.6; PathPatternParser is the default matcher since 2.6 |
| B | 2.7.18 → **3.5.16** | **17** | Jakarta namespace (`javax.*` → `jakarta.*`), `WebSecurityConfigurerAdapter` gone, Hibernate 6, trailing-slash matching off by default |
| C | 3.5.16 → **4.0.x** | 17 (unchanged) | Jackson 3, modularized starters, `@MockBean`/`@SpyBean` removed, Testcontainers 2.x, Undertow removed |
| D | 4.0.x → **4.1.1** | 17 (unchanged) | Almost nothing — property deprecations, defaults tightened. A version bump with a checklist |

A Boot 2.x app walks all four. A Boot 3.x app starts at hop C (or B's tail if it isn't on 3.5 yet).
A Boot 4.0 app only needs hop D. State which entry point applies before doing anything.

Verify the current versions before pinning — these are the pins as of writing:

```bash
# latest Boot GA per line (Initializr lists in-flight versions too)
curl -s -H 'Accept: application/json' https://start.spring.io/metadata/client \
  | python3 -c "import json,sys; print([v['id'] for v in json.load(sys.stdin)['bootVersion']['values']])"

# latest 3.5.x and 2.7.x on Maven Central
curl -s "https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml" \
  | grep -oE '<version>(2\.7|3\.5)\.[0-9]+</version>' | tail -5
```

**Java floor per hop:** Boot 3 requires Java 17 — the JDK upgrade happens at hop B, not before and
not after. Boot 4 keeps the floor at 17 (25 supported). Upgrading the JDK *and* the framework in the
same commit as anything else is how diffs become unreviewable; the OpenRewrite recipe bumps the
compiler level as part of hop B, let it.

---

## Step 0 — Branch and git discipline

```bash
git checkout -b migration/boot-4
```

One commit per hop, message naming the hop (`migrate: Boot 2.7.18 → 3.5.16 (Jakarta)`). Never mix
migration with feature work — if the user asks for both, the migration finishes first. If the work
tree is dirty, stop and say so.

---

## Step 1 — Audit (before touching anything)

Run all of these. Every hit goes on the hit list with a category; the hit list is what sizes the
work and decides whether hop C is even possible today.

```bash
# current versions
grep -m1 -A2 'spring-boot-starter-parent\|spring-boot-dependencies' pom.xml
grep -m1 'java.version\|maven.compiler' pom.xml

# 1. javax exposure (hop B surface) — imports AND strings
grep -rn --include=*.java 'import javax\.' src/main src/test | cut -d: -f1 | sort -u | wc -l
grep -rn 'javax\.' src/main/resources src/main/webapp 2>/dev/null | head -20

# 2. APIs removed in Boot 3 / 4
grep -rn --include=*.java -e 'WebSecurityConfigurerAdapter' -e 'WebMvcConfigurerAdapter' \
  -e '@MockBean' -e '@SpyBean' -e 'springfox' src pom.xml | head -20

# 3. config shape and property risk
ls src/main/resources/application.* src/main/resources/bootstrap.* 2>/dev/null

# 4. Spring Cloud (must move to 2025.x at hop C; BOM must be upgraded in lockstep)
grep -n 'spring-cloud' pom.xml | head -10

# 5. custom auto-config and deep Boot integration
find src/main/resources -name 'spring.factories' -o -name '*.imports' | head
grep -rln --include=*.java -e 'EnvironmentPostProcessor' -e 'spring.autoconfigure.EnableAutoConfiguration' src/main | head

# 6. third-party starters — the blockers list
grep -B1 -A2 '<artifactId>.*-spring-boot-starter\|spring-boot-starter-.*</artifactId>' pom.xml \
  | grep -v 'org.springframework.boot' | head -30

# 7. test style
grep -rln --include=*.java -e 'org.junit.Test' -e '@RunWith' src/test | wc -l          # JUnit 4
grep -rn --include=*.java 'org.testcontainers.containers\.' src/test | head -10        # TC 1.x imports
grep -rn --include=*.java -e ':latest' src/test | head                                 # unpinned images

# 8. server + features with known Boot 4 removals
grep -n -e 'undertow' -e 'spring-session' -e 'spring-retry' -e 'launch.script' pom.xml
```

Report the audit as a short categorized list: *javax hits, removed-API hits, property risk, third-party
starters (with their Boot-4 status), test style, removals triggered*. **Check every third-party
starter against Boot 4 support now** — one unmaintained starter is a blocker to name out loud, not
to discover later.

---

## Step 2 — The OpenRewrite stance

OpenRewrite does the mechanical 80%: dependency versions, property renames, import swaps, removed
API replacements, starter renames. It does **not** see strings, XML, reflection, SpEL, bean names,
or behavior — that 20% is the hand-sweep in Step 6.

The Boot 4 recipes exist, are GA-grade, and ship in the community `rewrite-spring` artifact. The
composites chain across recipe artifacts, so the plugin classpath needs all four:

| Artifact | Pin (verify below) | Provides |
|---|---|---|
| `org.openrewrite.recipe:rewrite-spring` | 6.37.1 | All `UpgradeSpringBoot_*` recipes, Jakarta chaining, modular-starter migration |
| `org.openrewrite.recipe:rewrite-migrate-java` | 3.42.1 | `UpgradeToJava17`, `JakartaEE10` — chained by the 3.0 recipe; run fails without it |
| `org.openrewrite.recipe:rewrite-hibernate` | 2.25.0 | `MigrateToHibernate61` / `71` — chained by the 3.0 / 4.0 recipes |
| `org.openrewrite.recipe:rewrite-testing-frameworks` | 3.44.0 | `Testcontainers2Migration` — chained by the 4.0 recipe |

Missing artifacts fail with "recipe not found" — that error means the classpath, not the recipe name.

```bash
# verify the pins (Maven Central metadata)
for a in rewrite-spring rewrite-migrate-java rewrite-hibernate rewrite-testing-frameworks; do
  printf '%s: ' "$a"
  curl -s "https://repo1.maven.org/maven2/org/openrewrite/recipe/$a/maven-metadata.xml" \
    | grep -o '<latest>[^<]*' | sed 's/<latest>//'
done
curl -s "https://repo1.maven.org/maven2/org/openrewrite/maven/rewrite-maven-plugin/maven-metadata.xml" \
  | grep -o '<latest>[^<]*' | sed 's/<latest>//'
```

**Newer releases moved off Maven Central** into Moderne's authenticated Code Genome Project
repository. The Central pins above still resolve anonymously and contain every recipe this skill
uses — pin them; do not chase `RELEASE` into the gated repo.

No pom change is needed — the plugin runs from the command line, always `dryRun` before `run`:

```bash
./mvnw -U org.openrewrite.maven:rewrite-maven-plugin:6.46.1:dryRun \
  -Drewrite.recipeArtifactCoordinates=org.openrewrite.recipe:rewrite-spring:6.37.1,org.openrewrite.recipe:rewrite-migrate-java:3.42.1,org.openrewrite.recipe:rewrite-hibernate:2.25.0,org.openrewrite.recipe:rewrite-testing-frameworks:3.44.0 \
  -Drewrite.activeRecipes=<recipe>
# read target/rewrite/rewrite.patch, then re-run with :run
```

Gradle delta: apply the `org.openrewrite.rewrite` plugin with the same four artifacts in the
`rewrite` configuration, `activeRecipe('<recipe>')`, then `gradle rewriteDryRun` / `rewriteRun`.
Everything else in this skill is build-tool-neutral.

**Review the dry-run patch like production code.** A recipe that deletes something it can't prove
unused is the known failure mode; revert individual hunks with `git checkout -p` after `run`.

---

## Step 3 — Hop A: 2.x → 2.7.18

Recipe: `org.openrewrite.java.spring.boot2.UpgradeSpringBoot_2_7` (chains the whole 2.0→2.7 line).

The bump itself is small; the point is reaching the 2.7 baseline the 3.0 migration guide assumes,
with every deprecation warning visible. Two defaults already flipped back at 2.6 and bite now if
the app predates them — circular bean references are refused at startup, and PathPatternParser is
the default path matcher. Both are in the reference's symptom table; fix the cause, don't re-enable
the old behavior as a permanent answer.

Run the verification ladder (Step 7), commit.

---

## Step 4 — Hop B: 2.7.18 → 3.5.16 (the Jakarta jump)

Recipe: `org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_0` — this one chains the Java 17
bump, the full `javax.*` → `jakarta.*` migration (via `JakartaEE10`), Hibernate 6.1, the Spring
Framework 6 and Security 6 API migrations, and the Boot 3.0 property renames. Then bump the parent
to 3.5.16 by hand and resolve whatever deprecations the build surfaces.

**OpenRewrite rewrites imports. It does not see:**

- `persistence.xml`, `web.xml`, `orm.xml` — `javax.*` in schema locations and class names
- strings: `@Qualifier("javax.persistence...")`-style names, reflection (`Class.forName`),
  SpEL, `getProperty("javax...")`
- Logback/Log4j2 config, `spring.factories`, Spring XML config
- Hibernate dialect and `hibernate.*` properties in `application.yml`

The javax→jakarta table — including what does **not** move (`javax.sql`, `javax.net`,
`javax.naming`, `java.*` — over-migrating these breaks against the JDK) — is in the reference,
along with the exact greps for the string sweep.

**Security config:** `WebSecurityConfigurerAdapter` is gone in Security 6. The recipe converts
simple cases; anything with custom filters, multiple chains, or `authorizeRequests` nuance is
handwork. Target state is `SecurityFilterChain` beans — follow the **`spring-security`** skill for
the house conventions rather than inventing them here.

**Properties:** add `spring-boot-properties-migrator` (runtime scope), boot the app, read the
warnings, rename the keys, then **remove the migrator again**. It is a diagnostic, not a permanent
dependency — a migrator left in the pom silently papers over every stale key forever. The common
renames are tabled in the reference.

Run the verification ladder, commit.

---

## Step 5 — Hop C: 3.5.16 → 4.0.x

Recipe: `org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0` — chains Boot 3.5, Spring Cloud
2025.1, Framework 7, Security 7, Batch 5→6, the Boot 4.0 property renames, `@MockBean`→`@MockitoBean`,
Hibernate 7.1, Testcontainers 2.x, SpringDoc 3, and the modular-starter migration. There is **no**
`UpgradeSpringBoot_4_1` recipe — hop D is a version bump plus the properties recipe below.

If the recipe's starter reorganization is too much to review in one diff, the bridge is deliberate:
swap to `spring-boot-starter-classic` + `spring-boot-starter-test-classic` (the Boot-3-style
all-in classpath), get green, then decompose starter by starter using the compile errors as the
map. Land on the modular starters — classic is scaffolding, not a destination. The target layout is
the one **`spring-scaffold`** generates; use it as the reference for what "done" looks like.

Hand-sweep after the recipe, in this order:

1. **Jackson 3.** `com.fasterxml.jackson` → `tools.jackson` wherever application code surfaces
   Jackson — except `jackson-annotations`, which stays `com.fasterxml.jackson.annotation`. Custom
   serializers, mixins (`@JsonComponent` → `@JacksonComponent`), `ObjectMapper` beans (now
   `JsonMapper`), `Jackson2ObjectMapperBuilderCustomizer` (now `JsonMapperBuilderCustomizer`), and
   `spring.jackson.*` property moves are all tabled in the reference. The stop-gaps
   (`spring.jackson.use-jackson2-defaults=true`, the deprecated `spring-boot-jackson2` module) are
   for buying time, not for finishing — flag any use in the report.
2. **Removed features.** Undertow (gone — Servlet 6.1 floor), Spring Session Hazelcast/MongoDB,
   reactive Pulsar, uber-jar launch scripts, Spock integration, Spring Retry dependency management
   (explicit version now required), classic uber-jar loader config. Step 1's audit already named
   which apply.
3. **Test API.** `@MockBean`/`@SpyBean` are *removed*, not deprecated — the recipe rewrites field
   usage, but mocks declared on `@Configuration` classes need the `@MockitoBean(types=...)` class-level
   form. `@SpringBootTest` no longer provides MockMvc, `WebClient`, or `TestRestTemplate` without
   their `@AutoConfigure*` annotations. **`spring-testing`** is the authority for the target API —
   align with it, batch all test edits into one commit, run the suite once.
4. **Behavior changes that compile green.** Jackson now registers *all* classpath modules,
   liveness/readiness probes are on by default, DevTools live reload is off by default, optional
   dependencies no longer land in the uber jar. Each is a runtime check in the reference's symptom
   table — a green build here proves nothing.

Run the verification ladder, commit.

---

## Step 6 — Hop D and the manual sweep

**Hop D:** bump the parent to 4.1.1, run recipe
`org.openrewrite.java.spring.boot4.SpringBootProperties_4_1` for the 4.1 property renames
(the pre-4.1 flat OTLP keys `management.otlp.tracing.*` / `logging.*` are among them — the 4.1
spellings are in the **`otel-setup`** reference), rebuild, run the ladder, commit.

**The sweep** — categories the recipes structurally cannot cover. Each has its greps in the
reference:

- strings/XML/reflection remnants of `javax.` and `com.fasterxml.jackson`
- property keys the migrator warned about, cross-checked against the rename table
- third-party starter behavior (read their migration notes — Boot 4 forced every one of them into a
  major)
- custom auto-config: `spring.factories` → `AutoConfiguration.imports` (hop B), moved
  `EnvironmentPostProcessor`/`BootstrapRegistry` packages (hop C)
- actuator endpoints the app or its dashboards actually call — sanitization rules and probe
  defaults changed across both majors

---

## Step 7 — The verification ladder (after EVERY hop)

A migration step is done when the ladder passes, not when the build compiles:

1. `./mvnw clean verify` — green, and read the warnings; deprecations left behind here are next
   hop's breakage
2. App boots: `./mvnw spring-boot:run` (or `test-run` where the project has it) — clean startup,
   no bean-override or circular-reference backdoors enabled to force it
3. One real web request — `curl` a critical endpoint, expect the exact old response shape
4. One real DB query — an endpoint that hits the database, not a cached or mocked path
5. `curl localhost:8080/actuator/health` — `UP`, and on Boot 4 the liveness/readiness groups are
   exposed by default; confirm nothing downstream chokes on the new payload
6. The runtime checks from the reference's symptom table that apply to this hop — trailing-slash
   routes, date serialization formats, `@Configuration` mocks

Squashing hops skips ladders 2–6 on the intermediate versions — which is exactly where the
"green build, broken app" class of failure hides.

---

## Step 8 — Report

Lead with the verdict, then the board:

```
Boot 2.7.18 → 4.1.1 · 4 hops · suite green · runtime checks passed

━━━ LEGACY-MIGRATION ━━━━━━━━━━━━━━━━━━━━━━━
Audit ................. 41 javax files · 2 third-party starters (1 blocked: foo-bar-starter)
Hop A 2.7.18 .......... ✅ recipe + 3 manual fixes · ladder passed
Hop B 3.5.16 .......... ✅ Jakarta: 41 imports by recipe, 6 string hits by hand
Hop C 4.0.8 ........... ✅ Jackson 3 swept · starters decomposed (no classic left)
Hop D 4.1.1 ........... ✅ version bump + SpringBootProperties_4_1
OpenRewrite ........... 212 files changed across 3 recipe runs
Manual sweeps ......... 23 files (strings, XML, SecurityFilterChain, @Configuration mocks)
Properties migrator ... ✅ used at hop B, removed after
Test suite ............ ✅ 187 passing · 0 skipped
Remaining ............. ⚠  foo-bar-starter pinned to 3.x API — owner notified
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Next: re-enable the ci deploy stage on the migration branch and smoke the staging environment.
```

Mark a hop ⚠ with the reason if its ladder only partially passed — "verify green, staging untested"
is a fair ⚠; a green board nobody ran is not. `Next:` names the single most concrete remaining
action — typically deploying the migration branch somewhere real, or running **`spring-testing`**
to modernize the suite the migration just rewrote.
