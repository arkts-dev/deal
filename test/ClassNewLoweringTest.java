package deal.test;

import deal.ast.ProgramNode;
import deal.checker.*;
import deal.lexer.Lexer;
import deal.parser.Parser;
import deal.semantic.*;
import deal.semantic.ir.*;

import java.nio.file.Path;
import java.util.*;

/** Source-to-IR construction shape, execution, and deterministic provider registration. */
public class ClassNewLoweringTest {
    private static int passed, failed;
    private static final ModuleId MODULE = new ModuleId("main");
    private static final String SOURCE_ID = "test.deal";
    private static final String REGISTRY_HASH =
        CapabilityRegistry.releaseRegistry().capabilityRegistryHash();

    private static void check(boolean condition, String message) {
        if (condition) passed++;
        else { failed++; System.err.println("FAIL: " + message); }
    }

    private static SemanticLowerer.ClassDeclarationCoreResult lowerModule(String source) {
        var lex = new Lexer(source, SOURCE_ID).tokenize();
        var parse = new Parser(lex.tokens(), SOURCE_ID).parse();
        check(!parse.hasErrors(), "source parses: " + parse.diagnostics());
        if (parse.hasErrors()) return null;
        NameResolver resolver = new NameResolver(MODULE.path(), (ModuleResolver) null);
        SymbolTable symbols = resolver.resolve(parse.program());
        check(resolver.diagnostics().isEmpty(), "source resolves: " + resolver.diagnostics());
        if (!resolver.diagnostics().isEmpty()) return null;
        CheckResult checks = TypeChecker.check(MODULE.path(), symbols, resolver, parse.program());
        check(!checks.hasErrors(), "source checks: " + checks.diagnostics());
        if (checks.hasErrors()) return null;
        CompilerInvocation invocation = CompilerProfileProvider.resolve(ReleaseState.V1_3_ACTIVE,
            CapabilityRegistry.releaseRegistry());
        ModuleFact fact = new ModuleFact(SOURCE_ID, MODULE, false, false, parse.program(),
            Map.of(), symbols, checks, List.of());
        var built = CheckedProjectBuilder.build(invocation, MODULE, List.of(fact));
        check(!built.hasErrors() && built.input() != null && built.index() != null,
            "checked project builds: " + built.diagnostics());
        if (built.hasErrors() || built.input() == null || built.index() == null) return null;
        var manifests = LoweringSupport.computeManifests(invocation, built.input(), built.index());
        check(manifests.diagnostics().isEmpty() && manifests.manifests().size() == 1,
            "one requirement manifest: " + manifests.diagnostics());
        if (!manifests.diagnostics().isEmpty() || manifests.manifests().size() != 1) return null;
        Map<ConstructKind, List<SemanticOpKind>> coverage =
            new LinkedHashMap<>(manifests.manifests().get(0).constructCoverage());
        coverage.remove(ConstructKind.IMPORT_EXPORT_ENTRY);
        var module = new CheckedModuleInput(MODULE, SOURCE_ID, Path.of(SOURCE_ID), parse.program(),
            checks, List.of(), List.of(), CheckedModuleKind.IMPLEMENTATION);
        var result = SemanticLowerer.lowerModuleClassCore(module, invocation.semanticProfile(),
            coverage, built.index().interfaceIndexDigest(), REGISTRY_HASH,
            built.index().modules().get(MODULE), SemanticIdAllocator.over(List.of(MODULE)));
        check(result.lowering() != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null,
            "validated lowering: " + result.lowering().diagnostics());
        return result.lowering().hasErrors() ? null : result;
    }

    private static List<SemanticOp> ofKind(LoweredModuleUnit unit, SemanticOpKind kind) {
        return unit.ops().stream().filter(op -> op.kind() == kind).toList();
    }

    private static Map<OpId, SemanticOp> lookup(LoweredModuleUnit unit) {
        Map<OpId, SemanticOp> ops = new LinkedHashMap<>();
        for (SemanticOp op : unit.ops()) ops.put(op.opId(), op);
        return ops;
    }

