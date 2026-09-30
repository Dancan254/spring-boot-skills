---
max_turns: 2
timeout_seconds: 180
allowed_tools: [Read, Glob, Grep, Skill]
tags: [routing]
---

This service calls an external payments API that keeps timing out. Make those HTTP calls resilient with retries and a circuit breaker.
