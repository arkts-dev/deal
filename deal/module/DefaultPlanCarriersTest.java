package deal.module;

import deal.ast.ExpressionNode;
import deal.ast.LiteralExpr;
import deal.ast.LiteralValue;
import deal.ast.Span;
import deal.checker.Symbol;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.identity.CanonicalClassIdentity;
import deal.identity.CanonicalModuleIdentity;
import deal.identity.ProjectModuleIdentity;
import deal.project.ProjectDeploymentIdentity;
import deal.types.Type;

import java.lang.reflect.RecordComponent;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The carrier-shape unit battery for the default-plan carriers of
 * ISSUE-0540 (design sources {@code default-plan-carriers} D1-D9,
 * {@code deal-v1.2-int32-and-bytes-architecture} D5,
 * {@code provider-versioned-default-plans} D2/D7,
 * {@code runtime-default-evaluators-and-construction-phases} D1,
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D3), covering the
 * design's verification items 1-9:
 *
 * <ol>
 *   <li><b>Shapes:</b> all nine carrier types (seven records plus the
 *       two forward seams) with the exact pinned field names, field
 *       types, and collection semantics; {@code Reason} has exactly
 *       RUNTIME_USE and DEFERRED_DEFAULT_BINDING; {@code Invocation} is
 *       a functional interface.</li>
 *   <li><b>Presence rules:</b> every XOR arm of the
 *       optional/required-present rules on both entries.</li>
 *   <li><b>Ordering:</b> {@code orderedFields},
 *       {@code runtimeResources}, and {@code runtimeDependencies}
 *       preserve input iteration order exactly, including copies from a
 *       non-LinkedHashSet input collection; {@code resolvedBindings}
 *       preserves insertion order.</li>
 *   <li><b>Uniqueness rejection:</b> duplicate entry names rejected in
 *       both plans; duplicate elements rejected in both ordered sets.</li>
 *   <li><b>Immutability:</b> input mutation after construction changes
 *       no carrier; accessor collections throw on mutation; completion
 *       leaves the source instance unchanged.</li>
 *   <li><b>Completion seams:</b> {@code withCanonicalSemantics}
 *       produces the completed expression (non-null content/digest,
 *       resources of any size including empty) and rejects nulls;
 *       {@code withRuntimeDependencies} produces the completed plan and
 *       rejects nulls; all other fields survive completion
 *       byte-identical.</li>
 *   <li><b>Invocation:</b> a lambda {@code invoke()} round-trips the
 *       value, propagates a thrown DEAL error unchanged, and mutates
 *       nothing.</li>
 *   <li><b>Reused carriers:</b> plan construction exercises
 *       {@link RuntimeResourceReference}, the identity carriers, and
 *       {@link DiagnosticRange} in their real shapes; the pinned
 *       provider-digest derivation is deterministic.</li>
 *   <li><b>Null rejection:</b> every non-nullable field rejects null at
 *       construction; entry names reject the empty string.</li>
 * </ol>
 *
 * <p>Runs via main() using the repository's plain check()-helper
 * convention (no JUnit dependency; assertion failures exit non-zero).</p>
 */
public final class DefaultPlanCarriersTest {

    private static int passed = 0;
    private static int failed = 0;

    // =========================================================================
    // Shared fixtures (real landed-carrier shapes, never substitutes)
    // =========================================================================

    private static final DiagnosticRange RANGE =
        new DiagnosticRange("main.deal", 1, 1, 1, 5, 0, 4, 4, RangeOrigin.SOURCE);

    private static final ProjectDeploymentIdentity DEPLOYMENT =
        new ProjectDeploymentIdentity("file:///proj/deal.json", "a".repeat(64));

    private static final SemanticModuleIdentity MODULE =
        new SemanticModuleIdentity(DEPLOYMENT, "file:///proj/src/main.deal");

    private static final SemanticModuleIdentity PROVIDER_MODULE =
        new SemanticModuleIdentity(DEPLOYMENT, "file:///proj/src/provider.deal");

    private static final LexicalDeclarationIdentity FUNCTION_LEX =
        new LexicalDeclarationIdentity(
            LexicalDeclarationIdentity.DeclarationKind.FUNCTION,
            "helper", "main", RANGE);

    private static final SemanticResourceIdentity RESOURCE =
        new SemanticResourceIdentity(
            PROVIDER_MODULE,
            LexicalDeclarationIdentity.DeclarationKind.FUNCTION,
            FUNCTION_LEX);

    private static final CanonicalClassIdentity CLASS_IDENTITY =
        new CanonicalClassIdentity(
            new CanonicalModuleIdentity.ProjectModule(
                new ProjectModuleIdentity("src", "/proj/src", List.of())),
            "User");

    /** The pinned deterministic provider-contract digest derivation. */
    private static final String DIGEST =
        RuntimeResourceReference.providerContractDigestOf("provider-body");

    private static final String DIGEST_2 =
        RuntimeResourceReference.providerContractDigestOf("other-body");

    private static final String DIGEST_3 =
        RuntimeResourceReference.providerContractDigestOf("third-body");

    private static final String IMPL_DIGEST = "i".repeat(64);

    private static final DeclaringLexicalContext CONTEXT =
        new DeclaringLexicalContext() { };

    private static final TypedEvaluatorIr IR = new TypedEvaluatorIr() { };

    // =========================================================================
    // Fixture builders
    // =========================================================================

    private static LiteralExpr literal() {
        return new LiteralExpr(new Span("main.deal", 1, 1, 1, 1),
            new LiteralValue.IntLiteral(0));
    }

    private static Symbol.FunctionSymbol helperSymbol() {
        return new Symbol.FunctionSymbol("helper",
            new Type.Func(List.of(Type.Int.INSTANCE), Type.Int.INSTANCE));
    }

