# Spring Boot Skills — Agent Notes

## What this repo is

A collection of agent skills for Spring Boot / Java backend engineering. Each skill lives in
`skills/<name>/SKILL.md` and may include `references/` files that the skill loads before doing work.

Engineering skills: `spring-scaffold`, `spring-data-jpa`, `redis-setup`, `spring-security`,
`api-design`, `spring-testing`, `devops-scaffold`, `otel-setup`, `kafka-setup`, `rabbitmq-setup`,
`security-hardening`, `spring-ai-rag`, `mcp-server`, `legacy-migration`.

This is a meta-project: the deliverables are the skill files themselves, not a running application.

The skills use the shared `SKILL.md` format and run in Claude Code, Codex, and Kimi Code CLI. Each
tool has a thin manifest: `.claude-plugin/` (Claude Code), root `plugin.json` (Agent Plugins /
Codex), `.kimi-plugin/` (Kimi). All three point at the same `skills/` folder. See `README.md` for
install instructions.

## Skill conventions

- Every `SKILL.md` must start with YAML frontmatter containing `name:` and `description:`.
- The `description:` is the trigger phrase. It should be specific enough to match the right intent but broad enough to catch natural language variants.
- `SKILL_DIR` = the directory containing the `SKILL.md`. Skills should load `SKILL_DIR/references/<file>.md` when they need extra context.
- Keep each `SKILL.md` under ~500 lines. Fixed file content goes in `SKILL_DIR/assets/templates/`; the
  `SKILL.md` keeps the steps and the reasoning.
- Don't duplicate another skill's step. Hand off instead ("apply Step 3 of `otel-setup`") so each
  pin and template has one owner.
- Generated code and config should have minimal comments — only `WHY`, never `WHAT`.
- Every generated artifact must include a concrete next step for the user.
- Pins (versions, image tags, action tags) are current defaults; each skill must include the verification command so the agent can re-check before writing.

## When editing these skills

- Keep skill bodies tool-neutral — no tool or brand names. Skills are invoked by natural-language
  intent. Tool-specific install steps live in `README.md` only.
- Preserve the source attribution in spirit but do not copy `@your_javaguy` brand references. Use neutral, direct language.
- Update `references/` files when the corresponding `SKILL.md` changes so they stay consistent.
- If you add a new skill, add it to `README.md` and update this `AGENTS.md`.

## Brand voice

- Direct, terse, and practical.
- Prefer tables and boards over prose.
- Lead with the verdict, then the details.
- No emojis in generated files unless the user asks.

## Files to protect

- `skills/*/references/*.md` are loaded by the skill at runtime. Do not delete or rename them without updating the loading instruction in the parent `SKILL.md`.
- `skills/*/assets/templates/**` are copied into generated projects. Same rule: renaming one means updating
  the `SKILL.md` step that names it.
- `README.md` is the human-facing front door; keep it in sync with the skill list.
- Keep `name`, `version`, and `description` in sync across `.claude-plugin/plugin.json`,
  `plugin.json`, and `.kimi-plugin/plugin.json`, and log every release in `CHANGELOG.md`.
