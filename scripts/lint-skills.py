#!/usr/bin/env python3
"""Structural lint for the skill pack. Exits non-zero on any failure."""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SKILLS = ROOT / "skills"
MAX_DESCRIPTION = 400
MAX_SKILL_LINES = 500
MANIFESTS = [".claude-plugin/plugin.json", "plugin.json", ".kimi-plugin/plugin.json"]

V = r"([0-9][0-9A-Za-z.-]*?)"
TAG = r"([0-9A-Za-z][0-9A-Za-z.-]*)"

# Where each pin appears in context; the value itself lives in VERSIONS.md.
PIN_PATTERNS = {
    "spring-boot": [
        r"bootVersion=(\d+\.\d+\.\d+)",
        r"confirm `(\d+\.\d+\.\d+)` is still the latest stable Boot",
        r"`(\d+\.\d+\.\d+)\.RELEASE` in the metadata is `(\d+\.\d+\.\d+)`",
        r"Boot \**(4\.\d+\.\d+)\b(?!\.)",
        r"→ \**(4\.\d+\.\d+)\b",
        r"parent to (\d+\.\d+\.\d+)",
        r"Hop D (\d+\.\d+\.\d+)",
        r"the (\d+\.\d+\.\d+) (?:configuration )?metadata",
        r"against the (\d+\.\d+\.\d+) /",
        r"spring-boot-dependencies[/-](\d+\.\d+\.\d+)",
        r"^\| (4\.\d+\.\d+) \| 1\.\d+\.\d+ \|",
    ],
    "testcontainers (Boot BOM)": [r"Testcontainers\s+\**(\d+\.\d+\.\d+)\b"],
    "opentelemetry-api (Boot BOM)": [
        r"`opentelemetry\.version` (\d+\.\d+\.\d+)",
        r"OTel API (\d+\.\d+\.\d+)",
        r"^\| \d+\.\d+\.\d+ \| (\d+\.\d+\.\d+) \|",
    ],
    "opentelemetry-logback-appender": [r"(\d+\.\d+\.\d+-alpha)"],
    "spring-ai-bom": [
        r"<artifactId>spring-ai-bom</artifactId>\s*<version>" + V + "</version>",
        r"^\| (\d+\.\d+\.\d+) \| 4\.0\.x / 4\.1\.x \|",
    ],
    "springdoc-openapi": [r"<artifactId>springdoc-openapi-starter-[\w-]+</artifactId>\s*<version>" + V + "</version>"],
    "testcontainers-redis": [
        r"<artifactId>testcontainers-redis</artifactId>\s*<version>" + V + "</version>",
        r"confirm `" + V + r"` is still the latest before writing",
    ],
    "bucket4j": [r"<artifactId>bucket4j[\w-]*</artifactId>\s*<version>" + V + "</version>"],
    "dependency-check-maven": [r"<artifactId>dependency-check-maven</artifactId>\s*<version>" + V + "</version>"],
    "cyclonedx-maven-plugin": [r"<artifactId>cyclonedx-maven-plugin</artifactId>\s*<version>" + V + "</version>"],
    "rewrite-maven-plugin": [r"rewrite-maven-plugin[: ](\d+\.\d+\.\d+)"],
    "image postgres": [r"(?<![\w/.])postgres:" + TAG],
    "image pgvector/pgvector": [r"pgvector/pgvector:" + TAG],
    "image grafana/otel-lgtm": [r"grafana/otel-lgtm:" + TAG, r"confirm `" + TAG + r"` is still the newest `grafana/otel-lgtm`"],
    "image apache/kafka": [r"apache/kafka:" + TAG, r"confirm `" + TAG + r"` is still current:\s*```bash\s*curl -s \"https://hub\.docker\.com/v2/repositories/apache/kafka"],
    "image rabbitmq": [r"(?<![\w/.])rabbitmq:" + TAG],
    "image redis": [r"(?<![\w/.])redis:" + TAG],
    "image ollama/ollama": [r"ollama/ollama:" + TAG],
    "image localstack/localstack": [r"localstack/localstack:" + TAG],
    "action actions/checkout": [r"actions/checkout@(v\d+)"],
    "action actions/setup-java": [r"actions/setup-java@(v\d+)"],
    "action actions/upload-artifact": [r"actions/upload-artifact@(v\d+)"],
    "action aquasecurity/trivy-action": [r"aquasecurity/trivy-action@" + TAG],
    "action github/codeql-action": [r"github/codeql-action/[\w-]+@(v\d+)"],
}

errors = []


def fail(message):
    errors.append(message)


