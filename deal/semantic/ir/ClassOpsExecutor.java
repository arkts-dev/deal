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
        permits Value.Null, Value.Bool, Value.Number, Value.String,
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
        record Array(SemanticArray<Value> array) implements Value {

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
            readValue = Value.Null.INSTANCE;
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

    public sealed interface JsonParse permits JsonParse.Success, JsonParse.SyntaxFailure {

        /**
         * The parse succeeded: {@code value} is the closed parsed-JSON
         * value model of the input — exactly the JSON-shaped subset of
         * the executor's {@link Value} view: {@code Null}, {@code Bool},
         * {@code Int} (signed32 integer lexical forms only),
         * {@code Number}, {@code String} (valid scalar carriers),
         * {@code Table} (objects with ordered entries), and
         * {@code Array}. A value outside that set is a seam-contract
         * violation and fails closed as a producer {@link Defect}.
         */
        record Success(Value value) implements JsonParse {

            public Success {
                Objects.requireNonNull(value, "value must not be null");
            }
        }

        /** The input is not RFC-8259 scalar-valid JSON text. */
        record SyntaxFailure() implements JsonParse {
        }
    }

    public sealed interface JsonStringify permits JsonStringify.Success, JsonStringify.Failure {

        /** The exact RFC-8259 text of the JSON-shaped value. */
        record Success(UnicodeScalars.Valid text) implements JsonStringify {

            public Success {
                Objects.requireNonNull(text, "text must not be null");
            }
        }

        /**
         * The first declaration-order failure: {@code fieldPath} is the
         * full pinned-convention path (the caller's prefix plus the
         * seam's relative segments), {@code actual} is the offending
         * value's closed typed-boundary token, and {@code cycle} is the
         * seam's closed arm selection — true exactly for a path-local
         * container re-entry, which selects the cycle arm; the walk
         * never infers an arm from a token.
         */
        record Failure(String fieldPath, String actual, boolean cycle)
                implements JsonStringify {

            public Failure {
                Objects.requireNonNull(fieldPath, "fieldPath must not be null");
                Objects.requireNonNull(actual, "actual must not be null");
            }

            public Failure(String fieldPath, String actual) {
                this(fieldPath, actual, false);
            }
        }
    }

    @FunctionalInterface
    public interface JsonParser {

        /**
         * Parses one scalar-valid JSON text per the E8 {@code JSON_PARSE}
         * row.
         *
         */
        JsonParse parse(UnicodeScalars.Valid text);
    }

    @FunctionalInterface
    public interface JsonStringifier {

        /**
         * Stringifies one JSON-shaped value per the E8
         * {@code JSON_STRINGIFY} row: {@code entered} is the enclosing
         * class/declared-array walk's path-local container-identity set
         * (see {@link ClassOpsExecutor#executeJsonToClass}). The seam's own first-failure
         * walk continues that same identity discipline — it adds its own
         * table/array containers on entry and removes them on exit, and it
         * never removes an entry it did not add — so a table-subtree
         * position that re-enters a container already on the path (a
         * declared-array element position included) selects the cycle arm
         * exactly like the emitted Lua/JVM walks. There is no fixed
         * nesting-depth bound: a container is never rejected merely for
         * its depth, only for an unsupported carrier, an invalid scalar,
         * a nonfinite number, or a path-local re-entry.
         *
         */
        JsonStringify stringify(Value jsonShaped, String fieldPathPrefix,
                               Set<Object> entered);

        /**
         * The stringify of one JSON-shaped value with a fresh path-local
         * identity set (the direct seam callers outside the class walk).
         *
         */
        default JsonStringify stringify(Value jsonShaped, String fieldPathPrefix) {
            return stringify(jsonShaped, fieldPathPrefix,
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        }
    }

    public static Value executeJsonFromClass(
            SemanticOp op,
            Map<ValueId, Value> priorValues,
            Map<ClassId, ClassLayout> layouts,
            JsonParser parser) {
        requireOp(op, SemanticOpKind.JSON_FROM_CLASS, FailurePolicyId.JSON_FROM_NULL);
        Objects.requireNonNull(priorValues, "priorValues must not be null");
        Objects.requireNonNull(layouts, "layouts must not be null");
        Objects.requireNonNull(parser, "parser must not be null");
        KindPayload.JsonFromClassPayload payload =
            (KindPayload.JsonFromClassPayload) op.payload();

        ClassLayout layout = layouts.get(payload.layout().classId());
        if (layout == null || !layout.equals(payload.layout())) {
            throw new Defect("JSON_FROM_CLASS " + op.opId() + " layout of "
                + payload.layout().classId() + " does not resolve to exactly the "
                + "payload's layout in the layout-resolution context: the payload's "
                + "layout must be exactly the resolved layout — an unresolvable or "
                + "mismatched layout is a producer defect, never executed");
        }

        // The JSON string operand resolves exactly once (never
        // re-evaluated); an invalid scalar carrier is a syntax defect of
        // the walk's input — RFC-8259 text is scalar-valid, so the walk
        // returns language null before any parse or default runs.
        Value operand = resolve(priorValues, payload.jsonString());
        if (!(operand instanceof Value.String string)) {
            throw new Defect("JSON_FROM_CLASS " + op.opId() + " jsonString operand "
                + payload.jsonString() + " resolves to " + operand.actualKind()
                + ": the pinned operand is a string value (the generated body's "
                + "parameter load) — a wrong-kind operand is a producer defect, never "
                + "executed");
        }
        if (!(string.scalar() instanceof UnicodeScalars.Valid valid)) {
            return Value.Null.INSTANCE;
        }

        // Parse through the seam (the E8 JSON_PARSE algorithm); a syntax
        // defect returns language null — no DEAL failure.
        JsonParse parsed = parser.parse(valid);
        if (parsed instanceof JsonParse.SyntaxFailure) {
            return Value.Null.INSTANCE;
        }
        Value document = ((JsonParse.Success) parsed).value();
        requireJsonShape(op, document);

        if (document instanceof Value.Null) {
            return Value.Null.INSTANCE;
        }
        // The root gate: only a JSON object document decodes (the class
        // helper's one accepted shape). An array document of any length —
        // the empty array included — is a decode failure returning
        // language null, never an empty-object collapse.
        if (!(document instanceof Value.Table table)) {
            return Value.Null.INSTANCE;
        }
        try {
            return decodeFromJson(op, layout, table, layouts);
        } catch (JsonFromFailure failure) {
            // Decode failure publishes null, never a partial instance.
            return Value.Null.INSTANCE;
        }
    }

    public static Outcome<Value> executeJsonToClass(
            SemanticOp op,
            Map<ValueId, Value> priorValues,
            Map<ClassId, ClassLayout> layouts,
            JsonStringifier stringifier,
            SourceOrigin callOrigin) {
        requireOp(op, SemanticOpKind.JSON_TO_CLASS, FailurePolicyId.JSON_TO_ERROR);
        Objects.requireNonNull(priorValues, "priorValues must not be null");
        Objects.requireNonNull(layouts, "layouts must not be null");
        Objects.requireNonNull(stringifier, "stringifier must not be null");
        Objects.requireNonNull(callOrigin, "callOrigin must not be null");
        KindPayload.JsonToClassPayload payload =
            (KindPayload.JsonToClassPayload) op.payload();

        ClassLayout layout = layouts.get(payload.layout().classId());
        if (layout == null || !layout.equals(payload.layout())) {
            throw new Defect("JSON_TO_CLASS " + op.opId() + " layout of "
                + payload.layout().classId() + " does not resolve to exactly the "
                + "payload's layout in the layout-resolution context: the payload's "
                + "layout must be exactly the resolved layout — an unresolvable or "
                + "mismatched layout is a producer defect, never executed");
        }

        // The class value operand resolves exactly once (never
        // re-evaluated).
        Value operand = resolve(priorValues, payload.classValue());
        Set<Object> entered = java.util.Collections.newSetFromMap(
            new java.util.IdentityHashMap<>());
        try {
            String text = encodeToJson(op, layout, operand, layouts, stringifier,
                entered, "");
            return new Outcome.Success<Value>(Value.string(text));
        } catch (JsonToFailure failure) {
            // The first declaration-order failure: the closed arm the
            // walk selected (the walk arm with {fieldPath}/{actual}, or
            // the cycle arm with no parameters) rendered by the
            // authority at the call origin (never the generated body's
            // synthetic anchor) with no cause. The walk composes no
            // text, no token, and no span of its own.
            BoundaryFailure row = switch (failure.arm) {
                case JSON_TO_WALK_CYCLE -> FailureContractRegistry.render(
                    FailureArmId.JSON_TO_WALK_CYCLE, Map.of(), null, null, null);
                default -> FailureContractRegistry.render(
                    FailureArmId.JSON_TO_WALK,
                    parametersOf("fieldPath", failure.fieldPath, "actual",
                        failure.actual),
                    null, failure.actual, null);
            };
            return new Outcome.Failure<Value>(new OpFailure(row, callOrigin));
        }
    }

    // =========================================================================
    // The JSON walk internals (fail closed, never a DEAL projection)
    // =========================================================================

    private static final class JsonFromFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        JsonFromFailure(String reason) {
            super(reason);
        }

        JsonFromFailure(String reason, Throwable cause) {
            super(reason, cause);
        }
    }

    /**
     * The internal failure of the {@code JSON_TO_CLASS} walk: the closed arm
     * selection (the walk arm at the walk's own checks, the cycle arm at the
     * path-local container re-entry needle), the first declaration-order
     * failing position's pinned fieldPath, and the offending value's closed
     * typed-boundary token — the carrier {@link #executeJsonToClass} renders
     * through the authority at the call origin
     * (jsonable-tojson-walk-arm-binding W3). The arm is selected by the
     * walk's own closed marker, never by a token comparison. The carrier is
     * data-only: {@link #executeJsonToClass} renders the arm through
     * {@code FailureContractRegistry.render}, which is the only producer of
     * the product text, so no product message is composed here.
     */
    private static final class JsonToFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        final FailureArmId arm;
        final String fieldPath;
        final String actual;

        JsonToFailure(String fieldPath, String actual) {
            this(FailureArmId.JSON_TO_WALK, fieldPath, actual);
        }

        JsonToFailure(FailureArmId arm, String fieldPath, String actual) {
            super(null, null);
            this.arm = arm;
            this.fieldPath = fieldPath;
            this.actual = actual;
        }

        /** The path-local container re-entry needle's failure (the cycle arm). */
        static JsonToFailure cycle() {
            return new JsonToFailure(FailureArmId.JSON_TO_WALK_CYCLE, null, null);
        }
    }

    private static Value decodeFromJson(
            SemanticOp op, ClassLayout layout, Value.Table document,
            Map<ClassId, ClassLayout> layouts) {
        SemanticTable<Value> entries = document.table();
        for (String key : entries.keys()) {
            if (fieldOf(layout, key) == null) {
                throw new JsonFromFailure("extra key '" + key + "' in the JSON document "
                    + "of " + layout.classId() + " (K-D8 step 3)");
            }
        }

        LinkedHashMap<String, Value> instanceFields = new LinkedHashMap<>();
        for (ClassLayout.FieldLayout field : layout.fields()) {
            SemanticTable.Lookup<Value> lookup = entries.get(field.name());
            if (!(lookup instanceof SemanticTable.Lookup.Present<Value> present)) {
                continue;
            }
            Value raw = present.value();
            requireJsonShape(op, raw);
            instanceFields.put(field.name(), decodeRaw(op, field.descriptor(), raw,
                layouts));
        }

        for (ClassLayout.FieldLayout field : layout.fields()) {
            if (instanceFields.containsKey(field.name())) {
                continue; // provided: decoded; the skip-provided rule.
            }
            if (!field.required()) {
                continue; // optional: stays missing, no default ever runs.
            }
            throw new JsonFromFailure("required-present field '" + field.name()
                + "' absent from the JSON document");
        }

        for (ClassLayout.FieldLayout field : layout.fields()) {
            Value value = instanceFields.get(field.name());
            if (value == null) {
                continue;
            }
            requireDescriptorConforming(op, field.descriptor(), value, layouts);
        }

        return taggedInstance(layout, instanceFields);
    }

    private static Value decodeNestedClass(
            SemanticOp op, ClassId classId, Value.Table document,
            Map<ClassId, ClassLayout> layouts) {
        ClassLayout nested = layouts.get(classId);
        if (nested == null) {
            throw new Defect("JSON_FROM_CLASS " + op.opId() + " nested decode of "
                + classId + " does not resolve in the layout-resolution context: every "
                + "nested @jsonable class resolves through the unit's classLayouts plus "
                + "the project interface index — an unresolvable nested layout is a "
                + "producer defect, never executed");
        }
        // The nested extra-key gate in document order.
        SemanticTable<Value> entries = document.table();
        for (String key : entries.keys()) {
            if (fieldOf(nested, key) == null) {
                throw new JsonFromFailure("extra key '" + key + "' in the JSON document "
                    + "of nested class " + classId + " (K-D8 step 6)");
            }
        }
        // Nested provided-field decode in declaration order.
        LinkedHashMap<String, Value> decoded = new LinkedHashMap<>();
        for (ClassLayout.FieldLayout field : nested.fields()) {
            SemanticTable.Lookup<Value> lookup = entries.get(field.name());
            if (!(lookup instanceof SemanticTable.Lookup.Present<Value> present)) {
                continue;
            }
            Value raw = present.value();
            requireJsonShape(op, raw);
            decoded.put(field.name(), decodeRaw(op, field.descriptor(), raw, layouts));
        }

        LinkedHashMap<String, Value> instanceFields = new LinkedHashMap<>();
        for (ClassLayout.FieldLayout field : nested.fields()) {
            Value provided = decoded.get(field.name());
            if (provided != null) {
                instanceFields.put(field.name(), provided);
                continue;
            }
            if (field.required()) {
                throw new JsonFromFailure("required-present field '" + field.name()
                    + "' of nested class " + classId + " absent from the JSON document");
            }
        }
        // Nested final validation in declaration order, then the nested
        // tag.
        for (ClassLayout.FieldLayout field : nested.fields()) {
            Value value = instanceFields.get(field.name());
            if (value == null) {
                continue;
            }
            requireDescriptorConforming(op, field.descriptor(), value, layouts);
        }
        return taggedInstance(nested, instanceFields);
    }

    private static Value decodeRaw(
            SemanticOp op, RuntimeDescriptor descriptor, Value raw,
            Map<ClassId, ClassLayout> layouts) {
        return switch (descriptor) {
            case RuntimeDescriptor.Null ignored -> {
                if (!(raw instanceof Value.Null)) {
                    throw new JsonFromFailure("expected null, got " + raw.actualKind());
                }
                yield Value.Null.INSTANCE;
            }
            case RuntimeDescriptor.Boolean ignored -> {
                if (!(raw instanceof Value.Bool)) {
                    throw new JsonFromFailure(
                        "expected boolean, got " + raw.actualKind());
                }
                yield raw;
            }

            case RuntimeDescriptor.Number ignored -> {
                if (raw instanceof Value.Number) {
                    yield raw;
                }

                throw new JsonFromFailure("expected number, got " + raw.actualKind());
            }
            case RuntimeDescriptor.String ignored -> {
                if (!(raw instanceof Value.String)) {
                    throw new JsonFromFailure(
                        "expected string, got " + raw.actualKind());
                }
                yield raw;
            }
            case RuntimeDescriptor.Table ignored -> {
                if (raw instanceof Value.Table tableValue) {
                    boundParsedTableContents(op, tableValue);
                    yield tableValue;
                }
                // A table field accepts only a JSON object
                // (docs/spec-v1.4.md:695): an array-shaped value of any
                // length, the empty array included, is a validation
                // failure — never an empty table.
                throw new JsonFromFailure("expected table, got " + raw.actualKind());
            }
            case RuntimeDescriptor.Array arrayDescriptor -> {
                if (!(raw instanceof Value.Array elements)) {
                    throw new JsonFromFailure(
                        "expected array, got " + raw.actualKind());
                }
                List<Value> decoded = new ArrayList<>(elements.array().size());
                for (Value element : elements.array().elements()) {
                    requireJsonShape(op, element);
                    decoded.add(decodeRaw(op, arrayDescriptor.element(), element, layouts));
                }
                yield new Value.Array(SemanticArray.of(decoded));
            }
            case RuntimeDescriptor.Nullable nullable -> {
                if (raw instanceof Value.Null) {
                    yield Value.Null.INSTANCE;
                }
                yield decodeRaw(op, nullable.inner(), raw, layouts);
            }
            case RuntimeDescriptor.Class classDescriptor -> {
                if (raw instanceof Value.Null) {
                    throw new JsonFromFailure("expected "
                        + classDescriptor.canonicalSpecText() + ", got null (a JSON "
                        + "null on a non-nullable field fails)");
                }
                // The nested class value is a JSON object, exactly the
                // root gate's own shape: an array value of any length —
                // the empty array included — is a validation failure,
                // never an empty-object collapse.
                if (raw instanceof Value.Table) {
                    yield decodeNestedClass(op, classDescriptor.classId(),
                        (Value.Table) raw, layouts);
                }
                throw new JsonFromFailure("expected "
                    + classDescriptor.canonicalSpecText() + ", got " + raw.actualKind());
            }
            case RuntimeDescriptor.Func ignored -> throw new Defect(
                "a @jsonable field never carries a function descriptor (the checker's "
                    + "E4007 allowlist) — a function-typed JSON field is a producer "
                    + "defect, never executed");
        };
    }

    private static String encodeToJson(
            SemanticOp op, ClassLayout layout, Value value,
            Map<ClassId, ClassLayout> layouts, JsonStringifier stringifier,
            Set<Object> entered, String fieldPath) {
        // The root identity check (K-D10 step 1): the value must be an
        // instance of exactly layout.classId — a wrong identity fails
        // with the carried canonical class atom, any other kind with its
        // own closed typed-boundary token.
        if (!(value instanceof Value.Class instance)) {
            throw new JsonToFailure(fieldPath, actualTokenOf(value));
        }
        if (!instance.classId().equals(layout.classId())) {
            throw new JsonToFailure(fieldPath, actualTokenOf(value));
        }
        if (instance.fields().size() != layout.fields().size()) {
            throw new Defect("JSON_TO_CLASS " + op.opId() + " root instance of "
                + layout.classId() + " carries " + instance.fields().size()
                + " field states for a layout of " + layout.fields().size()
                + " fields: the pinned instance shape matches its layout's declaration "
                + "order — a count mismatch is a producer defect, never executed");
        }
        // The path-local cycle check over entered class instances: the
        // needle's own fact selects the cycle arm (never a token
        // comparison).
        if (!entered.add(instance)) {
            throw JsonToFailure.cycle();
        }
        try {
            StringBuilder out = new StringBuilder();
            out.append('{');
            boolean first = true;
            for (int i = 0; i < layout.fields().size(); i++) {
                ClassLayout.FieldLayout field = layout.fields().get(i);
                FieldState state = instance.fields().get(i);
                if (state instanceof FieldState.Missing) {
                    // A missing required field fails (the "missing
                    // required value" arm); an omitted optional field is
                    // skipped.
                    if (field.required()) {
                        throw new JsonToFailure(fieldPathOf(fieldPath, field.name()),
                            FailureProjections.typedBoundaryToken(ActualKind.MISSING,
                                null));
                    }
                    continue;
                }
                Value fieldValue = ((FieldState.Present) state).value();
                String fieldPosition = fieldPathOf(fieldPath, field.name());

                String encoded = encodeField(op, field.descriptor(), fieldValue,
                    fieldPosition, layouts, stringifier, entered);
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(jsonStringOf(field.name()));
                out.append(':');
                out.append(encoded);
            }
            out.append('}');
            return out.toString();
        } finally {
            entered.remove(instance);
        }
    }

    private static String encodeField(
            SemanticOp op, RuntimeDescriptor descriptor, Value value, String fieldPath,
            Map<ClassId, ClassLayout> layouts, JsonStringifier stringifier,
            Set<Object> entered) {
        return switch (descriptor) {
            case RuntimeDescriptor.Null ignored -> {
                if (!(value instanceof Value.Null)) {
                    throw new JsonToFailure(fieldPath, actualTokenOf(value));
                }
                yield seamText(op, stringifier, value, fieldPath, entered);
            }
            case RuntimeDescriptor.Boolean ignored -> {
                if (!(value instanceof Value.Bool)) {
                    throw new JsonToFailure(fieldPath, actualTokenOf(value));
                }
                yield seamText(op, stringifier, value, fieldPath, entered);
            }

            case RuntimeDescriptor.Number ignored -> {
                if (!(value instanceof Value.Number)) {
                    throw new JsonToFailure(fieldPath, actualTokenOf(value));
                }
                // Nonfinite numbers fail inside the seam (the E8 row).
                yield seamText(op, stringifier, value, fieldPath, entered);
            }
            case RuntimeDescriptor.String ignored -> {
                if (!(value instanceof Value.String string)) {
                    throw new JsonToFailure(fieldPath, actualTokenOf(value));
                }
                if (!(string.scalar() instanceof UnicodeScalars.Valid)) {
                    throw new JsonToFailure(fieldPath,
                        FailureProjections.typedBoundaryToken(
                            ActualKind.INVALID_UNICODE, null));
                }
                yield seamText(op, stringifier, value, fieldPath, entered);
            }
            case RuntimeDescriptor.Table ignored -> {
                if (!(value instanceof Value.Table)) {
                    throw new JsonToFailure(fieldPath, actualTokenOf(value));
                }
                // The seam's own first-failure walk owns the table
                // contents' walk order (the pre-walk selects an earlier
                // position's carrier/cycle failure first).
                yield seamText(op, stringifier, value, fieldPath, entered);
            }
            case RuntimeDescriptor.Array arrayDescriptor -> {
                if (!(value instanceof Value.Array elements)) {
                    throw new JsonToFailure(fieldPath, actualTokenOf(value));
                }
                yield encodeArrayElements(op, arrayDescriptor.element(), elements,
                    fieldPath, layouts, stringifier, entered);
            }
            case RuntimeDescriptor.Nullable nullable -> {
                if (value instanceof Value.Null) {
                    yield seamText(op, stringifier, value, fieldPath, entered);
                }
                yield encodeField(op, nullable.inner(), value, fieldPath, layouts,
                    stringifier, entered);
            }
            case RuntimeDescriptor.Class classDescriptor -> {
                if (value instanceof Value.Null) {
                    throw new JsonToFailure(fieldPath, actualTokenOf(value));
                }
                if (!(value instanceof Value.Class nested)) {
                    throw new JsonToFailure(fieldPath, actualTokenOf(value));
                }
                if (!nested.classId().equals(classDescriptor.classId())) {
                    // The nested identity check: a wrong identity fails
                    // with actual class:<other> at the nested position.
                    throw new JsonToFailure(fieldPath, actualTokenOf(nested));
                }
                ClassLayout nestedLayout = layouts.get(classDescriptor.classId());
                if (nestedLayout == null) {
                    throw new Defect("JSON_TO_CLASS " + op.opId() + " nested class "
                        + classDescriptor.classId() + " does not resolve in the "
                        + "layout-resolution context — an unresolvable nested layout is "
                        + "a producer defect, never executed");
                }
                yield encodeToJson(op, nestedLayout, nested, layouts, stringifier,
                    entered, fieldPath);
            }
            case RuntimeDescriptor.Func ignored -> throw new Defect(
                "a @jsonable field never carries a function descriptor (the checker's "
                    + "E4007 allowlist) — a function-typed JSON field is a producer "
                    + "defect, never executed");
        };
    }

    private static String encodeArrayElements(
            SemanticOp op, RuntimeDescriptor elementDescriptor, Value.Array elements,
            String fieldPath, Map<ClassId, ClassLayout> layouts,
            JsonStringifier stringifier, Set<Object> entered) {
        // The path-local cycle check over entered array containers (the
        // same needle discipline the emitted Lua/JVM walks apply, and the
        // same identity set the table-subtree seam continues): the
        // needle's own fact selects the cycle arm (never a token
        // comparison).
        if (!entered.add(elements.array())) {
            throw JsonToFailure.cycle();
        }
        try {
            StringBuilder out = new StringBuilder();
            out.append('[');
            for (int i = 0; i < elements.array().size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                String elementPath = fieldPath + "[" + i + "]";
                out.append(encodeField(op, elementDescriptor,
                    elements.array().elementAt(i), elementPath, layouts, stringifier,
                    entered));
            }
            out.append(']');
            return out.toString();
        } finally {
            entered.remove(elements.array());
        }
    }

    /**
     * Encodes one leaf/table value through the stringify seam and
     * converts the seam's failure into the walk's own
     * {@link JsonToFailure} (the seam reports the full pinned-convention
     * path prefixed with the caller's field path). The seam continues the
     * class walk's path-local container-identity set, so a table-subtree
     * position that re-enters an already-entered declared-array container
     * selects the cycle arm at the re-entry, exactly like the emitted
     * Lua/JVM walks.
     */
    private static String seamText(SemanticOp op, JsonStringifier stringifier,
                                   Value value, String fieldPath,
                                   Set<Object> entered) {
        JsonStringify rendered = stringifier.stringify(value, fieldPath, entered);
        return switch (rendered) {
            case JsonStringify.Success success -> success.text().carrier();
            case JsonStringify.Failure failure -> throw failure.cycle()
                ? JsonToFailure.cycle()
                : new JsonToFailure(failure.fieldPath(), failure.actual());
        };
    }

    /**
     * The closed typed-boundary token of one walk value (the bound
     * {@code JSON_TO_WALK} arm's actual): a class instance projects its
     * carried canonical class atom — never the {@code class:<ClassId>}
     * IR spelling — and every other kind its closed token.
     */
    private static String actualTokenOf(Value value) {
        if (value instanceof Value.Class instance) {
            return FailureProjections.typedBoundaryToken(ActualKind.CLASS,
                instance.classId().text());
        }
        return FailureProjections.typedBoundaryToken(value.actualKind(), null);
    }

    private static void requireDescriptorConforming(SemanticOp op,
                                                    RuntimeDescriptor descriptor,
                                                    Value value,
                                                    Map<ClassId, ClassLayout> layouts) {
        switch (descriptor) {
            case RuntimeDescriptor.Null ignored -> {
                if (value instanceof Value.Null) {
                    return;
                }
            }
            case RuntimeDescriptor.Boolean ignored -> {
                if (value instanceof Value.Bool) {
                    return;
                }
            }

            case RuntimeDescriptor.Number ignored -> {
                if (value instanceof Value.Number) {
                    return;
                }
            }
            case RuntimeDescriptor.String ignored -> {
                if (value instanceof Value.String string
                        && string.scalar() instanceof UnicodeScalars.Valid) {
                    return;
                }
            }
            case RuntimeDescriptor.Table ignored -> {
                if (value instanceof Value.Table) {
                    return;
                }
            }
            case RuntimeDescriptor.Array arrayDescriptor -> {
                if (value instanceof Value.Array elements) {
                    for (Value element : elements.array().elements()) {
                        requireDescriptorConforming(op, arrayDescriptor.element(),
                            element, layouts);
                    }
                    return;
                }
            }
            case RuntimeDescriptor.Nullable nullable -> {
                if (value instanceof Value.Null) {
                    return;
                }
                requireDescriptorConforming(op, nullable.inner(), value, layouts);
                return;
            }
            case RuntimeDescriptor.Class classDescriptor -> {
                if (value instanceof Value.Class instance) {
                    if (!instance.classId().equals(classDescriptor.classId())) {
                        break;
                    }
                    ClassLayout nested = layouts.get(classDescriptor.classId());
                    if (nested != null
                            && nested.fields().size() != instance.fields().size()) {
                        break;
                    }
                    return;
                }
            }
            case RuntimeDescriptor.Func ignored -> throw new Defect(
                "a @jsonable field never carries a function descriptor (the checker's "
                    + "E4007 allowlist) — a function-typed JSON field is a producer "
                    + "defect, never executed");
        }
        throw new JsonFromFailure("value for " + descriptor.canonicalSpecText() + " is "
            + actualTokenOf(value));
    }

    /**
     * Requires one parsed-JSON value to sit in the closed JSON-shaped
     * set of the seam's contract (null/bool/int/number/valid string/
     * table/array). A value outside the set is a seam-contract
     * violation and fails closed as a producer {@link Defect}, never as
     * a walk failure.
     */
    private static void requireJsonShape(SemanticOp op, Value raw) {
        switch (raw) {
            case Value.Null ignored -> {
            }
            case Value.Bool ignored -> {
            }

            case Value.Number ignored -> {
            }
            case Value.Table ignored -> {
            }
            case Value.Array ignored -> {
            }
            case Value.String string -> {
                if (!(string.scalar() instanceof UnicodeScalars.Valid)) {
                    throw new Defect("JSON_FROM_CLASS " + op.opId() + ": the JSON "
                        + "algorithm seam produced an invalid-scalar string carrier: "
                        + "the closed parsed-JSON value model carries valid scalar "
                        + "strings only — a seam-contract violation is a producer "
                        + "defect, never executed");
                }
            }
            default -> throw new Defect("JSON_FROM_CLASS " + op.opId() + ": the JSON "
                + "algorithm seam produced a non-JSON-shaped value "
                + raw.actualKind() + ": the closed parsed-JSON value model carries "
                + "null/bool/int/number/string/object/array only — a seam-contract "
                + "violation is a producer defect, never executed");
        }
    }

    private static void boundParsedTableContents(SemanticOp op, Value value) {
        switch (value) {
            case Value.Table table -> {
                for (String key : table.table().keys()) {
                    SemanticTable.Lookup<Value> lookup = table.table().get(key);
                    if (!(lookup instanceof SemanticTable.Lookup.Present<Value> present)) {
                        throw new Defect("JSON_FROM_CLASS " + op.opId() + ": a parsed "
                            + "table key '" + key + "' reads Missing: the closed parsed "
                            + "model keeps every listed key present — a seam-contract "
                            + "violation is a producer defect, never executed");
                    }
                    requireJsonShape(op, present.value());
                    boundParsedTableContents(op, present.value());
                }
            }
            case Value.Array array -> {
                for (Value element : array.array().elements()) {
                    requireJsonShape(op, element);
                    boundParsedTableContents(op, element);
                }
            }
            default -> {
                // JSON-shaped leaves (validated by requireJsonShape at
                // every recursion site).
            }
        }
    }

    private static String fieldPathOf(String parent, String fieldName) {
        return parent.isEmpty() ? fieldName : parent + "." + fieldName;
    }

    private static Value taggedInstance(ClassLayout layout,
                                        LinkedHashMap<String, Value> instanceFields) {
        List<FieldState> states = new ArrayList<>(layout.fields().size());
        for (ClassLayout.FieldLayout field : layout.fields()) {
            Value value = instanceFields.get(field.name());
            states.add(value == null ? FieldState.Missing.INSTANCE
                : new FieldState.Present(value));
        }
        return new Value.Class(layout.classId(), List.copyOf(states));
    }

    /** The RFC-8259 text of one declared field name (an identifier scalar sequence). */
    private static String jsonStringOf(String name) {
        // Field names are checker-pinned identifiers: quote, backslash,
        // and control scalars cannot appear, so the exact RFC-8259 text
        // is the quoted name itself.
        return "\"" + name + "\"";
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
        for (ClassLayout.FieldLayout field : layout.fields()) {
            if (field.name().equals(name)) {
                return field;
            }
        }
        return null;
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
