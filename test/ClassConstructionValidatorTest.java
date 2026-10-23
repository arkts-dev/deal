package deal.test;

import deal.ast.ProgramNode;
import deal.checker.CheckResult;
import deal.checker.NameResolver;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.lexer.Lexer;
import deal.parser.Parser;
import deal.semantic.CanonicalTypeText;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CheckedModuleInput;
import deal.semantic.CheckedModuleKind;
import deal.semantic.CheckedProjectBuilder;
import deal.semantic.ClassConstructionValidator;
import deal.semantic.ClassRegistrationSeeds;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.DescriptorService;
import deal.semantic.LoweringSupport;
import deal.semantic.ModuleFact;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.ir.AdaptSourceRef;
import deal.semantic.ir.AnchorId;
import deal.semantic.ir.BindingGeneration;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.ClassFactoryId;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ClassInterface;
import deal.semantic.ir.ClassLayout;
import deal.semantic.ir.CaptureMode;
import deal.semantic.ir.ClassBindingFacts;
import deal.semantic.ir.ClosedSelector;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.ConstructKind;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.ConstructionProvider;
import deal.semantic.ir.ExportInterface;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.ExternalModuleKind;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FieldInterface;
import deal.semantic.ir.InitializationMode;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.LoweringContextHash;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ModuleInitPlan;
import deal.semantic.ir.OpId;
import deal.semantic.ir.OpResultType;
import deal.semantic.ir.OperationContractSnapshot;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.RawOp;
import deal.semantic.ir.RawOrigin;
import deal.semantic.ir.RawUnit;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.ResolvedImport;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticIdAllocator;
import deal.semantic.ir.SemanticIrDumper;
import deal.semantic.ir.SemanticIrValidator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.SemanticValue;
import deal.semantic.ir.SharedFactoryFacts;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;
import deal.types.Type;
import deal.types.Types;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class ClassConstructionValidatorTest {

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

    private static void expectNpe(Runnable runnable, String what) {
        try {
            runnable.run();
            fail("expected NullPointerException for " + what + ", but no exception was raised");
        } catch (NullPointerException expected) {
            passed++;
        } catch (Throwable other) {
            fail("expected NullPointerException for " + what + ", got "
                + other.getClass().getSimpleName() + ": " + other.getMessage());
        }
    }

    // =========================================================================
    // Fixed invocation facts (the ClassDeclarationLoweringTest discipline)
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

    private record LoweredSlice(SemanticLowerer.ClassDeclarationCoreResult result,
                                ExternalModuleInterface ownInterface) {
    }

    private static CheckedSlice checkSlice(String source) {
        Lexer lexer = new Lexer(source, SOURCE_ID);
        deal.lexer.LexResult lex = lexer.tokenize();
        Parser parser = new Parser(lex.tokens(), SOURCE_ID, lex.directiveEvents());
        deal.parser.ParseResult parse = parser.parse();
        check(parse.diagnostics().isEmpty(), "the slice parses cleanly: " + parse.diagnostics());
        if (!parse.diagnostics().isEmpty()) {
            return null;
        }
        NameResolver resolver = new NameResolver(MODULE.path(), (deal.checker.ModuleResolver) null);
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

    private static PipelineSlice pipeline(CheckedSlice slice) {
        CompilerInvocation invocation = CompilerProfileProvider.resolve(ReleaseState.V1_3_ACTIVE,
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

    /** The whole-pipeline driver: lexer/parser/checker → lowerModuleClassCore. */
    private static LoweredSlice lowerModule(String source) {
        CheckedSlice slice = checkSlice(source);
        if (slice == null) {
            return null;
        }
        PipelineSlice pipeline = pipeline(slice);
        if (pipeline == null) {
            return null;
        }
        return new LoweredSlice(SemanticLowerer.lowerModuleClassCore(moduleOf(slice),
            SemanticProfile.DEAL_V1_3_INT32, pipeline.coverage(), pipeline.interfaceHash(),
            REGISTRY_HASH, pipeline.ownInterface(),
            SemanticIdAllocator.over(List.of(MODULE))), pipeline.ownInterface());
    }

    // =========================================================================
    // Mutation helpers (rebuild one op with a fresh wired digest)
    // =========================================================================

    private static OperationContractSnapshot contractFor(SemanticOp op, KindPayload payload,
                                                         String digest) {
        ClosedSelector selector = payload instanceof KindPayload.SelectorCarrying carrying
            ? carrying.selector() : null;
        return new OperationContractSnapshot(OperationContractSnapshot.VERSION, op.kind(),
            op.resultType(), op.operandTypes(), selector, payload, op.failurePolicy(),
            List.of(), digest);
    }

    private static SemanticOp rebuild(SemanticOp op, KindPayload payload) {
        String digest = ContractSnapshotCanonicalizer.digest(contractFor(op, payload, "placeholder"));
        return new SemanticOp(op.opId(), op.kind(), op.origin(), op.result(), op.resultType(),
            op.operands(), op.operandTypes(), payload, op.failurePolicy(),
            contractFor(op, payload, digest));
    }

    /** Rebuild one op with a different published result type and a wired digest. */
    private static SemanticOp rebuildWithResultType(SemanticOp op, OpResultType resultType) {
        ClosedSelector selector = op.payload() instanceof KindPayload.SelectorCarrying carrying
            ? carrying.selector() : null;
        OperationContractSnapshot contract = new OperationContractSnapshot(
            OperationContractSnapshot.VERSION, op.kind(), resultType, op.operandTypes(),
            selector, op.payload(), op.failurePolicy(), List.of(), "placeholder");
        String digest = ContractSnapshotCanonicalizer.digest(contract);
        OperationContractSnapshot wired = new OperationContractSnapshot(
            OperationContractSnapshot.VERSION, op.kind(), resultType, op.operandTypes(),
            selector, op.payload(), op.failurePolicy(), List.of(), digest);
        return new SemanticOp(op.opId(), op.kind(), op.origin(), op.result(), resultType,
            op.operands(), op.operandTypes(), op.payload(), op.failurePolicy(), wired);
    }

    private static SemanticOp withParent(SemanticOp op, OpId parent) {
        SourceOrigin origin = new SourceOrigin(op.origin().sourceId(), op.origin().span(),
            op.origin().kind(), op.origin().anchorId(), parent);
        return new SemanticOp(op.opId(), op.kind(), origin, op.result(), op.resultType(),
            op.operands(), op.operandTypes(), op.payload(), op.failurePolicy(),
            op.contract());
    }

    private static LoweredModuleUnit unitWithOps(LoweredModuleUnit unit, List<SemanticOp> ops) {
        return new LoweredModuleUnit(unit.formatVersion(), unit.semanticProfile(),
            unit.moduleId(), unit.interfaceHash(), unit.loweringContextHash(),
            unit.requiredCapabilities(), unit.constructCoverage(), unit.classLayouts(),
            unit.functions(), unit.moduleInit(), unit.exportPlan(), unit.functionBindings(),
            ops, unit.bodyTable(), unit.classFactories(), unit.classBindingFacts());
    }

    private static LoweredModuleUnit unitWithLayouts(LoweredModuleUnit unit,
                                                     Map<ClassId, ClassLayout> layouts) {
        return new LoweredModuleUnit(unit.formatVersion(), unit.semanticProfile(),
            unit.moduleId(), unit.interfaceHash(), unit.loweringContextHash(),
            unit.requiredCapabilities(), unit.constructCoverage(), layouts,
            unit.functions(), unit.moduleInit(), unit.exportPlan(), unit.functionBindings(),
            unit.ops(), unit.bodyTable(), unit.classFactories(), unit.classBindingFacts());
    }

    private static List<SemanticOp> ofKind(LoweredModuleUnit unit, SemanticOpKind kind) {
        List<SemanticOp> matches = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == kind) {
                matches.add(op);
            }
        }
        return matches;
    }

    private static SemanticOp firstOfKind(LoweredModuleUnit unit, SemanticOpKind kind) {
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == kind) {
                return op;
            }
        }
        return null;
    }

    private static List<SemanticOp> childrenOf(LoweredModuleUnit unit, SemanticOp owner) {
        List<SemanticOp> children = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.BOUNDARY
                    && owner.opId().equals(op.origin().parentOpId())) {
                children.add(op);
            }
        }
        return children;
    }

    /** Asserts one E6005 with the exact pinned detail fields. */
    private static void expectE6005(Optional<CompilerDiagnostic> outcome, String rule) {
        check(outcome.isPresent(), "the tampered unit fails with E6005 (" + rule + ")");
        if (outcome.isEmpty()) {
            return;
        }
        CompilerDiagnostic diagnostic = outcome.get();
        check("E6005".equals(diagnostic.code()), "the failure code is E6005; got "
            + diagnostic.code());
        check(diagnostic.message().contains("validatorRule " + rule),
            "the failure names validatorRule " + rule + "; got " + diagnostic.message());
        check(diagnostic.message().contains("capability CLASSES"),
            "the failure detail carries capability CLASSES");
        check(diagnostic.message().contains("module 'main'"),
            "the failure detail carries the module");
        check(diagnostic.message().contains("semanticProfile DEAL_V1_3_INT32"),
            "the failure detail carries the semantic profile");
        check(diagnostic.message().contains("irVersion deal.semantic-ir/2"),
            "the failure detail carries the IR version");
        check(diagnostic.message().contains("origin ClassConstructionValidator " + rule),
            "the failure detail carries the ClassConstructionValidator origin");
    }

    /**
     * Asserts one E6005 whose range is exactly the pinned SOURCE range
     * (ISSUE-0750: the offending op's own file/span survives the shared
     * failure construction).
     */
    private static void expectE6005Range(Optional<CompilerDiagnostic> outcome, String rule,
                                         String file, int startLine, int startColumn,
                                         int endLine, int endColumn, int startOffset,
                                         int endOffset) {
        expectE6005(outcome, rule);
        if (outcome.isEmpty()) {
            return;
        }
        DiagnosticRange range = outcome.get().range();
        check(range.origin() == RangeOrigin.SOURCE,
            rule + ": the range is SOURCE; got " + range);
        check(file.equals(range.file()),
            rule + ": the range names " + file + "; got " + range.file());
        check(range.startLine() == startLine && range.startColumn() == startColumn
                && range.endLine() == endLine && range.endColumn() == endColumn,
            rule + ": the range positions are " + startLine + ":" + startColumn + "-"
                + endLine + ":" + endColumn + "; got " + range.startLine() + ":"
                + range.startColumn() + "-" + range.endLine() + ":"
                + range.endColumn());
        check(range.startScalarOffset() == startOffset
                && range.endScalarOffset() == endOffset
                && range.scalarLength() == endOffset - startOffset,
            rule + ": the range offsets are [" + startOffset + "," + endOffset
                + "); got [" + range.startScalarOffset() + ","
                + range.endScalarOffset() + ") length " + range.scalarLength());
    }

    // =========================================================================
    // 1. Positive corpus
    // =========================================================================

    private static void testPositiveCorpus() {
        System.out.println("-- positive corpus: every admitted class-op shape validates --");

        // The representative slice: an exported defaulted class, a
        // non-exported class, construction, field ops, has(), and the
        // generated @jsonable bodies with a same-module nested class.
        String source = """
            // @jsonable
            export class Point {
              x: number;
              tag: string;
              note?: string;
            }
            // @jsonable
            export class Inner { n: number; }
            // @jsonable
            export class Holder { home: Point; opt?: Inner; }
            let p: Point = {x: 1, tag: "p"};
            let q: Point = {x: 2, tag: "q"};
            p.x = 9;
            p.note = "a";
            delete p.note;
            let hasNote: boolean = has(p.note);
            """;
        LoweredSlice lowered = lowerModule(source);
        SemanticLowerer.ClassDeclarationCoreResult result =
            lowered == null ? null : lowered.result();
        check(result != null && result.lowering() != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null,
            "the representative slice lowers (the production seam runs the validator "
                + "internally): " + (result == null || result.lowering() == null ? "null"
                    : result.lowering().diagnostics()));
        if (result == null || result.lowering() == null || result.lowering().hasErrors()
                || result.lowering().unit() == null) {
            return;
        }
        // The direct drive over the produced records (unit + table +
        // registry + json defaults + own interface + empty imported
        // facts) passes.
        Optional<CompilerDiagnostic> direct = ClassConstructionValidator.validate(
            result.lowering().unit(), result.lowering().table(), result.registry(),
            lowered.ownInterface(), Map.of());
        check(direct.isEmpty(),
            "the produced records validate directly: " + direct);

        // The SHARED_FACTORY positive is the two-module seam's; the
        // single-module seam exercises LOCAL plus the shared shapes'
        // record invariants.
    }

    // =========================================================================
    // 2. FACTORY_COHERENCE negatives
    // =========================================================================

    private static void testFactoryNegatives(LoweredSlice lowered) {
        System.out.println("-- factory negatives: unregistered/fabricated entries, "
            + "reordered children, parented factory, duplicate defaults --");

        SemanticLowerer.ClassDeclarationCoreResult result =
            lowered == null ? null : lowered.result();
        check(result != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null, "the factory slice lowers");
        if (result == null || result.lowering().hasErrors()
                || result.lowering().unit() == null) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();

        // (a) An unregistered exported class: the registry emptied while
        // the unit still produces the factory op.
        expectE6005(ClassConstructionValidator.validate(unit, result.lowering().table(),
            new ClassFactoryRegistry(Map.of()), lowered.ownInterface(), Map.of()), ClassConstructionValidator
                .FACTORY_COHERENCE);

        // (b) A fabricated registry key: a registration under an entry no
        // exported class carries (the valid registrations stay).
        ClassFactoryId fabricated = new ClassFactoryId(99_999);
        Map<ClassFactoryId, OpId> withFabricated = new LinkedHashMap<>(
            result.registry().factories());
        withFabricated.put(fabricated, ofKind(unit, SemanticOpKind.CLASS_FACTORY)
            .get(0).opId());
        expectE6005(ClassConstructionValidator.validate(unit, result.lowering().table(),
            new ClassFactoryRegistry(withFabricated),
            lowered.ownInterface(), Map.of()),
            ClassConstructionValidator.FACTORY_COHERENCE);

        // (c) A provider names a class absent from its declaration surface.
        SemanticOp factory = firstOfKind(unit, SemanticOpKind.CLASS_FACTORY);
        KindPayload.ClassFactoryPayload factoryPayload = (KindPayload.ClassFactoryPayload) factory.payload();
        SemanticOp tampered = rebuild(factory, new KindPayload.ClassFactoryPayload(
            new ClassId(MODULE.path(), "Missing"), factoryPayload.callerOpRef()));
        expectE6005(ClassConstructionValidator.validate(unitWithOps(unit, replaceOp(unit, tampered)),
            result.lowering().table(), result.registry(), lowered.ownInterface(), Map.of()), ClassConstructionValidator.FACTORY_COHERENCE);

        // (d) A parented factory (the detached static shape broken).
        expectE6005(ClassConstructionValidator.validate(
            unitWithOps(unit, replaceOp(unit, withParent(factory, factory.opId()))),
            result.lowering().table(), result.registry(), lowered.ownInterface(), Map.of()),
            ClassConstructionValidator.FACTORY_COHERENCE);

        // (e) A duplicate detached provider under a fresh operation/result identity.
        SemanticOp clone = new SemanticOp(new OpId(MODULE, 99_998), factory.kind(),
            factory.origin(), new ValueId(99_998), factory.resultType(), factory.operands(),
            factory.operandTypes(), factory.payload(), factory.failurePolicy(), factory.contract());
        List<SemanticOp> withClone = new ArrayList<>(unit.ops());
        withClone.add(clone);
        expectE6005(ClassConstructionValidator.validate(unitWithOps(unit, withClone),
            result.lowering().table(), result.registry(), lowered.ownInterface(), Map.of()), ClassConstructionValidator.FACTORY_COHERENCE);

        // (f) A factory op carrying another module's tag: the registry's
        // owning module and the operation's module tag are the provider
        // owner (ISSUE-0821), never the class-id namespace or a basename.
        {
            OpId foreignId = new OpId(new ModuleId("other.module"), factory.opId().id());
            SemanticOp foreignTagged = new SemanticOp(foreignId, factory.kind(),
                factory.origin(), factory.result(), factory.resultType(),
                factory.operands(), factory.operandTypes(), factory.payload(),
                factory.failurePolicy(), factory.contract());
            Map<ClassFactoryId, OpId> foreign = new LinkedHashMap<>(
                result.registry().factories());
            for (Map.Entry<ClassFactoryId, OpId> row
                    : result.registry().factories().entrySet()) {
                if (row.getValue().equals(factory.opId())) {
                    foreign.put(row.getKey(), foreignId);
                }
            }
            List<SemanticOp> foreignOps = new ArrayList<>(unit.ops());
            for (int i = 0; i < foreignOps.size(); i++) {
                if (foreignOps.get(i).opId().equals(factory.opId())) {
                    foreignOps.set(i, foreignTagged);
                }
            }
            expectE6005(ClassConstructionValidator.validate(
                unitWithOps(unit, foreignOps),
                result.lowering().table(), new ClassFactoryRegistry(foreign),
                lowered.ownInterface(), Map.of()),
                ClassConstructionValidator.FACTORY_COHERENCE);
        }
    }

    private static List<SemanticOp> replaceOp(LoweredModuleUnit unit, SemanticOp replacement) {
        List<SemanticOp> ops = new ArrayList<>(unit.ops());
        replaceOpIn(ops, replacement);
        return ops;
    }

    /** Replace one op's entry in an already-detached op list, in place. */
    private static void replaceOpIn(List<SemanticOp> ops, SemanticOp replacement) {
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i).opId().equals(replacement.opId())) {
                ops.set(i, replacement);
                return;
            }
        }
        throw new IllegalArgumentException("no op with id " + replacement.opId());
    }

    // =========================================================================
    // 3. CONSTRUCTION_COHERENCE negatives
    // =========================================================================

    private static void testConstructionNegatives(LoweredSlice lowered) {
        System.out.println("-- construction negatives: layout identity, undeclared "
            + "provided field, boundary order/input/kind, owner shapes --");

        SemanticLowerer.ClassDeclarationCoreResult result =
            lowered == null ? null : lowered.result();
        check(result != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null, "the construction slice lowers");
        if (result == null || result.lowering().hasErrors()
                || result.lowering().unit() == null) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        SemanticOp classNew = firstOfKind(unit, SemanticOpKind.CLASS_NEW);
        KindPayload.ClassNewPayload payload = (KindPayload.ClassNewPayload) classNew.payload();

        // (a) A layout carrying a foreign classId (the payload layout
        // keeps the foreign id while the payload names Point).
        ClassLayout foreignLayout = new ClassLayout(
            new ClassId("foreign", "Point"), payload.layout().fields());
        expectE6005(ClassConstructionValidator.validate(unitWithOps(unit,
            replaceOp(unit, rebuild(classNew,
                new KindPayload.ClassNewPayload(payload.classId(), foreignLayout,
                    payload.providedFields(), payload.constructionProvider(),
                    payload.classFactoryRef(),
                    payload.fieldBoundaries())))),
            result.lowering().table(), result.registry(), lowered.ownInterface(), Map.of()),
            ClassConstructionValidator.CONSTRUCTION_COHERENCE);

        // (b) An undeclared provided field.
        List<KindPayload.ProvidedField> extraProvided =
            new ArrayList<>(payload.providedFields());
        extraProvided.add(new KindPayload.ProvidedField("zzz", new ValueId(1)));
        expectE6005(ClassConstructionValidator.validate(unitWithOps(unit,
            replaceOp(unit, rebuild(classNew,
                new KindPayload.ClassNewPayload(payload.classId(), payload.layout(),
                    extraProvided, payload.constructionProvider(), payload.classFactoryRef(), payload.fieldBoundaries())))),
            result.lowering().table(), result.registry(), lowered.ownInterface(), Map.of()),
            ClassConstructionValidator.CONSTRUCTION_COHERENCE);

        // (c) A swapped field-boundary order (the parented children keep
        // their emission order, so the payload order mismatch is caught).
        List<KindPayload.FieldBoundary> swapped =
            new ArrayList<>(payload.fieldBoundaries());
        if (swapped.size() < 2) {
            fail("the slice's CLASS_NEW carries fewer than two field boundaries");
        } else {
            java.util.Collections.swap(swapped, 0, 1);
            expectE6005(ClassConstructionValidator.validate(unitWithOps(unit,
                replaceOp(unit, rebuild(classNew,
                    new KindPayload.ClassNewPayload(payload.classId(), payload.layout(),
                        payload.providedFields(), payload.constructionProvider(),
                        payload.classFactoryRef(),
                        swapped)))),
                result.lowering().table(), result.registry(), lowered.ownInterface(), Map.of()),
                ClassConstructionValidator.CONSTRUCTION_COHERENCE);
        }

        // (d) A tampered boundary input: the first boundary child's input
        // no longer names the provided value.
        List<SemanticOp> children = childrenOf(unit, classNew);
        if (!children.isEmpty()) {
            KindPayload.BoundaryPayload boundaryPayload =
                (KindPayload.BoundaryPayload) children.get(0).payload();
            SemanticOp tampered = rebuild(children.get(0),
                new KindPayload.BoundaryPayload(boundaryPayload.kind(),
                    boundaryPayload.descriptor(), new ValueId(99_997),
                    boundaryPayload.realization()));
            expectE6005(ClassConstructionValidator.validate(unitWithOps(unit,
                replaceOp(unit, tampered)), result.lowering().table(),
                result.registry(), lowered.ownInterface(),
                Map.of()), ClassConstructionValidator.CONSTRUCTION_COHERENCE);
        }

        // (e) An ordinary variable boundary cannot serve a provided class field.
        if (!payload.fieldBoundaries().isEmpty()) {
            KindPayload.FieldBoundary first = payload.fieldBoundaries().get(0);
            SemanticOp child = null;
            for (SemanticOp op : unit.ops()) {
                if (op.opId().equals(first.boundaryOpId())) {
                    child = op;
                }
            }
            if (child != null) {
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) child.payload();
                BoundaryKind wrongKind = first.kind() == BoundaryKind
                    .CLASS_LITERAL_FIELD
                    ? BoundaryKind.VARIABLE_DECLARATION
                    : BoundaryKind.CLASS_LITERAL_FIELD;
                SemanticOp tampered = rebuild(child,
                    new KindPayload.BoundaryPayload(wrongKind,
                        boundaryPayload.descriptor(), boundaryPayload.input(),
                        boundaryPayload.realization()));
                List<KindPayload.FieldBoundary> wrongEntries =
                    new ArrayList<>(payload.fieldBoundaries());
                wrongEntries.set(0, new KindPayload.FieldBoundary(first.field(), wrongKind,
                    first.boundaryOpId()));
                expectE6005(ClassConstructionValidator.validate(unitWithOps(unit,
                    replaceOp(unit, tampered)), result.lowering().table(),
                    result.registry(), lowered.ownInterface(),
                    Map.of()), ClassConstructionValidator.CONSTRUCTION_COHERENCE);
                // The payload kind and the child kind both tampered
                // against the pinned expectation.
                expectE6005(ClassConstructionValidator.validate(unitWithOps(unit,
                    replaceOp(unit, rebuild(classNew,
                        new KindPayload.ClassNewPayload(payload.classId(),
                            payload.layout(), payload.providedFields(),
                            payload.constructionProvider(), payload.classFactoryRef(), wrongEntries)))),
                    result.lowering().table(), result.registry(), lowered.ownInterface(), Map.of()),
                    ClassConstructionValidator.CONSTRUCTION_COHERENCE);
            }
        }

        // (g) A RETAINED_ABI owner (E10's shape, never produced here).
        expectE6005(ClassConstructionValidator.validate(unitWithOps(unit,
            replaceOp(unit, rebuild(classNew,
                new KindPayload.ClassNewPayload(payload.classId(), payload.layout(),
                    payload.providedFields(), ConstructionProvider.RETAINED_ABI, new ClassFactoryId(78), payload.fieldBoundaries())))),
            result.lowering().table(), result.registry(), lowered.ownInterface(), Map.of()),
            ClassConstructionValidator.CONSTRUCTION_COHERENCE);
    }

    // =========================================================================
    // 4. FIELD_OPERATION_SHAPE negatives
    // =========================================================================

    private static void testFieldOperationNegatives() {
        System.out.println("-- field-operation negatives: dropped child, tampered "
            + "descriptor, extra child, required-field delete --");

        String source = """
            class Point {
              x: number;
              y?: number;
            }
            let p: Point = {x: 1};
            let v: number | null = p.y;
            p.x = 9;
            delete p.y;
            """;
        LoweredSlice lowered = lowerModule(source);
        SemanticLowerer.ClassDeclarationCoreResult result =
            lowered == null ? null : lowered.result();
        check(result != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null, "the field slice lowers");
        if (result == null || result.lowering().hasErrors()
                || result.lowering().unit() == null) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        SemanticOp read = firstOfKind(unit, SemanticOpKind.FIELD_READ);

        // (a) The OPTIONAL_FIELD_READ child dropped (reparented away).
        List<SemanticOp> readChildren = childrenOf(unit, read);
        if (readChildren.size() == 2) {
            expectE6005(ClassConstructionValidator.validate(unitWithOps(unit,
                replaceOp(unit, withParent(readChildren.get(1), null))),
                result.lowering().table(), result.registry(), lowered.ownInterface(), Map.of()),
                ClassConstructionValidator.FIELD_OPERATION_SHAPE);
        }

        // (b) A tampered receiver descriptor.
        if (!readChildren.isEmpty()) {
            KindPayload.BoundaryPayload boundaryPayload =
                (KindPayload.BoundaryPayload) readChildren.get(0).payload();
            SemanticOp tampered = rebuild(readChildren.get(0),
                new KindPayload.BoundaryPayload(boundaryPayload.kind(),
                    new RuntimeDescriptor.Class(new ClassId("other", "Other")),
                    boundaryPayload.input(), boundaryPayload.realization()));
            expectE6005(ClassConstructionValidator.validate(unitWithOps(unit,
                replaceOp(unit, tampered)), result.lowering().table(),
                result.registry(), lowered.ownInterface(),
                Map.of()), ClassConstructionValidator.FIELD_OPERATION_SHAPE);
        }

        // (c) An extra boundary child parented to the read op.
        SemanticOp delete = firstOfKind(unit, SemanticOpKind.FIELD_DELETE);
        if (delete != null) {
            List<SemanticOp> deleteChildren = childrenOf(unit, delete);
            if (!deleteChildren.isEmpty()) {
                expectE6005(ClassConstructionValidator.validate(unitWithOps(unit,
                    replaceOp(unit, withParent(deleteChildren.get(0), read.opId()))),
                    result.lowering().table(), result.registry(), lowered.ownInterface(), Map.of()),
                    ClassConstructionValidator.FIELD_OPERATION_SHAPE);
            }
        }

        // (d) A required-field delete (the payload field rewritten from
        // the optional y to the required x).
        if (delete != null) {
            KindPayload.FieldDeletePayload deletePayload =
                (KindPayload.FieldDeletePayload) delete.payload();
            expectE6005(ClassConstructionValidator.validate(unitWithOps(unit,
                replaceOp(unit, rebuild(delete,
                    new KindPayload.FieldDeletePayload(deletePayload.classValue(),
                        deletePayload.classId(), "x")))),
                result.lowering().table(), result.registry(), lowered.ownInterface(), Map.of()),
                ClassConstructionValidator.FIELD_OPERATION_SHAPE);
        }
    }

    /**
     * The optional null read at the validator (ISSUE-0883): the read result
     * of an optional field whose declared type is {@code null} is the
     * {@code null} atom — {@code null | null} canonicalizes to {@code null}
     * — so the layout-derived expected descriptor never builds the
     * forbidden {@code ?null} wrapper. The read arm validates cleanly, and a
     * read that publishes the wrong wrap (the op result and its boundary
     * child both retyped to {@code ?string}) fails the pinned
     * FIELD_OPERATION_SHAPE rule instead of crashing.
     */
    private static void testOptionalNullFieldReadArm() {
        System.out.println("-- optional null read: the read-result descriptor is the "
            + "null atom, never ?null --");

        LoweredSlice lowered = lowerModule("""
            class Box {
              n?: null;
            }
            let b: Box = {};
            let n: null = b.n;
            """);
        SemanticLowerer.ClassDeclarationCoreResult result =
            lowered == null ? null : lowered.result();
        check(result != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null,
            "the optional-null read slice lowers and validates: "
                + (result == null || result.lowering() == null ? "null"
                    : result.lowering().diagnostics()));
        if (result == null || result.lowering().hasErrors()
                || result.lowering().unit() == null) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        SemanticOp read = firstOfKind(unit, SemanticOpKind.FIELD_READ);
        check(read != null, "the slice lowers one FIELD_READ op");
        if (read == null) {
            return;
        }
        check(read.resultType() == RuntimeDescriptor.Null.INSTANCE,
            "the FIELD_READ publishes the null atom; got " + read.resultType());
        List<SemanticOp> children = childrenOf(unit, read);
        check(children.size() == 2,
            "the read parents exactly two boundary children; got " + children.size());
        if (children.size() != 2
                || !(children.get(1).payload()
                    instanceof KindPayload.BoundaryPayload fieldBoundary)) {
            return;
        }
        check(fieldBoundary.kind() == BoundaryKind.OPTIONAL_FIELD_READ
                && fieldBoundary.descriptor() == RuntimeDescriptor.Null.INSTANCE,
            "the OPTIONAL_FIELD_READ descriptor is the null atom; got "
                + fieldBoundary.kind() + " " + fieldBoundary.descriptor());

        // The layout-derived expected descriptor must be the null atom: the
        // clean re-validation of the lowered unit is the regression (a raw
        // ?null construction throws out of the validator instead).
        check(ClassConstructionValidator.validate(unit, result.lowering().table(),
                result.registry(), lowered.ownInterface(), Map.of())
                .isEmpty(),
            "the lowered optional-null read re-validates with no failure");

        // A read publishing the wrong wrap: the ?string form names the same
        // shape, so only the layout comparison can reject it.
        RuntimeDescriptor nullableString =
            new RuntimeDescriptor.Nullable(RuntimeDescriptor.String.INSTANCE);
        SemanticOp wrongRead = rebuildWithResultType(read, nullableString);
        SemanticOp wrongChild = rebuild(children.get(1),
            new KindPayload.BoundaryPayload(fieldBoundary.kind(), nullableString,
                fieldBoundary.input(), fieldBoundary.realization()));
        List<SemanticOp> tampered = replaceOp(unit, wrongRead);
        replaceOpIn(tampered, wrongChild);
        expectE6005(ClassConstructionValidator.validate(
                unitWithOps(unit, tampered),
                result.lowering().table(), result.registry(), lowered.ownInterface(), Map.of()),
            ClassConstructionValidator.FIELD_OPERATION_SHAPE);
    }

    /**
     * The malformed links of the exact declaring-provider field route: a
     * recorded construction entry naming another class's declaration entry,
     * an entry resolving no registered declaration, and a record-less
     * operation of a multiple-declaration identity. Each fails with the
     * named E6005 carrying the offending op's own SOURCE range — never the
     * range-less canonical synthetic surface (ISSUE-0809: the available
     * origin survives the admission failure).
     */
    private static void testFieldLayoutLinkOrigins() {
        System.out.println("-- the malformed field-layout links fail closed with the "
            + "offending op's own SOURCE range --");

        String source = """
            class Point {
              x: number;
              y?: number;
            }
            let p: Point = {x: 1};
            let v: number | null = p.y;
            p.x = 9;
            delete p.y;
            """;
        LoweredSlice lowered = lowerModule(source);
        SemanticLowerer.ClassDeclarationCoreResult result =
            lowered == null ? null : lowered.result();
        check(result != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null, "the link baseline lowers");
        if (result == null || result.lowering().hasErrors()
                || result.lowering().unit() == null) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        SemanticOp read = firstOfKind(unit, SemanticOpKind.FIELD_READ);
        check(read != null, "the link baseline produces one FIELD_READ");
        if (read == null) {
            return;
        }
        ClassId point = ((KindPayload.FieldReadPayload) read.payload()).classId();
        ClassId foreign = new ClassId("src/m/ir", "Ir");
        ClassFactoryId foreignEntry = new ClassFactoryId(1_001);
        ClassFactoryId siblingEntry = new ClassFactoryId(1_002);
        ClassFactoryId unregisteredEntry = new ClassFactoryId(9_999);
        // Two same-identity sibling declarations of one canonical class id:
        // a record-less operation of the identity resolves no exact
        // declaration and fails closed rather than selecting a first alias.
        List<FieldInterface> foreignFields = List.of(
            new FieldInterface("a", "number", false, false));
        ClassLayout foreignLayout = new ClassLayout(foreign, List.of(
            new ClassLayout.FieldLayout("a", RuntimeDescriptor.Number.INSTANCE, true,
                ConstructionProvider.LOCAL)));
        Map<ClassFactoryId, SharedFactoryFacts> providers = new LinkedHashMap<>();
        providers.put(foreignEntry, new SharedFactoryFacts(foreign,
            new ClassInterface(foreign, foreignFields, foreignEntry), foreignLayout,
            new OpId(new ModuleId("src/m/ir"), 1), new ValueId(1)));
        providers.put(siblingEntry, new SharedFactoryFacts(foreign,
            new ClassInterface(foreign, foreignFields, siblingEntry),
            new ClassLayout(foreign, foreignLayout.fields()),
            new OpId(new ModuleId("src/m/codec"), 1), new ValueId(2)));

        Map<SemanticOpKind, SemanticOp> fieldOps = new LinkedHashMap<>();
        Map<SemanticOpKind, String> accessText = new LinkedHashMap<>();
        fieldOps.put(SemanticOpKind.FIELD_READ, read);
        accessText.put(SemanticOpKind.FIELD_READ, "p.y");
        fieldOps.put(SemanticOpKind.FIELD_WRITE,
            firstOfKind(unit, SemanticOpKind.FIELD_WRITE));
        accessText.put(SemanticOpKind.FIELD_WRITE, "p.x");
        fieldOps.put(SemanticOpKind.FIELD_DELETE,
            firstOfKind(unit, SemanticOpKind.FIELD_DELETE));
        accessText.put(SemanticOpKind.FIELD_DELETE, "p.y");

        for (Map.Entry<SemanticOpKind, SemanticOp> entry : fieldOps.entrySet()) {
            SemanticOp op = entry.getValue();
            check(op != null, "the link baseline produces one " + entry.getKey());
            if (op == null) {
                continue;
            }
            String text = accessText.get(entry.getKey());
            expectFieldLinkRange(validateFieldLink(unit, op, point, foreignEntry,
                    lowered, result, providers), op, source, text,
                "a construction entry of another class's declaration",
                "not its own classId");
            expectFieldLinkRange(validateFieldLink(unit, op, point, unregisteredEntry,
                    lowered, result, providers), op, source, text,
                "a construction entry resolving no registered declaration",
                "resolves no registered declaration");
            expectFieldLinkRange(validateFieldLink(unit, op, foreign, null,
                    lowered, result, providers), op, source, text,
                "a record-less operation of a multiple-declaration identity",
                "carries no exact declaring-provider reference");
        }
    }

    /** One tampered field op admitted with the supplied provider facts. */
    private static Optional<CompilerDiagnostic> validateFieldLink(
            LoweredModuleUnit unit, SemanticOp op, ClassId classId,
            ClassFactoryId ref, LoweredSlice lowered,
            SemanticLowerer.ClassDeclarationCoreResult result,
            Map<ClassFactoryId, SharedFactoryFacts> providers) {
        return ClassConstructionValidator.validate(
            withFieldLink(unit, op, classId, ref), result.lowering().table(),
            result.registry(), lowered.ownInterface(),
            Map.of(), Map.of(), providers, Set.of());
    }

    /**
     * One field op whose payload carries {@code classId}/{@code ref}, with
     * its receiver boundary child carrying the matching class descriptor
     * (the field op's receiver child names the payload's class id).
     */
    private static LoweredModuleUnit withFieldLink(LoweredModuleUnit unit, SemanticOp op,
                                                   ClassId classId, ClassFactoryId ref) {
        KindPayload tampered = switch (op.payload()) {
            case KindPayload.FieldReadPayload read -> new KindPayload.FieldReadPayload(
                read.classValue(), classId, read.field(), ref);
            case KindPayload.FieldWritePayload write -> new KindPayload.FieldWritePayload(
                write.classValue(), classId, write.field(), write.value(), ref);
            case KindPayload.FieldDeletePayload delete -> new KindPayload.FieldDeletePayload(
                delete.classValue(), classId, delete.field(), ref);
            default -> throw new IllegalArgumentException(
                "not a field payload: " + op.payload());
        };
        SemanticOp rebuilt = rebuild(op, tampered);
        SemanticOp receiver = receiverBoundaryOf(unit, op);
        List<SemanticOp> ops = new ArrayList<>(unit.ops());
        for (int i = 0; i < ops.size(); i++) {
            SemanticOp candidate = ops.get(i);
            if (candidate.opId().equals(op.opId())) {
                ops.set(i, rebuilt);
            } else if (receiver != null && candidate.opId().equals(receiver.opId())) {
                KindPayload.BoundaryPayload boundary =
                    (KindPayload.BoundaryPayload) receiver.payload();
                ops.set(i, rebuild(receiver, new KindPayload.BoundaryPayload(
                    boundary.kind(), new RuntimeDescriptor.Class(classId),
                    boundary.input(), boundary.realization())));
            }
        }
        return unitWithOps(unit, ops);
    }

    /** The op's UNTYPED_CLASS_INPUT boundary child, or {@code null}. */
    private static SemanticOp receiverBoundaryOf(LoweredModuleUnit unit, SemanticOp op) {
        for (SemanticOp child : childrenOf(unit, op)) {
            if (child.payload() instanceof KindPayload.BoundaryPayload boundary
                    && boundary.kind() == BoundaryKind.UNTYPED_CLASS_INPUT) {
                return child;
            }
        }
        return null;
    }

    /**
     * Asserts the named E6005 of one malformed field link carries exactly
     * the offending op's own SOURCE range, whose offsets name the member
     * access of the test source.
     */
    private static void expectFieldLinkRange(Optional<CompilerDiagnostic> outcome,
                                             SemanticOp op, String source,
                                             String accessText, String link,
                                             String messageFragment) {
        expectE6005(outcome, ClassConstructionValidator.FIELD_OPERATION_SHAPE);
        if (outcome.isEmpty()) {
            return;
        }
        CompilerDiagnostic diagnostic = outcome.get();
        check(diagnostic.message().contains(messageFragment),
            "the " + link + " failure names the malformed link (" + messageFragment
                + "); got " + diagnostic.message());
        DiagnosticRange expected = op.origin().span().range();
        DiagnosticRange range = diagnostic.range();
        check(expected.origin() == RangeOrigin.SOURCE,
            "the offending " + op.kind() + " " + op.opId()
                + " carries its own SOURCE origin; got " + expected);
        check(range.equals(expected),
            "the " + link + " failure carries the offending " + op.kind() + " "
                + op.opId() + "'s own SOURCE range " + expected + "; got " + range);
        check(!range.isCanonicalSynthetic(),
            "the " + link + " failure never carries the range-less canonical "
                + "synthetic surface; got " + range);
        int start = range.startScalarOffset();
        int end = range.endScalarOffset();
        check(start >= 0 && end == start + accessText.length()
                && source.startsWith(accessText, start),
            "the " + link + " failure range " + range + " names the member access '"
                + accessText + "' in the source; got [" + start + "," + end + ")");
    }

    /** Replaces one produced op by identity. */
    private static LoweredModuleUnit unitWithOp(LoweredModuleUnit unit, OpId replaced,
                                                SemanticOp replacement) {
        List<SemanticOp> ops = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            ops.add(op.opId().equals(replaced) ? replacement : op);
        }
        return unitWithOps(unit, ops);
    }

    /** One op re-origined (the contract snapshot excludes source coordinates). */
    private static SemanticOp withOrigin(SemanticOp op, SourceOrigin origin) {
        return new SemanticOp(op.opId(), op.kind(), origin, op.result(), op.resultType(),
            op.operands(), op.operandTypes(), op.payload(), op.failurePolicy(),
            op.contract());
    }

    /** The table with one op moved into the given membership block. */
    private static StructuredBodyTable tableWithOpBlock(StructuredBodyTable table,
                                                        OpId opId, BlockId block) {
        Map<OpId, BlockId> opBlocks = new LinkedHashMap<>(table.opBlocks());
        opBlocks.put(opId, block);
        Map<BlockId, List<OpId>> blockOps = new LinkedHashMap<>(table.blockOps());
        List<OpId> members = new ArrayList<>(blockOps.getOrDefault(block, List.of()));
        if (!members.contains(opId)) {
            members.add(opId);
        }
        blockOps.put(block, members);
        return new StructuredBodyTable(blockOps, opBlocks);
    }

    /** The table with one op moved into a membership block and an empty added block. */
    private static StructuredBodyTable tableWithOpBlockAndBlock(StructuredBodyTable table,
                                                                OpId opId, BlockId block,
                                                                BlockId added) {
        StructuredBodyTable moved = tableWithOpBlock(table, opId, block);
        Map<BlockId, List<OpId>> blockOps = new LinkedHashMap<>(moved.blockOps());
        blockOps.putIfAbsent(added, List.of());
        return new StructuredBodyTable(blockOps, moved.opBlocks());
    }

    /** One directly seeded produced op with its wired contract digest. */
    private static SemanticOp seedOp(SemanticOpKind kind, OpId opId, SourceOrigin origin,
                                     ValueId result, RuntimeDescriptor resultType,
                                     KindPayload payload) {
        ClosedSelector selector = payload instanceof KindPayload.SelectorCarrying carrying
            ? carrying.selector() : null;
        OperationContractSnapshot provisional = new OperationContractSnapshot(
            OperationContractSnapshot.VERSION, kind, resultType, List.of(), selector,
            payload, FailurePolicyId.NO_DEAL_FAILURE, List.of(), "placeholder");
        String digest = ContractSnapshotCanonicalizer.digest(provisional);
        return new SemanticOp(opId, kind, origin, result, resultType, List.of(), List.of(),
            payload, FailurePolicyId.NO_DEAL_FAILURE,
            new OperationContractSnapshot(OperationContractSnapshot.VERSION, kind,
                resultType, List.of(), selector, payload, FailurePolicyId.NO_DEAL_FAILURE,
                List.of(), digest));
    }

    // =========================================================================
    // 6. JSON_LAYOUT_COHERENCE negatives
    // =========================================================================

    private static void testJsonNegatives() {
        System.out.println("-- JSON negatives: dropped default child, orphan entry, "
            + "unresolvable nested layout, bytes field --");

        String source = """
            // @jsonable
            export class Point {
              x: number;
              tag: string;
            }
            // @jsonable
            export class Inner { n: number; }
            // @jsonable
            export class Holder { home: Point; opt?: Inner; }
            """;
        LoweredSlice lowered = lowerModule(source);
        SemanticLowerer.ClassDeclarationCoreResult result =
            lowered == null ? null : lowered.result();
        check(result != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null, "the json slice lowers");
        if (result == null || result.lowering().hasErrors()
                || result.lowering().unit() == null) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        SemanticOp fromJson = firstOfKind(unit, SemanticOpKind.JSON_FROM_CLASS);
        KindPayload.JsonFromClassPayload fromPayload =
            (KindPayload.JsonFromClassPayload) fromJson.payload();

        check(ClassConstructionValidator.validate(unit, result.lowering().table(),
            result.registry(), lowered.ownInterface(), Map.of()).isEmpty(),
            "the structural JSON baseline admits without an initializer companion");

        // (c) An unresolvable nested class layout: the Holder layout is
        // rebuilt (unit layout and JSON payload layouts alike) with a
        // class-typed field naming a class that resolves nowhere — the
        // nested-jsonable resolution fails.
        ClassId unresolvedHolderId = new ClassId(MODULE.path(), "Holder");
        ClassLayout unresolvedHolderLayout =
            unit.classLayouts().get(unresolvedHolderId);
        if (unresolvedHolderLayout != null) {
            List<ClassLayout.FieldLayout> holderFields =
                new ArrayList<>(unresolvedHolderLayout.fields());
            holderFields.add(new ClassLayout.FieldLayout("gone",
                new RuntimeDescriptor.Class(new ClassId("foreign", "Missing")),
                true, ConstructionProvider.LOCAL));
            ClassLayout tamperedHolder =
                new ClassLayout(unresolvedHolderId, holderFields);
            Map<ClassId, ClassLayout> layouts = new LinkedHashMap<>(unit.classLayouts());
            layouts.put(unresolvedHolderId, tamperedHolder);
            List<SemanticOp> ops = new ArrayList<>(unit.ops());
            for (int i = 0; i < ops.size(); i++) {
                SemanticOp op = ops.get(i);
                if (op.kind() == SemanticOpKind.JSON_FROM_CLASS
                        && op.payload() instanceof KindPayload.JsonFromClassPayload json
                        && json.layout().classId().equals(unresolvedHolderId)) {
                    ops.set(i, rebuild(op, new KindPayload.JsonFromClassPayload(
                        tamperedHolder, json.jsonString())));
                } else if (op.kind() == SemanticOpKind.JSON_TO_CLASS
                        && op.payload() instanceof KindPayload.JsonToClassPayload json
                        && json.layout().classId().equals(unresolvedHolderId)) {
                    ops.set(i, rebuild(op, new KindPayload.JsonToClassPayload(
                        json.classValue(), tamperedHolder)));
                }
            }
            expectE6005(ClassConstructionValidator.validate(
                unitWithLayouts(unitWithOps(unit, ops), layouts),
                result.lowering().table(), result.registry(), lowered.ownInterface(), Map.of()),
                ClassConstructionValidator.JSON_LAYOUT_COHERENCE);
        }

        // (d) A function-typed jsonable field (hand-rebuilt layout): both
        // the payload layout and the unit layout carry the unsupported field.
        ClassId holderId = new ClassId(MODULE.path(), "Holder");
        ClassLayout holderLayout = unit.classLayouts().get(holderId);
        if (holderLayout != null) {
            List<ClassLayout.FieldLayout> fields = new ArrayList<>(holderLayout.fields());
            fields.add(new ClassLayout.FieldLayout("raw",
                RuntimeDescriptor.parseCanonicalText("()->null"),
                true, ConstructionProvider.LOCAL));
            ClassLayout tamperedLayout = new ClassLayout(holderId, fields);
            Map<ClassId, ClassLayout> layouts = new LinkedHashMap<>(unit.classLayouts());
            layouts.put(holderId, tamperedLayout);
            List<SemanticOp> ops = new ArrayList<>(unit.ops());
            for (int i = 0; i < ops.size(); i++) {
                SemanticOp op = ops.get(i);
                if (op.kind() == SemanticOpKind.JSON_FROM_CLASS
                        || op.kind() == SemanticOpKind.JSON_TO_CLASS) {
                    KindPayload payloadOfOp = op.payload();
                    if (payloadOfOp instanceof KindPayload.JsonFromClassPayload jsonPayload
                            && jsonPayload.layout().classId().equals(holderId)) {
                        ops.set(i, rebuild(op,
                            new KindPayload.JsonFromClassPayload(tamperedLayout,
                                jsonPayload.jsonString())));
                    } else if (payloadOfOp instanceof KindPayload.JsonToClassPayload
                            jsonPayload && jsonPayload.layout().classId().equals(holderId)) {
                        ops.set(i, rebuild(op,
                            new KindPayload.JsonToClassPayload(jsonPayload.classValue(),
                                tamperedLayout)));
                    }
                }
            }
            expectE6005(ClassConstructionValidator.validate(
                unitWithLayouts(unitWithOps(unit, ops), layouts),
                result.lowering().table(), result.registry(), lowered.ownInterface(), Map.of()),
                ClassConstructionValidator.JSON_LAYOUT_COHERENCE);
        }
    }

    // =========================================================================
    // 7. Detail fields, dump/snapshot wiring, determinism, nulls
    // =========================================================================

    private static void testDetailsDumpDeterminism() {
        System.out.println("-- detail fields, dump/snapshot wiring, determinism, nulls --");

        String source = """
            // @jsonable
            export class Point {
              x: number;
              tag: string;
              note?: string;
            }
            let p: Point = {x: 1, tag: "p"};
            p.x = 9;
            let v: string | null = p.note;
            let hasNote: boolean = has(p.note);
            """;
        LoweredSlice lowered = lowerModule(source);
        SemanticLowerer.ClassDeclarationCoreResult result =
            lowered == null ? null : lowered.result();
        check(result != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null, "the detail slice lowers");
        if (result == null || result.lowering().hasErrors()
                || result.lowering().unit() == null) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        ExternalModuleInterface own = lowered.ownInterface();

        // The dump/snapshot wiring: the produced unit dumps through the
        // canonicalizer and re-validates from the dump text — the class
        // payload snapshots (layout, provided fields, field boundaries,
        // class/factory refs) survive the text round-trip byte-for-byte.
        String dumpText = SemanticIrDumper.dumpModuleText(unit);
        check(dumpText.contains("\"providedFields\"") && dumpText.contains("\"layout\"")
                && dumpText.contains("\"fieldBoundaries\"")
                && dumpText.contains("\"classId\""),
            "the dump text carries the CLASS_NEW payload fields (providedFields, "
                + "layout, fieldBoundaries, classId)");
        Optional<CompilerDiagnostic> revalidated = SemanticIrValidator.validateText(dumpText,
            new SemanticIrValidator.ComparisonFacts(unit.interfaceHash(),
                SemanticProfile.DEAL_V1_3_INT32, REGISTRY_HASH));
        check(revalidated.isEmpty(),
            "the dumped class unit re-validates from the dump text: " + revalidated);

        // Every class op's digest equals the recomputed canonical digest
        // (the snapshot wiring pin).
        for (SemanticOp op : unit.ops()) {
            switch (op.kind()) {
                case CLASS_NEW, CLASS_FACTORY, FIELD_READ, FIELD_WRITE,
                     FIELD_DELETE, JSON_FROM_CLASS, JSON_TO_CLASS, HAS_FIELD -> {
                    String recomputed = ContractSnapshotCanonicalizer.digest(
                        op.contract());
                    check(op.contract().canonicalDigest().equals(recomputed),
                        op.kind() + " " + op.opId() + " carries the recomputed "
                            + "canonical digest");
                }
                default -> {
                    // Not a class op.
                }
            }
        }

        // Determinism and purity: the direct validation repeats with
        // byte-identical outcomes and mutates nothing.
        Optional<CompilerDiagnostic> first = ClassConstructionValidator.validate(unit,
            result.lowering().table(), result.registry(), own,
            Map.of());
        Optional<CompilerDiagnostic> second = ClassConstructionValidator.validate(unit,
            result.lowering().table(), result.registry(), own,
            Map.of());
        check(first.isEmpty() && second.isEmpty(),
            "the direct validation is deterministic (both runs empty)");
        check(unit.ops().size() == result.lowering().unit().ops().size()
                && result.registry().factories().equals(result.registry().factories()),
            "the validator mutates neither the unit nor the records");

        // Null-argument rejection.
        expectNpe(() -> ClassConstructionValidator.validate(null,
            result.lowering().table(), result.registry(), own,
            Map.of()), "null unit");
        expectNpe(() -> ClassConstructionValidator.validate(unit, null,
            result.registry(), own, Map.of()), "null table");
        expectNpe(() -> ClassConstructionValidator.validate(unit,
            result.lowering().table(), null, own, Map.of()),
            "null factory registry");
        expectNpe(() -> ClassConstructionValidator.validate(unit,
            result.lowering().table(), result.registry(), null, Map.of()),
            "null json defaults");
        expectNpe(() -> ClassConstructionValidator.validate(unit,
            result.lowering().table(), result.registry(), null,
            Map.of()), "null own interface");
        expectNpe(() -> ClassConstructionValidator.validate(unit,
            result.lowering().table(), result.registry(), own,
            null), "null shared factories");
    }

    // =========================================================================
    // 8. Failure origins (ISSUE-0750)
    // =========================================================================

    /**
     * ISSUE-0750: a shared validation failure that identifies an offending
     * produced op carries that op's own exact source range — never the
     * empty-file synthetic 1:1 anchor — while a failure with no produced
     * offending op keeps the canonical synthetic range. Codes, messages,
     * and validation order are unchanged.
     */
    private static void testFailureRangeOrigins(LoweredSlice constructionLowered) {
        System.out.println("-- failure origins: an offending op's exact source range "
            + "survives the shared E6005; a genuinely originless failure keeps the "
            + "canonical empty-file synthetic range --");

        String source = """
            export class Point {
              x: number;
            }
            """;
        LoweredSlice lowered = lowerModule(source);
        SemanticLowerer.ClassDeclarationCoreResult result =
            lowered == null ? null : lowered.result();
        check(result != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null, "the origin slice lowers");
        if (result == null || result.lowering().hasErrors()
                || result.lowering().unit() == null) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();

        // (a) A duplicate provider retains the offending operation's exact source range.
        SemanticOp provider = firstOfKind(unit, SemanticOpKind.CLASS_FACTORY);
        SourceOrigin sourceOrigin = new SourceOrigin(SOURCE_ID,
            new SourceSpan(SOURCE_ID, 2, 3, 2, 6, 20, 24),
            SourceOriginKind.USER, new AnchorId(99_999), null);
        SemanticOp clone = new SemanticOp(new OpId(MODULE, 99_999), provider.kind(),
            sourceOrigin, new ValueId(99_999), provider.resultType(), provider.operands(),
            provider.operandTypes(), provider.payload(), provider.failurePolicy(), provider.contract());
        List<SemanticOp> withClone = new ArrayList<>(unit.ops());
        withClone.add(clone);
        expectE6005Range(ClassConstructionValidator.validate(unitWithOps(unit, withClone),
            result.lowering().table(), result.registry(), lowered.ownInterface(), Map.of()), ClassConstructionValidator.FACTORY_COHERENCE,
            SOURCE_ID, 2, 3, 2, 7, 20, 24);

        // (b) A second converted branch: a CLASS_NEW-carried failure keeps
        // the CLASS_NEW op's own range (its origin), not the synthetic
        // anchor — proven against the op's own converted span.
        SemanticLowerer.ClassDeclarationCoreResult constructionResult =
            constructionLowered == null ? null : constructionLowered.result();
        if (constructionResult != null && !constructionResult.lowering().hasErrors()
                && constructionResult.lowering().unit() != null) {
            LoweredModuleUnit constructionUnit = constructionResult.lowering().unit();
            SemanticOp classNew = firstOfKind(constructionUnit, SemanticOpKind.CLASS_NEW);
            KindPayload.ClassNewPayload newPayload =
                (KindPayload.ClassNewPayload) classNew.payload();
            Optional<CompilerDiagnostic> classNewFailure = ClassConstructionValidator
                .validate(unitWithOps(constructionUnit, replaceOp(constructionUnit,
                    rebuild(classNew, new KindPayload.ClassNewPayload(newPayload.classId(),
                        newPayload.layout(), newPayload.providedFields(),
                        ConstructionProvider.LOCAL, new ClassFactoryId(77), newPayload.fieldBoundaries())))),
                    constructionResult.lowering().table(), constructionResult.registry(),
                    constructionLowered.ownInterface(),
                    Map.of());
            expectE6005(classNewFailure, ClassConstructionValidator.CONSTRUCTION_COHERENCE);
            check(classNewFailure.isPresent() && classNewFailure.get().message().endsWith(
                "CLASS_NEW " + classNew.opId() + " carries classFactoryRef "
                    + new ClassFactoryId(77)
                    + " under LOCAL: the pinned LOCAL shape carries a null factory ref)"),
                "the LOCAL factory-ref failure keeps the exact construction detail");
            if (classNewFailure.isPresent()) {
                DiagnosticRange expected = classNew.origin().span().range();
                check(classNewFailure.get().range().origin() == RangeOrigin.SOURCE
                        && classNewFailure.get().range().equals(expected),
                    "the CLASS_NEW failure carries the offending op's own SOURCE "
                        + "range " + expected + "; got " + classNewFailure.get().range());
            }
        } else {
            fail("the construction origin slice lowers");
        }

        // (c) The originless control: a registry entry naming an op the
        // unit does not produce identifies no produced offending op, so
        // the canonical empty-file synthetic range is preserved.
        List<ClassInterface> ownClasses = lowered.ownInterface().classes();
        if (ownClasses.isEmpty()) {
            fail("the origin slice exports one class");
            return;
        }
        ClassFactoryId constructionEntry = ownClasses.get(0).constructionEntry();
        Map<ClassFactoryId, OpId> orphaned = new LinkedHashMap<>(
            result.registry().factories());
        orphaned.put(constructionEntry, new OpId(MODULE, 1_000_000));
        Optional<CompilerDiagnostic> originless = ClassConstructionValidator.validate(
            unit, result.lowering().table(), new ClassFactoryRegistry(orphaned),
            lowered.ownInterface(), Map.of());
        expectE6005(originless, ClassConstructionValidator.FACTORY_COHERENCE);
        if (originless.isPresent()) {
            DiagnosticRange range = originless.get().range();
            check(range.origin() == RangeOrigin.SYNTHETIC && range.isCanonicalSynthetic()
                    && range.file().isEmpty(),
                "the originless failure keeps the canonical empty-file synthetic "
                    + "range, got " + range);
        }
    }

    // =========================================================================
    // 9. Project seed/interface coherence (ISSUE-0818)
    // =========================================================================

    private static final ModuleId HOST_FACADE = new ModuleId("host.facade");
    private static final String HOST_FACADE_SPECIFIER = "host/facade";
    private static final String EXTERNAL_PREFIX = "$external/";
    private static final ClassId FACADE_ENDPOINT =
        new ClassId(EXTERNAL_PREFIX + HOST_FACADE_SPECIFIER, "Endpoint");
    private static final ClassId FACADE_OTHER =
        new ClassId(EXTERNAL_PREFIX + HOST_FACADE_SPECIFIER, "Other");
    private static final ClassId FACADE_ENDPOINT_DECLARED =
        new ClassId(HOST_FACADE.path(), "Endpoint");
    private static final ClassId FACADE_OTHER_DECLARED =
        new ClassId(HOST_FACADE.path(), "Other");


    /**
     * The transported declaration facts of a host declaration module: two
     * exported classes with every declared-type form (scalars, optional,
     * nullable, array, function, same-module class, Error) and a declaration module, plus an implementation module class the
     * seeds never cover.
     */
    private static ProjectInterfaceIndex facadeInterfaceIndex() {
        Map<ModuleId, ExternalModuleInterface> modules = new LinkedHashMap<>();
        modules.put(HOST_FACADE, new ExternalModuleInterface(HOST_FACADE,
            ExternalModuleKind.HOST,
            List.of(new ResolvedImport("facade", HOST_FACADE_SPECIFIER, HOST_FACADE,
                ExternalModuleKind.HOST)),
            List.of(new ExportInterface("Endpoint", "@host.facade/Endpoint")),
            List.of(
                new ClassInterface(FACADE_ENDPOINT_DECLARED,
                    List.of(new FieldInterface("path", "string", false, false),
                        new FieldInterface("port", "number", true, false),
                        new FieldInterface("note", "string | null", true, true),
                        new FieldInterface("tags", "string[]", true, false),
                        new FieldInterface("cb", "((number) => string) | null", true, true),
                        new FieldInterface("peer", "@host.facade/Other", false, false),
                        new FieldInterface("others", "(@host.facade/Other | null)[]", true, false),
                        new FieldInterface("failure", "Error", false, false)),
                    new ClassFactoryId(3)),
                new ClassInterface(FACADE_OTHER_DECLARED,
                    List.of(new FieldInterface("count", "number", false, false)),
                    new ClassFactoryId(4))),
            InitializationMode.ONCE_AFTER_DEPENDENCIES));
        modules.put(MODULE, new ExternalModuleInterface(MODULE,
            ExternalModuleKind.IMPLEMENTATION, List.of(), List.of(),
            List.of(new ClassInterface(new ClassId("src", "Point"),
                List.of(new FieldInterface("x", "number", false, false)),
                new ClassFactoryId(6))),
            InitializationMode.ONCE_AFTER_DEPENDENCIES));
        return new ProjectInterfaceIndex(ProjectInterfaceIndex.FORMAT_VERSION, modules);
    }

    private static ClassLayout.FieldLayout seedField(String name,
            RuntimeDescriptor descriptor, boolean required) {
        return seedField(name, descriptor, required, ConstructionProvider.HOST_DECLARATION);
    }

    private static ClassLayout.FieldLayout seedField(String name,
            RuntimeDescriptor descriptor, boolean required, ConstructionProvider owner) {
        return new ClassLayout.FieldLayout(name, descriptor, required, owner);
    }

    /** The coherent registration set of {@link #facadeInterfaceIndex()}. */
    private static ClassRegistrationSeeds facadeSeeds() {
        Map<ClassId, ClassRegistrationSeeds.ClassRegistration> registrations =
            new LinkedHashMap<>();
        registrations.put(ClassId.ERROR,
            ClassRegistrationSeeds.builtinErrorRegistration());
        registrations.put(FACADE_ENDPOINT,
            new ClassRegistrationSeeds.ClassRegistration(
                new ClassLayout(FACADE_ENDPOINT, List.of(
                    seedField("path", RuntimeDescriptor.String.INSTANCE, true),
                    seedField("port", RuntimeDescriptor.Number.INSTANCE, false),
                    seedField("note", new RuntimeDescriptor.Nullable(
                        RuntimeDescriptor.String.INSTANCE), false),
                    seedField("tags", new RuntimeDescriptor.Array(
                        RuntimeDescriptor.String.INSTANCE), false),
                    seedField("cb", new RuntimeDescriptor.Nullable(
                        new RuntimeDescriptor.Func(List.of(RuntimeDescriptor.Number.INSTANCE),
                            RuntimeDescriptor.String.INSTANCE)), false),
                    seedField("peer", new RuntimeDescriptor.Class(FACADE_OTHER), true),
                    seedField("others", new RuntimeDescriptor.Array(
                        new RuntimeDescriptor.Nullable(
                            new RuntimeDescriptor.Class(FACADE_OTHER))), false),
                    seedField("failure", new RuntimeDescriptor.Class(ClassId.ERROR), true))),
                ConstructionProvider.HOST_DECLARATION));
        registrations.put(FACADE_OTHER,
            new ClassRegistrationSeeds.ClassRegistration(
                new ClassLayout(FACADE_OTHER,
                    List.of(seedField("count", RuntimeDescriptor.Number.INSTANCE, true))),
                ConstructionProvider.HOST_DECLARATION));
        return new ClassRegistrationSeeds(registrations);
    }

    /** One seed-field descriptor replacement in a registration. */
    private static ClassRegistrationSeeds withEndpointField(String name,
            RuntimeDescriptor descriptor, boolean required) {
        ClassRegistrationSeeds base = facadeSeeds();
        Map<ClassId, ClassRegistrationSeeds.ClassRegistration> registrations =
            new LinkedHashMap<>(base.registrations());
        ClassRegistrationSeeds.ClassRegistration endpoint =
            registrations.get(FACADE_ENDPOINT);
        List<ClassLayout.FieldLayout> fields = new ArrayList<>();
        for (ClassLayout.FieldLayout field : endpoint.layout().fields()) {
            fields.add(field.name().equals(name) ? seedField(name, descriptor, required)
                : field);
        }
        registrations.put(FACADE_ENDPOINT,
            new ClassRegistrationSeeds.ClassRegistration(
                new ClassLayout(FACADE_ENDPOINT, fields), endpoint.owner()));
        return new ClassRegistrationSeeds(registrations);
    }

    private static String seedCoherenceFailure(ProjectInterfaceIndex index,
            ClassRegistrationSeeds seeds) {
        Optional<String> failure =
            ClassConstructionValidator.checkProjectSeedCoherence(index, seeds);
        check(failure.isPresent(), "the seed/interface contradiction is reported");
        return failure.orElse("");
    }

    /**
     * The project-level seed/interface coherence rule (ISSUE-0818): the
     * transported class registration seeds must repeat the supplied
     * interface index exactly — identity, declaration owner, field names and
     * order, required presence, and each field descriptor's declared type
     * (the scalar, nullable, array, function, same-module class and builtin
     * Error forms included) — and nothing is repaired or merged.
     */
    private static void testProjectSeedCoherence() {
        System.out.println("-- project seed/interface coherence (ISSUE-0818) --");
        ProjectInterfaceIndex index = facadeInterfaceIndex();
        ClassRegistrationSeeds seeds = facadeSeeds();
        Optional<String> coherent =
            ClassConstructionValidator.checkProjectSeedCoherence(index, seeds);
        check(coherent.isEmpty(),
            "the coherent declaration seeds and index pass the project seed coherence "
                + "rule: " + coherent);

        // The non-exported declaration class tolerance: the index declares
        // exported classes only, so a registration the index declares at no
        // entry is not itself a contradiction (the rule never invents one).
        Map<ClassId, ClassRegistrationSeeds.ClassRegistration> withHidden =
            new LinkedHashMap<>(seeds.registrations());
        withHidden.put(new ClassId(EXTERNAL_PREFIX + HOST_FACADE_SPECIFIER, "Hidden"),
            new ClassRegistrationSeeds.ClassRegistration(
                new ClassLayout(new ClassId(EXTERNAL_PREFIX + HOST_FACADE_SPECIFIER,
                    "Hidden"),
                    List.of(seedField("hidden", RuntimeDescriptor.Number.INSTANCE, true))),
                ConstructionProvider.HOST_DECLARATION));
        check(ClassConstructionValidator.checkProjectSeedCoherence(index,
                new ClassRegistrationSeeds(withHidden)).isEmpty(),
            "a declaration module class the index does not declare (a non-exported "
                + "class) is not reported as a contradiction");
        Map<ClassId, ClassRegistrationSeeds.ClassRegistration> withEmptyModulePath =
            new LinkedHashMap<>(seeds.registrations());
        ClassId emptyModulePath = new ClassId(EXTERNAL_PREFIX, "Stray");
        withEmptyModulePath.put(emptyModulePath,
            new ClassRegistrationSeeds.ClassRegistration(
                new ClassLayout(emptyModulePath,
                    List.of(seedField("stray", RuntimeDescriptor.Number.INSTANCE, true))),
                ConstructionProvider.HOST_DECLARATION));
        check(ClassConstructionValidator.checkProjectSeedCoherence(index,
                new ClassRegistrationSeeds(withEmptyModulePath)).isEmpty(),
            "a registration key with an empty externals suffix is unresolvable, not a "
                + "crash");

        // Every declared-type form: a mutated descriptor contradicts the
        // interface's declared type.
        check(seedCoherenceFailure(index, withEndpointField("path",
                RuntimeDescriptor.Number.INSTANCE, true)).contains("path"),
            "a scalar seed field descriptor of another type rejects");
        check(seedCoherenceFailure(index, withEndpointField("note",
                RuntimeDescriptor.String.INSTANCE, false)).contains("note"),
            "a nullable seed field descriptor without its nullable form rejects");
        check(seedCoherenceFailure(index, withEndpointField("tags",
                new RuntimeDescriptor.Array(RuntimeDescriptor.Number.INSTANCE), false))
                .contains("tags"),
            "an array seed field descriptor of another element type rejects");
        check(seedCoherenceFailure(index, withEndpointField("cb",
                new RuntimeDescriptor.Func(List.of(RuntimeDescriptor.Number.INSTANCE),
                    RuntimeDescriptor.String.INSTANCE), false)).contains("cb"),
            "a function seed field descriptor without its nullable form rejects");
        check(seedCoherenceFailure(index, withEndpointField("peer",
                new RuntimeDescriptor.Class(
                    new ClassId(EXTERNAL_PREFIX + "host/other", "Other")), true))
                .contains("peer"),
            "a class seed field descriptor of another class identity rejects");
        // The exact nominal identity, never the dotted rendering: a class
        // reference that names an unregistered external spelling of a
        // declared class is a contradiction even though its dotted text is
        // the one the interface declares.
        String alternateSpelling = seedCoherenceFailure(index, withEndpointField("peer",
            new RuntimeDescriptor.Class(
                new ClassId(EXTERNAL_PREFIX + "host.facade", "Other")), true));
        check(alternateSpelling.contains("peer")
                && alternateSpelling.contains("unresolvable"),
            "a class seed field descriptor naming an unregistered external spelling "
                + "of the declared class rejects (identity by exact carried "
                + "registration, never by the dotted rendering): " + alternateSpelling);

        // The registered non-exported declaration class: the index declares
        // no class entry for it, so the reference resolves through its exact
        // carried registration and the declaration module's own entry.
        ClassId hidden = new ClassId(EXTERNAL_PREFIX + HOST_FACADE_SPECIFIER,
            "Hidden");
        Map<ModuleId, ExternalModuleInterface> hiddenModules =
            new LinkedHashMap<>(index.modules());
        ExternalModuleInterface facade = hiddenModules.get(HOST_FACADE);
        List<ClassInterface> hiddenClasses = new ArrayList<>();
        for (ClassInterface entry : facade.classes()) {
            if (!entry.classId().equals(FACADE_ENDPOINT_DECLARED)) {
                hiddenClasses.add(entry);
                continue;
            }
            List<FieldInterface> fields = new ArrayList<>(entry.fields());
            fields.add(new FieldInterface("hidden", "@host.facade/Hidden", false, false));
            hiddenClasses.add(new ClassInterface(entry.classId(), fields,
                entry.constructionEntry()));
        }
        hiddenModules.put(HOST_FACADE, new ExternalModuleInterface(HOST_FACADE,
            facade.kind(), facade.imports(), facade.exports(), hiddenClasses,
            facade.initialization()));
        Map<ClassId, ClassRegistrationSeeds.ClassRegistration> hiddenRegistrations =
            new LinkedHashMap<>(seeds.registrations());
        ClassRegistrationSeeds.ClassRegistration endpoint =
            hiddenRegistrations.get(FACADE_ENDPOINT);
        List<ClassLayout.FieldLayout> hiddenEndpointFields =
            new ArrayList<>(endpoint.layout().fields());
        hiddenEndpointFields.add(seedField("hidden",
            new RuntimeDescriptor.Class(hidden), true));
        hiddenRegistrations.put(FACADE_ENDPOINT,
            new ClassRegistrationSeeds.ClassRegistration(
                new ClassLayout(FACADE_ENDPOINT, hiddenEndpointFields),
                endpoint.owner()));
        hiddenRegistrations.put(hidden,
            new ClassRegistrationSeeds.ClassRegistration(
                new ClassLayout(hidden, List.of(
                    seedField("value", RuntimeDescriptor.String.INSTANCE, true))),
                ConstructionProvider.HOST_DECLARATION));
        Optional<String> nonExported = ClassConstructionValidator
            .checkProjectSeedCoherence(new ProjectInterfaceIndex(
                    ProjectInterfaceIndex.FORMAT_VERSION, hiddenModules),
                new ClassRegistrationSeeds(hiddenRegistrations));
        check(nonExported.isEmpty(),
            "a seed field referencing a non-exported declaration class resolves "
                + "through its exact carried registration: " + nonExported);
        List<ClassLayout.FieldLayout> mutatedHiddenFields =
            new ArrayList<>(hiddenEndpointFields);
        mutatedHiddenFields.set(mutatedHiddenFields.size() - 1, seedField("hidden",
            new RuntimeDescriptor.Class(new ClassId(EXTERNAL_PREFIX + "host.facade",
                "Hidden")), true));
        Map<ClassId, ClassRegistrationSeeds.ClassRegistration> mutatedHiddenRegistrations =
            new LinkedHashMap<>(hiddenRegistrations);
        mutatedHiddenRegistrations.put(FACADE_ENDPOINT,
            new ClassRegistrationSeeds.ClassRegistration(
                new ClassLayout(FACADE_ENDPOINT, mutatedHiddenFields),
                endpoint.owner()));
        Optional<String> mutatedNonExported = ClassConstructionValidator
            .checkProjectSeedCoherence(new ProjectInterfaceIndex(
                    ProjectInterfaceIndex.FORMAT_VERSION, hiddenModules),
                new ClassRegistrationSeeds(mutatedHiddenRegistrations));
        check(mutatedNonExported.isPresent()
                && mutatedNonExported.get().contains("hidden")
                && mutatedNonExported.get().contains("unresolvable"),
            "the unregistered alternate spelling of a non-exported declaration "
                + "class reference rejects: " + mutatedNonExported);
        check(seedCoherenceFailure(index, withEndpointField("failure",
                new RuntimeDescriptor.Class(FACADE_OTHER), true)).contains("failure"),
            "a seed field descriptor of another class than the declared Error "
                + "rejects");
        check(seedCoherenceFailure(index, withEndpointField("others",
                new RuntimeDescriptor.Array(
                    new RuntimeDescriptor.Class(FACADE_OTHER)), false)).contains("others"),
            "an array-of-nullable class seed field descriptor without its nullable "
                + "element form rejects");
        check(seedCoherenceFailure(index, withEndpointField("path",
                RuntimeDescriptor.String.INSTANCE, false)).contains("required"),
            "a seed field whose required marker contradicts the declared optional "
                + "fact rejects");

        // Field names, order, count and the registration identity/owner.
        ClassRegistrationSeeds renamed = facadeSeeds();
        Map<ClassId, ClassRegistrationSeeds.ClassRegistration> renamedRows =
            new LinkedHashMap<>();
        for (Map.Entry<ClassId, ClassRegistrationSeeds.ClassRegistration> entry
                : renamed.registrations().entrySet()) {
            if (!entry.getKey().equals(FACADE_ENDPOINT)) {
                renamedRows.put(entry.getKey(), entry.getValue());
                continue;
            }
            List<ClassLayout.FieldLayout> fields = new ArrayList<>();
            for (ClassLayout.FieldLayout field : entry.getValue().layout().fields()) {
                fields.add(field.name().equals("path")
                    ? seedField("route", field.descriptor(), field.required())
                    : field);
            }
            renamedRows.put(entry.getKey(),
                new ClassRegistrationSeeds.ClassRegistration(
                    new ClassLayout(FACADE_ENDPOINT, fields), entry.getValue().owner()));
        }
        check(seedCoherenceFailure(index, new ClassRegistrationSeeds(renamedRows))
                .contains("route"),
            "a seed field name the interface class does not declare rejects");

        Map<ClassId, ClassRegistrationSeeds.ClassRegistration> detachedKey =
            new LinkedHashMap<>(seeds.registrations());
        detachedKey.remove(FACADE_ENDPOINT);
        detachedKey.put(FACADE_ENDPOINT_DECLARED,
            new ClassRegistrationSeeds.ClassRegistration(
                new ClassLayout(FACADE_ENDPOINT, List.of(
                    seedField("path", RuntimeDescriptor.String.INSTANCE, true))),
                ConstructionProvider.HOST_DECLARATION));
        check(seedCoherenceFailure(index, new ClassRegistrationSeeds(detachedKey))
                .contains("class identity"),
            "a seed whose registration key disagrees with its contained layout "
                + "identity rejects");

        Map<ClassId, ClassRegistrationSeeds.ClassRegistration> missing =
            new LinkedHashMap<>(seeds.registrations());
        missing.remove(FACADE_OTHER);
        check(seedCoherenceFailure(index, new ClassRegistrationSeeds(missing))
                .contains("no class registration seed"),
            "an exported declaration class without a registration rejects");

        Map<ClassId, ClassRegistrationSeeds.ClassRegistration> duplicated =
            new LinkedHashMap<>(seeds.registrations());
        ClassRegistrationSeeds.ClassRegistration endpointRegistration =
            seeds.registrationFor(FACADE_ENDPOINT);
        duplicated.put(FACADE_ENDPOINT_DECLARED,
            new ClassRegistrationSeeds.ClassRegistration(
                new ClassLayout(FACADE_ENDPOINT_DECLARED,
                    endpointRegistration.layout().fields()),
                endpointRegistration.owner()));
        check(seedCoherenceFailure(index, new ClassRegistrationSeeds(duplicated))
                .contains("exactly one registration"),
            "two registrations of one declared class reject");

        Map<ClassId, ClassRegistrationSeeds.ClassRegistration> localOwner =
            new LinkedHashMap<>(seeds.registrations());
        localOwner.remove(FACADE_ENDPOINT);
        localOwner.put(FACADE_ENDPOINT,
            new ClassRegistrationSeeds.ClassRegistration(
                new ClassLayout(FACADE_ENDPOINT, List.of(
                    new ClassLayout.FieldLayout("path",
                        RuntimeDescriptor.String.INSTANCE, true, ConstructionProvider.LOCAL))),
                ConstructionProvider.LOCAL));
        check(seedCoherenceFailure(index, new ClassRegistrationSeeds(localOwner))
                .contains("owner"),
            "a declaration registration under the LOCAL owner rejects");

        Map<ClassId, ClassRegistrationSeeds.ClassRegistration> implementationSeed =
            new LinkedHashMap<>(seeds.registrations());
        implementationSeed.put(new ClassId("src", "Point"),
            new ClassRegistrationSeeds.ClassRegistration(
                new ClassLayout(new ClassId("src", "Point"), List.of(
                    seedField("x", RuntimeDescriptor.Number.INSTANCE, true))),
                ConstructionProvider.HOST_DECLARATION));
        check(seedCoherenceFailure(index, new ClassRegistrationSeeds(implementationSeed))
                .contains("IMPLEMENTATION"),
            "a registration naming an implementation module class rejects");

        // The compiler-owned builtin Error registration: its exact owner and
        // exact layout, and its absent form (a legal empty class fact).
        Map<ClassId, ClassRegistrationSeeds.ClassRegistration> withoutError =
            new LinkedHashMap<>(seeds.registrations());
        withoutError.remove(ClassId.ERROR);
        check(ClassConstructionValidator.checkProjectSeedCoherence(index,
                new ClassRegistrationSeeds(withoutError)).isEmpty(),
            "a seed set without the builtin Error registration stays a legal empty "
                + "class fact (the /2 seed collection carries its empty form)");
        Map<ClassId, ClassRegistrationSeeds.ClassRegistration> wrongError =
            new LinkedHashMap<>(seeds.registrations());
        wrongError.put(ClassId.ERROR,
            new ClassRegistrationSeeds.ClassRegistration(
                new ClassLayout(ClassId.ERROR, List.of(
                    new ClassLayout.FieldLayout("code",
                        RuntimeDescriptor.String.INSTANCE, true,
                        ConstructionProvider.HOST_DECLARATION))),
                ConstructionProvider.HOST_DECLARATION));
        check(seedCoherenceFailure(index, new ClassRegistrationSeeds(wrongError))
                .contains("builtin"),
            "a builtin Error registration under another owner rejects");

        expectNpe(() -> ClassConstructionValidator.checkProjectSeedCoherence(null, seeds),
            "a null interface index");
        expectNpe(() -> ClassConstructionValidator.checkProjectSeedCoherence(index, null),
            "a null seed set");
    }

    /**
     * The project seed/module join (ISSUE-0818 review): a carried
     * construction of a declaration class must refer to exactly one carried
     * registration — the same class identity, the same declaration owner
     * and the same contained layout — and the join never repairs either
     * record. The rule reads the raw facts both project admission surfaces
     * share, so a seed set that is internally coherent with the interface
     * index is still rejected when a module's referring {@code CLASS_NEW}
     * disagrees.
     */
    private static void testProjectSeedModuleCoherence() {
        System.out.println("-- project seed/module join (ISSUE-0818 review) --");
        ClassRegistrationSeeds seeds = facadeSeeds();
        ClassRegistrationSeeds.ClassRegistration endpoint =
            seeds.registrationFor(FACADE_ENDPOINT);

        // Positive: the referring CLASS_NEW repeats its registration exactly,
        // under either declaration owner, and an unrelated unit is unaffected.
        check(ClassConstructionValidator.checkProjectSeedModuleCoherence(seeds,
                List.of(unitWithClassNew(FACADE_ENDPOINT, ConstructionProvider.HOST_DECLARATION,
                    endpoint.layout()))).isEmpty(),
            "a CLASS_NEW repeating its HOST_DECLARATION registration passes the join");
        check(ClassConstructionValidator.checkProjectSeedModuleCoherence(seeds,
                List.of(unitWithOps(List.of()))).isEmpty(),
            "a unit without declaration-class constructions passes the join");
        check(ClassConstructionValidator.checkProjectSeedModuleCoherence(seeds,
                List.of(unitWithClassNew(FACADE_ENDPOINT, ConstructionProvider.HOST_DECLARATION,
                    endpoint.layout()), unitWithClassNew(FACADE_OTHER,
                    ConstructionProvider.HOST_DECLARATION,
                    seeds.registrationFor(FACADE_OTHER).layout()))).isEmpty(),
            "two declaration-class constructions in supplied unit order pass the join");

        // Negative: the internally consistent owner mutation (a declaration
        // owner the index/seed rule accepts) contradicts the referring
        // operation, and so do a missing registration and a foreign layout.
        Optional<ClassConstructionValidator.SeedModuleViolation> owner =
            ClassConstructionValidator.checkProjectSeedModuleCoherence(
                new ClassRegistrationSeeds(Map.of(FACADE_ENDPOINT,
                    new ClassRegistrationSeeds.ClassRegistration(
                        new ClassLayout(endpoint.layout().classId(),
                            endpoint.layout().fields().stream().map(field ->
                                new ClassLayout.FieldLayout(field.name(),
                                    field.descriptor(), field.required(),
                                    ConstructionProvider.BUILTIN_ERROR)).toList()),
                        ConstructionProvider.BUILTIN_ERROR))),
                List.of(unitWithClassNew(FACADE_ENDPOINT, ConstructionProvider.HOST_DECLARATION,
                    endpoint.layout())));
        check(owner.isPresent() && owner.get().detail().contains("constructionProvider")
                && owner.get().detail().contains("HOST_DECLARATION")
                && owner.get().detail().contains("BUILTIN_ERROR"),
            "a CLASS_NEW whose default owner disagrees with its seed rejects: " + owner);
        check(owner.isPresent() && owner.get().reason().contains(MODULE.path())
                && owner.get().detail().contains("CLASS_NEW")
                && owner.get().referringUnit().modulePath().equals(MODULE.path())
                && owner.get().referringOp().opId().equals(new OpId(MODULE, 1L)),
            "the violation names the referring module and operation: "
                + owner.map(ClassConstructionValidator.SeedModuleViolation::reason)
                    .orElse(""));

        Optional<ClassConstructionValidator.SeedModuleViolation> missing =
            ClassConstructionValidator.checkProjectSeedModuleCoherence(
                new ClassRegistrationSeeds(Map.of()),
                List.of(unitWithClassNew(FACADE_ENDPOINT, ConstructionProvider.HOST_DECLARATION,
                    endpoint.layout())));
        check(missing.isPresent()
                && missing.get().detail().contains("no class registration seed"),
            "a CLASS_NEW of an unregistered declaration class rejects: " + missing);

        ClassLayout foreign = new ClassLayout(FACADE_ENDPOINT,
            List.of(seedField("path", RuntimeDescriptor.Number.INSTANCE, true)));
        Optional<ClassConstructionValidator.SeedModuleViolation> layout =
            ClassConstructionValidator.checkProjectSeedModuleCoherence(seeds,
                List.of(unitWithClassNew(FACADE_ENDPOINT, ConstructionProvider.HOST_DECLARATION,
                    foreign)));
        check(layout.isPresent() && layout.get().detail().contains("layout"),
            "a CLASS_NEW whose layout differs from its registration rejects: " + layout);

        // A construction shape outside the carried payload record is left to
        // the closed rule engine that owns it, not reported by the join.
        check(ClassConstructionValidator.checkProjectSeedModuleCoherence(seeds,
                List.of(unitWithOps(List.of(rawClassNew("NOT_AN_OWNER",
                    FACADE_ENDPOINT, endpoint.layout()))))).isEmpty(),
            "a CLASS_NEW outside the declaration owners is not the join's subject");

        expectNpe(() -> ClassConstructionValidator.checkProjectSeedModuleCoherence(
                null, List.of()), "a null seed set");
        expectNpe(() -> ClassConstructionValidator.checkProjectSeedModuleCoherence(
                seeds, null), "a null unit list");
    }

    /**
     * The declaration-seed field-operation rule with an optional null field
     * (ISSUE-0883): the seed's read-result descriptor of an optional field
     * declared {@code null} is the {@code null} atom (its two states are the
     * missing and present-null reads, both language {@code null}), so a
     * transported FIELD_READ publishing {@code null} and its
     * OPTIONAL_FIELD_READ boundary agree with the registration, while any
     * other nullable wrap of the read disagrees with a normal violation
     * instead of aborting the check.
     */
    private static void testSeedOptionalNullFieldOperation() {
        System.out.println("-- the declaration-seed optional-null read-result "
            + "projection --");

        ClassRegistrationSeeds withNull = withEndpointExtraField("n",
            RuntimeDescriptor.Null.INSTANCE, false);
        // The optional ?string control keeps its one nullable wrap.
        check(ClassConstructionValidator.checkProjectSeedModuleCoherence(withNull,
                List.of(unitWithRawOps(rawFieldRead(FACADE_ENDPOINT, "note", "?string"),
                    rawFieldReadBoundary(new OpId(MODULE, 1L), "?string")))).isEmpty(),
            "the optional ?string field read agrees with its registration");
        check(ClassConstructionValidator.checkProjectSeedModuleCoherence(withNull,
                List.of(unitWithRawOps(rawFieldRead(FACADE_ENDPOINT, "n", "null"),
                    rawFieldReadBoundary(new OpId(MODULE, 1L), "null")))).isEmpty(),
            "the optional null field read publishing the null atom agrees with its "
                + "registration");

        Optional<ClassConstructionValidator.SeedModuleViolation> wrong =
            ClassConstructionValidator.checkProjectSeedModuleCoherence(withNull,
                List.of(unitWithRawOps(rawFieldRead(FACADE_ENDPOINT, "n", "?string"),
                    rawFieldReadBoundary(new OpId(MODULE, 1L), "?string"))));
        check(wrong.isPresent() && wrong.get().detail().contains("FIELD_READ")
                && wrong.get().detail().contains("null"),
            "a read publishing a nullable wrap over the null atom rejects: " + wrong);
        Optional<ClassConstructionValidator.SeedModuleViolation> boundary =
            ClassConstructionValidator.checkProjectSeedModuleCoherence(withNull,
                List.of(unitWithRawOps(rawFieldRead(FACADE_ENDPOINT, "n", "null"),
                    rawFieldReadBoundary(new OpId(MODULE, 1L), "?string"))));
        check(boundary.isPresent() && boundary.get().detail().contains(
                BoundaryKind.OPTIONAL_FIELD_READ.name()),
            "a boundary descriptor that disagrees with the null atom rejects: "
                + boundary);
    }

    /** One seed registration carrying an extra field after the declared ones. */
    private static ClassRegistrationSeeds withEndpointExtraField(String name,
            RuntimeDescriptor descriptor, boolean required) {
        ClassRegistrationSeeds base = facadeSeeds();
        Map<ClassId, ClassRegistrationSeeds.ClassRegistration> registrations =
            new LinkedHashMap<>(base.registrations());
        ClassRegistrationSeeds.ClassRegistration endpoint =
            registrations.get(FACADE_ENDPOINT);
        List<ClassLayout.FieldLayout> fields =
            new ArrayList<>(endpoint.layout().fields());
        fields.add(seedField(name, descriptor, required));
        registrations.put(FACADE_ENDPOINT,
            new ClassRegistrationSeeds.ClassRegistration(
                new ClassLayout(FACADE_ENDPOINT, fields), endpoint.owner()));
        return new ClassRegistrationSeeds(registrations);
    }

    /** One raw unit carrying a field-read op and its boundary child. */
    private static RawUnit unitWithRawOps(RawOp read, RawOp boundary) {
        return unitWithOps(List.of(read, boundary));
    }

    /** One raw FIELD_READ naming a declaration field and its published result text. */
    private static RawOp rawFieldRead(ClassId classId, String field, String resultType) {
        CanonicalJson.Obj payload = CanonicalJson.obj(
            CanonicalJson.e("classId",
                ContractSnapshotCanonicalizer.semanticIdJson(classId)),
            CanonicalJson.e("classValue",
                ContractSnapshotCanonicalizer.semanticIdJson(new ValueId(1L))),
            CanonicalJson.e("field", CanonicalJson.str(field)));
        return new RawOp(new OpId(MODULE, 1L), SemanticOpKind.FIELD_READ.name(),
            FailurePolicyId.NO_DEAL_FAILURE.name(), null, new ValueId(1L), null,
            resultType, List.of(), List.of(), payload, null, "digest",
            CanonicalJson.obj(), new RawOrigin(SOURCE_ID, SOURCE_ID, 1, 1, 1, 1, 0, 0,
                SourceOriginKind.SYNTHETIC.name(), 0));
    }

    /** One raw OPTIONAL_FIELD_READ boundary child of the given read op. */
    private static RawOp rawFieldReadBoundary(OpId parent, String descriptor) {
        CanonicalJson.Obj payload = CanonicalJson.obj(
            CanonicalJson.e("kind",
                CanonicalJson.str(BoundaryKind.OPTIONAL_FIELD_READ.name())),
            CanonicalJson.e("descriptor", CanonicalJson.str(descriptor)));
        return new RawOp(new OpId(MODULE, 3L), SemanticOpKind.BOUNDARY.name(),
            FailurePolicyId.TYPE_DESCRIPTOR.name(), parent, new ValueId(2L), null,
            null, List.of(), List.of(), payload, null, "digest",
            CanonicalJson.obj(), new RawOrigin(SOURCE_ID, SOURCE_ID, 1, 1, 1, 1, 0, 0,
                SourceOriginKind.SYNTHETIC.name(), 0));
    }

    /** One raw unit carrying exactly the given raw operations. */
    private static RawUnit unitWithOps(List<RawOp> ops) {
        return new RawUnit(MODULE.path(), SemanticProfile.DEAL_V1_3_INT32.name(),
            "interface-digest-1", LoweringContextHash.of(SemanticProfile.DEAL_V1_3_INT32,
                REGISTRY_HASH),
            List.of(), List.of(), List.of(), List.of(),
            new ModuleInitPlan(List.of(), new BlockId(0)), List.of(), List.of(), ops,
            new StructuredBodyTable(Map.of(), Map.of()),
            new ClassFactoryRegistry(Map.of()), ClassBindingFacts.empty());
    }

    /** One raw unit carrying a single CLASS_NEW of the given class. */
    private static RawUnit unitWithClassNew(ClassId classId, ConstructionProvider owner,
            ClassLayout layout) {
        return unitWithOps(List.of(rawClassNew(owner.name(), classId, layout)));
    }

    /** One raw CLASS_NEW operation payload naming the class, owner and layout. */
    private static RawOp rawClassNew(String owner, ClassId classId, ClassLayout layout) {
        CanonicalJson.Obj payload = CanonicalJson.obj(
            CanonicalJson.e("classId",
                ContractSnapshotCanonicalizer.semanticIdJson(classId)),
            CanonicalJson.e("constructionProvider", CanonicalJson.str(owner)),
            CanonicalJson.e("layout", ContractSnapshotCanonicalizer.layoutJson(layout)));
        return new RawOp(new OpId(MODULE, 1L), SemanticOpKind.CLASS_NEW.name(),
            FailurePolicyId.NO_DEAL_FAILURE.name(), null, null, null, null,
            List.of(), List.of(), payload, null, "digest", CanonicalJson.obj(),
            new RawOrigin(SOURCE_ID, SOURCE_ID, 1, 1, 1, 1, 0, 0,
                SourceOriginKind.SYNTHETIC.name(), 0));
    }

    /**
     * The declared-type rendering of the transported descriptors against the
     * real producers (ISSUE-0818): for every structural type form the
     * interface's {@code declaredType} ({@link CanonicalTypeText#render(Type)})
     * and the seed field's descriptor
     * ({@link DescriptorService#describe(Type)}) are two renderings of the
     * same declared type, so the coherence rule accepts the producer's pair
     * and rejects each other form's descriptor.
     */
    private static void testSeedDeclaredTypeRenderingAgainstProducers() {
        System.out.println("-- seed declared-type rendering against the producers "
            + "(ISSUE-0818) --");
        List<Type> forms = List.of(
            Type.Number.INSTANCE,
            Type.Boolean.INSTANCE,
            Type.Number.INSTANCE,
            Type.String.INSTANCE,
            Type.Table.INSTANCE,
            Type.Null.INSTANCE,
            Types.nullable(Type.String.INSTANCE),
            Types.array(Type.Number.INSTANCE),
            Types.array(Types.nullable(Type.Number.INSTANCE)),
            Types.nullable(Types.array(Type.String.INSTANCE)),
            Types.func(List.of(Type.Number.INSTANCE), Type.String.INSTANCE),
            Types.func(List.of(Types.func(List.of(Type.Number.INSTANCE),
                Type.String.INSTANCE), Type.Number.INSTANCE), Type.String.INSTANCE),
            Types.func(List.of(Types.nullable(Type.Number.INSTANCE)),
                Type.String.INSTANCE),
            Types.func(List.of(Type.Number.INSTANCE), Types.array(Type.Table.INSTANCE),
                true),
            Types.array(Types.func(List.of(Type.Number.INSTANCE), Type.String.INSTANCE)),
            Types.nullable(Types.func(List.of(Type.Number.INSTANCE), Type.String.INSTANCE)),
            Types.array(Types.array(Type.Number.INSTANCE)));
        ModuleId formsModule = new ModuleId("forms.host");
        ClassId formsClass = new ClassId(EXTERNAL_PREFIX + "forms/host", "Forms");
        List<FieldInterface> declaredFields = new ArrayList<>();
        List<ClassLayout.FieldLayout> seedFields = new ArrayList<>();
        for (int i = 0; i < forms.size(); i++) {
            declaredFields.add(new FieldInterface("f" + i,
                CanonicalTypeText.render(forms.get(i)), false, false));
            seedFields.add(new ClassLayout.FieldLayout("f" + i,
                DescriptorService.describe(forms.get(i)), true, ConstructionProvider.HOST_DECLARATION));
        }
        ProjectInterfaceIndex index = new ProjectInterfaceIndex(
            ProjectInterfaceIndex.FORMAT_VERSION,
            Map.of(formsModule, new ExternalModuleInterface(formsModule,
                ExternalModuleKind.HOST, List.of(), List.of(),
                List.of(new ClassInterface(new ClassId(formsModule.path(), "Forms"),
                    declaredFields, new ClassFactoryId(9))),
                InitializationMode.ONCE_AFTER_DEPENDENCIES)));
        Map<ClassId, ClassRegistrationSeeds.ClassRegistration> registrations =
            new LinkedHashMap<>();
        registrations.put(ClassId.ERROR,
            ClassRegistrationSeeds.builtinErrorRegistration());
        registrations.put(formsClass,
            new ClassRegistrationSeeds.ClassRegistration(
                new ClassLayout(formsClass, seedFields), ConstructionProvider.HOST_DECLARATION));
        Optional<String> coherent = ClassConstructionValidator
            .checkProjectSeedCoherence(index, new ClassRegistrationSeeds(registrations));
        check(coherent.isEmpty(),
            "the producer's declared-type and descriptor renderings agree for every "
                + "structural form: " + coherent);
        for (int i = 0; i < forms.size(); i++) {
            int other = (i + 1) % forms.size();
            if (CanonicalTypeText.render(forms.get(other))
                    .equals(CanonicalTypeText.render(forms.get(i)))) {
                continue;
            }
            List<ClassLayout.FieldLayout> mutated = new ArrayList<>(seedFields);
            mutated.set(i, new ClassLayout.FieldLayout("f" + i,
                DescriptorService.describe(forms.get(other)), true,
                ConstructionProvider.HOST_DECLARATION));
            Map<ClassId, ClassRegistrationSeeds.ClassRegistration> mutatedRegistrations =
                new LinkedHashMap<>(registrations);
            mutatedRegistrations.put(formsClass,
                new ClassRegistrationSeeds.ClassRegistration(
                    new ClassLayout(formsClass, mutated), ConstructionProvider.HOST_DECLARATION));
            Optional<String> failure = ClassConstructionValidator.checkProjectSeedCoherence(
                index, new ClassRegistrationSeeds(mutatedRegistrations));
            check(failure.isPresent() && failure.get().contains("f" + i + "'"),
                "the mutated descriptor of form " + i + " rejects: " + failure);
        }
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Class Construction Validator Tests (ISSUE-0516 K-D11) ===");
        testPositiveCorpus();
        LoweredSlice shared = lowerModule("""
            let base: number = 40;
            export class Point {
              x: number;
              tag: string;
              note?: string;
            }
            let p: Point = {x: 1, tag: "p"};
            """);
        testFactoryNegatives(shared);
        testConstructionNegatives(shared);
        testFieldOperationNegatives();
        testOptionalNullFieldReadArm();
        testFieldLayoutLinkOrigins();
        testJsonNegatives();
        testDetailsDumpDeterminism();
        testFailureRangeOrigins(shared);
        testProjectSeedCoherence();
        testProjectSeedModuleCoherence();
        testSeedOptionalNullFieldOperation();
        testSeedDeclaredTypeRenderingAgainstProducers();
        System.out.println();
        if (failed == 0) {
            System.out.println("ClassConstructionValidatorTest: " + passed + " passed, "
                + failed + " failed");
        } else {
            System.out.println("ClassConstructionValidatorTest: " + passed + " passed, "
                + failed + " failed");
            System.exit(1);
        }
    }
}
