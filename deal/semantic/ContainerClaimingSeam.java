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

public final class ContainerClaimingSeam {

    /**
     * The producer fact-defect identifier of the E6005
     * {@code OPERATION_OUTSIDE_CLAIMED_CAPABILITY} arm (D9 item 3): the
     * single firing condition of the op-side check — an active home row
     * fully evidenced by the unit and left unclaimed.
     */
    public static final String OPERATION_OUTSIDE_CLAIMED_CAPABILITY =
        "OPERATION_OUTSIDE_CLAIMED_CAPABILITY";

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

    public static final Set<SemanticCapability> E9_GATE_ACTIVATION =
        Collections.unmodifiableSet(EnumSet.of(SemanticCapability.SIGNED_INT32,
            SemanticCapability.FOUNDATION_VALUES, SemanticCapability.DESCRIPTORS,
            SemanticCapability.BOUNDARIES, SemanticCapability.CONTAINERS_AND_STRINGS,
            SemanticCapability.EVALUATION_ORDER, SemanticCapability.BINDINGS,
            SemanticCapability.CLASSES));

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
     */
    public record SeamResult(Set<SemanticCapability> derivedClaims,
                             List<RecordedOutcome> outcomes,
                             LoweringFailureDetail failure) {

        public SeamResult {
            Objects.requireNonNull(derivedClaims, "derivedClaims must not be null");
            Objects.requireNonNull(outcomes, "outcomes must not be null");
        }
    }

    public static List<SemanticCapability> homeRows(SemanticOp op) {
        Objects.requireNonNull(op, "op must not be null");
        return switch (op.kind()) {
            case CONST, STRING_CONCAT -> List.of(SemanticCapability.FOUNDATION_VALUES);
            case ARRAY_NEW, TABLE_NEW, ARRAY_LENGTH, MEMBER_READ, FOR_EACH ->
                List.of(SemanticCapability.CONTAINERS_AND_STRINGS);
            // The CONTAINERS_AND_STRINGS extras (the step-1 cutover): the
            // OPTIONAL_READ envelope op homes to CONTAINERS_AND_STRINGS
            // exactly like the other container read/creation families.
            case OPTIONAL_READ -> List.of(SemanticCapability.CONTAINERS_AND_STRINGS);
            case BOUNDARY -> switch (((KindPayload.BoundaryPayload) op.payload()).kind()) {
                case ARRAY_LITERAL_ELEMENT, CONTEXTUAL_TABLE_READ,
                     UNTYPED_CLASS_INPUT, OPTIONAL_FIELD_READ, CLASS_FIELD_ASSIGNMENT,
                     CLASS_LITERAL_FIELD, CLASS_DEFAULT_FIELD ->
                    List.of(SemanticCapability.DESCRIPTORS, SemanticCapability.BOUNDARIES);
                default -> List.of();
            };
            case BINDING_LOAD -> List.of(SemanticCapability.BINDINGS);
            case BRANCH, LOOP, DISCARD -> List.of(SemanticCapability.EVALUATION_ORDER);
            case STDLIB_CALL -> List.of(SemanticCapability.STDLIB_SEMANTICS);

            case CLASS_NEW, CLASS_FACTORY, CLASS_DEFAULT, FIELD_READ, FIELD_WRITE,
                 FIELD_DELETE, JSON_FROM_CLASS, JSON_TO_CLASS ->
                List.of(SemanticCapability.CLASSES);
            case HAS_FIELD -> List.of(SemanticCapability.CONTAINERS_AND_STRINGS);
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
