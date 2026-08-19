package deal.test;

import deal.ast.*;
import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.NameResolver;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.types.Type;
import deal.types.Types;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * JUnit4 + Hamcrest tests for the cross-module field-type-resolution seam:
 * {@code ModuleResolver.resolveTypeNodeInModule} +
 * {@code TypeChecker}'s foreign-first resolution with the plain
 * importing-module fallback.
 *
 * <p>Drives {@link TypeChecker} over programs equivalent to the merged
 * conformance fixtures with a stub {@link ModuleResolver} that implements
 * both {@code resolveClassSymbol} and {@code resolveTypeNodeInModule}, and
 * asserts (a) the nested-array member access types without E-diagnostics,
 * (b) the array/member/index expression types, and (c) that a resolver
 * returning {@code null} produces the identical fallback behavior to
 * today's local resolution (silent {@code Type.Error} degradation).</p>
 */
public class CrossModuleTypingTest {

    private static final String LIB = "./jsonable_batch2_lib";

    private static Span span() {
        return Span.synthetic("main.deal");
    }

    // =========================================================================
    // Stub resolver implementing both cross-module seams
    // =========================================================================

    private static final class TypingResolver implements ModuleResolver {
        private final Map<String, Map<String, Type>> modules = new HashMap<>();
        private final Map<String, Symbol.ClassSymbol> classSymbols = new HashMap<>();
        private final boolean supportTypeNodeResolution;
        private final boolean supportClassSymbols;

        TypingResolver(boolean supportTypeNodeResolution) {
            this(supportTypeNodeResolution, true);
        }

        TypingResolver(boolean supportTypeNodeResolution,
                       boolean supportClassSymbols) {
            this.supportTypeNodeResolution = supportTypeNodeResolution;
            this.supportClassSymbols = supportClassSymbols;
        }

        void register(String path, Map<String, Type> exports) {
            modules.put(path, exports);
        }

        void registerClassSymbol(String modulePath, Symbol.ClassSymbol classSymbol) {
            classSymbols.put(modulePath + ":" + classSymbol.name(), classSymbol);
        }

        @Override
        public Map<String, Type> resolveModule(String modulePath,
                String importingModule, Set<String> modulesInProgress)
                throws ModuleNotFoundException {
            Map<String, Type> exports = modules.get(modulePath);
            if (exports == null) {
                throw new ModuleNotFoundException("Module not found: " + modulePath);
            }
            return exports;
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className,
                String modulePath, String importingModule)
                throws ModuleNotFoundException {
            if (!supportClassSymbols) {
                return null; // today's harness behavior
            }
            return classSymbols.get(modulePath + ":" + className);
        }

        @Override
        public Type resolveTypeNodeInModule(TypeNode typeNode,
                String modulePath, String importingModule)
                throws ModuleNotFoundException {
            if (!supportTypeNodeResolution) {
                return null; // unsupported → checker falls back to local resolution
            }
            return resolveForeign(typeNode, modulePath);
        }

        /** Resolves a field TypeNode against the owning module's declarations. */
        private static Type resolveForeign(TypeNode typeNode, String modulePath) {
            return switch (typeNode) {
                case NamedType nt -> switch (nt.name()) {
                    case "null" -> Type.Null.INSTANCE;
                    case "boolean" -> Type.Boolean.INSTANCE;
                    case "int" -> Type.Int.INSTANCE;
                    case "number" -> Type.Number.INSTANCE;
                    case "string" -> Type.String.INSTANCE;
                    case "table" -> Type.Table.INSTANCE;
                    default -> Types.classType(nt.name(), modulePath);
                };
                case ArrayType at -> {
                    Type elem = resolveForeign(at.elementType(), modulePath);
                    if (elem == Type.Error.INSTANCE) yield Type.Error.INSTANCE;
                    yield Types.array(elem);
                }
                case NullableType nt -> {
                    Type inner = resolveForeign(nt.innerType(), modulePath);
                    if (inner == Type.Error.INSTANCE) yield Type.Error.INSTANCE;
                    yield Types.nullable(inner);
                }
                default -> null;
            };
        }
    }

