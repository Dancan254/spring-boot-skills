import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Structural lint for the skill pack. Exits non-zero on any failure. Run: java scripts/LintSkills.java */
public class LintSkills {

    static final Path ROOT = Path.of("").toAbsolutePath();
    static final Path SKILLS = ROOT.resolve("skills");
    static final int MAX_DESCRIPTION = 400;
    static final int MAX_SKILL_LINES = 500;
    static final List<String> MANIFESTS = List.of(
            ".claude-plugin/plugin.json", "plugin.json", ".kimi-plugin/plugin.json");

    static final String V = "([0-9][0-9A-Za-z.-]*?)";
    static final String TAG = "([0-9A-Za-z][0-9A-Za-z.-]*)";

    // Where each pin appears in context; the value itself lives in VERSIONS.md.
    static final Map<String, List<String>> PIN_PATTERNS = new LinkedHashMap<>();
    static {
        PIN_PATTERNS.put("spring-boot", List.of(
                "bootVersion=(\\d+\\.\\d+\\.\\d+)",
                "confirm `(\\d+\\.\\d+\\.\\d+)` is still the latest stable Boot",
                "`(\\d+\\.\\d+\\.\\d+)\\.RELEASE` in the metadata is `(\\d+\\.\\d+\\.\\d+)`",
                "Boot \\**(4\\.\\d+\\.\\d+)\\b(?!\\.)",
                "→ \\**(4\\.\\d+\\.\\d+)\\b",
                "parent to (\\d+\\.\\d+\\.\\d+)",
                "Hop D (\\d+\\.\\d+\\.\\d+)",
                "the (\\d+\\.\\d+\\.\\d+) (?:configuration )?metadata",
                "against the (\\d+\\.\\d+\\.\\d+) /",
                "spring-boot-dependencies[/-](\\d+\\.\\d+\\.\\d+)",
                "^\\| (4\\.\\d+\\.\\d+) \\| 1\\.\\d+\\.\\d+ \\|"));
        PIN_PATTERNS.put("testcontainers (Boot BOM)", List.of(
                "Testcontainers\\s+\\**(\\d+\\.\\d+\\.\\d+)\\b"));
        PIN_PATTERNS.put("opentelemetry-api (Boot BOM)", List.of(
                "`opentelemetry\\.version` (\\d+\\.\\d+\\.\\d+)",
                "OTel API (\\d+\\.\\d+\\.\\d+)",
                "^\\| \\d+\\.\\d+\\.\\d+ \\| (\\d+\\.\\d+\\.\\d+) \\|"));
        PIN_PATTERNS.put("opentelemetry-logback-appender", List.of(
                "(\\d+\\.\\d+\\.\\d+-alpha)"));
        PIN_PATTERNS.put("spring-ai-bom", List.of(
                "<artifactId>spring-ai-bom</artifactId>\\s*<version>" + V + "</version>",
                "^\\| (\\d+\\.\\d+\\.\\d+) \\| 4\\.0\\.x / 4\\.1\\.x \\|"));
        PIN_PATTERNS.put("springdoc-openapi", List.of(
                "<artifactId>springdoc-openapi-starter-[\\w-]+</artifactId>\\s*<version>" + V + "</version>"));
        PIN_PATTERNS.put("testcontainers-redis", List.of(
                "<artifactId>testcontainers-redis</artifactId>\\s*<version>" + V + "</version>",
                "confirm `" + V + "` is still the latest before writing"));
        PIN_PATTERNS.put("bucket4j", List.of(
                "<artifactId>bucket4j[\\w-]*</artifactId>\\s*<version>" + V + "</version>"));
        PIN_PATTERNS.put("dependency-check-maven", List.of(
                "<artifactId>dependency-check-maven</artifactId>\\s*<version>" + V + "</version>"));
        PIN_PATTERNS.put("cyclonedx-maven-plugin", List.of(
                "<artifactId>cyclonedx-maven-plugin</artifactId>\\s*<version>" + V + "</version>"));
        PIN_PATTERNS.put("rewrite-maven-plugin", List.of(
                "rewrite-maven-plugin[: ](\\d+\\.\\d+\\.\\d+)"));
        PIN_PATTERNS.put("testcontainers-floci", List.of(
                "<artifactId>(?:spring-boot-)?testcontainers-floci</artifactId>\\s*<version>" + V + "</version>"));
        PIN_PATTERNS.put("image postgres", List.of("(?<![\\w/.])postgres:" + TAG));
        PIN_PATTERNS.put("image pgvector/pgvector", List.of("pgvector/pgvector:" + TAG));
        PIN_PATTERNS.put("image grafana/otel-lgtm", List.of("grafana/otel-lgtm:" + TAG));
        PIN_PATTERNS.put("image apache/kafka", List.of("apache/kafka:" + TAG));
        PIN_PATTERNS.put("image rabbitmq", List.of("(?<![\\w/.])rabbitmq:" + TAG));
        PIN_PATTERNS.put("image redis", List.of("(?<![\\w/.])redis:" + TAG));
        PIN_PATTERNS.put("image ollama/ollama", List.of("ollama/ollama:" + TAG));
        PIN_PATTERNS.put("image floci/floci", List.of("floci/floci:" + TAG));
        PIN_PATTERNS.put("image mongo", List.of("(?<![\\w/.])mongo:" + TAG));
        PIN_PATTERNS.put("action actions/checkout", List.of("actions/checkout@(v\\d+)"));
        PIN_PATTERNS.put("action actions/setup-java", List.of("actions/setup-java@(v\\d+)"));
        PIN_PATTERNS.put("action actions/upload-artifact", List.of("actions/upload-artifact@(v\\d+)"));
        PIN_PATTERNS.put("action aquasecurity/trivy-action", List.of("aquasecurity/trivy-action@" + TAG));
        PIN_PATTERNS.put("action github/codeql-action", List.of("github/codeql-action/[\\w-]+@(v\\d+)"));
        // Every image pin also appears in its Docker Hub check command and the "confirm `<tag>`" line before it.
        for (Map.Entry<String, List<String>> e : PIN_PATTERNS.entrySet()) {
            if (e.getKey().startsWith("image ")) {
                String repo = e.getKey().substring("image ".length());
                String hub = Pattern.quote(repo.contains("/") ? repo : "library/" + repo);
                List<String> patterns = new ArrayList<>(e.getValue());
                patterns.add("repositories/" + hub + "/tags/" + TAG);
                patterns.add("confirm `" + TAG + "`[^\\n]*:\\s*```bash\\s*curl -s \"https://hub\\.docker\\.com/v2/repositories/"
                        + hub + "/");
                e.setValue(patterns);
            }
        }
    }

