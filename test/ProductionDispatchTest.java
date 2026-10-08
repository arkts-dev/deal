package deal.test;

import deal.checker.CheckResult;
import deal.checker.NameResolver;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmBackend;
import deal.codegen.jvm.JvmNames;
import deal.diagnostics.CompilerDiagnostic;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.module.CompilationOrchestrator;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.project.CliOverrides;
import deal.project.ProjectLocator;
import deal.publication.PublicationStager;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.SemanticLowerer;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticProfile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class ProductionDispatchTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static void checkEq(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual),
            message + " (expected " + expected + ", got " + actual + ")");
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    // =========================================================================
    // Fixture helpers
    // =========================================================================

    private static final String DEAL_JSON_LUA = """
        {
          "languageVersion": "1.2",
          "moduleRoots": ["src"],
          "output": "out",
          "backend": "luajit"
        }
        """;

    private static final String DEAL_JSON_JVM = """
        {
          "languageVersion": "1.2",
          "moduleRoots": ["src"],
          "output": "out",
          "backend": "jvm"
        }
        """;

    private static final String HOST_DEAL_JSON_LUA = """
        {
          "languageVersion": "1.2",
          "moduleRoots": ["src"],
          "output": "out",
          "backend": "luajit",
          "externals": {
            "host/cfg": { "declaration": "cfg.d.deal" }
          }
        }
        """;

    /** The covered library module: no call, no later-slice construct. */
    private static final String LIB_SOURCE = """
        export function value(): int {
          return 42
        }

        export function label(): string {
          return "lib"
        }
        """;

    /** The covered two-module entry: a COMPILED import, no call. */
    private static final String APP_SOURCE = """
        import * as lib from "./lib"
        import * as console from "std/console"

        export function main(): null {
          console.log("PROBE|MAIN-ONCE")
          return null
        }

        export function label(): string {
          return "app"
        }
        """;

    /** The overflow fixture: the production DEAL_ERROR_CODE terminal. */
    private static final String OVERFLOW_SOURCE = """
        export function main(): null {
          let x: int = 2147483647 + 1
          return null
        }
        """;

    /** The bytes-bearing fixture: the lowering fails CONSTRUCT_UNLOWERED. */
    private static final String BYTES_SOURCE = """
        export function main(): null {
          let n: int = 3
          let b: bytes = bytes(n)
          return null
        }
        """;

    private static final String ERROR_SOURCE = """
        export function main(): null {
          let e: Error = { code: "E1", message: "m" }
          return null
        }
        """;

    private static final String TIME_SOURCE = """
        import * as time from "std/time"

        export function main(): null {
          let t: int = time.nowMillis()
          return null
        }
        """;

    /** The function-typed materialization (a later slice's construct). */
    private static final String FUNCTION_VALUE_SOURCE = """
        export function main(): null {
          let f: (a: int) => int = one
          let x: int = f(1)
          return null
        }

        function one(x: int): int {
          return x
        }
        """;

    /** The cross-module sync call: the realized external-call arm. */
    private static final String SYNC_CALL_SOURCE = """
        import * as lib from "./lib"

        export function main(): null {
          let x: int = lib.value()
          return null
        }
        """;

    /** The cross-module async library. */
    private static final String ASYNC_LIB_SOURCE = """
        export async function getValue(): int {
          return 42
        }
        """;

    /** The cross-module async call (a never-invoked body carries it). */
    private static final String CROSS_ASYNC_SOURCE = """
        import * as lib from "./lib"

        export function main(): null {
          return null
        }

        async function worker(): int {
          return await lib.getValue()
        }
        """;

    /**
     * The same-module async call: accepted, emitted, and executable. The
     * awaiting call sits in an exported zero-arity async function, so a
     * runner can invoke it through the artifact's published entry surface
     * and actually execute the ASYNC_START(DEAL_BODY)/AWAIT path.
     */
    private static final String SAME_ASYNC_SOURCE = """
        export function main(): null {
          return null
        }

        async function compute(): int {
          return 1
        }

        export async function worker(): int {
          return await compute()
        }
        """;

    /** The host declaration of the HOST-import fixture. */
    private static final String HOST_DECLARATION = """
        export function version(): int;
        """;

    /** The host-import fixture: the import is never called. */
    private static final String HOST_IMPORT_SOURCE = """
        import * as cfg from "host/cfg"

        export function main(): null {
          return null
        }
        """;

    private static void write(Path root, String relative, String content)
            throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void deleteRecursively(Path dir) {
        try {
            if (dir == null || !Files.exists(dir)) {
                return;
            }
            Files.walk(dir).sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
        } catch (Exception ignored) {
        }
    }

    private record ProjectOutcome(int exitCode, String stderr) {
    }

    /** Runs the release-owned production CLI in-process. */
    private static ProjectOutcome runProductionCli(String... args)
            throws Exception {
        return runCli(() -> deal.Main.run(args));
    }

    /** Runs the test-scope harness compile entry in-process. */
    private static ProjectOutcome runHarnessCli(String... args)
            throws Exception {
        return runCli(() -> HarnessCompileEntry.run(args));
    }

    private interface CliCall {
        int run() throws Exception;
    }

    private static ProjectOutcome runCli(CliCall call) throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        int exitCode;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exitCode = call.run();
            System.err.flush();
        } finally {
            System.setErr(originalErr);
        }
        return new ProjectOutcome(exitCode,
            err.toString(StandardCharsets.UTF_8).trim());
    }

    private record ProcessOutcome(int exitCode, String output) {
    }

    private static ProcessOutcome runProcess(Path directory, String... command)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new ProcessOutcome(exitCode, output);
    }

    /**
     * One orchestrator compile of the given project through the compile's
     * own manifest with an explicit invocation (the dispatch seam).
     */
    private record ArmCompile(CompilationOrchestrator orchestrator,
                              boolean success) {
    }

    private static ArmCompile compileWithInvocation(Path project, String entry,
            CompilerInvocation invocation) throws Exception {
        return compileWithInvocation(project, entry, invocation, false);
    }

    private static ArmCompile compileWithInvocation(Path project, String entry,
            CompilerInvocation invocation, boolean dumpIr) throws Exception {
        Path entryFile = project.resolve(entry).toAbsolutePath().normalize();
        ProjectLocator.LocateResult located = ProjectLocator.locate(
            entryFile.toString(), new CliOverrides(null, null));
        if (located.context() == null) {
            throw new IllegalStateException("locate failed: " + located);
        }
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            located.context(), entryFile, false, dumpIr, false, false, null,
            invocation);
        return new ArmCompile(orchestrator, orchestrator.compile());
    }

    private static ProjectOutcome productionCompile(Path project, String entry,
            String output) throws Exception {
        return runProductionCli("compile",
            project.resolve(entry).toAbsolutePath().toString(),
            "--output", project.resolve(output).toAbsolutePath().toString());
    }

    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    private static CompilerInvocation commonShadow() {
        return CompilerProfileProvider.resolveCommonShadow(
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.V1_2_ACTIVE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    private static CompilerInvocation legacyRegression() {
        return CompilerProfileProvider.resolveLegacyRegression(
            SemanticProfile.LEGACY_SAFE_INT, ReleaseState.V1_2_ACTIVE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    /** The test-only PUBLIC_BUILD record of another release state. */
    private static CompilerInvocation publicBuildPreActivation() {
        return CompilerProfileProvider.resolve(ReleaseState.PRE_ACTIVATION,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    /** The test-only PUBLIC_BUILD record of another registry digest. */
    private static CompilerInvocation publicBuildShadowRegistry() {
        return CompilerProfileProvider.resolve(ReleaseState.V1_2_ACTIVE,
            CapabilityRegistry.releaseRegistry());
    }

    private static List<String> artifactFiles(Path root) throws Exception {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                .map(path -> root.relativize(path).toString()
                    .replace(File.separatorChar, '/'))
                .sorted().toList();
        }
    }

    /** Byte-for-byte snapshot of one artifact tree. */
    private static Map<String, byte[]> snapshotTree(Path root) throws Exception {
        Map<String, byte[]> snapshot = new java.util.LinkedHashMap<>();
        for (String relative : artifactFiles(root)) {
            snapshot.put(relative, Files.readAllBytes(root.resolve(relative)));
        }
        return snapshot;
    }

    private static void checkTreeIdentical(Map<String, byte[]> before,
            Path root, String context) throws Exception {
        Map<String, byte[]> after = snapshotTree(root);
        boolean same = before.keySet().equals(after.keySet());
        if (same) {
            for (Map.Entry<String, byte[]> entry : before.entrySet()) {
                same = Arrays.equals(entry.getValue(),
                    after.get(entry.getKey()));
                if (!same) {
                    break;
                }
            }
        }
        check(same, context + ": the previous artifact set is byte-identical");
    }

    // =========================================================================
    // 1. The production-invocation predicate
    // =========================================================================

    private static void testInvocationPredicate() {
        System.out.println("-- the production-invocation predicate: record "
            + "identity, never a purpose-only test --");

        check(CompilationOrchestrator.isProductionInvocation(
                productionInvocation()),
            "the release-owned production record is the production invocation");
        checkEq(productionInvocation(),
            CompilerProfileProvider.resolve(
                ReleaseConfiguration.CURRENT_RELEASE_STATE,
                ReleaseConfiguration.releaseCapabilityRegistry()),
            "the predicate's reference record is the release-state resolution");

        check(!CompilationOrchestrator.isProductionInvocation(commonShadow()),
            "the COMMON_SHADOW harness record is not the production invocation");
        check(!CompilationOrchestrator.isProductionInvocation(
                legacyRegression()),
            "the LEGACY_REGRESSION harness record is not the production "
                + "invocation");
        check(!CompilationOrchestrator.isProductionInvocation(
                publicBuildPreActivation()),
            "a test-only PUBLIC_BUILD record of another release state is not "
                + "the production invocation");
        check(!CompilationOrchestrator.isProductionInvocation(
                publicBuildShadowRegistry()),
            "a test-only PUBLIC_BUILD record of another registry digest is "
                + "not the production invocation");

        check(productionInvocation().purpose()
                == deal.semantic.ir.InvocationPurpose.PUBLIC_BUILD
                && productionInvocation().semanticProfile()
                    == SemanticProfile.DEAL_V1_2_INT32,
            "the production invocation is the release-derived PUBLIC_BUILD "
                + "record of the active release");
    }

    // =========================================================================
    // 2. One production compile: one artifact, no route plan, real toolchain
    // =========================================================================

    private static void testProductionLuaJitSingleModule() throws Exception {
        System.out.println("-- LuaJIT production compile: one project artifact, "
            + "one emission, no route plan --");

        Path project = Files.createTempDirectory("production-dispatch-lua-");
        try {
            write(project, "deal.json", DEAL_JSON_LUA);
            write(project, "src/main.deal", """
                import * as console from "std/console"

                export function main(): null {
                  console.log("PROBE|MAIN-ONCE")
                  return null
                }

                export function label(): string {
                  return "single"
                }
                """);

            ArmCompile arm = compileWithInvocation(project, "src/main.deal",
                productionInvocation());
            check(arm.success(), "the production LuaJIT compile succeeds: "
                + arm.orchestrator().diagnostics());
            if (!arm.success()) {
                return;
            }
            CompilationOrchestrator orchestrator = arm.orchestrator();
            checkEq(1, orchestrator.semanticEmissionCount(),
                "the production arm records exactly one project emission");

            Path out = project.resolve("out");
            List<String> artifacts = artifactFiles(out);
            check(artifacts.contains("main.lua"),
                "the one project artifact is named for the entry module: "
                    + artifacts);
            check(!artifacts.contains("lib.lua"),
                "the published set carries no per-module sibling: "
                    + artifacts);
            check(artifacts.stream().noneMatch(
                    file -> file.endsWith(".deal.map.json")),
                "the production set carries no source-map sidecar: "
                    + artifacts);

            ProcessOutcome run = runProcess(out, "luajit", "main.lua");
            check(run.exitCode() == 0,
                "the production LuaJIT artifact runs clean: exit="
                    + run.exitCode() + " output=" + run.output());
            checkEq(1, countOccurrences(run.output(), "PROBE|MAIN-ONCE"),
                "the entry main runs exactly once per chunk execution");

            // Byte-identical repeated compiles.
            Map<String, byte[]> first = snapshotTree(out);
            ProjectOutcome repeat = productionCompile(project, "src/main.deal",
                "out-repeat");
            check(repeat.exitCode() == 0, "the repeated compile succeeds: "
                + repeat.stderr());
            Map<String, byte[]> second = snapshotTree(
                project.resolve("out-repeat"));
            checkEq(first.keySet(), second.keySet(),
                "the repeated compile publishes the same artifact set");
            for (String relative : first.keySet()) {
                check(Arrays.equals(first.get(relative), second.get(relative)),
                    "the repeated compile is byte-identical for " + relative);
            }
        } finally {
            deleteRecursively(project);
        }
    }

    private static void testProductionJvmTwoModule() throws Exception {
        System.out.println("-- JVM production compile: one project artifact, "
            + "javac --release 25 -proc:none plus java --");

        Path project = Files.createTempDirectory("production-dispatch-jvm-");
        try {
            write(project, "deal.json", DEAL_JSON_JVM);
            write(project, "src/lib.deal", LIB_SOURCE);
            write(project, "src/main.deal", APP_SOURCE);

            ArmCompile arm = compileWithInvocation(project, "src/main.deal",
                productionInvocation());
            check(arm.success(), "the production JVM compile succeeds: "
                + arm.orchestrator().diagnostics());
            if (!arm.success()) {
                return;
            }
            CompilationOrchestrator orchestrator = arm.orchestrator();
            checkEq(1, orchestrator.semanticEmissionCount(),
                "the production arm records exactly one project emission");

            Path out = project.resolve("out");
            List<String> artifacts = artifactFiles(out);
            check(artifacts.equals(List.of("Main.java")),
                "the one project artifact is the entry class source: "
                    + artifacts);

            // The export surfaces of both modules are in the artifact, keyed
            // by the dotted module path.
            String source = Files.readString(out.resolve("Main.java"));
            check(source.contains("\"lib\"") && source.contains("\"main\""),
                "the artifact carries one export surface per module keyed by "
                    + "module identity");
            check(source.contains("public static void main(String[] args)"),
                "the project class publishes public static void main");
            check(source.contains("public final class Main"),
                "the project class is one public final class");

            String buildCp = Path.of("build").toAbsolutePath().normalize()
                .toString();
            ProcessOutcome javac = runProcess(project, "javac", "--release",
                "25", "-proc:none", "-cp", buildCp, "-d", out.toString(),
                out.resolve("Main.java").toString());
            check(javac.exitCode() == 0,
                "the production JVM artifact compiles: " + javac.output());
            if (javac.exitCode() != 0) {
                return;
            }
            ProcessOutcome run = runProcess(out, "java", "-cp",
                buildCp + File.pathSeparator + out, "Main");
            check(run.exitCode() == 0,
                "the production JVM artifact runs clean: exit="
                    + run.exitCode() + " output=" + run.output());
            checkEq(1, countOccurrences(run.output(), "PROBE|MAIN-ONCE"),
                "the entry main runs exactly once under java");
        } finally {
            deleteRecursively(project);
        }
    }

    private static void testProductionLuaJitTwoModuleSurfaces()
            throws Exception {
        System.out.println("-- LuaJIT two-module realizable fixture: one "
            + "artifact per target and the executed export surfaces --");

        Path project = Files.createTempDirectory("production-dispatch-surf-");
        try {
            write(project, "deal.json", DEAL_JSON_LUA);
            write(project, "src/lib.deal", LIB_SOURCE);
            write(project, "src/main.deal", APP_SOURCE);

            ArmCompile arm = compileWithInvocation(project, "src/main.deal",
                productionInvocation());
            check(arm.success(), "the two-module production compile succeeds: "
                + arm.orchestrator().diagnostics());
            if (!arm.success()) {
                return;
            }
            CompilationOrchestrator orchestrator = arm.orchestrator();
            checkEq(1, orchestrator.semanticEmissionCount(),
                "the two-module compile records one project emission");
            List<String> artifacts = artifactFiles(project.resolve("out"));
            check(artifacts.contains("main.lua")
                    && !artifacts.contains("lib.lua"),
                "the two-module compile publishes one artifact named for the "
                    + "entry module: " + artifacts);

            // The executed surfaces: the entry surface is returned by the
            // chunk; a runner probe asserts both per-module surfaces and
            // their declaration-order entries after execution.
            write(project, "probe.lua", """
                local surfaces = dofile("out/main.lua")
                assert(type(surfaces) == "table", "the chunk returns the entry surface")
                assert(type(surfaces.label) == "table", "the entry surface carries label")
                assert(surfaces.label.__kind == "function", "the landed entry shape")
                local lib = __exportSurfaces["lib"]
                assert(type(lib) == "table", "the lib surface exists")
                assert(type(lib.value) == "table" and lib.value.__kind == "function",
                  "the lib surface carries value")
                assert(type(lib.label) == "table" and lib.label.__kind == "function",
                  "the lib surface carries label")
                return surfaces
                """);
            ProcessOutcome probe = runProcess(project, "luajit", "probe.lua");
            check(probe.exitCode() == 0,
                "the executed entry surface carries its declared exports: "
                    + probe.output());
            ProcessOutcome run = runProcess(project.resolve("out"), "luajit",
                "main.lua");
            checkEq(1, countOccurrences(run.output(), "PROBE|MAIN-ONCE"),
                "the two-module entry main runs exactly once");
        } finally {
            deleteRecursively(project);
        }
    }

    private static void testConversionOverflowTerminal() throws Exception {
        System.out.println("-- the production conversion-overflow fixture: the "
            + "DEAL_ERROR_CODE terminal --");

        Path project = Files.createTempDirectory("production-dispatch-ovf-");
        try {
            write(project, "deal.json", DEAL_JSON_LUA);
            write(project, "src/main.deal", OVERFLOW_SOURCE);
            ProjectOutcome compile = productionCompile(project, "src/main.deal",
                "out");
            check(compile.exitCode() == 0,
                "the overflow fixture compiles: " + compile.stderr());
            if (compile.exitCode() != 0) {
                return;
            }
            ProcessOutcome run = runProcess(project.resolve("out"), "luajit",
                "main.lua");
            check(run.exitCode() == 1
                    && run.output().contains("DEAL_ERROR_CODE: E8004"),
                "the production artifact prints DEAL_ERROR_CODE: E8004 and "
                    + "exits 1: exit=" + run.exitCode() + " output="
                    + run.output());
        } finally {
            deleteRecursively(project);
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    // =========================================================================
    // 3. Atomic failure through the dispatch
    // =========================================================================

    private static void testAtomicFailureThroughDispatch() throws Exception {
        System.out.println("-- atomic failure: a failing lowering stages nothing "
            + "and preserves the previous set; the cross-module sync call "
            + "emits --");

        Path project = Files.createTempDirectory("production-dispatch-atomic-");
        try {
            Path out = project.resolve("out");
            write(project, "deal.json", DEAL_JSON_LUA);

            write(project, "src/main.deal", FUNCTION_VALUE_SOURCE);
            // A previous artifact set in the live output root.
            write(out, "main.lua", "-- previous artifact\n");
            write(out, "deal/runtime.lua", "-- previous runtime\n");
            Map<String, byte[]> before = snapshotTree(out);
            ProjectOutcome compile = productionCompile(project, "src/main.deal",
                "out");
            check(compile.exitCode() != 0,
                "the function-typed-materialization production compile fails "
                    + "closed");
            check(compile.stderr().contains("E6005")
                    && compile.stderr().contains("CONSTRUCT_UNLOWERED"),
                "the failing lowering names the construct rule: "
                    + compile.stderr());
            checkTreeIdentical(before, out,
                "the failing lowering preserves the previous set");

            Path sync = Files.createTempDirectory(
                "production-dispatch-atomic-sync-");
            try {
                write(sync, "deal.json", DEAL_JSON_LUA);
                write(sync, "src/lib.deal", LIB_SOURCE);
                write(sync, "src/main.deal", SYNC_CALL_SOURCE);
                Path syncOut = sync.resolve("out");
                ProjectOutcome syncCompile = productionCompile(sync,
                    "src/main.deal", "out");
                check(syncCompile.exitCode() == 0,
                    "the cross-module sync call compiles: "
                        + syncCompile.stderr());
                check(!syncCompile.stderr().contains("E6005"),
                    "the cross-module sync call reports no emitter gap: "
                        + syncCompile.stderr());
                check(artifactFiles(syncOut).contains("main.lua")
                        && !artifactFiles(syncOut).contains("lib.lua"),
                    "the emit publishes the one project artifact and no "
                        + "per-module sibling: " + artifactFiles(syncOut));
                ProcessOutcome syncRun = runProcess(syncOut, "luajit", "main.lua");
                check(syncRun.exitCode() == 0,
                    "the published project artifact executes: "
                        + syncRun.output());
            } finally {
                deleteRecursively(sync);
            }
        } finally {
            deleteRecursively(project);
        }
    }

    // =========================================================================
    // 4. The fail-closed families and the accepted closures
    // =========================================================================

    private static void testFailClosedFamilies() throws Exception {
        System.out.println("-- accept-and-emit families: HOST import, "
            + "cross-module async, bytes, Error, time, function values --");

        Path host = Files.createTempDirectory("production-dispatch-host-");
        try {
            write(host, "deal.json", HOST_DEAL_JSON_LUA);
            write(host, "cfg.d.deal", HOST_DECLARATION);
            write(host, "src/main.deal", HOST_IMPORT_SOURCE);
            ArmCompile arm = compileWithInvocation(host, "src/main.deal",
                productionInvocation());
            check(arm.success(),
                "the HOST-importing closure compiles: "
                    + arm.orchestrator().diagnostics());
            if (arm.success()) {
                checkEq(1, arm.orchestrator().semanticEmissionCount(),
                    "the HOST import records one project emission");
                List<String> artifacts = artifactFiles(host.resolve("out"));
                check(artifacts.contains("main.lua")
                        && !artifacts.contains("cfg.lua"),
                    "the HOST import publishes the one project artifact and "
                        + "no per-module sibling: " + artifacts);
                String chunk = Files.readString(host.resolve("out/main.lua"),
                    StandardCharsets.UTF_8);
                check(chunk.contains("__exportSurfaces[\"host.cfg\"] = "
                        + "__exportSurfaces[\"host.cfg\"] or "
                        + "__rt.load_host(\"host/cfg\", {")
                        && chunk.contains("[\"version\"] = "),
                    "the artifact carries the host load with the emitted "
                        + "declared map");
            }
        } finally {
            deleteRecursively(host);
        }

        Path async = Files.createTempDirectory("production-dispatch-async-");
        try {
            write(async, "deal.json", DEAL_JSON_LUA);
            write(async, "src/lib.deal", ASYNC_LIB_SOURCE);
            write(async, "src/main.deal", CROSS_ASYNC_SOURCE);
            ArmCompile arm = compileWithInvocation(async, "src/main.deal",
                productionInvocation());
            check(arm.success(),
                "the cross-module async closure compiles: "
                    + arm.orchestrator().diagnostics());
            if (arm.success()) {
                checkEq(1, arm.orchestrator().semanticEmissionCount(),
                    "the cross-module async call records one project emission");
                List<String> artifacts = artifactFiles(async.resolve("out"));
                check(artifacts.contains("main.lua")
                        && !artifacts.contains("lib.lua"),
                    "the cross-module async call publishes the one project "
                        + "artifact and no per-module sibling: " + artifacts);
                String chunk = Files.readString(async.resolve("out/main.lua"),
                    StandardCharsets.UTF_8);
                check(!chunk.contains("EXTERNAL_ASYNC_CALL")
                        && !chunk.contains("SharedM"),
                    "the artifact carries no superseded guard token and no "
                        + "per-module async-entry reference");
                ProcessOutcome run = runProcess(async.resolve("out"), "luajit",
                    "main.lua");
                check(run.exitCode() == 0,
                    "the cross-module async artifact executes: "
                        + run.output());
            }
        } finally {
            deleteRecursively(async);
        }

        // (c) The same-module async call emits and executes on both
        // targets. The awaiting call is reachable through the exported
        // zero-arity async function's published surface, so the runner's
        // invocation really runs the ASYNC_START(DEAL_BODY)/AWAIT path
        // (never merely compiling a never-invoked body).
        Path same = Files.createTempDirectory("production-dispatch-sameasync-");
        try {
            write(same, "deal.json", DEAL_JSON_LUA);
            write(same, "src/main.deal", SAME_ASYNC_SOURCE);

            // LuaJIT: the entry chunk executes under luajit and the
            // runner invokes the exported async function.
            ProjectOutcome luaCompile = productionCompile(same, "src/main.deal",
                "out");
            check(luaCompile.exitCode() == 0,
                "the same-module async closure emits: " + luaCompile.stderr());
            if (luaCompile.exitCode() != 0) {
                return;
            }
            write(same, "probe.lua", """
                local surfaces = dofile("out/main.lua")
                assert(type(surfaces) == "table",
                  "the chunk returns the entry surface")
                local worker = surfaces.worker
                assert(type(worker) == "table" and worker.__kind == "function",
                  "the entry surface publishes the same-module async export")
                local completion = worker.f()
                assert(completion == 1,
                  "the awaiting call completes with 1, got "
                    .. tostring(completion))
                """);
            ProcessOutcome probe = runProcess(same, "luajit", "probe.lua");
            check(probe.exitCode() == 0,
                "the same-module await path executes under luajit: "
                    + probe.output());

            // JVM: the artifact compiles with javac --release 25
            // -proc:none and the runner executes the exported async
            // function through the artifact's published surface.
            Path jvmOut = same.resolve("out-jvm");
            ProjectOutcome jvmCompile = runProductionCli("compile",
                same.resolve("src/main.deal").toAbsolutePath().toString(),
                "--backend", "jvm", "--output",
                jvmOut.toAbsolutePath().toString());
            check(jvmCompile.exitCode() == 0,
                "the same-module async JVM closure emits: "
                    + jvmCompile.stderr());
            if (jvmCompile.exitCode() != 0) {
                return;
            }
            write(jvmOut, "AsyncWorkerRunner.java", """
                import deal.codegen.jvm.JvmRuntime;

                public final class AsyncWorkerRunner {
                  public static void main(String[] args) {
                    Main.main(new String[0]);
                    JvmRuntime.Table surface = Main.EXPORT_SURFACES.get("main");
                    JvmRuntime.FunctionValue worker =
                        (JvmRuntime.FunctionValue) surface.read("worker");
                    Object completion = worker.fn.invoke(new Object[0]);
                    System.out.println("PROBE|ASYNC-RESULT|" + completion);
                  }
                }
                """);
            String buildCp = Path.of("build").toAbsolutePath().normalize()
                .toString();
            ProcessOutcome javac = runProcess(same, "javac", "--release", "25",
                "-proc:none", "-cp", buildCp, "-d", jvmOut.toString(),
                jvmOut.resolve("Main.java").toString(),
                jvmOut.resolve("AsyncWorkerRunner.java").toString());
            check(javac.exitCode() == 0,
                "the same-module async JVM artifact compiles: "
                    + javac.output());
            if (javac.exitCode() != 0) {
                return;
            }
            ProcessOutcome jvmRun = runProcess(jvmOut, "java", "-cp",
                buildCp + File.pathSeparator + jvmOut, "AsyncWorkerRunner");
            check(jvmRun.exitCode() == 0
                    && jvmRun.output().contains("PROBE|ASYNC-RESULT|1"),
                "the same-module await path executes under java: exit="
                    + jvmRun.exitCode() + " output=" + jvmRun.output());
        } finally {
            deleteRecursively(same);
        }

        checkBytesProductionCoverage();
        checkTimeNowMillisCoverage();
        checkLaterSliceConstruct("function-typed materialization",
            Map.of("src/main.deal", FUNCTION_VALUE_SOURCE),
            "CONSTRUCT_UNLOWERED");
        checkBuiltinErrorConstruction();

        // (e) The extern-C declaration import is admitted on LuaJIT: the
        // production compile emits the load_ffi prelude at the import's
        // MODULE_IMPORT, and the artifact's init fails at the import with
        // FFI_LIBRARY_LOAD (the wiring names an unbuilt library); the JVM
        // target keeps its phase-3.9 E6006 rejection.
        Path externC = Files.createTempDirectory("production-dispatch-ffi-");
        try {
            write(externC, "deal.json", """
                {
                  "languageVersion": "1.2",
                  "moduleRoots": ["src"],
                  "output": "out",
                  "backend": "luajit",
                  "externals": {
                    "native/math": {
                      "declaration": "native.d.deal",
                      "nativeLibrary": "libs/libnative.so"
                    }
                  }
                }
                """);
            write(externC, "native.d.deal", """
                // @extern-c
                export function add(a: int, b: int): int;
                """);
            write(externC, "src/main.deal", """
                import * as math from "native/math"

                export function main(): null {
                  return null
                }
                """);
            ProjectOutcome compile = productionCompile(externC,
                "src/main.deal", "out");
            check(compile.exitCode() == 0,
                "the extern-C declaration import is admitted on LuaJIT: "
                    + compile.stderr());
            if (compile.exitCode() == 0) {
                String artifact = Files.readString(externC.resolve("out/main.lua"),
                    StandardCharsets.UTF_8);
                check(artifact.contains("__exportSurfaces[\"native.math\"] = "
                        + "__exportSurfaces[\"native.math\"] or __rt.load_ffi("
                        + "\"ffi:@$external/native/math\", "),
                    "the artifact publishes the loaded table through the "
                        + "load_ffi call at the import's MODULE_IMPORT: " + artifact);
                check(artifact.contains("nativeLibrary = { kind = "
                        + "\"MANIFEST_RELATIVE_PATH\", loaderText = \""),
                    "the bundle carries the manifest-relative loader text");
                check(artifact.contains(", \"" + externC.resolve("src/main.deal")
                        .toAbsolutePath() + "\", 1, 1)"),
                    "the load carries the import statement's span triplet");
                check(!artifact.contains("ffi.C") && !artifact.contains("cdef("),
                    "the artifact carries no ffi.C/cdef text");

                write(externC, "out/probe.lua", """
                    package.path = "./?.lua;./std/?.lua;" .. package.path
                    local ok, err = pcall(dofile, "main.lua")
                    if ok then
                      print("PROBE-FAIL|the init succeeded")
                      os.exit(1)
                    end
                    if type(err) ~= "table" or err.code == nil then
                      print("PROBE-FAIL|not a runtime error: " .. tostring(err))
                      os.exit(1)
                    end
                    print("ERR|" .. tostring(err.code) .. "|" .. tostring(err.message)
                      .. "|" .. tostring(err.file) .. "|" .. tostring(err.line)
                      .. "|" .. tostring(err.column))
                    """);
                ProcessOutcome run = runProcess(externC.resolve("out"),
                    "luajit", "probe.lua");
                check(run.exitCode() == 0
                        && run.output().startsWith("ERR|FFI_LIBRARY_LOAD|")
                        && run.output().contains("src/main.deal|1|1"),
                    "the emitted artifact fails at the import with "
                        + "FFI_LIBRARY_LOAD at the import origin: exit="
                        + run.exitCode() + " output=" + run.output());
            }

            // The JVM target keeps the phase-3.9 E6006 rejection.
            ProjectOutcome jvmCompile = runProductionCli("compile",
                externC.resolve("src/main.deal").toAbsolutePath().toString(),
                "--backend", "jvm", "--output",
                externC.resolve("out-jvm").toAbsolutePath().toString());
            check(jvmCompile.exitCode() != 0
                    && jvmCompile.stderr().contains("E6006"),
                "the JVM extern-C case keeps the phase-3.9 E6006: "
                    + jvmCompile.stderr());
        } finally {
            deleteRecursively(externC);
        }
    }

    private static void checkLaterSliceConstruct(String name,
            Map<String, String> sources, String expectedRule) throws Exception {
        Path project = Files.createTempDirectory("production-dispatch-slice-");
        try {
            write(project, "deal.json", DEAL_JSON_LUA);
            for (Map.Entry<String, String> source : sources.entrySet()) {
                write(project, source.getKey(), source.getValue());
            }
            ProjectOutcome compile = productionCompile(project, "src/main.deal",
                "out");
            check(compile.exitCode() != 0,
                name + ": the later-slice construct fails closed");
            check(compile.stderr().contains("E6005")
                    && compile.stderr().contains(expectedRule),
                name + ": the failure names " + expectedRule + ": "
                    + compile.stderr());
            check(!Files.exists(project.resolve("out")),
                name + ": the failure stages no artifact");
        } finally {
            deleteRecursively(project);
        }
    }

    /**
     * The K6 bytes coverage through the release-owned production invocation
     * (the fixture this battery pinned fail-closed): the closure emits
     * exactly one project artifact and the artifact executes under the real
     * {@code luajit} toolchain.
     */
    private static void checkBytesProductionCoverage() throws Exception {
        Path project = Files.createTempDirectory("production-dispatch-bytes-");
        try {
            write(project, "deal.json", DEAL_JSON_LUA);
            write(project, "src/main.deal", BYTES_SOURCE);
            ProjectOutcome compile = productionCompile(project, "src/main.deal",
                "out");
            check(compile.exitCode() == 0,
                "the bytes closure emits one project artifact: "
                    + compile.stderr());
            if (compile.exitCode() == 0) {
                ProcessOutcome run = runProcess(project.resolve("out"),
                    "luajit", "main.lua");
                check(run.exitCode() == 0,
                    "the bytes production artifact runs: exit=" + run.exitCode()
                        + " output=" + run.output());
            }
        } finally {
            deleteRecursively(project);
        }
    }

    /**
     * The K7 {@code time.nowMillis} coverage through the release-owned
     * production invocation (the fixture this battery pinned fail-closed):
     * the closure emits exactly one project artifact per target and both
     * artifacts execute under their real toolchains with the pinned E8004
     * {@code int out of safe range} terminal — the declared {@code int}
     * boundary is the single terminal of the target-clock read.
     */
    private static void checkTimeNowMillisCoverage() throws Exception {
        Path project = Files.createTempDirectory("production-dispatch-time-");
        try {
            write(project, "deal.json", DEAL_JSON_LUA);
            write(project, "src/main.deal", TIME_SOURCE);
            ProjectOutcome luaCompile = productionCompile(project, "src/main.deal",
                "out");
            check(luaCompile.exitCode() == 0,
                "the time.nowMillis closure emits one project artifact: "
                    + luaCompile.stderr());
            if (luaCompile.exitCode() == 0) {
                ProcessOutcome run = runProcess(project.resolve("out"),
                    "luajit", "main.lua");
                check(run.exitCode() == 1
                        && run.output().contains("DEAL_ERROR_CODE: E8004"),
                    "the LuaJIT artifact publishes the pinned E8004 terminal: exit="
                        + run.exitCode() + " output=" + run.output());
            }

            Path jvmOut = project.resolve("out-jvm");
            ProjectOutcome jvmCompile = runProductionCli("compile",
                project.resolve("src/main.deal").toAbsolutePath().toString(),
                "--backend", "jvm", "--output",
                jvmOut.toAbsolutePath().toString());
            check(jvmCompile.exitCode() == 0,
                "the time.nowMillis JVM closure emits one project artifact: "
                    + jvmCompile.stderr());
            if (jvmCompile.exitCode() != 0) {
                return;
            }
            String buildCp = Path.of("build").toAbsolutePath().normalize()
                .toString();
            ProcessOutcome javac = runProcess(project, "javac", "--release", "25",
                "-proc:none", "-cp", buildCp, "-d", jvmOut.toString(),
                jvmOut.resolve("Main.java").toString());
            check(javac.exitCode() == 0,
                "the JVM time.nowMillis artifact compiles: " + javac.output());
            if (javac.exitCode() != 0) {
                return;
            }
            ProcessOutcome javaRun = runProcess(jvmOut, "java", "-cp",
                buildCp + File.pathSeparator + jvmOut, "Main");
            check(javaRun.exitCode() == 1
                    && javaRun.output().contains("DEAL_ERROR_CODE: E8004"),
                "the JVM artifact publishes the pinned E8004 terminal: exit="
                    + javaRun.exitCode() + " output=" + javaRun.output());
        } finally {
            deleteRecursively(project);
        }
    }

    private static void checkBuiltinErrorConstruction() throws Exception {
        Path project = Files.createTempDirectory("production-dispatch-error-");
        try {
            write(project, "deal.json", DEAL_JSON_LUA);
            write(project, "src/main.deal", ERROR_SOURCE);
            ProjectOutcome luaCompile = productionCompile(project, "src/main.deal",
                "out");
            check(luaCompile.exitCode() == 0,
                "the builtin Error closure emits one project artifact: "
                    + luaCompile.stderr());
            if (luaCompile.exitCode() == 0) {
                String lua = Files.readString(project.resolve("out/main.lua"));
                check(lua.contains("__instT = {__d = true, code = ")
                        && lua.contains(", m = ")
                        && lua.contains("\"E1\"")
                        && lua.contains("\"m\""),
                    "the LuaJIT artifact carries the canonical err carrier built "
                        + "from the provided fields: " + lua);
                ProcessOutcome run = runProcess(project.resolve("out"),
                    "luajit", "main.lua");
                check(run.exitCode() == 0 && run.output().isEmpty(),
                    "the LuaJIT builtin Error artifact runs clean: exit="
                        + run.exitCode() + " output=" + run.output());
            }

            Path jvmOut = project.resolve("out-jvm");
            ProjectOutcome jvmCompile = runProductionCli("compile",
                project.resolve("src/main.deal").toAbsolutePath().toString(),
                "--backend", "jvm", "--output",
                jvmOut.toAbsolutePath().toString());
            check(jvmCompile.exitCode() == 0,
                "the builtin Error JVM closure emits one project artifact: "
                    + jvmCompile.stderr());
            if (jvmCompile.exitCode() != 0) {
                return;
            }
            String java = Files.readString(jvmOut.resolve("Main.java"));
            check(java.contains("new JvmRuntime.ErrorValue("),
                "the JVM artifact publishes the canonical ErrorValue carrier: "
                    + java);
            String buildCp = Path.of("build").toAbsolutePath().normalize()
                .toString();
            ProcessOutcome javac = runProcess(project, "javac", "--release", "25",
                "-proc:none", "-cp", buildCp, "-d", jvmOut.toString(),
                jvmOut.resolve("Main.java").toString());
            check(javac.exitCode() == 0,
                "the JVM builtin Error artifact compiles: " + javac.output());
            if (javac.exitCode() != 0) {
                return;
            }
            ProcessOutcome javaRun = runProcess(jvmOut, "java", "-cp",
                buildCp + File.pathSeparator + jvmOut, "Main");
            check(javaRun.exitCode() == 0 && javaRun.output().isEmpty(),
                "the JVM builtin Error artifact runs clean: exit="
                    + javaRun.exitCode() + " output=" + javaRun.output());
        } finally {
            deleteRecursively(project);
        }
    }

    // =========================================================================
    // 5. The C9 source-map disposition
    // =========================================================================

    private static void testSourceMapDisposition() throws Exception {
        System.out.println("-- source map: an explicit --source-map warns once "
            + "and stages no sidecar; --dump-ir is silent --");

        Path project = Files.createTempDirectory("production-dispatch-map-");
        try {
            write(project, "deal.json", DEAL_JSON_JVM);
            write(project, "src/lib.deal", LIB_SOURCE);
            write(project, "src/main.deal", APP_SOURCE);

            ProjectOutcome explicit = runProductionCli("compile",
                project.resolve("src/main.deal").toAbsolutePath().toString(),
                "--backend", "jvm", "--source-map", "--output",
                project.resolve("out-map").toAbsolutePath().toString());
            check(explicit.exitCode() == 0,
                "the explicit --source-map production compile succeeds: "
                    + explicit.stderr());
            checkEq(1, countOccurrences(explicit.stderr(),
                    deal.module.ProductionProjectEmission.WARNING_JVM),
                "the pinned JVM warning prints exactly once");
            check(artifactFiles(project.resolve("out-map"))
                    .contains("Main.java"),
                "the explicit --source-map compile publishes its project "
                    + "artifact");
            check(artifactFiles(project.resolve("out-map")).stream()
                    .noneMatch(file -> file.endsWith(".deal.map.json")),
                "the explicit --source-map compile writes no sidecar");

            ProjectOutcome dumpIr = runProductionCli("compile",
                project.resolve("src/main.deal").toAbsolutePath().toString(),
                "--backend", "jvm", "--dump-ir", "--output",
                project.resolve("out-dump").toAbsolutePath().toString());
            check(dumpIr.exitCode() == 0,
                "the --dump-ir-only production compile succeeds: "
                    + dumpIr.stderr());
            check(!dumpIr.stderr().contains("source-map"),
                "a --dump-ir-derived flag prints no warning: "
                    + dumpIr.stderr());

            // The LuaJIT target pins the LuaJIT warning text.
            Path luaProject = Files.createTempDirectory(
                "production-dispatch-map-lua-");
            try {
                write(luaProject, "deal.json", DEAL_JSON_LUA);
                write(luaProject, "src/main.deal", """
                    export function main(): null {
                      return null
                    }
                    """);
                ProjectOutcome lua = runProductionCli("compile",
                    luaProject.resolve("src/main.deal").toAbsolutePath()
                        .toString(),
                    "--source-map", "--output",
                    luaProject.resolve("out-map").toAbsolutePath().toString());
                check(lua.exitCode() == 0,
                    "the explicit --source-map LuaJIT compile succeeds: "
                        + lua.stderr());
                checkEq(1, countOccurrences(lua.stderr(),
                        deal.module.ProductionProjectEmission.WARNING_LUAJIT),
                    "the pinned LuaJIT warning prints exactly once");
                check(artifactFiles(luaProject.resolve("out-map"))
                        .contains("main.lua"),
                    "the LuaJIT --source-map compile publishes its project "
                        + "artifact");
            } finally {
                deleteRecursively(luaProject);
            }
        } finally {
            deleteRecursively(project);
        }
    }

    // =========================================================================
    // 6. The single-arm dispatch: every recorded invocation runs the one
    //    production arm; a legacy profile fails closed at the lowering
    // =========================================================================

    private static void testSingleArmDispatch() throws Exception {
        System.out.println("-- dispatch: every recorded invocation runs the one "
            + "production arm; the legacy profile fails closed at the "
            + "lowering --");

        checkEq(3, Backend.values().length,
            "the Backend enum keeps exactly its three closed values");
        check(Backend.fromCliName("luajit").orElseThrow() == Backend.LUAJIT
                && Backend.fromCliName("jvm").orElseThrow() == Backend.JVM
                && Backend.fromCliName("js").orElseThrow() == Backend.JS,
            "the three backend CLI names keep their closed values");

        Path project = Files.createTempDirectory("production-dispatch-arm-");
        try {
            // A multi-module fixture with a cross-module call: the one
            // production arm emits it as the one project artifact.
            write(project, "deal.json", DEAL_JSON_LUA);
            write(project, "src/lib.deal", LIB_SOURCE);
            write(project, "src/main.deal", SYNC_CALL_SOURCE);

            ArmCompile reference = compileWithInvocation(project,
                "src/main.deal", productionInvocation());
            check(reference.success(), "the release-owned compile succeeds: "
                + reference.orchestrator().diagnostics());
            if (!reference.success()) {
                return;
            }
            Map<String, byte[]> referenceSet =
                snapshotTree(project.resolve("out"));
            check(referenceSet.containsKey("main.lua")
                    && !referenceSet.containsKey("lib.lua"),
                "the release-owned compile publishes the one project artifact: "
                    + referenceSet.keySet());

            // A delivery-equivalent record (any purpose, release state, or
            // registry digest carrying the production profile) selects no
            // arm: its artifact set is the release-owned set, byte for
            // byte, and it reports no diagnostic.
            checkDeliveryEquivalentRow(project, "COMMON_SHADOW",
                commonShadow(), referenceSet);
            checkDeliveryEquivalentRow(project,
                "PUBLIC_BUILD + V1_2_ACTIVE + all-SHADOW registry digest",
                publicBuildShadowRegistry(), referenceSet);

            // A legacy-profile record fails closed at the lowering: exactly
            // one E6005 LOWER_LEGACY_PROFILE_REJECTED, nothing staged, and a
            // previously published set stays byte-identical.
            checkLegacyFailClosedRow(project, "LEGACY_REGRESSION",
                legacyRegression());
            checkLegacyFailClosedRow(project, "PUBLIC_BUILD + PRE_ACTIVATION",
                publicBuildPreActivation());
        } finally {
            deleteRecursively(project);
        }

        // A fail-closed input keeps the release-owned diagnostics under a
        // delivery-equivalent record: the E6005 text carries the profile
        // (identical for both records), so code and message match exactly.
        Path fail = Files.createTempDirectory("production-dispatch-diag-");
        try {
            write(fail, "deal.json", DEAL_JSON_LUA);
            write(fail, "src/main.deal", FUNCTION_VALUE_SOURCE);
            ArmCompile releaseFail = compileWithInvocation(fail,
                "src/main.deal", productionInvocation());
            ArmCompile shadowFail = compileWithInvocation(fail,
                "src/main.deal", commonShadow());
            check(!releaseFail.success() && !shadowFail.success(),
                "the fail-closed input fails under both records: "
                    + releaseFail.orchestrator().diagnostics() + " / "
                    + shadowFail.orchestrator().diagnostics());
            checkEq(diagnosticTexts(releaseFail.orchestrator()),
                diagnosticTexts(shadowFail.orchestrator()),
                "the delivery-equivalent record reports the release-owned "
                    + "diagnostics for the fail-closed input");
            check(diagnosticTexts(shadowFail.orchestrator()).stream()
                    .anyMatch(diagnostic -> diagnostic.contains("E6005")
                        && diagnostic.contains("CONSTRUCT_UNLOWERED")),
                "the shared diagnostics name the fail-closed construct: "
                    + diagnosticTexts(shadowFail.orchestrator()));
        } finally {
            deleteRecursively(fail);
        }

        // The surviving harness mirror records its invocation but selects no
        // arm: its result equals the release-owned compile for the same
        // input, artifact for artifact.
        Path mirror = Files.createTempDirectory("production-dispatch-mirror-");
        try {
            write(mirror, "deal.json", DEAL_JSON_LUA);
            write(mirror, "src/lib.deal", LIB_SOURCE);
            write(mirror, "src/main.deal", APP_SOURCE);
            ProjectOutcome harness = runHarnessCli("compile",
                mirror.resolve("src/main.deal").toAbsolutePath().toString(),
                "--output", mirror.resolve("out-harness").toAbsolutePath()
                    .toString());
            ProjectOutcome release = productionCompile(mirror, "src/main.deal",
                "out-release");
            check(harness.exitCode() == 0 && release.exitCode() == 0,
                "the harness mirror and the release-owned compile both "
                    + "succeed: harness=" + harness.exitCode() + " release="
                    + release.exitCode() + " stderr=" + harness.stderr());
            check(treeEquals(snapshotTree(mirror.resolve("out-harness")),
                    snapshotTree(mirror.resolve("out-release"))),
                "the harness mirror publishes the release-owned artifact set "
                    + "byte-for-byte");
        } finally {
            deleteRecursively(mirror);
        }
    }

    private static void checkDeliveryEquivalentRow(Path project, String name,
            CompilerInvocation invocation, Map<String, byte[]> referenceSet)
            throws Exception {
        deleteRecursively(project.resolve("out"));
        ArmCompile arm = compileWithInvocation(project, "src/main.deal",
            invocation);
        check(arm.success(), name + ": the compile succeeds: "
            + arm.orchestrator().diagnostics());
        if (!arm.success()) {
            return;
        }
        check(!CompilationOrchestrator.isProductionInvocation(invocation),
            name + ": the record is not the release-owned production "
                + "invocation");
        checkEq(1, arm.orchestrator().semanticEmissionCount(),
            name + ": the one production arm records one project emission");
        check(arm.orchestrator().diagnostics().isEmpty(),
            name + ": the compile reports no diagnostic: "
                + arm.orchestrator().diagnostics());
        check(treeEquals(referenceSet, snapshotTree(project.resolve("out"))),
            name + ": the artifact set is the release-owned artifact set "
                + "byte-for-byte");
    }

    private static void checkLegacyFailClosedRow(Path project, String name,
            CompilerInvocation invocation) throws Exception {
        Path out = project.resolve("out");
        deleteRecursively(out);
        write(project, "out/main.lua", "-- previous artifact\n");
        write(project, "out/deal/runtime.lua", "-- previous runtime\n");
        Map<String, byte[]> before = snapshotTree(out);

        ArmCompile arm = compileWithInvocation(project, "src/main.deal",
            invocation);
        check(!arm.success(),
            name + ": the legacy-profile invocation fails closed");
        List<CompilerDiagnostic> errors = arm.orchestrator().diagnostics()
            .stream().filter(d -> "error".equals(d.severity())).toList();
        checkEq(1, errors.size(),
            name + ": exactly one error diagnostic: "
                + arm.orchestrator().diagnostics());
        if (errors.size() == 1) {
            check(errors.get(0).code().equals("E6005")
                    && errors.get(0).message().contains(
                        SemanticLowerer.LOWER_LEGACY_PROFILE_REJECTED),
                name + ": the one failure is E6005 "
                    + SemanticLowerer.LOWER_LEGACY_PROFILE_REJECTED + ": "
                    + errors.get(0).message());
        }
        checkEq(0, arm.orchestrator().semanticEmissionCount(),
            name + ": the fail-closed lowering records no project emission");
        checkTreeIdentical(before, out,
            name + ": the fail-closed compile stages nothing");
    }

    private static List<String> diagnosticTexts(
            CompilationOrchestrator orchestrator) {
        return orchestrator.diagnostics().stream()
            .map(d -> d.code() + "|" + d.severity() + "|" + d.message())
            .toList();
    }

    /**
     * The dumps-enabled legacy rejection (both targets): the accepted
     * compile stages its generated IR dump with the artifact set after
     * the one production arm accepted it, while the legacy-profile
     * rejection stages nothing at all — no staging transaction starts,
     * so a staging fault installed at staging start cannot preempt the
     * one E6005 LOWER_LEGACY_PROFILE_REJECTED.
     */
    private static void testLegacyRejectionStagesNothingWithDumps()
            throws Exception {
        System.out.println("-- legacy-profile rejection with --dump-ir: the "
            + "accepted dump stages after acceptance; the rejection stages "
            + "nothing and no staging fault can preempt it --");

        for (String backend : List.of("luajit", "jvm")) {
            Path project = Files.createTempDirectory(
                "production-dispatch-dump-");
            try {
                write(project, "deal.json",
                    "jvm".equals(backend) ? DEAL_JSON_JVM : DEAL_JSON_LUA);
                write(project, "src/main.deal",
                    "export function main(): null { return null; }\n");
                Path out = project.resolve("out");

                // Accepted control: the release-owned compile with
                // --dump-ir generates the dump in its existing phase and
                // stages it in the accepted compile's one transaction.
                int[] stagingStarts = {0};
                PublicationStager.installPublishFault(step -> {
                    if (PublicationStager.FAULT_STEP_STAGING_BEGAN.equals(
                            step)) {
                        stagingStarts[0]++;
                    }
                });
                ArmCompile accepted;
                try {
                    accepted = compileWithInvocation(project, "src/main.deal",
                        productionInvocation(), true);
                } finally {
                    PublicationStager.clearPublishFault();
                }
                check(accepted.success(), backend
                    + ": the dumps-enabled release-owned compile succeeds: "
                    + accepted.orchestrator().diagnostics());
                checkEq(1, stagingStarts[0], backend
                    + ": the accepted compile stages exactly one staging "
                    + "transaction (dump and artifact)");
                check(artifactFiles(out).contains("main.ir.txt"), backend
                    + ": the accepted compile publishes the generated IR "
                    + "dump: " + artifactFiles(out));

                // The legacy rejection with --dump-ir: exactly one E6005,
                // zero staging starts (the pending dump is discarded), and
                // the prior live set byte-identical. The staging fault
                // installed at staging start never fires, so it cannot
                // preempt the legacy diagnostic with a publish I/O error.
                Map<String, byte[]> before = snapshotTree(out);
                stagingStarts[0] = 0;
                PublicationStager.installPublishFault(step -> {
                    if (PublicationStager.FAULT_STEP_STAGING_BEGAN.equals(
                            step)) {
                        stagingStarts[0]++;
                        throw new IOException(
                            "injected staging failure (legacy probe)");
                    }
                });
                ArmCompile rejected;
                try {
                    rejected = compileWithInvocation(project, "src/main.deal",
                        legacyRegression(), true);
                } finally {
                    PublicationStager.clearPublishFault();
                }
                check(!rejected.success(), backend
                    + ": the dumps-enabled legacy-profile invocation fails "
                    + "closed");
                List<CompilerDiagnostic> errors =
                    rejected.orchestrator().diagnostics().stream()
                        .filter(d -> "error".equals(d.severity())).toList();
                checkEq(1, errors.size(), backend
                    + ": exactly one error diagnostic (the staging fault "
                    + "cannot preempt it): "
                    + rejected.orchestrator().diagnostics());
                if (errors.size() == 1) {
                    check(errors.get(0).code().equals("E6005")
                            && errors.get(0).message().contains(
                                SemanticLowerer.LOWER_LEGACY_PROFILE_REJECTED),
                        backend + ": the one failure is E6005 "
                            + SemanticLowerer.LOWER_LEGACY_PROFILE_REJECTED
                            + ": " + errors.get(0).message());
                }
                checkEq(0, rejected.orchestrator().semanticEmissionCount(),
                    backend + ": the fail-closed lowering records no project "
                        + "emission");
                checkEq(0, stagingStarts[0], backend
                    + ": the rejected compile starts no staging transaction");
                checkTreeIdentical(before, out, backend
                    + ": the rejected compile leaves the prior artifact set "
                    + "byte-identical");
                checkEq(List.of(), stageResidue(out), backend
                    + ": the rejected compile leaves no stage or retired "
                    + "residue");
            } finally {
                deleteRecursively(project);
            }
        }
    }

    /** The stage/retired siblings beside one live output root. */
    private static List<String> stageResidue(Path root) throws Exception {
        Path normalized = root.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return List.of();
        }
        String name = normalized.getFileName().toString();
        String stagePrefix = name + PublicationStager.STAGE_TREE_MARKER;
        String retiredPrefix = name + PublicationStager.RETIRED_TREE_MARKER;
        try (Stream<Path> entries = Files.list(parent)) {
            return entries.map(path -> path.getFileName().toString())
                .filter(entry -> entry.startsWith(stagePrefix)
                    || entry.startsWith(retiredPrefix))
                .sorted().toList();
        }
    }

    private static boolean treeEquals(Map<String, byte[]> left,
            Map<String, byte[]> right) {
        if (!left.keySet().equals(right.keySet())) {
            return false;
        }
        for (Map.Entry<String, byte[]> entry : left.entrySet()) {
            if (!Arrays.equals(entry.getValue(), right.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    // =========================================================================
    // 7. The still-live test-scope retained emitters: direct emission from
    //    test/legacy, never a production compile route
    // =========================================================================

    /**
     * The retained AST-walking emitters under {@code test/legacy} stay
     * test-scope only (test/AGENTS.md) and still live: a test-scope
     * subject calls each one directly — the direct-emission contract
     * ISSUE-0693 records for a retained-emitter-only subject (a retained
     * compile route is never restored to keep a test green).
     *
     * <p>The emitted artifacts are the retained emitters' own: the LuaJIT
     * chunk publishes and invokes the entry main and runs under the real
     * interpreter with the deployed runtime; the JVM entry class carries
     * the shared naming surface's derivation, compiles under
     * {@code javac --release 25 -proc:none}, and runs. The release-owned
     * production dispatch reaches neither emitter (the one-arm checks
     * above).</p>
     */
    private static void testRetainedTestScopeEmitters() throws Exception {
        System.out.println("-- the still-live test-scope retained emitters: "
            + "direct emission, no production compile route --");

        String source = """
            export function main(): null {
              return null
            }
            """;
        LexResult lex = new Lexer(source, "main.deal").tokenize();
        check(lex.diagnostics().isEmpty(), "the retained-emitter probe lexes "
            + "cleanly: " + lex.diagnostics());
        if (lex.hasErrors()) { return; }
        ParseResult parse = new Parser(lex.tokens(), "main.deal",
            lex.directiveEvents()).parse();
        check(parse.diagnostics().isEmpty(), "the retained-emitter probe "
            + "parses cleanly: " + parse.diagnostics());
        if (parse.hasErrors()) { return; }
        NameResolver resolver = new NameResolver("main.deal", null);
        SymbolTable symbols = resolver.resolve(parse.program());
        check(resolver.diagnostics().isEmpty(), "the retained-emitter probe "
            + "resolves cleanly: " + resolver.diagnostics());
        CheckResult checks = TypeChecker.check("main.deal", symbols, resolver,
            parse.program());
        check(!checks.hasErrors(), "the retained-emitter probe checks cleanly: "
            + checks.diagnostics());
        if (checks.hasErrors()) { return; }

        // The retained LuaJIT emitter (test/legacy/deal/codegen/lua/LuaBackend):
        // the direct test-scope call emits the one module chunk, whose entry
        // invokes main exactly once, and the chunk runs under real luajit with
        // the deployed runtime.
        deal.codegen.lua.LuaBackend lua = new deal.codegen.lua.LuaBackend(
            checks.typeMap(), symbols, "main");
        String chunk = lua.generateFromInstance(parse.program(), true);
        check(lua.diagnostics().isEmpty(), "the retained LuaJIT emitter "
            + "reports no diagnostic: " + lua.diagnostics());
        check(chunk != null && chunk.contains("exports.main.f()"),
            "the retained LuaJIT entry chunk publishes and invokes the entry "
                + "main");
        Path luaOut = Files.createTempDirectory(
            "production-dispatch-retained-lua-");
        try {
            write(luaOut, "deal/runtime.lua",
                Files.readString(Path.of("deal", "runtime.lua")));
            write(luaOut, "main.lua", chunk);
            ProcessOutcome run = runProcess(luaOut, "luajit", "main.lua");
            checkEq(0, run.exitCode(), "the retained LuaJIT chunk runs under "
                + "real luajit: " + run.output());
            checkEq("", run.output(), "the retained LuaJIT chunk runs "
                + "silently: " + run.output());
        } finally {
            deleteRecursively(luaOut);
        }

        // The retained JVM emitter (test/legacy/deal/codegen/jvm/JvmBackend):
        // the direct static call emits the one entry class through the shared
        // naming surface the production emitters resolve; the class compiles
        // under javac --release 25 -proc:none and runs.
        JvmBackend.JvmCodegenResult jvm = JvmBackend.generate(parse.program(),
            checks, "main");
        check(!jvm.hasErrors(), "the retained JVM emitter reports no error: "
            + jvm.diagnostics());
        checkEq(JvmNames.classNameFor("main"), jvm.className(),
            "the retained JVM emitter derives the shared naming surface's "
                + "entry class name");
        check(jvm.source().contains("public final class " + jvm.className()),
            "the retained JVM artifact declares its entry class");
        Path jvmOut = Files.createTempDirectory(
            "production-dispatch-retained-jvm-");
        try {
            Path jvmSource = jvmOut.resolve(jvm.className() + ".java");
            Files.writeString(jvmSource, jvm.source(), StandardCharsets.UTF_8);
            ProcessOutcome javac = runProcess(jvmOut, "javac", "--release",
                "25", "-proc:none", "-d", jvmOut.toString(),
                jvmSource.toString());
            checkEq(0, javac.exitCode(), "the retained JVM artifact compiles "
                + "under javac --release 25 -proc:none: " + javac.output());
            if (javac.exitCode() == 0) {
                ProcessOutcome run = runProcess(jvmOut, "java", "-cp",
                    jvmOut.toString(), jvm.className());
                checkEq(0, run.exitCode(), "the retained JVM artifact runs: "
                    + run.output());
            }
        } finally {
            deleteRecursively(jvmOut);
        }
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Production Dispatch and Cutover Acceptance "
            + "Tests (ISSUE-0643) ===\n");

        testInvocationPredicate();
        testProductionLuaJitSingleModule();
        testProductionJvmTwoModule();
        testProductionLuaJitTwoModuleSurfaces();
        testConversionOverflowTerminal();
        testAtomicFailureThroughDispatch();
        testFailClosedFamilies();
        testSourceMapDisposition();
        testSingleArmDispatch();
        testLegacyRejectionStagesNothingWithDumps();
        testRetainedTestScopeEmitters();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
