package deal.module;

import deal.ast.ArrayLiteralExpr;
import deal.ast.Block;
import deal.ast.ClassDeclaration;
import deal.ast.ClassField;
import deal.ast.ExpressionNode;
import deal.ast.ExportDeclaration;
import deal.ast.FunctionDeclaration;
import deal.ast.LiteralExpr;
import deal.ast.LiteralValue;
import deal.ast.NamedType;
import deal.ast.ObjectLiteralExpr;
import deal.ast.Parameter;
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
            String source = "import * as L from \"./lib\";\n"
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
        testSiblingProvidersResolveTheDeclaringModule();
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
    // Sibling providers sharing one directory (GitHub #6)
    // =========================================================================

    /**
     * The canonical module identity shared by two sibling modules in one
     * directory: the relative module components exclude the file stem, so
     * two provider modules in {@code src/} are identity-equal.
     */
    private static final ProjectModuleIdentity SIBLING_IDENTITY =
        new ProjectModuleIdentity("src", "/proj/src", List.of());

    /** The distinct identity of a provider moved to another directory. */
    private static final ProjectModuleIdentity OTHER_DIR_IDENTITY =
        new ProjectModuleIdentity("src", "/proj/src", List.of("n"));

    /**
     * One synthetic provider snapshot of the sibling probes: a program
     * declaring exactly one exported class (with one int field, at a real
     * scalar range) and one exported function whose signature names it.
     */
    private record SiblingProvider(String alias, String sourcePath,
            String modulePath, CanonicalModuleIdentity identity,
            ProgramNode program, ClassDeclaration declaration) {

        String functionName() {
            return "use" + declaration.name();
        }

        CanonicalClassIdentity classIdentity() {
            return new CanonicalClassIdentity(identity,
                declaration.name());
        }

        Type classType() {
            return Types.classType(declaration.name(), classIdentity());
        }

        DefaultPlanImport planImport() {
            Type classType = classType();
            return new DefaultPlanImport(sourcePath, modulePath, program,
                new SourceModuleLocation(sourcePath,
                    new SemanticModuleIdentity(DEPLOYMENT,
                        "file://" + sourcePath),
                    "m" + alias, projectIdentity(), identity),
                Map.of(declaration.name(), classType,
                    functionName(), Types.func(List.of(classType),
                        Type.Int.INSTANCE)),
                new SymbolTable(), false, false);
        }

        private deal.identity.ProjectModuleIdentity projectIdentity() {
            return ((CanonicalModuleIdentity.ProjectModule) identity)
                .projectIdentity();
        }
    }

    private static SiblingProvider siblingProvider(String alias,
            String sourcePath, String modulePath,
            CanonicalModuleIdentity identity, String className) {
        Span classSpan = new Span(sourcePath, 1, 1, 1, 40, 0, 39);
        List<ClassField> fields = List.of(new ClassField(classSpan, "v",
            false, false, new NamedType(classSpan, "int"),
            java.util.Optional.of(new LiteralExpr(classSpan,
                new LiteralValue.IntLiteral(0)))));
        ClassDeclaration declaration = new ClassDeclaration(classSpan,
            className, fields);
        Span functionSpan = new Span(sourcePath, 2, 1, 2, 30, 40, 69);
        FunctionDeclaration use = new FunctionDeclaration(functionSpan,
            "use" + className,
            List.of(new Parameter(functionSpan, "x",
                new NamedType(functionSpan, className))),
            new NamedType(functionSpan, "int"),
            new Block(functionSpan, List.of()), false, false);
        ProgramNode program = new ProgramNode(functionSpan, List.of(
            new ExportDeclaration(classSpan, declaration),
            new ExportDeclaration(functionSpan, use)));
        return new SiblingProvider(alias, sourcePath, modulePath, identity,
            program, declaration);
    }

    /**
     * One consumer over the given providers, in the given source
     * import-declaration order: a class default holding one contextual
     * class literal of the given field type. Returns the recorded
     * occurrences.
     */
    private static List<DefaultResourceOccurrence> siblingRun(
            List<SiblingProvider> providers, String fieldDecl,
            String literalSource, Type fieldType) {
        StringBuilder source = new StringBuilder();
        for (SiblingProvider provider : providers) {
            source.append("import * as ").append(provider.alias())
                .append(" from \"./").append(provider.alias())
                .append("\"\n");
        }
        source.append("class Outer {\n")
            .append("  ").append(fieldDecl).append(" = ")
            .append(literalSource).append(";\n")
            .append("}\n");
        LexResult lex = new Lexer(source.toString(), "main.deal").tokenize();
        check(!lex.hasErrors(), "sibling fixture lexes: "
            + lex.diagnostics());
        ParseResult parse = new Parser(lex.tokens(), "main.deal",
            lex.directiveEvents()).parse();
        check(!parse.hasErrors(), "sibling fixture parses: "
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
            "the sibling fixture declares exactly one Outer field");
        if (outer == null || outer.fields().size() != 1) {
            return List.of();
        }
        ExpressionNode defaultExpr = outer.fields().get(0).defaultExpr()
            .orElse(null);
        Map<ExpressionNode, Type> typeMap = new HashMap<>();
        if (defaultExpr != null) {
            for (ExpressionNode node : collectAll(defaultExpr)) {
                if (node instanceof ObjectLiteralExpr) {
                    typeMap.put(node, fieldType);
                }
            }
        }
        Map<String, DefaultPlanImport> imports = new LinkedHashMap<>();
        Map<String, CanonicalModuleIdentity> classification =
            new LinkedHashMap<>();
        SymbolTable scope = new SymbolTable();
        for (SiblingProvider provider : providers) {
            DefaultPlanImport planImport = provider.planImport();
            imports.put(provider.alias(), planImport);
            classification.put(provider.modulePath(), provider.identity());
            scope.define(provider.alias(), new deal.checker.Symbol
                .ModuleSymbol(provider.alias(), planImport.exports(),
                    new Span("main.deal", 1, 1, 1, 5)));
        }
        DefaultIrRecorder recorder = new DefaultIrRecorder(
            typeMap, Map.of(), imports, classification,
            CONSUMER_LOCATION, "src.main", program,
            (typeNode, resolutionScope) -> {
                if (typeNode instanceof NamedType nt) {
                    for (SiblingProvider provider : providers) {
                        if (provider.declaration().name()
                                .equals(nt.name())) {
                            return provider.classType();
                        }
                    }
                }
                return Type.Error.INSTANCE;
            },
            Map.of());
        recorder.record(defaultExpr, scope, fieldType);
        return recorder.occurrences();
    }

    /** One occurrence naming the provider's exact defining declaration. */
    private static void checkSiblingOccurrence(
            List<DefaultResourceOccurrence> occurrences,
            SiblingProvider provider, String context) {
        check(occurrences.size() == 1, context + ": exactly one occurrence,"
            + " got " + occurrences);
        if (occurrences.size() != 1) {
            return;
        }
        DefaultResourceOccurrence occurrence = occurrences.get(0);
        check(occurrence.kind().name().equals("IMPORTED_CLASS_DEFAULT_PLAN"),
            context + ": the occurrence is the imported class default"
                + " plan, got " + occurrence.kind());
        check(occurrence.toSemanticModuleIdentity()
                .canonicalResolvedSourceUri()
                .equals("file://" + provider.sourcePath()),
            context + ": the occurrence targets the declaring provider '"
                + provider.sourcePath() + "', got "
                + occurrence.toSemanticModuleIdentity()
                    .canonicalResolvedSourceUri());
        LexicalDeclarationIdentity declaration = occurrence
            .semanticResourceIdentity().lexicalDeclarationIdentity();
        check(declaration.declaredName()
                .equals(provider.declaration().name()),
            context + ": the resource identity names the demanded class, "
                + "got " + declaration.declaredName());
        check(declaration.sourceScalarRange().equals(
                provider.declaration().span().range()),
            context + ": the resource identity carries the exact defining"
                + " declaration range "
                + provider.declaration().span().range() + ", got "
                + declaration.sourceScalarRange());
    }

    private static void testSiblingProvidersResolveTheDeclaringModule() {
        System.out.println("-- sibling providers in one directory: the"
            + " declaring module owns the class literal --");
        CanonicalModuleIdentity sibling =
            new CanonicalModuleIdentity.ProjectModule(SIBLING_IDENTITY);
        CanonicalModuleIdentity otherDir =
            new CanonicalModuleIdentity.ProjectModule(OTHER_DIR_IDENTITY);
        SiblingProvider ir = siblingProvider("a", "/proj/src/a.deal",
            "src.a", sibling, "Ir");
        SiblingProvider codec = siblingProvider("b", "/proj/src/b.deal",
            "src.b", sibling, "Codec");

        // Both import orders: the declaring provider is selected by the
        // demanded class declaration, never by the identity-equal sibling.
        for (List<SiblingProvider> order : List.of(
                List.of(ir, codec), List.of(codec, ir))) {
            checkSiblingOccurrence(siblingRun(order, "x: Ir", "{ v: 1 }",
                ir.classType()), ir, "the Ir probe");
            checkSiblingOccurrence(siblingRun(order, "y: Codec",
                "{ v: 1 }", codec.classType()), codec,
                "the Codec probe");
        }

        // Single import: the declaring provider alone resolves.
        checkSiblingOccurrence(siblingRun(List.of(ir), "x: Ir", "{ v: 1 }",
            ir.classType()), ir, "the single-import control");

        // Different directory: the distinct identity resolves directly.
        SiblingProvider loneCodec = siblingProvider("b",
            "/proj/src/n/b.deal", "src.n.b", otherDir, "Codec");
        checkSiblingOccurrence(siblingRun(List.of(ir, loneCodec), "y: Codec",
            "{ v: 1 }", loneCodec.classType()), loneCodec,
            "the different-directory control");
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
