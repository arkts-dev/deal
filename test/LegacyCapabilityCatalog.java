// =========================================================================
// LegacyCapabilityCatalog — the release-owned unsupported-legacy-slice
// authority (historical-and-legacy-catalogs H4)
// =========================================================================

package deal.test;

import deal.semantic.SemanticRequirementManifest;
import deal.semantic.ir.SemanticCapability;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The closed, release-owned legacy-capability catalog (H4): one row per
 * retained unsupported-legacy assignment surface with its target, its
 * required capabilities, its precise in-tree evidence locator, and its
 * expectation class. The catalog lives in its own gate-run conformance
 * test file (the {@code LegacyProfileRegressionCatalog} precedent) and
 * is test-harness data only — production code never depends on it.
 *
 * <p><b>Row schema (H4, closed).</b>
 * {@code LegacyCapabilityRow {entryId, target: LUAJIT|JVM,
 * requiredCapabilities: [SemanticCapability], evidenceLocator,
 * expectationClass: BackendReject|SameAsShared}}. Every row is
 * release-owned: an entry id is unique, the target and capability list
 * are closed (no open member; the capability list is non-empty and in
 * {@code SemanticCapability} declaration order), and the evidence
 * locator must resolve in-tree at every validation.</p>
 *
 * <p><b>Assignment validation (H4 contract).</b> An
 * {@code UnsupportedLegacySlice} ({@code {target, requiredCapabilities,
 * catalogEntryId, evidenceLocator}} — parent D6) validates against
 * exactly one release-owned catalog row: the {@code catalogEntryId} must
 * exist, the assignment's target/declared capabilities/evidence locator
 * must equal the row's, the row's {@code evidenceLocator} must resolve,
 * and the case's actual requirement manifests — derived through the real
 * {@code deal.semantic.LoweringSupport} pass, never fixture-asserted —
 * must require the row's capabilities. A fixture cannot hide a mismatch
 * by naming a capability: a declared capability the derived manifests do
 * not require fails validation. Every failure is
 * {@code LEGACY_ASSIGNMENT_INVALID} naming the failing fact.</p>
 *
 * <p><b>Retained-target accounting.</b> A validated assignment never
 * skips a shared consumer; the retained target records
 * {@code LEGACY_NOT_RUN} and earns no rollback-equivalence credit
 * ({@link AssignmentValidation#retainedTargetStatus()},
 * {@link AssignmentValidation#rollbackEquivalenceCredit()} — parent D6).
 * A validated unsupported state cannot replace attainable retained
 * evidence (the H5 floors live in
 * {@link HistoricalRegressionCatalog#retainedAssignmentFloorViolations}).</p>
 */
public final class LegacyCapabilityCatalog {

    private LegacyCapabilityCatalog() {
        // Static authority data only; no instances.
    }

    // =========================================================================
    // Closed axes (H4)
    // =========================================================================

    /** The two closed retained targets. */
    public enum Target {
        LUAJIT,
        JVM
    }

    /** The two closed expectation classes of a legacy-capability row. */
    public enum ExpectationClass {
        BackendReject,
        SameAsShared
    }

    /** The pinned assignment-invalid failure code (H4). */
    public static final String LEGACY_ASSIGNMENT_INVALID =
        "LEGACY_ASSIGNMENT_INVALID";

    /** The retained target's recorded status for a validated assignment. */
    public static final String LEGACY_NOT_RUN = "LEGACY_NOT_RUN";

    /** A validated unsupported assignment never earns rollback credit. */
    public static final boolean NO_ROLLBACK_EQUIVALENCE_CREDIT = false;

    /**
     * The closed catalog row (H4). The compact constructor rejects a
     * blank/duplicate-free entry id (uniqueness is enforced by the
     * catalog index), a null/empty capability list, capabilities outside
     * the closed {@code SemanticCapability} order, and any evidence
     * locator that is neither a fixture-case locator
     * ({@code <file>#<case>}) nor a code locator
     * ({@code <file>:<line>[-<line>]}).
     */
    public record Row(String entryId,
                      Target target,
                      List<SemanticCapability> requiredCapabilities,
                      String evidenceLocator,
                      ExpectationClass expectationClass) {

        public Row {
            Objects.requireNonNull(entryId, "entryId must not be null");
            Objects.requireNonNull(target, "target must not be null");
            Objects.requireNonNull(requiredCapabilities,
                "requiredCapabilities must not be null");
            Objects.requireNonNull(evidenceLocator,
                "evidenceLocator must not be null");
            Objects.requireNonNull(expectationClass,
                "expectationClass must not be null");
            if (entryId.isBlank()) {
                throw new IllegalArgumentException(
                    "entryId must not be blank");
            }
            if (requiredCapabilities.isEmpty()) {
                throw new IllegalArgumentException("row '" + entryId
                    + "': requiredCapabilities must not be empty");
            }
            List<SemanticCapability> closed = new ArrayList<>();
            for (SemanticCapability capability : requiredCapabilities) {
                if (!closed.contains(capability)) {
                    closed.add(capability);
                }
            }
            if (closed.size() != requiredCapabilities.size()) {
                throw new IllegalArgumentException("row '" + entryId
                    + "': requiredCapabilities must not repeat a capability");
            }
            closed.sort(java.util.Comparator.comparingInt(Enum::ordinal));
            if (!closed.equals(List.copyOf(requiredCapabilities))) {
                throw new IllegalArgumentException("row '" + entryId
                    + "': requiredCapabilities must be in "
                    + "SemanticCapability declaration order, got "
                    + requiredCapabilities);
            }
            requiredCapabilities = closed;
            if (evidenceLocator.isBlank()) {
                throw new IllegalArgumentException(
                    "evidenceLocator must not be blank");
            }
            if (!HistoricalRegressionCatalog.isFixtureCaseLocator(
                    evidenceLocator)
                    && !HistoricalRegressionCatalog.isCodeLocator(
                        evidenceLocator)) {
                throw new IllegalArgumentException("row '" + entryId
                    + "': evidenceLocator '" + evidenceLocator
                    + "' is neither a fixture-case locator nor a "
                    + "file:line[-line] code locator — a release-owned "
                    + "row must pin a precise resolvable locator");
            }
        }
    }

    // =========================================================================
    // The closed catalog content (H4, release-owned)
    // =========================================================================

    /**
     * The release-owned rows, each pinning a retained rejection with
     * in-tree evidence:
     * <ul>
     *   <li>{@code jvm-function-value-adapter-lua-only-rejection} — the
     *       retained JVM conservatively rejects the reassigned-local /
     *       call-result adapter shapes with E6000 (ISSUE-0110 owns the
     *       promotion); the Lua-only fixture case is the retained
     *       rejection evidence (backends {@code ["luajit"]} only).</li>
     *   <li>{@code jvm-array-index-delete-e6000} — the retained JVM
     *       rejects every remaining delete target shape with E6000 at
     *       {@code deal/codegen/jvm/JvmBackend.java:9920}; the code
     *       locator pins the retained rejection.</li>
     * </ul>
     * Rows are added only with in-tree evidence: the entry id must be
     * unique, and the evidence locator must resolve at every validation.
     */
    static final List<Row> ROWS = List.of(
        new Row("jvm-function-value-adapter-lua-only-rejection",
            Target.JVM,
            List.of(SemanticCapability.BINDINGS),
            "jvm-function-values-slice.json#jvm-fv-lua-ref-reassigned-adapter",
            ExpectationClass.BackendReject),
        new Row("jvm-array-index-delete-e6000",
            Target.JVM,
            List.of(SemanticCapability.CONTAINERS_AND_STRINGS),
            "deal/codegen/jvm/JvmBackend.java:9920",
            ExpectationClass.BackendReject)
    );

    /** entryId → row; duplicate entry ids are a harness defect. */
    private static final Map<String, Row> BY_ENTRY = buildIndex();

    private static Map<String, Row> buildIndex() {
        Map<String, Row> index = new LinkedHashMap<>();
        for (Row row : ROWS) {
            Row prior = index.put(row.entryId(), row);
            if (prior != null) {
                throw new IllegalStateException(
                    "duplicate legacy-capability entry id '" + row.entryId()
                        + "'");
            }
        }
        return Collections.unmodifiableMap(index);
    }

    // =========================================================================
    // UnsupportedLegacySlice (parent D6 shape)
    // =========================================================================

    /**
     * The closed unsupported-legacy assignment shape (parent D6):
     * {@code {target, requiredCapabilities, catalogEntryId,
     * evidenceLocator}}. The compact constructor requires the closed
     * target spelling, a non-empty capability list in
     * {@code SemanticCapability} declaration order, and non-blank
     * catalog/evidence identifiers.
     */
    public record UnsupportedLegacySlice(String target,
                                         List<SemanticCapability>
                                             requiredCapabilities,
                                         String catalogEntryId,
                                         String evidenceLocator) {

        public UnsupportedLegacySlice {
            Objects.requireNonNull(target, "target must not be null");
            Objects.requireNonNull(requiredCapabilities,
                "requiredCapabilities must not be null");
            Objects.requireNonNull(catalogEntryId,
                "catalogEntryId must not be null");
            Objects.requireNonNull(evidenceLocator,
                "evidenceLocator must not be null");
            if (target.isBlank()) {
                throw new IllegalArgumentException(
                    "target must not be blank");
            }
            if (catalogEntryId.isBlank()) {
                throw new IllegalArgumentException(
                    "catalogEntryId must not be blank");
            }
            if (evidenceLocator.isBlank()) {
                throw new IllegalArgumentException(
                    "evidenceLocator must not be blank");
            }
            if (requiredCapabilities.isEmpty()) {
                throw new IllegalArgumentException(
                    "requiredCapabilities must not be empty");
            }
            List<SemanticCapability> closed = new ArrayList<>();
            for (SemanticCapability capability : requiredCapabilities) {
                if (!closed.contains(capability)) {
                    closed.add(capability);
                }
            }
            if (closed.size() != requiredCapabilities.size()) {
                throw new IllegalArgumentException(
                    "requiredCapabilities must not repeat a capability");
            }
            closed.sort(java.util.Comparator.comparingInt(Enum::ordinal));
            if (!closed.equals(List.copyOf(requiredCapabilities))) {
                throw new IllegalArgumentException(
                    "requiredCapabilities must be in SemanticCapability "
                        + "declaration order, got " + requiredCapabilities);
            }
            requiredCapabilities = closed;
        }
    }

    // =========================================================================
    // Assignment validation (H4 contract)
    // =========================================================================

    /**
     * The validation verdict of one {@code UnsupportedLegacySlice}
     * against the release-owned catalog: {@code valid} plus (when valid)
     * the matched row and the retained-target accounting facts, or (when
     * invalid) the {@code LEGACY_ASSIGNMENT_INVALID} failure naming the
     * failing fact.
     */
    public record AssignmentValidation(boolean valid,
                                       String failure,
                                       Row row) {

        public AssignmentValidation {
            Objects.requireNonNull(failure, "failure must not be null");
            if (valid && row == null) {
                throw new IllegalArgumentException(
                    "a valid assignment names its matched row");
            }
            if (!valid && failure.isBlank()) {
                throw new IllegalArgumentException(
                    "an invalid assignment names its failing fact");
            }
        }

        /** A validated assignment records {@code LEGACY_NOT_RUN} for the
         * retained target (parent D6) — never a skip of a shared consumer. */
        public String retainedTargetStatus() {
            return valid ? LEGACY_NOT_RUN : null;
        }

        /** A validated unsupported state earns no rollback-equivalence
         * credit (parent D6) — never a substitute for attainable evidence. */
        public boolean rollbackEquivalenceCredit() {
            return NO_ROLLBACK_EQUIVALENCE_CREDIT;
        }
    }

    private static AssignmentValidation invalid(String failure) {
        return new AssignmentValidation(false,
            LEGACY_ASSIGNMENT_INVALID + " — " + failure, null);
    }

    /**
     * True when the evidence locator resolves in-tree: a fixture-case
     * locator resolves against the runner's parsed fixture index, a code
     * locator resolves to an existing source file with in-bounds lines.
     */
    static boolean evidenceResolves(String evidenceLocator,
                                    Path sourceRoot,
                                    Map<String, Map<String,
                                        Map<String, Object>>> sliceIndex) {
        if (HistoricalRegressionCatalog.isCodeLocator(evidenceLocator)) {
            HistoricalRegressionCatalog.SpanHolder span =
                HistoricalRegressionCatalog.parseSpanHolder(evidenceLocator);
            if (span == null) {
                return false;
            }
            Path file = sourceRoot.resolve(span.file());
            try {
                byte[] bytes = Files.readAllBytes(file);
                return HistoricalRegressionCatalog.spanInBounds(bytes,
                    span);
            } catch (Exception e) {
                return false;
            }
        }
        String[] parts = evidenceLocator.split("#", 2);
        Map<String, Map<String, Object>> file = sliceIndex.get(parts[0]);
        return file != null && file.containsKey(parts[1]);
    }

    /**
     * Validates one {@code UnsupportedLegacySlice} against exactly one
     * release-owned catalog row (H4): the entry exists; the assignment's
     * target, declared capabilities, and evidence locator equal the
     * row's; the row's evidence locator resolves; and the case's actual
     * requirement manifests — derived through the real
     * {@code deal.semantic.LoweringSupport} pass over the case's checked
     * project, never fixture-asserted — require the row's capabilities.
     * Any failing fact is {@code LEGACY_ASSIGNMENT_INVALID}.
     *
     * @param slice             the assignment under validation
     * @param derivedManifests  the case's requirement manifests produced
     *                          by {@code deal.semantic.LoweringSupport}
     *                          (the validator reads them; it never derives
     *                          or fabricates them)
     * @param sourceRoot        the repository root for code locators
     * @param sliceIndex        the runner's parsed fixture index
     *                          (fixture file → case name → raw case map)
     */
    public static AssignmentValidation validate(
            UnsupportedLegacySlice slice,
            List<SemanticRequirementManifest> derivedManifests,
            Path sourceRoot,
            Map<String, Map<String, Map<String, Object>>> sliceIndex) {
        return validate(slice, derivedManifests, sourceRoot, sliceIndex,
            BY_ENTRY);
    }

    /**
     * The generic validation over an injected closed row index (the
     * test seam): the validation logic is the same closed contract, run
     * against a test-authored row set. The release-owned index is the
     * production default of {@link #validate(UnsupportedLegacySlice,
     * List, Path, Map)}.
     */
    static AssignmentValidation validate(
            UnsupportedLegacySlice slice,
            List<SemanticRequirementManifest> derivedManifests,
            Path sourceRoot,
            Map<String, Map<String, Map<String, Object>>> sliceIndex,
            Map<String, Row> catalog) {
        Row row = catalog.get(slice.catalogEntryId());
        if (row == null) {
            return invalid("catalog entry '" + slice.catalogEntryId()
                + "' does not exist — an UnsupportedLegacySlice must name "
                + "exactly one release-owned catalog entry");
        }
        Target declaredTarget;
        try {
            declaredTarget = Target.valueOf(slice.target());
        } catch (IllegalArgumentException e) {
            return invalid("target '" + slice.target()
                + "' is not a closed retained target (LUAJIT|JVM)");
        }
        if (declaredTarget != row.target()) {
            return invalid("assignment target " + declaredTarget
                + " differs from catalog entry '" + row.entryId()
                + "'s target " + row.target());
        }
        if (!slice.requiredCapabilities().equals(
                row.requiredCapabilities())) {
            return invalid("the assignment's declared capabilities "
                + slice.requiredCapabilities() + " differ from catalog "
                + "entry '" + row.entryId() + "'s required capabilities "
                + row.requiredCapabilities() + " — a fixture cannot name "
                + "capabilities its catalog entry does not carry");
        }
        if (!slice.evidenceLocator().equals(row.evidenceLocator())) {
            return invalid("the assignment's evidenceLocator '"
                + slice.evidenceLocator() + "' differs from catalog entry '"
                + row.entryId() + "'s release-owned evidenceLocator '"
                + row.evidenceLocator() + "' — an assignment carries no "
                + "evidence of its own");
        }
        if (!evidenceResolves(row.evidenceLocator(), sourceRoot,
                sliceIndex)) {
            return invalid("catalog entry '" + row.entryId()
                + "'s evidenceLocator '" + row.evidenceLocator()
                + "' does not resolve in-tree");
        }
        if (derivedManifests == null || derivedManifests.isEmpty()) {
            return invalid("the case derives no requirement manifests — "
                + "the manifests must come from the real "
                + "deal.semantic.LoweringSupport pass over the case's "
                + "checked project, never from the fixture's claims");
        }
        Set<SemanticCapability> required = EnumSet.noneOf(
            SemanticCapability.class);
        for (SemanticRequirementManifest manifest : derivedManifests) {
            Objects.requireNonNull(manifest, "derived manifests must not "
                + "contain null");
            required.addAll(manifest.capabilities());
        }
        if (!required.containsAll(row.requiredCapabilities())) {
            return invalid("the case's derived requirement manifests "
                + required + " do not require catalog entry '"
                + row.entryId() + "'s capabilities "
                + row.requiredCapabilities() + " — derived through the "
                + "real deal.semantic.LoweringSupport pass, never "
                + "fixture-asserted, so a fixture cannot hide a mismatch "
                + "by naming a capability");
        }
        return new AssignmentValidation(true, "valid — retained target "
            + row.target() + " records " + LEGACY_NOT_RUN
            + "; no rollback-equivalence credit; shared consumers never "
            + "skip", row);
    }

    // =========================================================================
    // Row validation (H4: every evidence locator resolves)
    // =========================================================================

    /**
     * Validates every code-locator row against the source tree. Called
     * at harness startup by the runners that resolve the located sources
     * but not the JSON-slice fixture index (the
     * {@code LegacyProfileRegressionCatalog} precedent split).
     */
    public static List<String> validateCodeRows(Path sourceRoot) {
        List<String> violations = new ArrayList<>();
        for (Row row : ROWS) {
            if (HistoricalRegressionCatalog.isCodeLocator(
                    row.evidenceLocator())) {
                if (!evidenceResolves(row.evidenceLocator(), sourceRoot,
                        Map.of())) {
                    violations.add("LEGACY_CAPABILITY_LOCATOR_DANGLED entry '"
                        + row.entryId() + "' — the release-owned "
                        + "evidenceLocator '" + row.evidenceLocator()
                        + "' does not resolve in-tree (a release-owned "
                        + "unsupported-slice row must pin a precise "
                        + "resolvable rejection locator)");
                }
            }
        }
        return violations;
    }

    /**
     * Validates every release-owned row's evidence locator against the
     * tree and the runner's parsed fixture index. Called at harness
     * startup and by the promotion gate's retained-assignments item.
     */
    public static List<String> validateRows(
            Path sourceRoot,
            Map<String, Map<String, Map<String, Object>>> sliceIndex) {
        List<String> violations = new ArrayList<>();
        for (Row row : ROWS) {
            if (!evidenceResolves(row.evidenceLocator(), sourceRoot,
                    sliceIndex)) {
                violations.add("LEGACY_CAPABILITY_LOCATOR_DANGLED entry '"
                    + row.entryId() + "' — the release-owned "
                    + "evidenceLocator '" + row.evidenceLocator()
                    + "' does not resolve in-tree (a release-owned "
                    + "unsupported-slice row must pin a precise resolvable "
                    + "rejection locator)");
            }
        }
        return violations;
    }
}
