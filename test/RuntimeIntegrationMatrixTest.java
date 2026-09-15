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
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.AnchorId;
import deal.semantic.ir.BinarySelector;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.ClosedSelector;
import deal.semantic.ir.ConstructKind;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.ExportPlan;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.LoweringContextHash;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ModuleInitPlan;
import deal.semantic.ir.OpId;
import deal.semantic.ir.OpResultType;
import deal.semantic.ir.OperationContractSnapshot;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.ResolvedImport;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.ScalarValue;
import deal.semantic.ir.SemanticIdAllocator;
import deal.semantic.ir.SemanticIrValidator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.SemanticValue;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;

import java.nio.file.Files;
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
                // Production-faithful resolved ids (the checked-project
                // builder records the resolved target's dotted module id,
                // e.g. std/console → std.console, never the raw import
                // specifier): the closed StdlibFunctionCatalog keys on
                // the resolved dotted form.
                String resolved = switch (imp.modulePath()) {
                    case "std/console" -> "std.console";
                    case "std/string" -> "std.string";
                    case "std/table" -> "std.table";
                    case "std/json" -> "std.json";
                    case "std/math" -> "std.math";
                    case "std/time" -> "std.time";
                    default -> imp.modulePath();
                };
                imports.add(new ResolvedImport(imp.alias(), imp.modulePath(),
                    new ModuleId(resolved),
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

        // (a2)/(a3) Hoisted-operand parity seeds reproducing the pinned
        // jvm-arr-eval-order-hoisted (index-side, index, value-side, value)
        // and jvm-arr-eval-order-write-hoisted-parity (a, b, i, c, v,
        // write-ok) outputs: an operand's nested side-effecting argument
        // completes before the operand call's own effect, with hoisted
        // operands — on the oracle AND both shared emitters.
        {
            String source = CONSOLE
                + "function pick(s: string, z: null): int { console.log(s); return 0; }\n"
                + "function main(): null {\n"
                + "  let xs: int[] = [5, 6];\n"
                + "  xs[pick(\"index\", console.log(\"index-side\"))] = "
                + "pick(\"value\", console.log(\"value-side\"));\n"
                + "  if (xs[0] === 0) { console.log(\"written\") } "
                + "else { console.log(\"bad\") }\n"
                + "}\n";
            runMatrix(source, "hoisted-operand parity "
                + "jvm-arr-eval-order-hoisted (index-side, index, value-side, value)",
                List.of("index-side", "index", "value-side", "value", "written"),
                SUCCESS);
        }
        {
            String source = CONSOLE
                + "function pick(s: string, z: null): int { console.log(s); return 0; }\n"
                + "function getArr(s: string, xs: int[]): int[] "
                + "{ console.log(s); return xs; }\n"
                + "function main(): null {\n"
                + "  let xs: int[] = [5, 6];\n"
                + "  getArr(\"a\", xs)[pick(\"i\", console.log(\"b\"))] = "
                + "pick(\"v\", console.log(\"c\"));\n"
                + "  if (xs[0] === 0) { console.log(\"write-ok\"); }\n"
                + "}\n";
            runMatrix(source, "hoisted-operand parity "
                + "jvm-arr-eval-order-write-hoisted-parity (a, b, i, c, v, write-ok)",
                List.of("a", "b", "i", "c", "v", "write-ok"), SUCCESS);
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
                + "  let s1: string = \"\uFFFF\";\n"
                + "  let s2: string = \"\uD800\uDC00\";\n"
                + "  if (s1 < s2) { console.log(\"codepoint-order\") } "
                + "else { console.log(\"bad\") }\n"
                + "  if (\"a\" === \"a\" && \"a\" !== \"b\") { console.log(\"str-eq\") } "
                + "else { console.log(\"bad2\") }\n"
                + "}\n";
            // U+FFFF vs U+10000 discriminates: code point order says
            // FFFF < 10000 (true -> codepoint-order), while the UTF-16
            // code-unit order used by String.compareTo says FFFF > D800
            // (false) — a shared-JVM realization using String.compareTo
            // fails this seed.
            runMatrix(source, "STRING_* scalar-lexicographic order (code point, "
                + "not UTF-16; the supplementary pair discriminates "
                + "String.compareTo)", List.of("codepoint-order", "str-eq"), SUCCESS);
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
                + "  let xs: (int|null)[] = [];\n"
                + "  xs[0] = null;\n"
                + "  xs[1] = 1;\n"
                + "  for (let e: int|null of xs) {\n"
                + "    if (e === null) { console.log(\"nil\") } "
                + "else { console.log(\"int\") }\n"
                + "  }\n"
                + "  console.log(\"done\");\n"
                + "}\n";
            // A present null element passes the nullable element descriptor
            // (effects nil, int, done); conflating it with missing raises
            // E8001 "expected nullable(int), got missing" — the parity
            // break the emitters must never reproduce.
            runMatrix(source, "FOR_EACH(ARRAY_VALUES) preserves a present null "
                + "element (null never conflated with missing)",
                List.of("nil", "int", "done"), SUCCESS);
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
    // 5. The CONTAINERS_AND_STRINGS extras matrix (the step-1 cutover):
    //    OPTIONAL_READ pre-map seeds and HAS_FIELD presence units
    // =========================================================================

    static void testContainerExtrasMatrix() {
        System.out.println("-- Container extras matrix: OPTIONAL_READ present/missing/"
            + "present-null/wrong-kind, HAS_FIELD presence --");

        // (a) Present: the missing-capable read of a present key lowers
        // to MEMBER_READ(INTERNAL_MISSING) + OPTIONAL_READ + the
        // CONTEXTUAL_TABLE_READ boundary child, and the present value
        // passes the optional-read validation unchanged.
        {
            String source = CONSOLE
                + "function main(): null {\n"
                + "  let t: table = { a: 1 };\n"
                + "  let x: int | null = t.a;\n"
                + "  if (x === null) { console.log(\"bad\") } "
                + "else { console.log(\"present\") }\n"
                + "}\n";
            runMatrix(source, "OPTIONAL_READ present key (value passes)",
                List.of("present"), SUCCESS);
        }

        // (b) Missing: an absent key pre-maps to language null before
        // the boundary validates — never the E8001 missing projection.
        {
            String source = CONSOLE
                + "function main(): null {\n"
                + "  let t: table = { a: 1 };\n"
                + "  let y: int | null = t.b;\n"
                + "  if (y === null) { console.log(\"nil\") } "
                + "else { console.log(\"bad\") }\n"
                + "}\n";
            runMatrix(source, "OPTIONAL_READ missing key (missing → null before "
                + "validation)", List.of("nil"), SUCCESS);
        }

        // (c) Present null stays present null: a stored null passes the
        // nullable descriptor and never conflates with the internal
        // missing against the inner descriptor.
        {
            String source = CONSOLE
                + "function main(): null {\n"
                + "  let t: table = { n: null };\n"
                + "  let w: int | null = t.n;\n"
                + "  if (w === null) { console.log(\"nil\") } "
                + "else { console.log(\"bad\") }\n"
                + "}\n";
            runMatrix(source, "OPTIONAL_READ present null (null never conflated "
                + "with missing)", List.of("nil"), SUCCESS);
        }

        // (d) A present wrong-kind value fails the optional-read present
        // branch: the CONTEXTUAL_TABLE_READ child projects the pinned
        // E8001 at the boundary origin (the differential verdict pins
        // the event-for-event failure shape on all three consumers).
        {
            String source = CONSOLE
                + "function main(): null {\n"
                + "  let t: table = { a: \"s\" };\n"
                + "  let x: int | null = t.a;\n"
                + "  console.log(\"after\");\n"
                + "}\n";
            runMatrix(source, "OPTIONAL_READ present wrong kind (E8001 at the "
                + "boundary origin)", List.of(), E8001);
        }
    }

    static void testHasFieldPresenceMatrix() {
        System.out.println("-- HAS_FIELD presence matrix: present int / present null / "
            + "absent keys through the three consumers --");

        ValueId one = nextValue();
        ValueId nul = nextValue();
        ValueId table = nextValue();
        ValueId hasA = nextValue();
        ValueId hasN = nextValue();
        ValueId hasM = nextValue();
        List<SemanticOp> ops = List.of(
            syntheticOp(SemanticOpKind.CONST,
                new KindPayload.ConstPayload(new ScalarValue.Int(1)),
                one, RuntimeDescriptor.Int.INSTANCE),
            syntheticOp(SemanticOpKind.CONST,
                new KindPayload.ConstPayload(ScalarValue.Null.INSTANCE),
                nul, RuntimeDescriptor.Null.INSTANCE),
            syntheticOp(SemanticOpKind.TABLE_NEW,
                new KindPayload.TableNewPayload(List.of(
                    new KindPayload.TableEntry("a", one),
                    new KindPayload.TableEntry("n", nul))),
                table, RuntimeDescriptor.Table.INSTANCE),
            syntheticOp(SemanticOpKind.HAS_FIELD,
                new KindPayload.HasFieldPayload(table, "a"),
                hasA, RuntimeDescriptor.Boolean.INSTANCE),
            syntheticOp(SemanticOpKind.HAS_FIELD,
                new KindPayload.HasFieldPayload(table, "n"),
                hasN, RuntimeDescriptor.Boolean.INSTANCE),
            syntheticOp(SemanticOpKind.HAS_FIELD,
                new KindPayload.HasFieldPayload(table, "m"),
                hasM, RuntimeDescriptor.Boolean.INSTANCE));
        LoweredModuleUnit unit = syntheticUnit(ops);
        StructuredBodyTable bodyTable = syntheticTable(ops);
        var validation = SemanticIrValidator.validate(unit,
            new SemanticIrValidator.ComparisonFacts(INTERFACE_HASH,
                SemanticProfile.DEAL_V1_2_INT32, REGISTRY_HASH));
        check(validation.isEmpty(),
            "the HAS_FIELD presence unit is valid IR: " + validation);
        SemanticDifferentialHarness.Verdict verdict = SemanticDifferentialHarness.run(
            unit, bodyTable, new SemanticDifferentialHarness.Expectation(List.of(),
                SUCCESS, "HAS_FIELD presence (present int, present null, absent)"),
            WORKSPACE);
        check(verdict.pass(),
            "the HAS_FIELD presence unit passes the three-consumer matrix:\n"
                + verdict.report());

        // The present=false OPTIONAL_READ payload shape (a statically
        // absent slot): language null, no boundary child — the second
        // closed payload arm.
        ValueId absent = nextValue();
        LoweredModuleUnit absentUnit = syntheticUnit(List.of(
            syntheticOp(SemanticOpKind.OPTIONAL_READ,
                new KindPayload.OptionalReadPayload(null, false,
                    RuntimeDescriptor.Int.INSTANCE),
                absent, nullableInt())));
        StructuredBodyTable absentTable = syntheticTable(absentUnit.ops());
        var absentValidation = SemanticIrValidator.validate(absentUnit,
            new SemanticIrValidator.ComparisonFacts(INTERFACE_HASH,
                SemanticProfile.DEAL_V1_2_INT32, REGISTRY_HASH));
        check(absentValidation.isEmpty(),
            "the present=false OPTIONAL_READ unit is valid IR: " + absentValidation);
        SemanticDifferentialHarness.Verdict absentVerdict = SemanticDifferentialHarness.run(
            absentUnit, absentTable, new SemanticDifferentialHarness.Expectation(List.of(),
                SUCCESS, "OPTIONAL_READ present=false (statically absent slot → null)"),
            WORKSPACE);
        check(absentVerdict.pass(),
            "the present=false OPTIONAL_READ unit passes the three-consumer "
                + "matrix:\n" + absentVerdict.report());
    }

    // =========================================================================
    // 5b. The BINDINGS family matrix (the step-5 emission):
    //     RECURSIVE_GROUP_INIT through the group-core lowering path
    //     (RecursiveGroupLoweringTest's real reachable IR) on all three
    //     consumers — atomic publication, fresh identities, one factory
    //     invocation per member per execution, and the nested-scope
    //     emission shape
    // =========================================================================

    /**
     * The group-core lowering path (ISSUE-0446's production surface):
     * the identical validated unit the
     * {@code RecursiveGroupLoweringTest} path produces — the
     * {@code RECURSIVE_GROUP_INIT} op appears in the validated unit,
     * never invented by the seeds (anti-hollow: the harness compares
     * the three consumers' traces over this real IR, not source
     * presence).
     */
    private static SemanticLowerer.GroupCoreResult lowerGroupCore(CheckedSlice slice,
                                                                  String what) {
        if (slice == null) {
            return null;
        }
        CheckedModuleInput input = new CheckedModuleInput(MODULE, SOURCE_ID,
            Path.of("test.deal"), slice.program(), slice.checks(),
            importsOf(slice.program()), List.of(), CheckedModuleKind.IMPLEMENTATION);
        SemanticLowerer.GroupCoreResult result = SemanticLowerer.lowerModuleGroupCore(
            input, SemanticProfile.DEAL_V1_2_INT32, Map.of(), INTERFACE_HASH,
            REGISTRY_HASH, SemanticIdAllocator.over(List.of(MODULE)));
        check(result != null && result.lowering() != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null,
            what + ": the group-core path lowers a validated unit: "
                + (result == null || result.lowering() == null ? "null"
                    : result.lowering().diagnostics()));
        if (result == null || result.lowering() == null
                || result.lowering().hasErrors() || result.lowering().unit() == null) {
            return null;
        }
        return result;
    }

    /** Runs the group-core seed through the three-consumer matrix. */
    private static SemanticDifferentialHarness.Verdict runGroupMatrix(
            String source, String what) {
        CheckedSlice slice = checkSlice(source, what);
        SemanticLowerer.GroupCoreResult result = lowerGroupCore(slice, what);
        if (result == null) {
            return null;
        }
        SemanticDifferentialHarness.Verdict verdict = SemanticDifferentialHarness.run(
            result.lowering().unit(), result.lowering().table(),
            new SemanticDifferentialHarness.Expectation(List.of(), SUCCESS, what),
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

    /**
     * The atomic-publication pin: for every group op, each consumer's
     * SUCCESS event directly follows the op's START (the publication
     * sequence emits zero interleaved events), and every executed
     * member-load event follows the group op's SUCCESS — a partial
     * publisher interleaving a member observation mid-publication (the
     * partial-publication defect) breaks this adjacency, and the
     * three-way trace comparison then names the first mismatch class.
     */
    private static void pinAtomicPublication(LoweredModuleUnit unit,
            SemanticDifferentialHarness.Verdict verdict, String what) {
        List<SemanticOp> groups = ofKind(unit, SemanticOpKind.RECURSIVE_GROUP_INIT);
        check(!groups.isEmpty(), what + ": the unit carries at least one "
            + "RECURSIVE_GROUP_INIT op (real reachable IR)");
        for (SemanticOp group : groups) {
            for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                List<SemanticRuntimeModel.TraceEvent> events = run.trace();
                int start = -1;
                int success = -1;
                for (int i = 0; i < events.size(); i++) {
                    SemanticRuntimeModel.TraceEvent event = events.get(i);
                    if (!event.op().equals(group.opId())) {
                        continue;
                    }
                    if (event.phase() == SemanticRuntimeModel.Phase.START) {
                        start = i;
                    } else if (event.phase() == SemanticRuntimeModel.Phase.SUCCESS) {
                        success = i;
                    }
                }
                check(start >= 0, what + ": " + run.consumer() + " starts the group "
                    + group.opId());
                check(success == start + 1, what + ": " + run.consumer()
                    + " publishes the group " + group.opId() + " atomically "
                    + "(SUCCESS directly follows START at index " + start
                    + "/" + success + " — no interleaved member observation)");
                for (int i = 0; i < events.size(); i++) {
                    SemanticRuntimeModel.TraceEvent event = events.get(i);
                    if (event.kind() == SemanticOpKind.BINDING_LOAD
                            && isMemberLoadOf(unit, group, event.op())
                            && i < success) {
                        fail(what + ": " + run.consumer() + " executes member load "
                            + event.op() + " before the group " + group.opId()
                            + " completes (partial publication)");
                    }
                }
            }
        }
    }

    /** True iff the event's op is a generation-0 load of one group member binding. */
    private static boolean isMemberLoadOf(LoweredModuleUnit unit, SemanticOp group,
                                          OpId eventOp) {
        SemanticOp op = opById(unit, eventOp);
        if (op == null || op.kind() != SemanticOpKind.BINDING_LOAD) {
            return false;
        }
        KindPayload.RecursiveGroupInitPayload payload =
            (KindPayload.RecursiveGroupInitPayload) group.payload();
        KindPayload.BindingLoadPayload load = (KindPayload.BindingLoadPayload) op.payload();
        return load.generation() == 0 && payload.bindings().contains(load.binding());
    }

    static void testRecursiveGroupMatrix() {
        System.out.println("-- Recursive group matrix: atomic publication, fresh "
            + "identities, one factory invocation per member per execution, "
            + "nested-scope emission --");

        // (a) The canonical mutual pair: sibling loads inside the member
        // bodies, module-level loads after the group op — the
        // publication-completeness seed (a member reading a group
        // binding must observe the published sibling, never a partial
        // group; the atomic-publication pin asserts the group's events
        // carry zero interleaved observations).
        {
            String source = """
                function f(): null {
                  let gRef: () => null = g;
                  let selfRef: () => null = f;
                }
                function g(): null {
                  let fRef: () => null = f;
                }
                let fTop: () => null = f;
                let gTop: () => null = g;
                """;
            CheckedSlice slice = checkSlice(source, "group (a) mutual pair");
            SemanticLowerer.GroupCoreResult result =
                lowerGroupCore(slice, "group (a) mutual pair");
            if (result == null) {
                return;
            }
            check(ofKind(result.lowering().unit(),
                    SemanticOpKind.RECURSIVE_GROUP_INIT).size() == 1,
                "group (a): exactly one RECURSIVE_GROUP_INIT op in the validated "
                    + "unit");
            SemanticDifferentialHarness.Verdict verdict = SemanticDifferentialHarness.run(
                result.lowering().unit(), result.lowering().table(),
                new SemanticDifferentialHarness.Expectation(List.of(), SUCCESS,
                    "group (a) mutual pair — atomic publication and member "
                        + "loads after publication"),
                WORKSPACE);
            check(verdict.pass(),
                "group (a): the three-consumer matrix verdict passes:\n"
                    + verdict.report());
            if (verdict.pass()) {
                pinAtomicPublication(result.lowering().unit(), verdict,
                    "group (a)");
            }
        }

        // (b) The fresh-identity and single-factory pins: two groups plus
        // two loads of the same member — the two loads publish the
        // identical ref atom (exactly one factory invocation per member
        // per execution; a re-evaluating factory would publish a second
        // distinct identity and the three-way trace comparison would
        // name the first mismatch class), while a different group's
        // member publishes a distinct ref (fresh identities per group).
        {
            String source = """
                function f(): null {
                  let gRef: () => null = g;
                }
                function g(): null {
                  let fRef: () => null = f;
                }
                function h(): null {
                  let iRef: () => null = i;
                }
                function i(): null {
                  let hRef: () => null = h;
                }
                let a: () => null = f;
                let b: () => null = f;
                let c: () => null = h;
                """;
            CheckedSlice slice = checkSlice(source,
                "group (b) fresh identities / single factory");
            SemanticLowerer.GroupCoreResult result =
                lowerGroupCore(slice, "group (b) fresh identities / single factory");
            if (result == null) {
                return;
            }
            check(ofKind(result.lowering().unit(),
                    SemanticOpKind.RECURSIVE_GROUP_INIT).size() == 2,
                "group (b): exactly two RECURSIVE_GROUP_INIT ops in the validated "
                    + "unit");
            SemanticDifferentialHarness.Verdict verdict = SemanticDifferentialHarness.run(
                result.lowering().unit(), result.lowering().table(),
                new SemanticDifferentialHarness.Expectation(List.of(), SUCCESS,
                    "group (b) fresh identities / single factory per member"),
                WORKSPACE);
            check(verdict.pass(),
                "group (b): the three-consumer matrix verdict passes:\n"
                    + verdict.report());
            if (verdict.pass()) {
                pinAtomicPublication(result.lowering().unit(), verdict,
                    "group (b)");
                for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                    List<String> functionLoadAtoms = new ArrayList<>();
                    for (SemanticRuntimeModel.TraceEvent event : run.trace()) {
                        if (event.kind() == SemanticOpKind.BINDING_LOAD
                                && event.phase() == SemanticRuntimeModel.Phase.SUCCESS
                                && event.output() != null
                                && event.output().startsWith("ref:")) {
                            functionLoadAtoms.add(event.output());
                        }
                    }
                    check(functionLoadAtoms.size() == 3
                            && functionLoadAtoms.get(0).equals(functionLoadAtoms.get(1))
                            && !functionLoadAtoms.get(1).equals(functionLoadAtoms.get(2)),
                        "group (b): " + run.consumer() + " publishes one identity "
                            + "per member per execution (the two f-loads agree at "
                            + functionLoadAtoms.get(0) + "; the h-load is a fresh "
                            + "distinct identity) — a re-evaluated factory breaks "
                            + "the load-atom equality: " + functionLoadAtoms);
                }
            }
        }

        // (c) A non-member capture: the member factory receives the
        // capturing cell among its arguments (the enclosing body's
        // parameter cell and the sibling SHARED_CELLs) — the emission
        // shape compiles and runs on both real toolchains. (A
        // module-level let read inside a member body is a module-member
        // construct of the E9/E10 window, outside the group-core walk;
        // the parameter capture pins the non-member capture-cell arm
        // instead.)
        {
            String source = """
                function outer(x: int): null {
                  function f(): null {
                    let xRef: int = x;
                    let gRef: () => null = g;
                  }
                  function g(): null {
                    let fRef: () => null = f;
                  }
                  let gTop: () => null = g;
                }
                """;
            runGroupMatrix(source, "group (c) non-member capture cells "
                + "(enclosing parameter cell)");
        }

        // (d) A closure group side by side: a self-recursive closure
        // (size-1 SCC → CLOSURE_NEW) next to the mutual group — both
        // producing allocations execute in one unit.
        {
            String source = """
                function f(): null {
                  let gRef: () => null = g;
                  let selfRef: () => null = f;
                }
                function g(): null {
                  let fRef: () => null = f;
                }
                function self(): null {
                  let me: () => null = self;
                }
                let fTop: () => null = f;
                let selfTop: () => null = self;
                """;
            runGroupMatrix(source, "group (d) closure + group producing "
                + "allocations in one unit");
        }

        // (e) The nested-scope group: the op sits at the first member's
        // declaration position inside the enclosing function's body —
        // the emitters realize the arm inside the factory body, and the
        // artifacts still compile and run under the real toolchains
        // (the group never executes because the enclosing body never
        // runs in the group-core window — an emission-shape seed).
        {
            String source = """
                function outer(): null {
                  let before: int = 1;
                  function f(): null {
                    let gRef: () => null = g;
                  }
                  function g(): null {
                    let fRef: () => null = f;
                  }
                  let after: int = 2;
                }
                """;
            CheckedSlice slice = checkSlice(source, "group (e) nested-scope group");
            SemanticLowerer.GroupCoreResult result =
                lowerGroupCore(slice, "group (e) nested-scope group");
            if (result == null) {
                return;
            }
            List<SemanticOp> groups = ofKind(result.lowering().unit(),
                SemanticOpKind.RECURSIVE_GROUP_INIT);
            check(groups.size() == 1, "group (e): exactly one RECURSIVE_GROUP_INIT "
                + "op; got " + groups.size());
            if (groups.size() == 1) {
                SemanticOp group = groups.get(0);
                boolean inEnclosingBody = false;
                for (SemanticOp op : result.lowering().unit().ops()) {
                    if (op.kind() == SemanticOpKind.CLOSURE_NEW
                            && ((KindPayload.ClosureNewPayload) op.payload())
                                .binding().blockId()
                                .equals(result.lowering().table().opBlocks()
                                    .get(group.opId()))) {
                        inEnclosingBody = true;
                    }
                }
                check(inEnclosingBody, "group (e): the group op is a member of the "
                    + "enclosing function's body block (nested-scope placement)");
            }
            SemanticDifferentialHarness.Verdict verdict = SemanticDifferentialHarness.run(
                result.lowering().unit(), result.lowering().table(),
                new SemanticDifferentialHarness.Expectation(List.of(), SUCCESS,
                    "group (e) nested-scope group emission inside the factory body"),
                WORKSPACE);
            check(verdict.pass(),
                "group (e): the three-consumer matrix verdict passes (the "
                    + "nested-group arm compiles and runs in both real artifacts):\n"
                    + verdict.report());
        }
    }

    /**
     * The emitOp totality pin for the family: exactly one
     * {@code RECURSIVE_GROUP_INIT} realization arm per emitter switch
     * and the retained fail-closed default throw in both switches (the
     * E6005-converted backstop — a removed default arm is itself a gate
     * failure). The behavioral half is the group matrix above: a
     * validated unit carrying the op emits on both targets.
     */
    static void testRecursiveGroupArmPins() {
        System.out.println("-- RECURSIVE_GROUP_INIT emitOp pins: one arm per emitter, "
            + "the default throw retained in both switches --");
        for (String path : List.of("deal/codegen/lua/LuaSemanticEmitter.java",
                "deal/codegen/jvm/JvmSemanticEmitter.java")) {
            String text;
            try {
                text = Files.readString(Path.of(path));
            } catch (java.io.IOException exception) {
                fail(path + " cannot be read for the emitOp arm pin: "
                    + exception.getMessage());
                continue;
            }
            int arms = 0;
            int index = 0;
            while ((index = text.indexOf("case RECURSIVE_GROUP_INIT ->", index)) >= 0) {
                arms++;
                index++;
            }
            check(arms == 1, path + " carries exactly one RECURSIVE_GROUP_INIT "
                + "realization arm in its emitOp switch; got " + arms);
            check(text.contains("default -> throw new IllegalStateException"),
                path + " retains the fail-closed default throw (the E6005-converted "
                    + "backstop)");
        }

        // Production-mode realization (no trace-only arm): the same
        // group unit emitted through the production surfaces runs under
        // the real toolchains with the retained production terminal
        // (silent success, exit 0 — the publication code is emitted
        // identically in both modes).
        {
            String source = """
                function f(): null {
                  let gRef: () => null = g;
                  let selfRef: () => null = f;
                }
                function g(): null {
                  let fRef: () => null = f;
                }
                let fTop: () => null = f;
                let gTop: () => null = g;
                """;
            CheckedSlice slice = checkSlice(source,
                "production-mode group emission");
            SemanticLowerer.GroupCoreResult result =
                lowerGroupCore(slice, "production-mode group emission");
            if (result == null) {
                return;
            }
            try {
                String lua = deal.codegen.lua.LuaSemanticEmitter
                    .emitProductionModule(result.lowering().unit(),
                        result.lowering().table(), true);
                Path script = WORKSPACE.resolve("group-prod.lua");
                Files.writeString(script, lua);
                Process luaRun = new ProcessBuilder("luajit",
                    script.toAbsolutePath().toString())
                    .redirectErrorStream(true).start();
                String luaOutput = new String(luaRun.getInputStream().readAllBytes());
                int luaExit = luaRun.waitFor();
                check(luaExit == 0 && luaOutput.isEmpty(),
                    "the production shared-LuaJIT group artifact runs with the "
                        + "retained silent production terminal (exit " + luaExit
                        + ", output " + luaOutput.trim() + ")");

                deal.codegen.jvm.JvmSemanticEmitter.EmissionResult emission =
                    deal.codegen.jvm.JvmSemanticEmitter.emitProductionModule(
                        result.lowering().unit(), result.lowering().table(), true,
                        "GroupProdMain");
                Path sourceFile = WORKSPACE.resolve("GroupProdMain.java");
                Files.writeString(sourceFile, emission.source());
                Path classes = WORKSPACE.resolve("group-prod-classes");
                Files.createDirectories(classes);
                String classpath = System.getProperty("java.class.path", "");
                Process compile = new ProcessBuilder("javac", "--release", "25",
                    "-proc:none", "-cp", classpath, "-d", classes.toString(),
                    sourceFile.toAbsolutePath().toString())
                    .redirectErrorStream(true).start();
                String compileOutput = new String(
                    compile.getInputStream().readAllBytes());
                int compileExit = compile.waitFor();
                check(compileExit == 0, "the production shared-JVM group artifact "
                    + "compiles (exit " + compileExit + ": " + compileOutput.trim()
                    + ")");
                if (compileExit == 0) {
                    Process javaRun = new ProcessBuilder("java", "-cp",
                        classpath + java.io.File.pathSeparator + classes,
                        "GroupProdMain").redirectErrorStream(true).start();
                    String javaOutput = new String(
                        javaRun.getInputStream().readAllBytes());
                    int javaExit = javaRun.waitFor();
                    check(javaExit == 0 && javaOutput.isEmpty(),
                        "the production shared-JVM group artifact runs with the "
                            + "retained silent production terminal (exit " + javaExit
                            + ", output " + javaOutput.trim() + ")");
                }
            } catch (java.io.IOException | InterruptedException exception) {
                fail("production-mode group emission: infrastructure failure: "
                    + exception.getMessage());
            }
        }
    }

    // =========================================================================
    // 6. Pinned IR facts
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
    // Synthetic unit helpers (the hand-built extras corpus units)
    // =========================================================================

    private static int nextSyntheticValue = 1;

    private static ValueId nextValue() {
        return new ValueId(nextSyntheticValue++);
    }

    private static RuntimeDescriptor nullableInt() {
        return new RuntimeDescriptor.Nullable(RuntimeDescriptor.Int.INSTANCE);
    }

    /** One synthetic validated-shape op with a wired contract digest. */
    private static SemanticOp syntheticOp(SemanticOpKind kind, KindPayload payload,
                                          SemanticValue result, OpResultType resultType) {
        AnchorId anchor = new AnchorId(0);
        SourceOrigin origin = new SourceOrigin("test.deal",
            SourceSpan.synthetic("test.deal"), SourceOriginKind.SYNTHETIC, anchor, null);
        ClosedSelector selector = payload instanceof KindPayload.SelectorCarrying carrying
            ? carrying.selector() : null;
        OperationContractSnapshot placeholder = new OperationContractSnapshot(
            OperationContractSnapshot.VERSION, kind, resultType, List.of(), selector,
            payload, FailurePolicyId.NO_DEAL_FAILURE, List.of(), "placeholder");
        String digest = ContractSnapshotCanonicalizer.digest(placeholder);
        OperationContractSnapshot contract = new OperationContractSnapshot(
            OperationContractSnapshot.VERSION, kind, resultType, List.of(), selector,
            payload, FailurePolicyId.NO_DEAL_FAILURE, List.of(), digest);
        return new SemanticOp(new OpId(MODULE, nextSyntheticValue++), kind, origin, result,
            resultType, List.of(), List.of(), payload, FailurePolicyId.NO_DEAL_FAILURE,
            contract);
    }

    /** One synthetic unit over the extras corpus ops. */
    private static LoweredModuleUnit syntheticUnit(List<SemanticOp> ops) {
        return new LoweredModuleUnit(LoweredModuleUnit.FORMAT_VERSION,
            SemanticProfile.DEAL_V1_2_INT32, MODULE, INTERFACE_HASH,
            LoweringContextHash.of(SemanticProfile.DEAL_V1_2_INT32, REGISTRY_HASH),
            java.util.Set.of(), Map.of(), Map.of(), Map.of(),
            new ModuleInitPlan(List.of(), new BlockId(0)), ExportPlan.empty(), Map.of(),
            ops);
    }

    /** The single-block membership table of one synthetic unit. */
    private static StructuredBodyTable syntheticTable(List<SemanticOp> ops) {
        List<OpId> opIds = new ArrayList<>();
        Map<OpId, BlockId> opBlocks = new java.util.LinkedHashMap<>();
        for (SemanticOp op : ops) {
            opIds.add(op.opId());
            opBlocks.put(op.opId(), new BlockId(0));
        }
        return new StructuredBodyTable(Map.of(new BlockId(0), opIds), opBlocks);
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
        testContainerExtrasMatrix();
        testHasFieldPresenceMatrix();
        testRecursiveGroupMatrix();
        testRecursiveGroupArmPins();
        testPinnedIrFacts();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
