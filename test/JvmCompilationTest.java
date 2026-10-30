package deal.test;

import deal.test.conformance.JvmCompilation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Reused compiler infrastructure must not retain source, output or classpath facts. */
public final class JvmCompilationTest {
    public static void main(String[] args) throws Exception {
        Path scratch = Files.createTempDirectory("jvm-compile-controls-");
        try {
            Path source = scratch.resolve("Probe.java");
            Path output = scratch.resolve("classes");
            Files.createDirectories(output);
            Files.writeString(source, "public class Probe { public static void main(String[] a) { System.out.print(3); } }");
            check(compile(scratch, source, output, List.of()).exitCode() == 0, "first compile");
            execute(scratch, output, "3");
            Files.writeString(source, "public class Probe { public static void main(String[] a) { System.out.print(7); } }");
            check(compile(scratch, source, output, List.of()).exitCode() == 0, "changed source compiles");
            execute(scratch, output, "7");
            Files.writeString(source, "not Java source");
            check(compile(scratch, source, output, List.of()).exitCode() != 0, "invalid source fails anew");
            Files.writeString(source, "public class Probe { int n = Dependency.n; }");
            Path dependency = scratch.resolve("Dependency.java");
            Files.writeString(dependency, "public class Dependency { public static int n = 9; }");
            Path dependencies = Files.createDirectories(scratch.resolve("dependencies"));
            check(compile(scratch, dependency, dependencies, List.of()).exitCode() == 0,
                "dependency compiles");
            check(compile(scratch, source, output, List.of("-cp", dependencies.toString())).exitCode() == 0,
                "classpath admits dependency");
            check(compile(scratch, source, output, List.of()).exitCode() != 0,
                "omitted classpath does not inherit the previous request");
            Path empty = Files.createDirectories(scratch.resolve("empty"));
            check(compile(scratch, source, output, List.of("-cp", empty.toString())).exitCode() != 0,
                "changed classpath does not inherit dependency");
            Path jar = scratch.resolve("dependency.jar");
            try (var archive = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
                archive.putNextEntry(new java.util.jar.JarEntry("Dependency.class"));
                archive.write(Files.readAllBytes(dependencies.resolve("Dependency.class")));
                archive.closeEntry();
            }
            check(compile(scratch, source, output, List.of("-cp", jar.toString())).exitCode() == 0,
                "archive classpath admits dependency");
            Files.delete(jar);
            try (var archive = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
                archive.putNextEntry(new java.util.jar.JarEntry("unrelated"));
                archive.closeEntry();
            }
            check(compile(scratch, source, output, List.of("-cp", jar.toString())).exitCode() != 0,
                "replaced archive cannot retain an obsolete dependency");
            Files.delete(dependencies.resolve("Dependency.class"));
            check(compile(scratch, source, output, List.of("-cp", dependencies.toString())).exitCode() != 0,
                "deleted dependency is not retained");
            Files.writeString(source, "public class Probe { int n = ; }");
            for (int index = 0; index < 3; index++) {
                check(compile(scratch, source, output, List.of()).exitCode() != 0,
                    "repeated invalid source is never cached as success");
            }
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        System.out.println("JVM compilation controls: passed");
    }

    private static JvmCompilation.Result compile(Path scratch, Path source, Path output,
                                                 List<String> flags) throws Exception {
        var command = new java.util.ArrayList<>(List.of("javac", "--release", "25", "-proc:none",
            "-d", output.toString()));
        command.addAll(flags);
        command.add(source.toString());
        return JvmCompilation.compile(command, scratch);
    }

    private static void execute(Path scratch, Path output, String expected) throws Exception {
        var run = BoundedProcess.runCaptured(List.of("java", "-cp", output.toString(), "Probe"),
            scratch, Map.of());
        check(run.exitCode() == 0 && run.stdout().equals(expected), "current output executes: " + run);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
