package deal.test;

import deal.ast.ArrayLiteralExpr;
import deal.ast.AssignmentExpr;
import deal.ast.Block;
import deal.ast.DeleteStatement;
import deal.ast.ExpressionNode;
import deal.ast.ExpressionStatement;
import deal.ast.IfStatement;
import deal.ast.ForOfStatement;
import deal.ast.IdentifierExpr;
import deal.ast.IndexExpr;
import deal.ast.LiteralExpr;
import deal.ast.LiteralValue;
import deal.ast.MemberAccessExpr;
import deal.ast.ObjectLiteralExpr;
import deal.ast.ProgramNode;
import deal.ast.Property;
import deal.ast.Span;
import deal.ast.StatementNode;
import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.NameResolver;
import deal.checker.Symbol;
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
import deal.semantic.ControlFlowValidator;
import deal.semantic.SemanticLowerer;
import deal.semantic.ir.AddressChainProtocol;
import deal.semantic.ir.AssignTargetKind;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.BoundaryRealization;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ConstructKind;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.DeleteTargetKind;
import deal.semantic.ir.ExportPlan;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.IndexMode;
import deal.semantic.ir.InitializationMode;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ModuleInitPlan;
import deal.semantic.ir.OpId;
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
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.ValueId;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.ExternalModuleKind;
import deal.types.Type;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class AddressChainLoweringTest {

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

    /** The own-module interface entry of the fixed invocation facts. */
    private static ExternalModuleInterface ownInterface() {
        return new ExternalModuleInterface(MODULE, ExternalModuleKind.IMPLEMENTATION,
            List.of(), List.of(), List.of(), InitializationMode.ONCE_AFTER_DEPENDENCIES);
    }

    private static final String INTERFACE_HASH = new ProjectInterfaceIndex(
        ProjectInterfaceIndex.FORMAT_VERSION, Map.of(MODULE, ownInterface()))
        .interfaceIndexDigest();

    private static SemanticIrValidator.ComparisonFacts facts() {
        return new SemanticIrValidator.ComparisonFacts(INTERFACE_HASH,
            SemanticProfile.DEAL_V1_5_BINARY64, REGISTRY_HASH);
    }

    private static SemanticLowerer.ModuleLowerer lowerer(CheckResult checks) {
        return new SemanticLowerer.ModuleLowerer(MODULE, SOURCE_ID, checks,
            SemanticIdAllocator.over(List.of(MODULE)));
    }

    private static CheckResult checks(Map<ExpressionNode, Type> types, SymbolTable table) {
        return new CheckResult(types, table, List.of());
    }

    private record CheckedSlice(ProgramNode program, CheckResult checks) {
    }

    private static CheckedSlice checkSlice(String source) {
        LexResult lex = new Lexer(source, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        check(parse.diagnostics().isEmpty(), "the slice parses cleanly: " + parse.diagnostics());
        if (!parse.diagnostics().isEmpty()) {
            return null;
        }
        NameResolver nr = new NameResolver("test.deal", (ModuleResolver) null);
        SymbolTable symTable = nr.resolve(parse.program());
        check(nr.diagnostics().isEmpty(), "the slice resolves cleanly: " + nr.diagnostics());
        if (!nr.diagnostics().isEmpty()) {
            return null;
        }
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());
        return new CheckedSlice(parse.program(), result);
    }

    private static CheckedModuleInput moduleOf(CheckedSlice slice) {
        return new CheckedModuleInput(MODULE, SOURCE_ID, Path.of("test.deal"), slice.program(),
            slice.checks(), List.of(), List.of(), CheckedModuleKind.IMPLEMENTATION);
    }

    // =========================================================================
    // Hand-built AST fixtures
    // =========================================================================

    private static Span span(int startColumn, int endColumn) {
        return new Span(SOURCE_ID, 1, startColumn, 1, endColumn);
    }

    private static IdentifierExpr ident(String name, int column) {
        return new IdentifierExpr(span(column, column + name.length() - 1), name);
    }

    private static LiteralExpr intLit(long value, int column) {
        return new LiteralExpr(span(column, column), new LiteralValue.NumberLiteral(value));
    }

    private static LiteralExpr stringLit(String value, int column) {
        return new LiteralExpr(span(column, column + value.length() + 1),
            new LiteralValue.StringLiteral(value));
    }

    private static MemberAccessExpr member(ExpressionNode object, String field, int column) {
        return new MemberAccessExpr(span(column, column + field.length()), object, field);
    }

    private static IndexExpr index(ExpressionNode array, ExpressionNode key, int column) {
        return new IndexExpr(span(column, column + 3), array, key);
    }

    private static AssignmentExpr assign(ExpressionNode target, ExpressionNode value) {
        return new AssignmentExpr(span(1, 10), target, value);
    }

    private static DeleteStatement delete(ExpressionNode target) {
        return new DeleteStatement(span(1, 10), target);
    }

    // =========================================================================
    // Shape assertion helpers
    // =========================================================================

    /** Asserts the parentage pin: the child records the chain op as parentOpId. */
    private static void checkParentage(String what, SemanticOp child, OpId chainOp) {
        check(chainOp.equals(child.origin().parentOpId()),
            what + " records the chain op as parentOpId (got " + child.origin().parentOpId()
                + ")");
    }

    /** Asserts the canonical runtime-validation realization id (D3). */
    private static void checkCanonicalRealization(String what, BoundaryRealization realization) {
        check(realization instanceof BoundaryRealization.RuntimeValidation validation
                && SemanticLowerer.CANONICAL_RUNTIME_VALIDATION_ID.equals(validation.checkId()),
            what + " carries the canonical RuntimeValidation realization id (got "
                + realization + ")");
    }

    // =========================================================================
    // 1. ASSIGN VARIABLE — [valueOp, VARIABLE_ASSIGNMENT, BINDING_STORE]
    // =========================================================================

    static void testVariableAssignArm() {
        System.out.println("-- ASSIGN VARIABLE: [valueOp, VARIABLE_ASSIGNMENT, BINDING_STORE] --");

        IdentifierExpr target = ident("x", 1);
        LiteralExpr value = intLit(1, 5);
        AssignmentExpr assignment = assign(target, value);
        SymbolTable table = new SymbolTable();
        table.define("x", new Symbol.VariableSymbol("x", Type.Number.INSTANCE, false));
        CheckResult checked = checks(
            Map.of(target, Type.Number.INSTANCE, value, Type.Number.INSTANCE, assignment, Type.Number.INSTANCE), table);

        SemanticLowerer.ModuleLowerer lowerer = lowerer(checked);
        ValueId result = lowerer.lowerAssignment(assignment);
        List<SemanticOp> ops = lowerer.ops();
        check(AddressChainProtocol.validate(protocolUnit(ops)).isEmpty(), "lowered arm passes address protocol");
        check(ops.size() == 4,
            "x = 1 produces CONST + VARIABLE_ASSIGNMENT + BINDING_STORE + ASSIGN = 4 ops; got "
                + ops.size());
        if (ops.size() != 4) {
            return;
        }
        SemanticOp valueOp = ops.get(0);
        SemanticOp boundaryOp = ops.get(1);
        SemanticOp commitOp = ops.get(2);
        SemanticOp chainOp = ops.get(3);

        check(valueOp.kind() == SemanticOpKind.CONST
                && ((KindPayload.ConstPayload) valueOp.payload()).value()
                    .equals(new ScalarValue.Number(1)),
            "the value child is the CONST producing the committed value 1");
        check(valueOp.result() instanceof ValueId, "the value child publishes a ValueId");
        ValueId committed = (ValueId) valueOp.result();
        check(valueOp.origin().kind() == SourceOriginKind.USER,
            "the value child lowers the user source expression (USER origin)");

        check(boundaryOp.kind() == SemanticOpKind.BOUNDARY,
            "the boundary child is a BOUNDARY op");
        KindPayload.BoundaryPayload boundary =
            (KindPayload.BoundaryPayload) boundaryOp.payload();
        check(boundary.kind() == BoundaryKind.VARIABLE_ASSIGNMENT,
            "the boundary kind is VARIABLE_ASSIGNMENT");
        check(boundary.descriptor().equals(RuntimeDescriptor.Number.INSTANCE),
            "the boundary descriptor is the declared target descriptor (number)");
        check(boundaryOp.failurePolicy() == FailurePolicyId.TYPE_DESCRIPTOR,
            "the non-function descriptor uses the TYPE_DESCRIPTOR policy (descriptor-kind "
                + "rule)");
        check(boundary.input().equals(committed),
            "the boundary input is the committed value (the valueOp result)");
        checkCanonicalRealization("the VARIABLE_ASSIGNMENT boundary", boundary.realization());
        check(boundaryOp.origin().span().startColumn() == 1,
            "the boundary carries the assignment target's span");

        check(commitOp.kind() == SemanticOpKind.BINDING_STORE,
            "the commit child is a BINDING_STORE op");
        KindPayload.BindingStorePayload store =
            (KindPayload.BindingStorePayload) commitOp.payload();
        check(store.value().equals(committed),
            "BindingStorePayload.value is the committed value");
        check(store.binding() != null, "BindingStorePayload carries the binding identity");
        check(store.generation() == SemanticLowerer.INITIAL_LOOP_GENERATION,
            "BindingStorePayload carries the pinned initial generation");
        check(commitOp.failurePolicy() == FailurePolicyId.NO_DEAL_FAILURE,
            "the commit carries NO_DEAL_FAILURE");

        check(chainOp.kind() == SemanticOpKind.ASSIGN, "the chain op is ASSIGN");
        KindPayload.AssignPayload assignPayload = (KindPayload.AssignPayload) chainOp.payload();
        check(assignPayload.targetKind() == AssignTargetKind.VARIABLE,
            "the target kind is VARIABLE");
        check(assignPayload.childOps().equals(
                List.of(valueOp.opId(), boundaryOp.opId(), commitOp.opId())),
            "the child list is exactly [valueOp, boundaryOp, commitOp] in payload order");
        check(chainOp.result().equals(committed),
            "ASSIGN publishes the committed value's identity (A-D6)");
        check(chainOp.resultType().equals(RuntimeDescriptor.Number.INSTANCE),
            "ASSIGN resultType is the target position's checked descriptor (number)");
        check(chainOp.failurePolicy() == FailurePolicyId.NO_DEAL_FAILURE,
            "the chain op carries NO_DEAL_FAILURE");
        check(result.equals(committed), "lowerAssignment returns the committed value");

        checkParentage("the value child", valueOp, chainOp.opId());
        checkParentage("the boundary child", boundaryOp, chainOp.opId());
        checkParentage("the commit child", commitOp, chainOp.opId());
        check(chainOp.origin().parentOpId() == null,
            "a top-level chain op carries no parentOpId");
        check(chainOp.origin().span().startColumn() == 1
                && chainOp.origin().span().endColumn() == 10,
            "the chain op carries the whole assignment's span");
        check(ops.stream().filter(op -> op.kind() == SemanticOpKind.BINDING_STORE).count() == 1
                && ops.get(ops.size() - 1) == chainOp,
            "exactly one commit, and the chain op closes the list (commit is last among the "
                + "chain children)");
    }

    // =========================================================================
    // 2. VARIABLE boundary production: function descriptor and binding reuse
    // =========================================================================

    static void testVariableBoundaryDescriptorKindAndBindingReuse() {
        System.out.println("-- VARIABLE boundary: FUNCTION_SIGNATURE descriptor, binding reuse --");

        // (a) Function-typed target: the boundary carries the declared
        // function descriptor with policy FUNCTION_SIGNATURE.
        Type.Func funcType = new Type.Func(List.of(Type.Number.INSTANCE), Type.Number.INSTANCE);
        IdentifierExpr target = ident("f", 1);
        IdentifierExpr value = ident("n", 5);
        AssignmentExpr assignment = assign(target, value);
        SymbolTable table = new SymbolTable();
        table.define("f", new Symbol.VariableSymbol("f", funcType, false));
        table.define("n", new Symbol.VariableSymbol("n", funcType, false));
        CheckResult checked = checks(
            Map.of(target, funcType, value, funcType, assignment, funcType), table);

        SemanticLowerer.ModuleLowerer lowerer = lowerer(checked);
        SemanticLowerer.ForEachFrame frame = lowerer.openForEachScope("n");
        ValueId committed = lowerer.lowerAssignment(assignment);
        lowerer.closeForEachScope();
        List<SemanticOp> ops = lowerer.ops();
        check(AddressChainProtocol.validate(protocolUnit(ops)).isEmpty(), "lowered arm passes address protocol");
        check(ops.size() == 4,
            "f = n produces BINDING_LOAD + VARIABLE_ASSIGNMENT + BINDING_STORE + ASSIGN = 4 "
                + "ops; got " + ops.size());
        if (ops.size() != 4) {
            return;
        }
        SemanticOp valueOp = ops.get(0);
        SemanticOp boundaryOp = ops.get(1);
        SemanticOp commitOp = ops.get(2);
        SemanticOp chainOp = ops.get(3);
        check(valueOp.kind() == SemanticOpKind.BINDING_LOAD,
            "the value child is the loop-binding load of n");
        RuntimeDescriptor.Func functionDescriptor =
            new RuntimeDescriptor.Func(List.of(RuntimeDescriptor.Number.INSTANCE),
                RuntimeDescriptor.Number.INSTANCE);
        check(valueOp.resultType().equals(functionDescriptor),
            "the load publishes the function descriptor (number)->number");
        KindPayload.BoundaryPayload boundary =
            (KindPayload.BoundaryPayload) boundaryOp.payload();
        check(boundary.kind() == BoundaryKind.VARIABLE_ASSIGNMENT
                && boundary.descriptor().equals(functionDescriptor),
            "the boundary descriptor is the declared function descriptor (number)->number");
        check(boundaryOp.failurePolicy() == FailurePolicyId.FUNCTION_SIGNATURE,
            "the function descriptor uses the FUNCTION_SIGNATURE policy (descriptor-kind "
                + "rule)");
        check(boundary.input().equals(committed),
            "the boundary input is the committed value (the load result)");
        check(((KindPayload.BindingStorePayload) commitOp.payload()).value().equals(committed),
            "the store commits the adapter-free committed value");
        check(chainOp.resultType().equals(functionDescriptor),
            "ASSIGN resultType is the declared function descriptor");
        check(chainOp.result().equals(committed),
            "ASSIGN publishes the committed value identity");

        // (b) Binding reuse: a second assignment to the same variable
        // stores through the identical BindingId (generation pinned).
        IdentifierExpr target2 = ident("x", 1);
        LiteralExpr value2 = intLit(2, 5);
        AssignmentExpr assignment2 = assign(target2, value2);
        SymbolTable table2 = new SymbolTable();
        table2.define("x", new Symbol.VariableSymbol("x", Type.Number.INSTANCE, false));
        CheckResult checked2 = checks(
            Map.of(target2, Type.Number.INSTANCE, value2, Type.Number.INSTANCE, assignment2, Type.Number.INSTANCE), table2);
        SemanticLowerer.ModuleLowerer reuse = lowerer(checked2);
        ValueId firstValue = reuse.lowerAssignment(assignment2);
        ValueId secondValue = reuse.lowerAssignment(assignment2);
        check(firstValue != secondValue, "each assignment produces its own committed value");
        List<SemanticOp> reuseOps = reuse.ops();
        List<KindPayload.BindingStorePayload> stores = new ArrayList<>();
        for (SemanticOp op : reuseOps) {
            if (op.kind() == SemanticOpKind.BINDING_STORE) {
                stores.add((KindPayload.BindingStorePayload) op.payload());
            }
        }
        check(stores.size() == 2, "two assignments produce two BINDING_STORE commits");
        if (stores.size() == 2) {
            check(stores.get(0).binding().equals(stores.get(1).binding()),
                "both stores reference the same variable's binding identity");
            check(stores.get(0).generation() == stores.get(1).generation()
                    && stores.get(0).generation() == SemanticLowerer.INITIAL_LOOP_GENERATION,
                "both stores carry the pinned initial generation");
        }

        // (c) A loop-binding target reuses the for-of frame's binding id
        // and generation (the store composes with the frame allocation).
        IdentifierExpr loopTarget = ident("k", 1);
        LiteralExpr loopValue = stringLit("b", 5);
        AssignmentExpr loopAssign = assign(loopTarget, loopValue);
        SymbolTable table3 = new SymbolTable();
        table3.define("k", new Symbol.VariableSymbol("k", Type.String.INSTANCE, false));
        CheckResult checked3 = checks(
            Map.of(loopTarget, Type.String.INSTANCE, loopValue, Type.String.INSTANCE, loopAssign, Type.String.INSTANCE), table3);
        SemanticLowerer.ModuleLowerer frameLowerer = lowerer(checked3);
        SemanticLowerer.ForEachFrame loopFrame = frameLowerer.openForEachScope("k");
        frameLowerer.lowerAssignment(loopAssign);
        frameLowerer.closeForEachScope();
        List<KindPayload.BindingStorePayload> frameStores = new ArrayList<>();
        for (SemanticOp op : frameLowerer.ops()) {
            if (op.kind() == SemanticOpKind.BINDING_STORE) {
                frameStores.add((KindPayload.BindingStorePayload) op.payload());
            }
        }
        check(frameStores.size() == 1, "the loop assignment produces one store");
        if (frameStores.size() == 1) {
            check(frameStores.get(0).binding().equals(loopFrame.binding()),
                "the store references the enclosing FOR_EACH frame's binding id");
            check(frameStores.get(0).generation() == loopFrame.generation(),
                "the store carries the frame's pinned generation");
        }
    }

    // =========================================================================
    // 3. ASSIGN TABLE_SLOT member — [containerOp, valueOp, MEMBER_WRITE]
    // =========================================================================

    static void testTableMemberAssignArm() {
        System.out.println("-- ASSIGN TABLE_SLOT member: [containerOp, valueOp, MEMBER_WRITE] --");

        IdentifierExpr receiver = ident("t", 1);
        MemberAccessExpr target = member(receiver, "y", 3);
        LiteralExpr value = intLit(9, 7);
        AssignmentExpr assignment = assign(target, value);
        SymbolTable table = new SymbolTable();
        table.define("t", new Symbol.VariableSymbol("t", Type.Table.INSTANCE, false));
        CheckResult checked = checks(Map.of(receiver, Type.Table.INSTANCE, target,
            Type.Table.INSTANCE, value, Type.Number.INSTANCE, assignment, Type.Number.INSTANCE), table);

        SemanticLowerer.ModuleLowerer lowerer = lowerer(checked);
        lowerer.openForEachScope("t");
        ValueId result = lowerer.lowerAssignment(assignment);
        lowerer.closeForEachScope();
        List<SemanticOp> ops = lowerer.ops();
        check(AddressChainProtocol.validate(protocolUnit(ops)).isEmpty(), "lowered arm passes address protocol");
        check(ops.size() == 4,
            "t.y = 9 produces BINDING_LOAD + CONST + MEMBER_WRITE + ASSIGN = 4 ops; got "
                + ops.size());
        if (ops.size() != 4) {
            return;
        }
        SemanticOp containerOp = ops.get(0);
        SemanticOp valueOp = ops.get(1);
        SemanticOp commitOp = ops.get(2);
        SemanticOp chainOp = ops.get(3);
        ValueId container = (ValueId) containerOp.result();
        ValueId committed = (ValueId) valueOp.result();

        check(containerOp.kind() == SemanticOpKind.BINDING_LOAD
                && containerOp.resultType().equals(RuntimeDescriptor.Table.INSTANCE),
            "the container child is the receiver load (table descriptor)");
        check(valueOp.kind() == SemanticOpKind.CONST, "the value child is the RHS CONST");
        check(commitOp.kind() == SemanticOpKind.MEMBER_WRITE,
            "the commit child is MEMBER_WRITE");
        KindPayload.MemberWritePayload write =
            (KindPayload.MemberWritePayload) commitOp.payload();
        check(write.table().equals(container),
            "MEMBER_WRITE references the resolved receiver value");
        check("y".equals(write.key()), "MEMBER_WRITE carries the literal member key 'y'");
        check(write.value().equals(committed), "MEMBER_WRITE stores the RHS value");

        KindPayload.AssignPayload payload = (KindPayload.AssignPayload) chainOp.payload();
        check(payload.targetKind() == AssignTargetKind.TABLE_SLOT
                && payload.childOps().equals(
                    List.of(containerOp.opId(), valueOp.opId(), commitOp.opId())),
            "the chain is TABLE_SLOT [containerOp, valueOp, MEMBER_WRITE] with no keyOp "
                + "(the member key is a literal string)");
        check(chainOp.result().equals(committed)
                && chainOp.resultType().equals(RuntimeDescriptor.Number.INSTANCE),
            "ASSIGN publishes the original number RHS identity and descriptor");
        check(result.equals(committed), "lowerAssignment returns the committed value");
        checkParentage("the container child", containerOp, chainOp.opId());
        checkParentage("the value child", valueOp, chainOp.opId());
        checkParentage("the commit child", commitOp, chainOp.opId());
        check(ops.stream().noneMatch(op -> op.kind() == SemanticOpKind.BOUNDARY),
            "TABLE_SLOT member writes carry zero write-check boundaries (A-D4)");
    }

    // =========================================================================
    // 4. ASSIGN TABLE_SLOT index — [containerOp, keyOp, valueOp, normalize, INDEX_WRITE]
    // =========================================================================

    static void testTableIndexAssignArm() {
        System.out.println("-- ASSIGN TABLE_SLOT index: [containerOp, keyOp, valueOp, "
            + "INDEX_NORMALIZE(TABLE_WRITE), INDEX_WRITE] --");

        IdentifierExpr receiver = ident("t", 1);
        LiteralExpr key = stringLit("k", 3);
        IndexExpr target = index(receiver, key, 3);
        LiteralExpr value = intLit(9, 9);
        AssignmentExpr assignment = assign(target, value);
        SymbolTable table = new SymbolTable();
        table.define("t", new Symbol.VariableSymbol("t", Type.Table.INSTANCE, false));
        CheckResult checked = checks(Map.of(receiver, Type.Table.INSTANCE, key,
            Type.String.INSTANCE, target, Type.Table.INSTANCE, value, Type.Number.INSTANCE, assignment, Type.Number.INSTANCE),
            table);

        SemanticLowerer.ModuleLowerer lowerer = lowerer(checked);
        lowerer.openForEachScope("t");
        ValueId result = lowerer.lowerAssignment(assignment);
        lowerer.closeForEachScope();
        List<SemanticOp> ops = lowerer.ops();
        check(AddressChainProtocol.validate(protocolUnit(ops)).isEmpty(), "lowered arm passes address protocol");
        check(ops.size() == 6,
            "t[\"k\"] = 9 produces BINDING_LOAD + CONST(k) + CONST(9) + INDEX_NORMALIZE + "
                + "INDEX_WRITE + ASSIGN = 6 ops; got " + ops.size());
        if (ops.size() != 6) {
            return;
        }
        SemanticOp containerOp = ops.get(0);
        SemanticOp keyOp = ops.get(1);
        SemanticOp valueOp = ops.get(2);
        SemanticOp normalizeOp = ops.get(3);
        SemanticOp commitOp = ops.get(4);
        SemanticOp chainOp = ops.get(5);
        ValueId container = (ValueId) containerOp.result();
        ValueId keyValue = (ValueId) keyOp.result();
        ValueId committed = (ValueId) valueOp.result();
        ValueId slot = (ValueId) normalizeOp.result();

        check(keyOp.kind() == SemanticOpKind.CONST
                && keyOp.resultType().equals(RuntimeDescriptor.String.INSTANCE),
            "the key child is the string CONST (static string by the E3018 gate)");
        check(normalizeOp.kind() == SemanticOpKind.INDEX_NORMALIZE,
            "the normalize child is INDEX_NORMALIZE");
        KindPayload.IndexNormalizePayload normalize =
            (KindPayload.IndexNormalizePayload) normalizeOp.payload();
        check(normalize.mode() == IndexMode.TABLE_WRITE,
            "the normalize mode is TABLE_WRITE (write is a mutation context)");
        check(normalizeOp.failurePolicy() == FailurePolicyId.NO_DEAL_FAILURE,
            "the normalize is pure: NO_DEAL_FAILURE");
        check(normalize.rawKey().equals(keyValue)
                && normalize.currentLength().equals(keyValue),
            "rawKey and the unused currentLength both reference the raw key identity (no "
                + "length read for table targets)");
        check(normalizeOp.resultType().equals(RuntimeDescriptor.String.INSTANCE),
            "the normalize result type is string (TableSlot {key: string})");
        check(commitOp.kind() == SemanticOpKind.INDEX_WRITE,
            "the commit child is INDEX_WRITE");
        KindPayload.IndexWritePayload write = (KindPayload.IndexWritePayload) commitOp.payload();
        check(write.container().equals(container) && write.slot().equals(slot)
                && write.value().equals(committed),
            "INDEX_WRITE references the resolved container, the normalized slot, and the RHS");

        KindPayload.AssignPayload payload = (KindPayload.AssignPayload) chainOp.payload();
        check(payload.targetKind() == AssignTargetKind.TABLE_SLOT
                && payload.childOps().equals(List.of(containerOp.opId(), keyOp.opId(),
                    valueOp.opId(), normalizeOp.opId(), commitOp.opId())),
            "the chain is TABLE_SLOT [containerOp, keyOp, valueOp, normalizeOp, "
                + "INDEX_WRITE]");
        check(chainOp.result().equals(committed)
                && chainOp.resultType().equals(RuntimeDescriptor.Number.INSTANCE),
            "ASSIGN publishes the original number RHS identity and descriptor");
        check(result.equals(committed), "lowerAssignment returns the committed value");
        checkParentage("the container child", containerOp, chainOp.opId());
        checkParentage("the key child", keyOp, chainOp.opId());
        checkParentage("the value child", valueOp, chainOp.opId());
        checkParentage("the normalize child", normalizeOp, chainOp.opId());
        checkParentage("the commit child", commitOp, chainOp.opId());
        check(ops.stream().noneMatch(op -> op.kind() == SemanticOpKind.BOUNDARY),
            "TABLE_SLOT index writes carry zero write-check boundaries (A-D4)");
    }

    // =========================================================================
    // 5. ASSIGN ARRAY_SLOT — the seven-child write chain with the bounds boundary
    // =========================================================================

    static void testArrayIndexAssignArm() {
        System.out.println("-- ASSIGN ARRAY_SLOT: [containerOp, keyOp, valueOp, lengthOp, "
            + "normalize(ARRAY_WRITE), ARRAY_ELEMENT_ASSIGNMENT, INDEX_WRITE] --");

        IdentifierExpr receiver = ident("a", 1);
        LiteralExpr key = intLit(0, 3);
        IndexExpr target = index(receiver, key, 3);
        LiteralExpr value = intLit(9, 7);
        AssignmentExpr assignment = assign(target, value);
        Type.Array arrayType = new Type.Array(Type.Number.INSTANCE);
        SymbolTable table = new SymbolTable();
        table.define("a", new Symbol.VariableSymbol("a", arrayType, false));
        CheckResult checked = checks(Map.of(receiver, arrayType, key, Type.Number.INSTANCE,
            target, Type.Number.INSTANCE, value, Type.Number.INSTANCE, assignment, Type.Number.INSTANCE), table);

        SemanticLowerer.ModuleLowerer lowerer = lowerer(checked);
        lowerer.openForEachScope("a");
        ValueId result = lowerer.lowerAssignment(assignment);
        lowerer.closeForEachScope();
        List<SemanticOp> ops = lowerer.ops();
        check(AddressChainProtocol.validate(protocolUnit(ops)).isEmpty(), "lowered arm passes address protocol");
        check(ops.size() == 8,
            "a[0] = 9 produces BINDING_LOAD + CONST(k) + CONST(v) + ARRAY_LENGTH + "
                + "INDEX_NORMALIZE + BOUNDARY + INDEX_WRITE + ASSIGN = 8 ops; got "
                + ops.size());
        if (ops.size() != 8) {
            return;
        }
        SemanticOp containerOp = ops.get(0);
        SemanticOp keyOp = ops.get(1);
        SemanticOp valueOp = ops.get(2);
        SemanticOp lengthOp = ops.get(3);
        SemanticOp normalizeOp = ops.get(4);
        SemanticOp boundaryOp = ops.get(5);
        SemanticOp commitOp = ops.get(6);
        SemanticOp chainOp = ops.get(7);
        ValueId container = (ValueId) containerOp.result();
        ValueId keyValue = (ValueId) keyOp.result();
        ValueId committed = (ValueId) valueOp.result();
        ValueId length = (ValueId) lengthOp.result();
        ValueId slot = (ValueId) normalizeOp.result();

        check(lengthOp.kind() == SemanticOpKind.ARRAY_LENGTH
                && lengthOp.resultType().equals(RuntimeDescriptor.Number.INSTANCE),
            "the length child is an ARRAY_LENGTH read of the resolved receiver (number "
                + "result)");
        check(((KindPayload.ArrayLengthPayload) lengthOp.payload()).arrayValue()
                .equals(container),
            "the ARRAY_LENGTH child references the resolved receiver (the length read pins "
                + "at normalize time, after key and RHS)");
        check(normalizeOp.kind() == SemanticOpKind.INDEX_NORMALIZE
                && normalizeOp.failurePolicy() == FailurePolicyId.NO_DEAL_FAILURE,
            "the normalize child is pure INDEX_NORMALIZE (NO_DEAL_FAILURE — never E8002)");
        KindPayload.IndexNormalizePayload normalize =
            (KindPayload.IndexNormalizePayload) normalizeOp.payload();
        check(normalize.mode() == IndexMode.ARRAY_WRITE,
            "the normalize mode is ARRAY_WRITE");
        check(normalize.rawKey().equals(keyValue)
                && normalize.currentLength().equals(length),
            "the normalize carries rawKey = the key result and currentLength = the length "
                + "read at normalize time");
        check(normalizeOp.resultType().equals(RuntimeDescriptor.Number.INSTANCE),
            "the normalize result type is number (ArraySlot)");

        check(boundaryOp.kind() == SemanticOpKind.BOUNDARY
                && boundaryOp.failurePolicy() == FailurePolicyId.ARRAY_WRITE_BOUNDS_THEN_ELEMENT,
            "exactly one write-check boundary with ARRAY_WRITE_BOUNDS_THEN_ELEMENT");
        KindPayload.BoundaryPayload boundary =
            (KindPayload.BoundaryPayload) boundaryOp.payload();
        check(boundary.kind() == BoundaryKind.ARRAY_ELEMENT_ASSIGNMENT,
            "the boundary kind is ARRAY_ELEMENT_ASSIGNMENT");
        check(boundary.descriptor().equals(RuntimeDescriptor.Number.INSTANCE),
            "the boundary descriptor is the element descriptor (number)");
        check(boundary.input().equals(committed),
            "the boundary input is the checked RHS value");
        checkCanonicalRealization("the ARRAY_ELEMENT_ASSIGNMENT boundary",
            boundary.realization());
        check(boundaryOp.origin().span().startColumn() == 3,
            "the boundary carries the write-target (index) span");

        check(commitOp.kind() == SemanticOpKind.INDEX_WRITE,
            "the commit child is INDEX_WRITE");
        KindPayload.IndexWritePayload write = (KindPayload.IndexWritePayload) commitOp.payload();
        check(write.container().equals(container) && write.slot().equals(slot)
                && write.value().equals(committed),
            "INDEX_WRITE references the resolved container, the normalized slot, and the "
                + "checked RHS");

        KindPayload.AssignPayload payload = (KindPayload.AssignPayload) chainOp.payload();
        check(payload.targetKind() == AssignTargetKind.ARRAY_SLOT
                && payload.childOps().equals(List.of(containerOp.opId(), keyOp.opId(),
                    valueOp.opId(), lengthOp.opId(), normalizeOp.opId(), boundaryOp.opId(),
                    commitOp.opId())),
            "the chain is ARRAY_SLOT [containerOp, keyOp, valueOp, lengthOp, normalizeOp, "
                + "boundaryOp, INDEX_WRITE]");
        check(chainOp.result().equals(committed)
                && chainOp.resultType().equals(RuntimeDescriptor.Number.INSTANCE),
            "ASSIGN publishes the committed value with the target position's checked "
                + "descriptor (the element descriptor number)");
        check(result.equals(committed), "lowerAssignment returns the committed value");
        for (SemanticOp child : ops.subList(0, 7)) {
            checkParentage(child.kind().name() + " child", child, chainOp.opId());
        }
    }

    // =========================================================================
    // 6. The append idiom — xs[xs.length] = v through the standard chain
    // =========================================================================

    static void testAppendIdiomArm() {
        System.out.println("-- Append idiom: xs[xs.length] = v through the standard "
            + "ARRAY_SLOT chain --");

        IdentifierExpr receiver = ident("xs", 1);
        MemberAccessExpr lengthAccess = member(receiver, "length", 4);
        IndexExpr target = index(receiver, lengthAccess, 4);
        LiteralExpr value = intLit(9, 14);
        AssignmentExpr assignment = assign(target, value);
        Type.Array arrayType = new Type.Array(Type.Number.INSTANCE);
        SymbolTable table = new SymbolTable();
        table.define("xs", new Symbol.VariableSymbol("xs", arrayType, false));
        CheckResult checked = checks(Map.of(receiver, arrayType, lengthAccess,
            Type.Number.INSTANCE, target, Type.Number.INSTANCE, value, Type.Number.INSTANCE, assignment, Type.Number.INSTANCE), table);

        SemanticLowerer.ModuleLowerer lowerer = lowerer(checked);
        lowerer.openForEachScope("xs");
        ValueId result = lowerer.lowerAssignment(assignment);
        lowerer.closeForEachScope();
        List<SemanticOp> ops = lowerer.ops();
        check(AddressChainProtocol.validate(protocolUnit(ops)).isEmpty(), "lowered arm passes address protocol");
        check(ops.size() == 9,
            "xs[xs.length] = 9 produces receiver load + key load + ARRAY_LENGTH(key) + "
                + "CONST(v) + ARRAY_LENGTH(lengthOp) + INDEX_NORMALIZE + BOUNDARY + "
                + "INDEX_WRITE + ASSIGN = 9 ops; got " + ops.size());
        if (ops.size() != 9) {
            return;
        }
        SemanticOp containerOp = ops.get(0);
        SemanticOp keyReceiverOp = ops.get(1);
        SemanticOp keyOp = ops.get(2);
        SemanticOp valueOp = ops.get(3);
        SemanticOp lengthOp = ops.get(4);
        SemanticOp normalizeOp = ops.get(5);
        SemanticOp boundaryOp = ops.get(6);
        SemanticOp commitOp = ops.get(7);
        SemanticOp chainOp = ops.get(8);
        ValueId container = (ValueId) containerOp.result();
        ValueId committed = (ValueId) valueOp.result();

        check(containerOp.kind() == SemanticOpKind.BINDING_LOAD
                && keyReceiverOp.kind() == SemanticOpKind.BINDING_LOAD,
            "the identifier appears twice in the source (receiver and .length object) and "
                + "lowers to two loads — each source occurrence evaluated once");
        check(keyOp.kind() == SemanticOpKind.ARRAY_LENGTH
                && ((KindPayload.ArrayLengthPayload) keyOp.payload()).arrayValue()
                    .equals((ValueId) keyReceiverOp.result()),
            "the key child is the ARRAY_LENGTH read of the .length source occurrence "
                + "(keyOp result type number)");
        check(lengthOp.kind() == SemanticOpKind.ARRAY_LENGTH
                && ((KindPayload.ArrayLengthPayload) lengthOp.payload()).arrayValue()
                    .equals(container),
            "the chain's lengthOp is a second ARRAY_LENGTH of the resolved receiver (the "
                + "normalize-time length pin)");
        KindPayload.IndexNormalizePayload normalize =
            (KindPayload.IndexNormalizePayload) normalizeOp.payload();
        check(normalize.mode() == IndexMode.ARRAY_WRITE
                && normalize.rawKey().equals((ValueId) keyOp.result())
                && normalize.currentLength().equals((ValueId) lengthOp.result()),
            "the normalize computes append from index == length over the key and the "
                + "normalize-time length (no special append shape exists)");
        check(boundaryOp.kind() == SemanticOpKind.BOUNDARY
                && ((KindPayload.BoundaryPayload) boundaryOp.payload()).kind()
                    == BoundaryKind.ARRAY_ELEMENT_ASSIGNMENT,
            "the standard ARRAY_ELEMENT_ASSIGNMENT boundary runs for the append idiom");
        check(commitOp.kind() == SemanticOpKind.INDEX_WRITE
                && ((KindPayload.IndexWritePayload) commitOp.payload()).value()
                    .equals(committed),
            "INDEX_WRITE appends the RHS when the normalize computed append");
        KindPayload.AssignPayload payload = (KindPayload.AssignPayload) chainOp.payload();
        check(payload.targetKind() == AssignTargetKind.ARRAY_SLOT && payload.childOps().size() == 7,
            "the append idiom lowers through the standard seven-child ARRAY_SLOT chain");
        check(chainOp.result().equals(committed)
                && chainOp.resultType().equals(RuntimeDescriptor.Number.INSTANCE),
            "ASSIGN publishes the committed value with the element descriptor");
        check(result.equals(committed), "lowerAssignment returns the committed value");
    }

    // =========================================================================
    // 7. ASSIGN CLASS_FIELD — [containerOp, valueOp, FIELD_WRITE]
    // =========================================================================

    static void testClassFieldAssignArm() {
        System.out.println("-- ASSIGN CLASS_FIELD: [containerOp, valueOp, FIELD_WRITE] --");

        IdentifierExpr receiver = ident("c", 1);
        MemberAccessExpr target = member(receiver, "f", 3);
        LiteralExpr value = intLit(9, 7);
        AssignmentExpr assignment = assign(target, value);
        Type.Class classType = IdentityTestFixtures.classType("C", "main");
        SymbolTable table = new SymbolTable();
        table.define("c", new Symbol.VariableSymbol("c", classType, false));
        CheckResult checked = checks(Map.of(receiver, classType, target, Type.Number.INSTANCE,
            value, Type.Number.INSTANCE, assignment, Type.Number.INSTANCE), table);

        SemanticLowerer.ModuleLowerer lowerer = lowerer(checked);
        lowerer.openForEachScope("c");
        ValueId result = lowerer.lowerAssignment(assignment);
        lowerer.closeForEachScope();
        List<SemanticOp> ops = lowerer.ops();
        check(AddressChainProtocol.validate(protocolUnit(ops)).isEmpty(), "lowered arm passes address protocol");
        check(ops.size() == 4,
            "c.f = 9 produces BINDING_LOAD + CONST + FIELD_WRITE + ASSIGN = 4 ops; got "
                + ops.size());
        if (ops.size() != 4) {
            return;
        }
        SemanticOp containerOp = ops.get(0);
        SemanticOp valueOp = ops.get(1);
        SemanticOp commitOp = ops.get(2);
        SemanticOp chainOp = ops.get(3);
        ValueId container = (ValueId) containerOp.result();
        ValueId committed = (ValueId) valueOp.result();

        check(commitOp.kind() == SemanticOpKind.FIELD_WRITE,
            "the commit child is FIELD_WRITE");
        KindPayload.FieldWritePayload write = (KindPayload.FieldWritePayload) commitOp.payload();
        check(write.classValue().equals(container),
            "FIELD_WRITE references the resolved class value");
        check(write.classId().equals(new ClassId("main", "C")),
            "FIELD_WRITE carries the checked class identity @main/C");
        check("f".equals(write.field()) && write.value().equals(committed),
            "FIELD_WRITE carries the field name and the RHS value");

        KindPayload.AssignPayload payload = (KindPayload.AssignPayload) chainOp.payload();
        check(payload.targetKind() == AssignTargetKind.CLASS_FIELD
                && payload.childOps().equals(
                    List.of(containerOp.opId(), valueOp.opId(), commitOp.opId())),
            "the chain is CLASS_FIELD [containerOp, valueOp, FIELD_WRITE]");
        check(chainOp.result().equals(committed)
                && chainOp.resultType().equals(RuntimeDescriptor.Number.INSTANCE),
            "ASSIGN publishes the committed value with the field descriptor (number)");
        check(result.equals(committed), "lowerAssignment returns the committed value");
        check(ops.stream().noneMatch(op -> op.kind() == SemanticOpKind.BOUNDARY),
            "CLASS_FIELD writes carry zero write-check boundaries here "
                + "(CLASS_FIELD_ASSIGNMENT stays E9's)");
        checkParentage("the container child", containerOp, chainOp.opId());
        checkParentage("the value child", valueOp, chainOp.opId());
        checkParentage("the commit child", commitOp, chainOp.opId());
    }

    // =========================================================================
    // 8. The four DELETE rows — result none, no RHS
    // =========================================================================

    static void testDeleteArms() {
        System.out.println("-- DELETE rows: table member, table index, array index, class "
            + "field --");

        // (a) Table member delete: [containerOp, MEMBER_DELETE].
        {
            IdentifierExpr receiver = ident("t", 8);
            MemberAccessExpr target = member(receiver, "y", 10);
            DeleteStatement delete = delete(target);
            SymbolTable table = new SymbolTable();
            table.define("t", new Symbol.VariableSymbol("t", Type.Table.INSTANCE, false));
            CheckResult checked = checks(
                Map.of(receiver, Type.Table.INSTANCE, target, Type.Table.INSTANCE), table);
            SemanticLowerer.ModuleLowerer lowerer = lowerer(checked);
            lowerer.openForEachScope("t");
            lowerer.lowerDelete(delete);
            lowerer.closeForEachScope();
            List<SemanticOp> ops = lowerer.ops();
        check(AddressChainProtocol.validate(protocolUnit(ops)).isEmpty(), "lowered arm passes address protocol");
            check(ops.size() == 3,
                "delete t.y produces BINDING_LOAD + MEMBER_DELETE + DELETE = 3 ops; got "
                    + ops.size());
            if (ops.size() == 3) {
                SemanticOp containerOp = ops.get(0);
                SemanticOp commitOp = ops.get(1);
                SemanticOp chainOp = ops.get(2);
                check(commitOp.kind() == SemanticOpKind.MEMBER_DELETE
                        && ((KindPayload.MemberDeletePayload) commitOp.payload()).table()
                            .equals(containerOp.result())
                        && "y".equals(
                            ((KindPayload.MemberDeletePayload) commitOp.payload()).key()),
                    "MEMBER_DELETE references the resolved table and the literal key 'y'");
                check(chainOp.kind() == SemanticOpKind.DELETE
                        && ((KindPayload.DeletePayload) chainOp.payload()).targetKind()
                            == DeleteTargetKind.TABLE_SLOT
                        && ((KindPayload.DeletePayload) chainOp.payload()).childOps()
                            .equals(List.of(containerOp.opId(), commitOp.opId())),
                    "the chain is DELETE TABLE_SLOT [containerOp, MEMBER_DELETE]");
                check(chainOp.result() == null && chainOp.resultType() == null,
                    "DELETE publishes none (A-D6)");
                checkParentage("the container child", containerOp, chainOp.opId());
                checkParentage("the commit child", commitOp, chainOp.opId());
            }
        }

        // (b) Table index delete: [containerOp, keyOp, normalize(TABLE_WRITE), INDEX_DELETE].
        {
            IdentifierExpr receiver = ident("t", 8);
            LiteralExpr key = stringLit("k", 10);
            IndexExpr target = index(receiver, key, 10);
            DeleteStatement delete = delete(target);
            SymbolTable table = new SymbolTable();
            table.define("t", new Symbol.VariableSymbol("t", Type.Table.INSTANCE, false));
            CheckResult checked = checks(Map.of(receiver, Type.Table.INSTANCE, key,
                Type.String.INSTANCE, target, Type.Table.INSTANCE), table);
            SemanticLowerer.ModuleLowerer lowerer = lowerer(checked);
            lowerer.openForEachScope("t");
            lowerer.lowerDelete(delete);
            lowerer.closeForEachScope();
            List<SemanticOp> ops = lowerer.ops();
        check(AddressChainProtocol.validate(protocolUnit(ops)).isEmpty(), "lowered arm passes address protocol");
            check(ops.size() == 5,
                "delete t[\"k\"] produces BINDING_LOAD + CONST + INDEX_NORMALIZE + "
                    + "INDEX_DELETE + DELETE = 5 ops; got " + ops.size());
            if (ops.size() == 5) {
                SemanticOp containerOp = ops.get(0);
                SemanticOp keyOp = ops.get(1);
                SemanticOp normalizeOp = ops.get(2);
                SemanticOp commitOp = ops.get(3);
                SemanticOp chainOp = ops.get(4);
                KindPayload.IndexNormalizePayload normalize =
                    (KindPayload.IndexNormalizePayload) normalizeOp.payload();
                check(normalizeOp.kind() == SemanticOpKind.INDEX_NORMALIZE
                        && normalize.mode() == IndexMode.TABLE_WRITE
                        && normalizeOp.failurePolicy() == FailurePolicyId.NO_DEAL_FAILURE
                        && normalize.rawKey().equals(keyOp.result())
                        && normalize.currentLength().equals(keyOp.result()),
                    "the table delete normalize is TABLE_WRITE with the raw key identity "
                        + "(delete is a mutation context; the closed IndexMode set is not "
                        + "extended)");
                check(normalizeOp.resultType().equals(RuntimeDescriptor.String.INSTANCE),
                    "the table delete normalize result type is string (TableSlot)");
                KindPayload.IndexDeletePayload commit =
                    (KindPayload.IndexDeletePayload) commitOp.payload();
                check(commitOp.kind() == SemanticOpKind.INDEX_DELETE
                        && commit.container().equals(containerOp.result())
                        && commit.slot().equals(normalizeOp.result()),
                    "INDEX_DELETE references the resolved container and the normalized slot");
                check(chainOp.kind() == SemanticOpKind.DELETE
                        && ((KindPayload.DeletePayload) chainOp.payload()).targetKind()
                            == DeleteTargetKind.TABLE_SLOT
                        && ((KindPayload.DeletePayload) chainOp.payload()).childOps()
                            .equals(List.of(containerOp.opId(), keyOp.opId(),
                                normalizeOp.opId(), commitOp.opId())),
                    "the chain is DELETE TABLE_SLOT [containerOp, keyOp, normalizeOp, "
                        + "INDEX_DELETE]");
                check(chainOp.result() == null && chainOp.resultType() == null,
                    "DELETE publishes none");
                for (SemanticOp child : ops.subList(0, 4)) {
                    checkParentage(child.kind().name() + " child", child, chainOp.opId());
                }
                check(ops.stream().noneMatch(op -> op.kind() == SemanticOpKind.BOUNDARY),
                    "table deletes run no bounds boundary (A-D4/A-D9)");
            }
        }

        // (c) Array index delete: [containerOp, keyOp, lengthOp, normalize(ARRAY_WRITE),
        // ARRAY_ELEMENT_DELETE, INDEX_DELETE].
        {
            IdentifierExpr receiver = ident("a", 8);
            LiteralExpr key = intLit(0, 10);
            IndexExpr target = index(receiver, key, 10);
            DeleteStatement delete = delete(target);
            Type.Array arrayType = new Type.Array(Type.Number.INSTANCE);
            SymbolTable table = new SymbolTable();
            table.define("a", new Symbol.VariableSymbol("a", arrayType, false));
            CheckResult checked = checks(Map.of(receiver, arrayType, key, Type.Number.INSTANCE,
                target, Type.Number.INSTANCE), table);
            SemanticLowerer.ModuleLowerer lowerer = lowerer(checked);
            lowerer.openForEachScope("a");
            lowerer.lowerDelete(delete);
            lowerer.closeForEachScope();
            List<SemanticOp> ops = lowerer.ops();
        check(AddressChainProtocol.validate(protocolUnit(ops)).isEmpty(), "lowered arm passes address protocol");
            check(ops.size() == 7,
                "delete a[0] produces BINDING_LOAD + CONST + ARRAY_LENGTH + "
                    + "INDEX_NORMALIZE + BOUNDARY + INDEX_DELETE + DELETE = 7 ops; got "
                    + ops.size());
            if (ops.size() == 7) {
                SemanticOp containerOp = ops.get(0);
                SemanticOp keyOp = ops.get(1);
                SemanticOp lengthOp = ops.get(2);
                SemanticOp normalizeOp = ops.get(3);
                SemanticOp boundaryOp = ops.get(4);
                SemanticOp commitOp = ops.get(5);
                SemanticOp chainOp = ops.get(6);
                check(lengthOp.kind() == SemanticOpKind.ARRAY_LENGTH
                        && ((KindPayload.ArrayLengthPayload) lengthOp.payload()).arrayValue()
                            .equals(containerOp.result()),
                    "the length child is an ARRAY_LENGTH read of the resolved receiver");
                KindPayload.IndexNormalizePayload normalize =
                    (KindPayload.IndexNormalizePayload) normalizeOp.payload();
                check(normalize.mode() == IndexMode.ARRAY_WRITE
                        && normalize.rawKey().equals(keyOp.result())
                        && normalize.currentLength().equals(lengthOp.result())
                        && normalizeOp.failurePolicy() == FailurePolicyId.NO_DEAL_FAILURE,
                    "the array delete normalize is pure ARRAY_WRITE over the key and the "
                        + "normalize-time length");
                check(boundaryOp.kind() == SemanticOpKind.BOUNDARY
                        && boundaryOp.failurePolicy() == FailurePolicyId.ARRAY_DELETE_BOUNDS,
                    "exactly one ARRAY_ELEMENT_DELETE bounds boundary with "
                        + "ARRAY_DELETE_BOUNDS");
                KindPayload.BoundaryPayload boundary =
                    (KindPayload.BoundaryPayload) boundaryOp.payload();
                check(boundary.kind() == BoundaryKind.ARRAY_ELEMENT_DELETE
                        && boundary.input().equals(normalizeOp.result()),
                    "the delete boundary input is the normalized index (E8002 'array index "
                        + "out of bounds' for <0/>length at the delete-target origin)");
                checkCanonicalRealization("the ARRAY_ELEMENT_DELETE boundary",
                    boundary.realization());
                check(boundaryOp.origin().span().startColumn() == 10,
                    "the delete boundary carries the delete-target (index) span");
                KindPayload.IndexDeletePayload commit =
                    (KindPayload.IndexDeletePayload) commitOp.payload();
                check(commitOp.kind() == SemanticOpKind.INDEX_DELETE
                        && commit.container().equals(containerOp.result())
                        && commit.slot().equals(normalizeOp.result()),
                    "INDEX_DELETE references the resolved container and the normalized slot "
                        + "(no nil write on a failed bounds check)");
                check(chainOp.kind() == SemanticOpKind.DELETE
                        && ((KindPayload.DeletePayload) chainOp.payload()).targetKind()
                            == DeleteTargetKind.ARRAY_SLOT
                        && ((KindPayload.DeletePayload) chainOp.payload()).childOps()
                            .equals(List.of(containerOp.opId(), keyOp.opId(),
                                lengthOp.opId(), normalizeOp.opId(), boundaryOp.opId(),
                                commitOp.opId())),
                    "the chain is DELETE ARRAY_SLOT [containerOp, keyOp, lengthOp, "
                        + "normalizeOp, ARRAY_ELEMENT_DELETE boundaryOp, INDEX_DELETE]");
                check(chainOp.result() == null && chainOp.resultType() == null,
                    "DELETE publishes none");
                for (SemanticOp child : ops.subList(0, 6)) {
                    checkParentage(child.kind().name() + " child", child, chainOp.opId());
                }
            }
        }

        // (d) Class field delete: [containerOp, FIELD_DELETE].
        {
            IdentifierExpr receiver = ident("c", 8);
            MemberAccessExpr target = member(receiver, "f", 10);
            DeleteStatement delete = delete(target);
            Type.Class classType = IdentityTestFixtures.classType("C", "main");
            SymbolTable table = new SymbolTable();
            table.define("c", new Symbol.VariableSymbol("c", classType, false));
            CheckResult checked = checks(
                Map.of(receiver, classType, target, Type.Number.INSTANCE), table);
            SemanticLowerer.ModuleLowerer lowerer = lowerer(checked);
            lowerer.openForEachScope("c");
            lowerer.lowerDelete(delete);
            lowerer.closeForEachScope();
            List<SemanticOp> ops = lowerer.ops();
        check(AddressChainProtocol.validate(protocolUnit(ops)).isEmpty(), "lowered arm passes address protocol");
            check(ops.size() == 3,
                "delete c.f produces BINDING_LOAD + FIELD_DELETE + DELETE = 3 ops; got "
                    + ops.size());
            if (ops.size() == 3) {
                SemanticOp containerOp = ops.get(0);
                SemanticOp commitOp = ops.get(1);
                SemanticOp chainOp = ops.get(2);
                KindPayload.FieldDeletePayload commit =
                    (KindPayload.FieldDeletePayload) commitOp.payload();
                check(commitOp.kind() == SemanticOpKind.FIELD_DELETE
                        && commit.classValue().equals(containerOp.result())
                        && commit.classId().equals(new ClassId("main", "C"))
                        && "f".equals(commit.field()),
                    "FIELD_DELETE references the resolved class value, the class identity, "
                        + "and the field name");
                check(chainOp.kind() == SemanticOpKind.DELETE
                        && ((KindPayload.DeletePayload) chainOp.payload()).targetKind()
                            == DeleteTargetKind.CLASS_FIELD
                        && ((KindPayload.DeletePayload) chainOp.payload()).childOps()
                            .equals(List.of(containerOp.opId(), commitOp.opId())),
                    "the chain is DELETE CLASS_FIELD [containerOp, FIELD_DELETE]");
                check(chainOp.result() == null && chainOp.resultType() == null,
                    "DELETE publishes none");
                check(ops.stream().noneMatch(op -> op.kind() == SemanticOpKind.BOUNDARY),
                    "class deletes run no bounds boundary");
            }
        }
    }

    // =========================================================================
    // 9. Nested chains — an inner ASSIGN as the outer chain's value child
    // =========================================================================

    static void testNestedChainParentage() {
        System.out.println("-- Nested chains: x = (y = 1) parentage --");

        IdentifierExpr outerTarget = ident("x", 1);
        IdentifierExpr innerTarget = ident("y", 5);
        LiteralExpr value = intLit(1, 9);
        AssignmentExpr inner = assign(innerTarget, value);
        AssignmentExpr outer = assign(outerTarget, inner);
        SymbolTable table = new SymbolTable();
        table.define("x", new Symbol.VariableSymbol("x", Type.Number.INSTANCE, false));
        table.define("y", new Symbol.VariableSymbol("y", Type.Number.INSTANCE, false));
        CheckResult checked = checks(Map.of(outerTarget, Type.Number.INSTANCE, innerTarget,
            Type.Number.INSTANCE, value, Type.Number.INSTANCE, inner, Type.Number.INSTANCE, outer, Type.Number.INSTANCE), table);

        SemanticLowerer.ModuleLowerer lowerer = lowerer(checked);
        ValueId result = lowerer.lowerAssignment(outer);
        List<SemanticOp> ops = lowerer.ops();
        check(AddressChainProtocol.validate(protocolUnit(ops)).isEmpty(), "lowered arm passes address protocol");
        check(ops.size() == 7,
            "x = (y = 1) produces 7 ops — the inner chain's four ops plus the outer "
                + "chain's boundary/store/op (the inner ASSIGN op is itself the outer "
                + "chain's valueOp); got " + ops.size());
        if (ops.size() != 7) {
            return;
        }
        // Inner chain: ops 0..3; outer boundary/store/chain: ops 4..6.
        SemanticOp innerChain = null;
        SemanticOp outerChain = null;
        for (SemanticOp op : ops) {
            if (op.kind() == SemanticOpKind.ASSIGN) {
                if (innerChain == null) {
                    innerChain = op;
                } else {
                    outerChain = op;
                }
            }
        }
        check(innerChain != null && outerChain != null,
            "the session produced both chain ops");
        if (innerChain == null || outerChain == null) {
            return;
        }
        check(outerChain.opId().equals(innerChain.origin().parentOpId()),
            "the inner ASSIGN op records the outer chain op as parentOpId (assignment is "
                + "an expression; nested chains parent to the enclosing chain)");
        KindPayload.AssignPayload outerPayload =
            (KindPayload.AssignPayload) outerChain.payload();
        check(outerPayload.childOps().get(0).equals(innerChain.opId()),
            "the outer chain's valueOp is the inner ASSIGN op");
        check(outerChain.result().equals(innerChain.result()),
            "the outer ASSIGN publishes the inner chain's committed value identity");
        check(result.equals(innerChain.result()),
            "lowerAssignment returns the committed value");
        check(AddressChainProtocol.validate(protocolUnit(ops)).isEmpty(), "nested assignment chain protocol admission");
        KindPayload.AssignPayload innerPayload =
            (KindPayload.AssignPayload) innerChain.payload();
        check(innerPayload.childOps().size() == 3,
            "the inner chain keeps its own three-child VARIABLE shape");
        for (SemanticOp op : ops) {
            if (op.opId().equals(innerPayload.childOps().get(0))
                    || op.opId().equals(innerPayload.childOps().get(1))
                    || op.opId().equals(innerPayload.childOps().get(2))) {
                checkParentage("inner child", op, innerChain.opId());
            }
        }
    }

    // =========================================================================
    // 10. The E3018 gate before lowering
    // =========================================================================

    static void testE3018StringKeyGate() {
        System.out.println("-- E3018: table index write/delete keys must be static string --");

        // t[0] = 1: the checker rejects with E3018 at the index span before
        // any lowering; the write-context checkIndex gate fires identically
        // for the delete form.
        CheckedSlice writeSlice = checkSlice("let t = {x: 1};\nt[0] = 1;\n");
        if (writeSlice != null) {
            check(writeSlice.checks().hasErrors()
                    && writeSlice.checks().diagnostics().stream()
                        .anyMatch(d -> d.diagnosticCode() == DiagnosticCode.E3018),
                "t[0] = 1 fails E3018 in the checker (no ASSIGN op is ever produced)");
            if (writeSlice.checks().hasErrors()) {
                Optional<CompilerDiagnostic> e3018 = writeSlice.checks().diagnostics()
                    .stream()
                    .filter(d -> d.diagnosticCode() == DiagnosticCode.E3018)
                    .findFirst();
                check(e3018.isPresent() && e3018.get().severity().equals("error"),
                    "the E3018 diagnostic is error severity at the index expression span");
            }
        }
        CheckedSlice deleteSlice = checkSlice("let t = {x: 1};\ndelete t[0];\n");
        if (deleteSlice != null) {
            check(deleteSlice.checks().hasErrors()
                    && deleteSlice.checks().diagnostics().stream()
                        .anyMatch(d -> d.diagnosticCode() == DiagnosticCode.E3018),
                "delete t[0] fails E3018 in the checker (no DELETE op is ever produced)");
        }

        // t["k"] = 1 and delete t["k"]: checker-clean, and the assignment
        // arm lowers with the total string key (TableSlot {key: "k"}).
        CheckedSlice stringKeySlice = checkSlice("let t = {x: 1};\nt[\"k\"] = 1;\n");
        if (stringKeySlice != null) {
            check(!stringKeySlice.checks().hasErrors(),
                "t[\"k\"] = 1 checks cleanly (static string key)");
            AssignmentExpr assignment = null;
            for (StatementNode statement : stringKeySlice.program().statements()) {
                if (statement instanceof ExpressionStatement expression
                        && expression.expr() instanceof AssignmentExpr candidate) {
                    assignment = candidate;
                }
            }
            check(assignment != null, "the slice carries the assignment expression");
            if (assignment != null) {
                CheckResult checks = stringKeySlice.checks();
                SemanticLowerer.ModuleLowerer lowerer = lowerer(checks);
                lowerer.openForEachScope("t");
                lowerer.lowerAssignment(assignment);
                lowerer.closeForEachScope();
                List<SemanticOp> ops = lowerer.ops();
        check(AddressChainProtocol.validate(protocolUnit(ops)).isEmpty(), "lowered arm passes address protocol");
                Optional<SemanticOp> normalize = ops.stream()
                    .filter(op -> op.kind() == SemanticOpKind.INDEX_NORMALIZE)
                    .findFirst();
                check(normalize.isPresent()
                        && ((KindPayload.IndexNormalizePayload) normalize.get().payload())
                            .mode() == IndexMode.TABLE_WRITE
                        && normalize.get().resultType()
                            .equals(RuntimeDescriptor.String.INSTANCE),
                    "t[\"k\"] = 1 lowers with the TABLE_WRITE normalize whose result type "
                        + "is string — TableSlot {key: \"k\"} is total, no coercion");
                Optional<SemanticOp> chain = ops.stream()
                    .filter(op -> op.kind() == SemanticOpKind.ASSIGN)
                    .findFirst();
                check(chain.isPresent()
                        && ((KindPayload.AssignPayload) chain.get().payload()).targetKind()
                            == AssignTargetKind.TABLE_SLOT,
                    "the string-keyed index assignment lowers to an ASSIGN TABLE_SLOT chain");
            }
        }
        CheckedSlice stringKeyDeleteSlice = checkSlice("let t = {x: 1};\ndelete t[\"k\"];\n");
        if (stringKeyDeleteSlice != null) {
            check(!stringKeyDeleteSlice.checks().hasErrors(),
                "delete t[\"k\"] checks cleanly (static string key)");
            DeleteStatement delete = null;
            for (StatementNode statement : stringKeyDeleteSlice.program().statements()) {
                if (statement instanceof DeleteStatement candidate) {
                    delete = candidate;
                }
            }
            check(delete != null, "the slice carries the delete statement");
            if (delete != null) {
                CheckResult checks = stringKeyDeleteSlice.checks();
                SemanticLowerer.ModuleLowerer lowerer = lowerer(checks);
                lowerer.openForEachScope("t");
                lowerer.lowerDelete(delete);
                lowerer.closeForEachScope();
                List<SemanticOp> ops = lowerer.ops();
        check(AddressChainProtocol.validate(protocolUnit(ops)).isEmpty(), "lowered arm passes address protocol");
                Optional<SemanticOp> chain = ops.stream()
                    .filter(op -> op.kind() == SemanticOpKind.DELETE)
                    .findFirst();
                check(chain.isPresent()
                        && ((KindPayload.DeletePayload) chain.get().payload()).targetKind()
                            == DeleteTargetKind.TABLE_SLOT
                        && ((KindPayload.DeletePayload) chain.get().payload()).childOps()
                            .size() == 4,
                    "delete t[\"k\"] lowers to the four-child DELETE TABLE_SLOT index chain");
            }
        }

        // .length assignment never reaches lowering (E3017 frontend
        // rejection).
        CheckedSlice lengthSlice = checkSlice("let xs: number[] = [1];\nxs.length = 2;\n");
        if (lengthSlice != null) {
            check(lengthSlice.checks().hasErrors()
                    && lengthSlice.checks().diagnostics().stream()
                        .anyMatch(d -> d.diagnosticCode() == DiagnosticCode.E3017),
                "xs.length = 2 fails E3017 in the checker and never reaches lowering");
        }
    }

    // =========================================================================
    // 11. Every lowered chain passes the production-time protocol;
    //     hand-corrupted variants fail E6005 through the same authority
    // =========================================================================

    static void testProtocolValidationOfLoweredChains() {
        System.out.println("-- Protocol: lowered chains pass; corrupted units fail E6005 --");

        // Lower one VARIABLE chain and one ARRAY_SLOT delete chain into a
        // unit, then validate it through AddressChainProtocol.
        IdentifierExpr target = ident("x", 1);
        LiteralExpr value = intLit(1, 5);
        AssignmentExpr assignment = assign(target, value);
        IdentifierExpr receiver = ident("a", 1);
        LiteralExpr key = intLit(0, 3);
        IndexExpr indexTarget = index(receiver, key, 3);
        DeleteStatement delete = delete(indexTarget);
        Type.Array arrayType = new Type.Array(Type.Number.INSTANCE);
        SymbolTable table = new SymbolTable();
        table.define("x", new Symbol.VariableSymbol("x", Type.Number.INSTANCE, false));
        table.define("a", new Symbol.VariableSymbol("a", arrayType, false));
        CheckResult checked = checks(Map.of(target, Type.Number.INSTANCE, value,
            Type.Number.INSTANCE, receiver, arrayType, key, Type.Number.INSTANCE, indexTarget,
            Type.Number.INSTANCE, assignment, Type.Number.INSTANCE), table);
        SemanticLowerer.ModuleLowerer lowerer = lowerer(checked);
        lowerer.openForEachScope("a");
        lowerer.lowerAssignment(assignment);
        lowerer.lowerDelete(delete);
        lowerer.closeForEachScope();

        Map<ConstructKind, List<SemanticOpKind>> coverage = Map.of(
            ConstructKind.SCALAR_LITERAL, ConstructKind.SCALAR_LITERAL.mappedOpKinds(),
            ConstructKind.IDENTIFIER, ConstructKind.IDENTIFIER.mappedOpKinds(),
            ConstructKind.ASSIGNMENT, ConstructKind.ASSIGNMENT.mappedOpKinds(),
            ConstructKind.DELETE, ConstructKind.DELETE.mappedOpKinds());
        LoweredModuleUnit unit = lowerer.buildUnit(coverage, List.of(),
            SemanticProfile.DEAL_V1_5_BINARY64, INTERFACE_HASH, REGISTRY_HASH);
        // The manual chain slice lowers no declaration arm, so its binding
        // cells are absent; the common-admission head is the applicable
        // closed-rule gate of this partial slice (the complete admission of
        // a full closure runs the bindings owner over the produced cells).
        Optional<CompilerDiagnostic> validation =
            SemanticIrValidator.validateUnitHead(unit, facts());
        check(validation.isEmpty(),
            "the two-chain unit passes the common-admission head: " + validation);
        if (validation.isPresent()) {
            return;
        }
        Optional<CompilerDiagnostic> protocol = AddressChainProtocol.validate(unit);
        check(protocol.isEmpty(),
            "every lowered chain passes AddressChainProtocol: " + protocol);
        if (protocol.isPresent()) {
            return;
        }

        // Hand-corrupted variants through the same protocol authority:
        // (a) missing lengthOp on the array delete chain → ADDRESS_CHAIN_SHAPE.
        SemanticOp arrayDeleteChain = null;
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.DELETE) {
                arrayDeleteChain = op;
            }
        }
        check(arrayDeleteChain != null, "the unit carries the DELETE ARRAY_SLOT chain");
        if (arrayDeleteChain != null) {
            List<OpId> original = ((KindPayload.DeletePayload) arrayDeleteChain.payload())
                .childOps();
            List<OpId> missingLength = new ArrayList<>(original);
            missingLength.remove(2); // the ARRAY_LENGTH lengthOp
            KindPayload.DeletePayload corruptedPayload =
                new KindPayload.DeletePayload(DeleteTargetKind.ARRAY_SLOT, missingLength);
            LoweredModuleUnit corrupted = replacePayload(unit, arrayDeleteChain,
                corruptedPayload);
            Optional<CompilerDiagnostic> rejected =
                AddressChainProtocol.validate(corrupted);
            checkE6005(rejected, "ADDRESS_CHAIN_SHAPE",
                "a hand-corrupted array delete chain without its lengthOp fails E6005 "
                    + "ADDRESS_CHAIN_SHAPE");

            // (b) commit not last (commit first) → ADDRESS_CHAIN_SHAPE.
            List<OpId> commitFirst = new ArrayList<>(original);
            OpId commit = commitFirst.remove(commitFirst.size() - 1);
            commitFirst.add(0, commit);
            LoweredModuleUnit corruptedCommit = replacePayload(unit, arrayDeleteChain,
                new KindPayload.DeletePayload(DeleteTargetKind.ARRAY_SLOT, commitFirst));
            Optional<CompilerDiagnostic> rejectedCommit =
                AddressChainProtocol.validate(corruptedCommit);
            checkE6005(rejectedCommit, "ADDRESS_CHAIN_SHAPE",
                "a hand-corrupted chain with the commit not last fails E6005 "
                    + "ADDRESS_CHAIN_SHAPE");

            // (c) a duplicated child in one chain → SINGLE_EVALUATION.
            List<OpId> duplicated = new ArrayList<>(original);
            duplicated.add(1, original.get(1));
            LoweredModuleUnit corruptedDuplicate = replacePayload(unit, arrayDeleteChain,
                new KindPayload.DeletePayload(DeleteTargetKind.ARRAY_SLOT, duplicated));
            Optional<CompilerDiagnostic> rejectedDuplicate =
                AddressChainProtocol.validate(corruptedDuplicate);
            checkE6005(rejectedDuplicate, "SINGLE_EVALUATION",
                "a hand-corrupted chain listing a producing child twice fails E6005 "
                    + "SINGLE_EVALUATION");
        }
    }

    /** Copies the unit with one op's payload replaced (protocol-only corruption). */
    private static LoweredModuleUnit replacePayload(LoweredModuleUnit unit, SemanticOp chain,
                                                    KindPayload payload) {
        List<SemanticOp> ops = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            if (op.opId().equals(chain.opId())) {
                OperationContractSnapshot placeholder = new OperationContractSnapshot(
                    OperationContractSnapshot.VERSION, op.kind(), op.resultType(),
                    op.operandTypes(), null, payload, op.failurePolicy(), List.of(),
                    "placeholder");
                String digest = ContractSnapshotCanonicalizer.digest(placeholder);
                OperationContractSnapshot contract = new OperationContractSnapshot(
                    OperationContractSnapshot.VERSION, op.kind(), op.resultType(),
                    op.operandTypes(), null, payload, op.failurePolicy(), List.of(),
                    digest);
                ops.add(new SemanticOp(op.opId(), op.kind(), op.origin(), op.result(),
                    op.resultType(), op.operands(), op.operandTypes(), payload,
                    op.failurePolicy(), contract));
            } else {
                ops.add(op);
            }
        }
        return new LoweredModuleUnit(unit.formatVersion(), unit.semanticProfile(),
            unit.moduleId(), unit.interfaceHash(), unit.loweringContextHash(),
            unit.requiredCapabilities(), unit.constructCoverage(), Map.of(), Map.of(),
            new ModuleInitPlan(List.of(), new deal.semantic.ir.BlockId(0)),
            ExportPlan.empty(), Map.of(), ops, unit.bodyTable(),
            unit.classFactories(), unit.classBindingFacts());
    }

    private static void checkE6005(Optional<CompilerDiagnostic> diagnostic, String rule,
                                   String what) {
        check(diagnostic.isPresent(), what);
        if (diagnostic.isEmpty()) {
            return;
        }
        CompilerDiagnostic d = diagnostic.get();
        check("E6005".equals(d.code()) && d.diagnosticCode() == DiagnosticCode.E6005,
            rule + " diagnostic code is E6005");
        check(d.message().contains(rule), rule + " message names the pinned rule");
        check(d.message().contains("module 'main'"), rule + " message names the module");
        check(d.message().contains("capability EVALUATION_ORDER"),
            rule + " message names capability EVALUATION_ORDER");
    }

    // =========================================================================
    // 12. The module-level seam — corpus, protocol pass, determinism
    // =========================================================================

    static void testModuleCorpusAndDeterminism() {
        System.out.println("-- Module seam: delete corpus + nested VARIABLE chain, "
            + "protocol, determinism --");

        CheckedSlice slice = checkSlice("""
            delete {x: 1}.y;
            delete {x: 1}["z"];
            delete [1, 2][0];
            for (let k of "a") {
              delete {x: 1}[k];
              delete {x: 1}[k = "b"];
            }
            """);
        if (slice == null) {
            return;
        }
        check(!slice.checks().hasErrors(),
            "the corpus checks cleanly: " + slice.checks().diagnostics());
        if (slice.checks().hasErrors()) {
            return;
        }
        Map<ConstructKind, List<SemanticOpKind>> coverage = Map.of(
            ConstructKind.SCALAR_LITERAL, ConstructKind.SCALAR_LITERAL.mappedOpKinds(),
            ConstructKind.IDENTIFIER, ConstructKind.IDENTIFIER.mappedOpKinds(),
            ConstructKind.ARRAY_OBJECT_LITERAL,
            ConstructKind.ARRAY_OBJECT_LITERAL.mappedOpKinds(),
            ConstructKind.IF_WHILE_FOR_FOR_OF,
            ConstructKind.IF_WHILE_FOR_FOR_OF.mappedOpKinds(),
            ConstructKind.ASSIGNMENT, ConstructKind.ASSIGNMENT.mappedOpKinds(),
            ConstructKind.DELETE, ConstructKind.DELETE.mappedOpKinds());

        SemanticLowerer.LoweringResult first = SemanticLowerer.lowerModule(moduleOf(slice),
            SemanticProfile.DEAL_V1_5_BINARY64, coverage, INTERFACE_HASH, REGISTRY_HASH,
            SemanticIdAllocator.over(List.of(MODULE)));
        check(first != null && !first.hasErrors() && first.unit() != null,
            "the corpus lowers to a validated unit: " + (first == null ? "null"
                : first.diagnostics()));
        if (first == null || first.hasErrors()) {
            return;
        }
        LoweredModuleUnit unit = first.unit();
        check(first.table() == unit.bodyTable(),
            "the single-module result's table is the unit's own bodyTable, not a "
                + "detached copy");
        Optional<CompilerDiagnostic> protocol = AddressChainProtocol.validate(unit);
        check(protocol.isEmpty(),
            "the corpus unit passes the production-time address-chain protocol: "
                + protocol);
        List<SemanticOpKind> kinds = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            kinds.add(op.kind());
        }
        check(kinds.contains(SemanticOpKind.ASSIGN)
                && kinds.contains(SemanticOpKind.DELETE)
                && kinds.contains(SemanticOpKind.BINDING_STORE)
                && kinds.contains(SemanticOpKind.MEMBER_DELETE)
                && kinds.contains(SemanticOpKind.INDEX_DELETE)
                && kinds.contains(SemanticOpKind.ARRAY_LENGTH)
                && kinds.contains(SemanticOpKind.INDEX_NORMALIZE),
            "the corpus unit carries every recorded row's mapped kind plus the chain "
                + "machinery: " + kinds);
        long boundaries = unit.ops().stream()
            .filter(op -> op.kind() == SemanticOpKind.BOUNDARY)
            .count();
        long elementDeletes = unit.ops().stream()
            .filter(op -> op.kind() == SemanticOpKind.BOUNDARY
                && ((KindPayload.BoundaryPayload) op.payload()).kind()
                    == BoundaryKind.ARRAY_ELEMENT_DELETE)
            .count();
        long variableAssignments = unit.ops().stream()
            .filter(op -> op.kind() == SemanticOpKind.BOUNDARY
                && ((KindPayload.BoundaryPayload) op.payload()).kind()
                    == BoundaryKind.VARIABLE_ASSIGNMENT)
            .count();
        long literalElements = unit.ops().stream()
            .filter(op -> op.kind() == SemanticOpKind.BOUNDARY
                && ((KindPayload.BoundaryPayload) op.payload()).kind()
                    == BoundaryKind.ARRAY_LITERAL_ELEMENT)
            .count();
        check(boundaries == 4 && elementDeletes == 1 && variableAssignments == 1
                && literalElements == 2,
            "the corpus carries exactly the one ARRAY_ELEMENT_DELETE bounds boundary, the "
                + "one VARIABLE_ASSIGNMENT boundary, and the two ARRAY_LITERAL_ELEMENT "
                + "children of the [1, 2] receiver (got " + boundaries + " boundaries: "
                + elementDeletes + " delete, " + variableAssignments + " variable, "
                + literalElements + " literal)");
        SemanticOp variableAssign = null;
        SemanticOp deleteWithNestedKey = null;
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.ASSIGN && variableAssign == null) {
                variableAssign = op;
            }
            if (op.kind() == SemanticOpKind.DELETE) {
                KindPayload.DeletePayload payload =
                    (KindPayload.DeletePayload) op.payload();
                if (payload.childOps().size() == 4) {
                    deleteWithNestedKey = op;
                }
            }
        }
        check(variableAssign != null && deleteWithNestedKey != null,
            "the corpus carries the VARIABLE ASSIGN and the delete whose key is the "
                + "nested chain");
        if (variableAssign != null && deleteWithNestedKey != null) {
            KindPayload.DeletePayload deletePayload =
                (KindPayload.DeletePayload) deleteWithNestedKey.payload();
            check(variableAssign.opId().equals(deletePayload.childOps().get(1)),
                "the nested VARIABLE chain is the delete chain's keyOp");
            check(deleteWithNestedKey.opId().equals(variableAssign.origin().parentOpId()),
                "the nested ASSIGN op records the enclosing DELETE chain as parentOpId");
        }

        // Determinism: a fresh lowering produces byte-identical dumps.
        SemanticLowerer.LoweringResult second = SemanticLowerer.lowerModule(moduleOf(slice),
            SemanticProfile.DEAL_V1_5_BINARY64, coverage, INTERFACE_HASH, REGISTRY_HASH,
            SemanticIdAllocator.over(List.of(MODULE)));
        check(second != null && !second.hasErrors(),
            "the repeated lowering validates again");
        if (second != null && !second.hasErrors()) {
            byte[] firstDump = SemanticIrDumper.dumpModule(unit);
            byte[] secondDump = SemanticIrDumper.dumpModule(second.unit());
            check(Arrays.equals(firstDump, secondDump),
                "two fresh lowerings produce byte-identical unit dumps (determinism)");
        }
    }

    // =========================================================================
    // Runner
    // =========================================================================

    private static List<SemanticOp> protocolSeed(int arm) {
        IdentifierExpr receiver = ident("r", 1), variable = ident("x", 2);
        LiteralExpr key = arm == 1 ? stringLit("k", 3) : intLit(0, 3);
        LiteralExpr value = intLit(7, 5);
        Type receiverType = arm == 1 ? Type.Table.INSTANCE : new Type.Array(Type.Number.INSTANCE);
        ExpressionNode target = arm == 0 ? variable : arm == 1 ? member(receiver, "k", 3) : index(receiver, key, 3);
        SymbolTable symbols = new SymbolTable();
        symbols.define("r", new Symbol.VariableSymbol("r", receiverType, false));
        symbols.define("x", new Symbol.VariableSymbol("x", Type.Number.INSTANCE, false));
        Map<ExpressionNode, Type> types = new java.util.IdentityHashMap<>();
        types.put(receiver, receiverType); types.put(variable, Type.Number.INSTANCE);
        types.put(key, arm == 1 ? Type.String.INSTANCE : Type.Number.INSTANCE);
        types.put(value, Type.Number.INSTANCE); types.put(target, arm == 1 ? Type.Table.INSTANCE : Type.Number.INSTANCE);
        AssignmentExpr assignment = assign(target, value);
        types.put(assignment, Type.Number.INSTANCE);
        SemanticLowerer.ModuleLowerer producer = lowerer(checks(types, symbols));
        if (arm != 0) { producer.openForEachScope("r"); }
        producer.lowerAssignment(assignment);
        if (arm != 0) { producer.closeForEachScope(); }
        List<SemanticOp> ops = producer.ops();
        check(AddressChainProtocol.validate(protocolUnit(ops)).isEmpty(), "lowered protocol seed " + arm);
        return ops;
    }

    private static LoweredModuleUnit protocolUnit(List<SemanticOp> ops) {
        LoweredModuleUnit unit = lowerer(checks(Map.of(), new SymbolTable()))
            .buildUnit(Map.of(), List.of(), SemanticProfile.DEAL_V1_5_BINARY64,
                INTERFACE_HASH, REGISTRY_HASH);
        // The membership of the detached fixture ops: one block that is the
        // plan's init block (a root of the block tree), so the protocol and
        // rule-family fixtures exercise exactly their subject op.
        BlockId initBlock = unit.moduleInit().initBlock();
        Map<BlockId, List<OpId>> blockOps = new LinkedHashMap<>();
        Map<OpId, BlockId> opBlocks = new LinkedHashMap<>();
        List<OpId> ids = new ArrayList<>();
        for (SemanticOp op : ops) {
            ids.add(op.opId());
            opBlocks.put(op.opId(), initBlock);
        }
        blockOps.put(initBlock, ids);
        return new LoweredModuleUnit(unit.formatVersion(), unit.semanticProfile(), unit.moduleId(),
            unit.interfaceHash(), unit.loweringContextHash(), unit.requiredCapabilities(), unit.constructCoverage(),
            unit.classLayouts(), unit.functions(), unit.moduleInit(), unit.exportPlan(),
            unit.functionBindings(), ops, new StructuredBodyTable(blockOps, opBlocks), unit.classFactories(),
            unit.classBindingFacts());
    }

    private static SemanticOp protocolCopy(SemanticOp op, KindPayload payload, FailurePolicyId policy,
                                            SourceOrigin origin) {
        deal.semantic.ir.ClosedSelector selector = payload instanceof deal.semantic.ir.KindPayload.SelectorCarrying p
            ? p.selector() : null;
        OperationContractSnapshot contract = new OperationContractSnapshot(OperationContractSnapshot.VERSION,
            op.kind(), op.resultType(), op.operandTypes(), selector, payload, policy, List.of(), "placeholder");
        contract = new OperationContractSnapshot(OperationContractSnapshot.VERSION, op.kind(), op.resultType(),
            op.operandTypes(), selector, payload, policy, List.of(), ContractSnapshotCanonicalizer.digest(contract));
        return new SemanticOp(op.opId(), op.kind(), origin, op.result(), op.resultType(), op.operands(),
            op.operandTypes(), payload, policy, contract);
    }

    private record ProtocolCase(int arm, String detail, java.util.function.Consumer<List<SemanticOp>> mutate) {}

    private static void testProtocolMutationMatrix() {
        List<List<SemanticOp>> seeds = List.of(protocolSeed(0), protocolSeed(1), protocolSeed(2));
        List<ProtocolCase> rows = List.of(
            new ProtocolCase(1, "resolved table reference", ops -> {
                SemanticOp chain = ops.getLast();
                List<OpId> ids = new ArrayList<>(((KindPayload.AssignPayload) chain.payload()).childOps());
                java.util.Collections.swap(ids, 0, 1);
                ops.set(ops.size() - 1, protocolCopy(chain, new KindPayload.AssignPayload(AssignTargetKind.TABLE_SLOT, ids),
                    chain.failurePolicy(), chain.origin()));
            }),
            new ProtocolCase(1, "ValueId result", ops -> {
                SemanticOp value = ops.get(1), commit = ops.get(2);
                ops.set(1, new SemanticOp(value.opId(), commit.kind(), value.origin(), null, null,
                    List.of(), List.of(), commit.payload(), commit.failurePolicy(), commit.contract()));
            }),
            new ProtocolCase(0, "descriptor-kind policy", ops -> {
                SemanticOp boundary = ops.get(1);
                ops.set(1, protocolCopy(boundary, boundary.payload(), FailurePolicyId.ARRAY_DELETE_BOUNDS, boundary.origin()));
            }),
            new ProtocolCase(2, "ARRAY_READ", ops -> {
                SemanticOp normalize = ops.get(4);
                KindPayload.IndexNormalizePayload p = (KindPayload.IndexNormalizePayload) normalize.payload();
                ops.set(4, protocolCopy(normalize, new KindPayload.IndexNormalizePayload(IndexMode.ARRAY_READ,
                    p.rawKey(), p.currentLength()), normalize.failurePolicy(), normalize.origin()));
            }),
            new ProtocolCase(2, "6 children", ops -> {
                SemanticOp chain = ops.getLast();
                List<OpId> ids = new ArrayList<>(((KindPayload.AssignPayload) chain.payload()).childOps()); ids.remove(3);
                ops.set(ops.size() - 1, protocolCopy(chain, new KindPayload.AssignPayload(AssignTargetKind.ARRAY_SLOT, ids),
                    chain.failurePolicy(), chain.origin()));
            }),
            new ProtocolCase(0, "value child", ops -> {
                SemanticOp chain = ops.getLast();
                List<OpId> ids = new ArrayList<>(((KindPayload.AssignPayload) chain.payload()).childOps());
                java.util.Collections.swap(ids, 0, 2);
                ops.set(ops.size() - 1, protocolCopy(chain, new KindPayload.AssignPayload(AssignTargetKind.VARIABLE, ids),
                    chain.failurePolicy(), chain.origin()));
            }),
            new ProtocolCase(0, "VARIABLE_ASSIGNMENT", ops -> {
                SemanticOp chain = ops.getLast();
                List<OpId> ids = new ArrayList<>(((KindPayload.AssignPayload) chain.payload()).childOps());
                java.util.Collections.swap(ids, 1, 2);
                ops.set(ops.size() - 1, protocolCopy(chain, new KindPayload.AssignPayload(AssignTargetKind.VARIABLE, ids),
                    chain.failurePolicy(), chain.origin()));
            }));
        for (ProtocolCase row : rows) {
            List<SemanticOp> ops = new ArrayList<>(seeds.get(row.arm())); row.mutate().accept(ops);
            assertProtocolFailure(ops, "ADDRESS_CHAIN_SHAPE", row.detail(), null);
        }
        List<SemanticOp> duplicate = new ArrayList<>(seeds.get(0)); duplicate.add(duplicate.getFirst());
        assertProtocolFailure(duplicate, "SINGLE_EVALUATION", "appears more than once", null);
        List<SemanticOp> shared = new ArrayList<>(seeds.get(0));
        OpId chainB = new OpId(MODULE, 99999);
        for (int i = 1; i < seeds.get(0).size(); i++) {
            SemanticOp original = seeds.get(0).get(i);
            KindPayload payload = i == 3 ? new KindPayload.AssignPayload(AssignTargetKind.VARIABLE,
                List.of(shared.getFirst().opId(), new OpId(MODULE, 99997), new OpId(MODULE, 99998)))
                : original.payload();
            SemanticOp copy = protocolCopy(original, payload, original.failurePolicy(),
                protocolOrigin("chain-b.deal", i == 3 ? null : chainB));
            shared.add(new SemanticOp(new OpId(MODULE, 99996 + i), copy.kind(), copy.origin(), copy.result(),
                copy.resultType(), copy.operands(), copy.operandTypes(), copy.payload(), copy.failurePolicy(), copy.contract()));
        }
        assertProtocolFailure(shared, "SINGLE_EVALUATION",
            "the producing child " + shared.getFirst().opId() + " is referenced by more than one chain", null);
        List<SemanticOp> reordered = new ArrayList<>(seeds.get(2)); java.util.Collections.reverse(reordered);
        check(AddressChainProtocol.validate(protocolUnit(reordered)).isEmpty(), "chain resolves children by identity, not unit order");
        check(AddressChainProtocol.validate(protocolUnit(List.of())).isEmpty(), "empty unit outside protocol domain");
        check(AddressChainProtocol.validate(protocolUnit(List.of(seeds.get(0).getFirst()))).isEmpty(), "non-chain unit outside protocol domain");
        testProtocolOrigins(seeds.get(0));
        CheckedSlice adapterSource = checkSlice("""
            function inner(x: number): null {}
            function main(): null {
              let f: (x: number) => null = inner;
              let g: (a: number, b: number) => null = inner;
              g = f;
            }
            """);
        SemanticLowerer.ShapeMapCoreResult adapted = SemanticLowerer.lowerModuleShapeMapCore(moduleOf(adapterSource),
            SemanticProfile.DEAL_V1_5_BINARY64, Map.of(), INTERFACE_HASH, REGISTRY_HASH,
            SemanticIdAllocator.over(List.of(MODULE)));
        check(!adapted.lowering().hasErrors(), "adapter assignment lowers: " + adapted.lowering().diagnostics());
        if (!adapted.lowering().hasErrors()) {
            check(adapted.lowering().unit().ops().stream().anyMatch(op ->
                op.payload() instanceof KindPayload.AssignPayload p && p.childOps().size() == 4),
                "actual adapter VARIABLE chain exercised");
            check(AddressChainProtocol.validate(adapted.lowering().unit()).isEmpty(), "adapter VARIABLE chain admitted");
        }
        testNormalizedSlots();
    }

    /**
     * The composed production admission keeps the pinned per-module order:
     * the common-admission head, then {@code AddressChainProtocol}, then the
     * control-flow owner. A unit carrying both an address-chain defect and a
     * block-membership defect reports the address-chain rule as its first
     * E6005 through {@code SemanticLowerer.validateProjectUnit}, while the
     * typed and text unit entrypoints (the head plus the control owner)
     * report the control rule identically on both surfaces, so no surface
     * loses the membership rule and the production order does not relocate
     * it.
     */
    static void testComposedAdmissionOrder() {
        System.out.println("-- Composed admission order: address chain before control flow --");
        List<SemanticOp> ops = new ArrayList<>(protocolSeed(1));
        SemanticOp chain = ops.getLast();
        List<OpId> children = new ArrayList<>(
            ((KindPayload.AssignPayload) chain.payload()).childOps());
        java.util.Collections.swap(children, 0, 1);
        ops.set(ops.size() - 1, protocolCopy(chain,
            new KindPayload.AssignPayload(AssignTargetKind.TABLE_SLOT, children),
            chain.failurePolicy(), chain.origin()));
        LoweredModuleUnit clean = protocolUnit(ops);
        // The block list lists the first op twice while the inverse map keeps
        // its single row: a control membership defect the text surface can
        // carry (a duplicated ops-list identity rejects at transport decode
        // instead, so it cannot exercise the composed order).
        BlockId initBlock = clean.moduleInit().initBlock();
        Map<BlockId, List<OpId>> blocks = new LinkedHashMap<>(clean.bodyTable().blockOps());
        List<OpId> initOps = new ArrayList<>(blocks.get(initBlock));
        initOps.add(initOps.getFirst());
        blocks.put(initBlock, initOps);
        LoweredModuleUnit unit = new LoweredModuleUnit(clean.formatVersion(),
            clean.semanticProfile(), clean.moduleId(), clean.interfaceHash(),
            clean.loweringContextHash(), clean.requiredCapabilities(),
            clean.constructCoverage(), clean.classLayouts(), clean.functions(),
            clean.moduleInit(), clean.exportPlan(), clean.functionBindings(), clean.ops(),
            new StructuredBodyTable(blocks, clean.bodyTable().opBlocks()),
            clean.classFactories(), clean.classBindingFacts());

        // Both defects are real: the control owner rejects the membership and
        // the address-chain owner rejects the chain, but the common-admission
        // head admits the unit, so the composed order decides which rule is
        // reported first.
        checkE6005(ControlFlowValidator.validate(unit, unit.bodyTable()),
            "CONTROL_BLOCK_TREE", "listed twice in one block");
        Optional<CompilerDiagnostic> chainFailure = AddressChainProtocol.validate(unit);
        checkE6005(chainFailure, "ADDRESS_CHAIN_SHAPE",
            "the address-chain owner rejects the dual-defect unit");
        Optional<CompilerDiagnostic> head = SemanticIrValidator.validateUnitHead(unit, facts());
        check(head.isEmpty(),
            "the common-admission head admits the dual-defect unit (the first failure "
                + "belongs to a composed owner): "
                + head.map(CompilerDiagnostic::message).orElse(""));

        // The typed and text unit entrypoints: head then the control owner,
        // with identical first failure on both surfaces.
        Optional<CompilerDiagnostic> typed = SemanticIrValidator.validate(unit, facts());
        Optional<CompilerDiagnostic> text = SemanticIrValidator.validateText(
            SemanticIrValidator.toUnitText(unit), facts());
        checkE6005(typed, "CONTROL_BLOCK_TREE", "listed twice in one block");
        checkE6005(text, "CONTROL_BLOCK_TREE", "listed twice in one block");
        check(typed.isPresent() && text.isPresent()
                && typed.get().message().equals(text.get().message()),
            "the typed and text unit surfaces report the identical control first failure");
        if (typed.isPresent() && text.isPresent()) {
            check(typed.get().range().equals(text.get().range()),
                "the typed and text unit surfaces keep the same failing range");
        }

        // The composed production admission runs the head, then the address
        // chain, then the control owner: the pinned address-chain rule is the
        // first failure, and it runs once.
        Optional<CompilerDiagnostic> composed = SemanticLowerer.validateProjectUnit(unit,
            unit.bodyTable(), facts(), unit.classBindingFacts(), unit.classFactories(),
            ownInterface(), Map.of());
        checkE6005(composed, "ADDRESS_CHAIN_SHAPE",
            "the composed production admission reports the address-chain rule");
        check(chainFailure.isPresent() && composed.isPresent()
                && chainFailure.get().message().equals(composed.get().message())
                && chainFailure.get().range().equals(composed.get().range()),
            "the composed production admission reports exactly the address-chain first "
                + "failure: " + composed.map(CompilerDiagnostic::message).orElse("admitted"));
    }

    private static void assertProtocolFailure(List<SemanticOp> ops, String rule, String detail, SourceOrigin expected) {
        LoweredModuleUnit unit = protocolUnit(ops);
        Optional<CompilerDiagnostic> failure = AddressChainProtocol.validate(unit);
        checkE6005(failure, rule, detail);
        if (failure.isPresent()) {
            String message = failure.get().message();
            check(message.startsWith("Common semantic lowering failed")
                    && message.contains("validatorRule " + rule + ",")
                    && message.contains("semanticProfile DEAL_V1_5_BINARY64")
                    && message.contains("irVersion deal.semantic-ir/2")
                    && message.contains("AddressChainProtocol " + rule)
                    && message.contains(detail) && failure.get().severity().equals("error"),
                "exact protocol rule/detail: " + message);
            if (expected != null) { check(failure.get().range().equals(expected.span().range()), "exact protocol offending range"); }
        }
        check(failure.equals(AddressChainProtocol.validate(unit)), "protocol diagnostic deterministic");
        check(unit.ops().equals(ops), "protocol leaves operations unchanged");
    }

    private static SourceOrigin protocolOrigin(String file, OpId parent) {
        return new SourceOrigin(file, new SourceSpan(file, 2, 3, 2, 7, 10, 15),
            SourceOriginKind.USER, new deal.semantic.ir.AnchorId(1), parent);
    }

    private static void testProtocolOrigins(List<SemanticOp> seed) {
        for (boolean originless : List.of(false, true)) {
            for (int defect = 0; defect < 5; defect++) {
                List<SemanticOp> ops = new ArrayList<>(seed);
                OpId chainId = seed.getLast().opId();
                for (int i = 0; i < ops.size(); i++) {
                    SemanticOp op = ops.get(i);
                    SourceOrigin origin = protocolOrigin(List.of("value.deal", "boundary.deal", "commit.deal", "chain.deal").get(i),
                        i == 3 ? null : chainId);
                    if (originless && i == 3) { origin = new SourceOrigin("", SourceSpan.synthetic(""),
                        SourceOriginKind.SYNTHETIC, new deal.semantic.ir.AnchorId(0), null); }
                    ops.set(i, protocolCopy(op, op.payload(), op.failurePolicy(), origin));
                }
                SemanticOp boundary = ops.get(1), commit = ops.get(2), chain = ops.get(3);
                KindPayload.BoundaryPayload p = (KindPayload.BoundaryPayload) boundary.payload();
                SourceOrigin expected = boundary.origin();
                String detail;
                switch (defect) {
                    case 0 -> {
                        ops.set(1, protocolCopy(boundary, new KindPayload.BoundaryPayload(BoundaryKind.VARIABLE_DECLARATION,
                            p.descriptor(), p.input(), p.realization()), boundary.failurePolicy(), boundary.origin()));
                        detail = "must carry kind VARIABLE_ASSIGNMENT";
                    }
                    case 1 -> {
                        expected = protocolOrigin("boundary.deal", new OpId(MODULE, 99998));
                        ops.set(1, protocolCopy(boundary, p, boundary.failurePolicy(), expected));
                        detail = "must record the chain op as its parentOpId";
                    }
                    case 2 -> {
                        ops.set(1, protocolCopy(boundary, new KindPayload.BoundaryPayload(p.kind(), p.descriptor(),
                            new ValueId(99997), p.realization()), boundary.failurePolicy(), boundary.origin()));
                        detail = "must reference the resolved value";
                    }
                    case 3 -> {
                        KindPayload.BindingStorePayload store = (KindPayload.BindingStorePayload) commit.payload();
                        ops.set(2, protocolCopy(commit, new KindPayload.BindingStorePayload(store.binding(), store.generation(),
                            new ValueId(99996)), commit.failurePolicy(), commit.origin()));
                        expected = commit.origin(); detail = "stored value must reference the resolved value";
                    }
                    default -> {
                        ops.remove(1);
                        ops.set(2, protocolCopy(chain, new KindPayload.AssignPayload(AssignTargetKind.VARIABLE,
                            List.of(seed.get(0).opId(), commit.opId())), chain.failurePolicy(), chain.origin()));
                        expected = chain.origin(); detail = "2 children";
                    }
                }
                assertProtocolFailure(ops, "ADDRESS_CHAIN_SHAPE", detail, expected);
            }
        }
    }

    private static void testNormalizedSlots() {
        for (IndexMode mode : List.of(IndexMode.ARRAY_READ, IndexMode.ARRAY_WRITE)) {
            for (int length : List.of(0, 3)) {
                for (int index : List.of(-1, 0, 2, 3, 5)) {
                    deal.semantic.ir.NormalizedSlot.ArraySlot slot = deal.semantic.ir.NormalizedSlot.arraySlot(mode, index, length);
                    check(slot.index() == index && slot.present() == (index < length)
                            && slot.append() == (mode == IndexMode.ARRAY_WRITE && index == length)
                            && slot.resultType() == RuntimeDescriptor.Number.INSTANCE,
                        "normalization pure computation " + mode + " " + index + "/" + length);
                    check(slot.equals(deal.semantic.ir.NormalizedSlot.arraySlot(mode, index, length)), "normalization stable");
                }
            }
        }
        for (IndexMode mode : List.of(IndexMode.TABLE_READ, IndexMode.TABLE_WRITE)) {
            for (String key : List.of("0", "k")) {
                deal.semantic.ir.NormalizedSlot.TableSlot slot = deal.semantic.ir.NormalizedSlot.tableSlot(mode, key);
                check(slot.key().equals(key) && slot.resultType() == RuntimeDescriptor.String.INSTANCE
                        && slot.equals(new deal.semantic.ir.NormalizedSlot.TableSlot(key)), "table keys never coerced");
            }
        }
        expectProtocolException(IllegalArgumentException.class, () -> deal.semantic.ir.NormalizedSlot.arraySlot(IndexMode.TABLE_READ, 1, 1));
        expectProtocolException(IllegalArgumentException.class, () -> deal.semantic.ir.NormalizedSlot.tableSlot(IndexMode.ARRAY_WRITE, "k"));
        expectProtocolException(NullPointerException.class, () -> deal.semantic.ir.NormalizedSlot.tableSlot(IndexMode.TABLE_WRITE, null));
        SemanticOp normalize = protocolSeed(2).get(4);
        Optional<CompilerDiagnostic> pureFailure = SemanticIrValidator.validate(
            protocolUnit(List.of(normalize)), facts());
        check(pureFailure.isEmpty(), "INDEX_NORMALIZE pure policy admitted: "
            + pureFailure.map(CompilerDiagnostic::message).orElse(""));
        SemanticOp wrong = protocolCopy(normalize, normalize.payload(), FailurePolicyId.TYPE_DESCRIPTOR, normalize.origin());
        Optional<CompilerDiagnostic> failure = SemanticIrValidator.validate(protocolUnit(List.of(wrong)), facts());
        check(failure.isPresent() && failure.get().message().contains("R-POLICY-KIND"), "INDEX_NORMALIZE wrong policy rejected");
    }

    private static void expectProtocolException(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); fail("expected " + type.getName()); }
        catch (Throwable failure) { check(type.isInstance(failure), "exact normalization exception " + failure); }
    }

    // =========================================================================
    // 13. The refined index receiver/key admission (ISSUE-0861)
    // =========================================================================

    /**
     * A null-narrowed read consumed as an index chain's receiver or key is
     * the same unvalidated consumer class as an operator operand: the read
     * is admitted through the {@code NARROWED_READ} cell and the chain's
     * length/normalize/commit children consume the admitted value. The
     * admission is the receiver/key child itself, so the closed A-D9 child
     * shape (count, positions, parentage) is unchanged and the protocol
     * still validates the unit.
     */
    static void testNarrowedReceiverAndKeyAdmission() {
        System.out.println("-- Refined index receiver/key: NARROWED_READ admission is the "
            + "receiver/key child --");

        // The refined reads are the chain's receiver and key; the checker
        // publishes exactly those two identifier reads in
        // CheckResult.narrowedReads (declared T | null, read as T).
        IdentifierExpr receiver = ident("xs", 1);
        IdentifierExpr key = ident("i", 4);
        IndexExpr target = index(receiver, key, 3);
        LiteralExpr value = intLit(3, 8);
        AssignmentExpr assignment = assign(target, value);
        IdentifierExpr readReceiver = ident("xs", 11);
        IdentifierExpr readKey = ident("i", 14);
        IndexExpr read = index(readReceiver, readKey, 13);
        Type.Array arrayType = new Type.Array(Type.Number.INSTANCE);
        SymbolTable table = new SymbolTable();
        table.define("xs", new Symbol.VariableSymbol("xs", arrayType, false));
        table.define("i", new Symbol.VariableSymbol("i", Type.Number.INSTANCE, false));
        CheckResult checked = new CheckResult(
            Map.of(receiver, arrayType, key, Type.Number.INSTANCE, target, Type.Number.INSTANCE,
                value, Type.Number.INSTANCE, readReceiver, arrayType,
                readKey, Type.Number.INSTANCE, read, Type.Number.INSTANCE, assignment, Type.Number.INSTANCE),
            table, Map.of(), List.of(), Map.of(), Map.of(), Map.of(),
            Set.of(receiver, key, readReceiver, readKey));

        SemanticLowerer.ModuleLowerer lowerer = lowerer(checked);
        lowerer.openForEachScope("xs");
        lowerer.openForEachScope("i");
        lowerer.lowerAssignment(assignment);
        lowerer.lowerExpression(read);
        lowerer.closeForEachScope();
        lowerer.closeForEachScope();
        List<SemanticOp> opList = lowerer.ops();
        check(AddressChainProtocol.validate(protocolUnit(opList)).isEmpty(),
            "the admission-bearing chain passes the production-time address-chain "
                + "protocol: " + AddressChainProtocol.validate(protocolUnit(opList)));

        SemanticOp chain = null;
        SemanticOp indexRead = null;
        for (SemanticOp op : opList) {
            if (op.kind() == SemanticOpKind.ASSIGN
                    && ((KindPayload.AssignPayload) op.payload()).targetKind()
                        == AssignTargetKind.ARRAY_SLOT) {
                chain = op;
            }
            if (op.kind() == SemanticOpKind.INDEX_READ) {
                indexRead = op;
            }
        }
        check(chain != null, "the slice carries one ASSIGN ARRAY_SLOT chain");
        check(indexRead != null, "the slice carries one ARRAY_READ INDEX_READ");
        if (chain == null || indexRead == null) {
            return;
        }
        KindPayload.AssignPayload payload = (KindPayload.AssignPayload) chain.payload();
        check(payload.childOps().size() == 7,
            "the chain keeps the closed seven-child ARRAY_SLOT shape; got "
                + payload.childOps().size());
        if (payload.childOps().size() != 7) {
            return;
        }
        Map<OpId, SemanticOp> byId = new LinkedHashMap<>();
        for (SemanticOp op : opList) {
            byId.put(op.opId(), op);
        }
        SemanticOp receiverCell = byId.get(payload.childOps().get(0));
        SemanticOp keyCell = byId.get(payload.childOps().get(1));
        SemanticOp length = byId.get(payload.childOps().get(3));
        SemanticOp normalize = byId.get(payload.childOps().get(4));
        SemanticOp commit = byId.get(payload.childOps().get(6));
        check(receiverCell != null && keyCell != null && length != null
                && normalize != null && commit != null,
            "the chain's receiver/key/length/normalize/commit children resolve");

        checkAdmission("the chain's receiver child", receiverCell, BoundaryKind.NARROWED_READ,
            new RuntimeDescriptor.Array(RuntimeDescriptor.Number.INSTANCE), chain);
        checkAdmission("the chain's key child", keyCell, BoundaryKind.NARROWED_READ,
            RuntimeDescriptor.Number.INSTANCE, chain);
        if (receiverCell != null && keyCell != null && length != null
                && normalize != null && commit != null) {
            KindPayload.ArrayLengthPayload lengthPayload =
                (KindPayload.ArrayLengthPayload) length.payload();
            check(lengthPayload.arrayValue().equals(receiverCell.result()),
                "the length child reads the admitted receiver (the admission's result)");
            KindPayload.IndexNormalizePayload normalizePayload =
                (KindPayload.IndexNormalizePayload) normalize.payload();
            check(normalizePayload.rawKey().equals(keyCell.result()),
                "the normalize child keys on the admitted index (the admission's result)");
            check(normalizePayload.currentLength().equals(length.result()),
                "the normalize child reads the length child's result");
            KindPayload.IndexWritePayload commitWrite =
                (KindPayload.IndexWritePayload) commit.payload();
            check(commitWrite.container().equals(receiverCell.result()),
                "the commit writes through the admitted receiver");
            check(commitWrite.slot().equals(normalize.result()),
                "the commit uses the normalize child's slot");
            check(!commitWrite.container().equals(
                    ((KindPayload.BoundaryPayload) receiverCell.payload()).input()),
                "the commit never reuses the unadmitted receiver read identity");
        }

        KindPayload.IndexReadPayload readPayload =
            (KindPayload.IndexReadPayload) indexRead.payload();
        SemanticOp readBoundary = byId.get(readPayload.elementBoundaryOpId());
        check(readBoundary != null
                && ((KindPayload.BoundaryPayload) readBoundary.payload()).kind()
                    == BoundaryKind.ARRAY_ELEMENT_READ,
            "the index read keeps its ARRAY_ELEMENT_READ boundary child");
        SemanticOp readContainerProducer = null;
        SemanticOp readKeyProducer = null;
        for (SemanticOp op : opList) {
            if (!(op.result() instanceof ValueId valueId)) {
                continue;
            }
            if (valueId.equals(readPayload.container())) {
                readContainerProducer = op;
            }
            if (valueId.equals(readPayload.slot())) {
                readKeyProducer = op;
            }
        }
        check(readContainerProducer != null
                && readContainerProducer.kind() == SemanticOpKind.BOUNDARY
                && ((KindPayload.BoundaryPayload) readContainerProducer.payload()).kind()
                    == BoundaryKind.NARROWED_READ,
            "the index read's container is the admitted receiver (the admission's result), "
                + "never the raw refined read");
        check(readKeyProducer != null
                && readKeyProducer.kind() == SemanticOpKind.INDEX_NORMALIZE,
            "the index read's slot is the normalize child");
        if (readKeyProducer != null) {
            KindPayload.IndexNormalizePayload readNormalize =
                (KindPayload.IndexNormalizePayload) readKeyProducer.payload();
            SemanticOp readKeyAdmission = null;
            for (SemanticOp op : opList) {
                if (op.result() instanceof ValueId valueId
                        && valueId.equals(readNormalize.rawKey())) {
                    readKeyAdmission = op;
                }
            }
            check(readKeyAdmission != null
                    && readKeyAdmission.kind() == SemanticOpKind.BOUNDARY
                    && ((KindPayload.BoundaryPayload) readKeyAdmission.payload()).kind()
                        == BoundaryKind.NARROWED_READ,
                "the index read's normalize keys on the admitted index (the admission's "
                    + "result), never the raw refined read");
        }
        long readAdmissions = opList.stream()
            .filter(op -> op.kind() == SemanticOpKind.BOUNDARY
                && ((KindPayload.BoundaryPayload) op.payload()).kind()
                    == BoundaryKind.NARROWED_READ)
            .count();
        check(readAdmissions == 4,
            "the slice carries exactly the four refined-read admissions (write receiver, "
                + "write key, read receiver, read key); got " + readAdmissions);
    }

    /** Asserts one chain position's NARROWED_READ admission cell. */
    private static void checkAdmission(String what, SemanticOp op, BoundaryKind kind,
                                       RuntimeDescriptor descriptor, SemanticOp chain) {
        check(op != null, what + " resolves");
        if (op == null) {
            return;
        }
        check(op.kind() == SemanticOpKind.BOUNDARY,
            what + " is a BOUNDARY op");
        if (op.kind() != SemanticOpKind.BOUNDARY) {
            return;
        }
        KindPayload.BoundaryPayload boundary = (KindPayload.BoundaryPayload) op.payload();
        check(boundary.kind() == kind,
            what + " carries kind " + kind + " (got " + boundary.kind() + ")");
        check(boundary.descriptor().equals(descriptor),
            what + " carries the read's refined descriptor " + descriptor.canonicalSpecText()
                + " (got " + boundary.descriptor().canonicalSpecText() + ")");
        check(op.result() instanceof ValueId, what + " publishes the admitted value");
        check(!boundary.input().equals(op.result()),
            what + " publishes a distinct admitted value (the chain consumes it)");
        check(op.failurePolicy() == FailurePolicyId.TYPE_DESCRIPTOR,
            what + " carries the descriptor-kind policy of its own descriptor");
        checkCanonicalRealization(what, boundary.realization());
        checkParentage(what, op, chain.opId());
        check(op.origin().kind() == SourceOriginKind.USER,
            what + " keeps the read's own USER origin");
    }

    /**
     * A null-narrowed read consumed as a table member's receiver is the same
     * unvalidated consumer class: the read is admitted through the
     * {@code NARROWED_READ} cell and the {@code MEMBER_READ} /
     * {@code MEMBER_WRITE} / {@code MEMBER_DELETE} operation dereferences the
     * admitted value — never the unadmitted read. The admission is the
     * chain's container child itself, so the closed A-D9 child shape (count,
     * positions, parentage) is unchanged and the protocol still validates the
     * unit.
     */
    static void testNarrowedMemberReceiverAdmission() {
        System.out.println("-- Refined member receiver: the NARROWED_READ admission is the "
            + "member operation's receiver --");

        // The refined reads are the member operations' receivers; the
        // checker publishes exactly those identifier reads in
        // CheckResult.narrowedReads (declared table | null, read as table).
        IdentifierExpr writeReceiver = ident("t", 3);
        MemberAccessExpr writeTarget = member(writeReceiver, "a", 3);
        LiteralExpr value = intLit(2, 9);
        AssignmentExpr write = assign(writeTarget, value);
        IdentifierExpr deleteReceiver = ident("t", 16);
        MemberAccessExpr deleteTarget = member(deleteReceiver, "b", 16);
        DeleteStatement remove = delete(deleteTarget);
        IdentifierExpr readReceiver = ident("t", 30);
        MemberAccessExpr read = member(readReceiver, "c", 30);
        SymbolTable table = new SymbolTable();
        table.define("t", new Symbol.VariableSymbol("t",
            new Type.Nullable(Type.Table.INSTANCE), false));
        CheckResult checked = new CheckResult(
            Map.of(write, Type.Number.INSTANCE, writeReceiver, Type.Table.INSTANCE, writeTarget, Type.Number.INSTANCE,
                value, Type.Number.INSTANCE, deleteReceiver, Type.Table.INSTANCE,
                deleteTarget, Type.Number.INSTANCE, readReceiver, Type.Table.INSTANCE,
                read, Type.Number.INSTANCE),
            table, Map.of(), List.of(), Map.of(), Map.of(), Map.of(),
            Set.of(writeReceiver, deleteReceiver, readReceiver));

        SemanticLowerer.ModuleLowerer lowerer = lowerer(checked);
        lowerer.openForEachScope("t");
        lowerer.lowerAssignment(write);
        lowerer.lowerDelete(remove);
        lowerer.lowerExpression(read);
        lowerer.closeForEachScope();
        List<SemanticOp> opList = lowerer.ops();
        check(AddressChainProtocol.validate(protocolUnit(opList)).isEmpty(),
            "the admission-bearing member chains pass the production-time "
                + "address-chain protocol: " + AddressChainProtocol.validate(protocolUnit(opList)));

        Map<OpId, SemanticOp> byId = new LinkedHashMap<>();
        for (SemanticOp op : opList) {
            byId.put(op.opId(), op);
        }
        SemanticOp writeChain = null;
        SemanticOp deleteChain = null;
        SemanticOp memberRead = null;
        for (SemanticOp op : opList) {
            if (op.kind() == SemanticOpKind.ASSIGN
                    && ((KindPayload.AssignPayload) op.payload()).targetKind()
                        == AssignTargetKind.TABLE_SLOT) {
                writeChain = op;
            }
            if (op.kind() == SemanticOpKind.DELETE
                    && ((KindPayload.DeletePayload) op.payload()).targetKind()
                        == DeleteTargetKind.TABLE_SLOT) {
                deleteChain = op;
            }
            if (op.kind() == SemanticOpKind.MEMBER_READ) {
                memberRead = op;
            }
        }
        check(writeChain != null, "the slice carries one ASSIGN TABLE_SLOT chain");
        check(deleteChain != null, "the slice carries one DELETE TABLE_SLOT chain");
        check(memberRead != null, "the slice carries one MEMBER_READ");
        if (writeChain == null || deleteChain == null || memberRead == null) {
            return;
        }

        // (a) The member write: [containerOp, valueOp, commitOp(MEMBER_WRITE)]
        // keeps its closed three-child shape, and the container child is the
        // admission the commit writes through.
        KindPayload.AssignPayload writePayload =
            (KindPayload.AssignPayload) writeChain.payload();
        check(writePayload.childOps().size() == 3,
            "the member write keeps the closed three-child shape; got "
                + writePayload.childOps().size());
        if (writePayload.childOps().size() == 3) {
            SemanticOp receiverCell = byId.get(writePayload.childOps().get(0));
            SemanticOp commit = byId.get(writePayload.childOps().get(2));
            checkAdmission("the member write's container child", receiverCell,
                BoundaryKind.NARROWED_READ, RuntimeDescriptor.Table.INSTANCE, writeChain);
            check(commit != null && commit.kind() == SemanticOpKind.MEMBER_WRITE,
                "the member write's commit child is the MEMBER_WRITE op");
            if (receiverCell != null && commit != null
                    && receiverCell.kind() == SemanticOpKind.BOUNDARY) {
                KindPayload.MemberWritePayload commitWrite =
                    (KindPayload.MemberWritePayload) commit.payload();
                check(commitWrite.table().equals(receiverCell.result()),
                    "the member write writes through the admitted receiver");
                check(!commitWrite.table().equals(
                        ((KindPayload.BoundaryPayload) receiverCell.payload()).input()),
                    "the member write never reuses the unadmitted receiver read identity");
            }
        }

        // (b) The member delete: [containerOp, commitOp(MEMBER_DELETE)] keeps
        // its closed two-child shape, and the container child is the
        // admission the commit deletes through.
        KindPayload.DeletePayload deletePayload =
            (KindPayload.DeletePayload) deleteChain.payload();
        check(deletePayload.childOps().size() == 2,
            "the member delete keeps the closed two-child shape; got "
                + deletePayload.childOps().size());
        if (deletePayload.childOps().size() == 2) {
            SemanticOp receiverCell = byId.get(deletePayload.childOps().get(0));
            SemanticOp commit = byId.get(deletePayload.childOps().get(1));
            checkAdmission("the member delete's container child", receiverCell,
                BoundaryKind.NARROWED_READ, RuntimeDescriptor.Table.INSTANCE, deleteChain);
            check(commit != null && commit.kind() == SemanticOpKind.MEMBER_DELETE,
                "the member delete's commit child is the MEMBER_DELETE op");
            if (receiverCell != null && commit != null
                    && receiverCell.kind() == SemanticOpKind.BOUNDARY) {
                KindPayload.MemberDeletePayload commitDelete =
                    (KindPayload.MemberDeletePayload) commit.payload();
                check(commitDelete.table().equals(receiverCell.result()),
                    "the member delete deletes through the admitted receiver");
                check(!commitDelete.table().equals(
                        ((KindPayload.BoundaryPayload) receiverCell.payload()).input()),
                    "the member delete never reuses the unadmitted receiver read identity");
            }
        }

        // (c) The member read: the MEMBER_READ's table operand is the admitted
        // receiver (the admission's result), never the raw refined read.
        KindPayload.MemberReadPayload readPayload =
            (KindPayload.MemberReadPayload) memberRead.payload();
        SemanticOp readReceiverCell = null;
        for (SemanticOp op : opList) {
            if (op.result() instanceof ValueId valueId
                    && valueId.equals(readPayload.table())) {
                readReceiverCell = op;
            }
        }
        check(readReceiverCell != null,
            "the member read's table operand is produced by a prior step");
        boolean readAdmitted = readReceiverCell != null
            && readReceiverCell.kind() == SemanticOpKind.BOUNDARY
            && ((KindPayload.BoundaryPayload) readReceiverCell.payload()).kind()
                == BoundaryKind.NARROWED_READ;
        check(readAdmitted,
            "the member read's table operand is the admitted receiver (the "
                + "admission's result), never the raw refined read");
        if (readAdmitted) {
            KindPayload.BoundaryPayload boundary =
                (KindPayload.BoundaryPayload) readReceiverCell.payload();
            check(boundary.descriptor().equals(RuntimeDescriptor.Table.INSTANCE),
                "the member read's admission carries the read's refined descriptor table");
            check(readReceiverCell.failurePolicy() == FailurePolicyId.TYPE_DESCRIPTOR,
                "the member read's admission carries the descriptor-kind policy");
            check(readReceiverCell.origin().kind() == SourceOriginKind.USER,
                "the member read's admission keeps the read's own USER origin");
            check(!boundary.input().equals(readReceiverCell.result()),
                "the member read's admission publishes a distinct admitted value");
        }
        long admissions = opList.stream()
            .filter(op -> op.kind() == SemanticOpKind.BOUNDARY
                && ((KindPayload.BoundaryPayload) op.payload()).kind()
                    == BoundaryKind.NARROWED_READ)
            .count();
        check(admissions == 3,
            "the slice carries exactly the three refined-read admissions (write, delete "
                + "and read receiver); got " + admissions);
    }

    public static void main(String[] args) {
        System.out.println("=== Address Chain Lowering Test (ISSUE-0405) ===\n");

        testVariableAssignArm();
        testVariableBoundaryDescriptorKindAndBindingReuse();
        testTableMemberAssignArm();
        testTableIndexAssignArm();
        testArrayIndexAssignArm();
        testAppendIdiomArm();
        testClassFieldAssignArm();
        testDeleteArms();
        testNestedChainParentage();
        testE3018StringKeyGate();
        testProtocolValidationOfLoweredChains();
        testProtocolMutationMatrix();
        testComposedAdmissionOrder();
        testModuleCorpusAndDeterminism();
        testNarrowedReceiverAndKeyAdmission();
        testNarrowedMemberReceiverAdmission();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
