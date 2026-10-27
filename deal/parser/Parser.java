package deal.parser;

import deal.ast.*;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.DiagnosticNote;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.lexer.CompilerDirective;
import deal.lexer.DirectiveName;
import deal.lexer.TemplateScanner;
import deal.lexer.Token;
import deal.source.ScalarSourceCursor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
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

    /**
     * The directive event stream (fixed-name-directive-events D1/D10):
     * the parent lexer events plus the template-embedded merged events,
     * in eventIndex order. Merged events append at
     * {@code parseEmbeddedExpression} completion with continuing
     * event indices.
     */
    private final List<CompilerDirective> directiveEvents = new ArrayList<>();
    /** File-directive evaluation state (D4/D7), shared with merge points. */
    private final FileDirectiveEvaluator evaluator;
    /** Events claimed by declaration binding (identity semantics). */
    private final Set<CompilerDirective> claimedEvents =
        Collections.newSetFromMap(new IdentityHashMap<>());

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /**
     * The no-events constructor (fixed-name-directive-events D1/D10.7):
     * pinned as the defensive form used by the template sub-parser,
     * defensive/test callers, and the JVM harness runner parse (D8 item
     * 2c) — no file-directive evaluation or binding runs.
     */
    public Parser(List<Token> tokens, String file) {
        this(tokens, file, List.of());
    }

    /**
     * The events-carrying constructor
     * (fixed-name-directive-events D1): the production shape — the
     * lexer's {@code directiveEvents} flow in, file-directive evaluation
     * and declaration binding run exactly as in production. Numeric tokens
     * decode directly to binary64; unary negation is always an expression.
     */
    public Parser(List<Token> tokens, String file,
                  List<CompilerDirective> directiveEvents) {
        this.tokens = List.copyOf(tokens);
        this.file = file;
        this.pos = 0;
        this.inputEofToken = findEofToken(this.tokens);
        if (directiveEvents != null) {
            this.directiveEvents.addAll(directiveEvents);
        }
        this.evaluator = new FileDirectiveEvaluator(diagnostics);
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
        // Validate directive arguments in event order.
        evaluator.evaluateParentEvents(directiveEvents);

        List<StatementNode> statements = new ArrayList<>();

        while (!isAtEnd()) {
            int diagCountBefore = diagnostics.size();
            StatementNode stmt = parseStatement();
            if (stmt != null) {
                checkStatementTerminator(stmt, diagCountBefore);
                statements.add(stmt);
            }
        }

        sweepUnclaimedAndUnanchored();

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

        ProgramNode program = new ProgramNode(progSpan, List.copyOf(statements),
            evaluator.build());
        return new ParseResult(program, List.copyOf(diagnostics));
    }

    // =======================================================================
    // Directive binding (D4)
    // =======================================================================

    /**
     * True when the source file path is a declaration file
     * ({@code .d.deal}) — the declaration-file {@code @jsonable}
     * no-effect rule (D4) keys on it.
     */
    private boolean isDeclarationFile() {
        return file != null && file.endsWith(".d.deal");
    }

    /**
     * Claims the directive events anchored at the current statement's
     * start-token index. Binding applies only at the statement-dispatch
     * start token (D4): {@code EXPORT} for exported declarations,
     * {@code CLASS}/{@code FUNCTION}/{@code ASYNC} for non-exported
     * ones. Events anchored to any other token stay unclaimed and are
     * handled by the finalize sweep.
     */
    private List<CompilerDirective> claimAnchoredAt(int tokenIndex) {
        TokenType type = peek().type();
        if (type != TokenType.EXPORT && type != TokenType.CLASS
                && type != TokenType.FUNCTION && type != TokenType.ASYNC) {
            return List.of();
        }
        List<CompilerDirective> anchored = new ArrayList<>();
        for (CompilerDirective e : directiveEvents) {
            if (e.declarationAnchorTokenIndex() != null
                    && e.declarationAnchorTokenIndex() == tokenIndex
                    && !claimedEvents.contains(e)) {
                anchored.add(e);
            }
        }
        return anchored;
    }

    /**
     * Applies binding to a parsed statement (D4). Returns the statement
     * to record — an export-wrapped class whose metadata changed is
     * rebuilt with the new directive set.
     */
    private StatementNode applyBinding(StatementNode stmt,
                                       List<CompilerDirective> anchored) {
        if (anchored.isEmpty() || stmt == null) {
            return stmt;
        }
        if (stmt instanceof ExportDeclaration exp
                && exp.declaration() instanceof ClassDeclaration cd) {
            // Declaration files accept the marker without applying metadata.
            Set<DeclarationDirective> dirs = new HashSet<>(cd.directives());
            boolean changed = false;
            for (CompilerDirective e : anchored) {
                claimedEvents.add(e);
                if (e.name() == DirectiveName.JSONABLE) {
                    if (!isDeclarationFile()) {
                        dirs.add(DeclarationDirective.JSONABLE);
                        changed = true;
                    }
                }
            }
            if (!changed) {
                return stmt;
            }
            ClassDeclaration newCd = new ClassDeclaration(cd.span(),
                cd.name(), cd.fields(), dirs);
            return new ExportDeclaration(exp.span(), newCd);
        }
        // Other declaration attachments reject without applying metadata.
        for (CompilerDirective e : anchored) {
            if (e.name() == DirectiveName.JSONABLE) {
                errorJsonable(e);
                claimedEvents.add(e);
            } else {
                claimedEvents.add(e);
            }
        }
        return stmt;
    }
    /** Reject unattached jsonable events in event order. */
    private void sweepUnclaimedAndUnanchored() {
        for (CompilerDirective e : directiveEvents) {
            if (claimedEvents.contains(e)) {
                continue;
            }
            if (e.name() == DirectiveName.JSONABLE) {
                errorJsonable(e);
            }
        }
    }
    /**
     * The E1043 incompatible-attachment rejection (D4): error severity,
     * the pinned message, no metadata, compilation continues; anchored at
     * the complete directive comment range (parent D6).
     */
    private void errorJsonable(CompilerDirective e) {
        diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E1043,
            "@jsonable directive is only valid on 'export class'",
            e.sourceRange()));
    }

    // =======================================================================
    // Statement parsing
    // =======================================================================

    private StatementNode parseStatement() {
        // D4: binding applies only at the statement-dispatch start token —
        // EXPORT for exported declarations, CLASS/FUNCTION/ASYNC for
        // non-exported ones. Events anchored to any other token are
        // non-declaration anchors handled by the finalize sweep.
        int startIndex = pos;
        List<CompilerDirective> anchored = claimAnchoredAt(startIndex);

        TokenType type = peek().type();
        StatementNode stmt = switch (type) {
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
            stmt = applyBinding(stmt, anchored);
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
            StatementNode stmt = parseStatement();
            if (stmt != null) {
                checkStatementTerminator(stmt, diagCountBefore);
                statements.add(stmt);
            }
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
        if (!isPropertyName(peek())) {
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

        int diagCountBefore = diagnostics.size();
        if (match(TokenType.EQ_SIGN)) {
            error(DiagnosticCode.E1007, "Class field initializers are not supported", previous());
            // Recover at the field terminator without admitting an initializer AST.
            parseExpression();
        }
        optionalSemicolon();
        // Class fields require their ';'.
        checkTerminator(diagCountBefore);

        Span sp = spanBetween(nameToken, previousOrCurrent());
        return new ClassField(sp, nameToken.lexeme(), optional, nullable, type);
    }

    // -- FunctionDeclaration --
    private StatementNode parseFunctionDeclaration() {
        boolean isAsync = match(TokenType.ASYNC);
        Token startToken = isAsync ? previous() : peek();
        if (isAsync && peek().line() != startToken.line()) {
            error(DiagnosticCode.E1007, "No line terminator between async and function", peek());
        }
        Token funcToken = advance();  // consumes FUNCTION
        Token nameToken = expect(TokenType.IDENTIFIER, DiagnosticCode.E1007, "Expected function name after 'function'");
        if (nameToken == null) { synchronize(); return null; }

        FunctionSuffix suffix = parseFunctionSuffix("Expected '(' after function name",
            "Expected ':' return type annotation", true);
        if (suffix == null) { return null; }

        Span sp = spanBetween(startToken, previousOrCurrent());
        if (isAsync != suffix.isAsync() && !suffix.isExternal()) {
            error(DiagnosticCode.E1011, "Async implementations require Promise result notation; sync implementations forbid it", startToken);
        }
        if (isAsync && suffix.isExternal()) {
            error(DiagnosticCode.E1011, "External declarations must omit async", startToken);
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
        expect(TokenType.LPAREN, DiagnosticCode.E1009, lparenMessage);
        ParamListResult paramResult = parseParameterList();
        expect(TokenType.RPAREN, DiagnosticCode.E1010, "Expected ')' after function parameters");

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
            if (peek().line() != retToken.line()) {
                error(DiagnosticCode.E1007, "No line terminator between return and its operand", peek());
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
        expect(TokenType.LPAREN, DiagnosticCode.E1014, "Expected '(' after 'for'");
        expect(TokenType.LET, DiagnosticCode.E1007, "Expected 'let' in for-of loop");
        Token name = expect(TokenType.IDENTIFIER, DiagnosticCode.E1007,
            "Expected iteration variable name");
        if (name == null) { synchronize(); return null; }
        TypeNode type = null;
        if (match(TokenType.COLON)) {
            error(DiagnosticCode.E1013, "For-of bindings must not have a type annotation", previous());
            parseType();
        }
        if (!match(TokenType.OF)) {
            error(DiagnosticCode.E1013, "Expected 'of'; C-style for loops are not supported", peek());
            synchronize(); return null;
        }
        ExpressionNode iterable = parseExpression();
        if (iterable == null) { synchronize(); return null; }
        expect(TokenType.RPAREN, DiagnosticCode.E1015, "Expected ')' after for-of iterable");
        Block body = parseBlock();
        return new ForOfStatement(spanBetween(forToken, previousOrCurrent()),
            name.lexeme(), type, iterable, body);
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
        return new TryStatement(sp, tryBlock, catchVarToken.lexeme(),
            spanOf(catchVarToken), catchBlock);
    }

    // -- Throw --
    private StatementNode parseThrowStatement() {
        Token throwToken = advance();
        if (peek().line() != throwToken.line()) {
            error(DiagnosticCode.E1007, "No line terminator between throw and its operand", peek());
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
        int savedPos = pos;
        int diagSize = diagnostics.size();

        Token lparen = advance();

        List<FunctionTypeParam> params = new ArrayList<>();

        if (peek().type() != TokenType.RPAREN) {
            if (peek().type() == TokenType.ELLIPSIS) {
                // function types have no rest arm.
                error(DiagnosticCode.E1047,
                    "Rest parameters are not supported (function types carry no rest arm)",
                    peek());
                skipRestArmTokens();
            } else {
                FunctionTypeParam first = parseFunctionTypeParam();
                if (first != null) params.add(first);

                while (match(TokenType.COMMA)) {
                    if (peek().type() == TokenType.RPAREN) {
                        // FunctionTypeParams carries no trailing comma.
                        error(DiagnosticCode.E1054,
                            "Trailing comma in parameter list is not allowed", peek());
                        break;
                    }
                    if (peek().type() == TokenType.ELLIPSIS) {
                        error(DiagnosticCode.E1047,
                            "Rest parameters are not supported (function types carry no rest arm)",
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

        ResultAnnotation result = parseResultAnnotation();
        TypeNode returnType = result.type();
        if (returnType == null) {
            returnType = new NamedType(spanOf(previousOrCurrent()), "void");
        }

        return new FunctionType(spanBetween(lparen, previousOrCurrent()),
                List.copyOf(params), returnType, result.isAsync());
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
                return new NullableType(spanBetween(nullToken, previousOrCurrent()), inner,
                    nullToken.lexeme().equals("undefined"));
            }
            return parseNullableSuffix(nullType);
        }

        TypeNode inner = parseArrayType();
        if (inner == null) return null;

        return parseNullableSuffix(inner);
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
                        spanBetween(tokenSpanStart(inner), previousOrCurrent()),
                        inner, member.lexeme().equals("undefined"));
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
        return parseArraySuffixes(type);
    }

    /** Consumes the {@code ('[' ']')*} suffixes of an ArrayType onto its primary. */
    private TypeNode parseArraySuffixes(TypeNode primary) {
        TypeNode type = primary;
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

        // Parenthesized type: the v1.2 surface admits a parenthesized
        // nullable array element type, e.g. (int | null)[] (the spec's
        // nullable-array example). Function types are consumed by
        // tryParseFunctionType before this arm is reached.
        if (tt == TokenType.LPAREN) {
            Token lparen = advance();
            TypeNode inner = parseType();
            if (inner == null) return null;
            if (!match(TokenType.RPAREN)) {
                error(DiagnosticCode.E1015, "Expected ')' after type", peek());
            }
            return inner;
        }

        // 'null' keyword
        if (tt == TokenType.NULL) {
            Token t = advance();
            return new NamedType(spanOf(t), "null");
        }

        // Builtin type names (identifiers): boolean, int, number, string, table, Error
        // Also a single-qualifier type like ModuleAlias.ClassName
        if (tt == TokenType.IDENTIFIER) {
            Token first = advance();
            if (first.lexeme().equals("Record")) {
                expect(TokenType.LT, DiagnosticCode.E1032, "Expected '<' after Record");
                Token key = expect(TokenType.IDENTIFIER, DiagnosticCode.E1032, "Expected string key type");
                expect(TokenType.COMMA, DiagnosticCode.E1032, "Expected ',' in Record type");
                Token value = expect(TokenType.IDENTIFIER, DiagnosticCode.E1032, "Expected unknown value type");
                expect(TokenType.GT, DiagnosticCode.E1032, "Expected '>' after Record type");
                if (key == null || !key.lexeme().equals("string") || value == null || !value.lexeme().equals("unknown")) {
                    error(DiagnosticCode.E1032, "Only Record<string, unknown> is supported", first);
                }
                return new NamedType(spanBetween(first, previousOrCurrent()), "Record<string, unknown>");
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
                    error(DiagnosticCode.E1058,
                        "Type names admit at most one module or class qualifier"
                            + " component",
                        peek());
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
            // Rest parameters were removed from the language.
            error(DiagnosticCode.E1047,
                "Rest parameters are not supported", peek());
            skipRestParameterTokens();
            return new ParamListResult(params);
        }

        Parameter first = parseParameter();
        if (first != null) params.add(first);

        while (match(TokenType.COMMA)) {
            if (peek().type() == TokenType.RPAREN) {
                // ParameterList carries no trailing comma (v1.2 grammar).
                error(DiagnosticCode.E1054,
                    "Trailing comma in parameter list is not allowed", peek());
                break;
            }
            if (peek().type() == TokenType.ELLIPSIS) {
                error(DiagnosticCode.E1047,
                    "Rest parameters are not supported", peek());
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
                Token fieldToken = expectPropertyName(DiagnosticCode.E1034,
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
                        if (peek().type() == TokenType.RPAREN) {
                            // ArgumentList carries no trailing comma (v1.2 grammar).
                            error(DiagnosticCode.E1055,
                                "Trailing comma in argument list is not allowed", peek());
                            break;
                        }
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
            case NUMBER_LITERAL -> {
                advance();
                double value;
                try {
                    value = Double.parseDouble(previous().lexeme());
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
        // D10.3: a merged embedded event's precedingNonCommentTokenCount
        // is the template token's parent stream index plus 1 — pos is
        // exactly that value right after the template token was advanced.
        int templatePrecedingCount = pos;
        String raw = token.lexeme();
        List<ExpressionNode> parts = splitTemplateLiteral(raw, token.line(),
            token.column(), token.startScalarOffset(), templatePrecedingCount);
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
     * sub-lexed coordinate and merged directive event back onto the
     * original source (D10/D11).</p>
     */
    private List<ExpressionNode> splitTemplateLiteral(String raw, int baseLine, int baseCol,
                                                      int templateTokenStartScalarOffset,
                                                      int templatePrecedingCount) {
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
                raw.substring(exprUtf16Start, exprUtf16End), map, exprEndRaw,
                templatePrecedingCount));
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
        String value = raw.substring(utf16Start, utf16End);
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
                                                   int eofRawScalarIndex,
                                                   int templatePrecedingCount) {
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
                    d.message(), rebased, rebasedNotes, d.diagnosticCode()));
            } else {
                diagnostics.add(d);
            }
        }

        // 2b. Merge sub-lexer directive events (D10.1-3, D10.5): rebase
        // both ranges through the same TemplateScalarMap rules, force the
        // anchor null (sub-tokens never enter the parent stream and
        // expressions cannot contain declarations), give the truthful
        // preceding count (template token index + 1), and run the same
        // per-event rules at this merge point with the shared seen-sets.
        // The merge happens before the empty-expression early return so
        // embedded directives are never silently dropped (D10).
        for (CompilerDirective e : subResult.directiveEvents()) {
            DiagnosticRange rebasedSource = rebaseSubRange(e.sourceRange(), map);
            DiagnosticRange rebasedName = rebaseSubRange(e.nameRange(), map);
            if (rebasedSource == null) {
                rebasedSource = e.sourceRange();
            }
            if (rebasedName == null) {
                rebasedName = e.nameRange();
            }
            CompilerDirective merged = new CompilerDirective(
                directiveEvents.size(), e.name(), e.rawArgument(),
                e.trimmedArgument(), rebasedSource, rebasedName,
                templatePrecedingCount, null);
            directiveEvents.add(merged);
            evaluator.evaluateMergedEvent(merged);
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
        //    D10.7: the sub-parser is constructed with NO directive
        //    events — sub-events hoist to the enclosing parser instead of
        //    being re-evaluated here; the seen-sets are shared with the
        //    parent events (D10.5) so nested duplicate checks see them.
        Parser subParser = new Parser(adjusted, file, List.of());
        ExpressionNode expr = subParser.parseExpression();

        // 6. Merge sub-parser diagnostics. The adjusted tokens carry rebased
        // line/column positions and exact original scalar offsets, so
        // sub-parser diagnostics are already SOURCE-exact in the original
        // file — including end-of-input errors anchored at the past-end
        // pseudo-EOF derived from the retained rebased EOF token. Nested
        // template-embedded per-event diagnostics surface here too (the
        // nested merge points ran inside the sub-parser).
        diagnostics.addAll(subParser.diagnostics);

        // 7. v1.4: an interpolation contains exactly one complete
        //    expression (docs/spec-v1.5.md lexical grammar). A leftover
        //    token after the parsed expression is E1042 at that token's
        //    rebased position.
        if (expr != null && subParser.peek().type() != TokenType.EOF) {
            error(DiagnosticCode.E1042,
                "Template literal interpolation must contain exactly one"
                    + " complete expression",
                subParser.peek());
        }

        // 8. Nested-template events bubble up through the same append rule
        // (D10.4): they were validated at their nested merge points, so no
        // re-validation runs here; event indices continue after the parent
        // lexer's last event and anchors stay null. The finalize sweep of
        // this parser covers them as unanchored events.
        for (CompilerDirective e : subParser.directiveEvents) {
            directiveEvents.add(new CompilerDirective(
                directiveEvents.size(), e.name(), e.rawArgument(),
                e.trimmedArgument(), e.sourceRange(), e.nameRange(),
                e.precedingNonCommentTokenCount(), null));
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

    private ExpressionNode parseFunctionExpression() {
        boolean isAsync = match(TokenType.ASYNC);
        Token startToken = isAsync ? previous() : peek();
        if (isAsync && peek().line() != startToken.line()) {
            error(DiagnosticCode.E1007, "No line terminator between async and function", peek());
        }
        Token funcToken = advance();  // consumes FUNCTION

        FunctionSuffix suffix = parseFunctionSuffix("Expected '(' after 'function'",
            "Expected ':' return type annotation on function expression", false);
        if (suffix == null) { return null; }

        if (isAsync != suffix.isAsync()) {
            error(DiagnosticCode.E1011, "Async implementations require Promise result notation; sync implementations forbid it", startToken);
        }
        Span sp = spanBetween(startToken, previousOrCurrent());
        return new FunctionExpr(sp, suffix.params(), suffix.returnType(), suffix.body(),
            suffix.isAsync());
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

        if (isPropertyName(peek())) {
            Property first = parseProperty();
            if (first != null) properties.add(first);

            while (match(TokenType.COMMA)) {
                if (peek().type() == TokenType.RBRACE) break;
                if (!isPropertyName(peek())) {
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

    private static boolean isPropertyName(Token token) {
        return switch (token.type()) {
            case IDENTIFIER, LET, CLASS, FUNCTION, ASYNC, AWAIT, RETURN, IF, ELSE,
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

    /**
     * The v1.4 statement-terminator requirement: every production whose
     * grammar carries a {@code ';'} must consume it. Reports E1053 at the
     * next token when the statement ended without one; suppressed when the
     * statement already reported a diagnostic so recovery does not cascade.
     */
    private void checkTerminator(int diagCountBefore) {
        if (diagnostics.size() > diagCountBefore) return;
        if (pos > 0 && tokens.get(pos - 1).type() == TokenType.SEMICOLON) return;
        error(DiagnosticCode.E1053, missingTerminatorMessage(), peek());
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
            case NULL, TRUE, FALSE, NUMBER_LITERAL, STRING_LITERAL, TEMPLATE_LITERAL,
                 IDENTIFIER, BANG, MINUS, LPAREN, LBRACKET, LBRACE,
                 FUNCTION, AWAIT, ASYNC, TYPEOF -> true;
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
            case LT, LTE, GT, GTE, AS, INSTANCEOF -> PREC_RELATIONAL;
            case PLUS, MINUS -> PREC_ADDITIVE;
            case STAR, SLASH -> PREC_MULTIPLICATIVE;
            default         -> PREC_NONE;
        };
    }

    private BinaryOp tokenToBinaryOp(TokenType type) {
        return switch (type) {
            case PLUS        -> BinaryOp.ADD;
            case MINUS       -> BinaryOp.SUB;
            case STAR        -> BinaryOp.MUL;
            case SLASH       -> BinaryOp.DIV;

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
