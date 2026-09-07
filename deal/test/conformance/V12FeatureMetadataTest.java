package deal.test.conformance;

import deal.test.conformance.V12FeatureMetadata.ExpectedOutcome;
import deal.test.conformance.V12FeatureMetadata.FeatureId;
import deal.test.conformance.V12FeatureMetadata.Invocation;
import deal.test.conformance.V12FeatureMetadata.Metadata;
import deal.test.conformance.V12FeatureMetadata.Oracle;
import deal.test.conformance.V12FeatureMetadata.ParseFailure;
import deal.test.conformance.V12FeatureMetadata.ParseResult;

import java.util.List;
import java.util.Optional;

/**
 * The dedicated unit battery of the strict v1.2 feature-record metadata
 * parser (ISSUE-0157, {@code V12FeatureMetadata}): exact field parsing,
 * the closed schema-v1 field set, the exact conditional invocation/
 * oracle rules, and the E4 strict canonical descriptor surface of
 * oracle signatures.
 *
 * <p>The suite is standalone: every document is a literal string, no
 * I/O, deterministic. Each positive pins the exact parsed field values;
 * each negative pins the offending field and a reason fragment.</p>
 */
public class V12FeatureMetadataTest {

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

    private static void checkFail(ParseResult result, String field,
            String reasonFragment) {
        check(result.wellFormed(), "result must be well-formed: " + result);
        check(result.failure().isPresent(), "expected a parse failure: " + result);
        ParseFailure failure = result.failure().orElse(null);
        if (failure != null) {
            check(field.equals(failure.field()),
                "expected field " + field + ", got " + failure.field()
                    + " (" + failure.reason() + ")");
            check(failure.reason().contains(reasonFragment),
                "expected reason containing \"" + reasonFragment
                    + "\", got \"" + failure.reason() + "\"");
        }
    }

    private static Metadata ok(String json) {
        ParseResult result = V12FeatureMetadata.parse(json);
        check(result.metadata().isPresent(),
            "expected valid metadata, got failure: "
                + result.failure().map(ParseFailure::message).orElse("<none>"));
        return result.metadata().orElse(null);
    }

