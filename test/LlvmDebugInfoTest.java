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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The dedicated two-module cross-module diagnostics and debug-information case
 * ({@code llvm-native-conformance-gate} G7 and Verification 6,
 * {@code llvm-native-codegen-cfg-ssa} C8 and Verification 6).
 *
 * <p>One two-module project runs its failure through the second module: the
 * runtime error's {@code sourceFile}, line and column name the second module's
 * own source coordinate. The same project's built product must carry DWARF that
 * {@code llvm-dwarfdump} reads as both constituent source files with their
 * declared lines and columns, and one {@code DISubprogram} per generated
 * function whose {@code DW_AT_name} is the DEAL source name and whose
 * {@code DW_AT_linkage_name} is the generated {@code F<id>}.</p>
 *
 * <p>Both optimization levels must satisfy the same assertions. The pinned
 * coordinates are read from the fixture source itself (the declaration line and
 * the failing expression's column), never from a captured run.</p>
 */
public final class LlvmDebugInfoTest {

    /** The main module: it calls the second module's export through a
     * name-less function expression, whose {@code DISubprogram} carries the
     * generated {@code F<id>} in both name fields (C8). */
    private static final String MAIN_SOURCE = """
        import * as lib from "./lib";

        export function main(): null {
          let value: number = lib.compute(0);
          let bump: (n: number) => number = function (n: number): number {
            return n + 1;
          };
          let next: number = bump(value);
          return null;
        }
        """;

    /** The second module: its array read fails at its own source coordinate. */
    private static final String LIB_SOURCE = """
        export function compute(seed: number): number {
          let values: number[] = [seed, 1, 2];
          return values[-1];
        }
        """;

    /** The second module's declared function line (1-based). */
    private static final int LIB_DECL_LINE = 1;

    /** The second module's failing expression line and column (1-based). */
    private static final int LIB_ERROR_LINE = 3;
    private static final int LIB_ERROR_COLUMN = 10;

    /** The main module's call line and the read of the imported export. */
    private static final int MAIN_CALL_LINE = 4;
    private static final int MAIN_CALL_COLUMN = 23;

    /** The main module's declared entry function line. */
    private static final int MAIN_DECL_LINE = 3;

    /** True when a name is the generated {@code F<id>}/type spelling. */
    private static boolean generatedName(String name) {
        return name != null && name.matches("[FT][0-9]+");
    }

    private static final List<Integer> LEVELS = List.of(0, 2);

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("=== LLVM Two-Module Debug Info and Cross-Module "
            + "Diagnostics (ISSUE-0914 G7/C8) ===");
        Path scratch = NativeCompiler.executableScratch("llvm-debug-info");
        try {
            Path project = project(scratch);
            Path published = compile(project);
            Path mainModule = published.resolve("main.ll");
            if (!Files.isRegularFile(mainModule)) {
                System.out.println("GATE FAILURE: the two-module compile "
                    + "published no textual module");
                System.exit(1);
            }
            for (int level : LEVELS) {
                check("two-module -O" + level, () ->
                    verify(published, scratch, level));
            }
            // The debug and origin coordinates are project-relative, never
            // publication-root dependent: the same project compiled to a
            // non-default --output root renders the identical module and the
            // identical source spelling.
            check("non-default --output root", () ->
                verifyProjectRelative(published, project, scratch));
        } finally {
            deleteTree(scratch);
        }
        System.out.println();
        System.out.println("Two-module debug info: " + passed + " passed, "
            + failed + " failed (" + LEVELS.size() + " level cases and the"
            + " non-default output-root case)");
        if (failed != 0) {
            System.out.println("Two-module debug info: FAIL");
            System.exit(1);
        }
        System.out.println("Two-module debug info: PASS");
    }

    private static void check(String label, Check body) {
        try {
            body.run();
            passed += 1;
            System.out.println("  [" + label + "] OK");
        } catch (AssertionError e) {
            failed += 1;
            System.out.println("  [" + label + "] FAIL: " + e.getMessage());
        } catch (Exception e) {
            failed += 1;
            System.out.println("  [" + label + "] FAIL: " + e);
        }
    }

    private interface Check {
        void run() throws Exception;
    }

    // =========================================================================
    // The project and the production compile
    // =========================================================================

    private static Path project(Path scratch) throws IOException {
        Path root = scratch.resolve("project");
        Path source = root.resolve("src/main.deal");
        Files.createDirectories(source.getParent());
        Files.writeString(source, MAIN_SOURCE, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/lib.deal"), LIB_SOURCE,
            StandardCharsets.UTF_8);
        Files.writeString(root.resolve("deal.json"),
            "{\"languageVersion\": \"1.6\", \"stdlib\": \"1.6\","
                + " \"moduleRoots\": [\"src\"], \"backend\": \"llvm\"}\n",
            StandardCharsets.UTF_8);
        return root;
    }

    private static Path compile(Path root) throws IOException {
        return compile(root, null);
    }

    /**
     * The one production compile of the project into the given
     * {@code --output} root; {@code null} keeps the manifest default
     * ({@code <project>/build/llvm}).
     */
    private static Path compile(Path root, String output) throws IOException {
        Path source = root.resolve("src/main.deal").toAbsolutePath().normalize();
        ProjectLocator.LocateResult located = ProjectLocator.locate(
            source.toString(),
            new CliOverrides(Backend.LLVM.cliName(), output));
        if (located.context() == null) {
            throw new AssertionError("the two-module project did not resolve: "
                + (located.e2010() != null ? located.e2010().message() : "")
                + (located.cliDiagnostic() != null
                    ? located.cliDiagnostic().message() : ""));
        }
        ProjectContext context = located.context();
        CompilerInvocation invocation = CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            context, source, false, false, false, false, null, invocation);
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
            throw new AssertionError("the two-module project failed to compile: "
                + diagnostics + captured.toString(StandardCharsets.UTF_8));
        }
        Path published = Path.of(context.outputPath().absoluteNormalizedPath());
        for (String required : List.of("main.ll", "deal/native/main.c",
                "deal/native/deal_rt.h", "deal/native/entry.h")) {
            if (!Files.isRegularFile(published.resolve(required))) {
                throw new AssertionError("the published set omits " + required);
            }
        }
        return published;
    }

    // =========================================================================
    // One optimization level
    // =========================================================================

    private static void verify(Path published, Path scratch, int level)
            throws Exception {
        Path productDirectory = scratch.resolve("product-" + level);
        Files.createDirectories(productDirectory);
        Path product = productDirectory.resolve("debug-driver");
        String driver = """
            #include "deal/native/entry.h"
            #include <stdio.h>

            int main(void) {
              deal_outcome started = deal_project_start();
              deal_error *error = started.error;
              if (error == NULL) {
                deal_module *module = deal_project_exports("main");
                if (module == NULL) {
                  fputs("missing module\\n", stderr);
                  return 4;
                }
                deal_export_entry *entry =
                    deal_export_lookup(module, "main");
                if (entry == NULL) {
                  fputs("missing entry\\n", stderr);
                  return 4;
                }
                deal_outcome outcome = deal_export_invoke(entry);
                error = outcome.error;
              }
              if (error == NULL) {
                fputs("the second module raised nothing\\n", stderr);
                return 3;
              }
              const char *code = NULL;
              const char *file = NULL;
              const char *message = NULL;
              int32_t line = 0;
              int32_t column = 0;
              int32_t frames = 0;
              deal_error_code(error, &code);
              deal_error_message(error, &message);
              deal_error_source_file(error, &file);
              deal_error_line(error, &line);
              deal_error_column(error, &column);
              deal_error_frame_count(error, &frames);
              printf("code=%s\\n", code == NULL ? "" : code);
              printf("message=%s\\n", message == NULL ? "" : message);
              printf("file=%s\\n", file == NULL ? "" : file);
              printf("line=%d\\n", (int)line);
              printf("column=%d\\n", (int)column);
              printf("frames=%d\\n", (int)frames);
              return 0;
            }
            """;
        try {
            NativeCompiler.build(new NativeBuildRequest(published, product, level,
                List.of(), driver));
        } catch (deal.toolchain.NativeBuildException e) {
            throw new AssertionError("the toolchain seam rejected the driver "
                + "build: " + e.getMessage(), e);
        }
        NativeCompiler.Result run = NativeCompiler.execute(product,
            productDirectory, Map.of());
        if (run.exitCode() != 0) {
            throw new AssertionError("the driver exited " + run.exitCode()
                + ": " + run.stderr().replace("\n", "\\n"));
        }
        Map<String, String> fields = fields(run.stdout());

        // 1. The runtime error names the *second* module's own coordinate.
        require(fields, "code", "E8002");
        require(fields, "file", "src/lib.deal");
        require(fields, "line", Integer.toString(LIB_ERROR_LINE));
        require(fields, "column", Integer.toString(LIB_ERROR_COLUMN));
        int frames = Integer.parseInt(fields.get("frames"));
        if (frames < 2) {
            throw new AssertionError("the carried error records " + frames
                + " frame(s); a failure raised in the second module under the "
                + "entry activation records at least two");
        }

        // 2. The debug information names both constituent sources and every
        //    generated function of each module: the DEAL source name as
        //    DW_AT_name beside its generated F<id> linkage name at the
        //    declared line.
        List<Subprogram> subprograms = subprograms(product);
        requireSubprogram(subprograms, "compute", "src/lib.deal", LIB_DECL_LINE);
        requireSubprogram(subprograms, "main", "src/main.deal", MAIN_DECL_LINE);
        // C8's anonymous form: a name-less function expression carries the
        // generated F<id> in both name fields, and no named DEAL function is
        // spelled with the generated name. The rejection is therefore scoped
        // to the named form: a generated spelling in DW_AT_name is accepted
        // exactly when DW_AT_linkage_name is the same spelling.
        boolean anonymous = false;
        for (Subprogram subprogram : subprograms) {
            if (!generatedName(subprogram.name())) {
                continue;
            }
            if (!subprogram.name().equals(subprogram.linkageName())) {
                throw new AssertionError("a DISubprogram of "
                    + subprogram.declFile() + " carries the generated spelling "
                    + subprogram.name() + " as its DW_AT_name with DW_AT_"
                    + "linkage_name (\"" + subprogram.linkageName() + "\") "
                    + "instead of the DEAL source name");
            }
            if (subprogram.declFile() != null
                    && subprogram.declFile().endsWith("src/main.deal")) {
                anonymous = true;
            }
        }
        if (!anonymous) {
            throw new AssertionError("the debug information carries no "
                + "DISubprogram of the name-less function expression (the "
                + "generated F<id> is expected in both name fields)");
        }
        for (String source : List.of("src/lib.deal", "src/main.deal")) {
            boolean named = subprograms.stream()
                .anyMatch(subprogram -> subprogram.declFile().endsWith(source));
            if (!named) {
                throw new AssertionError("the debug information carries no "
                    + "DISubprogram of " + source);
            }
        }

        // 3. The line table carries both source files with the declared
        //    lines and columns of the mapped op groups.
        requireLineEntry(lineTableFor(product, "src/lib.deal"), "src/lib.deal",
            LIB_ERROR_LINE, LIB_ERROR_COLUMN);
        requireLineEntry(lineTableFor(product, "src/main.deal"), "src/main.deal",
            MAIN_CALL_LINE, MAIN_CALL_COLUMN);
    }

    /**
     * C8/C9 under the documented {@code --output <artifactsDir>} form: a
     * shallow and a deeply nested output root produce the very same textual
     * module as the default root, no absolute or layout-prefixed source
     * spelling reaches the IR, and the non-default product's DWARF and
     * carried error coordinate still name the project-relative source.
     */
    private static void verifyProjectRelative(Path published, Path project,
                                              Path scratch) throws Exception {
        Path shallow = scratch.resolve("out-nonddefault");
        Path deep = scratch.resolve("out-nested/deeper/out");
        Path fromShallow = compile(project, shallow.toString());
        Path fromDeep = compile(project, deep.toString());
        byte[] reference = Files.readAllBytes(published.resolve("main.ll"));
        byte[] shallowBytes = Files.readAllBytes(fromShallow.resolve("main.ll"));
        byte[] deepBytes = Files.readAllBytes(fromDeep.resolve("main.ll"));
        if (!java.util.Arrays.equals(reference, shallowBytes)
                || !java.util.Arrays.equals(reference, deepBytes)) {
            throw new AssertionError("the same project publishes different"
                + " textual modules for different output roots");
        }
        String module = new String(shallowBytes, StandardCharsets.UTF_8);
        for (String absolute : List.of(scratch.toString(),
                project.toAbsolutePath().normalize().toString())) {
            if (module.contains(absolute)) {
                throw new AssertionError("the emitted module carries the"
                    + " absolute path " + absolute);
            }
        }
        for (String source : List.of("src/lib.deal", "src/main.deal")) {
            if (!module.contains("DIFile(filename: \"" + source + "\"")) {
                throw new AssertionError("the emitted module carries no"
                    + " project-relative DIFile for " + source);
            }
        }
        // The non-default product's debug information and carried-error
        // projection keep the same project-relative coordinates.
        verify(fromShallow, scratch, 0);
    }

    /** One generated function's DWARF names and declared position. */
    private record Subprogram(String name, String linkageName,
                              String declFile, int declLine) { }

    /**
     * Every {@code DISubprogram} the product's debug information carries:
     * {@code llvm-dwarfdump} is filtered to the tag and attribute lines the
     * assertion reads, so the runtime units' variable DIEs never reach the
     * bounded capture.
     */
    private static List<Subprogram> subprograms(Path product) throws IOException {
        // Only the project's own subprogram blocks reach the bounded capture:
        // the awk program buffers one subprogram's attribute lines and prints
        // the block only when its declaration file is a project module.
        String program = "awk '/DW_TAG_subprogram/{block=$0 \"\\n\"; "
            + "collecting=1; next} "
            + "collecting && /DW_AT_/{block=block $0 \"\\n\"; next} "
            + "collecting {if (block ~ /src\\/(lib|main)\\.deal/) "
            + "printf \"%s\", block; collecting=0; block=\"\"} "
            + "END {if (collecting && block ~ /src\\/(lib|main)\\.deal/) "
            + "printf \"%s\", block}'";
        NativeCompiler.Result dump = NativeCompiler.run(List.of("sh", "-c",
            "llvm-dwarfdump --debug-info \"$1\" | " + program, "sh",
            product.toString()), product.getParent());
        if (dump.exitCode() != 0) {
            throw new AssertionError("llvm-dwarfdump --debug-info exited "
                + dump.exitCode() + ": " + dump.stderr().replace("\n", "\\n"));
        }
        List<Subprogram> subprograms = new ArrayList<>();
        String name = null;
        String linkageName = null;
        String declFile = null;
        int declLine = 0;
        boolean inside = false;
        for (String line : dump.stdout().split("\n")) {
            if (line.contains("DW_TAG_subprogram")) {
                if (inside) {
                    subprograms.add(new Subprogram(name, linkageName, declFile,
                        declLine));
                }
                inside = true;
                name = null;
                linkageName = null;
                declFile = null;
                declLine = 0;
                continue;
            }
            if (!inside) {
                continue;
            }
            String value = attribute(line, "DW_AT_name");
            if (value != null) {
                name = value;
            }
            value = attribute(line, "DW_AT_linkage_name");
            if (value != null) {
                linkageName = value;
            }
            value = attribute(line, "DW_AT_decl_file");
            if (value != null) {
                declFile = value;
            }
            value = attribute(line, "DW_AT_decl_line");
            if (value != null) {
                declLine = Integer.parseInt(value.trim());
            }
        }
        if (inside) {
            subprograms.add(new Subprogram(name, linkageName, declFile, declLine));
        }
        if (subprograms.isEmpty()) {
            throw new AssertionError("the debug information carries no "
                + "DISubprogram of either project module");
        }
        return subprograms;
    }

    /** The parenthesised value of one attribute line, or null. */
    private static String attribute(String line, String attribute) {
        int index = line.indexOf(attribute + "\t(");
        if (index < 0) {
            return null;
        }
        int start = line.indexOf('(', index) + 1;
        int end = line.lastIndexOf(')');
        if (end < start) {
            return null;
        }
        String value = line.substring(start, end);
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static void requireSubprogram(List<Subprogram> subprograms,
            String sourceName, String declFile, int declaredLine) {
        for (Subprogram subprogram : subprograms) {
            if (!sourceName.equals(subprogram.name())
                    || subprogram.declFile() == null
                    || !subprogram.declFile().endsWith(declFile)) {
                continue;
            }
            if (!subprogram.linkageName().matches("F[0-9]+")) {
                throw new AssertionError("the DISubprogram for " + sourceName
                    + " carries DW_AT_linkage_name (\""
                    + subprogram.linkageName() + "\"), expected the generated "
                    + "F<id>");
            }
            if (subprogram.declLine() != declaredLine) {
                throw new AssertionError("the DISubprogram for " + sourceName
                    + " declares line " + subprogram.declLine() + ", expected "
                    + declaredLine);
            }
            return;
        }
        throw new AssertionError("the debug information carries no "
            + "DISubprogram with DW_AT_name (\"" + sourceName
            + "\") and an F<id> DW_AT_linkage_name in " + declFile);
    }

    /**
     * The line table of one source file: {@code llvm-dwarfdump --debug-line}
     * is split on the table records, so only the named file's table reaches the
     * bounded capture.
     */
    private static String lineTableFor(Path product, String sourceFile)
            throws IOException {
        NativeCompiler.Result dump = NativeCompiler.run(List.of("sh", "-c",
            "llvm-dwarfdump --debug-line \"$1\" | awk -v RS='debug_line\\[' "
                + "-v file=\"$2\" 'index($0, \"name: \\\"\" file "
                + "\"\\\"\") {print}'",
            "sh", product.toString(), sourceFile), product.getParent());
        if (dump.exitCode() != 0) {
            throw new AssertionError("llvm-dwarfdump --debug-line exited "
                + dump.exitCode() + ": " + dump.stderr().replace("\n", "\\n"));
        }
        if (dump.stdout().isEmpty()) {
            throw new AssertionError("the line table names no source file "
                + sourceFile);
        }
        return dump.stdout();
    }

    /**
     * Requires one line-table row at the declared line and column of a mapped
     * op group of the named source file.
     */
    private static void requireLineEntry(String table, String sourceFile,
            int line, int column) {
        Matcher row = Pattern.compile(
            "^0x[0-9a-f]+\\s+([0-9]+)\\s+([0-9]+)", Pattern.MULTILINE)
            .matcher(table);
        while (row.find()) {
            if (Integer.parseInt(row.group(1)) == line
                    && Integer.parseInt(row.group(2)) == column) {
                return;
            }
        }
        throw new AssertionError("the line table of " + sourceFile
            + " carries no row at line " + line + " column " + column);
    }

    private static Map<String, String> fields(String stdout) {
        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
        for (String line : stdout.split("\n")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                fields.put(line.substring(0, equals), line.substring(equals + 1));
            }
        }
        return fields;
    }

    private static void require(Map<String, String> fields, String field,
            String expected) {
        String actual = fields.get(field);
        if (!expected.equals(actual)) {
            throw new AssertionError("the carried error's " + field + " is '"
                + actual + "', expected '" + expected + "'");
        }
    }

    private static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            List<Path> ordered = new ArrayList<>();
            paths.sorted(Comparator.reverseOrder()).forEach(ordered::add);
            for (Path path : ordered) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Best effort: the suite owns this scratch directory.
        }
    }
}
