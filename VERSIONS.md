# Pinned versions

The single source of truth for every version, image tag, and action tag the skills write.
`scripts/lint-skills.py` fails CI when any skill disagrees with this table.

**To bump a pin:** run the verification command in the owning skill, change the value here, run
`python3 scripts/lint-skills.py`, and fix every file it lists. Log the bump in `CHANGELOG.md`.

"Also allowed" lists values that legitimately appear in prose next to the pin, such as version floors
or intermediate migration hops. They are not alternatives to the pin.

| Pin | Value | Also allowed | Owner skill |
|-----|-------|--------------|-------------|
| spring-boot | 4.1.1 | 4.0.0, 4.0.1, 4.0.8 | spring-scaffold |
| testcontainers (Boot BOM) | 2.0.5 | | spring-testing |
| opentelemetry-api (Boot BOM) | 1.62.0 | | otel-setup |
| opentelemetry-logback-appender | 2.28.1-alpha | | otel-setup |
| spring-ai-bom | 2.0.1 | | spring-ai-rag |
| springdoc-openapi | 3.1.1 | | api-design |
| testcontainers-redis | 2.2.4 | | redis-setup |
| bucket4j | 8.20.0 | | redis-setup |
| dependency-check-maven | 13.0.0 | | security-hardening |
| cyclonedx-maven-plugin | 2.9.3 | | security-hardening |
| rewrite-maven-plugin | 6.46.1 | | legacy-migration |
| image postgres | 18-alpine | | spring-testing |
| image pgvector/pgvector | 0.8.6-pg18 | | spring-ai-rag |
| image grafana/otel-lgtm | 0.33.1 | | otel-setup |
| image apache/kafka | 4.3.1 | | kafka-setup |
| image rabbitmq | 4-management-alpine | | rabbitmq-setup |
| image redis | 8.8.3-alpine | | redis-setup |
| image ollama/ollama | 0.34.4 | | spring-ai-rag |
| image localstack/localstack | 4 | | spring-testing |
| action actions/checkout | v7 | | devops-scaffold |
| action actions/setup-java | v6 | | devops-scaffold |
| action actions/upload-artifact | v7 | | devops-scaffold |
| action aquasecurity/trivy-action | 0.36.0 | | security-hardening |
| action github/codeql-action | v3 | | security-hardening |
