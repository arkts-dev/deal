package deal.test;

import deal.diagnostics.DiagnosticCode;
import deal.semantic.ir.ActualKind;
import deal.semantic.ir.AnchorId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryFailure;
import deal.semantic.ir.ScalarValue;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.BoundaryRealization;
import deal.semantic.ir.ClassFactoryId;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ClassInterface;
import deal.semantic.ir.ClassLayout;
import deal.semantic.ir.ClassOpsExecutor;
import deal.semantic.ir.ClassOpsExecutor.BodyRunner;
import deal.semantic.ir.ClassOpsExecutor.BoundaryCheckRunner;
import deal.semantic.ir.ClassOpsExecutor.BoundaryResult;
import deal.semantic.ir.ClassOpsExecutor.Defect;
import deal.semantic.ir.ClassOpsExecutor.FieldState;
import deal.semantic.ir.ClassOpsExecutor.Outcome;
import deal.semantic.ir.ClassOpsExecutor.Value;
import deal.semantic.ir.ClosedSelector;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.DefaultOwner;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FieldInterface;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.OpResultType;
import deal.semantic.ir.OperationContractSnapshot;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticValue;
import deal.semantic.ir.SharedFactoryFacts;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.ValueId;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verifies the ISSUE-0514 {@link ClassOpsExecutor} cross-unit default
 * filling surface (class-construction-jsonable-operations K-D5 and the
 * K-D4 SHARED_FACTORY transfer; parent D16): the
 * {@code CLASS_FACTORY} execution contract — the
 * {@code CLASS_NEW(SHARED_FACTORY)} trigger, the
 * {@code ClassFactoryId}-resolved transfer, the declaration-order
 * default application with the skip-provided rule, the untagged
 * internal transfer instance, zero boundaries and zero return
 * boundaries, the executed cross-unit {@code parentOpId} pin, and the
 * owner-scope default evaluation — plus the caller-side
 * {@code CLASS_NEW(SHARED_FACTORY)} order: literal-order provided-value
 * resolution before the transfer (the
 * {@code jvm-xmod-class-construction-eval-order} pin), the transfer,
 * extra-key E8007 rejection after the completed defaults,
 * declaration-order provided overlay (the
 * {@code jvm-xmod-class-construction-defaults} reorder pin), the
 * {@code CLASS_DEFAULT_FIELD} extraction rule (the boundary's input
 * naming the owner factory result {@code ValueId} is the wiring; the
 * checked value is the transferred instance's named field), and the
 * tag-last fresh caller-side publication.
 *
 * <p>Pinned cases (the task verification):
 * <ol>
 *   <li>the trigger resolves the owner factory by
 *       {@code ClassFactoryId}; the factory executes its children in
 *       declaration order skipping provided fields (the fixture body
 *       runner records order and omission — a provided field's default
 *       never runs) and returns the default-filled untagged transfer
 *       instance;</li>
 *   <li>the factory's executed {@code parentOpId} equals the caller
 *       {@code CLASS_NEW} op id (cross-unit modules) — a non-{@code
 *       CLASS_NEW} trigger and a self-trigger are producer defects;</li>
 *   <li>the caller overlays provided fields in declaration order onto
 *       the transferred instance (the reorder pin asserted via the
 *       field-state result); the extraction rule checks the transferred
 *       instance's named field values through the boundary delegate;
 *       tag and publish only after all validation; zero return
 *       boundaries;</li>
 *   <li>a failing default child fails the caller op (no caller
 *       instance, completed default-block effects remain);</li>
 *   <li>a default referencing an owner-module binding resolves in the
 *       owner's scope (the body runner executes exactly the owner
 *       unit's {@code CLASS_DEFAULT} op instance);</li>
 *   <li>the extra-key scan runs after the completed transfer and
 *       publishes the exact E8007 template at the op origin with no
 *       boundary run;</li>
 *   <li>fail-closed defects — wrong owners/policies, a null factory
 *       ref, non-empty {@code classDefaultOpIds}, unresolvable
 *       registry/factory ops, wrong factory class/kind/policy/result,
 *       wiring mismatches, missing transferred fields — are producer
 *       defects, never DEAL projections; null arguments throw the
 *       documented NPEs;</li>
 *   <li>determinism — repeated drives with equal inputs produce equal
 *       results (the executor is stateless).</li>
 * </ol>
 */
public class SharedFactoryExecutorTest {

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
    // Fixed facts and semantic-id builders (two cross-unit modules)
    // =========================================================================

    private static final ModuleId OWNER = new ModuleId("owner.mod");
    private static final ModuleId CALLER = new ModuleId("caller.mod");
    private static final ClassId CLS = new ClassId(OWNER.path(), "Point");
    private static final RuntimeDescriptor INT = RuntimeDescriptor.Int.INSTANCE;
    private static final RuntimeDescriptor STRING = RuntimeDescriptor.String.INSTANCE;
    private static final ClassFactoryId FACTORY_ENTRY = new ClassFactoryId(7);

    private static int nextOp = 1;
    private static int nextValue = 1;
    private static int nextColumn = 1;

    private static OpId nextOpId(ModuleId module) {
        return new OpId(module, nextOp++);
    }

    private static ValueId nextValue() {
        return new ValueId(nextValue++);
    }

    private static SourceOrigin originAt(ModuleId module, OpId parent, int startColumn) {
        return new SourceOrigin(module.path() + ".deal",
            new SourceSpan(module.path() + ".deal", 1, startColumn, 1, startColumn + 3),
            SourceOriginKind.USER, new AnchorId(0), parent);
    }

    private static SourceOrigin nextOrigin(ModuleId module, OpId parent) {
        int column = nextColumn;
        nextColumn += 10;
        return originAt(module, parent, column);
    }

    private static OperationContractSnapshot contractFor(SemanticOpKind kind, KindPayload payload,
            OpResultType resultType, List<RuntimeDescriptor> operandTypes,
            FailurePolicyId policy, String digest) {
        ClosedSelector selector = payload instanceof KindPayload.SelectorCarrying carrying
            ? carrying.selector() : null;
        return new OperationContractSnapshot(OperationContractSnapshot.VERSION, kind, resultType,
            operandTypes, selector, payload, policy, List.of(), digest);
    }

    private static SemanticOp opWithId(ModuleId module, OpId id, SemanticOpKind kind,
            KindPayload payload, SemanticValue result, OpResultType resultType,
            FailurePolicyId policy, OpId parent) {
        OperationContractSnapshot contract =
            contractFor(kind, payload, resultType, List.of(), policy, "placeholder");
        String digest = ContractSnapshotCanonicalizer.digest(contract);
        contract = contractFor(kind, payload, resultType, List.of(), policy, digest);
        return new SemanticOp(id, kind, nextOrigin(module, parent), result, resultType,
            List.of(), List.of(), payload, policy, contract);
    }

    private static SemanticOp opIn(ModuleId module, SemanticOpKind kind, KindPayload payload,
            SemanticValue result, OpResultType resultType, FailurePolicyId policy,
            OpId parent) {
        return opWithId(module, nextOpId(module), kind, payload, result, resultType, policy,
            parent);
    }

    private static ClassLayout.FieldLayout field(String name, RuntimeDescriptor descriptor,
                                                 boolean required) {
        return new ClassLayout.FieldLayout(name, descriptor, required, DefaultOwner.LOCAL);
    }

    private static ClassLayout layout(ClassLayout.FieldLayout... fields) {
        return new ClassLayout(CLS, List.of(fields));
    }

    /** A detached owner-side CLASS_DEFAULT op (K-D12: no static parent). */
    private static SemanticOp defaultOp(String field, SemanticValue result,
                                        RuntimeDescriptor resultType, FailurePolicyId policy) {
        return opIn(OWNER, SemanticOpKind.CLASS_DEFAULT,
            new KindPayload.ClassDefaultPayload(CLS, field, new BlockId(1)),
            result, resultType, policy, null);
    }

    /** A parented field-boundary child (K-D4 parentage pin). */
    private static SemanticOp boundaryChild(ModuleId module, OpId parentId, BoundaryKind kind,
            RuntimeDescriptor descriptor, ValueId input, FailurePolicyId policy) {
        return opIn(module, SemanticOpKind.BOUNDARY,
            new KindPayload.BoundaryPayload(kind, descriptor, input,
                new BoundaryRealization.RuntimeValidation("runtime-validation")),
            null, null, policy, parentId);
    }

    /** The owner-side CLASS_FACTORY op registered under FACTORY_ENTRY. */
    private static SemanticOp factoryOp(List<SemanticOp> defaults, OpId callerOpRef) {
        List<OpId> defaultIds = new ArrayList<>();
        for (SemanticOp defaultOp : defaults) {
            defaultIds.add(defaultOp.opId());
        }
        return opIn(OWNER, SemanticOpKind.CLASS_FACTORY,
            new KindPayload.ClassFactoryPayload(CLS, defaultIds, callerOpRef),
            nextValue(), new RuntimeDescriptor.Class(CLS),
            FailurePolicyId.CLASS_CONSTRUCTION, null);
    }

    private record ProvidedEntry(String name, ValueId valueId) {
    }

    private record BoundaryEntry(String field, BoundaryKind kind, SemanticOp child) {
    }

    private record SharedNewFixture(SemanticOp op, Map<OpId, SemanticOp> boundaryOps) {
    }

    /**
     * Builds a validated-shape caller-side CLASS_NEW(SHARED_FACTORY) op
     * with the pinned children, then re-parents the boundary children to
     * the CLASS_NEW op id (the validator-pinned K-D4 parentage).
     */
    private static SharedNewFixture sharedNewFixture(ClassLayout layout,
            List<ProvidedEntry> provided, ClassFactoryId factoryRef,
            List<BoundaryEntry> entries) {
        OpId opId = nextOpId(CALLER);
        List<KindPayload.ProvidedField> providedFields = new ArrayList<>();
        for (ProvidedEntry entry : provided) {
            providedFields.add(new KindPayload.ProvidedField(entry.name(), entry.valueId()));
        }
        List<KindPayload.FieldBoundary> boundaries = new ArrayList<>();
        Map<OpId, SemanticOp> boundaryOps = new LinkedHashMap<>();
        for (BoundaryEntry entry : entries) {
            KindPayload.BoundaryPayload payload =
                (KindPayload.BoundaryPayload) entry.child().payload();
            OpId childId = entry.child().opId();
            SourceOrigin origin = new SourceOrigin(entry.child().origin().sourceId(),
                entry.child().origin().span(), entry.child().origin().kind(),
                entry.child().origin().anchorId(), opId);
            OperationContractSnapshot contract =
                contractFor(SemanticOpKind.BOUNDARY, payload, null, List.of(),
                    entry.child().failurePolicy(), "placeholder");
            String digest = ContractSnapshotCanonicalizer.digest(contract);
            contract = contractFor(SemanticOpKind.BOUNDARY, payload, null, List.of(),
                entry.child().failurePolicy(), digest);
            SemanticOp child = new SemanticOp(childId, SemanticOpKind.BOUNDARY, origin,
                null, null, List.of(), List.of(), payload, entry.child().failurePolicy(),
                contract);
            boundaries.add(new KindPayload.FieldBoundary(entry.field(), entry.kind(), childId));
            boundaryOps.put(childId, child);
        }
        SemanticOp op = opWithId(CALLER, opId, SemanticOpKind.CLASS_NEW,
            new KindPayload.ClassNewPayload(CLS, layout, providedFields,
                DefaultOwner.SHARED_FACTORY, List.of(), factoryRef, boundaries),
            nextValue(), new RuntimeDescriptor.Class(CLS),
            FailurePolicyId.CLASS_CONSTRUCTION, null);
        return new SharedNewFixture(op, boundaryOps);
    }

    /** The value of the named declaration-order field of one instance, or null when missing. */
    private static Value fieldValue(Value.Class instance, ClassLayout layout, String name) {
        for (int i = 0; i < layout.fields().size(); i++) {
            if (layout.fields().get(i).name().equals(name)) {
                FieldState state = instance.fields().get(i);
                return state instanceof FieldState.Present present ? present.value() : null;
            }
        }
        return null;
    }

    // =========================================================================
    // Recording fixtures
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

    /** A recording body runner: records every default-block invocation and returns the scripted value. */
    private static final class RecordingBodyRunner implements BodyRunner {

        private final List<String> log;
        private final Map<OpId, Value> produced;
        private final Set<OpId> failOn;
        private int calls = 0;

        RecordingBodyRunner(List<String> log, Map<OpId, Value> produced) {
            this(log, produced, Set.of());
        }

        RecordingBodyRunner(List<String> log, Map<OpId, Value> produced, Set<OpId> failOn) {
            this.log = log;
            this.produced = produced;
            this.failOn = failOn;
        }

        int calls() {
            return calls;
        }

        @Override
        public Value runDefault(SemanticOp defaultOp) {
            calls++;
            log.add("default " + defaultOp.opId());
            if (failOn.contains(defaultOp.opId())) {
                throw new IllegalStateException("scripted default-block failure of "
                    + defaultOp.opId());
            }
            return produced.get(defaultOp.opId());
        }
    }

    /** A pass-through/fail-first boundary fixture recording every call. */
    private static final class ScriptedDelegate implements BoundaryCheckRunner {

        private final List<String> log;
        private final int failAtIndex;
        private int calls = 0;

        ScriptedDelegate(List<String> log, int failAtIndex) {
            this.log = log;
            this.failAtIndex = failAtIndex;
        }

        int calls() {
            return calls;
        }

        @Override
        public BoundaryResult run(KindPayload.BoundaryPayload boundary, Value input) {
            int index = calls++;
            log.add("child[" + index + "] " + boundary.kind() + " <- " + input.actualKind().token());
            if (index == failAtIndex) {
                return new BoundaryResult.Fail(BoundaryFailureFixture.failure());
            }
            return new BoundaryResult.Pass(input);
        }
    }

    /** A pinned registry-row failure for the fail-first fixture. */
    private static final class BoundaryFailureFixture {
        static BoundaryFailure failure() {
            return BoundaryFailure.fromRow(
                FailureContractRegistry.row(FailurePolicyId.TYPE_DESCRIPTOR), 0,
                INT.canonicalSpecText(), "string", Map.of(), null);
        }
    }

    // =========================================================================
    // 1. The factory trigger, skip-provided rule, and the untagged transfer
    // =========================================================================

    static void testFactoryTriggerSkipProvidedAndTransfer() {
        System.out.println("-- CLASS_FACTORY: the trigger resolves the owner factory, "
            + "children run in declaration order skipping provided fields, and the "
            + "transfer returns the default-filled untagged instance --");

        ClassLayout layout = layout(field("a", INT, true), field("b", INT, true),
            field("c", INT, false));
        SemanticOp defA = defaultOp("a", nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE);
        SemanticOp defB = defaultOp("b", nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE);
        Map<OpId, Value> produced = new LinkedHashMap<>();
        produced.put(defA.opId(), new Value.Int(10));
        produced.put(defB.opId(), new Value.Int(99));
        SemanticOp factory = factoryOp(List.of(defA, defB), nextOpId(CALLER));
        Map<OpId, SemanticOp> defaultOps = Map.of(defA.opId(), defA, defB.opId(), defB);

        // The triggering caller op: a caller-module CLASS_NEW (the
        // executed parentOpId pin — the factory's events parent to this
        // op, cross-unit).
        SemanticOp caller = sharedNewFixture(layout, List.of(), FACTORY_ENTRY, List.of()).op();

        List<String> log = new ArrayList<>();
        RecordingBodyRunner body = new RecordingBodyRunner(log, produced);
        Outcome<Value> outcome = ClassOpsExecutor.executeClassFactory(factory, caller,
            defaultOps, Map.of(CLS, layout), new LinkedHashSet<>(List.of("b")), body);

        check(outcome instanceof Outcome.Success<Value> success
                && success.value() instanceof Value.Class transfer,
            "the factory returns the internal transfer instance");
        if (outcome instanceof Outcome.Success<Value> success
                && success.value() instanceof Value.Class transfer) {
            check(transfer.classId().equals(CLS),
                "the transfer carries the class identity; got " + transfer.classId());
            check(transfer.fields().size() == 3
                    && fieldValue(transfer, layout, "a").equals(new Value.Int(10))
                    && fieldValue(transfer, layout, "b") == null
                    && fieldValue(transfer, layout, "c") == null,
                "the transfer fills the omitted defaulted field a, leaves the provided "
                    + "field b missing (the caller overlays it), and the optional c "
                    + "missing — the untagged internal shape");
        }
        check(body.calls() == 1
                && log.contains("default " + defA.opId())
                && !log.contains("default " + defB.opId()),
            "only the omitted field's default block runs — a provided field's default "
                + "is never executed by the BodyRunner fixture (the skip-provided "
                + "rule); got " + log);

        // The executed parentOpId pin (K-D5/K-D12): the factory executes
        // keyed to the triggering caller op — cross-unit modules.
        check(caller.opId().module().equals(CALLER) && factory.opId().module().equals(OWNER),
            "the triggering caller op belongs to the caller module and the factory to "
                + "the owner module (the cross-unit parentOpId shape)");
        check(factory.origin().parentOpId() == null,
            "the factory op's static origin parentOpId is absent (the executed parent "
                + "is the dynamic trigger, never a static parentage)");

        // Fail-closed trigger defects: a non-CLASS_NEW trigger and a
        // self-trigger never execute.
        SemanticOp fieldRead = opIn(CALLER, SemanticOpKind.FIELD_READ,
            new KindPayload.FieldReadPayload(nextValue(), CLS, "a"),
            nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE, null);
        expectDefect(() -> ClassOpsExecutor.executeClassFactory(factory, fieldRead,
                defaultOps, Map.of(CLS, layout), Set.of(), body),
            "a non-CLASS_NEW trigger (the executed parentOpId pin)");
        expectDefect(() -> ClassOpsExecutor.executeClassFactory(factory, factory,
                defaultOps, Map.of(CLS, layout), Set.of(), body),
            "the factory as its own trigger");
    }

    // =========================================================================
    // 2. The cross-unit full drive: transfer, overlay, extraction, tag
    // =========================================================================

    static void testClassNewSharedFactoryFullDrive() {
        System.out.println("-- CLASS_NEW(SHARED_FACTORY): literal-order resolution, the "
            + "transfer, declaration-order overlay, the extraction rule, tag-last "
            + "publication, zero return boundaries --");

        ClassLayout layout = layout(field("a", INT, true), field("b", INT, true),
            field("c", INT, false));
        SemanticOp defA = defaultOp("a", nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE);
        SemanticOp defB = defaultOp("b", nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE);
        Map<OpId, Value> produced = new LinkedHashMap<>();
        produced.put(defA.opId(), new Value.Int(10));
        produced.put(defB.opId(), new Value.Int(99));
        SemanticOp factory = factoryOp(List.of(defA, defB), nextOpId(CALLER));
        ValueId factoryResult = (ValueId) factory.result();

        ValueId vB = nextValue();
        Map<ValueId, Value> values = Map.of(vB, new Value.Int(20));
        List<BoundaryEntry> entries = new ArrayList<>();
        entries.add(new BoundaryEntry("a", BoundaryKind.CLASS_DEFAULT_FIELD,
            boundaryChild(CALLER, null, BoundaryKind.CLASS_DEFAULT_FIELD, INT, factoryResult,
                FailurePolicyId.TYPE_DESCRIPTOR)));
        entries.add(new BoundaryEntry("b", BoundaryKind.CLASS_LITERAL_FIELD,
            boundaryChild(CALLER, null, BoundaryKind.CLASS_LITERAL_FIELD, INT, vB,
                FailurePolicyId.TYPE_DESCRIPTOR)));
        SharedNewFixture fixture = sharedNewFixture(layout,
            List.of(new ProvidedEntry("b", vB)), FACTORY_ENTRY, entries);

        Map<OpId, SemanticOp> ownerOps = new LinkedHashMap<>();
        ownerOps.put(factory.opId(), factory);
        ownerOps.put(defA.opId(), defA);
        ownerOps.put(defB.opId(), defB);

        List<String> log = new ArrayList<>();
        RecordingValues recording = new RecordingValues(values, log);
        RecordingBodyRunner body = new RecordingBodyRunner(log, produced);
        ScriptedDelegate delegate = new ScriptedDelegate(log, -1);

        Outcome<Value> outcome = ClassOpsExecutor.executeClassNewSharedFactory(fixture.op(),
            recording, new ClassFactoryRegistry(Map.of(FACTORY_ENTRY, factory.opId())),
            ownerOps, fixture.boundaryOps(), Map.of(CLS, layout), delegate, body);

        check(outcome instanceof Outcome.Success<Value> success
                && success.value() instanceof Value.Class instance
                && instance.classId().equals(CLS),
            "SUCCESS publishes the fresh caller-side instance tagged with the classId");
        if (outcome instanceof Outcome.Success<Value> success
                && success.value() instanceof Value.Class instance) {
            check(instance.fields().size() == 3
                    && fieldValue(instance, layout, "a").equals(new Value.Int(10))
                    && fieldValue(instance, layout, "b").equals(new Value.Int(20))
                    && fieldValue(instance, layout, "c") == null,
                "the published instance overlays the provided b onto the "
                    + "factory-filled a in declaration order (the reorder pin); the "
                    + "omitted optional c stays missing");
        }
        // The eval-order pin: the provided value resolves in literal
        // order in the caller before any owner default runs (the
        // transfer follows the completed caller-side operands).
        check(log.get(0).equals("resolve " + vB),
            "the provided value resolves first, in the caller, in literal order; got "
                + log.get(0));
        int firstDefault = log.indexOf("default " + defA.opId());
        int lastResolve = -1;
        for (int i = 0; i < log.size(); i++) {
            if (log.get(i).startsWith("resolve ")) {
                lastResolve = i;
            }
        }
        check(firstDefault >= 0 && lastResolve >= 0 && lastResolve < firstDefault,
            "every provided value resolves before the owner default runs (provided "
                + "values complete before the transfer — the eval-order pin); got " + log);
        check(body.calls() == 1 && log.contains("default " + defA.opId())
                && !log.contains("default " + defB.opId()),
            "the factory skips the provided field's default (b provided — never runs); "
                + "got " + log);
        // The extraction rule: the CLASS_DEFAULT_FIELD boundary checked
        // the transferred instance's named field (the produced default
        // 10), and the CLASS_LITERAL_FIELD checked the provided 20 — in
        // declaration order (a before b, the reorder pin observable).
        check(log.contains("child[0] CLASS_DEFAULT_FIELD <- int")
                && log.contains("child[1] CLASS_LITERAL_FIELD <- int"),
            "the boundaries run in declaration order (a defaulted before b provided) "
                + "with the extracted and provided inputs; got " + log);
        for (String line : log) {
            check(!line.contains("FUNCTION_RETURN"),
                "zero return boundaries: no FUNCTION_RETURN child ever runs (log line: "
                    + line + ")");
        }

        // Fresh identity per construction; equal contents; statelessness.
        Outcome<Value> again = ClassOpsExecutor.executeClassNewSharedFactory(fixture.op(),
            recording, new ClassFactoryRegistry(Map.of(FACTORY_ENTRY, factory.opId())),
            ownerOps, fixture.boundaryOps(), Map.of(CLS, layout), delegate, body);
        Value firstInstance = outcome instanceof Outcome.Success<Value> first
            ? first.value() : null;
        Value secondInstance = again instanceof Outcome.Success<Value> second
            ? second.value() : null;
        check(firstInstance != null && secondInstance != null
                && firstInstance != secondInstance
                && firstInstance.equals(secondInstance),
            "each successful construction publishes a fresh caller-side instance "
                + "identity with equal contents (the model's allocation rule)");
    }

    // =========================================================================
    // 3. The extra-key scan after the completed transfer
    // =========================================================================

    static void testExtraKeyScanAfterTransfer() {
        System.out.println("-- the extra-key scan runs after the completed transfer and "
            + "before any provided application or field validation --");

        ClassLayout layout = layout(field("a", INT, true));
        SemanticOp defA = defaultOp("a", nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE);
        Map<OpId, Value> produced = Map.of(defA.opId(), new Value.Int(10));
        SemanticOp factory = factoryOp(List.of(defA), nextOpId(CALLER));
        ValueId factoryResult = (ValueId) factory.result();

        ValueId vZz = nextValue();
        ValueId vX = nextValue();
        Map<ValueId, Value> values = new LinkedHashMap<>();
        values.put(vZz, new Value.Int(2));
        values.put(vX, new Value.Int(1));
        List<BoundaryEntry> entries = List.of(new BoundaryEntry("a",
            BoundaryKind.CLASS_DEFAULT_FIELD,
            boundaryChild(CALLER, null, BoundaryKind.CLASS_DEFAULT_FIELD, INT, factoryResult,
                FailurePolicyId.TYPE_DESCRIPTOR)));
        SharedNewFixture fixture = sharedNewFixture(layout,
            List.of(new ProvidedEntry("zz", vZz), new ProvidedEntry("x", vX)),
            FACTORY_ENTRY, entries);

        Map<OpId, SemanticOp> ownerOps = new LinkedHashMap<>();
        ownerOps.put(factory.opId(), factory);
        ownerOps.put(defA.opId(), defA);

        List<String> log = new ArrayList<>();
        RecordingBodyRunner body = new RecordingBodyRunner(log, produced);
        ScriptedDelegate delegate = new ScriptedDelegate(log, -1);
        Outcome<Value> outcome = ClassOpsExecutor.executeClassNewSharedFactory(fixture.op(),
            values, new ClassFactoryRegistry(Map.of(FACTORY_ENTRY, factory.opId())),
            ownerOps, fixture.boundaryOps(), Map.of(CLS, layout), delegate, body);

        check(outcome instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().policy() == FailurePolicyId.CLASS_CONSTRUCTION
                && failure.failure().failure().code() == DiagnosticCode.E8007,
            "the first extra key fails CLASS_CONSTRUCTION with E8007");
        if (outcome instanceof Outcome.Failure<Value> failure) {
            check(failure.failure().failure().message()
                    .equals("extra field 'zz' in class '@owner.mod/Point'"),
                "the exact template extra field '{field}' in class '{classId}' names the "
                    + "first extra key in provided-source order; got "
                    + failure.failure().failure().message());
            check(failure.failure().origin().equals(fixture.op().origin()),
                "the E8007 origin is the operation origin (the caller CLASS_NEW)");
        }
        check(log.contains("default " + defA.opId()),
            "default application completed before the scan (the completed default "
                + "effects are observable); got " + log);
        check(delegate.calls() == 0,
            "no provided application and no field validation ran after the scan "
                + "(zero boundary calls); got " + delegate.calls());
    }

    // =========================================================================
    // 4. A failing default child fails the triggering caller op
    // =========================================================================

    static void testFailingDefaultChildFailsCaller() {
        System.out.println("-- a failing default child fails the caller op: no caller "
            + "instance, completed default-block effects remain --");

        ClassLayout layout = layout(field("a", INT, true), field("b", INT, true));
        SemanticOp defA = defaultOp("a", nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE);
        SemanticOp defB = defaultOp("b", nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE);
        Map<OpId, Value> produced = new LinkedHashMap<>();
        produced.put(defA.opId(), new Value.Int(10));
        produced.put(defB.opId(), new Value.Int(99));
        SemanticOp factory = factoryOp(List.of(defA, defB), nextOpId(CALLER));
        ValueId factoryResult = (ValueId) factory.result();
        List<BoundaryEntry> entries = List.of(
            new BoundaryEntry("a", BoundaryKind.CLASS_DEFAULT_FIELD,
                boundaryChild(CALLER, null, BoundaryKind.CLASS_DEFAULT_FIELD, INT,
                    factoryResult, FailurePolicyId.TYPE_DESCRIPTOR)),
            new BoundaryEntry("b", BoundaryKind.CLASS_DEFAULT_FIELD,
                boundaryChild(CALLER, null, BoundaryKind.CLASS_DEFAULT_FIELD, INT,
                    factoryResult, FailurePolicyId.TYPE_DESCRIPTOR)));
        SharedNewFixture fixture = sharedNewFixture(layout, List.of(), FACTORY_ENTRY, entries);

        Map<OpId, SemanticOp> ownerOps = new LinkedHashMap<>();
        ownerOps.put(factory.opId(), factory);
        ownerOps.put(defA.opId(), defA);
        ownerOps.put(defB.opId(), defB);

        List<String> log = new ArrayList<>();
        RecordingBodyRunner body = new RecordingBodyRunner(log, produced, Set.of(defB.opId()));
        ScriptedDelegate delegate = new ScriptedDelegate(log, -1);
        boolean threw = false;
        try {
            ClassOpsExecutor.executeClassNewSharedFactory(fixture.op(), Map.of(),
                new ClassFactoryRegistry(Map.of(FACTORY_ENTRY, factory.opId())),
                ownerOps, fixture.boundaryOps(), Map.of(CLS, layout), delegate, body);
        } catch (IllegalStateException expected) {
            threw = true;
            check(expected.getMessage().contains("scripted default-block failure of "
                    + defB.opId()),
                "the failing default child's throw propagates out of the caller's "
                    + "transfer (the failing child fails the triggering caller op); got "
                    + expected.getMessage());
        }
        check(threw, "the failing default child propagated (no caller outcome was "
            + "published — no partial instance, the tag never runs)");
        check(log.contains("default " + defA.opId()),
            "the completed default-block effects remain (the first child ran before "
                + "the failure); got " + log);
        check(delegate.calls() == 0,
            "no field validation ran after the failing transfer (zero boundary "
                + "calls); got " + delegate.calls());
    }

    // =========================================================================
    // 5. Owner-scope default evaluation
    // =========================================================================

    static void testOwnerScopeDefaultResolution() {
        System.out.println("-- a default referencing an owner-module binding resolves in "
            + "the owner's scope (the body runner executes the owner unit's op) --");

        ClassLayout layout = layout(field("a", INT, true));
        // The owner's CLASS_DEFAULT op of field a: its default block
        // references the owner-module binding `base` (the block's
        // BINDING_LOAD resolves in the owner unit — the owner-scope
        // resolution surface).
        SemanticOp defA = defaultOp("a", nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE);
        SemanticOp factory = factoryOp(List.of(defA), nextOpId(CALLER));
        ValueId factoryResult = (ValueId) factory.result();
        List<BoundaryEntry> entries = List.of(new BoundaryEntry("a",
            BoundaryKind.CLASS_DEFAULT_FIELD,
            boundaryChild(CALLER, null, BoundaryKind.CLASS_DEFAULT_FIELD, INT, factoryResult,
                FailurePolicyId.TYPE_DESCRIPTOR)));
        SharedNewFixture fixture = sharedNewFixture(layout, List.of(), FACTORY_ENTRY, entries);

        Map<OpId, SemanticOp> ownerOps = new LinkedHashMap<>();
        ownerOps.put(factory.opId(), factory);
        ownerOps.put(defA.opId(), defA);

        List<SemanticOp> invoked = new ArrayList<>();
        BodyRunner ownerScope = defaultOpArg -> {
            invoked.add(defaultOpArg);
            return new Value.Int(42);
        };
        ScriptedDelegate delegate = new ScriptedDelegate(new ArrayList<>(), -1);
        Outcome<Value> outcome = ClassOpsExecutor.executeClassNewSharedFactory(fixture.op(),
            Map.of(), new ClassFactoryRegistry(Map.of(FACTORY_ENTRY, factory.opId())),
            ownerOps, fixture.boundaryOps(), Map.of(CLS, layout), delegate, ownerScope);

        check(outcome instanceof Outcome.Success<Value>,
            "the owner-scope drive succeeds; got " + outcome);
        check(invoked.size() == 1 && invoked.get(0) == defA
                && invoked.get(0).opId().module().equals(OWNER),
            "the body runner executed exactly the owner unit's CLASS_DEFAULT op "
                + "instance (the declaring module's scope — never the caller's and "
                + "never a copy); got " + invoked);
        if (outcome instanceof Outcome.Success<Value> success
                && success.value() instanceof Value.Class instance) {
            check(fieldValue(instance, layout, "a").equals(new Value.Int(42)),
                "the owner-scope default value filled the published instance");
        }
    }

    // =========================================================================
    // 6. Fail-closed defects and null-argument guards
    // =========================================================================

    static void testFailClosedDefects() {
        System.out.println("-- fail-closed defects: wrong owners/policies, factory "
            + "resolution failures, wiring mismatches --");

        ClassLayout layout = layout(field("a", INT, true));
        SemanticOp defA = defaultOp("a", nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE);
        SemanticOp factory = factoryOp(List.of(defA), nextOpId(CALLER));
        ValueId factoryResult = (ValueId) factory.result();
        List<BoundaryEntry> entries = List.of(new BoundaryEntry("a",
            BoundaryKind.CLASS_DEFAULT_FIELD,
            boundaryChild(CALLER, null, BoundaryKind.CLASS_DEFAULT_FIELD, INT, factoryResult,
                FailurePolicyId.TYPE_DESCRIPTOR)));
        SharedNewFixture fixture = sharedNewFixture(layout, List.of(), FACTORY_ENTRY, entries);
        Map<OpId, SemanticOp> ownerOps = Map.of(factory.opId(), factory, defA.opId(), defA);
        ClassFactoryRegistry registry = new ClassFactoryRegistry(
            Map.of(FACTORY_ENTRY, factory.opId()));
        RecordingBodyRunner body = new RecordingBodyRunner(new ArrayList<>(),
            Map.of(defA.opId(), new Value.Int(10)));
        ScriptedDelegate pass = new ScriptedDelegate(new ArrayList<>(), -1);

        // A LOCAL payload on the shared surface.
        ValueId vA = nextValue();
        SemanticOp localOp = opWithId(CALLER, nextOpId(CALLER), SemanticOpKind.CLASS_NEW,
            new KindPayload.ClassNewPayload(CLS, layout,
                List.of(new KindPayload.ProvidedField("a", vA)), DefaultOwner.LOCAL,
                List.of(), null, List.of()),
            nextValue(), new RuntimeDescriptor.Class(CLS),
            FailurePolicyId.CLASS_CONSTRUCTION, null);
        expectDefect(() -> ClassOpsExecutor.executeClassNewSharedFactory(localOp,
                Map.of(vA, new Value.Int(1)), registry, ownerOps, Map.of(),
                Map.of(CLS, layout), pass, body),
            "a LOCAL payload reaching the shared surface");

        // A null classFactoryRef.
        SemanticOp nullRef = opWithId(CALLER, nextOpId(CALLER), SemanticOpKind.CLASS_NEW,
            new KindPayload.ClassNewPayload(CLS, layout, List.of(), DefaultOwner.SHARED_FACTORY,
                List.of(), null, entries.stream().map(e -> new KindPayload.FieldBoundary(
                    e.field(), e.kind(), e.child().opId())).toList()),
            nextValue(), new RuntimeDescriptor.Class(CLS),
            FailurePolicyId.CLASS_CONSTRUCTION, null);
        expectDefect(() -> ClassOpsExecutor.executeClassNewSharedFactory(nullRef,
                Map.of(), registry, ownerOps, Map.of(), Map.of(CLS, layout), pass, body),
            "a SHARED_FACTORY payload with a null classFactoryRef");

        // Non-empty classDefaultOpIds on the shared surface.
        SemanticOp nonEmptyDefaults = opWithId(CALLER, nextOpId(CALLER),
            SemanticOpKind.CLASS_NEW,
            new KindPayload.ClassNewPayload(CLS, layout, List.of(), DefaultOwner.SHARED_FACTORY,
                List.of(defA.opId()), FACTORY_ENTRY, List.of()),
            nextValue(), new RuntimeDescriptor.Class(CLS),
            FailurePolicyId.CLASS_CONSTRUCTION, null);
        expectDefect(() -> ClassOpsExecutor.executeClassNewSharedFactory(nonEmptyDefaults,
                Map.of(), registry, ownerOps, fixture.boundaryOps(), Map.of(CLS, layout),
                pass, body),
            "a SHARED_FACTORY payload with non-empty classDefaultOpIds");

        // An unresolvable registry binding.
        expectDefect(() -> ClassOpsExecutor.executeClassNewSharedFactory(fixture.op(),
                Map.of(), new ClassFactoryRegistry(Map.of()), ownerOps,
                fixture.boundaryOps(), Map.of(CLS, layout), pass, body),
            "an unresolvable factory registry binding");

        // A factory op outside the owner lookup / of the wrong kind.
        expectDefect(() -> ClassOpsExecutor.executeClassNewSharedFactory(fixture.op(),
                Map.of(), new ClassFactoryRegistry(Map.of(FACTORY_ENTRY, nextOpId(OWNER))),
                ownerOps, fixture.boundaryOps(), Map.of(CLS, layout), pass, body),
            "a factory op id absent from the owner lookup");
        SemanticOp notFactory = opIn(OWNER, SemanticOpKind.CONST,
            new KindPayload.ConstPayload(new ScalarValue.Int(1)),
            nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE, null);
        expectDefect(() -> ClassOpsExecutor.executeClassNewSharedFactory(fixture.op(),
                Map.of(), new ClassFactoryRegistry(Map.of(FACTORY_ENTRY, notFactory.opId())),
                Map.of(notFactory.opId(), notFactory), fixture.boundaryOps(),
                Map.of(CLS, layout), pass, body),
            "a non-CLASS_FACTORY op at the registry binding");

        // A factory of the wrong class.
        SemanticOp foreignFactory = opIn(OWNER, SemanticOpKind.CLASS_FACTORY,
            new KindPayload.ClassFactoryPayload(new ClassId("owner.mod", "Other"),
                List.of(defA.opId()), nextOpId(CALLER)),
            nextValue(), new RuntimeDescriptor.Class(CLS),
            FailurePolicyId.CLASS_CONSTRUCTION, null);
        expectDefect(() -> ClassOpsExecutor.executeClassNewSharedFactory(fixture.op(),
                Map.of(), new ClassFactoryRegistry(Map.of(FACTORY_ENTRY, foreignFactory.opId())),
                Map.of(foreignFactory.opId(), foreignFactory), fixture.boundaryOps(),
                Map.of(CLS, layout), pass, body),
            "a factory op of a different class");

        // A factory without a ValueId result.
        SemanticOp noResult = opIn(OWNER, SemanticOpKind.CLASS_FACTORY,
            new KindPayload.ClassFactoryPayload(CLS, List.of(defA.opId()), nextOpId(CALLER)),
            null, null, FailurePolicyId.CLASS_CONSTRUCTION, null);
        expectDefect(() -> ClassOpsExecutor.executeClassNewSharedFactory(fixture.op(),
                Map.of(), new ClassFactoryRegistry(Map.of(FACTORY_ENTRY, noResult.opId())),
                Map.of(noResult.opId(), noResult), fixture.boundaryOps(), Map.of(CLS, layout),
                pass, body),
            "a factory op without a ValueId result");

        // A CLASS_DEFAULT_FIELD wiring mismatch: the boundary input must
        // name the owner factory result ValueId (the K-D4 cross-unit
        // reference).
        ValueId wrongResult = nextValue();
        List<BoundaryEntry> wrongWiring = List.of(new BoundaryEntry("a",
            BoundaryKind.CLASS_DEFAULT_FIELD,
            boundaryChild(CALLER, null, BoundaryKind.CLASS_DEFAULT_FIELD, INT, wrongResult,
                FailurePolicyId.TYPE_DESCRIPTOR)));
        SharedNewFixture wrongFixture = sharedNewFixture(layout, List.of(), FACTORY_ENTRY,
            wrongWiring);
        expectDefect(() -> ClassOpsExecutor.executeClassNewSharedFactory(wrongFixture.op(),
                Map.of(), registry, ownerOps, wrongFixture.boundaryOps(), Map.of(CLS, layout),
                pass, body),
            "a CLASS_DEFAULT_FIELD boundary whose input is not the factory result");

        // A transferred instance missing the named defaulted field: the
        // factory payload names a different default than the boundary
        // list expects.
        ClassLayout twoFields = layout(field("a", INT, true), field("b", INT, true));
        SemanticOp defB = defaultOp("b", nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE);
        SemanticOp factoryB = factoryOp(List.of(defB), nextOpId(CALLER));
        ValueId factoryBResult = (ValueId) factoryB.result();
        List<BoundaryEntry> expectA = List.of(new BoundaryEntry("a",
            BoundaryKind.CLASS_DEFAULT_FIELD,
            boundaryChild(CALLER, null, BoundaryKind.CLASS_DEFAULT_FIELD, INT, factoryBResult,
                FailurePolicyId.TYPE_DESCRIPTOR)));
        SharedNewFixture missingField = sharedNewFixture(twoFields, List.of(), FACTORY_ENTRY,
            expectA);
        expectDefect(() -> ClassOpsExecutor.executeClassNewSharedFactory(missingField.op(),
                Map.of(), new ClassFactoryRegistry(Map.of(FACTORY_ENTRY, factoryB.opId())),
                Map.of(factoryB.opId(), factoryB, defB.opId(), defB),
                missingField.boundaryOps(), Map.of(CLS, twoFields), pass,
                new RecordingBodyRunner(new ArrayList<>(),
                    Map.of(defB.opId(), new Value.Int(99)))),
            "a transferred instance missing the field the boundary list names");
        // Null arguments.
        expectNpe(() -> ClassOpsExecutor.executeClassNewSharedFactory(null, Map.of(),
                registry, ownerOps, fixture.boundaryOps(), Map.of(CLS, layout), pass, body),
            "executeClassNewSharedFactory with a null op");
        expectNpe(() -> ClassOpsExecutor.executeClassNewSharedFactory(fixture.op(), null,
                registry, ownerOps, fixture.boundaryOps(), Map.of(CLS, layout), pass, body),
            "executeClassNewSharedFactory with a null priorValues");
        expectNpe(() -> ClassOpsExecutor.executeClassNewSharedFactory(fixture.op(), Map.of(),
                null, ownerOps, fixture.boundaryOps(), Map.of(CLS, layout), pass, body),
            "executeClassNewSharedFactory with a null registry");
        expectNpe(() -> ClassOpsExecutor.executeClassNewSharedFactory(fixture.op(), Map.of(),
                registry, null, fixture.boundaryOps(), Map.of(CLS, layout), pass, body),
            "executeClassNewSharedFactory with a null ownerOps");
        expectNpe(() -> ClassOpsExecutor.executeClassNewSharedFactory(fixture.op(), Map.of(),
                registry, ownerOps, null, Map.of(CLS, layout), pass, body),
            "executeClassNewSharedFactory with a null boundaryOps");
        expectNpe(() -> ClassOpsExecutor.executeClassNewSharedFactory(fixture.op(), Map.of(),
                registry, ownerOps, fixture.boundaryOps(), null, pass, body),
            "executeClassNewSharedFactory with a null layouts");
        expectNpe(() -> ClassOpsExecutor.executeClassNewSharedFactory(fixture.op(), Map.of(),
                registry, ownerOps, fixture.boundaryOps(), Map.of(CLS, layout), null, body),
            "executeClassNewSharedFactory with a null checkRunner");
        expectNpe(() -> ClassOpsExecutor.executeClassNewSharedFactory(fixture.op(), Map.of(),
                registry, ownerOps, fixture.boundaryOps(), Map.of(CLS, layout), pass, null),
            "executeClassNewSharedFactory with a null ownerBodyRunner");
        expectNpe(() -> ClassOpsExecutor.executeClassFactory(null, fixture.op(),
                Map.of(defA.opId(), defA), Map.of(CLS, layout), Set.of(), body),
            "executeClassFactory with a null op");
        expectNpe(() -> ClassOpsExecutor.executeClassFactory(factory, null,
                Map.of(defA.opId(), defA), Map.of(CLS, layout), Set.of(), body),
            "executeClassFactory with a null triggeringCaller");
        expectNpe(() -> ClassOpsExecutor.executeClassFactory(factory, fixture.op(),
                null, Map.of(CLS, layout), Set.of(), body),
            "executeClassFactory with a null defaultOps");
        expectNpe(() -> ClassOpsExecutor.executeClassFactory(factory, fixture.op(),
                Map.of(defA.opId(), defA), null, Set.of(), body),
            "executeClassFactory with a null layouts");
        expectNpe(() -> ClassOpsExecutor.executeClassFactory(factory, fixture.op(),
                Map.of(defA.opId(), defA), Map.of(CLS, layout), null, body),
            "executeClassFactory with a null providedFields");
        expectNpe(() -> ClassOpsExecutor.executeClassFactory(factory, fixture.op(),
                Map.of(defA.opId(), defA), Map.of(CLS, layout), Set.of(), null),
            "executeClassFactory with a null bodyRunner");
    }

    // =========================================================================
    // 7. Determinism
    // =========================================================================

    static void testDeterminism() {
        System.out.println("-- determinism: repeated drives with equal inputs produce "
            + "equal results (the executor is stateless) --");

        ClassLayout layout = layout(field("a", INT, true), field("b", INT, true));
        SemanticOp defA = defaultOp("a", nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE);
        SemanticOp defB = defaultOp("b", nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE);
        Map<OpId, Value> produced = Map.of(defA.opId(), new Value.Int(10),
            defB.opId(), new Value.Int(99));
        SemanticOp factory = factoryOp(List.of(defA, defB), nextOpId(CALLER));
        ValueId factoryResult = (ValueId) factory.result();
        List<BoundaryEntry> entries = List.of(
            new BoundaryEntry("a", BoundaryKind.CLASS_DEFAULT_FIELD,
                boundaryChild(CALLER, null, BoundaryKind.CLASS_DEFAULT_FIELD, INT,
                    factoryResult, FailurePolicyId.TYPE_DESCRIPTOR)),
            new BoundaryEntry("b", BoundaryKind.CLASS_DEFAULT_FIELD,
                boundaryChild(CALLER, null, BoundaryKind.CLASS_DEFAULT_FIELD, INT,
                    factoryResult, FailurePolicyId.TYPE_DESCRIPTOR)));
        SharedNewFixture fixture = sharedNewFixture(layout, List.of(), FACTORY_ENTRY, entries);
        Map<OpId, SemanticOp> ownerOps = Map.of(factory.opId(), factory,
            defA.opId(), defA, defB.opId(), defB);
        ClassFactoryRegistry registry = new ClassFactoryRegistry(
            Map.of(FACTORY_ENTRY, factory.opId()));

        Outcome<Value> first = ClassOpsExecutor.executeClassNewSharedFactory(fixture.op(),
            Map.of(), registry, ownerOps, fixture.boundaryOps(), Map.of(CLS, layout),
            new ScriptedDelegate(new ArrayList<>(), -1),
            new RecordingBodyRunner(new ArrayList<>(), produced));
        Outcome<Value> second = ClassOpsExecutor.executeClassNewSharedFactory(fixture.op(),
            Map.of(), registry, ownerOps, fixture.boundaryOps(), Map.of(CLS, layout),
            new ScriptedDelegate(new ArrayList<>(), -1),
            new RecordingBodyRunner(new ArrayList<>(), produced));
        check(first.equals(second)
                && first instanceof Outcome.Success<Value> s1
                && s1.value() instanceof Value.Class i1
                && second instanceof Outcome.Success<Value> s2
                && s2.value() instanceof Value.Class i2
                && i1.fields().get(0) instanceof FieldState.Present p1
                && p1.value().equals(new Value.Int(10))
                && i2.fields().get(0) instanceof FieldState.Present p2
                && p2.value().equals(new Value.Int(10)),
            "two equal drives publish equal instances (defaults filled per drive, "
                + "deterministic)");
    }

    // =========================================================================
    // Runner
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Shared Factory Executor Tests (ISSUE-0514 K-D4/K-D5) ===\n");

        testFactoryTriggerSkipProvidedAndTransfer();
        testClassNewSharedFactoryFullDrive();
        testExtraKeyScanAfterTransfer();
        testFailingDefaultChildFailsCaller();
        testOwnerScopeDefaultResolution();
        testFailClosedDefects();
        testDeterminism();

        System.out.println("\nSharedFactoryExecutorTest: " + passed + " passed, "
            + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
