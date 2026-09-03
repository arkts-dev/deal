// =========================================================================
// LegacyProfileRegressionCatalog — the closed legacy regression authority (A4)
// =========================================================================

package deal.test;

import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The closed, release-owned legacy-profile regression authority (A4):
 * one row per safe-int-era assertion locator with purpose
 * {@code LEGACY_REGRESSION}, profile {@code LEGACY_SAFE_INT}, authority
 * {@code legacy-regression}, credit {@code none}, and the mandatory
 * additive v1.2 replacement locator (absent only for the excluded
 * families — the slice-level time lock and the JS v1.2 exclusion; the
 * backend-runtime time fixture is no longer catalogued after the
 * disposition-application unit flipped it to {@code runtime-error E8004}).
 * The three conformance runners consume the catalog per case (A5): a
 * catalogued case resolves the legacy regression invocation with its
 * source and expectations unchanged, every other case and every
 * frontend suite
 * resolves {@code COMMON_SHADOW + DEAL_V1_2_INT32}, and the JS retained
 * route ignores the profile.
 *
 * <p>Completeness rule (closed): every assertion whose fixture source
 * produces E1036 under the v1.2 parse contract (an int literal outside
 * {@code [-2147483648, 2147483647]} outside the {@code -2147483648}
 * immediate-token position), or whose expected result/error — including
 * a runtime-ok exit-0 expectation — depends on the ±(2^53−1) range, or
 * whose expected message pins a legacy template, must have a catalog
 * row. An uncatalogued legacy-dependent assertion fails loudly under the
 * v1.2 invocation (its source raises E1036 or its expectation diverges),
 * and the runners' startup validation names the omission — an omission
 * can never silently weaken a pin.</p>
 *
 * <p>The catalog validates at harness startup (A4 contract): every row
 * locator must resolve (an unknown locator is a harness defect), the
 * closed completeness scan must find every E1036-bearing fixture source
 * catalogued (unless the fixture's own expectation is the v1.2-positive
 * E1036 pin), and the mechanism self-probes prove that a v1.2 invocation
 * leaking into a legacy-dependent compile fails the case loudly. The
 * pairing validation (a replacement locator exists and passes under the
 * v1.2 profile) is activated by the I6 coverage child when the
 * {@code jvm-int32-slice.json} matrix, the uniform-sidecar backend-runtime
 * int32 fixtures, and {@code test_runtime_int32.lua} land.</p>
 *
 * <p>Production code never depends on this class: it lives in its own
 * gate-run conformance test file (compiled through the gate compile
 * list) and is test-harness data only. The class is public because the
 * differential gate's LuaJIT lane (ISSUE-0354) consumes the per-case
 * profile selection (A5) from {@code deal.test.conformance}.</p>
 */
public final class LegacyProfileRegressionCatalog {

    /** The pinned row authority (A4). */
    static final String AUTHORITY = "legacy-regression";

    /** The pinned credit value (A4): a legacy result earns no credit. */
    static final String NO_CREDIT = "none";

    /**
     * The catalog row (A4): locator, the pinned legacy purpose/profile
     * pair, authority, credit, and the additive replacement. The compact
     * constructor defensively rejects any other purpose/profile
     * combination (the A1 matrix: only
     * {@code LEGACY_REGRESSION + LEGACY_SAFE_INT} carries legacy
     * authority).
     */
    record Row(String locator,
               deal.semantic.ir.InvocationPurpose purpose,
               SemanticProfile profile,
               String authority, String credit,
               String additiveReplacement) {
        Row {
            java.util.Objects.requireNonNull(locator,
                "locator must not be null");
            java.util.Objects.requireNonNull(purpose,
                "purpose must not be null");
            java.util.Objects.requireNonNull(profile,
                "profile must not be null");
            java.util.Objects.requireNonNull(authority,
                "authority must not be null");
            java.util.Objects.requireNonNull(credit,
                "credit must not be null");
            if (locator.isBlank()) {
                throw new IllegalArgumentException(
                    "locator must not be blank");
            }
            if (purpose != deal.semantic.ir.InvocationPurpose.LEGACY_REGRESSION
                    || profile != SemanticProfile.LEGACY_SAFE_INT) {
                throw new IllegalArgumentException(
                    "legacy regression catalog rows are closed to "
                        + "LEGACY_REGRESSION + LEGACY_SAFE_INT, got "
                        + purpose + " + " + profile);
            }
        }
    }

