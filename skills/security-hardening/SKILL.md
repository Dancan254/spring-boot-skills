---
name: security-hardening
description: "Add DevSecOps checks to an existing Maven project — OWASP dependency check, secrets scanning, container image scanning, SBOM, and a security GitHub Actions workflow. Use when asked to harden a project, scan dependencies or images, add an SBOM, or set up security CI. Not for app authentication — use spring-security."
---

# Security Hardening Skill

Adds security scanning and supply-chain hardening to an existing Spring Boot 4 project.

`SKILL_DIR` = directory containing this SKILL.md file.

Load `SKILL_DIR/references/hardening-checklist.md` before configuring scanners — it covers plugin
config, suppression rules, CI job ordering, and false-positive handling.

---

## Step 0 — Gather inputs

| Field | Required | Notes |
|-------|----------|-------|
| `enableDependencyCheck` | No | `true` (default) — OWASP dependency-check |
| `enableSecretsScan` | No | `true` (default) — TruffleHog |
| `enableContainerScan` | No | `true` (default) — Trivy image scan |
| `enableSbom` | No | `true` (default) — CycloneDX SBOM |

---

## Step 1 — Read the project

```bash
cat pom.xml
ls .github/workflows/ 2>/dev/null
cat Dockerfile 2>/dev/null
ls src/main/resources/ 2>/dev/null
```

Confirm:
- Maven project.
- `Dockerfile` exists (for container scan).
- Existing workflows so we can merge rather than overwrite.

---

## Step 2 — Add Maven plugins

Add to `pom.xml` inside `<build><plugins>`:

```xml
<plugin>
    <groupId>org.owasp</groupId>
    <artifactId>dependency-check-maven</artifactId>
    <version>13.0.0</version>
    <configuration>
        <failBuildOnCVSS>7</failBuildOnCVSS>
        <suppressionFiles>
            <suppressionFile>dependency-check-suppressions.xml</suppressionFile>
        </suppressionFiles>
    </configuration>
</plugin>

<plugin>
    <groupId>org.cyclonedx</groupId>
    <artifactId>cyclonedx-maven-plugin</artifactId>
    <version>2.9.3</version>
</plugin>
```

Before writing, verify the latest versions:

```bash
curl -s "https://repo1.maven.org/maven2/org/owasp/dependency-check-maven/maven-metadata.xml" \
  | python3 -c "import sys,re; print(re.findall(r'<version>(.*?)</version>', sys.stdin.read())[-1])"

curl -s "https://repo1.maven.org/maven2/org/cyclonedx/cyclonedx-maven-plugin/maven-metadata.xml" \
  | python3 -c "import sys,re; print(re.findall(r'<version>(.*?)</version>', sys.stdin.read())[-1])"
```

Create `dependency-check-suppressions.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<suppressions xmlns="https://jeremylong.github.io/DependencyCheck/dependency-suppression.1.3.xsd">
    <!-- Example:
    <suppress>
        <notes>False positive for internal-only test dependency</notes>
        <cve>CVE-20XX-XXXXX</cve>
    </suppress>
    -->
</suppressions>
```

---

## Step 3 — Security workflow

Create or merge `.github/workflows/security.yml`:

```yaml
name: Security

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]
  schedule:
    - cron: '17 4 * * 1'   # weekly Monday 04:17

permissions:
  contents: read
  actions: read

jobs:
  dependency-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with:
          java-version: '25'
          distribution: 'temurin'
          cache: maven
      - name: Run OWASP dependency check
        run: ./mvnw dependency-check:check
      - uses: actions/upload-artifact@v7
        if: always()
        with:
          name: dependency-check-report
          path: target/dependency-check-report.html

  secrets-scan:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - name: Secret scan
        uses: trufflesecurity/trufflehog@main
        with:
          path: ./
          base: main
          head: HEAD
          extra_args: --debug --only-verified

  container-scan:
    runs-on: ubuntu-latest
    needs: build-image
    steps:
      - uses: actions/checkout@v7
      - name: Build image
        run: docker build -t app:${{ github.sha }} .
      - name: Scan image with Trivy
        uses: aquasecurity/trivy-action@0.36.0
        with:
          image-ref: app:${{ github.sha }}
          format: sarif
          output: trivy-results.sarif
      - uses: github/codeql-action/upload-sarif@v3
        if: always()
        with:
          sarif_file: trivy-results.sarif

  sbom:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with:
          java-version: '25'
          distribution: 'temurin'
          cache: maven
      - name: Generate SBOM
        run: ./mvnw cyclonedx:makeBom
      - uses: actions/upload-artifact@v7
        with:
          name: sbom
          path: target/bom.json
```

Before writing, verify the action tags:

```bash
for r in actions/checkout actions/setup-java actions/upload-artifact aquasecurity/trivy-action github/codeql-action; do
  printf '%s: ' "$r"
  curl -s "https://api.github.com/repos/$r/releases/latest" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['tag_name'])"
done
```

If a workflow already exists, merge carefully — add jobs, don't remove existing ones.

---

## Step 4 — SECURITY.md

Create `SECURITY.md`:

```markdown
# Security policy

## Supported versions

| Version | Supported |
|---------|-----------|
| main    | yes       |

## Reporting a vulnerability

Email security@example.com or open a private vulnerability report via GitHub.

Do not open public issues for security bugs.
```

Replace the contact with a real address or GitHub reporting link.

---

## Step 5 — Dockerfile hardening

If `Dockerfile` exists, verify and harden:

- Multi-stage build.
- Non-root `USER`.
- Minimal JRE image (`eclipse-temurin:<version>-jre-alpine`).
- No secrets in layers.
- Distroless or `-jre-alpine` runtime.

If the generated Dockerfile from `spring-scaffold` is present, it already follows these rules. Add a
comment only if something needs changing.

---

## Step 6 — Run locally

```bash
./mvnw dependency-check:check
./mvnw cyclonedx:makeBom
```

Dependency check downloads an NVD database on first run — it takes several minutes and prints
progress. The second run is fast.

---

## Step 7 — Report

Report:

- Plugins added and their versions
- Workflow jobs created
- `SECURITY.md` and `dependency-check-suppressions.xml` created
- Whether the Dockerfile needed hardening
- Local scan command results (or note if first-run database download is in progress)
- Next step: review `dependency-check-report.html` and triage findings; add justified suppressions to
  `dependency-check-suppressions.xml`
