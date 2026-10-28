package deal.test;

import deal.semantic.SharedStdlibSemantics;
import deal.semantic.SharedStdlibSemantics.Outcome;
import deal.semantic.SharedStdlibSemantics.StdlibFailure;
import deal.semantic.SharedStdlibSemantics.Value;
import deal.semantic.ir.AnchorId;
import deal.semantic.ir.BoundaryFailure;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.FailureArmId;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailureProjections;
import deal.semantic.ir.SemanticTable;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.UnicodeScalars;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The C11 {@code json} and {@code std} service groups
 * (ISSUE-0908, {@code llvm-native-runtime-value-model} R6/R7/R8/R10,
 * {@code JsonFromClass}/{@code JsonToClass}/{@code StdlibInvoke}).
 *
 * <p>Every case compiles and links the <em>published</em> runtime tree produced
 * by {@link NativeCompiler#deployDeploymentSet(Path)} with the pinned strict
 * C11 flag list at {@code -O0} and {@code -O2}, runs the committed driver
 * ({@code test/native/json_stdlib_driver.c}) and compares its canonical
 * observation lines against expectations derived here:</p>
 *
 * <ol>
 *   <li>the JSON parse/encode round trip, the parse-side numeric tokens and
 *       every closed parse defect against the merged v1.5
 *       {@link SharedStdlibSemantics} ({@code jsonParse}/{@code jsonStringify}),
 *       including the one-based UTF-8 byte position each defect reports;</li>
 *   <li>the v1.5 stringify rules — omission of undefined/function members, the
 *       array substitution, non-finite to null, own-key order, the top-level
 *       undefined result, a path-local cycle versus shared acyclic children and
 *       the closed std/json rejection arm — against the specification's rules
 *       and the failure-arm authority;</li>
 *   <li>every one of the eighteen merged {@code StdlibFunctionId} operations
 *       against {@link SharedStdlibSemantics}, with the Unicode scalar
 *       positions of the string family, the two bound failures, the parameter
 *       and return crossings through the typed-boundary arm and a positive
 *       binary64 epoch {@code nowMillis};</li>
 *   <li>the two emitted class walks ({@code C$fromJson}/{@code C$toJson})
 *       against the specification's {@code @jsonable} rules: declaration order,
 *       required presence, rejected unknown keys, preserved optional absence,
 *       the required-undefined asymmetry, non-finite to null and the walk and
 *       cycle arms;</li>
 *   <li>the stack-safe traversal of both class walks: a recursive
 *       {@code @jsonable} plan decodes a 100000-level document — nested through
 *       the class field and nested through an array — to its innermost level
 *       with the explicit frame stack, exactly as the parser and the encoder
 *       carry no nesting cap;</li>
 *   <li>byte-identical observations at both levels and under
 *       {@code DEAL_GC_STRESS=1}, and the deterministic producer/invariant
 *       defects of the two groups (one stderr line, exit 2).</li>
 * </ol>
 */
public final class NativeJsonStdlibTest {

    private static final int[] LEVELS = {0, 2};

    private static final String FAULT_PREFIX = "DEAL_RUNTIME_FAULT: ";

    private static final String ACCEPTANCE_LINE = "json-stdlib ok\n";

    private static final String ORIGIN_FILE = "native/json-stdlib.deal";
    private static final int ORIGIN_LINE = 12;
    private static final int ORIGIN_COLUMN = 5;

    private static int passed;
    private static int failed;

    private static Path scratch;
    private static Path published;

    public static void main(String[] args) throws Exception {
        System.out.println("=== C11 JSON Runtime / Standard Library Dispatch Tests"
            + " (ISSUE-0908) ===\n");
        scratch = NativeCompiler.executableScratch("native-json-stdlib");
        published = Files.createDirectories(scratch.resolve("published"));
        try {
            int deployed = NativeCompiler.deployDeploymentSet(published);
            checkEq(deal.toolchain.NativeRuntime.FILES.size(), deployed,
                "the published runtime tree carries the closed deployment set");
            System.out.println("native compiler: " + NativeCompiler.compiler().path()
                + " (major " + NativeCompiler.compiler().major() + ")");
            testStdlibIdParity();
            testArmTextAuthority();
            for (int level : LEVELS) {
                runAcceptance(level);
            }
            runNegatives();
        } finally {
            deleteRecursively(scratch);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== C11 JSON Runtime / Standard Library Dispatch Tests"
            + " Passed ===");
    }

    // =========================================================================
    // The closed operation set and the arm-text authority
    // =========================================================================

    /**
     * The published operation ids are the merged v1.5 {@code StdlibFunctionId}
     * ordinals, in canonical order: the emitted {@code STDLIB_CALL} payload's id
     * and the runtime's closed operation table can never drift.
     */
    private static void testStdlibIdParity() throws IOException {
        System.out.println("-- the published operation ids are the merged"
            + " registry's ordinals --");
        String header = Files.readString(
            NativeCompiler.repository().resolve("deal/native/std.h"),
            StandardCharsets.UTF_8);
        deal.semantic.ir.StdlibFunctionId[] operations =
            deal.semantic.ir.StdlibFunctionId.values();
        checkEq(18, operations.length,
            "the merged registry carries the closed eighteen operations");
        Map<String, Integer> published = new LinkedHashMap<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
            "#define\\s+DEAL_STDLIB_([A-Z0-9_]+)\\s+(\\d+)[uU]").matcher(header);
        while (matcher.find()) {
            published.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
        }
        published.remove("OPERATION_COUNT");
        checkEq(operations.length, published.size(),
            "the header publishes one operation id per closed operation");
        for (int index = 0; index < operations.length; index += 1) {
            String name = operations[index].name();
            checkEq(index, published.get(name),
                "the published operation id " + name + " keeps its ordinal");
        }
        java.util.regex.Matcher count = java.util.regex.Pattern.compile(
            "#define\\s+DEAL_STDLIB_OPERATION_COUNT\\s+(\\d+)[uU]").matcher(header);
        check(count.find(), "the header publishes the operation count");
        if (count.find(0)) {
            checkEq(operations.length, Integer.parseInt(count.group(1)),
                "the published operation count equals the closed set");
        }
    }

    /**
     * The std/json rejection arm's pinned expected text is the failure-arm
     * authority's, never a text the runtime authors: the one C constant is
     * compared against the registry so a registry change can never drift
     * silently past the runtime.
     */
    private static void testArmTextAuthority() throws IOException {
        System.out.println("-- the runtime's pinned arm text is the authority's --");
        String source = Files.readString(
            NativeCompiler.repository().resolve("deal/native/json.c"),
            StandardCharsets.UTF_8);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
            "deal_json_stringify_expected =\\s*\"([^\"]*)\"",
            java.util.regex.Pattern.DOTALL).matcher(source);
        check(matcher.find(), "the runtime carries the arm's pinned expected text");
        String pinned = deal.semantic.ir.FailureContractRegistry
            .arm(FailureArmId.JSON_STRINGIFY_UNSUPPORTED).pinnedExpectedText();
        if (matcher.find(0)) {
            checkEq(pinned, matcher.group(1),
                "the runtime's pinned expected text equals the authority's");
        }
        checkEq("string, number, boolean, or table", pinned,
            "the std/json rejection arm's pinned expected text");
    }

    // =========================================================================
    // The acceptance run
    // =========================================================================

    private static void runAcceptance(int level) throws IOException {
        System.out.println("-- the JSON matrix, the stringify rules, every stdlib"
            + " operation and both class walks at -O" + level + " --");
        Path product = scratch.resolve("json-stdlib-o" + level);
        List<Path> sources = new ArrayList<>();
        sources.add(NativeCompiler.fixtures().resolve("json_stdlib_driver.c"));
        sources.add(NativeCompiler.fixtures().resolve("registry_empty.c"));
        for (Path unit : NativeCompiler.runtimeTranslationUnits(published)) {
            if (!unit.getFileName().toString().equals("main.c")) {
                sources.add(unit);
            }
        }
        NativeCompiler.Result link = NativeCompiler.link(published, level, sources,
            List.of("-DDEAL_JSON_CASE=0"), product);
        check(link.succeeded(), "the json/stdlib driver builds at -O" + level
            + ": " + link.describe());
        checkEq("", link.stderr(), "the json/stdlib driver builds without"
            + " diagnostics at -O" + level);
        if (!link.succeeded()) {
            return;
        }
        NativeCompiler.Result normal = NativeCompiler.execute(product, scratch,
            Map.of());
        checkEq(0, normal.exitCode(), "the json/stdlib driver exits zero at -O"
            + level + ": " + normal.describe());
        checkEq("failure line\n", normal.stderr(),
            "the console-error effect is the driver's only stderr bytes at -O"
                + level);
        check(normal.stdout().endsWith(ACCEPTANCE_LINE),
            "the driver reports its exact success line at -O" + level
                + ": " + normal.describe());
        check(!normal.stdout().contains("FAIL:"),
            "every driver self-check passes at -O" + level);
        verifyObservations(normal.stdout(), "at -O" + level);

        NativeCompiler.Result stressed = NativeCompiler.execute(product, scratch,
            Map.of("DEAL_GC_STRESS", "1"));
        checkEq(0, stressed.exitCode(), "the stressed driver exits zero at -O"
            + level + ": " + stressed.describe());
        checkEq(withoutTime(normal.stdout()), withoutTime(stressed.stdout()),
            "DEAL_GC_STRESS changes collection timing only: the json/stdlib driver"
                + " prints byte-identical observations at -O" + level);

        // The encoder's scratch is linear in nesting depth: a deeply nested
        // finite document round-trips inside a bounded address space, which a
        // per-frame concatenated walk position cannot fit.
        NativeCompiler.Result deep = NativeCompiler.execute(product, scratch,
            Map.of("DEAL_JSON_DEEP", "60000"));
        checkEq(0, deep.exitCode(), "the deep linear-scratch round trip exits"
            + " zero at -O" + level + ": " + deep.describe());
        checkEq("DEEP\tlinear-scratch\t60000\t120000\tmatch\n", deep.stdout(),
            "the encoder's scratch stays linear in nesting depth at -O" + level);
        checkEq("", deep.stderr(), "the deep round trip publishes no fault at -O"
            + level);

        // The class decode walk is stack-safe for the same reason: a recursive
        // `@jsonable` plan decoded from a 100000-level document reaches the
        // innermost level through the explicit frame stack, for the chain shape
        // and for the nested-array shape.
        NativeCompiler.Result classDeep = NativeCompiler.execute(product, scratch,
            Map.of("DEAL_JSON_CLASS_DEEP", "100000"));
        checkEq(0, classDeep.exitCode(), "the deep class decode walk exits zero at"
            + " -O" + level + ": " + classDeep.describe());
        checkEq("CLASSDEEP\tchain\t100000\t100000\n"
                + "CLASSDEEP\tarray\t100000\t100000\n", classDeep.stdout(),
            "the class decode walk is stack-safe in nested classes and nested"
                + " arrays at -O" + level);
        checkEq("", classDeep.stderr(), "the deep class decode walk publishes no"
            + " fault at -O" + level);

        if (level == 2) {
            Path reference = scratch.resolve("json-stdlib-o0");
            NativeCompiler.Result at0 = NativeCompiler.execute(reference, scratch,
                Map.of());
            checkEq(withoutTime(at0.stdout()), withoutTime(normal.stdout()),
                "-O0 and -O2 produce byte-identical observations");
        }
    }
    // =========================================================================
    // The expectation set (authored from the shared authorities and the spec)
    // =========================================================================

    /** The emitted call origin every observation is rendered at. */
    private static SourceOrigin origin() {
        return new SourceOrigin(ORIGIN_FILE,
            new SourceSpan(ORIGIN_FILE, ORIGIN_LINE, ORIGIN_COLUMN,
                           ORIGIN_LINE, ORIGIN_COLUMN),
            SourceOriginKind.SYNTHETIC, new AnchorId(1), null);
    }

    private static UnicodeScalars.Valid valid(String text) {
        UnicodeScalars.ScalarString scalar = UnicodeScalars.validate(text);
        if (!(scalar instanceof UnicodeScalars.Valid checked)) {
            throw new AssertionError("the fixture text is not a scalar sequence: "
                + text);
        }
        return checked;
    }

    private static Value success(Outcome<Value> outcome, String what) {
        if (outcome instanceof Outcome.Success<Value> success) {
            return success.value();
        }
        throw new AssertionError(what + ": the shared authority failed: "
            + ((Outcome.Failure<Value>) outcome).failure().failure().message());
    }

    private static BoundaryFailure failureOf(Outcome<Value> outcome, String what) {
        if (outcome instanceof Outcome.Failure<Value> failure) {
            return failure.failure().failure();
        }
        throw new AssertionError(what + ": the shared authority unexpectedly"
            + " succeeded");
    }

    /** One failure's wire tail: code, message, expected, actual and the origin. */
    private static String tail(BoundaryFailure failure) {
        return failure.code().name() + "\t" + failure.message() + "\t"
            + (failure.expected() == null ? "-" : failure.expected()) + "\t"
            + (failure.actual() == null ? "-" : failure.actual()) + "\t"
            + ORIGIN_FILE + "\t" + ORIGIN_LINE + "\t" + ORIGIN_COLUMN + "\t0";
    }

    /** The closed cycle arm, rendered by its own authority. */
    private static BoundaryFailure cycleArm() {
        return FailureContractRegistry.render(FailureArmId.JSON_TO_WALK_CYCLE,
            Map.of(), null, null, null);
    }

    /** The std/json rejection arm for one failing carrier kind. */
    private static BoundaryFailure stringifyArm(
            FailureProjections.CarrierKind carrierKind) {
        return FailureProjections.stringifyUnsupported(carrierKind);
    }

    /** The typed-boundary kind arm for one descriptor and actual token. */
    private static BoundaryFailure kindArm(String descriptorText,
                                           String actualToken) {
        return FailureProjections.kindFailure(descriptorText, actualToken);
    }

    private static String text(Value value, String what) {
        if (!(value instanceof Value.String string)) {
            throw new AssertionError(what + ": expected a string, got "
                + value.actualKind());
        }
        if (!(string.scalar() instanceof UnicodeScalars.Valid valid)) {
            throw new AssertionError(what + ": an invalid scalar carrier");
        }
        return valid.carrier();
    }

    private static String numberBits(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        return String.format("%016x", Double.doubleToRawLongBits(value));
    }

    private static String stdText(String label, String value) {
        return "STD\t" + label + "\tTEXT\t" + value;
    }

    private static String stdNumber(String label, double value) {
        return "STD\t" + label + "\tNUMBER\t" + numberBits(value);
    }

    private static String stdBoolean(String label, boolean value) {
        return "STD\t" + label + "\tBOOLEAN\t" + value;
    }

    private static String stdFailure(String label, BoundaryFailure failure) {
        return "STD\t" + label + "\tERROR\t" + tail(failure);
    }

    /** The round-trip inputs of the JSON matrix, in the driver's order. */
    private static Map<String, String> roundtripInputs() {
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("object", "{\"a\":1,\"b\":[true,false,null],\"c\":\"x\"}");
        inputs.put("empty-array", "[]");
        inputs.put("empty-object", "{}");
        inputs.put("null", "null");
        inputs.put("true", "true");
        inputs.put("string", "\"s\"");
        inputs.put("zero", "0");
        inputs.put("negative-zero", "-0");
        inputs.put("negative-zero-fraction", "-0.0");
        inputs.put("one-e0", "1e0");
        inputs.put("exponent", "1E2");
        inputs.put("tenth", "0.1");
        inputs.put("binary64-boundary", "9007199254740993");
        inputs.put("overflow", "1e400");
        inputs.put("underflow", "1e-400");
        inputs.put("precision", "123456789012345678901234567890");
        inputs.put("large-exponent", "1e21");
        inputs.put("small-exponent", "1e-7");
        inputs.put("negative", "-2.5");
        inputs.put("nested", "{\"a\":{\"b\":{\"c\":[1,[2,[3]]]}}}");
        inputs.put("bmp-scalar", "\"\\u00e9\\u0041\"");
        inputs.put("surrogate-pair", "\"\\ud83d\\ude00\"");
        inputs.put("escapes", "\"tab\\there quote\\\" backslash\\\\\"");
        inputs.put("control-scalars", "\"\\u0000\\u001f\"");
        inputs.put("own-key-order", "{\"b\":1,\"2\":2,\"a\":3,\"1\":4}");
        inputs.put("astral-literal", "\"\uD834\uDD1Eab\"");
        inputs.put("whitespace", " \t\n\r[ 1 , 2 ] \t\n");
        return inputs;
    }

    /** The parse-defect inputs of the JSON matrix, in the driver's order. */
    private static Map<String, String> parseDefects() {
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("unexpected-end-object", "{");
        inputs.put("missing-colon", "{\"a\"");
        inputs.put("unterminated-object", "{\"a\":1");
        inputs.put("unterminated-array", "[1");
        inputs.put("unterminated-string", "\"abc");
        inputs.put("duplicate-key", "{\"a\":1,\"a\":2}");
        inputs.put("duplicate-escaped-key", "{\"a\":1,\"\\u0061\":2}");
        inputs.put("leading-zero", "[01]");
        inputs.put("invalid-number", "[1.]");
        inputs.put("unexpected-character", "[nul]");
        inputs.put("missing-comma", "[1 2]");
        inputs.put("trailing-content", "{\"a\":1}x");
        inputs.put("invalid-escape", "\"\\q\"");
        inputs.put("unpaired-surrogate", "\"\\ud800\"");
        inputs.put("raw-control", "\"a\u0001b\"");
        inputs.put("empty-input", "");
        return inputs;
    }

    /** The numeric tokens whose binary64 decode the driver publishes. */
    private static List<String> numberTokens() {
        return List.of("0", "-0", "1", "1.0", "1e0", "1E2", "0.1",
            "9007199254740993", "4.9406564584124654e-324", "1e400", "1e-400",
            "1.7976931348623157e308", "123456789012345678901234567890", "-2.5");
    }

    /** The class-walk field texts one fromJson/toJson case observes. */
    private static final String TO_PREFIX =
        "{\"name\":\"Ada\",\"bio\":null,\"tags\":[],\"props\":{},\"items\":[]}";

    private static List<String> expectations() {
        List<String> expected = new ArrayList<>();

        // 1. The JSON matrix: parse then encode, through the shared model.
        for (Map.Entry<String, String> entry : roundtripInputs().entrySet()) {
            Value value = success(
                SharedStdlibSemantics.jsonParse(origin(), valid(entry.getValue())),
                entry.getKey());
            String encoded = text(success(
                SharedStdlibSemantics.jsonStringify(origin(), value),
                entry.getKey()), entry.getKey());
            expected.add("RT\t" + entry.getKey() + "\tTEXT\t" + encoded);
        }
        // The own-key order the specification pins explicitly: array-index keys
        // ascending first, then creation order.
        int orderIndex = expected.indexOf(expected.stream()
            .filter(line -> line.startsWith("RT\town-key-order\t"))
            .findFirst().orElseThrow());
        checkEq("RT\town-key-order\tTEXT\t"
                + "{\"1\":4.0,\"2\":2.0,\"b\":1.0,\"a\":3.0}",
            expected.get(orderIndex),
            "own-key order follows the specification's array-index rule");

        // 2. The parse-side binary64 tokens.
        for (String token : numberTokens()) {
            expected.add("NUM\t" + token + "\t"
                + numberBits(Double.parseDouble(token)));
        }

        // 3. The closed parse defects with their one-based byte positions.
        for (Map.Entry<String, String> entry : parseDefects().entrySet()) {
            BoundaryFailure failure = failureOf(
                SharedStdlibSemantics.jsonParse(origin(), valid(entry.getValue())),
                entry.getKey());
            expected.add("PARSE\t" + entry.getKey() + "\tERROR\t" + tail(failure));
        }

        // 3b. A deeply nested finite document round-trips exactly: the
        // stack-safe traversal has no nesting cap.
        expected.add("DEEP\tnested-2000\t2000\t4000\tmatch");

        // 4. The v1.5 encoding rules from the specification's JSON encoding
        // section, and the two closed arms from their own authority.
        expected.add("ENC\trecord-omission\tTEXT\t{\"0\":0,\"kept\":1.0}");
        expected.add("ENC\tarray-substitution\tTEXT\t[1.0,null,null,null]");
        expected.add("ENC\trecord-cycle\tERROR\t" + tail(cycleArm()));
        expected.add("ENC\tshared-children\tTEXT\t[{\"v\":2.0},{\"v\":2.0}]");
        expected.add("ENC\tclass-unsupported\tERROR\t"
            + tail(stringifyArm(FailureProjections.CarrierKind.TABLE)));
        expected.add("ENC\troot-undefined\tUNDEFINED");
        expected.add("ENC\troot-function\tUNDEFINED");

        // 5. The console effect: the argument's exact bytes plus one newline.
        expected.add("BEFORE\tconsole-log");
        expected.add("hello\tworld \u00e9");
        expected.add("STD\tconsole-log\tUNDEFINED");
        expected.add("AFTER\tconsole-log");
        expected.add("BEFORE\tconsole-error");
        expected.add("STD\tconsole-error\tUNDEFINED");
        expected.add("AFTER\tconsole-error");

        // 6. std/string length over the empty, ASCII, BMP and astral cases.
        String[] lengthTexts = {"", "abc", "\u00e9", "\uD834\uDD1Eab"};
        String[] lengthLabels = {"length-empty", "length-ascii", "length-bmp",
                                 "length-astral"};
        for (int index = 0; index < lengthTexts.length; index += 1) {
            Value value = success(SharedStdlibSemantics.stringLength(origin(),
                valid(lengthTexts[index])), lengthLabels[index]);
            expected.add(stdNumber(lengthLabels[index],
                ((Value.Number) value).value()));
        }

        // 7. std/string substring: clamping, reversed ranges, scalar slicing and
        // the two bound failures.
        expected.add(substringExpectation("substring-basic", "hello", 1, 3));
        expected.add(substringExpectation("substring-negative-start", "hello", -5, 3));
        expected.add(substringExpectation("substring-negative-end", "hello", 3, -1));
        expected.add(substringExpectation("substring-reversed", "hello", 4, 2));
        expected.add(substringExpectation("substring-scalars", "h\u00e9llo", 0, 2));
        expected.add(substringExpectation("substring-astral",
            "\uD834\uDD1Eab", 1, 3));
        expected.add(substringExpectation("substring-clamp-high", "hello", 2, 99));
        expected.add(substringExpectation("substring-empty", "", 0, 0));
        expected.add(substringExpectation("substring-fractional", "hello", 1.5, 3));
        expected.add(substringExpectation("substring-nan", "hello",
            Double.NaN, 3));
        expected.add(substringExpectation("substring-infinite", "hello", 0,
            Double.POSITIVE_INFINITY));
        // 7b. Integrality holds over the whole finite binary64 domain: every
        // value of magnitude at least 2^52 is integral, so these bounds clamp
        // instead of raising the SUBSTRING_BOUNDS arm.
        expected.add(substringExpectation("substring-two-to-53", "hello",
            9007199254740992.0, 3));
        expected.add(substringExpectation("substring-above-two-to-53", "hello",
            9007199254740994.0, 3));
        expected.add(substringExpectation("substring-huge-start", "hello",
            1e18, 3));
        expected.add(substringExpectation("substring-huge-end", "hello",
            1, 1e18));
        expected.add(substringExpectation("substring-huge-both", "hello",
            1e300, 2e300));
        expected.add(substringExpectation("substring-negative-huge-start",
            "hello", -1e300, 3));
        expected.add(substringExpectation("substring-negative-huge-end",
            "\uD834\uDD1Eab", 1, -1e300));

        // 8. The literal searches and the replacement rules.
        expected.add(stringExpectation("contains-hit",
            SharedStdlibSemantics.stringContains(origin(), valid("hello"),
                valid("ell"))));
        expected.add(stringExpectation("contains-miss",
            SharedStdlibSemantics.stringContains(origin(), valid("hello"),
                valid("z"))));
        expected.add(stringExpectation("contains-empty",
            SharedStdlibSemantics.stringContains(origin(), valid("hello"),
                valid(""))));
        expected.add(stringExpectation("contains-astral",
            SharedStdlibSemantics.stringContains(origin(),
                valid("\uD834\uDD1Eab"), valid("ab"))));
        expected.add(stringExpectation("startswith-hit",
            SharedStdlibSemantics.stringStartsWith(origin(), valid("hello"),
                valid("he"))));
        expected.add(stringExpectation("startswith-miss",
            SharedStdlibSemantics.stringStartsWith(origin(), valid("hello"),
                valid("lo"))));
        expected.add(stringExpectation("startswith-empty",
            SharedStdlibSemantics.stringStartsWith(origin(), valid("hello"),
                valid(""))));
        expected.add(stringExpectation("endswith-hit",
            SharedStdlibSemantics.stringEndsWith(origin(), valid("hello"),
                valid("lo"))));
        expected.add(stringExpectation("endswith-miss",
            SharedStdlibSemantics.stringEndsWith(origin(), valid("hello"),
                valid("he"))));
        expected.add(stringExpectation("endswith-empty",
            SharedStdlibSemantics.stringEndsWith(origin(), valid("hello"),
                valid(""))));
        expected.add(stringExpectation("replace-all",
            SharedStdlibSemantics.stringReplace(origin(), valid("aaaa"),
                valid("aa"), valid("b"))));
        expected.add(stringExpectation("replace-delete",
            SharedStdlibSemantics.stringReplace(origin(), valid("abc"),
                valid("b"), valid(""))));
        expected.add(stringExpectation("replace-empty-search",
            SharedStdlibSemantics.stringReplace(origin(), valid("aaa"),
                valid(""), valid("x"))));
        expected.add(stringExpectation("replace-literal",
            SharedStdlibSemantics.stringReplace(origin(), valid("a.b.c"),
                valid("."), valid("-"))));

        // 9. std/string split over the empty input, the empty separator and the
        // preserved empty parts.
        expected.addAll(arrayExpectation("split-empty-input",
            SharedStdlibSemantics.stringSplit(origin(), valid(""), valid(","))));
        expected.addAll(arrayExpectation("split-basic",
            SharedStdlibSemantics.stringSplit(origin(), valid("a,b"), valid(","))));
        expected.addAll(arrayExpectation("split-empty-parts",
            SharedStdlibSemantics.stringSplit(origin(), valid(",a,"), valid(","))));
        expected.addAll(arrayExpectation("split-scalars",
            SharedStdlibSemantics.stringSplit(origin(), valid("abc"), valid(""))));
        expected.addAll(arrayExpectation("split-astral",
            SharedStdlibSemantics.stringSplit(origin(), valid("\uD834\uDD1Ea"),
                valid(""))));

        // 10. std/string trim: the closed set only.
        expected.add(stringExpectation("trim-closed-set",
            SharedStdlibSemantics.stringTrim(origin(), valid(" \t\n\r\u000b\u000cx \t"))));
        expected.add(stringExpectation("trim-nbsp",
            SharedStdlibSemantics.stringTrim(origin(), valid("\u00a0x\u00a0"))));
        expected.add(stringExpectation("trim-all",
            SharedStdlibSemantics.stringTrim(origin(), valid("   "))));

        // 11. std/table keys: present keys in the recorded first-insertion order
        // (R2), null- and undefined-valued keys included, a deleted key absent.
        SemanticTable<Value> table = new SemanticTable<>();
        table.put("b", Value.Null.INSTANCE);
        table.put("a", Value.Undefined.INSTANCE);
        table.put("z", new Value.Number(1.0));
        table.put("mid", new Value.Number(1.0));
        table.put("gone", new Value.Number(1.0));
        table.remove("gone");
        expected.addAll(arrayExpectation("table-keys",
            SharedStdlibSemantics.tableKeys(origin(), table)));

        // 12. std/json parse: the record root and the closed return boundary.
        Value record = success(SharedStdlibSemantics.jsonParse(origin(),
            valid("{\"a\":1}")), "parse-record");
        expected.add("STD\tparse-record\tTABLE\t"
            + text(success(SharedStdlibSemantics.jsonStringify(origin(), record),
                "parse-record"), "parse-record"));
        expected.add(stdFailure("parse-array-root",
            kindArm("record", "array")));
        expected.add(stdFailure("parse-number-root",
            kindArm("record", "number")));
        expected.add(stdFailure("parse-null-root", kindArm("record", "null")));

        // 13. std/json stringify through the closed operation.
        SemanticTable<Value> simple = new SemanticTable<>();
        simple.put("k", Value.string("v"));
        expected.add(stdText("stringify-record", text(success(
            SharedStdlibSemantics.jsonStringify(origin(), new Value.Table(simple)),
            "stringify-record"), "stringify-record")));
        expected.add("STD\tstringify-undefined\tUNDEFINED");
        expected.add(stdText("stringify-number", text(success(
            SharedStdlibSemantics.jsonStringify(origin(), new Value.Number(1.5)),
            "stringify-number"), "stringify-number")));

        // 14. std/math: the IEEE results and the negative-sqrt arm.
        expected.add(numberExpectation("floor-positive",
            SharedStdlibSemantics.mathFloor(origin(), 1.5)));
        expected.add(numberExpectation("floor-negative",
            SharedStdlibSemantics.mathFloor(origin(), -0.5)));
        expected.add(numberExpectation("floor-integral",
            SharedStdlibSemantics.mathFloor(origin(), 3.0)));
        expected.add(numberExpectation("floor-negative-zero",
            SharedStdlibSemantics.mathFloor(origin(), -0.0)));
        expected.add(numberExpectation("floor-nan",
            SharedStdlibSemantics.mathFloor(origin(), Double.NaN)));
        expected.add(numberExpectation("floor-infinite",
            SharedStdlibSemantics.mathFloor(origin(), Double.POSITIVE_INFINITY)));
        expected.add(numberExpectation("ceil-positive",
            SharedStdlibSemantics.mathCeil(origin(), 1.5)));
        expected.add(numberExpectation("ceil-negative",
            SharedStdlibSemantics.mathCeil(origin(), -0.5)));
        expected.add(numberExpectation("ceil-integral",
            SharedStdlibSemantics.mathCeil(origin(), 3.0)));
        expected.add(numberExpectation("ceil-negative-zero",
            SharedStdlibSemantics.mathCeil(origin(), -0.0)));
        expected.add(numberExpectation("ceil-nan",
            SharedStdlibSemantics.mathCeil(origin(), Double.NaN)));
        expected.add(numberExpectation("ceil-infinite",
            SharedStdlibSemantics.mathCeil(origin(), Double.NEGATIVE_INFINITY)));
        expected.add(numberExpectation("sqrt-two",
            SharedStdlibSemantics.mathSqrt(origin(), 2.0)));
        expected.add(numberExpectation("sqrt-exact",
            SharedStdlibSemantics.mathSqrt(origin(), 6.25)));
        expected.add(numberExpectation("sqrt-nine",
            SharedStdlibSemantics.mathSqrt(origin(), 9.0)));
        expected.add(numberExpectation("sqrt-zero",
            SharedStdlibSemantics.mathSqrt(origin(), 0.0)));
        expected.add(numberExpectation("sqrt-negative-zero",
            SharedStdlibSemantics.mathSqrt(origin(), -0.0)));
        expected.add(numberExpectation("sqrt-nan",
            SharedStdlibSemantics.mathSqrt(origin(), Double.NaN)));
        expected.add(numberExpectation("sqrt-infinite",
            SharedStdlibSemantics.mathSqrt(origin(), Double.POSITIVE_INFINITY)));
        expected.add(numberExpectation("sqrt-large",
            SharedStdlibSemantics.mathSqrt(origin(), 1e300)));
        expected.add(numberExpectation("sqrt-smallest-subnormal",
            SharedStdlibSemantics.mathSqrt(origin(), 4.9406564584124654e-324)));
        expected.add(numberExpectation("sqrt-rounding",
            SharedStdlibSemantics.mathSqrt(origin(), 2.0e-308)));
        expected.add(stdFailure("sqrt-negative", failureOf(
            SharedStdlibSemantics.mathSqrt(origin(), -1.0), "sqrt-negative")));
        expected.add(numberExpectation("abs-negative-zero",
            SharedStdlibSemantics.mathAbsNumber(origin(), -0.0)));
        expected.add(numberExpectation("abs-negative",
            SharedStdlibSemantics.mathAbsNumber(origin(), -2.5)));
        expected.add(numberExpectation("abs-nan",
            SharedStdlibSemantics.mathAbsNumber(origin(), Double.NaN)));
        expected.add(numberExpectation("abs-infinite",
            SharedStdlibSemantics.mathAbsNumber(origin(),
                Double.NEGATIVE_INFINITY)));

        // 15. std/time.nowMillis: a positive binary64 epoch reading, asserted by
        // the observation verifier rather than by a fixed timestamp.
        expected.add("NOW-MILLIS");

        // 16. The two stdlib crossing failures.
        expected.add(stdFailure("length-kind-failure",
            kindArm("string", "number")));
        expected.add(stdFailure("stringify-nothing-failure",
            kindArm("string", "undefined")));

        // 17. The class walks: authored from docs/spec-v1.5.md's @jsonable rules.
        expected.add("FROM\tcomplete\tTEXT\t" + "{\"name\":\"Ada\",\"bio\":null,"
            + "\"tags\":[\"x\",\"y\"],\"props\":{\"k\":[1.0,2.0]},"
            + "\"items\":[{\"id\":2.0}],\"label\":null}");
        expected.add("FROM\toptional-present\tTEXT\t" + "{\"name\":\"Ada\","
            + "\"nick\":\"A\",\"bio\":\"text\",\"tags\":[],\"props\":{},"
            + "\"items\":[{\"id\":2.0,\"note\":\"n\"}],"
            + "\"label\":\"L\"}");
        expected.add("FROM\textra-key\tNULL");
        expected.add("FROM\tmissing-required\tNULL");
        expected.add("FROM\tmissing-required-nullable\tNULL");
        expected.add("FROM\twrong-kind\tNULL");
        expected.add("FROM\tduplicate-nested-key\tNULL");
        expected.add("FROM\tnested-extra-key\tNULL");
        expected.add("FROM\tarray-element-kind\tNULL");
        expected.add("FROM\tnon-object-root\tNULL");
        expected.add("FROM\tsyntax-defect\tNULL");
        expected.add("FROM\textra-present-undefined-member\tNULL");
        // The required-undefined asymmetry on the decode side: `label!: ?!string`
        // must be present even though its type admits undefined.
        expected.add("FROM\trequired-undefined-omitted\tNULL");
        expected.add("TO\tinstance-minimal\tTEXT\t" + "{\"name\":\"Ada\","
            + "\"bio\":null,\"tags\":[\"x\",\"y\"],\"props\":{\"k\":\"v\"},"
            + "\"items\":[{\"id\":3.0}],\"label\":null}");
        expected.add("TO\tinstance-omits-undefined\tTEXT\t" + "{\"name\":\"Ada\","
            + "\"bio\":null,\"tags\":[],\"props\":{},\"items\":[],"
            + "\"label\":null}");
        expected.add("TO\tmissing-required-field\tERROR\t"
            + tail(walkArm("name", "nil")));
        expected.add("TO\tundefined-required-field\tERROR\t"
            + tail(walkArm("name", "undefined")));
        expected.add("TO\tnonfinite-field\tTEXT\t" + "{\"name\":\"Ada\","
            + "\"bio\":null,\"tags\":[],\"props\":{},\"items\":[{\"id\":null}],"
            + "\"label\":null}");
        expected.add("TO\trecord-content\tTEXT\t" + "{\"name\":\"Ada\","
            + "\"bio\":null,\"tags\":[],\"props\":{\"kept\":[\"a\"]},"
            + "\"items\":[],\"label\":null}");
        expected.add("TO\trecord-class-member\tERROR\t"
            + tail(walkArm("props.nested", "table")));
        expected.add("TO\trecord-cycle\tERROR\t" + tail(cycleArm()));
        // The encode side of the required-undefined asymmetry: the required
        // `label` is present-undefined, so its key is omitted and the encoded
        // text cannot decode back (a missing required key).
        expected.add("TO\trequired-undefined-present\tTEXT\t" + "{\"name\":\"Ada\","
            + "\"bio\":null,\"tags\":[],\"props\":{},\"items\":[]}");
        // A failure projection three segments deep: `props.a.b[0]`, produced by
        // the walk position the encoder materializes only at the failure.
        expected.add("TO\trecord-deep-member\tERROR\t"
            + tail(walkArm("props.a.b[0]", "table")));
        return expected;
    }

    /** The walk arm's own render at one position and one typed-boundary token. */
    private static BoundaryFailure walkArm(String fieldPath, String actual) {
        return FailureContractRegistry.render(FailureArmId.JSON_TO_WALK,
            Map.of("fieldPath", fieldPath, "actual", actual), null, actual, null);
    }

    private static String substringExpectation(String label, String text,
                                               double start, double end) {
        Outcome<Value> outcome = SharedStdlibSemantics.stringSubstring(origin(),
            valid(text), start, end);
        if (outcome instanceof Outcome.Failure<Value> failure) {
            return stdFailure(label, failure.failure().failure());
        }
        return stdText(label, text(success(outcome, label), label));
    }

    private static String stringExpectation(String label, Outcome<Value> outcome) {
        if (outcome instanceof Outcome.Failure<Value> failure) {
            return stdFailure(label, failure.failure().failure());
        }
        Value value = success(outcome, label);
        return switch (value) {
            case Value.Bool bool -> stdBoolean(label, bool.value());
            case Value.String string -> stdText(label, text(string, label));
            case Value.Number number -> stdNumber(label, number.value());
            default -> throw new AssertionError(label + ": unexpected result kind "
                + value.actualKind());
        };
    }

    private static String numberExpectation(String label, Outcome<Value> outcome) {
        if (outcome instanceof Outcome.Failure<Value> failure) {
            return stdFailure(label, failure.failure().failure());
        }
        Value value = success(outcome, label);
        if (!(value instanceof Value.Number number)) {
            throw new AssertionError(label + ": expected a number, got "
                + value.actualKind());
        }
        return stdNumber(label, number.value());
    }

    /** One array result: the count line and one line per element. */
    private static List<String> arrayExpectation(String label,
                                                 Outcome<Value> outcome) {
        List<String> lines = new ArrayList<>();
        Value value = success(outcome, label);
        if (!(value instanceof Value.Array array)) {
            throw new AssertionError(label + ": expected an array, got "
                + value.actualKind());
        }
        lines.add("STD\t" + label + "\tARRAY\t" + array.elements().size());
        for (int index = 0; index < array.elements().size(); index += 1) {
            lines.add("ARR\t" + label + "\t" + index + "\t"
                + text(array.elements().elementAt(index), label + "[" + index + "]"));
        }
        return lines;
    }

    // =========================================================================
    // Verification, negatives and the small harness
    // =========================================================================

    /** Compares every printed observation against the authored expectation. */
    private static void verifyObservations(String stdout, String where) {
        String body = stdout.endsWith(ACCEPTANCE_LINE)
            ? stdout.substring(0, stdout.length() - ACCEPTANCE_LINE.length())
            : stdout;
        List<String> observed = new ArrayList<>();
        for (String line : body.split("\n", -1)) {
            if (!line.isEmpty()) {
                observed.add(line);
            }
        }
        List<String> expected = expectations();
        checkEq(expected.size(), observed.size(),
            where + ": the observation count");
        for (int index = 0; index < Math.min(expected.size(), observed.size());
                index += 1) {
            String want = expected.get(index);
            String got = observed.get(index);
            if (want.equals("NOW-MILLIS")) {
                verifyNowMillis(got, where);
                continue;
            }
            checkEq(want, got, where + ": observation " + index);
        }
    }

    /** The same observation text with the time reading neutralized. */
    private static String withoutTime(String stdout) {
        StringBuilder text = new StringBuilder();
        for (String line : stdout.split("\n", -1)) {
            if (line.startsWith("STD\tnow-millis\t")) {
                text.append("STD\tnow-millis\t<NOW>");
            } else {
                text.append(line);
            }
            text.append('\n');
        }
        return text.toString();
    }

    /** The time selector publishes a positive binary64 epoch reading. */
    private static void verifyNowMillis(String line, String where) {
        String[] fields = line.split("\t", -1);
        if (fields.length != 4 || !fields[0].equals("STD")
                || !fields[1].equals("now-millis")
                || !fields[2].equals("NUMBER")) {
            check(false, where + ": the nowMillis observation: " + line);
            return;
        }
        if (fields[3].equals("NaN")) {
            check(false, where + ": nowMillis is never NaN");
            return;
        }
        double value = Double.longBitsToDouble(
            Long.parseUnsignedLong(fields[3], 16));
        check(Double.isFinite(value) && value > 1.6e12 && value < 4.0e12,
            where + ": nowMillis is a positive binary64 epoch reading, got "
                + value);
    }

    /** One deterministic producer or invariant defect of the two groups. */
    private record Negative(int level, int selection, String reason) { }

    private static void runNegatives() throws IOException {
        System.out.println("-- the deterministic producer/invariant defects --");
        List<Negative> negatives = List.of(
            new Negative(0, 1, "std: operation id outside the closed set"),
            new Negative(0, 2,
                "std: argument count differs from the declared arity"),
            new Negative(0, 3, "json: null parse input"),
            new Negative(0, 4, "json: missing parse origin"),
            new Negative(0, 5, "json: incomplete class plan"),
            new Negative(0, 6, "json: instance does not belong to its plan"),
            new Negative(0, 7,
                "json: array element layout outside the runtime boxed set"),
            new Negative(2, 1, "std: operation id outside the closed set"),
            new Negative(2, 5, "json: incomplete class plan"),
            new Negative(2, 7,
                "json: array element layout outside the runtime boxed set"));
        for (Negative negative : negatives) {
            Path product = scratch.resolve("negative-" + negative.selection()
                + "-o" + negative.level());
            List<Path> sources = new ArrayList<>();
            sources.add(NativeCompiler.fixtures().resolve("json_stdlib_driver.c"));
            sources.add(NativeCompiler.fixtures().resolve("registry_empty.c"));
            for (Path unit : NativeCompiler.runtimeTranslationUnits(published)) {
                if (!unit.getFileName().toString().equals("main.c")) {
                    sources.add(unit);
                }
            }
            NativeCompiler.Result link = NativeCompiler.link(published,
                negative.level(), sources, List.of(), product);
            check(link.succeeded(), "the defect product builds (case "
                + negative.selection() + " at -O" + negative.level() + "): "
                + link.describe());
            if (!link.succeeded()) {
                continue;
            }
            NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
                Map.of("DEAL_JSON_CASE", Integer.toString(negative.selection())));
            checkEq(2, run.exitCode(), "DEAL_JSON_CASE=" + negative.selection()
                + " at -O" + negative.level() + " terminates the process: "
                + run.describe());
            checkEq(FAULT_PREFIX + negative.reason() + "\n", run.stderr(),
                "DEAL_JSON_CASE=" + negative.selection()
                    + " publishes exactly one deterministic stderr line");
            checkEq("", run.stdout(), "DEAL_JSON_CASE=" + negative.selection()
                + " publishes no observation");
        }
    }

    // =========================================================================
    // The small harness
    // =========================================================================

    private static void check(boolean condition, String what) {
        if (condition) {
            passed += 1;
            return;
        }
        failed += 1;
        System.out.println("FAIL: " + what);
    }

    private static void checkEq(Object expected, Object actual, String what) {
        if (java.util.Objects.equals(expected, actual)) {
            passed += 1;
            return;
        }
        failed += 1;
        System.out.println("FAIL: " + what);
        System.out.println("  expected: " + describe(expected));
        System.out.println("  actual:   " + describe(actual));
    }

    private static String describe(Object value) {
        return String.valueOf(value).replace("\t", "\\t");
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder())
                    .toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
