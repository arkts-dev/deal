package deal.test;

import deal.semantic.ir.ScalarValue;
import deal.semantic.ir.SemanticArray;
import deal.semantic.ir.SemanticTable;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies the ISSUE-0383 component C2 surface: {@link SemanticTable} as
 * the closed first-insertion-order table of the semantic value model and
 * {@link SemanticArray} as the closed ordered array value — the
 * value-model primitives the executor (C3), the future oracle (E11), and
 * the {@code table.keys} contract consume (ISSUE-0232 design D4/D6;
 * parent D8 and the stdlib {@code table keys} row).
 *
 * <p>Pinned sequences (wiki Verification 2 and the task criteria):
 * <ol>
 *   <li>Insert {@code a, b, c} → keys {@code [a, b, c]}.</li>
 *   <li>Overwrite {@code b} keeps slot order {@code [a, b, c]} with the
 *       new value (overwrite never moves a key).</li>
 *   <li>Delete {@code b} then reinsert {@code b} yields {@code [a, c, b]}
 *       (delete removes the slot; reinsertion appends).</li>
 *   <li>Duplicate-key put keeps the first position with the last value
 *       (duplicate literal keys: later value wins, first source
 *       position).</li>
 *   <li>{@code get} distinguishes a present null (the closed
 *       {@link ScalarValue.Null}) from a missing key
 *       ({@link SemanticTable.Lookup.Missing}).</li>
 *   <li>The array model preserves element order and reports the signed32
 *       count; every allocation carries a fresh identity.</li>
 * </ol>
 *
 * <p>Additional fail-closed pins: null keys/values/elements are producer
 * defects ({@link NullPointerException}, never a DEAL projection);
 * out-of-range array element access is a {@link SemanticArray.Defect}
 * (the model performs no bounds enforcement — the executor applies the
 * pinned bounds policy before access); {@code keys()} and
 * {@code elements()} are immutable snapshots; order storage is linear in
 * the present-key count; repeated operations are deterministic.</p>
 */
