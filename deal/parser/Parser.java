package deal.parser;

import deal.ast.*;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.DiagnosticNote;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.lexer.TemplateScanner;
import deal.lexer.Token;
import deal.source.ScalarSourceCursor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Hand-written recursive descent parser with precedence climbing for expressions.
 *
 * <p>Covers every grammar production in spec.md lines 100–260.  Produces an
 * immutable raw AST ({@link ProgramNode}) from a {@code List<Token>}.
 * Emits {@code E1xxx} diagnostics for syntax errors and recovers at statement
 * boundaries so that as many errors as possible are collected in a single pass.</p>
 */
public final class Parser {

    // -----------------------------------------------------------------------
    // Precedence constants (higher = tighter binding)
    // -----------------------------------------------------------------------

    private static final int PREC_NONE          = 0;
    private static final int PREC_OR            = 1;
    private static final int PREC_AND           = 2;
    private static final int PREC_EQUALITY      = 3;
    private static final int PREC_RELATIONAL    = 4;
    private static final int PREC_ADDITIVE      = 5;
    private static final int PREC_MULTIPLICATIVE = 6;

    // -----------------------------------------------------------------------
    // Token stream
    // -----------------------------------------------------------------------

    private final List<Token> tokens;
    private final String file;
    private final List<CompilerDiagnostic> diagnostics = new ArrayList<>();
    /** The input list's EOF token, or null when the list has none. */
    private final Token inputEofToken;
    private int pos;          // current index into tokens (0-based)

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /** Parses tokens with exact source positions; comments carry no meaning. */
    public Parser(List<Token> tokens, String file) {
        this.tokens = new ArrayList<>(tokens);
        this.file = file;
        this.pos = 0;
        this.inputEofToken = findEofToken(this.tokens);
    }

    /**
     * Returns the last EOF token in the list, or null when the list has
     * none (defensive/test-only inputs such as {@code List.of()}).
     */
    private static Token findEofToken(List<Token> tokens) {
        for (int i = tokens.size() - 1; i >= 0; i--) {
            if (tokens.get(i).type() == TokenType.EOF) {
                return tokens.get(i);
            }
        }
        return null;
    }

    // =======================================================================
    // Public API
    // =======================================================================

    public ParseResult parse() {
        List<StatementNode> statements = new ArrayList<>();

        while (!isAtEnd()) {
            int diagCountBefore = diagnostics.size();
            StatementNode stmt = parseStatement();
            if (stmt != null) {
                checkStatementTerminator(stmt, diagCountBefore);
                statements.add(stmt);
            }
        }

        Span progSpan;
        if (statements.isEmpty()) {
            // Empty program: an exact zero-length range at file start with
            // explicit known offsets (0,0) — never UNKNOWN.
            progSpan = new Span(file, 1, 1, 1, 1, 0, 0);
        } else {
            StatementNode first = statements.get(0);
            StatementNode last = statements.get(statements.size() - 1);
            // spanBetween propagates the first statement's start offset and
            // the last statement's end offset.
            progSpan = spanBetween(first.span(), last.span());
        }

        ProgramNode program = new ProgramNode(progSpan, List.copyOf(statements));
        return new ParseResult(program, List.copyOf(diagnostics));
    }

    // =======================================================================
    // Statement parsing
    // =======================================================================

    private StatementNode parseStatement() {
        if (peek().type() == TokenType.IDENTIFIER && peek().lexeme().equals("var") && pos + 1 < tokens.size()
                && (isIdentifier(tokens.get(pos + 1).type()) || tokens.get(pos + 1).type() == TokenType.LBRACKET
                    || tokens.get(pos + 1).type() == TokenType.LBRACE)) {
            Token keyword = advance();
            rejectedSyntax(keyword, "var-binding", "Var declarations are not supported; choose let or const",
                    "Use let for a reassignable binding or const for a fixed binding; both are block-scoped, unlike JavaScript var.");
            recoverUnsupportedStatement();
            return null;
        }
        if (peek().type() == TokenType.IDENTIFIER) {
            String word = peek().lexeme();
            if (Set.of("interface", "type", "enum", "namespace").contains(word) && pos + 1 < tokens.size()
                    && isIdentifier(tokens.get(pos + 1).type())) {
                Token keyword = advance();
                rejectedSyntax(keyword, word + "-declaration", "Declaration '" + word + "' is not supported; use the specified type forms",
                        "DEAL supports nominal classes with fields, function declarations and the closed builtin types; do not substitute a structural or generic contract implicitly.");
                skipRejectedControlFlow(word);
                return null;
            }
            if (Set.of("switch", "do", "yield").contains(word)
                    || pos + 1 < tokens.size() && tokens.get(pos + 1).type() == TokenType.COLON) {
                Token keyword = advance();
                String feature = Set.of("switch", "do", "yield").contains(word) ? word : "label";
                rejectedSyntax(keyword, feature, switch (feature) {
                    case "switch" -> "Switch statements are not supported; use explicit if/else branches";
                    case "do" -> "Do-while is not supported; use an explicit while loop";
                    case "yield" -> "Yield is not supported; use ordinary function returns";
                    default -> "Labels are not supported; use unlabelled loop control";
                }, "Choose explicit supported control flow; preserve once-only tests, effects and the intended break/continue target.");
                skipRejectedControlFlow(feature);
                return null;
            }
        }
        if (peek().type() == TokenType.FUNCTION && pos + 1 < tokens.size() && tokens.get(pos + 1).type() == TokenType.STAR) {
            Token keyword = advance();
            rejectedSyntax(keyword, "generator", "Generators are not supported; use an ordinary function",
                    "Return an explicit data value or collection; DEAL has no generator or iterator protocol.");
            skipRejectedControlFlow("generator");
            return null;
        }
        TokenType type = peek().type();
        StatementNode stmt = switch (type) {
            case LBRACE    -> parseBlock();
            case CLASS     -> parseClassDeclaration();
            case FUNCTION  -> {
                if (pos + 1 < tokens.size()
                        && isIdentifier(tokens.get(pos + 1).type())
                        && pos + 2 < tokens.size()
                        && (tokens.get(pos + 2).type() == TokenType.LPAREN || tokens.get(pos + 2).type() == TokenType.LT)) {
                    yield parseFunctionDeclaration();
                }
                functionExpressionStatementError();
                yield parseExpressionStatement();
            }
            case ASYNC  -> {
                if (pos + 1 < tokens.size()
                        && tokens.get(pos + 1).type() == TokenType.FUNCTION
                        && pos + 2 < tokens.size()
                        && isIdentifier(tokens.get(pos + 2).type())
                        && pos + 3 < tokens.size()
                        && (tokens.get(pos + 3).type() == TokenType.LPAREN || tokens.get(pos + 3).type() == TokenType.LT)) {
                    yield parseFunctionDeclaration();
                }
                if (pos + 1 < tokens.size() && tokens.get(pos + 1).type() == TokenType.FUNCTION) {
                    functionExpressionStatementError();
                }
                yield parseExpressionStatement();
            }
            case LET, CONST -> parseVariableDeclaration();
            case RETURN    -> parseReturnStatement();
            case IF        -> parseIfStatement();
            case WHILE     -> parseWhileStatement();
            case FOR       -> parseForOfStatement();
            case BREAK     -> parseBreakStatement();
            case CONTINUE  -> parseContinueStatement();
            case IMPORT    -> parseImportDeclaration();
            case EXPORT    -> parseExportDeclaration();
            case DELETE    -> parseDeleteStatement();
            case TRY       -> parseTryStatement();
            case THROW     -> parseThrowStatement();
            // The v1.4 Syntactic grammar has no empty-statement
            // production: Program ::= ImportDeclaration*
            // TopLevelDeclaration*, Statement* and Block ::= '{'
            // Statement* '}'. A ';' in statement position is therefore
            // outside every production — including the old
            // optional-semicolon form after a block-ending declaration —
            // and reports E1059 at its own scalar range. The statement's
            // real terminator stays owned by the production that carries
            // it (checked by checkStatementTerminator), so this arm only
            // handles a ';' that no production consumed.
            case SEMICOLON -> {
                error(DiagnosticCode.E1059, "Unexpected ';'", peek());
                advance();
                yield null;
            }
            case EOF       -> null;
            case RBRACE    -> { advance(); error(DiagnosticCode.E1041, "Unexpected '}'", previous()); yield null; }
            default        -> parseExpressionStatement();
        };
        if (stmt != null) {
        }
        return stmt;
    }

    // -- Block --
    private Block parseBlock() {
        Token lbrace = expect(TokenType.LBRACE, DiagnosticCode.E1005, "Expected '{'");
        if (lbrace == null) { synchronize(); return emptyBlock(); }

        List<StatementNode> statements = new ArrayList<>();
        while (!isAtEnd() && peek().type() != TokenType.RBRACE) {
            int diagCountBefore = diagnostics.size();
            Token statementStart = peek();
            StatementNode stmt = parseStatement();
            if (stmt != null) {
                if (stmt instanceof FunctionDeclaration || stmt instanceof ExportDeclaration) {
                    subsetError(DiagnosticCode.E1042, "Function declarations and exports are module-level only", statementStart,
                            "declaration-placement", "module-level function declaration or export",
                            "Choose an intended module-level declaration or a local function-valued binding; moving code may change captures and is not an automatic repair.");
                }
                checkStatementTerminator(stmt, diagCountBefore);
                statements.add(stmt);
            }
        }

        Token rbrace;
        if (match(TokenType.RBRACE)) {
            rbrace = previous();
        } else {
            expectedCloser(DiagnosticCode.E1006, "Expected '}'", TokenType.RBRACE, lbrace);
            rbrace = previousOrCurrent();
        }
        return new Block(spanBetween(lbrace, rbrace), List.copyOf(statements));
    }

    // -- ClassDeclaration --
    private StatementNode parseClassDeclaration() {
        Token classToken = advance();
        Token nameToken = expect(TokenType.IDENTIFIER, DiagnosticCode.E1007, "Expected class name after 'class'");
        if (nameToken == null) { synchronize(); return null; }

        rejectGenericDeclaration("class");
        Token lbrace = expect(TokenType.LBRACE, DiagnosticCode.E1005, "Expected '{' after class name");
        if (lbrace == null) { synchronize(); return null; }

        List<ClassField> fields = new ArrayList<>();
        while (!isAtEnd() && peek().type() != TokenType.RBRACE) {
            ClassField field = parseClassField();
            if (field != null) {
                fields.add(field);
            } else {
                synchronizeTo(TokenType.RBRACE, TokenType.IDENTIFIER);
                if (peek().type() == TokenType.RBRACE) break;
                if (peek().type() != TokenType.IDENTIFIER) break;
            }
        }

        Token rbrace;
        if (match(TokenType.RBRACE)) {
            rbrace = previous();
        } else {
            expectedCloser(DiagnosticCode.E1006, "Expected '}' at end of class body", TokenType.RBRACE, lbrace);
            rbrace = previousOrCurrent();
        }
        return new ClassDeclaration(spanBetween(classToken, rbrace),
                nameToken.lexeme(), List.copyOf(fields));
    }

    private ClassField parseClassField() {
        boolean readonly = peek().lexeme().equals("readonly") && isPropertyName(peek(1));
        if (readonly) advance();
        if (!isPropertyName(peek())) {
            error(DiagnosticCode.E1007, "Expected field name (identifier)", peek());
            advance();
            return null;
        }
        Token nameToken = advance();
        if (nameToken.lexeme().equals("constructor")) {
            subsetError(DiagnosticCode.E1042, "Class fields cannot be named 'constructor'", nameToken,
                    "constructor-field", "class field name other than constructor",
                    "DEAL classes declare nominal record fields, not constructors or methods; choose the intended data field name.");
        }
        boolean optional = match(TokenType.QUESTION);

        if (!match(TokenType.COLON)) {
            expectedToken(DiagnosticCode.E1008, "Expected ':' after field name", TokenType.COLON);
            return null;
        }
        TypeNode type = parseType();
        if (type == null) return null;

        boolean nullable = type instanceof NullableType;

        int diagCountBefore = diagnostics.size();
        if (match(TokenType.EQ_SIGN)) {
            subsetError(DiagnosticCode.E1007, "Class field initializers are not supported", previous(),
                    "field-initializer", "class field type and presence declaration without an initializer",
                    "Supply intended field values at construction sites; deleting or relocating an initializer may change behavior and is not inferred.");
            // Recover at the field terminator without admitting an initializer AST.
            parseExpression();
        }
        optionalSemicolon();
        // Class fields require their ';'.
        checkTerminator(diagCountBefore);

        Span sp = spanBetween(nameToken, previousOrCurrent());
        return new ClassField(sp, nameToken.lexeme(), optional, nullable, type, readonly);
    }

    // -- FunctionDeclaration --
    private StatementNode parseFunctionDeclaration() {
        boolean isAsync = match(TokenType.ASYNC);
        Token startToken = isAsync ? previous() : peek();
        if (isAsync && peek().line() != startToken.line()) {
            lineTerminatorError("No line terminator between async and function", "async-function", startToken);
        }
        Token funcToken = advance();  // consumes FUNCTION
        Token nameToken = expect(TokenType.IDENTIFIER, DiagnosticCode.E1007, "Expected function name after 'function'");
        if (nameToken == null) { synchronize(); return null; }

        rejectGenericDeclaration("function");
        FunctionSuffix suffix = parseFunctionSuffix("Expected '(' after function name",
            "Expected ':' return type annotation", true);
        if (suffix == null) { return null; }

        Span sp = spanBetween(startToken, previousOrCurrent());
        if (isAsync != suffix.isAsync() && !suffix.isExternal()) {
            resultNotationError(startToken, isAsync);
        }
        if (isAsync && suffix.isExternal()) {
            subsetError(DiagnosticCode.E1011, "External declarations must omit async", startToken,
                    "external-async", "external declaration without async implementation marker",
                    "External declarations express async results using Promise<T> without the async implementation marker.");
        }
        return new FunctionDeclaration(sp, nameToken.lexeme(),
                suffix.params(), suffix.returnType(), suffix.body(), suffix.isAsync(),
                suffix.isExternal());
    }

