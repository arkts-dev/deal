package deal.test.conformance;

import deal.semantic.ir.CanonicalJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * ISSUE-0475 real-manifest mechanical check: the authored corpus
 * coverage manifest {@code test/conformance/v1.2-coverage.json} (schema
 * v1) covers every non-deferred §Conformance tests bullet and every
 * §Backend conformance contract observable per its C7 kind against the
 * on-disk corpus, verified through the reusable
 * {@link CoverageManifestValidator} plus independent on-disk reads
 * (Verification 2 of {@code v12-coverage-manifest-and-validator}; the
 * C7 per-kind table and Corpus Coverage Manifest contract of
 * {@code v12-three-backend-conformance-corpus}; the T14 consumer is the
 * gate's Coverage Manifest Validator component of
 * {@code v12-zero-skip-conformance-gate}).
 *
 * <p>The 82 checks:</p>
 * <ol>
 *   <li>15 document-level checks: the manifest exists; it parses as
 *       canonical JSON with an object root; {@code version} is 1;
 *       {@link CoverageManifestValidator#validate} returns an empty
 *       failure list (the per-kind rules over every entry — path
 *       existence, kind/classification cross-check, sidecar schema
 *       validation, Diagnostics compile sidecars, FFI pin families,
 *       benchmark compilation, {@code @spec} completeness, deferral
 *       bookkeeping); the requirements key set equals
 *       {@link CoverageManifestValidator#REQUIRED_BULLETS} and the
 *       observables key set equals
 *       {@link CoverageManifestValidator#REQUIRED_OBSERVABLES}; every
 *       requirement and observable entry carries at least one fixture;
 *       the benchmark artifact {@code docs/deal-v1.2-capabilities.deal}
 *       exists and is named by the benchmark-kind entry; the
 *       Diagnostics entries name fixtures carrying Compile Expectation
 *       Sidecars ({@code mode: "compile-error"}); {@code specSections}
 *       is complete in both directions; the deferral bookkeeping is
 *       exact (the two T14 rows with non-empty reasons and no coverage
 *       entry naming a deferred row).</li>
 *   <li>67 fixture-reference checks: every fixture path named by the
 *       requirements and observables maps resolves to an existing file
 *       with the classification its kind requires — an independent
 *       expected-tag read on the on-disk fixture (runtime-ok or
 *       runtime-error CODE plus a sibling Structured Expectation
 *       Sidecar for {@code runtime}-kind; compile-ok, compile-error
 *       CODE, or tracked known-fail compile-error CODE for
 *       {@code compile}-kind; the existing repo-root artifact for
 *       {@code benchmark}-kind) — with the row pins the design
 *       dispositions require: every C FFI declaration-rules fixture
 *       pins exactly {@code compile-error E7001|E7002} and the C FFI
 *       invalid-manifest-policy fixture pins exactly the promoted
 *       {@code compile-error E2010} manifest-policy rejection (D6).</li>
 * </ol>
 *
 * <p>The suite runs from the repository root (the {@code run_tests.sh}
 * contract, like {@code ConformanceTest}); it performs read-only I/O on
 * {@code test/conformance/}, {@code docs/deal-v1.2-capabilities.deal},
 * and the manifest, mutates nothing, and exits nonzero on any failed
 * check (gate-fatal). Every check is independent of check order; the
 * same inputs always produce the identical verdict. It prints the
 * per-kind entry counts, the section count, and the deferred count for
 * the release record. Result on the authored tree: 82/0 and exit 0.</p>
 */
public class CoverageManifestCorpusTest {

    private static int passed = 0;
    private static int failed = 0;

    // =========================================================================
    // Roots and pinned paths
    // =========================================================================

    /** The corpus root, relative to the repository root. */
    private static final Path CORPUS_ROOT = Path.of("test", "conformance");
    /** The repository root (the {@code run_tests.sh} working directory). */
    private static final Path REPO_ROOT = Path.of(".");
    /** The manifest path under the corpus root. */
    private static final Path MANIFEST_PATH =
        CORPUS_ROOT.resolve("v1.2-coverage.json");

    /** The benchmark artifact (repo-root-relative, corpus C7). */
    private static final String BENCHMARK_ARTIFACT =
        "docs/deal-v1.2-capabilities.deal";
    /** The benchmark requirement id of the closed inventory. */
    private static final String BENCHMARK_REQUIREMENT =
        "conformance-ai-codegen-benchmark";
    /** The C FFI invalid-manifest-policy row of the closed inventory. */
    private static final String FFI_INVALID_MANIFEST_POLICY_REQUIREMENT =
        "conformance-ffi-invalid-manifest-policy";
    /** The observable Diagnostics row (the second Diagnostics entry). */
    private static final String DIAGNOSTICS_OBSERVABLE =
        "observable-compile-time-diagnostics";

    /** The {@code @spec} first-component separator (an em dash, D5). */
    private static final String SPEC_SEPARATOR = " \u2014 ";

    // =========================================================================
    // Result types
    // =========================================================================

    /** One parsed manifest entry: id, kind, and fixture list in order. */
    private record ManifestEntry(String id, CoverageManifestValidator.Kind kind,
                                 List<String> fixtures) {}

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Coverage Manifest Corpus Test "
            + "(ISSUE-0475) ===\n");

        // 1. The manifest exists.
        boolean manifestExists = Files.isRegularFile(MANIFEST_PATH);
        check(manifestExists, "the corpus coverage manifest must exist at "
            + MANIFEST_PATH);
        String manifestJson = manifestExists
            ? Files.readString(MANIFEST_PATH) : "";

        // 2. The manifest parses as canonical JSON with an object root.
        CanonicalJson.Obj root = null;
        try {
            CanonicalJson.Value parsed = CanonicalJson.parse(manifestJson);
            check(parsed instanceof CanonicalJson.Obj,
                "the manifest root must be a JSON object");
            if (parsed instanceof CanonicalJson.Obj obj) {
                root = obj;
            }
        } catch (RuntimeException e) {
            check(false, "the manifest must parse as canonical JSON: "
                + e.getMessage());
        }

        // 3. The manifest schema version is exactly 1.
        check(root != null
                && versionOf(root) == CoverageManifestValidator.MANIFEST_SCHEMA_VERSION,
            "the manifest version must be exactly "
                + CoverageManifestValidator.MANIFEST_SCHEMA_VERSION
                + " (schema v1)");

        // 4. The reusable validator returns an empty failure list — the
        // C7 per-kind rules over every entry against the real corpus and
        // repo roots (path existence, classification cross-check, sidecar
        // schema validation, FFI pin families, benchmark compilation,
        // @spec completeness, deferral bookkeeping).
        List<CoverageManifestValidator.CoverageFailure> validatorFailures =
            CoverageManifestValidator.validate(manifestJson, CORPUS_ROOT,
                REPO_ROOT);
        check(validatorFailures.isEmpty(),
            "CoverageManifestValidator.validate must return an empty "
                + "failure list for the authored manifest");
        for (CoverageManifestValidator.CoverageFailure f : validatorFailures) {
            System.err.println("  validator: " + f.message());
        }

        // The two entry sections, TreeMap-ordered (deterministic).
        Map<String, ManifestEntry> requirements =
            parseSection(sectionOf(root, "requirements"));
        Map<String, ManifestEntry> observables =
            parseSection(sectionOf(root, "observables"));

        // 5-6. The key sets equal the closed inventories exactly.
        checkKeySet("requirements", requirements,
            CoverageManifestValidator.REQUIRED_BULLETS.keySet());
        checkKeySet("observables", observables,
            CoverageManifestValidator.REQUIRED_OBSERVABLES.keySet());

        // 7-8. Every entry carries at least one fixture.
        String emptyRequirement = firstEmptyEntry(requirements);
        check(emptyRequirement == null, emptyRequirement == null
            ? "every requirement entry carries at least one fixture"
            : "requirement entry \"" + emptyRequirement
                + "\" carries no fixtures");
        String emptyObservable = firstEmptyEntry(observables);
        check(emptyObservable == null, emptyObservable == null
            ? "every observable entry carries at least one fixture"
            : "observable entry \"" + emptyObservable
                + "\" carries no fixtures");

        // 9-10. The benchmark artifact exists and the benchmark-kind
        // entry names it (the validator proves it compiles under the
        // embedded v1.2 frontend with no error diagnostics).
        check(Files.isRegularFile(REPO_ROOT.resolve(BENCHMARK_ARTIFACT)),
            "the benchmark artifact must exist at " + BENCHMARK_ARTIFACT);
        String benchmarkProblem =
            benchmarkProblem(requirements.get(BENCHMARK_REQUIREMENT));
        check(benchmarkProblem == null, benchmarkProblem == null
            ? "the benchmark-kind entry \"" + BENCHMARK_REQUIREMENT
                + "\" names " + BENCHMARK_ARTIFACT + " (the validator "
                + "proves it compiles under the v1.2 frontend)"
            : benchmarkProblem);

        // 11. The Diagnostics entries name fixtures carrying Compile
        // Expectation Sidecars (mode "compile-error").
        String diagnosticsProblem =
            diagnosticsSidecarProblem(requirements, observables);
        check(diagnosticsProblem == null, diagnosticsProblem == null
            ? "the Diagnostics entries name fixtures carrying Compile "
                + "Expectation Sidecars (mode \"compile-error\")"
            : diagnosticsProblem);

        // 12-13. specSections is complete in both directions (D5): every
        // used @spec first-component is listed, and every listed section
        // is used by at least one fixture (no dead entries).
        List<String> listedSections = stringArrayOf(root, "specSections");
        Set<String> listed = new LinkedHashSet<>(listedSections);
        Map<String, String> used = usedSpecComponents();
        String uncoveredComponent = null;
        String uncoveredBy = null;
        for (Map.Entry<String, String> use : used.entrySet()) {
            if (!listed.contains(use.getKey())) {
                uncoveredComponent = use.getKey();
                uncoveredBy = use.getValue();
                break;
            }
        }
        check(uncoveredComponent == null, uncoveredComponent == null
            ? "specSections lists every used @spec first-component ("
                + used.size() + " used components)"
            : "specSections must list every @spec first-component used "
                + "by a discovered corpus fixture — \"" + uncoveredComponent
                + "\" (first used by " + uncoveredBy + ") is not listed");
        String deadSection = null;
        for (String section : listed) {
            if (!used.containsKey(section)) {
                deadSection = section;
                break;
            }
        }
        check(deadSection == null, deadSection == null
            ? "every listed specSections entry is used by at least one "
                + "discovered corpus fixture (no dead entries)"
            : "specSections must carry no dead entry — \"" + deadSection
                + "\" is listed but used by no discovered corpus fixture");

        // 14-15. The deferral bookkeeping is exact (D3): the two T14
        // rows with non-empty reasons, and no coverage entry naming a
        // deferred row.
        Map<String, String> deferred = stringMapOf(root, "deferred");
        Set<String> knownDeferred =
            CoverageManifestValidator.KNOWN_DEFERRED.keySet();
        if (!deferred.keySet().equals(knownDeferred)) {
            check(false, "the deferral bookkeeping must name exactly the "
                + "closed T14 set " + knownDeferred + ", got "
                + deferred.keySet());
        } else {
            String emptyReason = null;
            for (Map.Entry<String, String> d : deferred.entrySet()) {
                if (d.getValue().trim().isEmpty()) {
                    emptyReason = d.getKey();
                    break;
                }
            }
            check(emptyReason == null, emptyReason == null
                ? "the deferral bookkeeping names exactly the two T14 rows "
                    + "with non-empty reasons"
                : "the deferred row \"" + emptyReason
                    + "\" must carry a non-empty reason");
        }
        String deferredConflict = null;
        for (String id : requirements.keySet()) {
            if (knownDeferred.contains(id)) {
                deferredConflict = id;
                break;
            }
        }
        if (deferredConflict == null) {
            for (String id : observables.keySet()) {
                if (knownDeferred.contains(id)) {
                    deferredConflict = id;
                    break;
                }
            }
        }
        check(deferredConflict == null, deferredConflict == null
            ? "no coverage entry names a deferred row"
            : "the deferred row \"" + deferredConflict + "\" must not "
                + "carry a coverage entry (T14 adds the row and removes "
                + "the bookkeeping in one change)");

        // 16-82. One check per fixture reference (67): the file exists
        // and its exact on-disk @expected tag classifies as its kind
        // requires, with the row pins of D6 (FFI E7001|E7002 pins, the
        // tracked E2010 pin) and the runtime sidecar presence.
        for (Map.Entry<String, ManifestEntry> entry : requirements.entrySet()) {
            for (String fixturePath : entry.getValue().fixtures()) {
                checkFixture(entry.getKey(), entry.getValue().kind(),
                    fixturePath);
            }
        }
        for (Map.Entry<String, ManifestEntry> entry : observables.entrySet()) {
            for (String fixturePath : entry.getValue().fixtures()) {
                checkFixture(entry.getKey(), entry.getValue().kind(),
                    fixturePath);
            }
        }

        // The release record: per-kind entry counts, section count, and
        // deferred count.
        printSummary(requirements, observables, listedSections, deferred);

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // Manifest parsing helpers (independent reads)
    // =========================================================================

    /** One field of a JSON object, or {@code null}. */
    private static CanonicalJson.Value fieldOf(CanonicalJson.Obj obj,
            String key) {
        if (obj == null) {
            return null;
        }
        for (CanonicalJson.Entry entry : obj.entries()) {
            if (entry.key().equals(key)) {
                return entry.value();
            }
        }
        return null;
    }

    /** One entry section (requirements or observables) as an object. */
    private static CanonicalJson.Obj sectionOf(CanonicalJson.Obj root,
            String key) {
        CanonicalJson.Value value = fieldOf(root, key);
        return value instanceof CanonicalJson.Obj obj ? obj : null;
    }

    /** The manifest {@code version} integer, or -1 when absent/invalid. */
    private static int versionOf(CanonicalJson.Obj root) {
        CanonicalJson.Value value = fieldOf(root, "version");
        return value instanceof CanonicalJson.Int i ? i.value() : -1;
    }

    /**
     * One entry section into a TreeMap of {@link ManifestEntry}. Entries
     * that fail the entry shape (not an object, no kind, no fixtures
     * array) are absent from the result so the key-set checks name the
     * missing id; the validator reports the structural violation.
     */
    private static Map<String, ManifestEntry> parseSection(
            CanonicalJson.Obj section) {
        Map<String, ManifestEntry> out = new TreeMap<>();
        if (section == null) {
            return out;
        }
        for (CanonicalJson.Entry sectionEntry : section.entries()) {
            if (!(sectionEntry.value() instanceof CanonicalJson.Obj obj)) {
                continue;
            }
            CoverageManifestValidator.Kind kind = null;
            List<String> fixtures = new ArrayList<>();
            for (CanonicalJson.Entry field : obj.entries()) {
                if (field.key().equals("kind")
                        && field.value() instanceof CanonicalJson.Str s) {
                    kind = CoverageManifestValidator.Kind.of(s.value());
                }
                if (field.key().equals("fixtures")
                        && field.value() instanceof CanonicalJson.Arr arr) {
                    for (CanonicalJson.Value item : arr.items()) {
                        if (item instanceof CanonicalJson.Str s) {
                            fixtures.add(s.value());
                        }
                    }
                }
            }
            if (kind == null) {
                continue;
            }
            out.put(sectionEntry.key(),
                new ManifestEntry(sectionEntry.key(), kind, fixtures));
        }
        return out;
    }

    /** A JSON string array field (empty when absent or malformed). */
    private static List<String> stringArrayOf(CanonicalJson.Obj root,
            String key) {
        CanonicalJson.Value value = fieldOf(root, key);
        if (!(value instanceof CanonicalJson.Arr arr)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (CanonicalJson.Value item : arr.items()) {
            if (item instanceof CanonicalJson.Str s) {
                out.add(s.value());
            }
        }
        return out;
    }

    /** A JSON string map field (empty when absent or malformed). */
    private static Map<String, String> stringMapOf(CanonicalJson.Obj root,
            String key) {
        CanonicalJson.Value value = fieldOf(root, key);
        Map<String, String> out = new TreeMap<>();
        if (!(value instanceof CanonicalJson.Obj obj)) {
            return out;
        }
        for (CanonicalJson.Entry entry : obj.entries()) {
            if (entry.value() instanceof CanonicalJson.Str s) {
                out.put(entry.key(), s.value());
            }
        }
        return out;
    }

    /** The first entry id carrying an empty fixtures array, or null. */
    private static String firstEmptyEntry(
            Map<String, ManifestEntry> section) {
        for (Map.Entry<String, ManifestEntry> entry : section.entrySet()) {
            if (entry.getValue().fixtures().isEmpty()) {
                return entry.getKey();
            }
        }
        return null;
    }

    // =========================================================================
    // Document-level problem checks
    // =========================================================================

    /** The key-set equality check naming missing and extra ids. */
    private static void checkKeySet(String sectionName,
            Map<String, ManifestEntry> actual, Set<String> expected) {
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual.keySet());
        Set<String> extra = new TreeSet<>(actual.keySet());
        extra.removeAll(expected);
        if (missing.isEmpty() && extra.isEmpty()) {
            check(true, sectionName + " key set equals the closed "
                + "inventory (" + expected.size() + " ids)");
        } else {
            check(false, sectionName + " key set must equal the closed "
                + "inventory — missing " + missing + ", extra " + extra);
        }
    }

    /** The benchmark entry problem, or null when it names the artifact. */
    private static String benchmarkProblem(ManifestEntry benchmark) {
        if (benchmark == null) {
            return "the benchmark requirement \"" + BENCHMARK_REQUIREMENT
                + "\" is missing from the requirements map";
        }
        if (benchmark.kind() != CoverageManifestValidator.Kind.BENCHMARK) {
            return "the benchmark requirement \"" + BENCHMARK_REQUIREMENT
                + "\" must be kind benchmark (C7), got "
                + benchmark.kind().manifestName();
        }
        if (!benchmark.fixtures().contains(BENCHMARK_ARTIFACT)) {
            return "the benchmark-kind entry must name "
                + BENCHMARK_ARTIFACT + ", got " + benchmark.fixtures();
        }
        return null;
    }

    /**
     * The Diagnostics-entries sidecar problem, or null. Both Diagnostics
     * entries (the §Conformance tests row and the observable row) name
     * fixtures whose sibling sidecar must exist and be a Compile
     * Expectation Sidecar ({@code mode: "compile-error"}).
     */
    private static String diagnosticsSidecarProblem(
            Map<String, ManifestEntry> requirements,
            Map<String, ManifestEntry> observables) {
        for (String row : List.of(
                CoverageManifestValidator.DIAGNOSTICS_REQUIREMENT,
                DIAGNOSTICS_OBSERVABLE)) {
            ManifestEntry entry = requirements.get(row);
            if (entry == null) {
                entry = observables.get(row);
            }
            if (entry == null) {
                continue; // the key-set checks name the missing row
            }
            for (String fixturePath : entry.fixtures()) {
                Path sidecar = sidecarFor(fixturePath);
                if (!Files.isRegularFile(sidecar)) {
                    return row + ": " + fixturePath + ": the "
                        + "Diagnostics-row fixture is missing its Compile "
                        + "Expectation Sidecar " + sidecar.getFileName();
                }
                String mode = sidecarMode(sidecar);
                if (!"compile-error".equals(mode)) {
                    return row + ": " + fixturePath + ": the "
                        + "Diagnostics-row sidecar must be a Compile "
                        + "Expectation Sidecar with mode "
                        + "\"compile-error\", got " + describe(mode);
                }
            }
        }
        return null;
    }

    // =========================================================================
    // Independent corpus walk (@spec first-components, D5)
    // =========================================================================

    /**
     * Every used {@code @spec} first-component (the part before the
     * {@code " — "} separator), component → the first fixture using it,
     * from an independent walk exactly like the gate: every {@code .deal}
     * file under the corpus root, the {@code host-fixtures} subtree
     * excluded, sorted; companion modules without {@code @spec} skipped.
     */
    private static Map<String, String> usedSpecComponents() throws Exception {
        Map<String, String> firstUse = new LinkedHashMap<>();
        List<Path> files;
        try (Stream<Path> stream = Files.walk(CORPUS_ROOT)) {
            files = stream
                .filter(p -> p.toString().endsWith(".deal"))
                .filter(p -> {
                    Path rel = CORPUS_ROOT.relativize(p);
                    return rel.getNameCount() == 0
                        || !"host-fixtures".equals(
                            rel.getName(0).toString());
                })
                .sorted()
                .toList();
        }
        for (Path file : files) {
            String corpusPath = slash(CORPUS_ROOT.relativize(file));
            String spec = tagOf(Files.readString(file), "// @spec:");
            if (spec.isEmpty()) {
                continue; // companion module without @spec
            }
            firstUse.putIfAbsent(firstComponentOf(spec), corpusPath);
        }
        return firstUse;
    }

    /** The {@code @spec} first-component: before the " — " separator. */
    private static String firstComponentOf(String spec) {
        String first = spec.split(SPEC_SEPARATOR, 2)[0].trim();
        return first.isEmpty() ? spec.trim() : first;
    }

    // =========================================================================
    // Per-fixture checks (67 independent expected-tag reads)
    // =========================================================================

    /** One fixture-reference check naming the requirement on failure. */
    private static void checkFixture(String requirement,
            CoverageManifestValidator.Kind kind, String fixturePath) {
        String problem = fixtureProblem(requirement, kind, fixturePath);
        if (problem != null) {
            check(false, requirement + ": " + fixturePath + ": " + problem);
            return;
        }
        String positive = switch (kind) {
            case BENCHMARK -> "benchmark artifact exists";
            case RUNTIME -> "runtime-kind fixture exists, is "
                + "runtime-classified, and carries its sibling Structured "
                + "Expectation Sidecar";
            case COMPILE -> "compile-kind fixture exists with its required "
                + "classification";
        };
        check(true, requirement + ": " + fixturePath + ": " + positive);
    }

    /** The fixture problem, or null when the check passes. */
    private static String fixtureProblem(String requirement,
            CoverageManifestValidator.Kind kind, String fixturePath) {
        switch (kind) {
            case BENCHMARK -> {
                if (!Files.isRegularFile(
                        REPO_ROOT.resolve(fixturePath))) {
                    return "unknown or missing benchmark artifact path \""
                        + fixturePath + "\"";
                }
                return null;
            }
            case RUNTIME -> {
                Path file = CORPUS_ROOT.resolve(fixturePath);
                if (!Files.isRegularFile(file)) {
                    return "unknown or missing fixture path \""
                        + fixturePath + "\"";
                }
                String tag = expectedTagOf(file);
                if (!isRuntimeClassification(tag)) {
                    return "a runtime-kind fixture must be runtime-ok or "
                        + "runtime-error CODE, got " + describe(tag);
                }
                Path sidecar = sidecarFor(fixturePath);
                if (!Files.isRegularFile(sidecar)) {
                    return "a runtime-kind fixture requires a sibling "
                        + "Structured Expectation Sidecar (schema v1, "
                        + "exactly the three backends), missing "
                        + sidecar.getFileName();
                }
                return null;
            }
            case COMPILE -> {
                Path file = CORPUS_ROOT.resolve(fixturePath);
                if (!Files.isRegularFile(file)) {
                    return "unknown or missing fixture path \""
                        + fixturePath + "\"";
                }
                String tag = expectedTagOf(file);
                if (CoverageManifestValidator
                        .FFI_DECLARATION_RULES_REQUIREMENT
                        .equals(requirement)) {
                    if (tag.equals("compile-error E7001")
                            || tag.equals("compile-error E7002")) {
                        return null;
                    }
                    return "the C FFI declaration-rules row requires a "
                        + "real compile-error E7001|E7002 pin (the "
                        + "parser-emitted C-marker cardinality/placement "
                        + "arms), got " + describe(tag);
                }
                if (FFI_INVALID_MANIFEST_POLICY_REQUIREMENT
                        .equals(requirement)) {
                    if (tag.equals("compile-error E2010")) {
                        return null;
                    }
                    return "the C FFI invalid-manifest-policy row requires "
                        + "the exact compile-error E2010 pin (the "
                        + "promoted C FFI manifest-policy rejection at "
                        + "the import), got " + describe(tag);
                }
                if (!isCompileClassification(tag)) {
                    return "a compile-kind fixture must be compile-ok, "
                        + "exact compile-error CODE, or tracked "
                        + "known-fail compile-error CODE, got "
                        + describe(tag);
                }
                return null;
            }
            default -> {
                return "unknown kind";
            }
        }
    }

    /** The runtime classification predicate (the validator's grammar). */
    private static boolean isRuntimeClassification(String tag) {
        return tag.equals("runtime-ok")
            || (tag.startsWith("runtime-error ")
                && tag.length() > "runtime-error ".length());
    }

    /** The compile classification predicate (the validator's grammar). */
    private static boolean isCompileClassification(String tag) {
        return tag.equals("compile-ok")
            || (tag.startsWith("compile-error ")
                && tag.length() > "compile-error ".length())
            || (tag.startsWith("known-fail compile-error ")
                && tag.length() > "known-fail compile-error ".length());
    }

    /** The exact on-disk {@code @expected} tag (first 40 lines). */
    private static String expectedTagOf(Path file) {
        String raw;
        try {
            raw = Files.readString(file);
        } catch (IOException e) {
            return "";
        }
        return tagOf(raw, "// @expected:");
    }

    /** The sidecar {@code mode} string, or null when absent/malformed. */
    private static String sidecarMode(Path sidecarPath) {
        try {
            CanonicalJson.Value root =
                CanonicalJson.parse(Files.readString(sidecarPath));
            if (!(root instanceof CanonicalJson.Obj obj)) {
                return null;
            }
            CanonicalJson.Value mode = fieldOf(obj, "mode");
            return mode instanceof CanonicalJson.Str s ? s.value() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // =========================================================================
    // Small helpers
    // =========================================================================

    /** The sibling sidecar path for a corpus-relative fixture path. */
    private static Path sidecarFor(String corpusPath) {
        String stem = corpusPath.endsWith(".deal")
            ? corpusPath.substring(0, corpusPath.length() - ".deal".length())
            : corpusPath;
        return CORPUS_ROOT.resolve(stem + ".expect.json");
    }

    /** The exact metadata tag value of one header prefix (first 40 lines). */
    private static String tagOf(String source, String prefix) {
        String[] lines = source.split("\n", -1);
        int linesToScan = Math.min(lines.length, 40);
        for (int i = 0; i < linesToScan; i++) {
            String line = lines[i].trim();
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    /** A tag for failure messages: the tag itself or a missing marker. */
    private static String describe(String tag) {
        return tag.isEmpty() ? "<no @expected tag>" : "\"" + tag + "\"";
    }

    private static String slash(Path path) {
        return path.toString().replace(java.io.File.separatorChar, '/');
    }

    // =========================================================================
    // Release record and result accounting
    // =========================================================================

    /** The per-kind entry counts, section count, and deferred count. */
    private static void printSummary(Map<String, ManifestEntry> requirements,
            Map<String, ManifestEntry> observables, List<String> sections,
            Map<String, String> deferred) {
        int[] reqCounts = kindCounts(requirements);
        int[] obsCounts = kindCounts(observables);
        int totalCompile = reqCounts[0] + obsCounts[0];
        int totalRuntime = reqCounts[1] + obsCounts[1];
        int totalBenchmark = reqCounts[2] + obsCounts[2];
        System.out.println("\nCoverage summary (release record):");
        System.out.println("  requirements: " + requirements.size()
            + " entries (compile " + reqCounts[0] + ", runtime "
            + reqCounts[1] + ", benchmark " + reqCounts[2] + ")");
        System.out.println("  observables: " + observables.size()
            + " entries (compile " + obsCounts[0] + ", runtime "
            + obsCounts[1] + ", benchmark " + obsCounts[2] + ")");
        System.out.println("  entries total: "
            + (requirements.size() + observables.size()) + " (compile "
            + totalCompile + ", runtime " + totalRuntime + ", benchmark "
            + totalBenchmark + ")");
        System.out.println("  specSections: " + sections.size());
        System.out.println("  deferred: " + deferred.size());
    }

    /** Kind counts {@code [compile, runtime, benchmark]} of one section. */
    private static int[] kindCounts(Map<String, ManifestEntry> section) {
        int[] counts = new int[3];
        for (ManifestEntry entry : section.values()) {
            switch (entry.kind()) {
                case COMPILE -> counts[0]++;
                case RUNTIME -> counts[1]++;
                case BENCHMARK -> counts[2]++;
                default -> { }
            }
        }
        return counts;
    }

    /** One pass/fail result; failures print immediately and gate-fatal. */
    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            fail(message);
        }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }
}
