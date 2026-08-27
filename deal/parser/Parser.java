package deal.parser;

import deal.ast.*;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticNote;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.lexer.Token;
import deal.source.ScalarSourceCursor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import deal.diagnostics.DiagnosticCode;

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
    private static final int PREC_EXPONENT      = 7;

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

    public Parser(List<Token> tokens, String file) {
        this.tokens = List.copyOf(tokens);
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
        validateFileVersionDirectives();
        List<StatementNode> statements = new ArrayList<>();

        while (!isAtEnd()) {
            StatementNode stmt = parseStatement();
            if (stmt != null) {
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
    // File directive validation
    // =======================================================================

    /**
     * Validates the {@code @deal-version} file directive VALUE across the
     * token stream.  The lexer already enforces the directive shape
     * (single argument, placement before the first non-comment token, at
     * most once); this pass enforces version compatibility.
     *
     * <p>DEAL v1.2 is not source-compatible with earlier language
     * versions, and minor-version migrations may only be performed by
     * explicit compiler migration rules (spec-v1.2: Declaration metadata
     * versioning).  This compiler implements exactly DEAL v1.2, so every
     * declared version other than {@code 1.2} is a compile-time error
     * (E1055) — this includes older versions ({@code 1.0}, {@code 1.1})
     * and newer major versions ({@code 2.0}+).</p>
     */
    private void validateFileVersionDirectives() {
        for (Token token : tokens) {
            for (String directive : token.directives()) {
                if (!directive.equals("@deal-version")
                        && !directive.startsWith("@deal-version ")) {
                    continue;
                }
                String value = directive.equals("@deal-version")
                    ? "" : directive.substring("@deal-version ".length()).trim();
                if (value.isEmpty()) {
                    // Shape error already reported by the lexer.
                    continue;
                }
                if (!"1.2".equals(value)) {
                    error(DiagnosticCode.E1055,
                        "Unsupported DEAL version '" + value
                            + "': DEAL v1.2 is not source-compatible with"
                            + " earlier language versions and this compiler"
                            + " supports only '1.2'",
                        token);
                }
            }
        }
    }

    // =======================================================================
    // Statement parsing
    // =======================================================================

    private StatementNode parseStatement() {
        TokenType type = peek().type();

        // General guard: @jsonable directive is only valid on export class (D2)
        if (type != TokenType.EXPORT && peek().directives().contains("@jsonable")) {
            warn(DiagnosticCode.E1043, "@jsonable directive is only valid on 'export class', ignoring", peek());
        }
        return switch (type) {
            case LBRACE    -> parseBlock();
            case CLASS     -> parseClassDeclaration();
            case FUNCTION  -> {
                if (pos + 1 < tokens.size()
                        && tokens.get(pos + 1).type() == TokenType.IDENTIFIER
                        && pos + 2 < tokens.size()
                        && tokens.get(pos + 2).type() == TokenType.LPAREN) {
                    yield parseFunctionDeclaration();
                }
                yield parseExpressionStatement();
            }
            case ASYNC  -> {
                if (pos + 1 < tokens.size()
                        && tokens.get(pos + 1).type() == TokenType.FUNCTION
                        && pos + 2 < tokens.size()
                        && tokens.get(pos + 2).type() == TokenType.IDENTIFIER
                        && pos + 3 < tokens.size()
                        && tokens.get(pos + 3).type() == TokenType.LPAREN) {
                    yield parseFunctionDeclaration();
                }
                yield parseExpressionStatement();
            }
            case LET       -> parseVariableDeclaration();
            case RETURN    -> parseReturnStatement();
            case IF        -> parseIfStatement();
            case WHILE     -> parseWhileStatement();
            case FOR       -> parseForStatement();
            case BREAK     -> parseBreakStatement();
            case CONTINUE  -> parseContinueStatement();
            case IMPORT    -> parseImportDeclaration();
            case EXPORT    -> parseExportDeclaration();
            case DELETE    -> parseDeleteStatement();
            case TRY       -> parseTryStatement();
            case THROW     -> parseThrowStatement();
            case SEMICOLON -> { advance(); yield null; }
            case EOF       -> null;
            case RBRACE    -> { advance(); error(DiagnosticCode.E1041, "Unexpected '}'", previous()); yield null; }
            default        -> parseExpressionStatement();
        };
    }

    // -- Block --
    private Block parseBlock() {
        Token lbrace = expect(TokenType.LBRACE, DiagnosticCode.E1005, "Expected '{'");
        if (lbrace == null) { synchronize(); return emptyBlock(); }

        List<StatementNode> statements = new ArrayList<>();
        while (!isAtEnd() && peek().type() != TokenType.RBRACE) {
            StatementNode stmt = parseStatement();
            if (stmt != null) statements.add(stmt);
        }

        Token rbrace;
        if (match(TokenType.RBRACE)) {
            rbrace = previous();
        } else {
            error(DiagnosticCode.E1006, "Expected '}'", peek());
            rbrace = previousOrCurrent();
        }
        return new Block(spanBetween(lbrace, rbrace), List.copyOf(statements));
    }

    // -- ClassDeclaration --
    private StatementNode parseClassDeclaration() {
        Token classToken = advance();
        Token nameToken = expect(TokenType.IDENTIFIER, DiagnosticCode.E1007, "Expected class name after 'class'");
        if (nameToken == null) { synchronize(); return null; }

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
            error(DiagnosticCode.E1006, "Expected '}' at end of class body", peek());
            rbrace = previousOrCurrent();
        }
        return new ClassDeclaration(spanBetween(classToken, rbrace),
                nameToken.lexeme(), List.copyOf(fields));
    }

    private ClassField parseClassField() {
        if (peek().type() != TokenType.IDENTIFIER) {
            error(DiagnosticCode.E1007, "Expected field name (identifier)", peek());
            advance();
            return null;
        }
        Token nameToken = advance();
        boolean optional = match(TokenType.QUESTION);

        if (!match(TokenType.COLON)) {
            error(DiagnosticCode.E1008, "Expected ':' after field name", peek());
            return null;
        }
        TypeNode type = parseType();
        if (type == null) return null;

        boolean nullable = type instanceof NullableType;

        Optional<ExpressionNode> defaultExpr = Optional.empty();
        if (match(TokenType.EQ_SIGN)) {
            ExpressionNode expr = parseExpression();
            if (expr != null) defaultExpr = Optional.of(expr);
        }
        optionalSemicolon();

        Span sp = spanBetween(nameToken, previousOrCurrent());
        return new ClassField(sp, nameToken.lexeme(), optional, nullable, type, defaultExpr);
    }

    // -- FunctionDeclaration --
    private StatementNode parseFunctionDeclaration() {
        boolean isAsync = match(TokenType.ASYNC);
        Token startToken = isAsync ? previous() : peek();
        Token funcToken = advance();  // consumes FUNCTION
        Token nameToken = expect(TokenType.IDENTIFIER, DiagnosticCode.E1007, "Expected function name after 'function'");
        if (nameToken == null) { synchronize(); return null; }

        expect(TokenType.LPAREN, DiagnosticCode.E1009, "Expected '(' after function name");
        var paramResult = parseParameterList();
        expect(TokenType.RPAREN, DiagnosticCode.E1010, "Expected ')' after function parameters");

        if (!match(TokenType.COLON)) {
            error(DiagnosticCode.E1011, "Expected ':' return type annotation", peek());
            synchronize(); return null;
        }
        TypeNode returnType = parseType();
        if (returnType == null) { synchronize(); return null; }

        Block body;
        boolean isExternal = false;
        if (peek().type() == TokenType.LBRACE) {
            body = parseBlock();
        } else if (match(TokenType.SEMICOLON)) {
            // External function declaration (declaration files only).
            body = new Block(spanOf(previous()), List.of());
            isExternal = true;
        } else {
            error(DiagnosticCode.E1012, "Expected '{' for function body", peek());
            body = emptyBlock();
            synchronize();
        }

        Span sp = spanBetween(startToken, previousOrCurrent());
        return new FunctionDeclaration(sp, nameToken.lexeme(),
                paramResult.params, returnType, body, isAsync, isExternal);
    }

    // -- VariableDeclaration --
    private StatementNode parseVariableDeclaration() {
        Token letToken = advance();
        Token nameToken = expect(TokenType.IDENTIFIER, DiagnosticCode.E1007, "Expected variable name after 'let'");
        if (nameToken == null) { synchronize(); return null; }

        Optional<TypeNode> typeAnnotation = Optional.empty();
        if (match(TokenType.COLON)) {
            TypeNode type = parseType();
            if (type != null) typeAnnotation = Optional.of(type);
        }

        if (!match(TokenType.EQ_SIGN)) {
            error(DiagnosticCode.E1013, "Expected '=' initializer in variable declaration", peek());
            synchronize(); return null;
        }

        ExpressionNode init = parseExpression();
        if (init == null) { synchronize(); return null; }

        optionalSemicolon();

        Span sp = spanBetween(letToken, previousOrCurrent());
        return new VariableDeclaration(sp, nameToken.lexeme(), typeAnnotation, init);
    }

    // -- ReturnStatement --
    private StatementNode parseReturnStatement() {
        Token retToken = advance();

        Optional<ExpressionNode> expr = Optional.empty();
        if (!isAtEnd() && canStartExpression(peek().type())) {
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

    // -- ForStatement --
    private StatementNode parseForStatement() {
        Token forToken = advance();
        expect(TokenType.LPAREN, DiagnosticCode.E1014, "Expected '(' after 'for'");

        // --- Init ---
        Optional<ForInit> init = Optional.empty();
        if (peek().type() == TokenType.LET) {
            Token letToken = advance();
            Token nameToken = expect(TokenType.IDENTIFIER, DiagnosticCode.E1007,
                    "Expected loop variable name after 'let'");
            if (nameToken == null) { synchronize(); return null; }

            Optional<TypeNode> typeAnnotation = Optional.empty();
            if (match(TokenType.COLON)) {
                typeAnnotation = Optional.ofNullable(parseType());
            }

            // D6: For-of detection — after optional type annotation
            if (typeAnnotation.isPresent() && match(TokenType.OF)) {
                ExpressionNode iterable = parseExpression();
                // D20: Null guard — matches established parser pattern
                if (iterable == null) { synchronize(); return null; }
                expect(TokenType.RPAREN, DiagnosticCode.E1015, "Expected ')' after for-of iterable");
                Block body = parseBlock();
                Span sp = spanBetween(forToken, previousOrCurrent());
                return new ForOfStatement(sp, nameToken.lexeme(),
                    typeAnnotation.get(), iterable, body);
            }

            if (!match(TokenType.EQ_SIGN)) {
                error(DiagnosticCode.E1013, "Expected '=' initializer in for-loop variable", peek());
                synchronize(); return null;
            }

            ExpressionNode initExpr = parseExpression();
            if (initExpr == null) { synchronize(); return null; }

            Span varSpan = spanBetween(letToken, previousOrCurrent());
            VariableDeclaration varDecl = new VariableDeclaration(varSpan,
                    nameToken.lexeme(), typeAnnotation, initExpr);
            init = Optional.of(new ForInit.VarDecl(varDecl));

        } else if (peek().type() != TokenType.SEMICOLON) {
            ExpressionNode expr = parseExpression();
            if (expr instanceof AssignmentExpr assign) {
                init = Optional.of(new ForInit.AssignExpr(assign));
            } else if (expr != null) {
                error(DiagnosticCode.E1017,
                    "For-loop initializer must be a variable declaration or assignment",
                    expr);
                // Wrap in a synthetic assignment for recovery
                init = Optional.of(new ForInit.AssignExpr(
                        new AssignmentExpr(expr.span(), expr,
                                new LiteralExpr(expr.span(),
                                        new LiteralValue.NullLiteral()))));
            }
        }

        expect(TokenType.SEMICOLON, DiagnosticCode.E1018, "Expected ';' after for-loop initializer");

        // --- Condition ---
        Optional<ExpressionNode> condition = Optional.empty();
        if (peek().type() != TokenType.SEMICOLON) {
            ExpressionNode cond = parseExpression();
            if (cond != null) condition = Optional.of(cond);
        }
        expect(TokenType.SEMICOLON, DiagnosticCode.E1018, "Expected ';' after for-loop condition");

        // --- Update ---
        Optional<ExpressionNode> update = Optional.empty();
        if (peek().type() != TokenType.RPAREN) {
            ExpressionNode upd = parseExpression();
            if (upd != null) update = Optional.of(upd);
        }
        expect(TokenType.RPAREN, DiagnosticCode.E1015, "Expected ')' after for-loop update");

        Block body = parseBlock();

        Span sp = spanBetween(forToken, previousOrCurrent());
        return new ForStatement(sp, init, condition, update, body);
    }

    // -- Break / Continue --
    private StatementNode parseBreakStatement() {
        Token t = advance();
        optionalSemicolon();
        return new BreakStatement(spanOf(t));
    }

    private StatementNode parseContinueStatement() {
        Token t = advance();
        optionalSemicolon();
        return new ContinueStatement(spanOf(t));
    }

    // -- Import --
    private StatementNode parseImportDeclaration() {
        Token importToken = advance();

        if (!match(TokenType.STAR)) {
            error(DiagnosticCode.E1019, "Expected '*' after 'import' (only namespace imports supported)", peek());
            synchronize(); return null;
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

        String raw = pathToken.lexeme();
        String modulePath = raw.substring(1, raw.length() - 1);

        optionalSemicolon();
        Span sp = spanBetween(importToken, previousOrCurrent());
        // ISSUE-0252 @extern-c marker surface (js-backend-emitter D8): the
        // import token carries the lexer-attached compiler directives
        // (@extern-c among them) and the AST record propagates them — the
        // @jsonable -> ClassDeclaration.isJsonable() export-path precedent
        // (parseExportDeclaration). The checker reads only alias()/
        // modulePath(), so the new component is checker-inert; placement
        // and C-FFI-metadata validation stay with the C-FFI frontend epic.
        return new ImportDeclaration(sp, aliasToken.lexeme(), modulePath,
            importToken.directives());
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

        // @jsonable directive handling (D2)
        if (exportToken.directives().contains("@jsonable")) {
            if (declaration instanceof ClassDeclaration cd) {
                declaration = new ClassDeclaration(cd.span(), cd.name(), cd.fields(), true);
            } else {
                warn(DiagnosticCode.E1043, "@jsonable directive is only valid on 'export class', ignoring", exportToken);
            }
        }

        Span sp = spanBetween(exportToken, previousOrCurrent());
        return new ExportDeclaration(sp, declaration);
    }

    // -- Delete --
    private StatementNode parseDeleteStatement() {
        Token deleteToken = advance();

        ExpressionNode target = parseExpression();
        if (target == null) { synchronize(); return null; }

        if (!(target instanceof MemberAccessExpr) && !(target instanceof IndexExpr)) {
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

        if (!match(TokenType.CATCH)) {
            error(DiagnosticCode.E1026, "Expected 'catch' after try block", peek());
            synchronize(); return null;
        }

        expect(TokenType.LPAREN, DiagnosticCode.E1027, "Expected '(' after 'catch'");
        Token catchVarToken = expect(TokenType.IDENTIFIER, DiagnosticCode.E1028,
                "Expected catch variable name");
        if (catchVarToken == null) { synchronize(); return null; }
        expect(TokenType.RPAREN, DiagnosticCode.E1015, "Expected ')' after catch variable");

        Block catchBlock = parseBlock();

        Span sp = spanBetween(tryToken, previousOrCurrent());
        return new TryStatement(sp, tryBlock, catchVarToken.lexeme(), catchBlock);
    }

    // -- Throw --
    private StatementNode parseThrowStatement() {
        Token throwToken = advance();

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
        // Try function type first: '(' params ')' '=>' Type   or   async '(' params ')' '=>' Type
        if (peek().type() == TokenType.LPAREN || peek().type() == TokenType.ASYNC) {
            TypeNode funcType = tryParseFunctionType();
            if (funcType != null) return funcType;
        }
        return parseNullableType();
    }

    private TypeNode tryParseFunctionType() {
        int savedPos = pos;
        int diagSize = diagnostics.size();

        boolean isAsync = match(TokenType.ASYNC);
        Token lparen = advance();  // consumes LPAREN (or whatever if not a function type)

        List<FunctionTypeParam> params = new ArrayList<>();

        if (peek().type() != TokenType.RPAREN) {
            if (peek().type() == TokenType.ELLIPSIS) {
                // DEAL v1.2: function types have no rest arm.
                error(DiagnosticCode.E1047,
                    "Rest parameters are not supported in DEAL v1.2 (function types carry no rest arm)",
                    peek());
                skipRestArmTokens();
            } else {
                FunctionTypeParam first = parseFunctionTypeParam();
                if (first != null) params.add(first);

                while (match(TokenType.COMMA) && peek().type() != TokenType.RPAREN) {
                    if (peek().type() == TokenType.ELLIPSIS) {
                        error(DiagnosticCode.E1047,
                            "Rest parameters are not supported in DEAL v1.2 (function types carry no rest arm)",
                            peek());
                        skipRestArmTokens();
                        break;
                    }
                    FunctionTypeParam param = parseFunctionTypeParam();
                    if (param != null) params.add(param);
                    else break;
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

        TypeNode returnType = parseType();
        if (returnType == null) {
            returnType = new NamedType(spanOf(previousOrCurrent()), "null");
        }

        return new FunctionType(spanBetween(lparen, previousOrCurrent()),
                List.copyOf(params), returnType, isAsync);
    }

    private FunctionTypeParam parseFunctionTypeParam() {
        Token nameToken = expect(TokenType.IDENTIFIER, DiagnosticCode.E1029,
                "Expected parameter name in function type");
        if (nameToken == null) return null;

        if (!match(TokenType.COLON)) {
            error(DiagnosticCode.E1008, "Expected ':' after parameter name in function type", peek());
            return null;
        }
        TypeNode type = parseType();
        if (type == null) return null;

        return new FunctionTypeParam(spanBetween(nameToken, previousOrCurrent()),
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
        // "null |" prefix — can be chained: null | null | T
        if (match(TokenType.NULL)) {
            Token nullToken = previous();
            if (match(TokenType.PIPE)) {
                TypeNode inner = parseNullableType();
                if (inner == null) return null;
                return new NullableType(spanBetween(nullToken, previousOrCurrent()), inner);
            }
            // Just "null" — return as NamedType
            return new NamedType(spanOf(nullToken), "null");
        }

        TypeNode inner = parseArrayType();
        if (inner == null) return null;

        // "| null" suffix — can be chained: T | null | null
        while (match(TokenType.PIPE)) {
            if (match(TokenType.NULL)) {
                inner = new NullableType(
                        spanBetween(tokenSpanStart(inner), previousOrCurrent()),
                        inner);
            } else {
                error(DiagnosticCode.E1030, "Expected 'null' after '|' in nullable type", peek());
                break;
            }
        }

        return inner;
    }

    private TypeNode parseArrayType() {
        TypeNode type = parsePrimaryType();
        if (type == null) return null;

        while (match(TokenType.LBRACKET)) {
            if (!match(TokenType.RBRACKET)) {
                error(DiagnosticCode.E1031, "Expected ']' in array type", peek());
            }
            type = new ArrayType(spanBetween(tokenSpanStart(type), previousOrCurrent()), type);
        }
        return type;
    }

    private TypeNode parsePrimaryType() {
        TokenType tt = peek().type();

        // 'null' keyword
        if (tt == TokenType.NULL) {
            Token t = advance();
            return new NamedType(spanOf(t), "null");
        }

        // Builtin type names (identifiers): boolean, int, number, string, table, coroutine, Error
        // Also the start of qualified types like ModuleAlias.ClassName
        if (tt == TokenType.IDENTIFIER) {
            Token first = advance();

            // Check for qualified type: A.B or A.B.C etc.
            if (peek().type() == TokenType.DOT) {
                java.util.List<Token> nameTokens = new java.util.ArrayList<>();
                nameTokens.add(first);

                while (peek().type() == TokenType.DOT) {
                    advance(); // DOT
                    if (peek().type() != TokenType.IDENTIFIER) {
                        error(DiagnosticCode.E1032, "Expected type name after '.'", peek());
                        break;
                    }
                    Token nameToken = advance();
                    nameTokens.add(nameToken);
                }

                // Build module-qualified type: last token is the type name,
                // all preceding tokens joined by dots form the module path.
                // For A.B: moduleName = "A", typeName = "B"
                // For A.B.C: moduleName = "A.B", typeName = "C"
                if (nameTokens.size() >= 2) {
                    StringBuilder modulePart = new StringBuilder(nameTokens.get(0).lexeme());
                    for (int i = 1; i < nameTokens.size() - 1; i++) {
                        modulePart.append('.').append(nameTokens.get(i).lexeme());
                    }
                    String typePart = nameTokens.get(nameTokens.size() - 1).lexeme();
                    Span qspan = spanBetween(nameTokens.get(0),
                            nameTokens.get(nameTokens.size() - 1));
                    return new QualifiedType(qspan, modulePart.toString(), typePart);
                }
                // Fallback: single identifier with trailing dots (should not happen)
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

        error(DiagnosticCode.E1032, "Expected type name", peek());
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
            // DEAL v1.2: rest parameters were removed from the language.
            error(DiagnosticCode.E1047,
                "Rest parameters are not supported in DEAL v1.2", peek());
            skipRestParameterTokens();
            return new ParamListResult(params);
        }

        Parameter first = parseParameter();
        if (first != null) params.add(first);

        while (match(TokenType.COMMA)) {
            if (peek().type() == TokenType.RPAREN) break;
            if (peek().type() == TokenType.ELLIPSIS) {
                error(DiagnosticCode.E1047,
                    "Rest parameters are not supported in DEAL v1.2", peek());
                skipRestParameterTokens();
                break;
            }
            Parameter param = parseParameter();
            if (param != null) params.add(param);
            else break;
        }

        return new ParamListResult(params);
    }

    private Parameter parseParameter() {
        Token nameToken = expect(TokenType.IDENTIFIER, DiagnosticCode.E1029, "Expected parameter name");
        if (nameToken == null) return null;

        if (!match(TokenType.COLON)) {
            error(DiagnosticCode.E1008, "Expected ':' after parameter name", peek());
            return null;
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
        ExpressionNode left = parseBinary(PREC_OR);

        if (match(TokenType.EQ_SIGN)) {
            ExpressionNode right = parseAssignment();
            if (right == null) return left;

            if (!isValidAssignmentTarget(left)) {
                error(DiagnosticCode.E1033, "Invalid assignment target", left);
            }

            Span sp = spanBetween(left.span(), right.span());
            return new AssignmentExpr(sp, left, right);
        }

        return left;
    }

    private ExpressionNode parseBinary(int minPrec) {
        ExpressionNode left = parseUnary();
        if (left == null) return null;

        while (true) {
            Token op = peek();
            int prec = getBinaryPrecedence(op.type());
            if (prec < minPrec) break;

            advance();
            int nextMinPrec = isRightAssocBinary(prec) ? prec : prec + 1;
            ExpressionNode right = parseBinary(nextMinPrec);
            if (right == null) return left;

            BinaryOp binaryOp = tokenToBinaryOp(op.type());
            Span sp = spanBetween(left.span(), right.span());
            left = new BinaryExpr(sp, left, binaryOp, right);
        }

        return left;
    }

    private ExpressionNode parseUnary() {
        // await expression: await PostfixExpression
        if (match(TokenType.AWAIT)) {
            Token awaitToken = previous();
            ExpressionNode expr = parsePostfix();
            if (expr == null) return null;
            if (!(expr instanceof CallExpr)) {
                error(DiagnosticCode.E1042, "'await' must be followed by a function call", awaitToken);
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

        return parsePostfix();
    }

    private ExpressionNode parsePostfix() {
        ExpressionNode expr = parsePrimary();
        if (expr == null) return null;

        while (true) {
            if (match(TokenType.DOT)) {
                Token fieldToken = expect(TokenType.IDENTIFIER, DiagnosticCode.E1034,
                        "Expected field name after '.'");
                if (fieldToken == null) break;
                Span sp = spanBetween(expr.span(), spanOf(fieldToken));
                expr = new MemberAccessExpr(sp, expr, fieldToken.lexeme());
            } else if (match(TokenType.LBRACKET)) {
                ExpressionNode index = parseExpression();
                if (index == null) { synchronize(); break; }
                Token rbracket = expect(TokenType.RBRACKET, DiagnosticCode.E1035, "Expected ']'");
                Span sp;
                if (rbracket != null) {
                    sp = spanBetween(expr.span(), spanOf(rbracket));
                } else {
                    sp = spanBetween(expr.span(), index.span());
                }
                expr = new IndexExpr(sp, expr, index);
            } else if (match(TokenType.LPAREN)) {
                List<ExpressionNode> args = new ArrayList<>();
                if (peek().type() != TokenType.RPAREN) {
                    ExpressionNode first = parseExpression();
                    if (first != null) args.add(first);
                    while (match(TokenType.COMMA)) {
                        if (peek().type() == TokenType.RPAREN) break;
                        ExpressionNode arg = parseExpression();
                        if (arg != null) args.add(arg);
                        else break;
                    }
                }
                Token rparen = expect(TokenType.RPAREN, DiagnosticCode.E1010,
                        "Expected ')' after arguments");
                Span sp;
                if (rparen != null) {
                    sp = spanBetween(expr.span(), spanOf(rparen));
                } else {
                    sp = spanBetween(expr.span(), spanOf(previousOrCurrent()));
                }
                expr = new CallExpr(sp, expr, List.copyOf(args));
            } else {
                break;
            }
        }

        return expr;
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
            case INT_LITERAL -> {
                advance();
                long value;
                try {
                    value = Long.parseLong(previous().lexeme());
                } catch (NumberFormatException e) {
                    error(DiagnosticCode.E1036, "Integer literal out of range: " + previous().lexeme(), previous());
                    value = 0;
                }
                return new LiteralExpr(spanOf(previous()), new LiteralValue.IntLiteral(value));
            }
            case NUMBER_LITERAL -> {
                advance();
                double value;
                try {
                    value = Double.parseDouble(previous().lexeme());
                } catch (NumberFormatException e) {
                    error(DiagnosticCode.E1036, "Invalid number literal: " + previous().lexeme(), previous());
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
            case IDENTIFIER -> {
                advance();
                return new IdentifierExpr(spanOf(previous()), previous().lexeme());
            }
            case LPAREN -> {
                advance();
                ExpressionNode inner = parseExpression();
                if (inner == null) {
                    synchronize();
                    return new LiteralExpr(spanOf(token), new LiteralValue.NullLiteral());
                }
                expect(TokenType.RPAREN, DiagnosticCode.E1015, "Expected ')' after expression");
                return inner;
            }
            case LBRACKET -> { return parseArrayLiteral(); }
            case LBRACE   -> { return parseObjectLiteral(); }
            case FUNCTION -> { return parseFunctionExpression(); }
            case ASYNC    -> { return parseFunctionExpression(); }
            case HAS      -> { return parseHasExpression(); }
            default -> {
                error(DiagnosticCode.E1037, "Expected expression", token);
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
        return new TemplateLiteralExpr(spanOf(token), parts);
    }

    /**
     * Splits raw template literal content into alternating string-literal
     * and expression parts.  Even-indexed parts are LiteralExpr(StringLiteral);
     * odd-indexed parts are interpolated expressions.
     *
     * <p>Implements escape processing (D11), brace-depth scan (D14),
     * sub-lexer re-entry, and the D5 template scalar-map rebasing.</p>
     */
    private List<ExpressionNode> splitTemplateLiteral(String raw, int baseLine, int baseCol,
                                                      int templateTokenStartScalarOffset) {
        List<ExpressionNode> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int pos = 0;
        // Track where the current string accumulation started in the raw content.
        // The raw content begins at source column baseCol + 1 (after the opening backtick).
        int stringStart = 0;

        while (pos < raw.length()) {
            char c = raw.charAt(pos);

            // 1. Backslash escape
            if (c == '\\') {
                pos++; // skip backslash
                if (pos >= raw.length()) break;
                char esc = raw.charAt(pos);
                switch (esc) {
                    case 'n'  -> current.append('\n');
                    case 't'  -> current.append('\t');
                    case '\\' -> current.append('\\');
                    case '"'  -> current.append('"');
                    case '\'' -> current.append('\'');
                    case '`'  -> current.append('`');
                    case '$'  -> current.append('$');
                    case '\r', '\n' -> { /* handled by lexer — should not occur here */ }
                    default -> {
                        // D5: the E1042 pseudo-token is raw-positioned —
                        // column and scalar offset derive from the raw scalar
                        // count of the prefix; the scalar length is the raw
                        // run it marks (the escape character, one scalar).
                        error(DiagnosticCode.E1042,
                            "Invalid escape sequence in template literal: '\\" + esc + "'",
                            rawPositionedToken(raw, baseLine, baseCol,
                                templateTokenStartScalarOffset, pos, 1));
                    }
                }
                pos++;
                continue;
            }

            // 2. Interpolation start: ${
            if (c == '$' && pos + 1 < raw.length() && raw.charAt(pos + 1) == '{') {
                // Flush accumulated string part
                flushStringPart(parts, current.toString(), baseLine, baseCol + 1 + stringStart);
                current.setLength(0);

                pos += 2; // skip '$' and '{'
                int exprStart = pos;

                // D14: Find matching '}' using string/template/escape-aware scan
                int exprEnd = findMatchingBrace(raw, pos, baseLine, baseCol,
                    templateTokenStartScalarOffset);
                if (exprEnd < 0) {
                    // Unterminated — error already emitted by findMatchingBrace
                    // Use rest of raw as expression for recovery
                    exprEnd = raw.length();
                }

                // D5: decode the expression and build the scalar map in one
                // pass (an escape's decoded scalar maps to the escape's first
                // raw scalar).
                DecodedTemplateExpr decoded = unescapeTemplateExpression(raw, exprStart, exprEnd);
                TemplateScalarMap map = new TemplateScalarMap(baseLine, baseCol,
                    templateTokenStartScalarOffset,
                    ScalarSourceCursor.scalarCount(raw, 0, exprStart),
                    ScalarSourceCursor.scalarCount(raw, 0, exprEnd),
                    decoded.rawStarts(), decoded.rawEnds());

                // The rebased sub-parser EOF token sits at the raw position of
                // the first scalar after the expression: the closing '}' when
                // the interpolation is terminated, else the end of the raw
                // template content (the same recovery position
                // findMatchingBrace already selects).
                ExpressionNode expr = parseEmbeddedExpression(decoded.source(), map,
                    ScalarSourceCursor.scalarCount(raw, 0, exprEnd));
                parts.add(expr);

                pos = exprEnd;
                if (pos < raw.length() && raw.charAt(pos) == '}') {
                    pos++; // skip '}'
                }
                // Next string part starts after the closing brace
                stringStart = pos;
                continue;
            }

            // 3. Unescaped '}' without matching '${'
            if (c == '}') {
                error(DiagnosticCode.E1042,
                    "Unexpected '}' in template literal",
                    rawPositionedToken(raw, baseLine, baseCol,
                        templateTokenStartScalarOffset, pos, 1));
                pos++;
                continue;
            }

            // 4. Bare character
            current.append(c);
            pos++;
        }

        // Flush remaining accumulator
        flushStringPart(parts, current.toString(), baseLine, baseCol + 1 + stringStart);

        return parts;
    }

    /**
     * D14: Escape-aware, string-literal-aware, nested-template-literal-aware
     * brace-depth scan. Returns the index of the matching '}' or -1 if unterminated.
     */
    private int findMatchingBrace(String raw, int startPos, int baseLine, int baseCol,
                                  int templateTokenStartScalarOffset) {
        int depth = 1;
        int scanPos = startPos;

        while (scanPos < raw.length() && depth > 0) {
            char c = raw.charAt(scanPos);

            // Top-level backslash: skip escape sequence entirely
            if (c == '\\') {
                scanPos += 2;
                continue;
            }

            // String literal: skip to matching closing quote
            if (c == '"' || c == '\'') {
                char quote = c;
                scanPos++;
                while (scanPos < raw.length()) {
                    char sc = raw.charAt(scanPos);
                    if (sc == '\\') {
                        scanPos += 2; // skip escape sequence
                    } else if (sc == quote) {
                        scanPos++;
                        break; // closing quote found
                    } else {
                        scanPos++;
                    }
                }
                continue;
            }

            // Nested template literal: skip to matching backtick
            if (c == '`') {
                scanPos++;
                while (scanPos < raw.length()) {
                    char tc = raw.charAt(scanPos);
                    if (tc == '\\') {
                        scanPos += 2; // skip escape sequence
                    } else if (tc == '`') {
                        scanPos++;
                        break; // closing backtick found
                    } else {
                        scanPos++;
                    }
                }
                continue;
            }

            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return scanPos; // matching '}' found
                }
            }
            scanPos++;
        }

        // Unterminated — the E1042 pseudo-token is raw-positioned (D5): it
        // marks the two raw scalars at startPos (the expression start, the
        // historical recovery anchor) with exact scalar offsets.
        error(DiagnosticCode.E1042,
            "Unterminated '${' in template literal",
            rawPositionedToken(raw, baseLine, baseCol,
                templateTokenStartScalarOffset, startPos, 2));
        return -1;
    }

    /**
     * Unescapes template-literal escape sequences in the expression substring
     * before passing it to the sub-lexer, and produces the per-decoded-scalar
     * raw mapping alongside (D5).  This converts {@code \\`} to {@code `},
     * {@code \\$} to {@code $}, {@code \\\\} to {@code \\}, etc., respecting string
     * literal and nested template literal boundaries so that escapes inside strings
     * are preserved verbatim.
     *
     * <p>The raw mapping is indexed by decoded Unicode scalar: entry {@code i}
     * covers the {@code i}-th decoded scalar of the returned source and names
     * the raw scalar range {@code [rawStarts[i], rawEnds[i])} (indices into the
     * template's raw content) that produced it. A recognized escape's single
     * decoded scalar maps to its first raw scalar (the backslash), so the raw
     * run is two scalars; every verbatim copy maps one-to-one.</p>
     */
    private DecodedTemplateExpr unescapeTemplateExpression(String raw, int exprStart, int exprEnd) {
        String expr = raw.substring(exprStart, exprEnd);
        StringBuilder sb = new StringBuilder(expr.length());
        List<Integer> rawStarts = new ArrayList<>();
        List<Integer> rawEnds = new ArrayList<>();
        // Raw scalar index (into the full raw content) of the current position.
        int rawIndex = ScalarSourceCursor.scalarCount(raw, 0, exprStart);
        int pos = 0;
        while (pos < expr.length()) {
            char c = expr.charAt(pos);

            // Top-level escape sequence: unescape it (one decoded scalar from
            // two raw scalars, mapped to the escape's first raw scalar).
            if (c == '\\' && pos + 1 < expr.length()) {
                char next = expr.charAt(pos + 1);
                switch (next) {
                    case 'n'  -> appendDecoded(sb, rawStarts, rawEnds, '\n', rawIndex, rawIndex + 2);
                    case 't'  -> appendDecoded(sb, rawStarts, rawEnds, '\t', rawIndex, rawIndex + 2);
                    case '\\' -> appendDecoded(sb, rawStarts, rawEnds, '\\', rawIndex, rawIndex + 2);
                    case '"'  -> appendDecoded(sb, rawStarts, rawEnds, '"', rawIndex, rawIndex + 2);
                    case '\'' -> appendDecoded(sb, rawStarts, rawEnds, '\'', rawIndex, rawIndex + 2);
                    case '`'  -> appendDecoded(sb, rawStarts, rawEnds, '`', rawIndex, rawIndex + 2);
                    case '$'  -> appendDecoded(sb, rawStarts, rawEnds, '$', rawIndex, rawIndex + 2);
                    default -> {
                        // Unknown escape: backslash and character are kept
                        // verbatim, one decoded scalar each. A surrogate pair
                        // after the backslash stays one decoded scalar mapped
                        // to its one raw scalar.
                        appendDecoded(sb, rawStarts, rawEnds, c, rawIndex, rawIndex + 1);
                        if (Character.isHighSurrogate(next) && pos + 2 < expr.length()
                                && Character.isLowSurrogate(expr.charAt(pos + 2))) {
                            appendDecoded(sb, rawStarts, rawEnds,
                                Character.toCodePoint(next, expr.charAt(pos + 2)),
                                rawIndex + 1, rawIndex + 2);
                            pos += 3;
                        } else {
                            appendDecoded(sb, rawStarts, rawEnds, next,
                                rawIndex + 1, rawIndex + 2);
                            pos += 2;
                        }
                        rawIndex += 2;
                        continue;
                    }
                }
                rawIndex += 2;
                pos += 2;
                continue;
            }

            // String literal: copy verbatim to preserve internal escapes
            if (c == '"' || c == '\'') {
                char quote = c;
                appendDecoded(sb, rawStarts, rawEnds, c, rawIndex, rawIndex + 1);
                rawIndex++;
                pos++;
                while (pos < expr.length()) {
                    char sc = expr.charAt(pos);
                    if (sc == '\\' && pos + 1 < expr.length()) {
                        appendDecoded(sb, rawStarts, rawEnds, sc, rawIndex, rawIndex + 1);
                        char esc = expr.charAt(pos + 1);
                        if (Character.isHighSurrogate(esc) && pos + 2 < expr.length()
                                && Character.isLowSurrogate(expr.charAt(pos + 2))) {
                            appendDecoded(sb, rawStarts, rawEnds,
                                Character.toCodePoint(esc, expr.charAt(pos + 2)),
                                rawIndex + 1, rawIndex + 2);
                            pos += 3;
                        } else {
                            appendDecoded(sb, rawStarts, rawEnds, esc,
                                rawIndex + 1, rawIndex + 2);
                            pos += 2;
                        }
                        rawIndex += 2;
                    } else if (sc == quote) {
                        appendDecoded(sb, rawStarts, rawEnds, sc, rawIndex, rawIndex + 1);
                        rawIndex++;
                        pos++;
                        break;
                    } else {
                        int cp = expr.codePointAt(pos);
                        appendDecoded(sb, rawStarts, rawEnds, cp, rawIndex, rawIndex + 1);
                        rawIndex++;
                        pos += Character.charCount(cp);
                    }
                }
                continue;
            }

            // Nested template literal: copy verbatim (sub-lexer will handle it)
            if (c == '`') {
                appendDecoded(sb, rawStarts, rawEnds, c, rawIndex, rawIndex + 1);
                rawIndex++;
                pos++;
                while (pos < expr.length()) {
                    char tc = expr.charAt(pos);
                    if (tc == '\\' && pos + 1 < expr.length()) {
                        appendDecoded(sb, rawStarts, rawEnds, tc, rawIndex, rawIndex + 1);
                        char esc = expr.charAt(pos + 1);
                        if (Character.isHighSurrogate(esc) && pos + 2 < expr.length()
                                && Character.isLowSurrogate(expr.charAt(pos + 2))) {
                            appendDecoded(sb, rawStarts, rawEnds,
                                Character.toCodePoint(esc, expr.charAt(pos + 2)),
                                rawIndex + 1, rawIndex + 2);
                            pos += 3;
                        } else {
                            appendDecoded(sb, rawStarts, rawEnds, esc,
                                rawIndex + 1, rawIndex + 2);
                            pos += 2;
                        }
                        rawIndex += 2;
                    } else if (tc == '`') {
                        appendDecoded(sb, rawStarts, rawEnds, tc, rawIndex, rawIndex + 1);
                        rawIndex++;
                        pos++;
                        break;
                    } else {
                        int cp = expr.codePointAt(pos);
                        appendDecoded(sb, rawStarts, rawEnds, cp, rawIndex, rawIndex + 1);
                        rawIndex++;
                        pos += Character.charCount(cp);
                    }
                }
                continue;
            }

            // Regular character: one decoded scalar, one-to-one raw mapping.
            int cp = expr.codePointAt(pos);
            appendDecoded(sb, rawStarts, rawEnds, cp, rawIndex, rawIndex + 1);
            rawIndex++;
            pos += Character.charCount(cp);
        }
        return new DecodedTemplateExpr(sb.toString(), rawStarts, rawEnds);
    }

    /** Appends one decoded scalar and records its raw scalar run. */
    private static void appendDecoded(StringBuilder sb, List<Integer> rawStarts,
                                      List<Integer> rawEnds, int codePoint,
                                      int rawStart, int rawEnd) {
        sb.appendCodePoint(codePoint);
        rawStarts.add(rawStart);
        rawEnds.add(rawEnd);
    }

    /**
     * Creates a LiteralExpr from an accumulated string part and adds it to the list.
     */
    private void flushStringPart(List<ExpressionNode> parts, String value,
                                  int line, int col) {
        Span sp = new Span(file, line, col, line, col + Math.max(0, value.length() - 1));
        parts.add(new LiteralExpr(sp, new LiteralValue.StringLiteral(value)));
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
            runUtf16Length, startOffset, scalarLength, List.of());
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
        int baseLine = map.sourceLine();

        // 1. Sub-lex the expression substring
        deal.lexer.Lexer subLexer = new deal.lexer.Lexer(source, file);
        deal.lexer.LexResult subResult = subLexer.tokenize();

        // 2. Merge sub-lexer diagnostics rebased through the scalar map. A
        // range that cannot be rebased (defensive: non-SOURCE or offset-less)
        // is kept as produced by the sub-lexer.
        for (CompilerDiagnostic d : subResult.diagnostics()) {
            DiagnosticRange rebased = rebaseSubRange(d.range(), map);
            if (rebased != null) {
                diagnostics.add(new CompilerDiagnostic(d.code(), d.severity(),
                    d.message(), rebased, d.notes(), d.diagnosticCode()));
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
            adjusted.add(rebaseSubToken(t, map, baseLine));
        }
        adjusted.add(new Token(TokenType.EOF, "", baseLine,
            map.sourceColumnAtRawScalar(eofRawScalarIndex), 0,
            map.sourceOffsetAtRawScalar(eofRawScalarIndex), 0, List.of()));

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
                new Token(TokenType.IDENTIFIER, "", baseLine,
                    map.sourceColumnAtRawScalar(rawScalarIndex), 0,
                    map.sourceOffsetAtRawScalar(rawScalarIndex), 0, List.of()));
            return placeholderLiteral(map);
        }

        // 5. Parse expression with a sub-parser
        //    parseExpression() is private but accessible — Java JLS section 6.6.1
        Parser subParser = new Parser(adjusted, file);
        ExpressionNode expr = subParser.parseExpression();

        // 6. Merge sub-parser diagnostics. The adjusted tokens carry rebased
        // line/column positions and exact original scalar offsets, so
        // sub-parser diagnostics are already SOURCE-exact in the original
        // file — including end-of-input errors anchored at the past-end
        // pseudo-EOF derived from the retained rebased EOF token.
        diagnostics.addAll(subParser.diagnostics);

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
        return new DiagnosticRange(file, map.sourceLine(),
            map.sourceColumnAtRawScalar(rawStart),
            map.sourceLine(), map.sourceColumnAtRawScalar(rawEnd),
            startOffset, endOffset, endOffset - startOffset, RangeOrigin.SOURCE);
    }

    /**
     * Rebuilds one sub-lexer token with rebased coordinates: start column and
     * scalar offset come from the raw scalar index of the token's first
     * decoded scalar; the scalar length is the raw run the token covers.
     * A token without offset information (defensive/test-only) keeps the
     * line/column rebase and carries UNKNOWN offsets.
     */
    private Token rebaseSubToken(Token t, TemplateScalarMap map, int baseLine) {
        if (!t.hasScalarOffsets()) {
            return new Token(t.type(), t.lexeme(), baseLine,
                map.sourceColumnAtRawScalar(map.expressionStartRawScalarIndex())
                    + t.column() - 1,
                t.length(), t.directives());
        }
        int decodedStart = t.startScalarOffset();
        int decodedEnd = t.endScalarOffset();
        int rawStart = map.decodedToRawStart(decodedStart);
        int rawEnd = decodedEnd > decodedStart
            ? map.decodedToRawEnd(decodedEnd - 1)
            : rawStart;
        return new Token(t.type(), t.lexeme(), baseLine,
            map.sourceColumnAtRawScalar(rawStart), t.length(),
            map.sourceOffsetAtRawScalar(rawStart),
            Math.max(0, rawEnd - rawStart), t.directives());
    }

    /**
     * The D16 placeholder: a zero-scalar-length LiteralExpr at the
     * expression-start raw position with explicit exact scalar offsets
     * (derived through the map's raw side).
     */
    private LiteralExpr placeholderLiteral(TemplateScalarMap map) {
        int rawScalarIndex = map.expressionStartRawScalarIndex();
        int line = map.sourceLine();
        int column = map.sourceColumnAtRawScalar(rawScalarIndex);
        int offset = map.sourceOffsetAtRawScalar(rawScalarIndex);
        return new LiteralExpr(
            new Span(file, line, column, line, column, offset, offset),
            new LiteralValue.StringLiteral(""));
    }

    /** Decoded template expression source plus its per-scalar raw mapping. */
    private record DecodedTemplateExpr(String source, List<Integer> rawStarts,
                                       List<Integer> rawEnds) {
    }

    private ExpressionNode parseFunctionExpression() {
        boolean isAsync = match(TokenType.ASYNC);
        Token startToken = isAsync ? previous() : peek();
        Token funcToken = advance();  // consumes FUNCTION

        expect(TokenType.LPAREN, DiagnosticCode.E1009, "Expected '(' after 'function'");
        var paramResult = parseParameterList();
        expect(TokenType.RPAREN, DiagnosticCode.E1010, "Expected ')' after function parameters");

        if (!match(TokenType.COLON)) {
            error(DiagnosticCode.E1011, "Expected ':' return type annotation on function expression", peek());
            synchronize(); return null;
        }
        TypeNode returnType = parseType();
        if (returnType == null) { synchronize(); return null; }

        Block body;
        if (peek().type() == TokenType.LBRACE) {
            body = parseBlock();
        } else {
            error(DiagnosticCode.E1012, "Expected '{' for function body", peek());
            body = emptyBlock();
            synchronize();
        }

        Span sp = spanBetween(startToken, previousOrCurrent());
        return new FunctionExpr(sp, paramResult.params, returnType, body, isAsync);
    }

    private ExpressionNode parseArrayLiteral() {
        Token lbracket = advance();
        List<ExpressionNode> elements = new ArrayList<>();

        if (peek().type() != TokenType.RBRACKET) {
            ExpressionNode first = parseExpression();
            if (first != null) elements.add(first);

            while (match(TokenType.COMMA)) {
                if (peek().type() == TokenType.RBRACKET) break;
                ExpressionNode elem = parseExpression();
                if (elem != null) elements.add(elem);
                else break;
            }
        }

        Token rbracket;
        if (match(TokenType.RBRACKET)) {
            rbracket = previous();
        } else {
            error(DiagnosticCode.E1035, "Expected ']' to close array literal", peek());
            rbracket = previousOrCurrent();
        }

        return new ArrayLiteralExpr(spanBetween(lbracket, rbracket), List.copyOf(elements));
    }

    private ExpressionNode parseObjectLiteral() {
        Token lbrace = advance();
        List<Property> properties = new ArrayList<>();

        if (peek().type() == TokenType.IDENTIFIER) {
            Property first = parseProperty();
            if (first != null) properties.add(first);

            while (match(TokenType.COMMA)) {
                if (peek().type() == TokenType.RBRACE) break;
                if (peek().type() != TokenType.IDENTIFIER) {
                    error(DiagnosticCode.E1038,
                        "Expected property name (identifier) in object literal", peek());
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
            error(DiagnosticCode.E1006, "Expected '}' to close object literal", peek());
            rbrace = previousOrCurrent();
        }

        return new ObjectLiteralExpr(spanBetween(lbrace, rbrace), List.copyOf(properties));
    }

    private Property parseProperty() {
        Token nameToken = advance();

        if (!match(TokenType.COLON)) {
            error(DiagnosticCode.E1008, "Expected ':' after property name", peek());
            return null;
        }

        ExpressionNode value = parseExpression();
        if (value == null) return null;

        Span sp = combineTokenAndSpan(nameToken, value.span());
        return new Property(sp, nameToken.lexeme(), value);
    }

    private ExpressionNode parseHasExpression() {
        Token hasToken = advance();

        expect(TokenType.LPAREN, DiagnosticCode.E1039, "Expected '(' after 'has'");

        // Parse the full expression including the .field part.
        // parsePostfix() will consume the chain like obj.field or a.b.c.
        ExpressionNode full = parsePostfix();

        expect(TokenType.RPAREN, DiagnosticCode.E1015, "Expected ')' after has() expression");

        // Decompose: the expression must be a MemberAccessExpr.
        // For a.b.c, the full expression is MemberAccessExpr(MemberAccessExpr(a, "b"), "c"),
        // and we want HasExpr(MemberAccessExpr(a, "b"), "c") — the outermost field
        // is the one being checked for existence.
        if (full instanceof MemberAccessExpr ma) {
            Span sp = spanBetween(hasToken, previousOrCurrent());
            return new HasExpr(sp, ma.object(), ma.field());
        }

        if (full != null) {
            error(DiagnosticCode.E1040, "Expected member access (obj.field) in has() expression", full);
        } else {
            error(DiagnosticCode.E1040, "Expected member access (obj.field) in has() expression", hasToken);
        }
        Span sp = spanBetween(hasToken, previousOrCurrent());
        return new HasExpr(sp, full, "?");
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
                    inputEofToken.startScalarOffset(), inputEofToken.scalarLength(),
                    List.of());
            }
            return new Token(TokenType.EOF, "", 1, 1, 0);
        }
        return tokens.get(pos);
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

    private Token expect(TokenType type, DiagnosticCode code, String message) {
        if (peek().type() == type) {
            return advance();
        }
        error(code, message, peek());
        return null;
    }

    private void optionalSemicolon() {
        match(TokenType.SEMICOLON);
    }

    // =======================================================================
    // Helper methods — spans
    // =======================================================================

    /**
     * Inclusive end column of a token on its line (tokens never span line
     * breaks). Uses the token's scalar length when known — columns count
     * decoded Unicode scalars — and falls back to the UTF-16 length for
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

    /**
     * Returns a zero-scalar-length token carrying the start position and
     * start scalar offset of the given span.
     */
    private Token tokenSpanStart(Span sp) {
        return new Token(TokenType.IDENTIFIER, "", sp.startLine(), sp.startColumn(), 1,
            sp.startScalarOffset(), 0, List.of());
    }

    /**
     * Returns a zero-scalar-length token carrying the start position and
     * start scalar offset of a TypeNode's span.
     */
    private Token tokenSpanStart(TypeNode type) {
        Span sp = type.span();
        return new Token(TokenType.IDENTIFIER, "", sp.startLine(), sp.startColumn(), 1,
            sp.startScalarOffset(), 0, List.of());
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
            case RBRACE, LET, CLASS, FUNCTION, ASYNC, RETURN, IF, WHILE, FOR,
                 BREAK, CONTINUE, IMPORT, EXPORT, DELETE, TRY, THROW,
                 SEMICOLON, EOF -> true;
            default -> false;
        };
    }

    private boolean canStartExpression(TokenType type) {
        return switch (type) {
            case NULL, TRUE, FALSE, INT_LITERAL, NUMBER_LITERAL, STRING_LITERAL, TEMPLATE_LITERAL,
                 IDENTIFIER, BANG, MINUS, LPAREN, LBRACKET, LBRACE,
                 FUNCTION, HAS, AWAIT, ASYNC -> true;
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
            case EQ_STRICT, NEQ_STRICT -> PREC_EQUALITY;
            case LT, LTE, GT, GTE -> PREC_RELATIONAL;
            case PLUS, MINUS -> PREC_ADDITIVE;
            case STAR, SLASH, PERCENT -> PREC_MULTIPLICATIVE;
            case STAR_STAR  -> PREC_EXPONENT;
            default         -> PREC_NONE;
        };
    }

    private boolean isRightAssocBinary(int prec) {
        return prec == PREC_EXPONENT;
    }

    private BinaryOp tokenToBinaryOp(TokenType type) {
        return switch (type) {
            case PLUS        -> BinaryOp.ADD;
            case MINUS       -> BinaryOp.SUB;
            case STAR        -> BinaryOp.MUL;
            case SLASH       -> BinaryOp.DIV;
            case PERCENT     -> BinaryOp.MOD;
            case STAR_STAR   -> BinaryOp.POW;
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
        String inner = lexeme.substring(1, lexeme.length() - 1);
        StringBuilder sb = new StringBuilder(inner.length());
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '\\' && i + 1 < inner.length()) {
                i++;
                switch (inner.charAt(i)) {
                    case 'n'  -> sb.append('\n');
                    case 't'  -> sb.append('\t');
                    case '\\' -> sb.append('\\');
                    case '"'  -> sb.append('"');
                    case '\'' -> sb.append('\'');
                    default   -> { sb.append('\\'); sb.append(inner.charAt(i)); }
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
