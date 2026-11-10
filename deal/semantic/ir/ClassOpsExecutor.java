package deal.semantic.ir;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public final class ClassOpsExecutor {

    private ClassOpsExecutor() {
        // Static surface only; pure and stateless.
    }

    // =========================================================================
    // Producer defects (fail closed, never a DEAL projection)
    // =========================================================================

    /**
     * An executor producer defect: a shape outside the pinned contracts
     * reached the executor — a wrong op kind, a non-pinned failure
     * policy, a non-{@code LOCAL} default owner or a non-null factory
     * ref, an unresolvable or mismatched layout, an unresolvable
     * provided-field prior step, a wrong-kind value, a boundary child of
     * the wrong kind or parentage, a child-count/order mismatch, an
     * input-wiring mismatch or a malformed field boundary. Internal control
     * flow — fail closed, never a DEAL projection and never a crash (the
     * validated-shapes-only discipline).
     */
    public static final class Defect extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public Defect(String message) {
            super(message);
        }
    }

    // =========================================================================
    // The closed value view, extended with the class variant
    // =========================================================================

    public sealed interface Value
        permits Value.Undefined, Value.Null, Value.Bool, Value.Number, Value.String,
                Value.Table, Value.Array, Value.Class, Value.Function,
                Value.Missing {

        /** The canonical actual kind this value renders as. */
        ActualKind actualKind();

        /** A string view carrying the validated classification of {@code carrier}. */
        static Value string(java.lang.String carrier) {
            return new String(UnicodeScalars.validate(carrier));
        }

        /** Language null (the explicit {@code ActualKind#NULL}). */
        enum Null implements Value {
            INSTANCE;

            @Override
            public ActualKind actualKind() {
                return ActualKind.NULL;
            }
        }

        enum Undefined implements Value {
            INSTANCE;
            public ActualKind actualKind() { return ActualKind.UNDEFINED; }
        }

        /** A boolean value. */
        record Bool(boolean value) implements Value {

            @Override
            public ActualKind actualKind() {
                return ActualKind.BOOLEAN;
            }
        }


/** An IEEE-754 number value (NaN and infinity included). */
        record Number(double value) implements Value {

            @Override
            public ActualKind actualKind() {
                return ActualKind.NUMBER;
            }
        }

        /**
         * A string value carrying the closed scalar classification
         * ({@link UnicodeScalars.ScalarString}): {@code Valid} renders
         * {@link ActualKind#STRING}, {@code Invalid} renders
         * {@link ActualKind#INVALID_UNICODE}. The classification is
         * never normalized away.
         */
        record String(UnicodeScalars.ScalarString scalar) implements Value {

            public String {
                Objects.requireNonNull(scalar, "scalar must not be null");
            }

            @Override
            public ActualKind actualKind() {
                return scalar.actualKind();
            }
        }

        /** A table value carrying its first-insertion-order model. */
        record Table(SemanticTable<Value> table) implements Value {

            public Table {
                Objects.requireNonNull(table, "table must not be null");
            }

            @Override
            public ActualKind actualKind() {
                return ActualKind.TABLE;
            }
        }


        /** An array value carrying its ordered element list. */
        record Array(SemanticArray<Value> array, RuntimeDescriptor elementDescriptor) implements Value {

            public Array(SemanticArray<Value> array) { this(array, null); }

            public Array {
                Objects.requireNonNull(array, "array must not be null");
            }

            @Override
            public ActualKind actualKind() {
                return ActualKind.ARRAY;
            }
        }

        /**
         * A class instance: the canonical {@code classId} tag plus its
         * fields in declaration order as {@link FieldState}
         * {@code Present(Value) | Missing}. Present null (the explicit
         * {@link Null} variant) stays {@code Present} and is
         * distinguishable from {@code Missing}; a {@code Present} field
         * never carries the internal {@code Missing} view (fail closed).
         */
        record Class(ClassId classId, List<FieldState> fields) implements Value {

            public Class {
                Objects.requireNonNull(classId, "classId must not be null");
                Objects.requireNonNull(fields, "fields must not be null");
                if (!(fields instanceof LiveFields)) {
                    fields = List.copyOf(fields);
                    for (FieldState field : fields) {
                        if (field instanceof FieldState.Present present
                                && present.value() instanceof Missing) {
                            throw new Defect("a present class field never carries the internal "
                                + "Missing view — a field value is language null (Null), a "
                                + "value, or the field is absent (the Missing field state): "
                                + "a shape outside the pinned contracts, never executed");
                        }
                    }
                }
            }

            @Override
            public ActualKind actualKind() {
                return ActualKind.CLASS;
            }
        }

        /** A function value carrying its closed signature (descriptor-kind rule). */
        record Function(RuntimeDescriptor.Func signature) implements Value {

            public Function {
                Objects.requireNonNull(signature, "signature must not be null");
            }

            @Override
            public ActualKind actualKind() {
                return ActualKind.FUNCTION;
            }
        }

        /** The internal missing (an absent class field; never Java null). */
        enum Missing implements Value {
            INSTANCE;

            @Override
            public ActualKind actualKind() {
                return ActualKind.MISSING;
            }
        }
    }

    /**
     * The read-only live field-state list of one converted class view:
     * {@code fieldAt} converts the field state at {@code index} on every
     * read from the backing class value. A converted class view is never
     * copied or materialized at conversion time, so a field reference back
     * to the same backing class value reads back as the same view object (a
     * path-local re-entry stays one) and a field commit to the backing
     * value is observed by every alias. {@link #get} keeps the closed
     * field-state invariant: {@link FieldState.Present}'s own construction
     * rejects a present internal {@code Missing}.
     */
    private static final class LiveFields extends java.util.AbstractList<FieldState> {

        private final int size;
        private final IntFunction<FieldState> fieldAt;

        LiveFields(int size, IntFunction<FieldState> fieldAt) {
            this.size = size;
            this.fieldAt = fieldAt;
        }

        @Override
        public FieldState get(int index) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("live class field index " + index
                    + " outside [0, " + size + ")");
            }
            return Objects.requireNonNull(fieldAt.apply(index),
                "a live class field state must not be null");
        }

        @Override
        public int size() {
            return size;
        }
    }

    /**
     * A read-only live class view over the conversion boundary's backing
     * class value: the canonical {@code classId} tag plus {@code fieldCount}
     * lazily converted field states in declaration order. The conversion
     * boundary creates exactly one view per backing class value
     * (identity-keyed), so a field reference back to the same class value
     * yields the same view object — a path-local class re-entry stays one,
     * and a field commit to the backing value is observed by every alias.
     */
    public static Value.Class liveClassView(ClassId classId, int fieldCount,
                                            IntFunction<FieldState> fieldAt) {
        Objects.requireNonNull(classId, "classId must not be null");
        Objects.requireNonNull(fieldAt, "fieldAt must not be null");
        if (fieldCount < 0) {
            throw new Defect("a live class view's field count must be non-negative, got "
                + fieldCount);
        }
        return new Value.Class(classId, new LiveFields(fieldCount, fieldAt));
    }

    /**
     * One class-field state of the closed value view:
     * {@code Present(Value) | Missing}. Present null (the explicit
     * {@link Value.Null} variant) is {@code Present} — the three presence
     * states (missing, present null, present value) are distinct and are
     * never conflated.
     */
    public sealed interface FieldState permits FieldState.Present, FieldState.Missing {

        /** The field is present; {@code value} is its value (never the internal Missing). */
        record Present(Value value) implements FieldState {

            public Present {
                Objects.requireNonNull(value, "value must not be null");
                if (value instanceof Value.Missing) {
                    throw new Defect("a present class field never carries the internal "
                        + "Missing view — a present field holds language null (Null) or a "
                        + "value; absence is the Missing field state, never a field value");
                }
            }
        }

        /** The field is absent (the schema's internal missing). */
        enum Missing implements FieldState {
            INSTANCE;
        }
    }

    // =========================================================================
    // Op-level failure and outcome
    // =========================================================================

    /**
     * One op-level failure: the structured registry-arm projection
     * (rendered through {@link FailureContractRegistry#render}) paired with
     * the executed op's origin — the operation origin of the closed table's
     * rule (for {@code CLASS_NEW} the extra-key scan reports at the op
     * origin; for a field-boundary child failure the parent op's origin —
     * the child's own origin stays observable through the unit's ops).
     */
    public record OpFailure(BoundaryFailure failure, SourceOrigin origin) {

        public OpFailure {
            Objects.requireNonNull(failure, "failure must not be null");
            Objects.requireNonNull(origin, "origin must not be null");
        }
    }

    public sealed interface Outcome<V> permits Outcome.Success, Outcome.Failure {

        /** The op succeeded; {@code value} is the published result. */
        record Success<V>(V value) implements Outcome<V> {

            public Success {
                Objects.requireNonNull(value, "value must not be null");
            }
        }

        /** The op failed; {@code failure} is the registry-row projection. */
        record Failure<V>(OpFailure failure) implements Outcome<V> {

            public Failure {
                Objects.requireNonNull(failure, "failure must not be null");
            }
        }
    }

    @FunctionalInterface
    public interface BoundaryCheckRunner {

        /**
         * Runs one field-boundary check of the construction.
         *
         */
        BoundaryResult run(KindPayload.BoundaryPayload boundary, Value input);
    }

    /**
     * The delegate's terminal: {@code Pass(value) | Fail(failure)}. The
     * first {@code Fail} of the field-boundary run fails the op with
     * that child's failure — no instance is published (the tag never
     * runs), and the later children never run.
     */
    public sealed interface BoundaryResult permits BoundaryResult.Pass, BoundaryResult.Fail {

        /**
         * The boundary passed; {@code value} is the delegate-published
         * checked value (a passing boundary never copies or converts).
         */
        record Pass(Value value) implements BoundaryResult {

            public Pass {
                Objects.requireNonNull(value, "value must not be null");
            }
        }

        /** The boundary failed; {@code failure} is its registry-row projection. */
        record Fail(BoundaryFailure failure) implements BoundaryResult {

            public Fail {
                Objects.requireNonNull(failure, "failure must not be null");
            }
        }
    }

    // =========================================================================
    // CLASS_NEW(LOCAL)
    // =========================================================================

    public static Outcome<Value> executeClassNewLocal(
            SemanticOp op,
            Map<ValueId, Value> priorValues,
            Map<OpId, SemanticOp> boundaryOps,
            Map<ClassId, ClassLayout> layouts,
            BoundaryCheckRunner checkRunner) {
        requireOp(op, SemanticOpKind.CLASS_NEW, FailurePolicyId.CLASS_CONSTRUCTION);
        Objects.requireNonNull(priorValues, "priorValues must not be null");
        Objects.requireNonNull(boundaryOps, "boundaryOps must not be null");
        Objects.requireNonNull(layouts, "layouts must not be null");
        Objects.requireNonNull(checkRunner, "checkRunner must not be null");
        KindPayload.ClassNewPayload payload = (KindPayload.ClassNewPayload) op.payload();
        // This child's surface: LOCAL execution only. SHARED_FACTORY
        // execution is executeClassNewSharedFactory's; the host
        // declaration class construction is
        // executeClassNewHost's; the builtin Error construction is
        // executeClassNewBuiltinError's; RETAINED_ABI is E10's — all fail
        // closed here, never silently executed as LOCAL.
        if (payload.constructionProvider() != ConstructionProvider.LOCAL) {
            throw new Defect("CLASS_NEW " + op.opId() + " carries constructionProvider "
                + payload.constructionProvider() + ": this executor surface is LOCAL "
                + "execution — SHARED_FACTORY transfer is "
                + "executeClassNewSharedFactory's, RETAINED_ABI transfer is E10's, "
                + "the host construction is executeClassNewHost's, the "
                + "builtin Error construction is executeClassNewBuiltinError's; "
                + "a non-LOCAL owner reaching executeClassNewLocal is a producer "
                + "defect, never executed");
        }
        if (payload.classFactoryRef() != null) {
            throw new Defect("CLASS_NEW " + op.opId() + " carries a non-null classFactoryRef "
                + payload.classFactoryRef() + ": the pinned LOCAL shape carries "
                + "classFactoryRef null (the factory ref exists only for "
                + "SHARED_FACTORY/RETAINED_ABI owners) — a producer defect, never "
                + "executed");
        }

        ClassLayout layout = layouts.get(payload.classId());
        if (layout == null) {
            throw new Defect("CLASS_NEW " + op.opId() + " classId " + payload.classId()
                + " does not resolve in the layout-resolution context: every "
                + "payload-referenced class resolves through the unit's classLayouts "
                + "(plus the project interface index) — an unresolvable layout is a "
                + "producer defect, never executed");
        }
        if (!layout.equals(payload.layout())) {
            throw new Defect("CLASS_NEW " + op.opId() + " carries a layout that differs "
                + "from the resolution context's layout of " + payload.classId()
                + ": the payload's layout must be exactly the resolved layout — a "
                + "mismatch is a producer defect, never executed");
        }

        LinkedHashMap<String, Value> providedValues = new LinkedHashMap<>();
        LinkedHashMap<String, ValueId> providedValueIds = new LinkedHashMap<>();
        resolveProvidedFields(op, payload, priorValues, null, providedValues,
            providedValueIds);

        Outcome.Failure<Value> extraKeyFailure =
            undeclaredProvidedFieldFailure(op, payload, layout);
        if (extraKeyFailure != null) {
            return extraKeyFailure;
        }

        List<KindPayload.FieldBoundary> boundaries = payload.fieldBoundaries();
        requireBoundaryShape(op, layout, providedValues, boundaries);

        LinkedHashMap<String, Value> checkedValues = new LinkedHashMap<>();
        OpFailure fieldFailure = applyFieldBoundaries(op, payload, layout, boundaries,
            providedValues, providedValueIds, boundaryOps, checkRunner, checkedValues);
        LinkedHashMap<String, Value> instanceFields = new LinkedHashMap<>();
        if (fieldFailure != null) {
            return new Outcome.Failure<Value>(fieldFailure);
        }
        instanceFields.putAll(checkedValues);

        List<FieldState> states = new ArrayList<>(layout.fields().size());
        for (ClassLayout.FieldLayout fieldLayout : layout.fields()) {
            Value value = instanceFields.get(fieldLayout.name());
            states.add(value == null ? FieldState.Missing.INSTANCE
                : new FieldState.Present(value));
        }
        return new Outcome.Success<Value>(
            new Value.Class(payload.classId(), List.copyOf(states)));
    }

    // =========================================================================
    // CLASS_NEW (BUILTIN_ERROR, the builtin Error construction)
    // =========================================================================

    public static Outcome<Value> executeClassNewBuiltinError(
            SemanticOp op,
            Map<ValueId, Value> priorValues,
            Map<OpId, SemanticOp> boundaryOps,
            Map<ClassId, ClassLayout> layouts,
            BoundaryCheckRunner checkRunner) {
        requireOp(op, SemanticOpKind.CLASS_NEW, FailurePolicyId.CLASS_CONSTRUCTION);
        Objects.requireNonNull(priorValues, "priorValues must not be null");
        Objects.requireNonNull(boundaryOps, "boundaryOps must not be null");
        Objects.requireNonNull(layouts, "layouts must not be null");
        Objects.requireNonNull(checkRunner, "checkRunner must not be null");
        KindPayload.ClassNewPayload payload = (KindPayload.ClassNewPayload) op.payload();

        // This child's surface: BUILTIN_ERROR execution only. The host
        // declaration class construction (HOST_DECLARATION) is
        // executeClassNewHost's; a foreign owner
        // reaching this surface is a producer defect, never silently
        // executed.
        if (payload.constructionProvider() != ConstructionProvider.BUILTIN_ERROR) {
            throw new Defect("CLASS_NEW " + op.opId() + " carries constructionProvider "
                + payload.constructionProvider() + ": this executor surface is the builtin"
                + " Error construction (BUILTIN_ERROR) — LOCAL is"
                + " executeClassNewLocal's, SHARED_FACTORY transfer is"
                + " executeClassNewSharedFactory's, the host declaration class"
                + " construction is executeClassNewHost's, RETAINED_ABI"
                + " transfer is E10's; a foreign owner reaching"
                + " executeClassNewBuiltinError"
                + " is a producer defect, never executed");
        }
        if (!ClassId.ERROR.equals(payload.classId())) {
            throw new Defect("CLASS_NEW " + op.opId() + " carries constructionProvider"
                + " BUILTIN_ERROR for class " + payload.classId() + ": the"
                + " builtin-defaults owner is admissible only for the builtin Error"
                + " class " + ClassId.ERROR.text() + " — a non-builtin class under"
                + " BUILTIN_ERROR is a producer defect, never executed");
        }
        if (payload.classFactoryRef() != null) {
            throw new Defect("CLASS_NEW " + op.opId() + " carries a non-null"
                + " classFactoryRef " + payload.classFactoryRef() + " under"
                + " BUILTIN_ERROR: the builtin Error construction carries a null"
                + " factory ref (the builtin defaults are compiler constants, never a"
                + " factory transfer) — a producer defect, never executed");
        }


        ClassLayout layout = layouts.get(payload.classId());
        if (layout == null || !layout.equals(ClassLayout.BUILTIN_ERROR)) {
            throw new Defect("CLASS_NEW " + op.opId() + " classId "
                + payload.classId() + " does not resolve to the compiler-owned"
                + " builtin Error layout in the layout-resolution context: the"
                + " builtin class is a compiler constant layout — an unresolvable or"
                + " foreign entry is a producer defect, never executed");
        }
        if (!layout.equals(payload.layout())) {
            throw new Defect("CLASS_NEW " + op.opId() + " carries a layout that"
                + " differs from the compiler-owned builtin Error layout: the"
                + " payload's layout must be exactly the resolved layout — a mismatch"
                + " is a producer defect, never executed");
        }

        LinkedHashMap<String, Value> providedValues = new LinkedHashMap<>();
        LinkedHashMap<String, ValueId> providedValueIds = new LinkedHashMap<>();
        resolveProvidedFields(op, payload, priorValues, layout, providedValues,
            providedValueIds);

        LinkedHashMap<String, Value> checkedValues = new LinkedHashMap<>();
        OpFailure fieldFailure = applyLiteralFieldBoundaries(op, payload, layout,
            providedValues, providedValueIds, payload.fieldBoundaries(), boundaryOps,
            checkRunner, checkedValues, "builtin Error shape");
        if (fieldFailure != null) {
            return new Outcome.Failure<Value>(fieldFailure);
        }

        // Both Error fields must have boundary-published provided values.
        List<FieldState> states = new ArrayList<>(layout.fields().size());
        for (ClassLayout.FieldLayout fieldLayout : layout.fields()) {
            Value value = checkedValues.get(fieldLayout.name());
            if (value == null) {
                throw new Defect("builtin Error construction omits '" + fieldLayout.name() + "'");
            }
            states.add(new FieldState.Present(value));
        }
        return new Outcome.Success<Value>(
            new Value.Class(payload.classId(), List.copyOf(states)));
    }

    public static Outcome<Value> executeClassNewHost(
            SemanticOp op,
            Map<ValueId, Value> priorValues,
            Map<OpId, SemanticOp> boundaryOps,
            Map<ClassId, ClassLayout> layouts,
            BoundaryCheckRunner checkRunner) {
        requireOp(op, SemanticOpKind.CLASS_NEW, FailurePolicyId.CLASS_CONSTRUCTION);
        Objects.requireNonNull(priorValues, "priorValues must not be null");
        Objects.requireNonNull(boundaryOps, "boundaryOps must not be null");
        Objects.requireNonNull(layouts, "layouts must not be null");
        Objects.requireNonNull(checkRunner, "checkRunner must not be null");
        KindPayload.ClassNewPayload payload = (KindPayload.ClassNewPayload) op.payload();

        if (payload.constructionProvider() != ConstructionProvider.HOST_DECLARATION) {
            throw new Defect("CLASS_NEW " + op.opId() + " carries constructionProvider "
                + payload.constructionProvider() + ": this executor surface is the host"
                + " declaration class construction (HOST_DECLARATION) — LOCAL is"
                + " executeClassNewLocal's, SHARED_FACTORY transfer is"
                + " executeClassNewSharedFactory's, the builtin Error construction is"
                + " executeClassNewBuiltinError's, RETAINED_ABI transfer is E10's;"
                + " a foreign owner reaching executeClassNewHost is a producer"
                + " defect,"
                + " never executed");
        }
        if (payload.classFactoryRef() != null) {
            throw new Defect("CLASS_NEW " + op.opId() + " carries a non-null"
                + " classFactoryRef " + payload.classFactoryRef() + " under"
                + " HOST_DECLARATION: the host construction carries a null factory ref"
                + " (the loaded defaults are data, never a factory transfer) — a"
                + " producer defect, never executed");
        }


        ClassLayout layout = layouts.get(payload.classId());
        if (layout == null) {
            throw new Defect("CLASS_NEW " + op.opId() + " classId " + payload.classId()
                + " does not resolve in the layout-resolution context: a declaration"
                + " class resolves through the project's registered declaration"
                + " layouts — an unresolvable layout is a producer defect, never"
                + " executed");
        }
        if (!layout.equals(payload.layout())) {
            throw new Defect("CLASS_NEW " + op.opId() + " carries a layout that differs"
                + " from the resolution context's layout of " + payload.classId()
                + ": the payload's layout must be exactly the registered declaration"
                + " layout — a mismatch is a producer defect, never executed");
        }

        // Phase 1: provided values resolve in literal order; an undeclared
        // provided name is unreachable from the checker (E4002) and stays
        // fail closed.
        LinkedHashMap<String, Value> providedValues = new LinkedHashMap<>();
        LinkedHashMap<String, ValueId> providedValueIds = new LinkedHashMap<>();
        resolveProvidedFields(op, payload, priorValues, layout, providedValues,
            providedValueIds);


        // Phase 4: exactly one CLASS_LITERAL_FIELD boundary per provided
        // field in declaration order; each runs through the delegate.
        LinkedHashMap<String, Value> checkedValues = new LinkedHashMap<>();
        OpFailure fieldFailure = applyLiteralFieldBoundaries(op, payload, layout,
            providedValues, providedValueIds, payload.fieldBoundaries(), boundaryOps,
            checkRunner, checkedValues, "host construction");
        if (fieldFailure != null) {
            return new Outcome.Failure<Value>(fieldFailure);
        }

        // Only provided values become present; omitted optionals stay absent.
        List<FieldState> states = new ArrayList<>(layout.fields().size());
        for (ClassLayout.FieldLayout fieldLayout : layout.fields()) {
            Value provided = checkedValues.get(fieldLayout.name());
            if (provided != null) {
                states.add(new FieldState.Present(provided));
                continue;
            }
            states.add(FieldState.Missing.INSTANCE);
        }
        return new Outcome.Success<Value>(
            new Value.Class(payload.classId(), List.copyOf(states)));
    }

    public static Outcome<Value> executeClassFactory(
            SemanticOp op,
            SemanticOp triggeringCaller,
            Map<ClassId, ClassLayout> layouts) {
        requireOp(op, SemanticOpKind.CLASS_FACTORY, FailurePolicyId.CLASS_CONSTRUCTION);
        Objects.requireNonNull(triggeringCaller, "triggeringCaller must not be null");
        Objects.requireNonNull(layouts, "layouts must not be null");

        if (triggeringCaller.kind() != SemanticOpKind.CLASS_NEW) {
            throw new Defect("CLASS_FACTORY " + op.opId() + " triggered by an op of kind "
                + triggeringCaller.kind() + ": only CLASS_NEW resolves a construction provider"
                + " (producer defect)");
        }
        if (triggeringCaller.opId().equals(op.opId())) {
            throw new Defect("CLASS_FACTORY " + op.opId() + " triggered by itself: the "
                + "factory is the declaring module's default-application op and its "
                + "executed parentOpId is the triggering caller op (cross-unit) — a "
                + "factory as its own caller is a producer defect, never executed");
        }
        KindPayload.ClassFactoryPayload payload =
            (KindPayload.ClassFactoryPayload) op.payload();

        ClassLayout layout = layouts.get(payload.classId());
        if (layout == null) {
            throw new Defect("CLASS_FACTORY " + op.opId() + " classId " + payload.classId()
                + " does not resolve in the layout-resolution context: the factory fills "
                + "its class's declared layout (the owner unit's classLayouts) — an "
                + "unresolvable layout is a producer defect, never executed");
        }

        // The provider record carries declaration identity, not executable
        // initialization or values to overlay onto a construction.
        List<FieldState> states = new ArrayList<>(layout.fields().size());
        for (ClassLayout.FieldLayout fieldLayout : layout.fields()) {
            states.add(FieldState.Missing.INSTANCE);
        }
        return new Outcome.Success<Value>(
            new Value.Class(payload.classId(), List.copyOf(states)));
    }

    // =========================================================================
    // CLASS_NEW(SHARED_FACTORY)
    // =========================================================================

    public static Outcome<Value> executeClassNewSharedFactory(
            SemanticOp op,
            Map<ValueId, Value> priorValues,
            ClassFactoryRegistry factories,
            Map<OpId, SemanticOp> ownerOps,
            Map<OpId, SemanticOp> boundaryOps,
            Map<ClassId, ClassLayout> layouts,
            BoundaryCheckRunner checkRunner) {
        requireOp(op, SemanticOpKind.CLASS_NEW, FailurePolicyId.CLASS_CONSTRUCTION);
        Objects.requireNonNull(priorValues, "priorValues must not be null");
        Objects.requireNonNull(factories, "factories must not be null");
        Objects.requireNonNull(ownerOps, "ownerOps must not be null");
        Objects.requireNonNull(boundaryOps, "boundaryOps must not be null");
        Objects.requireNonNull(layouts, "layouts must not be null");
        Objects.requireNonNull(checkRunner, "checkRunner must not be null");
        KindPayload.ClassNewPayload payload = (KindPayload.ClassNewPayload) op.payload();

        // This child's surface: SHARED_FACTORY execution. LOCAL execution
        // is {@link #executeClassNewLocal}'s; the host declaration class
        // construction is {@link #executeClassNewHost}'s; the
        // builtin Error construction is
        // {@link #executeClassNewBuiltinError}'s; RETAINED_ABI is E10's — a
        // non-SHARED_FACTORY owner reaching this surface is a producer
        // defect, never silently executed as a transfer.
        if (payload.constructionProvider() != ConstructionProvider.SHARED_FACTORY) {
            throw new Defect("CLASS_NEW " + op.opId() + " carries constructionProvider "
                + payload.constructionProvider() + ": this child's executor surface is "
                + "SHARED_FACTORY transfer (executeClassNewLocal is the LOCAL surface, "
                + "executeClassNewHost is the host declaration class surface, "
                + "executeClassNewBuiltinError is the builtin Error surface, "
                + "RETAINED_ABI transfer is E10's) — a"
                + " non-SHARED_FACTORY owner reaching "
                + "executeClassNewSharedFactory is a producer defect, never executed");
        }
        if (payload.classFactoryRef() == null) {
            throw new Defect("CLASS_NEW " + op.opId() + " carries a null classFactoryRef: "
                + "the pinned SHARED_FACTORY shape carries classFactoryRef = the "
                + "interface's pre-allocated constructionEntry — a null factory ref is a "
                + "producer defect, never executed");
        }


        ClassLayout layout = layouts.get(payload.classId());
        if (layout == null) {
            throw new Defect("CLASS_NEW " + op.opId() + " classId " + payload.classId()
                + " does not resolve in the layout-resolution context: every "
                + "payload-referenced class resolves through the unit's classLayouts "
                + "(plus the project interface index) — an unresolvable layout is a "
                + "producer defect, never executed");
        }
        if (!layout.equals(payload.layout())) {
            throw new Defect("CLASS_NEW " + op.opId() + " carries a layout that differs "
                + "from the resolution context's layout of " + payload.classId()
                + ": the payload's layout must be exactly the resolved layout — a "
                + "mismatch is a producer defect, never executed");
        }

        LinkedHashMap<String, Value> providedValues = new LinkedHashMap<>();
        LinkedHashMap<String, ValueId> providedValueIds = new LinkedHashMap<>();
        resolveProvidedFields(op, payload, priorValues, null, providedValues,
            providedValueIds);

        OpId factoryOpId = factories.factoryFor(payload.classFactoryRef());
        if (factoryOpId == null) {
            throw new Defect("CLASS_NEW " + op.opId() + " classFactoryRef "
                + payload.classFactoryRef() + " does not resolve in the owner's "
                + "ClassFactoryRegistry: a SHARED_FACTORY op reaching execution without "
                + "a registered factory is a producer defect (an owner on a retained "
                + "route defers at lowering — RETAINED_ABI is E10's), never executed");
        }
        SemanticOp factoryOp = ownerOps.get(factoryOpId);
        if (factoryOp == null) {
            throw new Defect("CLASS_NEW " + op.opId() + " factory op id " + factoryOpId
                + " does not resolve in the owner unit's op lookup: the registry binding "
                + "names the owner unit's CLASS_FACTORY op — an unresolvable factory op "
                + "is a producer defect, never executed");
        }
        requireOp(factoryOp, SemanticOpKind.CLASS_FACTORY,
            FailurePolicyId.CLASS_CONSTRUCTION);
        KindPayload.ClassFactoryPayload factoryPayload =
            (KindPayload.ClassFactoryPayload) factoryOp.payload();
        if (!factoryPayload.classId().equals(payload.classId())) {
            throw new Defect("CLASS_NEW " + op.opId() + " resolves factory op "
                + factoryOp.opId() + " of class " + factoryPayload.classId()
                + ": the provider must name the constructed class ("
                + payload.classId() + ") — a classId mismatch is a producer defect, "
                + "never executed");
        }
        if (!(factoryOp.result() instanceof ValueId)) {
            throw new Defect("CLASS_NEW " + op.opId() + " factory op " + factoryOp.opId()
                + " publishes a non-ValueId provider result " + factoryOp.result()
                + " (producer defect)");
        }

        Outcome<Value> transferred = executeClassFactory(factoryOp, op, layouts);
        if (!(transferred instanceof Outcome.Success<Value> transferSuccess
                && transferSuccess.value() instanceof Value.Class transferredInstance)) {
            throw new Defect("CLASS_NEW " + op.opId() + " factory transfer produced "
                + transferred + ": the pinned factory returns exactly its internal "
                + "structural provider instance — a wrong outcome is a producer "
                + "defect, never executed");
        }
        if (!transferredInstance.classId().equals(payload.classId())
                || transferredInstance.fields().size() != layout.fields().size()) {
            throw new Defect("CLASS_NEW " + op.opId() + " factory transfer produced an "
                + "instance of " + transferredInstance.classId() + " with "
                + transferredInstance.fields().size() + " field states: the pinned "
                + "transfer carries the constructed class's declaration-order states ("
                + payload.classId() + ", " + layout.fields().size() + " fields) — a "
                + "mismatch is a producer defect, never executed");
        }

        Outcome.Failure<Value> extraKeyFailure =
            undeclaredProvidedFieldFailure(op, payload, layout);
        if (extraKeyFailure != null) {
            return extraKeyFailure;
        }

        List<KindPayload.FieldBoundary> boundaries = payload.fieldBoundaries();
        requireBoundaryShape(op, layout, providedValues, boundaries);

        LinkedHashMap<String, Value> checkedValues = new LinkedHashMap<>();
        OpFailure fieldFailure = applyFieldBoundaries(op, payload, layout, boundaries,
            providedValues, providedValueIds, boundaryOps, checkRunner, checkedValues);
        LinkedHashMap<String, Value> instanceFields = new LinkedHashMap<>();
        if (fieldFailure != null) {
            return new Outcome.Failure<Value>(fieldFailure);
        }
        instanceFields.putAll(checkedValues);

        List<FieldState> states = new ArrayList<>(layout.fields().size());
        for (ClassLayout.FieldLayout fieldLayout : layout.fields()) {
            Value value = instanceFields.get(fieldLayout.name());
            states.add(value == null ? FieldState.Missing.INSTANCE
                : new FieldState.Present(value));
        }
        return new Outcome.Success<Value>(
            new Value.Class(payload.classId(), List.copyOf(states)));
    }

    // =========================================================================
    // FIELD_READ
    // =========================================================================

    public static Outcome<Value> executeFieldRead(
            SemanticOp op,
            Map<ValueId, Value> priorValues,
            SemanticOp receiverBoundary,
            SemanticOp fieldBoundary,
            Map<ClassId, ClassLayout> layouts,
            BoundaryCheckRunner checkRunner) {
        requireOp(op, SemanticOpKind.FIELD_READ, FailurePolicyId.NO_DEAL_FAILURE);
        Objects.requireNonNull(priorValues, "priorValues must not be null");
        Objects.requireNonNull(receiverBoundary, "receiverBoundary must not be null");
        Objects.requireNonNull(fieldBoundary, "fieldBoundary must not be null");
        Objects.requireNonNull(layouts, "layouts must not be null");
        Objects.requireNonNull(checkRunner, "checkRunner must not be null");
        KindPayload.FieldReadPayload payload = (KindPayload.FieldReadPayload) op.payload();

        // The receiver resolves exactly once (never re-evaluated).
        Value receiver = resolve(priorValues, payload.classValue());

        RuntimeDescriptor classDescriptor = new RuntimeDescriptor.Class(payload.classId());
        requireFieldBoundaryChild(receiverBoundary, op, BoundaryKind.UNTYPED_CLASS_INPUT,
            classDescriptor, payload.classValue());
        BoundaryResult receiverResult =
            runReceiverBoundary(op, receiverBoundary, receiver, checkRunner);
        if (receiverResult instanceof BoundaryResult.Fail fail) {
            return new Outcome.Failure<Value>(new OpFailure(fail.failure(), op.origin()));
        }

        // After the receiver boundary passes, the receiver is the pinned
        // class instance (the production delegate can only pass an
        // instance of class:<ClassId>; a pass-through fixture passing
        // anything else is a producer defect).
        Value receiverValue = ((BoundaryResult.Pass) receiverResult).value();
        ClassLayout layout = requireInstance(op, payload.classId(), receiverValue, layouts);

        int fieldIndex = fieldIndexOf(op, layout, payload.field());
        FieldState state = ((Value.Class) receiverValue).fields().get(fieldIndex);
        Value readValue;
        if (state instanceof FieldState.Present present) {
            readValue = present.value();
        } else {
            readValue = Value.Undefined.INSTANCE;
        }

        // The field boundary's pinned result descriptor (the checker's
        // optional-read wrap: the field's declared descriptor,
        // nullable-wrapped for an optional non-nullable field).
        ClassLayout.FieldLayout fieldLayout = layout.fields().get(fieldIndex);
        RuntimeDescriptor resultDescriptor = readResultDescriptorOf(fieldLayout);
        if (!(op.result() instanceof ValueId readResult)) {
            throw new Defect("FIELD_READ " + op.opId() + " publishes a non-ValueId result "
                + op.result() + ": the pinned OPTIONAL_FIELD_READ input is the op's own "
                + "result ValueId — a producer defect, never executed");
        }
        requireFieldBoundaryChild(fieldBoundary, op, BoundaryKind.OPTIONAL_FIELD_READ,
            resultDescriptor, readResult);
        BoundaryResult fieldResult = checkRunner.run(
            (KindPayload.BoundaryPayload) fieldBoundary.payload(), readValue);
        return switch (fieldResult) {
            case BoundaryResult.Pass pass -> new Outcome.Success<Value>(pass.value());
            case BoundaryResult.Fail fail -> new Outcome.Failure<Value>(
                new OpFailure(fail.failure(), op.origin()));
        };
    }

    // =========================================================================
    // FIELD_WRITE
    // =========================================================================

    public static Outcome<Value> executeFieldWrite(
            SemanticOp op,
            Map<ValueId, Value> priorValues,
            SemanticOp receiverBoundary,
            SemanticOp fieldBoundary,
            Map<ClassId, ClassLayout> layouts,
            BoundaryCheckRunner checkRunner) {
        requireOp(op, SemanticOpKind.FIELD_WRITE, FailurePolicyId.NO_DEAL_FAILURE);
        Objects.requireNonNull(priorValues, "priorValues must not be null");
        Objects.requireNonNull(receiverBoundary, "receiverBoundary must not be null");
        Objects.requireNonNull(fieldBoundary, "fieldBoundary must not be null");
        Objects.requireNonNull(layouts, "layouts must not be null");
        Objects.requireNonNull(checkRunner, "checkRunner must not be null");
        KindPayload.FieldWritePayload payload = (KindPayload.FieldWritePayload) op.payload();

        // The receiver and the stored value each resolve exactly once.
        Value receiver = resolve(priorValues, payload.classValue());
        Value stored = resolve(priorValues, payload.value());

        RuntimeDescriptor classDescriptor = new RuntimeDescriptor.Class(payload.classId());
        requireFieldBoundaryChild(receiverBoundary, op, BoundaryKind.UNTYPED_CLASS_INPUT,
            classDescriptor, payload.classValue());
        BoundaryResult receiverResult =
            runReceiverBoundary(op, receiverBoundary, receiver, checkRunner);
        if (receiverResult instanceof BoundaryResult.Fail fail) {
            return new Outcome.Failure<Value>(new OpFailure(fail.failure(), op.origin()));
        }
        Value receiverValue = ((BoundaryResult.Pass) receiverResult).value();
        ClassLayout layout = requireInstance(op, payload.classId(), receiverValue, layouts);
        int fieldIndex = fieldIndexOf(op, layout, payload.field());
        ClassLayout.FieldLayout fieldLayout = layout.fields().get(fieldIndex);

        requireFieldBoundaryChild(fieldBoundary, op, BoundaryKind.CLASS_FIELD_ASSIGNMENT,
            fieldLayout.descriptor(), payload.value());
        BoundaryResult fieldResult = checkRunner.run(
            (KindPayload.BoundaryPayload) fieldBoundary.payload(), stored);
        if (fieldResult instanceof BoundaryResult.Fail fail) {
            return new Outcome.Failure<Value>(new OpFailure(fail.failure(), op.origin()));
        }

        // The store commits immediately before SUCCESS: the published
        // instance carries the boundary-published value in the named
        // field; every other field state is unchanged.
        Value committed = ((BoundaryResult.Pass) fieldResult).value();
        return new Outcome.Success<Value>(updatedInstance((Value.Class) receiverValue,
            fieldIndex, new FieldState.Present(committed)));
    }

    // =========================================================================
    // FIELD_DELETE
    // =========================================================================

    public static Outcome<Value> executeFieldDelete(
            SemanticOp op,
            Map<ValueId, Value> priorValues,
            SemanticOp receiverBoundary,
            Map<ClassId, ClassLayout> layouts,
            BoundaryCheckRunner checkRunner) {
        requireOp(op, SemanticOpKind.FIELD_DELETE, FailurePolicyId.NO_DEAL_FAILURE);
        Objects.requireNonNull(priorValues, "priorValues must not be null");
        Objects.requireNonNull(receiverBoundary, "receiverBoundary must not be null");
        Objects.requireNonNull(layouts, "layouts must not be null");
        Objects.requireNonNull(checkRunner, "checkRunner must not be null");
        KindPayload.FieldDeletePayload payload = (KindPayload.FieldDeletePayload) op.payload();

        // The receiver resolves exactly once (never re-evaluated).
        Value receiver = resolve(priorValues, payload.classValue());

        RuntimeDescriptor classDescriptor = new RuntimeDescriptor.Class(payload.classId());
        requireFieldBoundaryChild(receiverBoundary, op, BoundaryKind.UNTYPED_CLASS_INPUT,
            classDescriptor, payload.classValue());
        BoundaryResult receiverResult =
            runReceiverBoundary(op, receiverBoundary, receiver, checkRunner);
        if (receiverResult instanceof BoundaryResult.Fail fail) {
            return new Outcome.Failure<Value>(new OpFailure(fail.failure(), op.origin()));
        }
        Value receiverValue = ((BoundaryResult.Pass) receiverResult).value();
        ClassLayout layout = requireInstance(op, payload.classId(), receiverValue, layouts);
        int fieldIndex = fieldIndexOf(op, layout, payload.field());

        // Set the field missing; deleting an already-missing field is a
        // no-op SUCCESS (the produced instance equals the receiver's
        // state).
        return new Outcome.Success<Value>(updatedInstance((Value.Class) receiverValue,
            fieldIndex, FieldState.Missing.INSTANCE));
    }

    // =========================================================================
    // HAS_FIELD
    // =========================================================================

    public static Outcome<Value> executeHasField(
            SemanticOp op,
            Map<ValueId, Value> priorValues,
            Map<ClassId, ClassLayout> layouts) {
        requireOp(op, SemanticOpKind.HAS_FIELD, FailurePolicyId.NO_DEAL_FAILURE);
        Objects.requireNonNull(priorValues, "priorValues must not be null");
        Objects.requireNonNull(layouts, "layouts must not be null");
        KindPayload.HasFieldPayload payload = (KindPayload.HasFieldPayload) op.payload();

        // The receiver resolves exactly once; the key is never evaluated.
        Value receiver = resolve(priorValues, payload.receiver());
        if (!(receiver instanceof Value.Class instance)) {
            throw new Defect("HAS_FIELD " + op.opId() + " receiver " + payload.receiver()
                + " resolves to " + receiver.actualKind()
                + ": the pinned receiver is a class instance (the checker admits class "
                + "receivers only) — a producer defect, never a projection");
        }
        ClassLayout layout = layouts.get(instance.classId());
        if (layout == null) {
            throw new Defect("HAS_FIELD " + op.opId() + " instance classId "
                + instance.classId() + " does not resolve in the layout-resolution "
                + "context: the presence read maps the static key through the instance's "
                + "own layout — an unresolvable layout is a producer defect, never "
                + "executed");
        }
        if (layout.fields().size() != instance.fields().size()) {
            throw new Defect("HAS_FIELD " + op.opId() + " instance of "
                + instance.classId() + " carries " + instance.fields().size()
                + " field states for a layout of " + layout.fields().size()
                + " fields: the pinned instance shape matches its layout's declaration "
                + "order — a count mismatch is a producer defect, never executed");
        }
        int fieldIndex = fieldIndexOf(op, layout, payload.key());
        FieldState state = instance.fields().get(fieldIndex);
        return new Outcome.Success<Value>(new Value.Bool(
            state instanceof FieldState.Present));
    }

    // =========================================================================
    // Field-op fail-closed helpers
    // =========================================================================

    private static void requireFieldBoundaryChild(SemanticOp child, SemanticOp owner,
                                                  BoundaryKind pinnedKind,
                                                  RuntimeDescriptor pinnedDescriptor,
                                                  ValueId pinnedInput) {
        requireChildOf(child, owner, pinnedKind);
        KindPayload.BoundaryPayload payload = (KindPayload.BoundaryPayload) child.payload();
        if (!(payload.realization() instanceof BoundaryRealization.RuntimeValidation)) {
            throw new Defect(owner.kind() + " " + owner.opId() + " names boundary child "
                + child.opId() + " carrying realization " + payload.realization()
                + ": the pinned field-op boundary cells are RuntimeValidation only "
                + "(representation proof is inadmissible on UNTYPED_CLASS_INPUT, "
                + "OPTIONAL_FIELD_READ, and CLASS_FIELD_ASSIGNMENT) — a producer defect, "
                + "never executed");
        }
        if (!payload.descriptor().equals(pinnedDescriptor)) {
            throw new Defect(owner.kind() + " " + owner.opId() + " names boundary child "
                + child.opId() + " carrying descriptor "
                + payload.descriptor().canonicalSpecText() + ": the pinned child "
                + "descriptor is " + pinnedDescriptor.canonicalSpecText()
                + " — a mismatch is a producer defect, never executed");
        }
        if (!payload.input().equals(pinnedInput)) {
            throw new Defect(owner.kind() + " " + owner.opId() + " names boundary child "
                + child.opId() + " carrying input " + payload.input()
                + ": the pinned input is " + pinnedInput + " (K-D6 input wiring) — a "
                + "mismatch is a producer defect, never executed");
        }
        requireDescriptorKindPolicy(child, pinnedDescriptor);
    }

    /**
     * Runs the nominal receiver boundary of one field op with the
     * resolved receiver and returns the delegate's terminal. The child's
     * pinned shape has already been checked by the caller
     * ({@link #requireFieldBoundaryChild}); the descriptor-kind policy
     * projection is the delegate's (the production
     * {@link BoundaryExecutor}'s canonical E8001/E8010 projections).
     */
    private static BoundaryResult runReceiverBoundary(SemanticOp op, SemanticOp receiverBoundary,
                                                      Value receiver,
                                                      BoundaryCheckRunner checkRunner) {
        return checkRunner.run((KindPayload.BoundaryPayload) receiverBoundary.payload(),
            receiver);
    }

    /**
     * Requires the post-boundary receiver of one field op: a
     * {@link Value.Class} instance of the payload's classId whose field
     * count matches the resolved layout's declaration order. The pinned
     * receiver boundary on a class descriptor can only pass such an
     * instance (the production delegate's canonical identity check), so
     * any other shape reaching the read/write/delete is a producer
     * defect — never a projection and never a silent read.
     */
    private static ClassLayout requireInstance(SemanticOp op, ClassId classId, Value receiver,
                                               Map<ClassId, ClassLayout> layouts) {
        if (!(receiver instanceof Value.Class instance)) {
            throw new Defect(op.kind() + " " + op.opId() + " receiver boundary passed value "
                + receiver.actualKind() + ": the pinned UNTYPED_CLASS_INPUT boundary on "
                + "descriptor class:" + classId.text() + " can only pass an instance of "
                + classId + " — a wrong-kind value after a passing receiver boundary is a "
                + "producer defect, never executed");
        }
        if (!instance.classId().equals(classId)) {
            throw new Defect(op.kind() + " " + op.opId() + " receiver boundary passed an "
                + "instance of " + instance.classId() + ": the pinned receiver is "
                + classId + " (the canonical nominal identity check) — a wrong-identity "
                + "value after a passing receiver boundary is a producer defect, never "
                + "executed");
        }
        ClassLayout layout = layouts.get(classId);
        if (layout == null) {
            throw new Defect(op.kind() + " " + op.opId() + " classId " + classId
                + " does not resolve in the layout-resolution context: the field "
                + "read/write/delete maps the static field name through the class's "
                + "layout — an unresolvable layout is a producer defect, never executed");
        }
        if (layout.fields().size() != instance.fields().size()) {
            throw new Defect(op.kind() + " " + op.opId() + " instance of " + classId
                + " carries " + instance.fields().size() + " field states for a layout of "
                + layout.fields().size() + " fields: the pinned instance shape matches its "
                + "layout's declaration order — a count mismatch is a producer defect, "
                + "never executed");
        }
        return layout;
    }

    /** The declaration-order index of {@code field} in {@code layout}, fail closed. */
    private static int fieldIndexOf(SemanticOp op, ClassLayout layout, java.lang.String field) {
        for (int i = 0; i < layout.fields().size(); i++) {
            if (layout.fields().get(i).name().equals(field)) {
                return i;
            }
        }
        throw new Defect(op.kind() + " " + op.opId() + " names field '" + field
            + "' which is not a declared field of " + layout.classId()
            + ": the pinned payload names declared fields only — a producer defect, "
            + "never executed");
    }

    /**
     * The pinned {@code OPTIONAL_FIELD_READ} result descriptor of one
     * layout field (the checker's optional-read wrap, the one canonical
     * nullable production: a declared {@code null} optional field reads as
     * {@code null}): the field's declared descriptor, nullable-wrapped for
     * an optional non-nullable field.
     */
    private static RuntimeDescriptor readResultDescriptorOf(ClassLayout.FieldLayout field) {
        return field.readResultDescriptor();
    }

    /**
     * Produces the updated instance of one field commit: the named
     * declaration-order field state replaced with the committed state
     * ({@code Present(committed)} for a write, {@code Missing} for a
     * delete); every other field state is unchanged. The model is
     * immutable, so the commit is the fresh updated instance the caller
     * rebinds the receiver's reference to — a failed boundary produces
     * no updated instance at all (nothing commits).
     */
    private static Value updatedInstance(Value.Class instance, int fieldIndex,
                                         FieldState committed) {
        List<FieldState> states = new ArrayList<>(instance.fields());
        states.set(fieldIndex, committed);
        return new Value.Class(instance.classId(), List.copyOf(states));
    }

    // =========================================================================
    // Shared fail-closed helpers
    // =========================================================================

    /**
     * Requires the pinned op shape: exactly {@code kind} and exactly
     * {@code policy}. The executor interprets validated shapes only — a
     * different kind is not the op this method executes, and a
     * non-pinned policy would silently change the contract, so both fail
     * closed as producer defects (never executed, never projected).
     */

    private static void resolveProvidedFields(
            SemanticOp op, KindPayload.ClassNewPayload payload,
            Map<ValueId, Value> priorValues, ClassLayout declaredLayout,
            LinkedHashMap<String, Value> providedValues,
            LinkedHashMap<String, ValueId> providedValueIds) {
        for (KindPayload.ProvidedField field : payload.providedFields()) {
            Value value = resolve(priorValues, field.valueOpId());
            if (value instanceof Value.Missing) {
                throw new Defect("CLASS_NEW " + op.opId() + " provided field '"
                    + field.name() + "' (" + field.valueOpId() + ") resolves to the"
                    + " internal Missing view: a provided field always carries a"
                    + " present value (language null is the explicit Null variant) — a"
                    + " wrong-kind value is a producer defect, never executed");
            }
            if (declaredLayout != null && fieldOf(declaredLayout, field.name()) == null) {
                throw new Defect("CLASS_NEW " + op.opId() + " provides field '"
                    + field.name() + "' which is not a declared field of "
                    + payload.classId() + ": the checker's E4002 rejects an extra"
                    + " literal field before lowering — a producer defect, never"
                    + " executed");
            }
            if (providedValues.putIfAbsent(field.name(), value) != null) {
                throw new Defect("CLASS_NEW " + op.opId() + " repeats provided field '"
                    + field.name() + "'");
            }
            providedValueIds.put(field.name(), field.valueOpId());
        }
        ClassLayout layout = declaredLayout == null ? payload.layout() : declaredLayout;
        for (ClassLayout.FieldLayout field : layout.fields()) {
            if (field.required() && !providedValues.containsKey(field.name())) {
                throw new Defect("CLASS_NEW " + op.opId() + " omits required-present field '"
                    + field.name() + "'");
            }
        }
    }

    private static Outcome.Failure<Value> undeclaredProvidedFieldFailure(
            SemanticOp op, KindPayload.ClassNewPayload payload, ClassLayout layout) {
        for (KindPayload.ProvidedField field : payload.providedFields()) {
            if (fieldOf(layout, field.name()) == null) {
                BoundaryFailure failure = FailureContractRegistry.render(
                    FailureArmId.CLASS_EXTRA_FIELD,
                    parametersOf("field", field.name(), "classId", payload.classId().text()),
                    null, null, null);
                return new Outcome.Failure<Value>(new OpFailure(failure, op.origin()));
            }
        }
        return null;
    }

    private static void requireBoundaryShape(
            SemanticOp op, ClassLayout layout, Map<String, Value> providedValues,
            List<KindPayload.FieldBoundary> boundaries) {
        List<java.lang.String> expectedBoundaryFields = new ArrayList<>();
        for (ClassLayout.FieldLayout fieldLayout : layout.fields()) {
            if (providedValues.containsKey(fieldLayout.name())) {
                expectedBoundaryFields.add(fieldLayout.name());
            }
        }
        if (boundaries.size() != expectedBoundaryFields.size()) {
            throw new Defect("CLASS_NEW " + op.opId() + " carries " + boundaries.size()
                + " field-boundary entries for " + expectedBoundaryFields.size()
                + " present fields: the pinned shape carries exactly one boundary per "
                + "provided field in "
                + "declaration order — a child-count mismatch is a producer defect, "
                + "never executed");
        }
        for (int i = 0; i < boundaries.size(); i++) {
            KindPayload.FieldBoundary entry = boundaries.get(i);
            java.lang.String expectedField = expectedBoundaryFields.get(i);
            if (!entry.field().equals(expectedField)) {
                throw new Defect("CLASS_NEW " + op.opId() + " field-boundary entry " + i
                    + " names field '" + entry.field() + "': the pinned declaration order "
                    + "names '" + expectedField + "' — a field-boundary order mismatch "
                    + "is a producer defect, never executed");
            }
            BoundaryKind expectedKind = BoundaryKind.CLASS_LITERAL_FIELD;
            if (entry.kind() != expectedKind) {
                throw new Defect("CLASS_NEW " + op.opId() + " field-boundary entry for '"
                    + entry.field() + "' carries kind " + entry.kind() + ": the pinned "
                    + "kind is " + expectedKind + " for provided values — a kind "
                    + "mismatch is a producer defect, never executed");
            }
        }
    }

    private static OpFailure applyFieldBoundaries(
            SemanticOp op, KindPayload.ClassNewPayload payload, ClassLayout layout,
            List<KindPayload.FieldBoundary> boundaries,
            Map<String, Value> providedValues, Map<String, ValueId> providedValueIds,
            Map<OpId, SemanticOp> boundaryOps, BoundaryCheckRunner checkRunner,
            LinkedHashMap<String, Value> checkedValues) {
        for (KindPayload.FieldBoundary entry : boundaries) {
            ClassLayout.FieldLayout fieldLayout = fieldOf(layout, entry.field());
            if (fieldLayout == null) {
                throw new Defect("CLASS_NEW " + op.opId() + " field-boundary entry names "
                    + "field '" + entry.field() + "' which is not a declared field of "
                    + payload.classId() + ": a boundary of an undeclared field is a "
                    + "producer defect, never executed");
            }
            SemanticOp child = requireBoundaryChild(boundaryOps, entry.boundaryOpId(), op,
                entry.kind());
            KindPayload.BoundaryPayload boundaryPayload =
                (KindPayload.BoundaryPayload) child.payload();
            if (!boundaryPayload.descriptor().equals(fieldLayout.descriptor())) {
                throw new Defect("CLASS_NEW " + op.opId() + " field boundary "
                    + child.opId() + " carries descriptor "
                    + boundaryPayload.descriptor().canonicalSpecText()
                    + ": the pinned child descriptor is the field's declared descriptor "
                    + fieldLayout.descriptor().canonicalSpecText() + " — a mismatch is a "
                    + "producer defect, never executed");
            }
            requireDescriptorKindPolicy(child, boundaryPayload.descriptor());
            Value input;
            if (entry.kind() == BoundaryKind.CLASS_LITERAL_FIELD) {
                Value provided = providedValues.get(entry.field());
                ValueId providedId = providedValueIds.get(entry.field());
                if (provided == null || providedId == null) {
                    throw new Defect("CLASS_NEW " + op.opId() + " carries a "
                        + "CLASS_LITERAL_FIELD boundary for field '" + entry.field()
                        + "' without a provided value: the pinned kind names provided "
                        + "values only — a producer defect, never executed");
                }
                if (!boundaryPayload.input().equals(providedId)) {
                    throw new Defect("CLASS_NEW " + op.opId() + " field boundary "
                        + child.opId() + " carries input " + boundaryPayload.input()
                        + ": the pinned CLASS_LITERAL_FIELD input is the field's "
                        + "provided value op " + providedId + " (K-D4 input wiring) — a "
                        + "mismatch is a producer defect, never executed");
                }
                input = provided;
            } else {
                throw new Defect("CLASS_NEW " + op.opId()
                    + " carries a non-provided boundary for '" + entry.field() + "'");
            }
            BoundaryResult result = checkRunner.run(boundaryPayload, input);
            if (result instanceof BoundaryResult.Pass pass) {
                checkedValues.put(entry.field(), pass.value());
                continue;
            }
            // The first failing child fails the op: no instance, no tag,
            // and the later children never run.
            BoundaryResult.Fail fail = (BoundaryResult.Fail) result;
            return new OpFailure(fail.failure(), op.origin());
        }
        return null;
    }

    /**
     * The pinned literal-field boundary coverage of the builtin/host
     * construction surfaces: exactly one {@code CLASS_LITERAL_FIELD}
     * entry per provided field in provided order, each running through
     * the delegate. The {@code shapeLabel} names the pinned shape in the
     * fail-closed messages. Returns the first failing boundary's op
     * failure, or {@code null} on success with {@code checkedValues}
     * filled.
     */
    private static OpFailure applyLiteralFieldBoundaries(
            SemanticOp op, KindPayload.ClassNewPayload payload, ClassLayout layout,
            Map<String, Value> providedValues, Map<String, ValueId> providedValueIds,
            List<KindPayload.FieldBoundary> boundaries,
            Map<OpId, SemanticOp> boundaryOps, BoundaryCheckRunner checkRunner,
            LinkedHashMap<String, Value> checkedValues, String shapeLabel) {
        if (boundaries.size() != providedValues.size()) {
            throw new Defect("CLASS_NEW " + op.opId() + " carries "
                + boundaries.size() + " field-boundary entries for "
                + providedValues.size() + " provided fields: the pinned " + shapeLabel
                + " carries exactly one CLASS_LITERAL_FIELD boundary per provided field"
                + " — a child-count mismatch is a producer defect, never executed");
        }
        for (KindPayload.FieldBoundary entry : boundaries) {
            Value provided = providedValues.get(entry.field());
            ValueId providedId = providedValueIds.get(entry.field());
            if (entry.kind() != BoundaryKind.CLASS_LITERAL_FIELD || provided == null
                    || providedId == null) {
                throw new Defect("CLASS_NEW " + op.opId() + " carries boundary entry"
                    + " for field '" + entry.field() + "' of kind " + entry.kind()
                    + ": the pinned " + shapeLabel
                    + " carries exactly one CLASS_LITERAL_FIELD boundary per provided"
                    + " field — a shape deviation is a producer defect, never executed");
            }
            ClassLayout.FieldLayout fieldLayout = fieldOf(layout, entry.field());
            if (fieldLayout == null) {
                throw new Defect("CLASS_NEW " + op.opId() + " field-boundary entry"
                    + " names field '" + entry.field() + "' which is not a declared"
                    + " field of " + payload.classId() + " — a producer defect, never"
                    + " executed");
            }
            SemanticOp child = requireBoundaryChild(boundaryOps, entry.boundaryOpId(), op,
                entry.kind());
            KindPayload.BoundaryPayload boundaryPayload =
                (KindPayload.BoundaryPayload) child.payload();
            if (!boundaryPayload.descriptor().equals(fieldLayout.descriptor())) {
                throw new Defect("CLASS_NEW " + op.opId() + " field boundary "
                    + child.opId() + " carries descriptor "
                    + boundaryPayload.descriptor().canonicalSpecText()
                    + ": the pinned child descriptor is the field's declared descriptor "
                    + fieldLayout.descriptor().canonicalSpecText() + " — a mismatch is a "
                    + "producer defect, never executed");
            }
            requireDescriptorKindPolicy(child, boundaryPayload.descriptor());
            if (!boundaryPayload.input().equals(providedId)) {
                throw new Defect("CLASS_NEW " + op.opId() + " field boundary "
                    + child.opId() + " carries input " + boundaryPayload.input()
                    + ": the pinned CLASS_LITERAL_FIELD input is the field's provided"
                    + " value op " + providedId + " (K-D4 input wiring) — a mismatch"
                    + " is a producer defect, never executed");
            }
            BoundaryResult result = checkRunner.run(boundaryPayload, provided);
            if (result instanceof BoundaryResult.Pass pass) {
                checkedValues.put(entry.field(), pass.value());
                continue;
            }
            // The first failing child fails the op: no instance, no tag, and
            // the later children never run.
            BoundaryResult.Fail fail = (BoundaryResult.Fail) result;
            return new OpFailure(fail.failure(), op.origin());
        }
        return null;
    }

    private static void requireOp(SemanticOp op, SemanticOpKind kind, FailurePolicyId policy) {
        String defect = ExecutorGuards.opShapeDefect(op, kind, policy);
        if (defect != null) {
            throw new Defect(defect);
        }
    }

    /**
     * Resolves one payload-referenced provided-field prior step. Prior
     * steps complete before operation START in the validated machine, so
     * an unresolved reference is a producer defect (fail closed, never a
     * projection and never a silent skip).
     */
    private static Value resolve(Map<ValueId, Value> priorValues, ValueId id) {
        Value value = priorValues.get(id);
        if (value == null) {
            throw new Defect("prior-step value " + id + " is not resolved in the value "
                + "lookup: every payload-referenced provided-field prior step resolves "
                + "before the op's behavior — an unresolved reference is a producer "
                + "defect, never executed");
        }
        return value;
    }

    private static SemanticOp requireBoundaryChild(Map<OpId, SemanticOp> boundaryOps,
                                                   OpId childId, SemanticOp owner,
                                                   BoundaryKind pinnedKind) {
        SemanticOp child = boundaryOps.get(childId);
        if (child == null) {
            throw new Defect(ExecutorGuards.missingBoundaryChildDefect(owner, childId));
        }
        requireChildOf(child, owner, pinnedKind);
        return child;
    }

    /** The shared child-shape checks of {@link #requireBoundaryChild}. */
    private static void requireChildOf(SemanticOp child, SemanticOp owner,
                                       BoundaryKind pinnedKind) {
        String defect = ExecutorGuards.childShapeDefect(child, owner, pinnedKind);
        if (defect != null) {
            throw new Defect(defect);
        }
    }

    /**
     * Requires the descriptor-kind rule on one field-boundary child (the
     * pinned policy of the {@code CLASS_LITERAL_FIELD} cells): {@code TYPE_DESCRIPTOR} for
     * non-function descriptors, {@code FUNCTION_SIGNATURE} for function
     * descriptors. A deviation would silently change the contract — fail
     * closed.
     */
    private static void requireDescriptorKindPolicy(SemanticOp child,
                                                    RuntimeDescriptor descriptor) {
        FailurePolicyId pinned = descriptor instanceof RuntimeDescriptor.Func
            ? FailurePolicyId.FUNCTION_SIGNATURE : FailurePolicyId.TYPE_DESCRIPTOR;
        if (child.failurePolicy() != pinned) {
            throw new Defect(child.kind() + " " + child.opId() + " carries failure policy "
                + child.failurePolicy() + " on descriptor "
                + descriptor.canonicalSpecText() + ": the pinned descriptor-kind-rule "
                + "policy is " + pinned + " — a non-pinned policy is a producer defect, "
                + "never executed");
        }
    }

    /** The layout field of {@code name} in declaration order, or {@code null}. */
    private static ClassLayout.FieldLayout fieldOf(ClassLayout layout, java.lang.String name) {
        return layout.fieldOf(name);
    }

    /** The named-parameter values of one two-parameter arm render. */
    private static Map<String, String> parametersOf(String key1, String value1,
                                                    String key2, String value2) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put(key1, value1);
        parameters.put(key2, value2);
        return parameters;
    }
}
