package deal.semantic;

import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.CapabilityRequirementCatalog;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.LoweringFailureDetail;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The unit-producer claiming seam of the container/string construct stage
 * (ISSUE-0232 D9; ISSUE-0387): the activation-gated op-side check of
 * E6005 coverage item 4 with the full-evidence claim derivation and the
 * per-unit claim deferral. This is the construct-epic claim discipline at
 * unit-production time — never a validator rule (the closed validator rule
 * set is unchanged, and R-CAPABILITY's claimed→evidence direction is
 * unchanged).
 *
 * <p><b>The pinned home mapping (D9 item 3).</b> Every op this epic's
 * arms produce has exactly the following home capability rows:</p>
 * <ul>
 *   <li>{@code CONST} (scalar literals, template fragments) and
 *       {@code STRING_CONCAT} → {@code FOUNDATION_VALUES};</li>
 *   <li>{@code ARRAY_NEW}/{@code TABLE_NEW}/{@code ARRAY_LENGTH}/
 *       {@code MEMBER_READ}/{@code FOR_EACH} →
 *       {@code CONTAINERS_AND_STRINGS};</li>
 *   <li>the {@code ARRAY_LITERAL_ELEMENT}/
 *       {@code CONTEXTUAL_TABLE_READ} boundary children →
 *       {@code DESCRIPTORS} and {@code BOUNDARIES} (each a single-family
 *       {@code {BOUNDARY}} row, so one such child fully evidences both
 *       rows);</li>
 *   <li>{@code BINDING_LOAD} → {@code BINDINGS}.</li>
 * </ul>
 *
 * <p>Every other op kind and every other boundary kind has no home row in
 * this seam: those ops are other construct epics' (ISSUE-0231/0234/0235…)
 * production and their op-side claim outcomes are recorded by their own
 * producers under this same shared mechanism. In particular no E3 op homes
 * to {@code SIGNED_INT32} — that row's {@code CONST(Int)}/
 * {@code BOUNDARY(int descriptor)} specializations are E2's evidence
 * (D9 item 4).</p>
 *
 * <p><b>Row activation (D9 item 1).</b> Capability C's claiming
 * derivation row activates at the closing gate of the construct epic that
 * lands C's last required family; until then no unit may claim C and C's
 * families are staged. The pinned gate activation states below are the
 * recorded release-registry hand-offs (not in-window flips):</p>
 * <ul>
 *   <li>{@link #E3_WINDOW_ACTIVATION} — the activation during this epic's
 *       tail: {@code SIGNED_INT32} activated at E2's gate (ISSUE-0231's
 *       closing gate); {@code FOUNDATION_VALUES} activates at this epic's
 *       gate, after the tail;</li>
 *   <li>{@link #E3_GATE_ACTIVATION} — the E3-gate hand-off:
 *       {@code FOUNDATION_VALUES} activates with the D9 item 5(a)
 *       claim/check consequence, identical at every later gate;</li>
 *   <li>{@link #E4_GATE_ACTIVATION} — {@code DESCRIPTORS}/
 *       {@code BOUNDARIES} activate for the boundary children (E4's
 *       gate);</li>
 *   <li>{@link #E5_GATE_ACTIVATION} — {@code CONTAINERS_AND_STRINGS} and
 *       {@code EVALUATION_ORDER} activate (E5's gate);</li>
 *   <li>{@link #E6_GATE_ACTIVATION} — {@code BINDINGS} activates for
 *       {@code BINDING_LOAD} (E6's gate).</li>
 * </ul>
 *
 * <p><b>Full-evidence claim derivation (D9 item 2).</b>
 * {@link #deriveClaims} claims capability C exactly when row(C) is active
 * and the unit produces at least one op of every required family of C —
 * the closed {@link CapabilityRequirementCatalog} applied in reverse,
 * kind-level exactly as R-CAPABILITY consumes it. R-CAPABILITY then holds
 * by construction: every claimed family is guaranteed produced
 * mechanically, never by corpus luck. A claim without full evidence is
 * therefore impossible by the derivation; an empty-evidence row
 * ({@code STDLIB_TIME_CONFLICT}, a routing marker only) is never derived
 * and never fully evidenced, and {@link #check} rejects a claim the
 * derivation cannot derive as a producer defect.</p>
 *
 * <p><b>The activation-gated op-side check (D9 item 3).</b>
 * {@link #check} makes one deterministic pass over the produced op set
 * and records exactly one outcome per produced op and per home row:</p>
 * <ul>
 *   <li><b>Row inactive</b> → the op is a recorded
 *       {@link OutcomeKind#STAGED_HAND_OFF}: the unit's claim set excludes
 *       the row. Deterministic; never a silent skip and never a
 *       failure.</li>
 *   <li><b>Row active and fully evidenced by the unit</b> → the
 *       derivation claims the row and the check passes — the recorded
 *       {@link OutcomeKind#CLAIMED} outcome. If the producer nevertheless
 *       omitted the claim, the seam fires the single E6005 condition: the
 *       returned {@link SeamResult#failure()} carries the exact
 *       {@link LoweringFailureDetail} with validatorRule
 *       {@link #OPERATION_OUTSIDE_CLAIMED_CAPABILITY}, capability = the
 *       home row, {@link SemanticProfile#DEAL_V1_2_INT32},
 *       {@code deal.semantic-ir/1}, and the pinned origin — converted to
 *       the E6005 diagnostic through
 *       {@code FailureContractRegistry.e6005} by the producer (the
 *       derivation-invariant guard). The firing position's record is that
 *       failure detail (no outcome entry), the pass continues recording
 *       the remaining positions' outcomes, and the first firing position
 *       in the pinned iteration order wins.</li>
 *   <li><b>Row active but not fully evidenced by the unit</b> → the
 *       recorded, terminal claim outcome is the per-unit
 *       {@link OutcomeKind#DEFERRED} deferral for that immutable unit.
 *       No E6005 — demanding the claim would be impossible by
 *       construction, because R-CAPABILITY rejects a claimed row without
 *       its required operations — and the deferral is never
 *       retroactively converted into a failure.</li>
 * </ul>
 *
 * <p>{@code OPERATION_OUTSIDE_CLAIMED_CAPABILITY} has exactly this one
 * firing condition: an active home row fully evidenced by the unit and
 * left unclaimed.</p>
 *
 * <p><b>E3 tail and the pinned gate claim states (D9 items 4/5).</b>
 * During the tail every E3-produced op's home row is inactive
 * ({@link #E3_WINDOW_ACTIVATION}), so the tail's corpus units derive the
 * empty claim set with every op a recorded staged hand-off; R-CAPABILITY
 * is green (vacuous) and the manifest's plan-time
 * {@code FOUNDATION_VALUES} claim (routing) is untouched. At the E5
 * valid-until gate the pinned full corpus derives exactly claims
 * {@code {DESCRIPTORS, BOUNDARIES}} with per-unit deferrals for
 * {@code FOUNDATION_VALUES} ({@code CONST}/{@code STRING_CONCAT} without
 * {@code UNARY}/{@code BINARY}), {@code CONTAINERS_AND_STRINGS} (the six
 * container ops and {@code FOR_EACH} without
 * {@code INDEX_*}/{@code OPTIONAL_READ}/{@code HAS_FIELD}), and
 * {@code EVALUATION_ORDER} (the corpus's {@code BRANCH}/{@code DISCARD}
 * ops without {@code LOOP} — those op-side outcomes are recorded by E5's
 * producer under this shared mechanism).</p>
 *
 * <p>The seam is pure and deterministic: one pass over the produced op
 * set, capabilities in the closed declaration order, home rows in the
 * pinned mapping order — identical inputs produce identical derived
 * claims, outcomes, and failure details.</p>
 */
public final class ContainerClaimingSeam {

    /**
     * The producer fact-defect identifier of the E6005
     * {@code OPERATION_OUTSIDE_CLAIMED_CAPABILITY} arm (D9 item 3): the
     * single firing condition of the op-side check — an active home row
     * fully evidenced by the unit and left unclaimed.
     */
    public static final String OPERATION_OUTSIDE_CLAIMED_CAPABILITY =
        "OPERATION_OUTSIDE_CLAIMED_CAPABILITY";

    /**
     * The activation state during this epic's tail (E3's window, D9
     * items 1/4): {@code SIGNED_INT32} activated at E2's gate (all five
     * families are ISSUE-0231's); no E3 op homes to it, so every
     * E3-produced op's home row is inactive and the tail units derive the
     * empty claim set with every op a recorded staged hand-off.
     * {@code FOUNDATION_VALUES} activates at this epic's gate, after the
     * tail — a recorded post-tail release-registry hand-off, never an
     * in-window flip.
     */
    public static final Set<SemanticCapability> E3_WINDOW_ACTIVATION =
        Collections.unmodifiableSet(EnumSet.of(SemanticCapability.SIGNED_INT32));

    /**
     * The E3-gate (post-tail) activation hand-off (D9 item 5(a)):
     * {@code FOUNDATION_VALUES} activates — every newly produced unit from
     * then on undergoes the op-side check with it active: a unit fully
     * evidencing {@code {CONST, UNARY, BINARY, STRING_CONCAT}} must claim
     * it (else E6005), and a unit evidencing only a subset defers per
     * unit. Identical at every later gate.
     */
    public static final Set<SemanticCapability> E3_GATE_ACTIVATION =
        Collections.unmodifiableSet(EnumSet.of(SemanticCapability.SIGNED_INT32,
            SemanticCapability.FOUNDATION_VALUES));

    /**
     * The E4-gate activation hand-off (D9 item 5(b)):
     * {@code DESCRIPTORS}/{@code BOUNDARIES} activate; every unit
     * producing a lowerer-created boundary child fully evidences both
     * single-family rows and must claim both;
     * {@code FOUNDATION_VALUES}-partial units defer.
     */
    public static final Set<SemanticCapability> E4_GATE_ACTIVATION =
        Collections.unmodifiableSet(EnumSet.of(SemanticCapability.SIGNED_INT32,
            SemanticCapability.FOUNDATION_VALUES, SemanticCapability.DESCRIPTORS,
            SemanticCapability.BOUNDARIES));

    /**
     * The E5-gate activation hand-off (D9 item 5(c)):
     * {@code CONTAINERS_AND_STRINGS} and {@code EVALUATION_ORDER} activate.
     * The pinned full corpus derives exactly claims
     * {@code {DESCRIPTORS, BOUNDARIES}} with per-unit deferrals for
     * {@code FOUNDATION_VALUES}, {@code CONTAINERS_AND_STRINGS}, and
     * {@code EVALUATION_ORDER} (the corpus's {@code BRANCH}/
     * {@code DISCARD} ops without {@code LOOP}).
     */
    public static final Set<SemanticCapability> E5_GATE_ACTIVATION =
        Collections.unmodifiableSet(EnumSet.of(SemanticCapability.SIGNED_INT32,
            SemanticCapability.FOUNDATION_VALUES, SemanticCapability.DESCRIPTORS,
            SemanticCapability.BOUNDARIES, SemanticCapability.CONTAINERS_AND_STRINGS,
            SemanticCapability.EVALUATION_ORDER));

    /**
     * The E6-gate activation hand-off (D9 item 5(d)): {@code BINDINGS}
     * activates for {@code BINDING_LOAD} — units producing
     * {@code BINDING_LOAD} without the remaining {@code BINDINGS} families
     * defer per unit; a unit producing the full {@code BINDINGS} family
     * set must claim {@code BINDINGS}.
     */
    public static final Set<SemanticCapability> E6_GATE_ACTIVATION =
        Collections.unmodifiableSet(EnumSet.of(SemanticCapability.SIGNED_INT32,
            SemanticCapability.FOUNDATION_VALUES, SemanticCapability.DESCRIPTORS,
            SemanticCapability.BOUNDARIES, SemanticCapability.CONTAINERS_AND_STRINGS,
            SemanticCapability.EVALUATION_ORDER, SemanticCapability.BINDINGS));

    private ContainerClaimingSeam() {
        // Static entry points and the pinned gate activation states; no instances.
    }

    /**
     * The recorded op-side outcome of one produced op at one home row
     * (D9 item 3). Exactly one of the three pinned kinds on the pass
     * path:
     * <ul>
     *   <li>{@link #STAGED_HAND_OFF} — the home row is inactive; the
     *       unit's claim set excludes the row;</li>
     *   <li>{@link #CLAIMED} — the home row is active, the unit fully
     *       evidences it, and the unit carries the derived claim (the
     *       check passes);</li>
     *   <li>{@link #DEFERRED} — the home row is active but the unit does
     *       not fully evidence it: the per-unit claim deferral, the
     *       recorded terminal claim outcome for that immutable unit.</li>
     * </ul>
     */
    public enum OutcomeKind {

        /** Inactive home row: recorded staged hand-off, the row excluded from the unit's claims. */
        STAGED_HAND_OFF,

        /** Active home row, fully evidenced and claimed: the check passes. */
        CLAIMED,

        /** Active home row not fully evidenced: per-unit claim deferral, never a failure. */
        DEFERRED
    }

    /**
     * One recorded op-side outcome: the produced op's identity and kind,
     * the boundary kind for a {@code BOUNDARY} op ({@code null} for every
     * other kind), the home capability row, and the resolved outcome
     * kind. {@code boundaryKind} is non-null exactly when
     * {@code opKind == BOUNDARY}.
     *
     * @param opId         the produced op's id; non-null
     * @param opKind       the produced op's closed kind; non-null
     * @param boundaryKind the {@code BoundaryKind} of a {@code BOUNDARY}
     *                     op, {@code null} otherwise
     * @param home         the pinned home capability row of the op; non-null
     * @param outcome      the resolved outcome kind; non-null
     */
    public record RecordedOutcome(OpId opId, SemanticOpKind opKind, BoundaryKind boundaryKind,
                                  SemanticCapability home, OutcomeKind outcome) {

        public RecordedOutcome {
            Objects.requireNonNull(opId, "opId must not be null");
            Objects.requireNonNull(opKind, "opKind must not be null");
            if (opKind == SemanticOpKind.BOUNDARY) {
                Objects.requireNonNull(boundaryKind, "boundaryKind must not be null for a BOUNDARY op");
            }
            Objects.requireNonNull(home, "home must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
        }
    }

    /**
     * The seam's deterministic result: the derived claim set (the unit's
     * {@code requiredCapabilities} under the full-evidence derivation),
     * the recorded per-op, per-home outcomes in the pinned pass order,
     * and the single E6005 failure detail of the
     * {@code OPERATION_OUTSIDE_CLAIMED_CAPABILITY} firing condition —
     * {@code null} on the pass path, non-null exactly when an active home
     * row fully evidenced by the unit was left unclaimed.
     *
     * @param derivedClaims the full-evidence claim derivation over the
     *                      produced ops and the active rows; non-null
     * @param outcomes      the recorded outcomes in pinned pass order;
     *                      non-null
     * @param failure       the firing condition's exact
     *                      {@link LoweringFailureDetail}, or {@code null}
     *                      on the pass path
     */
    public record SeamResult(Set<SemanticCapability> derivedClaims,
                             List<RecordedOutcome> outcomes,
                             LoweringFailureDetail failure) {

        public SeamResult {
            Objects.requireNonNull(derivedClaims, "derivedClaims must not be null");
            Objects.requireNonNull(outcomes, "outcomes must not be null");
        }
    }

    /**
     * The pinned home capability rows of one produced op (D9 item 3).
     * Ops of every other kind (and {@code BOUNDARY} ops of every other
     * boundary kind) have no home row in this seam — their op-side claim
     * outcomes are recorded by their producing construct epic's producer
     * under this same shared mechanism. The returned list preserves the
     * pinned mapping order ({@code DESCRIPTORS} before {@code BOUNDARIES}
     * for a boundary child).
     *
     * @param op the produced op; non-null
     * @return the home rows in pinned order (empty when the op has no
     *         home row in this seam)
     */
    public static List<SemanticCapability> homeRows(SemanticOp op) {
        Objects.requireNonNull(op, "op must not be null");
        return switch (op.kind()) {
            case CONST, STRING_CONCAT -> List.of(SemanticCapability.FOUNDATION_VALUES);
            case ARRAY_NEW, TABLE_NEW, ARRAY_LENGTH, MEMBER_READ, FOR_EACH ->
                List.of(SemanticCapability.CONTAINERS_AND_STRINGS);
            case BOUNDARY -> switch (((KindPayload.BoundaryPayload) op.payload()).kind()) {
                case ARRAY_LITERAL_ELEMENT, CONTEXTUAL_TABLE_READ ->
                    List.of(SemanticCapability.DESCRIPTORS, SemanticCapability.BOUNDARIES);
                default -> List.of();
            };
            case BINDING_LOAD -> List.of(SemanticCapability.BINDINGS);
            default -> List.of();
        };
    }

    /**
     * The kind-level full-evidence predicate (D9 item 2): the unit
     * produces at least one op of every required family of the capability
     * — the closed {@link CapabilityRequirementCatalog} applied in
     * reverse, exactly as R-CAPABILITY consumes it. An empty-evidence row
     * ({@code STDLIB_TIME_CONFLICT}, a routing marker only, never valid
     * IR) is never fully evidenced — it can never be derived and can
     * never be claimed.
     *
     * @param ops        the unit's produced operations in source order; non-null
     * @param capability the closed capability row; non-null
     * @return true iff every required family has at least one produced op
     */
    public static boolean fullyEvidenced(List<SemanticOp> ops, SemanticCapability capability) {
        Objects.requireNonNull(ops, "ops must not be null");
        Objects.requireNonNull(capability, "capability must not be null");
        List<CapabilityRequirementCatalog.RequiredOperation> required =
            CapabilityRequirementCatalog.requiredOperations(capability);
        if (required.isEmpty()) {
            // STDLIB_TIME_CONFLICT → {}: a routing marker only, never valid
            // IR and never derivable (R-CAPABILITY rejects the claim on the
            // empty evidence set — the derivation never produces it).
            return false;
        }
        EnumSet<SemanticOpKind> produced = EnumSet.noneOf(SemanticOpKind.class);
        for (SemanticOp op : ops) {
            produced.add(op.kind());
        }
        for (CapabilityRequirementCatalog.RequiredOperation requiredOp : required) {
            boolean satisfied = false;
            for (SemanticOpKind kind : requiredOp.kinds()) {
                if (produced.contains(kind)) {
                    satisfied = true;
                    break;
                }
            }
            if (!satisfied) {
                return false;
            }
        }
        return true;
    }

    /**
     * The full-evidence claim derivation (D9 item 2): claim capability C
     * exactly when row(C) is active and the unit produces at least one op
     * of every required family of C. Deterministic: capabilities in the
     * closed declaration order. The derived set is what the producer
     * records as the unit's {@code requiredCapabilities}; R-CAPABILITY
     * holds by construction (claimed → evidenced, mechanically). A claim
     * without full evidence is impossible by this derivation: an inactive
     * row is never claimed, an under-evidenced row is never claimed, and
     * the empty-evidence routing marker {@code STDLIB_TIME_CONFLICT} is
     * never claimed.
     *
     * @param ops        the unit's produced operations in source order; non-null
     * @param activeRows the then-active capability claiming rows (the
     *                   release-registry activation hand-off); non-null
     * @return the derived claim set (unmodifiable; empty when no active
     *         row is fully evidenced)
     */
    public static Set<SemanticCapability> deriveClaims(List<SemanticOp> ops,
                                                       Set<SemanticCapability> activeRows) {
        Objects.requireNonNull(ops, "ops must not be null");
        Objects.requireNonNull(activeRows, "activeRows must not be null");
        EnumSet<SemanticCapability> claims = EnumSet.noneOf(SemanticCapability.class);
        for (SemanticCapability capability : SemanticCapability.values()) {
            if (!activeRows.contains(capability)) {
                continue; // staged: an inactive row is never claimed
            }
            if (CapabilityRequirementCatalog.requiredOperations(capability).isEmpty()) {
                continue; // the routing marker is never derivable (never valid IR)
            }
            if (fullyEvidenced(ops, capability)) {
                claims.add(capability);
            }
        }
        return Collections.unmodifiableSet(claims);
    }

    /**
     * The activation-gated op-side check (D9 item 3) with the per-unit
     * claim deferral: one deterministic pass over the produced op set,
     * deriving the full-evidence claim set and recording exactly one
     * outcome per produced op and per home row — a staged hand-off for an
     * inactive home row, a claimed outcome (pass) for an active home row
     * fully evidenced by the unit and carried in {@code unitClaims}, and
     * the per-unit claim deferral for an active home row the unit does not
     * fully evidence. The single E6005 firing condition is an active home
     * row fully evidenced by the unit and left unclaimed: the returned
     * {@link SeamResult#failure()} carries the exact
     * {@link LoweringFailureDetail} (validatorRule
     * {@link #OPERATION_OUTSIDE_CLAIMED_CAPABILITY}, capability = the home
     * row), the firing position's record is that failure detail, and the
     * pass continues recording the remaining positions' outcomes. A claim
     * of a row the derivation cannot derive (inactive or under-evidenced)
     * is a producer defect and fails closed with
     * {@link IllegalArgumentException} — a claim without full evidence is
     * impossible by the derivation.
     *
     * @param ops        the unit's produced operations in source order; non-null
     * @param activeRows the then-active capability claiming rows (the
     *                   release-registry activation hand-off); non-null
     * @param unitClaims the claim set the producer recorded on the unit;
     *                   non-null and a subset of the derived set
     * @param module     the module whose unit-production seam runs; non-null
     * @return the deterministic {@link SeamResult}: the derived claims,
     *         the recorded outcomes in pinned pass order, and the failure
     *         detail ({@code null} on the pass path)
     */
    public static SeamResult check(List<SemanticOp> ops, Set<SemanticCapability> activeRows,
                                   Set<SemanticCapability> unitClaims, ModuleId module) {
        Objects.requireNonNull(ops, "ops must not be null");
        Objects.requireNonNull(activeRows, "activeRows must not be null");
        Objects.requireNonNull(unitClaims, "unitClaims must not be null");
        Objects.requireNonNull(module, "module must not be null");
        Set<SemanticCapability> derived = deriveClaims(ops, activeRows);
        for (SemanticCapability claimed : unitClaims) {
            if (!derived.contains(claimed)) {
                throw new IllegalArgumentException("claim of " + claimed
                    + " without full evidence is impossible by the derivation (the row is "
                    + "inactive or the unit does not produce every required operation "
                    + "family) — a producer defect");
            }
        }
        List<RecordedOutcome> outcomes = new ArrayList<>();
        LoweringFailureDetail failure = null;
        for (SemanticOp op : ops) {
            for (SemanticCapability home : homeRows(op)) {
                if (!activeRows.contains(home)) {
                    outcomes.add(new RecordedOutcome(op.opId(), op.kind(), boundaryKindOf(op),
                        home, OutcomeKind.STAGED_HAND_OFF));
                    continue;
                }
                if (derived.contains(home)) {
                    if (unitClaims.contains(home)) {
                        outcomes.add(new RecordedOutcome(op.opId(), op.kind(),
                            boundaryKindOf(op), home, OutcomeKind.CLAIMED));
                        continue;
                    }
                    // The single firing condition: an active home row fully
                    // evidenced by the unit and left unclaimed. The position's
                    // record is the failure detail; the first firing position
                    // in the pinned pass order wins and the pass continues.
                    if (failure == null) {
                        failure = new LoweringFailureDetail(module.path(), home,
                            OPERATION_OUTSIDE_CLAIMED_CAPABILITY,
                            SemanticProfile.DEAL_V1_2_INT32, LoweredModuleUnit.FORMAT_VERSION,
                            "ContainerClaimingSeam " + OPERATION_OUTSIDE_CLAIMED_CAPABILITY
                                + " (active home row " + home + " fully evidenced by the "
                                + "unit and left unclaimed)");
                    }
                    continue;
                }
                outcomes.add(new RecordedOutcome(op.opId(), op.kind(), boundaryKindOf(op),
                    home, OutcomeKind.DEFERRED));
            }
        }
        return new SeamResult(derived, List.copyOf(outcomes), failure);
    }

    /** The boundary kind of a {@code BOUNDARY} op, {@code null} otherwise. */
    private static BoundaryKind boundaryKindOf(SemanticOp op) {
        if (op.kind() == SemanticOpKind.BOUNDARY) {
            return ((KindPayload.BoundaryPayload) op.payload()).kind();
        }
        return null;
    }
}
