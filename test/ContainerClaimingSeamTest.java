package deal.test;

import deal.ast.ArrayLiteralExpr;
import deal.ast.BinaryExpr;
import deal.ast.Either;
import deal.ast.ExpressionNode;
import deal.ast.ForOfStatement;
import deal.ast.IdentifierExpr;
import deal.ast.LiteralExpr;
import deal.ast.MemberAccessExpr;
import deal.ast.ObjectLiteralExpr;
import deal.ast.ProgramNode;
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
import deal.semantic.CheckedModuleKind;
import deal.semantic.CheckedProjectInput;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ContainerClaimingSeam;
import deal.semantic.LoweringSupport;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticRequirementManifest;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BinarySelector;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.BoundaryRealization;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.ClosedSelector;
import deal.semantic.ir.ConstructKind;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.ControlSelector;
import deal.semantic.ir.ExportPlan;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.ExternalModuleKind;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.InitializationMode;
import deal.semantic.ir.IterationMode;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.LoweringContextHash;
import deal.semantic.ir.LoweringFailureDetail;
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
import deal.semantic.ir.UnarySelector;
import deal.semantic.ir.ValueId;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Verifies the ISSUE-0387 stage-incremental claiming seam
 * ({@link ContainerClaimingSeam}): the pinned home mapping, the activation
 * gates, the full-evidence claim derivation, the activation-gated op-side
 * check with its three recorded outcomes plus the single E6005
 * {@code OPERATION_OUTSIDE_CLAIMED_CAPABILITY} firing condition, the E3
 * tail's ∅-claim staged state, the pinned E5-gate corpus claim state
 * ({@code {DESCRIPTORS, BOUNDARIES}} claimed with
 * {@code FOUNDATION_VALUES}/{@code CONTAINERS_AND_STRINGS}/
 * {@code EVALUATION_ORDER} deferred per unit), validator acceptance for
 * every D1 shape with the pinned negatives (R-PRIVATE-STEP, R-COVERAGE,
 * misplaced boundary, wrong boundary policy, wrong boundary count),
 * byte-identical determinism, and the combined dependency step — the
 * nested-for-of corpus through the foundation detector, C5's lowering
 * arms, and the seam into a validated ∅-claim unit.
 */
