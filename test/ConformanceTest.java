package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.codegen.lua.LuaBackend;
import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.diagnostics.CompilerDiagnostic;
import deal.identity.CanonicalModuleIdentity;
import deal.identity.ProjectModuleIdentity;
import deal.lexer.*;
import deal.module.ExportExtractor;
import deal.module.ModuleIdentityResolver;
import deal.module.ModuleShapeValidator;
import deal.module.StdlibModuleResolver;
import deal.parser.*;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticProfile;
import deal.types.Type;
import deal.types.Types;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Spec-centric conformance test runner for DEAL v1.2 (ISSUE-0107
 * conformance promotion gate).
 *
 * <p>Discovers all .deal files under test/conformance/, parses metadata
 * header comments, compiles and/or executes each test according to its
 * {@code @expected} tag, and produces a pass/fail report with spec
 * coverage summary plus a per-gate v1.2 promotion report.</p>
 *
 * <h2>Classification contract (v1.2 gate)</h2>
 *
 * <p>Every discovered .deal file must carry an explicit classification.
 * Unclassified skips are removed: a file without {@code @expected}, with
 * an unknown {@code @expected} value, or with a non-v1.2 {@code @spec}
 * reference is a configuration failure that fails the gate.</p>
 *
 * <ul>
 *   <li>{@code compile-ok} / {@code compile-error CODE} — frontend-only
 *       checks over the shared lexer/parser/name-resolution/type-checker
 *       pipeline (backend-neutral).</li>
 *   <li>{@code runtime-ok} / {@code runtime-error CODE} — compile plus
 *       real LuaJIT execution through the Lua backend (backend-runtime).</li>
 *   <li>{@code companion} — a classified support module ({@code *_lib}
 *       fixtures) compiled by the companion catalog when a parent fixture
 *       imports it; the gate additionally verifies it compiles standalone
 *       so a dead companion fails loudly.</li>
 *   <li>{@code known-fail MODE} — an intentionally unsupported v1.2 case
 *       ("explicit failing fixture"). The runner verifies that the
 *       requirement {@code MODE} is still NOT satisfied; a mandatory
 *       {@code @issue} tag tracks the owning follow-up issue. When the
 *       tracked issue lands and the fixture starts passing, the gate
 *       FAILS with a promotion instruction (remove the marker and set the
 *       real {@code @expected}), so promotion is forced.</li>
 *   <li>{@code staged-fail} (runner registry, never a {@code @expected}
 *       value) — a fixture whose own expectation is temporarily
 *       unsatisfied by a design-sanctioned locked artifact whose
 *       disposition is owed by another child. The runner executes the
 *       fixture, verifies that the locked artifact code is still
 *       produced, and records the case as a non-fatal tracked staged
 *       failure naming the owning issue. When the owning child lands its
 *       disposition — the fixture starts passing its expectation, or its
 *       classification changes — the gate FAILS with a promotion
 *       instruction (remove the registry entry), so the staged state can
 *       never silently rot.</li>
 *   <li>{@code host-fixture} — reserved for
 *       {@code test/conformance/host-fixtures/*.d.deal}; discovery skips
 *       that subtree and a file classified this way anywhere else is a
 *       configuration failure.</li>
 * </ul>
 */
public class ConformanceTest {

    private static int passed = 0;
    private static int failed = 0;
    private static int skipped = 0;
    private static int knownFailures = 0;
    private static int stagedFailures = 0;
    private static int companions = 0;
    private static final Map<String, List<TestResult>> specGroups = new LinkedHashMap<>();
    private static final Map<String, Integer> knownFailsByIssue = new LinkedHashMap<>();
    private static final Map<String, Integer> stagedFailuresByIssue = new LinkedHashMap<>();
    private static final Map<String, int[]> phaseStats = new LinkedHashMap<>();
    private static final List<String> classificationFailures = new ArrayList<>();
    private static boolean luajitAvailable;

    /**
     * Profile-authority accounting (A4/A5): every legacy-authority
     * result is labelled distinctly and keeps its own denominator; a
     * legacy-profile pass contributes zero v1.2/promotion credit.
     */
    private static int legacyAuthorityResults = 0;
    private static int legacyAuthorityPassed = 0;
    private static int legacyAuthorityFailed = 0;
    private static int v12CreditResults = 0;

    /**
     * Host fixture root (host-module-abi D6): declarations
     * ({@code <name>.d.deal}) plus raw Lua implementations
     * ({@code <name>.lua}) for bare {@code host/<name>} imports.
     * Derived from the conformance root argument in {@code main}.
     */
    private static Path hostFixturesRoot =
        Path.of("test/conformance/host-fixtures");

    /**
     * The conformance root the identity surface classifies compiled module
     * paths against (emitter page D1): fixture/companion class atoms are
     * machine-independent — {@code @conformance/<corpus-relative
     * components>/<file stem>/<C>} — while host paths keep the pinned
     * dotted projection. Set in {@code main} from the root argument.
     */
    private static Path conformanceRoot =
        Path.of("test/conformance").toAbsolutePath().normalize();

    // =========================================================================
    // Data types
    // =========================================================================

    private record TestFile(
        Path path,
        String relativePath,
        String phase,
        String spec,
        String description,
        String expected,
        String features,
        String issue
    ) {}

    /** Outcome classification of one fixture in the v1.2 gate. */
    private enum State { PASS, FAIL, SKIP, KNOWN_FAIL, COMPANION, STAGED_FAIL }

    private record TestResult(
        TestFile test,
        State state,
        String message
    ) {}

    /** Probe result of one underlying expectation check. */
    private record RunProbe(boolean ok, boolean environmental, String detail) {}

    /**
     * One staged-failure registry entry: the corpus-relative path, the
     * fixture classification the entry is pinned to, the locked artifact
     * error code the staged state must keep producing, the owning issue,
     * and the documented reason.
     */
    private record StagedEntry(String path, String pinnedExpectation,
        String artifactCode, String issue, String reason) {}

    /**
     * The staged-failure registry: design-sanctioned interim states whose
     * disposition is owed by another child (mirrors the JVM lane's skip
     * registry: every entry is validated against the on-disk corpus each
     * run, and an entry that becomes stale fails the gate with a
     * promotion instruction).
     *
     * <p>The single entry is the locked {@code TIME_NOW_MILLIS} artifact
     * (luajit-v1.2-stdlib-contracts D6, luajit-v1.2-conformance-retirement-and-gate
     * D3): the retained {@code std/time.nowMillis ()->int} route computes
     * {@code os.time() * 1000} (≈1.7e12 for contemporary epoch
     * milliseconds), which deterministically raises E8004 under the
     * signed-int32 gate landed by ISSUE-0332, while the shared corpus
     * fixture keeps its {@code runtime-ok} expectation and {@code
     * std/time.lua} stays frozen until the delegated time-selector child
     * (ISSUE-0237) lands its disposition pair. This runner records the
     * staged state without touching the fixture or the stdlib module.</p>
     */
    private static final Map<String, StagedEntry> STAGED_FAILURES =
        new LinkedHashMap<>();
    static {
        stagedFailure("backend-runtime/stdlib-edge/time-now-millis-positive.deal",
            "runtime-ok",
            "E8004",
            "ISSUE-0237",
            "the retained std/time.nowMillis ()->int route raises E8004 "
                + "for contemporary epoch milliseconds under the "
                + "signed-int32 gate (locked TIME_NOW_MILLIS artifact); "
                + "the fixture's runtime-ok expectation and std/time.lua "
                + "are frozen until the delegated time-selector child "
                + "lands its disposition pair");
    }

    private static void stagedFailure(String path, String pinnedExpectation,
            String artifactCode, String issue, String reason) {
        STAGED_FAILURES.put(path, new StagedEntry(path, pinnedExpectation,
            artifactCode, issue, reason));
    }

    /**
     * A companion module that has been compiled to Lua and is ready to be
     * written to the temp directory for runtime resolution.
     */
    private record CompanionModule(String luaSource, String moduleName) {}

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        String conformanceRootArg = "test/conformance/";
        if (args.length > 0) {
            conformanceRootArg = args[0];
            hostFixturesRoot = Path.of(conformanceRootArg).resolve("host-fixtures");
        }
        conformanceRoot = Path.of(conformanceRootArg)
            .toAbsolutePath().normalize();

        try {
            new ProcessBuilder("luajit", "-v").start().waitFor();
            luajitAvailable = true;
        } catch (IOException e) {
            luajitAvailable = false;
        }

        System.out.println("=== DEAL v1.2 Conformance Test Suite ===");
        System.out.println("Root: " + conformanceRootArg);
        System.out.println("LuaJIT: " + (luajitAvailable ? "available" :
            "NOT available (runtime tests will be skipped)"));
        System.out.println();

        // Discover test files
        List<TestFile> tests = discoverTests(conformanceRoot);
        System.out.println("Discovered " + tests.size() + " conformance test(s)");
        System.out.println();

        // Validate the staged-failure registry against the on-disk corpus:
        // every entry must name a discovered fixture, so the staged state
        // can never silently disappear.
        Set<String> discovered = new HashSet<>();
        for (TestFile test : tests) {
            discovered.add(test.relativePath());
        }
        for (var entry : STAGED_FAILURES.entrySet()) {
            if (!discovered.contains(entry.getKey())) {
                classificationFailure("staged-failure registry entry '"
                    + entry.getKey() + "' names no discovered fixture");
            }
        }

        // LegacyProfileRegressionCatalog validation (A4): every row
        // locator resolves, the closed completeness rule is enforced over
        // the discovered corpus, and the mechanism self-probes run before
        // any fixture executes. A violation is a harness defect — the gate
        // fails naming it, never silently weakening a pin.
        LegacyProfileRegressionCatalog.validateRows();
        LegacyProfileRegressionCatalog.runSelfProbes();
        for (TestFile test : tests) {
            LegacyProfileRegressionCatalog.scanDealSource(
                test.relativePath(), Files.readString(test.path()),
                test.expected());
        }
        List<String> catalogViolations =
            LegacyProfileRegressionCatalog.drainViolations();
        if (!catalogViolations.isEmpty()) {
            System.out.println("CATALOG FAILURE: "
                + "LegacyProfileRegressionCatalog validation failed:");
            for (String violation : catalogViolations) {
                System.out.println("  " + violation);
            }
            System.exit(1);
        }

        // Run each test
        for (TestFile test : tests) {
            runTest(test);
        }

        // Print summary
        printSummary();

        // Print coverage report
        printCoverageReport();

        // Gate-closure strict gate (luajit-gate-closure D2/D5): the
        // post-unit zero-fail assertion, keyed on the staged-failure
        // registry shape. Dormant under the exact sanctioned pre-unit
        // ISSUE-0237 pair — no assertion, no extra output; strict under
        // the empty registry; a hard failure for every other shape. Its
        // strict-mode residual GATE FAILURE lines print before any exit,
        // and the dormant mode leaves the failed > 0 exit below as the
        // only exit mechanism.
        runGateClosureCheck();

        if (failed > 0) {
            System.exit(1);
        }
    }



    // =========================================================================
    // Gate-closure strict gate (post-unit zero-fail assertion)
    // =========================================================================

    /**
     * The gate-closure strict gate (luajit-gate-closure D2/D5): the
     * post-unit zero-fail assertion evaluated in the summary/exit path.
     * The activation key is the staged-failure registry shape, compared
     * field-exact on the {@code StagedEntry} fields {@code path},
     * {@code pinnedExpectation}, {@code artifactCode}, and {@code issue}
     * (the {@code reason} string is documentation and is not compared):
     *
     * <ul>
     *   <li>Dormant — the registry equals the exact sanctioned pre-unit
     *       ISSUE-0237 pair (exactly one entry: path
     *       {@code backend-runtime/stdlib-edge/time-now-millis-positive.deal},
     *       pinned expectation {@code runtime-ok}, artifact code
     *       {@code E8004}, issue {@code ISSUE-0237}): no assertion and no
     *       extra output; the pre-unit tracked non-fatal semantics and the
     *       preserved {@code failed > 0} exit are byte-for-byte unchanged
     *       (the closure is pending and never asserted).</li>
     *   <li>Strict — the registry is empty (the disposition-application
     *       unit's landing artifact): the backend-runtime phase counters
     *       ({@code phaseStats} {@code record()} {@code int[5]} indices
     *       1-4 — failed, skipped, known-fail, staged) and the global
     *       {@code knownFailures} counter must be zero. Residuals are
     *       enumerated from the {@code specGroups} {@code TestResult}
     *       records — a residual is any backend-runtime fixture whose
     *       recorded state is FAIL, SKIP, KNOWN_FAIL, or STAGED_FAIL,
     *       plus any known-fail fixture of either phase for the
     *       summary-level assertion (D5). The {@code specGroups} records
     *       correspond one-to-one with the phase counters and the global
     *       {@code knownFailures} counter ({@code record()} increments
     *       both), so strict mode asserts those counters zero exactly
     *       when no residual enumerates; with the zeros no
     *       {@code GATE FAILURE} line prints, the preserved
     *       {@code failed > 0} check is false, and the process exits 0.</li>
     *   <li>Shape failure — any other registry shape is itself a gate
     *       failure regardless of the counters (a re-added entry, a new
     *       entry for any fixture, or any field edit of the sanctioned
     *       entry — even when the dispatch recorded a tracked
     *       STAGED-FAIL first).</li>
     * </ul>
     */
    private static void runGateClosureCheck() {
        if (STAGED_FAILURES.isEmpty()) {
            runStrictModeGate();
            return;
        }
        if (isSanctionedPreUnitPair()) {
            // Dormant mode: the closure is pending and never asserted.
            return;
        }
        for (StagedEntry entry : STAGED_FAILURES.values()) {
            System.out.println("GATE FAILURE: staged-failure registry is "
                + "neither the sanctioned pre-unit ISSUE-0237 pair nor empty — "
                + entry.path() + " (tracked by " + entry.issue() + ")");
        }
        System.out.println("promotion instruction: remove the registry entry "
            + "(or entries)");
        System.exit(1);
    }

    /**
     * Strict mode (empty registry): enumerate the post-unit residuals from
     * the {@code specGroups} {@code TestResult} records and print one
     * pinned {@code GATE FAILURE} line per residual before
     * {@code System.exit(1)} (the JVM precedent pattern,
     * {@code test/JvmConformanceTest.java}).
     */
    private static void runStrictModeGate() {
        List<TestResult> failedResiduals = new ArrayList<>();
        List<TestResult> skippedResiduals = new ArrayList<>();
        List<TestResult> knownFailResiduals = new ArrayList<>();
        List<TestResult> stagedResiduals = new ArrayList<>();
        for (List<TestResult> results : specGroups.values()) {
            for (TestResult result : results) {
                if (result.state() == State.KNOWN_FAIL) {
                    // Summary-level known-fail assertion (D5): any
                    // known-fail fixture of either phase.
                    knownFailResiduals.add(result);
                } else if ("backend-runtime".equals(
                        result.test().phase())) {
                    switch (result.state()) {
                        case FAIL -> failedResiduals.add(result);
                        case SKIP -> skippedResiduals.add(result);
                        case STAGED_FAIL -> stagedResiduals.add(result);
                        default -> { }
                    }
                }
            }
        }
        if (failedResiduals.isEmpty() && skippedResiduals.isEmpty()
                && knownFailResiduals.isEmpty()
                && stagedResiduals.isEmpty()) {
            return;
        }
        for (TestResult result : failedResiduals) {
            System.out.println("GATE FAILURE: " + failedResiduals.size()
                + " failed — " + result.test().relativePath() + " — "
                + result.message());
        }
        for (TestResult result : skippedResiduals) {
            System.out.println("GATE FAILURE: " + skippedResiduals.size()
                + " skipped — " + result.test().relativePath()
                + " — LuaJIT unavailable on the gate machine; the LuaJIT "
                + "lane cannot be verified (environmental probe branch)");
        }
        for (TestResult result : knownFailResiduals) {
            // <mode> resolves to the mode token(s) after the
            // 'known-fail ' prefix in the residual's classified @expected
            // value; <issue> resolves to the residual's retained @issue
            // tag. Both are retained on the TestFile that record() stored
            // in this TestResult — no re-execution, no fixture re-read,
            // no parsing of the recorded detail (the detail is used only
            // by the failed-residual line).
            String mode = result.test().expected()
                .substring("known-fail ".length()).trim();
            System.out.println("GATE FAILURE: " + knownFailResiduals.size()
                + " known-fail — " + result.test().relativePath()
                + " — tracked by " + result.test().issue()
                + "; promotion instruction: set '@expected: " + mode
                + "' and drop the @issue tag");
        }
        for (TestResult result : stagedResiduals) {
            // Unreachable in strict mode — an empty registry cannot record
            // STAGED-FAIL; retained as defense in depth.
            System.out.println("GATE FAILURE: " + stagedResiduals.size()
                + " staged — " + result.test().relativePath()
                + " — promotion instruction: remove the registry entry");
        }
        System.exit(1);
    }

    /**
     * True when the staged-failure registry is field-exactly the
     * sanctioned pre-unit ISSUE-0237 pair.
     */
    private static boolean isSanctionedPreUnitPair() {
        if (STAGED_FAILURES.size() != 1) {
            return false;
        }
        StagedEntry entry = STAGED_FAILURES.get(
            "backend-runtime/stdlib-edge/time-now-millis-positive.deal");
        return entry != null
            && "runtime-ok".equals(entry.pinnedExpectation())
            && "E8004".equals(entry.artifactCode())
            && "ISSUE-0237".equals(entry.issue());
    }

    // =========================================================================
    // Discovery and classification
    // =========================================================================

    private static List<TestFile> discoverTests(Path root) throws IOException {
        List<TestFile> result = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(p -> p.toString().endsWith(".deal"))
                  // Host fixture declarations (host-fixtures/*.d.deal)
                  // carry the @expected: host-fixture classification and
                  // are resolved on demand by the host-fixture registry
                  // (host-module-abi D6) — the discovery walk skips the
                  // subtree entirely. A host-fixture classification outside
                  // the subtree is a configuration failure.
                  .filter(p -> {
                      Path rel = root.relativize(p);
                      return rel.getNameCount() == 0
                          || !"host-fixtures".equals(
                              rel.getName(0).toString());
                  })
                  .sorted()
                  .forEach(p -> {
                      TestFile tf = parseMetadata(p, root);
                      if (tf != null) {
                          result.add(tf);
                      }
                  });
        }
        return result;
    }

    private static TestFile parseMetadata(Path file, Path root) {
        try {
            List<String> lines = Files.readAllLines(file);
            String spec = "";
            String description = "";
            String expected = "";
            String features = "";
            String issue = "";

            int linesToScan = Math.min(lines.size(), 40);
            for (int i = 0; i < linesToScan; i++) {
                String line = lines.get(i).trim();
                if (line.startsWith("// @spec:")) {
                    spec = line.substring("// @spec:".length()).trim();
                } else if (line.startsWith("// @description:")) {
                    description = line.substring("// @description:".length()).trim();
                } else if (line.startsWith("// @expected:")) {
                    expected = line.substring("// @expected:".length()).trim();
                } else if (line.startsWith("// @features:")) {
                    features = line.substring("// @features:".length()).trim();
                } else if (line.startsWith("// @issue:")) {
                    issue = line.substring("// @issue:".length()).trim();
                }
            }

            String relPath = root.relativize(file).toString();
            String phase = relPath.contains(File.separator)
                ? relPath.substring(0, relPath.indexOf(File.separator))
                : "";

            if (expected.isEmpty()) {
                classificationFailure(file + ": no @expected tag — the v1.2 "
                    + "gate has no unclassified skips; classify the file as a "
                    + "test or as '@expected: companion'");
                return null;
            }

            if (expected.equals("companion")) {
                if (!spec.isEmpty() && !validSpecReference(spec)) {
                    classificationFailure(file + ": companion @spec '" + spec
                        + "' does not reference a v1.2 spec section");
                    return null;
                }
                return new TestFile(file.toAbsolutePath(), relPath, phase, spec,
                    description, expected, features, issue);
            }

            if (expected.equals("host-fixture")) {
                classificationFailure(file + ": @expected: host-fixture is "
                    + "reserved for test/conformance/host-fixtures/*.d.deal");
                return null;
            }

            if (spec.isEmpty() || !validSpecReference(spec)) {
                classificationFailure(file + ": missing or stale @spec '"
                    + spec + "' — the v1.2 gate requires every expectation to "
                    + "reference a v1.2 spec section");
                return null;
            }

            if (expected.startsWith("known-fail ")) {
                String mode = expected.substring("known-fail ".length()).trim();
                String modeError = knownFailModeError(mode);
                if (modeError != null) {
                    classificationFailure(file + ": " + modeError);
                    return null;
                }
                if (issue.isEmpty()) {
                    classificationFailure(file + ": @expected: known-fail "
                        + "requires a // @issue: <tracked follow-up issue> tag");
                    return null;
                }
                return new TestFile(file.toAbsolutePath(), relPath, phase, spec,
                    description, expected, features, issue);
            }

            if (expected.startsWith("compile-error ")
                    || expected.startsWith("runtime-error ")) {
                String code = expected.substring(expected.indexOf(' ') + 1).trim();
                if (code.isEmpty() || code.equals("any")) {
                    classificationFailure(file + ": '" + expected + "' must "
                        + "name one specific diagnostic code (the 'any' form "
                        + "is reserved for @expected: known-fail)");
                    return null;
                }
            } else if (!expected.equals("compile-ok")
                    && !expected.equals("runtime-ok")) {
                classificationFailure(file + ": unknown @expected '"
                    + expected + "' — the v1.2 gate has no unclassified skips");
                return null;
            }

            return new TestFile(file.toAbsolutePath(), relPath, phase, spec,
                description, expected, features, issue);
        } catch (IOException e) {
            classificationFailure(file + ": cannot read: " + e.getMessage());
            return null;
        }
    }

    /** Records a classification failure (unclassified-skip removal). */
    private static void classificationFailure(String message) {
        classificationFailures.add(message);
        failed++;
    }

    /**
     * The v1.2 spec sections a {@code @spec} reference may start with.
     * Mirrors the section headings of fs/docs/spec-v1.2.md; the two
     * project report groups ("Standard library declarations",
     * "C FFI declaration files") are {@code ###} subsections of the
     * modules chapter and are accepted as first components for fixture
     * grouping.
     */
    private static final Set<String> SPEC_HEADINGS = Set.of(
        "Lexical elements",
        "Syntactic grammar",
        "Type system",
        "Classes",
        "Functions",
        "Variables",
        "Tables",
        "Arrays",
        "Bytes",
        "Control flow",
        "Error handling",
        "Async/Await",
        "Modules, declarations, standard library, and host ABI",
        "C FFI declaration files",
        "Runtime execution model",
        "Standard library declarations",
        "Test suite basis",
        "Diagnostics"
    );

    private static boolean validSpecReference(String spec) {
        String first = spec.split(" \u2014 ", 2)[0].trim();
        return SPEC_HEADINGS.contains(first);
    }

    /**
     * Returns an error description when {@code mode} is not a supported
     * known-fail mode, or {@code null} when it is.
     */
    private static String knownFailModeError(String mode) {
        if (mode.equals("compile-ok") || mode.equals("runtime-ok")) {
            return null;
        }
        if (mode.startsWith("compile-error ") || mode.startsWith("runtime-error ")) {
            String code = mode.substring(mode.indexOf(' ') + 1).trim();
            if (code.isEmpty()) {
                return "known-fail '" + mode + "' must name a diagnostic code "
                    + "(or 'any')";
            }
            return null;
        }
        return "unknown known-fail mode '" + mode
            + "' (supported: compile-ok, compile-error CODE|any, "
            + "runtime-ok, runtime-error CODE|any)";
    }

    // =========================================================================
    // Test execution dispatch
    // =========================================================================

    private static void runTest(TestFile test) {
        boolean legacyAuthority = LegacyProfileRegressionCatalog
            .isCatalogued(test.relativePath());
        System.out.print("  [" + test.relativePath() + "]"
            + (legacyAuthority
                ? " LEGACY-AUTHORITY (legacy-regression; zero v1.2 credit)"
                : "")
            + " ");
        String expected = test.expected();
        int passedSnapshot = passed;
        int failedSnapshot = failed;

        try {
            StagedEntry staged = STAGED_FAILURES.get(test.relativePath());
            if (staged != null && staged.pinnedExpectation().equals(expected)) {
                runStagedFailure(test, staged);
            } else if (staged != null) {
                // The owning child changed the fixture's classification:
                // the entry is stale — fail the gate with a promotion
                // instruction (remove the registry entry so the fixture
                // runs under its new expectation).
                System.out.println("FAIL (STALE staged entry: '"
                    + test.relativePath() + "' is now classified '"
                    + expected + "' instead of the pinned '"
                    + staged.pinnedExpectation() + "' — the "
                    + staged.issue() + " child applied its disposition: "
                    + "remove the registry entry)");
                record(test, State.FAIL, "stale staged entry; fixture "
                    + "expectation changed to '" + expected + "'");
            } else if (expected.equals("companion")) {
                runCompanion(test);
            } else if (expected.startsWith("known-fail ")) {
                runKnownFail(test);
            } else if (expected.startsWith("compile-ok")) {
                runCompileOk(test);
            } else if (expected.startsWith("runtime-ok")) {
                runRuntimeOk(test);
            } else if (expected.startsWith("compile-error ")) {
                String code = expected.substring("compile-error ".length()).trim();
                runCompileError(test, code);
            } else if (expected.startsWith("runtime-error ")) {
                String code = expected.substring("runtime-error ".length()).trim();
                runRuntimeError(test, code);
            } else {
                // parseMetadata rejects unknown expectations; this branch
                // is unreachable defense-in-depth.
                System.out.println("FAIL (unknown @expected: " + expected + ")");
                record(test, State.FAIL, "unknown @expected: " + expected);
            }
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
            record(test, State.FAIL, "exception: " + e.getMessage());
        }
        if (legacyAuthority) {
            legacyAuthorityResults++;
            if (passed > passedSnapshot) legacyAuthorityPassed++;
            if (failed > failedSnapshot) legacyAuthorityFailed++;
        } else {
            v12CreditResults++;
        }
    }

    // =========================================================================
    // Classified support modules (companions)
    // =========================================================================

    private static void runCompanion(TestFile test) throws Exception {
        List<CompilerDiagnostic> diags = compileCompanion(test);
        boolean hasErrors = diags.stream().anyMatch(d -> "error".equals(d.severity()));
        if (hasErrors) {
            System.out.println("FAIL (companion support module does not compile)");
            for (CompilerDiagnostic d : diags) {
                if ("error".equals(d.severity())) {
                    System.out.println("    " + d);
                }
            }
            record(test, State.FAIL, "companion does not compile");
        } else {
            System.out.println("COMPANION (classified support module; compiles)");
            record(test, State.COMPANION, "companion compiles");
        }
    }

    /**
     * Compiles a classified companion support module standalone. Regular
     * {@code .deal} companions run the shared frontend pipeline
     * (lexer/parser/name resolution/type checking). {@code .d.deal}
     * declaration companions run the declaration-file pipeline the
     * harness itself uses when the parent fixture imports them
     * ({@link ConformanceModuleResolver#resolveFileModule}: lexer/parser
     * plus {@link ExportExtractor}), because external function
     * declarations have no body to type-check.
     */
    @SuppressWarnings("deprecation")
    private static List<CompilerDiagnostic> compileCompanion(TestFile test) {
        try {
            String source = Files.readString(test.path());
            String filename = test.path().toString();

            LexResult lex = new Lexer(source, filename).tokenize();
            if (lex.hasErrors()) {
                return lex.diagnostics();
            }

            Parser parser = new Parser(lex.tokens(), filename,
                LegacyProfileRegressionCatalog.frontendInvocation()
                    .semanticProfile());
            ParseResult parseResult = parser.parse();
            if (parseResult.hasErrors()) {
                return parseResult.diagnostics();
            }

            if (filename.endsWith(".d.deal")) {
                ExportExtractor extractor =
                    new ExportExtractor(filename, true);
                extractor.extract(parseResult.program());
                return extractor.diagnostics();
            }

            // DEAL v1.2 module shape gate (spec-v1.2: Syntactic grammar —
            // Statement/expression separation): imports must precede all
            // top-level declarations (E1048), the module top level allows
            // only imports/functions/classes/exports (E1049), imports and
            // exports are not statements (E1050), and implementation files
            // have no bodyless function declarations (E1051).
            List<CompilerDiagnostic> shapeDiags =
                ModuleShapeValidator.validate(
                    parseResult.program(), filename, false);
            if (shapeDiags.stream().anyMatch(d -> "error".equals(d.severity()))) {
                return new ArrayList<>(shapeDiags);
            }

            List<CompilerDiagnostic> allDiags = new ArrayList<>();
            ConformanceModuleResolver resolver =
                new ConformanceModuleResolver(test.path(), null,
                    LegacyProfileRegressionCatalog.frontendInvocation()
                        .semanticProfile());
            NameResolver nr = new NameResolver(filename, resolver);
            SymbolTable symTable;
            try {
                symTable = nr.resolve(parseResult.program());
            } catch (Exception e) {
                // E9999 is the test-only pseudo code for an unexpected
                // NameResolver exception (D5/verification 6): the
                // deprecated synthetic factory carries the canonical
                // synthetic range plus an anchor note naming the fixture
                // source.
                allDiags.add(CompilerDiagnostic.synthetic("E9999", "error",
                    e.getMessage(), filename,
                    "missing anchor: fixture source '" + filename + "'"));
                return allDiags;
            }
            allDiags.addAll(nr.diagnostics());
            CheckResult result = TypeChecker.check(filename, symTable, nr,
                parseResult.program());
            allDiags.addAll(result.diagnostics());
            return allDiags;
        } catch (IOException e) {
            String filename = test.path().toString();
            return List.of(CompilerDiagnostic.synthetic("E9999", "error",
                "cannot read: " + e.getMessage(), filename,
                "missing anchor: fixture source '" + filename + "'"));
        }
    }

    // =========================================================================
    // Staged failures: design-sanctioned interim states (tracked)
    // =========================================================================

    /**
     * A staged failure: the fixture's own expectation is temporarily
     * unsatisfied by a locked artifact whose disposition is owed by the
     * registry entry's issue. The runner verifies that the locked
     * artifact code is still produced and records the case as a
     * non-fatal tracked staged failure. If the fixture starts passing
     * its own expectation (the owning child landed a passing
     * disposition) or fails for any other reason, the gate FAILS —
     * promotion is forced instead of silently forgotten.
     */
    private static void runStagedFailure(TestFile test, StagedEntry entry)
            throws Exception {
        RunProbe locked = probeRuntimeError(test, entry.artifactCode());
        if (locked.environmental()) {
            System.out.println("SKIP (" + locked.detail() + ")");
            record(test, State.SKIP, locked.detail());
            return;
        }
        if (locked.ok()) {
            System.out.println("STAGED-FAIL (" + entry.artifactCode()
                + " locked artifact; tracked by " + entry.issue() + ": "
                + entry.reason() + ")");
            stagedFailuresByIssue.merge(entry.issue(), 1, Integer::sum);
            record(test, State.STAGED_FAIL, entry.reason());
            return;
        }
        RunProbe own = probeRuntimeOk(test);
        if (own.ok()) {
            System.out.println("FAIL (STALE staged entry: the fixture now "
                + "passes '" + test.expected() + "' — the " + entry.issue()
                + " child landed a passing disposition: remove the "
                + "registry entry)");
            record(test, State.FAIL,
                "stale staged entry; fixture passes its expectation");
        } else {
            System.out.println("FAIL (fixture fails outside the locked "
                + entry.artifactCode() + " artifact: " + own.detail() + ")");
            record(test, State.FAIL, "unexpected failure: " + own.detail());
        }
    }

    // =========================================================================
    // Intentionally unsupported v1.2 cases: explicit failing fixtures
    // =========================================================================

    private static void runKnownFail(TestFile test) throws Exception {
        String mode = test.expected().substring("known-fail ".length()).trim();
        RunProbe probe = probeMode(test, mode);
        if (probe.environmental()) {
            System.out.println("SKIP (" + probe.detail() + ")");
            record(test, State.SKIP, probe.detail());
            return;
        }
        if (probe.ok()) {
            System.out.println("FAIL (STALE known-fail: the v1.2 requirement "
                + "tracked by " + test.issue() + " now passes — promote the "
                + "fixture: set '@expected: " + mode + "' and drop the "
                + "@issue tag)");
            record(test, State.FAIL, "stale known-fail; promote fixture");
        } else {
            System.out.println("KNOWN-FAIL (" + mode + " not yet satisfied; "
                + "tracked by " + test.issue() + ")");
            knownFailsByIssue.merge(test.issue(), 1, Integer::sum);
            record(test, State.KNOWN_FAIL, probe.detail());
        }
    }

    /**
     * Executes the underlying expectation of a mode and reports whether it
     * currently passes. Used by the known-fail classification (and the
     * normal dispatch below): {@code ok=true} means the v1.2 requirement
     * is satisfied today.
     */
    private static RunProbe probeMode(TestFile test, String mode) throws Exception {
        SemanticProfile probeProfile = LegacyProfileRegressionCatalog
            .profileFor(test.relativePath());
        if (mode.equals("compile-ok")) {
            List<CompilerDiagnostic> diags = compileAndGetDiagnostics(test, null,
                probeProfile);
            boolean hasErrors = diags.stream()
                .anyMatch(d -> "error".equals(d.severity()));
            return new RunProbe(!hasErrors, false,
                hasErrors ? "unexpected compile errors: " + errorCodes(diags)
                    : "compiles");
        }
        if (mode.equals("runtime-ok")) {
            return probeRuntimeOk(test);
        }
        if (mode.startsWith("compile-error ")) {
            String code = mode.substring("compile-error ".length()).trim();
            List<CompilerDiagnostic> diags = compileAndGetDiagnostics(test, null,
                probeProfile);
            boolean found = "any".equals(code)
                ? diags.stream().anyMatch(d -> "error".equals(d.severity()))
                : diags.stream().anyMatch(d -> "error".equals(d.severity())
                    && code.equals(d.code()));
            return new RunProbe(found, false,
                found ? "produces " + code
                    : "does not produce " + code + " (got: "
                        + errorCodes(diags) + ")");
        }
        if (mode.startsWith("runtime-error ")) {
            String code = mode.substring("runtime-error ".length()).trim();
            return probeRuntimeError(test, code);
        }
        return new RunProbe(false, false, "unsupported known-fail mode: " + mode);
    }

    private static List<String> errorCodes(List<CompilerDiagnostic> diags) {
        List<String> codes = new ArrayList<>();
        for (CompilerDiagnostic d : diags) {
            if ("error".equals(d.severity())) {
                codes.add(d.code());
            }
        }
        return codes;
    }

    // =========================================================================
    // compile-ok
    // =========================================================================

    private static void runCompileOk(TestFile test) throws Exception {
        List<CompilerDiagnostic> diags = compileAndGetDiagnostics(test, null,
            LegacyProfileRegressionCatalog.frontendInvocation()
                .semanticProfile());
        boolean hasErrors = diags.stream().anyMatch(d -> "error".equals(d.severity()));
        if (hasErrors) {
            System.out.println("FAIL (unexpected compile errors)");
            for (CompilerDiagnostic d : diags) {
                if ("error".equals(d.severity())) {
                    System.out.println("    " + d);
                }
            }
            record(test, State.FAIL, "unexpected compile errors");
        } else {
            System.out.println("OK");
            record(test, State.PASS, "compile ok");
        }
    }

    // =========================================================================
    // runtime-ok
    // =========================================================================

    private static void runRuntimeOk(TestFile test) throws Exception {
        RunProbe probe = probeRuntimeOk(test);
        if (probe.environmental()) {
            System.out.println("SKIP (" + probe.detail() + ")");
            record(test, State.SKIP, probe.detail());
            return;
        }
        if (probe.ok()) {
            System.out.println("OK");
            record(test, State.PASS, "runtime ok");
        } else {
            System.out.println("FAIL (" + probe.detail() + ")");
            record(test, State.FAIL, probe.detail());
        }
    }

    private static RunProbe probeRuntimeOk(TestFile test) throws Exception {
        if (!luajitAvailable) {
            return new RunProbe(false, true, "LuaJIT not available");
        }

        SemanticProfile caseProfile = LegacyProfileRegressionCatalog
            .profileFor(test.relativePath());
        CompanionCatalog catalog = new CompanionCatalog(caseProfile);
        List<CompilerDiagnostic> diags = compileAndGetDiagnostics(test, catalog,
            caseProfile);
        boolean hasErrors = diags.stream().anyMatch(d -> "error".equals(d.severity()));
        if (hasErrors) {
            return new RunProbe(false, false,
                "unexpected compile errors: " + errorCodes(diags));
        }

        // Generate Lua for the main test file and any companion modules it imports
        var generated = generateLuaWithCompanions(test.path(), catalog);
        if (generated == null) {
            return new RunProbe(false, false, "codegen failed");
        }

        String output = executeLua(generated.mainLua(), generated.companionModules(), false);
        if (output == null) {
            return new RunProbe(false, false, "Lua execution failed");
        }
        return new RunProbe(true, false, "runtime ok");
    }

    // =========================================================================
    // compile-error
    // =========================================================================

    private static void runCompileError(TestFile test, String expectedCode) throws Exception {
        List<CompilerDiagnostic> diags = compileAndGetDiagnostics(test, null,
            LegacyProfileRegressionCatalog.frontendInvocation()
                .semanticProfile());
        boolean found = diags.stream().anyMatch(
            d -> "error".equals(d.severity()) && expectedCode.equals(d.code()));
        if (found) {
            System.out.println("OK (found " + expectedCode + ")");
            record(test, State.PASS, "found " + expectedCode);
        } else {
            System.out.println("FAIL (expected " + expectedCode +
                ", got: " + errorCodes(diags) + ")");
            record(test, State.FAIL,
                "expected " + expectedCode + ", got: " + errorCodes(diags));
        }
    }

    // =========================================================================
    // runtime-error
    // =========================================================================

    private static void runRuntimeError(TestFile test, String expectedCode) throws Exception {
        RunProbe probe = probeRuntimeError(test, expectedCode);
        if (probe.environmental()) {
            System.out.println("SKIP (" + probe.detail() + ")");
            record(test, State.SKIP, probe.detail());
            return;
        }
        if (probe.ok()) {
            System.out.println("OK (found DEAL_ERROR_CODE: " + expectedCode + ")");
            record(test, State.PASS, "found " + expectedCode);
        } else {
            System.out.println("FAIL (" + probe.detail() + ")");
            record(test, State.FAIL, probe.detail());
        }
    }

    private static RunProbe probeRuntimeError(TestFile test, String expectedCode) throws Exception {
        if (!luajitAvailable) {
            return new RunProbe(false, true, "LuaJIT not available");
        }

        SemanticProfile caseProfile = LegacyProfileRegressionCatalog
            .profileFor(test.relativePath());
        CompanionCatalog catalog = new CompanionCatalog(caseProfile);
        List<CompilerDiagnostic> diags = compileAndGetDiagnostics(test, catalog,
            caseProfile);
        boolean hasErrors = diags.stream().anyMatch(d -> "error".equals(d.severity()));
        if (hasErrors) {
            return new RunProbe(false, false,
                "unexpected compile errors: " + errorCodes(diags));
        }

        // Generate Lua for the main test file and any companion modules it imports
        var generated = generateLuaWithCompanions(test.path(), catalog);
        if (generated == null) {
            return new RunProbe(false, false, "codegen failed");
        }

        String output = executeLua(generated.mainLua(), generated.companionModules(), true);
        if (output == null) {
            return new RunProbe(false, false, "Lua execution returned null");
        }

        if ("any".equals(expectedCode)) {
            if (output.contains("DEAL_ERROR_CODE: ")) {
                return new RunProbe(true, false, "found DEAL_ERROR_CODE");
            }
            return new RunProbe(false, false, "expected any DEAL_ERROR_CODE, got: "
                + output.replace("\n", "\\n"));
        }

        String needle = "DEAL_ERROR_CODE: " + expectedCode;
        if (output.contains(needle)) {
            return new RunProbe(true, false, "found " + expectedCode);
        }
        return new RunProbe(false, false, "expected " + needle + ", got: "
            + output.replace("\n", "\\n"));
    }

    // =========================================================================
    // Compilation helpers
    // =========================================================================

    @SuppressWarnings("deprecation")
    private static List<CompilerDiagnostic> compileAndGetDiagnostics(TestFile test,
            CompanionCatalog catalog, SemanticProfile profile) throws Exception {
        String source = Files.readString(test.path());
        String filename = test.path().toString();

        LexResult lex = new Lexer(source, filename).tokenize();
        List<CompilerDiagnostic> allDiags = new ArrayList<>(lex.diagnostics());
        if (lex.hasErrors()) {
            return allDiags;
        }

        Parser parser = new Parser(lex.tokens(), filename, profile);
        ParseResult parseResult = parser.parse();
        allDiags.addAll(parseResult.diagnostics());
        if (parseResult.hasErrors()) {
            return allDiags;
        }

        // DEAL v1.2 module shape gate (spec-v1.2: Syntactic grammar —
        // Statement/expression separation): imports must precede all
        // top-level declarations (E1048), the module top level allows
        // only imports/functions/classes/exports (E1049), imports and
        // exports are not statements (E1050), and implementation files
        // have no bodyless function declarations (E1051).  This mirrors
        // the post-parse pass CompilationOrchestrator runs for every
        // production module.
        List<CompilerDiagnostic> shapeDiags =
            ModuleShapeValidator.validate(
                parseResult.program(), filename, false);
        allDiags.addAll(shapeDiags);
        if (shapeDiags.stream().anyMatch(d -> "error".equals(d.severity()))) {
            return allDiags;
        }

        ConformanceModuleResolver resolver =
            new ConformanceModuleResolver(test.path(), catalog, profile);
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable;
        try {
            symTable = nr.resolve(parseResult.program());
        } catch (Exception e) {
            // E9999 is the test-only pseudo code for an unexpected
            // NameResolver exception (D5/verification 6): the deprecated
            // synthetic factory carries the canonical synthetic range plus
            // an anchor note naming the fixture source.
            allDiags.add(CompilerDiagnostic.synthetic("E9999", "error",
                e.getMessage(), filename,
                "missing anchor: fixture source '" + filename + "'"));
            return allDiags;
        }
        allDiags.addAll(nr.diagnostics());

        CheckResult result = TypeChecker.check(filename, symTable, nr, parseResult.program());
        allDiags.addAll(result.diagnostics());

        return allDiags;
    }

    // =========================================================================
    // GeneratedLua and companion module support
    // =========================================================================

    /**
     * Result of generating Lua for a test file and its companion modules.
     */
    private record GeneratedLua(String mainLua, Map<String, CompanionModule> companionModules) {}

    /**
     * Generate Lua for a test file and any companion modules it imports.
     * <p>
     * Companion modules are .deal files reachable from the test file through
     * relative ({@code ./} / {@code ../}) imports. The {@link CompanionCatalog}
     * compiles each companion transitively (depth-first, with a cycle guard)
     * and this method collects the full transitive closure so the Lua
     * {@code require} system can resolve every module at runtime.
     * <p>
     * A companion that fails to compile yields no entry (as before), which
     * surfaces as a runtime require failure in the test — the unchanged
     * failure mode.
     */
    private static GeneratedLua generateLuaWithCompanions(Path file,
            CompanionCatalog catalog) throws Exception {
        // The main test file is the v1.2 entry module of its own invocation:
        // it must export a non-async main(): null and the backend invokes
        // main() from the generated chunk.
        CompanionCatalog.Artifact mainArtifact =
            catalog.entryArtifactFor(file.toAbsolutePath().normalize());
        if (mainArtifact == null || mainArtifact.luaSource() == null) {
            return null;
        }
        boolean backendErrors = mainArtifact.backendDiagnostics().stream()
            .anyMatch(d -> "error".equals(d.severity()));
        if (backendErrors) {
            for (CompilerDiagnostic d : mainArtifact.backendDiagnostics()) {
                if ("error".equals(d.severity())) {
                    System.out.println("    " + d);
                }
            }
            return null;
        }

        Map<String, CompanionModule> companionModules = new LinkedHashMap<>();
        Set<Path> seen = new HashSet<>();
        collectCompanionModules(mainArtifact, catalog, companionModules, seen);
        return new GeneratedLua(mainArtifact.luaSource(), companionModules);
    }

    /**
     * Collects every transitively reachable companion module (depth-first)
     * from an artifact's direct companion dependencies. Each companion is
     * written once, keyed by its stem module name in the flat temp-dir
     * namespace.
     */
    private static void collectCompanionModules(CompanionCatalog.Artifact artifact,
            CompanionCatalog catalog,
            Map<String, CompanionModule> companionModules,
            Set<Path> seen) {
        for (Path dep : artifact.companionDependencies()) {
            if (!seen.add(dep.toAbsolutePath().normalize())) continue;
            CompanionCatalog.Artifact depArtifact =
                catalog.artifactFor(dep.toAbsolutePath().normalize());
            if (depArtifact == null || depArtifact.luaSource() == null) {
                continue; // compile failure → runtime require failure (unchanged)
            }
            String moduleName = moduleNameFor(dep);
            companionModules.put(moduleName,
                new CompanionModule(depArtifact.luaSource(), moduleName));
            collectCompanionModules(depArtifact, catalog, companionModules, seen);
        }
    }

    /**
     * Resolve a relative import path to a .deal file on disk.
     *
     * @param importPath the raw import path from the ImportDeclaration
     * @param baseDir    the directory containing the importing file
     * @return the resolved path, or {@code null} if not found
     */
    private static Path resolveCompanionPath(String importPath, Path baseDir) {
        if (!importPath.startsWith("./") && !importPath.startsWith("../")) {
            return null;
        }
        Path resolved = baseDir.resolve(importPath).normalize();
        if (Files.exists(resolved)) return resolved;
        Path withExt = baseDir.resolve(importPath + ".deal").normalize();
        if (Files.exists(withExt)) return withExt;
        Path withDeclExt = baseDir.resolve(importPath + ".d.deal").normalize();
        if (Files.exists(withDeclExt)) return withDeclExt;
        return null;
    }

    /**
     * Derive a Lua module name from a .deal file path.
     * Uses the filename stem (without extension) as the module name.
     */
    private static String moduleNameFor(Path path) {
        String name = path.getFileName().toString();
        if (name.endsWith(".d.deal")) {
            return name.substring(0, name.length() - ".d.deal".length());
        } else if (name.endsWith(".deal")) {
            return name.substring(0, name.length() - ".deal".length());
        }
        return name;
    }



    // =========================================================================
    // Lua execution
    // =========================================================================

    /**
     * Execute Lua source with optional companion modules.
     *
     * @param luaSource        the generated Lua for the main test module
     * @param companionModules companion module name → Lua source pairs to write
     *                         as .lua files in the temp directory
     * @param isXpcallWrapped  true for runtime-error tests, false for runtime-ok
     * @return stdout output, or {@code null} on failure
     */
    private static String executeLua(String luaSource,
            Map<String, CompanionModule> companionModules,
            boolean isXpcallWrapped) {
        // ABI invariant (lua-abi-emission-layer): generated Lua contains '$'
        // only inside quoted string keys — the frozen export-key surface
        // (exports["C$fromJson"]) and META keys under the bare class name.
        if (!dollarOnlyInQuotedKeys(luaSource)) {
            System.err.println("    Generated Lua contains $ outside a quoted string key");
            return null;
        }
        for (var entry : companionModules.entrySet()) {
            if (!dollarOnlyInQuotedKeys(entry.getValue().luaSource())) {
                System.err.println("    Companion " + entry.getKey()
                    + " contains $ outside a quoted string key");
                return null;
            }
        }
        String runner;
        if (isXpcallWrapped) {
            runner = buildXpcallRunner(luaSource);
        } else {
            runner = buildRuntimeOkRunner(luaSource);
        }
        try {
            Path tmpDir = Files.createTempDirectory("deal_conf_");
            Path luaFile = tmpDir.resolve("test_main.lua");
            Files.writeString(luaFile, runner);

            // Copy runtime
            Path runtimeDir = tmpDir.resolve("deal");
            Files.createDirectories(runtimeDir);
            Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

            // Write companion .lua files so that `require` can find them
            for (var entry : companionModules.entrySet()) {
                Path companionFile = tmpDir.resolve(entry.getKey() + ".lua");
                Files.writeString(companionFile, entry.getValue().luaSource());
            }

            // Copy host fixture implementations (<name>.lua →
            // <tmp>/host/<name>.lua) so bare host imports resolve through
            // the raw slash-form require under the runner's package.path
            // ("./?.lua" → "./host/<name>.lua"), host-module-abi D6. Host
            // .lua files are never inspected by the $-gate — it applies
            // to generated Lua only.
            if (Files.isDirectory(hostFixturesRoot)) {
                Path targetHostDir = tmpDir.resolve("host");
                Files.createDirectories(targetHostDir);
                try (var stream = Files.list(hostFixturesRoot)) {
                    stream.filter(p -> p.toString().endsWith(".lua"))
                          .forEach(p -> {
                              try {
                                  Files.copy(p,
                                      targetHostDir.resolve(p.getFileName()));
                              } catch (IOException ignored) {}
                          });
                }
            }

            // Copy stdlib .lua files
            Path stdDir = Path.of("std");
            if (Files.isDirectory(stdDir)) {
                Path targetStdDir = tmpDir.resolve("std");
                Files.createDirectories(targetStdDir);
                try (var stream = Files.list(stdDir)) {
                    stream.filter(p -> p.toString().endsWith(".lua"))
                          .forEach(p -> {
                              try {
                                  Files.copy(p, targetStdDir.resolve(p.getFileName()));
                              } catch (IOException ignored) {}
                          });
                }
            }

            ProcessBuilder pb = new ProcessBuilder("luajit", luaFile.toString());
            pb.directory(tmpDir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            int exit = p.waitFor();

            // Cleanup
            try {
                Files.walk(tmpDir).sorted(Comparator.reverseOrder())
                    .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
            } catch (IOException ignored) {}

            if (isXpcallWrapped) {
                return output;
            } else {
                if (exit != 0) {
                    System.err.println("    LuaJIT exit " + exit + ": " + output);
                    return null;
                }
                return output;
            }
        } catch (Exception e) {
            System.err.println("    Lua execution exception: " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns true iff every '$' in the generated Lua sits inside a quoted
     * string key (between the enclosing double quotes).
     */
    private static boolean dollarOnlyInQuotedKeys(String lua) {
        for (int i = lua.indexOf('$'); i >= 0; i = lua.indexOf('$', i + 1)) {
            int open = lua.lastIndexOf('"', i);
            int close = lua.indexOf('"', i + 1);
            if (open < 0 || close < 0 || open >= i || i >= close) {
                return false;
            }
        }
        return true;
    }

    /**
     * Build a runner for runtime-ok tests: load the module and call all
     * exported zero-argument functions.  Compiler-generated functions
     * (those with {@code $} in their name) are skipped because they
     * typically require arguments (e.g., {@code C$fromJson(s: string)}).
     */
    private static String buildRuntimeOkRunner(String generatedLua) {
        return
            "package.path = './?.lua;./std/?.lua;' .. package.path\n" +
            "local __mod = (function()\n" +
            generatedLua + "\n" +
            "end)()\n" +
            "if type(__mod) == 'table' then\n" +
            "  for __k, __v in pairs(__mod) do\n" +
            "    if type(__v) == 'table' and __v.__kind == 'function' then\n" +
            "      -- Skip compiler-generated functions ($ in name)\n" +
            "      -- as they typically require arguments\n" +
            "      if not string.find(__k, \"$\", 1, true) then\n" +
            "        -- The backend already invoked main() at chunk end\n" +
            "        -- (v1.2 entry contract): never invoke it twice.\n" +
            "        if __k ~= \"main\" then\n" +
            "          __v.f()\n" +
            "        end\n" +
            "      end\n" +
            "    end\n" +
            "  end\n" +
            "end\n";
    }

    /**
     * Build an xpcall-wrapped runner for runtime-error tests (D7).
     * Compiler-generated functions (those with {@code $} in name) are
     * skipped because they typically require arguments.
     */
    private static String buildXpcallRunner(String generatedLua) {
        return
            "package.path = './?.lua;./std/?.lua;' .. package.path\n" +
            "local __ok, __err = xpcall(function()\n" +
            "  local __mod = (function()\n" +
            generatedLua + "\n" +
            "  end)()\n" +
            "  if type(__mod) == 'table' then\n" +
            "    for __k, __v in pairs(__mod) do\n" +
            "      if type(__v) == 'table' and __v.__kind == 'function' then\n" +
            "        -- Skip compiler-generated functions ($ in name)\n" +
            "        if not string.find(__k, \"$\", 1, true) then\n" +
            "          -- The backend already invoked main() at chunk end\n" +
            "          -- (v1.2 entry contract): never invoke it twice.\n" +
            "          if __k ~= \"main\" then\n" +
            "            __v.f()\n" +
            "          end\n" +
            "        end\n" +
            "      end\n" +
            "    end\n" +
            "  end\n" +
            "end, function(err)\n" +
            "  if type(err) == 'table' and err.code ~= nil then\n" +
            "    print('DEAL_ERROR_CODE: ' .. err.code)\n" +
            "  else\n" +
            "    print('DEAL_ERROR_CODE: ' .. tostring(err))\n" +
            "  end\n" +
            "end)\n" +
            "if not __ok then os.exit(1) end\n";
    }

    // =========================================================================
    // Results & coverage
    // =========================================================================

    private static void record(TestFile test, State state, String message) {
        switch (state) {
            case PASS -> passed++;
            case FAIL -> failed++;
            case SKIP -> skipped++;
            case KNOWN_FAIL -> knownFailures++;
            case STAGED_FAIL -> stagedFailures++;
            case COMPANION -> companions++;
        }
        int[] stats = phaseStats.computeIfAbsent(test.phase(), k -> new int[5]);
        switch (state) {
            case PASS -> stats[0]++;
            case FAIL -> stats[1]++;
            case SKIP -> stats[2]++;
            case KNOWN_FAIL -> stats[3]++;
            case STAGED_FAIL -> stats[4]++;
            case COMPANION -> {}
        }
        if (state == State.COMPANION) {
            return; // support modules carry no spec coverage of their own
        }
        String specKey = test.spec().isEmpty() ? "(no @spec)" : test.spec();
        specGroups.computeIfAbsent(specKey, k -> new ArrayList<>())
                  .add(new TestResult(test, state, message));
    }

    private static void printSummary() {
        System.out.println();
        System.out.println("=== Conformance Summary ===");
        int total = passed + failed + skipped;
        System.out.println("Total: " + total + ", Passed: " + passed +
            ", Failed: " + failed + ", Skipped: " + skipped +
            ", KnownFailures (tracked): " + knownFailures +
            ", StagedFailures (tracked): " + stagedFailures);
        System.out.println("Companions (classified support modules): "
            + companions);
        System.out.println("Profile-authority accounting: "
            + legacyAuthorityResults
            + " legacy-authority result(s) (LEGACY_REGRESSION + "
            + "LEGACY_SAFE_INT — zero v1.2/promotion credit; "
            + legacyAuthorityPassed + " passed, "
            + legacyAuthorityFailed + " failed), "
            + v12CreditResults
            + " v1.2-credit result(s) (COMMON_SHADOW + DEAL_V1_2_INT32)");

        if (!classificationFailures.isEmpty()) {
            System.out.println();
            System.out.println("Classification failures (unclassified skips "
                + "are removed by the v1.2 gate):");
            for (String message : classificationFailures) {
                System.out.println("  " + message);
            }
        }

        System.out.println();
        System.out.println("=== DEAL v1.2 Promotion Gate ===");
        printPhaseGate("frontend",
            "Frontend conformance (v1.2 grammar and semantics)");
        printPhaseGate("backend-runtime",
            "LuaJIT backend-runtime conformance (v1.2)");
        System.out.println("  JVM backend-runtime conformance (v1.2): enforced by "
            + "BackendConformanceTest (test/conformance/fixtures/*.json, "
            + "backends=[\"jvm\"]) and JvmBackendTest in run_tests.sh — both "
            + "must pass with zero unclassified skips");
        if (knownFailsByIssue.isEmpty()) {
            System.out.println("  Tracked v1.2 follow-up issues: none — full "
                + "v1.2 conformance");
        } else {
            System.out.println("  Tracked v1.2 follow-up issues (intentionally "
                + "unsupported cases, each with an explicit failing fixture):");
            knownFailsByIssue.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.println("    " + e.getKey() + ": "
                    + e.getValue() + " known-fail fixture(s)"));
        }
        if (!stagedFailuresByIssue.isEmpty()) {
            System.out.println("  Tracked v1.2 staged failures "
                + "(design-sanctioned interim states whose disposition is "
                + "owed by the named child):");
            stagedFailuresByIssue.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.println("    " + e.getKey() + ": "
                    + e.getValue() + " staged fixture(s)"));
        }
    }

    private static void printPhaseGate(String phase, String label) {
        int[] stats = phaseStats.getOrDefault(phase, new int[5]);
        int total = stats[0] + stats[1] + stats[2] + stats[3] + stats[4];
        System.out.println("  " + label + ": " + stats[0] + "/" + total
            + " passed, " + stats[1] + " failed, " + stats[2] + " skipped, "
            + stats[3] + " known-fail (tracked), " + stats[4]
            + " staged-fail (tracked)");
    }

    private static void printCoverageReport() {
        System.out.println();
        System.out.println("=== Spec Coverage Report (v1.2) ===");
        System.out.println();

        List<String> specSections = List.of(
            "Lexical elements",
            "Syntactic grammar",
            "Type system",
            "Classes",
            "Functions",
            "Variables",
            "Tables",
            "Arrays",
            "Bytes",
            "Control flow",
            "Error handling",
            "Async/Await",
            "Modules, declarations, standard library, and host ABI",
            "C FFI declaration files",
            "Runtime execution model",
            "Standard library declarations",
            "Test suite basis",
            "Diagnostics"
        );

        for (String section : specSections) {
            List<TestResult> sectionResults = new ArrayList<>();
            for (var entry : specGroups.entrySet()) {
                if (entry.getKey().startsWith(section)) {
                    sectionResults.addAll(entry.getValue());
                }
            }

            if (sectionResults.isEmpty()) {
                System.out.printf("  %-55s %s%n",
                    "\u00a7" + section, "UNCOVERED (0 tests)");
            } else {
                long sectionPassed = sectionResults.stream()
                    .filter(r -> r.state() == State.PASS).count();
                long sectionFailed = sectionResults.stream()
                    .filter(r -> r.state() == State.FAIL).count();
                long sectionKnown = sectionResults.stream()
                    .filter(r -> r.state() == State.KNOWN_FAIL).count();
                long sectionStaged = sectionResults.stream()
                    .filter(r -> r.state() == State.STAGED_FAIL).count();
                long sectionTotal = sectionResults.size();
                System.out.printf("  %-55s %d/%d passed, %d failed, "
                    + "%d known-fail, %d staged-fail%n",
                    "\u00a7" + section, sectionPassed, sectionTotal,
                    sectionFailed, sectionKnown, sectionStaged);
            }
        }
    }

    // =========================================================================
    // Module resolver for conformance tests
    // =========================================================================

    /**
     * Compile-and-cache service for companion {@code .deal} files, shared by
     * code generation ({@code generateLuaWithCompanions}) and type resolution
     * ({@code ConformanceModuleResolver.resolveClassSymbol} /
     * {@code resolveTypeNodeInModule}).
     *
     * <p>Per-file artifacts hold the generated Lua source, the import
     * resolutions (import path → stem module name), the compiled symbol
     * table and name resolver, and the set of direct companion
     * dependencies. Companions are compiled transitively, depth-first,
     * with a per-file cache keyed by absolute path and an in-progress set
     * that guards cycles (an in-progress file is skipped — its module is
     * already being emitted once).</p>
     *
     * <p>Module names are file stems in one flat temp-dir namespace; a
     * companion closure must not contain two same-stem files (the gap-02
     * fixture set satisfies this).</p>
     */
    private static final class CompanionCatalog {

        /** Compilation artifact for a single .deal file. */
        record Artifact(
            String luaSource,
            Map<String, String> importResolutions,
            SymbolTable symbolTable,
            NameResolver nameResolver,
            Set<Path> companionDependencies,
            List<CompilerDiagnostic> backendDiagnostics
        ) {}

        private final Map<Path, Artifact> cache = new LinkedHashMap<>();
        private final Map<Path, Artifact> entryCache = new LinkedHashMap<>();
        private final Set<Path> inProgress = new HashSet<>();
        /** The per-case project-wide profile (A5 seam): the catalogued
         * backend-runtime cases compile their companions under the
         * legacy regression profile; every other case under v1.2. */
        private final SemanticProfile profile;
        /** Shared host-fixture registry (host-module-abi D6). */
        private final HostRegistry hostRegistry;

        CompanionCatalog(SemanticProfile profile) {
            this.profile = java.util.Objects.requireNonNull(profile,
                "profile must not be null");
            this.hostRegistry = new HostRegistry(profile);
        }

        /**
         * The harness-wide canonical identity surface (emitter page D1):
         * one growing module-path classification keyed by dotted/absolute
         * module paths. {@code ""} classifies the intrinsic builtin Error
         * module; a host path classifies as a project module whose root
         * text is the dotted host path itself (keeping the pinned
         * @host.cfg/&lt;C&gt; identity byte-for-byte — the frozen cfg.lua
         * seam); any other compiled module path (an absolute .deal file
         * path) classifies as a project module under the fixed root text
         * {@code conformance} with the path's directory components plus
         * the file stem (two same-directory companion modules therefore
         * get distinct atoms — the modid-class-identity cross-module
         * mismatch fixture depends on it). Every compiled artifact
         * registers its path before backend construction, so every
         * Type.Class module path the emitter encodes is present
         * (companions compile depth-first before their importers).
         */
        private final Map<String, CanonicalModuleIdentity> moduleIdentities =
            new LinkedHashMap<>();
        {
            moduleIdentities.put("",
                CanonicalModuleIdentity.BuiltinModule.INSTANCE);
        }

        /** The descriptor service built over the current classification. */
        private CanonicalRuntimeTypeDescriptor descriptorService() {
            ModuleIdentityResolver.IdentityIndex index =
                ModuleIdentityResolver.buildIndex(moduleIdentities);
            return new CanonicalRuntimeTypeDescriptor(index,
                index.moduleIdentityLookup());
        }

        /** Classifies one module path for the harness identity surface. */
        private CanonicalModuleIdentity classifyModulePath(String modulePath) {
            if (modulePath == null || modulePath.isEmpty()) {
                return CanonicalModuleIdentity.BuiltinModule.INSTANCE;
            }
            if (hostRegistry.isHostModule(modulePath)) {
                String dotted = modulePath.replace('/', '.');
                return new CanonicalModuleIdentity.ProjectModule(
                    new ProjectModuleIdentity(dotted, dotted, List.of()));
            }
            Path p = Path.of(modulePath).toAbsolutePath().normalize();
            // Classify corpus-relative when the path lives inside the
            // conformance root: the atom text is then machine-independent
            // (@conformance/backend-runtime/<dirs>/<file stem>/<C>),
            // matching the sidecar-pinned identity text. Out-of-root paths
            // keep their absolute components (still consistent within the
            // run).
            Path rel;
            try {
                rel = conformanceRoot.relativize(p);
            } catch (IllegalArgumentException e) {
                rel = p;
            }
            if (rel.startsWith("..")) {
                rel = p;
            }
            List<String> components = new ArrayList<>();
            Path parent = rel.getParent();
            if (parent != null) {
                for (Path part : parent) {
                    String c = part.toString();
                    if (!c.isEmpty() && !c.equals("/")) {
                        components.add(c);
                    }
                }
            }
            Path fileName = rel.getFileName();
            if (fileName != null && !fileName.toString().isEmpty()) {
                components.add(fileName.toString());
            }
            String anchor = parent != null
                ? parent.toAbsolutePath().normalize().toString()
                : modulePath;
            return new CanonicalModuleIdentity.ProjectModule(
                new ProjectModuleIdentity("conformance", anchor, components));
        }

        /** Registers a module path (and its host paths) before backend use. */
        private void registerModulePath(String modulePath,
                Map<String, Map<String, Type>> hostModules) {
            if (modulePath != null && !modulePath.isEmpty()
                    && !moduleIdentities.containsKey(modulePath)) {
                moduleIdentities.put(modulePath, classifyModulePath(modulePath));
            }
            for (String raw : hostModules.keySet()) {
                String dotted = raw.replace('/', '.');
                if (!moduleIdentities.containsKey(dotted)) {
                    moduleIdentities.put(dotted, classifyModulePath(dotted));
                }
            }
        }

        /**
         * Returns the compiled artifact for a file, compiling it (and all
         * of its transitive companion dependencies) on first use.
         * Returns {@code null} when the file cannot be read, fails any
         * compilation stage, or is currently in progress (cycle) — the
         * same tolerant contract production resolution has.
         */
        Artifact artifactFor(Path file) {
            return artifactFor(file, false);
        }

        /**
         * Compiles the main test file as the v1.2 entry module (the
         * backend validates the exported non-async main(): null and emits
         * the main() invocation). The entry artifact is cached separately
         * from companion artifacts — the same file never mixes both roles
         * in one test run.
         */
        Artifact entryArtifactFor(Path file) {
            return artifactFor(file, true);
        }

        private Artifact artifactFor(Path file, boolean isEntry) {
            Path key = file.toAbsolutePath().normalize();
            Map<Path, Artifact> targetCache = isEntry ? entryCache : cache;
            Artifact cached = targetCache.get(key);
            if (cached != null) return cached;
            if (inProgress.contains(key)) return null;

            inProgress.add(key);
            try {
                Artifact artifact = compile(key, isEntry);
                if (artifact != null) {
                    targetCache.put(key, artifact);
                }
                return artifact;
            } finally {
                inProgress.remove(key);
            }
        }

        private Artifact compile(Path file, boolean isEntry) {
            try {
                String source = Files.readString(file);
                String filename = file.toString();

                LexResult lex = new Lexer(source, filename).tokenize();
                if (lex.hasErrors()) return null;

                Parser parser = new Parser(lex.tokens(), filename, profile);
                ParseResult parseResult = parser.parse();
                if (parseResult.hasErrors()) return null;

                // Discover this file's own companion imports. Stdlib paths
                // (non-./ and non-../) are not companions: resolveCompanionPath
                // returns null and they pass through raw as slash-separated
                // require names.
                Path fileDir = file.toAbsolutePath().normalize().getParent();
                Map<String, String> importResolutions = new LinkedHashMap<>();
                Set<Path> companionDependencies = new LinkedHashSet<>();
                Map<String, Map<String, Type>> hostModules = new LinkedHashMap<>();

                for (StatementNode stmt : parseResult.program().statements()) {
                    if (stmt instanceof ImportDeclaration imp) {
                        String importPath = imp.modulePath();
                        if (hostRegistry.isHostModule(importPath)) {
                            // Host fixture import (host/<name>): the
                            // declared exports drive the emitted
                            // __rt.load_host loader with the raw
                            // slash-form specifier verbatim; the import
                            // is never a companion and never enters
                            // importResolutions (host-module-abi D6).
                            try {
                                hostModules.put(importPath, hostRegistry
                                    .forModule(importPath).exports());
                            } catch (ModuleResolver.ModuleNotFoundException e) {
                                return null;
                            }
                            continue;
                        }
                        Path resolvedPath = resolveCompanionPath(importPath, fileDir);
                        if (resolvedPath != null) {
                            importResolutions.put(importPath,
                                moduleNameFor(resolvedPath));
                            companionDependencies.add(
                                resolvedPath.toAbsolutePath().normalize());
                        }
                    }
                }

                // Compile each companion dependency transitively (depth-first).
                for (Path dep : new ArrayList<>(companionDependencies)) {
                    artifactFor(dep);
                }

                // Name resolution rooted at this file.
                ConformanceModuleResolver resolver =
                    new ConformanceModuleResolver(file, this, profile);
                NameResolver nr = new NameResolver(filename, resolver);
                SymbolTable symTable = nr.resolve(parseResult.program());
                if (nr.diagnostics().stream().anyMatch(
                        d -> "error".equals(d.severity()))) {
                    return null;
                }

                CheckResult result = TypeChecker.check(
                    filename, symTable, nr, parseResult.program());
                if (result.hasErrors()) return null;

                // Canonical descriptor surface (emitter page D1): register
                // this module's path (and its host module paths) in the
                // catalog classification, then construct the backend over
                // the per-compilation descriptor service so every emitted
                // descriptor resolves through the identity index.
                registerModulePath(filename, hostModules);
                LuaBackend backend = new LuaBackend(
                    result.typeMap(), result.symbolTable(), filename,
                    filename, descriptorService(), profile);
                String luaSource = backend.generateFromInstance(
                    parseResult.program(), isEntry, importResolutions,
                    hostModules);
                return new Artifact(luaSource,
                    Collections.unmodifiableMap(new LinkedHashMap<>(importResolutions)),
                    symTable, nr,
                    Collections.unmodifiableSet(new LinkedHashSet<>(companionDependencies)),
                    backend.diagnostics());
            } catch (Exception e) {
                return null; // mirrors today's null degradation paths
            }
        }
    }

    /**
     * Shared registry of host fixture declarations (host-module-abi D6).
     *
     * <p>A bare import {@code host/<name>} resolves from
     * {@code host-fixtures/<name>.d.deal}; the typing/class-identity module
     * path is the dotted form {@code host.<name>} (class descriptors
     * {@code @host.presence/Config}), while the require path stays the raw
     * slash-form specifier verbatim. Every class declaration in the
     * fixture program (including {@code ExportDeclaration}-wrapped ones)
     * becomes a synthesized {@link Symbol.ClassSymbol} carrying name,
     * fields, and the dotted module path.</p>
     */
    private static final class HostRegistry {

        /** Declared exports plus the synthesized class symbols of one fixture. */
        record HostDeclaration(
            Map<String, Type> exports,
            Map<String, Symbol.ClassSymbol> classSymbols
        ) {}

        private final Map<String, HostDeclaration> cache = new LinkedHashMap<>();
        private final SemanticProfile profile;

        HostRegistry(SemanticProfile profile) {
            this.profile = java.util.Objects.requireNonNull(profile,
                "profile must not be null");
        }

        /**
         * True when {@code modulePath} names a registered host fixture —
         * the raw slash-form specifier ({@code host/<name>}) or its dotted
         * typing form ({@code host.<name>}) — and the fixture declaration
         * file exists on disk.
         */
        boolean isHostModule(String modulePath) {
            if (modulePath == null || modulePath.isEmpty()) return false;
            Path decl = declarationFileFor(toRawPath(modulePath));
            return decl != null && Files.exists(decl);
        }

        /**
         * Returns the declaration for a host fixture, parsing and caching
         * it on first use.
         *
         * @throws ModuleResolver.ModuleNotFoundException when the fixture
         *         declaration is missing or fails to lex/parse
         */
        HostDeclaration forModule(String modulePath)
                throws ModuleResolver.ModuleNotFoundException {
            String raw = toRawPath(modulePath);
            HostDeclaration cached = cache.get(raw);
            if (cached != null) return cached;

            Path decl = declarationFileFor(raw);
            if (decl == null || !Files.exists(decl)) {
                throw new ModuleResolver.ModuleNotFoundException(
                    "Module not found: " + modulePath);
            }
            try {
                String source = Files.readString(decl);
                String filename = decl.toString();

                LexResult lex = new Lexer(source, filename).tokenize();
                if (lex.hasErrors())
                    throw new ModuleResolver.ModuleNotFoundException(
                        "Lex errors in " + filename);

                Parser parser = new Parser(lex.tokens(), filename, profile);
                ParseResult parseResult = parser.parse();
                if (parseResult.hasErrors())
                    throw new ModuleResolver.ModuleNotFoundException(
                        "Parse errors in " + filename);

                // The typing/class-identity module path is the dotted form
                // (host-module-abi D6): class types and descriptors carry
                // it; the require path stays the raw slash-form specifier.
                String dotted = raw.replace('/', '.');
                ExportExtractor extractor = new ExportExtractor(dotted, true);
                Map<String, Type> exports =
                    extractor.extract(parseResult.program());

                // Class-symbol synthesis: every ClassDeclaration in the
                // fixture program (incl. ExportDeclaration-wrapped ones)
                // becomes a ClassSymbol carrying the dotted module path.
                Map<String, Symbol.ClassSymbol> classSymbols = new LinkedHashMap<>();
                for (StatementNode stmt : parseResult.program().statements()) {
                    if (stmt instanceof ClassDeclaration cd) {
                        classSymbols.put(cd.name(),
                            new Symbol.ClassSymbol(cd.name(), cd.fields(), dotted));
                    } else if (stmt instanceof ExportDeclaration exp
                            && exp.declaration() instanceof ClassDeclaration cd) {
                        classSymbols.put(cd.name(),
                            new Symbol.ClassSymbol(cd.name(), cd.fields(), dotted));
                    }
                }

                HostDeclaration result = new HostDeclaration(
                    Collections.unmodifiableMap(new LinkedHashMap<>(exports)),
                    Collections.unmodifiableMap(new LinkedHashMap<>(classSymbols)));
                cache.put(raw, result);
                return result;
            } catch (IOException e) {
                throw new ModuleResolver.ModuleNotFoundException(
                    "Cannot read: " + decl);
            }
        }

        /**
         * Normalizes a host module path to the raw slash-form specifier:
         * {@code host.presence} → {@code host/presence}; an already raw
         * {@code host/presence} is unchanged.
         */
        private String toRawPath(String modulePath) {
            return modulePath.replace('.', '/');
        }

        /**
         * Maps a raw {@code host/<name>} specifier to its fixture
         * declaration file, or {@code null} when the specifier is not a
         * single-segment host path.
         */
        private Path declarationFileFor(String rawPath) {
            String prefix = "host/";
            if (!rawPath.startsWith(prefix) || rawPath.length() == prefix.length()) {
                return null;
            }
            String name = rawPath.substring(prefix.length());
            if (name.contains("/") || name.contains("\\")) {
                return null;
            }
            return hostFixturesRoot.resolve(name + ".d.deal");
        }
    }

    /**
     * A module resolver that resolves stdlib modules by parsing the actual
     * .d.deal files via {@link StdlibModuleResolver}, and resolves relative
     * file imports using {@link ExportExtractor}.
     *
     * <p>Stdlib exports are derived from the 6 spec-listed .d.deal files
     * — there is no hardcoded export map. Non-spec modules (std/io,
     * std/coroutine) are not resolved.
     */
    private static class ConformanceModuleResolver implements ModuleResolver {

        private final Path testFileDir;
        private final CompanionCatalog catalog;
        private final Map<String, Map<String, Type>> stdlibExports;
        private final HostRegistry hostRegistry;
        private final SemanticProfile profile;

        ConformanceModuleResolver(Path testFile, CompanionCatalog catalog,
                SemanticProfile profile) {
            this.testFileDir = testFile.toAbsolutePath().getParent();
            this.catalog = catalog;
            this.profile = java.util.Objects.requireNonNull(profile,
                "profile must not be null");
            this.stdlibExports = StdlibModuleResolver.stdlibExports();
            this.hostRegistry = catalog != null
                ? catalog.hostRegistry
                : new HostRegistry(profile);
        }

        @Override
        public Map<String, Type> resolveModule(String modulePath,
                String importingModule, Set<String> modulesInProgress)
                throws ModuleNotFoundException {
            // Check spec-listed stdlib modules
            if (stdlibExports.containsKey(modulePath)) {
                return stdlibExports.get(modulePath);
            }

            // Reject non-spec stdlib paths (std/io, std/coroutine)
            if (modulePath.startsWith("std/") && !modulePath.startsWith("./")
                    && !modulePath.startsWith("../")) {
                throw new ModuleNotFoundException(
                    "Module not found: '" + modulePath
                    + "' is not a spec-listed stdlib module");
            }

            // Host-fixture registry (host-module-abi D6): a bare
            // host/<name> import resolves from
            // host-fixtures/<name>.d.deal; the typing/class-identity
            // module path is the dotted host.<name> form, while the
            // require path stays the raw slash-form specifier. The
            // dotted form also reaches here (isFunctionExportedFromModule
            // passes a class's module path).
            if (hostRegistry.isHostModule(modulePath)) {
                return hostRegistry.forModule(modulePath).exports();
            }

            // Try relative file import
            Path resolved = resolveRelativePath(modulePath);
            if (resolved != null && Files.exists(resolved)) {
                return resolveFileModule(resolved, modulesInProgress);
            }

            throw new ModuleNotFoundException("Module not found: " + modulePath);
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className,
                String modulePath, String importingModule)
                throws ModuleNotFoundException {
            // Host-fixture branch (host-module-abi D6): classes of a
            // host.<name> module resolve to the ClassSymbols synthesized
            // from the fixture declaration; an absent class returns null,
            // producing the usual E3004/E4002 diagnostics.
            if (hostRegistry.isHostModule(modulePath)) {
                HostRegistry.HostDeclaration decl =
                    hostRegistry.forModule(modulePath);
                return decl.classSymbols().get(className);
            }
            // Local classes (absent modulePath) resolve through the local
            // scope in NameResolver; only foreign module paths reach here.
            if (catalog == null || modulePath == null || modulePath.isEmpty()) {
                return null;
            }
            CompanionCatalog.Artifact artifact = catalog.artifactFor(
                Path.of(modulePath).toAbsolutePath().normalize());
            if (artifact == null || artifact.symbolTable() == null) {
                return null; // file missing / compile failure / in-progress cycle
            }
            Symbol sym = artifact.symbolTable().resolve(className);
            if (sym instanceof Symbol.ClassSymbol cs) return cs;
            return null;
        }

        @Override
        public Type resolveTypeNodeInModule(TypeNode typeNode,
                String modulePath, String importingModule)
                throws ModuleNotFoundException {
            // Host-fixture branch (host-module-abi D6): field annotations
            // of a host.<name> class resolve against the fixture's own
            // class registry (NamedType/ArrayType/NullableType shapes);
            // any other shape degrades through the local fallback below.
            if (hostRegistry.isHostModule(modulePath)) {
                HostRegistry.HostDeclaration decl =
                    hostRegistry.forModule(modulePath);
                return resolveHostTypeNode(typeNode, modulePath, decl);
            }
            if (catalog == null || modulePath == null || modulePath.isEmpty()) {
                return null;
            }
            CompanionCatalog.Artifact artifact = catalog.artifactFor(
                Path.of(modulePath).toAbsolutePath().normalize());
            if (artifact == null || artifact.nameResolver() == null) {
                return null;
            }
            return artifact.nameResolver().resolveTypeNode(typeNode);
        }

        /**
         * Resolves a field type annotation of a host-fixture class against
         * the fixture's own class registry (host-module-abi D6): NamedType
         * nodes naming registered host classes resolve to
         * {@code Types.classType(name, modulePath)}, and ArrayType/
         * NullableType wrappers are rebuilt around resolved inners. Any
         * other shape (primitives, qualified types, function types)
         * returns null so the caller falls back to its local resolution.
         */
        private Type resolveHostTypeNode(TypeNode typeNode, String modulePath,
                HostRegistry.HostDeclaration decl) {
            if (typeNode instanceof NamedType nt) {
                if (decl.classSymbols().containsKey(nt.name())) {
                    return Types.classType(nt.name(), modulePath);
                }
                return null;
            }
            if (typeNode instanceof deal.ast.ArrayType at) {
                Type elem = resolveHostTypeNode(at.elementType(), modulePath, decl);
                return elem == null ? null : Types.array(elem);
            }
            if (typeNode instanceof deal.ast.NullableType nullable) {
                Type inner = resolveHostTypeNode(
                    nullable.innerType(), modulePath, decl);
                if (inner == null) return null;
                try {
                    return Types.nullable(inner);
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
            return null;
        }

        // ---- relative file imports ----

        private Path resolveRelativePath(String importPath) {
            if (!importPath.startsWith("./") && !importPath.startsWith("../")) {
                return null;
            }
            Path resolved = testFileDir.resolve(importPath).normalize();
            if (Files.exists(resolved)) return resolved;
            Path withExt = testFileDir.resolve(importPath + ".deal").normalize();
            if (Files.exists(withExt)) return withExt;
            Path withDeclExt = testFileDir.resolve(importPath + ".d.deal").normalize();
            if (Files.exists(withDeclExt)) return withDeclExt;
            return null;
        }

        private Map<String, Type> resolveFileModule(Path file,
                Set<String> modulesInProgress) throws ModuleNotFoundException {
            try {
                String source = Files.readString(file);
                String filename = file.toString();
                boolean isDecl = filename.endsWith(".d.deal");

                LexResult lex = new Lexer(source, filename).tokenize();
                if (lex.hasErrors())
                    throw new ModuleNotFoundException("Lex errors in " + filename);

                Parser parser = new Parser(lex.tokens(), filename, profile);
                ParseResult parseResult = parser.parse();
                if (parseResult.hasErrors())
                    throw new ModuleNotFoundException("Parse errors in " + filename);

                ExportExtractor extractor = new ExportExtractor(filename, isDecl);
                return extractor.extract(parseResult.program());
            } catch (IOException e) {
                throw new ModuleNotFoundException("Cannot read: " + file);
            }
        }
    }
}

// =========================================================================
// LegacyProfileRegressionCatalog — the closed legacy regression authority (A4)
// =========================================================================

/**
 * The closed, release-owned legacy-profile regression authority (A4):
 * one row per safe-int-era assertion locator with purpose
 * {@code LEGACY_REGRESSION}, profile {@code LEGACY_SAFE_INT}, authority
 * {@code legacy-regression}, credit {@code none}, and the mandatory
 * additive v1.2 replacement locator (absent only for the excluded
 * families — the time lock and the JS v1.2 exclusion). The three
 * conformance runners consume the catalog per case (A5): a catalogued
 * case resolves the legacy regression invocation with its source and
 * expectations unchanged, every other case and every frontend suite
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
 * <p>Production code never depends on this class: it is authored within
 * the gate-run conformance test files (the gate compile/runner lists stay
 * unchanged) and is test-harness data only.</p>
 */
final class LegacyProfileRegressionCatalog {

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
     * replacement this epic: {@code jvm-std-time-nowmillis} and
     * {@code time-now-millis-positive.deal} (time lock — the nowMillis
     * resolution owns the replacement), {@code js-int-safe-range-e8004},
     * {@code js-bytes-length-above-int32-e8012}, and
     * {@code js-stdlib-time-structural} (JS v1.2 excluded —
     * {@code js-v12-int32-bytes} owns the JS replacements).
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
        // JS retained lane (profile-agnostic; JS v1.2 excluded)
        row("js-skeleton.json#js-int-safe-range-e8004", null),
        row("js-skeleton.json#js-bytes-length-above-int32-e8012", null),
        row("js-skeleton.json#js-stdlib-time-structural", null),
        // backend-runtime .deal population
        row("backend-runtime/runtime/int-convert-range.deal",
            "jvm-int32-slice.json#int32-convert-range"),
        row("backend-runtime/stdlib-edge/time-now-millis-positive.deal", null),
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
     */
    static CompilerInvocation invocationFor(String locator) {
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
    static SemanticProfile profileFor(String locator) {
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
        ParseResult parse = new Parser(lex.tokens(), filename, profile).parse();
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
        if (!isCatalogued(time)) {
            violation("self-probe: the " + time + " catalog row is "
                + "missing (completeness rule: its exit-0 expectation "
                + "depends on the legacy range)");
        }
        if (isCatalogued(time + "#removed")) {
            violation("self-probe: an unknown locator must not be "
                + "catalogued");
        }
        CompilerInvocation timeInvocation = invocationFor(time);
        if (timeInvocation.purpose() != deal.semantic.ir.InvocationPurpose.LEGACY_REGRESSION
                || timeInvocation.semanticProfile()
                    != SemanticProfile.LEGACY_SAFE_INT) {
            violation("self-probe: the catalogued time fixture must "
                + "resolve LEGACY_REGRESSION + LEGACY_SAFE_INT, got "
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
