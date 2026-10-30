package deal.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The async runtime group end to end (ISSUE-0913:
 * {@code llvm-native-host-abi-and-async} H2/H5/H6,
 * {@code llvm-native-runtime-value-model} R1/R3/R11,
 * {@code llvm-native-memory-management} M5,
 * {@code llvm-native-codegen-cfg-ssa} C7).
 *
 * <p>Every case runs the production path: {@code deal compile --backend llvm}
 * publishes the project module and the generated host declaration header,
 * {@code deal native build} links the supplied host unit through the generated
 * registry at the pinned flags in the driver shape, and the driver invokes the
 * recorded async export through {@code deal_async_export_invoke} at
 * {@code -O0} and {@code -O2}, normally and under {@code DEAL_GC_STRESS=1}.</p>
 *
 * <p>Covered criteria:</p>
 *
 * <ol>
 *   <li>the emitted async surface: {@code ASYNC_START} over a declared host
 *       async export creates the export's own carrier before the start call,
 *       {@code AWAIT} carries the completion descriptor to the await origin,
 *       {@code deal_async_export_invoke} dispatches the record's async marker
 *       to the async export surface, the module defines exactly six
 *       export-surface functions and no process entry, and the IR is valid and
 *       deterministic;</li>
 *   <li>one delivery routine serves the synchronous-complete (DEAL function),
 *       the service-hook and the any-thread posted completion paths: a posted
 *       number, the {@code undefined} completion of {@code Promise<void>},
 *       {@code null}, posted {@code undefined}, a managed carrier completed on
 *       the mutator thread, a host-authored error completion and a string
 *       posted from storage the poster overwrites and releases before it
 *       returns (copied-input independence);</li>
 *   <li>the poster and the service hook run off and on the mutator thread
 *       respectively, asserted through the published mutator-thread entry;</li>
 *   <li>the await-origin {@code ASYNC_COMPLETION} boundary rejects a wrong-kind
 *       completion and a malformed text completion with the closed
 *       {@code ASYNC_COMPLETION_KIND} arm, and a checked {@code as T} keeps
 *       rejecting an array whose persistent element descriptor differs;</li>
 *   <li>the closed completion statuses: an unknown token, a foreign DEAL-origin
 *       failure token, an off-mutator managed delivery and a duplicate
 *       completion answer the prescribed statuses and change nothing;</li>
 *   <li>the fatal protocol cases each write one deterministic stderr line and
 *       exit 2: a duplicate post, a completion naming no live operation, a
 *       completion for a retired token and a substituted operation
 *       carrier;</li>
 *   <li>a declared async export that answers a carrier of another kind renders
 *       the closed {@code ASYNC_OPERATION_HANDLE} arm with its typed-boundary
 *       actual at the async call origin, and the operation is abandoned;</li>
 *   <li>an awaited Error keeps one identity through the operation and the
 *       await, and a host-authored completion Error is caught as {@code unknown}
 *       and narrowed to the builtin {@code Error};</li>
 *   <li>two host operations pending concurrently with distinct runtime-issued
 *       tokens receive exactly their own completion, delivered out of start
 *       order: the first operation's frame is taken while the second operation
 *       is the one being awaited, and both awaits observe their own value;</li>
 *   <li>a relayed DEAL failure keeps one identity across the crossing that
 *       raises it, the opaque handle that retains it and the waiting-thread
 *       completion of the pending operation: the awaited Error is the same
 *       object with its mutated {@code code} and {@code message} and its
 *       original origin, expected/actual projection and frames; the host-text
 *       arm of the same completion entry materializes the host's own code and
 *       message;</li>
 *   <li>the completion ingress rejects an async-operation carrier and the
 *       no-value marker with the closed kind status and changes nothing, so no
 *       operation becomes data and the following real completion is the one the
 *       await observes;</li>
 *   <li>a queued frame holds copied bytes only: the async copy-in record
 *       declares no carrier, no handle and no DEAL-origin failure token.</li>
 * </ol>
 */
public final class LlvmAsyncRuntimeTest {

    private static final Path REPO = NativeCompiler.repository();
    private static final int[] LEVELS = {0, 2};

    private static final String DEAL_JSON = """
        {"languageVersion": "1.5", "backend": "llvm",
         "moduleRoots": ["src"], "output": "out",
         "externals": {"host/async": {"declaration": "bindings/async.d.deal"}}}
        """;

    /** The declared async surface the fixture host unit implements. */
    private static final String DECLARATION = """
        export function sync(n: number): Promise<number>;
        export function voidOp(): Promise<void>;
        export function nullOp(): Promise<null>;
        export function undefinedOp(): Promise<undefined>;
        export function pending(): Promise<number>;
        export function wrongKind(): Promise<number>;
        export function malformed(): Promise<string>;
        export function textFailure(): Promise<string>;
        export function managed(): Promise<unknown>;
        export function copiedText(): Promise<string>;
        export function statuses(): Promise<number>;
        export function duplicate(): Promise<number>;
        export function unknownToken(): Promise<number>;
        export function substituted(): Promise<number>;
        export function boolOp(): Promise<boolean>;
        export function late(): Promise<number>;
        export function wrongShape(): Promise<number>;
        export function applyAsync(f: () => Promise<number>): number;
        export function armFailure(f: () => number): number;
        export function pairFirst(): Promise<number>;
        export function pairSecond(): Promise<number>;
        export function relay(): Promise<number>;
        export function textCompletion(): Promise<number>;
        export function ingress(): Promise<number>;
        """;