    private static void assertShape(LoweredModuleUnit unit, SemanticOp op,
                                    List<String> provided, List<String> boundaries) {
        var payload = (KindPayload.ClassNewPayload) op.payload();
        ClassLayout layout = unit.classLayouts().get(payload.classId());
        check(layout != null && layout.equals(payload.layout()), "exact declaring layout");
        check(payload.constructionProvider() == ConstructionProvider.LOCAL && payload.classFactoryRef() == null,
            "local construction has no foreign provider reference");
        check(payload.providedFields().stream().map(KindPayload.ProvidedField::name).toList()
            .equals(provided), "provided values retain source order");
        check(payload.fieldBoundaries().stream().map(KindPayload.FieldBoundary::field).toList()
            .equals(boundaries), "only provided fields have declaration-order boundaries");
        Map<OpId, SemanticOp> ops = lookup(unit);
        for (var entry : payload.fieldBoundaries()) {
            SemanticOp child = ops.get(entry.boundaryOpId());
            check(entry.kind() == BoundaryKind.CLASS_LITERAL_FIELD, "provided boundary kind");
            check(child != null && child.kind() == SemanticOpKind.BOUNDARY
                    && child.origin().kind() == SourceOriginKind.SYNTHETIC
                    && op.opId().equals(child.origin().parentOpId()), "synthetic child parent");
            if (child == null) continue;
            var boundary = (KindPayload.BoundaryPayload) child.payload();
            var field = layout.fields().stream().filter(f -> f.name().equals(entry.field()))
                .findFirst().orElseThrow();
            var value = payload.providedFields().stream()
                .filter(f -> f.name().equals(entry.field())).findFirst().orElseThrow();
            check(boundary.input().equals(value.valueOpId()), "boundary uses provided value id");
            check(boundary.descriptor().equals(field.descriptor()), "declared field descriptor");
            check(boundary.kind() == entry.kind()
                && boundary.realization() instanceof BoundaryRealization.RuntimeValidation,
                "runtime validation realization");
            check(child.failurePolicy() == FailurePolicyId.TYPE_DESCRIPTOR,
                "number/string descriptor failure policy");
        }
        check(op.failurePolicy() == FailurePolicyId.CLASS_CONSTRUCTION, "construction policy");
        check(op.result() instanceof ValueId && op.resultType() instanceof RuntimeDescriptor.Class c
            && c.classId().equals(payload.classId()), "fresh nominal result");
        check(op.operands().isEmpty(), "no pre-START operands");
        check(unit.ops().stream().noneMatch(child -> op.opId().equals(child.origin().parentOpId())
            && child.payload() instanceof KindPayload.BoundaryPayload p
            && p.kind() == BoundaryKind.FUNCTION_RETURN), "no spurious return boundaries");
    }

    private static void testFullLiteralPinnedShape() {
        var result = lowerModule("""
            class Point { x: number; tag: string; }
            let p: Point = {tag: "t", x: 3};
            """);
        if (result == null) return;
        var unit = result.lowering().unit();
        var constructions = ofKind(unit, SemanticOpKind.CLASS_NEW);
        check(constructions.size() == 1, "one full construction");
        if (constructions.size() != 1) return;
        var op = constructions.get(0);
        assertShape(unit, op, List.of("tag", "x"), List.of("x", "tag"));
        var payload = (KindPayload.ClassNewPayload) op.payload();
        check(payload.classId().equals(new ClassId("main", "Point")), "resolved class identity");
        for (var provided : payload.providedFields()) {
            var producer = unit.ops().stream().filter(p -> provided.valueOpId().equals(p.result()))
                .findFirst().orElseThrow();
            check(producer.payload() instanceof KindPayload.ConstPayload, "literal CONST producer");
            var scalar = ((KindPayload.ConstPayload) producer.payload()).value();
            check(provided.name().equals("tag")
                ? scalar instanceof ScalarValue.String s && s.value().equals("t")
                : scalar instanceof ScalarValue.Number n && n.value() == 3,
                "independent literal value expectation");
        }
    }

