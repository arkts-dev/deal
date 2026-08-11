package test;

import deal.checker.CheckerOutput;
import static test.Assertions.*;

/**
 * Tests for the semantic checker (TypeChecker), covering type errors,
 * name resolution errors, and related diagnostics produced during
 * compilation.  Each test method compiles a small DEAL snippet via
 * {@code checkProgram} and then asserts either that expected error
 * codes appear or that no errors occur.
 *
 * <p><b>How to run</b>: this file is invoked by {@code run_tests.sh}
 * through the Gradle test task.  Each {@code static void test…()}
 * method is executed via Java reflection from the test harness.</p>
 *
 * <p>Error codes exercised here (non-exhaustive):
 *   E2001, E2003, E2005, E2006, E2008,
 *   E3001-E3005, E3007-E3009, E3011-E3016,
 *   E4001, E4002, E4004-E4006,
 *   E5001
 * </p>
 */
public final class CheckerTest {

    // =========================================================================
    // E3002 — Duplicate symbol
    // =========================================================================

    static void testDuplicateClass() {
        System.out.println("-- Duplicate class --");
        CheckerOutput out = checkProgram(
            "class X { field: int }\n" +
            "class X { other: string }"
        );
        assertError(out, "E3002", "duplicate class");
    }

    static void testDuplicateFunction() {
        System.out.println("-- Duplicate function --");
        CheckerOutput out = checkProgram(
            "function f(): null { return null; }\n" +
            "function f(): null { return null; }"
        );
        assertError(out, "E3002", "duplicate function");
    }

    static void testDuplicateVariable() {
        System.out.println("-- Duplicate variable --");
        CheckerOutput out = checkProgram(
            "let x: int = 1;\n" +
            "let x: string = \"hello\";"
        );
        assertError(out, "E3002", "duplicate variable");
    }

    // =========================================================================
    // E3003 — Type mismatch in let declaration
    // =========================================================================

    static void testLetTypeMismatch() {
        System.out.println("-- Let type mismatch --");
        CheckerOutput out = checkProgram(
            "let x: int = \"hello\";"
        );
        assertError(out, "E3003", "let type mismatch");
    }

    // =========================================================================
    // E3004 — Unknown type
    // =========================================================================

    static void testUnknownType() {
        System.out.println("-- Unknown type --");
        CheckerOutput out = checkProgram(
            "let x: Foobar = 42;"
        );
        assertError(out, "E3004", "unknown type Foobar");
    }

    // =========================================================================
    // E2001 — Unresolved name
    // =========================================================================

    static void testUnresolvedName() {
        System.out.println("-- Unresolved name --");
        CheckerOutput out = checkProgram(
            "let x: int = y;"
        );
        assertError(out, "E2001", "unresolved name y");
    }

    // =========================================================================
    // E3001 — Type mismatch in return
    // =========================================================================

    static void testReturnTypeMismatch() {
        System.out.println("-- Return type mismatch --");
        CheckerOutput out = checkProgram(
            "function f(): int { return \"hello\"; }"
        );
        assertError(out, "E3001", "return type mismatch");
    }

    static void testReturnWithoutExpressionWhenNullExpected() {
        System.out.println("-- Return without expression when null expected --");
        CheckerOutput out = checkProgram(
            "function f(): null { return; }"
        );
        assertNoErrors(out, "return without expression for null return type");
    }

    // =========================================================================
    // E3005 — Break outside loop
    // =========================================================================

    static void testBreakOutsideLoop() {
        System.out.println("-- Break outside loop --");
        CheckerOutput out = checkProgram(
            "function f(): null { break; return null; }"
        );
        assertError(out, "E3005", "break outside loop");
    }

    // =========================================================================
    // E3005 — Continue outside loop
    // =========================================================================

    static void testContinueOutsideLoop() {
        System.out.println("-- Continue outside loop --");
        CheckerOutput out = checkProgram(
            "function f(): null { continue; return null; }"
        );
        assertError(out, "E3005", "continue outside loop");
    }

    // =========================================================================
    // Break and continue inside while loops (no error)
    // =========================================================================

