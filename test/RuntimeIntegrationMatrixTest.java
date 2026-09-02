package deal.test;

import deal.ast.ProgramNode;
import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.NameResolver;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CheckedModuleInput;
import deal.semantic.CheckedModuleKind;
import deal.semantic.CheckedProjectBuilder;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.LoweringSupport;
import deal.semantic.ModuleFact;
import deal.semantic.RequirementManifestResult;
import deal.codegen.SemanticDifferentialHarness;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.BinarySelector;
import deal.semantic.ir.ConstructKind;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.ResolvedImport;
import deal.semantic.ir.SemanticIdAllocator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.StructuredBodyTable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The runtime integration matrix of the {@code EVALUATION_ORDER}
 * decomposition tail (ISSUE-0410): every matrix seed is lowered through
 * the real production chain (lexer → parser → checker → checked project →
 * requirement manifest → the carrier's full-program lowerer → the closed
 * validator → the address-chain protocol → the control-flow validator)
 * and then executed on the semantic oracle and BOTH shared emitters'
 * real artifacts (the shared LuaJIT script run by the real {@code luajit}
 * binary; the shared JVM class compiled with the real
 * {@code javac --release 25 -proc:none} and executed by the real
 * {@code java}) via {@link SemanticDifferentialHarness} — the identical
 * validated {@link LoweredModuleUnit} plus its
 * {@link StructuredBodyTable} through all three consumers.
 *
 * <p>The verdict is a real execution comparison report: every event
 * validates against its exact IR operation (snapshot, digest,
 * {@code parentOpId} nesting), the three traces match event-for-event,
 * ordered effects match the wiki projections, and terminals (post-state
 * result and error origin) match — a duplicated evaluation, a wrong
 * selector, or a missing boundary fails the tail even when printed
 * output coincides.</p>
 */
public class RuntimeIntegrationMatrixTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    // =========================================================================
    // Fixed invocation facts
    // =========================================================================

    private static final ModuleId MODULE = new ModuleId("main");
    private static final String SOURCE_ID = "test.deal";
    private static final String REGISTRY_HASH =
        CapabilityRegistry.releaseRegistry().capabilityRegistryHash();
    private static final String INTERFACE_HASH = new deal.semantic.ir.ProjectInterfaceIndex(
        deal.semantic.ir.ProjectInterfaceIndex.FORMAT_VERSION, Map.of(MODULE,
            new deal.semantic.ir.ExternalModuleInterface(MODULE,
                deal.semantic.ir.ExternalModuleKind.IMPLEMENTATION, List.of(), List.of(),
                List.of(), deal.semantic.ir.InitializationMode.ONCE_AFTER_DEPENDENCIES)))
        .interfaceIndexDigest();
    private static final Path WORKSPACE = Path.of("build/runtime-matrix-work");

    // =========================================================================
    // Seed preludes (each seed declares exactly the helpers it calls)
    // =========================================================================

    private static final String CONSOLE = "import * as console from \"std/console\"\n";
    private static final String FN_MARK =
        "function mark(s: string, v: int): int { console.log(s); return v; }\n";
    private static final String FN_GETARR =
        "function getArr(s: string, xs: int[]): int[] { console.log(s); return xs; }\n";
    private static final String FN_BOOMINT =
        "function boomInt(s: string, z: int | null): int { console.log(s); "
            + "let x: int = int(z); return x; }\n";
    private static final String FN_BOOMARR =
        "function boomArr(s: string, z: int | null): int[] { console.log(s); "
            + "let x: int = int(z); return [x]; }\n";

    private static final String P_MARK = CONSOLE + FN_MARK;
    private static final String P_MARK_GET = CONSOLE + FN_MARK + FN_GETARR;
    private static final String P_MARK_GET_BOOMINT =
        CONSOLE + FN_MARK + FN_GETARR + FN_BOOMINT;
    private static final String P_BOOMARR = CONSOLE + FN_BOOMARR;

    private record CheckedSlice(ProgramNode program, SymbolTable symbols, CheckResult checks) {
    }

    private static CheckedSlice checkSlice(String source, String what) {
        LexResult lex = new Lexer(source, SOURCE_ID).tokenize();
        ParseResult parse = new Parser(lex.tokens(), SOURCE_ID).parse();
        check(parse.diagnostics().isEmpty(), what + ": parses cleanly: " + parse.diagnostics());
        if (!parse.diagnostics().isEmpty()) {
            return null;
        }
        ModuleResolver resolver = new ModuleResolver() {
            @Override
            public Map<String, deal.types.Type> resolveModule(String modulePath,
                    String importingModule, java.util.Set<String> modulesInProgress)
                    throws ModuleNotFoundException {
                var exports = deal.module.StdlibModuleResolver.stdlibExports(
                    Path.of("std").toAbsolutePath().toString());
                if (!exports.containsKey(modulePath)) {
                    throw new ModuleNotFoundException("Module not found: " + modulePath);
                }
                return exports.get(modulePath);
            }

            @Override
            public deal.checker.Symbol.ClassSymbol resolveClassSymbol(String className,
                    String modulePath, String importingModule)
                    throws ModuleNotFoundException {
                return null;
            }
        };
        NameResolver nr = new NameResolver(SOURCE_ID, resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        check(nr.diagnostics().isEmpty(), what + ": resolves cleanly: " + nr.diagnostics());
        if (!nr.diagnostics().isEmpty()) {
            return null;
        }
        CheckResult result = TypeChecker.check(SOURCE_ID, symTable, nr, parse.program());
        check(result.diagnostics().isEmpty(), what + ": checks cleanly: " + result.diagnostics());
        if (!result.diagnostics().isEmpty()) {
            return null;
        }
        return new CheckedSlice(parse.program(), symTable, result);
    }

    private static List<ResolvedImport> importsOf(ProgramNode program) {
        List<ResolvedImport> imports = new ArrayList<>();
        for (deal.ast.StatementNode stmt : program.statements()) {
            if (stmt instanceof deal.ast.ImportDeclaration imp) {
                imports.add(new ResolvedImport(imp.alias(), imp.modulePath(),
                    new ModuleId(imp.modulePath()),
                    deal.semantic.ir.ExternalModuleKind.STDLIB));
            }
        }
        return imports;
    }

    private record LoweredSlice(LoweredModuleUnit unit, StructuredBodyTable table) {
    }

    /** The full production chain: checked project → manifests → carrier lowering. */
    private static LoweredSlice lowerFull(CheckedSlice slice, String what) {
        if (slice == null) {
            return null;
        }
        CheckedModuleInput input = new CheckedModuleInput(MODULE, SOURCE_ID,
            Path.of("test.deal"), slice.program(), slice.checks(),
            importsOf(slice.program()), List.of(), CheckedModuleKind.IMPLEMENTATION);
        CompilerInvocation invocation = CompilerProfileProvider.resolveCommonShadow(
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.V1_2_ACTIVE,
            CapabilityRegistry.releaseRegistry());
        ModuleFact fact = new ModuleFact(SOURCE_ID, MODULE, false, false, slice.program(),
            Map.of(), slice.symbols(), slice.checks(), List.of());
        deal.semantic.CheckedProjectBuildResult built = CheckedProjectBuilder.build(
            invocation, MODULE, List.of(fact));
        check(built != null && !built.hasErrors() && built.input() != null,
            what + ": the checked project builds cleanly");
        if (built == null || built.hasErrors() || built.input() == null) {
            return null;
        }
        RequirementManifestResult manifests = LoweringSupport.computeManifests(invocation,
            built.input(), built.index());
        check(manifests != null && manifests.diagnostics().isEmpty(),
            what + ": the manifest computation is clean");
        if (manifests == null || !manifests.diagnostics().isEmpty()) {
            return null;
        }
        Map<ConstructKind, List<SemanticOpKind>> coverage =
            manifests.manifests().get(0).constructCoverage();
        SemanticLowerer.LoweringResult lowering = SemanticLowerer.lowerModuleFullProgram(
            input, SemanticProfile.DEAL_V1_2_INT32, coverage, INTERFACE_HASH,
            REGISTRY_HASH, SemanticIdAllocator.over(List.of(MODULE)));
        check(lowering != null && !lowering.hasErrors() && lowering.unit() != null,
            what + ": the carrier lowers through the validator/chain protocol/"
                + "control-flow validator: " + (lowering == null ? "null"
                    : lowering.diagnostics()));
        if (lowering == null || lowering.hasErrors() || lowering.unit() == null) {
            return null;
        }
        return new LoweredSlice(lowering.unit(), lowering.table());
    }

    /**
     * Runs the three-consumer differential matrix for one seed and
     * asserts the pinned projection.
     */
    private static SemanticDifferentialHarness.Verdict runMatrix(String source, String what,
            List<String> expectedEffects,
            SemanticDifferentialHarness.TerminalExpectation terminal) {
        CheckedSlice slice = checkSlice(source, what);
        LoweredSlice lowered = lowerFull(slice, what);
        if (lowered == null) {
            return null;
        }
        SemanticDifferentialHarness.Verdict verdict = SemanticDifferentialHarness.run(
            lowered.unit(), lowered.table(),
            new SemanticDifferentialHarness.Expectation(expectedEffects, terminal, what),
            WORKSPACE);
        check(verdict.pass(), what + ": the three-consumer matrix verdict passes:\n"
            + verdict.report());
        if (!verdict.pass()) {
            return verdict;
        }
        for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
            check(!run.trace().isEmpty(), what + ": " + run.consumer()
                + " produced real events");
        }
        return verdict;
    }

    private static final SemanticDifferentialHarness.TerminalExpectation SUCCESS =
        new SemanticDifferentialHarness.TerminalExpectation.SuccessWith("null");
    private static final SemanticDifferentialHarness.TerminalExpectation E8001 =
        new SemanticDifferentialHarness.TerminalExpectation.FailureWith("E8001", null);
    private static final SemanticDifferentialHarness.TerminalExpectation E8002 =
        new SemanticDifferentialHarness.TerminalExpectation.FailureWith("E8002", null);

    // =========================================================================
    // 1. The chain matrix
    // =========================================================================

    static void testChainOrderMatrix() {
        System.out.println("-- Chain matrix: receiver/key/RHS order, normalize-time "
            + "length, VARIABLE boundary placement, append, publish --");

        // (a) receiver → key → RHS with console-marked functions.
        {
            String source = P_MARK_GET
                + "function main(): null {\n"
                + "  let xs: int[] = [1, 2, 3];\n"
                + "  getArr(\"arr\", xs)[mark(\"idx\", 0)] = mark(\"rhs\", 42);\n"
                + "  console.log(\"done\");\n"
                + "}\n";
            SemanticDifferentialHarness.Verdict verdict = runMatrix(source,
                "chain order seed (arr/idx/rhs)",
                List.of("arr", "idx", "rhs", "done"), SUCCESS);
            // The retained double-evaluation detector: each CALL starts
            // exactly once in every consumer's trace.
            if (verdict != null) {
                for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                    Map<String, Integer> starts = new java.util.HashMap<>();
                    for (SemanticRuntimeModel.TraceEvent event : run.trace()) {
                        if (event.kind() == SemanticOpKind.CALL
                                && event.phase() == SemanticRuntimeModel.Phase.START) {
                            starts.merge(event.op().toString(), 1, Integer::sum);
                        }
                    }
                    check(starts.values().stream().allMatch(count -> count == 1),
                        "chain order seed: every CALL starts exactly once in "
                            + run.consumer() + " (single evaluation): " + starts);
                }
            }
        }

        // (b) TABLE_SLOT member and index targets.
        {
            String source = P_MARK
                + "function main(): null {\n"
                + "  let t = {a: 1};\n"
                + "  t.a = mark(\"rhs\", 7);\n"
                + "  t[\"k\"] = mark(\"rhs2\", 8);\n"
                + "  console.log(\"done\");\n"
                + "}\n";
            runMatrix(source, "TABLE_SLOT member/index chains",
                List.of("rhs", "rhs2", "done"), SUCCESS);
        }

        // (c) The normalize-time length read.
        {
            String source = CONSOLE
                + "function grow(s: string, xs: int[]): int { console.log(s); "
                + "xs[xs.length] = 9; return 0; }\n"
                + "function rhs(s: string, v: int): int { console.log(s); return v; }\n"
                + "function main(): null {\n"
                + "  let xs: int[] = [1, 2];\n"
                + "  xs[grow(\"idx\", xs)] = rhs(\"rhs\", 42);\n"
                + "  if (xs.length === 3 && xs[0] === 42 && xs[2] === 9) "
                + "{ console.log(\"normalize-time\") } else { console.log(\"bad\") }\n"
                + "}\n";
            runMatrix(source, "normalize-time length read (length-mutating key)",
                List.of("idx", "rhs", "normalize-time"), SUCCESS);
        }

        // (d) VARIABLE boundary placement.
        {
            String source = CONSOLE
                + "function pick(s: string, v: int): int { console.log(s); return v; }\n"
                + "function main(): null {\n"
                + "  let x: int = 0;\n"
                + "  x = pick(\"val\", 42);\n"
                + "  if (x === 42) { console.log(\"stored\") } else { console.log(\"bad\") }\n"
                + "}\n";
            runMatrix(source, "VARIABLE chain boundary placement",
                List.of("val", "stored"), SUCCESS);
        }

        // (e) The append idiom.
        {
            String source = P_MARK
                + "function main(): null {\n"
                + "  let xs: int[] = [1];\n"
                + "  xs[xs.length] = mark(\"rhs\", 42);\n"
                + "  if (xs.length === 2 && xs[1] === 42) { console.log(\"append\") } "
                + "else { console.log(\"bad\") }\n"
                + "}\n";
            runMatrix(source, "append idiom xs[xs.length] = v",
                List.of("rhs", "append"), SUCCESS);
        }

        // (f) ASSIGN publishes the committed value; DELETE publishes none.
        {
            String source = P_MARK
                + "function main(): null {\n"
                + "  let xs: int[] = [1, 2, 3];\n"
                + "  let y: int = (xs[0] = mark(\"rhs\", 42));\n"
                + "  if (y === 42) { console.log(\"published\") } else { console.log(\"bad\") }\n"
                + "  delete xs[2];\n"
                + "  console.log(\"deleted\");\n"
                + "}\n";
            runMatrix(source, "ASSIGN publishes the committed value; DELETE publishes none",
                List.of("rhs", "published", "deleted"), SUCCESS);
        }
    }

    // =========================================================================
    // 2. Chain failure matrix
    // =========================================================================

    static void testChainFailureMatrix() {
        System.out.println("-- Chain failure matrix: failure at every position, "
            + "no commit, earlier effects remain --");

        {
            String source = P_BOOMARR
                + "function main(): null {\n"
                + "  let xs: int[] = [1, 2];\n"
                + "  boomArr(\"arr\", null)[0] = 42;\n"
                + "  console.log(\"after\");\n"
                + "}\n";
            runMatrix(source, "receiver failure (no commit; [arr] remains)",
                List.of("arr"), E8001);
        }
        {
            String source = P_MARK_GET_BOOMINT
                + "function main(): null {\n"
                + "  let xs: int[] = [1, 2];\n"
                + "  getArr(\"arr\", xs)[boomInt(\"idx\", null)] = mark(\"rhs\", 42);\n"
                + "  console.log(\"after\");\n"
                + "}\n";
            runMatrix(source, "key failure (RHS never evaluates; no commit)",
                List.of("arr", "idx"), E8001);
        }
        {
            String source = P_MARK_GET_BOOMINT
                + "function main(): null {\n"
                + "  let xs: int[] = [1, 2];\n"
                + "  getArr(\"arr\", xs)[mark(\"idx\", 0)] = boomInt(\"rhs\", null);\n"
                + "  console.log(\"after\");\n"
                + "}\n";
            runMatrix(source, "RHS failure (no commit; [arr, idx, rhs] remain)",
                List.of("arr", "idx", "rhs"), E8001);
        }
        {
            String source = P_MARK_GET
                + "function main(): null {\n"
                + "  let xs: int[] = [1, 2];\n"
                + "  getArr(\"arr\", xs)[mark(\"idx\", -1)] = mark(\"rhs\", 42);\n"
                + "  console.log(\"after\");\n"
                + "}\n";
            runMatrix(source, "write bounds failure (E8002 at the write-target origin)",
                List.of("arr", "idx", "rhs"), E8002);
        }
        {
            String source = P_MARK_GET
                + "function main(): null {\n"
                + "  let xs: int[] = [1, 2];\n"
                + "  let x: int = getArr(\"arr\", xs)[mark(\"idx\", -1)];\n"
                + "  console.log(\"after\");\n"
                + "}\n";
            runMatrix(source, "negative index read (E8002 negative array index)",
                List.of("arr", "idx"), E8002);
        }
        {
            String source = P_MARK_GET
                + "function main(): null {\n"
                + "  let xs: int[] = [1, 2];\n"
                + "  delete getArr(\"arr\", xs)[mark(\"idx\", -1)];\n"
                + "  console.log(\"after\");\n"
                + "}\n";
            runMatrix(source, "array delete bounds failure (E8002 delete-target origin)",
                List.of("arr", "idx"), E8002);
        }
        {
            String source = CONSOLE
                + "function main(): null {\n"
                + "  let xs: int[] = [1, 2];\n"
                + "  delete xs[2];\n"
                + "  if (xs.length === 2) { console.log(\"no-op-ok\") } "
                + "else { console.log(\"bad\") }\n"
                + "}\n";
            runMatrix(source, "array delete at index == length (permitted no-op)",
                List.of("no-op-ok"), SUCCESS);
        }
    }

    // =========================================================================
    // 3. The comparison matrix
    // =========================================================================

    static void testComparisonMatrix() {
        System.out.println("-- Comparison matrix: the closed B-D2 rows through all "
            + "three consumers --");

        {
            String source = CONSOLE
                + "function main(): null {\n"
                + "  let n: number = 0.0 / 0.0;\n"
                + "  if (n === n) { console.log(\"nan-eq\") } else { console.log(\"nan-ne\") }\n"
                + "  let z: number = -0.0;\n"
                + "  let p: number = 0.0;\n"
                + "  if (z === p) { console.log(\"nz-eq\") } else { console.log(\"bad1\") }\n"
                + "  if (z < p) { console.log(\"bad2\") } else { console.log(\"nz-nolt\") }\n"
                + "  if (z <= p) { console.log(\"nz-le\") } else { console.log(\"bad3\") }\n"
                + "  if (n < 1.0) { console.log(\"bad4\") } else { console.log(\"nan-nolt\") }\n"
                + "  if (n >= 1.0) { console.log(\"bad5\") } else { console.log(\"nan-noge\") }\n"
                + "  if (1.5 < 2.5) { console.log(\"lt\") } else { console.log(\"bad6\") }\n"
                + "  if (2.5 > 1.5 && 1.5 <= 1.5 && 1.5 >= 1.5 && 1.5 !== 2.5) "
                + "{ console.log(\"num-ok\") } else { console.log(\"bad7\") }\n"
                + "}\n";
            runMatrix(source, "NUMBER_* IEEE rows (NaN, -0.0, orderings)",
                List.of("nan-ne", "nz-eq", "nz-nolt", "nz-le", "nan-nolt", "nan-noge",
                    "lt", "num-ok"), SUCCESS);
        }

        {
            String source = CONSOLE
                + "function main(): null {\n"
                + "  let s1: string = \"\uE000\";\n"
                + "  let s2: string = \"\uD800\uDC00\";\n"
                + "  if (s1 < s2) { console.log(\"codepoint-order\") } "
                + "else { console.log(\"bad\") }\n"
                + "  if (\"a\" === \"a\" && \"a\" !== \"b\") { console.log(\"str-eq\") } "
                + "else { console.log(\"bad2\") }\n"
                + "}\n";
            runMatrix(source, "STRING_* scalar-lexicographic order (code point, "
                + "not UTF-16)", List.of("codepoint-order", "str-eq"), SUCCESS);
        }

        {
            String source = CONSOLE
                + "function main(): null {\n"
                + "  if (1 === 1 && 1 !== 2 && 1 < 2 && 2 > 1 && 1 <= 1 && 2 >= 1) "
                + "{ console.log(\"int-ok\") } else { console.log(\"bad\") }\n"
                + "  if (true === true && false !== true) { console.log(\"bool-ok\") } "
                + "else { console.log(\"bad2\") }\n"
                + "  let m: null = null;\n"
                + "  if (m === m && !(m !== m)) { console.log(\"null-ok\") } "
                + "else { console.log(\"bad3\") }\n"
                + "}\n";
            runMatrix(source, "INT32_*/BOOLEAN_*/NULL_* rows",
                List.of("int-ok", "bool-ok", "null-ok"), SUCCESS);
        }

        {
            String source = CONSOLE
                + "function main(): null {\n"
                + "  let a: int | null = null;\n"
                + "  let b: int | null = null;\n"
                + "  let c: int | null = 3;\n"
                + "  let d: int | null = 3;\n"
                + "  if (a === b) { console.log(\"null-null\") } else { console.log(\"bad\") }\n"
                + "  if (a !== c) { console.log(\"null-val-ne\") } else { console.log(\"bad2\") }\n"
                + "  if (c === d) { console.log(\"val-val\") } else { console.log(\"bad3\") }\n"
                + "  if (a === null) { console.log(\"n-null-eq\") } else { console.log(\"bad4\") }\n"
                + "  if (null === a) { console.log(\"null-n-eq\") } else { console.log(\"bad5\") }\n"
                + "  if (c !== null) { console.log(\"v-null-ne\") } else { console.log(\"bad6\") }\n"
                + "  if (null !== c) { console.log(\"null-v-ne\") } else { console.log(\"bad7\") }\n"
                + "}\n";
            runMatrix(source, "NULLABLE_* sides and NULLABLE_NULL_* directions",
                List.of("null-null", "null-val-ne", "val-val", "n-null-eq", "null-n-eq",
                    "v-null-ne", "null-v-ne"), SUCCESS);
        }

        {
            String source = CONSOLE
                + "function main(): null {\n"
                + "  let xs: int[] = [1, 2];\n"
                + "  let ys: int[] = [1, 2];\n"
                + "  let t = {a: 1};\n"
                + "  let u = {a: 1};\n"
                + "  if (xs === xs) { console.log(\"same-arr\") } else { console.log(\"bad\") }\n"
                + "  if (xs !== ys) { console.log(\"distinct-arr\") } else { console.log(\"bad2\") }\n"
                + "  if (t === t) { console.log(\"same-table\") } else { console.log(\"bad3\") }\n"
                + "  if (t !== u) { console.log(\"distinct-table\") } else { console.log(\"bad4\") }\n"
                + "}\n";
            runMatrix(source, "REFERENCE_* identity (array/table)",
                List.of("same-arr", "distinct-arr", "same-table", "distinct-table"),
                SUCCESS);
        }

        {
            String source = CONSOLE
                + "function main(): null {\n"
                + "  let xs: int[] = [1];\n"
                + "  if (xs[99] === 5) { console.log(\"bad\") } else { console.log(\"ne\") }\n"
                + "  if (xs[99] !== 5) { console.log(\"neq\") } else { console.log(\"bad2\") }\n"
                + "  if (xs[99] === xs[99]) { console.log(\"nil-eq-nil\") } "
                + "else { console.log(\"bad3\") }\n"
                + "  console.log(\"done\");\n"
                + "}\n";
            runMatrix(source, "missing≡null past-end comparison operands (no E8001)",
                List.of("ne", "neq", "nil-eq-nil", "done"), SUCCESS);
        }

        {
            String source = CONSOLE
                + "function left(): int { console.log(\"l\"); return 1; }\n"
                + "function right(): int { console.log(\"r\"); return 2; }\n"
                + "function main(): null {\n"
                + "  if (left() === right()) { console.log(\"bad\") } "
                + "else { console.log(\"ordered\") }\n"
                + "  console.log(\"done\");\n"
                + "}\n";
            runMatrix(source, "comparison operands left-to-right exactly once",
                List.of("l", "r", "ordered", "done"), SUCCESS);
        }
    }

    // =========================================================================
    // 4. The control matrix
    // =========================================================================

    static void testControlMatrix() {
        System.out.println("-- Control matrix: short circuit, loops, FOR_EACH, "
            + "TRY_CATCH/THROW, BREAK/CONTINUE, DISCARD --");

        {
            String source = CONSOLE
                + "function side(): boolean { console.log(\"side\"); return true; }\n"
                + "function yes(): boolean { console.log(\"yes\"); return true; }\n"
                + "function no(): boolean { console.log(\"no\"); return false; }\n"
                + "function main(): null {\n"
                + "  if (no() && side()) { console.log(\"bad\") } "
                + "else { console.log(\"and-skip\") }\n"
                + "  if (yes() || side()) { console.log(\"or-skip\") } "
                + "else { console.log(\"bad2\") }\n"
                + "  if (yes() && yes()) { console.log(\"and-run\") } "
                + "else { console.log(\"bad3\") }\n"
                + "  if (no() || yes()) { console.log(\"or-run\") } "
                + "else { console.log(\"bad4\") }\n"
                + "}\n";
            runMatrix(source, "short circuit (skipped blocks leave no effects)",
                List.of("no", "and-skip", "yes", "or-skip", "yes", "yes", "and-run",
                    "no", "yes", "or-run"), SUCCESS);
        }

        {
            String source = CONSOLE
                + "function count(): boolean { console.log(\"c\"); return true; }\n"
                + "function dec(x: int): int { console.log(\"d\"); return x - 1; }\n"
                + "function main(): null {\n"
                + "  let i: int = 2;\n"
                + "  while (count() && i > 0) { i = dec(i); }\n"
                + "  console.log(\"done\");\n"
                + "}\n";
            runMatrix(source, "while per-iteration condition re-evaluation",
                List.of("c", "d", "c", "d", "c", "done"), SUCCESS);
        }

        {
            String source = CONSOLE
                + "function main(): null {\n"
                + "  for (let i: int = 0; i < 5; i = i + 1) {\n"
                + "    if (i === 1) { continue }\n"
                + "    if (i === 3) { break }\n"
                + "    console.log(\"b\");\n"
                + "  }\n"
                + "  console.log(\"done\");\n"
                + "}\n";
            runMatrix(source, "FOR init/test/body/update with continue-to-update "
                + "and break", List.of("b", "b", "done"), SUCCESS);
        }

        {
            String source = CONSOLE
                + "function main(): null {\n"
                + "  let n: int = 0;\n"
                + "  for (;;) {\n"
                + "    n = n + 1;\n"
                + "    if (n === 3) { break }\n"
                + "  }\n"
                + "  if (n === 3) { console.log(\"testless-ok\") } else { console.log(\"bad\") }\n"
                + "}\n";
            CheckedSlice slice = checkSlice(source, "test-less for");
            LoweredSlice lowered = lowerFull(slice, "test-less for");
            if (lowered != null) {
                boolean shape = ofKind(lowered.unit(), SemanticOpKind.LOOP).stream()
                    .anyMatch(op -> {
                        KindPayload.LoopPayload payload =
                            (KindPayload.LoopPayload) op.payload();
                        SemanticOp producer = producerOf(lowered.unit(),
                            payload.condition());
                        return producer != null && producer.kind() == SemanticOpKind.CONST
                            && producer.origin().kind()
                                == deal.semantic.ir.SourceOriginKind.SYNTHETIC
                            && lowered.table().blockOps()
                                .getOrDefault(payload.initBlock(), List.of())
                                .contains(producer.opId());
                    });
                check(shape, "test-less for: the CONST true condition production "
                    + "lives once in initBlock");
            }
            runMatrix(source, "test-less for (;;) shape",
                List.of("testless-ok"), SUCCESS);
        }

        {
            String source = CONSOLE
                + "function mk(s: string): int[] { console.log(s); return [10, 20]; }\n"
                + "function main(): null {\n"
                + "  for (let e: int of mk(\"iter\")) {\n"
                + "    if (e === 10) { console.log(\"first\") } else { console.log(\"other\") }\n"
                + "  }\n"
                + "  let xs: int[] = [1, 2];\n"
                + "  for (let e: int of xs) {\n"
                + "    if (e === 1) { xs[xs.length] = 99; }\n"
                + "    console.log(\"v\");\n"
                + "  }\n"
                + "  console.log(\"done\");\n"
                + "}\n";
            runMatrix(source, "FOR_EACH iterable-once, 0-based first visit, "
                + "snapshot range", List.of("iter", "first", "other", "v", "v", "done"),
                SUCCESS);
        }

        {
            String source = CONSOLE
                + "function main(): null {\n"
                + "  let xs: int[] = [1, 2];\n"
                + "  for (let e: int of xs) {\n"
                + "    if (e === 1) { delete xs[1]; }\n"
                + "    console.log(\"v\");\n"
                + "  }\n"
                + "  console.log(\"after\");\n"
                + "}\n";
            runMatrix(source, "FOR_EACH shrink (missing element E8001 at the "
                + "FOR_EACH origin)", List.of("v"), E8001);
        }

        {
            String source = CONSOLE
                + "function boom(z: int | null): int { return int(z); }\n"
                + "function main(): null {\n"
                + "  try { let x: int = boom(null); console.log(\"bad\"); } "
                + "catch (e) { console.log(\"caught\"); }\n"
                + "  try { console.log(\"ok\"); } catch (e) { console.log(\"bad2\"); }\n"
                + "  console.log(\"after\");\n"
                + "}\n";
            runMatrix(source, "TRY_CATCH catch-on-failure / skip-on-success",
                List.of("caught", "ok", "after"), SUCCESS);
        }
        {
            String source = CONSOLE
                + "function rethrow(): null { try { let z: int | null = null; "
                + "let x: int = int(z); } catch (e) { throw e; } }\n"
                + "function main(): null { rethrow(); console.log(\"after\"); }\n";
            SemanticDifferentialHarness.Verdict verdict = runMatrix(source,
                "catch re-throw (own THROW origin with cause = the original)",
                List.of(), E8001);
            if (verdict != null) {
                for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                    check(run.terminal() instanceof
                        SemanticRuntimeModel.Terminal.DealFailure failure
                        && failure.error().cause() != null
                        && "E8001".equals(failure.error().cause().code()),
                        "catch re-throw: " + run.consumer()
                            + " carries cause = the original E8001 snapshot");
                }
            }
        }

        {
            String source = CONSOLE
                + "function main(): null {\n"
                + "  while (true) {\n"
                + "    try { console.log(\"t\"); break; } catch (e) { console.log(\"bad\"); }\n"
                + "  }\n"
                + "  console.log(\"after\");\n"
                + "}\n";
            runMatrix(source, "break across a try boundary",
                List.of("t", "after"), SUCCESS);
        }

        {
            String source = P_MARK
                + "function main(): null {\n"
                + "  mark(\"d\", 1);\n"
                + "  console.log(\"after\");\n"
                + "}\n";
            CheckedSlice slice = checkSlice(source, "discard");
            LoweredSlice lowered = lowerFull(slice, "discard");
            if (lowered != null) {
                check(ofKind(lowered.unit(), SemanticOpKind.DISCARD).stream()
                    .anyMatch(op -> op.origin().kind()
                        == deal.semantic.ir.SourceOriginKind.SYNTHETIC),
                    "discard: the expression statement lowers to an audited DISCARD");
            }
            runMatrix(source, "DISCARD audit", List.of("d", "after"), SUCCESS);
        }
    }

    // =========================================================================
    // 5. Pinned IR facts
    // =========================================================================

    static void testPinnedIrFacts() {
        System.out.println("-- Pinned IR facts: wrong selector / missing boundary / "
            + "moved child negatives --");

        {
            String source = CONSOLE
                + "function main(): null {\n"
                + "  let n: number = 0.0 / 0.0;\n"
                + "  if (n === n) { console.log(\"bad\") } else { console.log(\"ne\") }\n"
                + "}\n";
            CheckedSlice slice = checkSlice(source, "selector pinning");
            LoweredSlice lowered = lowerFull(slice, "selector pinning");
            if (lowered != null) {
                boolean pinned = ofKind(lowered.unit(), SemanticOpKind.BINARY).stream()
                    .anyMatch(op -> ((KindPayload.BinaryPayload) op.payload()).selector()
                        == BinarySelector.NUMBER_EQ);
                check(pinned, "the NaN equality lowers to the pinned NUMBER_EQ "
                    + "selector");
            }
        }

        {
            String source = P_MARK_GET
                + "function main(): null {\n"
                + "  let xs: int[] = [1, 2];\n"
                + "  getArr(\"arr\", xs)[mark(\"idx\", 0)] = mark(\"rhs\", 42);\n"
                + "}\n";
            CheckedSlice slice = checkSlice(source, "chain shape pinning");
            LoweredSlice lowered = lowerFull(slice, "chain shape pinning");
            if (lowered != null) {
                boolean shape = ofKind(lowered.unit(), SemanticOpKind.ASSIGN).stream()
                    .anyMatch(op -> {
                        KindPayload.AssignPayload payload =
                            (KindPayload.AssignPayload) op.payload();
                        if (payload.childOps().size() != 7) {
                            return false;
                        }
                        SemanticOp boundary = opById(lowered.unit(),
                            payload.childOps().get(5));
                        SemanticOp commit = opById(lowered.unit(),
                            payload.childOps().get(6));
                        return boundary != null
                            && boundary.kind() == SemanticOpKind.BOUNDARY
                            && ((KindPayload.BoundaryPayload) boundary.payload()).kind()
                                == deal.semantic.ir.BoundaryKind.ARRAY_ELEMENT_ASSIGNMENT
                            && commit != null && commit.kind() == SemanticOpKind.INDEX_WRITE;
                    });
                check(shape, "the ARRAY_SLOT chain carries the pinned seven-child "
                    + "shape with the boundary before the commit");
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static List<SemanticOp> ofKind(LoweredModuleUnit unit, SemanticOpKind kind) {
        List<SemanticOp> found = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == kind) {
                found.add(op);
            }
        }
        return found;
    }

    private static SemanticOp opById(LoweredModuleUnit unit, OpId id) {
        for (SemanticOp op : unit.ops()) {
            if (op.opId().equals(id)) {
                return op;
            }
        }
        return null;
    }

    private static SemanticOp producerOf(LoweredModuleUnit unit,
                                         deal.semantic.ir.ValueId value) {
        for (SemanticOp op : unit.ops()) {
            if (value.equals(op.result())) {
                return op;
            }
        }
        return null;
    }

    // =========================================================================
    // Entry
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Runtime Integration Matrix Test (ISSUE-0410 "
            + "decomposition tail: semantic oracle + shared LuaJIT + shared JVM) ===\n");

        testChainOrderMatrix();
        testChainFailureMatrix();
        testComparisonMatrix();
        testControlMatrix();
        testPinnedIrFacts();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
