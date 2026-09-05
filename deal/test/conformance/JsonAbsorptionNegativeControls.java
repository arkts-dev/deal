package deal.test.conformance;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dev-time oracle-negative control battery for the ISSUE-0360 JSON-slice
 * absorption (Verification 4 of {@code v12-three-backend-conformance-corpus}):
 * every absorbed destination fixture passes an oracle-negative control — a
 * plausible perturbation of the claimed value/order/identity/async result
 * fails the fixture on the differential gate.
 *
 * <p>Controls, all on scratch copies (never the committed corpus):</p>
 * <ul>
 *   <li><b>runtime destinations</b> — the fixture's in-program
 *       Runtime Conformance Assertion guard is inverted (the first
 *       {@code !==}/{@code ===} comparison flips, so the claimed value no
 *       longer matches and the guard throws {@code TEST_FAIL}); a fixture
 *       whose oracle carries no comparison gets an injected unconditional
 *       {@code TEST_FAIL} throw at the top of {@code main}. The perturbed
 *       fixture must fail on all three production lanes and the gate must
 *       name the fixture.</li>
 *   <li><b>frontend destinations</b> — the fixture's pinned
 *       {@code @expected: compile-error CODE} is perturbed to
 *       {@code E9999} on the scratch copy; the backend-neutral frontend
 *       comparison must fail naming the fixture.</li>
 * </ul>
 *
 * <p>The class is compile-listed in {@code run_tests.sh} but deliberately
 * not added to the run phase (dev-time evidence battery; its committed log
 * lives at {@code test/conformance/json-absorption/negative-control-log.txt}).</p>
 */
public final class JsonAbsorptionNegativeControls {

    private JsonAbsorptionNegativeControls() {
        // Static utility; no instances.
    }