    /**
     * The function suffix shared by declarations and expressions:
     * {@code '(' parameters ')' ':' returnType} and the body block.
     * {@code allowExternal} admits the {@code ';'} external form (the
     * declaration's shape) and {@code returnTypeMessage} carries the
     * caller's pinned E1011 text.
     */
    private FunctionSuffix parseFunctionSuffix(String lparenMessage,
                                               String returnTypeMessage,
                                               boolean allowExternal) {
        Token lparen = expect(TokenType.LPAREN, DiagnosticCode.E1009, lparenMessage);
        if (lparen == null) { synchronize(); return null; }
        ParamListResult paramResult = parseParameterList();
        if (allowExternal) for (Parameter parameter : paramResult.params()) {
            if (parameter.type() == null) error(DiagnosticCode.E1008,
                "Function declarations require parameter annotations", peek());
        }
        expectCloser(TokenType.RPAREN, DiagnosticCode.E1010, "Expected ')' after function parameters", lparen);

        if (!match(TokenType.COLON)) {
            error(DiagnosticCode.E1011, returnTypeMessage, peek());
            synchronize(); return null;
        }
        ResultAnnotation result = parseResultAnnotation();
        TypeNode returnType = result.type();
        if (returnType == null) { synchronize(); return null; }

        Block body;
        boolean isExternal = false;
        if (peek().type() == TokenType.LBRACE) {
            body = parseBlock();
        } else if (allowExternal && match(TokenType.SEMICOLON)) {
            // External function declaration (declaration files only).
            body = new Block(spanOf(previous()), List.of());
            isExternal = true;
        } else {
            error(DiagnosticCode.E1012, "Expected '{' for function body", peek());
            body = emptyBlock();
            synchronize();
        }
        return new FunctionSuffix(paramResult.params(), returnType, body, isExternal, result.isAsync());
    }

    /** The parsed function suffix shared by declarations and expressions. */
    private record FunctionSuffix(List<Parameter> params, TypeNode returnType,
                                  Block body, boolean isExternal, boolean isAsync) {
    }

    private record ResultAnnotation(TypeNode type, boolean isAsync) {}

    private ResultAnnotation parseResultAnnotation() {
        if (peek().type() == TokenType.IDENTIFIER && peek().lexeme().equals("Promise")) {
            advance();
            expect(TokenType.LT, DiagnosticCode.E1032, "Expected '<' after Promise");
            TypeNode result = parseType();
            expect(TokenType.GT, DiagnosticCode.E1032, "Expected '>' after Promise result");
            return new ResultAnnotation(result, true);
        }
        return new ResultAnnotation(parseType(), false);
    }

    // -- VariableDeclaration --
    private StatementNode parseVariableDeclaration() {
        Token letToken = advance();
        if (rejectDestructuring("declaration")) { recoverUnsupportedStatement(); return null; }
        Token nameToken = expect(TokenType.IDENTIFIER, DiagnosticCode.E1007, "Expected variable name after '" + letToken.lexeme() + "'");
        if (nameToken == null) { synchronize(); return null; }

        Optional<TypeNode> typeAnnotation = Optional.empty();
        if (match(TokenType.COLON)) {
            TypeNode type = parseType();
            if (type != null) typeAnnotation = Optional.of(type);
        }

        if (!match(TokenType.EQ_SIGN)) {
            subsetError(DiagnosticCode.E1013, "Every binding requires an initializer; choose an explicit value", peek(),
                    "binding-initializer", "= initializer",
                    "Initialize the binding with an intentional value of its type; annotations do not create values and no default is inferred.");
            synchronize(); return null;
        }

        ExpressionNode init = parseExpression();
        if (init == null) { synchronize(); return null; }
        if (peek().type() == TokenType.COMMA) {
            Token comma = advance();
            rejectedSyntax(comma, "multiple-declarations", "Multiple bindings in one declaration are not supported; use separate declarations",
                    "Declare and initialize each binding separately, in the intended evaluation order.");
            recoverUnsupportedStatement();
        }
        optionalSemicolon();

        Span sp = spanBetween(letToken, previousOrCurrent());
        return new VariableDeclaration(sp, nameToken.lexeme(), typeAnnotation, init,
            letToken.type() == TokenType.CONST);
    }

    // -- ReturnStatement --
    private StatementNode parseReturnStatement() {
        Token retToken = advance();

        Optional<ExpressionNode> expr = Optional.empty();
        if (!isAtEnd() && canStartExpression(peek().type())) {
            if (peek().line() != retToken.line()) {
                lineTerminatorError("No line terminator between return and its operand", "return-operand", retToken);
            }
            ExpressionNode parsed = parseExpression();
            if (parsed != null) expr = Optional.of(parsed);
        }
        optionalSemicolon();

        Span sp = spanBetween(retToken, previousOrCurrent());
        return new ReturnStatement(sp, expr);
    }

    // -- IfStatement --
    private StatementNode parseIfStatement() {
        Token ifToken = advance();

        expect(TokenType.LPAREN, DiagnosticCode.E1014, "Expected '(' after 'if'");
        ExpressionNode condition = parseExpression();
        if (condition == null) { synchronize(); return null; }
        expect(TokenType.RPAREN, DiagnosticCode.E1015, "Expected ')' after if condition");

        Block thenBlock = parseBlock();

        Optional<Either<IfStatement, Block>> elseBranch = Optional.empty();
        if (match(TokenType.ELSE)) {
            if (peek().type() == TokenType.IF) {
                StatementNode elseIfStmt = parseIfStatement();
                if (elseIfStmt instanceof IfStatement ifStmt) {
                    elseBranch = Optional.of(new Either.Left<>(ifStmt));
                }
            } else if (peek().type() == TokenType.LBRACE) {
                Block elseBlock = parseBlock();
                elseBranch = Optional.of(new Either.Right<>(elseBlock));
            } else {
                error(DiagnosticCode.E1016, "Expected 'if' or '{' after 'else'", peek());
            }
        }

        Span sp = spanBetween(ifToken, previousOrCurrent());
        return new IfStatement(sp, condition, thenBlock, elseBranch);
    }

    // -- WhileStatement --
    private StatementNode parseWhileStatement() {
        Token whileToken = advance();

        expect(TokenType.LPAREN, DiagnosticCode.E1014, "Expected '(' after 'while'");
        ExpressionNode condition = parseExpression();
        if (condition == null) { synchronize(); return null; }
        expect(TokenType.RPAREN, DiagnosticCode.E1015, "Expected ')' after while condition");

        Block body = parseBlock();

        Span sp = spanBetween(whileToken, previousOrCurrent());
        return new WhileStatement(sp, condition, body);
    }

    // -- For-of --
    private StatementNode parseForOfStatement() {
        Token forToken = advance();
        if (peek().type() == TokenType.AWAIT) {
            Token await = advance();
            rejectedSyntax(await, "for-await", "For-await is not supported; await calls explicitly in an async body",
                    "Iterate a checked array/string with for-of and await each intended async call inside the body; DEAL has no async iterable protocol.");
        }
        expect(TokenType.LPAREN, DiagnosticCode.E1014, "Expected '(' after 'for'");
        boolean isConst = match(TokenType.CONST);
        if (!isConst) expect(TokenType.LET, DiagnosticCode.E1007, "Expected 'let' or 'const' in for-of loop");
        if (rejectDestructuring("iteration")) {
            while (!isAtEnd() && peek().type() != TokenType.RPAREN && peek().type() != TokenType.RBRACE) advance();
            match(TokenType.RPAREN);
            if (peek().type() == TokenType.LBRACE) parseBlock();
            return null;
        }
        Token name = expect(TokenType.IDENTIFIER, DiagnosticCode.E1007,
            "Expected iteration variable name");
        if (name == null) { synchronize(); return null; }
        TypeNode type = null;
        if (match(TokenType.COLON)) {
            subsetError(DiagnosticCode.E1013, "For-of bindings must not have a type annotation", previous(),
                    "for-of-annotation", "unannotated iteration binding",
                    "DEAL derives the iteration binding type from the checked iterable. See docs/spec-v1.6.md#for-of; example: for (let item of values) { consume(item); }. Choose the intended iterable; no element type is invented.");
            parseType();
        }
        if (peek().type() == TokenType.IDENTIFIER && peek().lexeme().equals("in")) {
            Token in = advance();
            subsetError(DiagnosticCode.E1013, "For-in key enumeration is not supported; choose explicit key or value iteration", in,
                    "for-in", "for-of value iteration or Object.keys key iteration",
                    "Use for-of for array/string values; for class/Record keys, iterate Object.keys(value). These differ from JavaScript for-in inherited-property enumeration.");
            int last = diagnostics.size() - 1;
            diagnostics.set(last, diagnostics.get(last).withFacts(diagnostics.get(last).facts()
                    .withLanguageDifference(deal.diagnostics.LanguageDifference.syntax("for-in"))));
            ExpressionNode iterable = parseExpression();
            expect(TokenType.RPAREN, DiagnosticCode.E1015, "Expected ')' after for-in expression");
            Block body = parseBlock();
            return new ForOfStatement(spanBetween(forToken, previousOrCurrent()), name.lexeme(), type,
                    iterable == null ? new LiteralExpr(spanOf(in), new LiteralValue.NullLiteral()) : iterable, body, isConst);
        }
        if (!match(TokenType.OF)) {
            subsetError(DiagnosticCode.E1013, "Expected 'of'; C-style for loops are not supported", peek(),
                    "c-style-for", "for (let name of iterable) or for (const name of iterable)",
                    "DEAL supports for-of and while, not C-style for headers. See docs/spec-v1.6.md#for-of and #while; example shapes: for (let item of values) { consume(item); } and while (condition) { step(); }. Choose iteration semantics explicitly; initialization, update and continue behavior are not rewritten.");
            recoverUnsupportedForHeader();
            if (peek().type() == TokenType.LBRACE) parseBlock();
            return null;
        }
        ExpressionNode iterable = parseExpression();
        if (iterable == null) { synchronize(); return null; }
        expect(TokenType.RPAREN, DiagnosticCode.E1015, "Expected ')' after for-of iterable");
        Block body = parseBlock();
        return new ForOfStatement(spanBetween(forToken, previousOrCurrent()),
            name.lexeme(), type, iterable, body, isConst);
    }

    // -- Break / Continue --
    private StatementNode parseBreakStatement() {
        Token t = advance();
        rejectJumpLabel();
        optionalSemicolon();
        return new BreakStatement(spanOf(t));
    }

    private StatementNode parseContinueStatement() {
        Token t = advance();
        rejectJumpLabel();
        optionalSemicolon();
        return new ContinueStatement(spanOf(t));
    }

    // -- Import --
    private StatementNode parseImportDeclaration() {
        Token importToken = advance();

        if (!match(TokenType.STAR)) {
            subsetError(DiagnosticCode.E1019, "Expected '*' after 'import' (only namespace imports supported)", peek(),
                    "namespace-import", "import * as alias from a module path string",
                    "DEAL supports namespace imports, not named/default imports. See docs/spec-v1.6.md#import-forms-and-import-typing; example: import * as api from \"./module\";. Choose the intended alias and module explicitly; no replacement import or exported name is inferred.");
            recoverUnsupportedStatement();
            return null;
        }
        if (!match(TokenType.AS)) {
            error(DiagnosticCode.E1020, "Expected 'as' after '*' in import", peek());
            synchronize(); return null;
        }
        Token aliasToken = expect(TokenType.IDENTIFIER, DiagnosticCode.E1021, "Expected import alias name");
        if (aliasToken == null) { synchronize(); return null; }

        if (!match(TokenType.FROM)) {
            error(DiagnosticCode.E1022, "Expected 'from' after import alias", peek());
            synchronize(); return null;
        }
        Token pathToken = expect(TokenType.STRING_LITERAL, DiagnosticCode.E1023,
                "Expected module path string literal after 'from'");
        if (pathToken == null) { synchronize(); return null; }

        // The module path is the string literal's decoded value: the
        // escape table applies exactly as it does to every other string
        // literal (a path carrying a backslash is spelled `\\`).
        String modulePath = unescapeString(pathToken.lexeme());

        optionalSemicolon();
        Span sp = spanBetween(importToken, previousOrCurrent());
        return new ImportDeclaration(sp, aliasToken.lexeme(), modulePath);
    }

    // -- Export --
    private StatementNode parseExportDeclaration() {
        Token exportToken = advance();
        StatementNode declaration;

        TokenType nextType = peek().type();
        if (nextType == TokenType.FUNCTION || nextType == TokenType.ASYNC) {
            declaration = parseFunctionDeclaration();
        } else if (nextType == TokenType.CLASS) {
            declaration = parseClassDeclaration();
        } else {
            error(DiagnosticCode.E1024, "'export' must be followed by 'function', 'async function', or 'class'", peek());
            synchronize(); return null;
        }

        if (declaration == null) return null;

        // Directive binding is applied by parseStatement/applyBinding at
        // the statement-dispatch start token (D4) — the export token.
        Span sp = spanBetween(exportToken, previousOrCurrent());
        return new ExportDeclaration(sp, declaration);
    }

    // -- Delete --
    private StatementNode parseDeleteStatement() {
        Token deleteToken = advance();

        ExpressionNode target = parseExpression();
        if (target == null) { synchronize(); return null; }

        ExpressionNode storage = target instanceof OptionalChainExpr chain ? chain.expression() : target;
        if (!(storage instanceof MemberAccessExpr) && !(storage instanceof IndexExpr)) {
            error(DiagnosticCode.E1025,
                "Delete target must be a member access (obj.field) or index access (arr[idx])",
                target);
        }

        optionalSemicolon();
        Span sp = spanBetween(deleteToken, previousOrCurrent());
        return new DeleteStatement(sp, target);
    }

    // -- Try --
    private StatementNode parseTryStatement() {
        Token tryToken = advance();
        Block tryBlock = parseBlock();

        if (peek().type() == TokenType.IDENTIFIER && peek().lexeme().equals("finally")) {
            rejectFinally();
            return null;
        }
        if (!match(TokenType.CATCH)) {
            error(DiagnosticCode.E1026, "Expected 'catch' after try block", peek());
            synchronize(); return null;
        }

        if (peek().type() == TokenType.LBRACE) {
            rejectedSyntax(peek(), "bindingless-catch", "Catch requires a binding; write catch (error)",
                    "Bind the caught value as unknown; narrow or check it before typed use, even when the value is otherwise unused.");
            parseBlock();
            return null;
        }
        expect(TokenType.LPAREN, DiagnosticCode.E1027, "Expected '(' after 'catch'");
        Token catchVarToken = expect(TokenType.IDENTIFIER, DiagnosticCode.E1028,
                "Expected catch variable name");
        if (catchVarToken == null) { synchronize(); return null; }
        expect(TokenType.RPAREN, DiagnosticCode.E1015, "Expected ')' after catch variable");

        Block catchBlock = parseBlock();
        if (peek().type() == TokenType.IDENTIFIER && peek().lexeme().equals("finally")) rejectFinally();

        Span sp = spanBetween(tryToken, previousOrCurrent());
        return new TryStatement(sp, tryBlock, catchVarToken.lexeme(),
            spanOf(catchVarToken), catchBlock);
    }