    private static Symbol.VariableSymbol variableSymbol(String name) {
        return new Symbol.VariableSymbol(name, Type.Int.INSTANCE, false);
    }

    private static ResolvedDefaultExpression expression() {
        return new ResolvedDefaultExpression(
            literal(), Type.Int.INSTANCE, RANGE, CONTEXT,
            Map.of("helper", helperSymbol()), IR, null, null, Set.of());
    }

    private static CompilerClassDefaultEntry compilerEntry(String name,
                                                           boolean optional) {
        return new CompilerClassDefaultEntry(name, Type.Int.INSTANCE, "int",
            optional, optional ? null : expression());
    }

    private static RuntimeClassDefaultEntry runtimeEntry(String name,
                                                         boolean optional) {
        return new RuntimeClassDefaultEntry(name, "int", optional,
            optional ? null : evaluator());
    }

    private static RuntimeDefaultEvaluator evaluator() {
        return new RuntimeDefaultEvaluator(DIGEST, IMPL_DIGEST,
            () -> "value");
    }

    private static RuntimeResourceReference resource(String digest) {
        return new RuntimeResourceReference(
            RuntimeResourceReference.Kind.IMPORTED_FUNCTION_WRAPPER,
            RESOURCE, digest, RANGE);
    }

    private static RuntimeImportDependency dependency(
            String digest, RuntimeImportDependency.Reason reason) {
        return new RuntimeImportDependency(MODULE, PROVIDER_MODULE, "prov",
            RESOURCE, digest, RANGE, reason);
    }

    private static CompilerClassDefaultPlan compilerPlan() {
        return new CompilerClassDefaultPlan(CLASS_IDENTITY, MODULE,
            List.of(compilerEntry("count", false),
                compilerEntry("note", true)),
            Set.of());
    }

    private static RuntimeClassDefaultPlan runtimePlan() {
        return new RuntimeClassDefaultPlan(CLASS_IDENTITY,
            List.of(runtimeEntry("count", false),
                runtimeEntry("note", true)));
    }

    /**
     * A deterministic set that yields its backing list's elements on
     * iteration — including duplicates — so the carrier's defensive
     * duplicate-element rejection is exercisable even though the
     * completion seams take {@code Set} inputs (a well-behaved Set
     * never contains duplicate elements).
     */
    private static <T> Set<T> duplicateYieldingSet(List<T> elements) {
        return new AbstractSet<>() {
            @Override public Iterator<T> iterator() {
                return elements.iterator();
            }

            @Override public int size() {
                return elements.size();
            }
        };
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    public static void main(String[] args) {
        testShapesAndFieldTypes();
        testPresenceXorRules();
        testOrdering();
        testUniquenessRejection();
        testImmutability();
        testCompletionSeams();
        testInvocation();
        testReusedCarriers();
        testNullRejection();
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.err.println("DefaultPlanCarriersTest FAILED: " + failed
                + " failure(s)");
            System.exit(1);
        }
    }

    // =========================================================================
    // Verification 1: shapes, field names, field types, collection semantics
    // =========================================================================

