package deal.test;

import deal.codegen.Backend;
import deal.codegen.jvm.JvmNames;
import deal.module.CompilationOrchestrator;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.test.conformance.JvmCompilation;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Executes independently specified generated compositions on deployed artifacts. */
public final class CompositionProductionTest {
    private static final List<Backend> BACKENDS = List.of(Backend.JS, Backend.LUAJIT, Backend.JVM);

    static void execute(String source, String expected, Path root, Path host) throws Exception {
        execute(source, expected, root, host, BACKENDS);
    }

    static void execute(String source, String expected, Path root, Path host,
                        List<Backend> backends) throws Exception {
        execute(source, expected, "", root, host, backends);
    }

    static void execute(String source, String expected, String expectedStderr,
                        Path root, Path host, List<Backend> backends) throws Exception {
        for (Backend backend : backends) {
            Path project = root.resolve(backend.name());
            Path src = project.resolve("src");
            Path out = project.resolve("out");
            Files.createDirectories(src);
            Path entry = src.resolve("main.deal");
            Files.writeString(entry, source);
            if (host != null) {
                Files.createDirectories(src.resolve("host"));
                Files.copy(host.resolve("probe.d.deal"), src.resolve("host/probe.d.deal"));
            }
            var invocation = CompilerProfileProvider.resolve(ReleaseConfiguration.CURRENT_RELEASE_STATE,
                ReleaseConfiguration.releaseCapabilityRegistry());
            var compiler = new CompilationOrchestrator(entry.toAbsolutePath(), out, false, false,
                false, false, backend, host == null ? null
                    : Map.of("host/probe", src.resolve("host/probe.d.deal").toAbsolutePath().toString()),
                List.of(src.toAbsolutePath()),
                Path.of("").toAbsolutePath(), null, invocation);
            compiler.compile();
            if (!compiler.diagnostics().isEmpty() || compiler.semanticEmissionCount() != 1)
                throw new AssertionError("SOURCE_REJECTION " + backend + ": " + compiler.diagnostics());
            if (backend == Backend.LLVM) {
                List<Path> hostUnits = new ArrayList<>();
                if (host != null) {
                    Path nativeHost = project.resolve("hosts");
                    Files.createDirectories(nativeHost);
                    Path unit = nativeHost.resolve("probe.c");
                    Files.copy(host.resolve("probe.c"), unit);
                    for (String helper : List.of("native_helpers.h", "native_hostile.h"))
                        Files.copy(Path.of("test/conformance/host-fixtures/" + helper), nativeHost.resolve(helper));
                    hostUnits.add(unit);
                }
                boolean[] instrumentation = host == null
                    ? new boolean[] {false} : new boolean[] {false, true};
                for (int level : new int[] {0, 2}) {
                    for (boolean sanitized : instrumentation) {
                        Path product = project.resolve("native" + level + (sanitized ? "-sanitized" : ""));
                        NativeCompiler.build(new deal.toolchain.NativeBuildRequest(
                            out, product, level, hostUnits, null),
                            sanitized ? NativeCompiler.SANITIZER_FLAGS : List.of());
                        for (boolean stress : new boolean[] {false, true}) {
                            var environment = new java.util.HashMap<String, String>();
                            environment.put("DEAL_GC_STRESS", stress ? "1" : "0");
                            if (sanitized) environment.putAll(NativeCompiler.sanitizerEnvironment());
                            var result = sanitized
                                ? NativeCompiler.runSanitized(product, project, environment).result()
                                : NativeCompiler.execute(product, project, environment);
                            if (result.exitCode() != 0 || !result.stderr().equals(expectedStderr)
                                    || !result.stdout().equals(expected))
                                throw new AssertionError("RUNTIME_OR_RESULT_FAILURE LLVM O" + level
                                    + " stress=" + stress + " sanitized=" + sanitized + ": " + result.describe()
                                    + " expected stdout=" + expected + " source=" + entry);
                        }
                    }
                }
                continue;
            }
            List<String> launch;
            if (backend == Backend.JVM) {
                if (host != null) Files.copy(host.resolve("probe.java"), out.resolve("HostProbe.java"));
                Path classes = out.resolve("classes");
                Files.createDirectories(classes);
                String cp = TestClasspath.absolute(TestClasspath.EmptyEntry.OMIT);
                var command = new ArrayList<>(List.of("javac", "--release", "25", "-proc:none",
                    "-cp", cp, "-d", classes.toAbsolutePath().toString()));
                try (var files = Files.walk(out)) {
                    files.filter(p -> p.toString().endsWith(".java")).sorted()
                        .forEach(p -> command.add(p.toAbsolutePath().toString()));
                }
                var built = JvmCompilation.compile(command, out);
                if (built.exitCode() != 0) throw new AssertionError("ARTIFACT_BUILD_FAILURE: " + built.stderr());
                launch = List.of("java", "-cp", classes.toAbsolutePath() + File.pathSeparator + cp,
                    JvmNames.classNameFor("main"));
            } else {
                String extension = backend == Backend.JS ? "js" : "lua";
                if (host != null) {
                    Files.createDirectories(out.resolve("host"));
                    Files.copy(host.resolve("probe." + extension), out.resolve("host/probe." + extension));
                }
                launch = List.of(backend == Backend.JS ? "node" : "luajit", "main." + extension);
            }
            var result = BoundedProcess.runCaptured(launch, out, Map.of());
            if (result.stdoutTruncated() || result.stderrTruncated() || result.exitCode() != 0
                    || !result.stderr().equals(expectedStderr) || !result.stdout().equals(expected))
                throw new AssertionError("RUNTIME_OR_RESULT_FAILURE " + backend + ": " + result
                    + " expected stdout=" + expected + " source=" + entry);
        }
    }