    // -- Throw --
    private StatementNode parseThrowStatement() {
        Token throwToken = advance();
        if (peek().line() != throwToken.line()) {
            lineTerminatorError("No line terminator between throw and its operand", "throw-operand", throwToken);
        }

        ExpressionNode expr = parseExpression();
        if (expr == null) { synchronize(); return null; }

        optionalSemicolon();
        Span sp = spanBetween(throwToken, previousOrCurrent());
        return new ThrowStatement(sp, expr);
    }

    // -- ExpressionStatement --
    private StatementNode parseExpressionStatement() {
        ExpressionNode expr = parseExpression();
        if (expr == null) { synchronize(); return null; }

        optionalSemicolon();
        return new ExpressionStatement(expr.span(), expr);
    }

    // =======================================================================
    // Type parsing (spec.md Type grammar)
    // =======================================================================

    private TypeNode parseType() {
        if (peek().type() == TokenType.LPAREN) {
            TypeNode funcType = tryParseFunctionType();
            if (funcType != null) return funcType;
        }
        return parseNullableType();
    }

    private TypeNode tryParseFunctionType() {
        // An opening parenthesis cannot name a parameter. This is a grouped
        // type, often a function result such as (()=>number). Do not let
        // malformed-parameter recovery skip its inner delimiters and consume
        // an enclosing arrow's => as though it belonged to this type.
        if (pos + 1 < tokens.size() && tokens.get(pos + 1).type() == TokenType.LPAREN)
            return null;
        int savedPos = pos;
        int diagSize = diagnostics.size();

        Token lparen = advance();

        List<FunctionTypeParam> params = new ArrayList<>();

        if (peek().type() != TokenType.RPAREN) {
            if (peek().type() == TokenType.ELLIPSIS) {
                // function types have no rest arm.
                restParameterError("Rest parameters are not supported (function types carry no rest arm)");
                skipRestArmTokens();
            } else {
                FunctionTypeParam first = parseFunctionTypeParam();
                if (first != null) params.add(first);
                else recoverParameter();

                while (match(TokenType.COMMA)) {
                    if (peek().type() == TokenType.RPAREN) {
                        break;
                    }
                    if (peek().type() == TokenType.ELLIPSIS) {
                        restParameterError("Rest parameters are not supported (function types carry no rest arm)");
                        skipRestArmTokens();
                        break;
                    }
                    FunctionTypeParam param = parseFunctionTypeParam();
                    if (param != null) params.add(param);
                    else recoverParameter();
                }
            }
        }

        if (!match(TokenType.RPAREN) || !match(TokenType.ARROW)) {
            // Restore state — not a function type
            pos = savedPos;
            while (diagnostics.size() > diagSize) {
                diagnostics.remove(diagnostics.size() - 1);
            }
            return null;
        }

        ResultAnnotation result = parseResultAnnotation();
        TypeNode returnType = result.type();
        if (returnType == null) {
            returnType = new NamedType(spanOf(previousOrCurrent()), "void");
        }

        return new FunctionType(spanBetween(lparen, previous()),
                List.copyOf(params), returnType, result.isAsync());
    }

    private FunctionTypeParam parseFunctionTypeParam() {
        if (rejectDestructuring("parameter")) return null;
        Token nameToken = expect(TokenType.IDENTIFIER, DiagnosticCode.E1029,
                "Expected parameter name in function type");
        if (nameToken == null) return null;

        if (!match(TokenType.COLON)) {
            expectedToken(DiagnosticCode.E1008, "Expected ':' after parameter name in function type", TokenType.COLON);
            return null;
        }
        TypeNode type = parseType();
        if (type == null) return null;

        return new FunctionTypeParam(spanBetween(nameToken, previous()),
                nameToken.lexeme(), type);
    }

    /**
     * Recovery after an E1047 rest-arm rejection inside a function type:
     * consume the ellipsis, the (optional) parameter name, the ':' and the
     * type so the caller can resume at the expected ')'.
     */
    private void skipRestArmTokens() {
        advance();  // ELLIPSIS
        if (match(TokenType.IDENTIFIER)) {
            match(TokenType.COLON);
            parseType();
        }
    }

    private TypeNode parseNullableType() {
        // 'null' is a BuiltinType, and the grammar's ArrayType admits every
        // PrimaryType with any number of '[]' suffixes. A leading 'null'
        // therefore starts either the plain null primary, an array whose
        // element chain is rooted at null (null[], null[][]), or the
        // "null |" nullable prefix. The array suffixes bind tighter than
        // '|', so they are consumed before any nullable decision.
        if (peek().type() == TokenType.NULL || isUndefinedToken(peek())) {
            Token nullToken = advance();
            TypeNode nullType = parseArraySuffixes(new NamedType(spanOf(nullToken), nullToken.lexeme()));
            // "null |" prefix — can be chained: null | null | T
            if (isPlainNullish(nullType) && match(TokenType.PIPE)) {
                TypeNode inner = parseNullableType();
                if (inner == null) return null;
                return new NullableType(spanBetween(nullToken, previous()), inner,
                    nullToken.lexeme().equals("undefined"));
            }
            return parseNullableSuffix(nullType);
        }

        TypeNode inner = parseArrayType();
        if (inner == null) return null;

        TypeNode result = parseNullableSuffix(inner);
        if (peek().type() == TokenType.AMPERSAND) {
            Token intersection = advance();
            rejectedSyntax(intersection, "intersection-type", "Intersection types are not supported; choose one declared type",
                    "Declare the intended nominal fields in one class; DEAL does not combine structural type contracts.");
            parseType();
        }
        return result;
    }

    /** Whether a parsed type node is the bare {@code null} builtin primary. */
    private static boolean isPlainNullish(TypeNode type) {
        return type instanceof NamedType named
            && (named.name().equals("null") || named.name().equals("undefined"));
    }

    private static boolean isUndefinedToken(Token token) {
        return token.type() == TokenType.IDENTIFIER && token.lexeme().equals("undefined");
    }

    /**
     * The "| null" suffix of a nullable type. The grammar admits at most
     * one suffix; chained forms ({@code T | null | null}) are parsed here
     * and rejected by the type checker's nullable invariant (E3005) so the
     * exact diagnostic stays the checker's.
     */
    private TypeNode parseNullableSuffix(TypeNode type) {
        TypeNode inner = type;
        while (match(TokenType.PIPE)) {
            if (peek().type() == TokenType.NULL || isUndefinedToken(peek())) {
                Token member = advance();
                inner = new NullableType(
                        spanBetween(tokenSpanStart(inner), previous()),
                        inner, member.lexeme().equals("undefined"));
            } else {
                subsetError(DiagnosticCode.E1030, "Arbitrary unions are not supported; only null/undefined may widen a base type", peek(),
                        "nullable-member", "null or undefined",
                        "Choose one base type with optional null/undefined members, or an intentional unknown contract with checked extraction.");
                int last = diagnostics.size() - 1;
                diagnostics.set(last, diagnostics.get(last).withFacts(diagnostics.get(last).facts()
                        .withLanguageDifference(deal.diagnostics.LanguageDifference.syntax("arbitrary-union"))));
                parseArrayType();
            }
        }
        return inner;
    }

    private TypeNode parseArrayType() {
        Token start = peek();
        boolean readonly = start.lexeme().equals("readonly")
            && (isIdentifier(peek(1).type()) || peek(1).type() == TokenType.LPAREN
                || peek(1).type() == TokenType.NULL);
        if (readonly) advance();
        TypeNode type = parsePrimaryType();
        if (type == null) return null;
        type = parseArraySuffixes(type);
        if (readonly) {
            if (type instanceof ArrayType array)
                return new ArrayType(spanBetween(start, previous()), array.elementType(), true);
            error(DiagnosticCode.E1031, "readonly requires an array type", start);
        }
        return type;
    }

    /** Consumes the {@code ('[' ']')*} suffixes of an ArrayType onto its primary. */
    private TypeNode parseArraySuffixes(TypeNode primary) {
        TypeNode type = primary;
        while (match(TokenType.LBRACKET)) {
            Token opener = previous();
            expectCloser(TokenType.RBRACKET, DiagnosticCode.E1031, "Expected ']' in array type", opener);
            type = new ArrayType(spanBetween(tokenSpanStart(type), previous()), type);
        }
        return type;
    }

    private TypeNode parsePrimaryType() {
        TokenType tt = peek().type();
        if (tt == TokenType.LBRACKET || tt == TokenType.LBRACE) {
            Token opener = advance();
            String feature = tt == TokenType.LBRACKET ? "tuple-type" : "inline-object-type";
            rejectedSyntax(opener, feature, tt == TokenType.LBRACKET
                            ? "Tuple types are not supported; choose an array or nominal class"
                            : "Inline object types are not supported; declare a nominal class with fields",
                    "Use an array for one element type or a declared class for named typed fields; Record<string, unknown> requires checked extraction on reads.");
            skipBalancedType(opener.type(), tt == TokenType.LBRACKET ? TokenType.RBRACKET : TokenType.RBRACE);
            return new NamedType(spanOf(opener), "unknown");
        }
        if (tt == TokenType.STRING_LITERAL || tt == TokenType.NUMBER_LITERAL || tt == TokenType.TRUE
                || tt == TokenType.FALSE || tt == TokenType.MINUS) {
            Token literal = advance();
            rejectedSyntax(literal, "literal-type", "Literal types are not supported; use string, number or boolean",
                    "Choose the primitive type and validate any value-specific constraint explicitly; a primitive annotation is not a literal-value constraint.");
            if (tt == TokenType.MINUS && peek().type() == TokenType.NUMBER_LITERAL) advance();
            return new NamedType(spanOf(literal), "unknown");
        }

        // Parenthesized type: the v1.2 surface admits a parenthesized
        // nullable array element type, e.g. (int | null)[] (the spec's
        // nullable-array example). Function types are consumed by
        // tryParseFunctionType before this arm is reached.
        if (tt == TokenType.LPAREN) {
            Token lparen = advance();
            TypeNode inner = parseType();
            if (inner == null) return null;
            if (inner instanceof NamedType named && named.name().equals("void")) {
                error(DiagnosticCode.E1032, "Parenthesized void is not a data type", lparen);
            }
            expectCloser(TokenType.RPAREN, DiagnosticCode.E1015, "Expected ')' after type", lparen);
            return inner;
        }

        // 'null' keyword
        if (tt == TokenType.NULL) {
            Token t = advance();
            return new NamedType(spanOf(t), "null");
        }

        // Builtin type names (identifiers): boolean, int, number, string, table, Error
        // Also a single-qualifier type like ModuleAlias.ClassName
        if (isIdentifier(tt)) {
            Token first = advance();
            if ((first.lexeme().equals("Array") || first.lexeme().equals("Promise")) && peek().type() == TokenType.LT) {
                rejectedSyntax(first, first.lexeme().equals("Array") ? "array-generic-type" : "promise-data-type",
                        first.lexeme().equals("Array") ? "Array<T> is not supported; spell array types T[]"
                                : "Promise<T> is not a data type; use it only as an async result annotation",
                        first.lexeme().equals("Array") ? "Use T[] or readonly T[] for the intended element type."
                                : "Async operations require immediate await in async bodies; they cannot be stored, passed or inspected as data.");
                advance();
                skipAngleType();
                return new NamedType(spanOf(first), "unknown");
            }
            if (first.lexeme().equals("Record")) {
                expect(TokenType.LT, DiagnosticCode.E1032, "Expected '<' after Record");
                Token key = expect(TokenType.IDENTIFIER, DiagnosticCode.E1032, "Expected string key type");
                expect(TokenType.COMMA, DiagnosticCode.E1032, "Expected ',' in Record type");
                Token value = expect(TokenType.IDENTIFIER, DiagnosticCode.E1032, "Expected unknown value type");
                expect(TokenType.GT, DiagnosticCode.E1032, "Expected '>' after Record type");
                if (key == null || !key.lexeme().equals("string") || value == null || !value.lexeme().equals("unknown")) {
                    rejectedSyntax(first, "record-type-parameters", "Only Record<string, unknown> is supported; use a class for typed fields",
                            "Record keys are strings and reads are unknown; choose a nominal class when field names/types form a fixed contract.");
                }
                return new NamedType(spanBetween(first, previous()), "Record<string, unknown>");
            }

            // Qualified type: exactly one '.' level (v1.4 Type grammar:
            // PrimaryType ::= Identifier | Identifier '.' Identifier).
            if (peek().type() == TokenType.DOT) {
                Token dot = advance();
                Token nameToken = expect(TokenType.IDENTIFIER,
                    DiagnosticCode.E1032, "Expected type name after '.'");
                if (nameToken == null) {
                    return new NamedType(spanOf(first), first.lexeme());
                }
                // Overqualification (A.B.C) is outside the v1.4 type
                // grammar: reject at the second '.' and consume the
                // remaining qualifier segments for recovery.
                if (peek().type() == TokenType.DOT) {
                    subsetError(DiagnosticCode.E1058,
                        "Type names admit at most one module or class qualifier component", peek(),
                        "type-qualifier", "unqualified type or one module/class qualifier",
                        "Select the intended visible type and exact provider; no import or provider substitution is inferred.");
                    while (peek().type() == TokenType.DOT) {
                        advance();
                        if (peek().type() == TokenType.IDENTIFIER) {
                            advance();
                        } else {
                            break;
                        }
                    }
                }
                Span qspan = spanBetween(first, nameToken);
                return new QualifiedType(qspan, first.lexeme(), nameToken.lexeme());
            }

            return new NamedType(spanOf(first), first.lexeme());
        }

        // Parenthesized type
        if (tt == TokenType.LPAREN) {
            advance(); // '('
            TypeNode inner = parseType();
            if (inner == null) { synchronize(); return null; }
            expect(TokenType.RPAREN, DiagnosticCode.E1015, "Expected ')' after parenthesized type");
            return inner;
        }

        // 'true', 'false' — not valid type names, but parse as NamedType for error recovery
        if (tt == TokenType.TRUE || tt == TokenType.FALSE) {
            Token t = advance();
            return new NamedType(spanOf(t), t.lexeme());
        }

        diagnostics.add(fromTokenAnchor(DiagnosticCode.E1032, "error", "Expected type name", peek())
                .withFacts(new deal.diagnostics.DiagnosticFacts("syntax.type-required", "parse.type",
                        "DEAL type", peek().type().name(),
                        List.of("Provide the intended admitted type annotation; no type is inferred from recovery."))
                        .withSyntax(syntaxContext("type", peek(), List.of()))));
        return null;
    }

    // =======================================================================
    // Parameter list parsing
    // =======================================================================

    private record ParamListResult(List<Parameter> params) {}