    private static void testShapesAndFieldTypes() {
        System.out.println("testShapesAndFieldTypes");

        checkComponents(CompilerClassDefaultPlan.class,
            "classIdentity", "declaringSemanticModuleIdentity",
            "orderedFields", "runtimeDependencies");
        checkComponentTypes(CompilerClassDefaultPlan.class,
            "deal.identity.CanonicalClassIdentity",
            "deal.module.SemanticModuleIdentity",
            "java.util.List<deal.module.CompilerClassDefaultEntry>",
            "java.util.Set<deal.module.RuntimeImportDependency>");

        checkComponents(CompilerClassDefaultEntry.class,
            "name", "resolvedFieldType", "runtimeTypeDescriptor",
            "optional", "defaultExpression");
        checkComponentTypes(CompilerClassDefaultEntry.class,
            "java.lang.String", "deal.types.Type", "java.lang.String",
            "boolean", "deal.module.ResolvedDefaultExpression");

        checkComponents(ResolvedDefaultExpression.class,
            "expressionAst", "resolvedExpressionType", "sourceRange",
            "declaringLexicalContext", "resolvedBindings", "semanticIr",
            "canonicalSemanticContent", "semanticDigest",
            "runtimeResources");
        checkComponentTypes(ResolvedDefaultExpression.class,
            "deal.ast.ExpressionNode", "deal.types.Type",
            "deal.diagnostics.DiagnosticRange",
            "deal.module.DeclaringLexicalContext",
            "java.util.Map<java.lang.String, deal.checker.Symbol>",
            "deal.module.TypedEvaluatorIr", "java.lang.String",
            "java.lang.String",
            "java.util.Set<deal.module.RuntimeResourceReference>");

        checkComponents(RuntimeClassDefaultPlan.class,
            "classIdentity", "orderedFields");
        checkComponentTypes(RuntimeClassDefaultPlan.class,
            "deal.identity.CanonicalClassIdentity",
            "java.util.List<deal.module.RuntimeClassDefaultEntry>");

        checkComponents(RuntimeClassDefaultEntry.class,
            "name", "runtimeTypeDescriptor", "optional",
            "defaultEvaluator");
        checkComponentTypes(RuntimeClassDefaultEntry.class,
            "java.lang.String", "java.lang.String", "boolean",
            "deal.module.RuntimeDefaultEvaluator");

        checkComponents(RuntimeDefaultEvaluator.class,
            "semanticDigest", "implementationDigest", "invoke");
        checkComponentTypes(RuntimeDefaultEvaluator.class,
            "java.lang.String", "java.lang.String",
            "deal.module.RuntimeDefaultEvaluator$Invocation");

        checkComponents(RuntimeImportDependency.class,
            "fromSemanticModuleIdentity", "toSemanticModuleIdentity",
            "importAlias", "semanticResourceIdentity",
            "providerContractDigest", "sourceRange", "reason");
        checkComponentTypes(RuntimeImportDependency.class,
            "deal.module.SemanticModuleIdentity",
            "deal.module.SemanticModuleIdentity", "java.lang.String",
            "deal.module.SemanticResourceIdentity", "java.lang.String",
            "deal.diagnostics.DiagnosticRange",
            "deal.module.RuntimeImportDependency$Reason");

        // The two forward seams are memberless marker interfaces.
        check(DeclaringLexicalContext.class.isInterface(),
            "DeclaringLexicalContext must be an interface");
        check(DeclaringLexicalContext.class.getDeclaredMethods().length == 0,
            "DeclaringLexicalContext must declare no members");
        check(TypedEvaluatorIr.class.isInterface(),
            "TypedEvaluatorIr must be an interface");
        check(TypedEvaluatorIr.class.getDeclaredMethods().length == 0,
            "TypedEvaluatorIr must declare no members");

        // The closed reason set: exactly two values, in the pinned order.
        check(Arrays.equals(RuntimeImportDependency.Reason.values(),
                new RuntimeImportDependency.Reason[]{
                    RuntimeImportDependency.Reason.RUNTIME_USE,
                    RuntimeImportDependency.Reason.DEFERRED_DEFAULT_BINDING}),
            "Reason must have exactly RUNTIME_USE then"
                + " DEFERRED_DEFAULT_BINDING");

        // The invocation seam is a functional interface over a lambda.
        check(RuntimeDefaultEvaluator.Invocation.class
                .isAnnotationPresent(FunctionalInterface.class),
            "Invocation must be a functional interface");
        RuntimeDefaultEvaluator.Invocation viaLambda = () -> "lambda";
        check("lambda".equals(viaLambda.invoke()),
            "lambda-assigned Invocation must round-trip");

        // Accessor values equal the inputs field-for-field.
        ResolvedDefaultExpression expr = expression();
        check(expr.expressionAst() instanceof ExpressionNode,
            "expressionAst must be an ExpressionNode");
        check(expr.resolvedExpressionType() == Type.Int.INSTANCE,
            "resolvedExpressionType must be the input type");
        check(expr.sourceRange() == RANGE, "sourceRange must be the input");
        check(expr.declaringLexicalContext() == CONTEXT,
            "declaringLexicalContext must be the input");
        check(expr.resolvedBindings().get("helper") instanceof Symbol,
            "resolvedBindings must carry the input symbol");
        check(expr.semanticIr() == IR, "semanticIr must be the input");
        check(expr.canonicalSemanticContent() == null,
            "pre-completion canonical content must be null");
        check(expr.semanticDigest() == null,
            "pre-completion semantic digest must be null");
        check(expr.runtimeResources().isEmpty(),
            "pre-completion runtime resources must be empty");

        RuntimeDefaultEvaluator ev = evaluator();
        check(DIGEST.equals(ev.semanticDigest()),
            "semanticDigest must be the input");
        check(IMPL_DIGEST.equals(ev.implementationDigest()),
            "implementationDigest must be the input");
    }

    // =========================================================================
    // Verification 2: presence rules (XOR on both entries)
    // =========================================================================

    private static void testPresenceXorRules() {
        System.out.println("testPresenceXorRules");

        // Compiler-side: required-present must carry, optional must not.
        CompilerClassDefaultEntry requiredOk =
            new CompilerClassDefaultEntry("count", Type.Int.INSTANCE, "int",
                false, expression());
        check(requiredOk.defaultExpression() != null,
            "required-present compiler entry must carry a default"
                + " expression");

        CompilerClassDefaultEntry optionalOk =
            new CompilerClassDefaultEntry("note", Type.String.INSTANCE,
                "string", true, null);
        check(optionalOk.defaultExpression() == null,
            "optional compiler entry must carry no default expression");

        IllegalArgumentException requiredMissing = checkThrows(
            IllegalArgumentException.class,
            () -> new CompilerClassDefaultEntry("count", Type.Int.INSTANCE,
                "int", false, null),
            "required-present compiler entry without a default expression"
                + " must be rejected");
        check(requiredMissing.getMessage().contains("count"),
            "rejection message must name the entry: "
                + requiredMissing.getMessage());

        IllegalArgumentException optionalExtra = checkThrows(
            IllegalArgumentException.class,
            () -> new CompilerClassDefaultEntry("note", Type.String.INSTANCE,
                "string", true, expression()),
            "optional compiler entry with a default expression must be"
                + " rejected");
        check(optionalExtra.getMessage().contains("note"),
            "rejection message must name the entry: "
                + optionalExtra.getMessage());

        // Runtime-side: required-present must carry, optional must not.
        RuntimeClassDefaultEntry runtimeRequiredOk =
            new RuntimeClassDefaultEntry("count", "int", false, evaluator());
        check(runtimeRequiredOk.defaultEvaluator() != null,
            "required-present runtime entry must carry an evaluator");

        RuntimeClassDefaultEntry runtimeOptionalOk =
            new RuntimeClassDefaultEntry("note", "string", true, null);
        check(runtimeOptionalOk.defaultEvaluator() == null,
            "optional runtime entry must carry no evaluator");

        IllegalArgumentException runtimeRequiredMissing = checkThrows(
            IllegalArgumentException.class,
            () -> new RuntimeClassDefaultEntry("count", "int", false, null),
            "required-present runtime entry without an evaluator must be"
                + " rejected");
        check(runtimeRequiredMissing.getMessage().contains("count"),
            "rejection message must name the entry: "
                + runtimeRequiredMissing.getMessage());

        IllegalArgumentException runtimeOptionalExtra = checkThrows(
            IllegalArgumentException.class,
            () -> new RuntimeClassDefaultEntry("note", "string", true,
                evaluator()),
            "optional runtime entry with an evaluator must be rejected");
        check(runtimeOptionalExtra.getMessage().contains("note"),
            "rejection message must name the entry: "
                + runtimeOptionalExtra.getMessage());
    }