    static void testBreakInsideWhile() {
        System.out.println("-- Break inside while --");
        CheckerOutput out = checkProgram(
            "function f(): null { while (true) { break; } return null; }"
        );
        assertNoErrors(out, "break inside while");
    }

    static void testContinueInsideWhile() {
        System.out.println("-- Continue inside while --");
        CheckerOutput out = checkProgram(
            "function f(): null { while (true) { continue; } return null; }"
        );
        assertNoErrors(out, "continue inside while");
    }

    // =========================================================================
    // E3011 — If condition not boolean
    // =========================================================================

    static void testIfConditionNotBoolean() {
        System.out.println("-- If condition not boolean --");
        CheckerOutput out = checkProgram(
            "function f(): null { if (42) { return null; } return null; }"
        );
        assertError(out, "E3011", "if condition not boolean");
    }

    // =========================================================================
    // E3011 — While condition not boolean
    // =========================================================================

    static void testWhileConditionNotBoolean() {
        System.out.println("-- While condition not boolean --");
        CheckerOutput out = checkProgram(
            "function f(): null { while (42) { } return null; }"
        );
        assertError(out, "E3011", "while condition not boolean");
    }

    // =========================================================================
    // E3007 — Not a function
    // =========================================================================

    static void testNotAFunction() {
        System.out.println("-- Not a function --");
        CheckerOutput out = checkProgram(
            "let x: int = 42;\n" +
            "let y: int = x();"
        );
        assertError(out, "E3007", "not a function");
    }

    // =========================================================================
    // E3008 — Wrong number of arguments
    // =========================================================================

    static void testWrongNumberOfArguments() {
        System.out.println("-- Wrong number of arguments --");
        CheckerOutput out = checkProgram(
            "function f(a: int): int { return a; }\n" +
            "let x: int = f();"
        );
        assertError(out, "E3008", "wrong number of arguments (too few)");
    }

    static void testWrongNumberOfArgumentsTooMany() {
        System.out.println("-- Wrong number of arguments (too many) --");
        CheckerOutput out = checkProgram(
            "function f(a: int): int { return a; }\n" +
            "let x: int = f(1, 2);"
        );
        assertError(out, "E3008", "wrong number of arguments (too many)");
    }

    // =========================================================================
    // E3009 — Argument type mismatch
    // =========================================================================

    static void testArgumentTypeMismatch() {
        System.out.println("-- Argument type mismatch --");
        CheckerOutput out = checkProgram(
            "function f(a: int): int { return a; }\n" +
            "let x: int = f(\"hello\");"
        );
        assertError(out, "E3009", "argument type mismatch");
    }

    // =========================================================================
    // E3012 — Cannot assign to immutable variable
    // =========================================================================

    static void testAssignToImmutable() {
        System.out.println("-- Assign to immutable --");
        CheckerOutput out = checkProgram(
            "function f(): null { let x: int = 1; x = 2; return null; }"
        );
        assertError(out, "E3012", "assign to immutable variable");
    }

    // =========================================================================
    // E3007 — Cannot call non-function expression
    //   (exercises the function-expr path that calls checkFunctionExpr)
    // =========================================================================

    static void testCallNonFunctionExpression() {
        System.out.println("-- Call non-function expression --");
        CheckerOutput out = checkProgram(
            "let c: int = 0;\n" +
            "let result: int = c();"
        );
        assertError(out, "E3007", "cannot call non-function (int variable)");
    }

    // =========================================================================
    // Type-checking expressions (no errors expected)
    // =========================================================================

    static void testClassFieldAccess() {
        System.out.println("-- Class field access --");
        CheckerOutput out = checkProgram(
            "class Point { x: int, y: int }\n" +
            "function f(p: Point): int { return p.x; }"
        );
        assertNoErrors(out, "class field access");
    }

    static void testAssignToClassField() {
        System.out.println("-- Assign to class field --");
        CheckerOutput out = checkProgram(
            "class Point { x: int, y: int }\n" +
            "function f(p: Point): null { p.x = 5; return null; }"
        );
        assertNoErrors(out, "assign to class field");
    }