    // =========================================================================
    // Positive matrix — exact parsed values
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("-- V12FeatureMetadata: positive matrix --");
        Metadata runtime = ok("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "spec-v1.2.md §Bytes",
             "description": "bytes ops", "expected": "runtime-ok",
             "backends": ["luajit", "jvm"], "invocation": "direct-main",
             "support": []}
            """);
        if (runtime != null) {
            check(runtime.feature() == FeatureId.BYTES_CORE, "feature parse");
            check("spec-v1.2.md §Bytes".equals(runtime.spec()), "spec parse");
            check("bytes ops".equals(runtime.description()), "description parse");
            check(runtime.expected() instanceof ExpectedOutcome.RuntimeOk,
                "runtime-ok parse");
            check(runtime.backends().equals(List.of("luajit", "jvm")),
                "backends parse");
            check(runtime.invocation() == Invocation.DIRECT_MAIN,
                "invocation parse");
            check(runtime.oracle().isEmpty(), "no oracle");
            check(runtime.support().isEmpty(), "empty support");
            check(runtime.linkedRecord().isEmpty(), "no linkedRecord");
        }

        Metadata compile = ok("""
            {"version": 1, "feature": "DIRECTIVES", "spec": "spec-v1.2.md §Comment semantics",
             "description": "directives", "expected": {"compile-error": "E1045"},
             "backends": ["luajit", "jvm"], "invocation": "compile-only",
             "support": []}
            """);
        if (compile != null) {
            check(compile.feature() == FeatureId.DIRECTIVES, "feature parse 2");
            check(compile.expected() instanceof ExpectedOutcome.CompileError e
                    && "E1045".equals(e.code()), "compile-error code parse");
            check(compile.invocation() == Invocation.COMPILE_ONLY,
                "compile-only parse");
        }

        Metadata runtimeErr = ok("""
            {"version": 1, "feature": "SIGNED_INT32", "spec": "spec-v1.2.md §Numeric overflow",
             "description": "overflow", "expected": {"runtime-error": "E8004"},
             "backends": ["luajit", "jvm"], "invocation": "direct-main",
             "support": ["signed-int32/helper.deal"]}
            """);
        if (runtimeErr != null) {
            check(runtimeErr.expected() instanceof ExpectedOutcome.RuntimeError e
                    && "E8004".equals(e.code()), "runtime-error code parse");
            check(runtimeErr.support().equals(List.of("signed-int32/helper.deal")),
                "support parse");
        }

        Metadata synthetic = ok("""
            {"version": 1, "feature": "BYTES_DEFAULTS", "spec": "spec-v1.2.md §Classes",
             "description": "defaults", "expected": "runtime-ok",
             "backends": ["luajit", "jvm"], "invocation": "synthetic-main",
             "oracle": {"exportName": "defaultsOracle",
                        "functionDescriptor": "()->int"},
             "support": []}
            """);
        if (synthetic != null) {
            Oracle oracle = synthetic.oracle().orElseThrow();
            check("defaultsOracle".equals(oracle.exportName()), "oracle export name");
            check("()->int".equals(oracle.functionDescriptor()),
                "oracle descriptor text");
            check(!oracle.functionAtom().isAsync()
                    && oracle.functionAtom().params().isEmpty(),
                "oracle sync zero-parameter atom");
            check("int".equals(oracle.returnDescriptor()),
                "oracle return descriptor int");
        }

        Metadata asyncExport = ok("""
            {"version": 1, "feature": "BYTES_ASYNC_FUNCTION", "spec": "spec-v1.2.md §Async/Await",
             "description": "async bytes", "expected": "runtime-ok",
             "backends": ["luajit", "jvm"], "invocation": "async-export",
             "oracle": {"exportName": "oracle",
                        "functionDescriptor": "async()->null"},
             "support": []}
            """);
        if (asyncExport != null) {
            Oracle oracle = asyncExport.oracle().orElseThrow();
            check(oracle.functionAtom().isAsync()
                    && oracle.functionAtom().params().isEmpty(),
                "oracle async zero-parameter atom");
            check("null".equals(oracle.returnDescriptor()),
                "oracle return descriptor null");
        }

        Metadata arrayReturn = ok("""
            {"version": 1, "feature": "BYTES_SYNC_FUNCTION", "spec": "spec-v1.2.md §Function values",
             "description": "sync bytes fn", "expected": "runtime-ok",
             "backends": ["luajit", "jvm"], "invocation": "synthetic-main",
             "oracle": {"exportName": "syncFunctionOracle",
                        "functionDescriptor": "()->[bytes]"},
             "support": ["bytes-core/helper/bytes-ops.deal",
                         "bytes-defaults/defaults-synthetic.deal"]}
            """);
        if (arrayReturn != null) {
            Oracle oracle = arrayReturn.oracle().orElseThrow();
            check("[bytes]".equals(oracle.returnDescriptor()),
                "oracle recursive return descriptor [bytes]");
        }

        Metadata linked = ok("""
            {"version": 1, "feature": "C_FFI", "spec": "spec-v1.2.md §C FFI runtime semantics",
             "description": "c ffi", "expected": "runtime-ok",
             "backends": ["luajit"], "invocation": "direct-main",
             "support": [], "linkedRecord": "c-ffi/jvm-unsupported"}
            """);
        if (linked != null) {
            check(linked.linkedRecord().isPresent()
                    && "c-ffi/jvm-unsupported".equals(
                        linked.linkedRecord().orElse(null)),
                "linkedRecord parse");
        }

        // =========================================================================
        System.out.println("-- V12FeatureMetadata: schema negatives --");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": "runtime-ok", "backends": ["luajit", "jvm"],
             "invocation": "direct-main", "support": [], "extra": true}
            """), "extra", "unknown field");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 2, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": "runtime-ok", "backends": ["luajit", "jvm"],
             "invocation": "direct-main", "support": []}
            """), "version", "unknown feature metadata schema version");

        checkFail(V12FeatureMetadata.parse("""
            {"feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": "runtime-ok", "backends": ["luajit", "jvm"],
             "invocation": "direct-main", "support": []}
            """), "version", "missing mandatory version");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "NOT_A_FEATURE", "spec": "x",
             "description": "y", "expected": "runtime-ok",
             "backends": ["luajit", "jvm"], "invocation": "direct-main",
             "support": []}
            """), "feature", "unknown feature");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "", "description": "y",
             "expected": "runtime-ok", "backends": ["luajit", "jvm"],
             "invocation": "direct-main", "support": []}
            """), "spec", "non-empty");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": 42, "backends": ["luajit", "jvm"],
             "invocation": "direct-main", "support": []}
            """), "expected", "must be the string");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": "runtime-maybe", "backends": ["luajit", "jvm"],
             "invocation": "direct-main", "support": []}
            """), "expected", "unknown expectation");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": {"compile-error": "E1045", "runtime-error": "E8001"},
             "backends": ["luajit", "jvm"], "invocation": "direct-main",
             "support": []}
            """), "expected", "exactly one");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": {"wrong-key": "E1045"}, "backends": ["luajit", "jvm"],
             "invocation": "direct-main", "support": []}
            """), "expected.wrong-key", "unknown field");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": {"compile-error": "E9999"}, "backends": ["luajit", "jvm"],
             "invocation": "compile-only", "support": []}
            """), "expected.compile-error", "not a registered");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": {"runtime-error": "E1044"}, "backends": ["luajit", "jvm"],
             "invocation": "direct-main", "support": []}
            """), "expected.runtime-error", "not a registered DEAL runtime error");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": {"runtime-error": "E9999"}, "backends": ["luajit", "jvm"],
             "invocation": "direct-main", "support": []}
            """), "expected.runtime-error", "not a registered DEAL runtime error");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": "runtime-ok", "backends": [], "invocation": "direct-main",
             "support": []}
            """), "backends", "non-empty ordered subset");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": "runtime-ok", "backends": ["luajit", "js"],
             "invocation": "direct-main", "support": []}
            """), "backends", "unknown backend");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": "runtime-ok", "backends": ["jvm", "luajit"],
             "invocation": "direct-main", "support": []}
            """), "backends", "ordered subset");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": "runtime-ok", "backends": ["luajit", "luajit"],
             "invocation": "direct-main", "support": []}
            """), "backends", "without duplicates");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": "runtime-ok", "backends": ["luajit", "jvm"],
             "invocation": "run-main", "support": []}
            """), "invocation", "unknown invocation");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": "runtime-ok", "backends": ["luajit", "jvm"],
             "invocation": "synthetic-main",
             "oracle": {"exportName": "o", "functionDescriptor": "()->int",
                        "extra": 1},
             "support": []}
            """), "oracle.extra", "unknown field in the oracle object");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": "runtime-ok", "backends": ["luajit", "jvm"],
             "invocation": "synthetic-main",
             "oracle": {"exportName": "9bad", "functionDescriptor": "()->int"},
             "support": []}
            """), "oracle.exportName", "DEAL identifier");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": "runtime-ok", "backends": ["luajit", "jvm"],
             "invocation": "synthetic-main",
             "oracle": {"exportName": "o", "functionDescriptor": "int"},
             "support": []}
            """), "oracle.functionDescriptor", "not a function descriptor");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": "runtime-ok", "backends": ["luajit", "jvm"],
             "invocation": "synthetic-main",
             "oracle": {"exportName": "o", "functionDescriptor": "int[]"},
             "support": []}
            """), "oracle.functionDescriptor", "not a canonical function");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": "runtime-ok", "backends": ["luajit", "jvm"],
             "invocation": "direct-main", "support": 7}
            """), "support", "array of canonical relative paths");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": "runtime-ok", "backends": ["luajit", "jvm"],
             "invocation": "direct-main", "support": ["/abs/path.deal"]}
            """), "support", "must not start or end with");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": "runtime-ok", "backends": ["luajit", "jvm"],
             "invocation": "direct-main", "support": ["a/../b.deal"]}
            """), "support", "dot segments");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": "runtime-ok", "backends": ["luajit", "jvm"],
             "invocation": "direct-main", "support": ["a\\\\b.deal"]}
            """), "support", "backslash");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": "runtime-ok", "backends": ["luajit", "jvm"],
             "invocation": "direct-main", "support": ["a.deal", "a.deal"]}
            """), "support", "more than once");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": "runtime-ok", "backends": ["luajit", "jvm"],
             "invocation": "direct-main", "support": [], "linkedRecord": 3}
            """), "linkedRecord", "canonical feature-record id string");

        // =========================================================================
        System.out.println("-- V12FeatureMetadata: conditional-rule negatives --");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": "compile-ok", "backends": ["luajit", "jvm"],
             "invocation": "direct-main", "support": []}
            """), "invocation", "requires compile-only");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": "runtime-ok", "backends": ["luajit", "jvm"],
             "invocation": "compile-only", "support": []}
            """), "invocation", "forbids compile-only");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": {"compile-error": "E1045"}, "backends": ["luajit", "jvm"],
             "invocation": "compile-only",
             "oracle": {"exportName": "o", "functionDescriptor": "()->int"},
             "support": []}
            """), "oracle", "compile outcome forbids an oracle");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": "runtime-ok", "backends": ["luajit", "jvm"],
             "invocation": "direct-main",
             "oracle": {"exportName": "o", "functionDescriptor": "()->int"},
             "support": []}
            """), "oracle", "direct-main forbids an oracle");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": "runtime-ok", "backends": ["luajit", "jvm"],
             "invocation": "synthetic-main", "support": []}
            """), "oracle", "requires exactly one oracle");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": "runtime-ok", "backends": ["luajit", "jvm"],
             "invocation": "synthetic-main",
             "oracle": {"exportName": "o", "functionDescriptor": "async()->int"},
             "support": []}
            """), "oracle.functionDescriptor", "requires the exact ()->R form");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_ASYNC_FUNCTION", "spec": "x",
             "description": "y", "expected": "runtime-ok",
             "backends": ["luajit", "jvm"], "invocation": "async-export",
             "oracle": {"exportName": "o", "functionDescriptor": "()->null"},
             "support": []}
            """), "oracle.functionDescriptor", "requires the exact async()->R form");

        checkFail(V12FeatureMetadata.parse("""
            {"version": 1, "feature": "BYTES_ASYNC_FUNCTION", "spec": "x",
             "description": "y", "expected": "runtime-ok",
             "backends": ["luajit", "jvm"], "invocation": "async-export",
             "oracle": {"exportName": "o",
                        "functionDescriptor": "async(int)->null"},
             "support": []}
            """), "oracle.functionDescriptor", "zero-parameter");

        // Determinism: the same document always yields the same outcome.
        String sample = """
            {"version": 1, "feature": "BYTES_CORE", "spec": "x", "description": "y",
             "expected": "runtime-ok", "backends": ["luajit", "jvm"],
             "invocation": "direct-main", "support": []}
            """;
        check(V12FeatureMetadata.parse(sample).equals(
                V12FeatureMetadata.parse(sample)),
            "parse is deterministic");

        // E4 strict parse: legacy spellings are rejected, canonical
        // recursive forms are accepted.
        Metadata recursive = ok("""
            {"version": 1, "feature": "BYTES_SYNC_FUNCTION", "spec": "x",
             "description": "y", "expected": "runtime-ok",
             "backends": ["luajit", "jvm"], "invocation": "synthetic-main",
             "oracle": {"exportName": "o",
                        "functionDescriptor": "()->[?bytes]"},
             "support": []}
            """);
        if (recursive != null) {
            check("[?bytes]".equals(recursive.oracle().orElseThrow()
                    .returnDescriptor()),
                "recursive nullable-array return descriptor");
        }

        System.out.println();
        if (failed > 0) {
            System.err.println("V12FeatureMetadataTest: " + passed + " passed, "
                + failed + " failed");
            System.exit(1);
        }
        System.out.println("V12FeatureMetadataTest: " + passed + " passed, 0 failed");
    }
}