    // =========================================================================
    // Verification 3: insertion-order preservation
    // =========================================================================

    private static void testOrdering() {
        System.out.println("testOrdering");

        // orderedFields: list input order is preserved by both plans.
        List<CompilerClassDefaultEntry> compilerEntries = new ArrayList<>(
            List.of(compilerEntry("a", true), compilerEntry("b", false),
                compilerEntry("c", true)));
        CompilerClassDefaultPlan compilerPlan = new CompilerClassDefaultPlan(
            CLASS_IDENTITY, MODULE, compilerEntries, Set.of());
        checkListOrder(compilerPlan.orderedFields(),
            List.of("a", "b", "c"), "compiler orderedFields");

        List<RuntimeClassDefaultEntry> runtimeEntries = new ArrayList<>(
            List.of(runtimeEntry("a", true), runtimeEntry("b", false),
                runtimeEntry("c", true)));
        RuntimeClassDefaultPlan runtimePlan = new RuntimeClassDefaultPlan(
            CLASS_IDENTITY, runtimeEntries);
        checkListOrder(runtimePlan.orderedFields(),
            List.of("a", "b", "c"), "runtime orderedFields");

        // runtimeResources: a LinkedHashSet input keeps its insertion
        // order.
        Set<RuntimeResourceReference> resourcesInsertion =
            new LinkedHashSet<>(List.of(resource(DIGEST),
                resource(DIGEST_2), resource(DIGEST_3)));
        ResolvedDefaultExpression completedInsertion = expression()
            .withCanonicalSemantics("content", DIGEST, resourcesInsertion);
        checkSetOrder(completedInsertion.runtimeResources(),
            List.of(DIGEST, DIGEST_2, DIGEST_3),
            "runtimeResources from a LinkedHashSet input");

        // runtimeResources: a non-LinkedHashSet input (a HashSet) keeps
        // its own iteration order through the copy.
        Set<RuntimeResourceReference> resourcesHashed = new HashSet<>(
            List.of(resource(DIGEST), resource(DIGEST_2),
                resource(DIGEST_3)));
        ResolvedDefaultExpression completedHashed = expression()
            .withCanonicalSemantics("content", DIGEST, resourcesHashed);
        checkSetOrder(completedHashed.runtimeResources(),
            resourcesHashed.stream()
                .map(RuntimeResourceReference::providerContractDigest)
                .toList(),
            "runtimeResources from a non-LinkedHashSet input");

        // runtimeDependencies: a LinkedHashSet input keeps its insertion
        // order (positions and reasons pinned).
        Set<RuntimeImportDependency> depsInsertion = new LinkedHashSet<>(
            List.of(
                dependency(DIGEST, RuntimeImportDependency.Reason.RUNTIME_USE),
                dependency(DIGEST_2,
                    RuntimeImportDependency.Reason.DEFERRED_DEFAULT_BINDING)));
        CompilerClassDefaultPlan completedPlan = compilerPlan()
            .withRuntimeDependencies(depsInsertion);
        List<RuntimeImportDependency> deps =
            List.copyOf(completedPlan.runtimeDependencies());
        check(deps.size() == 2, "completed dependencies must have 2 entries");
        check(deps.get(0).providerContractDigest().equals(DIGEST)
                && deps.get(0).reason()
                    == RuntimeImportDependency.Reason.RUNTIME_USE,
            "first dependency must keep the input position and reason");
        check(deps.get(1).providerContractDigest().equals(DIGEST_2)
                && deps.get(1).reason()
                    == RuntimeImportDependency.Reason.DEFERRED_DEFAULT_BINDING,
            "second dependency must keep the input position and reason");

        // runtimeDependencies: a non-LinkedHashSet input keeps its own
        // iteration order through the copy.
        Set<RuntimeImportDependency> depsHashed = new HashSet<>(List.of(
            dependency(DIGEST, RuntimeImportDependency.Reason.RUNTIME_USE),
            dependency(DIGEST_2,
                RuntimeImportDependency.Reason.DEFERRED_DEFAULT_BINDING)));
        CompilerClassDefaultPlan hashedPlan = compilerPlan()
            .withRuntimeDependencies(depsHashed);
        check(List.copyOf(hashedPlan.runtimeDependencies()).stream()
                .map(RuntimeImportDependency::providerContractDigest).toList()
                .equals(List.copyOf(depsHashed).stream()
                    .map(RuntimeImportDependency::providerContractDigest)
                    .toList()),
            "dependencies from a non-LinkedHashSet input must keep the"
                + " input iteration order");

        // resolvedBindings: insertion order is preserved.
        Map<String, Symbol> bindings = new LinkedHashMap<>();
        bindings.put("first", variableSymbol("first"));
        bindings.put("second", variableSymbol("second"));
        bindings.put("third", variableSymbol("third"));
        ResolvedDefaultExpression bound = new ResolvedDefaultExpression(
            literal(), Type.Int.INSTANCE, RANGE, CONTEXT, bindings, IR,
            null, null, Set.of());
        check(List.copyOf(bound.resolvedBindings().keySet())
                .equals(List.of("first", "second", "third")),
            "resolvedBindings must preserve insertion order");
    }

    // =========================================================================
    // Verification 4: uniqueness rejection
    // =========================================================================

