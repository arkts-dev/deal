package deal.test;

import deal.codegen.llvm.LlvmRuntimeAbi;
import deal.codegen.llvm.LlvmTypeMapper;
import deal.diagnostics.CompilerDiagnostic;
import deal.module.CompilationOrchestrator;
import deal.project.CliOverrides;
import deal.project.ProjectContext;
import deal.project.ProjectLocator;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The call, closure, dynamic-dispatch, module, stdlib and async lowering of the
 * native backend ({@code llvm-native-codegen-cfg-ssa} C2/C4/C5/C6/C7/C9,
 * {@code llvm-native-runtime-value-model} R7/R8, task "calls, closures,
 * dynamic dispatch, modules, stdlib calls and async lowering").
 *
 * <p>Every case compiles a real project through the production resolution and
 * emission path and inspects the <em>published</em> module of that compile,
 * then builds and runs the published product through the pinned toolchain
 * seam:</p>
 *
 * <ol>
 *   <li>a captured or reassigned binding is one heap cell (C5): the module
 *       allocates it, reads and writes it through the tag-aware cell entries,
 *       roots it, and a closure captures exactly the cells of its lowered
 *       capture list; one generation owns exactly one allocation, so a
 *       captured or reassigned parameter's generation 0 cell is initialized
 *       from the incoming argument by the setup segment and its own
 *       {@code BINDING_ALLOC} allocates no second cell;</li>
 *   <li>every managed temporary live across an allocating call owns a root
 *       slot (M4), including the adapter's value-source cell across
 *       {@code deal_fn_create};</li>
 *   <li>a function value is the managed {@code deal_fn*} carrier with its
 *       boxed apply thunk; a dynamically dispatched call validates the carried
 *       signature atom at the recorded call signature (the one E8010 arm) and
 *       invokes the thunk with the boxed argument presentations;</li>
 *   <li>a function value crossing a function-typed, {@code unknown} or
 *       function-based nullish position carries its interned signature text
 *       into the check input and is checked at the boundary kind its
 *       descriptor belongs to, so the runtime classifies it as a function
 *       instead of faulting ({@code llvm-native-runtime-value-model} R4);</li>
 *   <li>an arity-extension adapter is one closure over the recorded source
 *       cell whose thunk validates the source signature and re-invokes it with
 *       the target's leading parameters; source expressions evaluate once at
 *       creation, and genuine closure bindings remain live;</li>
 *   <li>every member of a recursive group is a cell whose declared descriptor
 *       is the descriptor of the function the group publishes into it, so the
 *       group initializes before any call and each member is callable;</li>
 *   <li>a checked {@code as T} assertion to a function or nullish descriptor
 *       renders the one descriptor-driven check at that descriptor's own
 *       boundary kind, keeps the extracted value's identity and reports its
 *       E8010/E8001 arm at the assertion's own origin;</li>
 *   <li>a unit's initialization is once-only with a sticky carried failure,
 *       imports its dependencies depth-first in imported order, and publishes
 *       its export records only after its own initialization succeeded;</li>
 *   <li>every stdlib operation is the one runtime dispatch entry with the
 *       parameter and return crossings, each operand is boxed at its own
 *       static type, and the executed operations agree with the shared
 *       semantics at both optimization levels;</li>
 *   <li>an async project publishes valid IR that references the C7 operation
 *       and await entries and fails its native link with the seam's pinned
 *       diagnostic until the async runtime group exists;</li>
 *   <li>the synthetic bodies of the thunks declare every runtime entry they
 *       emit, so a project whose only nullish or adapter fact is a thunk body
 *       still publishes a module {@code llvm-as} accepts (C9);</li>
 *   <li>every synthesized dispatch shim contributes no Error frame of its
 *       own: the adapter thunk pops its label before it applies the retained
 *       source, and an adapted call reports
 *       exactly the frames of the DEAL activations that raised and dispatched
 *       it — with the caller's frame and root depth restored after every
 *       invocation, a failed one included;</li>
 *   <li>two compiles are byte-identical, {@code llvm-as} and the pinned
 *       {@code clang} accept the module at {@code -O0} and {@code -O2}, and
 *       the non-host synchronous products build and run at both levels, also
 *       under {@code DEAL_GC_STRESS=1}.</li>
 * </ol>
 */
public final class LlvmCallClosureEmissionTest {

    private static final Path REPO = NativeCompiler.repository();

    /** Closures, cells, recursion, a dynamic call and an adapter. */
    private static final String CALLS_SOURCE = """
        function counter(start: number): () => number {
          let n: number = start;
          return function(): number { n = n + 1; return n; };
        }
        function one(a: number): number { return a; }
        function use(f: (a: number, b: number) => number): number {
          return f(1, 2);
        }
        export function main(): null {
          let c: () => number = counter(1);
          let first: number = c();
          let second: number = c();
          let g: (a: number, b: number) => number = one;
          let adapted: number = use(g);
          function count(n: number): boolean {
            if (n === 0) { return true; }
            return count(n - 1);
          }
          if (first === 2 && second === 3 && adapted === 1 && count(4)) {
            let observed: string = (first as unknown) as string;
            return null;
          }
          return null;
        }
        """;

    /** Stdlib operations over numbers (the staged scalar surface). */
    private static final String STDLIB_SOURCE = """
        import * as math from "std/math";
        import * as time from "std/time";
        export function main(): null {
          let a: number = math.floor(2.9);
          let b: number = math.ceil(1.1);
          let c: number = math.absNumber(0 - 4);
          let d: number = math.sqrt(9);
          let now: number = time.nowMillis();
          if (a === 2 && b === 2 && c === 4 && d === 3 && now > 0) {
            let observed: string = (a as unknown) as string;
            return null;
          }
          return null;
        }
        """;

    /**
     * The pinned IEEE {@code std/math.sqrt} semantics: a negative input yields
     * NaN (the specification's v1.5 rule), so the executed {@code !==} test is
     * true and the fixture reaches its E8001 arm.
     */
    private static final String SQRT_SOURCE = """
        import * as math from "std/math";
        export function main(): null {
          let value: number = math.sqrt(0 - 1);
          let root: number = math.sqrt(4);
          if (value !== value && root === 2) {
            let observed: string = (value as unknown) as string;
            return null;
          }
          return null;
        }
        """;

    /** An async fact: the C7 lowering publishes IR, the runtime is pending. */
    private static final String ASYNC_SOURCE = """
        async function work(n: number): Promise<number> { return n + 1; }
        async function drive(n: number): Promise<null> {
          let value: number = await work(n);
          if (value === 2) { return null; }
          return null;
        }
        export function main(): null {
          return null;
        }
        """;

    private static final String LIB_SOURCE = """
        export function double(n: number): number { return n + n; }
        export function isTwo(n: number): boolean { return n === 2; }
        """;

    /**
     * A function value crossing a dynamic call's function-typed and
     * {@code unknown} parameter positions, reached through the boxed apply
     * thunk: the thunk's check input of a function datum carries the carrier's
     * signature text and a function-typed position is checked at the
     * {@code FUNCTION_SIGNATURE} boundary.
     */
    private static final String FUNCTION_PARAMETER_SOURCE = """
        function one(n: number): number { return n; }
        function apply1(f: (n: number) => number): number { return f(1); }
        function callFunction(g: (v: (n: number) => number) => number): number {
          return g(one);
        }
        function callUnknown(g: (v: unknown) => number,
                             f: (n: number) => number): number {
          return g(f);
        }
        export function main(): null {
          function passthrough(value: unknown): number { return 7; }
          let applied: number = callFunction(apply1);
          let g: (v: unknown) => number = passthrough;
          let carried: number = callUnknown(g, one);
          if (applied === 1 && carried === 7) {
            let observed: string = (applied as unknown) as string;
            return null;
          }
          return null;
        }
        """;

    /**
     * A function value crossing the {@code unknown} surface: the checked
     * boundary classifies the datum as a function through its carried
     * signature instead of faulting, so the recorded E8001 arm renders.
     */
    private static final String FUNCTION_UNKNOWN_SOURCE = """
        function one(n: number): number { return n; }
        export function main(): null {
          let a: (n: number) => number = one;
          let boxed: unknown = a;
          let back: string = boxed as string;
          return null;
        }
        """;

    /**
     * A nullish position whose base is a function type: the carried signature
     * of the {@code null} arm is absent without dereferencing the singleton as
     * a carrier, so the typed boundary renders the datum's own tag.
     */
    private static final String NULLISH_FUNCTION_SOURCE = """
        function one(n: number): number { return n; }
        export function main(): null {
          let empty: ((n: number) => number) | null = null;
          let boxed: unknown = empty;
          let back: string = boxed as string;
          return null;
        }
        """;

    /**
     * Stdlib arguments whose own static type is not the declared parameter
     * descriptor: every operand is boxed at its own static type and the
     * runtime's {@code STDLIB_PARAMETER} crossing validates the presentation
     * against the declared {@code unknown} parameter.
     */
    private static final String STDLIB_BOXING_SOURCE = """
        import * as json from "std/json";
        function one(n: number): number { return n; }
        export function main(): null {
          let numberText: string | undefined = json.stringify(1);
          let booleanText: string | undefined = json.stringify(true);
          let nullText: string | undefined = json.stringify(null);
          let functionText: string | undefined = json.stringify(one);
          let value: number = 3;
          let observed: string = (value as unknown) as string;
          return null;
        }
        """;

    /**
     * Closures over parameters and a reassigned parameter: the parameter's
     * generation 0 cell is published once, from the incoming argument, and the
     * closure, the alias and the body all observe that one cell (C5).
     */
    private static final String PARAMETER_CELL_SOURCE = """
        function capture(n: number): () => number {
          return function(): number { return n; };
        }
        function reassign(n: number): () => number {
          n = n + 1;
          return function(): number { return n; };
        }
        function alias(n: number): number {
          let read: () => number = function(): number { return n; };
          n = n + 2;
          return read() + n;
        }
        export function main(): null {
          let captured: number = capture(7)();
          let bumped: number = reassign(41)();
          let shared: number = alias(1);
          if (captured === 7 && bumped === 42 && shared === 6) {
            let observed: string = (captured as unknown) as string;
            return null;
          }
          return null;
        }
        """;

    /**
     * An assignment expression whose adapter is taken over the RHS value: the
     * adapter's own source cell is live across {@code deal_fn_create}'s
     * safepoint, so it owns a root slot and survives a full collection, and the
     * stored adapter keeps the target arity while the expression keeps the
     * RHS's own value (C5).
     */
    private static final String ADAPTER_VALUE_SOURCE = """
        function one(n: number): number { return n; }
        function use2(f: (a: number, b: number) => number): number {
          return f(1, 2);
        }
        export function main(): null {
          let h: (a: number, b: number) => number = one;
          let y: (n: number) => number = (h = one);
          let stored: number = use2(h);
          let direct: number = y(5);
          if (stored === 1 && direct === 5) {
            let observed: string = (stored as unknown) as string;
            return null;
          }
          return null;
        }
        """;

    private static final String APP_SOURCE = """
        import * as util from "./lib";
        export function main(): null {
          let direct: boolean = util.isTwo(util.double(1));
          let fn: (n: number) => number = util.double;
          let dynamic: number = fn(1);
          if (direct && dynamic === 2) {
            let observed: string = (dynamic as unknown) as string;
            return null;
          }
          return null;
        }
        """;

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("=== LLVM Call / Closure / Module / Stdlib / Async Tests"
            + " (ISSUE-0911) ===\n");
        Path scratch = NativeCompiler.executableScratch("llvm-calls");
        try {
            testCallsAndClosures(scratch);
            testParameterCells(scratch);
            testIterationCells(scratch);
            testAdapterValueSource(scratch);
            testFunctionParameters(scratch);
            testModules(scratch);
            testStdlib(scratch);
            testContainerProjections(scratch);
            testVoidResults(scratch);
            testAsyncLowering(scratch);
            testRecursiveGroups(scratch);
            testRetainedValueAdapters(scratch);
            testCheckedAssertions(scratch);
            testSyntheticBodyDeclarations(scratch);
            testAdapterFrames(scratch);
            testDeterminism(scratch);
        } finally {
            deleteRecursively(scratch);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== LLVM Call / Closure / Module / Stdlib / Async Tests"
            + " Passed ===");
    }

    // =========================================================================
    // 1. Calls, closures, cells, dispatch and adapters
    // =========================================================================

    private static void testCallsAndClosures(Path scratch) throws Exception {
        System.out.println("-- closures, cells, dynamic dispatch and adapters --");
        Path published = compileProject(scratch, "calls", CALLS_SOURCE);
        String module = Files.readString(published.resolve("main.ll"),
            StandardCharsets.UTF_8);
        // The captured binding is one heap cell (C5).
        check(module.contains("call i8* @deal_cell_alloc_data("),
            "a captured binding is one heap cell");
        check(module.contains("@deal_cell_read_data("),
            "a captured binding load reads through the cell");
        check(module.contains("call void @deal_cell_store_data(i8* "),
            "a captured binding store runs the cell barrier");
        check(module.contains("call i8* @deal_fn_capture(%struct.deal_fn* %env"),
            "a closure body reads its own capture list from the carrier");
        check(module.contains("call %struct.deal_fn* @deal_fn_create("),
            "a closure is the managed deal_fn carrier of C5");
        check(!Pattern.compile("bitcast \\([^)]*\\) @F\\d+ to i8\\*")
                .matcher(module).find(),
            "no emitted function value is a raw code pointer");
        // The dynamically dispatched call validates the signature atom and
        // invokes the boxed apply thunk (C5).
        check(module.contains("@deal_fn_signature("),
            "a dynamic call reads the closure's signature atom");
        check(Pattern.compile("@deal_check_value\\([^\\n]*, i32 "
                + LlvmRuntimeAbi.BOUNDARY_FUNCTION_SIGNATURE + ", ")
                .matcher(module).find(),
            "a dynamic call validates the signature at the E8010 boundary");
        check(module.contains("@deal_fn_apply("),
            "a dynamic call invokes the boxed apply thunk");
        check(module.contains("insertvalue %struct.deal_check_input"),
            "a dynamic call boxes its arguments into the recorded presentations");
        // The adapter is one closure over the source cell.
        check(module.contains("@A") && module.contains("@deal_fn_create(i8*"
                + " bitcast (%struct.deal_outcome_data (i8*, %struct.deal_check_input*,"
                + " i32, %struct.deal_origin*)* @A"),
            "an arity-extension adapter is one emitted closure");
        check(Pattern.compile("@A\\d+\\(i8\\* %functionvalue").matcher(module).find(),
            "the adapter thunk carries the source function value");
        // Module-level carriers are static roots (M5).
        check(module.contains("@deal_static_root_register("),
            "module-level cells and published exports are static roots");
        // Every generated activation carries its environment and restores the
        // frames on its exit paths.
        Matcher definitions = Pattern.compile("(?m)^define internal [^@]*@F\\d+"
            + "\\(%struct.deal_fn\\* %env").matcher(module);
        check(definitions.find(), "every lowered body receives its environment");
        for (int level : new int[] {0, 2}) {
            runProduct(scratch, published, level, "calls");
        }
    }

    // =========================================================================
    // 2. Parameter cells: one heap cell per parameter generation
    // =========================================================================

    private static void testParameterCells(Path scratch) throws Exception {
        System.out.println("-- one cell per parameter generation --");
        Path published = compileProject(scratch, "parameter-cells",
            PARAMETER_CELL_SOURCE);
        String module = Files.readString(published.resolve("main.ll"),
            StandardCharsets.UTF_8);
        // One activation writes one generation's cell exactly once: the setup
        // segment publishes the parameter cell from the incoming argument and
        // the body's own BINDING_ALLOC allocates no second cell (C5).
        Pattern cellStore = Pattern.compile(
            "^\\s+store i8\\* [^,]+, i8\\*\\* (%c(\\d+)_(\\d+))$",
            Pattern.MULTILINE);
        int cellSlots = 0;
        for (String chunk : module.split("\\ndefine ")) {
            Map<String, Integer> writes = new LinkedHashMap<>();
            Matcher matcher = cellStore.matcher(chunk);
            while (matcher.find()) {
                writes.merge(matcher.group(1), 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> entry : writes.entrySet()) {
                cellSlots++;
                checkEq(1, entry.getValue(), "the cell slot " + entry.getKey()
                    + " of one activation is written by exactly one allocation");
            }
        }
        check(cellSlots >= 3, "the fixture records one cell slot per captured"
            + " parameter activation: " + cellSlots);
        // A parameter cell is initialized from the argument, never from the
        // undefined datum of a second allocation.
        int parameterActivations = 0;
        for (String chunk : module.split("\\ndefine ")) {
            if (!chunk.contains("%parameter0value")) {
                continue;
            }
            parameterActivations++;
            check(!chunk.contains("@deal_data_of_undefined("),
                "a captured parameter's activation allocates no"
                    + " undefined-initialized cell");
        }
        checkEq(3, parameterActivations, "every captured parameter activation"
            + " is inspected");
        for (int level : new int[] {0, 2}) {
            // The closure over the parameter reads 7, the reassigned parameter
            // reads 42 and the alias observes the one shared cell, so the
            // fixture reaches its E8001 arm only when every cell is initialized
            // from the argument.
            runProduct(scratch, published, level, "parameter-cells");
        }
    }

    /**
     * Closures over a for-of iteration binding: each iteration owns a fresh
     * heap cell (C5, docs/spec-v1.5.md "For a for-of loop, each iteration
     * creates a fresh iteration binding for variables captured by closures").
     *
     * <p>The executed fixture discriminates the two behaviors exactly: the
     * closure created in the first iteration is re-invoked in the later ones,
     * so a shared binding would read the later element and yield 60, and the
     * fresh per-iteration cell yields 30.</p>
     */
    private static final String ITERATION_CELL_SOURCE = """
        function build(): number {
          let saved: () => number = function(): number { return 0; };
          let total: number = 0;
          for (let i of [10, 20, 30]) {
            if (total === 0) {
              saved = function(): number { return i; };
            }
            total = total + saved();
          }
          return total;
        }
        function counted(): number {
          let mixed: (number | null)[] = [7, null];
          let sum: number = 0;
          for (let item of mixed) {
            if (item === null) {
              sum = sum + 100;
            } else {
              sum = sum + item;
            }
          }
          return sum;
        }
        export function main(): null {
          let total: number = build();
          let sum: number = counted();
          if (total === 30 && sum === 107) {
            let observed: string = (total as unknown) as string;
            return null;
          }
          return null;
        }
        """;

    /**
     * A data-valued iteration binding (an {@code unknown} element), so the
     * iteration cell stores a managed datum and the element is live across the
     * cell's allocating call.
     */
    private static final String ITERATION_DATA_CELL_SOURCE = """
        function build(): unknown[] {
          let out: unknown[] = [];
          for (let item of [[1, 2], [3]]) {
            out[out.length] = function(): number { return item.length; };
          }
          return out;
        }
        function total(): number { return 4; }
        export function main(): null {
          let values: unknown[] = build();
          let count: number = total();
          if (values.length === 2 && count === 4) {
            let observed: string = (count as unknown) as string;
            return null;
          }
          return null;
        }
        """;

    private static void testIterationCells(Path scratch) throws Exception {
        System.out.println("-- one fresh cell per for-of iteration --");
        Path published = compileProject(scratch, "iteration-cells",
            ITERATION_CELL_SOURCE);
        String module = Files.readString(published.resolve("main.ll"),
            StandardCharsets.UTF_8);
        // The iteration binding is a cell: every iteration allocates its own
        // datum cell inside the loop's check segment and roots it (C5, M4).
        check(module.contains("call i8* @deal_cell_alloc_data("),
            "a for-of iteration binding is one heap cell");
        check(module.contains("@deal_cell_read_data("),
            "a captured iteration binding is read through the cell");
        boolean perIteration = false;
        for (String chunk : module.split("\\ndefine ")) {
            int element = chunk.indexOf("@deal_container_array_element(");
            int allocated = chunk.indexOf("@deal_cell_alloc_data(");
            if (element >= 0 && allocated > element) {
                perIteration = true;
            }
        }
        check(perIteration, "the iteration cell is allocated inside the loop's"
            + " check segment, so every iteration owns a fresh one");
        // The nullish element (and the narrowed read of it) project the cell's
        // datum onto the declared nullish representation (C3, R4).
        check(module.contains("%struct.deal_nullish_number"),
            "a nullish iteration element keeps its tagged representation");
        NativeCompiler.Result assembled = NativeCompiler.run(
            List.of("llvm-as", published.resolve("main.ll").toString()),
            scratch);
        checkEq(0, assembled.exitCode(),
            "the iteration-cell module is valid: " + assembled.describe());
        for (int level : new int[] {0, 2}) {
            // The fixture reaches its E8001 arm only when the closure created
            // in the first iteration keeps reading that iteration's own cell.
            runProduct(scratch, published, level, "iteration-cells");
        }
        // A managed element: the iteration datum is live across the cell's
        // allocating call and owns a root slot.
        Path data = compileProject(scratch, "iteration-data-cells",
            ITERATION_DATA_CELL_SOURCE);
        String dataModule = Files.readString(data.resolve("main.ll"),
            StandardCharsets.UTF_8);
        check(dataModule.contains("call void @deal_root_frame_write_data("),
            "a managed iteration element is rooted across the cell allocation");
        for (int level : new int[] {0, 2}) {
            runProduct(scratch, data, level, "iteration-data-cells");
        }
    }

    // =========================================================================
    // 3. The adapter's value-source cell is rooted across deal_fn_create
    // =========================================================================

    private static void testAdapterValueSource(Path scratch) throws Exception {
        System.out.println("-- the adapter's value-source cell is rooted --");
        Path published = compileProject(scratch, "adapter-value",
            ADAPTER_VALUE_SOURCE);
        String module = Files.readString(published.resolve("main.ll"),
            StandardCharsets.UTF_8);
        int datum = module.indexOf("%adapterdatum");
        check(datum >= 0, "the adapter's value source has its own datum");
        int allocated = datum < 0 ? -1
            : module.indexOf("@deal_cell_alloc_data(", datum);
        int created = allocated < 0 ? -1
            : module.indexOf("@deal_fn_create(", allocated);
        check(allocated > datum && created > allocated,
            "the adapter's cell is allocated before its closure");
        String between = allocated < 0 || created < 0 ? ""
            : module.substring(allocated, created);
        // The cell is a managed temporary live across an allocating call (M4).
        check(between.contains("%scratchcell")
                && between.contains("@deal_root_frame_write("),
            "the adapter's cell owns a root slot across deal_fn_create");
        for (int level : new int[] {0, 2}) {
            // The stored adapter extends the arity while the assignment
            // expression keeps the RHS's own value; under DEAL_GC_STRESS the
            // invoked adapter reads its own cell, not a reclaimed one.
            runProduct(scratch, published, level, "adapter-value");
        }
    }

    // =========================================================================
    // 4. Function-typed boundaries at the boxed apply thunk and the unknown surface
    // =========================================================================

    private static void testFunctionParameters(Path scratch) throws Exception {
        System.out.println("-- function-typed and unknown boundaries of a"
            + " function value --");
        Path published = compileProject(scratch, "function-parameters",
            FUNCTION_PARAMETER_SOURCE);
        String module = Files.readString(published.resolve("main.ll"),
            StandardCharsets.UTF_8);
        // The thunk's check input of a function-typed parameter carries the
        // carrier's signature text and checks it at FUNCTION_SIGNATURE.
        Pattern signatureCheck = Pattern.compile("@deal_check_value\\([^\\n]*?, i32 "
            + LlvmRuntimeAbi.BOUNDARY_FUNCTION_SIGNATURE + ", ");
        boolean thunkSignature = false;
        for (String chunk : module.split("\\ndefine ")) {
            if (!chunk.startsWith("internal %struct.deal_outcome_data @T")) {
                continue;
            }
            if (signatureCheck.matcher(chunk).find()) {
                thunkSignature = true;
            }
        }
        check(thunkSignature,
            "a thunk checks a function-typed parameter at the signature boundary");
        // The argument presentation of a function value carries the carrier's
        // signature text in the check input's own field (R4).
        check(Pattern.compile("insertvalue %struct\\.deal_check_input "
                + "[^,]+, i8\\* [^,]+, 4")
                .matcher(module).find(),
            "a function presentation carries its signature into the check input");
        check(module.contains("call i8* @deal_fn_signature(%struct.deal_fn* "),
            "the carried signature text is the carrier's own interned text");
        for (int level : new int[] {0, 2}) {
            runProduct(scratch, published, level, "function-parameters");
        }
        // A function value presented to an unknown position renders the typed
        // boundary's own arm instead of faulting on a missing signature.
        Path unknown = compileProject(scratch, "function-unknown",
            FUNCTION_UNKNOWN_SOURCE);
        for (int level : new int[] {0, 2}) {
            NativeCompiler.Result run = runProduct(scratch, unknown, level,
                "function-unknown", true);
            checkEq(1, run.exitCode(), "-O" + level + " the function value"
                + " reaches its typed boundary: " + run.describe());
            check(run.stdout().contains("DEAL_ERROR_CODE: E8001"),
                "-O" + level + " the function value renders the E8001 arm: "
                    + run.describe());
            check(run.stdout().contains("\"actual\":\"function\""),
                "-O" + level + " the arm's actual kind is the function datum: "
                    + run.describe());
        }
        // The nullish base's null arm is never dereferenced as a carrier.
        Path nullish = compileProject(scratch, "nullish-function",
            NULLISH_FUNCTION_SOURCE);
        for (int level : new int[] {0, 2}) {
            NativeCompiler.Result run = runProduct(scratch, nullish, level,
                "nullish-function", true);
            checkEq(1, run.exitCode(), "-O" + level + " the nullish function"
                + " value reaches its typed boundary: " + run.describe());
            check(run.stdout().contains("\"actual\":\"null\""),
                "-O" + level + " the null arm renders its own tag: "
                    + run.describe());
        }
    }

    // =========================================================================
    // 5. Modules: depth-first once-only init and export publication
    // =========================================================================

    private static void testModules(Path scratch) throws Exception {
        System.out.println("-- module initialization, exports and imports --");
        Path root = scratch.resolve("modules");
        writeProject(root, Map.of("src/main.deal", APP_SOURCE,
            "src/lib.deal", LIB_SOURCE));
        CompileOutcome outcome = compile(root.resolve("src/main.deal"),
            root.resolve("build/llvm"));
        check(outcome.success(), "the multi-module project compiles: "
            + outcome.diagnostics());
        if (!outcome.success()) {
            return;
        }
        Path published = root.resolve("build/llvm");
        String module = Files.readString(published.resolve("main.ll"),
            StandardCharsets.UTF_8);
        // One init per unit, each depth-first in imported order, once only.
        int inits = count(module, "define internal %struct.deal_outcome"
            + " @deal_module_init_");
        checkEq(2, inits, "the project defines one initialization per unit");
        String mainInit = functionText(module, "deal_module_init_"
            + (inits == 2 ? moduleIndex(module, "lib", "main") : "0"));
        check(mainInit.contains("call %struct.deal_outcome @deal_module_init_"),
            "a unit initializes its import before its own user code");
        check(module.contains("store i32 1, i32*") && module.contains("store i32 2, i32*"),
            "the initialization state machine records initializing and ready");
        check(module.contains("store i32 3, i32*"),
            "a failed initialization records its sticky failed state");
        check(module.contains("getelementptr %struct.deal_module,"
                + " %struct.deal_module* @deal.module."),
            "the sticky failure is stored in the module's own record");
        check(module.contains("store %struct.deal_fn* "),
            "the unit publishes the function value into its export record");
        check(module.contains("@deal_export_lookup("),
            "an imported function value is read through the emitted export table");
        // The imported unit's body receives the environment of its own cell.
        check(module.contains("call %struct.deal_fn* @deal_fn_create("),
            "an imported module-scope function is one closure carrier");
        for (int level : new int[] {0, 2}) {
            runProduct(scratch, published, level, "modules");
        }
    }

    // =========================================================================
    // 6. Stdlib
    // =========================================================================

    private static void testStdlib(Path scratch) throws Exception {
        System.out.println("-- stdlib dispatch and its parameter/return crossings --");
        Path published = compileProject(scratch, "stdlib", STDLIB_SOURCE);
        String module = Files.readString(published.resolve("main.ll"),
            StandardCharsets.UTF_8);
        check(module.contains("@deal_std_call("),
            "every stdlib operation reaches the one dispatch entry");
        for (int level : new int[] {0, 2}) {
            runProduct(scratch, published, level, "stdlib");
        }
        Path sqrt = compileProject(scratch, "sqrt", SQRT_SOURCE);
        for (int level : new int[] {0, 2}) {
            // The fixture reaches its E8001 arm only when the negative operand
            // yielded NaN and the positive one yielded its root.
            runProduct(scratch, sqrt, level, "sqrt");
        }
        // Every operand is boxed at its own static type: the recorded operand
        // types are the declared parameter descriptors, and the runtime's
        // STDLIB_PARAMETER crossing validates the presentation against them.
        Path boxing = compileProject(scratch, "stdlib-boxing",
            STDLIB_BOXING_SOURCE);
        String boxingModule = Files.readString(boxing.resolve("main.ll"),
            StandardCharsets.UTF_8);
        check(boxingModule.contains("insertvalue %struct.deal_data"
                + " zeroinitializer, i32 " + LlvmTypeMapper.DATA_NULL + ", 0"),
            "a null argument is boxed at its own static type, not read as a datum");
        check(boxingModule.contains("insertvalue %struct.deal_data"
                + " zeroinitializer, i32 " + LlvmTypeMapper.DATA_NUMBER + ", 0")
                && Pattern.compile("insertvalue %struct\\.deal_data [^,]+,"
                    + " double %load").matcher(boxingModule).find(),
            "a number argument is boxed from its own number representation");
        check(Pattern.compile("insertvalue %struct\\.deal_data [^,]+,"
                + " i8\\* %tmp").matcher(boxingModule).find(),
            "a function argument is boxed with its managed carrier reference");
        for (int level : new int[] {0, 2}) {
            runProduct(scratch, boxing, level, "stdlib-boxing");
        }
    }

    // =========================================================================
    // 6b. Container and class datum projections
    // =========================================================================

    /**
     * A captured array, a captured class instance and a dynamically dispatched
     * call whose result is an array: each crosses the persistent datum surface
     * of C3, so the captured cell read, the apply completion and the stdlib
     * return all project the datum back onto the value's own representation
     * while preserving its identity and descriptor (C3/C5, R7).
     */
    private static final String CONTAINER_PROJECTION_SOURCE = """
        class Box { value: number; }
        function captureArray(): () => number {
          let a: number[] = [4, 5];
          return function(): number { return a[1]; };
        }
        function captureClass(): () => number {
          let b: Box = { value: 9 };
          return function(): number { return b.value; };
        }
        function makeArray(): number[] { return [6, 7]; }
        function dynamicReturnArray(f: () => number[]): number[] { return f(); }
        export function main(): null {
          let indexed: number = captureArray()();
          let field: number = captureClass()();
          let through: number[] = dynamicReturnArray(makeArray);
          if (indexed === 5 && field === 9 && through[0] === 6) {
            let observed: string = (indexed as unknown) as string;
            return null;
          }
          return null;
        }
        """;

    /**
     * The stdlib operations whose return positions are containers: each result
     * reaches the caller as the managed array or record the runtime built,
     * with its persistent element descriptor intact (R2, R7).
     */
    private static final String STDLIB_CONTAINER_SOURCE = """
        import * as strings from "std/string";
        import * as table from "std/table";
        import * as json from "std/json";
        export function main(): null {
          let parts: string[] = strings.split("a,b,c", ",");
          let record: Record<string, unknown> = json.parse("{\\"x\\":1,\\"y\\":2}");
          let names: string[] = table.keys(record);
          if (parts.length === 3 && names.length === 2) {
            let observed: string = (parts.length as unknown) as string;
            return null;
          }
          return null;
        }
        """;

    private static void testContainerProjections(Path scratch) throws Exception {
        System.out.println("-- container and class datum projections --");
        Path published = compileProject(scratch, "container-projections",
            CONTAINER_PROJECTION_SOURCE);
        String module = Files.readString(published.resolve("main.ll"),
            StandardCharsets.UTF_8);
        // A captured container is a cell whose datum the read projects back
        // onto the array pointer, and the projection is the datum's referent.
        check(Pattern.compile("bitcast i8\\* %tmp\\d+ to %struct\\.deal_array\\*")
                .matcher(module).find(),
            "a captured array datum is projected onto its own representation");
        check(Pattern.compile("bitcast i8\\* %tmp\\d+ to %struct\\.deal_class\\.")
                .matcher(module).find(),
            "a captured class datum is projected onto its own layout pointer");
        for (int level : new int[] {0, 2}) {
            runProduct(scratch, published, level, "container-projections");
        }
        Path stdlib = compileProject(scratch, "stdlib-containers",
            STDLIB_CONTAINER_SOURCE);
        String stdlibModule = Files.readString(stdlib.resolve("main.ll"),
            StandardCharsets.UTF_8);
        check(stdlibModule.contains("@deal_std_call("),
            "every container-returning stdlib operation is the one dispatch entry");
        for (int level : new int[] {0, 2}) {
            runProduct(scratch, stdlib, level, "stdlib-containers");
        }
    }

    // =========================================================================
    // 6c. Void results
    // =========================================================================

    /**
     * The result-only {@code void} surface (C3, R7): a sync void body allows a
     * bare return, a dynamically dispatched call of a void function, and the
     * {@code std/console} operations whose results are void. None of them
     * produces a data slot or exposes a void result as source data.
     */
    private static final String VOID_SOURCE = """
        import * as c from "std/console";
        function tick(): void { return; }
        function counter(n: number): void { let v: number = n + 1; }
        export function main(): null {
          c.log("void-log");
          c.error("void-error");
          let f: () => void = tick;
          f();
          let g: (n: number) => void = counter;
          g(1);
          return null;
        }
        """;

    /**
     * A {@code Promise<void>} await: the completion descriptor is result-only
     * {@code void}, so the await projects undefined through the result protocol
     * and never a data value. The program publishes valid IR and reaches the
     * pinned native-link failure until the async runtime group exists.
     */
    private static final String VOID_AWAIT_SOURCE = """
        async function asyncTick(): Promise<void> { return; }
        async function drive(): Promise<null> {
          await asyncTick();
          return null;
        }
        export function main(): null { return null; }
        """;

    private static void testVoidResults(Path scratch) throws Exception {
        System.out.println("-- void results and discards --");
        Path published = compileProject(scratch, "void", VOID_SOURCE);
        String module = Files.readString(published.resolve("main.ll"),
            StandardCharsets.UTF_8);
        check(module.contains("@deal_std_call("),
            "the console operations reach the one stdlib dispatch entry");
        check(module.contains("@deal_fn_apply("),
            "a dynamic void call invokes the boxed apply thunk");
        for (int level : new int[] {0, 2}) {
            Path product = scratch.resolve("products").resolve("void-" + level);
            Files.createDirectories(product.getParent());
            NativeCompiler.build(
                new deal.toolchain.NativeBuildRequest(published, product, level,
                    List.of(), null));
            NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
                Map.of("DEAL_GC_STRESS", "1"));
            checkEq(0, run.exitCode(), "-O" + level
                + " the void fixture terminates successfully: "
                + run.describe());
            check(run.stdout().contains("void-log"),
                "-O" + level + " console.log writes its line: "
                    + run.describe());
            check(run.stderr().contains("void-error"),
                "-O" + level + " console.error writes its line: "
                    + run.describe());
        }
        Path awaited = compileProject(scratch, "void-await", VOID_AWAIT_SOURCE);
        String awaitedModule = Files.readString(awaited.resolve("main.ll"),
            StandardCharsets.UTF_8);
        check(awaitedModule.contains("@deal_async_await("),
            "the Promise<void> await renders the await-origin call");
        // A void completion owns no data slot: the await group extracts only the
        // carried error, never a completion value (C3, C7).
        int completionExtracts = 0;
        for (String chunk : awaitedModule.split("\\ndefine ")) {
            if (chunk.contains("@deal_async_await(")) {
                completionExtracts = count(chunk,
                    "extractvalue %struct.deal_outcome_data");
            }
        }
        checkEq(1, completionExtracts, "the Promise<void> await projects no"
            + " completion value");
        NativeCompiler.Result assembled = NativeCompiler.run(
            List.of("llvm-as", awaited.resolve("main.ll").toString()), scratch);
        checkEq(0, assembled.exitCode(), "the Promise<void> IR is valid: "
            + assembled.describe());
        for (int level : new int[] {0, 2}) {
            Path product = scratch.resolve("products")
                .resolve("void-await-" + level);
            Files.createDirectories(product.getParent());
            boolean failed = false;
            String message = "";
            try {
                NativeCompiler.build(
                    new deal.toolchain.NativeBuildRequest(awaited, product, level,
                        List.of(), null));
            } catch (deal.toolchain.NativeBuildException failure) {
                failed = true;
                message = String.valueOf(failure.getMessage());
            }
            check(failed, "-O" + level + " the Promise<void> project fails its"
                + " native link (the async runtime group is 0913's)");
            check(message.startsWith("deal: native link failed:"),
                "-O" + level + " the staging departure is the seam's pinned"
                    + " diagnostic: " + message);
        }
    }

    // =========================================================================
    // 7. Async lowering
    // =========================================================================

    private static void testAsyncLowering(Path scratch) throws Exception {
        System.out.println("-- async lowering records C7 and fails its link --");
        Path published = compileProject(scratch, "async", ASYNC_SOURCE);
        String module = Files.readString(published.resolve("main.ll"),
            StandardCharsets.UTF_8);
        check(module.contains("@deal_async_start("),
            "ASYNC_START emits the runtime operation-record and token call");
        check(module.contains("@deal_async_await("),
            "AWAIT emits the await-origin completion call");
        check(module.contains("@deal_fn_create("),
            "the async callee is the managed function value");
        check(module.contains("@deal_descriptor_intern("),
            "the completion descriptor reaches the await boundary");
        NativeCompiler.Result assembled = NativeCompiler.run(
            List.of("llvm-as", published.resolve("main.ll").toString()), scratch);
        checkEq(0, assembled.exitCode(),
            "the async project's IR is valid: " + assembled.describe());
        for (int level : new int[] {0, 2}) {
            Path product = scratch.resolve("async-product-" + level);
            boolean failed = false;
            String message = "";
            try {
                NativeCompiler.build(
                    new deal.toolchain.NativeBuildRequest(published, product,
                        level, List.of(), null));
            } catch (deal.toolchain.NativeBuildException failure) {
                failed = true;
                message = String.valueOf(failure.getMessage());
            }
            check(failed, "-O" + level + " the async project fails its native"
                + " link (the async runtime group is 0913's)");
            check(message.startsWith("deal: native link failed:"),
                "-O" + level + " the staging departure is the seam's pinned"
                    + " diagnostic: " + message);
            check(!Files.exists(product),
                "-O" + level + " the failed async build produces no product");
        }
        // A cross-module async callee resolves through the payload's own
        // external async link to the published lowered body, so the call
        // publishes C7 IR instead of failing closed (C7, the module criterion).
        Path root = scratch.resolve("async-cross");
        writeProject(root, Map.of("src/main.deal", CROSS_MODULE_ASYNC_SOURCE,
            "src/lib.deal", ASYNC_LIB_SOURCE));
        CompileOutcome outcome = compile(root.resolve("src/main.deal"),
            root.resolve("build/llvm"));
        check(outcome.success(), "the cross-module async project compiles: "
            + outcome.diagnostics());
        if (outcome.success()) {
            Path cross = root.resolve("build/llvm");
            String crossModule = Files.readString(cross.resolve("main.ll"),
                StandardCharsets.UTF_8);
            check(crossModule.contains("@deal_async_start("),
                "a cross-module ASYNC_START emits the operation-record call");
            check(crossModule.contains("@deal_async_await("),
                "a cross-module AWAIT emits the await-origin call");
            // The carrier handed to the runtime is the callee unit's own
            // lowered body, not a locally synthesized stub.
            Matcher label = Pattern.compile("lib#work@F(\\d+)")
                .matcher(crossModule);
            check(label.find(), "the callee unit's lowered body is emitted");
            String crossFunction = "";
            for (String chunk : crossModule.split("\\ndefine ")) {
                if (chunk.contains("@deal_async_start(")) {
                    crossFunction = chunk;
                }
            }
            check(label.reset().find()
                    && crossFunction.contains("@T" + label.group(1)),
                "the cross-module carrier is the callee's own lowered body");
            NativeCompiler.Result crossAssembled = NativeCompiler.run(
                List.of("llvm-as", cross.resolve("main.ll").toString()),
                scratch);
            checkEq(0, crossAssembled.exitCode(),
                "the cross-module async IR is valid: "
                    + crossAssembled.describe());
            for (int level : new int[] {0, 2}) {
                Path product = scratch.resolve("async-cross-product-" + level);
                boolean failed = false;
                String message = "";
                try {
                    NativeCompiler.build(
                        new deal.toolchain.NativeBuildRequest(cross, product,
                            level, List.of(), null));
                } catch (deal.toolchain.NativeBuildException failure) {
                    failed = true;
                    message = String.valueOf(failure.getMessage());
                }
                check(failed, "-O" + level + " the cross-module async project"
                    + " fails its native link (the async runtime group is"
                    + " 0913's)");
                check(message.startsWith("deal: native link failed:"),
                    "-O" + level + " the staging departure is the seam's"
                        + " pinned diagnostic: " + message);
            }
        }
        // A dynamically checked async callee owns the same E8010 arm as the
        // dynamically dispatched call: the signature atom is validated at the
        // recorded call signature before the operation record is created.
        Path dynamic = compileProject(scratch, "async-dynamic",
            DYNAMIC_ASYNC_SOURCE);
        String dynamicModule = Files.readString(dynamic.resolve("main.ll"),
            StandardCharsets.UTF_8);
        String awaitFunction = "";
        for (String chunk : dynamicModule.split("\ndefine ")) {
            if (chunk.contains("@deal_async_start(")) {
                awaitFunction = chunk;
            }
        }
        check(!awaitFunction.isEmpty(),
            "the dynamically checked async start is emitted");
        int signatureCheck = indexOfPattern(awaitFunction,
            "@deal_check_value\\([^\\n]*, i32 "
                + LlvmRuntimeAbi.BOUNDARY_FUNCTION_SIGNATURE + ", ");
        int started = awaitFunction.indexOf("@deal_async_start(");
        check(signatureCheck >= 0 && signatureCheck < started,
            "a dynamically checked async callee validates its signature atom"
                + " at the FUNCTION_SIGNATURE boundary before the operation"
                + " record is created");
        check(awaitFunction.contains("@deal_fn_signature("),
            "the async E8010 arm reads the carrier's signature atom");
    }

    /**
     * Two modules whose async callee is exported by the imported unit: the
     * call crosses the module boundary through the callee's own lowered body.
     */
    private static final String CROSS_MODULE_ASYNC_SOURCE = """
        import * as lib from "./lib";
        async function drive(n: number): Promise<null> {
          let value: number = await lib.work(n);
          if (value === 2) { return null; }
          return null;
        }
        export function main(): null {
          return null;
        }
        """;

    private static final String ASYNC_LIB_SOURCE = """
        export async function work(n: number): Promise<number> { return n + 1; }
        """;

    /** A dynamically checked async callee: the awaited function is a value. */
    private static final String DYNAMIC_ASYNC_SOURCE = """
        async function drive(f: (n: number) => Promise<number>,
                             n: number): Promise<null> {
          let value: number = await f(n);
          if (value === 2) { return null; }
          return null;
        }
        export function main(): null { return null; }
        """;

    // =========================================================================
    // 8. Recursive groups
    // =========================================================================

    /**
     * The canonical recursive group: two members that each reference the
     * other's binding. The group's own {@code RECURSIVE_GROUP_INIT} publishes
     * every member closure into its cell, so every member binding has the
     * declared type of the function it holds (C5).
     */
    private static final String RECURSIVE_GROUP_SOURCE = """
        function left(): null { let rightRef: () => null = right; return null; }
        function right(): null { let leftRef: () => null = left; return null; }
        export function main(): null { return null; }
        """;

    /**
     * An executed recursive group: both members read the other member's cell
     * and call it, and the membership alternates per call, so a missing or
     * unreadable member type cannot produce the asserted result.
     */
    private static final String RECURSIVE_GROUP_EXECUTED_SOURCE = """
        function left(n: number): number {
          if (n === 0) { return 0; }
          let step: (n: number) => number = right;
          return step(n - 1) + 1;
        }
        function right(n: number): number {
          if (n === 0) { return 0; }
          let step: (n: number) => number = left;
          return step(n - 1) + 1;
        }
        export function main(): null {
          let total: number = left(4);
          let other: number = right(6);
          if (total === 4 && other === 6) {
            let observed: string = (total as unknown) as string;
          }
          return null;
        }
        """;

    private static void testRecursiveGroups(Path scratch) throws Exception {
        System.out.println("-- recursive groups --");
        Path published = compileProject(scratch, "recursive-group",
            RECURSIVE_GROUP_SOURCE);
        String module = Files.readString(published.resolve("main.ll"),
            StandardCharsets.UTF_8);
        check(module.contains("call void @deal_cell_store_data(i8* "),
            "the recursive group publishes each member closure into its cell");
        long allocated = count(module, "call i8* @deal_cell_alloc_data(");
        check(allocated >= 2, "the group allocates one cell per member before"
            + " it publishes any member closure (got " + allocated + ")");
        assertDeclaredAndParsable(scratch, published, module,
            "recursive-group");
        Path executed = compileProject(scratch, "recursive-group-executed",
            RECURSIVE_GROUP_EXECUTED_SOURCE);
        for (int level : new int[] {0, 2}) {
            runProduct(scratch, executed, level, "recursive-group-executed");
        }
    }

    // =========================================================================
    // 9. Once-evaluated adapter sources
    // =========================================================================

    /**
     * An imported member read as an arity-extension source: the recorded
     * {@code AdaptSourceRef.Value} retains the member read's value for every
     * invocation and hands the produced function value to the source-signature
     * check (C5, {@code AdaptSourceRef}).
     */
    private static final String ADAPTER_IMPORT_SOURCE = """
        import * as util from "./lib";
        export function main(): null {
          let fromImport: (a: number, b: number) => number = util.ident;
          let value: number = fromImport(1, 2);
          if (value === 1) {
            let observed: string = (value as unknown) as string;
          }
          return null;
        }
        """;

    private static final String ADAPTER_IMPORT_LIB_SOURCE = """
        export function ident(n: number): number { return n; }
        """;

    /**
     * A call-result source executes once at creation; repeated adapter calls
     * invoke the retained function without repeating factory effects.
     */
    private static final String ADAPTER_FACTORY_SOURCE = """
        function ident(n: number): number { return n; }
        export function main(): null {
          let calls: number = 0;
          function tick(): number { calls = calls + 1; return calls; }
          function maker(): (n: number) => number { tick(); return ident; }
          let fromCall: (a: number, b: number) => number = maker();
          let before: number = calls;
          let first: number = fromCall(1, 2);
          let second: number = fromCall(3, 4);
          if (before === 1 && first === 1 && second === 3 && calls === 1) {
            let observed: string = (calls as unknown) as string;
          }
          return null;
        }
        """;

    /**
     * The retained closure captures its factory parameter and remains rooted
     * across subsequent adapter calls and allocations.
     */
    private static final String ADAPTER_FACTORY_CAPTURE_SOURCE = """
        function maker(k: number): (n: number) => number {
          function add(n: number): number { return n + k; }
          return add;
        }
        export function main(): null {
          let base: number = 10;
          let fromCall: (a: number, b: number) => number = maker(base);
          let first: number = fromCall(1, 2);
          let second: number = fromCall(3, 4);
          if (first === 11 && second === 13) {
            let observed: string = (first as unknown) as string;
          }
          return null;
        }
        """;

    /**
     * Repeated calls share the retained counter closure: they observe 1 and 2,
     * not two fresh closures each returning 1.
     */
    private static final String ADAPTER_COUNTER_SOURCE = """
        function maker(): () => number {
          let n: number = 0;
          return function(): number { n = n + 1; return n; };
        }
        export function main(): null {
          let h: (a: number) => number = maker();
          let first: number = h(0);
          let second: number = h(0);
          if (first === 1 && second === 2) {
            let observed: string = (first as unknown) as string;
          }
          return null;
        }
        """;

    private static void testRetainedValueAdapters(Path scratch)
            throws Exception {
        System.out.println("-- adapter sources evaluate once and retain their values --");
        Path root = scratch.resolve("adapter-import");
        writeProject(root, Map.of("src/main.deal", ADAPTER_IMPORT_SOURCE,
            "src/lib.deal", ADAPTER_IMPORT_LIB_SOURCE));
        CompileOutcome importOutcome = compile(root.resolve("src/main.deal"),
            root.resolve("build/llvm"));
        check(importOutcome.success(), "an imported member read is a renderable"
            + " adapter source: " + importOutcome.diagnostics());
        Path published = root.resolve("build/llvm");
        String module = Files.readString(published.resolve("main.ll"),
            StandardCharsets.UTF_8);
        check(!module.contains("define internal %struct.deal_outcome @R"),
            "the adapter emits no source re-executor");
        check(module.contains("call i8* @deal_fn_signature("),
            "the retained source value's signature atom is validated");
        checkEq(0, NativeCompiler.run(List.of("llvm-as",
            published.resolve("main.ll").toString()), scratch).exitCode(),
            "llvm-as accepts the imported member-read adapter module");
        for (int level : new int[] {0, 2}) {
            runProduct(scratch, published, level, "adapter-import");
        }
        Path factory = compileProject(scratch, "adapter-factory",
            ADAPTER_FACTORY_SOURCE);
        String factoryModule = Files.readString(factory.resolve("main.ll"),
            StandardCharsets.UTF_8);
        check(!factoryModule.contains("define internal %struct.deal_outcome @R"),
            "the call-result adapter retains the evaluated function");
        for (int level : new int[] {0, 2}) {
            runProduct(scratch, factory, level, "adapter-factory");
        }
        Path capture = compileProject(scratch, "adapter-factory-capture",
            ADAPTER_FACTORY_CAPTURE_SOURCE);
        String captureModule = Files.readString(capture.resolve("main.ll"),
            StandardCharsets.UTF_8);
        check(captureModule.contains("call i8* @deal_fn_capture("
            + "%struct.deal_fn* %carrier, i32 0)"),
            "the adapter retains its source value cell");
        for (int level : new int[] {0, 2}) {
            runProduct(scratch, capture, level, "adapter-factory-capture");
        }
        Path identity = compileProject(scratch, "adapter-counter",
            ADAPTER_COUNTER_SOURCE);
        for (int level : new int[] {0, 2}) {
            runProduct(scratch, identity, level, "adapter-counter");
        }
        Path snapshots = compileProject(scratch, "adapter-value-capture", """
            import * as console from "std/console";
            class State { calls: number; }
            function one(): number { return 1; }
            function two(): number { return 2; }
            function check(ok: boolean): void {
                if (!ok) { throw {code: "TEST_FAIL", message: "adapter snapshot"}; }
            }
            function make(state: State): () => number {
                state.calls = state.calls + 1;
                console.log("factory");
                return one;
            }
            export function main(): null {
                let source: () => number = one;
                let saved: (ignored: number) => number = source;
                source = two;
                check(saved(0) === 1);
                function take(value: (ignored: number) => number): number {
                    source = two;
                    return value(0);
                }
                source = one;
                check(take(source) === 1);
                let state: State = {calls: 0};
                let made: (ignored: number) => number = make(state);
                console.log("after");
                check(state.calls === 1);
                check(made(0) === 1);
                check(made(0) === 1);
                check(state.calls === 1);
                let live: number = 3;
                let closure: () => number = function(): number { return live; };
                let adapted: (ignored: number) => number = closure;
                closure = two;
                live = 4;
                check(adapted(0) === 4);
                console.log("OK");
                return null;
            }
            """);
        for (int level : new int[] {0, 2}) {
            var run = runProduct(scratch, snapshots, level, "adapter-value-capture", true);
            checkEq(0, run.exitCode(), "native adapter snapshot controls: " + run.describe());
            checkEq("factory\nafter\nOK\n", run.stdout(),
                "native creation timing, source snapshots and closure control");
        }
    }

    // =========================================================================
    // 10. Checked assertions to function and nullish descriptors
    // =========================================================================

    /**
     * A function value recovered from {@code unknown} and a string recovered
     * into a nullish position: both are the checked {@code as T} form, so each
     * renders the one descriptor-driven check at its own declared boundary kind
     * and keeps the extracted value's identity (C6/R4).
     */
    private static final String ASSERTION_SOURCE = """
        import * as strings from "std/string";
        function one(n: number): number { return n + 1; }
        export function main(): null {
          let a: (n: number) => number = one;
          let boxed: unknown = a;
          let back: (n: number) => number = boxed as (n: number) => number;
          let applied: number = back(2);
          let text: unknown = "hello";
          let nullish: string | null = text as string | null;
          if (nullish !== null) {
            let length: number = strings.length(nullish);
            if (applied === 3 && length === 5) {
              let observed: string = (applied as unknown) as string;
            }
          }
          return null;
        }
        """;

    /** A recovered function value of the wrong signature (the E8010 arm). */
    private static final String ASSERTION_SIGNATURE_MISMATCH_SOURCE = """
        function id(x: string): string { return x; }
        export function main(): null {
          let f: (x: string) => string = id;
          let boxed: unknown = f;
          let wrong: (n: number) => number = boxed as (n: number) => number;
          let applied: number = wrong(2);
          return null;
        }
        """;

    /** A nullish assertion over a value the declared base never admits. */
    private static final String ASSERTION_NULLISH_MISMATCH_SOURCE = """
        export function main(): null {
          let text: unknown = 1;
          let nullish: string | null = text as string | null;
          if (nullish === null) { return null; }
          return null;
        }
        """;

    private static void testCheckedAssertions(Path scratch) throws Exception {
        System.out.println("-- checked assertions to function and nullish"
            + " descriptors --");
        Path published = compileProject(scratch, "assertions",
            ASSERTION_SOURCE);
        String module = Files.readString(published.resolve("main.ll"),
            StandardCharsets.UTF_8);
        check(indexOfPattern(module, "@deal_check_value\\([^\\n]*, i32 "
            + LlvmRuntimeAbi.BOUNDARY_FUNCTION_SIGNATURE + ", ") >= 0,
            "the function assertion checks at the FUNCTION_SIGNATURE"
                + " boundary");
        check(indexOfPattern(module, "@deal_check_value\\([^\\n]*, i32 "
            + LlvmRuntimeAbi.BOUNDARY_TYPE_DESCRIPTOR + ", ") >= 0,
            "the nullish assertion checks at the TYPE_DESCRIPTOR boundary");
        check(module.contains("bitcast i8* ")
            && module.contains(" to %struct.deal_fn*"),
            "the checked function datum is projected onto its own carrier");
        for (int level : new int[] {0, 2}) {
            runProduct(scratch, published, level, "assertions");
        }
        Path mismatch = compileProject(scratch, "assertion-signature",
            ASSERTION_SIGNATURE_MISMATCH_SOURCE);
        for (int level : new int[] {0, 2}) {
            NativeCompiler.Result run = runProduct(scratch, mismatch, level,
                "assertion-signature", true);
            checkEq(1, run.exitCode(), "-O" + level + " the mismatched function"
                + " assertion reaches its E8010 arm: " + run.describe());
            check(run.stdout().contains("DEAL_ERROR_CODE: E8010"),
                "-O" + level + " the assertion reports E8010: "
                    + run.describe());
            check(run.stdout().contains("\"line\":5"),
                "-O" + level + " the E8010 arm carries the assertion's own"
                    + " origin: " + run.describe());
        }
        Path nullish = compileProject(scratch, "assertion-nullish",
            ASSERTION_NULLISH_MISMATCH_SOURCE);
        for (int level : new int[] {0, 2}) {
            NativeCompiler.Result run = runProduct(scratch, nullish, level,
                "assertion-nullish", true);
            checkEq(1, run.exitCode(), "-O" + level + " the non-admitted"
                + " nullish assertion reaches its E8001 arm: "
                + run.describe());
            check(run.stdout().contains("DEAL_ERROR_CODE: E8001"),
                "-O" + level + " the nullish assertion reports E8001: "
                    + run.describe());
            check(run.stdout().contains("\"line\":3"),
                "-O" + level + " the E8001 arm carries the assertion's own"
                    + " origin: " + run.describe());
        }
    }

    // =========================================================================
    // 11. Synthetic bodies declare every runtime entry they emit
    // =========================================================================

    /**
     * A boxed apply thunk whose completion is a reference nullish, with no
     * other nullish reference in the project: the synthetic body is the only
     * user of the two singleton identity entries it emits, so the published
     * module must declare them itself (C9, Verification 2).
     */
    private static final String REFERENCE_NULLISH_RESULT_SOURCE = """
        function pick(flag: boolean, s: string): string | undefined {
          if (flag) { return s; }
          return undefined;
        }
        export function main(): null { return null; }
        """;

    /**
     * The same omission for a nullish function base: the completion's boxing
     * is the reference-nullish arm with the function tag, and no other fact of
     * the project references the two identity entries (C9, Verification 2).
     */
    private static final String NULLISH_FUNCTION_RESULT_SOURCE = """
        function one(n: number): number { return n; }
        function pick(flag: number): ((n: number) => number) | null {
          if (flag === 1) { return one; }
          return null;
        }
        export function main(): null { return null; }
        """;

    /**
     * An arity adapter that no dynamic call consumes: the adapter thunk is the
     * only user of the signature entry it emits, so the published module must
     * declare it (C9, Verification 2).
     */
    private static final String ADAPTER_ONLY_SOURCE = """
        function one(n: number): number { return n; }
        export function main(): null {
          let h: (a: number, b: number) => number = one;
          return null;
        }
        """;

    private static void testSyntheticBodyDeclarations(Path scratch)
            throws Exception {
        System.out.println("-- synthetic bodies declare every entry they emit --");
        // Every fixture is the project's only user of the entries its own
        // synthetic body emits, so a missing declaration leaves the published
        // IR unparsable instead of being masked by another fact.
        Path referenceNullish = compileProject(scratch, "reference-nullish",
            REFERENCE_NULLISH_RESULT_SOURCE);
        String nullishModule = Files.readString(
            referenceNullish.resolve("main.ll"), StandardCharsets.UTF_8);
        check(nullishModule.contains("call i32 @deal_value_is_null("),
            "the reference-nullish boxing tests the null singleton");
        assertDeclaredAndParsable(scratch, referenceNullish, nullishModule,
            "reference-nullish", "declare i32 @deal_value_is_null(i8*)",
            "declare i32 @deal_value_is_undefined(i8*)");
        Path nullishFunction = compileProject(scratch, "nullish-function-result",
            NULLISH_FUNCTION_RESULT_SOURCE);
        String functionModule = Files.readString(
            nullishFunction.resolve("main.ll"), StandardCharsets.UTF_8);
        check(functionModule.contains("call i32 @deal_value_is_null("),
            "the nullish function base boxing tests the null singleton");
        assertDeclaredAndParsable(scratch, nullishFunction, functionModule,
            "nullish-function-result",
            "declare i32 @deal_value_is_null(i8*)",
            "declare i32 @deal_value_is_undefined(i8*)");
        Path adapterOnly = compileProject(scratch, "adapter-only",
            ADAPTER_ONLY_SOURCE);
        String adapterModule = Files.readString(adapterOnly.resolve("main.ll"),
            StandardCharsets.UTF_8);
        check(adapterModule.contains("call i8* @deal_fn_signature("),
            "the adapter thunk reads the source's signature atom");
        assertDeclaredAndParsable(scratch, adapterOnly, adapterModule,
            "adapter-only", "declare i8* @deal_fn_signature(%struct.deal_fn*)");
    }

    /**
     * Asserts that every entry the synthetic body's module emits is declared by
     * the published module, that {@code llvm-as} accepts it, and that the
     * product builds and runs at both levels (these shapes are legal programs,
     * not rejected ones).
     */
    private static void assertDeclaredAndParsable(Path scratch, Path published,
                                                  String module, String name,
                                                  String... declarations)
            throws Exception {
        for (String declaration : declarations) {
            check(module.contains(declaration), name + " declares '"
                + declaration + "'");
        }
        NativeCompiler.Result assembled = NativeCompiler.run(
            List.of("llvm-as", published.resolve("main.ll").toString()), scratch);
        checkEq(0, assembled.exitCode(), "llvm-as accepts the " + name
            + " module: " + assembled.describe());
        for (int level : new int[] {0, 2}) {
            Path product = scratch.resolve("products").resolve(name + "-" + level);
            Files.createDirectories(product.getParent());
            try {
                NativeCompiler.build(
                    new deal.toolchain.NativeBuildRequest(published, product,
                        level, List.of(), null));
            } catch (deal.toolchain.NativeBuildException failure) {
                check(false, "-O" + level + " the " + name + " product"
                    + " builds: " + failure.getMessage());
                continue;
            }
            NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
                Map.of("DEAL_GC_STRESS", "1"));
            checkEq(0, run.exitCode(), "-O" + level + " the " + name
                + " product runs: " + run.describe());
        }
    }

    // =========================================================================
    // 12. A synthesized dispatch shim contributes no Error frame
    // =========================================================================

    /**
     * Three DEAL activations reached through an arity-extension adapter whose
     * source is a retained value: the innermost assertion fails, so the carried
     * Error must snapshot exactly the three activations that raised and
     * dispatched it — never a synthesized `adapter@` shim frame (C8/R5, task
     * criterion "cross-call Error identity/origin/frames").
     */
    private static final String ADAPTER_FRAMES_SOURCE = """
        function one(mixed: unknown): number {
          return mixed as number;
        }
        function use2(f: (a: unknown, b: number) => number, mixed: unknown): number {
          return f(mixed, 2);
        }
        export function main(): null {
          let h: (n: unknown) => number = one;
          let wide: (a: unknown, b: number) => number = h;
          let text: string = "not a number";
          let value: number = use2(wide, text);
          return null;
        }
        """;

    /**
     * A factory failure occurs during source evaluation at adapter creation:
     * the snapshot is `maker`, `main`, before `use2` can be invoked.
     */
    private static final String ADAPTER_CREATION_FAILURE_SOURCE = """
        function one(n: number): number { return n; }
        function maker(mixed: unknown): (n: number) => number {
          let value: number = mixed as number;
          return one;
        }
        function use2(f: (a: number, b: number) => number): number {
          return f(1, 2);
        }
        export function main(): null {
          let text: string = "not a number";
          let wide: (a: number, b: number) => number = maker(text);
          let value: number = use2(wide);
          return null;
        }
        """;

    /**
     * An explicit `throw` inside a function reached through an arity-extension
     * adapter: the carried Error keeps its code and message, its own origin and
     * exactly the three activations that raised and dispatched it (C4/R5).
     */
    private static final String ADAPTER_THROWN_FRAMES_SOURCE = """
        function one(n: number): number {
          if (n > 0) { throw { code: "E9001", message: "adapted call failed" }; }
          return n;
        }
        function use2(f: (a: number, b: number) => number): number {
          return f(1, 2);
        }
        export function main(): null {
          let h: (n: number) => number = one;
          let wide: (a: number, b: number) => number = h;
          let value: number = use2(wide);
          return null;
        }
        """;

    /**
     * An adapted call that fails and is caught on every iteration: each adapter
     * invocation restores the caller's frame and root depth (M4/CallFrameContract),
     * so the third iteration still runs, all three failures are caught and the
     * terminal arm is the fixture's own distinguishing assertion instead of the
     * adapted call's origin.
     */
    private static final String ADAPTER_RECOVERY_SOURCE = """
        function one(mixed: unknown): number {
          return mixed as number;
        }
        function use2(f: (a: unknown, b: number) => number, mixed: unknown): number {
          return f(mixed, 2);
        }
        export function main(): null {
          let h: (n: unknown) => number = one;
          let wide: (a: unknown, b: number) => number = h;
          let text: string = "not a number";
          let caught: number = 0;
          let round: number = 0;
          while (round < 3) {
            try {
              let value: number = use2(wide, text);
              let unused: number = value;
            } catch (failure) {
              let error: Error = failure as Error;
              if (error.code === "E8001") { caught = caught + 1; }
            }
            round = round + 1;
          }
          let observed: string = (caught as unknown) as string;
          return null;
        }
        """;

    /**
     * The driver shape's frame projection (R9 accessors, D4: every published
     * runtime unit except the product process entry plus this driver).
     */
    private static final String FRAME_DRIVER_SOURCE = """
        #include "deal/native/entry.h"
        #include <stdio.h>

        int main(void) {
            deal_outcome started = deal_project_start();
            if (started.error != NULL) {
                printf("start=failed\\n");
                return 3;
            }
            deal_outcome outcome = deal_project_run_main();
            if (outcome.error == NULL) {
                printf("ok\\n");
                return 0;
            }
            int32_t frames = 0;
            if (!deal_error_frame_count(outcome.error, &frames)) {
                frames = -1;
            }
            const char *code = NULL;
            const char *message = NULL;
            printf("code=%s\\n",
                deal_error_code(outcome.error, &code) ? code : "-");
            printf("message=%s\\n",
                deal_error_message(outcome.error, &message) ? message : "-");
            printf("frames=%d\\n", (int)frames);
            for (int32_t index = 0; index < frames; index += 1) {
                const char *label = NULL;
                if (deal_error_frame(outcome.error, index, &label)) {
                    printf("frame%d=%s\\n", (int)index, label);
                }
            }
            return 0;
        }
        """;

    private static void testAdapterFrames(Path scratch) throws Exception {
        System.out.println("-- a dispatch shim contributes no Error frame --");
        Path value = compileProject(scratch, "adapter-frames",
            ADAPTER_FRAMES_SOURCE);
        String valueModule = Files.readString(value.resolve("main.ll"),
            StandardCharsets.UTF_8);
        assertShimFrameDiscipline(valueModule, "the value-source adapter");
        for (int level : new int[] {0, 2}) {
            checkEq(List.of("code=E8001", "message=expected number", "frames=3",
                    "frame0=" + recordedFrameLabel(valueModule, "one"),
                    "frame1=" + recordedFrameLabel(valueModule, "use2"),
                    "frame2=" + recordedFrameLabel(valueModule, "main")),
                projectedFrames(scratch, value, level, "adapter-frames"),
                "-O" + level + " an adapted call reports exactly its three"
                    + " source activations");
        }
        Path thunk = compileProject(scratch, "adapter-thunk-frames",
            ADAPTER_CREATION_FAILURE_SOURCE);
        String thunkModule = Files.readString(thunk.resolve("main.ll"),
            StandardCharsets.UTF_8);
        assertShimFrameDiscipline(thunkModule, "the thunk-source adapter");
        for (int level : new int[] {0, 2}) {
            checkEq(List.of("code=E8001", "message=expected number", "frames=2",
                    "frame0=" + recordedFrameLabel(thunkModule, "maker"),
                    "frame1=" + recordedFrameLabel(thunkModule, "main")),
                projectedFrames(scratch, thunk, level, "adapter-thunk-frames"),
                "-O" + level + " a creation-time source failure reports exactly"
                    + " the activations it entered");
        }
        Path thrown = compileProject(scratch, "adapter-thrown-frames",
            ADAPTER_THROWN_FRAMES_SOURCE);
        String thrownModule = Files.readString(thrown.resolve("main.ll"),
            StandardCharsets.UTF_8);
        assertShimFrameDiscipline(thrownModule, "the throwing adapter");
        for (int level : new int[] {0, 2}) {
            checkEq(List.of("code=E9001", "message=adapted call failed",
                    "frames=3",
                    "frame0=" + recordedFrameLabel(thrownModule, "one"),
                    "frame1=" + recordedFrameLabel(thrownModule, "use2"),
                    "frame2=" + recordedFrameLabel(thrownModule, "main")),
                projectedFrames(scratch, thrown, level, "adapter-thrown-frames"),
                "-O" + level + " an Error thrown through an adapted call keeps"
                    + " its code, message and three source activations");
        }
        Path recovery = compileProject(scratch, "adapter-recovery",
            ADAPTER_RECOVERY_SOURCE);
        for (int level : new int[] {0, 2}) {
            NativeCompiler.Result run = runProduct(scratch, recovery, level,
                "adapter-recovery", true);
            checkEq(1, run.exitCode(), "-O" + level + " the adapter recovery"
                + " fixture reaches its own assertion: " + run.describe());
            check(run.stdout().contains("DEAL_ERROR_CODE: E8001"),
                "-O" + level + " every adapted invocation's failure was"
                    + " caught: " + run.describe());
            check(run.stdout().contains("\"line\":23"),
                "-O" + level + " the terminal arm is the fixture's own"
                    + " assertion, not the adapted call's: " + run.describe());
        }
    }

    /**
     * The emitted frame discipline of the synthesized adapter bodies of one
     * module: the adapter thunk pops its own label before it dispatches the
     * source (so the dispatched body's label is the one frame the call
     * contributes). Source evaluation happens in the creating activation.
     */
    private static void assertShimFrameDiscipline(String module, String name) {
        Matcher thunks = Pattern.compile("@(A\\d+)\\(i8\\* %functionvalue")
            .matcher(module);
        int thunkCount = 0;
        while (thunks.find()) {
            String text = functionText(module, thunks.group(1));
            int push = text.indexOf("@deal_call_frame_push");
            int pop = text.indexOf("@deal_call_frame_pop");
            int dispatch = indexOfPattern(text, "@deal_fn_apply\\(");
            thunkCount++;
            check(push >= 0 && pop > push, name + " pushes and pops its own"
                + " frame label: " + thunks.group(1));
            check(dispatch > pop, name + " pops its frame label before it"
                + " dispatches the source: " + thunks.group(1));
            checkEq(count(text, "@deal_call_frame_push"),
                count(text, "@deal_call_frame_pop"), name + " balances its"
                    + " frame push and pop: " + thunks.group(1));
        }
        check(thunkCount > 0, name + " emits at least one adapter thunk");
        check(!Pattern.compile("define [^\\n]*@R\\d+\\(").matcher(module).find(),
            name + " emits no source re-executor");
    }

    /**
     * The module's own recorded frame label of one source function: the
     * `<modulePath>#<sourceName>@F<id>` constant the emitter publishes, so the
     * executed projection is compared against the artifact instead of a
     * recorded run.
     */
    private static String recordedFrameLabel(String module, String sourceName) {
        Matcher matcher = Pattern.compile("\"([^\"]*#"
            + Pattern.quote(sourceName) + "@F\\d+)\\\\00\"").matcher(module);
        if (!matcher.find()) {
            check(false, "the module records the frame label of '" + sourceName
                + "'");
            return null;
        }
        return matcher.group(1);
    }

    /**
     * Builds the driver shape of one published fixture and returns its
     * projected arm lines (the arm tuple's own code and message, then the frame
     * count and every label, in order).
     */
    private static List<String> projectedFrames(Path scratch, Path published,
                                               int level, String name)
            throws Exception {
        Path driver = scratch.resolve(name + "-frame-driver.c");
        Files.writeString(driver, FRAME_DRIVER_SOURCE, StandardCharsets.UTF_8);
        List<Path> sources = new ArrayList<>();
        sources.add(published.resolve("main.ll"));
        for (Path unit : NativeCompiler.runtimeTranslationUnits(published)) {
            if (!unit.getFileName().toString().equals("main.c")) {
                sources.add(unit);
            }
        }
        sources.add(driver);
        Path product = scratch.resolve("products")
            .resolve(name + "-frames-" + level);
        Files.createDirectories(product.getParent());
        NativeCompiler.Result link = NativeCompiler.link(published, level,
            sources, product);
        check(link.succeeded(), "-O" + level + " the " + name + " frame"
            + " driver links: " + link.describe());
        if (!link.succeeded()) {
            return List.of();
        }
        NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
            Map.of("DEAL_GC_STRESS", "1"));
        checkEq(0, run.exitCode(), "-O" + level + " the " + name + " frame"
            + " driver runs: " + run.describe());
        List<String> lines = new ArrayList<>();
        for (String line : run.stdout().split("\n")) {
            if (line.startsWith("frames=") || line.startsWith("frame")
                    || line.startsWith("code=") || line.startsWith("message=")) {
                lines.add(line);
            }
        }
        return lines;
    }

    // =========================================================================
    // 13. Determinism
    // =========================================================================

    private static void testDeterminism(Path scratch) throws Exception {
        System.out.println("-- two compiles are byte identical --");
        Path first = compileProject(scratch, "determinism-a", CALLS_SOURCE);
        Path second = compileProject(scratch, "determinism-b", CALLS_SOURCE);
        NativeCompiler.requireSameBytes(
            Files.readAllBytes(first.resolve("main.ll")),
            Files.readAllBytes(second.resolve("main.ll")),
            "the two emitted modules");
        check(NativeCompiler.run(List.of("llvm-as",
                first.resolve("main.ll").toString()), scratch).exitCode() == 0,
            "llvm-as accepts the closure module");
    }

    // =========================================================================
    // Harness helpers
    // =========================================================================

    private record CompileOutcome(boolean success,
                                  List<CompilerDiagnostic> diagnostics,
                                  String stdout, String stderr) {
    }

    private static Path compileProject(Path scratch, String name, String source)
            throws Exception {
        Path root = scratch.resolve(name);
        writeProject(root, Map.of("src/main.deal", source));
        CompileOutcome outcome = compile(root.resolve("src/main.deal"),
            root.resolve("build/llvm"));
        if (!outcome.success()) {
            throw new IllegalStateException("the fixture '" + name
                + "' failed to compile: " + outcome.diagnostics());
        }
        return root.resolve("build/llvm");
    }

    private static void writeProject(Path root, Map<String, String> sources)
            throws IOException {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("deal.json"),
            "{\"languageVersion\": \"1.5\", \"moduleRoots\": [\"src\"],"
                + " \"backend\": \"llvm\"}", StandardCharsets.UTF_8);
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Path file = root.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
        }
    }

    private static CompileOutcome compile(Path entry, Path output)
            throws IOException {
        ProjectLocator.LocateResult located = ProjectLocator.locate(
            entry.toAbsolutePath().toString(),
            new CliOverrides(null, output.toAbsolutePath().toString()));
        if (located.context() == null) {
            return new CompileOutcome(false, List.of(), "",
                String.valueOf(located.cliDiagnostic()));
        }
        ProjectContext context = located.context();
        CompilerInvocation invocation = CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            context, entry.toAbsolutePath().normalize(), false, false, false,
            false, null, invocation);
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

    /**
     * Builds and runs one published fixture through the production toolchain
     * seam. The successful fixtures reach their E8001 assertion when every
     * lowered behavior agrees with the expected value; the caller passes
     * {@code expectFailure} for the arm that is expected to fail.
     */
    private static NativeCompiler.Result runProduct(Path scratch, Path published,
                                                    int level, String name)
            throws Exception {
        return runProduct(scratch, published, level, name, false);
    }

    private static NativeCompiler.Result runProduct(Path scratch, Path published,
                                                    int level, String name,
                                                    boolean expectFailure)
            throws Exception {
        Path product = scratch.resolve("products").resolve(name + "-" + level);
        Files.createDirectories(product.getParent());
        NativeCompiler.build(new deal.toolchain.NativeBuildRequest(
            published, product, level, List.of(), null));
        NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
            Map.of("DEAL_GC_STRESS", "1"));
        if (expectFailure) {
            return run;
        }
        checkEq(1, run.exitCode(), "-O" + level + " the " + name
            + " fixture reaches its executed assertion: " + run.describe());
        check(run.stdout().contains("DEAL_ERROR_CODE: E8001"),
            "-O" + level + " the " + name + " fixture reports the E8001 arm: "
                + run.describe());
        return run;
    }

    /** The index of the unit whose path is {@code path}. */
    private static String moduleIndex(String module, String... paths) {
        Matcher matcher = Pattern.compile("deal\\.module\\.(\\d+) = internal"
            + " global %struct\\.deal_module \\{ i8\\* getelementptr \\(\\[")
            .matcher(module);
        int index = 0;
        while (matcher.find()) {
            index = Integer.parseInt(matcher.group(1));
        }
        return Integer.toString(index);
    }

    private static String functionText(String module, String name) {
        int start = module.indexOf("@" + name + "(");
        if (start < 0) {
            return "";
        }
        int bodyStart = module.indexOf('{', start);
        int depth = 0;
        for (int position = bodyStart; position < module.length(); position++) {
            char character = module.charAt(position);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return module.substring(start, position + 1);
                }
            }
        }
        return module.substring(start);
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

    /** The character offset of the first match of one pattern, or -1. */
    private static int indexOfPattern(String text, String pattern) {
        Matcher matcher = Pattern.compile(pattern).matcher(text);
        return matcher.find() ? matcher.start() : -1;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder())
                    .toList()) {
                Files.deleteIfExists(path);
            }
        }
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
        check(java.util.Objects.equals(expected, actual), description
            + " (expected " + expected + ", got " + actual + ")");
    }
}
