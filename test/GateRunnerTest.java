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
                trap 'rm -f "running/$$"' EXIT
                count=$(find running -type f | wc -l)
                if [ "$count" -gt 2 ]; then echo budget-exceeded; exit 9; fi
                echo "executed:$name active=$count"
                if [ "$name" = fail ]; then sleep .05; exit 7; fi
                sleep .1
                """);
            Files.writeString(tools.resolve("gate-manifest.sh"), """
                TEST_MAINS=(
                  'bg|long|java -ea -cp build first'
                  'fg|short|java -ea -cp build second'
                  'fg|short|java -ea -cp build third'
                  'fg|short|java -ea -cp build fourth'
                )
                """);
            Map<String, String> env = Map.of("PATH", bin + ":" + System.getenv("PATH"));
            var passed = BoundedProcess.runCaptured(
                List.of("/bin/bash", "run_tests.sh", "--jobs", "2"), scratch, env);
            check(passed.exitCode() == 0, "successful selection passes: " + passed);
            for (String name : List.of("first", "second", "third", "fourth")) {
                check(passed.stdout().contains("executed:" + name), "every suite executes: " + name);
            }
            check(passed.stdout().contains("All Tests Passed"), "success is explicit");
            var serial = BoundedProcess.runCaptured(
                List.of("/bin/bash", "run_tests.sh", "--jobs", "1"), scratch, env);
            check(serial.exitCode() == 0 && !serial.stdout().contains("active=2"),
                "one job is strictly sequential: " + serial);
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
            check(!failed.stdout().contains("All Tests Passed"), "failure never prints success");
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