    private static void testUniquenessRejection() {
        System.out.println("testUniquenessRejection");

        IllegalArgumentException compilerDup = checkThrows(
            IllegalArgumentException.class,
            () -> new CompilerClassDefaultPlan(CLASS_IDENTITY, MODULE,
                List.of(compilerEntry("dup", true),
                    compilerEntry("dup", false)),
                Set.of()),
            "duplicate compiler entry names must be rejected");
        check(compilerDup.getMessage().contains("dup"),
            "duplicate-name message must name the entry: "
                + compilerDup.getMessage());

        IllegalArgumentException runtimeDup = checkThrows(
            IllegalArgumentException.class,
            () -> new RuntimeClassDefaultPlan(CLASS_IDENTITY,
                List.of(runtimeEntry("dup", true),
                    runtimeEntry("dup", false))),
            "duplicate runtime entry names must be rejected");
        check(runtimeDup.getMessage().contains("dup"),
            "duplicate-name message must name the entry: "
                + runtimeDup.getMessage());

        IllegalArgumentException resourceDup = checkThrows(
            IllegalArgumentException.class,
            () -> new ResolvedDefaultExpression(literal(),
                Type.Int.INSTANCE, RANGE, CONTEXT,
                Map.of("helper", helperSymbol()), IR, null, null,
                duplicateYieldingSet(List.of(resource(DIGEST),
                    resource(DIGEST)))),
            "duplicate runtime resources must be rejected at"
                + " construction");
        check(resourceDup.getMessage().contains("runtimeResources"),
            "duplicate-resource message must name the field: "
                + resourceDup.getMessage());

        IllegalArgumentException completedResourceDup = checkThrows(
            IllegalArgumentException.class,
            () -> expression().withCanonicalSemantics("content", DIGEST,
                duplicateYieldingSet(List.of(resource(DIGEST_2),
                    resource(DIGEST_2)))),
            "duplicate runtime resources must be rejected at completion");
        check(completedResourceDup.getMessage().contains("runtimeResources"),
            "duplicate-resource completion message must name the field: "
                + completedResourceDup.getMessage());

        IllegalArgumentException dependencyDup = checkThrows(
            IllegalArgumentException.class,
            () -> compilerPlan().withRuntimeDependencies(
                duplicateYieldingSet(List.of(
                    dependency(DIGEST,
                        RuntimeImportDependency.Reason.RUNTIME_USE),
                    dependency(DIGEST,
                        RuntimeImportDependency.Reason.RUNTIME_USE)))),
            "duplicate runtime dependencies must be rejected at"
                + " completion");
        check(dependencyDup.getMessage().contains("runtimeDependencies"),
            "duplicate-dependency message must name the field: "
                + dependencyDup.getMessage());
    }

    // =========================================================================
    // Verification 5: immutability
    // =========================================================================

    private static void testImmutability() {
        System.out.println("testImmutability");

        // Mutating the input list after construction changes no carrier.
        List<CompilerClassDefaultEntry> entries = new ArrayList<>(
            List.of(compilerEntry("a", true), compilerEntry("b", false)));
        CompilerClassDefaultPlan plan = new CompilerClassDefaultPlan(
            CLASS_IDENTITY, MODULE, entries, Set.of());
        entries.add(compilerEntry("c", true));
        check(plan.orderedFields().size() == 2,
            "input list mutation must not change the plan");

        // Mutating the input set after completion changes no carrier.
        Set<RuntimeResourceReference> resources = new LinkedHashSet<>(
            List.of(resource(DIGEST), resource(DIGEST_2)));
        ResolvedDefaultExpression completed = expression()
            .withCanonicalSemantics("content", DIGEST, resources);
        resources.add(resource(DIGEST_3));
        check(completed.runtimeResources().size() == 2,
            "input set mutation must not change the completed expression");

        // Accessor collections throw on mutation.
        checkThrows(UnsupportedOperationException.class,
            () -> plan.orderedFields().add(compilerEntry("d", true)),
            "orderedFields accessor must be unmodifiable");
        checkThrows(UnsupportedOperationException.class,
            () -> completed.runtimeResources().add(resource(DIGEST_3)),
            "runtimeResources accessor must be unmodifiable");
        checkThrows(UnsupportedOperationException.class,
            () -> expression().resolvedBindings().put("extra",
                variableSymbol("extra")),
            "resolvedBindings accessor must be unmodifiable");

        // Completion leaves the source instance unchanged.
        ResolvedDefaultExpression source = expression();
        ResolvedDefaultExpression done = source.withCanonicalSemantics(
            "content", DIGEST, Set.of(resource(DIGEST)));
        check(source.canonicalSemanticContent() == null
                && source.semanticDigest() == null
                && source.runtimeResources().isEmpty(),
            "completion must leave the source expression unchanged");
        check(done.canonicalSemanticContent().equals("content")
                && done.semanticDigest().equals(DIGEST)
                && done.runtimeResources().size() == 1,
            "completion must produce the completed expression");

        CompilerClassDefaultPlan sourcePlan = compilerPlan();
        CompilerClassDefaultPlan donePlan = sourcePlan.withRuntimeDependencies(
            Set.of(dependency(DIGEST,
                RuntimeImportDependency.Reason.RUNTIME_USE)));
        check(sourcePlan.runtimeDependencies().isEmpty(),
            "completion must leave the source plan unchanged");
        check(donePlan.runtimeDependencies().size() == 1,
            "completion must produce the completed plan");
    }

    // =========================================================================
    // Verification 6: completion seams
    // =========================================================================

