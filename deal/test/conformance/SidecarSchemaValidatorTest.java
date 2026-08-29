package deal.test.conformance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Verifies the ISSUE-0348 reusable Sidecar Schema Validation component
 * ({@link SidecarSchemaValidator}) against schema version 1 of the
 * Structured Expectation Sidecar (uniform {@code runtime-ok}, uniform
 * {@code runtime-error}, divergent form) and the Compile Expectation
 * Sidecar, with synthetic sidecar inputs and synthetic compilation sets
 * (corpus page {@code v12-three-backend-conformance-corpus}
 * Verification 1/7; gate page {@code v12-zero-skip-conformance-gate}
 * Verification 6).
 *
 * <p>Negative matrix — every case must produce a classification failure
 * naming the fixture and the offending field:</p>
 * <ol>
 *   <li>unknown version; unknown root field; unknown variant
 *       (incl. {@code compile-reject} inside a uniform {@code expected});</li>
 *   <li>partial backend list; more than three backends; duplicate or
 *       unknown backend; a divergent sidecar missing a backend;</li>
 *   <li>divergent form for a non-FFI fixture (no {@code @extern-c} in the
 *       compilation set) even when both jvm/js legs pin E6006;</li>
 *   <li>a {@code compile-reject} entry on the luajit leg; a partial split
 *       (jvm compile-reject E6006 while js carries a runtime
 *       expectation); a luajit compile-reject with jvm/js runtime; a
 *       non-E6006 compile-reject code; an all-runtime divergent form;</li>
 *   <li>{@code error} on {@code runtime-ok}; missing mandatory
 *       {@code code}/{@code message}/{@code sourceFile}/{@code line}/
 *       {@code column}; unknown error field; wrong exit code;</li>
 *   <li>{@code sourceFile} naming a corpus module outside the fixture's
 *       compilation set; absolute, temp-workspace, backslash-separated,
 *       or nonexistent-module {@code sourceFile};</li>
 *   <li>a compile sidecar carrying a {@code backends} key; a compile
 *       sidecar on a non-{@code compile-error} fixture; a compile sidecar
 *       pin code differing from {@code @expected}; malformed JSON.</li>
 * </ol>
 *
 * <p>Positive matrix — valid uniform runtime-ok, uniform runtime-error
 * with and without pinned optional fields, a runtime-error sidecar whose
 * {@code sourceFile} names a companion module of the compilation set, the
 * sanctioned divergent form (luajit runtime, jvm/js compile-reject E6006)
 * on a compilation set carrying the {@code @extern-c} trigger, and valid
 * compile sidecars all validate clean.</p>
 *
 * <p>Every case additionally asserts determinism: validating the same
 * input twice yields the identical outcome.</p>
 */
public class SidecarSchemaValidatorTest {

    private static int passed = 0;
    private static int failed = 0;

    // =========================================================================
    // Synthetic corpus context
    // =========================================================================

    private static final String FIXTURE =
        "backend-runtime/arithmetic/int-add-overflow.deal";

    private static final SidecarSchemaValidator.CompilationModule FIXTURE_MODULE =
        new SidecarSchemaValidator.CompilationModule(FIXTURE,
            "export function main(): null { return null; }\n");

    private static final SidecarSchemaValidator.CompilationModule COMPANION =
        new SidecarSchemaValidator.CompilationModule(
            "backend-runtime/arithmetic/helpers.deal",
            "export function add(a: int, b: int): int { return a + b; }\n");

    private static final String FFI_FIXTURE =
        "backend-runtime/ffi/extern-c-record.deal";

    private static final SidecarSchemaValidator.CompilationModule FFI_MODULE =
        new SidecarSchemaValidator.CompilationModule(FFI_FIXTURE,
            "// @extern-c\nimport * as ffi from \"myffi\"\n"
                + "export function record(): int { return 1; }\n");

    private static final SidecarSchemaValidator.CompilationModule FFI_PLAIN_MODULE =
        new SidecarSchemaValidator.CompilationModule(FFI_FIXTURE,
            "export function record(): int { return 1; }\n");