    /** Builds the batch-2 companion module's class symbols and exports. */
    private static TypingResolver batch2Resolver(boolean supportTypeNodeResolution) {
        return batch2Resolver(supportTypeNodeResolution, true);
    }

    private static TypingResolver batch2Resolver(boolean supportTypeNodeResolution,
                                                 boolean supportClassSymbols) {
        TypingResolver resolver =
            new TypingResolver(supportTypeNodeResolution, supportClassSymbols);
        Span s = span();

        Symbol.ClassSymbol child = new Symbol.ClassSymbol("Child", List.of(
            new ClassField(s, "value", false, false,
                new NamedType(s, "int"), Optional.empty())), LIB);
        Symbol.ClassSymbol parent = new Symbol.ClassSymbol("Parent", List.of(
            new ClassField(s, "children", false, false,
                new ArrayType(s, new NamedType(s, "Child")), Optional.empty())), LIB);
        Symbol.ClassSymbol maybeChild = new Symbol.ClassSymbol("MaybeChild", List.of(
            new ClassField(s, "child", true, true,
                new NullableType(s, new NamedType(s, "Child")), Optional.empty())), LIB);
        resolver.registerClassSymbol(LIB, child);
        resolver.registerClassSymbol(LIB, parent);
        resolver.registerClassSymbol(LIB, maybeChild);

        Type parentType = Types.classType("Parent", LIB);
        Type childType = Types.classType("Child", LIB);
        Type maybeChildType = Types.classType("MaybeChild", LIB);
        resolver.register(LIB, Map.of(
            "Parent", parentType,
            "Child", childType,
            "MaybeChild", maybeChildType,
            "Parent$fromJson", new Type.Func(List.of(Type.String.INSTANCE),
                Types.nullable(parentType)),
            "Parent$toJson", new Type.Func(List.of(parentType),
                Type.String.INSTANCE),
            "MaybeChild$fromJson", new Type.Func(List.of(Type.String.INSTANCE),
                Types.nullable(maybeChildType)),
            "makeParent", new Type.Func(
                List.of(Type.Int.INSTANCE, Type.Int.INSTANCE),
                parentType)));
        return resolver;
    }

    // =========================================================================
    // Compile helper
    // =========================================================================

    private record TypedProgram(CheckResult result, ProgramNode program) {}

    private static TypedProgram check(String source, TypingResolver resolver) {
        String filename = "main.deal";
        LexResult lex = new Lexer(source, filename).tokenize();
        ParseResult parse = new Parser(lex.tokens(), filename).parse();
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        assertTrue("name-resolution diagnostics: " + nr.diagnostics(),
            nr.diagnostics().stream().noneMatch(
                d -> "error".equals(d.severity())));
        CheckResult result = TypeChecker.check(filename, symTable, nr, parse.program());
        return new TypedProgram(result, parse.program());
    }

    // =========================================================================
    // AST node collection (mini-scanner for typeMap assertions)
    // =========================================================================

    private record FoundNodes(List<MemberAccessExpr> memberAccesses,
                              List<IndexExpr> indexExprs) {}

    private static FoundNodes collect(ProgramNode program) {
        List<MemberAccessExpr> memberAccesses = new ArrayList<>();
        List<IndexExpr> indexExprs = new ArrayList<>();
        for (StatementNode stmt : program.statements()) {
            collect(stmt, memberAccesses, indexExprs);
        }
        return new FoundNodes(memberAccesses, indexExprs);
    }

