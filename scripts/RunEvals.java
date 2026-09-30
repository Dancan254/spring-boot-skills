import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Run the routing eval suite on any supported agent CLI.
 *
 * Claude runs delegate to `claude plugin eval` (the official harness). Kimi and
 * Codex runs drive each CLI's headless mode and grade the event stream here:
 * `tool_used` graders count Skill invocations matching input_match, `regex`
 * graders match the final answer text. No LLM judges, no ablation arms.
 *
 * Usage: java scripts/RunEvals.java --tool kimi [--case glob]... [--runs N] [--model M]
 */
public class RunEvals {

    static final Path ROOT = Path.of("").toAbsolutePath();
    static final Path EVALS = ROOT.resolve("evals");
    static final Path RESULTS = EVALS.resolve("results");

    static final Map<String, Integer> FLAG_MAP = Map.of(
            "i", Pattern.CASE_INSENSITIVE, "m", Pattern.MULTILINE,
            "s", Pattern.DOTALL, "x", Pattern.COMMENTS);

    static final String MINIMAL_POM = "<project><modelVersion>4.0.0</modelVersion>"
            + "<groupId>com.example</groupId><artifactId>eval-fixture</artifactId>"
            + "<version>0.0.1</version></project>";

    // Milliseconds to keep reading after the Skill call so its tool result lands.
    static final long SKILL_GRACE_MS = 8_000;

    record Case(String name, String prompt, Map<String, Object> meta, List<Map<String, Object>> graders) {}

    static Object parseValue(String raw) {
        raw = raw.trim();
        if (raw.startsWith("[") && raw.endsWith("]")) {
            List<String> items = new ArrayList<>();
            for (String v : raw.substring(1, raw.length() - 1).split(",")) {
                if (!v.trim().isEmpty()) {
                    items.add(stripQuotes(v.trim()));
                }
            }
            return items;
        }
        if (raw.length() >= 2 && raw.charAt(0) == raw.charAt(raw.length() - 1)
                && (raw.charAt(0) == '\'' || raw.charAt(0) == '"')) {
            return raw.substring(1, raw.length() - 1);
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            try {
                return Double.parseDouble(raw);
            } catch (NumberFormatException e2) {
                return raw;
            }
        }
    }

