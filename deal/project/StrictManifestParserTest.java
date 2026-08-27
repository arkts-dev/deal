package deal.project;

import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.source.ScalarSourceCursor;
import deal.source.SourceScalarRange;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.Modifier;

/**
 * The strict-schema test battery for {@link StrictManifestParser} /
 * {@link ProjectConfigValidator} (design source
 * {@code strict-project-context-resolution-identity} Verification item 2;
 * the strict UTF-8 byte decode is ProjectLocator's input contract, so this
 * battery covers the strict parse over strictly decoded text).
 *
 * <p>Coverage: duplicates at top level, in externals entries, and inside
 * {@code dependencies} at any depth (sibling objects under an array never
 * collide); the pinned escape set ({@code \u0073rc} → {@code src},
 * surrogate-pair decoding, {@code \b}/{@code \f}/{@code \u005cuXXXX} valid,
 * {@code \x}/{@code \u005cu00G0}/{@code \u005cu00}/lone {@code \u005cuD800}/trailing
 * backslash fail at the escape's range, literal {@code \uFFFD} valid);
 * strict numbers ({@code 01}, {@code 1.}, {@code -.5} fail; {@code 1e5}
 * valid); boolean/null exactness; unknown members ({@code permissions},
 * {@code limits}, typos); missing/wrong {@code languageVersion}; backend
 * {@code lua}/{@code js}/case/whitespace/empty; roots (wrong type, empty,
 * NUL, absolute); externals (list form, missing/non-string/non-{@code .d.deal}
 * declaration, non-string {@code nativeLibrary}, unknown entry member);
 * {@code stdlib}; {@code dependencies} type; {@code output} type/text;
 * fail-fast scan-order and pinned canonical order; content-only purity
 * (parsing content whose referenced files do not exist; no
 * {@code java.io}/{@code java.nio.file} type on the parser surface;
 * deterministic double parse); and complete E1 scalar ranges (multi-line,
 * tab, CRLF, astral, exact line/column/offset pins).
 *
 * <p>Runs via main() using the check() helpers; exits non-zero on failure.
 */
public final class StrictManifestParserTest {

    private StrictManifestParserTest() {
    }

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

    // =========================================================================
    // Test runner
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Running Strict Manifest Parser Tests ===");