    private static void testCompletionSeams() {
        System.out.println("testCompletionSeams");

        // Empty completed resources are the correct state for defaults
        // whose IR walk visits no runtime resource.
        ResolvedDefaultExpression empty = expression().withCanonicalSemantics(
            "content", DIGEST, Set.of());
        check(empty.canonicalSemanticContent().equals("content")
                && empty.semanticDigest().equals(DIGEST)
                && empty.runtimeResources().isEmpty(),
            "completion with an empty resource set must succeed");

        // All other fields survive completion byte-identical.
        ResolvedDefaultExpression source = expression();
        ResolvedDefaultExpression done = source.withCanonicalSemantics(
            "content", DIGEST,
            new LinkedHashSet<>(List.of(resource(DIGEST),
                resource(DIGEST_2))));
        check(source.expressionAst() == done.expressionAst()
                && source.resolvedExpressionType() == done.resolvedExpressionType()
                && source.sourceRange() == done.sourceRange()
                && source.declaringLexicalContext() == done.declaringLexicalContext()
                && source.resolvedBindings().equals(done.resolvedBindings())
                && source.semanticIr() == done.semanticIr(),
            "completion must keep all other fields byte-identical");

        checkThrows(NullPointerException.class,
            () -> expression().withCanonicalSemantics(null, DIGEST, Set.of()),
            "null canonical content must be rejected");
        checkThrows(NullPointerException.class,
            () -> expression().withCanonicalSemantics("content", null,
                Set.of()),
            "null semantic digest must be rejected");
        checkThrows(NullPointerException.class,
            () -> expression().withCanonicalSemantics("content", DIGEST,
                null),
            "null runtime resources must be rejected");

        // Plan completion: empty and populated sets, null rejection, and
        // field preservation.
        CompilerClassDefaultPlan plan = compilerPlan();
        CompilerClassDefaultPlan emptyPlan = plan.withRuntimeDependencies(
            Set.of());
        check(emptyPlan.runtimeDependencies().isEmpty(),
            "completion with an empty dependency set must succeed");
        checkThrows(NullPointerException.class,
            () -> plan.withRuntimeDependencies(null),
            "null runtime dependencies must be rejected");

        CompilerClassDefaultPlan populated = plan.withRuntimeDependencies(
            Set.of(dependency(DIGEST,
                RuntimeImportDependency.Reason.RUNTIME_USE)));
        check(populated.classIdentity() == plan.classIdentity()
                && populated.declaringSemanticModuleIdentity()
                    == plan.declaringSemanticModuleIdentity()
                && populated.orderedFields().equals(plan.orderedFields()),
            "plan completion must keep all other fields byte-identical");
    }

    // =========================================================================
    // Verification 7: the zero-argument sync invocation
    // =========================================================================

    private static void testInvocation() {
        System.out.println("testInvocation");

        RuntimeDefaultEvaluator evaluator = evaluator();
        check("value".equals(evaluator.invoke().invoke()),
            "invoke() must round-trip the evaluated DEAL value");

        // A raised DEAL error propagates unchanged.
        RuntimeException boom = new RuntimeException("boom");
        RuntimeDefaultEvaluator failing = new RuntimeDefaultEvaluator(
            DIGEST, IMPL_DIGEST, () -> {
                throw boom;
            });
        RuntimeException caught = checkThrows(RuntimeException.class,
            () -> failing.invoke().invoke(),
            "a raised DEAL error must propagate through invoke()");
        check(caught == boom,
            "the propagated error must be the identical instance");

        // Invocation mutates no evaluator state.
        check(DIGEST.equals(failing.semanticDigest())
                && IMPL_DIGEST.equals(failing.implementationDigest()),
            "invoke() must mutate no evaluator fields");
        evaluator.invoke().invoke();
        check(DIGEST.equals(evaluator.semanticDigest())
                && IMPL_DIGEST.equals(evaluator.implementationDigest())
                && "value".equals(evaluator.invoke().invoke()),
            "repeated invocation must leave the evaluator unchanged");
    }

    // =========================================================================
    // Verification 8: reused landed carriers
    // =========================================================================