    private static final SidecarSchemaValidator.CompilationModule FFI_COMPANION =
        new SidecarSchemaValidator.CompilationModule(
            "backend-runtime/ffi/ffi-wrapper.deal",
            "// @extern-c\nimport * as ffi from \"myffi\"\n"
                + "export function wrap(): int { return 2; }\n");

    private static final String COMPILE_FIXTURE =
        "frontend/diagnostics/expected-int-got-number.deal";

    private static final String FULL_ERROR =
        "{ \"code\": \"E8001\", \"message\": \"expected int, got number\", "
            + "\"sourceFile\": \"" + FIXTURE + "\", \"line\": 4, \"column\": 9, "
            + "\"expected\": \"int\", \"actual\": \"number\", \"frames\": 3, "
            + "\"cause\": \"E8002\" }";

    // =========================================================================
    // Sidecar document builders (synthetic JSON)
    // =========================================================================

    private static String uniform(String expectationBody) {
        return """
            {
              "version": 1,
              "backends": ["luajit", "jvm", "js"],
              "expected": %s
            }
            """.formatted(expectationBody);
    }

    private static String runtimeOkEntry(String stdout) {
        return "{ \"mode\": \"runtime-ok\", \"transcript\": { \"stdout\": \""
            + stdout + "\", \"stderr\": \"\" }, \"exitCode\": 0 }";
    }

    private static String runtimeErrorEntry(String errorBody) {
        return "{ \"mode\": \"runtime-error\", \"transcript\": { \"stdout\": "
            + "\"DEAL_ERROR_CODE: E8001\", \"stderr\": \"\" }, \"exitCode\": 1, "
            + "\"error\": " + errorBody + " }";
    }

    private static String rejectEntry(String code) {
        return "{ \"mode\": \"compile-reject\", \"diagnostic\": { \"code\": \""
            + code + "\" } }";
    }

    private static String divergent(String luajit, String jvm, String js) {
        return """
            {
              "version": 1,
              "backends": {
                "luajit": %s,
                "jvm": %s,
                "js": %s
              }
            }
            """.formatted(luajit, jvm, js);
    }

    private static String compileSidecar(String diagnosticBody) {
        return """
            {
              "version": 1,
              "mode": "compile-error",
              "diagnostic": %s
            }
            """.formatted(diagnosticBody);
    }

    // =========================================================================
    // Assertion helpers
    // =========================================================================

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    private static Optional<SidecarSchemaValidator.ClassificationFailure> run(
        String fixturePath, String expectedTag,
        List<SidecarSchemaValidator.CompilationModule> compilationSet,
        Set<String> corpusIndex, String sidecarJson) {
        SidecarSchemaValidator.ValidationContext context =
            new SidecarSchemaValidator.ValidationContext(
                fixturePath, expectedTag, compilationSet, corpusIndex);
        Optional<SidecarSchemaValidator.ClassificationFailure> first =
            SidecarSchemaValidator.validate(context, sidecarJson);
        Optional<SidecarSchemaValidator.ClassificationFailure> second =
            SidecarSchemaValidator.validate(context, sidecarJson);
        check(first.equals(second),
            "validation must be deterministic for the same input (case: "
                + sidecarJson.replace('\n', ' ').substring(0, Math.min(80,
                    sidecarJson.replace('\n', ' ').length())) + " ...)");
        return first;
    }

    private static void expectValid(String fixturePath, String expectedTag,
        List<SidecarSchemaValidator.CompilationModule> compilationSet,
        String sidecarJson, String caseName) {
        expectValid(fixturePath, expectedTag, compilationSet, Set.of(),
            sidecarJson, caseName);
    }

    private static void expectValid(String fixturePath, String expectedTag,
        List<SidecarSchemaValidator.CompilationModule> compilationSet,
        Set<String> corpusIndex, String sidecarJson, String caseName) {
        Optional<SidecarSchemaValidator.ClassificationFailure> failure =
            run(fixturePath, expectedTag, compilationSet, corpusIndex, sidecarJson);
        check(failure.isEmpty(), caseName + ": expected validation to pass, got "
            + (failure.isPresent() ? failure.get().message() : ""));
    }