public class ContainerClaimingSeamTest {

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
            new ExternalModuleInterface(MODULE, ExternalModuleKind.IMPLEMENTATION,
                List.of(), List.of(), List.of(), InitializationMode.ONCE_AFTER_DEPENDENCIES)))
        .interfaceIndexDigest();

    private static SemanticIrValidator.ComparisonFacts facts() {
        return new SemanticIrValidator.ComparisonFacts(INTERFACE_HASH,
            SemanticProfile.DEAL_V1_2_INT32, REGISTRY_HASH);
    }

    private static SemanticLowerer.ModuleLowerer lowerer(CheckResult checks) {
        return new SemanticLowerer.ModuleLowerer(MODULE, SOURCE_ID, checks,
            SemanticIdAllocator.over(List.of(MODULE)));
    }

    private record CheckedSlice(ProgramNode program, CheckResult checks) {
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
        return new CheckedSlice(parse.program(), result);
    }

    private static CheckedModuleInput moduleOf(CheckedSlice slice) {
        return new CheckedModuleInput(MODULE, SOURCE_ID, Path.of("test.deal"), slice.program(),
            slice.checks(), List.of(), List.of(), CheckedModuleKind.IMPLEMENTATION);
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
                    if (branch instanceof Either.Left<deal.ast.IfStatement, deal.ast.Block> left) {
                        collectStatements(left.value(), outStatements, outExpressions);
                    } else if (branch
                            instanceof Either.Right<deal.ast.IfStatement, deal.ast.Block> right) {
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
                new BoundaryRealization.RuntimeValidation("runtime-validation")),
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

    private static void assertE6005(Optional<CompilerDiagnostic> diagnostic, String rule,
            String... contains) {
        check(diagnostic.isPresent(), rule + " is rejected with E6005");
        if (diagnostic.isEmpty()) {
            return;
        }
        CompilerDiagnostic d = diagnostic.get();
        check("E6005".equals(d.code()), rule + " diagnostic code is E6005");
        check(DiagnosticCode.E6005.phase() == DiagnosticCode.Phase.BACKEND_LOWERING,
            rule + " phase is BACKEND_LOWERING");
        check("error".equals(d.severity()), rule + " severity is error");
        String message = d.message();
        check(message.startsWith("Common semantic lowering failed"),
            rule + " message instantiates the registry-owned E6005 template");
        String[] byRule = message.split("validatorRule ");
        check(byRule.length == 2 && byRule[1].startsWith(rule + ","),
            rule + " is named as the validator rule; got \"" + message + "\"");
        for (String c : contains) {
            check(message.contains(c), rule + " message contains \"" + c + "\"; got \""
                + message + "\"");
        }
    }

    // ---- text-surface substitution helpers (one leaf; R-PRIVATE-STEP precedes
    // ---- R-DIGEST in the closed rule order, so no digest recompute is needed) ----

    private static CanonicalJson.Obj parseObj(String text) {
        return (CanonicalJson.Obj) CanonicalJson.parse(text);
    }

    private static CanonicalJson.Value at(CanonicalJson.Obj obj, String key) {
        for (CanonicalJson.Entry entry : obj.entries()) {
            if (entry.key().equals(key)) {
                return entry.value();
            }
        }
        return null;
    }

    private static CanonicalJson.Obj withEntry(CanonicalJson.Obj obj, String key,
            CanonicalJson.Value value) {
        List<CanonicalJson.Entry> entries = new ArrayList<>();
        for (CanonicalJson.Entry entry : obj.entries()) {
            entries.add(entry.key().equals(key) ? CanonicalJson.e(key, value) : entry);
        }
        return CanonicalJson.obj(entries);
    }

    private static String serialize(CanonicalJson.Value value) {
        return CanonicalJson.serializeText(value);
    }

    private static String substituteFirstOpKind(String text, String to) {
        CanonicalJson.Obj root = parseObj(text);
        CanonicalJson.Arr ops = (CanonicalJson.Arr) at(root, "ops");
        CanonicalJson.Obj op = (CanonicalJson.Obj) ops.items().get(0);
        CanonicalJson.Obj contract = (CanonicalJson.Obj) at(op, "contract");
        CanonicalJson.Obj op2 = withEntry(op, "kind", CanonicalJson.str(to));
        op2 = withEntry(op2, "contract", withEntry(contract, "opKind", CanonicalJson.str(to)));
        List<CanonicalJson.Value> items = new ArrayList<>(ops.items());
        items.set(0, op2);
        return serialize(withEntry(root, "ops", CanonicalJson.arr(items)));
    }

    // =========================================================================
    // Outcome lookup helper
    // =========================================================================

    private static ContainerClaimingSeam.RecordedOutcome outcomeOf(
            List<ContainerClaimingSeam.RecordedOutcome> outcomes, OpId opId,
            SemanticCapability home) {
        for (ContainerClaimingSeam.RecordedOutcome outcome : outcomes) {
            if (outcome.opId().equals(opId) && outcome.home() == home) {
                return outcome;
            }
        }
        return null;
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

    // =========================================================================
    // 1. The pinned home mapping (D9 item 3)
    // =========================================================================

    static void testHomeMapping() {
        System.out.println("-- The pinned home mapping --");

        SemanticOp constOp = op(SemanticOpKind.CONST,
            new KindPayload.ConstPayload(new ScalarValue.String("ab")), nextValue(), STR,
            FailurePolicyId.NO_DEAL_FAILURE, null);
        check(ContainerClaimingSeam.homeRows(constOp)
                .equals(List.of(SemanticCapability.FOUNDATION_VALUES)),
            "CONST homes to FOUNDATION_VALUES (scalar literals, template fragments)");

        SemanticOp concatOp = op(SemanticOpKind.STRING_CONCAT,
            new KindPayload.StringConcatPayload(List.of(nextValue(), nextValue())), nextValue(),
            STR, FailurePolicyId.NO_DEAL_FAILURE, null);
        check(ContainerClaimingSeam.homeRows(concatOp)
                .equals(List.of(SemanticCapability.FOUNDATION_VALUES)),
            "STRING_CONCAT homes to FOUNDATION_VALUES");

        List<SemanticOp> containerOps = List.of(
            op(SemanticOpKind.ARRAY_NEW,
                new KindPayload.ArrayNewPayload(INT, List.of(), List.of()), nextValue(),
                new RuntimeDescriptor.Array(INT), FailurePolicyId.NO_DEAL_FAILURE, null),
            op(SemanticOpKind.TABLE_NEW,
                new KindPayload.TableNewPayload(List.of()), nextValue(), TBL,
                FailurePolicyId.NO_DEAL_FAILURE, null),
            op(SemanticOpKind.ARRAY_LENGTH,
                new KindPayload.ArrayLengthPayload(nextValue()), nextValue(), INT,
                FailurePolicyId.INT32_RESULT, null),
            op(SemanticOpKind.MEMBER_READ,
                new KindPayload.MemberReadPayload(nextValue(), "k"), nextValue(), BOOL,
                FailurePolicyId.NO_DEAL_FAILURE, null),
            op(SemanticOpKind.FOR_EACH,
                new KindPayload.ForEachPayload(IterationMode.STRING_SCALARS, nextValue(),
                    new BindingId(1), 0, new BlockId(1)),
                null, null, FailurePolicyId.TYPE_DESCRIPTOR, null));
        for (SemanticOp containerOp : containerOps) {
            check(ContainerClaimingSeam.homeRows(containerOp)
                    .equals(List.of(SemanticCapability.CONTAINERS_AND_STRINGS)),
                containerOp.kind() + " homes to CONTAINERS_AND_STRINGS");
        }

        SemanticOp elementBoundary = boundaryWith(nextOpId(),
            BoundaryKind.ARRAY_LITERAL_ELEMENT, INT, FailurePolicyId.ARRAY_ELEMENT_DESCRIPTOR,
            null);
        check(ContainerClaimingSeam.homeRows(elementBoundary).equals(List.of(
                SemanticCapability.DESCRIPTORS, SemanticCapability.BOUNDARIES)),
            "BOUNDARY(ARRAY_LITERAL_ELEMENT) homes to DESCRIPTORS then BOUNDARIES (pinned order)");

        SemanticOp contextualBoundary = boundaryWith(nextOpId(),
            BoundaryKind.CONTEXTUAL_TABLE_READ, BOOL, FailurePolicyId.TYPE_DESCRIPTOR, null);
        check(ContainerClaimingSeam.homeRows(contextualBoundary).equals(List.of(
                SemanticCapability.DESCRIPTORS, SemanticCapability.BOUNDARIES)),
            "BOUNDARY(CONTEXTUAL_TABLE_READ) homes to DESCRIPTORS then BOUNDARIES "
                + "(pinned order)");

        SemanticOp foreignBoundary = boundaryWith(nextOpId(),
            BoundaryKind.VARIABLE_DECLARATION, INT, FailurePolicyId.TYPE_DESCRIPTOR, null);
        check(ContainerClaimingSeam.homeRows(foreignBoundary).isEmpty(),
            "a BOUNDARY of another kind has no home row in this seam (its producer records "
                + "its op-side outcomes)");

        SemanticOp loadOp = op(SemanticOpKind.BINDING_LOAD,
            new KindPayload.BindingLoadPayload(new BindingId(1), 0), nextValue(), STR,
            FailurePolicyId.NO_DEAL_FAILURE, null);
        check(ContainerClaimingSeam.homeRows(loadOp)
                .equals(List.of(SemanticCapability.BINDINGS)),
            "BINDING_LOAD homes to BINDINGS (the loop-binding load-resolution rule)");

        List<SemanticOp> foreignOps = List.of(
            op(SemanticOpKind.UNARY, new KindPayload.UnaryPayload(UnarySelector.BOOL_NOT),
                nextValue(), BOOL, FailurePolicyId.NO_DEAL_FAILURE, null),
            op(SemanticOpKind.BINARY,
                new KindPayload.BinaryPayload(BinarySelector.INT32_ADD, null, null),
                nextValue(), INT, FailurePolicyId.INT32_RESULT, null),
            op(SemanticOpKind.BRANCH,
                new KindPayload.BranchPayload(ControlSelector.IF, nextValue(), new BlockId(1),
                    null),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, null),
            op(SemanticOpKind.DISCARD, new KindPayload.DiscardPayload(nextValue()), null, null,
                FailurePolicyId.NO_DEAL_FAILURE, null));
        for (SemanticOp foreignOp : foreignOps) {
            check(ContainerClaimingSeam.homeRows(foreignOp).isEmpty(),
                foreignOp.kind() + " has no home row in this seam (another construct epic's "
                    + "production)");
        }
    }

    // =========================================================================
    // 2. The pinned activation gates (D9 items 1/4/5)
    // =========================================================================

    static void testActivationGates() {
        System.out.println("-- The pinned activation gates --");

        check(ContainerClaimingSeam.E3_WINDOW_ACTIVATION.equals(Set.of(
                SemanticCapability.SIGNED_INT32)),
            "E3's tail activation is exactly {SIGNED_INT32} (activated at E2's gate; no E3 "
                + "op homes to it — its CONST(Int)/BOUNDARY(int) specializations are E2's "
                + "evidence)");
        check(ContainerClaimingSeam.E3_GATE_ACTIVATION.equals(Set.of(
                SemanticCapability.SIGNED_INT32, SemanticCapability.FOUNDATION_VALUES)),
            "the E3-gate hand-off activates FOUNDATION_VALUES (post-tail, never an in-window "
                + "flip)");
        check(ContainerClaimingSeam.E4_GATE_ACTIVATION.equals(Set.of(
                SemanticCapability.SIGNED_INT32, SemanticCapability.FOUNDATION_VALUES,
                SemanticCapability.DESCRIPTORS, SemanticCapability.BOUNDARIES)),
            "the E4-gate hand-off activates DESCRIPTORS and BOUNDARIES");
        check(ContainerClaimingSeam.E5_GATE_ACTIVATION.equals(Set.of(
                SemanticCapability.SIGNED_INT32, SemanticCapability.FOUNDATION_VALUES,
                SemanticCapability.DESCRIPTORS, SemanticCapability.BOUNDARIES,
                SemanticCapability.CONTAINERS_AND_STRINGS,
                SemanticCapability.EVALUATION_ORDER)),
            "the E5-gate hand-off activates CONTAINERS_AND_STRINGS and EVALUATION_ORDER");
        check(ContainerClaimingSeam.E6_GATE_ACTIVATION.equals(Set.of(
                SemanticCapability.SIGNED_INT32, SemanticCapability.FOUNDATION_VALUES,
                SemanticCapability.DESCRIPTORS, SemanticCapability.BOUNDARIES,
                SemanticCapability.CONTAINERS_AND_STRINGS,
                SemanticCapability.EVALUATION_ORDER, SemanticCapability.BINDINGS)),
            "the E6-gate hand-off activates BINDINGS");
        check(ContainerClaimingSeam.E3_WINDOW_ACTIVATION.containsAll(
                ContainerClaimingSeam.E3_WINDOW_ACTIVATION)
                && ContainerClaimingSeam.E3_GATE_ACTIVATION.containsAll(
                    ContainerClaimingSeam.E3_WINDOW_ACTIVATION)
                && ContainerClaimingSeam.E4_GATE_ACTIVATION.containsAll(
                    ContainerClaimingSeam.E3_GATE_ACTIVATION)
                && ContainerClaimingSeam.E5_GATE_ACTIVATION.containsAll(
                    ContainerClaimingSeam.E4_GATE_ACTIVATION)
                && ContainerClaimingSeam.E6_GATE_ACTIVATION.containsAll(
                    ContainerClaimingSeam.E5_GATE_ACTIVATION),
            "the activation states grow monotonically across the gates (a row never "
                + "deactivates)");
    }

    // =========================================================================
    // 3. The item-4 op-side check: all four outcomes + the derivation guard
    // =========================================================================

    static void testSeamFourOutcomes() {
        System.out.println("-- The item-4 op-side check: the four outcomes --");

        // --- (a) Row inactive → the recorded staged hand-off; no E6005. ---
        {
            List<SemanticOp> ops = List.of(
                op(SemanticOpKind.CONST, new KindPayload.ConstPayload(
                        new ScalarValue.String("ab")),
                    nextValue(), STR, FailurePolicyId.NO_DEAL_FAILURE, null),
                op(SemanticOpKind.STRING_CONCAT,
                    new KindPayload.StringConcatPayload(List.of(nextValue())), nextValue(), STR,
                    FailurePolicyId.NO_DEAL_FAILURE, null),
                op(SemanticOpKind.ARRAY_NEW,
                    new KindPayload.ArrayNewPayload(INT, List.of(), List.of()), nextValue(),
                    new RuntimeDescriptor.Array(INT), FailurePolicyId.NO_DEAL_FAILURE, null),
                op(SemanticOpKind.TABLE_NEW, new KindPayload.TableNewPayload(List.of()),
                    nextValue(), TBL, FailurePolicyId.NO_DEAL_FAILURE, null),
                op(SemanticOpKind.ARRAY_LENGTH, new KindPayload.ArrayLengthPayload(nextValue()),
                    nextValue(), INT, FailurePolicyId.INT32_RESULT, null),
                op(SemanticOpKind.MEMBER_READ, new KindPayload.MemberReadPayload(nextValue(),
                        "k"),
                    nextValue(), BOOL, FailurePolicyId.NO_DEAL_FAILURE, null),
                op(SemanticOpKind.FOR_EACH,
                    new KindPayload.ForEachPayload(IterationMode.STRING_SCALARS, nextValue(),
                        new BindingId(1), 0, new BlockId(1)),
                    null, null, FailurePolicyId.TYPE_DESCRIPTOR, null),
                boundaryWith(nextOpId(), BoundaryKind.ARRAY_LITERAL_ELEMENT, INT,
                    FailurePolicyId.ARRAY_ELEMENT_DESCRIPTOR, null),
                boundaryWith(nextOpId(), BoundaryKind.CONTEXTUAL_TABLE_READ, BOOL,
                    FailurePolicyId.TYPE_DESCRIPTOR, null),
                op(SemanticOpKind.BINDING_LOAD,
                    new KindPayload.BindingLoadPayload(new BindingId(1), 0), nextValue(), STR,
                    FailurePolicyId.NO_DEAL_FAILURE, null));
            Set<SemanticCapability> derived = ContainerClaimingSeam.deriveClaims(ops,
                ContainerClaimingSeam.E3_WINDOW_ACTIVATION);
            check(derived.isEmpty(),
                "under E3's tail activation the derived claim set is empty (every home row "
                    + "inactive); got " + derived);
            ContainerClaimingSeam.SeamResult result = ContainerClaimingSeam.check(ops,
                ContainerClaimingSeam.E3_WINDOW_ACTIVATION, Set.of(), MODULE);
            check(result.failure() == null,
                "no E6005 fires for an inactive-home op (the staged hand-off is never a "
                    + "failure)");
            check(result.outcomes().size() == 12,
                "one recorded outcome per produced op and per home row: 7 single-home ops + "
                    + "2 boundary children × 2 rows = 12; got " + result.outcomes().size());
            check(countOutcomes(result.outcomes(),
                    ContainerClaimingSeam.OutcomeKind.STAGED_HAND_OFF) == 12,
                "every outcome is the recorded staged hand-off");
            check(result.outcomes().stream().allMatch(
                    outcome -> outcome.opKind() == SemanticOpKind.BOUNDARY
                        ? outcome.boundaryKind() != null : outcome.boundaryKind() == null),
                "every outcome records its op identity (boundary kind exactly for BOUNDARY "
                    + "ops)");
        }

        // --- (b) Row active but only partially evidenced → the per-unit claim
        //     deferral; no E6005. ---
        {
            List<SemanticOp> partial = List.of(
                op(SemanticOpKind.CONST, new KindPayload.ConstPayload(
                        new ScalarValue.String("ab")),
                    nextValue(), STR, FailurePolicyId.NO_DEAL_FAILURE, null),
                op(SemanticOpKind.STRING_CONCAT,
                    new KindPayload.StringConcatPayload(List.of(nextValue())), nextValue(), STR,
                    FailurePolicyId.NO_DEAL_FAILURE, null));
            Set<SemanticCapability> active = Set.of(SemanticCapability.FOUNDATION_VALUES);
            Set<SemanticCapability> derived = ContainerClaimingSeam.deriveClaims(partial, active);
            check(derived.isEmpty(),
                "a CONST/STRING_CONCAT unit without UNARY/BINARY does not fully evidence "
                    + "FOUNDATION_VALUES: the derived set is empty; got " + derived);
            ContainerClaimingSeam.SeamResult result = ContainerClaimingSeam.check(partial,
                active, Set.of(), MODULE);
            check(result.failure() == null,
                "no E6005 fires for the partial-evidence unit (the deferral is never a "
                    + "failure)");
            check(result.outcomes().size() == 2,
                "exactly two outcomes: one per op at its FOUNDATION_VALUES home row");
            check(result.outcomes().stream().allMatch(outcome ->
                    outcome.home() == SemanticCapability.FOUNDATION_VALUES
                        && outcome.outcome() == ContainerClaimingSeam.OutcomeKind.DEFERRED),
                "both outcomes are the per-unit claim deferral (the recorded, terminal claim "
                    + "outcome of the immutable unit)");
            // Deterministic terminal outcome: the same immutable unit checked
            // twice records the identical deferral — never retroactively
            // converted into a failure.
            ContainerClaimingSeam.SeamResult repeated = ContainerClaimingSeam.check(partial,
                active, Set.of(), MODULE);
            check(repeated.failure() == null
                    && repeated.outcomes().equals(result.outcomes())
                    && repeated.derivedClaims().equals(result.derivedClaims()),
                "the deferral is deterministic and terminal across repeated checks of the "
                    + "same immutable unit");
        }

        // --- (c) Row active and fully evidenced with the derived claim → the
        //     recorded claimed outcome passes with R-CAPABILITY green. ---
        {
            List<SemanticOp> full = List.of(
                op(SemanticOpKind.CONST, new KindPayload.ConstPayload(
                        new ScalarValue.String("ab")),
                    nextValue(), STR, FailurePolicyId.NO_DEAL_FAILURE, null),
                op(SemanticOpKind.UNARY, new KindPayload.UnaryPayload(UnarySelector.BOOL_NOT),
                    nextValue(), BOOL, FailurePolicyId.NO_DEAL_FAILURE, null),
                op(SemanticOpKind.BINARY,
                    new KindPayload.BinaryPayload(BinarySelector.INT32_ADD, null, null),
                    nextValue(), INT, FailurePolicyId.INT32_RESULT, null),
                op(SemanticOpKind.STRING_CONCAT,
                    new KindPayload.StringConcatPayload(List.of(nextValue())), nextValue(), STR,
                    FailurePolicyId.NO_DEAL_FAILURE, null));
            Set<SemanticCapability> active = Set.of(SemanticCapability.FOUNDATION_VALUES);
            Set<SemanticCapability> derived = ContainerClaimingSeam.deriveClaims(full, active);
            check(derived.equals(Set.of(SemanticCapability.FOUNDATION_VALUES)),
                "the {CONST, UNARY, BINARY, STRING_CONCAT} unit fully evidences "
                    + "FOUNDATION_VALUES and the derivation claims it; got " + derived);
            ContainerClaimingSeam.SeamResult result = ContainerClaimingSeam.check(full, active,
                derived, MODULE);
            check(result.failure() == null,
                "the fully evidencing unit carrying its derived claim passes the check");
            check(result.outcomes().size() == 2,
                "exactly the two epic-home ops record outcomes (UNARY/BINARY outcomes are "
                    + "E2's producer's); got " + result.outcomes());
            for (ContainerClaimingSeam.RecordedOutcome outcome : result.outcomes()) {
                check(outcome.home() == SemanticCapability.FOUNDATION_VALUES
                        && outcome.outcome() == ContainerClaimingSeam.OutcomeKind.CLAIMED,
                    outcome.opKind() + " records the claimed outcome");
            }
            LoweredModuleUnit unit = unit(derived, Map.of(), full);
            assertPass(SemanticIrValidator.validate(unit, facts()),
                "the fully evidencing unit carrying its derived claim passes R-CAPABILITY "
                    + "(claimed → evidenced, mechanically)");
        }

        // --- (d) Row active and fully evidenced but the claim omitted → the
        //     single E6005 firing condition with the exact detail. ---
        {
            List<SemanticOp> full = List.of(
                op(SemanticOpKind.CONST, new KindPayload.ConstPayload(
                        new ScalarValue.String("ab")),
                    nextValue(), STR, FailurePolicyId.NO_DEAL_FAILURE, null),
                op(SemanticOpKind.UNARY, new KindPayload.UnaryPayload(UnarySelector.BOOL_NOT),
                    nextValue(), BOOL, FailurePolicyId.NO_DEAL_FAILURE, null),
                op(SemanticOpKind.BINARY,
                    new KindPayload.BinaryPayload(BinarySelector.INT32_ADD, null, null),
                    nextValue(), INT, FailurePolicyId.INT32_RESULT, null),
                op(SemanticOpKind.STRING_CONCAT,
                    new KindPayload.StringConcatPayload(List.of(nextValue())), nextValue(), STR,
                    FailurePolicyId.NO_DEAL_FAILURE, null),
                op(SemanticOpKind.BINDING_LOAD,
                    new KindPayload.BindingLoadPayload(new BindingId(1), 0), nextValue(), STR,
                    FailurePolicyId.NO_DEAL_FAILURE, null));
            Set<SemanticCapability> active = Set.of(SemanticCapability.FOUNDATION_VALUES);
            ContainerClaimingSeam.SeamResult result = ContainerClaimingSeam.check(full, active,
                Set.of(), MODULE);
            check(result.failure() != null,
                "a fully evidencing unit that omits the claim fires the E6005 "
                    + "OPERATION_OUTSIDE_CLAIMED_CAPABILITY condition");
            if (result.failure() != null) {
                LoweringFailureDetail detail = result.failure();
                check("main".equals(detail.module()),
                    "the firing detail's module is the unit's module");
                check(detail.capability() == SemanticCapability.FOUNDATION_VALUES,
                    "the firing detail's capability is the home row FOUNDATION_VALUES");
                check(ContainerClaimingSeam.OPERATION_OUTSIDE_CLAIMED_CAPABILITY
                        .equals(detail.validatorRule()),
                    "the firing detail's validatorRule is OPERATION_OUTSIDE_CLAIMED_CAPABILITY");
                check(detail.semanticProfile() == SemanticProfile.DEAL_V1_2_INT32,
                    "the firing detail's semanticProfile is DEAL_V1_2_INT32");
                check(LoweredModuleUnit.FORMAT_VERSION.equals(detail.irVersion()),
                    "the firing detail's irVersion is deal.semantic-ir/1");
                check(("ContainerClaimingSeam "
                        + ContainerClaimingSeam.OPERATION_OUTSIDE_CLAIMED_CAPABILITY
                        + " (active home row FOUNDATION_VALUES fully evidenced by the unit "
                        + "and left unclaimed)").equals(detail.origin()),
                    "the firing detail's origin is the pinned component description");
                CompilerDiagnostic diagnostic = FailureContractRegistry.e6005(detail);
                check("E6005".equals(diagnostic.code())
                        && diagnostic.diagnosticCode() == DiagnosticCode.E6005
                        && "error".equals(diagnostic.severity()),
                    "the firing detail converts to an error-severity E6005 through the "
                        + "registry");
                check(diagnostic.message().contains(
                        ContainerClaimingSeam.OPERATION_OUTSIDE_CLAIMED_CAPABILITY),
                    "the E6005 message carries OPERATION_OUTSIDE_CLAIMED_CAPABILITY");
            }
            check(result.outcomes().size() == 1,
                "the firing positions' record is the failure detail; the pass continues and "
                    + "records the remaining positions — BINDING_LOAD's inactive BINDINGS row "
                    + "is the one staged hand-off; got " + result.outcomes());
            if (!result.outcomes().isEmpty()) {
                ContainerClaimingSeam.RecordedOutcome remaining = result.outcomes().get(0);
                check(remaining.opKind() == SemanticOpKind.BINDING_LOAD
                        && remaining.home() == SemanticCapability.BINDINGS
                        && remaining.outcome() == ContainerClaimingSeam.OutcomeKind.STAGED_HAND_OFF,
                    "the remaining recorded outcome is BINDING_LOAD's staged hand-off under "
                        + "the inactive BINDINGS row");
            }
        }

        // --- (e) A claim without full evidence is impossible by the derivation;
        //     asserting one fails closed. ---
        {
            List<SemanticOp> partial = List.of(
                op(SemanticOpKind.CONST, new KindPayload.ConstPayload(
                        new ScalarValue.String("ab")),
                    nextValue(), STR, FailurePolicyId.NO_DEAL_FAILURE, null),
                op(SemanticOpKind.STRING_CONCAT,
                    new KindPayload.StringConcatPayload(List.of(nextValue())), nextValue(), STR,
                    FailurePolicyId.NO_DEAL_FAILURE, null));
            Set<SemanticCapability> derived = ContainerClaimingSeam.deriveClaims(partial,
                Set.of(SemanticCapability.FOUNDATION_VALUES));
            check(derived.isEmpty() && !derived.contains(SemanticCapability.FOUNDATION_VALUES),
                "the derivation never claims the partially evidenced FOUNDATION_VALUES row");
            try {
                ContainerClaimingSeam.check(partial,
                    Set.of(SemanticCapability.FOUNDATION_VALUES),
                    Set.of(SemanticCapability.FOUNDATION_VALUES), MODULE);
                fail("a claimed row the derivation cannot derive must fail closed");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("impossible by the derivation"),
                    "claiming an under-evidenced active row fails closed: "
                        + expected.getMessage());
            }
            List<SemanticOp> full = List.of(
                op(SemanticOpKind.CONST, new KindPayload.ConstPayload(
                        new ScalarValue.String("ab")),
                    nextValue(), STR, FailurePolicyId.NO_DEAL_FAILURE, null),
                op(SemanticOpKind.UNARY, new KindPayload.UnaryPayload(UnarySelector.BOOL_NOT),
                    nextValue(), BOOL, FailurePolicyId.NO_DEAL_FAILURE, null),
                op(SemanticOpKind.BINARY,
                    new KindPayload.BinaryPayload(BinarySelector.INT32_ADD, null, null),
                    nextValue(), INT, FailurePolicyId.INT32_RESULT, null),
                op(SemanticOpKind.STRING_CONCAT,
                    new KindPayload.StringConcatPayload(List.of(nextValue())), nextValue(), STR,
                    FailurePolicyId.NO_DEAL_FAILURE, null));
            try {
                ContainerClaimingSeam.check(full, Set.of(),
                    Set.of(SemanticCapability.FOUNDATION_VALUES), MODULE);
                fail("a claimed inactive row must fail closed");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("impossible by the derivation"),
                    "claiming a row before its activation gate fails closed: "
                        + expected.getMessage());
            }
        }
    }

    // =========================================================================
    // 4. The pinned gate claim states (D9 items 4/5)
    // =========================================================================

    static void testGateClaimStates() {
        System.out.println("-- The pinned gate claim states --");

        // --- The E5-gate full corpus expectation (D7/D9 item 5(c)): the
        //     corpus's derived op set derives exactly {DESCRIPTORS, BOUNDARIES}
        //     with FOUNDATION_VALUES/CONTAINERS_AND_STRINGS/EVALUATION_ORDER
        //     deferred per unit. ---
        {
            OpId arrayNewId = nextOpId();
            OpId memberReadId = nextOpId();
            OpId elementChildId = nextOpId();
            OpId contextualChildId = nextOpId();
            List<SemanticOp> corpusOps = List.of(
                op(SemanticOpKind.CONST, new KindPayload.ConstPayload(
                        new ScalarValue.String("ab")),
                    nextValue(), STR, FailurePolicyId.NO_DEAL_FAILURE, null),
                op(SemanticOpKind.STRING_CONCAT,
                    new KindPayload.StringConcatPayload(List.of(nextValue(), nextValue())),
                    nextValue(), STR, FailurePolicyId.NO_DEAL_FAILURE, null),
                opWith(arrayNewId, SemanticOpKind.ARRAY_NEW,
                    new KindPayload.ArrayNewPayload(INT, List.of(nextValue()),
                        List.of(elementChildId)),
                    nextValue(), new RuntimeDescriptor.Array(INT),
                    FailurePolicyId.NO_DEAL_FAILURE, null),
                boundaryWith(elementChildId, BoundaryKind.ARRAY_LITERAL_ELEMENT, INT,
                    FailurePolicyId.ARRAY_ELEMENT_DESCRIPTOR, arrayNewId),
                op(SemanticOpKind.TABLE_NEW,
                    new KindPayload.TableNewPayload(List.of(
                        new KindPayload.TableEntry("k", nextValue()))),
                    nextValue(), TBL, FailurePolicyId.NO_DEAL_FAILURE, null),
                op(SemanticOpKind.ARRAY_LENGTH, new KindPayload.ArrayLengthPayload(nextValue()),
                    nextValue(), INT, FailurePolicyId.INT32_RESULT, null),
                opWith(memberReadId, SemanticOpKind.MEMBER_READ,
                    new KindPayload.MemberReadPayload(nextValue(), "k"), nextValue(), BOOL,
                    FailurePolicyId.NO_DEAL_FAILURE, null),
                boundaryWith(contextualChildId, BoundaryKind.CONTEXTUAL_TABLE_READ, BOOL,
                    FailurePolicyId.TYPE_DESCRIPTOR, memberReadId),
                op(SemanticOpKind.FOR_EACH,
                    new KindPayload.ForEachPayload(IterationMode.STRING_SCALARS, nextValue(),
                        new BindingId(1), 0, new BlockId(1)),
                    null, null, FailurePolicyId.TYPE_DESCRIPTOR, null),
                op(SemanticOpKind.BRANCH,
                    new KindPayload.BranchPayload(ControlSelector.IF, nextValue(),
                        new BlockId(2), null),
                    null, null, FailurePolicyId.NO_DEAL_FAILURE, null),
                op(SemanticOpKind.DISCARD, new KindPayload.DiscardPayload(nextValue()), null,
                    null, FailurePolicyId.NO_DEAL_FAILURE, null));
            check(corpusOps.stream().noneMatch(op -> op.kind() == SemanticOpKind.LOOP),
                "the pinned corpus's derived op set carries no LOOP op (no while/for "
                    + "statement is in the corpus)");

            Set<SemanticCapability> derived = ContainerClaimingSeam.deriveClaims(corpusOps,
                ContainerClaimingSeam.E5_GATE_ACTIVATION);
            check(derived.equals(Set.of(SemanticCapability.DESCRIPTORS,
                    SemanticCapability.BOUNDARIES)),
                "the E5-gate corpus derives exactly {DESCRIPTORS, BOUNDARIES}; got " + derived);
            check(!derived.contains(SemanticCapability.FOUNDATION_VALUES)
                    && !derived.contains(SemanticCapability.CONTAINERS_AND_STRINGS)
                    && !derived.contains(SemanticCapability.EVALUATION_ORDER),
                "FOUNDATION_VALUES (CONST/STRING_CONCAT without UNARY/BINARY), "
                    + "CONTAINERS_AND_STRINGS (the six ops without INDEX_*/OPTIONAL_READ/"
                    + "HAS_FIELD), and EVALUATION_ORDER (BRANCH/DISCARD without LOOP) are not "
                    + "claimed");

            ContainerClaimingSeam.SeamResult result = ContainerClaimingSeam.check(corpusOps,
                ContainerClaimingSeam.E5_GATE_ACTIVATION, derived, MODULE);
            check(result.failure() == null,
                "the corpus run is green with exactly its derived claim state — E6005 never "
                    + "fires for the corpus");
            check(result.outcomes().size() == 11,
                "11 recorded outcomes: 2 FOUNDATION_VALUES deferrals + 5 "
                    + "CONTAINERS_AND_STRINGS deferrals + 2 boundary children × 2 claimed "
                    + "rows; got " + result.outcomes().size());
            for (SemanticOp op : corpusOps) {
                if (op.kind() == SemanticOpKind.BRANCH
                        || op.kind() == SemanticOpKind.DISCARD) {
                    check(outcomeOf(result.outcomes(), op.opId(),
                            SemanticCapability.EVALUATION_ORDER) == null
                            && result.outcomes().stream().noneMatch(outcome ->
                                outcome.opId().equals(op.opId())),
                        op.kind() + " records no op-side outcome in this seam — its outcomes "
                            + "are recorded by E5's producer under the shared mechanism");
                }
            }
            for (SemanticOpKind deferredKind : List.of(SemanticOpKind.CONST,
                    SemanticOpKind.STRING_CONCAT)) {
                for (ContainerClaimingSeam.RecordedOutcome outcome : result.outcomes()) {
                    if (outcome.opKind() == deferredKind) {
                        check(outcome.home() == SemanticCapability.FOUNDATION_VALUES
                                && outcome.outcome()
                                    == ContainerClaimingSeam.OutcomeKind.DEFERRED,
                            deferredKind + " records the FOUNDATION_VALUES per-unit deferral");
                    }
                }
            }
            for (SemanticOpKind containerKind : List.of(SemanticOpKind.ARRAY_NEW,
                    SemanticOpKind.TABLE_NEW, SemanticOpKind.ARRAY_LENGTH,
                    SemanticOpKind.MEMBER_READ, SemanticOpKind.FOR_EACH)) {
                for (ContainerClaimingSeam.RecordedOutcome outcome : result.outcomes()) {
                    if (outcome.opKind() == containerKind) {
                        check(outcome.home() == SemanticCapability.CONTAINERS_AND_STRINGS
                                && outcome.outcome()
                                    == ContainerClaimingSeam.OutcomeKind.DEFERRED,
                            containerKind + " records the CONTAINERS_AND_STRINGS per-unit "
                                + "deferral");
                    }
                }
            }
            for (OpId boundaryId : List.of(elementChildId, contextualChildId)) {
                check(outcomeOf(result.outcomes(), boundaryId, SemanticCapability.DESCRIPTORS)
                        != null
                        && outcomeOf(result.outcomes(), boundaryId,
                            SemanticCapability.DESCRIPTORS).outcome()
                            == ContainerClaimingSeam.OutcomeKind.CLAIMED,
                    "boundary " + boundaryId + " records the DESCRIPTORS claimed outcome");
                check(outcomeOf(result.outcomes(), boundaryId, SemanticCapability.BOUNDARIES)
                        != null
                        && outcomeOf(result.outcomes(), boundaryId,
                            SemanticCapability.BOUNDARIES).outcome()
                            == ContainerClaimingSeam.OutcomeKind.CLAIMED,
                    "boundary " + boundaryId + " records the BOUNDARIES claimed outcome");
            }

            // The corpus's derived claim state passes R-CAPABILITY (and
            // R-COVERAGE) with no E6005 on the closed validator.
            LoweredModuleUnit unit = unit(derived, Map.of(), corpusOps);
            assertPass(SemanticIrValidator.validate(unit, facts()),
                "the E5-gate corpus unit with claims {DESCRIPTORS, BOUNDARIES} passes "
                    + "R-CAPABILITY and R-COVERAGE with no E6005");
        }

        // --- The E6-gate BINDINGS deferral (D9 item 5(d)): a BINDING_LOAD-only
        //     unit defers per unit. ---
        {
            List<SemanticOp> loadOnly = List.of(
                op(SemanticOpKind.BINDING_LOAD,
                    new KindPayload.BindingLoadPayload(new BindingId(1), 0), nextValue(), STR,
                    FailurePolicyId.NO_DEAL_FAILURE, null));
            Set<SemanticCapability> derived = ContainerClaimingSeam.deriveClaims(loadOnly,
                ContainerClaimingSeam.E6_GATE_ACTIVATION);
            check(derived.isEmpty() && !derived.contains(SemanticCapability.BINDINGS),
                "a BINDING_LOAD-only unit does not fully evidence BINDINGS (the row needs all "
                    + "six families): derived " + derived);
            ContainerClaimingSeam.SeamResult result = ContainerClaimingSeam.check(loadOnly,
                ContainerClaimingSeam.E6_GATE_ACTIVATION, Set.of(), MODULE);
            check(result.failure() == null && result.outcomes().size() == 1,
                "the BINDING_LOAD-only unit records exactly one outcome and no E6005");
            if (result.outcomes().size() == 1) {
                ContainerClaimingSeam.RecordedOutcome outcome = result.outcomes().get(0);
                check(outcome.home() == SemanticCapability.BINDINGS
                        && outcome.outcome() == ContainerClaimingSeam.OutcomeKind.DEFERRED,
                    "the BINDING_LOAD records the BINDINGS per-unit deferral at E6's gate");
            }
        }
    }

    // =========================================================================
    // 5. Validator acceptance: positive units for every D1 shape
    // =========================================================================

    private static void assertSeamOnUnit(LoweredModuleUnit unit, String what) {
        check(unit.requiredCapabilities().isEmpty(),
            what + " unit claims ∅ (derived through the seam in E3's tail window)");
        ContainerClaimingSeam.SeamResult seam = ContainerClaimingSeam.check(unit.ops(),
            ContainerClaimingSeam.E3_WINDOW_ACTIVATION, unit.requiredCapabilities(), MODULE);
        check(seam.failure() == null, what + " seam check fires no E6005");
        check(seam.derivedClaims().isEmpty(), what + " seam derived claims are ∅");
        if (!seam.outcomes().isEmpty()) {
            check(seam.outcomes().stream().allMatch(outcome -> outcome.outcome()
                    == ContainerClaimingSeam.OutcomeKind.STAGED_HAND_OFF),
                what + " seam records every op as a staged hand-off");
        }
    }

    static void testValidatorAcceptance() {
        System.out.println("-- Validator acceptance: positive units for every D1 shape --");

        // --- CONST (scalar literal). ---
        {
            CheckedSlice slice = checkSlice("""
                function f(): null {
                  let s = "abc"
                  return null
                }
                """);
            if (slice != null) {
                LiteralExpr literal = first(slice.program(), LiteralExpr.class);
                check(literal != null, "the scalar-literal slice carries its literal");
                if (literal != null) {
                    SemanticLowerer.ModuleLowerer lowerer = lowerer(slice.checks());
                    lowerer.lowerExpression(literal);
                    Map<ConstructKind, List<SemanticOpKind>> coverage = Map.of(
                        ConstructKind.SCALAR_LITERAL,
                        ConstructKind.SCALAR_LITERAL.mappedOpKinds());
                    LoweredModuleUnit unit = lowerer.buildUnit(coverage, List.of(),
                        INTERFACE_HASH, REGISTRY_HASH);
                    assertPass(SemanticIrValidator.validate(unit, facts()),
                        "the CONST unit passes the closed validator");
                    assertSeamOnUnit(unit, "CONST");
                }
            }
        }

        // --- BINDING_LOAD + FOR_EACH (the nested-for-of corpus). ---
        {
            CheckedSlice slice = checkSlice("""
                for (let x: string of "ab") {
                  for (let y: string of x) {}
                }
                """);
            if (slice != null) {
                ForOfStatement outer = first(slice.program(), ForOfStatement.class);
                check(outer != null, "the nested-for-of slice carries the outer for-of");
                if (outer != null) {
                    SemanticLowerer.ModuleLowerer lowerer = lowerer(slice.checks());
                    lowerer.lowerForOfStatement(outer);
                    Map<ConstructKind, List<SemanticOpKind>> coverage = Map.of(
                        ConstructKind.SCALAR_LITERAL,
                        ConstructKind.SCALAR_LITERAL.mappedOpKinds(),
                        ConstructKind.IDENTIFIER, ConstructKind.IDENTIFIER.mappedOpKinds(),
                        ConstructKind.IF_WHILE_FOR_FOR_OF,
                        ConstructKind.IF_WHILE_FOR_FOR_OF.mappedOpKinds());
                    LoweredModuleUnit unit = lowerer.buildUnit(coverage, List.of(),
                        INTERFACE_HASH, REGISTRY_HASH);
                    assertPass(SemanticIrValidator.validate(unit, facts()),
                        "the nested-for-of unit (BINDING_LOAD + FOR_EACH) passes the closed "
                            + "validator");
                    assertSeamOnUnit(unit, "nested-for-of");
                }
            }
        }

        // --- ARRAY_NEW with its ARRAY_LITERAL_ELEMENT children. ---
        {
            CheckedSlice slice = checkSlice("""
                function f(): null {
                  let xs: int[] = [1, 2]
                  return null
                }
                """);
            if (slice != null) {
                ArrayLiteralExpr literal = first(slice.program(), ArrayLiteralExpr.class);
                check(literal != null, "the array slice carries its array literal");
                if (literal != null) {
                    SemanticLowerer.ModuleLowerer lowerer = lowerer(slice.checks());
                    lowerer.lowerExpression(literal);
                    Map<ConstructKind, List<SemanticOpKind>> coverage = Map.of(
                        ConstructKind.SCALAR_LITERAL,
                        ConstructKind.SCALAR_LITERAL.mappedOpKinds(),
                        ConstructKind.ARRAY_OBJECT_LITERAL,
                        ConstructKind.ARRAY_OBJECT_LITERAL.mappedOpKinds());
                    LoweredModuleUnit unit = lowerer.buildUnit(coverage, List.of(),
                        INTERFACE_HASH, REGISTRY_HASH);
                    assertPass(SemanticIrValidator.validate(unit, facts()),
                        "the ARRAY_NEW unit with its ARRAY_LITERAL_ELEMENT children "
                            + "(ARRAY_ELEMENT_DESCRIPTOR) passes the closed validator");
                    assertSeamOnUnit(unit, "ARRAY_NEW");
                    List<SemanticOp> boundaries = new ArrayList<>();
                    for (SemanticOp op : unit.ops()) {
                        if (op.kind() == SemanticOpKind.BOUNDARY) {
                            boundaries.add(op);
                        }
                    }
                    check(boundaries.size() == 2
                            && boundaries.stream().allMatch(op ->
                                ((KindPayload.BoundaryPayload) op.payload()).kind()
                                    == BoundaryKind.ARRAY_LITERAL_ELEMENT
                                && op.failurePolicy()
                                    == FailurePolicyId.ARRAY_ELEMENT_DESCRIPTOR),
                        "the accepted unit carries exactly the two ARRAY_LITERAL_ELEMENT "
                            + "children with the pinned policy");
                }
            }
        }

        // --- TABLE_NEW (duplicate keys keep source order in the payload). ---
        {
            CheckedSlice slice = checkSlice("""
                function f(): null {
                  let t = {x: 1, y: "k", x: 2}
                  return null
                }
                """);
            if (slice != null) {
                ObjectLiteralExpr literal = first(slice.program(), ObjectLiteralExpr.class);
                check(literal != null, "the table slice carries its object literal");
                if (literal != null) {
                    SemanticLowerer.ModuleLowerer lowerer = lowerer(slice.checks());
                    lowerer.lowerExpression(literal);
                    Map<ConstructKind, List<SemanticOpKind>> coverage = Map.of(
                        ConstructKind.SCALAR_LITERAL,
                        ConstructKind.SCALAR_LITERAL.mappedOpKinds(),
                        ConstructKind.ARRAY_OBJECT_LITERAL,
                        ConstructKind.ARRAY_OBJECT_LITERAL.mappedOpKinds());
                    LoweredModuleUnit unit = lowerer.buildUnit(coverage, List.of(),
                        INTERFACE_HASH, REGISTRY_HASH);
                    assertPass(SemanticIrValidator.validate(unit, facts()),
                        "the TABLE_NEW unit passes the closed validator");
                    assertSeamOnUnit(unit, "TABLE_NEW");
                }
            }
        }

        // --- ARRAY_LENGTH (receiver is one prior step; INT32_RESULT). ---
        {
            CheckedSlice slice = checkSlice("""
                function f(xs: int[]): null {
                  let n = xs.length
                  return null
                }
                """);
            if (slice != null) {
                MemberAccessExpr access = first(slice.program(), MemberAccessExpr.class);
                check(access != null && "length".equals(access.field()),
                    "the length slice carries xs.length");
                if (access != null) {
                    SemanticLowerer.ModuleLowerer lowerer = lowerer(slice.checks());
                    lowerer.openForEachScope("xs");
                    lowerer.lowerExpression(access);
                    lowerer.closeForEachScope();
                    Map<ConstructKind, List<SemanticOpKind>> coverage = Map.of(
                        ConstructKind.IDENTIFIER, ConstructKind.IDENTIFIER.mappedOpKinds(),
                        ConstructKind.MEMBER_ACCESS,
                        ConstructKind.MEMBER_ACCESS.mappedOpKinds());
                    LoweredModuleUnit unit = lowerer.buildUnit(coverage, List.of(),
                        INTERFACE_HASH, REGISTRY_HASH);
                    assertPass(SemanticIrValidator.validate(unit, facts()),
                        "the ARRAY_LENGTH unit passes the closed validator");
                    assertSeamOnUnit(unit, "ARRAY_LENGTH");
                }
            }
        }

        // --- MEMBER_READ with its CONTEXTUAL_TABLE_READ child. ---
        {
            CheckedSlice slice = checkSlice("""
                function f(t: table): null {
                  if (t.k) {}
                  return null
                }
                """);
            if (slice != null) {
                MemberAccessExpr access = first(slice.program(), MemberAccessExpr.class);
                check(access != null && "k".equals(access.field()),
                    "the member-read slice carries t.k in the if-condition context");
                if (access != null) {
                    SemanticLowerer.ModuleLowerer lowerer = lowerer(slice.checks());
                    lowerer.openForEachScope("t");
                    lowerer.lowerExpression(access);
                    lowerer.closeForEachScope();
                    Map<ConstructKind, List<SemanticOpKind>> coverage = Map.of(
                        ConstructKind.IDENTIFIER, ConstructKind.IDENTIFIER.mappedOpKinds(),
                        ConstructKind.MEMBER_ACCESS,
                        ConstructKind.MEMBER_ACCESS.mappedOpKinds());
                    LoweredModuleUnit unit = lowerer.buildUnit(coverage, List.of(),
                        INTERFACE_HASH, REGISTRY_HASH);
                    assertPass(SemanticIrValidator.validate(unit, facts()),
                        "the MEMBER_READ unit with its CONTEXTUAL_TABLE_READ child "
                            + "(TYPE_DESCRIPTOR) passes the closed validator");
                    assertSeamOnUnit(unit, "MEMBER_READ");
                    List<SemanticOp> boundaries = new ArrayList<>();
                    for (SemanticOp op : unit.ops()) {
                        if (op.kind() == SemanticOpKind.BOUNDARY) {
                            boundaries.add(op);
                        }
                    }
                    check(boundaries.size() == 1
                            && ((KindPayload.BoundaryPayload) boundaries.get(0).payload())
                                .kind() == BoundaryKind.CONTEXTUAL_TABLE_READ
                            && boundaries.get(0).failurePolicy()
                                == FailurePolicyId.TYPE_DESCRIPTOR,
                        "the accepted unit carries exactly the single CONTEXTUAL_TABLE_READ "
                            + "child with the descriptor-kind-rule policy");
                }
            }
        }

        // --- STRING_CONCAT (string +; never BINARY). ---
        {
            CheckedSlice slice = checkSlice("""
                function f(a: string, b: string): null {
                  let s = a + b
                  return null
                }
                """);
            if (slice != null) {
                BinaryExpr binary = first(slice.program(), BinaryExpr.class);
                check(binary != null, "the string-plus slice carries its binary expression");
                if (binary != null) {
                    SemanticLowerer.ModuleLowerer lowerer = lowerer(slice.checks());
                    lowerer.openForEachScope("a");
                    lowerer.openForEachScope("b");
                    lowerer.lowerExpression(binary);
                    lowerer.closeForEachScope();
                    lowerer.closeForEachScope();
                    Map<ConstructKind, List<SemanticOpKind>> coverage = Map.of(
                        ConstructKind.IDENTIFIER, ConstructKind.IDENTIFIER.mappedOpKinds(),
                        ConstructKind.STRING_CONCAT_TEMPLATE,
                        ConstructKind.STRING_CONCAT_TEMPLATE.mappedOpKinds());
                    LoweredModuleUnit unit = lowerer.buildUnit(coverage, List.of(),
                        INTERFACE_HASH, REGISTRY_HASH);
                    assertPass(SemanticIrValidator.validate(unit, facts()),
                        "the string-+ STRING_CONCAT unit passes the closed validator");
                    assertSeamOnUnit(unit, "string +");
                    check(unit.ops().stream().noneMatch(
                            op -> op.kind() == SemanticOpKind.BINARY),
                        "string + never lowers to BINARY");
                }
            }
        }

        // --- STRING_CONCAT (template with fragment CONST steps). ---
        {
            CheckedSlice slice = checkSlice("""
                function f(a: string): null {
                  let s = `x${a}y`
                  return null
                }
                """);
            if (slice != null) {
                TemplateLiteralExpr template = first(slice.program(), TemplateLiteralExpr.class);
                check(template != null, "the template slice carries its template literal");
                if (template != null) {
                    SemanticLowerer.ModuleLowerer lowerer = lowerer(slice.checks());
                    lowerer.openForEachScope("a");
                    lowerer.lowerExpression(template);
                    lowerer.closeForEachScope();
                    Map<ConstructKind, List<SemanticOpKind>> coverage = Map.of(
                        ConstructKind.SCALAR_LITERAL,
                        ConstructKind.SCALAR_LITERAL.mappedOpKinds(),
                        ConstructKind.IDENTIFIER, ConstructKind.IDENTIFIER.mappedOpKinds(),
                        ConstructKind.STRING_CONCAT_TEMPLATE,
                        ConstructKind.STRING_CONCAT_TEMPLATE.mappedOpKinds());
                    LoweredModuleUnit unit = lowerer.buildUnit(coverage, List.of(),
                        INTERFACE_HASH, REGISTRY_HASH);
                    assertPass(SemanticIrValidator.validate(unit, facts()),
                        "the template STRING_CONCAT unit with its fragment CONST steps passes "
                            + "the closed validator");
                    assertSeamOnUnit(unit, "template");
                }
            }
        }
    }

    // =========================================================================
    // 6. Validator negatives: private step, uncovered kind, misplaced boundary,
    //    wrong boundary policy, wrong boundary count
    // =========================================================================

    static void testValidatorNegatives() {
        System.out.println("-- Validator negatives --");

        // --- R-PRIVATE-STEP: an out-of-set op-kind string in a real lowered
        //     unit's text. ---
        {
            CheckedSlice slice = checkSlice("""
                function f(): null {
                  let s = "abc"
                  return null
                }
                """);
            if (slice != null) {
                LiteralExpr literal = first(slice.program(), LiteralExpr.class);
                if (literal != null) {
                    SemanticLowerer.ModuleLowerer lowerer = lowerer(slice.checks());
                    lowerer.lowerExpression(literal);
                    // An empty coverage map keeps R-COVERAGE vacuous so the
                    // injected private step is the unit's first defect (the
                    // closed rule order puts R-PRIVATE-STEP after R-COVERAGE).
                    LoweredModuleUnit unit = lowerer.buildUnit(Map.of(), List.of(),
                        INTERFACE_HASH, REGISTRY_HASH);
                    assertPass(SemanticIrValidator.validate(unit, facts()),
                        "the private-step injection base passes validation");
                    String text = substituteFirstOpKind(
                        SemanticIrValidator.toUnitText(unit), "PRIVATE_STEP_X");
                    assertE6005(SemanticIrValidator.validateText(text, facts()),
                        "R-PRIVATE-STEP", "PRIVATE_STEP_X");
                }
            }
        }

        // --- R-COVERAGE: a recorded row without a produced op of its mapped
        //     kind (an uncovered kind is a hard failure, never a skip). ---
        {
            LoweredModuleUnit unit = unit(Set.of(),
                Map.of(ConstructKind.CALL, ConstructKind.CALL.mappedOpKinds()),
                List.of(op(SemanticOpKind.CONST,
                    new KindPayload.ConstPayload(new ScalarValue.String("ab")), nextValue(),
                    STR, FailurePolicyId.NO_DEAL_FAILURE, null)));
            assertE6005(SemanticIrValidator.validate(unit, facts()),
                "R-COVERAGE", "CALL");
        }

        // --- Misplaced element boundary: ARRAY_LITERAL_ELEMENT outside an
        //     ARRAY_NEW. ---
        {
            LoweredModuleUnit unit = unit(Set.of(), Map.of(),
                List.of(boundaryWith(nextOpId(), BoundaryKind.ARRAY_LITERAL_ELEMENT, INT,
                    FailurePolicyId.ARRAY_ELEMENT_DESCRIPTOR, null)));
            assertE6005(SemanticIrValidator.validate(unit, facts()),
                "R-BOUNDARY-TRIPLE", "ARRAY_LITERAL_ELEMENT boundary outside an ARRAY_NEW");
        }

        // --- Wrong policy for the kind: ARRAY_LITERAL_ELEMENT without
        //     ARRAY_ELEMENT_DESCRIPTOR, and CONTEXTUAL_TABLE_READ violating the
        //     descriptor-kind rule. ---
        {
            LoweredModuleUnit unit = unit(Set.of(), Map.of(),
                List.of(boundaryWith(nextOpId(), BoundaryKind.ARRAY_LITERAL_ELEMENT, INT,
                    FailurePolicyId.TYPE_DESCRIPTOR, null)));
            assertE6005(SemanticIrValidator.validate(unit, facts()),
                "R-BOUNDARY-TRIPLE", "must carry ARRAY_ELEMENT_DESCRIPTOR");
        }
        {
            LoweredModuleUnit unit = unit(Set.of(), Map.of(),
                List.of(boundaryWith(nextOpId(), BoundaryKind.CONTEXTUAL_TABLE_READ, INT,
                    FailurePolicyId.ARRAY_ELEMENT_DESCRIPTOR, null)));
            assertE6005(SemanticIrValidator.validate(unit, facts()),
                "R-BOUNDARY-TRIPLE", "CONTEXTUAL_TABLE_READ", "descriptor-kind rule");
        }

        // --- Wrong boundary count: ARRAY_NEW with two values and one element
        //     boundary id. ---
        {
            OpId arrayNewId = nextOpId();
            OpId childId = nextOpId();
            List<SemanticOp> ops = List.of(
                opWith(arrayNewId, SemanticOpKind.ARRAY_NEW,
                    new KindPayload.ArrayNewPayload(INT, List.of(nextValue(), nextValue()),
                        List.of(childId)),
                    nextValue(), new RuntimeDescriptor.Array(INT),
                    FailurePolicyId.NO_DEAL_FAILURE, null),
                boundaryWith(childId, BoundaryKind.ARRAY_LITERAL_ELEMENT, INT,
                    FailurePolicyId.ARRAY_ELEMENT_DESCRIPTOR, arrayNewId));
            LoweredModuleUnit unit = unit(Set.of(), Map.of(), ops);
            assertE6005(SemanticIrValidator.validate(unit, facts()),
                "R-BOUNDARY-TRIPLE", "must match the element count");
        }
    }

    // =========================================================================
    // 7. Determinism: the fixed corpus lowered twice is byte-identical
    // =========================================================================

    static void testDeterminism() {
        System.out.println("-- Determinism: byte-identical repeated lowerings --");

        CheckedSlice slice = checkSlice("""
            for (let x: string of "ab" + `c${"d"}e`) {
              for (let y: string of x) {}
            }
            """);
        if (slice == null) {
            return;
        }
        Map<ConstructKind, List<SemanticOpKind>> coverage = Map.of(
            ConstructKind.SCALAR_LITERAL, ConstructKind.SCALAR_LITERAL.mappedOpKinds(),
            ConstructKind.IDENTIFIER, ConstructKind.IDENTIFIER.mappedOpKinds(),
            ConstructKind.STRING_CONCAT_TEMPLATE,
            ConstructKind.STRING_CONCAT_TEMPLATE.mappedOpKinds(),
            ConstructKind.IF_WHILE_FOR_FOR_OF,
            ConstructKind.IF_WHILE_FOR_FOR_OF.mappedOpKinds());

        SemanticLowerer.LoweringResult first = SemanticLowerer.lowerModule(moduleOf(slice),
            coverage, INTERFACE_HASH, REGISTRY_HASH, SemanticIdAllocator.over(List.of(MODULE)));
        check(first != null && !first.hasErrors() && first.unit() != null,
            "the fixed corpus lowers to a validated unit: "
                + (first == null ? "null" : first.diagnostics()));
        if (first == null || first.hasErrors()) {
            return;
        }
        SemanticLowerer.LoweringResult second = SemanticLowerer.lowerModule(moduleOf(slice),
            coverage, INTERFACE_HASH, REGISTRY_HASH, SemanticIdAllocator.over(List.of(MODULE)));
        check(second != null && !second.hasErrors() && second.unit() != null,
            "the repeated lowering validates again: "
                + (second == null ? "null" : second.diagnostics()));
        if (second == null || second.hasErrors()) {
            return;
        }
        LoweredModuleUnit unit = first.unit();
        LoweredModuleUnit repeated = second.unit();

        byte[] firstDump = SemanticIrDumper.dumpModule(unit);
        byte[] secondDump = SemanticIrDumper.dumpModule(repeated);
        check(Arrays.equals(firstDump, secondDump),
            "two fresh lowerings produce byte-identical unit dumps (D8)");
        check(firstDump.length > 0, "the dump is non-empty");
        check(SemanticIrDumper.dumpModuleText(unit).equals(
                SemanticIrDumper.dumpModuleText(repeated)),
            "two fresh lowerings produce byte-identical dump text");

        List<String> firstDigests = new ArrayList<>();
        List<String> secondDigests = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            firstDigests.add(op.contract().canonicalDigest());
        }
        for (SemanticOp op : repeated.ops()) {
            secondDigests.add(op.contract().canonicalDigest());
        }
        check(firstDigests.equals(secondDigests) && !firstDigests.isEmpty(),
            "canonical contract digests are stable across runs: " + firstDigests);

        ContainerClaimingSeam.SeamResult firstSeam = ContainerClaimingSeam.check(unit.ops(),
            ContainerClaimingSeam.E3_WINDOW_ACTIVATION, unit.requiredCapabilities(), MODULE);
        ContainerClaimingSeam.SeamResult secondSeam = ContainerClaimingSeam.check(repeated.ops(),
            ContainerClaimingSeam.E3_WINDOW_ACTIVATION, repeated.requiredCapabilities(), MODULE);
        check(firstSeam.failure() == null && secondSeam.failure() == null
                && firstSeam.derivedClaims().equals(secondSeam.derivedClaims())
                && firstSeam.outcomes().equals(secondSeam.outcomes()),
            "the seam's derived claims and recorded outcomes are byte-identical across the "
                + "two lowerings");
    }

    // =========================================================================
    // 8. The combined dependency step: the nested-for-of corpus through the
    //    foundation detector, C5's arms, and the seam into a validated
    //    ∅-claim unit
    // =========================================================================

    static void testCombinedDependencyStep() {
        System.out.println("-- Combined dependency step: detector → arms → seam → validated "
            + "unit --");

        CheckedSlice slice = checkSlice("""
            for (let x: string of "ab") {
              for (let y: string of x) {}
            }
            """);
        if (slice == null) {
            return;
        }
        CheckedModuleInput module = moduleOf(slice);

        // The foundation detector's requirement manifest (the recorded
        // constructCoverage rows the unit producer consumes).
        CompilerInvocation invocation = CompilerProfileProvider.resolveCommonShadow(
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.V1_2_ACTIVE,
            CapabilityRegistry.releaseRegistry());
        ProjectInterfaceIndex index = new ProjectInterfaceIndex(
            ProjectInterfaceIndex.FORMAT_VERSION, Map.of(MODULE,
                new ExternalModuleInterface(MODULE, ExternalModuleKind.IMPLEMENTATION,
                    List.of(), List.of(), List.of(),
                    InitializationMode.ONCE_AFTER_DEPENDENCIES)));
        CheckedProjectInput input = new CheckedProjectInput(invocation, MODULE,
            List.of(module), invocation.releaseStateHash());
        RequirementManifestResult manifests =
            LoweringSupport.computeManifests(invocation, input, index);
        check(manifests != null && manifests.diagnostics().isEmpty() && manifests.manifests() != null
                && manifests.manifests().size() == 1,
            "the foundation detector produces exactly one requirement manifest: "
                + (manifests == null ? "null" : manifests.diagnostics()));
        if (manifests == null || !manifests.diagnostics().isEmpty()
                || manifests.manifests() == null || manifests.manifests().size() != 1) {
            return;
        }
        SemanticRequirementManifest manifest = manifests.manifests().get(0);

        // The pinned corpus-positionability facts: the detector records
        // IF_WHILE_FOR_FOR_OF (outer and inner for-of) with FOR_EACH,
        // SCALAR_LITERAL with CONST, and IDENTIFIER with BINDING_LOAD — every
        // row satisfiable by this epic's own arms end-to-end.
        check(manifest.constructCoverage().keySet().equals(Set.of(
                ConstructKind.SCALAR_LITERAL, ConstructKind.IDENTIFIER,
                ConstructKind.IF_WHILE_FOR_FOR_OF)),
            "the detector records exactly the pinned rows for the nested-for-of corpus: "
                + manifest.constructCoverage().keySet());
        check(manifest.constructCoverage().get(ConstructKind.SCALAR_LITERAL)
                .equals(ConstructKind.SCALAR_LITERAL.mappedOpKinds())
                && manifest.constructCoverage().get(ConstructKind.IDENTIFIER)
                .equals(ConstructKind.IDENTIFIER.mappedOpKinds())
                && manifest.constructCoverage().get(ConstructKind.IF_WHILE_FOR_FOR_OF)
                .equals(ConstructKind.IF_WHILE_FOR_FOR_OF.mappedOpKinds()),
            "the recorded rows carry the closed construct→op detector table verbatim");
        check(manifest.capabilities().contains(SemanticCapability.FOUNDATION_VALUES),
            "the manifest's plan-time FOUNDATION_VALUES claim (routing) is untouched");

        // C5's arms + the seam: lower the corpus module to its validated unit.
        SemanticLowerer.LoweringResult result = SemanticLowerer.lowerModule(module,
            manifest.constructCoverage(), INTERFACE_HASH, REGISTRY_HASH,
            SemanticIdAllocator.over(List.of(MODULE)));
        check(result != null && !result.hasErrors() && result.unit() != null,
            "the corpus lowers through C5's arms into a validated unit with no E6005: "
                + (result == null ? "null" : result.diagnostics()));
        if (result == null || result.hasErrors()) {
            return;
        }
        LoweredModuleUnit unit = result.unit();

        // The derived ∅ claim set and the recorded staged hand-offs.
        check(unit.requiredCapabilities().isEmpty(),
            "the corpus unit's derived claim set is ∅ (every E3 op's home row is inactive "
                + "during the tail)");
        check(!manifest.capabilities().equals(unit.requiredCapabilities()),
            "the manifest's plan-time FOUNDATION_VALUES claim (routing) and the unit's ∅ "
                + "claim set diverge exactly as the foundation pins");
        ContainerClaimingSeam.SeamResult seam = ContainerClaimingSeam.check(unit.ops(),
            ContainerClaimingSeam.E3_WINDOW_ACTIVATION, unit.requiredCapabilities(), MODULE);
        check(seam.failure() == null, "the seam fires no E6005 for the corpus unit");
        check(seam.derivedClaims().isEmpty(), "the seam derives the empty claim set");
        check(seam.outcomes().size() == 4,
            "the seam records exactly 4 staged hand-offs: CONST → FOUNDATION_VALUES, "
                + "BINDING_LOAD → BINDINGS, and the two FOR_EACH ops → "
                + "CONTAINERS_AND_STRINGS; got " + seam.outcomes().size());
        check(countOutcomes(seam.outcomes(), ContainerClaimingSeam.OutcomeKind.STAGED_HAND_OFF)
                == seam.outcomes().size(),
            "every recorded outcome is a staged hand-off");
        List<SemanticOpKind> kinds = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            kinds.add(op.kind());
        }
        check(kinds.contains(SemanticOpKind.CONST)
                && kinds.contains(SemanticOpKind.BINDING_LOAD)
                && kinds.stream().filter(kind -> kind == SemanticOpKind.FOR_EACH).count() == 2,
            "the corpus produces CONST (SCALAR_LITERAL), BINDING_LOAD (IDENTIFIER), and two "
                + "FOR_EACH ops (IF_WHILE_FOR_FOR_OF) — R-COVERAGE green; got " + kinds);

        // The nested-for-of loop-binding load-resolution rule end-to-end: the
        // load carries the enclosing FOR_EACH payload's binding and initial
        // generation.
        SemanticOp load = null;
        SemanticOp outerForEach = null;
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.BINDING_LOAD && load == null) {
                load = op;
            }
            if (op.kind() == SemanticOpKind.FOR_EACH && outerForEach == null) {
                outerForEach = op;
            }
        }
        if (load != null && outerForEach != null) {
            KindPayload.BindingLoadPayload loadPayload =
                (KindPayload.BindingLoadPayload) load.payload();
            KindPayload.ForEachPayload forEachPayload =
                (KindPayload.ForEachPayload) outerForEach.payload();
            check(loadPayload.binding().equals(forEachPayload.binding())
                    && loadPayload.generation() == forEachPayload.generation()
                    && loadPayload.generation() == SemanticLowerer.INITIAL_LOOP_GENERATION,
                "the corpus's loop-binding load carries the enclosing FOR_EACH payload's "
                    + "initial generation (the loop-binding load-resolution rule)");
        }
    }

    // =========================================================================
    // Runner
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Container Claiming Seam Test (ISSUE-0387) ===\n");

        testHomeMapping();
        testActivationGates();
        testSeamFourOutcomes();
        testGateClaimStates();
        testValidatorAcceptance();
        testValidatorNegatives();
        testDeterminism();
        testCombinedDependencyStep();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
