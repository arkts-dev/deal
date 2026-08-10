package deal.test;

import deal.lexer.Lexer;
import deal.lexer.LexResult;
import deal.parser.Parser;
import deal.parser.ParseResult;
import deal.ast.*;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tests that all .d.deal declaration files parse without errors
 * and contain no executable statements.
 *
 * <p>Verifies ISSUE-0009 requirement: each .d.deal file is parseable
 * and type information is extractable.
 */
public class StdlibDeclParseTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    private static final String[] DECL_FILES = {
        "std/console.d.deal",
        "std/string.d.deal",
        "std/table.d.deal",
        "std/json.d.deal",
        "std/math.d.deal",
        "std/time.d.deal",
        "std/io.d.deal",
        "std/coroutine.d.deal"
    };

    public static void main(String[] args) throws Exception {
        System.out.println("=== Stdlib .d.deal Declaration Parse Tests ===\n");

        for (String declFile : DECL_FILES) {
            testParseDeclFile(declFile);
        }

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testParseDeclFile(String declFile) throws Exception {
        System.out.println("-- Parsing: " + declFile + " --");

        Path file = Path.of(declFile);
        if (!Files.exists(file)) {
            check(false, declFile + ": file not found");
            return;
        }

        String source = Files.readString(file);

        // Test 1: Lexing succeeds
        LexResult lex = new Lexer(source, declFile).tokenize();
        boolean hasLexErrors = lex.diagnostics().stream()
            .anyMatch(d -> "error".equals(d.severity()));
        check(!hasLexErrors, declFile + ": lexing succeeds");
        if (hasLexErrors) {
            lex.diagnostics().forEach(d ->
                System.err.println("  Lex error: " + d.code() + ": " + d.message()));
            return; // Can't parse if lexing fails
        }

        // Test 2: Parsing succeeds
        Parser parser = new Parser(lex.tokens(), declFile);
        ParseResult parse = parser.parse();
        check(!parse.hasErrors(), declFile + ": parsing succeeds");
        if (parse.hasErrors()) {
            parse.diagnostics().forEach(d ->
                System.err.println("  Parse error: " + d.code() + ": " + d.message()));
            return; // Can't verify statements if parsing fails
        }

        // Test 3: No executable statements in .d.deal files
        ProgramNode program = parse.program();
        boolean hasExecutable = false;
        for (StatementNode stmt : program.statements()) {
            if (hasExecutableStatement(stmt)) {
                hasExecutable = true;
                System.err.println("  Found executable statement: " + stmt.getClass().getSimpleName());
            }
        }
        check(!hasExecutable, declFile + ": no executable statements");

        // Test 4: Contains at least one export
        boolean hasExports = program.statements().stream()
            .anyMatch(s -> s instanceof ExportDeclaration);
        check(hasExports, declFile + ": contains export declarations");

        // Test 5: Verify export function declarations have no bodies
        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof ExportDeclaration exp) {
                if (exp.declaration() instanceof FunctionDeclaration fd) {
                    check(fd.body() == null || fd.body().statements().isEmpty(),
                        declFile + ": function '" + fd.name() + "' has no body (semicolon-ended)");
                }
            }
        }
    }

    /**
     * Returns true if the statement is an executable statement (not a
     * declaration, export, or import).
     */
    private static boolean hasExecutableStatement(StatementNode stmt) {
        return switch (stmt) {
            case ExportDeclaration exp -> hasExecutableStatement(exp.declaration());
            case FunctionDeclaration fd -> fd.body() != null && !fd.body().statements().isEmpty();
            case ClassDeclaration cd -> false;
            case ImportDeclaration id -> false;
            // These are executable statements that should NOT appear in .d.deal:
            case VariableDeclaration vd -> true;
            case ExpressionStatement es -> true;
            case ReturnStatement rs -> true;
            case IfStatement is -> true;
            case ForStatement fs -> true;
            case WhileStatement ws -> true;
            case ThrowStatement ts -> true;
            case TryStatement ts -> true;
            case DeleteStatement ds -> true;
            case BreakStatement bs -> true;
            case ContinueStatement cs -> true;
            default -> {
                // Block should not be at the top level of a .d.deal
                if (stmt instanceof Block b) {
                    for (StatementNode s : b.statements()) {
                        if (hasExecutableStatement(s)) yield true;
                    }
                }
                yield false;
            }
        };
    }
}
