package deal.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Exercises the actual runner with controlled suite processes, not source assertions. */
public final class GateRunnerTest {
    public static void main(String[] args) throws Exception {
        Path scratch = Files.createTempDirectory("gate-runner-controls-");
        try {
            Path tools = Files.createDirectories(scratch.resolve("tools"));
            Path bin = Files.createDirectories(scratch.resolve("bin"));
            Files.createDirectories(scratch.resolve("deal"));
            Files.createDirectories(scratch.resolve("test"));
            Files.copy(Path.of("run_tests.sh"), scratch.resolve("run_tests.sh"));
            Files.copy(Path.of("tools/tool-preflight.sh"), tools.resolve("tool-preflight.sh"));
            tools.resolve("tool-preflight.sh").toFile().setExecutable(true);
            for (String name : List.of("javac", "luajit", "node", "gcc", "clang")) {
                executable(bin.resolve(name), "#!/bin/bash\nexit 0\n");
            }
            executable(bin.resolve("java"), """
                #!/bin/bash
                set -e
                name="${!#}"
                mkdir -p markers running
                touch "markers/$name" "running/$$"
                echo "$name" >> starts
                trap 'rm -f "running/$$"' EXIT
                count=$(find running -type f | wc -l)
                if [ "$count" -gt 2 ]; then echo budget-exceeded; exit 9; fi
                echo "executed:$name active=$count"
                if [ "$name" = fail ]; then sleep .05; exit 7; fi
                sleep .1
                """);
            Files.writeString(tools.resolve("gate-manifest.sh"), """
                TEST_MAINS=(
                  'fg|short|java -ea -cp build second'
                  'bg|long|java -ea -cp build first'
                  'fg|short|java -ea -cp build fourth'
                  'bg|long|java -ea -cp build third'
                )
                """);
            // The coverage marker must no longer silently override the suite budget.
            Map<String, String> env = Map.of("PATH", bin + ":" + System.getenv("PATH"),
                "DEAL_COVERAGE_AGENT", "isolated-by-launcher");
            var passed = BoundedProcess.runCaptured(
                List.of("/bin/bash", "run_tests.sh", "--jobs", "2"), scratch, env);
            check(passed.exitCode() == 0, "successful selection passes: " + passed);
            check(passed.stdout().contains("active=2"),
                "coverage marker preserves requested parallel suite budget: " + passed);
            for (String name : List.of("first", "second", "third", "fourth")) {
                check(passed.stdout().contains("executed:" + name), "every suite executes: " + name);
            }
            check(passed.stdout().contains("Selected Tests Passed"), "selected success is explicit");
            check(passed.stdout().lines().anyMatch(line -> line.equals("=== Selected Tests Passed ===")),
                "success reports the executed selection without obsolete omission warnings");
            check(new java.util.HashSet<>(Files.readAllLines(scratch.resolve("starts")).subList(0, 2))
                .equals(java.util.Set.of("first", "third")), "long lanes get the first bounded slots");
            Files.delete(scratch.resolve("starts"));
            var serial = BoundedProcess.runCaptured(
                List.of("/bin/bash", "run_tests.sh", "--jobs", "1"), scratch, env);
            check(serial.exitCode() == 0 && !serial.stdout().contains("active=2"),
                "one job is strictly sequential: " + serial);
            check(Files.readAllLines(scratch.resolve("starts")).equals(
                List.of("first", "third", "second", "fourth")),
                "priority is stable and every remaining suite executes exactly once");
            Files.writeString(tools.resolve("gate-manifest.sh"), """
                TEST_MAINS=(
                  'bg|negative|java -ea -cp build fail'
                  'fg|peer|java -ea -cp build peer'
                  'fg|not admitted|java -ea -cp build forbidden'
                )
                """);
            var failed = BoundedProcess.runCaptured(
                List.of("/bin/bash", "run_tests.sh", "--jobs", "2"), scratch, env);
            check(failed.exitCode() == 7, "suite failure is the gate's status: " + failed);
            check(failed.stdout().contains("executed:fail"), "failure output is retained");
            check(!failed.stdout().contains("Selected Tests Passed"), "failure never prints success");
            check(!Files.exists(scratch.resolve("markers/forbidden")), "failure stops admission");
            var invalid = BoundedProcess.runCaptured(
                List.of("/bin/bash", "run_tests.sh", "--jobs", "0"), scratch, env);
            check(invalid.exitCode() == 2, "invalid budget fails before building");
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        var coverage = BoundedProcess.runCaptured(
            List.of("python3", "test/coverage_controls.py"), Path.of("."), Map.of());
        check(coverage.exitCode() == 0, "coverage workflow controls: " + coverage);
        System.out.println("Gate runner controls: passed");
    }

    private static void executable(Path file, String text) throws Exception {
        Files.writeString(file, text);
        if (!file.toFile().setExecutable(true)) throw new AssertionError("cannot make tool executable");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