        testMinimalManifestDefaults();
        testFullManifestValuesAndKinds();
        testStrictEscapeSet();
        testDuplicateMembersRecursively();
        testSiblingObjectsNeverCollide();
        testStrictNumbers();
        testLiteralExactness();
        testUnknownMembers();
        testLanguageVersion();
        testBackend();
        testModuleRoots();
        testOutput();
        testStdlib();
        testDependencies();
        testExternals();
        testStructuralFaults();
        testFailFastScanOrder();
        testFailFastCanonicalOrder();
        testContentOnlyAndDeterminism();
        testParserSurfaceHasNoIoTypes();
        testCompleteScalarRanges();

        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.err.println("StrictManifestParserTest FAILED: " + failed + " failure(s)");
            System.exit(1);
        }
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    /**
     * Parses with a manifest path whose directory does not exist on disk —
     * a successful parse therefore proves the parser performs no
     * filesystem access.
     */
    private static StrictManifestParser.StrictManifestParseResult parse(String json) {
        return StrictManifestParser.parse("/nonexistent/project/dir/deal.json", json);
    }

    /** Parses and asserts success; returns the manifest (or null). */
    private static ProjectManifest checkSuccess(String json) {
        StrictManifestParser.StrictManifestParseResult result = parse(json);
        check(result.failure() == null,
            "no diagnostic for valid input: " + json
                + (result.failure() == null ? "" : " -> " + result.failure().message()));
        check(result.manifest() != null, "manifest present for valid input: " + json);
        return result.manifest();
    }

    /**
     * Parses and asserts exactly one E2010 failure anchored at the
     * independently recomputed range of the given needle occurrence.
     * Returns the diagnostic (or null).
     */
    private static CompilerDiagnostic checkFailure(String json, String messageContains,
                                                   String needle, int occurrence) {
        StrictManifestParser.StrictManifestParseResult result = parse(json);
        CompilerDiagnostic diagnostic = checkFailureShape(json, messageContains, result);
        if (diagnostic != null && needle != null) {
            checkRangeEquals("failure anchor for '" + needle + "'", result,
                expectedRange(json, needle, occurrence));
        }
        return diagnostic;
    }

    /**
     * Parses and asserts exactly one E2010 failure anchored at the
     * explicitly supplied expected range (zero-length and end-of-input
     * anchors). Returns the diagnostic (or null).
     */
    private static CompilerDiagnostic checkFailureAt(String json, String messageContains,
                                                     SourceScalarRange expected) {
        StrictManifestParser.StrictManifestParseResult result = parse(json);
        CompilerDiagnostic diagnostic = checkFailureShape(json, messageContains, result);
        if (diagnostic != null) {
            checkRangeEquals("failure anchor", result, expected);
        }
        return diagnostic;
    }

    /** The shared E2010/severity/manifest-absence assertions. */
    private static CompilerDiagnostic checkFailureShape(String json, String messageContains,
            StrictManifestParser.StrictManifestParseResult result) {
        check(result.manifest() == null, "no manifest on failure: " + json);
        check(result.failure() != null, "one diagnostic on failure: " + json);
        if (result.failure() == null) {
            return null;
        }
        CompilerDiagnostic diagnostic = result.failure();
        check(diagnostic.code().equals("E2010"),
            "E2010 code, was " + diagnostic.code() + ": " + json);
        check(diagnostic.severity().equals("error"), "error severity: " + json);
        if (messageContains != null) {
            check(diagnostic.message().contains(messageContains),
                "message contains '" + messageContains + "', was: " + diagnostic.message());
        }
        return diagnostic;
    }

    /** Asserts the diagnostic's SOURCE range equals the expected scalar range. */
    private static void checkRangeEquals(String context,
                                         StrictManifestParser.StrictManifestParseResult result,
                                         SourceScalarRange expected) {
        CompilerDiagnostic diagnostic = result.failure();
        check(diagnostic != null && diagnostic.range() != null, context + ": range present");
        if (diagnostic == null || diagnostic.range() == null) {
            return;
        }
        DiagnosticRange actual = diagnostic.range();
        check(actual.origin() == RangeOrigin.SOURCE,
            context + ": SOURCE origin, was " + actual.origin());
        check(actual.hasScalarOffsets(), context + ": has scalar offsets");
        check(actual.file().equals("/nonexistent/project/dir/deal.json"),
            context + ": manifest path in range file, was '" + actual.file() + "'");
        if (expected == null) {
            return;
        }
        check(actual.startLine() == expected.startLine(),
            context + ": startLine " + actual.startLine() + " vs " + expected.startLine());
        check(actual.startColumn() == expected.startColumn(),
            context + ": startColumn " + actual.startColumn() + " vs " + expected.startColumn());
        check(actual.endLine() == expected.endLine(),
            context + ": endLine " + actual.endLine() + " vs " + expected.endLine());
        check(actual.endColumn() == expected.endColumn(),
            context + ": endColumn " + actual.endColumn() + " vs " + expected.endColumn());
        check(actual.startScalarOffset() == expected.startScalarOffset(),
            context + ": startScalarOffset " + actual.startScalarOffset()
                + " vs " + expected.startScalarOffset());
        check(actual.endScalarOffset() == expected.endScalarOffset(),
            context + ": endScalarOffset " + actual.endScalarOffset()
                + " vs " + expected.endScalarOffset());
        check(actual.scalarLength() == expected.endScalarOffset() - expected.startScalarOffset(),
            context + ": scalarLength invariant " + actual.scalarLength() + " vs "
                + (expected.endScalarOffset() - expected.startScalarOffset()));
    }

    /** Asserts a manifest's published member range equals the recomputed one. */
    private static void checkManifestRange(String context, String source,
                                           SourceScalarRange actual, String needle,
                                           int occurrence) {
        SourceScalarRange expected = expectedRange(source, needle, occurrence);
        check(actual != null, context + ": range present");
        if (actual == null) {
            return;
        }
        check(actual.startLine() == expected.startLine()
                && actual.startColumn() == expected.startColumn()
                && actual.endLine() == expected.endLine()
                && actual.endColumn() == expected.endColumn()
                && actual.startScalarOffset() == expected.startScalarOffset()
                && actual.endScalarOffset() == expected.endScalarOffset(),
            context + ": range " + actual + " vs expected " + expected);
    }

    /** The UTF-16 index of the given needle occurrence. */
    private static int indexOfOccurrence(String source, String needle, int occurrence) {
        int from = 0;
        int index = -1;
        for (int i = 0; i <= occurrence; i++) {
            index = source.indexOf(needle, from);
            if (index < 0) {
                throw new IllegalStateException("needle '" + needle + "' occurrence "
                    + occurrence + " not found in: " + source);
            }
            from = index + 1;
        }
        return index;
    }

    /**
     * Recomputes the half-open decoded-scalar range of a needle
     * occurrence with an independent {@link ScalarSourceCursor} walk.
     */
    private static SourceScalarRange expectedRange(String source, String needle,
                                                   int occurrence) {
        int index = indexOfOccurrence(source, needle, occurrence);
        ScalarSourceCursor cursor = new ScalarSourceCursor(source);
        while (!cursor.atEnd() && cursor.index() < index) {
            cursor.advance();
        }
        int startLine = cursor.line();
        int startColumn = cursor.column();
        int startOffset = cursor.scalarOffset();
        int end = index + needle.length();
        while (!cursor.atEnd() && cursor.index() < end) {
            cursor.advance();
        }
        return new SourceScalarRange(startLine, startColumn, cursor.line(), cursor.column(),
            startOffset, cursor.scalarOffset());
    }

    /** The decoded scalar offset at the given UTF-16 index. */
    private static int scalarOffsetAt(String source, int utf16Index) {
        ScalarSourceCursor cursor = new ScalarSourceCursor(source);
        while (!cursor.atEnd() && cursor.index() < utf16Index) {
            cursor.advance();
        }
        return cursor.scalarOffset();
    }

    /** A zero-length range at the start of a needle occurrence. */
    private static SourceScalarRange expectedZeroRange(String source, String needle,
                                                       int occurrence) {
        int index = indexOfOccurrence(source, needle, occurrence);
        ScalarSourceCursor cursor = new ScalarSourceCursor(source);
        while (!cursor.atEnd() && cursor.index() < index) {
            cursor.advance();
        }
        return new SourceScalarRange(cursor.line(), cursor.column(), cursor.line(),
            cursor.column(), cursor.scalarOffset(), cursor.scalarOffset());
    }

    /** A zero-length range at end of input. */
    private static SourceScalarRange expectedEndRange(String source) {
        ScalarSourceCursor cursor = new ScalarSourceCursor(source);
        while (!cursor.atEnd()) {
            cursor.advance();
        }
        return new SourceScalarRange(cursor.line(), cursor.column(), cursor.line(),
            cursor.column(), cursor.scalarOffset(), cursor.scalarOffset());
    }

    // =========================================================================
    // Defaults and field values
    // =========================================================================

    private static void testMinimalManifestDefaults() {
        String json = "{\"languageVersion\":\"1.2\"}";
        ProjectManifest manifest = checkSuccess(json);
        if (manifest == null) {
            return;
        }
        check(manifest.languageVersion().equals("1.2"), "languageVersion pinned '1.2'");
        check(manifest.backend().equals("luajit"), "absent backend defaults to 'luajit'");
        check(manifest.stdlib().equals("1.2"), "absent stdlib defaults to '1.2'");
        check(manifest.moduleRoots().isEmpty(), "absent moduleRoots is the empty list");
        check(manifest.output() == null, "absent output stays absent (OutputConfigResolver duty)");
        check(manifest.dependencies() == null, "absent dependencies stays absent");
        check(manifest.externals().isEmpty(), "absent externals is the empty map");
        check(manifest.ranges().size() == 1
                && manifest.ranges().containsKey("languageVersion"),
            "ranges maps only the present member");
        checkManifestRange("languageVersion value range", json,
            manifest.ranges().get("languageVersion"), "\"1.2\"", 0);
    }

    private static void testFullManifestValuesAndKinds() {
        String json = "{\"languageVersion\":\"1.2\",\"moduleRoots\":[\"src\",\"lib\"],"
            + "\"output\":\"build/out\",\"backend\":\"jvm\",\"stdlib\":\"1.2\","
            + "\"dependencies\":{\"a\":1,\"sub\":{\"x\":\"y\\n\"},\"arr\":[true,false,null]},"
            + "\"externals\":{"
            + "\"host/a\":{\"declaration\":\"bindings/a.d.deal\",\"nativeLibrary\":\"liba.so\"},"
            + "\"host/b\":{\"declaration\":\"/abs/b.d.deal\",\"nativeLibrary\":\"libs/libb.so\"},"
            + "\"host/c\":{\"declaration\":\"../c.d.deal\",\"nativeLibrary\":\"/usr/lib/libc.so\"},"
            + "\"host/d\":{\"declaration\":\"bindings/d.d.deal\"},"
            + "\"host/e\":{\"declaration\":\"bindings/e.d.deal\",\"nativeLibrary\":\"lib\\\\e.so\"}}}";
        ProjectManifest manifest = checkSuccess(json);
        if (manifest == null) {
            return;
        }
        check(manifest.backend().equals("jvm"), "backend 'jvm'");
        check(manifest.moduleRoots().size() == 2, "two moduleRoots");
        check(manifest.moduleRoots().get(0).value().equals("src"), "root 0 value");
        check(manifest.moduleRoots().get(1).value().equals("lib"), "root 1 value");
        checkManifestRange("root 0 range", json, manifest.moduleRoots().get(0).sourceRange(),
            "\"src\"", 0);
        checkManifestRange("root 1 range", json, manifest.moduleRoots().get(1).sourceRange(),
            "\"lib\"", 0);
        check(manifest.output() != null && manifest.output().value().equals("build/out"),
            "output value");
        checkManifestRange("output range", json, manifest.output().sourceRange(),
            "\"build/out\"", 0);
        check(manifest.dependencies() != null, "dependencies object present");
        StrictJsonValue.ObjectVal dependencies = manifest.dependencies();
        check(dependencies.members().get("a") instanceof StrictJsonValue.NumberVal number
                && number.sourceText().equals("1"),
            "dependencies number preserved exactly");
        StrictJsonValue sub = dependencies.members().get("sub");
        check(sub instanceof StrictJsonValue.ObjectVal subObject
                && subObject.members().get("x") instanceof StrictJsonValue.StringVal x
                && x.value().equals("y\n"),
            "dependencies nested string strictly decoded (\\n -> newline)");
        check(dependencies.members().get("arr") instanceof StrictJsonValue.ArrayVal array
                && array.elements().get(0) instanceof StrictJsonValue.BoolVal first
                && first.value()
                && array.elements().get(1) instanceof StrictJsonValue.BoolVal second
                && !second.value()
                && array.elements().get(2) instanceof StrictJsonValue.NullVal,
            "dependencies array contents strict");
        check(manifest.externals().size() == 5, "five externals entries");
        ExternalEntrySpec a = manifest.externals().get("host/a");
        check(a != null && a.rawImportSpecifier().equals("host/a")
                && a.declaration().value().equals("bindings/a.d.deal")
                && a.nativeLibrary() != null
                && a.nativeLibrary().kind() == NativeLibraryRef.Kind.BARE_NAME
                && a.nativeLibrary().loaderText().equals("liba.so"),
            "externals bare loader name");
        ExternalEntrySpec b = manifest.externals().get("host/b");
        check(b != null && b.declaration().value().equals("/abs/b.d.deal")
                && b.nativeLibrary() != null
                && b.nativeLibrary().kind() == NativeLibraryRef.Kind.MANIFEST_RELATIVE_PATH,
            "externals manifest-relative library");
        ExternalEntrySpec c = manifest.externals().get("host/c");
        check(c != null && c.declaration().value().equals("../c.d.deal")
                && c.nativeLibrary() != null
                && c.nativeLibrary().kind() == NativeLibraryRef.Kind.ABSOLUTE_PATH,
            "externals absolute library path");
        ExternalEntrySpec d = manifest.externals().get("host/d");
        check(d != null && d.nativeLibrary() == null, "externals entry without nativeLibrary");
        ExternalEntrySpec e = manifest.externals().get("host/e");
        check(e != null && e.nativeLibrary() != null
                && e.nativeLibrary().kind() == NativeLibraryRef.Kind.BARE_NAME
                && e.nativeLibrary().loaderText().equals("lib\\e.so"),
            "backslash is an ordinary filename character");
        checkManifestRange("declaration range", json,
            manifest.externals().get("host/a").declaration().sourceRange(),
            "\"bindings/a.d.deal\"", 0);
        check(manifest.ranges().size() == 7, "ranges maps all seven present members");
        checkManifestRange("backend range", json, manifest.ranges().get("backend"),
            "\"jvm\"", 0);
        SourceScalarRange depsStart = expectedRange(json, "{\"a\":1", 0);
        int depsCloseBrace = json.indexOf("},\"externals\"");
        SourceScalarRange depsRange = manifest.ranges().get("dependencies");
        check(depsRange != null
                && depsRange.startScalarOffset() == depsStart.startScalarOffset()
                && depsRange.endScalarOffset() == scalarOffsetAt(json, depsCloseBrace + 1),
            "dependencies value range spans the whole object");
        // The externals value range spans the whole externals object.
        SourceScalarRange externalsStart = expectedRange(json, "{\"host/a\"", 0);
        int externalsClose = json.lastIndexOf('}', json.length() - 2) + 1;
        SourceScalarRange externalsRange = manifest.ranges().get("externals");
        check(externalsRange != null
                && externalsRange.startScalarOffset() == externalsStart.startScalarOffset()
                && externalsRange.endScalarOffset() == scalarOffsetAt(json, externalsClose),
            "externals value range spans the whole object");
    }

    // =========================================================================
    // Strict escape set
    // =========================================================================

    private static void testStrictEscapeSet() {
        String unicode = "{\"languageVersion\":\"1.2\",\"moduleRoots\":[\"\\u0073rc\"]}";
        ProjectManifest manifest = checkSuccess(unicode);
        check(manifest != null && manifest.moduleRoots().get(0).value().equals("src"),
            "\\u0073rc strictly decodes to 'src' (permissive values never used)");

        String pair = "{\"languageVersion\":\"1.2\",\"moduleRoots\":[\"\\uD83D\\uDE00\"]}";
        manifest = checkSuccess(pair);
        check(manifest != null
                && manifest.moduleRoots().get(0).value().equals("\uD83D\uDE00"),
            "surrogate-pair escape decodes to one supplementary scalar");

        String specials = "{\"languageVersion\":\"1.2\",\"moduleRoots\":"
            + "[\"a\\bb\",\"c\\fd\",\"e\\tf\",\"g\\nh\",\"i\\rj\",\"k\\\"l\",\"m\\\\n\",\"o\\/p\"]}";
        manifest = checkSuccess(specials);
        if (manifest != null) {
            check(manifest.moduleRoots().get(0).value().equals("a\bb"), "\\b valid and decoded");
            check(manifest.moduleRoots().get(1).value().equals("c\fd"), "\\f valid and decoded");
            check(manifest.moduleRoots().get(2).value().equals("e\tf"), "\\t valid and decoded");
            check(manifest.moduleRoots().get(3).value().equals("g\nh"), "\\n valid and decoded");
            check(manifest.moduleRoots().get(4).value().equals("i\rj"), "\\r valid and decoded");
            check(manifest.moduleRoots().get(5).value().equals("k\"l"), "\\\" valid and decoded");
            check(manifest.moduleRoots().get(6).value().equals("m\\n"), "\\\\ valid and decoded");
            check(manifest.moduleRoots().get(7).value().equals("o/p"), "\\/ valid and decoded");
        }

        String hex = "{\"languageVersion\":\"1.2\",\"moduleRoots\":[\"\\u0041\"]}";
        manifest = checkSuccess(hex);
        check(manifest != null && manifest.moduleRoots().get(0).value().equals("A"),
            "\\u0041 decodes to 'A'");

        String fffd = "{\"languageVersion\":\"1.2\",\"moduleRoots\":[\"\\uFFFD\"]}";
        manifest = checkSuccess(fffd);
        check(manifest != null && manifest.moduleRoots().get(0).value().equals("\uFFFD"),
            "a literal \\uFFFD escape remains valid and decodes to U+FFFD");

        // Defective escapes fail at the escape's range (backslash through
        // the offending scalar, or the token end).
        checkFailure("{\"languageVersion\":\"1.2\",\"moduleRoots\":[\"a\\x\"]}",
            "invalid escape sequence in string", "\\x", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"moduleRoots\":[\"a\\u00G0\"]}",
            "invalid escape sequence in string", "\\u00G", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"moduleRoots\":[\"a\\u00\"]}",
            "invalid escape sequence in string", "\\u00\"", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"moduleRoots\":[\"a\\uD800\"]}",
            "invalid escape sequence in string", "\\uD800", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"moduleRoots\":[\"a\\uDC00\"]}",
            "invalid escape sequence in string", "\\uDC00", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"moduleRoots\":[\"a\\uD800\\u0041\"]}",
            "invalid escape sequence in string", "\\uD800", 0);
        String trailingBackslash = "{\"languageVersion\":\"1.2\",\"moduleRoots\":[\"abc\\";
        checkFailure(trailingBackslash, "invalid escape sequence in string", "\\", 0);

        // Escape defects inside a KEY fail at the escape's range too.
        checkFailure("{\"language\\xVersion\":\"1.2\"}",
            "invalid escape sequence in member key", "\\x", 0);
    }

    // =========================================================================
    // Duplicate members, recursively at any depth
    // =========================================================================

    private static void testDuplicateMembersRecursively() {
        String topLevel = "{\"languageVersion\":\"1.2\",\"languageVersion\":\"1.2\"}";
        checkFailure(topLevel, "duplicate member 'languageVersion'",
            "\"languageVersion\"", 1);

        String externalsEntry = "{\"languageVersion\":\"1.2\",\"externals\":"
            + "{\"m\":{\"declaration\":\"a.d.deal\",\"declaration\":\"b.d.deal\"}}}";
        checkFailure(externalsEntry, "duplicate member 'declaration'", "\"declaration\"", 1);

        String dependencies = "{\"languageVersion\":\"1.2\",\"dependencies\":"
            + "{\"a\":1,\"a\":2}}";
        checkFailure(dependencies, "duplicate member 'a'", "\"a\"", 1);

        String deepDependencies = "{\"languageVersion\":\"1.2\",\"dependencies\":"
            + "{\"outer\":{\"inner\":{\"k\":1,\"k\":2}}}}";
        checkFailure(deepDependencies, "duplicate member 'k'", "\"k\"", 1);

        // Duplicate detection compares strictly decoded keys, so a
        // differently spelled key that decodes to the same string still
        // collides at the second key's range.
        String decodedDuplicate = "{\"languageVersion\":\"1.2\","
            + "\"\\u006canguageVersion\":\"1.2\"}";
        checkFailure(decodedDuplicate, "duplicate member 'languageVersion'",
            "\"\\u006canguageVersion\"", 0);
    }

    private static void testSiblingObjectsNeverCollide() {
        String siblings = "{\"deps\":[{\"a\":1},{\"a\":2}]}";
        CompilerDiagnostic diagnostic = checkFailure(siblings,
            "unknown member 'deps'", "\"deps\"", 0);
        check(diagnostic != null && !diagnostic.message().contains("duplicate"),
            "sibling objects under an array are not a duplicate: "
                + (diagnostic == null ? "no diagnostic" : diagnostic.message()));
    }

    // =========================================================================
    // Strict numbers and literals
    // =========================================================================

    private static void testStrictNumbers() {
        checkFailure("{\"languageVersion\":\"1.2\",\"dependencies\":{\"n\":01}}",
            "strict JSON grammar", "01", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"dependencies\":{\"n\":1.}}",
            "strict JSON grammar", "1.", 1);
        checkFailure("{\"languageVersion\":\"1.2\",\"dependencies\":{\"n\":-.5}}",
            "strict JSON grammar", "-.5", 0);
        ProjectManifest manifest = checkSuccess(
            "{\"languageVersion\":\"1.2\",\"dependencies\":{\"n\":1e5}}");
        check(manifest != null
                && manifest.dependencies().members().get("n")
                    instanceof StrictJsonValue.NumberVal number
                && number.sourceText().equals("1e5"),
            "1e5 is a valid strict number, preserved exactly");
        manifest = checkSuccess(
            "{\"languageVersion\":\"1.2\",\"dependencies\":{\"n\":-0.5e-3}}");
        check(manifest != null
                && manifest.dependencies().members().get("n")
                    instanceof StrictJsonValue.NumberVal number2
                && number2.sourceText().equals("-0.5e-3"),
            "-0.5e-3 is a valid strict number");
    }

    private static void testLiteralExactness() {
        checkFailure("{\"languageVersion\":\"1.2\",\"dependencies\":{\"b\":tru,}}",
            "expected literal 'false'", "tru,}", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"dependencies\":{\"b\":fals}}",
            "expected literal 'false'", "fals}", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"dependencies\":{\"b\":nul}}",
            "expected literal 'null'", "nul}", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"dependencies\":{\"b\":truex}}",
            "invalid JSON", "x", 0);
        ProjectManifest manifest = checkSuccess("{\"languageVersion\":\"1.2\","
            + "\"dependencies\":{\"t\":true,\"f\":false,\"n\":null}}");
        check(manifest != null
                && manifest.dependencies().members().get("t")
                    instanceof StrictJsonValue.BoolVal t && t.value()
                && manifest.dependencies().members().get("f")
                    instanceof StrictJsonValue.BoolVal f && !f.value()
                && manifest.dependencies().members().get("n")
                    instanceof StrictJsonValue.NullVal,
            "true/false/null are exact and strictly decoded");
    }

    // =========================================================================
    // Closed member sets
    // =========================================================================

    private static void testUnknownMembers() {
        checkFailure("{\"languageVersion\":\"1.2\",\"permissions\":[]}",
            "unknown member 'permissions'", "\"permissions\"", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"limits\":{}}",
            "unknown member 'limits'", "\"limits\"", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"moduleRoot\":[\"src\"]}",
            "unknown member 'moduleRoot'", "\"moduleRoot\"", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"externals\":"
                + "{\"m\":{\"declaration\":\"a.d.deal\",\"foo\":1}}}",
            "unknown member 'foo' in externals entry", "\"foo\"", 0);
        // A strictly decoded alias of a legacy member is still unknown.
        checkFailure("{\"languageVersion\":\"1.2\",\"perm\\u0069ssions\":[]}",
            "unknown member 'permissions'", "\"perm\\u0069ssions\"", 0);
    }

    // =========================================================================
    // Field schema: languageVersion / backend / moduleRoots / output /
    // stdlib / dependencies
    // =========================================================================

    private static void testLanguageVersion() {
        checkFailure("{\"backend\":\"jvm\"}", "missing required member 'languageVersion'",
            "{\"backend\":\"jvm\"}", 0);
        checkFailure("", "missing required member 'languageVersion'", null, 0);
        CompilerDiagnostic empty = parse("").failure();
        check(empty != null && empty.range() != null
                && empty.range().startLine() == 1 && empty.range().startColumn() == 1
                && empty.range().endLine() == 1 && empty.range().endColumn() == 1
                && empty.range().startScalarOffset() == 0
                && empty.range().endScalarOffset() == 0
                && empty.range().scalarLength() == 0
                && empty.range().origin() == RangeOrigin.SOURCE,
            "empty input pins the document-start zero-length SOURCE range (1,1,1,1,0,0,0)");
        checkFailure(" \n\t\r ", "missing required member 'languageVersion'", null, 0);
        checkFailure("{\"languageVersion\":1}", "must be the JSON string", "1", 0);
        checkFailure("{\"languageVersion\":null}", "must be the JSON string", "null", 0);
        checkFailure("{\"languageVersion\":\"1.1\"}", "must be exactly \"1.2\"", "\"1.1\"", 0);
        checkFailure("{\"languageVersion\":\"1.2 \"}", "must be exactly \"1.2\"", "\"1.2 \"", 0);
        checkFailure("[]", "manifest root must be a JSON object", "[]", 0);
        checkFailure("\"x\"", "manifest root must be a JSON object", "\"x\"", 0);
        checkFailure("1", "manifest root must be a JSON object", "1", 0);
        checkFailure("true", "manifest root must be a JSON object", "true", 0);
        checkFailure("null", "manifest root must be a JSON object", "null", 0);
    }

    private static void testBackend() {
        checkFailure("{\"languageVersion\":\"1.2\",\"backend\":\"lua\"}",
            "unsupported backend 'lua'", "\"lua\"", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"backend\":\"js\"}",
            "unsupported backend 'js'", "\"js\"", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"backend\":\"LuaJIT\"}",
            "unsupported backend 'LuaJIT'", "\"LuaJIT\"", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"backend\":\" luajit\"}",
            "unsupported backend ' luajit'", "\" luajit\"", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"backend\":\"\"}",
            "unsupported backend ''", "\"\"", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"backend\":\"clang\"}",
            "unsupported backend 'clang'", "\"clang\"", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"backend\":1}",
            "'backend' must be a string", "1", 1);
        ProjectManifest manifest = checkSuccess(
            "{\"languageVersion\":\"1.2\",\"backend\":\"jvm\"}");
        check(manifest != null && manifest.backend().equals("jvm"), "backend 'jvm' valid");
        manifest = checkSuccess("{\"languageVersion\":\"1.2\",\"backend\":\"luajit\"}");
        check(manifest != null && manifest.backend().equals("luajit"),
            "backend 'luajit' valid");
    }

    private static void testModuleRoots() {
        checkFailure("{\"languageVersion\":\"1.2\",\"moduleRoots\":\"src\"}",
            "'moduleRoots' must be an array", "\"src\"", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"moduleRoots\":{\"src\":1}}",
            "'moduleRoots' must be an array", "{\"src\":1}", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"moduleRoots\":[1,\"src\"]}",
            "'moduleRoots' entries must be strings", "1", 1);
        checkFailure("{\"languageVersion\":\"1.2\",\"moduleRoots\":[\"\"]}",
            "must be a non-empty string", "\"\"", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"moduleRoots\":[\"a\\u0000b\"]}",
            "must not contain NUL", "\"a\\u0000b\"", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"moduleRoots\":[\"/src\"]}",
            "must be relative", "\"/src\"", 0);
        ProjectManifest manifest = checkSuccess("{\"languageVersion\":\"1.2\",\"moduleRoots\":"
            + "[\"src\",\"lib\",\"lib/utils\",\"../shared\",\"C:\\\\foo\",\"a\\\\b\"]}");
        if (manifest != null) {
            check(manifest.moduleRoots().size() == 6, "six valid roots");
            check(manifest.moduleRoots().get(0).value().equals("src")
                    && manifest.moduleRoots().get(1).value().equals("lib")
                    && manifest.moduleRoots().get(2).value().equals("lib/utils")
                    && manifest.moduleRoots().get(3).value().equals("../shared")
                    && manifest.moduleRoots().get(4).value().equals("C:\\foo")
                    && manifest.moduleRoots().get(5).value().equals("a\\b"),
                "relative roots preserved exactly (backslash ordinary; ../ permitted)");
        }
        manifest = checkSuccess("{\"languageVersion\":\"1.2\",\"moduleRoots\":[]}");
        check(manifest != null && manifest.moduleRoots().isEmpty(),
            "an empty moduleRoots array is the empty list");
    }

    private static void testOutput() {
        checkFailure("{\"languageVersion\":\"1.2\",\"output\":5}",
            "'output' must be a non-empty string", "5", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"output\":\"\"}",
            "'output' must be a non-empty string", "\"\"", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"output\":\"a\\u0000b\"}",
            "must not contain NUL", "\"a\\u0000b\"", 0);
        ProjectManifest manifest = checkSuccess(
            "{\"languageVersion\":\"1.2\",\"output\":\"./build/out\"}");
        check(manifest != null && manifest.output() != null
                && manifest.output().value().equals("./build/out"),
            "relative ./ output form is schema-valid text");
        manifest = checkSuccess("{\"languageVersion\":\"1.2\",\"output\":\"/abs/out\"}");
        check(manifest != null && manifest.output() != null
                && manifest.output().value().equals("/abs/out"),
            "absolute output form is schema-valid text");
    }

    private static void testStdlib() {
        checkFailure("{\"languageVersion\":\"1.2\",\"stdlib\":\"2.0\"}",
            "'stdlib' must be exactly \"1.2\"", "\"2.0\"", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"stdlib\":1}",
            "'stdlib' must be a string", "1", 1);
        ProjectManifest manifest = checkSuccess(
            "{\"languageVersion\":\"1.2\",\"stdlib\":\"1.2\"}");
        check(manifest != null && manifest.stdlib().equals("1.2"), "stdlib '1.2' valid");
    }

    private static void testDependencies() {
        checkFailure("{\"languageVersion\":\"1.2\",\"dependencies\":[]}",
            "'dependencies' must be a JSON object", "[]", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"dependencies\":\"x\"}",
            "'dependencies' must be a JSON object", "\"x\"", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"dependencies\":null}",
            "'dependencies' must be a JSON object", "null", 0);
        ProjectManifest manifest = checkSuccess("{\"languageVersion\":\"1.2\","
            + "\"dependencies\":{\"deep\":{\"escaped\":\"\\u00e9\",\"n\":-1,\"b\":true}}}");
        check(manifest != null && manifest.dependencies() != null
                && manifest.dependencies().members().get("deep")
                    instanceof StrictJsonValue.ObjectVal deep
                && deep.members().get("escaped")
                    instanceof StrictJsonValue.StringVal escaped
                && escaped.value().equals("\u00e9"),
            "opaque dependencies contents strictly decoded at any depth");
    }

    // =========================================================================
    // Field schema: externals
    // =========================================================================

    private static void testExternals() {
        checkFailure("{\"languageVersion\":\"1.2\",\"externals\":[\"a\"]}",
            "'externals' must be an object", "[\"a\"]", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"externals\":5}",
            "'externals' must be an object", "5", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"externals\":\"x\"}",
            "'externals' must be an object", "\"x\"", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"externals\":{\"m\":\"x.d.deal\"}}",
            "externals entry 'm' must be an object", "\"x.d.deal\"", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"externals\":{\"m\":{}}}",
            "missing required member 'declaration'", "{}", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"externals\":"
                + "{\"m\":{\"declaration\":1}}}",
            "'declaration' must be a string", "1", 1);
        checkFailure("{\"languageVersion\":\"1.2\",\"externals\":"
                + "{\"m\":{\"declaration\":\"\"}}}",
            "must be a non-empty string", "\"\"", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"externals\":"
                + "{\"m\":{\"declaration\":\"a\\u0000.d.deal\"}}}",
            "must not contain NUL", "\"a\\u0000.d.deal\"", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"externals\":"
                + "{\"m\":{\"declaration\":\"a.deal\"}}}",
            "must be a host declaration file (.d.deal)", "\"a.deal\"", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"externals\":"
                + "{\"m\":{\"declaration\":\"a.d.deal\",\"nativeLibrary\":1}}}",
            "'nativeLibrary' must be a string", "1", 1);
        checkFailure("{\"languageVersion\":\"1.2\",\"externals\":"
                + "{\"m\":{\"declaration\":\"a.d.deal\",\"nativeLibrary\":\"\"}}}",
            "must be a non-empty string", "\"\"", 0);
        ProjectManifest manifest = checkSuccess("{\"languageVersion\":\"1.2\","
            + "\"externals\":{\"m\":{\"declaration\":\"bindings/a.d.deal\"}}}");
        check(manifest != null && manifest.externals().size() == 1
                && manifest.externals().get("m") != null
                && manifest.externals().get("m").declaration().value()
                    .equals("bindings/a.d.deal"),
            "minimal externals entry valid");
        manifest = checkSuccess("{\"languageVersion\":\"1.2\",\"externals\":{}}");
        check(manifest != null && manifest.externals().isEmpty(),
            "an empty externals object is the empty map");
    }

    // =========================================================================
    // Structural strictness (the ten pinned fault kinds)
    // =========================================================================

    private static void testStructuralFaults() {
        checkFailure("{\"languageVersion\"\u2028: \"1.2\"}",
            "non-strict JSON whitespace", "\u2028", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"moduleRoots\":[\"a\u0001b\"]}",
            "raw control character in string", "\u0001", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"moduleRoots\":[\"\uD800\"]}",
            "unpaired surrogate in string", "\uD800", 0);
        String unclosed = "{\"languageVersion\":\"1.2\"";
        checkFailureAt(unclosed, "unclosed object", expectedEndRange(unclosed));
        String missingComma = "{\"languageVersion\":\"1.2\" \"x\":1}";
        checkFailureAt(missingComma, "expected ',' or container end",
            expectedZeroRange(missingComma, "\"x\"", 0));
        checkFailure("{\"languageVersion\":\"1.2\",}", "expected string key", "}", 0);
        checkFailure("{\"languageVersion\" 1}", "expected ':'", "1", 0);
        checkFailure("{\"languageVersion\":}", "expected value", "}", 0);
        checkFailure("{\"languageVersion\":\"1.2\"}#", "invalid JSON", "#", 0);
        String trailing = "{\"languageVersion\":\"1.2\"} x";
        checkFailure(trailing, "after root value", "x", 0);
        String trailingToken = "{\"languageVersion\":\"1.2\"} 1";
        checkFailureAt(trailingToken, "trailing content after root value",
            expectedZeroRange(trailingToken, "1", 1));
    }

    // =========================================================================
    // Fail-fast pinning
    // =========================================================================

    private static void testFailFastScanOrder() {
        String escapeFirst = "{\"languageVersion\":\"1.2\",\"dependencies\":"
            + "{\"x\":\"\\q\",\"y\":01}}";
        checkFailure(escapeFirst, "invalid escape sequence in string", "\\q", 0);
        String numberFirst = "{\"languageVersion\":\"1.2\",\"dependencies\":"
            + "{\"y\":01,\"x\":\"\\q\"}}";
        checkFailure(numberFirst, "strict JSON grammar", "01", 0);
        String faultAfterToken = "{\"languageVersion\":\"1.2\",\"dependencies\":"
            + "{\"a\":01}\u2028}";
        checkFailure(faultAfterToken, "strict JSON grammar", "01", 0);
        String faultBeforeToken = "{\"languageVersion\":\"1.2\",\"dependencies\":\u2028"
            + "{\"a\":01}}";
        checkFailure(faultBeforeToken, "non-strict JSON whitespace", "\u2028", 0);
    }

    private static void testFailFastCanonicalOrder() {
        checkFailure("{\"languageVersion\":1,\"backend\":\"js\",\"moduleRoots\":\"x\","
                + "\"output\":5,\"stdlib\":9}",
            "'languageVersion' must be the JSON string", "1", 0);
        checkFailure("{\"output\":5,\"languageVersion\":1}",
            "'languageVersion' must be the JSON string", "1", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"backend\":\"js\",\"moduleRoots\":\"x\"}",
            "unsupported backend 'js'", "\"js\"", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"moduleRoots\":[\"/abs\"],\"output\":5}",
            "'moduleRoots' entries must be relative", "\"/abs\"", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"output\":5,\"stdlib\":9}",
            "'output' must be a non-empty string", "5", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"stdlib\":9,\"output\":5}",
            "'output' must be a non-empty string", "5", 0);
        checkFailure("{\"languageVersion\":\"1.2\",\"externals\":"
                + "{\"b\":{\"declaration\":\"x.deal\"},\"a\":{\"declaration\":1}}}",
            "must be a host declaration file (.d.deal)", "\"x.deal\"", 0);
    }

    // =========================================================================
    // Content-only proof and determinism
    // =========================================================================

    private static void testContentOnlyAndDeterminism() {
        // Referenced files do not exist on disk; the manifest path's
        // directory does not exist either. A successful parse proves the
        // parser performs no filesystem access.
        String json = "{\"languageVersion\":\"1.2\",\"moduleRoots\":[\"missing/root\"],"
            + "\"externals\":{\"host/x\":{\"declaration\":\"/no/such/file.d.deal\","
            + "\"nativeLibrary\":\"missing-lib.so\"}},\"output\":\"build/out\"}";
        StrictManifestParser.StrictManifestParseResult first = parse(json);
        check(first.failure() == null && first.manifest() != null,
            "content whose referenced files do not exist still parses");
        StrictManifestParser.StrictManifestParseResult second = parse(json);
        check(second.failure() == null && second.manifest() != null,
            "second parse succeeds too");
        check(first.equals(second), "parsing the same text twice yields equal results");
        if (first.manifest() != null && second.manifest() != null) {
            check(first.manifest().equals(second.manifest()),
                "manifests are structurally equal across parses");
            check(first.manifest().ranges().equals(second.manifest().ranges()),
                "range maps are equal across parses");
        }
    }

    /** The parser surface carries no java.io / java.nio.file types. */
    private static void testParserSurfaceHasNoIoTypes() {
        Class<?>[] surface = {
            StrictManifestParser.class,
            StrictManifestParser.StrictManifestParseResult.class,
            ProjectConfigValidator.class,
            ProjectConfigValidator.Outcome.class,
            ProjectConfigValidator.Failure.class,
            ProjectManifest.class,
            ManifestString.class,
            NativeLibraryRef.class,
            NativeLibraryRef.Kind.class,
            ExternalEntrySpec.class,
            ConfiguredModuleRoot.class,
            NormalizedDeclarationPath.class,
            ExternalEntry.class,
            StrictJsonValue.class,
            StrictJsonValue.StringVal.class,
            StrictJsonValue.NumberVal.class,
            StrictJsonValue.BoolVal.class,
            StrictJsonValue.NullVal.class,
            StrictJsonValue.ArrayVal.class,
            StrictJsonValue.ObjectVal.class,
            StrictJsonDocument.class,
        };
        for (Class<?> type : surface) {
            for (Method method : type.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                check(!isIoType(method.getReturnType(), method.getGenericReturnType())
                        && !hasIoParameter(method),
                    type.getSimpleName() + "." + method.getName()
                        + " must not expose java.io/java.nio types");
                for (Class<?> exceptionType : method.getExceptionTypes()) {
                    check(!isIoType(exceptionType, exceptionType),
                        type.getSimpleName() + "." + method.getName()
                            + " must not declare IO exception types");
                }
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (!Modifier.isPublic(constructor.getModifiers())) {
                    continue;
                }
                check(!hasIoParameter(constructor),
                    type.getSimpleName() + " constructor must not take IO types");
            }
            if (type.isRecord()) {
                for (RecordComponent component : type.getRecordComponents()) {
                    check(!isIoType(component.getType(), component.getGenericType()),
                        type.getSimpleName() + "." + component.getName()
                            + " must not be an IO type");
                }
            }
        }
    }

    private static boolean hasIoParameter(java.lang.reflect.Executable executable) {
        for (Class<?> parameterType : executable.getParameterTypes()) {
            if (isIoType(parameterType, parameterType)) {
                return true;
            }
        }
        for (Type genericType : executable.getGenericParameterTypes()) {
            if (containsIoType(genericType)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIoType(Class<?> type, Type genericType) {
        return containsIoType(type) || containsIoType(genericType);
    }

    private static boolean containsIoType(Type type) {
        if (type == null) {
            return false;
        }
        String name = type.getTypeName();
        return name.contains("java.io.") || name.contains("java.nio.");
    }

    // =========================================================================
    // Complete E1 scalar ranges
    // =========================================================================

    private static void testCompleteScalarRanges() {
        // Multi-line manifest: exact line/column pins.
        String multiLine = "{\n  \"languageVersion\": \"1.2\",\n  \"moduleRoots\": [\"src\"]\n}";
        checkFailure("{\n  \"languageVersion\": \"1.1\",\n  \"moduleRoots\": [\"src\"]\n}",
            "must be exactly \"1.2\"", "\"1.1\"", 0);
        StrictManifestParser.StrictManifestParseResult multiResult = parse(multiLine);
        check(multiResult.manifest() != null, "multi-line manifest parses");
        if (multiResult.manifest() != null) {
            SourceScalarRange expected = expectedRange(multiLine, "\"1.2\"", 0);
            check(expected.startLine() == 2 && expected.startColumn() == 22
                    && expected.endLine() == 2 && expected.endColumn() == 27,
                "multi-line value range line/column recomputation sane");
            checkManifestRange("multi-line languageVersion range", multiLine,
                multiResult.manifest().ranges().get("languageVersion"), "\"1.2\"", 0);
        }

        // CRLF: two scalars per line break.
        String crlf = "{\r\n  \"languageVersion\": \"1.2\"\r\n}";
        StrictManifestParser.StrictManifestParseResult crlfResult = parse(crlf);
        check(crlfResult.manifest() != null, "CRLF manifest parses");
        if (crlfResult.manifest() != null) {
            checkManifestRange("CRLF languageVersion range", crlf,
                crlfResult.manifest().ranges().get("languageVersion"), "\"1.2\"", 0);
            SourceScalarRange expected = expectedRange(crlf, "\"1.2\"", 0);
            check(expected.startLine() == 2 && expected.startColumn() == 22,
                "CRLF value range starts on line 2 (line break at CR)");
        }

        // Astral escape: the token's scalar range counts the escape
        // spelling, not the decoded scalar.
        String astral = "{\"languageVersion\":\"1.2\",\"moduleRoots\":[\"\\uD83D\\uDE00rc\"]}";
        StrictManifestParser.StrictManifestParseResult astralResult = parse(astral);
        check(astralResult.manifest() != null, "astral escape manifest parses");
        if (astralResult.manifest() != null) {
            check(astralResult.manifest().moduleRoots().get(0).value()
                    .equals("\uD83D\uDE00rc"), "astral pair decodes with trailing text");
            SourceScalarRange expected = expectedRange(astral, "\"\\uD83D\\uDE00rc\"", 0);
            check(expected.endScalarOffset() - expected.startScalarOffset() == 16,
                "escape-spelling token spans 16 scalars");
            checkManifestRange("astral token range", astral,
                astralResult.manifest().moduleRoots().get(0).sourceRange(),
                "\"\\uD83D\\uDE00rc\"", 0);
        }

        // A failure range mid-line after an astral scalar stays exact.
        String astralFail = "{\"languageVersion\":\"1.2\",\"moduleRoots\":[\"\\uD83D\\uDE00\\x\"]}";
        checkFailure(astralFail, "invalid escape sequence in string", "\\x", 0);

        // Tab escape token: 7 scalars.
        String tab = "{\"languageVersion\":\"1.2\",\"moduleRoots\":[\"a\\tb\"]}";
        StrictManifestParser.StrictManifestParseResult tabResult = parse(tab);
        check(tabResult.manifest() != null
                && tabResult.manifest().moduleRoots().get(0).value().equals("a\tb"),
            "\\t escape decodes to tab");
        if (tabResult.manifest() != null) {
            checkManifestRange("tab-escape token range", tab,
                tabResult.manifest().moduleRoots().get(0).sourceRange(), "\"a\\tb\"", 0);
        }

        // Every failure carries a complete SOURCE range with the scalar
        // length invariant; no failure is SYNTHETIC.
        String[] failures = {
            "{\"languageVersion\":1.2}", "{\"languageVersion\":\"1.2\",\"backend\":\"js\"}",
            "{\"languageVersion\":\"1.2\",\"moduleRoots\":[\"/abs\"]}",
            "{\"languageVersion\":\"1.2\",\"externals\":{\"m\":{\"declaration\":\"x.deal\"}}}",
            "{\"languageVersion\":\"1.2\",\"dependencies\":{\"a\":01}}",
        };
        for (String failure : failures) {
            CompilerDiagnostic diagnostic = parse(failure).failure();
            check(diagnostic != null && diagnostic.range() != null
                    && diagnostic.range().origin() == RangeOrigin.SOURCE
                    && diagnostic.range().hasScalarOffsets()
                    && diagnostic.range().scalarLength()
                        == diagnostic.range().endScalarOffset()
                            - diagnostic.range().startScalarOffset(),
                "complete SOURCE range for failure input: " + failure);
        }
    }
}
