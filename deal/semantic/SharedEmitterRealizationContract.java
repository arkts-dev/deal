package deal.semantic;

import deal.semantic.ir.StructuredBodyTable;

import java.util.List;
import java.util.Objects;

/**
 * The shared-emitter realization contract of {@code deal.semantic-ir/1}
 * (ISSUE-0410 — the {@code EVALUATION_ORDER} epic's decomposition tail;
 * assignment-delete-address-chains "Shared-emitter realization
 * obligations" and A-D2/A-D5/A-D8, binary-comparison-selectors B-D6,
 * control-flow-structures C-D9): the closed obligation set every shared
 * emitter — shared LuaJIT and shared JVM (ISSUE-0239's emitters) — must
 * realize over this epic's validated units, and the verification form of
 * each obligation.
 *
 * <p><b>Ownership split (pinned).</b> The emission carrier seam that
 * hands the validated {@code LoweredModuleUnit} plus its
 * {@link StructuredBodyTable} to the emitters is ISSUE-0239's. The
 * obligations below are pinned by this epic and are verified by
 * trace/effect comparison under
 * {@code semantic-lowering-differential-conformance}: every obligation
 * row names its closed contract, its exact prohibitions, and the
 * trace/effect form that detects a violation. A violation is a claimed
 * common contract broken — E6005 territory or a failed differential
 * verdict, never a silently tolerated divergence.</p>
 *
 * <p><b>Chains (A-D2/A-D5/A-D8).</b> The emitter consumes the validated
 * {@code ASSIGN}/{@code DELETE} payloads ({@code AddressChainProtocol}
 * has already closed the shapes): each chain child's result must be
 * materialized into a fresh local in payload order before the next
 * child executes (receiver → key → RHS → normalize → boundary →
 * commit); the bounds check is emitted only from the boundary child's
 * projection (never a target-side re-check that can raise a second
 * time); a receiver/key/RHS expression is never re-emitted; and the
 * retained Lua {@code emitAssignment} shape
 * ({@code deal/codegen/lua/LuaBackend.java:2366-2406} — whole-target
 * evaluation, receiver re-emitted at :2378 and index at :2379, bounds
 * check before the RHS at :2395-2399) never appears in shared units: a
 * duplicated evaluation emits duplicated effects that fail effect/trace
 * pairing and sequence against the single validated op. Exactly one
 * commit op runs, always last, never re-evaluating (A-D5).</p>
 *
 * <p><b>Comparisons (B-D6).</b> Shared LuaJIT: native
 * {@code ==}/{@code ~=}/{@code <}/{@code <=}/{@code >}/{@code >=} on
 * numbers realize IEEE semantics; on validated scalar strings, UTF-8
 * byte order equals code point order, so native relationals realize
 * scalar-lexicographic order; table/function identity via native
 * equality. Shared JVM: int via primitive comparison; number EQ via
 * IEEE {@code ==} (never {@code Double.compare} for EQ) and orderings
 * via {@code <}/{@code <=}/{@code >}/{@code >=} predicates (NaN false);
 * string order via code point comparison (never
 * {@code String.compareTo}); reference identity via {@code ==} (never
 * {@code equals()}). The trace snapshot reports the validated selector
 * regardless of the target construct used.</p>
 *
 * <p><b>Control flow (C-D9).</b> No speculative execution of any
 * body/update/block; condition ops are emitted inside the loop
 * structure and re-evaluated per iteration; the {@code FOR_EACH}
 * iterable is materialized into a local before the loop (evaluated
 * exactly once); the short-circuited block is emitted behind a
 * guard/closure so its effects cannot run when skipped; FOR's continue
 * landing sits before the update; block code is generated per the
 * {@link StructuredBodyTable} membership; catches are limited to DEAL
 * errors (infrastructure failures are never caught or reified).</p>
 *
 * <p>The contract is closed, deterministic, and pure data: it adds no
 * dependency beyond the schema and the structured-table record, carries
 * no target knowledge, no AST, and no checker state, and it is consumed
 * by the emitter seam (ISSUE-0239), the differential harness
 * (ISSUE-0240), and this epic's integration verification tail.</p>
 */