    /** The conforming completions of every closed completion arm. */
    private static final String VALUES_APP = """
        import * as probe from "host/async";
        import * as c from "std/console";

        export async function run(): Promise<null> {
          let synced: number = await probe.sync(1);
          if (synced === 2) { c.log("sync:ok"); } else { c.log("sync:bad"); }
          await probe.voidOp();
          c.log("void:ok");
          let truth: boolean = await probe.boolOp();
          if (truth === true) { c.log("boolean:ok"); } else { c.log("boolean:bad"); }
          let nulled: null = await probe.nullOp();
          if (nulled === null) { c.log("null:ok"); } else { c.log("null:bad"); }
          let undef: undefined = await probe.undefinedOp();
          if (undef === undefined) { c.log("undefined:ok"); } else { c.log("undefined:bad"); }
          let managed: unknown = await probe.managed();
          let numbers: number[] = managed as number[];
          if (numbers.length === 2 && numbers[0] === 4 && numbers[1] === 5) {
            c.log("managed:ok");
          } else {
            c.log("managed:bad");
          }
          let rejected: boolean = false;
          try {
            let wrong: string[] = managed as string[];
            c.log("descriptor:bad");
            if (wrong.length === 0) { c.log("descriptor:bad"); }
          } catch (value) {
            let e: Error = value as Error;
            if (e.code === "E8001") { c.log("descriptor:ok"); }
            rejected = true;
          }
          if (rejected) { c.log("descriptor:rejected"); }
          let text: string = await probe.copiedText();
          if (text === "copied-completion") { c.log("copied:ok"); }
          else { c.log("copied:bad"); }
          let caught: boolean = false;
          try {
            let ignored: string = await probe.textFailure();
            c.log("completion-error:bad");
            if (ignored === "") { c.log("completion-error:bad"); }
          } catch (value) {
            let e: Error = value as Error;
            if (e.code === "E_HOST_ASYNC_TEXT"
                && e.message === "the host async operation failed") {
              c.log("completion-error:ok");
            } else {
              c.log("completion-error:bad");
            }
            caught = true;
          }
          if (caught) { c.log("completion-error:caught"); }
          return null;
        }

        export function main(): null { return null; }
        """;

    /** The awaited DEAL Error identity and the boundary arms of C7. */
    private static final String IDENTITY_APP = """
        import * as c from "std/console";

        async function failWith(failure: Error): Promise<number> {
          throw failure;
        }

        async function nested(n: number): Promise<number> {
          if (n === 1) { return n; }
          return await nested(n - 1);
        }

        export async function run(): Promise<null> {
          let original: Error = {
            code: "E_DEAL_ASYNC",
            message: "the DEAL async body failed"
          };
          let caught: boolean = false;
          try {
            let ignored: number = await failWith(original);
            c.log("identity:bad");
            if (ignored === 0) { c.log("identity:bad"); }
          } catch (value) {
            let e: Error = value as Error;
            if (e === original) { c.log("identity:ok"); } else { c.log("identity:bad"); }
            if (e.code === "E_DEAL_ASYNC") { c.log("code:ok"); } else { c.log("code:bad"); }
            original.code = "E_DEAL_ASYNC_MUTATED";
            if (e.code === "E_DEAL_ASYNC_MUTATED") { c.log("mutation:ok"); }
            else { c.log("mutation:bad"); }
            caught = true;
          }
          if (caught) { c.log("identity:caught"); }
          let value: number = await nested(3);
          if (value === 1) { c.log("nested:ok"); } else { c.log("nested:bad"); }
          return null;
        }

        export function main(): null { return null; }
        """;

    /** The pending-completion app: the awaited export is completed elsewhere. */
    private static final String PENDING_APP = """
        import * as probe from "host/async";
        import * as c from "std/console";

        export async function run(): Promise<null> {
          let value: number = await probe.pending();
          if (value === 21) { c.log("pending:ok"); } else { c.log("pending:bad"); }
          return null;
        }

        export function main(): null { return null; }
        """;

    /** The status probe app: every closed completion status was as prescribed. */
    private static final String STATUS_APP = """
        import * as probe from "host/async";
        import * as c from "std/console";

        export async function run(): Promise<null> {
          let value: number = await probe.statuses();
          if (value === 7) { c.log("statuses:ok"); } else { c.log("statuses:bad"); }
          return null;
        }

        export function main(): null { return null; }
        """;

    private static final String WRONG_KIND_APP = """
        import * as probe from "host/async";

        export async function run(): Promise<null> {
          let value: number = await probe.wrongKind();
          if (value === 0) { return null; }
          return null;
        }

        export function main(): null { return null; }
        """;

    private static final String MALFORMED_APP = """
        import * as probe from "host/async";

        export async function run(): Promise<null> {
          let text: string = await probe.malformed();
          if (text === "") { return null; }
          return null;
        }

        export function main(): null { return null; }
        """;

    /** A declared async export that answers a carrier which is no operation. */
    private static final String WRONG_SHAPE_APP = """
        import * as probe from "host/async";

        export async function run(): Promise<null> {
          let value: number = await probe.wrongShape();
          if (value === 0) { return null; }
          return null;
        }

        export function main(): null { return null; }
        """;