    static final List<String> errors = new ArrayList<>();

    static void fail(String message) {
        errors.add(message);
    }

    static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Path> skillDirs() throws IOException {
        try (Stream<Path> s = Files.list(SKILLS)) {
            return s.filter(Files::isDirectory).sorted().toList();
        }
    }

    static Map<String, String> frontmatter(String text) {
        Matcher m = Pattern.compile("^---\n(.*?)\n---\n", Pattern.DOTALL).matcher(text);
        if (!m.find()) {
            return null;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : m.group(1).split("\n")) {
            int colon = line.indexOf(':');
            if (colon >= 0) {
                fields.put(line.substring(0, colon).trim(),
                        line.substring(colon + 1).trim().replaceAll("^\"|\"$", ""));
            }
        }
        return fields;
    }

    static void checkSkills() throws IOException {
        Pattern refRe = Pattern.compile(
                "(?:(?<=SKILL_DIR/)|(?<![\\w/.-]))(?:references|assets/templates)/[\\w./-]*\\w");
        Pattern siblingRe = Pattern.compile("SKILL_DIR/\\.\\./([\\w-]+/[\\w./-]*\\w)");
        for (Path skill : skillDirs()) {
            Path skillMd = skill.resolve("SKILL.md");
            Path rel = ROOT.relativize(skillMd);
            if (!Files.exists(skillMd)) {
                fail(ROOT.relativize(skill) + ": missing SKILL.md");
                continue;
            }
            String text = read(skillMd);
            Map<String, String> fields = frontmatter(text);
            if (fields == null) {
                fail(rel + ": no YAML frontmatter");
                continue;
            }
            String dirName = skill.getFileName().toString();
            if (!dirName.equals(fields.get("name"))) {
                fail(rel + ": name '" + fields.get("name") + "' does not match folder '" + dirName + "'");
            }
            String description = fields.getOrDefault("description", "");
            if (description.isEmpty()) {
                fail(rel + ": empty description");
            }
            if (description.length() > MAX_DESCRIPTION) {
                fail(rel + ": description is " + description.length() + " chars (max " + MAX_DESCRIPTION + ")");
            }
            long lineCount = text.chars().filter(c -> c == '\n').count();
            if (lineCount > MAX_SKILL_LINES) {
                fail(rel + ": " + lineCount + " lines (max " + MAX_SKILL_LINES
                        + ") — move fixed content to assets/templates/");
            }
            Set<String> refs = new TreeSet<>();
            Matcher rm = refRe.matcher(text);
            while (rm.find()) {
                refs.add(rm.group());
            }
            for (String ref : refs) {
                if (!Files.exists(skill.resolve(ref))) {
                    fail(rel + ": references missing file " + ref);
                }
            }
            Set<String> siblings = new TreeSet<>();
            Matcher sm = siblingRe.matcher(text);
            while (sm.find()) {
                siblings.add(sm.group(1));
            }
            for (String sibling : siblings) {
                if (!Files.exists(SKILLS.resolve(sibling))) {
                    fail(rel + ": points at missing sibling file " + sibling);
                }
            }
        }
    }

