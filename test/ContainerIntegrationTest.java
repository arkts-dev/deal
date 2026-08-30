package deal.test;

import deal.ast.ArrayLiteralExpr;
import deal.ast.BinaryExpr;
import deal.ast.BinaryOp;
import deal.ast.Block;
import deal.ast.Either;
import deal.ast.ExpressionNode;
import deal.ast.ForOfStatement;
import deal.ast.IdentifierExpr;
import deal.ast.LiteralExpr;
import deal.ast.MemberAccessExpr;
import deal.ast.ObjectLiteralExpr;
import deal.ast.ProgramNode;
import deal.ast.Span;
import deal.ast.StatementNode;
import deal.ast.TemplateLiteralExpr;
import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.NameResolver;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CheckedModuleInput;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CheckedProjectBuilder;
import deal.semantic.CheckedProjectInput;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ContainerClaimingSeam;
import deal.semantic.LoweringSupport;
import deal.semantic.ModuleFact;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticRequirementManifest;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.BoundaryRealization;
import deal.semantic.ir.ClosedSelector;
import deal.semantic.ir.ConstructKind;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.ControlSelector;
import deal.semantic.ir.ExportPlan;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.IterationMode;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.LoweringContextHash;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ModuleInitPlan;
import deal.semantic.ir.OpId;
import deal.semantic.ir.OpResultType;
import deal.semantic.ir.OperationContractSnapshot;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.ScalarValue;
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
import deal.semantic.ir.ValueId;
import deal.types.Type;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The epic's decomposition tail (ISSUE-0388): the layered fixed-corpus
 * integration verification of ISSUE-0232, closing the epic with
 * {@code ./run_tests.sh} exiting 0.
 *
 * <p><b>Layer 1 (runs at E3's gate).</b> The positionable whole-pipeline
 * corpus — for-of statements over string-typed iterables embedding string
 * literals, string {@code +}, templates, and the nested-for-of
 * loop-binding load — runs through checked project ({@link
 * CheckedProjectBuilder}) &rarr; foundation detector ({@link
 * LoweringSupport#computeManifests}) &rarr; E3 lowering arms ({@link
 * SemanticLowerer#lowerModule}) &rarr; validated unit ({@link
 * SemanticIrValidator}) &rarr; deterministic dump ({link
 * SemanticIrDumper}) with every recorded row produced (including
 * {@code IDENTIFIER → BINDING_LOAD}), the ∅-claim staged units with
 * recorded staged hand-offs, and byte-identical repeats across two full
 * repetitions.</p>
 *
 * <p><b>Layer 2 (runs at the same gate).</b> Per-construct checked-source
 * slices for every D1 arm — scalar literals, identifiers (the
 * loop-binding load), array literals, table literals, string {@code +},
 * templates, array {@code .length}, table member reads, and string for-of
 * — each asserted against the exact pinned shapes, orders, and
 * policies.</p>
 *
 * <p><b>Layer 3 (pinned now; its whole-pipeline run executes at the
 * valid-until gate E5's gate).</b> The full fixed source corpus (arrays,
 * tables, templates, length, scalar iteration) is committed as one module
 * in {@code test/fixtures/container-fixed-corpus.deal} with the pinned
 * construct list — the array literal, table literal, string {@code +},
 * template, and array {@code .length} expressions each in an expression
 * statement (→ {@code DISCARD}, E5's C-D8 arm; the parser reads a
 * statement-leading {@code {} as a block, so the pinned table-literal
 * statement is the parenthesized form — the parser unwraps the parens and
 * the AST expression is the table literal itself); the table member read in
 * an if-condition with an empty then-block (→ {@code BRANCH}, E5's C-D7
 * arm — the if-condition is the only variable-free table-read context at
 * E5's gate); the string for-of with a string-literal iterable and an
 * empty body; no while/for, no return, no array for-of, no variable
 * declaration, and no numeric literal. The gate probes E5's two
 * positioning production arms (expression-statement {@code DISCARD} and
 * if-condition {@code BRANCH}): in E3's window both probes fail with the
 * pinned E6005 {@code CONSTRUCT_UNLOWERED}, so the gate records E5's gate
 * as the valid-until boundary and does not execute the full-corpus
 * pipeline — the refusal itself is asserted as correct stage behavior,
 * never a defect; when the E5 arms land (both probes succeed), the same
 * test executes the corpus's whole-pipeline run and verifies exactly this
 * design's shapes, orders, and determinism and the pinned claim state —
 * {@code {DESCRIPTORS, BOUNDARIES}} claimed;
 * {@code FOUNDATION_VALUES}, {@code CONTAINERS_AND_STRINGS}, and
 * {@code EVALUATION_ORDER} (the corpus's {@code BRANCH}/{@code DISCARD}
 * ops without {@code LOOP}) deferred per unit — R-CAPABILITY and
 * R-COVERAGE green with no E6005. An extension adding a while/for
 * statement would change the pinned claim state and is outside this
 * epic's pinned integration tail.</p>
 *
 * <p><b>Fault injection.</b> Named constituent failures, never generic
 * red flags: a removed produced row fails R-COVERAGE (layer 1); a
 * corrupted claim state fails the claiming seam (layer 3); a changed
 * construct breaks a slice (layer 2).</p>
 */
public class ContainerIntegrationTest {

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
    private static final String INTERFACE_HASH = new ProjectInterfaceIndex(
        ProjectInterfaceIndex.FORMAT_VERSION, Map.of(MODULE,
            new deal.semantic.ir.ExternalModuleInterface(MODULE,
                deal.semantic.ir.ExternalModuleKind.IMPLEMENTATION,
                List.of(), List.of(), List.of(),
                deal.semantic.ir.InitializationMode.ONCE_AFTER_DEPENDENCIES)))
        .interfaceIndexDigest();

    private static SemanticIrValidator.ComparisonFacts facts() {
        return new SemanticIrValidator.ComparisonFacts(INTERFACE_HASH,
            SemanticProfile.DEAL_V1_2_INT32, REGISTRY_HASH);
    }

    private static SemanticLowerer.ModuleLowerer lowerer(CheckResult checks) {
        return new SemanticLowerer.ModuleLowerer(MODULE, SOURCE_ID, checks,
            SemanticIdAllocator.over(List.of(MODULE)));
    }

    private record CheckedSlice(ProgramNode program, SymbolTable symbols, CheckResult checks) {
    }

    private static CheckedSlice checkSlice(String source) {
        return checkSlice(source, null);
    }

    private static CheckedSlice checkSlice(String source, ModuleResolver resolver) {
        LexResult lex = new Lexer(source, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        check(parse.diagnostics().isEmpty(), "the slice parses cleanly: " + parse.diagnostics());
        if (!parse.diagnostics().isEmpty()) {
            return null;
        }
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        check(nr.diagnostics().isEmpty(), "the slice resolves cleanly: " + nr.diagnostics());
        if (!nr.diagnostics().isEmpty()) {
            return null;
        }
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());
        check(result.diagnostics().isEmpty(),
            "the slice checks cleanly: " + result.diagnostics());
        if (result.hasErrors()) {
            return null;
        }
        return new CheckedSlice(parse.program(), symTable, result);
    }

    // =========================================================================
    // The whole-pipeline runner: checked project → foundation detector →
    // lowering arms (+ seam + validator)
    // =========================================================================

    private record PipelineArtifacts(CheckedProjectInput input, ProjectInterfaceIndex index,
                                     SemanticRequirementManifest manifest) {
    }

    private static PipelineArtifacts buildPipeline(CheckedSlice slice) {
        CompilerInvocation invocation = CompilerProfileProvider.resolveCommonShadow(
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.V1_2_ACTIVE,
            CapabilityRegistry.releaseRegistry());
        ModuleFact fact = new ModuleFact(SOURCE_ID, MODULE, false, false, slice.program(),
            Map.of(), slice.symbols(), slice.checks(), List.of());
        CheckedProjectBuildResult built = CheckedProjectBuilder.build(invocation, MODULE,
            List.of(fact));
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
        check(manifests != null && manifests.diagnostics().isEmpty() && manifests.manifests() != null
                && manifests.manifests().size() == 1,
            "the foundation detector produces exactly one requirement manifest: "
                + (manifests == null ? "null" : manifests.diagnostics()));
        if (manifests == null || !manifests.diagnostics().isEmpty()
                || manifests.manifests() == null || manifests.manifests().size() != 1) {
            return null;
        }
        return new PipelineArtifacts(built.input(), built.index(), manifests.manifests().get(0));
    }

    private static SemanticLowerer.LoweringResult lowerPipeline(PipelineArtifacts artifacts) {
        CheckedModuleInput module = artifacts.input().modules().get(0);
        return SemanticLowerer.lowerModule(module, SemanticProfile.DEAL_V1_2_INT32,
            artifacts.manifest().constructCoverage(),
            artifacts.index().interfaceIndexDigest(), REGISTRY_HASH,
            SemanticIdAllocator.over(List.of(MODULE)));
    }

    // =========================================================================
    // AST finders (source-order walk over statements and expressions)
    // =========================================================================

    private static void collectStatements(StatementNode node, List<StatementNode> outStatements,
                                          List<ExpressionNode> outExpressions) {
        if (node == null) {
            return;
        }
        outStatements.add(node);
        switch (node) {
            case deal.ast.FunctionDeclaration fd ->
                collectStatements(fd.body(), outStatements, outExpressions);
            case deal.ast.VariableDeclaration vd ->
                collectExpressions(vd.initializer(), outExpressions);
            case deal.ast.ReturnStatement rs ->
                rs.expr().ifPresent(e -> collectExpressions(e, outExpressions));
            case deal.ast.IfStatement is -> {
                collectExpressions(is.condition(), outExpressions);
                collectStatements(is.thenBlock(), outStatements, outExpressions);
                is.elseBranch().ifPresent(branch -> {
                    if (branch instanceof Either.Left<deal.ast.IfStatement, Block> left) {
                        collectStatements(left.value(), outStatements, outExpressions);
                    } else if (branch
                            instanceof Either.Right<deal.ast.IfStatement, Block> right) {
                        collectStatements(right.value(), outStatements, outExpressions);
                    }
                });
            }
            case deal.ast.WhileStatement ws -> {
                collectExpressions(ws.condition(), outExpressions);
                collectStatements(ws.body(), outStatements, outExpressions);
            }
            case deal.ast.ForStatement fs -> {
                fs.init().ifPresent(init -> {
                    if (init instanceof deal.ast.ForInit.VarDecl decl) {
                        collectExpressions(decl.decl().initializer(), outExpressions);
                    } else if (init instanceof deal.ast.ForInit.AssignExpr assign) {
                        collectExpressions(assign.expr(), outExpressions);
                    }
                });
                fs.condition().ifPresent(c -> collectExpressions(c, outExpressions));
                fs.update().ifPresent(u -> collectExpressions(u, outExpressions));
                collectStatements(fs.body(), outStatements, outExpressions);
            }
            case ForOfStatement fos -> {
                collectExpressions(fos.iterable(), outExpressions);
                collectStatements(fos.body(), outStatements, outExpressions);
            }
            case deal.ast.ExpressionStatement es -> collectExpressions(es.expr(), outExpressions);
            case deal.ast.ExportDeclaration ed ->
                collectStatements(ed.declaration(), outStatements, outExpressions);
            case deal.ast.DeleteStatement ds -> collectExpressions(ds.target(), outExpressions);
            case deal.ast.TryStatement ts -> {
                collectStatements(ts.tryBlock(), outStatements, outExpressions);
                collectStatements(ts.catchBlock(), outStatements, outExpressions);
            }
            case deal.ast.ThrowStatement th -> collectExpressions(th.expr(), outExpressions);
            case deal.ast.Block b -> {
                for (StatementNode s : b.statements()) {
                    collectStatements(s, outStatements, outExpressions);
                }
            }
            case deal.ast.ClassDeclaration cd -> {
                for (deal.ast.ClassField f : cd.fields()) {
                    f.defaultExpr().ifPresent(e -> collectExpressions(e, outExpressions));
                }
            }
            default -> { /* Break/Continue/Import carry no sub-structure. */ }
        }
    }

    private static void collectExpressions(ExpressionNode node,
                                           List<ExpressionNode> outExpressions) {
        if (node == null) {
            return;
        }
        outExpressions.add(node);
        switch (node) {
            case BinaryExpr b -> {
                collectExpressions(b.left(), outExpressions);
                collectExpressions(b.right(), outExpressions);
            }
            case deal.ast.UnaryExpr u -> collectExpressions(u.expr(), outExpressions);
            case deal.ast.CallExpr c -> {
                collectExpressions(c.callee(), outExpressions);
                for (ExpressionNode arg : c.args()) {
                    collectExpressions(arg, outExpressions);
                }
            }
            case MemberAccessExpr m -> collectExpressions(m.object(), outExpressions);
            case deal.ast.IndexExpr i -> {
                collectExpressions(i.array(), outExpressions);
                collectExpressions(i.index(), outExpressions);
            }
            case ArrayLiteralExpr al -> {
                for (ExpressionNode e : al.elements()) {
                    collectExpressions(e, outExpressions);
                }
            }
            case ObjectLiteralExpr ol -> {
                for (deal.ast.Property p : ol.properties()) {
                    collectExpressions(p.value(), outExpressions);
                }
            }
            case deal.ast.FunctionExpr fe -> {
                List<StatementNode> body = new ArrayList<>();
                collectStatements(fe.body(), body, outExpressions);
            }
            case deal.ast.HasExpr h -> collectExpressions(h.object(), outExpressions);
            case deal.ast.AssignmentExpr as -> {
                collectExpressions(as.target(), outExpressions);
                collectExpressions(as.value(), outExpressions);
            }
            case TemplateLiteralExpr t -> {
                for (ExpressionNode part : t.parts()) {
                    collectExpressions(part, outExpressions);
                }
            }
            case deal.ast.AwaitExpression aw -> collectExpressions(aw.callee(), outExpressions);
            default -> { /* Literal/Identifier leaves. */ }
        }
    }

    private static <T> T first(ProgramNode program, Class<T> kind) {
        List<StatementNode> statements = new ArrayList<>();
        List<ExpressionNode> expressions = new ArrayList<>();
        for (StatementNode statement : program.statements()) {
            collectStatements(statement, statements, expressions);
        }
        for (StatementNode statement : statements) {
            if (kind.isInstance(statement)) {
                return kind.cast(statement);
            }
        }
        for (ExpressionNode expression : expressions) {
            if (kind.isInstance(expression)) {
                return kind.cast(expression);
            }
        }
        return null;
    }

    // =========================================================================
    // Op-shape assertion helpers
    // =========================================================================

    private static long countKinds(List<SemanticOp> ops, SemanticOpKind kind) {
        long count = 0;
        for (SemanticOp op : ops) {
            if (op.kind() == kind) {
                count++;
            }
        }
        return count;
    }

    private static List<SemanticOp> ofKind(List<SemanticOp> ops, SemanticOpKind kind) {
        List<SemanticOp> found = new ArrayList<>();
        for (SemanticOp op : ops) {
            if (op.kind() == kind) {
                found.add(op);
            }
        }
        return found;
    }

    private static List<SemanticOp> boundaries(List<SemanticOp> ops, BoundaryKind kind) {
        List<SemanticOp> found = new ArrayList<>();
        for (SemanticOp op : ops) {
            if (op.kind() == SemanticOpKind.BOUNDARY
                    && ((KindPayload.BoundaryPayload) op.payload()).kind() == kind) {
                found.add(op);
            }
        }
        return found;
    }

    private static void checkOrigin(String what, SemanticOp op, Span expected,
                                    SourceOriginKind kind, OpId parent) {
        check(SOURCE_ID.equals(op.origin().sourceId()),
            what + " origin sourceId is the module's stable source identity");
        checkSpan(what, op.origin(), expected);
        check(op.origin().kind() == kind,
            what + " origin kind " + op.origin().kind() + " == " + kind);
        check(java.util.Objects.equals(op.origin().parentOpId(), parent),
            what + " origin parentOpId " + op.origin().parentOpId() + " == " + parent);
    }

    private static void checkSpan(String what, SourceOrigin origin, Span expected) {
        check(expected.file().equals(origin.span().file()),
            what + " span file " + origin.span().file() + " == " + expected.file());
        check(origin.span().startLine() == expected.startLine(),
            what + " span start line " + origin.span().startLine() + " == "
                + expected.startLine());
        check(origin.span().startColumn() == expected.startColumn(),
            what + " span start column " + origin.span().startColumn() + " == "
                + expected.startColumn());
        check(origin.span().endLine() == expected.endLine(),
            what + " span end line " + origin.span().endLine() + " == " + expected.endLine());
        check(origin.span().endColumn() == expected.endColumn(),
            what + " span end column " + origin.span().endColumn() + " == "
                + expected.endColumn());
    }

    private static void checkEmptyOperands(String what, SemanticOp op) {
        check(op.operands().isEmpty() && op.operandTypes().isEmpty(),
            what + " carries empty operand/operandTypes lists (payload-referenced prior steps)");
    }

    private static void checkCanonicalRealization(String what, SemanticOp boundary) {
        KindPayload.BoundaryPayload payload = (KindPayload.BoundaryPayload) boundary.payload();
        check(payload.realization().equals(new BoundaryRealization.RuntimeValidation(
                SemanticLowerer.CANONICAL_RUNTIME_VALIDATION_ID)),
            what + " realization is the pinned RuntimeValidation("
                + SemanticLowerer.CANONICAL_RUNTIME_VALIDATION_ID + ") — inside the contract "
                + "digest, so units and dumps are byte-identical");
    }

    // =========================================================================
    // Synthetic typed fixtures (mirroring the validator-test surface)
    // =========================================================================

    private static final RuntimeDescriptor INT = RuntimeDescriptor.Int.INSTANCE;
    private static final RuntimeDescriptor BOOL = RuntimeDescriptor.Boolean.INSTANCE;
    private static final RuntimeDescriptor STR = RuntimeDescriptor.String.INSTANCE;
    private static final RuntimeDescriptor TBL = RuntimeDescriptor.Table.INSTANCE;

    private static int nextOp = 1;
    private static int nextVal = 1;

    private static OpId nextOpId() {
        return new OpId(MODULE, nextOp++);
    }

    private static ValueId nextValue() {
        return new ValueId(nextVal++);
    }

    private static SourceOrigin origin(OpId parent) {
        return new SourceOrigin("test.deal", SourceSpan.synthetic("test.deal"),
            SourceOriginKind.SYNTHETIC, new deal.semantic.ir.AnchorId(0), parent);
    }

    private static OperationContractSnapshot contractFor(SemanticOpKind kind, KindPayload payload,
            OpResultType resultType, List<RuntimeDescriptor> operandTypes,
            FailurePolicyId policy, String digest) {
        ClosedSelector selector = payload instanceof KindPayload.SelectorCarrying carrying
            ? carrying.selector() : null;
        return new OperationContractSnapshot(OperationContractSnapshot.VERSION, kind, resultType,
            operandTypes, selector, payload, policy, List.of(), digest);
    }

    private static SemanticOp opWith(OpId id, SemanticOpKind kind, KindPayload payload,
            SemanticValue result, OpResultType resultType, FailurePolicyId policy, OpId parent) {
        OperationContractSnapshot contract =
            contractFor(kind, payload, resultType, List.of(), policy, "placeholder");
        String digest = ContractSnapshotCanonicalizer.digest(contract);
        contract = contractFor(kind, payload, resultType, List.of(), policy, digest);
        return new SemanticOp(id, kind, origin(parent), result, resultType, List.of(), List.of(),
            payload, policy, contract);
    }

    private static SemanticOp op(SemanticOpKind kind, KindPayload payload, SemanticValue result,
            OpResultType resultType, FailurePolicyId policy, OpId parent) {
        return opWith(nextOpId(), kind, payload, result, resultType, policy, parent);
    }

    private static SemanticOp boundaryWith(OpId id, BoundaryKind kind, RuntimeDescriptor descriptor,
            FailurePolicyId policy, OpId parent) {
        return opWith(id, SemanticOpKind.BOUNDARY,
            new KindPayload.BoundaryPayload(kind, descriptor, nextValue(),
                new BoundaryRealization.RuntimeValidation(
                    SemanticLowerer.CANONICAL_RUNTIME_VALIDATION_ID)),
            null, null, policy, parent);
    }

    private static LoweredModuleUnit unit(Set<SemanticCapability> caps,
            Map<ConstructKind, List<SemanticOpKind>> coverage, List<SemanticOp> ops) {
        return new LoweredModuleUnit(LoweredModuleUnit.FORMAT_VERSION,
            SemanticProfile.DEAL_V1_2_INT32, MODULE, INTERFACE_HASH,
            LoweringContextHash.of(SemanticProfile.DEAL_V1_2_INT32, REGISTRY_HASH), caps,
            coverage, Map.of(), Map.of(), new ModuleInitPlan(List.of(), new BlockId(0)),
            ExportPlan.empty(), Map.of(), ops);
    }

    private static void assertPass(Optional<CompilerDiagnostic> diagnostic, String what) {
        check(diagnostic.isEmpty(), what + " passes validation"
            + (diagnostic.isPresent() ? ": " + diagnostic.get().message() : ""));
    }

    private static long countOutcomes(List<ContainerClaimingSeam.RecordedOutcome> outcomes,
                                      ContainerClaimingSeam.OutcomeKind kind) {
        long count = 0;
        for (ContainerClaimingSeam.RecordedOutcome outcome : outcomes) {
            if (outcome.outcome() == kind) {
                count++;
            }
        }
        return count;
    }

    /** Asserts the pinned E3-window refusal of an E5-owned construct. */
    private static void assertConstructUnlowered(SemanticLowerer.LoweringResult result,
                                                 String constructDescription) {
        check(result != null && result.hasErrors() && result.unit() == null,
            "the pipeline refuses the E5-owned construct with no unit"
                + (result == null ? "" : ": " + result.diagnostics()));
        if (result == null || !result.hasErrors()) {
            return;
        }
        CompilerDiagnostic diagnostic = result.diagnostics().get(0);
        check("E6005".equals(diagnostic.code())
                && diagnostic.diagnosticCode() == DiagnosticCode.E6005
                && "error".equals(diagnostic.severity()),
            "the refusal is an error-severity E6005 through the failure contract registry");
        check(diagnostic.message().startsWith("Common semantic lowering failed")
                && diagnostic.message().contains(SemanticLowerer.CONSTRUCT_UNLOWERED),
            "the refusal names CONSTRUCT_UNLOWERED (a hard compile failure in this stage's "
                + "window, never a reroute)");
        check(diagnostic.message().contains(constructDescription),
            "the refusal names the exact construct \"" + constructDescription + "\"; got \""
                + diagnostic.message() + "\"");
    }

    // =========================================================================
    // Layer 1: the positionable whole-pipeline corpus at E3's gate
    // =========================================================================

    /**
     * The pinned layer-1 corpus (D7): for-of statements over string-typed
     * iterables embedding string literals, string {@code +}, templates,
     * and identifier loads of the enclosing for-of loop binding — as the
     * nested for-of's iterable operand and as a string-concat/template
     * operand (both positions pinned positionable).
     */
    private static final String LAYER_1_CORPUS = """
        for (let x: string of "ab" + `c${"d"}e` + "fg") {
          for (let y: string of x + `i${x}j`) {}
        }
        """;

    static void testLayer1WholePipelineCorpus() {
        System.out.println("-- Layer 1: whole-pipeline positionable corpus at E3's gate --");

        CheckedSlice slice = checkSlice(LAYER_1_CORPUS);
        if (slice == null) {
            return;
        }
        PipelineArtifacts artifacts = buildPipeline(slice);
        if (artifacts == null) {
            return;
        }

        // The pinned corpus-positionability facts: the foundation detector
        // records exactly the four rows this epic's own arms satisfy.
        Map<ConstructKind, List<SemanticOpKind>> coverage = artifacts.manifest()
            .constructCoverage();
        check(coverage.keySet().equals(Set.of(
                ConstructKind.SCALAR_LITERAL, ConstructKind.IDENTIFIER,
                ConstructKind.STRING_CONCAT_TEMPLATE, ConstructKind.IF_WHILE_FOR_FOR_OF)),
            "the detector records exactly the pinned rows for the corpus: "
                + coverage.keySet());
        check(artifacts.manifest().capabilities().contains(SemanticCapability.FOUNDATION_VALUES),
            "the manifest's plan-time FOUNDATION_VALUES claim (routing) is untouched");

        // Whole-pipeline lowering → validated unit.
        SemanticLowerer.LoweringResult result = lowerPipeline(artifacts);
        check(result != null && !result.hasErrors() && result.unit() != null,
            "the corpus lowers through the checked project and the E3 arms into a validated "
                + "unit with no E6005: " + (result == null ? "null" : result.diagnostics()));
        if (result == null || result.hasErrors() || result.unit() == null) {
            return;
        }
        LoweredModuleUnit unit = result.unit();

        // Every recorded row is produced (R-COVERAGE green on the real
        // produced op set — a real-output assertion, not existence-only).
        List<SemanticOp> ops = unit.ops();
        check(countKinds(ops, SemanticOpKind.CONST) == 7
                && countKinds(ops, SemanticOpKind.STRING_CONCAT) == 5
                && countKinds(ops, SemanticOpKind.BINDING_LOAD) == 2
                && countKinds(ops, SemanticOpKind.FOR_EACH) == 2
                && ops.size() == 16,
            "the corpus produces exactly CONST ×7, STRING_CONCAT ×5, BINDING_LOAD ×2, "
                + "FOR_EACH ×2 (16 ops — every recorded row's mapped kind); got "
                + ops.size() + ": " + ops.stream().map(op -> op.kind().name()).toList());
        check(countKinds(ops, SemanticOpKind.BINARY) == 0,
            "string + never emits BINARY (the detector's STRING_CONCAT_TEMPLATE row maps to "
                + "STRING_CONCAT)");
        check(ops.stream().noneMatch(op -> op.kind() == SemanticOpKind.UNARY),
            "no UNARY op appears (no numeric construct is in the corpus)");

        // The nested-for-of loop-binding load-resolution rule end-to-end:
        // every load of the outer loop binding inside its body carries the
        // enclosing FOR_EACH payload's binding and initial generation.
        SemanticOp outerForEach = null;
        for (SemanticOp op : ops) {
            if (op.kind() == SemanticOpKind.FOR_EACH && outerForEach == null) {
                outerForEach = op;
            }
        }
        check(outerForEach != null, "the corpus carries the outer FOR_EACH");
        if (outerForEach != null) {
            KindPayload.ForEachPayload outerPayload =
                (KindPayload.ForEachPayload) outerForEach.payload();
            check(outerPayload.mode() == IterationMode.STRING_SCALARS,
                "the outer FOR_EACH mode is STRING_SCALARS");
            check(outerPayload.generation() == SemanticLowerer.INITIAL_LOOP_GENERATION,
                "the outer FOR_EACH payload generation is the pinned initial generation");
            check(outerForEach.failurePolicy() == FailurePolicyId.TYPE_DESCRIPTOR,
                "the outer FOR_EACH policy is TYPE_DESCRIPTOR");
            int loadCount = 0;
            for (SemanticOp op : ops) {
                if (op.kind() == SemanticOpKind.BINDING_LOAD) {
                    KindPayload.BindingLoadPayload load =
                        (KindPayload.BindingLoadPayload) op.payload();
                    check(load.binding().equals(outerPayload.binding())
                            && load.generation() == outerPayload.generation()
                            && load.generation() == SemanticLowerer.INITIAL_LOOP_GENERATION,
                        "loop-binding load " + loadCount + " carries the enclosing FOR_EACH "
                            + "payload's binding and initial generation (the payload is never "
                            + "rewritten per iteration — the body-runner substitutes "
                            + "initial + iteration index)");
                    check(op.resultType().equals(RuntimeDescriptor.String.INSTANCE)
                            && op.failurePolicy() == FailurePolicyId.NO_DEAL_FAILURE,
                        "loop-binding load " + loadCount
                            + " carries D(checked type) = string and NO_DEAL_FAILURE");
                    loadCount++;
                }
            }
            check(loadCount == 2,
                "exactly the two loads exist — the inner iterable operand and the template "
                    + "interpolation operand; got " + loadCount);
        }

        // The ∅-claim staged unit: derived claims empty, one recorded
        // staged hand-off per op (the claiming seam's real outcomes).
        check(unit.requiredCapabilities().isEmpty(),
            "the corpus unit's derived claim set is ∅ (every E3 op's home row is inactive "
                + "during the tail)");
        check(!artifacts.manifest().capabilities().equals(unit.requiredCapabilities()),
            "the manifest's plan-time FOUNDATION_VALUES claim (routing) and the unit's ∅ "
                + "claim set diverge exactly as the foundation pins");
        ContainerClaimingSeam.SeamResult seam = ContainerClaimingSeam.check(ops,
            ContainerClaimingSeam.E3_WINDOW_ACTIVATION, unit.requiredCapabilities(), MODULE);
        check(seam.failure() == null, "the seam fires no E6005 for the corpus unit");
        check(seam.derivedClaims().isEmpty(), "the seam derives the empty claim set");
        check(seam.outcomes().size() == 16
                && countOutcomes(seam.outcomes(),
                    ContainerClaimingSeam.OutcomeKind.STAGED_HAND_OFF) == 16,
            "the seam records exactly 16 staged hand-offs — one per op at its home row "
                + "(7 CONST + 5 STRING_CONCAT → FOUNDATION_VALUES, 2 BINDING_LOAD → BINDINGS, "
                + "2 FOR_EACH → CONTAINERS_AND_STRINGS); got " + seam.outcomes().size());

        // Deterministic dump: a second full pipeline repetition (fresh
        // checked project, manifest, allocator, and lowering) produces a
        // byte-identical unit dump, text, and contract digests (D8).
        PipelineArtifacts repeatedArtifacts = buildPipeline(slice);
        check(repeatedArtifacts != null, "the repeated pipeline run builds again");
        if (repeatedArtifacts == null) {
            return;
        }
        SemanticLowerer.LoweringResult repeated = lowerPipeline(repeatedArtifacts);
        check(repeated != null && !repeated.hasErrors() && repeated.unit() != null,
            "the repeated pipeline run validates again: "
                + (repeated == null ? "null" : repeated.diagnostics()));
        if (repeated == null || repeated.hasErrors() || repeated.unit() == null) {
            return;
        }
        byte[] firstDump = SemanticIrDumper.dumpModule(unit);
        byte[] secondDump = SemanticIrDumper.dumpModule(repeated.unit());
        check(Arrays.equals(firstDump, secondDump) && firstDump.length > 0,
            "two full pipeline repetitions produce byte-identical unit dumps (D8); "
                + firstDump.length + " bytes");
        check(SemanticIrDumper.dumpModuleText(unit).equals(
                SemanticIrDumper.dumpModuleText(repeated.unit())),
            "two full pipeline repetitions produce byte-identical dump text");
        List<String> firstDigests = new ArrayList<>();
        List<String> secondDigests = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            firstDigests.add(op.contract().canonicalDigest());
        }
        for (SemanticOp op : repeated.unit().ops()) {
            secondDigests.add(op.contract().canonicalDigest());
        }
        check(firstDigests.equals(secondDigests) && !firstDigests.isEmpty(),
            "canonical contract digests are byte-identical across the two repetitions");
        check(repeated.unit().requiredCapabilities().equals(unit.requiredCapabilities()),
            "the repeated unit's ∅ claim set is identical");

        // The real dump carries the recorded rows' producers — the dump is
        // the layer's real output, asserted on its content, never on file
        // existence.
        String dumpText = SemanticIrDumper.dumpModuleText(unit);
        for (String kindName : List.of("CONST", "BINDING_LOAD", "STRING_CONCAT", "FOR_EACH")) {
            check(dumpText.contains("\"kind\":\"" + kindName + "\""),
                "the dump text carries a produced " + kindName + " op");
        }
        check(dumpText.contains("SCALAR_LITERAL") && dumpText.contains("IDENTIFIER")
                && dumpText.contains("STRING_CONCAT_TEMPLATE")
                && dumpText.contains("IF_WHILE_FOR_FOR_OF"),
            "the dump text carries the four recorded coverage rows");
    }

    // =========================================================================
    // Layer 1 fault injection: named constituent failures
    // =========================================================================

    static void testLayer1FaultInjection() {
        System.out.println("-- Layer 1 fault injection: named constituent failures --");

        CheckedSlice slice = checkSlice(LAYER_1_CORPUS);
        if (slice == null) {
            return;
        }
        PipelineArtifacts artifacts = buildPipeline(slice);
        if (artifacts == null) {
            return;
        }
        SemanticLowerer.LoweringResult result = lowerPipeline(artifacts);
        if (result == null || result.hasErrors() || result.unit() == null) {
            return;
        }
        LoweredModuleUnit unit = result.unit();

        // --- Fault L1-A: a removed produced row fails R-COVERAGE. ---
        // The BINDING_LOAD producer is removed from the otherwise-valid
        // unit while the recorded IDENTIFIER row stays — the validator's
        // R-COVERAGE (the first closed rule) rejects the unit naming the
        // construct row, never a silent skip.
        List<SemanticOp> withoutLoads = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            if (op.kind() != SemanticOpKind.BINDING_LOAD) {
                withoutLoads.add(op);
            }
        }
        check(withoutLoads.size() == unit.ops().size() - 2,
            "the injected variant removes exactly the two BINDING_LOAD producers");
        LoweredModuleUnit loadRemoved = new LoweredModuleUnit(unit.formatVersion(),
            unit.semanticProfile(), unit.moduleId(), unit.interfaceHash(),
            unit.loweringContextHash(),
            unit.requiredCapabilities(), unit.constructCoverage(), unit.classLayouts(),
            unit.functions(), unit.moduleInit(), unit.exportPlan(), unit.functionBindings(),
            withoutLoads);
        Optional<CompilerDiagnostic> validation = SemanticIrValidator.validate(loadRemoved,
            facts());
        check(validation.isPresent(), "the load-removed unit is rejected");
        if (validation.isPresent()) {
            CompilerDiagnostic diagnostic = validation.get();
            check("E6005".equals(diagnostic.code())
                    && diagnostic.message().contains(SemanticIrValidator.R_COVERAGE)
                    && diagnostic.message().contains("IDENTIFIER"),
                "the named failure is R-COVERAGE on the IDENTIFIER row; got \""
                    + diagnostic.message() + "\"");
        }

        // --- Fault L1-B: a corrupted claim state fails the seam. ---
        // The intact corpus unit claims ∅ (correct); corrupting the unit's
        // claim set with an under-evidenced row is impossible by the
        // derivation and the seam fails closed.
        try {
            ContainerClaimingSeam.check(unit.ops(),
                ContainerClaimingSeam.E3_WINDOW_ACTIVATION,
                Set.of(SemanticCapability.FOUNDATION_VALUES), MODULE);
            fail("a corrupted claim (FOUNDATION_VALUES without UNARY/BINARY evidence) must "
                + "fail the seam closed");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("impossible by the derivation"),
                "the corrupted claim state fails the seam with the named "
                    + "derivation-invariant defect: " + expected.getMessage());
        }
    }

    // =========================================================================
    // Layer 2: per-construct checked-source slices for every D1 arm
    // =========================================================================

    static void testLayer2ScalarLiteralSlice() {
        System.out.println("-- Layer 2 slice: scalar literals → CONST --");

        CheckedSlice slice = checkSlice("""
            function f(): null {
              let n = null
              let b = true
              let s = "abc"
              return
            }
            """);
        if (slice == null) {
            return;
        }
        SemanticLowerer.ModuleLowerer lowerer = lowerer(slice.checks());
        List<ExpressionNode> expressions = new ArrayList<>();
        for (StatementNode statement : slice.program().statements()) {
            collectStatements(statement, new ArrayList<>(), expressions);
        }
        List<LiteralExpr> literals = new ArrayList<>();
        for (ExpressionNode expression : expressions) {
            if (expression instanceof LiteralExpr literal) {
                literals.add(literal);
            }
        }
        RuntimeDescriptor[] expectedTypes = {RuntimeDescriptor.Null.INSTANCE,
            RuntimeDescriptor.Boolean.INSTANCE, RuntimeDescriptor.String.INSTANCE};
        ScalarValue[] expectedScalars = {ScalarValue.Null.INSTANCE,
            new ScalarValue.Boolean(true), new ScalarValue.String("abc")};
        check(literals.size() == 3, "the slice carries the three E3-positionable scalar "
            + "literals (int/number CONST evidence is E2's); got " + literals.size());
        if (literals.size() != 3) {
            return;
        }
        for (int i = 0; i < literals.size(); i++) {
            LiteralExpr literal = literals.get(i);
            ValueId value = lowerer.lowerExpression(literal);
            SemanticOp op = lowerer.ops().get(lowerer.ops().size() - 1);
            check(op.kind() == SemanticOpKind.CONST,
                "literal " + i + " lowers to one CONST; got " + op.kind());
            KindPayload.ConstPayload payload = (KindPayload.ConstPayload) op.payload();
            check(payload.value().equals(expectedScalars[i]),
                "literal " + i + " CONST payload value is the literal scalar");
            check(op.result().equals(value)
                    && op.resultType().equals(expectedTypes[i]),
                "literal " + i + " CONST publishes its result with D(checked type)");
            check(op.failurePolicy() == FailurePolicyId.NO_DEAL_FAILURE,
                "literal " + i + " CONST policy NO_DEAL_FAILURE");
            checkOrigin("literal " + i, op, literal.span(), SourceOriginKind.USER, null);
            checkEmptyOperands("literal " + i, op);
        }
    }

    static void testLayer2IdentifierSlice() {
        System.out.println("-- Layer 2 slice: identifier → BINDING_LOAD (loop-binding load) --");

        CheckedSlice slice = checkSlice("""
            for (let x: string of "ab") {
              for (let y: string of x) {}
            }
            """);
        if (slice == null) {
            return;
        }
        ForOfStatement outer = first(slice.program(), ForOfStatement.class);
        IdentifierExpr identifier = first(slice.program(), IdentifierExpr.class);
        check(outer != null && identifier != null && "x".equals(identifier.name()),
            "the slice carries the outer for-of and the inner iterable identifier 'x'");
        if (outer == null || identifier == null) {
            return;
        }
        SemanticLowerer.ModuleLowerer lowerer = lowerer(slice.checks());
        OpId outerOpId = lowerer.lowerForOfStatement(outer);
        List<SemanticOp> ops = lowerer.ops();
        check(ops.size() == 4 && ops.get(0).kind() == SemanticOpKind.CONST
                && ops.get(1).kind() == SemanticOpKind.FOR_EACH
                && ops.get(2).kind() == SemanticOpKind.BINDING_LOAD
                && ops.get(3).kind() == SemanticOpKind.FOR_EACH,
            "the slice produces exactly CONST, outer FOR_EACH, BINDING_LOAD, inner FOR_EACH "
                + "in source order; got " + ops.size());
        if (ops.size() != 4) {
            return;
        }
        SemanticOp outerOp = ops.get(1);
        check(outerOp.opId().equals(outerOpId), "the arm returns the FOR_EACH op id");
        KindPayload.ForEachPayload outerPayload = (KindPayload.ForEachPayload) outerOp.payload();
        SemanticOp load = ops.get(2);
        KindPayload.BindingLoadPayload loadPayload =
            (KindPayload.BindingLoadPayload) load.payload();
        check(loadPayload.binding().equals(outerPayload.binding())
                && loadPayload.generation() == outerPayload.generation()
                && loadPayload.generation() == SemanticLowerer.INITIAL_LOOP_GENERATION,
            "the load carries the enclosing FOR_EACH payload's binding and initial generation "
                + "(the loop-binding load-resolution rule)");
        check(load.resultType().equals(RuntimeDescriptor.String.INSTANCE)
                && load.failurePolicy() == FailurePolicyId.NO_DEAL_FAILURE,
            "the load carries D(checked type) = string and NO_DEAL_FAILURE");
        checkOrigin("loop-binding load", load, identifier.span(), SourceOriginKind.USER, null);
        checkEmptyOperands("loop-binding load", load);
        KindPayload.ForEachPayload innerPayload =
            (KindPayload.ForEachPayload) ops.get(3).payload();
        check(innerPayload.mode() == IterationMode.STRING_SCALARS
                && innerPayload.iterable().equals(load.result())
                && !innerPayload.binding().equals(outerPayload.binding()),
            "the inner FOR_EACH consumes the load's value and allocates a distinct binding");
    }

    static void testLayer2ArrayLiteralSlice() {
        System.out.println("-- Layer 2 slice: array literal → ARRAY_NEW + element boundaries --");

        CheckedSlice slice = checkSlice("""
            function f(): null {
              let xs: string[] = ["a", "b"]
              return
            }
            """);
        if (slice == null) {
            return;
        }
        ArrayLiteralExpr literal = first(slice.program(), ArrayLiteralExpr.class);
        check(literal != null && literal.elements().size() == 2,
            "the slice carries the two-element array literal");
        if (literal == null) {
            return;
        }
        SemanticLowerer.ModuleLowerer lowerer = lowerer(slice.checks());
        ValueId arrayValue = lowerer.lowerExpression(literal);
        List<SemanticOp> ops = lowerer.ops();
        check(ops.size() == 5,
            "the slice produces 2 element CONSTs + ARRAY_NEW + 2 boundary children = 5 ops; "
                + "got " + ops.size());
        if (ops.size() != 5) {
            return;
        }
        check(ops.get(0).kind() == SemanticOpKind.CONST
                && ops.get(1).kind() == SemanticOpKind.CONST,
            "the element prior steps precede ARRAY_NEW in source order");
        SemanticOp arrayNew = ops.get(2);
        check(arrayNew.kind() == SemanticOpKind.ARRAY_NEW, "op 2 is ARRAY_NEW");
        KindPayload.ArrayNewPayload payload = (KindPayload.ArrayNewPayload) arrayNew.payload();
        check(payload.elementDescriptor().equals(RuntimeDescriptor.String.INSTANCE),
            "ARRAY_NEW elementDescriptor is D(T) = string");
        check(payload.values().equals(List.of(ops.get(0).result(), ops.get(1).result())),
            "ARRAY_NEW values are the element ValueIds in source order");
        check(payload.elementBoundaryOpIds().size() == 2
                && payload.elementBoundaryOpIds().get(0).equals(ops.get(3).opId())
                && payload.elementBoundaryOpIds().get(1).equals(ops.get(4).opId()),
            "ARRAY_NEW elementBoundaryOpIds name the children in the same source order");
        check(arrayNew.result().equals(arrayValue)
                && arrayNew.resultType().equals(new RuntimeDescriptor.Array(
                    RuntimeDescriptor.String.INSTANCE)),
            "ARRAY_NEW publishes its result with resultType [D(T)] = [string]");
        check(arrayNew.failurePolicy() == FailurePolicyId.NO_DEAL_FAILURE,
            "ARRAY_NEW policy NO_DEAL_FAILURE");
        checkOrigin("ARRAY_NEW", arrayNew, literal.span(), SourceOriginKind.USER, null);
        checkEmptyOperands("ARRAY_NEW", arrayNew);
        for (int i = 0; i < 2; i++) {
            SemanticOp child = ops.get(3 + i);
            check(child.kind() == SemanticOpKind.BOUNDARY, "op " + (3 + i) + " is a BOUNDARY");
            KindPayload.BoundaryPayload boundary =
                (KindPayload.BoundaryPayload) child.payload();
            check(boundary.kind() == BoundaryKind.ARRAY_LITERAL_ELEMENT,
                "child " + i + " kind ARRAY_LITERAL_ELEMENT");
            check(boundary.descriptor().equals(RuntimeDescriptor.String.INSTANCE),
                "child " + i + " descriptor D(T) = string");
            check(boundary.input().equals(payload.values().get(i)),
                "child " + i + " input is the matching element ValueId");
            check(child.failurePolicy() == FailurePolicyId.ARRAY_ELEMENT_DESCRIPTOR,
                "child " + i + " policy ARRAY_ELEMENT_DESCRIPTOR");
            checkCanonicalRealization("child " + i, child);
            checkOrigin("child " + i, child, literal.elements().get(i).span(),
                SourceOriginKind.SYNTHETIC, arrayNew.opId());
        }
    }

    static void testLayer2TableLiteralSlice() {
        System.out.println("-- Layer 2 slice: table literal → TABLE_NEW (source order, "
            + "duplicate keys) --");

        CheckedSlice slice = checkSlice("""
            function f(): null {
              let t = {a: "x", b: "y", a: "z"}
              return
            }
            """);
        if (slice == null) {
            return;
        }
        ObjectLiteralExpr literal = first(slice.program(), ObjectLiteralExpr.class);
        check(literal != null && literal.properties().size() == 3,
            "the slice carries the duplicate-key table literal");
        if (literal == null) {
            return;
        }
        SemanticLowerer.ModuleLowerer lowerer = lowerer(slice.checks());
        ValueId tableValue = lowerer.lowerExpression(literal);
        List<SemanticOp> ops = lowerer.ops();
        check(ops.size() == 4,
            "the slice produces 3 value CONSTs + TABLE_NEW = 4 ops; got " + ops.size());
        if (ops.size() != 4) {
            return;
        }
        SemanticOp tableNew = ops.get(3);
        check(tableNew.kind() == SemanticOpKind.TABLE_NEW, "op 3 is TABLE_NEW");
        KindPayload.TableNewPayload payload = (KindPayload.TableNewPayload) tableNew.payload();
        check(payload.entries().size() == 3
                && payload.entries().get(0).key().equals("a")
                && payload.entries().get(0).value().equals(ops.get(0).result())
                && payload.entries().get(1).key().equals("b")
                && payload.entries().get(1).value().equals(ops.get(1).result())
                && payload.entries().get(2).key().equals("a")
                && payload.entries().get(2).value().equals(ops.get(2).result()),
            "TABLE_NEW entries are the identifier keys with their value ValueIds in source "
                + "order — duplicate keys stay in source order in the payload (the "
                + "executor's put pins the later value and the first position)");
        check(tableNew.result().equals(tableValue)
                && tableNew.resultType().equals(RuntimeDescriptor.Table.INSTANCE),
            "TABLE_NEW publishes its result with resultType table");
        check(tableNew.failurePolicy() == FailurePolicyId.NO_DEAL_FAILURE,
            "TABLE_NEW policy NO_DEAL_FAILURE");
        checkOrigin("TABLE_NEW", tableNew, literal.span(), SourceOriginKind.USER, null);
        checkEmptyOperands("TABLE_NEW", tableNew);
    }

    static void testLayer2StringPlusSlice() {
        System.out.println("-- Layer 2 slice: string + → STRING_CONCAT (never BINARY) --");

        CheckedSlice slice = checkSlice("""
            function f(): null {
              let s = "a" + "b"
              return
            }
            """);
        if (slice == null) {
            return;
        }
        BinaryExpr binary = first(slice.program(), BinaryExpr.class);
        check(binary != null && binary.op() == BinaryOp.ADD,
            "the slice carries the string + expression");
        if (binary == null) {
            return;
        }
        SemanticLowerer.ModuleLowerer lowerer = lowerer(slice.checks());
        ValueId concatValue = lowerer.lowerExpression(binary);
        List<SemanticOp> ops = lowerer.ops();
        check(ops.size() == 3,
            "the slice produces 2 operand CONSTs + STRING_CONCAT = 3 ops; got " + ops.size());
        if (ops.size() != 3) {
            return;
        }
        SemanticOp concat = ops.get(2);
        check(concat.kind() == SemanticOpKind.STRING_CONCAT, "op 2 is STRING_CONCAT");
        check(ops.stream().noneMatch(op -> op.kind() == SemanticOpKind.BINARY),
            "string + never emits BINARY");
        KindPayload.StringConcatPayload payload =
            (KindPayload.StringConcatPayload) concat.payload();
        check(payload.fragments().equals(List.of(ops.get(0).result(), ops.get(1).result())),
            "STRING_CONCAT fragments are the operand ValueIds in source order");
        check(concat.result().equals(concatValue)
                && concat.resultType().equals(RuntimeDescriptor.String.INSTANCE),
            "STRING_CONCAT publishes its result with resultType string");
        check(concat.failurePolicy() == FailurePolicyId.NO_DEAL_FAILURE,
            "STRING_CONCAT policy NO_DEAL_FAILURE");
        checkOrigin("STRING_CONCAT", concat, binary.span(), SourceOriginKind.USER, null);
        checkEmptyOperands("STRING_CONCAT", concat);
    }

    static void testLayer2TemplateSlice() {
        System.out.println("-- Layer 2 slice: template → CONST steps + STRING_CONCAT --");

        CheckedSlice slice = checkSlice("""
            function f(): null {
              let s = `p${"q"}r`
              let solo = `single`
              return
            }
            """);
        if (slice == null) {
            return;
        }
        List<TemplateLiteralExpr> templates = new ArrayList<>();
        List<StatementNode> statements = new ArrayList<>();
        List<ExpressionNode> expressions = new ArrayList<>();
        for (StatementNode statement : slice.program().statements()) {
            collectStatements(statement, statements, expressions);
        }
        for (ExpressionNode expression : expressions) {
            if (expression instanceof TemplateLiteralExpr template) {
                templates.add(template);
            }
        }
        check(templates.size() == 2,
            "the slice carries the multi-part template and the single-fragment template; "
                + "got " + templates.size());
        if (templates.size() != 2) {
            return;
        }

        // The multi-part template: each literal part is one CONST step in
        // source order, the interpolation lowers in position, then one
        // STRING_CONCAT with the part ValueIds in source order.
        {
            TemplateLiteralExpr template = templates.get(0);
            SemanticLowerer.ModuleLowerer lowerer = lowerer(slice.checks());
            ValueId concatValue = lowerer.lowerExpression(template);
            List<SemanticOp> ops = lowerer.ops();
            check(ops.size() == 4,
                "the multi-part template produces 3 part steps + STRING_CONCAT = 4 ops; got "
                    + ops.size());
            if (ops.size() == 4) {
                check(ops.get(0).kind() == SemanticOpKind.CONST
                        && ops.get(1).kind() == SemanticOpKind.CONST
                        && ops.get(2).kind() == SemanticOpKind.CONST
                        && ops.get(3).kind() == SemanticOpKind.STRING_CONCAT,
                    "the parts produce CONST steps in source order, then STRING_CONCAT");
                KindPayload.StringConcatPayload payload =
                    (KindPayload.StringConcatPayload) ops.get(3).payload();
                check(payload.fragments().equals(List.of(ops.get(0).result(),
                        ops.get(1).result(), ops.get(2).result())),
                    "STRING_CONCAT fragments are the part ValueIds in source order");
                check(((KindPayload.ConstPayload) ops.get(0).payload()).value()
                        .equals(new ScalarValue.String("p"))
                        && ((KindPayload.ConstPayload) ops.get(1).payload()).value()
                        .equals(new ScalarValue.String("q"))
                        && ((KindPayload.ConstPayload) ops.get(2).payload()).value()
                        .equals(new ScalarValue.String("r")),
                    "the CONST steps carry the literal fragment texts in source order");
                check(ops.get(3).result().equals(concatValue)
                        && ops.get(3).resultType().equals(RuntimeDescriptor.String.INSTANCE),
                    "STRING_CONCAT publishes its result with resultType string");
                check(ops.get(3).failurePolicy() == FailurePolicyId.NO_DEAL_FAILURE,
                    "STRING_CONCAT policy NO_DEAL_FAILURE");
                checkOrigin("template STRING_CONCAT", ops.get(3), template.span(),
                    SourceOriginKind.USER, null);
            }
        }

        // The single-fragment template: still STRING_CONCAT with one
        // fragment — no lowerer-side fold.
        {
            TemplateLiteralExpr template = templates.get(1);
            SemanticLowerer.ModuleLowerer lowerer = lowerer(slice.checks());
            ValueId concatValue = lowerer.lowerExpression(template);
            List<SemanticOp> ops = lowerer.ops();
            check(ops.size() == 2,
                "the single-fragment template produces one CONST step + STRING_CONCAT = 2 "
                    + "ops (no folding — the construct row maps templates to "
                    + "STRING_CONCAT); got " + ops.size());
            if (ops.size() == 2) {
                check(ops.get(0).kind() == SemanticOpKind.CONST
                        && ops.get(1).kind() == SemanticOpKind.STRING_CONCAT,
                    "the single fragment is a CONST step followed by STRING_CONCAT");
                KindPayload.StringConcatPayload payload =
                    (KindPayload.StringConcatPayload) ops.get(1).payload();
                check(payload.fragments().equals(List.of(ops.get(0).result())),
                    "STRING_CONCAT carries exactly the one fragment ValueId");
                check(((KindPayload.ConstPayload) ops.get(0).payload()).value()
                        .equals(new ScalarValue.String("single")),
                    "the CONST step carries the literal fragment text");
                check(ops.get(1).result().equals(concatValue),
                    "STRING_CONCAT publishes its result");
            }
        }
    }

    static void testLayer2ArrayLengthSlice() {
        System.out.println("-- Layer 2 slice: array .length → ARRAY_LENGTH (receiver once) --");

        CheckedSlice slice = checkSlice("""
            function f(a: string[]): null {
              let n: int = a.length
              return
            }
            """);
        if (slice == null) {
            return;
        }
        MemberAccessExpr access = first(slice.program(), MemberAccessExpr.class);
        check(access != null && "length".equals(access.field()),
            "the slice carries the array .length member access");
        if (access == null) {
            return;
        }
        SemanticLowerer.ModuleLowerer lowerer = lowerer(slice.checks());
        SemanticLowerer.ForEachFrame frame = lowerer.openForEachScope("a");
        ValueId lengthValue = lowerer.lowerExpression(access);
        lowerer.closeForEachScope();
        List<SemanticOp> ops = lowerer.ops();
        check(ops.size() == 2,
            "the slice produces exactly the receiver load + ARRAY_LENGTH = 2 ops (the "
                + "receiver is evaluated once, never re-evaluated); got " + ops.size());
        if (ops.size() != 2) {
            return;
        }
        check(ops.get(0).kind() == SemanticOpKind.BINDING_LOAD
                && ((KindPayload.BindingLoadPayload) ops.get(0).payload()).binding()
                .equals(frame.binding()),
            "the receiver is one prior-step load");
        SemanticOp length = ops.get(1);
        check(length.kind() == SemanticOpKind.ARRAY_LENGTH, "op 1 is ARRAY_LENGTH");
        KindPayload.ArrayLengthPayload payload =
            (KindPayload.ArrayLengthPayload) length.payload();
        check(payload.arrayValue().equals(ops.get(0).result()),
            "ARRAY_LENGTH payload arrayValue is the receiver ValueId (exactly once)");
        check(length.result().equals(lengthValue)
                && length.resultType().equals(RuntimeDescriptor.Int.INSTANCE),
            "ARRAY_LENGTH publishes its result with resultType int");
        check(length.failurePolicy() == FailurePolicyId.INT32_RESULT,
            "ARRAY_LENGTH policy INT32_RESULT (the signed32 count with the range policy)");
        checkOrigin("ARRAY_LENGTH", length, access.span(), SourceOriginKind.USER, null);
        checkEmptyOperands("ARRAY_LENGTH", length);
    }

    static void testLayer2MemberReadSlice() {
        System.out.println("-- Layer 2 slice: table member read → MEMBER_READ + contextual "
            + "child --");

        CheckedSlice slice = checkSlice("""
            function f(t: table): null {
              if (t.k) {}
              return
            }
            """);
        if (slice == null) {
            return;
        }
        MemberAccessExpr access = first(slice.program(), MemberAccessExpr.class);
        check(access != null && "k".equals(access.field()),
            "the slice carries the if-condition member read t.k");
        if (access == null) {
            return;
        }
        SemanticLowerer.ModuleLowerer lowerer = lowerer(slice.checks());
        lowerer.openForEachScope("t");
        ValueId readValue = lowerer.lowerExpression(access);
        lowerer.closeForEachScope();
        List<SemanticOp> ops = lowerer.ops();
        check(ops.size() == 3,
            "t.k produces the receiver load + MEMBER_READ + its contextual child = 3 ops; "
                + "got " + ops.size());
        if (ops.size() != 3) {
            return;
        }
        check(ops.get(0).kind() == SemanticOpKind.BINDING_LOAD,
            "the receiver is one prior-step load");
        SemanticOp read = ops.get(1);
        check(read.kind() == SemanticOpKind.MEMBER_READ, "op 1 is MEMBER_READ");
        KindPayload.MemberReadPayload payload = (KindPayload.MemberReadPayload) read.payload();
        check(payload.table().equals(ops.get(0).result()),
            "MEMBER_READ payload table is the receiver ValueId (exactly once)");
        check("k".equals(payload.key()),
            "MEMBER_READ key is the constant identifier string (never evaluated)");
        check(read.result().equals(readValue)
                && read.resultType().equals(RuntimeDescriptor.Boolean.INSTANCE),
            "MEMBER_READ publishes its result with D(contextual type) = boolean (the "
                + "if-condition context)");
        check(read.failurePolicy() == FailurePolicyId.NO_DEAL_FAILURE,
            "MEMBER_READ policy NO_DEAL_FAILURE");
        checkOrigin("MEMBER_READ", read, access.span(), SourceOriginKind.USER, null);
        checkEmptyOperands("MEMBER_READ", read);
        SemanticOp child = ops.get(2);
        check(child.kind() == SemanticOpKind.BOUNDARY,
            "op 2 is the single contextual child");
        KindPayload.BoundaryPayload boundary = (KindPayload.BoundaryPayload) child.payload();
        check(boundary.kind() == BoundaryKind.CONTEXTUAL_TABLE_READ,
            "child kind CONTEXTUAL_TABLE_READ");
        check(boundary.descriptor().equals(RuntimeDescriptor.Boolean.INSTANCE),
            "child descriptor D(contextual type) = boolean");
        check(boundary.input().equals(read.result()),
            "child input is the MEMBER_READ result ValueId");
        check(child.failurePolicy() == FailurePolicyId.TYPE_DESCRIPTOR,
            "child policy is the descriptor-kind rule: TYPE_DESCRIPTOR (boolean is not a "
                + "function descriptor)");
        checkCanonicalRealization("contextual child", child);
        checkOrigin("contextual child", child, access.span(), SourceOriginKind.SYNTHETIC,
            read.opId());
    }

    static void testLayer2ForOfSlice() {
        System.out.println("-- Layer 2 slice: string for-of → FOR_EACH(STRING_SCALARS) --");

        CheckedSlice slice = checkSlice("""
            for (let s: string of "ab" + `c${"d"}`) {}
            """);
        if (slice == null) {
            return;
        }
        ForOfStatement statement = first(slice.program(), ForOfStatement.class);
        check(statement != null, "the slice carries the string for-of");
        if (statement == null) {
            return;
        }
        SemanticLowerer.ModuleLowerer lowerer = lowerer(slice.checks());
        OpId forEachId = lowerer.lowerForOfStatement(statement);
        List<SemanticOp> ops = lowerer.ops();
        check(ops.size() == 7,
            "the slice produces CONST(\"ab\") + the template's three part steps "
                + "(CONST \"c\", CONST \"d\", CONST \"\" — the trailing empty literal "
                + "part) + the template STRING_CONCAT + the binary STRING_CONCAT + FOR_EACH "
                + "= 7 ops (the empty body produces no body ops); got " + ops.size());
        if (ops.size() != 7) {
            return;
        }
        check(((KindPayload.ConstPayload) ops.get(3).payload()).value()
                .equals(new ScalarValue.String("")),
            "the template's trailing empty literal part is its own CONST step (no "
                + "lowerer-side folding)");
        check(((KindPayload.StringConcatPayload) ops.get(4).payload()).fragments()
                .equals(List.of(ops.get(1).result(), ops.get(2).result(),
                    ops.get(3).result())),
            "the template STRING_CONCAT carries the three part ValueIds in source order");
        SemanticOp forEach = ops.get(6);
        check(forEach.kind() == SemanticOpKind.FOR_EACH && forEach.opId().equals(forEachId),
            "the arm returns the FOR_EACH op id");
        KindPayload.ForEachPayload payload = (KindPayload.ForEachPayload) forEach.payload();
        check(payload.mode() == IterationMode.STRING_SCALARS,
            "FOR_EACH mode STRING_SCALARS");
        check(payload.iterable().equals(ops.get(5).result()),
            "FOR_EACH iterable is the iterable expression's producing op (the binary "
                + "STRING_CONCAT prior step, evaluated once)");
        check(statement.varName().equals("s"), "the for-of statement's loop binding is 's'");
        check(payload.binding() != null,
            "FOR_EACH binding is the producer-allocated fresh binding of the loop "
                + "variable's scope (per iteration the body-runner binds the yielded scalar "
                + "to a fresh generation of it)");
        check(payload.generation() == SemanticLowerer.INITIAL_LOOP_GENERATION,
            "FOR_EACH generation is the pinned initial generation");
        check(payload.body() != null, "FOR_EACH body is the empty loop body block");
        check(forEach.result() == null && forEach.resultType() == null,
            "FOR_EACH result/resultType are none");
        check(forEach.failurePolicy() == FailurePolicyId.TYPE_DESCRIPTOR,
            "FOR_EACH policy TYPE_DESCRIPTOR (the op's own invalid-unicode terminal check)");
        checkOrigin("FOR_EACH", forEach, statement.span(), SourceOriginKind.USER, null);
        checkEmptyOperands("FOR_EACH", forEach);
    }

    static void testLayer2Slices() {
        testLayer2ScalarLiteralSlice();
        testLayer2IdentifierSlice();
        testLayer2ArrayLiteralSlice();
        testLayer2TableLiteralSlice();
        testLayer2StringPlusSlice();
        testLayer2TemplateSlice();
        testLayer2ArrayLengthSlice();
        testLayer2MemberReadSlice();
        testLayer2ForOfSlice();
    }

    // =========================================================================
    // Layer 2 fault injection: named constituent failures
    // =========================================================================

    static void testLayer2FaultInjection() {
        System.out.println("-- Layer 2 fault injection: named constituent failures --");

        // --- Fault L2-A: changing the string-+ slice's construct to a
        //     comparison (E2's BINARY selector) fails the slice hard with
        //     CONSTRUCT_UNLOWERED — never a STRING_CONCAT. ---
        CheckedSlice changed = checkSlice("""
            function f(): null {
              let s = "a" === "a"
              return
            }
            """);
        if (changed != null) {
            BinaryExpr binary = first(changed.program(), BinaryExpr.class);
            check(binary != null, "the changed slice carries the comparison");
            if (binary != null) {
                SemanticLowerer.ModuleLowerer lowerer = lowerer(changed.checks());
                try {
                    lowerer.lowerExpression(binary);
                    fail("the changed construct (string ===, a BINARY comparison selector "
                        + "owned by E2) must not produce a STRING_CONCAT");
                } catch (SemanticLowerer.ConstructUnlowered expected) {
                    check(expected.getMessage().contains("binary selector")
                            && expected.getMessage().contains("never BINARY"),
                        "the changed construct fails the slice hard with the named "
                            + "CONSTRUCT_UNLOWERED: " + expected.getMessage());
                }
            }
        }

        // --- Fault L2-B: changing the template slice's construct to a plain
        //     string literal produces no STRING_CONCAT — the slice's exact
        //     shape assertion fails on the changed construct. ---
        CheckedSlice plain = checkSlice("""
            function f(): null {
              let s = "plain"
              return
            }
            """);
        if (plain != null) {
            LiteralExpr literal = first(plain.program(), LiteralExpr.class);
            check(literal != null, "the changed slice carries the plain string literal");
            if (literal != null) {
                SemanticLowerer.ModuleLowerer lowerer = lowerer(plain.checks());
                lowerer.lowerExpression(literal);
                check(lowerer.ops().size() == 1
                        && lowerer.ops().get(0).kind() == SemanticOpKind.CONST
                        && lowerer.ops().stream().noneMatch(op ->
                            op.kind() == SemanticOpKind.STRING_CONCAT),
                    "the changed construct (plain string literal) produces one CONST and no "
                        + "STRING_CONCAT — the template slice's pinned shape check fails for "
                        + "the changed construct");
            }
        }
    }

    // =========================================================================
    // Layer 3: the pinned full six-construct corpus, gated at E5's gate
    // =========================================================================

    /** The pinned fixture path of the fixed corpus (committed now). */
    private static final String LAYER_3_FIXTURE = "test/fixtures/container-fixed-corpus.deal";

    /**
     * The pinned layer-3 corpus (D7): the full fixed source corpus — one
     * module with the pinned construct list. The array literal, table
     * literal, string {@code +}, template, and array {@code .length}
     * expressions each in an expression statement (→ {@code DISCARD});
     * the table member read in an if-condition with an empty then-block
     * (→ {@code BRANCH}); the string for-of with a string-literal
     * iterable and an empty body. No while/for, no return, no array
     * for-of, no variable declaration, and no numeric literal.
     */
    private static final String LAYER_3_CORPUS = """
        ["s"];
        ({k: "v"});
        "a" + "b";
        `p${"q"}r`;
        ["x"].length;
        if ({flag: true}.flag) {}
        for (let s: string of "ab") {}
        """;

    /** The corpus's pinned derived op-kind multiset at the E5 gate. */
    private static final Map<SemanticOpKind, Long> LAYER_3_PINNED_KINDS = Map.of(
        SemanticOpKind.CONST, 10L,
        SemanticOpKind.STRING_CONCAT, 2L,
        SemanticOpKind.ARRAY_NEW, 2L,
        SemanticOpKind.TABLE_NEW, 2L,
        SemanticOpKind.ARRAY_LENGTH, 1L,
        SemanticOpKind.MEMBER_READ, 1L,
        SemanticOpKind.FOR_EACH, 1L,
        SemanticOpKind.BRANCH, 1L,
        SemanticOpKind.DISCARD, 5L,
        SemanticOpKind.BOUNDARY, 3L);

    private static String readCorpusFixture() {
        try {
            return Files.readString(Path.of(LAYER_3_FIXTURE));
        } catch (Exception e) {
            fail("the committed layer-3 corpus fixture reads cleanly: " + e);
            return null;
        }
    }

    /**
     * Probes one E5 positioning production arm: lowering the probe module
     * succeeds (arm landed) or fails with exactly the pinned E6005
     * {@code CONSTRUCT_UNLOWERED} naming the E5-owned construct (E3's
     * window — correct stage behavior, never a defect). Any other
     * failure is a broken constituent and fails the probe.
     */
    private static boolean probePositioningArm(String probeSource, String constructDescription,
                                               SemanticOpKind expectedProduced) {
        CheckedSlice slice = checkSlice(probeSource);
        if (slice == null) {
            return false;
        }
        PipelineArtifacts artifacts = buildPipeline(slice);
        if (artifacts == null) {
            return false;
        }
        SemanticLowerer.LoweringResult result = lowerPipeline(artifacts);
        if (result != null && !result.hasErrors() && result.unit() != null) {
            check(result.unit().ops().stream().anyMatch(op -> op.kind() == expectedProduced),
                "the landed positioning arm produces " + expectedProduced);
            return true;
        }
        assertConstructUnlowered(result, constructDescription);
        return false;
    }

    /**
     * The synthetic pinned derived op set of the corpus at the E5 gate —
     * the exact op multiset the corpus's construct list derives (D9 item
     * 5(c)): 10 CONSTs, 2 STRING_CONCATs, 2 ARRAY_NEW + 2
     * ARRAY_LITERAL_ELEMENT children, 2 TABLE_NEW, 1 ARRAY_LENGTH, 1
     * MEMBER_READ + 1 CONTEXTUAL_TABLE_READ child, 1 FOR_EACH, 1 BRANCH,
     * 5 DISCARDS. Built through the same closed payload shapes the
     * validator accepts.
     */
    private static List<SemanticOp> pinnedCorpusOpSet() {
        List<SemanticOp> ops = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ops.add(op(SemanticOpKind.CONST,
                new KindPayload.ConstPayload(new ScalarValue.String("s" + i)), nextValue(), STR,
                FailurePolicyId.NO_DEAL_FAILURE, null));
        }
        for (int i = 0; i < 2; i++) {
            ops.add(op(SemanticOpKind.STRING_CONCAT,
                new KindPayload.StringConcatPayload(List.of(nextValue(), nextValue())),
                nextValue(), STR, FailurePolicyId.NO_DEAL_FAILURE, null));
        }
        for (int i = 0; i < 2; i++) {
            OpId arrayNewId = nextOpId();
            OpId childId = nextOpId();
            ops.add(opWith(arrayNewId, SemanticOpKind.ARRAY_NEW,
                new KindPayload.ArrayNewPayload(STR, List.of(nextValue()), List.of(childId)),
                nextValue(), new RuntimeDescriptor.Array(STR),
                FailurePolicyId.NO_DEAL_FAILURE, null));
            ops.add(boundaryWith(childId, BoundaryKind.ARRAY_LITERAL_ELEMENT, STR,
                FailurePolicyId.ARRAY_ELEMENT_DESCRIPTOR, arrayNewId));
        }
        for (int i = 0; i < 2; i++) {
            ops.add(op(SemanticOpKind.TABLE_NEW,
                new KindPayload.TableNewPayload(List.of(
                    new KindPayload.TableEntry("k", nextValue()))),
                nextValue(), TBL, FailurePolicyId.NO_DEAL_FAILURE, null));
        }
        ops.add(op(SemanticOpKind.ARRAY_LENGTH,
            new KindPayload.ArrayLengthPayload(nextValue()), nextValue(), INT,
            FailurePolicyId.INT32_RESULT, null));
        {
            OpId memberReadId = nextOpId();
            OpId childId = nextOpId();
            ops.add(opWith(memberReadId, SemanticOpKind.MEMBER_READ,
                new KindPayload.MemberReadPayload(nextValue(), "k"), nextValue(), BOOL,
                FailurePolicyId.NO_DEAL_FAILURE, null));
            ops.add(boundaryWith(childId, BoundaryKind.CONTEXTUAL_TABLE_READ, BOOL,
                FailurePolicyId.TYPE_DESCRIPTOR, memberReadId));
        }
        ops.add(op(SemanticOpKind.FOR_EACH,
            new KindPayload.ForEachPayload(IterationMode.STRING_SCALARS, nextValue(),
                new BindingId(1), SemanticLowerer.INITIAL_LOOP_GENERATION, new BlockId(1)),
            null, null, FailurePolicyId.TYPE_DESCRIPTOR, null));
        ops.add(op(SemanticOpKind.BRANCH,
            new KindPayload.BranchPayload(ControlSelector.IF, nextValue(), new BlockId(2),
                null),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, null));
        for (int i = 0; i < 5; i++) {
            ops.add(op(SemanticOpKind.DISCARD, new KindPayload.DiscardPayload(nextValue()), null,
                null, FailurePolicyId.NO_DEAL_FAILURE, null));
        }
        return ops;
    }

    /**
     * Asserts the pinned E5-gate claim state of the corpus over the given
     * produced op set (D9 item 5(c)): exactly
     * {@code {DESCRIPTORS, BOUNDARIES}} claimed with
     * {@code FOUNDATION_VALUES}, {@code CONTAINERS_AND_STRINGS}, and
     * {@code EVALUATION_ORDER} deferred per unit; the run is green with
     * R-CAPABILITY and R-COVERAGE and no E6005.
     */
    private static void assertPinnedE5ClaimState(List<SemanticOp> ops) {
        Set<SemanticCapability> derived = ContainerClaimingSeam.deriveClaims(ops,
            ContainerClaimingSeam.E5_GATE_ACTIVATION);
        check(derived.equals(Set.of(SemanticCapability.DESCRIPTORS,
                SemanticCapability.BOUNDARIES)),
            "the corpus derives exactly {DESCRIPTORS, BOUNDARIES}; got " + derived);
        check(!derived.contains(SemanticCapability.FOUNDATION_VALUES)
                && !derived.contains(SemanticCapability.CONTAINERS_AND_STRINGS)
                && !derived.contains(SemanticCapability.EVALUATION_ORDER),
            "FOUNDATION_VALUES (CONST/STRING_CONCAT without UNARY/BINARY), "
                + "CONTAINERS_AND_STRINGS (the six ops without INDEX_*/OPTIONAL_READ/"
                + "HAS_FIELD), and EVALUATION_ORDER (BRANCH/DISCARD without LOOP) are not "
                + "claimed");
        ContainerClaimingSeam.SeamResult result = ContainerClaimingSeam.check(ops,
            ContainerClaimingSeam.E5_GATE_ACTIVATION, derived, MODULE);
        check(result.failure() == null,
            "the corpus run is green with exactly its derived claim state — E6005 never "
                + "fires for the corpus");
        check(result.outcomes().size() == 25,
            "25 recorded outcomes: 12 FOUNDATION_VALUES deferrals + 7 "
                + "CONTAINERS_AND_STRINGS deferrals + 3 boundary children × 2 claimed "
                + "rows; got " + result.outcomes().size());
        long deferredFv = 0;
        long deferredCs = 0;
        long claimed = 0;
        for (ContainerClaimingSeam.RecordedOutcome outcome : result.outcomes()) {
            if (outcome.outcome() == ContainerClaimingSeam.OutcomeKind.DEFERRED
                    && outcome.home() == SemanticCapability.FOUNDATION_VALUES) {
                deferredFv++;
            }
            if (outcome.outcome() == ContainerClaimingSeam.OutcomeKind.DEFERRED
                    && outcome.home() == SemanticCapability.CONTAINERS_AND_STRINGS) {
                deferredCs++;
            }
            if (outcome.outcome() == ContainerClaimingSeam.OutcomeKind.CLAIMED
                    && (outcome.home() == SemanticCapability.DESCRIPTORS
                        || outcome.home() == SemanticCapability.BOUNDARIES)) {
                claimed++;
            }
        }
        check(deferredFv == 12,
            "the 10 CONSTs + 2 STRING_CONCATs record the FOUNDATION_VALUES per-unit "
                + "deferral; got " + deferredFv);
        check(deferredCs == 7,
            "the 2 ARRAY_NEW + 2 TABLE_NEW + ARRAY_LENGTH + MEMBER_READ + FOR_EACH record "
                + "the CONTAINERS_AND_STRINGS per-unit deferral; got " + deferredCs);
        check(claimed == 6,
            "the 3 boundary children record the DESCRIPTORS and BOUNDARIES claimed "
                + "outcomes (each single-family row fully evidenced); got " + claimed);
        check(result.outcomes().stream().noneMatch(outcome ->
                outcome.opKind() == SemanticOpKind.BRANCH
                    || outcome.opKind() == SemanticOpKind.DISCARD),
            "the corpus's BRANCH/DISCARD ops record no op-side outcome in this seam — "
                + "their outcomes are recorded by E5's producer under the shared mechanism "
                + "(D9 item 4)");
        LoweredModuleUnit unit = unit(derived, LAYER_3_PINNED_COVERAGE, ops);
        assertPass(SemanticIrValidator.validate(unit, facts()),
            "the corpus unit with claims {DESCRIPTORS, BOUNDARIES} passes R-CAPABILITY and "
                + "R-COVERAGE with no E6005");
    }

    /** The corpus's recorded coverage rows at the E5 gate (detector-verbatim). */
    private static final Map<ConstructKind, List<SemanticOpKind>> LAYER_3_PINNED_COVERAGE =
        Map.of(
            ConstructKind.SCALAR_LITERAL, ConstructKind.SCALAR_LITERAL.mappedOpKinds(),
            ConstructKind.STRING_CONCAT_TEMPLATE,
            ConstructKind.STRING_CONCAT_TEMPLATE.mappedOpKinds(),
            ConstructKind.ARRAY_OBJECT_LITERAL, ConstructKind.ARRAY_OBJECT_LITERAL.mappedOpKinds(),
            ConstructKind.MEMBER_ACCESS, ConstructKind.MEMBER_ACCESS.mappedOpKinds(),
            ConstructKind.IF_WHILE_FOR_FOR_OF, ConstructKind.IF_WHILE_FOR_FOR_OF.mappedOpKinds(),
            ConstructKind.RETURN_EXPRESSION_STATEMENT,
            ConstructKind.RETURN_EXPRESSION_STATEMENT.mappedOpKinds());

    static void testLayer3Gate() {
        System.out.println("-- Layer 3: the pinned fixed corpus and its E5-gate boundary --");

        // The committed fixture is the pinned corpus (the fixture is the
        // authoritative committed form; the inline constant must equal it).
        String fixture = readCorpusFixture();
        if (fixture == null) {
            return;
        }
        check(fixture.equals(LAYER_3_CORPUS),
            "the committed fixture equals the pinned corpus constant verbatim");

        CheckedSlice slice = checkSlice(LAYER_3_CORPUS);
        if (slice == null) {
            return;
        }
        PipelineArtifacts artifacts = buildPipeline(slice);
        if (artifacts == null) {
            return;
        }

        // The foundation detector records the pinned rows for the corpus's
        // constructs (the recorded expectation, computed in E3's window).
        Map<ConstructKind, List<SemanticOpKind>> coverage = artifacts.manifest()
            .constructCoverage();
        check(coverage.keySet().equals(Set.of(
                ConstructKind.SCALAR_LITERAL, ConstructKind.STRING_CONCAT_TEMPLATE,
                ConstructKind.ARRAY_OBJECT_LITERAL, ConstructKind.MEMBER_ACCESS,
                ConstructKind.IF_WHILE_FOR_FOR_OF,
                ConstructKind.RETURN_EXPRESSION_STATEMENT)),
            "the detector records exactly the pinned corpus rows: " + coverage.keySet());
        for (Map.Entry<ConstructKind, List<SemanticOpKind>> entry : coverage.entrySet()) {
            check(entry.getValue().equals(entry.getKey().mappedOpKinds()),
                "the recorded " + entry.getKey() + " row carries the closed construct→op "
                    + "detector table verbatim");
        }

        // The gate probes E5's two positioning production arms
        // (expression-statement DISCARD, if-condition BRANCH).
        boolean discardLanded = probePositioningArm("\"probe\";",
            "expression statement (DISCARD is E5's, ISSUE-0234)", SemanticOpKind.DISCARD);
        boolean branchLanded = probePositioningArm("if ({p: true}.p) {}",
            "if statement (BRANCH(IF) is E5's, ISSUE-0234)", SemanticOpKind.BRANCH);

        if (discardLanded && branchLanded) {
            // --- The E5 gate is open: execute the pinned full-corpus
            //     whole-pipeline run and verify exactly this design's
            //     shapes, orders, determinism, and claim state. ---
            System.out.println("Layer 3 gate: E5's positioning arms landed — executing the "
                + "pinned full-corpus pipeline at E5's gate.");
            testLayer3CorpusPipelineE5Gate(slice);
            return;
        }
        if (discardLanded || branchLanded) {
            fail("the layer-3 gate is half-open: E5's two positioning production arms "
                + "(expression-statement DISCARD and if-condition BRANCH) land together at "
                + "E5's gate, never one at a time — a broken constituent state");
            return;
        }

        // --- E3's window: the gate records the pinned expectation without
        //     executing the full-corpus pipeline. ---
        System.out.println("Layer 3 gate: E3's window — recording the pinned E5-gate "
            + "expectation; the full-corpus pipeline is not executed.");

        // The recorded valid-until boundary is E5's gate: the activation
        // hand-off state at that gate is the pinned set.
        check(ContainerClaimingSeam.E5_GATE_ACTIVATION.equals(Set.of(
                SemanticCapability.SIGNED_INT32, SemanticCapability.FOUNDATION_VALUES,
                SemanticCapability.DESCRIPTORS, SemanticCapability.BOUNDARIES,
                SemanticCapability.CONTAINERS_AND_STRINGS,
                SemanticCapability.EVALUATION_ORDER)),
            "the valid-until boundary is E5's gate with the pinned activation set: "
                + ContainerClaimingSeam.E5_GATE_ACTIVATION);
        check(ContainerClaimingSeam.E3_WINDOW_ACTIVATION.containsAll(
                ContainerClaimingSeam.E3_WINDOW_ACTIVATION)
                && ContainerClaimingSeam.E5_GATE_ACTIVATION.containsAll(
                    ContainerClaimingSeam.E3_WINDOW_ACTIVATION),
            "the activation states grow monotonically from E3's window to E5's gate");

        // The full-corpus pipeline is not executed in E3's window:
        // attempting it fails with the pinned E6005 CONSTRUCT_UNLOWERED at
        // the first E5-owned construct (correct stage behavior, never a
        // defect, never a reroute).
        SemanticLowerer.LoweringResult refused = lowerPipeline(artifacts);
        assertConstructUnlowered(refused,
            "expression statement (DISCARD is E5's, ISSUE-0234)");

        // The pinned claim-state expectation is recorded and verified now
        // over the corpus's pinned derived op set (the exact op multiset
        // the corpus's construct list derives at the E5 gate).
        assertPinnedE5ClaimState(pinnedCorpusOpSet());
    }

    /**
     * The E5-gate execution of the pinned full-corpus pipeline (live only
     * when both positioning arms landed): checked project → lowering →
     * validated unit with the exact pinned shapes, orders, claim state,
     * and byte-identical determinism. ISSUE-0234's design must keep the
     * pinned corpus positionable and its derived claim state exactly as
     * pinned here.
     */
    static void testLayer3CorpusPipelineE5Gate(CheckedSlice slice) {
        PipelineArtifacts artifacts = buildPipeline(slice);
        if (artifacts == null) {
            return;
        }
        SemanticLowerer.LoweringResult result = lowerPipeline(artifacts);
        check(result != null && !result.hasErrors() && result.unit() != null,
            "the corpus's whole-pipeline run executes at E5's gate with no E6005: "
                + (result == null ? "null" : result.diagnostics()));
        if (result == null || result.hasErrors() || result.unit() == null) {
            return;
        }
        LoweredModuleUnit unit = result.unit();
        List<SemanticOp> ops = unit.ops();

        // The exact pinned op multiset (no LOOP, no RETURN, no BINARY, no
        // BINDING_*, no array for-of, no numeric literal).
        for (Map.Entry<SemanticOpKind, Long> entry : LAYER_3_PINNED_KINDS.entrySet()) {
            check(countKinds(ops, entry.getKey()) == entry.getValue(),
                entry.getKey() + " count is exactly " + entry.getValue() + "; got "
                    + countKinds(ops, entry.getKey()));
        }
        check(ops.size() == 28, "the corpus produces exactly 28 ops; got " + ops.size());
        check(ops.stream().noneMatch(op -> op.kind() == SemanticOpKind.LOOP)
                && ops.stream().noneMatch(op -> op.kind() == SemanticOpKind.RETURN)
                && ops.stream().noneMatch(op -> op.kind() == SemanticOpKind.BINARY)
                && ops.stream().noneMatch(op -> op.kind() == SemanticOpKind.BINDING_LOAD)
                && ops.stream().noneMatch(op -> op.kind() == SemanticOpKind.BINDING_ALLOC)
                && ops.stream().noneMatch(op -> op.kind() == SemanticOpKind.UNARY),
            "the corpus carries no LOOP/RETURN/BINARY/BINDING_*/UNARY op (the pinned "
                + "exclusions hold)");

        // Per-statement source order: every op's origin span line pins its
        // statement; the per-line kind sequences are the pinned ones.
        Map<Integer, List<SemanticOp>> byLine = new java.util.LinkedHashMap<>();
        for (SemanticOp op : ops) {
            int line = op.origin().span().startLine();
            byLine.computeIfAbsent(line, ignored -> new ArrayList<>()).add(op);
        }
        check(byLine.size() == 7, "the corpus ops span exactly the seven statements; got "
            + byLine.keySet());
        List<List<SemanticOpKind>> pinnedSequences = List.of(
            List.of(SemanticOpKind.CONST, SemanticOpKind.ARRAY_NEW, SemanticOpKind.BOUNDARY,
                SemanticOpKind.DISCARD),
            List.of(SemanticOpKind.CONST, SemanticOpKind.TABLE_NEW, SemanticOpKind.DISCARD),
            List.of(SemanticOpKind.CONST, SemanticOpKind.CONST, SemanticOpKind.STRING_CONCAT,
                SemanticOpKind.DISCARD),
            List.of(SemanticOpKind.CONST, SemanticOpKind.CONST, SemanticOpKind.CONST,
                SemanticOpKind.STRING_CONCAT, SemanticOpKind.DISCARD),
            List.of(SemanticOpKind.CONST, SemanticOpKind.ARRAY_NEW, SemanticOpKind.BOUNDARY,
                SemanticOpKind.ARRAY_LENGTH, SemanticOpKind.DISCARD),
            List.of(SemanticOpKind.CONST, SemanticOpKind.TABLE_NEW, SemanticOpKind.MEMBER_READ,
                SemanticOpKind.BOUNDARY, SemanticOpKind.BRANCH),
            List.of(SemanticOpKind.CONST, SemanticOpKind.FOR_EACH));
        int lineIndex = 0;
        for (List<SemanticOp> lineOps : byLine.values()) {
            if (lineIndex >= pinnedSequences.size()) {
                break;
            }
            List<SemanticOpKind> actual = new ArrayList<>();
            for (SemanticOp op : lineOps) {
                actual.add(op.kind());
            }
            check(actual.equals(pinnedSequences.get(lineIndex)),
                "statement " + (lineIndex + 1) + " produces the pinned op sequence "
                    + pinnedSequences.get(lineIndex) + "; got " + actual);
            lineIndex++;
        }

        // Shape facts per family (the six ops + the operand producers).
        for (SemanticOp arrayNew : ofKind(ops, SemanticOpKind.ARRAY_NEW)) {
            KindPayload.ArrayNewPayload payload =
                (KindPayload.ArrayNewPayload) arrayNew.payload();
            check(payload.elementDescriptor().equals(RuntimeDescriptor.String.INSTANCE),
                "ARRAY_NEW elementDescriptor is string");
            check(payload.values().size() == 1
                    && payload.elementBoundaryOpIds().size() == 1
                    && payload.elementBoundaryOpIds().get(0).equals(
                        opById(ops, payload.elementBoundaryOpIds().get(0)).opId()),
                "ARRAY_NEW carries one element value and one child boundary op");
            check(arrayNew.resultType().equals(new RuntimeDescriptor.Array(
                    RuntimeDescriptor.String.INSTANCE)),
                "ARRAY_NEW resultType is [string]");
            check(arrayNew.failurePolicy() == FailurePolicyId.NO_DEAL_FAILURE,
                "ARRAY_NEW policy NO_DEAL_FAILURE");
        }
        for (SemanticOp child : boundaries(ops, BoundaryKind.ARRAY_LITERAL_ELEMENT)) {
            KindPayload.BoundaryPayload boundary = (KindPayload.BoundaryPayload) child.payload();
            check(boundary.descriptor().equals(RuntimeDescriptor.String.INSTANCE)
                    && child.failurePolicy() == FailurePolicyId.ARRAY_ELEMENT_DESCRIPTOR
                    && boundary.realization().equals(new BoundaryRealization.RuntimeValidation(
                        SemanticLowerer.CANONICAL_RUNTIME_VALIDATION_ID)),
                "each ARRAY_LITERAL_ELEMENT child carries the string descriptor, "
                    + "ARRAY_ELEMENT_DESCRIPTOR, and the canonical realization");
            SemanticOp parent = opById(ops, child.origin().parentOpId());
            check(parent != null && parent.kind() == SemanticOpKind.ARRAY_NEW,
                "each ARRAY_LITERAL_ELEMENT child is parented to its ARRAY_NEW");
            check(((KindPayload.ArrayNewPayload) parent.payload()).values().get(0)
                    .equals(boundary.input()),
                "the child input is its element's ValueId");
        }
        for (SemanticOp tableNew : ofKind(ops, SemanticOpKind.TABLE_NEW)) {
            KindPayload.TableNewPayload payload = (KindPayload.TableNewPayload) tableNew.payload();
            check(payload.entries().size() == 1
                    && (payload.entries().get(0).key().equals("k")
                        || payload.entries().get(0).key().equals("flag")),
                "each TABLE_NEW carries its one identifier-key entry in source order");
            check(tableNew.resultType().equals(RuntimeDescriptor.Table.INSTANCE)
                    && tableNew.failurePolicy() == FailurePolicyId.NO_DEAL_FAILURE,
                "TABLE_NEW resultType table and policy NO_DEAL_FAILURE");
        }
        for (SemanticOp concat : ofKind(ops, SemanticOpKind.STRING_CONCAT)) {
            check(concat.resultType().equals(RuntimeDescriptor.String.INSTANCE)
                    && concat.failurePolicy() == FailurePolicyId.NO_DEAL_FAILURE
                    && !((KindPayload.StringConcatPayload) concat.payload()).fragments()
                        .isEmpty(),
                "each STRING_CONCAT carries its fragments in source order, resultType "
                    + "string, NO_DEAL_FAILURE");
        }
        for (SemanticOp length : ofKind(ops, SemanticOpKind.ARRAY_LENGTH)) {
            check(length.resultType().equals(RuntimeDescriptor.Int.INSTANCE)
                    && length.failurePolicy() == FailurePolicyId.INT32_RESULT
                    && ((KindPayload.ArrayLengthPayload) length.payload()).arrayValue() != null,
                "ARRAY_LENGTH carries the receiver ValueId, resultType int, "
                    + "INT32_RESULT (the signed32 count; the receiver is evaluated once)");
        }
        for (SemanticOp read : ofKind(ops, SemanticOpKind.MEMBER_READ)) {
            KindPayload.MemberReadPayload payload = (KindPayload.MemberReadPayload) read.payload();
            check("flag".equals(payload.key())
                    && read.resultType().equals(RuntimeDescriptor.Boolean.INSTANCE)
                    && read.failurePolicy() == FailurePolicyId.NO_DEAL_FAILURE,
                "MEMBER_READ carries the constant key \"flag\", D(contextual type) = boolean "
                    + "(the if-condition context), NO_DEAL_FAILURE");
            SemanticOp flagTable = null;
            for (SemanticOp tableNew : ofKind(ops, SemanticOpKind.TABLE_NEW)) {
                KindPayload.TableNewPayload tablePayload =
                    (KindPayload.TableNewPayload) tableNew.payload();
                if (tablePayload.entries().size() == 1
                        && "flag".equals(tablePayload.entries().get(0).key())) {
                    flagTable = tableNew;
                }
            }
            check(flagTable != null && payload.table().equals(flagTable.result()),
                "MEMBER_READ's table is the if-condition's TABLE_NEW result (the receiver "
                    + "prior step, exactly once)");
        }
        for (SemanticOp child : boundaries(ops, BoundaryKind.CONTEXTUAL_TABLE_READ)) {
            KindPayload.BoundaryPayload boundary = (KindPayload.BoundaryPayload) child.payload();
            check(boundary.descriptor().equals(RuntimeDescriptor.Boolean.INSTANCE)
                    && child.failurePolicy() == FailurePolicyId.TYPE_DESCRIPTOR
                    && boundary.realization().equals(new BoundaryRealization.RuntimeValidation(
                        SemanticLowerer.CANONICAL_RUNTIME_VALIDATION_ID)),
                "the CONTEXTUAL_TABLE_READ child carries the boolean descriptor, "
                    + "TYPE_DESCRIPTOR (the descriptor-kind rule), and the canonical "
                    + "realization");
            SemanticOp parent = opById(ops, child.origin().parentOpId());
            check(parent != null && parent.kind() == SemanticOpKind.MEMBER_READ
                    && boundary.input().equals(parent.result()),
                "the CONTEXTUAL_TABLE_READ child is parented to its MEMBER_READ and its "
                    + "input is the MEMBER_READ result");
        }
        for (SemanticOp forEach : ofKind(ops, SemanticOpKind.FOR_EACH)) {
            KindPayload.ForEachPayload payload = (KindPayload.ForEachPayload) forEach.payload();
            check(payload.mode() == IterationMode.STRING_SCALARS
                    && payload.binding() != null
                    && payload.generation() == SemanticLowerer.INITIAL_LOOP_GENERATION
                    && forEach.failurePolicy() == FailurePolicyId.TYPE_DESCRIPTOR,
                "FOR_EACH is STRING_SCALARS over the literal iterable with the loop binding "
                    + "'s', the initial generation, and TYPE_DESCRIPTOR (the empty body "
                    + "produces no BINDING_LOAD)");
        }
        for (SemanticOp branch : ofKind(ops, SemanticOpKind.BRANCH)) {
            KindPayload.BranchPayload payload = (KindPayload.BranchPayload) branch.payload();
            check(payload.selector() == ControlSelector.IF,
                "the corpus's BRANCH selector is IF (the if-condition member read)");
            SemanticOp read = ofKind(ops, SemanticOpKind.MEMBER_READ).get(0);
            check(payload.condition().equals(read.result()),
                "the BRANCH condition is the MEMBER_READ result");
        }
        for (SemanticOp discard : ofKind(ops, SemanticOpKind.DISCARD)) {
            check(((KindPayload.DiscardPayload) discard.payload()).value() != null,
                "each DISCARD carries its expression's producing value");
        }
        check(ofKind(ops, SemanticOpKind.FOR_EACH).size() == 1
                && boundaries(ops, BoundaryKind.CONTEXTUAL_TABLE_READ).size() == 1
                && ofKind(ops, SemanticOpKind.ARRAY_LENGTH).size() == 1
                && ofKind(ops, SemanticOpKind.MEMBER_READ).size() == 1
                && ofKind(ops, SemanticOpKind.BRANCH).size() == 1
                && ofKind(ops, SemanticOpKind.DISCARD).size() == 5,
            "the six constructs' op families appear with the pinned counts");

        // The pinned claim state of the real produced unit (D9 item 5(c)).
        check(unit.requiredCapabilities().equals(Set.of(SemanticCapability.DESCRIPTORS,
                SemanticCapability.BOUNDARIES)),
            "the corpus unit's claim set is exactly {DESCRIPTORS, BOUNDARIES}; got "
                + unit.requiredCapabilities());
        assertPinnedE5ClaimState(ops);

        // Determinism: a second full pipeline repetition is byte-identical.
        PipelineArtifacts repeatedArtifacts = buildPipeline(slice);
        check(repeatedArtifacts != null, "the repeated pipeline run builds again");
        if (repeatedArtifacts == null) {
            return;
        }
        SemanticLowerer.LoweringResult repeated = lowerPipeline(repeatedArtifacts);
        check(repeated != null && !repeated.hasErrors() && repeated.unit() != null,
            "the repeated pipeline run validates again");
        if (repeated != null && !repeated.hasErrors() && repeated.unit() != null) {
            check(Arrays.equals(SemanticIrDumper.dumpModule(unit),
                    SemanticIrDumper.dumpModule(repeated.unit())),
                "two full corpus pipeline repetitions produce byte-identical dumps (D8)");
            check(repeated.unit().requiredCapabilities().equals(unit.requiredCapabilities()),
                "the repeated unit's claim set is identical");
        }
    }

    private static SemanticOp opById(List<SemanticOp> ops, OpId id) {
        for (SemanticOp op : ops) {
            if (op.opId().equals(id)) {
                return op;
            }
        }
        return null;
    }

    // =========================================================================
    // Layer 3 fault injection: named constituent failures
    // =========================================================================

    static void testLayer3FaultInjection() {
        System.out.println("-- Layer 3 fault injection: named constituent failures --");

        List<SemanticOp> corpusOps = pinnedCorpusOpSet();
        Set<SemanticCapability> derived = ContainerClaimingSeam.deriveClaims(corpusOps,
            ContainerClaimingSeam.E5_GATE_ACTIVATION);
        check(derived.equals(Set.of(SemanticCapability.DESCRIPTORS,
                SemanticCapability.BOUNDARIES)),
            "the pinned corpus op set derives {DESCRIPTORS, BOUNDARIES} (the intact "
                + "baseline for the injections)");

        // --- Fault L3-A: a corrupted claim state fails the seam. ---
        // The boundary children fully evidence DESCRIPTORS and BOUNDARIES;
        // omitting DESCRIPTORS while fully evidenced fires the seam's
        // single E6005 condition.
        {
            ContainerClaimingSeam.SeamResult omitted = ContainerClaimingSeam.check(corpusOps,
                ContainerClaimingSeam.E5_GATE_ACTIVATION,
                Set.of(SemanticCapability.BOUNDARIES), MODULE);
            check(omitted.failure() != null,
                "omitting the fully evidenced DESCRIPTORS claim fires the seam's E6005 "
                    + "condition");
            if (omitted.failure() != null) {
                check(omitted.failure().capability() == SemanticCapability.DESCRIPTORS
                        && ContainerClaimingSeam.OPERATION_OUTSIDE_CLAIMED_CAPABILITY
                        .equals(omitted.failure().validatorRule()),
                    "the named failure is OPERATION_OUTSIDE_CLAIMED_CAPABILITY on "
                        + "DESCRIPTORS");
            }
        }

        // Claiming an under-evidenced row is impossible by the derivation —
        // the seam fails closed.
        {
            try {
                ContainerClaimingSeam.check(corpusOps, ContainerClaimingSeam.E5_GATE_ACTIVATION,
                    Set.of(SemanticCapability.DESCRIPTORS, SemanticCapability.BOUNDARIES,
                        SemanticCapability.EVALUATION_ORDER), MODULE);
                fail("a corrupted claim state (EVALUATION_ORDER without LOOP evidence) must "
                    + "fail the seam closed");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("impossible by the derivation")
                        && expected.getMessage().contains("EVALUATION_ORDER"),
                    "the corrupted claim state fails the seam with the named "
                        + "derivation-invariant defect: " + expected.getMessage());
            }
        }

        // --- Fault L3-B: a corpus extension adding a while/for statement
        //     (a LOOP op) fully evidences EVALUATION_ORDER and changes the
        //     pinned claim state — outside this epic's pinned integration
        //     tail, exactly as pinned. ---
        {
            List<SemanticOp> extended = new ArrayList<>(corpusOps);
            extended.add(op(SemanticOpKind.LOOP,
                new KindPayload.LoopPayload(ControlSelector.WHILE, null, nextValue(),
                    new BlockId(3), null),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, null));
            Set<SemanticCapability> extendedClaims = ContainerClaimingSeam.deriveClaims(extended,
                ContainerClaimingSeam.E5_GATE_ACTIVATION);
            check(extendedClaims.contains(SemanticCapability.EVALUATION_ORDER),
                "the LOOP-adding extension fully evidences EVALUATION_ORDER and derives it — "
                    + "the pinned claim state changes exactly as the design pins");
            check(!extendedClaims.equals(derived),
                "the extension's claim state differs from the pinned corpus state");
        }
    }

    // =========================================================================
    // Runner
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Container Integration Test (ISSUE-0388, decomposition tail) ===\n");

        System.out.println("-- Layer 1 (E3's gate): the positionable whole-pipeline corpus --");
        testLayer1WholePipelineCorpus();
        testLayer1FaultInjection();

        System.out.println();
        System.out.println("-- Layer 2 (E3's gate): per-construct checked-source slices --");
        testLayer2Slices();
        testLayer2FaultInjection();

        System.out.println();
        System.out.println("-- Layer 3 (pinned; whole-pipeline run gated at E5's gate) --");
        testLayer3Gate();
        testLayer3FaultInjection();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
