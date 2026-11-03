package deal.test;

import deal.codegen.Backend;
import deal.diagnostics.CompilerDiagnostic;
import deal.module.CompilationOrchestrator;
import deal.project.CliOverrides;
import deal.project.ProjectContext;
import deal.project.ProjectLocator;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.toolchain.NativeBuildRequest;
import deal.toolchain.NativeToolchain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The native GC stress suite ({@code llvm-native-conformance-gate} G6,
 * {@code llvm-native-memory-management} M1-M8 Verification 1-13).
 *
 * <p>Each program is a small DEAL project compiled through the production
 * {@link Backend#LLVM} arm into its own publication root; the driver shape of
 * {@link NativeToolchain} then builds one retained executable per optimization
 * level, and that executable is run twice: once normally and once with
 * {@code DEAL_GC_STRESS=1}. Both runs must produce byte-identical stdout,
 * stderr and exit status, and the stress run is the in-activation collection
 * that exposes a missing root, a missing insertion barrier or a lifetime
 * defect: a wrong value is the program's own self-check failure, never an
 * accepted observation.</p>
 *
 * <p>The programs drive collection timing from inside a DEAL activation through
 * the compile-in test host {@code host/gc} ({@code test/native/gc_control.c}):
 * {@code collect()} runs one complete incremental cycle and the accessors expose
 * the runtime's own statistics (M7), so a program asserts directly that a fully
 * live heap reclaims nothing and that dropped cycles are reclaimed. Collection
 * statistics also reach the suite through the generated driver, which writes
 * them to a side file — never into the program's stdout, so the normal and
 * stress runs stay byte-identical.</p>
 */
public final class LlvmGcStressTest {

    // =========================================================================
    // Program model
    // =========================================================================

    /**
     * One stress program: its name, its DEAL source, the exports the driver
     * invokes in declaration order, and its exact stdout.
     */
    private record Program(String name, String source, List<String> invocations,
                           List<String> asyncInvocations, List<String> hosts,
                           String stdout) { }

    /**
     * One compile-in test host: its declaration text, its C unit and the
     * translation-unit prefix its behavior selector needs.
     */
    private record TestHost(String specifier, String declaration, String unit,
                            String prefix) { }

    /**
     * The two compile-in hosts of the suite. {@code host/gc} drives collection
     * timing and statistics from inside an activation (M7); {@code host/gccross}
     * builds a host graph, retains a handle and exercises the relay/discard
     * choice (H2/H3).
     */
    private static final java.util.Map<String, TestHost> HOSTS = java.util.Map.of(
        "gc", new TestHost("host/gc", """
            export function collect(): null;
            export function sweptObjects(): number;
            export function collections(): number;
            export function allocatedBytes(): number;
            """, "test/native/gc_control.c", ""),
        "gccross", new TestHost("host/gccross", """
            export class Holder {
              label: string;
              count: number;
              tags: string[];
              record: Record<string, unknown>;
            }

            export function makeHolder(label: string): Holder;
            export function retain(holder: Holder): null;
            export function retainedLabel(): string;
            export function release(): null;
            export function swallow(f: () => number): number;
            export function relay(f: () => number): number;
            """, "test/native/gc_crossing.c", ""),
        "async-service", new TestHost("host/async", """
            export function pending(): Promise<number>;
            """, "test/native/async_probe.c", "#define DEAL_HOST_ASYNC_CASE 1\n"),
        "async-thread", new TestHost("host/async", """
            export function pending(): Promise<number>;
            """, "test/native/async_probe.c", "#define DEAL_HOST_ASYNC_CASE 2\n#define DEAL_HOST_POST_DURING_COLLECTION 1\n"),
        "async-record", new TestHost("host/async", """
            export class Payload {
              value: number;
            }

            export function arm(callback: () => null): null;
            export function pendingManaged(): Promise<Payload>;
            """, "test/native/async_probe.c", "#define DEAL_HOST_ASYNC_CASE 15\n"));

    // =========================================================================
    // Counters and entry point
    // =========================================================================

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Native GC Stress Suite (llvm-native-memory-management "
            + "M1-M8, conformance gate G6) ===");

        Path checkout = NativeCompiler.repository();
        if (!Files.isDirectory(checkout.resolve("deal/native"))) {
            System.out.println("GATE FAILURE: the C11 runtime source tree is "
                + "absent (deal/native); the native stress suite cannot run");
            System.exit(2);
        }
        NativeCompiler.compiler();

        Path scratch = NativeCompiler.executableScratch("llvm-gc-stress");
        try {
            int cases = 0;
            for (Program program : programs()) {
                cases += 2;
                runProgram(program, scratch);
            }
            System.out.println();
            System.out.println("GC stress suite: " + passed + " passed, "
                + failed + " failed (" + cases + " program/level cases)");
        } finally {
            deleteTree(scratch);
        }
        if (failed != 0) {
            System.out.println("GC stress suite: FAIL");
            System.exit(1);
        }
        System.out.println("GC stress suite: PASS");
    }

    /**
     * The compile-in host unit of the {@code host/gc} declaration, with the
     * declaration module's own stem so the generated registry pairs it with the
     * import (H1).
     */
    /**
     * The host units of one program. Each unit's file stem is its imported
     * declaration module's stem ({@code gc}, {@code gccross}, {@code async}),
     * so the generated registry pairs it with that import (H1).
     */
    private static java.util.Map<String, Path> hostUnits(Path scratch,
            Program program) throws IOException {
        java.util.Map<String, Path> units = new LinkedHashMap<>();
        for (String host : program.hosts()) {
            TestHost testHost = HOSTS.get(host);
            String stem = testHost.specifier()
                .substring(testHost.specifier().lastIndexOf('/') + 1);
            Path unit = scratch.resolve("hosts").resolve(program.name())
                .resolve(stem + ".c");
            Files.createDirectories(unit.getParent());
            Files.writeString(unit, testHost.prefix() + Files.readString(
                NativeCompiler.repository().resolve(testHost.unit()),
                StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            units.put(host, unit);
        }
        return units;
    }

    // =========================================================================
    // One program at both optimization levels
    // =========================================================================

    private static void runProgram(Program program, Path scratch)
            throws Exception {
        Path root = scratch.resolve("programs").resolve(program.name());
        Path published = compile(program, root);
        java.util.Map<String, Path> hostUnits = hostUnits(scratch, program);
        for (int level : new int[] {0, 2}) {
            String label = program.name() + " -O" + level;
            try {
                checkProgram(program, root, published, hostUnits, level);
                passed += 1;
                System.out.println("  [" + label + "] OK");
            } catch (AssertionError e) {
                failed += 1;
                System.out.println("  [" + label + "] FAIL: " + e.getMessage());
            }
        }
    }

    private static void checkProgram(Program program, Path root, Path published,
            java.util.Map<String, Path> hostUnits, int level) throws Exception {
        Path productDirectory = root.resolve("product-" + level);
        Files.createDirectories(productDirectory);
        Path product = productDirectory.resolve("driver");
        Path statsPath = productDirectory.resolve("statistics.txt");
        Files.deleteIfExists(statsPath);
        String driver = driverSource(program, statsPath);
        List<Path> units = new java.util.ArrayList<>();
        for (String host : program.hosts()) {
            units.add(hostUnits.get(host));
        }
        try {
            NativeCompiler.build(new NativeBuildRequest(published, product, level,
                units, driver));
        } catch (deal.toolchain.NativeBuildException e) {
            throw new AssertionError("the toolchain seam rejected the driver "
                + "build: " + e.getMessage(), e);
        }
        if (!Files.isExecutable(product)) {
            throw new AssertionError("the retained driver product is not an "
                + "executable outside the publication root");
        }

        NativeCompiler.Result normal = NativeCompiler.execute(product,
            productDirectory, Map.of());
        NativeCompiler.Result stress = NativeCompiler.execute(product,
            productDirectory, Map.of("DEAL_GC_STRESS", "1"));

        // 1. Both runs satisfy the program's own checks and its exact output.
        assertRun("normal", normal, program);
        assertRun("stress", stress, program);
        // 2. Collection timing alone changes nothing observable (M7).
        checkEqual("stdout", program, normal.stdout(), stress.stdout());
        checkEqual("stderr", program, normal.stderr(), stress.stderr());
        if (normal.exitCode() != stress.exitCode()) {
            throw new AssertionError(program.name() + ": exit status differs "
                + "between the normal and stress runs (" + normal.exitCode()
                + " vs " + stress.exitCode() + ")");
        }

        // 3. The driver's own statistics observation (M7 Verification 8).
        Map<String, Map<String, Long>> statistics = parseStatistics(statsPath);
        Map<String, Long> start = require(statistics, "start");
        Map<String, Long> collected = require(statistics, "collected");
        Map<String, Long> last = require(statistics, "final");
        if (get(last, "allocated_bytes") <= get(start, "allocated_bytes")) {
            throw new AssertionError(program.name() + ": allocation statistics "
                + "did not advance");
        }
        if (get(last, "collections") < 3) {
            throw new AssertionError(program.name() + ": the run completed "
                + get(last, "collections") + " collections, expected at least 3");
        }
        long idle = get(last, "swept_objects") - get(collected, "swept_objects");
        if (idle != 0) {
            throw new AssertionError(program.name() + ": a second full "
                + "collection over a fully live heap reclaimed " + idle
                + " object(s)");
        }
    }

    private static void assertRun(String run, NativeCompiler.Result result,
            Program program) {
        if (result.exitCode() != 0) {
            throw new AssertionError(program.name() + ": the " + run
                + " run exited " + result.exitCode() + ", stderr \""
                + result.stderr().replace("\n", "\\n") + "\"");
        }
        if (!result.stderr().isEmpty()) {
            throw new AssertionError(program.name() + ": the " + run
                + " run wrote to stderr: "
                + result.stderr().replace("\n", "\\n"));
        }
        if (!result.stdout().equals(program.stdout())) {
            throw new AssertionError(program.name() + ": the " + run
                + " run printed \"" + result.stdout().replace("\n", "\\n")
                + "\", expected \"" + program.stdout().replace("\n", "\\n")
                + "\"");
        }
    }

    private static void checkEqual(String what, Program program, String a,
            String b) {
        if (!a.equals(b)) {
            throw new AssertionError(program.name() + ": " + what
                + " differs between the normal and stress runs (\""
                + a.replace("\n", "\\n") + "\" vs \""
                + b.replace("\n", "\\n") + "\")");
        }
    }

    private static Map<String, Long> require(
            Map<String, Map<String, Long>> statistics, String tag) {
        Map<String, Long> record = statistics.get(tag);
        if (record == null) {
            throw new AssertionError("the driver wrote no '" + tag
                + "' collection statistics");
        }
        return record;
    }

    private static long get(Map<String, Long> record, String field) {
        Long value = record.get(field);
        if (value == null) {
            throw new AssertionError("the collection statistics carry no '"
                + field + "' field");
        }
        return value;
    }

    private static Map<String, Map<String, Long>> parseStatistics(Path path)
            throws IOException {
        Map<String, Map<String, Long>> statistics = new LinkedHashMap<>();
        if (!Files.isRegularFile(path)) {
            throw new AssertionError("the driver wrote no collection statistics");
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String[] fields = line.trim().split("\\s+");
            Map<String, Long> record = new LinkedHashMap<>();
            for (int index = 1; index < fields.length; index += 1) {
                int equals = fields[index].indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                record.put(fields[index].substring(0, equals),
                    Long.parseLong(fields[index].substring(equals + 1)));
            }
            statistics.put(fields[0], record);
        }
        return statistics;
    }

    // =========================================================================
    // The production compile
    // =========================================================================

    private static Path compile(Program program, Path root) throws IOException {
        Path source = root.resolve("src/main.deal");
        Files.createDirectories(source.getParent());
        Files.writeString(source, program.source(), StandardCharsets.UTF_8);
        StringBuilder externals = new StringBuilder();
        for (String host : program.hosts()) {
            TestHost testHost = HOSTS.get(host);
            Path declaration = root.resolve("bindings/" + host + ".d.deal");
            Files.createDirectories(declaration.getParent());
            Files.writeString(declaration, testHost.declaration(),
                StandardCharsets.UTF_8);
            externals.append(externals.length() == 0 ? "" : ", ")
                .append("\"").append(testHost.specifier()).append("\": {")
                .append("\"declaration\": \"bindings/").append(host)
                .append(".d.deal\"}");
        }
        String externalText = externals.length() == 0 ? ""
            : ", \"externals\": {" + externals + "}";
        Files.writeString(root.resolve("deal.json"),
            "{\"languageVersion\": \"1.5\", \"stdlib\": \"1.5\","
                + " \"moduleRoots\": [\"src\"], \"backend\": \"llvm\""
                + externalText + "}\n",
            StandardCharsets.UTF_8);

        ProjectLocator.LocateResult located = ProjectLocator.locate(
            source.toAbsolutePath().toString(),
            new CliOverrides(Backend.LLVM.cliName(), null));
        if (located.context() == null) {
            throw new AssertionError("the " + program.name()
                + " project did not resolve: "
                + (located.e2010() != null ? located.e2010().message() : "")
                + (located.cliDiagnostic() != null
                    ? located.cliDiagnostic().message() : ""));
        }
        ProjectContext context = located.context();
        CompilerInvocation invocation = CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            context, source.toAbsolutePath().normalize(), false, false, false,
            false, null, invocation);
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        boolean success;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            success = orchestrator.compile();
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        if (!success) {
            StringBuilder diagnostics = new StringBuilder();
            for (CompilerDiagnostic diagnostic : orchestrator.diagnostics()) {
                diagnostics.append(diagnostic.message()).append('\n');
            }
            throw new AssertionError("the " + program.name()
                + " project failed to compile: " + diagnostics
                + captured.toString(StandardCharsets.UTF_8));
        }
        Path published = Path.of(context.outputPath().absoluteNormalizedPath());
        if (!Files.isRegularFile(published.resolve("main.ll"))) {
            throw new AssertionError("the " + program.name()
                + " compile published no textual module");
        }
        boolean headers = true;
        for (String host : program.hosts()) {
            headers = headers && Files.isRegularFile(
                published.resolve(HOSTS.get(host).specifier() + ".h"));
        }
        if (!Files.isRegularFile(published.resolve("deal/native/main.c"))
                || !headers) {
            throw new AssertionError("the " + program.name()
                + " compile published no complete runtime deployment set or "
                + "generated host header");
        }
        return published;
    }

    // =========================================================================
    // The generated driver
    // =========================================================================

    private static String driverSource(Program program, Path statsPath) {
        StringBuilder source = new StringBuilder();
        source.append("#include \"deal/native/entry.h\"\n");
        source.append("#include \"deal/native/memory.h\"\n");
        source.append("#include <stdio.h>\n\n");
        source.append("static const char *stats_path = ")
            .append(cString(statsPath.toAbsolutePath().normalize().toString()))
            .append(";\n\n");
        source.append("""
            static void write_stats(const char *tag) {
                deal_gc_statistics_record record;
                deal_gc_statistics(&record);
                FILE *file = fopen(stats_path, "a");
                if (file == NULL) {
                    fprintf(stderr, "statistics log unavailable\\n");
                    return;
                }
                fprintf(file, "%s live_bytes=%llu object_count=%llu "
                    "allocated_bytes=%llu collections=%llu swept_objects=%llu\\n",
                    tag, (unsigned long long)record.live_bytes,
                    (unsigned long long)record.object_count,
                    (unsigned long long)record.allocated_bytes,
                    (unsigned long long)record.collections,
                    (unsigned long long)record.swept_objects);
                fclose(file);
            }

            static int report(deal_error *error) {
                const char *code = NULL;
                const char *message = NULL;
                const char *file = NULL;
                int32_t line = 0;
                int32_t column = 0;
                deal_error_code(error, &code);
                deal_error_message(error, &message);
                deal_error_source_file(error, &file);
                deal_error_line(error, &line);
                deal_error_column(error, &column);
                fprintf(stderr, "carried error %s: %s (%s:%d:%d)\\n",
                    code == NULL ? "-" : code,
                    message == NULL ? "-" : message,
                    file == NULL ? "-" : file, (int)line, (int)column);
                return 3;
            }

            static int invoke(const char *name, int async) {
                deal_module *module = deal_project_exports("main");
                if (module == NULL) {
                    fprintf(stderr, "missing module\\n");
                    return 4;
                }
                deal_export_entry *entry = deal_export_lookup(module, name);
                if (entry == NULL) {
                    fprintf(stderr, "missing export %s\\n", name);
                    return 4;
                }
                deal_outcome outcome = async
                    ? deal_async_export_invoke(entry)
                    : deal_export_invoke(entry);
                return outcome.error == NULL ? 0 : report(outcome.error);
            }

            int main(void) {
                write_stats("start");
                deal_outcome started = deal_project_start();
                if (started.error != NULL) {
                    return report(started.error);
                }
            """);
        source.append("    static const struct { const char *name;"
            + " int async; } invocations[] = {");
        for (int index = 0; index < program.invocations().size(); index += 1) {
            String name = program.invocations().get(index);
            source.append(index == 0 ? "" : ", ")
                .append("{" + cString(name) + ", ")
                .append(program.asyncInvocations().contains(name) ? "1" : "0")
                .append("}");
        }
        source.append("};\n");
        source.append("""
                for (unsigned index = 0;
                        index < sizeof invocations / sizeof invocations[0];
                        index += 1) {
                    int status = invoke(invocations[index].name,
                        invocations[index].async);
                    if (status != 0) {
                        return status;
                    }
                }
                deal_gc_collect_full();
                write_stats("collected");
                deal_gc_collect_full();
                write_stats("final");
                return 0;
            }
            """);
        return source.toString();
    }

    private static String cString(String text) {
        StringBuilder quoted = new StringBuilder("\"");
        for (int index = 0; index < text.length(); index += 1) {
            char c = text.charAt(index);
            switch (c) {
                case '\\' -> quoted.append("\\\\");
                case '"' -> quoted.append("\\\"");
                case '\n' -> quoted.append("\\n");
                default -> quoted.append(c);
            }
        }
        return quoted.append('"').toString();
    }

    private static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort: the scratch directory is the suite's own.
                }
            });
        } catch (IOException ignored) {
            // Best effort: the scratch directory is the suite's own.
        }
    }

    // =========================================================================
    // The programs
    // =========================================================================

    private static List<Program> programs() {
        return List.of(
            new Program("live-values", LIVE_VALUES_SOURCE,
                List.of("test_live_values"), List.of(), List.of("gc"), ""),
            new Program("captured-cells", CAPTURED_CELLS_SOURCE,
                List.of("test_captured_cells"), List.of(), List.of("gc"), ""),
            new Program("barrier-insertion", BARRIER_SOURCE,
                List.of("test_barrier_insertion"), List.of(), List.of("gc"), ""),
            new Program("cycle-reclamation", CYCLE_SOURCE,
                List.of("test_cycle_reclamation"), List.of(), List.of("gc"), ""),
            new Program("errors-across-collection", ERROR_SOURCE,
                List.of("test_error_identity"), List.of(), List.of("gc"), ""),
            new Program("host-graph", HOST_GRAPH_SOURCE,
                List.of("test_host_graph"), List.of(),
                List.of("gc", "gccross"), ""),
            new Program("host-handle", HOST_HANDLE_SOURCE,
                List.of("test_host_handle"), List.of(),
                List.of("gc", "gccross"), ""),
            new Program("error-crossing", ERROR_CROSSING_SOURCE,
                List.of("test_error_crossing"), List.of(),
                List.of("gc", "gccross"), ""),
            new Program("async-service-hook", ASYNC_SERVICE_SOURCE,
                List.of("test_async_completion"), List.of("test_async_completion"),
                List.of("gc", "async-service"), ""),
            new Program("async-thread-poster", ASYNC_THREAD_SOURCE,
                List.of("test_async_completion"), List.of("test_async_completion"),
                List.of("gc", "async-thread"), ""),
            new Program("open-operation-record", ASYNC_RECORD_SOURCE,
                List.of("test_arm", "test_delivered"), List.of("test_delivered"),
                List.of("gc", "async-record"), ""));
    }

    private static final String LIVE_VALUES_SOURCE = """
        // Managed values that stay live only through locals, unknown aliases
        // and inferred reads across an in-activation collection (M4, M5).
        import * as gc from "host/gc";

        class Node {
          name: string;
          next: Node | null;
        }

        function make(name: string): Node {
          return { name: name, next: null };
        }

        export function test_live_values(): number {
          gc.collect();
          let before: number = gc.sweptObjects();
          let a: Node = make("a");
          let b: Node = make("b");
          a.next = b;
          let rows: Node[] = [make("r0"), make("r1"), make("r2")];
          let holder: unknown = a;
          gc.collect();
          let after: number = gc.sweptObjects();
          if (after !== before) {
            throw { code: "TEST_FAIL", message: "a fully live heap reclaimed objects" };
          }
          let same: Node = holder as Node;
          if (same !== a) {
            throw { code: "TEST_FAIL", message: "unknown lost class identity" };
          }
          let following: Node = a.next as Node;
          if (following !== b) {
            throw { code: "TEST_FAIL", message: "class field identity changed" };
          }
          if (rows.length !== 3) {
            throw { code: "TEST_FAIL", message: "array elements were reclaimed" };
          }
          let total: number = 0;
          for (let row of rows) {
            let name: string = row.name;
            if (name !== "r0" && name !== "r1" && name !== "r2") {
              throw { code: "TEST_FAIL", message: "element value changed" };
            }
            total = total + 1;
          }
          return total;
        }

        export function main(): null {
          return null;
        }
        """;

    private static final String CAPTURED_CELLS_SOURCE = """
        // Captured mutable bindings keep one cell per binding generation and
        // survive a full collection (M6, Verification 5). A captured catch
        // binding is one cell too: its incarnation carries the caught Error
        // itself, so a closure over the catch binding and the direct name read
        // the same value across full collections (C5, C4).
        import * as gc from "host/gc";

        function errorOf(value: unknown): Error { return value as Error; }

        export function test_captured_cells(): number {
          let readers: (() => number)[] = [];
          for (let i of [0, 1, 2]) {
            readers[readers.length] = function (): number { return i; };
          }
          let counter: number = 0;
          let bump: () => number = function (): number {
            counter = counter + 1;
            return counter;
          };
          let alias: () => number = bump;
          let read: () => string = function (): string { return ""; };
          let held: Error | null = null;
          try {
            throw { code: "E_CELL", message: "caught" };
          } catch (e) {
            read = function (): string { return errorOf(e).message; };
            held = e as Error;
          }
          if (held === null) {
            throw { code: "TEST_FAIL", message: "the caught Error was dropped" };
          }
          let caught: Error = held as Error;
          gc.collect();
          let before: number = gc.sweptObjects();
          gc.collect();
          let after: number = gc.sweptObjects();
          if (after !== before) {
            throw { code: "TEST_FAIL", message: "live cells were reclaimed" };
          }
          if (readers[0]() !== 0 || readers[1]() !== 1 || readers[2]() !== 2) {
            throw { code: "TEST_FAIL", message: "iteration cells were shared" };
          }
          if (bump() !== 1 || alias() !== 2) {
            throw { code: "TEST_FAIL", message: "cell aliases diverged" };
          }
          if (read() !== "caught") {
            throw { code: "TEST_FAIL", message: "the captured catch cell was replaced" };
          }
          if (caught.code !== "E_CELL" || caught.message !== "caught") {
            throw { code: "TEST_FAIL", message: "the caught Error changed" };
          }
          return counter;
        }

        export function main(): null {
          return null;
        }
        """;

    private static final String BARRIER_SOURCE = """
        // The insertion barrier: a fresh object stored into an already-live
        // managed container stays alive across a collection (M2, Verification 4).
        import * as gc from "host/gc";

        class Box {
          value: number;
        }

        export function test_barrier_insertion(): number {
          let previous: Box[] = [{ value: -1 }, { value: -1 }, { value: -1 }];
          let boxes: Box[] = [previous[0], previous[1], previous[2]];
          let table: Record<string, unknown> = {};
          table["boxes"] = boxes;
          gc.collect();
          let before: number = gc.sweptObjects();
          let fresh: Box[] = [{ value: 0 }, { value: 1 }, { value: 2 }];
          boxes[0] = fresh[0];
          boxes[1] = fresh[1];
          boxes[2] = fresh[2];
          gc.collect();
          let after: number = gc.sweptObjects();
          if (after !== before) {
            throw { code: "TEST_FAIL", message: "shaded stores were reclaimed" };
          }
          let projected: Box[] = table["boxes"] as Box[];
          if (projected.length !== 3) {
            throw { code: "TEST_FAIL", message: "record slot length changed" };
          }
          let i: number = 0;
          while (i < 3) {
            let read: Box = projected[i];
            if (read.value !== i) {
              throw { code: "TEST_FAIL", message: "stored value changed" };
            }
            if (read !== fresh[i]) {
              throw { code: "TEST_FAIL", message: "stored identity changed" };
            }
            if (previous[i].value !== -1) {
              throw { code: "TEST_FAIL", message: "replaced value changed" };
            }
            i = i + 1;
          }
          return projected.length;
        }

        export function main(): null {
          return null;
        }
        """;

    private static final String CYCLE_SOURCE = """
        // Cycle reclamation: table, array and class cycles built and dropped in
        // one activation are reclaimed by the program's own full collection,
        // while a live self-reference survives (M1, Verification 2 and 3).
        import * as gc from "host/gc";

        class Cycle {
          self: Cycle | null;
        }

        export function test_cycle_reclamation(): number {
          gc.collect();
          let before: number = gc.sweptObjects();
          let kept: Cycle = { self: null };
          kept.self = kept;
          let i: number = 0;
          while (i < 20) {
            let node: Cycle = { self: null };
            node.self = node;
            let array: unknown[] = [];
            array[0] = array;
            let table: Record<string, unknown> = {};
            table["self"] = table;
            table["array"] = array;
            i = i + 1;
          }
          gc.collect();
          let after: number = gc.sweptObjects();
          if (after <= before) {
            throw { code: "TEST_FAIL", message: "the dropped cycles were not reclaimed" };
          }
          let self: Cycle = kept.self as Cycle;
          if (self !== kept) {
            throw { code: "TEST_FAIL", message: "the live cycle lost identity" };
          }
          return after - before;
        }

        export function main(): null {
          return null;
        }
        """;

    private static final String ERROR_SOURCE = """
        // Error identity, current fields, origin and frames survive a full
        // collection between the catch and the rethrow (M5, Verification 6).
        import * as gc from "host/gc";

        export function test_error_identity(): number {
          let kept: Error | null = null;
          try {
            throw { code: "E_KEPT", message: "kept" };
          } catch (e) {
            let caught: unknown = e;
            let error: Error = caught as Error;
            error.message = "mutated";
            kept = error;
          }
          if (kept === null) {
            throw { code: "TEST_FAIL", message: "the caught Error was dropped" };
          }
          let current: Error = kept as Error;
          gc.collect();
          if (current.code !== "E_KEPT" || current.message !== "mutated") {
            throw { code: "TEST_FAIL", message: "Error fields changed" };
          }
          let rethrown: Error | null = null;
          try {
            throw current;
          } catch (e) {
            let caught: unknown = e;
            rethrown = caught as Error;
          }
          if (rethrown === null) {
            throw { code: "TEST_FAIL", message: "the rethrown Error was dropped" };
          }
          let again: Error = rethrown as Error;
          if (again !== current) {
            throw { code: "TEST_FAIL", message: "rethrow replaced the Error" };
          }
          if (again.code !== "E_KEPT" || again.message !== "mutated") {
            throw { code: "TEST_FAIL", message: "rethrow changed the fields" };
          }
          return 1;
        }

        export function main(): null {
          return null;
        }
        """;

    private static final String HOST_GRAPH_SOURCE = """
        // A host-built class/table/array graph is materialized at the return
        // crossing and survives full collections before and after DEAL stores
        // it (M5 Verification 10, R11). The record member is the host-built
        // table carrier itself: its own identity, its insertion-ordered members
        // and its alias of the class's array carrier are all observed across
        // collections.
        import * as gc from "host/gc";
        import * as gccross from "host/gccross";

        export function test_host_graph(): number {
          let holder: gccross.Holder = gccross.makeHolder("built");
          let record: Record<string, unknown> = holder.record;
          gc.collect();
          let label: string = holder.label;
          if (label !== "built") {
            throw { code: "TEST_FAIL", message: "host graph label changed" };
          }
          let tags: string[] = holder.tags;
          if (tags.length !== 2 || tags[0] !== "alpha" || tags[1] !== "beta") {
            throw { code: "TEST_FAIL", message: "host graph array changed" };
          }
          let alpha: number = record["alpha"] as number;
          if (alpha !== 3) {
            throw { code: "TEST_FAIL", message: "host-built table member changed" };
          }
          let beta: string = record["beta"] as string;
          if (beta !== "two") {
            throw { code: "TEST_FAIL", message: "host-built table text changed" };
          }
          let aliased: string[] = record["tags"] as string[];
          if (aliased !== tags) {
            throw { code: "TEST_FAIL", message: "host graph alias identity changed" };
          }
          let nested: Record<string, unknown> = record["nested"] as Record<string, unknown>;
          let deep: number = nested["deep"] as number;
          if (deep !== 1) {
            throw { code: "TEST_FAIL", message: "nested host table changed" };
          }
          let table: Record<string, unknown> = {};
          table["holder"] = holder;
          table["record"] = record;
          gc.collect();
          let stored: gccross.Holder = table["holder"] as gccross.Holder;
          if (stored !== holder) {
            throw { code: "TEST_FAIL", message: "stored graph lost identity" };
          }
          if (stored.count !== 2) {
            throw { code: "TEST_FAIL", message: "host graph field changed" };
          }
          let storedRecord: Record<string, unknown> = table["record"] as Record<string, unknown>;
          if (storedRecord !== record) {
            throw { code: "TEST_FAIL", message: "stored table lost identity" };
          }
          let again: number = storedRecord["alpha"] as number;
          if (again !== 3) {
            throw { code: "TEST_FAIL", message: "stored table member changed" };
          }
          let kept: gccross.Holder[] = [stored];
          gc.collect();
          if (kept[0].label !== "built") {
            throw { code: "TEST_FAIL", message: "kept graph changed" };
          }
          if (kept[0].record !== record) {
            throw { code: "TEST_FAIL", message: "kept table lost identity" };
          }
          let keptDeep: Record<string, unknown> = (kept[0].record)["nested"] as Record<string, unknown>;
          if ((keptDeep["deep"] as number) !== 1) {
            throw { code: "TEST_FAIL", message: "kept nested table changed" };
          }
          return kept.length;
        }

        export function main(): null {
          return null;
        }
        """;

    private static final String HOST_HANDLE_SOURCE = """
        // A host-retained handle roots a DEAL value across repeated full
        // collections, and releases it afterwards (H3, M5 Verification 6).
        import * as gc from "host/gc";
        import * as gccross from "host/gccross";

        export function test_host_handle(): number {
          let holder: gccross.Holder = gccross.makeHolder("retained");
          gccross.retain(holder);
          gc.collect();
          gc.collect();
          let label: string = gccross.retainedLabel();
          if (label !== "retained") {
            throw { code: "TEST_FAIL", message: "the retained value was reclaimed" };
          }
          gccross.release();
          gc.collect();
          if (holder.label !== "retained") {
            throw { code: "TEST_FAIL", message: "the DEAL value changed" };
          }
          return 1;
        }

        export function main(): null {
          return null;
        }
        """;

    private static final String ERROR_CROSSING_SOURCE = """
        // The relay and discard choices of the DEAL-origin failure channel,
        // with collections around the crossing (H2, M5 Verification 12).
        import * as gc from "host/gc";
        import * as gccross from "host/gccross";

        export function test_error_crossing(): number {
          let kept: Error | null = null;
          try {
            throw { code: "E_DISCARD", message: "discarded" };
          } catch (e) {
            let caught: unknown = e;
            kept = caught as Error;
          }
          if (kept === null) {
            throw { code: "TEST_FAIL", message: "the thrown Error was dropped" };
          }
          let current: Error = kept as Error;
          let thrower: () => number = function (): number { throw current; };
          gc.collect();
          let swallowed: number = gccross.swallow(thrower);
          if (swallowed !== 7) {
            throw { code: "TEST_FAIL", message: "the discarded failure propagated" };
          }
          gc.collect();
          let caught: Error | null = null;
          try {
            gccross.relay(thrower);
          } catch (e) {
            let next: unknown = e;
            caught = next as Error;
          }
          if (caught === null) {
            throw { code: "TEST_FAIL", message: "the relayed failure propagated nothing" };
          }
          let relayed: Error = caught as Error;
          if (relayed !== current) {
            throw { code: "TEST_FAIL", message: "the relayed Error was replaced" };
          }
          if (relayed.code !== "E_DISCARD" || relayed.message !== "discarded") {
            throw { code: "TEST_FAIL", message: "the relayed Error changed" };
          }
          return 1;
        }

        export function main(): null {
          return null;
        }
        """;

    private static final String ASYNC_SERVICE_SOURCE = """
        // A pending operation's completion is delivered by the module service
        // hook on the mutator thread, and the awaiting activation observes it
        // across full collections (H5, M5 Verification 9 and 11).
        import * as gc from "host/gc";
        import * as probe from "host/async";

        export async function test_async_completion(): Promise<number> {
          gc.collect();
          let value: number = await probe.pending();
          gc.collect();
          if (value !== 21) {
            throw { code: "TEST_FAIL", message: "completion value changed" };
          }
          return value;
        }

        export function main(): null {
          return null;
        }
        """;

    private static final String ASYNC_THREAD_SOURCE = """
        // A completion posted from a second thread while the mutator collects is
        // delivered on the waiting mutator thread and survives (H5, M3, M5
        // Verification 9).
        import * as gc from "host/gc";
        import * as probe from "host/async";

        export async function test_async_completion(): Promise<number> {
          gc.collect();
          let value: number = await probe.pending();
          gc.collect();
          if (value !== 21) {
            throw { code: "TEST_FAIL", message: "posted completion changed" };
          }
          return value;
        }

        export function main(): null {
          return null;
        }
        """;

    private static final String ASYNC_RECORD_SOURCE = """
        // The open-operation record roots a *delivered* completion until the
        // consuming AWAIT reads it (M5 `OpenOperationRecord`, Verification 11).
        // The module's service hook completes the pending operation with a
        // managed class instance and then re-enters the DEAL callback armed
        // below, whose body runs one complete collection while the delivered
        // value is still only reachable through the record's completion slot
        // and the pump has not re-checked the operation yet. A record that does
        // not root that slot loses the instance before the AWAIT consumes it.
        import * as gc from "host/gc";
        import * as async from "host/async";

        function collectNow(): null {
          gc.collect();
          return null;
        }

        export function test_arm(): null {
          async.arm(collectNow);
          return null;
        }

        export async function test_delivered(): Promise<null> {
          let delivered: async.Payload = await async.pendingManaged();
          if (delivered.value !== 42) {
            throw { code: "TEST_FAIL", message: "the delivered completion value" };
          }
          let alias: unknown = delivered;
          gc.collect();
          let again: async.Payload = alias as async.Payload;
          if (again !== delivered) {
            throw { code: "TEST_FAIL", message: "the delivered completion identity" };
          }
          if (again.value !== 42) {
            throw { code: "TEST_FAIL", message: "the consumed completion value" };
          }
          return null;
        }

        export function main(): null {
          return null;
        }
        """;
}