    private ParamListResult parseParameterList() {
        List<Parameter> params = new ArrayList<>();

        if (peek().type() == TokenType.RPAREN) {
            return new ParamListResult(params);
        }

        if (peek().type() == TokenType.ELLIPSIS) {
            // Rest parameters were removed from the language.
            restParameterError("Rest parameters are not supported");
            skipRestParameterTokens();
            return new ParamListResult(params);
        }

        Parameter first = parseParameter();
        if (first != null) params.add(first);
        else recoverParameter();

        while (match(TokenType.COMMA)) {
            if (peek().type() == TokenType.RPAREN) {
                break;
            }
            if (peek().type() == TokenType.ELLIPSIS) {
                restParameterError("Rest parameters are not supported");
                skipRestParameterTokens();
                break;
            }
            Parameter param = parseParameter();
            if (param != null) params.add(param);
            else recoverParameter();
        }

        return new ParamListResult(params);
    }

    /** Resume at this list's next parameter or closer, never consume a body/declaration boundary. */
    private void recoverParameter() {
        int parentheses = 0;
        int brackets = 0;
        while (!isAtEnd()) {
            TokenType kind = peek().type();
            if (kind == TokenType.LBRACE || kind == TokenType.RBRACE || kind == TokenType.SEMICOLON) return;
            if (parentheses == 0 && brackets == 0 && (kind == TokenType.COMMA || kind == TokenType.RPAREN)) return;
            if (kind == TokenType.LPAREN) parentheses++;
            else if (kind == TokenType.RPAREN) { if (parentheses == 0) return; parentheses--; }
            else if (kind == TokenType.LBRACKET) brackets++;
            else if (kind == TokenType.RBRACKET && brackets > 0) brackets--;
            advance();
        }
    }

    private Parameter parseParameter() {
        if (rejectDestructuring("parameter")) return null;
        Token nameToken = expect(TokenType.IDENTIFIER, DiagnosticCode.E1029, "Expected parameter name");
        if (nameToken == null) return null;

        if (!match(TokenType.COLON)) {
            if (peek().type() != TokenType.COMMA && peek().type() != TokenType.RPAREN) {
                expectedToken(DiagnosticCode.E1008, "Expected ':' after parameter name", TokenType.COLON);
                return null;
            }
            return new Parameter(spanBetween(nameToken, nameToken), nameToken.lexeme(), null);
        }
        TypeNode type = parseType();
        if (type == null) return null;

        return new Parameter(spanBetween(nameToken, previousOrCurrent()),
                nameToken.lexeme(), type);
    }

    /**
     * Recovery after an E1047 rest-parameter rejection inside a parameter
     * list: consume the ellipsis, the (optional) parameter name, the ':'
     * and the type so the caller can resume at the expected ')'.
     */
    private void skipRestParameterTokens() {
        advance();  // ELLIPSIS
        if (match(TokenType.IDENTIFIER)) {
            match(TokenType.COLON);
            parseType();
        }
    }

    // =======================================================================
    // Expression parsing (precedence climbing)
    // =======================================================================

    private ExpressionNode parseExpression() {
        return parseAssignment();
    }

    private ExpressionNode parseAssignment() {
        ExpressionNode left = parseConditional();
        if (left == null) return null;

        AssignmentOperator assignmentOperator = switch (peek().type()) {
            case EQ_SIGN -> AssignmentOperator.ASSIGN;
            case PLUS_ASSIGN -> AssignmentOperator.ADD;
            case MINUS_ASSIGN -> AssignmentOperator.SUB;
            case STAR_ASSIGN -> AssignmentOperator.MUL;
            case SLASH_ASSIGN -> AssignmentOperator.DIV;
            case PERCENT_ASSIGN -> AssignmentOperator.REM;
            default -> null;
        };
        if (assignmentOperator != null) {
            advance();
            ExpressionNode right = parseAssignment();
            if (right == null) return left;

            if (!isValidAssignmentTarget(left)) {
                error(DiagnosticCode.E1033, "Invalid assignment target", left);
            }

            Span sp = spanBetween(left.span(), right.span());
            return new AssignmentExpr(sp, left, right, assignmentOperator);
        }

        return left;
    }

    private ExpressionNode parseConditional() {
        ExpressionNode condition = parseShortCircuit();
        if (condition == null || !match(TokenType.QUESTION)) return condition;
        ExpressionNode consequent = parseAssignment();
        expect(TokenType.COLON, DiagnosticCode.E1007, "Expected ':' in conditional expression");
        ExpressionNode alternate = parseAssignment();
        if (consequent == null || alternate == null) return condition;
        return new ConditionalExpr(spanBetween(condition.span(), alternate.span()),
            condition, consequent, alternate);
    }

    private ExpressionNode parseShortCircuit() {
        ExpressionNode left = parseBinary(PREC_EQUALITY);
        if (left == null) return null;
        if (match(TokenType.COALESCE)) {
            do {
                ExpressionNode right = parseBinary(PREC_EQUALITY);
                if (right == null) return left;
                left = new BinaryExpr(spanBetween(left.span(), right.span()), left, BinaryOp.COALESCE, right);
            } while (match(TokenType.COALESCE));
            return left;
        }
        // Resume the logical precedence levels after the first equality
        // operand. A parenthesized expression is a primary and therefore
        // may contain either short-circuit family independently.
        while (peek().type() == TokenType.AND || peek().type() == TokenType.OR) {
            Token operator = advance();
            int precedence = getBinaryPrecedence(operator.type());
            ExpressionNode right = parseBinary(precedence + 1);
            if (right == null) return left;
            left = new BinaryExpr(spanBetween(left.span(), right.span()), left,
                tokenToBinaryOp(operator.type()), right);
        }
        return left;
    }

    private ExpressionNode parseBinary(int minPrec) {
        ExpressionNode left = parseUnary();
        if (left == null) return null;

        while (true) {
            Token op = peek();
            if (isRejectedBinaryOperator()) {
                String spelling = op.lexeme();
                if ((op.type() == TokenType.LT || op.type() == TokenType.GT)) {
                    advance();
                    spelling += advance().lexeme();
                    if (op.type() == TokenType.GT && peek().type() == TokenType.GT
                            && previous().range(file).endScalarOffset() == peek().range(file).startScalarOffset()) spelling += advance().lexeme();
                    op = new Token(TokenType.REJECTED_OPERATOR, spelling, op.line(), op.column(), spelling.length(), op.range(file).startScalarOffset(), spelling.length());
                } else advance();
                rejectedSyntax(op, "operator", "Operator '" + spelling + "' is not supported; choose an explicit supported operation",
                        "DEAL has arithmetic and boolean operators, not bitwise, shift or logical/exponentiation assignments; preserve operand evaluation and intended numeric semantics explicitly.");
                parseBinary(PREC_MULTIPLICATIVE + 1);
                continue;
            }
            if (op.type() == TokenType.IDENTIFIER && op.lexeme().equals("satisfies")) {
                advance();
                rejectedSyntax(op, "satisfies", "The satisfies operator is not supported; use a declared type annotation",
                        "Annotate the intended binding or parameter; an as assertion performs a runtime check and is not equivalent to satisfies.");
                parseType();
                continue;
            }
            int prec = getBinaryPrecedence(op.type());
            if (prec < minPrec) break;

            advance();
            if (op.type() == TokenType.EQ || op.type() == TokenType.NEQ) {
                subsetError(DiagnosticCode.E1042,
                        "Coercing equality '" + op.lexeme() + "' is not supported; choose an explicit comparison", op,
                        "coercing-equality", "explicit comparison without coercion",
                        "Strict ===/!== do not coerce and distinguish null from undefined; replacing this operator can change the result. Test both nullish values explicitly when intended.");
                int last = diagnostics.size() - 1;
                diagnostics.set(last, diagnostics.get(last).withFacts(diagnostics.get(last).facts()
                        .withLanguageDifference(deal.diagnostics.LanguageDifference.syntax("coercing-equality"))));
                parseBinary(prec + 1);
                continue;
            }
            if (op.type() == TokenType.AS || op.type() == TokenType.INSTANCEOF) {
                TypeNode target = op.type() == TokenType.AS ? parseType() : parsePrimaryType();
                if (target == null) return left;
                Span sp = spanBetween(left.span(), target.span());
                left = op.type() == TokenType.AS ? new AssertionExpr(sp, left, target)
                    : new InstanceofExpr(sp, left, target);
                if (getBinaryPrecedence(peek().type()) == prec) {
                    error(DiagnosticCode.E1056, "Chained relational expression is not supported", peek());
                }
                continue;
            }
            int nextMinPrec = prec + 1;
            ExpressionNode right = parseBinary(nextMinPrec);
            if (right == null) return left;

            BinaryOp binaryOp = tokenToBinaryOp(op.type());
            Span sp = spanBetween(left.span(), right.span());
            left = new BinaryExpr(sp, left, binaryOp, right);

            // Comparison groups are non-associative (v1.2 grammar): a second
            // comparison operator at the same precedence requires parentheses.
            if ((prec == PREC_EQUALITY || prec == PREC_RELATIONAL)
                    && getBinaryPrecedence(peek().type()) == prec) {
                error(DiagnosticCode.E1056,
                    "Chained comparison is not allowed; parenthesize the operands",
                    peek());
            }
        }

        return left;
    }

    private ExpressionNode parseUnary() {
        if (peek().type() == TokenType.REJECTED_OPERATOR || peek().type() == TokenType.AMPERSAND
                || peek().type() == TokenType.PIPE
                || peek().type() == TokenType.IDENTIFIER && peek().lexeme().equals("void")) {
            Token operator = advance();
            rejectedSyntax(operator, operator.lexeme().equals("void") ? "void-expression" : "operator",
                    "Unary '" + operator.lexeme() + "' is not supported; choose an explicit supported expression",
                    "Use an ordinary expression statement to discard a result; no bitwise conversion or implicit coercion is available.");
            return parseUnary();
        }
        if (peek().type() == TokenType.LT) {
            Token opener = advance();
            rejectedSyntax(opener, "angle-assertion", "Angle-bracket assertions are not supported; use a checked 'as T' assertion",
                    "DEAL's value as T checks the value at runtime without coercion; choose the intended target type explicitly.");
            parseType();
            expect(TokenType.GT, DiagnosticCode.E1031, "Expected '>' after assertion type");
            return parseUnary();
        }
        if (match(TokenType.TYPEOF)) {
            Token token = previous();
            ExpressionNode value = parseUnary();
            return value == null ? null : new UnaryExpr(combineTokenAndSpan(token, value.span()), UnaryOp.TYPEOF, value);
        }
        // await expression: await PostfixExpression
        if (match(TokenType.AWAIT)) {
            Token awaitToken = previous();
            ExpressionNode expr = parsePostfix();
            if (expr == null) return null;
            if (!(expr instanceof CallExpr)) {
                subsetError(DiagnosticCode.E1042, "'await' must be followed by a function call", awaitToken,
                        "await-call", "direct awaited call expression",
                        "DEAL await syntax requires a call; choose the intended async callable rather than inventing a call or Promise value.");
            }
            Span sp = spanBetween(spanOf(awaitToken), expr.span());
            return new AwaitExpression(sp, expr);
        }

        if (match(TokenType.BANG)) {
            Token bang = previous();
            ExpressionNode operand = parseUnary();
            if (operand == null) return null;
            Span sp = spanBetween(spanOf(bang), operand.span());
            return new UnaryExpr(sp, UnaryOp.NOT, operand);
        }

        if (match(TokenType.MINUS)) {
            Token minus = previous();
            ExpressionNode operand = parseUnary();
            if (operand == null) return null;
            Span sp = spanBetween(spanOf(minus), operand.span());
            return new UnaryExpr(sp, UnaryOp.NEG, operand);
        }

        if (peek().type() == TokenType.INCREMENT || peek().type() == TokenType.DECREMENT) {
            Token operator = advance();
            ExpressionNode target = parsePostfix();
            return target == null ? null : updateExpression(operator, target, false);
        }
        ExpressionNode target = parsePostfix();
        if (target != null && (peek().type() == TokenType.INCREMENT || peek().type() == TokenType.DECREMENT)
                && peek().line() == target.span().endLine())
            return updateExpression(advance(), target, true);
        return target;
    }

    private AssignmentExpr updateExpression(Token operator, ExpressionNode target, boolean postfix) {
        if (!isValidAssignmentTarget(target)) error(DiagnosticCode.E1033, "Invalid update target", target);
        AssignmentOperator kind = operator.type() == TokenType.INCREMENT
            ? (postfix ? AssignmentOperator.POST_INCREMENT : AssignmentOperator.PRE_INCREMENT)
            : (postfix ? AssignmentOperator.POST_DECREMENT : AssignmentOperator.PRE_DECREMENT);
        Span span = postfix ? spanBetween(target.span(), spanOf(operator)) : spanBetween(spanOf(operator), target.span());
        return new AssignmentExpr(span, target,
            new LiteralExpr(spanOf(operator), new LiteralValue.NumberLiteral(1)), kind);
    }