    /** The retired-token case: a second completion for a consumed operation. */
    private static final String LATE_APP = """
        import * as probe from "host/async";
        import * as c from "std/console";

        export async function run(): Promise<null> {
          let first: number = await probe.late();
          if (first === 11) { c.log("late:ok"); } else { c.log("late:bad"); }
          let second: number = await probe.pending();
          if (second === 21) { c.log("pending:ok"); } else { c.log("pending:bad"); }
          return null;
        }

        export function main(): null { return null; }
        """;

    private static final String PROTOCOL_APP = """
        import * as probe from "host/async";

        export async function run(): Promise<null> {
          let value: number = await %s();
          if (value === 0) { return null; }
          return null;
        }

        export function main(): null { return null; }
        """;

    /**
     * Two host operations pending at once (H5 Verification 6): the host applies
     * the retained DEAL async function from its own {@code pairFirst} crossing,
     * so that await starts {@code pairSecond} beside it. The service hook posts
     * {@code pairFirst}'s completion first, while {@code pairSecond} is the
     * operation being awaited, so a delivery that resolved a frame to the
     * awaited record instead of the frame's own token swaps both values.
     */
    private static final String PAIRED_APP = """
        import * as probe from "host/async";
        import * as c from "std/console";

        async function awaitsSecond(): Promise<number> {
          let value: number = await probe.pairSecond();
          if (value === 222) { c.log("second:ok"); } else { c.log("second:bad"); }
          return value;
        }

        export async function run(): Promise<null> {
          let armed: number = probe.applyAsync(awaitsSecond);
          if (armed === 1) { c.log("paired:armed"); } else { c.log("paired:armed:bad"); }
          let first: number = await probe.pairFirst();
          if (first === 111) { c.log("first:ok"); } else { c.log("first:bad"); }
          return null;
        }

        export function main(): null { return null; }
        """;

    /**
     * The relayed DEAL failure (H2/H5): the host applies the retained DEAL
     * function inside its {@code relay} crossing, keeps the Error the failure
     * carries as an opaque handle, discards the issuing token and completes the
     * pending operation from the service hook with
     * {@code deal_error_from_handle} + {@code deal_async_complete_error}. The
     * awaited Error is asserted to be the same object with its mutated fields,
     * and the rethrow projects the origin, expected/actual and frames the
     * original failure recorded. The neighbouring {@code textCompletion} export
     * is completed through the same entry's host-text arm.
     */
    private static final String RELAY_APP = """
        import * as probe from "host/async";
        import * as c from "std/console";

        export async function run(): Promise<null> {
          let relayed: Error | null = null;
          let caughtValue: Error | null = null;
          function failer(): number {
            let numbers: number[] = [7];
            try {
              let bad: number = numbers[3];
              return bad;
            } catch (value) {
              let e: Error = value as Error;
              e.code = "E_RELAY_MUTATED";
              e.message = "mutated before the relay";
              relayed = e;
              throw e;
            }
          }
          let armed: number = probe.armFailure(failer);
          if (armed === 1) { c.log("relay:armed"); } else { c.log("relay:armed:bad"); }
          let observed: boolean = false;
          try {
            let value: number = await probe.relay();
            c.log("relay:completed");
            if (value === 0) { c.log("relay:completed"); }
          } catch (value) {
            let e: Error = value as Error;
            let original: Error | null = relayed;
            if (original === null) {
              c.log("relay:identity:bad");
            } else {
              if (e === original) { c.log("relay:identity:ok"); }
              else { c.log("relay:identity:bad"); }
            }
            caughtValue = e;
            if (e.code === "E_RELAY_MUTATED") { c.log("relay:code:ok"); }
            else { c.log("relay:code:bad"); }
            if (e.message === "mutated before the relay") {
              c.log("relay:message:ok");
            } else {
              c.log("relay:message:bad");
            }
            observed = true;
          }
          if (observed) { c.log("relay:caught"); }
          let textObserved: boolean = false;
          try {
            let value: number = await probe.textCompletion();
            c.log("relay:text:completed");
            if (value === 0) { c.log("relay:text:completed"); }
          } catch (value) {
            let e: Error = value as Error;
            if (e.code === "E_HOST_ASYNC_COMPLETE") {
              c.log("relay:text:code:ok");
            } else {
              c.log("relay:text:code:bad");
            }
            if (e.message === "the service hook completed with text") {
              c.log("relay:text:message:ok");
            } else {
              c.log("relay:text:message:bad");
            }
            textObserved = true;
          }
          if (textObserved) { c.log("relay:text:caught"); }
          let again: Error | null = caughtValue;
          if (again !== null) { throw again; }
          return null;
        }

        export function main(): null { return null; }
        """;

    /**
     * The completion ingress (H5/R3): an async-operation carrier and the
     * no-value marker are closed status probes, never data, and the real
     * completion that follows is the one the await observes.
     */
    private static final String INGRESS_APP = """
        import * as probe from "host/async";
        import * as c from "std/console";

        export async function run(): Promise<null> {
          let value: number = await probe.ingress();
          if (value === 7) { c.log("ingress:ok"); } else { c.log("ingress:bad"); }
          return null;
        }

        export function main(): null { return null; }
        """;