public class SemanticTableTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    /** Pins a documented NullPointerException with the exact message. */
    private static void expectNpe(Runnable runnable, String message, String what) {
        try {
            runnable.run();
            fail("expected NullPointerException for " + what + ", but no exception was raised");
        } catch (NullPointerException expected) {
            check(message.equals(expected.getMessage()),
                what + " NPE message: expected [" + message + "], got ["
                    + expected.getMessage() + "]");
        } catch (Throwable other) {
            fail("expected NullPointerException for " + what + ", got "
                + other.getClass().getSimpleName() + ": " + other.getMessage());
        }
    }

    /** Accepts any NullPointerException (pinned by the JDK collection factories). */
    private static void expectAnyNpe(Runnable runnable, String what) {
        try {
            runnable.run();
            fail("expected NullPointerException for " + what + ", but no exception was raised");
        } catch (NullPointerException expected) {
            passed++;
        } catch (Throwable other) {
            fail("expected NullPointerException for " + what + ", got "
                + other.getClass().getSimpleName() + ": " + other.getMessage());
        }
    }

    private static void expectDefect(Runnable runnable, String what) {
        try {
            runnable.run();
            fail("expected SemanticArray.Defect for " + what + ", but no exception was raised");
        } catch (SemanticArray.Defect expected) {
            passed++;
        } catch (Throwable other) {
            fail("expected SemanticArray.Defect for " + what + ", got "
                + other.getClass().getSimpleName() + ": " + other.getMessage());
        }
    }

    private static void expectUnsupportedOp(Runnable runnable, String what) {
        try {
            runnable.run();
            fail("expected UnsupportedOperationException for " + what
                + ", but no exception was raised");
        } catch (UnsupportedOperationException expected) {
            passed++;
        } catch (Throwable other) {
            fail("expected UnsupportedOperationException for " + what + ", got "
                + other.getClass().getSimpleName() + ": " + other.getMessage());
        }
    }

    private static ScalarValue str(String s) {
        return new ScalarValue.String(s);
    }

    /** Builds a table holding the string scalars a, b, c in that order. */
    private static SemanticTable<ScalarValue> abcTable() {
        SemanticTable<ScalarValue> table = new SemanticTable<>();
        table.put("a", str("a"));
        table.put("b", str("b"));
        table.put("c", str("c"));
        return table;
    }

    // =========================================================================
    // Pinned order sequences
    // =========================================================================

    static void testInsertOrder() {
        System.out.println("-- pinned sequence: insert a, b, c -> keys [a, b, c] --");

        SemanticTable<ScalarValue> table = abcTable();
        check(table.keys().equals(List.of("a", "b", "c")),
            "insert a,b,c yields keys [a,b,c]; got " + table.keys());
        check(table.size() == 3,
            "insert a,b,c yields size 3; got " + table.size());
        switch (table.get("a")) {
            case SemanticTable.Lookup.Present<ScalarValue> present ->
                check(present.value().equals(str("a")), "get(a) is Present(\"a\")");
            case SemanticTable.Lookup.Missing<ScalarValue> ignored ->
                fail("get(a) after put(a) is Missing");
        }
    }

    static void testOverwriteKeepsSlot() {
        System.out.println("-- pinned sequence: overwrite b keeps slot order with the new value --");

        SemanticTable<ScalarValue> table = abcTable();
        table.put("b", str("B2"));
        check(table.keys().equals(List.of("a", "b", "c")),
            "overwrite b keeps slot order [a,b,c]; got " + table.keys());
        switch (table.get("b")) {
            case SemanticTable.Lookup.Present<ScalarValue> present ->
                check(present.value().equals(str("B2")),
                    "overwrite b stores the new value; got " + present.value());
            case SemanticTable.Lookup.Missing<ScalarValue> ignored ->
                fail("get(b) after overwrite is Missing");
        }
        switch (table.get("a")) {
            case SemanticTable.Lookup.Present<ScalarValue> present ->
                check(present.value().equals(str("a")),
                    "overwrite b leaves a unchanged");
            case SemanticTable.Lookup.Missing<ScalarValue> ignored ->
                fail("get(a) after overwrite b is Missing");
        }
        check(table.size() == 3,
            "overwrite never changes the key count; got " + table.size());
    }

    static void testDeleteThenReinsertMovesToEnd() {
        System.out.println("-- pinned sequence: delete b then reinsert b yields [a, c, b] --");

        SemanticTable<ScalarValue> table = abcTable();
        table.remove("b");
        check(table.keys().equals(List.of("a", "c")),
            "delete b removes the slot; got " + table.keys());
        check(table.size() == 2, "delete b shrinks size to 2; got " + table.size());
        switch (table.get("b")) {
            case SemanticTable.Lookup.Present<ScalarValue> present ->
                fail("get(b) after delete is Present(" + present.value() + ")");
            case SemanticTable.Lookup.Missing<ScalarValue> ignored ->
                check(true, "get(b) after delete is Missing");
        }
        table.put("b", str("B2"));
        check(table.keys().equals(List.of("a", "c", "b")),
            "delete b then reinsert b yields [a,c,b]; got " + table.keys());
        switch (table.get("b")) {
            case SemanticTable.Lookup.Present<ScalarValue> present ->
                check(present.value().equals(str("B2")),
                    "reinserted b stores the reinserted value; got " + present.value());
            case SemanticTable.Lookup.Missing<ScalarValue> ignored ->
                fail("get(b) after reinsert is Missing");
        }
    }

    static void testDuplicateKeyPut() {
        System.out.println("-- duplicate-key put keeps the first position with the last value --");

        SemanticTable<ScalarValue> table = abcTable();
        table.put("a", str("A2"));
        check(table.keys().equals(List.of("a", "b", "c")),
            "duplicate put(a) keeps the first position [a,b,c]; got " + table.keys());
        switch (table.get("a")) {
            case SemanticTable.Lookup.Present<ScalarValue> present ->
                check(present.value().equals(str("A2")),
                    "duplicate put(a) stores the last value; got " + present.value());
            case SemanticTable.Lookup.Missing<ScalarValue> ignored ->
                fail("get(a) after duplicate put is Missing");
        }
        check(table.size() == 3,
            "duplicate put never changes the key count; got " + table.size());
    }

    static void testPresentNullVsMissing() {
        System.out.println("-- get distinguishes present null from missing --");

        SemanticTable<ScalarValue> table = new SemanticTable<>();
        table.put("nullable", ScalarValue.Null.INSTANCE);
        switch (table.get("nullable")) {
            case SemanticTable.Lookup.Present<ScalarValue> present ->
                check(present.value() == ScalarValue.Null.INSTANCE,
                    "get(nullable) is Present(ScalarValue.Null.INSTANCE)");
            case SemanticTable.Lookup.Missing<ScalarValue> ignored ->
                fail("get(nullable) with a present null value is Missing");
        }
        switch (table.get("absent")) {
            case SemanticTable.Lookup.Present<ScalarValue> present ->
                fail("get(absent) is Present(" + present.value() + ")");
            case SemanticTable.Lookup.Missing<ScalarValue> ignored ->
                check(true, "get(absent) is Missing");
        }
        check(table.keys().equals(List.of("nullable")),
            "a present null key is a present key; got " + table.keys());
        check(table.size() == 1,
            "a present null value occupies a slot; got size " + table.size());
    }

    // =========================================================================
    // Remove and snapshot semantics
    // =========================================================================

    static void testRemoveSemantics() {
        System.out.println("-- remove semantics --");

        SemanticTable<ScalarValue> table = abcTable();
        table.remove("absent");
        check(table.keys().equals(List.of("a", "b", "c")) && table.size() == 3,
            "removing an absent key is a no-op; got " + table.keys());
        table.remove("b");
        table.remove("b");
        check(table.keys().equals(List.of("a", "c")) && table.size() == 2,
            "removing a deleted key again is a no-op; got " + table.keys());
        check(table.keys().equals(List.of("a", "c")),
            "remaining keys keep their order after a middle removal");
    }

    static void testKeysSnapshotImmutability() {
        System.out.println("-- keys() is an immutable first-insertion-order snapshot --");

        SemanticTable<ScalarValue> table = abcTable();
        List<String> snapshot = table.keys();
        expectUnsupportedOp(() -> snapshot.add("d"),
            "mutating the keys() snapshot");
        table.put("d", str("d"));
        check(snapshot.equals(List.of("a", "b", "c")),
            "the earlier keys() snapshot is unaffected by a later put");
        check(table.keys().equals(List.of("a", "b", "c", "d")),
            "the fresh keys() snapshot reflects the append; got " + table.keys());
        check(table.keys().equals(table.keys()),
            "repeated keys() calls are equal and deterministic");
    }

    static void testNullArgumentsFailClosed() {
        System.out.println("-- null keys/values are producer defects, never a DEAL projection --");

        SemanticTable<ScalarValue> table = new SemanticTable<>();
        expectNpe(() -> table.put(null, str("v")), "key must not be null",
            "put with a null key");
        expectNpe(() -> table.put("k", null),
            "value must not be null (language null is the closed value type's "
                + "explicit null variant, never a raw reference)",
            "put with a null value");
        expectNpe(() -> table.get(null), "key must not be null", "get with a null key");
        expectNpe(() -> table.remove(null), "key must not be null",
            "remove with a null key");
        check(table.size() == 0,
            "failed puts store nothing; got size " + table.size());
    }

    // =========================================================================
    // Array value model
    // =========================================================================

    static void testArrayModelOrderAndCount() {
        System.out.println("-- array model preserves element order and reports the signed32 count --");

        SemanticArray<String> empty = SemanticArray.of();
        check(empty.size() == 0, "empty array count is 0; got " + empty.size());
        check(empty.elements().isEmpty(), "empty array has no elements");

        SemanticArray<String> arr = SemanticArray.of("x", "y", "z");
        check(arr.size() == 3, "size reports the signed32 element count; got " + arr.size());
        check(arr.elementAt(0).equals("x"), "elementAt(0) is x");
        check(arr.elementAt(1).equals("y"), "elementAt(1) is y");
        check(arr.elementAt(2).equals("z"), "elementAt(2) is z");
        check(arr.elements().equals(List.of("x", "y", "z")),
            "elements() preserves allocation order; got " + arr.elements());

        List<String> input = List.of("x", "y", "z");
        SemanticArray<String> fromList = SemanticArray.of(input);
        check(fromList.size() == 3 && fromList.elements().equals(input),
            "of(List) preserves the given order and count");

        List<String> big = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            big.add("e" + i);
        }
        SemanticArray<String> bigArr = SemanticArray.of(big);
        check(bigArr.size() == 1000,
            "count for a 1000-element array is 1000; got " + bigArr.size());
        check(bigArr.elementAt(0).equals("e0")
                && bigArr.elementAt(499).equals("e499")
                && bigArr.elementAt(999).equals("e999"),
            "element order is preserved for every index");
        check(bigArr.elements().equals(big),
            "elements() equals the allocation order for 1000 elements");
    }

    static void testArrayFreshIdentity() {
        System.out.println("-- every array/table allocation carries a fresh identity --");

        SemanticArray<String> first = SemanticArray.of("x");
        SemanticArray<String> second = SemanticArray.of("x");
        check(first != second,
            "two allocations with equal content are distinct array identities");
        check(first.elementAt(0).equals(second.elementAt(0)),
            "equal content is observable through the elements");
        check(first.size() == second.size(),
            "equal content reports equal counts");

        SemanticTable<ScalarValue> t1 = new SemanticTable<>();
        SemanticTable<ScalarValue> t2 = new SemanticTable<>();
        check(t1 != t2, "two fresh tables are distinct table identities");
    }

    static void testArrayBoundsFailClosed() {
        System.out.println("-- out-of-range element access is a producer defect, never a DEAL projection --");

        SemanticArray<String> arr = SemanticArray.of("x", "y", "z");
        expectDefect(() -> arr.elementAt(-1), "elementAt(-1) on a size-3 array");
        expectDefect(() -> arr.elementAt(3), "elementAt(size) on a size-3 array");
        check(arr.elementAt(0).equals("x"), "elementAt(0) is the first valid boundary");
        check(arr.elementAt(2).equals("z"), "elementAt(size-1) is the last valid boundary");

        SemanticArray<String> empty = SemanticArray.of();
        expectDefect(() -> empty.elementAt(0), "elementAt(0) on an empty array");
        check(empty.size() == 0, "empty array count stays 0");
    }

    static void testArrayNullElementsFailClosed() {
        System.out.println("-- null elements are producer defects, never a DEAL projection --");

        expectNpe(() -> SemanticArray.of((List<String>) null), "elements must not be null",
            "of((List) null)");
        expectNpe(() -> SemanticArray.of((String[]) null), "elements must not be null",
            "of((String[]) null)");
        expectAnyNpe(() -> SemanticArray.of("a", null), "of with a null element");
        expectAnyNpe(() -> SemanticArray.of(new ArrayList<>(List.of("a", null))),
            "of(List) with a null element");

        SemanticArray<String> arr = SemanticArray.of("x");
        expectUnsupportedOp(() -> arr.elements().add("y"),
            "mutating the elements() snapshot");
    }

    static void testNestedComposition() {
        System.out.println("-- tables and arrays compose as slot/element values --");

        SemanticArray<String> arr = SemanticArray.of("x", "y");
        SemanticTable<String> inner = new SemanticTable<>();
        inner.put("k", "v");

        SemanticTable<Object> outer = new SemanticTable<>();
        outer.put("arr", arr);
        outer.put("table", inner);
        switch (outer.get("arr")) {
            case SemanticTable.Lookup.Present<Object> present ->
                check(present.value() == arr,
                    "a stored array value is retrieved as the same identity");
            case SemanticTable.Lookup.Missing<Object> ignored ->
                fail("get(arr) after put(arr) is Missing");
        }
        switch (outer.get("table")) {
            case SemanticTable.Lookup.Present<Object> present ->
                check(present.value() == inner,
                    "a stored table value is retrieved as the same identity");
            case SemanticTable.Lookup.Missing<Object> ignored ->
                fail("get(table) after put(table) is Missing");
        }
    }

    static void testDeterminism() {
        System.out.println("-- deterministic repetition --");

        SemanticTable<ScalarValue> run1 = abcTable();
        run1.put("b", str("B2"));
        run1.remove("b");
        run1.put("b", str("B3"));
        SemanticTable<ScalarValue> run2 = abcTable();
        run2.put("b", str("B2"));
        run2.remove("b");
        run2.put("b", str("B3"));
        check(run1.keys().equals(run2.keys()),
            "identical operation sequences produce identical key order");
        check(run1.keys().equals(List.of("a", "c", "b")),
            "the repeated pinned sequence yields [a,c,b]; got " + run1.keys());
        SemanticTable.Lookup<ScalarValue> b1 = run1.get("b");
        SemanticTable.Lookup<ScalarValue> b2 = run2.get("b");
        check(b1 instanceof SemanticTable.Lookup.Present<ScalarValue> p1
                && p1.value().equals(str("B3"))
                && b2 instanceof SemanticTable.Lookup.Present<ScalarValue> p2
                && p2.value().equals(str("B3")),
            "identical sequences produce identical slot values");

        SemanticArray<String> arr1 = SemanticArray.of("x", "y", "z");
        SemanticArray<String> arr2 = SemanticArray.of("x", "y", "z");
        check(arr1.elements().equals(arr2.elements()) && arr1.size() == arr2.size(),
            "repeated array allocations are content-deterministic");
    }

    // =========================================================================
    // Runner
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Semantic Table / Array Value Model Test (ISSUE-0383 C2) ===\n");

        testInsertOrder();
        testOverwriteKeepsSlot();
        testDeleteThenReinsertMovesToEnd();
        testDuplicateKeyPut();
        testPresentNullVsMissing();
        testRemoveSemantics();
        testKeysSnapshotImmutability();
        testNullArgumentsFailClosed();
        testArrayModelOrderAndCount();
        testArrayFreshIdentity();
        testArrayBoundsFailClosed();
        testArrayNullElementsFailClosed();
        testNestedComposition();
        testDeterminism();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
