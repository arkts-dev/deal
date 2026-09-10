package deal.semantic;

import deal.semantic.ir.StdlibFunctionId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The target-helper equivalence gate of {@code deal.semantic-ir/1}
 * ({@code stdlib-operations-and-time-lock} D7, Contracts §Target-helper
 * equivalence gate): the closed verdict registry that is the only
 * admission path for wiring a retained/target stdlib helper into a
 * SHARED emitter, and the {@code STDLIB_SEMANTICS} promotion evidence
 * surface (D9: the capability promotes only when every id has an exact
 * common case, the equivalence battery passes, and the integration
 * verification task passes).
 *
 * <p><b>The closed candidate set.</b> {@link #closedCandidates()} names
 * every retained helper candidate the equivalence battery must run —
 * the retained Lua {@code std/*.lua} modules, the retained JS
 * {@code std/*.js} modules, and the JVM backend's emitted stdlib
 * helpers ({@code deal/codegen/jvm/JvmBackend.java} stdlib member
 * calls), one candidate per
 * {@code (lane, resolved module path, export name, StdlibFunctionId)}.
 * The set derives from the closed {@link StdlibFunctionCatalog} (the
 * single module/name authority — no consumer interprets a module/name
 * pair outside it): the two retained lanes cover all 20 catalog rows;
 * the JVM lane covers the 18 rows outside {@code std/json}
 * ({@code JSON_PARSE}/{@code JSON_STRINGIFY}), whose E6000 position is
 * <em>not</em> an equivalence candidate ({@link #exclusions()}).
 * {@code std/time} has no catalog row (D8) and no candidate.</p>
 *
 * <p><b>Verdicts are produced by running the comparison, never
 * hardcoded.</b> The equivalence battery (the integration-verification
 * consumer) executes every candidate against
 * {@link SharedStdlibSemantics} plus the projection wiring on the full
 * declared input domain — result values, console effect bytes, and
 * failure projections, including every edge case of the algorithm
 * battery — and calls {@link #record} once per candidate. A verdict is
 * {@code VERIFIED_EQUIVALENT} only when every battery case matched;
 * any detected divergence (order, int mapping, trim set, projection,
 * or permissiveness) makes it {@code DIVERGENT} with the exact
 * divergence seeds recorded. The known retained divergences — Lua/JS
 * {@code table.keys} and Lua {@code json.stringify} iteration order
 * versus common first-insertion order, retained {@code json.parse}
 * value-based int mapping versus common signed32 integer lexical
 * forms — must be detected by the comparison; the battery never
 * hardcodes them silently.</p>
 *
 * <p><b>Wiring rule (D7).</b> SHARED emitters realize
 * {@code STDLIB_CALL} through the common algorithm by default; a
 * target helper is admissible only through
 * {@link #isVerifiedEquivalent(Candidate)}; no emitter dispatches on a
 * module/name pair; a {@code DIVERGENT}, {@code NOT_CANDIDATE}, or
 * not-yet-batteried candidate is never wirable.</p>
 *
 * <p><b>Promotion evidence (D9).</b> {@link #completeCoverage()}
 * requires a recorded verdict for every closed candidate;
 * {@link #batteryEvidence()} is the immutable verdict snapshot the
 * promotion gate consumes. The registry is in-process, deterministic,
 * and carries no target knowledge beyond the named lanes.</p>
 */
public final class StdlibHelperEquivalence {

    private StdlibHelperEquivalence() {
        // Static surface only; the registry is closed data.
    }

    // =========================================================================
    // Lanes and candidates
    // =========================================================================

    /** The retained/target helper lanes the battery compares. */
    public enum Lane {
        /** The retained Lua {@code std/*.lua} modules (LuaJIT lane). */
        RETAINED_LUA,
        /** The retained JS {@code std/*.js} modules (JS lane). */
        RETAINED_JS,
        /** The JVM backend's emitted stdlib helpers ({@code JvmBackend} member calls). */
        JVM_EMITTED
    }

    /** One closed helper candidate: the lane, the catalog module/export pair, and the id. */
    public record Candidate(Lane lane, String modulePath, String exportName,
                            StdlibFunctionId function) {

        public Candidate {
            Objects.requireNonNull(lane, "lane must not be null");
            Objects.requireNonNull(modulePath, "modulePath must not be null");
            Objects.requireNonNull(exportName, "exportName must not be null");
            Objects.requireNonNull(function, "function must not be null");
        }
    }

    /** The closed verdict kinds of the equivalence gate. */
    public enum VerdictKind {
        /** No battery record yet — never wirable, never promotion evidence. */
        NOT_BATTERIED,
        /** Every battery case of the candidate matched the common operation. */
        VERIFIED_EQUIVALENT,
        /** At least one battery case diverged; the record names the seeds. */
        DIVERGENT,
        /** Outside the candidate domain by a named closed exclusion — never wirable. */
        NOT_CANDIDATE
    }

    /**
     * One battery-produced verdict record: the candidate, the kind, the
     * exact detail text (equivalence domain or first divergence), the
     * battery case count, and the divergence seeds that flipped the
     * verdict (empty for an equivalent or non-candidate record).
     */
    public record VerdictRecord(Candidate candidate, VerdictKind kind, String detail,
                                int casesRun, List<String> divergenceSeeds) {

        public VerdictRecord {
            Objects.requireNonNull(candidate, "candidate must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(detail, "detail must not be null");
            if (casesRun < 0) {
                throw new IllegalArgumentException("casesRun must be >= 0");
            }
            divergenceSeeds = List.copyOf(divergenceSeeds);
            if (kind == VerdictKind.VERIFIED_EQUIVALENT && !divergenceSeeds.isEmpty()) {
                throw new IllegalArgumentException(
                    "a VERIFIED_EQUIVALENT record carries no divergence seeds");
            }
        }
    }

    /** One closed candidate-domain exclusion with its pinned reason. */
    public record Exclusion(Candidate candidate, String reason) {

        public Exclusion {
            Objects.requireNonNull(candidate, "candidate must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    // =========================================================================
    // The closed candidate set (derived from the single catalog, D7)
    // =========================================================================

    private static final List<Candidate> CLOSED_CANDIDATES = buildCandidates();
    private static final List<Exclusion> CLOSED_EXCLUSIONS = buildExclusions();

    private static List<Candidate> buildCandidates() {
        List<Candidate> candidates = new ArrayList<>();
        for (StdlibFunctionCatalog.Entry entry : StdlibFunctionCatalog.entries()) {
            candidates.add(new Candidate(Lane.RETAINED_LUA, entry.modulePath(),
                entry.exportName(), entry.function()));
            candidates.add(new Candidate(Lane.RETAINED_JS, entry.modulePath(),
                entry.exportName(), entry.function()));
            if (!"std.json".equals(entry.modulePath())) {
                candidates.add(new Candidate(Lane.JVM_EMITTED, entry.modulePath(),
                    entry.exportName(), entry.function()));
            }
        }
        Set<Candidate> unique = new java.util.HashSet<>(candidates.size());
        for (Candidate candidate : candidates) {
            if (!unique.add(candidate)) {
                throw new IllegalStateException(
                    "duplicate closed helper candidate " + candidate
                        + " (producer defect: the closed candidate set is exactly one "
                        + "entry per (lane, module path, export name))");
            }
        }
        return List.copyOf(candidates);
    }

    private static List<Exclusion> buildExclusions() {
        List<Exclusion> exclusions = new ArrayList<>();
        for (String exportName : List.of("parse", "stringify")) {
            StdlibFunctionId function = StdlibFunctionCatalog
                .lookup("std.json", exportName)
                .map(StdlibFunctionCatalog.Entry::function)
                .orElse(null);
            if (function != null) {
                exclusions.add(new Exclusion(
                    new Candidate(Lane.JVM_EMITTED, "std.json", exportName, function),
                    "the JVM std/json position keeps its own corpus-pinned surface "
                        + "(ISSUE-0302: the import compiles over the emitted shared "
                        + "JSON runtime) and is not an equivalence battery candidate — "
                        + "the retained Lua/JS helpers are the comparison lanes "
                        + "(stdlib-operations-and-time-lock D7)"));
            }
        }
        return List.copyOf(exclusions);
    }

    // =========================================================================
    // The verdict table (written by the equivalence battery only)
    // =========================================================================

    private static final Map<Candidate, VerdictRecord> VERDICTS =
        Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * Records one battery-produced verdict for a closed candidate. The
     * equivalence battery is the only writer by construction: the
     * verdict is the result of actually running the comparison, never a
     * hardcoded claim. Recording a candidate outside the closed set
     * fails closed.
     *
     * @param candidate       the closed candidate; non-null
     * @param kind            the verdict kind (never {@code NOT_BATTERIED});
     *                        non-null
     * @param detail          the exact detail text; non-null
     * @param casesRun        the battery case count run for the candidate
     * @param divergenceSeeds the divergence seeds (empty unless
     *                        {@code DIVERGENT}); non-null
     * @return the recorded verdict
     * @throws IllegalArgumentException if the candidate is outside the
     *                                  closed set, the kind is
     *                                  {@code NOT_BATTERIED}, or a
     *                                  {@code VERIFIED_EQUIVALENT}
     *                                  record carries divergence seeds
     */
    public static synchronized VerdictRecord record(Candidate candidate, VerdictKind kind,
                                                    String detail, int casesRun,
                                                    List<String> divergenceSeeds) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        if (kind == VerdictKind.NOT_BATTERIED) {
            throw new IllegalArgumentException(
                "record() never writes NOT_BATTERIED: the battery records a real "
                    + "verdict for every candidate it runs");
        }
        if (!CLOSED_CANDIDATES.contains(candidate)) {
            throw new IllegalArgumentException(
                "candidate " + candidate + " is outside the closed helper-candidate "
                    + "set: the verdict registry admits only the closed candidates");
        }
        VerdictRecord record =
            new VerdictRecord(candidate, kind, detail, casesRun, divergenceSeeds);
        VERDICTS.put(candidate, record);
        return record;
    }

    /**
     * Returns the recorded verdict of a candidate, or {@code null} when
     * no battery record exists ({@code NOT_BATTERIED}).
     */
    public static synchronized VerdictRecord verdict(Candidate candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        return VERDICTS.get(candidate);
    }

    /**
     * Clears every recorded verdict (test-only: the battery re-runs from
     * a clean table; production never resets promotion evidence).
     */
    public static synchronized void reset() {
        VERDICTS.clear();
    }

    // =========================================================================
    // Coverage and promotion evidence
    // =========================================================================

    /**
     * True when every closed candidate carries a recorded verdict — the
     * battery ran the complete candidate set (D7/Contracts §Target-helper
     * equivalence gate).
     */
    public static synchronized boolean completeCoverage() {
        for (Candidate candidate : CLOSED_CANDIDATES) {
            if (!VERDICTS.containsKey(candidate)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The immutable verdict evidence snapshot for the
     * {@code STDLIB_SEMANTICS} promotion gate (D9): one record per
     * recorded candidate in closed-set order.
     */
    public static synchronized List<VerdictRecord> batteryEvidence() {
        List<VerdictRecord> evidence = new ArrayList<>();
        for (Candidate candidate : CLOSED_CANDIDATES) {
            VerdictRecord record = VERDICTS.get(candidate);
            if (record != null) {
                evidence.add(record);
            }
        }
        return List.copyOf(evidence);
    }

    // =========================================================================
    // The wiring admission rule (D7)
    // =========================================================================

    /**
     * The only admission path for wiring a target helper into a SHARED
     * emitter (D7): true exactly when the battery recorded
     * {@code VERIFIED_EQUIVALENT} for the candidate. A divergent,
     * non-candidate, or not-yet-batteried helper is never wirable, and
     * SHARED emitters realize {@code STDLIB_CALL} through the common
     * algorithm by default — never by dispatching on a module/name pair.
     */
    public static boolean isVerifiedEquivalent(Candidate candidate) {
        VerdictRecord record = verdict(candidate);
        return record != null && record.kind() == VerdictKind.VERIFIED_EQUIVALENT;
    }

    /**
     * The wiring predicate for one {@code (lane, id)}: true only when
     * the candidate's battery verdict is {@code VERIFIED_EQUIVALENT}.
     * A {@code (lane, id)} outside the closed candidate set (the JVM
     * {@code std/json} E6000 position, any {@code std/time} member)
     * is never wirable.
     */
    public static boolean isWirable(Lane lane, StdlibFunctionId function) {
        Objects.requireNonNull(lane, "lane must not be null");
        Objects.requireNonNull(function, "function must not be null");
        for (Candidate candidate : CLOSED_CANDIDATES) {
            if (candidate.lane() == lane && candidate.function() == function) {
                return isVerifiedEquivalent(candidate);
            }
        }
        return false;
    }

    // =========================================================================
    // Static surface
    // =========================================================================

    /** The closed candidate set in the pinned build order (58 candidates). */
    public static List<Candidate> closedCandidates() {
        return CLOSED_CANDIDATES;
    }

    /** The closed candidate-domain exclusions with their pinned reasons. */
    public static List<Exclusion> exclusions() {
        return CLOSED_EXCLUSIONS;
    }
}
