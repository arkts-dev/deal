package deal.test.conformance;

import deal.ast.ImportDeclaration;
import deal.ast.StatementNode;
import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.ModuleResolver.ModuleNotFoundException;
import deal.checker.NameResolver;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.diagnostics.CompilerDiagnostic;
import deal.lexer.Lexer;
import deal.lexer.LexResult;
import deal.module.ModuleShapeValidator;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.semantic.ir.CanonicalJson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * ISSUE-0349 (T2) + ISSUE-0350 (T3) corpus-sidecar validation: the
 * complete sidecar population authored for the corpus is schema-v1 valid
 * and complete.
 *
 * <p>Pins, per the tasks' completeness obligations (the Zero-Skip
 * Classification Contract makes each sidecar set a single completeness
 * obligation, so the whole runtime-ok set and the whole runtime-error set
 * each land in one change):</p>
 * <ol>
 *   <li>Every {@code @expected: runtime-ok} fixture under
 *       {@code test/conformance/backend-runtime/} carries exactly one
 *       sibling {@code <fixture>.expect.json} that validates clean
 *       against {@link SidecarSchemaValidator} schema version 1: exactly
 *       the three backends, mode {@code runtime-ok}, exit code 0, no
 *       {@code error}/{@code sourceFile} field anywhere.</li>
 *   <li>Transcripts follow the C4 authoring rules: stderr is empty
 *       everywhere and stdout is empty except the single
 *       {@code std/console} fixture, whose stdout is exactly
 *       {@code "hello\n"} (the {@code console.log("hello")} string
 *       literal plus the canonical trailing newline of all three
 *       backends).</li>
 *   <li>Every {@code @expected: runtime-error CODE} fixture under
 *       {@code test/conformance/backend-runtime/} carries exactly one
 *       sibling sidecar that validates clean against schema version 1:
 *       exactly the three backends, mode {@code runtime-error}, exit
 *       code 1, empty stderr, and the exact G4.6 lane error framing on
 *       stdout ({@code DEAL_ERROR_CODE: <code>} then
 *       {@code DEAL_ERROR_SNAPSHOT: <canonical JSON>}). The Error
 *       Expectation is the authoritative field set: mandatory
 *       {@code code}/{@code message}/{@code sourceFile}/{@code line}/
 *       {@code column} (the code equals the fixture's {@code @expected}
 *       code; the sourceFile names the module that threw — the fixture
 *       itself or a corpus module of its transitive import closure — in
 *       canonical corpus-relative form), optional {@code expected}/
 *       {@code actual} pinned as the {@code _err} pair where the spec
 *       diagnostic carries them, and no {@code frames}/{@code cause}
 *       pins (today's runtime error values carry no structured frames;
 *       the cause chain is pinned nowhere). The snapshot JSON of the
 *       framing must byte-equal the canonical snapshot serialization
 *       recomputed from the error object: fixed key order
 *       {@code code, message, sourceFile, line, column, expected,
 *       actual, frames, cause}, minimal RFC 8259 §7 escaping, raw UTF-8,
 *       canonical decimal integers, no whitespace between tokens.</li>
 *   <li>The backend-runtime known-fail population is empty since
 *       ISSUE-0339 promoted the last tracked fixture
 *       ({@code bytes-buffer-ops.deal}, which received its runtime-ok
 *       sidecar in the same change that dropped its known-fail marker).
 *       The promoted {@code int-add-overflow.deal} received its
 *       runtime-error sidecar in the same change that dropped its
 *       known-fail marker (ISSUE-0332); the A5 profile-selection merge
 *       then removed the backend-runtime home with its sidecar — the
 *       JS lane's legacy range gate makes a uniform three-backend E8004
 *       sidecar impossible until JS v1.2 int32 lands, so its coverage
 *       re-homed to the two-backend slice surface
 *       ({@code jvm-int32-slice.json#int32-add-overflow}), and this
 *       population excludes it.</li>
 *   <li>All other fixtures ({@code compile-ok}, {@code compile-error},
 *       {@code companion}, frontend fixtures) carry no sidecar except
 *       the Diagnostics-bullet fixtures.</li>
 *   <li>The two Diagnostics-bullet fixtures
 *       ({@code frontend/diagnostics/assignment-mismatch.deal},
 *       {@code frontend/diagnostics/return-mismatch.deal}) carry a
 *       Compile Expectation Sidecar that validates clean, and its pin
 *       matches the real frontend diagnostic set field-exact: exactly one
 *       error diagnostic, equal {@code code}/{@code line}/{@code column}/
 *       {@code message} (the Compile Diagnostic comparison of the gate,
 *       run here against the authored pins).</li>
 * </ol>
 *
 * <p>The test runs from the repository root (the {@code run_tests.sh}
 * contract, like {@code ConformanceTest}); it performs read-only I/O on
 * {@code test/conformance/} and mutates nothing.</p>
 */
public class SidecarCorpusValidationTest {

    private static int passed = 0;
    private static int failed = 0;

    /** The corpus root, relative to the repository root. */
    private static final Path CORPUS_ROOT = Path.of("test", "conformance");

    /** The Diagnostics-bullet fixtures carrying Compile Expectation Sidecars. */
    private static final String DIAG_ASSIGNMENT =
        "frontend/diagnostics/assignment-mismatch.deal";
    private static final String DIAG_RETURN =
        "frontend/diagnostics/return-mismatch.deal";

    /** The only runtime-ok fixture whose transcript carries stdout bytes. */
    private static final String CONSOLE_FIXTURE =
        "backend-runtime/stdlib/console/import-log.deal";
    private static final String CONSOLE_STDOUT = "hello\n";

    /** The exact runtime-ok population (ISSUE-0349 completeness, plus
     * the stdlib/math int-minmax-extremes fixture ISSUE-0337 added with
     * its sidecar in the same change; plus the two async fixtures
     * ISSUE-0335 added with their sidecars in the same change:
     * direct-await-completion-values and async-cross-module-chain; plus
     * the four canonical-boundary runtime-ok fixtures ISSUE-0336 added
     * with their sidecars in the same change; plus the six bytes
     * fixtures ISSUE-0339 added/promoted with their sidecars in the
     * same change: the promoted bytes-buffer-ops and the new
     * bytes-length, bytes-descriptor-boundary,
     * bytes-class-field-descriptor, bytes-write-single-evaluation, and
     * bytes-write-validation-order fixtures; plus the two stdlib/json
     * D5 fixtures ISSUE-0342 added with their sidecars in the same
     * change: int32-boundary-parse and json-stringify-roundtrip; plus
     * the ISSUE-0397 I6 arithmetic/int32-mod-min-neg-one fixture — the
     * truncated remainder -2147483648 % -1 == 0 on all three lanes —
     * added with its uniform sidecar in the same change; plus the
     * host-class-default-isolation fixture ISSUE-0334 added with its
     * sidecar in the same change). */
    private static final int RUNTIME_OK_COUNT = 209;

    /**
     * The exact runtime-error population (ISSUE-0350 completeness, plus
     * the int32 E8004 fixtures ISSUE-0332 promoted/added: the
     * promoted int-add-overflow — later re-homed to the two-backend
     * slice surface by the A5 profile-selection merge, which removed the
     * backend-runtime fixture with its sidecar, so this population
     * excludes it — and the new int-sub-overflow, int-mul-overflow,
     * int-conversion-out-of-range, and source-location/int32-overflow-source
     * fixtures each land their sidecar in the same change as their
     * expectation; plus the stdlib/math int-abs-min-overflow E8004
     * fixture ISSUE-0337 added with its sidecar in the same change; plus
     * the host-async-shape-value E8010 fixture ISSUE-0335 added with its
     * sidecar in the same change; plus the canonical signature-mismatch
     * E8010 fixture ISSUE-0336 added with its sidecar in the same change;
     * plus the six int-neg/bytes error fixtures ISSUE-0339 added with
     * their sidecars in the same change: int-neg-min, bytes-index-bounds,
     * bytes-write-range, source-location/int-neg-min-source,
     * source-location/bytes-index-bounds-source, and
     * source-location/bytes-write-range-source; plus the
     * stdlib/json/json-stringify-bytes-error E8001 fixture ISSUE-0342
     * added with its sidecar in the same change; plus the two
     * ISSUE-0397 I6 arithmetic pow-band fixtures — int32-pow-overflow
     * (2 ** 62 → E8004, the finite band) and int32-pow-infinity
     * (2 ** 1024 → E8001 infinity, the NaN/infinity-first band) — each
     * added with its uniform three-backend sidecar in the same change;
     * plus the host-class-extra-field E8007 fixture ISSUE-0334 added
     * with its sidecar in the same change).
     */
    private static final int RUNTIME_ERROR_COUNT = 80;

    /**
     * ISSUE-0397 count-pin criterion record (MR-0305 review cycles 1
     * and 2, the count-pin finding). The amended criterion — the
     * count-pin amendment prescribed by the MR-0305 review and applied
     * by this tree — pins the count movement as
     * {@code RUNTIME_OK_COUNT 207 -> 208} and
     * {@code RUNTIME_ERROR_COUNT 77 -> 79} with the known-fail
     * population unchanged at the empty set: the written delta (+1
     * runtime-ok, +2 runtime-error, known-fail unchanged) applied to
     * the evolved merge-base pins. The written absolute values
     * ({@code 192 -> 193}, {@code 63 -> 65}, known-fail
     * {@code {backend-runtime/bytes/bytes-buffer-ops.deal}}) were
     * authored against an earlier tree and are unattainable in this
     * one: at the MR's merge base (52a262da, the canonical revision;
     * content-identical to its ancestor 6c4fac5d — and 113c048 already
     * carried the same 207/77 pins with an empty known-fail
     * population) the corpus had grown through the
     * merged sibling issues ISSUE-0332 (signed32 gate), ISSUE-0335
     * (async), ISSUE-0336 (boundaries), ISSUE-0337 (math), ISSUE-0339
     * (bytes — which promoted
     * {@code backend-runtime/bytes/bytes-buffer-ops.deal} to a
     * runtime-ok fixture in commit 8d6a78f), and ISSUE-0342 (json).
     * This record states the amended criterion the tree implements and
     * verifies; the issue-tracker update to the amended wording is
     * flagged on MR-0305 for the issue authority (the implementer's
     * tooling cannot amend the issue record) — the MR-0146 precedent
     * resolved its equivalent criteria conflict on the same basis. The
     * amended values are the only pin set that keeps the corpus honest
     * and every gate green: the corpus carries 208/79, so pins at the
     * written 193/65 would deterministically fail the completeness
     * checks, and reverting the corpus to the written population would
     * revert unrelated merged sibling-issue work (out of scope).
     *
     * ISSUE-0334 lands on the amended 208/79 corpus and adds exactly
     * two host-class fixtures with their sidecars in the same change:
     * the runtime-ok host-class-default-isolation fixture (pins move
     * 208 -> 209) and the runtime-error E8007 host-class-extra-field
     * fixture (pins move 79 -> 80). The known-fail population stays
     * empty. The pins below carry those ISSUE-0334 deltas.
     */

    /** The G4.6 lane error framing prefixes. */
    private static final String DEAL_ERROR_CODE_LINE = "DEAL_ERROR_CODE: ";
    private static final String DEAL_ERROR_SNAPSHOT_LINE = "DEAL_ERROR_SNAPSHOT: ";

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

    // =========================================================================
    // Corpus discovery and classification
    // =========================================================================

    /**
     * One discovered corpus fixture: its corpus-relative slash path, its
     * source text, and its exact {@code @expected} tag.
     */
    private record Fixture(String corpusPath, String source, String expectedTag) {}

    private static List<Fixture> discover() throws Exception {
        List<Fixture> fixtures = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(CORPUS_ROOT)) {
            List<Path> files = stream
                .filter(p -> p.toString().endsWith(".deal"))
                .filter(p -> {
                    Path rel = CORPUS_ROOT.relativize(p);
                    return rel.getNameCount() == 0
                        || !"host-fixtures".equals(rel.getName(0).toString());
                })
                .sorted()
                .toList();
            for (Path file : files) {
                String corpusPath = slash(CORPUS_ROOT.relativize(file));
                String source = Files.readString(file);
                String expectedTag = expectedTagOf(source, corpusPath);
                fixtures.add(new Fixture(corpusPath, source, expectedTag));
            }
        }
        return fixtures;
    }

    /** The exact {@code @expected} tag from the metadata header block. */
    private static String expectedTagOf(String source, String corpusPath) {
        String[] lines = source.split("\n", -1);
        int linesToScan = Math.min(lines.length, 40);
        for (int i = 0; i < linesToScan; i++) {
            String line = lines[i].trim();
            if (line.startsWith("// @expected:")) {
                return line.substring("// @expected:".length()).trim();
            }
        }
        fail(corpusPath + ": no @expected tag — the v1.2 gate has no "
            + "unclassified skips");
        return "";
    }

    private static String slash(Path path) {
        return path.toString().replace(java.io.File.separatorChar, '/');
    }

    // =========================================================================
    // Sidecar validation
    // =========================================================================

    private static Optional<SidecarSchemaValidator.ClassificationFailure>
            validateSidecar(Fixture fixture, String sidecarText,
                            List<SidecarSchemaValidator.CompilationModule> compilationSet,
                            Set<String> corpusIndex) {
        SidecarSchemaValidator.ValidationContext context =
            new SidecarSchemaValidator.ValidationContext(
                fixture.corpusPath(), fixture.expectedTag(),
                compilationSet, corpusIndex);
        return SidecarSchemaValidator.validate(context, sidecarText);
    }

    private static Optional<SidecarSchemaValidator.ClassificationFailure>
            validateSidecar(Fixture fixture, String sidecarText,
                            Set<String> corpusIndex) {
        return validateSidecar(fixture, sidecarText,
            List.of(new SidecarSchemaValidator.CompilationModule(
                fixture.corpusPath(), fixture.source())),
            corpusIndex);
    }

    /** No object key may be {@code error} or {@code sourceFile} anywhere. */
    private static void checkNoErrorField(CanonicalJson.Value value, String path,
                                          String fixture) {
        if (value instanceof CanonicalJson.Obj obj) {
            for (CanonicalJson.Entry entry : obj.entries()) {
                String key = entry.key();
                if (key.equals("error") || key.equals("sourceFile")) {
                    fail(fixture + ": a runtime-ok sidecar must not carry a "
                        + "\"" + key + "\" field (found at " + path + ")");
                }
                checkNoErrorField(entry.value(), path + "." + key, fixture);
            }
        } else if (value instanceof CanonicalJson.Arr arr) {
            int i = 0;
            for (CanonicalJson.Value item : arr.items()) {
                checkNoErrorField(item, path + "[" + i + "]", fixture);
                i++;
            }
        }
    }

    // =========================================================================
    // Checks per classification
    // =========================================================================

    private static void validateRuntimeOkFixture(Fixture fixture,
            Path sidecarPath, Set<String> corpusIndex) throws Exception {
        if (!Files.exists(sidecarPath)) {
            fail(fixture.corpusPath() + ": runtime-ok fixture is missing its "
                + "Structured Expectation Sidecar " + sidecarPath.getFileName());
            return;
        }
        String sidecarText = Files.readString(sidecarPath);
        Optional<SidecarSchemaValidator.ClassificationFailure> failure =
            validateSidecar(fixture, sidecarText, corpusIndex);
        if (failure.isPresent()) {
            fail(failure.get().message());
            return;
        }
        check(true, fixture.corpusPath()
            + ": runtime-ok sidecar validates clean (schema v1)");

        // Transcript authoring pins (C4): stderr always empty; stdout empty
        // except the single std/console fixture.
        CanonicalJson.Value root = CanonicalJson.parse(sidecarText);
        checkNoErrorField(root, "<root>", fixture.corpusPath());
        CanonicalJson.Obj expected = (CanonicalJson.Obj)
            ((CanonicalJson.Obj) root).entries().stream()
                .filter(e -> e.key().equals("expected"))
                .findFirst().orElseThrow().value();
        CanonicalJson.Obj transcript = (CanonicalJson.Obj)
            expected.entries().stream()
                .filter(e -> e.key().equals("transcript"))
                .findFirst().orElseThrow().value();
        String stdout = stringField(transcript, "stdout");
        String stderr = stringField(transcript, "stderr");
        if (fixture.corpusPath().equals(CONSOLE_FIXTURE)) {
            check(stdout.equals(CONSOLE_STDOUT), CONSOLE_FIXTURE
                + ": stdout must be exactly " + describe(CONSOLE_STDOUT)
                + " (the console.log(\"hello\") literal plus the canonical "
                + "trailing newline), got " + describe(stdout));
        } else {
            check(stdout.isEmpty(), fixture.corpusPath()
                + ": runtime-ok transcripts are authored empty — the fixture "
                + "performs no std/console write, got stdout "
                + describe(stdout));
        }
        check(stderr.isEmpty(), fixture.corpusPath()
            + ": stderr must be empty on runtime-ok, got " + describe(stderr));
    }

    private static void validateRuntimeErrorFixture(Fixture fixture,
            Path sidecarPath, Set<String> corpusIndex,
            Map<String, Fixture> corpusByPath) throws Exception {
        if (!Files.exists(sidecarPath)) {
            fail(fixture.corpusPath() + ": runtime-error fixture is missing "
                + "its Structured Expectation Sidecar "
                + sidecarPath.getFileName());
            return;
        }
        String sidecarText = Files.readString(sidecarPath);
        List<SidecarSchemaValidator.CompilationModule> compilationSet =
            compilationSetFor(fixture, corpusByPath);
        Optional<SidecarSchemaValidator.ClassificationFailure> failure =
            validateSidecar(fixture, sidecarText, compilationSet, corpusIndex);
        if (failure.isPresent()) {
            fail(failure.get().message());
            return;
        }
        check(true, fixture.corpusPath()
            + ": runtime-error sidecar validates clean (schema v1)");

        CanonicalJson.Value root = CanonicalJson.parse(sidecarText);
        CanonicalJson.Obj expected = (CanonicalJson.Obj)
            ((CanonicalJson.Obj) root).entries().stream()
                .filter(e -> e.key().equals("expected"))
                .findFirst().orElseThrow().value();
        CanonicalJson.Obj error = (CanonicalJson.Obj)
            expected.entries().stream()
                .filter(e -> e.key().equals("error"))
                .findFirst().orElseThrow().value();

        // Error Expectation field audits (sidecar-authoritative field set).
        String code = stringField(error, "code");
        String pinnedCode = fixture.expectedTag().startsWith("runtime-error ")
            ? fixture.expectedTag().substring("runtime-error ".length()).trim()
            : "";
        check(!pinnedCode.isEmpty() && code.equals(pinnedCode),
            fixture.corpusPath() + ": error.code must equal the fixture's "
                + "@expected code " + describe(pinnedCode) + ", got "
                + describe(code));
        String message = stringField(error, "message");
        check(!message.isEmpty(), fixture.corpusPath()
            + ": error.message must be the exact canonical template "
            + "instantiation, not an empty placeholder");
        int line = intField(error, "line");
        int column = intField(error, "column");
        check(line >= 1, fixture.corpusPath()
            + ": error.line must trace to the throwing site (a positive "
            + "line), got " + line);
        check(column >= 1, fixture.corpusPath()
            + ": error.column must trace to the throwing site (a positive "
            + "column), got " + column);
        check(!hasErrorField(error, "frames"), fixture.corpusPath()
            + ": no runtime-error sidecar pins frames — today's runtime "
            + "error values carry no structured frames");
        check(!hasErrorField(error, "cause"), fixture.corpusPath()
            + ": no runtime-error sidecar pins cause — the cause chain is "
            + "deterministic nowhere in the authored population");
        boolean hasExpected = hasErrorField(error, "expected");
        boolean hasActual = hasErrorField(error, "actual");
        check(hasExpected == hasActual, fixture.corpusPath()
            + ": error.expected/error.actual are pinned as the _err pair "
            + "(the spec diagnostic carries both or neither), got expected="
            + hasExpected + ", actual=" + hasActual);
        if (hasExpected) {
            check(!stringField(error, "expected").isEmpty(),
                fixture.corpusPath() + ": error.expected must be the exact "
                    + "spec type name, not an empty placeholder");
            check(!stringField(error, "actual").isEmpty(),
                fixture.corpusPath() + ": error.actual must be the exact "
                    + "runtime kind, not an empty placeholder");
        }

        // Transcript framing audit (G4.6): stdout must be exactly the two
        // framing lines — no runtime-error fixture console-writes before
        // its error today (a future console-writing fixture updates this
        // pin, the CONSOLE_FIXTURE pattern); stderr must be empty.
        CanonicalJson.Obj transcript = (CanonicalJson.Obj)
            expected.entries().stream()
                .filter(e -> e.key().equals("transcript"))
                .findFirst().orElseThrow().value();
        String stdout = stringField(transcript, "stdout");
        String stderr = stringField(transcript, "stderr");
        check(stderr.isEmpty(), fixture.corpusPath()
            + ": stderr must be empty on runtime-error, got " + describe(stderr));
        String canonicalSnapshot = canonicalSnapshotOf(error, fixture.corpusPath());
        String expectedStdout = DEAL_ERROR_CODE_LINE + code + "\n"
            + DEAL_ERROR_SNAPSHOT_LINE + canonicalSnapshot + "\n";
        check(stdout.equals(expectedStdout), fixture.corpusPath()
            + ": stdout must be exactly the two G4.6 lane framing lines with "
            + "the canonical snapshot serialization (fixed key order, minimal "
            + "escaping, no whitespace between tokens), got "
            + describe(stdout));
    }

    /**
     * The canonical snapshot serialization of the error object (corpus
     * C2): fixed key order {@code code, message, sourceFile, line, column,
     * expected, actual, frames, cause}; only pinned fields emitted; no
     * whitespace between tokens; strings with minimal RFC 8259 §7 escaping
     * (only {@code "} → {@code \"}, {@code \\} → {@code \\\\}, and
     * U+0000–U+001F as the named escapes or {@code \\u00XX} with uppercase
     * hex; non-ASCII raw UTF-8, {@code /} never escaped); integers as
     * canonical decimal.
     */
    private static String canonicalSnapshotOf(CanonicalJson.Obj error,
                                              String fixturePath) {
        String[] order = { "code", "message", "sourceFile", "line", "column",
            "expected", "actual", "frames", "cause" };
        Map<String, CanonicalJson.Value> fields = fieldsOf(error);
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (String key : order) {
            CanonicalJson.Value value = fields.get(key);
            if (value == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(escapeString(key)).append(':');
            if (value instanceof CanonicalJson.Str str) {
                sb.append(escapeString(str.value()));
            } else if (value instanceof CanonicalJson.Int integer) {
                sb.append(integer.value());
            } else {
                fail(fixturePath + ": error field " + key
                    + " must be a string or an integer for the canonical "
                    + "snapshot serialization");
                sb.append("null");
            }
        }
        sb.append('}');
        return sb.toString();
    }

    /** Minimal RFC 8259 §7 string escaping (C2 canonical snapshot rules). */
    private static String escapeString(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\f' -> sb.append("\\f");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04X", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static Map<String, CanonicalJson.Value> fieldsOf(CanonicalJson.Obj obj) {
        Map<String, CanonicalJson.Value> fields = new HashMap<>();
        for (CanonicalJson.Entry entry : obj.entries()) {
            fields.put(entry.key(), entry.value());
        }
        return fields;
    }

    private static boolean hasErrorField(CanonicalJson.Obj error, String key) {
        for (CanonicalJson.Entry entry : error.entries()) {
            if (entry.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static String stringField(CanonicalJson.Obj obj, String key) {
        CanonicalJson.Value value = obj.entries().stream()
            .filter(e -> e.key().equals(key))
            .findFirst().orElseThrow().value();
        return ((CanonicalJson.Str) value).value();
    }

    private static int intField(CanonicalJson.Obj obj, String key) {
        CanonicalJson.Value value = obj.entries().stream()
            .filter(e -> e.key().equals(key))
            .findFirst().orElseThrow().value();
        return ((CanonicalJson.Int) value).value();
    }

    private static String describe(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\n", "\\n")
            .replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    // =========================================================================
    // Compilation-set resolution (transitive corpus import closure)
    // =========================================================================

    /**
     * The fixture's compilation set for the sidecar {@code sourceFile}
     * check: the fixture itself plus every transitively imported corpus
     * module, each with its corpus-relative path and source text. Relative
     * imports ({@code ./}, {@code ../}) resolve against the importing
     * module's corpus directory with the {@code .deal}/{@code .d.deal}
     * fallback; stdlib and host imports resolve outside the corpus and
     * contribute no module (the validator's {@code sourceFile} rule never
     * names them).
     */
    private static List<SidecarSchemaValidator.CompilationModule>
            compilationSetFor(Fixture fixture, Map<String, Fixture> corpusByPath) {
        List<SidecarSchemaValidator.CompilationModule> set = new ArrayList<>();
        collectCompilationModules(fixture.corpusPath(), fixture.source(),
            corpusByPath, set, new HashSet<>());
        return set;
    }

    private static void collectCompilationModules(String corpusPath,
            String source, Map<String, Fixture> corpusByPath,
            List<SidecarSchemaValidator.CompilationModule> set,
            Set<String> inProgress) {
        if (!inProgress.add(corpusPath)) {
            return; // cycle guard — the module is already emitted once
        }
        set.add(new SidecarSchemaValidator.CompilationModule(corpusPath, source));
        for (String importPath : relativeImportPaths(source)) {
            String resolved = resolveRelativeImport(corpusPath, importPath);
            if (resolved == null) {
                continue;
            }
            Fixture dependency = corpusByPath.get(resolved);
            if (dependency != null) {
                collectCompilationModules(dependency.corpusPath(),
                    dependency.source(), corpusByPath, set, inProgress);
            }
        }
    }

    /** Every relative-import module path of the source, via the real parser. */
    private static List<String> relativeImportPaths(String source) {
        List<String> paths = new ArrayList<>();
        LexResult lex = new Lexer(source, "<sidecar-compilation-set>").tokenize();
        if (lex.hasErrors()) {
            return paths;
        }
        ParseResult result = new Parser(lex.tokens(), "<sidecar-compilation-set>")
            .parse();
        if (result.hasErrors()) {
            return paths;
        }
        for (StatementNode statement : result.program().statements()) {
            if (statement instanceof ImportDeclaration decl) {
                if (decl.modulePath().startsWith("./")
                        || decl.modulePath().startsWith("../")) {
                    paths.add(decl.modulePath());
                }
            }
        }
        return paths;
    }

    /**
     * Resolves a relative import against the importing module's corpus
     * directory, mirroring {@code ConformanceTest.resolveCompanionPath}:
     * the raw path, then the {@code .deal} and {@code .d.deal} extensions.
     * Returns the corpus-relative slash path, or {@code null} when the
     * resolution leaves the corpus or no file exists.
     */
    private static String resolveRelativeImport(String importer, String importPath) {
        Path parent = Path.of(importer).getParent();
        Path base = parent == null ? CORPUS_ROOT : CORPUS_ROOT.resolve(parent);
        for (Path candidate : new Path[] {
                base.resolve(importPath).normalize(),
                base.resolve(importPath + ".deal").normalize(),
                base.resolve(importPath + ".d.deal").normalize() }) {
            if (!candidate.startsWith(CORPUS_ROOT)) {
                continue;
            }
            if (Files.exists(candidate)) {
                return slash(CORPUS_ROOT.relativize(candidate));
            }
        }
        return null;
    }

    private static void validateCompileSidecarFixture(Fixture fixture,
            Path sidecarPath, Set<String> corpusIndex) throws Exception {
        if (!Files.exists(sidecarPath)) {
            fail(fixture.corpusPath() + ": the Diagnostics-bullet fixture is "
                + "missing its Compile Expectation Sidecar "
                + sidecarPath.getFileName());
            return;
        }
        String sidecarText = Files.readString(sidecarPath);
        Optional<SidecarSchemaValidator.ClassificationFailure> failure =
            validateSidecar(fixture, sidecarText, corpusIndex);
        if (failure.isPresent()) {
            fail(failure.get().message());
            return;
        }
        check(true, fixture.corpusPath()
            + ": Compile Expectation Sidecar validates clean (schema v1)");

        // Compile Diagnostic comparison: the fixture must emit exactly one
        // error diagnostic equal to the pin field-exact (code, line, column,
        // message — all four are pinned on the Diagnostics-bullet fixtures).
        CanonicalJson.Value root = CanonicalJson.parse(sidecarText);
        CanonicalJson.Obj diagnostic = (CanonicalJson.Obj)
            ((CanonicalJson.Obj) root).entries().stream()
                .filter(e -> e.key().equals("diagnostic"))
                .findFirst().orElseThrow().value();
        String pinCode = stringField(diagnostic, "code");
        int pinLine = intField(diagnostic, "line");
        int pinColumn = intField(diagnostic, "column");
        String pinMessage = stringField(diagnostic, "message");

        List<CompilerDiagnostic> errors = frontendErrors(fixture);
        if (errors.size() != 1) {
            fail(fixture.corpusPath() + ": COMPILE_DIAGNOSTIC_MISMATCH — the "
                + "fixture must emit exactly one error diagnostic, got "
                + errors.size());
            return;
        }
        CompilerDiagnostic d = errors.get(0);
        compareDiagnosticField(fixture.corpusPath(), "code",
            pinCode, d.code());
        compareDiagnosticField(fixture.corpusPath(), "line",
            String.valueOf(pinLine), String.valueOf(d.line()));
        compareDiagnosticField(fixture.corpusPath(), "column",
            String.valueOf(pinColumn), String.valueOf(d.column()));
        compareDiagnosticField(fixture.corpusPath(), "message",
            pinMessage, d.message());
    }

    private static void compareDiagnosticField(String fixture, String field,
            String expected, String actual) {
        check(expected.equals(actual), fixture
            + ": COMPILE_DIAGNOSTIC_MISMATCH — diagnostic." + field
            + " must be " + describe(expected) + ", got " + describe(actual));
    }

    // =========================================================================
    // Real frontend compilation (the Compile Diagnostic comparison source)
    // =========================================================================

    /**
     * Runs the backend-neutral frontend pipeline (lexer → parser → module
     * shape gate → name resolution → type checker) and returns every error
     * diagnostic, mirroring {@code ConformanceTest.compileAndGetDiagnostics}
     * with a resolver that rejects every module (the Diagnostics-bullet
     * fixtures import nothing).
     */
    @SuppressWarnings("deprecation")
    private static List<CompilerDiagnostic> frontendErrors(Fixture fixture) {
        String filename = fixture.corpusPath();
        String source = fixture.source();
        List<CompilerDiagnostic> errors = new ArrayList<>();

        LexResult lex = new Lexer(source, filename).tokenize();
        for (CompilerDiagnostic d : lex.diagnostics()) {
            if ("error".equals(d.severity())) { errors.add(d); }
        }
        if (lex.hasErrors()) { return errors; }

        Parser parser = new Parser(lex.tokens(), filename);
        ParseResult parseResult = parser.parse();
        for (CompilerDiagnostic d : parseResult.diagnostics()) {
            if ("error".equals(d.severity())) { errors.add(d); }
        }
        if (parseResult.hasErrors()) { return errors; }

        for (CompilerDiagnostic d : ModuleShapeValidator.validate(
                parseResult.program(), filename, false)) {
            if ("error".equals(d.severity())) { errors.add(d); }
        }
        if (!errors.isEmpty()) { return errors; }

        ModuleResolver resolver = new ModuleResolver() {
            @Override
            public java.util.Map<String, deal.types.Type> resolveModule(
                    String modulePath, String importingModule,
                    Set<String> modulesInProgress) throws ModuleNotFoundException {
                throw new ModuleNotFoundException(
                    "Module not found: " + modulePath);
            }

            @Override
            public Symbol.ClassSymbol resolveClassSymbol(String className,
                    String modulePath, String importingModule)
                    throws ModuleNotFoundException {
                return null;
            }
        };
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable;
        try {
            symTable = nr.resolve(parseResult.program());
        } catch (Exception e) {
            errors.add(CompilerDiagnostic.synthetic("E9999", "error",
                e.getMessage(), filename,
                "missing anchor: fixture source '" + filename + "'"));
            return errors;
        }
        for (CompilerDiagnostic d : nr.diagnostics()) {
            if ("error".equals(d.severity())) { errors.add(d); }
        }
        CheckResult result = TypeChecker.check(filename, symTable, nr,
            parseResult.program());
        for (CompilerDiagnostic d : result.diagnostics()) {
            if ("error".equals(d.severity())) { errors.add(d); }
        }
        return errors;
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Sidecar Corpus Validation Test "
            + "(ISSUE-0349 + ISSUE-0350) ===\n");

        List<Fixture> fixtures = discover();

        // Corpus module index: every discovered corpus-relative module path
        // (the sourceFile check resolves against it).
        Set<String> corpusIndex = new LinkedHashSet<>();
        Map<String, Fixture> corpusByPath = new HashMap<>();
        for (Fixture fixture : fixtures) {
            corpusIndex.add(fixture.corpusPath());
            corpusByPath.put(fixture.corpusPath(), fixture);
        }

        // The exact sidecar paths the corpus may carry: one per runtime-ok
        // fixture, one per runtime-error fixture, plus the two
        // Diagnostics-bullet Compile Expectation Sidecars.
        Set<String> allowedSidecars = new HashSet<>();
        int runtimeOk = 0;
        int runtimeError = 0;
        for (Fixture fixture : fixtures) {
            boolean isRuntimeOk = "runtime-ok".equals(fixture.expectedTag());
            boolean isRuntimeError =
                fixture.expectedTag().startsWith("runtime-error ");
            boolean isDiagnosticsPin = fixture.corpusPath().equals(DIAG_ASSIGNMENT)
                || fixture.corpusPath().equals(DIAG_RETURN);
            Path sidecarPath = sidecarFor(fixture.corpusPath());

            if (isRuntimeOk) {
                runtimeOk++;
                allowedSidecars.add(sidecarPath.toString());
                validateRuntimeOkFixture(fixture, sidecarPath, corpusIndex);
            } else if (isRuntimeError) {
                runtimeError++;
                allowedSidecars.add(sidecarPath.toString());
                validateRuntimeErrorFixture(fixture, sidecarPath, corpusIndex,
                    corpusByPath);
            } else if (isDiagnosticsPin) {
                allowedSidecars.add(sidecarPath.toString());
                validateCompileSidecarFixture(fixture, sidecarPath, corpusIndex);
            } else {
                check(!Files.exists(sidecarPath), fixture.corpusPath()
                    + ": no sidecar is authored for this classification yet "
                    + "(" + fixture.expectedTag() + " — known-fail sidecars "
                    + "land at the zero-skip flip; compile/companion/frontend "
                    + "fixtures carry none except the Diagnostics-bullet "
                    + "fixtures), found "
                    + sidecarPath.getFileName());
            }
        }

        // Completeness: the runtime-ok population and the runtime-error
        // population are each their task's whole set.
        check(runtimeOk == RUNTIME_OK_COUNT, "the corpus must carry exactly "
            + RUNTIME_OK_COUNT + " runtime-ok fixtures with sidecars, found "
            + runtimeOk);
        check(runtimeError == RUNTIME_ERROR_COUNT, "the corpus must carry "
            + "exactly " + RUNTIME_ERROR_COUNT + " runtime-error fixtures "
            + "with sidecars, found " + runtimeError);

        // The tracked known-fail population is empty: the last tracked
        // backend-runtime known-fail (bytes-buffer-ops; int-add-overflow
        // was promoted by ISSUE-0332 and its backend-runtime home was
        // removed by the A5 profile-selection merge, whose coverage
        // re-homes to the two-backend slice surface) was promoted by
        // ISSUE-0339, so no backend-runtime fixture carries a known-fail
        // marker — and no pin may name the removed fixture.
        Set<String> knownFail = new TreeSet<>();
        for (Fixture fixture : fixtures) {
            if (fixture.corpusPath().startsWith("backend-runtime/")
                    && fixture.expectedTag().startsWith("known-fail ")) {
                knownFail.add(fixture.corpusPath());
            }
        }
        Set<String> expectedKnownFail = new TreeSet<>();
        check(knownFail.equals(expectedKnownFail),
            "the tracked known-fail population must be exactly "
                + expectedKnownFail + ", got " + knownFail);

        // No stray sidecar anywhere in the corpus.
        try (Stream<Path> stream = Files.walk(CORPUS_ROOT)) {
            for (Path sidecar : stream
                    .filter(p -> p.toString().endsWith(".expect.json"))
                    .sorted()
                    .toList()) {
                check(allowedSidecars.contains(sidecar.toString()),
                    "stray sidecar file " + CORPUS_ROOT.relativize(sidecar)
                        + " — a sidecar may exist only next to a runtime-ok "
                        + "fixture, a runtime-error fixture, or the "
                        + "Diagnostics-bullet fixtures");
            }
        }

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** The sibling sidecar path for a corpus-relative fixture path. */
    private static Path sidecarFor(String corpusPath) {
        String stem = corpusPath.endsWith(".deal")
            ? corpusPath.substring(0, corpusPath.length() - ".deal".length())
            : corpusPath;
        return CORPUS_ROOT.resolve(stem + ".expect.json");
    }
}
