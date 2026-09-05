package deal.test;

import deal.ast.ClassDeclaration;
import deal.ast.ExportDeclaration;
import deal.ast.ObjectLiteralExpr;
import deal.ast.ProgramNode;
import deal.ast.StatementNode;
import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.NameResolver;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.diagnostics.CompilerDiagnostic;
import deal.lexer.Lexer;
import deal.parser.Parser;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CheckedModuleInput;
import deal.semantic.CheckedModuleKind;
import deal.semantic.CheckedProjectBuilder;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ControlFlowValidator;
import deal.semantic.LoweringSupport;
import deal.semantic.ModuleFact;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ClassInterface;
import deal.semantic.ir.ClassLayout;
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
import deal.semantic.ir.SemanticIdAllocator;
import deal.semantic.ir.SemanticIrDumper;
import deal.semantic.ir.SemanticIrValidator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.StructuredBodyTable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifies the ISSUE-0511 class-declaration lowering arm of
 * {@link SemanticLowerer} (class-construction-jsonable-operations
 * K-D2/K-D3/K-D4): the whole-pipeline slices (lexer/parser/checker &#8594;
 * {@link SemanticLowerer#lowerModuleClassCore} — the class arm of the
 * lowerModule family) for class layouts, {@code CLASS_FACTORY}
 * registration ({@link ClassFactoryRegistry}), and {@code CLASS_DEFAULT}
 * emission.
 *
 * <p>Pinned cases (the task verification):
 * <ol>
 *   <li>an exported class with defaulted fields — one layout in
 *       declaration order with exact descriptors/required/defaultOwner,
 *       one factory op under the pre-allocated
 *       {@code ClassInterface.constructionEntry} id with
 *       {@code classDefaultOpIds} in declaration order, policy
 *       {@code CLASS_CONSTRUCTION}, zero boundary children, registry
 *       bijection, {@code CLASS_DEFAULT} ops with the pinned payloads and
 *       block membership;</li>
 *   <li>an exported class without defaults — factory with empty
 *       {@code classDefaultOpIds};</li>
 *   <li>a non-exported defaulted class — layout plus {@code CLASS_DEFAULT}
 *       ops, no factory, no registry entry;</li>
 *   <li>a non-exported no-default class — layout only, the unit validates
 *       (R-COVERAGE green through the row-extension admission);</li>
 *   <li>multiple classes — declaration-order layouts;</li>
 *   <li>admission positives — a default referencing a module-level
 *       binding and a default containing block-internal
 *       array/table/nested-class literals lower;</li>
 *   <li>admission negative — a default referencing an enclosing
 *       function-local binding fails E6005 with
 *       {@code CLASS_DEFAULT_CAPTURE} and the pinned detail fields;</li>
 *   <li>determinism — two full repetitions produce byte-identical
 *       {@link SemanticIrDumper} dumps;</li>
 *   <li>the produced unit passes {@link SemanticIrValidator} and
 *       {@link ControlFlowValidator} (factory detached from the block
 *       tree per the five-module-level-kind rule).</li>
 * </ol>
 */
public class ClassDeclarationLoweringTest {

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
        // The class-core window's recorded rows: the manifest rows minus
        // the IMPORT_EXPORT_ENTRY row — the class export/import publication
        // ops (EXPORT_PUBLISH/MODULE_IMPORT) are the modules epic's (E10);
        // the declaration arm produces the CLASS_DECLARATION row's ops.
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

    /** The whole-pipeline driver: lexer/parser/checker &#8594; lowerModule. */
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

    /** The module's own interface entry (the constructionEntry authority). */
    private static ClassInterface classEntryOf(String source, String className) {
        CheckedSlice slice = checkSlice(source);
        if (slice == null) {
            return null;
        }
        PipelineSlice pipeline = pipeline(slice);
        if (pipeline == null || pipeline.ownInterface() == null) {
            return null;
        }
        for (ClassInterface entry : pipeline.ownInterface().classes()) {
            if (entry.classId().equals(new ClassId(MODULE.path(), className))) {
                return entry;
            }
        }
        return null;
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

    private static ClassLayout layoutOf(LoweredModuleUnit unit, String className) {
        return unit.classLayouts().get(new ClassId(MODULE.path(), className));
    }

    private static KindPayload.ClassDefaultPayload defaultPayload(SemanticOp op) {
        return (KindPayload.ClassDefaultPayload) op.payload();
    }

    private static KindPayload.ClassFactoryPayload factoryPayload(SemanticOp op) {
        return (KindPayload.ClassFactoryPayload) op.payload();
    }

    private static KindPayload.ClassNewPayload classNewPayload(SemanticOp op) {
        return (KindPayload.ClassNewPayload) op.payload();
    }

    // =========================================================================
    // (a) exported class with defaulted fields
    // =========================================================================

    private static void testExportedClassWithDefaults() {
        System.out.println("-- exported class with defaulted fields: layout, factory, "
            + "CLASS_DEFAULT, registry --");

        String source = """
            export class Point {
              x: int = 40 + 2;
              tag: string = "p" + "t";
            }
            """;
        ClassInterface entry = classEntryOf(source, "Point");
        check(entry != null, "the interface index carries the exported class entry");
        SemanticLowerer.ClassDeclarationCoreResult result = lowerModule(source);
        if (result == null) {
            return;
        }
        check(result.lowering() != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null,
            "the exported-defaulted slice lowers to a validated unit: "
                + (result.lowering() == null ? "null" : result.lowering().diagnostics()));
        if (result.lowering() == null || result.lowering().hasErrors()
                || result.lowering().unit() == null) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        StructuredBodyTable table = result.lowering().table();
        List<SemanticOp> ops = unit.ops();

        // The layout: declaration order, exact descriptors/required/defaultOwner.
        check(unit.classLayouts().size() == 1,
            "exactly one layout; got " + unit.classLayouts().size());
        ClassLayout layout = layoutOf(unit, "Point");
        check(layout != null, "the layout is keyed by the checker-resolved ClassId");
        if (layout == null) {
            return;
        }
        check(layout.classId().equals(new ClassId("main", "Point")),
            "the layout carries the class identity; got " + layout.classId());
        check(layout.fields().size() == 2, "two field layouts; got " + layout.fields().size());
        if (layout.fields().size() != 2) {
            return;
        }
        ClassLayout.FieldLayout x = layout.fields().get(0);
        ClassLayout.FieldLayout tag = layout.fields().get(1);
        check(x.name().equals("x") && x.descriptor().equals(RuntimeDescriptor.Int.INSTANCE)
                && x.required() && x.defaultOwner() == DefaultOwner.LOCAL,
            "field x: name/descriptor/required/defaultOwner pinned; got " + x);
        check(tag.name().equals("tag")
                && tag.descriptor().equals(RuntimeDescriptor.String.INSTANCE)
                && tag.required() && tag.defaultOwner() == DefaultOwner.LOCAL,
            "field tag: name/descriptor/required/defaultOwner pinned; got " + tag);

        // The CLASS_DEFAULT ops: one per defaulted field, pinned payloads.
        List<SemanticOp> defaults = ofKind(ops, SemanticOpKind.CLASS_DEFAULT);
        check(defaults.size() == 2, "two CLASS_DEFAULT ops; got " + defaults.size());
        if (defaults.size() != 2) {
            return;
        }
        ClassId pointId = new ClassId("main", "Point");
        KindPayload.ClassDefaultPayload dx = defaultPayload(defaults.get(0));
        KindPayload.ClassDefaultPayload dt = defaultPayload(defaults.get(1));
        check(dx.classId().equals(pointId) && dx.field().equals("x"),
            "first CLASS_DEFAULT payload pins {classId, field x}; got " + dx);
        check(dt.classId().equals(pointId) && dt.field().equals("tag"),
            "second CLASS_DEFAULT payload pins {classId, field tag}; got " + dt);
        check(defaults.get(0).failurePolicy() == FailurePolicyId.NO_DEAL_FAILURE
                && defaults.get(1).failurePolicy() == FailurePolicyId.NO_DEAL_FAILURE,
            "CLASS_DEFAULT policy NO_DEAL_FAILURE");
        check(defaults.get(0).result() != null && defaults.get(1).result() != null,
            "CLASS_DEFAULT ops publish a result slot");
        check(defaults.get(0).resultType() != null
                && defaults.get(0).resultType().equals(RuntimeDescriptor.Int.INSTANCE)
                && defaults.get(1).resultType() != null
                && defaults.get(1).resultType().equals(RuntimeDescriptor.String.INSTANCE),
            "CLASS_DEFAULT result types are the field descriptors");
        // Block membership: each CLASS_DEFAULT op is a member of exactly its
        // own default block; only the default blocks enter the table.
        for (SemanticOp def : defaults) {
            BlockId defaultBlock = defaultPayload(def).defaultBlock();
            check(table.opBlocks().get(def.opId()) != null
                    && table.opBlocks().get(def.opId()).equals(defaultBlock),
                "CLASS_DEFAULT op " + def.opId() + " is a member of exactly its own "
                    + "default block");
            List<OpId> blockOps = table.blockOps().get(defaultBlock);
            check(blockOps != null && !blockOps.isEmpty()
                    && blockOps.get(0).equals(def.opId()),
                "the default block lists the CLASS_DEFAULT op first; got " + blockOps);
            for (int i = 1; i < blockOps.size(); i++) {
                SemanticOp blockOp = opById(unit, blockOps.get(i));
                check(blockOp != null && def.opId().equals(blockOp.origin().parentOpId()),
                    "the default-block op " + blockOps.get(i) + " records the "
                        + "CLASS_DEFAULT op as parentOpId");
            }
        }
        // The default expression ops: 40 + 2 -> CONST, CONST, BINARY with
        // INT32_ADD; "p" + "t" -> CONST, CONST, STRING_CONCAT.
        BlockId xBlock = dx.defaultBlock();
        List<OpId> xOps = table.blockOps().get(xBlock);
        check(xOps != null && xOps.size() == 4, "the x default block carries the op plus "
            + "CONST/CONST/BINARY; got " + xOps);
        if (xOps != null && xOps.size() == 4) {
            SemanticOp binary = opById(unit, xOps.get(3));
            check(binary != null && binary.kind() == SemanticOpKind.BINARY,
                "the x default expression lowers to a BINARY op");
        }
        BlockId tagBlock = dt.defaultBlock();
        List<OpId> tagOps = table.blockOps().get(tagBlock);
        check(tagOps != null && tagOps.size() == 4, "the tag default block carries the op plus "
            + "CONST/CONST/STRING_CONCAT; got " + tagOps);
        if (tagOps != null && tagOps.size() == 4) {
            SemanticOp concat = opById(unit, tagOps.get(3));
            check(concat != null && concat.kind() == SemanticOpKind.STRING_CONCAT,
                "the tag default expression lowers to a STRING_CONCAT op");
        }

        // The factory: one op under the pre-allocated constructionEntry id.
        List<SemanticOp> factories = ofKind(ops, SemanticOpKind.CLASS_FACTORY);
        check(factories.size() == 1, "one CLASS_FACTORY op; got " + factories.size());
        if (factories.size() != 1 || entry == null) {
            return;
        }
        SemanticOp factory = factories.get(0);
        check(result.registry().factories().size() == 1,
            "the registry carries exactly one binding; got "
                + result.registry().factories());
        check(result.registry().factoryFor(entry.constructionEntry()) != null
                && result.registry().factoryFor(entry.constructionEntry())
                    .equals(factory.opId()),
            "the factory op is registered under the pre-allocated constructionEntry "
                + entry.constructionEntry());
        KindPayload.ClassFactoryPayload factoryP = factoryPayload(factory);
        check(factoryP.classId().equals(pointId), "the factory payload pins the classId");
        check(factoryP.classDefaultOpIds().equals(List.of(defaults.get(0).opId(),
                defaults.get(1).opId())),
            "the factory's classDefaultOpIds are the defaulted fields' CLASS_DEFAULT "
                + "op ids in declaration order; got " + factoryP.classDefaultOpIds());
        check(factoryP.callerOpRef() != null, "the factory payload carries the deterministic "
            + "callerOpRef wiring slot");
        check(factory.failurePolicy() == FailurePolicyId.CLASS_CONSTRUCTION,
            "the factory policy is CLASS_CONSTRUCTION; got " + factory.failurePolicy());
        // Zero boundary children: no BOUNDARY op parents to the factory.
        boolean boundaryChild = false;
        for (SemanticOp op : ops) {
            if (op.kind() == SemanticOpKind.BOUNDARY
                    && factory.opId().equals(op.origin().parentOpId())) {
                boundaryChild = true;
            }
        }
        check(!boundaryChild, "the factory has zero boundary children");
        // Detached: absent from the block-membership table.
        boolean inTable = false;
        for (List<OpId> listed : table.blockOps().values()) {
            if (listed.contains(factory.opId())) {
                inTable = true;
            }
        }
        check(!inTable && !table.opBlocks().containsKey(factory.opId()),
            "the factory is detached from the block tree (the five-module-level-kind rule)");
    }

    // =========================================================================
    // (b) exported class without defaults
    // =========================================================================

    private static void testExportedClassWithoutDefaults() {
        System.out.println("-- exported class without defaults: factory with empty "
            + "classDefaultOpIds --");

        String source = "export class Empty { x: int; }";
        ClassInterface entry = classEntryOf(source, "Empty");
        check(entry != null, "the interface index carries the exported no-default class entry");
        SemanticLowerer.ClassDeclarationCoreResult result = lowerModule(source);
        if (result == null) {
            return;
        }
        check(result.lowering() != null && !result.lowering().hasErrors(),
            "the exported-no-default slice lowers: "
                + (result.lowering() == null ? "null" : result.lowering().diagnostics()));
        if (result.lowering() == null || result.lowering().hasErrors()) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        ClassLayout layout = layoutOf(unit, "Empty");
        check(layout != null && layout.fields().size() == 1
                && layout.fields().get(0).name().equals("x")
                && layout.fields().get(0).required(),
            "the layout records the no-default required field; got " + layout);
        List<SemanticOp> defaults = ofKind(unit.ops(), SemanticOpKind.CLASS_DEFAULT);
        check(defaults.isEmpty(), "no CLASS_DEFAULT ops; got " + defaults.size());
        List<SemanticOp> factories = ofKind(unit.ops(), SemanticOpKind.CLASS_FACTORY);
        check(factories.size() == 1, "one CLASS_FACTORY op; got " + factories.size());
        if (factories.size() == 1) {
            check(factoryPayload(factories.get(0)).classDefaultOpIds().isEmpty(),
                "the factory's classDefaultOpIds is empty");
            check(factories.get(0).failurePolicy() == FailurePolicyId.CLASS_CONSTRUCTION,
                "the factory policy is CLASS_CONSTRUCTION");
        }
        check(entry != null && result.registry().factories().size() == 1
                && result.registry().factoryFor(entry.constructionEntry()) != null,
            "the factory is registered under the constructionEntry");
    }

    // =========================================================================
    // (c) non-exported defaulted class
    // =========================================================================

    private static void testNonExportedDefaultedClass() {
        System.out.println("-- non-exported defaulted class: layout + CLASS_DEFAULT, no "
            + "factory --");

        String source = """
            class Hidden {
              y?: int;
              v: int = 5;
            }
            """;
        SemanticLowerer.ClassDeclarationCoreResult result = lowerModule(source);
        if (result == null) {
            return;
        }
        check(result.lowering() != null && !result.lowering().hasErrors(),
            "the non-exported-defaulted slice lowers: "
                + (result.lowering() == null ? "null" : result.lowering().diagnostics()));
        if (result.lowering() == null || result.lowering().hasErrors()) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        ClassLayout layout = layoutOf(unit, "Hidden");
        check(layout != null && layout.fields().size() == 2,
            "the layout records both fields; got " + layout);
        if (layout == null || layout.fields().size() != 2) {
            return;
        }
        check(!layout.fields().get(0).required() && layout.fields().get(0).name().equals("y"),
            "the optional field records required=false; got " + layout.fields().get(0));
        check(layout.fields().get(1).required() && layout.fields().get(1).name().equals("v"),
            "the defaulted field records required=true; got " + layout.fields().get(1));
        List<SemanticOp> defaults = ofKind(unit.ops(), SemanticOpKind.CLASS_DEFAULT);
        check(defaults.size() == 1, "one CLASS_DEFAULT op for the defaulted field; got "
            + defaults.size());
        if (defaults.size() == 1) {
            check(defaultPayload(defaults.get(0)).field().equals("v"),
                "the CLASS_DEFAULT payload names the defaulted field");
        }
        List<SemanticOp> factories = ofKind(unit.ops(), SemanticOpKind.CLASS_FACTORY);
        check(factories.isEmpty(), "no CLASS_FACTORY op; got " + factories.size());
        check(result.registry().factories().isEmpty(),
            "no registry entry; got " + result.registry().factories());
    }

    // =========================================================================
    // (d) non-exported no-default class (layout only)
    // =========================================================================

    private static void testNonExportedNoDefaultClass() {
        System.out.println("-- non-exported no-default class: layout only, unit validates --");

        String source = "class Ghost { w: int; }";
        SemanticLowerer.ClassDeclarationCoreResult result = lowerModule(source);
        if (result == null) {
            return;
        }
        check(result.lowering() != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null,
            "the layout-only slice lowers to a validated unit (R-COVERAGE green): "
                + (result.lowering() == null ? "null" : result.lowering().diagnostics()));
        if (result.lowering() == null || result.lowering().hasErrors()
                || result.lowering().unit() == null) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        ClassLayout layout = layoutOf(unit, "Ghost");
        check(layout != null && layout.fields().size() == 1
                && layout.fields().get(0).name().equals("w")
                && layout.fields().get(0).required()
                && layout.fields().get(0).defaultOwner() == DefaultOwner.LOCAL,
            "the layout-only class records its layout; got " + layout);
        check(ofKind(unit.ops(), SemanticOpKind.CLASS_DEFAULT).isEmpty()
                && ofKind(unit.ops(), SemanticOpKind.CLASS_FACTORY).isEmpty(),
            "no vacuous op is invented for the layout-only shape");
        check(result.registry().factories().isEmpty(), "no registry entry");
        // The recorded CLASS_DECLARATION row is satisfied by the layout record
        // (the row-extension admission) — re-validated directly.
        check(unit.constructCoverage().containsKey(ConstructKind.CLASS_DECLARATION),
            "the unit records the CLASS_DECLARATION constructCoverage row");
        check(SemanticIrValidator.validate(unit,
                new SemanticIrValidator.ComparisonFacts(unit.interfaceHash(),
                    SemanticProfile.DEAL_V1_2_INT32, REGISTRY_HASH)).isEmpty(),
            "the layout-only unit passes the closed validator");
        check(ControlFlowValidator.validate(unit, result.lowering().table()).isEmpty(),
            "the layout-only unit passes the control-flow validator");
    }

    // =========================================================================
    // (e) multiple classes: declaration-order layouts
    // =========================================================================

    private static void testMultipleClassesDeclarationOrder() {
        System.out.println("-- multiple classes: declaration-order layouts --");

        String source = """
            class A { x: int = 1; }
            export class B { y: int = 2; }
            class C { z?: int; }
            """;
        SemanticLowerer.ClassDeclarationCoreResult result = lowerModule(source);
        if (result == null) {
            return;
        }
        check(result.lowering() != null && !result.lowering().hasErrors(),
            "the multi-class slice lowers: "
                + (result.lowering() == null ? "null" : result.lowering().diagnostics()));
        if (result.lowering() == null || result.lowering().hasErrors()) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        List<ClassId> keys = new ArrayList<>(unit.classLayouts().keySet());
        check(keys.equals(List.of(new ClassId("main", "A"), new ClassId("main", "B"),
                new ClassId("main", "C"))),
            "the layouts are keyed in declaration order; got " + keys);
        check(result.registry().factories().size() == 1,
            "only the exported class registers a factory; got "
                + result.registry().factories());
        for (OpId factory : result.registry().factories().values()) {
            SemanticOp op = opById(unit, factory);
            check(op != null && op.kind() == SemanticOpKind.CLASS_FACTORY
                    && factoryPayload(op).classId().equals(new ClassId("main", "B")),
                "the single factory belongs to class B");
        }
        List<SemanticOp> defaults = ofKind(unit.ops(), SemanticOpKind.CLASS_DEFAULT);
        check(defaults.size() == 2, "two CLASS_DEFAULT ops (A.x, B.y); got " + defaults.size());
    }

    // =========================================================================
    // (f) admission positives
    // =========================================================================

    private static void testAdmissionPositives() {
        System.out.println("-- admission positives: module-level binding + block-internal "
            + "array/table/nested-class literals --");

        String source = """
            let base: int = 41;
            class Point { x: int; }
            export class Line {
              a: int = base + 1;
              b: int[] = [1, 2, 3];
              c: table = {k: "v"};
              p: Point = {x: 7};
            }
            """;
        SemanticLowerer.ClassDeclarationCoreResult result = lowerModule(source);
        if (result == null) {
            return;
        }
        check(result.lowering() != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null,
            "the admission-positive slice lowers: "
                + (result.lowering() == null ? "null" : result.lowering().diagnostics()));
        if (result.lowering() == null || result.lowering().hasErrors()
                || result.lowering().unit() == null) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        StructuredBodyTable table = result.lowering().table();
        List<SemanticOp> defaults = ofKind(unit.ops(), SemanticOpKind.CLASS_DEFAULT);
        check(defaults.size() == 4, "four CLASS_DEFAULT ops; got " + defaults.size());
        if (defaults.size() != 4) {
            return;
        }
        // The module-level-binding default (field a): the block contains a
        // BINDING_LOAD resolving the module-level binding.
        KindPayload.ClassDefaultPayload a = defaultPayload(defaults.get(0));
        boolean moduleLoad = false;
        for (OpId listed : table.blockOps().getOrDefault(a.defaultBlock(), List.of())) {
            SemanticOp op = opById(unit, listed);
            if (op != null && op.kind() == SemanticOpKind.BINDING_LOAD
                    && op.origin().parentOpId() != null
                    && op.origin().parentOpId().equals(defaults.get(0).opId())) {
                moduleLoad = true;
            }
        }
        check(a.field().equals("a") && moduleLoad,
            "the module-level-binding default lowers a BINDING_LOAD nested under the "
                + "CLASS_DEFAULT op");
        // The array/table/nested-class defaults: ARRAY_NEW, TABLE_NEW, CLASS_NEW
        // inside their default blocks.
        boolean arrayNew = false;
        boolean tableNew = false;
        boolean classNew = false;
        for (int i = 1; i < defaults.size(); i++) {
            BlockId block = defaultPayload(defaults.get(i)).defaultBlock();
            for (OpId listed : table.blockOps().getOrDefault(block, List.of())) {
                SemanticOp op = opById(unit, listed);
                if (op == null) {
                    continue;
                }
                if (op.kind() == SemanticOpKind.ARRAY_NEW) {
                    arrayNew = true;
                }
                if (op.kind() == SemanticOpKind.TABLE_NEW) {
                    tableNew = true;
                }
                if (op.kind() == SemanticOpKind.CLASS_NEW) {
                    classNew = true;
                    // The nested-class literal's field boundary parents to the
                    // CLASS_NEW op and sits in its fieldBoundaries list.
                    KindPayload.ClassNewPayload payload = classNewPayload(op);
                    check(payload.classId().equals(new ClassId("main", "Point"))
                            && payload.defaultOwner() == DefaultOwner.LOCAL
                            && payload.providedFields().size() == 1
                            && payload.providedFields().get(0).name().equals("x")
                            && payload.classDefaultOpIds().isEmpty()
                            && payload.fieldBoundaries().size() == 1
                            && payload.fieldBoundaries().get(0).kind()
                                == BoundaryKind.CLASS_LITERAL_FIELD,
                        "the nested-class literal lowers a CLASS_NEW with the pinned LOCAL "
                            + "payload; got " + payload);
                    SemanticOp boundary = opById(unit,
                        payload.fieldBoundaries().get(0).boundaryOpId());
                    check(boundary != null && boundary.kind() == SemanticOpKind.BOUNDARY
                            && op.opId().equals(boundary.origin().parentOpId()),
                        "the CLASS_LITERAL_FIELD boundary parents to the CLASS_NEW op");
                    check(boundary != null && boundary.origin().parentOpId().equals(op.opId())
                            && op.opId().equals(
                                payload.fieldBoundaries().get(0).boundaryOpId().equals(
                                    boundary.opId()) ? op.opId() : null),
                        "the field boundary entry names the boundary op");
                }
            }
        }
        check(arrayNew, "the array-literal default lowers an ARRAY_NEW in its default block");
        check(tableNew, "the table-literal default lowers a TABLE_NEW in its default block");
        check(classNew, "the nested-class default lowers a CLASS_NEW in its default block");
        // A nested class in a function body with a literal-only default is an
        // admission positive too (no enclosing-region reference).
        SemanticLowerer.ClassDeclarationCoreResult nested = lowerModule("""
            function make(): null {
              class Point { x: int; }
              class Line { p: Point = {x: 3}; }
            }
            """);
        check(nested != null && nested.lowering() != null && !nested.lowering().hasErrors()
                && nested.lowering().unit() != null,
            "a nested class declaration with a literal-only default lowers: "
                + (nested == null || nested.lowering() == null ? "null"
                    : nested.lowering().diagnostics()));
    }

    // =========================================================================
    // (g) admission negative
    // =========================================================================

    private static void testAdmissionNegative() {
        System.out.println("-- admission negative: enclosing function-local reference -> "
            + "E6005 CLASS_DEFAULT_CAPTURE --");

        SemanticLowerer.ClassDeclarationCoreResult result = lowerModule("""
            function make(n: int): null {
              class Inner { v: int = n; }
            }
            """);
        check(result != null && result.lowering() != null && result.lowering().hasErrors()
                && result.lowering().unit() == null,
            "the enclosing-region reference fails lowering with no unit: "
                + (result == null || result.lowering() == null ? "null"
                    : result.lowering().diagnostics()));
        if (result != null && result.lowering() != null && result.lowering().hasErrors()) {
            CompilerDiagnostic diagnostic = result.lowering().diagnostics().get(0);
            check("E6005".equals(diagnostic.code()), "the failure is E6005; got "
                + diagnostic.code());
            check(diagnostic.message().contains(SemanticLowerer.CLASS_DEFAULT_CAPTURE),
                "the failure names the CLASS_DEFAULT_CAPTURE validator rule");
            check(diagnostic.message().contains("capability CLASSES"),
                "the failure detail carries capability CLASSES");
            check(diagnostic.message().contains("module 'main'"),
                "the failure detail carries the module");
            check(diagnostic.message().contains("SemanticLowerer CLASS_DEFAULT_CAPTURE"),
                "the failure detail carries the SemanticLowerer origin");
        }

        // A function-local let variant (the let is the enclosing-region binding).
        SemanticLowerer.ClassDeclarationCoreResult letVariant = lowerModule("""
            function make(): null {
              let local: int = 5
              class Inner { v: int = local; }
            }
            """);
        check(letVariant != null && letVariant.lowering() != null
                && letVariant.lowering().hasErrors()
                && letVariant.lowering().unit() == null
                && letVariant.lowering().diagnostics().get(0).message()
                    .contains(SemanticLowerer.CLASS_DEFAULT_CAPTURE),
            "the function-local let variant fails with CLASS_DEFAULT_CAPTURE");
    }

    // =========================================================================
    // (h) determinism
    // =========================================================================

    private static void testDeterminism() {
        System.out.println("-- determinism: two repetitions are byte-identical --");

        String source = """
            let base: int = 41;
            class Point { x: int; }
            export class Line {
              a: int = base + 1;
              b: int[] = [1, 2, 3];
              p: Point = {x: 7};
            }
            """;
        SemanticLowerer.ClassDeclarationCoreResult first = lowerModule(source);
        SemanticLowerer.ClassDeclarationCoreResult second = lowerModule(source);
        check(first != null && !first.lowering().hasErrors()
                && second != null && !second.lowering().hasErrors(),
            "both repetitions lower");
        if (first == null || first.lowering().hasErrors()
                || second == null || second.lowering().hasErrors()) {
            return;
        }
        byte[] firstDump = SemanticIrDumper.dumpModule(first.lowering().unit());
        byte[] secondDump = SemanticIrDumper.dumpModule(second.lowering().unit());
        check(java.util.Arrays.equals(firstDump, secondDump),
            "the two repetitions produce byte-identical SemanticIrDumper dumps");
        check(first.registry().factories().equals(second.registry().factories()),
            "the two repetitions produce identical factory registries");
        check(first.lowering().unit().classLayouts()
                .equals(second.lowering().unit().classLayouts()),
            "the two repetitions produce identical class layouts");
    }

    // =========================================================================
    // (i) validator passage and the detached factory
    // =========================================================================

    private static void testValidatorPassage() {
        System.out.println("-- produced units pass SemanticIrValidator and "
            + "ControlFlowValidator --");

        SemanticLowerer.ClassDeclarationCoreResult result = lowerModule("""
            let base: int = 41;
            class Point { x: int; }
            export class Line {
              a: int = base + 1;
              b: int[] = [1, 2, 3];
              p: Point = {x: 7};
            }
            """);
        if (result == null) {
            return;
        }
        check(result.lowering() != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null,
            "the representative slice lowers");
        if (result.lowering() == null || result.lowering().hasErrors()) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        check(SemanticIrValidator.validate(unit,
                new SemanticIrValidator.ComparisonFacts(unit.interfaceHash(),
                    SemanticProfile.DEAL_V1_2_INT32, REGISTRY_HASH)).isEmpty(),
            "the produced unit passes the closed SemanticIrValidator");
        check(ControlFlowValidator.validate(unit, result.lowering().table()).isEmpty(),
            "the produced unit passes the ControlFlowValidator");
        // The factory is detached from the block tree (never part of the
        // module-init flow) while every CLASS_DEFAULT op is a block member.
        List<SemanticOp> factories = ofKind(unit.ops(), SemanticOpKind.CLASS_FACTORY);
        check(factories.size() == 1, "one factory op");
        if (factories.size() == 1) {
            OpId factoryId = factories.get(0).opId();
            boolean listed = false;
            for (List<OpId> block : result.lowering().table().blockOps().values()) {
                if (block.contains(factoryId)) {
                    listed = true;
                }
            }
            check(!listed, "the factory is detached from the block tree");
        }
        for (SemanticOp def : ofKind(unit.ops(), SemanticOpKind.CLASS_DEFAULT)) {
            check(result.lowering().table().opBlocks().containsKey(def.opId()),
                "every CLASS_DEFAULT op is a member of exactly one block");
        }
    }

    // =========================================================================
    // Unchanged-boundary pins
    // =========================================================================

    private static void testUnchangedBoundaries() {
        System.out.println("-- unchanged boundaries: non-class windows keep the E9 "
            + "fail-closed arms --");

        // The E5 window's lowerModule still fails class declarations hard
        // (no unit) and the class-literal arm still names CLASS_NEW.
        CheckedSlice slice = checkSlice("""
            class Point { x: int; }
            function f(): null {
              let p: Point = {x: 1}
              return null
            }
            """);
        if (slice != null) {
            SemanticLowerer.LoweringResult e5 = SemanticLowerer.lowerModule(moduleOf(slice),
                SemanticProfile.DEAL_V1_2_INT32, Map.of(), "hash", REGISTRY_HASH,
                SemanticIdAllocator.over(List.of(MODULE)));
            check(e5.hasErrors() && e5.unit() == null,
                "the E5 lowerModule window still fails class declarations hard");
            ObjectLiteralExpr literal = null;
            for (StatementNode statement : slice.program().statements()) {
                literal = findObjectLiteral(statement);
                if (literal != null) {
                    break;
                }
            }
            if (literal != null) {
                SemanticLowerer.ModuleLowerer lowerer = new SemanticLowerer.ModuleLowerer(
                    MODULE, SOURCE_ID, slice.checks(),
                    SemanticIdAllocator.over(List.of(MODULE)));
                RuntimeException defect = null;
                try {
                    lowerer.lowerExpression(literal);
                } catch (SemanticLowerer.ConstructUnlowered unlowered) {
                    defect = unlowered;
                }
                check(defect != null && defect.getMessage() != null
                        && defect.getMessage().contains("CLASS_NEW"),
                    "the non-class window's class-literal arm keeps the CLASS_NEW "
                        + "fail-closed message");
            }
            // A non-class export declaration stays E10's in the class window.
            SemanticLowerer.ClassDeclarationCoreResult exportLet = lowerModule("""
                export function f(): null { }
                """);
            check(exportLet != null && exportLet.lowering().hasErrors()
                    && exportLet.lowering().unit() == null
                    && exportLet.lowering().diagnostics().get(0).message()
                        .contains(SemanticLowerer.CONSTRUCT_UNLOWERED),
                "a non-class export declaration stays the modules epic's (E6005 "
                    + "CONSTRUCT_UNLOWERED)");
        }
    }

    private static ObjectLiteralExpr findObjectLiteral(StatementNode statement) {
        List<StatementNode> stack = new ArrayList<>(List.of(statement));
        while (!stack.isEmpty()) {
            StatementNode current = stack.remove(stack.size() - 1);
            if (current instanceof deal.ast.Block block) {
                stack.addAll(block.statements());
            } else if (current instanceof deal.ast.FunctionDeclaration function
                    && function.body() != null) {
                stack.add(function.body());
            } else if (current instanceof deal.ast.VariableDeclaration declaration) {
                ObjectLiteralExpr found = findInExpression(declaration.initializer());
                if (found != null) {
                    return found;
                }
            } else if (current instanceof deal.ast.ExpressionStatement expressionStatement) {
                ObjectLiteralExpr found = findInExpression(expressionStatement.expr());
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static ObjectLiteralExpr findInExpression(deal.ast.ExpressionNode expression) {
        if (expression instanceof ObjectLiteralExpr literal) {
            return literal;
        }
        List<deal.ast.ExpressionNode> stack = new ArrayList<>(List.of(expression));
        while (!stack.isEmpty()) {
            deal.ast.ExpressionNode current = stack.remove(stack.size() - 1);
            if (current instanceof ObjectLiteralExpr literal) {
                return literal;
            }
            if (current instanceof deal.ast.BinaryExpr binary) {
                stack.add(binary.left());
                stack.add(binary.right());
            } else if (current instanceof deal.ast.AssignmentExpr assignment) {
                stack.add(assignment.target());
                stack.add(assignment.value());
            } else if (current instanceof deal.ast.MemberAccessExpr access) {
                stack.add(access.object());
            } else if (current instanceof deal.ast.IndexExpr index) {
                stack.add(index.array());
                stack.add(index.index());
            }
        }
        return null;
    }

    private static SemanticOp opById(LoweredModuleUnit unit, OpId opId) {
        for (SemanticOp op : unit.ops()) {
            if (op.opId().equals(opId)) {
                return op;
            }
        }
        return null;
    }

    // =========================================================================
    // Runner
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Class Declaration Lowering Tests (ISSUE-0511 declaration arm) ===\n");

        testExportedClassWithDefaults();
        testExportedClassWithoutDefaults();
        testNonExportedDefaultedClass();
        testNonExportedNoDefaultClass();
        testMultipleClassesDeclarationOrder();
        testAdmissionPositives();
        testAdmissionNegative();
        testDeterminism();
        testValidatorPassage();
        testUnchangedBoundaries();

        System.out.println("\nClassDeclarationLoweringTest: " + passed + " passed, "
            + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