    private ExpressionNode parsePostfix() {
        ExpressionNode expr = parsePrimary();
        if (expr == null) return null;

        boolean optionalChain = false;
        while (true) {
            int genericEnd = genericCallTypeArgumentsEnd();
            if (genericEnd >= 0) {
                Token opener = advance();
                subsetError(DiagnosticCode.E1042, "Explicit type arguments on calls are not supported; use the declared signature", opener,
                        "generic-call", "call without type arguments",
                        "Call the declared function without type arguments. For array literals use a T[] annotation; Array<T>() is not a constructor in DEAL.");
                int last = diagnostics.size() - 1;
                diagnostics.set(last, diagnostics.get(last).withFacts(diagnostics.get(last).facts()
                        .withLanguageDifference(deal.diagnostics.LanguageDifference.syntax("generic-call"))));
                pos = genericEnd + 1;
            }
            boolean optionalPart = match(TokenType.OPTIONAL_CHAIN);
            if (optionalPart) {
                optionalChain = true;
                expr = new OptionalGuardExpr(expr.span(), expr);
            }
            if ((!optionalPart && match(TokenType.DOT))
                    || optionalPart && peek().type() != TokenType.LBRACKET && peek().type() != TokenType.LPAREN) {
                Token fieldToken = expectPropertyName(DiagnosticCode.E1034,
                        "Expected field name after '.'");
                if (fieldToken == null) break;
                Span sp = spanBetween(expr.span(), spanOf(fieldToken));
                expr = new MemberAccessExpr(sp, expr, fieldToken.lexeme());
            } else if (match(TokenType.LBRACKET)) {
                Token opener = previous();
                ExpressionNode index = parseExpression();
                if (index == null) { synchronize(); break; }
                Token rbracket = expectCloser(TokenType.RBRACKET, DiagnosticCode.E1035, "Expected ']'", opener);
                Span sp;
                if (rbracket != null) {
                    sp = spanBetween(expr.span(), spanOf(rbracket));
                } else {
                    sp = spanBetween(expr.span(), index.span());
                }
                expr = new IndexExpr(sp, expr, index);
            } else if (match(TokenType.LPAREN)) {
                Token opener = previous();
                List<ExpressionNode> args = new ArrayList<>();
                if (peek().type() != TokenType.RPAREN) {
                    do {
                        ExpressionNode arg = parseListElement();
                        if (arg != null) args.add(arg);
                        else recoverExpressionList(TokenType.RPAREN);
                    } while (match(TokenType.COMMA) && peek().type() != TokenType.RPAREN);
                }
                Token rparen = expectCloser(TokenType.RPAREN, DiagnosticCode.E1010,
                        "Expected ')' after arguments", opener);
                Span sp;
                if (rparen != null) {
                    sp = spanBetween(expr.span(), spanOf(rparen));
                } else {
                    sp = spanBetween(expr.span(), spanOf(previousOrCurrent()));
                }
                if (expr instanceof MemberAccessExpr member
                        && member.object() instanceof IdentifierExpr id
                        && id.name().equals("Object") && member.field().equals("hasOwn")
                        && args.size() == 2) {
                    ExpressionNode key = args.get(1);
                    String literal = key instanceof LiteralExpr lit
                        && lit.value() instanceof LiteralValue.StringLiteral s ? s.value() : null;
                    expr = new HasExpr(sp, args.get(0), literal, key);
                } else {
                    expr = new CallExpr(sp, expr, List.copyOf(args));
                }
            } else if (peek().type() == TokenType.TEMPLATE_LITERAL) {
                Token template = advance();
                rejectedSyntax(template, "tagged-template", "Tagged templates are not supported; use an ordinary call or template",
                        "Choose an ordinary function call with explicit arguments or an untagged template; neither reproduces tag invocation automatically.");
            } else if (peek().type() == TokenType.BANG && peek().line() == expr.span().endLine()) {
                Token bang = advance();
                rejectedSyntax(bang, "non-null-assertion", "Postfix '!' is not supported; guard null and undefined explicitly",
                        "Use explicit null/undefined guards before access; a checked as T assertion can fail at runtime and is not a non-null assertion.");
            } else {
                break;
            }
        }

        return optionalChain ? new OptionalChainExpr(expr.span(), expr) : expr;
    }

    /** A complete angle-bracket list followed by arguments, not an ordinary relational operand. */
    private int genericCallTypeArgumentsEnd() {
        if (peek().type() != TokenType.LT) return -1;
        int depth = 0;
        boolean member = false;
        for (int i = pos; i < tokens.size(); i++) {
            TokenType kind = tokens.get(i).type();
            if (kind == TokenType.LT) depth++;
            else if (kind == TokenType.GT) {
                if (--depth == 0) return member && i + 1 < tokens.size()
                        && tokens.get(i + 1).type() == TokenType.LPAREN ? i : -1;
            } else if (kind == TokenType.IDENTIFIER || kind == TokenType.NULL || kind == TokenType.ASYNC
                    || kind == TokenType.AS || kind == TokenType.FROM || kind == TokenType.OF) member = true;
            else if (kind != TokenType.COMMA && kind != TokenType.DOT && kind != TokenType.PIPE
                    && kind != TokenType.LBRACKET && kind != TokenType.RBRACKET
                    && kind != TokenType.LPAREN && kind != TokenType.RPAREN
                    && kind != TokenType.COLON && kind != TokenType.ARROW) return -1;
        }
        return -1;
    }

    private ExpressionNode parsePrimary() {
        Token token = peek();

        switch (token.type()) {
            case NULL -> {
                advance();
                return new LiteralExpr(spanOf(previous()), new LiteralValue.NullLiteral());
            }
            case TRUE -> {
                advance();
                return new LiteralExpr(spanOf(previous()),
                        new LiteralValue.BooleanLiteral(true));
            }
            case FALSE -> {
                advance();
                return new LiteralExpr(spanOf(previous()),
                        new LiteralValue.BooleanLiteral(false));
            }
            case REJECTED_BIGINT -> {
                advance();
                rejectedSyntax(token, "bigint-literal", "BigInt literals are not supported; DEAL numbers are binary64",
                        "Choose a number only if binary64 precision is suitable, or represent exact integers explicitly; removing n can lose precision.");
                return new LiteralExpr(spanOf(token), new LiteralValue.NullLiteral());
            }
            case SLASH -> {
                advance();
                rejectedSyntax(token, "regexp-literal", "Regular-expression literals are not supported; use supported string operations",
                        "Use the specified string methods for literal searches, or an explicitly imported host API for regular-expression behavior.");
                while (!isAtEnd() && peek().type() != TokenType.SLASH && peek().type() != TokenType.SEMICOLON
                        && peek().type() != TokenType.RBRACE && peek().line() == token.line()) advance();
                if (match(TokenType.SLASH) && peek().type() == TokenType.IDENTIFIER) advance();
                return new LiteralExpr(spanOf(token), new LiteralValue.NullLiteral());
            }
            case NUMBER_LITERAL -> {
                advance();
                double value;
                try {
                    String spelling = previous().lexeme().replace("_", "");
                    int radix = spelling.length() > 2 && spelling.charAt(0) == '0'
                        ? switch (spelling.charAt(1)) {
                            case 'x', 'X' -> 16;
                            case 'b', 'B' -> 2;
                            case 'o', 'O' -> 8;
                            default -> 0;
                        } : 0;
                    value = radix == 0 ? Double.parseDouble(spelling)
                        : new java.math.BigInteger(spelling.substring(2), radix).doubleValue();
                } catch (NumberFormatException e) {
                    error(DiagnosticCode.E1002, "Invalid number literal: " + previous().lexeme(), previous());
                    value = 0.0;
                }
                return new LiteralExpr(spanOf(previous()),
                        new LiteralValue.NumberLiteral(value));
            }
            case STRING_LITERAL -> {
                advance();
                String value = unescapeString(previous().lexeme());
                return new LiteralExpr(spanOf(previous()),
                        new LiteralValue.StringLiteral(value));
            }
            case TEMPLATE_LITERAL -> {
                advance();
                return parseTemplateLiteral();
            }
            case IDENTIFIER, AS, FROM, OF -> {
                advance();
                return new IdentifierExpr(spanOf(previous()), previous().lexeme());
            }
            case LPAREN -> {
                if (looksLikeArrow(pos)) return parseArrowExpression();
                advance();
                ExpressionNode inner = parseExpression();
                if (inner == null) {
                    synchronize();
                    return new LiteralExpr(spanOf(token), new LiteralValue.NullLiteral());
                }
                if (peek().type() == TokenType.COMMA) {
                    Token comma = advance();
                    rejectedSyntax(comma, "comma-expression", "Comma expressions are not supported; use separate statements",
                            "Keep expressions in their intended evaluation order as separate statements; select the intended result explicitly.");
                    do { parseAssignment(); } while (match(TokenType.COMMA));
                }
                expectCloser(TokenType.RPAREN, DiagnosticCode.E1015, "Expected ')' after expression", token);
                return inner;
            }
            case LBRACKET -> { return parseArrayLiteral(); }
            case LBRACE   -> { return parseObjectLiteral(); }
            case FUNCTION -> { return parseFunctionExpression(); }
            case ASYNC    -> {
                if (pos + 1 < tokens.size() && tokens.get(pos + 1).type() == TokenType.LPAREN
                        && looksLikeArrow(pos + 1)) return parseArrowExpression();
                if (pos + 1 < tokens.size() && tokens.get(pos + 1).type() == TokenType.FUNCTION)
                    return parseFunctionExpression();
                advance();
                return new IdentifierExpr(spanOf(token), token.lexeme());
            }
            default -> {
                expressionRequired(token);
                if (!isStatementBoundary(token.type())) {
                    advance();
                }
                return null;
            }
        }
    }

    // =======================================================================
    // Template literal parsing
    // =======================================================================

    /**
     * Parses a template literal from a TEMPLATE_LITERAL token.
     * The token's lexeme is the raw content between backticks.
     */
    private ExpressionNode parseTemplateLiteral() {
        Token token = previous(); // the TEMPLATE_LITERAL token
        String raw = token.lexeme();
        List<ExpressionNode> parts = splitTemplateLiteral(raw, token.line(),
            token.column(), token.startScalarOffset());
        return new TemplateLiteralExpr(templateLiteralSpan(token, raw), parts);
    }

    /**
     * The span of the complete template literal: from the opening backtick
     * through the closing backtick when the token consumed one, else through
     * the last raw content scalar. Line and column follow the source
     * cursor's CRLF rule, because an interpolation admits whitespace line
     * terminators even though TemplateText does not.
     */
    private Span templateLiteralSpan(Token token, String raw) {
        int contentScalars = ScalarSourceCursor.scalarCount(raw);
        boolean terminated = token.scalarLength() == contentScalars + 2;
        if (terminated) {
            int[] closingBacktick = TemplateScalarMap.positionAt(raw,
                token.line(), token.column(), contentScalars);
            return new Span(file, token.line(), token.column(),
                closingBacktick[0], closingBacktick[1],
                token.startScalarOffset(), token.endScalarOffset());
        }
        if (raw.isEmpty()) {
            return new Span(file, token.line(), token.column(),
                token.line(), token.column(), token.startScalarOffset(),
                token.startScalarOffset());
        }
        int[] lastContent = TemplateScalarMap.positionAt(raw, token.line(),
            token.column(), contentScalars - 1);
        int endOffset = token.hasScalarOffsets()
            ? token.startScalarOffset() + 1 + contentScalars
            : Token.UNKNOWN_OFFSET;
        return new Span(file, token.line(), token.column(),
            lastContent[0], lastContent[1], token.startScalarOffset(),
            endOffset);
    }

    /**
     * Splits raw template literal content into alternating string-literal
     * and expression parts. Even-indexed parts are LiteralExpr(StringLiteral);
     * odd-indexed parts are interpolated expressions.
     *
     * <p>{@link TemplateScanner} owns the TemplateText/interpolation split
     * (v1.4 lexical grammar): TemplateText is verbatim (no escape
     * processing; a backslash and a bare {@code '}'} are ordinary text),
     * while an interpolation admits expression, comment, quoted-string and
     * nested template-literal rules. Each interpolation is sub-lexed
     * verbatim through {@link TemplateScalarMap}, which rebases every
     * sub-lexed coordinate back onto the original source.</p>
     *
     * <p>The scan's trailing-backslash violations (an odd backslash run
     * before a text-run delimiter) are not re-reported here: the lexer
     * already rejected the outer template token through the same shared
     * scan, and a nested template literal's violation is reported by the
     * expression sub-lexer below, which owns that nested literal's own
     * admission. This split therefore reads the segments only.</p>
     */
    private List<ExpressionNode> splitTemplateLiteral(String raw, int baseLine, int baseCol,
                                                      int templateTokenStartScalarOffset) {
        List<ExpressionNode> parts = new ArrayList<>();
        TemplateScanner.Scan scan = TemplateScanner.scan(raw, 0);

        int textStart = 0;
        for (TemplateScanner.Segment seg : scan.segments()) {
            if (!seg.interpolation()) {
                // Text runs are emitted from the gaps around interpolations.
                continue;
            }
            int exprUtf16Start = Math.min(raw.length(), seg.start() + 2);
            int exprUtf16End = Math.min(raw.length(), seg.end());
            parts.add(textPart(raw, baseLine, baseCol,
                templateTokenStartScalarOffset, textStart, seg.start()));
            int exprStartRaw = ScalarSourceCursor.scalarCount(raw, 0,
                exprUtf16Start);
            int exprEndRaw = ScalarSourceCursor.scalarCount(raw, 0,
                exprUtf16End);
            if (seg.unterminated()) {
                error(DiagnosticCode.E1042,
                    "Unterminated '${' in template literal",
                    rawPositionedToken(raw, baseLine, baseCol,
                        templateTokenStartScalarOffset, exprUtf16Start, 2));
            }
            TemplateScalarMap map = new TemplateScalarMap(raw, baseLine, baseCol,
                templateTokenStartScalarOffset, exprStartRaw, exprEndRaw);
            parts.add(parseEmbeddedExpression(
                raw.substring(exprUtf16Start, exprUtf16End), map, exprEndRaw));
            textStart = Math.min(raw.length(), seg.end() + 1);
        }
        parts.add(textPart(raw, baseLine, baseCol, templateTokenStartScalarOffset,
            textStart, raw.length()));
        return parts;
    }

    /**
     * One verbatim TemplateText part: the raw slice with no escape
     * processing, positioned by its exact raw scalar range.
     */
    private LiteralExpr textPart(String raw, int baseLine, int baseCol,
                                 int templateTokenStartScalarOffset,
                                 int utf16Start, int utf16End) {
        String value = TemplateScanner.decodeText(raw.substring(utf16Start, utf16End));
        int startRaw = ScalarSourceCursor.scalarCount(raw, 0, utf16Start);
        int endRaw = ScalarSourceCursor.scalarCount(raw, 0, utf16End);
        int[] startPosition = TemplateScalarMap.positionAt(raw, baseLine, baseCol,
            startRaw);
        int startOffset = templateTokenStartScalarOffset >= 0
            ? templateTokenStartScalarOffset + 1 + startRaw
            : Token.UNKNOWN_OFFSET;
        int endLine = startPosition[0];
        int endColumn = startPosition[1];
        int endOffset = startOffset;
        if (utf16End > utf16Start) {
            int[] endPosition = TemplateScalarMap.positionAt(raw, baseLine,
                baseCol, endRaw - 1);
            endLine = endPosition[0];
            endColumn = endPosition[1];
            endOffset = templateTokenStartScalarOffset >= 0
                ? templateTokenStartScalarOffset + 1 + endRaw
                : Token.UNKNOWN_OFFSET;
        }
        Span sp = new Span(file, startPosition[0], startPosition[1],
            endLine, endColumn, startOffset, endOffset);
        return new LiteralExpr(sp, new LiteralValue.StringLiteral(value));
    }

    /**
     * D5: a raw-positioned E1042 pseudo-token. Column and scalar offset
     * derive from the raw scalar count of the raw-content prefix
     * ({@code baseCol + 1 + rawScalarCount(raw[0..pos))},
     * {@code templateTokenStartScalarOffset + 1 + rawScalarCount(raw[0..pos))});
     * the scalar length is the raw run the token marks.
     */
    private Token rawPositionedToken(String raw, int baseLine, int baseCol,
                                     int templateTokenStartScalarOffset,
                                     int rawUtf16Pos, int runUtf16Length) {
        int rawScalarIndex = ScalarSourceCursor.scalarCount(raw, 0, rawUtf16Pos);
        int runScalars = ScalarSourceCursor.scalarCount(raw, rawUtf16Pos,
            rawUtf16Pos + runUtf16Length);
        int startOffset = templateTokenStartScalarOffset >= 0
            ? templateTokenStartScalarOffset + 1 + rawScalarIndex
            : Token.UNKNOWN_OFFSET;
        int scalarLength = startOffset >= 0 ? runScalars : Token.UNKNOWN_OFFSET;
        return new Token(TokenType.IDENTIFIER, "", baseLine, baseCol + 1 + rawScalarIndex,
            runUtf16Length, startOffset, scalarLength);
    }

