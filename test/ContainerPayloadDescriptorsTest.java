package deal.test;

import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.semantic.ContainerPayloadDescriptors;
import deal.semantic.DescriptorService;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.LoweringFailureDetail;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticProfile;
import deal.types.Type;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Verifies the ISSUE-0232 D2 surface: {@link ContainerPayloadDescriptors}
 * as the declared pre-E4 descriptor bridge — exactly the two derivation
 * positions ({@code elementDescriptorOf} for array element types,
 * {@code resultDescriptorOf} for table-read contextual types and the
 * fixed {@code string}/{@code int}/{@code table} result types), the
 * verbatim D2 mapping table over the sealed
 * {@link RuntimeDescriptor} variants (including {@code Array}/{@code
 * Nullable} recursion, {@code Func} parameter-order/return/async
 * construction, and {@code ClassId} construction from
 * {@code (modulePath, name)}), the fail-closed {@code Type.Bytes}/
 * {@code Type.Error} {@code DESCRIPTOR_UNREPRESENTABLE} failure with the
 * exact {@link LoweringFailureDetail} fields ({@code capability
 * CONTAINERS_AND_STRINGS}, {@code semanticProfile DEAL_V1_2_INT32},
 * {@code irVersion deal.semantic-ir/1} — never a crash, never an
 * invented descriptor), the declared pre-E4 bridge status, and the
 * recorded E4 retirement hand-off naming {@code DescriptorService} as
 * the successor and the producer-singularity pin as the mechanical
 * enforcement.
 */
