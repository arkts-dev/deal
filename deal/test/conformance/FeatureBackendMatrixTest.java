package deal.test.conformance;

import deal.test.conformance.FeatureBackendMatrix.MatrixFailure;
import deal.test.conformance.V12FeatureMetadata.FeatureId;
import deal.test.conformance.V12FeatureMetadata.Metadata;
import deal.test.conformance.V12FeatureMetadata.ParseResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The dedicated unit battery of the architecture-owned feature/backend
 * matrix (ISSUE-0157, {@code FeatureBackendMatrix}): per-record row
 * validation for every {@link FeatureId}, the C_FFI linked-record pair
 * rules, and the catalog-level completeness/coverage/link rules.
 *
 * <p>Each positive pins that the matrix accepts the record; each
 * negative pins the failing field and a reason fragment. All documents
 * are metadata-valid (the {@link V12FeatureMetadata} conditional rules
 * are preconditions), so every failure here is a genuine matrix
 * failure.</p>
 */
public class FeatureBackendMatrixTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static Metadata parse(String json) {
        ParseResult result = V12FeatureMetadata.parse(json);
        if (result.failure().isPresent()) {
            throw new IllegalStateException("fixture metadata must be valid: "
                + result.failure().get().message());
        }
        return result.metadata().orElseThrow();
    }

    private static void checkOk(String recordId, Metadata m) {
        check(FeatureBackendMatrix.validateRecord(recordId, m).isEmpty(),
            "expected matrix-accept for " + recordId + ", got "
                + FeatureBackendMatrix.validateRecord(recordId, m)
                    .map(MatrixFailure::message).orElse("<ok>"));
    }

    private static void checkFail(String recordId, Metadata m, String field,
            String reasonFragment) {
        Optional<MatrixFailure> failure =
            FeatureBackendMatrix.validateRecord(recordId, m);
        check(failure.isPresent(), "expected matrix failure for " + recordId);
        MatrixFailure f = failure.orElse(null);
        if (f != null) {
            check(field.equals(f.field()),
                "expected field " + field + ", got " + f.field()
                    + " (" + f.reason() + ")");
            check(f.reason().contains(reasonFragment),
                "expected reason containing \"" + reasonFragment
                    + "\", got \"" + f.reason() + "\"");
        }
    }

    // =========================================================================
    // Record builders
    // =========================================================================

    private static String runtimeRecord(FeatureId feature, String expected,
            String invocation, String backends, String linked) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"version\": 1, \"feature\": \"").append(feature.jsonText())
            .append("\", \"spec\": \"x\", \"description\": \"y\", ");
        if ("runtime-ok".equals(expected) || "compile-ok".equals(expected)) {
            sb.append("\"expected\": \"").append(expected).append("\", ");
        } else if (expected.startsWith("compile-error ")) {
            sb.append("\"expected\": {\"compile-error\": \"")
                .append(expected.substring("compile-error ".length()))
                .append("\"}, ");
        } else {
            sb.append("\"expected\": {\"runtime-error\": \"")
                .append(expected.substring("runtime-error ".length()))
                .append("\"}, ");
        }
        sb.append("\"backends\": ").append(backends)
            .append(", \"invocation\": \"").append(invocation).append("\", ");
        if (invocation.contains("synthetic")) {
            sb.append("\"oracle\": {\"exportName\": \"o\", "
                + "\"functionDescriptor\": \"()->int\"}, ");
        } else if (invocation.contains("async")) {
            sb.append("\"oracle\": {\"exportName\": \"o\", "
                + "\"functionDescriptor\": \"async()->null\"}, ");
        }
        sb.append("\"support\": []");
        if (linked != null) {
            sb.append(", \"linkedRecord\": \"").append(linked).append("\"");
        }
        sb.append('}');
        return sb.toString();
    }

    public static void main(String[] args) {
        System.out.println("-- FeatureBackendMatrix: runtime-parity row --");
        checkOk("signed-int32/ok", parse(runtimeRecord(FeatureId.SIGNED_INT32,
            "runtime-ok", "direct-main", "[\"luajit\", \"jvm\"]", null)));
        checkOk("bytes-core/ok", parse(runtimeRecord(FeatureId.BYTES_CORE,
            "runtime-ok", "direct-main", "[\"luajit\", \"jvm\"]", null)));
        checkOk("bytes-defaults/ok", parse(runtimeRecord(FeatureId.BYTES_DEFAULTS,
            "runtime-error E8012", "synthetic-main", "[\"luajit\", \"jvm\"]",
            null)));
        checkOk("bytes-descriptors/ok", parse(runtimeRecord(
            FeatureId.BYTES_DESCRIPTORS, "runtime-ok", "synthetic-main",
            "[\"luajit\", \"jvm\"]", null)));
        checkOk("bytes-sync-function/ok", parse(runtimeRecord(
            FeatureId.BYTES_SYNC_FUNCTION, "runtime-ok", "direct-main",
            "[\"luajit\", \"jvm\"]", null)));
        checkFail("int/omit-jvm", parse(runtimeRecord(FeatureId.SIGNED_INT32,
            "runtime-ok", "direct-main", "[\"luajit\"]", null)),
            "backends", "exactly LuaJIT and JVM");
        checkFail("int/omit-luajit", parse(runtimeRecord(FeatureId.SIGNED_INT32,
            "runtime-ok", "direct-main", "[\"jvm\"]", null)),
            "backends", "exactly LuaJIT and JVM");
        checkFail("bytes/compile-outcome", parse(runtimeRecord(
            FeatureId.BYTES_CORE, "compile-ok", "compile-only",
            "[\"luajit\", \"jvm\"]", null)),
            "expected", "requires a runtime outcome");
        checkFail("bytes/async-invocation", parse(runtimeRecord(
            FeatureId.BYTES_CORE, "runtime-ok", "async-export",
            "[\"luajit\", \"jvm\"]", null)),
            "invocation", "permits direct-main/synthetic-main");
        checkFail("bytes/linked", parse(runtimeRecord(FeatureId.BYTES_CORE,
            "runtime-ok", "direct-main", "[\"luajit\", \"jvm\"]",
            "other-record")),
            "linkedRecord", "forbids a linked record");

        System.out.println("-- FeatureBackendMatrix: async-bytes row --");
        checkOk("bytes-async/ok", parse(runtimeRecord(
            FeatureId.BYTES_ASYNC_FUNCTION, "runtime-ok", "async-export",
            "[\"luajit\", \"jvm\"]", null)));
        checkOk("bytes-async/runtime-error", parse(runtimeRecord(
            FeatureId.BYTES_ASYNC_FUNCTION, "runtime-error E8012",
            "async-export", "[\"luajit\", \"jvm\"]", null)));
        checkFail("bytes-async/direct-main", parse(runtimeRecord(
            FeatureId.BYTES_ASYNC_FUNCTION, "runtime-ok", "direct-main",
            "[\"luajit\", \"jvm\"]", null)),
            "invocation", "requires exactly async-export");
        checkFail("bytes-async/synthetic-main", parse(runtimeRecord(
            FeatureId.BYTES_ASYNC_FUNCTION, "runtime-ok", "synthetic-main",
            "[\"luajit\", \"jvm\"]", null)),
            "invocation", "requires exactly async-export");
        checkFail("bytes-async/omit-jvm", parse(runtimeRecord(
            FeatureId.BYTES_ASYNC_FUNCTION, "runtime-ok", "async-export",
            "[\"luajit\"]", null)),
            "backends", "exactly LuaJIT and JVM");

        System.out.println("-- FeatureBackendMatrix: compiler-feature row --");
        checkOk("directives/compile-ok", parse(runtimeRecord(
            FeatureId.DIRECTIVES, "compile-ok", "compile-only",
            "[\"luajit\", \"jvm\"]", null)));
        checkOk("diagnostic-range/compile-error", parse(runtimeRecord(
            FeatureId.DIAGNOSTIC_RANGE, "compile-error E1036", "compile-only",
            "[\"luajit\", \"jvm\"]", null)));
        checkOk("project-config/runtime", parse(runtimeRecord(
            FeatureId.PROJECT_CONFIG, "runtime-ok", "direct-main",
            "[\"luajit\", \"jvm\"]", null)));
        checkFail("directives/omit-jvm", parse(runtimeRecord(
            FeatureId.DIRECTIVES, "compile-ok", "compile-only",
            "[\"luajit\"]", null)),
            "backends", "each applicable backend pipeline");
        checkFail("directives/synthetic", parse(runtimeRecord(
            FeatureId.DIRECTIVES, "runtime-ok", "synthetic-main",
            "[\"luajit\", \"jvm\"]", null)),
            "invocation", "permits direct-main");
        checkFail("project-config/async", parse(runtimeRecord(
            FeatureId.PROJECT_CONFIG, "runtime-ok", "async-export",
            "[\"luajit\", \"jvm\"]", null)),
            "invocation", "permits direct-main");

        System.out.println("-- FeatureBackendMatrix: C_FFI row --");
        checkOk("c-ffi/luajit-runtime", parse(runtimeRecord(FeatureId.C_FFI,
            "runtime-ok", "direct-main", "[\"luajit\"]", "c-ffi/jvm")));
        checkOk("c-ffi/synthetic", parse(runtimeRecord(FeatureId.C_FFI,
            "runtime-ok", "synthetic-main", "[\"luajit\"]", "c-ffi/jvm")));
        checkOk("c-ffi/jvm-rejection", parse("""
            {"version": 1, "feature": "C_FFI", "spec": "x", "description": "y",
             "expected": {"compile-error": "E6003"}, "backends": ["jvm"],
             "invocation": "compile-only", "support": []}
            """));
        checkFail("c-ffi/jvm-runtime", parse(runtimeRecord(FeatureId.C_FFI,
            "runtime-ok", "direct-main", "[\"jvm\"]", "c-ffi/jvm")),
            "backends", "runs exactly on LuaJIT");
        checkFail("c-ffi/both-backends", parse(runtimeRecord(FeatureId.C_FFI,
            "runtime-ok", "direct-main", "[\"luajit\", \"jvm\"]",
            "c-ffi/jvm")),
            "backends", "runs exactly on LuaJIT");
        checkFail("c-ffi/missing-link", parse(runtimeRecord(FeatureId.C_FFI,
            "runtime-ok", "direct-main", "[\"luajit\"]", null)),
            "linkedRecord", "must link the JVM");
        checkFail("c-ffi/async", parse(runtimeRecord(FeatureId.C_FFI,
            "runtime-ok", "async-export", "[\"luajit\"]", "c-ffi/jvm")),
            "invocation", "permits direct-main/synthetic-main");
        checkFail("c-ffi/wrong-reject-code", parse("""
            {"version": 1, "feature": "C_FFI", "spec": "x", "description": "y",
             "expected": {"compile-error": "E7002"}, "backends": ["jvm"],
             "invocation": "compile-only", "support": []}
            """), "expected", "must pin exactly E6003");
        checkFail("c-ffi/compile-ok", parse(runtimeRecord(FeatureId.C_FFI,
            "compile-ok", "compile-only", "[\"jvm\"]", null)),
            "expected", "neither");
        checkFail("c-ffi/reject-linked", parse("""
            {"version": 1, "feature": "C_FFI", "spec": "x", "description": "y",
             "expected": {"compile-error": "E6003"}, "backends": ["jvm"],
             "invocation": "compile-only", "support": [],
             "linkedRecord": "other"}
            """), "linkedRecord", "carries no linkedRecord of its own");

        System.out.println("-- FeatureBackendMatrix: C_FFI_DECLARATION_ERROR row --");
        checkOk("c-ffi-decl-error/both", parse(runtimeRecord(
            FeatureId.C_FFI_DECLARATION_ERROR, "compile-error E7002",
            "compile-only", "[\"luajit\", \"jvm\"]", null)));
        checkOk("c-ffi-decl-error/jvm-only", parse(runtimeRecord(
            FeatureId.C_FFI_DECLARATION_ERROR, "compile-error E7002",
            "compile-only", "[\"jvm\"]", null)));
        checkOk("c-ffi-decl-error/luajit-only", parse(runtimeRecord(
            FeatureId.C_FFI_DECLARATION_ERROR, "compile-error E7002",
            "compile-only", "[\"luajit\"]", null)));
        checkFail("c-ffi-decl-error/runtime", parse(runtimeRecord(
            FeatureId.C_FFI_DECLARATION_ERROR, "runtime-ok", "direct-main",
            "[\"luajit\", \"jvm\"]", null)),
            "expected", "requires compile-error");
        checkFail("c-ffi-decl-error/linked", parse("""
            {"version": 1, "feature": "C_FFI_DECLARATION_ERROR", "spec": "x",
             "description": "y", "expected": {"compile-error": "E7002"},
             "backends": ["luajit", "jvm"], "invocation": "compile-only",
             "support": [], "linkedRecord": "other"}
            """), "linkedRecord", "carries no linked record");

        System.out.println("-- FeatureBackendMatrix: catalog-level rules --");
        // Empty catalog: every feature omitted.
        List<MatrixFailure> empty =
            FeatureBackendMatrix.validateCatalog(Map.of());
        check(!empty.isEmpty(), "empty catalog fails");
        check(empty.stream().anyMatch(f -> f.field().equals("feature")
                && f.reason().contains("SIGNED_INT32")),
            "empty catalog reports SIGNED_INT32 omission");

        // Missing link target.
        Map<String, Metadata> missingTarget = new LinkedHashMap<>();
        missingTarget.put("c-ffi/luajit", parse(runtimeRecord(FeatureId.C_FFI,
            "runtime-ok", "direct-main", "[\"luajit\"]", "c-ffi/nope")));
        List<MatrixFailure> missingTargetFailures =
            FeatureBackendMatrix.validateCatalog(missingTarget);
        check(missingTargetFailures.stream().anyMatch(f ->
                f.field().equals("linkedRecord")
                    && f.reason().contains("does not exist")),
            "missing linked record fails: " + missingTargetFailures);

        // Link to a non-rejection record.
        Map<String, Metadata> wrongTarget = new LinkedHashMap<>();
        wrongTarget.put("c-ffi/luajit", parse(runtimeRecord(FeatureId.C_FFI,
            "runtime-ok", "direct-main", "[\"luajit\"]", "c-ffi/jvm")));
        wrongTarget.put("c-ffi/jvm", parse(runtimeRecord(FeatureId.C_FFI,
            "runtime-ok", "direct-main", "[\"jvm\"]", null)));
        List<MatrixFailure> wrongTargetFailures =
            FeatureBackendMatrix.validateCatalog(wrongTarget);
        check(wrongTargetFailures.stream().anyMatch(f ->
                f.field().equals("linkedRecord")
                    && f.reason().contains("is not the JVM compile-error")),
            "link to non-rejection record fails: " + wrongTargetFailures);

        // Unlinked rejection record.
        Map<String, Metadata> unlinkedReject = new LinkedHashMap<>();
        unlinkedReject.put("c-ffi/luajit", parse(runtimeRecord(FeatureId.C_FFI,
            "runtime-ok", "direct-main", "[\"luajit\"]", "c-ffi/other")));
        unlinkedReject.put("c-ffi/other", parse("""
            {"version": 1, "feature": "C_FFI", "spec": "x", "description": "y",
             "expected": {"compile-error": "E6003"}, "backends": ["jvm"],
             "invocation": "compile-only", "support": []}
            """));
        unlinkedReject.put("c-ffi/orphan", parse("""
            {"version": 1, "feature": "C_FFI", "spec": "x", "description": "y",
             "expected": {"compile-error": "E6003"}, "backends": ["jvm"],
             "invocation": "compile-only", "support": []}
            """));
        List<MatrixFailure> unlinkedFailures =
            FeatureBackendMatrix.validateCatalog(unlinkedReject);
        check(unlinkedFailures.stream().anyMatch(f ->
                f.recordId().equals("c-ffi/orphan")
                    && f.reason().contains("not the target")),
            "unlinked rejection record fails: " + unlinkedFailures);

        // Declaration-error pipeline coverage: luajit-only records.
        Map<String, Metadata> luajitOnly = new LinkedHashMap<>();
        luajitOnly.put("decl/a", parse(runtimeRecord(
            FeatureId.C_FFI_DECLARATION_ERROR, "compile-error E7002",
            "compile-only", "[\"luajit\"]", null)));
        List<MatrixFailure> coverageFailures =
            FeatureBackendMatrix.validateCatalog(luajitOnly);
        check(coverageFailures.stream().anyMatch(f ->
                f.field().equals("backends")
                    && f.reason().contains("both backend pipelines")),
            "declaration-error pipeline coverage fails: " + coverageFailures);

        // A fully valid catalog pair validates clean at the C_FFI level
        // (other features still omitted — that is reported, not
        // silently passed).
        Map<String, Metadata> pair = new LinkedHashMap<>();
        pair.put("c-ffi/luajit", parse(runtimeRecord(FeatureId.C_FFI,
            "runtime-ok", "direct-main", "[\"luajit\"]", "c-ffi/jvm")));
        pair.put("c-ffi/jvm", parse("""
            {"version": 1, "feature": "C_FFI", "spec": "x", "description": "y",
             "expected": {"compile-error": "E6003"}, "backends": ["jvm"],
             "invocation": "compile-only", "support": []}
            """));
        List<MatrixFailure> pairFailures =
            FeatureBackendMatrix.validateCatalog(pair);
        check(pairFailures.stream().noneMatch(f ->
                f.recordId().startsWith("c-ffi/")),
            "valid C_FFI pair has no C_FFI failures: " + pairFailures);
        check(pairFailures.stream().anyMatch(f ->
                f.field().equals("feature")),
            "valid C_FFI pair still reports the other omitted features: "
                + pairFailures);

        System.out.println();
        if (failed > 0) {
            System.err.println("FeatureBackendMatrixTest: " + passed
                + " passed, " + failed + " failed");
            System.exit(1);
        }
        System.out.println("FeatureBackendMatrixTest: " + passed + " passed, 0 failed");
    }
}
