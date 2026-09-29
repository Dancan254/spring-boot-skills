---
max_turns: 2
timeout_seconds: 180
allowed_tools: [Read, Glob, Grep, Skill]
tags: [routing]
---

The product lookup endpoint is slow. Cache it in Redis and rate limit the public API.