    private static void collect(StatementNode stmt,
            List<MemberAccessExpr> memberAccesses,
            List<IndexExpr> indexExprs) {
        switch (stmt) {
            case VariableDeclaration vd -> collect(vd.initializer(), memberAccesses, indexExprs);
            case FunctionDeclaration fd -> {
                if (fd.body() != null) {
                    for (StatementNode inner : fd.body().statements()) {
                        collect(inner, memberAccesses, indexExprs);
                    }
                }
            }
            case Block b -> {
                for (StatementNode inner : b.statements()) {
                    collect(inner, memberAccesses, indexExprs);
                }
            }
            case IfStatement is -> {
                collect(is.condition(), memberAccesses, indexExprs);
                collect(is.thenBlock(), memberAccesses, indexExprs);
                is.elseBranch().ifPresent(either -> {
                    switch (either) {
                        case Either.Left<IfStatement, Block> left ->
                            collect(left.value(), memberAccesses, indexExprs);
                        case Either.Right<IfStatement, Block> right ->
                            collect(right.value(), memberAccesses, indexExprs);
                    }
                });
            }
            case WhileStatement ws -> {
                collect(ws.condition(), memberAccesses, indexExprs);
                collect(ws.body(), memberAccesses, indexExprs);
            }
            case ReturnStatement rs ->
                rs.expr().ifPresent(e -> collect(e, memberAccesses, indexExprs));
            case ExpressionStatement es -> collect(es.expr(), memberAccesses, indexExprs);
            case DeleteStatement ds -> collect(ds.target(), memberAccesses, indexExprs);
            case ThrowStatement ts -> collect(ts.expr(), memberAccesses, indexExprs);
            case ExportDeclaration ed -> collect(ed.declaration(), memberAccesses, indexExprs);
            case ForStatement fs -> {
                fs.init().ifPresent(init -> {
                    if (init instanceof ForInit.VarDecl vd) {
                        collect(vd.decl().initializer(), memberAccesses, indexExprs);
                    }
                });
                fs.condition().ifPresent(c -> collect(c, memberAccesses, indexExprs));
                fs.update().ifPresent(u -> collect(u, memberAccesses, indexExprs));
                collect(fs.body(), memberAccesses, indexExprs);
            }
            default -> { /* no nested expressions of interest */ }
        }
    }

    private static void collect(ExpressionNode expr,
            List<MemberAccessExpr> memberAccesses,
            List<IndexExpr> indexExprs) {
        switch (expr) {
            case MemberAccessExpr mae -> {
                memberAccesses.add(mae);
                collect(mae.object(), memberAccesses, indexExprs);
            }
            case IndexExpr idx -> {
                indexExprs.add(idx);
                collect(idx.array(), memberAccesses, indexExprs);
                collect(idx.index(), memberAccesses, indexExprs);
            }
            case BinaryExpr bin -> {
                collect(bin.left(), memberAccesses, indexExprs);
                collect(bin.right(), memberAccesses, indexExprs);
            }
            case UnaryExpr un -> collect(un.expr(), memberAccesses, indexExprs);
            case CallExpr call -> {
                collect(call.callee(), memberAccesses, indexExprs);
                for (ExpressionNode arg : call.args()) {
                    collect(arg, memberAccesses, indexExprs);
                }
            }
            case ArrayLiteralExpr arr -> {
                for (ExpressionNode elem : arr.elements()) {
                    collect(elem, memberAccesses, indexExprs);
                }
            }
            case ObjectLiteralExpr obj -> {
                for (Property prop : obj.properties()) {
                    collect(prop.value(), memberAccesses, indexExprs);
                }
            }
            case AssignmentExpr assign -> {
                collect(assign.target(), memberAccesses, indexExprs);
                collect(assign.value(), memberAccesses, indexExprs);
            }
            case HasExpr has -> collect(has.object(), memberAccesses, indexExprs);
            case TemplateLiteralExpr tl -> {
                for (ExpressionNode part : tl.parts()) {
                    collect(part, memberAccesses, indexExprs);
                }
            }
            case AwaitExpression await -> collect(await.callee(), memberAccesses, indexExprs);
            default -> { /* leaf */ }
        }
    }

    // =========================================================================
    // (a) + (b): nested-array member access types without E-diagnostics
    // =========================================================================

