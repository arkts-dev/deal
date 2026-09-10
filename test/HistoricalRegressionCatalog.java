// =========================================================================
// HistoricalRegressionCatalog — the closed historical regression authority
// (historical-and-legacy-catalogs H1/H2/H3/H5)
// =========================================================================

package deal.test;

import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.InvocationPurpose;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticProfile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The closed, release-owned historical regression authority (H1/H2):
 * one row per retained historical pin with its capability mapping, its
 * declared execution authority, its family, its expectation class, and
 * its pinned expectation-baseline digest. The catalog lives in its own
 * gate-run conformance test file (the
 * {@link LegacyProfileRegressionCatalog} precedent) and is test-harness
 * data only — production code never depends on it.
 *
 * <p><b>Row schema (H1, closed).</b>
 * {@code {capability, pinId, locator, authority, family,
 * expectationClass, errorCode, anchors, expectationBaseline}} with the
 * closed derivation per locator kind:</p>
 *
 * <ul>
 *   <li><b>Fixture-case locator</b> ({@code <fixture-file>#<case-name>},
 *       authorities {@code BACKEND_SLICE_JVM}/{@code CORPUS_JVM}): the
 *       baseline is the canonical SHA-256 over the canonical JSON record
 *       {@code {caseName, expectedOutput, expectedError, expectedExitCode,
 *       expectedCompileError, expectedNotOutput, irContains, irNotContains,
 *       backends}} — {@code caseName} is the fixture's {@code "name"}
 *       field, arrays in authored order, absent optional fields as
 *       {@code null} — serialized through the
 *       {@code deal.semantic.ir.CanonicalJson} facility. Every
 *       expectation field a re-author could weaken is part of the
 *       baseline.</li>
 *   <li><b>Code-contract locator</b> ({@code <file>:<line>[-<line>]},
 *       authorities {@code BACKEND_SOURCE_LUAJIT}/
 *       {@code BACKEND_SOURCE_JVM}): the row pins {@code errorCode} and
 *       one or more exact-text anchors; the baseline is the canonical
 *       SHA-256 over the canonical JSON record
 *       {@code {locator, errorCode, anchors, spans}} where
 *       {@code anchors} = {@code [{anchorId, locator, text}]} in authored
 *       order and {@code spans} = the exact source bytes of the primary
 *       locator span followed by each anchor's span bytes. The spans are
 *       read from the tree at validation — never from the row — and
 *       every anchor's text must occur verbatim within its span's bytes.</li>
 *   <li><b>Any other locator kind or authority has no baseline derivation
 *       and is rejected at catalog authoring</b> (fail-closed: no row may
 *       carry an unverifiable baseline).</li>
 * </ul>
 *
 * <p>Baselines are pinned when the catalog row is authored from the
 * then-current tree and are <b>never auto-updated</b>: the rows and the
 * authored expectation surfaces are literals, class initialization
 * verifies their mutual consistency, and every validation recomputes the
 * surface from the tree and compares against the pinned digest. A
 * dangling, skipped, weakened, or re-authored pin fails validation — an
 * omission can never silently weaken a historical pin.</p>
 *
 * <p><b>The pinned pin list and capability mapping (H2).</b> The
 * parent's pin list carried with current-tree locators. Per the H2
 * family mapping, each pin's single capability field is its family's
 * capability (the union over the pins is exactly the H2 table's
 * capability columns):
 * {@code ADAPTER → BINDINGS};
 * {@code BOUNDARY_ARITY}/{@code ASYNC_HOST}/{@code IMPORTED_CALLS → CALLS};
 * {@code ARRAY_BOUNDS}/{@code ARRAY_DELETE → CONTAINERS_AND_STRINGS};
 * {@code WRITE_ORDER}/{@code FIELD_WRITE → EVALUATION_ORDER};
 * {@code XMOD_CONSTRUCTION → CLASSES}.</p>
 *
 * <p><b>Validation (H1 contract).</b> At harness startup and at the
 * promotion gate: every locator resolves in-tree; every pin's assertion
 * executes in its declared authority with its expectation unchanged;
 * the resolved expectation surface's digest equals the pinned baseline.
 * Failures carry the pinned failure classes:
 * {@code HISTORICAL_LOCATOR_DANGLED <pinId>},
 * {@code HISTORICAL_PIN_INACTIVE <pinId>}, and
 * {@code HISTORICAL_PIN_EXPECTATION_CHANGED <pinId>} naming the changed
 * expectation field (or the changed span bytes / the absent anchor text
 * for a code-contract row).</p>
 *
 * <p><b>LegacyProfileRegressionCatalog consumption (H3).</b> The landed
 * A4 authority is preserved exactly; this catalog only extends the
 * promotion gate's consumption: the gate's historical item for
 * {@code SIGNED_INT32} passes only when every safe-int row
 * ({@code jvm-std-math-int}, {@code jvm-int-safe-range-*},
 * {@code jvm-std-time-nowmillis}) stays catalogued, routes through
 * {@code LEGACY_REGRESSION + LEGACY_SAFE_INT} with credit {@code none}
 * (a legacy-profile result earns no v1.2 credit), each non-excluded
 * row's additive v1.2 replacement is uncatalogued (it runs under the
 * v1.2 profile) and resolves on disk, and the wired runners' execution
 * evidence is green ({@link #recordLegacyExecution},
 * {@link #recordV12Execution}, {@link #recordExactOutcome},
 * {@link #executedEvidenceViolations}).</p>
 *
 * <p><b>Attainable-evidence floors (H5).</b> A validated unsupported
 * state cannot replace attainable retained evidence: every historical
 * target-specific pin stays active, each pinned
 * {@code (capability, family)} pair keeps its evidence rows, the
 * retained array-delete contract keeps its LuaJIT {@code SameAsShared}
 * pin and its JVM {@code BackendReject} E6000 pin, and every
 * {@code LegacyCapabilityCatalog} row's evidence locator resolves
 * ({@link #retainedAssignmentFloorViolations}).</p>
 */
public final class HistoricalRegressionCatalog {

    private HistoricalRegressionCatalog() {
        // Static authority data only; no instances.
    }

    // =========================================================================
    // Closed axes (H1)
    // =========================================================================

    /** The eight closed execution-authority axes. */
    public enum Authority {
        BACKEND_SLICE_JVM,
        BACKEND_RUNTIME_LUAJIT,
        CORPUS_JVM,
        DIRECT_LUA,
        IR_GOLDEN,
        UNIT_TEST,
        BACKEND_SOURCE_LUAJIT,
        BACKEND_SOURCE_JVM
    }

    /** The nine closed pin families. */
    public enum Family {
        ADAPTER,
        BOUNDARY_ARITY,
        WRITE_ORDER,
        ARRAY_BOUNDS,
        ARRAY_DELETE,
        FIELD_WRITE,
        XMOD_CONSTRUCTION,
        ASYNC_HOST,
        IMPORTED_CALLS
    }

    /** The three closed expectation classes (the authority/projection axis). */
    public enum ExpectationClass {
        SameAsShared,
        BackendReject,
        ExactPin
    }

    /**
     * One exact-text anchor (H1): the text must occur verbatim within
     * its span's bytes, both at catalog authoring and at every
     * validation from the tree.
     */
    public record ExactTextAnchor(String anchorId, String locator,
                                  String text) {

        public ExactTextAnchor {
            Objects.requireNonNull(anchorId, "anchorId must not be null");
            Objects.requireNonNull(locator, "locator must not be null");
            Objects.requireNonNull(text, "text must not be null");
            if (anchorId.isBlank()) {
                throw new IllegalArgumentException(
                    "anchorId must not be blank");
            }
            if (locator.isBlank()) {
                throw new IllegalArgumentException(
                    "anchor locator must not be blank");
            }
            if (text.isEmpty()) {
                throw new IllegalArgumentException(
                    "anchor text must not be empty (an empty text would "
                        + "pin no machine surface)");
            }
        }
    }

    // =========================================================================
    // The catalog row (H1, closed)
    // =========================================================================

    private static final Pattern BASELINE = Pattern.compile("[0-9a-f]{64}");

    /** True when the locator has the {@code <file>:<line>[-<line>]} shape. */
    static boolean isCodeLocator(String locator) {
        return locator.contains(":");
    }

    /** True when the locator has the {@code <fixture-file>#<case-name>} shape. */
    static boolean isFixtureCaseLocator(String locator) {
        return !isCodeLocator(locator) && locator.contains("#");
    }

    /**
     * The closed catalog row (H1). The compact constructor enforces the
     * closed derivation per locator kind: fixture-case rows
     * ({@code <file>#<case>}) carry authorities
     * {@code BACKEND_SLICE_JVM}/{@code CORPUS_JVM}, no error code, and no
     * anchors; code-contract rows ({@code <file>:<line>[-<line>]}) carry
     * authorities {@code BACKEND_SOURCE_LUAJIT}/
     * {@code BACKEND_SOURCE_JVM}, a non-blank error code, and at least
     * one anchor; any other locator kind or authority has no baseline
     * derivation and is rejected at catalog authoring (fail-closed).
     */
    public record Row(SemanticCapability capability,
                      String pinId,
                      String locator,
                      Authority authority,
                      Family family,
                      ExpectationClass expectationClass,
                      String errorCode,
                      List<ExactTextAnchor> anchors,
                      String expectationBaseline) {

        public Row {
            Objects.requireNonNull(capability, "capability must not be null");
            Objects.requireNonNull(pinId, "pinId must not be null");
            Objects.requireNonNull(locator, "locator must not be null");
            Objects.requireNonNull(authority, "authority must not be null");
            Objects.requireNonNull(family, "family must not be null");
            Objects.requireNonNull(expectationClass,
                "expectationClass must not be null");
            Objects.requireNonNull(anchors, "anchors must not be null");
            Objects.requireNonNull(expectationBaseline,
                "expectationBaseline must not be null");
            if (pinId.isBlank()) {
                throw new IllegalArgumentException("pinId must not be blank");
            }
            if (locator.isBlank()) {
                throw new IllegalArgumentException(
                    "locator must not be blank");
            }
            if (!BASELINE.matcher(expectationBaseline).matches()) {
                throw new IllegalArgumentException(
                    "expectationBaseline must be exactly 64 lowercase "
                        + "hex characters (a pinned SHA-256), got '"
                        + expectationBaseline + "'");
            }
            anchors = List.copyOf(anchors);
            if (isFixtureCaseLocator(locator)) {
                String[] parts = locator.split("#", 2);
                if (parts[0].isBlank() || parts[1].isBlank()) {
                    throw new IllegalArgumentException(
                        "fixture-case locator '" + locator
                            + "' must name a fixture file and a case");
                }
                if (authority != Authority.BACKEND_SLICE_JVM
                        && authority != Authority.CORPUS_JVM) {
                    throw new IllegalArgumentException(
                        "row '" + pinId + "': a fixture-case locator has "
                            + "a baseline derivation only under "
                            + "BACKEND_SLICE_JVM/CORPUS_JVM, got "
                            + authority + " (rejected at catalog authoring)");
                }
                if (errorCode != null || !anchors.isEmpty()) {
                    throw new IllegalArgumentException(
                        "row '" + pinId + "': errorCode and anchors are "
                            + "code-contract rows only (a fixture-case row "
                            + "carries neither)");
                }
            } else if (isCodeLocator(locator)) {
                if (authority != Authority.BACKEND_SOURCE_LUAJIT
                        && authority != Authority.BACKEND_SOURCE_JVM) {
                    throw new IllegalArgumentException(
                        "row '" + pinId + "': a code-contract locator has "
                            + "a baseline derivation only under "
                            + "BACKEND_SOURCE_LUAJIT/BACKEND_SOURCE_JVM, "
                            + "got " + authority
                            + " (rejected at catalog authoring)");
                }
                if (errorCode == null || errorCode.isBlank()) {
                    throw new IllegalArgumentException(
                        "row '" + pinId + "': a code-contract row must "
                            + "pin an errorCode");
                }
                if (anchors.isEmpty()) {
                    throw new IllegalArgumentException(
                        "row '" + pinId + "': a code-contract row must "
                            + "carry at least one exact-text anchor");
                }
                for (ExactTextAnchor anchor : anchors) {
                    if (!isCodeLocator(anchor.locator())) {
                        throw new IllegalArgumentException(
                            "row '" + pinId + "': anchor '" + anchor.anchorId()
                                + "' locator '" + anchor.locator()
                                + "' must be a file:line[-line] span");
                    }
                }
            } else {
                throw new IllegalArgumentException(
                    "row '" + pinId + "': locator '" + locator
                        + "' is neither a fixture-case locator nor a "
                        + "code-contract locator — no baseline derivation "
                        + "exists for it (rejected at catalog authoring)");
            }
        }
    }

    /**
     * The authored expectation surface of one fixture-case row (H1): the
     * pinned field values the baseline digests, kept as reference data
     * so a validation failure can name the changed expectation field.
     * Never updated from the tree (baselines are never auto-updated).
     */
    record AuthoredFixtureSurface(String caseName,
                                  String expectedOutput,
                                  String expectedError,
                                  Integer expectedExitCode,
                                  String expectedCompileError,
                                  List<String> expectedNotOutput,
                                  List<String> irContains,
                                  List<String> irNotContains,
                                  List<String> backends) {

        AuthoredFixtureSurface {
            Objects.requireNonNull(caseName, "caseName must not be null");
            Objects.requireNonNull(irContains, "irContains must not be null");
            Objects.requireNonNull(irNotContains, "irNotContains must not be null");
        }
    }

    /**
     * Registers one fixture-case row: the authored expectation surface
     * and the pinned baseline digest are both literals; class
     * initialization re-derives the digest over the authored surface and
     * requires equality with the pinned baseline (an authoring defect
     * fails loudly — a baseline can never be updated silently).
     */
    private static Row fixtureRow(SemanticCapability capability, Family family,
            String pinId, String file, String caseName,
            String expectedOutput, String expectedError,
            Integer expectedExitCode, String expectedCompileError,
            List<String> expectedNotOutput, List<String> irContains,
            List<String> irNotContains, List<String> backends,
            String expectationBaseline) {
        AuthoredFixtureSurface surface = new AuthoredFixtureSurface(caseName,
            expectedOutput, expectedError, expectedExitCode,
            expectedCompileError, expectedNotOutput == null ? null
                : List.copyOf(expectedNotOutput),
            List.copyOf(irContains), List.copyOf(irNotContains),
            List.copyOf(backends));
        AUTHORED_SURFACES.put(pinId, surface);
        return new Row(capability, pinId, file + "#" + caseName,
            Authority.BACKEND_SLICE_JVM, family, ExpectationClass.ExactPin,
            null, List.of(), expectationBaseline);
    }

    /** pinId → authored fixture expectation surface (field-naming reference). */
    private static final Map<String, AuthoredFixtureSurface>
        AUTHORED_SURFACES =
        new LinkedHashMap<>();

    // =========================================================================
    // The pinned pin list and capability mapping (H2)
    // =========================================================================

    /**
     * The closed catalog content (H2): the parent's pin list carried
     * verbatim with current-tree locators and the H2 family→capability
     * mapping. Expectation baselines are pinned literal digests computed
     * from the tree at authoring (see the H1 derivation); the
     * tree-resolved surfaces must reproduce them byte-for-byte.
     */
    static final List<Row> ROWS = List.of(
        // BINDINGS — ADAPTER (the Lua-only adapter capture pins)
        fixtureRow(SemanticCapability.BINDINGS, Family.ADAPTER,
            "jvm-fv-lua-ref-reassigned-adapter",
            "jvm-function-values-slice.json",
            "jvm-fv-lua-ref-reassigned-adapter",
            "jvm-fv-lua-ref-reassigned-adapter-ok", null, 0, null,
            null, List.of(), List.of(), List.of("luajit"),
            "f353304c8ce192e9d598249eb2317cbf083804fc9fd5b08bebf8541aac25cb9c"),
        fixtureRow(SemanticCapability.BINDINGS, Family.ADAPTER,
            "jvm-fv-lua-ref-callresult-adapter",
            "jvm-function-values-slice.json",
            "jvm-fv-lua-ref-callresult-adapter",
            "picked\npicked\njvm-fv-lua-ref-callresult-adapter-ok", null, 0,
            null, null, List.of(), List.of(), List.of("luajit"),
            "297d0b2c6bff074c50b56b7e31547d0b4db1aa70a6b7b4758e4a942e2ee314f5"),

        // CALLS — BOUNDARY_ARITY (the return-boundary E8010 pin)
        fixtureRow(SemanticCapability.CALLS, Family.BOUNDARY_ARITY,
            "jvm-fv-sig-check-return-error",
            "jvm-function-values-slice.json",
            "jvm-fv-sig-check-return-error",
            null, "E8010", 1, null,
            null, List.of(), List.of(), List.of("jvm"),
            "151fdf6ea85ddbf46af84b83872afda0c3dadd3178e96b50abaf9bf118658290"),

        // CALLS — ASYNC_HOST (the async host shape/completion pins)
        fixtureRow(SemanticCapability.CALLS, Family.ASYNC_HOST,
            "jvm-host-async-shape-bad",
            "jvm-host-abi-slice.json",
            "jvm-host-async-shape-bad",
            null, "E8010", 1, null,
            null, List.of(), List.of(), List.of("jvm"),
            "9da84e4174aa711ecd4b36a0535374c1463011e50013b13db38b537af168b8d2"),
        fixtureRow(SemanticCapability.CALLS, Family.ASYNC_HOST,
            "jvm-host-async-completion-bad",
            "jvm-host-abi-slice.json",
            "jvm-host-async-completion-bad",
            null, "E8001", 1, null,
            null, List.of(), List.of(), List.of("jvm"),
            "989c3b9e0b30907904cff3ed49039d3498a2baf7a45d9a991013d4ddcdc47a00"),

        // CALLS — IMPORTED_CALLS (imported sync/async call pins)
        fixtureRow(SemanticCapability.CALLS, Family.IMPORTED_CALLS,
            "jvm-mod-imported-direct-call",
            "jvm-modules-slice.json",
            "jvm-mod-imported-direct-call",
            "5", null, 0, null,
            null, List.of("import * as lib from \"./lib\""), List.of(),
            List.of("jvm"),
            "595be096243820e803123b7dc2e515fc318ec6ce1928c3f0e5449f2a5f74e732"),
        fixtureRow(SemanticCapability.CALLS, Family.IMPORTED_CALLS,
            "jvm-mod-imported-recursion",
            "jvm-modules-slice.json",
            "jvm-mod-imported-recursion",
            "720", null, 0, null,
            null, List.of(), List.of(), List.of("jvm"),
            "f5f40d4413645c3bdf303c943e6b841cfe3dec160b06d68344da0bcc6b018f44"),
        fixtureRow(SemanticCapability.CALLS, Family.IMPORTED_CALLS,
            "jvm-mod-imported-void-call",
            "jvm-modules-slice.json",
            "jvm-mod-imported-void-call",
            "from-lib", null, 0, null,
            null, List.of(), List.of(), List.of("jvm"),
            "4c1ce5581473d998c132558aad629b03603da61ef30dc39748adb49b5222e4e2"),
        fixtureRow(SemanticCapability.CALLS, Family.IMPORTED_CALLS,
            "jvm-async-function-value",
            "jvm-async-slice.json",
            "jvm-async-function-value",
            "6", null, 0, null,
            null,
            List.of("async function value: int", "let f: async()->int",
                "await : int"),
            List.of(), List.of("jvm"),
            "7c42ff692b10706aac86652a246529d337f3d7dd76cec5bc61d951d64f19618f"),
        fixtureRow(SemanticCapability.CALLS, Family.IMPORTED_CALLS,
            "jvm-async-function-value-callback",
            "jvm-async-slice.json",
            "jvm-async-function-value-callback",
            "7", null, 0, null,
            null,
            List.of("async function plus1: int",
                "ident cb : async(int)->int", "await : int"),
            List.of(), List.of("jvm"),
            "79b22be35aeb4d1c9a4c3455967d8e630d8b99a5739bd9d85c264ad0b4a5a0e3"),
        fixtureRow(SemanticCapability.CALLS, Family.IMPORTED_CALLS,
            "jvm-async-function-value-local-reassign",
            "jvm-async-slice.json",
            "jvm-async-function-value-local-reassign",
            "ab", null, 0, null,
            null,
            List.of("async function a: string", "async function b: string",
                "await : string"),
            List.of(), List.of("jvm"),
            "70673550677fc90d6a22c228463ed332ff08e989fab192aceb8d7c446d3ff6c7"),
        fixtureRow(SemanticCapability.CALLS, Family.IMPORTED_CALLS,
            "jvm-async-multi-module",
            "jvm-async-slice.json",
            "jvm-async-multi-module",
            "5", null, 0, null,
            null, List.of("await : string", "await : int"), List.of(),
            List.of("jvm"),
            "9bcdf194608716b2c641517dea38427637db4eefa3a31938b41aa85785870f14"),

        // CONTAINERS_AND_STRINGS — ARRAY_BOUNDS (array read/write bounds)
        fixtureRow(SemanticCapability.CONTAINERS_AND_STRINGS,
            Family.ARRAY_BOUNDS, "jvm-arr-negative-read-parity",
            "jvm-arrays-slice.json", "jvm-arr-negative-read-parity",
            null, "E8002", 1, null,
            null, List.of("index [] : int"), List.of(),
            List.of("luajit", "jvm"),
            "1619dc1cbdef2a0cb70aa29928db795209a2fadaec3e522adf8a3685420da1e3"),
        fixtureRow(SemanticCapability.CONTAINERS_AND_STRINGS,
            Family.ARRAY_BOUNDS, "jvm-arr-negative-write-e8002",
            "jvm-arrays-slice.json", "jvm-arr-negative-write-e8002",
            null, "E8002", 1, null,
            null, List.of("array-write"), List.of(), List.of("jvm"),
            "03520a6d557789adbbad1def04bfc97d3f957ece5c6b53292cc1e15b6ec219b8"),
        fixtureRow(SemanticCapability.CONTAINERS_AND_STRINGS,
            Family.ARRAY_BOUNDS, "jvm-arr-gap-write-e8002",
            "jvm-arrays-slice.json", "jvm-arr-gap-write-e8002",
            null, "E8002", 1, null,
            null, List.of("array-write"), List.of(), List.of("jvm"),
            "02639f224217382fb548992f0fa15772c3fb0bfed89b31b8fa74c6c6083dea50"),

        // CONTAINERS_AND_STRINGS — ARRAY_DELETE (the retained array-delete
        // contract: LuaJIT SameAsShared E8002, JVM BackendReject E6000 —
        // the only pins of the retained contract; no fixture case
        // exercises array delete, so both rows are code-contract rows
        // pinned over the located source bytes with exact-text anchors).
        new Row(SemanticCapability.CONTAINERS_AND_STRINGS,
            "lua-array-index-delete-e8002",
            "deal/codegen/lua/LuaBackend.java:2160-2182",
            Authority.BACKEND_SOURCE_LUAJIT, Family.ARRAY_DELETE,
            ExpectationClass.SameAsShared,
            "E8002",
            List.of(new ExactTextAnchor("e8002-bounds-before-nil-write",
                "deal/codegen/lua/LuaBackend.java:2160-2182",
                "if __idx < 0 or __idx > #__arr then error(__rt._err(\\\"E8002\\\", ")),
            "dbeb5c4ed64d681f6b734e3534b6294a4f7924c2de784e3b5dbcb3fb70e70f0e"),
        new Row(SemanticCapability.CONTAINERS_AND_STRINGS,
            "jvm-array-index-delete-e6000",
            "deal/codegen/jvm/JvmBackend.java:9670",
            Authority.BACKEND_SOURCE_JVM, Family.ARRAY_DELETE,
            ExpectationClass.BackendReject,
            "E6000",
            List.of(
                new ExactTextAnchor("delete-target-shape-rejection",
                    "deal/codegen/jvm/JvmBackend.java:9670",
                    "unsupported(\"delete of this target shape\", ds.span());"),
                new ExactTextAnchor("e6000-rejection-mapping",
                    "deal/codegen/jvm/JvmBackend.java:15236-15240",
                    "DiagnosticCode.E6000")),
            "0c4a2362ac0351d1e6e5f11b4841a938a81d4f0ba576cbf551772be2cfa2aa62"),

        // EVALUATION_ORDER — WRITE_ORDER (array-write evaluation order)
        fixtureRow(SemanticCapability.EVALUATION_ORDER, Family.WRITE_ORDER,
            "jvm-arr-eval-order-write",
            "jvm-arrays-slice.json", "jvm-arr-eval-order-write",
            "arr\nidx\nrhs\n42", null, 0, null,
            null, List.of("array-write"), List.of(), List.of("jvm"),
            "d594c78f24d6150c6dd2a1cd7483f34db4bd3734f221e4c4a6aa41148e49c445"),
        fixtureRow(SemanticCapability.EVALUATION_ORDER, Family.WRITE_ORDER,
            "jvm-arr-eval-order-write-hoisted-parity",
            "jvm-arrays-slice.json",
            "jvm-arr-eval-order-write-hoisted-parity",
            "a\nb\ni\nc\nv\nwrite-ok", null, 0, null,
            null, List.of("array-write"), List.of(),
            List.of("luajit", "jvm"),
            "ce65067deca1ea601878f7ce8defeebaeff4eb1632dee9785894006d49205d00"),

        // EVALUATION_ORDER — FIELD_WRITE (class field-write parity)
        fixtureRow(SemanticCapability.EVALUATION_ORDER, Family.FIELD_WRITE,
            "jvm-class-field-write-eval-order-parity",
            "jvm-classes-slice.json",
            "jvm-class-field-write-eval-order-parity",
            "target\nvalue\nmake", null, 0, null,
            null, List.of("class Box", "field x"), List.of(),
            List.of("luajit", "jvm"),
            "502afc79ce4fc6663c87df65b4030330929f87b38e7fc9050550a654b0d1ebd8"),

        // CLASSES — XMOD_CONSTRUCTION (cross-module construction pins)
        fixtureRow(SemanticCapability.CLASSES, Family.XMOD_CONSTRUCTION,
            "jvm-xmod-class-construction-defaults",
            "jvm-xmod-classes-slice.json",
            "jvm-xmod-class-construction-defaults",
            "217", null, 0, null,
            null, List.of("class Point", "[boundary: class-construct]"),
            List.of(), List.of("jvm"),
            "faaa0ca6b6a29fc61fa01719fb60c4df5eaae9df2a48df33434c9b77f760cc7a"),
        fixtureRow(SemanticCapability.CLASSES, Family.XMOD_CONSTRUCTION,
            "jvm-xmod-class-construction-eval-order",
            "jvm-xmod-classes-slice.json",
            "jvm-xmod-class-construction-eval-order",
            "y-first\nx-second\n7", null, 0, null,
            null, List.of(), List.of(), List.of("jvm"),
            "da45f98373cd12d0764fde2ec99b0db0cf8857311376430cd8e579d2c2a4eb84")
    );

    // =========================================================================
    // Canonical baseline derivations (H1, closed)
    // =========================================================================

    /**
     * The canonical fixture-case expectation record (H1): the nine
     * pinned fields, absent optional fields as {@code null}, arrays in
     * authored order — serialized through {@code CanonicalJson}.
     */
    static CanonicalJson.Obj fixtureExpectationRecord(
            AuthoredFixtureSurface surface) {
        return CanonicalJson.obj(
            CanonicalJson.e("caseName", strOrNull(surface.caseName())),
            CanonicalJson.e("expectedOutput",
                strOrNull(surface.expectedOutput())),
            CanonicalJson.e("expectedError",
                strOrNull(surface.expectedError())),
            CanonicalJson.e("expectedExitCode",
                surface.expectedExitCode() == null ? CanonicalJson.nullValue()
                    : CanonicalJson.intValue(surface.expectedExitCode())),
            CanonicalJson.e("expectedCompileError",
                strOrNull(surface.expectedCompileError())),
            CanonicalJson.e("expectedNotOutput",
                listOrNull(surface.expectedNotOutput())),
            CanonicalJson.e("irContains",
                listOrNull(surface.irContains())),
            CanonicalJson.e("irNotContains",
                listOrNull(surface.irNotContains())),
            CanonicalJson.e("backends", listOrNull(surface.backends())));
    }

    private static CanonicalJson.Value strOrNull(String value) {
        return value == null ? CanonicalJson.nullValue()
            : CanonicalJson.str(value);
    }

    private static CanonicalJson.Value listOrNull(List<String> values) {
        if (values == null) {
            return CanonicalJson.nullValue();
        }
        List<CanonicalJson.Value> items = new ArrayList<>();
        for (String value : values) {
            items.add(CanonicalJson.str(value));
        }
        return CanonicalJson.arr(items);
    }

    /**
     * The canonical code-contract record (H1): {@code {locator, errorCode,
     * anchors, spans}} with anchors in authored order and {@code spans} =
     * the exact source bytes of the primary locator span followed by each
     * anchor's span bytes, read from the tree at validation — never from
     * the row. Span bytes map to the JSON string one byte per Latin-1
     * character (a lossless byte roundtrip).
     */
    static CanonicalJson.Obj codeContractRecord(String locator,
            String errorCode, List<ExactTextAnchor> anchors, String spans) {
        List<CanonicalJson.Value> anchorValues = new ArrayList<>();
        for (ExactTextAnchor anchor : anchors) {
            anchorValues.add(CanonicalJson.obj(
                CanonicalJson.e("anchorId",
                    CanonicalJson.str(anchor.anchorId())),
                CanonicalJson.e("locator",
                    CanonicalJson.str(anchor.locator())),
                CanonicalJson.e("text", CanonicalJson.str(anchor.text()))));
        }
        return CanonicalJson.obj(
            CanonicalJson.e("locator", CanonicalJson.str(locator)),
            CanonicalJson.e("errorCode", CanonicalJson.str(errorCode)),
            CanonicalJson.e("anchors", CanonicalJson.arr(anchorValues)),
            CanonicalJson.e("spans", CanonicalJson.str(spans)));
    }

    /** The canonical digest: lowercase SHA-256 over the canonical bytes. */
    private static String digest(CanonicalJson.Obj record) {
        return CanonicalJson.sha256Hex(CanonicalJson.serializeBytes(record));
    }

    // =========================================================================
    // Tree resolution of expectation surfaces (H1, closed)
    // =========================================================================

    /**
     * Resolves one fixture case's expectation surface from the runner's
     * parsed fixture index (fixture file → case name → raw JSON case
     * map). Returns {@code null} when the file or the case is missing.
     * Absent or null fields resolve to {@code null} exactly like the
     * authored surface; the runner's private JSON-null sentinel is
     * tolerated (it is not a {@code String}/{@code List}/{@code Number}).
     */
    static AuthoredFixtureSurface resolveFixtureSurface(Row row,
            Map<String, Map<String, Map<String, Object>>> sliceIndex) {
        String[] parts = row.locator().split("#", 2);
        Map<String, Map<String, Object>> file = sliceIndex.get(parts[0]);
        if (file == null) {
            return null;
        }
        Map<String, Object> test = file.get(parts[1]);
        if (test == null) {
            return null;
        }
        return new AuthoredFixtureSurface(parts[1],
            jsonString(test, "expectedOutput"),
            jsonString(test, "expectedError"),
            jsonInteger(test, "expectedExitCode"),
            jsonString(test, "expectedCompileError"),
            jsonStringList(test, "expectedNotOutput"),
            jsonStringList(test, "irContains"),
            jsonStringList(test, "irNotContains"),
            jsonStringList(test, "backends"));
    }

    private static String jsonString(Map<String, Object> test, String key) {
        Object value = test.get(key);
        return value instanceof String s ? s : null;
    }

    private static Integer jsonInteger(Map<String, Object> test, String key) {
        Object value = test.get(key);
        if (value instanceof Number n && !(value instanceof Float
                || value instanceof Double)) {
            return n.intValue();
        }
        if (value instanceof Double d && d == Math.rint(d)) {
            return d.intValue();
        }
        return null;
    }

    private static List<String> jsonStringList(Map<String, Object> test,
            String key) {
        Object value = test.get(key);
        if (!(value instanceof List<?> list)) {
            return null;
        }
        List<String> strings = new ArrayList<>();
        for (Object item : list) {
            strings.add(String.valueOf(item));
        }
        return strings;
    }

    /**
     * A parsed {@code file:line[-line]} span locator (shared with
     * {@link LegacyCapabilityCatalog} for evidence-locator resolution).
     */
    static record SpanHolder(String file, int startLine, int endLine) {
    }

    static SpanHolder parseSpanHolder(String locator) {
        int colon = locator.lastIndexOf(':');
        String file = locator.substring(0, colon);
        String lines = locator.substring(colon + 1);
        int dash = lines.indexOf('-');
        int startLine;
        int endLine;
        try {
            if (dash >= 0) {
                startLine = Integer.parseInt(lines.substring(0, dash));
                endLine = Integer.parseInt(lines.substring(dash + 1));
            } else {
                startLine = Integer.parseInt(lines);
                endLine = startLine;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        if (file.isBlank() || startLine < 1 || endLine < startLine) {
            return null;
        }
        return new SpanHolder(file, startLine, endLine);
    }

    /**
     * Reads one span's exact source bytes from the tree: the file bytes
     * from the start of {@code startLine} through the end of
     * {@code endLine} (each line including its terminator, if any).
     * Returns {@code null} when the file is missing or the lines are out
     * of bounds.
     */
    /** True when the span's lines are in bounds of the file bytes. */
    static boolean spanInBounds(byte[] fileBytes, SpanHolder span) {
        List<Integer> lineStarts = new ArrayList<>();
        lineStarts.add(0);
        for (int i = 0; i < fileBytes.length; i++) {
            if (fileBytes[i] == '\n') {
                lineStarts.add(i + 1);
            }
        }
        int lineCount = lineStarts.size();
        return span.startLine() <= lineCount && span.endLine() <= lineCount;
    }

    /**
     * Reads one span's exact source bytes from the tree: the file bytes
     * from the start of {@code startLine} through the end of
     * {@code endLine} (each line including its terminator, if any).
     * Returns {@code null} when the lines are out of bounds.
     */
    private static byte[] readSpanBytes(byte[] fileBytes,
            SpanHolder span) {
        List<Integer> lineStarts = new ArrayList<>();
        lineStarts.add(0);
        for (int i = 0; i < fileBytes.length; i++) {
            if (fileBytes[i] == '\n') {
                lineStarts.add(i + 1);
            }
        }
        int lineCount = lineStarts.size();
        if (span.startLine() > lineCount || span.endLine() > lineCount) {
            return null;
        }
        int startOffset = lineStarts.get(span.startLine() - 1);
        int endOffset = span.endLine() < lineCount
            ? lineStarts.get(span.endLine()) : fileBytes.length;
        return Arrays.copyOfRange(fileBytes, startOffset, endOffset);
    }

    /** True when the anchor's UTF-8 bytes occur verbatim in the span bytes. */
    private static boolean anchorTextPresent(byte[] spanBytes, String text) {
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        if (needle.length == 0 || needle.length > spanBytes.length) {
            return false;
        }
        outer:
        for (int i = 0; i + needle.length <= spanBytes.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (spanBytes[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** Maps the concatenated span bytes to a JSON string (Latin-1, lossless). */
    private static String spansString(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            sb.append((char) (b & 0xff));
        }
        return sb.toString();
    }

    // =========================================================================
    // Validation (H1 contract)
    // =========================================================================

    /** The pinned failure class: the pin's locator resolves nowhere. */
    static final String LOCATOR_DANGLED = "HISTORICAL_LOCATOR_DANGLED";

    /** The pinned failure class: the pin resolves but never executes. */
    static final String PIN_INACTIVE = "HISTORICAL_PIN_INACTIVE";

    /** The pinned failure class: the pin's expectation surface changed. */
    static final String EXPECTATION_CHANGED =
        "HISTORICAL_PIN_EXPECTATION_CHANGED";

    private static String locatorDangled(String pinId, String detail) {
        return LOCATOR_DANGLED + " " + pinId + " — " + detail;
    }

    private static String pinInactive(String pinId, String detail) {
        return PIN_INACTIVE + " " + pinId + " — " + detail;
    }

    private static String expectationChanged(String pinId, String detail) {
        return EXPECTATION_CHANGED + " " + pinId + " — " + detail;
    }

    /**
     * Validates one fixture-case row against the runner's parsed fixture
     * index: the locator resolves (file + case), the assertion executes
     * in its declared authority (a non-empty {@code backends} array —
     * a narrowed/emptied backend list executes the case nowhere), and
     * the tree-resolved expectation surface's digest equals the pinned
     * baseline. A digest mismatch names the changed expectation field
     * against the authored surface.
     */
    static List<String> validateFixtureRow(Row row,
            Map<String, Map<String, Map<String, Object>>> sliceIndex) {
        List<String> violations = new ArrayList<>();
        String[] parts = row.locator().split("#", 2);
        Map<String, Map<String, Object>> file = sliceIndex.get(parts[0]);
        if (file == null) {
            violations.add(locatorDangled(row.pinId(), "fixture file '"
                + parts[0] + "' is not in the runner's parsed fixture "
                + "index"));
            return violations;
        }
        if (!file.containsKey(parts[1])) {
            violations.add(locatorDangled(row.pinId(), "case '" + parts[1]
                + "' is not in fixture file '" + parts[0] + "'"));
            return violations;
        }
        AuthoredFixtureSurface resolved = resolveFixtureSurface(row,
            sliceIndex);
        if (resolved.backends() == null || resolved.backends().isEmpty()) {
            violations.add(pinInactive(row.pinId(), "case '" + parts[1]
                + "' executes on no backend (its 'backends' array is "
                + "missing or empty) — the pin's assertion no longer "
                + "executes in its declared authority"));
            return violations;
        }
        String treeDigest = digest(fixtureExpectationRecord(resolved));
        if (!treeDigest.equals(row.expectationBaseline())) {
            violations.add(expectationChanged(row.pinId(),
                changedFixtureField(row, resolved, treeDigest)));
        }
        return violations;
    }

    /** Names the first expectation field whose tree value differs from
     * the authored surface (the H1 visible-error contract). */
    private static String changedFixtureField(Row row,
            AuthoredFixtureSurface resolved, String treeDigest) {
        AuthoredFixtureSurface authored = AUTHORED_SURFACES.get(row.pinId());
        if (authored == null) {
            return "the resolved expectation surface's digest '" + treeDigest
                + "' differs from the pinned baseline (no authored surface "
                + "is recorded for field naming)";
        }
        if (!Objects.equals(authored.caseName(), resolved.caseName())) {
            return "expectation field caseName changed: pinned '"
                + authored.caseName() + "', tree '" + resolved.caseName()
                + "'";
        }
        if (!Objects.equals(authored.expectedOutput(),
                resolved.expectedOutput())) {
            return "expectation field expectedOutput changed: pinned "
                + describe(authored.expectedOutput()) + ", tree "
                + describe(resolved.expectedOutput());
        }
        if (!Objects.equals(authored.expectedError(),
                resolved.expectedError())) {
            return "expectation field expectedError changed: pinned "
                + describe(authored.expectedError()) + ", tree "
                + describe(resolved.expectedError());
        }
        if (!Objects.equals(authored.expectedExitCode(),
                resolved.expectedExitCode())) {
            return "expectation field expectedExitCode changed: pinned "
                + describe(authored.expectedExitCode()) + ", tree "
                + describe(resolved.expectedExitCode());
        }
        if (!Objects.equals(authored.expectedCompileError(),
                resolved.expectedCompileError())) {
            return "expectation field expectedCompileError changed: pinned "
                + describe(authored.expectedCompileError()) + ", tree "
                + describe(resolved.expectedCompileError());
        }
        if (!Objects.equals(authored.expectedNotOutput(),
                resolved.expectedNotOutput())) {
            return "expectation field expectedNotOutput changed: pinned "
                + describe(authored.expectedNotOutput()) + ", tree "
                + describe(resolved.expectedNotOutput());
        }
        if (!Objects.equals(authored.irContains(), resolved.irContains())) {
            return "expectation field irContains changed: pinned "
                + describe(authored.irContains()) + ", tree "
                + describe(resolved.irContains());
        }
        if (!Objects.equals(authored.irNotContains(),
                resolved.irNotContains())) {
            return "expectation field irNotContains changed: pinned "
                + describe(authored.irNotContains()) + ", tree "
                + describe(resolved.irNotContains());
        }
        return "expectation field backends changed: pinned "
            + describe(authored.backends()) + ", tree "
            + describe(resolved.backends());
    }

    private static String describe(Object value) {
        return value == null ? "null" : "'" + value + "'";
    }

    /**
     * Validates one code-contract row against the tree: every locator
     * resolves in-bounds, every anchor's text occurs verbatim within its
     * span's bytes, and the tree-recomputed record's digest equals the
     * pinned baseline. A missing anchor text or changed span bytes fails
     * {@code HISTORICAL_PIN_EXPECTATION_CHANGED} naming the anchor or the
     * changed span.
     */
    static List<String> validateCodeRow(Row row, Path sourceRoot) {
        List<String> violations = new ArrayList<>();
        SpanHolder primarySpan = parseSpanHolder(row.locator());
        if (primarySpan == null) {
            violations.add(locatorDangled(row.pinId(), "locator '"
                + row.locator() + "' is not a file:line[-line] span"));
            return violations;
        }
        Path primaryFile = sourceRoot.resolve(primarySpan.file());
        byte[] primaryBytes;
        try {
            primaryBytes = Files.readAllBytes(primaryFile);
        } catch (Exception e) {
            violations.add(locatorDangled(row.pinId(), "source file '"
                + primarySpan.file() + "' does not resolve in-tree"));
            return violations;
        }
        byte[] primarySpanBytes = readSpanBytes(primaryBytes, primarySpan);
        if (primarySpanBytes == null) {
            violations.add(locatorDangled(row.pinId(), "span '"
                + row.locator() + "' is out of bounds in '"
                + primarySpan.file() + "'"));
            return violations;
        }
        StringBuilder spans = new StringBuilder();
        spans.append(spansString(primarySpanBytes));
        for (ExactTextAnchor anchor : row.anchors()) {
            SpanHolder anchorSpan = parseSpanHolder(anchor.locator());
            if (anchorSpan == null) {
                violations.add(locatorDangled(row.pinId(), "anchor '"
                    + anchor.anchorId() + "' locator '" + anchor.locator()
                    + "' is not a file:line[-line] span"));
                continue;
            }
            Path anchorFile = sourceRoot.resolve(anchorSpan.file());
            byte[] anchorBytes;
            try {
                anchorBytes = Files.readAllBytes(anchorFile);
            } catch (Exception e) {
                violations.add(locatorDangled(row.pinId(), "anchor '"
                    + anchor.anchorId() + "' source file '"
                    + anchorSpan.file() + "' does not resolve in-tree"));
                continue;
            }
            byte[] anchorSpanBytes = readSpanBytes(anchorBytes, anchorSpan);
            if (anchorSpanBytes == null) {
                violations.add(locatorDangled(row.pinId(), "anchor '"
                    + anchor.anchorId() + "' span '" + anchor.locator()
                    + "' is out of bounds in '" + anchorSpan.file() + "'"));
                continue;
            }
            if (!anchorTextPresent(anchorSpanBytes, anchor.text())) {
                violations.add(expectationChanged(row.pinId(),
                    "code-contract anchor text '" + anchor.anchorId()
                        + "' no longer occurs verbatim in its span "
                        + anchor.locator() + " (the anchor text is absent "
                        + "from the located source bytes)"));
            }
            spans.append(spansString(anchorSpanBytes));
        }
        if (!violations.isEmpty()) {
            return violations;
        }
        String treeDigest = digest(codeContractRecord(row.locator(),
            row.errorCode(), row.anchors(), spans.toString()));
        if (!treeDigest.equals(row.expectationBaseline())) {
            violations.add(expectationChanged(row.pinId(),
                "code-contract span bytes changed for the primary span '"
                    + row.locator() + "' (every anchor text still occurs, "
                    + "so the located source bytes themselves changed — "
                    + "recomputed digest '" + treeDigest + "')"));
        }
        return violations;
    }

    /**
     * Validates every code-contract row against the source tree. Called
     * at harness startup by every conformance runner that can resolve
     * the located sources (the {@code LegacyProfileRegressionCatalog}
     * precedent: slice rows are validated separately where the parsed
     * fixture index exists).
     */
    public static List<String> validateSourceRows(Path sourceRoot) {
        List<String> violations = new ArrayList<>();
        for (Row row : ROWS) {
            if (isCodeLocator(row.locator())) {
                violations.addAll(validateCodeRow(row, sourceRoot));
            }
        }
        return violations;
    }

    /**
     * Validates every fixture-case row against the runner's parsed
     * fixture index. Called at harness startup by the JSON-slice runner
     * (the authority that executes the pins) and by the promotion gate's
     * historical item.
     */
    public static List<String> validateFixtureRows(
            Map<String, Map<String, Map<String, Object>>> sliceIndex) {
        List<String> violations = new ArrayList<>();
        for (Row row : ROWS) {
            if (isFixtureCaseLocator(row.locator())) {
                violations.addAll(validateFixtureRow(row, sliceIndex));
            }
        }
        return violations;
    }

    /** Validates every row of both kinds (harness startup / gate). */
    public static List<String> validateAll(
            Map<String, Map<String, Map<String, Object>>> sliceIndex,
            Path sourceRoot) {
        List<String> violations = new ArrayList<>();
        violations.addAll(validateFixtureRows(sliceIndex));
        violations.addAll(validateSourceRows(sourceRoot));
        return violations;
    }

    // =========================================================================
    // LegacyProfileRegressionCatalog consumption (H3)
    // =========================================================================

    /**
     * The pinned safe-int row locators of the landed A4 authority (H3):
     * {@code jvm-skeleton.json#jvm-int-safe-range-*},
     * {@code jvm-stdlib-slice.json#jvm-std-math-int}, and
     * {@code jvm-stdlib-slice.json#jvm-std-time-nowmillis}. These are the
     * {@code SIGNED_INT32} historical item's legacy rows — they pass
     * unchanged under {@code LEGACY_REGRESSION} in every runner that
     * executes them and earn zero v1.2 credit.
     */
    private static boolean isSafeIntRow(String locator) {
        return locator.equals("jvm-stdlib-slice.json#jvm-std-math-int")
            || locator.equals("jvm-stdlib-slice.json#jvm-std-time-nowmillis")
            || (locator.startsWith("jvm-skeleton.json#")
                && locator.substring("jvm-skeleton.json#".length())
                    .startsWith("jvm-int-safe-range-"));
    }

    /** The pinned safe-int locators, in catalog order, exactly once. */
    private static final Set<String> SIGNED_INT32_LEGACY_ROWS =
        computeSignedInt32LegacyRows();

    private static Set<String> computeSignedInt32LegacyRows() {
        Set<String> locators = new java.util.LinkedHashSet<>();
        for (LegacyProfileRegressionCatalog.Row row
                : LegacyProfileRegressionCatalog.ROWS) {
            if (isSafeIntRow(row.locator())) {
                locators.add(row.locator());
            }
        }
        return Collections.unmodifiableSet(locators);
    }

    /** The non-excluded additive v1.2 replacements of the safe-int rows. */
    private static final List<String> SIGNED_INT32_REPLACEMENT_ROWS =
        computeSignedInt32ReplacementRows();

    private static List<String> computeSignedInt32ReplacementRows() {
        List<String> locators = new ArrayList<>();
        for (LegacyProfileRegressionCatalog.Row row
                : LegacyProfileRegressionCatalog.ROWS) {
            if (isSafeIntRow(row.locator())
                    && row.additiveReplacement() != null) {
                locators.add(row.additiveReplacement());
            }
        }
        return List.copyOf(locators);
    }

    /** True when the locator is a pinned safe-int legacy row. */
    public static boolean isSignedInt32LegacyLocator(String locator) {
        return SIGNED_INT32_LEGACY_ROWS.contains(locator);
    }

    /** True when the locator is a pinned additive v1.2 replacement. */
    public static boolean isSignedInt32ReplacementLocator(String locator) {
        return SIGNED_INT32_REPLACEMENT_ROWS.contains(locator);
    }

    /** The pinned safe-int locators, in catalog order, exactly once. */
    public static List<String> signedInt32LegacyRows() {
        List<String> locators = new ArrayList<>();
        for (LegacyProfileRegressionCatalog.Row row
                : LegacyProfileRegressionCatalog.ROWS) {
            if (isSafeIntRow(row.locator())) {
                locators.add(row.locator());
            }
        }
        return List.copyOf(locators);
    }

    /** The non-excluded additive v1.2 replacements of the safe-int rows. */
    public static List<String> signedInt32ReplacementRows() {
        return SIGNED_INT32_REPLACEMENT_ROWS;
    }

    /**
     * The H3 consumption checks for the {@code SIGNED_INT32} historical
     * item: the pinned safe-int rows stay catalogued and route through
     * {@code LEGACY_REGRESSION + LEGACY_SAFE_INT} with credit
     * {@code none} (a legacy-profile result earns no v1.2 credit), and
     * every non-excluded row's additive v1.2 replacement is uncatalogued
     * (it runs under the v1.2 profile) and resolves on disk.
     */
    static List<String> signedInt32LegacyConsumptionViolations() {
        List<String> violations = new ArrayList<>();
        List<String> safeIntRows = signedInt32LegacyRows();
        if (safeIntRows.isEmpty()) {
            violations.add("SIGNED_INT32 legacy consumption: the pinned "
                + "safe-int row set (jvm-std-math-int, "
                + "jvm-int-safe-range-*, jvm-std-time-nowmillis) is empty "
                + "in LegacyProfileRegressionCatalog — a dropped A4 row "
                + "silently weakens the SIGNED_INT32 historical item");
            return violations;
        }
        for (LegacyProfileRegressionCatalog.Row row
                : LegacyProfileRegressionCatalog.ROWS) {
            if (!isSafeIntRow(row.locator())) {
                continue;
            }
            if (!"none".equals(row.credit())) {
                violations.add("SIGNED_INT32 legacy consumption: safe-int "
                    + "row '" + row.locator() + "' carries credit '"
                    + row.credit() + "' instead of the pinned 'none' — a "
                    + "legacy-profile pass must earn zero promotion credit");
            }
            if (row.purpose()
                    != InvocationPurpose.LEGACY_REGRESSION
                    || row.profile() != SemanticProfile.LEGACY_SAFE_INT) {
                violations.add("SIGNED_INT32 legacy consumption: safe-int "
                    + "row '" + row.locator() + "' must stay closed to "
                    + "LEGACY_REGRESSION + LEGACY_SAFE_INT (it passes "
                    + "unchanged under the legacy regression invocation), "
                    + "got " + row.purpose() + " + " + row.profile());
            }
            String replacement = row.additiveReplacement();
            if (replacement == null) {
                continue; // excluded family (the time lock): no replacement
            }
            if (LegacyProfileRegressionCatalog.isCatalogued(replacement)) {
                violations.add("SIGNED_INT32 legacy consumption: additive "
                    + "replacement '" + replacement + "' for safe-int row '"
                    + row.locator() + "' is itself catalogued — an "
                    + "additive replacement must run under the v1.2 "
                    + "profile, never the legacy regression authority");
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
                violations.add("SIGNED_INT32 legacy consumption: additive "
                    + "replacement '" + replacement + "' for safe-int row '"
                    + row.locator() + "' names no on-disk replacement "
                    + "artifact (a missing additive v1.2 replacement — "
                    + "the pairing contract requires every non-excluded "
                    + "row to name an existing replacement that passes "
                    + "under DEAL_V1_2_INT32)");
            }
        }
        return violations;
    }

    // =========================================================================
    // Promotion-gate inputs (consumed by the gate child)
    // =========================================================================

    /**
     * The input of the promotion gate's {@code HISTORICAL_TESTS} item
     * for one capability: every catalog row mapped to the capability
     * must resolve, execute, and reproduce its pinned baseline; for
     * {@code SIGNED_INT32} the landed {@code LegacyProfileRegressionCatalog}
     * safe-int rows' consumption checks (H3) join the verdict.
     */
    public record HistoricalGateInput(SemanticCapability capability,
                                      boolean pass,
                                      int pinnedRows,
                                      List<String> violations) {

        public HistoricalGateInput {
            Objects.requireNonNull(capability, "capability must not be null");
            Objects.requireNonNull(violations, "violations must not be null");
            violations = List.copyOf(violations);
        }
    }

    /**
     * Computes the {@code HISTORICAL_TESTS} gate input for one
     * capability over the given tree/slice resolution environments (the
     * real tree and the runner's parsed fixture index at gate time).
     */
    public static HistoricalGateInput historicalItem(
            SemanticCapability capability,
            Path sourceRoot,
            Map<String, Map<String, Map<String, Object>>> sliceIndex) {
        List<String> violations = new ArrayList<>();
        int pinnedRows = 0;
        for (Row row : ROWS) {
            if (row.capability() != capability) {
                continue;
            }
            pinnedRows++;
            if (isCodeLocator(row.locator())) {
                violations.addAll(validateCodeRow(row, sourceRoot));
            } else {
                violations.addAll(validateFixtureRow(row, sliceIndex));
            }
        }
        if (capability == SemanticCapability.SIGNED_INT32) {
            violations.addAll(signedInt32LegacyConsumptionViolations());
            violations.addAll(executedEvidenceViolations(false));
        }
        return new HistoricalGateInput(capability, violations.isEmpty(),
            pinnedRows, violations);
    }

    // =========================================================================
    // Attainable-evidence floors (H5)
    // =========================================================================

    /**
     * The pinned per-capability family sets (H2): every family in the
     * set must keep at least one active row — a vacated family drops the
     * corpus below the attainable floor.
     */
    private static final Map<SemanticCapability, Set<Family>> FAMILY_FLOORS =
        buildFamilyFloors();

    private static Map<SemanticCapability, Set<Family>> buildFamilyFloors() {
        Map<SemanticCapability, Set<Family>> floors =
            new EnumMap<>(SemanticCapability.class);
        floors.put(SemanticCapability.BINDINGS, EnumSet.of(Family.ADAPTER));
        floors.put(SemanticCapability.CALLS, EnumSet.of(
            Family.BOUNDARY_ARITY, Family.ASYNC_HOST, Family.IMPORTED_CALLS));
        floors.put(SemanticCapability.CONTAINERS_AND_STRINGS, EnumSet.of(
            Family.ARRAY_BOUNDS, Family.ARRAY_DELETE));
        floors.put(SemanticCapability.EVALUATION_ORDER, EnumSet.of(
            Family.WRITE_ORDER, Family.FIELD_WRITE));
        floors.put(SemanticCapability.CLASSES,
            EnumSet.of(Family.XMOD_CONSTRUCTION));
        return Collections.unmodifiableMap(floors);
    }

    /** The rows mapped to one capability, in catalog order. */
    private static List<Row> rowsFor(SemanticCapability capability) {
        List<Row> rows = new ArrayList<>();
        for (Row row : ROWS) {
            if (row.capability() == capability) {
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * The attainable-evidence floor violations (H5): every historical
     * target-specific pin stays active with its baseline unchanged;
     * every pinned {@code (capability, family)} pair keeps at least one
     * active row; the retained array-delete contract keeps its LuaJIT
     * {@code SameAsShared} pin and its JVM {@code BackendReject} E6000
     * pin; and every {@code LegacyCapabilityCatalog} row's evidence
     * locator resolves. A validated unsupported state cannot replace any
     * of this attainable retained evidence.
     */
    public static List<String> retainedAssignmentFloorViolations(
            Path sourceRoot,
            Map<String, Map<String, Map<String, Object>>> sliceIndex) {
        List<String> violations = new ArrayList<>();
        violations.addAll(validateAll(sliceIndex, sourceRoot));
        for (Map.Entry<SemanticCapability, Set<Family>> entry
                : FAMILY_FLOORS.entrySet()) {
            SemanticCapability capability = entry.getKey();
            Set<Family> pinnedFamilies = entry.getValue();
            Set<Family> actualFamilies = EnumSet.noneOf(Family.class);
            for (Row row : rowsFor(capability)) {
                actualFamilies.add(row.family());
            }
            if (!actualFamilies.equals(pinnedFamilies)) {
                Set<Family> missing = EnumSet.copyOf(pinnedFamilies);
                missing.removeAll(actualFamilies);
                violations.add("RETAINED_FLOOR_VIOLATION capability "
                    + capability + ": the pinned family set "
                    + pinnedFamilies + " is not covered by the catalog's "
                    + actualFamilies + " — the vacated families " + missing
                    + " drop the corpus below the attainable floor (every "
                    + "pinned family must keep at least one active "
                    + "historical pin)");
            }
        }
        boolean luaDeleteActive = false;
        boolean jvmDeleteActive = false;
        for (Row row : rowsFor(SemanticCapability.CONTAINERS_AND_STRINGS)) {
            if (row.family() == Family.ARRAY_DELETE) {
                if (row.expectationClass() == ExpectationClass.SameAsShared
                        && validateCodeRow(row, sourceRoot).isEmpty()) {
                    luaDeleteActive = true;
                }
                if (row.expectationClass() == ExpectationClass.BackendReject
                        && validateCodeRow(row, sourceRoot).isEmpty()) {
                    jvmDeleteActive = true;
                }
            }
        }
        if (!luaDeleteActive) {
            violations.add("RETAINED_FLOOR_VIOLATION: the retained "
                + "array-delete LuaJIT SameAsShared pin is not active — "
                + "the retained supported delete operation family keeps "
                + "no SameAsShared evidence");
        }
        if (!jvmDeleteActive) {
            violations.add("RETAINED_FLOOR_VIOLATION: the retained "
                + "array-delete JVM BackendReject E6000 pin is not active "
                + "— the retained rejection class keeps no exact "
                + "rejection evidence");
        }
        violations.addAll(LegacyCapabilityCatalog.validateRows(
            sourceRoot, sliceIndex));
        return violations;
    }

    /**
     * The input of the promotion gate's {@code RETAINED_ASSIGNMENTS}
     * item: every assignment must validate against exactly one
     * release-owned {@code LegacyCapabilityCatalog} row and the
     * attainable floors must hold.
     */
    public record RetainedAssignmentsInput(boolean pass,
                                           int assignmentCount,
                                           int floorPinCount,
                                           List<String> violations) {

        public RetainedAssignmentsInput {
            Objects.requireNonNull(violations, "violations must not be null");
            violations = List.copyOf(violations);
        }
    }

    /** Computes the {@code RETAINED_ASSIGNMENTS} gate input. */
    public static RetainedAssignmentsInput retainedAssignmentsItem(
            Path sourceRoot,
            Map<String, Map<String, Map<String, Object>>> sliceIndex,
            List<LegacyCapabilityCatalog.AssignmentValidation>
                assignmentValidations) {
        List<String> violations = new ArrayList<>();
        for (LegacyCapabilityCatalog.AssignmentValidation validation
                : assignmentValidations) {
            if (!validation.valid()) {
                violations.add(validation.failure());
            }
        }
        violations.addAll(retainedAssignmentFloorViolations(sourceRoot,
            sliceIndex));
        return new RetainedAssignmentsInput(violations.isEmpty(),
            assignmentValidations.size(), ROWS.size(), violations);
    }

    // =========================================================================
    // Wired-runner execution evidence (H3 consumption)
    // =========================================================================

    /** Safe-int legacy rows dispatched by a wired runner (set-once facts). */
    private static final Set<String> legacyExecuted =
        ConcurrentHashMap.newKeySet();

    /** v1.2 replacement rows dispatched by a wired runner. */
    private static final Set<String> v12Executed =
        ConcurrentHashMap.newKeySet();

    /** Exact per-case outcomes recorded by single-threaded runners. */
    private static final Map<String, Boolean> exactOutcomes =
        new ConcurrentHashMap<>();

    /**
     * Records that a wired runner dispatched the locator under the
     * legacy regression authority (an execution fact, not an outcome —
     * the runner's own gate verdict supplies the outcome).
     */
    public static void recordLegacyExecution(String locator) {
        legacyExecuted.add(locator);
    }

    /**
     * Records that a wired runner dispatched the locator under the v1.2
     * invocation (an execution fact, not an outcome).
     */
    public static void recordV12Execution(String locator) {
        v12Executed.add(locator);
    }

    /**
     * Records an exact per-case outcome (single-threaded runners whose
     * per-case pass/fail attribution is exact).
     */
    public static void recordExactOutcome(String locator, boolean passed) {
        exactOutcomes.put(locator, passed);
    }

    /**
     * The executed-evidence violations: every safe-int row needs at
     * least one wired runner's execution under the legacy authority and
     * every non-excluded replacement needs one under the v1.2 profile;
     * every recorded exact outcome must have passed; a global failure in
     * a runner that executed the rows voids the evidence.
     */
    public static List<String> executedEvidenceViolations(
            boolean globalFailure) {
        return executedEvidenceViolations(signedInt32LegacyRows(),
            signedInt32ReplacementRows(), globalFailure);
    }

    /**
     * The executed-evidence violations over one runner's required
     * locators: every required locator needs this runner's execution
     * (a dispatched execution fact or an exact recorded outcome), every
     * recorded exact outcome must have passed, and a global failure in
     * this runner voids the evidence. The full-set variant
     * ({@link #executedEvidenceViolations(boolean)}) is the promotion
     * gate's composition.
     */
    public static List<String> executedEvidenceViolations(
            List<String> requiredLegacyLocators,
            List<String> requiredV12Locators,
            boolean globalFailure) {
        List<String> violations = new ArrayList<>();
        for (String locator : requiredLegacyLocators) {
            if (!legacyExecuted.contains(locator)
                    && !exactOutcomes.containsKey(locator)) {
                violations.add("HISTORICAL_EVIDENCE_MISSING " + locator
                    + " — the safe-int legacy row was not executed under "
                    + "LEGACY_REGRESSION by this runner (the H3 "
                    + "historical item needs the unchanged legacy "
                    + "execution evidence)");
            } else if (Boolean.FALSE.equals(exactOutcomes.get(locator))) {
                violations.add("HISTORICAL_EVIDENCE_FAILED " + locator
                    + " — the safe-int legacy row failed under "
                    + "LEGACY_REGRESSION");
            }
        }
        for (String locator : requiredV12Locators) {
            if (!v12Executed.contains(locator)
                    && !exactOutcomes.containsKey(locator)) {
                violations.add("HISTORICAL_EVIDENCE_MISSING " + locator
                    + " — the additive v1.2 replacement was not executed "
                    + "under DEAL_V1_2_INT32 by this runner (every "
                    + "non-excluded safe-int row needs its replacement "
                    + "passing under the v1.2 profile)");
            } else if (Boolean.FALSE.equals(exactOutcomes.get(locator))) {
                violations.add("HISTORICAL_EVIDENCE_FAILED " + locator
                    + " — the additive v1.2 replacement failed under "
                    + "DEAL_V1_2_INT32");
            }
        }
        if (globalFailure && (!requiredLegacyLocators.isEmpty()
                || !requiredV12Locators.isEmpty())) {
            violations.add("HISTORICAL_EVIDENCE_GLOBAL_FAILURE — a "
                + "failure occurred in a runner that executed the "
                + "signed-int32 historical rows; the unchanged-pass "
                + "evidence is not green");
        }
        return violations;
    }

    // =========================================================================
    // Catalog authoring-consistency check (class initialization)
    // =========================================================================

    static {
        // Every fixture row's pinned baseline must equal the digest over
        // its authored surface (an authoring defect fails loudly — a
        // baseline can never be updated silently); every pinId is unique.
        Set<String> pinIds = new java.util.HashSet<>();
        for (Row row : ROWS) {
            if (!pinIds.add(row.pinId())) {
                throw new IllegalStateException("duplicate catalog pinId '"
                    + row.pinId() + "'");
            }
            if (isFixtureCaseLocator(row.locator())) {
                AuthoredFixtureSurface surface =
                    AUTHORED_SURFACES.get(row.pinId());
                if (surface == null) {
                    throw new IllegalStateException("row '" + row.pinId()
                        + "' has no authored expectation surface");
                }
                String authoredDigest =
                    digest(fixtureExpectationRecord(surface));
                if (!authoredDigest.equals(row.expectationBaseline())) {
                    throw new IllegalStateException("row '" + row.pinId()
                        + "': the pinned baseline " + row.expectationBaseline()
                        + " does not equal the digest over the authored "
                        + "expectation surface (" + authoredDigest
                        + ") — author the baseline from the then-current "
                        + "tree's surface");
                }
            }
        }
    }
}
