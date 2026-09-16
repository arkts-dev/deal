package deal.test.conformance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Classification unit tests (ISSUE-0353 Verification): the classification
 * grammar over synthetic scratch fixtures, including missing and unknown
 * {@code @expected}, stale {@code @spec}, exact error codes, and sidecar
 * presence rules.
 */
public class GateClassificationTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Gate Classification Tests (ISSUE-0353) ===\n");

        grammarUnits();
        sidecarPresenceRules();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static final String HEADER_SPEC =
        "// @spec: Type system — Typing rules\n";
    private static final String HEADER_DESCRIPTION =
        "// @description: synthetic classification unit\n";

    private static String fixture(String specLine, String expectedLine,
            String issueLine) {
        return specLine + HEADER_DESCRIPTION + expectedLine + issueLine
            + "\nexport function main(): null { return null; }\n";
    }

    private static Path writeScratch(Path root, String corpusPath,
            String content) throws IOException {
        Path file = root.resolve(corpusPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static CorpusDiscovery.DiscoveryResult discover(Path root)
            throws IOException {
        return CorpusDiscovery.discover(root);
    }

    private static void expectClassificationFailure(Path root,
            String corpusPath, String fieldPart, String reasonPart,
            String message) throws IOException {
        CorpusDiscovery.DiscoveryResult result = discover(root);
        check(result.failures().size() == 1,
            message + ": exactly one classification failure, got "
                + result.failures().size());
        if (result.failures().size() == 1) {
            SidecarSchemaValidator.ClassificationFailure failure =
                result.failures().get(0);
            check(corpusPath.equals(failure.fixturePath()),
                message + ": the failure names the fixture, got "
                    + failure.fixturePath());
            check(failure.field().contains(fieldPart),
                message + ": the failure names the field " + fieldPart
                    + ", got " + failure.field());
            check(failure.reason().contains(reasonPart),
                message + ": the reason mentions \"" + reasonPart
                    + "\", got: " + failure.reason());
        }
        check(result.fixtures().stream()
                .filter(f -> f.corpusPath().equals(corpusPath))
                .findFirst().get().classification() == null,
            message + ": the failed fixture carries no classification");
    }

    private static void expectClassified(Path root, String corpusPath,
            CorpusDiscovery.Kind kind, String message) throws IOException {
        CorpusDiscovery.DiscoveryResult result = discover(root);
        check(result.failures().isEmpty(),
            message + ": zero classification failures, got "
                + result.failures());
        CorpusDiscovery.Fixture fixture = result.fixtures().stream()
            .filter(f -> f.corpusPath().equals(corpusPath))
            .findFirst().orElseThrow();
        check(fixture.classification() != null
                && fixture.classification().kind() == kind,
            message + ": classified " + kind + ", got "
                + fixture.classification());
    }

    private static Path scratchRoot() throws IOException {
        return Files.createTempDirectory("gate_classification_");
    }

    private static void clean(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder())
                    .toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    // =========================================================================
    // Grammar units
    // =========================================================================

    private static void grammarUnits() throws IOException {
        {
            Path root = scratchRoot();
            writeScratch(root, "frontend/x.deal",
                fixture(HEADER_SPEC, "", ""));
            expectClassificationFailure(root, "frontend/x.deal", "@expected",
                "no @expected tag", "missing @expected");
            clean(root);
        }
        {
            Path root = scratchRoot();
            writeScratch(root, "frontend/x.deal",
                fixture(HEADER_SPEC, "// @expected: compile-maybe\n", ""));
            expectClassificationFailure(root, "frontend/x.deal", "@expected",
                "unknown @expected", "unknown @expected");
            clean(root);
        }
        {
            Path root = scratchRoot();
            writeScratch(root, "frontend/x.deal",
                fixture("// @spec: Not a v1.2 section — Typing rules\n",
                    "// @expected: compile-ok\n", ""));
            expectClassificationFailure(root, "frontend/x.deal", "@spec",
                "missing or stale @spec", "stale @spec heading");
            clean(root);
        }
        {
            Path root = scratchRoot();
            writeScratch(root, "frontend/x.deal",
                fixture(HEADER_SPEC, "// @expected: compile-error any\n", ""));
            expectClassificationFailure(root, "frontend/x.deal", "@expected",
                "must name one specific diagnostic code",
                "the 'any' code is not an exact diagnostic code");
            clean(root);
        }
        {
            Path root = scratchRoot();
            writeScratch(root, "frontend/x.deal",
                fixture(HEADER_SPEC, "// @expected: runtime-error \n", ""));
            expectClassificationFailure(root, "frontend/x.deal", "@expected",
                "unknown @expected",
                "an empty runtime-error code");
            clean(root);
        }
        {
            Path root = scratchRoot();
            writeScratch(root, "frontend/x.deal",
                fixture(HEADER_SPEC, "// @expected: host-fixture\n", ""));
            expectClassificationFailure(root, "frontend/x.deal", "@expected",
                "reserved for test/conformance/host-fixtures",
                "host-fixture outside the subtree");
            clean(root);
        }
        {
            Path root = scratchRoot();
            writeScratch(root, "backend-runtime/x.deal",
                fixture("", "// @expected: companion\n", ""));
            expectClassified(root, "backend-runtime/x.deal",
                CorpusDiscovery.Kind.COMPANION,
                "companion without @spec (the legacy companion rule)");
            clean(root);
        }
        {
            Path root = scratchRoot();
            writeScratch(root, "backend-runtime/x.deal",
                fixture(HEADER_SPEC, "// @expected: runtime-ok\n", ""));
            expectClassified(root, "backend-runtime/x.deal",
                CorpusDiscovery.Kind.RUNTIME_OK, "runtime-ok");
            clean(root);
        }
        {
            Path root = scratchRoot();
            writeScratch(root, "backend-runtime/x.deal",
                fixture(HEADER_SPEC, "// @expected: runtime-error E8001\n",
                    ""));
            expectClassified(root, "backend-runtime/x.deal",
                CorpusDiscovery.Kind.RUNTIME_ERROR, "runtime-error E8001");
            clean(root);
        }
        {
            Path root = scratchRoot();
            writeScratch(root, "host-fixtures/x.d.deal",
                fixture(HEADER_SPEC, "// @expected: runtime-ok\n", ""));
            CorpusDiscovery.DiscoveryResult result = discover(root);
            check(result.fixtures().isEmpty() && result.failures().isEmpty(),
                "the host-fixtures subtree is excluded from discovery");
            clean(root);
        }
    }

    // =========================================================================
    // Sidecar presence rules
    // =========================================================================

    private static void sidecarPresenceRules() throws IOException {
        // A runtime fixture without its sidecar is a classification failure.
        {
            Path root = scratchRoot();
            writeScratch(root, "backend-runtime/x.deal",
                fixture(HEADER_SPEC, "// @expected: runtime-ok\n", ""));
            DifferentialGate.GateRun run = runGate(root);
            check(!run.ok(), "missing sidecar: the gate fails");
            check(run.failures().size() == 1
                    && run.failures().get(0).kind().equals("classification")
                    && run.failures().get(0).detail().contains(
                        "missing sidecar"),
                "missing sidecar: a classification failure names the fixture "
                    + "and the missing sidecar, got: "
                    + (run.failures().isEmpty() ? "<none>"
                        : run.failures().get(0).message()));
            clean(root);
        }
        // A compile-ok fixture must not carry a sidecar.
        {
            Path root = scratchRoot();
            writeScratch(root, "backend-runtime/x.deal",
                fixture(HEADER_SPEC, "// @expected: compile-ok\n", ""));
            writeScratch(root, "backend-runtime/x.expect.json",
                "{\"version\":1,\"backends\":[\"luajit\",\"jvm\",\"js\"],"
                    + "\"expected\":{\"mode\":\"runtime-ok\",\"transcript\":"
                    + "{\"stdout\":\"\",\"stderr\":\"\"},\"exitCode\":0}}\n");
            DifferentialGate.GateRun run = runGate(root);
            check(!run.ok(), "compile-ok with a sidecar: the gate fails");
            check(run.failures().stream().anyMatch(f ->
                    f.detail().contains("sidecars sit only next to")),
                "compile-ok with a sidecar: the failure names the presence "
                    + "rule, got: " + run.failures());
            clean(root);
        }
        // A malformed sidecar next to a runtime fixture fails validation.
        {
            Path root = scratchRoot();
            writeScratch(root, "backend-runtime/x.deal",
                fixture(HEADER_SPEC, "// @expected: runtime-ok\n", ""));
            writeScratch(root, "backend-runtime/x.expect.json",
                "{\"version\":1,\"backends\":[\"luajit\",\"jvm\"]}\n");
            DifferentialGate.GateRun run = runGate(root);
            check(!run.ok(), "partial sidecar: the gate fails");
            check(run.failures().stream().anyMatch(f ->
                    f.kind().equals("classification")
                        && f.subject().equals("backend-runtime/x.deal")
                        && f.detail().contains("exactly the three backends")),
                "partial sidecar: T1 validation names the fixture and the "
                    + "backends field, got: " + run.failures());
            clean(root);
        }
        // A compile-error fixture whose sidecar pin differs from @expected
        // fails the classification cross-check.
        {
            Path root = scratchRoot();
            writeScratch(root, "backend-runtime/x.deal",
                fixture(HEADER_SPEC, "// @expected: compile-error E3001\n",
                    ""));
            writeScratch(root, "backend-runtime/x.expect.json",
                "{\"version\":1,\"mode\":\"compile-error\",\"diagnostic\":"
                    + "{\"code\":\"E3999\"}}\n");
            DifferentialGate.GateRun run = runGate(root);
            check(!run.ok(), "pin/@expected code mismatch: the gate fails");
            check(run.failures().stream().anyMatch(f ->
                    f.detail().contains("pins code E3999 but the fixture's "
                        + "@expected pins E3001")),
                "pin/@expected code mismatch: T1 names the classification "
                    + "cross-check, got: " + run.failures());
            clean(root);
        }
        // A runtime-shaped sidecar next to a compile-error fixture fails.
        {
            Path root = scratchRoot();
            writeScratch(root, "backend-runtime/x.deal",
                fixture(HEADER_SPEC, "// @expected: compile-error E3001\n",
                    ""));
            writeScratch(root, "backend-runtime/x.expect.json",
                "{\"version\":1,\"backends\":[\"luajit\",\"jvm\",\"js\"],"
                    + "\"expected\":{\"mode\":\"runtime-ok\",\"transcript\":"
                    + "{\"stdout\":\"\",\"stderr\":\"\"},\"exitCode\":0}}\n");
            DifferentialGate.GateRun run = runGate(root);
            check(!run.ok(),
                "runtime sidecar on a compile fixture: the gate fails");
            check(run.failures().stream().anyMatch(f ->
                    f.detail().contains("sidecars sit only next to")),
                "runtime sidecar on a compile fixture: the failure names the "
                    + "presence rule, got: " + run.failures());
            clean(root);
        }
        // A valid runtime-ok sidecar validates clean end to end.
        {
            Path root = scratchRoot();
            writeScratch(root, "backend-runtime/x.deal",
                fixture(HEADER_SPEC, "// @expected: runtime-ok\n", ""));
            writeScratch(root, "backend-runtime/x.expect.json",
                "{\"version\":1,\"backends\":[\"luajit\",\"jvm\",\"js\"],"
                    + "\"expected\":{\"mode\":\"runtime-ok\",\"transcript\":"
                    + "{\"stdout\":\"\",\"stderr\":\"\"},\"exitCode\":0}}\n");
            DifferentialGate.GateRun run = runGate(root);
            check(run.ok(), "a valid runtime-ok sidecar validates clean, got: "
                + run.failures());
            clean(root);
        }
    }

    private static DifferentialGate.GateRun runGate(Path root)
            throws IOException {
        PrintStream capture = new PrintStream(new ByteArrayOutputStream(),
            true, StandardCharsets.UTF_8);
        return DifferentialGate.run(root, Map.of(), 2,
            Duration.ofSeconds(5), capture);
    }
}
