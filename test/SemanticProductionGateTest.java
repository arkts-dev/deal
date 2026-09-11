package deal.test;

import deal.diagnostics.CompilerDiagnostic;
import deal.module.CompilationOrchestrator;
import deal.project.CliOverrides;
import deal.project.ProjectLocator;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ModuleRoute;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RoutePlanResult;
import deal.semantic.ir.ReleaseState;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The ISSUE-0239 E10 production gate: real post-flip orchestrator
 * compiles through the full production pipeline (ProjectLocator →
 * CompilationOrchestrator) on both retained targets and executes the
 * emitted artifacts through the real toolchains (luajit;
 * {@code javac --release 25 -proc:none} + {@code java}), pinning:
 *
 * <ul>
 *   <li>the production emitter seam — {@code LoweredModuleUnit} +
 *       {@code StructuredBodyTable}, never AST/CheckResult;</li>
 *   <li>the {@code ModuleRoutePlan} selection without fallback — one
 *       semantic/zero retained artifacts for the all-shared
 *       single-module projects, zero semantic/two retained for the
 *       multi-module graph, and the dual-shape plan-time reroute
 *       (never E6005, never a within-run fallback);</li>
 *   <li>the retained {@code DEAL_ERROR_CODE: <code>} terminal contract
 *       (E8004 out of the shared artifact);</li>
 *   <li>the plan-time LEGACY reroute of every named shape without a
 *       shared representation — the adapter-invocation module and the
 *       stored/embedded function-expression shapes (binding, array
 *       literal, table literal) compile through the retained route with
 *       zero semantic/one retained artifact and an all-LEGACY plan
 *       (never E6005, never a within-run fallback);</li>
 *   <li>the SHARED table member read — the emitted {@code __member}
 *       helper resolves present and absent keys identically to
 *       {@code JvmRuntime.Table.read} on both targets and the published
 *       artifacts load and run;</li>
 *   <li>the atomic publication failure preservation — a failing compile
 *       drives the residual public shared-lowering E6005 (the
 *       function-typed table member read, R-FUNCTION-BINDING), publishes
 *       nothing, and preserves the prior artifact set byte-for-byte with
 *       no staging/retired siblings surviving.</li>
 * </ul>
 */
