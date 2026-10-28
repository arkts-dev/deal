package deal.test;

import deal.semantic.ir.ActualKind;
import deal.semantic.ir.BoundaryContext;
import deal.semantic.ir.BoundaryExecutor;
import deal.semantic.ir.BoundaryFailure;
import deal.semantic.ir.BoundaryOutcome;
import deal.semantic.ir.BoundaryValueView;
import deal.semantic.ir.FailureArm;
import deal.semantic.ir.FailureArmId;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.UnicodeScalars;
import deal.toolchain.NativeRuntime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The C11 descriptor atoms, typed boundaries, class construction and Error
 * materialization contract (ISSUE-0907, {@code llvm-native-runtime-value-model}
 * R2/R4/R5/R10, {@code CheckValue}/{@code ConstructClass}/{@code
 * FailureAdmission}).
 *
 * <p>Every case compiles and links the <em>published</em> runtime tree produced
 * by {@link NativeCompiler#deployDeploymentSet(Path)} with the pinned strict C11
 * flag list at {@code -O0} and {@code -O2}. The observations are printed by the
 * committed driver ({@code test/native/boundary_driver.c}) in one canonical
 * tab-separated form and the expectations are <em>derived</em> here from the
 * shared failure-projection authority — {@link BoundaryExecutor} for the
 * boundary checks and {@link FailureContractRegistry} for the arm renders — so
 * no observation becomes its own expectation.</p>
 *
 * <p>Covered criteria:</p>
 *
 * <ol>
 *   <li>the canonical descriptor grammar and its interned atoms over the
 *       specification's own examples, the nullish forms, the class atoms and
 *       the sync/async signatures with a result-only {@code void};</li>
 *   <li>the descriptor-driven check at every closed boundary kind: the typed
 *       boundary, the function signature, the host parameter and host return
 *       cells and the awaited completion, each with its arm's exact code,
 *       template instantiation, declared expected/actual projection and the
 *       supplied one-based origin, and the passing forms (nullish members,
 *       {@code unknown} admission, persistent array element descriptors,
 *       nominal class identity, carried signatures, absent reads projecting
 *       undefined);</li>
 *   <li>the scalar boundary helpers, which publish the same arm tuple and
 *       project the native payload on success;</li>
 *   <li>class construction: required presence, omitted optionals, present
 *       null/undefined against the declared store type, the E8007 extra-field
 *       rejection preceding every field boundary, declaration-order field
 *       validation at each field's own origin and a tag published only after
 *       success;</li>
 *   <li>every merged arm materialized through the installed table with its own
 *       code, template, declared parameters and declared fields — including the
 *       host-load E8011 arms and the E8003 leaf cause — and the E8002
 *       array-position projections;</li>
 *   <li>the canonical floating-point text of a numeric arm actual;</li>
 *   <li>the two R5 failure classes: host-authored text with no invented origin
 *       or frames, and the unchanged admission of a carried {@code deal_error}
 *       whose identity, fields, origin, frames and cause survive mutation,
 *       snapshot, re-admission and repeated full collections;</li>
 *   <li>the fail-closed producer defects: malformed descriptor text, an
 *       unknown or inner-only arm, a parameter-shape or declared-field
 *       mismatch, a duplicated or missing construction field, a call-frame or
 *       boundary mismatch, and — through the delivered install entry — an
 *       unknown arm, a missing arm and a shape mismatch in the emitted table
 *       before any user code runs;</li>
 *   <li>the C-side compiled repertoire equals the closed arm authority arm for
 *       arm, and the {@code DEAL_ARM_ID_*} identities equal their canonical
 *       ordinals.</li>
 * </ol>
 */
public final class NativeBoundaryErrorsTest {

    private static final int[] LEVELS = {0, 2};

    private static final String FAULT_PREFIX = "DEAL_RUNTIME_FAULT: ";

    private static final String ACCEPTANCE_LINE = "boundary ok\n";

    private static final String CLASS_USER = "@src/app/user.deal/User";
    private static final String CLASS_OTHER = "@src/app/user.deal/Other";

    private static final String ORIGIN_FILE = "native/boundary.deal";

    private static int passed;
    private static int failed;

    private static Path scratch;
    private static Path published;

    public static void main(String[] args) throws Exception {
        System.out.println("=== C11 Descriptor / Typed Boundary / Class Construction /"
            + " Error Materialization Tests (ISSUE-0907) ===\n");
        scratch = NativeCompiler.executableScratch("native-boundary");
        published = Files.createDirectories(scratch.resolve("published"));
        try {
            int deployed = NativeCompiler.deployDeploymentSet(published);
            checkEq(NativeRuntime.FILES.size(), deployed,
                "the published runtime tree carries the closed deployment set");
            for (String relativeName : NativeRuntime.FILES) {
                NativeCompiler.requireSameBytes(
                    Files.readAllBytes(NativeCompiler.repository()
                        .resolve(relativeName)),
                    Files.readAllBytes(published.resolve(relativeName)),
                    "the published file equals its source: " + relativeName);
            }
            System.out.println("native compiler: " + NativeCompiler.compiler().path()
                + " (major " + NativeCompiler.compiler().major() + ")");
            testRepertoireParity();
            testBoundaryAcceptance();
            testClosedNegatives();
            testRepertoireInstallNegatives();
        } finally {
            deleteRecursively(scratch);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== C11 Descriptor / Typed Boundary / Class Construction /"
            + " Error Materialization Tests Passed ===");
    }

    // =========================================================================
    // 1. The compiled repertoire equals the closed arm authority
    // =========================================================================

    /**
     * The runtime validates the emitted table against its compiled closed arm
     * repertoire, so that repertoire is a second place the arm identities,
     * parameter names, render scopes and declared fields live. This case reads
     * the published {@code deal/native/error.c} and {@code deal/native/error.h}
     * bytes and compares them against {@link FailureContractRegistry} arm for
     * arm, so a drift is reported here rather than as a runtime fault at the
     * install entry.
     */
    private static void testRepertoireParity() throws IOException {
        System.out.println("-- the compiled closed arm repertoire equals the"
            + " authority --");
        String header = Files.readString(
            NativeCompiler.repository().resolve("deal/native/error.h"),
            StandardCharsets.UTF_8);
        String source = Files.readString(
            NativeCompiler.repository().resolve("deal/native/error.c"),
            StandardCharsets.UTF_8);
        List<FailureArm> arms = List.copyOf(FailureContractRegistry.arms().values());
        checkEq(arms.size(), FailureArmId.values().length,
            "the closed arm set is exactly the declared arm ids");
        // The published arm identities.
        Map<String, Integer> ids = new LinkedHashMap<>();
        Matcher idMatcher = Pattern.compile(
            "#define\\s+DEAL_ARM_ID_([A-Z0-9_]+)\\s+(\\d+)[uU]").matcher(header);
        while (idMatcher.find()) {
            ids.put(idMatcher.group(1), Integer.parseInt(idMatcher.group(2)));
        }
        checkEq(arms.size(), ids.size(),
            "the header publishes one arm identity per closed arm");
        for (int index = 0; index < arms.size(); index++) {
            String name = arms.get(index).id().name();
            checkEq(index, ids.get(name),
                "the published arm identity " + name + " keeps its canonical ordinal");
        }
        Matcher countMatcher = Pattern.compile(
            "#define\\s+DEAL_ARM_REPERTOIRE_COUNT\\s+(\\d+)[uU]").matcher(header);
        check(countMatcher.find(), "the header publishes the repertoire count");
        if (countMatcher.find(0)) {
            checkEq(arms.size(), Integer.parseInt(countMatcher.group(1)),
                "the published repertoire count equals the closed arm set");
        }
        // The compiled repertoire.
        Map<String, List<String>> parameterArrays = parameterArraysOf(source);
        int blockStart = source.indexOf("static const deal_arm_repertoire_entry"
            + " deal_arm_repertoire[] = {");
        int blockEnd = source.indexOf("\n};", blockStart);
        check(blockStart >= 0 && blockEnd > blockStart,
            "the runtime compiles one closed arm repertoire");
        String block = source.substring(blockStart, blockEnd);
        List<String> entries = new ArrayList<>();
        Matcher entryMatcher = Pattern.compile("DEAL_ARM_ENTRY\\(([^;]*?)\\)",
            Pattern.DOTALL).matcher(block);
        while (entryMatcher.find()) {
            entries.add(entryMatcher.group(1).replaceAll("\\s+", " ").strip());
        }
        checkEq(arms.size(), entries.size(),
            "the compiled repertoire carries one entry per closed arm");
        for (int index = 0; index < Math.min(arms.size(), entries.size()); index++) {
            FailureArm arm = arms.get(index);
            String entry = entries.get(index);
            check(entry.startsWith(arm.id().name() + ","),
                "compiled repertoire entry " + index + " is " + arm.id());
            String[] fields = entry.split(",\\s*");
            if (fields.length != 6) {
                check(false, "compiled repertoire entry " + index
                    + " carries six fields: " + entry);
                continue;
            }
            List<String> names = parameterArrays.getOrDefault(fields[1], List.of());
            checkEq(arm.parameters(), names,
                "the compiled repertoire keeps the parameter names of " + arm.id());
            checkEq(arm.parameters().size(), Integer.parseInt(
                fields[2].replaceAll("[uU]", "")),
                "the compiled repertoire keeps the parameter count of " + arm.id());
            checkEq(arm.scope() == FailureArm.RenderScope.INNER_ONLY
                    ? "DEAL_ARM_SCOPE_INNER_ONLY" : "DEAL_ARM_SCOPE_TOP_LEVEL",
                fields[3],
                "the compiled repertoire keeps the render scope of " + arm.id());
            checkEq(arm.declaresExpected() ? "1U" : "0U", fields[4],
                "the compiled repertoire keeps the expected-field declaration of "
                    + arm.id());
            checkEq(arm.declaresActual() ? "1U" : "0U", fields[5],
                "the compiled repertoire keeps the actual-field declaration of "
                    + arm.id());
        }
    }

    /** The string parameter arrays declared in the published error source. */
    private static Map<String, List<String>> parameterArraysOf(String source) {
        Map<String, List<String>> arrays = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile(
            "static const char \\*const (\\w+)\\[\\] =\\s*\\{([^}]*)\\};",
            Pattern.DOTALL).matcher(source);
        while (matcher.find()) {
            List<String> names = new ArrayList<>();
            Matcher value = Pattern.compile("\"([^\"]*)\"").matcher(matcher.group(2));
            while (value.find()) {
                names.add(value.group(1));
            }
            arrays.put(matcher.group(1), List.copyOf(names));
        }
        return arrays;
    }

    // =========================================================================
    // 2. The boundary acceptance matrix
    // =========================================================================

    private static void testBoundaryAcceptance() throws IOException {
        System.out.println("-- atoms, checks, construction, every arm and the two"
            + " failure classes at -O0/-O2 --");
        new java.io.File("").getAbsoluteFile();
        for (int level : LEVELS) {
            Path product = scratch.resolve("boundary-driver-o" + level);
            NativeCompiler.Result link = NativeCompiler.link(published, level,
                sourcesOf("boundary_driver"), List.of("-DDEAL_BOUNDARY_CASE=0"),
                product);
            check(link.succeeded(), "the boundary driver builds at -O" + level
                + ": " + link.describe());
            checkEq("", link.stderr(), "the boundary driver builds without"
                + " diagnostics at -O" + level);
            if (!link.succeeded()) {
                continue;
            }
            NativeCompiler.Result normal = NativeCompiler.execute(product, scratch,
                Map.of());
            checkEq(0, normal.exitCode(), "the boundary driver exits zero at -O"
                + level + ": " + normal.describe());
            checkEq("", normal.stderr(), "the boundary driver keeps stderr empty at"
                + " -O" + level);
            check(normal.stdout().endsWith(ACCEPTANCE_LINE),
                "the boundary driver reports its exact success line at -O" + level
                    + ": " + normal.describe());
            check(!normal.stdout().contains("FAIL:"),
                "every driver self-check passes at -O" + level);
            verifyObservations(normal.stdout(), "at -O" + level);

            NativeCompiler.Result stressed = NativeCompiler.execute(product, scratch,
                Map.of("DEAL_GC_STRESS", "1"));
            checkEq(0, stressed.exitCode(), "the stressed boundary driver exits zero"
                + " at -O" + level + ": " + stressed.describe());
            checkEq(normal.stdout(), stressed.stdout(),
                "DEAL_GC_STRESS changes collection timing only: the boundary driver"
                    + " prints byte-identical observations at -O" + level);
            checkEq("", stressed.stderr(),
                "DEAL_GC_STRESS produces no diagnostic at -O" + level);

            // Physical memory safety: the same descriptor, boundary, class
            // construction and arm-materialization matrix under AddressSanitizer
            // and UndefinedBehaviorSanitizer must publish the same observations
            // and no finding at all.
            Path sanitizedProduct = scratch.resolve("boundary-driver-asan-o"
                + level);
            NativeCompiler.Result sanitizedLink = NativeCompiler.link(published,
                level, sourcesOf("boundary_driver"),
                concat(List.of("-DDEAL_BOUNDARY_CASE=0"),
                    NativeCompiler.SANITIZER_FLAGS),
                sanitizedProduct);
            check(sanitizedLink.succeeded(), "the boundary driver builds with "
                + "ASan/UBSan at -O" + level + ": "
                + sanitizedLink.describe());
            if (sanitizedLink.succeeded()) {
                NativeCompiler.SanitizedRun sanitized =
                    NativeCompiler.runSanitized(sanitizedProduct, scratch,
                        NativeCompiler.sanitizerEnvironment());
                checkEq(0, sanitized.result().exitCode(), "the boundary driver "
                    + "exits zero under ASan/UBSan at -O" + level + ": "
                    + sanitized.result().describe());
                checkEq(normal.stdout(), sanitized.result().stdout(),
                    "the boundary observations are unchanged under ASan/UBSan at "
                        + "-O" + level);
                checkEq("", sanitized.result().stderr(), "the boundary driver "
                    + "reports no sanitizer finding at -O" + level + ": "
                    + sanitized.result().describe());
            }
        }
    }

    /** One flag list followed by another. */
    private static List<String> concat(List<String> first, List<String> second) {
        List<String> combined = new ArrayList<>(first);
        combined.addAll(second);
        return combined;
    }

    /** Compares every printed observation against the shared authority. */
    private static void verifyObservations(String stdout, String where) {
        List<String> checks = new ArrayList<>();
        List<String> arms = new ArrayList<>();
        List<String> constructs = new ArrayList<>();
        for (String line : stdout.split("\n")) {
            if (line.startsWith("CHECK\t")) {
                checks.add(line);
            } else if (line.startsWith("ARM\t")) {
                arms.add(line);
            } else if (line.startsWith("CONSTRUCT\t")) {
                constructs.add(line);
            }
        }
        for (String line : checks) {
            verifyCheck(line, where);
        }
        for (String line : arms) {
            verifyArm(line, where);
        }
        verifyConstructs(constructs, where);
    }

    /** One printed check observation against the executor's own projection. */
    private static void verifyCheck(String line, String where) {
        String[] fields = line.split("\t", -1);
        if (fields.length != 12) {
            check(false, "a check observation carries twelve fields " + where
                + ": " + line);
            return;
        }
        String descriptorText = fields[1];
        String boundary = fields[2];
        int index = Integer.parseInt(fields[3]);
        String kind = fields[4];
        String payload = fields[5];
        String code = fields[6];
        String expected = fields[7];
        String actual = fields[8];
        String message = fields[11];
        RuntimeDescriptor descriptor =
            RuntimeDescriptor.parseCanonicalText(descriptorText);
        FailurePolicyId policy = switch (boundary) {
            case "type-descriptor" -> FailurePolicyId.TYPE_DESCRIPTOR;
            case "function-signature" -> FailurePolicyId.FUNCTION_SIGNATURE;
            case "host-parameter" -> FailurePolicyId.HOST_PARAMETER;
            case "host-sync-return" -> FailurePolicyId.HOST_SYNC_RETURN;
            case "async-completion" -> FailurePolicyId.ASYNC_COMPLETION;
            default -> throw new IllegalStateException("unknown boundary " + boundary);
        };
        BoundaryContext context = boundary.equals("host-parameter")
            ? BoundaryContext.parameter(index) : BoundaryContext.none();
        BoundaryOutcome outcome = BoundaryExecutor.check(policy, descriptor,
            view(kind, payload), context);
        String label = "the " + boundary + " check of " + descriptorText + "/"
            + kind + payload + where;
        if (outcome instanceof BoundaryOutcome.Pass) {
            checkEq("-", code, label + " passes");
            checkEq("-", expected, label + " carries no expected field");
            checkEq("-", actual, label + " carries no actual field");
            checkEq("-", message, label + " publishes no message");
            return;
        }
        BoundaryFailure failure = ((BoundaryOutcome.Fail) outcome).failure();
        checkEq(failure.code().name(), code, label + " publishes the arm's code");
        checkEq(nonNull(failure.expected()), expected,
            label + " publishes the declared expected field");
        checkEq(nonNull(failure.actual()), actual,
            label + " publishes the declared actual projection");
        checkEq(failure.message(), message,
            label + " instantiates the arm's own template");
        checkEq("41", fields[9], label + " keeps the supplied one-based line");
        checkEq("7", fields[10], label + " keeps the supplied one-based column");
    }

    /** One printed arm render against the registry's own render. */
    private static void verifyArm(String line, String where) {
        String[] fields = line.split("\t", -1);
        if (fields.length < 6) {
            check(false, "an arm observation carries its tuple " + where + ": "
                + line);
            return;
        }
        int index = 0;
        FailureArmId id;
        try {
            id = FailureArmId.values()[Integer.parseInt(fields[1])];
        } catch (RuntimeException invalid) {
            check(false, "an arm observation names a closed arm id " + where + ": "
                + line);
            return;
        }
        int parameterCount = Integer.parseInt(fields[2]);
        if (fields.length != 3 + parameterCount * 2 + 4) {
            check(false, "the arm observation of " + id + " carries its"
                + " parameter count " + where + ": " + line);
            return;
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        for (int slot = 0; slot < parameterCount; slot++) {
            parameters.put(fields[3 + slot * 2], fields[4 + slot * 2]);
        }
        String code = fields[3 + parameterCount * 2];
        String expected = fields[4 + parameterCount * 2];
        String actual = fields[5 + parameterCount * 2];
        String message = fields[6 + parameterCount * 2];
        BoundaryFailure derived;
        try {
            derived = FailureContractRegistry.render(id, parameters,
                absentAsNull(expected), absentAsNull(actual), null);
        } catch (RuntimeException defect) {
            check(false, "the " + id + " observation is a renderable arm"
                + " declaration " + where + ": " + defect.getMessage());
            return;
        }
        checkEq(derived.code().name(), code, "the " + id + " render keeps the"
            + " arm's code " + where);
        checkEq(nonNull(derived.expected()), expected, "the " + id + " render"
            + " keeps the declared expected field " + where);
        checkEq(nonNull(derived.actual()), actual, "the " + id + " render"
            + " keeps the declared actual projection " + where);
        checkEq(derived.message(), message, "the " + id + " render instantiates"
            + " the arm's own template " + where);
    }

    /** Every construction case against its derived failure or its tag. */
    private static void verifyConstructs(List<String> lines, String where) {
        Map<String, String[]> observed = new LinkedHashMap<>();
        for (String line : lines) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 8) {
                check(false, "a construction observation carries eight fields "
                    + where + ": " + line);
                continue;
            }
            observed.put(fields[1], fields);
        }
        Map<String, String[]> expected = new LinkedHashMap<>();
        // The passing constructions publish their tag and no failure.
        for (String name : List.of("valid", "optional-absent", "present-null",
                "present-undefined")) {
            expected.put(name, new String[] {name, "-", "-", "-", CLASS_USER, "-",
                "-"});
        }
        expected.put("optional-undefined", construct(
            FailureContractRegistry.render(FailureArmId.TYPED_BOUNDARY_KIND,
                Map.of("kind", "string"), "string", "undefined", null),
            ORIGIN_FILE + ":13:5", null));
        expected.put("extra-field", construct(
            FailureContractRegistry.render(FailureArmId.CLASS_EXTRA_FIELD,
                Map.of("field", "extra", "classId", CLASS_USER), null, null, null),
            ORIGIN_FILE + ":11:3", null));
        expected.put("extra-first", construct(
            FailureContractRegistry.render(FailureArmId.CLASS_EXTRA_FIELD,
                Map.of("field", "extra", "classId", CLASS_USER), null, null, null),
            ORIGIN_FILE + ":11:3", null));
        expected.put("declaration-order", construct(
            FailureContractRegistry.render(FailureArmId.TYPED_BOUNDARY_KIND,
                Map.of("kind", "string"), "string", "number", null),
            ORIGIN_FILE + ":12:5", null));
        expected.put("signature-field", construct(
            FailureContractRegistry.render(FailureArmId.FUNCTION_SIGNATURE_MISMATCH,
                Map.of("expected", "(number)->string",
                    "actual", "(number)->number"),
                "(number)->string", "(number)->number", null),
            ORIGIN_FILE + ":12:5", null));
        checkEq(new java.util.TreeSet<>(expected.keySet()),
            new java.util.TreeSet<>(observed.keySet()),
            "every construction case is observed " + where);
        for (Map.Entry<String, String[]> entry : expected.entrySet()) {
            String[] seen = observed.get(entry.getKey());
            if (seen == null) {
                continue;
            }
            String[] want = entry.getValue();
            for (int index = 1; index < want.length; index++) {
                checkEq(want[index], seen[index + 1], "the construction case "
                    + entry.getKey() + " keeps field " + index + " " + where);
            }
        }
    }

    /** One construction observation from a derived render. */
    private static String[] construct(BoundaryFailure failure, String origin,
                                      String tag) {
        return new String[] {null, failure.code().name(),
            nonNull(failure.expected()), nonNull(failure.actual()),
            tag == null ? "-" : tag, origin, failure.message()};
    }

    /** The value view one printed presentation names. */
    private static BoundaryValueView view(String kind, String payload) {
        return switch (kind) {
            case "null" -> new BoundaryValueView(ActualKind.NULL, null, null, null,
                null, false);
            case "undefined" -> new BoundaryValueView(ActualKind.UNDEFINED, null,
                null, null, null, false);
            case "missing" -> new BoundaryValueView(ActualKind.MISSING, null, null,
                null, null, false);
            case "boolean" -> new BoundaryValueView(ActualKind.BOOLEAN, null, null,
                null, null, false);
            case "number" -> new BoundaryValueView(ActualKind.NUMBER, null, 1.0,
                null, null, false);
            case "string" -> new BoundaryValueView(ActualKind.STRING, null, null,
                null, null, false);
            case "table" -> new BoundaryValueView(ActualKind.TABLE, null, null,
                null, null, false);
            case "array" -> new BoundaryValueView(ActualKind.ARRAY, null, null, null,
                List.of(), false, null,
                payload.equals("-") ? null
                    : RuntimeDescriptor.parseCanonicalText(payload));
            case "class" -> new BoundaryValueView(ActualKind.CLASS, payload, null,
                null, null, false);
            case "error" -> new BoundaryValueView(ActualKind.CLASS, "Error", null,
                null, null, false);
            case "function" -> new BoundaryValueView(ActualKind.FUNCTION, null, null,
                (RuntimeDescriptor.Func) RuntimeDescriptor.parseCanonicalText(payload),
                null, false);
            case "nothing" -> new BoundaryValueView(ActualKind.NOTHING, null, null,
                null, null, false);
            case "async-operation" -> new BoundaryValueView(ActualKind.ASYNC_OPERATION,
                null, null, null, null, false);
            case "invalid-unicode" -> new BoundaryValueView(ActualKind.INVALID_UNICODE,
                null, null, null, null, false,
                payload.equals("surrogate") ? UnicodeScalars.Defect.SURROGATE
                    : UnicodeScalars.Defect.MALFORMED);
            default -> throw new IllegalStateException(
                "unknown presentation " + kind);
        };
    }

    // =========================================================================
    // 3. The fail-closed producer defects of the driver surface
    // =========================================================================

    private static void testClosedNegatives() throws IOException {
        System.out.println("-- the deterministic descriptor, arm, construction and"
            + " admission faults --");
        record Fault(String define, String reason) { }
        List<Fault> faults = List.of(
            new Fault("-DDEAL_BOUNDARY_CASE=1",
                "descriptor: void outside a function result"),
            new Fault("-DDEAL_BOUNDARY_CASE=2",
                "descriptor: void outside a function result"),
            new Fault("-DDEAL_BOUNDARY_CASE=3",
                "descriptor: invalid nullish descriptor"),
            new Fault("-DDEAL_BOUNDARY_CASE=4",
                "descriptor: invalid nullish descriptor"),
            new Fault("-DDEAL_BOUNDARY_CASE=5",
                "descriptor: class atom without modulePath/ClassName"),
            new Fault("-DDEAL_BOUNDARY_CASE=6",
                "descriptor: not a canonical descriptor text"),
            new Fault("-DDEAL_BOUNDARY_CASE=7", "arm render: unknown arm id"),
            new Fault("-DDEAL_BOUNDARY_CASE=8",
                "arm render: inner-only arm rendered at a site"),
            new Fault("-DDEAL_BOUNDARY_CASE=9",
                "arm render: declared field shape mismatch"),
            new Fault("-DDEAL_BOUNDARY_CASE=10",
                "arm render: missing declared parameter"),
            new Fault("-DDEAL_BOUNDARY_CASE=11",
                "arm render: undeclared parameter"),
            new Fault("-DDEAL_BOUNDARY_CASE=12",
                "arm render: no installed arm table"),
            new Fault("-DDEAL_BOUNDARY_CASE=13",
                "class construction: a field is supplied twice"),
            new Fault("-DDEAL_BOUNDARY_CASE=14",
                "class construction: a required field is absent"),
            new Fault("-DDEAL_BOUNDARY_CASE=15",
                "check: a function descriptor at a typed boundary"),
            new Fault("-DDEAL_BOUNDARY_CASE=16",
                "check: a host parameter boundary needs a one-based index"),
            new Fault("-DDEAL_BOUNDARY_CASE=17",
                "array position: not a bounds arm"),
            new Fault("-DDEAL_BOUNDARY_CASE=18",
                "failure admission: relayed Error beside host text"));
        for (Fault fault : faults) {
            for (int level : LEVELS) {
                Path product = scratch.resolve("boundary-fault"
                    + fault.define().replace("=", "").replace("-D", "")
                    + "-o" + level);
                NativeCompiler.Result link = NativeCompiler.link(published, level,
                    sourcesOf("boundary_driver"), List.of(fault.define()), product);
                check(link.succeeded(), "the " + fault.reason() + " case builds at"
                    + " -O" + level + ": " + link.describe());
                if (!link.succeeded()) {
                    continue;
                }
                NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
                    Map.of());
                checkEq(2, run.exitCode(), "the " + fault.reason()
                    + " case exits 2 at -O" + level + ": " + run.describe());
                checkEq("", run.stdout(), "the " + fault.reason()
                    + " case writes no stdout at -O" + level);
                checkEq(FAULT_PREFIX + fault.reason() + "\n", run.stderr(),
                    "the " + fault.reason() + " case writes exactly one"
                        + " deterministic stderr line at -O" + level);
            }
        }
    }

    // =========================================================================
    // 4. The install entry's repertoire validation
    // =========================================================================

    private static void testRepertoireInstallNegatives() throws IOException {
        System.out.println("-- the install entry rejects an unknown arm, a missing"
            + " arm and a shape mismatch before user code --");
        record Fault(String stubCase, String reason) { }
        List<Fault> faults = List.of(
            new Fault("8", "arm table: unknown arm id"),
            new Fault("9", "arm table: missing arm"),
            new Fault("10", "arm table: parameter shape mismatch"),
            new Fault("11", "arm table: declared field shape mismatch"));
        for (Fault fault : faults) {
            for (int level : LEVELS) {
                Path product = scratch.resolve("repertoire-" + fault.stubCase()
                    + "-o" + level);
                NativeCompiler.Result link = NativeCompiler.link(published, level,
                    NativeCompiler.productSources(published,
                        List.of(fixture("module_stub.c"),
                            fixture("registry_empty.c"))),
                    List.of("-DDEAL_STUB_CASE=" + fault.stubCase()), product);
                check(link.succeeded(), "the " + fault.reason() + " product builds"
                    + " at -O" + level + ": " + link.describe());
                if (!link.succeeded()) {
                    continue;
                }
                NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
                    Map.of());
                checkEq(2, run.exitCode(), "the " + fault.reason()
                    + " case exits 2 at -O" + level + ": " + run.describe());
                checkEq("", run.stdout(), "the " + fault.reason()
                    + " case writes no stdout at -O" + level);
                checkEq(FAULT_PREFIX + fault.reason() + "\n", run.stderr(),
                    "the " + fault.reason() + " case writes exactly one"
                        + " deterministic stderr line at -O" + level);
            }
        }
        // The accepted table still installs and runs, so the negatives above are
        // the only difference from the accepted shape.
        for (int level : LEVELS) {
            Path product = scratch.resolve("repertoire-accepted-o" + level);
            NativeCompiler.Result link = NativeCompiler.link(published, level,
                NativeCompiler.productSources(published,
                    List.of(fixture("module_stub.c"), fixture("registry_empty.c"))),
                List.of("-DDEAL_STUB_CASE=0"), product);
            check(link.succeeded(), "the accepted table installs at -O" + level
                + ": " + link.describe());
            if (!link.succeeded()) {
                continue;
            }
            NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
                Map.of());
            checkEq(0, run.exitCode(), "the accepted table product exits zero at"
                + " -O" + level + ": " + run.describe());
            checkEq("", run.stdout(),
                "the accepted table product writes no terminal output at -O" + level);
            checkEq("", run.stderr(),
                "the accepted table product writes no diagnostic at -O" + level);
        }
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    private static List<Path> sourcesOf(String driver) {
        return NativeCompiler.driverSources(published,
            List.of(fixture("module_stub.c"), fixture("registry_empty.c")),
            fixture(driver + ".c"));
    }

    private static Path fixture(String name) {
        return NativeCompiler.fixtures().resolve(name);
    }

    private static String absentAsNull(String text) {
        return "-".equals(text) ? null : text;
    }

    private static String nonNull(String text) {
        return text == null ? "-" : text;
    }

    private static void check(boolean condition, String what) {
        if (condition) {
            passed += 1;
        } else {
            failed += 1;
            System.out.println("FAIL: " + what);
        }
    }

    private static void checkEq(Object expected, Object actual, String what) {
        if (expected == null ? actual == null : expected.equals(actual)) {
            passed += 1;
        } else {
            failed += 1;
            System.out.println("FAIL: " + what + " (expected " + expected
                + ", got " + actual + ")");
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
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
