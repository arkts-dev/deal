package deal.test;

import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.semantic.DescriptorService;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.LoweringFailureDetail;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.SemanticIrTextDecodeException;
import deal.types.Type;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Verifies the ISSUE-0233 D1/D2 surface: {@link DescriptorService} as the
 * single, pure {@code Type}→{@link RuntimeDescriptor} producer for common
 * units — the exact D1 mapping table over the ten supported variants, the
 * schema-owned canonical spec text with the pinned
 * {@code parseCanonicalText(canonicalSpecText())} round-trip, structural
 * equality (never text-based), byte-identical determinism, the bytes/Error
 * fail-closed {@code DescriptorService.Defect} → E6005 conversion seam
 * through {@code FailureContractRegistry}
 * ({@code capability DESCRIPTORS}, {@code validatorRule
 * DESCRIPTOR_UNREPRESENTABLE}, {@code semanticProfile
 * DEAL_V1_2_INT32}, {@code irVersion deal.semantic-ir/1}), and the
 * producer-singularity source scan.
 *
 * <p>Tests:
 * <ol>
 *   <li>Mapping pins: {@code describe} for every {@code Type} variant —
 *       primitives, {@code Class}, {@code Array}, {@code Nullable}, sync
 *       and async {@code Func} — including the builtin
 *       {@code Type.Class("Error", "")} → {@code Class(ClassId.ERROR)}
 *       spelling.</li>
 *   <li>Canonical-text round-trip: for every pinned spelling
 *       ({@code null}, {@code boolean}, {@code int}, {@code number},
 *       {@code string}, {@code table}, {@code @src/app/User},
 *       {@code @/Error}, {@code [int]}, {@code [[int]]},
 *       {@code [?@src/app/User]}, {@code ?string},
 *       {@code ?[@src/app/User]}, {@code (int,string)->boolean},
 *       {@code ()->null}, {@code async(int)->string}, plus depth-≥2
 *       function/nested forms) {@code describe(t).canonicalSpecText()}
 *       equals the pinned text and
 *       {@code parseCanonicalText(text)} equals {@code describe(t)}.</li>
 *   <li>Structural equality across parse/render and across separately
 *       constructed variants; async marker and class identity
 *       differences are inequality, never text comparison.</li>
 *   <li>Decoder amendment pins: {@code @/Error} decodes as
 *       {@code Class(ClassId.ERROR)} and round-trips through
 *       {@code ClassId.ERROR.text()}; {@code @src/app/User} decodes
 *       exactly as before; {@code @User}, {@code @src/}, and {@code @/}
 *       stay rejected with {@code SemanticIrTextDecodeException}.</li>
 *   <li>Fail-closed path: {@code describe(Type.Bytes.INSTANCE)} and
 *       {@code describe(Type.Error.INSTANCE)} raise
 *       {@code DescriptorService.Defect} (never a descriptor); the
 *       conversion seam returns the E6005
 *       {@code BACKEND_LOWERING} diagnostic with the registry-instantiated
 *       message carrying every pinned detail field.</li>
 *   <li>Determinism: repeated {@code describe} calls produce
 *       byte-identical canonical text.</li>
 *   <li>Producer-singularity scan: every production file under
 *       {@code deal/**} except {@code deal/semantic/DescriptorService.java}
 *       that both references {@code deal.types.Type} and constructs a
 *       {@code RuntimeDescriptor} fails the gate (fail-closed, the same
 *       scan-gate pattern as the run_tests.sh migration gates).</li>
 * </ol>
 */
public class DescriptorServiceTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    private static void expectDefect(Runnable runnable, String what) {
        try {
            runnable.run();
            fail("expected DescriptorService.Defect for " + what + ", but no exception was raised");
        } catch (DescriptorService.Defect expected) {
            passed++;
        } catch (Throwable other) {
            fail("expected DescriptorService.Defect for " + what + ", got "
                + other.getClass().getSimpleName() + ": " + other.getMessage());
        }
    }

    private static DescriptorService.Defect defect(Runnable runnable) {
        try {
            runnable.run();
            throw new IllegalStateException("no Defect raised");
        } catch (DescriptorService.Defect defect) {
            return defect;
        }
    }

    private static void expectDecodeFailure(String text, String what) {
        try {
            RuntimeDescriptor.parseCanonicalText(text);
            fail("expected SemanticIrTextDecodeException for " + what + " (text \"" + text + "\")");
        } catch (SemanticIrTextDecodeException expected) {
            passed++;
        }
    }

    // =========================================================================
    // 1. Exact D1 mapping pins for every Type variant
    // =========================================================================

    static void testDescribeMappingPins() {
        System.out.println("-- DescriptorService.describe: exact D1 mapping pins --");

        check(RuntimeDescriptor.Null.INSTANCE == DescriptorService.describe(Type.Null.INSTANCE),
            "Null -> Null.INSTANCE");
        check(RuntimeDescriptor.Boolean.INSTANCE == DescriptorService.describe(Type.Boolean.INSTANCE),
            "Boolean -> Boolean.INSTANCE");
        check(RuntimeDescriptor.Int.INSTANCE == DescriptorService.describe(Type.Int.INSTANCE),
            "Int -> Int.INSTANCE");
        check(RuntimeDescriptor.Number.INSTANCE == DescriptorService.describe(Type.Number.INSTANCE),
            "Number -> Number.INSTANCE");
        check(RuntimeDescriptor.String.INSTANCE == DescriptorService.describe(Type.String.INSTANCE),
            "String -> String.INSTANCE");
        check(RuntimeDescriptor.Table.INSTANCE == DescriptorService.describe(Type.Table.INSTANCE),
            "Table -> Table.INSTANCE");

        RuntimeDescriptor user = DescriptorService.describe(new Type.Class("User", "src/app"));
        check(new RuntimeDescriptor.Class(new ClassId("src/app", "User")).equals(user),
            "Class(name, modulePath) -> Class(new ClassId(modulePath, name))");

        RuntimeDescriptor builtinError = DescriptorService.describe(new Type.Class("Error", ""));
        check(new RuntimeDescriptor.Class(ClassId.ERROR).equals(builtinError),
            "the builtin Error class type maps to Class(ClassId.ERROR) (@/Error)");

        RuntimeDescriptor array = DescriptorService.describe(new Type.Array(Type.Int.INSTANCE));
        check(new RuntimeDescriptor.Array(RuntimeDescriptor.Int.INSTANCE).equals(array),
            "Array(T) -> Array(describe(T))");

        RuntimeDescriptor nestedArray = DescriptorService.describe(
            new Type.Array(new Type.Array(Type.Int.INSTANCE)));
        check(new RuntimeDescriptor.Array(
                new RuntimeDescriptor.Array(RuntimeDescriptor.Int.INSTANCE)).equals(nestedArray),
            "Array depth 2 -> [[int]]");

        RuntimeDescriptor nullable = DescriptorService.describe(
            new Type.Nullable(Type.String.INSTANCE));
        check(new RuntimeDescriptor.Nullable(RuntimeDescriptor.String.INSTANCE).equals(nullable),
            "Nullable(T) -> Nullable(describe(T))");

        RuntimeDescriptor nullableArrayOfClass = DescriptorService.describe(
            new Type.Nullable(new Type.Array(new Type.Class("User", "src/app"))));
        check(new RuntimeDescriptor.Nullable(new RuntimeDescriptor.Array(
                new RuntimeDescriptor.Class(new ClassId("src/app", "User"))))
                .equals(nullableArrayOfClass),
            "Nullable(Array(Class)) at depth 2");

        RuntimeDescriptor sync = DescriptorService.describe(new Type.Func(
            List.of(Type.Int.INSTANCE, Type.String.INSTANCE), Type.Boolean.INSTANCE));
        check(new RuntimeDescriptor.Func(
                List.of(RuntimeDescriptor.Int.INSTANCE, RuntimeDescriptor.String.INSTANCE),
                RuntimeDescriptor.Boolean.INSTANCE, false).equals(sync),
            "Func(params, ret, false) -> Func(param descriptors in order, describe(ret), false)");

        RuntimeDescriptor nullary = DescriptorService.describe(
            new Type.Func(List.of(), Type.Null.INSTANCE));
        check(new RuntimeDescriptor.Func(List.of(), RuntimeDescriptor.Null.INSTANCE).equals(nullary),
            "nullary sync function");

        RuntimeDescriptor async = DescriptorService.describe(new Type.Func(
            List.of(Type.Int.INSTANCE), Type.String.INSTANCE, true));
        check(new RuntimeDescriptor.Func(List.of(RuntimeDescriptor.Int.INSTANCE),
                RuntimeDescriptor.String.INSTANCE, true).equals(async),
            "Func(params, ret, true) -> async Func descriptor");

        RuntimeDescriptor asyncNested = DescriptorService.describe(new Type.Func(
            List.of(new Type.Array(Type.Int.INSTANCE)),
            new Type.Nullable(Type.String.INSTANCE), true));
        check("async([int])->?string".equals(asyncNested.canonicalSpecText()),
            "async function with array parameter and nullable return at depth 2; got "
                + asyncNested.canonicalSpecText());

        RuntimeDescriptor arrayOfFunc = DescriptorService.describe(new Type.Array(
            new Type.Func(List.of(Type.Int.INSTANCE), Type.Boolean.INSTANCE)));
        check("[(int)->boolean]".equals(arrayOfFunc.canonicalSpecText()),
            "array of sync function at depth 2; got " + arrayOfFunc.canonicalSpecText());

        RuntimeDescriptor triple = DescriptorService.describe(new Type.Array(
            new Type.Array(new Type.Array(Type.Int.INSTANCE))));
        check("[[[int]]]".equals(triple.canonicalSpecText()),
            "array depth 3; got " + triple.canonicalSpecText());

        expectDefect(() -> DescriptorService.describe(Type.Bytes.INSTANCE),
            "Type.Bytes (no descriptor member exists in deal.semantic-ir/1)");
        expectDefect(() -> DescriptorService.describe(Type.Error.INSTANCE),
            "Type.Error (the internal checker sentinel is excluded from common units)");
        expectDefect(() -> DescriptorService.describe(new Type.Array(Type.Bytes.INSTANCE)),
            "Array(Bytes) — no bytes descriptor at any depth");
        expectDefect(() -> DescriptorService.describe(
                new Type.Func(List.of(Type.Bytes.INSTANCE), Type.Null.INSTANCE)),
            "Func with a bytes parameter — no bytes descriptor at any depth");
        expectDefect(() -> DescriptorService.describe(new Type.Array(Type.Error.INSTANCE)),
            "Array(Error) — no sentinel descriptor at any depth");
    }

    // =========================================================================
    // 2. Canonical-text round-trip (schema-owned text, D2)
    // =========================================================================

    static void testCanonicalTextRoundTrip() {
        System.out.println("-- canonical spec text round-trip (schema-owned text) --");

        record Case(java.lang.String pinnedText, Type type) {}
        List<Case> cases = List.of(
            new Case("null", Type.Null.INSTANCE),
            new Case("boolean", Type.Boolean.INSTANCE),
            new Case("int", Type.Int.INSTANCE),
            new Case("number", Type.Number.INSTANCE),
            new Case("string", Type.String.INSTANCE),
            new Case("table", Type.Table.INSTANCE),
            new Case("@src/app/User", new Type.Class("User", "src/app")),
            new Case("@/Error", new Type.Class("Error", "")),
            new Case("[int]", new Type.Array(Type.Int.INSTANCE)),
            new Case("[[int]]", new Type.Array(new Type.Array(Type.Int.INSTANCE))),
            new Case("[?@src/app/User]", new Type.Array(
                new Type.Nullable(new Type.Class("User", "src/app")))),
            new Case("?string", new Type.Nullable(Type.String.INSTANCE)),
            new Case("?[@src/app/User]", new Type.Nullable(
                new Type.Array(new Type.Class("User", "src/app")))),
            new Case("(int,string)->boolean", new Type.Func(
                List.of(Type.Int.INSTANCE, Type.String.INSTANCE), Type.Boolean.INSTANCE)),
            new Case("()->null", new Type.Func(List.of(), Type.Null.INSTANCE)),
            new Case("async(int)->string", new Type.Func(
                List.of(Type.Int.INSTANCE), Type.String.INSTANCE, true)),
            new Case("async([int])->?string", new Type.Func(
                List.of(new Type.Array(Type.Int.INSTANCE)),
                new Type.Nullable(Type.String.INSTANCE), true)),
            new Case("[(int)->boolean]", new Type.Array(
                new Type.Func(List.of(Type.Int.INSTANCE), Type.Boolean.INSTANCE))),
            new Case("?[(int)->boolean]", new Type.Nullable(
                new Type.Array(new Type.Func(List.of(Type.Int.INSTANCE), Type.Boolean.INSTANCE)))),
            new Case("[[[int]]]", new Type.Array(
                new Type.Array(new Type.Array(Type.Int.INSTANCE)))));

        for (Case c : cases) {
            RuntimeDescriptor descriptor = DescriptorService.describe(c.type());
            check(c.pinnedText().equals(descriptor.canonicalSpecText()),
                "canonicalSpecText of " + c.pinnedText() + " is the pinned schema text; got "
                    + descriptor.canonicalSpecText());
            RuntimeDescriptor decoded = RuntimeDescriptor.parseCanonicalText(
                descriptor.canonicalSpecText());
            check(descriptor.equals(decoded),
                "parseCanonicalText(canonicalSpecText()) is structurally equal for "
                    + c.pinnedText());
        }
    }

    // =========================================================================
    // 3. Structural equality (authoritative; never text-based)
    // =========================================================================

    static void testStructuralEquality() {
        System.out.println("-- structural equality across construction paths --");

        // Separately constructed variants with equal structure are equal.
        RuntimeDescriptor a = DescriptorService.describe(
            new Type.Array(new Type.Nullable(new Type.Class("User", "src/app"))));
        RuntimeDescriptor b = DescriptorService.describe(
            new Type.Array(new Type.Nullable(new Type.Class("User", "src/app"))));
        check(a.equals(b), "separately constructed equal types produce equal descriptors");

        // Parse/render path equals the direct-construction path.
        check(RuntimeDescriptor.parseCanonicalText(a.canonicalSpecText()).equals(a)
                && a.equals(RuntimeDescriptor.parseCanonicalText(a.canonicalSpecText())),
            "parse/render equality is symmetric");

        // Class equality is ClassId(modulePath, name) equality.
        check(DescriptorService.describe(new Type.Class("User", "src/app"))
                .equals(DescriptorService.describe(new Type.Class("User", "src/app"))),
            "class descriptors with equal module path and name are equal");
        check(!DescriptorService.describe(new Type.Class("User", "src/app"))
                .equals(DescriptorService.describe(new Type.Class("Other", "src/app"))),
            "different class names are different descriptors");
        check(!DescriptorService.describe(new Type.Class("User", "src/app"))
                .equals(DescriptorService.describe(new Type.Class("User", "other/app"))),
            "different module paths are different descriptors");
        check(!DescriptorService.describe(new Type.Class("Error", ""))
                .equals(DescriptorService.describe(new Type.Class("User", ""))),
            "builtin Error is not the empty-module User class");

        // Func equality: async marker + parameter list + return descriptor.
        List<Type> params1 = new ArrayList<>(List.of(Type.Int.INSTANCE, Type.String.INSTANCE));
        List<Type> params2 = List.of(Type.Int.INSTANCE, Type.String.INSTANCE);
        RuntimeDescriptor f1 = DescriptorService.describe(
            new Type.Func(params1, Type.Boolean.INSTANCE));
        RuntimeDescriptor f2 = DescriptorService.describe(
            new Type.Func(params2, Type.Boolean.INSTANCE));
        check(f1.equals(f2), "function descriptors with equal structure are equal");
        check(!f1.equals(DescriptorService.describe(
                new Type.Func(List.of(Type.Int.INSTANCE, Type.String.INSTANCE),
                    Type.Boolean.INSTANCE, true))),
            "the async marker distinguishes function descriptors");
        check(!f1.equals(DescriptorService.describe(
                new Type.Func(List.of(Type.Int.INSTANCE), Type.Boolean.INSTANCE))),
            "parameter lists distinguish function descriptors");
        check(!f1.equals(DescriptorService.describe(
                new Type.Func(List.of(Type.Int.INSTANCE, Type.String.INSTANCE),
                    Type.Null.INSTANCE))),
            "return descriptors distinguish function descriptors");

        // Descriptor equality is structural, never text-based: two
        // different structures ([?string] vs ?[string]) must not compare
        // equal merely because their texts share the same tokens.
        RuntimeDescriptor arrNullable = DescriptorService.describe(
            new Type.Array(new Type.Nullable(Type.String.INSTANCE)));
        RuntimeDescriptor nullableArr = DescriptorService.describe(
            new Type.Nullable(new Type.Array(Type.String.INSTANCE)));
        check(!arrNullable.equals(nullableArr),
            "[?string] and ?[string] are structurally different");

        // The sealed runtime hierarchy enforces the same invariants as the
        // type hierarchy: a checked Type.Nullable can never hold a null or
        // nested nullable inner, so describe never hits a constructor
        // rejection for supported variants.
        check(RuntimeDescriptor.parseCanonicalText(
                DescriptorService.describe(new Type.Nullable(Type.String.INSTANCE))
                    .canonicalSpecText())
                .equals(new RuntimeDescriptor.Nullable(RuntimeDescriptor.String.INSTANCE)),
            "nullable invariant round-trip holds");
    }

    // =========================================================================
    // 4. Decoder amendment pins (minimal guard change)
    // =========================================================================

    static void testDecoderAmendmentPins() {
        System.out.println("-- decoder amendment: builtin @/Error spelling --");

        RuntimeDescriptor builtin = RuntimeDescriptor.parseCanonicalText("@/Error");
        check(new RuntimeDescriptor.Class(ClassId.ERROR).equals(builtin),
            "parseCanonicalText(\"@/Error\") equals Class(ClassId.ERROR)");
        check(new ClassId("", "Error").equals(((RuntimeDescriptor.Class) builtin).classId()),
            "@/Error decodes with the empty module path and name Error");

        RuntimeDescriptor roundTripped = RuntimeDescriptor.parseCanonicalText(
            ClassId.ERROR.text());
        check(new RuntimeDescriptor.Class(ClassId.ERROR).equals(roundTripped),
            "ClassId.ERROR.text() round-trips through parseCanonicalText");

        RuntimeDescriptor user = RuntimeDescriptor.parseCanonicalText("@src/app/User");
        check(new RuntimeDescriptor.Class(new ClassId("src/app", "User")).equals(user),
            "parseCanonicalText(\"@src/app/User\") is unchanged");
        check("@src/app/User".equals(user.canonicalSpecText()),
            "@src/app/User re-renders byte-identically");

        // No-slash and empty-name segments stay rejected.
        expectDecodeFailure("@User", "a class segment without a module path");
        expectDecodeFailure("@src/", "a class segment without a class name");
        expectDecodeFailure("@/", "an empty class segment");
    }

    // =========================================================================
    // 5. Fail-closed path: Defect -> E6005 through the registry seam
    // =========================================================================

    static void testFailClosedE6005() {
        System.out.println("-- fail-closed bytes/Error path and the E6005 conversion seam --");

        DescriptorService.Defect bytesDefect =
            defect(() -> DescriptorService.describe(Type.Bytes.INSTANCE));
        DescriptorService.Defect errorDefect =
            defect(() -> DescriptorService.describe(Type.Error.INSTANCE));
        check(bytesDefect.getMessage().contains("Type.Bytes"),
            "the bytes defect names the bytes primitive");
        check(errorDefect.getMessage().contains("Type.Error"),
            "the Error defect names the checker sentinel");

        ModuleId module = new ModuleId("test.module");
        for (DescriptorService.Defect d : List.of(bytesDefect, errorDefect)) {
            CompilerDiagnostic diag = DescriptorService.e6005(module, d);
            check("E6005".equals(diag.code()), "the seam diagnostic code is E6005");
            check(diag.diagnosticCode() != null
                    && diag.diagnosticCode().phase() == DiagnosticCode.Phase.BACKEND_LOWERING,
                "the seam diagnostic phase is BACKEND_LOWERING");
            check("error".equals(diag.severity()), "the seam diagnostic is error severity");
            check(diag.message().contains("module 'test.module'"),
                "the message carries the module");
            check(diag.message().contains("capability DESCRIPTORS"),
                "the message carries capability DESCRIPTORS");
            check(diag.message().contains("validatorRule DESCRIPTOR_UNREPRESENTABLE"),
                "the message carries validatorRule DESCRIPTOR_UNREPRESENTABLE");
            check(diag.message().contains("semanticProfile DEAL_V1_2_INT32"),
                "the message carries semanticProfile DEAL_V1_2_INT32");
            check(diag.message().contains("irVersion deal.semantic-ir/1"),
                "the message carries irVersion deal.semantic-ir/1");
            check(diag.message().contains(
                    "origin DescriptorService DESCRIPTOR_UNREPRESENTABLE ("),
                "the message carries the DescriptorService origin");
            LoweringFailureDetail expectedDetail = new LoweringFailureDetail(
                "test.module", SemanticCapability.DESCRIPTORS,
                "DESCRIPTOR_UNREPRESENTABLE", SemanticProfile.DEAL_V1_2_INT32,
                "deal.semantic-ir/1",
                "DescriptorService DESCRIPTOR_UNREPRESENTABLE (" + d.getMessage() + ")");
            check(FailureContractRegistry.instantiateMessage(expectedDetail)
                    .equals(diag.message()),
                "the message is exactly the registry-instantiated E6005 template");
        }

        // The fail-closed path never produces a descriptor: describe
        // throws before any RuntimeDescriptor is constructed, and the seam
        // converts only the defect (asserted above by the Defect-typed
        // catches and the registry message).
    }

    // =========================================================================
    // 6. Determinism: byte-identical repeats
    // =========================================================================

    static void testDeterminism() {
        System.out.println("-- determinism: byte-identical repeats --");

        List<Type> types = List.of(
            Type.Null.INSTANCE,
            Type.Int.INSTANCE,
            new Type.Class("User", "src/app"),
            new Type.Class("Error", ""),
            new Type.Array(new Type.Nullable(new Type.Class("User", "src/app"))),
            new Type.Func(List.of(Type.Int.INSTANCE, Type.String.INSTANCE),
                Type.Boolean.INSTANCE, true));
        for (Type type : types) {
            java.lang.String first = DescriptorService.describe(type).canonicalSpecText();
            for (int i = 0; i < 3; i++) {
                java.lang.String repeat = DescriptorService.describe(type).canonicalSpecText();
                check(first.equals(repeat),
                    "repeated describe of " + first + " is byte-identical");
            }
        }
    }

    // =========================================================================
    // 7. Producer-singularity scan (the "only" clause)
    // =========================================================================

    private static final Pattern SINGLETON_CONSTRUCTION = Pattern.compile(
        "RuntimeDescriptor\\.(Null|Boolean|Int|Number|String|Table)\\.INSTANCE");

    static void testProducerSingularityScan() {
        System.out.println("-- producer-singularity scan over deal/**/*.java --");

        Path dealRoot = Path.of("deal");
        if (!Files.isDirectory(dealRoot)) {
            fail("the deal/ source directory is not present under the working directory; "
                + "the producer-singularity scan fails closed");
            return;
        }
        try (Stream<Path> walk = Files.walk(dealRoot)) {
            List<Path> sources = walk
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .filter(p -> !p.toString().replace('\\', '/')
                    .equals("deal/semantic/DescriptorService.java"))
                .sorted()
                .toList();
            int violations = 0;
            for (Path source : sources) {
                java.lang.String content = Files.readString(source);
                boolean typeBearing = content.contains("deal.types.Type")
                    || content.contains("import deal.types.*");
                boolean descriptorConstruction = content.contains("new RuntimeDescriptor.")
                    || SINGLETON_CONSTRUCTION.matcher(content).find();
                if (typeBearing && descriptorConstruction) {
                    violations++;
                    fail("production file " + source + " references deal.types.Type and "
                        + "constructs a RuntimeDescriptor: only "
                        + "deal/semantic/DescriptorService.java may map Type -> "
                        + "RuntimeDescriptor");
                }
            }
            check(violations == 0,
                "no production file outside DescriptorService maps Type -> RuntimeDescriptor");
            check(sources.size() >= 100,
                "the scan corpus is populated (fail-closed: " + sources.size()
                    + " production sources scanned)");
        } catch (java.io.IOException e) {
            fail("producer-singularity scan failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // Runner
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Descriptor Service Test (ISSUE-0233 D1/D2) ===\n");

        testDescribeMappingPins();
        testCanonicalTextRoundTrip();
        testStructuralEquality();
        testDecoderAmendmentPins();
        testFailClosedE6005();
        testDeterminism();
        testProducerSingularityScan();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