public final class SharedEmitterRealizationContract {

    private SharedEmitterRealizationContract() {
        // Static surface only; the contract is closed data.
    }

    /** The obligation domains of this epic's units. */
    public enum Domain {
        /** ASSIGN/DELETE address chains (assignment-delete-address-chains A-D2/A-D5/A-D8). */
        ADDRESS_CHAINS,
        /** Comparison selectors (binary-comparison-selectors B-D6). */
        COMPARISONS,
        /** Control-flow structures (control-flow-structures C-D9). */
        CONTROL_FLOW
    }

    /**
     * One closed obligation row: the obligation identifier, its domain,
     * its contract sentence, its exact pinned prohibitions, and the
     * trace/effect verification form that detects a violation.
     */
    public record Obligation(String id, Domain domain, String contract,
                             List<String> prohibitions, String verification) {

        public Obligation {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(domain, "domain must not be null");
            Objects.requireNonNull(contract, "contract must not be null");
            Objects.requireNonNull(prohibitions, "prohibitions must not be null");
            Objects.requireNonNull(verification, "verification must not be null");
            prohibitions = List.copyOf(prohibitions);
        }
    }

    // =========================================================================
    // Obligation identifiers (closed; one row each)
    // =========================================================================

    /** Chains: fresh-local materialization per child in payload order. */
    public static final String CHAIN_FRESH_LOCAL_MATERIALIZATION =
        "CHAIN_FRESH_LOCAL_MATERIALIZATION";

    /** Chains: the bounds check only from the boundary child's projection. */
    public static final String CHAIN_BOUNDARY_SINGLE_PROJECTION =
        "CHAIN_BOUNDARY_SINGLE_PROJECTION";

    /** Chains: never re-emit a receiver/key/RHS expression. */
    public static final String CHAIN_NO_REEMISSION = "CHAIN_NO_REEMISSION";

    /** Chains: never reproduce the retained Lua emitAssignment shape. */
    public static final String CHAIN_NO_RETAINED_DOUBLE_EVALUATION =
        "CHAIN_NO_RETAINED_DOUBLE_EVALUATION";

    /** Chains: exactly one commit op, last, never re-evaluating. */
    public static final String CHAIN_EXACTLY_ONE_COMMIT_LAST =
        "CHAIN_EXACTLY_ONE_COMMIT_LAST";

    /** Comparisons: the shared LuaJIT realization. */
    public static final String COMPARISON_SHARED_LUA_NATIVE =
        "COMPARISON_SHARED_LUA_NATIVE";

    /** Comparisons: the shared JVM realization. */
    public static final String COMPARISON_SHARED_JVM_NATIVE =
        "COMPARISON_SHARED_JVM_NATIVE";

    /** Comparisons: the trace reports the validated selector. */
    public static final String COMPARISON_TRACE_SELECTOR_AUTHORITY =
        "COMPARISON_TRACE_SELECTOR_AUTHORITY";

    /** Control flow: no speculative execution. */
    public static final String CONTROL_NO_SPECULATIVE_EXECUTION =
        "CONTROL_NO_SPECULATIVE_EXECUTION";

    /** Control flow: condition ops inside the loop structure, re-evaluated
     *  per iteration. */
    public static final String CONTROL_CONDITION_INSIDE_LOOP =
        "CONTROL_CONDITION_INSIDE_LOOP";

    /** Control flow: the FOR_EACH iterable materialized once before the loop. */
    public static final String CONTROL_FOR_EACH_ITERABLE_ONCE =
        "CONTROL_FOR_EACH_ITERABLE_ONCE";

    /** Control flow: the short-circuited block behind a guard/closure. */
    public static final String CONTROL_SHORT_CIRCUIT_GUARD =
        "CONTROL_SHORT_CIRCUIT_GUARD";

