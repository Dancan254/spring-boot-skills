---
max_turns: 3
timeout_seconds: 180
allowed_tools: [Read, Glob, Grep, Skill]
tags: [routing]
---

Add a RabbitMQ work queue so this Spring Boot service can process invoice jobs in the background.
