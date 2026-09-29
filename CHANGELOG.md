# Changelog

All notable changes to this skill pack. Versions match the `version` field in every plugin manifest.

## [Unreleased]

### Added
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

### Removed
- `article` skill. The pack is engineering-only.

## [1.0.0]

- Initial release for Kimi Code CLI: 14 Spring Boot 4 engineering skills plus `article`.
