package deal.module;

import deal.ast.ArrayLiteralExpr;
import deal.ast.ClassDeclaration;
import deal.ast.ClassField;
import deal.ast.ExpressionNode;
import deal.ast.ExportDeclaration;
import deal.ast.LiteralExpr;
import deal.ast.LiteralValue;
import deal.ast.NamedType;
import deal.ast.ObjectLiteralExpr;
import deal.ast.ProgramNode;
import deal.ast.Span;
import deal.ast.StatementNode;
import deal.ast.TypeNode;
import deal.types.Types;
import deal.checker.SymbolTable;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.identity.CanonicalClassIdentity;
import deal.identity.CanonicalModuleIdentity;
import deal.identity.ProjectModuleIdentity;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.project.ProjectDeploymentIdentity;
import deal.types.Type;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The focused typed-evaluator-IR walker battery of ISSUE-0541 (the
 * supplementary unit half; the orchestrator-level planner battery is
 * {@code test/DefaultSemanticPlannerTest}): the complete IR walk over
 * synthetic checked facts — nested contextual class literals, including
 * a literal directly inside an array literal (typed by the checked facts
 * the way the contextual checker records them), nested literals inside
 * literal fields, call arguments, the ordered occurrence records with
 * first-occurrence ranges and semantic-resource-identity deduplication,
 * resolved bindings, and the E3020 sync gate (await at evaluator scope
 * rejected; await inside a nested function-expression body legal).
 */
