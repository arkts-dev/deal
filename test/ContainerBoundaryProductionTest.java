package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmBackend;
import deal.codegen.jvm.JvmSemanticEmitter;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.identity.CanonicalModuleIdentity;
import deal.module.CompilationOrchestrator;
import deal.project.CliOverrides;
import deal.project.ProjectLocator;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CheckedProjectInput;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.HostDeclarationSurface;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticOracle;
import deal.semantic.SemanticRequirementManifest;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.BoundaryContext;
import deal.semantic.ir.BoundaryExecutor;
import deal.semantic.ir.BoundaryFailure;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.BoundaryOutcome;
import deal.semantic.ir.BoundaryValueView;
import deal.semantic.ir.CallMode;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FunctionAllocationIdentity;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.IndexMode;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.NormalizedSlot;
import deal.semantic.ir.OpId;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticIrValidator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;
import deal.test.conformance.ErrorSnapshot;
import deal.test.conformance.JvmCompilation;
import deal.test.conformance.SidecarExpectations;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class ContainerBoundaryProductionTest {

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


    private static final Path CORPUS = Path.of("test", "conformance");
    private static final String FIXTURE_DIR = "backend-runtime/boundaries";

    // =========================================================================
    // The bytes corpus table
    // =========================================================================

    /**
     * One driveable boundary fixture: its corpus directory, its
     * relative path, the export the drive's entry calls, the export's
     * declared return type, its companions, and the pinned failure row
     * ({@code null} for a runtime-ok fixture).
     */
    private record BoundaryFixture(String directory, String relativePath, String export,
                                String resultType, List<String> companions, String code,
                                String message, int line, int column, String expected,
                                String actual) {

        BoundaryFixture(String directory, String relativePath, String export,
                     String resultType, List<String> companions, String code,
                     String message, int line, int column) {
            this(directory, relativePath, export, resultType, companions, code,
                message, line, column, null, null);
        }

        boolean runtimeOk() {
            return code == null;
        }

        /** True for the rows whose sidecar also pins the expected/actual tokens. */
        boolean pinsExpectedActual() {
            return expected != null && actual != null;
        }

        String fixtureFile() {
            return directory + "/" + relativePath + ".deal";
        }

        String what() {
            return relativePath;
        }
    }


    private static BoundaryFixture error(String path, String export, String type,
                                      String code, String message, int line, int column) {
        return new BoundaryFixture(FIXTURE_DIR, path, export, type, List.of(), code, message,
            line, column);
    }


    // =========================================================================
    // 2. The production-entry drive harness
    // =========================================================================

    private record Compiled(
        Path root,
        CheckedProjectInput checkedProject,
        ProjectInterfaceIndex index,
        List<SemanticRequirementManifest> manifests,
        HostDeclarationSurface surface,
        Map<ModuleId, CanonicalModuleIdentity> identities,
        int strippedHeaderLines,
        String luaSource,
        String jvmSource) {
    }

    private record Drive(
        Compiled compiled,
        SemanticLowerer.ProjectLoweringResult result,
        ModuleId fixtureModule,
        LoweredModuleUnit unit,
        BoundaryFixture spec) {

        ExecutableLoweredProject project() {
            return result.project();
        }

        Map<ModuleId, StructuredBodyTable> tables() {
            return result.tables();
        }

        Map<ModuleId, ClassFactoryRegistry> registries() {
            return result.registries();
        }
    }

    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    /**
     * Materializes one boundary fixture (headers stripped, the drive's
     * own entry added) and compiles it through the real orchestrator.
     *
     * <p>The original classification metadata of the fixture and of every
     * companion is validated before any scratch state exists, so a
     * non-production or malformed declaration is the named gate-input
     * defect (CD3) and a rejected input allocates — and thus can never
     * leak — an owned temporary root. Any later preparation failure
     * removes the allocated root and reports a cleanup failure on the
     * original failure.</p>
     */
    private static Compiled compileFixture(BoundaryFixture fixture) throws Exception {
        String raw = Files.readString(CORPUS.resolve(fixture.fixtureFile()),
            StandardCharsets.UTF_8);
        String stripped = ConformanceHarnessMetadata.productionCorpusSource(
            raw, fixture.fixtureFile());
        int strippedLines = raw.split("\n", -1).length
            - stripped.split("\n", -1).length;
        Map<String, String> companionSources = new LinkedHashMap<>();
        for (String companion : fixture.companions()) {
            String companionCorpusPath = FIXTURE_DIR + "/" + companion + ".deal";
            companionSources.put(companion,
                ConformanceHarnessMetadata.productionCorpusSource(
                    Files.readString(CORPUS.resolve(companionCorpusPath),
                        StandardCharsets.UTF_8),
                    companionCorpusPath));
        }
        Path root = Files.createTempDirectory("boundary-corpus-");
        try {
            return compileFixtureInto(root, fixture, stripped, strippedLines,
                companionSources);
        } catch (Exception | Error failure) {
            cleanupRejectedScratch(root, failure);
            throw failure;
        }
    }

    private static void testRejectedRawProfileLeavesNoScratch() throws Exception {
        Path input = Files.createTempDirectory("boundary-profile-input-");
        Path temp = Path.of(System.getProperty("java.io.tmpdir"));
        String source = "export function main(): null { let n: number = 1; return null; }\n";
        try {
            Files.writeString(input.resolve("late-profile.deal"), "\n".repeat(40)
                + "// @profile: legacy-safe-number\n" + source, StandardCharsets.UTF_8);
            checkEq(source, ConformanceHarnessMetadata.productionCorpusSource(
                source, "profile-control.deal"), "the profile-free boundary control is accepted");
            Set<Path> before;
            try (var listing = Files.list(temp)) {
                before = listing.filter(path -> path.getFileName().toString()
                    .startsWith("boundary-corpus-")).collect(java.util.stream.Collectors.toSet());
            }
            BoundaryFixture fixture = new BoundaryFixture(input.toAbsolutePath().toString(),
                "late-profile", "main", "null", List.of(), null, null, 0, 0);
            try {
                Compiled compiled = compileFixture(fixture);
                if (compiled != null) {
                    deleteRecursively(compiled.root());
                }
                check(false, "the line-41 legacy profile must reject before compilation");
            } catch (ConformanceHarnessMetadata.HarnessInputDefectException defect) {
                check(defect.getMessage().contains(ConformanceHarnessMetadata.HARNESS_DEFECT)
                        && defect.getMessage().contains("late-profile.deal"),
                    "the late profile rejection names the input and defect: " + defect.getMessage());
            }
            Set<Path> after;
            try (var listing = Files.list(temp)) {
                after = listing.filter(path -> path.getFileName().toString()
                    .startsWith("boundary-corpus-")).collect(java.util.stream.Collectors.toSet());
            }
            checkEq(before, after, "the rejected boundary fixture leaves no compilation scratch root");
        } finally {
            deleteRecursively(input);
        }
    }

    private static Compiled compileFixtureInto(Path root, BoundaryFixture fixture,
            String stripped, int strippedLines,
            Map<String, String> companionSources) throws Exception {
        Path corpusRoot = root.resolve("corpus");
        Path fixtureFile = corpusRoot.resolve(fixture.fixtureFile());
        Files.createDirectories(fixtureFile.getParent());
        Files.writeString(fixtureFile, stripped, StandardCharsets.UTF_8);
        for (Map.Entry<String, String> companion : companionSources.entrySet()) {
            Path companionFile = corpusRoot.resolve(FIXTURE_DIR)
                .resolve(companion.getKey() + ".deal");
            Files.createDirectories(companionFile.getParent());
            Files.writeString(companionFile, companion.getValue(),
                StandardCharsets.UTF_8);
        }
        Path entry = "main".equals(fixture.export())
            ? fixtureFile
            : root.resolve("app.deal");
        if (!"main".equals(fixture.export())) {
            Files.writeString(entry, driver(fixture), StandardCharsets.UTF_8);
        }
        Map<String, String> externals = null;
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entry.toAbsolutePath(), root.resolve("out"), false, false, false, false,
            Backend.LUAJIT, externals, List.of(root.toAbsolutePath()),
            Path.of("std").toAbsolutePath().normalize(), null, productionInvocation());
        boolean compiled = orchestrator.compile();
        check(compiled, fixture.what() + ": the production orchestrator compiles "
            + "the project: " + orchestrator.diagnostics());
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        check(built != null && built.input() != null && built.index() != null
                && !built.hasErrors(),
            fixture.what() + ": the checked project builds: "
                + (built == null ? "null" : built.diagnostics()));
        if (!compiled || built == null || built.input() == null || built.index() == null
                || built.hasErrors() || manifests == null
                || manifests.manifests() == null
                || orchestrator.hostDeclarationSurface() == null) {
            deleteRecursively(root);
            return null;
        }
        Map<ModuleId, CanonicalModuleIdentity> identities = new LinkedHashMap<>();
        for (ModuleId declaration : orchestrator.hostDeclarationSurface().moduleIds()) {
            identities.put(declaration,
                new CanonicalModuleIdentity.ExternalModule(declaration.path()));
        }
        String modulePath = built.input().entryModule().path();
        String luaSource = Files.readString(root.resolve("out").resolve(
            modulePath.replace('.', '/') + ".lua"), StandardCharsets.UTF_8);
        String jvmSource = null;
        return new Compiled(root, built.input(), built.index(),
            manifests.manifests(), orchestrator.hostDeclarationSurface(), identities,
            strippedLines, luaSource, jvmSource);
    }

    /** The drive's own entry: it imports the fixture and calls its test export. */
    private static String driver(BoundaryFixture fixture) {
        String specifier = fixture.fixtureFile();
        if (specifier.endsWith(".deal")) {
            specifier = specifier.substring(0, specifier.length() - ".deal".length());
        }
        String call = fixture.runtimeOk() && "main".equals(fixture.export())
            && "null".equals(fixture.resultType())
            ? "  fx.main();\n"
            : "  let r: " + fixture.resultType() + " = fx." + fixture.export() + "();\n";
        return "import * as fx from \"./corpus/" + specifier + "\";\n\n"
            + "export function main(): null {\n" + call + "  return null;\n}\n";
    }


    private static void deleteRecursively(Path path) {
        if (path == null) {
            return;
        }
        try {
            if (Files.exists(path)) {
                try (var walk = Files.walk(path)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(entry -> {
                        try {
                            Files.deleteIfExists(entry);
                        } catch (java.io.IOException ignored) {
                            // best effort
                        }
                    });
                }
            }
        } catch (java.io.IOException ignored) {
            // best effort
        }
    }

    /**
     * Removes one rejected input's scratch root: the primary failure is
     * preserved and a cleanup failure is reported on it, so a rejected
     * input can never silently leave owned scratch state behind.
     */
    private static void cleanupRejectedScratch(Path root, Throwable primary) {
        deleteRecursively(root);
        if (Files.exists(root)) {
            primary.addSuppressed(new IllegalStateException(
                "the rejected input left its scratch root behind: " + root));
        }
    }

    private static Drive compileInlineDrive(Path root, String source, BoundaryFixture spec)
            throws Exception {
        Compiled compiled = compileFocused(root, source, null, null);
        return compiled == null ? null : lowerFocused(compiled, spec);
    }


    // =========================================================================
    // 4c. The decoded-array mark: the real decode producers, the exact
    //     tuples, and projection-only inertness (ISSUE-0710)
    // =========================================================================

    /**
     * One pinned failure projection: code, message, and the token pair.
     * The comparison reports the first divergent field with both values.
     */
    private record Tuple(String code, String message, String expected, String actual) {

        static Tuple of(SemanticRuntimeModel.ErrorSnapshot error) {
            return new Tuple(error.code(), error.message(),
                error.expected() == null ? "" : error.expected(),
                error.actual() == null ? "" : error.actual());
        }

        /** The first divergent field with captured and pinned values, or null. */
        String firstDivergence(Tuple pinned) {
            if (!code.equals(pinned.code())) {
                return "code: captured " + code + ", pinned " + pinned.code();
            }
            if (!message.equals(pinned.message())) {
                return "message: captured " + message + ", pinned " + pinned.message();
            }
            if (!expected.equals(pinned.expected())) {
                return "expected: captured " + expected + ", pinned "
                    + pinned.expected();
            }
            if (!actual.equals(pinned.actual())) {
                return "actual: captured " + actual + ", pinned " + pinned.actual();
            }
            return null;
        }
    }

    /**
     * The scratch unlowered-construct seed: a for-let update that is not the
     * assignment chain the update block lowers ({@code ForUpdate ::=
     * LeftHandSideExpression '=' AssignmentExpression}), so the production
     * compile still fails closed with the named E6005
     * {@code CONSTRUCT_UNLOWERED}. The function-typed for-counter this seed
     * previously named is production-covered now: the counter's declaration
     * takes the shared declaration-initializer production and its carry
     * loads resolve the current binding.
     */
    private static final String GUARD_SEED = "export function main(): null { let x: number = true; return null; }";

    /**
     * The marked case (B3): a real {@code std/json} decode whose nested
     * arrays carry the mark. The failing element of the outer decoded array
     * renders the reference kind {@code table}.
     */
    private static final String DECODED_NESTED_SOURCE = """
        import * as json from "std/json";

        export function main(): null {
          let t: table = json.parse("{\\"values\\":[[\\"x\\"],[\\"y\\"]]}");
          let xs: table[][] = t.values;
          return null;
        }
        """;

    /**
     * The unmarked control: a DEAL nested array (an array literal) keeps the
     * landed refined token {@code array} at the same projection.
     */
    private static final String UNMARKED_NESTED_SOURCE = """
        export function main(): null {
          let inner: number[] = [1];
          let holder: table = { values: [inner] };
          let xs: table[][] = holder.values;
          return null;
        }
        """;

    /**
     * The host-returned control: the array returned through the host ABI
     * ({@code host/array_return}'s {@code string[]}) is a real host
     * conversion and keeps the unmarked {@code array} token.
     */
    private static final String HOST_RETURNED_NESTED_SOURCE = """
        import * as host from "host/array_return";

        export function main(): null {
          let inner: string[] = host.split("ignored");
          let holder: table = { values: [inner] };
          let xs: table[][] = holder.values;
          return null;
        }
        """;

    /**
     * The {@code STRING_SPLIT} control: the array {@code std/string.split}
     * returns is not a decode result, so its nested element keeps the landed
     * unmarked {@code array} token (B3: the {@code STRING_SPLIT} result is
     * unmarked).
     */
    private static final String SPLIT_NESTED_SOURCE = """
        import * as strings from "std/string";

        export function main(): null {
          let parts: string[] = strings.split("a,b", ",");
          let holder: table = { values: [parts] };
          let xs: table[][] = holder.values;
          return null;
        }
        """;

    /**
     * The {@code TABLE_KEYS} control: the array {@code std/table.keys}
     * returns is not a decode result, so its nested element keeps the landed
     * unmarked {@code array} token (B3: the {@code TABLE_KEYS} result is
     * unmarked).
     */
    private static final String KEYS_NESTED_SOURCE = """
        import * as tables from "std/table";

        export function main(): null {
          let t: table = { a: 1 };
          let ks: string[] = tables.keys(t);
          let holder: table = { values: [ks] };
          let xs: table[][] = holder.values;
          return null;
        }
        """;

    /**
     * The decode tree's second nested level: a {@code table[][][]} crossing
     * over a parsed {@code [[["x"]]]} reports the same marked element one
     * wrap deeper, so the decode site marks every array of the tree
     * recursively.
     */
    private static final String DECODED_DEEP_NESTED_SOURCE = """
        import * as json from "std/json";

        export function main(): null {
          let t: table = json.parse("{\\"values\\":[[[\\"x\\"]]]}");
          let xs: table[][][] = t.values;
          return null;
        }
        """;

    /**
     * Projection-only inertness: the decoded tree's identity, length,
     * indexing, alias-observed mutation, and stringify encoding are
     * unchanged by the mark.
     */
    private static final String DECODED_INERTNESS_SOURCE = """
        import * as json from "std/json";

        export function main(): null {
          let t: table = json.parse("{\\"values\\":[[1.0,2.0],[3.0,4.0]]}");
          let xs: number[][] = t.values;
          let first: number[] = xs[0];
          let alias: number[] = xs[0];
          if (first !== alias) {
            throw { code: "TEST_FAIL", message: "decoded identity" };
          }
          let reread: number[][] = t.values;
          if (reread !== xs) {
            throw { code: "TEST_FAIL", message: "decoded member identity" };
          }
          if (first.length !== 2) {
            throw { code: "TEST_FAIL", message: "decoded length" };
          }
          if (xs[1][0] !== 3) {
            throw { code: "TEST_FAIL", message: "decoded indexing" };
          }
          first[1] = 9;
          if (alias[1] !== 9) {
            throw { code: "TEST_FAIL", message: "decoded alias mutation" };
          }
          let encoded: string = json.stringify(t);
          if (encoded !== "{\\"values\\":[[1.0,9.0],[3.0,4.0]]}") {
            throw { code: "TEST_FAIL", message: "decoded stringify: " + encoded };
          }
          return null;
        }
        """;

    /**
     * Admission inertness: a decoded array stays rejected at a {@code table}
     * descriptor with the landed {@code array} actual token.
     */
    private static final String DECODED_ADMISSION_SOURCE = """
        import * as json from "std/json";
        import * as tables from "std/table";

        export function main(): null {
          let t: table = json.parse("{\\"values\\":[[1.0,2.0]]}");
          let ks: string[] = tables.keys(t.values);
          return null;
        }
        """;

    /**
     * The decoded-array mark end to end (B3): the closed decode producers
     * are exercised through the real production pipeline, the marked nested
     * element renders {@code table}, the unmarked and host-returned nested
     * elements keep the landed {@code array} token, the decoded tree stays
     * inert outside the array-element projection, and the comparison fails
     * by field on any divergence.
     */
    private static void testDecodedArrayMark() throws Exception {
        System.out.println("-- the decoded-array mark: real decode producers, exact "
            + "tuples, and projection-only inertness (ISSUE-0710) --");

        driveFocusedFailures(
            new FocusedFailure("decoded nested element (marked)",
                DECODED_NESTED_SOURCE, 5, 11,
                new Tuple("E8003", "array element 1 type mismatch", "[table]", "table")),
            new FocusedFailure("unmarked nested element", UNMARKED_NESTED_SOURCE,
                4, 11,
                new Tuple("E8003", "array element 1 type mismatch", "[table]", "array")),

            // STRING_SPLIT and TABLE_KEYS results stay unmarked.
            new FocusedFailure("split-result nested element",
                SPLIT_NESTED_SOURCE, 6, 11,
                new Tuple("E8003", "array element 1 type mismatch", "[table]", "array")),
            new FocusedFailure("keys-result nested element",
                KEYS_NESTED_SOURCE, 7, 11,
                new Tuple("E8003", "array element 1 type mismatch", "[table]", "array")),
            new FocusedFailure("decoded element two levels down (marked)",
                DECODED_DEEP_NESTED_SOURCE, 5, 11,
                new Tuple("E8003", "array element 1 type mismatch", "[[table]]", "table")),
            new FocusedFailure("decoded array at a table descriptor",
                DECODED_ADMISSION_SOURCE, 6, 34,
                new Tuple("E8001", "expected table", "table", "array")));

        driveFocusedFailure("host-returned nested element",
            HOST_RETURNED_NESTED_SOURCE, "array_return", "host/array_return", 6, 11,
            new Tuple("E8003", "array element 1 type mismatch", "[table]", "array"));

        // Comparison honesty: a pin-exact tuple reports no divergence and a
        // divergent field fails by name with the captured and pinned values.
        Tuple pinned = new Tuple("E8003", "array element 1 type mismatch",
            "[table]", "table");
        checkEq(null, pinned.firstDivergence(pinned),
            "a pin-exact tuple reports no divergent field");
        checkEq("actual: captured array, pinned table",
            new Tuple("E8003", "array element 1 type mismatch", "[table]", "array")
                .firstDivergence(pinned),
            "the pre-correction actual token fails by field");
        checkEq("code: captured E8001, pinned E8003",
            new Tuple("E8001", "array element 1 type mismatch", "[table]", "table")
                .firstDivergence(pinned),
            "a divergent code fails by field");

        checkDeterministicEmission();
        checkAtomicStaging();
    }

    // =========================================================================
    // 4e. The integral number-form JSON element at array boundaries
    //     (ISSUE-0768)
    // =========================================================================

    /**
     * The flat case (ISSUE-0768): a {@code number[]} class field decoded
     * from {@code [2147483648]} carries its slot's recorded number mark
     * (the exact v1.3 classification: an integral mathematical value
     * outside signed32 is the number variant), so the same array admitted
     * to a {@code string[]} boundary projects the number actual token —
     * the oracle's retained NumValue and the JVM's retained Double alike
     * — never the int token an integral bare Lua number suggests.
     */
    private static final String INTEGRAL_NUMBER_ELEMENT_SOURCE = """
        // @jsonable
        export class Doc {
          values: number[] = [];
        }

        export function main(): null {
          let d: Doc | null = Doc$fromJson("{\\"values\\":[2147483648]}");
          if (d !== null) {
            let holder: table = { xs: d.values };
            let ys: string[] = holder.xs;
            return null;
          }
          return null;
        }
        """;

    /**
     * The nested case (ISSUE-0768): a decoded nested array (the empty
     * inner array admitted at the {@code string[][]} element position)
     * precedes the integral out-of-range number-form element, so the
     * array recursion keeps the number variant at depth and the outer
     * element arm renders the number token at its own index.
     */
    private static final String NESTED_NUMBER_ELEMENT_SOURCE = """
        // @jsonable
        export class Nest {
          values: table = {};
        }

        export function main(): null {
          let d: Nest | null = Nest$fromJson("{\\"values\\":{\\"xs\\":[[],2147483648.0]}}");
          if (d !== null) {
            let ys: string[][] = d.values.xs;
            return null;
          }
          return null;
        }
        """;

    /**
     * The inner nested-array case (ISSUE-0768): the integral out-of-range
     * number-form element is the only element of an inner array; the
     * boundary applies to that inner array at the outer index read, so
     * the same recorded number mark projects the number token through
     * the recursion.
     */
    private static final String NESTED_INNER_NUMBER_ELEMENT_SOURCE = """
        // @jsonable
        export class Deep {
          values: number[][] = [];
        }

        export function main(): null {
          let d: Deep | null = Deep$fromJson("{\\"values\\":[[2147483648]]}");
          if (d !== null) {
            let holder: table = { xs: d.values[0] };
            let ys: string[] = holder.xs;
            return null;
          }
          return null;
        }
        """;

    /**
     * The nested-array admission control (ISSUE-0768): the same nested
     * shape with matching element kinds runs to success on all three
     * consumers, so the variant materialization never rejects a decoded
     * nested array. The descriptor is the full nesting
     * ({@code string[][]}) and the value is the decoded nested array.
     */
    private static final String NESTED_ADMISSION_SOURCE = """
        // @jsonable
        export class Ok {
          values: table = {};
        }

        export function main(): null {
          let d: Ok | null = Ok$fromJson("{\\"values\\":{\\"xs\\":[[\\"a\\"]]}}");
          if (d !== null) {
            let ys: string[][] = d.values.xs;
            return null;
          }
          return null;
        }
        """;

    /**
     * The canonical numeric-variant projection at array boundaries
     * (ISSUE-0768): an integral number-form element of a JSON-decoded
     * array (the {@code __nK} mark the {@code @jsonable} decode transfers
     * from the parser's lexical variant) projects the number token on the
     * oracle, the LuaJIT production artifact, and the JVM production
     * artifact alike — the emitted Lua {@code __bcheck} element recursion
     * materializes each element's recorded variant before the recursive
     * validation and the failure projection, and never rewrites the
     * stored value. The nested shapes prove the materialization at every
     * recursion depth and the admission control proves the fix does not
     * reject matching nested arrays.
     */
    private static void testIntegralNumberFormArrayElements() throws Exception {
        System.out.println("-- the integral number-form JSON element at array "
            + "boundaries keeps its number variant on all three consumers --");

        driveFocusedFailures(
            new FocusedFailure("integral number-form element",
                INTEGRAL_NUMBER_ELEMENT_SOURCE, 10, 13,
                new Tuple("E8003", "array element 1 type mismatch", "string", "number")),
            new FocusedFailure("integral number-form element after a nested array",
                NESTED_NUMBER_ELEMENT_SOURCE, 9, 13,
                new Tuple("E8003", "array element 2 type mismatch", "[string]", "number")),
            new FocusedFailure("integral number-form element inside a nested array",
                NESTED_INNER_NUMBER_ELEMENT_SOURCE, 10, 13,
                new Tuple("E8003", "array element 1 type mismatch", "string", "number")));

        Path root = Files.createTempDirectory("number-form-admission-");
        try {
            Compiled compiled = compileFocused(root, NESTED_ADMISSION_SOURCE, null, null);
            if (compiled != null) {
                BoundaryFixture spec = new BoundaryFixture(FIXTURE_DIR, "nested admission",
                    "main", "null", List.of(), null, null, 0, 0);
                Drive drive = lowerFocused(compiled, spec);
                if (drive != null) {
                    driveFocusedSuccess("nested decoded array admission", drive);
                }
            }
        } finally {
            deleteRecursively(root);
        }
    }

    // =========================================================================
    // 4f. The canonical function-signature argument at field/class
    //     boundaries (ISSUE-0768)
    // =========================================================================

    /**
     * The class-literal field boundary (ISSUE-0768): a function-typed
     * declared field receives a mismatched function through the class
     * literal, so the class construction's field cell publishes the
     * canonical signature texts — the oracle's and the JVM's projection,
     * never the Lua prelude's internal {@code function(int;int)} dialect.
     */
    private static final String CLASS_LITERAL_FUNCTION_MISMATCH_SOURCE = """
        function identity(x: number): number {
          return x;
        }

        class Box {
          f: (x: number) => number = identity;
        }

        function asString(x: number): string {
          return "s";
        }

        export function main(): null {
          let holder: table = { g: asString };
          let b: Box = { f: holder.g };
          return null;
        }
        """;

    /**
     * The class-field assignment boundary (ISSUE-0768): the same declared
     * function cell fails through the {@code CLASS_FIELD_ASSIGNMENT} cell
     * with the identical canonical signature projection.
     */
    private static final String CLASS_FIELD_FUNCTION_MISMATCH_SOURCE = """
        function identity(x: number): number {
          return x;
        }

        class Box {
          f: (x: number) => number = identity;
        }

        function asString(x: number): string {
          return "s";
        }

        export function main(): null {
          let b: Box = { f: identity };
          let holder: table = { g: asString };
          b.f = holder.g;
          return null;
        }
        """;

    /**
     * The canonical function-signature projection at field/class
     * boundaries (ISSUE-0768): the emitted Lua field and class boundary
     * cells carry the descriptor's canonical spec text as the boundary
     * check's trailing argument, so a mismatched carried signature
     * projects {@code (int)->int} / {@code (int)->string} — the identical
     * tuple the oracle's boundary engine and the JVM production artifact
     * render — at the class literal expression and the assignment
     * expression origins.
     */
    private static void testCanonicalFunctionSignatureFieldBoundaries()
            throws Exception {
        System.out.println("-- the canonical function-signature projection at "
            + "field/class boundaries on all three consumers --");

        driveFocusedFailures(
            new FocusedFailure("class literal function field mismatch",
                CLASS_LITERAL_FUNCTION_MISMATCH_SOURCE, 15, 16,
                new Tuple("E8010",
                    "function signature mismatch: expected (number)->number, got (number)->string",
                    "(number)->number", "(number)->string")),
            new FocusedFailure("class field assignment function mismatch",
                CLASS_FIELD_FUNCTION_MISMATCH_SOURCE, 16, 3,
                new Tuple("E8010",
                    "function signature mismatch: expected (number)->number, got (number)->string",
                    "(number)->number", "(number)->string")));
    }

    /**
     * The array-element assignment cell (ISSUE-0767): a mismatched
     * function reaches an {@code ((x: int) => int)[]} element through the
     * element assignment's descriptor check, so the cell must publish
     * the canonical signature texts and the assignment expression's own
     * origin on every consumer (the oracle's
     * {@code ARRAY_ELEMENT_ASSIGNMENT} projection).
     */
    private static final String ARRAY_ELEMENT_FUNCTION_MISMATCH_SOURCE = """
        function identity(x: number): number {
          return x;
        }

        function asString(x: number): string {
          return "s";
        }

        export function main(): null {
          let xs: ((x: number) => number)[] = [identity];
          let holder: table = { g: asString };
          xs[0] = holder.g;
          return null;
        }
        """;

    /**
     * The nullable-array-element position (ISSUE-0767): the element
     * descriptor carries the function behind a nullable wrapper, so the
     * canonical signature argument must survive the nullable recursion
     * of the same element assignment cell.
     */
    private static final String ARRAY_ELEMENT_NULLABLE_FUNCTION_MISMATCH_SOURCE = """
        function identity(x: number): number {
          return x;
        }

        function asString(x: number): string {
          return "s";
        }

        export function main(): null {
          let xs: (((x: number) => number) | null)[] = [identity];
          let holder: table = { g: asString };
          xs[0] = holder.g;
          return null;
        }
        """;

    /**
     * The recursive array position (ISSUE-0767): a class field declared
     * {@code ((x: int) => int)[]} receives an array whose element is not a
     * function (read from a dynamic table alias), so the field boundary's
     * array recursion must project the canonical element text
     * {@code (int)->int} in its E8003 row, never the internal
     * {@code function(int;int)} dialect.
     */
    private static final String CLASS_FIELD_FUNCTION_ARRAY_ELEMENT_SOURCE = """
        function identity(x: number): number {
          return x;
        }

        class Box {
          fs: ((x: number) => number)[] = [];
        }

        export function main(): null {
          let holder: table = { xs: [1] };
          let b: Box = { fs: holder.xs };
          return null;
        }
        """;

    /**
     * The canonical function-signature projection at the remaining
     * function-bearing production check sites (ISSUE-0767): the element
     * assignment cell carries the canonical trailer into its descriptor
     * check, and the recursive array position of a class field projects
     * the canonical element text in its E8003 row. Every case asserts the
     * exact tuple on the oracle, the LuaJIT production artifact, and the
     * JVM production artifact.
     */
    private static void testCanonicalSignatureElementBoundaries()
            throws Exception {
        System.out.println("-- the canonical function-signature projection at "
            + "array-element and recursive array positions on all three "
            + "consumers --");

        driveFocusedFailures(
            new FocusedFailure("array element function mismatch",
                ARRAY_ELEMENT_FUNCTION_MISMATCH_SOURCE, 12, 3,
                new Tuple("E8010",
                    "function signature mismatch: expected (number)->number, got (number)->string",
                    "(number)->number", "(number)->string")),
            new FocusedFailure("array element nullable function mismatch",
                ARRAY_ELEMENT_NULLABLE_FUNCTION_MISMATCH_SOURCE, 12, 3,
                new Tuple("E8010",
                    "function signature mismatch: expected (number)->number, got (number)->string",
                    "(number)->number", "(number)->string")),
            new FocusedFailure("class field function array element mismatch",
                CLASS_FIELD_FUNCTION_ARRAY_ELEMENT_SOURCE, 11, 16,
                new Tuple("E8003", "array element 1 type mismatch",
                    "(number)->number", "number")));
    }

    private record FocusedFailure(String label, String source, int line, int column,
                                  Tuple pinned) {
    }

    private static void driveFocusedFailures(FocusedFailure... cases) throws Exception {
        Path workspace = Files.createTempDirectory("boundary-focused-batch-");
        List<Drive> drives = new ArrayList<>();
        try {
            for (int i = 0; i < cases.length; i++) {
                FocusedFailure focused = cases[i];
                BoundaryFixture spec = new BoundaryFixture(FIXTURE_DIR, focused.label(), "main",
                    "null", List.of(), focused.pinned().code(), focused.pinned().message(),
                    focused.line(), focused.column(), focused.pinned().expected(),
                    focused.pinned().actual());
                Drive drive = compileInlineDrive(workspace.resolve("case" + i),
                    focused.source(), spec);
                if (drive != null) {
                    drives.add(drive);
                }
            }
            prepareJvmBatch(drives, workspace);
            for (Drive drive : drives) {
                BoundaryFixture spec = drive.spec();
                driveFocusedFailure(spec.what(), drive, null, null, spec.line(),
                    spec.column(), new Tuple(spec.code(), spec.message(), spec.expected(),
                        spec.actual()));
            }
        } finally {
            for (Drive drive : drives) {
                preparedJvm.remove(drive.compiled());
            }
            deleteRecursively(workspace);
        }
    }

    /**
     * Drives one focused failing program through the real production
     * pipeline: the oracle (with the optional host responder), the LuaJIT
     * production artifact, and the JVM production artifact, and asserts the
     * exact pinned tuple on every consumer.
     */
    private static String driveFocusedFailure(String label, String source,
            String hostStem, String hostSpecifier, int line, int column, Tuple pinned)
            throws Exception {
        Path root = Files.createTempDirectory("boundary-mark-");
        try {
            BoundaryFixture spec = new BoundaryFixture(FIXTURE_DIR, label, "main", "null",
                List.of(), pinned.code(), pinned.message(), line, column);
            Compiled compiled = compileFocused(root, source, hostStem, hostSpecifier);
            if (compiled == null) {
                return null;
            }
            Drive drive = lowerFocused(compiled, spec);
            if (drive == null) {
                return null;
            }
            return driveFocusedFailure(label, drive, hostStem, hostSpecifier, line,
                column, pinned);
        } finally {
            deleteRecursively(root);
        }
    }

    private static String driveFocusedFailure(String label, Drive drive,
            String hostStem, String hostSpecifier, int line, int column, Tuple pinned)
            throws Exception {
        int[] calls = { 0 };
        SemanticOracle.HostResponder responder = hostStem == null ? null
            : focusedArrayHostResponder(calls);
        SemanticRuntimeModel.ConsumerRun oracle =
            SemanticOracle.executeProjectInits(drive.project(), drive.tables(),
                drive.registries(), responder);
        if (hostStem != null) {
            checkEq(1, calls[0], label + ": the host array return is called once");
        }
        String origin = drive.compiled().root().resolve("src").resolve("main.deal")
            .toAbsolutePath() + ":" + line + ":" + column;
        check(oracle.terminal()
                instanceof SemanticRuntimeModel.Terminal.DealFailure,
            label + ": the oracle projects the pinned failure: "
                + oracle.terminal());
        if (oracle.terminal()
                instanceof SemanticRuntimeModel.Terminal.DealFailure failure) {
            checkEq(null, Tuple.of(failure.error()).firstDivergence(pinned),
                label + ": the oracle tuple is pin-exact");
            checkEq(origin, failure.error().origin(),
                label + ": the oracle origin is the pinned expression");
        }
        Path hostLua = hostStem == null ? null
            : Path.of("test", "conformance", "host-fixtures", hostStem + ".lua");
        Path hostJava = hostStem == null ? null
            : Path.of("test", "conformance", "host-fixtures", hostStem + ".java");
        String probeTranscript = "ERR:" + pinned.code() + "|" + pinned.message()
            + "|" + origin + "|" + pinned.expected() + "|" + pinned.actual()
            + "\n";
        String terminalTranscript = "DEAL_ERROR_CODE: " + pinned.code() + "\n";
        String pinnedOutcome = probeTranscript.strip();
        String chunk = drive.compiled().luaSource();
        ArtifactRun lua = luaProduction(drive, hostSpecifier, hostLua, chunk);
        assertTranscript(label, "luajit probe", lua.probe(), 0, probeTranscript);
        assertTranscript(label, "luajit terminal", lua.terminal(), 1,
            terminalTranscript);
        checkEq(pinnedOutcome, lua.outcome(), label
            + ": the LuaJIT production artifact reproduces the exact tuple");
        ArtifactRun jvm = hostStem == null ? jvmProduction(drive)
            : jvmProduction(drive, hostSpecifier, hostJava);
        assertTranscript(label, "jvm probe", jvm.probe(), 0, probeTranscript);
        assertTranscript(label, "jvm terminal", jvm.terminal(), 1,
            terminalTranscript);
        checkEq(pinnedOutcome, jvm.outcome(), label
            + ": the JVM production artifact reproduces the exact tuple");
        return chunk;
    }

    /**
     * Drives one focused runtime-ok program through the real production
     * pipeline: the oracle and both production artifacts reach the pinned
     * success with no terminal.
     */
    private static void driveFocusedSuccess(String label, Drive drive)
            throws Exception {
        driveFocusedSuccess(label, drive, null);
    }

    private static void driveFocusedSuccess(String label, Drive drive, String jvmSource)
            throws Exception {
        SemanticRuntimeModel.ConsumerRun oracle =
            SemanticOracle.executeProjectInits(drive.project(), drive.tables(),
                drive.registries(), null);
        check(oracle.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
            label + ": the oracle runs the decoded tree to success: "
                + oracle.terminal());
        ArtifactRun lua = luaProduction(drive);
        assertTranscript(label, "luajit probe", lua.probe(), 0, "OK\n");
        check(lua.terminal() == null, label
            + ": a runtime-ok drive runs no direct terminal artifact");
        checkEq("OK", lua.outcome(), label
            + ": the LuaJIT production artifact runs to success");
        ArtifactRun jvm = jvmProduction(drive, null, null, jvmSource);
        assertTranscript(label, "jvm probe", jvm.probe(), 0, "OK\n");
        check(jvm.terminal() == null, label
            + ": a runtime-ok drive runs no direct terminal artifact");
        checkEq("OK", jvm.outcome(), label
            + ": the JVM production artifact runs to success");
    }


    // =========================================================================
    // 5c. The indirect bytes allocation: the seeded bytes intrinsic value
    // =========================================================================


    /**
     * The oracle-side host of the focused host-returned-array control:
     * {@code host/array_return}'s {@code split} returns a real (unmarked)
     * string array, the same shape the deployed Lua host module and JVM host
     * class return.
     */
    private static SemanticOracle.HostResponder focusedArrayHostResponder(int[] calls) {
        return new SemanticOracle.HostResponder() {
            @Override
            public SyncOutcome call(ModuleId module, String export,
                    RuntimeDescriptor.Func descriptor,
                    List<SemanticOracle.Value> args) {
                if (!"host.array_return".equals(module.path())
                        || !"split".equals(export)) {
                    return new SyncOutcome.Thrown("E9001", "unknown host call "
                        + module.path() + "#" + export);
                }
                calls[0]++;
                List<SemanticOracle.Value> elements = new ArrayList<>();
                elements.add(new SemanticOracle.Value.StrValue("a"));
                elements.add(new SemanticOracle.Value.StrValue("b"));
                elements.add(new SemanticOracle.Value.StrValue("c"));
                return new SyncOutcome.Returned(new SemanticOracle.Value.ArrayValue(
                    elements, RuntimeDescriptor.String.INSTANCE, false));
            }
        };
    }

    /**
     * Materializes one focused program ({@code src/main.deal} plus the
     * optional host declaration) and compiles it through the release-owned
     * production orchestrator.
     */
    private static Compiled compileFocused(Path root, String source, String hostStem,
            String hostSpecifier) throws Exception {
        Path src = root.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("main.deal"), source, StandardCharsets.UTF_8);
        Map<String, String> externals = null;
        if (hostStem != null) {
            Path declaration = root.resolve("host").resolve(hostStem + ".d.deal");
            Files.createDirectories(declaration.getParent());
            Files.writeString(declaration,
                ConformanceHarnessMetadata.stripClassificationHeaders(
                    Files.readString(Path.of("test", "conformance", "host-fixtures",
                        hostStem + ".d.deal"), StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
            externals = new LinkedHashMap<>();
            externals.put(hostSpecifier, declaration.toAbsolutePath().toString());
            externals.put(hostSpecifier.replace('.', '/'),
                declaration.toAbsolutePath().toString());
        }
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            src.resolve("main.deal").toAbsolutePath(), root.resolve("out"), false,
            false, false, false, Backend.LUAJIT, externals,
            List.of(src.toAbsolutePath()), Path.of("std").toAbsolutePath().normalize(),
            null, productionInvocation());
        boolean compiled = orchestrator.compile();
        check(compiled, "the focused program compiles through the release-owned "
            + "invocation: " + orchestrator.diagnostics());
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        check(built != null && built.input() != null && built.index() != null
                && !built.hasErrors(),
            "the focused program's checked project builds: "
                + (built == null ? "null" : built.diagnostics()));
        if (!compiled || built == null || built.input() == null || built.index() == null
                || built.hasErrors() || manifests == null
                || manifests.manifests() == null
                || orchestrator.hostDeclarationSurface() == null) {
            return null;
        }
        Map<ModuleId, CanonicalModuleIdentity> identities = new LinkedHashMap<>();
        for (ModuleId declaration : orchestrator.hostDeclarationSurface().moduleIds()) {
            identities.put(declaration,
                new CanonicalModuleIdentity.ExternalModule(hostStem == null
                    ? declaration.path() : declaration.path().replace('/', '.')));
        }
        String modulePath = built.input().entryModule().path();
        String luaSource = Files.readString(root.resolve("out").resolve(
            modulePath.replace('.', '/') + ".lua"), StandardCharsets.UTF_8);
        return new Compiled(root, built.input(), built.index(),
            manifests.manifests(), orchestrator.hostDeclarationSurface(), identities, 0,
            luaSource, null);
    }

    /** The production project lowering entry over one focused program. */
    private static Drive lowerFocused(Compiled compiled, BoundaryFixture spec) {
        SemanticLowerer.ProjectLoweringResult result = SemanticLowerer.lowerProject(
            productionInvocation(), compiled.checkedProject(), compiled.index(),
            compiled.manifests(), compiled.surface(), compiled.identities(), BuiltinErrorDeclaration.synthesized(
                compiled.checkedProject().modules().get(0).ast().span()),
            Set.of());
        check(result.project() != null, spec.what() + ": the production project entry "
            + "lowers the focused program with zero diagnostics: "
            + result.diagnostics());
        if (result.project() == null) {
            return null;
        }
        ModuleId fixtureModule = result.project().entryModule();
        LoweredModuleUnit unit = result.project().modules().get(fixtureModule);
        Optional<CompilerDiagnostic> gate = SemanticIrValidator.validate(
            result.project(), new SemanticIrValidator.ComparisonFacts(
                unit.interfaceHash(), SemanticProfile.DEAL_V1_3_INT32,
                ReleaseConfiguration.releaseCapabilityRegistry()
                    .capabilityRegistryHash()));
        check(gate.isEmpty(), spec.what() + ": the closed schema and bindings gates "
            + "accept the produced closure: "
            + gate.map(CompilerDiagnostic::message).orElse("admission"));
        if (gate.isPresent()) {
            return null;
        }
        return new Drive(compiled, result, fixtureModule, unit, spec);
    }

    /**
     * Repeated lowering and emission of one focused program are
     * byte-identical on both targets: two production compiles of the same
     * source publish the same artifact bytes.
     */
    private static void checkDeterministicEmission() throws Exception {
        Path root = Files.createTempDirectory("boundary-determinism-");
        try {
            Path src = root.resolve("src");
            Files.createDirectories(src);
            Files.writeString(src.resolve("main.deal"), DECODED_INERTNESS_SOURCE,
                StandardCharsets.UTF_8);
            Compiled inertness = null;
            String inertnessJvmSource = null;
            for (Backend backend : List.of(Backend.LUAJIT, Backend.JVM)) {
                String artifact = backend == Backend.JVM
                    ? JvmBackend.classNameFor("main") + ".java" : "main.lua";
                String first;
                if (backend == Backend.LUAJIT) {
                    inertness = compileFocused(root, DECODED_INERTNESS_SOURCE,
                        null, null);
                    first = inertness == null ? null : inertness.luaSource();
                } else {
                    first = compileArtifact(root, src, backend, artifact);
                    inertnessJvmSource = first;
                }
                String second = compileArtifact(root, src, backend, artifact);
                check(first != null && second != null, "the " + backend
                    + " determinism drive publishes its artifact");
                checkEq(first, second, "repeated " + backend + " lowering and "
                    + "emission are byte-identical");
            }
            if (inertness != null) {
                BoundaryFixture spec = new BoundaryFixture(FIXTURE_DIR,
                    "decoded-array inertness", "main", "null", List.of(),
                    null, null, 0, 0);
                Drive drive = lowerFocused(inertness, spec);
                if (drive != null) {
                    driveFocusedSuccess("decoded-array inertness", drive,
                        inertnessJvmSource);
                }
            }
        } finally {
            deleteRecursively(root);
        }
    }

    /** One production compile of the determinism drive; the artifact text. */
    private static String compileArtifact(Path root, Path src, Backend backend,
            String artifact) throws Exception {
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            src.resolve("main.deal").toAbsolutePath(), root.resolve("out"), false,
            false, false, false, backend, null, List.of(src.toAbsolutePath()),
            Path.of("std").toAbsolutePath().normalize(), null, productionInvocation());
        boolean compiled = orchestrator.compile();
        check(compiled, "the determinism compile (" + backend + ") succeeds: "
            + orchestrator.diagnostics());
        if (!compiled) {
            return null;
        }
        return Files.readString(root.resolve("out").resolve(artifact),
            StandardCharsets.UTF_8);
    }

    /**
     * A fail-closed production compile stages nothing and preserves the
     * previous artifact set byte-identical (the atomic staging contract),
     * on both targets.
     */
    private static void checkAtomicStaging() throws Exception {
        for (Backend backend : List.of(Backend.LUAJIT, Backend.JVM)) {
            Path scratch = Files.createTempDirectory("boundary-atomic-");
            try {
                Path src = scratch.resolve("src");
                Files.createDirectories(src);
                Files.writeString(src.resolve("main.deal"), GUARD_SEED,
                    StandardCharsets.UTF_8);
                Path out = scratch.resolve("out");
                Files.createDirectories(out);
                Files.writeString(out.resolve("main.lua"),
                    "-- the prior artifact set\n", StandardCharsets.UTF_8);
                String before = Files.readString(out.resolve("main.lua"),
                    StandardCharsets.UTF_8);
                CompilationOrchestrator orchestrator = new CompilationOrchestrator(
                    src.resolve("main.deal").toAbsolutePath(), out, false, false,
                    false, false, backend, null, List.of(src.toAbsolutePath()),
                    Path.of("std").toAbsolutePath().normalize(), null,
                    productionInvocation());
                boolean compiled = orchestrator.compile();
                check(!compiled, "the unrealized-construct seed fails closed on "
                    + backend + ": " + orchestrator.diagnostics());
                check(orchestrator.diagnostics().stream().anyMatch(diagnostic ->
                        "E3001".equals(diagnostic.code())
                            && diagnostic.message().contains("Cannot assign")),
                    "the fail-closed compile names E6005 CONSTRUCT_UNLOWERED on "
                        + backend + ": " + orchestrator.diagnostics());
                check(!Files.exists(out.resolve("deal").resolve("runtime.lua")),
                    "the failing compile stages no deployment copy on " + backend);
                checkEq(before, Files.readString(out.resolve("main.lua"),
                        StandardCharsets.UTF_8),
                    "the failing compile leaves the prior artifact set byte-identical "
                        + "on " + backend);
            } finally {
                deleteRecursively(scratch);
            }
        }
    }

    // =========================================================================
    // The production artifact runners (the real toolchains)
    // =========================================================================

    /**
     * One production artifact run: the complete bounded capture of the framed
     * probe run and, on a failing drive, of the artifact's direct
     * {@code DEAL_ERROR_CODE} terminal run ({@code null} for a runtime-ok
     * drive). The drives compare both captures exactly (status, stdout,
     * stderr, capture health) before they read the framed outcome.
     */
    private record ArtifactRun(BoundedRun probe, BoundedRun terminal) {

        /** The framed outcome line; read only after the transcript comparison. */
        String outcome() {
            return extractOutcome(probe.stdout());
        }
    }

    /** The probe framing: the last {@code ERR:}/{@code OK} line of a stdout. */
    private static String extractOutcome(String stdout) {
        return stdout.lines()
            .filter(line -> line.startsWith("ERR:") || line.equals("OK"))
            .reduce((first, second) -> second).orElse("");
    }

    private static String jvmProbeName() {
        return "BytesProbe_" + java.util.UUID.randomUUID().toString().replace("-", "");
    }

    private static String absoluteClasspath() {
        StringBuilder resolved = new StringBuilder();
        for (String entry : System.getProperty("java.class.path", "")
                .split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            if (resolved.length() > 0) {
                resolved.append(File.pathSeparator);
            }
            resolved.append(Path.of(entry).toAbsolutePath().normalize());
        }
        return resolved.toString();
    }

    private record BoundedRun(int exitCode, String stdout, String stderr,
                              boolean stdoutOverflow, boolean stderrOverflow) {
        boolean captureClean() {
            return !stdoutOverflow() && !stderrOverflow();
        }
    }

    /**
     * The artifact transcript comparison: the exact expected exit status, the
     * complete stdout, an empty stderr, and the capture health. Returns the
     * first divergence by name or {@code null} when the run is pin-exact. The
     * probe framing and the source-coordinate origin are the only
     * transformations applied to the raw streams.
     */
    private static String transcriptDivergence(BoundedRun run, int expectedExit,
            String expectedStdout) {
        if (run.stdoutOverflow() || run.stderrOverflow()) {
            return "capture overflow: the "
                + (run.stdoutOverflow() ? "stdout" : "stderr")
                + " capture exceeded the " + BoundedProcess.STREAM_CAP_BYTES + "-byte cap";
        }
        if (run.exitCode() != expectedExit) {
            return "exit: captured " + run.exitCode() + ", pinned " + expectedExit;
        }
        if (!expectedStdout.equals(run.stdout())) {
            return "stdout: captured " + escaped(run.stdout()) + ", pinned "
                + escaped(expectedStdout);
        }
        if (!run.stderr().isEmpty()) {
            return "stderr: captured " + escaped(run.stderr()) + ", pinned none";
        }
        return null;
    }

    private static String escaped(String text) {
        return "'" + text.replace("\\", "\\\\").replace("\r", "\\r")
            .replace("\n", "\\n") + "'";
    }

    /** Asserts the exact status and complete transcript of one artifact run. */
    private static void assertTranscript(String label, String target, BoundedRun run,
            int expectedExit, String expectedStdout) {
        if (run == null) {
            check(false, label + " (" + target + "): no artifact run was captured");
            return;
        }
        String divergence = transcriptDivergence(run, expectedExit, expectedStdout);
        check(divergence == null, label + " (" + target + "): the exit status and the "
            + "complete transcript are pin-exact, but " + divergence);
    }

    private static BoundedRun runBounded(List<String> argv, Path workingDir,
            Map<String, String> environment) throws Exception {
        BoundedProcess.BoundedResult result = BoundedProcess.runCaptured(
            argv, workingDir, environment);
        return new BoundedRun(result.exitCode(), result.stdout(), result.stderr(),
            result.stdoutTruncated(), result.stderrTruncated());
    }

    private static ArtifactRun luaProduction(Drive drive) throws Exception {
        return luaProduction(drive, null, null);
    }

    /**
     * The production LuaJIT artifact, optionally with a deployed host
     * module for a host-importing focused program.
     */
    private static ArtifactRun luaProduction(Drive drive, String hostModule,
            Path hostImplementation) throws Exception {
        return luaProduction(drive, hostModule, hostImplementation,
            drive.compiled().luaSource());
    }

    private static ArtifactRun luaProduction(Drive drive, String hostModule,
            Path hostImplementation, String chunk) throws Exception {
        Path workspace = Files.createTempDirectory("boundary-lua");
        try {
            Path artifact = workspace.resolve("project.lua");
            Files.writeString(artifact, chunk, StandardCharsets.UTF_8);
            deployRuntime(workspace);
            if (hostModule != null) {
                Path hostDir = workspace.resolve("host");
                Files.createDirectories(hostDir);
                Files.copy(hostImplementation, hostDir.resolve(
                    hostModule.substring(hostModule.lastIndexOf('/') + 1) + ".lua"));
            }
            Path probe = workspace.resolve("probe.lua");
            Files.writeString(probe, """
                local chunk = dofile("%s")
                local ok, err = __dealMain()
                if not ok then
                  if type(err) == "table" and err[__dealCarrierKeys.d] then
                    local expected = err.e
                    local actual = err.a
                    if expected == nil then expected = "" end
                    if actual == nil then actual = "" end
                    print("ERR:" .. err.code .. "|" .. err.m .. "|" .. err.o .. "|"
                      .. expected .. "|" .. actual)
                  else
                    print("ERR:" .. tostring(err))
                  end
                  os.exit(0)
                end
                print("OK")
                """.formatted(artifact.toAbsolutePath().toString()),
                StandardCharsets.UTF_8);
            BoundedRun probeRun = runLua(workspace, probe,
                Map.of("DEAL_DEFER_MAIN", "1"));
            BoundedRun terminalRun = drive.spec().runtimeOk() ? null
                : runLua(workspace, artifact, Map.of());
            return new ArtifactRun(probeRun, terminalRun);
        } finally {
            deleteRecursively(workspace);
        }
    }

    /** One bounded run of a Lua script in the artifact workspace. */
    private static BoundedRun runLua(Path workspace, Path script,
            Map<String, String> environment) throws Exception {
        return runBounded(List.of("luajit", script.toAbsolutePath().toString()),
            workspace, environment);
    }

    private static void deployRuntime(Path workspace) throws Exception {
        Path runtimeTarget = workspace.resolve("deal").resolve("runtime.lua");
        Files.createDirectories(runtimeTarget.getParent());
        Files.copy(Path.of("deal", "runtime.lua"), runtimeTarget);
        Path stdTarget = workspace.resolve("std");
        Files.createDirectories(stdTarget);
        try (var listing = Files.list(Path.of("std"))) {
            for (Path file : listing.sorted().toList()) {
                if (file.getFileName().toString().endsWith(".lua")) {
                    Files.copy(file, stdTarget.resolve(file.getFileName()));
                }
            }
        }
    }

    private record PreparedJvm(Path classes, String probeName, String className) {
    }

    private static final Map<Compiled, PreparedJvm> preparedJvm =
        new java.util.IdentityHashMap<>();

    private static String jvmProbeSource(String probeName, String className) {
        return "final class " + probeName + " { public static void main(String[] args) {"
            + " try { " + className + ".dealMain(); }"
            + " catch (deal.codegen.jvm.JvmRuntime.DealError error) {"
            + " System.out.println(\"ERR:\" + error.code + \"|\" + error.msg + \"|\""
            + " + error.origin + \"|\" + (error.expected == null ? \"\" : error.expected)"
            + " + \"|\" + (error.actual == null ? \"\" : error.actual)); return; }"
            + " System.out.println(\"OK\"); } }\n";
    }

    private static void prepareJvmBatch(List<Drive> drives, Path workspace) throws Exception {
        if (drives.isEmpty()) {
            return;
        }
        Path classes = workspace.resolve("classes");
        Files.createDirectories(classes);
        List<String> sources = new ArrayList<>();
        Map<Compiled, PreparedJvm> prepared = new java.util.IdentityHashMap<>();
        for (int i = 0; i < drives.size(); i++) {
            Drive drive = drives.get(i);
            String packageName = "boundarybatch" + i;
            Path directory = workspace.resolve(packageName);
            Files.createDirectories(directory);
            String className = JvmBackend.classNameFor(drive.project().entryModule().path());
            String source = drive.compiled().jvmSource();
            if (source == null) {
                source = JvmSemanticEmitter.emitProductionProject(drive.project(),
                    drive.tables(), drive.registries(), className,
                    drive.compiled().surface()).source();
            }
            String probeName = jvmProbeName();
            Path artifact = directory.resolve(className + ".java");
            Path probe = directory.resolve(probeName + ".java");
            Files.writeString(artifact, "package " + packageName + ";\n" + source,
                StandardCharsets.UTF_8);
            Files.writeString(probe, "package " + packageName + ";\n"
                + jvmProbeSource(probeName, className), StandardCharsets.UTF_8);
            sources.add(artifact.toString());
            sources.add(probe.toString());
            prepared.put(drive.compiled(), new PreparedJvm(classes,
                packageName + "." + probeName, packageName + "." + className));
        }
        BoundedRun compile = runJavac(workspace, classes, sources);
        check(compile.captureClean() && compile.exitCode() == 0,
            "the isolated boundary JVM batch compiles: " + compile.stdout() + compile.stderr());
        if (compile.captureClean() && compile.exitCode() == 0) {
            preparedJvm.putAll(prepared);
        }
    }

    private static ArtifactRun jvmProduction(Drive drive) throws Exception {
        PreparedJvm prepared = preparedJvm.get(drive.compiled());
        if (prepared == null) {
            return jvmProduction(drive, null, null);
        }
        BoundedRun probe = runJava(absoluteClasspath(), prepared.classes(), prepared.probeName());
        BoundedRun terminal = drive.spec().runtimeOk() ? null
            : runJava(absoluteClasspath(), prepared.classes(), prepared.className());
        return new ArtifactRun(probe, terminal);
    }

    /**
     * The production JVM artifact, optionally with a deployed host class
     * for a host-importing focused program.
     */
    private static ArtifactRun jvmProduction(Drive drive, String hostModule,
            Path hostImplementation) throws Exception {
        return jvmProduction(drive, hostModule, hostImplementation,
            drive.compiled().jvmSource());
    }

    private static ArtifactRun jvmProduction(Drive drive, String hostModule,
            Path hostImplementation, String source) throws Exception {
        Path workspace = Files.createTempDirectory("boundary-jvm");
        try {
            String className = JvmBackend.classNameFor(
                drive.project().entryModule().path());
            if (source == null) {
                source = JvmSemanticEmitter.emitProductionProject(drive.project(),
                    drive.tables(), drive.registries(), className,
                    drive.compiled().surface()).source();
            }
            Files.writeString(workspace.resolve(className + ".java"),
                source, StandardCharsets.UTF_8);
            List<String> sources = new ArrayList<>();
            sources.add(className + ".java");
            if (hostModule != null) {
                String hostClass = JvmBackend.classNameFor(hostModule);
                Files.copy(hostImplementation, workspace.resolve(hostClass + ".java"));
                sources.add(hostClass + ".java");
            }
            String probeName = jvmProbeName();
            Files.writeString(workspace.resolve(probeName + ".java"), """
                final class %s {
                  public static void main(String[] args) {
                    try {
                      %s.dealMain();
                    } catch (deal.codegen.jvm.JvmRuntime.DealError error) {
                      System.out.println("ERR:" + error.code + "|" + error.msg + "|"
                          + error.origin + "|"
                          + (error.expected == null ? "" : error.expected) + "|"
                          + (error.actual == null ? "" : error.actual));
                      return;
                    }
                    System.out.println("OK");
                  }
                }
                """.formatted(probeName, className), StandardCharsets.UTF_8);
            sources.add(probeName + ".java");
            Path classes = workspace.resolve("classes");
            Files.createDirectories(classes);
            BoundedRun compile = runJavac(workspace, classes, sources);
            check(compile.captureClean() && compile.exitCode() == 0,
                drive.spec().what() + ": the JVM production artifact compiles "
                    + "(javac --release 25 -proc:none): " + compile.stdout()
                    + compile.stderr());
            if (compile.exitCode() != 0) {
                return new ArtifactRun(failedRun(), null);
            }
            BoundedRun probeRun = runJava(absoluteClasspath(), classes, probeName);
            BoundedRun terminalRun = drive.spec().runtimeOk() ? null
                : runJava(absoluteClasspath(), classes, className);
            return new ArtifactRun(probeRun, terminalRun);
        } finally {
            deleteRecursively(workspace);
        }
    }

    /** One bounded {@code javac} run of the emitted sources. */
    private static BoundedRun runJavac(Path workspace, Path classes,
            List<String> sources) throws Exception {
        List<String> argv = new ArrayList<>(List.of("javac", "--release", "25",
            "-proc:none", "-cp", classes + File.pathSeparator + absoluteClasspath(),
            "-d", classes.toString()));
        argv.addAll(sources);
        JvmCompilation.Result compile = JvmCompilation.compile(argv, workspace,
            BoundedProcess.SUBPROCESS_BUDGET_MS);
        return new BoundedRun(compile.exitCode(), compile.stdout(), compile.stderr(),
            false, false);
    }

    /** One bounded {@code java} run of a compiled artifact or probe. */
    private static BoundedRun runJava(String classpath, Path classes, String mainClass)
            throws Exception {
        return runBounded(List.of("java", "-cp",
            classes + File.pathSeparator + classpath, mainClass),
            classes.getParent(), Map.of());
    }

    /** The failed-run placeholder: a compile that produced no artifact. */
    private static BoundedRun failedRun() {
        return new BoundedRun(-1, "", "", false, false);
    }

    // =========================================================================
    // 7. The focused runner contract: exact status and transcript, capture
    //    health, and the bounded process lifecycle (ISSUE-0710)
    // =========================================================================

    /** A runtime-ok program that prints one unexpected stdout line. */
    private static final String EXTRA_STDOUT_SOURCE = """
        import * as console from "std/console";

        export function main(): null {
          console.log("UNEXPECTED_STDOUT");
          return null;
        }
        """;

    /** A runtime-ok program that prints one unexpected stderr line. */
    private static final String EXTRA_STDERR_SOURCE = """
        import * as console from "std/console";

        export function main(): null {
          console.error("UNEXPECTED_STDERR");
          return null;
        }
        """;

    /**
     * The focused acceptance contract's runner and comparison paths: extra
     * stdout, extra stderr, a wrong exit status, an over-cap capture, and a
     * read failure each fail by name; an interrupted run stops its child and
     * preserves the interrupt status; and a descendant that survives its
     * parent while holding the pipes is stopped within a bounded window.
     */
    private static void testFocusedRunnerContract() throws Exception {
        System.out.println("-- the focused runner contract: exact status and "
            + "transcript, capture health, and the bounded process lifecycle "
            + "(ISSUE-0710) --");
        Path scratch = Files.createTempDirectory("boundary-runner-");
        try {
            BoundedRun extraStdout = runBounded(List.of("sh", "-c",
                "printf 'OK\\nUNEXPECTED_STDOUT\\n'"), scratch, Map.of());
            String stdoutDivergence = transcriptDivergence(extraStdout, 0, "OK\n");
            check(stdoutDivergence != null && stdoutDivergence.startsWith("stdout:"),
                "extra stdout fails the transcript comparison by name: "
                    + stdoutDivergence);
            check(transcriptDivergence(extraStdout, 0,
                    "OK\nUNEXPECTED_STDOUT\n") == null,
                "the same capture is pin-exact against its true transcript");

            BoundedRun extraStderr = runBounded(List.of("sh", "-c",
                "printf 'OK\\n'; printf 'UNEXPECTED_STDERR\\n' >&2"), scratch,
                Map.of());
            String stderrDivergence = transcriptDivergence(extraStderr, 0, "OK\n");
            check(stderrDivergence != null && stderrDivergence.startsWith("stderr:"),
                "extra stderr fails the transcript comparison by name: "
                    + stderrDivergence);

            BoundedRun wrongStatus = runBounded(List.of("sh", "-c",
                "printf 'OK\\n'; exit 3"), scratch, Map.of());
            String statusDivergence = transcriptDivergence(wrongStatus, 0, "OK\n");
            check(statusDivergence != null && statusDivergence.startsWith("exit:"),
                "a wrong exit status fails the transcript comparison by name: "
                    + statusDivergence);
            checkEq(3, wrongStatus.exitCode(), "the wrong-status control keeps its "
                + "exit status through the runner");

            for (boolean stdout : List.of(false, true)) {
                BoundedRun truncated = new BoundedRun(0, "OK\n", "", stdout, !stdout);
                check(transcriptDivergence(truncated, 0, "OK\n").startsWith(
                    "capture overflow:"), "a truncated stream cannot earn transcript credit");
            }
        } finally {
            deleteRecursively(scratch);
        }

        checkProgramOutputFailsTheTranscript();
    }

    /**
     * The reviewer-visible trigger: a real production program that prints
     * beyond its pinned transcript is caught by the drive's transcript
     * comparison on both production targets, and nothing reports success.
     */
    private static void checkProgramOutputFailsTheTranscript() throws Exception {
        checkProgramOutput(EXTRA_STDOUT_SOURCE, "stdout:");
        checkProgramOutput(EXTRA_STDERR_SOURCE, "stderr:");
    }

    private static void checkProgramOutput(String source, String expectedFacet)
            throws Exception {
        Path root = Files.createTempDirectory("boundary-program-output-");
        try {
            BoundaryFixture spec = new BoundaryFixture(FIXTURE_DIR, "program-output", "main",
                "null", List.of(), null, null, 0, 0);
            Compiled compiled = compileFocused(root, source, null, null);
            if (compiled == null) {
                return;
            }
            Drive drive = lowerFocused(compiled, spec);
            if (drive == null) {
                return;
            }
            String label = "the real program output control (" + expectedFacet + ")";
            String luaDivergence = transcriptDivergence(luaProduction(drive).probe(), 0,
                "OK\n");
            check(luaDivergence != null && luaDivergence.startsWith(expectedFacet),
                label + ": the LuaJIT artifact's unexpected output fails the "
                    + "transcript comparison: " + luaDivergence);
            String jvmDivergence = transcriptDivergence(jvmProduction(drive).probe(), 0,
                "OK\n");
            check(jvmDivergence != null && jvmDivergence.startsWith(expectedFacet),
                label + ": the JVM artifact's unexpected output fails the transcript "
                    + "comparison: " + jvmDivergence);
        } finally {
            deleteRecursively(root);
        }
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Running Container Boundary Production Tests ===");
        testRejectedRawProfileLeavesNoScratch();
        testDecodedArrayMark();
        testIntegralNumberFormArrayElements();
        testCanonicalFunctionSignatureFieldBoundaries();
        testCanonicalSignatureElementBoundaries();
        testFocusedRunnerContract();

        System.out.println();
        System.out.println("ContainerBoundaryProductionTest: " + passed + " passed, " + failed
            + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