    private static void testReusedCarriers() {
        System.out.println("testReusedCarriers");

        // The pinned deterministic digest derivation.
        check(RuntimeResourceReference.providerContractDigestOf("provider-body")
                .equals(DIGEST),
            "providerContractDigestOf must be deterministic");
        check(DIGEST.length() == 64
                && DIGEST.chars().allMatch(c ->
                    (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')),
            "the digest must be 64 lowercase hex chars");
        check(!DIGEST.equals(DIGEST_2),
            "different provider content must produce different digests");

        // The full carrier graph over the real landed carriers.
        RuntimeResourceReference resource = resource(DIGEST);
        check(resource.kind()
                == RuntimeResourceReference.Kind.IMPORTED_FUNCTION_WRAPPER
                && resource.semanticResourceIdentity() == RESOURCE
                && resource.sourceRange() == RANGE,
            "RuntimeResourceReference must carry the landed shape");

        RuntimeImportDependency edge = dependency(DIGEST,
            RuntimeImportDependency.Reason.DEFERRED_DEFAULT_BINDING);
        check(edge.fromSemanticModuleIdentity() == MODULE
                && edge.toSemanticModuleIdentity() == PROVIDER_MODULE
                && edge.semanticResourceIdentity() == RESOURCE
                && edge.providerContractDigest().equals(DIGEST)
                && edge.sourceRange() == RANGE,
            "RuntimeImportDependency must carry the landed identity"
                + " carriers");

        check(edge.semanticResourceIdentity().semanticModuleIdentity()
                == PROVIDER_MODULE
                && edge.semanticResourceIdentity().resourceKind()
                    == LexicalDeclarationIdentity.DeclarationKind.FUNCTION
                && edge.semanticResourceIdentity()
                    .lexicalDeclarationIdentity().declaredName()
                        .equals("helper")
                && edge.semanticResourceIdentity()
                    .lexicalDeclarationIdentity()
                    .enclosingLexicalDeclarationPath().equals("main")
                && edge.semanticResourceIdentity()
                    .lexicalDeclarationIdentity().sourceScalarRange() == RANGE,
            "the reused SemanticResourceIdentity/LexicalDeclarationIdentity"
                + " must round-trip through the edge");

        CompilerClassDefaultPlan plan = new CompilerClassDefaultPlan(
            CLASS_IDENTITY, MODULE,
            List.of(compilerEntry("count", false)), Set.of());
        check(plan.classIdentity() == CLASS_IDENTITY
                && plan.declaringSemanticModuleIdentity() == MODULE,
            "the plan must carry the reused identity carriers");

        // The carrier set references the landed carriers by their real
        // package — no duplicates of those types exist among the
        // carriers (pinned by the component-type names above and by the
        // landed package here).
        check(RuntimeResourceReference.class.getPackageName()
                .equals("deal.module")
                && SemanticModuleIdentity.class.getPackageName()
                    .equals("deal.module")
                && SemanticResourceIdentity.class.getPackageName()
                    .equals("deal.module")
                && LexicalDeclarationIdentity.class.getPackageName()
                    .equals("deal.module")
                && DiagnosticRange.class.getPackageName()
                    .equals("deal.diagnostics"),
            "the reused carriers must be the landed deal.module/"
                + "deal.diagnostics types");
    }

    // =========================================================================
    // Verification 9: null rejection on every non-nullable field
    // =========================================================================

    private static void testNullRejection() {
        System.out.println("testNullRejection");

        checkThrows(NullPointerException.class,
            () -> new CompilerClassDefaultPlan(null, MODULE,
                List.of(), Set.of()),
            "null classIdentity must be rejected (compiler plan)");
        checkThrows(NullPointerException.class,
            () -> new CompilerClassDefaultPlan(CLASS_IDENTITY, null,
                List.of(), Set.of()),
            "null declaringSemanticModuleIdentity must be rejected");
        checkThrows(NullPointerException.class,
            () -> new CompilerClassDefaultPlan(CLASS_IDENTITY, MODULE,
                null, Set.of()),
            "null orderedFields must be rejected (compiler plan)");
        checkThrows(NullPointerException.class,
            () -> new CompilerClassDefaultPlan(CLASS_IDENTITY, MODULE,
                List.of(), null),
            "null runtimeDependencies must be rejected");

        checkThrows(NullPointerException.class,
            () -> new CompilerClassDefaultEntry(null, Type.Int.INSTANCE,
                "int", true, null),
            "null name must be rejected (compiler entry)");
        checkThrows(IllegalArgumentException.class,
            () -> new CompilerClassDefaultEntry("", Type.Int.INSTANCE,
                "int", true, null),
            "empty name must be rejected (compiler entry)");
        checkThrows(NullPointerException.class,
            () -> new CompilerClassDefaultEntry("f", null, "int", true,
                null),
            "null resolvedFieldType must be rejected");
        checkThrows(NullPointerException.class,
            () -> new CompilerClassDefaultEntry("f", Type.Int.INSTANCE,
                null, true, null),
            "null runtimeTypeDescriptor must be rejected (compiler entry)");

        checkThrows(NullPointerException.class,
            () -> new ResolvedDefaultExpression(null, Type.Int.INSTANCE,
                RANGE, CONTEXT, Map.of(), IR, null, null, Set.of()),
            "null expressionAst must be rejected");
        checkThrows(NullPointerException.class,
            () -> new ResolvedDefaultExpression(literal(), null, RANGE,
                CONTEXT, Map.of(), IR, null, null, Set.of()),
            "null resolvedExpressionType must be rejected");
        checkThrows(NullPointerException.class,
            () -> new ResolvedDefaultExpression(literal(), Type.Int.INSTANCE,
                null, CONTEXT, Map.of(), IR, null, null, Set.of()),
            "null sourceRange must be rejected");
        checkThrows(NullPointerException.class,
            () -> new ResolvedDefaultExpression(literal(), Type.Int.INSTANCE,
                RANGE, null, Map.of(), IR, null, null, Set.of()),
            "null declaringLexicalContext must be rejected");
        checkThrows(NullPointerException.class,
            () -> new ResolvedDefaultExpression(literal(), Type.Int.INSTANCE,
                RANGE, CONTEXT, null, IR, null, null, Set.of()),
            "null resolvedBindings must be rejected");
        checkThrows(NullPointerException.class,
            () -> new ResolvedDefaultExpression(literal(), Type.Int.INSTANCE,
                RANGE, CONTEXT, Map.of(), null, null, null, Set.of()),
            "null semanticIr must be rejected");
        checkThrows(NullPointerException.class,
            () -> new ResolvedDefaultExpression(literal(), Type.Int.INSTANCE,
                RANGE, CONTEXT, Map.of(), IR, null, null, null),
            "null runtimeResources must be rejected");

        checkThrows(NullPointerException.class,
            () -> new RuntimeClassDefaultPlan(null, List.of()),
            "null classIdentity must be rejected (runtime plan)");
        checkThrows(NullPointerException.class,
            () -> new RuntimeClassDefaultPlan(CLASS_IDENTITY, null),
            "null orderedFields must be rejected (runtime plan)");

        checkThrows(NullPointerException.class,
            () -> new RuntimeClassDefaultEntry(null, "int", true, null),
            "null name must be rejected (runtime entry)");
        checkThrows(IllegalArgumentException.class,
            () -> new RuntimeClassDefaultEntry("", "int", true, null),
            "empty name must be rejected (runtime entry)");
        checkThrows(NullPointerException.class,
            () -> new RuntimeClassDefaultEntry("f", null, true, null),
            "null runtimeTypeDescriptor must be rejected (runtime entry)");

        checkThrows(NullPointerException.class,
            () -> new RuntimeDefaultEvaluator(null, IMPL_DIGEST,
                () -> "value"),
            "null semanticDigest must be rejected");
        checkThrows(NullPointerException.class,
            () -> new RuntimeDefaultEvaluator(DIGEST, null,
                () -> "value"),
            "null implementationDigest must be rejected");
        checkThrows(NullPointerException.class,
            () -> new RuntimeDefaultEvaluator(DIGEST, IMPL_DIGEST, null),
            "null invoke must be rejected");

        checkThrows(NullPointerException.class,
            () -> new RuntimeImportDependency(null, PROVIDER_MODULE, "prov",
                RESOURCE, DIGEST, RANGE,
                RuntimeImportDependency.Reason.RUNTIME_USE),
            "null fromSemanticModuleIdentity must be rejected");
        checkThrows(NullPointerException.class,
            () -> new RuntimeImportDependency(MODULE, null, "prov",
                RESOURCE, DIGEST, RANGE,
                RuntimeImportDependency.Reason.RUNTIME_USE),
            "null toSemanticModuleIdentity must be rejected");
        checkThrows(NullPointerException.class,
            () -> new RuntimeImportDependency(MODULE, PROVIDER_MODULE, null,
                RESOURCE, DIGEST, RANGE,
                RuntimeImportDependency.Reason.RUNTIME_USE),
            "null importAlias must be rejected");
        checkThrows(NullPointerException.class,
            () -> new RuntimeImportDependency(MODULE, PROVIDER_MODULE, "prov",
                null, DIGEST, RANGE,
                RuntimeImportDependency.Reason.RUNTIME_USE),
            "null semanticResourceIdentity must be rejected");
        checkThrows(NullPointerException.class,
            () -> new RuntimeImportDependency(MODULE, PROVIDER_MODULE, "prov",
                RESOURCE, null, RANGE,
                RuntimeImportDependency.Reason.RUNTIME_USE),
            "null providerContractDigest must be rejected");
        checkThrows(NullPointerException.class,
            () -> new RuntimeImportDependency(MODULE, PROVIDER_MODULE, "prov",
                RESOURCE, DIGEST, null,
                RuntimeImportDependency.Reason.RUNTIME_USE),
            "null sourceRange must be rejected");
        checkThrows(NullPointerException.class,
            () -> new RuntimeImportDependency(MODULE, PROVIDER_MODULE, "prov",
                RESOURCE, DIGEST, RANGE, null),
            "null reason must be rejected");
    }

    // =========================================================================
    // Assertion helpers
    // =========================================================================

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static <T extends Throwable> T checkThrows(
            Class<T> expected, Runnable action, String message) {
        try {
            action.run();
        } catch (Throwable t) {
            if (expected.isInstance(t)) {
                passed++;
                return expected.cast(t);
            }
            failed++;
            System.err.println("FAIL: " + message + " — expected "
                + expected.getSimpleName() + ", got " + t);
            return null;
        }
        failed++;
        System.err.println("FAIL: " + message + " — nothing was thrown");
        return null;
    }

    private static void checkComponents(Class<?> recordClass,
                                        String... expectedNames) {
        RecordComponent[] components = recordClass.getRecordComponents();
        check(components != null && components.length == expectedNames.length,
            recordClass.getSimpleName() + " must have "
                + expectedNames.length + " components, got "
                + (components == null ? "null" : components.length));
        if (components == null) {
            return;
        }
        for (int i = 0; i < components.length; i++) {
            check(expectedNames[i].equals(components[i].getName()),
                recordClass.getSimpleName() + " component " + i
                    + " must be named '" + expectedNames[i] + "', got '"
                    + components[i].getName() + "'");
        }
    }

    private static void checkComponentTypes(Class<?> recordClass,
                                            String... expectedTypeNames) {
        RecordComponent[] components = recordClass.getRecordComponents();
        check(components != null && components.length == expectedTypeNames.length,
            recordClass.getSimpleName() + " must have "
                + expectedTypeNames.length + " components, got "
                + (components == null ? "null" : components.length));
        if (components == null) {
            return;
        }
        for (int i = 0; i < components.length; i++) {
            String actual = components[i].getGenericType().getTypeName();
            check(expectedTypeNames[i].equals(actual),
                recordClass.getSimpleName() + " component "
                    + components[i].getName() + " must be typed '"
                    + expectedTypeNames[i] + "', got '" + actual + "'");
        }
    }

    private static void checkListOrder(List<? extends Object> actual,
                                       List<String> expectedNames,
                                       String field) {
        check(actual.size() == expectedNames.size(),
            field + " must have " + expectedNames.size() + " entries");
        for (int i = 0; i < expectedNames.size(); i++) {
            Object entry = actual.get(i);
            check(entry instanceof CompilerClassDefaultEntry compilerEntry
                    ? expectedNames.get(i).equals(compilerEntry.name())
                    : expectedNames.get(i).equals(
                        ((RuntimeClassDefaultEntry) entry).name()),
                field + " position " + i + " must be '"
                    + expectedNames.get(i) + "'");
        }
    }

    private static void checkSetOrder(Set<RuntimeResourceReference> actual,
                                      List<String> expectedDigests,
                                      String field) {
        List<RuntimeResourceReference> ordered = List.copyOf(actual);
        check(ordered.size() == expectedDigests.size(),
            field + " must have " + expectedDigests.size() + " entries");
        for (int i = 0; i < expectedDigests.size(); i++) {
            check(expectedDigests.get(i).equals(
                    ordered.get(i).providerContractDigest()),
                field + " position " + i + " must carry digest '"
                    + expectedDigests.get(i) + "'");
        }
    }

    private DefaultPlanCarriersTest() {
    }
}