    private static void expectFailure(String fixturePath, String expectedTag,
        List<SidecarSchemaValidator.CompilationModule> compilationSet,
        String sidecarJson, String expectedField, String caseName) {
        expectFailure(fixturePath, expectedTag, compilationSet, Set.of(),
            sidecarJson, expectedField, caseName);
    }

    private static void expectFailure(String fixturePath, String expectedTag,
        List<SidecarSchemaValidator.CompilationModule> compilationSet,
        Set<String> corpusIndex, String sidecarJson, String expectedField,
        String caseName) {
        Optional<SidecarSchemaValidator.ClassificationFailure> failure =
            run(fixturePath, expectedTag, compilationSet, corpusIndex, sidecarJson);
        if (failure.isEmpty()) {
            fail(caseName + ": expected a classification failure but "
                + "validation passed");
            return;
        }
        SidecarSchemaValidator.ClassificationFailure f = failure.get();
        check(f.fixturePath().equals(fixturePath),
            caseName + ": failure must name the fixture \"" + fixturePath
                + "\", got \"" + f.fixturePath() + "\"");
        check(f.field().equals(expectedField),
            caseName + ": expected failure field \"" + expectedField
                + "\", got \"" + f.field() + "\"");
        check(f.message().contains(fixturePath) && f.message().contains(expectedField),
            caseName + ": the failure message must name the fixture and the "
                + "field, got: " + f.message());
    }

    private static List<SidecarSchemaValidator.CompilationModule> modules(
        SidecarSchemaValidator.CompilationModule... items) {
        return new ArrayList<>(List.of(items));
    }

    // =========================================================================
    // Positive cases
    // =========================================================================

    private static void testUniformRuntimeOkValid() {
        expectValid(FIXTURE, "runtime-ok", modules(FIXTURE_MODULE),
            uniform(runtimeOkEntry("ok\\n")),
            "valid uniform runtime-ok sidecar");
    }

    private static void testUniformRuntimeErrorWithOptionalsValid() {
        expectValid(FIXTURE, "runtime-error E8001", modules(FIXTURE_MODULE),
            uniform(runtimeErrorEntry(FULL_ERROR)),
            "valid uniform runtime-error sidecar with pinned optional fields");
    }

    private static void testUniformRuntimeErrorWithoutOptionalsValid() {
        String error = "{ \"code\": \"E8001\", \"message\": \"boom\", "
            + "\"sourceFile\": \"" + FIXTURE + "\", \"line\": 1, \"column\": 1 }";
        expectValid(FIXTURE, "runtime-error E8001", modules(FIXTURE_MODULE),
            uniform(runtimeErrorEntry(error)),
            "valid uniform runtime-error sidecar without optional fields");
    }

    private static void testCompanionSourceFileValid() {
        String error = "{ \"code\": \"E8001\", \"message\": \"companion threw\", "
            + "\"sourceFile\": \"" + COMPANION.corpusPath()
            + "\", \"line\": 2, \"column\": 3 }";
        expectValid(FIXTURE, "runtime-error E8001",
            modules(FIXTURE_MODULE, COMPANION),
            uniform(runtimeErrorEntry(error)),
            "runtime-error sidecar whose sourceFile names a companion module "
                + "of the compilation set (the module that threw)");
    }

    private static void testSanctionedDivergentRuntimeOkValid() {
        expectValid(FFI_FIXTURE, "runtime-ok", modules(FFI_MODULE),
            divergent(runtimeOkEntry("record\\n"), rejectEntry("E6006"),
                rejectEntry("E6006")),
            "sanctioned divergent form: luajit runtime-ok, jvm/js "
                + "compile-reject E6006, with the @extern-c trigger");
    }

    private static void testSanctionedDivergentTriggerInCompanionValid() {
        // The C6 trigger sits in a companion module of the compilation set
        // (the fixture itself carries no @extern-c import).
        expectValid(FFI_FIXTURE, "runtime-ok",
            modules(FFI_PLAIN_MODULE, FFI_COMPANION),
            divergent(runtimeOkEntry("record\\n"), rejectEntry("E6006"),
                rejectEntry("E6006")),
            "sanctioned divergent form whose @extern-c trigger is a companion "
                + "module of the compilation set");
    }