    /**
     * D11: Sub-lexer re-entry for an embedded expression inside ${...}.
     * Creates a fresh Lexer and Parser for the expression substring,
     * rebasing every sub-lexed token and diagnostic range through the
     * {@link TemplateScalarMap} back onto the original source coordinates
     * (D5).
     *
     * <p>D16: If the expression is syntactically invalid, substitutes a
     * placeholder LiteralExpr so the rest of the template compiles. The
     * placeholder span is zero scalar length at the expression-start raw
     * position with exact scalar offsets.</p>
     */
    private ExpressionNode parseEmbeddedExpression(String source, TemplateScalarMap map,
                                                   int eofRawScalarIndex) {
        // 1. Sub-lex the expression substring
        deal.lexer.Lexer subLexer = new deal.lexer.Lexer(source, file);
        deal.lexer.LexResult subResult = subLexer.tokenize();

        // 2. Merge sub-lexer diagnostics rebased through the scalar map. A
        // range that cannot be rebased (defensive: non-SOURCE or offset-less)
        // is kept as produced by the sub-lexer. D10.8: when the primary
        // range is rebased, every note range is rebased through the same
        // rule — a note range that cannot be rebased (null range,
        // non-SOURCE origin, missing scalar offsets, or a map without
        // template scalar offsets) is kept as produced; when the primary
        // cannot be rebased the diagnostic including its notes is kept as
        // produced.
        for (CompilerDiagnostic d : subResult.diagnostics()) {
            DiagnosticRange rebased = rebaseSubRange(d.range(), map);
            if (rebased != null) {
                List<DiagnosticNote> rebasedNotes = new ArrayList<>();
                for (DiagnosticNote n : d.notes()) {
                    if (n.range() == null) {
                        rebasedNotes.add(n);
                        continue;
                    }
                    DiagnosticRange rebasedNote = rebaseSubRange(n.range(), map);
                    rebasedNotes.add(rebasedNote != null
                        ? new DiagnosticNote(n.message(), rebasedNote)
                        : n);
                }
                diagnostics.add(new CompilerDiagnostic(d.code(), d.severity(),
                    d.message(), rebased, rebasedNotes, d.diagnosticCode(), d.missingSymbols(), d.facts()));
            } else {
                diagnostics.add(d);
            }
        }

        // 3. Rebuild the adjusted token list: the sub-lexer's own EOF token is
        // discarded; every non-EOF token is rebuilt with explicit rebased
        // scalar offsets (never via the offset-less constructor); a rebased
        // EOF token is appended at the raw position of the first scalar after
        // the expression — the closing '}' position when the interpolation is
        // terminated, else the end of the raw template content — carrying that
        // raw position's scalar offset and zero scalar length (D5). The
        // past-end peek() pseudo-EOF therefore carries exact scalar positions.
        List<Token> adjusted = new ArrayList<>();
        for (Token t : subResult.tokens()) {
            if (t.type() == TokenType.EOF) break;
            adjusted.add(rebaseSubToken(t, map));
        }
        adjusted.add(new Token(TokenType.EOF, "",
            map.sourceLineAtRawScalar(eofRawScalarIndex),
            map.sourceColumnAtRawScalar(eofRawScalarIndex), 0,
            map.sourceOffsetAtRawScalar(eofRawScalarIndex), 0));

        // 4. Empty-expression detection counts tokens other than the retained
        // EOF (D5).
        boolean hasExpressionTokens = false;
        for (Token t : adjusted) {
            if (t.type() != TokenType.EOF) {
                hasExpressionTokens = true;
                break;
            }
        }
        if (!hasExpressionTokens) {
            // The E1042 pseudo-token is raw-positioned: zero scalar length at
            // the expression-start raw position (D5).
            int rawScalarIndex = map.expressionStartRawScalarIndex();
            error(DiagnosticCode.E1042,
                "Empty expression in template literal",
                new Token(TokenType.IDENTIFIER, "",
                    map.sourceLineAtRawScalar(rawScalarIndex),
                    map.sourceColumnAtRawScalar(rawScalarIndex), 0,
                    map.sourceOffsetAtRawScalar(rawScalarIndex), 0));
            return placeholderLiteral(map);
        }

        // 5. Parse interpolated expressions under the same binary64
        //    literal and ordinary unary-expression rules as outer source.
        //    parseExpression() is private but accessible — Java JLS section 6.6.1
        Parser subParser = new Parser(adjusted, file);
        ExpressionNode expr = subParser.parseExpression();

        // 6. Merge sub-parser diagnostics. The adjusted tokens carry rebased
        // line/column positions and exact original scalar offsets, so
        // sub-parser diagnostics are already SOURCE-exact in the original
        // file — including end-of-input errors anchored at the past-end
        // pseudo-EOF derived from the retained rebased EOF token.
        diagnostics.addAll(subParser.diagnostics);

        // 7. v1.4: an interpolation contains exactly one complete
        //    expression (docs/spec-v1.6.md lexical grammar). A leftover
        //    token after the parsed expression is E1042 at that token's
        //    rebased position.
        if (expr != null && subParser.peek().type() != TokenType.EOF) {
            error(DiagnosticCode.E1042,
                "Template literal interpolation must contain exactly one"
                    + " complete expression",
                subParser.peek());
        }

        // D16: Null-safety guard — if the expression is syntactically invalid
        // (e.g., ${@}), parseExpression() returns null. Substitute a placeholder
        // so the rest of the template and compilation can continue. The
        // placeholder span is zero scalar length at the expression-start raw
        // position with exact scalar offsets (D5).
        if (expr == null) {
            expr = placeholderLiteral(map);
        }

        return expr;
    }

    /**
     * Rebases a sub-lexer diagnostic range through the scalar map: the
     * range's decoded scalar offsets name raw scalar indices, and line/column
     * come from the single-line template base arithmetic. Returns null when
     * the range carries no offset information or is not SOURCE — such ranges
     * cannot be translated into original-source SOURCE coordinates.
     */
    private DiagnosticRange rebaseSubRange(DiagnosticRange r, TemplateScalarMap map) {
        if (r == null || r.origin() != RangeOrigin.SOURCE || !r.hasScalarOffsets()
                || !map.hasTemplateScalarOffsets()) {
            return null;
        }
        int decodedStart = r.startScalarOffset();
        int decodedEnd = r.endScalarOffset();
        int rawStart = map.decodedToRawStart(decodedStart);
        int rawEnd = decodedEnd > decodedStart
            ? map.decodedToRawEnd(decodedEnd - 1)
            : rawStart;
        int startOffset = map.sourceOffsetAtRawScalar(rawStart);
        int endOffset = map.sourceOffsetAtRawScalar(rawEnd);
        return new DiagnosticRange(file, map.sourceLineAtRawScalar(rawStart),
            map.sourceColumnAtRawScalar(rawStart),
            map.sourceLineAtRawScalar(rawEnd),
            map.sourceColumnAtRawScalar(rawEnd),
            startOffset, endOffset, endOffset - startOffset, RangeOrigin.SOURCE);
    }

    /**
     * Rebuilds one sub-lexer token with rebased coordinates: start column and
     * scalar offset come from the raw scalar index of the token's first
     * decoded scalar; the scalar length is the raw run the token covers.
     * A token without offset information (defensive/test-only) keeps the
     * line/column rebase and carries UNKNOWN offsets.
     */
    private Token rebaseSubToken(Token t, TemplateScalarMap map) {
        if (!t.hasScalarOffsets()) {
            return new Token(t.type(), t.lexeme(),
                map.sourceLineAtRawScalar(map.expressionStartRawScalarIndex()),
                map.sourceColumnAtRawScalar(map.expressionStartRawScalarIndex())
                    + t.column() - 1,
                t.length());
        }
        int decodedStart = t.startScalarOffset();
        int decodedEnd = t.endScalarOffset();
        int rawStart = map.decodedToRawStart(decodedStart);
        int rawEnd = decodedEnd > decodedStart
            ? map.decodedToRawEnd(decodedEnd - 1)
            : rawStart;
        return new Token(t.type(), t.lexeme(),
            map.sourceLineAtRawScalar(rawStart),
            map.sourceColumnAtRawScalar(rawStart), t.length(),
            map.sourceOffsetAtRawScalar(rawStart),
            Math.max(0, rawEnd - rawStart));
    }

    /**
     * The D16 placeholder: a zero-scalar-length LiteralExpr at the
     * expression-start raw position with explicit exact scalar offsets
     * (derived through the map's raw side).
     */
    private LiteralExpr placeholderLiteral(TemplateScalarMap map) {
        int rawScalarIndex = map.expressionStartRawScalarIndex();
        int line = map.sourceLineAtRawScalar(rawScalarIndex);
        int column = map.sourceColumnAtRawScalar(rawScalarIndex);
        int offset = map.sourceOffsetAtRawScalar(rawScalarIndex);
        return new LiteralExpr(
            new Span(file, line, column, line, column, offset, offset),
            new LiteralValue.StringLiteral(""));
    }

    /** A typed arrow head is distinguished without consuming grouped expressions. */
    private boolean looksLikeArrow(int start) {
        // A grouped consequent followed by ':' is not an arrow head.
        // Nonempty heads begin with a parameter, optionally annotated.
        if (start + 2 < tokens.size() && tokens.get(start + 1).type() != TokenType.RPAREN
                && (tokens.get(start + 1).type() != TokenType.IDENTIFIER
                    || !List.of(TokenType.COLON, TokenType.COMMA, TokenType.RPAREN).contains(tokens.get(start + 2).type()))) return false;
        int depth = 0;
        for (int index = start; index < tokens.size(); index++) {
            TokenType type = tokens.get(index).type();
            if (type == TokenType.LPAREN) depth++;
            if (type == TokenType.RPAREN && --depth == 0) {
                if (index + 1 >= tokens.size() || tokens.get(index + 1).type() != TokenType.COLON) return false;
                if (tokens.get(start + 1).type() == TokenType.RPAREN
                        || tokens.get(start + 2).type() == TokenType.COLON) return true;
                // An unannotated head also looks like a grouped ternary arm.
                // Require a complete result annotation followed by the arrow.
                int savedPos = pos;
                int savedDiagnostics = diagnostics.size();
                pos = index + 2;
                try {
                    ResultAnnotation result = parseResultAnnotation();
                    return result.type() != null && peek().type() == TokenType.ARROW;
                } finally {
                    pos = savedPos;
                    diagnostics.subList(savedDiagnostics, diagnostics.size()).clear();
                }
            }
        }
        return false;
    }

    private ExpressionNode parseArrowExpression() {
        boolean async = match(TokenType.ASYNC);
        Token start = async ? previous() : peek();
        if (async && start.line() != peek().line()) {
            lineTerminatorError("No line terminator between async and (", "async-arrow", start);
        }
        expect(TokenType.LPAREN, DiagnosticCode.E1009, "Expected '(' before arrow parameters");
        ParamListResult params = parseParameterList();
        expect(TokenType.RPAREN, DiagnosticCode.E1010, "Expected ')' after arrow parameters");
        expect(TokenType.COLON, DiagnosticCode.E1011, "Expected arrow result annotation");
        ResultAnnotation result = parseResultAnnotation();
        if (result.type() == null) return null;
        if (peek().line() != previous().line()) {
            lineTerminatorError("No line terminator before =>", "arrow", previous());
        }
        expect(TokenType.ARROW, DiagnosticCode.E1012, "Expected '=>' after arrow result");
        if (async != result.isAsync()) {
            resultNotationError(start, async);
        }
        boolean expressionBody = peek().type() != TokenType.LBRACE;
        Block body;
        if (!expressionBody) {
            body = parseBlock();
        } else {
            ExpressionNode value = parseExpression();
            if (value == null) return null;
            // Retain the distinction for void-body typing; all execution uses ordinary body IR.
            boolean voidResult = result.type() instanceof NamedType named && named.name().equals("void");
            body = new Block(value.span(), voidResult
                ? List.of(new ExpressionStatement(value.span(), value),
                    new ReturnStatement(value.span(), Optional.empty()))
                : List.of(new ReturnStatement(value.span(), Optional.of(value))));
        }
        return new FunctionExpr(spanBetween(start, previousOrCurrent()), params.params(),
            result.type(), body, result.isAsync(), expressionBody);
    }

    private ExpressionNode parseFunctionExpression() {
        boolean isAsync = match(TokenType.ASYNC);
        Token startToken = isAsync ? previous() : peek();
        if (isAsync && peek().line() != startToken.line()) {
            lineTerminatorError("No line terminator between async and function", "async-function", startToken);
        }
        Token funcToken = advance();  // consumes FUNCTION

        FunctionSuffix suffix = parseFunctionSuffix("Expected '(' after 'function'",
            "Expected ':' return type annotation on function expression", false);
        if (suffix == null) { return null; }

        if (isAsync != suffix.isAsync()) {
            resultNotationError(startToken, isAsync);
        }
        Span sp = spanBetween(startToken, previousOrCurrent());
        return new FunctionExpr(sp, suffix.params(), suffix.returnType(), suffix.body(),
            suffix.isAsync());
    }

    private ExpressionNode parseArrayLiteral() {
        Token lbracket = advance();
        List<ExpressionNode> elements = new ArrayList<>();

        if (peek().type() != TokenType.RBRACKET) {
            do {
                ExpressionNode elem;
                if (peek().type() == TokenType.COMMA) {
                    subsetError(DiagnosticCode.E1037, "Array holes are not supported; provide an explicit element", peek(),
                            "array-hole", "explicit array element",
                            "Choose an element of the intended type; a hole is not a stored undefined value.");
                    int last = diagnostics.size() - 1;
                    diagnostics.set(last, diagnostics.get(last).withFacts(diagnostics.get(last).facts()
                            .withLanguageDifference(deal.diagnostics.LanguageDifference.syntax("array-hole"))));
                    elem = null;
                } else elem = parseListElement();
                if (elem != null) elements.add(elem);
                else recoverExpressionList(TokenType.RBRACKET);
            } while (match(TokenType.COMMA) && peek().type() != TokenType.RBRACKET);
        }

        Token rbracket;
        if (match(TokenType.RBRACKET)) {
            rbracket = previous();
        } else {
            expectedCloser(DiagnosticCode.E1035, "Expected ']' to close array literal", TokenType.RBRACKET, lbracket);
            rbracket = previousOrCurrent();
        }

        return new ArrayLiteralExpr(spanBetween(lbracket, rbracket), List.copyOf(elements));
    }

