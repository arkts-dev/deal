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
 * a nested object completion; a bytes completion classified as a
 * representation failure; a delayed host async completion awaited by the
 * export; a typed completion rejection at the await site (E8001, message
 * unchanged); a DEAL error raised by the async operation and by
 * {@code main()} (code/message/location unchanged on both targets); the
 * shared DEAL-error envelope projection (carried origin with integer
 * line/column, absent origin omitted); and module initialization plus
 * {@code main()} running exactly once per invocation (the host probe
 * counts the host load and the {@code main} touch). The JVM lane compiles
 * the emitted source with {@code javac} and asserts the reserved
 * {@code <Entry>$$AsyncExportHost} launcher class file exists.</p>
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
        testRepresentationFailureBothTargets();
        testDelayedHostCompletionBothTargets();
        testTypedCompletionRejectionBothTargets();
        testUnchangedErrorFieldsBothTargets();
        testInitializationAndMainOnceBothTargets();
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
     * and never a value.
     */
    private static void testRepresentationFailureBothTargets() throws Exception {
        System.out.println("-- a bytes completion is a representation failure on both"
            + " targets");
        for (String backend : List.of("luajit", "jvm")) {
            Project project = compile("bytes-completion-" + backend, backend,
                String.format(PLAIN_MANIFEST, backend), BYTES_COMPLETION_SOURCE,
                null, null);
            try {
                invoke(project, "run", "bytes");
                check(false, backend + ": a bytes completion is a hard failure, never"
                    + " a value");
            } catch (LuaJitAsyncExportInvocationException
                    | JvmAsyncExportInvocationException failure) {
                check(failure.getMessage() != null
                        && failure.getMessage().startsWith(
                            "value-representation failure")
                        && failure.getMessage().contains(
                            "unsupported type for JSON encoding: bytes"),
                    backend + ": the representation failure carries the pinned"
                        + " encoder rejection: " + failure.getMessage());
            } finally {
                deleteRecursively(project.root());
            }
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
        Path root = Files.createTempDirectory("async-export-" + name + "-");
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