    private static void testSanctionedDivergentRuntimeErrorValid() {
        String error = "{ \"code\": \"E8001\", \"message\": \"ffi threw\", "
            + "\"sourceFile\": \"" + FFI_FIXTURE + "\", \"line\": 3, "
            + "\"column\": 1 }";
        expectValid(FFI_FIXTURE, "runtime-error E8001", modules(FFI_MODULE),
            divergent(runtimeErrorEntry(error), rejectEntry("E6006"),
                rejectEntry("E6006")),
            "sanctioned divergent form with a runtime-error luajit leg "
                + "(runtime-ok or runtime-error is valid on luajit)");
    }

    private static void testCompileSidecarValid() {
        String diagnostic = "{ \"code\": \"E3001\", \"line\": 4, \"column\": 9, "
            + "\"message\": \"expected int, got number\" }";
        expectValid(COMPILE_FIXTURE, "compile-error E3001",
            modules(new SidecarSchemaValidator.CompilationModule(COMPILE_FIXTURE,
                "// @spec: §Type checker\n")),
            compileSidecar(diagnostic),
            "valid Compile Expectation Sidecar with pinned line/column/message");
    }

    private static void testCompileSidecarMinimalValid() {
        expectValid(COMPILE_FIXTURE, "compile-error E3001",
            modules(new SidecarSchemaValidator.CompilationModule(COMPILE_FIXTURE,
                "// @spec: §Type checker\n")),
            compileSidecar("{ \"code\": \"E3001\" }"),
            "valid Compile Expectation Sidecar with a code-only diagnostic pin");
    }

    // =========================================================================
    // Negative cases — schema v1 failures
    // =========================================================================

    private static void testUnknownVersionFails() {
        expectFailure(FIXTURE, "runtime-ok", modules(FIXTURE_MODULE),
            uniform(runtimeOkEntry("ok")).replace("\"version\": 1",
                "\"version\": 2"),
            "version", "unknown sidecar schema version");
    }

    private static void testUnknownRootFieldFails() {
        expectFailure(FIXTURE, "runtime-ok", modules(FIXTURE_MODULE),
            uniform(runtimeOkEntry("ok")).replace("\"version\": 1,",
                "\"version\": 1, \"notes\": \"x\","),
            "notes", "unknown field in the sidecar root");
    }

    private static void testUnknownVariantFails() {
        expectFailure(FIXTURE, "runtime-ok", modules(FIXTURE_MODULE),
            uniform("{ \"mode\": \"runtime-panic\", \"transcript\": { "
                + "\"stdout\": \"\", \"stderr\": \"\" }, \"exitCode\": 0 }"),
            "expected.mode", "unknown variant in a uniform expected object");
    }

    private static void testCompileRejectInUniformFails() {
        expectFailure(FIXTURE, "runtime-ok", modules(FIXTURE_MODULE),
            uniform("{ \"mode\": \"compile-reject\" }"),
            "expected.mode", "compile-reject in a uniform expected object is "
                + "an unknown-variant failure");
    }

    private static void testPartialBackendListFails() {
        expectFailure(FIXTURE, "runtime-ok", modules(FIXTURE_MODULE),
            """
            {
              "version": 1,
              "backends": ["luajit", "jvm"],
              "expected": %s
            }
            """.formatted(runtimeOkEntry("ok")),
            "backends", "partial backend list");
    }

    private static void testMoreThanThreeBackendsFails() {
        expectFailure(FIXTURE, "runtime-ok", modules(FIXTURE_MODULE),
            """
            {
              "version": 1,
              "backends": ["luajit", "jvm", "js", "luajit"],
              "expected": %s
            }
            """.formatted(runtimeOkEntry("ok")),
            "backends", "more than three backends (duplicate name)");
    }

    private static void testUnknownBackendFails() {
        expectFailure(FIXTURE, "runtime-ok", modules(FIXTURE_MODULE),
            """
            {
              "version": 1,
              "backends": ["luajit", "jvm", "python"],
              "expected": %s
            }
            """.formatted(runtimeOkEntry("ok")),
            "backends", "unknown backend name in the uniform list");
    }

