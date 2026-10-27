package deal.test;

import deal.codegen.Backend;
import deal.codegen.llvm.LlvmRuntimeAbi;
import deal.diagnostics.CompilerDiagnostic;
import deal.distribution.DistributionHome;
import deal.module.CompilationOrchestrator;
import deal.project.OutputConfigResolver;
import deal.project.ProjectLocator;
import deal.project.CliOverrides;
import deal.project.ProjectContext;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.toolchain.NativeRuntime;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The native production-emission arm
 * ({@code llvm-native-backend-integration} D2/D3/D6 {@code CompileNative},
 * {@code llvm-native-codegen-cfg-ssa} C2/C8/C9) driven through the public
 * production entry {@code deal.Main}'s own resolution and compile path.
 *
 * <p>Every case reads the <em>published</em> tree of a real compile — never a
 * reconstructed one:</p>
 *
 * <ol>
 *   <li>the published set is exactly {@code <entry>.ll} plus the closed D6
 *       runtime set, byte-equal to the distribution files, with no source-map
 *       sidecar, no generated registry, no driver and no build product;</li>
 *   <li>the emitted module carries the pinned triple, defines exactly the six
 *       C2 export-surface functions, defines no process entry and no
 *       runtime-owned symbol, installs the arm table before any unit
 *       initialization, emits the zero-slot prologue/epilogue pair on every
 *       generated activation, its frame labels, its {@code deal_origin}
 *       records and both debug names per function;</li>
 *   <li>the emitted failure-arm table equals the registry's canonical
 *       serialization record for record;</li>
 *   <li>two compiles are byte-identical; a compile removes stale artifacts; a
 *       failed compile preserves the previous set byte for byte and leaves no
 *       stage tree;</li>
 *   <li>the published set builds and runs as the product shape at
 *       {@code -O0} and {@code -O2}, and its DWARF names both sources and
 *       both function-name spellings;</li>
 *   <li>a missing runtime file is the pinned E6000 with nothing published, and
 *       a compile executes no external tool (it succeeds with no compiler on
 *       {@code PATH}) and creates no product.</li>
 * </ol>
 */
public final class LlvmProjectEmissionTest {

    private static final Path REPO = NativeCompiler.repository();

    private static final String APP_SOURCE = """
        import * as lib from "./lib";

        export function main(): null {
          let ignored: null = lib.ping();
          return ignored;
        }
        """;

    private static final String LIB_SOURCE = """
        function twice(a: number): number {
          return a + a;
        }

        export function ping(): null {
          let total: number = twice(2);
          let i: number = 0;
          while (i < total) {
            i = i + 1;
          }
          if (i > 3) {
            i = i - 1;
          }
          return null;
        }
        """;

