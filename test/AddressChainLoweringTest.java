package deal.test;

import deal.ast.ArrayLiteralExpr;
import deal.ast.AssignmentExpr;
import deal.ast.Block;
import deal.ast.DeleteStatement;
import deal.ast.ExpressionNode;
import deal.ast.ExpressionStatement;
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
import deal.semantic.SemanticLowerer;
import deal.semantic.ir.AddressChainProtocol;
import deal.semantic.ir.AssignTargetKind;
import deal.semantic.ir.BindingId;
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

/**
 * Verifies the ISSUE-0405 ASSIGN/DELETE address-chain lowering arms of
 * {@link SemanticLowerer} (assignment-delete-address-chains A-D2/A-D4/
 * A-D5/A-D6/A-D7/A-D8/A-D9; parent D14): the closed A-D9 construct→op
 * map, the pinned child order and parentage, the boundary production,
 * the commit structure, the committed-value result, and single
 * evaluation — plus the E3018 string-key gate interplay, the
 * production-time {@link AddressChainProtocol} validation of every
 * lowered chain, and the module-level seam with byte-identical
 * determinism.
 *
 * <p>Pinned cases (the task verification):
 * <ol>
 *   <li>every A-D9 row — VARIABLE write, TABLE_SLOT member/index
 *       writes, ARRAY_SLOT write, CLASS_FIELD write, the four delete
 *       rows — with exact child kinds, exact order,
 *       {@code parentOpId} = the chain op, boundary kind/policy,
 *       normalize modes, one commit last, {@code ASSIGN} result = the
 *       committed value with the target descriptor, {@code DELETE}
 *       result none;</li>
 *   <li>the append idiom {@code xs[xs.length] = v} lowers through the
 *       standard ARRAY_SLOT chain (the keyOp is the {@code .length}
 *       {@code ARRAY_LENGTH} read);</li>
 *   <li>VARIABLE boundary production: function-typed target →
 *       {@code FUNCTION_SIGNATURE}, non-function target →
 *       {@code TYPE_DESCRIPTOR}, boundary between value child and
 *       {@code BINDING_STORE}, the store carrying
 *       {@code {binding, generation, committed value}}; loop-binding
 *       targets reuse the frame's binding identity;</li>
 *   <li>nested chains: an inner {@code ASSIGN} as the value child
 *       records the outer chain op as its {@code parentOpId};</li>
 *   <li>the E3018 gate: {@code t[0] = v}/{@code delete t[0]} fail in
 *       the checker before lowering; {@code t["k"] = v}/
 *       {@code delete t["k"]} lower with the total string key;</li>
 *   <li>every lowered chain passes {@link AddressChainProtocol}; hand-
 *       corrupted variants (missing lengthOp, commit not last,
 *       duplicated child) fail E6005
 *       {@code ADDRESS_CHAIN_SHAPE}/{@code SINGLE_EVALUATION};</li>
 *   <li>the module-level corpus (deletes plus a nested VARIABLE chain
 *       inside a delete key) lowers to a validated unit, passes the
 *       protocol, and repeats byte-identically.</li>
 * </ol>
 */
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
        return new LiteralExpr(span(column, column), new LiteralValue.IntLiteral(value));
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
        table.define("x", new Symbol.VariableSymbol("x", Type.Int.INSTANCE, false));
        CheckResult checked = checks(
            Map.of(target, Type.Int.INSTANCE, value, Type.Int.INSTANCE), table);

        SemanticLowerer.ModuleLowerer lowerer = lowerer(checked);
        ValueId result = lowerer.lowerAssignment(assignment);
        List<SemanticOp> ops = lowerer.ops();
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
                    .equals(new ScalarValue.Int(1)),
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
        check(boundary.descriptor().equals(RuntimeDescriptor.Int.INSTANCE),
            "the boundary descriptor is the declared target descriptor (int)");
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
        check(chainOp.resultType().equals(RuntimeDescriptor.Int.INSTANCE),
            "ASSIGN resultType is the target position's checked descriptor (int)");
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
        Type.Func funcType = new Type.Func(List.of(Type.Int.INSTANCE), Type.Int.INSTANCE);
        IdentifierExpr target = ident("f", 1);
        IdentifierExpr value = ident("n", 5);
        AssignmentExpr assignment = assign(target, value);
        SymbolTable table = new SymbolTable();
        table.define("f", new Symbol.VariableSymbol("f", funcType, false));
        table.define("n", new Symbol.VariableSymbol("n", funcType, false));
        CheckResult checked = checks(
            Map.of(target, funcType, value, funcType), table);

        SemanticLowerer.ModuleLowerer lowerer = lowerer(checked);
        SemanticLowerer.ForEachFrame frame = lowerer.openForEachScope("n");
        ValueId committed = lowerer.lowerAssignment(assignment);
        lowerer.closeForEachScope();
        List<SemanticOp> ops = lowerer.ops();
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
            new RuntimeDescriptor.Func(List.of(RuntimeDescriptor.Int.INSTANCE),
                RuntimeDescriptor.Int.INSTANCE);
        check(valueOp.resultType().equals(functionDescriptor),
            "the load publishes the function descriptor (int)->int");
        KindPayload.BoundaryPayload boundary =
            (KindPayload.BoundaryPayload) boundaryOp.payload();
        check(boundary.kind() == BoundaryKind.VARIABLE_ASSIGNMENT
                && boundary.descriptor().equals(functionDescriptor),
            "the boundary descriptor is the declared function descriptor (int)->int");
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
        table2.define("x", new Symbol.VariableSymbol("x", Type.Int.INSTANCE, false));
        CheckResult checked2 = checks(
            Map.of(target2, Type.Int.INSTANCE, value2, Type.Int.INSTANCE), table2);
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
            Map.of(loopTarget, Type.String.INSTANCE, loopValue, Type.String.INSTANCE), table3);
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
            Type.Table.INSTANCE, value, Type.Int.INSTANCE), table);

        SemanticLowerer.ModuleLowerer lowerer = lowerer(checked);
        lowerer.openForEachScope("t");
        ValueId result = lowerer.lowerAssignment(assignment);
        lowerer.closeForEachScope();
        List<SemanticOp> ops = lowerer.ops();
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
                && chainOp.resultType().equals(RuntimeDescriptor.Table.INSTANCE),
            "ASSIGN publishes the committed value with the target position's checked "
                + "descriptor (table)");
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
            Type.String.INSTANCE, target, Type.Table.INSTANCE, value, Type.Int.INSTANCE),
            table);

        SemanticLowerer.ModuleLowerer lowerer = lowerer(checked);
        lowerer.openForEachScope("t");
        ValueId result = lowerer.lowerAssignment(assignment);
        lowerer.closeForEachScope();
        List<SemanticOp> ops = lowerer.ops();
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
                && chainOp.resultType().equals(RuntimeDescriptor.Table.INSTANCE),
            "ASSIGN publishes the committed value with the target position's checked "
                + "descriptor (table)");
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
        Type.Array arrayType = new Type.Array(Type.Int.INSTANCE);
        SymbolTable table = new SymbolTable();
        table.define("a", new Symbol.VariableSymbol("a", arrayType, false));
        CheckResult checked = checks(Map.of(receiver, arrayType, key, Type.Int.INSTANCE,
            target, Type.Int.INSTANCE, value, Type.Int.INSTANCE), table);

        SemanticLowerer.ModuleLowerer lowerer = lowerer(checked);
        lowerer.openForEachScope("a");
        ValueId result = lowerer.lowerAssignment(assignment);
        lowerer.closeForEachScope();
        List<SemanticOp> ops = lowerer.ops();
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
                && lengthOp.resultType().equals(RuntimeDescriptor.Int.INSTANCE),
            "the length child is an ARRAY_LENGTH read of the resolved receiver (int "
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
        check(normalizeOp.resultType().equals(RuntimeDescriptor.Int.INSTANCE),
            "the normalize result type is int (ArraySlot)");

        check(boundaryOp.kind() == SemanticOpKind.BOUNDARY
                && boundaryOp.failurePolicy() == FailurePolicyId.ARRAY_WRITE_BOUNDS_THEN_ELEMENT,
            "exactly one write-check boundary with ARRAY_WRITE_BOUNDS_THEN_ELEMENT");
        KindPayload.BoundaryPayload boundary =
            (KindPayload.BoundaryPayload) boundaryOp.payload();
        check(boundary.kind() == BoundaryKind.ARRAY_ELEMENT_ASSIGNMENT,
            "the boundary kind is ARRAY_ELEMENT_ASSIGNMENT");
        check(boundary.descriptor().equals(RuntimeDescriptor.Int.INSTANCE),
            "the boundary descriptor is the element descriptor (int)");
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
                && chainOp.resultType().equals(RuntimeDescriptor.Int.INSTANCE),
            "ASSIGN publishes the committed value with the target position's checked "
                + "descriptor (the element descriptor int)");
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
        Type.Array arrayType = new Type.Array(Type.Int.INSTANCE);
        SymbolTable table = new SymbolTable();
        table.define("xs", new Symbol.VariableSymbol("xs", arrayType, false));
        CheckResult checked = checks(Map.of(receiver, arrayType, lengthAccess,
            Type.Int.INSTANCE, target, Type.Int.INSTANCE, value, Type.Int.INSTANCE), table);

        SemanticLowerer.ModuleLowerer lowerer = lowerer(checked);
        lowerer.openForEachScope("xs");
        ValueId result = lowerer.lowerAssignment(assignment);
        lowerer.closeForEachScope();
        List<SemanticOp> ops = lowerer.ops();
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
                + "(keyOp result type int)");
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
                && chainOp.resultType().equals(RuntimeDescriptor.Int.INSTANCE),
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
        Type.Class classType = new Type.Class("C", "main");
        SymbolTable table = new SymbolTable();
        table.define("c", new Symbol.VariableSymbol("c", classType, false));
        CheckResult checked = checks(Map.of(receiver, classType, target, Type.Int.INSTANCE,
            value, Type.Int.INSTANCE), table);

        SemanticLowerer.ModuleLowerer lowerer = lowerer(checked);
        lowerer.openForEachScope("c");
        ValueId result = lowerer.lowerAssignment(assignment);
        lowerer.closeForEachScope();
        List<SemanticOp> ops = lowerer.ops();
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
                && chainOp.resultType().equals(RuntimeDescriptor.Int.INSTANCE),
            "ASSIGN publishes the committed value with the field descriptor (int)");
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
            Type.Array arrayType = new Type.Array(Type.Int.INSTANCE);
            SymbolTable table = new SymbolTable();
            table.define("a", new Symbol.VariableSymbol("a", arrayType, false));
            CheckResult checked = checks(Map.of(receiver, arrayType, key, Type.Int.INSTANCE,
                target, Type.Int.INSTANCE), table);
            SemanticLowerer.ModuleLowerer lowerer = lowerer(checked);
            lowerer.openForEachScope("a");
            lowerer.lowerDelete(delete);
            lowerer.closeForEachScope();
            List<SemanticOp> ops = lowerer.ops();
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
            Type.Class classType = new Type.Class("C", "main");
            SymbolTable table = new SymbolTable();
            table.define("c", new Symbol.VariableSymbol("c", classType, false));
            CheckResult checked = checks(
                Map.of(receiver, classType, target, Type.Int.INSTANCE), table);
            SemanticLowerer.ModuleLowerer lowerer = lowerer(checked);
            lowerer.openForEachScope("c");
            lowerer.lowerDelete(delete);
            lowerer.closeForEachScope();
            List<SemanticOp> ops = lowerer.ops();
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
        table.define("x", new Symbol.VariableSymbol("x", Type.Int.INSTANCE, false));
        table.define("y", new Symbol.VariableSymbol("y", Type.Int.INSTANCE, false));
        CheckResult checked = checks(Map.of(outerTarget, Type.Int.INSTANCE, innerTarget,
            Type.Int.INSTANCE, value, Type.Int.INSTANCE), table);

        SemanticLowerer.ModuleLowerer lowerer = lowerer(checked);
        ValueId result = lowerer.lowerAssignment(outer);
        List<SemanticOp> ops = lowerer.ops();
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
        CheckedSlice writeSlice = checkSlice("let t = {x: 1}\nt[0] = 1\n");
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
        CheckedSlice deleteSlice = checkSlice("let t = {x: 1}\ndelete t[0]\n");
        if (deleteSlice != null) {
            check(deleteSlice.checks().hasErrors()
                    && deleteSlice.checks().diagnostics().stream()
                        .anyMatch(d -> d.diagnosticCode() == DiagnosticCode.E3018),
                "delete t[0] fails E3018 in the checker (no DELETE op is ever produced)");
        }

        // t["k"] = 1 and delete t["k"]: checker-clean, and the assignment
        // arm lowers with the total string key (TableSlot {key: "k"}).
        CheckedSlice stringKeySlice = checkSlice("let t = {x: 1}\nt[\"k\"] = 1\n");
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
        CheckedSlice stringKeyDeleteSlice = checkSlice("let t = {x: 1}\ndelete t[\"k\"]\n");
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
        CheckedSlice lengthSlice = checkSlice("let xs: int[] = [1]\nxs.length = 2\n");
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
        Type.Array arrayType = new Type.Array(Type.Int.INSTANCE);
        SymbolTable table = new SymbolTable();
        table.define("x", new Symbol.VariableSymbol("x", Type.Int.INSTANCE, false));
        table.define("a", new Symbol.VariableSymbol("a", arrayType, false));
        CheckResult checked = checks(Map.of(target, Type.Int.INSTANCE, value,
            Type.Int.INSTANCE, receiver, arrayType, key, Type.Int.INSTANCE, indexTarget,
            Type.Int.INSTANCE), table);
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
        LoweredModuleUnit unit = lowerer.buildUnit(coverage, List.of(), INTERFACE_HASH,
            REGISTRY_HASH);
        Optional<CompilerDiagnostic> validation = SemanticIrValidator.validate(unit, facts());
        check(validation.isEmpty(),
            "the two-chain unit passes the foundation validator: " + validation);
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
            ExportPlan.empty(), Map.of(), ops);
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
            delete {x: 1}.y
            delete {x: 1}["z"]
            delete [1, 2][0]
            for (let k: string of "a") {
              delete {x: 1}[k]
              delete {x: 1}[k = "b"]
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
            SemanticProfile.DEAL_V1_2_INT32, coverage, INTERFACE_HASH, REGISTRY_HASH,
            SemanticIdAllocator.over(List.of(MODULE)));
        check(first != null && !first.hasErrors() && first.unit() != null,
            "the corpus lowers to a validated unit: " + (first == null ? "null"
                : first.diagnostics()));
        if (first == null || first.hasErrors()) {
            return;
        }
        LoweredModuleUnit unit = first.unit();
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
            SemanticProfile.DEAL_V1_2_INT32, coverage, INTERFACE_HASH, REGISTRY_HASH,
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
        testModuleCorpusAndDeterminism();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