    private static void testRuntimeSidecarWithoutBackendsFails() {
        expectFailure(FIXTURE, "runtime-ok", modules(FIXTURE_MODULE),
            """
            {
              "version": 1,
              "expected": %s
            }
            """.formatted(runtimeOkEntry("ok")),
            "backends", "runtime sidecar without a backends key");
    }

    private static void testUniformWithoutExpectedFails() {
        expectFailure(FIXTURE, "runtime-ok", modules(FIXTURE_MODULE),
            """
            {
              "version": 1,
              "backends": ["luajit", "jvm", "js"]
            }
            """,
            "expected", "uniform sidecar without the expected object");
    }

    private static void testMalformedJsonFails() {
        expectFailure(FIXTURE, "runtime-ok", modules(FIXTURE_MODULE),
            "{ \"version\": 1, \"backends\": [",
            "<sidecar>", "malformed sidecar JSON");
    }

    // =========================================================================
    // Negative cases — runtime-ok / runtime-error expectation rules
    // =========================================================================

    private static void testErrorOnRuntimeOkFails() {
        expectFailure(FIXTURE, "runtime-ok", modules(FIXTURE_MODULE),
            uniform("{ \"mode\": \"runtime-ok\", \"transcript\": { \"stdout\": "
                + "\"ok\", \"stderr\": \"\" }, \"exitCode\": 0, \"error\": "
                + FULL_ERROR + " }"),
            "expected.error", "error field on runtime-ok");
    }

    private static void testWrongExitCodeFails() {
        expectFailure(FIXTURE, "runtime-ok", modules(FIXTURE_MODULE),
            uniform("{ \"mode\": \"runtime-ok\", \"transcript\": { \"stdout\": "
                + "\"ok\", \"stderr\": \"\" }, \"exitCode\": 1 }"),
            "expected.exitCode", "runtime-ok with exitCode 1");
    }

    private static void testUnknownTranscriptFieldFails() {
        expectFailure(FIXTURE, "runtime-ok", modules(FIXTURE_MODULE),
            uniform("{ \"mode\": \"runtime-ok\", \"transcript\": { \"stdout\": "
                + "\"ok\", \"stderr\": \"\", \"extra\": \"x\" }, "
                + "\"exitCode\": 0 }"),
            "expected.transcript.extra", "unknown field in the transcript");
    }

    private static void testMissingMandatoryErrorFieldFails() {
        Map<String, String> pieces = new LinkedHashMap<>();
        pieces.put("code", "\"code\": \"E8001\"");
        pieces.put("message", "\"message\": \"boom\"");
        pieces.put("sourceFile", "\"sourceFile\": \"" + FIXTURE + "\"");
        pieces.put("line", "\"line\": 1");
        pieces.put("column", "\"column\": 1");
        for (String field : List.of("code", "message", "sourceFile", "line",
                "column")) {
            List<String> kept = new ArrayList<>();
            for (var entry : pieces.entrySet()) {
                if (!entry.getKey().equals(field)) {
                    kept.add(entry.getValue());
                }
            }
            String error = "{ " + String.join(", ", kept) + " }";
            expectFailure(FIXTURE, "runtime-error E8001",
                modules(FIXTURE_MODULE), uniform(runtimeErrorEntry(error)),
                "expected.error." + field,
                "missing mandatory error field \"" + field + "\"");
        }
    }

    private static void testUnknownErrorFieldFails() {
        String error = "{ \"code\": \"E8001\", \"message\": \"boom\", "
            + "\"sourceFile\": \"" + FIXTURE + "\", \"line\": 1, "
            + "\"column\": 1, \"span\": [1, 2] }";
        expectFailure(FIXTURE, "runtime-error E8001", modules(FIXTURE_MODULE),
            uniform(runtimeErrorEntry(error)),
            "expected.error.span", "unknown field in the error object");
    }

    private static void testWrongOptionalErrorFieldTypeFails() {
        String error = "{ \"code\": \"E8001\", \"message\": \"boom\", "
            + "\"sourceFile\": \"" + FIXTURE + "\", \"line\": 1, "
            + "\"column\": 1, \"frames\": \"three\" }";
        expectFailure(FIXTURE, "runtime-error E8001", modules(FIXTURE_MODULE),
            uniform(runtimeErrorEntry(error)),
            "expected.error.frames", "frames must be an integer");
    }