    static void checkToolNeutral() throws IOException {
        Pattern kimi = Pattern.compile("kimi", Pattern.CASE_INSENSITIVE);
        try (Stream<Path> s = Files.walk(SKILLS)) {
            for (Path path : s.filter(Files::isRegularFile).sorted().toList()) {
                if (kimi.matcher(read(path)).find()) {
                    fail(ROOT.relativize(path) + ": mentions Kimi — skill bodies must stay tool-neutral");
                }
            }
        }
    }

    record Pins(Set<String> accepted, String value) {}

    static Map<String, Pins> readPins() {
        Map<String, Pins> pins = new LinkedHashMap<>();
        for (String line : read(ROOT.resolve("VERSIONS.md")).split("\n")) {
            String stripped = line.strip();
            while (stripped.startsWith("|")) {
                stripped = stripped.substring(1);
            }
            while (stripped.endsWith("|")) {
                stripped = stripped.substring(0, stripped.length() - 1);
            }
            String[] cells = stripped.split("\\|");
            List<String> trimmed = new ArrayList<>();
            for (String c : cells) {
                trimmed.add(c.strip());
            }
            if (trimmed.size() != 4 || trimmed.get(0).equals("Pin") || trimmed.get(0).isEmpty()
                    || trimmed.get(0).chars().allMatch(c -> c == '-')) {
                continue;
            }
            Set<String> accepted = new LinkedHashSet<>();
            accepted.add(trimmed.get(1));
            for (String a : trimmed.get(2).split(",")) {
                if (!a.strip().isEmpty()) {
                    accepted.add(a.strip());
                }
            }
            pins.put(trimmed.get(0), new Pins(accepted, trimmed.get(1)));
        }
        return pins;
    }