    /**
     * The reentrancy fixture: the driver invokes `outer`, whose pending host
     * completion leaves the pump active while the module's service hook
     * re-enters the recorded callback `hook`, which itself awaits a pending
     * host operation (H5's non-reentrancy rule).
     */
    private static final String REENTRANT_APP = """
        import * as probe from "host/async";

        async function awaitsPending(): Promise<number> {
          let value: number = await probe.pending();
          return value;
        }

        export async function outer(): Promise<null> {
          let kept: number = probe.applyAsync(awaitsPending);
          let value: number = await probe.pending();
          if (kept === 0 && value === 0) { return null; }
          return null;
        }

        export function main(): null { return null; }
        """;

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("=== LLVM Async Runtime Group Tests (ISSUE-0913) ===\n");
        Path scratch = NativeCompiler.executableScratch("llvm-async");
        try {
            testEmittedAsyncSurface(scratch);
            testConformingCompletions(scratch);
            testPendingCompletions(scratch);
            testCompletionBoundaryArms(scratch);
            testAwaitedErrorIdentity(scratch);
            testCompletionStatuses(scratch);
            testProtocolFailures(scratch);
            testAsyncShapeArm(scratch);
            testReentrantPump(scratch);
            testConcurrentPendingTokens(scratch);
            testRelayedErrorCompletion(scratch);
            testCompletionIngress(scratch);
            testCopiedFrameRecord();
        } finally {
            deleteRecursively(scratch);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== LLVM Async Runtime Group Tests Passed ===");
    }

    // =========================================================================
    // The emitted artifacts
    // =========================================================================

    /**
     * Criterion 1: the production emission of the async facts at both levels —
     * one ASYNC_START per await whose host callee carries the export's own
     * carrier, the completion descriptor at the await origin, the async export
     * surface dispatch, six export-surface functions, no process entry, valid
     * IR and identical bytes on a second compile.
     */
    private static void testEmittedAsyncSurface(Path scratch) throws Exception {
        System.out.println("-- the emitted async surface (C7/H5/H6) --");
        Path output = compile(scratch, "surface", VALUES_APP);
        String module = Files.readString(output.resolve("main.ll"),
            StandardCharsets.UTF_8);
        check(module.contains("@deal_async_start("),
            "ASYNC_START emits the runtime operation-record and token call");
        check(module.contains("@deal_async_await("),
            "AWAIT emits the await-origin completion call");
        check(module.contains("@deal_host_export_value("),
            "the host async callee is the declared export's own carrier");
        int started = count(module,
            "call %struct.deal_outcome_reference @deal_async_start(");
        checkEq(8, started, "each awaited host call emits one operation start"
            + " (the conforming fixture awaits eight exports)");
        for (String section : module.split("\\ndefine ")) {
            if (!section.contains("call %struct.deal_outcome_reference"
                    + " @deal_async_start(")) {
                continue;
            }
            String[] calls = section.split("\\n");
            int lastCarrier = -1;
            for (String line : calls) {
                if (line.contains("@deal_host_export_value(")) {
                    lastCarrier = 1;
                } else if (line.contains(" @deal_async_start(")) {
                    check(lastCarrier == 1, "the export carrier is created"
                        + " before the operation starts: " + line);
                }
            }
        }
        check(module.contains("call %struct.deal_outcome"
                + " @deal_async_export_call_host(%struct.deal_host_export*"),
            "the export surface dispatches a host async export to the async"
                + " crossing");
        check(module.contains("call %struct.deal_outcome"
                + " @deal_async_export_call(%struct.deal_fn*"),
            "the export surface dispatches a DEAL async export to its own"
                + " function value");
        check(module.contains("define %struct.deal_outcome"
                + " @deal_async_export_invoke(%struct.deal_export_entry*"),
            "the module defines the async export invocation entry");
        checkEq(1, count(module, "define %struct.deal_outcome"
            + " @deal_async_export_invoke("),
            "the async export invocation entry is defined once");
        check(!module.contains("define i32 @main("),
            "the emitted module defines no process entry (H6)");
        NativeCompiler.Result assembled = NativeCompiler.run(
            List.of("llvm-as", output.resolve("main.ll").toString()), scratch);
        checkEq(0, assembled.exitCode(),
            "the async project's IR is valid: " + assembled.describe());
        // Determinism (C9): a second compile of the same project produces
        // identical bytes.
        Path again = output.getParent().resolve("out-again");
        int status = deal.Main.run(new String[] {"compile",
            output.getParent().resolve("src/main.deal").toString(),
            "--backend", "llvm", "--output", again.toString()});
        checkEq(0, status, "the second compile of the async project succeeds");
        NativeCompiler.requireSameBytes(
            Files.readAllBytes(output.resolve("main.ll")),
            Files.readAllBytes(again.resolve("main.ll")),
            "two compiles of the async project emit identical module bytes");
        check(Files.isRegularFile(output.resolve("host/async.h")),
            "the async host declaration header is one published artifact");
    }

    // =========================================================================
    // The executed delivery paths
    // =========================================================================