    private ExpressionNode parseObjectLiteral() {
        Token lbrace = advance();
        List<Property> properties = new ArrayList<>();

        if (isObjectKey(peek()) || peek().type() == TokenType.ELLIPSIS) {
            Property first = parseProperty();
            if (first != null) properties.add(first);

            while (match(TokenType.COMMA)) {
                if (peek().type() == TokenType.RBRACE) break;
                if (!isObjectKey(peek()) && peek().type() != TokenType.ELLIPSIS) {
                    error(DiagnosticCode.E1038,
                        "Expected object literal key", peek());
                    break;
                }
                Property prop = parseProperty();
                if (prop != null) properties.add(prop);
                else break;
            }
        } else if (peek().type() != TokenType.RBRACE) {
            error(DiagnosticCode.E1038, "Expected property name or '}' in object literal", peek());
        }

        Token rbrace;
        if (match(TokenType.RBRACE)) {
            rbrace = previous();
        } else {
            expectedCloser(DiagnosticCode.E1006, "Expected '}' to close object literal", TokenType.RBRACE, lbrace);
            rbrace = previousOrCurrent();
        }

        return new ObjectLiteralExpr(spanBetween(lbrace, rbrace), List.copyOf(properties));
    }

    private static boolean isPropertyName(Token token) {
        return switch (token.type()) {
            case IDENTIFIER, LET, CONST, CLASS, FUNCTION, ASYNC, AWAIT, RETURN, IF, ELSE,
                WHILE, FOR, OF, BREAK, CONTINUE, NULL, TRUE, FALSE, IMPORT, EXPORT,
                FROM, DELETE, TYPEOF, INSTANCEOF, TRY, CATCH, THROW, AS -> true;
            default -> false;
        };
    }

    private Token expectPropertyName(DiagnosticCode code, String message) {
        if (isPropertyName(peek())) return advance();
        error(code, message, peek());
        return null;
    }

    private static boolean isObjectKey(Token token) {
        return isPropertyName(token) || token.type() == TokenType.STRING_LITERAL
            || token.type() == TokenType.LBRACKET;
    }

    private Property parseProperty() {
        Token nameToken = advance();
        if (nameToken.type() == TokenType.ELLIPSIS) {
            rejectedSyntax(nameToken, "object-spread", "Object spread is not supported; supply explicit fields",
                    "Write the intended class/Record fields explicitly, preserving evaluation order and optional-field presence.");
            parseAssignment();
            return null;
        }
        ExpressionNode key = null;
        String name = nameToken.lexeme();
        if (nameToken.type() == TokenType.LBRACKET) {
            key = parseExpression();
            if (key == null) return null;
            expectCloser(TokenType.RBRACKET, DiagnosticCode.E1035, "Expected ']' after computed key", nameToken);
            name = null;
        } else if (nameToken.type() == TokenType.STRING_LITERAL) {
            name = unescapeString(name);
        }
        if (!match(TokenType.COLON)) {
            if (key == null && (peek().type() == TokenType.COMMA || peek().type() == TokenType.RBRACE)) {
                rejectedSyntax(nameToken, "shorthand-property", "Shorthand properties are not supported; write name: value",
                        "Supply the intended field value explicitly; field names and variable bindings are independent.");
                return null;
            }
            error(DiagnosticCode.E1008, "Expected ':' after property name", peek());
            return null;
        }

        ExpressionNode value = parseExpression();
        if (value == null) return null;

        Span sp = combineTokenAndSpan(nameToken, value.span());
        return new Property(sp, name, key, value);
    }

    // =======================================================================
    // Helper methods — token stream
    // =======================================================================

    /**
     * Returns the current token, or a past-end pseudo-EOF when the token
     * stream is exhausted. The pseudo-EOF carries the input list's
     * EOF-token position (line/column always; scalar offsets once the
     * lexer computes them, UNKNOWN when the list has no EOF token). A list
     * without an EOF token (defensive/test-only) falls back to the
     * historical (1,1) position with no offset information.
     */
    private Token peek() {
        if (pos >= tokens.size()) {
            if (inputEofToken != null) {
                return new Token(TokenType.EOF, "",
                    inputEofToken.line(), inputEofToken.column(), 0,
                    inputEofToken.startScalarOffset(), inputEofToken.scalarLength());
            }
            return new Token(TokenType.EOF, "", 1, 1, 0);
        }
        return tokens.get(pos);
    }

    private Token peek(int offset) {
        return pos + offset < tokens.size() ? tokens.get(pos + offset) : peek();
    }

    private Token advance() {
        Token t = peek();
        if (pos < tokens.size()) pos++;
        return t;
    }

    private Token previous() {
        return pos > 0 ? tokens.get(pos - 1) : tokens.get(0);
    }

    private Token previousOrCurrent() {
        if (pos == 0) return peek();
        return tokens.get(Math.min(pos, tokens.size() - 1));
    }

    private boolean match(TokenType type) {
        if (peek().type() == type) {
            advance();
            return true;
        }
        return false;
    }

    private boolean isAtEnd() {
        return peek().type() == TokenType.EOF;
    }

    private static boolean isIdentifier(TokenType type) {
        return type == TokenType.IDENTIFIER || type == TokenType.ASYNC
            || type == TokenType.AS || type == TokenType.FROM || type == TokenType.OF;
    }

    private Token expect(TokenType type, DiagnosticCode code, String message) {
        if (type == TokenType.GT && peek().type() == TokenType.GTE) {
            Token t = peek();
            // Promise<T>=> lexes as >= followed by >; recover the adjacent arrow.
            boolean arrow = pos + 1 < tokens.size()
                && tokens.get(pos + 1).type() == TokenType.GT
                && tokens.get(pos + 1).line() == t.line()
                && tokens.get(pos + 1).column() == t.column() + 2;
            if (arrow) {
                tokens.remove(pos + 1);
            }
            tokens.set(pos, new Token(TokenType.GT, ">", t.line(), t.column(), 1,
                t.startScalarOffset(), 1));
            tokens.add(pos + 1, new Token(arrow ? TokenType.ARROW : TokenType.EQ_SIGN,
                arrow ? "=>" : "=", t.line(), t.column() + 1, arrow ? 2 : 1,
                t.startScalarOffset() < 0 ? t.startScalarOffset() : t.startScalarOffset() + 1, arrow ? 2 : 1));
        }
        if (peek().type() == type || type == TokenType.IDENTIFIER && isIdentifier(peek().type())) {
            return advance();
        }
        expectedToken(code, message, type);
        return null;
    }

    private void lineTerminatorError(String message, String rule, Token left) {
        diagnostics.add(fromTokenAnchor(DiagnosticCode.E1007, "error", message, peek())
                .withFacts(new deal.diagnostics.DiagnosticFacts("syntax.line-terminator." + rule, "parse.line-terminator",
                        "same source line", "line terminator", List.of(
                                "Keep these grammar components on one source line, including through comments; do not replace or invent an operand."))
                        .withSyntax(syntaxContext("line-terminator." + rule, peek(), List.of())))
                .withNote("Same-line restriction starts here", left.range(file)));
    }

    private void resultNotationError(Token start, boolean async) {
        diagnostics.add(fromTokenAnchor(DiagnosticCode.E1011, "error",
                "Async implementations require Promise result notation; sync implementations forbid it", start)
                .withFacts(new deal.diagnostics.DiagnosticFacts("syntax.result-notation", "parse.result",
                        async ? "Promise<T> result notation" : "ordinary result notation",
                        async ? "ordinary result notation" : "Promise<T> result notation", List.of(
                                "Choose the intended sync or async implementation and make its marker, result notation and callers consistent; no automatic Promise or await rewrite is implied."))
                        .withSyntax(syntaxContext("result-notation", start, List.of()))));
    }

    private static deal.diagnostics.DiagnosticFacts.SyntaxContext syntaxContext(
            String context, Token found, List<String> expectedKinds) {
        String lexeme = found.lexeme();
        int end = Math.min(80, lexeme.length());
        if (end < lexeme.length() && end > 0 && Character.isHighSurrogate(lexeme.charAt(end - 1))
                && Character.isLowSurrogate(lexeme.charAt(end))) end--;
        return new deal.diagnostics.DiagnosticFacts.SyntaxContext(context, expectedKinds, found.type().name(),
                lexeme.substring(0, end), lexeme.length(), end < lexeme.length());
    }

    private void skipBalancedType(TokenType open, TokenType close) {
        int depth = 1;
        while (!isAtEnd() && depth > 0) {
            TokenType kind = advance().type();
            if (kind == open) depth++;
            else if (kind == close) depth--;
        }
    }

    private void skipAngleType() { skipBalancedType(TokenType.LT, TokenType.GT); }

    private void rejectGenericDeclaration(String declaration) {
        if (peek().type() != TokenType.LT) return;
        Token opener = advance();
        rejectedSyntax(opener, "generic-" + declaration, "Generic " + declaration + " declarations are not supported; use explicit concrete types",
                "Choose concrete parameter, result and field types, or distinct declarations; DEAL has no source-level type parameters.");
        int depth = 1;
        while (!isAtEnd() && depth > 0) {
            TokenType kind = peek().type();
            if (kind == TokenType.LBRACE || kind == TokenType.SEMICOLON) return;
            advance();
            if (kind == TokenType.LT) depth++;
            else if (kind == TokenType.GT) depth--;
        }
    }

    private void rejectJumpLabel() {
        if (!isIdentifier(peek().type()) || peek().line() != previous().line()) return;
        Token label = advance();
        rejectedSyntax(label, "labelled-jump", "Labelled jumps are not supported; break/continue target the nearest loop",
                "Restructure the loop control explicitly; removing a label can change the target.");
    }

    private void rejectFinally() {
        Token keyword = advance();
        rejectedSyntax(keyword, "finally", "Finally blocks are not supported; make cleanup control flow explicit",
                "Handle intended success and failure cleanup explicitly with try/catch; preserve returns and rethrows rather than deleting cleanup.");
        if (peek().type() == TokenType.LBRACE) parseBlock();
    }

    private void skipRejectedControlFlow(String feature) {
        int parentheses = 0;
        while (!isAtEnd() && peek().type() != TokenType.RBRACE) {
            TokenType kind = peek().type();
            if (parentheses == 0 && kind == TokenType.LBRACE) {
                int braces = 0;
                do {
                    TokenType nested = advance().type();
                    if (nested == TokenType.LBRACE) braces++;
                    else if (nested == TokenType.RBRACE) braces--;
                } while (!isAtEnd() && braces > 0);
                if (feature.equals("do") && match(TokenType.WHILE)) {
                    while (!isAtEnd() && peek().type() != TokenType.SEMICOLON && peek().type() != TokenType.RBRACE) advance();
                    match(TokenType.SEMICOLON);
                }
                return;
            }
            if (parentheses == 0 && kind == TokenType.SEMICOLON) { advance(); return; }
            if (kind == TokenType.LPAREN) parentheses++;
            else if (kind == TokenType.RPAREN && parentheses > 0) parentheses--;
            advance();
        }
    }

    private boolean rejectDestructuring(String position) {
        if (peek().type() != TokenType.LBRACKET && peek().type() != TokenType.LBRACE) return false;
        Token opener = peek();
        rejectedSyntax(opener, "destructuring-" + position,
                "Destructuring " + position + " bindings are not supported; bind one name and access its members",
                "Use a single binding, then explicit indexed or field reads; preserve evaluation order and handle null/undefined explicitly.");
        List<TokenType> closers = new ArrayList<>();
        do {
            TokenType kind = advance().type();
            if (kind == TokenType.LBRACKET) closers.add(TokenType.RBRACKET);
            else if (kind == TokenType.LBRACE) closers.add(TokenType.RBRACE);
            else if (kind == TokenType.LPAREN) closers.add(TokenType.RPAREN);
            else if (!closers.isEmpty() && kind == closers.getLast()) closers.removeLast();
            if (peek().type() == TokenType.SEMICOLON) break;
        } while (!isAtEnd() && !closers.isEmpty());
        return true;
    }

    private boolean isRejectedBinaryOperator() {
        Token op = peek();
        if (op.type() == TokenType.REJECTED_OPERATOR || op.type() == TokenType.AMPERSAND || op.type() == TokenType.PIPE) return true;
        return (op.type() == TokenType.LT || op.type() == TokenType.GT) && pos + 1 < tokens.size()
                && tokens.get(pos + 1).type() == op.type()
                && op.range(file).endScalarOffset() == tokens.get(pos + 1).range(file).startScalarOffset();
    }

    private void rejectedSyntax(Token token, String feature, String message, String guidance) {
        subsetError(DiagnosticCode.E1042, message, token, feature, "supported DEAL syntax", guidance);
        int last = diagnostics.size() - 1;
        diagnostics.set(last, diagnostics.get(last).withFacts(diagnostics.get(last).facts()
                .withLanguageDifference(deal.diagnostics.LanguageDifference.syntax(feature))));
    }

    private void subsetError(DiagnosticCode code, String message, Token token, String rule,
                             String expected, String guidance) {
        diagnostics.add(fromTokenAnchor(code, "error", message, token).withFacts(
                new deal.diagnostics.DiagnosticFacts("syntax.subset." + rule, "parse.subset",
                        expected, token.type().name(), List.of(guidance))
                        .withSyntax(syntaxContext("subset." + rule, token, List.of()))));
    }

    private void functionExpressionStatementError() {
        subsetError(DiagnosticCode.E1042, "Function expression statements require parentheses", peek(),
                "function-expression-statement", "parenthesized function expression statement",
                "Parenthesize an intended function expression statement; a declaration is a different form.");
    }

    private void restParameterError(String message) {
        subsetError(DiagnosticCode.E1047, message, peek(), "rest-parameter", "fixed annotated parameters",
                "DEAL has no rest parameter syntax; choose the intended fixed signature or an explicitly typed collection parameter and update callers consistently.");
    }

    private void expectedToken(DiagnosticCode code, String message, TokenType expected) {
        diagnostics.add(fromTokenAnchor(code, "error", message, peek()).withFacts(
                new deal.diagnostics.DiagnosticFacts("syntax.expected-token", "parse.token",
                        expected.name(), peek().type().name(), List.of())
                        .withSyntax(syntaxContext("token", peek(), expected == TokenType.IDENTIFIER
                                ? List.of("IDENTIFIER", "ASYNC", "AS", "FROM", "OF") : List.of(expected.name())))));
    }

    private Token expectCloser(TokenType kind, DiagnosticCode code, String message, Token opener) {
        if (peek().type() == kind) return advance();
        expectedCloser(code, message, kind, opener);
        return null;
    }

