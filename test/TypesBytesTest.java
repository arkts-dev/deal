package deal.test;

import deal.checker.SymbolTable;
import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.ir.IrDumper;
import deal.types.Type;
import deal.test.IdentityTestFixtures;
import deal.types.Types;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Types-layer tests for the DEAL v1.2 canonical bytes primitive
 * (ISSUE-0308): {@link Type.Bytes} singleton identity, the
 * {@link Types#equals(Type, Type)} structural arm, canonicalize
 * identity, the {@link Types#containsBytes(Type)} D8 recursion over
 * nested Array/Nullable/Function trees at depth &gt;= 50, and the
 * pinned {@code bytes} descriptor text at every Type-to-text producer.
 *
 * <p>Runs via main() using assertions. Enable with -ea JVM flag.</p>
 */
public class TypesBytesTest {

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
        System.out.println("=== Running Types Bytes Tests (ISSUE-0308) ===");

        testBytesSingletonIdentity();
        testBytesEquality();
        testBytesCanonicalizeIdentity();
        testContainsBytesTruthTable();
        testContainsBytesDeepRecursion();
        testDeepCompositionConstructsWithoutError();
        testNullableGuardUnchanged();
        testDescriptorTextPins();

        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // -----------------------------------------------------------------------
    // Singleton identity
    // -----------------------------------------------------------------------

    static void testBytesSingletonIdentity() {
        System.out.println("-- Bytes singleton identity --");

        check(Type.Bytes.INSTANCE != null, "Bytes.INSTANCE must not be null");
        check(Type.Bytes.INSTANCE == Type.Bytes.INSTANCE,
            "Bytes.INSTANCE must be reference-identical to itself");
        check(Type.Bytes.INSTANCE instanceof Type,
            "Bytes.INSTANCE must implement Type");
    }

    // -----------------------------------------------------------------------
    // Types.equals structural arm
    // -----------------------------------------------------------------------

    static void testBytesEquality() {
        System.out.println("-- Types.equals bytes arm --");

        check(Types.equals(Type.Bytes.INSTANCE, Type.Bytes.INSTANCE),
            "Bytes equals Bytes");
        check(!Types.equals(Type.Bytes.INSTANCE, Type.Null.INSTANCE),
            "Bytes does not equal Null");
        check(!Types.equals(Type.Bytes.INSTANCE, Type.Boolean.INSTANCE),
            "Bytes does not equal Boolean");
        check(!Types.equals(Type.Bytes.INSTANCE, Type.Int.INSTANCE),
            "Bytes does not equal Int");
        check(!Types.equals(Type.Bytes.INSTANCE, Type.Number.INSTANCE),
            "Bytes does not equal Number");
        check(!Types.equals(Type.Bytes.INSTANCE, Type.String.INSTANCE),
            "Bytes does not equal String");
        check(!Types.equals(Type.Bytes.INSTANCE, Type.Table.INSTANCE),
            "Bytes does not equal Table");
        check(!Types.equals(Type.Bytes.INSTANCE, Type.Error.INSTANCE),
            "Bytes does not equal the Error sentinel");
        check(!Types.equals(Type.Bytes.INSTANCE,
                new Type.Array(Type.Bytes.INSTANCE)),
            "Bytes does not equal Array(bytes)");
        check(!Types.equals(Type.Bytes.INSTANCE,
                new Type.Nullable(Type.Bytes.INSTANCE)),
            "Bytes does not equal Nullable(bytes)");
        check(!Types.equals(Type.Bytes.INSTANCE,
                IdentityTestFixtures.classType("bytes", "")),
            "Bytes does not equal Class(bytes)");
        check(!Types.equals(Type.Bytes.INSTANCE,
                new Type.Func(List.of(), Type.Bytes.INSTANCE, false)),
            "Bytes does not equal Func()->bytes");
        check(!Types.equals(Type.Bytes.INSTANCE, null),
            "Bytes does not equal null");
        check(!Types.equals(null, Type.Bytes.INSTANCE),
            "null does not equal Bytes");

        check(Types.equals(new Type.Array(Type.Bytes.INSTANCE),
                new Type.Array(Type.Bytes.INSTANCE)),
            "Array(bytes) equals Array(bytes)");
        check(!Types.equals(new Type.Array(Type.Bytes.INSTANCE),
                new Type.Array(Type.Int.INSTANCE)),
            "Array(bytes) does not equal Array(int)");
        check(Types.equals(new Type.Nullable(Type.Bytes.INSTANCE),
                new Type.Nullable(Type.Bytes.INSTANCE)),
            "Nullable(bytes) equals Nullable(bytes)");

        Type.Func syncBytes = new Type.Func(List.of(Type.Bytes.INSTANCE),
            Type.Bytes.INSTANCE, false);
        Type.Func asyncBytes = new Type.Func(List.of(Type.Bytes.INSTANCE),
            Type.Bytes.INSTANCE, true);
        check(Types.equals(syncBytes, new Type.Func(List.of(Type.Bytes.INSTANCE),
                Type.Bytes.INSTANCE, false)),
            "sync (bytes)->bytes equals itself");
        check(!Types.equals(syncBytes, asyncBytes),
            "sync and async (bytes)->bytes are distinct");
    }

    // -----------------------------------------------------------------------
    // canonicalize identity
    // -----------------------------------------------------------------------

    static void testBytesCanonicalizeIdentity() {
        System.out.println("-- canonicalize identity --");

        check(Types.canonicalize(Type.Bytes.INSTANCE) == Type.Bytes.INSTANCE,
            "canonicalize(Bytes) returns the same singleton (reference identity)");

        Type arr = new Type.Array(Type.Bytes.INSTANCE);
        Type canonArr = Types.canonicalize(arr);
        check(canonArr instanceof Type.Array ca
                && ca.element() == Type.Bytes.INSTANCE,
            "canonicalize(Array(bytes)) keeps the bytes element");
        check(Types.equals(canonArr, arr), "canonicalize(Array(bytes)) is equal");

        Type nul = new Type.Nullable(Type.Bytes.INSTANCE);
        Type canonNul = Types.canonicalize(nul);
        check(canonNul instanceof Type.Nullable cn
                && cn.inner() == Type.Bytes.INSTANCE,
            "canonicalize(Nullable(bytes)) keeps the bytes inner");
        check(Types.equals(canonNul, nul),
            "canonicalize(Nullable(bytes)) is equal");

        Type fn = new Type.Func(List.of(Type.Bytes.INSTANCE),
            new Type.Array(Type.Bytes.INSTANCE), true);
        Type canonFn = Types.canonicalize(fn);
        check(canonFn instanceof Type.Func cf
                && cf.isAsync()
                && cf.paramTypes().get(0) == Type.Bytes.INSTANCE
                && cf.returnType() instanceof Type.Array cra
                && cra.element() == Type.Bytes.INSTANCE,
            "canonicalize preserves async (bytes)->[bytes] structure");
        check(Types.equals(canonFn, fn), "canonicalize(func) is equal");
    }

    // -----------------------------------------------------------------------
    // containsBytes truth table (DEAL v1.2 D8)
    // -----------------------------------------------------------------------

    static void testContainsBytesTruthTable() {
        System.out.println("-- containsBytes truth table --");

        check(Types.containsBytes(Type.Bytes.INSTANCE), "containsBytes(bytes)");

        check(Types.containsBytes(new Type.Array(Type.Bytes.INSTANCE)),
            "containsBytes([bytes])");
        check(Types.containsBytes(new Type.Nullable(Type.Bytes.INSTANCE)),
            "containsBytes(?bytes)");
        check(Types.containsBytes(new Type.Array(
                new Type.Nullable(Type.Bytes.INSTANCE))),
            "containsBytes([?bytes])");
        check(Types.containsBytes(new Type.Nullable(
                new Type.Array(Type.Bytes.INSTANCE))),
            "containsBytes(?[bytes])");

        check(Types.containsBytes(new Type.Func(List.of(Type.Int.INSTANCE),
                Type.Bytes.INSTANCE, false)),
            "containsBytes((int)->bytes)");
        check(Types.containsBytes(new Type.Func(List.of(Type.Bytes.INSTANCE),
                Type.Int.INSTANCE, false)),
            "containsBytes((bytes)->int)");
        check(Types.containsBytes(new Type.Func(List.of(
                Type.Int.INSTANCE, new Type.Array(Type.Bytes.INSTANCE)),
                Type.Null.INSTANCE, false)),
            "containsBytes((int,[bytes])->null)");
        check(Types.containsBytes(new Type.Func(List.of(),
                new Type.Func(List.of(Type.Bytes.INSTANCE), Type.Bytes.INSTANCE, true), false)),
            "containsBytes(()->async(bytes)->bytes)");
        check(Types.containsBytes(new Type.Nullable(new Type.Func(
                List.of(Type.Int.INSTANCE), Type.Bytes.INSTANCE, false))),
            "containsBytes(?(int)->bytes)");
        check(Types.containsBytes(new Type.Array(new Type.Func(
                List.of(Type.Bytes.INSTANCE), Type.Bytes.INSTANCE, true))),
            "containsBytes([async(bytes)->bytes])");

        check(!Types.containsBytes(Type.Null.INSTANCE),
            "!containsBytes(null)");
        check(!Types.containsBytes(Type.Boolean.INSTANCE),
            "!containsBytes(boolean)");
        check(!Types.containsBytes(Type.Int.INSTANCE), "!containsBytes(int)");
        check(!Types.containsBytes(Type.Number.INSTANCE),
            "!containsBytes(number)");
        check(!Types.containsBytes(Type.String.INSTANCE),
            "!containsBytes(string)");
        check(!Types.containsBytes(Type.Table.INSTANCE),
            "!containsBytes(table)");
        check(!Types.containsBytes(Type.Error.INSTANCE),
            "!containsBytes(Error sentinel)");
        check(!Types.containsBytes(IdentityTestFixtures.classType("User", "lib")),
            "!containsBytes(class)");
        check(!Types.containsBytes(new Type.Array(Type.Int.INSTANCE)),
            "!containsBytes([int])");
        check(!Types.containsBytes(new Type.Nullable(Type.String.INSTANCE)),
            "!containsBytes(?string)");
        check(!Types.containsBytes(new Type.Func(
                List.of(Type.Int.INSTANCE, Type.String.INSTANCE),
                Type.String.INSTANCE, false)),
            "!containsBytes((int,string)->string)");
        check(!Types.containsBytes(new Type.Array(new Type.Nullable(
                new Type.Func(List.of(Type.Int.INSTANCE), Type.String.INSTANCE, true)))),
            "!containsBytes([?async(int)->string])");
    }

    // -----------------------------------------------------------------------
    // containsBytes at depth >= 50
    // -----------------------------------------------------------------------

    static void testContainsBytesDeepRecursion() {
        System.out.println("-- containsBytes deep recursion (depth >= 50) --");

        final int depth = 60;

        // bytes nested under 60 alternating Array/Nullable wrappers.
        Type bytesDeep = Type.Bytes.INSTANCE;
        for (int i = 0; i < depth; i++) {
            bytesDeep = (i % 2 == 0)
                ? new Type.Array(bytesDeep)
                : new Type.Nullable(bytesDeep);
        }
        check(Types.containsBytes(bytesDeep),
            "containsBytes at depth " + depth + " via Array/Nullable");

        // bytes nested through 60 alternating function parameter/return
        // positions.
        Type fnDeep = Type.Bytes.INSTANCE;
        for (int i = 0; i < depth; i++) {
            fnDeep = (i % 2 == 0)
                ? new Type.Func(List.of(fnDeep), Type.Int.INSTANCE, false)
                : new Type.Func(List.of(Type.Int.INSTANCE), fnDeep, true);
        }
        check(Types.containsBytes(fnDeep),
            "containsBytes at depth " + depth + " via function params/returns");

        // A matching deep tree with no bytes stays false.
        Type noBytesDeep = Type.Int.INSTANCE;
        for (int i = 0; i < depth; i++) {
            noBytesDeep = (i % 3 == 0)
                ? new Type.Array(noBytesDeep)
                : (i % 3 == 1)
                    ? new Type.Nullable(noBytesDeep)
                    : new Type.Func(List.of(Type.String.INSTANCE), noBytesDeep, false);
        }
        check(!Types.containsBytes(noBytesDeep),
            "!containsBytes at depth " + depth + " without bytes");

        // A mixed deep tree whose only bytes sits in one function parameter.
        Type mixedDeep = Type.Int.INSTANCE;
        for (int i = 0; i < depth; i++) {
            mixedDeep = new Type.Func(
                List.of(Type.Bytes.INSTANCE, mixedDeep),
                Type.Null.INSTANCE, false);
        }
        check(Types.containsBytes(mixedDeep),
            "containsBytes finds bytes in one parameter of a depth-"
                + depth + " function chain");
    }

    // -----------------------------------------------------------------------
    // Deep composition constructs without error
    // -----------------------------------------------------------------------

    static void testDeepCompositionConstructsWithoutError() {
        System.out.println("-- deep composition over bytes --");

        final int depth = 64;

        Type t = Type.Bytes.INSTANCE;
        for (int i = 0; i < depth; i++) {
            t = new Type.Array(t);
            t = new Type.Nullable(t);
        }
        check(t != null, "Array/Nullable alternation at depth " + depth
            + " constructs without error");
        check(Types.containsBytes(t), "constructed deep type contains bytes");

        Type fn = Type.Bytes.INSTANCE;
        for (int i = 0; i < depth; i++) {
            fn = new Type.Func(List.of(fn, Type.Null.INSTANCE),
                fn, i % 2 == 0);
        }
        check(fn != null, "function nesting at depth " + depth
            + " constructs without error");
        check(Types.containsBytes(fn), "constructed deep function contains bytes");

        check(Types.equals(t, Types.canonicalize(t)),
            "deep canonicalize round-trip preserves equality");
    }

    // -----------------------------------------------------------------------
    // Nullable guard unchanged
    // -----------------------------------------------------------------------

    static void testNullableGuardUnchanged() {
        System.out.println("-- Nullable guard unchanged --");

        check(new Type.Nullable(Type.Bytes.INSTANCE) != null,
            "Nullable(bytes) constructs");
        check(new Type.Nullable(new Type.Array(Type.Bytes.INSTANCE)) != null,
            "Nullable([bytes]) constructs");

        assertThrows(IllegalArgumentException.class,
            () -> new Type.Nullable(Type.Null.INSTANCE),
            "Nullable(null) throws");
        assertThrows(IllegalArgumentException.class,
            () -> new Type.Nullable(new Type.Nullable(Type.Bytes.INSTANCE)),
            "Nullable(Nullable(bytes)) throws");
        assertThrows(IllegalArgumentException.class,
            () -> new Type.Nullable(null),
            "Nullable(null Java ref) throws");
        assertThrows(IllegalArgumentException.class,
            () -> new Type.Array(null),
            "Array(null) throws");
        assertThrows(IllegalArgumentException.class,
            () -> new Type.Func(null, Type.Bytes.INSTANCE, false),
            "Func(null params, bytes) throws");
    }

    // -----------------------------------------------------------------------
    // Pinned descriptor text "bytes" at every Type-to-text producer
    // -----------------------------------------------------------------------

    static void testDescriptorTextPins() {
        System.out.println("-- descriptor text pins --");

        // JvmBackend.typeDescriptor is the public static producer.
        check("bytes".equals(
                deal.codegen.jvm.JvmBackend.typeDescriptor(Type.Bytes.INSTANCE)),
            "JvmBackend.typeDescriptor(bytes) == \"bytes\"");
        check("[bytes]".equals(deal.codegen.jvm.JvmBackend.typeDescriptor(
                new Type.Array(Type.Bytes.INSTANCE))),
            "JvmBackend.typeDescriptor([bytes]) == \"[bytes]\"");
        check("?bytes".equals(deal.codegen.jvm.JvmBackend.typeDescriptor(
                new Type.Nullable(Type.Bytes.INSTANCE))),
            "JvmBackend.typeDescriptor(?bytes) == \"?bytes\"");
        check("async(bytes)->bytes".equals(
                deal.codegen.jvm.JvmBackend.typeDescriptor(
                    new Type.Func(List.of(Type.Bytes.INSTANCE),
                        Type.Bytes.INSTANCE, true))),
            "JvmBackend.typeDescriptor(async(bytes)->bytes) == \"async(bytes)->bytes\"");

        // LuaBackend.typeDescriptor — private instance producer, reached
        // through its public constructor + reflection.
        try {
            deal.codegen.lua.LuaBackend lua = new deal.codegen.lua.LuaBackend(
                Map.of(), new SymbolTable(), "test.deal");
            Method m = deal.codegen.lua.LuaBackend.class.getDeclaredMethod(
                "typeDescriptor", Type.class);
            m.setAccessible(true);
            String desc = (String) m.invoke(lua, Type.Bytes.INSTANCE);
            check("bytes".equals(desc),
                "LuaBackend.typeDescriptor(bytes) == \"bytes\"");
        } catch (Throwable t) {
            fail("LuaBackend.typeDescriptor reflection failed: " + t);
        }

        // The canonical per-compilation descriptor service — the one
        // Type-to-text producer the JS backend consumes since
        // ISSUE-0317 retired JsBackend.jsTypeDescriptor.
        deal.module.ModuleIdentityResolver.IdentityIndex index =
            deal.module.ModuleIdentityResolver.buildIndex(
                Map.of("", deal.identity.CanonicalModuleIdentity.BuiltinModule.INSTANCE));
        CanonicalRuntimeTypeDescriptor service =
            new CanonicalRuntimeTypeDescriptor(index);
        check("bytes".equals(service.encode(Type.Bytes.INSTANCE)),
            "CanonicalRuntimeTypeDescriptor.encode(bytes) == \"bytes\"");
        check("[bytes]".equals(service.encode(
                new Type.Array(Type.Bytes.INSTANCE))),
            "CanonicalRuntimeTypeDescriptor.encode([bytes]) == \"[bytes]\"");
        check("?bytes".equals(service.encode(
                new Type.Nullable(Type.Bytes.INSTANCE))),
            "CanonicalRuntimeTypeDescriptor.encode(?bytes) == \"?bytes\"");
        check("async(bytes)->bytes".equals(service.encode(
                new Type.Func(List.of(Type.Bytes.INSTANCE),
                    Type.Bytes.INSTANCE, true))),
            "CanonicalRuntimeTypeDescriptor.encode(async(bytes)->bytes) == "
                + "\"async(bytes)->bytes\"");

        // IrDumper.specTypeDescriptor — private instance producer.
        try {
            Constructor<IrDumper> ctor = IrDumper.class.getDeclaredConstructor(
                Map.class, SymbolTable.class, String.class, boolean.class);
            ctor.setAccessible(true);
            IrDumper dumper = ctor.newInstance(Map.of(), new SymbolTable(),
                "", false);
            Method m = IrDumper.class.getDeclaredMethod("specTypeDescriptor",
                Type.class);
            m.setAccessible(true);
            String desc = (String) m.invoke(dumper, Type.Bytes.INSTANCE);
            check("bytes".equals(desc),
                "IrDumper.specTypeDescriptor(bytes) == \"bytes\"");
        } catch (Throwable t) {
            fail("IrDumper.specTypeDescriptor reflection failed: " + t);
        }
    }
}
