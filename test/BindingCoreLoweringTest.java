package deal.test;

import deal.ast.ProgramNode;
import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.NameResolver;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CheckedModuleInput;
import deal.semantic.CheckedModuleKind;
import deal.semantic.SemanticLowerer;
import deal.semantic.ir.BindingCellKind;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.BoundaryRealization;
import deal.semantic.ir.ControlSelector;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.ExternalModuleKind;
import deal.semantic.ir.InitializationMode;
import deal.semantic.ir.IterationMode;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticIdAllocator;
import deal.semantic.ir.SemanticIrDumper;
import deal.semantic.ir.SemanticIrValidator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The binding-core child's lowering tests (ISSUE-0444 sequencing item 1):
 * one globally unique {@code BindingId} per declared name, static
 * per-incarnation generation ordinals, {@code BINDING_ALLOC/INIT/LOAD/
 * STORE} production with the closed {@code DIRECT|SHARED_CELL} defaults
 * and special cases, hoisted module-level function ALLOCs and
 * module-init-top intrinsic bindings, the two-incarnation for-let shape,
 * and {@code FOR_EACH} iteration-binding producing allocations — driven
 * through the child's public lowering entry point
 * ({@link SemanticLowerer#lowerModuleBindingCore}) with the produced
 * units validated by the closed validator, the address-chain protocol,
 * and the claiming seam under the pinned E6-gate activation.
 *
 * <p><b>Coverage.</b></p>
 * <ul>
 *   <li>let declarations: ALLOC (generation 0, {@code DIRECT}, mutable)
 *       then the initializer's value ops through the value-expression
 *       seam then exactly one BINDING_INIT, with the
 *       {@code VARIABLE_DECLARATION} boundary preceding INIT for
 *       annotated declarations and INIT direct for inferred ones;</li>
 *   <li>parameter/catch/import-alias ALLOCs at block entry with
 *       generation 0 and no BINDING_INIT;</li>
 *   <li>intrinsic {@code int}/{@code number} ALLOC + INIT at
 *       module-init top;</li>
 *   <li>hoisted module-level function ALLOCs preceding the first
 *       statement of module init, in declaration order; nested function
 *       ALLOCs at the declaration position;</li>
 *   <li>the for-let two-incarnation shape: counter generation 0 in the
 *       {@code LOOP} init block ({@code DIRECT}) and the per-iteration
 *       generation 1 at the body top ({@code SHARED_CELL}) with the INIT
 *       operand equal to the generation-0 load; condition/update
 *       references name generation 0, body references generation 1;</li>
 *   <li>{@code FOR_EACH} payloads carrying the iteration binding's
 *       generation with the binding classified {@code SHARED_CELL} and
 *       the op as the producing allocation (no {@code BINDING_ALLOC});</li>
 *   <li>{@code BINDING_LOAD}/{@code BINDING_STORE} payloads naming
 *       {@code {binding, generation}} of the dominant incarnation at the
 *       site;</li>
 *   <li>exactly one INIT per source-declaration incarnation, and the
 *       closed cell-kind defaults and special cases;</li>
 *   <li>determinism: two fresh lowerings produce identical BindingIds,
 *       generation ordinals, and payload fields (byte-identical dumps);</li>
 *   <li>fail-closed negatives: foreign constructs raise E6005
 *       {@code CONSTRUCT_UNLOWERED} and a legacy profile raises the
 *       pinned {@code LOWER_LEGACY_PROFILE_REJECTED} guard.</li>
 * </ul>
 */
public class BindingCoreLoweringTest {

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
    private static final String INTERFACE_HASH = new ProjectInterfaceIndex(
        ProjectInterfaceIndex.FORMAT_VERSION, Map.of(MODULE,
            new ExternalModuleInterface(MODULE, ExternalModuleKind.IMPLEMENTATION,
                List.of(), List.of(), List.of(), InitializationMode.ONCE_AFTER_DEPENDENCIES)))
        .interfaceIndexDigest();

    private record CheckedSlice(ProgramNode program, CheckResult checks) {
    }

    // =========================================================================
    // Checked-source slices and the entry-point driver
    // =========================================================================

    private static CheckedSlice checkSlice(String source) {
        return checkSlice(source, null);
    }

    private static CheckedSlice checkSlice(String source, ModuleResolver resolver) {
        LexResult lex = new Lexer(source, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal", lex.directiveEvents()).parse();
        check(parse.diagnostics().isEmpty(), "the slice parses cleanly: " + parse.diagnostics());
        if (!parse.diagnostics().isEmpty()) {
            return null;
        }
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        check(nr.diagnostics().isEmpty(), "the slice resolves cleanly: " + nr.diagnostics());
        if (!nr.diagnostics().isEmpty()) {
            return null;
        }
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());
        check(result.diagnostics().isEmpty(),
            "the slice checks cleanly: " + result.diagnostics());
        if (result.hasErrors()) {
            return null;
        }
        return new CheckedSlice(parse.program(), result);
    }

    private static CheckedModuleInput moduleOf(CheckedSlice slice) {
        return new CheckedModuleInput(MODULE, SOURCE_ID, Path.of("test.deal"), slice.program(),
            slice.checks(), List.of(), List.of(), CheckedModuleKind.IMPLEMENTATION);
    }

    private static SemanticLowerer.BindingCoreResult lowerSlice(String source) {
        return lowerSlice(source, null);
    }

    private static SemanticLowerer.BindingCoreResult lowerSlice(String source,
                                                                 ModuleResolver resolver) {
        CheckedSlice slice = checkSlice(source, resolver);
        if (slice == null) {
            return null;
        }
        return SemanticLowerer.lowerModuleBindingCore(moduleOf(slice),
            SemanticProfile.DEAL_V1_2_INT32, Map.of(), INTERFACE_HASH, REGISTRY_HASH,
            SemanticIdAllocator.over(List.of(MODULE)));
    }

    private static List<SemanticOp> ofKind(List<SemanticOp> ops, SemanticOpKind kind) {
        List<SemanticOp> matches = new ArrayList<>();
        for (SemanticOp op : ops) {
            if (op.kind() == kind) {
                matches.add(op);
            }
        }
        return matches;
    }

    /** The binding facts entry of a declared name (registered exactly once). */
    private static SemanticLowerer.BindingCoreBinding fact(
            SemanticLowerer.BindingCoreFacts facts, String name) {
        List<SemanticLowerer.BindingCoreBinding> matches = new ArrayList<>();
        for (SemanticLowerer.BindingCoreBinding binding : facts.bindings()) {
            if (binding.name().equals(name)) {
                matches.add(binding);
            }
        }
        check(matches.size() == 1, "exactly one BindingId registered for '" + name
            + "'; got " + matches.size());
        return matches.size() == 1 ? matches.get(0) : null;
    }

    /** Counts INIT ops naming {binding, generation}. */
    private static long initCount(List<SemanticOp> ops, SemanticLowerer.BindingSite site) {
        long count = 0;
        for (SemanticOp op : ops) {
            if (op.kind() == SemanticOpKind.BINDING_INIT
                    && op.payload() instanceof KindPayload.BindingInitPayload init
                    && init.binding().equals(site.binding())
                    && init.generation() == site.generation()) {
                count++;
            }
        }
        return count;
    }

    // =========================================================================
    // Tests
    // =========================================================================

    /**
     * let declarations: ALLOC (gen 0, DIRECT, mutable) then the
     * initializer's value ops then exactly one INIT; the declaration
     * boundary precedes INIT for annotated declarations and INIT is
     * direct for inferred ones.
     */
    static void testLetDeclarationShape() {
        System.out.println("-- let declarations: ALLOC -> value ops -> [boundary] -> INIT --");

        SemanticLowerer.BindingCoreResult result = lowerSlice("""
            let a = 1;
            let b: int = 2;
            """);
        if (result == null) {
            return;
        }
        check(result.lowering() != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null,
            "the let slice lowers to a validated unit: "
                + (result.lowering() == null ? "null" : result.lowering().diagnostics()));
        if (result.lowering() == null || result.lowering().hasErrors()) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        List<SemanticOp> ops = unit.ops();
        BlockId moduleInitBlock = unit.moduleInit().initBlock();

        List<SemanticOp> allocs = ofKind(ops, SemanticOpKind.BINDING_ALLOC);
        List<SemanticOp> inits = ofKind(ops, SemanticOpKind.BINDING_INIT);
        List<SemanticOp> consts = ofKind(ops, SemanticOpKind.CONST);
        List<SemanticOp> boundaries = ofKind(ops, SemanticOpKind.BOUNDARY);

        // Intrinsic bindings first: ALLOC+INIT for int and number at
        // module-init top (the retained per-module wrapper shape).
        check(allocs.size() == 4 && inits.size() == 4,
            "the unit carries the two intrinsic ALLOC/INIT pairs plus the two let "
                + "ALLOCs (4 ALLOCs, 4 INITs); got " + allocs.size() + "/" + inits.size());
        if (allocs.size() != 4 || inits.size() != 4) {
            return;
        }
        for (int i = 0; i < 4; i++) {
            check(allocs.get(i).payload() instanceof KindPayload.BindingAllocPayload alloc
                    && alloc.scope().equals(moduleInitBlock)
                    && alloc.generation() == 0
                    && alloc.cellKind() == BindingCellKind.DIRECT,
                "ALLOC " + i + " carries {scope=module-init, generation 0, DIRECT}");
        }

        // The two let declarations: a (inferred, no boundary) and b
        // (annotated, boundary preceding INIT).
        SemanticLowerer.BindingCoreBinding a = fact(result.facts(), "a");
        SemanticLowerer.BindingCoreBinding b = fact(result.facts(), "b");
        if (a == null || b == null) {
            return;
        }
        check(a.incarnations().size() == 1 && a.incarnations().get(0).generation() == 0,
            "binding a has exactly one incarnation at generation 0");
        check(b.incarnations().size() == 1 && b.incarnations().get(0).generation() == 0,
            "binding b has exactly one incarnation at generation 0");
        check(a.incarnations().get(0).cellKind() == BindingCellKind.DIRECT
                && a.incarnations().get(0).mutable()
                && a.incarnations().get(0).producer() == SemanticLowerer.BindingProducer.BINDING_ALLOC,
            "binding a's incarnation is DIRECT, mutable, produced by BINDING_ALLOC");

        List<SemanticOp> aInits = new ArrayList<>();
        List<SemanticOp> bInits = new ArrayList<>();
        for (SemanticOp op : inits) {
            if (op.payload() instanceof KindPayload.BindingInitPayload init) {
                if (init.binding().equals(a.binding())) {
                    aInits.add(op);
                } else if (init.binding().equals(b.binding())) {
                    bInits.add(op);
                }
            }
        }
        check(aInits.size() == 1 && bInits.size() == 1,
            "exactly one INIT per source-declaration incarnation (a: " + aInits.size()
                + ", b: " + bInits.size() + ")");
        if (aInits.size() != 1 || bInits.size() != 1) {
            return;
        }
        KindPayload.BindingInitPayload aInit = (KindPayload.BindingInitPayload) aInits.get(0).payload();
        KindPayload.BindingInitPayload bInit = (KindPayload.BindingInitPayload) bInits.get(0).payload();
        check(aInit.generation() == 0 && aInit.value() != null
                && aInit.value().equals(consts.get(0).result()),
            "a's INIT commits generation 0 from the initializer's CONST result");
        check(bInit.generation() == 0 && bInit.value() != null
                && bInit.value().equals(consts.get(1).result()),
            "b's INIT commits generation 0 from the initializer's CONST result");

        // Order: ALLOC(a) precedes a's initializer ops precede INIT(a);
        // ALLOC(b) precedes initializer; the boundary sits between the
        // initializer and INIT(b) (annotated), and no boundary exists for a.
        List<SemanticOp> aAllocs = new ArrayList<>();
        List<SemanticOp> bAllocs = new ArrayList<>();
        for (SemanticOp op : allocs) {
            if (op.payload() instanceof KindPayload.BindingAllocPayload alloc) {
                if (alloc.binding().equals(a.binding())) {
                    aAllocs.add(op);
                } else if (alloc.binding().equals(b.binding())) {
                    bAllocs.add(op);
                }
            }
        }
        check(aAllocs.size() == 1 && bAllocs.size() == 1, "one ALLOC per let binding");
        check(indexOf(ops, aAllocs.get(0).opId()) < indexOf(ops, consts.get(0).opId())
                && indexOf(ops, consts.get(0).opId()) < indexOf(ops, aInits.get(0).opId()),
            "a: ALLOC then initializer value ops then INIT (source order)");
        check(indexOf(ops, bAllocs.get(0).opId()) < indexOf(ops, consts.get(1).opId())
                && indexOf(ops, consts.get(1).opId()) < indexOf(ops, bInits.get(0).opId()),
            "b: ALLOC then initializer value ops then INIT (source order)");

        check(boundaries.size() == 1,
            "exactly one boundary child for the annotated declaration; got " + boundaries.size());
        if (boundaries.size() == 1) {
            KindPayload.BoundaryPayload boundary =
                (KindPayload.BoundaryPayload) boundaries.get(0).payload();
            check(boundary.kind() == BoundaryKind.VARIABLE_DECLARATION
                    && boundary.descriptor().equals(RuntimeDescriptor.Int.INSTANCE)
                    && boundary.input().equals(consts.get(1).result())
                    && boundaries.get(0).failurePolicy() == FailurePolicyId.TYPE_DESCRIPTOR
                    && boundary.realization() instanceof BoundaryRealization.RuntimeValidation,
                "the annotated declaration's boundary is VARIABLE_DECLARATION, descriptor "
                    + "int, descriptor-kind policy TYPE_DESCRIPTOR, runtime-validation "
                    + "realization, input = the initializer value");
            check(indexOf(ops, consts.get(1).opId()) < indexOf(ops, boundaries.get(0).opId())
                    && indexOf(ops, boundaries.get(0).opId()) < indexOf(ops, bInits.get(0).opId()),
                "the declaration boundary precedes INIT (the Binding lifecycle contract shape)");
        }
    }

    /**
     * Parameters: ALLOC at the body block's entry with generation 0 and
     * no BINDING_INIT; module-level function-name ALLOCs hoisted to
     * module-init top in declaration order (preceding the first
     * statement's ops).
     */
    static void testParametersAndHoistedFunctionAllocs() {
        System.out.println("-- parameters: entry ALLOCs without INIT; hoisted module-level "
            + "function ALLOCs --");

        SemanticLowerer.BindingCoreResult result = lowerSlice("""
            function first(p: int, q: number): null {}
            function second(): null {}
            """);
        if (result == null) {
            return;
        }
        check(result.lowering() != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null,
            "the function slice lowers to a validated unit: "
                + (result.lowering() == null ? "null" : result.lowering().diagnostics()));
        if (result.lowering() == null || result.lowering().hasErrors()) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        List<SemanticOp> ops = unit.ops();
        BlockId moduleInitBlock = unit.moduleInit().initBlock();

        SemanticLowerer.BindingCoreBinding first = fact(result.facts(), "first");
        SemanticLowerer.BindingCoreBinding second = fact(result.facts(), "second");
        SemanticLowerer.BindingCoreBinding p = fact(result.facts(), "p");
        SemanticLowerer.BindingCoreBinding q = fact(result.facts(), "q");
        if (first == null || second == null || p == null || q == null) {
            return;
        }

        // Hoisted ALLOCs: intrinsics first (int, number), then first,
        // then second — all before the first statement's ops.
        List<SemanticOp> allocs = ofKind(ops, SemanticOpKind.BINDING_ALLOC);
        check(allocs.size() == 6,
            "two intrinsic ALLOCs + two hoisted function ALLOCs + two parameter ALLOCs; got "
                + allocs.size());
        int firstAlloc = -1;
        int secondAlloc = -1;
        int firstParam = -1;
        for (int i = 0; i < allocs.size(); i++) {
            KindPayload.BindingAllocPayload alloc =
                (KindPayload.BindingAllocPayload) allocs.get(i).payload();
            if (alloc.binding().equals(first.binding())) {
                firstAlloc = i;
            } else if (alloc.binding().equals(second.binding())) {
                secondAlloc = i;
            } else if (alloc.binding().equals(p.binding())) {
                firstParam = i;
            }
        }
        check(firstAlloc != -1 && secondAlloc != -1 && firstParam != -1,
            "the hoisted and parameter ALLOCs are present");
        check(firstAlloc >= 2 && secondAlloc == firstAlloc + 1 && firstParam > secondAlloc,
            "the hoisted module-level function ALLOCs sit at module-init top in declaration "
                + "order (first then second), before the body's parameter ALLOCs");
        check(indexOf(ops, allocs.get(secondAlloc).opId())
                < indexOf(ops, allocs.get(firstParam).opId()),
            "the hoisted ALLOC precedes the first statement of module init (the parameter "
                + "ALLOCs of first's body)");

        // The hoisted ALLOCs carry the module-init scope.
        KindPayload.BindingAllocPayload firstAllocPayload =
            (KindPayload.BindingAllocPayload) allocs.get(firstAlloc).payload();
        KindPayload.BindingAllocPayload secondAllocPayload =
            (KindPayload.BindingAllocPayload) allocs.get(secondAlloc).payload();
        check(firstAllocPayload.scope().equals(moduleInitBlock)
                && firstAllocPayload.generation() == 0
                && firstAllocPayload.cellKind() == BindingCellKind.DIRECT
                && firstAllocPayload.mutable()
                && secondAllocPayload.scope().equals(moduleInitBlock)
                && secondAllocPayload.generation() == 0,
            "the hoisted function-name ALLOCs carry {scope=module-init, generation 0, "
                + "DIRECT, mutable}");

        // Parameters: generation-0 ALLOCs at the body block's entry,
        // DIRECT, mutable, no INIT. The function-name incarnation lives at
        // module-init top; the parameter incarnations carry the body block
        // identity.
        BlockId bodyBlock = p.incarnations().get(0).scope();
        check(p.incarnations().get(0).scope().equals(bodyBlock)
                && q.incarnations().get(0).scope().equals(bodyBlock),
            "both parameter incarnations carry the same body block identity");
        check(p.incarnations().get(0).generation() == 0
                && p.incarnations().get(0).cellKind() == BindingCellKind.DIRECT
                && p.incarnations().get(0).mutable()
                && p.incarnations().get(0).producer() == SemanticLowerer.BindingProducer.BINDING_ALLOC,
            "parameter p: generation 0, DIRECT, mutable, produced by BINDING_ALLOC");
        long pInits = initCount(ops, new SemanticLowerer.BindingSite(p.binding(), 0));
        long qInits = initCount(ops, new SemanticLowerer.BindingSite(q.binding(), 0));
        long firstInits = initCount(ops, new SemanticLowerer.BindingSite(first.binding(), 0));
        check(pInits == 0 && qInits == 0,
            "parameters have ALLOC at block entry with generation 0 and no BINDING_INIT "
                + "(their initial write is the invoking machinery's)");
        check(firstInits == 0 && initCount(ops,
                new SemanticLowerer.BindingSite(second.binding(), 0)) == 0,
            "function-name bindings have no INIT in this child's window (CLOSURE_NEW + "
                + "BINDING_INIT stay at the declaration position for the closure child)");
    }

    /** Intrinsic bindings: ALLOC + INIT at module-init top, before everything else. */
    static void testIntrinsicBindingsAtModuleTop() {
        System.out.println("-- intrinsic bindings: ALLOC + INIT at module-init top --");

        SemanticLowerer.BindingCoreResult result = lowerSlice("let a = 1;");
        if (result == null) {
            return;
        }
        check(result.lowering() != null && !result.lowering().hasErrors(),
            "the slice lowers to a validated unit");
        if (result.lowering() == null || result.lowering().hasErrors()) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        List<SemanticOp> ops = unit.ops();
        BlockId moduleInitBlock = unit.moduleInit().initBlock();

        SemanticLowerer.BindingCoreBinding intBinding = fact(result.facts(), "int");
        SemanticLowerer.BindingCoreBinding numberBinding = fact(result.facts(), "number");
        if (intBinding == null || numberBinding == null) {
            return;
        }
        check(ops.size() >= 4
                && ops.get(0).kind() == SemanticOpKind.BINDING_ALLOC
                && ops.get(1).kind() == SemanticOpKind.BINDING_INIT
                && ops.get(2).kind() == SemanticOpKind.BINDING_ALLOC
                && ops.get(3).kind() == SemanticOpKind.BINDING_INIT,
            "the unit opens with ALLOC(int), INIT(int), ALLOC(number), INIT(number)");
        if (ops.size() < 4) {
            return;
        }
        KindPayload.BindingAllocPayload intAlloc =
            (KindPayload.BindingAllocPayload) ops.get(0).payload();
        KindPayload.BindingInitPayload intInit =
            (KindPayload.BindingInitPayload) ops.get(1).payload();
        KindPayload.BindingAllocPayload numberAlloc =
            (KindPayload.BindingAllocPayload) ops.get(2).payload();
        KindPayload.BindingInitPayload numberInit =
            (KindPayload.BindingInitPayload) ops.get(3).payload();
        check(intAlloc.binding().equals(intBinding.binding())
                && intAlloc.scope().equals(moduleInitBlock)
                && intAlloc.generation() == 0
                && intAlloc.cellKind() == BindingCellKind.DIRECT
                && !intAlloc.mutable(),
            "int: ALLOC at module-init top, generation 0, DIRECT, not mutable");
        check(numberAlloc.binding().equals(numberBinding.binding())
                && numberAlloc.scope().equals(moduleInitBlock)
                && numberAlloc.generation() == 0
                && numberAlloc.cellKind() == BindingCellKind.DIRECT
                && !numberAlloc.mutable(),
            "number: ALLOC at module-init top, generation 0, DIRECT, not mutable");
        check(intInit.binding().equals(intBinding.binding()) && intInit.generation() == 0
                && intInit.value() != null,
            "int: INIT at module-init top, generation 0, with the pinned intrinsic-value "
                + "operand identity");
        check(numberInit.binding().equals(numberBinding.binding())
                && numberInit.generation() == 0 && numberInit.value() != null,
            "number: INIT at module-init top, generation 0, with the pinned intrinsic-value "
                + "operand identity");
        SemanticLowerer.BindingCoreBinding a = fact(result.facts(), "a");
        if (a != null) {
            KindPayload.BindingAllocPayload aAlloc = null;
            for (SemanticOp op : ofKind(ops, SemanticOpKind.BINDING_ALLOC)) {
                KindPayload.BindingAllocPayload payload =
                    (KindPayload.BindingAllocPayload) op.payload();
                if (payload.binding().equals(a.binding())) {
                    aAlloc = payload;
                }
            }
            check(aAlloc != null && indexOf(ops, ops.get(0).opId())
                    < indexOf(ops, ops.get(1).opId())
                    && indexOf(ops, ops.get(3).opId()) < indexOf(ops, ops.get(4).opId()),
                "the intrinsic ALLOC+INIT pairs precede the first statement's ops");
        }
    }

    /** Nested function declarations: name ALLOC at the declaration position. */
    static void testNestedFunctionAllocAtPosition() {
        System.out.println("-- nested function declarations: ALLOC at the declaration position --");

        SemanticLowerer.BindingCoreResult result = lowerSlice("""
            function outer(): null {
                let x = 1;
                function g(): null {}
            }
            """);
        if (result == null) {
            return;
        }
        check(result.lowering() != null && !result.lowering().hasErrors(),
            "the nested-function slice lowers to a validated unit: "
                + (result.lowering() == null ? "null" : result.lowering().diagnostics()));
        if (result.lowering() == null || result.lowering().hasErrors()) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        List<SemanticOp> ops = unit.ops();
        BlockId moduleInitBlock = unit.moduleInit().initBlock();

        SemanticLowerer.BindingCoreBinding outer = fact(result.facts(), "outer");
        SemanticLowerer.BindingCoreBinding g = fact(result.facts(), "g");
        SemanticLowerer.BindingCoreBinding x = fact(result.facts(), "x");
        if (outer == null || g == null || x == null) {
            return;
        }
        // outer hoisted at module-init top; g allocated at its position.
        KindPayload.BindingAllocPayload outerAlloc = null;
        KindPayload.BindingAllocPayload gAlloc = null;
        for (SemanticOp op : ofKind(ops, SemanticOpKind.BINDING_ALLOC)) {
            KindPayload.BindingAllocPayload payload =
                (KindPayload.BindingAllocPayload) op.payload();
            if (payload.binding().equals(outer.binding())) {
                outerAlloc = payload;
            } else if (payload.binding().equals(g.binding())) {
                gAlloc = payload;
            }
        }
        check(outerAlloc != null && gAlloc != null, "outer and g ALLOCs are present");
        if (outerAlloc == null || gAlloc == null) {
            return;
        }
        check(outerAlloc.scope().equals(moduleInitBlock),
            "outer's name ALLOC is hoisted to module-init top");
        check(!gAlloc.scope().equals(moduleInitBlock),
            "g's name ALLOC is not at module-init top (nested declarations are defined at "
                + "their position, never hoisted)");
        BlockId outerBody = x.incarnations().get(0).scope();
        check(gAlloc.scope().equals(outerBody),
            "g's name ALLOC carries the enclosing body block identity");
        SemanticOp gAllocOp = null;
        SemanticOp xInit = null;
        for (SemanticOp op : ops) {
            if (op.kind() == SemanticOpKind.BINDING_ALLOC
                    && ((KindPayload.BindingAllocPayload) op.payload()).binding()
                        .equals(g.binding())) {
                gAllocOp = op;
            }
            if (op.kind() == SemanticOpKind.BINDING_INIT
                    && ((KindPayload.BindingInitPayload) op.payload()).binding()
                        .equals(x.binding())) {
                xInit = op;
            }
        }
        check(gAllocOp != null && xInit != null
                && indexOf(ops, xInit.opId()) < indexOf(ops, gAllocOp.opId()),
            "g's ALLOC follows the earlier statements of outer's body (the declaration "
                + "position, after x's INIT)");
        check(initCount(ops, new SemanticLowerer.BindingSite(g.binding(), 0)) == 0,
            "g has no INIT in this child's window");
    }

    /** Catch bindings: ALLOC at the catch block's entry, generation 0, no INIT. */
    static void testCatchBindingAlloc() {
        System.out.println("-- catch bindings: ALLOC at the catch block's entry, no INIT --");

        SemanticLowerer.BindingCoreResult result = lowerSlice("""
            try {
                let x = 1;
            } catch (e) {
                let y = 2;
            }
            """);
        if (result == null) {
            return;
        }
        check(result.lowering() != null && !result.lowering().hasErrors(),
            "the try/catch slice lowers to a validated unit: "
                + (result.lowering() == null ? "null" : result.lowering().diagnostics()));
        if (result.lowering() == null || result.lowering().hasErrors()) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        List<SemanticOp> ops = unit.ops();

        List<SemanticOp> tryCatch = ofKind(ops, SemanticOpKind.TRY_CATCH);
        check(tryCatch.size() == 1, "exactly one TRY_CATCH op; got " + tryCatch.size());
        if (tryCatch.size() != 1) {
            return;
        }
        KindPayload.TryCatchPayload payload =
            (KindPayload.TryCatchPayload) tryCatch.get(0).payload();
        SemanticLowerer.BindingCoreBinding e = fact(result.facts(), "e");
        SemanticLowerer.BindingCoreBinding x = fact(result.facts(), "x");
        SemanticLowerer.BindingCoreBinding y = fact(result.facts(), "y");
        if (e == null || x == null || y == null) {
            return;
        }
        check(payload.catchBinding().equals(e.binding()),
            "the TRY_CATCH payload carries the catch binding identity");
        check(payload.tryBlock().equals(x.incarnations().get(0).scope())
                && payload.catchBlock().equals(y.incarnations().get(0).scope())
                && payload.catchBlock().equals(e.incarnations().get(0).scope()),
            "the try/catch block identities match the ALLOC scopes of their bindings");

        KindPayload.BindingAllocPayload catchAlloc = null;
        for (SemanticOp op : ofKind(ops, SemanticOpKind.BINDING_ALLOC)) {
            KindPayload.BindingAllocPayload alloc = (KindPayload.BindingAllocPayload) op.payload();
            if (alloc.binding().equals(e.binding())) {
                catchAlloc = alloc;
            }
        }
        check(catchAlloc != null, "the catch binding's ALLOC is present");
        if (catchAlloc != null) {
            check(catchAlloc.generation() == 0
                    && catchAlloc.cellKind() == BindingCellKind.DIRECT
                    && catchAlloc.mutable()
                    && catchAlloc.scope().equals(payload.catchBlock()),
                "the catch ALLOC carries {scope=catch block, generation 0, DIRECT, mutable}");
        }
        check(initCount(ops, new SemanticLowerer.BindingSite(e.binding(), 0)) == 0,
            "the catch binding has no BINDING_INIT (the catch-entry write is TRY_CATCH's, "
                + "ISSUE-0234)");
        check(initCount(ops, new SemanticLowerer.BindingSite(x.binding(), 0)) == 1
                && initCount(ops, new SemanticLowerer.BindingSite(y.binding(), 0)) == 1,
            "the try/catch block lets each get exactly one INIT");
    }

    /** Import aliases: ALLOC at module-init top, generation 0, no INIT. */
    static void testImportAliasAlloc() {
        System.out.println("-- import aliases: ALLOC at module entry, no INIT --");

        StubModuleResolver resolver = new StubModuleResolver();
        resolver.register("stub/util", Map.of());
        SemanticLowerer.BindingCoreResult result = lowerSlice("""
            import * as util from "stub/util";
            let a = 1;
            """, resolver);
        if (result == null) {
            return;
        }
        check(result.lowering() != null && !result.lowering().hasErrors(),
            "the import slice lowers to a validated unit: "
                + (result.lowering() == null ? "null" : result.lowering().diagnostics()));
        if (result.lowering() == null || result.lowering().hasErrors()) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        List<SemanticOp> ops = unit.ops();
        BlockId moduleInitBlock = unit.moduleInit().initBlock();

        SemanticLowerer.BindingCoreBinding util = fact(result.facts(), "util");
        if (util == null) {
            return;
        }
        KindPayload.BindingAllocPayload aliasAlloc = null;
        for (SemanticOp op : ofKind(ops, SemanticOpKind.BINDING_ALLOC)) {
            KindPayload.BindingAllocPayload alloc = (KindPayload.BindingAllocPayload) op.payload();
            if (alloc.binding().equals(util.binding())) {
                aliasAlloc = alloc;
            }
        }
        check(aliasAlloc != null, "the import alias's ALLOC is present");
        if (aliasAlloc != null) {
            check(aliasAlloc.scope().equals(moduleInitBlock)
                    && aliasAlloc.generation() == 0
                    && aliasAlloc.cellKind() == BindingCellKind.DIRECT
                    && !aliasAlloc.mutable(),
                "the import-alias ALLOC sits at module-init top with generation 0, DIRECT, "
                    + "not mutable (module aliases are not ordinary cells)");
        }
        check(initCount(ops, new SemanticLowerer.BindingSite(util.binding(), 0)) == 0,
            "the import alias has no BINDING_INIT (the MODULE_IMPORT completion write is "
                + "the modules epic's, ISSUE-0239)");
    }

    /**
     * The for-let two-incarnation shape: counter generation 0 (DIRECT) in
     * the LOOP init block, per-iteration generation 1 (SHARED_CELL) at the
     * body top with INIT from the generation-0 load; condition/update
     * references name generation 0 and body references generation 1.
     */
    static void testForLetTwoIncarnations() {
        System.out.println("-- for-let: two incarnations of one BindingId --");

        SemanticLowerer.BindingCoreResult result = lowerSlice("""
            for (let i = 0; i < 3; i = i + 1) {
                let x = i;
            }
            """);
        if (result == null) {
            return;
        }
        check(result.lowering() != null && !result.lowering().hasErrors(),
            "the for-let slice lowers to a validated unit: "
                + (result.lowering() == null ? "null" : result.lowering().diagnostics()));
        if (result.lowering() == null || result.lowering().hasErrors()) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        List<SemanticOp> ops = unit.ops();

        SemanticLowerer.BindingCoreBinding i = fact(result.facts(), "i");
        SemanticLowerer.BindingCoreBinding x = fact(result.facts(), "x");
        if (i == null || x == null) {
            return;
        }
        check(i.incarnations().size() == 2,
            "exactly two incarnations of one BindingId for the for-let counter; got "
                + i.incarnations().size());
        if (i.incarnations().size() != 2) {
            return;
        }
        SemanticLowerer.BindingCoreIncarnation counter = i.incarnations().get(0);
        SemanticLowerer.BindingCoreIncarnation perIteration = i.incarnations().get(1);
        check(counter.generation() == 0 && perIteration.generation() == 1,
            "counter generation 0, per-iteration generation 1");
        check(counter.cellKind() == BindingCellKind.DIRECT
                && perIteration.cellKind() == BindingCellKind.SHARED_CELL,
            "counter DIRECT, per-iteration SHARED_CELL (the closed special cases)");
        check(counter.producer() == SemanticLowerer.BindingProducer.BINDING_ALLOC
                && perIteration.producer() == SemanticLowerer.BindingProducer.BINDING_ALLOC,
            "both incarnations are produced by BINDING_ALLOC ops");

        List<SemanticOp> loops = ofKind(ops, SemanticOpKind.LOOP);
        check(loops.size() == 1, "exactly one LOOP op; got " + loops.size());
        if (loops.size() != 1) {
            return;
        }
        KindPayload.LoopPayload loop = (KindPayload.LoopPayload) loops.get(0).payload();
        check(loop.selector() == ControlSelector.FOR,
            "the LOOP payload selector is FOR");
        check(loop.initBlock().equals(counter.scope())
                && loop.bodyBlock().equals(perIteration.scope()),
            "counter ALLOC in the LOOP init block, per-iteration ALLOC in the body block");

        // The counter's ALLOC/INIT and the first condition production are
        // init-block members; the body-top INIT operand is the
        // generation-0 load.
        List<SemanticOp> iAllocs = new ArrayList<>();
        List<SemanticOp> iInits = new ArrayList<>();
        List<SemanticOp> iLoads = new ArrayList<>();
        for (SemanticOp op : ops) {
            switch (op.payload()) {
                case KindPayload.BindingAllocPayload alloc when alloc.binding()
                        .equals(i.binding()) -> iAllocs.add(op);
                case KindPayload.BindingInitPayload init when init.binding()
                        .equals(i.binding()) -> iInits.add(op);
                case KindPayload.BindingLoadPayload load when load.binding()
                        .equals(i.binding()) -> iLoads.add(op);
                default -> { }
            }
        }
        check(iAllocs.size() == 2 && iInits.size() == 2,
            "two ALLOCs and two INITs for the counter binding (one per incarnation)");
        if (iAllocs.size() != 2 || iInits.size() != 2) {
            return;
        }
        KindPayload.BindingAllocPayload counterAlloc =
            (KindPayload.BindingAllocPayload) iAllocs.get(0).payload();
        KindPayload.BindingAllocPayload perIterationAlloc =
            (KindPayload.BindingAllocPayload) iAllocs.get(1).payload();
        check(counterAlloc.generation() == 0 && counterAlloc.scope().equals(loop.initBlock())
                && counterAlloc.cellKind() == BindingCellKind.DIRECT && counterAlloc.mutable(),
            "counter ALLOC: generation 0 in the LOOP init block, DIRECT, mutable");
        check(perIterationAlloc.generation() == 1
                && perIterationAlloc.scope().equals(loop.bodyBlock())
                && perIterationAlloc.cellKind() == BindingCellKind.SHARED_CELL
                && perIterationAlloc.mutable(),
            "per-iteration ALLOC: generation 1 at the body top, SHARED_CELL, mutable");
        KindPayload.BindingInitPayload counterInit =
            (KindPayload.BindingInitPayload) iInits.get(0).payload();
        KindPayload.BindingInitPayload perIterationInit =
            (KindPayload.BindingInitPayload) iInits.get(1).payload();
        check(counterInit.generation() == 0 && perIterationInit.generation() == 1,
            "INIT generations 0 and 1");
        check(perIterationInit.value() != null,
            "the per-iteration INIT carries the generation-0 load operand");
        KindPayload.BindingLoadPayload carry = null;
        for (SemanticOp op : iLoads) {
            KindPayload.BindingLoadPayload load = (KindPayload.BindingLoadPayload) op.payload();
            if (load.generation() == 0 && load.binding().equals(i.binding())
                    && op.result() != null && op.result().equals(perIterationInit.value())) {
                carry = load;
            }
        }
        check(carry != null,
            "the body-top INIT operand equals a BINDING_LOAD naming {i, generation 0}");

        // The condition's first production and re-production reference the
        // generation-0 counter; the body reference is generation 1. The
        // update RHS's INT32_ADD is a third BINARY — only the two
        // INT32_LT comparisons are condition productions.
        List<SemanticOp> conditions = new ArrayList<>();
        for (SemanticOp op : ofKind(ops, SemanticOpKind.BINARY)) {
            if (op.payload() instanceof KindPayload.BinaryPayload binary
                    && binary.selector() instanceof deal.semantic.ir.BinarySelector selector
                    && selector == deal.semantic.ir.BinarySelector.INT32_LT) {
                conditions.add(op);
            }
        }
        check(conditions.size() == 2,
            "two comparison productions (first in the init block, re-test in the update "
                + "block); got " + conditions.size());
        long gen0Loads = 0;
        long gen1Loads = 0;
        for (SemanticOp op : iLoads) {
            KindPayload.BindingLoadPayload load = (KindPayload.BindingLoadPayload) op.payload();
            if (load.generation() == 0) {
                gen0Loads++;
            } else if (load.generation() == 1) {
                gen1Loads++;
            }
        }
        // The generation-0 loads: the carry load plus one load in each of
        // the two condition productions plus the update's RHS load — four.
        // The generation-1 load: the body's `let x = i;` initializer.
        check(gen0Loads == 4 && gen1Loads == 1,
            "generation-0 loads (carry + 2 condition reads + update RHS) and exactly one "
                + "generation-1 load (the body reference); got " + gen0Loads + "/" + gen1Loads);

        // The update's ASSIGN chain commits BINDING_STORE {i, 0}.
        List<SemanticOp> assigns = ofKind(ops, SemanticOpKind.ASSIGN);
        check(assigns.size() == 1, "exactly one ASSIGN chain (the update); got "
            + assigns.size());
        if (assigns.size() == 1) {
            KindPayload.AssignPayload assign = (KindPayload.AssignPayload) assigns.get(0).payload();
            SemanticOp storeOp = null;
            for (SemanticOp op : ops) {
                if (op.kind() == SemanticOpKind.BINDING_STORE
                        && assign.childOps().contains(op.opId())) {
                    storeOp = op;
                }
            }
            check(storeOp != null,
                "the VARIABLE chain carries its BINDING_STORE commit child");
            if (storeOp != null) {
                KindPayload.BindingStorePayload store =
                    (KindPayload.BindingStorePayload) storeOp.payload();
                check(store.binding().equals(i.binding()) && store.generation() == 0,
                    "the update's BINDING_STORE names {i, generation 0} (the dominant "
                        + "incarnation at the update site)");
            }
        }

        // The body's load of i names generation 1 (dominant in the body).
        KindPayload.BindingInitPayload xInit = null;
        for (SemanticOp op : ops) {
            if (op.kind() == SemanticOpKind.BINDING_INIT
                    && op.payload() instanceof KindPayload.BindingInitPayload init
                    && init.binding().equals(x.binding())) {
                xInit = init;
            }
        }
        check(xInit != null, "x's INIT is present");
        if (xInit != null) {
            for (SemanticOp op : iLoads) {
                if (op.result() != null && op.result().equals(xInit.value())) {
                    KindPayload.BindingLoadPayload load =
                        (KindPayload.BindingLoadPayload) op.payload();
                    check(load.generation() == 1,
                        "the body's load of the counter names generation 1");
                }
            }
        }

        // Order: init-block ops precede the LOOP op precede the body-block
        // ops precede the update-block ops.
        check(indexOf(ops, iAllocs.get(0).opId()) < indexOf(ops, loops.get(0).opId())
                && indexOf(ops, loops.get(0).opId()) < indexOf(ops, iAllocs.get(1).opId())
                && indexOf(ops, iAllocs.get(1).opId()) < indexOf(ops, assigns.get(0).opId()),
            "flat order: counter ALLOC, LOOP op, per-iteration ALLOC, then the update chain");
        check(loop.condition() != null
                && loop.condition().equals(conditions.get(0).result()),
            "the LOOP payload's condition names the init block's first condition production");
    }

    /** FOR_EACH: the op is the iteration binding's producing allocation, SHARED_CELL. */
    static void testForEachIterationBinding() {
        System.out.println("-- FOR_EACH: the iteration op is the producing allocation --");

        SemanticLowerer.BindingCoreResult result = lowerSlice("""
            for (let c: string of "abc") {
                let y = c;
            }
            """);
        if (result == null) {
            return;
        }
        check(result.lowering() != null && !result.lowering().hasErrors(),
            "the for-of slice lowers to a validated unit: "
                + (result.lowering() == null ? "null" : result.lowering().diagnostics()));
        if (result.lowering() == null || result.lowering().hasErrors()) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        List<SemanticOp> ops = unit.ops();

        List<SemanticOp> forEach = ofKind(ops, SemanticOpKind.FOR_EACH);
        check(forEach.size() == 1, "exactly one FOR_EACH op; got " + forEach.size());
        if (forEach.size() != 1) {
            return;
        }
        KindPayload.ForEachPayload payload =
            (KindPayload.ForEachPayload) forEach.get(0).payload();
        check(payload.mode() == IterationMode.STRING_SCALARS,
            "the FOR_EACH mode is STRING_SCALARS");

        SemanticLowerer.BindingCoreBinding c = fact(result.facts(), "c");
        SemanticLowerer.BindingCoreBinding y = fact(result.facts(), "y");
        if (c == null || y == null) {
            return;
        }
        check(c.incarnations().size() == 1 && c.incarnations().get(0).generation() == 0,
            "the iteration binding has one incarnation at generation 0");
        check(payload.binding().equals(c.binding()) && payload.generation() == 0,
            "the FOR_EACH payload carries the iteration binding's identity and generation "
                + "(its incarnation's ordinal)");
        check(c.incarnations().get(0).cellKind() == BindingCellKind.SHARED_CELL
                && c.incarnations().get(0).producer() == SemanticLowerer.BindingProducer.FOR_EACH,
            "the iteration binding is classified SHARED_CELL with the FOR_EACH op as its "
                + "producing allocation");
        boolean allocForC = false;
        for (SemanticOp op : ofKind(ops, SemanticOpKind.BINDING_ALLOC)) {
            if (((KindPayload.BindingAllocPayload) op.payload()).binding()
                    .equals(c.binding())) {
                allocForC = true;
            }
        }
        check(!allocForC,
            "no BINDING_ALLOC exists for the iteration binding (the FOR_EACH op is the "
                + "producing allocation; fresh cells come from its per-iteration allocation "
                + "semantics, never a new ordinal)");
        check(initCount(ops, new SemanticLowerer.BindingSite(c.binding(), 0)) == 0,
            "the iteration binding has no BINDING_INIT (the per-iteration transfer phase "
                + "writes the fresh cell)");

        // The body's load of c names {c, 0}.
        KindPayload.BindingInitPayload yInit = null;
        for (SemanticOp op : ops) {
            if (op.kind() == SemanticOpKind.BINDING_INIT
                    && op.payload() instanceof KindPayload.BindingInitPayload init
                    && init.binding().equals(y.binding())) {
                yInit = init;
            }
        }
        check(yInit != null, "y's INIT is present");
        if (yInit != null) {
            boolean bodyLoad = false;
            for (SemanticOp op : ops) {
                if (op.kind() == SemanticOpKind.BINDING_LOAD
                        && op.payload() instanceof KindPayload.BindingLoadPayload load
                        && load.binding().equals(c.binding())
                        && load.generation() == 0
                        && op.result() != null && op.result().equals(yInit.value())) {
                    bodyLoad = true;
                }
            }
            check(bodyLoad,
                "the body's load of the iteration binding names {c, generation 0}");
        }
    }

    /** LOAD/STORE payloads name the dominant incarnation at the site. */
    static void testLoadsAndStoresNameDominantIncarnation() {
        System.out.println("-- LOAD/STORE payloads name the dominant incarnation --");

        SemanticLowerer.BindingCoreResult result = lowerSlice("""
            let x = 1;
            let y = x;
            x = 2;
            """);
        if (result == null) {
            return;
        }
        check(result.lowering() != null && !result.lowering().hasErrors(),
            "the load/store slice lowers to a validated unit: "
                + (result.lowering() == null ? "null" : result.lowering().diagnostics()));
        if (result.lowering() == null || result.lowering().hasErrors()) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        List<SemanticOp> ops = unit.ops();

        SemanticLowerer.BindingCoreBinding x = fact(result.facts(), "x");
        SemanticLowerer.BindingCoreBinding y = fact(result.facts(), "y");
        if (x == null || y == null) {
            return;
        }
        check(x.incarnations().size() == 1 && x.incarnations().get(0).generation() == 0,
            "x has one incarnation at generation 0");
        // y's INIT operand: the load of x.
        KindPayload.BindingInitPayload yInit = null;
        for (SemanticOp op : ops) {
            if (op.kind() == SemanticOpKind.BINDING_INIT
                    && op.payload() instanceof KindPayload.BindingInitPayload init
                    && init.binding().equals(y.binding())) {
                yInit = init;
            }
        }
        check(yInit != null, "y's INIT is present");
        if (yInit != null) {
            boolean loadNamed = false;
            for (SemanticOp op : ops) {
                if (op.kind() == SemanticOpKind.BINDING_LOAD
                        && op.payload() instanceof KindPayload.BindingLoadPayload load
                        && load.binding().equals(x.binding()) && load.generation() == 0
                        && op.result() != null && op.result().equals(yInit.value())) {
                    loadNamed = true;
                }
            }
            check(loadNamed,
                "y's initializer load names {x, generation 0} (the dominant incarnation)");
        }
        // The assignment statement commits BINDING_STORE {x, 0} through the
        // VARIABLE chain.
        List<SemanticOp> assigns = ofKind(ops, SemanticOpKind.ASSIGN);
        check(assigns.size() == 1, "exactly one ASSIGN chain; got " + assigns.size());
        if (assigns.size() == 1) {
            KindPayload.AssignPayload assign = (KindPayload.AssignPayload) assigns.get(0).payload();
            SemanticOp storeOp = null;
            for (SemanticOp op : ops) {
                if (op.kind() == SemanticOpKind.BINDING_STORE
                        && assign.childOps().contains(op.opId())) {
                    storeOp = op;
                }
            }
            check(storeOp != null, "the VARIABLE chain carries its BINDING_STORE commit");
            if (storeOp != null) {
                KindPayload.BindingStorePayload store =
                    (KindPayload.BindingStorePayload) storeOp.payload();
                check(store.binding().equals(x.binding()) && store.generation() == 0,
                    "the assignment's BINDING_STORE names {x, generation 0}");
            }
        }
    }

    /** Determinism: identical BindingIds, generations, and payload fields across runs. */
    static void testDeterminism() {
        System.out.println("-- determinism: identical ids, generations, and payload fields --");

        String source = """
            import * as util from "stub/util";
            let a = 1;
            function f(p: int): null {
                let q = p;
            }
            for (let i = 0; i < 3; i = i + 1) {
                let s = i;
            }
            for (let c: string of "xy") {
                let t = c;
            }
            try {
                let u = 1;
            } catch (e) {
                let v = 2;
            }
            """;
        StubModuleResolver resolver = new StubModuleResolver();
        resolver.register("stub/util", Map.of());
        CheckedSlice slice = checkSlice(source, resolver);
        if (slice == null) {
            return;
        }
        SemanticLowerer.BindingCoreResult first = SemanticLowerer.lowerModuleBindingCore(
            moduleOf(slice), SemanticProfile.DEAL_V1_2_INT32, Map.of(), INTERFACE_HASH,
            REGISTRY_HASH, SemanticIdAllocator.over(List.of(MODULE)));
        SemanticLowerer.BindingCoreResult second = SemanticLowerer.lowerModuleBindingCore(
            moduleOf(slice), SemanticProfile.DEAL_V1_2_INT32, Map.of(), INTERFACE_HASH,
            REGISTRY_HASH, SemanticIdAllocator.over(List.of(MODULE)));
        check(first != null && !first.lowering().hasErrors()
                && second != null && !second.lowering().hasErrors(),
            "both runs lower to validated units");
        if (first == null || first.lowering().hasErrors()
                || second == null || second.lowering().hasErrors()) {
            return;
        }
        check(first.facts().equals(second.facts()),
            "two runs produce identical binding facts (BindingIds, generation ordinals, "
                + "cell kinds, mutability, producers)");
        byte[] firstDump = SemanticIrDumper.dumpModule(first.lowering().unit());
        byte[] secondDump = SemanticIrDumper.dumpModule(second.lowering().unit());
        check(Arrays.equals(firstDump, secondDump),
            "two runs produce byte-identical unit dumps (ids and payload fields included)");
    }

    /** Fail-closed negatives: foreign constructs and the profile guard. */
    static void testFailClosedNegatives() {
        System.out.println("-- fail-closed negatives --");

        // (a) A foreign statement (if) fails hard with CONSTRUCT_UNLOWERED.
        SemanticLowerer.BindingCoreResult foreign = lowerSlice("if (true) {}");
        if (foreign != null) {
            check(foreign.lowering().hasErrors() && foreign.lowering().unit() == null,
                "an if statement fails hard with no unit");
            if (foreign.lowering().hasErrors()) {
                CompilerDiagnostic diagnostic = foreign.lowering().diagnostics().get(0);
                check("E6005".equals(diagnostic.code())
                        && diagnostic.diagnosticCode() == DiagnosticCode.E6005
                        && "error".equals(diagnostic.severity()),
                    "the foreign construct converts to an error-severity E6005");
                check(diagnostic.message().contains(SemanticLowerer.CONSTRUCT_UNLOWERED),
                    "the E6005 names CONSTRUCT_UNLOWERED: " + diagnostic.message());
            }
        }

        // (b) A for without a let initializer is outside the window.
        SemanticLowerer.BindingCoreResult nonLetFor = lowerSlice("""
            let n = 3;
            for (n = 0; n < 3; n = n + 1) {}
            """);
        if (nonLetFor != null) {
            check(nonLetFor.lowering().hasErrors() && nonLetFor.lowering().unit() == null,
                "a for without a let initializer fails hard with no unit");
            if (nonLetFor.lowering().hasErrors()) {
                check(nonLetFor.lowering().diagnostics().get(0).message()
                        .contains(SemanticLowerer.CONSTRUCT_UNLOWERED),
                    "the non-let for names CONSTRUCT_UNLOWERED");
            }
        }

        // (c) The I3 profile guard applies to the binding entry point too.
        CheckedSlice slice = checkSlice("let a = 1;");
        if (slice != null) {
            SemanticLowerer.BindingCoreResult legacy = SemanticLowerer.lowerModuleBindingCore(
                moduleOf(slice), SemanticProfile.LEGACY_SAFE_INT, Map.of(), INTERFACE_HASH,
                REGISTRY_HASH, SemanticIdAllocator.over(List.of(MODULE)));
            check(legacy != null && legacy.lowering().hasErrors()
                    && legacy.lowering().unit() == null
                    && legacy.facts().bindings().isEmpty(),
                "a LEGACY_SAFE_INT binding request fails hard with no unit and no facts");
            if (legacy != null && legacy.lowering().hasErrors()) {
                check(legacy.lowering().diagnostics().get(0).message()
                        .contains(SemanticLowerer.LOWER_LEGACY_PROFILE_REJECTED),
                    "the legacy profile rejection names the pinned profile-guard rule");
            }
        }
    }

    /** The first op-list index of an op id (producer defect when absent). */
    private static int indexOf(List<SemanticOp> ops, OpId opId) {
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i).opId().equals(opId)) {
                return i;
            }
        }
        fail("op " + opId + " not found in the produced op list");
        return -1;
    }

    // =========================================================================
    // Runner
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Binding Core Lowering Test (ISSUE-0444 binding-core child) ===\n");

        testLetDeclarationShape();
        testParametersAndHoistedFunctionAllocs();
        testIntrinsicBindingsAtModuleTop();
        testNestedFunctionAllocAtPosition();
        testCatchBindingAlloc();
        testImportAliasAlloc();
        testForLetTwoIncarnations();
        testForEachIterationBinding();
        testLoadsAndStoresNameDominantIncarnation();
        testDeterminism();
        testFailClosedNegatives();

        System.out.println("\nBinding core lowering: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