    private static void testPartialLiteralPresence() {
        var result = lowerModule("""
            class Point { x?: number; y: number; tag?: string; }
            let p: Point = {y: 20};
            """);
        if (result == null) return;
        var unit = result.lowering().unit();
        var constructions = ofKind(unit, SemanticOpKind.CLASS_NEW);
        check(constructions.size() == 1, "one partial construction");
        if (constructions.size() != 1) return;
        assertShape(unit, constructions.get(0), List.of("y"), List.of("y"));
        var payload = (KindPayload.ClassNewPayload) constructions.get(0).payload();
        check(payload.layout().fields().stream().map(ClassLayout.FieldLayout::required).toList()
            .equals(List.of(false, true, false)), "presence independent of values");
    }

    private static void testEmptyLiteralPresence() {
        var result = lowerModule("""
            class P { a?: number; b?: string; }
            let p: P = {};
            """);
        if (result == null) return;
        var unit = result.lowering().unit();
        var constructions = ofKind(unit, SemanticOpKind.CLASS_NEW);
        check(constructions.size() == 1, "one all-optional empty construction");
        if (constructions.size() == 1)
            assertShape(unit, constructions.get(0), List.of(), List.of());
    }

    private static void testProducedExecutorDrive() {
        var result = lowerModule("""
            export class Point { x: number; tag?: string; }
            let p: Point = {x: 42};
            """);
        if (result == null) return;
        var unit = result.lowering().unit();
        var constructions = ofKind(unit, SemanticOpKind.CLASS_NEW);
        var factories = ofKind(unit, SemanticOpKind.CLASS_FACTORY);
        check(constructions.size() == 1 && factories.size() == 1,
            "one construction and one exported provider registration");
        if (constructions.size() != 1 || factories.size() != 1) return;
        var op = constructions.get(0);
        var payload = (KindPayload.ClassNewPayload) op.payload();
        check(((KindPayload.ClassFactoryPayload) factories.get(0).payload()).classId()
            .equals(payload.classId()), "factory registration names exact class");
        assertShape(unit, op, List.of("x"), List.of("x"));
        var valueId = payload.providedFields().get(0).valueOpId();
        var checked = new ArrayList<ValueId>();
        var outcome = ClassOpsExecutor.executeClassNewLocal(op,
            Map.of(valueId, new ClassOpsExecutor.Value.Number(42)), lookup(unit),
            unit.classLayouts(), (boundary, input) -> {
                checked.add(boundary.input());
                return new ClassOpsExecutor.BoundaryResult.Pass(input);
            });
        check(checked.equals(List.of(valueId)),
            "executor drives exactly the produced boundary");
        check(outcome instanceof ClassOpsExecutor.Outcome.Success<ClassOpsExecutor.Value> success
            && success.value() instanceof ClassOpsExecutor.Value.Class instance
            && instance.classId().equals(payload.classId())
            && instance.fields().equals(List.of(
                new ClassOpsExecutor.FieldState.Present(new ClassOpsExecutor.Value.Number(42)),
                ClassOpsExecutor.FieldState.Missing.INSTANCE)),
            "executor publishes provided value and absent optional");
    }

    private static void testDeterminism() {
        String source = """
            export class Line { a: number; b?: string; }
            class Point { x: number; }
            let p: Point = {x: 2};
            let q: Point = {x: 9};
            let l: Line = {b: "t", a: 1};
            """;
        var first = lowerModule(source);
        var second = lowerModule(source);
        if (first == null || second == null) return;
        check(Arrays.equals(SemanticIrDumper.dumpModule(first.lowering().unit()),
            SemanticIrDumper.dumpModule(second.lowering().unit())), "byte-identical module dumps");
        check(first.lowering().unit().classLayouts().equals(second.lowering().unit().classLayouts()),
            "identical layouts");
        check(first.registry().factories().equals(second.registry().factories()),
            "identical provider registrations");
        var unit = first.lowering().unit();
        check(SemanticIrValidator.validate(unit, new SemanticIrValidator.ComparisonFacts(
            unit.interfaceHash(), unit.semanticProfile(), REGISTRY_HASH)).isEmpty(),
            "ordinary closed validator accepts construction-bearing unit");
    }

    public static void main(String[] args) {
        testFullLiteralPinnedShape();
        testPartialLiteralPresence();
        testEmptyLiteralPresence();
        testProducedExecutorDrive();
        testDeterminism();
        System.out.println("ClassNewLoweringTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) System.exit(1);
    }
}
