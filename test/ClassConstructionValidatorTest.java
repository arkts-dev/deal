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
import deal.semantic.ir.DefaultOwner;
import deal.semantic.ir.ExportInterface;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.ExternalModuleKind;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FieldInterface;
import deal.semantic.ir.InitializationMode;
import deal.semantic.ir.JsonDefaultChildTable;
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
            ops, unit.bodyTable(), unit.classFactories(), unit.jsonDefaults(),
            unit.classBindingFacts());
    }

    private static LoweredModuleUnit unitWithLayouts(LoweredModuleUnit unit,
                                                     Map<ClassId, ClassLayout> layouts) {
        return new LoweredModuleUnit(unit.formatVersion(), unit.semanticProfile(),
            unit.moduleId(), unit.interfaceHash(), unit.loweringContextHash(),
            unit.requiredCapabilities(), unit.constructCoverage(), layouts,
            unit.functions(), unit.moduleInit(), unit.exportPlan(), unit.functionBindings(),
            unit.ops(), unit.bodyTable(), unit.classFactories(), unit.jsonDefaults(),
            unit.classBindingFacts());
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
              x: int = 40 + 2;
              tag: string = "p";
              note?: string;
            }
            // @jsonable
            export class Inner { n: int = 7; }
            // @jsonable
            export class Holder { home: Point; opt?: Inner; }
            let p: Point = {x: 1};
            let q: Point = {};
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
            result.jsonDefaults(), lowered.ownInterface(), Map.of());
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
            new ClassFactoryRegistry(Map.of()), result.jsonDefaults(),
            lowered.ownInterface(), Map.of()), ClassConstructionValidator
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
            result.jsonDefaults(), lowered.ownInterface(), Map.of()),
            ClassConstructionValidator.FACTORY_COHERENCE);

        // (c) Reordered factory classDefaultOpIds.
        SemanticOp factory = firstOfKind(unit, SemanticOpKind.CLASS_FACTORY);
        KindPayload.ClassFactoryPayload factoryPayload =
            (KindPayload.ClassFactoryPayload) factory.payload();
        List<OpId> reversed = new ArrayList<>(factoryPayload.classDefaultOpIds());
        java.util.Collections.reverse(reversed);
        SemanticOp tampered = rebuild(factory,
            new KindPayload.ClassFactoryPayload(factoryPayload.classId(), reversed,
                factoryPayload.callerOpRef()));
        expectE6005(ClassConstructionValidator.validate(unitWithOps(unit, replaceOp(unit, tampered)),
            result.lowering().table(), result.registry(), result.jsonDefaults(),
            lowered.ownInterface(), Map.of()),
            ClassConstructionValidator.FACTORY_COHERENCE);

        // (d) A parented factory (the detached static shape broken).
        expectE6005(ClassConstructionValidator.validate(
            unitWithOps(unit, replaceOp(unit, withParent(factory, factory.opId()))),
            result.lowering().table(), result.registry(), result.jsonDefaults(),
            lowered.ownInterface(), Map.of()),
            ClassConstructionValidator.FACTORY_COHERENCE);

        // (e) A duplicate CLASS_DEFAULT op: clone one default op under a
        // fresh op id.
        SemanticOp firstDefault = firstOfKind(unit, SemanticOpKind.CLASS_DEFAULT);
        KindPayload.ClassDefaultPayload defaultPayload =
            (KindPayload.ClassDefaultPayload) firstDefault.payload();
        KindPayload.ClassDefaultPayload clonePayload =
            new KindPayload.ClassDefaultPayload(defaultPayload.classId(),
                defaultPayload.field(), defaultPayload.defaultBlock());
        String cloneDigest = ContractSnapshotCanonicalizer.digest(contractFor(
            firstDefault, clonePayload, "placeholder"));
        SemanticOp clone = new SemanticOp(new OpId(MODULE, 99_998), firstDefault.kind(),
            new SourceOrigin(firstDefault.origin().sourceId(), firstDefault.origin().span(),
                SourceOriginKind.SYNTHETIC, new AnchorId(1), null),
            new ValueId(99_998), firstDefault.resultType(), firstDefault.operands(),
            firstDefault.operandTypes(), clonePayload, FailurePolicyId.NO_DEAL_FAILURE,
            contractFor(firstDefault, clonePayload, cloneDigest));
        List<SemanticOp> withClone = new ArrayList<>(unit.ops());
        withClone.add(clone);
        expectE6005(ClassConstructionValidator.validate(unitWithOps(unit, withClone),
            result.lowering().table(), result.registry(), result.jsonDefaults(),
            lowered.ownInterface(), Map.of()),
            ClassConstructionValidator.FACTORY_COHERENCE);
    }

    private static List<SemanticOp> replaceOp(LoweredModuleUnit unit, SemanticOp replacement) {
        List<SemanticOp> ops = new ArrayList<>(unit.ops());
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i).opId().equals(replacement.opId())) {
                ops.set(i, replacement);
                return ops;
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
                    payload.providedFields(), payload.defaultOwner(),
                    payload.classDefaultOpIds(), payload.classFactoryRef(),
                    payload.fieldBoundaries())))),
            result.lowering().table(), result.registry(), result.jsonDefaults(),
            lowered.ownInterface(), Map.of()),
            ClassConstructionValidator.CONSTRUCTION_COHERENCE);

        // (b) An undeclared provided field.
        List<KindPayload.ProvidedField> extraProvided =
            new ArrayList<>(payload.providedFields());
        extraProvided.add(new KindPayload.ProvidedField("zzz", new ValueId(1)));
        expectE6005(ClassConstructionValidator.validate(unitWithOps(unit,
            replaceOp(unit, rebuild(classNew,
                new KindPayload.ClassNewPayload(payload.classId(), payload.layout(),
                    extraProvided, payload.defaultOwner(), payload.classDefaultOpIds(),
                    payload.classFactoryRef(), payload.fieldBoundaries())))),
            result.lowering().table(), result.registry(), result.jsonDefaults(),
            lowered.ownInterface(), Map.of()),
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
                        payload.providedFields(), payload.defaultOwner(),
                        payload.classDefaultOpIds(), payload.classFactoryRef(),
                        swapped)))),
                result.lowering().table(), result.registry(), result.jsonDefaults(),
                lowered.ownInterface(), Map.of()),
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
                result.registry(), result.jsonDefaults(), lowered.ownInterface(),
                Map.of()), ClassConstructionValidator.CONSTRUCTION_COHERENCE);
        }

        // (e) A wrong boundary kind (CLASS_LITERAL_FIELD becomes
        // CLASS_DEFAULT_FIELD at a provided position).
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
                    ? BoundaryKind.CLASS_DEFAULT_FIELD
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
                    result.registry(), result.jsonDefaults(), lowered.ownInterface(),
                    Map.of()), ClassConstructionValidator.CONSTRUCTION_COHERENCE);
                // The payload kind and the child kind both tampered
                // against the pinned expectation.
                expectE6005(ClassConstructionValidator.validate(unitWithOps(unit,
                    replaceOp(unit, rebuild(classNew,
                        new KindPayload.ClassNewPayload(payload.classId(),
                            payload.layout(), payload.providedFields(),
                            payload.defaultOwner(), payload.classDefaultOpIds(),
                            payload.classFactoryRef(), wrongEntries)))),
                    result.lowering().table(), result.registry(), result.jsonDefaults(),
                    lowered.ownInterface(), Map.of()),
                    ClassConstructionValidator.CONSTRUCTION_COHERENCE);
            }
        }

        // (g) A RETAINED_ABI owner (E10's shape, never produced here).
        expectE6005(ClassConstructionValidator.validate(unitWithOps(unit,
            replaceOp(unit, rebuild(classNew,
                new KindPayload.ClassNewPayload(payload.classId(), payload.layout(),
                    payload.providedFields(), DefaultOwner.RETAINED_ABI, List.of(),
                    new ClassFactoryId(78), payload.fieldBoundaries())))),
            result.lowering().table(), result.registry(), result.jsonDefaults(),
            lowered.ownInterface(), Map.of()),
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
              x: int;
              y?: int = 0;
            }
            let p: Point = {x: 1};
            let v: int | null = p.y;
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
                result.lowering().table(), result.registry(), result.jsonDefaults(),
                lowered.ownInterface(), Map.of()),
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
                result.registry(), result.jsonDefaults(), lowered.ownInterface(),
                Map.of()), ClassConstructionValidator.FIELD_OPERATION_SHAPE);
        }

        // (c) An extra boundary child parented to the read op.
        SemanticOp delete = firstOfKind(unit, SemanticOpKind.FIELD_DELETE);
        if (delete != null) {
            List<SemanticOp> deleteChildren = childrenOf(unit, delete);
            if (!deleteChildren.isEmpty()) {
                expectE6005(ClassConstructionValidator.validate(unitWithOps(unit,
                    replaceOp(unit, withParent(deleteChildren.get(0), read.opId()))),
                    result.lowering().table(), result.registry(), result.jsonDefaults(),
                    lowered.ownInterface(), Map.of()),
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
                result.lowering().table(), result.registry(), result.jsonDefaults(),
                lowered.ownInterface(), Map.of()),
                ClassConstructionValidator.FIELD_OPERATION_SHAPE);
        }
    }

    // =========================================================================
    // 5. DEFAULT_BLOCK_ADMISSION negatives
    // =========================================================================

    private static void testDefaultBlockAdmissionNegatives(LoweredSlice lowered) {
        System.out.println("-- default-block admission negatives: an enclosing-function "
            + "load and a nested capture inside the default closure --");

        SemanticLowerer.ClassDeclarationCoreResult result =
            lowered == null ? null : lowered.result();
        check(result != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null, "the admission baseline lowers");
        if (result == null || result.lowering().hasErrors()
                || result.lowering().unit() == null) {
            return;
        }
        ExternalModuleInterface own = lowered.ownInterface();
        LoweredModuleUnit unit = result.lowering().unit();
        SemanticOp defaultOp = firstOfKind(unit, SemanticOpKind.CLASS_DEFAULT);
        KindPayload.ClassDefaultPayload defaultPayload =
            (KindPayload.ClassDefaultPayload) defaultOp.payload();
        BlockId defaultBlock = defaultPayload.defaultBlock();

        // A foreign binding allocated in a foreign block (a function body
        // the default block does not reach).
        BindingId foreignBinding = new BindingId(97_777);
        BlockId foreignBlock = new BlockId(97_778);
        List<SemanticOp> ops = new ArrayList<>(unit.ops());
        KindPayload.BindingAllocPayload allocPayload =
            new KindPayload.BindingAllocPayload(foreignBinding, foreignBlock, true,
                deal.semantic.ir.BindingCellKind.DIRECT, 0);
        String allocDigest = ContractSnapshotCanonicalizer.digest(new
            OperationContractSnapshot(OperationContractSnapshot.VERSION,
                SemanticOpKind.BINDING_ALLOC, null, List.of(), null, allocPayload,
                FailurePolicyId.NO_DEAL_FAILURE, List.of(), "placeholder"));
        ops.add(new SemanticOp(new OpId(MODULE, 97_776), SemanticOpKind.BINDING_ALLOC,
            new SourceOrigin(SOURCE_ID, SourceSpan.synthetic(SOURCE_ID),
                SourceOriginKind.SYNTHETIC, new AnchorId(2), null),
            null, null, List.of(), List.of(), allocPayload,
            FailurePolicyId.NO_DEAL_FAILURE,
            new OperationContractSnapshot(OperationContractSnapshot.VERSION,
                SemanticOpKind.BINDING_ALLOC, null, List.of(), null, allocPayload,
                FailurePolicyId.NO_DEAL_FAILURE, List.of(), allocDigest)));
        List<SemanticOp> allocatedOps = List.copyOf(ops);
        KindPayload.BindingLoadPayload loadPayload =
            new KindPayload.BindingLoadPayload(foreignBinding, 0);
        String loadDigest = ContractSnapshotCanonicalizer.digest(new
            OperationContractSnapshot(OperationContractSnapshot.VERSION,
                SemanticOpKind.BINDING_LOAD, RuntimeDescriptor.Int.INSTANCE, List.of(),
                null, loadPayload, FailurePolicyId.NO_DEAL_FAILURE, List.of(),
                "placeholder"));
        ops.add(new SemanticOp(new OpId(MODULE, 97_774), SemanticOpKind.BINDING_LOAD,
            new SourceOrigin(SOURCE_ID, SourceSpan.synthetic(SOURCE_ID),
                SourceOriginKind.SYNTHETIC, new AnchorId(3), defaultOp.opId()),
            new ValueId(97_775), RuntimeDescriptor.Int.INSTANCE, List.of(), List.of(),
            loadPayload, FailurePolicyId.NO_DEAL_FAILURE,
            new OperationContractSnapshot(OperationContractSnapshot.VERSION,
                SemanticOpKind.BINDING_LOAD, RuntimeDescriptor.Int.INSTANCE, List.of(),
                null, loadPayload, FailurePolicyId.NO_DEAL_FAILURE, List.of(),
                loadDigest)));
        // The tampered load becomes a member of the default block; the
        // foreign alloc becomes a member of the foreign block.
        Map<BlockId, List<OpId>> blockOps = new LinkedHashMap<>(result.lowering().table()
            .blockOps());
        List<OpId> defaultMembers = new ArrayList<>(blockOps.getOrDefault(defaultBlock,
            List.of()));
        defaultMembers.add(new OpId(MODULE, 97_774));
        blockOps.put(defaultBlock, defaultMembers);
        blockOps.put(foreignBlock, List.of(new OpId(MODULE, 97_776)));
        Map<OpId, BlockId> opBlocks = new LinkedHashMap<>(result.lowering().table()
            .opBlocks());
        opBlocks.put(new OpId(MODULE, 97_774), defaultBlock);
        opBlocks.put(new OpId(MODULE, 97_776), foreignBlock);
        StructuredBodyTable tamperedTable = new StructuredBodyTable(blockOps, opBlocks);
        expectE6005(ClassConstructionValidator.validate(unitWithOps(unit, ops),
            tamperedTable, result.registry(), result.jsonDefaults(), own, Map.of()),
            ClassConstructionValidator.DEFAULT_BLOCK_ADMISSION);

        // A nested closure capture of the foreign binding inside the
        // default block (the capture is the reference, not a load): the
        // closure body is an internal block of the default walk while the
        // captured binding stays allocated in the foreign block.
        BlockId closureBody = new BlockId(97_770);
        RuntimeDescriptor.Func closureSignature =
            new RuntimeDescriptor.Func(List.of(), RuntimeDescriptor.Int.INSTANCE, false);
        KindPayload.ClosureNewPayload closurePayload = new KindPayload.ClosureNewPayload(
            new deal.semantic.ir.FunctionId(97_771), closureSignature,
            List.of(new BindingGeneration(foreignBinding, 0)),
            new deal.semantic.ir.FunctionExecutionBinding.LoweredBody(
                new deal.semantic.ir.FunctionId(97_771), closureBody));
        String closureDigest = ContractSnapshotCanonicalizer.digest(new
            OperationContractSnapshot(OperationContractSnapshot.VERSION,
                SemanticOpKind.CLOSURE_NEW, closureSignature, List.of(), null,
                closurePayload, FailurePolicyId.NO_DEAL_FAILURE, List.of(),
                "placeholder"));
        SemanticOp closure = new SemanticOp(new OpId(MODULE, 97_772),
            SemanticOpKind.CLOSURE_NEW,
            new SourceOrigin(SOURCE_ID, SourceSpan.synthetic(SOURCE_ID),
                SourceOriginKind.SYNTHETIC, new AnchorId(4), defaultOp.opId()),
            new ValueId(97_773), closureSignature, List.of(), List.of(),
            closurePayload, FailurePolicyId.NO_DEAL_FAILURE,
            new OperationContractSnapshot(OperationContractSnapshot.VERSION,
                SemanticOpKind.CLOSURE_NEW, closureSignature, List.of(), null,
                closurePayload, FailurePolicyId.NO_DEAL_FAILURE, List.of(),
                closureDigest));
        Map<BlockId, List<OpId>> captureBlocks = new LinkedHashMap<>(
            result.lowering().table().blockOps());
        captureBlocks.put(foreignBlock, List.of(new OpId(MODULE, 97_776)));
        captureBlocks.put(closureBody, List.of());
        Map<OpId, BlockId> captureInverse = new LinkedHashMap<>(
            result.lowering().table().opBlocks());
        captureInverse.put(new OpId(MODULE, 97_776), foreignBlock);
        StructuredBodyTable allocatedTable = new StructuredBodyTable(captureBlocks,
            captureInverse);
        check(ClassConstructionValidator.validate(unitWithOps(unit, allocatedOps),
            allocatedTable, result.registry(), result.jsonDefaults(), own, Map.of()).isEmpty(),
            "the fresh foreign-allocation seed validates before adding the capture");
        List<SemanticOp> captureOps = new ArrayList<>(allocatedOps);
        captureOps.add(closure);
        List<OpId> captureMembers = new ArrayList<>(captureBlocks.get(defaultBlock));
        captureMembers.add(closure.opId());
        captureBlocks.put(defaultBlock, captureMembers);
        captureInverse.put(closure.opId(), defaultBlock);
        StructuredBodyTable captureTable = new StructuredBodyTable(captureBlocks,
            captureInverse);
        Optional<CompilerDiagnostic> captureFailure = ClassConstructionValidator.validate(
            unitWithOps(unit, captureOps), captureTable, result.registry(),
            result.jsonDefaults(), own, Map.of());
        expectE6005(captureFailure, ClassConstructionValidator.DEFAULT_BLOCK_ADMISSION);
        check(captureFailure.isPresent() && captureFailure.get().message().endsWith(
            admissionSubject(defaultOp, foreignBinding, "sits in block " + foreignBlock)),
            "the nested capture rejects the foreign allocation with the exact admission detail");
        check(captureFailure.isPresent()
                && closure.origin().span().range().equals(captureFailure.get().range()),
            "the nested capture failure carries the closure's own offsetless range");
        KindPayload.ClosureNewPayload admittedCapture = new KindPayload.ClosureNewPayload(
            closurePayload.function(), closurePayload.signature(), List.of(),
            closurePayload.binding());
        check(ClassConstructionValidator.validate(unitWithOps(unit,
            replaceOp(unitWithOps(unit, captureOps), rebuild(closure, admittedCapture))),
            captureTable, result.registry(), result.jsonDefaults(), own, Map.of()).isEmpty(),
            "removing only the foreign capture restores admission");
    }

    // =========================================================================
    // 5b. DEFAULT_BLOCK_ADMISSION origins (ISSUE-0750 review cycle 4)
    // =========================================================================

    /**
     * ISSUE-0750: a rejected binding reference names the owning
     * {@code CLASS_DEFAULT} in its message but carries the referencing
     * operation's own file/span as its diagnostic range — never the
     * enclosing default expression's. All three reference-bearing
     * branches (BINDING_LOAD, CLOSURE_NEW, FUNCTION_ADAPT) are driven
     * with a reference range distinct from the default's, plus the
     * originless-default control whose identifiable reference still
     * supplies the exact range. Codes, messages, and fail-closed
     * behavior are unchanged.
     */
    private static void testDefaultBlockAdmissionReferenceOrigins() {
        System.out.println("-- default-block admission origins: the rejected reference's "
            + "own range survives, not the enclosing CLASS_DEFAULT's --");

        final String source = """
            let base: int = 5;
            let f: () => null = function(): null { };
            export class Point {
              x: int = base + 1;
            }
            """;
        LoweredSlice lowered = lowerModule(source);
        SemanticLowerer.ClassDeclarationCoreResult result =
            lowered == null ? null : lowered.result();
        check(result != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null,
            "the admission-origin slice lowers");
        if (result == null || result.lowering().hasErrors()
                || result.lowering().unit() == null) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        StructuredBodyTable table = result.lowering().table();
        ExternalModuleInterface own = lowered.ownInterface();

        SemanticOp defaultOp = firstOfKind(unit, SemanticOpKind.CLASS_DEFAULT);
        check(defaultOp != null, "the slice produces one CLASS_DEFAULT op");
        if (defaultOp == null) {
            return;
        }
        KindPayload.ClassDefaultPayload defaultPayload =
            (KindPayload.ClassDefaultPayload) defaultOp.payload();
        BlockId defaultBlock = defaultPayload.defaultBlock();
        DiagnosticRange defaultRange = defaultOp.origin().span().range();
        check(defaultRange.origin() == RangeOrigin.SOURCE
                && SOURCE_ID.equals(defaultRange.file())
                && defaultRange.startLine() == 4 && defaultRange.startColumn() == 12
                && defaultRange.endLine() == 4 && defaultRange.endColumn() == 20
                && defaultRange.startScalarOffset() == 93
                && defaultRange.endScalarOffset() == 101,
            "the enclosing CLASS_DEFAULT's own range is the default expression "
                + "4:12-4:20 [93,101); got " + defaultRange);

        Optional<CompilerDiagnostic> baseline = ClassConstructionValidator.validate(unit,
            table, result.registry(), result.jsonDefaults(), own, Map.of());
        check(baseline.isEmpty(), "the untampered slice validates cleanly: " + baseline);

        // The BINDING_LOAD of `base` inside the default expression's
        // block: the referencing op whose own span the failure must use.
        SemanticOp loadOp = null;
        for (SemanticOp member : unit.ops()) {
            if (member.kind() == SemanticOpKind.BINDING_LOAD
                    && defaultBlock.equals(table.opBlocks().get(member.opId()))) {
                loadOp = member;
            }
        }
        check(loadOp != null, "the default block carries the `base` BINDING_LOAD");
        if (loadOp == null) {
            return;
        }
        DiagnosticRange loadRange = loadOp.origin().span().range();
        check(!loadRange.equals(defaultRange),
            "the reference and default spans are distinct (reference " + loadRange
                + ", default " + defaultRange + ")");

        // (a) BINDING_LOAD: the reviewer's probe — a nonexistent binding
        // identity makes the reference inadmissible; the load's own range
        // (4:12-4:16 [93,97)) must be the diagnostic's, not the enclosing
        // default expression's (4:12-4:20 [93,101)).
        BindingId missingBinding = new BindingId(999_999);
        KindPayload.BindingLoadPayload loadPayload =
            (KindPayload.BindingLoadPayload) loadOp.payload();
        SemanticOp tamperedLoad = rebuild(loadOp,
            new KindPayload.BindingLoadPayload(missingBinding, loadPayload.generation()));
        Optional<CompilerDiagnostic> loadFailure = ClassConstructionValidator.validate(
            unitWithOp(unit, loadOp.opId(), tamperedLoad), table, result.registry(),
            result.jsonDefaults(), own, Map.of());
        expectE6005Range(loadFailure, ClassConstructionValidator.DEFAULT_BLOCK_ADMISSION,
            SOURCE_ID, 4, 12, 4, 16, 93, 97);
        if (loadFailure.isPresent()) {
            check(loadFailure.get().range().equals(loadRange),
                "the BINDING_LOAD failure carries the referencing op's own converted "
                    + "span " + loadRange + "; got " + loadFailure.get().range());
            check(!loadFailure.get().range().equals(defaultRange),
                "the BINDING_LOAD failure never substitutes the enclosing default's "
                    + "range " + defaultRange);
            check(loadFailure.get().message().endsWith(admissionSubject(defaultOp,
                    missingBinding, "is not a block member")),
                "the BINDING_LOAD message still names the owning CLASS_DEFAULT "
                    + defaultOp.opId() + " and the rejected binding; got "
                    + loadFailure.get().message());
        }

        // (b) CLOSURE_NEW: the real module-level closure moved into the
        // default closure with one foreign capture — the range is the
        // closure expression's own span (2:21-2:42 [39,60)), not the
        // default's.
        SemanticOp closureOp = firstOfKind(unit, SemanticOpKind.CLOSURE_NEW);
        check(closureOp != null, "the slice produces one CLOSURE_NEW op");
        if (closureOp != null) {
            KindPayload.ClosureNewPayload closurePayload =
                (KindPayload.ClosureNewPayload) closureOp.payload();
            BindingId foreignBinding = new BindingId(97_777);
            SemanticOp tamperedClosure = rebuild(closureOp,
                new KindPayload.ClosureNewPayload(closurePayload.function(),
                    closurePayload.signature(),
                    List.of(new BindingGeneration(foreignBinding, 0)),
                    closurePayload.binding()));
            Optional<CompilerDiagnostic> closureFailure =
                ClassConstructionValidator.validate(
                    unitWithOp(unit, closureOp.opId(), tamperedClosure),
                    tableWithOpBlock(table, closureOp.opId(), defaultBlock),
                    result.registry(), result.jsonDefaults(), own, Map.of());
            expectE6005Range(closureFailure,
                ClassConstructionValidator.DEFAULT_BLOCK_ADMISSION,
                SOURCE_ID, 2, 21, 2, 42, 39, 60);
            if (closureFailure.isPresent()) {
                check(closureFailure.get().range()
                        .equals(closureOp.origin().span().range()),
                    "the CLOSURE_NEW failure carries the capturing closure's own "
                        + "span; got " + closureFailure.get().range());
                check(!closureFailure.get().range().equals(defaultRange),
                    "the CLOSURE_NEW failure never substitutes the enclosing "
                        + "default's range " + defaultRange);
            }
        }

        // (c) FUNCTION_ADAPT(REEVALUATE_THUNK) with one foreign capture:
        // the seeded reference op's own range (2:21-2:42 [38,59)) wins.
        BlockId thunkBlock = new BlockId(97_780);
        BindingId adaptForeign = new BindingId(97_781);
        RuntimeDescriptor.Func adaptSignature = new RuntimeDescriptor.Func(
            List.of(), RuntimeDescriptor.Int.INSTANCE, false);
        KindPayload.FunctionAdaptPayload adaptPayload =
            new KindPayload.FunctionAdaptPayload(adaptSignature, adaptSignature,
                CaptureMode.REEVALUATE_THUNK,
                new AdaptSourceRef.Thunk(thunkBlock,
                    List.of(new BindingGeneration(adaptForeign, 0))), null);
        SemanticOp adaptOp = seedOp(SemanticOpKind.FUNCTION_ADAPT,
            new OpId(MODULE, 97_782),
            new SourceOrigin(SOURCE_ID, new SourceSpan(SOURCE_ID, 2, 21, 2, 41, 38, 59),
                SourceOriginKind.USER, new AnchorId(97_783), defaultOp.opId()),
            new ValueId(97_784), adaptSignature, adaptPayload);
        List<SemanticOp> adaptOps = new ArrayList<>(unit.ops());
        adaptOps.add(adaptOp);
        Optional<CompilerDiagnostic> adaptFailure = ClassConstructionValidator.validate(
            unitWithOps(unit, adaptOps),
            tableWithOpBlockAndBlock(table, adaptOp.opId(), defaultBlock, thunkBlock),
            result.registry(), result.jsonDefaults(), own, Map.of());
        expectE6005Range(adaptFailure, ClassConstructionValidator.DEFAULT_BLOCK_ADMISSION,
            SOURCE_ID, 2, 21, 2, 42, 38, 59);
        if (adaptFailure.isPresent()) {
            check(adaptFailure.get().range().equals(adaptOp.origin().span().range()),
                "the FUNCTION_ADAPT failure carries the capturing adapter's own "
                    + "span; got " + adaptFailure.get().range());
            check(!adaptFailure.get().range().equals(defaultRange),
                "the FUNCTION_ADAPT failure never substitutes the enclosing "
                    + "default's range " + defaultRange);
            check(adaptFailure.get().message().endsWith(admissionSubject(defaultOp,
                    adaptForeign, "is not a block member")),
                "the FUNCTION_ADAPT message still names the owning CLASS_DEFAULT "
                    + defaultOp.opId() + " and the rejected binding; got "
                    + adaptFailure.get().message());
        }

        // (d) Originless-default control: a synthetic empty-file
        // CLASS_DEFAULT origin with an identifiable reference still
        // yields the reference's exact SOURCE range — the diagnostic never
        // borrows the absent default anchor.
        SemanticOp originlessDefault = withOrigin(defaultOp,
            new SourceOrigin(SOURCE_ID, SourceSpan.synthetic(""),
                SourceOriginKind.SYNTHETIC, defaultOp.origin().anchorId(), null));
        List<SemanticOp> originlessOps = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            if (op.opId().equals(defaultOp.opId())) {
                originlessOps.add(originlessDefault);
            } else if (op.opId().equals(loadOp.opId())) {
                originlessOps.add(tamperedLoad);
            } else {
                originlessOps.add(op);
            }
        }
        Optional<CompilerDiagnostic> originlessFailure =
            ClassConstructionValidator.validate(unitWithOps(unit, originlessOps), table,
                result.registry(), result.jsonDefaults(), own, Map.of());
        expectE6005Range(originlessFailure,
            ClassConstructionValidator.DEFAULT_BLOCK_ADMISSION,
            SOURCE_ID, 4, 12, 4, 16, 93, 97);
        if (originlessFailure.isPresent()) {
            check(!originlessFailure.get().range().isCanonicalSynthetic(),
                "the originless-default failure does not fall back to the canonical "
                    + "empty-file synthetic range; got " + originlessFailure.get().range());
            check(originlessFailure.get().message().endsWith(admissionSubject(defaultOp,
                    missingBinding, "is not a block member")),
                "the originless-default message still names the owning CLASS_DEFAULT "
                    + defaultOp.opId() + " and the rejected binding; got "
                    + originlessFailure.get().message());
        }
    }

    /** The exact parenthesized subject tail of a DEFAULT_BLOCK_ADMISSION message. */
    private static String admissionSubject(SemanticOp defaultOp, BindingId binding,
                                           String reason) {
        KindPayload.ClassDefaultPayload payload =
            (KindPayload.ClassDefaultPayload) defaultOp.payload();
        return "(CLASS_DEFAULT " + defaultOp.opId() + " of " + payload.classId() + "."
            + payload.field() + " references binding " + binding
            + " whose producing allocation " + reason
            + ": a default block admits only allocations inside the default closure "
            + "itself and module-level bindings resolved through the module-init "
            + "block (an enclosing-region free reference is invalid — K-D3))";
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
              x: int = 40 + 2;
              tag: string = "p";
            }
            // @jsonable
            export class Inner { n: int = 7; }
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

        // (a) A dropped per-site default child (the table entry loses its
        // second child).
        List<OpId> children = result.jsonDefaults().childrenOf(fromJson.opId());
        if (children != null && children.size() >= 2) {
            Map<OpId, List<OpId>> entries = new LinkedHashMap<>(
                result.jsonDefaults().defaultChildren());
            List<OpId> dropped = new ArrayList<>(children);
            dropped.remove(dropped.size() - 1);
            entries.put(fromJson.opId(), dropped);
            expectE6005(ClassConstructionValidator.validate(unit, result.lowering().table(),
                result.registry(), new JsonDefaultChildTable(entries),
                lowered.ownInterface(), Map.of()),
                ClassConstructionValidator.JSON_LAYOUT_COHERENCE);
        }

        // (b) An orphan table entry naming a non-JSON op.
        SemanticOp someDefault = firstOfKind(unit, SemanticOpKind.CLASS_DEFAULT);
        if (someDefault != null) {
            expectE6005(ClassConstructionValidator.validate(unit, result.lowering().table(),
                result.registry(),
                new JsonDefaultChildTable(Map.of(someDefault.opId(), List.of())),
                lowered.ownInterface(), Map.of()),
                ClassConstructionValidator.JSON_LAYOUT_COHERENCE);
        }

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
                true, DefaultOwner.LOCAL));
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
                result.lowering().table(), result.registry(), result.jsonDefaults(),
                lowered.ownInterface(), Map.of()),
                ClassConstructionValidator.JSON_LAYOUT_COHERENCE);
        }

        // (d) A bytes-typed jsonable field (hand-rebuilt layout): the
        // payload layout and the unit layout both carry the bytes field.
        ClassId holderId = new ClassId(MODULE.path(), "Holder");
        ClassLayout holderLayout = unit.classLayouts().get(holderId);
        if (holderLayout != null) {
            List<ClassLayout.FieldLayout> fields = new ArrayList<>(holderLayout.fields());
            fields.add(new ClassLayout.FieldLayout("raw", RuntimeDescriptor.Bytes.INSTANCE,
                true, DefaultOwner.LOCAL));
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
                result.lowering().table(), result.registry(), result.jsonDefaults(),
                lowered.ownInterface(), Map.of()),
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
              x: int = 40 + 2;
              tag: string = "p";
              note?: string;
            }
            let p: Point = {x: 1};
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
                case CLASS_NEW, CLASS_FACTORY, CLASS_DEFAULT, FIELD_READ, FIELD_WRITE,
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
            result.lowering().table(), result.registry(), result.jsonDefaults(), own,
            Map.of());
        Optional<CompilerDiagnostic> second = ClassConstructionValidator.validate(unit,
            result.lowering().table(), result.registry(), result.jsonDefaults(), own,
            Map.of());
        check(first.isEmpty() && second.isEmpty(),
            "the direct validation is deterministic (both runs empty)");
        check(unit.ops().size() == result.lowering().unit().ops().size()
                && result.registry().factories().equals(result.registry().factories()),
            "the validator mutates neither the unit nor the records");

        // Null-argument rejection.
        expectNpe(() -> ClassConstructionValidator.validate(null,
            result.lowering().table(), result.registry(), result.jsonDefaults(), own,
            Map.of()), "null unit");
        expectNpe(() -> ClassConstructionValidator.validate(unit, null,
            result.registry(), result.jsonDefaults(), own, Map.of()), "null table");
        expectNpe(() -> ClassConstructionValidator.validate(unit,
            result.lowering().table(), null, result.jsonDefaults(), own, Map.of()),
            "null factory registry");
        expectNpe(() -> ClassConstructionValidator.validate(unit,
            result.lowering().table(), result.registry(), null, own, Map.of()),
            "null json defaults");
        expectNpe(() -> ClassConstructionValidator.validate(unit,
            result.lowering().table(), result.registry(), result.jsonDefaults(), null,
            Map.of()), "null own interface");
        expectNpe(() -> ClassConstructionValidator.validate(unit,
            result.lowering().table(), result.registry(), result.jsonDefaults(), own,
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
              x: int = 40 + 2;
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

        // (a) A duplicate CLASS_DEFAULT op (fresh ids, the offending op's
        // own origin retained): the factory-coherence failure carries the
        // default expression's exact SOURCE range.
        SemanticOp firstDefault = firstOfKind(unit, SemanticOpKind.CLASS_DEFAULT);
        KindPayload.ClassDefaultPayload defaultPayload =
            (KindPayload.ClassDefaultPayload) firstDefault.payload();
        KindPayload.ClassDefaultPayload clonePayload =
            new KindPayload.ClassDefaultPayload(defaultPayload.classId(),
                defaultPayload.field(), defaultPayload.defaultBlock());
        String cloneDigest = ContractSnapshotCanonicalizer.digest(contractFor(
            firstDefault, clonePayload, "placeholder"));
        SemanticOp clone = new SemanticOp(new OpId(MODULE, 99_999), firstDefault.kind(),
            firstDefault.origin(), new ValueId(99_999), firstDefault.resultType(),
            firstDefault.operands(), firstDefault.operandTypes(), clonePayload,
            FailurePolicyId.NO_DEAL_FAILURE,
            contractFor(firstDefault, clonePayload, cloneDigest));
        List<SemanticOp> withClone = new ArrayList<>(unit.ops());
        withClone.add(clone);
        expectE6005Range(ClassConstructionValidator.validate(unitWithOps(unit, withClone),
            result.lowering().table(), result.registry(), result.jsonDefaults(),
            lowered.ownInterface(), Map.of()),
            ClassConstructionValidator.FACTORY_COHERENCE,
            SOURCE_ID, 2, 12, 2, 18, 32, 38);

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
                        DefaultOwner.LOCAL, newPayload.classDefaultOpIds(),
                        new ClassFactoryId(77), newPayload.fieldBoundaries())))),
                    constructionResult.lowering().table(), constructionResult.registry(),
                    constructionResult.jsonDefaults(), constructionLowered.ownInterface(),
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
            result.jsonDefaults(), lowered.ownInterface(), Map.of());
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
    private static final ModuleId NATIVE_MATH = new ModuleId("native.math");
    private static final ClassId VEC2 =
        new ClassId(EXTERNAL_PREFIX + "native/math", "Vec2");

    /**
     * The transported declaration facts of a host declaration module: two
     * exported classes with every declared-type form (scalars, optional,
     * nullable, array, function, same-module class, Error) and one extern-C
     * style declaration module, plus an implementation module class the
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
                    List.of(new FieldInterface("path", "string", false, false, false),
                        new FieldInterface("port", "int", true, false, false),
                        new FieldInterface("note", "string | null", true, true, false),
                        new FieldInterface("tags", "string[]", true, false, false),
                        new FieldInterface("cb", "((int) => string) | null", true, true,
                            false),
                        new FieldInterface("peer", "@host.facade/Other", false, false,
                            false),
                        new FieldInterface("others", "(@host.facade/Other | null)[]", true,
                            false, false),
                        new FieldInterface("failure", "Error", false, false, false)),
                    new ClassFactoryId(3)),
                new ClassInterface(FACADE_OTHER_DECLARED,
                    List.of(new FieldInterface("count", "int", false, false, true)),
                    new ClassFactoryId(4))),
            InitializationMode.ONCE_AFTER_DEPENDENCIES));
        modules.put(NATIVE_MATH, new ExternalModuleInterface(NATIVE_MATH,
            ExternalModuleKind.HOST, List.of(), List.of(),
            List.of(new ClassInterface(new ClassId(NATIVE_MATH.path(), "Vec2"),
                List.of(new FieldInterface("x", "number", false, false, true)),
                new ClassFactoryId(5))),
            InitializationMode.ONCE_AFTER_DEPENDENCIES));
        modules.put(MODULE, new ExternalModuleInterface(MODULE,
            ExternalModuleKind.IMPLEMENTATION, List.of(), List.of(),
            List.of(new ClassInterface(new ClassId("src", "Point"),
                List.of(new FieldInterface("x", "int", false, false, true)),
                new ClassFactoryId(6))),
            InitializationMode.ONCE_AFTER_DEPENDENCIES));
        return new ProjectInterfaceIndex(ProjectInterfaceIndex.FORMAT_VERSION, modules);
    }

    private static ClassLayout.FieldLayout seedField(String name,
            RuntimeDescriptor descriptor, boolean required) {
        return seedField(name, descriptor, required, DefaultOwner.HOST_DEFAULTS);
    }

    private static ClassLayout.FieldLayout seedField(String name,
            RuntimeDescriptor descriptor, boolean required, DefaultOwner owner) {
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
                    seedField("port", RuntimeDescriptor.Int.INSTANCE, false),
                    seedField("note", new RuntimeDescriptor.Nullable(
                        RuntimeDescriptor.String.INSTANCE), false),
                    seedField("tags", new RuntimeDescriptor.Array(
                        RuntimeDescriptor.String.INSTANCE), false),
                    seedField("cb", new RuntimeDescriptor.Nullable(
                        new RuntimeDescriptor.Func(List.of(RuntimeDescriptor.Int.INSTANCE),
                            RuntimeDescriptor.String.INSTANCE)), false),
                    seedField("peer", new RuntimeDescriptor.Class(FACADE_OTHER), true),
                    seedField("others", new RuntimeDescriptor.Array(
                        new RuntimeDescriptor.Nullable(
                            new RuntimeDescriptor.Class(FACADE_OTHER))), false),
                    seedField("failure", new RuntimeDescriptor.Class(ClassId.ERROR), true))),
                DefaultOwner.HOST_DEFAULTS));
        registrations.put(FACADE_OTHER,
            new ClassRegistrationSeeds.ClassRegistration(
                new ClassLayout(FACADE_OTHER,
                    List.of(seedField("count", RuntimeDescriptor.Int.INSTANCE, true))),
                DefaultOwner.HOST_DEFAULTS));
        registrations.put(VEC2,
            new ClassRegistrationSeeds.ClassRegistration(
                new ClassLayout(VEC2,
                    List.of(seedField("x", RuntimeDescriptor.Number.INSTANCE, true,
                        DefaultOwner.FFI_PLAN))),
                DefaultOwner.FFI_PLAN));
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
                    List.of(seedField("hidden", RuntimeDescriptor.Int.INSTANCE, true))),
                DefaultOwner.HOST_DEFAULTS));
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
                    List.of(seedField("stray", RuntimeDescriptor.Int.INSTANCE, true))),
                DefaultOwner.HOST_DEFAULTS));
        check(ClassConstructionValidator.checkProjectSeedCoherence(index,
                new ClassRegistrationSeeds(withEmptyModulePath)).isEmpty(),
            "a registration key with an empty externals suffix is unresolvable, not a "
                + "crash");

        // Every declared-type form: a mutated descriptor contradicts the
        // interface's declared type.
        check(seedCoherenceFailure(index, withEndpointField("path",
                RuntimeDescriptor.Int.INSTANCE, true)).contains("path"),
            "a scalar seed field descriptor of another type rejects");
        check(seedCoherenceFailure(index, withEndpointField("note",
                RuntimeDescriptor.String.INSTANCE, false)).contains("note"),
            "a nullable seed field descriptor without its nullable form rejects");
        check(seedCoherenceFailure(index, withEndpointField("tags",
                new RuntimeDescriptor.Array(RuntimeDescriptor.Int.INSTANCE), false))
                .contains("tags"),
            "an array seed field descriptor of another element type rejects");
        check(seedCoherenceFailure(index, withEndpointField("cb",
                new RuntimeDescriptor.Func(List.of(RuntimeDescriptor.Int.INSTANCE),
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
            fields.add(new FieldInterface("hidden", "@host.facade/Hidden", false, false,
                false));
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
                DefaultOwner.HOST_DEFAULTS));
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
                DefaultOwner.HOST_DEFAULTS));
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
                        RuntimeDescriptor.String.INSTANCE, true, DefaultOwner.LOCAL))),
                DefaultOwner.LOCAL));
        check(seedCoherenceFailure(index, new ClassRegistrationSeeds(localOwner))
                .contains("owner"),
            "a declaration registration under the LOCAL owner rejects");

        Map<ClassId, ClassRegistrationSeeds.ClassRegistration> implementationSeed =
            new LinkedHashMap<>(seeds.registrations());
        implementationSeed.put(new ClassId("src", "Point"),
            new ClassRegistrationSeeds.ClassRegistration(
                new ClassLayout(new ClassId("src", "Point"), List.of(
                    seedField("x", RuntimeDescriptor.Int.INSTANCE, true))),
                DefaultOwner.HOST_DEFAULTS));
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
                        DefaultOwner.HOST_DEFAULTS))),
                DefaultOwner.HOST_DEFAULTS));
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
        ClassRegistrationSeeds.ClassRegistration vec2 = seeds.registrationFor(VEC2);

        // Positive: the referring CLASS_NEW repeats its registration exactly,
        // under either declaration owner, and an unrelated unit is unaffected.
        check(ClassConstructionValidator.checkProjectSeedModuleCoherence(seeds,
                List.of(unitWithClassNew(FACADE_ENDPOINT, DefaultOwner.HOST_DEFAULTS,
                    endpoint.layout()))).isEmpty(),
            "a CLASS_NEW repeating its HOST_DEFAULTS registration passes the join");
        check(ClassConstructionValidator.checkProjectSeedModuleCoherence(seeds,
                List.of(unitWithClassNew(VEC2, DefaultOwner.FFI_PLAN, vec2.layout())))
                .isEmpty(),
            "a CLASS_NEW repeating its FFI_PLAN registration passes the join");
        check(ClassConstructionValidator.checkProjectSeedModuleCoherence(seeds,
                List.of(unitWithOps(List.of()))).isEmpty(),
            "a unit without declaration-class constructions passes the join");
        check(ClassConstructionValidator.checkProjectSeedModuleCoherence(seeds,
                List.of(unitWithClassNew(FACADE_ENDPOINT, DefaultOwner.HOST_DEFAULTS,
                    endpoint.layout()), unitWithClassNew(FACADE_OTHER,
                    DefaultOwner.HOST_DEFAULTS,
                    seeds.registrationFor(FACADE_OTHER).layout()))).isEmpty(),
            "two declaration-class constructions in supplied unit order pass the join");

        // Negative: the internally consistent owner mutation (a declaration
        // owner the index/seed rule accepts) contradicts the referring
        // operation, and so do a missing registration and a foreign layout.
        Optional<ClassConstructionValidator.SeedModuleViolation> owner =
            ClassConstructionValidator.checkProjectSeedModuleCoherence(seeds,
                List.of(unitWithClassNew(FACADE_ENDPOINT, DefaultOwner.FFI_PLAN,
                    endpoint.layout())));
        check(owner.isPresent() && owner.get().detail().contains("defaultOwner")
                && owner.get().detail().contains("HOST_DEFAULTS")
                && owner.get().detail().contains("FFI_PLAN"),
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
                List.of(unitWithClassNew(FACADE_ENDPOINT, DefaultOwner.HOST_DEFAULTS,
                    endpoint.layout())));
        check(missing.isPresent()
                && missing.get().detail().contains("no class registration seed"),
            "a CLASS_NEW of an unregistered declaration class rejects: " + missing);

        ClassLayout foreign = new ClassLayout(FACADE_ENDPOINT,
            List.of(seedField("path", RuntimeDescriptor.Int.INSTANCE, true)));
        Optional<ClassConstructionValidator.SeedModuleViolation> layout =
            ClassConstructionValidator.checkProjectSeedModuleCoherence(seeds,
                List.of(unitWithClassNew(FACADE_ENDPOINT, DefaultOwner.HOST_DEFAULTS,
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

    /** One raw unit carrying exactly the given raw operations. */
    private static RawUnit unitWithOps(List<RawOp> ops) {
        return new RawUnit(MODULE.path(), SemanticProfile.DEAL_V1_3_INT32.name(),
            "interface-digest-1", LoweringContextHash.of(SemanticProfile.DEAL_V1_3_INT32,
                REGISTRY_HASH),
            List.of(), List.of(), List.of(), List.of(),
            new ModuleInitPlan(List.of(), new BlockId(0)), List.of(), List.of(), ops,
            new StructuredBodyTable(Map.of(), Map.of()),
            new ClassFactoryRegistry(Map.of()), new JsonDefaultChildTable(Map.of()),
            ClassBindingFacts.empty());
    }

    /** One raw unit carrying a single CLASS_NEW of the given class. */
    private static RawUnit unitWithClassNew(ClassId classId, DefaultOwner owner,
            ClassLayout layout) {
        return unitWithOps(List.of(rawClassNew(owner.name(), classId, layout)));
    }

    /** One raw CLASS_NEW operation payload naming the class, owner and layout. */
    private static RawOp rawClassNew(String owner, ClassId classId, ClassLayout layout) {
        CanonicalJson.Obj payload = CanonicalJson.obj(
            CanonicalJson.e("classId",
                ContractSnapshotCanonicalizer.semanticIdJson(classId)),
            CanonicalJson.e("defaultOwner", CanonicalJson.str(owner)),
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
            Type.Int.INSTANCE,
            Type.Boolean.INSTANCE,
            Type.Number.INSTANCE,
            Type.String.INSTANCE,
            Type.Bytes.INSTANCE,
            Type.Table.INSTANCE,
            Type.Null.INSTANCE,
            Types.nullable(Type.String.INSTANCE),
            Types.array(Type.Int.INSTANCE),
            Types.array(Types.nullable(Type.Int.INSTANCE)),
            Types.nullable(Types.array(Type.String.INSTANCE)),
            Types.func(List.of(Type.Int.INSTANCE), Type.String.INSTANCE),
            Types.func(List.of(Types.func(List.of(Type.Int.INSTANCE),
                Type.String.INSTANCE), Type.Int.INSTANCE), Type.String.INSTANCE),
            Types.func(List.of(Types.nullable(Type.Int.INSTANCE)),
                Type.String.INSTANCE),
            Types.func(List.of(Type.Int.INSTANCE), Types.array(Type.Bytes.INSTANCE),
                true),
            Types.array(Types.func(List.of(Type.Int.INSTANCE), Type.String.INSTANCE)),
            Types.nullable(Types.func(List.of(Type.Int.INSTANCE), Type.String.INSTANCE)),
            Types.array(Types.array(Type.Int.INSTANCE)));
        ModuleId formsModule = new ModuleId("forms.host");
        ClassId formsClass = new ClassId(EXTERNAL_PREFIX + "forms/host", "Forms");
        List<FieldInterface> declaredFields = new ArrayList<>();
        List<ClassLayout.FieldLayout> seedFields = new ArrayList<>();
        for (int i = 0; i < forms.size(); i++) {
            declaredFields.add(new FieldInterface("f" + i,
                CanonicalTypeText.render(forms.get(i)), false, false, false));
            seedFields.add(new ClassLayout.FieldLayout("f" + i,
                DescriptorService.describe(forms.get(i)), true, DefaultOwner.HOST_DEFAULTS));
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
                new ClassLayout(formsClass, seedFields), DefaultOwner.HOST_DEFAULTS));
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
                DefaultOwner.HOST_DEFAULTS));
            Map<ClassId, ClassRegistrationSeeds.ClassRegistration> mutatedRegistrations =
                new LinkedHashMap<>(registrations);
            mutatedRegistrations.put(formsClass,
                new ClassRegistrationSeeds.ClassRegistration(
                    new ClassLayout(formsClass, mutated), DefaultOwner.HOST_DEFAULTS));
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
            let base: int = 40;
            export class Point {
              x: int = base + 2;
              tag: string = "p";
              note?: string;
            }
            let p: Point = {x: 1};
            """);
        testFactoryNegatives(shared);
        testConstructionNegatives(shared);
        testFieldOperationNegatives();
        testDefaultBlockAdmissionNegatives(shared);
        testDefaultBlockAdmissionReferenceOrigins();
        testJsonNegatives();
        testDetailsDumpDeterminism();
        testFailureRangeOrigins(shared);
        testProjectSeedCoherence();
        testProjectSeedModuleCoherence();
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