    /**
     * Criterion 2 and 3: one delivery routine serves the posted number, the
     * {@code undefined} completion of {@code Promise<void>}, {@code null},
     * posted {@code undefined}, the managed completion on the mutator thread,
     * the copied text and the host-authored error completion, at both levels and
     * under {@code DEAL_GC_STRESS=1}.
     */
    private static void testConformingCompletions(Path scratch) throws Exception {
        System.out.println("-- the conforming completion arms (H5) --");
        Path output = compile(scratch, "values", VALUES_APP);
        String expected = """
            sync:ok
            void:ok
            boolean:ok
            null:ok
            undefined:ok
            managed:ok
            descriptor:ok
            descriptor:rejected
            copied:ok
            completion-error:ok
            completion-error:caught
            """;
        runExecuted(scratch, output, "values", 0, expected, 0, "");
    }

    /**
     * Criterion 2, 3 and 8: the pump's service-hook poll and the any-thread
     * posted completion each deliver on the waiting thread, and the poster
     * observes that it is not the mutator.
     */
    private static void testPendingCompletions(Path scratch) throws Exception {
        System.out.println("-- the pending service-hook and any-thread"
            + " completions (H5) --");
        Path output = compile(scratch, "pending", PENDING_APP);
        runExecuted(scratch, output, "pending", 1, "pending:ok\n", 0, "");
        runExecuted(scratch, output, "pending", 2, "pending:ok\n", 0, "");
    }

    /**
     * Criterion 4: the await-origin completion boundary renders the closed
     * {@code ASYNC_COMPLETION_KIND} arm for a wrong-kind completion and for a
     * malformed text completion, at the await expression's own origin.
     */
    private static void testCompletionBoundaryArms(Path scratch) throws Exception {
        System.out.println("-- the await-origin completion arms (C7/H5) --");
        for (String[] arm : new String[][] {
                {"wrongkind", WRONG_KIND_APP, "expected number",
                 "number", "string", "23"},
                {"malformed", MALFORMED_APP, "expected string",
                 "string", "invalid-unicode", "22"}}) {
            Path output = compile(scratch, arm[0], arm[1]);
            for (int level : LEVELS) {
                NativeCompiler.Result run = driver(scratch, output, arm[0],
                    level, 0, "run");
                checkEq(1, run.exitCode(), "-O" + level + " the " + arm[0]
                    + " completion stops the product: " + run.describe());
                check(run.stdout().contains("DEAL_ERROR_CODE: E8001"),
                    "-O" + level + " the " + arm[0] + " completion renders"
                        + " E8001: " + run.describe());
                check(run.stdout().contains("\"message\":\"" + arm[2] + "\""),
                    "-O" + level + " the " + arm[0] + " completion renders the"
                        + " async completion arm's own message: "
                        + run.describe());
                check(run.stdout().contains("\"expected\":\"" + arm[3] + "\"")
                        && run.stdout().contains("\"actual\":\"" + arm[4]
                            + "\""),
                    "-O" + level + " the " + arm[0] + " completion projects"
                        + " the closed tuple: " + run.describe());
                Matcher origin = Pattern.compile(
                    "\"sourceFile\":\"[^\"]*src/main\\.deal\",\"line\":(\\d+),"
                        + "\"column\":(\\d+)").matcher(run.stdout());
                boolean placed = origin.find();
                check(placed && origin.group(1).equals("4")
                        && origin.group(2).equals(arm[5]),
                    "-O" + level + " the " + arm[0] + " completion renders at"
                        + " the await expression's own origin: "
                        + run.describe());
                checkEq("", run.stderr(), "-O" + level + " the " + arm[0]
                    + " completion is a DEAL failure, not a protocol fault: "
                    + run.describe());
            }
        }
    }

    /**
     * Criterion 7: an awaited DEAL Error keeps one identity through the
     * operation and the await, and a mutation observed through the caught alias
     * is visible on the original object. A nested awaited call completes
     * synchronously.
     */
    private static void testAwaitedErrorIdentity(Path scratch) throws Exception {
        System.out.println("-- the awaited Error identity (C7/H5) --");
        Path output = compile(scratch, "identity", IDENTITY_APP);
        String expected = """
            identity:ok
            code:ok
            mutation:ok
            identity:caught
            nested:ok
            """;
        runExecuted(scratch, output, "identity", -1, expected, 0, "");
    }

    /**
     * Criterion 5: the closed completion statuses change nothing and answer the
     * prescribed status, asserted by the host unit itself; the managed
     * completion that follows the status probes is the one DEAL observes.
     */
    private static void testCompletionStatuses(Path scratch) throws Exception {
        System.out.println("-- the closed completion statuses (H5) --");
        Path output = compile(scratch, "statuses", STATUS_APP);
        runExecuted(scratch, output, "statuses", 5, "statuses:ok\n", 0, "");
    }

