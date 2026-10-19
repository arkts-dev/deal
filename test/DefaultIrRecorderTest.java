package deal.module;

import deal.ast.ArrayLiteralExpr;
import deal.ast.Block;
import deal.ast.ClassDeclaration;
import deal.ast.ClassField;
import deal.ast.ExpressionNode;
import deal.ast.ExpressionStatement;
import deal.ast.ExportDeclaration;
import deal.ast.FunctionDeclaration;
import deal.ast.LiteralExpr;
import deal.ast.LiteralValue;
import deal.ast.NamedType;
import deal.ast.ObjectLiteralExpr;
import deal.ast.Parameter;
import deal.ast.ProgramNode;
import deal.ast.ReturnStatement;
import deal.ast.Span;
import deal.ast.StatementNode;
import deal.ast.TypeNode;
import deal.ast.VariableDeclaration;
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
        testSameNameSiblingCollision();
        testContextualCallSiblingCollision();
        testShadowedLocalCalleeSiblingCollision();
        testDeclaredContextSiblingCollision();
        testIdentifierAssignmentSiblingCollision();
        testMemberAssignmentSiblingCollision();
        testShadowingInitializerAssignmentCollision();
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
    // Sibling providers of a harness-shared identity (GitHub #6)
    // =========================================================================

    /**
     * A harness-synthesized canonical module identity shared by two
     * sibling provider snapshots: valid source never produces one (the
     * canonical identity names the defining file), so this pins the
     * defensive identity-equal provider selection.
     */
    private static final ProjectModuleIdentity SIBLING_IDENTITY =
        new ProjectModuleIdentity("src", "/proj/src", List.of());

    /** The distinct identity of a provider moved to another directory. */
    private static final ProjectModuleIdentity OTHER_DIR_IDENTITY =
        new ProjectModuleIdentity("src", "/proj/src", List.of("n"));

    /**
     * One synthetic provider snapshot of the sibling probes: a program
     * declaring exactly one exported class (with one int field, at a real
     * scalar range) and one exported function whose signature names it,
     * plus (for the same-name collision probes) one non-exported class
     * that is never on the export surface.
     */
    private record SiblingProvider(String alias, String sourcePath,
            String modulePath, CanonicalModuleIdentity identity,
            ProgramNode program, ClassDeclaration declaration,
            ClassDeclaration privateDeclaration) {

        String functionName() {
            return "use" + declaration.name();
        }

        CanonicalClassIdentity classIdentity() {
            return new CanonicalClassIdentity(identity,
                declaration.name());
        }

        CanonicalClassIdentity privateIdentity() {
            return privateDeclaration == null ? null
                : new CanonicalClassIdentity(identity,
                    privateDeclaration.name());
        }

        Type classType() {
            return Types.classType(declaration.name(), classIdentity());
        }

        DefaultPlanImport planImport() {
            Type classType = classType();
            Map<String, Type> exports = new LinkedHashMap<>();
            for (StatementNode stmt : program.statements()) {
                if (stmt instanceof ExportDeclaration ed
                        && ed.declaration() instanceof ClassDeclaration cd) {
                    exports.put(cd.name(), Types.classType(cd.name(),
                        new CanonicalClassIdentity(identity, cd.name())));
                }
            }
            exports.put(functionName(), Types.func(List.of(classType),
                Type.Int.INSTANCE));
            return new DefaultPlanImport(sourcePath, modulePath, program,
                new SourceModuleLocation(sourcePath,
                    new SemanticModuleIdentity(DEPLOYMENT,
                        "file://" + sourcePath),
                    "m" + alias, projectIdentity(), identity),
                exports, new SymbolTable(), false, false);
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
        ClassDeclaration declaration = new ClassDeclaration(classSpan,
            className, siblingFields(classSpan));
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
            program, declaration, null);
    }

    /**
     * The same-name collision provider: an exported class
     * ({@code className}) plus, before it in source order, a
     * non-exported top-level class named {@code privateName} (the
     * demanded class of an identity-equal sibling). The private
     * declaration never appears on the export surface.
     */
    private static SiblingProvider siblingProviderWithPrivateClass(
            String alias, String sourcePath, String modulePath,
            CanonicalModuleIdentity identity, String className,
            String privateName) {
        Span privateSpan = new Span(sourcePath, 1, 1, 1, 30, 0, 29);
        ClassDeclaration privateDeclaration = new ClassDeclaration(
            privateSpan, privateName, siblingFields(privateSpan));
        Span classSpan = new Span(sourcePath, 2, 1, 2, 40, 30, 69);
        ClassDeclaration declaration = new ClassDeclaration(classSpan,
            className, siblingFields(classSpan));
        Span functionSpan = new Span(sourcePath, 3, 1, 3, 30, 70, 99);
        FunctionDeclaration use = new FunctionDeclaration(functionSpan,
            "use" + className,
            List.of(new Parameter(functionSpan, "x",
                new NamedType(functionSpan, className))),
            new NamedType(functionSpan, "int"),
            new Block(functionSpan, List.of()), false, false);
        ProgramNode program = new ProgramNode(functionSpan, List.of(
            privateDeclaration,
            new ExportDeclaration(classSpan, declaration),
            new ExportDeclaration(functionSpan, use)));
        return new SiblingProvider(alias, sourcePath, modulePath, identity,
            program, declaration, privateDeclaration);
    }

    /** One int field {@code v} at the given span. */
    private static List<ClassField> siblingFields(Span span) {
        return List.of(new ClassField(span, "v", false, false,
            new NamedType(span, "int"), java.util.Optional.of(
                new LiteralExpr(span, new LiteralValue.IntLiteral(0)))));
    }

    /**
     * The member-assignment provider (review cycle 5): the demanded
     * exported class plus an exported wrapper class whose class-typed
     * member field names the demanded class, so a member assignment
     * target's declared field type is the only context of the assigned
     * literal.
     */
    private static SiblingProvider siblingProviderWithMemberField(
            String alias, String sourcePath, String modulePath,
            CanonicalModuleIdentity identity, String className) {
        Span classSpan = new Span(sourcePath, 1, 1, 1, 40, 0, 39);
        ClassDeclaration declaration = new ClassDeclaration(classSpan,
            className, siblingFields(classSpan));
        Span wrapperSpan = new Span(sourcePath, 2, 1, 2, 40, 40, 79);
        ClassDeclaration wrapper = new ClassDeclaration(wrapperSpan,
            "Wrap", List.of(new ClassField(wrapperSpan, "inner", false,
                false, new NamedType(wrapperSpan, className),
                java.util.Optional.of(new ObjectLiteralExpr(wrapperSpan,
                    List.of())))));
        Span functionSpan = new Span(sourcePath, 3, 1, 3, 30, 80, 109);
        FunctionDeclaration use = new FunctionDeclaration(functionSpan,
            "use" + className,
            List.of(new Parameter(functionSpan, "x",
                new NamedType(functionSpan, className))),
            new NamedType(functionSpan, "int"),
            new Block(functionSpan, List.of()), false, false);
        ProgramNode program = new ProgramNode(functionSpan, List.of(
            new ExportDeclaration(classSpan, declaration),
            new ExportDeclaration(wrapperSpan, wrapper),
            new ExportDeclaration(functionSpan, use)));
        return new SiblingProvider(alias, sourcePath, modulePath, identity,
            program, declaration, null);
    }

    /**
     * One consumer over the given providers, in the given source
     * import-declaration order, with no checked qualified alias: a
     * class default holding one contextual class literal of the given
     * field type. Returns the recorded occurrences.
     */
    private static List<DefaultResourceOccurrence> siblingRun(
            List<SiblingProvider> providers, String fieldDecl,
            String literalSource, Type fieldType) {
        return siblingRun(providers, Map.of(), fieldDecl, literalSource,
            fieldType);
    }

    /**
     * One consumer over the given providers, in the given source
     * import-declaration order, with the given checked qualified
     * aliases: a class default holding one contextual class literal of
     * the given field type. Returns the recorded occurrences.
     */
    private static List<DefaultResourceOccurrence> siblingRun(
            List<SiblingProvider> providers,
            Map<String, String> qualifiedAliases, String fieldDecl,
            String literalSource, Type fieldType) {
        return siblingRun(providers, qualifiedAliases, fieldDecl,
            literalSource, fieldType, fieldType, null, null);
    }

    /**
     * The contextual-call probe (review cycle 2): a class default whose
     * literal arrives through a call, so the literal's class context is
     * the callee's parameter type and the only exact provenance is the
     * resolved callee's declaring module.
     */
    private static List<DefaultResourceOccurrence> siblingContextualRun(
            List<SiblingProvider> providers, SiblingProvider callee,
            boolean localCallee) {
        String call = localCallee
            ? "useEntry({ v: 7 })"
            : callee.alias() + "." + callee.functionName()
                + "({ v: 7 })";
        return siblingRun(providers, Map.of(), "x: int", call,
            Type.Int.INSTANCE, callee.classType(),
            localCallee ? callableType(callee)
                : callee.planImport().exports()
                    .get(callee.functionName()),
            localCallee ? new LocalFunction("useEntry",
                callee.alias() + ".Ir") : null);
    }

    /** One synthetic same-module top-level function of the probe. */
    private record LocalFunction(String name, String qualifiedParamType) {
    }

    /**
     * The shadowed-local-callee body probe (review cycle 4): the module
     * scope carries the top-level {@code consume} function symbol and a
     * body block scope carries the nested same-named declaration's
     * symbol (exactly the checker's scope shape); the body's call
     * passes a class literal. The resolved symbol's own declaration —
     * never the top-level declaration of that name — owns the literal's
     * parameter context. Returns the recorded occurrences.
     */
    private static List<DefaultResourceOccurrence> siblingShadowedCallRun(
            List<SiblingProvider> providers, SiblingProvider nested,
            SiblingProvider topLevel) {
        StringBuilder source = new StringBuilder();
        for (SiblingProvider provider : providers) {
            source.append("import * as ").append(provider.alias())
                .append(" from \"./").append(provider.alias())
                .append("\";\n");
        }
        source.append("function consume(x: ").append(topLevel.alias())
            .append(".Ir): int { return x.v; }\n")
            .append("function outer(): int {\n")
            .append("  function consume(x: ").append(nested.alias())
            .append(".Ir): int { return x.v; }\n")
            .append("  return consume({ v: 7 });\n")
            .append("}\n");
        LexResult lex = new Lexer(source.toString(), "main.deal")
            .tokenize();
        check(!lex.hasErrors(), "shadowed-callee fixture lexes: "
            + lex.diagnostics());
        ParseResult parse = new Parser(lex.tokens(), "main.deal",
            lex.directiveEvents()).parse();
        check(!parse.hasErrors(), "shadowed-callee fixture parses: "
            + parse.diagnostics());
        ProgramNode program = parse.program();
        FunctionDeclaration outer = null;
        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof FunctionDeclaration fd
                    && fd.name().equals("outer")) {
                outer = fd;
            }
        }
        FunctionDeclaration nestedDeclaration = outer == null ? null
            : directFunctionOf(outer.body(), "consume");
        check(outer != null && nestedDeclaration != null,
            "the shadowed-callee fixture declares the nested consume");
        if (outer == null || nestedDeclaration == null) {
            return List.of();
        }
        Map<ExpressionNode, Type> typeMap = new HashMap<>();
        for (StatementNode stmt : outer.body().statements()) {
            if (stmt instanceof ReturnStatement rs) {
                rs.expr().ifPresent(expr -> {
                    for (ExpressionNode node : collectAll(expr)) {
                        if (node instanceof ObjectLiteralExpr) {
                            typeMap.put(node, nested.classType());
                        }
                        if (node instanceof deal.ast.CallExpr call) {
                            typeMap.put(call.callee(),
                                callableType(nested));
                        }
                    }
                });
            }
        }
        Map<String, DefaultPlanImport> imports = new LinkedHashMap<>();
        Map<String, CanonicalModuleIdentity> classification =
            new LinkedHashMap<>();
        SymbolTable root = new SymbolTable();
        for (SiblingProvider provider : providers) {
            DefaultPlanImport planImport = provider.planImport();
            imports.put(provider.alias(), planImport);
            classification.put(provider.modulePath(), provider.identity());
            root.define(provider.alias(), new deal.checker.Symbol
                .ModuleSymbol(provider.alias(), planImport.exports(),
                    new Span("main.deal", 1, 1, 1, 5)));
        }
        // The checker defines the module-level function in the module
        // scope and each nested declaration in its body block scope.
        root.define("consume", new deal.checker.Symbol.FunctionSymbol(
            "consume", (Type.Func) callableType(topLevel)));
        SymbolTable bodyScope = root.enterScope();
        bodyScope.define("consume", new deal.checker.Symbol.FunctionSymbol(
            "consume", (Type.Func) callableType(nested)));
        Map<StatementNode, SymbolTable> scopeMap =
            Map.of(outer.body(), bodyScope);
        DefaultIrRecorder recorder = new DefaultIrRecorder(
            typeMap, scopeMap, imports, classification,
            CONSUMER_LOCATION, "src.main", program,
            (typeNode, resolutionScope) -> Type.Error.INSTANCE,
            Map.of());
        recorder.recordFunctionBody(outer, root);
        return recorder.occurrences();
    }

    /** The direct function declaration of the given name in a block. */
    private static FunctionDeclaration directFunctionOf(Block block,
            String name) {
        for (StatementNode statement : block.statements()) {
            if (statement instanceof FunctionDeclaration fd
                    && fd.name().equals(name)) {
                return fd;
            }
        }
        return null;
    }

    /**
     * One body-walk probe (review cycle 3): the given top-level function
     * declaration's body carries a class literal whose provider only the
     * declared signature or annotation names. Every object literal of
     * the body is pinned to the demanded class; the body walk records
     * the occurrences. Returns the recorded occurrences.
     */
    private static List<DefaultResourceOccurrence> siblingBodyRun(
            List<SiblingProvider> providers, String declarationSource,
            Type literalType) {
        StringBuilder source = new StringBuilder();
        for (SiblingProvider provider : providers) {
            source.append("import * as ").append(provider.alias())
                .append(" from \"./").append(provider.alias())
                .append("\";\n");
        }
        source.append(declarationSource).append('\n');
        LexResult lex = new Lexer(source.toString(), "main.deal")
            .tokenize();
        check(!lex.hasErrors(), "sibling body fixture lexes: "
            + lex.diagnostics());
        ParseResult parse = new Parser(lex.tokens(), "main.deal",
            lex.directiveEvents()).parse();
        check(!parse.hasErrors(), "sibling body fixture parses: "
            + parse.diagnostics());
        ProgramNode program = parse.program();
        FunctionDeclaration make = null;
        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof FunctionDeclaration fd
                    && fd.body() != null) {
                make = fd;
            }
        }
        check(make != null, "the sibling body fixture declares a function");
        if (make == null) {
            return List.of();
        }
        Map<ExpressionNode, Type> typeMap = new HashMap<>();
        pinBodyLiterals(make.body().statements(), literalType, typeMap);
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
            (typeNode, resolutionScope) -> Type.Error.INSTANCE,
            Map.of());
        recorder.recordFunctionBody(make, scope);
        return recorder.occurrences();
    }

    /** Pins every object literal of the given body statements. */
    private static void pinBodyLiterals(List<StatementNode> statements,
            Type literalType, Map<ExpressionNode, Type> typeMap) {
        for (StatementNode stmt : statements) {
            switch (stmt) {
                case ReturnStatement rs -> rs.expr().ifPresent(expr ->
                    pinLiterals(expr, literalType, typeMap));
                case VariableDeclaration vd -> {
                    if (vd.initializer() != null) {
                        pinLiterals(vd.initializer(), literalType, typeMap);
                    }
                }
                case deal.ast.ExpressionStatement es ->
                    pinLiterals(es.expr(), literalType, typeMap);
                case Block block -> pinBodyLiterals(block.statements(),
                    literalType, typeMap);
                case FunctionDeclaration fd -> {
                    if (fd.body() != null) {
                        pinBodyLiterals(fd.body().statements(),
                            literalType, typeMap);
                    }
                }
                default -> { }
            }
        }
    }

    /** Pins every object literal under one expression. */
    private static void pinLiterals(ExpressionNode root, Type literalType,
            Map<ExpressionNode, Type> typeMap) {
        for (ExpressionNode node : collectAll(root)) {
            if (node instanceof ObjectLiteralExpr) {
                typeMap.put(node, literalType);
            }
        }
    }

    /**
     * One identifier-assignment body probe (review cycle 5): the
     * function's body declares one annotated class-typed local and
     * assigns a class literal to it. The checked binding carries the
     * declaration's own annotation node (exactly what the pass-1
     * resolver records), so the assignment value's provider is the
     * annotation's provider. Returns the recorded occurrences.
     */
    private static List<DefaultResourceOccurrence> siblingAssignmentRun(
            List<SiblingProvider> providers, String declarationSource,
            Type literalType) {
        StringBuilder source = new StringBuilder();
        for (SiblingProvider provider : providers) {
            source.append("import * as ").append(provider.alias())
                .append(" from \"./").append(provider.alias())
                .append("\";\n");
        }
        source.append(declarationSource).append('\n');
        LexResult lex = new Lexer(source.toString(), "main.deal")
            .tokenize();
        check(!lex.hasErrors(), "identifier-assignment fixture lexes: "
            + lex.diagnostics());
        ParseResult parse = new Parser(lex.tokens(), "main.deal",
            lex.directiveEvents()).parse();
        check(!parse.hasErrors(), "identifier-assignment fixture parses: "
            + parse.diagnostics());
        ProgramNode program = parse.program();
        FunctionDeclaration make = null;
        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof FunctionDeclaration fd
                    && fd.body() != null) {
                make = fd;
            }
        }
        check(make != null, "the identifier-assignment fixture declares"
            + " a function");
        if (make == null) {
            return List.of();
        }
        Map<ExpressionNode, Type> typeMap = new HashMap<>();
        pinBodyLiterals(make.body().statements(), literalType, typeMap);
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
        // The checker defines each annotated local in its walked scope
        // with the declaration's own annotation node on the binding.
        defineAnnotatedLocals(make.body().statements(), providers, scope);
        DefaultIrRecorder recorder = new DefaultIrRecorder(
            typeMap, Map.of(), imports, classification,
            CONSUMER_LOCATION, "src.main", program,
            (typeNode, resolutionScope) -> classTypeOf(typeNode, providers),
            Map.of());
        recorder.recordFunctionBody(make, scope);
        return recorder.occurrences();
    }

    /**
     * One shadowing-initializer assignment probe (review 0005): the
     * outer declaration's annotation names one sibling and the inner
     * (shadowing) declaration's annotation the other; the inner
     * declaration's initializer assigns a literal to the name. The
     * pending declaration is invisible to its own initializer, so the
     * assignment targets the enclosing binding and both literals carry
     * the outer annotation's provider. The checked scope shape mirrors
     * the pass-1 resolver: the enclosing binding in the body scope, the
     * pending declaration in the inner block's scope, both recorded in
     * the scope map (the walk's pending-frame trigger). Returns the
     * recorded occurrences.
     */
    private static List<DefaultResourceOccurrence>
            siblingShadowingAssignmentRun(List<SiblingProvider> providers,
                    String outerSpelling, String innerSpelling) {
        StringBuilder source = new StringBuilder();
        for (SiblingProvider provider : providers) {
            source.append("import * as ").append(provider.alias())
                .append(" from \"./").append(provider.alias())
                .append("\";\n");
        }
        source.append("function make(): int {\n")
            .append("  let x: ").append(outerSpelling)
            .append(" = {};\n")
            .append("  { let x: ").append(innerSpelling)
            .append(" = (x = {}); }\n")
            .append("  return x.a;\n")
            .append("}\n");
        LexResult lex = new Lexer(source.toString(), "main.deal").tokenize();
        check(!lex.hasErrors(), "shadowing-initializer fixture lexes: "
            + lex.diagnostics());
        ParseResult parse = new Parser(lex.tokens(), "main.deal",
            lex.directiveEvents()).parse();
        check(!parse.hasErrors(), "shadowing-initializer fixture parses: "
            + parse.diagnostics());
        ProgramNode program = parse.program();
        FunctionDeclaration make = null;
        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof FunctionDeclaration fd
                    && fd.body() != null) {
                make = fd;
            }
        }
        check(make != null, "the shadowing-initializer fixture declares"
            + " a function");
        if (make == null) {
            return List.of();
        }
        VariableDeclaration outer = null;
        Block innerBlock = null;
        VariableDeclaration inner = null;
        for (StatementNode stmt : make.body().statements()) {
            if (stmt instanceof VariableDeclaration vd && outer == null) {
                outer = vd;
            } else if (stmt instanceof Block block) {
                innerBlock = block;
                for (StatementNode innerStmt : block.statements()) {
                    if (innerStmt instanceof VariableDeclaration vd) {
                        inner = vd;
                    }
                }
            }
        }
        check(outer != null && innerBlock != null && inner != null,
            "the shadowing-initializer fixture declares the enclosing"
                + " binding, the inner block, and the shadowing "
                + "declaration");
        if (outer == null || innerBlock == null || inner == null) {
            return List.of();
        }
        TypeNode outerAnnotation = outer.typeAnnotation().orElseThrow();
        TypeNode innerAnnotation = inner.typeAnnotation().orElseThrow();
        Type outerType = classTypeOf(outerAnnotation, providers);
        Map<ExpressionNode, Type> typeMap = new HashMap<>();
        // The checker contextualizes both literals by the enclosing
        // binding's declared class: the assignment target is the
        // enclosing binding, so the pending inner declaration's
        // annotation never types the assigned value.
        pinBodyLiterals(make.body().statements(), outerType, typeMap);
        Map<String, DefaultPlanImport> imports = new LinkedHashMap<>();
        Map<String, CanonicalModuleIdentity> classification =
            new LinkedHashMap<>();
        SymbolTable moduleScope = new SymbolTable();
        for (SiblingProvider provider : providers) {
            DefaultPlanImport planImport = provider.planImport();
            imports.put(provider.alias(), planImport);
            classification.put(provider.modulePath(), provider.identity());
            moduleScope.define(provider.alias(), new deal.checker.Symbol
                .ModuleSymbol(provider.alias(), planImport.exports(),
                    new Span("main.deal", 1, 1, 1, 5)));
        }
        SymbolTable bodyScope = moduleScope.enterScope();
        bodyScope.define(outer.name(), new deal.checker.Symbol
            .VariableSymbol(outer.name(), outerType, false, null,
                outerAnnotation));
        SymbolTable innerScope = bodyScope.enterScope();
        innerScope.define(inner.name(), new deal.checker.Symbol
            .VariableSymbol(inner.name(),
                classTypeOf(innerAnnotation, providers), false, null,
                innerAnnotation));
        Map<StatementNode, SymbolTable> scopeMap = new HashMap<>();
        scopeMap.put(make.body(), bodyScope);
        scopeMap.put(innerBlock, innerScope);
        DefaultIrRecorder recorder = new DefaultIrRecorder(
            typeMap, scopeMap, imports, classification,
            CONSUMER_LOCATION, "src.main", program,
            (typeNode, resolutionScope) -> classTypeOf(typeNode, providers),
            Map.of());
        recorder.recordFunctionBody(make, moduleScope);
        return recorder.occurrences();
    }

    /**
     * Defines every annotated {@code let} of the given statements in the
     * walked scope as the checker's binding: the declared type and its
     * own annotation node travel on the symbol.
     */
    private static void defineAnnotatedLocals(List<StatementNode> statements,
            List<SiblingProvider> providers, SymbolTable scope) {
        for (StatementNode stmt : statements) {
            if (stmt instanceof VariableDeclaration vd
                    && vd.typeAnnotation().isPresent()) {
                TypeNode annotation = vd.typeAnnotation().get();
                scope.define(vd.name(), new deal.checker.Symbol
                    .VariableSymbol(vd.name(),
                        classTypeOf(annotation, providers), false, null,
                        annotation));
            }
        }
    }

    /**
     * One member-assignment body probe (review cycle 5): a function
     * parameter declared with the given qualified wrapper spelling
     * assigns a class literal to the wrapper's class-typed member. The
     * owner expression's checked route (the parameter's own annotation
     * node, exactly what the pass-1 resolver records) names the
     * declaring provider, so that provider's own field declaration —
     * never the first identity-equal export — owns the literal. Returns
     * the recorded occurrences.
     */
    private static List<DefaultResourceOccurrence> siblingMemberAssignmentRun(
            List<SiblingProvider> providers, String ownerSpelling,
            Type ownerType, Type literalType) {
        StringBuilder source = new StringBuilder();
        for (SiblingProvider provider : providers) {
            source.append("import * as ").append(provider.alias())
                .append(" from \"./").append(provider.alias())
                .append("\";\n");
        }
        source.append("function make(w: ").append(ownerSpelling)
            .append("): int { w.inner = { v: 7 }; return w.inner.v; }\n");
        LexResult lex = new Lexer(source.toString(), "main.deal").tokenize();
        check(!lex.hasErrors(), "member-assignment fixture lexes: "
            + lex.diagnostics());
        ParseResult parse = new Parser(lex.tokens(), "main.deal",
            lex.directiveEvents()).parse();
        check(!parse.hasErrors(), "member-assignment fixture parses: "
            + parse.diagnostics());
        ProgramNode program = parse.program();
        FunctionDeclaration make = null;
        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof FunctionDeclaration fd
                    && fd.body() != null) {
                make = fd;
            }
        }
        check(make != null, "the member-assignment fixture declares"
            + " a function");
        if (make == null) {
            return List.of();
        }
        Map<ExpressionNode, Type> typeMap = new HashMap<>();
        pinBodyLiterals(make.body().statements(), literalType, typeMap);
        pinMemberOwners(make.body().statements(), "w", ownerType, typeMap);
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
            (typeNode, resolutionScope) ->
                typeNode instanceof deal.ast.QualifiedType qualified
                    && qualified.typeName().equals("Wrap")
                ? ownerType : classTypeOf(typeNode, providers),
            Map.of());
        recorder.recordFunctionBody(make, scope);
        return recorder.occurrences();
    }

    /** Pins the owner expression of every member-assignment target of
     * the walked body to the wrapper's class type (the checker's
     * member-access object type). */
    private static void pinMemberOwners(List<StatementNode> statements,
            String ownerName, Type ownerType,
            Map<ExpressionNode, Type> typeMap) {
        for (StatementNode stmt : statements) {
            switch (stmt) {
                case ExpressionStatement es -> pinMemberOwners(es.expr(),
                    ownerName, ownerType, typeMap);
                case VariableDeclaration vd -> {
                    if (vd.initializer() != null) {
                        pinMemberOwners(vd.initializer(), ownerName,
                            ownerType, typeMap);
                    }
                }
                case ReturnStatement rs -> rs.expr().ifPresent(expr ->
                    pinMemberOwners(expr, ownerName, ownerType, typeMap));
                case Block block -> pinMemberOwners(block.statements(),
                    ownerName, ownerType, typeMap);
                default -> { }
            }
        }
    }

    private static void pinMemberOwners(ExpressionNode root,
            String ownerName, Type ownerType,
            Map<ExpressionNode, Type> typeMap) {
        for (ExpressionNode node : collectAll(root)) {
            if (node instanceof deal.ast.AssignmentExpr assign
                    && assign.target()
                        instanceof deal.ast.MemberAccessExpr mae
                    && mae.object() instanceof deal.ast.IdentifierExpr id
                    && id.name().equals(ownerName)) {
                typeMap.put(id, ownerType);
            }
        }
    }

    /** The provider class type one declared annotation names (a mini
     * resolver over the probe's siblings, mirroring the checker). */
    private static Type classTypeOf(TypeNode annotation,
            List<SiblingProvider> providers) {
        for (SiblingProvider provider : providers) {
            if (annotation instanceof deal.ast.QualifiedType q
                    && provider.alias().equals(q.moduleName())
                    && provider.declaration().name().equals(q.typeName())) {
                return provider.classType();
            }
            if (annotation instanceof NamedType named
                    && provider.declaration().name().equals(named.name())) {
                return provider.classType();
            }
        }
        return Type.Error.INSTANCE;
    }

    private static Type callableType(SiblingProvider provider) {
        return Types.func(List.of(provider.classType()),
            Type.Int.INSTANCE);
    }

    /**
     * One consumer over the given providers, in the given source
     * import-declaration order: a class default holding one contextual
     * class literal of the given field type. {@code literalType} pins
     * every object literal's checked type, {@code calleeType} pins every
     * call's callee expression (the contextual route), and
     * {@code localFunction} optionally prepends a same-module top-level
     * function declaration whose parameter is spelled with the given
     * qualified type. Returns the recorded occurrences.
     */
    private static List<DefaultResourceOccurrence> siblingRun(
            List<SiblingProvider> providers,
            Map<String, String> qualifiedAliases, String fieldDecl,
            String literalSource, Type fieldType, Type literalType,
            Type calleeType, LocalFunction localFunction) {
        StringBuilder source = new StringBuilder();
        for (SiblingProvider provider : providers) {
            source.append("import * as ").append(provider.alias())
                .append(" from \"./").append(provider.alias())
                .append("\";\n");
        }
        if (localFunction != null) {
            source.append("function ").append(localFunction.name())
                .append("(x: ").append(localFunction.qualifiedParamType())
                .append("): int { return x.v; }\n");
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
        if (defaultExpr != null && literalType != null) {
            for (ExpressionNode node : collectAll(defaultExpr)) {
                if (node instanceof ObjectLiteralExpr) {
                    typeMap.put(node, literalType);
                }
            }
        }
        if (defaultExpr != null && calleeType != null) {
            for (ExpressionNode node : collectAll(defaultExpr)) {
                if (node instanceof deal.ast.CallExpr call) {
                    typeMap.put(call.callee(), calleeType);
                }
            }
        }
        Map<String, DefaultPlanImport> imports = new LinkedHashMap<>();
        Map<String, CanonicalModuleIdentity> classification =
            new LinkedHashMap<>();
        SymbolTable scope = new SymbolTable();
        if (localFunction != null && calleeType instanceof Type.Func fn) {
            scope.define(localFunction.name(),
                new deal.checker.Symbol.FunctionSymbol(
                    localFunction.name(), fn));
        }
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
            qualifiedAliases);
        recorder.record(defaultExpr, scope, fieldType);
        return recorder.occurrences();
    }

    /**
     * The class-default-plan occurrence of a call-bearing probe: the
     * imported-function wrapper occurrence of the callee is not the
     * demand under assertion.
     */
    private static void checkSiblingClassOccurrence(
            List<DefaultResourceOccurrence> occurrences,
            SiblingProvider provider, String context) {
        List<DefaultResourceOccurrence> plans = new java.util.ArrayList<>();
        for (DefaultResourceOccurrence occurrence : occurrences) {
            if (occurrence.kind()
                    == RuntimeResourceReference.Kind
                        .IMPORTED_CLASS_DEFAULT_PLAN) {
                plans.add(occurrence);
            }
        }
        checkSiblingOccurrence(plans, provider, context);
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

        // Both import orders: the provider is selected by the checked
        // export surface that carries the demanded class, never by the
        // identity-equal sibling.
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

    /**
     * The same-name sibling collision (review cycle 1): an identity-equal
     * sibling whose program carries a non-exported class of the same
     * name is never the provider. The checked qualified spelling of the
     * declaring annotation wins when present, and the checked export
     * surface resolves the provider otherwise — in both import orders,
     * with the exact defining declaration range.
     */
    private static void testSameNameSiblingCollision() {
        System.out.println("-- same-name sibling collision: a non-exported"
            + " same-name class is never the provider --");
        CanonicalModuleIdentity sibling =
            new CanonicalModuleIdentity.ProjectModule(SIBLING_IDENTITY);
        SiblingProvider ir = siblingProvider("ir", "/proj/src/m/ir.deal",
            "src.m.ir", sibling, "Ir");
        SiblingProvider codec = siblingProviderWithPrivateClass("codec",
            "/proj/src/m/codec.deal", "src.m.codec", sibling, "Codec",
            "Ir");
        check(codec.privateIdentity().equals(ir.classIdentity()),
            "the fixture's private same-name class is identity-equal to"
                + " the demanded class (the harness-shared identity model)");

        for (List<SiblingProvider> order : List.of(
                List.of(ir, codec), List.of(codec, ir))) {
            // The field annotation's resolved alias names the provider.
            checkSiblingOccurrence(siblingRun(order, Map.of("Ir", "ir"),
                "r: ir.Ir", "{ v: 7 }", ir.classType()), ir,
                "the aliased collision probe");
            // Without an annotation alias (a body literal): the checked
            // export surface resolves, never the name-bearing sibling
            // program.
            checkSiblingOccurrence(siblingRun(order, "r: Ir", "{ v: 7 }",
                ir.classType()), ir, "the export-surface collision probe");
        }

        // Two identity-equal siblings that both export the demanded
        // class name: the checked spelling decides, so neither import
        // order changes the exact provider.
        SiblingProvider irExported = siblingProvider("ir",
            "/proj/src/m/ir.deal", "src.m.ir", sibling, "Ir");
        SiblingProvider codecExported = siblingProvider("codec",
            "/proj/src/m/codec.deal", "src.m.codec", sibling, "Ir");
        for (List<SiblingProvider> order : List.of(
                List.of(irExported, codecExported),
                List.of(codecExported, irExported))) {
            checkSiblingOccurrence(siblingRun(order, Map.of("Ir", "ir"),
                "r: ir.Ir", "{ v: 7 }", irExported.classType()),
                irExported, "the exported-collision ir spelling");
            checkSiblingOccurrence(siblingRun(order,
                Map.of("Ir", "codec"), "r: codec.Ir", "{ v: 7 }",
                codecExported.classType()), codecExported,
                "the exported-collision codec spelling");
        }
    }

    /**
     * The contextual-call collision (review cycle 2): the class context
     * arrives through a call's parameter, so the declaring field
     * annotation names no class and both identity-equal siblings export
     * the demanded name. The resolved callee's declaring module is the
     * only exact provider — in both import orders, for an imported
     * callee and for a same-module callee whose parameter spelling
     * names an import.
     */
    private static void testContextualCallSiblingCollision() {
        System.out.println("-- the resolved callee's parameter context names"
            + " the class-literal provider (both import orders) --");
        CanonicalModuleIdentity sibling =
            new CanonicalModuleIdentity.ProjectModule(SIBLING_IDENTITY);
        SiblingProvider ir = siblingProvider("ir", "/proj/src/m/ir.deal",
            "src.m.ir", sibling, "Ir");
        SiblingProvider codec = siblingProvider("codec",
            "/proj/src/m/codec.deal", "src.m.codec", sibling, "Ir");
        check(codec.classIdentity().equals(ir.classIdentity()),
            "the fixture's exported same-name siblings are identity-equal"
                + " (the harness-shared identity model)");

        for (List<SiblingProvider> order : List.of(
                List.of(ir, codec), List.of(codec, ir))) {
            checkSiblingClassOccurrence(
                siblingContextualRun(order, ir, false), ir,
                "the imported-callee collision probe ir.useIr");
            checkSiblingClassOccurrence(
                siblingContextualRun(order, codec, false), codec,
                "the imported-callee collision probe codec.useIr");
            checkSiblingClassOccurrence(
                siblingContextualRun(order, ir, true), ir,
                "the local-callee collision probe f(x: ir.Ir)");
        }
    }

    /**
     * The shadowed-local-callee collision (review cycle 4): a nested
     * function declaration shadows a same-named module-level function,
     * so the resolved callee's own declaration — never the top-level
     * declaration carrying that name — owns the literal's parameter
     * context. The nested declaration's provider must win in both
     * import orders, in both directions of the shadowing.
     */
    private static void testShadowedLocalCalleeSiblingCollision() {
        System.out.println("-- a nested callee declaration shadows the"
            + " module function: its parameter context owns the literal"
            + " (both import orders) --");
        CanonicalModuleIdentity sibling =
            new CanonicalModuleIdentity.ProjectModule(SIBLING_IDENTITY);
        SiblingProvider ir = siblingProvider("ir", "/proj/src/m/ir.deal",
            "src.m.ir", sibling, "Ir");
        SiblingProvider codec = siblingProvider("codec",
            "/proj/src/m/codec.deal", "src.m.codec", sibling, "Ir");
        check(codec.classIdentity().equals(ir.classIdentity()),
            "the fixture's exported same-name siblings are identity-equal"
                + " (the harness-shared identity model)");

        for (List<SiblingProvider> order : List.of(
                List.of(ir, codec), List.of(codec, ir))) {
            checkSiblingClassOccurrence(
                siblingShadowedCallRun(order, ir, codec), ir,
                "the shadowed-callee collision probe (nested ir.Ir"
                    + " shadows top-level codec.Ir)");
            checkSiblingClassOccurrence(
                siblingShadowedCallRun(order, codec, ir), codec,
                "the shadowed-callee collision probe (nested codec.Ir"
                    + " shadows top-level ir.Ir)");
        }
    }

    /**
     * The declared-context collisions (review cycle 3): the class
     * context arrives through a declared signature or annotation other
     * than a parameter, so the same ambiguity applies. A local
     * function's declared return type, an array element's annotation,
     * and a member-assignment target's declared type must each own the
     * literal through their exact provider, in both import orders — a
     * sibling's own serialization is never recorded.
     */
    private static void testDeclaredContextSiblingCollision() {
        System.out.println("-- declared return types and array annotations name"
            + " the class-literal provider (both import orders) --");
        CanonicalModuleIdentity sibling =
            new CanonicalModuleIdentity.ProjectModule(SIBLING_IDENTITY);
        SiblingProvider ir = siblingProvider("ir", "/proj/src/m/ir.deal",
            "src.m.ir", sibling, "Ir");
        SiblingProvider codec = siblingProvider("codec",
            "/proj/src/m/codec.deal", "src.m.codec", sibling, "Ir");

        for (List<SiblingProvider> order : List.of(
                List.of(ir, codec), List.of(codec, ir))) {
            checkSiblingClassOccurrence(
                siblingBodyRun(order, "function make(): ir.Ir"
                    + " { return { v: 7 }; }", ir.classType()), ir,
                "the return-type collision probe make(): ir.Ir");
            checkSiblingClassOccurrence(
                siblingBodyRun(order, "function make(): codec.Ir"
                    + " { return { v: 7 }; }", codec.classType()), codec,
                "the return-type collision probe make(): codec.Ir");
            checkSiblingClassOccurrence(
                siblingBodyRun(order, "function make(): int { let xs:"
                    + " ir.Ir[] = [{ v: 7 }]; return xs[0].v; }",
                    ir.classType()), ir,
                "the array-annotation collision probe ir.Ir[]");
            checkSiblingClassOccurrence(
                siblingBodyRun(order, "function outer(): ir.Ir { function"
                    + " inner(): codec.Ir { return { v: 7 }; } return"
                    + " inner(); }", codec.classType()), codec,
                "the nested-declaration collision probe inner():"
                    + " codec.Ir");
        }
    }

    /**
     * The identifier-assignment collision (review cycle 5): the assigned
     * binding's own declared annotation is the assignment value's
     * checked context, so the target binding's exact provider owns the
     * construction in both import orders — never the first
     * identity-equal export the import order happens to select.
     */
    private static void testIdentifierAssignmentSiblingCollision() {
        System.out.println("-- identifier assignment: the target binding's"
            + " declared annotation owns the literal (both import orders)"
            + " --");
        CanonicalModuleIdentity sibling =
            new CanonicalModuleIdentity.ProjectModule(SIBLING_IDENTITY);
        SiblingProvider ir = siblingProvider("ir", "/proj/src/m/ir.deal",
            "src.m.ir", sibling, "Ir");
        SiblingProvider codec = siblingProvider("codec",
            "/proj/src/m/codec.deal", "src.m.codec", sibling, "Ir");
        check(codec.classIdentity().equals(ir.classIdentity()),
            "the fixture's exported same-name siblings are identity-equal"
                + " (the harness-shared identity model)");

        for (List<SiblingProvider> order : List.of(
                List.of(ir, codec), List.of(codec, ir))) {
            checkSiblingClassOccurrence(
                siblingAssignmentRun(order,
                    "function make(): int { let x: ir.Ir = { v: 7 };"
                        + " x = { v: 7 }; return x.v; }",
                    ir.classType()), ir,
                "the identifier-assignment probe (ir spelling)");
            checkSiblingClassOccurrence(
                siblingAssignmentRun(order,
                    "function make(): int { let x: codec.Ir = { v: 7 };"
                        + " x = { v: 7 }; return x.v; }",
                    codec.classType()), codec,
                "the identifier-assignment probe (codec spelling)");
            checkSiblingClassOccurrence(
                siblingAssignmentRun(order,
                    "function make(): int { let xs: ir.Ir[] = [{ v: 7 }];"
                        + " xs[0] = { v: 7 }; return xs[0].v; }",
                    ir.classType()), ir,
                "the indexed-assignment probe (ir element spelling)");
            checkSiblingClassOccurrence(
                siblingAssignmentRun(order,
                    "function make(): int { let xs: codec.Ir[] ="
                        + " [{ v: 7 }]; xs[0] = { v: 7 }; return xs[0].v; }",
                    codec.classType()), codec,
                "the indexed-assignment probe (codec element spelling)");
        }
    }

    /**
     * The member-assignment collision (review cycle 5): the assignment
     * target is a member of a binding whose declared annotation names
     * one identity-equal sibling, so the member's field declaration must
     * be read from that provider's own program — never from the first
     * identity-equal export of the import order. Asserted in both import
     * orders for both sibling spellings, with the exact defining
     * declaration range.
     */
    private static void testMemberAssignmentSiblingCollision() {
        System.out.println("-- member assignment: the owner binding's own"
            + " route owns the member's declared field provider (both"
            + " import orders) --");
        CanonicalModuleIdentity sibling =
            new CanonicalModuleIdentity.ProjectModule(SIBLING_IDENTITY);
        SiblingProvider ir = siblingProviderWithMemberField("ir",
            "/proj/src/m/ir.deal", "src.m.ir", sibling, "Ir");
        SiblingProvider codec = siblingProviderWithMemberField("codec",
            "/proj/src/m/codec.deal", "src.m.codec", sibling, "Ir");
        check(codec.classIdentity().equals(ir.classIdentity()),
            "the fixture's exported same-name siblings are identity-equal"
                + " (the harness-shared identity model)");
        Type wrapper = Types.classType("Wrap",
            new CanonicalClassIdentity(sibling, "Wrap"));

        for (List<SiblingProvider> order : List.of(
                List.of(ir, codec), List.of(codec, ir))) {
            checkSiblingClassOccurrence(
                siblingMemberAssignmentRun(order, "ir.Wrap", wrapper,
                    ir.classType()), ir,
                "the member-assignment probe (ir.Wrap owner)");
            checkSiblingClassOccurrence(
                siblingMemberAssignmentRun(order, "codec.Wrap", wrapper,
                    codec.classType()), codec,
                "the member-assignment probe (codec.Wrap owner)");
        }
    }

    /**
     * The shadowing-initializer assignment collision (review 0005): the
     * assignment inside a shadowing let's own initializer targets the
     * enclosing binding — the pending declaration is not visible there
     * (docs/spec-v1.3.md &sect;Name resolution — Declaration order), exactly like the
     * checker's {@code resolveVisibleBinding} — so the literal keeps
     * the enclosing binding's declared provider, never the
     * not-yet-visible inner declaration's annotation and never the
     * first identity-equal export. Asserted in both import orders and
     * both sibling spellings.
     */
    private static void testShadowingInitializerAssignmentCollision() {
        System.out.println("-- shadowing-initializer assignment: the"
            + " pending let is invisible to its own initializer, so the"
            + " enclosing binding owns the literal (both import orders)"
            + " --");
        CanonicalModuleIdentity sibling =
            new CanonicalModuleIdentity.ProjectModule(SIBLING_IDENTITY);
        SiblingProvider ir = siblingProvider("ir", "/proj/src/m/ir.deal",
            "src.m.ir", sibling, "Ir");
        SiblingProvider codec = siblingProvider("codec",
            "/proj/src/m/codec.deal", "src.m.codec", sibling, "Ir");
        check(codec.classIdentity().equals(ir.classIdentity()),
            "the fixture's exported same-name siblings are identity-equal"
                + " (the harness-shared identity model)");

        for (List<SiblingProvider> order : List.of(
                List.of(ir, codec), List.of(codec, ir))) {
            checkSiblingClassOccurrence(
                siblingShadowingAssignmentRun(order, "ir.Ir", "codec.Ir"),
                ir, "the shadowing-initializer probe (ir outer binding)");
            checkSiblingClassOccurrence(
                siblingShadowingAssignmentRun(order, "codec.Ir", "ir.Ir"),
                codec, "the shadowing-initializer probe (codec outer"
                    + " binding)");
        }
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