    /** The absorbed runtime destinations (ISSUE-0360 migration record,
     * runtime-destination table). */
    private static final Set<String> RUNTIME_DESTINATIONS = Set.of(
        "backend-runtime/arrays/array-alias-mutation.deal",
        "backend-runtime/arrays/index-write.deal",
        "backend-runtime/async-await/async-await-statement.deal",
        "backend-runtime/async-await/async-error-propagation.deal",
        "backend-runtime/async-await/async-fn-expr.deal",
        "backend-runtime/async-await/async-function-value.deal",
        "backend-runtime/async-await/async-multiple-awaits.deal",
        "backend-runtime/async-await/async-nested.deal",
        "backend-runtime/async-await/async-nullable-return.deal",
        "backend-runtime/async-await/async-simple-await.deal",
        "backend-runtime/async-await/async-sync-complete.deal",
        "backend-runtime/async-await/async-throw-catch.deal",
        "backend-runtime/async-await/async-with-params.deal",
        "backend-runtime/async-await/await-completion-check.deal",
        "backend-runtime/async-await/await-in-call-arg.deal",
        "backend-runtime/async-await/await-in-if-condition.deal",
        "backend-runtime/async-await/nested-await-arguments.deal",
        "backend-runtime/class-runtime-errors/dynamic-null-to-nullable-class.deal",
        "backend-runtime/classes/array-default-fresh-per-instance.deal",
        "backend-runtime/classes/construction.deal",
        "backend-runtime/classes/optional-nullable-three-states.deal",
        "backend-runtime/classes/table-default-fresh-per-instance.deal",
        "backend-runtime/control-flow/for-of-array.deal",
        "backend-runtime/control-flow/for-of-unicode-scalar.deal",
        "backend-runtime/control-flow/if-else.deal",
        "backend-runtime/control-flow/while.deal",
        "backend-runtime/descriptors/canonical-array-boundary.deal",
        "backend-runtime/descriptors/canonical-async-marker-boundary.deal",
        "backend-runtime/descriptors/canonical-class-atom-error-roundtrip.deal",
        "backend-runtime/descriptors/canonical-nullable-boundary.deal",
        "backend-runtime/error-handling/error-roundtrip.deal",
        "backend-runtime/error-handling/try-catch.deal",
        "backend-runtime/functions/arity-extension.deal",
        "backend-runtime/functions/direct-recursion.deal",
        "backend-runtime/functions/for-loop-closure-array.deal",
        "backend-runtime/functions/intrinsic-arity-extension.deal",
        "backend-runtime/functions/intrinsic-as-callback.deal",
        "backend-runtime/functions/intrinsic-as-function-value.deal",
        "backend-runtime/functions/recursive-function-value.deal",
        "backend-runtime/host-abi/host-async-ok.deal",
        "backend-runtime/host-abi/host-extra-export-ignored.deal",
        "backend-runtime/host-abi/host-null-return-ok.deal",
        "backend-runtime/host-abi/host-nullable-return-ok.deal",
        "backend-runtime/jsonable/jsonable-complex-roundtrip.deal",
        "backend-runtime/jsonable/jsonable-cross-module.deal",
        "backend-runtime/jsonable/jsonable-fromjson-extra-keys.deal",
        "backend-runtime/jsonable/jsonable-fromjson-nested-depth3.deal",
        "backend-runtime/jsonable/jsonable-fromjson-null.deal",
        "backend-runtime/jsonable/jsonable-malformed-input.deal",
        "backend-runtime/jsonable/jsonable-nested-array-malformed-element.deal",
        "backend-runtime/jsonable/jsonable-nested.deal",
        "backend-runtime/jsonable/jsonable-optional-nullable.deal",
        "backend-runtime/jsonable/jsonable-roundtrip.deal",
        "backend-runtime/jsonable/jsonable-tojson-omits-missing.deal",
        "backend-runtime/jsonable/optional-nullable-three-state-roundtrip.deal",
        "backend-runtime/jsonable/table-field-roundtrip.deal",
        "backend-runtime/modules/cross-module-class-factory.deal",
        "backend-runtime/modules/import-alias-member-access.deal",
        "backend-runtime/modules/modid-class-identity-control.deal",
        "backend-runtime/runtime/int-convert.deal",
        "backend-runtime/runtime/number-convert.deal",
        "backend-runtime/stdlib/math/number-helpers.deal",
        "backend-runtime/stdlib/string/length-unicode.deal",
        "backend-runtime/stdlib/string/search-helpers.deal",
        "backend-runtime/stdlib/string/substring-unicode.deal",
        "backend-runtime/stdlib/string/transform-helpers.deal",
        "backend-runtime/stdlib/table/keys-string-inclusion.deal",
        "backend-runtime/tables/null-field-delete-read.deal",
        "backend-runtime/templates/template-basic.deal",
        "backend-runtime/templates/template-multi.deal",
        "backend-runtime/templates/template-plain.deal",
        "backend-runtime/type-system/dynamic-null-to-nullable.deal",
        "backend-runtime/types/nullable-narrowing.deal");

    /** The absorbed frontend destinations (ISSUE-0360 migration record,
     * frontend-destination table). */
    private static final Set<String> FRONTEND_DESTINATIONS = Set.of(
        "frontend/arrays/length-assignment.deal",
        "frontend/async-await/async-call-without-await.deal",
        "frontend/async-await/await-non-async-call.deal",
        "frontend/async-await/await-outside-async.deal",
        "frontend/classes/nominal-mismatch.deal",
        "frontend/control-flow/if-condition-non-boolean.deal",
        "frontend/diagnostics/return-mismatch.deal",
        "frontend/functions/reverse-arity.deal",
        "frontend/functions/wrong-arg-types.deal",
        "frontend/jsonable/jsonable-non-jsonable-field.deal",
        "frontend/modules/imported-parameter-type-mismatch.deal",
        "frontend/modules/missing-export.deal",
        "frontend/parser/top-level-expression-statement.deal",
        "frontend/tables/table-direct-call-rejected.deal",
        "frontend/templates/template-interpolation-type.deal",
        "frontend/type-system/function-parameter-variance-mismatch.deal",
        "frontend/types/invariance-int-number.deal",
        "frontend/types/nullable-no-narrow.deal");

