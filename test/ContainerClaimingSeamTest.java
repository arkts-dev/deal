package deal.test;

import deal.ast.ArrayLiteralExpr;
import deal.ast.BinaryExpr;
import deal.ast.Either;
import deal.ast.ExpressionNode;
import deal.ast.ForOfStatement;
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
import deal.semantic.ContainerClaimingSeam;
import deal.semantic.SemanticLowerer;
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
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ModuleInitPlan;
import deal.semantic.ir.OpId;
import deal.semantic.ir.OpResultType;
import deal.semantic.ir.OperationContractSnapshot;
import deal.semantic.ir.ProjectInterfaceIndex;
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
 * Verifies semantic-operation home mapping, lowering, validation failures,
 * and deterministic IR generation for container and string operations.
 */
public class ContainerClaimingSeamTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
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
        ParseResult parse = new Parser(lex.tokens(), "test.deal", lex.directiveEvents()).parse();
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

        check(ContainerClaimingSeam.homeRows(
                op(SemanticOpKind.OPTIONAL_READ,
                    new KindPayload.OptionalReadPayload(nextValue(), true, INT),
                    nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE, null))
                .equals(List.of(SemanticCapability.CONTAINERS_AND_STRINGS)),
            "OPTIONAL_READ homes to CONTAINERS_AND_STRINGS (the extras envelope)");

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
            op(SemanticOpKind.BREAK, new KindPayload.BreakPayload(nextOpId()), null, null,
                FailurePolicyId.NO_DEAL_FAILURE, null),
            op(SemanticOpKind.CONTINUE, new KindPayload.ContinuePayload(nextOpId()), null,
                null, FailurePolicyId.NO_DEAL_FAILURE, null),
            op(SemanticOpKind.TRY_CATCH,
                new KindPayload.TryCatchPayload(new BlockId(1), new BindingId(1),
                    new BlockId(2)),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, null),
            op(SemanticOpKind.THROW, new KindPayload.ThrowPayload(nextValue()), null, null,
                FailurePolicyId.THROW_TRANSFER, null));
        for (SemanticOp foreignOp : foreignOps) {
            check(ContainerClaimingSeam.homeRows(foreignOp).isEmpty(),
                foreignOp.kind() + " has no home row in this seam (its producer records "
                    + "its op-side outcomes)");
        }

        check(ContainerClaimingSeam.homeRows(
                op(SemanticOpKind.BRANCH,
                    new KindPayload.BranchPayload(ControlSelector.IF, nextValue(),
                        new BlockId(1), null),
                    null, null, FailurePolicyId.NO_DEAL_FAILURE, null))
                .equals(List.of(SemanticCapability.EVALUATION_ORDER)),
            "BRANCH homes to EVALUATION_ORDER");
        check(ContainerClaimingSeam.homeRows(
                op(SemanticOpKind.LOOP,
                    new KindPayload.LoopPayload(ControlSelector.WHILE, null, nextValue(),
                        new BlockId(1), null),
                    null, null, FailurePolicyId.NO_DEAL_FAILURE, null))
                .equals(List.of(SemanticCapability.EVALUATION_ORDER)),
            "LOOP homes to EVALUATION_ORDER");
        check(ContainerClaimingSeam.homeRows(
                op(SemanticOpKind.DISCARD, new KindPayload.DiscardPayload(nextValue()), null,
                    null, FailurePolicyId.NO_DEAL_FAILURE, null))
                .equals(List.of(SemanticCapability.EVALUATION_ORDER)),
            "DISCARD homes to EVALUATION_ORDER");
    }

    // =========================================================================
    // Lowering and validation
    // =========================================================================

    // =========================================================================
    // Validator acceptance
    // =========================================================================

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
            SemanticProfile.DEAL_V1_2_INT32, coverage, INTERFACE_HASH, REGISTRY_HASH,
            SemanticIdAllocator.over(List.of(MODULE)));
        check(first != null && !first.hasErrors() && first.unit() != null,
            "the fixed corpus lowers to a validated unit: "
                + (first == null ? "null" : first.diagnostics()));
        if (first == null || first.hasErrors()) {
            return;
        }
        SemanticLowerer.LoweringResult second = SemanticLowerer.lowerModule(moduleOf(slice),
            SemanticProfile.DEAL_V1_2_INT32, coverage, INTERFACE_HASH, REGISTRY_HASH,
            SemanticIdAllocator.over(List.of(MODULE)));
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

    }

    // =========================================================================
    // Runner
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Container Claiming Seam Test (ISSUE-0387) ===\n");

        testHomeMapping();
        testValidatorAcceptance();
        testValidatorNegatives();
        testDeterminism();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