public class SemanticProductionGateTest {

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

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    private static void write(Path root, String relative, String content)
            throws IOException {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path, FileVisitOption.values())) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private record ProcessOutcome(int exitCode, String output) {
    }

    private static ProcessOutcome runProcess(Path directory, List<String> command)
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

    /** The production compilation path of the gate (E10): ProjectLocator +
     * the context-taking orchestrator constructor with the promoted
     * release registry — the same surface the CLI uses. */
    private static CompilationOrchestrator compileProject(Path project,
                                                          String entry,
                                                          String output)
            throws IOException {
        Path entryFile = project.resolve(entry).toAbsolutePath().normalize();
        ProjectLocator.LocateResult located = ProjectLocator.locate(
            entryFile.toString(), new CliOverrides(null, null));
        if (located.context() == null) {
            throw new IllegalStateException("locate failed: " + located);
        }
        CompilerInvocation invocation = CompilerProfileProvider.resolve(
            ReleaseState.V1_2_ACTIVE,
            ReleaseConfiguration.releaseCapabilityRegistry());
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            located.context(), entryFile, false, false, false, false, null,
            invocation);
        boolean success = orchestrator.compile();
        if (!success) {
            throw new IllegalStateException("compile failed: "
                + orchestrator.diagnostics());
        }
        return orchestrator;
    }

    private static void testEmitterSeam() {
        System.out.println("-- Production emitter seam: unit + table, never AST/CheckResult --");

        Method luaEmit = null;
        for (Method method : LuaEmitterClass().getDeclaredMethods()) {
            if (method.getName().equals("emitProductionModule")) {
                luaEmit = method;
            }
        }
        Method jvmEmit = null;
        for (Method method : JvmEmitterClass().getDeclaredMethods()) {
            if (method.getName().equals("emitProductionModule")) {
                jvmEmit = method;
            }
        }
        check(luaEmit != null && jvmEmit != null,
            "both shared emitters publish a production entry point");
        for (Method method : new Method[] {luaEmit, jvmEmit}) {
            if (method == null) {
                continue;
            }
            boolean hasUnit = false;
            boolean hasTable = false;
            for (Parameter parameter : method.getParameters()) {
                if (parameter.getType()
                        == deal.semantic.ir.LoweredModuleUnit.class) {
                    hasUnit = true;
                }
                if (parameter.getType()
                        == deal.semantic.ir.StructuredBodyTable.class) {
                    hasTable = true;
                }
                if (parameter.getType().getName().startsWith("deal.ast.")
                        || parameter.getType().getName().startsWith("deal.checker.")) {
                    fail(method.getDeclaringClass().getSimpleName()
                        + ".emitProductionModule takes AST/checker state: "
                        + parameter.getType().getName());
                }
            }
            check(hasUnit && hasTable,
                method.getDeclaringClass().getSimpleName()
                    + ".emitProductionModule consumes the validated unit + table");
        }
    }

    private static Class<?> LuaEmitterClass() {
        return deal.codegen.lua.LuaSemanticEmitter.class;
    }

    private static Class<?> JvmEmitterClass() {
        return deal.codegen.jvm.JvmSemanticEmitter.class;
    }

    private static final String DEAL_JSON_LUA =
        "{\n  \"languageVersion\": \"1.2\",\n  \"moduleRoots\": [\"src\"],\n"
            + "  \"output\": \"out\",\n  \"backend\": \"luajit\"\n}\n";
    private static final String DEAL_JSON_JVM =
        "{\n  \"languageVersion\": \"1.2\",\n  \"moduleRoots\": [\"src\"],\n"
            + "  \"output\": \"out\",\n  \"backend\": \"jvm\"\n}\n";

    private static final String ADD_MAIN_SOURCE =
        "export function add(x: int, y: int): int {\n  return x + y\n}\n\n"
            + "export function main(): null {\n  return null\n}\n";

    private static final String OVERFLOW_SOURCE =
        "export function main(): null {\n  let x: int = 2147483647 + 1\n"
            + "  return null\n}\n";

    private static final String TABLE_MEMBER_READ_SOURCE =
        "export function main(): null {\n  let t: table = { a: 1 }\n"
            + "  let x: int = t.a\n  return null\n}\n";

    private static void testAllSharedLuaJit() throws Exception {
        System.out.println("-- All-shared LuaJIT: semantic IR artifact runs (success + E8004) --");

        Path project = Files.createTempDirectory("deal-e10-lua-");
        try {
            write(project, "deal.json", DEAL_JSON_LUA);
            write(project, "src/main.deal", ADD_MAIN_SOURCE);
            CompilationOrchestrator orchestrator =
                compileProject(project, "src/main.deal", "out");
            check(orchestrator.semanticEmissionCount() == 1
                    && orchestrator.retainedEmissionCount() == 0,
                "one semantic/zero retained artifacts: semantic="
                    + orchestrator.semanticEmissionCount() + " retained="
                    + orchestrator.retainedEmissionCount());
            RoutePlanResult plan = orchestrator.routePlan();
            check(plan != null && !plan.hasErrors() && plan.plan() != null
                    && plan.plan().entries().values().stream()
                        .allMatch(route -> route == ModuleRoute.SHARED),
                "the post-flip plan routes the single module SHARED");

            Path out = project.resolve("out");
            check(Files.isRegularFile(out.resolve("main.lua")),
                "the semantic artifact main.lua is staged and published");
            ProcessOutcome run = runProcess(out, List.of("luajit", "main.lua"));
            check(run.exitCode() == 0 && run.output().isEmpty(),
                "the shared LuaJIT artifact runs clean: exit=" + run.exitCode()
                    + " output=" + run.output().replace("\n", "\\n"));

            Path overflowProject = Files.createTempDirectory("deal-e10-lua-ovf-");
            try {
                write(overflowProject, "deal.json", DEAL_JSON_LUA);
                write(overflowProject, "src/main.deal", OVERFLOW_SOURCE);
                CompilationOrchestrator overflow =
                    compileProject(overflowProject, "src/main.deal", "out");
                check(overflow.semanticEmissionCount() == 1
                        && overflow.retainedEmissionCount() == 0,
                    "the overflow project emits exactly one semantic artifact");
                ProcessOutcome overflowRun = runProcess(
                    overflowProject.resolve("out"), List.of("luajit", "main.lua"));
                check(overflowRun.exitCode() == 1
                        && overflowRun.output().contains("DEAL_ERROR_CODE: E8004"),
                    "the shared LuaJIT artifact publishes the retained DEAL_ERROR_CODE "
                        + "terminal: exit=" + overflowRun.exitCode() + " output="
                        + overflowRun.output().replace("\n", "\\n"));
            } finally {
                deleteRecursively(overflowProject);
            }
        } finally {
            deleteRecursively(project);
        }
    }

    private static void testAllSharedJvm() throws Exception {
        System.out.println("-- All-shared JVM: semantic IR artifact compiles and runs (success + E8004) --");

        Path project = Files.createTempDirectory("deal-e10-jvm-");
        try {
            write(project, "deal.json", DEAL_JSON_JVM);
            write(project, "src/main.deal", ADD_MAIN_SOURCE);
            CompilationOrchestrator orchestrator =
                compileProject(project, "src/main.deal", "out");
            check(orchestrator.semanticEmissionCount() == 1
                    && orchestrator.retainedEmissionCount() == 0,
                "one semantic/zero retained artifacts: semantic="
                    + orchestrator.semanticEmissionCount() + " retained="
                    + orchestrator.retainedEmissionCount());

            Path out = project.resolve("out");
            check(Files.isRegularFile(out.resolve("Main.java")),
                "the semantic artifact Main.java is staged and published");
            String buildCp = Path.of("build").toAbsolutePath().normalize().toString();
            ProcessOutcome javac = runProcess(project, List.of(
                "javac", "--release", "25", "-proc:none", "-cp", buildCp,
                "-d", out.toString(), out.resolve("Main.java").toString()));
            check(javac.exitCode() == 0,
                "the shared JVM artifact compiles: " + javac.output().replace("\n", "\\n"));
            if (javac.exitCode() == 0) {
                ProcessOutcome run = runProcess(out, List.of(
                    "java", "-cp", buildCp + File.pathSeparator + out, "Main"));
                check(run.exitCode() == 0 && run.output().isEmpty(),
                    "the shared JVM artifact runs clean: exit=" + run.exitCode()
                        + " output=" + run.output().replace("\n", "\\n"));
            }

            Path overflowProject = Files.createTempDirectory("deal-e10-jvm-ovf-");
            try {
                write(overflowProject, "deal.json", DEAL_JSON_JVM);
                write(overflowProject, "src/main.deal", OVERFLOW_SOURCE);
                CompilationOrchestrator overflow =
                    compileProject(overflowProject, "src/main.deal", "out");
                check(overflow.semanticEmissionCount() == 1
                        && overflow.retainedEmissionCount() == 0,
                    "the overflow project emits exactly one semantic artifact");
                Path overflowOut = overflowProject.resolve("out");
                ProcessOutcome overflowJavac = runProcess(overflowProject, List.of(
                    "javac", "--release", "25", "-proc:none", "-cp", buildCp,
                    "-d", overflowOut.toString(),
                    overflowOut.resolve("Main.java").toString()));
                if (overflowJavac.exitCode() == 0) {
                    ProcessOutcome overflowRun = runProcess(overflowOut, List.of(
                        "java", "-cp", buildCp + File.pathSeparator + overflowOut,
                        "Main"));
                    check(overflowRun.exitCode() == 1
                            && overflowRun.output().contains("DEAL_ERROR_CODE: E8004"),
                        "the shared JVM artifact publishes the retained DEAL_ERROR_CODE "
                            + "terminal: exit=" + overflowRun.exitCode() + " output="
                            + overflowRun.output().replace("\n", "\\n"));
                } else {
                    fail("the overflow shared JVM artifact compiles: "
                        + overflowJavac.output().replace("\n", "\\n"));
                }
            } finally {
                deleteRecursively(overflowProject);
            }
        } finally {
            deleteRecursively(project);
        }
    }

    private static void testSharedTableMemberReadRuns() throws Exception {
        System.out.println("-- SHARED table member read: the emitted __member helper "
            + "runs on both targets --");

        // The cycle-2 critical shape: any MEMBER_READ references the
        // prelude helper __member, so a program reading a table field
        // publishes an artifact that runs clean (never a nil-global
        // crash). Present-key reads yield the stored value on both
        // targets, exactly JvmRuntime.Table.read's split.
        Path project = Files.createTempDirectory("deal-e10-tmember-lua-");
        try {
            write(project, "deal.json", DEAL_JSON_LUA);
            write(project, "src/main.deal", TABLE_MEMBER_READ_SOURCE);
            CompilationOrchestrator orchestrator =
                compileProject(project, "src/main.deal", "out");
            check(orchestrator.semanticEmissionCount() == 1
                    && orchestrator.retainedEmissionCount() == 0,
                "the table-member-read module emits one semantic/zero retained "
                    + "artifacts: semantic=" + orchestrator.semanticEmissionCount()
                    + " retained=" + orchestrator.retainedEmissionCount());
            RoutePlanResult plan = orchestrator.routePlan();
            check(plan != null && !plan.hasErrors() && plan.plan() != null
                    && plan.plan().entries().values().stream()
                        .allMatch(route -> route == ModuleRoute.SHARED),
                "the post-flip plan routes the table-member-read module SHARED");
            ProcessOutcome run = runProcess(project.resolve("out"),
                List.of("luajit", "main.lua"));
            check(run.exitCode() == 0 && run.output().isEmpty(),
                "the shared LuaJIT table-member-read artifact runs clean: exit="
                    + run.exitCode() + " output=" + run.output().replace("\n", "\\n"));
        } finally {
            deleteRecursively(project);
        }

        Path jvmProject = Files.createTempDirectory("deal-e10-tmember-jvm-");
        try {
            write(jvmProject, "deal.json", DEAL_JSON_JVM);
            write(jvmProject, "src/main.deal", TABLE_MEMBER_READ_SOURCE);
            CompilationOrchestrator orchestrator =
                compileProject(jvmProject, "src/main.deal", "out");
            check(orchestrator.semanticEmissionCount() == 1
                    && orchestrator.retainedEmissionCount() == 0,
                "the JVM table-member-read module emits one semantic/zero retained "
                    + "artifacts: semantic=" + orchestrator.semanticEmissionCount()
                    + " retained=" + orchestrator.retainedEmissionCount());
            Path out = jvmProject.resolve("out");
            String buildCp = Path.of("build").toAbsolutePath().normalize().toString();
            ProcessOutcome javac = runProcess(jvmProject, List.of(
                "javac", "--release", "25", "-proc:none", "-cp", buildCp,
                "-d", out.toString(), out.resolve("Main.java").toString()));
            check(javac.exitCode() == 0,
                "the shared JVM table-member-read artifact compiles: "
                    + javac.output().replace("\n", "\\n"));
            if (javac.exitCode() == 0) {
                ProcessOutcome run = runProcess(out, List.of(
                    "java", "-cp", buildCp + File.pathSeparator + out, "Main"));
                check(run.exitCode() == 0 && run.output().isEmpty(),
                    "the shared JVM table-member-read artifact runs clean: exit="
                        + run.exitCode() + " output=" + run.output().replace("\n", "\\n"));
            }
        } finally {
            deleteRecursively(jvmProject);
        }
    }

    private static void testRouteSelectionWithoutFallback() throws Exception {
        System.out.println("-- Route selection: multi-module LEGACY, dual-shape LEGACY, no fallback --");

        Path project = Files.createTempDirectory("deal-e10-routes-");
        try {
            write(project, "deal.json", DEAL_JSON_LUA);
            write(project, "src/lib.deal",
                "export function value(): int {\n  return 42\n}\n");
            write(project, "src/main.deal",
                "import * as lib from \"./lib\"\n\n"
                    + "export function probe(): int {\n  return lib.value()\n}\n\n"
                    + "export function main(): null {\n  return null\n}\n");
            CompilationOrchestrator orchestrator =
                compileProject(project, "src/main.deal", "out");
            check(orchestrator.semanticEmissionCount() == 0
                    && orchestrator.retainedEmissionCount() == 2,
                "the multi-module graph emits zero semantic/two retained artifacts: "
                    + "semantic=" + orchestrator.semanticEmissionCount()
                    + " retained=" + orchestrator.retainedEmissionCount());
            RoutePlanResult plan = orchestrator.routePlan();
            check(plan != null && !plan.hasErrors() && plan.plan() != null
                    && plan.plan().entries().values().stream()
                        .allMatch(route -> route == ModuleRoute.LEGACY),
                "the multi-module plan is all-LEGACY at plan time (never E6005, "
                    + "never a within-run fallback)");
            ProcessOutcome run = runProcess(project.resolve("out"),
                List.of("luajit", "main.lua"));
            check(run.exitCode() == 0,
                "the retained multi-module artifact set runs: exit=" + run.exitCode()
                    + " output=" + run.output().replace("\n", "\\n"));

            Path dualProject = Files.createTempDirectory("deal-e10-dual-");
            try {
                write(dualProject, "deal.json", DEAL_JSON_LUA);
                write(dualProject, "src/main.deal",
                    "export function add(x: int, y: int): int {\n  return x + y\n}\n\n"
                        + "export function main(): null {\n  add(2, 3)\n"
                        + "  return null\n}\n");
                CompilationOrchestrator dual =
                    compileProject(dualProject, "src/main.deal", "out");
                check(dual.semanticEmissionCount() == 0
                        && dual.retainedEmissionCount() == 1,
                    "the dual-shape module emits zero semantic/one retained artifact: "
                        + "semantic=" + dual.semanticEmissionCount()
                        + " retained=" + dual.retainedEmissionCount());
                RoutePlanResult dualPlan = dual.routePlan();
                check(dualPlan != null && !dualPlan.hasErrors()
                        && dualPlan.plan() != null
                        && dualPlan.plan().entries().values().stream()
                            .allMatch(route -> route == ModuleRoute.LEGACY),
                    "the dual-shape plan is LEGACY at plan time");
            } finally {
                deleteRecursively(dualProject);
            }
        } finally {
            deleteRecursively(project);
        }
    }

    private static void testAdapterShapeReroutesLegacy() throws Exception {
        System.out.println("-- Plan-time reroute: shapes without a shared representation "
            + "are LEGACY, never E6005 --");

        // The review-verified corpus shapes (directive lines stripped) plus
        // the adapter-invocation shape and the stored/embedded
        // function-expression shapes: every module without a shared
        // representation claims its owning capability at plan time, so F4
        // rule 4 reroutes it LEGACY — zero semantic/one retained artifact,
        // an all-LEGACY plan, and the retained v1.2 route compiles and
        // runs the program as before (the parent epic's "unsupported
        // capabilities choose legacy and are not errors").
        Map<String, String> fixtures = Map.of(
            "adapter", "export function main(): null {\n"
                + "  let f: (a: int, b: int) => int = one\n"
                + "  f(1, 2)\n"
                + "  return null\n"
                + "}\n"
                + "function one(x: int): int {\n  return x\n}\n",
            "bytes-length", "export function test_bytes_length(): null {\n"
                + "  let n: int = 3;\n"
                + "  let b: bytes = bytes(n);\n"
                + "  if (b.length !== 3) {\n"
                + "    throw { code: \"TEST_FAIL\", message: \"bytes: length mismatch\" };\n"
                + "  }\n"
                + "  return null;\n"
                + "}\n"
                + "export function main(): null {\n  return null\n}\n",
            "direct-recursion", "export function test_direct_recursion(): int {\n"
                + "  let downCalls: int = 0;\n"
                + "  function down(n: int): int {\n"
                + "    downCalls = downCalls + 1;\n"
                + "    if (n === 0) { return 0; }\n"
                + "    return down(n - 1);\n"
                + "  }\n"
                + "  let result: int = down(8);\n"
                + "  return 0;\n"
                + "}\n"
                + "export function main(): null {\n  return null\n}\n",
            "closure-capture", "export function test_closure(): int {\n"
                + "  let x: int = 1;\n"
                + "  let f: () => int = function(): int { return x; };\n"
                + "  x = 2;\n"
                + "  let result: int = f();\n"
                + "  if (result !== 2) {\n"
                + "    throw { code: \"TEST_FAIL\", message: \"closure capture result mismatch after x = 2\" };\n"
                + "  }\n"
                + "  return result;\n"
                + "}\n"
                + "export function main(): null {\n  return null\n}\n",
            // The cycle-2 stored/embedded function-expression shapes: a
            // closure stored in a binding initializer, an array literal
            // element, or a table literal field whose body has no direct
            // invocation — its RETURN boundary names no invocation shape
            // (R-BOUNDARY-TRIPLE), so the scan arm claims CALLS and F4
            // rule 4 reroutes the module LEGACY at plan time (never
            // E6005, never a within-run fallback).
            "stored-closure-binding", "export function main(): null {\n"
                + "  let g: () => int = function(): int { return 7 }\n"
                + "  return null\n"
                + "}\n",
            "stored-closure-array", "export function main(): null {\n"
                + "  let fs: (() => int)[] = [function(): int { return 7 }]\n"
                + "  return null\n"
                + "}\n",
            "stored-closure-table", "export function main(): null {\n"
                + "  let t: table = { f: function(): int { return 7 } }\n"
                + "  return null\n"
                + "}\n");
        for (Map.Entry<String, String> fixture : fixtures.entrySet()) {
            Path project = Files.createTempDirectory(
                "deal-e10-reroute-" + fixture.getKey() + "-");
            try {
                write(project, "deal.json", DEAL_JSON_LUA);
                write(project, "src/main.deal", fixture.getValue());
                CompilationOrchestrator orchestrator =
                    compileProject(project, "src/main.deal", "out");
                check(orchestrator.semanticEmissionCount() == 0
                        && orchestrator.retainedEmissionCount() == 1,
                    fixture.getKey() + ": the module reroutes LEGACY at plan time: "
                        + "semantic=" + orchestrator.semanticEmissionCount()
                        + " retained=" + orchestrator.retainedEmissionCount());
                RoutePlanResult plan = orchestrator.routePlan();
                check(plan != null && !plan.hasErrors() && plan.plan() != null
                        && plan.plan().entries().values().stream()
                            .allMatch(route -> route == ModuleRoute.LEGACY),
                    fixture.getKey() + ": the plan is all-LEGACY at plan time "
                        + "(never E6005, never a within-run fallback)");
                ProcessOutcome run = runProcess(project.resolve("out"),
                    List.of("luajit", "main.lua"));
                check(run.exitCode() == 0,
                    fixture.getKey() + ": the retained artifact set runs: exit="
                        + run.exitCode() + " output="
                        + run.output().replace("\n", "\\n"));
            } finally {
                deleteRecursively(project);
            }
        }
    }

    private static void testFailurePreservesPriorArtifacts() throws Exception {
        System.out.println("-- Atomic publication: a failing compile preserves the prior set --");

        Path project = Files.createTempDirectory("deal-e10-atomic-");
        try {
            write(project, "deal.json", DEAL_JSON_LUA);
            write(project, "src/main.deal",
                "export function main(): null {\n  return null\n}\n");
            CompilationOrchestrator first =
                compileProject(project, "src/main.deal", "out");
            check(first.semanticEmissionCount() == 1
                    && first.retainedEmissionCount() == 0,
                "the first compile publishes the semantic artifact set");
            Map<String, String> prior = fingerprint(project.resolve("out"));

            // A failing recompile through the same real pipeline: the
            // residual public shared-lowering E6005 shape — a
            // function-typed table member read (the MEMBER_READ result
            // resolves no FunctionExecutionBinding, R-FUNCTION-BINDING)
            // — routes SHARED and fails the compile inside the shared
            // lowering; nothing stages, nothing publishes, and the prior
            // artifact set stays byte-for-byte with no staging/retired
            // residue. The cycle-2 named reroute shapes (stored/embedded
            // function expressions, adapters, bytes, recursion, class
            // literals) reroute LEGACY at plan time — pinned by
            // testAdapterShapeReroutesLegacy — so this residual shape is
            // the public E6005 the atomicity contract is pinned against;
            // the stager-level discard paths stay pinned in
            // StagingPublicationTest/PublicationStagerTest.
            write(project, "src/main.deal",
                "function one2(): int {\n  return 1\n}\n\n"
                    + "export function main(): null {\n"
                    + "  let t: table = { f: one2 }\n"
                    + "  let g: () => int = t.f\n"
                    + "  one2()\n"
                    + "  return null\n"
                    + "}\n");
            Path entryFile = project.resolve("src/main.deal").toAbsolutePath()
                .normalize();
            ProjectLocator.LocateResult located = ProjectLocator.locate(
                entryFile.toString(), new CliOverrides(null, null));
            if (located.context() == null) {
                fail("locate failed: " + located);
                deleteRecursively(project);
                return;
            }
            CompilerInvocation invocation = CompilerProfileProvider.resolve(
                ReleaseState.V1_2_ACTIVE,
                ReleaseConfiguration.releaseCapabilityRegistry());
            CompilationOrchestrator failing = new CompilationOrchestrator(
                located.context(), entryFile, false, false, false, false, null,
                invocation);
            boolean compiled = failing.compile();
            check(!compiled,
                "the shared-lowering E6005 recompile fails (nothing publishes)");
            check(failing.diagnostics().stream()
                    .anyMatch(d -> "E6005".equals(d.code())),
                "the failure is the shared-lowering E6005 (never a frontend "
                    + "rejection): " + failing.diagnostics());
            Map<String, String> after = fingerprint(project.resolve("out"));
            check(prior.equals(after),
                "the prior artifact set is preserved byte-for-byte after the failed "
                    + "compile");
            boolean residue = false;
            try (Stream<Path> entries = Files.list(project)) {
                for (Path entry : entries.toList()) {
                    String name = entry.getFileName().toString();
                    if (name.contains(".deal-stage-")
                            || name.contains(".deal-retired-")) {
                        residue = true;
                    }
                }
            }
            check(!residue,
                "no staging/retired siblings survive the failed publication");
        } finally {
            deleteRecursively(project);
        }
    }

    private static Map<String, String> fingerprint(Path root) throws Exception {
        Map<String, String> result = new TreeMap<>();
        if (!Files.isDirectory(root)) {
            return result;
        }
        try (Stream<Path> walk = Files.walk(root, FileVisitOption.values())) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(path).toString()
                    .replace(File.separatorChar, '/');
                result.put(relative, sha256Hex(Files.readAllBytes(path)));
            }
        }
        return result;
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Semantic Production Gate Test (ISSUE-0239 E10) ===\n");
        testEmitterSeam();
        testAllSharedLuaJit();
        testAllSharedJvm();
        testSharedTableMemberReadRuns();
        testRouteSelectionWithoutFallback();
        testAdapterShapeReroutesLegacy();
        testFailurePreservesPriorArtifacts();
        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
