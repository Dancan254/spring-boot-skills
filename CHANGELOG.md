# Changelog

All notable changes to this skill pack. Versions match the `version` field in every plugin manifest.

## [Unreleased]

### Changed
- Pins refreshed: `grafana/otel-lgtm` 0.34.0, `redis` 8.10.2-alpine, `ollama/ollama` 0.35.0,
  `github/codeql-action` v4.
- The skill lint also checks each image's Docker Hub verification command and the "confirm `<tag>`"
  line before it.

- `spring-testing` emulates AWS with Floci (`floci/floci:2.1.0`, `io.floci:*testcontainers-floci`
  2.16.1, with `@ServiceConnection` for Spring Cloud AWS) instead of LocalStack, which now needs an auth
  token on every run.
- `mongo` pinned to `8.3.11`; it floated on `mongo:8`.
- `spring-testing` uses `com.redis.testcontainers.RedisContainer` for Redis, matching `redis-setup`,
  instead of a `GenericContainer` that `@ServiceConnection` only matched by image name.

### Fixed
- `security-hardening` referenced `aquasecurity/trivy-action@0.36.0`, a tag that does not exist; it is
  `v0.36.0`.

## [1.1.0] - 2026-09-29

### Added
- `VERSIONS.md` as the single source of truth for every pin, and `scripts/lint-skills.py`, which
  fails when a skill disagrees with it or breaks frontmatter, reference, size, or neutrality rules.
- `Lint` GitHub Actions workflow: skill lint plus `claude plugin validate`.
- Claude Code plugin and marketplace manifests (`.claude-plugin/`).
- Portable Agent Plugins manifest (`plugin.json`) for Codex.
- `CLAUDE.md` importing `AGENTS.md` for contributors using Claude Code.
- Install instructions for Claude Code, Codex, and Kimi Code CLI.

### Changed
- Skill bodies and `AGENTS.md` are tool-neutral; tool-specific steps live in `README.md` only.
- Every skill description trimmed to ~290–340 characters, with a "not for X, use Y" clause where
  skills overlap, and "Maven" stated for the Maven-only skills.
- `kafka-setup` and `rabbitmq-setup` ask which broker to use when the request doesn't name one.
- `spring-security` verifies the project's Boot version against the latest 4.x GA.
- `spring-scaffold` cut from 904 to 428 lines: fixed file content moved to `assets/templates/`, and the
  Dockerfile, CI, and OTLP log-export steps now hand off to `devops-scaffold` and `otel-setup`.
- `spring-scaffold` defaults `outputDir` to `./<name>` in the current directory, states its
  Lombok/PostgreSQL/OTel defaults up front, and accepts `lombok: false` and `otel: false`.
- `devops-scaffold` CI cancels superseded runs (`concurrency`), carried over from the scaffold's copy.

### Fixed
- Redis image pinned to `8.8.3-alpine` everywhere; `devops-scaffold` and `spring-testing` floated on
  `8-alpine`.
- All three manifests share one description.

### Removed
- `article` skill. The pack is engineering-only.

## [1.0.0]

- Initial release for Kimi Code CLI: 14 Spring Boot 4 engineering skills plus `article`.