    static void testClassFieldWrongType() {
        System.out.println("-- Class field wrong type --");
        CheckerOutput out = checkProgram(
            "class Point { x: int }\n" +
            "function f(p: Point): string { return p.x; }"
        );
        assertError(out, "E3001", "class field wrong type (int assigned to string)");
    }

    static void testLogicalOperators() {
        System.out.println("-- Logical operators --");
        CheckerOutput out = checkProgram(
            "function f(): boolean { return true || false; }"
        );
        assertNoErrors(out, "logical operator ||");
        out = checkProgram(
            "function f(): boolean { return true && false; }"
        );
        assertNoErrors(out, "logical operator &&");
        out = checkProgram(
            "function f(): boolean { return !true; }"
        );
        assertNoErrors(out, "unary !");
    }

    static void testLogicalOperatorsBadOperand() {
        System.out.println("-- Logical operators with non-boolean operands --");
        CheckerOutput out = checkProgram(
            "function f(): boolean { return 1 || true; }"
        );
        assertError(out, "E3011", "|| with non-boolean left");
        out = checkProgram(
            "function f(): boolean { return true && 2; }"
        );
        assertError(out, "E3011", "&& with non-boolean right");
        out = checkProgram(
            "function f(): boolean { return !1; }"
        );
        assertError(out, "E3011", "! with non-boolean");
    }

    static void testComparisonOperators() {
        System.out.println("-- Comparison operators --");
        CheckerOutput out = checkProgram(
            "function f(): boolean { return 1 < 2; }"
        );
        assertNoErrors(out, "comparison <");
        out = checkProgram(
            "function f(): boolean { return 1 > 2; }"
        );
        assertNoErrors(out, "comparison >");
        out = checkProgram(
            "function f(): boolean { return 1 <= 2; }"
        );
        assertNoErrors(out, "comparison <=");
        out = checkProgram(
            "function f(): boolean { return 1 >= 2; }"
        );
        assertNoErrors(out, "comparison >=");
        out = checkProgram(
            "function f(): boolean { return 1 == 2; }"
        );
        assertNoErrors(out, "comparison ==");
        out = checkProgram(
            "function f(): boolean { return 1 != 2; }"
        );
        assertNoErrors(out, "comparison !=");
    }

    static void testArithmeticOperators() {
        System.out.println("-- Arithmetic operators --");
        CheckerOutput out = checkProgram(
            "function f(): int { return 1 + 2; }"
        );
        assertNoErrors(out, "int addition");
        out = checkProgram(
            "function f(): int { return 1 - 2; }"
        );
        assertNoErrors(out, "int subtraction");
        out = checkProgram(
            "function f(): int { return 1 * 2; }"
        );
        assertNoErrors(out, "int multiplication");
        out = checkProgram(
            "function f(): int { return 1 / 2; }"
        );
        assertNoErrors(out, "int division");
        out = checkProgram(
            "function f(): number { return 1.5 + 2.5; }"
        );
        assertNoErrors(out, "number addition");
    }

    static void testArithmeticWithStrings() {
        System.out.println("-- Arithmetic with strings --");
        CheckerOutput out = checkProgram(
            "function f(): int { return \"hello\" + \"world\"; }"
        );
        assertError(out, "E3011", "string + string");
    }

    static void testNullableReturnWithExpression() {
        System.out.println("-- Nullable return with expression --");
        CheckerOutput out = checkProgram(
            "function f(): int | null { return 42; }"
        );
        assertNoErrors(out, "nullable return with matching expression");
    }

    static void testNullableReturnWithNullLiteral() {
        System.out.println("-- Nullable return with null literal --");
        CheckerOutput out = checkProgram(
            "function f(): int | null { return null; }"
        );
        assertNoErrors(out, "nullable return with null literal");
    }

    static void testNullableReturnTypeMismatch() {
        System.out.println("-- Nullable return type mismatch --");
        CheckerOutput out = checkProgram(
            "function f(): int | null { return \"hello\"; }"
        );
        assertError(out, "E3001", "nullable return type mismatch");
    }

    static void testNullableArgumentPassing() {
        System.out.println("-- Nullable argument passing --");
        CheckerOutput out = checkProgram(
            "function f(x: int | null): int { return 0; }\n" +
            "let r: int = f(null);"
        );
        assertNoErrors(out, "passing null to nullable param");
    }

