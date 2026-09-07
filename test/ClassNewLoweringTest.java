package deal.test;

import deal.ast.ProgramNode;
import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.NameResolver;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.lexer.Lexer;
import deal.parser.Parser;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CheckedModuleInput;
import deal.semantic.CheckedModuleKind;
import deal.semantic.CheckedProjectBuilder;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.LoweringSupport;
import deal.semantic.ModuleFact;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.BoundaryRealization;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ClassLayout;
import deal.semantic.ir.ClassOpsExecutor;
import deal.semantic.ir.ConstructKind;
import deal.semantic.ir.DefaultOwner;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.ScalarValue;
import deal.semantic.ir.SemanticIdAllocator;
import deal.semantic.ir.SemanticIrDumper;
import deal.semantic.ir.SemanticIrValidator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifies the ISSUE-0512 {@code CLASS_NEW} LOCAL lowering arm of
 * {@link SemanticLowerer} (class-construction-jsonable-operations K-D4;
 * parent D16): the class-typed object-literal arm's pinned LOCAL payload
 * shape and the executor resolution of the T1-emitted
 * {@code CLASS_DEFAULT} ops.
 *
 * <p>Pinned cases (the task verification):
 * <ol>
 *   <li>a full literal — {@code CLASS_NEW} with the checker-resolved
 *       classId, the declared layout, provided fields in literal source
 *       order, {@code defaultOwner LOCAL}, empty
 *       {@code classDefaultOpIds}, null {@code classFactoryRef},
 *       declaration-order {@code CLASS_LITERAL_FIELD} boundaries with
 *       the pinned kind/input/descriptor/policy/realization/
 *       {@code parentOpId}, policy {@code CLASS_CONSTRUCTION}, the
 *       {@code class:<ClassId>} result descriptor, and zero return
 *       boundaries;</li>
 *   <li>a partial literal — {@code classDefaultOpIds} names the omitted
 *       required-present defaulted fields' {@code CLASS_DEFAULT} op ids
 *       in declaration order only; {@code CLASS_DEFAULT_FIELD}
 *       boundaries wire the {@code CLASS_DEFAULT} op results; omitted
 *       optionals get no boundary and no default op id;</li>
 *   <li>an empty literal — every required-present defaulted field's
 *       default op id in declaration order and an all-{@code
 *       CLASS_DEFAULT_FIELD} boundary list;</li>
 *   <li>the combined T1+T2 scenario — a checked source declaring a
 *       defaulted class (T1's arm) plus a class literal (T2's arm)
 *       lowers to a validated unit whose {@code CLASS_NEW} references
 *       T1-emitted {@code CLASS_DEFAULT} op ids, ties to the exported
 *       class's {@code CLASS_FACTORY} list, and the {@link
 *       ClassOpsExecutor} resolves those ops from the produced unit's
 *       op list when executing the construction (this scenario fails if
 *       T1's layout/factory/default emission is broken);</li>
 *   <li>determinism — two repetitions produce byte-identical
 *       {@link SemanticIrDumper} dumps.</li>
 * </ol>
 */
public class ClassNewLoweringTest {

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

    // =========================================================================
    // Fixed invocation facts
    // =========================================================================

    private static final ModuleId MODULE = new ModuleId("main");
    private static final String SOURCE_ID = "test.deal";
    private static final String REGISTRY_HASH =
        CapabilityRegistry.releaseRegistry().capabilityRegistryHash();

    private record CheckedSlice(ProgramNode program, SymbolTable symbols, CheckResult checks) {
    }

    private record PipelineSlice(CheckedSlice slice, String interfaceHash,
                                 ExternalModuleInterface ownInterface,
                                 Map<ConstructKind, List<SemanticOpKind>> coverage) {
    }

    // =========================================================================
    // Whole-pipeline helpers (lexer/parser/checker -> lowerModuleClassCore)
    // =========================================================================

    private static CheckedSlice checkSlice(String source) {
        Lexer lexer = new Lexer(source, SOURCE_ID);
        deal.lexer.LexResult lex = lexer.tokenize();
        Parser parser = new Parser(lex.tokens(), SOURCE_ID);
        deal.parser.ParseResult parse = parser.parse();
        check(parse.diagnostics().isEmpty(), "the slice parses cleanly: " + parse.diagnostics());
        if (!parse.diagnostics().isEmpty()) {
            return null;
        }
        NameResolver resolver = new NameResolver(MODULE.path(), (ModuleResolver) null);
        SymbolTable symbols = resolver.resolve(parse.program());
        check(resolver.diagnostics().isEmpty(),
            "the slice resolves cleanly: " + resolver.diagnostics());
        if (!resolver.diagnostics().isEmpty()) {
            return null;
        }
        CheckResult checks = TypeChecker.check(MODULE.path(), symbols, resolver, parse.program());
        check(checks.diagnostics().isEmpty(), "the slice checks cleanly: " + checks.diagnostics());
        if (checks.hasErrors()) {
            return null;
        }
        return new CheckedSlice(parse.program(), symbols, checks);
    }

    /** The foundation detector's project facts of the slice (index + coverage). */
    private static PipelineSlice pipeline(CheckedSlice slice) {
        CompilerInvocation invocation = CompilerProfileProvider.resolveCommonShadow(
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.V1_2_ACTIVE,
            CapabilityRegistry.releaseRegistry());
        ModuleFact fact = new ModuleFact(SOURCE_ID, MODULE, false, false, slice.program(),
            Map.of(), slice.symbols(), slice.checks(), List.of());
        deal.semantic.CheckedProjectBuildResult built = CheckedProjectBuilder.build(invocation,
            MODULE, List.of(fact));
        check(built != null && !built.hasErrors() && built.input() != null
                && built.index() != null,
            "the checked project builds cleanly: "
                + (built == null ? "null" : built.diagnostics()));
        if (built == null || built.hasErrors() || built.input() == null
                || built.index() == null) {
            return null;
        }
        RequirementManifestResult manifests = LoweringSupport.computeManifests(invocation,
            built.input(), built.index());
        check(manifests != null && manifests.diagnostics().isEmpty()
                && manifests.manifests() != null && manifests.manifests().size() == 1,
            "the foundation detector produces exactly one requirement manifest: "
                + (manifests == null ? "null" : manifests.diagnostics()));
        if (manifests == null || !manifests.diagnostics().isEmpty()
                || manifests.manifests() == null || manifests.manifests().size() != 1) {
            return null;
        }
        Map<ConstructKind, List<SemanticOpKind>> coverage =
            new LinkedHashMap<>(manifests.manifests().get(0).constructCoverage());
        coverage.remove(ConstructKind.IMPORT_EXPORT_ENTRY);
        ExternalModuleInterface own = built.index().modules().get(MODULE);
        check(own != null, "the index carries the module's own interface entry");
        return new PipelineSlice(slice, built.index().interfaceIndexDigest(), own, coverage);
    }

    private static CheckedModuleInput moduleOf(CheckedSlice slice) {
        return new CheckedModuleInput(MODULE, SOURCE_ID, Path.of(SOURCE_ID), slice.program(),
            slice.checks(), List.of(), List.of(), CheckedModuleKind.IMPLEMENTATION);
    }

    /** The whole-pipeline driver: lexer/parser/checker &#8594; lowerModuleClassCore. */
    private static SemanticLowerer.ClassDeclarationCoreResult lowerModule(String source) {
        CheckedSlice slice = checkSlice(source);
        if (slice == null) {
            return null;
        }
        PipelineSlice pipeline = pipeline(slice);
        if (pipeline == null) {
            return null;
        }
        return SemanticLowerer.lowerModuleClassCore(moduleOf(slice),
            SemanticProfile.DEAL_V1_2_INT32, pipeline.coverage(), pipeline.interfaceHash(),
            REGISTRY_HASH, pipeline.ownInterface(),
            SemanticIdAllocator.over(List.of(MODULE)));
    }

    private static List<SemanticOp> ofKind(List<SemanticOp> ops, SemanticOpKind kind) {
        List<SemanticOp> matches = new ArrayList<>();
        for (SemanticOp op : ops) {
            if (op.kind() == kind) {
                matches.add(op);
            }
        }
        return matches;
    }

    private static KindPayload.ClassNewPayload classNewPayload(SemanticOp op) {
        return (KindPayload.ClassNewPayload) op.payload();
    }

    private static KindPayload.ClassDefaultPayload defaultPayload(SemanticOp op) {
        return (KindPayload.ClassDefaultPayload) op.payload();
    }

    private static KindPayload.ClassFactoryPayload factoryPayload(SemanticOp op) {
        return (KindPayload.ClassFactoryPayload) op.payload();
    }

    private static SemanticOp opById(LoweredModuleUnit unit, OpId opId) {
        for (SemanticOp op : unit.ops()) {
            if (op.opId().equals(opId)) {
                return op;
            }
        }
        return null;
    }

    /** The CONST op publishing {@code result} (the provided value's producing op). */
    private static SemanticOp constPublishing(LoweredModuleUnit unit, ValueId result) {
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.CONST && result.equals(op.result())) {
                return op;
            }
        }
        return null;
    }

    // =========================================================================
    // (a) full literal: the pinned LOCAL payload shape
    // =========================================================================

    private static void testFullLiteralPinnedShape() {
        System.out.println("-- full literal: CLASS_NEW with the pinned LOCAL shape "
            + "(literal-order provided fields, declaration-order boundaries, zero "
            + "return boundaries) --");

        SemanticLowerer.ClassDeclarationCoreResult result = lowerModule("""
            class Point { x: int; tag: string; }
            let p: Point = {tag: "t", x: 3}
            """);
        check(result != null && result.lowering() != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null,
            "the full-literal slice lowers to a validated unit: "
                + (result == null || result.lowering() == null ? "null"
                    : result.lowering().diagnostics()));
        if (result == null || result.lowering() == null
                || result.lowering().hasErrors() || result.lowering().unit() == null) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        List<SemanticOp> classNews = ofKind(unit.ops(), SemanticOpKind.CLASS_NEW);
        check(classNews.size() == 1, "exactly one CLASS_NEW op; got " + classNews.size());
        if (classNews.size() != 1) {
            return;
        }
        SemanticOp op = classNews.get(0);
        ClassId pointId = new ClassId("main", "Point");
        KindPayload.ClassNewPayload payload = classNewPayload(op);

        check(payload.classId().equals(pointId),
            "the payload carries the checker-resolved classId; got " + payload.classId());
        ClassLayout layout = unit.classLayouts().get(pointId);
        check(layout != null && payload.layout().equals(layout),
            "the payload carries the declared layout (the unit's classLayouts record)");
        check(payload.defaultOwner() == DefaultOwner.LOCAL,
            "defaultOwner is LOCAL; got " + payload.defaultOwner());
        check(payload.classDefaultOpIds().isEmpty(),
            "the full literal names no CLASS_DEFAULT op ids; got " + payload.classDefaultOpIds());
        check(payload.classFactoryRef() == null,
            "the LOCAL shape carries classFactoryRef null");

        // Provided fields in literal source order with the producing op
        // results as value op ids.
        check(payload.providedFields().size() == 2,
            "two provided fields; got " + payload.providedFields().size());
        if (payload.providedFields().size() == 2) {
            KindPayload.ProvidedField tag = payload.providedFields().get(0);
            KindPayload.ProvidedField x = payload.providedFields().get(1);
            check(tag.name().equals("tag") && x.name().equals("x"),
                "provided fields record the literal source order (tag, x); got "
                    + payload.providedFields());
            SemanticOp tagConst = constPublishing(unit, tag.valueOpId());
            SemanticOp xConst = constPublishing(unit, x.valueOpId());
            check(tagConst != null
                    && tagConst.payload() instanceof KindPayload.ConstPayload tagPayload
                    && tagPayload.value() instanceof ScalarValue.String tagValue
                    && tagValue.value().equals("t"),
                "the tag provided value op is the 't' CONST producing op");
            check(xConst != null
                    && xConst.payload() instanceof KindPayload.ConstPayload xPayload
                    && xPayload.value() instanceof ScalarValue.Int xValue
                    && xValue.value() == 3,
                "the x provided value op is the 3 CONST producing op");
        }

        // Field boundaries in declaration order with the pinned children.
        check(payload.fieldBoundaries().size() == 2,
            "two field boundaries; got " + payload.fieldBoundaries().size());
        if (payload.fieldBoundaries().size() == 2) {
            KindPayload.FieldBoundary x = payload.fieldBoundaries().get(0);
            KindPayload.FieldBoundary tag = payload.fieldBoundaries().get(1);
            check(x.field().equals("x") && x.kind() == BoundaryKind.CLASS_LITERAL_FIELD
                    && tag.field().equals("tag")
                    && tag.kind() == BoundaryKind.CLASS_LITERAL_FIELD,
                "field boundaries are in declaration order (x, tag) with "
                    + "CLASS_LITERAL_FIELD kinds; got " + payload.fieldBoundaries());
            for (KindPayload.FieldBoundary entry : payload.fieldBoundaries()) {
                SemanticOp child = opById(unit, entry.boundaryOpId());
                check(child != null && child.kind() == SemanticOpKind.BOUNDARY
                        && op.opId().equals(child.origin().parentOpId()),
                    "the boundary child resolves to a BOUNDARY op parented to the "
                        + "CLASS_NEW op");
                if (child == null || child.kind() != SemanticOpKind.BOUNDARY
                        || !op.opId().equals(child.origin().parentOpId())) {
                    continue;
                }
                KindPayload.BoundaryPayload boundary =
                    (KindPayload.BoundaryPayload) child.payload();
                check(boundary.kind() == entry.kind(),
                    "the child's boundary kind matches the payload entry");
                RuntimeDescriptor expected = entry.field().equals("x")
                    ? RuntimeDescriptor.Int.INSTANCE : RuntimeDescriptor.String.INSTANCE;
                check(boundary.descriptor().equals(expected),
                    "the child carries the field's declared descriptor; got "
                        + boundary.descriptor().canonicalSpecText());
                check(boundary.input().equals(providedIdOf(payload, entry.field())),
                    "the CLASS_LITERAL_FIELD input is the field's provided value op id");
                check(child.failurePolicy() == FailurePolicyId.TYPE_DESCRIPTOR,
                    "the descriptor-kind policy (TYPE_DESCRIPTOR for int/string fields); "
                        + "got " + child.failurePolicy());
                check(boundary.realization()
                        instanceof BoundaryRealization.RuntimeValidation,
                    "the child realization is RuntimeValidation");
                check(child.origin().kind() == SourceOriginKind.SYNTHETIC,
                    "the boundary child origin is SYNTHETIC");
            }
        }

        // The op header.
        check(op.failurePolicy() == FailurePolicyId.CLASS_CONSTRUCTION,
            "the CLASS_NEW policy is CLASS_CONSTRUCTION; got " + op.failurePolicy());
        check(op.result() instanceof ValueId,
            "the op publishes a fresh-instance ValueId");
        check(op.resultType() instanceof RuntimeDescriptor.Class classDescriptor
                && classDescriptor.classId().equals(pointId),
            "the result type is the class:<ClassId> descriptor; got " + op.resultType());
        check(op.operands().isEmpty(), "CLASS_NEW carries no pre-START operands");

        // Zero return boundaries: no FUNCTION_RETURN boundary child exists
        // anywhere under the CLASS_NEW op.
        boolean returnBoundary = false;
        for (SemanticOp unitOp : unit.ops()) {
            if (unitOp.kind() == SemanticOpKind.BOUNDARY
                    && op.opId().equals(unitOp.origin().parentOpId())
                    && unitOp.payload() instanceof KindPayload.BoundaryPayload boundary
                    && boundary.kind() == BoundaryKind.FUNCTION_RETURN) {
                returnBoundary = true;
            }
        }
        check(!returnBoundary, "zero return boundaries: no FUNCTION_RETURN child exists "
            + "under the CLASS_NEW op");
    }

    private static ValueId providedIdOf(KindPayload.ClassNewPayload payload, String name) {
        for (KindPayload.ProvidedField field : payload.providedFields()) {
            if (field.name().equals(name)) {
                return field.valueOpId();
            }
        }
        return null;
    }

    // =========================================================================
    // (b) partial literal: omitted-required defaults only
    // =========================================================================

    private static void testPartialLiteralDefaults() {
        System.out.println("-- partial literal: classDefaultOpIds for omitted required "
            + "defaulted fields only; CLASS_DEFAULT_FIELD wiring --");

        SemanticLowerer.ClassDeclarationCoreResult result = lowerModule("""
            class Point { x: int = 1; y: int = 2; tag?: string; }
            let p: Point = {y: 20}
            """);
        check(result != null && result.lowering() != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null,
            "the partial-literal slice lowers to a validated unit: "
                + (result == null || result.lowering() == null ? "null"
                    : result.lowering().diagnostics()));
        if (result == null || result.lowering() == null
                || result.lowering().hasErrors() || result.lowering().unit() == null) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        List<SemanticOp> classNews = ofKind(unit.ops(), SemanticOpKind.CLASS_NEW);
        check(classNews.size() == 1, "exactly one CLASS_NEW op; got " + classNews.size());
        if (classNews.size() != 1) {
            return;
        }
        KindPayload.ClassNewPayload payload = classNewPayload(classNews.get(0));
        ClassId pointId = new ClassId("main", "Point");

        check(payload.providedFields().size() == 1
                && payload.providedFields().get(0).name().equals("y"),
            "the only provided field is y in literal order; got "
                + payload.providedFields());

        // classDefaultOpIds: the omitted required-present defaulted field
        // (x) only, in declaration order — y is provided, tag is optional
        // and omitted, so neither contributes an op id.
        check(payload.classDefaultOpIds().size() == 1,
            "exactly one CLASS_DEFAULT op id (the omitted required defaulted x); got "
                + payload.classDefaultOpIds());
        if (payload.classDefaultOpIds().size() == 1) {
            SemanticOp defX = opById(unit, payload.classDefaultOpIds().get(0));
            check(defX != null && defX.kind() == SemanticOpKind.CLASS_DEFAULT
                    && defaultPayload(defX).classId().equals(pointId)
                    && defaultPayload(defX).field().equals("x"),
                "the op id resolves to the T1-emitted CLASS_DEFAULT op of field x");
            check(defX != null && defX.failurePolicy() == FailurePolicyId.NO_DEAL_FAILURE,
                "the CLASS_DEFAULT op carries NO_DEAL_FAILURE");
        }

        // Field boundaries in declaration order: x CLASS_DEFAULT_FIELD, y
        // CLASS_LITERAL_FIELD; the omitted optional tag gets no boundary.
        check(payload.fieldBoundaries().size() == 2,
            "two field boundaries; got " + payload.fieldBoundaries().size());
        if (payload.fieldBoundaries().size() == 2
                && payload.classDefaultOpIds().size() == 1) {
            KindPayload.FieldBoundary x = payload.fieldBoundaries().get(0);
            KindPayload.FieldBoundary y = payload.fieldBoundaries().get(1);
            check(x.field().equals("x") && x.kind() == BoundaryKind.CLASS_DEFAULT_FIELD
                    && y.field().equals("y") && y.kind() == BoundaryKind.CLASS_LITERAL_FIELD,
                "the boundaries are declaration order (x defaulted, y provided); got "
                    + payload.fieldBoundaries());
            SemanticOp xChild = opById(unit, x.boundaryOpId());
            check(xChild != null && xChild.kind() == SemanticOpKind.BOUNDARY
                    && classNews.get(0).opId().equals(xChild.origin().parentOpId())
                    && xChild.payload() instanceof KindPayload.BoundaryPayload xBoundary
                    && xBoundary.input().equals(
                        opById(unit, payload.classDefaultOpIds().get(0)).result()),
                "the CLASS_DEFAULT_FIELD boundary input is the field's CLASS_DEFAULT "
                    + "op result ValueId");
            SemanticOp yChild = opById(unit, y.boundaryOpId());
            check(yChild != null && yChild.kind() == SemanticOpKind.BOUNDARY
                    && classNews.get(0).opId().equals(yChild.origin().parentOpId())
                    && yChild.payload() instanceof KindPayload.BoundaryPayload yBoundary
                    && yBoundary.input().equals(providedIdOf(payload, "y")),
                "the CLASS_LITERAL_FIELD boundary input is the provided y value op id");
        }
        check(payload.defaultOwner() == DefaultOwner.LOCAL && payload.classFactoryRef() == null,
            "the LOCAL shape is preserved on the partial literal");
        check(classNews.get(0).failurePolicy() == FailurePolicyId.CLASS_CONSTRUCTION,
            "the policy is CLASS_CONSTRUCTION");
    }

    // =========================================================================
    // (c) empty literal: all required defaulted fields
    // =========================================================================

    private static void testEmptyLiteralDefaults() {
        System.out.println("-- empty literal: every required-present defaulted field's "
            + "default op id in declaration order --");

        SemanticLowerer.ClassDeclarationCoreResult result = lowerModule("""
            class P { a: int = 5; b: string = "b"; }
            let p: P = {}
            """);
        check(result != null && result.lowering() != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null,
            "the empty-literal slice lowers to a validated unit: "
                + (result == null || result.lowering() == null ? "null"
                    : result.lowering().diagnostics()));
        if (result == null || result.lowering() == null
                || result.lowering().hasErrors() || result.lowering().unit() == null) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        List<SemanticOp> classNews = ofKind(unit.ops(), SemanticOpKind.CLASS_NEW);
        check(classNews.size() == 1, "exactly one CLASS_NEW op; got " + classNews.size());
        if (classNews.size() != 1) {
            return;
        }
        KindPayload.ClassNewPayload payload = classNewPayload(classNews.get(0));

        check(payload.providedFields().isEmpty(),
            "the empty literal provides no fields; got " + payload.providedFields());
        check(payload.classDefaultOpIds().size() == 2,
            "both required-present defaulted fields' op ids are listed; got "
                + payload.classDefaultOpIds());
        if (payload.classDefaultOpIds().size() == 2) {
            SemanticOp defA = opById(unit, payload.classDefaultOpIds().get(0));
            SemanticOp defB = opById(unit, payload.classDefaultOpIds().get(1));
            check(defA != null && defB != null
                    && defA.kind() == SemanticOpKind.CLASS_DEFAULT
                    && defB.kind() == SemanticOpKind.CLASS_DEFAULT
                    && defaultPayload(defA).field().equals("a")
                    && defaultPayload(defB).field().equals("b"),
                "the op ids resolve to the CLASS_DEFAULT ops in declaration order "
                    + "(a, b)");
        }
        check(payload.fieldBoundaries().size() == 2
                && payload.fieldBoundaries().get(0).field().equals("a")
                && payload.fieldBoundaries().get(0).kind() == BoundaryKind.CLASS_DEFAULT_FIELD
                && payload.fieldBoundaries().get(1).field().equals("b")
                && payload.fieldBoundaries().get(1).kind() == BoundaryKind.CLASS_DEFAULT_FIELD,
            "the boundaries are all-CLASS_DEFAULT_FIELD in declaration order; got "
                + payload.fieldBoundaries());
        if (payload.fieldBoundaries().size() == 2
                && payload.classDefaultOpIds().size() == 2) {
            for (int i = 0; i < 2; i++) {
                SemanticOp child = opById(unit, payload.fieldBoundaries().get(i).boundaryOpId());
                check(child != null && child.payload() instanceof KindPayload.BoundaryPayload
                            boundary
                        && boundary.input().equals(
                            opById(unit, payload.classDefaultOpIds().get(i)).result()),
                    "boundary " + i + " wires the CLASS_DEFAULT op result");
            }
        }
    }

    // =========================================================================
    // (d) combined T1+T2 scenario: the executor resolves the produced unit
    // =========================================================================

    private static void testCombinedT1T2ExecutorDrive() {
        System.out.println("-- combined T1+T2: the CLASS_NEW references T1-emitted "
            + "CLASS_DEFAULT ops and the executor resolves them from the produced "
            + "unit's op list --");

        SemanticLowerer.ClassDeclarationCoreResult result = lowerModule("""
            export class Point { x: int = 40 + 2; tag?: string; }
            let p: Point = {}
            """);
        check(result != null && result.lowering() != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null,
            "the combined slice lowers to a validated unit: "
                + (result == null || result.lowering() == null ? "null"
                    : result.lowering().diagnostics()));
        if (result == null || result.lowering() == null
                || result.lowering().hasErrors() || result.lowering().unit() == null) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        StructuredBodyTable table = result.lowering().table();
        ClassId pointId = new ClassId("main", "Point");

        List<SemanticOp> classNews = ofKind(unit.ops(), SemanticOpKind.CLASS_NEW);
        check(classNews.size() == 1, "one CLASS_NEW op; got " + classNews.size());
        List<SemanticOp> factories = ofKind(unit.ops(), SemanticOpKind.CLASS_FACTORY);
        check(factories.size() == 1, "one CLASS_FACTORY op (the exported class); got "
            + factories.size());
        if (classNews.size() != 1 || factories.size() != 1) {
            return;
        }
        SemanticOp op = classNews.get(0);
        KindPayload.ClassNewPayload payload = classNewPayload(op);

        // The T1 tie: the literal's default application names exactly the
        // same CLASS_DEFAULT op ids as the exported class's factory (both
        // in declaration order) — if T1's emission is broken, either list
        // fails to resolve here.
        check(payload.classDefaultOpIds().size() == 1,
            "the literal names one CLASS_DEFAULT op id (the omitted required x); got "
                + payload.classDefaultOpIds());
        check(payload.classDefaultOpIds()
                .equals(factoryPayload(factories.get(0)).classDefaultOpIds()),
            "the CLASS_NEW's classDefaultOpIds equal the factory's list (the T1 "
                + "emission tie); got " + payload.classDefaultOpIds() + " vs "
                + factoryPayload(factories.get(0)).classDefaultOpIds());

        // The executor drive: resolve every referenced op from the
        // produced unit's op list (an unresolvable id fails the executor
        // fail-closed — this scenario fails if T1 is broken).
        Map<OpId, SemanticOp> opLookup = new LinkedHashMap<>();
        for (SemanticOp unitOp : unit.ops()) {
            opLookup.put(unitOp.opId(), unitOp);
        }
        Map<OpId, SemanticOp> defaultOps = new LinkedHashMap<>();
        for (OpId id : payload.classDefaultOpIds()) {
            SemanticOp def = opLookup.get(id);
            check(def != null && def.kind() == SemanticOpKind.CLASS_DEFAULT
                    && defaultPayload(def).classId().equals(pointId)
                    && defaultPayload(def).field().equals("x"),
                "the CLASS_DEFAULT op id resolves in the produced unit's op list");
            if (def != null) {
                defaultOps.put(id, def);
            }
        }
        Map<OpId, SemanticOp> boundaryOps = new LinkedHashMap<>();
        boolean allBoundariesResolved = true;
        for (KindPayload.FieldBoundary entry : payload.fieldBoundaries()) {
            SemanticOp child = opLookup.get(entry.boundaryOpId());
            check(child != null && child.kind() == SemanticOpKind.BOUNDARY
                    && op.opId().equals(child.origin().parentOpId()),
                "the field boundary " + entry.boundaryOpId() + " resolves in the "
                    + "produced unit's op list and parents to the CLASS_NEW op");
            if (child == null) {
                allBoundariesResolved = false;
            } else {
                boundaryOps.put(entry.boundaryOpId(), child);
            }
        }
        // The T1 default block of x: CONST 40, CONST 2, BINARY INT32_ADD
        // (proving the layout/default emission, not a hollow op list).
        if (!payload.classDefaultOpIds().isEmpty()) {
            SemanticOp def = opLookup.get(payload.classDefaultOpIds().get(0));
            if (def != null) {
                List<OpId> block = table.blockOps().get(defaultPayload(def).defaultBlock());
                boolean addOp = false;
                if (block != null) {
                    for (OpId listed : block) {
                        SemanticOp blockOp = opLookup.get(listed);
                        if (blockOp != null && blockOp.kind() == SemanticOpKind.BINARY) {
                            addOp = true;
                        }
                    }
                }
                check(addOp, "the x default block carries the T1-emitted BINARY (40 + 2)");
            }
        }

        if (!payload.classDefaultOpIds().isEmpty() && defaultOps.size() == 1
                && allBoundariesResolved) {
            final SemanticOp producedDefault = defaultOps.values().iterator().next();
            final ValueId defaultResult = producedDefault.result() instanceof ValueId id
                ? id : null;
            check(defaultResult != null, "the CLASS_DEFAULT op publishes a ValueId result");
            // The body-runner resolves the default op's result ValueId
            // from the produced unit (the op is the exact unit op) and
            // scripts the evaluated 40 + 2 default.
            ClassOpsExecutor.BodyRunner bodyRunner = defaultOp -> {
                check(defaultOp.opId().equals(producedDefault.opId()),
                    "the executor drives exactly the produced unit's CLASS_DEFAULT op");
                return new ClassOpsExecutor.Value.Int(42);
            };
            ClassOpsExecutor.BoundaryCheckRunner pass = (boundary, input) ->
                new ClassOpsExecutor.BoundaryResult.Pass(input);

            ClassOpsExecutor.Outcome<ClassOpsExecutor.Value> outcome =
                ClassOpsExecutor.executeClassNewLocal(op, Map.of(), defaultOps, boundaryOps,
                    unit.classLayouts(), pass, bodyRunner);
            check(outcome instanceof ClassOpsExecutor.Outcome.Success<ClassOpsExecutor.Value>
                    success
                    && success.value() instanceof ClassOpsExecutor.Value.Class instance
                    && instance.classId().equals(pointId)
                    && instance.fields().size() == 2
                    && instance.fields().get(0)
                        instanceof ClassOpsExecutor.FieldState.Present present
                    && present.value().equals(new ClassOpsExecutor.Value.Int(42))
                    && instance.fields().get(1)
                        == ClassOpsExecutor.FieldState.Missing.INSTANCE,
                "the executor publishes the tagged instance with the default-filled x and "
                    + "the omitted optional tag missing");
        }
    }

    // =========================================================================
    // (e) determinism
    // =========================================================================

    private static void testDeterminism() {
        System.out.println("-- determinism: two repetitions are byte-identical --");

        String source = """
            export class Line { a: int = 1; b?: string; }
            class Point { x: int = 2; }
            let p: Point = {}
            let q: Point = {x: 9}
            let l: Line = {b: "t"}
            """;
        SemanticLowerer.ClassDeclarationCoreResult first = lowerModule(source);
        SemanticLowerer.ClassDeclarationCoreResult second = lowerModule(source);
        check(first != null && first.lowering() != null && !first.lowering().hasErrors()
                && second != null && second.lowering() != null
                && !second.lowering().hasErrors(),
            "both repetitions lower");
        if (first == null || first.lowering() == null || first.lowering().hasErrors()
                || second == null || second.lowering() == null
                || second.lowering().hasErrors()) {
            return;
        }
        byte[] firstDump = SemanticIrDumper.dumpModule(first.lowering().unit());
        byte[] secondDump = SemanticIrDumper.dumpModule(second.lowering().unit());
        check(java.util.Arrays.equals(firstDump, secondDump),
            "the two repetitions produce byte-identical SemanticIrDumper dumps");
        check(first.lowering().unit().classLayouts()
                .equals(second.lowering().unit().classLayouts()),
            "the two repetitions produce identical class layouts");
        check(first.registry().factories().equals(second.registry().factories()),
            "the two repetitions produce identical factory registries");
        // Validator passage of a literal-bearing unit.
        check(SemanticIrValidator.validate(first.lowering().unit(),
                new SemanticIrValidator.ComparisonFacts(first.lowering().unit().interfaceHash(),
                    SemanticProfile.DEAL_V1_2_INT32, REGISTRY_HASH)).isEmpty(),
            "the literal-bearing unit passes the closed SemanticIrValidator");
    }

    // =========================================================================
    // Runner
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Class New Lowering Tests (ISSUE-0512 LOCAL arm) ===\n");

        testFullLiteralPinnedShape();
        testPartialLiteralDefaults();
        testEmptyLiteralDefaults();
        testCombinedT1T2ExecutorDrive();
        testDeterminism();

        System.out.println("\nClassNewLoweringTest: " + passed + " passed, "
            + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
