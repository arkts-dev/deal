package deal.test;

import deal.ast.ClassDeclaration;
import deal.ast.ExportDeclaration;
import deal.ast.ProgramNode;
import deal.ast.StatementNode;
import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.NameResolver;
import deal.checker.Symbol;
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
import deal.semantic.SemanticOracle;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ClassInterface;
import deal.semantic.ir.ClassLayout;
import deal.semantic.ir.ClassOpsExecutor;
import deal.semantic.ir.ConstructKind;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.ExternalModuleKind;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.OperationContractSnapshot;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.ResolvedImport;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticIdAllocator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.SharedFactoryFacts;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ISSUE-0586 class-construction differential corpus (sequencing step
 * 8, first slice — E5 core): the {@code CLASS_DEFAULT}/
 * {@code CLASS_NEW}/{@code CLASS_FACTORY} arms of the semantic oracle
 * and both shared emitters, verified green three-way over the
 * production lowerer's class arms (the
 * {@code ClassConstructionIntegrationTailTest} pipeline pattern — real
 * reachable IR, never source presence).
 *
 * <p><b>Positive seeds.</b> (a) provided/default order — full, partial,
 * empty, and reordered literals over a defaulted class; (b)
 * mutable-default freshness — two constructions of an array/table
 * defaulted class allocate fresh identities per attempt; (c) the E8007
 * extra-key pin — the first unknown provided name fails
 * {@code CLASS_CONSTRUCTION} at the op origin after the completed
 * default application; (d) present-null-vs-missing instance states —
 * {@code {note: null}} stores present null, {@code {}} stores missing,
 * observed through the {@code has()} presence boolean; (e) the
 * cross-module factory — the owner's {@code CLASS_FACTORY} events
 * parent to the triggering caller's {@code CLASS_NEW} (cross-unit) and
 * defaults evaluate in the declaring module's scope.</p>
 *
 * <p><b>Negative seeds.</b> A corrupted construction order, a
 * cross-unit factory wiring defect, and a duplicated boundary child
 * each fail closed as {@link ClassOpsExecutor.Defect} — a failing
 * verdict, never a silent pass.</p>
 *
 * <p><b>Totality gate.</b> Both emitters' {@code emitOp} switches carry
 * exactly one arm each for {@code CLASS_DEFAULT}/{@code CLASS_NEW}/
 * {@code CLASS_FACTORY}, and the default throw remains present as the
 * fail-closed backstop.</p>
 */
public class ClassConstructionDifferentialTest {

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
    private static final ModuleId OWNER = new ModuleId("owner");
    private static final ModuleId OTHER = new ModuleId("other");
    private static final String SOURCE_ID = "main.deal";
    private static final String OWNER_SOURCE_ID = "owner.deal";
    private static final String OTHER_SOURCE_ID = "other.deal";
    private static final String CALLER_SOURCE_ID = "caller.deal";
    private static final String REGISTRY_HASH =
        CapabilityRegistry.releaseRegistry().capabilityRegistryHash();
    private static final Path WORKSPACE = Path.of("build/class-construction-diff");

    // =========================================================================
    // Pipeline helpers (lexer/parser/checker -> lowerModuleClassCore)
    // =========================================================================

    private record CheckedSlice(ProgramNode program, SymbolTable symbols, CheckResult checks) {
    }

    private static CheckedSlice checkSlice(String source, ModuleId moduleId,
                                           ModuleResolver resolver) {
        String sourceId = moduleId.path() + ".deal";
        Lexer lexer = new Lexer(source, sourceId);
        deal.lexer.LexResult lex = lexer.tokenize();
        Parser parser = new Parser(lex.tokens(), sourceId, lex.directiveEvents());
        deal.parser.ParseResult parse = parser.parse();
        check(parse.diagnostics().isEmpty(),
            "the slice parses cleanly: " + parse.diagnostics());
        if (!parse.diagnostics().isEmpty()) {
            return null;
        }
        NameResolver names = new NameResolver(moduleId.path(), resolver);
        SymbolTable symbols = names.resolve(parse.program());
        check(names.diagnostics().isEmpty(),
            "the slice resolves cleanly: " + names.diagnostics());
        if (!names.diagnostics().isEmpty()) {
            return null;
        }
        CheckResult checks = TypeChecker.check(moduleId.path(), symbols, names,
            parse.program());
        check(checks.diagnostics().isEmpty(),
            "the slice checks cleanly: " + checks.diagnostics());
        if (checks.hasErrors()) {
            return null;
        }
        return new CheckedSlice(parse.program(), symbols, checks);
    }

    private static CheckedModuleInput moduleOf(CheckedSlice slice, ModuleId moduleId,
                                               List<ResolvedImport> imports) {
        return new CheckedModuleInput(moduleId, moduleId.path() + ".deal",
            Path.of(moduleId.path() + ".deal"), slice.program(), slice.checks(), imports,
            List.of(), CheckedModuleKind.IMPLEMENTATION);
    }

    private record Pipeline(CheckedSlice slice, String interfaceHash,
                            ExternalModuleInterface ownInterface,
                            Map<ConstructKind, List<SemanticOpKind>> coverage) {
    }

