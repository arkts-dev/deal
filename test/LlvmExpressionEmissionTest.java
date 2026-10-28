package deal.test;

import deal.codegen.llvm.LlvmSelectorArms;
import deal.codegen.llvm.LlvmTypeMapper;
import deal.diagnostics.CompilerDiagnostic;
import deal.module.CompilationOrchestrator;
import deal.project.CliOverrides;
import deal.project.ProjectContext;
import deal.project.ProjectLocator;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.SharedValueSemantics;
import deal.semantic.ir.BinarySelector;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.ComparisonExecutor;
import deal.semantic.ir.ComparisonOperandView;
import deal.semantic.ir.ControlSelector;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.NullableSide;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.UnarySelector;
import deal.semantic.ir.UnicodeScalars;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The scalar, control-flow, arithmetic and selector rendering of the native
 * backend ({@code llvm-native-codegen-cfg-ssa} C4, C6, C9, {@code OpRendering};
 * {@code llvm-native-runtime-value-model} R5; task "bindings, expressions,
 * failure routing, control flow, binary64 arithmetic and all comparison/unary
 * selectors").
 *
 * <p>Every case compiles a real project through the production resolution and
 * emission path and inspects the <em>published</em> module of that compile:</p>
 *
 * <ol>
 *   <li>the closed selector tables carry exactly one arm per merged selector,
 *       with C6's pinned predicate or instruction;</li>
 *   <li>the emitted bytes carry every pinned predicate and type operation —
 *       the IEEE ordered/unordered comparisons, the one string-comparison
 *       authority, the boolean bytes, the distinct nullish singleton/tag
 *       tests, the closed data relation, managed reference identity,
 *       {@code fneg}, {@code xor i1}, {@code typeof} and the nominal tag
 *       test — and no fast-math flag on any instruction;</li>
 *   <li>{@code BRANCH}/{@code LOOP} render their structured targets, the
 *       failure route of a rendered check reaches the enclosing
 *       {@code TRY_CATCH} handler, the handler binds the identical carried
 *       Error as the boxed {@code unknown} datum, and every exit path leaves
 *       the root frame and pops the call frame;</li>
 *   <li>every managed temporary owns a root slot inside the activation's
 *       static slot count and no code pointer or scalar occupies one — which
 *       includes the closed datum of a function value: a value the stage
 *       cannot carry as the managed {@code deal_fn*} of C5 is rejected at its
 *       {@code unknown} boundary (one origin-bearing E6005, nothing published)
 *       and the {@code typeof} datum carries only the function tag with a NULL
 *       {@code reference};</li>
 *   <li>every emitted binding store is mapped to the IR fact it renders — one
 *       store per {@code BINDING_INIT}/{@code BINDING_STORE} op into that
 *       generation's own slot, exactly one entry store for a parameter and for
 *       a caught binding, and no store into a position that has no such
 *       fact;</li>
 *   <li>the failure path of every activation returns the enclosing function's
 *       own C4 outcome shape — {@code number}, {@code boolean}, the two scalar
 *       nullish aggregates and the persistent datum included — so an accepted
 *       nested scalar call is never spelled with the error-only aggregate;</li>
 *   <li>two compiles are byte-identical, and {@code llvm-as} plus the pinned
 *       {@code clang} accept the module at {@code -O0} and {@code -O2};</li>
 *   <li>the scalar, the nonfailing {@code try}/`catch` and the
 *       caught-assertion projects build and run as products at both levels
 *       with exit 0 and empty stdout;</li>
 *   <li>the assigned selector families are checked for oracle/emitter parity:
 *       every merged selector's emitted arm — its C6 form, predicate and
 *       runtime-entry result encoding evaluated over the runtime
 *       representation it reads — must equal the shared authority's answer
 *       ({@link ComparisonExecutor} and {@link SharedValueSemantics}) over the
 *       same operand matrix, and the emitted bytes carry that same arm. The
 *       logical traversal is derived from the recorded {@code BranchPayload}
 *       and the failure routing from {@link FailureContractRegistry};</li>
 *   <li>the logical selectors, the closed {@code unknown} relation and the
 *       fully terminating {@code try}/{@code catch} and {@code if}/{@code
 *       else} tails execute through the production toolchain seam at both
 *       levels: each wrong rendering reaches a pinned {@code E8001}
 *       assertion, so the exit status and terminal snapshot are the
 *       observation channel, and a control fixture proves the channel
 *       fires;</li>
 *   <li>a fact outside the staged set still raises one origin-bearing E6005
 *       with nothing published.</li>
 * </ol>
 *
 * <p>The observable boolean values are asserted through the {@code E8001}
 * assertion channel only: this leaf builds no class, container, stdlib, host or
 * export surface, so the executed fault/identity/framed-tuple evidence stays
 * with the data/class and call/closure stages. Those stages execute these same
 * arms and add no comparison or unary path of their own.</p>
 */
public final class LlvmExpressionEmissionTest {

    private static final Path REPO = NativeCompiler.repository();

    /** Every comparison, unary and type operation of the closed selectors. */
    private static final String SELECTOR_SOURCE = """
        function numberEq(a: number, b: number): boolean { return a === b; }
        function numberNe(a: number, b: number): boolean { return a !== b; }
        function numberLt(a: number, b: number): boolean { return a < b; }
        function numberLe(a: number, b: number): boolean { return a <= b; }
        function numberGt(a: number, b: number): boolean { return a > b; }
        function numberGe(a: number, b: number): boolean { return a >= b; }
        function stringEq(a: string, b: string): boolean { return a === b; }
        function stringNe(a: string, b: string): boolean { return a !== b; }
        function stringLt(a: string, b: string): boolean { return a < b; }
        function stringLe(a: string, b: string): boolean { return a <= b; }
        function stringGt(a: string, b: string): boolean { return a > b; }
        function stringGe(a: string, b: string): boolean { return a >= b; }
        function booleanEq(a: boolean, b: boolean): boolean { return a === b; }
        function booleanNe(a: boolean, b: boolean): boolean { return a !== b; }
        function nullEq(): boolean { return null === null; }
        function nullNe(): boolean { return null !== null; }
        function undefinedEq(): boolean { return undefined === undefined; }
        function undefinedNe(): boolean { return undefined !== undefined; }
        function unknownEq(a: unknown, b: unknown): boolean { return a === b; }
        function unknownNe(a: unknown, b: unknown): boolean { return a !== b; }
        function nullableEq(a: number | null, b: number | null): boolean {
          return a === b;
        }
        function nullableNe(a: number | null, b: number | null): boolean {
          return a !== b;
        }
        function nullableNullEq(a: number | null): boolean { return a === null; }
        function nullableNullNe(a: number | null): boolean { return a !== null; }
        function nullableUndefinedEq(a: number | undefined): boolean {
          return a === undefined;
        }
        function nullableUndefinedNe(a: number | undefined): boolean {
          return a !== undefined;
        }
        function nullableStringEq(a: string | null, b: string | null): boolean {
          return a === b;
        }
        function nullableStringNe(a: string | null, b: string | null): boolean {
          return a !== b;
        }
        function nullableStringNullEq(a: string | null): boolean {
          return a === null;
        }
        function referenceEq(a: () => number, b: () => number): boolean {
          return a === b;
        }
        function referenceNe(a: () => number, b: () => number): boolean {
          return a !== b;
        }
        function arithmetic(a: number, b: number): number {
          return a + b - a * b / b;
        }
        function negate(a: number): number { return -a; }
        function not(a: boolean): boolean { return !a; }
        function kindOf(a: unknown): string { return typeof a; }
        function isError(a: unknown): boolean { return a instanceof Error; }
        function asNumber(a: unknown): number { return a as number; }
        export function main(): null { return null; }
        """;

    /** Structured control flow, a checked assignment, and try/catch. */
    private static final String CONTROL_SOURCE = """
        function work(n: number): number {
          let total: number = 0;
          let i: number = 0;
          while (i < n) {
            if (i > 2) {
              total = total + i;
            } else {
              total = total - 1;
            }
            i = i + 1;
            if (i === 4) {
              break;
            }
          }
          return total;
        }

        function skips(n: number): number {
          let total: number = 0;
          let i: number = 0;
          while (i < n) {
            i = i + 1;
            if (i === 2) {
              continue;
            }
            total = total + i;
          }
          return total;
        }

        function kindOf(a: unknown): string {
          return typeof a;
        }

        function boxText(a: string | null): unknown {
          let boxed: unknown = a;
          return boxed;
        }

        export function main(): null {
          let total: number = work(5);
          let skipped: number = skips(4);
          let boxed: unknown = 7;
          let maybe: number | null = 1;
          let boxedMaybe: unknown = maybe;
          let text: string = kindOf(boxed);
          try {
            total = total + skipped;
          } catch (e) {
            total = 1;
          }
          return null;
        }
        """;

    /**
     * A rendered assertion whose check fails inside a catchable block: the
     * failure route reaches the handler and the activation completes.
     */
    private static final String CAUGHT_ASSERTION_SOURCE = """
        function asNumber(a: unknown): number {
          return a as number;
        }

        export function main(): null {
          let n: number = 0;
          try {
            let bad: unknown = null;
            n = asNumber(bad);
          } catch (e) {
            n = 1;
          }
          return null;
        }
        """;

    /**
     * The four logical traversal shapes and the right operand's effects: the
     * right operand's own op group lives in the recorded selected block, so a
     * right operand that fails the {@code as boolean} assertion shows whether
     * it ran. Each result and each observed effect is routed into a failing
     * {@code as number} assertion, so the exit status is the observation.
     */
    private static final String LOGICAL_SOURCE = """
        function wrong(): number {
          let v: unknown = true;
          return v as number;
        }

        function poison(): boolean {
          let v: unknown = 1;
          return v as boolean;
        }

        function andValue(a: boolean, b: boolean): boolean { return a && b; }
        function orValue(a: boolean, b: boolean): boolean { return a || b; }

        export function main(): null {
          let truth: boolean = true;
          let falsity: boolean = false;
          if (!(andValue(falsity, truth) === false)) { let a: number = wrong(); }
          if (!(andValue(truth, falsity) === false)) { let b: number = wrong(); }
          if (!(andValue(truth, truth) === true)) { let c: number = wrong(); }
          if (!(orValue(falsity, truth) === true)) { let d: number = wrong(); }
          if (!(orValue(truth, falsity) === true)) { let e: number = wrong(); }
          if (!(orValue(falsity, falsity) === false)) { let f: number = wrong(); }

          let orSkipped: boolean = false;
          try {
            let orLeft: boolean = truth || poison();
            if (!(orLeft === true)) { let g: number = wrong(); }
            orSkipped = true;
          } catch (e) {
            let caught: unknown = e;
          }
          if (!(orSkipped === true)) { let h: number = wrong(); }

          let andSkipped: boolean = false;
          try {
            let andLeft: boolean = falsity && poison();
            if (!(andLeft === false)) { let i: number = wrong(); }
            andSkipped = true;
          } catch (e) {
            let caught: unknown = e;
          }
          if (!(andSkipped === true)) { let j: number = wrong(); }

          let orRan: boolean = false;
          try {
            let orRight: boolean = falsity || poison();
            let orRightAfter: number = 0;
          } catch (e) {
            orRan = true;
          }
          if (!(orRan === true)) { let k: number = wrong(); }

          let andRan: boolean = false;
          try {
            let andRight: boolean = truth && poison();
            let andRightAfter: number = 0;
          } catch (e) {
            andRan = true;
          }
          if (!(andRan === true)) { let l: number = wrong(); }
          return null;
        }
        """;

    /**
     * The closed {@code unknown} relation through {@code deal_data_same}: equal
     * and distinct numbers, number versus null (both operand orders), and null
     * versus undefined, each observed through a failing assertion.
     */
    private static final String UNKNOWN_EQUALITY_SOURCE = """
        function wrong(): number {
          let v: unknown = true;
          return v as number;
        }

        function eq(a: unknown, b: unknown): boolean { return a === b; }
        function ne(a: unknown, b: unknown): boolean { return a !== b; }

        export function main(): null {
          let one: unknown = 1;
          let other: unknown = 1;
          let two: unknown = 2;
          let n: unknown = null;
          let u: unknown = undefined;
          if (!(eq(one, other) === true)) { let a: number = wrong(); }
          if (!(ne(one, two) === true)) { let b: number = wrong(); }
          if (!(eq(one, two) === false)) { let c: number = wrong(); }
          if (!(eq(one, n) === false)) { let d: number = wrong(); }
          if (!(eq(n, one) === false)) { let e: number = wrong(); }
          if (!(eq(n, u) === false)) { let f: number = wrong(); }
          if (!(eq(n, n) === true)) { let g: number = wrong(); }
          if (!(eq(u, u) === true)) { let h: number = wrong(); }
          if (!(ne(n, u) === true)) { let i: number = wrong(); }
          return null;
        }
        """;

    /**
     * Value-carrying activations whose every recorded path returns or throws:
     * the join after the structure op has no predecessor, so no fall-through
     * exit exists and the common {@code try}/{@code catch} and
     * {@code if}/{@code else} shapes must compile and link.
     */
    private static final String TERMINATING_TAIL_SOURCE = """
        function fromTry(n: number): number {
          try { return n; } catch (e) { return 1; }
        }

        function fromIf(n: number): number {
          if (n > 1) { return 1; } else { return 2; }
        }

        function fromLoop(n: number): number {
          while (true) { return n; }
        }

        function fromRethrow(n: number): number {
          try { return n; } catch (e) { throw (e as Error); }
        }

        export function main(): null {
          let a: number = fromTry(3);
          let b: number = fromIf(4);
          let c: number = fromLoop(5);
          let d: number = fromRethrow(6);
          return null;
        }
        """;


    /**
     * The observation channel's control: a deliberately wrong rendering would
     * reach this assertion, so a run of it must exit 1 with the E8001 arm.
     */
    private static final String LOGICAL_CONTROL_SOURCE = """
        function wrong(): number {
          let v: unknown = true;
          return v as number;
        }

        export function main(): null {
          let falsity: boolean = false;
          if (!(falsity || falsity)) { let x: number = wrong(); }
          return null;
        }
        """;

    /**
     * A function value at a declared {@code unknown} position, through a
     * returned parameter: the {@code deal_data} box of C3 would have to carry
     * the managed {@code deal_fn*} closure of C5, which this stage does not
     * construct, so the boundary is rejected instead of published as a datum
     * whose managed tag holds a code address.
     */
    private static final String FUNCTION_BOXED_RETURN_SOURCE = """
        function seven(): number { return 7; }
        function box(a: () => number): unknown { return a; }
        export function main(): null {
          let u: unknown = box(seven);
          return null;
        }
        """;

    /**
     * The same admission through a nullish function position: the datum form
     * decision looks through the nullish base, so `(() => number) | null` is
     * rejected too.
     */
    private static final String FUNCTION_BOXED_NULLISH_SOURCE = """
        function seven(): number { return 7; }
        function pick(a: (() => number) | null): unknown { return a; }
        export function main(): null {
          let maybe: (() => number) | null = seven;
          let u: unknown = pick(maybe);
          return null;
        }
        """;

    /**
     * The same admission at a {@code let} boundary: the module-scope function
     * binding and the {@code unknown} binding share one lowered value id, so a
     * boundary keyed on the value alone would also give the function slot the
     * other binding's declared descriptor.
     */
    private static final String FUNCTION_BOXED_DECLARATION_SOURCE = """
        function seven(): number { return 7; }
        export function main(): null {
          let boxed: unknown = seven;
          return null;
        }
        """;

    /**
     * The function identity stays usable where only its tag is needed: the
     * {@code typeof} projection classifies the tag, so its internal datum must
     * carry a NULL {@code reference}, and the module-scope function binding
     * keeps its own code-pointer slot.
     */
    private static final String FUNCTION_TYPEOF_SOURCE = """
        function seven(): number { return 7; }
        export function main(): null {
          let kind: string = typeof seven;
          let count: unknown = 1;
          return null;
        }
        """;

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("=== LLVM Expression Emission Tests (ISSUE-0909) ===\n");
        Path scratch = NativeCompiler.executableScratch("llvm-expressions");
        try {
            testSelectorParity();
            testClosedSelectorTables();
            Path selectors = compileProject(scratch, "selectors", SELECTOR_SOURCE);
            String module = Files.readString(selectors.resolve("main.ll"),
                StandardCharsets.UTF_8);
            testSelectorBytes(module);
            testEmittedComparatorParity(module);
            testFailureRoutingParity(module);
            testNoFastMath(module);
            testDebugLocations(module);
            testRootSlotsInsideTheFrame(module);
            testValidityAtBothLevels(selectors);
            testDeterminism(scratch);
            Path control = compileProject(scratch, "control", CONTROL_SOURCE);
            String controlModule = Files.readString(control.resolve("main.ll"),
                StandardCharsets.UTF_8);
            testStructuredControlFlow(controlModule);
            testAssignmentStoresOnce(control, controlModule);
            testExecution(scratch, control, selectors);
            Path caught = compileProject(scratch, "caught", CAUGHT_ASSERTION_SOURCE);
            String caughtModule = Files.readString(caught.resolve("main.ll"),
                StandardCharsets.UTF_8);
            testFailureRouteAndCatchBinding(caughtModule);
            testCaughtFailureProduct(scratch, caught);
            Path logical = compileProject(scratch, "logical", LOGICAL_SOURCE);
            String logicalModule = Files.readString(logical.resolve("main.ll"),
                StandardCharsets.UTF_8);
            testLogicalTraversalParity(logical);
            testCallContinuation(logicalModule);
            testTypedFailureReturns(scratch);
            testExecutedObservations(scratch);
            testTerminatingTails(scratch);
            testFunctionValueNeverBoxed(scratch);
            testRejections(scratch);
        } finally {
            deleteRecursively(scratch);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== LLVM Expression Emission Tests Passed ===");
    }

    // =========================================================================
    // 1. The closed selector tables
    // =========================================================================

    private static void testClosedSelectorTables() {
        System.out.println("-- one rendering arm per merged selector --");
        List<LlvmSelectorArms.BinaryArm> binary = LlvmSelectorArms.binaryArms();
        checkEq(BinarySelector.values().length, binary.size(),
            "every merged binary selector carries exactly one arm");
        Set<String> expectedBinary = new LinkedHashSet<>();
        for (BinarySelector selector : BinarySelector.values()) {
            expectedBinary.add(selector.name());
        }
        Set<String> actualBinary = new LinkedHashSet<>();
        for (LlvmSelectorArms.BinaryArm arm : binary) {
            actualBinary.add(arm.selector().name());
        }
        checkEq(expectedBinary, actualBinary,
            "the binary arm table covers the merged family exactly");
        Map<BinarySelector, String[]> pinned = Map.ofEntries(
            Map.entry(BinarySelector.NUMBER_ADD, new String[] {"ARITHMETIC", "", "fadd"}),
            Map.entry(BinarySelector.NUMBER_SUB, new String[] {"ARITHMETIC", "", "fsub"}),
            Map.entry(BinarySelector.NUMBER_MUL, new String[] {"ARITHMETIC", "", "fmul"}),
            Map.entry(BinarySelector.NUMBER_DIV_IEEE,
                new String[] {"ARITHMETIC", "", "fdiv"}),
            Map.entry(BinarySelector.NUMBER_EQ,
                new String[] {"IEEE_PREDICATE", "oeq", ""}),
            Map.entry(BinarySelector.NUMBER_NE,
                new String[] {"IEEE_PREDICATE", "une", ""}),
            Map.entry(BinarySelector.NUMBER_LT,
                new String[] {"IEEE_PREDICATE", "olt", ""}),
            Map.entry(BinarySelector.NUMBER_LE,
                new String[] {"IEEE_PREDICATE", "ole", ""}),
            Map.entry(BinarySelector.NUMBER_GT,
                new String[] {"IEEE_PREDICATE", "ogt", ""}),
            Map.entry(BinarySelector.NUMBER_GE,
                new String[] {"IEEE_PREDICATE", "oge", ""}),
            Map.entry(BinarySelector.STRING_EQ,
                new String[] {"TEXT_COMPARE", "eq", ""}),
            Map.entry(BinarySelector.STRING_NE,
                new String[] {"TEXT_COMPARE", "ne", ""}),
            Map.entry(BinarySelector.STRING_LT,
                new String[] {"TEXT_COMPARE", "slt", ""}),
            Map.entry(BinarySelector.STRING_LE,
                new String[] {"TEXT_COMPARE", "sle", ""}),
            Map.entry(BinarySelector.STRING_GT,
                new String[] {"TEXT_COMPARE", "sgt", ""}),
            Map.entry(BinarySelector.STRING_GE,
                new String[] {"TEXT_COMPARE", "sge", ""}),
            Map.entry(BinarySelector.BOOLEAN_EQ,
                new String[] {"BOOLEAN_BYTES", "eq", ""}),
            Map.entry(BinarySelector.BOOLEAN_NE,
                new String[] {"BOOLEAN_BYTES", "ne", ""}),
            Map.entry(BinarySelector.NULL_EQ,
                new String[] {"SINGLETON_TAGS", "eq", ""}),
            Map.entry(BinarySelector.NULL_NE,
                new String[] {"SINGLETON_TAGS", "ne", ""}),
            Map.entry(BinarySelector.UNDEFINED_EQ,
                new String[] {"SINGLETON_TAGS", "eq", ""}),
            Map.entry(BinarySelector.UNDEFINED_NE,
                new String[] {"SINGLETON_TAGS", "ne", ""}),
            Map.entry(BinarySelector.UNKNOWN_EQ,
                new String[] {"DATA_SAME", "eq", ""}),
            Map.entry(BinarySelector.UNKNOWN_NE,
                new String[] {"DATA_SAME", "ne", ""}),
            Map.entry(BinarySelector.NULLABLE_UNDEFINED_EQ,
                new String[] {"TAG_TEST", "eq", ""}),
            Map.entry(BinarySelector.NULLABLE_UNDEFINED_NE,
                new String[] {"TAG_TEST", "ne", ""}),
            Map.entry(BinarySelector.NULLABLE_NULL_EQ,
                new String[] {"TAG_TEST", "eq", ""}),
            Map.entry(BinarySelector.NULLABLE_NULL_NE,
                new String[] {"TAG_TEST", "ne", ""}),
            Map.entry(BinarySelector.NULLABLE_EQ,
                new String[] {"TAG_TEST", "eq", ""}),
            Map.entry(BinarySelector.NULLABLE_NE,
                new String[] {"TAG_TEST", "ne", ""}),
            Map.entry(BinarySelector.REFERENCE_EQ,
                new String[] {"IDENTITY", "eq", ""}),
            Map.entry(BinarySelector.REFERENCE_NE,
                new String[] {"IDENTITY", "ne", ""}));
        for (LlvmSelectorArms.BinaryArm arm : binary) {
            String[] expected = pinned.get(arm.selector());
            check(expected != null, "the arm of " + arm.selector()
                + " is pinned in the test's own table");
            if (expected == null) {
                continue;
            }
            checkEq(expected[0], arm.form().name(),
                "the emitted form of " + arm.selector());
            checkEq(expected[1], arm.predicate(),
                "the emitted predicate of " + arm.selector());
            checkEq(expected[2], arm.mnemonic(),
                "the emitted instruction of " + arm.selector());
        }
        List<LlvmSelectorArms.UnaryArm> unary = LlvmSelectorArms.unaryArms();
        checkEq(UnarySelector.values().length, unary.size(),
            "every merged unary selector carries exactly one arm");
        for (LlvmSelectorArms.UnaryArm arm : unary) {
            switch (arm.selector()) {
                case BOOL_NOT -> {
                    checkEq("XOR_TRUE", arm.form().name(),
                        "BOOL_NOT is the xor form");
                    check(arm.instruction().startsWith("xor i1 "),
                        "BOOL_NOT renders `xor i1 x, true`");
                }
                case NUMBER_NEG -> {
                    checkEq("FNEG", arm.form().name(),
                        "NUMBER_NEG is the fneg form");
                    check(arm.instruction().startsWith("fneg double "),
                        "NUMBER_NEG renders `fneg double x`, never fsub");
                }
                case TYPEOF -> {
                    checkEq("TYPE_NAME", arm.form().name(),
                        "TYPEOF is the six-name projection");
                    check(arm.instruction().contains("@deal_descriptor_type_name"),
                        "TYPEOF calls the one type-name projection");
                }
                case INSTANCEOF -> {
                    checkEq("CLASS_TAG", arm.form().name(),
                        "INSTANCEOF is the nominal tag test");
                    check(arm.instruction().contains("icmp eq i32"),
                        "INSTANCEOF compares the closed tag");
                }
            }
        }
    }

    // =========================================================================
    // 2. The emitted selector bytes (C6)
    // =========================================================================

    private static void testSelectorBytes(String module) {
        System.out.println("-- the emitted bytes carry C6's exact arms --");
        for (String predicate : List.of("oeq", "une", "olt", "ole", "ogt", "oge")) {
            check(module.contains("fcmp " + predicate + " double "),
                "the emitted binary64 comparison uses fcmp " + predicate);
        }
        for (String predicate : List.of("eq", "ne", "slt", "sle", "sgt", "sge")) {
            String function = functionOf(module, stringFunction(predicate));
            check(function.contains("@deal_text_compare"),
                "the string " + predicate + " arm calls the one comparison"
                    + " authority");
            check(function.contains("icmp " + predicate + " i32 "),
                "the string " + predicate + " arm compares deal_text_compare's"
                    + " result with icmp " + predicate);
        }
        check(functionOf(module, "booleanEq").contains("icmp eq i1 "),
            "boolean equality is one icmp eq over the 0/1 bytes");
        check(functionOf(module, "booleanNe").contains("icmp ne i1 "),
            "boolean inequality is one icmp ne over the 0/1 bytes");
        check(functionOf(module, "nullEq").contains("@deal_null_singleton"),
            "null equality tests the null singleton");
        check(functionOf(module, "nullNe").contains("icmp ne i8* "),
            "null inequality inverts the singleton test");
        check(functionOf(module, "undefinedEq")
                .contains("@deal_undefined_singleton"),
            "undefined equality tests the distinct undefined singleton");
        for (BinarySelector selector : List.of(BinarySelector.UNKNOWN_EQ,
                BinarySelector.UNKNOWN_NE)) {
            String name = selector == BinarySelector.UNKNOWN_EQ
                ? "unknownEq" : "unknownNe";
            String function = functionOf(module, name);
            check(function.contains("@deal_data_same"),
                "the " + name + " arm uses the closed data relation");
            check(function.contains("icmp "
                    + LlvmSelectorArms.resultPredicate(LlvmSelectorArms.binary(selector))
                    + " i32 "),
                "the " + name + " arm compares the relation's 1-when-equal result"
                    + " with the polarity the helper's encoding dictates");
        }
        String nullableNull = functionOf(module, "nullableNullEq");
        check(nullableNull.contains("extractvalue %struct.deal_nullish_number")
                && nullableNull.contains("icmp eq i32 "),
            "NULLABLE_NULL_EQ extracts the tagged scalar's tag and tests null");
        String nullableUndefined = functionOf(module, "nullableUndefinedEq");
        check(nullableUndefined.contains("extractvalue %struct.deal_nullish_number")
                && nullableUndefined.contains("icmp eq i32 "),
            "NULLABLE_UNDEFINED_EQ tests the undefined tag");
        check(functionOf(module, "nullableStringNullEq")
                .contains("@deal_null_singleton"),
            "a reference nullish position tests the null singleton pointer");
        String nullableEq = functionOf(module, "nullableEq");
        check(nullableEq.contains("fcmp oeq double "),
            "NULLABLE_EQ compares two base values with their own equality");
        String nullableNe = functionOf(module, "nullableNe");
        check(nullableNe.contains("fcmp une double "),
            "NULLABLE_NE inverts the base equality (NaN unequal)");
        String nullableStringEq = functionOf(module, "nullableStringEq");
        check(nullableStringEq.contains("@deal_text_compare"),
            "a reference nullish equality compares its base strings by value");
        check(functionOf(module, "referenceEq").contains("icmp eq i8* "),
            "REFERENCE_EQ is managed identity, not structural equality");
        check(functionOf(module, "referenceNe").contains("icmp ne i8* "),
            "REFERENCE_NE inverts managed identity");
        check(functionOf(module, "arithmetic").contains("fadd double ")
                && functionOf(module, "arithmetic").contains("fsub double ")
                && functionOf(module, "arithmetic").contains("fmul double ")
                && functionOf(module, "arithmetic").contains("fdiv double "),
            "the four arithmetic selectors use fadd/fsub/fmul/fdiv");
        check(functionOf(module, "negate").contains("fneg double "),
            "NUMBER_NEG uses fneg double");
        check(!functionOf(module, "negate").contains("fsub double 0.0"),
            "NUMBER_NEG never spells fsub +0.0, x");
        check(functionOf(module, "not").contains("xor i1 "),
            "BOOL_NOT uses xor i1");
        check(functionOf(module, "kindOf").contains("@deal_descriptor_type_name"),
            "TYPEOF renders the shared type-name projection (C6)");
        String instanceOf = functionOf(module, "isError");
        check(instanceOf.contains("extractvalue %struct.deal_data ")
                && instanceOf.contains("icmp eq i32 "),
            "INSTANCEOF over unknown data tests the nominal tag");
        String assertion = functionOf(module, "asNumber");
        check(assertion.contains("@deal_check_value")
                && assertion.contains("@deal_descriptor_intern"),
            "`as T` renders the one descriptor-driven check (E8001 arm)");
        check(assertion.contains("byval(%struct.deal_check_input)"),
            "the check input crosses the C ABI by value, as published");
    }

    /** The source function name of one string-comparison arm. */
    private static String stringFunction(String predicate) {
        return switch (predicate) {
            case "eq" -> "stringEq";
            case "ne" -> "stringNe";
            case "slt" -> "stringLt";
            case "sle" -> "stringLe";
            case "sgt" -> "stringGt";
            default -> "stringGe";
        };
    }

    private static void testNoFastMath(String module) {
        System.out.println("-- no instruction carries a fast-math flag --");
        Matcher flags = Pattern.compile(
            "(?m)^\\s+.*\\b(nnan|ninf|nsz|fast|reassoc|contract)\\b.*$")
            .matcher(module);
        List<String> offending = new ArrayList<>();
        while (flags.find()) {
            offending.add(flags.group().strip());
        }
        checkEq(List.of(), offending,
            "no instruction carries nnan/ninf/nsz/fast/reassoc/contract");
    }

    private static void testDebugLocations(String module) {
        System.out.println("-- every source-mapped group carries a location --");
        for (String name : List.of("numberEq", "stringLt", "kindOf", "asNumber")) {
            check(functionOf(module, name).contains("!dbg !"),
                "the function of " + name + " attaches DILocations");
        }
        check(module.contains("!DILocation("),
            "the module emits DILocation records");
        check(module.contains("@deal.origin."),
            "the module emits deal_origin records for its checks");
        String assertion = functionOf(module, "asNumber");
        check(assertion.contains("%struct.deal_origin* @deal.origin."),
            "the rendered check's context names its own origin record");
        // DiagnosticOriginProjection: the origin's span projects to the
        // compiled file text, one-based line and column, without rebasing.
        int[] position = sourcePosition(SELECTOR_SOURCE, "a as number");
        check(module.contains("i32 " + position[0] + ", i32 " + position[1] + " }"),
            "the rendered check's origin record carries the one-based line "
                + position[0] + " and column " + position[1]);
    }

    /** The one-based line and column of one source fragment's first character. */
    private static int[] sourcePosition(String source, String fragment) {
        int offset = source.indexOf(fragment);
        if (offset < 0) {
            throw new IllegalStateException("the fixture carries no '" + fragment
                + "'");
        }
        int line = 1;
        int lineStart = 0;
        for (int index = 0; index < offset; index++) {
            if (source.charAt(index) == '\n') {
                line++;
                lineStart = index + 1;
            }
        }
        return new int[] {line, offset - lineStart + 1};
    }

    /** Every managed slot is rooted inside the activation's static count. */
    private static void testRootSlotsInsideTheFrame(String module) {
        System.out.println("-- root slots stay inside the activation frame --");
        Matcher enter = Pattern.compile(
            "call void @deal_root_frame_enter\\(i32 (\\d+)\\)").matcher(module);
        int frames = 0;
        while (enter.find()) {
            frames++;
        }
        check(frames > 0, "every generated activation enters a root frame");
        for (String name : List.of("stringEq", "unknownEq", "kindOf")) {
            String function = functionOf(module, name);
            Matcher count = Pattern.compile(
                "call void @deal_root_frame_enter\\(i32 (\\d+)\\)")
                .matcher(function);
            check(count.find(), "the function of " + name + " enters a frame");
            int slots = Integer.parseInt(count.group(1));
            check(slots > 0, "a managed activation reserves at least one slot: "
                + name);
            Matcher writes = Pattern.compile(
                "deal_root_frame_write(?:_data)?\\(i32 (\\d+)").matcher(function);
            while (writes.find()) {
                int index = Integer.parseInt(writes.group(1));
                check(index < slots, "the root slot " + index + " of " + name
                    + " is inside its static count " + slots);
            }
        }
        check(functionOf(module, "numberEq").contains("deal_root_frame_enter(i32 0)"),
            "a scalar activation reserves no root slot");
        check(!functionOf(module, "numberEq").contains("deal_root_frame_write"),
            "no scalar value is rooted");
    }

    // =========================================================================
    // 2b. The typed failure return of a value-carrying activation (C4)
    // =========================================================================

    /**
     * One nested scalar call per closed result kind: every function declares
     * its own local callee, so the call is the staged {@code CALL(DIRECT)} whose
     * failure path leaves the activation.
     */
    private static final String TYPED_FAILURE_SOURCE = """
        function double(n: number): number {
          function inner(a: number): number {
            return a + a;
          }
          let doubled: number = inner(n);
          return doubled + 1.0;
        }

        function positive(n: number): boolean {
          function inner(a: number): number {
            return a + a;
          }
          let doubled: number = inner(n);
          return doubled > 0.0;
        }

        function maybeNumber(n: number): number | null {
          function inner(a: number): number {
            return a + a;
          }
          let doubled: number = inner(n);
          return doubled;
        }

        function maybeBoolean(n: number): boolean | undefined {
          function inner(a: number): number {
            return a + a;
          }
          let doubled: number = inner(n);
          return doubled > 0.0;
        }

        function anyValue(n: number): unknown {
          function inner(a: number): number {
            return a + a;
          }
          let doubled: number = inner(n);
          return doubled;
        }

        export function main(): null {
          let sum: number = double(2.0);
          let flag: boolean = positive(2.0);
          let maybe: number | null = maybeNumber(2.0);
          let maybeFlag: boolean | undefined = maybeBoolean(2.0);
          let carried: unknown = anyValue(2.0);
          return null;
        }
        """;

    /**
     * C4's failure return is the enclosing activation's outcome shape: a
     * function whose declared result carries a value returns its typed outcome
     * with the value slot untouched on failure. The check is structural over
     * the published module — every {@code ret} of an activation names that
     * activation's own {@code define} type and the failure aggregate is built
     * from it — and the module is then accepted by {@code llvm-as} and the
     * pinned {@code clang} at both levels, which is exactly what a failure path
     * spelled with the error-only aggregate is not.
     */
    private static void testTypedFailureReturns(Path scratch) throws Exception {
        System.out.println("-- a nested scalar call returns the enclosing"
            + " function's outcome shape --");
        Path published = compileProject(scratch, "typed-failures",
            TYPED_FAILURE_SOURCE);
        String module = Files.readString(published.resolve("main.ll"),
            StandardCharsets.UTF_8);
        Map<String, String> outcomes = new LinkedHashMap<>();
        outcomes.put("double", "%struct.deal_outcome_number");
        outcomes.put("positive", "%struct.deal_outcome_boolean");
        outcomes.put("maybeNumber", "%struct.deal_outcome_nullish_number");
        outcomes.put("maybeBoolean", "%struct.deal_outcome_nullish_boolean");
        outcomes.put("anyValue", "%struct.deal_outcome_data");
        for (Map.Entry<String, String> entry : outcomes.entrySet()) {
            String body = functionOf(module, entry.getKey());
            String outcome = entry.getValue();
            check(body.startsWith("define internal " + outcome + " @"),
                "the " + entry.getKey() + " activation declares the outcome "
                    + outcome);
            check(body.contains("%failure"),
                "the " + entry.getKey() + " activation renders the nested"
                    + " call's failure path");
            check(body.contains("insertvalue " + outcome
                    + " zeroinitializer, i8* "),
                "the " + entry.getKey() + " failure aggregate is built from its"
                    + " own outcome " + outcome);
            checkEq(0, foreignReturnTypes(body, outcome),
                "every return of the " + entry.getKey() + " activation carries "
                    + "its own outcome shape " + outcome);
        }
        testValidityAtBothLevels(published);
    }

    /** The returns of one function body whose aggregate is not {@code outcome}. */
    private static int foreignReturnTypes(String body, String outcome) {
        Matcher matcher = Pattern.compile("(?m)^\\s+ret (\\S+)").matcher(body);
        int foreign = 0;
        while (matcher.find()) {
            if (!matcher.group(1).equals(outcome)) {
                foreign++;
            }
        }
        return foreign;
    }

    private static void testValidityAtBothLevels(Path published)
            throws IOException {
        System.out.println("-- llvm-as and the pinned clang accept the module --");
        Path module = published.resolve("main.ll");
        NativeCompiler.Result assembled = NativeCompiler.run(
            List.of("llvm-as", module.toString(), "-o",
                published.resolve("probe.bc").toString()), published);
        checkEq(0, assembled.exitCode(),
            "llvm-as accepts the emitted module: " + assembled.describe());
        Files.deleteIfExists(published.resolve("probe.bc"));
        Path productRoot = Files.createDirectories(published.resolve("products"));
        for (int level : new int[] {0, 2}) {
            Path product = productRoot.resolve("native-" + level);
            NativeCompiler.Result link = NativeCompiler.link(published, level,
                NativeCompiler.productSources(published, List.of(module)),
                product);
            checkEq(0, link.exitCode(), "-O" + level
                + " the pinned clang accepts the module: " + link.describe());
        }
    }

    private static void testDeterminism(Path scratch) throws Exception {
        System.out.println("-- two compiles are byte-identical --");
        Path again = compileProject(scratch, "selectors-again", SELECTOR_SOURCE);
        checkEq(Files.readString(Path.of(scratch.toString(), "selectors",
                "build", "llvm", "main.ll"), StandardCharsets.UTF_8),
            Files.readString(again.resolve("main.ll"), StandardCharsets.UTF_8),
            "two compiles of one project produce identical module bytes");
    }

    // =========================================================================
    // 3. Structured control flow and assignment storage
    // =========================================================================

    private static void testStructuredControlFlow(String module) {
        System.out.println("-- structured branch/loop targets --");
        String work = functionOf(module, "work");
        check(work.contains("br i1 "), "BRANCH renders a conditional branch");
        check(work.contains("loopcheck"), "LOOP renders its condition block");
        check(work.contains("loopexit"), "LOOP renders its exit block");
        check(count(work, "br label %loop") >= 2,
            "the loop body branches back to the header (one back edge)");
        String skips = functionOf(module, "skips");
        check(skips.contains("loop"), "the continue loop renders its header");
        check(skips.contains("fcmp oeq double "),
            "the break/continue conditions are the recorded comparisons");
        check(work.contains("fcmp ogt double "),
            "the branch condition uses the ordered predicate of C6");
        for (String name : List.of("work", "skips")) {
            String function = functionOf(module, name);
            int leaves = count(function, "call void @deal_root_frame_leave()");
            int pops = count(function, "call void @deal_call_frame_pop()");
            Matcher returns = Pattern.compile("(?m)^\\s+ret ").matcher(function);
            int exits = 0;
            while (returns.find()) {
                exits++;
            }
            checkEq(exits, leaves, "every exit of " + name
                + " leaves the root frame");
            checkEq(exits, pops, "every exit of " + name
                + " pops the call frame");
        }
        check(functionOf(module, "work").contains(
                "call void @deal_call_frame_push("),
            "the activation pushes its own frame label (CallFrameContract)");
    }

    /**
     * The assignment publishes the once-evaluated RHS identity and stores it
     * exactly once per write op.
     *
     * <p>Every emitted store site is mapped to the IR fact it renders instead of
     * comparing two populations by size: a {@code BINDING_INIT}/{@code
     * BINDING_STORE} op of one {@code (binding, generation)} renders exactly one
     * store into that generation's own slot ({@code %c<binding>_<generation>} or
     * the module-scope {@code @deal.slot.<binding>.<generation>}); a parameter
     * binding's slot carries the one entry store {@code emitParameters} writes
     * and no write op; a caught binding's slot carries the one handler store
     * {@code emitTryCatch} writes and no write op. A double store, a dropped
     * store or a store into a position that has neither of those two facts fails
     * here, and no surplus entry store can pad a missing write.</p>
     */
    private static void testAssignmentStoresOnce(Path published, String module) {
        System.out.println("-- an assignment stores once and publishes the RHS --");
        ExecutableLoweredProject project = producedProject(
            published.getParent().getParent().resolve("src/main.deal"));
        check(project != null, "the control fixture's lowered project is"
            + " available for the store count");
        if (project == null) {
            return;
        }
        Map<SlotKey, Integer> writes = new LinkedHashMap<>();
        Map<SlotKey, String> entryStores = new LinkedHashMap<>();
        int writeOps = 0;
        for (LoweredModuleUnit unit : project.modules().values()) {
            Map<Long, Long> generations = new LinkedHashMap<>();
            for (SemanticOp op : unit.ops()) {
                if (op.payload() instanceof KindPayload.BindingAllocPayload alloc) {
                    generations.put(alloc.binding().id(), alloc.generation());
                }
            }
            for (SemanticOp op : unit.ops()) {
                switch (op.payload()) {
                    case KindPayload.BindingInitPayload init -> {
                        writes.merge(new SlotKey(init.binding().id(),
                            init.generation()), 1, Integer::sum);
                        writeOps++;
                    }
                    case KindPayload.BindingStorePayload store -> {
                        writes.merge(new SlotKey(store.binding().id(),
                            store.generation()), 1, Integer::sum);
                        writeOps++;
                    }
                    case KindPayload.TryCatchPayload caught -> entryStores.put(
                        new SlotKey(caught.catchBinding().id(),
                            generations.getOrDefault(
                                caught.catchBinding().id(), 0L)),
                        "catch handler");
                    default -> { }
                }
            }
            for (BindingId parameter : unit.classBindingFacts().parameters()) {
                entryStores.put(new SlotKey(parameter.id(),
                    generations.getOrDefault(parameter.id(), 0L)),
                    "parameter entry");
            }
        }
        Map<SlotKey, Integer> stores = new LinkedHashMap<>();
        Matcher slotStore = Pattern.compile("(?m)^\\s+store .*, \\S+\\* (?:%c(\\d+)_(\\d+)"
            + "|@deal\\.slot\\.(\\d+)\\.(\\d+))$").matcher(module);
        while (slotStore.find()) {
            boolean local = slotStore.group(1) != null;
            long binding = Long.parseLong(local
                ? slotStore.group(1) : slotStore.group(3));
            long generation = Long.parseLong(local
                ? slotStore.group(2) : slotStore.group(4));
            stores.merge(new SlotKey(binding, generation), 1, Integer::sum);
        }
        Set<SlotKey> keys = new LinkedHashSet<>(writes.keySet());
        keys.addAll(entryStores.keySet());
        keys.addAll(stores.keySet());
        int writeStores = 0;
        for (SlotKey key : keys) {
            boolean positioned = writes.containsKey(key)
                || entryStores.containsKey(key);
            check(positioned, "the slot " + key + " belongs to a write op or to"
                + " a parameter/catch position, never to an unrecorded store");
            int expected = writes.getOrDefault(key, 0)
                + (entryStores.containsKey(key) ? 1 : 0);
            int emitted = stores.getOrDefault(key, 0);
            checkEq(expected, emitted, "the slot " + key + " carries exactly one"
                + " store per write op"
                + (entryStores.containsKey(key)
                    ? " plus its single " + entryStores.get(key) + " store"
                    : ""));
            if (writes.containsKey(key)) {
                writeStores += emitted;
            }
        }
        check(!writes.isEmpty(), "the fixture records binding writes: "
            + writeOps);
        check(!entryStores.isEmpty(), "the fixture records the parameter and"
            + " catch positions its entry stores belong to");
        for (SlotKey key : writes.keySet()) {
            check(!entryStores.containsKey(key), "no entry store is attributed to"
                + " a write op: " + key);
        }
        checkEq(writeOps, writeStores, "every write op is one store and no entry"
            + " store pads a missing write (the ASSIGN op stores nothing)");
        check(module.contains("store %struct.deal_data %"),
            "the widened `unknown` binding stores the declared datum, not the"
                + " initializing expression's number");
        // The computed assignment's value is the RHS's own SSA slot.
        check(module.contains("fadd double "),
            "the assignment RHS is evaluated once by its arithmetic op");
    }

    /** One binding generation: the slot key of one stored value (C5). */
    private record SlotKey(long binding, long generation) {
        @Override
        public String toString() {
            return "c" + binding + "_" + generation;
        }
    }

    // =========================================================================
    // 4. Execution (validity only; see the class note)
    // =========================================================================

    private static void testExecution(Path scratch, Path control, Path selectors)
            throws Exception {
        System.out.println("-- the scalar and nonfailing try/catch products run --");
        for (Path published : List.of(control, selectors)) {
            for (int level : new int[] {0, 2}) {
                Path product = published.resolve("run-" + level);
                Files.createDirectories(product.getParent());
                NativeCompiler.Result link = NativeCompiler.link(published, level,
                    NativeCompiler.productSources(published,
                        List.of(published.resolve("main.ll"))), product);
                checkEq(0, link.exitCode(), "-O" + level + " the "
                    + published.getParent().getFileName() + " product links: "
                    + link.describe());
                if (link.exitCode() != 0) {
                    continue;
                }
                NativeCompiler.Result run = NativeCompiler.execute(product,
                    scratch, Map.of());
                checkEq(0, run.exitCode(), "-O" + level + " the "
                    + published.getParent().getFileName()
                    + " product exits 0: " + run.describe());
                checkEq("", run.stdout(), "-O" + level + " a successful run"
                    + " prints no terminal output");
            }
        }
    }

    private static void testCaughtFailureProduct(Path scratch, Path published)
            throws Exception {
        System.out.println("-- a caught check failure completes successfully --");
        for (int level : new int[] {0, 2}) {
            Path product = published.resolve("run-" + level);
            Files.createDirectories(product.getParent());
            NativeCompiler.Result link = NativeCompiler.link(published, level,
                NativeCompiler.productSources(published,
                    List.of(published.resolve("main.ll"))), product);
            checkEq(0, link.exitCode(), "-O" + level + " the caught-assertion"
                + " product links: " + link.describe());
            if (link.exitCode() != 0) {
                continue;
            }
            NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
                Map.of());
            checkEq(0, run.exitCode(), "-O" + level + " the caught assertion is"
                + " handled and the activation completes: " + run.describe());
            checkEq("", run.stdout(), "-O" + level + " the handled failure"
                + " prints no terminal output");
        }
    }

    // =========================================================================
    // 5. The failure route and the catch binding
    // =========================================================================

    private static void testFailureRouteAndCatchBinding(String module) {
        System.out.println("-- the failure route reaches the try/catch handler --");
        String checked = functionOf(module, "asNumber");
        check(checked.contains("checkfail"),
            "the rendered check has a failure arm");
        check(checked.contains("checkok"),
            "the rendered check has a success arm");
        check(checked.contains("call void @deal_root_frame_leave()")
                && checked.contains("call void @deal_call_frame_pop()"),
            "a propagated check failure restores the frame and root depth");
        String main = functionOf(module, "main");
        check(main.contains("store i8* ") && main.contains("i8** %pending"),
            "the failure route stores the carried Error in the handler's slot");
        check(main.contains("br label %catch"), "the failure route transfers to"
            + " the recorded catch block");
        check(main.contains("insertvalue %struct.deal_data { i32 9,"),
            "the handler binds the identical Error as the boxed unknown datum"
                + " (DEAL_DATA_ERROR)");
        check(main.contains("deal_root_frame_write_data"),
            "the catch binding's datum is rooted");
        check(!main.contains("@deal_error_materialize"),
            "the emitter authors no second failure projection: the check's"
                + " own arm materializes the Error");
    }

    // =========================================================================
    // 6. Rejections stay fail-closed
    // =========================================================================

    private static void testRejections(Path scratch) throws Exception {
        System.out.println("-- a fact outside the staged set is one E6005 --");
        Map<String, String> fixtures = new LinkedHashMap<>();
        fixtures.put("a string literal", """
            export function main(): null {
              let text: string = "x";
              return null;
            }
            """);
        fixtures.put("a managed reference equality", """
            function same(a: number[], b: number[]): boolean {
              return a === b;
            }
            export function main(): null { return null; }
            """);
        fixtures.put("a non-Error nominal instance test", """
            class Point { x: number; }
            function isPoint(a: unknown): boolean {
              return a instanceof Point;
            }
            export function main(): null { return null; }
            """);
        fixtures.put("an assertion to a declared class", """
            class Point { x: number; }
            function asPoint(a: unknown): Point {
              return a as Point;
            }
            export function main(): null { return null; }
            """);
        for (Map.Entry<String, String> fixture : fixtures.entrySet()) {
            Path root = scratch.resolve("reject-"
                + fixture.getKey().replaceAll("[^a-z]+", "-"));
            writeProject(root, fixture.getValue());
            CompileOutcome outcome = compile(root);
            check(!outcome.success(), "the " + fixture.getKey()
                + " project fails closed");
            check(!outcome.diagnostics().isEmpty(), "the "
                + fixture.getKey() + " rejection carries a diagnostic");
            if (!outcome.diagnostics().isEmpty()) {
                CompilerDiagnostic diagnostic = outcome.diagnostics().get(0);
                checkEq("E6005", diagnostic.code(), "the " + fixture.getKey()
                    + " rejection is one E6005");
                check(diagnostic.range() != null
                        && diagnostic.range().startLine() >= 1,
                    "the " + fixture.getKey() + " rejection carries its"
                        + " one-based origin");
            }
            check(!Files.exists(root.resolve("build/llvm")),
                "the " + fixture.getKey() + " rejection publishes nothing");
        }
    }


    // =========================================================================
    // 7. Oracle/emitter parity for the assigned selector families
    // =========================================================================

    /**
     * One operand as the emitted arm reads it: the runtime representation of
     * C3/R2 that the instruction consumes, plus the shared-authority operand
     * view ({@link ComparisonOperandView}) the executor compares.
     */
    private record Sample(Rep rep, ComparisonOperandView view) {
    }

    /** The runtime representation of one comparison operand (C3/R2). */
    private sealed interface Rep {
        /** A binary64 value (the emitted {@code double}). */
        record Num(double value) implements Rep { }
        /** A boolean SSA value (the emitted {@code i1}). */
        record Bool(boolean value) implements Rep { }
        /** A {@code deal_string*} carrying this scalar sequence. */
        record Str(String text) implements Rep { }
        /** The null singleton {@code deal_null_singleton}. */
        record Null() implements Rep { }
        /** The distinct undefined singleton. */
        record Undef() implements Rep { }
        /** A tagged scalar nullish value {@code {i32 tag, double value}}. */
        record Tagged(int tag, double value) implements Rep { }
        /** An {@code i8*} slot: a managed pointer or an admitted nullish singleton. */
        record Ptr(Object pointer) implements Rep { }
    }

    private static final Object NULL_SINGLETON = new Object();
    private static final Object UNDEFINED_SINGLETON = new Object();

    private static Sample number(double value) {
        return new Sample(new Rep.Num(value), new ComparisonOperandView.Number(value));
    }

    private static Sample flag(boolean value) {
        return new Sample(new Rep.Bool(value), new ComparisonOperandView.Boolean(value));
    }

    private static Sample text(String value) {
        return new Sample(new Rep.Str(value),
            new ComparisonOperandView.String(new UnicodeScalars.Valid(value)));
    }

    private static Sample nullSample() {
        return new Sample(new Rep.Null(), ComparisonOperandView.Null.INSTANCE);
    }

    private static Sample undefinedSample() {
        return new Sample(new Rep.Undef(), ComparisonOperandView.Undefined.INSTANCE);
    }

    private static Sample taggedNumber(int tag, double value) {
        ComparisonOperandView view = switch (tag) {
            case LlvmTypeMapper.NULLISH_NULL -> ComparisonOperandView.Null.INSTANCE;
            case LlvmTypeMapper.NULLISH_UNDEFINED ->
                ComparisonOperandView.Undefined.INSTANCE;
            default -> new ComparisonOperandView.Number(value);
        };
        return new Sample(new Rep.Tagged(tag, value), view);
    }

    private static Sample pointer(Object token) {
        ComparisonOperandView view = token == NULL_SINGLETON
            ? ComparisonOperandView.Null.INSTANCE
            : token == UNDEFINED_SINGLETON
                ? ComparisonOperandView.Undefined.INSTANCE
                : token instanceof String text
                    ? new ComparisonOperandView.String(new UnicodeScalars.Valid(text))
                    : new ComparisonOperandView.Ref(token);
        return new Sample(new Rep.Ptr(token), view);
    }

    /** One matrix entry: both operands and the executor's payload shape. */
    private record Case(Sample left, Sample right, RuntimeDescriptor inner,
                        NullableSide side) {
        Case(Sample left, Sample right) {
            this(left, right, null, null);
        }
    }

    /**
     * The oracle/emitter parity of the assigned selector families: for every
     * merged selector the emitted arm's semantics — C6's form, predicate and
     * runtime-entry result encoding evaluated over the runtime representation
     * the instruction reads — must equal the shared authority's answer
     * ({@link ComparisonExecutor} and {@link SharedValueSemantics}) over the
     * same operand matrix. The expectation is derived, never restated: an
     * inverted helper polarity, a wrong predicate or a null/undefined collapse
     * makes the two disagree.
     */
    private static void testSelectorParity() {
        System.out.println("-- selector parity against the shared authorities --");
        for (BinarySelector selector : BinarySelector.values()) {
            LlvmSelectorArms.BinaryArm arm = LlvmSelectorArms.binary(selector);
            if (arm.form() == LlvmSelectorArms.Form.ARITHMETIC) {
                testArithmeticParity(arm);
                continue;
            }
            checkEq(c6Form(selector), arm.form(),
                "the emitted form of " + selector + " is C6's");
            if (c6Form(selector) != arm.form()) {
                continue;
            }
            List<Case> matrix = matrixOf(selector);
            check(!matrix.isEmpty(), "the operand matrix of " + selector
                + " is not empty");
            for (Case entry : matrix) {
                boolean expected;
                try {
                    expected = ComparisonExecutor.compare(selector, entry.left().view(),
                        entry.right().view(), entry.inner(), entry.side());
                } catch (ComparisonExecutor.Defect defect) {
                    throw new IllegalStateException("the shared executor rejects the "
                        + selector + " matrix entry: " + defect.getMessage(), defect);
                }
                boolean emitted = emittedArm(arm, entry);
                checkEq(expected, emitted, "the emitted arm of " + selector
                    + " agrees with ComparisonExecutor for " + describe(entry));
            }
        }
        testUnaryParity();
    }

    /** C6's pinned instruction family of one selector. */
    private static LlvmSelectorArms.Form c6Form(BinarySelector selector) {
        return switch (selector) {
            case NUMBER_ADD, NUMBER_SUB, NUMBER_MUL, NUMBER_DIV_IEEE ->
                LlvmSelectorArms.Form.ARITHMETIC;
            case NUMBER_EQ, NUMBER_NE, NUMBER_LT, NUMBER_LE, NUMBER_GT, NUMBER_GE ->
                LlvmSelectorArms.Form.IEEE_PREDICATE;
            case STRING_EQ, STRING_NE, STRING_LT, STRING_LE, STRING_GT, STRING_GE ->
                LlvmSelectorArms.Form.TEXT_COMPARE;
            case BOOLEAN_EQ, BOOLEAN_NE -> LlvmSelectorArms.Form.BOOLEAN_BYTES;
            case NULL_EQ, NULL_NE, UNDEFINED_EQ, UNDEFINED_NE ->
                LlvmSelectorArms.Form.SINGLETON_TAGS;
            case UNKNOWN_EQ, UNKNOWN_NE -> LlvmSelectorArms.Form.DATA_SAME;
            case NULLABLE_EQ, NULLABLE_NE, NULLABLE_NULL_EQ, NULLABLE_NULL_NE,
                 NULLABLE_UNDEFINED_EQ, NULLABLE_UNDEFINED_NE ->
                LlvmSelectorArms.Form.TAG_TEST;
            case REFERENCE_EQ, REFERENCE_NE -> LlvmSelectorArms.Form.IDENTITY;
        };
    }

    /** The operand matrix of one selector, in the representation it reads. */
    private static List<Case> matrixOf(BinarySelector selector) {
        List<Case> matrix = new ArrayList<>();
        RuntimeDescriptor strings = RuntimeDescriptor.String.INSTANCE;
        RuntimeDescriptor numbers = RuntimeDescriptor.Number.INSTANCE;
        RuntimeDescriptor functions = new RuntimeDescriptor.Func(List.of(),
            RuntimeDescriptor.Number.INSTANCE);
        switch (selector) {
            case NUMBER_EQ, NUMBER_NE, NUMBER_LT, NUMBER_LE, NUMBER_GT, NUMBER_GE -> {
                for (double left : BINARY64) {
                    for (double right : BINARY64) {
                        matrix.add(new Case(number(left), number(right)));
                    }
                }
            }
            case STRING_EQ, STRING_NE, STRING_LT, STRING_LE, STRING_GT, STRING_GE -> {
                for (String left : SCALAR_SEQUENCES) {
                    for (String right : SCALAR_SEQUENCES) {
                        matrix.add(new Case(text(left), text(right)));
                    }
                }
            }
            case BOOLEAN_EQ, BOOLEAN_NE -> {
                matrix.add(new Case(flag(true), flag(true)));
                matrix.add(new Case(flag(true), flag(false)));
                matrix.add(new Case(flag(false), flag(true)));
                matrix.add(new Case(flag(false), flag(false)));
            }
            case NULL_EQ, NULL_NE ->
                matrix.add(new Case(nullSample(), nullSample()));
            case UNDEFINED_EQ, UNDEFINED_NE ->
                matrix.add(new Case(undefinedSample(), undefinedSample()));
            case UNKNOWN_EQ, UNKNOWN_NE -> {
                matrix.add(new Case(number(1.0), number(1.0)));
                matrix.add(new Case(number(1.0), number(2.0)));
                matrix.add(new Case(number(Double.NaN), number(Double.NaN)));
                matrix.add(new Case(number(0.0), number(-0.0)));
                matrix.add(new Case(text("a"), text("a")));
                matrix.add(new Case(text("a"), text("ab")));
                matrix.add(new Case(flag(true), flag(true)));
                matrix.add(new Case(flag(true), flag(false)));
                matrix.add(new Case(nullSample(), nullSample()));
                matrix.add(new Case(nullSample(), undefinedSample()));
                matrix.add(new Case(undefinedSample(), undefinedSample()));
                matrix.add(new Case(pointer(new Object()), pointer(nullReference())));
            }
            case NULLABLE_NULL_EQ, NULLABLE_NULL_NE -> {
                matrix.add(new Case(taggedNumber(LlvmTypeMapper.NULLISH_BASE, 1.0),
                    nullSample(), numbers, NullableSide.LEFT));
                matrix.add(new Case(taggedNumber(LlvmTypeMapper.NULLISH_NULL, 0.0),
                    nullSample(), numbers, NullableSide.LEFT));
                matrix.add(new Case(taggedNumber(LlvmTypeMapper.NULLISH_UNDEFINED, 0.0),
                    nullSample(), numbers, NullableSide.LEFT));
                matrix.add(new Case(nullSample(),
                    taggedNumber(LlvmTypeMapper.NULLISH_NULL, 0.0), numbers,
                    NullableSide.RIGHT));
                matrix.add(new Case(nullSample(),
                    taggedNumber(LlvmTypeMapper.NULLISH_BASE, 2.0), numbers,
                    NullableSide.RIGHT));
            }
            case NULLABLE_UNDEFINED_EQ, NULLABLE_UNDEFINED_NE -> {
                matrix.add(new Case(taggedNumber(LlvmTypeMapper.NULLISH_BASE, 1.0),
                    undefinedSample(), numbers, NullableSide.LEFT));
                matrix.add(new Case(taggedNumber(LlvmTypeMapper.NULLISH_UNDEFINED, 0.0),
                    undefinedSample(), numbers, NullableSide.LEFT));
                matrix.add(new Case(taggedNumber(LlvmTypeMapper.NULLISH_NULL, 0.0),
                    undefinedSample(), numbers, NullableSide.LEFT));
            }
            case NULLABLE_EQ, NULLABLE_NE -> {
                // Scalar nullish positions: the tagged scalar representation.
                matrix.add(new Case(taggedNumber(LlvmTypeMapper.NULLISH_BASE, 1.0),
                    taggedNumber(LlvmTypeMapper.NULLISH_BASE, 1.0), numbers,
                    NullableSide.BOTH));
                matrix.add(new Case(taggedNumber(LlvmTypeMapper.NULLISH_BASE, 1.0),
                    taggedNumber(LlvmTypeMapper.NULLISH_BASE, 2.0), numbers,
                    NullableSide.BOTH));
                matrix.add(new Case(taggedNumber(LlvmTypeMapper.NULLISH_BASE, 1.0),
                    taggedNumber(LlvmTypeMapper.NULLISH_NULL, 0.0), numbers,
                    NullableSide.BOTH));
                matrix.add(new Case(taggedNumber(LlvmTypeMapper.NULLISH_NULL, 0.0),
                    taggedNumber(LlvmTypeMapper.NULLISH_NULL, 0.0), numbers,
                    NullableSide.BOTH));
                matrix.add(new Case(taggedNumber(LlvmTypeMapper.NULLISH_UNDEFINED, 0.0),
                    taggedNumber(LlvmTypeMapper.NULLISH_UNDEFINED, 0.0), numbers,
                    NullableSide.BOTH));
                matrix.add(new Case(taggedNumber(LlvmTypeMapper.NULLISH_NULL, 0.0),
                    taggedNumber(LlvmTypeMapper.NULLISH_UNDEFINED, 0.0), numbers,
                    NullableSide.BOTH));
                // Reference nullish positions: the nullish singleton pointers
                // and two base strings compared by value.
                matrix.add(new Case(pointer(NULL_SINGLETON), pointer(NULL_SINGLETON),
                    strings, NullableSide.BOTH));
                matrix.add(new Case(pointer(NULL_SINGLETON), pointer("a"), strings,
                    NullableSide.BOTH));
                matrix.add(new Case(pointer(NULL_SINGLETON),
                    pointer(UNDEFINED_SINGLETON), strings, NullableSide.BOTH));
                matrix.add(new Case(pointer("a"), pointer("a"), strings,
                    NullableSide.BOTH));
                matrix.add(new Case(pointer("a"), pointer("ab"), strings,
                    NullableSide.BOTH));
            }
            case REFERENCE_EQ, REFERENCE_NE -> {
                Object token = new Object();
                matrix.add(new Case(pointer(token), pointer(token), functions,
                    NullableSide.BOTH));
                matrix.add(new Case(pointer(token), pointer(new Object()), functions,
                    NullableSide.BOTH));
            }
            default -> throw new IllegalStateException("no matrix for " + selector);
        }
        return matrix;
    }

    private static Object nullReference() {
        return new Object();
    }

    /** The binary64 matrix of C6 (NaN, both zeros, the infinities, extremes). */
    private static final double[] BINARY64 = {
        1.0, 2.0, -1.5, 0.0, -0.0, Double.NaN,
        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
    };

    /** The scalar-sequence matrix of R8 (empty, prefixes, non-ASCII). */
    private static final String[] SCALAR_SEQUENCES = {
        "", "a", "b", "ab", "\u00e9", "z\u00e9"
    };

    /**
     * The semantics of one emitted arm over one matrix entry: C6's form,
     * predicate and runtime-entry result encoding applied to the values the
     * instruction reads.
     */
    private static boolean emittedArm(LlvmSelectorArms.BinaryArm arm, Case entry) {
        return switch (arm.form()) {
            case ARITHMETIC -> throw new IllegalStateException(
                "the arithmetic arm of " + arm.selector() + " is not a comparison");
            case IEEE_PREDICATE -> ordered(arm.predicate(), numberValue(entry.left()),
                numberValue(entry.right()));
            case BOOLEAN_BYTES -> booleanPredicate(arm.predicate(),
                flagValue(entry.left()) == flagValue(entry.right()));
            case TEXT_COMPARE -> ordered(arm.predicate(), scalarOrder(entry.left(),
                entry.right()), 0);
            case SINGLETON_TAGS -> booleanPredicate(arm.predicate(),
                nullishKind(entry.left().rep()) == nullishKind(entry.right().rep()));
            case DATA_SAME -> {
                // deal_data_same's published encoding: 1 exactly when the
                // closed === relation holds.
                boolean same = ComparisonExecutor.compare(BinarySelector.UNKNOWN_EQ,
                    entry.left().view(), entry.right().view(), null, null);
                yield ordered(LlvmSelectorArms.resultPredicate(arm), same ? 1 : 0,
                    Integer.parseInt(LlvmSelectorArms.resultOperand(arm)));
            }
            case TAG_TEST -> tagTest(arm, entry);
            case IDENTITY -> booleanPredicate(arm.predicate(),
                entry.left().rep() instanceof Rep.Ptr left
                    && entry.right().rep() instanceof Rep.Ptr right
                    && left.pointer() == right.pointer());
        };
    }

    /** {@code NULLABLE_*}: the nullish tag first, then the base equality. */
    private static boolean tagTest(LlvmSelectorArms.BinaryArm arm, Case entry) {
        BinarySelector selector = arm.selector();
        if (selector == BinarySelector.NULLABLE_NULL_EQ
                || selector == BinarySelector.NULLABLE_NULL_NE
                || selector == BinarySelector.NULLABLE_UNDEFINED_EQ
                || selector == BinarySelector.NULLABLE_UNDEFINED_NE) {
            Sample named = entry.side() == NullableSide.RIGHT ? entry.right() : entry.left();
            String expected = selector == BinarySelector.NULLABLE_NULL_EQ
                    || selector == BinarySelector.NULLABLE_NULL_NE
                ? "null" : "undefined";
            return booleanPredicate(arm.predicate(),
                expected.equals(nullishKind(named.rep())));
        }
        String leftKind = nullishKind(entry.left().rep());
        String rightKind = nullishKind(entry.right().rep());
        if (leftKind != null || rightKind != null) {
            return booleanPredicate(arm.predicate(),
                leftKind != null && leftKind.equals(rightKind));
        }
        return booleanPredicate(arm.predicate(), baseEqual(entry));
    }

    /** The base-value equality of C6, derived from the shared executor. */
    private static boolean baseEqual(Case entry) {
        BinarySelector base = entry.inner() instanceof RuntimeDescriptor.String
            ? BinarySelector.STRING_EQ
            : entry.inner() instanceof RuntimeDescriptor.Boolean
                ? BinarySelector.BOOLEAN_EQ
                : BinarySelector.NUMBER_EQ;
        return ComparisonExecutor.compare(base, entry.left().view(),
            entry.right().view(), null, null);
    }

    private static String nullishKind(Rep rep) {
        return switch (rep) {
            case Rep.Null ignored -> "null";
            case Rep.Undef ignored -> "undefined";
            case Rep.Tagged tagged -> tagged.tag() == LlvmTypeMapper.NULLISH_NULL
                ? "null"
                : tagged.tag() == LlvmTypeMapper.NULLISH_UNDEFINED ? "undefined" : null;
            case Rep.Ptr pointer -> pointer.pointer() == NULL_SINGLETON
                ? "null"
                : pointer.pointer() == UNDEFINED_SINGLETON ? "undefined" : null;
            default -> null;
        };
    }

    /** {@code deal_text_compare}'s -1/0/1, derived from the shared executor. */
    private static int scalarOrder(Sample left, Sample right) {
        if (ComparisonExecutor.compare(BinarySelector.STRING_EQ, left.view(),
                right.view(), null, null)) {
            return 0;
        }
        return ComparisonExecutor.compare(BinarySelector.STRING_LT, left.view(),
            right.view(), null, null) ? -1 : 1;
    }

    /** The emitted {@code fcmp} predicate's semantics, from the shared rows. */
    private static boolean ordered(String predicate, double left, double right) {
        return switch (predicate) {
            case "oeq" -> SharedValueSemantics.numberEq(left, right);
            case "une" -> SharedValueSemantics.numberNe(left, right);
            case "olt" -> SharedValueSemantics.numberLt(left, right);
            case "ole" -> SharedValueSemantics.numberLe(left, right);
            case "ogt" -> SharedValueSemantics.numberGt(left, right);
            case "oge" -> SharedValueSemantics.numberGe(left, right);
            default -> throw new IllegalStateException(
                "the predicate " + predicate + " is not an ordered/unordered"
                    + " binary64 predicate");
        };
    }

    private static boolean ordered(String predicate, int left, int right) {
        return switch (predicate) {
            case "eq" -> left == right;
            case "ne" -> left != right;
            case "slt" -> left < right;
            case "sle" -> left <= right;
            case "sgt" -> left > right;
            case "sge" -> left >= right;
            default -> throw new IllegalStateException(
                "the predicate " + predicate + " is not an integer comparison");
        };
    }

    private static boolean booleanPredicate(String predicate, boolean equal) {
        return switch (predicate) {
            case "eq" -> equal;
            case "ne" -> !equal;
            default -> throw new IllegalStateException(
                "the predicate " + predicate + " is not a boolean comparison");
        };
    }

    private static double numberValue(Sample sample) {
        if (sample.rep() instanceof Rep.Num value) {
            return value.value();
        }
        throw new IllegalStateException("not a number representation: " + sample.rep());
    }

    private static boolean flagValue(Sample sample) {
        if (sample.rep() instanceof Rep.Bool value) {
            return value.value();
        }
        throw new IllegalStateException("not a boolean representation: " + sample.rep());
    }

    private static String describe(Case entry) {
        return entry.left().rep() + " / " + entry.right().rep();
    }

    /** The arithmetic arms against {@link SharedValueSemantics}. */
    private static void testArithmeticParity(LlvmSelectorArms.BinaryArm arm) {
        double[] values = new double[BINARY64.length + 3];
        System.arraycopy(BINARY64, 0, values, 0, BINARY64.length);
        values[BINARY64.length] = Double.MIN_VALUE;
        values[BINARY64.length + 1] = Double.MAX_VALUE;
        values[BINARY64.length + 2] = 5e-324;
        for (double left : values) {
            for (double right : values) {
                double emitted = switch (arm.mnemonic()) {
                    case "fadd" -> left + right;
                    case "fsub" -> left - right;
                    case "fmul" -> left * right;
                    case "fdiv" -> left / right;
                    default -> throw new IllegalStateException("the arm of "
                        + arm.selector() + " names no arithmetic instruction: "
                        + arm.mnemonic());
                };
                double shared = switch (arm.selector()) {
                    case NUMBER_ADD -> SharedValueSemantics.numberAdd(left, right);
                    case NUMBER_SUB -> SharedValueSemantics.numberSub(left, right);
                    case NUMBER_MUL -> SharedValueSemantics.numberMul(left, right);
                    case NUMBER_DIV_IEEE -> SharedValueSemantics.numberDiv(left, right);
                    default -> throw new IllegalStateException("the arithmetic arm of "
                        + arm.selector() + " has no shared row");
                };
                checkEq(Double.doubleToRawLongBits(shared),
                    Double.doubleToRawLongBits(emitted), "the " + arm.mnemonic()
                        + " arm of " + arm.selector() + " agrees bit-for-bit with"
                        + " SharedValueSemantics for " + left + " / " + right);
            }
        }
    }

    /** The unary arms against {@link SharedValueSemantics}. */
    private static void testUnaryParity() {
        for (UnarySelector selector : UnarySelector.values()) {
            LlvmSelectorArms.UnaryArm arm = LlvmSelectorArms.unary(selector);
            switch (selector) {
                case BOOL_NOT -> {
                    for (boolean value : new boolean[] {true, false}) {
                        boolean emitted = arm.form() == LlvmSelectorArms.UnaryForm.XOR_TRUE
                            ? value ^ true : value;
                        checkEq(SharedValueSemantics.boolNot(value), emitted,
                            "the BOOL_NOT arm agrees with SharedValueSemantics.boolNot");
                    }
                }
                case NUMBER_NEG -> {
                    for (double value : new double[] {0.0, -0.0, 1.5, -1.5,
                            Double.NaN, Double.POSITIVE_INFINITY,
                            Double.NEGATIVE_INFINITY, Double.MIN_VALUE}) {
                        double emitted = arm.form() == LlvmSelectorArms.UnaryForm.FNEG
                            ? -value : value;
                        checkEq(Double.doubleToRawLongBits(
                                SharedValueSemantics.numberNeg(value)),
                            Double.doubleToRawLongBits(emitted),
                            "the NUMBER_NEG arm agrees bit-for-bit with"
                                + " SharedValueSemantics.numberNeg for " + value);
                    }
                }
                case TYPEOF -> checkEq(LlvmSelectorArms.UnaryForm.TYPE_NAME, arm.form(),
                    "TYPEOF renders the shared six-name projection");
                case INSTANCEOF -> checkEq(LlvmSelectorArms.UnaryForm.CLASS_TAG, arm.form(),
                    "INSTANCEOF renders the nominal tag test");
            }
        }
    }

    /**
     * The emitted-bytes half of the parity: each module function carries the
     * comparison its arm names, with the polarity the runtime entry's result
     * encoding dictates.
     */
    private static void testEmittedComparatorParity(String module) {
        System.out.println("-- the emitted bytes carry the parity-derived arms --");
        for (BinarySelector selector : BinarySelector.values()) {
            LlvmSelectorArms.BinaryArm arm = LlvmSelectorArms.binary(selector);
            String name = sampleFunction(selector);
            if (name == null) {
                continue;
            }
            String function = functionOf(module, name);
            switch (arm.form()) {
                case IEEE_PREDICATE -> check(function.contains("fcmp "
                        + arm.predicate() + " double "),
                    "the emitted " + selector + " arm carries fcmp "
                        + arm.predicate());
                case BOOLEAN_BYTES -> check(function.contains("icmp "
                        + arm.predicate() + " i1 "),
                    "the emitted " + selector + " arm carries icmp "
                        + arm.predicate() + " i1");
                case IDENTITY -> check(function.contains("icmp " + arm.predicate()
                        + " i8* "), "the emitted " + selector
                        + " arm compares managed identity with icmp "
                        + arm.predicate());
                case SINGLETON_TAGS -> check(function.contains("icmp "
                        + arm.predicate() + " i8* "),
                    "the emitted " + selector + " arm tests the distinct"
                        + " singleton with icmp " + arm.predicate());
                case TEXT_COMPARE -> check(function.contains("icmp "
                        + LlvmSelectorArms.resultPredicate(arm) + " i32 "),
                    "the emitted " + selector + " arm applies the zero-based"
                        + " ordering predicate "
                        + LlvmSelectorArms.resultPredicate(arm));
                case DATA_SAME -> check(function.contains("call i32 @deal_data_same")
                        && function.contains("icmp "
                            + LlvmSelectorArms.resultPredicate(arm) + " i32 "),
                    "the emitted " + selector + " arm applies the truth-result"
                        + " polarity " + LlvmSelectorArms.resultPredicate(arm));
                case TAG_TEST -> check(function.contains("icmp " + arm.predicate()
                        + " i32 ") || function.contains("icmp " + arm.predicate()
                        + " i8* "),
                    "the emitted " + selector + " arm compares the nullish tag"
                        + " with icmp " + arm.predicate());
                case ARITHMETIC -> check(function.contains(arm.mnemonic()
                        + " double "), "the emitted " + selector
                        + " arm carries " + arm.mnemonic());
            }
        }
    }

    /** The fixture function carrying one selector arm, or {@code null}. */
    private static String sampleFunction(BinarySelector selector) {
        return switch (selector) {
            case NUMBER_ADD, NUMBER_SUB, NUMBER_MUL, NUMBER_DIV_IEEE -> "arithmetic";
            case NUMBER_EQ -> "numberEq";
            case NUMBER_NE -> "numberNe";
            case NUMBER_LT -> "numberLt";
            case NUMBER_LE -> "numberLe";
            case NUMBER_GT -> "numberGt";
            case NUMBER_GE -> "numberGe";
            case STRING_EQ -> "stringEq";
            case STRING_NE -> "stringNe";
            case STRING_LT -> "stringLt";
            case STRING_LE -> "stringLe";
            case STRING_GT -> "stringGt";
            case STRING_GE -> "stringGe";
            case BOOLEAN_EQ -> "booleanEq";
            case BOOLEAN_NE -> "booleanNe";
            case NULL_EQ -> "nullEq";
            case NULL_NE -> "nullNe";
            case UNDEFINED_EQ -> "undefinedEq";
            case UNDEFINED_NE -> "undefinedNe";
            case UNKNOWN_EQ -> "unknownEq";
            case UNKNOWN_NE -> "unknownNe";
            case NULLABLE_EQ -> "nullableEq";
            case NULLABLE_NE -> "nullableNe";
            case NULLABLE_NULL_EQ -> "nullableNullEq";
            case NULLABLE_NULL_NE -> "nullableNullNe";
            case NULLABLE_UNDEFINED_EQ -> "nullableUndefinedEq";
            case NULLABLE_UNDEFINED_NE -> "nullableUndefinedNe";
            case REFERENCE_EQ -> "referenceEq";
            case REFERENCE_NE -> "referenceNe";
        };
    }

    /**
     * The logical traversal parity: for every recorded logical {@code BRANCH}
     * the emitted conditional branch must enter the recorded selected block
     * exactly when the left operand does not decide the result — on true for
     * {@code LOGICAL_AND} and on false for {@code LOGICAL_OR}
     * ({@code BranchPayload} traversal of {@code SemanticLowerer}).
     */
    private static void testLogicalTraversalParity(Path published) {
        System.out.println("-- the logical traversal follows the recorded payload --");
        ExecutableLoweredProject project = producedProject(published.getParent()
            .getParent().resolve("src/main.deal"));
        check(project != null, "the logical fixture's lowered project is available");
        if (project == null) {
            return;
        }
        String module;
        try {
            module = Files.readString(published.resolve("main.ll"),
                StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        Matcher branches = Pattern.compile(
            "br i1 [^,\\n]+, label %(\\w+), label %(\\w+)").matcher(module);
        List<String[]> targets = new ArrayList<>();
        while (branches.find()) {
            targets.add(new String[] {branches.group(1), branches.group(2)});
        }
        int logical = 0;
        for (var unit : project.modules().values()) {
            for (SemanticOp op : unit.ops()) {
                if (op.kind() != SemanticOpKind.BRANCH
                        || !(op.payload() instanceof KindPayload.BranchPayload payload)) {
                    continue;
                }
                if (payload.selector() != ControlSelector.LOGICAL_AND
                        && payload.selector() != ControlSelector.LOGICAL_OR) {
                    continue;
                }
                logical++;
                String selected = "Lb" + payload.selectedBlock().id();
                boolean found = false;
                for (String[] pair : targets) {
                    if (!pair[0].equals(selected) && !pair[1].equals(selected)) {
                        continue;
                    }
                    found = true;
                    boolean entersOnTrue = pair[0].equals(selected);
                    checkEq(payload.selector() == ControlSelector.LOGICAL_AND,
                        entersOnTrue, "the recorded " + payload.selector()
                            + " branch " + op.opId() + " enters its right-operand"
                            + " block on "
                            + (payload.selector() == ControlSelector.LOGICAL_AND
                                ? "true (left false decides false)"
                                : "false (left true decides true)"));
                }
                check(found, "the module publishes the recorded logical branch "
                    + op.opId());
            }
        }
        check(logical >= 4, "the fixture records logical branches to verify: "
            + logical);
    }

    /**
     * The failure-routing parity: every rendered check keeps the registry's own
     * arm table (R5) and the module installs exactly the canonical arms.
     */
    private static void testFailureRoutingParity(String module) {
        System.out.println("-- the rendered failure routing keeps the registry arms --");
        List<String> canonical = FailureContractRegistry.canonicalArmSerialization();
        checkEq(FailureContractRegistry.arms().size(), canonical.size(),
            "the canonical arm serialization covers every registry arm");
        check(module.contains("x %struct.deal_arm_record], ["
                + FailureContractRegistry.arms().size() + " x %struct.deal_arm_record]"),
            "the module installs exactly the registry's arms (R5)");
        check(module.contains("call i32 @deal_arm_table_install("),
            "the module installs its arm table before user code");
        int asserts = count(module, "@deal_check_value(");
        check(asserts > 0, "the fixture renders at least one typed check");
    }

    /**
     * The call continuation: the carried Error is tested explicitly, so the
     * success path after a call is a reachable block and its effects run (C4).
     */
    private static void testCallContinuation(String module) {
        System.out.println("-- every rendered call tests its carried Error --");
        Matcher calls = Pattern.compile(
            "%callerr\\d+ = extractvalue [^\\n]*\\n\\s+%tmp\\d+ = icmp ne i8\\* [^,]+"
                + ", null[^\\n]*\\n\\s+br i1 %tmp\\d+, label %(\\w+), label %(\\w+)")
            .matcher(module);
        int tested = 0;
        while (calls.find()) {
            tested++;
            check(calls.group(1).startsWith("callfail"),
                "the tested failure enters the call's failure arm");
            check(calls.group(2).startsWith("callok"),
                "the untested success enters the call's continuation");
        }
        check(tested > 0, "a rendered call tests its outcome before the"
            + " continuation");
        check(module.contains("label %callok"),
            "the call continuation is a CFG predecessor of its success block");
    }


    /**
     * The executed observations at both levels: every fixture routes a wrong
     * rendering into the {@code E8001} assertion, so the exit status and the
     * terminal snapshot are the observation channel; the control fixture proves
     * the channel fires.
     */
    private static void testExecutedObservations(Path scratch) throws Exception {
        System.out.println("-- the logical and data-relation products run --");
        for (String fixture : List.of("logical", "equality")) {
            String source = "logical".equals(fixture)
                ? LOGICAL_SOURCE : UNKNOWN_EQUALITY_SOURCE;
            Path published = compileProject(scratch, fixture, source);
            for (int level : new int[] {0, 2}) {
                NativeCompiler.Result run = runProduct(scratch, published, level,
                    fixture);
                checkEq(0, run.exitCode(), "-O" + level + " the " + fixture
                    + " fixture's rendered booleans agree with C6: "
                    + run.describe());
                checkEq("", run.stdout(), "-O" + level + " the " + fixture
                    + " fixture prints no terminal output");
            }
        }
        Path control = compileProject(scratch, "logical-control",
            LOGICAL_CONTROL_SOURCE);
        for (int level : new int[] {0, 2}) {
            NativeCompiler.Result run = runProduct(scratch, control, level,
                "logical-control");
            checkEq(1, run.exitCode(), "-O" + level + " the control fixture"
                + " reaches its assertion: " + run.describe());
            check(run.stdout().contains("DEAL_ERROR_CODE: E8001"),
                "-O" + level + " the control fixture reports the E8001 arm");
        }
    }

    /**
     * Builds one published fixture through the production toolchain seam
     * ({@code NativeToolchain}, the product shape of D3/D4/D5) and runs its
     * product: the durable path is the caller's own, outside the publication
     * root, exactly as {@code deal native build} places it.
     */
    private static NativeCompiler.Result runProduct(Path scratch, Path published,
                                                    int level, String name)
            throws Exception {
        Path product = scratch.resolve("products").resolve(name + "-" + level);
        Files.createDirectories(product.getParent());
        try {
            deal.toolchain.NativeToolchain.build(new deal.toolchain.NativeBuildRequest(
                published, product, level, List.of(), null));
        } catch (deal.toolchain.NativeBuildException failure) {
            throw new IllegalStateException("the production toolchain seam rejects"
                + " the " + name + " build at -O" + level + ": "
                + failure.getMessage(), failure);
        }
        check(Files.isExecutable(product), "the " + name + " product at -O" + level
            + " is a durable executable outside the publication root");
        return NativeCompiler.execute(product, scratch, Map.of());
    }

    /**
     * The function-value admission: a code pointer is never published under a
     * managed {@code deal_data} tag (C3/R2 — a DEAL function value is the
     * managed {@code deal_fn*} of C5, and the {@code deal_data} reference is
     * "the managed payload for the managed tags and always NULL otherwise"),
     * and an unrendered admission is one origin-bearing E6005 with nothing
     * published (C9).
     */
    private static void testFunctionValueNeverBoxed(Path scratch) throws Exception {
        System.out.println("-- a code pointer never becomes a managed datum --");
        Map<String, String> rejections = new LinkedHashMap<>();
        rejections.put("return", FUNCTION_BOXED_RETURN_SOURCE);
        rejections.put("declaration", FUNCTION_BOXED_DECLARATION_SOURCE);
        rejections.put("nullish", FUNCTION_BOXED_NULLISH_SOURCE);
        for (Map.Entry<String, String> fixture : rejections.entrySet()) {
            Path root = scratch.resolve("function-box-" + fixture.getKey());
            writeProject(root, fixture.getValue());
            CompileOutcome outcome = compile(root);
            check(!outcome.success(), "the " + fixture.getKey()
                + " of a function value into `unknown` fails closed");
            check(!outcome.diagnostics().isEmpty(), "the " + fixture.getKey()
                + " rejection carries a diagnostic");
            if (!outcome.diagnostics().isEmpty()) {
                CompilerDiagnostic diagnostic = outcome.diagnostics().get(0);
                checkEq("E6005", diagnostic.code(), "the " + fixture.getKey()
                    + " rejection is one E6005");
                check(diagnostic.range() != null
                        && diagnostic.range().startLine() >= 1
                        && diagnostic.range().startColumn() >= 1,
                    "the " + fixture.getKey() + " rejection carries the"
                        + " offending op's one-based origin, not a synthetic"
                        + " 1:1");
                check(String.valueOf(diagnostic.message()).contains("does not"
                        + " prove the declared 'unknown'"),
                    "the " + fixture.getKey() + " rejection names the unimplemented"
                        + " admission: " + diagnostic.message());
            }
            check(!Files.exists(root.resolve("build/llvm")),
                "the " + fixture.getKey() + " rejection publishes nothing");
        }
        Path root = scratch.resolve("function-typeof");
        writeProject(root, FUNCTION_TYPEOF_SOURCE);
        CompileOutcome outcome = compile(root);
        check(outcome.success(), "the `typeof` projection of a function value"
            + " still compiles: " + outcome.diagnostics());
        if (!outcome.success()) {
            return;
        }
        Path published = root.resolve("build/llvm");
        String module = Files.readString(published.resolve("main.ll"),
            StandardCharsets.UTF_8);
        int functionDatum = LlvmTypeMapper.DATA_FUNCTION;
        Matcher datums = Pattern.compile(
            "\\{ i32 " + functionDatum + ", i32 0, double [^,]+, i8\\* ([^}]+?)\\}")
            .matcher(module);
        int tagged = 0;
        while (datums.find()) {
            tagged++;
            checkEq("null", datums.group(1).trim(), "the function-tagged"
                + " datum carries no code pointer in its managed reference");
        }
        check(tagged > 0, "the `typeof` projection builds the function-tagged"
            + " datum: " + tagged);
        check(module.contains("@deal_descriptor_type_name("),
            "the `typeof` projection reads the tag through the one runtime entry");
        Matcher functionSlot = Pattern.compile(
            "store i8\\* %load\\d+, i8\\*\\* @deal\\.slot\\.\\d+\\.0")
            .matcher(module);
        check(functionSlot.find(), "the module-scope function binding keeps its"
            + " own code-pointer slot and does not inherit the `unknown`"
            + " datum of the neighbouring binding");
        check(module.contains("store %struct.deal_data %"),
            "the `unknown` binding of the same fixture stores its own datum");
        for (String function : List.of("seven", "main")) {
            String body = functionOf(module, function);
            check(!Pattern.compile("insertvalue %struct.deal_data \\{ i32 "
                    + functionDatum).matcher(body).find(),
                "the " + function + " activation never builds a function datum"
                    + " over a code pointer");
        }
        check(!Pattern.compile("insertvalue %struct\\.deal_data \\{ i32 "
                + functionDatum + ", i32 0, double 0\\.0, i8\\* null \\}, i8\\* ")
                .matcher(module).find(),
            "no emitted instruction inserts a value into the function datum's"
                + " managed reference");
        testValidityAtBothLevels(published);
    }

    /**
     * Value-carrying activations whose every recorded path returns or throws:
     * the join after the structure op has no predecessor, so there is no
     * fall-through exit to render and the shape compiles, links and runs at
     * both levels.
     */
    private static void testTerminatingTails(Path scratch) throws Exception {
        System.out.println("-- a fully terminating tail renders no fall-through --");
        Path root = scratch.resolve("terminating");
        writeProject(root, TERMINATING_TAIL_SOURCE);
        CompileOutcome outcome = compile(root);
        check(outcome.success(), "the terminating-tail fixture compiles: "
            + outcome.diagnostics());
        if (!outcome.success()) {
            return;
        }
        Path published = root.resolve("build/llvm");
        String module = Files.readString(published.resolve("main.ll"),
            StandardCharsets.UTF_8);
        for (String name : List.of("fromTry", "fromIf", "fromLoop", "fromRethrow")) {
            String function = functionOf(module, name);
            check(!function.contains(
                    "ret %struct.deal_outcome_number { i8* null }"),
                "the " + name + " activation emits no valueless fall-through"
                    + " return for its value result");
            check(!function.contains("call void @deal_root_frame_leave()\n  call"
                    + " void @deal_call_frame_pop()\n  ret %struct.deal_outcome"
                    + " { i8* null }"),
                "the " + name + " activation emits no unresulted epilogue");
        }
        check(module.contains("unreachable"),
            "the predecessor-less join is rendered as an unreachable terminator");
        testValidityAtBothLevels(published);
        for (int level : new int[] {0, 2}) {
            NativeCompiler.Result run = runProduct(scratch, published, level,
                "terminating");
            checkEq(0, run.exitCode(), "-O" + level + " the terminating-tail"
                + " product runs: " + run.describe());
            checkEq("", run.stdout(), "-O" + level + " the terminating-tail"
                + " product prints no terminal output");
        }
    }

    // =========================================================================
    // The compile path and the fixtures
    // =========================================================================

    private record CompileOutcome(boolean success,
                                  List<CompilerDiagnostic> diagnostics,
                                  String stdout, String stderr) {
    }

    private static Path compileProject(Path scratch, String name, String source)
            throws Exception {
        Path root = scratch.resolve(name);
        writeProject(root, source);
        CompileOutcome outcome = compile(root);
        if (!outcome.success()) {
            throw new IllegalStateException("the fixture '" + name
                + "' failed to compile: " + outcome.diagnostics());
        }
        return root.resolve("build/llvm");
    }

    private static void writeProject(Path root, String source) throws IOException {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("deal.json"),
            "{\"languageVersion\": \"1.5\", \"moduleRoots\": [\"src\"],"
                + " \"backend\": \"llvm\"}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/main.deal"), source,
            StandardCharsets.UTF_8);
    }

    private static CompileOutcome compile(Path root) throws IOException {
        Orc orc = new Orc();
        boolean success = orc.run(root.resolve("src/main.deal"),
            root.resolve("build/llvm"));
        return new CompileOutcome(success, orc.diagnostics, orc.stdout,
            orc.stderr);
    }

    private static ExecutableLoweredProject producedProject(Path entry) {
        try {
            Orc orc = new Orc();
            orc.run(entry, entry.getParent().getParent().resolve("build/llvm"));
            return orc.project;
        } catch (IOException exception) {
            return null;
        }
    }

    /** One production compile through the CLI's own resolution and arm. */
    private static final class Orc {
        private final List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        private ExecutableLoweredProject project;
        private String stdout = "";
        private String stderr = "";

        private boolean run(Path entry, Path output) throws IOException {
            ProjectLocator.LocateResult located = ProjectLocator.locate(
                entry.toAbsolutePath().toString(),
                new CliOverrides(null, output.toAbsolutePath().toString()));
            if (located.context() == null) {
                stderr = String.valueOf(located.cliDiagnostic());
                return false;
            }
            ProjectContext context = located.context();
            CompilerInvocation invocation = CompilerProfileProvider.resolve(
                ReleaseConfiguration.CURRENT_RELEASE_STATE,
                ReleaseConfiguration.releaseCapabilityRegistry());
            CompilationOrchestrator orchestrator = new CompilationOrchestrator(
                context, entry.toAbsolutePath().normalize(), false, false,
                false, false, null, invocation);
            PrintStream out = System.out;
            PrintStream err = System.err;
            ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
            ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
            boolean success;
            try {
                System.setOut(new PrintStream(capturedOut, true,
                    StandardCharsets.UTF_8));
                System.setErr(new PrintStream(capturedErr, true,
                    StandardCharsets.UTF_8));
                success = orchestrator.compile();
            } finally {
                System.setOut(out);
                System.setErr(err);
            }
            diagnostics.addAll(orchestrator.diagnostics());
            project = orchestrator.producedProject();
            stdout = capturedOut.toString(StandardCharsets.UTF_8);
            stderr = capturedErr.toString(StandardCharsets.UTF_8);
            return success;
        }
    }

    // =========================================================================
    // Text helpers
    // =========================================================================

    /** The exact {@code define} block of the function of one source name. */
    private static String functionOf(String module, String sourceName) {
        Matcher plain = Pattern.compile(Pattern.quote("#" + sourceName
            + "@F") + "(\\d+)").matcher(module);
        String id = null;
        if (plain.find()) {
            id = plain.group(1);
        }
        if (id == null) {
            throw new IllegalStateException("the module carries no frame label"
                + " for the source function " + sourceName);
        }
        String name = "F" + id;
        Matcher definition = Pattern.compile("(?ms)^define [^\\n]* @" + name
            + "\\(.*?^\\}$").matcher(module);
        if (!definition.find()) {
            throw new IllegalStateException("the module defines no function "
                + name + " for the source function " + sourceName);
        }
        return definition.group();
    }

    private static int count(String text, String needle) {
        int total = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            total++;
            index = text.indexOf(needle, index + needle.length());
        }
        return total;
    }

    private static void check(boolean condition, String description) {
        if (condition) {
            passed++;
            return;
        }
        failed++;
        System.out.println("FAIL: " + description);
    }

    private static void checkEq(Object expected, Object actual,
                                String description) {
        if (expected == null ? actual == null : expected.equals(actual)) {
            passed++;
            return;
        }
        failed++;
        System.out.println("FAIL: " + description + " (expected " + expected
            + ", got " + actual + ")");
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(java.util.Comparator.reverseOrder())
                .toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }
}