    /** Control flow: FOR's continue landing before the update. */
    public static final String CONTROL_CONTINUE_BEFORE_UPDATE =
        "CONTROL_CONTINUE_BEFORE_UPDATE";

    /** Control flow: block code generated per the StructuredBodyTable. */
    public static final String CONTROL_BLOCK_CODE_FROM_TABLE =
        "CONTROL_BLOCK_CODE_FROM_TABLE";

    /** Control flow: catches limited to DEAL errors. */
    public static final String CONTROL_CATCH_DEAL_ONLY = "CONTROL_CATCH_DEAL_ONLY";

    // =========================================================================
    // The closed obligation list
    // =========================================================================

    /**
     * The closed obligation list in canonical order — the single
     * authoritative pin of this epic's shared-emitter realization
     * obligations. Deterministic and immutable; every row is one named
     * obligation with its exact prohibitions and its trace/effect
     * verification form.
     */
    public static List<Obligation> closed() {
        return List.of(
            new Obligation(
                CHAIN_FRESH_LOCAL_MATERIALIZATION,
                Domain.ADDRESS_CHAINS,
                "Every address-chain child's result is materialized into a fresh local in "
                    + "payload order before the next child executes — receiver → key → RHS → "
                    + "normalize → boundary → commit (VARIABLE: value → adapter child if any → "
                    + "VARIABLE_ASSIGNMENT boundary → commit).",
                List.of(
                    "never reorder chain children",
                    "never let a later child's evaluation observe an unmaterialized earlier "
                        + "child result",
                    "never reuse one local for two distinct chain children"),
                "Effect/trace sequence comparison: the ADDRESS_RECEIVER/ADDRESS_KEY/"
                    + "ADDRESS_VALUE/ADDRESS_NORMALIZE/ADDRESS_BOUNDARY/ADDRESS_COMMIT "
                    + "events must appear exactly in payload order with each child's "
                    + "producing events completing before the next child's START."),
            new Obligation(
                CHAIN_BOUNDARY_SINGLE_PROJECTION,
                Domain.ADDRESS_CHAINS,
                "The array bounds check is emitted only from the boundary child's projection "
                    + "(ARRAY_ELEMENT_ASSIGNMENT with ARRAY_WRITE_BOUNDS_THEN_ELEMENT; "
                    + "ARRAY_ELEMENT_DELETE with ARRAY_DELETE_BOUNDS) — never a target-side "
                    + "re-check that can raise a second time.",
                List.of(
                    "never emit a second bounds test at the commit site",
                    "never synthesize an E8002 outside the boundary child",
                    "never duplicate the <0 or >length predicate"),
                "A seed with a failing bounds index produces exactly one E8002 event from "
                    + "the boundary child's FAILURE; a duplicated target-side check emits a "
                    + "second E8002 that fails event pairing."),
            new Obligation(
                CHAIN_NO_REEMISSION,
                Domain.ADDRESS_CHAINS,
                "A receiver, key, or RHS expression is never re-emitted: each producing "
                    + "child appears exactly once in the unit and is emitted exactly once by "
                    + "the emitter.",
                List.of(
                    "never re-emit a receiver/key/RHS expression",
                    "never evaluate any chain child more than once"),
                "Single evaluation via AddressChainProtocol (SINGLE_EVALUATION) plus "
                    + "effect/trace comparison: a re-emitted expression emits duplicated "
                    + "effects that fail pairing/sequence against the single validated op."),
            new Obligation(
                CHAIN_NO_RETAINED_DOUBLE_EVALUATION,
                Domain.ADDRESS_CHAINS,
                "The retained Lua emitAssignment shape never appears in shared units: the "
                    + "whole target is not evaluated and then re-emitted, and the bounds "
                    + "check never runs before the RHS.",
                List.of(
                    "never reproduce deal/codegen/lua/LuaBackend.java:2366-2406 "
                        + "(whole-target evaluation at :2369, receiver re-emitted at :2378, "
                        + "index at :2379, bounds check before the RHS at :2395-2399)",
                    "never run the write check before the RHS completes",
                    "never share the retained double-evaluation shape with retained paths"),
                "The retained double-evaluation detector: a duplicated evaluation emits "
                    + "duplicated effects that fail effect/trace pairing and sequence; the "
                    + "shared unit is validated with every producing child exactly once."),
            new Obligation(
                CHAIN_EXACTLY_ONE_COMMIT_LAST,
                Domain.ADDRESS_CHAINS,
                "Every chain ends with exactly one commit op (BINDING_STORE, MEMBER_WRITE/"
                    + "DELETE, INDEX_WRITE/DELETE, FIELD_WRITE/DELETE), always last, that "
                    + "consumes only the resolved receiver/key/slot values and never "
                    + "re-evaluates a source expression.",
                List.of(
                    "never emit more than one commit per chain",
                    "never place the commit before the boundary",
                    "never let the commit re-read or re-evaluate receiver/key/RHS"),
                "AddressChainProtocol shape validation plus effect/trace comparison: "
                    + "exactly one ADDRESS_COMMIT event per chain, after every other "
                    + "child, with commit operands equal to the resolved child results."),
            new Obligation(
                COMPARISON_SHARED_LUA_NATIVE,
                Domain.COMPARISONS,
                "Shared LuaJIT realizes the B-D2 comparison table with native operators: "
                    + "native ==/~=/</<=/>/>= on numbers realize IEEE semantics; on "
                    + "validated scalar strings, UTF-8 byte order equals code point order, "
                    + "so native relationals realize scalar-lexicographic order; "
                    + "table/function identity via native equality.",
                List.of(
                    "never implement a per-character Lua loop for string order",
                    "never coerce operands across types",
                    "never compare references structurally"),
                "The B-D2 seed matrix on the shared LuaJIT consumer: NaN rows, -0.0 rows, "
                    + "supplementary-character order rows, and identity rows match the "
                    + "ComparisonExecutor exactly, and the trace reports the validated "
                    + "selector."),
            new Obligation(
                COMPARISON_SHARED_JVM_NATIVE,
                Domain.COMPARISONS,
                "Shared JVM realizes the B-D2 comparison table with native constructs: int "
                    + "via primitive comparison; number EQ via IEEE == (never "
                    + "Double.compare for EQ) and orderings via </<=/>/>= predicates (NaN "
                    + "false); string order via code point comparison (never "
                    + "String.compareTo); reference identity via == (never equals()).",
                List.of(
                    "never Double.compare for number EQ",
                    "never Double.compare for number orderings",
                    "never String.compareTo for string order",
                    "never equals() for reference identity"),
                "JVM negatives prove the prohibitions: NaN === NaN is false (Double.compare "
                    + "would say equal), -0.0 < 0.0 is false (Double.compare would say "
                    + "true), a supplementary-vs-BMP string pair orders by code point "
                    + "(String.compareTo orders by UTF-16 code units), and two distinct "
                    + "allocations of equal shape compare NE (equals() would say equal)."),
            new Obligation(
                COMPARISON_TRACE_SELECTOR_AUTHORITY,
                Domain.COMPARISONS,
                "The trace snapshot reports the validated BINARY selector regardless of the "
                    + "target construct used to realize it.",
                List.of(
                    "never report a target-specific selector name",
                    "never omit the selector from the contract snapshot",
                    "never substitute the native operator for the validated selector"),
                "Operation-contract snapshot validation: the event's selector must equal "
                    + "the validated unit's selector; a wrong-selector trace fails even "
                    + "with coincidental output."),
            new Obligation(
                CONTROL_NO_SPECULATIVE_EXECUTION,
                Domain.CONTROL_FLOW,
                "No loop body, loop update, or short-circuited block ever executes "
                    + "speculatively.",
                List.of(
                    "never pre-execute a body/update before its condition",
                    "never execute the skipped side of a short circuit",
                    "never speculate for any target optimization"),
                "Effect/trace comparison: a skipped block contributes zero events; a "
                    + "speculative execution emits observable effects that fail the "
                    + "expected-effect projection."),
            new Obligation(
                CONTROL_CONDITION_INSIDE_LOOP,
                Domain.CONTROL_FLOW,
                "Condition ops are emitted inside the loop structure and re-evaluated per "
                    + "iteration — never hoisted out of the loop.",
                List.of(
                    "never hoist condition ops above the loop",
                    "never reuse a pre-loop condition value across iterations"),
                "A counting condition seed prints per iteration; a hoisted condition "
                    + "emits exactly one producing trace event while the validated unit "
                    + "requires one production per iteration."),
            new Obligation(
                CONTROL_FOR_EACH_ITERABLE_ONCE,
                Domain.CONTROL_FLOW,
                "The FOR_EACH iterable is materialized into a local before the loop — "
                    + "evaluated exactly once.",
                List.of(
                    "never re-evaluate the iterable per iteration",
                    "never re-read the iterable binding inside the loop"),
                "A printing iterable seed prints exactly once; the validated unit carries "
                    + "exactly one producing prior step for the iterable operand."),
            new Obligation(
                CONTROL_SHORT_CIRCUIT_GUARD,
                Domain.CONTROL_FLOW,
                "The short-circuited block is emitted behind a guard/closure so its effects "
                    + "cannot run when the left operand decides the result.",
                List.of(
                    "never emit the right operand's ops unconditionally",
                    "never evaluate the right operand's value before the guard"),
                "The false && sideEffect() / true || sideEffect() seeds produce zero "
                    + "effect events for the skipped block on every shared emitter."),
            new Obligation(
                CONTROL_CONTINUE_BEFORE_UPDATE,
                Domain.CONTROL_FLOW,
                "FOR's continue landing sits before the update: a CONTINUE executes the "
                    + "updateBlock and then re-tests — the retained landing-pad precedent, "
                    + "never a jump past the update.",
                List.of(
                    "never land continue after the update",
                    "never skip the update on continue"),
                "A side-effecting update seed proves the update runs exactly once per "
                    + "continue before the re-test; the trace shows update events between "
                    + "the CONTINUE terminal and the next condition production."),
            new Obligation(
                CONTROL_BLOCK_CODE_FROM_TABLE,
                Domain.CONTROL_FLOW,
                "Block code is generated per the StructuredBodyTable membership — each "
                    + "block's ops in list order, never re-derived from op-list contiguity "
                    + "or source adjacency.",
                List.of(
                    "never infer block membership from the op list",
                    "never emit an op outside its table block",
                    "never drop or duplicate a block op"),
                "The produced StructuredBodyTable passes ControlFlowValidator, and every "
                    + "emitted block op's events parent to the structure op that owns its "
                    + "block."),
            new Obligation(
                CONTROL_CATCH_DEAL_ONLY,
                Domain.CONTROL_FLOW,
                "Catches are limited to DEAL failures: INFRASTRUCTURE_ONLY failures and "
                    + "non-DEAL target/harness errors are never caught and never reified "
                    + "as Error values.",
                List.of(
                    "never reify an infrastructure failure as E8001",
                    "never catch a non-DEAL target exception",
                    "never synthesize an Error value for an uncaught host failure"),
                "An infrastructure-failure seed escapes outside the DEAL result on every "
                    + "shared emitter with zero catch-block events; only E8 DEAL failures "
                    + "produce the reified Error binding.")
        );
    }

    /**
     * Returns the obligation with the given identifier, or {@code null}
     * if the identifier is not one of the closed obligation ids.
     */
    public static Obligation byId(String id) {
        Objects.requireNonNull(id, "id must not be null");
        for (Obligation obligation : closed()) {
            if (obligation.id().equals(id)) {
                return obligation;
            }
        }
        return null;
    }
}