    // =========================================================================
    // Negative cases — sourceFile canonical form
    // =========================================================================

    private static String errorWithSourceFile(String sourceFile) {
        return "{ \"code\": \"E8001\", \"message\": \"boom\", \"sourceFile\": \""
            + sourceFile + "\", \"line\": 1, \"column\": 1 }";
    }

    private static void testSourceFileOutsideCompilationSetFails() {
        String unrelated = "backend-runtime/arithmetic/unrelated-fixture.deal";
        expectFailure(FIXTURE, "runtime-error E8001",
            modules(FIXTURE_MODULE, COMPANION), Set.of(unrelated),
            uniform(runtimeErrorEntry(errorWithSourceFile(unrelated))),
            "expected.error.sourceFile",
            "sourceFile naming a corpus module outside the fixture's "
                + "compilation set");
    }

    private static void testSourceFileAbsoluteFails() {
        expectFailure(FIXTURE, "runtime-error E8001", modules(FIXTURE_MODULE),
            uniform(runtimeErrorEntry(errorWithSourceFile(
                "/home/user/deal/test/conformance/" + FIXTURE))),
            "expected.error.sourceFile", "absolute sourceFile");
    }

    private static void testSourceFileTempWorkspaceFails() {
        expectFailure(FIXTURE, "runtime-error E8001", modules(FIXTURE_MODULE),
            uniform(runtimeErrorEntry(errorWithSourceFile(
                "/tmp/deal_jvm_conf_123456/" + FIXTURE))),
            "expected.error.sourceFile", "temp-workspace sourceFile");
    }

    private static void testSourceFileBackslashFails() {
        expectFailure(FIXTURE, "runtime-error E8001", modules(FIXTURE_MODULE),
            uniform(runtimeErrorEntry(errorWithSourceFile(
                "backend-runtime\\\\arithmetic\\\\int-add-overflow.deal"))),
            "expected.error.sourceFile", "backslash-separated sourceFile");
    }

    private static void testSourceFileNonexistentModuleFails() {
        expectFailure(FIXTURE, "runtime-error E8001", modules(FIXTURE_MODULE),
            uniform(runtimeErrorEntry(errorWithSourceFile(
                "backend-runtime/arithmetic/does-not-exist.deal"))),
            "expected.error.sourceFile",
            "sourceFile resolving to no existing corpus module");
    }

    // =========================================================================
    // Negative cases — divergent form
    // =========================================================================

    private static void testDivergentPartialBackendListFails() {
        expectFailure(FFI_FIXTURE, "runtime-ok", modules(FFI_MODULE),
            """
            {
              "version": 1,
              "backends": {
                "luajit": %s,
                "jvm": %s
              }
            }
            """.formatted(runtimeOkEntry("ok"), rejectEntry("E6006")),
            "backends", "divergent sidecar missing the js leg");
    }

    private static void testDivergentUnknownBackendFails() {
        expectFailure(FFI_FIXTURE, "runtime-ok", modules(FFI_MODULE),
            divergent(runtimeOkEntry("ok"), rejectEntry("E6006"),
                rejectEntry("E6006")).replace("\"js\":",
                "\"python\": { \"mode\": \"runtime-ok\", \"transcript\": { "
                    + "\"stdout\": \"\", \"stderr\": \"\" }, \"exitCode\": 0 }, "
                    + "\"js\":"),
            "backends.python", "unknown backend key in the divergent form");
    }

    private static void testDivergentNonFfiFails() {
        // Both jvm and js legs pin E6006, but the compilation set carries
        // no @extern-c import: the divergence is unsanctioned.
        expectFailure(FIXTURE, "runtime-ok", modules(FIXTURE_MODULE, COMPANION),
            divergent(runtimeOkEntry("ok"), rejectEntry("E6006"),
                rejectEntry("E6006")),
            "backends", "divergent form on a fixture whose compilation set "
                + "is not the C FFI case");
    }

    private static void testCompileRejectOnLuajitLegFails() {
        expectFailure(FFI_FIXTURE, "runtime-ok", modules(FFI_MODULE),
            divergent(rejectEntry("E6006"), rejectEntry("E6006"),
                rejectEntry("E6006")),
            "backends.luajit.mode", "compile-reject entry on the luajit leg");
    }

