package deal.semantic.ir;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.Objects;

/**
 * The single op-level execution form of the class-construction operations
 * of {@code deal.semantic-ir/1} (class-construction-jsonable-operations
 * K-D4/K-D11; ISSUE-0512; parent D16 and the normative construction
 * section, the closed {@code CLASS_NEW}/{@code CLASS_DEFAULT} operation
 * rows, and the {@code CLASS_CONSTRUCTION} E8007 policy row): a static,
 * pure, deterministic, stateless executor over the closed value view —
 * extended with the class variant
 * {@code Class {classId, fields: [Present(Value) | Missing]}} preserving
 * missing versus null — plus the value lookup for payload-referenced
 * provided-field prior steps. The sequencing item 2 child (ISSUE-0512)
 * introduced the executor with the
 * {@code CLASS_DEFAULT}/{@code CLASS_NEW(LOCAL)} surface and the pinned
 * delegate seams; this child (sequencing item 3, ISSUE-0513) completes
 * the field-operation surface — {@code FIELD_READ}/{@code FIELD_WRITE}/
 * {@code FIELD_DELETE} with the nominal receiver boundary first and the
 * presence-aware read/commit/idempotent-delete semantics (K-D6) and
 * {@code HAS_FIELD} with the presence boolean (K-D7). The sequencing
 * item 4 child (ISSUE-0514) completes the cross-unit default filling —
 * {@code CLASS_FACTORY} with the K-D5 execution contract (the
 * {@code CLASS_NEW(SHARED_FACTORY)} trigger, the skip-provided rule,
 * the untagged internal transfer, the executed cross-unit
 * {@code parentOpId} pin) and {@code CLASS_NEW(SHARED_FACTORY)} with
 * the K-D4 transfer order, the overlay reorder pin, the
 * {@code CLASS_DEFAULT_FIELD} extraction rule, and the tag-last
 * publication. The {@code JSON_*} surfaces complete the assembled
 * executor in the later children (sequencing items 5-6).
 *
 * <p><b>Interpretation surface.</b> The executor interprets only
 * validated op shapes ({@link SemanticOp} records whose kind/payload
 * pairing the {@code SemanticOp} constructor has already enforced) and
 * the closed {@link Value} view — never AST nodes, never target
 * representations, never host code, never identity-keyed checker state.
 * A shape outside the pinned contracts — a wrong op kind, a non-pinned
 * failure policy, a non-{@code LOCAL} default owner, an unresolvable
 * lookup value, a wrong-kind value, a boundary child of the wrong kind
 * or parentage, a child-count mismatch, or an input-wiring mismatch —
 * fails closed as a producer {@link Defect}, never as a DEAL projection
 * and never as a crash (the same fail-closed discipline as
 * {@link ContainerOpsExecutor} and {@link BoundaryExecutor}).</p>
 *
 * <p><b>Value lookup.</b> Every payload-referenced provided-field prior
 * step resolves through the caller-supplied {@code Map<ValueId, Value>}
 * in literal order before the op's behavior runs (K-D4 step 1). A
 * referenced prior step the lookup does not resolve is a producer defect
 * (prior steps complete before operation START in the validated machine,
 * so the oracle's lookup always resolves). A provided value resolving to
 * the internal {@link Value.Missing} is a producer defect — a provided
 * field always carries a present value, and the missing state exists
 * only for absent slots of the constructed instance.</p>
 *
 * <p><b>Layout resolution.</b> The payload carries the layout; the
 * caller-supplied {@code Map<ClassId, ClassLayout>} is the
 * layout-resolution context (K-D11: the unit's {@code classLayouts} plus
 * the project interface index). The executor resolves the payload's
 * {@code classId} against the context and requires the resolved layout
 * to be exactly the payload's layout — an unresolvable or mismatched
 * layout is a producer defect (the payload is never interpreted against
 * a foreign layout).</p>
 *
 * <p><b>Boundary orchestration (K-D4 step 5, pinned).</b> The executor
 * creates no boundary projection: every field boundary is driven through
 * the {@link BoundaryCheckRunner} delegate seam
 * ({@code (BoundaryPayload, Value) → Pass | Fail}) strictly in payload
 * (declaration) order; the first failing child fails the op with that
 * child's failure and no instance is published (the tag never runs). The
 * delegate is E4's {@link BoundaryExecutor} in production; this epic's
 * unit tests use pass-through/fail-first fixtures. The pinned child
 * wiring is checked fail closed before each run: kind
 * {@code CLASS_LITERAL_FIELD}/{@code CLASS_DEFAULT_FIELD} matching the
 * payload entry, {@code parentOpId} = the {@code CLASS_NEW} op, the
 * field's declared descriptor, the descriptor-kind policy
 * ({@code TYPE_DESCRIPTOR} for non-function descriptors,
 * {@code FUNCTION_SIGNATURE} for function descriptors), and the input —
 * the field's {@code valueOpId} for provided fields, the field's
 * {@code CLASS_DEFAULT} op result {@code ValueId} for defaulted fields
 * (K-D4 input wiring).</p>
 *
 * <p><b>Default application (K-D4 step 2, pinned).</b>
 * {@code CLASS_DEFAULT} children execute in declaration order through
 * the {@link BodyRunner} seam — the block runs exactly once per
 * triggering construction attempt and the callback returns the produced
 * default value; the op carries no boundary of its own and its policy is
 * {@code NO_DEAL_FAILURE}. A child whose field is provided is skipped —
 * a provided field's default never runs. {@code CLASS_DEFAULT} ops are
 * detached structural ops (K-D12: their nesting owner is recorded by the
 * {@code CLASS_NEW} payload's {@code classDefaultOpIds}, not by a static
 * {@code parentOpId}), so the executor checks payload membership and
 * {@code classId} coherence instead of parentage. Per-construction
 * freshness is produced by block re-execution: each triggering attempt
 * invokes the body runner again, so a literal-typed default allocates
 * freshly per attempt (the oracle's machine re-executes the block; this
 * executor pins the per-trigger invocation protocol).</p>
 *
 * <p><b>The closed K-D4 order of {@code CLASS_NEW(LOCAL)}.</b>
 * (1) provided values resolve in literal order; (2) default application
 * for omitted required-present fields with declared defaults in
 * declaration order, skipping provided fields; (3) extra-key rejection
 * first in provided-source order — the first provided name not in the
 * layout fails {@code CLASS_CONSTRUCTION} with the registry row's pinned
 * E8007 {@code extra field '{field}' in class '{classId}'} at the op
 * origin, after default application and before any provided-field
 * application or field validation; (4) provided-field application in
 * declaration order (the overlay onto the default-filled instance — the
 * {@code jvm-xmod-class-construction-defaults} reorder pin); (5) field
 * validation in declaration order through the
 * {@link BoundaryCheckRunner} seam per {@code fieldBoundaries} entry;
 * (6) the fresh instance is tagged with {@code classId} and SUCCESS
 * publishes it. A failure at any step publishes no partial instance (the
 * tag never runs); completed children's effects remain observable.
 * {@code CLASS_NEW} runs zero return boundaries and the executor never
 * drives a {@code FUNCTION_RETURN} child.</p>
 *
 * <p><b>Failure rows.</b> The E8007 projection is instantiated from the
 * pinned {@link FailureContractRegistry} {@code CLASS_CONSTRUCTION} row
 * through {@link BoundaryFailure#fromRow} with the row's metadata
 * ({@code field}, {@code classId}) — the executor never selects message
 * text, and consumers never select messages. {@link OpFailure} pairs the
 * structured projection with the executed op's origin (the operation
 * origin of the closed table's rule).</p>
 *
 * <p><b>Purity and bounds.</b> No mutation of the unit, no randomness,
 * no I/O, no host code, no retry, no {@code deal.types} dependency; the
 * executor is linear in the provided-field count, the default count, the
 * boundary count, and the layout's field count, and delegates
 * descriptor-depth work to the boundary delegate (this component
 * recurses over no descriptor). Fresh instances carry fresh identities
 * (the model's allocation rule); repeated executions with equal inputs
 * produce equal results.</p>
 */
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
     * input-wiring mismatch, a {@code CLASS_DEFAULT} child of the wrong
     * kind/policy/class, or a duplicate default child. Internal control
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

    /**
     * The closed value view the executor consumes and produces over the
     * semantic value model: the {@link ContainerOpsExecutor} view
     * extended with the class variant (K-D4: the construction result)
     * — language null, boolean, signed32 int, IEEE-754 number, string
     * (carrying the closed {@link UnicodeScalars.ScalarString}
     * classification), table ({@link SemanticTable}), array
     * ({@link SemanticArray}), class ({@link Value.Class}), function
     * (carrying the closed signature for the descriptor-kind rule's
     * {@code FUNCTION_SIGNATURE} cells), and the internal
     * {@code missing} (the schema's {@link ActualKind#MISSING}, never a
     * Java null reference). Async-operation values are never produced or
     * consumed by construction, so the view does not carry them.
     *
     * <p>A class value carries the canonical {@code classId} tag and its
     * fields in declaration order as {@link FieldState}
     * {@code Present(Value) | Missing} — present null (the explicit
     * {@link Null} variant) is {@code Present} and is distinguishable
     * from {@code Missing}, so the three presence states (missing,
     * present null, present value) are preserved exactly (K-D6's
     * storage discipline). A {@code Present} field never carries the
     * internal {@code Missing} view (fail closed).</p>
     *
     * <p>Every variant renders its canonical actual kind
     * ({@link ActualKind}): a {@link String} view renders
     * {@code STRING} for a {@code Valid} scalar sequence and
     * {@code INVALID_UNICODE} for an {@code Invalid} one; a
     * {@link Class} view renders {@code CLASS} with the canonical
     * {@code class:<ClassId>} atom text. Null references never appear:
     * language null is the explicit {@link Null} variant, and absent
     * fields are the explicit {@link Missing} state.</p>
     */
    public sealed interface Value
        permits Value.Null, Value.Bool, Value.Int, Value.Number, Value.String,
                Value.Table, Value.Array, Value.Class, Value.Function, Value.Missing {

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
     * One op-level failure: the structured registry-row projection
     * (built with {@link BoundaryFailure#fromRow}) paired with the
     * executed op's origin — the operation origin of the closed table's
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

    /**
     * The closed terminal of one executor call:
     * {@code Success(value) | Failure(OpFailure)}. A {@code Failure} is
     * exactly one pinned projection — never a partial value and never a
     * retry. A failed {@code CLASS_NEW} publishes no partial instance:
     * the {@code Value.Class} exists only on the success path, so the
     * tag never runs on a failure (K-D4 step 6).
     *
     * @param <V> the success value type of the op (the produced default
     *            value or the fresh tagged instance)
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
    // Boundary-check delegate seam (K-D11)
    // =========================================================================

    /**
     * The boundary-check delegate seam (K-D11): exactly
     * {@code (BoundaryPayload, Value) → Pass | Fail} — the closed value
     * view of the checked field input in, the published checked value or
     * the boundary's own failure out. E4's {@link BoundaryExecutor} is
     * the production delegate; this epic's unit tests use
     * pass-through/fail-first fixtures. The delegate owns every boundary
     * projection (the descriptor-kind rule's E8001/E8010); the executor
     * pins only the orchestration (declaration order, the first failure
     * fails the op, tag only after all validation).
     */
    @FunctionalInterface
    public interface BoundaryCheckRunner {

        /**
         * Runs one field-boundary check of the construction.
         *
         * @param boundary the child's pinned {@code BOUNDARY} payload
         *                 (kind, descriptor, input, realization)
         * @param input    the closed value view the child checks — the
         *                 provided value ({@code CLASS_LITERAL_FIELD})
         *                 or the default block's produced value
         *                 ({@code CLASS_DEFAULT_FIELD})
         * @return {@code Pass} with the published checked value, or
         *         {@code Fail} with the boundary's own failure
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
    // Body-runner seam (K-D11)
    // =========================================================================

    /**
     * The body-runner delegate seam (K-D11): executes one
     * {@code CLASS_DEFAULT} default block (the detached per-construction
     * block of the op's payload) exactly once for the current triggering
     * construction attempt and returns the block's produced default
     * value — the {@code CLASS_DEFAULT} op's result. E5's block machinery
     * is the production delegate; this epic's unit tests use scripted
     * fixtures recording every invocation (the invocation log proves the
     * declaration order and the skip-provided rule). Per-construction
     * freshness is realized by re-invocation: the executor calls the
     * runner again on every triggering attempt, so literal-typed defaults
     * allocate freshly per attempt. A default-block failure propagates as
     * the callback's own throw (the oracle's machine owns it), never as a
     * synthesized projection here.
     */
    @FunctionalInterface
    public interface BodyRunner {

        /**
         * Executes one {@code CLASS_DEFAULT} default block.
         *
         * @param defaultOp the validated {@code CLASS_DEFAULT} op whose
         *                  {@link KindPayload.ClassDefaultPayload#defaultBlock()}
         *                  is the block to run; non-null
         * @return the block's produced default value (the op's result);
         *         never the internal {@link Value.Missing} (a default
         *         block always produces a present value — fail closed)
         */
        Value runDefault(SemanticOp defaultOp);
    }

    // =========================================================================
    // CLASS_DEFAULT
    // =========================================================================

    /**
     * Executes one validated {@code CLASS_DEFAULT} op (K-D3/K-D4 step 2):
     * the default block runs exactly once for the current triggering
     * construction attempt through the {@link BodyRunner} seam and the
     * op publishes the produced default value. The op carries no boundary
     * of its own and its policy is {@code NO_DEAL_FAILURE}; it exists
     * only as a child of a triggering construction ({@code CLASS_NEW} or
     * {@code CLASS_FACTORY}), and its detached block is re-run on every
     * triggering attempt — per-construction freshness is produced by
     * block re-execution, never by deep copies.
     *
     * @param op         the validated {@code CLASS_DEFAULT} op; non-null
     * @param bodyRunner the default-block callback; non-null
     * @return {@code Success} with the block's produced default value
     * @throws Defect               if the op is not a
     *                              {@code CLASS_DEFAULT} carrying
     *                              {@code NO_DEAL_FAILURE}, or if the
     *                              produced value is the internal
     *                              {@link Value.Missing} (a default block
     *                              always produces a present value)
     * @throws NullPointerException if any argument is null
     */
    public static Outcome<Value> executeClassDefault(SemanticOp op, BodyRunner bodyRunner) {
        requireOp(op, SemanticOpKind.CLASS_DEFAULT, FailurePolicyId.NO_DEAL_FAILURE);
        Objects.requireNonNull(bodyRunner, "bodyRunner must not be null");
        Value produced = bodyRunner.runDefault(op);
        if (produced instanceof Value.Missing) {
            throw new Defect("CLASS_DEFAULT " + op.opId() + " produced the internal Missing "
                + "view: a default block always produces a present value (language null is "
                + "the explicit Null variant) — a wrong-kind value is a producer defect, "
                + "never executed");
        }
        return new Outcome.Success<Value>(produced);
    }

    // =========================================================================
    // CLASS_NEW(LOCAL)
    // =========================================================================

    /**
     * Executes one validated {@code CLASS_NEW} op with
     * {@code defaultOwner: LOCAL} in the closed K-D4/D16 order:
     *
     * <ol>
     *   <li>provided values resolve from the lookup in literal order
     *       ({@code providedFields} payload order);</li>
     *   <li>default application for omitted required-present fields with
     *       declared defaults — the {@code classDefaultOpIds}
     *       {@code CLASS_DEFAULT} children in declaration order through
     *       the {@link BodyRunner} seam, skipping any child whose field
     *       is provided (a provided field's default never runs);</li>
     *   <li>extra-key rejection first in provided-source order: the
     *       first provided name not in the layout fails
     *       {@code CLASS_CONSTRUCTION} — E8007
     *       {@code extra field '{field}' in class '{classId}'} at the op
     *       origin — after default application and before any
     *       provided-field application or field validation (the default
     *       side effects have completed, no provided field is applied,
     *       and no boundary runs after the scan fails);</li>
     *   <li>provided-field application in declaration order (the overlay
     *       onto the default-filled instance — the
     *       {@code jvm-xmod-class-construction-defaults} reorder pin;
     *       duplicate provided names keep the last provided value, the
     *       checker's literal-map rule);</li>
     *   <li>field validation in declaration order through the
     *       {@link BoundaryCheckRunner} seam per {@code fieldBoundaries}
     *       entry ({@code CLASS_LITERAL_FIELD} for provided fields,
     *       {@code CLASS_DEFAULT_FIELD} for defaulted fields; the pinned
     *       input wiring, declared descriptor, descriptor-kind policy,
     *       and {@code parentOpId} are checked fail closed before each
     *       run); omitted optional fields get no boundary and stay
     *       missing;</li>
     *   <li>the fresh instance is tagged with {@code classId}
     *       ({@code class:<ClassId>} canonical identity) and SUCCESS
     *       publishes it.</li>
     * </ol>
     *
     * A failure at any step publishes no partial instance (the
     * {@link Value.Class} exists only on the success path, so the tag
     * never runs); completed children's effects remain observable.
     * {@code CLASS_NEW} runs zero return boundaries — the executor never
     * drives a {@code FUNCTION_RETURN} child.
     *
     * @param op          the validated {@code CLASS_NEW} op carrying
     *                    {@code CLASS_CONSTRUCTION}; non-null
     * @param priorValues the resolved provided-field prior-step values;
     *                    non-null, no null entries
     * @param defaultOps  the unit's {@code CLASS_DEFAULT} ops by
     *                    {@link OpId}; every id of
     *                    {@code classDefaultOpIds} must resolve to a
     *                    {@code CLASS_DEFAULT} op of this classId
     *                    carrying {@code NO_DEAL_FAILURE}; non-null
     * @param boundaryOps the unit's boundary ops by {@link OpId}; every
     *                    {@code fieldBoundaries} id must resolve to a
     *                    {@code BOUNDARY} op parented to this op; non-null
     * @param layouts     the layout-resolution context
     *                    {@code ClassId → ClassLayout} (K-D11); the
     *                    payload's classId must resolve to exactly the
     *                    payload's layout; non-null
     * @param checkRunner the boundary-check delegate; non-null
     * @param bodyRunner  the default-block callback; non-null
     * @return {@code Success} with the fresh tagged instance after every
     *         field boundary passed, or {@code Failure} with the E8007
     *         extra-key projection or the first failing boundary's
     *         failure
     * @throws Defect               if the op is not a {@code CLASS_NEW}
     *                              carrying {@code CLASS_CONSTRUCTION},
     *                              if its default owner is not
     *                              {@code LOCAL} or its factory ref is
     *                              non-null, if the layout resolution
     *                              fails or mismatches, if a provided
     *                              value does not resolve or resolves to
     *                              the internal {@code Missing}, if a
     *                              default child or boundary child
     *                              violates the pinned shape, if the
     *                              field-boundary entries mismatch the
     *                              pinned declaration-order
     *                              count/kinds, or if an input-wiring
     *                              mismatch appears
     * @throws NullPointerException if any argument is null
     */
    public static Outcome<Value> executeClassNewLocal(
            SemanticOp op,
            Map<ValueId, Value> priorValues,
            Map<OpId, SemanticOp> defaultOps,
            Map<OpId, SemanticOp> boundaryOps,
            Map<ClassId, ClassLayout> layouts,
            BoundaryCheckRunner checkRunner,
            BodyRunner bodyRunner) {
        requireOp(op, SemanticOpKind.CLASS_NEW, FailurePolicyId.CLASS_CONSTRUCTION);
        Objects.requireNonNull(priorValues, "priorValues must not be null");
        Objects.requireNonNull(defaultOps, "defaultOps must not be null");
        Objects.requireNonNull(boundaryOps, "boundaryOps must not be null");
        Objects.requireNonNull(layouts, "layouts must not be null");
        Objects.requireNonNull(checkRunner, "checkRunner must not be null");
        Objects.requireNonNull(bodyRunner, "bodyRunner must not be null");
        KindPayload.ClassNewPayload payload = (KindPayload.ClassNewPayload) op.payload();
        // This child's surface: LOCAL execution only. SHARED_FACTORY
        // execution is executeClassNewSharedFactory's; RETAINED_ABI is
        // E10's — both fail closed here, never silently executed as
        // LOCAL.
        if (payload.defaultOwner() != DefaultOwner.LOCAL) {
            throw new Defect("CLASS_NEW " + op.opId() + " carries defaultOwner "
                + payload.defaultOwner() + ": this executor surface is LOCAL "
                + "execution — SHARED_FACTORY transfer is "
                + "executeClassNewSharedFactory's and RETAINED_ABI transfer is E10's; "
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

        // Layout resolution (K-D11): the payload is never interpreted
        // against a foreign layout.
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

        // K-D4 step 1: provided values resolve in literal order.
        LinkedHashMap<String, Value> providedValues = new LinkedHashMap<>();
        LinkedHashMap<String, ValueId> providedValueIds = new LinkedHashMap<>();
        for (KindPayload.ProvidedField field : payload.providedFields()) {
            Value value = resolve(priorValues, field.valueOpId());
            if (value instanceof Value.Missing) {
                throw new Defect("CLASS_NEW " + op.opId() + " provided field '" + field.name()
                    + "' (" + field.valueOpId() + ") resolves to the internal Missing view: "
                    + "a provided field always carries a present value (language null is "
                    + "the explicit Null variant) — a wrong-kind value is a producer "
                    + "defect, never executed");
            }
            // Duplicate provided names keep the last value (the checker's
            // class-literal map rule); every entry still resolves in
            // literal order so its effects complete.
            providedValues.put(field.name(), value);
            providedValueIds.put(field.name(), field.valueOpId());
        }

        // K-D4 step 2: default application in declaration order for omitted
        // required-present fields — skipping any child whose field is
        // provided (a provided field's default never runs).
        LinkedHashMap<String, Value> defaultValues = new LinkedHashMap<>();
        LinkedHashMap<String, SemanticOp> defaultOpsByField = new LinkedHashMap<>();
        for (OpId defaultOpId : payload.classDefaultOpIds()) {
            SemanticOp defaultOp = requireDefaultChild(defaultOps, defaultOpId,
                payload.classId(), op);
            KindPayload.ClassDefaultPayload defaultPayload =
                (KindPayload.ClassDefaultPayload) defaultOp.payload();
            if (providedValues.containsKey(defaultPayload.field())) {
                // The skip-provided rule: a provided field's default
                // block is never executed by this construction attempt.
                continue;
            }
            ClassLayout.FieldLayout fieldLayout = fieldOf(layout, defaultPayload.field());
            if (fieldLayout == null) {
                throw new Defect("CLASS_NEW " + op.opId() + " names CLASS_DEFAULT child "
                    + defaultOpId + " for field '" + defaultPayload.field() + "' which is "
                    + "not a declared field of " + payload.classId() + ": a default child "
                    + "of an undeclared field is a producer defect, never executed");
            }
            if (!fieldLayout.required()) {
                throw new Defect("CLASS_NEW " + op.opId() + " names CLASS_DEFAULT child "
                    + defaultOpId + " for optional field '" + defaultPayload.field()
                    + "': default application runs for omitted required-present fields "
                    + "only — an optional field's default never runs at construction "
                    + "(the field stays missing), so a listed optional default is a "
                    + "producer defect, never executed");
            }
            if (defaultValues.containsKey(defaultPayload.field())) {
                throw new Defect("CLASS_NEW " + op.opId() + " names two CLASS_DEFAULT "
                    + "children for field '" + defaultPayload.field() + "': the pinned "
                    + "shape carries exactly one default child per defaulted field — a "
                    + "duplicate is a producer defect, never executed");
            }
            Outcome<Value> produced = executeClassDefault(defaultOp, bodyRunner);
            if (!(produced instanceof Outcome.Success<Value> success)) {
                // executeClassDefault cannot fail by itself: a default-block
                // failure propagates as the callback's own throw.
                throw new Defect("CLASS_NEW " + op.opId() + " CLASS_DEFAULT child "
                    + defaultOpId + " returned a failure terminal from the default "
                    + "execution: the default op's policy is NO_DEAL_FAILURE and only "
                    + "already-started child/operand failures may propagate — a "
                    + "producer defect, never executed");
            }
            defaultValues.put(defaultPayload.field(), success.value());
            defaultOpsByField.put(defaultPayload.field(), defaultOp);
        }

        // K-D4 step 3: extra-key rejection first in provided-source order —
        // after default application, before any provided-field application
        // or field validation.
        for (KindPayload.ProvidedField field : payload.providedFields()) {
            if (fieldOf(layout, field.name()) == null) {
                BoundaryFailure failure = BoundaryFailure.fromRow(
                    FailureContractRegistry.row(FailurePolicyId.CLASS_CONSTRUCTION), 0,
                    null, null,
                    metadataOf("field", field.name(), "classId", payload.classId().text()),
                    null);
                return new Outcome.Failure<Value>(new OpFailure(failure, op.origin()));
            }
        }

        // The pinned field-boundary coverage (K-D4 step 5 shape): exactly
        // one entry per provided field (CLASS_LITERAL_FIELD) and per
        // defaulted field (CLASS_DEFAULT_FIELD) in declaration order;
        // omitted optional fields get no boundary and stay missing. A
        // count, order, or kind deviation is a producer defect.
        List<java.lang.String> expectedBoundaryFields = new ArrayList<>();
        for (ClassLayout.FieldLayout fieldLayout : layout.fields()) {
            if (providedValues.containsKey(fieldLayout.name())
                    || defaultValues.containsKey(fieldLayout.name())) {
                expectedBoundaryFields.add(fieldLayout.name());
            }
        }
        List<KindPayload.FieldBoundary> boundaries = payload.fieldBoundaries();
        if (boundaries.size() != expectedBoundaryFields.size()) {
            throw new Defect("CLASS_NEW " + op.opId() + " carries " + boundaries.size()
                + " field-boundary entries for " + expectedBoundaryFields.size()
                + " present fields: the pinned shape carries exactly one boundary per "
                + "provided field and per omitted required-present defaulted field in "
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
            BoundaryKind expectedKind = providedValues.containsKey(entry.field())
                ? BoundaryKind.CLASS_LITERAL_FIELD : BoundaryKind.CLASS_DEFAULT_FIELD;
            if (entry.kind() != expectedKind) {
                throw new Defect("CLASS_NEW " + op.opId() + " field-boundary entry for '"
                    + entry.field() + "' carries kind " + entry.kind() + ": the pinned "
                    + "kind is " + expectedKind + " (CLASS_LITERAL_FIELD for provided "
                    + "values, CLASS_DEFAULT_FIELD for defaulted values) — a kind "
                    + "mismatch is a producer defect, never executed");
            }
        }

        // K-D4 step 4: provided-field application in declaration order (the
        // overlay onto the default-filled instance; duplicate names keep the
        // last provided value).
        LinkedHashMap<java.lang.String, Value> instanceFields = new LinkedHashMap<>();
        for (ClassLayout.FieldLayout fieldLayout : layout.fields()) {
            Value provided = providedValues.get(fieldLayout.name());
            Value defaultValue = defaultValues.get(fieldLayout.name());
            if (provided != null) {
                instanceFields.put(fieldLayout.name(), provided);
            } else if (defaultValue != null) {
                instanceFields.put(fieldLayout.name(), defaultValue);
            }
        }

        // K-D4 step 5: field validation in declaration order through the
        // BoundaryCheckRunner seam; the first failing child fails the op
        // and no instance is published.
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
                SemanticOp defaultOp = defaultOpsByField.get(entry.field());
                if (defaultOp == null) {
                    throw new Defect("CLASS_NEW " + op.opId() + " carries a "
                        + "CLASS_DEFAULT_FIELD boundary for field '" + entry.field()
                        + "' without a default child: the pinned kind names omitted "
                        + "required-present defaulted fields only — a producer defect, "
                        + "never executed");
                }
                if (!(defaultOp.result() instanceof ValueId resultId)) {
                    throw new Defect("CLASS_NEW " + op.opId() + " CLASS_DEFAULT child "
                        + defaultOp.opId() + " publishes a non-ValueId result "
                        + defaultOp.result() + ": the pinned CLASS_DEFAULT_FIELD input is "
                        + "the CLASS_DEFAULT op's result ValueId (K-D4 input wiring) — a "
                        + "producer defect, never executed");
                }
                if (!boundaryPayload.input().equals(resultId)) {
                    throw new Defect("CLASS_NEW " + op.opId() + " field boundary "
                        + child.opId() + " carries input " + boundaryPayload.input()
                        + ": the pinned CLASS_DEFAULT_FIELD input is the field's "
                        + "CLASS_DEFAULT op result " + resultId + " (K-D4 input wiring) — "
                        + "a mismatch is a producer defect, never executed");
                }
                input = defaultValues.get(entry.field());
            }
            BoundaryResult result = checkRunner.run(boundaryPayload, input);
            if (result instanceof BoundaryResult.Pass pass) {
                instanceFields.put(entry.field(), pass.value());
                continue;
            }
            // The first failing child fails the op: no instance, no tag,
            // and the later children never run.
            BoundaryResult.Fail fail = (BoundaryResult.Fail) result;
            return new Outcome.Failure<Value>(new OpFailure(fail.failure(), op.origin()));
        }

        // K-D4 step 6: tag the fresh instance with classId and SUCCESS
        // publishes it (declaration-order field states; omitted optionals
        // stay missing; present null stays present null).
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
    // CLASS_FACTORY (the SHARED_FACTORY transfer)
    // =========================================================================

    /**
     * Executes one validated {@code CLASS_FACTORY} op (K-D5): the
     * declaring module's default-application op, one per exported class,
     * triggered by a caller's {@code CLASS_NEW} with
     * {@code defaultOwner: SHARED_FACTORY} (this child's trigger; the
     * {@code JSON_FROM_CLASS} nested-class decode is the later JSON
     * child's trigger of the same closed trigger set). The factory runs
     * its {@code CLASS_DEFAULT} children in declaration order through
     * the {@link BodyRunner} seam — defaults evaluate per construction
     * in the declaring module's scope (the owner-side body runner
     * executes the owner unit's detached default blocks; mutable default
     * arrays/tables/nested classes allocate freshly per execution by
     * block re-execution) — skipping any child whose field the
     * triggering context records as provided (the caller's static
     * {@code providedFields}); fills an internal default-filled instance
     * (the untagged transfer — never published by the factory, never
     * passed through any boundary) and returns it as its result. The
     * factory runs zero boundaries and zero return boundaries; its
     * failure policy is {@code CLASS_CONSTRUCTION}; a failing default
     * child fails the triggering caller's op (the body runner's throw
     * propagates out of the caller's transfer — no caller instance is
     * published and the completed children's effects remain). The
     * executed {@code parentOpId} the trace records is the triggering
     * caller op's id (K-D12, cross-unit): {@code triggeringCaller} is
     * the caller op this execution parents to, and the factory never
     * constructs directly and never checks a return.
     *
     * <p>The factory's children are the detached structural
     * {@code CLASS_DEFAULT} ops named by the payload's
     * {@code classDefaultOpIds} (K-D12: the payload membership records
     * their nesting, not a static {@code parentOpId}); each is
     * shape-checked fail closed before its block runs (kind, policy,
     * classId coherence, a declared required-present field, no
     * duplicates). The payload's {@code callerOpRef} is the
     * deterministic pre-allocated execution-wiring slot (K-D2): the
     * executed parent is the triggering caller op this API pins, and a
     * non-{@code CLASS_NEW} caller reaching this child's surface is a
     * producer defect.</p>
     *
     * @param op              the validated owner-side {@code CLASS_FACTORY}
     *                        op carrying {@code CLASS_CONSTRUCTION};
     *                        non-null
     * @param triggeringCaller the triggering caller {@code CLASS_NEW} op
     *                        whose id is the factory's executed
     *                        {@code parentOpId} (cross-unit); non-null,
     *                        never the factory op itself
     * @param defaultOps      the owner unit's {@code CLASS_DEFAULT} ops
     *                        by {@link OpId}; every id of
     *                        {@code classDefaultOpIds} must resolve to a
     *                        {@code CLASS_DEFAULT} op of this classId
     *                        carrying {@code NO_DEAL_FAILURE}; non-null
     * @param layouts         the layout-resolution context
     *                        {@code ClassId → ClassLayout} (the owner's
     *                        {@code classLayouts} plus the project
     *                        interface facts); the payload's classId must
     *                        resolve; non-null
     * @param providedFields  the triggering context's provided-field name
     *                        set (a {@code CLASS_NEW}'s static
     *                        {@code providedFields} names) — every child
     *                        whose field is provided is skipped (a
     *                        provided field's default never runs); non-null
     * @param bodyRunner      the owner-side default-block callback
     *                        (defaults evaluate in the declaring module's
     *                        scope); non-null
     * @return {@code Success} with the internal default-filled transfer
     *         instance (untagged — declaration-order states, defaulted
     *         fields present, every other field missing)
     * @throws Defect               on a shape outside the pinned
     *                              contracts — a wrong op kind/policy, a
     *                              non-{@code CLASS_NEW} trigger, a
     *                              self-trigger, an unresolvable layout, a
     *                              default child of the wrong
     *                              kind/policy/class, a defaulted field
     *                              outside the layout or optional, or a
     *                              duplicate default child
     * @throws NullPointerException if any argument is null
     */
    public static Outcome<Value> executeClassFactory(
            SemanticOp op,
            SemanticOp triggeringCaller,
            Map<OpId, SemanticOp> defaultOps,
            Map<ClassId, ClassLayout> layouts,
            Set<String> providedFields,
            BodyRunner bodyRunner) {
        requireOp(op, SemanticOpKind.CLASS_FACTORY, FailurePolicyId.CLASS_CONSTRUCTION);
        Objects.requireNonNull(triggeringCaller, "triggeringCaller must not be null");
        Objects.requireNonNull(defaultOps, "defaultOps must not be null");
        Objects.requireNonNull(layouts, "layouts must not be null");
        Objects.requireNonNull(providedFields, "providedFields must not be null");
        Objects.requireNonNull(bodyRunner, "bodyRunner must not be null");
        // The executed parentOpId pin (K-D5/K-D12): the factory's events
        // parent to the triggering caller op — a caller CLASS_NEW for this
        // child's surface (the JSON_FROM_CLASS nested-decode trigger is
        // the later JSON child's). The factory never parents to itself.
        if (triggeringCaller.kind() != SemanticOpKind.CLASS_NEW) {
            throw new Defect("CLASS_FACTORY " + op.opId() + " triggered by an op of kind "
                + triggeringCaller.kind() + ": the closed trigger set is {CLASS_NEW (this "
                + "child's trigger), JSON_FROM_CLASS (the later JSON child's trigger)} and "
                + "the factory's executed parentOpId is the triggering caller op's id "
                + "(K-D5/K-D12) — a non-CLASS_NEW caller reaching this child's surface is "
                + "a producer defect, never executed");
        }
        if (triggeringCaller.opId().equals(op.opId())) {
            throw new Defect("CLASS_FACTORY " + op.opId() + " triggered by itself: the "
                + "factory is the declaring module's default-application op and its "
                + "executed parentOpId is the triggering caller op (cross-unit) — a "
                + "factory as its own caller is a producer defect, never executed");
        }
        KindPayload.ClassFactoryPayload payload =
            (KindPayload.ClassFactoryPayload) op.payload();

        // Layout resolution (K-D11): the factory fills the declared
        // layout of its own classId, never a foreign layout.
        ClassLayout layout = layouts.get(payload.classId());
        if (layout == null) {
            throw new Defect("CLASS_FACTORY " + op.opId() + " classId " + payload.classId()
                + " does not resolve in the layout-resolution context: the factory fills "
                + "its class's declared layout (the owner unit's classLayouts) — an "
                + "unresolvable layout is a producer defect, never executed");
        }

        // Declaration-order default application (K-D5): the CLASS_DEFAULT
        // children in declaration order, skipping any child whose field
        // the triggering context records as provided (a provided field's
        // default never runs); defaults evaluate per construction in the
        // declaring module's scope through the owner-side body runner.
        LinkedHashMap<String, Value> filled = new LinkedHashMap<>();
        for (OpId defaultOpId : payload.classDefaultOpIds()) {
            SemanticOp defaultOp = requireDefaultChild(defaultOps, defaultOpId,
                payload.classId(), op);
            KindPayload.ClassDefaultPayload defaultPayload =
                (KindPayload.ClassDefaultPayload) defaultOp.payload();
            if (providedFields.contains(defaultPayload.field())) {
                // The skip-provided rule (K-D5): the caller's provided
                // fields overlay after the transfer, so their defaults
                // are never executed by this construction attempt.
                continue;
            }
            ClassLayout.FieldLayout fieldLayout = fieldOf(layout, defaultPayload.field());
            if (fieldLayout == null) {
                throw new Defect("CLASS_FACTORY " + op.opId() + " names CLASS_DEFAULT "
                    + "child " + defaultOpId + " for field '" + defaultPayload.field()
                    + "' which is not a declared field of " + payload.classId()
                    + ": a default child of an undeclared field is a producer defect, "
                    + "never executed");
            }
            if (!fieldLayout.required()) {
                throw new Defect("CLASS_FACTORY " + op.opId() + " names CLASS_DEFAULT "
                    + "child " + defaultOpId + " for optional field '"
                    + defaultPayload.field() + "': the factory payload lists "
                    + "required-present defaulted fields only (an optional-with-default "
                    + "field's default never runs and its op id never enters the "
                    + "payload) — a listed optional default is a producer defect, never "
                    + "executed");
            }
            if (filled.containsKey(defaultPayload.field())) {
                throw new Defect("CLASS_FACTORY " + op.opId() + " names two CLASS_DEFAULT "
                    + "children for field '" + defaultPayload.field() + "': the pinned "
                    + "shape carries exactly one default child per defaulted field — a "
                    + "duplicate is a producer defect, never executed");
            }
            Outcome<Value> produced = executeClassDefault(defaultOp, bodyRunner);
            if (!(produced instanceof Outcome.Success<Value> success)) {
                // executeClassDefault cannot fail by itself: a default-block
                // failure propagates as the callback's own throw (a failing
                // child fails the triggering caller's op — no instance).
                throw new Defect("CLASS_FACTORY " + op.opId() + " CLASS_DEFAULT child "
                    + defaultOpId + " returned a failure terminal from the default "
                    + "execution: the default op's policy is NO_DEAL_FAILURE and only "
                    + "already-started child/operand failures may propagate — a "
                    + "producer defect, never executed");
            }
            filled.put(defaultPayload.field(), success.value());
        }

        // The internal default-filled transfer instance (K-D5, untagged):
        // declaration-order states — the defaulted fields present, every
        // other field missing. The factory never publishes this instance:
        // the caller's CLASS_NEW overlays provided fields, validates, and
        // tags its own fresh published instance.
        List<FieldState> states = new ArrayList<>(layout.fields().size());
        for (ClassLayout.FieldLayout fieldLayout : layout.fields()) {
            Value value = filled.get(fieldLayout.name());
            states.add(value == null ? FieldState.Missing.INSTANCE
                : new FieldState.Present(value));
        }
        return new Outcome.Success<Value>(
            new Value.Class(payload.classId(), List.copyOf(states)));
    }

    // =========================================================================
    // CLASS_NEW(SHARED_FACTORY)
    // =========================================================================

    /**
     * Executes one validated {@code CLASS_NEW} op with
     * {@code defaultOwner: SHARED_FACTORY} in the closed K-D4/D16 order
     * with the K-D5 cross-unit transfer:
     *
     * <ol>
     *   <li>provided values resolve from the lookup in literal order in
     *       the caller before the transfer (the
     *       {@code jvm-xmod-class-construction-eval-order} pin — the
     *       provided-field evaluation crosses the module boundary in
     *       literal order);</li>
     *   <li>default application transfers to the owner unit's
     *       {@code CLASS_FACTORY} op — resolved by the payload's
     *       {@code classFactoryRef} {@link ClassFactoryId} through the
     *       caller-supplied {@link ClassFactoryRegistry} (K-D5) — which
     *       fills the omitted required-present fields' defaults in the
     *       declaring module's scope (skipping provided fields) and
     *       returns the default-filled transfer instance;</li>
     *   <li>extra-key rejection first in provided-source order: the
     *       first provided name not in the layout fails
     *       {@code CLASS_CONSTRUCTION} — E8007
     *       {@code extra field '{field}' in class '{classId}'} at the op
     *       origin — after the transfer (the default side effects have
     *       completed) and before any provided-field application or
     *       field validation;</li>
     *   <li>provided-field application in declaration order: the overlay
     *       of the provided values onto the transferred instance (the
     *       {@code jvm-xmod-class-construction-defaults} reorder pin);</li>
     *   <li>field validation in declaration order through the
     *       {@link BoundaryCheckRunner} seam per {@code fieldBoundaries}
     *       entry — {@code CLASS_LITERAL_FIELD} for provided fields with
     *       input = the field's provided value; {@code CLASS_DEFAULT_FIELD}
     *       for omitted required-present defaulted fields with the pinned
     *       input wiring = the owner {@code CLASS_FACTORY} op's result
     *       {@link ValueId} (the K-D4 cross-unit D4-global reference) and
     *       the extraction rule — the executor reads the named field
     *       from the transferred instance before running the boundary
     *       (the checked value is the transferred instance's field);</li>
     *   <li>the fresh caller-side instance is tagged with {@code classId}
     *       ({@code class:<ClassId>} canonical identity) and SUCCESS
     *       publishes it — a fresh instance record distinct from the
     *       internal transfer instance.</li>
     * </ol>
     *
     * A failure at any step publishes no partial instance (the
     * {@link Value.Class} exists only on the success path, so the tag
     * never runs); completed children's effects remain. A failing
     * default child propagates as the owner body runner's own throw —
     * the triggering caller's op fails and no caller instance exists.
     * {@code CLASS_NEW} runs zero return boundaries — the executor never
     * drives a {@code FUNCTION_RETURN} child.
     *
     * @param op              the validated caller-side {@code CLASS_NEW}
     *                        op carrying {@code CLASS_CONSTRUCTION} with
     *                        {@code defaultOwner: SHARED_FACTORY},
     *                        non-null {@code classFactoryRef}, and empty
     *                        {@code classDefaultOpIds}; non-null
     * @param priorValues     the resolved provided-field prior-step values
     *                        (the caller's); non-null, no null entries
     * @param factories       the owner's {@link ClassFactoryRegistry} —
     *                        the {@code classFactoryRef} must resolve to
     *                        the owner {@code CLASS_FACTORY} op id
     *                        (K-D5); non-null
     * @param ownerOps        the owner unit's ops by {@link OpId} (the
     *                        factory op, its result, and the owner's
     *                        {@code CLASS_DEFAULT} ops); non-null
     * @param boundaryOps     the caller unit's boundary ops by
     *                        {@link OpId}; every {@code fieldBoundaries}
     *                        id must resolve to a {@code BOUNDARY} op
     *                        parented to this op; non-null
     * @param layouts         the layout-resolution context
     *                        {@code ClassId → ClassLayout} (the caller's
     *                        {@code classLayouts} plus the owner unit's);
     *                        the payload's classId must resolve to exactly
     *                        the payload's layout; non-null
     * @param checkRunner     the boundary-check delegate; non-null
     * @param ownerBodyRunner the owner-side default-block callback
     *                        (defaults evaluate in the declaring module's
     *                        scope); non-null
     * @return {@code Success} with the fresh tagged caller-side instance
     *         after every field boundary passed, or {@code Failure} with
     *         the E8007 extra-key projection or the first failing
     *         boundary's failure
     * @throws Defect               on a shape outside the pinned
     *                              contracts — a wrong op kind/policy, a
     *                              non-{@code SHARED_FACTORY} owner, a
     *                              null factory ref or non-empty
     *                              {@code classDefaultOpIds}, a layout
     *                              resolution failure, an unresolvable
     *                              factory binding or factory op, a
     *                              factory of the wrong kind/policy/class,
     *                              a non-{@link ValueId} factory result,
     *                              a provided value that does not resolve
     *                              or resolves to {@code Missing}, a
     *                              boundary child or field-boundary entry
     *                              outside the pinned shape, an
     *                              input-wiring mismatch (including the
     *                              {@code CLASS_DEFAULT_FIELD} input not
     *                              naming the factory result), or a
     *                              transferred instance missing a field
     *                              the boundary list names
     * @throws NullPointerException if any argument is null
     */
    public static Outcome<Value> executeClassNewSharedFactory(
            SemanticOp op,
            Map<ValueId, Value> priorValues,
            ClassFactoryRegistry factories,
            Map<OpId, SemanticOp> ownerOps,
            Map<OpId, SemanticOp> boundaryOps,
            Map<ClassId, ClassLayout> layouts,
            BoundaryCheckRunner checkRunner,
            BodyRunner ownerBodyRunner) {
        requireOp(op, SemanticOpKind.CLASS_NEW, FailurePolicyId.CLASS_CONSTRUCTION);
        Objects.requireNonNull(priorValues, "priorValues must not be null");
        Objects.requireNonNull(factories, "factories must not be null");
        Objects.requireNonNull(ownerOps, "ownerOps must not be null");
        Objects.requireNonNull(boundaryOps, "boundaryOps must not be null");
        Objects.requireNonNull(layouts, "layouts must not be null");
        Objects.requireNonNull(checkRunner, "checkRunner must not be null");
        Objects.requireNonNull(ownerBodyRunner, "ownerBodyRunner must not be null");
        KindPayload.ClassNewPayload payload = (KindPayload.ClassNewPayload) op.payload();

        // This child's surface: SHARED_FACTORY execution. LOCAL execution
        // is {@link #executeClassNewLocal}'s; RETAINED_ABI is E10's — a
        // non-SHARED_FACTORY owner reaching this surface is a producer
        // defect, never silently executed as a transfer.
        if (payload.defaultOwner() != DefaultOwner.SHARED_FACTORY) {
            throw new Defect("CLASS_NEW " + op.opId() + " carries defaultOwner "
                + payload.defaultOwner() + ": this child's executor surface is "
                + "SHARED_FACTORY transfer (executeClassNewLocal is the LOCAL surface and "
                + "RETAINED_ABI transfer is E10's) — a non-SHARED_FACTORY owner reaching "
                + "executeClassNewSharedFactory is a producer defect, never executed");
        }
        if (payload.classFactoryRef() == null) {
            throw new Defect("CLASS_NEW " + op.opId() + " carries a null classFactoryRef: "
                + "the pinned SHARED_FACTORY shape carries classFactoryRef = the "
                + "interface's pre-allocated constructionEntry — a null factory ref is a "
                + "producer defect, never executed");
        }
        if (!payload.classDefaultOpIds().isEmpty()) {
            throw new Defect("CLASS_NEW " + op.opId() + " carries " + payload.classDefaultOpIds()
                + ": the pinned SHARED_FACTORY shape carries classDefaultOpIds empty (the "
                + "owner's CLASS_FACTORY carries the CLASS_DEFAULT child list) — a "
                + "non-empty list is a producer defect, never executed");
        }

        // Layout resolution (K-D11): the payload is never interpreted
        // against a foreign layout.
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

        // K-D4 step 1: provided values resolve in literal order in the
        // caller before the transfer (the eval-order pin).
        LinkedHashMap<String, Value> providedValues = new LinkedHashMap<>();
        LinkedHashMap<String, ValueId> providedValueIds = new LinkedHashMap<>();
        for (KindPayload.ProvidedField field : payload.providedFields()) {
            Value value = resolve(priorValues, field.valueOpId());
            if (value instanceof Value.Missing) {
                throw new Defect("CLASS_NEW " + op.opId() + " provided field '" + field.name()
                    + "' (" + field.valueOpId() + ") resolves to the internal Missing view: "
                    + "a provided field always carries a present value (language null is "
                    + "the explicit Null variant) — a wrong-kind value is a producer "
                    + "defect, never executed");
            }
            // Duplicate provided names keep the last value (the checker's
            // class-literal map rule); every entry still resolves in
            // literal order so its effects complete.
            providedValues.put(field.name(), value);
            providedValueIds.put(field.name(), field.valueOpId());
        }

        // The factory resolution by ClassFactoryId (K-D5): the payload's
        // classFactoryRef names the owner's CLASS_FACTORY op through the
        // registry — a missing binding means an owner not on the shared
        // route (its construction defers at lowering, RETAINED_ABI is
        // E10's) and is never executed here.
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
                + ": the pinned factory fills the constructed class's defaults ("
                + payload.classId() + ") — a classId mismatch is a producer defect, "
                + "never executed");
        }
        if (!(factoryOp.result() instanceof ValueId factoryResult)) {
            throw new Defect("CLASS_NEW " + op.opId() + " factory op " + factoryOp.opId()
                + " publishes a non-ValueId result " + factoryOp.result()
                + ": the pinned CLASS_DEFAULT_FIELD input is the CLASS_FACTORY op's "
                + "result ValueId (K-D4 input wiring) — a producer defect, never "
                + "executed");
        }

        // The owner-side CLASS_DEFAULT lookup (defaults evaluate in the
        // declaring module's scope through the owner's ops).
        Map<OpId, SemanticOp> ownerDefaultOps = new LinkedHashMap<>();
        for (Map.Entry<OpId, SemanticOp> entry : ownerOps.entrySet()) {
            if (entry.getValue().kind() == SemanticOpKind.CLASS_DEFAULT) {
                ownerDefaultOps.put(entry.getKey(), entry.getValue());
            }
        }

        // K-D4 step 2: the transfer to the owner's CLASS_FACTORY — the
        // factory skips the caller's provided fields and returns the
        // default-filled transfer instance (untagged). A failing default
        // child propagates as the owner body runner's throw (the caller's
        // op fails; no instance published; completed effects remain).
        Set<String> providedNames = new LinkedHashSet<>(providedValues.keySet());
        Outcome<Value> transferred = executeClassFactory(factoryOp, op, ownerDefaultOps,
            layouts, providedNames, ownerBodyRunner);
        if (!(transferred instanceof Outcome.Success<Value> transferSuccess
                && transferSuccess.value() instanceof Value.Class transferredInstance)) {
            throw new Defect("CLASS_NEW " + op.opId() + " factory transfer produced "
                + transferred + ": the pinned factory returns exactly its internal "
                + "default-filled transfer instance — a wrong outcome is a producer "
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

        // K-D4 step 3: extra-key rejection first in provided-source order —
        // after the transfer, before any provided-field application or
        // field validation (the completed default effects are observable).
        for (KindPayload.ProvidedField field : payload.providedFields()) {
            if (fieldOf(layout, field.name()) == null) {
                BoundaryFailure failure = BoundaryFailure.fromRow(
                    FailureContractRegistry.row(FailurePolicyId.CLASS_CONSTRUCTION), 0,
                    null, null,
                    metadataOf("field", field.name(), "classId", payload.classId().text()),
                    null);
                return new Outcome.Failure<Value>(new OpFailure(failure, op.origin()));
            }
        }

        // The pinned field-boundary coverage (K-D4 step 5 shape): exactly
        // one entry per provided field (CLASS_LITERAL_FIELD) and per
        // defaulted field (CLASS_DEFAULT_FIELD) in declaration order;
        // omitted optional fields get no boundary and stay missing.
        List<String> expectedBoundaryFields = new ArrayList<>();
        for (ClassLayout.FieldLayout fieldLayout : layout.fields()) {
            if (providedValues.containsKey(fieldLayout.name())
                    || defaultValueOf(transferredInstance, layout, fieldLayout.name()) != null) {
                expectedBoundaryFields.add(fieldLayout.name());
            }
        }
        List<KindPayload.FieldBoundary> boundaries = payload.fieldBoundaries();
        if (boundaries.size() != expectedBoundaryFields.size()) {
            throw new Defect("CLASS_NEW " + op.opId() + " carries " + boundaries.size()
                + " field-boundary entries for " + expectedBoundaryFields.size()
                + " present fields: the pinned shape carries exactly one boundary per "
                + "provided field and per omitted required-present defaulted field in "
                + "declaration order — a child-count mismatch is a producer defect, "
                + "never executed");
        }
        for (int i = 0; i < boundaries.size(); i++) {
            KindPayload.FieldBoundary entry = boundaries.get(i);
            String expectedField = expectedBoundaryFields.get(i);
            if (!entry.field().equals(expectedField)) {
                throw new Defect("CLASS_NEW " + op.opId() + " field-boundary entry " + i
                    + " names field '" + entry.field() + "': the pinned declaration order "
                    + "names '" + expectedField + "' — a field-boundary order mismatch "
                    + "is a producer defect, never executed");
            }
            BoundaryKind expectedKind = providedValues.containsKey(entry.field())
                ? BoundaryKind.CLASS_LITERAL_FIELD : BoundaryKind.CLASS_DEFAULT_FIELD;
            if (entry.kind() != expectedKind) {
                throw new Defect("CLASS_NEW " + op.opId() + " field-boundary entry for '"
                    + entry.field() + "' carries kind " + entry.kind() + ": the pinned "
                    + "kind is " + expectedKind + " (CLASS_LITERAL_FIELD for provided "
                    + "values, CLASS_DEFAULT_FIELD for defaulted values) — a kind "
                    + "mismatch is a producer defect, never executed");
            }
        }

        // K-D4 step 4: provided-field application in declaration order (the
        // overlay onto the transferred instance; duplicate names keep the
        // last provided value).
        LinkedHashMap<String, Value> instanceFields = new LinkedHashMap<>();
        for (ClassLayout.FieldLayout fieldLayout : layout.fields()) {
            Value provided = providedValues.get(fieldLayout.name());
            Value defaultValue = defaultValueOf(transferredInstance, layout, fieldLayout.name());
            if (provided != null) {
                instanceFields.put(fieldLayout.name(), provided);
            } else if (defaultValue != null) {
                instanceFields.put(fieldLayout.name(), defaultValue);
            }
        }

        // K-D4 step 5: field validation in declaration order through the
        // BoundaryCheckRunner seam; the first failing child fails the op
        // and no instance is published.
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
                // The K-D4 extraction rule: the boundary's input naming
                // the owner CLASS_FACTORY op's result ValueId is the
                // wiring (the cross-unit D4-global reference); the checked
                // value is the transferred instance's named field — the
                // factory fills exactly the defaulted fields this list
                // names, so the field must be present.
                if (!boundaryPayload.input().equals(factoryResult)) {
                    throw new Defect("CLASS_NEW " + op.opId() + " field boundary "
                        + child.opId() + " carries input " + boundaryPayload.input()
                        + ": the pinned CLASS_DEFAULT_FIELD input of a SHARED_FACTORY "
                        + "CLASS_NEW is the owner CLASS_FACTORY op's result ValueId "
                        + factoryResult + " (K-D4 input wiring, the cross-unit D4-global "
                        + "reference) — a mismatch is a producer defect, never executed");
                }
                Value extracted = defaultValueOf(transferredInstance, layout, entry.field());
                if (extracted == null) {
                    throw new Defect("CLASS_NEW " + op.opId() + " carries a "
                        + "CLASS_DEFAULT_FIELD boundary for field '" + entry.field()
                        + "' whose transferred instance field is missing: the factory "
                        + "fills exactly the omitted required-present defaulted fields "
                        + "the boundary list names (the extraction rule reads the "
                        + "transferred instance's named field) — a missing field is a "
                        + "producer defect, never executed");
                }
                input = extracted;
            }
            BoundaryResult result = checkRunner.run(boundaryPayload, input);
            if (result instanceof BoundaryResult.Pass pass) {
                instanceFields.put(entry.field(), pass.value());
                continue;
            }
            // The first failing child fails the op: no instance, no tag,
            // and the later children never run.
            BoundaryResult.Fail fail = (BoundaryResult.Fail) result;
            return new Outcome.Failure<Value>(new OpFailure(fail.failure(), op.origin()));
        }

        // K-D4 step 6: tag the fresh caller-side instance with classId and
        // SUCCESS publishes it — a fresh instance record distinct from the
        // internal transfer instance (declaration-order field states;
        // omitted optionals stay missing; present null stays present
        // null).
        List<FieldState> states = new ArrayList<>(layout.fields().size());
        for (ClassLayout.FieldLayout fieldLayout : layout.fields()) {
            Value value = instanceFields.get(fieldLayout.name());
            states.add(value == null ? FieldState.Missing.INSTANCE
                : new FieldState.Present(value));
        }
        return new Outcome.Success<Value>(
            new Value.Class(payload.classId(), List.copyOf(states)));
    }

    /**
     * The present value of the named declaration-order field of one class
     * instance, or {@code null} when the field is missing (never a
     * conflated state — present null is the explicit {@link Value.Null}
     * variant and returns non-null). The instance's states parallel its
     * layout's declaration order (checked fail closed by the callers).
     */
    private static Value defaultValueOf(Value.Class instance, ClassLayout layout,
                                        String fieldName) {
        for (int i = 0; i < layout.fields().size(); i++) {
            if (layout.fields().get(i).name().equals(fieldName)) {
                FieldState state = instance.fields().get(i);
                return state instanceof FieldState.Present present ? present.value() : null;
            }
        }
        return null;
    }

    // =========================================================================
    // FIELD_READ
    // =========================================================================

    /**
     * Executes one validated {@code FIELD_READ} op (K-D6): the nominal
     * receiver boundary runs first — the {@code UNTYPED_CLASS_INPUT}
     * child with descriptor {@code class:<ClassId>} and input = the
     * payload's resolved receiver (a null receiver fails E8001
     * {@code expected {@module/C}, got null} and a wrong identity fails
     * with actual {@code class:<other>} — the canonical descriptor-kind
     * projections of the child's own delegate); then the presence-aware
     * read: a missing field pre-maps to language null before the
     * {@code OPTIONAL_FIELD_READ} boundary (present null passes a
     * nullable descriptor; a missing value against a non-nullable
     * descriptor — a required field of a defective instance — fails
     * E8001 {@code expected {T}, got null}); SUCCESS publishes the
     * boundary-checked value. The receiver resolves from the value
     * lookup exactly once (never re-evaluated) and the key is the
     * static payload field name (never evaluated).
     *
     * <p>The children are supplied as explicit ops (the payload records
     * no boundary ids — the K-D12 parentage pin is the owner relation)
     * and are shape-checked fail closed before each run: the pinned
     * kind, {@code parentOpId} = the {@code FIELD_READ} op, a
     * {@code RuntimeValidation} realization, the pinned descriptor
     * ({@code class:<ClassId>} for the receiver boundary; the read's
     * checked result descriptor for the field boundary — the field's
     * declared descriptor, nullable-wrapped for an optional non-nullable
     * field, the checker's optional-read wrap), the pinned input
     * (the payload's {@code classValue} / the op's own result
     * {@code ValueId}), and the descriptor-kind policy.</p>
     *
     * @param op               the validated {@code FIELD_READ} op
     *                         carrying {@code NO_DEAL_FAILURE}; non-null
     * @param priorValues      the resolved prior-step values (the
     *                         receiver); non-null, no null entries
     * @param receiverBoundary the op's first child — the
     *                         {@code UNTYPED_CLASS_INPUT} boundary;
     *                         non-null
     * @param fieldBoundary    the op's second child — the
     *                         {@code OPTIONAL_FIELD_READ} boundary;
     *                         non-null
     * @param layouts          the layout-resolution context
     *                         {@code ClassId → ClassLayout}; the
     *                         payload's classId must resolve; non-null
     * @param checkRunner      the boundary-check delegate; non-null
     * @return {@code Success} with the boundary-checked field value, or
     *         {@code Failure} with the first failing boundary's failure
     *         at the op origin
     * @throws Defect               on a shape outside the pinned
     *                              contracts — a wrong op kind/policy, a
     *                              non-{@code ValueId} result, a child of
     *                              the wrong kind/parentage/realization/
     *                              descriptor/policy/input, an
     *                              unresolvable or mismatched layout, a
     *                              field not declared in the layout, an
     *                              instance of the wrong class or field
     *                              count, or a receiver the pinned
     *                              receiver boundary could not pass
     * @throws NullPointerException if any argument is null
     */
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

        // The nominal receiver boundary first (K-D6): the pinned child
        // shape, then the delegate run with the resolved receiver.
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

        // The presence-aware read: Present(value) reads the value;
        // Missing pre-maps to language null before the field boundary
        // (K-D6) — present null stays present null, missing stays
        // distinguishable through the pre-map.
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

    /**
     * Executes one validated {@code FIELD_WRITE} commit op (K-D6): the
     * nominal receiver boundary runs first (the
     * {@code UNTYPED_CLASS_INPUT} child with descriptor
     * {@code class:<ClassId>} and input = the payload's resolved
     * receiver), then the field boundary (the
     * {@code CLASS_FIELD_ASSIGNMENT} child with the field's declared
     * descriptor and input = the payload's resolved stored value), and
     * the store commits immediately before SUCCESS — the published
     * instance carries the boundary-published value in the named field
     * with every other field state unchanged (a fresh updated instance;
     * the caller rebinds the receiver's reference to it). A failed
     * boundary commits nothing: the outcome is {@code Failure} and no
     * updated instance exists. The receiver and the stored value each
     * resolve from the value lookup exactly once (never re-evaluated);
     * the key is the static payload field name (never evaluated).
     *
     * <p>The children are supplied as explicit ops and are shape-checked
     * fail closed before each run exactly like {@link #executeFieldRead}'s
     * (pinned kind, {@code parentOpId} = the {@code FIELD_WRITE} op, a
     * {@code RuntimeValidation} realization, the pinned descriptor and
     * input, the descriptor-kind policy).</p>
     *
     * @param op               the validated {@code FIELD_WRITE} op
     *                         carrying {@code NO_DEAL_FAILURE}; non-null
     * @param priorValues      the resolved prior-step values (the
     *                         receiver and the stored value); non-null,
     *                         no null entries
     * @param receiverBoundary the op's first child — the
     *                         {@code UNTYPED_CLASS_INPUT} boundary;
     *                         non-null
     * @param fieldBoundary    the op's second child — the
     *                         {@code CLASS_FIELD_ASSIGNMENT} boundary;
     *                         non-null
     * @param layouts          the layout-resolution context
     *                         {@code ClassId → ClassLayout}; the
     *                         payload's classId must resolve; non-null
     * @param checkRunner      the boundary-check delegate; non-null
     * @return {@code Success} with the updated instance (the store
     *         committed), or {@code Failure} with the first failing
     *         boundary's failure at the op origin (nothing committed)
     * @throws Defect               on a shape outside the pinned
     *                              contracts (the
     *                              {@link #executeFieldRead} set)
     * @throws NullPointerException if any argument is null
     */
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

        // The nominal receiver boundary first (K-D6).
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

        // The field boundary with the stored value and the field's
        // declared descriptor (K-D6); the store commits only after both
        // pass — a failed boundary commits nothing.
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

    /**
     * Executes one validated {@code FIELD_DELETE} commit op (K-D6): the
     * nominal receiver boundary runs (the {@code UNTYPED_CLASS_INPUT}
     * child with descriptor {@code class:<ClassId>} and input = the
     * payload's resolved receiver), then the field is set missing — the
     * published instance carries {@code Missing} in the named field with
     * every other field state unchanged. Deleting an already-missing
     * field is a no-op SUCCESS (the published instance equals the
     * receiver's state). The receiver resolves from the value lookup
     * exactly once; the key is the static payload field name (never
     * evaluated).
     *
     * <p>The child is supplied as an explicit op and is shape-checked
     * fail closed before the run exactly like {@link #executeFieldRead}'s
     * children (pinned kind, {@code parentOpId} = the
     * {@code FIELD_DELETE} op, a {@code RuntimeValidation} realization,
     * the pinned descriptor and input, the descriptor-kind policy).</p>
     *
     * @param op               the validated {@code FIELD_DELETE} op
     *                         carrying {@code NO_DEAL_FAILURE}; non-null
     * @param priorValues      the resolved prior-step values (the
     *                         receiver); non-null, no null entries
     * @param receiverBoundary the op's only child — the
     *                         {@code UNTYPED_CLASS_INPUT} boundary;
     *                         non-null
     * @param layouts          the layout-resolution context
     *                         {@code ClassId → ClassLayout}; the
     *                         payload's classId must resolve; non-null
     * @param checkRunner      the boundary-check delegate; non-null
     * @return {@code Success} with the updated instance (the field
     *         missing), or {@code Failure} with the failing boundary's
     *         failure at the op origin
     * @throws Defect               on a shape outside the pinned
     *                              contracts (the
     *                              {@link #executeFieldRead} set)
     * @throws NullPointerException if any argument is null
     */
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

        // The nominal receiver boundary (K-D6).
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

    /**
     * Executes one validated {@code HAS_FIELD} op (K-D7): the receiver
     * resolves from the value lookup exactly once (the key is the static
     * payload field name — never evaluated) and the op publishes the
     * presence boolean: present (present null included) → {@code true},
     * missing → {@code false}. The op runs no boundary children of any
     * kind and its policy is {@code NO_DEAL_FAILURE} — a valid shape
     * never fails, so the success terminal always carries the presence
     * boolean.
     *
     * <p>The presence states stay distinct in the class value view:
     * {@code Present(null)} is present, {@code Missing} is missing —
     * never conflated.</p>
     *
     * @param op          the validated {@code HAS_FIELD} op carrying
     *                    {@code NO_DEAL_FAILURE}; non-null
     * @param priorValues the resolved prior-step values (the receiver);
     *                    non-null, no null entries
     * @param layouts     the layout-resolution context
     *                    {@code ClassId → ClassLayout}; the instance's
     *                    own classId must resolve; non-null
     * @return {@code Success} with the presence boolean
     * @throws Defect               on a shape outside the pinned
     *                              contracts — a wrong op kind/policy, a
     *                              receiver that does not resolve to a
     *                              class, an unresolvable layout, a key
     *                              not declared in the layout, or an
     *                              instance field-count mismatch
     * @throws NullPointerException if any argument is null
     */
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

    /**
     * Requires one field-op boundary child's pinned shape fail closed:
     * a {@code BOUNDARY} op of the pinned kind whose origin
     * {@code parentOpId} is the owning field op, a
     * {@code RuntimeValidation} realization (proof is inadmissible on
     * these cells, K-D6), the pinned descriptor, the pinned input, and
     * the descriptor-kind policy.
     */
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
     * layout field (the checker's optional-read wrap): the field's
     * declared descriptor, nullable-wrapped for an optional non-nullable
     * field.
     */
    private static RuntimeDescriptor readResultDescriptorOf(ClassLayout.FieldLayout field) {
        RuntimeDescriptor declared = field.descriptor();
        if (!field.required() && !(declared instanceof RuntimeDescriptor.Nullable)) {
            return new RuntimeDescriptor.Nullable(declared);
        }
        return declared;
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
    private static void requireOp(SemanticOp op, SemanticOpKind kind, FailurePolicyId policy) {
        Objects.requireNonNull(op, "op must not be null");
        if (op.kind() != kind) {
            throw new Defect("expected a " + kind + " op, got " + op.kind() + " "
                + op.opId() + ": the executor interprets validated op shapes only — a "
                + "wrong kind is a producer defect, never executed");
        }
        if (op.failurePolicy() != policy) {
            throw new Defect(kind + " " + op.opId() + " carries failure policy "
                + op.failurePolicy() + ": the pinned policy is " + policy
                + " — a non-pinned policy is a producer defect, never executed");
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

    /**
     * Resolves one named {@code CLASS_DEFAULT} child and requires the
     * pinned shape fail closed: a {@code CLASS_DEFAULT} op carrying
     * {@code NO_DEAL_FAILURE} and the payload's classId. The op is a
     * detached structural op (K-D12: the payload membership records its
     * nesting under the triggering construction, not a static
     * {@code parentOpId}), so no parentage check applies — the
     * {@code classDefaultOpIds} membership is the pinned owner relation.
     */
    private static SemanticOp requireDefaultChild(Map<OpId, SemanticOp> defaultOps,
                                                  OpId childId, ClassId classId,
                                                  SemanticOp owner) {
        SemanticOp child = defaultOps.get(childId);
        if (child == null) {
            throw new Defect(owner.kind() + " " + owner.opId() + " names CLASS_DEFAULT "
                + "child " + childId + " which the default-op lookup does not resolve — a "
                + "producer defect, never executed");
        }
        if (child.kind() != SemanticOpKind.CLASS_DEFAULT) {
            throw new Defect(owner.kind() + " " + owner.opId() + " names default child "
                + child.opId() + " of kind " + child.kind()
                + ": the pinned child kind is CLASS_DEFAULT — a producer defect, never "
                + "executed");
        }
        if (child.failurePolicy() != FailurePolicyId.NO_DEAL_FAILURE) {
            throw new Defect(owner.kind() + " " + owner.opId() + " names default child "
                + child.opId() + " carrying failure policy " + child.failurePolicy()
                + ": the pinned CLASS_DEFAULT policy is NO_DEAL_FAILURE — a non-pinned "
                + "policy is a producer defect, never executed");
        }
        KindPayload.ClassDefaultPayload payload =
            (KindPayload.ClassDefaultPayload) child.payload();
        if (!payload.classId().equals(classId)) {
            throw new Defect(owner.kind() + " " + owner.opId() + " names default child "
                + child.opId() + " of class " + payload.classId()
                + ": the pinned default child belongs to the constructed class " + classId
                + " — a classId mismatch is a producer defect, never executed");
        }
        return child;
    }

    /**
     * Resolves one named field-boundary child and requires the
     * validator-pinned shape fail closed: a {@code BOUNDARY} op of the
     * given boundary kind whose origin {@code parentOpId} is the owning
     * {@code CLASS_NEW} op (the K-D4 parentage pin).
     */
    private static SemanticOp requireBoundaryChild(Map<OpId, SemanticOp> boundaryOps,
                                                   OpId childId, SemanticOp owner,
                                                   BoundaryKind pinnedKind) {
        SemanticOp child = boundaryOps.get(childId);
        if (child == null) {
            throw new Defect(owner.kind() + " " + owner.opId() + " names boundary child "
                + childId + " which the boundary lookup does not resolve — a producer "
                + "defect, never executed");
        }
        requireChildOf(child, owner, pinnedKind);
        return child;
    }

    /** The shared child-shape checks of {@link #requireBoundaryChild}. */
    private static void requireChildOf(SemanticOp child, SemanticOp owner,
                                       BoundaryKind pinnedKind) {
        if (child.kind() != SemanticOpKind.BOUNDARY) {
            throw new Defect(owner.kind() + " " + owner.opId() + " names child "
                + child.opId() + " of kind " + child.kind()
                + ": the pinned child kind is BOUNDARY — a producer defect, never executed");
        }
        if (!owner.opId().equals(child.origin().parentOpId())) {
            throw new Defect(owner.kind() + " " + owner.opId() + " names boundary child "
                + child.opId() + " whose origin parentOpId is "
                + child.origin().parentOpId()
                + ": the validator pins the child's parentOpId to the owning op — a "
                + "mismatch is a producer defect, never executed");
        }
        KindPayload.BoundaryPayload payload = (KindPayload.BoundaryPayload) child.payload();
        if (payload.kind() != pinnedKind) {
            throw new Defect(owner.kind() + " " + owner.opId() + " names boundary child "
                + child.opId() + " of boundary kind " + payload.kind()
                + ": the pinned child boundary kind is " + pinnedKind
                + " — a producer defect, never executed");
        }
    }

    /**
     * Requires the descriptor-kind rule on one field-boundary child (the
     * pinned policy of the {@code CLASS_LITERAL_FIELD}/
     * {@code CLASS_DEFAULT_FIELD} cells): {@code TYPE_DESCRIPTOR} for
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

    /** A deterministic insertion-ordered metadata map (the row's pinned keys). */
    private static Map<String, String> metadataOf(String key1, String value1,
                                                  String key2, String value2) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(key1, value1);
        metadata.put(key2, value2);
        return metadata;
    }
}