    @Test
    public void nestedArrayMemberAccessTypesThroughTheForeignResolver() {
        String source =
            "import * as Lib from \"./jsonable_batch2_lib\"\n" +
            "export function test_cross_module(): null {\n" +
            "  let p: Lib.Parent = Lib.makeParent(3, 4);\n" +
            "  let json: string = Lib.Parent$toJson(p);\n" +
            "  let p2: Lib.Parent | null = Lib.Parent$fromJson(json);\n" +
            "  if (p2 !== null) {\n" +
            "    if (p2.children[1].value !== 4) { throw { code: \"TEST_FAIL\", message: \"mismatch\" }; }\n" +
            "    return null;\n" +
            "  }\n" +
            "  throw { code: \"TEST_FAIL\", message: \"fromJson failed\" };\n" +
            "}\n";

        TypedProgram typed = check(source, batch2Resolver(true));

        // (a) no error diagnostics
        assertTrue("unexpected diagnostics: " + typed.result().diagnostics(),
            !typed.result().hasErrors());

        // (b) the index expression is array-typed and yields the element class
        FoundNodes nodes = collect(typed.program());
        IndexExpr index = nodes.indexExprs().stream()
            .filter(idx -> idx.array() instanceof MemberAccessExpr mae
                && mae.field().equals("children"))
            .findFirst().orElse(null);
        assertNotNull("children index expression", index);

        Type memberType = typed.result().typeMap().get(index.array());
        assertNotNull("p2.children has a type", memberType);
        assertThat("p2.children is array-typed", memberType, instanceOf(Type.Array.class));
        Type.Array arr = (Type.Array) memberType;
        assertThat("array element is the companion's Child",
            arr.element(), instanceOf(Type.Class.class));
        assertThat("element module path is the companion",
            ((Type.Class) arr.element()).modulePath(), is(LIB));

        Type indexType = typed.result().typeMap().get(index);
        assertThat("index expression yields Child", indexType, instanceOf(Type.Class.class));

        // The chained .value member access types as int.
        MemberAccessExpr valueAccess = nodes.memberAccesses().stream()
            .filter(mae -> mae.field().equals("value")
                && mae.object() instanceof IndexExpr)
            .findFirst().orElse(null);
        assertNotNull("children[1].value member access", valueAccess);
        assertThat("children[1].value is int",
            typed.result().typeMap().get(valueAccess), is(Type.Int.INSTANCE));
    }

    // =========================================================================
    // (c): resolver returning null → identical fallback to local resolution
    // =========================================================================

    @Test
    public void nullForeignResultFallsBackToTodayLocalResolution() {
        String source =
            "import * as Lib from \"./jsonable_batch2_lib\"\n" +
            "export function test_cross_module(): null {\n" +
            "  let p: Lib.Parent = Lib.makeParent(3, 4);\n" +
            "  let json: string = Lib.Parent$toJson(p);\n" +
            "  let p2: Lib.Parent | null = Lib.Parent$fromJson(json);\n" +
            "  if (p2 !== null) {\n" +
            "    if (p2.children[1].value !== 4) { throw { code: \"TEST_FAIL\", message: \"mismatch\" }; }\n" +
            "    return null;\n" +
            "  }\n" +
            "  throw { code: \"TEST_FAIL\", message: \"fromJson failed\" };\n" +
            "}\n";

        TypedProgram typed = check(source, batch2Resolver(false));

        // Identical fallback behavior: silent Type.Error degradation, no
        // new diagnostics.
        assertTrue("fallback emits no diagnostics: " + typed.result().diagnostics(),
            !typed.result().hasErrors());

        FoundNodes nodes = collect(typed.program());
        IndexExpr index = nodes.indexExprs().stream()
            .filter(idx -> idx.array() instanceof MemberAccessExpr mae
                && mae.field().equals("children"))
            .findFirst().orElse(null);
        assertNotNull("children index expression", index);
        assertThat("fallback types the member as Error (today's behavior)",
            typed.result().typeMap().get(index.array()), is(Type.Error.INSTANCE));
    }

