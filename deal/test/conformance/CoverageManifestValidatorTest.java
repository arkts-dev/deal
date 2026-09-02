package deal.test.conformance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Verifies the ISSUE-0474 reusable Coverage Manifest Validation component
 * ({@link CoverageManifestValidator}) against a self-contained synthetic
 * corpus tree, covering the design's Verification 1 matrix
 * ({@code v12-coverage-manifest-and-validator}): a synthetic complete
 * manifest covering every required bullet and observable per its kind
 * validates clean and deterministically, and each negative produces a
 * gate failure naming the requirement.
 *
 * <p>Negative matrix — every case must produce a gate failure naming the
 * requirement:</p>
 * <ol>
 *   <li>uncovered bullet; uncovered observable; unknown kind (a required
 *       row carrying the wrong kind);</li>
 *   <li>kind/classification mismatches: a compile-kind entry naming a
 *       runtime-classified fixture; a runtime-kind entry naming a
 *       frontend-classified fixture; a compile-kind entry naming a
 *       {@code known-fail compile-ok} fixture; a compile-kind entry
 *       naming a {@code known-fail runtime-ok} fixture;</li>
 *   <li>FFI declaration-rules pin violations: a {@code compile-ok}
 *       fixture in the row (no pin); a tracked known-fail pin (pins the
 *       code's absence, not the code); a wrong-family code pin;</li>
 *   <li>stale fixture path; a runtime-kind entry whose fixture lacks its
 *       sidecar; a Diagnostics entry whose fixture lacks its compile
 *       sidecar; a missing benchmark artifact; a non-compiling benchmark
 *       artifact; an uncovered {@code @spec} first-component;</li>
 *   <li>the three deferral bookkeeping violations: missing row, unknown
 *       row, deferred row carrying a coverage entry;</li>
 *   <li>structural violations: unknown kind value, empty fixtures array,
 *       unknown root field, unknown requirement id.</li>
 * </ol>
 *
 * <p>The suite is standalone: it builds its own synthetic corpus and
 * repo trees under a fresh temporary directory, runs from any working
 * directory, performs read-only I/O over its inputs, and deletes the
 * trees at the end. Result: 26/0 and exit 0.</p>
 */
public class CoverageManifestValidatorTest {

    private static int passed = 0;
    private static int failed = 0;

    // =========================================================================
    // Synthetic tree paths
    // =========================================================================

    /** The temporary tree root (created in main, deleted at the end). */
    private static Path tempRoot;
    /** The synthetic corpus root (the manifest's corpusRoot argument). */
    private static Path corpusRoot;
    /** The synthetic repo root (the manifest's repoRoot argument). */
    private static Path repoRoot;

    /** Synthetic corpus fixture paths (corpus-relative). */
    private static final String COMPILE_OK = "frontend/lexer-ok.deal";
    private static final String DIAG = "frontend/diag-error.deal";
    private static final String DIAG_NO_SIDECAR = "frontend/diag-no-sidecar.deal";
    private static final String FFI = "frontend/ffi-marker.deal";
    private static final String FFI_POLICY = "frontend/ffi-policy.deal";
    private static final String FFI_COMPILE_OK = "frontend/ffi-compile-ok.deal";
    private static final String FFI_TRACKED = "frontend/ffi-tracked.deal";
    private static final String FFI_WRONG_FAMILY = "frontend/ffi-wrong-family.deal";
    private static final String KF_COMPILE_OK = "frontend/kf-compile-ok.deal";
    private static final String KF_RUNTIME_OK = "frontend/kf-runtime-ok.deal";
    private static final String RUNTIME_OK = "runtime/runtime-ok.deal";
    private static final String RUNTIME_NO_SIDECAR = "runtime/runtime-no-sidecar.deal";
    private static final String UNCOVERED_SPEC = "frontend/uncovered-spec.deal";

    /** Synthetic benchmark artifact paths (repo-root-relative). */
    private static final String BENCHMARK = "docs/capabilities.deal";
    private static final String BROKEN_BENCHMARK = "docs/broken.deal";

    /** The synthetic {@code @spec} first-components, A-E. */
    private static final List<String> SECTIONS = List.of(
        "Synthetic spec A", "Synthetic spec B", "Synthetic spec C",
        "Synthetic spec D", "Synthetic spec E");

    // =========================================================================
    // Synthetic tree construction
    // =========================================================================

    private static void setupTree() throws IOException {
        tempRoot = Files.createTempDirectory(
            "coverage-manifest-validator-test-");
        corpusRoot = tempRoot.resolve("corpus");
        repoRoot = tempRoot.resolve("repo");

        writeFixture(corpusRoot, COMPILE_OK, "Synthetic spec A", "compile-ok");
        writeFixture(corpusRoot, DIAG, "Synthetic spec B", "compile-error E3001");
        writeFixture(corpusRoot, DIAG_NO_SIDECAR, "Synthetic spec B",
            "compile-error E3001");
        writeFixture(corpusRoot, FFI, "Synthetic spec C", "compile-error E7002");
        writeFixture(corpusRoot, FFI_POLICY, "Synthetic spec C",
            "known-fail compile-error E2010");
        writeFixture(corpusRoot, FFI_COMPILE_OK, "Synthetic spec C",
            "compile-ok");
        writeFixture(corpusRoot, FFI_TRACKED, "Synthetic spec C",
            "known-fail compile-error E7002");
        writeFixture(corpusRoot, FFI_WRONG_FAMILY, "Synthetic spec C",
            "compile-error E7003");
        writeFixture(corpusRoot, KF_COMPILE_OK, "Synthetic spec A",
            "known-fail compile-ok");
        writeFixture(corpusRoot, KF_RUNTIME_OK, "Synthetic spec A",
            "known-fail runtime-ok");
        writeFixture(corpusRoot, RUNTIME_OK, "Synthetic spec D", "runtime-ok");
        writeFixture(corpusRoot, RUNTIME_NO_SIDECAR, "Synthetic spec D",
            "runtime-ok");
        writeFixture(corpusRoot, UNCOVERED_SPEC, "Synthetic spec E",
            "compile-ok");

        // The sibling sidecars the positive matrix relies on: a uniform
        // three-backend runtime-ok Structured Expectation Sidecar and a
        // Compile Expectation Sidecar whose pin equals @expected.
        write(corpusRoot.resolve("runtime/runtime-ok.expect.json"),
            "{ \"version\": 1, \"backends\": [\"luajit\", \"jvm\", \"js\"], "
                + "\"expected\": { \"mode\": \"runtime-ok\", \"transcript\": "
                + "{ \"stdout\": \"\", \"stderr\": \"\" }, \"exitCode\": 0 } }");
        write(corpusRoot.resolve("frontend/diag-error.expect.json"),
            "{ \"version\": 1, \"mode\": \"compile-error\", \"diagnostic\": "
                + "{ \"code\": \"E3001\" } }");

        // Benchmark artifacts under the synthetic repo root: one valid
        // backend-neutral v1.2 program and one non-compiling one.
        write(repoRoot.resolve(BENCHMARK),
            "export function main(): null { return null; }\n");
        write(repoRoot.resolve(BROKEN_BENCHMARK),
            "export function broken(): int { return \"not an int\"; }\n");
    }

    private static void writeFixture(Path root, String corpusPath,
            String spec, String expected) throws IOException {
        write(root.resolve(corpusPath),
            "// @spec: " + spec + "\n"
                + "// @expected: " + expected + "\n"
                + "// @features: synthetic\n"
                + "\n"
                + "export function main(): null { return null; }\n");
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static void deleteTree() throws IOException {
        if (tempRoot == null || !Files.exists(tempRoot)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(tempRoot)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    // =========================================================================
    // Synthetic manifest construction
    // =========================================================================

    /** One synthetic manifest entry: kind name plus fixture paths. */
    private record Entry(String kind, List<String> fixtures) {}

    private static Map<String, Entry> defaultRequirements() {
        Map<String, Entry> map = new TreeMap<>();
        for (String id : new TreeSet<>(
                CoverageManifestValidator.REQUIRED_BULLETS.keySet())) {
            CoverageManifestValidator.Kind kind =
                CoverageManifestValidator.REQUIRED_BULLETS.get(id);
            map.put(id, new Entry(kind.manifestName(),
                List.of(defaultFixtureFor(id, kind))));
        }
        return map;
    }

    private static Map<String, Entry> defaultObservables() {
        Map<String, Entry> map = new TreeMap<>();
        for (String id : new TreeSet<>(
                CoverageManifestValidator.REQUIRED_OBSERVABLES.keySet())) {
            CoverageManifestValidator.Kind kind =
                CoverageManifestValidator.REQUIRED_OBSERVABLES.get(id);
            map.put(id, new Entry(kind.manifestName(),
                List.of(defaultFixtureFor(id, kind))));
        }
        return map;
    }

    private static String defaultFixtureFor(String id,
            CoverageManifestValidator.Kind kind) {
        return switch (kind) {
            case BENCHMARK -> BENCHMARK;
            case RUNTIME -> RUNTIME_OK;
            case COMPILE -> switch (id) {
                case CoverageManifestValidator.DIAGNOSTICS_REQUIREMENT ->
                    DIAG;
                case CoverageManifestValidator
                        .FFI_DECLARATION_RULES_REQUIREMENT -> FFI;
                case "conformance-ffi-invalid-manifest-policy" -> FFI_POLICY;
                default -> COMPILE_OK;
            };
        };
    }

    private static Map<String, String> defaultDeferred() {
        return new TreeMap<>(CoverageManifestValidator.KNOWN_DEFERRED);
    }

    private static String buildManifest(Map<String, Entry> requirements,
            Map<String, Entry> observables, List<String> sections,
            Map<String, String> deferred, String extraRootField) {
        StringBuilder json = new StringBuilder();
        json.append("{ \"version\": 1, ");
        if (extraRootField != null) {
            json.append(extraRootField).append(", ");
        }
        json.append("\"requirements\": {");
        appendEntries(json, requirements);
        json.append("}, \"observables\": {");
        appendEntries(json, observables);
        json.append("}, \"specSections\": [");
        appendStrings(json, sections);
        json.append("], \"deferred\": {");
        boolean first = true;
        for (Map.Entry<String, String> entry : deferred.entrySet()) {
            if (!first) {
                json.append(", ");
            }
            first = false;
            json.append(jsonString(entry.getKey())).append(": ")
                .append(jsonString(entry.getValue()));
        }
        json.append("} }");
        return json.toString();
    }

    private static void appendEntries(StringBuilder json,
            Map<String, Entry> entries) {
        boolean first = true;
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            if (!first) {
                json.append(", ");
            }
            first = false;
            Entry value = entry.getValue();
            json.append(jsonString(entry.getKey()))
                .append(": { \"title\": \"Synthetic title\", \"kind\": ")
                .append(jsonString(value.kind()))
                .append(", \"fixtures\": [");
            appendStrings(json, value.fixtures());
            json.append("] }");
        }
    }

    private static void appendStrings(StringBuilder json,
            List<String> values) {
        boolean first = true;
        for (String value : values) {
            if (!first) {
                json.append(", ");
            }
            first = false;
            json.append(jsonString(value));
        }
    }

    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                sb.append("\\\"");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static Map<String, Entry> copy(Map<String, Entry> source) {
        return new TreeMap<>(source);
    }

    private static List<String> sectionsWithout(String section) {
        return SECTIONS.stream().filter(s -> !s.equals(section)).toList();
    }

    // =========================================================================
    // Assertion helpers
    // =========================================================================

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    private static List<CoverageManifestValidator.CoverageFailure> validate(
            String manifestJson) {
        return CoverageManifestValidator.validate(manifestJson, corpusRoot,
            repoRoot);
    }

    private static void expectClean(String manifestJson, String caseName) {
        List<CoverageManifestValidator.CoverageFailure> failures =
            validate(manifestJson);
        check(failures.isEmpty(), caseName
            + ": expected the manifest to validate clean, got " + failures);
    }

    private static void expectFailure(String manifestJson,
            String expectedRequirement, String caseName) {
        List<CoverageManifestValidator.CoverageFailure> failures =
            validate(manifestJson);
        boolean names = failures.stream()
            .anyMatch(f -> f.requirement().equals(expectedRequirement));
        check(!failures.isEmpty() && names, caseName
            + ": expected a gate failure naming \"" + expectedRequirement
            + "\", got " + failures);
    }

    private static void checkDeterminism(String manifestJson,
            String caseName) {
        List<CoverageManifestValidator.CoverageFailure> first =
            validate(manifestJson);
        List<CoverageManifestValidator.CoverageFailure> second =
            validate(manifestJson);
        check(first.equals(second), caseName
            + ": validation must be deterministic for the same inputs — "
            + "first " + first + ", second " + second);
    }

    // =========================================================================
    // The 26/0 matrix
    // =========================================================================

    private static void runMatrix() {
        Map<String, Entry> requirements = defaultRequirements();
        Map<String, Entry> observables = defaultObservables();
        Map<String, String> deferred = defaultDeferred();

        // --- Positive matrix ---
        String complete = buildManifest(requirements, observables, SECTIONS,
            deferred, null);

        // 1. A synthetic complete manifest over the synthetic corpus
        //    validates clean (the empty list is the clean verdict).
        expectClean(complete,
            "synthetic complete manifest (every bullet and observable per "
                + "its kind) validates clean");

        // 2-3. Determinism: the same inputs twice — a clean and a failing
        //      manifest — yield the identical outcome.
        checkDeterminism(complete, "determinism (clean manifest)");
        Map<String, Entry> uncoveredRequirements = copy(requirements);
        uncoveredRequirements.remove("conformance-lexer");
        String uncoveredBullet = buildManifest(uncoveredRequirements,
            observables, SECTIONS, deferred, null);
        checkDeterminism(uncoveredBullet, "determinism (failing manifest)");

        // --- Negative matrix: inventory ---
        // 4. Uncovered bullet.
        expectFailure(uncoveredBullet, "conformance-lexer",
            "uncovered bullet");
        // 5. Uncovered observable.
        Map<String, Entry> uncoveredObservables = copy(observables);
        uncoveredObservables.remove("observable-compile-time-diagnostics");
        expectFailure(buildManifest(requirements, uncoveredObservables,
                SECTIONS, deferred, null),
            "observable-compile-time-diagnostics", "uncovered observable");
        // 6. Unknown kind: a required row carrying the wrong (known) kind.
        Map<String, Entry> wrongKind = copy(requirements);
        wrongKind.put("conformance-lexer",
            new Entry("runtime", List.of(COMPILE_OK)));
        expectFailure(buildManifest(wrongKind, observables, SECTIONS,
                deferred, null), "conformance-lexer",
            "a required row carrying the wrong kind");

        // --- Negative matrix: kind/classification mismatches ---
        // 7. Compile-kind entry naming a runtime-classified fixture.
        Map<String, Entry> compileNamesRuntime = copy(requirements);
        compileNamesRuntime.put("conformance-lexer",
            new Entry("compile", List.of(RUNTIME_OK)));
        expectFailure(buildManifest(compileNamesRuntime, observables,
                SECTIONS, deferred, null), "conformance-lexer",
            "compile-kind entry naming a runtime-classified fixture");
        // 8. Runtime-kind entry naming a frontend-classified fixture.
        Map<String, Entry> runtimeNamesCompile = copy(requirements);
        runtimeNamesCompile.put("conformance-runtime-sentinels",
            new Entry("runtime", List.of(COMPILE_OK)));
        expectFailure(buildManifest(runtimeNamesCompile, observables,
                SECTIONS, deferred, null), "conformance-runtime-sentinels",
            "runtime-kind entry naming a frontend-classified fixture");
        // 9. Compile-kind entry naming a known-fail compile-ok fixture.
        Map<String, Entry> compileNamesKfOk = copy(requirements);
        compileNamesKfOk.put("conformance-parser",
            new Entry("compile", List.of(KF_COMPILE_OK)));
        expectFailure(buildManifest(compileNamesKfOk, observables,
                SECTIONS, deferred, null), "conformance-parser",
            "compile-kind entry naming a known-fail compile-ok fixture");
        // 10. Compile-kind entry naming a known-fail runtime fixture.
        Map<String, Entry> compileNamesKfRuntime = copy(requirements);
        compileNamesKfRuntime.put("conformance-type-checker",
            new Entry("compile", List.of(KF_RUNTIME_OK)));
        expectFailure(buildManifest(compileNamesKfRuntime, observables,
                SECTIONS, deferred, null), "conformance-type-checker",
            "compile-kind entry naming a known-fail runtime fixture");

        // --- Negative matrix: FFI declaration-rules pin violations ---
        // 11. A compile-ok fixture in the row (no pin).
        Map<String, Entry> ffiNoPin = copy(requirements);
        ffiNoPin.put(CoverageManifestValidator.FFI_DECLARATION_RULES_REQUIREMENT,
            new Entry("compile", List.of(FFI_COMPILE_OK)));
        expectFailure(buildManifest(ffiNoPin, observables, SECTIONS,
                deferred, null),
            CoverageManifestValidator.FFI_DECLARATION_RULES_REQUIREMENT,
            "FFI declaration-rules row naming a compile-ok fixture");
        // 12. A tracked known-fail pin in the row (pins the code's absence).
        Map<String, Entry> ffiTracked = copy(requirements);
        ffiTracked.put(CoverageManifestValidator.FFI_DECLARATION_RULES_REQUIREMENT,
            new Entry("compile", List.of(FFI_TRACKED)));
        expectFailure(buildManifest(ffiTracked, observables, SECTIONS,
                deferred, null),
            CoverageManifestValidator.FFI_DECLARATION_RULES_REQUIREMENT,
            "FFI declaration-rules row naming a tracked known-fail pin");
        // 13. A wrong-family code pin in the row.
        Map<String, Entry> ffiWrongFamily = copy(requirements);
        ffiWrongFamily.put(CoverageManifestValidator.FFI_DECLARATION_RULES_REQUIREMENT,
            new Entry("compile", List.of(FFI_WRONG_FAMILY)));
        expectFailure(buildManifest(ffiWrongFamily, observables, SECTIONS,
                deferred, null),
            CoverageManifestValidator.FFI_DECLARATION_RULES_REQUIREMENT,
            "FFI declaration-rules row naming a wrong-family code pin");

        // --- Negative matrix: paths, sidecars, benchmark, @spec ---
        // 14. Stale fixture path.
        Map<String, Entry> stalePath = copy(requirements);
        stalePath.put("conformance-lexer",
            new Entry("compile", List.of("frontend/missing.deal")));
        expectFailure(buildManifest(stalePath, observables, SECTIONS,
                deferred, null), "conformance-lexer", "stale fixture path");
        // 15. Runtime-kind entry whose fixture lacks its sidecar.
        Map<String, Entry> runtimeNoSidecar = copy(requirements);
        runtimeNoSidecar.put("conformance-runtime-sentinels",
            new Entry("runtime", List.of(RUNTIME_NO_SIDECAR)));
        expectFailure(buildManifest(runtimeNoSidecar, observables,
                SECTIONS, deferred, null), "conformance-runtime-sentinels",
            "runtime-kind entry whose fixture lacks its sidecar");
        // 16. Diagnostics entry whose fixture lacks its compile sidecar.
        Map<String, Entry> diagNoSidecar = copy(requirements);
        diagNoSidecar.put(CoverageManifestValidator.DIAGNOSTICS_REQUIREMENT,
            new Entry("compile", List.of(DIAG_NO_SIDECAR)));
        expectFailure(buildManifest(diagNoSidecar, observables, SECTIONS,
                deferred, null),
            CoverageManifestValidator.DIAGNOSTICS_REQUIREMENT,
            "Diagnostics entry whose fixture lacks its compile sidecar");
        // 17. Missing benchmark artifact.
        Map<String, Entry> missingBenchmark = copy(requirements);
        missingBenchmark.put("conformance-ai-codegen-benchmark",
            new Entry("benchmark", List.of("docs/missing.deal")));
        expectFailure(buildManifest(missingBenchmark, observables,
                SECTIONS, deferred, null), "conformance-ai-codegen-benchmark",
            "missing benchmark artifact");
        // 18. Non-compiling benchmark artifact.
        Map<String, Entry> brokenBenchmark = copy(requirements);
        brokenBenchmark.put("conformance-ai-codegen-benchmark",
            new Entry("benchmark", List.of(BROKEN_BENCHMARK)));
        expectFailure(buildManifest(brokenBenchmark, observables,
                SECTIONS, deferred, null), "conformance-ai-codegen-benchmark",
            "non-compiling benchmark artifact");
        // 19. Uncovered @spec first-component (naming the first fixture
        //     using it — the only Synthetic spec E fixture).
        expectFailure(buildManifest(requirements, observables,
                sectionsWithout("Synthetic spec E"), deferred, null),
            UNCOVERED_SPEC, "uncovered @spec first-component");

        // --- Negative matrix: deferral bookkeeping ---
        // 20. Missing deferred row.
        Map<String, String> missingDeferred = new TreeMap<>(deferred);
        missingDeferred.remove("conformance-ffi-runtime-errors");
        expectFailure(buildManifest(requirements, observables, SECTIONS,
                missingDeferred, null), "conformance-ffi-runtime-errors",
            "missing deferred row");
        // 21. Unknown deferred row.
        Map<String, String> unknownDeferred = new TreeMap<>(deferred);
        unknownDeferred.put("conformance-mystery", "an unknown reason");
        expectFailure(buildManifest(requirements, observables, SECTIONS,
                unknownDeferred, null), "conformance-mystery",
            "unknown deferred row");
        // 22. Deferred row carrying a coverage entry.
        Map<String, Entry> deferredCovered = copy(requirements);
        deferredCovered.put("conformance-ffi-abi-mapping",
            new Entry("compile", List.of(COMPILE_OK)));
        expectFailure(buildManifest(deferredCovered, observables, SECTIONS,
                deferred, null), "conformance-ffi-abi-mapping",
            "deferred row carrying a coverage entry");

        // --- Negative matrix: structural violations ---
        // 23. Unknown kind value.
        Map<String, Entry> unknownKindValue = copy(requirements);
        unknownKindValue.put("conformance-lexer",
            new Entry("banana", List.of(COMPILE_OK)));
        expectFailure(buildManifest(unknownKindValue, observables,
                SECTIONS, deferred, null), "conformance-lexer",
            "unknown kind value");
        // 24. Empty fixtures array.
        Map<String, Entry> emptyFixtures = copy(requirements);
        emptyFixtures.put("conformance-parser",
            new Entry("compile", List.of()));
        expectFailure(buildManifest(emptyFixtures, observables, SECTIONS,
                deferred, null), "conformance-parser",
            "empty fixtures array");
        // 25. Unknown root field.
        expectFailure(buildManifest(requirements, observables, SECTIONS,
                deferred, "\"extraField\": 1"), "<manifest>",
            "unknown root field");
        // 26. Unknown requirement id.
        Map<String, Entry> unknownRequirement = copy(requirements);
        unknownRequirement.put("conformance-mystery",
            new Entry("compile", List.of(COMPILE_OK)));
        expectFailure(buildManifest(unknownRequirement, observables,
                SECTIONS, deferred, null), "conformance-mystery",
            "unknown requirement id");
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Coverage Manifest Validator Test "
            + "(ISSUE-0474) ===\n");
        setupTree();
        try {
            runMatrix();
        } finally {
            deleteTree();
        }
        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
