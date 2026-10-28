package deal.test;

import deal.codegen.AsyncExportEnvelope;
import deal.codegen.jvm.JvmAsyncExportInvocationException;
import deal.codegen.jvm.JvmAsyncExportInvoker;
import deal.codegen.jvm.JvmRuntime;
import deal.codegen.lua.AsyncExportInvocationRequest;
import deal.codegen.lua.LuaJitAsyncExportInvocationException;
import deal.codegen.lua.LuaJitAsyncExportInvoker;

import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

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
 * leaves — and the decoded container shapes survive); deleted and
 * present-null array slots both encode JSON {@code null}; shared
 * references encode at every position and cyclic completions are
 * value-representation failures. Nonfinite numbers encode null; nominal
 * class fields and builtin Error code/message encode without private
 * metadata. Function array elements encode null, while function roots
 * have no JSON representation and fail before envelope serialization. A
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
    private static JavacPreparation javacPreparation;

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
        export async function run(): Promise<number> {
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
        export async function run(): Promise<null> {
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
        export async function run(): Promise<number[]> {
          return [7.0, 8];
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
        export async function run(): Promise<Record<string, unknown>> {
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
        export async function run(): Promise<Record<string, unknown>> {
          return { a: 7, xs: [7.0, 8], n: null };
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
        export async function run(): Promise<number> {
          let t: Record<string, unknown> = { v: 7.5 };
          let v: number = t.v as number;
          return v;
        }

        export function main(): null {
          return null;
        }
        """;

    /** The nested numeric read: array elements carry read carriers too. */
    private static final String NESTED_NUMBER_READ_SOURCE = """
        export async function run(): Promise<number[]> {
          let t: Record<string, unknown> = { v: 7.5 };
          let v: number = t.v as number;
          return [v, 8.25];
        }

        export function main(): null {
          return null;
        }
        """;

    /** The int-variant read carrier unwraps to its integral number. */
    private static final String INT_READ_SOURCE = """
        export async function run(): Promise<number> {
          let t: Record<string, unknown> = { v: 7 };
          let v: number = t.v as number;
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
        export async function run(): Promise<number> {
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
        export async function run(): Promise<number> {
          let t: Record<string, unknown> = { v: 0.30000000000000004 };
          let v: number = t.v as number;
          return v;
        }

        export function main(): null {
          return null;
        }
        """;

    /** The magnitude boundary of the number text (exponent spelling). */
    private static final String NUMBER_MAGNITUDE_SOURCE = """
        export async function run(): Promise<number> {
          let t: Record<string, unknown> = { v: 1e17 };
          let v: number = t.v as number;
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
        export async function run(): Promise<number> {
          let t: Record<string, unknown> = { v: 7 };
          let v: number = t.v as number;
          return v;
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * A nonfinite number passes its declared matcher and encodes JSON null.
     */
    private static final String INFINITE_NUMBER_SOURCE = """
        export async function run(): Promise<number> {
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
        export async function run(): Promise<number[]> {
          return [];
        }

        export function main(): null {
          return null;
        }
        """;

    /** Nested empty arrays keep the JSON array shape at every depth. */
    private static final String NESTED_EMPTY_ARRAY_SOURCE = """
        export async function run(): Promise<number[][]> {
          let xs: number[][] = [[], [7]];
          return xs;
        }

        export function main(): null {
          return null;
        }
        """;

    /** An empty array inside an object field keeps the JSON array shape. */
    private static final String EMPTY_ARRAY_FIELD_SOURCE = """
        export async function run(): Promise<Record<string, unknown>> {
          let xs: number[] = [];
          return { a: 7, xs: xs, n: null };
        }

        export function main(): null {
          return null;
        }
        """;

    /** A shared reference: repeated identities encode at every position. */
    private static final String SHARED_REFERENCE_SOURCE = """
        export async function run(): Promise<Record<string, unknown>> {
          let t: Record<string, unknown> = { v: 7 };
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
        export async function run(): Promise<Record<string, unknown>> {
          let t: Record<string, unknown> = { v: 7 };
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

        export async function run(): Promise<Record<string, unknown> | null> {
          return json.parse("{\\"x\\":0.30000000000000004,\\"xs\\":[5],\\"n\\":7.0}");
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

        export async function run(): Promise<Record<string, unknown> | null> {
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

        export async function run(): Promise<Record<string, unknown> | null> {
          return json.parse("{\\"xs\\":[1e17,1e-320,0]}");
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * A deleted array slot stays absent in the language but encodes JSON
     * null under the generic JSON array-position rule on both targets.
     */
    private static final String DELETED_SLOT_SOURCE = """
        export async function run(): Promise<Record<string, unknown>> {
          let xs: (number | undefined)[] = [7.0, 8];
          xs[0] = undefined;
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
        export async function run(): Promise<Record<string, unknown>> {
          let xs: (number | null)[] = [null, 8];
          return { xs: xs };
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * A nominal class completion retains declared field storage through
     * canonical matching and JSON encoding, without exposing carrier metadata.
     */
    private static final String CLASS_COMPLETION_SOURCE = """
        class C {
          x: number;
        }

        export async function run(): Promise<C> {
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
          x: number;
        }

        export async function run(): Promise<C[]> {
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
          x: number;
        }

        export async function run(): Promise<C | null> {
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
        export async function run(): Promise<() => number> {
          let f: () => number = function(): number { return 7; };
          return f;
        }

        export function main(): null {
          return null;
        }
        """;

    /** The same function value nested in an array completion. */
    private static final String FUNCTION_ARRAY_COMPLETION_SOURCE = """
        export async function run(): Promise<(() => number)[]> {
          let f: () => number = function(): number { return 7; };
          return [f];
        }

        export function main(): null {
          return null;
        }
        """;

    /** A stdlib callable completion under its declared function descriptor. */
    private static final String STDLIB_FUNCTION_COMPLETION_SOURCE = """
        import * as json from "std/json";

        export async function run(): Promise<(t: unknown) => string | undefined> {
          return json.stringify;
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * An Error member retains code/message through completion projection;
     * JSON exposes neither transport metadata nor a replacement message.
     */
    private static final String ERROR_FIELD_COMPLETION_SOURCE = """
        function passThrough(): Error {
          try { throw { code: "E_CANON", message: "roundtrip" }; }
          catch (e) { return e as Error; }
        }

        export async function run(): Promise<Record<string, unknown>> {
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
        export async function run(x: number): Promise<number> {
          return x;
        }

        export function main(): null {
          return null;
        }
        """;

    /** The delayed host completion: the export awaits the host's delayed int operation. */
    private static final String DELAYED_SOURCE = """
        import * as probe from "host/probe";

        export function main(): null {
          return null;
        }

        export async function run(): Promise<number> {
          let v: number = await probe.fetchDelayed();
          return v;
        }
        """;

    private static final String DELAYED_DECLARATION = """
        export function fetchDelayed(): Promise<number>;
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
        import * as probe from "host/probe";

        export function main(): null {
          return null;
        }

        export async function run(): Promise<number> {
          let v: string = await probe.fetchMismatch();
          return 7;
        }
        """;

    private static final String MISMATCH_DECLARATION = """
        export function fetchMismatch(): Promise<string>;
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
        export async function run(): Promise<number> {
          let z: number[] = [1];
          let w: number = 0;
          let q: number = z[w + 1];
          return q;
        }

        export function main(): null {
          return null;
        }
        """;

    /** The main-raised DEAL error: the same division by zero inside main(). */
    private static final String MAIN_ERROR_SOURCE = """
        export async function run(): Promise<number> {
          return 7;
        }

        export function main(): null {
          let z: number[] = [1];
          let w: number = 0;
          let q: number = z[w + 1];
          return null;
        }
        """;

    /**
     * The user-raised DEAL error of the line-terminator source-path
     * regression: the thrown error's message carries a line terminator
     * of its own, and the error's origin is the throw at 2:3.
     */
    private static final String USER_ERROR_SOURCE = """
        export async function run(): Promise<number> {
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
        import * as probe from "host/probe";

        export function main(): null {
          probe.touchMain();
          return null;
        }

        export async function run(): Promise<number> {
          return probe.snapshot();
        }
        """;

    private static final String COUNTS_DECLARATION = """
        export function touchMain(): null;
        export function snapshot(): number;
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
          "languageVersion": "1.5",
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
          "languageVersion": "1.5",
          "moduleRoots": ["src"],
          "output": "out",
          "backend": "%s"
        }
        """;

    /**
     * ISSUE-0756 subject {@code async-extended-argument-order}: the
     * awaited arity-extension adapter must evaluate the ignored awaited
     * argument before the source async call. The export's own trace
     * oracle raises unless the observed order is A, B, then the source
     * F, and the subject keeps its own production output — the
     * package's independent stdout expectation. Reused unchanged by the
     * corpus-faithful drive below; the classification metadata of the
     * package is not part of the subject source.
     */
    private static final String ARITY_EXTENSION_SOURCE = """
        import * as c from "std/console";

        export function main(): null { return null; }

        export async function run(): Promise<null> {
          let trace: string = "";
          async function value(s: string, x: number): Promise<number> { trace = trace + s; return x; }
          async function small(x: number): Promise<number> { trace = trace + "F"; return x; }
          let f: (x: number, y: number) => Promise<number> = small;
          let r: number = await f(await value("A", 7), await value("B", 8));
          if (r !== 7 || trace !== "ABF") {
            throw { code: "ARITY_FAIL", message: "async extended arguments: " + trace };
          }
          c.log("async-extended-argument-order");
          return null;
        }
        """;

    /** The subject's own production stdout: the package's exact pin. */
    private static final String ARITY_EXTENSION_MARKER =
        "async-extended-argument-order\n";

    /**
     * The production host's value envelope of the checked null
     * completion (the runtime half's matcher validated the completion
     * before the envelope is written, so this frame is the host-side
     * completion evidence of the direct drive).
     */
    private static final String ARITY_EXTENSION_ENVELOPE =
        "DEAL_ASYNC_EXPORT_RESULT:{\"status\":\"value\","
        + "\"descriptor\":\"null\",\"value\":\"null\"}\n";

    // =========================================================================
    // Scenarios
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Async-Export Invoker Production Tests (ISSUE-0749) ===\n");

        try (JavacPreparation preparation = new JavacPreparation()) {
            javacPreparation = preparation;
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
            testAsyncArityExtensionArgumentOrderBothTargets();
            testCornerCaseAwaitErrorsBothTargets();
            testCornerCaseSyncErrorsBothTargets();
            testAwaitCapableArrayWriteBothTargets();
            testProductionHostCaptureBoundary();
            testInitializationAndMainOnceBothTargets();
            testParameterizedPublishedWrapper();
            testDealErrorEnvelopeProjection();
            testSharedProtocolAndPackagedRuntime();
        } finally {
            javacPreparation = null;
        }

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
                Object result = invoke(project, "run", "number");
                checkEq(valueResult(backend, "number", "7.0"), result,
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
            Project intRead = compile("number-read-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend), INT_READ_SOURCE, null,
                null);
            try {
                Object result = invoke(intRead, "run", "number");
                checkEq(valueResult(backend, "number", "7.0"), result,
                    backend + ": an number read completes its integral number: "
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
            checkNumberCompletion(backend, "number-number-variant",
                NUMBER_INT_VARIANT_SOURCE, "number", "7.0");
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
                Object result = invoke(empty, "run", "[number]");
                checkEq(valueResult(backend, "[number]", "[]"), result,
                    backend + ": an empty number[] completion stays a JSON array: "
                        + result);
            } finally {
                deleteRecursively(empty.root());
            }
            Project nested = compile("nested-empty-array-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend), NESTED_EMPTY_ARRAY_SOURCE,
                null, null);
            try {
                Object result = invoke(nested, "run", "[[number]]");
                checkEq(valueResult(backend, "[[number]]", "[[],[7.0]]"), result,
                    backend + ": a nested empty array keeps its shape at every"
                        + " depth: " + result);
            } finally {
                deleteRecursively(nested.root());
            }
            Project field = compile("empty-array-field-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend), EMPTY_ARRAY_FIELD_SOURCE,
                null, null);
            try {
                Object result = invoke(field, "run", "record");
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
                Object result = invoke(shared, "run", "record");
                checkEq(valueResult(backend, "record",
                        "{\"a\":{\"v\":7.0},\"b\":{\"v\":7.0}}"), result,
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
                invoke(cyclic, "run", "record");
                check(false, backend + ": a cyclic completion is a hard failure,"
                    + " never a value");
            } catch (LuaJitAsyncExportInvocationException
                    | JvmAsyncExportInvocationException failure) {
                check(failure.getMessage() != null
                        && failure.getMessage().startsWith(
                            "value-representation failure")
                        && failure.getMessage().contains(
                            "cyclic value cannot be encoded as JSON"),
                    backend + ": the cycle rejection is the pinned closed cycle"
                        + " arm: " + failure.getMessage());
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
                Object result = invoke(numbers, "run", "?record");
                checkEq(valueResult(backend, "?record",
                        "{\"x\":0.30000000000000004,\"xs\":[5.0],\"n\":7.0}"),
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
                Object result = invoke(containers, "run", "?record");
                checkEq(valueResult(backend, "?record",
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
                Object result = invoke(exponents, "run", "?record");
                checkEq(valueResult(backend, "?record",
                        "{\"xs\":[1.0E17,1.0E-320,0]}"), result,
                    backend + ": parsed exponent and signed-zero leaves keep"
                        + " their binary64 JSON values: " + result);
                assertNoInternalKeys(backend, valueJson(result));
            } finally {
                deleteRecursively(exponents.root());
            }
        }
    }

    /**
     * Both deleted and present-null array slots encode null, without
     * changing language presence or confusing this JSON rule with admission.
     */
    private static void testAbsentAndNullSlotsBothTargets() throws Exception {
        System.out.println("-- deleted and explicit-null array slots encode JSON null");
        for (String backend : List.of("luajit", "jvm")) {
            assertEncodedCompletion(backend, "deleted-slot",
                DELETED_SLOT_SOURCE, "record", "{\"xs\":[null,8.0]}");
            Project project = compile("explicit-null-slot-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend),
                EXPLICIT_NULL_SLOT_SOURCE, null, null);
            try {
                Object result = invoke(project, "run", "record");
                checkEq(valueResult(backend, "record", "{\"xs\":[null,8.0]}"),
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
     * Checked class/Error carriers encode their public declared data;
     * function roots have no JSON representation, independently from null
     * substitution for function elements. Keep all published invocation lanes.
     */
    private static void testNonEncodableCarrierCompletionsBothTargets()
            throws Exception {
        System.out.println("-- class, function, and Error completion JSON rules");
        for (String backend : List.of("luajit", "jvm")) {
            assertEncodedCompletion(backend, "class-completion",
                CLASS_COMPLETION_SOURCE, "@src/main.deal/C", "{\"x\":7.0}");
            assertEncodedCompletion(backend, "class-array-completion",
                CLASS_ARRAY_COMPLETION_SOURCE, "[@src/main.deal/C]", "[{\"x\":7.0}]");
            assertEncodedCompletion(backend, "nullable-class-completion",
                NULLABLE_CLASS_COMPLETION_SOURCE, "?@src/main.deal/C", "{\"x\":7.0}");
            assertRepresentationFailure(backend, "function-completion",
                FUNCTION_COMPLETION_SOURCE, "()->number",
                "completion has no JSON representation");
            assertEncodedCompletion(backend, "function-array-completion",
                FUNCTION_ARRAY_COMPLETION_SOURCE, "[()->number]", "[null]");
            assertRepresentationFailure(backend, "stdlib-function-completion",
                STDLIB_FUNCTION_COMPLETION_SOURCE, "(unknown)->!string",
                "completion has no JSON representation");
            assertEncodedCompletion(backend, "error-field-completion",
                ERROR_FIELD_COMPLETION_SOURCE, "record",
                "{\"e\":{\"code\":\"E_CANON\",\"message\":\"roundtrip\"}}");
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
                assert(type(handle) == "table" and handle[__dealCarrierKeys.kind] == "async",
                  "the published parameterized export produces an operation: "
                    .. tostring(handle))
                rt.async_step(handle)
                local checked = rt.check_canonical_type("number", handle.__result)
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
                Object result = invoke(array, "run", "[number]");
                checkEq(valueResult(backend, "[number]", "[7.0,8.0]"), result,
                    backend + ": an number[] completion encodes as the checked JSON"
                        + " array: " + result);
                assertNoInternalKeys(backend, valueJson(result));
            } finally {
                deleteRecursively(array.root());
            }

            Project table = compile("table-completion-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend), TABLE_COMPLETION_SOURCE,
                null, null);
            try {
                Object result = invoke(table, "run", "record");
                checkEq(valueResult(backend, "record", "{\"a\":7.0}"), result,
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
                Object result = invoke(nested, "run", "record");
                String json = valueJson(result);
                check(result instanceof LuaJitAsyncExportInvoker.Result.Value
                        || result instanceof JvmAsyncExportInvoker.Result.Value,
                    backend + ": the nested completion is a value: " + result);
                check(json != null && json.contains("\"xs\":[7.0,8.0]"),
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
     * Nonfinite completions pass number admission and encode null, rather
     * than becoming fabricated DEAL or representation errors.
     */
    private static void testRepresentationFailureBothTargets() throws Exception {
        System.out.println("-- nonfinite completions encode null on both targets");
        for (String backend : List.of("luajit", "jvm")) {
            assertEncodedCompletion(backend, "infinite-number",
                INFINITE_NUMBER_SOURCE, "number", "null");
        }
    }

    /** Every encodable completion uses the production JSON value rules. */
    private static void assertEncodedCompletion(String backend, String name,
            String source, String descriptor, String expectedJson) throws Exception {
        Project project = compile(name + "-" + backend, backend,
            String.format(PLAIN_MANIFEST, backend), source, null, null);
        try {
            Object result = invoke(project, "run", descriptor);
            check(valueJson(result) != null,
                backend + ": " + name + " publishes a value envelope: " + result);
            if (valueJson(result) != null) {
                checkEq(jsonStructure(expectedJson), jsonStructure(valueJson(result)),
                    backend + ": " + name + " encodes its checked completion: " + result);
            }
            assertNoInternalKeys(backend, valueJson(result));
        } finally {
            deleteRecursively(project.root());
        }
    }

    private static Object jsonStructure(String text) {
        var parsed = deal.semantic.SharedStdlibSemantics.jsonParse(
            new deal.semantic.ir.SourceOrigin("completion.deal",
                deal.semantic.ir.SourceSpan.synthetic("completion.deal"),
                deal.semantic.ir.SourceOriginKind.SYNTHETIC,
                new deal.semantic.ir.AnchorId(1), null),
            new deal.semantic.ir.UnicodeScalars.Valid("{\"value\":" + text + "}"));
        if (!(parsed instanceof deal.semantic.SharedStdlibSemantics.Outcome.Success<
                deal.semantic.SharedStdlibSemantics.Value> success)) {
            throw new AssertionError("Invalid JSON completion: " + text);
        }
        return jsonStructure(success.value());
    }

    private static Object jsonStructure(deal.semantic.SharedStdlibSemantics.Value value) {
        return switch (value) {
            case deal.semantic.SharedStdlibSemantics.Value.Number n -> Double.doubleToLongBits(n.value());
            case deal.semantic.SharedStdlibSemantics.Value.Table t -> {
                java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
                for (String key : t.table().keys()) {
                    var slot = t.table().get(key);
                    if (!(slot instanceof deal.semantic.ir.SemanticTable.Lookup.Present<
                            deal.semantic.SharedStdlibSemantics.Value> present)) {
                        throw new AssertionError("Parsed JSON key is absent");
                    }
                    fields.put(key, jsonStructure(present.value()));
                }
                yield fields;
            }
            case deal.semantic.SharedStdlibSemantics.Value.Array a ->
                a.elements().elements().stream().map(AsyncExportInvokerProductionTest::jsonStructure).toList();
            default -> value;
        };
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
                && Double.parseDouble(number.token()) == 7.0,
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
                Object result = invoke(project, "run", "number");
                checkEq(valueResult(backend, "number", "7.0"), result,
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
                Object result = invoke(project, "run", "number");
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
                Object result = invoke(operation, "run", "number");
                checkEq("E8002", errorCode(result),
                    backend + ": the operation error keeps its code: " + result);
                checkEq("array index out of bounds", errorMessage(result),
                    backend + ": the operation error keeps its message: " + result);
                assertLocation(backend, result, operation.root()
                    .resolve("src/main.deal"), 4, 19);
            } finally {
                deleteRecursively(operation.root());
            }
            Project main = compile("main-error-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend), MAIN_ERROR_SOURCE, null, null);
            try {
                Object result = invoke(main, "run", "number");
                checkEq("E8002", errorCode(result),
                    backend + ": the main() error keeps its code: " + result);
                checkEq("array index out of bounds", errorMessage(result),
                    backend + ": the main() error keeps its message: " + result);
                assertLocation(backend, result, main.root()
                    .resolve("src/main.deal"), 8, 19);
            } finally {
                deleteRecursively(main.root());
            }
            Project caused = compile("caused-error-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend), """
                    export function main(): null {return null;}
                    export async function run(): Promise<number> {
                      try {let t: Record<string, unknown> = {value: "bad"}; let x: number = t.value as number; return x;}
                      catch(e) {throw {code: "OUTER", message: "outer"};}
                    }
                    """, null, null);
            try {
                Object result = invoke(caused, "run", "number");
                checkEq("OUTER", errorCode(result), backend + ": outer failure survives");
                AsyncExportEnvelope.ErrorDetail detail = errorDetail(result);
                AsyncExportEnvelope.ErrorDetail cause = detail == null ? null : detail.cause();
                check(cause != null, backend + ": actual public invocation retains cause");
                if (cause != null) {
                    checkEq("E8001", cause.code(), backend + ": cause code");
                    checkEq("expected number", cause.message(), backend + ": cause message");
                    checkEq("number", cause.expected(), backend + ": cause expected");
                    checkEq("string", cause.actual(), backend + ": cause actual");
                    checkEq(Integer.valueOf(3), cause.line(), backend + ": cause origin");
                    check(cause.frames() != null && cause.frames().contains("run@F"),
                        backend + ": cause retains its own frames");
                    check(cause.cause() == null, backend + ": leaf has no invented cause");
                }
            } finally {
                deleteRecursively(caused.root());
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
                Object result = invoke(project, "run", "number");
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
                + "\"message\":\"expected number\"}",
            JvmRuntime.asyncExportDealErrorEnvelope("E8001", "expected number", "-"),
            "the emitter's absent-origin marker omits every location field");
        checkEq(prefix + "{\"status\":\"deal-error\",\"code\":\"E8001\","
                + "\"message\":\"expected number\"}",
            JvmRuntime.asyncExportDealErrorEnvelope("E8001", "expected number", null),
            "an absent origin omits every location field");

        // The complete ErrorDetail projection: the expected/actual pair and
        // the ordered frame text travel as their own optional fields, and
        // the shared codec reads them back.
        String complete = JvmRuntime.asyncExportDealErrorEnvelope("E8001",
            "expected number", "/abs/src/helper.deal:3:16", "number", "string",
            "helper#fail@F20,main#run@F107");
        checkEq(prefix + "{\"status\":\"deal-error\",\"code\":\"E8001\","
                + "\"message\":\"expected number\","
                + "\"file\":\"/abs/src/helper.deal\",\"line\":3,"
                + "\"column\":16,\"expected\":\"number\","
                + "\"actual\":\"string\","
                + "\"frames\":\"helper#fail@F20,main#run@F107\"}",
            complete,
            "the complete deal-error envelope carries location plus"
                + " expected/actual/frames");
        AsyncExportEnvelope.Envelope detail = AsyncExportEnvelope.decodeEnvelope(
            complete.substring(prefix.length()));
        checkEq("number", detail.expected,
            "the shared codec reads the projected expected");
        checkEq("string", detail.actual,
            "the shared codec reads the projected actual");
        checkEq("helper#fail@F20,main#run@F107", detail.frames,
            "the shared codec reads the projected frames");
        JvmRuntime.DealError leaf = new JvmRuntime.DealError("E8001", "leaf",
            "src/helper.deal:3:16", "number", "string", "fail@F20", null);
        JvmRuntime.DealError outer = new JvmRuntime.DealError("E8003", "outer",
            "src/main.deal:4:1", null, null, "run@F107", leaf);
        AsyncExportEnvelope.Envelope caused = AsyncExportEnvelope.decodeEnvelope(
            JvmRuntime.asyncExportDealErrorEnvelope(outer).substring(prefix.length()));
        checkEq(new AsyncExportEnvelope.ErrorDetail("E8001", "leaf",
            "src/helper.deal", 3, 16, "number", "string", "fail@F20", null),
            caused.cause, "recursive cause survives the producer and closed codec");
        check(detail.cause == null, "direct errors do not invent a cause");
        // An absent optional field is genuinely absent on the wire and null
        // in the projection (the compatibility five-argument shape).
        AsyncExportEnvelope.Envelope absent = AsyncExportEnvelope.decodeEnvelope(
            JvmRuntime.asyncExportDealErrorEnvelope("E8001", "expected number",
                "-").substring(prefix.length()));
        check(absent.expected == null && absent.actual == null
                && absent.frames == null,
            "an absent optional detail stays absent in the projection");

        // Wrong-typed and unknown fields on the closed deal-error arm fail
        // closed (never a partial acceptance).
        for (String field : List.of(
                "\"frames\":3",
                "\"expected\":7",
                "\"cause\":{}",
                "\"cause\":null",
                "\"cause\":{\"code\":\"E8001\",\"message\":\"m\",\"unknown\":1.0}")) {
            checkRefusedEnvelope("{\"status\":\"deal-error\",\"code\":\"E8001\","
                + "\"message\":\"m\"," + field + "}", field);
        }

        // The retained five-argument public constructors stay constructible
        // from an external package (this suite's package differs from the
        // invokers'), with the absent optional metadata null.
        for (Object compat : List.of(
                new LuaJitAsyncExportInvoker.Result.DealError(
                    "E8001", "expected number", "/abs/src/helper.deal", 3, 16),
                new JvmAsyncExportInvoker.Result.DealError(
                    "E8001", "expected number", "/abs/src/helper.deal", 3, 16))) {
            checkEq("E8001", errorCode(compat),
                compat.getClass() + ": the five-argument DealError constructor stays public");
            AsyncExportEnvelope.ErrorDetail metadata = errorDetail(compat);
            check(metadata.expected() == null && metadata.actual() == null
                    && metadata.frames() == null,
                compat.getClass() + ": the compatibility constructor leaves optional detail null");
        }

        // A non-DEAL status keeps its existing projection and never maps to
        // a DealError.
        AsyncExportEnvelope.Envelope hostFailure =
            AsyncExportEnvelope.decodeEnvelope((prefix
                + "{\"status\":\"host-failure\",\"reason\":\"missing main\"}")
                    .substring(prefix.length()));
        checkEq(AsyncExportEnvelope.STATUS_HOST_FAILURE, hostFailure.status,
            "the host-failure status stays distinct");
        check(hostFailure.expected == null && hostFailure.frames == null,
            "a host-failure envelope carries no error detail");
    }

    /** A malformed deal-error envelope line is refused by the codec. */
    private static void checkRefusedEnvelope(String json, String what) {
        boolean refused = false;
        try {
            AsyncExportEnvelope.decodeEnvelope(json);
        } catch (LuaJitAsyncExportInvoker.EnvelopeJson.ParseException expected) {
            refused = true;
        }
        check(refused, "the codec refuses " + what);
    }

    /**
     * ISSUE-0756 subject {@code async-extended-argument-order}: a real
     * published async export whose own trace oracle proves the awaited
     * ignored argument evaluates before the source async call — the
     * completion is a value only when the order is A, B, F. The subject
     * keeps its own output (the package's exact stdout expectation), and
     * the drive pins the checked null completion through the production
     * invoker alongside the production host's exact separate streams and
     * status, so the order oracle, the publication and the output are
     * one regression.
     */
    private static void testAsyncArityExtensionArgumentOrderBothTargets()
            throws Exception {
        System.out.println("-- the awaited arity-extension argument order");
        for (String backend : List.of("luajit", "jvm")) {
            Project project = compile("arity-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend), ARITY_EXTENSION_SOURCE,
                null, null);
            try {
                // The checked null completion through the production
                // invoker: the matcher-validated Value, never an error,
                // a host failure or an unvalidated completion.
                Object result = invoke(project, "run", "null");
                check(result instanceof LuaJitAsyncExportInvoker.Result.Value
                        || result instanceof JvmAsyncExportInvoker.Result.Value,
                    backend + ": the arity-extension export completes a value,"
                        + " never an error or host failure: " + result);
                String json = valueJson(result);
                check(json == null || "null".equals(json),
                    backend + ": the null completion stays the language null"
                        + " (the trace oracle passed): " + json);

                // The production host transcript: the published artifact
                // executed by the same host surface the invoker drives,
                // with the subject's own output as the exact stdout pin
                // and the value envelope as the host-side completion.
                ProcessOutcome transcript =
                    productionHostTranscript(project, "run", "null");
                checkEq(0, transcript.exitCode(), backend + ": the production"
                    + " host exits 0: " + transcript.output());
                checkEq(ARITY_EXTENSION_MARKER + ARITY_EXTENSION_ENVELOPE,
                    transcript.stdout(), backend + ": the production host"
                        + " stdout is the subject's own marker followed by"
                        + " the checked-null value envelope");
                checkEq("", transcript.stderr(), backend + ": the production"
                    + " host stderr stays empty: " + transcript.output());
            } finally {
                deleteRecursively(project.root());
            }
        }
    }

    /**
     * The production host transcript of one async-export invocation: the
     * published artifact executed by the host surface the invoker drives
     * — the committed LuaJIT driver under the deferred-main marker, or
     * the emitted {@code <Entry>$$AsyncExportHost} launcher — with the
     * invoker's own argv contract, so the subject's own output and the
     * host's value envelope are observable byte-exact (the invoker's
     * success path consumes the same child's streams internally). The
     * host child runs through the landed shared bounded-process helper
     * with the invoker's working directory and environment override, so
     * a noisy host can never outgrow the shared per-stream bound.
     */
    private static ProcessOutcome productionHostTranscript(Project project,
            String exportName, String returnDescriptor) throws Exception {
        if ("luajit".equals(project.backend())) {
            Path driver = Path.of(LuaJitAsyncExportInvoker.DRIVER_RESOURCE)
                .toAbsolutePath().normalize();
            check(Files.isRegularFile(driver), "the committed LuaJIT"
                + " async-export driver resource exists: " + driver);
            if (!Files.isRegularFile(driver)) {
                throw new IllegalStateException("the committed LuaJIT"
                    + " async-export driver resource is missing: " + driver);
            }
            Path artifactRoot = project.root().resolve("out");
            return captureHostTranscript("luajit:" + exportName,
                List.of("luajit",
                    driver.toString(),
                    project.entryArtifact().toAbsolutePath().toString(),
                    artifactRoot.toAbsolutePath().toString(),
                    exportName, returnDescriptor),
                artifactRoot, Map.of("DEAL_DEFER_MAIN", "1"));
        }
        return captureHostTranscript("jvm:" + exportName,
            List.of("java", "-cp",
                project.classes() + File.pathSeparator + absoluteClasspath(),
                "Main$" + JvmAsyncExportInvoker.HOST_CLASS_SUFFIX,
                JvmAsyncExportInvoker.HOST_ARG, exportName, returnDescriptor),
            project.classes(), Map.of());
    }

    /**
     * The named refusal token of an over-limit production host capture.
     */
    private static final String HOST_CAPTURE_TRUNCATED =
        "ASYNC_HOST_CAPTURE_TRUNCATED";

    /**
     * One production host transcript through the landed shared
     * bounded-process helper ({@link BoundedProcess}): the working
     * directory and the environment overrides are preserved, both streams
     * stay separate and complete below the shared per-stream cap, and a
     * truncated or over-limit observation is refused by name before any
     * transcript comparison — the helper's retained prefix and truncation
     * marker stay diagnostics, never a host result.
     */
    private static ProcessOutcome captureHostTranscript(String label,
            List<String> command, Path directory,
            Map<String, String> environment) throws Exception {
        BoundedProcess.BoundedResult result =
            BoundedProcess.runCaptured(command, directory, environment);
        if (result.stdoutTruncated() || result.stderrTruncated()) {
            throw new AssertionError(HOST_CAPTURE_TRUNCATED + ": the"
                + " production host observation of " + label + " exceeded"
                + " the shared per-stream bound (stdoutTruncated="
                + result.stdoutTruncated() + ", stderrTruncated="
                + result.stderrTruncated() + "); the retained prefix is"
                + " diagnostics only, never a transcript");
        }
        return new ProcessOutcome(result.exitCode(), result.stdout(),
            result.stderr());
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
                Object first = invoke(project, "run", "number");
                Object second = invoke(project, "run", "number");
                checkEq(valueResult(backend, "number", "110.0"), first,
                    backend + ": one host-module load and one main touch before"
                        + " the export: " + first);
                checkEq(valueResult(backend, "number", "110.0"), second,
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

    private static AsyncExportEnvelope.ErrorDetail errorDetail(Object result) {
        if (result instanceof LuaJitAsyncExportInvoker.Result.DealError error) {
            return new AsyncExportEnvelope.ErrorDetail(error.code(), error.message(),
                error.file(), error.line(), error.column(), error.expected(),
                error.actual(), error.frames(), error.cause());
        }
        if (result instanceof JvmAsyncExportInvoker.Result.DealError error) {
            return new AsyncExportEnvelope.ErrorDetail(error.code(), error.message(),
                error.file(), error.line(), error.column(), error.expected(),
                error.actual(), error.frames(), error.cause());
        }
        return null;
    }

    // =========================================================================
    // The retained corner-case public errors (ISSUE-0815 subjects 5-8)
    // =========================================================================

    /**
     * The corner-case companion: the checked assertion on line 3
     * fails with the typed-kind arm at its own check anchor
     * (column 16 — never the later annotation check at column 10).
     */
    private static final String CORNER_HELPER = """
        export %sfunction fail(): %s {
          let t: Record<string, unknown> = {value: "bad"};
          let x: number = t.value as number;
          return x;
        }
        """;

    private static final String CORNER_MAIN = """
        import * as c from "std/console";
        import * as helper from "./helper";
        export function main(): null {return null;}
        export %sfunction run(): %s {%s}
        """;

    private static Project compileCorner(String name, String backend,
            boolean async, boolean rethrow) throws Exception {
        String body = "return " + (async ? "await " : "") + "helper.fail();";
        if (rethrow) {
            body = "try {" + body + "} catch(e) {throw e as Error;}";
        }
        String modifier = async ? "async " : "";
        Path root = Files.createTempDirectory("async-export-" + name + "-");
        writeFile(root, "src/helper.deal", CORNER_HELPER.formatted(modifier, async ? "Promise<number>" : "number"));
        return compileAt(root, name, backend, PLAIN_MANIFEST.formatted(backend),
            CORNER_MAIN.formatted(modifier, async ? "Promise<number>" : "number", body), null, null);
    }

    /**
     * The published two-module corner-case project of one async subject,
     * invoked through the production async invoker (exportName
     * {@code run}, returnDescriptor {@code int}): the result is the
     * complete ErrorDetail — code E8001, the typed-kind message,
     * expected int, actual string, the companion origin src/helper.deal
     * line 3 column 16, and the active frames naming the original and
     * generated fail/run functions — with the program streams empty and
     * the host marker decoded through the shared wire codec.
     */
    private static void testCornerCaseAwaitErrorsBothTargets() throws Exception {
        System.out.println("-- the retained corner-case public-await errors"
            + " (direct and rethrow) --");
        for (String backend : List.of("luajit", "jvm")) {
            for (boolean rethrow : new boolean[] {false, true}) {
                String name = "corner-await-" + (rethrow ? "rethrow" : "direct")
                    + "-" + backend;
                Project project = compileCorner(name, backend, true, rethrow);
                try {
                    Object result = invoke(project, "run", "number");
                    Path companion = project.root().resolve("src/helper.deal");
                    checkCornerError(name, errorDetail(result), companion);
                    AsyncExportEnvelope.Envelope envelope =
                        quietHostEnvelope(name, project);
                    if (envelope != null) {
                        checkEq(AsyncExportEnvelope.STATUS_DEAL_ERROR, envelope.status,
                            name + ": the wire status is deal-error");
                        checkCornerError(name + " wire", new AsyncExportEnvelope.ErrorDetail(
                            envelope.code, envelope.message, envelope.file, envelope.line,
                            envelope.column, envelope.expected, envelope.actual,
                            envelope.frames, envelope.cause), companion);
                    }
                } finally {
                    deleteRecursively(project.root());
                }
            }
        }
    }

    /**
     * The published two-module corner-case project of one sync subject,
     * invoked through the actual public exported-function wrapper: the
     * LuaJIT chunk's returned entry surface ({@code run.f}) and the JVM
     * emitted export surface ({@code Main.exportSurface("main")}). The
     * observed error record is the complete ErrorDetail, and the host
     * probe writes nothing to stdout or stderr, so the program streams
     * stay empty.
     */
    private static void testCornerCaseSyncErrorsBothTargets() throws Exception {
        System.out.println("-- the retained corner-case public sync errors"
            + " (direct and rethrow) through the exported wrapper --");
        Path drivers = Files.createTempDirectory("corner-sync-hosts-");
        try {
            writeFile(drivers, "corner_host.lua", CORNER_LUA_SYNC_HOST);
            writeFile(drivers, "CornerCaseHost.java", CORNER_JVM_SYNC_HOST);
            for (String backend : List.of("luajit", "jvm")) {
                for (boolean rethrow : new boolean[] {false, true}) {
                    String name = "corner-sync-" + (rethrow ? "rethrow" : "direct")
                        + "-" + backend;
                    Project project = compileCorner(name, backend, false, rethrow);
                    try {
                        if ("jvm".equals(backend) && !rethrow) {
                            StringWriter messages = new StringWriter();
                            boolean compiled = javacPreparation.compile(
                                List.of(drivers.resolve("CornerCaseHost.java")),
                                drivers, project.classes(), messages);
                            check(compiled, name + ": the JVM exported-wrapper host compiles: "
                                + messages);
                            if (!compiled) {
                                throw new IllegalStateException(name + ": " + messages);
                            }
                        }
                        Path directory = "luajit".equals(backend)
                            ? project.root().resolve("out") : project.classes();
                        List<String> command = "luajit".equals(backend)
                            ? List.of("luajit", drivers.resolve("corner_host.lua").toString())
                            : List.of("java", "-cp", project.classes() + File.pathSeparator
                                + drivers + File.pathSeparator + absoluteClasspath(),
                                "CornerCaseHost");
                        ProcessOutcome observation = runProcess(command, directory);
                        checkEq(0, observation.exitCode(), name
                            + ": the exported-wrapper host exits 0: " + observation.output());
                        checkEq("", observation.stdout(), name
                            + ": the program stdout stays empty: " + observation.output());
                        checkEq("", observation.stderr(), name
                            + ": the program stderr stays empty: " + observation.output());
                        Path observed = directory.resolve("corner_error.txt");
                        check(Files.isRegularFile(observed), name
                            + ": the exported-wrapper host records the observed error");
                        checkCornerObservation(name, readObservation(observed), backend);
                    } finally {
                        deleteRecursively(project.root());
                    }
                }
            }
        } finally {
            deleteRecursively(drivers);
        }
    }

    /**
     * The LuaJIT sync host: the published chunk's returned entry surface
     * is the public exported-function wrapper (the retained-caller ABI's
     * {@code run.f} callable). The observed error carrier fields are
     * written to a private file, so stdout/stderr stay the program's own
     * streams.
     */
    private static final String CORNER_LUA_SYNC_HOST = """
        local surfaces = dofile("main.lua")
        local run = surfaces.run
        local fields = {}
        local ok, err = pcall(run.f, "src/main.deal", 4, 41)
        if ok then
          fields = {"value=" .. tostring(err)}
        elseif type(err) == "table" then
          fields = {
            "code=" .. tostring(err.code),
            "message=" .. tostring(err.m or err.message),
            "origin=" .. tostring(err.o or ""),
            "expected=" .. tostring(err.e or err.expected or ""),
            "actual=" .. tostring(err.a or err.actual or ""),
            "frames=" .. tostring(err.f or err.frames or ""),
            "cause=" .. tostring(err.cause),
          }
        else
          fields = {"thrown=" .. tostring(err)}
        end
        local f = assert(io.open("corner_error.txt", "w"))
        f:write(table.concat(fields, "\\n") .. "\\n")
        f:close()
        """;

    /** The JVM sync host: the emitted {@code exportSurface} ABI. */
    private static final String CORNER_JVM_SYNC_HOST = """
        import deal.codegen.jvm.JvmRuntime;
        import java.nio.file.Files;
        import java.nio.file.Path;

        public class CornerCaseHost {
          public static void main(String[] args) throws Exception {
            Main.main(new String[0]);
            JvmRuntime.Table surface = Main.exportSurface("main");
            Object value = surface.read("run");
            StringBuilder sb = new StringBuilder();
            if (!(value instanceof JvmRuntime.FunctionValue fn)) {
              sb.append("value=").append(value).append('\\n');
            } else {
              try {
                Object r = fn.fn.invoke(new Object[] {"src/main.deal", 4, 41});
                sb.append("value=").append(r).append('\\n');
              } catch (Throwable t) {
                if (t instanceof JvmRuntime.DealError e) {
                  sb.append("code=").append(e.code).append('\\n');
                  sb.append("message=").append(e.msg).append('\\n');
                  sb.append("origin=").append(e.origin).append('\\n');
                  sb.append("expected=").append(e.expected).append('\\n');
                  sb.append("actual=").append(e.actual).append('\\n');
                  sb.append("frames=").append(e.frames).append('\\n');
                  sb.append("cause=").append(e.cause).append('\\n');
                } else {
                  sb.append("thrown=").append(t).append('\\n');
                }
              }
            }
            Files.writeString(Path.of("corner_error.txt"), sb.toString());
          }
        }
        """;

    /**
     * Asserts the complete corner-case failure record through the public
     * async invoker result on both targets and the exported-wrapper
     * observation file: the exact ErrorDetail and the original
     * check-anchor origin.
     */
    private static void checkCornerError(String name,
            AsyncExportEnvelope.ErrorDetail detail, Path companionSource) {
        check(detail != null, name + ": the language failure reaches the public result");
        if (detail == null) {
            return;
        }
        checkEq(new AsyncExportEnvelope.ErrorDetail("E8001", "expected number",
            companionSource.toAbsolutePath().toString(), 3, 19, "number", "string",
            detail.frames(), null), detail, name + ": the complete corner error metadata");
        checkCornerFrames(name, detail.frames());
    }

    private static void checkCornerFrames(String name, String frames) {
        check(frames != null && frames.indexOf("fail@F") >= 0
                && frames.indexOf("fail@F") < frames.indexOf("run@F"),
            name + ": frames name the original and generated fail/run functions: " + frames);
    }

    /**
     * Asserts the exported-wrapper observation record of one sync
     * corner-case subject: the complete ErrorDetail through the public
     * wrapper's own carrier, with the original check-anchor origin. The
     * LuaJIT carrier spells the origin as one {@code file:line:column}
     * text; the JVM carrier projects a Java exception.
     */
    private static void checkCornerObservation(String name,
            java.util.Map<String, String> observed, String backend) {
        checkEq("E8001", observed.get("code"), name + ": the observed code");
        checkEq("expected number", observed.get("message"), name
            + ": the observed typed-kind message");
        String origin = observed.getOrDefault("origin", "").replace('\\', '/');
        check(origin.endsWith("/src/helper.deal:3:19"),
            name + ": the observed origin is the companion line 3 contextual read anchor: "
                + origin);
        checkEq("number", observed.get("expected"), name
            + ": the observed expected type");
        checkEq("string", observed.get("actual"), name
            + ": the observed actual type");
        checkEq("luajit".equals(backend) ? "nil" : "null", observed.get("cause"),
            name + ": direct/rethrow error has no invented cause");
        checkCornerFrames(name, observed.get("frames"));
    }

    /**
     * The await-capable array-write subject (ISSUE-0815 subject 1): the
     * async export performs {@code receiver()[index()] = await value()}
     * inside a try/catch, so the RIV trace, the E8002 bounds rejection
     * after all three operand evaluations, the unchanged alias state and
     * the successful append are observed through the published artifact.
     * The export completes the int 1 exactly when every property held; a
     * reordered, repeated or suppressed operand, a committed invalid write
     * or a lost append completes 0.
     */
    private static final String AWAIT_ARRAY_WRITE_SUBJECT = """
        export function main(): null {return null;}
        export async function run(): Promise<number> {
          let xs: number[] = [7];
          let alias: number[] = xs;
          let trace: string = "";
          async function value(): Promise<number> {trace = trace + "V"; return 9;}
          function index(): number {trace = trace + "I"; return 2;}
          function receiver(): number[] {trace = trace + "R"; return xs;}
          try {receiver()[index()] = await value(); trace = trace + "X";}
          catch (e) {if ((e as Error).code !== "E8002") {return 0;} trace = trace + "C";}
          if (trace !== "RIVC" || alias.length !== 1 || alias[0] !== 7) {return 0;}
          alias[alias.length] = 8;
          if (xs.length !== 2 || xs[1] !== 8) {return 0;}
          let zero: Record<string, unknown> = {value: -0.0};
          function admitted(x: number): number {
            if (1.0 / x < 0.0) {return 1;}
            return 0;
          }
          return admitted(zero.value as number);
        }
        """;

    private static void testAwaitCapableArrayWriteBothTargets()
            throws Exception {
        System.out.println("-- the await-capable array write keeps the"
            + " receiver/index/RHS order before the E8002 bounds gate --");
        for (String backend : List.of("luajit", "jvm")) {
            String name = "await-array-write-" + backend;
            Project project = compile(name, backend,
                String.format(PLAIN_MANIFEST, backend),
                AWAIT_ARRAY_WRITE_SUBJECT, null, null);
            try {
                Object result = invoke(project, "run", "number");
                checkEq(valueResult(backend, "number", "1.0"), result,
                    name + ": the public completion is 1 (the RIV/bounds/"
                        + "no-write/append oracle passed)");

                AsyncExportEnvelope.Envelope envelope = quietHostEnvelope(name, project);
                if (envelope != null) {
                    checkEq(AsyncExportEnvelope.STATUS_VALUE, envelope.status,
                        name + ": the wire status is value");
                    checkEq("number", envelope.descriptor, name + ": the wire descriptor is number");
                    checkEq("1.0", envelope.value, name + ": the wire value is 1");
                }
            } finally {
                deleteRecursively(project.root());
            }
        }
    }

    private static AsyncExportEnvelope.Envelope quietHostEnvelope(String name,
            Project project) throws Exception {
        ProcessOutcome transcript = productionHostTranscript(project, "run", "number");
        checkEq(0, transcript.exitCode(), name + ": the production host exits 0: "
            + transcript.output());
        checkEq("", transcript.stderr(), name + ": the program stderr stays empty: "
            + transcript.output());
        String stdout = transcript.stdout();
        String marker = LuaJitAsyncExportInvoker.RESULT_PREFIX;
        boolean singleEnvelope = stdout.startsWith(marker) && stdout.endsWith("\n")
            && stdout.indexOf('\n') == stdout.length() - 1;
        check(singleEnvelope, name + ": stdout is exactly the host control envelope: <"
            + stdout + ">");
        return singleEnvelope ? AsyncExportEnvelope.decodeEnvelope(
            stdout.substring(marker.length(), stdout.length() - 1)) : null;
    }

    /** One observed exported-wrapper error record (k=v lines). */
    private static java.util.Map<String, String> readObservation(Path file)
            throws Exception {
        java.util.Map<String, String> fields = new java.util.HashMap<>();
        for (String line : Files.readString(file, StandardCharsets.UTF_8)
                .split("\n")) {
            int split = line.indexOf('=');
            if (split > 0) {
                fields.put(line.substring(0, split), line.substring(split + 1));
            }
        }
        return fields;
    }

    // =========================================================================
    // The compiled fixture project
    // =========================================================================

    private record Project(Path root, String backend, Path entryArtifact,
                           Path classes) {
    }

    static final class JavacPreparation implements AutoCloseable {
        private final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        private final StandardJavaFileManager fileManager;
        private final String classpath = absoluteClasspath();

        JavacPreparation() {
            if (compiler == null) {
                throw new IllegalStateException("no system Java compiler available");
            }
            fileManager = compiler.getStandardFileManager(null, null,
                StandardCharsets.UTF_8);
        }

        boolean compile(List<Path> sources, Path classes, StringWriter messages) {
            return compile(sources, classes, classes, messages);
        }

        boolean compile(List<Path> sources, Path classes, Path dependencies,
                StringWriter messages) {
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            Boolean compiled = compiler.getTask(messages, fileManager, diagnostics,
                List.of("--release", "25", "-proc:none", "-encoding", "UTF-8",
                    "-sourcepath", "", "-cp", dependencies + File.pathSeparator + classpath,
                    "-d", classes.toString()), null,
                fileManager.getJavaFileObjectsFromPaths(sources)).call();
            for (var diagnostic : diagnostics.getDiagnostics()) {
                messages.append(diagnostic.toString()).append('\n');
            }
            return Boolean.TRUE.equals(compiled);
        }

        @Override
        public void close() throws java.io.IOException {
            fileManager.close();
        }
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
        List<Path> sources = new ArrayList<>(List.of(root.resolve("out/Main.java")));
        if (hostSource != null) {
            sources.add(root.resolve("host/HostProbe.java"));
        }
        StringWriter messages = new StringWriter();
        boolean compiled = javacPreparation.compile(sources, classes, messages);
        check(compiled, name + ": the emitted JVM artifact compiles with javac: "
            + messages);
        if (!compiled) {
            throw new IllegalStateException(name + ": the emitted JVM artifact did not"
                + " compile: " + messages);
        }
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
            throw new IllegalStateException("the child did not exit: "
                + builder.command());
        }
        ProcessOutcome outcome = new ProcessOutcome(process.exitValue(),
            Files.readString(stdout, StandardCharsets.UTF_8),
            Files.readString(stderr, StandardCharsets.UTF_8));
        Files.deleteIfExists(stdout);
        Files.deleteIfExists(stderr);
        return outcome;
    }

    /**
     * The boundary controls of the production host capture
     * ({@link #captureHostTranscript}): a real child flooding one stream
     * at cap-minus-one and at the exact shared cap is a complete,
     * untruncated observation with separate streams and its own exit
     * status; at cap-plus-one the shared helper flags exactly the
     * overflowing stream, retains the bounded prefix plus its truncation
     * marker, and the host capture refuses the observation by name in
     * both stream positions before any transcript comparison.
     */
    private static void testProductionHostCaptureBoundary()
            throws Exception {
        System.out.println("-- the production host capture boundary"
            + " controls --");
        Path scratch = Files.createTempDirectory("async-host-capture-");
        try {
            int cap = BoundedProcess.STREAM_CAP_BYTES;
            for (String stream : List.of("stdout", "stderr")) {
                for (int bytes : List.of(cap - 1, cap)) {
                    ProcessOutcome flooded = captureHostTranscript(
                        stream + "-flood-" + bytes, hostFloodCommand(stream,
                            bytes), scratch, Map.of());
                    checkEq(0, flooded.exitCode(), stream + " " + bytes
                        + "-byte flood exits 0 (its own status verbatim)");
                    String floodedStream = "stdout".equals(stream)
                        ? flooded.stdout() : flooded.stderr();
                    String otherStream = "stdout".equals(stream)
                        ? flooded.stderr() : flooded.stdout();
                    checkEq(bytes,
                        floodedStream.getBytes(StandardCharsets.UTF_8).length,
                        stream + " " + bytes + "-byte flood is captured"
                            + " completely inside the shared bound");
                    checkEq("", otherStream, stream + " " + bytes
                        + "-byte flood keeps the other stream empty");
                }
                BoundedProcess.BoundedResult overLimit =
                    BoundedProcess.runCaptured(hostFloodCommand(stream,
                        cap + 1), scratch, Map.of());
                boolean stdoutOverflow = "stdout".equals(stream);
                checkEq(stdoutOverflow, overLimit.stdoutTruncated(),
                    stream + " cap-plus-one flags the overflow on stdout only"
                        + " when stdout is the flooded stream");
                checkEq(!stdoutOverflow, overLimit.stderrTruncated(),
                    stream + " cap-plus-one flags the overflow on stderr only"
                        + " when stderr is the flooded stream");
                String retained = stdoutOverflow
                    ? overLimit.stdout() : overLimit.stderr();
                check(retained.endsWith(BoundedProcess.TRUNCATION_MARKER),
                    stream + " cap-plus-one retains the bounded prefix plus"
                        + " the shared truncation marker");
                checkEq(cap + BoundedProcess.TRUNCATION_MARKER.length(),
                    retained.getBytes(StandardCharsets.UTF_8).length,
                    stream + " cap-plus-one retains exactly the cap prefix"
                        + " plus the marker");
                try {
                    ProcessOutcome refused = captureHostTranscript(
                        stream + "-flood-over-limit",
                        hostFloodCommand(stream, cap + 1), scratch, Map.of());
                    check(false, "the " + stream + " over-limit observation"
                        + " must be refused by name before any comparison,"
                        + " got " + refused.output());
                } catch (AssertionError expected) {
                    check(expected.getMessage().contains(
                            HOST_CAPTURE_TRUNCATED),
                        "the " + stream + " over-limit refusal names the"
                            + " host-capture token: " + expected.getMessage());
                    check(expected.getMessage().contains(stream + "Truncated=true"),
                        "the " + stream + " over-limit refusal names the"
                            + " overflowing stream: " + expected.getMessage());
                }
            }
        } finally {
            deleteRecursively(scratch);
        }
    }

    /** The boundary child's command: {stream, byte count} producers. */
    private static List<String> hostFloodCommand(String stream, int bytes) {
        return List.of("java", "-cp", absoluteClasspath(),
            HostCaptureChild.class.getName(), stream,
            Integer.toString(bytes));
    }

    /** The boundary control's child: writes filler bytes to one stream. */
    static final class HostCaptureChild {
        public static void main(String[] args) throws Exception {
            String stream = args[0];
            int count = Integer.parseInt(args[1]);
            byte[] filler = new byte[count];
            java.util.Arrays.fill(filler, (byte) 'x');
            if ("stdout".equals(stream)) {
                System.out.write(filler);
                System.out.flush();
            } else {
                System.err.write(filler);
                System.err.flush();
            }
        }
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
