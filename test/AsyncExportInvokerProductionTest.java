package deal.test;

import deal.codegen.AsyncExportEnvelope;
import deal.codegen.jvm.JvmAsyncExportInvocationException;
import deal.codegen.jvm.JvmAsyncExportInvoker;
import deal.codegen.jvm.JvmRuntime;
import deal.codegen.lua.AsyncExportInvocationRequest;
import deal.codegen.lua.LuaJitAsyncExportInvocationException;
import deal.codegen.lua.LuaJitAsyncExportInvoker;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The public production async-export drive (ISSUE-0749): every scenario
 * compiles a real project through the public {@code deal compile} entry,
 * stages the target's host implementation, and invokes the selected entry
 * artifact through the production {@link LuaJitAsyncExportInvoker} and
 * {@link JvmAsyncExportInvoker}.
 *
 * <p>Proven on both targets: a plain async export completing 7; a null
 * completion; an {@code int[]} completion encoded as a JSON array; a
 * plain-object completion encoded as a JSON object (no carrier metadata);
 * a nested object completion; scalar and nested numeric reads (the shared
 * emitter's numeric carrier unwraps to its number, whose JSON text is the
 * shared walker's canonical spelling — the JVM sibling's boxed-double
 * text at the integral-value, precision, and exponent boundaries; an
 * int-variant read keeps the integer spelling); empty, nested empty,
 * and object-field empty arrays (the JSON array shape survives every
 * depth); parsed-JSON completions (every decoded numeric leaf keeps its
 * own variant — the precision-boundary, nested fractional, and integral
 * leaves — and the decoded container shapes survive); absent versus
 * present-null slots (a deleted array element is a value-representation
 * failure, an explicit null element stays JSON {@code null}); shared
 * references that encode at every position and cyclic completions that
 * are value-representation failures; a bytes completion and a
 * non-finite number classified as representation failures; class,
 * function, and builtin Error completions that pass the matcher and are
 * rejected by the shared encoder (top-level, nullable, nested in an
 * array, and as a table field) instead of a fabricated DEAL error; a
 * delayed host async completion awaited by the export; a typed completion
 * rejection at the await site (E8001, message unchanged); a DEAL error
 * raised by the async operation and by {@code main()} (code/message/
 * location unchanged on both targets); the shared DEAL-error envelope
 * projection (carried origin with integer line/column, absent origin
 * omitted); the parameterized published wrapper forwarding {@code
 * run.f(7)} into the async entry; and module initialization plus {@code
 * main()} running exactly once per invocation (the host probe counts the
 * host load and the {@code main} touch). The JVM lane compiles the
 * emitted source with {@code javac} and asserts the reserved {@code
 * <Entry>$$AsyncExportHost} launcher class file exists.</p>
 */
public class AsyncExportInvokerProductionTest {

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