def skill_dirs():
    return sorted(p for p in SKILLS.iterdir() if p.is_dir())


def frontmatter(text):
    match = re.match(r"^---\n(.*?)\n---\n", text, re.S)
    if not match:
        return None
    fields = {}
    for line in match.group(1).splitlines():
        key, sep, value = line.partition(":")
        if sep:
            fields[key.strip()] = value.strip().strip('"')
    return fields


def check_skills():
    for skill in skill_dirs():
        skill_md = skill / "SKILL.md"
        rel = skill_md.relative_to(ROOT)
        if not skill_md.exists():
            fail(f"{skill.relative_to(ROOT)}: missing SKILL.md")
            continue
        text = skill_md.read_text()
        fields = frontmatter(text)
        if fields is None:
            fail(f"{rel}: no YAML frontmatter")
            continue
        if fields.get("name") != skill.name:
            fail(f"{rel}: name '{fields.get('name')}' does not match folder '{skill.name}'")
        description = fields.get("description", "")
        if not description:
            fail(f"{rel}: empty description")
        if len(description) > MAX_DESCRIPTION:
            fail(f"{rel}: description is {len(description)} chars (max {MAX_DESCRIPTION})")
        line_count = text.count("\n")
        if line_count > MAX_SKILL_LINES:
            fail(f"{rel}: {line_count} lines (max {MAX_SKILL_LINES}) — move fixed content to assets/templates/")
        for ref in sorted(set(re.findall(r"(?:(?<=SKILL_DIR/)|(?<![\w/.-]))(?:references|assets/templates)/[\w./-]*\w", text))):
            if not (skill / ref).exists():
                fail(f"{rel}: references missing file {ref}")
        for sibling_path in sorted(set(re.findall(r"SKILL_DIR/\.\./([\w-]+/[\w./-]*\w)", text))):
            if not (SKILLS / sibling_path).exists():
                fail(f"{rel}: points at missing sibling file {sibling_path}")


def check_tool_neutral():
    for path in sorted(SKILLS.rglob("*")):
        if path.is_file() and re.search(r"kimi", path.read_text(errors="ignore"), re.I):
            fail(f"{path.relative_to(ROOT)}: mentions Kimi — skill bodies must stay tool-neutral")


def read_pins():
    pins = {}
    for line in (ROOT / "VERSIONS.md").read_text().splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) != 4 or cells[0] in ("Pin", "") or set(cells[0]) <= set("-"):
            continue
        name, value, allowed, _owner = cells
        pins[name] = {value, *(a.strip() for a in allowed.split(",") if a.strip())}, value
    return pins


def check_pins():
    pins = read_pins()
    for name in sorted(set(pins) ^ set(PIN_PATTERNS)):
        where = "VERSIONS.md" if name not in pins else "scripts/lint-skills.py"
        fail(f"pin '{name}' is missing from {where}")
    files = [p for p in sorted(SKILLS.rglob("*")) if p.is_file()]
    for name, patterns in PIN_PATTERNS.items():
        if name not in pins:
            continue
        accepted, value = pins[name]
        seen = False
        for path in files:
            text = path.read_text(errors="ignore")
            for pattern in patterns:
                for match in re.finditer(pattern, text, re.M):
                    for found in filter(None, match.groups()):
                        seen = True
                        if found not in accepted:
                            line = text.count("\n", 0, match.start()) + 1
                            fail(f"{path.relative_to(ROOT)}:{line}: {name} is '{found}', VERSIONS.md says '{value}'")
        if not seen:
            fail(f"pin '{name}' not found in any skill — stale row in VERSIONS.md or broken pattern")


def check_manifests():
    loaded = {}
    for manifest in MANIFESTS:
        try:
            loaded[manifest] = json.loads((ROOT / manifest).read_text())
        except (OSError, json.JSONDecodeError) as ex:
            fail(f"{manifest}: {ex}")
    if "plugin.json" in loaded and not loaded["plugin.json"].get("$schema"):
        fail("plugin.json: Agent Plugins manifest requires $schema")
    for field in ("name", "version", "description"):
        values = {m: data.get(field) for m, data in loaded.items()}
        if len(set(values.values())) > 1:
            fail(f"manifests disagree on '{field}': {values}")


check_skills()
check_tool_neutral()
check_pins()
check_manifests()

for message in errors:
    print(f"✗ {message}")
if errors:
    print(f"\n{len(errors)} problem(s)")
    sys.exit(1)
print(f"✔ {len(skill_dirs())} skills, {len(PIN_PATTERNS)} pins, {len(MANIFESTS)} manifests — all consistent")