    private void expectedCloser(DiagnosticCode code, String message, TokenType kind, Token opener) {
        diagnostics.add(fromTokenAnchor(code, "error", message, peek())
                .withFacts(new deal.diagnostics.DiagnosticFacts("syntax.unclosed-delimiter", "parse.delimiter",
                        kind.name(), peek().type().name(), List.of("Close the delimiter opened at the related location."))
                        .withSyntax(syntaxContext("delimiter", peek(), List.of(kind.name()))))
                .withNote("Unclosed delimiter opened here", opener.range(file)));
    }

    private void optionalSemicolon() {
        match(TokenType.SEMICOLON);
    }

    /**
     * The v1.4 statement-terminator requirement: every production whose
     * grammar carries a {@code ';'} must consume it. Reports E1053 at the
     * next token when the statement ended without one; suppressed when the
     * statement already reported a diagnostic so recovery does not cascade.
     */
    private void checkTerminator(int diagCountBefore) {
        if (diagnostics.size() > diagCountBefore) return;
        if (pos > 0 && tokens.get(pos - 1).type() == TokenType.SEMICOLON) return;
        diagnostics.add(fromTokenAnchor(DiagnosticCode.E1053, "error", missingTerminatorMessage(), peek())
                .withFacts(new deal.diagnostics.DiagnosticFacts("syntax.missing-terminator", "parse.terminator",
                        TokenType.SEMICOLON.name(), peek().type().name(),
                        List.of("Terminate the preceding statement with ';' at this boundary."))
                        .withSyntax(syntaxContext("terminator", peek(), List.of("SEMICOLON"))
                            .withInsertion(isCertainTerminatorBoundary() && peek().range(file).origin() == deal.diagnostics.RangeOrigin.SOURCE
                                ? new deal.diagnostics.DiagnosticFacts.SyntaxInsertion(peek().range(file).startScalarOffset(), ";")
                                : null))));
    }

    /** Insertion is safe only at an independently recognizable statement boundary. */
    private boolean isCertainTerminatorBoundary() {
        return switch (peek().type()) {
            case EOF, RBRACE, LET, CONST, CLASS, FUNCTION, RETURN, IF, WHILE, FOR,
                    BREAK, CONTINUE, IMPORT, EXPORT, DELETE, TRY, THROW -> true;
            case ASYNC -> pos + 1 < tokens.size() && tokens.get(pos + 1).type() == TokenType.FUNCTION;
            default -> false;
        };
    }

    /** The E1053 message for the current (missing-terminator) position. */
    private String missingTerminatorMessage() {
        Token next = peek();
        if (next.type() == TokenType.EOF) {
            return "Missing ';' at end of input";
        }
        return "Missing ';' before '" + next.lexeme() + "'";
    }

    /**
     * Statement kinds whose grammar carries an optional trailing
     * {@code ;} — the only statements the v1.2 semicolon-boundary
     * whitelist governs. Block-bodied statements (blocks, if/while/for,
     * try, class/function declarations) end with {@code }} and need no
     * terminator.
     */
    private static boolean requiresTerminator(StatementNode stmt) {
        return switch (stmt) {
            case VariableDeclaration ignored -> true;
            case ReturnStatement ignored -> true;
            case BreakStatement ignored -> true;
            case ContinueStatement ignored -> true;
            case ExpressionStatement ignored -> true;
            case DeleteStatement ignored -> true;
            case ThrowStatement ignored -> true;
            case ImportDeclaration ignored -> true;
            default -> false;
        };
    }

    /**
     * Enforces the v1.4 statement terminator for the statement that just
     * ended (Syntactic grammar: every production carrying {@code ';'}
     * consumes one). Block-ending productions (blocks, if/while/for/try,
     * function and class declarations) end at {@code '}'} and carry no
     * added requirement. Suppressed when the statement itself already
     * reported a diagnostic, so recovery does not cascade.
     */
    private void checkStatementTerminator(StatementNode stmt, int diagCountBefore) {
        if (!requiresTerminator(stmt)) return;
        checkTerminator(diagCountBefore);
    }

    // =======================================================================
    // Helper methods — spans
    // =======================================================================

    /**
     * Inclusive end column of a token on its line (tokens never span line
     * breaks). Uses the token's scalar length when known — columns count
     * UTF-16 code units — and falls back to the token length for
     * column display only when no offset information is available.
     */
    private int tokenEndColumn(Token token) {
        int len = token.hasScalarOffsets() ? token.scalarLength() : token.length();
        return token.column() + Math.max(0, len - 1);
    }

    /**
     * Span of a token: start position and scalar offset from the token,
     * end offset {@code startScalarOffset + scalarLength} when known.
     * UNKNOWN inputs propagate UNKNOWN.
     */
    private Span spanOf(Token token) {
        int endCol = tokenEndColumn(token);
        return new Span(file, token.line(), token.column(), token.line(), endCol,
            token.startScalarOffset(), token.endScalarOffset());
    }

    /**
     * Span from the first token's start to the last token's end. UNKNOWN
     * inputs propagate UNKNOWN.
     */
    private Span spanBetween(Token start, Token end) {
        int endCol = tokenEndColumn(end);
        return new Span(file, start.line(), start.column(), end.line(), endCol,
            start.startScalarOffset(), end.endScalarOffset());
    }

    /**
     * Span from the first span's start to the last span's end. UNKNOWN
     * inputs propagate UNKNOWN.
     */
    private Span spanBetween(Span start, Span end) {
        return new Span(file, start.startLine(), start.startColumn(),
                end.endLine(), end.endColumn(),
                start.startScalarOffset(), end.endScalarOffset());
    }

    private Token tokenSpanStart(Span sp) {
        return new Token(TokenType.IDENTIFIER, "", sp.startLine(), sp.startColumn(), 1,
            sp.startScalarOffset(), 0);
    }

    private Token tokenSpanStart(TypeNode type) {
        Span sp = type.span();
        return new Token(TokenType.IDENTIFIER, "", sp.startLine(), sp.startColumn(), 1,
            sp.startScalarOffset(), 0);
    }

    /**
     * Combines a start Token and end Span into a new Span: the token's
     * start offset and the span's end offset. UNKNOWN inputs propagate
     * UNKNOWN.
     */
    private Span combineTokenAndSpan(Token start, Span end) {
        return new Span(file, start.line(), start.column(),
                end.endLine(), end.endColumn(),
                start.startScalarOffset(), end.endScalarOffset());
    }

    // =======================================================================
    // Helper methods — errors
    // =======================================================================

    private void error(DiagnosticCode code, String message, Token token) {
        diagnostics.add(fromTokenAnchor(code, "error", message, token));
    }

    private void error(DiagnosticCode code, String message, ExpressionNode node) {
        diagnostics.add(CompilerDiagnostic.error(code, message, node.span()));
    }

    private void error(DiagnosticCode code, String message, Span span) {
        diagnostics.add(CompilerDiagnostic.error(code, message, span));
    }

    private void error(DiagnosticCode code, String message, DiagnosticRange range) {
        diagnostics.add(CompilerDiagnostic.error(code, message, range));
    }

    // =======================================================================
    // Helper methods — warnings
    // =======================================================================

    public void warn(DiagnosticCode code, String message, Token token) {
        diagnostics.add(fromTokenAnchor(code, "warning", message, token));
    }

    public void warn(DiagnosticCode code, String message, ExpressionNode node) {
        diagnostics.add(CompilerDiagnostic.warning(code, message, node.span()));
    }

    /**
     * Builds a ranged diagnostic anchored at a token's full half-open
     * range in this parser's file. Real lexer tokens carry computed
     * scalar offsets, so the result is a SOURCE range with exact offsets
     * (D4). Tokens without offset information — the template sub-parser's
     * adjusted tokens and defensive/test-only inputs — convert to the
     * canonical SYNTHETIC shape with the mandatory D4 anchor note naming
     * the anchor's file position; no offset-less token can ever yield a
     * SOURCE range.
     */
    private CompilerDiagnostic fromTokenAnchor(DiagnosticCode code, String severity,
                                               String message, Token token) {
        DiagnosticRange range = token.range(file);
        List<DiagnosticNote> notes = range.origin() == RangeOrigin.SYNTHETIC
            ? List.of(new DiagnosticNote(
                "missing anchor: " + file + ":" + token.line() + ":" + token.column(),
                null))
            : null;
        return new CompilerDiagnostic(code.code(), severity, message, range,
            notes, code);
    }

    // =======================================================================
    // Helper methods — error recovery
    // =======================================================================

    private void expressionRequired(Token token) {
        diagnostics.add(fromTokenAnchor(DiagnosticCode.E1037, "error", "Expected expression", token)
                .withFacts(new deal.diagnostics.DiagnosticFacts("syntax.expression-required", "parse.expression",
                        "DEAL expression", token.type().name(),
                        List.of("Provide the intended expression; no value or execution path is inferred from recovery."))
                        .withSyntax(syntaxContext("expression", token, List.of()))));
    }

    private ExpressionNode parseListElement() {
        if (peek().type() == TokenType.ELLIPSIS) {
            Token spread = advance();
            rejectedSyntax(spread, "spread", "Spread is not supported; supply explicit arguments or elements",
                    "Pass the declared arguments explicitly; for arrays choose supported copy/concat operations with the intended element type.");
            return parseAssignment();
        }
        if (peek().type() == TokenType.COMMA) {
            expressionRequired(peek());
            return null;
        }
        return parseExpression();
    }

    /** Resume only at this expression list's separator/closer, not nested list separators. */
    private void recoverExpressionList(TokenType closer) {
        int parentheses = 0;
        int brackets = 0;
        int braces = 0;
        while (!isAtEnd()) {
            TokenType kind = peek().type();
            if (parentheses == 0 && brackets == 0 && braces == 0
                    && (kind == TokenType.COMMA || kind == closer || isStatementBoundary(kind))) return;
            if (kind == TokenType.LPAREN) parentheses++;
            else if (kind == TokenType.RPAREN) { if (parentheses == 0) return; parentheses--; }
            else if (kind == TokenType.LBRACKET) brackets++;
            else if (kind == TokenType.RBRACKET) { if (brackets == 0) return; brackets--; }
            else if (kind == TokenType.LBRACE) braces++;
            else if (kind == TokenType.RBRACE) { if (braces == 0) return; braces--; }
            advance();
        }
    }

    /** Skip one rejected statement without exposing nested braces as enclosing-block closers. */
    private void recoverUnsupportedStatement() {
        int parentheses = 0, brackets = 0, braces = 0;
        while (!isAtEnd()) {
            TokenType kind = peek().type();
            if (parentheses == 0 && brackets == 0 && braces == 0) {
                if (kind == TokenType.SEMICOLON) { advance(); return; }
                if (isStatementBoundary(kind) && kind != TokenType.LBRACE) return;
            }
            if (kind == TokenType.LPAREN) parentheses++;
            else if (kind == TokenType.LBRACKET) brackets++;
            else if (kind == TokenType.LBRACE) braces++;
            else if (kind == TokenType.RPAREN) { if (parentheses == 0) return; parentheses--; }
            else if (kind == TokenType.RBRACKET) { if (brackets == 0) return; brackets--; }
            else if (kind == TokenType.RBRACE) { if (braces == 0) return; braces--; }
            advance();
        }
    }

    private void recoverUnsupportedForHeader() {
        int depth = 0;
        while (!isAtEnd()) {
            TokenType kind = peek().type();
            if (kind == TokenType.RBRACE || kind == TokenType.LBRACE && depth == 0) return;
            advance();
            if (kind == TokenType.LPAREN) depth++;
            else if (kind == TokenType.RPAREN) { if (depth == 0) return; depth--; }
        }
    }

    private void synchronize() {
        while (!isAtEnd()) {
            if (isStatementBoundary(peek().type())) {
                if (peek().type() == TokenType.SEMICOLON) advance();
                return;
            }
            advance();
        }
    }

    private void synchronizeTo(TokenType... targets) {
        while (!isAtEnd()) {
            TokenType type = peek().type();
            for (TokenType t : targets) {
                if (type == t) return;
            }
            if (isStatementBoundary(type)) return;
            advance();
        }
    }

    private boolean isStatementBoundary(TokenType type) {
        return switch (type) {
            case RBRACE, LET, CONST, CLASS, FUNCTION, ASYNC, RETURN, IF, WHILE, FOR,
                 BREAK, CONTINUE, IMPORT, EXPORT, DELETE, TRY, THROW,
                 SEMICOLON, EOF -> true;
            default -> false;
        };
    }

    private boolean canStartExpression(TokenType type) {
        return switch (type) {
            case NULL, TRUE, FALSE, NUMBER_LITERAL, STRING_LITERAL, TEMPLATE_LITERAL,
                 IDENTIFIER, BANG, MINUS, LPAREN, LBRACKET, LBRACE,
                 FUNCTION, AWAIT, ASYNC, AS, FROM, OF, TYPEOF -> true;
            default -> false;
        };
    }

    // =======================================================================
    // Helper methods — precedence
    // =======================================================================

    private int getBinaryPrecedence(TokenType type) {
        return switch (type) {
            case OR         -> PREC_OR;
            case AND        -> PREC_AND;
            case EQ_STRICT, NEQ_STRICT, EQ, NEQ -> PREC_EQUALITY;
            case LT, LTE, GT, GTE, AS, INSTANCEOF -> PREC_RELATIONAL;
            case PLUS, MINUS -> PREC_ADDITIVE;
            case STAR, SLASH, PERCENT -> PREC_MULTIPLICATIVE;
            default         -> PREC_NONE;
        };
    }

    private BinaryOp tokenToBinaryOp(TokenType type) {
        return switch (type) {
            case PLUS        -> BinaryOp.ADD;
            case MINUS       -> BinaryOp.SUB;
            case STAR        -> BinaryOp.MUL;
            case SLASH       -> BinaryOp.DIV;
            case PERCENT     -> BinaryOp.REM;

            case EQ_STRICT   -> BinaryOp.EQ;
            case NEQ_STRICT  -> BinaryOp.NEQ;
            case LT          -> BinaryOp.LT;
            case LTE         -> BinaryOp.LTE;
            case GT          -> BinaryOp.GT;
            case GTE         -> BinaryOp.GTE;
            case AND         -> BinaryOp.AND;
            case OR          -> BinaryOp.OR;
            default -> throw new IllegalStateException(
                    "Unexpected binary operator token: " + type);
        };
    }

    private boolean isValidAssignmentTarget(ExpressionNode expr) {
        return expr instanceof IdentifierExpr
                || expr instanceof MemberAccessExpr
                || expr instanceof IndexExpr;
    }

    // =======================================================================
    // Helper methods — misc
    // =======================================================================

    private Block emptyBlock() {
        return new Block(new Span(file, 1, 1, 1, 1), List.of());
    }

    private static String unescapeString(String lexeme) {
        return TemplateScanner.decodeText(lexeme.substring(1, lexeme.length() - 1));
    }
}