    private static Pipeline pipeline(CheckedSlice slice, ModuleId moduleId) {
        CompilerInvocation invocation = CompilerProfileProvider.resolveCommonShadow(
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.V1_2_ACTIVE,
            CapabilityRegistry.releaseRegistry());
        ModuleFact fact = new ModuleFact(moduleId.path() + ".deal", moduleId, false, false,
            slice.program(), Map.of(), slice.symbols(), slice.checks(), List.of());
        deal.semantic.CheckedProjectBuildResult built = CheckedProjectBuilder.build(invocation,
            moduleId, List.of(fact));
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
                && manifests.manifests().size() == 1,
            "the foundation detector produces exactly one requirement manifest: "
                + (manifests == null ? "null" : manifests.diagnostics()));
        if (manifests == null || !manifests.diagnostics().isEmpty()
                || manifests.manifests().size() != 1) {
            return null;
        }
        Map<ConstructKind, List<SemanticOpKind>> coverage =
            new LinkedHashMap<>(manifests.manifests().get(0).constructCoverage());
        coverage.remove(ConstructKind.IMPORT_EXPORT_ENTRY);
        return new Pipeline(slice, built.index().interfaceIndexDigest(),
            built.index().modules().get(moduleId), coverage);
    }

    private record LoweredSlice(LoweredModuleUnit unit, StructuredBodyTable table,
                                ClassFactoryRegistry registry) {
    }

    private static LoweredSlice lowerModule(String source, String what) {
        CheckedSlice slice = checkSlice(source, MODULE, null);
        if (slice == null) {
            return null;
        }
        Pipeline pipeline = pipeline(slice, MODULE);
        if (pipeline == null) {
            return null;
        }
        SemanticLowerer.ClassDeclarationCoreResult result =
            SemanticLowerer.lowerModuleClassCore(moduleOf(slice, MODULE, List.of()),
                SemanticProfile.DEAL_V1_2_INT32, pipeline.coverage(),
                pipeline.interfaceHash(), REGISTRY_HASH, pipeline.ownInterface(), Map.of(),
                SemanticIdAllocator.over(List.of(MODULE)));
        check(result != null && result.lowering() != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null,
            what + " lowers to a validated unit: "
                + (result == null || result.lowering() == null ? "null"
                    : result.lowering().diagnostics()));
        if (result == null || result.lowering() == null || result.lowering().hasErrors()
                || result.lowering().unit() == null) {
            return null;
        }
        return new LoweredSlice(result.lowering().unit(), result.lowering().table(),
            result.registry());
    }

    private record XmodPair(LoweredSlice owner, LoweredSlice caller,
                            ExternalModuleInterface ownerInterface,
                            deal.semantic.ir.ProjectInterfaceIndex index) {
    }

    private static XmodPair lowerXmodPair(String ownerSource, String callerSource) {
        CheckedSlice owner = checkSlice(ownerSource, OWNER, null);
        if (owner == null) {
            return null;
        }
        ModuleResolver ownerResolver = new OwnerResolver(owner);
        CheckedSlice caller = checkSlice(callerSource, MODULE, ownerResolver);
        if (caller == null) {
            return null;
        }
        CompilerInvocation invocation = CompilerProfileProvider.resolveCommonShadow(
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.V1_2_ACTIVE,
            CapabilityRegistry.releaseRegistry());
        ModuleFact ownerFact = new ModuleFact(OWNER_SOURCE_ID, OWNER, false, false,
            owner.program(), ownerExports(owner), owner.symbols(), owner.checks(), List.of());
        ModuleFact callerFact = new ModuleFact(CALLER_SOURCE_ID, MODULE, false, false,
            caller.program(), Map.of(), caller.symbols(), caller.checks(),
            List.of(new ModuleFact.ImportFact("Owner", "owner", OWNER_SOURCE_ID)));
        deal.semantic.CheckedProjectBuildResult built = CheckedProjectBuilder.build(invocation,
            MODULE, List.of(ownerFact, callerFact));
        check(built != null && !built.hasErrors() && built.index() != null,
            "the two-module checked project builds cleanly: "
                + (built == null ? "null" : built.diagnostics()));
        if (built == null || built.hasErrors() || built.index() == null) {
            return null;
        }
        RequirementManifestResult manifests = LoweringSupport.computeManifests(invocation,
            built.input(), built.index());
        if (manifests == null || !manifests.diagnostics().isEmpty()
                || manifests.manifests().size() != 2) {
            fail("the two-module foundation detector fails: "
                + (manifests == null ? "null" : manifests.diagnostics()));
            return null;
        }
        Map<ModuleId, Map<ConstructKind, List<SemanticOpKind>>> coverages =
            new LinkedHashMap<>();
        List<ModuleId> ordered = List.of(OWNER, MODULE);
        for (int i = 0; i < ordered.size(); i++) {
            Map<ConstructKind, List<SemanticOpKind>> coverage =
                new LinkedHashMap<>(manifests.manifests().get(i).constructCoverage());
            coverage.remove(ConstructKind.IMPORT_EXPORT_ENTRY);
            coverages.put(ordered.get(i), coverage);
        }
        SemanticIdAllocator allocator = SemanticIdAllocator.over(ordered);
        LoweredSlice ownerSlice = lowerOne(owner, OWNER, List.of(), coverages.get(OWNER),
            built.index().interfaceIndexDigest(), built.index().modules().get(OWNER),
            Map.of(), allocator, "the owner module");
        if (ownerSlice == null) {
            return null;
        }
        ExternalModuleInterface ownerInterface = built.index().modules().get(OWNER);
        Map<ClassId, SharedFactoryFacts> facts = sharedFacts(ownerInterface, ownerSlice);
        if (facts == null) {
            return null;
        }
        LoweredSlice callerSlice = lowerOne(caller, MODULE,
            List.of(new ResolvedImport("Owner", OWNER.path(), OWNER,
                ExternalModuleKind.IMPLEMENTATION)),
            coverages.get(MODULE), built.index().interfaceIndexDigest(),
            built.index().modules().get(MODULE), facts, allocator, "the caller module");
        if (callerSlice == null) {
            return null;
        }
        return new XmodPair(ownerSlice, callerSlice, ownerInterface, built.index());
    }

    /**
     * Lowers one module of a closure through the production class arms.
     */
    private static LoweredSlice lowerOne(CheckedSlice slice, ModuleId moduleId,
            List<ResolvedImport> imports, Map<ConstructKind, List<SemanticOpKind>> coverage,
            String interfaceHash, ExternalModuleInterface ownInterface,
            Map<ClassId, SharedFactoryFacts> facts, SemanticIdAllocator allocator,
            String what) {
        SemanticLowerer.ClassDeclarationCoreResult result =
            SemanticLowerer.lowerModuleClassCore(moduleOf(slice, moduleId, imports),
                SemanticProfile.DEAL_V1_2_INT32, coverage, interfaceHash, REGISTRY_HASH,
                ownInterface, facts, allocator);
        check(result != null && result.lowering() != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null,
            what + " lowers to a validated unit: "
                + (result == null || result.lowering() == null ? "null"
                    : result.lowering().diagnostics()));
        if (result == null || result.lowering() == null || result.lowering().hasErrors()
                || result.lowering().unit() == null) {
            return null;
        }
        return new LoweredSlice(result.lowering().unit(), result.lowering().table(),
            result.registry());
    }

    /**
     * The owner-side construction facts of every exported class of a
     * lowered module (the K-D2 production records a caller's imported
     * construction resolves through; the two-module convention below is
     * the {@link ClassConstructionIntegrationTailTest} one).
     */
    private static Map<ClassId, SharedFactoryFacts> sharedFacts(
            ExternalModuleInterface moduleInterface, LoweredSlice slice) {
        Map<ClassId, SharedFactoryFacts> facts = new LinkedHashMap<>();
        for (ClassInterface entry : moduleInterface.classes()) {
            ClassLayout layout = slice.unit().classLayouts().get(entry.classId());
            OpId factoryOpId = slice.registry().factoryFor(entry.constructionEntry());
            SemanticOp factoryOp = opById(slice.unit(), factoryOpId);
            check(layout != null && factoryOpId != null && factoryOp != null
                    && factoryOp.result() instanceof ValueId,
                "the facts resolve: layout, registry binding, factory op, and "
                    + "factory result of " + entry.classId());
            if (layout == null || factoryOpId == null || factoryOp == null
                    || !(factoryOp.result() instanceof ValueId factoryResult)) {
                return null;
            }
            facts.put(entry.classId(), new SharedFactoryFacts(entry.classId(), entry,
                layout, factoryOpId, factoryResult));
        }
        return facts;
    }

    /**
     * The three-module closure of the nested-transfer seed: {@code other}
     * exports the defaulted class the {@code owner} constructs inside its
     * own default block, and the entry module constructs the owner's
     * class — so the owner-side default evaluation performs a second
     * factory transfer nested inside the first.
     */
    private record XmodTriple(LoweredSlice other, LoweredSlice owner,
                              LoweredSlice caller,
                              deal.semantic.ir.ProjectInterfaceIndex index) {
    }

    private static XmodTriple lowerXmodTriple(String otherSource, String ownerSource,
                                              String callerSource) {
        CheckedSlice other = checkSlice(otherSource, OTHER, null);
        if (other == null) {
            return null;
        }
        CheckedSlice owner = checkSlice(ownerSource, OWNER,
            new OwnerResolver(OTHER.path(), other));
        if (owner == null) {
            return null;
        }
        CheckedSlice caller = checkSlice(callerSource, MODULE,
            new OwnerResolver(OWNER.path(), owner));
        if (caller == null) {
            return null;
        }
        CompilerInvocation invocation = CompilerProfileProvider.resolveCommonShadow(
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.V1_2_ACTIVE,
            CapabilityRegistry.releaseRegistry());
        ModuleFact otherFact = new ModuleFact(OTHER_SOURCE_ID, OTHER, false, false,
            other.program(), ownerExports(other), other.symbols(), other.checks(), List.of());
        ModuleFact ownerFact = new ModuleFact(OWNER_SOURCE_ID, OWNER, false, false,
            owner.program(), ownerExports(owner), owner.symbols(), owner.checks(),
            List.of(new ModuleFact.ImportFact("Other", OTHER.path(), OTHER_SOURCE_ID)));
        ModuleFact callerFact = new ModuleFact(CALLER_SOURCE_ID, MODULE, false, false,
            caller.program(), Map.of(), caller.symbols(), caller.checks(),
            List.of(new ModuleFact.ImportFact("Owner", OWNER.path(), OWNER_SOURCE_ID)));
        deal.semantic.CheckedProjectBuildResult built = CheckedProjectBuilder.build(invocation,
            MODULE, List.of(otherFact, ownerFact, callerFact));
        check(built != null && !built.hasErrors() && built.index() != null,
            "the three-module checked project builds cleanly: "
                + (built == null ? "null" : built.diagnostics()));
        if (built == null || built.hasErrors() || built.index() == null) {
            return null;
        }
        RequirementManifestResult manifests = LoweringSupport.computeManifests(invocation,
            built.input(), built.index());
        if (manifests == null || !manifests.diagnostics().isEmpty()
                || manifests.manifests().size() != 3) {
            fail("the three-module foundation detector fails: "
                + (manifests == null ? "null" : manifests.diagnostics()));
            return null;
        }
        List<ModuleId> ordered = List.of(OTHER, OWNER, MODULE);
        Map<ModuleId, Map<ConstructKind, List<SemanticOpKind>>> coverages =
            new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            Map<ConstructKind, List<SemanticOpKind>> coverage =
                new LinkedHashMap<>(manifests.manifests().get(i).constructCoverage());
            coverage.remove(ConstructKind.IMPORT_EXPORT_ENTRY);
            coverages.put(ordered.get(i), coverage);
        }
        SemanticIdAllocator allocator = SemanticIdAllocator.over(ordered);
        LoweredSlice otherSlice = lowerOne(other, OTHER, List.of(), coverages.get(OTHER),
            built.index().interfaceIndexDigest(), built.index().modules().get(OTHER),
            Map.of(), allocator, "the other module");
        if (otherSlice == null) {
            return null;
        }
        Map<ClassId, SharedFactoryFacts> otherFacts = sharedFacts(
            built.index().modules().get(OTHER), otherSlice);
        if (otherFacts == null) {
            return null;
        }
        LoweredSlice ownerSlice = lowerOne(owner, OWNER,
            List.of(new ResolvedImport("Other", OTHER.path(), OTHER,
                ExternalModuleKind.IMPLEMENTATION)),
            coverages.get(OWNER), built.index().interfaceIndexDigest(),
            built.index().modules().get(OWNER), otherFacts, allocator, "the owner module");
        if (ownerSlice == null) {
            return null;
        }
        Map<ClassId, SharedFactoryFacts> ownerFacts = sharedFacts(
            built.index().modules().get(OWNER), ownerSlice);
        if (ownerFacts == null) {
            return null;
        }
        LoweredSlice callerSlice = lowerOne(caller, MODULE,
            List.of(new ResolvedImport("Owner", OWNER.path(), OWNER,
                ExternalModuleKind.IMPLEMENTATION)),
            coverages.get(MODULE), built.index().interfaceIndexDigest(),
            built.index().modules().get(MODULE), ownerFacts, allocator, "the caller module");
        if (callerSlice == null) {
            return null;
        }
        return new XmodTriple(otherSlice, ownerSlice, callerSlice, built.index());
    }

    /** The owner's export map (the orchestrator's derivation). */
    private static Map<String, deal.types.Type> ownerExports(CheckedSlice owner) {
        Map<String, deal.types.Type> exports = new LinkedHashMap<>();
        for (StatementNode stmt : owner.program().statements()) {
            if (stmt instanceof ExportDeclaration exp
                    && exp.declaration() instanceof ClassDeclaration cd) {
                Symbol sym = owner.symbols().resolve(cd.name());
                if (sym instanceof Symbol.ClassSymbol cs) {
                    exports.put(cd.name(), deal.types.Types.classType(cs.name(),
                        cs.identity()));
                }
            }
        }
        return exports;
    }

    /** The imported-module symbol resolver for a checking slice. */
    private static final class OwnerResolver implements ModuleResolver {
        private final Map<String, deal.types.Type> exports;
        private final Map<String, Symbol.ClassSymbol> classSymbols = new LinkedHashMap<>();
        private final String resolvedPath;

        OwnerResolver(CheckedSlice owner) {
            this(OWNER.path(), owner);
        }

        OwnerResolver(String modulePath, CheckedSlice module) {
            this.resolvedPath = modulePath;
            this.exports = ownerExports(module);
            for (StatementNode stmt : module.program().statements()) {
                if (stmt instanceof ExportDeclaration exp
                        && exp.declaration() instanceof ClassDeclaration cd) {
                    Symbol sym = module.symbols().resolve(cd.name());
                    if (sym instanceof Symbol.ClassSymbol cs) {
                        classSymbols.put(cd.name(), cs);
                    }
                }
            }
        }

        @Override
        public Map<String, deal.types.Type> resolveModule(String modulePath, String importingModule,
                Set<String> modulesInProgress) throws ModuleNotFoundException {
            if (resolvedPath.equals(modulePath)) {
                return exports;
            }
            throw new ModuleNotFoundException("Module not found: " + modulePath);
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className, String modulePath,
                String importingModule) throws ModuleNotFoundException {
            if (resolvedPath.equals(modulePath)) {
                return classSymbols.get(className);
            }
            return null;
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className,
                deal.identity.CanonicalModuleIdentity declaringModule,
                String importingModule) throws ModuleNotFoundException {
            return classSymbols.get(className);
        }
    }

    // =========================================================================
    // Op helpers
    // =========================================================================

    private static SemanticOp opById(LoweredModuleUnit unit, OpId opId) {
        for (SemanticOp op : unit.ops()) {
            if (op.opId().equals(opId)) {
                return op;
            }
        }
        return null;
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

    /** Rebuilds the unit with the replaced op list (negative seeds). */
    private static LoweredModuleUnit withOps(LoweredModuleUnit unit, List<SemanticOp> ops) {
        return new LoweredModuleUnit(unit.formatVersion(), unit.semanticProfile(),
            unit.moduleId(), unit.interfaceHash(), unit.loweringContextHash(),
            unit.requiredCapabilities(), unit.constructCoverage(), unit.classLayouts(),
            unit.functions(), unit.moduleInit(), unit.exportPlan(), unit.functionBindings(),
            ops);
    }

    /** Rebuilds one op over a replacement payload (the digest recomputes). */
    private static SemanticOp rebuildOp(SemanticOp original, KindPayload payload) {
        OperationContractSnapshot placeholder = new OperationContractSnapshot(
            OperationContractSnapshot.VERSION, original.kind(), original.resultType(),
            original.operandTypes(), null, payload, original.failurePolicy(), List.of(),
            "placeholder");
        String digest = ContractSnapshotCanonicalizer.digest(placeholder);
        OperationContractSnapshot contract = new OperationContractSnapshot(
            OperationContractSnapshot.VERSION, original.kind(), original.resultType(),
            original.operandTypes(), null, payload, original.failurePolicy(), List.of(),
            digest);
        return new SemanticOp(original.opId(), original.kind(), original.origin(),
            original.result(), original.resultType(), original.operands(),
            original.operandTypes(), payload, original.failurePolicy(), contract);
    }

    private static KindPayload.ClassNewPayload classNewPayload(SemanticOp op) {
        return (KindPayload.ClassNewPayload) op.payload();
    }

    // =========================================================================
    // Matrix runners
    // =========================================================================

    private static SemanticDifferentialHarness.Verdict runMatrix(LoweredSlice lowered,
            String what) {
        if (lowered == null) {
            return null;
        }
        SemanticDifferentialHarness.Verdict verdict = SemanticDifferentialHarness.run(
            lowered.unit(), lowered.table(),
            new SemanticDifferentialHarness.Expectation(List.of(),
                new SemanticDifferentialHarness.TerminalExpectation.SuccessWith("null"),
                what),
            WORKSPACE);
        check(verdict.pass(), what + ": the three-consumer matrix verdict passes:\n"
            + verdict.report());
        return verdict;
    }

    private static SemanticDifferentialHarness.Verdict runFailureMatrix(LoweredSlice lowered,
            String code, String originText, String what) {
        if (lowered == null) {
            return null;
        }
        SemanticDifferentialHarness.Verdict verdict = SemanticDifferentialHarness.run(
            lowered.unit(), lowered.table(),
            new SemanticDifferentialHarness.Expectation(List.of(),
                new SemanticDifferentialHarness.TerminalExpectation.FailureWith(code,
                    originText),
                what),
            WORKSPACE);
        check(verdict.pass(), what + ": the three-consumer matrix verdict passes:\n"
            + verdict.report());
        return verdict;
    }

    /** One emitted project closure (modules in walk order). */
    private record ProjectClosure(ExecutableLoweredProject project,
                                  Map<ModuleId, StructuredBodyTable> tables,
                                  Map<ModuleId, ClassFactoryRegistry> registries) {
    }

    private static ProjectClosure closure(List<ModuleId> order,
            Map<ModuleId, LoweredSlice> slices, ModuleId entry,
            deal.semantic.ir.ProjectInterfaceIndex index) {
        Map<ModuleId, LoweredModuleUnit> modules = new LinkedHashMap<>();
        Map<ModuleId, StructuredBodyTable> tables = new LinkedHashMap<>();
        Map<ModuleId, ClassFactoryRegistry> registries = new LinkedHashMap<>();
        for (ModuleId module : order) {
            LoweredSlice slice = slices.get(module);
            modules.put(module, slice.unit());
            tables.put(module, slice.table());
            registries.put(module, slice.registry());
        }
        return new ProjectClosure(new ExecutableLoweredProject(
            SemanticProfile.DEAL_V1_2_INT32, index, modules, entry), tables, registries);
    }

    private static ProjectClosure pairClosure(XmodPair pair) {
        Map<ModuleId, LoweredSlice> slices = new LinkedHashMap<>();
        slices.put(OWNER, pair.owner());
        slices.put(MODULE, pair.caller());
        return closure(List.of(OWNER, MODULE), slices, MODULE, pair.index());
    }

    private static ProjectClosure tripleClosure(XmodTriple triple) {
        Map<ModuleId, LoweredSlice> slices = new LinkedHashMap<>();
        slices.put(OTHER, triple.other());
        slices.put(OWNER, triple.owner());
        slices.put(MODULE, triple.caller());
        return closure(List.of(OTHER, OWNER, MODULE), slices, MODULE, triple.index());
    }

    private static SemanticDifferentialHarness.Verdict runProjectMatrix(ProjectClosure closure,
            SemanticDifferentialHarness.TerminalExpectation terminal, String what) {
        if (closure == null) {
            return null;
        }
        SemanticDifferentialHarness.Verdict verdict = SemanticDifferentialHarness.runProject(
            closure.project(), closure.tables(), closure.registries(),
            new SemanticDifferentialHarness.Expectation(List.of(), terminal, what),
            WORKSPACE);
        check(verdict.pass(), what + ": the three-consumer project verdict passes:\n"
            + verdict.report());
        return verdict;
    }

    // =========================================================================
    // 1. Provided/default order (K-D4 steps 2/4/5 in declaration order)
    // =========================================================================

    private static final String PERSON_SOURCE = """
        export class Person {
          name: string = "anon";
          age: int = 0;
          ratio: number = 0.5;
          note?: string | null;
          meta: table = {k: "v"};
        }
        """;

    static void testProvidedDefaultOrder() {
        System.out.println("-- Provided/default order: declaration-order defaults, "
            + "literal-order provided fields --");
        String source = PERSON_SOURCE + """
            let full: Person = {name: "bob", age: 3, ratio: 1.5, note: "x", meta: {m: "n"}}
            let partial: Person = {age: 7}
            let reordered: Person = {ratio: 2.5, name: "kim"}
            let empty: Person = {}
            """;
        LoweredSlice lowered = lowerModule(source, "provided/default order");
        if (lowered == null) {
            return;
        }
        List<SemanticOp> constructions = ofKind(lowered.unit(), SemanticOpKind.CLASS_NEW);
        check(constructions.size() == 4,
            "the seed lowers four CLASS_NEW ops, got " + constructions.size());
        // The partial literal: age provided (CLASS_LITERAL_FIELD), name/
        // ratio/meta defaulted (CLASS_DEFAULT_FIELD) in declaration order;
        // the note default never runs (optional).
        for (SemanticOp op : constructions) {
            KindPayload.ClassNewPayload payload = classNewPayload(op);
            check(payload.defaultOwner() == deal.semantic.ir.DefaultOwner.LOCAL
                    && payload.classFactoryRef() == null,
                "LOCAL construction carries defaultOwner LOCAL and no factory ref");
            if (payload.providedFields().size() == 1
                    && "age".equals(payload.providedFields().get(0).name())) {
                check(payload.classDefaultOpIds().size() == 3,
                    "the partial literal lists exactly the three omitted "
                        + "required-present defaults");
                check(payload.fieldBoundaries().size() == 4
                        && payload.fieldBoundaries().get(0).field().equals("name")
                        && payload.fieldBoundaries().get(1).field().equals("age")
                        && payload.fieldBoundaries().get(2).field().equals("ratio")
                        && payload.fieldBoundaries().get(3).field().equals("meta"),
                    "the partial literal's boundaries run in declaration order");
                check(payload.fieldBoundaries().get(1).kind()
                        == deal.semantic.ir.BoundaryKind.CLASS_LITERAL_FIELD
                        && payload.fieldBoundaries().get(0).kind()
                            == deal.semantic.ir.BoundaryKind.CLASS_DEFAULT_FIELD,
                    "provided fields carry CLASS_LITERAL_FIELD, defaulted fields "
                        + "CLASS_DEFAULT_FIELD");
            }
        }
        runMatrix(lowered, "provided/default order");
    }

    // =========================================================================
    // 2. Mutable-default freshness (per-attempt re-execution)
    // =========================================================================

    static void testMutableDefaultFreshness() {
        System.out.println("-- Mutable-default freshness: fresh array/table identities "
            + "per construction --");
        String source = PERSON_SOURCE + """
            export class Bag {
              tags: int[] = [1, 2];
              meta: table = {seed: "s"};
            }

            let b1: Bag = {}
            let b2: Bag = {}
            """;
        LoweredSlice lowered = lowerModule(source, "mutable-default freshness");
        if (lowered == null) {
            return;
        }
        SemanticDifferentialHarness.Verdict verdict = runMatrix(lowered,
            "mutable-default freshness");
        if (verdict == null) {
            return;
        }
        // The freshness observation: each executed CLASS_DEFAULT op runs
        // twice (once per construction) and publishes two distinct
        // allocation ids for its mutable default. Only the defaults the
        // constructions reference execute; unreferenced detached defaults
        // (the unconstructed Person's) never run.
        List<SemanticOp> defaults = ofKind(lowered.unit(), SemanticOpKind.CLASS_DEFAULT);
        check(!defaults.isEmpty(), "the seed lowers CLASS_DEFAULT ops");
        java.util.Set<OpId> referenced = new java.util.HashSet<>();
        for (SemanticOp construction : ofKind(lowered.unit(), SemanticOpKind.CLASS_NEW)) {
            referenced.addAll(classNewPayload(construction).classDefaultOpIds());
        }
        for (SemanticOp defaultOp : defaults) {
            if (!referenced.contains(defaultOp.opId())) {
                continue;
            }
            List<String> outputs = new ArrayList<>();
            for (SemanticRuntimeModel.TraceEvent event : verdict.runs().get(0).trace()) {
                if (event.op().equals(defaultOp.opId())
                        && event.phase() == SemanticRuntimeModel.Phase.SUCCESS) {
                    outputs.add(event.output());
                }
            }
            if (defaultOp.resultType() instanceof RuntimeDescriptor.Array
                    || defaultOp.resultType() instanceof RuntimeDescriptor.Table) {
                check(outputs.size() == 2 && !outputs.get(0).equals(outputs.get(1)),
                    "the mutable default " + defaultOp.opId() + " publishes two "
                        + "distinct allocation ids across the two constructions: "
                        + outputs);
            }
        }
    }

    // =========================================================================
    // 3. The E8007 extra-key pin (CLASS_CONSTRUCTION at the op origin)
    // =========================================================================

    static void testExtraKeyE8007() {
        System.out.println("-- Extra-key E8007: defaults complete, then the first "
            + "unknown provided name fails at the op origin --");
        // The checker rejects an unknown literal key for a resolved class
        // (E4002), so a checked source cannot reach the runtime arm; the
        // closed validator admits unknown provided names, and the
        // executor pins the arm (K-D4 step 3). The seed lowers the real
        // construction through the production class arms, then extends
        // the provided-field list with the unknown name — the exact
        // payload shape the runtime arm defends.
        String source = PERSON_SOURCE + """
            let broken: Person = {age: 5}
            """;
        LoweredSlice lowered = lowerModule(source, "extra-key E8007");
        if (lowered == null) {
            return;
        }
        SemanticOp construction = ofKind(lowered.unit(), SemanticOpKind.CLASS_NEW).get(0);
        KindPayload.ClassNewPayload payload = classNewPayload(construction);
        List<KindPayload.ProvidedField> extended = new ArrayList<>(payload.providedFields());
        ValueId unknownValue = payload.providedFields().isEmpty()
            ? null : payload.providedFields().get(0).valueOpId();
        if (unknownValue == null) {
            fail("the extra-key seed needs one provided value to reuse");
            return;
        }
        extended.add(new KindPayload.ProvidedField("unknown", unknownValue));
        KindPayload.ClassNewPayload corrupted = new KindPayload.ClassNewPayload(
            payload.classId(), payload.layout(), extended, payload.defaultOwner(),
            payload.classDefaultOpIds(), payload.classFactoryRef(),
            payload.fieldBoundaries());
        List<SemanticOp> ops = new ArrayList<>(lowered.unit().ops());
        ops.set(ops.indexOf(construction), rebuildOp(construction, corrupted));
        LoweredSlice extendedSlice = new LoweredSlice(withOps(lowered.unit(), ops),
            lowered.table(), lowered.registry());
        String origin = construction.origin().sourceId() + ":"
            + construction.origin().span().startLine() + ":"
            + construction.origin().span().startColumn();
        runFailureMatrix(extendedSlice, "E8007", origin, "extra-key E8007");
        // The default application precedes the extra-key rejection: the
        // trace records the name/ratio/meta CLASS_DEFAULT ops before the
        // CLASS_NEW FAILURE (the pinned K-D4 order, observable in the
        // oracle's run).
        SemanticRuntimeModel.ConsumerRun oracle =
            SemanticOracle.execute(extendedSlice.unit(), extendedSlice.table());
        boolean sawDefault = false;
        boolean sawFailure = false;
        for (SemanticRuntimeModel.TraceEvent event : oracle.trace()) {
            if (event.kind() == SemanticOpKind.CLASS_DEFAULT
                    && event.phase() == SemanticRuntimeModel.Phase.SUCCESS) {
                sawDefault = true;
            }
            if (event.op().equals(construction.opId())
                    && event.phase() == SemanticRuntimeModel.Phase.FAILURE) {
                check(sawDefault,
                    "the default application completes before the extra-key "
                        + "rejection (K-D4 step 2 before step 3)");
                check(event.error() != null && "E8007".equals(event.error().code())
                        && event.error().message().contains("'unknown'")
                        && event.error().message().contains("@main/Person"),
                    "the extra-key projection carries E8007 with the pinned "
                        + "template: " + (event.error() == null ? "null"
                            : event.error().code() + " " + event.error().message()));
                sawFailure = true;
            }
        }
        check(sawFailure, "the extra-key seed reaches the CLASS_NEW FAILURE terminal");
    }

    // =========================================================================
    // 4. Present-null-vs-missing instance states (presence-preserving storage)
    // =========================================================================

    static void testPresentNullVsMissing() {
        System.out.println("-- Present-null-vs-missing: {note: null} is present, {} is "
            + "missing, observed through has() --");
        String source = PERSON_SOURCE + """
            let presentNull: Person = {note: null}
            let missingNote: Person = {}
            let hasPresent: boolean = has(presentNull.note)
            let hasMissing: boolean = has(missingNote.note)
            """;
        LoweredSlice lowered = lowerModule(source, "present-null-vs-missing");
        if (lowered == null) {
            return;
        }
        // The storage discipline is visible in the payloads: the
        // present-null literal carries a CLASS_LITERAL_FIELD boundary for
        // note with a null input; the empty literal carries no note
        // boundary at all.
        List<SemanticOp> constructions = ofKind(lowered.unit(), SemanticOpKind.CLASS_NEW);
        for (SemanticOp op : constructions) {
            KindPayload.ClassNewPayload payload = classNewPayload(op);
            boolean noteProvided = payload.providedFields().stream()
                .anyMatch(field -> "note".equals(field.name()));
            boolean noteBoundary = payload.fieldBoundaries().stream()
                .anyMatch(boundary -> "note".equals(boundary.field()));
            check(noteProvided == noteBoundary,
                "a provided note carries exactly its boundary, an omitted note "
                    + "carries none (present null stays present null, missing "
                    + "stays missing)");
        }
        SemanticDifferentialHarness.Verdict verdict = runMatrix(lowered,
            "present-null-vs-missing");
        if (verdict == null) {
            return;
        }
        // The presence booleans: has(presentNull.note) = true,
        // has(missingNote.note) = false — two HAS_FIELD SUCCESS outputs.
        List<String> presenceOutputs = new ArrayList<>();
        for (SemanticRuntimeModel.TraceEvent event : verdict.runs().get(0).trace()) {
            if (event.kind() == SemanticOpKind.HAS_FIELD
                    && event.phase() == SemanticRuntimeModel.Phase.SUCCESS) {
                presenceOutputs.add(event.output());
            }
        }
        check(presenceOutputs.equals(List.of("bool:true", "bool:false")),
            "the presence booleans distinguish present null (true) from missing "
                + "(false): " + presenceOutputs);
    }

    // =========================================================================
    // 5. The cross-module factory (CLASS_FACTORY under the triggering caller)
    // =========================================================================

    private static final String OWNER_SOURCE = """
        let baseZip: int = 10115;

        export class Address {
          city: string = "berlin";
          zip: int = baseZip;
          note?: string | null;
        }

        export class Bag {
          tags: int[] = [1, 2];
          meta: table = {seed: "s"};
        }
        """;

    private static final String CALLER_SOURCE = """
        import * as Owner from "owner"

        let addr: Owner.Address = {zip: 9, city: "munich"}
        let emptyAddr: Owner.Address = {}
        let bag1: Owner.Bag = {}
        let bag2: Owner.Bag = {}
        let hasAddrNote: boolean = has(addr.note)
        let hasEmptyNote: boolean = has(emptyAddr.note)
        """;

    static void testCrossModuleFactory() {
        System.out.println("-- Cross-module factory: owner defaults in the declaring "
            + "module's scope, factory events parent to the caller CLASS_NEW --");
        XmodPair pair = lowerXmodPair(OWNER_SOURCE, CALLER_SOURCE);
        if (pair == null) {
            return;
        }
        // The caller's constructions carry defaultOwner SHARED_FACTORY
        // with the owner's constructionEntry and empty local default ids.
        List<SemanticOp> constructions = ofKind(pair.caller().unit(),
            SemanticOpKind.CLASS_NEW);
        check(constructions.size() == 4,
            "the caller lowers four SHARED_FACTORY constructions, got "
                + constructions.size());
        for (SemanticOp op : constructions) {
            KindPayload.ClassNewPayload payload = classNewPayload(op);
            check(payload.defaultOwner() == deal.semantic.ir.DefaultOwner.SHARED_FACTORY
                    && payload.classFactoryRef() != null
                    && payload.classDefaultOpIds().isEmpty(),
                "the imported construction carries SHARED_FACTORY with the "
                    + "constructionEntry and no local default ids");
        }
        SemanticDifferentialHarness.Verdict verdict = runProjectMatrix(pairClosure(pair),
            new SemanticDifferentialHarness.TerminalExpectation.SuccessWith("null"),
            "cross-module factory");
        if (verdict == null) {
            return;
        }
        // The cross-unit parenting: every CLASS_FACTORY event parents to a
        // caller CLASS_NEW op; the owner defaults evaluate in the owner's
        // scope (the zip default reads baseZip = 10115 — observable in the
        // CLASS_DEFAULT SUCCESS output int:10115 with module owner).
        boolean sawFactory = false;
        boolean sawZip = false;
        for (SemanticRuntimeModel.TraceEvent event : verdict.runs().get(0).trace()) {
            if (event.kind() == SemanticOpKind.CLASS_FACTORY) {
                check(event.parentOp() != null
                        && MODULE.equals(event.parentOp().module())
                        && event.module().equals(OWNER.path()),
                    "the factory event carries the owner's module and parents to "
                        + "the caller's CLASS_NEW: " + event.text());
                sawFactory = true;
            }
            if (event.kind() == SemanticOpKind.CLASS_DEFAULT
                    && event.phase() == SemanticRuntimeModel.Phase.SUCCESS
                    && "int:10115".equals(event.output())) {
                check(OWNER.path().equals(event.module()),
                    "the zip default evaluates in the declaring module's scope "
                        + "(module " + event.module() + ", baseZip 10115)");
                sawZip = true;
            }
        }
        check(sawFactory && sawZip,
            "the factory events and the owner-scope zip default both appear in "
                + "the trace");
    }

    // =========================================================================
    // 6. Module context on the cross-module construction surfaces
    // =========================================================================

    /** The first op of a kind originating in the given source file, or null. */
    private static SemanticOp opOfKindIn(LoweredModuleUnit unit, SemanticOpKind kind,
                                         String sourceId) {
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == kind && sourceId.equals(op.origin().sourceId())) {
                return op;
            }
        }
        return null;
    }

    private static String originTextOf(SemanticOp op) {
        return op.origin().sourceId() + ":" + op.origin().span().startLine() + ":"
            + op.origin().span().startColumn();
    }

    /**
     * Every consumer's event for the given op in the given phase, asserted
     * to carry the op's own module (the trace contract the harness checks:
     * {@code event.module() == op.opId().module().path()}).
     */
    private static void checkEventModule(SemanticDifferentialHarness.Verdict verdict,
            SemanticOp op, SemanticRuntimeModel.Phase phase, String modulePath,
            String what) {
        boolean saw = false;
        for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
            for (SemanticRuntimeModel.TraceEvent event : run.trace()) {
                if (event.op().equals(op.opId()) && event.phase() == phase) {
                    check(modulePath.equals(event.module()),
                        run.consumer() + ": " + what + " carries module "
                            + modulePath + ", got " + event.module());
                    saw = true;
                }
            }
        }
        check(saw, what + " appears in every consumer's trace");
    }

    private static final String FAILING_OWNER_SOURCE = """
        let base: int = 0;

        export class Blowup {
          zip: int = 100 / base;
        }
        """;

    private static final String FAILING_CALLER_SOURCE = """
        import * as Owner from "owner"

        let blowup: Owner.Blowup = {}
        """;

    /**
     * The owner default fails at run time (E8005 raised by the helper the
     * emitted BINARY arm calls) while the owner's factory transfer runs
     * under the caller's CLASS_NEW: the owner-side terminals keep the
     * owner module, the caller's CLASS_NEW terminal keeps the caller
     * module, and the helper-raised failure event keeps the module of the
     * op that raised it.
     */
    static void testCrossModuleDefaultFailureModuleContext() {
        System.out.println("-- Cross-module default failure: owner-side terminals stay on "
            + "the owner module, the caller terminal on the caller module --");
        XmodPair pair = lowerXmodPair(FAILING_OWNER_SOURCE, FAILING_CALLER_SOURCE);
        if (pair == null) {
            return;
        }
        SemanticOp division = opOfKindIn(pair.owner().unit(), SemanticOpKind.BINARY,
            OWNER_SOURCE_ID);
        SemanticOp construction = opOfKindIn(pair.caller().unit(), SemanticOpKind.CLASS_NEW,
            SOURCE_ID);
        if (division == null || construction == null) {
            fail("the failing seed lowers the owner division and the caller "
                + "CLASS_NEW");
            return;
        }
        SemanticDifferentialHarness.Verdict verdict = runProjectMatrix(pairClosure(pair),
            new SemanticDifferentialHarness.TerminalExpectation.FailureWith("E8005",
                originTextOf(division)),
            "cross-module default failure");
        if (verdict == null) {
            return;
        }
        checkEventModule(verdict, division, SemanticRuntimeModel.Phase.FAILURE,
            OWNER.path(), "the helper-raised division failure");
        checkEventModule(verdict, construction, SemanticRuntimeModel.Phase.FAILURE,
            MODULE.path(), "the caller's CLASS_NEW FAILURE terminal");
        checkEventModule(verdict, construction, SemanticRuntimeModel.Phase.START,
            MODULE.path(), "the caller's CLASS_NEW START");
    }

    private static final String BOOM_OWNER_SOURCE = """
        let zero: int = 0;
        let boom: int = 100 / zero;

        export class Marker {
          tag: int = 1;
        }
        """;

    private static final String BOOM_CALLER_SOURCE = """
        import * as Owner from "owner"

        let marker: Owner.Marker = {}
        """;

    /**
     * A helper-raised failure in a non-entry module's init walk: the
     * combined walk switches the module per module, so the failed
     * division the helper raises for the second module must carry that
     * module's path — not the entry module's.
     */
    static void testNonEntryModuleHelperFailureModuleContext() {
        System.out.println("-- Non-entry module helper failure: the walk's helper-raised "
            + "events carry their own module --");
        XmodPair pair = lowerXmodPair(BOOM_OWNER_SOURCE, BOOM_CALLER_SOURCE);
        if (pair == null) {
            return;
        }
        SemanticOp division = opOfKindIn(pair.owner().unit(), SemanticOpKind.BINARY,
            OWNER_SOURCE_ID);
        if (division == null) {
            fail("the non-entry failure seed lowers the owner division");
            return;
        }
        SemanticDifferentialHarness.Verdict verdict = runProjectMatrix(pairClosure(pair),
            new SemanticDifferentialHarness.TerminalExpectation.FailureWith("E8005",
                originTextOf(division)),
            "non-entry module walk failure");
        if (verdict == null) {
            return;
        }
        checkEventModule(verdict, division, SemanticRuntimeModel.Phase.FAILURE,
            OWNER.path(), "the owner walk's helper-raised division failure");
    }

    private static final String NEST_OTHER_SOURCE = """
        export class Inner {
          seed: int = 7;
        }
        """;

    private static final String NEST_OWNER_SOURCE = """
        import * as Other from "other"

        export class Outer {
          inner: Other.Inner = {}
          label: string = "outer";
        }
        """;

    private static final String NEST_CALLER_SOURCE = """
        import * as Owner from "owner"

        let outer: Owner.Outer = {}
        """;

    /**
     * The nested imported construction: the entry module constructs the
     * owner's class, whose default constructs another module's class — a
     * second factory transfer nested inside the first. After the inner
     * transfer returns, every subsequent event of the enclosing walk must
     * still carry the enclosing module (the module save of the outer
     * transfer is restored, not the inner one's).
     */
    static void testNestedImportedConstruction() {
        System.out.println("-- Nested imported construction: the inner factory "
            + "transfer restores the enclosing module context --");
        XmodTriple triple = lowerXmodTriple(NEST_OTHER_SOURCE, NEST_OWNER_SOURCE,
            NEST_CALLER_SOURCE);
        if (triple == null) {
            return;
        }
        SemanticOp outerConstruction = opOfKindIn(triple.caller().unit(),
            SemanticOpKind.CLASS_NEW, SOURCE_ID);
        SemanticOp innerConstruction = opOfKindIn(triple.owner().unit(),
            SemanticOpKind.CLASS_NEW, OWNER_SOURCE_ID);
        check(outerConstruction != null && innerConstruction != null,
            "the nested seed lowers the caller construction and the owner default's "
                + "nested construction");
        if (outerConstruction == null || innerConstruction == null) {
            return;
        }
        KindPayload.ClassNewPayload innerPayload = classNewPayload(innerConstruction);
        check(innerPayload.defaultOwner() == deal.semantic.ir.DefaultOwner.SHARED_FACTORY
                && OTHER.path().equals(innerPayload.classId().modulePath()),
            "the owner default's nested construction is a SHARED_FACTORY transfer to "
                + "the other module, got " + innerPayload.defaultOwner() + " / "
                + innerPayload.classId());
        SemanticDifferentialHarness.Verdict verdict = runProjectMatrix(tripleClosure(triple),
            new SemanticDifferentialHarness.TerminalExpectation.SuccessWith("null"),
            "nested imported construction");
        if (verdict == null) {
            return;
        }
        // Every event after the inner transfer belongs to the enclosing
        // op's module again: the outer construction's START, the inner
        // construction's own events (owner), and the outer terminal.
        checkEventModule(verdict, innerConstruction, SemanticRuntimeModel.Phase.START,
            OWNER.path(), "the nested construction's START");
        checkEventModule(verdict, innerConstruction, SemanticRuntimeModel.Phase.SUCCESS,
            OWNER.path(), "the nested construction's SUCCESS");
        checkEventModule(verdict, outerConstruction, SemanticRuntimeModel.Phase.SUCCESS,
            MODULE.path(), "the enclosing construction's SUCCESS");
    }

    // =========================================================================
    // 7. Negative controls (failing verdicts, fail closed)
    // =========================================================================

    /** Runs one corrupted-unit seed and asserts a failing verdict. */
    private static void checkNegative(LoweredModuleUnit corrupted,
                                      StructuredBodyTable table, String what) {
        boolean failingVerdict;
        try {
            SemanticDifferentialHarness.Verdict verdict = SemanticDifferentialHarness.run(
                corrupted, table,
                new SemanticDifferentialHarness.Expectation(List.of(),
                    new SemanticDifferentialHarness.TerminalExpectation.SuccessWith("null"),
                    what),
                WORKSPACE);
            failingVerdict = !verdict.pass();
        } catch (ClassOpsExecutor.Defect defect) {
            // The oracle's fail-closed executor rejection is itself the
            // failing verdict for the corrupted shape.
            failingVerdict = true;
        } catch (IllegalStateException defect) {
            // A producer defect raised by a consumer (a moved child, a
            // malformed boundary list) is a failing verdict, never a pass.
            failingVerdict = true;
        }
        check(failingVerdict, what + " produces a failing verdict");
    }

    private static LoweredSlice freshCorpusSlice() {
        return lowerModule(PERSON_SOURCE + """
            let p: Person = {age: 7}
            let q: Person = {}
            """, "negative corpus slice");
    }

    static void testNegativeWrongConstructionOrder() {
        System.out.println("-- Negative: a reordered boundary list fails closed --");
        LoweredSlice lowered = freshCorpusSlice();
        if (lowered == null) {
            return;
        }
        SemanticOp construction = ofKind(lowered.unit(), SemanticOpKind.CLASS_NEW).get(0);
        KindPayload.ClassNewPayload payload = classNewPayload(construction);
        List<KindPayload.FieldBoundary> reordered = new ArrayList<>(payload.fieldBoundaries());
        if (reordered.size() >= 2) {
            KindPayload.FieldBoundary first = reordered.get(0);
            reordered.set(0, reordered.get(1));
            reordered.set(1, first);
        }
        KindPayload.ClassNewPayload corrupted = new KindPayload.ClassNewPayload(
            payload.classId(), payload.layout(), payload.providedFields(),
            payload.defaultOwner(), payload.classDefaultOpIds(), payload.classFactoryRef(),
            reordered);
        List<SemanticOp> ops = new ArrayList<>(lowered.unit().ops());
        ops.set(ops.indexOf(construction), rebuildOp(construction, corrupted));
        checkNegative(withOps(lowered.unit(), ops), lowered.table(),
            "a reordered field-boundary list");
    }

    static void testNegativeWrongCrossUnitParent() {
        System.out.println("-- Negative: a cross-unit factory wiring defect fails closed --");
        XmodPair pair = lowerXmodPair(OWNER_SOURCE, CALLER_SOURCE);
        if (pair == null) {
            return;
        }
        // Point the Address construction at the Bag factory entry: the
        // factory-parent wiring resolves a factory of another class —
        // a cross-unit parent/wiring defect the executor rejects.
        SemanticOp address = ofKind(pair.caller().unit(), SemanticOpKind.CLASS_NEW).get(0);
        KindPayload.ClassNewPayload addressPayload = classNewPayload(address);
        ClassInterface bagInterface = null;
        for (ClassInterface entry : pair.ownerInterface().classes()) {
            if ("Bag".equals(entry.classId().name())) {
                bagInterface = entry;
            }
        }
        if (bagInterface == null) {
            fail("the owner interface carries the Bag constructionEntry");
            return;
        }
        KindPayload.ClassNewPayload corrupted = new KindPayload.ClassNewPayload(
            addressPayload.classId(), addressPayload.layout(),
            addressPayload.providedFields(), addressPayload.defaultOwner(),
            addressPayload.classDefaultOpIds(), bagInterface.constructionEntry(),
            addressPayload.fieldBoundaries());
        List<SemanticOp> ops = new ArrayList<>(pair.caller().unit().ops());
        ops.set(ops.indexOf(address), rebuildOp(address, corrupted));
        // The project matrix over the corrupted caller: the oracle's
        // factory transfer fails closed on the classId mismatch.
        Map<ModuleId, LoweredModuleUnit> modules = new LinkedHashMap<>();
        modules.put(OWNER, pair.owner().unit());
        modules.put(MODULE, withOps(pair.caller().unit(), ops));
        ExecutableLoweredProject project = new ExecutableLoweredProject(
            SemanticProfile.DEAL_V1_2_INT32, pair.index(), modules, MODULE);
        Map<ModuleId, StructuredBodyTable> tables = Map.of(OWNER, pair.owner().table(),
            MODULE, pair.caller().table());
        Map<ModuleId, ClassFactoryRegistry> registries = Map.of(OWNER,
            pair.owner().registry(), MODULE, pair.caller().registry());
        boolean failingVerdict;
        try {
            SemanticDifferentialHarness.Verdict verdict =
                SemanticDifferentialHarness.runProject(project, tables, registries,
                    new SemanticDifferentialHarness.Expectation(List.of(),
                        new SemanticDifferentialHarness.TerminalExpectation.SuccessWith(
                            "null"),
                        "a cross-unit factory wiring defect"),
                    WORKSPACE);
            failingVerdict = !verdict.pass();
        } catch (ClassOpsExecutor.Defect | IllegalStateException defect) {
            failingVerdict = true;
        }
        check(failingVerdict, "a cross-unit factory wiring defect produces a "
            + "failing verdict");
    }

    static void testNegativeDuplicatedBoundary() {
        System.out.println("-- Negative: a duplicated boundary child fails closed --");
        LoweredSlice lowered = freshCorpusSlice();
        if (lowered == null) {
            return;
        }
        SemanticOp construction = ofKind(lowered.unit(), SemanticOpKind.CLASS_NEW).get(0);
        KindPayload.ClassNewPayload payload = classNewPayload(construction);
        List<KindPayload.FieldBoundary> duplicated = new ArrayList<>(payload.fieldBoundaries());
        if (!duplicated.isEmpty()) {
            duplicated.add(duplicated.get(0));
        }
        KindPayload.ClassNewPayload corrupted = new KindPayload.ClassNewPayload(
            payload.classId(), payload.layout(), payload.providedFields(),
            payload.defaultOwner(), payload.classDefaultOpIds(), payload.classFactoryRef(),
            duplicated);
        List<SemanticOp> ops = new ArrayList<>(lowered.unit().ops());
        ops.set(ops.indexOf(construction), rebuildOp(construction, corrupted));
        checkNegative(withOps(lowered.unit(), ops), lowered.table(),
            "a duplicated boundary child");
    }

    // =========================================================================
    // 8. The emitter totality gate (one arm per kind; the default stays)
    // =========================================================================

    static void testEmitterTotality() {
        System.out.println("-- Emitter totality: one CLASS_DEFAULT/CLASS_NEW/"
            + "CLASS_FACTORY arm per emitter; the default throw remains --");
        for (String path : List.of("deal/codegen/lua/LuaSemanticEmitter.java",
                "deal/codegen/jvm/JvmSemanticEmitter.java")) {
            try {
                String text = Files.readString(Path.of(path));
                for (String arm : List.of("case CLASS_NEW ->", "case CLASS_DEFAULT ->",
                        "case CLASS_FACTORY ->")) {
                    int count = 0;
                    int index = text.indexOf(arm);
                    while (index >= 0) {
                        count++;
                        index = text.indexOf(arm, index + arm.length());
                    }
                    check(count == 1, path + " carries exactly one " + arm + " arm, got "
                        + count);
                }
                check(text.contains("default -> throw new IllegalStateException"),
                    path + " retains the default throw as the fail-closed backstop");
            } catch (Exception exception) {
                fail("reading " + path + " failed: " + exception.getMessage());
            }
        }
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) {
        testProvidedDefaultOrder();
        testMutableDefaultFreshness();
        testExtraKeyE8007();
        testPresentNullVsMissing();
        testCrossModuleFactory();
        testCrossModuleDefaultFailureModuleContext();
        testNonEntryModuleHelperFailureModuleContext();
        testNestedImportedConstruction();
        testNegativeWrongConstructionOrder();
        testNegativeWrongCrossUnitParent();
        testNegativeDuplicatedBoundary();
        testEmitterTotality();
        System.out.println();
        System.out.println("ClassConstructionDifferentialTest passed=" + passed
            + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