    // =========================================================================
    // Optional-nullable class field path (merged fixture shape)
    // =========================================================================

    @Test
    public void optionalNullableClassFieldPathTypesWithoutDiagnostics() {
        String source =
            "import * as Lib from \"./jsonable_batch2_lib\"\n" +
            "export function test_optional_nullable(): null {\n" +
            "  let value: Lib.MaybeChild | null = Lib.MaybeChild$fromJson(\"{\\\"child\\\":{\\\"value\\\":8}}\");\n" +
            "  if (value !== null) {\n" +
            "    if (value.child !== null) {\n" +
            "      if (value.child.value !== 8) { throw { code: \"TEST_FAIL\", message: \"nested value mismatch\" }; }\n" +
            "      return null;\n" +
            "    }\n" +
            "  }\n" +
            "  throw { code: \"TEST_FAIL\", message: \"nested value fromJson failed\" };\n" +
            "}\n";

        TypedProgram typed = check(source, batch2Resolver(true));
        assertTrue("unexpected diagnostics: " + typed.result().diagnostics(),
            !typed.result().hasErrors());

        FoundNodes nodes = collect(typed.program());
        MemberAccessExpr childAccess = nodes.memberAccesses().stream()
            .filter(mae -> mae.field().equals("child")
                && mae.object() instanceof IdentifierExpr id
                && id.name().equals("value"))
            .findFirst().orElse(null);
        assertNotNull("value.child member access", childAccess);

        Type childType = typed.result().typeMap().get(childAccess);
        assertNotNull("value.child has a type", childType);
        assertThat("value.child is nullable", childType, instanceOf(Type.Nullable.class));
        assertThat("nullable inner is the companion's Child",
            ((Type.Nullable) childType).inner(), instanceOf(Type.Class.class));

        MemberAccessExpr valueAccess = nodes.memberAccesses().stream()
            .filter(mae -> mae.field().equals("value")
                && mae.object() instanceof MemberAccessExpr inner
                && inner.field().equals("child"))
            .findFirst().orElse(null);
        assertNotNull("value.child.value member access", valueAccess);
        assertThat("value.child.value is int",
            typed.result().typeMap().get(valueAccess), is(Type.Int.INSTANCE));
    }

    // =========================================================================
    // checkClassConstruction foreign-first field typing
    // =========================================================================

    @Test
    public void importedClassConstructionChecksThroughTheForeignResolver() {
        // `children: Child[]` field typed foreign-first → the empty array
        // literal checks against Array(Child); locally it would be Error.
        String source =
            "import * as Lib from \"./jsonable_batch2_lib\"\n" +
            "export function make_parent(): Lib.Parent {\n" +
            "  return { children: [] };\n" +
            "}\n";

        TypedProgram typed = check(source, batch2Resolver(true));
        assertTrue("construction types without diagnostics: "
            + typed.result().diagnostics(), !typed.result().hasErrors());

        // Null type-node resolution falls back to local resolution: the
        // field type is Error, so the empty array literal cannot infer and
        // E3002 fires — the identical pre-fix degradation.
        TypedProgram fallback = check(source, batch2Resolver(false));
        assertTrue("fallback construction reports E3002: " + fallback.result().diagnostics(),
            fallback.result().diagnostics().stream().anyMatch(
                d -> "error".equals(d.severity()) && "E3002".equals(d.code())));

        // With no cross-module class symbols either (today's harness),
        // construction of an imported class literal is the E3004
        // unknown-class path.
        TypedProgram unknown = check(source, batch2Resolver(false, false));
        assertTrue("unknown class reports E3004: " + unknown.result().diagnostics(),
            unknown.result().diagnostics().stream().anyMatch(
                d -> "error".equals(d.severity()) && "E3004".equals(d.code())));
    }
}