    private static void checkEq(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual),
            message + " (expected " + expected + ", got " + actual + ")");
    }

    // =========================================================================
    // Fixtures
    // =========================================================================

    /** The pinned value-7 project of the issue: a quiet main plus an int async export. */
    private static final String VALUE_SEVEN_SOURCE = """
        export async function run(): int {
          return 7;
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The pinned null completion: the shared emitter's language null must
     * reach the deployed runtime's canonical matcher as the runtime's own
     * null sentinel (a raw {@code nil} is rejected as "expected null").
     */
    private static final String NULL_COMPLETION_SOURCE = """
        export async function run(): null {
          return null;
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * An {@code int[]} completion: the checked completion must encode as a
     * JSON array, never as an object carrying the emitter's array carrier
     * metadata ({@code __a}/{@code __n}).
     */
    private static final String ARRAY_COMPLETION_SOURCE = """
        export async function run(): int[] {
          return [7, 8];
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * A plain-object completion: the checked completion must encode as a
     * JSON object carrying exactly the declared fields, never the table
     * carrier metadata ({@code __t}/{@code __order}/{@code __keys}).
     */
    private static final String TABLE_COMPLETION_SOURCE = """
        export async function run(): table {
          return { a: 7 };
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The nested completion: an object field holding an array and an
     * object field holding a null — the projection descends into nested
     * containers and preserves the null slot as JSON {@code null}.
     */
    private static final String NESTED_COMPLETION_SOURCE = """
        export async function run(): table {
          return { a: 7, xs: [7, 8], n: null };
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The preserved representation-failure surface: a bytes completion is
     * admitted by the canonical matcher and refused by the encoding step.
     */
    private static final String BYTES_COMPLETION_SOURCE = """
        export async function run(): bytes {
          let b: bytes = bytes(0);
          return b;
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The scalar numeric read: the member read materializes the shared
     * emitter's numeric carrier, so the completion must reach the runtime
     * as the number it carries, never as the carrier table
     * (ISSUE-0749 review cycle 2, finding 1).
     */
    private static final String NUMBER_READ_SOURCE = """
        export async function run(): number {
          let t: table = { v: 7.5 };
          let v: number = t.v;
          return v;
        }

        export function main(): null {
          return null;
        }
        """;

    /** The nested numeric read: array elements carry read carriers too. */
    private static final String NESTED_NUMBER_READ_SOURCE = """
        export async function run(): number[] {
          let t: table = { v: 7.5 };
          let v: number = t.v;
          return [v, 8.25];
        }

        export function main(): null {
          return null;
        }
        """;

    /** The int-variant read carrier unwraps to its integral number. */
    private static final String INT_READ_SOURCE = """
        export async function run(): int {
          let t: table = { v: 7 };
          let v: int = t.v;
          return v;
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The integral arithmetic result of a declared {@code number} return:
     * the top-level scalar carries the number variant, so its JSON text is
     * the canonical decimal spelling (the JVM sibling's boxed-double text,
     * {@code "5.0"}), never the default {@code tostring}'s integral
     * spelling.
     */
    private static final String NUMBER_ARITHMETIC_SOURCE = """
        export async function run(): number {
          return 2.5 * 2.0;
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The precision boundary of the number text: the default {@code
     * tostring} spelling is a lossy {@code %.14g}, so a number read must
     * keep the shared walker's canonical shortest-round-trip text.
     */
    private static final String NUMBER_PRECISION_SOURCE = """
        export async function run(): number {
          let t: table = { v: 0.30000000000000004 };
          let v: number = t.v;
          return v;
        }

        export function main(): null {
          return null;
        }
        """;

    /** The magnitude boundary of the number text (exponent spelling). */
    private static final String NUMBER_MAGNITUDE_SOURCE = """
        export async function run(): number {
          let t: table = { v: 1e17 };
          let v: number = t.v;
          return v;
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The int-variant control under a declared {@code number} return: the
     * read carrier's own variant wins, so the text stays the integer
     * spelling the JVM sibling's boxed long emits.
     */
    private static final String NUMBER_INT_VARIANT_SOURCE = """
        export async function run(): number {
          let t: table = { v: 7 };
          let v: number = t.v;
          return v;
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The non-finite number boundary: the completion passes the canonical
     * matcher and the encoder's own finite check rejects it — a
     * representation failure with the pinned message, never a value.
     */
    private static final String INFINITE_NUMBER_SOURCE = """
        export async function run(): number {
          let n: number = 1e300 * 1e300;
          return n;
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The empty-array boundary: the zero-length array carrier must keep
     * the JSON array shape, not degrade to an empty JSON object
     * (ISSUE-0749 review cycle 2, finding 2).
     */
    private static final String EMPTY_ARRAY_SOURCE = """
        export async function run(): int[] {
          return [];
        }

        export function main(): null {
          return null;
        }
        """;

    /** Nested empty arrays keep the JSON array shape at every depth. */
    private static final String NESTED_EMPTY_ARRAY_SOURCE = """
        export async function run(): int[][] {
          let xs: int[][] = [[], [7]];
          return xs;
        }

        export function main(): null {
          return null;
        }
        """;

    /** An empty array inside an object field keeps the JSON array shape. */
    private static final String EMPTY_ARRAY_FIELD_SOURCE = """
        export async function run(): table {
          let xs: int[] = [];
          return { a: 7, xs: xs, n: null };
        }

        export function main(): null {
          return null;
        }
        """;

    /** A shared reference: repeated identities encode at every position. */
    private static final String SHARED_REFERENCE_SOURCE = """
        export async function run(): table {
          let t: table = { v: 7 };
          return { a: t, b: t };
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The cyclic completion: the encoder's cycle rejection is a value
     * representation failure, never a stack overflow misclassified as an
     * infrastructure failure (ISSUE-0749 review cycle 2, finding 3).
     */
    private static final String CYCLIC_COMPLETION_SOURCE = """
        export async function run(): table {
          let t: table = { v: 7 };
          t.self = t;
          return t;
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The parsed-JSON numeric leaves (the latest ISSUE-0749 review, finding
     * 1): a {@code std/json} decode carries each numeric leaf's variant in
     * the carrier itself ({@code __jn.k}), never in a container slot mark,
     * so the projection must record the destination slot's variant when it
     * unwraps the carrier. The precision-boundary leaf, the integral leaf,
     * and the nested {@code 5.0} leaf must all spell through the shared
     * walker's canonical text like the JVM sibling, never through the
     * default {@code tostring}'s lossy {@code %.14g}.
     */
    private static final String PARSED_JSON_NUMBERS_SOURCE = """
        import * as json from "std/json";

        export async function run(): table | null {
          return json.parse("{\\"x\\":0.30000000000000004,\\"xs\\":[5.0],\\"n\\":7}");
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The parsed-JSON container shapes and null field: an empty decoded
     * array keeps its JSON array shape, a decoded object keeps its null
     * field as the JSON null slot, and both encode without a carrier key.
     */
    private static final String PARSED_JSON_CONTAINER_SOURCE = """
        import * as json from "std/json";

        export async function run(): table | null {
          return json.parse("{\\"xs\\":[],\\"o\\":{\\"n\\":null}}");
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The parsed-JSON exponent and signed-zero leaves: the variant mark
     * carries the walker's canonical decimal spelling for every numeric
     * leaf the decoder produces, the exponent and negative-zero forms
     * included.
     */
    private static final String PARSED_JSON_EXPONENT_SOURCE = """
        import * as json from "std/json";

        export async function run(): table | null {
          return json.parse("{\\"xs\\":[1e17,1e-320,-0.0]}");
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The deleted array slot (the latest ISSUE-0749 review, finding 2): a raw
     * nil slot is the absent/deleted element, which the shared JSON walk
     * rejects as a value-representation failure — never a fabricated JSON
     * null. The JVM sibling's {@code MISSING} slot rejects identically.
     */
    private static final String DELETED_SLOT_SOURCE = """
        export async function run(): table {
          let xs: int[] = [7, 8];
          delete xs[0];
          return { xs: xs };
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The explicit-null counterpart of the deleted slot: a present null
     * element is the language null and must keep its JSON {@code null}
     * slot (the absent/missing distinction stays visible).
     */
    private static final String EXPLICIT_NULL_SLOT_SOURCE = """
        export async function run(): table {
          let xs: (int | null)[] = [null, 8];
          return { xs: xs };
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The class completion (the latest ISSUE-0749 review, finding 3): a valid
     * compiled class instance is admitted by the canonical matcher (the
     * projection carries the matcher's {@code __kind}/{@code __classname}
     * shape) and rejected by the shared encoder as a non-encodable
     * table-carried value — a representation failure on both targets,
     * never a fabricated DEAL error.
     */
    private static final String CLASS_COMPLETION_SOURCE = """
        class C {
          x: int = 7;
        }

        export async function run(): C {
          let c: C = { x: 7 };
          return c;
        }

        export function main(): null {
          return null;
        }
        """;

    /** The same class value nested in an array completion. */
    private static final String CLASS_ARRAY_COMPLETION_SOURCE = """
        class C {
          x: int = 7;
        }

        export async function run(): C[] {
          let c: C = { x: 7 };
          return [c];
        }

        export function main(): null {
          return null;
        }
        """;

    /** The same class value under a nullable completion descriptor. */
    private static final String NULLABLE_CLASS_COMPLETION_SOURCE = """
        class C {
          x: int = 7;
        }

        export async function run(): C | null {
          let c: C = { x: 7 };
          return c;
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The function completion (the latest ISSUE-0749 review, finding 3): a
     * declared {@code () => int} value is admitted by the canonical
     * matcher (the projection carries the matcher's
     * {@code __kind}/{@code sig} shape) and rejected by the shared encoder
     * as a DEAL function value on both targets.
     */
    private static final String FUNCTION_COMPLETION_SOURCE = """
        export async function run(): () => int {
          let f: () => int = function(): int { return 7; };
          return f;
        }

        export function main(): null {
          return null;
        }
        """;

    /** The same function value nested in an array completion. */
    private static final String FUNCTION_ARRAY_COMPLETION_SOURCE = """
        export async function run(): (() => int)[] {
          let f: () => int = function(): int { return 7; };
          return [f];
        }

        export function main(): null {
          return null;
        }
        """;

    /** A stdlib callable completion under its declared function descriptor. */
    private static final String STDLIB_FUNCTION_COMPLETION_SOURCE = """
        import * as json from "std/json";

        export async function run(): (t: table) => string {
          return json.stringify;
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The builtin Error value as a table field: the {@code __d} carrier
     * takes the matcher's class shape for nested occurrences and the
     * encoder rejects it as a table-carried value on both targets.
     */
    private static final String ERROR_FIELD_COMPLETION_SOURCE = """
        function passThrough(): Error {
          try { throw { code: "E_CANON", message: "roundtrip" }; }
          catch (e) { return e; }
        }

        export async function run(): table {
          return { e: passThrough() };
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The parameterized published wrapper: the retained-caller ABI's
     * {@code run.f(7)} must forward its declared argument into the async
     * entry (ISSUE-0749 review cycle 2, finding 4).
     */
    private static final String PARAMETERIZED_SOURCE = """
        export async function run(x: int): int {
          return x;
        }

        export function main(): null {
          return null;
        }
        """;

    /** The delayed host completion: the export awaits the host's delayed int operation. */
    private static final String DELAYED_SOURCE = """
        import * as probe from "host/probe"

        export function main(): null {
          return null;
        }

        export async function run(): int {
          let v: int = await probe.fetchDelayed();
          return v;
        }
        """;

    private static final String DELAYED_DECLARATION = """
        export async function fetchDelayed(): int;
        """;

    private static final String DELAYED_LUA_HOST = """
        local rt = require("deal.runtime")
        return {
          fetchDelayed = function()
            -- A delayed completion: the operation yields an inner operation
            -- that is not yet started, so it completes only after the
            -- runtime's async chaining drives the inner step.
            local pending = rt.async_create(function() return 7 end)
            return rt.async_start(function()
              coroutine.yield(pending)
              return 7
            end)
          end,
        }
        """;

    private static final String DELAYED_JVM_HOST = """
        import java.util.concurrent.CompletableFuture;

        final class HostProbe {
          public static Object fetchDelayed() {
            CompletableFuture<Object> future = new CompletableFuture<>();
            Thread worker = new Thread(() -> {
              try {
                Thread.sleep(150L);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
              future.complete(Long.valueOf(7L));
            });
            worker.setDaemon(true);
            worker.start();
            return future;
          }
        }
        """;

    /**
     * The typed completion rejection: the host operation is declared
     * {@code async (): string} but completes a number, so the await-site
     * completion check rejects it (E8001) inside the export.
     */
    private static final String MISMATCH_SOURCE = """
        import * as probe from "host/probe"

        export function main(): null {
          return null;
        }

        export async function run(): int {
          let v: string = await probe.fetchMismatch();
          return 7;
        }
        """;

    private static final String MISMATCH_DECLARATION = """
        export async function fetchMismatch(): string;
        """;

    private static final String MISMATCH_LUA_HOST = """
        local rt = require("deal.runtime")
        return {
          fetchMismatch = function()
            return rt.async_start(function() return 7 end)
          end,
        }
        """;

    private static final String MISMATCH_JVM_HOST = """
        import java.util.concurrent.CompletableFuture;

        final class HostProbe {
          public static Object fetchMismatch() {
            return CompletableFuture.completedFuture(Long.valueOf(7L));
          }
        }
        """;

    /** The operation-raised DEAL error: an int division by zero inside the async export. */
    private static final String OPERATION_ERROR_SOURCE = """
        export async function run(): int {
          let z: int = 1;
          let w: int = 0;
          let q: int = z / w;
          return q;
        }

        export function main(): null {
          return null;
        }
        """;

    /** The main-raised DEAL error: the same division by zero inside main(). */
    private static final String MAIN_ERROR_SOURCE = """
        export async function run(): int {
          return 7;
        }

        export function main(): null {
          let z: int = 1;
          let w: int = 0;
          let q: int = z / w;
          return null;
        }
        """;

    /**
     * The user-raised DEAL error of the line-terminator source-path
     * regression: the thrown error's message carries a line terminator
     * of its own, and the error's origin is the throw at 2:3.
     */
    private static final String USER_ERROR_SOURCE = """
        export async function run(): int {
          throw { code: "E_USER", message: "unchanged\\nmessage" };
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The once-counting probe: the host implementation counts its own
     * module load and every {@code touchMain} call, and the async export
     * completes the encoded counter ({@code load * 100 + main * 10}), so
     * 110 proves one host load and one main touch per invocation.
     */
    private static final String COUNTS_SOURCE = """
        import * as probe from "host/probe"

        export function main(): null {
          probe.touchMain();
          return null;
        }

        export async function run(): int {
          return probe.snapshot();
        }
        """;

    private static final String COUNTS_DECLARATION = """
        export function touchMain(): null;
        export function snapshot(): int;
        """;

    private static final String COUNTS_LUA_HOST = """
        local rt = require("deal.runtime")
        local loads = 0
        local mainTouches = 0
        loads = loads + 1
        return {
          touchMain = function()
            mainTouches = mainTouches + 1
            return rt.__NULL
          end,
          snapshot = function()
            return loads * 100 + mainTouches * 10
          end,
        }
        """;

    private static final String COUNTS_JVM_HOST = """
        final class HostProbe {
          private static final int LOADS = 1;
          private static int mainTouches = 0;

          public static Object touchMain() {
            mainTouches = mainTouches + 1;
            return null;
          }

          public static Object snapshot() {
            return Integer.valueOf(LOADS * 100 + mainTouches * 10);
          }
        }
        """;

    private static final String EXTERNALS_HEADER = """
        {
          "languageVersion": "1.2",
          "moduleRoots": ["src"],
          "output": "out",
          "backend": "%s",
          "externals": {
            "host/probe": { "declaration": "src/probe.d.deal" }
          }
        }
        """;

    private static final String PLAIN_MANIFEST = """
        {
          "languageVersion": "1.2",
          "moduleRoots": ["src"],
          "output": "out",
          "backend": "%s"
        }
        """;

    // =========================================================================
    // Scenarios
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Async-Export Invoker Production Tests (ISSUE-0749) ===\n");

        testValueSevenBothTargets();
        testNullAndContainerCompletionsBothTargets();
        testNumericReadCompletionsBothTargets();
        testEmptyArrayCompletionsBothTargets();
        testGraphCompletionsBothTargets();
        testParsedJsonCompletionsBothTargets();
        testAbsentAndNullSlotsBothTargets();
        testNonEncodableCarrierCompletionsBothTargets();
        testRepresentationFailureBothTargets();
        testDelayedHostCompletionBothTargets();
        testTypedCompletionRejectionBothTargets();
        testUnchangedErrorFieldsBothTargets();
        testLineTerminatorSourcePathBothTargets();
        testInitializationAndMainOnceBothTargets();
        testParameterizedPublishedWrapper();
        testDealErrorEnvelopeProjection();
        testSharedProtocolAndPackagedRuntime();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== Async-Export Invoker Production Tests Passed ===");
    }

    private static void testValueSevenBothTargets() throws Exception {
        System.out.println("-- the pinned value-7 project through both invokers");
        for (String backend : List.of("luajit", "jvm")) {
            Project project = compile("value7-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend), VALUE_SEVEN_SOURCE, null,
                null);
            try {
                Object result = invoke(project, "run", "int");
                checkEq(valueResult(backend, "int", "7"), result,
                    backend + ": the async export completes the pinned 7: " + result);
                if ("jvm".equals(backend)) {
                    check(Files.isRegularFile(project.classes()
                            .resolve("Main$$AsyncExportHost.class")),
                        "jvm: the compiled artifact carries the reserved"
                            + " Main$$AsyncExportHost launcher class the invoker"
                            + " resolves");
                }
            } finally {
                deleteRecursively(project.root());
            }
        }
    }

    /**
     * The numeric read carrier bridge (ISSUE-0749 review cycle 2, finding
     * 1): a member read materializes the shared emitter's numeric carrier;
     * the projection unwraps it to the number at the top level and at
     * every nested leaf, so a valid numeric completion reaches the runtime
     * matcher and encodes as its number text.
     */
    private static void testNumericReadCompletionsBothTargets() throws Exception {
        System.out.println("-- numeric read carriers unwrap to their numbers on both"
            + " targets");
        for (String backend : List.of("luajit", "jvm")) {
            Project scalar = compile("number-read-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend), NUMBER_READ_SOURCE, null,
                null);
            try {
                Object result = invoke(scalar, "run", "number");
                checkEq(valueResult(backend, "number", "7.5"), result,
                    backend + ": a number read completes its number, never the"
                        + " carrier: " + result);
                assertNoInternalKeys(backend, valueJson(result));
            } finally {
                deleteRecursively(scalar.root());
            }
            Project nested = compile("number-read-nested-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend), NESTED_NUMBER_READ_SOURCE,
                null, null);
            try {
                Object result = invoke(nested, "run", "[number]");
                checkEq(valueResult(backend, "[number]", "[7.5,8.25]"), result,
                    backend + ": a nested number read completes its numbers: "
                        + result);
                assertNoInternalKeys(backend, valueJson(result));
            } finally {
                deleteRecursively(nested.root());
            }
            Project intRead = compile("int-read-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend), INT_READ_SOURCE, null,
                null);
            try {
                Object result = invoke(intRead, "run", "int");
                checkEq(valueResult(backend, "int", "7"), result,
                    backend + ": an int read completes its integral number: "
                        + result);
            } finally {
                deleteRecursively(intRead.root());
            }
            checkNumberCompletion(backend, "number-arithmetic",
                NUMBER_ARITHMETIC_SOURCE, "number", "5.0");
            checkNumberCompletion(backend, "number-precision",
                NUMBER_PRECISION_SOURCE, "number", "0.30000000000000004");
            checkNumberCompletion(backend, "number-magnitude",
                NUMBER_MAGNITUDE_SOURCE, "number", "1.0E17");
            checkNumberCompletion(backend, "number-int-variant",
                NUMBER_INT_VARIANT_SOURCE, "number", "7");
        }
    }

    /**
     * One scalar number completion through the public invoker: the text
     * must be the shared value model's own spelling of the completion's
     * variant (the JVM sibling's boxed-double/long text), never the
     * default {@code tostring}'s lossy {@code %.14g} for a top-level
     * scalar.
     */
    private static void checkNumberCompletion(String backend, String name,
            String source, String descriptor, String expectedJson)
            throws Exception {
        Project project = compile(name + "-" + backend, backend,
            String.format(PLAIN_MANIFEST, backend), source, null, null);
        try {
            Object result = invoke(project, "run", descriptor);
            checkEq(valueResult(backend, descriptor, expectedJson), result,
                backend + ": " + name + " completes " + expectedJson + ": "
                    + result);
            assertNoInternalKeys(backend, valueJson(result));
        } finally {
            deleteRecursively(project.root());
        }
    }

    /**
     * The empty-array boundary (ISSUE-0749 review cycle 2, finding 2): an
     * array carrier keeps the JSON array shape at every depth — a
     * zero-length array is {@code []}, never the empty object {@code {}}
     * the maxIdx-only array detection produced.
     */
    private static void testEmptyArrayCompletionsBothTargets() throws Exception {
        System.out.println("-- empty and nested empty arrays keep the JSON array shape");
        for (String backend : List.of("luajit", "jvm")) {
            Project empty = compile("empty-array-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend), EMPTY_ARRAY_SOURCE, null,
                null);
            try {
                Object result = invoke(empty, "run", "[int]");
                checkEq(valueResult(backend, "[int]", "[]"), result,
                    backend + ": an empty int[] completion stays a JSON array: "
                        + result);
            } finally {
                deleteRecursively(empty.root());
            }
            Project nested = compile("nested-empty-array-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend), NESTED_EMPTY_ARRAY_SOURCE,
                null, null);
            try {
                Object result = invoke(nested, "run", "[[int]]");
                checkEq(valueResult(backend, "[[int]]", "[[],[7]]"), result,
                    backend + ": a nested empty array keeps its shape at every"
                        + " depth: " + result);
            } finally {
                deleteRecursively(nested.root());
            }
            Project field = compile("empty-array-field-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend), EMPTY_ARRAY_FIELD_SOURCE,
                null, null);
            try {
                Object result = invoke(field, "run", "table");
                String json = valueJson(result);
                check(json != null && json.contains("\"xs\":[]"),
                    backend + ": an empty array field stays a JSON array: " + json);
                check(json != null && json.contains("\"n\":null"),
                    backend + ": the null field beside it stays JSON null: " + json);
                assertNoInternalKeys(backend, json);
            } finally {
                deleteRecursively(field.root());
            }
        }
    }

    /**
     * The graph-aware projection (ISSUE-0749 review cycle 2, finding 3): a
     * value with a shared reference encodes at every position (the memoized
     * copy preserves identity), and a cyclic value reaches the encoder's
     * cycle rejection — a value-representation failure on both targets,
     * never a stack overflow misread as an infrastructure failure.
     */
    private static void testGraphCompletionsBothTargets() throws Exception {
        System.out.println("-- shared references encode; cycles are representation"
            + " failures");
        for (String backend : List.of("luajit", "jvm")) {
            Project shared = compile("shared-reference-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend), SHARED_REFERENCE_SOURCE,
                null, null);
            try {
                Object result = invoke(shared, "run", "table");
                checkEq(valueResult(backend, "table",
                        "{\"a\":{\"v\":7},\"b\":{\"v\":7}}"), result,
                    backend + ": a shared reference encodes at every position: "
                        + result);
                assertNoInternalKeys(backend, valueJson(result));
            } finally {
                deleteRecursively(shared.root());
            }
            Project cyclic = compile("cyclic-completion-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend), CYCLIC_COMPLETION_SOURCE,
                null, null);
            try {
                invoke(cyclic, "run", "table");
                check(false, backend + ": a cyclic completion is a hard failure,"
                    + " never a value");
            } catch (LuaJitAsyncExportInvocationException
                    | JvmAsyncExportInvocationException failure) {
                check(failure.getMessage() != null
                        && failure.getMessage().startsWith(
                            "value-representation failure")
                        && failure.getMessage().contains(
                            "unsupported type for JSON encoding: table"),
                    backend + ": the cycle rejection is the pinned"
                        + " representation failure: " + failure.getMessage());
            } finally {
                deleteRecursively(cyclic.root());
            }
        }
    }

    /**
     * The parsed-JSON completion surface (the latest ISSUE-0749 review,
     * finding 1): a {@code std/json} decode carries each numeric leaf's
     * variant in the carrier itself, so unwrapping a leaf must record the
     * destination slot's own variant — the precision-boundary and nested
     * fractional leaves spell through the shared walker's canonical text,
     * an integral leaf keeps the integer spelling, and the decoded
     * container shapes (an empty array, a null field) survive the
     * projection without a carrier key. The JVM sibling is the reference.
     */
    private static void testParsedJsonCompletionsBothTargets() throws Exception {
        System.out.println("-- parsed-JSON leaves keep their numeric variants"
            + " and shapes");
        for (String backend : List.of("luajit", "jvm")) {
            Project numbers = compile("parsed-json-numbers-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend),
                PARSED_JSON_NUMBERS_SOURCE, null, null);
            try {
                Object result = invoke(numbers, "run", "?table");
                checkEq(valueResult(backend, "?table",
                        "{\"x\":0.30000000000000004,\"xs\":[5.0],\"n\":7}"),
                    result,
                    backend + ": every parsed numeric leaf keeps its variant"
                        + " text: " + result);
                assertNoInternalKeys(backend, valueJson(result));
            } finally {
                deleteRecursively(numbers.root());
            }
            Project containers = compile("parsed-json-containers-" + backend,
                backend, String.format(PLAIN_MANIFEST, backend),
                PARSED_JSON_CONTAINER_SOURCE, null, null);
            try {
                Object result = invoke(containers, "run", "?table");
                checkEq(valueResult(backend, "?table",
                        "{\"xs\":[],\"o\":{\"n\":null}}"), result,
                    backend + ": a parsed empty array and null field keep their"
                        + " JSON shapes: " + result);
                assertNoInternalKeys(backend, valueJson(result));
            } finally {
                deleteRecursively(containers.root());
            }
            Project exponents = compile("parsed-json-exponents-" + backend,
                backend, String.format(PLAIN_MANIFEST, backend),
                PARSED_JSON_EXPONENT_SOURCE, null, null);
            try {
                Object result = invoke(exponents, "run", "?table");
                checkEq(valueResult(backend, "?table",
                        "{\"xs\":[1.0E17,1.0E-320,-0.0]}"), result,
                    backend + ": parsed exponent and signed-zero leaves keep"
                        + " their canonical text: " + result);
                assertNoInternalKeys(backend, valueJson(result));
            } finally {
                deleteRecursively(exponents.root());
            }
        }
    }

    /**
     * The absent/present-null slot distinction (the latest ISSUE-0749 review,
     * finding 2): a deleted array element is the internal missing slot and
     * the shared encoder rejects it as a value-representation failure on
     * both targets — the projection never fabricates a present JSON null
     * from an absent slot. A genuinely present null element keeps the JSON
     * null slot, so the two remain distinguishable.
     */
    private static void testAbsentAndNullSlotsBothTargets() throws Exception {
        System.out.println("-- a deleted slot is a representation failure; an"
            + " explicit null slot stays JSON null");
        for (String backend : List.of("luajit", "jvm")) {
            assertRepresentationFailure(backend, "deleted-slot",
                DELETED_SLOT_SOURCE, "table",
                "unsupported type for JSON encoding: nil");
            Project project = compile("explicit-null-slot-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend),
                EXPLICIT_NULL_SLOT_SOURCE, null, null);
            try {
                Object result = invoke(project, "run", "table");
                checkEq(valueResult(backend, "table", "{\"xs\":[null,8]}"),
                    result,
                    backend + ": a present null slot keeps its JSON null: "
                        + result);
                assertNoInternalKeys(backend, valueJson(result));
            } finally {
                deleteRecursively(project.root());
            }
        }
    }

    /**
     * The non-encodable value carriers (the latest ISSUE-0749 review, finding
     * 3): a valid compiled class instance, a declared function value, and
     * the builtin Error carrier are admitted by the canonical matcher —
     * the projection carries the matcher's own class and function shapes —
     * and the shared encoder rejects each as a value-representation
     * failure, exactly like the JVM sibling. A fabricated DEAL error for a
     * valid completion is the defect this pins, at the top level, under a
     * nullable descriptor, nested in an array, and as a table field.
     */
    private static void testNonEncodableCarrierCompletionsBothTargets()
            throws Exception {
        System.out.println("-- class, function, and Error completions reach the"
            + " encoder as representation failures");
        for (String backend : List.of("luajit", "jvm")) {
            assertRepresentationFailure(backend, "class-completion",
                CLASS_COMPLETION_SOURCE, "@src/C",
                "unsupported type for JSON encoding: table");
            assertRepresentationFailure(backend, "class-array-completion",
                CLASS_ARRAY_COMPLETION_SOURCE, "[@src/C]",
                "unsupported type for JSON encoding: table");
            assertRepresentationFailure(backend, "nullable-class-completion",
                NULLABLE_CLASS_COMPLETION_SOURCE, "?@src/C",
                "unsupported type for JSON encoding: table");
            assertRepresentationFailure(backend, "function-completion",
                FUNCTION_COMPLETION_SOURCE, "()->int",
                "unsupported type for JSON encoding: function");
            assertRepresentationFailure(backend, "function-array-completion",
                FUNCTION_ARRAY_COMPLETION_SOURCE, "[()->int]",
                "unsupported type for JSON encoding: function");
            assertRepresentationFailure(backend, "stdlib-function-completion",
                STDLIB_FUNCTION_COMPLETION_SOURCE, "(table)->string",
                "unsupported type for JSON encoding: function");
            assertRepresentationFailure(backend, "error-field-completion",
                ERROR_FIELD_COMPLETION_SOURCE, "table",
                "unsupported type for JSON encoding: table");
        }
    }

    /**
     * The parameterized published wrapper (ISSUE-0749 review cycle 2,
     * finding 4): the retained-caller ABI's {@code run.f(7)} forwards its
     * declared argument into the declaration's async entry (the operation
     * completes the checked 7), and a missing argument still reaches the
     * body's parameter boundary as the pinned E8001 rejection — the
     * published callable never drops or invents arguments. The probe runs
     * the compiled chunk under real LuaJIT through its published surface;
     * the JVM's published surface is the Java export table (the analogous
     * retained-caller path already forwards its arguments).
     */
    private static void testParameterizedPublishedWrapper() throws Exception {
        System.out.println("-- the parameterized published wrapper forwards its"
            + " arguments");
        Project project = compile("published-wrapper-luajit", "luajit",
            String.format(PLAIN_MANIFEST, "luajit"), PARAMETERIZED_SOURCE, null,
            null);
        try {
            writeFile(project.root(), "probe.lua", """
                package.path = "./out/?.lua;./out/std/?.lua;" .. package.path
                local rt = require("deal.runtime")
                local exports = dofile("out/main.lua")
                local handle = exports.run.f(7)
                assert(type(handle) == "table" and handle.__kind == "async",
                  "the published parameterized export produces an operation: "
                    .. tostring(handle))
                rt.async_step(handle)
                local checked = rt.check_canonical_type("int", handle.__result)
                assert(checked == 7, "the forwarded argument completes 7, got "
                    .. tostring(checked))
                local ok, err = pcall(exports.run.f)
                assert(not ok and type(err) == "table" and err.code == "E8001",
                  "a missing argument stays the parameter boundary rejection")
                print("PUBLISHED:" .. tostring(checked))
                """);
            ProcessOutcome outcome = runProcess(
                List.of("luajit", "probe.lua"), project.root());
            checkEq(0, outcome.exitCode(),
                "luajit: the parameterized published-wrapper probe exits 0: "
                    + outcome.output());
            check(outcome.stdout().contains("PUBLISHED:7"),
                "luajit: run.f(7) forwards its argument and completes 7: "
                    + outcome.stdout());
        } finally {
            deleteRecursively(project.root());
        }
    }

    /**
     * The completion-value representation bridge on both targets: a null
     * completion reaches the runtime matcher as the runtime's own null
     * sentinel, and array/table completions are encoded as the checked
     * arrays/plain objects — never as the emitter's internal carrier
     * metadata (ISSUE-0749 review cycle 1, findings 1 and 2).
     */
    private static void testNullAndContainerCompletionsBothTargets() throws Exception {
        System.out.println("-- null, array, and table completions through both invokers");
        for (String backend : List.of("luajit", "jvm")) {
            Project nullCompletion = compile("null-completion-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend), NULL_COMPLETION_SOURCE,
                null, null);
            try {
                Object result = invoke(nullCompletion, "run", "null");
                checkEq(valueResult(backend, "null", "null"), result,
                    backend + ": a null completion is the runtime's checked null: "
                        + result);
            } finally {
                deleteRecursively(nullCompletion.root());
            }

            Project array = compile("array-completion-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend), ARRAY_COMPLETION_SOURCE,
                null, null);
            try {
                Object result = invoke(array, "run", "[int]");
                checkEq(valueResult(backend, "[int]", "[7,8]"), result,
                    backend + ": an int[] completion encodes as the checked JSON"
                        + " array: " + result);
                assertNoInternalKeys(backend, valueJson(result));
            } finally {
                deleteRecursively(array.root());
            }

            Project table = compile("table-completion-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend), TABLE_COMPLETION_SOURCE,
                null, null);
            try {
                Object result = invoke(table, "run", "table");
                checkEq(valueResult(backend, "table", "{\"a\":7}"), result,
                    backend + ": a plain-object completion encodes as the checked"
                        + " JSON object: " + result);
                assertNoInternalKeys(backend, valueJson(result));
                assertDecodedTableShape(backend, valueJson(result));
            } finally {
                deleteRecursively(table.root());
            }

            Project nested = compile("nested-completion-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend), NESTED_COMPLETION_SOURCE,
                null, null);
            try {
                Object result = invoke(nested, "run", "table");
                String json = valueJson(result);
                check(result instanceof LuaJitAsyncExportInvoker.Result.Value
                        || result instanceof JvmAsyncExportInvoker.Result.Value,
                    backend + ": the nested completion is a value: " + result);
                check(json != null && json.contains("\"xs\":[7,8]"),
                    backend + ": a nested array field keeps its JSON array shape: "
                        + json);
                check(json != null && json.contains("\"n\":null"),
                    backend + ": a null field stays the JSON null slot: " + json);
                assertNoInternalKeys(backend, json);
            } finally {
                deleteRecursively(nested.root());
            }
        }
    }

    /**
     * The preserved representation-failure classification
     * (ISSUE-0749 review cycle 1, finding 2): a bytes completion passes
     * the canonical matcher (the declared bytes surface) and the encoding
     * step rejects it on both targets — a hard failure, never a DEAL code
     * and never a value. The non-finite number boundary is the same
     * status through the encoder's own finite check.
     */
    private static void testRepresentationFailureBothTargets() throws Exception {
        System.out.println("-- bytes and non-finite completions are representation"
            + " failures on both targets");
        for (String backend : List.of("luajit", "jvm")) {
            assertRepresentationFailure(backend, "bytes-completion",
                BYTES_COMPLETION_SOURCE, "bytes",
                "unsupported type for JSON encoding: bytes");
            assertRepresentationFailure(backend, "infinite-number",
                INFINITE_NUMBER_SOURCE, "number",
                "unsupported type for JSON encoding: number");
        }
    }

    /** One completion the encoder must reject as a value-representation failure. */
    private static void assertRepresentationFailure(String backend, String name,
            String source, String descriptor, String pinnedReason)
            throws Exception {
        Project project = compile(name + "-" + backend, backend,
            String.format(PLAIN_MANIFEST, backend), source, null, null);
        try {
            invoke(project, "run", descriptor);
            check(false, backend + ": " + name + " is a hard failure, never"
                + " a value");
        } catch (LuaJitAsyncExportInvocationException
                | JvmAsyncExportInvocationException failure) {
            check(failure.getMessage() != null
                    && failure.getMessage().startsWith(
                        "value-representation failure")
                    && failure.getMessage().contains(pinnedReason),
                backend + ": the representation failure carries the pinned"
                    + " encoder rejection: " + failure.getMessage());
        } finally {
            deleteRecursively(project.root());
        }
    }

    private static String valueJson(Object result) {
        if (result instanceof LuaJitAsyncExportInvoker.Result.Value value) {
            return value.valueJson();
        }
        if (result instanceof JvmAsyncExportInvoker.Result.Value value) {
            return value.valueJson();
        }
        return null;
    }

    /** The emitter's carrier marks: no encoded completion may carry one. */
    private static final List<String> INTERNAL_CARRIER_KEYS = List.of(
        "\"__a\"", "\"__n\"", "\"__t\"", "\"__order\"", "\"__keys\"",
        "\"__nK\"", "\"__da\"", "\"__c\"", "\"__id\"", "\"__f\"",
        "\"__p\"");

    private static void assertNoInternalKeys(String backend, String valueJson) {
        check(valueJson != null,
            backend + ": the completion value is JSON text");
        if (valueJson == null) {
            return;
        }
        for (String key : INTERNAL_CARRIER_KEYS) {
            check(!valueJson.contains(key),
                backend + ": the encoded completion carries no internal carrier"
                    + " key " + key + ": " + valueJson);
        }
    }

    /**
     * The decoded object shape of the plain-object completion: exactly the
     * declared {@code a} field with the integer 7 — every internal carrier
     * key is absent by construction of the strict reader's field map.
     */
    private static void assertDecodedTableShape(String backend, String valueJson) {
        if (valueJson == null) {
            return;
        }
        LuaJitAsyncExportInvoker.EnvelopeJson.Value decoded =
            LuaJitAsyncExportInvoker.EnvelopeJson.parseValue(valueJson);
        check(decoded instanceof LuaJitAsyncExportInvoker.EnvelopeJson.ObjectValue,
            backend + ": the plain-object completion decodes as a JSON object: "
                + valueJson);
        if (!(decoded instanceof LuaJitAsyncExportInvoker.EnvelopeJson.ObjectValue object)) {
            return;
        }
        checkEq(Set.of("a"), object.fields().keySet(),
            backend + ": the decoded object carries exactly the declared field");
        LuaJitAsyncExportInvoker.EnvelopeJson.Value field = object.fields().get("a");
        check(field instanceof LuaJitAsyncExportInvoker.EnvelopeJson.NumberValue number
                && "7".equals(number.token()),
            backend + ": the decoded field a is the number 7: " + field);
    }

    private static void testDelayedHostCompletionBothTargets() throws Exception {
        System.out.println("-- a delayed host async completion awaited by the export");
        for (String backend : List.of("luajit", "jvm")) {
            Project project = compile("delayed-" + backend, backend,
                String.format(EXTERNALS_HEADER, backend), DELAYED_SOURCE,
                DELAYED_DECLARATION,
                "luajit".equals(backend) ? DELAYED_LUA_HOST : DELAYED_JVM_HOST);
            try {
                Object result = invoke(project, "run", "int");
                checkEq(valueResult(backend, "int", "7"), result,
                    backend + ": the delayed host completion reaches the"
                        + " completion as 7: " + result);
            } finally {
                deleteRecursively(project.root());
            }
        }
    }

    private static void testTypedCompletionRejectionBothTargets() throws Exception {
        System.out.println("-- a typed completion rejection at the await site");
        for (String backend : List.of("luajit", "jvm")) {
            Project project = compile("mismatch-" + backend, backend,
                String.format(EXTERNALS_HEADER, backend), MISMATCH_SOURCE,
                MISMATCH_DECLARATION,
                "luajit".equals(backend) ? MISMATCH_LUA_HOST : MISMATCH_JVM_HOST);
            try {
                Object result = invoke(project, "run", "int");
                check(result instanceof LuaJitAsyncExportInvoker.Result.DealError
                        || result instanceof JvmAsyncExportInvoker.Result.DealError,
                    backend + ": a mismatched completion is a DEAL error, never a"
                        + " value or a host failure: " + result);
                checkEq("E8001", errorCode(result),
                    backend + ": the completion rejection carries the pinned code");
                checkEq("expected string", errorMessage(result),
                    backend + ": the completion rejection carries the pinned message");
                assertLocation(backend, result, project.root()
                    .resolve("src/main.deal"), 8, 19);
            } finally {
                deleteRecursively(project.root());
            }
        }
    }

    private static void testUnchangedErrorFieldsBothTargets() throws Exception {
        System.out.println("-- DEAL errors raised by the operation and by main()");
        for (String backend : List.of("luajit", "jvm")) {
            Project operation = compile("op-error-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend), OPERATION_ERROR_SOURCE,
                null, null);
            try {
                Object result = invoke(operation, "run", "int");
                checkEq("E8005", errorCode(result),
                    backend + ": the operation error keeps its code: " + result);
                checkEq("integer division by zero", errorMessage(result),
                    backend + ": the operation error keeps its message: " + result);
                assertLocation(backend, result, operation.root()
                    .resolve("src/main.deal"), 4, 16);
            } finally {
                deleteRecursively(operation.root());
            }
            Project main = compile("main-error-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend), MAIN_ERROR_SOURCE, null, null);
            try {
                Object result = invoke(main, "run", "int");
                checkEq("E8005", errorCode(result),
                    backend + ": the main() error keeps its code: " + result);
                checkEq("integer division by zero", errorMessage(result),
                    backend + ": the main() error keeps its message: " + result);
                assertLocation(backend, result, main.root()
                    .resolve("src/main.deal"), 8, 16);
            } finally {
                deleteRecursively(main.root());
            }
        }
    }

    /**
     * A source path that legally contains a line terminator: the carried
     * {@code file:line:column} origin still projects the exact file, line,
     * and column on both targets (the LuaJIT pattern already matched such
     * a path; the JVM origin regex did not).
     */
    private static void testLineTerminatorSourcePathBothTargets() throws Exception {
        System.out.println("-- a line terminator in the source path keeps the"
            + " exact coordinates");
        for (String backend : List.of("luajit", "jvm")) {
            Path parent = Files.createTempDirectory("async-export-folded-path-");
            Path root = parent.resolve("proj\nect");
            Files.createDirectories(root);
            try {
                Project project = compileAt(root, "newline-path-" + backend, backend,
                    String.format(PLAIN_MANIFEST, backend), USER_ERROR_SOURCE,
                    null, null);
                Object result = invoke(project, "run", "int");
                checkEq("E_USER", errorCode(result),
                    backend + ": the user error keeps its code: " + result);
                checkEq("unchanged\nmessage", errorMessage(result),
                    backend + ": the user error keeps its newline-bearing message: "
                        + result);
                assertLocation(backend, result, root.resolve("src/main.deal"), 2, 3);
            } finally {
                deleteRecursively(parent);
            }
        }
    }

    /**
     * The exact carried source location on both targets: the runtime
     * error's own {@code file:line:column} origin, projected through the
     * shared DEAL-error envelope (ISSUE-0749 review cycle 1, finding 3).
     */
    private static void assertLocation(String backend, Object result, Path source,
                                       int line, int column) {
        checkEq(source.toAbsolutePath().toString(), errorFile(result),
            backend + ": the DEAL error keeps its file");
        checkEq(Integer.valueOf(line), errorLine(result),
            backend + ": the DEAL error keeps its line");
        checkEq(Integer.valueOf(column), errorColumn(result),
            backend + ": the DEAL error keeps its column");
    }

    /**
     * The shared DEAL-error envelope projection: a carried
     * {@code file:line:column} origin is preserved with the line and
     * column as JSON integers, and only a genuinely absent origin
     * (null or the emitter's {@code "-"} marker) omits the location
     * fields (ISSUE-0749 review cycle 1, finding 3). The structural
     * control pins the absent-origin arm, which no reachable production
     * invocation produces once the completion representation matches the
     * deployed runtime.
     */
    private static void testDealErrorEnvelopeProjection() {
        System.out.println("-- the shared DEAL-error envelope projection");
        String prefix = JvmRuntime.ASYNC_EXPORT_RESULT_PREFIX;
        checkEq(prefix + "{\"status\":\"deal-error\",\"code\":\"E8005\","
                + "\"message\":\"integer division by zero\","
                + "\"file\":\"/abs/src/main.deal\",\"line\":4,\"column\":16}",
            JvmRuntime.asyncExportDealErrorEnvelope("E8005",
                "integer division by zero", "/abs/src/main.deal:4:16"),
            "a carried file:line:column origin projects with integer line/column");
        checkEq(prefix + "{\"status\":\"deal-error\",\"code\":\"E_USER\","
                + "\"message\":\"unchanged\\nmessage\","
                + "\"file\":\"/abs/pro\\nject/src/main.deal\",\"line\":2,"
                + "\"column\":3}",
            JvmRuntime.asyncExportDealErrorEnvelope("E_USER", "unchanged\nmessage",
                "/abs/pro\nject/src/main.deal:2:3"),
            "an origin whose file text contains a line terminator still splits"
                + " into file, line, and column");
        AsyncExportEnvelope.Envelope decoded = AsyncExportEnvelope.decodeEnvelope(
            JvmRuntime.asyncExportDealErrorEnvelope("E8005",
                "integer division by zero", "/abs/src/main.deal:4:16")
                .substring(prefix.length()));
        checkEq("/abs/src/main.deal", decoded.file,
            "the shared codec reads the projected file");
        checkEq(Integer.valueOf(4), decoded.line,
            "the shared codec reads the projected line");
        checkEq(Integer.valueOf(16), decoded.column,
            "the shared codec reads the projected column");
        checkEq(prefix + "{\"status\":\"deal-error\",\"code\":\"E8001\","
                + "\"message\":\"expected int\"}",
            JvmRuntime.asyncExportDealErrorEnvelope("E8001", "expected int", "-"),
            "the emitter's absent-origin marker omits every location field");
        checkEq(prefix + "{\"status\":\"deal-error\",\"code\":\"E8001\","
                + "\"message\":\"expected int\"}",
            JvmRuntime.asyncExportDealErrorEnvelope("E8001", "expected int", null),
            "an absent origin omits every location field");
    }

    private static void testInitializationAndMainOnceBothTargets() throws Exception {
        System.out.println("-- module initialization and main() run exactly once per"
            + " invocation");
        for (String backend : List.of("luajit", "jvm")) {
            Project project = compile("counts-" + backend, backend,
                String.format(EXTERNALS_HEADER, backend), COUNTS_SOURCE,
                COUNTS_DECLARATION,
                "luajit".equals(backend) ? COUNTS_LUA_HOST : COUNTS_JVM_HOST);
            try {
                Object first = invoke(project, "run", "int");
                Object second = invoke(project, "run", "int");
                checkEq(valueResult(backend, "int", "110"), first,
                    backend + ": one host-module load and one main touch before"
                        + " the export: " + first);
                checkEq(valueResult(backend, "int", "110"), second,
                    backend + ": the second invocation starts a fresh runtime"
                        + " instance (no shared host state): " + second);
            } finally {
                deleteRecursively(project.root());
            }
        }
    }

    private static void testSharedProtocolAndPackagedRuntime() {
        System.out.println("-- the shared envelope marker and the child runtime"
            + " classpath");
        checkEq(LuaJitAsyncExportInvoker.RESULT_PREFIX,
            JvmAsyncExportInvoker.RESULT_PREFIX,
            "both invokers consume one envelope marker");
        checkEq(JvmAsyncExportInvoker.RESULT_PREFIX,
            JvmRuntime.ASYNC_EXPORT_RESULT_PREFIX,
            "the emitted JVM host surface publishes the invokers' marker");
    }

    // =========================================================================
    // Result accessors (both invoker result types)
    // =========================================================================

    private static Object valueResult(String backend, String descriptor,
                                      String valueJson) {
        return "luajit".equals(backend)
            ? new LuaJitAsyncExportInvoker.Result.Value(descriptor, valueJson)
            : new JvmAsyncExportInvoker.Result.Value(descriptor, valueJson);
    }

    private static String errorCode(Object result) {
        if (result instanceof LuaJitAsyncExportInvoker.Result.DealError error) {
            return error.code();
        }
        if (result instanceof JvmAsyncExportInvoker.Result.DealError error) {
            return error.code();
        }
        return null;
    }

    private static String errorMessage(Object result) {
        if (result instanceof LuaJitAsyncExportInvoker.Result.DealError error) {
            return error.message();
        }
        if (result instanceof JvmAsyncExportInvoker.Result.DealError error) {
            return error.message();
        }
        return null;
    }

    private static String errorFile(Object result) {
        if (result instanceof LuaJitAsyncExportInvoker.Result.DealError error) {
            return error.file();
        }
        if (result instanceof JvmAsyncExportInvoker.Result.DealError error) {
            return error.file();
        }
        return null;
    }

    private static Integer errorLine(Object result) {
        if (result instanceof LuaJitAsyncExportInvoker.Result.DealError error) {
            return error.line();
        }
        if (result instanceof JvmAsyncExportInvoker.Result.DealError error) {
            return error.line();
        }
        return null;
    }

    private static Integer errorColumn(Object result) {
        if (result instanceof LuaJitAsyncExportInvoker.Result.DealError error) {
            return error.column();
        }
        if (result instanceof JvmAsyncExportInvoker.Result.DealError error) {
            return error.column();
        }
        return null;
    }

    // =========================================================================
    // The compiled fixture project
    // =========================================================================

    private record Project(Path root, String backend, Path entryArtifact,
                           Path classes) {
    }

    /**
     * Compiles one fixture project through the public {@code deal compile}
     * entry, then stages the target's host implementation: the LuaJIT lane
     * writes {@code out/host/probe.lua} (the driver's package.path root),
     * the JVM lane compiles the emitted source plus {@code HostProbe.java}
     * into {@code classes}.
     */
    private static Project compile(String name, String backend, String manifest,
            String mainSource, String declarationSource, String hostSource)
            throws Exception {
        return compileAt(Files.createTempDirectory("async-export-" + name + "-"),
            name, backend, manifest, mainSource, declarationSource, hostSource);
    }

    /**
     * The same public compile drive against a caller-supplied project
     * root (the line-terminator source-path regression needs a legal
     * filesystem name outside the fixed temp prefix).
     */
    private static Project compileAt(Path root, String name, String backend,
            String manifest, String mainSource, String declarationSource,
            String hostSource) throws Exception {
        writeFile(root, "deal.json", manifest);
        writeFile(root, "src/main.deal", mainSource);
        if (declarationSource != null) {
            writeFile(root, "src/probe.d.deal", declarationSource);
        }
        int exit = deal.Main.run(new String[] {"compile",
            root.resolve("src/main.deal").toAbsolutePath().toString()});
        checkEq(0, exit, name + ": the public deal compile succeeds");
        if (exit != 0) {
            throw new IllegalStateException(name + ": the fixture project did not"
                + " compile through the public entry");
        }
        if ("luajit".equals(backend)) {
            if (hostSource != null) {
                writeFile(root, "out/host/probe.lua", hostSource);
            }
            return new Project(root, backend, root.resolve("out/main.lua"), null);
        }
        if (hostSource != null) {
            writeFile(root, "host/HostProbe.java", hostSource);
        }
        Path classes = root.resolve("classes");
        Files.createDirectories(classes);
        List<String> argv = new ArrayList<>(List.of("javac", "--release", "25",
            "-proc:none", "-cp", absoluteClasspath(), "-d", classes.toString(),
            root.resolve("out/Main.java").toAbsolutePath().toString()));
        if (hostSource != null) {
            argv.add(root.resolve("host/HostProbe.java").toAbsolutePath()
                .toString());
        }
        ProcessOutcome compiled = runProcess(argv, root);
        checkEq(0, compiled.exitCode(),
            name + ": the emitted JVM artifact compiles with javac: "
                + compiled.output());
        return new Project(root, backend, classes.resolve("Main.class"), classes);
    }

    /** Invokes one export through the target's production invoker. */
    private static Object invoke(Project project, String exportName,
                                 String returnDescriptor) {
        AsyncExportInvocationRequest request = new AsyncExportInvocationRequest(
            project.entryArtifact(), exportName, returnDescriptor);
        return "luajit".equals(project.backend())
            ? new LuaJitAsyncExportInvoker().invoke(request)
            : new JvmAsyncExportInvoker().invoke(request);
    }

    // =========================================================================
    // Harness
    // =========================================================================

    private record ProcessOutcome(int exitCode, String stdout, String stderr) {
        String output() {
            return "exit=" + exitCode + " stdout=<" + stdout + "> stderr=<"
                + stderr + ">";
        }
    }

    private static ProcessOutcome runProcess(List<String> argv, Path directory)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.directory(directory.toFile());
        Path stdout = Files.createTempFile("async-export-stdout-", ".txt");
        Path stderr = Files.createTempFile("async-export-stderr-", ".txt");
        builder.redirectOutput(stdout.toFile());
        builder.redirectError(stderr.toFile());
        Process process = builder.start();
        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor();
            throw new IllegalStateException("the child did not exit: " + argv);
        }
        ProcessOutcome outcome = new ProcessOutcome(process.exitValue(),
            Files.readString(stdout, StandardCharsets.UTF_8),
            Files.readString(stderr, StandardCharsets.UTF_8));
        Files.deleteIfExists(stdout);
        Files.deleteIfExists(stderr);
        return outcome;
    }

    private static String absoluteClasspath() {
        StringBuilder resolved = new StringBuilder();
        for (String entry : System.getProperty("java.class.path", "")
                .split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            if (resolved.length() > 0) {
                resolved.append(File.pathSeparator);
            }
            resolved.append(Path.of(entry).toAbsolutePath().normalize());
        }
        return resolved.toString();
    }

    private static void writeFile(Path root, String relative, String text)
            throws Exception {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, text, StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