    /**
     * Criterion 6: a duplicate post, a completion naming no live operation and a
     * substituted operation carrier each write one deterministic stderr line and
     * exit 2 without a DEAL terminal projection.
     */
    private static void testProtocolFailures(Path scratch) throws Exception {
        System.out.println("-- the fatal protocol cases (H5) --");
        for (String[] probe : new String[][] {
                {"duplicate", "duplicate", "6",
                 "a duplicate completion for one async operation", ""},
                {"unknownToken", "unknownToken", "7",
                 "a completion names no live async operation", ""},
                {"substituted", "substituted", "8",
                 "a host async export returned a substituted operation token",
                 ""},
                {"late", "pending", "10",
                 "a completion names no live async operation", "late:ok\n"}}) {
            Path output = compile(scratch, probe[0],
                probe[0].equals("late") ? LATE_APP
                    : PROTOCOL_APP.formatted("probe." + probe[1]));
            for (int level : LEVELS) {
                NativeCompiler.Result run = driver(scratch, output, probe[0],
                    level, Integer.parseInt(probe[2]), "run");
                checkEq(2, run.exitCode(), "-O" + level + " the " + probe[0]
                    + " case is the deterministic protocol failure: "
                    + run.describe());
                checkEq("load:start\nload:done\nrun:start\n" + probe[4],
                    run.stdout(), "-O" + level + " the " + probe[0]
                        + " case publishes no DEAL terminal projection: "
                        + run.describe());
                checkEq("DEAL_HOST_PROTOCOL: " + probe[3] + "\n",
                    run.stderr(), "-O" + level + " the " + probe[0]
                        + " case writes one deterministic stderr line: "
                        + run.describe());
            }
        }
    }

    /**
     * The interface contract's closed shape arm (H5): a declared async export
     * that answers a carrier of another kind renders
     * {@code ASYNC_OPERATION_HANDLE} with the typed-boundary actual projection
     * at the async call origin, and the operation is abandoned.
     */
    private static void testAsyncShapeArm(Path scratch) throws Exception {
        System.out.println("-- the host async operation-shape arm (H5) --");
        Path output = compile(scratch, "wrongshape", WRONG_SHAPE_APP);
        for (int level : LEVELS) {
            NativeCompiler.Result run = driver(scratch, output, "wrongshape",
                level, 11, "run");
            checkEq(1, run.exitCode(), "-O" + level + " the wrong-shape async"
                + " return stops the product: " + run.describe());
            check(run.stdout().contains("DEAL_ERROR_CODE: E8010"),
                "-O" + level + " the wrong-shape async return renders E8010: "
                    + run.describe());
            check(run.stdout().contains("\"message\":\"host async function"
                    + " must return an async operation, got number\""),
                "-O" + level + " the arm's own message carries the"
                    + " typed-boundary actual: " + run.describe());
            check(run.stdout().contains("\"expected\":\"async operation\"")
                    && run.stdout().contains("\"actual\":\"number\""),
                "-O" + level + " the arm projects its closed expected and"
                    + " actual fields: " + run.describe());
            checkEq("", run.stderr(), "-O" + level + " the wrong-shape async"
                + " return is a DEAL failure, not a protocol fault: "
                + run.describe());
        }
    }

    /**
     * Criterion 6: an {@code AWAIT} reached while this thread is already inside
     * the pump is the deterministic protocol failure, because blocking lowering
     * cannot suspend the inner activation (H5). The service hook re-enters DEAL
     * code through a retained async function value (H3/H4), so the re-entered
     * body's own await would pump while this thread is pumping.
     */
    private static void testReentrantPump(Path scratch) throws Exception {
        System.out.println("-- the reentrant pump (H5) --");
        Path output = compile(scratch, "outer", REENTRANT_APP);
        for (int level : LEVELS) {
            NativeCompiler.Result run = driver(scratch, output, "outer", level,
                9, "outer");
            checkEq(2, run.exitCode(), "-O" + level + " the reentrant AWAIT is"
                + " the deterministic protocol failure: " + run.describe());
            checkEq("DEAL_HOST_PROTOCOL: a reentrant async pump\n",
                run.stderr(), "-O" + level + " the reentrant pump writes one"
                    + " deterministic stderr line: " + run.describe());
            checkEq("load:start\nload:done\nrun:start\n", run.stdout(),
                "-O" + level + " the reentrant pump publishes no DEAL terminal"
                    + " projection: " + run.describe());
        }
    }

    /**
     * Criterion 9: two host operations are pending at once with distinct
     * runtime-issued tokens, and each await observes exactly its own completion.
     * The first operation's frame is taken while the second operation is the one
     * being awaited, so the open-operation table's token-to-record binding, the
     * pump's per-frame resolution and the retire-on-consume path are all
     * exercised with two live records.
     */
    private static void testConcurrentPendingTokens(Path scratch) throws Exception {
        System.out.println("-- two concurrently pending operations (H5) --");
        Path output = compile(scratch, "paired", PAIRED_APP);
        String expected = """
            paired:armed
            second:ok
            first:ok
            """;
        runExecuted(scratch, output, "paired", 12, expected, 0, "");
    }

