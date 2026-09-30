import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Golden-path check: run a skill end-to-end on a real model, then build what it produced.
 * Routing evals prove the right skill fires; this proves the deliverable compiles.
 *
 * Currently drives kimi headless (the CLI we can run fully non-interactively here).
 *
 * Usage: java scripts/GoldenPath.java [--name eval-service] [--timeout-minutes 20]
 */
public class GoldenPath {

    static final Path ROOT = Path.of("").toAbsolutePath();

    record Check(String name, boolean passed, String detail) {}

    public static void main(String[] args) throws Exception {
        String projectName = "eval-service";
        int timeoutMinutes = 20;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--name" -> projectName = args[++i];
                case "--timeout-minutes" -> timeoutMinutes = Integer.parseInt(args[++i]);
                default -> {
                    System.err.println("unknown arg: " + args[i]);
                    System.exit(2);
                }
            }
        }

        Path workDir = Files.createTempDirectory("golden-path-");
        System.out.println("workspace: " + workDir);

        String prompt = "Scaffold a new Spring Boot project called " + projectName
                + " that manages Widgets.";
        List<String> agentCmd = List.of("kimi", "-p", prompt,
                "--skills-dir", ROOT.resolve("skills").toString());
        System.out.println("running agent (timeout " + timeoutMinutes + "m)...");
        int agentRc = run(agentCmd, workDir, timeoutMinutes);
        if (agentRc != 0) {
            System.out.println("✗ agent exited " + agentRc);
            System.exit(1);
        }

        List<Check> checks = new ArrayList<>();
        Path project = workDir.resolve(projectName);
        checks.add(new Check("project created", Files.isDirectory(project),
                Files.isDirectory(project) ? "" : "no " + projectName + "/ directory"));
        Path pom = project.resolve("pom.xml");
        checks.add(new Check("pom.xml exists", Files.exists(pom), ""));

        boolean compiled = false;
        String compileDetail = "";
        if (Files.exists(pom)) {
            List<String> buildCmd = Files.exists(project.resolve("mvnw"))
                    ? List.of("./mvnw", "-q", "-DskipTests", "compile")
                    : List.of("mvn", "-q", "-DskipTests", "compile");
            System.out.println("compiling: " + String.join(" ", buildCmd));
            compiled = run(buildCmd, project, 10) == 0;
            compileDetail = compiled ? "" : "mvn compile failed — see output above";
        }
        checks.add(new Check("project compiles", compiled, compileDetail));

        boolean allPassed = true;
        for (Check c : checks) {
            allPassed &= c.passed();
            System.out.printf("%s %s%s%n", c.passed() ? "✓" : "✗", c.name(),
                    c.detail().isEmpty() ? "" : " — " + c.detail());
        }
        System.out.println(allPassed ? "\nGOLDEN PATH PASS" : "\nGOLDEN PATH FAIL");
        System.exit(allPassed ? 0 : 1);
    }

    static int run(List<String> cmd, Path cwd, int timeoutMinutes) throws Exception {
        Process proc = new ProcessBuilder(cmd).directory(cwd.toFile())
                .redirectErrorStream(true).inheritIO().start();
        if (!proc.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            proc.destroyForcibly();
            return -1;
        }
        return proc.exitValue();
    }
}
