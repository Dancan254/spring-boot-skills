# Pinned versions

The single source of truth for every version, image tag, and action tag the skills write.
`scripts/LintSkills.java` fails CI when any skill disagrees with this table.

**To bump a pin:** run the verification command in the owning skill, change the value here, run
`java scripts/LintSkills.java`, and fix every file it lists. Log the bump in `CHANGELOG.md`.

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
| testcontainers-floci | 2.16.1 | | spring-testing |
| image postgres | 18-alpine | | spring-testing |
| image pgvector/pgvector | 0.8.6-pg18 | | spring-ai-rag |
| image grafana/otel-lgtm | 0.34.0 | | otel-setup |
| image apache/kafka | 4.3.1 | | kafka-setup |
| image rabbitmq | 4-management-alpine | | rabbitmq-setup |
| image redis | 8.10.2-alpine | | redis-setup |
| image ollama/ollama | 0.35.0 | | spring-ai-rag |
| image floci/floci | 2.1.0 | | spring-testing |
| image mongo | 8.3.11 | | spring-testing |
| zap docker image | 2.17.0 | | pentest-audit |
| resilience4j | 2.4.0 | | http-resilience |
| action actions/checkout | v7 | | devops-scaffold |
| action actions/setup-java | v6 | | devops-scaffold |
| action actions/upload-artifact | v7 | | devops-scaffold |
| action aquasecurity/trivy-action | v0.36.0 | | security-hardening |
| action github/codeql-action | v4 | | security-hardening |
