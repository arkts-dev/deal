package deal.test;

import deal.diagnostics.DiagnosticCode;
import deal.semantic.ir.ActualKind;
import deal.semantic.ir.AnchorId;
import deal.semantic.ir.AsyncTokenId;
import deal.semantic.ir.AsyncTokenOwner;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryContext;
import deal.semantic.ir.BoundaryExecutor;
import deal.semantic.ir.BoundaryFailure;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.BoundaryOutcome;
import deal.semantic.ir.BoundaryRealization;
import deal.semantic.ir.BoundaryValueView;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ClassLayout;
import deal.semantic.ir.ClassOpsExecutor;
import deal.semantic.ir.ClassOpsExecutor.BoundaryCheckRunner;
import deal.semantic.ir.ClassOpsExecutor.BoundaryResult;
import deal.semantic.ir.ClassOpsExecutor.Defect;
import deal.semantic.ir.ClassOpsExecutor.FieldState;
import deal.semantic.ir.ClassOpsExecutor.Outcome;
import deal.semantic.ir.ClassOpsExecutor.Value;
import deal.semantic.ir.ClosedSelector;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.ConstructionProvider;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.OpResultType;
import deal.semantic.ir.OperationContractSnapshot;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.ScalarValue;
import deal.semantic.ir.SemanticArray;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticTable;
import deal.semantic.ir.SemanticValue;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.ValueId;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ClassOpsExecutorTest {

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

    private static void expectDefect(Runnable runnable, String what) {
        try {
            runnable.run();
            fail("expected ClassOpsExecutor.Defect for " + what
                + ", but no exception was raised");
        } catch (Defect expected) {
            passed++;
        } catch (Throwable other) {
            fail("expected ClassOpsExecutor.Defect for " + what + ", got "
                + other.getClass().getSimpleName() + ": " + other.getMessage());
        }
    }

    private static void expectNpe(Runnable runnable, String what) {
        try {
            runnable.run();
            fail("expected NullPointerException for " + what + ", but no exception was raised");
        } catch (NullPointerException expected) {
            passed++;
        } catch (Throwable other) {
            fail("expected NullPointerException for " + what + ", got "
                + other.getClass().getSimpleName() + ": " + other.getMessage());
        }
    }

    // =========================================================================
    // Semantic-id and op builders (the ContainerOpsExecutorTest discipline)
    // =========================================================================

    private static final ModuleId MOD = new ModuleId("corpus.classops");
    private static final ClassId CLS = new ClassId(MOD.path(), "Point");
    private static final RuntimeDescriptor INT = RuntimeDescriptor.Number.INSTANCE;
    private static final RuntimeDescriptor STRING = RuntimeDescriptor.String.INSTANCE;
    private static final RuntimeDescriptor TABLE = RuntimeDescriptor.Table.INSTANCE;
    private static final RuntimeDescriptor.Func FUNC_NULL = new RuntimeDescriptor.Func(
        List.of(), RuntimeDescriptor.Null.INSTANCE, false);
    private static final RuntimeDescriptor.Func FUNC_NULL_ASYNC = new RuntimeDescriptor.Func(
        List.of(), RuntimeDescriptor.Null.INSTANCE, true);

    private static int nextOpId = 1;
    private static int nextValueId = 1;
    private static int nextColumn = 1;

    private static OpId nextOpId() {
        return new OpId(MOD, nextOpId++);
    }

    private static ValueId nextValue() {
        return new ValueId(nextValueId++);
    }

    /** A distinct USER origin per op so origin identity is provable. */
    private static SourceOrigin originAt(OpId parent, int startColumn) {
        return new SourceOrigin("corpus.deal",
            new SourceSpan("corpus.deal", 1, startColumn, 1, startColumn + 3),
            SourceOriginKind.USER, new AnchorId(0), parent);
    }

    private static SourceOrigin nextOrigin(OpId parent) {
        int column = nextColumn;
        nextColumn += 10;
        return originAt(parent, column);
    }

    private static OperationContractSnapshot contractFor(SemanticOpKind kind, KindPayload payload,
            OpResultType resultType, List<RuntimeDescriptor> operandTypes,
            FailurePolicyId policy, String digest) {
        ClosedSelector selector = payload instanceof KindPayload.SelectorCarrying carrying
            ? carrying.selector() : null;
        return new OperationContractSnapshot(OperationContractSnapshot.VERSION, kind, resultType,
            operandTypes, selector, payload, policy, List.of(), digest);
    }

    private static SemanticOp opWithId(OpId id, SemanticOpKind kind, KindPayload payload,
            SemanticValue result, OpResultType resultType, FailurePolicyId policy, OpId parent) {
        OperationContractSnapshot contract =
            contractFor(kind, payload, resultType, List.of(), policy, "placeholder");
        String digest = ContractSnapshotCanonicalizer.digest(contract);
        contract = contractFor(kind, payload, resultType, List.of(), policy, digest);
        return new SemanticOp(id, kind, nextOrigin(parent), result, resultType, List.of(),
            List.of(), payload, policy, contract);
    }

    private static SemanticOp op(SemanticOpKind kind, KindPayload payload, SemanticValue result,
            OpResultType resultType, FailurePolicyId policy, OpId parent) {
        return opWithId(nextOpId(), kind, payload, result, resultType, policy, parent);
    }

    private static ClassLayout.FieldLayout field(String name, RuntimeDescriptor descriptor,
                                                 boolean required) {
        return new ClassLayout.FieldLayout(name, descriptor, required, ConstructionProvider.LOCAL);
    }

    private static ClassLayout layout(ClassLayout.FieldLayout... fields) {
        return new ClassLayout(CLS, List.of(fields));
    }

    private static SemanticOp boundaryChild(OpId parentId, BoundaryKind kind,
            RuntimeDescriptor descriptor, ValueId input, FailurePolicyId policy) {
        return op(SemanticOpKind.BOUNDARY,
            new KindPayload.BoundaryPayload(kind, descriptor, input,
                new BoundaryRealization.RuntimeValidation("runtime-validation")),
            null, null, policy, parentId);
    }

    /** The value of the declared field {@code name} of one published instance. */
    private static Value fieldValue(Value.Class instance, ClassLayout layout, String name) {
        for (int i = 0; i < layout.fields().size(); i++) {
            if (layout.fields().get(i).name().equals(name)) {
                FieldState state = instance.fields().get(i);
                return state instanceof FieldState.Present present
                    ? present.value() : null;
            }
        }
        return null;
    }

    /** The field state of the declared field {@code name} of one published instance. */
    private static FieldState fieldState(Value.Class instance, ClassLayout layout, String name) {
        for (int i = 0; i < layout.fields().size(); i++) {
            if (layout.fields().get(i).name().equals(name)) {
                return instance.fields().get(i);
            }
        }
        return null;
    }

    // =========================================================================
    // Fixtures: the recording value lookup and the delegate stand-ins
    // =========================================================================

    /** A value lookup that records every resolution in the shared log. */
    private static final class RecordingValues extends AbstractMap<ValueId, Value> {

        private final Map<ValueId, Value> inner;
        private final List<String> log;

        RecordingValues(Map<ValueId, Value> inner, List<String> log) {
            this.inner = inner;
            this.log = log;
        }

        @Override
        public Set<Entry<ValueId, Value>> entrySet() {
            return inner.entrySet();
        }

        @Override
        public Value get(Object key) {
            Value value = inner.get(key);
            log.add("resolve " + key + (value == null ? " (unresolved)" : ""));
            return value;
        }
    }

    /**
     * A pass-through/fail-first boundary fixture: records every call in
     * the shared log (the call order proves the declaration order),
     * returns {@code Pass(input)} and fails child index
     * {@code failAtIndex} with the supplied failure.
     */
    private static final class ScriptedDelegate implements BoundaryCheckRunner {

        private final List<String> log;
        private final int failAtIndex;
        private final BoundaryFailure failure;
        private int calls = 0;

        ScriptedDelegate(List<String> log, int failAtIndex, BoundaryFailure failure) {
            this.log = log;
            this.failAtIndex = failAtIndex;
            this.failure = failure;
        }

        int calls() {
            return calls;
        }

        @Override
        public BoundaryResult run(KindPayload.BoundaryPayload boundary, Value input) {
            int index = calls++;
            log.add("child[" + index + "] " + boundary.kind() + " <- " + input.actualKind().token());
            if (index == failAtIndex) {
                return new BoundaryResult.Fail(failure);
            }
            return new BoundaryResult.Pass(input);
        }
    }

    /** Checks provided values through the production boundary projection. */
    private static BoundaryCheckRunner realProjectionDelegate(List<String> log) {
        return (boundary, input) -> {
            if (log != null) {
                log.add("child " + boundary.kind() + " <- " + input.actualKind().token());
            }
            FailurePolicyId policy = boundary.descriptor() instanceof RuntimeDescriptor.Func
                ? FailurePolicyId.FUNCTION_SIGNATURE : FailurePolicyId.TYPE_DESCRIPTOR;
            BoundaryOutcome outcome = BoundaryExecutor.check(policy, boundary.descriptor(),
                valueView(input), BoundaryContext.none());
            return switch (outcome) {
                case BoundaryOutcome.Pass pass ->
                    new BoundaryResult.Pass(publishValue(pass.value(), input));
                case BoundaryOutcome.Fail fail -> new BoundaryResult.Fail(fail.failure());
            };
        };
    }

    private static BoundaryValueView valueView(Value value) {
        return switch (value) {
            case Value.Null ignored -> BoundaryValueView.of(ActualKind.NULL);
            case Value.Undefined ignored -> BoundaryValueView.of(ActualKind.UNDEFINED);
            case Value.Bool bool -> BoundaryValueView.of(ActualKind.BOOLEAN);
            case Value.Number number -> BoundaryValueView.ofNumber(number.value());
            case Value.String string -> BoundaryValueView.of(string.scalar().actualKind());
            case Value.Table ignored -> BoundaryValueView.of(ActualKind.TABLE);
            case Value.Array ignored -> BoundaryValueView.of(ActualKind.ARRAY);
            case Value.Class classValue -> BoundaryValueView.ofClass(classValue.classId().text());
            case Value.Function function -> BoundaryValueView.ofFunction(function.signature());
            case Value.Missing ignored -> BoundaryValueView.of(ActualKind.MISSING);
        };
    }

    /**
     * The boundary's published value: a passing boundary never copies or
     * converts, so the published null view maps to the explicit null
     * variant and every other view keeps the input value.
     */
    private static Value publishValue(BoundaryValueView view, Value input) {
        return view.kind() == ActualKind.NULL ? Value.Null.INSTANCE : input;
    }

    // =========================================================================
    // Shared CLASS_NEW builders
    // =========================================================================

    /** A provided-field entry with a fresh prior-step id (not yet resolved). */
    private record ProvidedEntry(String name, ValueId valueId) {
    }

    /** One field-boundary entry plus its resolved child op. */
    private record BoundaryEntry(String field, BoundaryKind kind, SemanticOp child) {
    }

    /** A structural construction and its provided-field boundary children. */
    private record ClassNewFixture(SemanticOp op, Map<OpId, SemanticOp> boundaryOps) {
    }

    private static ClassNewFixture classNewFixture(ClassLayout layout,
            List<ProvidedEntry> provided,
            List<BoundaryEntry> entries) {
        OpId opId = nextOpId();
        List<KindPayload.ProvidedField> providedFields = new ArrayList<>();
        for (ProvidedEntry entry : provided) {
            providedFields.add(new KindPayload.ProvidedField(entry.name(), entry.valueId()));
        }
        List<KindPayload.FieldBoundary> boundaries = new ArrayList<>();
        Map<OpId, SemanticOp> boundaryOps = new LinkedHashMap<>();
        for (BoundaryEntry entry : entries) {
            boundaries.add(new KindPayload.FieldBoundary(entry.field(), entry.kind(),
                entry.child().opId()));
            boundaryOps.put(entry.child().opId(), entry.child());
        }
        SemanticOp op = opWithId(opId, SemanticOpKind.CLASS_NEW,
            new KindPayload.ClassNewPayload(CLS, layout, providedFields, ConstructionProvider.LOCAL,
                null, boundaries),
            nextValue(), new RuntimeDescriptor.Class(CLS),
            FailurePolicyId.CLASS_CONSTRUCTION, null);
        return new ClassNewFixture(op, boundaryOps);
    }

    private static Map<OpId, SemanticOp> reparent(ClassNewFixture fixture,
                                                  List<BoundaryEntry> entries) {
        Map<OpId, SemanticOp> boundaryOps = new LinkedHashMap<>();
        for (BoundaryEntry entry : entries) {
            KindPayload.BoundaryPayload payload =
                (KindPayload.BoundaryPayload) entry.child().payload();
            OpId childId = entry.child().opId();
            SourceOrigin origin = new SourceOrigin(entry.child().origin().sourceId(),
                entry.child().origin().span(), entry.child().origin().kind(),
                entry.child().origin().anchorId(), fixture.op().opId());
            OperationContractSnapshot contract =
                contractFor(SemanticOpKind.BOUNDARY, payload, null, List.of(),
                    entry.child().failurePolicy(), "placeholder");
            String digest = ContractSnapshotCanonicalizer.digest(contract);
            contract = contractFor(SemanticOpKind.BOUNDARY, payload, null, List.of(),
                entry.child().failurePolicy(), digest);
            SemanticOp child = new SemanticOp(childId, SemanticOpKind.BOUNDARY, origin,
                null, null, List.of(), List.of(), payload, entry.child().failurePolicy(),
                contract);
            boundaryOps.put(childId, child);
        }
        return boundaryOps;
    }

    // =========================================================================
    // 1. The closed value view
    // =========================================================================

    static void testValueViewClassification() {
        System.out.println("-- the closed value view renders the canonical actual kinds --");

        check(Value.Null.INSTANCE.actualKind() == ActualKind.NULL,
            "Null renders NULL");
        check(new Value.Bool(true).actualKind() == ActualKind.BOOLEAN,
            "Bool renders BOOLEAN");
        check(new Value.Number(-2147483648).actualKind() == ActualKind.NUMBER,
            "Int renders INT");
        check(new Value.Number(Double.NaN).actualKind() == ActualKind.NUMBER,
            "Number renders NUMBER");
        check(Value.string("x").actualKind() == ActualKind.STRING,
            "a valid String renders STRING");
        check(Value.string("\uD800").actualKind() == ActualKind.INVALID_UNICODE,
            "a lone-surrogate String renders INVALID_UNICODE (the classification "
                + "travels with the value)");
        check(new Value.Table(new SemanticTable<>()).actualKind() == ActualKind.TABLE,
            "Table renders TABLE");
        check(new Value.Array(SemanticArray.of(List.of())).actualKind() == ActualKind.ARRAY,
            "Array renders ARRAY");
        check(new Value.Class(CLS, List.of()).actualKind() == ActualKind.CLASS,
            "Class renders CLASS");
        check(new Value.Function(FUNC_NULL).actualKind() == ActualKind.FUNCTION,
            "Function renders FUNCTION");
        check(Value.Missing.INSTANCE.actualKind() == ActualKind.MISSING,
            "Missing renders the schema's internal MISSING, never Java null");
        check(("class:" + CLS.text()).equals(
                ActualKind.canonicalToken(ActualKind.CLASS, CLS.text())),
            "the CLASS canonical token is the pinned class:<ClassId> atom text; got "
                + ActualKind.canonicalToken(ActualKind.CLASS, CLS.text()));

        // The class variant preserves the three presence states exactly.
        Value.Class instance = new Value.Class(CLS, List.of(
            new FieldState.Present(Value.Null.INSTANCE),
            new FieldState.Present(new Value.Number(7)),
            FieldState.Missing.INSTANCE));
        check(instance.classId().equals(CLS), "the class value carries the canonical classId tag");
        check(instance.fields().size() == 3, "the class value carries its fields in order");
        check(instance.fields().get(0) instanceof FieldState.Present present
                && present.value() == Value.Null.INSTANCE,
            "a present null field is Present (never conflated with Missing)");
        check(instance.fields().get(1) instanceof FieldState.Present present
                && present.value().equals(new Value.Number(7)),
            "a present value field is Present with its value");
        check(instance.fields().get(2) == FieldState.Missing.INSTANCE,
            "an absent field is the Missing state (distinct from present null)");

        expectDefect(() -> new Value.Class(CLS, List.of(
                new FieldState.Present(Value.Missing.INSTANCE))),
            "a Present field carrying the internal Missing view");
        expectDefect(() -> new FieldState.Present(Value.Missing.INSTANCE),
            "a FieldState.Present carrying the internal Missing view");
        expectNpe(() -> new Value.Class(null, List.of()), "new Value.Class(null, …)");
        expectNpe(() -> new Value.Class(CLS, null), "new Value.Class(CLS, null)");
        expectNpe(() -> new Value.Function(null), "new Value.Function(null)");
        expectNpe(() -> new Value.String(null), "new Value.String(null)");
        expectNpe(() -> new Value.Table(null), "new Value.Table(null)");
        expectNpe(() -> new Value.Array(null), "new Value.Array(null)");
        expectNpe(() -> new FieldState.Present(null), "new FieldState.Present(null)");
    }

    // =========================================================================
    // 1b. The live read-only conversion views
    // =========================================================================

    /**
     * The conversion boundary's live read-only views: a live table/array/
     * class view reads its backing state on every call, so a reference
     * cycle and any later commit are observed without materializing or
     * descending at view creation; the views never mutate their backing
     * state, and a write through the live table surface fails closed.
     */
    static void testLiveContainerViews() {
        System.out.println("-- the live read-only conversion views --");

        // The live table: keys/lookups read the backing map again after a
        // commit, an absent key stays Missing, and the read-only surface
        // rejects put/remove.
        Map<String, Value> backingTable = new LinkedHashMap<>();
        backingTable.put("a", new Value.Number(1));
        SemanticTable<Value> liveTable = SemanticTable.live(
            () -> List.copyOf(backingTable.keySet()),
            key -> backingTable.containsKey(key)
                ? new SemanticTable.Lookup.Present<>(backingTable.get(key))
                : new SemanticTable.Lookup.Missing<>());
        check(liveTable.size() == 1 && liveTable.keys().equals(List.of("a")),
            "a live table reads its backing keys and size");
        check(liveTable.get("a") instanceof SemanticTable.Lookup.Present<Value> present
                && present.value().equals(new Value.Number(1)),
            "a live table reads the backing value of a present key");
        check(liveTable.get("absent") instanceof SemanticTable.Lookup.Missing<Value>,
            "a live table reports an absent backing key as Missing");
        backingTable.put("b", new Value.Number(2));
        check(liveTable.size() == 2 && liveTable.keys().equals(List.of("a", "b")),
            "a live table observes a commit to its backing state");
        expectIllegalState(() -> liveTable.put("c", new Value.Number(3)),
            "a put through a live read-only table view");
        expectIllegalState(() -> liveTable.remove("a"),
            "a remove through a live read-only table view");

        // The live array: element reads follow the backing list in place
        // (including a size change), the bounds contract stays the closed
        // Defect, and elements() is an immutable snapshot.
        List<Value> backingArray = new ArrayList<>(List.of(new Value.Number(7)));
        SemanticArray<Value> liveArray = SemanticArray.live(
            backingArray::size, backingArray::get);
        check(liveArray.size() == 1
                && liveArray.elementAt(0).equals(new Value.Number(7)),
            "a live array reads its backing elements");
        backingArray.set(0, new Value.Number(8));
        backingArray.add(new Value.Number(9));
        check(liveArray.size() == 2
                && liveArray.elementAt(0).equals(new Value.Number(8))
                && liveArray.elementAt(1).equals(new Value.Number(9)),
            "a live array observes an in-place commit and a backing size change");
        check(liveArray.elements().equals(List.of(new Value.Number(8), new Value.Number(9))),
            "a live array materializes its elements as an immutable snapshot");
        try {
            liveArray.elementAt(2);
            fail("the live array's pinned bounds Defect for elementAt(2)");
        } catch (SemanticArray.Defect expected) {
            passed++;
        }

        // The live class view: field states convert on read and follow the
        // backing field states after a commit; the closed field-state
        // invariant stays enforced by the present-state construction.
        List<FieldState> backingFields = new ArrayList<>(List.of(
            new FieldState.Present(new Value.Number(1)), FieldState.Missing.INSTANCE));
        Value.Class liveClass = ClassOpsExecutor.liveClassView(CLS, backingFields.size(),
            backingFields::get);
        check(liveClass.classId().equals(CLS) && liveClass.actualKind() == ActualKind.CLASS,
            "a live class view carries the canonical classId tag");
        check(liveClass.fields().get(0) instanceof FieldState.Present present
                && present.value().equals(new Value.Number(1))
                && liveClass.fields().get(1) == FieldState.Missing.INSTANCE,
            "a live class view reads its backing field states in declaration order");
        backingFields.set(0, new FieldState.Present(new Value.Number(2)));
        backingFields.set(1, new FieldState.Present(Value.Null.INSTANCE));
        check(liveClass.fields().get(0) instanceof FieldState.Present present
                && present.value().equals(new Value.Number(2))
                && liveClass.fields().get(1) instanceof FieldState.Present nullPresent
                && nullPresent.value() == Value.Null.INSTANCE,
            "a live class view observes a field commit to its backing state");
        check(liveClass.fields().size() == 2,
            "a live class view reports its backing field count");
        expectUnsupported(() -> liveClass.fields().add(FieldState.Missing.INSTANCE),
            "an add through a live read-only class field list");
        try {
            liveClass.fields().get(2);
            fail("the live class field list's bounds check for get(2)");
        } catch (IndexOutOfBoundsException expected) {
            passed++;
        }
        expectNpe(() -> ClassOpsExecutor.liveClassView(null, 0, index -> null),
            "liveClassView(null, …)");
        expectNpe(() -> ClassOpsExecutor.liveClassView(CLS, 0, null),
            "liveClassView(…, null)");
        expectDefect(() -> ClassOpsExecutor.liveClassView(CLS, -1, index -> null),
            "a negative live class field count");
    }

    private static void expectIllegalState(Runnable runnable, String what) {
        try {
            runnable.run();
            fail("expected IllegalStateException for " + what
                + ", but no exception was raised");
        } catch (IllegalStateException expected) {
            passed++;
        } catch (Throwable other) {
            fail("expected IllegalStateException for " + what + ", got "
                + other.getClass().getSimpleName() + ": " + other.getMessage());
        }
    }

    private static void expectUnsupported(Runnable runnable, String what) {
        try {
            runnable.run();
            fail("expected UnsupportedOperationException for " + what
                + ", but no exception was raised");
        } catch (UnsupportedOperationException expected) {
            passed++;
        } catch (Throwable other) {
            fail("expected UnsupportedOperationException for " + what + ", got "
                + other.getClass().getSimpleName() + ": " + other.getMessage());
        }
    }

    // =========================================================================
    // 2. CLASS_DEFAULT
    // =========================================================================

    static void testClassNewLocalSuccessReorderAndOverlay() {
        System.out.println("-- CLASS_NEW(LOCAL) success: literal-order resolution, "
            + "declaration-order application/validation, tag, fresh identity, zero "
            + "return boundaries --");

        ClassLayout layout = layout(field("x", INT, true), field("tag", STRING, true),
            field("y", INT, false));
        ValueId vTag = nextValue();
        ValueId vX = nextValue();
        Map<ValueId, Value> values = new LinkedHashMap<>();
        values.put(vTag, Value.string("pt"));
        values.put(vX, new Value.Number(3));

        // Provided in literal order (tag before x — the reorder source);
        // boundaries in declaration order (x before tag).
        List<ProvidedEntry> provided = List.of(new ProvidedEntry("tag", vTag),
            new ProvidedEntry("x", vX));
        List<BoundaryEntry> entries = new ArrayList<>();
        entries.add(new BoundaryEntry("x", BoundaryKind.CLASS_LITERAL_FIELD,
            boundaryChild(null, BoundaryKind.CLASS_LITERAL_FIELD, INT, vX,
                FailurePolicyId.TYPE_DESCRIPTOR)));
        entries.add(new BoundaryEntry("tag", BoundaryKind.CLASS_LITERAL_FIELD,
            boundaryChild(null, BoundaryKind.CLASS_LITERAL_FIELD, STRING, vTag,
                FailurePolicyId.TYPE_DESCRIPTOR)));

        List<String> log = new ArrayList<>();
        RecordingValues recording = new RecordingValues(values, log);
        ScriptedDelegate delegate = new ScriptedDelegate(log, -1, null);
        ClassNewFixture fixture = classNewFixture(layout, provided, entries);
        // The boundary children parent to the CLASS_NEW op (the builder
        // pins the payload; the parentage is pinned after the op id is
        // known).
        Map<OpId, SemanticOp> boundaryOps = reparent(fixture, entries);

        Outcome<Value> outcome = ClassOpsExecutor.executeClassNewLocal(fixture.op(),
            recording, boundaryOps, Map.of(CLS, layout),
            delegate);

        check(log.subList(0, 2).equals(List.of(
                "resolve " + vTag, "resolve " + vX)),
            "provided values resolve in literal order (tag, x); got " + log);
        check(outcome instanceof Outcome.Success<Value> success
                && success.value() instanceof Value.Class instance
                && instance.classId().equals(CLS),
            "SUCCESS publishes a fresh instance tagged with the classId");
        if (outcome instanceof Outcome.Success<Value> success
                && success.value() instanceof Value.Class instance) {
            check(instance.fields().size() == 3
                    && fieldValue(instance, layout, "x").equals(new Value.Number(3))
                    && fieldValue(instance, layout, "tag").equals(Value.string("pt"))
                    && fieldState(instance, layout, "y") == FieldState.Missing.INSTANCE,
                "the instance holds the overlaid provided values in declaration order "
                    + "and the omitted optional stays missing");
        }
        check(log.get(2).equals("child[0] CLASS_LITERAL_FIELD <- number")
                && log.get(3).equals("child[1] CLASS_LITERAL_FIELD <- string"),
            "field validation runs in declaration order (x then tag — the provided "
                + "overlay reorder) with the pinned inputs; got " + log);
        check(delegate.calls() == 2, "exactly one boundary run per provided field");
        for (String line : log) {
            check(!line.contains("FUNCTION_RETURN"),
                "zero return boundaries: no FUNCTION_RETURN child ever runs (log line: "
                    + line + ")");
        }

        // Fresh identity per construction; equal contents.
        Outcome<Value> again = ClassOpsExecutor.executeClassNewLocal(fixture.op(),
            recording, boundaryOps, Map.of(CLS, layout),
            delegate);
        Value firstInstance = outcome instanceof Outcome.Success<Value> first
            ? first.value() : null;
        Value secondInstance = again instanceof Outcome.Success<Value> second
            ? second.value() : null;
        check(firstInstance != null && secondInstance != null
                && firstInstance != secondInstance
                && firstInstance.equals(secondInstance),
            "each successful construction publishes a fresh instance identity with "
                + "equal contents (the model's allocation rule)");
    }


    static void testRequiredPresence() {
        ClassLayout layout = layout(field("a", INT, true), field("b", INT, true),
            field("c", INT, false));
        ValueId b = nextValue();
        ClassNewFixture missing = classNewFixture(layout,
            List.of(new ProvidedEntry("b", b)), List.of());
        expectDefect(() -> ClassOpsExecutor.executeClassNewLocal(missing.op(),
            Map.of(b, new Value.Number(20)), Map.of(), Map.of(CLS, layout),
            (boundary, input) -> { throw new AssertionError("missing required must precede checks"); }),
            "missing required a is never filled");
        ClassLayout nullable = layout(field("a", RuntimeDescriptor.nullable(INT), true));
        ClassNewFixture absent = classNewFixture(nullable, List.of(), List.of());
        expectDefect(() -> ClassOpsExecutor.executeClassNewLocal(absent.op(), Map.of(),
            Map.of(), Map.of(CLS, nullable), (boundary, input) -> new BoundaryResult.Pass(input)),
            "required nullable is still required-present");
    }


    static void testExtraKeyRejectionBeforeBoundaries() {
        ClassLayout layout = layout(field("a", INT, false));
        ValueId x = nextValue(), z = nextValue();
        ClassNewFixture fixture = classNewFixture(layout,
            List.of(new ProvidedEntry("x", x), new ProvidedEntry("zz", z)), List.of());
        ScriptedDelegate delegate = new ScriptedDelegate(new ArrayList<>(), -1, null);
        Outcome<Value> outcome = ClassOpsExecutor.executeClassNewLocal(fixture.op(),
            Map.of(x, new Value.Number(1), z, new Value.Number(2)), Map.of(),
            Map.of(CLS, layout), delegate);
        check(outcome instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().policy() == FailurePolicyId.CLASS_CONSTRUCTION
                && failure.failure().failure().code() == DiagnosticCode.E8007
                && failure.failure().failure().message().equals(
                    "extra field 'x' in class '@corpus.classops/Point'")
                && failure.failure().origin().equals(fixture.op().origin()),
            "first source-order extra key has exact E8007 tuple and caller origin");
        check(delegate.calls() == 0, "extra key stops all field boundaries");
    }

    static void testBoundaryFailureStopsValidationAndPublishesNothing() {
        System.out.println("-- a failing boundary publishes no instance and stops "
            + "validation at the first failing field --");

        ClassLayout layout = layout(field("a", INT, true), field("b", INT, true),
            field("c", INT, true));
        ValueId vA = nextValue();
        ValueId vB = nextValue();
        ValueId vC = nextValue();
        Map<ValueId, Value> values = new LinkedHashMap<>();
        values.put(vA, new Value.Number(1));
        values.put(vB, new Value.Number(2));
        values.put(vC, new Value.Number(3));

        // Provided in scrambled literal order (c, a, b): resolution stays
        // literal, validation stays declaration order (a, b, c).
        List<ProvidedEntry> provided = List.of(new ProvidedEntry("c", vC),
            new ProvidedEntry("a", vA), new ProvidedEntry("b", vB));
        List<BoundaryEntry> entries = new ArrayList<>();
        entries.add(new BoundaryEntry("a", BoundaryKind.CLASS_LITERAL_FIELD,
            boundaryChild(null, BoundaryKind.CLASS_LITERAL_FIELD, INT, vA,
                FailurePolicyId.TYPE_DESCRIPTOR)));
        entries.add(new BoundaryEntry("b", BoundaryKind.CLASS_LITERAL_FIELD,
            boundaryChild(null, BoundaryKind.CLASS_LITERAL_FIELD, INT, vB,
                FailurePolicyId.TYPE_DESCRIPTOR)));
        entries.add(new BoundaryEntry("c", BoundaryKind.CLASS_LITERAL_FIELD,
            boundaryChild(null, BoundaryKind.CLASS_LITERAL_FIELD, INT, vC,
                FailurePolicyId.TYPE_DESCRIPTOR)));

        BoundaryFailure scripted = deal.semantic.ir.FailureProjections
            .kindFailure("number", "string");
        List<String> log = new ArrayList<>();
        ScriptedDelegate delegate = new ScriptedDelegate(log, 1, scripted);
        ClassNewFixture fixture = classNewFixture(layout, provided, entries);
        Map<OpId, SemanticOp> boundaryOps = reparent(fixture, entries);

        Outcome<Value> outcome = ClassOpsExecutor.executeClassNewLocal(fixture.op(),
            values, boundaryOps, Map.of(CLS, layout),
            delegate);

        check(outcome instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().message()
                    .equals("expected number")
                && failure.failure().origin().equals(fixture.op().origin()),
            "the first failing boundary (b, the second field in declaration order) "
                + "fails the op with that child's failure at the op origin");
        check(delegate.calls() == 2,
            "validation stopped at the first failing field — the third child never "
                + "ran; got " + delegate.calls() + " calls");
        check(log.get(log.size() - 2).equals("child[0] CLASS_LITERAL_FIELD <- number")
                && log.get(log.size() - 1).equals("child[1] CLASS_LITERAL_FIELD <- number"),
            "the boundary order is declaration order (a then b) despite the literal "
                + "order (c, a, b); got " + log);
        check(!(outcome instanceof Outcome.Success<Value>),
            "a failing construction publishes no instance — the result is absent and "
                + "the tag never runs");
    }

    static void testRealProjectionDelegate() {
        System.out.println("-- the production-delegate stand-in: the real "
            + "BoundaryExecutor drives the descriptor-kind policies --");

        // A kind mismatch on an int field fails E8001 at the op origin.
        ClassLayout intLayout = layout(field("a", INT, true));
        ValueId vA = nextValue();
        Map<ValueId, Value> values = Map.of(vA, Value.string("wrong"));
        List<ProvidedEntry> provided = List.of(new ProvidedEntry("a", vA));
        List<BoundaryEntry> entries = List.of(new BoundaryEntry("a",
            BoundaryKind.CLASS_LITERAL_FIELD,
            boundaryChild(null, BoundaryKind.CLASS_LITERAL_FIELD, INT, vA,
                FailurePolicyId.TYPE_DESCRIPTOR)));
        ClassNewFixture fixture = classNewFixture(intLayout, provided, entries);
        Map<OpId, SemanticOp> boundaryOps = reparent(fixture, entries);
        List<String> log = new ArrayList<>();
        Outcome<Value> mismatch = ClassOpsExecutor.executeClassNewLocal(fixture.op(),
            values, boundaryOps, Map.of(CLS, intLayout),
            realProjectionDelegate(log));
        check(mismatch instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().code() == DiagnosticCode.E8001
                && failure.failure().failure().policy() == FailurePolicyId.TYPE_DESCRIPTOR
                && failure.failure().failure().expected().equals("number")
                && failure.failure().failure().actual().equals("string")
                && failure.failure().failure().message().equals("expected number")
                && failure.failure().origin().equals(fixture.op().origin()),
            "the real delegate projects the int-field kind mismatch as E8001 "
                + "expected number at the op origin");

        // A function-typed field: a differing carried signature fails E8010.
        ClassLayout fnLayout = layout(field("f", FUNC_NULL, true));
        ValueId vF = nextValue();
        Map<ValueId, Value> fnValues = Map.of(vF,
            new Value.Function(FUNC_NULL_ASYNC));
        List<ProvidedEntry> fnProvided = List.of(new ProvidedEntry("f", vF));
        List<BoundaryEntry> fnEntries = List.of(new BoundaryEntry("f",
            BoundaryKind.CLASS_LITERAL_FIELD,
            boundaryChild(null, BoundaryKind.CLASS_LITERAL_FIELD, FUNC_NULL, vF,
                FailurePolicyId.FUNCTION_SIGNATURE)));
        ClassNewFixture fnFixture = classNewFixture(fnLayout, fnProvided, fnEntries);
        Map<OpId, SemanticOp> fnBoundaryOps = reparent(fnFixture, fnEntries);
        Outcome<Value> sigMismatch = ClassOpsExecutor.executeClassNewLocal(fnFixture.op(),
            fnValues, fnBoundaryOps, Map.of(CLS, fnLayout),
            realProjectionDelegate(null));
        check(sigMismatch instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().code() == DiagnosticCode.E8010
                && failure.failure().failure().policy() == FailurePolicyId.FUNCTION_SIGNATURE
                && failure.failure().failure().message().startsWith(
                    "function signature mismatch: expected"),
            "the real delegate projects the function-signature mismatch as E8010 "
                + "(descriptor-kind rule FUNCTION_SIGNATURE)");

        // The matching signature passes and the instance carries the function.
        ValueId vOk = nextValue();
        Map<ValueId, Value> okValues = Map.of(vOk, new Value.Function(FUNC_NULL));
        List<ProvidedEntry> okProvided = List.of(new ProvidedEntry("f", vOk));
        List<BoundaryEntry> okEntries = List.of(new BoundaryEntry("f",
            BoundaryKind.CLASS_LITERAL_FIELD,
            boundaryChild(null, BoundaryKind.CLASS_LITERAL_FIELD, FUNC_NULL, vOk,
                FailurePolicyId.FUNCTION_SIGNATURE)));
        ClassNewFixture okFixture = classNewFixture(fnLayout, okProvided, okEntries);
        Map<OpId, SemanticOp> okBoundaryOps = reparent(okFixture, okEntries);
        Outcome<Value> pass = ClassOpsExecutor.executeClassNewLocal(okFixture.op(),
            okValues, okBoundaryOps, Map.of(CLS, fnLayout),
            realProjectionDelegate(null));
        check(pass instanceof Outcome.Success<Value> success
                && success.value() instanceof Value.Class instance
                && fieldValue(instance, fnLayout, "f").equals(new Value.Function(FUNC_NULL)),
            "the matching function value passes its FUNCTION_SIGNATURE boundary and "
                + "the instance carries it");
    }


    static void testFreshInstancesPreserveProvidedAliases() {
        ClassLayout layout = layout(field("items", TABLE, true));
        ValueId input = nextValue();
        List<BoundaryEntry> entries = List.of(new BoundaryEntry("items", BoundaryKind.CLASS_LITERAL_FIELD,
            boundaryChild(null, BoundaryKind.CLASS_LITERAL_FIELD, TABLE, input, FailurePolicyId.TYPE_DESCRIPTOR)));
        ClassNewFixture fixture = classNewFixture(layout, List.of(new ProvidedEntry("items", input)),
            entries);
        SemanticTable<Value> table = new SemanticTable<>();
        Value.Table provided = new Value.Table(table);
        Value.Class first = (Value.Class) ((Outcome.Success<Value>) ClassOpsExecutor.executeClassNewLocal(
            fixture.op(), Map.of(input, provided), reparent(fixture, entries), Map.of(CLS, layout),
            (boundary, value) -> new BoundaryResult.Pass(value))).value();
        Value.Class second = (Value.Class) ((Outcome.Success<Value>) ClassOpsExecutor.executeClassNewLocal(
            fixture.op(), Map.of(input, provided), reparent(fixture, entries), Map.of(CLS, layout),
            (boundary, value) -> new BoundaryResult.Pass(value))).value();
        check(first != second, "construction creates distinct instances");
        check(fieldValue(first, layout, "items") == provided
                && fieldValue(second, layout, "items") == provided,
            "provided mutable values preserve reference identity, never deep-copy");
        table.put("changed", new Value.Number(7));
        check(((Value.Table) fieldValue(first, layout, "items")).table().keys().contains("changed")
                && ((Value.Table) fieldValue(second, layout, "items")).table().keys().contains("changed"),
            "provided alias mutation remains visible in both instances");
    }

    // =========================================================================
    // 4. Fail-closed defects
    // =========================================================================

    static void testFailClosedDefects() {
        System.out.println("-- fail-closed: shapes outside the pinned contracts are "
            + "producer defects, never DEAL projections --");

        ClassLayout layout = layout(field("a", INT, true));
        ValueId vA = nextValue();
        Map<ValueId, Value> values = Map.of(vA, new Value.Number(1));
        List<String> log = new ArrayList<>();
        BoundaryCheckRunner pass = new ScriptedDelegate(new ArrayList<>(), -1, null);

        List<ProvidedEntry> provided = List.of(new ProvidedEntry("a", vA));
        List<BoundaryEntry> entries = List.of(new BoundaryEntry("a",
            BoundaryKind.CLASS_LITERAL_FIELD,
            boundaryChild(null, BoundaryKind.CLASS_LITERAL_FIELD, INT, vA,
                FailurePolicyId.TYPE_DESCRIPTOR)));
        ClassNewFixture good = classNewFixture(layout, provided, entries);
        Map<OpId, SemanticOp> goodBoundaryOps = reparent(good, entries);
        Map<ClassId, ClassLayout> layouts = Map.of(CLS, layout);

        // Wrong op kind and non-pinned policy.
        expectDefect(() -> ClassOpsExecutor.executeClassNewLocal(
                op(SemanticOpKind.CONST,
                    new KindPayload.ConstPayload(new ScalarValue.Number(1)),
                    nextValue(), INT, FailurePolicyId.CLASS_CONSTRUCTION, null),
                Map.of(), Map.of(), Map.of(), pass),
            "executing a CONST op as CLASS_NEW (wrong kind)");
        expectDefect(() -> ClassOpsExecutor.executeClassNewLocal(
                opWithId(nextOpId(), SemanticOpKind.CLASS_NEW,
                    new KindPayload.ClassNewPayload(CLS, layout, List.of(),
                        ConstructionProvider.LOCAL, null, List.of()),
                    nextValue(), new RuntimeDescriptor.Class(CLS),
                    FailurePolicyId.NO_DEAL_FAILURE, null),
                Map.of(), Map.of(), layouts, pass),
            "a CLASS_NEW carrying a non-pinned failure policy");

        // Non-LOCAL owners and factory refs.
        expectDefect(() -> ClassOpsExecutor.executeClassNewLocal(
                opWithId(nextOpId(), SemanticOpKind.CLASS_NEW,
                    new KindPayload.ClassNewPayload(CLS, layout, List.of(),
                        ConstructionProvider.SHARED_FACTORY, null, List.of()),
                    nextValue(), new RuntimeDescriptor.Class(CLS),
                    FailurePolicyId.CLASS_CONSTRUCTION, null),
                Map.of(), Map.of(), layouts, pass),
            "a SHARED_FACTORY owner (the later epic child's surface)");
        expectDefect(() -> ClassOpsExecutor.executeClassNewLocal(
                opWithId(nextOpId(), SemanticOpKind.CLASS_NEW,
                    new KindPayload.ClassNewPayload(CLS, layout, List.of(),
                        ConstructionProvider.RETAINED_ABI, null, List.of()),
                    nextValue(), new RuntimeDescriptor.Class(CLS),
                    FailurePolicyId.CLASS_CONSTRUCTION, null),
                Map.of(), Map.of(), layouts, pass),
            "a RETAINED_ABI owner (E10's surface)");
        expectDefect(() -> ClassOpsExecutor.executeClassNewLocal(
                opWithId(nextOpId(), SemanticOpKind.CLASS_NEW,
                    new KindPayload.ClassNewPayload(CLS, layout, List.of(),
                        ConstructionProvider.LOCAL, new deal.semantic.ir.ClassFactoryId(1), List.of()),
                    nextValue(), new RuntimeDescriptor.Class(CLS),
                    FailurePolicyId.CLASS_CONSTRUCTION, null),
                Map.of(), Map.of(), layouts, pass),
            "a LOCAL CLASS_NEW carrying a non-null classFactoryRef");

        // Layout resolution failures.
        expectDefect(() -> ClassOpsExecutor.executeClassNewLocal(good.op(),
                values, goodBoundaryOps, Map.of(), pass),
            "an unresolvable layout in the layout-resolution context");
        ClassLayout foreign = new ClassLayout(CLS, List.of(field("z", INT, true)));
        expectDefect(() -> ClassOpsExecutor.executeClassNewLocal(good.op(),
                values, goodBoundaryOps, Map.of(CLS, foreign), pass),
            "a payload layout differing from the resolved layout");

        // Unresolvable and wrong-kind provided values.
        expectDefect(() -> ClassOpsExecutor.executeClassNewLocal(good.op(),
                Map.of(), goodBoundaryOps, layouts, pass),
            "an unresolvable provided-field prior step");
        expectDefect(() -> ClassOpsExecutor.executeClassNewLocal(good.op(),
                Map.of(vA, Value.Missing.INSTANCE), goodBoundaryOps,
                layouts, pass),
            "a provided value resolving to the internal Missing view (wrong-kind value)");

        // Boundary-child defects (each child parented to the op that names
        // it, so the targeted check is the one that fires).
        OpId kindOpId = nextOpId();
        SemanticOp wrongKindChild = boundaryChild(kindOpId, BoundaryKind.VARIABLE_DECLARATION,
            INT, vA, FailurePolicyId.TYPE_DESCRIPTOR);
        expectDefect(() -> ClassOpsExecutor.executeClassNewLocal(
                opWithId(kindOpId, SemanticOpKind.CLASS_NEW,
                    new KindPayload.ClassNewPayload(CLS, layout, providedList(vA),
                        ConstructionProvider.LOCAL, null,
                        List.of(new KindPayload.FieldBoundary("a",
                            BoundaryKind.CLASS_LITERAL_FIELD, wrongKindChild.opId()))),
                    nextValue(), new RuntimeDescriptor.Class(CLS),
                    FailurePolicyId.CLASS_CONSTRUCTION, null),
                values, Map.of(wrongKindChild.opId(), wrongKindChild), layouts,
                pass),
            "a field-boundary entry whose child carries a different boundary kind");
        SemanticOp unparented = boundaryChild(null, BoundaryKind.CLASS_LITERAL_FIELD,
            INT, vA, FailurePolicyId.TYPE_DESCRIPTOR);
        expectDefect(() -> ClassOpsExecutor.executeClassNewLocal(
                opWithId(nextOpId(), SemanticOpKind.CLASS_NEW,
                    new KindPayload.ClassNewPayload(CLS, layout, providedList(vA),
                        ConstructionProvider.LOCAL, null,
                        List.of(new KindPayload.FieldBoundary("a",
                            BoundaryKind.CLASS_LITERAL_FIELD, unparented.opId()))),
                    nextValue(), new RuntimeDescriptor.Class(CLS),
                    FailurePolicyId.CLASS_CONSTRUCTION, null),
                values, Map.of(unparented.opId(), unparented), layouts, pass),
            "an unparented boundary child (parentOpId mismatch)");
        OpId constOpId = nextOpId();
        SemanticOp constChild = opWithId(nextOpId(), SemanticOpKind.CONST,
            new KindPayload.ConstPayload(new ScalarValue.Number(1)),
            nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE, constOpId);
        expectDefect(() -> ClassOpsExecutor.executeClassNewLocal(
                opWithId(constOpId, SemanticOpKind.CLASS_NEW,
                    new KindPayload.ClassNewPayload(CLS, layout, providedList(vA),
                        ConstructionProvider.LOCAL, null,
                        List.of(new KindPayload.FieldBoundary("a",
                            BoundaryKind.CLASS_LITERAL_FIELD, constChild.opId()))),
                    nextValue(), new RuntimeDescriptor.Class(CLS),
                    FailurePolicyId.CLASS_CONSTRUCTION, null),
                values, Map.of(constChild.opId(), constChild), layouts, pass),
            "a field-boundary entry naming a non-BOUNDARY op");
        OpId policyOpId = nextOpId();
        SemanticOp badPolicy = boundaryChild(policyOpId, BoundaryKind.CLASS_LITERAL_FIELD,
            INT, vA, FailurePolicyId.JSON_TO_ERROR);
        expectDefect(() -> ClassOpsExecutor.executeClassNewLocal(
                opWithId(policyOpId, SemanticOpKind.CLASS_NEW,
                    new KindPayload.ClassNewPayload(CLS, layout, providedList(vA),
                        ConstructionProvider.LOCAL, null,
                        List.of(new KindPayload.FieldBoundary("a",
                            BoundaryKind.CLASS_LITERAL_FIELD, badPolicy.opId()))),
                    nextValue(), new RuntimeDescriptor.Class(CLS),
                    FailurePolicyId.CLASS_CONSTRUCTION, null),
                values, Map.of(badPolicy.opId(), badPolicy), layouts, pass),
            "a field boundary carrying a non-descriptor-kind policy");
        OpId descriptorOpId = nextOpId();
        SemanticOp badDescriptor = boundaryChild(descriptorOpId,
            BoundaryKind.CLASS_LITERAL_FIELD, STRING, vA, FailurePolicyId.TYPE_DESCRIPTOR);
        expectDefect(() -> ClassOpsExecutor.executeClassNewLocal(
                opWithId(descriptorOpId, SemanticOpKind.CLASS_NEW,
                    new KindPayload.ClassNewPayload(CLS, layout, providedList(vA),
                        ConstructionProvider.LOCAL, null,
                        List.of(new KindPayload.FieldBoundary("a",
                            BoundaryKind.CLASS_LITERAL_FIELD, badDescriptor.opId()))),
                    nextValue(), new RuntimeDescriptor.Class(CLS),
                    FailurePolicyId.CLASS_CONSTRUCTION, null),
                values, Map.of(badDescriptor.opId(), badDescriptor), layouts,
                pass),
            "a field boundary carrying a descriptor different from the field's "
                + "declared descriptor");
        ValueId otherValue = nextValue();
        OpId inputOpId = nextOpId();
        SemanticOp badInput = boundaryChild(inputOpId, BoundaryKind.CLASS_LITERAL_FIELD,
            INT, otherValue, FailurePolicyId.TYPE_DESCRIPTOR);
        expectDefect(() -> ClassOpsExecutor.executeClassNewLocal(
                opWithId(inputOpId, SemanticOpKind.CLASS_NEW,
                    new KindPayload.ClassNewPayload(CLS, layout, providedList(vA),
                        ConstructionProvider.LOCAL, null,
                        List.of(new KindPayload.FieldBoundary("a",
                            BoundaryKind.CLASS_LITERAL_FIELD, badInput.opId()))),
                    nextValue(), new RuntimeDescriptor.Class(CLS),
                    FailurePolicyId.CLASS_CONSTRUCTION, null),
                values, Map.of(badInput.opId(), badInput), layouts, pass),
            "a CLASS_LITERAL_FIELD boundary whose input is not the field's provided "
                + "value op id (input-wiring mismatch)");
        // Child-count/order/kind defects.
        expectDefect(() -> ClassOpsExecutor.executeClassNewLocal(
                opWithId(nextOpId(), SemanticOpKind.CLASS_NEW,
                    new KindPayload.ClassNewPayload(CLS, layout, providedList(vA),
                        ConstructionProvider.LOCAL, null, List.of()),
                    nextValue(), new RuntimeDescriptor.Class(CLS),
                    FailurePolicyId.CLASS_CONSTRUCTION, null),
                values, Map.of(), layouts, pass),
            "a provided field without a field-boundary entry (child-count mismatch)");
        OpId optionalBoundaryOpId = nextOpId();
        SemanticOp optionalChild = boundaryChild(optionalBoundaryOpId,
            BoundaryKind.CLASS_LITERAL_FIELD, INT, vA, FailurePolicyId.TYPE_DESCRIPTOR);
        ClassLayout optLayout = layout(field("a", INT, true), field("o", INT, false));
        expectDefect(() -> ClassOpsExecutor.executeClassNewLocal(
                opWithId(optionalBoundaryOpId, SemanticOpKind.CLASS_NEW,
                    new KindPayload.ClassNewPayload(CLS, optLayout, providedList(vA),
                        ConstructionProvider.LOCAL, null,
                        List.of(new KindPayload.FieldBoundary("a",
                                BoundaryKind.CLASS_LITERAL_FIELD, optionalChild.opId()),
                            new KindPayload.FieldBoundary("o",
                                BoundaryKind.CLASS_LITERAL_FIELD, optionalChild.opId()))),
                    nextValue(), new RuntimeDescriptor.Class(CLS),
                    FailurePolicyId.CLASS_CONSTRUCTION, null),
                values, Map.of(optionalChild.opId(), optionalChild), Map.of(CLS, optLayout),
                pass),
            "a boundary entry for an omitted optional field (child-count mismatch)");
    }

    private static List<KindPayload.ProvidedField> providedList(ValueId valueId) {
        return List.of(new KindPayload.ProvidedField("a", valueId));
    }

    // =========================================================================
    // 5. Determinism
    // =========================================================================

    static void testDeterminism() {
        System.out.println("-- determinism: repeated executions with equal inputs "
            + "produce equal results --");

        ClassLayout layout = layout(field("a", INT, true), field("b", INT, false));
        ValueId vA = nextValue();
        Map<ValueId, Value> values = Map.of(vA, new Value.Number(5));
        List<ProvidedEntry> provided = List.of(new ProvidedEntry("a", vA));
        List<BoundaryEntry> entries = List.of(new BoundaryEntry("a",
            BoundaryKind.CLASS_LITERAL_FIELD,
            boundaryChild(null, BoundaryKind.CLASS_LITERAL_FIELD, INT, vA,
                FailurePolicyId.TYPE_DESCRIPTOR)));
        ClassNewFixture fixture = classNewFixture(layout, provided, entries);
        Map<OpId, SemanticOp> boundaryOps = reparent(fixture, entries);

        Outcome<Value> first = ClassOpsExecutor.executeClassNewLocal(fixture.op(),
            values, boundaryOps, Map.of(CLS, layout),
            new ScriptedDelegate(new ArrayList<>(), -1, null));
        Outcome<Value> second = ClassOpsExecutor.executeClassNewLocal(fixture.op(),
            values, boundaryOps, Map.of(CLS, layout),
            new ScriptedDelegate(new ArrayList<>(), -1, null));
        check(first instanceof Outcome.Success<Value> s1
                && second instanceof Outcome.Success<Value> s2
                && s1.value().equals(s2.value()),
            "equal inputs produce equal published instances");
    }

    // =========================================================================
    // 6. Null arguments fail closed
    // =========================================================================


    static void testNullArgumentsFailClosed() {
        ClassLayout layout = layout(field("a", INT, false));
        ClassNewFixture fixture = classNewFixture(layout, List.of(), List.of());
        BoundaryCheckRunner pass = (boundary, value) -> new BoundaryResult.Pass(value);
        Map<ClassId, ClassLayout> layouts = Map.of(CLS, layout);
        expectNpe(() -> ClassOpsExecutor.executeClassNewLocal(null, Map.of(), Map.of(), layouts, pass), "null op");
        expectNpe(() -> ClassOpsExecutor.executeClassNewLocal(fixture.op(), null, Map.of(), layouts, pass), "null values");
        expectNpe(() -> ClassOpsExecutor.executeClassNewLocal(fixture.op(), Map.of(), null, layouts, pass), "null boundaries");
        expectNpe(() -> ClassOpsExecutor.executeClassNewLocal(fixture.op(), Map.of(), Map.of(), null, pass), "null layouts");
        expectNpe(() -> ClassOpsExecutor.executeClassNewLocal(fixture.op(), Map.of(), Map.of(), layouts, null), "null delegate");
    }

    // =========================================================================
    // Runner
    // =========================================================================

    private static final ModuleId FACTORY_OWNER = new ModuleId("owner.mod");
    private static final ModuleId FACTORY_CALLER = new ModuleId("caller.mod");
    private static final ClassId SHARED_CLASS = new ClassId(FACTORY_OWNER.path(), "Point");
    private static final deal.semantic.ir.ClassFactoryId FACTORY_ENTRY = new deal.semantic.ir.ClassFactoryId(7);

    private static SemanticOp sharedOp(ModuleId module, SemanticOpKind kind, KindPayload payload,
                                       SemanticValue result, OpResultType type, FailurePolicyId policy, OpId parent) {
        OpId id = new OpId(module, nextOpId++);
        OperationContractSnapshot contract = contractFor(kind, payload, type, List.of(), policy, "placeholder");
        contract = contractFor(kind, payload, type, List.of(), policy, ContractSnapshotCanonicalizer.digest(contract));
        SourceOrigin origin = new SourceOrigin(module.path() + ".deal",
            new SourceSpan(module.path() + ".deal", 1, 3, 1, 6), SourceOriginKind.USER, new AnchorId(0), parent);
        return new SemanticOp(id, kind, origin, result, type, List.of(), List.of(), payload, policy, contract);
    }

    private record SharedFixture(ClassLayout layout, SemanticOp factory, SemanticOp caller,
                                 Map<OpId, SemanticOp> ownerOps, Map<OpId, SemanticOp> boundaries,
                                 Map<ValueId, Value> provided) {
        deal.semantic.ir.ClassFactoryRegistry registry() {
            return new deal.semantic.ir.ClassFactoryRegistry(Map.of(FACTORY_ENTRY, factory.opId()));
        }
        Map<ClassId, ClassLayout> layouts() { return Map.of(SHARED_CLASS, layout); }
    }

    private static SharedFixture sharedFixture(List<String> providedNames) {
        ClassLayout layout = new ClassLayout(SHARED_CLASS,
            List.of(field("a", INT, true), field("b", INT, true), field("c", INT, false)));
        SemanticOp factory = sharedOp(FACTORY_OWNER, SemanticOpKind.CLASS_FACTORY,
            new KindPayload.ClassFactoryPayload(SHARED_CLASS, new OpId(FACTORY_CALLER, nextOpId++)), nextValue(), new RuntimeDescriptor.Class(SHARED_CLASS),
            FailurePolicyId.CLASS_CONSTRUCTION, null);
        List<KindPayload.ProvidedField> provided = new ArrayList<>();
        Map<ValueId, Value> values = new LinkedHashMap<>();
        for (String name : providedNames) {
            ValueId id = nextValue(); provided.add(new KindPayload.ProvidedField(name, id)); values.put(id, new Value.Number(20));
        }
        OpId callerId = new OpId(FACTORY_CALLER, nextOpId++);
        List<KindPayload.FieldBoundary> fields = new ArrayList<>();
        Map<OpId, SemanticOp> boundaries = new LinkedHashMap<>();
        for (String name : List.of("a", "b")) {
            KindPayload.ProvidedField input = provided.stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
            if (input == null) continue;
            BoundaryKind kind = BoundaryKind.CLASS_LITERAL_FIELD;
            SemanticOp boundary = sharedOp(FACTORY_CALLER, SemanticOpKind.BOUNDARY,
                new KindPayload.BoundaryPayload(kind, INT, input.valueOpId(),
                    new BoundaryRealization.RuntimeValidation("runtime-validation")), null, null,
                FailurePolicyId.TYPE_DESCRIPTOR, callerId);
            boundaries.put(boundary.opId(), boundary); fields.add(new KindPayload.FieldBoundary(name, kind, boundary.opId()));
        }
        KindPayload payload = new KindPayload.ClassNewPayload(SHARED_CLASS, layout, provided,
            ConstructionProvider.SHARED_FACTORY, FACTORY_ENTRY, fields);
        RuntimeDescriptor descriptor = new RuntimeDescriptor.Class(SHARED_CLASS);
        OperationContractSnapshot contract = contractFor(SemanticOpKind.CLASS_NEW, payload,
            descriptor, List.of(), FailurePolicyId.CLASS_CONSTRUCTION, "placeholder");
        contract = contractFor(SemanticOpKind.CLASS_NEW, payload, descriptor,
            List.of(), FailurePolicyId.CLASS_CONSTRUCTION, ContractSnapshotCanonicalizer.digest(contract));
        SemanticOp caller = new SemanticOp(callerId, SemanticOpKind.CLASS_NEW, originAt(null, 8), nextValue(),
            descriptor, List.of(), List.of(), payload, FailurePolicyId.CLASS_CONSTRUCTION, contract);
        Map<OpId, SemanticOp> ownerOps = new LinkedHashMap<>(); ownerOps.put(factory.opId(), factory);
        return new SharedFixture(layout, factory, caller, ownerOps, boundaries, values);
    }

    private static SemanticOp sharedCopy(SemanticOp op, KindPayload payload, SemanticValue result, OpResultType type) {
        OperationContractSnapshot contract = contractFor(op.kind(), payload, type, op.operandTypes(), op.failurePolicy(), "placeholder");
        contract = contractFor(op.kind(), payload, type, op.operandTypes(), op.failurePolicy(), ContractSnapshotCanonicalizer.digest(contract));
        return new SemanticOp(op.opId(), op.kind(), op.origin(), result, type, op.operands(), op.operandTypes(), payload,
            op.failurePolicy(), contract);
    }

    private static Outcome<Value> executeShared(SharedFixture f, BoundaryCheckRunner boundary) {
        return ClassOpsExecutor.executeClassNewSharedFactory(f.caller(), f.provided(), f.registry(),
            f.ownerOps(), f.boundaries(), f.layouts(), boundary);
    }


    private static void testSharedFactoryExecution() {
        SharedFixture f = sharedFixture(List.of("b", "a"));
        Outcome<Value> transfer = ClassOpsExecutor.executeClassFactory(f.factory(), f.caller(), f.layouts());
        check(transfer instanceof Outcome.Success<Value> success
                && success.value() instanceof Value.Class value
                && value.classId().equals(SHARED_CLASS)
                && value.fields().stream().allMatch(state -> state instanceof FieldState.Missing),
            "provider record carries identity and no initializer values");
        SemanticOp wrongTrigger = sharedOp(FACTORY_CALLER, SemanticOpKind.FIELD_READ,
            new KindPayload.FieldReadPayload(nextValue(), SHARED_CLASS, "a"), nextValue(), INT,
            FailurePolicyId.NO_DEAL_FAILURE, null);
        expectDefect(() -> ClassOpsExecutor.executeClassFactory(f.factory(), wrongTrigger, f.layouts()), "wrong provider trigger");
        expectDefect(() -> ClassOpsExecutor.executeClassFactory(f.factory(), f.factory(), f.layouts()), "provider self trigger");
        List<Value> instances = new ArrayList<>();
        for (int attempt = 0; attempt < 2; attempt++) {
            List<String> log = new ArrayList<>();
            ScriptedDelegate delegate = new ScriptedDelegate(log, -1, null);
            Outcome<Value> outcome = ClassOpsExecutor.executeClassNewSharedFactory(f.caller(),
                new RecordingValues(f.provided(), log), f.registry(), f.ownerOps(), f.boundaries(),
                f.layouts(), delegate);
            check(outcome instanceof Outcome.Success<Value> success
                    && success.value() instanceof Value.Class value
                    && new Value.Number(20).equals(fieldValue(value, f.layout(), "a"))
                    && new Value.Number(20).equals(fieldValue(value, f.layout(), "b"))
                    && fieldState(value, f.layout(), "c") instanceof FieldState.Missing,
                "shared construction publishes only provided values");
            List<String> expected = new ArrayList<>();
            for (ValueId id : f.provided().keySet()) expected.add("resolve " + id);
            expected.add("child[0] CLASS_LITERAL_FIELD <- number");
            expected.add("child[1] CLASS_LITERAL_FIELD <- number");
            check(log.equals(expected) && delegate.calls() == 2,
                "shared source-order resolution precedes declaration-order checks: " + log);
            if (outcome instanceof Outcome.Success<Value> success) instances.add(success.value());
        }
        check(instances.size() == 2 && instances.get(0) != instances.get(1)
                && instances.get(0).equals(instances.get(1)), "shared instances are fresh and deterministic");
        for (List<String> names : List.of(List.<String>of(), List.of("a"), List.of("b"))) {
            SharedFixture missing = sharedFixture(names);
            expectDefect(() -> executeShared(missing, (boundary, value) -> new BoundaryResult.Pass(value)),
                "shared required omission " + names);
        }
        SharedFixture extra = sharedFixture(List.of("a", "b", "zz", "x"));
        ScriptedDelegate noChecks = new ScriptedDelegate(new ArrayList<>(), -1, null);
        Outcome<Value> failure = executeShared(extra, noChecks);
        check(failure instanceof Outcome.Failure<Value> failed
                && failed.failure().failure().code() == DiagnosticCode.E8007
                && failed.failure().failure().message().equals("extra field 'zz' in class '@owner.mod/Point'")
                && failed.failure().origin().equals(extra.caller().origin()) && noChecks.calls() == 0,
            "shared first extra key has exact caller origin and prevents checks");
        BoundaryFailure selected = deal.semantic.ir.FailureProjections.kindFailure("number", "string");
        ScriptedDelegate stopped = new ScriptedDelegate(new ArrayList<>(), 0, selected);
        Outcome<Value> boundaryFailure = executeShared(f, stopped);
        check(boundaryFailure instanceof Outcome.Failure<Value> failed
                && failed.failure().failure().equals(selected) && stopped.calls() == 1,
            "shared first failing boundary stops publication");
        testSharedFactoryDefects(f);
    }

    private static void testSharedFactoryDefects(SharedFixture f) {
        BoundaryCheckRunner pass = (p, v) -> new BoundaryResult.Pass(v);
        KindPayload.ClassNewPayload p = (KindPayload.ClassNewPayload) f.caller().payload();
        List<KindPayload> malformed = List.of(
            new KindPayload.ClassNewPayload(SHARED_CLASS, f.layout(), p.providedFields(), ConstructionProvider.LOCAL, null, p.fieldBoundaries()),
            new KindPayload.ClassNewPayload(SHARED_CLASS, f.layout(), p.providedFields(), ConstructionProvider.SHARED_FACTORY, null, p.fieldBoundaries()));
        for (KindPayload broken : malformed) {
            SemanticOp caller = sharedCopy(f.caller(), broken, f.caller().result(), f.caller().resultType());
            expectDefect(() -> ClassOpsExecutor.executeClassNewSharedFactory(caller, f.provided(), f.registry(), f.ownerOps(),
                f.boundaries(), f.layouts(), pass), "shared payload owner/ref mutation");
        }
        expectDefect(() -> ClassOpsExecutor.executeClassNewSharedFactory(f.caller(), f.provided(),
            new deal.semantic.ir.ClassFactoryRegistry(Map.of()), f.ownerOps(), f.boundaries(), f.layouts(), pass), "missing registry binding");
        expectDefect(() -> ClassOpsExecutor.executeClassNewSharedFactory(f.caller(), f.provided(), f.registry(),
            Map.of(), f.boundaries(), f.layouts(), pass), "factory absent from owner lookup");
        SemanticOp notFactory = sharedOp(FACTORY_OWNER, SemanticOpKind.CONST, new KindPayload.ConstPayload(new ScalarValue.Number(1)),
            nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE, null);
        SemanticOp wrongClass = sharedCopy(f.factory(), new KindPayload.ClassFactoryPayload(new ClassId("owner.mod", "Other"),
            f.caller().opId()),
            f.factory().result(), f.factory().resultType());
        SemanticOp noResult = sharedCopy(f.factory(), f.factory().payload(), null, null);
        for (SemanticOp broken : List.of(notFactory, wrongClass, noResult)) {
            Map<OpId, SemanticOp> owner = new LinkedHashMap<>(f.ownerOps()); owner.put(broken.opId(), broken);
            deal.semantic.ir.ClassFactoryRegistry registry = new deal.semantic.ir.ClassFactoryRegistry(Map.of(FACTORY_ENTRY, broken.opId()));
            expectDefect(() -> ClassOpsExecutor.executeClassNewSharedFactory(f.caller(), f.provided(), registry, owner,
                f.boundaries(), f.layouts(), pass), "factory kind/class/result mutation");
        }
        Map<OpId, SemanticOp> wrongInput = new LinkedHashMap<>(f.boundaries());
        SemanticOp boundary = wrongInput.values().iterator().next();
        KindPayload.BoundaryPayload bp = (KindPayload.BoundaryPayload) boundary.payload();
        wrongInput.put(boundary.opId(), sharedCopy(boundary, new KindPayload.BoundaryPayload(bp.kind(), bp.descriptor(),
            nextValue(), bp.realization()), null, null));
        expectDefect(() -> ClassOpsExecutor.executeClassNewSharedFactory(f.caller(), f.provided(), f.registry(), f.ownerOps(),
            wrongInput, f.layouts(), pass), "provided boundary must use its recorded input");
        List<Runnable> guards = List.of(
            () -> ClassOpsExecutor.executeClassNewSharedFactory(null, f.provided(), f.registry(), f.ownerOps(), f.boundaries(), f.layouts(), pass),
            () -> ClassOpsExecutor.executeClassNewSharedFactory(f.caller(), null, f.registry(), f.ownerOps(), f.boundaries(), f.layouts(), pass),
            () -> ClassOpsExecutor.executeClassNewSharedFactory(f.caller(), f.provided(), null, f.ownerOps(), f.boundaries(), f.layouts(), pass),
            () -> ClassOpsExecutor.executeClassNewSharedFactory(f.caller(), f.provided(), f.registry(), null, f.boundaries(), f.layouts(), pass),
            () -> ClassOpsExecutor.executeClassNewSharedFactory(f.caller(), f.provided(), f.registry(), f.ownerOps(), null, f.layouts(), pass),
            () -> ClassOpsExecutor.executeClassNewSharedFactory(f.caller(), f.provided(), f.registry(), f.ownerOps(), f.boundaries(), null, pass),
            () -> ClassOpsExecutor.executeClassNewSharedFactory(f.caller(), f.provided(), f.registry(), f.ownerOps(), f.boundaries(), f.layouts(), null),
            () -> ClassOpsExecutor.executeClassFactory(null, f.caller(), f.layouts()),
            () -> ClassOpsExecutor.executeClassFactory(f.factory(), null, f.layouts()),
            () -> ClassOpsExecutor.executeClassFactory(f.factory(), f.caller(), null));
        guards.forEach(action -> expectNpe(action, "shared factory API null argument"));
    }

    public static void main(String[] args) {
        testSharedFactoryExecution();
        System.out.println("=== Class Ops Executor Tests (ISSUE-0512 K-D4/K-D11) ===\n");

        testValueViewClassification();
        testLiveContainerViews();
        testClassNewLocalSuccessReorderAndOverlay();
        testRequiredPresence();
        testExtraKeyRejectionBeforeBoundaries();
        testBoundaryFailureStopsValidationAndPublishesNothing();
        testRealProjectionDelegate();
        testFreshInstancesPreserveProvidedAliases();
        testFailClosedDefects();
        testDeterminism();
        testNullArgumentsFailClosed();

        System.out.println("\nClassOpsExecutorTest: " + passed + " passed, "
            + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
