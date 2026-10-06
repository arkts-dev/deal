package deal.semantic.ir;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ContainerOpsExecutor {

    private ContainerOpsExecutor() {
        // Static surface only; pure and stateless.
    }

    // =========================================================================
    // Producer defects (fail closed, never a DEAL projection)
    // =========================================================================

    /**
     * An executor producer defect: a shape outside the pinned contracts
     * reached the executor — a wrong op kind, a non-pinned failure
     * policy, an unresolvable prior-step value, a wrong-kind
     * receiver/iterable/fragment, a boundary child of the wrong kind or
     * parentage, an element-boundary count mismatch, an {@code Invalid}
     * fragment inside {@code STRING_CONCAT}, a non-{@code STRING_SCALARS}
     * iteration mode, or a loop-binding load carrying a generation other
     * than the payload's initial generation. Internal control flow — fail
     * closed, never a DEAL projection and never a crash (the
     * validated-shapes-only discipline).
     */
    public static final class Defect extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public Defect(String message) {
            super(message);
        }
    }

    // =========================================================================
    // The closed value view of the six operations
    // =========================================================================

    /**
     * The closed value view the executor consumes and produces over the
     * semantic value model: exactly the values the six container/string
     * operations resolve, store, yield, concatenate, and publish —
     * language null, boolean, signed32 int, IEEE-754 number, string
     * (carrying the closed {@link UnicodeScalars.ScalarString}
     * classification), table ({@link SemanticTable}), array
     * ({@link SemanticArray}), bytes ({@link Value.Bytes}, K6 items
     * 10/11), and the internal {@code missing} (the
     * schema's {@link ActualKind#MISSING}, never a Java null reference).
     * Function, class, and async-operation values are the oracle's
     * value-model surface and are never produced or consumed by these six
     * operations, so the view does not carry them.
     *
     * <p>Every variant renders its canonical actual kind
     * ({@link ActualKind}): a {@link String} view renders
     * {@code STRING} for a {@code Valid} scalar sequence and
     * {@code INVALID_UNICODE} for an {@code Invalid} one (the closed
     * classification travels with the value). Null references never
     * appear: language null is the explicit {@link Null} variant, and
     * absent slots are the explicit {@link Missing} variant.</p>
     *
     * <p>The view is realized by the oracle's heap and by fixture
     * lookups; the executor interprets classifications and carriers only,
     * never target representations.</p>
     */
    public sealed interface Value
        permits Value.Null, Value.Bool, Value.Int, Value.Number, Value.String,
                Value.Table, Value.Array, Value.Bytes, Value.Missing {

        /** The canonical actual kind this value renders as. */
        ActualKind actualKind();

        /** A string view carrying the validated classification of {@code carrier}. */
        static Value string(java.lang.String carrier) {
            return new String(UnicodeScalars.validate(carrier));
        }

        /** A string view carrying an explicit scalar classification. */
        static Value string(UnicodeScalars.ScalarString scalar) {
            return new String(Objects.requireNonNull(scalar, "scalar must not be null"));
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

        /** A signed32 integer value. */
        record Int(int value) implements Value {

            @Override
            public ActualKind actualKind() {
                return ActualKind.INT;
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

        /**
         * A bytes buffer view (K6 item 10): the same mutable storage as the
         * oracle's bytes value, shared by every alias, so an element write
         * through one view is observed through every other. The logical
         * length is fixed at allocation and never changes; the view is
         * classification-only for boundary projection (K6 item 11).
         */
        record Bytes(byte[] storage, int length) implements Value {

            public Bytes {
                Objects.requireNonNull(storage, "storage must not be null");
                if (length < 0 || length > storage.length) {
                    throw new Defect("a bytes view's logical length " + length
                        + " is outside its storage of " + storage.length
                        + " byte(s)");
                }
            }

            @Override
            public ActualKind actualKind() {
                return ActualKind.BYTES;
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

        /** The internal missing (an absent table slot; never Java null). */
        enum Missing implements Value {
            INSTANCE;

            @Override
            public ActualKind actualKind() {
                return ActualKind.MISSING;
            }
        }
    }

    // =========================================================================
    // Op-level failure and outcome
    // =========================================================================

    /**
     * One op-level failure: the structured registry-arm projection
     * (rendered through {@link FailureContractRegistry#render}) paired with
     * the executed op's origin — the operation origin of the closed table's
     * rule (for {@code FOR_EACH(STRING_SCALARS)} the for-of origin; for
     * {@code ARRAY_LENGTH} the {@code .length} span; for a
     * {@code ARRAY_NEW}/{@code MEMBER_READ} child failure the parent op's
     * origin — the child's own origin stays observable through the unit's
     * ops).
     */
    public record OpFailure(BoundaryFailure failure, SourceOrigin origin) {

        public OpFailure {
            Objects.requireNonNull(failure, "failure must not be null");
            Objects.requireNonNull(origin, "origin must not be null");
        }
    }

    /**
     * The closed terminal of one executor call:
     * {@code Success(value) | Failure(OpFailure)}. A {@code Failure} is
     * exactly one pinned projection — never a partial value and never a
     * retry.
     *
     */
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

    // =========================================================================
    // Boundary-check delegate seam (D3)
    // =========================================================================

    @FunctionalInterface
    public interface BoundaryCheckRunner {

        /**
         * Runs one boundary check of the op's orchestration.
         *
         */
        BoundaryResult run(KindPayload.BoundaryPayload boundary, Value input);
    }

    /**
     * The delegate's terminal: {@code Pass(value) | Fail(failure)}. The
     * first {@code Fail} of an {@code ARRAY_NEW} element run fails the op
     * with that child's failure — no allocation, no partial instance, and
     * the later children never run.
     */
    public sealed interface BoundaryResult permits BoundaryResult.Pass, BoundaryResult.Fail {

        /**
         * The boundary passed; {@code value} is the delegate-published
         * checked value (a passing boundary never copies or converts —
         * except the pinned missing→null mappings the delegate owns).
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
    // Body-runner seam and the loop-binding load-resolution rule (D1/D6)
    // =========================================================================

    @FunctionalInterface
    public interface BodyRunner {

        /**
         * Executes the loop body for one iteration.
         *
         */
        void runBody(int iterationIndex, long bindingGeneration, Value.String yielded,
                     LoopLoadResolver loopLoad);
    }

    @FunctionalInterface
    public interface LoopLoadResolver {

        /**
         * Resolves the effective generation of one loop-binding load
         * during the current iteration.
         *
         */
        long effectiveGeneration(long carriedGeneration);
    }

    // =========================================================================
    // TABLE_NEW
    // =========================================================================

    /**
     * Executes one validated {@code TABLE_NEW} op: allocates a fresh
     * table and {@link SemanticTable#put}s every entry in source order
     * (D4 order contract — duplicate literal keys keep the first source
     * position and the last value; the write/delete address chains are
     * E5's and never appear here). {@code TABLE_NEW} has no failure of
     * its own ({@code NO_DEAL_FAILURE}): a prior-step failure means the
     * executor is never called, and no partial table exists.
     *
     */
    public static SemanticTable<Value> executeTableNew(SemanticOp op,
                                                       Map<ValueId, Value> priorValues) {
        requireOp(op, SemanticOpKind.TABLE_NEW, FailurePolicyId.NO_DEAL_FAILURE);
        KindPayload.TableNewPayload payload = (KindPayload.TableNewPayload) op.payload();
        SemanticTable<Value> table = new SemanticTable<>();
        for (KindPayload.TableEntry entry : payload.entries()) {
            table.put(entry.key(), resolve(priorValues, entry.value()));
        }
        return table;
    }

    // =========================================================================
    // ARRAY_NEW
    // =========================================================================

    /**
     * Executes one validated {@code ARRAY_NEW} op with the pinned
     * orchestration (D3): after all element prior steps completed (every
     * element value is resolved before any boundary runs), the
     * element-boundary children run strictly in payload order through the
     * {@link BoundaryCheckRunner} delegate. The first failing child fails
     * the op with that child's failure — no allocation, no partial
     * instance, the later children never run, and the earlier children's
     * passes remain observable in the delegate. Only after every child
     * passes does the fresh array allocate; its elements are the
     * delegate-published checked values in source order. No boundary
     * projection lives here: the delegate (E4's
     * {@link BoundaryExecutor} in production) owns every check.
     *
     */
    public static Outcome<SemanticArray<Value>> executeArrayNew(
            SemanticOp op, Map<ValueId, Value> priorValues,
            Map<OpId, SemanticOp> boundaryOps, BoundaryCheckRunner checkRunner) {
        requireOp(op, SemanticOpKind.ARRAY_NEW, FailurePolicyId.NO_DEAL_FAILURE);
        Objects.requireNonNull(boundaryOps, "boundaryOps must not be null");
        Objects.requireNonNull(checkRunner, "checkRunner must not be null");
        KindPayload.ArrayNewPayload payload = (KindPayload.ArrayNewPayload) op.payload();
        List<OpId> boundaryIds = payload.elementBoundaryOpIds();
        if (payload.values().size() != boundaryIds.size()) {
            throw new Defect("ARRAY_NEW " + op.opId() + " has " + payload.values().size()
                + " element values but " + boundaryIds.size()
                + " element-boundary children: the validator pins exactly one boundary per "
                + "element — a count mismatch is a producer defect, never executed");
        }

        // All element prior steps complete before any boundary child runs.
        List<Value> elements = new ArrayList<>(payload.values().size());
        for (ValueId elementId : payload.values()) {
            elements.add(resolve(priorValues, elementId));
        }

        List<Value> checked = new ArrayList<>(elements.size());
        for (int i = 0; i < boundaryIds.size(); i++) {
            SemanticOp child = requireBoundaryChild(boundaryOps, boundaryIds.get(i), op,
                BoundaryKind.ARRAY_LITERAL_ELEMENT);
            BoundaryResult result = checkRunner.run(
                (KindPayload.BoundaryPayload) child.payload(), elements.get(i));
            if (result instanceof BoundaryResult.Pass pass) {
                checked.add(pass.value());
                continue;
            }
            // The first failing child fails the op: no allocation, no
            // partial instance, later children never run.
            BoundaryResult.Fail fail = (BoundaryResult.Fail) result;
            return new Outcome.Failure<SemanticArray<Value>>(
                new OpFailure(fail.failure(), op.origin()));
        }
        // Allocation only after every element child passed.
        return new Outcome.Success<SemanticArray<Value>>(SemanticArray.of(checked));
    }

    // =========================================================================
    // MEMBER_READ
    // =========================================================================

    public static Outcome<Value> executeMemberRead(SemanticOp op,
                                                   Map<ValueId, Value> priorValues,
                                                   SemanticOp contextualChild,
                                                   BoundaryCheckRunner checkRunner) {
        requireOp(op, SemanticOpKind.MEMBER_READ, FailurePolicyId.NO_DEAL_FAILURE);
        Objects.requireNonNull(contextualChild, "contextualChild must not be null");
        Objects.requireNonNull(checkRunner, "checkRunner must not be null");
        KindPayload.MemberReadPayload payload = (KindPayload.MemberReadPayload) op.payload();

        Value receiver = resolve(priorValues, payload.table());
        if (!(receiver instanceof Value.Table table)) {
            throw new Defect("MEMBER_READ " + op.opId() + " receiver " + payload.table()
                + " resolves to " + receiver.actualKind()
                + ": the pinned receiver is a table — a producer defect, never a projection");
        }

        // The missing-aware read completes before the child starts; the
        // constant key is never evaluated (no key prior step exists).
        Value readOutcome = switch (table.table().get(payload.key())) {
            case SemanticTable.Lookup.Present<Value> present -> present.value();
            case SemanticTable.Lookup.Missing<Value> ignored -> Value.Missing.INSTANCE;
        };

        requireChildOf(contextualChild, op, BoundaryKind.CONTEXTUAL_TABLE_READ);
        BoundaryResult result = checkRunner.run(
            (KindPayload.BoundaryPayload) contextualChild.payload(), readOutcome);
        return switch (result) {
            case BoundaryResult.Pass pass -> new Outcome.Success<Value>(pass.value());
            case BoundaryResult.Fail fail -> new Outcome.Failure<Value>(
                new OpFailure(fail.failure(), op.origin()));
        };
    }

    // =========================================================================
    // ARRAY_LENGTH
    // =========================================================================

    /**
     * Executes one validated {@code ARRAY_LENGTH} op: the receiver array
     * resolves exactly once (never re-evaluated) and the signed32 element
     * count is published with the pinned {@code INT32_RESULT} range check
     * — a count outside {@code [-2147483648, 2147483647]} fails E8004
     * {@code int out of safe range} via the registry row (defensive: the
     * semantic model's counts are bounded, so the model cannot produce an
     * out-of-range count; the check exists because the closed policy pins
     * it).
     *
     */
    public static Outcome<Integer> executeArrayLength(SemanticOp op,
                                                      Map<ValueId, Value> priorValues) {
        requireOp(op, SemanticOpKind.ARRAY_LENGTH, FailurePolicyId.INT32_RESULT);
        KindPayload.ArrayLengthPayload payload = (KindPayload.ArrayLengthPayload) op.payload();

        Value receiver = resolve(priorValues, payload.arrayValue());
        if (!(receiver instanceof Value.Array array)) {
            throw new Defect("ARRAY_LENGTH " + op.opId() + " receiver " + payload.arrayValue()
                + " resolves to " + receiver.actualKind()
                + ": the pinned receiver is an array — a producer defect, never a projection");
        }

        // The count is promoted so the pinned range check is expressible;
        // the bounded semantic model never produces an out-of-range count.
        long count = array.array().size();
        if (count < -2147483648L || count > 2147483647L) {
            BoundaryFailure failure = FailureContractRegistry.render(FailureArmId.INT32_RANGE,
                new LinkedHashMap<>(), null, null, null);
            return new Outcome.Failure<Integer>(new OpFailure(failure, op.origin()));
        }
        return new Outcome.Success<Integer>((int) count);
    }

    // =========================================================================
    // STRING_CONCAT
    // =========================================================================

    /**
     * Executes one validated {@code STRING_CONCAT} op: the fragments
     * resolve strictly in payload (source) order and their scalar
     * sequences concatenate through {@link UnicodeScalars#concat} —
     * representation-agnostic, a surrogate pair stays one scalar, and no
     * folding or reordering ever occurs. An {@code Invalid} operand
     * reaching {@code STRING_CONCAT} inside the closed layer is a
     * producer defect (typed boundaries reject invalid strings first),
     * never a second projection — the executor fails closed instead of
     * projecting. A non-string fragment is likewise a producer defect.
     *
     */
    public static Value.String executeStringConcat(SemanticOp op,
                                                   Map<ValueId, Value> priorValues) {
        requireOp(op, SemanticOpKind.STRING_CONCAT, FailurePolicyId.NO_DEAL_FAILURE);
        KindPayload.StringConcatPayload payload = (KindPayload.StringConcatPayload) op.payload();

        List<UnicodeScalars.ScalarString> fragments =
            new ArrayList<>(payload.fragments().size());
        for (ValueId fragmentId : payload.fragments()) {
            Value fragment = resolve(priorValues, fragmentId);
            if (!(fragment instanceof Value.String stringFragment)) {
                throw new Defect("STRING_CONCAT " + op.opId() + " fragment " + fragmentId
                    + " resolves to " + fragment.actualKind()
                    + ": the pinned fragments are string values — a producer defect, "
                    + "never a projection");
            }
            fragments.add(stringFragment.scalar());
        }

        UnicodeScalars.ScalarString concatenated = UnicodeScalars.concat(fragments);
        if (concatenated instanceof UnicodeScalars.Invalid) {
            throw new Defect("STRING_CONCAT " + op.opId()
                + " concatenated an Invalid operand: typed boundaries reject invalid "
                + "strings first, so an Invalid scalar sequence inside the closed layer is "
                + "a producer defect — never a second projection");
        }
        return new Value.String((UnicodeScalars.Valid) concatenated);
    }

    // =========================================================================
    // FOR_EACH(STRING_SCALARS)
    // =========================================================================

    /**
     * Executes one validated {@code FOR_EACH(STRING_SCALARS)} op (D5/D6,
     * pinned): the iterable resolves exactly once and the complete scalar
     * sequence validates before the first binding — the op's own
     * {@code TYPE_DESCRIPTOR} terminal check, never a {@code BOUNDARY}
     * child. An {@code Invalid} iterable fails the op with the E8001
     * projection {@code expected string, got invalid Unicode scalar
     * encoding} (expected {@code string}, actual kind
     * {@code invalid-unicode}, instantiated from the registry's pinned
     * {@code TYPE_DESCRIPTOR} row) at the op's origin, and no body
     * execution occurs. A {@code Valid} iterable yields one
     * single-scalar string per iteration in scalar order to the
     * {@link BodyRunner}, each bound to a fresh generation of the loop
     * binding (initial + iteration index); an empty string yields zero
     * iterations. Loads of the loop binding inside the body carry the
     * payload's initial generation and the body-runner resolves the
     * effective generation as {@code initial + current iteration index}
     * through the supplied {@link LoopLoadResolver} (the D1
     * loop-binding load-resolution rule) — the payload is never rewritten
     * per iteration.
     *
     */
    public static Outcome<Integer> executeForEach(SemanticOp op,
                                                  Map<ValueId, Value> priorValues,
                                                  BodyRunner bodyRunner) {
        requireOp(op, SemanticOpKind.FOR_EACH, FailurePolicyId.TYPE_DESCRIPTOR);
        Objects.requireNonNull(bodyRunner, "bodyRunner must not be null");
        KindPayload.ForEachPayload payload = (KindPayload.ForEachPayload) op.payload();
        if (payload.mode() != IterationMode.STRING_SCALARS) {
            throw new Defect("FOR_EACH " + op.opId() + " carries iteration mode "
                + payload.mode() + ": FOR_EACH(ARRAY_VALUES) is E5's (ISSUE-0234) and is "
                + "not executable here — a producer defect, never executed");
        }

        Value iterable = resolve(priorValues, payload.iterable());
        if (!(iterable instanceof Value.String stringIterable)) {
            throw new Defect("FOR_EACH(STRING_SCALARS) " + op.opId() + " iterable "
                + payload.iterable() + " resolves to " + iterable.actualKind()
                + ": the pinned iterable is a string value — a producer defect, "
                + "never a projection");
        }

        // The complete scalar sequence validates before the first binding:
        // the op's own TYPE_DESCRIPTOR terminal check, never a BOUNDARY
        // child. Invalid → the pinned E8001 projection at the op origin,
        // zero body steps.
        UnicodeScalars.ScalarString scalar = stringIterable.scalar();
        if (scalar instanceof UnicodeScalars.Invalid) {
            // The invalid-Unicode TYPE_DESCRIPTOR arm's own render (the
            // pinned text, expected {@code string}, actual
            // {@code invalid-unicode}), never a row instantiation.
            BoundaryFailure failure = FailureContractRegistry.render(
                FailureArmId.TYPED_BOUNDARY_INVALID_UNICODE, new LinkedHashMap<>(),
                "string", "invalid-unicode", null);
            return new Outcome.Failure<Integer>(new OpFailure(failure, op.origin()));
        }

        List<Integer> codePoints = UnicodeScalars.scalars((UnicodeScalars.Valid) scalar);
        long initialGeneration = payload.generation();
        for (int i = 0; i < codePoints.size(); i++) {
            long bindingGeneration = initialGeneration + i;
            LoopLoadResolver loopLoad = carriedGeneration -> {
                if (carriedGeneration != initialGeneration) {
                    throw new Defect("a loop-binding load inside FOR_EACH " + op.opId()
                        + "'s body carries generation " + carriedGeneration
                        + ": the pinned load carries the payload's initial generation "
                        + initialGeneration + " — any other carried generation is not a "
                        + "load of this loop binding and is a producer defect");
                }
                return bindingGeneration;
            };
            UnicodeScalars.Valid yielded = new UnicodeScalars.Valid(
                UnicodeScalars.scalarString(codePoints.get(i)));
            bodyRunner.runBody(i, bindingGeneration, new Value.String(yielded), loopLoad);
        }
        return new Outcome.Success<Integer>(codePoints.size());
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
    private static void requireOp(SemanticOp op, SemanticOpKind kind, FailurePolicyId policy) {
        String defect = ExecutorGuards.opShapeDefect(op, kind, policy);
        if (defect != null) {
            throw new Defect(defect);
        }
    }

    /**
     * Resolves one payload-referenced prior step. Prior steps complete
     * before operation START in the validated machine, so an unresolved
     * reference is a producer defect (fail closed, never a projection and
     * never a silent skip).
     */
    private static Value resolve(Map<ValueId, Value> priorValues, ValueId id) {
        Objects.requireNonNull(priorValues, "priorValues must not be null");
        Value value = priorValues.get(id);
        if (value == null) {
            throw new Defect("prior-step value " + id + " is not resolved in the value "
                + "lookup: every payload-referenced prior step resolves before the op's "
                + "behavior — an unresolved reference is a producer defect, never "
                + "executed");
        }
        return value;
    }

    /**
     * Resolves one named boundary child and requires the validator-pinned
     * shape fail closed: a {@code BOUNDARY} op of the given boundary kind
     * whose origin {@code parentOpId} is the owning op (the
     * {@code ARRAY_NEW}/{@code MEMBER_READ} parentage pin).
     */
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
}
