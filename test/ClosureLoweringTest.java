package deal.test;

import deal.ast.Block;
import deal.ast.FunctionDeclaration;
import deal.ast.LiteralExpr;
import deal.ast.LiteralValue;
import deal.ast.NamedType;
import deal.ast.ProgramNode;
import deal.ast.Span;
import deal.ast.VariableDeclaration;
import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.NameResolver;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CheckedModuleInput;
import deal.semantic.CheckedModuleKind;
import deal.semantic.ContainerPayloadDescriptors;
import deal.semantic.SemanticLowerer;
import deal.semantic.ir.BindingCellKind;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.ExternalModuleKind;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FunctionAllocationIdentity;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.InitializationMode;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredFunction;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticIdAllocator;
import deal.semantic.ir.SemanticIrDumper;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;
import deal.types.Type;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ClosureLoweringTest {

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

    private static final String HOISTED_CAPTURE_SOURCE = """
        function first(): null {
          let h: () => null = function(): null {
            let copy: () => null = laterFn;
          };
        }
        function laterFn(): null {}
        """;

    // =========================================================================
    // Checked-source slices and the entry-point driver
    // =========================================================================

    private static CheckedSlice checkSlice(String source) {
        LexResult lex = new Lexer(source, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal", lex.directiveEvents()).parse();
        check(parse.diagnostics().isEmpty(), "the slice parses cleanly: " + parse.diagnostics());
        if (!parse.diagnostics().isEmpty()) {
            return null;
        }
        NameResolver nr = new NameResolver("test.deal", null);
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

    private static SemanticLowerer.ClosureCoreResult lowerSlice(String source) {
        return lowerSlice(checkSlice(source));
    }

    private static SemanticLowerer.ClosureCoreResult lowerSlice(CheckedSlice slice) {
        if (slice == null) {
            return null;
        }
        return SemanticLowerer.lowerModuleClosureCore(moduleOf(slice),
            SemanticProfile.DEAL_V1_3_INT32, Map.of(), INTERFACE_HASH, REGISTRY_HASH,
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

    /** The closure facts record of a produced {@code CLOSURE_NEW} by function name's facts. */
    private static SemanticLowerer.ClosureFacts closureOf(
            List<SemanticLowerer.ClosureFacts> closures, SemanticOp closureNew) {
        KindPayload.ClosureNewPayload payload =
            (KindPayload.ClosureNewPayload) closureNew.payload();
        List<SemanticLowerer.ClosureFacts> matches = new ArrayList<>();
        for (SemanticLowerer.ClosureFacts facts : closures) {
            if (facts.functionId().equals(payload.function())) {
                matches.add(facts);
            }
        }
        check(matches.size() == 1,
            "exactly one closure-facts record for CLOSURE_NEW " + closureNew.opId()
                + "; got " + matches.size());
        return matches.size() == 1 ? matches.get(0) : null;
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

    /** The CLOSURE_NEW op of a lowered function, by its FunctionId. */
    private static SemanticOp closureNewOf(List<SemanticOp> ops, FunctionId functionId) {
        List<SemanticOp> matches = new ArrayList<>();
        for (SemanticOp op : ops) {
            if (op.kind() == SemanticOpKind.CLOSURE_NEW
                    && op.payload() instanceof KindPayload.ClosureNewPayload payload
                    && payload.function().equals(functionId)) {
                matches.add(op);
            }
        }
        check(matches.size() == 1, "exactly one CLOSURE_NEW for FunctionId " + functionId
            + "; got " + matches.size());
        return matches.size() == 1 ? matches.get(0) : null;
    }

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
    // Tests
    // =========================================================================

    /**
     * A closure capturing two enclosing locals: the capture list carries
     * the bindings in first-reference order, each capture resolves to the
     * dominant incarnation at the creation site (generation 0, the
     * enclosing body block, producer BINDING_ALLOC), the payload and the
     * {@code LoweredFunction} record agree, exactly one
     * {@code LoweredBody} binding is registered per closure keyed by the
     * allocation identity (equal to the CLOSURE_NEW result id), and the
     * captured cells upgrade to {@code SHARED_CELL} while the
     * uncaptured locals stay {@code DIRECT}.
     */
    static void testClosureCapturesEnclosingLocalsInFirstReferenceOrder() {
        System.out.println("-- captures: first-reference order, resolved generation, "
            + "registry one-to-one --");

        SemanticLowerer.ClosureCoreResult result = lowerSlice("""
            function outer(): null {
              let x: number = 1;
              let y: number = 2;
              let h: () => null = function(): null {
                let a: number = y;
                let b: number = x;
              };
            }
            """);
        if (result == null || result.lowering().hasErrors() || result.lowering().unit() == null) {
            if (result != null) {
                fail("the slice lowers to a validated unit: " + result.lowering().diagnostics());
            }
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        List<SemanticOp> ops = unit.ops();
        SemanticLowerer.BindingCoreBinding x = fact(result.bindingFacts(), "x");
        SemanticLowerer.BindingCoreBinding y = fact(result.bindingFacts(), "y");
        SemanticLowerer.BindingCoreBinding h = fact(result.bindingFacts(), "h");
        if (x == null || y == null || h == null) {
            return;
        }

        // Exactly three closures: outer, h (and none other); h's CLOSURE_NEW
        // carries the ordered captures [y, x].
        List<SemanticOp> closureNews = ofKind(ops, SemanticOpKind.CLOSURE_NEW);
        check(closureNews.size() == 2, "one CLOSURE_NEW per function declaration/expression "
            + "(outer + h); got " + closureNews.size());
        if (closureNews.size() != 2) {
            return;
        }
        SemanticOp hClosure = null;
        for (SemanticOp op : closureNews) {
            if (!(op.payload() instanceof KindPayload.ClosureNewPayload payload)) {
                fail("CLOSURE_NEW payload shape (producer defect)");
                continue;
            }
            if (payload.captureBindings().contains(y.binding())) {
                hClosure = op;
            }
        }
        check(hClosure != null, "h's CLOSURE_NEW found (its captures include y)");
        if (hClosure == null) {
            return;
        }
        KindPayload.ClosureNewPayload hPayload =
            (KindPayload.ClosureNewPayload) hClosure.payload();
        check(hPayload.captureBindings().equals(List.of(y.binding(), x.binding())),
            "captures = [y, x] in first-reference order; got " + hPayload.captures());
        check(hClosure.result() != null
                && hClosure.resultType() instanceof RuntimeDescriptor.Func func
                && func.equals(new RuntimeDescriptor.Func(List.of(),
                    RuntimeDescriptor.Null.INSTANCE)),
            "h's CLOSURE_NEW result type is the exact declared signature () -> null");

        // The LoweredFunction record agrees with the payload.
        LoweredFunction hFunction = unit.functions().get(hPayload.function());
        check(hFunction != null, "the unit's functions map records h's LoweredFunction");
        if (hFunction != null) {
            check(hFunction.functionId().equals(hPayload.function())
                    && hFunction.descriptor().equals(hPayload.signature())
                    && hFunction.captures().equals(hPayload.captures())
                    && hFunction.body().equals(hPayload.binding().blockId()),
                "LoweredFunction {functionId, descriptor, captures, body} equals the "
                    + "CLOSURE_NEW payload fields");
        }

        // The closure facts: each capture resolved at the creation site.
        SemanticLowerer.ClosureFacts hFacts = closureOf(result.closures(), hClosure);
        if (hFacts != null) {
            check(hFacts.captures().size() == 2, "h resolves exactly two captures");
            if (hFacts.captures().size() == 2) {
                SemanticLowerer.ClosureCapture first = hFacts.captures().get(0);
                SemanticLowerer.ClosureCapture second = hFacts.captures().get(1);
                BlockId outerBody = x.incarnations().get(0).scope();
                check(first.binding().equals(y.binding()) && first.generation() == 0
                        && first.scope().equals(outerBody)
                        && first.producer() == SemanticLowerer.BindingProducer.BINDING_ALLOC,
                    "the first capture resolves y to generation 0 in outer's body block "
                        + "via its producing ALLOC: " + first);
                check(second.binding().equals(x.binding()) && second.generation() == 0
                        && second.scope().equals(outerBody)
                        && second.producer() == SemanticLowerer.BindingProducer.BINDING_ALLOC,
                    "the second capture resolves x to generation 0 in outer's body block "
                        + "via its producing ALLOC: " + second);
            }
        }

        // Registry one-to-one: exactly one LoweredBody per closure, keyed
        // by the allocation identity equal to the result value id.
        int hBindings = 0;
        for (FunctionExecutionBinding binding : unit.functionBindings().values()) {
            if (binding instanceof FunctionExecutionBinding.LoweredBody body
                    && body.functionId().equals(hPayload.function())) {
                hBindings++;
            }
        }
        check(hBindings == 1, "exactly one LoweredBody registration for h; got " + hBindings);
        check(unit.functionBindings().containsKey(
                new FunctionAllocationIdentity(((ValueId) hClosure.result()).id())),
            "h's binding is keyed by the allocation identity equal to the result value id");
        check(unit.functionBindings().size() == closureNews.size(),
            "one functionBinding per closure: " + unit.functionBindings().size()
                + " bindings for " + closureNews.size() + " closures");

        // Cell kinds: captured x/y SHARED_CELL; uncaptured a, b, h DIRECT.
        check(x.incarnations().get(0).cellKind() == BindingCellKind.SHARED_CELL,
            "x is SHARED_CELL (closure-captured)");
        check(y.incarnations().get(0).cellKind() == BindingCellKind.SHARED_CELL,
            "y is SHARED_CELL (closure-captured)");
        check(h.incarnations().get(0).cellKind() == BindingCellKind.DIRECT,
            "h is DIRECT (uncaptured)");
        SemanticLowerer.BindingCoreBinding a = fact(result.bindingFacts(), "a");
        SemanticLowerer.BindingCoreBinding b = fact(result.bindingFacts(), "b");
        if (a != null) {
            check(a.incarnations().get(0).cellKind() == BindingCellKind.DIRECT,
                "a is DIRECT (declared inside the closure body)");
        }
        if (b != null) {
            check(b.incarnations().get(0).cellKind() == BindingCellKind.DIRECT,
                "b is DIRECT (declared inside the closure body)");
        }
        // The rewritten ALLOC payloads carry the final derived kinds.
        List<SemanticOp> allocs = ofKind(ops, SemanticOpKind.BINDING_ALLOC);
        int xShared = 0;
        int yShared = 0;
        for (SemanticOp op : allocs) {
            if (op.payload() instanceof KindPayload.BindingAllocPayload alloc) {
                if (alloc.binding().equals(x.binding())) {
                    xShared += alloc.cellKind() == BindingCellKind.SHARED_CELL ? 1 : 0;
                } else if (alloc.binding().equals(y.binding())) {
                    yShared += alloc.cellKind() == BindingCellKind.SHARED_CELL ? 1 : 0;
                }
            }
        }
        check(xShared == 1 && yShared == 1,
            "x's and y's ALLOC payloads carry the re-derived SHARED_CELL kind "
                + "(x: " + xShared + ", y: " + yShared + ")");
    }

    /**
     * The doubly-nested capture chain of
     * {@code test/conformance/backend-runtime/closures/nested-closure-mutation.deal}
     * (runtime-ok across luajit/jvm/js) in its window-legal shape: the
     * fixture's {@code return}/{@code call} statements belong to the
     * control-flow and call epics' windows, so the equivalent window
     * shape — a nested function declaration {@code make} whose body
     * creates the inner closure — pins the same detaching chain. The
     * inner closure's capture of {@code x} resolves through make's body
     * (where {@code x} is itself no local) to {@code x}'s producing
     * ALLOC/INIT in {@code test_nested_closure_mutation}'s body, and
     * the same reference is a free binding of make's own body region —
     * the {@code CLOSURE_NEW} creation site of the inner closure sits
     * inside make's body — so make's capture set carries {@code x} too
     * (B9 R2(ii): the chain is inner-body capture → make's body capture
     * → the closing R1 step in test's body).
     */
    static void testDoublyNestedCaptureAlongDetachingChain() {
        System.out.println("-- doubly-nested capture: nested-closure-mutation.deal chain "
            + "(window shape) --");

        SemanticLowerer.ClosureCoreResult result = lowerSlice("""
            function test_nested_closure_mutation(): null {
              let x: number = 1;
              function make(): null {
                let inner: () => null = function(): null {
                  let y: number = x;
                };
              }
              x = 9;
            }
            """);
        if (result == null || result.lowering().hasErrors() || result.lowering().unit() == null) {
            if (result != null) {
                fail("the slice lowers to a validated unit: " + result.lowering().diagnostics());
            }
            return;
        }
        List<SemanticOp> ops = result.lowering().unit().ops();
        SemanticLowerer.BindingCoreBinding x = fact(result.bindingFacts(), "x");
        SemanticLowerer.BindingCoreBinding make = fact(result.bindingFacts(), "make");
        if (x == null || make == null) {
            return;
        }

        List<SemanticOp> closureNews = ofKind(ops, SemanticOpKind.CLOSURE_NEW);
        check(closureNews.size() == 3,
            "one CLOSURE_NEW per function (test_nested_closure_mutation, make, inner); got "
                + closureNews.size());
        if (closureNews.size() != 3) {
            return;
        }
        // Creation order is deterministic: test_nested_closure_mutation
        // (module-level declaration) first, make (declaration inside
        // test's body) second, inner (function expression inside make's
        // body) third — inner's CLOSURE_NEW is emitted into make's body
        // buffer, which flushes after make's declaration pair.
        SemanticOp testClosureNew = closureNews.get(0);
        SemanticOp makeClosureNew = closureNews.get(1);
        SemanticOp innerClosureNew = closureNews.get(2);
        KindPayload.ClosureNewPayload testPayload =
            (KindPayload.ClosureNewPayload) testClosureNew.payload();
        KindPayload.ClosureNewPayload makePayload =
            (KindPayload.ClosureNewPayload) makeClosureNew.payload();
        KindPayload.ClosureNewPayload innerPayload =
            (KindPayload.ClosureNewPayload) innerClosureNew.payload();
        check(testPayload.captures().isEmpty(),
            "test_nested_closure_mutation's CLOSURE_NEW payload carries no captures");
        check(makePayload.captureBindings().equals(List.of(x.binding())),
            "make's CLOSURE_NEW payload carries captures = [x] (the inner closure's "
                + "creation site lies inside make's body, so x is a free binding of "
                + "make's body region too — B9 R2(ii)); got " + makePayload.captures());
        check(innerPayload.captureBindings().equals(List.of(x.binding())),
            "inner's CLOSURE_NEW payload carries captures = [x]");

        // The closure facts records agree with the payloads: make records
        // captures = [x], inner records captures = [x].
        SemanticLowerer.ClosureFacts testFacts = closureOf(result.closures(), testClosureNew);
        SemanticLowerer.ClosureFacts makeFacts = closureOf(result.closures(), makeClosureNew);
        SemanticLowerer.ClosureFacts innerFacts = closureOf(result.closures(), innerClosureNew);
        if (testFacts == null || makeFacts == null || innerFacts == null) {
            return;
        }
        check(testFacts.captures().isEmpty(),
            "test_nested_closure_mutation's closure facts record no captures");
        check(makeFacts.captures().size() == 1
                && makeFacts.captures().get(0).binding().equals(x.binding()),
            "make's closure facts record captures = [x] (detaching-chain propagation)");
        check(innerFacts.captures().size() == 1
                && innerFacts.captures().get(0).binding().equals(x.binding()),
            "inner's closure facts record captures = [x]");

        BlockId enclosingBody = x.incarnations().get(0).scope();
        SemanticLowerer.ClosureCapture makeCapture = makeFacts.captures().get(0);
        SemanticLowerer.ClosureCapture innerCapture = innerFacts.captures().get(0);
        check(makeCapture.generation() == 0
                && makeCapture.scope().equals(enclosingBody)
                && makeCapture.producer() == SemanticLowerer.BindingProducer.BINDING_ALLOC,
            "make's capture of x resolves along the detaching chain to x's producing "
                + "ALLOC in test_nested_closure_mutation's body (generation 0, scope "
                + enclosingBody + "): " + makeCapture);
        check(makeCapture.binding().equals(x.binding()),
            "make's capture names x's BindingId (capture by binding, never a copied value)");
        check(innerCapture.generation() == 0
                && innerCapture.scope().equals(enclosingBody)
                && innerCapture.producer() == SemanticLowerer.BindingProducer.BINDING_ALLOC,
            "inner's capture of x resolves along the detaching chain to x's producing "
                + "ALLOC in test_nested_closure_mutation's body (generation 0, scope "
                + enclosingBody + "): " + innerCapture);
        check(innerCapture.binding().equals(x.binding()),
            "the capture names x's BindingId (capture by binding, never a copied value)");

        // The load inside inner's body names {x, 0} — the chain-closing
        // incarnation.
        List<SemanticOp> loads = ofKind(ops, SemanticOpKind.BINDING_LOAD);
        int xLoads = 0;
        for (SemanticOp op : loads) {
            if (op.payload() instanceof KindPayload.BindingLoadPayload load
                    && load.binding().equals(x.binding())
                    && load.generation() == 0) {
                xLoads++;
            }
        }
        check(xLoads == 1, "exactly one generation-0 load of x (inside inner's body); got "
            + xLoads);

        // Cell kinds: x SHARED_CELL; make DIRECT (its own name is never
        // captured — make's capture list names x, not make itself).
        check(x.incarnations().get(0).cellKind() == BindingCellKind.SHARED_CELL,
            "x is SHARED_CELL (captured by the inner closure through the chain)");
        check(make.incarnations().get(0).cellKind() == BindingCellKind.DIRECT,
            "make is DIRECT (its own name is never captured — the chain terminates at x)");
    }

    /**
     * Transitive propagation across deeper detaching chains (B9 R2(ii)):
     * a closure created inside a nested detached body registers its
     * capture into every enclosing open body whose capture border the
     * resolution lies outside — triple nesting propagates {@code x}
     * (declared in {@code outer}) through {@code mid}'s and
     * {@code inner}'s capture lists, while a binding declared inside
     * {@code mid}'s own body ({@code z}) stays out of {@code mid}'s
     * captures (it is mid's own scope chain, so the chain closes there).
     */
    static void testTransitivelyNestedCapturePropagation() {
        System.out.println("-- transitive detaching-chain propagation: triple nesting --");

        SemanticLowerer.ClosureCoreResult result = lowerSlice("""
            function outer(): null {
              let x: number = 1;
              function mid(): null {
                let z: number = 2;
                function inner(): null {
                  let a: number = x;
                  let b: number = z;
                }
              }
              x = 9;
            }
            """);
        if (result == null || result.lowering().hasErrors() || result.lowering().unit() == null) {
            if (result != null) {
                fail("the slice lowers to a validated unit: " + result.lowering().diagnostics());
            }
            return;
        }
        List<SemanticOp> ops = result.lowering().unit().ops();
        SemanticLowerer.BindingCoreBinding x = fact(result.bindingFacts(), "x");
        SemanticLowerer.BindingCoreBinding z = fact(result.bindingFacts(), "z");
        if (x == null || z == null) {
            return;
        }
        List<SemanticOp> closureNews = ofKind(ops, SemanticOpKind.CLOSURE_NEW);
        check(closureNews.size() == 3,
            "one CLOSURE_NEW per function (outer, mid, inner); got " + closureNews.size());
        if (closureNews.size() != 3) {
            return;
        }
        // Creation order: outer (module-level) first, mid (inside outer's
        // body) second, inner (inside mid's body) third.
        SemanticOp outerClosureNew = closureNews.get(0);
        SemanticOp midClosureNew = closureNews.get(1);
        SemanticOp innerClosureNew = closureNews.get(2);
        KindPayload.ClosureNewPayload outerPayload =
            (KindPayload.ClosureNewPayload) outerClosureNew.payload();
        KindPayload.ClosureNewPayload midPayload =
            (KindPayload.ClosureNewPayload) midClosureNew.payload();
        KindPayload.ClosureNewPayload innerPayload =
            (KindPayload.ClosureNewPayload) innerClosureNew.payload();
        check(outerPayload.captures().isEmpty(),
            "outer's CLOSURE_NEW payload carries no captures (x is outer's own local)");
        check(midPayload.captureBindings().equals(List.of(x.binding())),
            "mid's CLOSURE_NEW payload carries captures = [x] (transitive propagation; z "
                + "is mid's own local, so it never enters mid's captures)");
        check(innerPayload.captureBindings().equals(List.of(x.binding(), z.binding())),
            "inner's CLOSURE_NEW payload carries captures = [x, z] in first-reference "
                + "order; got " + innerPayload.captures());

        SemanticLowerer.ClosureFacts outerFacts = closureOf(result.closures(), outerClosureNew);
        SemanticLowerer.ClosureFacts midFacts = closureOf(result.closures(), midClosureNew);
        SemanticLowerer.ClosureFacts innerFacts = closureOf(result.closures(), innerClosureNew);
        if (outerFacts == null || midFacts == null || innerFacts == null) {
            return;
        }
        check(outerFacts.captures().isEmpty(),
            "outer's closure facts record no captures");
        check(midFacts.captures().size() == 1
                && midFacts.captures().get(0).binding().equals(x.binding()),
            "mid's closure facts record captures = [x] (transitive propagation)");
        check(innerFacts.captures().size() == 2
                && innerFacts.captures().get(0).binding().equals(x.binding())
                && innerFacts.captures().get(1).binding().equals(z.binding()),
            "inner's closure facts record captures = [x, z] in first-reference order");
        if (midFacts.captures().size() == 1 && innerFacts.captures().size() == 2) {
            BlockId outerBody = x.incarnations().get(0).scope();
            BlockId midBody = z.incarnations().get(0).scope();
            SemanticLowerer.ClosureCapture midCapture = midFacts.captures().get(0);
            SemanticLowerer.ClosureCapture innerX = innerFacts.captures().get(0);
            SemanticLowerer.ClosureCapture innerZ = innerFacts.captures().get(1);
            check(midCapture.generation() == 0 && midCapture.scope().equals(outerBody)
                    && midCapture.producer() == SemanticLowerer.BindingProducer.BINDING_ALLOC,
                "mid's capture of x resolves to x's producing ALLOC in outer's body: "
                    + midCapture);
            check(innerX.generation() == 0 && innerX.scope().equals(outerBody)
                    && innerX.producer() == SemanticLowerer.BindingProducer.BINDING_ALLOC,
                "inner's capture of x resolves through the chain to x's producing ALLOC "
                    + "in outer's body: " + innerX);
            check(innerZ.generation() == 0 && innerZ.scope().equals(midBody)
                    && innerZ.producer() == SemanticLowerer.BindingProducer.BINDING_ALLOC,
                "inner's capture of z resolves to z's producing ALLOC in mid's body "
                    + "(the chain closes in mid's own scope): " + innerZ);
        }

        // Cell kinds: x SHARED_CELL; z SHARED_CELL (captured by inner);
        // mid DIRECT (its own name is never captured).
        check(x.incarnations().get(0).cellKind() == BindingCellKind.SHARED_CELL,
            "x is SHARED_CELL (captured through the chain)");
        check(z.incarnations().get(0).cellKind() == BindingCellKind.SHARED_CELL,
            "z is SHARED_CELL (captured by inner)");
        SemanticLowerer.BindingCoreBinding mid = fact(result.bindingFacts(), "mid");
        if (mid != null) {
            check(mid.incarnations().get(0).cellKind() == BindingCellKind.DIRECT,
                "mid is DIRECT (its own name is never captured)");
        }
    }

    /**
     * A closure capturing a later-declared module function: the capture
     * resolves to the hoisted module-init ALLOC (B1; docs/spec-v1.3.md
     * §Name resolution — Declaration order), the hoisted ALLOC upgrades
     * to {@code SHARED_CELL}, and the function-typed load inside the earlier closure body
     * publishes the pre-allocated hoist-time identity that the
     * declaration-position {@code CLOSURE_NEW} reuses (loads preserve
     * allocation identity — R-FUNCTION-BINDING holds by construction).
     */
    static void testCaptureOfLaterDeclaredModuleFunctionResolvesToHoistedAlloc(
            SemanticLowerer.ClosureCoreResult result) {
        System.out.println("-- later-declared module function: hoisted ALLOC resolution and "
            + "identity preservation --");
        if (result == null || result.lowering().hasErrors() || result.lowering().unit() == null) {
            if (result != null) {
                fail("the slice lowers to a validated unit: " + result.lowering().diagnostics());
            }
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        List<SemanticOp> ops = unit.ops();
        SemanticLowerer.BindingCoreBinding laterFn = fact(result.bindingFacts(), "laterFn");
        SemanticLowerer.BindingCoreBinding first = fact(result.bindingFacts(), "first");
        if (laterFn == null || first == null) {
            return;
        }
        BlockId moduleInitBlock = first.incarnations().get(0).scope();

        // laterFn's incarnation sits in the module-init block (hoisted).
        check(laterFn.incarnations().size() == 1
                && laterFn.incarnations().get(0).generation() == 0
                && laterFn.incarnations().get(0).scope().equals(moduleInitBlock),
            "laterFn's ALLOC is hoisted to the module-init block at generation 0");
        check(laterFn.incarnations().get(0).cellKind() == BindingCellKind.SHARED_CELL,
            "the hoisted ALLOC upgraded to SHARED_CELL (captured by h)");

        // The capture resolution: h captures laterFn at the hoisted
        // ALLOC, and — h's creation site lies inside first's body — the
        // detaching-chain propagation records laterFn in first's captures
        // too (B9 R2(ii): the chain closes h's body capture → first's
        // body capture → the R1 step at the hoisted module-init ALLOC).
        List<SemanticLowerer.ClosureFacts> laterFnCapturers = new ArrayList<>();
        for (SemanticLowerer.ClosureFacts facts : result.closures()) {
            if (facts.captures().size() == 1
                    && facts.captures().get(0).binding().equals(laterFn.binding())) {
                laterFnCapturers.add(facts);
            }
        }
        check(laterFnCapturers.size() == 2,
            "exactly two closure-facts records carry captures = [laterFn] (h and first, "
                + "the detaching-chain propagation); got " + laterFnCapturers.size());
        for (SemanticLowerer.ClosureFacts facts : laterFnCapturers) {
            SemanticLowerer.ClosureCapture capture = facts.captures().get(0);
            check(capture.generation() == 0
                    && capture.scope().equals(moduleInitBlock)
                    && capture.producer() == SemanticLowerer.BindingProducer.BINDING_ALLOC,
                "each chain capture of laterFn resolves to the hoisted ALLOC (generation "
                    + "0, module-init block): " + capture);
        }
        int laterFnPayloadCaptures = 0;
        for (SemanticOp op : ofKind(ops, SemanticOpKind.CLOSURE_NEW)) {
            if (op.payload() instanceof KindPayload.ClosureNewPayload payload
                    && payload.captureBindings().equals(List.of(laterFn.binding()))) {
                laterFnPayloadCaptures++;
            }
        }
        check(laterFnPayloadCaptures == 2,
            "two CLOSURE_NEW payloads carry captures = [laterFn] (h's and first's); got "
                + laterFnPayloadCaptures);

        // Identity preservation: the load of laterFn inside h's body
        // publishes the same result identity as laterFn's own CLOSURE_NEW
        // (the hoist-time pre-allocated identity).
        List<SemanticOp> closureNews = ofKind(ops, SemanticOpKind.CLOSURE_NEW);
        SemanticOp laterClosure = null;
        for (SemanticOp op : closureNews) {
            if (op.payload() instanceof KindPayload.ClosureNewPayload payload
                    && op.result() != null) {
                // laterFn's closure: its body block carries no statements
                // and its result identity equals the hoisted pre-allocation.
                for (SemanticOp load : ofKind(ops, SemanticOpKind.BINDING_LOAD)) {
                    if (load.payload() instanceof KindPayload.BindingLoadPayload loadPayload
                            && loadPayload.binding().equals(laterFn.binding())
                            && load.result() != null && load.result().equals(op.result())) {
                        laterClosure = op;
                    }
                }
            }
        }
        check(laterClosure != null,
            "laterFn's CLOSURE_NEW reuses the pre-allocated hoist-time result identity "
                + "(a load of laterFn publishes the same identity)");
        if (laterClosure != null) {
            check(unit.functionBindings().containsKey(
                    new FunctionAllocationIdentity(((ValueId) laterClosure.result()).id())),
                "laterFn's binding is keyed by the shared allocation identity");
            int matching = 0;
            for (FunctionAllocationIdentity identity : unit.functionBindings().keySet()) {
                if (identity.id() == ((ValueId) laterClosure.result()).id()) {
                    matching++;
                }
            }
            check(matching == 1, "exactly one binding per allocation identity");
            // The load of laterFn carries the declared function descriptor.
            for (SemanticOp load : ofKind(ops, SemanticOpKind.BINDING_LOAD)) {
                if (load.payload() instanceof KindPayload.BindingLoadPayload loadPayload
                        && loadPayload.binding().equals(laterFn.binding())) {
                    check(load.resultType() instanceof RuntimeDescriptor.Func func
                            && func.equals(new RuntimeDescriptor.Func(List.of(),
                                RuntimeDescriptor.Null.INSTANCE)),
                        "the load of laterFn carries the declared signature descriptor "
                            + "() -> null");
                }
            }
        }

        // Ordering: the hoisted ALLOC precedes first's CLOSURE_NEW (the
        // forward reference validates at module-init completion, B9).
        List<SemanticOp> allocs = ofKind(ops, SemanticOpKind.BINDING_ALLOC);
        OpId hoistedAlloc = null;
        for (SemanticOp op : allocs) {
            if (op.payload() instanceof KindPayload.BindingAllocPayload alloc
                    && alloc.binding().equals(laterFn.binding())) {
                hoistedAlloc = op.opId();
            }
        }
        check(hoistedAlloc != null, "laterFn's hoisted ALLOC op found");
        if (hoistedAlloc != null) {
            boolean allClosuresAfter = true;
            for (SemanticOp op : closureNews) {
                if (indexOf(ops, op.opId()) < indexOf(ops, hoistedAlloc)) {
                    allClosuresAfter = false;
                }
            }
            check(allClosuresAfter,
                "the hoisted ALLOC precedes every CLOSURE_NEW in the op list");
        }
    }

    /**
     * A closure created inside a for body captures the per-iteration
     * incarnation (generation 1, the body block's ALLOC) — combined with
     * the binding-core child's two-incarnation map: the counter
     * incarnation stays {@code DIRECT} and the per-iteration incarnation
     * is {@code SHARED_CELL} (pinned), body loads name generation 1.
     */


    /**
     * The for-let per-iteration carry transfer (ISSUE-0863): the update
     * block opens with the synthetic generation-1 load of the counter and
     * the generation-0 store of that loaded value, before the update chain
     * and the condition re-production — so a body store reaches the
     * iteration's update and the subsequent test (the spec's
     * {@code for}-to-{@code while} desugaring, docs/spec-v1.3.md
     * §Control flow — For (C-style)). The update-less row carries the same
     * transfer before its condition re-production, and the body's own
     * incarnation stays the captured generation-1 cell.
     */


    /** One for-let slice's carry-transfer shape (the update block's first two ops). */


    /**
     * The function-valued for-let counter (ISSUE-0864): the loop-head
     * {@code let} of the binding-core window admits a function type, its
     * declaration carries the annotated {@code VARIABLE_DECLARATION}
     * boundary on the function policy before the single INIT, and the two
     * synthetic carry loads resolve like every other function-typed cell
     * read — a never-assigned counter republishes its tracked identity (no
     * registration), an assigned counter publishes its own dynamic
     * materialization (the current-binding read).
     */


    /** One function-typed for-let slice's declaration and carry registrations. */


    /**
     * Narrowed flow types are never captured: the capture list carries
     * {@code BindingId}s only (no type payload exists per capture), the
     * {@code LoweredFunction}/{@code CLOSURE_NEW} carry the declared
     * signature descriptor only, and a load of a nullable binding inside
     * the body carries the declared nullable descriptor (never a
     * narrowed one — {@code NullNarrowing} state is checker-local,
     * docs/spec-v1.3.md §Closures and captured variables).
     */
    static void testNarrowedFlowTypesNeverCaptured() {
        System.out.println("-- narrowed flow types: capture entries are BindingIds, declared "
            + "descriptors only --");

        SemanticLowerer.ClosureCoreResult result = lowerSlice("""
            function outer(): null {
              let x: number | null = 1;
              let h: () => null = function(): null {
                let y: number | null = x;
              };
            }
            """);
        if (result == null || result.lowering().hasErrors() || result.lowering().unit() == null) {
            if (result != null) {
                fail("the slice lowers to a validated unit: " + result.lowering().diagnostics());
            }
            return;
        }
        List<SemanticOp> ops = result.lowering().unit().ops();
        SemanticLowerer.BindingCoreBinding x = fact(result.bindingFacts(), "x");
        if (x == null) {
            return;
        }
        List<SemanticOp> closureNews = ofKind(ops, SemanticOpKind.CLOSURE_NEW);
        boolean sawCapture = false;
        for (SemanticOp op : closureNews) {
            if (!(op.payload() instanceof KindPayload.ClosureNewPayload payload)) {
                continue;
            }
            if (!payload.captureBindings().contains(x.binding())) {
                continue;
            }
            sawCapture = true;
            // The captures slot is generation-pinned: exactly one entry
            // for x, carrying no type and no narrowed variant.
            check(payload.captureBindings().stream()
                    .filter(x.binding()::equals).count() == 1,
                "exactly one capture entry for x (no narrowed-type duplicate)");
            LoweredFunction function =
                result.lowering().unit().functions().get(payload.function());
            check(function != null && function.captures().equals(payload.captures()),
                "the LoweredFunction repeats the generation-pinned capture list "
                    + "verbatim");
            check(function != null && function.descriptor()
                    .equals(new RuntimeDescriptor.Func(List.of(),
                        RuntimeDescriptor.Null.INSTANCE)),
                "the LoweredFunction carries the declared signature descriptor only");
        }
        check(sawCapture, "h captures x exactly once");
        // The load of x inside the body carries the declared nullable
        // descriptor (?number), never a narrowed type.
        int nullableLoads = 0;
        for (SemanticOp op : ofKind(ops, SemanticOpKind.BINDING_LOAD)) {
            if (op.payload() instanceof KindPayload.BindingLoadPayload load
                    && load.binding().equals(x.binding())
                    && op.resultType() instanceof RuntimeDescriptor.Nullable nullable
                    && nullable.inner().equals(RuntimeDescriptor.Number.INSTANCE)) {
                nullableLoads++;
            }
        }
        check(nullableLoads == 1,
            "the load of x carries the declared ?int descriptor; got " + nullableLoads);
    }

    /**
     * The closure-capture arm of the B2 cell-kind iff: captures upgrade
     * the incarnation they resolve to; uncaptured and reassignable-
     * uncaptured locals stay {@code DIRECT} with the {@code mutable}
     * flag recorded independently; a store inside the body captures the
     * cell (capture by binding — later assignments observed through the
     * cell); self-recursive own names capture the hoisted/nested ALLOC
     * and upgrade it; and an intrinsic reference captures the intrinsic
     * cell.
     */
    static void testCellKindClosureCaptureArm() {
        System.out.println("-- B2 closure-capture arm: SHARED_CELL iff captured --");

        // (a) Reassignable-uncaptured local stays DIRECT with mutable=true.
        SemanticLowerer.ClosureCoreResult uncaptured = lowerSlice("""
            function outer(): null {
              let x: number = 1;
              x = 2;
            }
            """);
        if (uncaptured != null && !uncaptured.lowering().hasErrors()) {
            SemanticLowerer.BindingCoreBinding x = fact(uncaptured.bindingFacts(), "x");
            if (x != null) {
                check(x.incarnations().get(0).cellKind() == BindingCellKind.DIRECT
                        && x.incarnations().get(0).mutable(),
                    "reassignable-uncaptured x is DIRECT with mutable=true");
            }
        } else if (uncaptured != null) {
            fail("(a) lowers: " + uncaptured.lowering().diagnostics());
        }

        // (b) A store inside the closure body captures the cell.
        SemanticLowerer.ClosureCoreResult storeCapture = lowerSlice("""
            function outer(): null {
              let x: number = 1;
              let h: () => null = function(): null {
                x = 2;
              };
              x = 9;
            }
            """);
        if (storeCapture != null && !storeCapture.lowering().hasErrors()) {
            SemanticLowerer.BindingCoreBinding x = fact(storeCapture.bindingFacts(), "x");
            if (x != null) {
                check(x.incarnations().get(0).cellKind() == BindingCellKind.SHARED_CELL,
                    "a store-only reference upgrades x to SHARED_CELL (capture by binding)");
            }
            boolean hCapturesX = false;
            for (SemanticLowerer.ClosureFacts facts : storeCapture.closures()) {
                for (SemanticLowerer.ClosureCapture capture : facts.captures()) {
                    if (capture.binding().equals(x.binding())) {
                        hCapturesX = true;
                    }
                }
            }
            check(hCapturesX, "h's capture list names x's cell (the store reference)");
        } else if (storeCapture != null) {
            fail("(b) lowers: " + storeCapture.lowering().diagnostics());
        }

        // (c) Self-recursion: the own name is a capture resolving to the
        // hoisted ALLOC; the hoisted ALLOC upgrades; the CLOSURE_NEW is
        // the immediate commit before BINDING_INIT.
        SemanticLowerer.ClosureCoreResult selfRecursion = lowerSlice("""
            function f(): null {
              let g: () => null = f;
            }
            """);
        if (selfRecursion != null && !selfRecursion.lowering().hasErrors()) {
            List<SemanticOp> ops = selfRecursion.lowering().unit().ops();
            SemanticLowerer.BindingCoreBinding f = fact(selfRecursion.bindingFacts(), "f");
            if (f != null) {
                check(f.incarnations().get(0).cellKind() == BindingCellKind.SHARED_CELL,
                    "f's hoisted ALLOC upgrades to SHARED_CELL (own-name capture)");
                List<SemanticOp> closureNews = ofKind(ops, SemanticOpKind.CLOSURE_NEW);
                check(closureNews.size() == 1, "exactly one CLOSURE_NEW (size-1 SCC)");
                if (closureNews.size() == 1) {
                    KindPayload.ClosureNewPayload payload =
                        (KindPayload.ClosureNewPayload) closureNews.get(0).payload();
                    check(payload.captureBindings().equals(List.of(f.binding())),
                        "the own name is a capture entry (size-1 SCC own-name arm)");
                    int closureIndex = indexOf(ops, closureNews.get(0).opId());
                    boolean initImmediatelyAfter = closureIndex + 1 < ops.size()
                        && ops.get(closureIndex + 1).kind() == SemanticOpKind.BINDING_INIT
                        && ops.get(closureIndex + 1).payload()
                            instanceof KindPayload.BindingInitPayload init
                        && init.binding().equals(f.binding());
                    check(initImmediatelyAfter,
                        "BINDING_INIT is the immediate commit after the CLOSURE_NEW "
                            + "(B4: no call observes a half-published value)");
                }
            }
        } else if (selfRecursion != null) {
            fail("(c) lowers: " + selfRecursion.lowering().diagnostics());
        }

        // (d) Nested self-recursion: g's body load of g resolves g's
        // nested-scope ALLOC (at the declaration position in outer's
        // body) and upgrades it.
        SemanticLowerer.ClosureCoreResult nestedSelf = lowerSlice("""
            function outer(): null {
              function g(): null {
                let copy: () => null = g;
              }
            }
            """);
        if (nestedSelf != null && !nestedSelf.lowering().hasErrors()) {
            SemanticLowerer.BindingCoreBinding g = fact(nestedSelf.bindingFacts(), "g");
            if (g != null) {
                check(g.incarnations().get(0).cellKind() == BindingCellKind.SHARED_CELL,
                    "g's nested-scope ALLOC upgrades to SHARED_CELL (own-name capture)");
            }
        } else if (nestedSelf != null) {
            fail("(d) lowers: " + nestedSelf.lowering().diagnostics());
        }
    }

    /**
     * Determinism: repeated lowering of the same checked module produces
     * identical capture lists, closure facts, binding facts, and
     * byte-identical unit dumps (ids and payload fields included).
     */
    static void testDeterminism(CheckedSlice slice,
                                SemanticLowerer.ClosureCoreResult firstRun) {
        System.out.println("-- determinism: identical captures, FunctionId/BlockId "
            + "allocation, byte-identical dumps --");

        if (slice == null) {
            return;
        }
        SemanticLowerer.ClosureCoreResult secondRun = lowerSlice(slice);
        check(firstRun != null && !firstRun.lowering().hasErrors()
                && secondRun != null && !secondRun.lowering().hasErrors(),
            "both runs lower to validated units");
        if (firstRun == null || firstRun.lowering().hasErrors()
                || secondRun == null || secondRun.lowering().hasErrors()) {
            return;
        }
        check(firstRun.closures().equals(secondRun.closures()),
            "two runs produce identical closure facts (capture lists, FunctionIds, "
                + "BlockIds, resolved generations)");
        check(firstRun.bindingFacts().equals(secondRun.bindingFacts()),
            "two runs produce identical binding facts (cell kinds included)");
        byte[] firstDump = SemanticIrDumper.dumpModule(firstRun.lowering().unit());
        byte[] secondDump = SemanticIrDumper.dumpModule(secondRun.lowering().unit());
        check(Arrays.equals(firstDump, secondDump),
            "two runs produce byte-identical unit dumps");
    }

    static void testFailClosedNegatives() {
        System.out.println("-- fail-closed negatives --");

        CheckedSlice slice = checkSlice("let h: () => null = function(): null {};");
        if (slice != null) {
            SemanticLowerer.BindingCoreResult foreign = SemanticLowerer.lowerModuleBindingCore(
                moduleOf(slice), SemanticProfile.DEAL_V1_3_INT32, Map.of(), INTERFACE_HASH,
                REGISTRY_HASH, SemanticIdAllocator.over(List.of(MODULE)));
            check(foreign != null && foreign.lowering().hasErrors()
                    && foreign.lowering().unit() == null,
                "a function expression fails hard in the binding-core window with no unit");
            if (foreign != null && foreign.lowering().hasErrors()) {
                CompilerDiagnostic diagnostic = foreign.lowering().diagnostics().get(0);
                check("E6005".equals(diagnostic.code())
                        && diagnostic.diagnosticCode() == DiagnosticCode.E6005
                        && diagnostic.message().contains(SemanticLowerer.CONSTRUCT_UNLOWERED),
                    "the foreign function expression converts to E6005 CONSTRUCT_UNLOWERED");
            }
        }


    }

    /**
     * ISSUE-0750: declaration-owned descriptor failures keep the
     * declaration's own identifiable origin — the function declaration's
     * span around the checked-signature derivation and the declared type
     * annotation's span around the let-boundary derivation. A corrupted
     * checked fact (the closed fact no checker-valid program produces)
     * fails closed exactly as before; only the carried E6005 range
     * changes. Known-file offsetless spans stay SYNTHETIC at their own
     * file, never at the canonical empty-file anchor.
     */
    static void testDeclarationDescriptorOrigins() {
        System.out.println("-- declaration-owned descriptor failures carry the "
            + "declaration's own range --");

        // (a) Function-signature derivation (closure-core): the corrupted
        // checked FunctionSymbol return type is identified by the function
        // declaration's own exact SOURCE range.
        CheckedSlice functionSlice = checkSlice("function f(): number {\n  return 1;\n}\n");
        if (functionSlice != null) {
            FunctionDeclaration function =
                (FunctionDeclaration) functionSlice.program().statements().get(0);
            functionSlice.checks().symbolTable().remove("f");
            functionSlice.checks().symbolTable().define("f",
                new Symbol.FunctionSymbol("f",
                    new Type.Func(List.of(), Type.Error.INSTANCE, false)));
            SemanticLowerer.ClosureCoreResult result = lowerSlice(functionSlice);
            check(result != null && result.lowering().hasErrors()
                    && result.lowering().unit() == null,
                "the corrupted function signature fails hard with no unit");
            if (result != null && result.lowering().hasErrors()) {
                CompilerDiagnostic diagnostic = result.lowering().diagnostics().get(0);
                check("E6005".equals(diagnostic.code())
                        && diagnostic.diagnosticCode() == DiagnosticCode.E6005
                        && diagnostic.message().contains(
                            ContainerPayloadDescriptors.DESCRIPTOR_UNREPRESENTABLE),
                    "the corrupted signature converts to the unchanged E6005 "
                        + "DESCRIPTOR_UNREPRESENTABLE; got " + diagnostic.message());
                DiagnosticRange range = diagnostic.range();
                check(range.origin() == RangeOrigin.SOURCE
                        && "test.deal".equals(range.file())
                        && range.startLine() == 1 && range.startColumn() == 1
                        && range.endLine() == 4 && range.endColumn() == 2
                        && range.startScalarOffset() == 0
                        && range.endScalarOffset() == 37
                        && range.scalarLength() == 37,
                    "the function-signature failure carries the function declaration's "
                        + "own exact SOURCE range test.deal 1:1-4:2 [0,37) length 37; got "
                        + range);
                check(range.equals(function.span().range()),
                    "the range is exactly the function declaration's own span conversion; "
                        + "got " + range);
            }
        }

        // (b) The declared-variable boundary derivation (binding-core): the
        // declared type annotation's own exact SOURCE range.
        CheckedSlice variableSlice = checkSlice("let x: number = 1;\n");
        if (variableSlice != null) {
            VariableDeclaration declaration =
                (VariableDeclaration) variableSlice.program().statements().get(0);
            variableSlice.checks().symbolTable().remove("x");
            variableSlice.checks().symbolTable().define("x",
                new Symbol.VariableSymbol("x", Type.Error.INSTANCE, false));
            SemanticLowerer.BindingCoreResult result = SemanticLowerer.lowerModuleBindingCore(
                moduleOf(variableSlice), SemanticProfile.DEAL_V1_3_INT32, Map.of(),
                INTERFACE_HASH, REGISTRY_HASH, SemanticIdAllocator.over(List.of(MODULE)));
            check(result != null && result.lowering().hasErrors()
                    && result.lowering().unit() == null,
                "the corrupted declared type fails hard with no unit");
            if (result != null && result.lowering().hasErrors()) {
                CompilerDiagnostic diagnostic = result.lowering().diagnostics().get(0);
                check("E6005".equals(diagnostic.code())
                        && diagnostic.diagnosticCode() == DiagnosticCode.E6005
                        && diagnostic.message().contains(
                            ContainerPayloadDescriptors.DESCRIPTOR_UNREPRESENTABLE),
                    "the corrupted declared type converts to the unchanged E6005 "
                        + "DESCRIPTOR_UNREPRESENTABLE; got " + diagnostic.message());
                DiagnosticRange range = diagnostic.range();
                check(range.origin() == RangeOrigin.SOURCE
                        && "test.deal".equals(range.file())
                        && range.startLine() == 1 && range.startColumn() == 8
                        && range.endLine() == 1 && range.endColumn() == 14
                        && range.startScalarOffset() == 7
                        && range.endScalarOffset() == 13
                        && range.scalarLength() == 6,
                    "the let-boundary failure carries the declared type annotation's own "
                        + "exact SOURCE range test.deal 1:8-1:14 [7,13) length 6; got "
                        + range);
                check(range.equals(declaration.typeAnnotation().get().span().range()),
                    "the range is exactly the annotation's own span conversion; got "
                        + range);
            }
        }

        // (c) Known-file offsetless control, let boundary: the same
        // declaration-owned derivation with an offsetless annotation span
        // stays SYNTHETIC at that file, never at the canonical empty-file
        // anchor.
        {
            Span annotationSpan = new Span("writer.deal", 2, 5, 2, 8);
            LiteralExpr initializer = new LiteralExpr(
                new Span("writer.deal", 2, 11, 2, 12), new LiteralValue.NumberLiteral(1));
            VariableDeclaration declaration = new VariableDeclaration(
                new Span("writer.deal", 2, 1, 2, 12), "x",
                Optional.of(new NamedType(annotationSpan, "int")), initializer);
            SymbolTable scope = new SymbolTable();
            scope.define("x", new Symbol.VariableSymbol("x", Type.Error.INSTANCE, false));
            CheckResult checks = new CheckResult(
                Map.of(initializer, Type.Number.INSTANCE), scope, List.of());
            SemanticLowerer.ModuleLowerer lowerer = new SemanticLowerer.ModuleLowerer(
                MODULE, "writer.deal", checks,
                SemanticIdAllocator.over(List.of(MODULE)), true,
                new Span("writer.deal", 1, 1, 1, 1));
            ContainerPayloadDescriptors.Defect defect = null;
            try {
                lowerer.lowerBindingModule(List.of(declaration));
            } catch (ContainerPayloadDescriptors.Defect raised) {
                defect = raised;
            }
            check(defect != null,
                "the offsetless annotated declaration raises the bridge Defect");
            if (defect != null) {
                DiagnosticRange carried = defect.diagnosticRange();
                check(carried != null && carried.origin() == RangeOrigin.SYNTHETIC
                        && "writer.deal".equals(carried.file())
                        && carried.startLine() == 1 && carried.startColumn() == 1
                        && carried.endLine() == 1 && carried.endColumn() == 1
                        && carried.startScalarOffset() == 0
                        && carried.endScalarOffset() == 0
                        && carried.scalarLength() == 0,
                    "the offsetless annotation keeps its known file and stays SYNTHETIC "
                        + "at the canonical anchor: " + carried);
                CompilerDiagnostic diagnostic = SemanticLowerer.loweringFailure(MODULE,
                    defect);
                check(diagnostic.range().origin() == RangeOrigin.SYNTHETIC
                        && "writer.deal".equals(diagnostic.range().file())
                        && diagnostic.range().startLine() == 1
                        && diagnostic.range().startColumn() == 1
                        && diagnostic.range().startScalarOffset() == 0
                        && diagnostic.range().endScalarOffset() == 0,
                    "the projection keeps the known offsetless file and never borrows "
                        + "the canonical empty-file anchor: " + diagnostic.range());
            }
        }

        // (d) Known-file offsetless control, function declaration: the
        // offsetless declaration span stays SYNTHETIC at its own file.
        {
            Span functionSpan = new Span("writer.deal", 3, 1, 5, 2);
            FunctionDeclaration function = new FunctionDeclaration(functionSpan, "f",
                List.of(), new NamedType(new Span("writer.deal", 3, 11, 3, 14), "int"),
                new Block(new Span("writer.deal", 4, 1, 5, 2), List.of()), false, false);
            SymbolTable scope = new SymbolTable();
            scope.define("f", new Symbol.FunctionSymbol("f",
                new Type.Func(List.of(), Type.Error.INSTANCE, false)));
            CheckResult checks = new CheckResult(Map.of(), scope, List.of());
            ProgramNode program = new ProgramNode(
                new Span("writer.deal", 1, 1, 5, 2), List.of(function));
            CheckedModuleInput module = new CheckedModuleInput(MODULE, "writer.deal",
                Path.of("writer.deal"), program, checks, List.of(), List.of(),
                CheckedModuleKind.IMPLEMENTATION);
            SemanticLowerer.ClosureCoreResult result = SemanticLowerer.lowerModuleClosureCore(
                module, SemanticProfile.DEAL_V1_3_INT32, Map.of(), INTERFACE_HASH,
                REGISTRY_HASH, SemanticIdAllocator.over(List.of(MODULE)));
            check(result != null && result.lowering().hasErrors()
                    && result.lowering().unit() == null,
                "the offsetless corrupted function signature fails hard with no unit");
            if (result != null && result.lowering().hasErrors()) {
                DiagnosticRange range = result.lowering().diagnostics().get(0).range();
                check(range.origin() == RangeOrigin.SYNTHETIC
                        && "writer.deal".equals(range.file())
                        && range.startLine() == 1 && range.startColumn() == 1
                        && range.startScalarOffset() == 0
                        && range.endScalarOffset() == 0
                        && range.scalarLength() == 0,
                    "the offsetless function declaration keeps its known file and stays "
                        + "SYNTHETIC, never the canonical empty-file anchor: " + range);
            }
        }


    }

    // =========================================================================
    // Runner
    // =========================================================================

    private static SemanticLowerer.GroupCoreResult lowerGroups(String source) {
        CheckedSlice slice = checkSlice(source);
        if (slice == null) {
            throw new AssertionError("group source must check");
        }
        return SemanticLowerer.lowerModuleGroupCore(moduleOf(slice),
            SemanticProfile.DEAL_V1_3_INT32, Map.of(), INTERFACE_HASH, REGISTRY_HASH,
            SemanticIdAllocator.over(List.of(MODULE)));
    }

    private static boolean namesBinding(SemanticOp op, BindingId binding) {
        return switch (op.payload()) {
            case KindPayload.BindingAllocPayload p -> p.binding().equals(binding);
            case KindPayload.BindingInitPayload p -> p.binding().equals(binding);
            default -> false;
        };
    }

    private static void testRecursiveGroups() {
        String source = """
            function f(): null {
              let h: () => null = function(): null { let copy: () => null = laterFn; };
              let gRef: () => null = g;
            }
            function g(): null { let fRef: () => null = f; }
            function laterFn(): null {}
            function self(): null { let copy: () => null = self; }
            function a(): null { let bRef: () => null = b; }
            function b(): null { let aRef: () => null = a; }
            function outer(): null {
              let before = 1;
              function n(): null { let mRef: () => null = m; }
              function m(): null { let nRef: () => null = n; }
              let after = 2;
            }
            """;
        SemanticLowerer.GroupCoreResult first = lowerGroups(source);
        check(!first.lowering().hasErrors() && first.lowering().unit() != null,
            "combined SCC corpus lowers: " + first.lowering().diagnostics());
        if (first.lowering().hasErrors()) {
            return;
        }
        LoweredModuleUnit unit = first.lowering().unit();
        List<SemanticOp> ops = unit.ops();
        check(first.groups().size() == 3 && ofKind(ops, SemanticOpKind.RECURSIVE_GROUP_INIT).size() == 3,
            "two module SCCs and one nested SCC, each emitted exactly once");
        java.util.Set<ValueId> identities = new java.util.HashSet<>();
        for (SemanticLowerer.GroupFacts group : first.groups()) {
            SemanticOp groupOp = ops.stream().filter(o -> o.opId().equals(group.opId()))
                .findFirst().orElseThrow();
            KindPayload.RecursiveGroupInitPayload payload =
                (KindPayload.RecursiveGroupInitPayload) groupOp.payload();
            check(groupOp.result() == null && groupOp.resultType() == null,
                "group initialization has no result");
            check(group.members().size() == 2
                    && payload.bindings().equals(group.members().stream()
                        .map(SemanticLowerer.GroupMemberFacts::binding).toList())
                    && payload.functions().equals(group.members().stream()
                        .map(SemanticLowerer.GroupMemberFacts::functionId).toList()),
                "payload and facts agree in declaration order");
            for (SemanticLowerer.GroupMemberFacts member : group.members()) {
                check(identities.add(member.identity()), "distinct preassigned group identities");
                SemanticLowerer.BindingCoreBinding binding = fact(first.bindingFacts(), member.name());
                check(binding.incarnations().size() == 1, "one member incarnation");
                SemanticLowerer.BindingCoreIncarnation incarnation = binding.incarnations().get(0);
                check(incarnation.generation() == 0
                        && incarnation.producer() == SemanticLowerer.BindingProducer.RECURSIVE_GROUP_INIT
                        && incarnation.cellKind() == BindingCellKind.SHARED_CELL
                        && incarnation.scope().equals(group.block()),
                    "group owns generation-zero shared cell in its block");
                check(ops.stream().noneMatch(o -> namesBinding(o, member.binding())),
                    "group members have no separate ALLOC or INIT");
                check(unit.functionBindings().get(new FunctionAllocationIdentity(member.identity().id()))
                        instanceof FunctionExecutionBinding.LoweredBody body
                        && body.functionId().equals(member.functionId())
                        && body.blockId().equals(member.bodyBlock()),
                    "member identity registers its exact lowered body");
                LoweredFunction function = unit.functions().get(member.functionId());
                check(function != null && function.captureBindings().equals(member.captures().stream()
                        .map(SemanticLowerer.ClosureCapture::binding).toList()),
                    "function captures agree with member facts");
                SemanticLowerer.GroupMemberFacts sibling = group.members().stream()
                    .filter(m -> !m.binding().equals(member.binding())).findFirst().orElseThrow();
                check(member.captures().stream().anyMatch(c -> c.binding().equals(sibling.binding())
                        && c.producer() == SemanticLowerer.BindingProducer.RECURSIVE_GROUP_INIT),
                    "mutual capture resolves through group");
                check(ofKind(ops, SemanticOpKind.BINDING_LOAD).stream().anyMatch(o ->
                        o.payload() instanceof KindPayload.BindingLoadPayload p
                        && p.binding().equals(member.binding()) && p.generation() == 0
                        && member.identity().equals(o.result())),
                    "member load publishes preassigned identity");
            }
        }
        List<SemanticLowerer.GroupFacts> moduleGroups = first.groups().stream()
            .filter(g -> g.block().equals(unit.moduleInit().initBlock())).toList();
        check(moduleGroups.stream().map(g -> g.members().stream()
                .map(SemanticLowerer.GroupMemberFacts::name).toList()).toList()
                .equals(List.of(List.of("f", "g"), List.of("a", "b"))),
            "disjoint module SCCs follow first-member declaration order");
        for (SemanticLowerer.GroupFacts group : moduleGroups) {
            check(ops.stream().filter(o -> o.payload() instanceof KindPayload.BindingAllocPayload p
                    && p.scope().equals(unit.moduleInit().initBlock()))
                    .allMatch(o -> indexOf(ops, o.opId()) < indexOf(ops, group.opId())),
                "module groups follow hoisted allocations");
        }
        check(indexOf(ops, moduleGroups.get(0).opId()) < indexOf(ops, moduleGroups.get(1).opId()),
            "module group op order is stable");
        SemanticLowerer.GroupFacts nested = first.groups().stream()
            .filter(g -> !g.block().equals(unit.moduleInit().initBlock())).findFirst().orElseThrow();
        check(nested.members().stream().map(SemanticLowerer.GroupMemberFacts::name).toList()
                .equals(List.of("n", "m")), "nested SCC declaration order");
        BindingId before = fact(first.bindingFacts(), "before").binding();
        BindingId after = fact(first.bindingFacts(), "after").binding();
        SemanticOp beforeInit = ops.stream().filter(o -> o.payload() instanceof KindPayload.BindingInitPayload p
            && p.binding().equals(before)).findFirst().orElseThrow();
        SemanticOp afterAlloc = ops.stream().filter(o -> o.payload() instanceof KindPayload.BindingAllocPayload p
            && p.binding().equals(after)).findFirst().orElseThrow();
        check(indexOf(ops, beforeInit.opId()) < indexOf(ops, nested.opId())
                && indexOf(ops, nested.opId()) < indexOf(ops, afterAlloc.opId()),
            "nested group is emitted at first member declaration");
        check(first.closures().stream().anyMatch(c -> c.bodyBlock().equals(nested.block())),
            "nested group belongs to enclosing closure body");
        SemanticLowerer.GroupCoreResult pair = lowerGroups("""
            function f(): null { let gRef: () => null = g; }
            function g(): null { let fRef: () => null = f; }
            """);
        check(!pair.lowering().hasErrors() && pair.groups().size() == 1
                && ofKind(pair.lowering().unit().ops(), SemanticOpKind.CLOSURE_NEW).isEmpty(),
            "isolated mutual pair produces no closure allocations");
        for (SemanticLowerer.GroupMemberFacts member : pair.groups().getFirst().members()) {
            BindingId sibling = pair.groups().getFirst().members().stream()
                .filter(m -> !m.binding().equals(member.binding())).findFirst().orElseThrow().binding();
            check(member.captures().size() == 1 && member.captures().getFirst().binding().equals(sibling)
                    && pair.lowering().unit().functions().get(member.functionId()).captureBindings().equals(List.of(sibling)),
                "isolated pair captures exactly its sibling, with no extra binding");
        }
        SemanticLowerer.GroupCoreResult singletonResult = lowerGroups("function f(): null { let self: () => null = f; }");
        check(!singletonResult.lowering().hasErrors() && singletonResult.groups().isEmpty()
                && ofKind(singletonResult.lowering().unit().ops(), SemanticOpKind.RECURSIVE_GROUP_INIT).isEmpty()
                && ofKind(singletonResult.lowering().unit().ops(), SemanticOpKind.CLOSURE_NEW).size() == 1,
            "isolated self SCC never emits a group");
        for (String singleton : List.of("self", "laterFn", "outer")) {
            SemanticLowerer.BindingCoreBinding binding = fact(first.bindingFacts(), singleton);
            check(ops.stream().anyMatch(o -> o.kind() == SemanticOpKind.BINDING_ALLOC
                    && namesBinding(o, binding.binding()))
                    && ops.stream().anyMatch(o -> o.kind() == SemanticOpKind.BINDING_INIT
                        && namesBinding(o, binding.binding()) && o.result() == null),
                "singleton " + singleton + " retains separate ALLOC/INIT");
        }
        check(ofKind(ops, SemanticOpKind.CLOSURE_NEW).size() == 4,
            "self recursion and ungrouped sibling stay closures, plus outer and nested h");
        BindingId later = fact(first.bindingFacts(), "laterFn").binding();
        SemanticLowerer.GroupMemberFacts f = moduleGroups.get(0).members().get(0);
        check(f.captures().size() == 2 && f.captures().stream().anyMatch(c ->
                c.binding().equals(later) && c.generation() == 0
                && c.producer() == SemanticLowerer.BindingProducer.BINDING_ALLOC),
            "member detaching chain captures later module function through hoist");
        SemanticLowerer.ClosureFacts h = first.closures().stream()
            .filter(c -> c.captures().size() == 1 && c.captures().get(0).binding().equals(later))
            .findFirst().orElseThrow();
        check(h.captures().get(0).generation() == 0
                && h.captures().get(0).producer() == SemanticLowerer.BindingProducer.BINDING_ALLOC
                && h.captures().get(0).scope().equals(unit.moduleInit().initBlock()),
            "nested closure resolves later function's module-init allocation");
        SemanticOp laterClosure = ofKind(ops, SemanticOpKind.CLOSURE_NEW).stream()
            .filter(o -> ops.stream().anyMatch(load -> load.payload() instanceof KindPayload.BindingLoadPayload p
                && p.binding().equals(later) && o.result().equals(load.result())))
            .findFirst().orElseThrow();
        check(indexOf(ops, moduleGroups.get(0).opId()) < indexOf(ops, laterClosure.opId()),
            "group precedes later closure, whose identity is reused by hoisted load");
        SemanticLowerer.GroupCoreResult second = lowerGroups(source);
        check(!second.lowering().hasErrors()
                && Arrays.equals(SemanticIrDumper.dumpModule(unit),
                    SemanticIrDumper.dumpModule(second.lowering().unit())),
            "independent group lowerings produce identical dumps");
        SemanticLowerer.GroupCoreResult foreign = lowerGroups("""
            function f(): null { g(); }
            function g(): null { let fRef: () => null = f; }
            """);
        check(foreign.lowering().unit() == null && foreign.lowering().hasErrors()
                && foreign.lowering().diagnostics().get(0).code().equals("E6005")
                && foreign.lowering().diagnostics().get(0).message().contains(SemanticLowerer.CONSTRUCT_UNLOWERED),
            "group member unsupported call fails closed");
    }

    public static void main(String[] args) {
        System.out.println("=== Closure Lowering Test (ISSUE-0445 closure child) ===\n");

        testClosureCapturesEnclosingLocalsInFirstReferenceOrder();
        testDoublyNestedCaptureAlongDetachingChain();
        testTransitivelyNestedCapturePropagation();
        CheckedSlice hoistedSlice = checkSlice(HOISTED_CAPTURE_SOURCE);
        SemanticLowerer.ClosureCoreResult hoisted = lowerSlice(hoistedSlice);
        testCaptureOfLaterDeclaredModuleFunctionResolvesToHoistedAlloc(hoisted);
        testNarrowedFlowTypesNeverCaptured();
        testCellKindClosureCaptureArm();
        testDeterminism(hoistedSlice, hoisted);
        testFailClosedNegatives();
        testDeclarationDescriptorOrigins();
        testRecursiveGroups();

        System.out.println("\nClosure lowering: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