    private static void testPartialSplitFails() {
        // jvm compile-reject E6006 while js carries a runtime expectation.
        expectFailure(FFI_FIXTURE, "runtime-ok", modules(FFI_MODULE),
            divergent(runtimeOkEntry("ok"), rejectEntry("E6006"),
                runtimeOkEntry("ok")),
            "backends.js.mode", "partial split: js carries a runtime "
                + "expectation instead of compile-reject E6006");
    }

    private static void testLuajitRejectWithRuntimeJvmJsFails() {
        expectFailure(FFI_FIXTURE, "runtime-ok", modules(FFI_MODULE),
            divergent(rejectEntry("E6006"), runtimeOkEntry("ok"),
                runtimeOkEntry("ok")),
            "backends.luajit.mode", "luajit compile-reject while jvm/js "
                + "carry runtime expectations");
    }

    private static void testNonE6006RejectCodeFails() {
        expectFailure(FFI_FIXTURE, "runtime-ok", modules(FFI_MODULE),
            divergent(runtimeOkEntry("ok"), rejectEntry("E7002"),
                rejectEntry("E6006")),
            "backends.jvm.diagnostic.code",
            "divergent compile-reject with a non-E6006 code");
    }

    private static void testAllRuntimeDivergentFails() {
        expectFailure(FFI_FIXTURE, "runtime-ok", modules(FFI_MODULE),
            divergent(runtimeOkEntry("ok"), runtimeOkEntry("ok"),
                runtimeOkEntry("ok")),
            "backends.jvm.mode", "all-runtime divergent form (the uniform "
                + "form is the only per-backend-identical shape)");
    }

    private static void testDivergentEntryNotObjectFails() {
        expectFailure(FFI_FIXTURE, "runtime-ok", modules(FFI_MODULE),
            """
            {
              "version": 1,
              "backends": {
                "luajit": "runtime-ok",
                "jvm": %s,
                "js": %s
              }
            }
            """.formatted(rejectEntry("E6006"), rejectEntry("E6006")),
            "backends.luajit", "divergent backend entry that is not an object");
    }

    private static void testCompileRejectEntryWithTranscriptFails() {
        String badJvm = "{ \"mode\": \"compile-reject\", \"diagnostic\": { "
            + "\"code\": \"E6006\" }, \"transcript\": { \"stdout\": \"\", "
            + "\"stderr\": \"\" } }";
        expectFailure(FFI_FIXTURE, "runtime-ok", modules(FFI_MODULE),
            divergent(runtimeOkEntry("ok"), badJvm, rejectEntry("E6006")),
            "backends.jvm.transcript",
            "compile-reject entry carrying a transcript field");
    }

    private static void testRejectDiagnosticMessageFieldFails() {
        String badJvm = "{ \"mode\": \"compile-reject\", \"diagnostic\": { "
            + "\"code\": \"E6006\", \"message\": \"detail\" } }";
        expectFailure(FFI_FIXTURE, "runtime-ok", modules(FFI_MODULE),
            divergent(runtimeOkEntry("ok"), badJvm, rejectEntry("E6006")),
            "backends.jvm.diagnostic.message",
            "compile-reject diagnostic carrying message (compile-sidecar-only)");
    }

    // =========================================================================
    // Negative cases — Compile Expectation Sidecar
    // =========================================================================

    private static void testCompileSidecarWithBackendsFails() {
        String json = """
            {
              "version": 1,
              "mode": "compile-error",
              "backends": ["luajit", "jvm", "js"],
              "diagnostic": { "code": "E3001" }
            }
            """;
        expectFailure(COMPILE_FIXTURE, "compile-error E3001",
            modules(new SidecarSchemaValidator.CompilationModule(COMPILE_FIXTURE,
                "// @spec: §Type checker\n")),
            json, "backends", "compile sidecar carrying a backends key");
    }

    private static void testCompileSidecarOnNonCompileErrorFixtureFails() {
        expectFailure(COMPILE_FIXTURE, "compile-ok",
            modules(new SidecarSchemaValidator.CompilationModule(COMPILE_FIXTURE,
                "// @spec: §Type checker\n")),
            compileSidecar("{ \"code\": \"E3001\" }"),
            "@expected", "compile sidecar on a fixture whose @expected is "
                + "not an exact compile-error CODE");
    }