    static String stripQuotes(String v) {
        if (v.length() >= 2 && v.charAt(0) == v.charAt(v.length() - 1)
                && (v.charAt(0) == '\'' || v.charAt(0) == '"')) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    /** Tiny YAML-subset frontmatter parser: scalars, inline lists, quoted strings. */
    static Map<String, Object> frontmatter(String text) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (!text.startsWith("---")) {
            return data;
        }
        String[] parts = text.split("---", 3);
        if (parts.length < 3) {
            return data;
        }
        for (String line : parts[1].trim().split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                data.put(line.substring(0, colon).trim(), parseValue(line.substring(colon + 1)));
            }
        }
        return data;
    }

    static String bodyAfterFrontmatter(String text) {
        String[] parts = text.split("---", 3);
        return parts.length >= 3 ? parts[2].trim() : text;
    }

    static boolean globMatch(String name, String glob) {
        String regex = glob.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        return name.matches(regex);
    }

    static List<Case> loadCases(List<String> globs) throws IOException {
        List<Case> cases = new ArrayList<>();
        try (Stream<Path> s = Files.list(EVALS)) {
            for (Path dir : s.filter(Files::isDirectory).sorted().toList()) {
                Path promptFile = dir.resolve("prompt.md");
                if (!Files.exists(promptFile) || dir.getFileName().toString().equals("results")) {
                    continue;
                }
                String name = dir.getFileName().toString();
                if (!globs.isEmpty() && globs.stream().noneMatch(g -> globMatch(name, g))) {
                    continue;
                }
                String text = Files.readString(promptFile);
                List<Map<String, Object>> graders = new ArrayList<>();
                Path gradersDir = dir.resolve("graders");
                if (Files.isDirectory(gradersDir)) {
                    try (Stream<Path> gs = Files.list(gradersDir)) {
                        for (Path g : gs.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                            Map<String, Object> gmeta = new LinkedHashMap<>(frontmatter(Files.readString(g)));
                            String file = g.getFileName().toString();
                            gmeta.put("name", file.substring(0, file.length() - 3));
                            graders.add(gmeta);
                        }
                    }
                }
                cases.add(new Case(name, bodyAfterFrontmatter(text), frontmatter(text), graders));
            }
        }
        return cases;
    }

    @SuppressWarnings("unchecked")
    static void walk(Object node, List<Map<String, Object>> out) {
        if (node instanceof Map<?, ?> m) {
            out.add((Map<String, Object>) m);
            for (Object v : m.values()) {
                walk(v, out);
            }
        } else if (node instanceof List<?> l) {
            for (Object v : l) {
                walk(v, out);
            }
        }
    }

    record StreamScan(List<Map<String, Object>> skillInputs, String finalText) {}

    /** Extract Skill invocations and assistant text from stream-json output. */
    static StreamScan parseEventStream(String stdout) {
        List<Map<String, Object>> skillInputs = new ArrayList<>();
        StringBuilder texts = new StringBuilder();
        for (String line : stdout.split("\n")) {
            line = line.trim();
            if (!line.startsWith("{")) {
                continue;
            }
            Object event;
            try {
                event = MiniJson.parse(line);
            } catch (RuntimeException e) {
                continue;
            }
            List<Map<String, Object>> nodes = new ArrayList<>();
            walk(event, nodes);
            for (Map<String, Object> node : nodes) {
                Object name = node.getOrDefault("name", node.get("tool_name"));
                boolean isTool = node.containsKey("arguments") || node.containsKey("input")
                        || "tool_use".equals(node.get("type")) || "tool_call".equals(node.get("type"));
                if ("Skill".equals(name) && isTool) {
                    Object payload = node.getOrDefault("input", node.get("arguments"));
                    if (payload instanceof String ps) {
                        try {
                            payload = MiniJson.parse(ps);
                        } catch (RuntimeException e) {
                            payload = Map.of("raw", ps);
                        }
                    }
                    skillInputs.add(payload instanceof Map<?, ?> pm ? castMap(pm) : Map.of());
                }
                if ("text".equals(node.get("type")) && node.get("text") instanceof String t) {
                    texts.append(t).append('\n');
                }
                if ("assistant".equals(node.get("role")) && node.get("content") instanceof String c) {
                    texts.append(c).append('\n');
                }
            }
            if (event instanceof Map<?, ?> em
                    && "result".equals(em.get("type")) && em.get("result") instanceof String r) {
                texts.append(r).append('\n');
            }
        }
        return new StreamScan(skillInputs, texts.toString());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    record GraderResult(String name, String type, boolean passed, String detail) {
        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("type", type);
            m.put("passed", passed);
            m.put("detail", detail);
            return m;
        }
    }

    static Map<String, Object> grade(List<Map<String, Object>> graders,
                                     List<Map<String, Object>> skillInputs, String finalText) {
        List<Object> results = new ArrayList<>();
        List<String> serialized = skillInputs.stream().map(MiniJson::write).toList();
        for (Map<String, Object> g : graders) {
            String type = String.valueOf(g.get("type"));
            String gname = String.valueOf(g.get("name"));
            boolean passed;
            String detail;
            if ("tool_used".equals(type)) {
                Object match = g.get("input_match");
                Pattern pattern = match != null ? Pattern.compile(String.valueOf(match)) : null;
                long count = serialized.stream()
                        .filter(s -> pattern == null || pattern.matcher(s).find()).count();
                int lo = g.get("min") instanceof Number n ? n.intValue() : 1;
                Integer hi = g.get("max") instanceof Number n ? n.intValue() : null;
                passed = count >= lo && (hi == null || count <= hi);
                detail = "Skill matched " + count + "x (expected " + lo + ".."
                        + (hi == null ? "inf" : hi) + ")";
            } else if ("regex".equals(type)) {
                int flags = 0;
                for (String ch : String.valueOf(g.getOrDefault("flags", "")).split("")) {
                    flags |= FLAG_MAP.getOrDefault(ch, 0);
                }
                passed = Pattern.compile(String.valueOf(g.get("pattern")), flags)
                        .matcher(finalText).find();
                detail = passed ? "pattern matched" : "pattern not found in final answer";
            } else {
                passed = false;
                detail = "unsupported grader type: " + type;
            }
            results.add(new GraderResult(gname, type, passed, detail).toJson());
        }
        double score = results.isEmpty() ? 1.0
                : results.stream().mapToDouble(r -> (Boolean) castMap((Map<?, ?>) r).get("passed") ? 1 : 0)
                        .average().orElse(1.0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", score);
        out.put("graders", results);
        return out;
    }

    static boolean lineHasSkillCall(String line) {
        Object event;
        try {
            event = MiniJson.parse(line);
        } catch (RuntimeException e) {
            return line.replace(" ", "").contains("\"name\":\"Skill\"");
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        walk(event, nodes);
        return nodes.stream().anyMatch(n -> "Skill".equals(n.getOrDefault("name", n.get("tool_name")))
                && (n.containsKey("arguments") || n.containsKey("input")
                || "tool_use".equals(n.get("type")) || "tool_call".equals(n.get("type"))));
    }

    static final class LineReader implements Runnable {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        final StringBuilder stderr = new StringBuilder();
        final Process proc;

        LineReader(Process proc) {
            this.proc = proc;
        }

        @Override
        public void run() {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    queue.add(line);
                }
            } catch (IOException ignored) {
                // process killed mid-read
            } finally {
                queue.add("");
            }
        }

        Thread drainStderr() {
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        stderr.append(line).append('\n');
                    }
                } catch (IOException ignored) {
                    // process killed mid-read
                }
            });
            t.setDaemon(true);
            t.start();
            return t;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> runHeadless(String tool, Case evalCase, String model) throws Exception {
        Object t = evalCase.meta().get("timeout_seconds");
        long timeoutMs = (t instanceof Number n ? n.longValue() : 180) * 1000;
        List<String> cmd = new ArrayList<>();
        if (tool.equals("kimi")) {
            cmd.addAll(List.of("kimi", "-p", evalCase.prompt(), "--output-format", "stream-json",
                    "--skills-dir", ROOT.resolve("skills").toString()));
            if (model != null) {
                cmd.addAll(List.of("-m", model));
            }
        } else {
            cmd.addAll(List.of("codex", "exec", evalCase.prompt(), "--json"));
            if (model != null) {
                cmd.addAll(List.of("--model", model));
            }
        }

        long started = System.nanoTime();
        Path cwd = Files.createTempDirectory("eval-" + tool + "-");
        // Positive prompts say "this Spring Boot service"; an empty dir makes
        // non-Claude models refuse instead of routing, so seed a marker pom.
        Files.writeString(cwd.resolve("pom.xml"), MINIMAL_POM);
        Process proc = new ProcessBuilder(cmd).directory(cwd.toFile()).start();
        LineReader reader = new LineReader(proc);
        Thread outThread = new Thread(reader);
        outThread.setDaemon(true);
        outThread.start();
        reader.drainStderr();

        // Cut the run once routing is decided (a Skill call is observed); the
        // skills instruct full implementations that would blow the timeout.
        // kimi/codex have no max-turns flag, so the harness enforces it here.
        StringBuilder stdout = new StringBuilder();
        Long skillSeenAt = null;
        boolean killed = false;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long wait = deadline - System.currentTimeMillis();
            if (skillSeenAt != null) {
                wait = Math.min(wait, skillSeenAt + SKILL_GRACE_MS - System.currentTimeMillis());
            }
            if (wait <= 0) {
                killed = true;
                proc.destroyForcibly();
                break;
            }
            String line = reader.queue.poll(wait, TimeUnit.MILLISECONDS);
            if (line == null) {
                killed = true;
                proc.destroyForcibly();
                break;
            }
            if (line.isEmpty()) {
                break; // EOF marker
            }
            stdout.append(line).append('\n');
            if (skillSeenAt == null && lineHasSkillCall(line)) {
                skillSeenAt = System.currentTimeMillis();
            }
        }
        proc.waitFor(5, TimeUnit.SECONDS);

        long duration = (System.nanoTime() - started) / 1_000_000_000L;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("durationSeconds", duration);
        String outText = stdout.toString();
        if (killed && skillSeenAt == null) {
            out.put("error", "timeout after " + timeoutMs / 1000 + "s");
            out.put("trace", tail(outText));
            return out;
        }
        if (outText.isBlank()) {
            out.put("error", "exit " + proc.exitValue() + ": " + reader.stderr.toString().strip()
                    .substring(0, Math.min(300, reader.stderr.toString().strip().length())));
            out.put("trace", tail(reader.stderr.toString()));
            return out;
        }
        StreamScan scan = parseEventStream(outText);
        Map<String, Object> graded = grade(evalCase.graders(), scan.skillInputs(), scan.finalText());
        out.putAll(graded);
        out.put("passed", (Double) graded.get("score") >= 1.0);
        if (!(Boolean) out.get("passed")) {
            out.put("trace", tail(outText));
        }
        return out;
    }

    static String tail(String s) {
        return s.substring(Math.max(0, s.length() - 20_000));
    }

    static int runWithClaude(List<Case> cases, int runs, String model) throws Exception {
        for (Case c : cases) {
            List<String> cmd = new ArrayList<>(List.of("claude", "plugin", "eval", ".",
                    "--case", c.name(), "--runs", String.valueOf(runs),
                    "--ablation", "none", "--no-publish"));
            if (model != null) {
                cmd.addAll(List.of("--model", model));
            }
            int rc = new ProcessBuilder(cmd).directory(ROOT.toFile()).inheritIO().start().waitFor();
            if (rc != 0) {
                return rc;
            }
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        String tool = null;
        List<String> caseGlobs = new ArrayList<>();
        int runs = 1;
        String model = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--tool" -> tool = args[++i];
                case "--case" -> caseGlobs.add(args[++i]);
                case "--runs" -> runs = Integer.parseInt(args[++i]);
                case "--model" -> model = args[++i];
                default -> {
                    System.err.println("unknown arg: " + args[i]);
                    System.exit(2);
                }
            }
        }
        if (tool == null || !List.of("claude", "kimi", "codex").contains(tool)) {
            System.err.println("usage: java scripts/RunEvals.java --tool claude|kimi|codex "
                    + "[--case glob]... [--runs N] [--model M]");
            System.exit(2);
        }
        if (Runtime.getRuntime().exec(new String[]{"which", tool}).waitFor() != 0) {
            System.err.println("`" + tool + "` is not on PATH. Install it first, then retry.");
            System.exit(1);
        }

        List<Case> cases = loadCases(caseGlobs);
        if (cases.isEmpty()) {
            System.err.println("no eval cases matched: " + caseGlobs);
            System.exit(1);
        }
        if (tool.equals("claude")) {
            System.exit(runWithClaude(cases, runs, model));
        }

        Instant startedAt = Instant.now();
        Path outDir = RESULTS.resolve(startedAt.toString().replaceAll("[:.]", "-")
                .replace("T", "T").substring(0, 19) + "-" + tool);
        Files.createDirectories(outDir);
        List<Object> allCases = new ArrayList<>();
        for (Case evalCase : cases) {
            List<Object> caseRuns = new ArrayList<>();
            for (int i = 0; i < runs; i++) {
                Map<String, Object> r = runHeadless(tool, evalCase, model);
                r.put("run", i + 1);
                if (r.containsKey("trace")) {
                    Path tracePath = outDir.resolve(evalCase.name() + "-run" + (i + 1) + ".jsonl");
                    Files.writeString(tracePath, String.valueOf(r.remove("trace")));
                    r.put("tracePath", tracePath.toString());
                }
                caseRuns.add(r);
                boolean passed = Boolean.TRUE.equals(r.get("passed"));
                String note = r.containsKey("error")
                        ? String.valueOf(r.get("error"))
                        : "score %.2f".formatted((Double) r.getOrDefault("score", 0.0));
                System.out.printf("  %s %s run %d/%d: %s%n", passed ? "✓" : "✗",
                        evalCase.name(), i + 1, runs, note);
            }
            long ok = caseRuns.stream().filter(r -> Boolean.TRUE.equals(castMap((Map<?, ?>) r)
                    .get("passed"))).count();
            double score = caseRuns.stream().mapToDouble(r ->
                    (Double) castMap((Map<?, ?>) r).getOrDefault("score", 0.0)).average().orElse(0);
            Map<String, Object> caseResult = new LinkedHashMap<>();
            caseResult.put("name", evalCase.name());
            caseResult.put("score", score);
            caseResult.put("passed", ok == caseRuns.size());
            caseResult.put("runs", caseRuns);
            allCases.add(caseResult);
        }

        Map<String, Object> aggregate = new LinkedHashMap<>();
        aggregate.put("tool", tool);
        aggregate.put("model", model);
        aggregate.put("startedAt", startedAt.toString());
        aggregate.put("cases", allCases);
        Files.writeString(outDir.resolve("aggregate-result.json"), MiniJson.write(aggregate));

        int width = allCases.stream().mapToInt(c -> String.valueOf(castMap((Map<?, ?>) c)
                .get("name")).length()).max().orElse(4);
        System.out.printf("%n%-" + width + "s  SCORE  RUNS  STATUS%n", "CASE");
        boolean allPassed = true;
        for (Object c : allCases) {
            Map<String, Object> cm = castMap((Map<?, ?>) c);
            boolean passed = Boolean.TRUE.equals(cm.get("passed"));
            allPassed &= passed;
            System.out.printf("%-" + width + "s  %.2f   %d     %s%n", cm.get("name"),
                    (Double) cm.get("score"), ((List<?>) cm.get("runs")).size(),
                    passed ? "pass" : "FAIL");
        }
        System.out.println("\nreport: " + outDir.resolve("aggregate-result.json"));
        System.exit(allPassed ? 0 : 1);
    }
}
