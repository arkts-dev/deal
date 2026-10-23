package deal.test;

import deal.types.Type;
import deal.types.Types;

public class TypesNullableTest {

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

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    private static <T extends Throwable> void assertThrows(
            Class<T> expectedType, Runnable action, String message) {
        try {
            action.run();
            fail(message + " — expected " + expectedType.getSimpleName()
                + " but no exception thrown");
        } catch (Throwable t) {
            if (expectedType.isInstance(t)) {
                passed++;
            } else {
                fail(message + " — expected " + expectedType.getSimpleName()
                     + " but got " + t.getClass().getSimpleName()
                     + ": " + t.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        System.out.println("=== Running Nullable Type Tests ===");

        testNullableGuardUnchanged();
        testNullableOrSelf();

        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // -----------------------------------------------------------------------
    // Nullable guard unchanged
    // -----------------------------------------------------------------------

    static void testNullableGuardUnchanged() {
        System.out.println("-- Nullable guard unchanged --");

        assertThrows(IllegalArgumentException.class,
            () -> new Type.Nullable(Type.Null.INSTANCE),
            "Nullable(null) throws");
        assertThrows(IllegalArgumentException.class,
            () -> new Type.Nullable(new Type.Nullable(Type.Table.INSTANCE)),
            "Nullable(Nullable(table)) throws");
        assertThrows(IllegalArgumentException.class,
            () -> new Type.Nullable(null),
            "Nullable(null Java ref) throws");
        assertThrows(IllegalArgumentException.class,
            () -> new Type.Array(null),
            "Array(null) throws");
        assertThrows(IllegalArgumentException.class,
            () -> new Type.Func(null, Type.Table.INSTANCE, false),
            "Func(null params, table) throws");
    }

    // -----------------------------------------------------------------------
    // The canonical nullable wrap (the optional-field read type)
    // -----------------------------------------------------------------------

    static void testNullableOrSelf() {
        System.out.println("-- nullableOrSelf: the canonical T | null wrap --");

        check(Types.nullableOrSelf(Type.Null.INSTANCE) == Type.Null.INSTANCE,
            "nullableOrSelf(null) is the null atom (null | null is null)");
        Type once = Types.nullableOrSelf(Type.Number.INSTANCE);
        check(once instanceof Type.Nullable nullable && nullable.inner() == Type.Number.INSTANCE,
            "nullableOrSelf(int) is the one ?int wrap");
        check(Types.nullableOrSelf(once) == once,
            "nullableOrSelf(?int) is that same wrap (idempotent)");
        Type classType = Types.classType("Box",
            IdentityTestFixtures.identityOf("main", "Box"));
        check(Types.nullableOrSelf(classType) instanceof Type.Nullable classWrap
                && Types.equals(classWrap.inner(), classType),
            "nullableOrSelf(Box) wraps the class type once");
        assertThrows(NullPointerException.class,
            () -> Types.nullableOrSelf(null),
            "nullableOrSelf(null Java ref) throws");
    }

}