    static void checkPins() throws IOException {
        Map<String, Pins> pins = readPins();
        Set<String> diff = new TreeSet<>(pins.keySet());
        Set<String> patternNames = new TreeSet<>(PIN_PATTERNS.keySet());
        Set<String> onlyPins = new TreeSet<>(diff);
        onlyPins.removeAll(patternNames);
        Set<String> onlyPatterns = new TreeSet<>(patternNames);
        onlyPatterns.removeAll(diff);
        Set<String> symDiff = new TreeSet<>(onlyPins);
        symDiff.addAll(onlyPatterns);
        for (String name : symDiff) {
            String where = pins.containsKey(name) ? "scripts/LintSkills.java" : "VERSIONS.md";
            fail("pin '" + name + "' is missing from " + where);
        }
        List<Path> files;
        try (Stream<Path> s = Files.walk(SKILLS)) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        }
        for (Map.Entry<String, List<String>> e : PIN_PATTERNS.entrySet()) {
            String name = e.getKey();
            if (!pins.containsKey(name)) {
                continue;
            }
            Pins pin = pins.get(name);
            boolean seen = false;
            for (Path path : files) {
                String text = read(path);
                for (String pat : e.getValue()) {
                    Matcher m = Pattern.compile(pat, Pattern.MULTILINE).matcher(text);
                    while (m.find()) {
                        for (int g = 1; g <= m.groupCount(); g++) {
                            String found = m.group(g);
                            if (found == null || found.isEmpty()) {
                                continue;
                            }
                            seen = true;
                            if (!pin.accepted().contains(found)) {
                                long line = text.substring(0, m.start()).chars().filter(c -> c == '\n').count() + 1;
                                fail(ROOT.relativize(path) + ":" + line + ": " + name + " is '" + found
                                        + "', VERSIONS.md says '" + pin.value() + "'");
                            }
                        }
                    }
                }
            }
            if (!seen) {
                fail("pin '" + name + "' not found in any skill — stale row in VERSIONS.md or broken pattern");
            }
        }
    }

    static void checkEvals() throws IOException {
        Path evals = ROOT.resolve("evals");
        Set<String> positive = new LinkedHashSet<>();
        Pattern re = Pattern.compile("input_match: '.*\\)\\?([\\w-]+)\\\\?\"'");
        try (Stream<Path> s = Files.walk(evals)) {
            for (Path grader : s.filter(p -> p.toString().contains("graders")
                    && p.toString().endsWith(".md")).sorted().toList()) {
                String text = read(grader);
                Matcher m = re.matcher(text);
                if (m.find() && !text.contains("max: 0")) {
                    positive.add(m.group(1));
                }
            }
        }
        for (Path skill : skillDirs()) {
            if (!positive.contains(skill.getFileName().toString())) {
                fail(ROOT.relativize(skill) + ": no eval case under evals/ expects this skill to fire");
            }
        }
    }

    @SuppressWarnings("unchecked")
    static void checkManifests() {
        Map<String, Map<String, Object>> loaded = new LinkedHashMap<>();
        for (String manifest : MANIFESTS) {
            try {
                loaded.put(manifest, (Map<String, Object>) MiniJson.parse(read(ROOT.resolve(manifest))));
            } catch (Exception e) {
                fail(manifest + ": " + e.getMessage());
            }
        }
        Map<String, Object> plugin = loaded.get("plugin.json");
        if (plugin != null && plugin.get("$schema") == null) {
            fail("plugin.json: Agent Plugins manifest requires $schema");
        }
        for (String field : new String[]{"name", "version", "description"}) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> e : loaded.entrySet()) {
                values.put(e.getKey(), e.getValue().get(field));
            }
            if (new LinkedHashSet<>(values.values()).size() > 1) {
                fail("manifests disagree on '" + field + "': " + values);
            }
        }
    }

    public static void main(String[] args) throws IOException {
        checkSkills();
        checkToolNeutral();
        checkPins();
        checkEvals();
        checkManifests();
        for (String message : errors) {
            System.out.println("✗ " + message);
        }
        if (!errors.isEmpty()) {
            System.out.println("\n" + errors.size() + " problem(s)");
            System.exit(1);
        }
        System.out.printf("✔ %d skills, %d pins, %d manifests — all consistent%n",
                skillDirs().size(), PIN_PATTERNS.size(), MANIFESTS.size());
    }
}
