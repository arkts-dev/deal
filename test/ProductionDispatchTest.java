package deal.test;

import deal.ast.ClassDeclaration;
import deal.ast.ExportDeclaration;
import deal.ast.StatementNode;
import deal.checker.CheckResult;
import deal.checker.NameResolver;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.codegen.Backend;
import deal.codegen.SourceMapGenerator;
import deal.codegen.jvm.JvmBackend;
import deal.codegen.jvm.JvmNames;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticRange;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.module.CompilationOrchestrator;
import deal.module.DefaultResourceOccurrence;
import deal.module.DefaultSerializerModuleInput;
import deal.module.PlannedDefaultClass;
import deal.module.ProductionProjectEmission;
import deal.module.RuntimeImportDependency;
import deal.module.RuntimeResourceReference;
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
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.test.conformance.ProducedProjectIdentityAudit;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    /**
     * The GitHub #6 sibling-provider reproducer (the exact reported
     * program): two class-declaring modules in one directory, imported
     * and constructed in {@code main}.
     */
    private static final String SIBLING_MAIN_SOURCE = """
        import * as console from "std/console";
        import * as ir from "./m/ir";
        import * as codec from "./m/codec";
        export function main(): null {
          if (ir.useIr({}) === 0 && codec.useCodec({}) === 0) { console.log("ok"); }
          return null;
        }
        """;

    /** The same program with the two provider imports reversed. */
    private static final String SIBLING_MAIN_SOURCE_REVERSED = """
        import * as console from "std/console";
        import * as codec from "./m/codec";
        import * as ir from "./m/ir";
        export function main(): null {
          if (ir.useIr({}) === 0 && codec.useCodec({}) === 0) { console.log("ok"); }
          return null;
        }
        """;

    private static final String SIBLING_IR_SOURCE = """
        export class Ir { a: int = 0; }
        export function useIr(x: Ir): int { return x.a; }
        """;

    private static final String SIBLING_CODEC_SOURCE = """
        export class Codec { b: int = 0; }
        export function useCodec(y: Codec): int { return y.b; }
        """;

    /**
     * The GitHub #6 same-name collision control (review cycle 1): the
     * second sibling declares a non-exported class of the first
     * sibling's name, so a name-bearing sibling program must never
     * become the provider and the sibling's own plan
     * ({@code a = 93}) must never serialize as the demanded class.
     */
    private static final String COLLISION_IR_SOURCE = """
        export class Ir { a: int = 7; }
        export function useIr(x: Ir): int { return x.a; }
        """;

    private static final String COLLISION_CODEC_SOURCE = """
        class Ir { a: int = 93; }
        export class Codec { b: int = 9; }
        export function useCodec(y: Codec): int { return y.b; }
        """;

    /** The collision consumer: the qualified field annotation names
     * {@code ir.Ir} while the codec sibling declares a same-name
     * non-exported class. */
    private static final String COLLISION_MAIN_CODEC_FIRST = """
        import * as console from "std/console";
        import * as codec from "./m/codec";
        import * as ir from "./m/ir";
        export class Outer { r: ir.Ir = {}; c: codec.Codec = {}; }
        export function main(): null {
          let o: Outer = {};
          if (o.r.a === 7 && o.c.b === 9) { console.log("ok"); }
          return null;
        }
        """;

    /** The collision consumer with the two provider imports reversed. */
    private static final String COLLISION_MAIN_IR_FIRST = """
        import * as console from "std/console";
        import * as ir from "./m/ir";
        import * as codec from "./m/codec";
        export class Outer { r: ir.Ir = {}; c: codec.Codec = {}; }
        export function main(): null {
          let o: Outer = {};
          if (o.r.a === 7 && o.c.b === 9) { console.log("ok"); }
          return null;
        }
        """;

    /**
     * The contextual-call collision (review cycle 2): the class context
     * arrives only through {@code ir.useIr}'s parameter, and both
     * identity-equal siblings export the demanded {@code Ir} name, so
     * the resolved imported callee's declaring module must own the
     * recorded plan and the emitted construction (the codec sibling's
     * own default never serializes as the demanded class).
     */
    private static final String CONTEXTUAL_IR_SOURCE = """
        export class Ir { a: int = 7; }
        export function useIr(x: Ir): int { return x.a; }
        """;

    private static final String CONTEXTUAL_CODEC_SOURCE = """
        export class Ir { a: int = 93; }
        export class Codec { b: int = 9; }
        export function useCodec(y: Codec): int { return y.b; }
        """;

    /** The contextual collision consumer: the literal is an argument of
     * the {@code ir}-imported callee, so only the callee's parameter
     * context names the demanded provider. */
    private static final String CONTEXTUAL_MAIN_CODEC_FIRST = """
        import * as console from "std/console";
        import * as codec from "./m/codec";
        import * as ir from "./m/ir";
        export class Outer { x: int = ir.useIr({}); c: codec.Codec = {}; }
        export function main(): null {
          let o: Outer = {};
          if (o.x === 7 && o.c.b === 9) { console.log("ok"); }
          return null;
        }
        """;

    /** The contextual collision consumer with the imports reversed. */
    private static final String CONTEXTUAL_MAIN_IR_FIRST = """
        import * as console from "std/console";
        import * as ir from "./m/ir";
        import * as codec from "./m/codec";
        export class Outer { x: int = ir.useIr({}); c: codec.Codec = {}; }
        export function main(): null {
          let o: Outer = {};
          if (o.x === 7 && o.c.b === 9) { console.log("ok"); }
          return null;
        }
        """;

    /** The original reproducer shape (a body call) under the exported
     * same-name sibling collision: the construction sits outside a
     * published plan default. */
    private static final String CONTEXTUAL_BODY_CODEC_FIRST = """
        import * as console from "std/console";
        import * as codec from "./m/codec";
        import * as ir from "./m/ir";
        export function main(): null {
          if (ir.useIr({}) === 7) { console.log("ok"); }
          return null;
        }
        """;

    private static final String CONTEXTUAL_BODY_IR_FIRST = """
        import * as console from "std/console";
        import * as ir from "./m/ir";
        import * as codec from "./m/codec";
        export function main(): null {
          if (ir.useIr({}) === 7) { console.log("ok"); }
          return null;
        }
        """;

    /**
     * The nested-literal control (review cycle 2): the demanded class
     * is a nested field of an imported class, so the enclosing field
     * annotation names the outer class only. The declaring program's own
     * field declaration carries the nested class's provider.
     */
    private static final String NESTED_IR_SOURCE = """
        export class Ir { a: int = 7; }
        export class Wrap { inner: Ir = {}; }
        """;

    private static final String NESTED_CODEC_SOURCE = """
        export class Ir { a: int = 93; }
        """;

    private static final String NESTED_MAIN_CODEC_FIRST = """
        import * as console from "std/console";
        import * as codec from "./m/codec";
        import * as ir from "./m/ir";
        export class Outer { w: ir.Wrap = { inner: {} }; }
        export function main(): null {
          let o: Outer = {};
          if (o.w.inner.a === 7) { console.log("ok"); }
          return null;
        }
        """;

    /**
     * The declared-context collision (review cycle 3): the demand
     * arrives through a declared return type, an array element
     * annotation, and a member-assignment target type — never through
     * an enclosing field annotation or a callee parameter. Every one of
     * the literals must bind the provider its declaration names, in
     * both import orders, and the published artifact must construct the
     * demanded default (7), never the sibling's (93).
     */
    private static final String RETURN_IR_SOURCE = """
        export class Ir { a: int = 7; }
        """;

    private static final String RETURN_CODEC_SOURCE = """
        export class Ir { a: int = 93; }
        export class Codec { b: int = 9; }
        """;

    private static final String RETURN_MAIN_CODEC_FIRST = """
        import * as console from "std/console";
        import * as codec from "./m/codec";
        import * as ir from "./m/ir";
        function make(): ir.Ir { return {}; }
        export class Outer { r: ir.Ir = {}; c: codec.Codec = {}; }
        export function main(): null {
          let o: Outer = {};
          o.r = {};
          let xs: ir.Ir[] = [{}];
          let m: ir.Ir = make();
          if (o.r.a === 7 && xs[0].a === 7 && m.a === 7 && o.c.b === 9)
            { console.log("ok"); }
          return null;
        }
        """;

    private static final String RETURN_MAIN_IR_FIRST = """
        import * as console from "std/console";
        import * as ir from "./m/ir";
        import * as codec from "./m/codec";
        function make(): ir.Ir { return {}; }
        export class Outer { r: ir.Ir = {}; c: codec.Codec = {}; }
        export function main(): null {
          let o: Outer = {};
          o.r = {};
          let xs: ir.Ir[] = [{}];
          let m: ir.Ir = make();
          if (o.r.a === 7 && xs[0].a === 7 && m.a === 7 && o.c.b === 9)
            { console.log("ok"); }
          return null;
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

    /**
     * The source-map library module: the sync exports plus one async export,
     * so the produced artifact carries an async-entry definition owned by
     * this module (the source-map regression pins its preceding comment and
     * sidecar mapping).
     */
    private static final String MAP_LIB_SOURCE = """
        export function value(): int {
          return 42
        }

        export function label(): string {
          return "lib"
        }

        export async function load(): int {
          return 7
        }
        """;

    /**
     * The source-map entry module: the probe main plus a cross-module async
     * export, so both constituent modules own an async-entry definition.
     */
    private static final String MAP_APP_SOURCE = """
        import * as lib from "./lib"
        import * as console from "std/console"

        export function main(): null {
          console.log("PROBE|MAIN-ONCE")
          return null
        }

        export function label(): string {
          return "app"
        }

        export async function worker(): int {
          return await lib.load()
        }
        """;

    /**
     * The direct-emission library of the source-map regression: one exported
     * class whose CLASS_FACTORY op carries the class declaration's span (the
     * owner module of the construction-time factory group).
     */
    private static final String DIRECT_EMISSION_LIB_SOURCE = """
        export function value(): int {
          return 42
        }

        export class Point {
          x: int = 0
        }
        """;

    /**
     * The direct-emission entry of the source-map regression: one
     * parameterized export (its parameter BINDING_ALLOC is emitted directly
     * in the function factory, never through emitOp) and one imported-class
     * construction (the owner-side factory group emitted directly in the
     * caller's body).
     */
    private static final String DIRECT_EMISSION_APP_SOURCE = """
        import * as lib from "./lib"
        import * as console from "std/console"

        export function f(x: int): int {
          return x
        }

        export function main(): null {
          let p: lib.Point = { x: 1 };
          console.log("PROBE|DIRECT-MAIN")
          return null
        }
        """;

    /**
     * The omitted-default factory fixture: the exported class carries two
     * defaulted fields, so the owner-side factory group emits one
     * caller-attributed failure branch per omitted default and must restore
     * the owning module's comment and mapping after each branch.
     */
    private static final String OMITTED_DEFAULT_LIB_SOURCE = """
        export class Point {
          x: int = 3
          y: int = 4
        }
        """;

    /**
     * The omitted-default entry: the one imported-class construction
     * provides no field, so both defaults evaluate on the owning side and
     * the construction stays the caller's own mapped group.
     */
    private static final String OMITTED_DEFAULT_APP_SOURCE = """
        import * as lib from "./lib"
        import * as console from "std/console"

        export function main(): null {
          let p: lib.Point = {};
          console.log("PROBE|OMITTED-DEFAULTS")
          return null
        }
        """;

    /**
     * The source-map unsafe-path fixture manifest: the module root carries an
     * embedded line terminator (the JSON escape the parser resolves to a real
     * LF in the directory name), so the source display name of the emitted
     * source comment is not a single comment line unless it is encoded.
     */
    private static final String DEAL_JSON_NEWLINE_ROOT = """
        {
          "languageVersion": "1.2",
          "moduleRoots": ["src\\nline"],
          "output": "out",
          "backend": "luajit"
        }
        """;

    /**
     * The source-map unsafe-path fixture manifest: the module root carries a
     * literal backslash-u000a sequence, the Java Unicode-escape hazard (the
     * Java lexer translates the escape before it recognizes the comment).
     */
    private static final String DEAL_JSON_UNICODE_ROOT = """
        {
          "languageVersion": "1.2",
          "moduleRoots": ["src\\\\u000aline"],
          "output": "out",
          "backend": "luajit"
        }
        """;

    /**
     * The source-map control-character fixture manifest: the module root
     * carries accepted C0 controls other than the LF/CR/TAB the serializer
     * always escaped (BEL, VT, FF, US), spelled as JSON {@code \\uXXXX}
     * escapes so the strict manifest parser resolves the real directory name.
     */
    private static final String DEAL_JSON_CONTROL_ROOT = """
        {
          "languageVersion": "1.2",
          "moduleRoots": ["src\\u0007\\u000b\\u000c\\u001fline"],
          "output": "out",
          "backend": "luajit"
        }
        """;

    /**
     * The control-character module root name: four accepted C0 controls the
     * serializer wrote into the sidecar raw before the strict-JSON escape
     * (BEL, VT, FF, US), around ordinary text.
     */
    private static final String CONTROL_ROOT_NAME = "src"
        + (char) 0x07 + (char) 0x0b + (char) 0x0c + (char) 0x1f + "line";

    /**
     * The control-character output directory name: BS and ESC in the
     * generated path (the output root sits two levels below the project
     * root, so the generated path is the project-relative spelling).
     */
    private static final String CONTROL_OUTPUT_NAME = "out"
        + (char) 0x08 + (char) 0x1b + "dir";

    /** The unsafe-path fixture body: a trivial entry that returns null. */
    private static final String TRIVIAL_MAIN_SOURCE = """
        export function main(): null {
          return null
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

    /**
     * The unrealized construct seed: a function-typed {@code for} counter
     * (the per-iteration carry load's function identity is outside the
     * closed lowering coverage, B5), so the production compile still fails
     * the module with the named E6005 {@code CONSTRUCT_UNLOWERED} before any
     * emission. The forward function-typed materialization this battery
     * previously pinned as a later slice's construct is production-covered
     * now: a module-level declaration's execution binding registers at the
     * hoist, so a forward alias resolves its body.
     */
    private static final String UNREALIZED_CONSTRUCT_SOURCE = """
        export function main(): null {
          let counter: (a: int) => int = function(a: int): int { return a; };
          for (let f: (a: int) => int = counter; f(1) === 1; f = counter) {
            break;
          }
          return null
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

    /** The test-only bounded node probe (a skip, never a failure, when
     * node is unavailable — the JsBackendTest toolchain rule). */
    private static Boolean nodeAvailable;

    private static boolean probeNode() {
        if (nodeAvailable == null) {
            try {
                Process node = new ProcessBuilder("node", "--version")
                    .redirectErrorStream(true).start();
                nodeAvailable = node.waitFor() == 0;
            } catch (IOException | InterruptedException e) {
                nodeAvailable = false;
            }
        }
        return nodeAvailable;
    }

    /** The {@link #runProcess} outcome for a command whose absence is a
     * probe failure, not a test abort. */
    private static ProcessOutcome runProcessUnchecked(Path directory,
            String... command) {
        try {
            return runProcess(directory, command);
        } catch (Exception e) {
            return new ProcessOutcome(-1, "process failure: " + e);
        }
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

    private static void testProductionSiblingModuleProviderIdentity()
            throws Exception {
        System.out.println("-- public compilation: sibling class-declaring"
            + " modules in one directory keep their own provider identity"
            + " and run on every target --");

        for (boolean reversed : new boolean[] { false, true }) {
            for (String backend : List.of("js", "luajit", "jvm")) {
                String label = backend + (reversed ? " (reversed imports)"
                    : "");
                Path project = Files.createTempDirectory(
                    "production-sibling-");
                try {
                    write(project, "deal.json", """
                        {
                          "languageVersion": "1.2",
                          "stdlib": "1.2",
                          "moduleRoots": ["src"],
                          "dependencies": {},
                          "externals": {},
                          "backend": "%s",
                          "output": "out"
                        }
                        """.formatted(backend));
                    write(project, "src/m/ir.deal", SIBLING_IR_SOURCE);
                    write(project, "src/m/codec.deal", SIBLING_CODEC_SOURCE);
                    write(project, "src/main.deal", reversed
                        ? SIBLING_MAIN_SOURCE_REVERSED
                        : SIBLING_MAIN_SOURCE);

                    ProjectOutcome compile = productionCompile(project,
                        "src/main.deal", "out");
                    check(compile.exitCode() == 0,
                        "the " + label + " public compile succeeds: "
                            + compile.stderr());
                    check(compile.stderr().isEmpty(),
                        "the " + label + " public compile emits no"
                            + " diagnostic: " + compile.stderr());
                    if (compile.exitCode() != 0) {
                        continue;
                    }
                    Path out = project.resolve("out");
                    switch (backend) {
                        case "js" -> checkSiblingJsRun(label, out);
                        case "luajit" -> checkSiblingLuaRun(label, out);
                        default -> checkSiblingJvmRun(label, project, out);
                    }
                } finally {
                    deleteRecursively(project);
                }
            }
        }
    }

    /** The JS artifact of the sibling project under the real node. */
    private static void checkSiblingJsRun(String label, Path out) {
        checkJsArtifactRun(label, out, "main.js");
    }

    /** One named JS entry artifact under the real node. */
    private static void checkJsArtifactRun(String label, Path out,
            String artifact) {
        if (!probeNode()) {
            System.out.println("  SKIP (node unavailable): the " + label
                + " artifact execution");
            return;
        }
        checkSiblingStdout(label, "node",
            runProcessUnchecked(out, "node", artifact));
    }

    /** The LuaJIT artifact of the sibling project under the real luajit. */
    private static void checkSiblingLuaRun(String label, Path out) {
        checkSiblingStdout(label, "luajit",
            runProcessUnchecked(out, "luajit", "main.lua"));
    }

    /**
     * The JVM artifact of the sibling project: javac --release 25 then
     * java, the pinned two-step of this suite.
     */
    private static void checkSiblingJvmRun(String label, Path project,
            Path out) throws Exception {
        String buildCp = Path.of("build").toAbsolutePath().normalize()
            .toString();
        ProcessOutcome javac = runProcess(project, "javac", "--release",
            "25", "-proc:none", "-cp", buildCp, "-d", out.toString(),
            out.resolve("Main.java").toString());
        check(javac.exitCode() == 0, "the " + label + " JVM artifact"
            + " compiles: " + javac.output());
        if (javac.exitCode() != 0) {
            return;
        }
        checkSiblingStdout(label, "java", runProcess(out, "java", "-cp",
            buildCp + File.pathSeparator + out, "Main"));
    }

    /** The sibling project's pinned observable: stdout "ok", exit 0. */
    private static void checkSiblingStdout(String label, String tool,
            ProcessOutcome run) {
        check(run.exitCode() == 0, "the " + label + " artifact runs clean"
            + " under " + tool + ": exit=" + run.exitCode() + " output="
            + run.output());
        check("ok".equals(run.output().trim()),
            "the " + label + " artifact prints 'ok' under " + tool
                + ", got '" + run.output() + "'");
    }

    /**
     * The GitHub #6 same-name sibling collision (review cycle 1): an
     * identity-equal sibling that declares a non-exported class of the
     * demanded name must never become the provider. Both import orders
     * compile through the release-owned production invocation in one
     * project (one deployment identity, so the provider digests are
     * comparable), and the completed plan occurrence and deferred
     * dependency edge must carry the declaring module, its exact
     * declaration range, and one import-order-independent provider
     * digest — never the private sibling's plan.
     */
    private static void testProductionSameNameSiblingCollision()
            throws Exception {
        System.out.println("-- production invocation: a non-exported"
            + " same-name sibling class never becomes the provider (both"
            + " import orders; exact resource and digest) --");

        Path root = Files.createTempDirectory("production-collision-");
        try {
            write(root, "deal.json", """
                {
                  "languageVersion": "1.2",
                  "stdlib": "1.2",
                  "moduleRoots": ["src"],
                  "dependencies": {},
                  "externals": {},
                  "backend": "js",
                  "output": "out"
                }
                """);
            write(root, "src/m/ir.deal", COLLISION_IR_SOURCE);
            write(root, "src/m/codec.deal", COLLISION_CODEC_SOURCE);
            write(root, "src/main.deal", COLLISION_MAIN_CODEC_FIRST);
            write(root, "src/main_ir_first.deal",
                COLLISION_MAIN_IR_FIRST);

            String codecFirstDigest = null;
            String codecFirstCodecDigest = null;
            for (String entry : List.of("src/main.deal",
                    "src/main_ir_first.deal")) {
                String label = entry.contains("ir_first") ? "ir-first"
                    : "codec-first";
                boolean irFirst = label.equals("ir-first");
                ArmCompile compile = compileWithInvocation(root, entry,
                    productionInvocation());
                check(compile.success(), "the " + label + " collision"
                    + " compile succeeds through the production"
                    + " invocation: " + compile.orchestrator()
                        .diagnostics());
                if (!compile.success()) {
                    continue;
                }
                CompilationOrchestrator orchestrator =
                    compile.orchestrator();
                String mainUri = serializerModuleUri(orchestrator,
                    "/" + entry);
                String irUri = serializerModuleUri(orchestrator,
                    "/src/m/ir.deal");
                String codecUri = serializerModuleUri(orchestrator,
                    "/src/m/codec.deal");
                check(mainUri != null && irUri != null
                        && codecUri != null, "the " + label + " collision"
                    + " compile exposes every module identity");
                if (mainUri == null || irUri == null || codecUri == null) {
                    continue;
                }
                DiagnosticRange irDeclaration = classDeclarationRange(
                    COLLISION_IR_SOURCE, irUri.substring("file://"
                        .length()), "Ir");
                DiagnosticRange privateIrDeclaration =
                    classDeclarationRange(COLLISION_CODEC_SOURCE,
                        codecUri.substring("file://".length()), "Ir");
                check(irDeclaration != null
                        && privateIrDeclaration != null,
                    "the collision control resolves both sibling Ir"
                        + " declaration ranges");
                if (irDeclaration == null
                        || privateIrDeclaration == null) {
                    continue;
                }

                String irDigest = checkClassDefaultResource(orchestrator,
                    label, mainUri, irUri, "ir", "Ir", irDeclaration,
                    privateIrDeclaration);
                String codecDigest = checkClassDefaultResource(
                    orchestrator, label, mainUri, codecUri, "codec",
                    "Codec", classDeclarationRange(COLLISION_CODEC_SOURCE,
                        codecUri.substring("file://".length()), "Codec"),
                    privateIrDeclaration);
                if (irFirst) {
                    checkEq(codecFirstDigest, irDigest, "the Ir provider"
                        + " digest is import-order independent");
                    checkEq(codecFirstCodecDigest, codecDigest, "the Codec"
                        + " provider digest is import-order independent");
                } else {
                    codecFirstDigest = irDigest;
                    codecFirstCodecDigest = codecDigest;
                }
            }
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * The contextual-call collision (review cycle 2): the class context
     * arrives only through the resolved imported callee's parameter, and
     * both identity-equal siblings export the demanded class name. The
     * recorded occurrence and deferred edge must name the callee's
     * declaring module, its exact declaration range, and the class's own
     * plan digest — in both import orders, and the published artifact
     * must construct that provider's class (the demanded default 7 is
     * observable on stdout, never the sibling's 93).
     */
    private static void testProductionContextualCallSiblingCollision()
            throws Exception {
        System.out.println("-- production invocation: the resolved imported"
            + " callee's parameter context owns the class literal (both"
            + " import orders; exact provider, digest, and artifact"
            + " outcome) --");

        Path root = Files.createTempDirectory("production-contextual-");
        try {
            write(root, "deal.json", """
                {
                  "languageVersion": "1.2",
                  "stdlib": "1.2",
                  "moduleRoots": ["src"],
                  "dependencies": {},
                  "externals": {},
                  "backend": "js",
                  "output": "out"
                }
                """);
            write(root, "src/m/ir.deal", CONTEXTUAL_IR_SOURCE);
            write(root, "src/m/codec.deal", CONTEXTUAL_CODEC_SOURCE);
            write(root, "src/main.deal", CONTEXTUAL_MAIN_CODEC_FIRST);
            write(root, "src/main_ir_first.deal",
                CONTEXTUAL_MAIN_IR_FIRST);
            write(root, "src/main_body.deal",
                CONTEXTUAL_BODY_CODEC_FIRST);
            write(root, "src/main_body_ir_first.deal",
                CONTEXTUAL_BODY_IR_FIRST);

            String codecFirstDigest = null;
            String codecFirstCodecDigest = null;
            for (String entry : List.of("src/main.deal",
                    "src/main_ir_first.deal")) {
                String label = entry.contains("ir_first") ? "ir-first"
                    : "codec-first";
                boolean irFirst = label.equals("ir-first");
                ArmCompile compile = compileWithInvocation(root, entry,
                    productionInvocation());
                check(compile.success(), "the " + label + " contextual"
                    + " collision compile succeeds through the production"
                    + " invocation: " + compile.orchestrator()
                        .diagnostics());
                if (!compile.success()) {
                    continue;
                }
                CompilationOrchestrator orchestrator =
                    compile.orchestrator();
                String mainUri = serializerModuleUri(orchestrator,
                    "/" + entry);
                String irUri = serializerModuleUri(orchestrator,
                    "/src/m/ir.deal");
                String codecUri = serializerModuleUri(orchestrator,
                    "/src/m/codec.deal");
                check(mainUri != null && irUri != null
                        && codecUri != null, "the " + label
                    + " contextual collision compile exposes every module"
                    + " identity");
                if (mainUri == null || irUri == null || codecUri == null) {
                    continue;
                }
                DiagnosticRange irDeclaration = classDeclarationRange(
                    CONTEXTUAL_IR_SOURCE, irUri.substring("file://"
                        .length()), "Ir");
                DiagnosticRange codecIrDeclaration = classDeclarationRange(
                    CONTEXTUAL_CODEC_SOURCE, codecUri.substring("file://"
                        .length()), "Ir");
                check(irDeclaration != null
                        && codecIrDeclaration != null,
                    "the contextual collision resolves both sibling Ir"
                        + " declaration ranges");
                if (irDeclaration == null || codecIrDeclaration == null) {
                    continue;
                }

                String irDigest = checkClassDefaultResource(orchestrator,
                    label, mainUri, irUri, "ir", "Ir", irDeclaration,
                    codecIrDeclaration);
                String codecDigest = checkClassDefaultResource(
                    orchestrator, label, mainUri, codecUri, "codec",
                    "Codec", classDeclarationRange(CONTEXTUAL_CODEC_SOURCE,
                        codecUri.substring("file://".length()), "Codec"),
                    codecIrDeclaration);
                if (irFirst) {
                    checkEq(codecFirstDigest, irDigest, "the contextual Ir"
                        + " provider digest is import-order independent");
                    checkEq(codecFirstCodecDigest, codecDigest, "the"
                        + " contextual Codec provider digest is"
                        + " import-order independent");
                } else {
                    codecFirstDigest = irDigest;
                    codecFirstCodecDigest = codecDigest;
                }

                // The published artifact constructs the recorded
                // provider's class: the demanded default (7) is the
                // observable outcome, never the sibling's (93).
                checkJsArtifactRun(label, root.resolve("out"),
                    irFirst ? "main_ir_first.js" : "main.js");
            }

            // The same collision with the construction in a function body
            // (the original reproducer's shape): the ordinary runtime-use
            // edge and the emitted construction must target the resolved
            // callee's declaring module in both import orders.
            String bodyCodecFirstDigest = null;
            for (String entry : List.of("src/main_body.deal",
                    "src/main_body_ir_first.deal")) {
                String label = entry.contains("ir_first")
                    ? "body ir-first" : "body codec-first";
                boolean irFirst = entry.contains("ir_first");
                ArmCompile compile = compileWithInvocation(root, entry,
                    productionInvocation());
                check(compile.success(), "the " + label + " contextual"
                    + " collision compile succeeds through the production"
                    + " invocation: " + compile.orchestrator()
                        .diagnostics());
                if (!compile.success()) {
                    continue;
                }
                CompilationOrchestrator orchestrator =
                    compile.orchestrator();
                String mainUri = serializerModuleUri(orchestrator,
                    "/" + entry);
                String irUri = serializerModuleUri(orchestrator,
                    "/src/m/ir.deal");
                String codecUri = serializerModuleUri(orchestrator,
                    "/src/m/codec.deal");
                DiagnosticRange irDeclaration = mainUri == null
                        || irUri == null || codecUri == null ? null
                    : classDeclarationRange(CONTEXTUAL_IR_SOURCE,
                        irUri.substring("file://".length()), "Ir");
                check(irDeclaration != null, "the " + label
                    + " collision resolves the declaring declaration"
                    + " range");
                if (irDeclaration == null) {
                    continue;
                }
                List<RuntimeImportDependency> edges = classEdges(
                    orchestrator, mainUri, "Ir",
                    RuntimeImportDependency.Reason.RUNTIME_USE);
                checkEq(1, edges.size(), "the " + label + " completed"
                    + " graph holds exactly one ordinary Ir edge");
                if (edges.size() != 1) {
                    continue;
                }
                RuntimeImportDependency edge = edges.get(0);
                checkEq(irUri, edge.toSemanticModuleIdentity()
                        .canonicalResolvedSourceUri(), "the " + label
                    + " ordinary Ir edge targets the declaring module");
                checkEq("ir", edge.importAlias(), "the " + label
                    + " ordinary Ir edge keeps the spelled alias");
                checkEq(irDeclaration, edge.semanticResourceIdentity()
                        .lexicalDeclarationIdentity().sourceScalarRange(),
                    "the " + label + " ordinary Ir edge carries the exact"
                        + " defining declaration range");
                if (irFirst) {
                    checkEq(bodyCodecFirstDigest,
                        edge.providerContractDigest(), "the " + label
                        + " ordinary Ir provider digest is import-order"
                        + " independent");
                } else {
                    bodyCodecFirstDigest = edge.providerContractDigest();
                    checkEq(codecFirstDigest, bodyCodecFirstDigest,
                        "the body-walk Ir provider digest equals the"
                            + " plan-default digest of the same provider");
                }

                // The published artifact constructs the recorded
                // provider's class: the demanded default (7) is the
                // observable outcome, never the sibling's (93).
                checkJsArtifactRun(label, root.resolve("out"),
                    irFirst ? "main_body_ir_first.js" : "main_body.js");
            }
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * The nested-literal control (review cycle 2): the demanded class
     * arrives as a nested field literal of an imported class, so the
     * enclosing field annotation names the outer class only and the
     * declaring program's own field declaration is the nested class's
     * provider. The completed plan, its digest, and the published
     * artifact's construction must all name that provider.
     */
    private static void testProductionNestedSiblingCollision()
            throws Exception {
        System.out.println("-- production invocation: a nested literal of an"
            + " imported class keeps the declaring program's field provider"
            + " --");

        Path root = Files.createTempDirectory("production-nested-");
        try {
            write(root, "deal.json", """
                {
                  "languageVersion": "1.2",
                  "stdlib": "1.2",
                  "moduleRoots": ["src"],
                  "dependencies": {},
                  "externals": {},
                  "backend": "js",
                  "output": "out"
                }
                """);
            write(root, "src/m/ir.deal", NESTED_IR_SOURCE);
            write(root, "src/m/codec.deal", NESTED_CODEC_SOURCE);
            write(root, "src/main.deal", NESTED_MAIN_CODEC_FIRST);

            ArmCompile compile = compileWithInvocation(root,
                "src/main.deal", productionInvocation());
            check(compile.success(), "the nested collision compile"
                + " succeeds through the production invocation: "
                + compile.orchestrator().diagnostics());
            if (compile.success()) {
                CompilationOrchestrator orchestrator =
                    compile.orchestrator();
                String mainUri = serializerModuleUri(orchestrator,
                    "/src/main.deal");
                String irUri = serializerModuleUri(orchestrator,
                    "/src/m/ir.deal");
                String codecUri = serializerModuleUri(orchestrator,
                    "/src/m/codec.deal");
                DiagnosticRange irDeclaration = classDeclarationRange(
                    NESTED_IR_SOURCE, irUri.substring("file://".length()),
                    "Ir");
                DiagnosticRange codecIrDeclaration = classDeclarationRange(
                    NESTED_CODEC_SOURCE, codecUri.substring("file://"
                        .length()), "Ir");
                check(mainUri != null && irUri != null
                        && codecUri != null && irDeclaration != null
                        && codecIrDeclaration != null,
                    "the nested collision resolves every module identity"
                        + " and declaration range");
                if (mainUri != null && irUri != null && codecUri != null
                        && irDeclaration != null
                        && codecIrDeclaration != null) {
                    checkClassDefaultResource(orchestrator, "codec-first",
                        mainUri, irUri, "ir", "Ir", irDeclaration,
                        codecIrDeclaration);
                }
                // The published artifact constructs the inner Ir of the
                // declaring program: the demanded default (7) is the
                // observable outcome, never the sibling's (93).
                checkSiblingJsRun("the nested collision",
                    root.resolve("out"));
            }
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * The declared-context collision (review cycle 3): the demand
     * arrives through a declared return type, an array element
     * annotation, and a member-assignment target type. The completed
     * plan occurrence and deferred edge must name the provider the
     * declaration names — one import-order-independent digest — and the
     * published artifact must print the demanded default (7).
     */
    private static void testProductionDeclaredContextSiblingCollision()
            throws Exception {
        System.out.println("-- production invocation: declared return types,"
            + " array annotations, and assignment targets keep their"
            + " provider (both import orders; exact resource and digest)"
            + " --");

        Path root = Files.createTempDirectory(
            "production-declared-context-");
        try {
            write(root, "deal.json", """
                {
                  "languageVersion": "1.2",
                  "stdlib": "1.2",
                  "moduleRoots": ["src"],
                  "dependencies": {},
                  "externals": {},
                  "backend": "js",
                  "output": "out"
                }
                """);
            write(root, "src/m/ir.deal", RETURN_IR_SOURCE);
            write(root, "src/m/codec.deal", RETURN_CODEC_SOURCE);
            write(root, "src/main.deal", RETURN_MAIN_CODEC_FIRST);
            write(root, "src/main_ir_first.deal", RETURN_MAIN_IR_FIRST);

            String codecFirstDigest = null;
            String codecFirstCodecDigest = null;
            for (String entry : List.of("src/main.deal",
                    "src/main_ir_first.deal")) {
                String label = entry.contains("ir_first") ? "ir-first"
                    : "codec-first";
                boolean irFirst = label.equals("ir-first");
                ArmCompile compile = compileWithInvocation(root, entry,
                    productionInvocation());
                check(compile.success(), "the " + label + " declared-"
                    + "context collision compile succeeds through the"
                    + " production invocation: " + compile.orchestrator()
                        .diagnostics());
                if (!compile.success()) {
                    continue;
                }
                CompilationOrchestrator orchestrator =
                    compile.orchestrator();
                String mainUri = serializerModuleUri(orchestrator,
                    "/" + entry);
                String irUri = serializerModuleUri(orchestrator,
                    "/src/m/ir.deal");
                String codecUri = serializerModuleUri(orchestrator,
                    "/src/m/codec.deal");
                DiagnosticRange irDeclaration = classDeclarationRange(
                    RETURN_IR_SOURCE, irUri.substring("file://".length()),
                    "Ir");
                DiagnosticRange codecIrDeclaration = classDeclarationRange(
                    RETURN_CODEC_SOURCE, codecUri.substring("file://"
                        .length()), "Ir");
                check(mainUri != null && irUri != null && codecUri != null
                        && irDeclaration != null
                        && codecIrDeclaration != null,
                    "the " + label + " declared-context collision resolves"
                        + " every module identity and declaration range");
                if (mainUri == null || irUri == null || codecUri == null
                        || irDeclaration == null
                        || codecIrDeclaration == null) {
                    continue;
                }
                String irDigest = checkClassDefaultResource(orchestrator,
                    label, mainUri, irUri, "ir", "Ir", irDeclaration,
                    codecIrDeclaration);
                String codecDigest = checkClassDefaultResource(
                    orchestrator, label, mainUri, codecUri, "codec",
                    "Codec", classDeclarationRange(RETURN_CODEC_SOURCE,
                        codecUri.substring("file://".length()),
                        "Codec"), codecIrDeclaration);
                // The body literals (the returned literal, the
                // assignment value, and the array element) are the
                // ordinary walk's runtime demands: exactly one edge, on
                // the declared provider, with the exact defining range
                // and the same provider digest as the plan route.
                List<RuntimeImportDependency> bodyEdges = classEdges(
                    orchestrator, mainUri, "Ir",
                    RuntimeImportDependency.Reason.RUNTIME_USE);
                checkEq(1, bodyEdges.size(), "the " + label + " body"
                    + " walk holds exactly one ordinary Ir edge");
                if (bodyEdges.size() == 1) {
                    RuntimeImportDependency edge = bodyEdges.get(0);
                    checkEq(irUri, edge.toSemanticModuleIdentity()
                        .canonicalResolvedSourceUri(), "the " + label
                        + " ordinary Ir edge targets the declaring"
                        + " module");
                    checkEq("ir", edge.importAlias(), "the " + label
                        + " ordinary Ir edge keeps the spelled alias");
                    checkEq(irDeclaration, edge.semanticResourceIdentity()
                        .lexicalDeclarationIdentity()
                        .sourceScalarRange(), "the " + label
                        + " ordinary Ir edge carries the exact defining"
                        + " declaration range");
                    checkEq(irDigest, edge.providerContractDigest(),
                        "the " + label + " body-walk Ir provider digest"
                            + " equals the plan-default digest of the"
                            + " same provider");
                }
                if (irFirst) {
                    checkEq(codecFirstDigest, irDigest, "the declared-"
                        + "context Ir provider digest is import-order"
                        + " independent");
                    checkEq(codecFirstCodecDigest, codecDigest, "the"
                        + " declared-context Codec provider digest is"
                        + " import-order independent");
                } else {
                    codecFirstDigest = irDigest;
                    codecFirstCodecDigest = codecDigest;
                }

                // The published artifact constructs only the named
                // provider's class: the demanded default (7) is the
                // observable outcome of every literal shape.
                checkJsArtifactRun(label, root.resolve("out"),
                    irFirst ? "main_ir_first.js" : "main.js");
            }
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * Asserts the completed graph facts of one demanded imported class:
     * exactly one class-default-plan occurrence and exactly one deferred
     * dependency edge, both on the declaring module's URI, the spelled
     * import alias, and the exact defining declaration range; the
     * forbidden (private same-name sibling) declaration is never bound.
     * Returns the edge's provider digest, or null when the shape is
     * wrong.
     */
    private static String checkClassDefaultResource(
            CompilationOrchestrator orchestrator, String label,
            String mainUri, String providerUri, String alias,
            String className, DiagnosticRange declaration,
            DiagnosticRange forbiddenDeclaration) {
        List<DefaultResourceOccurrence> occurrences =
            classPlanOccurrences(orchestrator, mainUri, className);
        checkEq(1, occurrences.size(), "the " + label + " completed"
            + " plan holds exactly one " + className
            + " class-default occurrence");
        if (occurrences.size() == 1) {
            DefaultResourceOccurrence occurrence = occurrences.get(0);
            checkEq(providerUri, occurrence.toSemanticModuleIdentity()
                    .canonicalResolvedSourceUri(), "the " + label + " "
                + className + " occurrence targets the declaring module");
            checkEq(alias, occurrence.importAlias(), "the " + label + " "
                + className + " occurrence keeps the spelled alias");
            checkEq(declaration, occurrence.semanticResourceIdentity()
                    .lexicalDeclarationIdentity().sourceScalarRange(),
                "the " + label + " " + className + " occurrence carries"
                    + " the exact defining declaration range");
        }

        List<RuntimeImportDependency> edges = classPlanEdges(orchestrator,
            mainUri, className);
        checkEq(1, edges.size(), "the " + label + " completed graph"
            + " holds exactly one deferred " + className + " edge");
        if (edges.size() != 1) {
            return null;
        }
        RuntimeImportDependency edge = edges.get(0);
        checkEq(providerUri, edge.toSemanticModuleIdentity()
                .canonicalResolvedSourceUri(), "the " + label + " "
            + className + " edge targets the declaring module");
        checkEq(alias, edge.importAlias(), "the " + label + " "
            + className + " edge keeps the spelled alias");
        checkEq(declaration, edge.semanticResourceIdentity()
                .lexicalDeclarationIdentity().sourceScalarRange(),
            "the " + label + " " + className + " edge carries the exact"
                + " defining declaration range");
        check(edge.providerContractDigest() != null
                && !edge.providerContractDigest().isEmpty(), "the "
            + label + " " + className + " edge carries its provider"
            + " digest");
        check(edges.stream().noneMatch(e -> e.semanticResourceIdentity()
                .lexicalDeclarationIdentity().sourceScalarRange()
                .equals(forbiddenDeclaration)), "the " + label + " "
            + className + " edge never binds the forbidden same-name"
            + " sibling declaration");
        return edge.providerContractDigest();
    }

    /** The class-default-plan occurrences of the given class name whose
     * consuming module is {@code fromUri}, in plan order. */
    private static List<DefaultResourceOccurrence> classPlanOccurrences(
            CompilationOrchestrator orchestrator, String fromUri,
            String className) {
        List<DefaultResourceOccurrence> matches = new ArrayList<>();
        for (List<PlannedDefaultClass> plans
                : orchestrator.completedDefaultPlans().values()) {
            for (PlannedDefaultClass planned : plans) {
                for (DefaultResourceOccurrence occurrence
                        : planned.occurrences()) {
                    if (occurrence.kind()
                            == RuntimeResourceReference.Kind
                                .IMPORTED_CLASS_DEFAULT_PLAN
                            && occurrence.fromSemanticModuleIdentity()
                                .canonicalResolvedSourceUri()
                                .equals(fromUri)
                            && occurrence.semanticResourceIdentity()
                                .lexicalDeclarationIdentity()
                                .declaredName().equals(className)) {
                        matches.add(occurrence);
                    }
                }
            }
        }
        return matches;
    }

    /** The completed deferred-default dependency edges of the given
     * class name whose consuming module is {@code fromUri}. */
    private static List<RuntimeImportDependency> classPlanEdges(
            CompilationOrchestrator orchestrator, String fromUri,
            String className) {
        return classEdges(orchestrator, fromUri, className,
            RuntimeImportDependency.Reason.DEFERRED_DEFAULT_BINDING);
    }

    /** The completed dependency edges of the given class name and reason
     * whose consuming module is {@code fromUri}. */
    private static List<RuntimeImportDependency> classEdges(
            CompilationOrchestrator orchestrator, String fromUri,
            String className,
            RuntimeImportDependency.Reason reason) {
        List<RuntimeImportDependency> matches = new ArrayList<>();
        for (RuntimeImportDependency edge
                : orchestrator.runtimeDependencies()) {
            if (edge.reason() == reason
                    && edge.fromSemanticModuleIdentity()
                        .canonicalResolvedSourceUri().equals(fromUri)
                    && edge.semanticResourceIdentity()
                        .lexicalDeclarationIdentity().declaredName()
                        .equals(className)) {
                matches.add(edge);
            }
        }
        return matches;
    }

    /** The semantic module URI of the compiled input whose source path
     * ends with the given suffix, or null. */
    private static String serializerModuleUri(
            CompilationOrchestrator orchestrator, String suffix) {
        for (DefaultSerializerModuleInput input
                : orchestrator.defaultSerializerModuleInputs().values()) {
            if (input.sourcePath().endsWith(suffix)) {
                return input.location().semanticModuleIdentity()
                    .canonicalResolvedSourceUri();
            }
        }
        return null;
    }

    /** The complete source range of one top-level class declaration of
     * the given source text, in the file string the compiler uses. */
    private static DiagnosticRange classDeclarationRange(String source,
            String fileName, String className) {
        LexResult lex = new Lexer(source, fileName).tokenize();
        if (lex.hasErrors()) {
            return null;
        }
        ParseResult parse = new Parser(lex.tokens(), fileName,
            lex.directiveEvents()).parse();
        if (parse.hasErrors()) {
            return null;
        }
        for (StatementNode stmt : parse.program().statements()) {
            ClassDeclaration cd = null;
            if (stmt instanceof ClassDeclaration c) {
                cd = c;
            } else if (stmt instanceof ExportDeclaration ed
                    && ed.declaration() instanceof ClassDeclaration c) {
                cd = c;
            }
            if (cd != null && cd.name().equals(className)) {
                return cd.span().range();
            }
        }
        return null;
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

            write(project, "src/main.deal", UNREALIZED_CONSTRUCT_SOURCE);
            // A previous artifact set in the live output root.
            write(out, "main.lua", "-- previous artifact\n");
            write(out, "deal/runtime.lua", "-- previous runtime\n");
            Map<String, byte[]> before = snapshotTree(out);
            ProjectOutcome compile = productionCompile(project, "src/main.deal",
                "out");
            check(compile.exitCode() != 0,
                "the function-typed for-counter production compile fails "
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
            write(same, "deal/runtime.lua",
                Files.readString(Path.of("deal/runtime.lua"), StandardCharsets.UTF_8));
            write(same, "probe.lua", """
                local surfaces = dofile("out/main.lua")
                assert(type(surfaces) == "table",
                  "the chunk returns the entry surface")
                local worker = surfaces.worker
                assert(type(worker) == "table" and worker.__kind == "function",
                  "the entry surface publishes the same-module async export")
                local handle = worker.f()
                assert(type(handle) == "table" and handle.__kind == "async",
                  "the published async export produces an async operation")
                require("deal.runtime").async_step(handle)
                local completion = handle.__result
                assert(handle.__done and completion == 1,
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
        checkLaterSliceConstruct("function-typed for-counter",
            Map.of("src/main.deal", UNREALIZED_CONSTRUCT_SOURCE),
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
    // 5. The source-map emission through the public CLI
    // =========================================================================

    private static void testSourceMapDisposition() throws Exception {
        System.out.println("-- source map: an explicit --source-map publishes the "
            + "artifact with source comments and one .deal.map.json per module "
            + "with real coordinates; a failing recompile preserves the "
            + "published artifact and maps byte-for-byte; --dump-ir emits the "
            + "same set without a warning --");

        Path project = Files.createTempDirectory("production-dispatch-map-");
        try {
            write(project, "deal.json", DEAL_JSON_JVM);
            write(project, "src/lib.deal", MAP_LIB_SOURCE);
            write(project, "src/main.deal", MAP_APP_SOURCE);

            // (1) The explicit --source-map: one sidecar per constituent
            // module mapping the one shared artifact, real generated/source
            // coordinates, no warning, and the commented artifact still
            // compiles and runs.
            Path jvmOut = project.resolve("build/out");
            ProjectOutcome explicit = runProductionCli("compile",
                project.resolve("src/main.deal").toAbsolutePath().toString(),
                "--backend", "jvm", "--source-map", "--output",
                jvmOut.toAbsolutePath().toString());
            check(explicit.exitCode() == 0,
                "the explicit --source-map production compile succeeds: "
                    + explicit.stderr());
            check(!explicit.stderr().contains("Warning:"),
                "the explicit --source-map compile prints no warning: "
                    + explicit.stderr());
            List<String> published = artifactFiles(jvmOut);
            check(published.contains("Main.java"),
                "the explicit --source-map compile publishes its project "
                    + "artifact: " + published);
            check(published.contains("main.deal.map.json")
                    && published.contains("lib.deal.map.json"),
                "the explicit --source-map compile publishes one sidecar per "
                    + "constituent module: " + published);
            verifyPublishedSidecar(project, jvmOut, "main.deal.map.json",
                "src/main.deal", "build/out/Main.java", "Main.java", "// ");
            verifyPublishedSidecar(project, jvmOut, "lib.deal.map.json",
                "src/lib.deal", "build/out/Main.java", "Main.java", "// ");
            verifyAsyncEntryComments(project, jvmOut, "Main.java", "// ",
                "public static Object ae", List.of("src/lib.deal",
                    "src/main.deal"));

            String classpath = Path.of("build").toAbsolutePath().normalize()
                .toString();
            ProcessOutcome javac = runProcess(project, "javac", "--release", "25",
                "-proc:none", "-cp", classpath, "-d", jvmOut.toString(),
                jvmOut.resolve("Main.java").toString());
            check(javac.exitCode() == 0,
                "the source-commented JVM artifact compiles: " + javac.output());
            if (javac.exitCode() == 0) {
                ProcessOutcome run = runProcess(jvmOut, "java", "-cp",
                    classpath + File.pathSeparator + jvmOut, "Main");
                check(run.exitCode() == 0
                        && countOccurrences(run.output(), "PROBE|MAIN-ONCE") == 1,
                    "the source-commented JVM artifact runs the entry once: exit="
                        + run.exitCode() + " output=" + run.output());
            }

            // (2) Rollback: a failing recompile into the same output root
            // leaves the published artifact and both sidecars byte-identical.
            Map<String, byte[]> publishedSet = snapshotTree(jvmOut);
            write(project, "src/lib.deal", "export function value(:\n");
            ProjectOutcome failing = runProductionCli("compile",
                project.resolve("src/main.deal").toAbsolutePath().toString(),
                "--backend", "jvm", "--source-map", "--output",
                jvmOut.toAbsolutePath().toString());
            check(failing.exitCode() != 0,
                "the failing --source-map recompile fails: " + failing.stderr());
            checkTreeIdentical(publishedSet, jvmOut,
                "the failing --source-map recompile preserves the published "
                    + "artifact and both sidecars");
            write(project, "src/lib.deal", MAP_LIB_SOURCE);

            // (3) The effective --dump-ir-derived request emits the same set
            // without a warning (the one effective flag of both arms).
            Path dumpOut = project.resolve("build/dump");
            ProjectOutcome dumpIr = runProductionCli("compile",
                project.resolve("src/main.deal").toAbsolutePath().toString(),
                "--backend", "jvm", "--dump-ir", "--output",
                dumpOut.toAbsolutePath().toString());
            check(dumpIr.exitCode() == 0,
                "the --dump-ir production compile succeeds: " + dumpIr.stderr());
            check(!dumpIr.stderr().contains("Warning:"),
                "a --dump-ir-derived flag prints no warning: " + dumpIr.stderr());
            List<String> dumped = artifactFiles(dumpOut);
            check(dumped.contains("main.deal.map.json")
                    && dumped.contains("lib.deal.map.json"),
                "the --dump-ir-derived flag emits the same sidecar set: "
                    + dumped);

            // (4) The LuaJIT target: the chunk carries the source comments and
            // the two sidecars map the same shared artifact; the commented
            // chunk still runs under luajit.
            Path luaOut = project.resolve("build/lua");
            ProjectOutcome lua = runProductionCli("compile",
                project.resolve("src/main.deal").toAbsolutePath().toString(),
                "--backend", "luajit", "--source-map", "--output",
                luaOut.toAbsolutePath().toString());
            check(lua.exitCode() == 0,
                "the LuaJIT --source-map compile succeeds: " + lua.stderr());
            check(!lua.stderr().contains("Warning:"),
                "the LuaJIT --source-map compile prints no warning: "
                    + lua.stderr());
            List<String> luaPublished = artifactFiles(luaOut);
            check(luaPublished.contains("main.lua")
                    && luaPublished.contains("main.deal.map.json")
                    && luaPublished.contains("lib.deal.map.json"),
                "the LuaJIT --source-map compile publishes the chunk and both "
                    + "sidecars: " + luaPublished);
            verifyPublishedSidecar(project, luaOut, "main.deal.map.json",
                "src/main.deal", "build/lua/main.lua", "main.lua", "-- ");
            verifyPublishedSidecar(project, luaOut, "lib.deal.map.json",
                "src/lib.deal", "build/lua/main.lua", "main.lua", "-- ");
            verifyAsyncEntryComments(project, luaOut, "main.lua", "-- ",
                "__asyncEntries[", List.of("src/lib.deal", "src/main.deal"));
            ProcessOutcome luaRun = runProcess(luaOut, "luajit", "main.lua");
            check(luaRun.exitCode() == 0
                    && countOccurrences(luaRun.output(), "PROBE|MAIN-ONCE") == 1,
                "the source-commented LuaJIT chunk runs the entry once: exit="
                    + luaRun.exitCode() + " output=" + luaRun.output());
        } finally {
            deleteRecursively(project);
        }
    }

    /**
     * Verifies one published {@code .deal.map.json} sidecar: the pinned
     * fields, at least one mapping, and every mapping against the real
     * artifact line (the mapped generated line is exactly the source comment
     * carrying the mapped source coordinates under the sidecar's source name)
     * and the real source file line range.
     */
    private static void verifyPublishedSidecar(Path project, Path outputRoot,
            String sidecarName, String expectedSource, String expectedGenerated,
            String artifactName, String commentMarker) throws Exception {
        Path sidecar = outputRoot.resolve(sidecarName);
        check(Files.isRegularFile(sidecar),
            "the sidecar is published: " + sidecarName);
        if (!Files.isRegularFile(sidecar)) {
            return;
        }
        String json = Files.readString(sidecar);
        check(json.contains("\"version\": 1"),
            sidecarName + " carries the pinned version: " + json);
        check(json.contains("\"source\": \"" + expectedSource + "\""),
            sidecarName + " names its constituent source file: " + json);
        check(json.contains("\"generated\": \"" + expectedGenerated + "\""),
            sidecarName + " maps the one shared artifact: " + json);

        List<int[]> mappings = parseSidecarMappings(json);
        check(!mappings.isEmpty(),
            sidecarName + " carries real mappings: " + json);
        List<String> artifactLines = Files.readString(
            outputRoot.resolve(artifactName)).lines().toList();
        List<String> sourceLines = Files.readString(
            project.resolve(expectedSource)).lines().toList();
        for (int[] mapping : mappings) {
            int generatedLine = mapping[0];
            int sourceLine = mapping[2];
            int sourceColumn = mapping[3];
            check(generatedLine >= 1 && generatedLine <= artifactLines.size(),
                sidecarName + " maps a real generated line: " + generatedLine);
            check(sourceLine >= 1 && sourceLine <= sourceLines.size(),
                sidecarName + " maps a real source line: " + sourceLine);
            if (generatedLine >= 1 && generatedLine <= artifactLines.size()) {
                String artifactLine = artifactLines.get(generatedLine - 1);
                checkEq(commentMarker + expectedSource + ":" + sourceLine + ":"
                        + sourceColumn,
                    artifactLine.strip(),
                    sidecarName + " maps the source comment at its generated "
                        + "line");
                checkEq(artifactLine.indexOf(commentMarker) + 1, mapping[1],
                    sidecarName + " maps the comment start column");
            }
        }
    }

    /** The mapping tuples of one sidecar JSON, in recorded order. */
    private static List<int[]> parseSidecarMappings(String json) {
        List<int[]> mappings = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
            "\\{ \"generatedLine\": ([0-9]+), \"generatedColumn\": ([0-9]+), "
                + "\"sourceLine\": ([0-9]+), \"sourceColumn\": ([0-9]+) \\}")
            .matcher(json);
        while (matcher.find()) {
            mappings.add(new int[] {
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                Integer.parseInt(matcher.group(4)),
            });
        }
        return mappings;
    }

    /**
     * Verifies the async-entry definitions of one produced artifact: every
     * {@code definitionPrefix} line is preceded by the entry op's own source
     * comment, that comment names an expected owning module, and the comment
     * line is exactly one mapping of the owning module's sidecar (the entry
     * boundary the JVM arm already recorded). The comment's source
     * coordinates must land on a real line of the module's source file.
     */
    private static void verifyAsyncEntryComments(Path project, Path outputRoot,
            String artifactName, String commentMarker, String definitionPrefix,
            List<String> expectedOwners) throws Exception {
        List<String> lines = Files.readString(outputRoot.resolve(artifactName))
            .lines().toList();
        List<Integer> entryLines = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).strip().startsWith(definitionPrefix)) {
                entryLines.add(i + 1);
            }
        }
        checkEq(expectedOwners.size(), entryLines.size(), artifactName
            + " carries one async-entry definition per constituent module: "
            + entryLines);
        List<String> owners = new ArrayList<>();
        for (int entryLine : entryLines) {
            check(entryLine >= 2, artifactName + " async entry at line "
                + entryLine + " has a preceding line");
            if (entryLine < 2) {
                continue;
            }
            String commentLine = lines.get(entryLine - 2);
            String comment = commentLine.strip();
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(commentMarker)
                    + "(.+):([0-9]+):([0-9]+)$").matcher(comment);
            check(matcher.matches(), artifactName + " async entry at line "
                + entryLine + " is preceded by its source comment: "
                + comment);
            if (!matcher.matches()) {
                continue;
            }
            String owner = matcher.group(1);
            owners.add(owner);
            int sourceLine = Integer.parseInt(matcher.group(2));
            int sourceColumn = Integer.parseInt(matcher.group(3));
            String[] sourceLines = Files.readString(project.resolve(owner))
                .lines().toArray(String[]::new);
            check(sourceLine >= 1 && sourceLine <= sourceLines.length
                    && !sourceLines[sourceLine - 1].isBlank(),
                artifactName + " maps a real source line of " + owner
                    + " (line " + sourceLine + ")");
            String sidecarName = owner.substring(owner.lastIndexOf('/') + 1)
                + ".map.json";
            List<int[]> mappings = parseSidecarMappings(
                Files.readString(outputRoot.resolve(sidecarName)));
            int commentColumn = commentLine.indexOf(commentMarker) + 1;
            boolean mapped = false;
            for (int[] mapping : mappings) {
                mapped = mapping[0] == entryLine - 1
                    && mapping[1] == commentColumn
                    && mapping[2] == sourceLine
                    && mapping[3] == sourceColumn;
                if (mapped) {
                    break;
                }
            }
            check(mapped, artifactName + " async entry at line " + entryLine
                + " is mapped by " + sidecarName + " at its comment line");
        }
        check(owners.containsAll(expectedOwners)
                && owners.size() == expectedOwners.size(),
            artifactName + " async entries cover every constituent module: "
                + owners);
    }

    /**
     * The unsafe-path-text regression of the source-map comments: a module
     * root carrying an embedded line terminator and one carrying a literal
     * backslash-u000a sequence (the Java Unicode-escape hazard) must still
     * yield artifacts that compile and run on both targets under
     * {@code --source-map}. The comment carries the encoded single-line
     * spelling of the source path; the sidecar keeps the actual path.
     */
    private static void testSourceMapUnsafeSourcePathText() throws Exception {
        System.out.println("-- source map: a module root carrying a line "
            + "terminator or a literal backslash-u000a sequence still yields "
            + "source-commented artifacts that compile and run on both "
            + "targets --");

        // (1) The embedded line terminator in the module root name.
        Path newlineProject = Files.createTempDirectory(
            "production-dispatch-map-nl-");
        try {
            Path newlineRoot = Path.of("src\nline");
            write(newlineProject, "deal.json", DEAL_JSON_NEWLINE_ROOT);
            write(newlineProject, newlineRoot.resolve("main.deal").toString(),
                TRIVIAL_MAIN_SOURCE);
            verifyUnsafePathArtifacts(newlineProject, newlineRoot,
                "src\\nline/main.deal");
        } finally {
            deleteRecursively(newlineProject);
        }

        // (2) The literal backslash-u000a sequence in the module root name.
        Path unicodeProject = Files.createTempDirectory(
            "production-dispatch-map-uni-");
        try {
            Path unicodeRoot = Path.of("src\\u000aline");
            write(unicodeProject, "deal.json", DEAL_JSON_UNICODE_ROOT);
            write(unicodeProject, unicodeRoot.resolve("main.deal").toString(),
                TRIVIAL_MAIN_SOURCE);
            verifyUnsafePathArtifacts(unicodeProject, unicodeRoot,
                "src\\\\u000aline/main.deal");
        } finally {
            deleteRecursively(unicodeProject);
        }
    }

    /**
     * Compiles one unsafe-path project through the public CLI on both targets
     * with {@code --source-map} and verifies the emitted artifacts: the source
     * comment carries the encoded single-line spelling of the module's source
     * path, the sidecar keeps the actual path, and the commented artifact
     * compiles and runs on its target.
     */
    private static void verifyUnsafePathArtifacts(Path project, Path moduleRoot,
            String expectedCommentPath) throws Exception {
        String entry = project.resolve(moduleRoot).resolve("main.deal")
            .toAbsolutePath().toString();
        String actualSourcePath = moduleRoot.toString()
            .replace(File.separatorChar, '/') + "/main.deal";

        Path jvmOut = project.resolve("build/jvm");
        ProjectOutcome jvmCompile = runProductionCli("compile", entry,
            "--backend", "jvm", "--source-map", "--output",
            jvmOut.toAbsolutePath().toString());
        check(jvmCompile.exitCode() == 0,
            "the JVM unsafe-path compile succeeds: " + jvmCompile.stderr());
        if (jvmCompile.exitCode() == 0) {
            String java = Files.readString(jvmOut.resolve("Main.java"));
            check(java.lines().anyMatch(line -> line.contains(
                    "// " + expectedCommentPath + ":1:1")),
                "the JVM comment encodes the unsafe source path on one line: "
                    + expectedCommentPath);
            checkEq(actualSourcePath, jsonStringField(Files.readString(
                    jvmOut.resolve("main.deal.map.json")), "source"),
                "the JVM sidecar keeps the actual source path");
            String classpath = Path.of("build").toAbsolutePath().normalize()
                .toString();
            ProcessOutcome javac = runProcess(project, "javac", "--release",
                "25", "-proc:none", "-cp", classpath, "-d",
                jvmOut.toString(), jvmOut.resolve("Main.java").toString());
            check(javac.exitCode() == 0,
                "the JVM unsafe-path artifact compiles: " + javac.output());
            if (javac.exitCode() == 0) {
                ProcessOutcome run = runProcess(jvmOut, "java", "-cp",
                    classpath + File.pathSeparator + jvmOut, "Main");
                checkEq(0, run.exitCode(),
                    "the JVM unsafe-path artifact runs: " + run.output());
            }
        }

        Path luaOut = project.resolve("build/lua");
        ProjectOutcome luaCompile = runProductionCli("compile", entry,
            "--backend", "luajit", "--source-map", "--output",
            luaOut.toAbsolutePath().toString());
        check(luaCompile.exitCode() == 0,
            "the LuaJIT unsafe-path compile succeeds: " + luaCompile.stderr());
        if (luaCompile.exitCode() == 0) {
            String lua = Files.readString(luaOut.resolve("main.lua"));
            check(lua.lines().anyMatch(line -> line.contains(
                    "-- " + expectedCommentPath + ":1:1")),
                "the LuaJIT comment encodes the unsafe source path on one "
                    + "line: " + expectedCommentPath);
            checkEq(actualSourcePath, jsonStringField(Files.readString(
                    luaOut.resolve("main.deal.map.json")), "source"),
                "the LuaJIT sidecar keeps the actual source path");
            ProcessOutcome run = runProcess(luaOut, "luajit", "main.lua");
            checkEq(0, run.exitCode(),
                "the LuaJIT unsafe-path artifact runs: " + run.output());
        }
    }

    /**
     * The unescaped value of one top-level string field of a sidecar JSON
     * document, or {@code null} when the field is absent.
     */
    private static String jsonStringField(String json, String field) {
        int key = json.indexOf("\"" + field + "\": \"");
        if (key < 0) {
            return null;
        }
        StringBuilder value = new StringBuilder();
        for (int i = key + field.length() + 5; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                return value.toString();
            }
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                switch (next) {
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    default -> value.append(next);
                }
            } else {
                value.append(c);
            }
        }
        return null;
    }

    /**
     * The control-character path regression of the source-map sidecars: an
     * accepted module root and an accepted output directory carrying C0
     * controls other than the LF/CR/TAB the serializer always escaped must
     * still yield sidecars that strict JSON consumers parse, with the decoded
     * source and generated paths exactly the actual ones, on both targets,
     * and the commented artifacts must still compile and run.
     */
    private static void testSourceMapControlCharacterPaths() throws Exception {
        System.out.println("-- source map: accepted C0 controls in the source "
            + "and generated paths still yield strict-JSON sidecars with the "
            + "actual decoded paths, and runnable artifacts on both "
            + "targets --");

        verifySerializerEscapesEveryControl();

        Path project = Files.createTempDirectory(
            "production-dispatch-map-ctl-");
        try {
            Path moduleRoot = Path.of(CONTROL_ROOT_NAME);
            write(project, "deal.json", DEAL_JSON_CONTROL_ROOT);
            write(project, moduleRoot.resolve("main.deal").toString(),
                TRIVIAL_MAIN_SOURCE);
            String entry = project.resolve(moduleRoot).resolve("main.deal")
                .toAbsolutePath().toString();
            String actualSource = moduleRoot.toString()
                .replace(File.separatorChar, '/') + "/main.deal";

            // (1) The JVM target: the sidecar is strict JSON with the actual
            // paths, and the commented class compiles and runs.
            Path jvmOut = project.resolve(
                Path.of("build", CONTROL_OUTPUT_NAME));
            ProjectOutcome jvmCompile = runProductionCli("compile", entry,
                "--backend", "jvm", "--source-map", "--output",
                jvmOut.toAbsolutePath().toString());
            check(jvmCompile.exitCode() == 0,
                "the JVM control-path compile succeeds: "
                    + jvmCompile.stderr());
            if (jvmCompile.exitCode() == 0) {
                verifyStrictSidecarPaths(jvmOut, "main.deal.map.json",
                    actualSource, Path.of("build", CONTROL_OUTPUT_NAME)
                        .toString().replace(File.separatorChar, '/')
                        + "/Main.java");
                String classpath = Path.of("build").toAbsolutePath().normalize()
                    .toString();
                ProcessOutcome javac = runProcess(project, "javac", "--release",
                    "25", "-proc:none", "-cp", classpath, "-d",
                    jvmOut.toString(), jvmOut.resolve("Main.java").toString());
                check(javac.exitCode() == 0,
                    "the JVM control-path artifact compiles: " + javac.output());
                if (javac.exitCode() == 0) {
                    ProcessOutcome run = runProcess(jvmOut, "java", "-cp",
                        classpath + File.pathSeparator + jvmOut, "Main");
                    checkEq(0, run.exitCode(),
                        "the JVM control-path artifact runs: " + run.output());
                }
            }

            // (2) The LuaJIT target: the same strict sidecar and a chunk that
            // runs under the real interpreter.
            Path luaOut = project.resolve(
                Path.of("build", CONTROL_OUTPUT_NAME + "-lua"));
            ProjectOutcome luaCompile = runProductionCli("compile", entry,
                "--backend", "luajit", "--source-map", "--output",
                luaOut.toAbsolutePath().toString());
            check(luaCompile.exitCode() == 0,
                "the LuaJIT control-path compile succeeds: "
                    + luaCompile.stderr());
            if (luaCompile.exitCode() == 0) {
                verifyStrictSidecarPaths(luaOut, "main.deal.map.json",
                    actualSource, Path.of("build",
                        CONTROL_OUTPUT_NAME + "-lua")
                        .toString().replace(File.separatorChar, '/')
                        + "/main.lua");
                ProcessOutcome run = runProcess(luaOut, "luajit", "main.lua");
                checkEq(0, run.exitCode(),
                    "the LuaJIT control-path artifact runs: " + run.output());
            }
        } finally {
            deleteRecursively(project);
        }
    }

    /**
     * The direct-emission-group regression of the source maps: the parameter
     * transfer of a parameterized export and the owner-side class-factory
     * group are emitted directly (never through {@code emitOp}), so each must
     * open its own mapped group — the parameter's own IR span, the owning
     * module's class declaration span, and the caller's span again when the
     * caller's statements resume — on both production targets, with the
     * artifacts still runnable.
     */
    private static void testSourceMapDirectEmissionGroups() throws Exception {
        System.out.println("-- source map: the directly emitted parameter "
            + "transfer and the owner-side class-factory group carry their "
            + "own source comments and sidecar mappings on both targets --");

        Path project = Files.createTempDirectory(
            "production-dispatch-map-direct-");
        try {
            write(project, "deal.json", DEAL_JSON_JVM);
            write(project, "src/lib.deal", DIRECT_EMISSION_LIB_SOURCE);
            write(project, "src/main.deal", DIRECT_EMISSION_APP_SOURCE);
            String entry = project.resolve("src/main.deal").toAbsolutePath()
                .toString();

            // (1) The JVM target: the directly emitted groups carry their
            // comments and mappings, and the commented class compiles and
            // runs the entry.
            Path jvmOut = project.resolve("build/jvm");
            ProjectOutcome jvmCompile = runProductionCli("compile", entry,
                "--backend", "jvm", "--source-map", "--output",
                jvmOut.toAbsolutePath().toString());
            check(jvmCompile.exitCode() == 0,
                "the direct-emission JVM compile succeeds: "
                    + jvmCompile.stderr());
            if (jvmCompile.exitCode() == 0) {
                verifyDirectEmissionGroups(project, jvmOut, "Main.java",
                    "// ", "b\\d+g0 = args\\[0\\];",
                    "String __prevMod_\\d+ = MODULE;", "MODULE = __prevMod_\\d+;");
                String classpath = Path.of("build").toAbsolutePath().normalize()
                    .toString();
                ProcessOutcome javac = runProcess(project, "javac", "--release",
                    "25", "-proc:none", "-cp", classpath, "-d",
                    jvmOut.toString(), jvmOut.resolve("Main.java").toString());
                check(javac.exitCode() == 0,
                    "the direct-emission JVM artifact compiles: "
                        + javac.output());
                if (javac.exitCode() == 0) {
                    ProcessOutcome run = runProcess(jvmOut, "java", "-cp",
                        classpath + File.pathSeparator + jvmOut, "Main");
                    check(run.exitCode() == 0
                            && countOccurrences(run.output(),
                                "PROBE|DIRECT-MAIN") == 1,
                        "the direct-emission JVM artifact runs the entry once: "
                            + "exit=" + run.exitCode() + " output="
                            + run.output());
                }
            }

            // (2) The LuaJIT target: the same groups carry the same
            // comments and mappings, and the chunk runs under the real
            // interpreter.
            Path luaOut = project.resolve("build/lua");
            ProjectOutcome luaCompile = runProductionCli("compile", entry,
                "--backend", "luajit", "--source-map", "--output",
                luaOut.toAbsolutePath().toString());
            check(luaCompile.exitCode() == 0,
                "the direct-emission LuaJIT compile succeeds: "
                    + luaCompile.stderr());
            if (luaCompile.exitCode() == 0) {
                verifyDirectEmissionGroups(project, luaOut, "main.lua", "-- ",
                    "S\\.b\\d+g0 = __args\\[1\\]",
                    "__modStack\\[#__modStack \\+ 1\\] = __module",
                    "__modStack\\[#__modStack\\] = nil");
                ProcessOutcome run = runProcess(luaOut, "luajit", "main.lua");
                check(run.exitCode() == 0
                        && countOccurrences(run.output(),
                            "PROBE|DIRECT-MAIN") == 1,
                    "the direct-emission LuaJIT chunk runs the entry once: "
                        + "exit=" + run.exitCode() + " output=" + run.output());
            }
        } finally {
            deleteRecursively(project);
        }
    }

    /**
     * Verifies the directly emitted statement groups of one produced
     * artifact: the parameter transfer of the parameterized export is
     * immediately preceded by the parameter's own source comment and mapped
     * at exactly that generated position by the entry module's sidecar; the
     * owner-side factory group is immediately preceded by the owning module's
     * class declaration comment and mapped there by the owning module's
     * sidecar; and the first source comment after the group's module restore
     * names the caller's own construction span and is mapped by the entry
     * module's sidecar.
     */
    private static void verifyDirectEmissionGroups(Path project, Path outputRoot,
            String artifactName, String commentMarker, String parameterPattern,
            String factoryGroupStart, String factoryGroupEnd) throws Exception {
        List<String> lines = Files.readString(outputRoot.resolve(artifactName))
            .lines().toList();
        List<String> appLines = Files.readString(project.resolve("src/main.deal"))
            .lines().toList();
        List<String> libLines = Files.readString(project.resolve("src/lib.deal"))
            .lines().toList();

        // The expected source positions, read from the fixture sources.
        int parameterLine = lineContaining(appLines, "export function f(") + 1;
        int parameterColumn = appLines.get(parameterLine - 1).indexOf("x:") + 1;
        int classLine = lineContaining(libLines, "export class Point") + 1;
        int classColumn = libLines.get(classLine - 1).indexOf("class") + 1;
        int constructLine = lineContaining(appLines, "let p: lib.Point") + 1;
        int constructColumn = appLines.get(constructLine - 1).indexOf("{ x:") + 1;

        // (1) The directly emitted parameter transfer.
        List<Integer> parameterGroups = matchingLines(lines, parameterPattern);
        checkEq(1, parameterGroups.size(), artifactName
            + " carries exactly one directly emitted parameter transfer: "
            + parameterGroups);
        if (parameterGroups.size() == 1) {
            int statementLine = parameterGroups.get(0);
            String comment = lines.get(statementLine - 1);
            checkEq(commentMarker + "src/main.deal:" + parameterLine + ":"
                    + parameterColumn,
                comment.strip(),
                artifactName + " precedes the parameter transfer with the "
                    + "parameter's own source comment");
            // The comment directly precedes the statement, so the
            // statement's 0-based index is the comment's 1-based line.
            checkSidecarMapping(outputRoot, "main.deal.map.json", statementLine,
                comment, commentMarker, parameterLine, parameterColumn,
                artifactName + " parameter transfer");
        }

        // (2) The owner-side class-factory group.
        List<Integer> factoryStarts = matchingLines(lines, factoryGroupStart);
        checkEq(1, factoryStarts.size(), artifactName
            + " carries exactly one owner-side factory group: " + factoryStarts);
        if (factoryStarts.size() != 1) {
            return;
        }
        int factoryStart = factoryStarts.get(0);
        String factoryComment = lines.get(factoryStart - 1);
        checkEq(commentMarker + "src/lib.deal:" + classLine + ":" + classColumn,
            factoryComment.strip(),
            artifactName + " precedes the owner-side factory group with the "
                + "class declaration comment of the owning module");
        // The factory comment directly precedes the group's first statement
        // (the 0-based statement index is the comment's 1-based line).
        checkSidecarMapping(outputRoot, "lib.deal.map.json", factoryStart,
            factoryComment, commentMarker, classLine, classColumn,
            artifactName + " owner-side factory group");

        // (3) The caller's attribution resumes after the group's module
        // restore: the first source comment after the restore names the
        // caller's own construction span and the entry sidecar maps it.
        int factoryEnd = -1;
        for (int candidate : matchingLines(lines, factoryGroupEnd)) {
            if (candidate > factoryStart) {
                factoryEnd = candidate;
            }
        }
        check(factoryEnd > factoryStart, artifactName
            + " restores the caller's module after the owner-side factory "
            + "group");
        if (factoryEnd <= factoryStart) {
            return;
        }
        int callerComment = -1;
        for (int i = factoryEnd + 1; i < lines.size(); i++) {
            if (lines.get(i).strip().startsWith(commentMarker)) {
                callerComment = i;
                break;
            }
        }
        check(callerComment > factoryEnd, artifactName
            + " emits a source comment after the owner-side factory group");
        if (callerComment <= factoryEnd) {
            return;
        }
        String callerLine = lines.get(callerComment);
        checkEq(commentMarker + "src/main.deal:" + constructLine + ":"
                + constructColumn,
            callerLine.strip(),
            artifactName + " restores the caller's own source comment when "
                + "the caller's statements resume");
        checkSidecarMapping(outputRoot, "main.deal.map.json", callerComment + 1,
            callerLine, commentMarker, constructLine, constructColumn,
            artifactName + " caller restore");
    }

    /**
     * The omitted-default regression: on both targets every omitted default
     * invocation, the owner-side transfer construction, and the caller's
     * final continuation carry the source comment and sidecar mapping of
     * their own source group. The caller's mapping inside an emitted
     * failure branch stays on the caller's own terminal and never leaks to
     * the owner-side statements that follow the branch.
     */
    private static void testSourceMapOmittedDefaultOwnerAttribution()
            throws Exception {
        System.out.println("-- source map: the owner-side factory group "
            + "restores its own comment and mapping after every "
            + "omitted-default failure branch on both targets --");

        Path project = Files.createTempDirectory(
            "production-dispatch-map-omitted-");
        try {
            write(project, "deal.json", DEAL_JSON_JVM);
            write(project, "src/lib.deal", OMITTED_DEFAULT_LIB_SOURCE);
            write(project, "src/main.deal", OMITTED_DEFAULT_APP_SOURCE);
            String entry = project.resolve("src/main.deal").toAbsolutePath()
                .toString();

            // (1) The JVM target: the two defaults are omitted, so the
            // owner-side failure branches execute; the artifact still
            // compiles and runs the entry, and every owner-side resumption
            // is attributed to the owning module again.
            Path jvmOut = project.resolve("build/jvm");
            ProjectOutcome jvmCompile = runProductionCli("compile", entry,
                "--backend", "jvm", "--source-map", "--output",
                jvmOut.toAbsolutePath().toString());
            check(jvmCompile.exitCode() == 0,
                "the omitted-default JVM compile succeeds: "
                    + jvmCompile.stderr());
            if (jvmCompile.exitCode() == 0) {
                verifyOmittedDefaultOwnerAttribution(project, jvmOut,
                    "Main.java", "// ", "String __prevMod_\\d+ = MODULE;",
                    "JvmRuntime\\.setModule\\(MODULE\\);",
                    "\\w+ = default\\d+\\(\\);",
                    "\\w+ __tf_\\d+_\\d+ = new \\w+\\(\\);", "throw __e;");
                String classpath = Path.of("build").toAbsolutePath().normalize()
                    .toString();
                ProcessOutcome javac = runProcess(project, "javac", "--release",
                    "25", "-proc:none", "-cp", classpath, "-d",
                    jvmOut.toString(), jvmOut.resolve("Main.java").toString());
                check(javac.exitCode() == 0,
                    "the omitted-default JVM artifact compiles: "
                        + javac.output());
                if (javac.exitCode() == 0) {
                    ProcessOutcome run = runProcess(jvmOut, "java", "-cp",
                        classpath + File.pathSeparator + jvmOut, "Main");
                    check(run.exitCode() == 0
                            && countOccurrences(run.output(),
                                "PROBE|OMITTED-DEFAULTS") == 1,
                        "the omitted-default JVM artifact evaluates both "
                            + "defaults and runs the entry once: exit="
                            + run.exitCode() + " output=" + run.output());
                }
            }

            // (2) The LuaJIT target: the same owner-side resumptions carry
            // the same comments and mappings, and the chunk runs under the
            // real interpreter.
            Path luaOut = project.resolve("build/lua");
            ProjectOutcome luaCompile = runProductionCli("compile", entry,
                "--backend", "luajit", "--source-map", "--output",
                luaOut.toAbsolutePath().toString());
            check(luaCompile.exitCode() == 0,
                "the omitted-default LuaJIT compile succeeds: "
                    + luaCompile.stderr());
            if (luaCompile.exitCode() == 0) {
                verifyOmittedDefaultOwnerAttribution(project, luaOut,
                    "main.lua", "-- ",
                    "__modStack\\[#__modStack \\+ 1\\] = __module",
                    "__modStack\\[#__modStack\\] = nil",
                    "__okT, __resT = pcall\\(__factories\\.D\\d+\\)",
                    "__instT = \\{\\}",
                    "__ev\\(\"main#\\d+\", \"FAILURE\", \"CLASS_NEW\".*");
                ProcessOutcome run = runProcess(luaOut, "luajit", "main.lua");
                check(run.exitCode() == 0
                        && countOccurrences(run.output(),
                            "PROBE|OMITTED-DEFAULTS") == 1,
                    "the omitted-default LuaJIT chunk evaluates both "
                        + "defaults and runs the entry once: exit="
                        + run.exitCode() + " output=" + run.output());
            }
        } finally {
            deleteRecursively(project);
        }
    }

    /**
     * Verifies the omitted-default factory groups of one produced artifact:
     * each omitted default invocation, the owner-side transfer construction,
     * and the caller's final continuation carry the source comment and
     * sidecar mapping of their own source group; each emitted failure branch
     * keeps the caller's mapping on the caller's own terminal.
     */
    private static void verifyOmittedDefaultOwnerAttribution(Path project,
            Path outputRoot, String artifactName, String commentMarker,
            String factoryGroupStart, String factoryGroupEnd,
            String defaultInvocation, String transferConstruction,
            String callerTerminal) throws Exception {
        List<String> lines = Files.readString(outputRoot.resolve(artifactName))
            .lines().toList();
        List<String> appLines = Files.readString(project.resolve("src/main.deal"))
            .lines().toList();
        List<String> libLines = Files.readString(project.resolve("src/lib.deal"))
            .lines().toList();
        int classLine = lineContaining(libLines, "export class Point") + 1;
        int classColumn = libLines.get(classLine - 1).indexOf("class") + 1;
        int constructLine = lineContaining(appLines, "let p: lib.Point") + 1;
        int constructColumn = appLines.get(constructLine - 1).indexOf("{") + 1;
        String ownerComment = commentMarker + "src/lib.deal:" + classLine + ":"
            + classColumn;
        String callerComment = commentMarker + "src/main.deal:" + constructLine
            + ":" + constructColumn;

        // The one owner-side factory group of the fixture, closed by its
        // success-path module restore (the first restore after the transfer
        // construction; the failure branches restore inside the group).
        List<Integer> groupStarts = matchingLines(lines, factoryGroupStart);
        checkEq(1, groupStarts.size(), artifactName
            + " carries exactly one owner-side factory group: " + groupStarts);
        if (groupStarts.size() != 1) {
            return;
        }
        int groupStart = groupStarts.get(0);

        // (1) Owner-side resumption: every omitted default invocation and
        // the owner-side transfer construction follow the owning module's
        // factory comment and are mapped by the owning module's sidecar at
        // exactly that generated position.
        List<Integer> invocations = new ArrayList<>();
        for (int candidate : matchingLines(lines, defaultInvocation)) {
            if (candidate > groupStart) {
                invocations.add(candidate);
            }
        }
        checkEq(2, invocations.size(), artifactName
            + " emits one invocation per omitted default: " + invocations);
        int transfer = -1;
        for (int i = (invocations.isEmpty()
                ? groupStart : invocations.get(invocations.size() - 1));
                i < lines.size(); i++) {
            if (lines.get(i).strip().matches(transferConstruction)) {
                transfer = i + 1;
                break;
            }
        }
        check(transfer > 0, artifactName
            + " emits the owner-side transfer construction after the last "
            + "omitted default: " + transfer);
        if (transfer <= 0) {
            return;
        }
        int groupEnd = -1;
        for (int candidate : matchingLines(lines, factoryGroupEnd)) {
            if (candidate > transfer) {
                groupEnd = candidate;
                break;
            }
        }
        check(groupEnd > transfer, artifactName
            + " closes the owner-side factory group on the success path: "
            + groupEnd);
        if (groupEnd <= transfer) {
            return;
        }
        List<Integer> ownerStatements = new ArrayList<>(invocations);
        ownerStatements.add(transfer);
        for (int statement : ownerStatements) {
            int comment = nearestPrecedingComment(lines, statement,
                commentMarker);
            check(comment > 0, artifactName + " precedes generated line "
                + statement + " with a source comment: " + comment);
            if (comment <= 0) {
                continue;
            }
            checkEq(ownerComment, lines.get(comment - 1).strip(),
                artifactName + " attributes generated line " + statement
                    + " to the owning module's factory group");
            checkSidecarMapping(outputRoot, "lib.deal.map.json", comment,
                lines.get(comment - 1), commentMarker, classLine, classColumn,
                artifactName + " owner-side resumption at generated line "
                    + statement);
        }

        // (2) Caller retention: each emitted failure branch keeps the
        // caller's mapping on the caller's own terminal.
        List<Integer> callerComments = new ArrayList<>();
        for (int index = groupStart; index < groupEnd - 1; index++) {
            if (lines.get(index).strip().equals(callerComment)) {
                callerComments.add(index + 1);
            }
        }
        checkEq(2, callerComments.size(), artifactName
            + " retains the caller's mapping inside each failure branch: "
            + callerComments);
        for (int comment : callerComments) {
            boolean terminal = comment < lines.size()
                && lines.get(comment).strip().matches(callerTerminal);
            check(terminal, artifactName + " keeps the caller mapping on the "
                + "caller's own failure terminal after line " + comment + ": "
                + (comment < lines.size() ? lines.get(comment).strip() : ""));
        }

        // (3) Caller continuation: the first source comment after the
        // group's success-path restore names the caller's own construction
        // span and the entry sidecar maps it.
        int continuation = -1;
        for (int i = groupEnd; i < lines.size(); i++) {
            if (lines.get(i).strip().startsWith(commentMarker)) {
                continuation = i + 1;
                break;
            }
        }
        check(continuation > 0, artifactName
            + " emits a source comment after the owner-side factory group");
        if (continuation > 0) {
            checkEq(callerComment, lines.get(continuation - 1).strip(),
                artifactName + " restores the caller's own source comment "
                    + "when the caller's statements resume");
            checkSidecarMapping(outputRoot, "main.deal.map.json", continuation,
                lines.get(continuation - 1), commentMarker, constructLine,
                constructColumn, artifactName + " caller continuation");
        }
    }

    /**
     * The line number (1-based) of the nearest source comment at or before
     * the given generated statement line, or {@code -1}.
     */
    private static int nearestPrecedingComment(List<String> lines,
            int statementLine, String commentMarker) {
        for (int i = statementLine - 2; i >= 0; i--) {
            if (lines.get(i).strip().startsWith(commentMarker)) {
                return i + 1;
            }
        }
        return -1;
    }

    /** The 0-based index of the first line containing the text, or -1. */
    private static int lineContaining(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    /** The 0-based indexes of the lines whose stripped text matches the pattern. */
    private static List<Integer> matchingLines(List<String> lines, String pattern) {
        List<Integer> matched = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).strip().matches(pattern)) {
                matched.add(i);
            }
        }
        return matched;
    }

    /**
     * Verifies that the sidecar maps the comment line's own generated
     * position (its 1-based line and its marker column) to the expected
     * source position.
     */
    private static void checkSidecarMapping(Path outputRoot, String sidecarName,
            int commentGeneratedLine, String commentLine, String commentMarker,
            int sourceLine, int sourceColumn, String context) throws Exception {
        List<int[]> mappings = parseSidecarMappings(
            Files.readString(outputRoot.resolve(sidecarName)));
        int generatedColumn = commentLine.indexOf(commentMarker) + 1;
        boolean mapped = false;
        for (int[] mapping : mappings) {
            mapped = mapping[0] == commentGeneratedLine
                && mapping[1] == generatedColumn
                && mapping[2] == sourceLine
                && mapping[3] == sourceColumn;
            if (mapped) {
                break;
            }
        }
        check(mapped, context + ": " + sidecarName + " maps generated line "
            + commentGeneratedLine + " column " + generatedColumn + " to "
            + "source " + sourceLine + ":" + sourceColumn);
    }

    /**
     * The exhaustive serializer check: a path carrying every C0 control
     * character (U+0000..U+001F, not only the LF/CR/TAB short escapes) must
     * serialize into a sidecar the strict canonical JSON transport parses,
     * with the decoded value equal to the path text.
     */
    private static void verifySerializerEscapesEveryControl() {
        SourceMapGenerator generator = new SourceMapGenerator();
        generator.emitStatement(1, 1, 1, 1);
        StringBuilder source = new StringBuilder("src");
        for (char control = 0; control < 0x20; control++) {
            source.append(control);
        }
        source.append("main.deal");
        String json = generator.toJson(source.toString(),
            "build/out/Main.java");
        Map<String, CanonicalJson.Value> fields = strictSidecarFields(json,
            "the every-C0-control serializer probe");
        if (fields == null) {
            return;
        }
        checkEq(source.toString(), canonicalScalar(fields.get("source")),
            "the serializer escapes every C0 control with the decoded value "
                + "preserved");
    }

    /**
     * Strictly parses one published sidecar through the canonical JSON
     * transport — the repository's strict reader, which rejects a raw control
     * character inside a string, an invalid escape, and malformed syntax —
     * and verifies the pinned version and the decoded source/generated paths
     * against the actual ones.
     */
    private static void verifyStrictSidecarPaths(Path outputRoot,
            String sidecarName, String expectedSource, String expectedGenerated)
            throws Exception {
        Path sidecar = outputRoot.resolve(sidecarName);
        check(Files.isRegularFile(sidecar),
            "the control-path sidecar is published: " + sidecarName);
        if (!Files.isRegularFile(sidecar)) {
            return;
        }
        Map<String, CanonicalJson.Value> fields = strictSidecarFields(
            Files.readString(sidecar, StandardCharsets.UTF_8), sidecarName);
        if (fields == null) {
            return;
        }
        checkEq(1, canonicalScalar(fields.get("version")),
            sidecarName + " carries the pinned version");
        checkEq(expectedSource, canonicalScalar(fields.get("source")),
            sidecarName + " decodes the actual source path");
        checkEq(expectedGenerated, canonicalScalar(fields.get("generated")),
            sidecarName + " decodes the actual generated path");
        check(fields.get("mappings") instanceof CanonicalJson.Arr,
            sidecarName + " carries its mappings array");
    }

    /**
     * The top-level fields of one strictly parsed sidecar, or {@code null}
     * after failing the check when the document is not strict JSON.
     */
    private static Map<String, CanonicalJson.Value> strictSidecarFields(
            String json, String context) {
        CanonicalJson.Obj root;
        try {
            root = (CanonicalJson.Obj) CanonicalJson.parse(json);
        } catch (RuntimeException malformed) {
            fail(context + " is not strict JSON: " + malformed);
            return null;
        }
        Map<String, CanonicalJson.Value> fields = new java.util.LinkedHashMap<>();
        for (CanonicalJson.Entry entry : root.entries()) {
            fields.put(entry.key(), entry.value());
        }
        return fields;
    }

    /** The Java scalar of one canonical JSON value (containers stay as-is). */
    private static Object canonicalScalar(CanonicalJson.Value value) {
        if (value instanceof CanonicalJson.Int integer) {
            return integer.value();
        }
        if (value instanceof CanonicalJson.Str string) {
            return string.value();
        }
        if (value instanceof CanonicalJson.Num number) {
            return number.value();
        }
        if (value instanceof CanonicalJson.Bool bool) {
            return bool.value();
        }
        return null;
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
            write(fail, "src/main.deal", UNREALIZED_CONSTRUCT_SOURCE);
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
    // 11. The produced-project accessor (ISSUE-0694 HR4(b))
    // =========================================================================

    /**
     * The produced-project accessor
     * ({@code conformance-cutover-guards-pins-and-production-source-removal}
     * HR4(b) and the lane project identity audit contract): a production
     * compile exposes the very validated project its one production arm
     * lowered and emitted — the entry artifact's project, over which the
     * lane-level dangling-identity guard runs — the JS arm and a compile
     * that fails before phase 4 record none, and the production source reads
     * the reference back nowhere, so no emission, staging, or comparison
     * step consumes it.
     */
    private static void testProducedProjectAccessor() throws Exception {
        System.out.println("-- the produced-project accessor: the production "
            + "arm exposes the project it emitted; the reference is "
            + "read-only and recorded at most once --");

        // (1) A successful production compile exposes its own project: the
        // entry artifact is the project's entry artifact and the identity
        // guard passes it (the audit's subject is non-empty).
        Path project = Files.createTempDirectory("production-dispatch-produced-");
        ExecutableLoweredProject produced = null;
        try {
            write(project, "deal.json", DEAL_JSON_LUA);
            write(project, "src/lib.deal", LIB_SOURCE);
            write(project, "src/main.deal", SYNC_CALL_SOURCE);

            ArmCompile production = compileWithInvocation(project,
                "src/main.deal", productionInvocation());
            check(production.success(), "the production compile succeeds: "
                + production.orchestrator().diagnostics());
            if (!production.success()) {
                return;
            }
            produced = production.orchestrator().producedProject();
            check(produced != null, "the production compile exposes the "
                + "project it lowered and emitted");
            if (produced == null) {
                return;
            }
            checkEq(SemanticProfile.DEAL_V1_2_INT32,
                produced.semanticProfile(),
                "the produced project is the v1.2 profile");
            check(produced.modules().containsKey(produced.entryModule()),
                "the produced project carries its entry module");
            check(containsReturnOp(produced),
                "the produced project carries RETURN ops (the identity "
                    + "audit's subject is non-empty)");
            String artifactRelativePath = produced.entryModule().path()
                .replace('.', '/') + ".lua";
            Path artifact = project.resolve("out")
                .resolve(artifactRelativePath);
            check(Files.isRegularFile(artifact),
                "the one staged artifact is the produced project's entry "
                    + "artifact: " + artifact);
            if (Files.isRegularFile(artifact)) {
                String chunk = Files.readString(artifact,
                    StandardCharsets.UTF_8);
                check(chunk.contains("__exportSurfaces[\""
                        + produced.entryModule().path() + "\"]"),
                    "the staged artifact carries the produced project's "
                        + "entry surface");
            }
            List<ProducedProjectIdentityAudit.Violation> violations =
                ProducedProjectIdentityAudit.audit(produced);
            check(violations.isEmpty(),
                "the produced project passes the dangling-identity audit: "
                    + violations);
        } finally {
            deleteRecursively(project);
        }

        // (2) The JS arm records none: the third, unchanged pipeline runs no
        // production arm, so the accessor can never surface a JS artifact's
        // project.
        Path js = Files.createTempDirectory("production-dispatch-produced-js-");
        try {
            write(js, "deal.json", """
                {
                  "languageVersion": "1.2",
                  "moduleRoots": ["src"],
                  "output": "out",
                  "backend": "js"
                }
                """);
            write(js, "src/lib.deal", LIB_SOURCE);
            write(js, "src/main.deal", SYNC_CALL_SOURCE);
            ArmCompile arm = compileWithInvocation(js, "src/main.deal",
                productionInvocation());
            check(arm.orchestrator().producedProject() == null,
                "the JS arm records no produced project");
        } finally {
            deleteRecursively(js);
        }

        // (3) A compile that fails before phase 4 records none: there is no
        // project to expose, so the accessor can never surface a
        // half-lowered or rejected project.
        CompilerDiagnostic failedDiagnostic = null;
        Path broken = Files.createTempDirectory(
            "production-dispatch-produced-broken-");
        try {
            write(broken, "deal.json", DEAL_JSON_LUA);
            write(broken, "src/main.deal",
                "export function main(: null { return null }\n");
            ArmCompile arm = compileWithInvocation(broken, "src/main.deal",
                productionInvocation());
            check(!arm.success(), "the parse-error fixture fails the "
                + "compile");
            check(arm.orchestrator().producedProject() == null,
                "a compile that runs no production arm records no project");
            checkEq(0, arm.orchestrator().semanticEmissionCount(),
                "no project emission is recorded for the failing compile");
            failedDiagnostic = arm.orchestrator().diagnostics().stream()
                .findFirst().orElse(null);
        } finally {
            deleteRecursively(broken);
        }

        // (4) The result contract's fail-closed halves: a failed run
        // carrying a produced project and an emitted run carrying none are
        // both refused, so the accessor can never be observed for a run that
        // produced nothing.
        if (produced != null && failedDiagnostic != null) {
            boolean failedRefused = false;
            try {
                new ProductionProjectEmission.Result(
                    ProductionProjectEmission.Outcome.FAILED,
                    List.of(failedDiagnostic), produced, null);
            } catch (IllegalArgumentException expected) {
                failedRefused = true;
            }
            check(failedRefused, "a failed production run refuses a "
                + "produced-project reference");
        }
        boolean emittedRefused = false;
        try {
            new ProductionProjectEmission.Result(
                ProductionProjectEmission.Outcome.EMITTED,
                List.of(), null, "main.lua");
        } catch (IllegalArgumentException expected) {
            emittedRefused = true;
        }
        check(emittedRefused, "an emitted production run refuses a missing "
            + "produced-project reference");

        checkProducedProjectAccessorIsReadOnly();
    }

    /** Whether the project closure carries at least one RETURN op. */
    private static boolean containsReturnOp(ExecutableLoweredProject project) {
        return project.modules().values().stream().anyMatch(unit ->
            unit.ops().stream().anyMatch(op ->
                op.kind() == SemanticOpKind.RETURN));
    }

    /**
     * The read-only wiring of the accessor: in the production source set the
     * token occurs only in the orchestrator — exactly one writer, inside the
     * production arm, refusing a second record — and in the production
     * unit's result record, which carries the reference out once; no
     * emission, staging, or comparison source reads it back.
     */
    private static void checkProducedProjectAccessorIsReadOnly()
            throws Exception {
        Path deal = Path.of("deal");
        check(Files.isDirectory(deal), "the production source root exists");
        if (!Files.isDirectory(deal)) {
            return;
        }
        try (Stream<Path> listing = Files.walk(deal)) {
            for (Path file : listing.filter(Files::isRegularFile).sorted()
                    .toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".java")) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                int occurrences = countOccurrences(source, "producedProject");
                if (name.equals("CompilationOrchestrator.java")
                        || name.equals("ProductionProjectEmission.java")) {
                    check(occurrences > 0, "the accessor surface is declared "
                        + "in " + name);
                } else {
                    checkEq(0, occurrences,
                        "no emission, staging, or comparison source mentions "
                            + "the produced-project reference (" + file + ")");
                }
            }
        }
        String orchestrator = Files.readString(
            Path.of("deal", "module", "CompilationOrchestrator.java"),
            StandardCharsets.UTF_8);
        checkEq(1, countOccurrences(orchestrator,
                "producedProject = Objects.requireNonNull("),
            "the orchestrator assigns the reference at exactly one writer");
        checkEq(2, countOccurrences(orchestrator, "recordProducedProject("),
            "the writer helper has one declaration and one call site");
        check(orchestrator.contains(
                "recordProducedProject(result.producedProject());"),
            "the production arm records the produced project it received");
        check(orchestrator.contains("if (producedProject != null)"),
            "the writer refuses a second record (set exactly once)");
        String emission = Files.readString(
            Path.of("deal", "module", "ProductionProjectEmission.java"),
            StandardCharsets.UTF_8);
        check(emission.contains("ExecutableLoweredProject producedProject,"),
            "the production arm's result carries the read-only project "
                + "reference");
        checkEq(0, countOccurrences(emission, ".producedProject()"),
            "no emission or staging step of the production unit reads the "
                + "reference back");
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
        testProductionSiblingModuleProviderIdentity();
        testProductionSameNameSiblingCollision();
        testProductionContextualCallSiblingCollision();
        testProductionNestedSiblingCollision();
        testProductionDeclaredContextSiblingCollision();
        testConversionOverflowTerminal();
        testAtomicFailureThroughDispatch();
        testFailClosedFamilies();
        testSourceMapDisposition();
        testSourceMapUnsafeSourcePathText();
        testSourceMapControlCharacterPaths();
        testSourceMapDirectEmissionGroups();
        testSourceMapOmittedDefaultOwnerAttribution();
        testSingleArmDispatch();
        testLegacyRejectionStagesNothingWithDumps();
        testRetainedTestScopeEmitters();
        testProducedProjectAccessor();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