    static void remove(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    public static void main(String[] args) throws Exception {
        // Bridge used by Hypothesis: one minimized source is tested against an
        // independent transcript on every lane, never against another backend.
        if (args.length == 3) {
            Path root = Files.createTempDirectory("deal-stateful-");
            try { execute(Files.readString(Path.of(args[0])), Files.readString(Path.of(args[1])),
                    root, Path.of(args[2])); }
            finally { remove(root); }
            return;
        }
        Path root = Files.createTempDirectory("deal-compositions-");
        try {
            String fixture = "control-flow/for-of-nested-compositions.deal";
            execute(ConformanceHarnessMetadata.productionCorpusSource(Files.readString(
                Path.of("test/conformance/backend-runtime").resolve(fixture)), fixture),
                "NESTED_OK\n", root.resolve("nested"), null);
            for (String commas : List.of("trailing-argument-comma", "trailing-parameter-comma")) {
                Path fixturePath = Path.of("test/conformance/backend-runtime/functions/" + commas + ".deal");
                execute(ConformanceHarnessMetadata.productionCorpusSource(Files.readString(fixturePath),
                    fixturePath.toString()), "", root.resolve(commas), null,
                    List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            }
            // Each seed changes values and composition depth, not the language
            // contract. Integer arithmetic here is exactly representable binary64.
            for (int seed = 0; seed < 4; seed++) {
                int base = seed + 1, delta = seed + 3, depth = seed + 1;
                String body = "let captured:()=>number=function():number{return 0;};"
                    + "let original:Error={code:\"C\",message:\"old\"};"
                    + "try{throw original;}catch(e){"
                    + "captured=function():number{if((e as Error)===original){return state;}return -1000;};"
                    + "(e as Error).message=\"new\";}"
                    + "state=state+" + delta + ";"
                    + "if(original.message!==\"new\"){throw {code:\"TEST\",message:\"identity\"};}"
                    + "let text=json.stringify({value:captured(),drop:captured});"
                    + "if(text===undefined){throw {code:\"TEST\",message:\"json\"};}"
                    + "let parsed=json.parse(text as string) as Record<string,unknown>;"
                    + "if(Object.hasOwn(parsed,\"drop\")){throw {code:\"TEST\",message:\"omission\"};}"
                    + "return parsed.value as number;";
                for (int i = 0; i < depth; i++)
                    body = "let f:()=>number=function():number{" + body + "};return f();";
                String source = "import * as c from \"std/console\";import * as json from \"std/json\";"
                    + "function run():number{let state:number=" + base + ";" + body + "}"
                    + "export function main():null{if(run()!==" + (base + delta)
                    + "){throw {code:\"TEST\",message:\"value\"};}c.log(\"COMPOSE_OK\");return null;}";
                execute(source, "COMPOSE_OK\n", root.resolve("seed" + seed), null);
            }
            System.out.println("Composition production: 15 composition lanes and 14 trailing-comma executions passed");
        } finally { remove(root); }
    }
}
