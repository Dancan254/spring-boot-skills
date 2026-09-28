# Kimi Development Skills — Agent Notes

## What this repo is

A collection of Kimi Code CLI skills for Spring Boot / Java backend engineering — plus the `article`
content skill. Each skill lives in `skills/<name>/SKILL.md` and may include `references/` files that
the skill loads before doing work.

Engineering skills: `spring-scaffold`, `spring-data-jpa`, `redis-setup`, `spring-security`,
`api-design`, `spring-testing`, `devops-scaffold`, `otel-setup`, `kafka-setup`, `rabbitmq-setup`,
`security-hardening`, `spring-ai-rag`, `mcp-server`, `legacy-migration`.
Content skill: `article`.

This is a meta-project: the deliverables are the skill files themselves, not a running application.

Kimi Code CLI discovers skills project-locally from `.kimi-code/skills/` or `.agents/skills/` in
the project root, globally from `~/.kimi-code/skills/` or `~/.agents/skills/`, or as a plugin via
`.kimi-plugin/plugin.json`. See `README.md` for install instructions.

The `article` skill is the exception to the terse-engineering style. It loads
`references/voice-profile.md` and `references/article-craft.md` and drafts in the user's personal
voice. The voice profile ships as a template with placeholders; remind the user to fill it in before
running the first article draft.

## Skill conventions

- Every `SKILL.md` must start with YAML frontmatter containing `name:` and `description:`.
- The `description:` is the trigger phrase. It should be specific enough to match the right intent but broad enough to catch natural language variants.
- `SKILL_DIR` = the directory containing the `SKILL.md`. Skills should load `SKILL_DIR/references/<file>.md` when they need extra context.
- Generated code and config should have minimal comments — only `WHY`, never `WHAT`.
- Every generated artifact must include a concrete next step for the user.
- Pins (versions, image tags, action tags) are current defaults; each skill must include the verification command so the agent can re-check before writing.

## When editing these skills

- Keep the Kimi Code CLI framing — skills are invoked by natural-language intent, not by slash command.
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
- `README.md` is the human-facing front door; keep it in sync with the skill list.