    static void testNonOptionalArgWithNullLiteral() {
        System.out.println("-- Non-optional arg with null literal --");
        CheckerOutput out = checkProgram(
            "function f(x: int): int { return x; }\n" +
            "let r: int = f(null);"
        );
        assertError(out, "E3009", "passing null to non-nullable param");
    }

    static void testAssignNullToNonNullableField() {
        System.out.println("-- Assign null to non-nullable field --");
        CheckerOutput out = checkProgram(
            "class Point { x: int }\n" +
            "function f(p: Point): null { p.x = null; return null; }"
        );
        assertError(out, "E3013", "assign null to non-nullable field");
    }

    static void testAssignNullToNonNullableLet() {
        System.out.println("-- Assign null to non-nullable let --");
        CheckerOutput out = checkProgram(
            "let x: int = null;"
        );
        assertError(out, "E3003", "let x: int = null should fail");
    }

    static void testAssignNullToLocalVariable() {
        System.out.println("-- Assign null to local variable --");
        CheckerOutput out = checkProgram(
            "function f(): null { let x: int = 1; x = null; return null; }"
        );
        assertError(out, "E3013", "assign null to non-nullable local");
    }

    static void testNullableUnionWithNullable() {
        System.out.println("-- Nullable union with nullable --");
        CheckerOutput out = checkProgram(
            "function f(x: int | null | null): int { return 0; }"
        );
        assertNoErrors(out, "nullable | null should flatten");
    }

    // =========================================================================
    // E4001 — Field not found
    // =========================================================================

    static void testFieldNotFound() {
        System.out.println("-- Field not found --");
        CheckerOutput out = checkProgram(
            "class Point { x: int }\n" +
            "function f(p: Point): int { return p.z; }"
        );
        assertError(out, "E4001", "field not found");
    }

    static void testFieldNotFoundOnNonClass() {
        System.out.println("-- Field not found on non-class --");
        CheckerOutput out = checkProgram(
            "let x: int = 42;\n" +
            "let y: int = x.foo;"
        );
        assertError(out, "E4001", "field access on non-class (int)");
    }

    // =========================================================================
    // E4002 — Method not found
    // =========================================================================

    static void testMethodNotFound() {
        System.out.println("-- Method not found --");
        CheckerOutput out = checkProgram(
            "class Point { x: int }\n" +
            "function f(p: Point): int { return p.distance(1, 2); }"
        );
        assertError(out, "E4002", "method not found");
    }

    // =========================================================================
    // E5001 — Missing return
    // =========================================================================

    static void testMissingReturnNonVoid() {
        System.out.println("-- Missing return (non-null) --");
        CheckerOutput out = checkProgram(
            "function f(): int { let x: int = 1; }"
        );
        assertError(out, "E5001", "missing return in non-null function");
    }

    static void testMissingReturnInBranching() {
        System.out.println("-- Missing return in branching --");
        // Returns null in if-block but not after the if
        CheckerOutput out = checkProgram(
            "function f(): int { if (true) { return 1; } }"
        );
        assertError(out, "E5001", "missing return after if without else");
    }

    static void testMissingReturnVoid() {
        System.out.println("-- Missing return (null) — no error --");
        // Function returning null doesn't need a return statement
        CheckerOutput out = checkProgram(
            "function f(): null { let x: int = 1; }"
        );
        assertNoErrors(out, "missing return for null return type is ok");
    }

    // =========================================================================
    // Rest parameter type checking
    // =========================================================================

    static void testRestParameterValid() {
        System.out.println("-- Rest parameter valid --");
        CheckerOutput out = checkProgram(
            "function sum(nums: ...int): int { return 0; }"
        );
        assertNoErrors(out, "rest parameter valid");
    }

    static void testRestArgumentMismatch() {
        System.out.println("-- Rest argument type mismatch --");
        CheckerOutput out = checkProgram(
            "function sum(nums: ...int): int { return 0; }\n" +
            "let s: int = sum(\"hello\");"
        );
        assertError(out, "E3009", "rest argument type mismatch");
    }