    private static int passed = 0;
    private static int failed = 0;

    /** Runs the battery: one scratch gate run over all perturbed runtime
     * destinations plus one over all perturbed frontend destinations. */
    public static void main(String[] args) throws IOException {
        Path corpusRoot = args.length > 0
            ? Path.of(args[0])
            : Path.of("test", "conformance");
        int jobs = Integer.getInteger("deal.test.jobs",
            Runtime.getRuntime().availableProcessors());
        PrintStream out = new PrintStream(System.out, true,
            StandardCharsets.UTF_8);

        List<Path> scratchRoots = new ArrayList<>();
        try {
            Path runtimeRoot = Files.createTempDirectory(
                "json_absorption_neg_runtime_");
            scratchRoots.add(runtimeRoot);
            out.println("=== Runtime-destination negative controls ===");
            buildRuntimeScratch(corpusRoot, runtimeRoot, out);
            DifferentialGate.GateRun runtimeRun = DifferentialGate.run(
                runtimeRoot, lanes(runtimeRoot), jobs,
                Duration.ofSeconds(120), out);
            for (String fixture : RUNTIME_DESTINATIONS) {
                GateDispatcher.CaseVerdict verdict = verdictOf(
                    runtimeRun, fixture);
                boolean allFail = verdict != null
                    && verdict.outcomes().size() == 3
                    && verdict.outcomes().stream().noneMatch(
                        GateDispatcher.LaneOutcome::passed);
                check(allFail, fixture + " — the guard-inversion "
                    + "perturbation fails all three lanes: " + verdict);
            }

            Path frontendRoot = Files.createTempDirectory(
                "json_absorption_neg_frontend_");
            scratchRoots.add(frontendRoot);
            out.println();
            out.println("=== Frontend-destination negative controls ===");
            buildFrontendScratch(corpusRoot, frontendRoot, out);
            DifferentialGate.GateRun frontendRun = DifferentialGate.run(
                frontendRoot, Map.of(), jobs, Duration.ofSeconds(120), out);
            for (String fixture : FRONTEND_DESTINATIONS) {
                boolean failedNamed = frontendRun.failures().stream()
                    .anyMatch(f -> fixture.equals(f.subject())
                        && ("frontend-compile".equals(f.kind())
                            || "classification".equals(f.kind())
                            || "compile-diagnostic".equals(f.kind())));
                check(failedNamed, fixture + " — the perturbed @expected "
                    + "code fails the backend-neutral comparison naming "
                    + "the fixture");
            }
        } finally {
            for (Path root : scratchRoots) {
                deleteTree(root);
            }
        }

        out.println();
        out.println("Negative controls passed: " + passed
            + ", failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static Map<String, Lane> lanes(Path root) {
        return Map.of(
            "luajit", new LuaLane(root),
            "jvm", new JvmLane(root),
            "js", new JsLane(root));
    }

    /** Copies the target fixtures (perturbed), their sidecars, every
     * companion module, and the host-fixtures triplets into the scratch
     * root. */
    private static void buildRuntimeScratch(Path corpusRoot, Path root,
            PrintStream out) throws IOException {
        // All companions (classified support modules) of the runtime tree.
        List<Path> companions = new ArrayList<>();
        try (var stream = Files.walk(corpusRoot.resolve("backend-runtime"))) {
            for (Path p : stream.filter(f -> f.toString().endsWith(".deal"))
                    .toList()) {
                String text = Files.readString(p);
                if (text.contains("@expected: companion")) {
                    companions.add(p);
                }
            }
        }
        // Host-fixture triplets (declarations + per-backend hosts).
        List<Path> hostFiles = new ArrayList<>();
        try (var stream = Files.walk(corpusRoot.resolve("host-fixtures"))) {
            stream.filter(f -> !Files.isDirectory(f)).forEach(hostFiles::add);
        }
        for (String fixture : RUNTIME_DESTINATIONS) {
            Path source = corpusRoot.resolve(fixture);
            String perturbed = perturbRuntime(Files.readString(source),
                fixture);
            Path target = root.resolve(fixture);
            Files.createDirectories(target.getParent());
            Files.writeString(target, perturbed);
            Path sidecar = corpusRoot.resolve(fixture.substring(0,
                fixture.length() - ".deal".length()) + ".expect.json");
            if (Files.exists(sidecar)) {
                Files.copy(sidecar, root.resolve(fixture.substring(0,
                    fixture.length() - ".deal".length()) + ".expect.json"));
            }
        }
        for (Path companion : companions) {
            Path rel = corpusRoot.relativize(companion);
            Path target = root.resolve(rel);
            Files.createDirectories(target.getParent());
            Files.copy(companion, target);
            Path sidecar = corpusRoot.resolve(rel.toString().substring(0,
                rel.toString().length() - ".deal".length())
                + ".expect.json");
            if (Files.exists(sidecar)) {
                Files.copy(sidecar, root.resolve(
                    rel.toString().substring(0,
                        rel.toString().length() - ".deal".length())
                    + ".expect.json"));
            }
        }
        for (Path hostFile : hostFiles) {
            Path rel = corpusRoot.relativize(hostFile);
            Path target = root.resolve(rel);
            Files.createDirectories(target.getParent());
            Files.copy(hostFile, target);
        }
        out.println("Scratch runtime root: " + root + " ("
            + RUNTIME_DESTINATIONS.size() + " perturbed fixtures, "
            + companions.size() + " companions, "
            + hostFiles.size() + " host files)");
    }

    /** Inverts the fixture's oracle guard: flips the first comparison so
     * the claimed value no longer matches and the Runtime Conformance
     * Assertion throws TEST_FAIL; a fixture without any comparison gets
     * an unconditional TEST_FAIL throw at the top of main. */
    private static String perturbRuntime(String source, String fixture) {
        int neq = source.indexOf("!==");
        int eq = source.indexOf("===");
        if (neq >= 0 && (eq < 0 || neq < eq)) {
            return source.substring(0, neq) + "==="
                + source.substring(neq + 3);
        }
        if (eq >= 0) {
            return source.substring(0, eq) + "!=="
                + source.substring(eq + 3);
        }
        int mainIndex = source.indexOf("export function main()");
        if (mainIndex >= 0) {
            int brace = source.indexOf('{', mainIndex);
            return source.substring(0, brace + 1)
                + "\n  throw { code: \"TEST_FAIL\", message: "
                + "\"negative control\" };"
                + source.substring(brace + 1);
        }
        throw new IllegalStateException("cannot perturb " + fixture
            + ": no comparison and no main");
    }

    /** Copies the target frontend fixtures with a perturbed
     * {@code @expected} code into the scratch root. */
    private static void buildFrontendScratch(Path corpusRoot, Path root,
            PrintStream out) throws IOException {
        for (String fixture : FRONTEND_DESTINATIONS) {
            Path source = corpusRoot.resolve(fixture);
            String text = Files.readString(source);
            String perturbed = text.replaceFirst(
                "@expected: compile-error [A-Z0-9]+",
                "@expected: compile-error E9999");
            if (perturbed.equals(text)) {
                throw new IllegalStateException("cannot perturb @expected "
                    + "of " + fixture);
            }
            Path target = root.resolve(fixture);
            Files.createDirectories(target.getParent());
            Files.writeString(target, perturbed);
        }
        out.println("Scratch frontend root: " + root + " ("
            + FRONTEND_DESTINATIONS.size() + " perturbed fixtures)");
    }

    private static GateDispatcher.CaseVerdict verdictOf(
            DifferentialGate.GateRun run, String fixturePath) {
        for (GateDispatcher.CaseVerdict verdict : run.verdicts()) {
            if (fixturePath.equals(verdict.fixturePath())) {
                return verdict;
            }
        }
        return null;
    }

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.out.println("NEGATIVE-CONTROL FAIL: " + message);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path p : stream.sorted(Comparator.reverseOrder())
                    .toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