    private static Row row(String locator, String additiveReplacement) {
        return new Row(locator,
            deal.semantic.ir.InvocationPurpose.LEGACY_REGRESSION,
            SemanticProfile.LEGACY_SAFE_INT, AUTHORITY, NO_CREDIT,
            additiveReplacement);
    }

    /**
     * The closed catalog content (A4). Slice rows carry
     * {@code <fixture-file>#<case-name>} locators; backend-runtime rows
     * carry the corpus-relative path; test locators carry the file plus
     * the pin lines for documentation. Excluded families record no
     * replacement this epic: {@code jvm-std-time-nowmillis} (time lock —
     * the nowMillis resolution owns the replacement),
     * {@code js-int-safe-range-e8004},
     * {@code js-bytes-length-above-int32-e8012}, and
     * {@code js-stdlib-time-structural} (JS v1.2 excluded —
     * {@code js-v12-int32-bytes} owns the JS replacements). The
     * {@code time-now-millis-positive.deal} backend-runtime row is gone:
     * the disposition-application unit flipped the fixture to
     * {@code runtime-error E8004}, whose expectation depends on the
     * v1.2 signed-int32 gate, not the legacy range — the fixture now
     * resolves the {@code COMMON_SHADOW + DEAL_V1_2_INT32} invocation
     * (A5) and passes under it on every lane.
     */
    static final List<Row> ROWS = List.of(
        // jvm-skeleton.json — the safe-int-era boundary/overflow family
        row("jvm-skeleton.json#jvm-int-safe-range-boundary",
            "jvm-int32-slice.json#int32-literal-boundaries"),
        row("jvm-skeleton.json#jvm-int-safe-range-overflow",
            "jvm-int32-slice.json#int32-add-overflow"),
        row("jvm-skeleton.json#jvm-int-safe-range-sub",
            "jvm-int32-slice.json#int32-sub-overflow"),
        row("jvm-skeleton.json#jvm-int-safe-range-pow",
            "backend-runtime/arithmetic/int32-pow-overflow.deal"),
        row("jvm-skeleton.json#jvm-int-safe-range-negpow",
            "backend-runtime/arithmetic/int32-pow-overflow.deal"),
        row("jvm-skeleton.json#jvm-int-safe-range-intrinsic",
            "jvm-int32-slice.json#int32-convert-range"),
        row("jvm-skeleton.json#jvm-int-safe-range-literal",
            "jvm-int32-slice.json#int32-literal-boundaries"),
        row("jvm-skeleton.json#jvm-int-safe-range-max",
            "jvm-int32-slice.json#int32-literal-boundaries"),
        row("jvm-skeleton.json#jvm-int-safe-range-mod",
            "jvm-int32-slice.json#int32-literal-boundaries"),
        row("jvm-skeleton.json#jvm-standalone-expression-overflow",
            "jvm-int32-slice.json#int32-standalone-overflow"),
        // stdlib / boundary / async / nullable / host-ABI families
        row("jvm-stdlib-slice.json#jvm-std-math-int",
            "jvm-int32-slice.json#int32-math-abs-min"),
        row("jvm-stdlib-slice.json#jvm-std-time-nowmillis", null),
        row("jvm-arrays-slice.json#jvm-arr-cmp-both-raise-precedence-parity",
            "jvm-int32-slice.json#int32-array-precedence-parity"),
        row("jvm-async-slice.json#jvm-async-completion-int-boundary",
            "jvm-int32-slice.json#int32-async-completion-safe"),
        row("jvm-nullable-slice.json#jvm-nullable-boundary-int-range-failure",
            "jvm-int32-slice.json#int32-table-read-boundary"),
        row("jvm-host-abi-slice.json#jvm-host-int-out-of-range-return",
            "jvm-int32-slice.json#int32-host-int-return"),
        // jvm-v1.2-known-fail.json mirrors — the tracked known-fails.
        // jvm-int32-add-overflow was promoted by ISSUE-0381
        // (jvm-v12-int32-bytes D6): its knownFail marker dropped and its
        // catalog row removed, so the A5 seam routes the case
        // COMMON_SHADOW + DEAL_V1_2_INT32, where the activated intAdd
        // raises E8004 for 2147483647 + 1 — the passing pin stays in
        // place as JVM-only activated-route coverage (the two-backend
        // slice re-home jvm-int32-slice.json#int32-add-overflow carries
        // the same coverage). jvm-int32-literal-out-of-range stays
        // tracked: the untouched legacy harness routes the case
        // LEGACY_REGRESSION + LEGACY_SAFE_INT, where the legacy parse
        // contract admits the out-of-range literal (the expected E1036
        // never fires) — the frontend-owned case keeps failing and stays
        // tracked with no stale gate; dropping its marker is the
        // frontend E1036 landing's separate promotion. jvm-bytes-buffer-ops
        // is not catalogued: it already runs COMMON_SHADOW +
        // DEAL_V1_2_INT32 and stays tracked while the JVM bytes core is
        // absent (E6000 at bytes sites, ISSUE-0277) — its promotion is
        // gated on the bytes core (D6).
        row("jvm-v1.2-known-fail.json#jvm-int32-literal-out-of-range", null),
        // JS retained lane (profile-agnostic; JS v1.2 excluded)
        row("js-skeleton.json#js-int-safe-range-e8004", null),
        row("js-skeleton.json#js-bytes-length-above-int32-e8012", null),
        row("js-skeleton.json#js-stdlib-time-structural", null),
        // backend-runtime .deal population
        row("backend-runtime/runtime/int-convert-range.deal",
            "jvm-int32-slice.json#int32-convert-range"),
        // Test locators (legacy defaults — no relocation needed, unchanged)
        row("test_runtime.lua:122-140,481-490", "test_runtime_int32.lua"),
        row("test/LuaBackendIntegrationTest.java", "test_runtime_int32.lua"),
        row("test/JvmBackendTest.java", "jvm-int32-slice.json#int32-add-overflow"),
        row("test/JsBackendTest.java", null),
        row("test/LuaBackendTest.java", "test_runtime_int32.lua"),
        // Safe-range IR goldens (legacy-default parse dumps; byte-stable)
        row("test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-boundary.deal",
            "frontend/lexer/int32-literal-max.deal"),
        row("test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-intrinsic.deal",
            "frontend/lexer/int32-literal-max.deal"),
        row("test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-literal.deal",
            "frontend/lexer/int32-literal-max.deal"),
        row("test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-max.deal",
            "frontend/lexer/int32-literal-max.deal"),
        row("test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-mod.deal",
            "frontend/lexer/int32-literal-max.deal"),
        row("test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-negpow.deal",
            "frontend/lexer/int32-literal-max.deal"),
        row("test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-overflow.deal",
            "frontend/lexer/int32-literal-max.deal"),
        row("test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-pow.deal",
            "frontend/lexer/int32-literal-max.deal"),
        row("test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-sub.deal",
            "frontend/lexer/int32-literal-max.deal")
    );