    /**
     * Criterion 10: a DEAL-origin failure relayed through a host crossing keeps
     * one identity, its mutated fields and its original origin, projection and
     * frames across the retained handle and the waiting-thread completion, and
     * the host-text arm of the same entry materializes the host's own code and
     * message with no invented arm, origin or frames.
     */
    private static void testRelayedErrorCompletion(Path scratch) throws Exception {
        System.out.println("-- the relayed DEAL Error completion (H2/H5, R5) --");
        Path output = compile(scratch, "relay", RELAY_APP);
        String receipts = """
            relay:armed
            relay:identity:ok
            relay:code:ok
            relay:message:ok
            relay:caught
            relay:text:code:ok
            relay:text:message:ok
            relay:text:caught
            """;
        String prefix = "load:start\nload:done\nrun:start\n" + receipts;
        /* The rethrown awaited Error is the object the failing read inside
         * `failer` created: its mutation survives, and its origin, projection
         * and innermost-first frame snapshot are the ones recorded at detection
         * (no cause, no refresh). */
        Pattern terminal = Pattern.compile(
            "DEAL_ERROR_CODE: E_RELAY_MUTATED\\nDEAL_ERROR_SNAPSHOT: "
                + "\\{\\\"code\\\":\\\"E_RELAY_MUTATED\\\",\\\"message\\\":"
                + "\\\"mutated before the relay\\\",\\\"sourceFile\\\":"
                + "\\\"[^\\\"]*src/main\\.deal\\\",\\\"line\\\":10,"
                + "\\\"column\\\":25,\\\"frames\\\":2\\}\\n");
        for (int level : LEVELS) {
            NativeCompiler.Result run = buildAndExecuteTwice(scratch, output,
                "relay", level, 13, "run");
            checkEq(1, run.exitCode(), "-O" + level + " the relayed DEAL Error"
                + " stops the product through its own snapshot: "
                + run.describe());
            check(run.stdout().startsWith(prefix), "-O" + level + " the"
                + " relayed DEAL Error keeps one identity through the handle"
                + " and the awaited completion, its mutated code and message"
                + " are the caught Error's, and the host-text arm keeps the"
                + " host's own code and message: " + run.describe());
            check(terminal.matcher(run.stdout().substring(
                Math.min(prefix.length(), run.stdout().length()))).matches(),
                "-O" + level + " the rethrown awaited Error projects the"
                    + " original origin, line, column and frame count with no"
                    + " cause and no refreshed frames: " + run.describe());
            checkEq("", run.stderr(), "-O" + level + " the relayed completion"
                + " is no protocol fault: " + run.describe());
        }
    }

    /**
     * Criterion 11: the completion ingress rejects an async-operation carrier
     * and the no-value marker with the closed kind status, changes nothing, and
     * the operation still receives the real completion that follows (no
     * operation becomes data, R3/V1.5).
     */
    private static void testCompletionIngress(Path scratch) throws Exception {
        System.out.println("-- the completion ingress statuses (H5/R3) --");
        Path output = compile(scratch, "ingress", INGRESS_APP);
        runExecuted(scratch, output, "ingress", 14, "ingress:ok\n", 0, "");
    }

    /**
     * Criterion 12: the async frame record carries copied native data only — no
     * carrier, no handle and no DEAL-origin failure token (R1/R3, H5).
     */
    private static void testCopiedFrameRecord() throws IOException {
        System.out.println("-- the copied frame record (R1/H5) --");
        String source = Files.readString(
            REPO.resolve("deal/native/async.c"), StandardCharsets.UTF_8);
        Matcher record = Pattern.compile(
            "(?s)typedef struct \\{\\s*deal_async_token token;\\s*"
                + "uint32_t kind;.*?\\} deal_async_frame_record;")
            .matcher(source);
        boolean found = record.find();
        check(found, "the async copy-in record is one declared structure");
        if (!found) {
            return;
        }
        String body = record.group();
        check(!body.contains("deal_value "),
            "the copied record holds no crossing carrier");
        check(!body.contains("deal_handle "),
            "the copied record holds no host handle");
        check(!body.contains("deal_error_token "),
            "the copied record holds no DEAL-origin failure token");
        check(!body.contains("deal_error *") && !body.contains("deal_error* "),
            "the copied record holds no managed Error pointer");
        check(!body.contains("deal_data "),
            "the copied record holds no persistent datum");
    }

    // =========================================================================
    // Shared harness
    // =========================================================================