    static void testRestArgumentMultipleCorrect() {
        System.out.println("-- Rest argument multiple correct --");
        CheckerOutput out = checkProgram(
            "function sum(nums: ...int): int { return 0; }\n" +
            "let s: int = sum(1, 2, 3, 4);"
        );
        assertNoErrors(out, "rest argument multiple correct");
    }

    // =========================================================================
    // Array literal type checking
    // =========================================================================

    static void testArrayLiteralSameType() {
        System.out.println("-- Array literal same type --");
        CheckerOutput out = checkProgram(
            "let xs: int[] = [1, 2, 3];"
        );
        assertNoErrors(out, "array literal ints");
    }

    static void testArrayLiteralMixedTypesError() {
        System.out.println("-- Array literal mixed types error --");
        CheckerOutput out = checkProgram(
            "let xs: int[] = [1, \"hello\"];"
        );
        assertError(out, "E3003", "array literal mixed types");
    }

    static void testArrayLiteralEmptyOK() {
        System.out.println("-- Array literal empty OK --");
        CheckerOutput out = checkProgram(
            "let xs: int[] = [];"
        );
        assertNoErrors(out, "empty array literal");
    }

    // =========================================================================
    // Index expression type checking
    // =========================================================================

    static void testIndexOnString() {
        System.out.println("-- Index on string --");
        CheckerOutput out = checkProgram(
            "function f(s: string): string { return s[0]; }"
        );
        assertNoErrors(out, "index on string");
    }

    static void testIndexOnStringNonIntError() {
        System.out.println("-- Index on string with non-int --");
        CheckerOutput out = checkProgram(
            "function f(s: string): string { return s[\"hello\"]; }"
        );
        assertError(out, "E3011", "string index with non-int");
    }

    static void testIndexOnArray() {
        System.out.println("-- Index on array --");
        CheckerOutput out = checkProgram(
            "function f(xs: int[]): int { return xs[0]; }"
        );
        assertNoErrors(out, "index on int array");
    }

    static void testIndexOnArrayWrongType() {
        System.out.println("-- Index on array wrong type --");
        CheckerOutput out = checkProgram(
            "function f(xs: int[]): int { return xs[\"hello\"]; }"
        );
        assertError(out, "E3011", "array index with non-int");
    }

    static void testIndexOnNonIndexable() {
        System.out.println("-- Index on non-indexable --");
        CheckerOutput out = checkProgram(
            "function f(x: int): int { return x[0]; }"
        );
        assertError(out, "E3010", "index on int");
    }

    // =========================================================================
    // E2006 — Delete on non-table
    // =========================================================================

    static void testDeleteOnNonTable() {
        System.out.println("-- Delete on non-table --");
        CheckerOutput out = checkProgram(
            "let x: int = 1;\n" +
            "delete x.foo;"
        );
        assertError(out, "E2006", "delete on non-table (int)");
    }

    // =========================================================================
    // E2005 — Has on non-table
    // =========================================================================

    static void testHasOnNonTable() {
        System.out.println("-- Has on non-table --");
        CheckerOutput out = checkProgram(
            "let x: int = 1;\n" +
            "let y: boolean = has x.foo;"
        );
        assertError(out, "E2005", "has on non-table (int)");
    }

    // =========================================================================
    // E3014 — Delete on a non-table field
    // =========================================================================

    static void testDeleteOnNonTableField() {
        System.out.println("-- Delete on non-table field --");
        CheckerOutput out = checkProgram(
            "let x: int = 1;\n" +
            "delete x;"
        );
        assertError(out, "E3014", "delete on non-table field (int)");
    }

    // =========================================================================
    // E3015 — For-of iterable type check (from ISSUE-0034)
    // =========================================================================

