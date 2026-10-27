package deal.test;

import deal.module.CompilationOrchestrator;
import deal.project.CliOverrides;
import deal.project.ProjectLocator;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Real JVM compilation and reentrant execution, not an emitted-source size estimate. */
public final class JvmMethodSizeProductionTest {
    private static int passed;
    private static int failed;

    private static void check(boolean condition, String message) {
        if (condition) passed++;
        else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static String source(int width, int calls, boolean async) {
        StringBuilder source = new StringBuilder(async
            ? "async function wide(depth: number): Promise<number> {\n"
            : "function wide(depth: number): number {\n");
        for (int i = 0; i < width; i++) {
            source.append("  let a").append(i).append(": number = ").append(i + 1).append(" + depth;\n");
        }
        source.append("  function captured(): number { return a0; }\n")
            .append("  if (captured() !== 1 + depth) { throw {code: \"TEST_FAIL\", message: \"segmented capture\"}; }\n")
            .append("  try { if (depth === 0) { return ");
        for (int i = 0; i < width; i++) {
            if (i > 0) source.append(" + ");
            source.append("a").append(i);
        }
        source.append("; } throw {code: \"PROBE\", message: \"handled\"}; }\n")
            .append("  catch (e) { if ((e as Error).code !== \"PROBE\") { throw e as Error; } }\n")
            .append("  let seen: number = 0;\n")
            .append("  for (let i of [0, 1, 2, 3]) { if (i === 0) { continue; } if (i === 3) { break; } seen = seen + 1; }\n")
            .append("  if (seen !== 2) { throw {code: \"TEST_FAIL\", message: \"segmented loop\"}; }\n");
        source.append("  if (depth > 0) {\n    let nested: number = ")
            .append(async ? "await " : "").append("wide(depth - 1);\n")
            .append("    if (nested !== ").append(width * (width + 1) / 2)
            .append(" + ").append(width).append(" * (depth - 1)")
            .append(") { throw { code: \"TEST_FAIL\", message: \"recursive state\" }; }\n  }\n")
            .append("  return ");
        for (int i = 0; i < width; i++) {
            if (i > 0) source.append(" + ");
            source.append("a").append(i);
        }
        source.append(";\n}\n").append(async
            ? "export async function drive(): Promise<number> {\n"
            : "export function main(): null {\n")
            .append("  let total: number = 0;\n");
        for (int i = 0; i < calls; i++) {
            source.append("  total = total + ").append(async ? "await " : "").append("wide(2);\n");
        }
        source.append("  if (total !== ").append(calls * (width * (width + 1) / 2 + 2 * width))
            .append(") { throw { code: \"TEST_FAIL\", message: \"caller state\" }; }\n")
            .append(async ? "  return total;\n}\nexport function main(): null { return null; }\n"
                : "  return null;\n}\n");
        source.append("export function resultlessSmall(): void { let v: number = 1; }\n")
            .append("export function resultless(): void {\n");
        for (int i = 0; i < width; i++) {
            source.append("  let v").append(i).append(": number = ").append(i).append(";\n");
        }
        source.append("}\n");
        return source.toString();
    }

    private static String absoluteClasspath() {
        List<String> paths = new ArrayList<>();
        for (String item : System.getProperty("java.class.path").split(File.pathSeparator)) {
            paths.add(Path.of(item).toAbsolutePath().toString());
        }
        return String.join(File.pathSeparator, paths);
    }

    private static void run(int width, int calls, boolean async) throws Exception {
        Path root = Files.createTempDirectory("deal-jvm-method-size-");
        try {
            Files.writeString(root.resolve("deal.json"),
                "{\"languageVersion\":\"1.5\",\"backend\":\"jvm\"}");
            Path entry = root.resolve("main.deal");
            Files.writeString(entry, source(width, calls, async));
            Path output = root.resolve("out");
            var located = ProjectLocator.locate(entry.toString(), new CliOverrides("jvm", output.toString()));
            check(located.context() != null, "JVM size fixture is located");
            if (located.context() == null) return;
            var invocation = CompilerProfileProvider.resolve(ReleaseConfiguration.CURRENT_RELEASE_STATE,
                ReleaseConfiguration.releaseCapabilityRegistry());
            var compiler = new CompilationOrchestrator(located.context(), entry,
                false, false, false, false, null, invocation);
            boolean compiled = compiler.compile();
            check(compiled, "JVM size fixture lowers and emits: " + compiler.diagnostics());
            if (!compiled) return;
            Path artifact;
            try (var files = Files.walk(output)) {
                artifact = files.filter(p -> p.toString().endsWith(".java"))
                    .findFirst().orElseThrow();
            }
            String className = artifact.getFileName().toString().replace(".java", "");
            check(Files.isRegularFile(artifact), "JVM size fixture publishes " + artifact);
            if (!Files.isRegularFile(artifact)) return;
            Path classes = root.resolve("classes");
            Files.createDirectories(classes);
            var javac = HostModuleLoadEmissionTest.runProcess(List.of("javac", "--release", "25",
                "-proc:none", "-cp", absoluteClasspath(), "-d", classes.toString(), artifact.toString()), root, false);
            check(javac.exitCode() == 0, "real javac admits width=" + width + ", calls=" + calls + ": " + javac.output());
            if (javac.exitCode() != 0) return;
            var java = HostModuleLoadEmissionTest.runProcess(List.of("java", "-cp",
                classes + File.pathSeparator + absoluteClasspath(), className), root, false);
            check(java.exitCode() == 0, "real JVM preserves caller and recursive state: " + java.output());
            check(java.stdout().isEmpty() && java.stderr().isEmpty(), "size fixture completes silently");
            // Invoke the published void body directly: the outer invocation
            // boundary must not normalize a wrong internal null into undefined.
            Path probe = root.resolve("VoidProbe.java");
            Files.writeString(probe, """
                import deal.codegen.jvm.JvmRuntime;
                public final class VoidProbe {
                  public static void main(String[] args) throws Exception {
                    Class.forName(args[0]).getMethod("main", String[].class)
                        .invoke(null, (Object) new String[0]);
                    for (String name : new String[]{"resultlessSmall", "resultless"}) {
                      Object callable = null;
                      for (var surface : JvmRuntime.EXPORT_SURFACES.values()) {
                        if (surface.keys.contains(name)) callable = surface.read(name);
                      }
                      if (!(callable instanceof JvmRuntime.FunctionValue fn))
                        throw new AssertionError("published void body missing: " + name);
                      if (fn.fn.invoke(new Object[0]) != JvmRuntime.UNDEFINED)
                        throw new AssertionError("void body did not publish original undefined: " + name);
                    }
                  }
                }
                """);
            var probeCompile = HostModuleLoadEmissionTest.runProcess(List.of("javac", "--release", "25",
                "-proc:none", "-cp", classes + File.pathSeparator + absoluteClasspath(),
                "-d", classes.toString(), probe.toString()), root, false);
            check(probeCompile.exitCode() == 0, "void direct-body probe compiles: " + probeCompile.output());
            if (probeCompile.exitCode() == 0) {
                var probeRun = HostModuleLoadEmissionTest.runProcess(List.of("java", "-cp",
                    classes + File.pathSeparator + absoluteClasspath(), "VoidProbe", className), root, false);
                check(probeRun.exitCode() == 0, "unsegmented/segmented void body publishes undefined: " + probeRun.output());
            }
            if (async) {
                var completion = new deal.codegen.jvm.JvmAsyncExportInvoker().invoke(
                    new deal.codegen.lua.AsyncExportInvocationRequest(
                        classes.resolve(className + ".class"), "drive", "number"));
                check(completion instanceof deal.codegen.jvm.JvmAsyncExportInvoker.Result.Value value
                        && Double.parseDouble(value.valueJson())
                            == calls * (width * (width + 1) / 2 + 2 * width),
                    "wide recursive async completions preserve activation state: " + completion);
            }
        } finally {
            HostModuleLoadEmissionTest.deleteRecursively(root);
        }
    }

    public static void main(String[] args) throws Exception {
        run(32, 8, false);
        run(128, 60, false);
        run(128, 60, true);
        System.out.println("JVM method size: " + passed + " passed, " + failed + " failed");
        if (failed != 0) System.exit(1);
    }
}