public final class DefaultIrRecorderTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static final DiagnosticRange RANGE =
        new DiagnosticRange("main.deal", 1, 1, 1, 5, 0, 4, 4,
            RangeOrigin.SOURCE);

    private static final ProjectDeploymentIdentity DEPLOYMENT =
        new ProjectDeploymentIdentity("file:///proj/deal.json",
            "a".repeat(64));

    private static final SourceModuleLocation CONSUMER_LOCATION =
        new SourceModuleLocation("/proj/src/main.deal",
            new SemanticModuleIdentity(DEPLOYMENT,
                "file:///proj/src/main.deal"),
            "mconsumer", null,
            new CanonicalModuleIdentity.ProjectModule(
                new ProjectModuleIdentity("src", "/proj/src",
                    List.of())));

    private static final SourceModuleLocation PROVIDER_LOCATION =
        new SourceModuleLocation("/proj/src/lib.deal",
            new SemanticModuleIdentity(DEPLOYMENT,
                "file:///proj/src/lib.deal"),
            "mprovider", null,
            new CanonicalModuleIdentity.ProjectModule(
                new ProjectModuleIdentity("src", "/proj/src",
                    List.of())));

    private static final CanonicalClassIdentity REC_IDENTITY =
        new CanonicalClassIdentity(
            new CanonicalModuleIdentity.ProjectModule(
                new ProjectModuleIdentity("src", "/proj/src",
                    List.of())),
            "Rec");

    private static final Type REC_TYPE = Types.classType("Rec",
        REC_IDENTITY);

    // =========================================================================
    // Harness
    // =========================================================================

    /**
     * Parses a class default expression and runs the recorder over
     * synthetic checked facts: the typeMap pins the given expression
     * nodes to their resolved types (the contextual-class-literal
     * positions included), the import surface resolves {@code L} to a
     * provider declaring class {@code Rec}, and the field type is the
     * default's expected type.
     */
    private static final class RecorderRun {
        final DefaultIrRecorder recorder;
        final DefaultIrNode root;
        final ExpressionNode defaultExpr;

        RecorderRun(String fieldDecl, String defaultSource,
                     Type fieldType, Type literalType) {
            String source = "import * as L from \"./lib\"\n"
                + "class Outer {\n"
                + "  " + fieldDecl + " = " + defaultSource + ";\n"
                + "}\n";
            LexResult lex = new Lexer(source, "main.deal").tokenize();
            check(!lex.hasErrors(), "recorder fixture lexes: "
                + lex.diagnostics());
            ParseResult parse = new Parser(lex.tokens(), "main.deal",
                lex.directiveEvents()).parse();
            check(!parse.hasErrors(), "recorder fixture parses: "
                + parse.diagnostics());
            ProgramNode program = parse.program();
            ClassDeclaration outer = null;
            for (StatementNode stmt : program.statements()) {
                if (stmt instanceof ClassDeclaration cd
                        && cd.name().equals("Outer")) {
                    outer = cd;
                }
            }
            check(outer != null && outer.fields().size() == 1,
                "the fixture declares exactly one Outer field");
            if (outer == null || outer.fields().size() != 1) {
                defaultExpr = null;
                recorder = null;
                root = null;
                return;
            }
            defaultExpr = outer.fields().get(0).defaultExpr().orElse(null);
            check(defaultExpr != null, "the fixture field has a default");

            Map<ExpressionNode, Type> typeMap = new HashMap<>();
            if (literalType != null) {
                for (ExpressionNode node : collectAll(defaultExpr)) {
                    if (node instanceof ObjectLiteralExpr) {
                        typeMap.put(node, literalType);
                    }
                }
            }
            ProgramNode providerProgram = providerProgram();
            Map<String, DefaultPlanImport> imports = Map.of("L",
                new DefaultPlanImport("/proj/src/lib.deal", "src.lib",
                    providerProgram, PROVIDER_LOCATION,
                    Map.of("Rec", REC_TYPE, "getTag",
                        Types.func(List.of(REC_TYPE), Type.Int.INSTANCE)),
                    new SymbolTable(), false, false));
            // The declaration scope: the import alias L and the
            // intrinsic int conversion.
            SymbolTable scope = new SymbolTable();
            scope.define("L", new deal.checker.Symbol.ModuleSymbol("L",
                Map.of("Rec", REC_TYPE, "getTag",
                    Types.func(List.of(REC_TYPE), Type.Int.INSTANCE)),
                new Span("main.deal", 1, 1, 1, 5)));
            scope.define("int", new deal.checker.Symbol.IntrinsicSymbol(
                "int", Types.func(List.of(Type.Number.INSTANCE),
                    Type.Int.INSTANCE), null));
            Map<String, CanonicalModuleIdentity> classification =
                Map.of("src.main",
                    new CanonicalModuleIdentity.ProjectModule(
                        new ProjectModuleIdentity("src", "/proj/src",
                            List.of())),
                    "src.lib",
                    new CanonicalModuleIdentity.ProjectModule(
                        new ProjectModuleIdentity("src", "/proj/src",
                            List.of())));
            recorder = new DefaultIrRecorder(
                typeMap, Map.of(), imports, classification,
                CONSUMER_LOCATION, "src.main", program,
                (typeNode, resolutionScope) -> REC_TYPE,
                Map.of("Rec", "L"));
            root = recorder.record(defaultExpr, scope, fieldType);
        }

        List<DefaultResourceOccurrence> occurrences() {
            return recorder.occurrences();
        }

        List<CompilerDiagnostic> awaitDiagnostics() {
            return recorder.awaitDiagnostics();
        }

        Map<String, deal.checker.Symbol> bindings() {
            return recorder.bindings();
        }
    }

    /** A provider program declaring the exported class Rec and the
     * exported function getTag(r: Rec): int. */
    private static ProgramNode providerProgram() {
        Span span = new Span("lib.deal", 1, 1, 1, 5);
        List<ClassField> fields = List.of(new ClassField(span, "tag",
            false, false, new NamedType(span, "int"),
            java.util.Optional.of(new LiteralExpr(span,
                new LiteralValue.IntLiteral(0)))));
        ClassDeclaration rec = new ClassDeclaration(span, "Rec", fields);
        deal.ast.FunctionDeclaration getTag = new deal.ast.FunctionDeclaration(
            span, "getTag",
            List.of(new deal.ast.Parameter(span, "r",
                new NamedType(span, "Rec"))),
            new NamedType(span, "int"),
            new deal.ast.Block(span, List.of()), false, false);
        return new ProgramNode(span,
            List.of(new ExportDeclaration(span, rec),
                new ExportDeclaration(span, getTag)));
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    public static void main(String[] args) {
        testClassLiteralInsideArrayLiteral();
        testNestedLiteralInsideLiteralField();
        testCallSiteRangeAndDeduplication();
        testSameModuleBindingsRecorded();
        testAwaitGate();
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.err.println("DefaultIrRecorderTest FAILED: " + failed
                + " failure(s)");
            System.exit(1);
        }
    }

    // =========================================================================
    // The complete walk: nested class literals inside arrays
    // =========================================================================

    private static void testClassLiteralInsideArrayLiteral() {
        System.out.println("-- Nested class literal inside an array"
            + " literal --");
        RecorderRun run = new RecorderRun("xs: Rec[]", "[{ tag: 1 }]",
            Types.array(REC_TYPE), REC_TYPE);
        // Pin the literal to the class type the contextual checker
        // records (the walker is type-directed over the checked facts).
        ObjectLiteralExpr literal = null;
        for (ExpressionNode node : collectAll(run.defaultExpr)) {
            if (node instanceof ObjectLiteralExpr obj) {
                literal = obj;
            }
        }
        check(literal != null, "the array literal holds one object"
            + " literal");
        List<DefaultResourceOccurrence> occurrences = run.occurrences();
        check(occurrences.size() == 1,
            "exactly one occurrence for the nested literal, got "
                + occurrences.size());
        if (!occurrences.isEmpty()) {
            DefaultResourceOccurrence occurrence = occurrences.get(0);
            check(occurrence.kind().name()
                    .equals("IMPORTED_CLASS_DEFAULT_PLAN"),
                "the occurrence is the imported class default plan");
            check(occurrence.sourceRange().startLine() == 3
                    && occurrence.sourceRange().startColumn() == 16,
                "the occurrence keeps the literal range (line 3 column"
                    + " 16), got " + occurrence.sourceRange().startLine()
                    + ":" + occurrence.sourceRange().startColumn());
            check(occurrence.importAlias().equals("L")
                    && "Rec".equals(occurrence.semanticResourceIdentity()
                        .lexicalDeclarationIdentity().declaredName()),
                "the occurrence carries alias 'L' and the Rec resource"
                    + " identity");
            check("file:///proj/src/lib.deal".equals(
                    occurrence.toSemanticModuleIdentity()
                        .canonicalResolvedSourceUri()),
                "the occurrence targets the provider module identity");
        }
    }

    // =========================================================================
    // Nested literals: a literal inside a literal field
    // =========================================================================

    private static void testNestedLiteralInsideLiteralField() {
        System.out.println("-- Nested literal inside a literal field --");
        RecorderRun run = new RecorderRun("r: Rec",
            "{ inner: { tag: 9 } }", REC_TYPE, REC_TYPE);
        // The outer and inner literals are both typed as the class.
        List<ExpressionNode> literals = collectAll(run.defaultExpr);
        int literalCount = 0;
        for (ExpressionNode node : literals) {
            if (node instanceof ObjectLiteralExpr) {
                literalCount++;
            }
        }
        check(literalCount == 2,
            "the default holds two nested class literals");
        List<DefaultResourceOccurrence> occurrences = run.occurrences();
        check(occurrences.size() == 1,
            "both literals deduplicate to one occurrence by semantic"
                + " resource identity, got " + occurrences.size());
        if (!occurrences.isEmpty()) {
            check(occurrences.get(0).sourceRange().startColumn() == 12,
                "the first occurrence keeps the OUTER literal range"
                    + " (column 12), got "
                    + occurrences.get(0).sourceRange().startColumn());
        }
    }

    // =========================================================================
    // Call-site ranges and deduplication across fields
    // =========================================================================

    private static void testCallSiteRangeAndDeduplication() {
        System.out.println("-- Call-site range and identity"
            + " deduplication --");
        // The recorder walk records the call before its callee member
        // access: the first occurrence keeps the CALL range.
        RecorderRun run = new RecorderRun("n: int",
            "L.getTag({ tag: 3 })", Type.Int.INSTANCE, REC_TYPE);
        List<DefaultResourceOccurrence> occurrences = run.occurrences();
        check(occurrences.size() == 2,
            "the call and the argument literal produce two occurrences,"
                + " got " + occurrences.size());
        if (occurrences.size() == 2) {
            DefaultResourceOccurrence call = occurrences.get(0);
            check(call.kind().name().equals("IMPORTED_FUNCTION_WRAPPER")
                    && "getTag".equals(call.semanticResourceIdentity()
                        .lexicalDeclarationIdentity().declaredName()),
                "the first occurrence is the imported getTag wrapper");
            check(call.sourceRange().startColumn() == 12,
                "the wrapper keeps the CALL-site range (column 13), got "
                    + call.sourceRange().startColumn());
            DefaultResourceOccurrence plan = occurrences.get(1);
            check(plan.kind().name()
                    .equals("IMPORTED_CLASS_DEFAULT_PLAN"),
                "the second occurrence is the argument literal's class"
                    + " plan");
        }
    }

    // =========================================================================
    // Same-module bindings
    // =========================================================================

    private static void testSameModuleBindingsRecorded() {
        System.out.println("-- Same-module targets record bindings, no"
            + " occurrences --");
        RecorderRun run = new RecorderRun("n: int", "int(3.0)",
            Type.Int.INSTANCE, null);
        check(run.occurrences().isEmpty(),
            "an intrinsic conversion publishes no occurrence");
        check(run.bindings().containsKey("int"),
            "the intrinsic call records the int intrinsic binding");
        check(run.bindings().get("int")
                instanceof deal.checker.Symbol.IntrinsicSymbol,
            "the recorded binding is the checker's IntrinsicSymbol");
        check(run.root.kind().name().equals("CALL")
                && run.root.target() != null
                && run.root.target().kind().name().equals("INTRINSIC"),
            "the intrinsic call carries the builtin target");
    }

    // =========================================================================
    // The E3020 sync gate
    // =========================================================================

    private static void testAwaitGate() {
        System.out.println("-- E3020 sync gate: evaluator scope vs"
            + " nested function-expression body --");
        // Await at evaluator scope (the checker-accepted shape inside an
        // async function body): E3020 at the await range.
        RecorderRun evaluatorScope = new RecorderRun("x: int",
            "await source()", Type.Int.INSTANCE, null);
        List<CompilerDiagnostic> diagnostics =
            evaluatorScope.awaitDiagnostics();
        check(diagnostics.size() == 1
                && "E3020".equals(diagnostics.get(0).code()),
            "evaluator-scope await is E3020, got " + diagnostics);
        if (!diagnostics.isEmpty()) {
            check(diagnostics.get(0).range().startLine() == 3
                    && diagnostics.get(0).range().startColumn() == 12,
                "E3020 anchors the await range (line 3 column 12), got "
                    + diagnostics.get(0).range().startLine() + ":"
                    + diagnostics.get(0).range().startColumn());
        }

        // Await inside a nested async function-expression body: legal.
        RecorderRun nested = new RecorderRun("f: async () => int",
            "async function(): int { return await source(); }",
            Types.func(List.of(), Type.Int.INSTANCE, true), null);
        check(nested.awaitDiagnostics().isEmpty(),
            "await inside the nested async function-expression body is"
                + " legal, got " + nested.awaitDiagnostics());
        check(nested.root.kind().name().equals("FUNCTION_EXPRESSION")
                && !nested.root.children().isEmpty(),
            "the function-expression body statements are part of the"
                + " complete IR walk");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** All expression nodes under {@code root}, pre-order. */
    private static List<ExpressionNode> collectAll(ExpressionNode root) {
        List<ExpressionNode> nodes = new java.util.ArrayList<>();
        collectInto(root, nodes);
        return nodes;
    }

    private static void collectInto(ExpressionNode node,
                                    List<ExpressionNode> nodes) {
        nodes.add(node);
        switch (node) {
            case deal.ast.BinaryExpr bin -> {
                collectInto(bin.left(), nodes);
                collectInto(bin.right(), nodes);
            }
            case deal.ast.UnaryExpr un ->
                collectInto(un.expr(), nodes);
            case deal.ast.CallExpr call -> {
                collectInto(call.callee(), nodes);
                for (ExpressionNode arg : call.args()) {
                    collectInto(arg, nodes);
                }
            }
            case deal.ast.MemberAccessExpr mae ->
                collectInto(mae.object(), nodes);
            case deal.ast.IndexExpr idx -> {
                collectInto(idx.array(), nodes);
                collectInto(idx.index(), nodes);
            }
            case deal.ast.ArrayLiteralExpr arr -> {
                for (ExpressionNode element : arr.elements()) {
                    collectInto(element, nodes);
                }
            }
            case deal.ast.ObjectLiteralExpr obj -> {
                for (deal.ast.Property property : obj.properties()) {
                    collectInto(property.value(), nodes);
                }
            }
            case deal.ast.HasExpr has ->
                collectInto(has.object(), nodes);
            case deal.ast.AssignmentExpr assign -> {
                collectInto(assign.target(), nodes);
                collectInto(assign.value(), nodes);
            }
            case deal.ast.AwaitExpression await ->
                collectInto(await.callee(), nodes);
            case deal.ast.TemplateLiteralExpr tl -> {
                for (ExpressionNode part : tl.parts()) {
                    collectInto(part, nodes);
                }
            }
            default -> {
                // leaves: literals, identifiers, function expressions
                // (bodies walked separately by the recorder)
            }
        }
    }
}