    private static void testCompileSidecarCodeDiffersFails() {
        expectFailure(COMPILE_FIXTURE, "compile-error E3002",
            modules(new SidecarSchemaValidator.CompilationModule(COMPILE_FIXTURE,
                "// @spec: §Type checker\n")),
            compileSidecar("{ \"code\": \"E3001\" }"),
            "diagnostic.code", "compile sidecar pin code differing from "
                + "@expected");
    }

    private static void testCompileSidecarMissingCodeFails() {
        expectFailure(COMPILE_FIXTURE, "compile-error E3001",
            modules(new SidecarSchemaValidator.CompilationModule(COMPILE_FIXTURE,
                "// @spec: §Type checker\n")),
            compileSidecar("{ \"line\": 4 }"),
            "diagnostic.code", "compile sidecar missing the mandatory code");
    }

    private static void testCompileDiagnosticUnknownFieldFails() {
        expectFailure(COMPILE_FIXTURE, "compile-error E3001",
            modules(new SidecarSchemaValidator.CompilationModule(COMPILE_FIXTURE,
                "// @spec: §Type checker\n")),
            compileSidecar("{ \"code\": \"E3001\", \"span\": [1, 2] }"),
            "diagnostic.span", "unknown field in the compile diagnostic object");
    }

    private static void testCompileSidecarUnknownModeFails() {
        expectFailure(COMPILE_FIXTURE, "compile-error E3001",
            modules(new SidecarSchemaValidator.CompilationModule(COMPILE_FIXTURE,
                "// @spec: §Type checker\n")),
            """
            {
              "version": 1,
              "mode": "runtime-ok",
              "diagnostic": { "code": "E3001" }
            }
            """,
            "mode", "compile sidecar with a non-compile-error mode");
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Sidecar Schema Validator Test (ISSUE-0348) ===\n");

        testUniformRuntimeOkValid();
        testUniformRuntimeErrorWithOptionalsValid();
        testUniformRuntimeErrorWithoutOptionalsValid();
        testCompanionSourceFileValid();
        testSanctionedDivergentRuntimeOkValid();
        testSanctionedDivergentTriggerInCompanionValid();
        testSanctionedDivergentRuntimeErrorValid();
        testCompileSidecarValid();
        testCompileSidecarMinimalValid();

        testUnknownVersionFails();
        testUnknownRootFieldFails();
        testUnknownVariantFails();
        testCompileRejectInUniformFails();
        testPartialBackendListFails();
        testMoreThanThreeBackendsFails();
        testUnknownBackendFails();
        testRuntimeSidecarWithoutBackendsFails();
        testUniformWithoutExpectedFails();
        testMalformedJsonFails();

        testErrorOnRuntimeOkFails();
        testWrongExitCodeFails();
        testUnknownTranscriptFieldFails();
        testMissingMandatoryErrorFieldFails();
        testUnknownErrorFieldFails();
        testWrongOptionalErrorFieldTypeFails();

        testSourceFileOutsideCompilationSetFails();
        testSourceFileAbsoluteFails();
        testSourceFileTempWorkspaceFails();
        testSourceFileBackslashFails();
        testSourceFileNonexistentModuleFails();

        testDivergentPartialBackendListFails();
        testDivergentUnknownBackendFails();
        testDivergentNonFfiFails();
        testCompileRejectOnLuajitLegFails();
        testPartialSplitFails();
        testLuajitRejectWithRuntimeJvmJsFails();
        testNonE6006RejectCodeFails();
        testAllRuntimeDivergentFails();
        testDivergentEntryNotObjectFails();
        testCompileRejectEntryWithTranscriptFails();
        testRejectDiagnosticMessageFieldFails();

        testCompileSidecarWithBackendsFails();
        testCompileSidecarOnNonCompileErrorFixtureFails();
        testCompileSidecarCodeDiffersFails();
        testCompileSidecarMissingCodeFails();
        testCompileDiagnosticUnknownFieldFails();
        testCompileSidecarUnknownModeFails();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