    /** Locator → row index; duplicate locators are a harness defect. */
    private static final Map<String, Row> BY_LOCATOR = buildIndex();

    private static Map<String, Row> buildIndex() {
        Map<String, Row> index = new LinkedHashMap<>();
        for (Row row : ROWS) {
            Row prior = index.put(row.locator(), row);
            if (prior != null) {
                throw new IllegalStateException(
                    "duplicate catalog locator '" + row.locator() + "'");
            }
        }
        return Collections.unmodifiableMap(index);
    }

    private LegacyProfileRegressionCatalog() {
        // Static authority data only; no instances.
    }

    // =========================================================================
    // Per-case invocation selection (A5)
    // =========================================================================

    /** True when the locator has a catalog row (legacy authority). */
    static boolean isCatalogued(String locator) {
        return BY_LOCATOR.containsKey(locator);
    }

    /**
     * The per-case invocation (A5): a catalogued case resolves
     * {@code LEGACY_REGRESSION + LEGACY_SAFE_INT} under the release-owned
     * registry; every other case resolves
     * {@code COMMON_SHADOW + DEAL_V1_2_INT32} with zero shadow-module
     * requests (F4 rule 5 leaves every module on the retained LEGACY
     * route, and the A1-updated planner guard admits the invocation at
     * phase 3.7).
     *
     * <p>Public so the JVM lane of the differential gate (ISSUE-0355)
     * routes every corpus fixture through the catalog's per-case A5
     * seam — the catalogued legacy-regression fixtures must compile
     * under {@code LEGACY_REGRESSION + LEGACY_SAFE_INT} on the lane
     * exactly as they do on the legacy harness.</p>
     */
    public static CompilerInvocation invocationFor(String locator) {
        Row row = BY_LOCATOR.get(locator);
        if (row != null) {
            // The row's own pinned purpose/profile pair (A4): the closed
            // catalog admits only LEGACY_REGRESSION + LEGACY_SAFE_INT,
            // enforced by the Row compact constructor.
            return CompilerProfileProvider.resolveLegacyRegression(
                row.profile(), ReleaseState.PRE_ACTIVATION,
                ReleaseConfiguration.releaseCapabilityRegistry());
        }
        return CompilerProfileProvider.resolveCommonShadow(
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.PRE_ACTIVATION,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    /** The frontend (backend-neutral) invocation (A5): v1.2 parse/check. */
    static CompilerInvocation frontendInvocation() {
        return CompilerProfileProvider.resolveCommonShadow(
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.PRE_ACTIVATION,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    /** The per-case project-wide profile the seam threads everywhere. */
    public static SemanticProfile profileFor(String locator) {
        return invocationFor(locator).semanticProfile();
    }

    // =========================================================================
    // Catalog validation (A4 contract)
    // =========================================================================

    private static final List<String> VIOLATIONS = new ArrayList<>();

    /** Clears and returns the accumulated validation violations. */
    static List<String> drainViolations() {
        List<String> drained = new ArrayList<>(VIOLATIONS);
        VIOLATIONS.clear();
        return drained;
    }

    private static void violation(String message) {
        VIOLATIONS.add(message);
    }

    /**
     * Validates every non-slice row locator against the repository: an
     * unknown locator is a harness defect (A4 contract). Slice rows are
     * validated against the parsed fixture index by
     * {@link #validateSliceRows(Map)}.
     */
    static void validateRows() {
        for (Row row : ROWS) {
            String locator = row.locator();
            if (locator.contains("#")) {
                continue; // slice rows: validateSliceRows
            }
            String filePart = locator.contains(":")
                ? locator.substring(0, locator.indexOf(':'))
                : locator;
            Path file = locator.startsWith("backend-runtime/")
                ? Path.of("test", "conformance", locator)
                : Path.of(filePart);
            if (!Files.isRegularFile(file)) {
                violation("unknown locator '" + locator
                    + "' — the catalog row names no on-disk assertion (a "
                    + "harness defect; the row must name an existing "
                    + "fixture, case, or test locator)");
            }
        }
    }

    /**
     * Validates every slice row against the runner's parsed fixture
     * index (fixture file name → case names): an unknown locator — a
     * file that does not exist or a case that is not in the file — is a
     * harness defect (A4 contract).
     */
    static void validateSliceRows(Map<String, Set<String>> sliceCases) {
        for (Row row : ROWS) {
            if (!row.locator().contains("#")) {
                continue;
            }
            String[] parts = row.locator().split("#", 2);
            Set<String> names = sliceCases.get(parts[0]);
            if (names == null || !names.contains(parts[1])) {
                violation("unknown locator '" + row.locator()
                    + "' — the catalog row names no fixture case in "
                    + parts[0] + " (a harness defect)");
            }
        }
    }

    /**
     * The additive-replacement pairing validation (A4; activated by the
     * I6 coverage child): every non-excluded row's
     * {@code additiveReplacement} locator must resolve to an on-disk
     * v1.2 replacement — the {@code jvm-int32-slice.json} matrix, the
     * uniform-sidecar backend-runtime int32 fixtures, the promoted
     * frontend E1036 fixtures, or {@code test_runtime_int32.lua} — and
     * must not itself be catalogued (a catalogued replacement would run
     * under the legacy authority and earn zero replacement credit; the
     * A5 seam sends every uncatalogued replacement through
     * {@code COMMON_SHADOW + DEAL_V1_2_INT32}). A missing replacement
     * is a harness defect, never a silent pairing gap. Slice-row
     * replacements are checked file-level here and case-level by
     * {@link #validateReplacementSliceRows(Map)} (the runners with the
     * parsed fixture index); excluded rows ({@code null} — the slice
     * time-lock pin and the JS v1.2 exclusion) record no replacement
     * this epic.
     */
    static void validateReplacementRows() {
        for (Row row : ROWS) {
            String replacement = row.additiveReplacement();
            if (replacement == null) {
                continue; // excluded family: no replacement this epic
            }
            if (isCatalogued(replacement)) {
                violation("replacement locator '" + replacement
                    + "' for row '" + row.locator() + "' is itself "
                    + "catalogued — an additive replacement must run "
                    + "under the v1.2 profile (the A5 seam), never the "
                    + "legacy regression authority");
                continue;
            }
            String filePart = replacement.contains("#")
                ? replacement.substring(0, replacement.indexOf('#'))
                : replacement;
            Path file = replacement.startsWith("backend-runtime/")
                    || replacement.startsWith("frontend/")
                ? Path.of("test", "conformance", filePart)
                : replacement.contains("#")
                    ? Path.of("test", "conformance", "fixtures", filePart)
                    : Path.of(filePart);
            if (!Files.isRegularFile(file)) {
                violation("replacement locator '" + replacement
                    + "' for row '" + row.locator()
                    + "' names no on-disk replacement artifact (a "
                    + "missing additive v1.2 replacement — the pairing "
                    + "contract requires every non-excluded row to name "
                    + "an existing replacement that passes under "
                    + "DEAL_V1_2_INT32)");
            }
        }
    }

    /**
     * The slice-index pairing check (A4): every slice-row replacement
     * ({@code <fixture-file>#<case-name>}) must name a case of the
     * runner's parsed fixture file. Called by the slice runner
     * (BackendConformanceTest) after its file-level parse; the
     * file-level existence check is {@link #validateReplacementRows()}.
     */
    static void validateReplacementSliceRows(
            Map<String, Set<String>> sliceCases) {
        for (Row row : ROWS) {
            String replacement = row.additiveReplacement();
            if (replacement == null || !replacement.contains("#")) {
                continue;
            }
            String[] parts = replacement.split("#", 2);
            Set<String> names = sliceCases.get(parts[0]);
            if (names == null || !names.contains(parts[1])) {
                violation("replacement locator '" + replacement
                    + "' for row '" + row.locator()
                    + "' names no fixture case in " + parts[0]
                    + " (a missing additive v1.2 replacement — the "
                    + "pairing contract requires every non-excluded row "
                    + "to name an existing replacement that passes under "
                    + "DEAL_V1_2_INT32)");
            }
        }
    }

    /** True when the fixture's own expectation is the v1.2-positive
     * E1036 pin (the promoted int32-literal compile-error fixtures). */
    private static boolean isE1036Expectation(String expectedTag,
            String expectedCompileError) {
        return "E1036".equals(expectedCompileError)
            || "compile-error E1036".equals(expectedTag);
    }

    /**
     * True when the source raises E1036 under the given profile — the
     * operational meaning of the completeness rule's first trigger: an
     * int literal outside {@code [-2147483648, 2147483647]} outside the
     * {@code -2147483648} immediate-token position. The real parser is
     * the authority, so the scan can never diverge from the parse
     * contract.
     */
    private static boolean parsesWithE1036(String source, String filename,
            SemanticProfile profile) {
        if (source == null) {
            return false;
        }
        LexResult lex = new Lexer(source, filename).tokenize();
        if (lex.hasErrors()) {
            return false;
        }
        // ISSUE-0273 D8 item 3: in-memory seam site — the parser runs
        // with the lexer's directive events so directive diagnostics
        // behave exactly as in production.
        ParseResult parse = new Parser(lex.tokens(), filename, profile,
            lex.directiveEvents()).parse();
        return parse.diagnostics().stream().anyMatch(
            d -> "error".equals(d.severity())
                && "E1036".equals(d.code()));
    }

    /**
     * The completeness scan over one discovered {@code .deal} fixture
     * (A4 closed rule): a source that raises E1036 under the v1.2 parse
     * contract must be catalogued — unless the fixture's own expectation
     * is the v1.2-positive E1036 pin. An omission is a violation, never
     * a silent pin weakening.
     */
    static void scanDealSource(String locator, String source,
            String expectedTag) {
        if (!parsesWithE1036(source, locator,
                SemanticProfile.DEAL_V1_2_INT32)) {
            return;
        }
        if (isCatalogued(locator)) {
            return;
        }
        if (isE1036Expectation(expectedTag, null)) {
            return;
        }
        violation("uncatalogued legacy-dependent assertion '" + locator
            + "' — its source contains an int literal outside "
            + "[-2147483648, 2147483647] (E1036 under DEAL_V1_2_INT32) "
            + "but the catalog has no row; add a LegacyProfileRegressionCatalog "
            + "row (completeness rule) or the v1.2-positive E1036 expectation");
    }

    /**
     * The completeness scan over one JSON slice case source (A4 closed
     * rule): the same closed trigger, with the case's
     * {@code expectedCompileError} supplying the v1.2-positive E1036
     * exemption.
     */
    static void scanCaseSource(String fixtureFile, String caseName,
            String source, String expectedCompileError) {
        if (!parsesWithE1036(source, fixtureFile + "#" + caseName,
                SemanticProfile.DEAL_V1_2_INT32)) {
            return;
        }
        if (isCatalogued(fixtureFile + "#" + caseName)) {
            return;
        }
        if (isE1036Expectation(null, expectedCompileError)) {
            return;
        }
        violation("uncatalogued legacy-dependent assertion '"
            + fixtureFile + "#" + caseName + "' — its source contains an "
            + "int literal outside [-2147483648, 2147483647] (E1036 under "
            + "DEAL_V1_2_INT32) but the catalog has no row; add a "
            + "LegacyProfileRegressionCatalog row (completeness rule) or the "
            + "v1.2-positive E1036 expectedCompileError");
    }

    // =========================================================================
    // Mechanism self-probes (A5 negative probe, catalog completeness probe)
    // =========================================================================

    /**
     * The mechanism probes run at harness startup before any fixture
     * executes (A4/A5 verification): (1) a legacy-dependent source fails
     * loudly under the v1.2 parse contract — a v1.2 invocation leaking
     * into a catalogued legacy compile fails the case; (2) the same
     * source parses under the legacy contract; (3) the catalogued time
     * fixture selects the legacy regression invocation while an
     * uncatalogued locator selects the v1.2 invocation — removing a row
     * therefore flips the decision to v1.2 and triggers the loud failure
     * of probe (1).
     */
    static void runSelfProbes() {
        String legacyDependent =
            "export function test(): int { return 9007199254740991; }";
        if (!parsesWithE1036(legacyDependent, "catalog-probe.deal",
                SemanticProfile.DEAL_V1_2_INT32)) {
            violation("self-probe: a legacy-dependent int literal must "
                + "raise E1036 under DEAL_V1_2_INT32 (a v1.2 invocation "
                + "leaking into a catalogued legacy compile must fail the "
                + "case)");
        }
        if (parsesWithE1036(legacyDependent, "catalog-probe.deal",
                SemanticProfile.LEGACY_SAFE_INT)) {
            violation("self-probe: the legacy parse contract must admit "
                + "the legacy-dependent int literal");
        }
        String time = "backend-runtime/stdlib-edge/time-now-millis-positive.deal";
        if (isCatalogued(time)) {
            violation("self-probe: the " + time + " catalog row must be "
                + "gone (the disposition-application unit flipped the "
                + "fixture to runtime-error E8004 — its expectation now "
                + "depends on the v1.2 signed-int32 gate, not the "
                + "legacy range)");
        }
        if (isCatalogued(time + "#removed")) {
            violation("self-probe: an unknown locator must not be "
                + "catalogued");
        }
        CompilerInvocation timeInvocation = invocationFor(time);
        if (timeInvocation.purpose() != deal.semantic.ir.InvocationPurpose.COMMON_SHADOW
                || timeInvocation.semanticProfile()
                    != SemanticProfile.DEAL_V1_2_INT32) {
            violation("self-probe: the uncatalogued time fixture must "
                + "resolve COMMON_SHADOW + DEAL_V1_2_INT32, got "
                + timeInvocation.purpose() + " + "
                + timeInvocation.semanticProfile());
        }
        CompilerInvocation otherInvocation = invocationFor(
            "backend-runtime/uncatalogued-probe.deal");
        if (otherInvocation.purpose() != deal.semantic.ir.InvocationPurpose.COMMON_SHADOW
                || otherInvocation.semanticProfile()
                    != SemanticProfile.DEAL_V1_2_INT32) {
            violation("self-probe: an uncatalogued locator must resolve "
                + "COMMON_SHADOW + DEAL_V1_2_INT32, got "
                + otherInvocation.purpose() + " + "
                + otherInvocation.semanticProfile());
        }
    }
}