public class ContainerPayloadDescriptorsTest {

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
            fail("expected ContainerPayloadDescriptors.Defect for " + what
                + ", but no exception was raised");
        } catch (ContainerPayloadDescriptors.Defect expected) {
            passed++;
        } catch (Throwable other) {
            fail("expected ContainerPayloadDescriptors.Defect for " + what + ", got "
                + other.getClass().getSimpleName() + ": " + other.getMessage());
        }
    }

    private static ContainerPayloadDescriptors.Defect defect(Runnable runnable) {
        try {
            runnable.run();
            throw new IllegalStateException("no Defect raised");
        } catch (ContainerPayloadDescriptors.Defect defect) {
            return defect;
        }
    }

    // =========================================================================
    // 1. The two derivation positions produce the verbatim D2 mapping table
    // =========================================================================

    static void testMappingPinsBothPositions() {
        System.out.println("-- the two derivation positions: exact D2 mapping pins --");

        record Case(Type type, RuntimeDescriptor expected, java.lang.String text,
                    java.lang.String what) {}

        List<Case> cases = List.of(
            new Case(Type.Null.INSTANCE, RuntimeDescriptor.Null.INSTANCE, "null",
                "Null -> the null descriptor"),
            new Case(Type.Boolean.INSTANCE, RuntimeDescriptor.Boolean.INSTANCE, "boolean",
                "Boolean -> the boolean descriptor"),
            new Case(Type.Int.INSTANCE, RuntimeDescriptor.Int.INSTANCE, "int",
                "Int -> the signed32 int descriptor"),
            new Case(Type.Number.INSTANCE, RuntimeDescriptor.Number.INSTANCE, "number",
                "Number -> the number descriptor"),
            new Case(Type.String.INSTANCE, RuntimeDescriptor.String.INSTANCE, "string",
                "String -> the string descriptor"),
            new Case(Type.Table.INSTANCE, RuntimeDescriptor.Table.INSTANCE, "table",
                "Table -> the table descriptor"),
            new Case(new Type.Class("User", "src/app"),
                new RuntimeDescriptor.Class(new ClassId("src/app", "User")),
                "@src/app/User", "Class(name, modulePath) -> Class(new ClassId(modulePath, name))"),
            new Case(new Type.Class("Error", ""),
                new RuntimeDescriptor.Class(ClassId.ERROR),
                "@/Error", "the builtin Error class type maps to Class(ClassId.ERROR)"),
            new Case(new Type.Array(Type.Int.INSTANCE),
                new RuntimeDescriptor.Array(RuntimeDescriptor.Int.INSTANCE),
                "[int]", "Array(T) -> Array(recurse(T))"),
            new Case(new Type.Array(new Type.Array(Type.Int.INSTANCE)),
                new RuntimeDescriptor.Array(
                    new RuntimeDescriptor.Array(RuntimeDescriptor.Int.INSTANCE)),
                "[[int]]", "Array depth 2 recursion"),
            new Case(new Type.Array(new Type.Array(new Type.Array(Type.Int.INSTANCE))),
                new RuntimeDescriptor.Array(new RuntimeDescriptor.Array(
                    new RuntimeDescriptor.Array(RuntimeDescriptor.Int.INSTANCE))),
                "[[[int]]]", "Array depth 3 recursion"),
            new Case(new Type.Array(new Type.Nullable(new Type.Class("User", "src/app"))),
                new RuntimeDescriptor.Array(new RuntimeDescriptor.Nullable(
                    new RuntimeDescriptor.Class(new ClassId("src/app", "User")))),
                "[?@src/app/User]", "Array(Nullable(Class)) at depth 2"),
            new Case(new Type.Nullable(Type.String.INSTANCE),
                new RuntimeDescriptor.Nullable(RuntimeDescriptor.String.INSTANCE),
                "?string", "Nullable(T) -> Nullable(recurse(T))"),
            new Case(new Type.Nullable(new Type.Array(new Type.Class("User", "src/app"))),
                new RuntimeDescriptor.Nullable(new RuntimeDescriptor.Array(
                    new RuntimeDescriptor.Class(new ClassId("src/app", "User")))),
                "?[@src/app/User]", "Nullable(Array(Class)) at depth 2"),
            new Case(new Type.Func(List.of(Type.Int.INSTANCE, Type.String.INSTANCE),
                    Type.Boolean.INSTANCE),
                new RuntimeDescriptor.Func(
                    List.of(RuntimeDescriptor.Int.INSTANCE, RuntimeDescriptor.String.INSTANCE),
                    RuntimeDescriptor.Boolean.INSTANCE, false),
                "(int,string)->boolean",
                "Func(paramTypes, ret, false) -> param descriptors in source order"),
            new Case(new Type.Func(List.of(), Type.Null.INSTANCE),
                new RuntimeDescriptor.Func(List.of(), RuntimeDescriptor.Null.INSTANCE),
                "()->null", "nullary sync function"),
            new Case(new Type.Func(List.of(Type.Int.INSTANCE), Type.String.INSTANCE, true),
                new RuntimeDescriptor.Func(List.of(RuntimeDescriptor.Int.INSTANCE),
                    RuntimeDescriptor.String.INSTANCE, true),
                "async(int)->string", "async marker construction"),
            new Case(new Type.Func(List.of(new Type.Array(Type.Int.INSTANCE)),
                    new Type.Nullable(Type.String.INSTANCE), true),
                new RuntimeDescriptor.Func(
                    List.of(new RuntimeDescriptor.Array(RuntimeDescriptor.Int.INSTANCE)),
                    new RuntimeDescriptor.Nullable(RuntimeDescriptor.String.INSTANCE), true),
                "async([int])->?string", "async function with array parameter and nullable return"),
            new Case(new Type.Array(
                    new Type.Func(List.of(Type.Int.INSTANCE), Type.Boolean.INSTANCE)),
                new RuntimeDescriptor.Array(new RuntimeDescriptor.Func(
                    List.of(RuntimeDescriptor.Int.INSTANCE), RuntimeDescriptor.Boolean.INSTANCE,
                    false)),
                "[(int)->boolean]", "array of sync function at depth 2"),
            new Case(new Type.Nullable(new Type.Array(
                    new Type.Func(List.of(Type.Int.INSTANCE), Type.Boolean.INSTANCE))),
                new RuntimeDescriptor.Nullable(new RuntimeDescriptor.Array(
                    new RuntimeDescriptor.Func(
                        List.of(RuntimeDescriptor.Int.INSTANCE),
                        RuntimeDescriptor.Boolean.INSTANCE, false))),
                "?[(int)->boolean]", "nullable array of function at depth 3"));

        for (Case c : cases) {
            RuntimeDescriptor element = ContainerPayloadDescriptors.elementDescriptorOf(
                c.type());
            RuntimeDescriptor result = ContainerPayloadDescriptors.resultDescriptorOf(
                c.type());
            check(c.expected().equals(element),
                "elementDescriptorOf pins " + c.what() + "; got " + element);
            check(c.expected().equals(result),
                "resultDescriptorOf pins " + c.what() + "; got " + result);
            check(element.equals(result),
                "the two derivation positions agree for " + c.what());
            check(c.text().equals(element.canonicalSpecText()),
                "canonicalSpecText of " + c.what() + " is the schema-owned '" + c.text()
                    + "'; got " + element.canonicalSpecText());
            check(c.expected().equals(DescriptorService.describe(c.type())),
                "the bridge's mapping is the verbatim DescriptorService table for "
                    + c.what());
        }

        // ClassId construction from (modulePath, name) is pinned inside the
        // class descriptor.
        RuntimeDescriptor user = ContainerPayloadDescriptors.elementDescriptorOf(
            new Type.Class("User", "src/app"));
        check(user instanceof RuntimeDescriptor.Class
                && new ClassId("src/app", "User")
                    .equals(((RuntimeDescriptor.Class) user).classId()),
            "the class descriptor carries ClassId(modulePath, name) — modulePath and name "
                + "in order");
        check(((RuntimeDescriptor.Class) user).classId().modulePath().equals("src/app")
                && ((RuntimeDescriptor.Class) user).classId().name().equals("User"),
            "ClassId construction from (modulePath, name) preserves each component");

        // Func parameter order is pinned inside the descriptor.
        RuntimeDescriptor func = ContainerPayloadDescriptors.resultDescriptorOf(
            new Type.Func(List.of(Type.Int.INSTANCE, Type.String.INSTANCE),
                Type.Boolean.INSTANCE));
        List<RuntimeDescriptor> params =
            ((RuntimeDescriptor.Func) func).paramTypes();
        check(params.size() == 2
                && RuntimeDescriptor.Int.INSTANCE.equals(params.get(0))
                && RuntimeDescriptor.String.INSTANCE.equals(params.get(1)),
            "Func parameter descriptors are in source order (int, string)");
        check(RuntimeDescriptor.Boolean.INSTANCE
                .equals(((RuntimeDescriptor.Func) func).returnType())
                && !((RuntimeDescriptor.Func) func).isAsync(),
            "Func return descriptor and sync async marker are pinned");
    }

    // =========================================================================
    // 2. Fail-closed bytes/Error path: DESCRIPTOR_UNREPRESENTABLE with the
    //    exact LoweringFailureDetail fields — never a crash, never an
    //    invented descriptor
    // =========================================================================

    static void testFailClosedUnrepresentable() {
        System.out.println("-- fail-closed bytes/Error derivations and the exact "
            + "DESCRIPTOR_UNREPRESENTABLE detail --");

        expectDefect(() -> ContainerPayloadDescriptors.elementDescriptorOf(
                Type.Bytes.INSTANCE),
            "elementDescriptorOf(Type.Bytes)");
        expectDefect(() -> ContainerPayloadDescriptors.resultDescriptorOf(
                Type.Bytes.INSTANCE),
            "resultDescriptorOf(Type.Bytes)");
        expectDefect(() -> ContainerPayloadDescriptors.elementDescriptorOf(
                Type.Error.INSTANCE),
            "elementDescriptorOf(Type.Error)");
        expectDefect(() -> ContainerPayloadDescriptors.resultDescriptorOf(
                Type.Error.INSTANCE),
            "resultDescriptorOf(Type.Error)");
        expectDefect(() -> ContainerPayloadDescriptors.elementDescriptorOf(
                new Type.Array(Type.Bytes.INSTANCE)),
            "elementDescriptorOf(Array(Bytes)) — no bytes descriptor at any depth");
        expectDefect(() -> ContainerPayloadDescriptors.resultDescriptorOf(
                new Type.Nullable(Type.Bytes.INSTANCE)),
            "resultDescriptorOf(Nullable(Bytes)) — no bytes descriptor at any depth");
        expectDefect(() -> ContainerPayloadDescriptors.elementDescriptorOf(
                new Type.Func(List.of(Type.Bytes.INSTANCE), Type.Null.INSTANCE)),
            "elementDescriptorOf(Func with a bytes parameter)");
        expectDefect(() -> ContainerPayloadDescriptors.resultDescriptorOf(
                new Type.Array(Type.Error.INSTANCE)),
            "resultDescriptorOf(Array(Error)) — no sentinel descriptor at any depth");

        ContainerPayloadDescriptors.Defect bytesDefect = defect(
            () -> ContainerPayloadDescriptors.elementDescriptorOf(Type.Bytes.INSTANCE));
        ContainerPayloadDescriptors.Defect errorDefect = defect(
            () -> ContainerPayloadDescriptors.resultDescriptorOf(Type.Error.INSTANCE));
        check(bytesDefect.getMessage().contains("Type.Bytes"),
            "the bytes defect names the bytes primitive");
        check(bytesDefect.getMessage().contains("elementDescriptorOf"),
            "the bytes defect names the failing derivation position");
        check(errorDefect.getMessage().contains("Type.Error"),
            "the Error defect names the checker sentinel");
        check(errorDefect.getMessage().contains("resultDescriptorOf"),
            "the Error defect names the failing derivation position");
        check(bytesDefect.getCause() != null
                && bytesDefect.getCause().getMessage().contains("Type.Bytes"),
            "the defect preserves the underlying fail-closed cause");

        ModuleId module = new ModuleId("test.module");
        for (ContainerPayloadDescriptors.Defect d : List.of(bytesDefect, errorDefect)) {
            LoweringFailureDetail detail =
                ContainerPayloadDescriptors.loweringFailureDetail(module, d);
            check("test.module".equals(detail.module()),
                "the detail carries the module");
            check(SemanticCapability.CONTAINERS_AND_STRINGS == detail.capability(),
                "the detail capability is CONTAINERS_AND_STRINGS");
            check("DESCRIPTOR_UNREPRESENTABLE".equals(detail.validatorRule()),
                "the detail validatorRule is the named DESCRIPTOR_UNREPRESENTABLE");
            check(SemanticProfile.DEAL_V1_2_INT32 == detail.semanticProfile(),
                "the detail semanticProfile is DEAL_V1_2_INT32");
            check("deal.semantic-ir/1".equals(detail.irVersion()),
                "the detail irVersion is deal.semantic-ir/1");
            check(("ContainerPayloadDescriptors DESCRIPTOR_UNREPRESENTABLE ("
                    + d.getMessage() + ")").equals(detail.origin()),
                "the detail origin is the ContainerPayloadDescriptors origin");

            // The exact-fields pin: the detail is exactly the six pinned
            // fields with no other content.
            LoweringFailureDetail expectedDetail = new LoweringFailureDetail(
                "test.module", SemanticCapability.CONTAINERS_AND_STRINGS,
                "DESCRIPTOR_UNREPRESENTABLE", SemanticProfile.DEAL_V1_2_INT32,
                "deal.semantic-ir/1",
                "ContainerPayloadDescriptors DESCRIPTOR_UNREPRESENTABLE ("
                    + d.getMessage() + ")");
            check(expectedDetail.equals(detail), "the detail equals the exact pinned record");

            // The unit-production seam (C5) submits exactly this detail to
            // the registry: the registry accepts it and instantiates the
            // canonical E6005 message carrying every pinned field.
            CompilerDiagnostic diag = FailureContractRegistry.e6005(detail);
            check("E6005".equals(diag.code()), "the seam diagnostic code is E6005");
            check(diag.diagnosticCode() != null
                    && diag.diagnosticCode().phase() == DiagnosticCode.Phase.BACKEND_LOWERING,
                "the seam diagnostic phase is BACKEND_LOWERING");
            check("error".equals(diag.severity()), "the seam diagnostic is error severity");
            check(FailureContractRegistry.instantiateMessage(expectedDetail)
                    .equals(diag.message()),
                "the registry message is exactly the instantiated E6005 template over the "
                    + "bridge's detail");
            check(diag.message().contains("capability CONTAINERS_AND_STRINGS"),
                "the message carries capability CONTAINERS_AND_STRINGS");
            check(diag.message().contains("validatorRule DESCRIPTOR_UNREPRESENTABLE"),
                "the message carries validatorRule DESCRIPTOR_UNREPRESENTABLE");
            check(diag.message().contains("origin ContainerPayloadDescriptors "
                    + "DESCRIPTOR_UNREPRESENTABLE ("),
                "the message carries the ContainerPayloadDescriptors origin");
        }

        // The bridge itself never constructs the diagnostic: the conversion
        // through FailureContractRegistry belongs to the unit-production
        // seam (C5). No method of the bridge returns a CompilerDiagnostic.
        for (Method method : ContainerPayloadDescriptors.class.getDeclaredMethods()) {
            check(!CompilerDiagnostic.class.equals(method.getReturnType()),
                "the bridge never constructs an E6005 diagnostic (method "
                    + method.getName() + " returns " + method.getReturnType().getSimpleName()
                    + ")");
        }
    }

    // =========================================================================
    // 3. Declared pre-E4 bridge status and the recorded E4 retirement hand-off
    // =========================================================================

    static void testPreE4BridgeDeclarationAndHandoff() {
        System.out.println("-- declared pre-E4 bridge status and the recorded E4 "
            + "retirement hand-off --");

        String handoff = ContainerPayloadDescriptors.E4_RETIREMENT_HANDOFF;
        check(handoff.contains("DescriptorService"),
            "the recorded hand-off names DescriptorService as the successor");
        check(handoff.contains("only"),
            "the recorded hand-off pins the only-clause of the successor");
        check(handoff.contains("producer-singularity"),
            "the recorded hand-off names the producer-singularity pin");
        check(handoff.contains("E4"),
            "the recorded hand-off pins the E4 retirement gate");

        Path source = Path.of("deal/semantic/ContainerPayloadDescriptors.java");
        if (!Files.isRegularFile(source)) {
            fail("deal/semantic/ContainerPayloadDescriptors.java is missing; the "
                + "declaration scan fails closed");
            return;
        }
        try {
            java.lang.String content = Files.readString(source);
            check(content.contains("pre-E4 bridge"),
                "the component declaration states the pre-E4 bridge status");
            check(content.contains("no singular-producer claim"),
                "the component declaration makes no singular-producer claim");
            check(content.contains("schema-owned"),
                "the component declaration owns no canonical-text authority");
            check(content.contains("exportedDescriptors"),
                "the component declaration completes no exportedDescriptors ABI fields");
            check(content.contains("E3's payload positions"),
                "the component declaration serves only E3's payload positions");
            check(content.contains("DescriptorService")
                    && content.contains("producer-singularity"),
                "the component declaration records the E4 retirement hand-off (successor "
                    + "and mechanical enforcement)");
            check(content.contains("E4_RETIREMENT_HANDOFF"),
                "the hand-off is recorded as a public constant of the component");

            // The bridge constructs no descriptor of its own (delegation to
            // the verbatim DescriptorService table): consistent with the
            // currently-landed producer-singularity pin, which fails any
            // production file outside DescriptorService that references
            // deal.types.Type and constructs a RuntimeDescriptor.
            Pattern singleton = Pattern.compile(
                "RuntimeDescriptor\\.(Null|Boolean|Int|Number|String|Table)\\.INSTANCE");
            check(!content.contains("new RuntimeDescriptor."),
                "the bridge source constructs no RuntimeDescriptor variant");
            check(!singleton.matcher(content).find(),
                "the bridge source references no RuntimeDescriptor singleton spellings");
        } catch (java.io.IOException e) {
            fail("pre-E4 bridge declaration scan failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // 4. Exactly two derivation positions — nothing else
    // =========================================================================

    static void testExactlyTwoDerivationPositions() {
        System.out.println("-- exactly two Type->RuntimeDescriptor derivation positions --");

        Set<String> positions = new LinkedHashSet<>();
        for (Method method : ContainerPayloadDescriptors.class.getDeclaredMethods()) {
            boolean isDerivation = Modifier.isPublic(method.getModifiers())
                && Modifier.isStatic(method.getModifiers())
                && method.getParameterCount() == 1
                && Type.class.equals(method.getParameterTypes()[0])
                && RuntimeDescriptor.class.equals(method.getReturnType());
            if (isDerivation) {
                positions.add(method.getName());
            }
        }
        check(positions.equals(new LinkedHashSet<>(List.of(
                "elementDescriptorOf", "resultDescriptorOf"))),
            "the public derivation surface is exactly {elementDescriptorOf, "
                + "resultDescriptorOf}; got " + positions);
        check(positions.size() == 2, "there are exactly two derivation positions");
        check(!positions.contains("describe"),
            "the bridge has no general-purpose describe entry point");
    }

    // =========================================================================
    // 5. Determinism: byte-identical repeats
    // =========================================================================

    static void testDeterminism() {
        System.out.println("-- determinism: byte-identical repeats --");

        List<Type> types = new ArrayList<>(List.of(
            Type.Null.INSTANCE,
            Type.Int.INSTANCE,
            new Type.Class("User", "src/app"),
            new Type.Class("Error", ""),
            new Type.Array(new Type.Nullable(new Type.Class("User", "src/app"))),
            new Type.Func(List.of(Type.Int.INSTANCE, Type.String.INSTANCE),
                Type.Boolean.INSTANCE, true)));
        for (Type type : types) {
            java.lang.String firstElement =
                ContainerPayloadDescriptors.elementDescriptorOf(type).canonicalSpecText();
            java.lang.String firstResult =
                ContainerPayloadDescriptors.resultDescriptorOf(type).canonicalSpecText();
            for (int i = 0; i < 3; i++) {
                check(firstElement.equals(ContainerPayloadDescriptors
                        .elementDescriptorOf(type).canonicalSpecText()),
                    "repeated elementDescriptorOf of " + firstElement + " is byte-identical");
                check(firstResult.equals(ContainerPayloadDescriptors
                        .resultDescriptorOf(type).canonicalSpecText()),
                    "repeated resultDescriptorOf of " + firstResult + " is byte-identical");
            }
        }
    }

    // =========================================================================
    // Runner
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Container Payload Descriptor Bridge Test (ISSUE-0232 D2) ===\n");

        testMappingPinsBothPositions();
        testFailClosedUnrepresentable();
        testPreE4BridgeDeclarationAndHandoff();
        testExactlyTwoDerivationPositions();
        testDeterminism();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