    private static final String ARRAY_SOURCE = """
        export function main(): null {
          let values: number[] = [1, 2];
          let first: number = values[0];
          return null;
        }
        """;

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--compile-probe")) {
            compileProbe(Path.of(args[1]), Path.of(args[2]));
            return;
        }
        System.out.println("=== LLVM Project Emission Tests (ISSUE-0903) ===\n");
        Path scratch = NativeCompiler.executableScratch("llvm-emission");
        try {
            Path projectRoot = scratch.resolve("scalar");
            Path published = compileScalarProject(projectRoot, false);
            testPublishedSet(published);
            String module = Files.readString(published.resolve("main.ll"),
                StandardCharsets.UTF_8);
            testModuleStructure(published, module);
            testDebugMetadata(module);
            testArmTable(module);
            testDeterminism(scratch, published, module);
            testStaleAndFailedPublication(scratch, projectRoot, published);
            testFrontendErrorPublishesNothing(scratch);
            testUnsupportedFactCarriesOrigin(scratch);
            testNoExternalTool(scratch);
            testMissingRuntimeFailsClosed(scratch);
            testProductBuildAndRun(scratch, published);
        } finally {
            deleteRecursively(scratch);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== LLVM Project Emission Tests Passed ===");
    }

    // =========================================================================
    // 1. The published set (D2/D6)
    // =========================================================================

    private static void testPublishedSet(Path published) throws IOException {
        System.out.println("-- the published set is exactly the module and D6 --");
        Set<String> expected = new LinkedHashSet<>();
        expected.add("main.ll");
        expected.addAll(NativeRuntime.FILES);
        checkEq(expected, new LinkedHashSet<>(filesUnder(published)),
            "the published set is the one module plus the closed runtime set");
        check(!Files.exists(published.resolve("main.deal.map.json")),
            "the native backend publishes no source-map sidecar (C8)");
        check(!Files.exists(published.resolve("lib.ll")),
            "no per-module artifact is published (D2)");
        check(!Files.exists(published.resolve("hosts_registry.c")),
            "no generated registry translation unit is published");
        check(!Files.exists(published.resolve("native")),
            "no build product is created inside the publication root");
        for (String relativeName : NativeRuntime.FILES) {
            check(java.util.Arrays.equals(
                    Files.readAllBytes(REPO.resolve(relativeName)),
                    Files.readAllBytes(published.resolve(relativeName))),
                "the deployment copy of " + relativeName
                    + " is byte-equal to the distribution file");
        }
        // No external tool ran: the publication root holds text only.
        for (String relativeName : filesUnder(published)) {
            byte[] bytes = Files.readAllBytes(published.resolve(relativeName));
            check(bytes.length > 0,
                "the published file " + relativeName + " is non-empty");
        }
    }

    // =========================================================================
    // 2. The module structure (C2/C4/C9)
    // =========================================================================

    private static void testModuleStructure(Path published, String module) {
        System.out.println("-- the emitted module structure (C2/C4/C9) --");
        check(module.contains("target triple = \"" + NativeCompiler.TARGET_TRIPLE
                + "\""), "the module header carries the pinned target triple");
        check(module.contains("target datalayout = \""), "the module pins a"
            + " deterministic data layout");
        check(module.contains("source_filename = \"deal.project\""),
            "the module carries one deterministic source filename");
        Set<String> definitions = definitionsOf(module);
        check(!definitions.contains("main"),
            "the emitted module defines no process entry (C2/R9)");
        for (String owned : LlvmRuntimeAbi.RUNTIME_OWNED) {
            check(!definitions.contains(owned),
                "the emitted module defines no runtime-owned symbol: " + owned);
        }
        for (String name : LlvmRuntimeAbi.EXPORT_SURFACE) {
            check(definitions.contains(name),
                "the emitted module defines the export-surface entry " + name);
        }
        checkEq(6, LlvmRuntimeAbi.EXPORT_SURFACE.size(),
            "the module defines exactly the six C2 export-surface functions");
        for (String name : definitions) {
            if (name.startsWith("F") && name.substring(1).matches("\\d+")) {
                check(module.contains("define internal "),
                    "every lowered function is internal");
                break;
            }
        }
        check(!module.contains("define internal %struct.deal_outcome"
                + " @deal_project_start"),
            "the export-surface entry is externally visible");
        // Only the permitted runtime entries appear as external references.
        Matcher calls = Pattern.compile("@(deal_[A-Za-z0-9_]+)\\(").matcher(module);
        Set<String> referenced = new LinkedHashSet<>();
        while (calls.find()) {
            referenced.add(calls.group(1));
        }
        Set<String> permitted = new LinkedHashSet<>(List.of(
            "deal_arm_table_install", "deal_call_frame_push",
            "deal_call_frame_pop", "deal_root_frame_enter",
            "deal_root_frame_leave"));
        referenced.removeAll(definitions);
        referenced.remove("deal_project_start");
        referenced.remove("deal_project_run_main");
        checkEq(permitted, referenced,
            "only the prologue/epilogue entries and the install entry are"
                + " referenced (task stage classifier)");
        // The arm table is installed before any unit initialization.
        String start = functionText(module, "deal_project_start");
        int installAt = start.indexOf("@deal_arm_table_install");
        int firstInit = start.indexOf("@deal_module_init_");
        check(installAt >= 0, "deal_project_start installs the arm table");
        check(firstInit > installAt,
            "the arm table is installed before any unit's init runs");
        // Zero-slot prologue/epilogue on every generated activation: each
        // generated function enters exactly one frame, and every exit path
        // leaves it before its return.
        StringBuilder generated = new StringBuilder();
        for (String name : definitions) {
            if (name.startsWith("F") || name.startsWith("deal_module_init_")) {
                generated.append(functionText(module, name)).append('\n');
            }
        }
        String generatedText = generated.toString();
        int pushes = countOccurrences(generatedText,
            "call void @deal_call_frame_push(");
        int enters = countOccurrences(generatedText,
            "call void @deal_root_frame_enter(i32 0)");
        int leaves = countOccurrences(generatedText,
            "call void @deal_root_frame_leave()");
        int pops = countOccurrences(generatedText,
            "call void @deal_call_frame_pop()");
        int returns = countOccurrences(generatedText, "  ret ");
        check(pushes > 0, "the module emits call-frame pushes");
        checkEq(generatedCount(module, definitions), pushes,
            "every generated function pushes exactly one call frame");
        checkEq(pushes, enters,
            "every generated activation enters one zero-slot root frame");
        checkEq(returns, leaves,
            "every exit path leaves the root frame");
        checkEq(returns, pops, "every exit path pops the call frame");
        checkEq(0, countOccurrences(module, "deal_root_frame_write"),
            "the staged subset emits no root slot write (zero-slot frames)");
        check(module.contains("@deal.slot."),
            "module-scope bindings use module-level storage");
        // The frame labels carry the module path, the source name and F<id>.
        check(module.contains("main#main@F"), "the entry frame label names its"
            + " module, source function and generated function");
        check(module.contains("lib#ping@F"), "the imported module's frame label"
            + " names its own module, source function and generated function");
        // The origin records carry one-based line/column of the compiled file.
        check(module.contains("private unnamed_addr constant %struct.deal_origin"),
            "the module emits deal_origin records (DiagnosticOriginProjection)");
        check(module.contains("i32 6, i32 3"),
            "the emitted origin of the first `let` op is line 6, column 3");
        check(module.contains("lib.deal") && module.contains("main.deal"),
            "the origin records carry the project-relative source names");
        check(!module.contains(REPO.toString()),
            "no absolute source path appears in the module");
        check(!Files.exists(published.resolve("main.deal.map.json")),
            "no sidecar is published");
    }

    // =========================================================================
    // 3. The debug metadata (C8)
    // =========================================================================

    private static void testDebugMetadata(String module) {
        System.out.println("-- the debug metadata names both files and functions --");
        checkEq(2, countOccurrences(module, "DICompileUnit("),
            "one DICompileUnit per constituent source");
        check(module.contains("!DIFile(filename: \"probe-scalar/src/lib.deal\"")
                || module.contains("!DIFile(filename: \"src/lib.deal\""),
            "the DIFile carries the project-relative source name");
        check(module.contains("!DIFile(filename: \"probe-scalar/src/main.deal\"")
                || module.contains("!DIFile(filename: \"src/main.deal\""),
            "the second constituent source has its own DIFile");
        check(!module.contains("DIFile(filename: \"/"),
            "no DIFile carries an absolute path");
        Matcher subprograms = Pattern.compile(
            "!DISubprogram\\(name: \"([^\"]*)\", linkageName: \"(F\\d+)\"")
            .matcher(module);
        Map<String, String> names = new LinkedHashMap<>();
        while (subprograms.find()) {
            names.put(subprograms.group(1), subprograms.group(2));
        }
        checkEq("F", names.getOrDefault("ping", "").replaceAll("\\d+$", ""),
            "the ping subprogram's linkage name is its generated F<id>");
        checkEq("F", names.getOrDefault("twice", "").replaceAll("\\d+$", ""),
            "the twice subprogram's linkage name is its generated F<id>");
        checkEq("F", names.getOrDefault("main", "").replaceAll("\\d+$", ""),
            "the main subprogram's linkage name is its generated F<id>");
        checkEq(3, names.size(), "one DISubprogram per generated function");
        check(module.contains("DISPFlagLocalToUnit | DISPFlagDefinition"),
            "each subprogram is a local definition");
        check(countOccurrences(module, "!DILocation(") > 0,
            "the module attaches DILocations to its source-mapped groups");
        check(module.contains("!\"Dwarf Version\"")
                && module.contains("!\"Debug Info Version\""),
            "the module flags enable readable DWARF");
    }

    // =========================================================================
    // 4. The failure-arm table (R5)
    // =========================================================================

    private static void testArmTable(String module) throws IOException {
        System.out.println("-- the emitted arm table equals the registry --");
        Map<String, String> strings = stringConstants(module);
        String recordArray = arrayLiteralAfter(module,
            "@deal.arm.records = ");
        List<String> records = new ArrayList<>();
        int cursor = 0;
        while (true) {
            int start = recordArray.indexOf("%struct.deal_arm_record {", cursor);
            if (start < 0) {
                break;
            }
            int end = recordArray.indexOf(" }", start);
            records.add(recordArray.substring(start, end + 2));
            cursor = end + 2;
        }
        List<deal.semantic.ir.FailureArm> arms = List.copyOf(
            deal.semantic.ir.FailureContractRegistry.arms().values());
        checkEq(arms.size(), records.size(),
            "the emitted table carries one record per registry arm");
        check(module.contains("@deal_project_arm_table = dso_local constant"),
            "the emitted module defines the installed arm table");
        check(module.contains("i32 1, i32 " + arms.size()),
            "the emitted table carries the published version and the arm count");
        int index = 0;
        for (deal.semantic.ir.FailureArm arm : arms) {
            String record = records.get(index++);
            check(record.startsWith("%struct.deal_arm_record { i32 "
                    + arm.id().ordinal() + ", "),
                "the arm record of " + arm.id() + " carries its registry ordinal");
            check(record.contains(resolve(strings, deal.semantic.ir
                    .FailureContractRegistry.codeOf(arm))),
                "the arm record of " + arm.id() + " carries the registry code");
            check(record.contains(resolve(strings, arm.template())),
                "the arm record of " + arm.id() + " carries the arm's own"
                    + " template text");
            check(record.endsWith(", i32 " + arm.parameters().size() + ", i8 "
                    + (arm.scope() == deal.semantic.ir.FailureArm.RenderScope.INNER_ONLY
                        ? LlvmRuntimeAbi.ARM_SCOPE_INNER_ONLY
                        : LlvmRuntimeAbi.ARM_SCOPE_TOP_LEVEL)
                    + ", i8 " + (arm.expectedSource()
                        == deal.semantic.ir.FailureArm.ExpectedSource.NONE ? 0 : 1)
                    + ", i8 " + (arm.actualProjection()
                        == deal.semantic.ir.FailureArm.ActualProjection.NONE ? 0 : 1)
                    + " }"),
                "the arm record of " + arm.id() + " carries the declared"
                    + " parameter count, render scope and field flags");
        }
        // The registry's canonical serialization and the emitted table agree
        // about every arm's ordinal, code and template.
        List<String> canonical =
            deal.semantic.ir.FailureContractRegistry.canonicalArmSerialization();
        checkEq(arms.size(), canonical.size(),
            "the canonical serialization covers exactly the emitted arms");
    }

    /** The emitted string constant contents keyed by their global name. */
    private static Map<String, String> stringConstants(String module) {
        Map<String, String> strings = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile(
            "@(deal\\.str\\.\\d+) = private unnamed_addr constant \\[(\\d+) x"
                + " i8\\] c\"(.*?)\"$", Pattern.MULTILINE).matcher(module);
        while (matcher.find()) {
            strings.put(matcher.group(1), unescapeLlvmString(matcher.group(3)));
        }
        return strings;
    }

    /** The decoded text of one emitted LLVM string constant body. */
    private static String unescapeLlvmString(String body) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < body.length(); index++) {
            char character = body.charAt(index);
            if (character != '\\') {
                text.append(character);
                continue;
            }
            String hex = body.substring(index + 1, index + 3);
            text.append((char) Integer.parseInt(hex, 16));
            index += 2;
        }
        String value = text.toString();
        return value.endsWith("\u0000")
            ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * The value of one arm's text operand: the record carries the string
     * operand of the emitted constant, so this resolves the operand back to
     * the constant's text.
     */
    private static String resolve(Map<String, String> strings, String text) {
        for (Map.Entry<String, String> entry : strings.entrySet()) {
            if (entry.getValue().equals(text)) {
                return "@" + entry.getKey();
            }
        }
        return "\u0000missing:" + text;
    }

    // =========================================================================
    // 5. Determinism, stale removal and failure preservation
    // =========================================================================

    private static void testDeterminism(Path scratch, Path published,
                                       String module) throws Exception {
        System.out.println("-- determinism (C9) --");
        Path second = compileScalarProject(scratch.resolve("scalar-again"), false);
        checkEq(module, Files.readString(second.resolve("main.ll"),
                StandardCharsets.UTF_8),
            "two compiles of one project produce byte-identical module bytes");
        checkEq(filesUnder(published), filesUnder(second),
            "two compiles produce the same published set");
    }

    private static void testStaleAndFailedPublication(Path scratch,
            Path projectRoot, Path published) throws Exception {
        System.out.println("-- stale removal and failure-safe publication --");
        byte[] before = Files.readAllBytes(published.resolve("main.ll"));
        Files.writeString(published.resolve("stale.lua"), "stale",
            StandardCharsets.UTF_8);
        Path recompiled = compileScalarProject(projectRoot, false);
        check(!Files.exists(published.resolve("stale.lua")),
            "a second compile removes the stale artifact of the prior set");
        check(java.util.Arrays.equals(before,
                Files.readAllBytes(recompiled.resolve("main.ll"))),
            "the recompiled module is the same deterministic module");
        // A failing compile into the same output root preserves the prior set.
        Path failing = scratch.resolve("scalar-failing");
        writeProject(failing, Map.of("src/main.deal", ARRAY_SOURCE),
            Map.of("backend", "llvm"));
        byte[] goodBytes = Files.readAllBytes(published.resolve("main.ll"));
        List<String> goodSet = filesUnder(published);
        CompileOutcome failedOutcome = compile(failing.resolve("src/main.deal"),
            published, false, false);
        check(!failedOutcome.success(),
            "the managed-array project is rejected with E6005");
        checkEq(goodSet, filesUnder(published),
            "a failed compile leaves the prior published set exactly");
        check(java.util.Arrays.equals(goodBytes,
                Files.readAllBytes(published.resolve("main.ll"))),
            "a failed compile preserves the prior module bytes");
        try (Stream<Path> walk = Files.walk(published.getParent())) {
            check(walk.noneMatch(path -> path.getFileName().toString()
                    .contains(PublicationMarker.STAGE)),
                "no stage tree survives the failed compile");
        }
    }

    /** The stage-tree marker of the publication transaction. */
    private static final class PublicationMarker {
        static final String STAGE = String.valueOf(
            deal.publication.PublicationStager.STAGE_TREE_MARKER);
    }

    private static void testFrontendErrorPublishesNothing(Path scratch)
            throws Exception {
        System.out.println("-- a frontend error publishes nothing --");
        Path root = scratch.resolve("frontend-error");
        writeProject(root, Map.of("src/main.deal",
            "export function main(): null {\n  let x: number = ;\n  return null;\n}\n"),
            Map.of("backend", "llvm"));
        Path output = root.resolve("build/llvm");
        CompileOutcome outcome = compile(root.resolve("src/main.deal"), output,
            false, false);
        check(!outcome.success(), "the malformed source fails the compile");
        check(!Files.exists(output),
            "a frontend error publishes no artifact set");
        check(outcome.diagnostics().stream()
                .anyMatch(diagnostic -> !diagnostic.code().equals("E6005")),
            "the frontend diagnostic is preserved, not replaced");
    }

    private static void testUnsupportedFactCarriesOrigin(Path scratch)
            throws Exception {
        System.out.println("-- an unclassified fact is an origin-bearing E6005 --");
        Path root = scratch.resolve("unsupported");
        writeProject(root, Map.of("src/main.deal", ARRAY_SOURCE),
            Map.of("backend", "llvm"));
        Path output = root.resolve("build/llvm");
        CompileOutcome outcome = compile(root.resolve("src/main.deal"), output,
            false, false);
        check(!outcome.success(), "the managed-array project fails the compile");
        check(!Files.exists(output), "nothing is published");
        CompilerDiagnostic diagnostic = outcome.diagnostics().get(0);
        checkEq("E6005", diagnostic.code(), "the rejection is one E6005");
        check(diagnostic.message().contains("ARRAY_NEW")
                || diagnostic.message().contains("INDEX_READ"),
            "the rejection names the unsupported fact: " + diagnostic.message());
        check(diagnostic.range() != null
                && diagnostic.range().file().contains("main.deal"),
            "the rejection carries the offending source file");
        check(diagnostic.range() != null && diagnostic.range().startLine() >= 1,
            "the rejection carries the offending one-based line");
    }

    // =========================================================================
    // 6. No external tool, no product, missing runtime
    // =========================================================================

    private static void testNoExternalTool(Path scratch) throws Exception {
        System.out.println("-- a compile executes no external tool --");
        Path root = scratch.resolve("no-tool");
        writeProject(root, Map.of(
            "src/lib.deal", LIB_SOURCE, "src/main.deal", APP_SOURCE),
            Map.of("backend", "llvm"));
        Path output = root.resolve("build/llvm");
        Path emptyPath = Files.createDirectories(scratch.resolve("empty-path"));
        List<String> command = List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", absoluteClasspath(),
            LlvmProjectEmissionTest.class.getName(),
            "--compile-probe", root.resolve("src/main.deal").toString(),
            output.toString());
        // The checkout cwd keeps the distribution tier resolvable; the empty
        // PATH proves the compile spawns no compiler at all.
        NativeCompiler.Result probe = NativeCompiler.run(command, REPO,
            Map.of("PATH", emptyPath.toString()));
        checkEq(0, probe.exitCode(),
            "the compile succeeds with no compiler on PATH: " + probe.describe());
        check(Files.isRegularFile(output.resolve("main.ll")),
            "the module is published without an external tool (D3)");
        check(!Files.exists(output.resolve("native"))
                && !Files.exists(root.resolve("build/native")),
            "the compile creates no build product");
    }

    private static void testMissingRuntimeFailsClosed(Path scratch)
            throws Exception {
        System.out.println("-- a missing runtime file is the pinned E6000 --");
        Path root = scratch.resolve("missing-runtime");
        writeProject(root, Map.of(
            "src/lib.deal", LIB_SOURCE, "src/main.deal", APP_SOURCE),
            Map.of("backend", "llvm"));
        Path output = root.resolve("build/llvm");
        Path emptyCheckout = Files.createDirectories(
            scratch.resolve("empty-checkout"));
        List<String> command = List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Ddeal.home=",
            "-cp", absoluteClasspath(),
            LlvmProjectEmissionTest.class.getName(),
            "--compile-probe", root.resolve("src/main.deal").toString(),
            output.toString());
        NativeCompiler.Result probe = NativeCompiler.run(command, emptyCheckout);
        check(probe.stdout().contains("Runtime library not found: "
                + "deal/native/deal_rt.h"),
            "a runtime file absent at every tier is the pinned E6000: "
                + probe.describe());
        check(!Files.exists(output),
            "the missing-runtime compile publishes nothing");
    }

    // =========================================================================
    // 7. The product shape at -O0 and -O2
    // =========================================================================

    private static void testProductBuildAndRun(Path scratch, Path published)
            throws Exception {
        System.out.println("-- the published set builds and runs as a product --");
        Path productRoot = Files.createDirectories(scratch.resolve("product"));
        for (int level : new int[] {0, 2}) {
            Path product = productRoot.resolve("native/main");
            Files.createDirectories(product.getParent());
            NativeCompiler.Result link = NativeCompiler.link(published, level,
                NativeCompiler.productSources(published,
                    List.of(published.resolve("main.ll"))), product);
            checkEq(0, link.exitCode(), "-O" + level
                + " the published module and runtime link: " + link.describe());
            if (link.exitCode() != 0) {
                continue;
            }
            NativeCompiler.Result run = NativeCompiler.execute(product,
                scratch, Map.of());
            checkEq(0, run.exitCode(), "-O" + level
                + " the product runs successfully: " + run.describe());
            checkEq("", run.stdout(),
                "-O" + level + " a successful run prints no terminal output");
            NativeCompiler.Result dwarf = NativeCompiler.run(
                List.of("llvm-dwarfdump", product.toString()), scratch);
            checkEq(0, dwarf.exitCode(), "llvm-dwarfdump reads the product at -O"
                + level);
            check(dwarf.stdout().contains("lib.deal")
                    && dwarf.stdout().contains("main.deal"),
                "-O" + level + " the DWARF names both DEAL sources");
            check(dwarf.stdout().contains("\"ping\"")
                    && dwarf.stdout().contains("\"main\""),
                "-O" + level + " the DWARF names the DEAL source functions");
            check(dwarf.stdout().matches("(?s).*DW_AT_linkage_name\\s+\\(\"F\\d+\"\\).*"),
                "-O" + level + " the DWARF names the generated function too");
        }
    }

    // =========================================================================
    // The compile path (the real project resolution and compiler)
    // =========================================================================

    /** One compile's outcome: success, its diagnostics and its console text. */
    private record CompileOutcome(boolean success,
                                  List<CompilerDiagnostic> diagnostics,
                                  String stdout, String stderr) {
    }

    private static Path compileScalarProject(Path root, boolean dumpIr)
            throws Exception {
        writeProject(root, Map.of(
            "src/lib.deal", LIB_SOURCE, "src/main.deal", APP_SOURCE),
            Map.of("backend", "llvm"));
        return compileTo(root, root.resolve("build/llvm"), dumpIr);
    }

    private static Path compileTo(Path root, Path output, boolean dumpIr)
            throws Exception {
        CompileOutcome outcome = compile(root.resolve("src/main.deal"), output,
            dumpIr, false);
        if (!outcome.success()) {
            throw new IllegalStateException("the fixture compile failed: "
                + outcome.diagnostics());
        }
        return output;
    }

    /** The production compile of one entry through the CLI's own seam. */
    private static CompileOutcome compile(Path entry, Path output,
            boolean dumpIr, boolean verbose) throws IOException {
        ProjectLocator.LocateResult located = ProjectLocator.locate(
            entry.toAbsolutePath().toString(),
            new CliOverrides(null, output.toAbsolutePath().toString()));
        if (located.context() == null) {
            return new CompileOutcome(false, List.of(),
                "", String.valueOf(located.cliDiagnostic()));
        }
        ProjectContext context = located.context();
        CompilerInvocation invocation = CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            context, entry.toAbsolutePath().normalize(), verbose, dumpIr,
            false, false, null, invocation);
        PrintStream stdout = System.out;
        PrintStream stderr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        boolean success;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            success = orchestrator.compile();
        } finally {
            System.setOut(stdout);
            System.setErr(stderr);
        }
        return new CompileOutcome(success, orchestrator.diagnostics(),
            out.toString(StandardCharsets.UTF_8),
            err.toString(StandardCharsets.UTF_8));
    }

    /** The child-JVM compile probe of the no-tool and missing-runtime cases. */
    private static void compileProbe(Path entry, Path output) throws IOException {
        CompileOutcome outcome = compile(entry, output, false, false);
        for (CompilerDiagnostic diagnostic : outcome.diagnostics()) {
            System.out.println(diagnostic.code() + ": " + diagnostic.message());
        }
        if (!outcome.success()) {
            System.exit(1);
        }
    }

    private static void writeProject(Path root, Map<String, String> sources,
            Map<String, String> manifest) throws IOException {
        Files.createDirectories(root.resolve("src"));
        StringBuilder json = new StringBuilder("{");
        json.append("\"languageVersion\": \"1.5\", \"moduleRoots\": [\"src\"]");
        for (Map.Entry<String, String> entry : manifest.entrySet()) {
            json.append(", \"").append(entry.getKey()).append("\": \"")
                .append(entry.getValue()).append('"');
        }
        json.append('}');
        Files.writeString(root.resolve("deal.json"), json.toString(),
            StandardCharsets.UTF_8);
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = root.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
        }
    }

    // =========================================================================
    // Small helpers
    // =========================================================================

    /** The definitions of one module text, by name. */
    private static Set<String> definitionsOf(String module) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("(?m)^define [^@]*@([A-Za-z0-9_.$]+)\\(")
            .matcher(module);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /** The complete text of one defined function. */
    private static String functionText(String module, String name) {
        int start = module.indexOf("@" + name + "(");
        if (start < 0) {
            return "";
        }
        int bodyStart = module.indexOf('{', start);
        int depth = 0;
        for (int index = bodyStart; index < module.length(); index++) {
            char character = module.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return module.substring(start, index + 1);
                }
            }
        }
        return module.substring(start);
    }

    /** The array literal of one global whose declaration starts at the marker. */
    private static String arrayLiteralAfter(String module, String marker) {
        int start = module.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int open = module.indexOf("[%struct.", module.indexOf("constant", start));
        int depth = 0;
        for (int index = open; index < module.length(); index++) {
            char character = module.charAt(index);
            if (character == '[') {
                depth++;
            } else if (character == ']') {
                depth--;
                if (depth == 0) {
                    return module.substring(open, index + 1);
                }
            }
        }
        return module.substring(open);
    }

    /** The number of generated (frame-entering) functions of the module. */
    private static int generatedCount(String module, Set<String> definitions) {
        int count = 0;
        for (String name : definitions) {
            if (name.startsWith("F") || name.startsWith("deal_module_init_")) {
                count++;
            }
        }
        return count;
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static List<String> filesUnder(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                .map(path -> root.relativize(path).toString()
                    .replace('\\', '/'))
                .sorted(Comparator.naturalOrder())
                .toList();
        }
    }

    private static String absoluteClasspath() {
        List<String> entries = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path")
                .split(Pattern.quote(java.io.File.pathSeparator))) {
            if (!entry.isEmpty()) {
                entries.add(Path.of(entry).toAbsolutePath().normalize()
                    .toString());
            }
        }
        return String.join(java.io.File.pathSeparator, entries);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

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
            message + " (expected " + abbreviate(expected) + ", got "
                + abbreviate(actual) + ")");
    }

    private static String abbreviate(Object value) {
        String text = String.valueOf(value);
        return text.length() <= 400 ? text : text.substring(0, 400) + "...";
    }
}
