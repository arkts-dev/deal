package deal.test.feature;

import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.DiagnosticNote;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.List;

/**
 * The ISSUE-0165 catalog/matrix unit gate: pins the strict sidecar
 * conditional rules, the architecture-owned {@link FeatureBackendMatrix}
 * mutation rejections, and the catalog closure rules without compiling
 * anything.
 *
 * <p>Every matrix mutation here is the intentional negative of the
 * declarations-page D12 table: omitting a required JVM record, adding a
 * forbidden JVM FFI runtime record, removing the linked E6003 record, and
 * mislabeling async invocation as direct-main each fail catalog
 * validation before any compilation. Exit 0 iff every case holds; a
 * failure prints its named token and exits nonzero (no skip, no retry).</p>
 */
public final class V12FeatureGateTest {

    private static int passed;
    private static int failed;

    private V12FeatureGateTest() {
        /* Static entry point only. */
    }

    public static void main(String[] args) {
        try {
            metadataRules();
            matrixMutations();
            catalogClosure();
            pinnedManifestE2010();
            runOutcomeRules();
            identityArtifactRules();
            System.out.println();
            System.out.println("=== V12 Feature Gate Test: " + passed + " passed, "
                + failed + " failed ===");
        } catch (RuntimeException | IOException e) {
            System.err.println("V12FEATURE-GATE-TEST-FAILURE: " + e);
            failed++;
        }
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void check(String name, ThrowingRunnable body) {
        try {
            body.run();
            passed++;
            System.out.println("  PASS " + name);
        } catch (AssertionError | RuntimeException e) {
            failed++;
            System.err.println("  FAIL " + name + ": " + e.getMessage());
        } catch (Exception e) {
            failed++;
            System.err.println("  FAIL " + name + ": " + e);
        }
    }

    /** A body that may raise the catalog failure under test or I/O. */
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void expectFailure(String name, String expectedSubstring,
                                      ThrowingRunnable body) {
        check(name, () -> {
            try {
                body.run();
            } catch (V12FeatureMetadata.CatalogFailure
                    | V12FeatureFixtureCatalog.CatalogFailure e) {
                if (expectedSubstring != null && !e.getMessage().contains(
                        expectedSubstring)) {
                    throw new AssertionError("expected failure text '"
                        + expectedSubstring + "', got: " + e.getMessage());
                }
                return;
            } catch (Exception e) {
                throw new AssertionError("unexpected exception: " + e);
            }
            throw new AssertionError("expected a catalog failure, none raised");
        });
    }

    // =========================================================================
    // Sidecar conditional rules (declarations page D12)
    // =========================================================================

    private static void metadataRules() throws IOException {
        System.out.println("=== Metadata conditional rules ===");
        Path tmp = Files.createTempDirectory("v12meta-");

        expectFailure("unknown field fails", "unknown field",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("\"spec\": \"s\",",
                    "\"spec\": \"s\", \"bogus\": true,")));
        expectFailure("duplicate JSON key fails", "duplicate member",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                "{\"version\": 1, \"feature\": \"directives\","
                    + " \"feature\": \"directives\"}"));
        expectFailure("wrong version fails", "version must be",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("\"version\": 1", "\"version\": 2")));
        expectFailure("unknown feature id fails", "unknown feature id",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("signed-int32", "unsigned-int64")));
        expectFailure("compile outcome with oracle fails", "forbids an oracle",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("\"invocation\": \"compile-only\"",
                    "\"invocation\": \"compile-only\","
                        + " \"oracle\": {\"exportName\": \"o\","
                        + " \"functionDescriptor\": \"()->int\"}")));
        expectFailure("runtime outcome with compile-only fails",
            "forbids invocation compile-only",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                "{\"version\": 1, \"feature\": \"signed-int32\","
                    + " \"spec\": \"s\", \"description\": \"d\","
                    + " \"expected\": \"runtime-ok\","
                    + " \"backends\": [\"luajit\", \"jvm\"],"
                    + " \"invocation\": \"compile-only\", \"support\": []}"));
        expectFailure("synthetic-main without oracle fails",
            "requires an oracle",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("\"invocation\": \"compile-only\"",
                    "\"invocation\": \"synthetic-main\"")
                    .replace("\"expected\": { \"compile-error\": \"E1036\" }",
                        "\"expected\": \"runtime-ok\"")));
        expectFailure("synthetic-main with an async oracle fails",
            "canonical sync",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("\"invocation\": \"compile-only\"",
                    "\"invocation\": \"synthetic-main\"")
                    .replace("\"expected\": { \"compile-error\": \"E1036\" }",
                        "\"expected\": \"runtime-ok\"")
                    .replace("\"support\": []",
                        "\"support\": [], \"oracle\": {\"exportName\": \"o\","
                            + " \"functionDescriptor\": \"async()->null\"}")));
        expectFailure("async-export with a sync oracle fails",
            "canonical async",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                "{\"version\": 1, \"feature\": \"bytes-async-function\","
                    + " \"spec\": \"s\", \"description\": \"d\","
                    + " \"expected\": \"runtime-ok\","
                    + " \"backends\": [\"luajit\", \"jvm\"],"
                    + " \"invocation\": \"async-export\","
                    + " \"oracle\": {\"exportName\": \"o\","
                    + " \"functionDescriptor\": \"()->null\"},"
                    + " \"support\": []}"));
        expectFailure("legacy descriptor rejected as oracle",
            "not canonical",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("\"invocation\": \"compile-only\"",
                    "\"invocation\": \"synthetic-main\"")
                    .replace("\"expected\": { \"compile-error\": \"E1036\" }",
                        "\"expected\": \"runtime-ok\"")
                    .replace("\"support\": []",
                        "\"support\": [], \"oracle\": {\"exportName\": \"o\","
                            + " \"functionDescriptor\": \"()->int[]\"}")));
        expectFailure("manifestErrorFragment with a runtime expectation fails",
            "requires a compile-error expectation",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("\"expected\": { \"compile-error\": \"E1036\" }",
                    "\"expected\": \"runtime-ok\"")
                    .replace("\"invocation\": \"compile-only\"",
                        "\"invocation\": \"direct-main\"")
                    .replace("\"support\": []",
                        "\"support\": [], \"manifestErrorFragment\": \"frag\"")));
        expectFailure("empty manifestErrorFragment fails", "non-empty string",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("\"support\": []",
                    "\"support\": [], \"manifestErrorFragment\": \"\"")));

        expectFailure("generated policy with a fragment fails",
            "manifestPolicy generated forbids",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("\"support\": []",
                    "\"support\": [], \"manifestErrorFragment\": \"frag\"")));

        expectFailure("missing policy requires a pinned fragment",
            "requires a compile-error expectation",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("\"support\": []",
                    "\"support\": [], \"manifestPolicy\": \"missing\"")));

        expectFailure("multiple policy requires a pinned fragment",
            "requires a compile-error expectation",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("\"support\": []",
                    "\"support\": [], \"manifestPolicy\": \"multiple\"")));

        expectFailure("unknown manifestPolicy fails", "manifestPolicy",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("\"support\": []",
                    "\"support\": [], \"manifestPolicy\": \"weird\"")));

        expectFailure("native without the c-ffi feature fails",
            "only feature c-ffi",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("\"support\": []",
                    "\"support\": [], \"native\": {\"entries\": ["
                        + "{\"importSpecifier\": \"native/math\","
                        + " \"declaration\": \"src/lib.d.deal\","
                        + " \"library\": \"/abs.so\"}]}")));

        expectFailure("c-ffi without a native block fails",
            "requires a native block",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("\"feature\": \"signed-int32\"",
                    "\"feature\": \"c-ffi\"")));

        expectFailure("native importSpecifier without a slash fails",
            "containing '/'",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("\"feature\": \"signed-int32\"",
                    "\"feature\": \"c-ffi\"").replace("\"support\": []",
                    "\"support\": [], \"native\": {\"entries\": ["
                        + "{\"importSpecifier\": \"math\","
                        + " \"declaration\": \"src/lib.d.deal\","
                        + " \"library\": \"/abs.so\"}]}")));

        expectFailure("native declaration must end .d.deal", ".d.deal",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("\"feature\": \"signed-int32\"",
                    "\"feature\": \"c-ffi\"").replace("\"support\": []",
                    "\"support\": [], \"native\": {\"entries\": ["
                        + "{\"importSpecifier\": \"native/math\","
                        + " \"declaration\": \"src/lib.deal\","
                        + " \"library\": \"/abs.so\"}]}")));

        expectFailure("native manifest-relative library must end .so",
            "end with .so",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("\"feature\": \"signed-int32\"",
                    "\"feature\": \"c-ffi\"").replace("\"support\": []",
                    "\"support\": [], \"native\": {\"entries\": ["
                        + "{\"importSpecifier\": \"native/math\","
                        + " \"declaration\": \"src/lib.d.deal\","
                        + " \"library\": \"native/rel.bad\"}]}")));

        expectFailure("native fixture names must not carry a slash",
            "must not contain '/'",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("\"feature\": \"signed-int32\"",
                    "\"feature\": \"c-ffi\"").replace("\"support\": []",
                    "\"support\": [], \"native\": {\"entries\": ["
                        + "{\"importSpecifier\": \"native/math\","
                        + " \"declaration\": \"src/lib.d.deal\","
                        + " \"library\": \"native/x.c\"}]}")));

        check("native classification shapes parse (bare + manifest-relative)",
            () -> {
                V12FeatureMetadata bare = V12FeatureMetadata.parse(
                    "x.sidecar.json",
                    valid().replace("\"feature\": \"signed-int32\"",
                        "\"feature\": \"c-ffi\"").replace("\"support\": []",
                        "\"support\": [], \"native\": {\"entries\": ["
                            + "{\"importSpecifier\": \"native/bare\","
                            + " \"declaration\": \"src/lib.d.deal\","
                            + " \"library\": \"v12-bare.so\"}]}"));
                V12FeatureMetadata relative = V12FeatureMetadata.parse(
                    "x.sidecar.json",
                    valid().replace("\"feature\": \"signed-int32\"",
                        "\"feature\": \"c-ffi\"").replace("\"support\": []",
                        "\"support\": [], \"native\": {\"entries\": ["
                            + "{\"importSpecifier\": \"native/rel\","
                            + " \"declaration\": \"src/lib.d.deal\","
                            + " \"library\": \"native/rel.so\"}]}"));
                if (!bare.nativePlan().entries().get(0).library()
                        .equals("v12-bare.so")
                        || !relative.nativePlan().entries().get(0).library()
                            .equals("native/rel.so")) {
                    throw new AssertionError("classification shapes not "
                        + "parsed: " + bare.nativePlan() + " / "
                        + relative.nativePlan());
                }
            });

        check("a c-ffi record with a native block parses", () -> {
            V12FeatureMetadata record = V12FeatureMetadata.parse(
                "x.sidecar.json",
                valid().replace("\"feature\": \"signed-int32\"",
                    "\"feature\": \"c-ffi\"").replace("\"support\": []",
                    "\"support\": [], \"native\": {\"entries\": ["
                        + "{\"importSpecifier\": \"native/math\","
                        + " \"declaration\": \"src/lib.d.deal\","
                        + " \"library\": \"fixture.c\"}]}"));
            if (record.nativePlan() == null
                    || record.nativePlan().entries().size() != 1
                    || !record.nativePlan().entries().get(0).library()
                        .equals("fixture.c")) {
                throw new AssertionError("wrong native plan: "
                    + record.nativePlan());
            }
        });
        expectFailure("empty backends fails", "malformed backends",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("[\"luajit\", \"jvm\"]", "[]")));
        expectFailure("unknown backend fails", "malformed backends",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("[\"luajit\", \"jvm\"]",
                    "[\"luajit\", \"lua\"]")));

        check("valid record parses", () -> {
            V12FeatureMetadata record = V12FeatureMetadata.parse(
                "int32/literal-syntax", valid());
            if (!(record.expected() instanceof V12FeatureMetadata.CompileError e)
                    || !e.code().equals("E1036")) {
                throw new AssertionError("wrong expectation: " + record.expected());
            }
            if (record.feature() != FeatureId.SIGNED_INT32
                    || !record.backends().equals(List.of("luajit", "jvm"))
                    || record.invocation()
                        != V12FeatureMetadata.Invocation.COMPILE_ONLY) {
                throw new AssertionError("wrong record fields: " + record);
            }
        });
    }

    private static String valid() {
        return "{\"version\": 1, \"feature\": \"signed-int32\","
            + " \"spec\": \"s\", \"description\": \"d\","
            + " \"expected\": { \"compile-error\": \"E1036\" },"
            + " \"backends\": [\"luajit\", \"jvm\"],"
            + " \"invocation\": \"compile-only\", \"support\": []}";
    }

    /** A valid record with the given manifestPolicy plus a pinned
     *  fragment (E2010 compile-error shape). */
    private static String injectPolicy(String policy) {
        return valid().replace("\"support\": []",
            "\"support\": [], \"manifestPolicy\": \"" + policy
                + "\", \"manifestErrorFragment\": \"frag\"");
    }

    /** The closed providerVariants block of the conditional-rule pins. */
    private static String providerVariantsBlock() {
        return "{\"providerPath\": \"shared/provider.deal\","
            + " \"variantA\": \"shared/a.deal\","
            + " \"variantB\": \"shared/b.deal\","
            + " \"oracleResults\": [42, 24]}";
    }

    /** A bytes-defaults runtime-ok synthetic-main record with the
     *  providerVariants block. */
    private static String providerVariantRecord(String feature) {
        return "{\"version\": 1, \"feature\": \"" + feature + "\","
            + " \"spec\": \"s\", \"description\": \"d\","
            + " \"expected\": \"runtime-ok\","
            + " \"backends\": [\"luajit\", \"jvm\"],"
            + " \"invocation\": \"synthetic-main\","
            + " \"oracle\": {\"exportName\": \"o\","
            + " \"functionDescriptor\": \"()->int\"},"
            + " \"support\": [],"
            + " \"providerVariants\": " + providerVariantsBlock() + "}";
    }

    /** A valid C_FFI record with one native entry naming the given
     *  declaration path. */
    private static String cffiSidecarWithDeclaration(String declaration) {
        return "{\"version\": 1, \"feature\": \"c-ffi\","
            + " \"spec\": \"s\", \"description\": \"d\","
            + " \"expected\": \"runtime-ok\","
            + " \"backends\": [\"luajit\"],"
            + " \"invocation\": \"direct-main\","
            + " \"linkedRecord\": \"c-ffi/jvm-reject\","
            + " \"support\": [], \"native\": {\"entries\": ["
            + "{\"importSpecifier\": \"native/math\","
            + " \"declaration\": \"" + declaration + "\","
            + " \"library\": \"/pinned/abs.so\"}]}}";
    }

    // =========================================================================
    // Matrix mutations (declarations page D12 table)
    // =========================================================================

    private static void matrixMutations() throws IOException {
        System.out.println("=== Matrix mutation rejections ===");

        expectFailure("omit JVM from an int32 record fails", "exact backend set",
            () -> catalogWith(fresh(), single(record("signed-int32", "compile-error E1036",
                List.of("luajit"), "compile-only", null))));

        expectFailure("omit JVM from a bytes-core record fails",
            "exact backend set",
            () -> catalogWith(fresh(), single(record("bytes-core", "runtime-ok",
                List.of("luajit"), "direct-main", null))));

        expectFailure("add JVM runtime to C_FFI fails", "exact backend set",
            () -> catalogWith(fresh(), single(withNative(record("c-ffi",
                "runtime-ok", List.of("luajit", "jvm"), "direct-main",
                null)))));

        expectFailure("C_FFI without a linked E6003 fails",
            "linked JVM compile-error E6003",
            () -> catalogWith(fresh(), single(withNative(record("c-ffi",
                "runtime-ok", List.of("luajit"), "direct-main", null)))));

        expectFailure("C_FFI with an unresolved linked record fails",
            "does not resolve",
            () -> catalogWith(fresh(), single(withNative(recordWithLinked(
                "c-ffi", "runtime-ok", List.of("luajit"), "direct-main",
                "c-ffi/jvm-reject-missing")))));

        expectFailure("linked record without E6003 fails", "expect compile-error E6003",
            () -> catalogWith(fresh(), Map.of(
                "c-ffi/runtime.sidecar.json",
                withNative(recordWithLinked("c-ffi", "runtime-ok",
                    List.of("luajit"), "direct-main", "c-ffi/jvm-reject")),
                "c-ffi/jvm-reject.sidecar.json",
                withNative(record("c-ffi", "compile-error E2010",
                    List.of("jvm"), "compile-only", null)))));

        expectFailure("async bytes mislabeled as direct-main fails",
            "requires async-export",
            () -> catalogWith(fresh(), single(record("bytes-async-function", "runtime-ok",
                List.of("luajit", "jvm"), "direct-main", null))));

        expectFailure("async bytes without an async oracle fails",
            "requires an oracle",
            () -> catalogWith(fresh(), single(record("bytes-async-function", "runtime-ok",
                List.of("luajit", "jvm"), "async-export", null))));

        expectFailure("declaration-error on one backend only fails",
            "both pipelines",
            () -> catalogWith(fresh(), single(record("c-ffi-declaration-error",
                "compile-error E7002", List.of("luajit"), "compile-only",
                null))));

        check("matrix-compliant int32 pair loads", () -> {
            V12FeatureFixtureCatalog catalog = catalogWith(fresh(),
                single(record("signed-int32", "compile-error E1036",
                    List.of("luajit", "jvm"), "compile-only", null)));
            if (catalog.records().size() != 1) {
                throw new AssertionError("wrong record count");
            }
        });
    }

    private static Path fresh() throws IOException {
        return Files.createTempDirectory("v12matrix-");
    }

    private static String jsonList(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("\"").append(values.get(i)).append("\"");
        }
        return sb.append("]").toString();
    }

    private static Map<String, String> single(String recordJson) {
        return Map.of("a.sidecar.json", recordJson);
    }

    private static Map<String, String> pair(String first, String second) {
        return Map.of("a.sidecar.json", first, "b.sidecar.json", second);
    }

    private static String record(String feature, String expectation,
                                 List<String> backends, String invocation,
                                 String id) {
        String expected = expectation.startsWith("compile-error")
            ? "{\"compile-error\": \"" + expectation.substring(
                "compile-error ".length()) + "\"}"
            : expectation.startsWith("runtime-error")
                ? "{\"runtime-error\": \"" + expectation.substring(
                    "runtime-error ".length()) + "\"}"
                : "\"" + expectation + "\"";
        return "{\"version\": 1, \"feature\": \"" + feature + "\","
            + " \"spec\": \"s\", \"description\": \"d\","
            + " \"expected\": " + expected + ","
            + " \"backends\": " + jsonList(backends) + ","
            + " \"invocation\": \"" + invocation + "\","
            + " \"support\": []"
            + "}";
    }

    private static String recordWithLinked(String feature, String expectation,
                                           List<String> backends,
                                           String invocation, String linked) {
        String base = record(feature, expectation, backends, invocation, null);
        return base.substring(0, base.length() - 1)
            + ", \"linkedRecord\": \"" + linked + "\"}";
    }

    /** The synthetic native block of matrix-mutation C_FFI records. */
    private static String cffiNative() {
        return "{\"entries\": [{\"importSpecifier\": \"native/math\","
            + " \"declaration\": \"src/lib.d.deal\","
            + " \"library\": \"/pinned/abs.so\"}]}";
    }

    private static String withNative(String recordJson) {
        return recordJson.substring(0, recordJson.length() - 1)
            + ", \"native\": " + cffiNative() + "}";
    }

    private static V12FeatureFixtureCatalog catalogWith(
            Path root, Map<String, String> sidecarContents) throws IOException {
        return V12FeatureFixtureCatalog.load(writeCatalog(root, sidecarContents));
    }

    /**
     * Writes a minimal synthetic catalog: the map names sidecar file
     * paths (relative paths like "a.sidecar.json" or
     * "c/record.sidecar.json") with their sidecar content; each record
     * additionally gets a trivial {@code src/main.deal} source so the
     * closure accounting holds.
     */
    private static Path writeCatalog(Path root, Map<String, String> sidecarContents)
            throws IOException {
        for (var entry : sidecarContents.entrySet()) {
            Path sidecar = root.resolve(entry.getKey());
            Files.createDirectories(sidecar.getParent());
            Files.writeString(sidecar, entry.getValue(), StandardCharsets.UTF_8);
            Path srcMain = sidecar.getParent().resolve("src/main.deal");
            Files.createDirectories(srcMain.getParent());
            Files.writeString(srcMain,
                "export function main(): null {\n  return null;\n}\n",
                StandardCharsets.UTF_8);
            if (entry.getValue().contains("\"feature\": \"c-ffi\"")) {
                Path lib = sidecar.getParent().resolve("src/lib.d.deal");
                Files.writeString(lib, "// @extern-c\n\n"
                    + "export function fixture_add_int(a: int, b: int): int;\n",
                    StandardCharsets.UTF_8);
            }
        }
        return root;
    }

    // =========================================================================
    // Pinned manifest-schema E2010 check (the review-proven vacuity fix)
    // =========================================================================

    /**
     * Pins {@link V12FeatureGate#manifestE2010Violation}: only a
     * manifest-parse E2010 — a SOURCE range anchored in deal.json whose
     * message carries the sidecar's pinned fragment — can satisfy a
     * malformed-manifest record. A discovery E2010 (synthetic range) or a
     * different manifest-schema failure must fail the record, so a
     * code-only E2010 match can never vacuous-pass.
     */
    private static void pinnedManifestE2010() {
        System.out.println("=== Pinned manifest-schema E2010 check ===");
        V12FeatureMetadata metadata = new V12FeatureMetadata(1,
            FeatureId.PROJECT_CONFIG, "s", "d",
            new V12FeatureMetadata.CompileError("E2010"),
            List.of("luajit", "jvm"),
            V12FeatureMetadata.Invocation.COMPILE_ONLY, null, List.of(), null,
            "unsupported backend 'lua'",
            V12FeatureMetadata.ManifestPolicy.INJECT, null, null, null);
        V12FeatureFixtureCatalog.RecordEntry entry =
            new V12FeatureFixtureCatalog.RecordEntry("probe",
                Path.of("probe"), Path.of("probe/record.sidecar.json"),
                metadata);

        check("a SOURCE deal.json E2010 with the pinned fragment complies",
            () -> {
                String violation = V12FeatureGate.manifestE2010Violation(entry,
                    CompilerDiagnostic.error(DiagnosticCode.E2010,
                        "deal.json: unsupported backend 'lua'. Supported "
                            + "backends: 'luajit', 'jvm', 'js'",
                        new DiagnosticRange("/tmp/v12/deal.json", 1, 1, 1, 5,
                            0, 5, 5, RangeOrigin.SOURCE)));
                if (violation != null) {
                    throw new AssertionError("unexpected violation: " + violation);
                }
            });

        check("a synthetic discovery E2010 is rejected by the pinned check",
            () -> {
                String violation = V12FeatureGate.manifestE2010Violation(entry,
                    CompilerDiagnostic.error(DiagnosticCode.E2010,
                        "deal: no deal.json project manifest found among the "
                            + "ancestor directories of entry file 'x'",
                        DiagnosticRange.synthetic("/tmp/v12/src/main.deal")));
                if (violation == null
                        || !violation.contains("not manifest discovery")) {
                    throw new AssertionError("expected the discovery rejection, "
                        + "got: " + violation);
                }
            });

        check("a different manifest-schema E2010 is rejected by the pinned check",
            () -> {
                String violation = V12FeatureGate.manifestE2010Violation(entry,
                    CompilerDiagnostic.error(DiagnosticCode.E2010,
                        "deal.json: unknown member 'bogus'",
                        new DiagnosticRange("/tmp/v12/deal.json", 1, 1, 1, 5,
                            0, 5, 5, RangeOrigin.SOURCE)));
                if (violation == null
                        || !violation.contains("pinned manifestErrorFragment")) {
                    throw new AssertionError("expected the fragment rejection, "
                        + "got: " + violation);
                }
            });

        V12FeatureMetadata missing = new V12FeatureMetadata(1,
            FeatureId.PROJECT_CONFIG, "s", "d",
            new V12FeatureMetadata.CompileError("E2010"),
            List.of("luajit", "jvm"),
            V12FeatureMetadata.Invocation.COMPILE_ONLY, null, List.of(), null,
            "no deal.json project manifest found",
            V12FeatureMetadata.ManifestPolicy.MISSING, null, null, null);
        V12FeatureFixtureCatalog.RecordEntry missingEntry =
            new V12FeatureFixtureCatalog.RecordEntry("missing-probe",
                Path.of("missing-probe"),
                Path.of("missing-probe/record.sidecar.json"), missing);

        check("the zero-manifest discovery E2010 complies", () -> {
            String violation = V12FeatureGate.discoveryE2010Violation(
                missingEntry, CompilerDiagnostic.error(DiagnosticCode.E2010,
                    "deal: no deal.json project manifest found among the "
                        + "ancestor directories of entry file 'x'",
                    DiagnosticRange.synthetic("/tmp/v12/src/main.deal")));
            if (violation != null) {
                throw new AssertionError("unexpected violation: " + violation);
            }
        });

        check("a SOURCE range is rejected by the discovery check", () -> {
            String violation = V12FeatureGate.discoveryE2010Violation(
                missingEntry, CompilerDiagnostic.error(DiagnosticCode.E2010,
                    "deal: no deal.json project manifest found among the "
                        + "ancestor directories of entry file 'x'",
                    new DiagnosticRange("/tmp/v12/deal.json", 1, 1, 1, 5,
                        0, 5, 5, RangeOrigin.SOURCE)));
            if (violation == null
                    || !violation.contains("synthetic range")) {
                throw new AssertionError("expected the synthetic-range "
                    + "rejection, got: " + violation);
            }
        });

        V12FeatureMetadata multiple = new V12FeatureMetadata(1,
            FeatureId.PROJECT_CONFIG, "s", "d",
            new V12FeatureMetadata.CompileError("E2010"),
            List.of("luajit", "jvm"),
            V12FeatureMetadata.Invocation.COMPILE_ONLY, null, List.of(), null,
            "multiple deal.json project manifests found",
            V12FeatureMetadata.ManifestPolicy.MULTIPLE, null, null, null);
        V12FeatureFixtureCatalog.RecordEntry multipleEntry =
            new V12FeatureFixtureCatalog.RecordEntry("multiple-probe",
                Path.of("multiple-probe"),
                Path.of("multiple-probe/record.sidecar.json"), multiple);

        check("the multiple-manifest discovery E2010 with two candidate "
                + "notes complies", () -> {
            CompilerDiagnostic diagnostic = new CompilerDiagnostic("E2010",
                "error",
                "deal: multiple deal.json project manifests found among the "
                    + "ancestor directories of entry file 'x'; exactly one "
                    + "ancestor manifest may govern a project",
                DiagnosticRange.synthetic("/tmp/v12/src/main.deal"),
                List.of(new DiagnosticNote("candidate manifest: /a/deal.json",
                        null),
                    new DiagnosticNote("candidate manifest: /a/src/deal.json",
                        null)),
                DiagnosticCode.E2010);
            String violation = V12FeatureGate.discoveryE2010Violation(
                multipleEntry, diagnostic);
            if (violation != null) {
                throw new AssertionError("unexpected violation: " + violation);
            }
        });

        check("the multiple-manifest check requires two candidate notes",
            () -> {
                CompilerDiagnostic diagnostic = new CompilerDiagnostic("E2010",
                    "error",
                    "deal: multiple deal.json project manifests found among "
                        + "the ancestor directories of entry file 'x'",
                    DiagnosticRange.synthetic("/tmp/v12/src/main.deal"),
                    List.of(new DiagnosticNote("candidate manifest: /a/deal.json",
                        null)),
                    DiagnosticCode.E2010);
                String violation = V12FeatureGate.discoveryE2010Violation(
                    multipleEntry, diagnostic);
                if (violation == null
                        || !violation.contains("at least two candidate")) {
                    throw new AssertionError("expected the candidate-note "
                        + "rejection, got: " + violation);
                }
            });
    }

    // =========================================================================
    // Catalog closure rules
    // =========================================================================

    private static void catalogClosure() throws IOException {
        System.out.println("=== Catalog closure ===");

        expectFailure("sidecar without source fails", "no fixture source",
            () -> {
                Path tmp = Files.createTempDirectory("v12closure-");
                Path dir = tmp.resolve("empty/record.sidecar.json");
                Files.createDirectories(dir.getParent());
                Files.writeString(dir, valid(), StandardCharsets.UTF_8);
                V12FeatureFixtureCatalog.load(tmp);
            });

        expectFailure("unreachable support-only source fails",
            "not reachable",
            () -> {
                Path tmp = Files.createTempDirectory("v12closure-");
                Path sidecar = tmp.resolve("root/record.sidecar.json");
                Files.createDirectories(sidecar.getParent().resolve("src"));
                Files.writeString(sidecar, valid(), StandardCharsets.UTF_8);
                Files.writeString(sidecar.getParent().resolve("src/main.deal"),
                    "export function main(): null { return null; }\n",
                    StandardCharsets.UTF_8);
                Files.createDirectories(tmp.resolve("orphan"));
                Files.writeString(tmp.resolve("orphan/tool.c"),
                    "int x;\n", StandardCharsets.UTF_8);
                V12FeatureFixtureCatalog.load(tmp);
            });

        expectFailure("an inject under the generated policy fails",
            "but manifestPolicy is generated",
            () -> {
                Path tmp = Files.createTempDirectory("v12closure-");
                Path sidecar = tmp.resolve("root/record.sidecar.json");
                Files.createDirectories(sidecar.getParent().resolve("src"));
                Files.writeString(sidecar, valid(), StandardCharsets.UTF_8);
                Files.writeString(sidecar.getParent().resolve("src/main.deal"),
                    "export function main(): null { return null; }\n",
                    StandardCharsets.UTF_8);
                Files.writeString(sidecar.getParent().resolve(
                    "manifest-inject.json"),
                    "{ \"languageVersion\": \"1.2\", \"backend\": \"lua\" }\n",
                    StandardCharsets.UTF_8);
                V12FeatureFixtureCatalog.load(tmp);
            });

        expectFailure("a fragment pin under the generated policy fails",
            "manifestPolicy generated forbids",
            () -> {
                Path tmp = Files.createTempDirectory("v12closure-");
                Path sidecar = tmp.resolve("root/record.sidecar.json");
                Files.createDirectories(sidecar.getParent().resolve("src"));
                Files.writeString(sidecar,
                    valid().replace("\"support\": []",
                        "\"support\": [],"
                            + " \"manifestErrorFragment\": \"frag\""),
                    StandardCharsets.UTF_8);
                Files.writeString(sidecar.getParent().resolve("src/main.deal"),
                    "export function main(): null { return null; }\n",
                    StandardCharsets.UTF_8);
                V12FeatureFixtureCatalog.load(tmp);
            });

        expectFailure("an inject policy without a root inject fails",
            "exactly one root manifest-inject",
            () -> {
                Path tmp = Files.createTempDirectory("v12closure-");
                Path sidecar = tmp.resolve("root/record.sidecar.json");
                Files.createDirectories(sidecar.getParent().resolve("src"));
                Files.writeString(sidecar,
                    injectPolicy("inject"), StandardCharsets.UTF_8);
                Files.writeString(sidecar.getParent().resolve("src/main.deal"),
                    "export function main(): null { return null; }\n",
                    StandardCharsets.UTF_8);
                V12FeatureFixtureCatalog.load(tmp);
            });

        expectFailure("a missing policy with an inject fails",
            "manifestPolicy missing writes no manifest",
            () -> {
                Path tmp = Files.createTempDirectory("v12closure-");
                Path sidecar = tmp.resolve("root/record.sidecar.json");
                Files.createDirectories(sidecar.getParent().resolve("src"));
                Files.writeString(sidecar,
                    injectPolicy("missing"), StandardCharsets.UTF_8);
                Files.writeString(sidecar.getParent().resolve("src/main.deal"),
                    "export function main(): null { return null; }\n",
                    StandardCharsets.UTF_8);
                Files.writeString(sidecar.getParent().resolve(
                    "manifest-inject.json"),
                    "{ \"languageVersion\": \"1.2\" }\n",
                    StandardCharsets.UTF_8);
                V12FeatureFixtureCatalog.load(tmp);
            });

        expectFailure("a multiple policy with a single inject fails",
            "at least two manifest-inject",
            () -> {
                Path tmp = Files.createTempDirectory("v12closure-");
                Path sidecar = tmp.resolve("root/record.sidecar.json");
                Files.createDirectories(sidecar.getParent().resolve("src"));
                Files.writeString(sidecar,
                    injectPolicy("multiple"), StandardCharsets.UTF_8);
                Files.writeString(sidecar.getParent().resolve("src/main.deal"),
                    "export function main(): null { return null; }\n",
                    StandardCharsets.UTF_8);
                Files.writeString(sidecar.getParent().resolve(
                    "manifest-inject.json"),
                    "{ \"languageVersion\": \"1.2\" }\n",
                    StandardCharsets.UTF_8);
                V12FeatureFixtureCatalog.load(tmp);
            });

        expectFailure("a native declaration missing from the record fails",
            "names a missing declaration",
            () -> {
                Path tmp = Files.createTempDirectory("v12closure-");
                Path sidecar = tmp.resolve("root/record.sidecar.json");
                Files.createDirectories(sidecar.getParent().resolve("src"));
                Files.writeString(sidecar,
                    cffiSidecarWithDeclaration("src/other.d.deal"),
                    StandardCharsets.UTF_8);
                Files.writeString(sidecar.getParent().resolve("src/main.deal"),
                    "export function main(): null { return null; }\n",
                    StandardCharsets.UTF_8);
                V12FeatureFixtureCatalog.load(tmp);
            });

        expectFailure("unresolved support path fails", "unresolved support path",
            () -> {
                Path tmp = Files.createTempDirectory("v12closure-");
                Path sidecar = tmp.resolve("root/record.sidecar.json");
                Files.createDirectories(sidecar.getParent().resolve("src"));
                Files.writeString(sidecar,
                    valid().replace("\"support\": []",
                        "\"support\": [\"nope\"]"),
                    StandardCharsets.UTF_8);
                Files.writeString(sidecar.getParent().resolve("src/main.deal"),
                    "export function main(): null { return null; }\n",
                    StandardCharsets.UTF_8);
                V12FeatureFixtureCatalog.load(tmp);
            });

        check("reachable support-only source loads", () -> {
            try {
                Path root = Files.createTempDirectory("v12closure-ok-");
                Path sidecar = root.resolve("root/record.sidecar.json");
                Files.createDirectories(sidecar.getParent().resolve("src"));
                Files.writeString(sidecar,
                    valid().replace("\"support\": []",
                        "\"support\": [\"tool\"]"),
                    StandardCharsets.UTF_8);
                Files.writeString(sidecar.getParent().resolve("src/main.deal"),
                    "export function main(): null { return null; }\n",
                    StandardCharsets.UTF_8);
                Files.createDirectories(root.resolve("tool"));
                Files.writeString(root.resolve("tool/probe.c"), "int x;\n",
                    StandardCharsets.UTF_8);
                V12FeatureFixtureCatalog catalog =
                    V12FeatureFixtureCatalog.load(root);
                if (catalog.records().size() != 1) {
                    throw new AssertionError("wrong record count");
                }
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });

        check("the committed corpus catalog is fully valid", () -> {
            V12FeatureFixtureCatalog catalog = V12FeatureFixtureCatalog.load(
                Path.of("test/features-iss0111").toAbsolutePath().normalize());
            List<String> ids = new ArrayList<>();
            for (var entry : catalog.records()) {
                ids.add(entry.id());
            }
            ids.sort(Comparator.naturalOrder());
            if (ids.size() != 43) {
                throw new AssertionError("committed corpus must carry 43 "
                    + "records, got " + ids);
            }
            if (!ids.contains("int32/truncating-arith/record")
                    || !ids.contains("c-ffi/duplicate-marker/record")
                    || !ids.contains("c-ffi/runtime-native/record")
                    || !ids.contains("c-ffi/linked-jvm-e6003/record")
                    || !ids.contains("c-ffi/unloadable-library/record")
                    || !ids.contains("c-ffi/missing-symbol/record")
                    || !ids.contains("c-ffi/classify-manifest-relative/record")
                    || !ids.contains("c-ffi/classify-bare/record")
                    || !ids.contains("project-config/out-of-root-import/record")
                    || !ids.contains("project-config/missing-manifest/record")
                    || !ids.contains("project-config/multiple-manifests/record")
                    || !ids.contains("project-config/identity-separation/record")
                    || !ids.contains("directives/anchor-break/record")
                    || !ids.contains("directives/anchor-attach/record")
                    || !ids.contains("bytes-core/buffer-ops/record")
                    || !ids.contains("bytes-core/write-post-state/record")
                    || !ids.contains("bytes-core/index-bounds-error/record")
                    || !ids.contains("bytes-core/write-range-error/record")
                    || !ids.contains("bytes-core/json-reject/record")
                    || !ids.contains("bytes-defaults/record")
                    || !ids.contains("bytes-defaults/out-of-root-provider/record")
                    || !ids.contains("bytes-defaults/cycle/record")
                    || !ids.contains("bytes-defaults/changed-provider/record")
                    || !ids.contains("bytes-descriptors/record")
                    || !ids.contains("bytes-descriptors/nullable/record")) {
                throw new AssertionError("missing committed record: " + ids);
            }
        });
    }


    // =========================================================================
    // Run-outcome containment rules (the review-cycle-2 javac-leg defect)
    // =========================================================================

    private static void runOutcomeRules() throws IOException {
        System.out.println("=== Run-outcome containment rules ===");
        check("clean target success is not rejected", () -> {
            if (V12FeatureGate.toolRunRejected(new V12FeatureGate.RunOutcome(
                    0, "ok", "", "-", true))) {
                throw new AssertionError("exit 0 with a clean REPORT token "
                    + "must not be rejected");
            }
        });
        check("nonzero target exit is rejected", () -> {
            if (!V12FeatureGate.toolRunRejected(new V12FeatureGate.RunOutcome(
                    1, "out", "err", "-", true))) {
                throw new AssertionError("a nonzero target exit must be "
                    + "rejected");
            }
        });
        check("containment failure with a clean target exit is rejected",
            () -> {
                // The pinned regression from review cycle 2 finding 5:
                // GROUP_SURVIVOR/ADOPTED_SURVIVOR after javac itself
                // exited 0 is still a hard failure — the javac leg must
                // never swallow containmentFailure().
                if (!V12FeatureGate.toolRunRejected(
                        new V12FeatureGate.RunOutcome(0, "out", "err",
                            "GROUP_SURVIVOR", true))) {
                    throw new AssertionError("a clean target exit under a "
                        + "survivor REPORT token must be rejected");
                }
            });
        check("unclean launcher exit is rejected", () -> {
            if (!V12FeatureGate.toolRunRejected(new V12FeatureGate.RunOutcome(
                    0, "out", "err", "-", false))) {
                throw new AssertionError("an unclean launcher exit must be "
                    + "rejected");
            }
        });
        check("async-export dispatch accepts luajit", () -> {
            if (V12FeatureGate.asyncExportBackendViolation("luajit")
                    != null) {
                throw new AssertionError("the LuaJIT host ABI must be "
                    + "the accepted async-export backend");
            }
        });
        check("async-export dispatch hard-fails a backend without a host "
                + "invoker", () -> {
            String violation = V12FeatureGate.asyncExportBackendViolation(
                "jvm");
            if (violation == null
                    || !violation.contains("no production host invoker")
                    || !violation.contains("refusing to substitute")) {
                throw new AssertionError("a JVM async-export leg must "
                    + "fail closed, got: " + violation);
            }
        });
        check("containmentFailure flag semantics", () -> {
            if (new V12FeatureGate.RunOutcome(0, "", "", "-",
                    true).containmentFailure()) {
                throw new AssertionError("clean token + clean exit must not "
                    + "flag containment failure");
            }
            if (!new V12FeatureGate.RunOutcome(0, "", "", "MALFORMED_REPORT",
                    true).containmentFailure()) {
                throw new AssertionError("a non-dash REPORT token must flag "
                    + "containment failure");
            }
            if (!new V12FeatureGate.RunOutcome(0, "", "", "-",
                    false).containmentFailure()) {
                throw new AssertionError("an unclean launcher exit must flag "
                    + "containment failure");
            }
        });
    }

    // =========================================================================
    // Identity-artifact pins (public/private identity separation, D4-D6)
    // =========================================================================

    private static void identityArtifactRules() throws IOException {
        System.out.println("=== Identity-artifact pins ===");
        check("identityArtifact parses a canonical descriptor", () -> {
            V12FeatureMetadata record = V12FeatureMetadata.parse(
                "x.sidecar.json",
                valid().replace("\"expected\": { \"compile-error\": \"E1036\" }",
                    "\"expected\": \"runtime-ok\"")
                    .replace("\"invocation\": \"compile-only\"",
                        "\"invocation\": \"direct-main\"")
                    .replace("\"support\": []",
                        "\"support\": [], \"identityArtifact\": "
                            + "{\"classDescriptor\": \"@src/main/Widget\"}"));
            if (record.identityArtifact() == null
                    || !record.identityArtifact().classDescriptor()
                        .equals("@src/main/Widget")) {
                throw new AssertionError("identityArtifact not parsed");
            }
        });
        expectFailure("identityArtifact rejects a non-object",
            "malformed identityArtifact",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("\"support\": []",
                    "\"support\": [], \"identityArtifact\": \"@src/main/W\"")));
        expectFailure("identityArtifact rejects extra members",
            "malformed identityArtifact",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("\"support\": []",
                    "\"support\": [], \"identityArtifact\": "
                        + "{\"classDescriptor\": \"@src/main/W\","
                        + " \"bogus\": 1}")));
        expectFailure("identityArtifact rejects an empty descriptor",
            "@-prefixed",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("\"support\": []",
                    "\"support\": [], \"identityArtifact\": "
                        + "{\"classDescriptor\": \"\"}")));
        expectFailure("identityArtifact rejects a non-@ descriptor",
            "@-prefixed",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("\"support\": []",
                    "\"support\": [], \"identityArtifact\": "
                        + "{\"classDescriptor\": \"src/main/Widget\"}")));
        expectFailure("identityArtifact with a compile-error expectation fails",
            "compile-ok or runtime-ok",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("\"support\": []",
                    "\"support\": [], \"identityArtifact\": "
                        + "{\"classDescriptor\": \"@src/main/W\"}")));
        expectFailure("identityArtifact with an inject policy fails",
            "compile-ok or runtime-ok",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                injectPolicy("inject")
                    .replace("\"manifestErrorFragment\": \"frag\"",
                        "\"manifestErrorFragment\": \"frag\","
                            + " \"identityArtifact\": "
                            + "{\"classDescriptor\": \"@src/main/W\"}")));

        check("providerVariants parses the closed block", () -> {
            V12FeatureMetadata record = V12FeatureMetadata.parse(
                "x.sidecar.json",
                valid().replace("\"feature\": \"signed-int32\"",
                    "\"feature\": \"bytes-defaults\"")
                    .replace("\"expected\": { \"compile-error\": \"E1036\" }",
                        "\"expected\": \"runtime-ok\"")
                    .replace("\"invocation\": \"compile-only\"",
                        "\"invocation\": \"synthetic-main\"")
                    .replace("\"support\": []",
                        "\"support\": [], \"oracle\": {\"exportName\": \"o\","
                            + " \"functionDescriptor\": \"()->int\"},"
                            + " \"providerVariants\": {\"providerPath\": "
                            + "\"shared/provider.deal\","
                            + " \"variantA\": \"shared/a.deal\","
                            + " \"variantB\": \"shared/b.deal\","
                            + " \"oracleResults\": [42, 24]}"));
            if (record.providerVariants() == null
                    || !record.providerVariants().oracleResults()
                        .equals(java.util.List.of(42, 24))) {
                throw new AssertionError("providerVariants not parsed: "
                    + record.providerVariants());
            }
        });
        expectFailure("providerVariants with a wrong feature fails",
            "bytes-defaults only",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                providerVariantRecord("signed-int32")));
        expectFailure("providerVariants with a compile-error expectation "
                + "fails", "runtime-ok expectation",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                valid().replace("\"feature\": \"signed-int32\"",
                    "\"feature\": \"bytes-defaults\"")
                    .replace("\"support\": []",
                        "\"support\": [], \"providerVariants\": "
                            + providerVariantsBlock())));

        expectFailure("providerVariants with direct-main fails",
            "synthetic-main",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                providerVariantRecord("bytes-defaults")
                    .replace("\"invocation\": \"synthetic-main\"",
                        "\"invocation\": \"direct-main\"")));
        expectFailure("providerVariants with a non-int oracle fails",
            "()->int oracle",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                providerVariantRecord("bytes-defaults")
                    .replace("\"()->int\"", "\"()->null\"")));
        expectFailure("providerVariants with one result fails",
            "exactly two integers",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                providerVariantRecord("bytes-defaults")
                    .replace("\"oracleResults\": [42, 24]",
                        "\"oracleResults\": [42]")));
        expectFailure("providerVariants with a non-integer result fails",
            "JSON integers",
            () -> V12FeatureMetadata.parse("x.sidecar.json",
                providerVariantRecord("bytes-defaults")
                    .replace("\"oracleResults\": [42, 24]",
                        "\"oracleResults\": [42, 2.5]")));

        Path tmp = Files.createTempDirectory("v12identity-");
        check("artifact pin accepts a descriptor without private text",
            () -> {
                Path artifact = tmp.resolve("main.lua");
                Files.writeString(artifact,
                    "local d = \"@src/main/Widget\"\n",
                    StandardCharsets.UTF_8);
                String violation = V12FeatureGate.identityArtifactViolation(
                    tmp, "@src/main/Widget",
                    "0123456789abcdef0123456789abcdef0123456789abcdef"
                        + "0123456789abcdef",
                    "file:///tmp/nowhere/deal.json");
                if (violation != null) {
                    throw new AssertionError("clean artifacts reported: "
                        + violation);
                }
            });
        check("artifact pin fails when the descriptor is absent", () -> {
            Path out = Files.createTempDirectory("v12identity-missing-");
            Files.writeString(out.resolve("main.lua"), "local x = 1\n",
                StandardCharsets.UTF_8);
            String violation = V12FeatureGate.identityArtifactViolation(
                out, "@src/main/Widget", null, null);
            if (violation == null
                    || !violation.contains("carries the pinned")) {
                throw new AssertionError("missing descriptor must violate: "
                    + violation);
            }
        });
        check("artifact pin fails when the private digest leaks", () -> {
            Path out = Files.createTempDirectory("v12identity-digest-");
            Files.writeString(out.resolve("main.lua"),
                "local d = \"@src/main/Widget\"\n"
                    + "local m = \"0123456789abcdef0123456789abcdef"
                    + "0123456789abcdef0123456789abcdef\"\n",
                StandardCharsets.UTF_8);
            String violation = V12FeatureGate.identityArtifactViolation(
                out, "@src/main/Widget",
                "0123456789abcdef0123456789abcdef0123456789abcdef"
                    + "0123456789abcdef",
                "file:///tmp/nowhere/deal.json");
            if (violation == null
                    || !violation.contains("digest appears")) {
                throw new AssertionError("a digest leak must violate: "
                    + violation);
            }
        });
        check("artifact pin fails when the manifest URI leaks", () -> {
            Path out = Files.createTempDirectory("v12identity-uri-");
            Files.writeString(out.resolve("main.lua"),
                "local d = \"@src/main/Widget\"\n"
                    + "local m = \"file:///tmp/nowhere/deal.json\"\n",
                StandardCharsets.UTF_8);
            String violation = V12FeatureGate.identityArtifactViolation(
                out, "@src/main/Widget",
                "0123456789abcdef0123456789abcdef0123456789abcdef"
                    + "0123456789abcdef",
                "file:///tmp/nowhere/deal.json");
            if (violation == null
                    || !violation.contains("manifest URI appears")) {
                throw new AssertionError("a manifest URI leak must violate: "
                    + violation);
            }
        });
    }

}