    /** One scratch project with the async declaration module and one app. */
    private static Path compile(Path scratch, String name, String app)
            throws IOException {
        Path root = scratch.resolve("project-" + name);
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("bindings"));
        Files.writeString(root.resolve("deal.json"), DEAL_JSON,
            StandardCharsets.UTF_8);
        Files.writeString(root.resolve("bindings/async.d.deal"), DECLARATION,
            StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/main.deal"), app,
            StandardCharsets.UTF_8);
        Path output = root.resolve("out");
        int status = deal.Main.run(new String[] {"compile",
            root.resolve("src/main.deal").toString(),
            "--backend", "llvm", "--output", output.toString()});
        checkEq(0, status, "the production compile of the '" + name
            + "' fixture publishes the project");
        return output;
    }

    /**
     * The production driver-shape build of one case with the fixture host unit
     * at one optimization level, then its execution.
     */
    private static NativeCompiler.Result driver(Path scratch, Path output,
                                                String name, int level,
                                                int hostCase,
                                                String exportName)
            throws Exception {
        Path unit = hostUnit(scratch, hostCase);
        Path product = scratch.resolve("product-" + name + "-" + hostCase
            + "-" + level);
        deleteRecursively(product);
        NativeCompiler.build(new deal.toolchain
            .NativeBuildRequest(output, product, level, List.of(unit),
                DRIVER_SOURCE.formatted(exportName)));
        return NativeCompiler.execute(product, scratch, Map.of());
    }

    /**
     * The fixture host unit of one case, with its behavior selector. The unit's
     * file stem is the imported declaration module's stem, so the generated
     * registry pairs it with the {@code host/async} import (H1).
     */
    private static Path hostUnit(Path scratch, int hostCase) throws IOException {
        Path unit = scratch.resolve("async-case-" + hostCase).resolve("async.c");
        if (!Files.exists(unit)) {
            Files.createDirectories(unit.getParent());
            Files.writeString(unit, "#define DEAL_HOST_ASYNC_CASE " + hostCase
                + "\n" + Files.readString(
                    REPO.resolve("test/native/async_probe.c"),
                    StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        }
        return unit;
    }

    /** Builds once, then executes normally and under {@code DEAL_GC_STRESS=1}. */
    private static void runExecuted(Path scratch, Path output, String name,
                                    int hostCase, String expected, int exitCode,
                                    String stderr) throws Exception {
        for (int level : LEVELS) {
            NativeCompiler.Result run = buildAndExecuteTwice(scratch, output,
                name, level, hostCase, "run");
            String full = "load:start\nload:done\nrun:start\n" + expected
                + "run:done\n";
            if (exitCode != 0) {
                full = "load:start\nload:done\nrun:start\n" + expected;
            }
            checkEq(exitCode, run.exitCode(), "-O" + level + " the '" + name
                + "' fixture exits " + exitCode + ": " + run.describe());
            checkEq(full, run.stdout(), "-O" + level + " the '" + name
                + "' fixture writes its exact output: " + run.describe());
            checkEq(stderr, run.stderr(), "-O" + level + " the '" + name
                + "' fixture writes no diagnostics: " + run.describe());
        }
    }

    /**
     * The production driver-shape build of one case at one optimization level,
     * executed normally and under {@code DEAL_GC_STRESS=1}; both runs must agree
     * exactly (collection timing changes nothing observable, M7).
     */
    private static NativeCompiler.Result buildAndExecuteTwice(
            Path scratch, Path output, String name, int level, int hostCase,
            String exportName) throws Exception {
        List<Path> hosts = hostCase < 0
            ? List.of() : List.of(hostUnit(scratch, hostCase));
        Path product = scratch.resolve("product-" + name + "-" + hostCase
            + "-" + level);
        deleteRecursively(product);
        NativeCompiler.build(new deal.toolchain
            .NativeBuildRequest(output, product, level, hosts,
                DRIVER_SOURCE.formatted(exportName)));
        NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
            Map.of());
        NativeCompiler.Result stressed = NativeCompiler.execute(product, scratch,
            Map.of("DEAL_GC_STRESS", "1"));
        checkEq(run.exitCode(), stressed.exitCode(), "-O" + level + " the '"
            + name + "' fixture survives a full collection at every"
            + " safepoint: " + stressed.describe());
        checkEq(run.stdout(), stressed.stdout(), "-O" + level + " the '"
            + name + "' fixture is byte-identical under DEAL_GC_STRESS");
        checkEq(run.stderr(), stressed.stderr(), "-O" + level + " the stressed '"
            + name + "' fixture writes no diagnostics");
        return run;
    }

    /**
     * The driver shape's own translation unit (D4/H6): it writes the ordered
     * load and probe receipts, owns the process entry and invokes the recorded
     * async export through the export surface.
     */
    private static final String DRIVER_SOURCE = """
        #include "deal/native/entry.h"
        #include "deal/native/platform.h"

        #include <string.h>

        static void say(const char *text) {
          deal_platform_write_stdout(text, strlen(text));
          deal_platform_flush_stdout();
        }

        static int report(deal_error *error) {
          const char *code = NULL;
          if (!deal_error_code(error, &code) || code == NULL) {
            code = "";
          }
          char *snapshot = deal_error_snapshot(error);
          deal_platform_write_stdout("DEAL_ERROR_CODE: ", 17);
          deal_platform_write_stdout(code, strlen(code));
          deal_platform_write_stdout("\\n", 1);
          deal_platform_write_stdout("DEAL_ERROR_SNAPSHOT: ", 21);
          deal_platform_write_stdout(snapshot, strlen(snapshot));
          deal_platform_write_stdout("\\n", 1);
          deal_platform_flush_stdout();
          deal_error_snapshot_release(snapshot);
          return 1;
        }

        int main(void) {
          say("load:start\\n");
          deal_outcome started = deal_project_start();
          if (started.error != NULL) {
            return report(started.error);
          }
          say("load:done\\n");
          deal_module *module = deal_project_exports("main");
          if (module == NULL) {
            return 2;
          }
          deal_export_entry *entry = deal_export_lookup(module, "%s");
          if (entry == NULL) {
            return 3;
          }
          say("run:start\\n");
          deal_outcome invoked = deal_async_export_invoke(entry);
          if (invoked.error != NULL) {
            return report(invoked.error);
          }
          say("run:done\\n");
          return 0;
        }
        """;

    private static int count(String text, String needle) {
        int found = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            found++;
            index = text.indexOf(needle, index + needle.length());
        }
        return found;
    }

    private static void check(boolean condition, String what) {
        if (condition) {
            passed += 1;
        } else {
            failed += 1;
            System.out.println("FAIL: " + what);
        }
    }

    private static void checkEq(Object expected, Object actual, String what) {
        if (expected.equals(actual)) {
            passed += 1;
        } else {
            failed += 1;
            System.out.println("FAIL: " + what + " (expected <" + expected
                + ">, got <" + actual + ">)");
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
