package deal.parser;

import deal.ast.*;
import deal.lexer.Diagnostic;
import deal.lexer.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private int pos;          // current index into tokens (0-based)

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    public Parser(List<Token> tokens, String file) {
        this.tokens = List.copyOf(tokens);
        this.file = file;
        this.pos = 0;
    }

    // =======================================================================
    // Public API
    // =======================================================================

    public ParseResult parse() {
        List<StatementNode> statements = new ArrayList<>();

        while (!isAtEnd()) {
            StatementNode stmt = parseStatement();
            if (stmt != null) {
                statements.add(stmt);
            }
        }

        Span progSpan;
        if (statements.isEmpty()) {
            progSpan = new Span(file, 1, 1, 1, 1);
        } else {
            StatementNode first = statements.get(0);
            StatementNode last = statements.get(statements.size() - 1);
            progSpan = new Span(file,
                    first.span().startLine(), first.span().startColumn(),
                    last.span().endLine(), last.span().endColumn());
        }

        ProgramNode program = new ProgramNode(progSpan, List.copyOf(statements));
        return new ParseResult(program, List.copyOf(diagnostics));
    }

    // =======================================================================
    // Statement parsing
    // =======================================================================

    private StatementNode parseStatement() {
        TokenType type = peek().type();

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
            case RBRACE    -> { advance(); error("E1041", "Unexpected '}'", previous()); yield null; }
            default        -> parseExpressionStatement();
        };
    }

    // -- Block --
    private Block parseBlock() {
        Token lbrace = expect(TokenType.LBRACE, "E1005", "Expected '{'");
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
            error("E1006", "Expected '}'", peek());
            rbrace = previousOrCurrent();
        }
        return new Block(spanBetween(lbrace, rbrace), List.copyOf(statements));
    }

    // -- ClassDeclaration --
    private StatementNode parseClassDeclaration() {
        Token classToken = advance();
        Token nameToken = expect(TokenType.IDENTIFIER, "E1007", "Expected class name after 'class'");
        if (nameToken == null) { synchronize(); return null; }

        Token lbrace = expect(TokenType.LBRACE, "E1005", "Expected '{' after class name");
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
            error("E1006", "Expected '}' at end of class body", peek());
            rbrace = previousOrCurrent();
        }
        return new ClassDeclaration(spanBetween(classToken, rbrace),
                nameToken.lexeme(), List.copyOf(fields));
    }

    private ClassField parseClassField() {
        if (peek().type() != TokenType.IDENTIFIER) {
            error("E1007", "Expected field name (identifier)", peek());
            advance();
            return null;
        }
        Token nameToken = advance();
        boolean optional = match(TokenType.QUESTION);

        if (!match(TokenType.COLON)) {
            error("E1008", "Expected ':' after field name", peek());
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
        Token funcToken = advance();
        Token nameToken = expect(TokenType.IDENTIFIER, "E1007", "Expected function name after 'function'");
        if (nameToken == null) { synchronize(); return null; }

        expect(TokenType.LPAREN, "E1009", "Expected '(' after function name");
        var paramResult = parseParameterList();
        expect(TokenType.RPAREN, "E1010", "Expected ')' after function parameters");

        if (!match(TokenType.COLON)) {
            error("E1011", "Expected ':' return type annotation", peek());
            synchronize(); return null;
        }
        TypeNode returnType = parseType();
        if (returnType == null) { synchronize(); return null; }

        Block body;
        if (peek().type() == TokenType.LBRACE) {
            body = parseBlock();
        } else if (match(TokenType.SEMICOLON)) {
            body = new Block(spanOf(previous()), List.of());
        } else {
            error("E1012", "Expected '{' for function body", peek());
            body = emptyBlock();
            synchronize();
        }

        Span sp = spanBetween(funcToken, previousOrCurrent());
        return new FunctionDeclaration(sp, nameToken.lexeme(),
                paramResult.params, paramResult.restParam, returnType, body);
    }

    // -- VariableDeclaration --
    private StatementNode parseVariableDeclaration() {
        Token letToken = advance();
        Token nameToken = expect(TokenType.IDENTIFIER, "E1007", "Expected variable name after 'let'");
        if (nameToken == null) { synchronize(); return null; }

        Optional<TypeNode> typeAnnotation = Optional.empty();
        if (match(TokenType.COLON)) {
            TypeNode type = parseType();
            if (type != null) typeAnnotation = Optional.of(type);
        }

        if (!match(TokenType.EQ_SIGN)) {
            error("E1013", "Expected '=' initializer in variable declaration", peek());
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

        expect(TokenType.LPAREN, "E1014", "Expected '(' after 'if'");
        ExpressionNode condition = parseExpression();
        if (condition == null) { synchronize(); return null; }
        expect(TokenType.RPAREN, "E1015", "Expected ')' after if condition");

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
                error("E1016", "Expected 'if' or '{' after 'else'", peek());
            }
        }

        Span sp = spanBetween(ifToken, previousOrCurrent());
        return new IfStatement(sp, condition, thenBlock, elseBranch);
    }

    // -- WhileStatement --
    private StatementNode parseWhileStatement() {
        Token whileToken = advance();

        expect(TokenType.LPAREN, "E1014", "Expected '(' after 'while'");
        ExpressionNode condition = parseExpression();
        if (condition == null) { synchronize(); return null; }
        expect(TokenType.RPAREN, "E1015", "Expected ')' after while condition");

        Block body = parseBlock();

        Span sp = spanBetween(whileToken, previousOrCurrent());
        return new WhileStatement(sp, condition, body);
    }

    // -- ForStatement --
    private StatementNode parseForStatement() {
        Token forToken = advance();
        expect(TokenType.LPAREN, "E1014", "Expected '(' after 'for'");

        // --- Init ---
        Optional<ForInit> init = Optional.empty();
        if (peek().type() == TokenType.LET) {
            Token letToken = advance();
            Token nameToken = expect(TokenType.IDENTIFIER, "E1007",
                    "Expected loop variable name after 'let'");
            if (nameToken == null) { synchronize(); return null; }

            Optional<TypeNode> typeAnnotation = Optional.empty();
            if (match(TokenType.COLON)) {
                typeAnnotation = Optional.ofNullable(parseType());
            }

            if (!match(TokenType.EQ_SIGN)) {
                error("E1013", "Expected '=' initializer in for-loop variable", peek());
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
                error("E1017",
                    "For-loop initializer must be a variable declaration or assignment",
                    expr);
                // Wrap in a synthetic assignment for recovery
                init = Optional.of(new ForInit.AssignExpr(
                        new AssignmentExpr(expr.span(), expr,
                                new LiteralExpr(expr.span(),
                                        new LiteralValue.NullLiteral()))));
            }
        }

        expect(TokenType.SEMICOLON, "E1018", "Expected ';' after for-loop initializer");

        // --- Condition ---
        Optional<ExpressionNode> condition = Optional.empty();
        if (peek().type() != TokenType.SEMICOLON) {
            ExpressionNode cond = parseExpression();
            if (cond != null) condition = Optional.of(cond);
        }
        expect(TokenType.SEMICOLON, "E1018", "Expected ';' after for-loop condition");

        // --- Update ---
        Optional<ExpressionNode> update = Optional.empty();
        if (peek().type() != TokenType.RPAREN) {
            ExpressionNode upd = parseExpression();
            if (upd != null) update = Optional.of(upd);
        }
        expect(TokenType.RPAREN, "E1015", "Expected ')' after for-loop update");

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
            error("E1019", "Expected '*' after 'import' (only namespace imports supported)", peek());
            synchronize(); return null;
        }
        if (!match(TokenType.AS)) {
            error("E1020", "Expected 'as' after '*' in import", peek());
            synchronize(); return null;
        }
        Token aliasToken = expect(TokenType.IDENTIFIER, "E1021", "Expected import alias name");
        if (aliasToken == null) { synchronize(); return null; }

        if (!match(TokenType.FROM)) {
            error("E1022", "Expected 'from' after import alias", peek());
            synchronize(); return null;
        }
        Token pathToken = expect(TokenType.STRING_LITERAL, "E1023",
                "Expected module path string literal after 'from'");
        if (pathToken == null) { synchronize(); return null; }

        String raw = pathToken.lexeme();
        String modulePath = raw.substring(1, raw.length() - 1);

        optionalSemicolon();
        Span sp = spanBetween(importToken, previousOrCurrent());
        return new ImportDeclaration(sp, aliasToken.lexeme(), modulePath);
    }

    // -- Export --
    private StatementNode parseExportDeclaration() {
        Token exportToken = advance();
        StatementNode declaration;

        TokenType nextType = peek().type();
        if (nextType == TokenType.FUNCTION) {
            declaration = parseFunctionDeclaration();
        } else if (nextType == TokenType.CLASS) {
            declaration = parseClassDeclaration();
        } else {
            error("E1024", "'export' must be followed by 'function' or 'class'", peek());
            synchronize(); return null;
        }

        if (declaration == null) return null;

        Span sp = spanBetween(exportToken, previousOrCurrent());
        return new ExportDeclaration(sp, declaration);
    }

    // -- Delete --
    private StatementNode parseDeleteStatement() {
        Token deleteToken = advance();

        ExpressionNode target = parseExpression();
        if (target == null) { synchronize(); return null; }

        if (!(target instanceof MemberAccessExpr) && !(target instanceof IndexExpr)) {
            error("E1025",
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
            error("E1026", "Expected 'catch' after try block", peek());
            synchronize(); return null;
        }

        expect(TokenType.LPAREN, "E1027", "Expected '(' after 'catch'");
        Token catchVarToken = expect(TokenType.IDENTIFIER, "E1028",
                "Expected catch variable name");
        if (catchVarToken == null) { synchronize(); return null; }
        expect(TokenType.RPAREN, "E1015", "Expected ')' after catch variable");

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
        // Try function type first: '(' params ')' '=>' Type
        if (peek().type() == TokenType.LPAREN) {
            TypeNode funcType = tryParseFunctionType();
            if (funcType != null) return funcType;
        }
        return parseNullableType();
    }

    private TypeNode tryParseFunctionType() {
        int savedPos = pos;
        int diagSize = diagnostics.size();

        Token lparen = advance();

        List<FunctionTypeParam> params = new ArrayList<>();
        Optional<FunctionTypeParam> rest = Optional.empty();

        if (peek().type() != TokenType.RPAREN) {
            if (peek().type() == TokenType.ELLIPSIS) {
                rest = Optional.ofNullable(parseFunctionTypeRest());
            } else {
                FunctionTypeParam first = parseFunctionTypeParam();
                if (first != null) params.add(first);

                while (match(TokenType.COMMA) && peek().type() != TokenType.RPAREN) {
                    if (peek().type() == TokenType.ELLIPSIS) {
                        rest = Optional.ofNullable(parseFunctionTypeRest());
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
                List.copyOf(params), rest, returnType);
    }

    private FunctionTypeParam parseFunctionTypeParam() {
        Token nameToken = expect(TokenType.IDENTIFIER, "E1029",
                "Expected parameter name in function type");
        if (nameToken == null) return null;

        if (!match(TokenType.COLON)) {
            error("E1008", "Expected ':' after parameter name in function type", peek());
            return null;
        }
        TypeNode type = parseType();
        if (type == null) return null;

        return new FunctionTypeParam(spanBetween(nameToken, previousOrCurrent()),
                nameToken.lexeme(), type);
    }

    private FunctionTypeParam parseFunctionTypeRest() {
        Token ellipsis = advance();
        Token nameToken = expect(TokenType.IDENTIFIER, "E1029",
                "Expected rest parameter name");
        if (nameToken == null) return null;

        if (!match(TokenType.COLON)) {
            error("E1008", "Expected ':' after rest parameter name", peek());
            return null;
        }
        TypeNode type = parseType();
        if (type == null) return null;

        return new FunctionTypeParam(spanBetween(ellipsis, previousOrCurrent()),
                nameToken.lexeme(), type);
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
                error("E1030", "Expected 'null' after '|' in nullable type", peek());
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
                error("E1031", "Expected ']' in array type", peek());
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
        if (tt == TokenType.IDENTIFIER) {
            Token t = advance();
            return new NamedType(spanOf(t), t.lexeme());
        }

        // Parenthesized type
        if (tt == TokenType.LPAREN) {
            advance(); // '('
            TypeNode inner = parseType();
            if (inner == null) { synchronize(); return null; }
            expect(TokenType.RPAREN, "E1015", "Expected ')' after parenthesized type");
            return inner;
        }

        // 'true', 'false' — not valid type names, but parse as NamedType for error recovery
        if (tt == TokenType.TRUE || tt == TokenType.FALSE) {
            Token t = advance();
            return new NamedType(spanOf(t), t.lexeme());
        }

        error("E1032", "Expected type name", peek());
        return null;
    }

    // =======================================================================
    // Parameter list parsing
    // =======================================================================

    private record ParamListResult(List<Parameter> params, Optional<Parameter> restParam) {}

    private ParamListResult parseParameterList() {
        List<Parameter> params = new ArrayList<>();
        Optional<Parameter> restParam = Optional.empty();

        if (peek().type() == TokenType.RPAREN) {
            return new ParamListResult(params, restParam);
        }

        if (peek().type() == TokenType.ELLIPSIS) {
            Parameter rest = parseRestParameter();
            if (rest != null) restParam = Optional.of(rest);
            return new ParamListResult(params, restParam);
        }

        Parameter first = parseParameter();
        if (first != null) params.add(first);

        while (match(TokenType.COMMA)) {
            if (peek().type() == TokenType.RPAREN) break;
            if (peek().type() == TokenType.ELLIPSIS) {
                Parameter rest = parseRestParameter();
                if (rest != null) restParam = Optional.of(rest);
                break;
            }
            Parameter param = parseParameter();
            if (param != null) params.add(param);
            else break;
        }

        return new ParamListResult(params, restParam);
    }

    private Parameter parseParameter() {
        Token nameToken = expect(TokenType.IDENTIFIER, "E1029", "Expected parameter name");
        if (nameToken == null) return null;

        if (!match(TokenType.COLON)) {
            error("E1008", "Expected ':' after parameter name", peek());
            return null;
        }
        TypeNode type = parseType();
        if (type == null) return null;

        return new Parameter(spanBetween(nameToken, previousOrCurrent()),
                nameToken.lexeme(), type);
    }

    private Parameter parseRestParameter() {
        Token ellipsis = advance();
        Token nameToken = expect(TokenType.IDENTIFIER, "E1029", "Expected rest parameter name");
        if (nameToken == null) return null;

        if (!match(TokenType.COLON)) {
            error("E1008", "Expected ':' after rest parameter name", peek());
            return null;
        }
        TypeNode type = parseType();
        if (type == null) return null;

        return new Parameter(spanBetween(ellipsis, previousOrCurrent()),
                nameToken.lexeme(), type);
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
                error("E1033", "Invalid assignment target", left);
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
                Token fieldToken = expect(TokenType.IDENTIFIER, "E1034",
                        "Expected field name after '.'");
                if (fieldToken == null) break;
                Span sp = spanBetween(expr.span(), spanOf(fieldToken));
                expr = new MemberAccessExpr(sp, expr, fieldToken.lexeme());
            } else if (match(TokenType.LBRACKET)) {
                ExpressionNode index = parseExpression();
                if (index == null) { synchronize(); break; }
                Token rbracket = expect(TokenType.RBRACKET, "E1035", "Expected ']'");
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
                Token rparen = expect(TokenType.RPAREN, "E1010",
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
                    error("E1036", "Integer literal out of range: " + previous().lexeme(), previous());
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
                    error("E1036", "Invalid number literal: " + previous().lexeme(), previous());
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
                expect(TokenType.RPAREN, "E1015", "Expected ')' after expression");
                return inner;
            }
            case LBRACKET -> { return parseArrayLiteral(); }
            case LBRACE   -> { return parseObjectLiteral(); }
            case FUNCTION -> { return parseFunctionExpression(); }
            case HAS      -> { return parseHasExpression(); }
            default -> {
                error("E1037", "Expected expression", token);
                if (!isStatementBoundary(token.type())) {
                    advance();
                }
                return null;
            }
        }
    }

    private ExpressionNode parseFunctionExpression() {
        Token funcToken = advance();

        expect(TokenType.LPAREN, "E1009", "Expected '(' after 'function'");
        var paramResult = parseParameterList();
        expect(TokenType.RPAREN, "E1010", "Expected ')' after function parameters");

        if (!match(TokenType.COLON)) {
            error("E1011", "Expected ':' return type annotation on function expression", peek());
            synchronize(); return null;
        }
        TypeNode returnType = parseType();
        if (returnType == null) { synchronize(); return null; }

        Block body;
        if (peek().type() == TokenType.LBRACE) {
            body = parseBlock();
        } else {
            error("E1012", "Expected '{' for function body", peek());
            body = emptyBlock();
            synchronize();
        }

        Span sp = spanBetween(funcToken, previousOrCurrent());
        return new FunctionExpr(sp, paramResult.params, paramResult.restParam, returnType, body);
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
            error("E1035", "Expected ']' to close array literal", peek());
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
                    error("E1038",
                        "Expected property name (identifier) in object literal", peek());
                    break;
                }
                Property prop = parseProperty();
                if (prop != null) properties.add(prop);
                else break;
            }
        } else if (peek().type() != TokenType.RBRACE) {
            error("E1038", "Expected property name or '}' in object literal", peek());
        }

        Token rbrace;
        if (match(TokenType.RBRACE)) {
            rbrace = previous();
        } else {
            error("E1006", "Expected '}' to close object literal", peek());
            rbrace = previousOrCurrent();
        }

        return new ObjectLiteralExpr(spanBetween(lbrace, rbrace), List.copyOf(properties));
    }

    private Property parseProperty() {
        Token nameToken = advance();

        if (!match(TokenType.COLON)) {
            error("E1008", "Expected ':' after property name", peek());
            return null;
        }

        ExpressionNode value = parseExpression();
        if (value == null) return null;

        Span sp = combineTokenAndSpan(nameToken, value.span());
        return new Property(sp, nameToken.lexeme(), value);
    }

    private ExpressionNode parseHasExpression() {
        Token hasToken = advance();

        expect(TokenType.LPAREN, "E1039", "Expected '(' after 'has'");

        // Parse the full expression including the .field part.
        // parsePostfix() will consume the chain like obj.field or a.b.c.
        ExpressionNode full = parsePostfix();

        expect(TokenType.RPAREN, "E1015", "Expected ')' after has() expression");

        // Decompose: the expression must be a MemberAccessExpr.
        // For a.b.c, the full expression is MemberAccessExpr(MemberAccessExpr(a, "b"), "c"),
        // and we want HasExpr(MemberAccessExpr(a, "b"), "c") — the outermost field
        // is the one being checked for existence.
        if (full instanceof MemberAccessExpr ma) {
            Span sp = spanBetween(hasToken, previousOrCurrent());
            return new HasExpr(sp, ma.object(), ma.field());
        }

        if (full != null) {
            error("E1040", "Expected member access (obj.field) in has() expression", full);
        } else {
            error("E1040", "Expected member access (obj.field) in has() expression", hasToken);
        }
        Span sp = spanBetween(hasToken, previousOrCurrent());
        return new HasExpr(sp, full, "?");
    }

    // =======================================================================
    // Helper methods — token stream
    // =======================================================================

    private Token peek() {
        if (pos >= tokens.size()) {
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

    private Token expect(TokenType type, String code, String message) {
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

    private Span spanOf(Token token) {
        int endCol = token.column() + Math.max(0, token.length() - 1);
        return new Span(file, token.line(), token.column(), token.line(), endCol);
    }

    private Span spanBetween(Token start, Token end) {
        int endCol = end.column() + Math.max(0, end.length() - 1);
        return new Span(file, start.line(), start.column(), end.line(), endCol);
    }

    private Span spanBetween(Span start, Span end) {
        return new Span(file, start.startLine(), start.startColumn(),
                end.endLine(), end.endColumn());
    }

    /** Returns a synthetic token carrying the start position of the given span. */
    private Token tokenSpanStart(Span sp) {
        return new Token(TokenType.IDENTIFIER, "", sp.startLine(), sp.startColumn(), 1);
    }

    /** Returns a synthetic token carrying the start position of a TypeNode. */
    private Token tokenSpanStart(TypeNode type) {
        Span sp = type.span();
        return new Token(TokenType.IDENTIFIER, "", sp.startLine(), sp.startColumn(), 1);
    }

    /** Combines a start Token and end Span into a new Span. */
    private Span combineTokenAndSpan(Token start, Span end) {
        return new Span(file, start.line(), start.column(),
                end.endLine(), end.endColumn());
    }

    // =======================================================================
    // Helper methods — errors
    // =======================================================================

    private void error(String code, String message, Token token) {
        diagnostics.add(Diagnostic.error(code, message, file, token.line(), token.column()));
    }

    private void error(String code, String message, ExpressionNode node) {
        Span sp = node.span();
        diagnostics.add(Diagnostic.error(code, message, file,
                sp.startLine(), sp.startColumn()));
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
            case RBRACE, LET, CLASS, FUNCTION, RETURN, IF, WHILE, FOR,
                 BREAK, CONTINUE, IMPORT, EXPORT, DELETE, TRY, THROW,
                 SEMICOLON, EOF -> true;
            default -> false;
        };
    }

    private boolean canStartExpression(TokenType type) {
        return switch (type) {
            case NULL, TRUE, FALSE, INT_LITERAL, NUMBER_LITERAL, STRING_LITERAL,
                 IDENTIFIER, BANG, MINUS, LPAREN, LBRACKET, LBRACE,
                 FUNCTION, HAS -> true;
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