    static void testForOfTypeCheck_nonIterable() {
        System.out.println("-- For-of Type Check: non-iterable -> E3015 --");
        CheckerOutput out = checkProgram(
            "function f(b: boolean): null {\n" +
            "  for (let x: int of b) {\n" +
            "    let y: int = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(out, "E3015", "for-of over boolean -> E3015");
    }

    static void testForOfTypeCheck_arrayWrongVarType() {
        System.out.println("-- For-of Type Check: array wrong var type -> E3015 --");
        CheckerOutput out = checkProgram(
            "function f(xs: int[]): null {\n" +
            "  for (let x: string of xs) {\n" +
            "    let y: string = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(out, "E3015", "for-of over int[] with string var -> E3015");
    }

    // =========================================================================
    // E3016 — Template expression not string
    // =========================================================================

    static void testTemplateLiteral_nonStringInterpolation() {
        System.out.println("-- Template Literal: non-string interpolation -> E3016 --");
        CheckerOutput out = checkProgram(
            "function f(n: int): string {\n" +
            "  return `Value: ${n}`;\n" +
            "}"
        );
        assertError(out, "E3016", "template literal with int interpolation -> E3016");
    }

    // =========================================================================
    // E4004 — Try without catch or finally
    // =========================================================================

    static void testTryWithoutCatchOrFinally() {
        System.out.println("-- Try without catch or finally --");
        CheckerOutput out = checkProgram(
            "function f(): null { try { return null; } return null; }"
        );
        assertError(out, "E4004", "try without catch or finally");
    }

    // =========================================================================
    // E4005 — Catch variable type must be Error or Error | null
    // =========================================================================

    static void testCatchVariableNotError() {
        System.out.println("-- Catch variable not Error --");
        CheckerOutput out = checkProgram(
            "function f(): null { try { return null; } catch (e: string) { return null; } return null; }"
        );
        assertError(out, "E4005", "catch variable not Error type");
    }

    static void testCatchVariableErrorOrNull() {
        System.out.println("-- Catch variable Error | null --");
        CheckerOutput out = checkProgram(
            "function f(): null { try { return null; } catch (e: Error | null) { return null; } return null; }"
        );
        assertNoErrors(out, "catch variable Error | null is valid");
    }

    // =========================================================================
    // E3013 — Throw non-Error
    // =========================================================================

    static void testThrowNonError() {
        System.out.println("-- Throw non-Error --");
        CheckerOutput out = checkProgram(
            "function f(): null { throw \"oops\"; return null; }"
        );
        assertError(out, "E3013", "throw non-Error (string)");
    }

    static void testThrowError() {
        System.out.println("-- Throw Error --");
        CheckerOutput out = checkProgram(
            "function f(): null { throw null; return null; }"
        );
        assertNoErrors(out, "throw null (Error) is valid");
    }

    // =========================================================================
    // E2001 — Catch variable not declared
    // =========================================================================

    static void testCatchVariableE2001() {
        System.out.println("-- Catch variable E2001 --");
        CheckerOutput out = checkProgram(
            "function f(): null { try { return null; } catch (x: Error) { return null; } return null; }"
        );
        assertError(out, "E2001", "catch variable not declared");
    }

    // =========================================================================
    // ISSUE-0017: void as type name produces E3004 (unknown type)
    // =========================================================================

    static void testVoidTypeNameProducesE3004() {
        System.out.println("-- ISSUE-0017: void as type name produces E3004 (unknown type) --");

        // Test 1: void as function return type produces E3004
        CheckerOutput out = checkProgram(
            "function f(): void { return; }"
        );
        assertError(out, "E3004", "void as function return type produces E3004");

        // Test 2: void as parameter type produces E3004
        out = checkProgram(
            "function f(x: void): null { return null; }"
        );
        assertError(out, "E3004", "void as parameter type produces E3004");
    }

    // =========================================================================
    // ISSUE-0017: coroutine as type name produces E3004 (unknown type)
    // =========================================================================

    static void testCoroutineTypeNameProducesE3004() {
        System.out.println("-- ISSUE-0017: coroutine as type name produces E3004 (unknown type) --");

        // Test 1: coroutine as function return type produces E3004
        CheckerOutput out = checkProgram(
            "function f(): coroutine { return null; }"
        );
        assertError(out, "E3004", "coroutine as function return type produces E3004");

        // Test 2: coroutine as variable type produces E3004
        out = checkProgram(
            "let c: coroutine = null;"
        );
        assertError(out, "E3004", "coroutine as variable type produces E3004");
    }

    // =========================================================================
    // ISSUE-0008: E6003 — coroutine import rejection
    // =========================================================================

    // ISSUE-0040: E6003 retired — coroutine import now fails with E2003
    // =========================================================================

    static void testCoroutineImportRejected() {
        System.out.println("-- ISSUE-0040: coroutine import produces E2003 (module not found) --");

        // Test 1: import * as c from "std/coroutine" should produce E2003
        CheckerOutput out = checkProgram(
            "import * as c from \"std/coroutine\";"
        );
        assertError(out, "E2003", "coroutine import produces E2003 (module not found)");

        // Test 2: import alongside other code — E2003 still fires.
        out = checkProgram(
            "import * as c from \"std/coroutine\";\n" +
            "let x: int = 42;"
        );
        assertError(out, "E2003", "coroutine import with other code still produces E2003");

        // Test 3: use of c.resumeInt after failed import — E2003 fires first.
        out = checkProgram(
            "import * as c from \"std/coroutine\";\n" +
            "let x: int = c.resumeInt(null);"
        );
        assertError(out, "E2003", "coroutine import with c.resumeInt usage produces E2003");
    }


    // =================================================================
    // v1.1: For-of Scoping Tests (ISSUE-0034)
    // =================================================================

    static void testForOfScoping_loopVarInBody() {
        System.out.println("-- For-of Scoping: loop var in body --");
        CheckerOutput out = checkProgram(
            "function f(xs: int[]): null {\n" +
            "  for (let x: int of xs) {\n" +
            "    let y: int = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(out, "loop variable visible in for-of body");
    }

    static void testForOfScoping_iterableCannotRefLoopVar() {
        System.out.println("-- For-of Scoping: iterable cannot ref loop var --");
        // The iterable x should resolve to outer scope. If there is no outer x, E2001.
        CheckerOutput out = checkProgram(
            "function f(): null {\n" +
            "  for (let x: int of x) {\n" +
            "    let y: int = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(out, "E2001", "iterable x should not see loop variable (E2001)");
    }

    static void testForOfScoping_iterableRefsOuterVar() {
        System.out.println("-- For-of Scoping: iterable refs outer var --");
        // The iterable x should resolve to the outer x (the parameter).
        CheckerOutput out = checkProgram(
            "function f(x: int[]): null {\n" +
            "  for (let x: int of x) {\n" +
            "    let y: int = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(out, "iterable x resolves to outer parameter");
    }

    static void testForOfScoping_breakInside() {
        System.out.println("-- For-of Scoping: break inside --");
        CheckerOutput out = checkProgram(
            "function f(xs: int[]): null {\n" +
            "  for (let x: int of xs) {\n" +
            "    break;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(out, "break inside for-of");
    }

    static void testForOfScoping_continueInside() {
        System.out.println("-- For-of Scoping: continue inside --");
        CheckerOutput out = checkProgram(
            "function f(xs: int[]): null {\n" +
            "  for (let x: int of xs) {\n" +
            "    continue;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(out, "continue inside for-of");
    }

    // ================================================================="
    // v1.1: For-of Type Checking Tests (ISSUE-0034)
    // ================================================================="

    static void testForOfTypeCheck_arrayCorrect() {
        System.out.println("-- For-of Type Check: array correct --");
        CheckerOutput out = checkProgram(
            "function f(xs: int[]): null {\n" +
            "  for (let x: int of xs) {\n" +
            "    let y: int = x;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(out, "for-of over int[] with int loop var");
    }

    static void testForOfTypeCheck_stringCorrect() {
        System.out.println("-- For-of Type Check: string correct --");
        CheckerOutput out = checkProgram(
            "function f(s: string): null {\n" +
            "  for (let c: string of s) {\n" +
            "    let d: string = c;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertNoErrors(out, "for-of over string with string loop var");
    }

        );
        assertError(out, "E3015", "for-of over boolean -> E3015");
    }

        );
        assertError(out, "E3015", "for-of over int[] with string var -> E3015");
    }

    static void testForOfTypeCheck_stringWrongVarType() {
        System.out.println("-- For-of Type Check: string wrong var type -> E3015 --");
        CheckerOutput out = checkProgram(
            "function f(s: string): null {\n" +
            "  for (let c: int of s) {\n" +
            "    let d: int = c;\n" +
            "  }\n" +
            "  return null;\n" +
            "}"
        );
        assertError(out, "E3015", "for-of over string with int var -> E3015");
    }

    // ================================================================="
    // v1.1: Template Literal Type Checking Tests (ISSUE-0034)
    // ================================================================="

    static void testTemplateLiteral_stringParts() {
        System.out.println("-- Template Literal: all string parts --");
        CheckerOutput out = checkProgram(
            "function f(name: string): string {\n" +
            "  return `Hello ${name}`;\n" +
            "}"
        );
        assertNoErrors(out, "template literal with string interpolation");
    }

        );
        assertError(out, "E3016", "template literal with int interpolation -> E3016");
    }

    static void testTemplateLiteral_typeInference() {
        System.out.println("-- Template Literal: type inference (D15) --");
        // let without :string annotation should infer string
        CheckerOutput out = checkProgram(
            "function f(name: string): void {\n" +
            "  let msg = `Hello ${name}`;\n" +
            "  let s: string = msg;\n" +
            "}"
        );
        assertNoErrors(out, "template literal infers string type");
    }

    static void testTemplateLiteral_nameResolution() {
        System.out.println("-- Template Literal: name resolution walks parts --");
        // FunctionExpr inside template interpolation should be found by NameResolver
        CheckerOutput out = checkProgram(
            "function f(): string {\n" +
            "  let g = function(): string { return \"world\"; };\n" +
            "  return `Hello ${g()}`;\n" +
            "}"
        );
        assertNoErrors(out, "template literal parts walked for name resolution");
    }

    // =========================================================================
    // ISSUE-0040: Error class prohibition (E4006)
    // =========================================================================

    static void testE4006_classErrorProhibition() {
        System.out.println("-- ISSUE-0040: class Error prohibition (E4006) --");

        // Test 1: module-level class Error should produce E4006
        CheckerOutput out = checkProgram(
            "class Error { code: string, message: string }"
        );
        assertError(out, "E4006", "module-level class Error produces E4006");

        // Test 2: exported class Error should produce E4006
        out = checkProgram(
            "export class Error { code: string, message: string }"
        );
        assertError(out, "E4006", "exported class Error produces E4006");

        // Test 3: nested class Error inside a function should produce E4006
        out = checkProgram(
            "function f() { class Error { code: string, message: string } }"
        );
        assertError(out, "E4006", "nested class Error inside function produces E4006");
    }

    // =========================================================================
    // ISSUE-0040: Dollar prohibition (E2008)
    // =========================================================================

    static void testE2008_dollarInIdentifier() {
        System.out.println("-- ISSUE-0040: dollar prohibition (E2008) --");

        // Test 1: dollar in class name
        CheckerOutput out = checkProgram(
            "class Foo$Bar { }"
        );
        assertError(out, "E2008", "dollar in class name produces E2008");

        // Test 2: dollar in function name
        out = checkProgram(
            "function foo$bar(): null { return null; }"
        );
        assertError(out, "E2008", "dollar in function name produces E2008");

        // Test 3: dollar in let variable name
        out = checkProgram(
            "let my$var: int = 42;"
        );
        assertError(out, "E2008", "dollar in let variable name produces E2008");

        // Test 4: dollar in parameter name
        out = checkProgram(
            "function f(param$name: int): null { return null; }"
        );
        assertError(out, "E2008", "dollar in parameter name produces E2008");

        // Test 5: dollar in import alias
        out = checkProgram(
            "import * as mod$name from \"./lib\";"
        );
        assertError(out, "E2008", "dollar in import alias produces E2008");

        // Test 6: dollar in nested class name
        out = checkProgram(
            "function f() { class Nested$Class { } }"
        );
        assertError(out, "E2008", "dollar in nested class name produces E2008");

        // Test 7: dollar in class field name (module-level class)
        out = checkProgram(
            "class Foo { bar$baz: int }"
        );
        assertError(out, "E2008", "dollar in class field name produces E2008");

        // Test 8: dollar in class field name (nested class)
        out = checkProgram(
            "function f() { class Foo { bar$baz: int } }"
        );
        assertError(out, "E2008", "dollar in nested class field name produces E2008");
    }


}
