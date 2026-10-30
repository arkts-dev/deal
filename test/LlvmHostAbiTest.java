package deal.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The synchronous native host ABI end to end (ISSUE-0912:
 * {@code llvm-native-host-abi-and-async} H1, H2, H3, H7,
 * {@code LoadHostModule}, {@code HostCallSync}, {@code RetainRelease},
 * {@code ExportInvoke}).
 *
 * <p>Every case runs the production path: {@code deal compile --backend llvm}
 * publishes the one project module plus one generated declaration header per
 * imported host declaration module, {@code deal native build} links the supplied
 * host unit through the generated registry at the pinned flags, and the product
 * executes at {@code -O0} and {@code -O2}.</p>
 *
 * <p>Covered criteria:</p>
 *
 * <ol>
 *   <li>the generated {@code <host module path>.h} is one published artifact of
 *       the same transaction; a standalone strict-C11 consumer includes only
 *       that header and compiles against the H2 declaration surface, the module
 *       specifier macro, the registration extern and the inline typed wrappers;
 *   <li>the emitted per-import declared-module record, the origin-carrying load
 *       call in the unit's initialization and the import's own module record are
 *       present, in declaration order, and the module layout stays emitted-only;
 *   <li>the H7 arms are reached by executed cases: an omitted unit is
 *       {@code HOST_LOAD_FAILED} with the pinned reason text, a missing declared
 *       export is {@code HOST_LOAD_MISSING_EXPORT}, a descriptor mismatch is
 *       {@code HOST_LOAD_SIGNATURE_MISMATCH}, a class identity mismatch is
 *       {@code HOST_LOAD_IDENTITY_MISMATCH}, malformed class metadata is
 *       {@code HOST_LOAD_INVALID_METADATA}, and extra exports are ignored;
 *   <li>a synchronous host crossing carries declared parameters and results in
 *       both directions: a host-built class instance materializes with its
 *       declared field value, a typed array's two slots naming one host instance
 *       stay one DEAL value, a host-authored failure becomes the carried Error
 *       with the host's own text, and the {@code C$fromJson}-independent
 *       host-declared class layout is the one the project emits;
 *   <li>the criterion-4 presentations execute at both levels: a nested
 *       class-typed field, a record graph at the declared {@code record}
 *       position (a class member, a function member and a rejected malformed
 *       key), a self-referential graph keeping its identity, a class that
 *       declares no field identifying itself at a declared class position and
 *       at {@code unknown}, a malformed text carrier at a declared
 *       {@code string} position, a hole in a typed array at a declared element
 *       position, and the host object's non-mutation after the crossing
 *       materialized it into DEAL data (re-read through the value surface);
 *   <li>the handle and DEAL-origin failure channel of H2/H3 through a C driver
 *       against the published runtime tree: retain/handle-value/release
 *       statuses, the identity-preserving relay of one Error and the discard
 *       path.
 *   <li>the V1.5 allocation surface: the result-only {@code void} position
 *       normalizes an admitted {@code nothing} and rejects a number with the
 *       return boundary's own arm, an empty array keeps its persistent element
 *       descriptor (a matching empty array is admitted, a mismatched one is
 *       rejected), a host-created function value is checked and extracted by
 *       identity and applied, and own-presence distinguishes a host-built
 *       class instance's present required field from its absent optional one;
 *   <li>the canonical both-member nullish descriptor ({@code ?!T}, spec
 *       {@code RuntimeTypeDescriptor}) is accepted wherever a host descriptor
 *       text arrives — a declared class field, a declared export result, a typed
 *       array's persistent element descriptor and {@code deal_handle_value} —
 *       while every non-canonical nullish shape is the closed
 *       {@code bad-descriptor} status and never a process fault;
 *   <li>the host absence marker is never data (H2/R2/R11): a {@code deal_absent()}
 *       carrier at a declared {@code unknown} result, at a declared class field
 *       that admits {@code undefined} and at a view store into such a field
 *       renders the declared position's own arm or the closed kind status;
 *   <li>the relayed DEAL-origin failure projected by an embedding driver through
 *       the R9 accessors: field text, origin (source file, line, column),
 *       innermost-first frame count and label, absent cause/expected/actual, the
 *       retained-Error relay in a later crossing by identity, the discard path
 *       and the unconsumed-token protocol failure, each repeated under
 *       {@code DEAL_GC_STRESS=1};
 *   <li>the function-typed value positions of H4: a declared function-valued
 *       export result crosses at its declared function position through the
 *       export's own signature result atom (a wrong kind and a differently
 *       typed function value are the return boundary's arms), and a host that
 *       applies a host-declared export value itself runs that value's declared
 *       parameter and result boundaries, including the untyped-array
 *       presentation and the closed argument-count status;
 *   <li>R11's closed host-boundary contract for the remaining malformed
 *       presentations: a host-built {@code Error} that omits a required field
 *       renders the missing field's own host cell arm, and a class atom outside
 *       the published {@code @modulePath/ClassName} grammar answers the closed
 *       {@code bad-descriptor} status instead of a process fault.
 *   <li>the declared host parameter cells of H2/H4: a {@code boolean}-valued
 *       declared result crosses as the C3 {@code i1}, and a stale-narrowed
 *       argument — at a scalar and at a function-typed position — renders the
 *       shared composite {@code E8010} arm at the crossing's own origin with the
 *       declared cell descriptor and the shared host-carrier token, never the
 *       typed-boundary arm at the argument expression.
 *   <li>R4's closed boundary rule at the declared function-valued positions of
 *       the value surface: a retained function value re-validated against its
 *       own canonical descriptor, the closed {@code kind} status for a
 *       mismatched kind and signature, a typed array of function elements
 *       materializing and taking a view store of a declared element, and a
 *       host-declared class whose field is a function type crossing a host-built
 *       graph and taking a view store of that slot, which the emitted layout also
 *       traces as a managed reference so the stored function value survives
 *       every collection.
 * </ol>
 */
public final class LlvmHostAbiTest {

    private static final Path REPO = NativeCompiler.repository();
    private static final int[] LEVELS = {0, 2};

    private static final String DEAL_JSON = """
        {"languageVersion": "1.5", "backend": "llvm",
         "moduleRoots": ["src"], "output": "out",
         "externals": {"host/probe": {"declaration": "bindings/probe.d.deal"}}}
        """;

    /** The fixture whose declared class carries a function-typed field. */
    private static final String SLOT_JSON = """
        {"languageVersion": "1.5", "backend": "llvm",
         "moduleRoots": ["src"], "output": "out",
         "externals": {"host/slot": {"declaration": "bindings/slot.d.deal"}}}
        """;

    private static final String SLOT_DECLARATION = """
        export class Action {
          run: (x: number) => number;
        }

        export function makeAction(): Action;
        export function probeFunctionSlot(value: Action): number;
        """;

    private static final String SLOT_APP = """
        import * as probe from "host/slot";

        function check(condition: boolean, message: string): null {
          if (!condition) {
            throw { code: "TEST_FAIL", message: message };
          }
          return null;
        }

        export function main(): null {
          let action: probe.Action = probe.makeAction();
          check(action.run(1) === 2,
                "the host-built function field applies through the crossing");
          check(probe.probeFunctionSlot(action) === 1,
                "the host view store of the declared function slot");
          check(action.run(2) === 3,
                "the admitted host view store commits in DEAL");
          return null;
        }
        """;

    private static final String DECLARATION = """
        export class Config {
          value: number;
          label?: string;
        }

        export class Other {
          value: number;
          label: string;
        }

        export class Holder {
          inner: Config;
        }

        export class Empty {}

        export class Nullish {
          value: number;
          note: string | null | undefined;
        }

        export function sum(a: number, b: number): number;
        export function echoBoolean(value: boolean): boolean;
        export function makeConfig(): Config;
        export function makeArray(): Config[];
        export function makeWrongElementArray(): Config[];
        export function makeEmptyConfigArray(): Config[];
        export function makeEmptyOtherArray(): Config[];
        export function makeFunction(): unknown;
        export function applyFunction(f: () => number): number;
        export function noop(): void;
        export function makeError(): Error;
        export function hostTextFailure(): number;
        export function handleProbe(): number;
        export function applyRelay(callback: () => number): number;
        export function applyDiscard(callback: () => number): number;
        export function makeHolder(): Holder;
        export function makeEmpty(): Empty;
        export function probeEmpty(value: Empty): number;
        export function probeConfig(value: Config): number;
        export function echo(value: unknown): unknown;
        export function makeNullish(): Nullish;
        export function pickNullish(): number | null | undefined;
        export function nullishList(): (number | null | undefined)[];
        export function probeNullish(value: Nullish): number;
        export function absentFieldGraph(): Config;
        export function malformedText(): string;
        export function makeSparseArray(): number[];
        export function makeRecord(): Record<string, unknown>;
        export function makeCycle(): unknown[];
        export function applyGraph(callback: (root: Config[]) => number): number;
        export function innerValue(x: number): number;
        export function makeInner(): () => number;
        export function wrongInnerKind(): () => number;
        export function wrongInnerSignature(): () => number;
        export function makeUntyped(): unknown;
        export function applyOne(f: (x: number) => number): number;
        export function applyUnknownResult(f: () => unknown): number;
        export function untypedArgument(f: (x: number) => number): number;
        export function makeBrokenError(): Error;
        export function classAtomStatuses(): number;
        """;

    /** The executed fixture: every synchronous crossing the declaration names. */
    private static final String APP = """
        import * as probe from "host/probe";

        function check(condition: boolean, message: string): null {
          if (!condition) {
            throw { code: "TEST_FAIL", message: message };
          }
          return null;
        }

        export function main(): null {
          let total: number = probe.sum(1, 2);
          check(total === 3, "the scalar parameter and return crossing");
          // The declared `boolean` result position (H2 verification 3): the
          // emitted host call stores the `i1` the projection already answers.
          check(probe.echoBoolean(true) === true,
                "the boolean result of a host crossing");
          check(probe.echoBoolean(false) === false,
                "the false boolean result of a host crossing");
          let made: probe.Config = probe.makeConfig();
          check(made.value === 42, "the host-built class field value");
          let all: probe.Config[] = probe.makeArray();
          check(all.length === 2, "the class-element array length");
          check(all[0].value === 42, "the class-element array element value");
          check(all[0] === all[1],
                "two slots naming one host instance are one DEAL value");
          let rejected: boolean = false;
          try {
            let wrong: probe.Config[] = probe.makeWrongElementArray();
            check(wrong.length !== 0,
                  "a wrong-element class array was admitted as DEAL data");
          } catch (value) {
            let element: Error = value as Error;
            check(element.code === "E8003",
                  "the wrong-element class array rejected at its element");
            check(element.message === "array element 1 type mismatch",
                  "the element arm's own message");
            rejected = true;
          }
          check(rejected, "the wrong-element class array crossing rejected");
          // V1.5: the declared result-only void position normalizes to
          // undefined, own-presence distinguishes the present required field
          // from the absent optional one, an empty array keeps its persistent
          // element descriptor and a host-created function value is checked,
          // extracted and applied by identity.
          probe.noop();
          check(Object.hasOwn(made, "value"),
                "the present required field is own");
          check(!Object.hasOwn(made, "label"),
                "the absent optional field is not own");
          let emptyOk: probe.Config[] = probe.makeEmptyConfigArray();
          check(emptyOk.length === 0,
                "an empty array with the matching descriptor is admitted");
          let emptyRejected: boolean = false;
          try {
            let wrongEmpty: probe.Config[] = probe.makeEmptyOtherArray();
            check(wrongEmpty.length !== 0,
                  "an empty wrong-descriptor class array was admitted");
          } catch (value) {
            let element: Error = value as Error;
            check(element.code === "E8010",
                  "the empty array descriptor mismatch renders E8010");
            check(element.message
                  === "return value 1 type mismatch: expected array",
                  "the empty array keeps its persistent element descriptor");
            emptyRejected = true;
          }
          check(emptyRejected, "the empty array descriptor mismatch rejected");
          let raw: unknown = probe.makeFunction();
          let hostFn: () => number = raw as () => number;
          check(hostFn() === 41,
                "the checked function extraction applies the host function");
          let extracted: () => number = raw as () => number;
          check(extracted === hostFn,
                "the checked extraction preserves the function identity");
          check(probe.applyFunction(hostFn) === 41,
                "the host applies the same function value");
          let handled: number = probe.handleProbe();
          check(handled === 1,
                "the retained class identity and the handle statuses");
          let hosted: Error = probe.makeError();
          check(hosted.code === "E_HOST_BUILT",
                "the host-built Error code");
          check(hosted.message === "the host built this error",
                "the host-built Error message");
          let caught: boolean = false;
          try {
            let ignored: number = probe.hostTextFailure();
            check(ignored !== ignored, "the host failure returned a value");
          } catch (value) {
            let e: Error = value as Error;
            check(e.code === "E_HOST_TEXT", "the host failure code");
            check(e.message === "the host refused the call",
                  "the host failure message");
            caught = true;
          }
          check(caught, "the host-authored failure propagated");
          let applied: Error = { code: "E_APPLY", message: "the applied function threw" };
          let boom: () => number = function(): number {
            if (applied.code === "E_APPLY_NEVER") {
              return 0;
            }
            applied.code = "E_APPLY_MUTATED";
            throw applied;
          };
          let relayedApply: boolean = false;
          try {
            let ignoredApply: number = probe.applyRelay(boom);
            check(false, "the relayed apply failure returned a value");
          } catch (value) {
            let e: Error = value as Error;
            check(e === applied,
                  "the relayed apply Error is the identical object");
            check(e.code === "E_APPLY_MUTATED", "the relayed apply code");
            check(e.message === "the applied function threw",
                  "the relayed apply message");
            relayedApply = true;
          }
          check(relayedApply, "the host relayed the applied function failure");
          let discardedApply: number = probe.applyDiscard(boom);
          check(discardedApply === 5,
                "a discarded apply failure propagates nothing");
          // H2 verification 4: a nested class field, a record graph at the
          // declared `record` position, a self-referential graph, a
          // zero-declared-field class and the host object's non-mutation.
          let holder: probe.Holder = probe.makeHolder();
          check(holder.inner.value === 42,
                "the nested class field materializes at its declared position");
          let record: Record<string, unknown> = probe.makeRecord();
          let member: probe.Config = record["cfg"] as probe.Config;
          check(member.value === 42, "the record's class member keeps its value");
          let memberFunction: () => number = record["fn"] as () => number;
          check(memberFunction() === 41, "the record's function member applies");
          let memberLabel: string = record["label"] as string;
          check(memberLabel === "record", "the record's string member");
          // A store through a host view of a DEAL-held instance is checked
          // against the declared slot, commits nothing on rejection and
          // commits through the insertion barrier on admission (R11).
          let stored: probe.Config = probe.makeConfig();
          check(probe.probeConfig(stored) === 1,
                "the host view store of the declared class slot");
          check(stored.value === 43,
                "the admitted host view store commits in DEAL");
          let cycle: unknown[] = probe.makeCycle();
          check(cycle.length === 2, "the self-referential graph's length");
          let looped: unknown[] = cycle[0] as unknown[];
          check(looped === cycle, "the self-reference keeps its identity");
          let tailValue: string = cycle[1] as string;
          check(tailValue === "tail", "the self-referential graph's second slot");
          let graphTotal: number = probe.applyGraph(
            function(root: probe.Config[]): number {
              check(root[0] === root[1],
                    "the applied graph keeps its alias identity");
              check(root.length === 2, "the applied graph's length");
              return root[0].value;
            });
          check(graphTotal === 42, "the applied graph answers the host value");
          // A class that declares no field still identifies itself: the
          // declared class position and the unknown position both admit it and
          // the checked extraction keeps the identical object.
          let blank: probe.Empty = probe.makeEmpty();
          check(probe.probeEmpty(blank) === 1,
                "the zero-declared-field instance identifies itself by its own"
                + " atom and declares no field");
          let echoed: unknown = probe.echo(blank);
          let narrowed: probe.Empty = echoed as probe.Empty;
          check(narrowed === blank,
                "the zero-declared-field instance keeps its identity");
          // The remaining negative presentations of the issue's criterion 4:
          // a malformed text carrier at a declared `string` position and a hole
          // in a typed array at a declared element position.
          let textRejected: boolean = false;
          try {
            let text: string = probe.malformedText();
            check(false, "a malformed host string was admitted");
            check(text === text, "the admitted malformed text");
          } catch (value) {
            let e: Error = value as Error;
            check(e.code === "E8010", "the malformed text renders E8010");
            check(e.message === "return value 1 type mismatch: expected string, got UTF-16 surrogate code point",
                  "the host string arm's own reason is the inner text");
            textRejected = true;
          }
          check(textRejected,
                "the malformed text is rejected at the declared position");
          let holeRejected: boolean = false;
          try {
            let holes: number[] = probe.makeSparseArray();
            check(holes.length < 0, "a sparse host array was admitted");
          } catch (value) {
            let e: Error = value as Error;
            check(e.code === "E8003", "the hole renders E8003");
            check(e.message === "array element 1 type mismatch",
                  "the element arm names the one-based hole index");
            holeRejected = true;
          }
          check(holeRejected,
                "a hole is rejected at the declared element position");
          // The canonical both-member nullish descriptor (`?!T`) of the spec's
          // `RuntimeTypeDescriptor` reaches a declared class field, a declared
          // export result and a typed array's persistent element descriptor.
          let nullish: probe.Nullish = probe.makeNullish();
          check(nullish.value === 7, "the nullish class field beside it");
          check(nullish.note === null,
                "the canonical both-member nullish class field");
          let picked: number | null | undefined = probe.pickNullish();
          check(picked === null,
                "the canonical both-member nullish export result");
          let listed: (number | null | undefined)[] = probe.nullishList();
          check(listed.length === 2, "the nullish element array length");
          check(listed[0] === null,
                "the canonical both-member nullish element descriptor");
          let second: number = listed[1] as number;
          check(second === 3, "the nullish element array's number element");
          check(probe.probeNullish(nullish) === 1,
                "the absent marker is not storable in a declared nullish slot");
          // The absence marker is presence metadata, never a datum: a host
          // class field carrying it is rejected at the field's declared slot.
          let absentRejected: boolean = false;
          try {
            let absentGraph: probe.Config = probe.absentFieldGraph();
            check(absentGraph.value < 0,
                  "a host class field carrying the absence marker was admitted");
          } catch (value) {
            let e: Error = value as Error;
            check(e.code === "E8010",
                  "the absent class field renders E8010");
            check(e.message
                  === "return value 1 type mismatch: expected number",
                  "the absent class field renders its own declared slot arm");
            absentRejected = true;
          }
          check(absentRejected,
                "a host class field carrying the absence marker is rejected");
          // H4: the export's own canonical signature declares its result
          // position, so a function-valued result is checked as that function
          // and applies through the host thunk, while a wrong-kind or
          // differently typed function at that position is rejected.
          let produced: () => number = probe.makeInner();
          check(probe.applyFunction(produced) === 41,
                "the function-valued host result crosses and applies");
          let wrongKind: boolean = false;
          try {
            let numberAtFunction: () => number = probe.wrongInnerKind();
            check(false, "a number at a declared function position was admitted");
          } catch (value) {
            let e: Error = value as Error;
            check(e.code === "E8010",
                  "the function-position kind mismatch renders E8010");
            check(e.message
                  === "return value 1 type mismatch: expected function",
                  "the function-position arm names the declared function");
            wrongKind = true;
          }
          check(wrongKind,
                "a number is rejected at a declared function position");
          let wrongSignature: boolean = false;
          try {
            let differ: () => number = probe.wrongInnerSignature();
            check(false, "a differently typed function value was admitted");
          } catch (value) {
            let e: Error = value as Error;
            check(e.code === "E8010",
                  "the function signature mismatch renders E8010");
            check(e.message
                  === "return value 1 type mismatch: function signature mismatch: expected ()->number, got (number)->number",
                  "the signature arm names both canonical signatures");
            wrongSignature = true;
          }
          check(wrongSignature,
                "a function value of another signature is rejected");
          // H4: a host that applies a host-declared export value itself runs
          // the declared parameter and result boundaries of that value.
          let parameterRejected: boolean = false;
          try {
            let ignored: number = probe.applyOne(probe.innerValue);
            check(false, "the host applied a wrong-kind argument unchecked");
          } catch (value) {
            let e: Error = value as Error;
            check(e.code === "E8010",
                  "the host-applied parameter boundary renders E8010");
            check(e.message === "parameter 1 type mismatch: expected number",
                  "the parameter arm names the declared position");
            parameterRejected = true;
          }
          check(parameterRejected,
                "a host-applied function value checks its own parameters");
          let untypedRejected: boolean = false;
          try {
            let ignored: number = probe.applyUnknownResult(probe.makeUntyped);
            check(false, "the host applied an untyped-array result");
          } catch (value) {
            let e: Error = value as Error;
            check(e.code === "E8010",
                  "the untyped-array result renders E8010");
            check(e.message
                  === "return value 1 type mismatch: expected unknown",
                  "the host-function result boundary rejects the untyped"
                  + " presentation");
            untypedRejected = true;
          }
          check(untypedRejected,
                "an untyped array is rejected at the host-function result"
                + " boundary");
          check(probe.untypedArgument(probe.innerValue) === 1,
                "an untyped argument answers the closed kind status and an"
                + " undeclared argument count the protocol status");
          // H2/R11: a host-built Error that omits a required field renders the
          // missing field's own host cell arm, never an infrastructure fault.
          let brokenRejected: boolean = false;
          try {
            let broken: Error = probe.makeBrokenError();
            check(false, "a host-built Error without its message was admitted");
          } catch (value) {
            let e: Error = value as Error;
            check(e.code === "E8010",
                  "the missing required Error field renders E8010");
            check(e.message
                  === "return value 1 type mismatch: expected string",
                  "the missing required Error field renders its own declared"
                  + " position");
            brokenRejected = true;
          }
          check(brokenRejected,
                "a host-built Error missing a required field is rejected");
          // R11: a class atom outside the published grammar is the closed
          // bad-descriptor status, never a fault and never an accepted
          // identity.
          check(probe.classAtomStatuses() === 1,
                "the class-atom grammar answers the closed bad-descriptor"
                + " status");
          return null;
        }
        """;

    /**
     * The driver fixture's declared host surface: the zero-arity re-entry
     * exports of H6's export surface, so an embedding driver (not DEAL code)
     * invokes them and projects the carried Error through the R9 accessors.
     */
    private static final String DRIVER_CALLBACK_DECLARATION = """
        export function reenterRelay(): number;
        export function reenterDiscard(): number;
        export function reenterRetain(): number;
        export function relayRetained(): number;
        export function swallow(): number;
        export function hostText(): number;
        """;

    /**
     * The driver fixture: `thrower` is the recorded callback export (the host
     * re-enters it), it constructs and throws one Error at a known source
     * position, and every host export drives one crossing over it.
     */
    private static final String DRIVER_CALLBACK_APP = """
        import * as probe from "host/probe";

        export function thrower(): number {
          let held: Error = { code: "E_CALLBACK_DRIVER", message: "the driver callback threw" };
          if (held.code === "E_CALLBACK_NEVER") {
            return 0;
          }
          throw held;
        }

        export function main(): null {
          let ignored: number = probe.hostText();
          return null;
        }
        """;

    /** The declared surface of the callback-module fixture (H4). */
    private static final String CALLBACK_DECLARATION = """
        export function reenterRelay(carried: Error): number;
        export function reenterDiscard(carried: Error): number;
        export function reenterRetain(carried: Error): number;
        export function relayRetained(): number;
        """;

    /**
     * The callback fixture: `handler` is the recorded callback export (the host
     * drives it, DEAL never calls it), it throws one module-scope Error the
     * host must relay by identity, and the three host exports re-enter it.
     */
    private static final String CALLBACK_APP = """
        import * as probe from "host/probe";

        export function handler(carried: Error): number {
          if (carried.code === "E_CALLBACK_NEVER") {
            return 0;
          }
          carried.code = "E_CALLBACK_MUTATED";
          throw carried;
        }

        function check(condition: boolean, message: string): null {
          if (!condition) {
            throw { code: "TEST_FAIL", message: message };
          }
          return null;
        }

        export function main(): null {
          let shared: Error = { code: "E_CALLBACK", message: "the callback body threw" };
          let relayed: boolean = false;
          try {
            let ignored: number = probe.reenterRelay(shared);
            check(false, "the relayed callback failure returned a value");
          } catch (value) {
            let e: Error = value as Error;
            check(e === shared,
                  "the relayed callback Error is the identical object");
            check(e.code === "E_CALLBACK_MUTATED",
                  "the relayed callback keeps the mutated code");
            check(e.message === "the callback body threw",
                  "the relayed callback keeps its message");
            relayed = true;
          }
          check(relayed, "the host relayed the callback failure");
          let discarded: number = probe.reenterDiscard(shared);
          check(discarded === 7,
                "a discarded callback failure propagates nothing");
          let kept: number = probe.reenterRetain(shared);
          check(kept === 1, "the retained callback failure");
          let late: boolean = false;
          try {
            let ignored2: number = probe.relayRetained();
            check(false, "the retained failure relayed later returned a value");
          } catch (value) {
            let e: Error = value as Error;
            check(e === shared,
                  "the later relayed Error is the identical object");
            check(e.code === "E_CALLBACK_MUTATED", "the later relayed code");
            late = true;
          }
          check(late, "the retained Error relayed in a later crossing");
          return null;
        }
        """;

    /**
     * The host unit of the callback fixture (H4/H2): it re-enters the recorded
     * DEAL function through the emitted entry with H2's `deal_host_result`
     * shape, inspects the DEAL-origin failure record, relays it unchanged,
     * discards another token and relays a retained Error in a later crossing.
     */
    private static String callbackHostUnit(String entry) {
        return """
            #include "host/probe.h"

            #include <string.h>

            /* The module's host-driven re-entry entry (H4): default linkage under
             * a deterministic C identifier, so the linked host unit calls it. */
            extern deal_status %s(deal_host_result *out, const deal_value *args,
                                  size_t argc);

            static deal_handle retained = 0;

            static deal_host_result ok_number(double value) {
              deal_host_result result;
              result.failed = 0;
              result.failure.kind = DEAL_FAILURE_HOST_TEXT;
              result.failure.code = NULL;
              result.failure.message = NULL;
              result.failure.expected = NULL;
              result.failure.actual = NULL;
              result.failure.error = 0;
              if (deal_number(value, &result.value) != DEAL_OK) {
                result.failed = 1;
                result.failure.kind = DEAL_FAILURE_HOST_TEXT;
                result.failure.code = "E_HOST";
                result.failure.message = "observation construction";
              }
              return result;
            }

            static deal_host_result host_failure(const char *message) {
              deal_host_result result;
              result.failed = 1;
              result.failure.kind = DEAL_FAILURE_HOST_TEXT;
              result.failure.code = "E_HOST";
              result.failure.message = message;
              result.failure.expected = NULL;
              result.failure.actual = NULL;
              result.failure.error = 0;
              result.value.slot = 0;
              result.value.generation = 0;
              return result;
            }

            /* The DEAL-origin failure record of one re-entry (H2): failed, the
             * DEAL_ERROR channel, one live token, no host text, and the class
             * carrier of that very Error with the mutated code. */
            static deal_status inspect(const deal_host_result *record) {
              if (record->failed == 0) {
                return DEAL_STATUS_KIND;
              }
              if (record->failure.kind != DEAL_FAILURE_DEAL_ERROR) {
                return DEAL_STATUS_KIND;
              }
              if (record->failure.error == 0) {
                return DEAL_STATUS_PROTOCOL;
              }
              if (record->failure.code != NULL || record->failure.message != NULL) {
                return DEAL_STATUS_PROTOCOL;
              }
              deal_value_kind kind = DEAL_KIND_NULL;
              if (deal_kind_of(record->value, &kind) != DEAL_OK
                  || kind != DEAL_KIND_CLASS) {
                return DEAL_STATUS_KIND;
              }
              const char *identity = NULL;
              if (deal_class_identity(record->value, &identity) != DEAL_OK
                  || identity == NULL || strcmp(identity, "Error") != 0) {
                return DEAL_STATUS_KIND;
              }
              deal_value code;
              const void *bytes = NULL;
              size_t length = 0;
              if (deal_field_get(record->value, "code", &code) != DEAL_OK
                  || deal_as_string(code, &bytes, &length) != DEAL_OK
                  || length != strlen("E_CALLBACK_MUTATED")
                  || memcmp(bytes, "E_CALLBACK_MUTATED", length) != 0) {
                return DEAL_STATUS_KIND;
              }
              return DEAL_OK;
            }

            static deal_status reenter(const deal_value *args, size_t argc,
                                       deal_host_result *record) {
              if (argc != 1u) {
                return DEAL_STATUS_PROTOCOL;
              }
              return %s(record, args, 1u);
            }

            static deal_host_result relay_impl(const deal_value *args, size_t argc) {
              deal_host_result record;
              if (reenter(args, argc, &record) != DEAL_OK) {
                return host_failure("the re-entry status");
              }
              if (inspect(&record) != DEAL_OK) {
                return host_failure("the re-entry failure record");
              }
              /* Relay the record unchanged: the runtime consumes the token and
               * re-admits the identical Error (H2). */
              return record;
            }

            static deal_host_result discard_impl(const deal_value *args, size_t argc) {
              deal_host_result record;
              if (reenter(args, argc, &record) != DEAL_OK) {
                return host_failure("the re-entry status");
              }
              if (inspect(&record) != DEAL_OK) {
                return host_failure("the re-entry failure record");
              }
              if (deal_failure_discard(record.failure.error) != DEAL_OK) {
                return host_failure("the discard status");
              }
              return ok_number(7.0);
            }

            static deal_host_result retain_impl(const deal_value *args, size_t argc) {
              deal_host_result record;
              if (reenter(args, argc, &record) != DEAL_OK) {
                return host_failure("the re-entry status");
              }
              if (inspect(&record) != DEAL_OK) {
                return host_failure("the re-entry failure record");
              }
              if (deal_retain(record.value, &retained) != DEAL_OK
                  || retained == 0) {
                return host_failure("the Error retain");
              }
              if (deal_failure_discard(record.failure.error) != DEAL_OK) {
                return host_failure("the discard status");
              }
              return ok_number(1.0);
            }

            static deal_host_result relay_later_impl(const deal_value *args,
                                                     size_t argc) {
              (void)args;
              (void)argc;
              deal_host_failure failure;
              deal_value carrier;
              if (retained == 0) {
                return host_failure("no retained Error");
              }
              if (deal_error_from_handle(retained, &failure) != DEAL_OK) {
                return host_failure("the retained failure token");
              }
              if (deal_handle_value(retained, "Error", &carrier) != DEAL_OK) {
                return host_failure("the retained Error carrier");
              }
              deal_host_result record;
              memset(&record, 0, sizeof record);
              record.failed = 1;
              record.value = carrier;
              record.failure = failure;
              return record;
            }

            static const deal_host_export exports[] = {
                {"reenterRelay", "(Error)->number", DEAL_HOST_EXPORT_FUNCTION, 0,
                 relay_impl, NULL, NULL},
                {"reenterDiscard", "(Error)->number", DEAL_HOST_EXPORT_FUNCTION,
                 0, discard_impl, NULL, NULL},
                {"reenterRetain", "(Error)->number", DEAL_HOST_EXPORT_FUNCTION,
                 0, retain_impl, NULL, NULL},
                {"relayRetained", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0,
                 relay_later_impl, NULL, NULL},
            };

            const deal_host_module deal_host_module_probe = {
                DEAL_HOST_SPECIFIER_PROBE, exports,
                sizeof exports / sizeof exports[0], NULL, 0u, NULL};
            """.formatted(entry, entry);
    }

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("=== LLVM Synchronous Host ABI Tests (ISSUE-0912) ===\n");
        Path scratch = NativeCompiler.executableScratch("llvm-host-abi");
        try {
            Path project = project(scratch);
            Path output = compile(project);
            testGeneratedHeader(output);
            testStrictHeaderConsumer(scratch, output);
            testEmittedDeclaredRecords(output);
            testExecutedCrossings(scratch, project, output);
            testVoidResultArm(scratch, project);
            testAbsentReturnArm(scratch, project);
            testAbsentFieldArm(scratch, project);
            testFunctionValueArms(scratch);
            testHostParameterArm(scratch);
            testHostLoadArms(scratch, project, output);
            testFieldMaterializationArms(scratch, project);
            testRegistrationProtocol(scratch, project);
            testExportInvokeDriver(scratch, project, output);
            testValueSurfaceAndHandles(scratch, output);
            testArrayCarrierResourceFault(scratch, output);
            testFunctionSlotViewStore(scratch);
            testCallbackReentry(scratch);
            testCallbackFailureProjection(scratch);
            testFailedInitializationGate(scratch);
        } finally {
            deleteRecursively(scratch);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== LLVM Synchronous Host ABI Tests Passed ===");
    }

    // =========================================================================
    // The published artifacts
    // =========================================================================

    /** One scratch project with one imported host declaration module. */
    private static Path project(Path scratch) throws IOException {
        Path root = scratch.resolve("project");
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("bindings"));
        Files.writeString(root.resolve("deal.json"), DEAL_JSON,
            StandardCharsets.UTF_8);
        Files.writeString(root.resolve("bindings/probe.d.deal"), DECLARATION,
            StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/main.deal"), APP,
            StandardCharsets.UTF_8);
        return root;
    }

    /** One production compile of the fixture; answers its publication root. */
    private static Path compile(Path project) throws IOException {
        Path output = project.resolve("out");
        deleteRecursively(output);
        int status = deal.Main.run(new String[] {"compile",
            project.resolve("src/main.deal").toString(),
            "--backend", "llvm", "--output", output.toString()});
        checkEq(0, status, "the production compile publishes the project");
        return output;
    }

    /** The generated declaration header, read as published bytes. */
    private static String header(Path output) throws IOException {
        return Files.readString(output.resolve("host/probe.h"),
            StandardCharsets.UTF_8);
    }

    /**
     * Criterion 1: the header declares exactly the H1 surface — the specifier
     * macro, the registration extern, the declared class metadata and the inline
     * typed wrappers over the H2 accessors — and includes only the published
     * declaration header.
     */
    private static void testGeneratedHeader(Path output) throws IOException {
        System.out.println("-- the generated declaration header (H1/H2) --");
        Path path = output.resolve("host/probe.h");
        check(Files.isRegularFile(path),
            "the compile publishes one header per imported host declaration"
                + " module at <host module path>.h");
        if (!Files.isRegularFile(path)) {
            return;
        }
        String text = header(output);
        check(text.contains("#include \"deal/native/deal_rt.h\""),
            "the generated header includes only the published declaration"
                + " header");
        checkEq(1, count(text, "#include"),
            "the generated header includes exactly one header");
        check(text.contains("#define DEAL_HOST_SPECIFIER_PROBE \"host/probe\""),
            "the generated header declares the raw specifier macro");
        check(text.contains("#define DEAL_HOST_MODULE_PATH_PROBE"
                + " \"$external/host/probe\""),
            "the generated header declares the declaration module's module-id"
                + " path beside the raw specifier");
        check(text.contains("extern const deal_host_module"
                + " deal_host_module_probe;"),
            "the generated header declares the registration symbol");
        check(text.contains("DEAL_HOST_CLASS_ATOM_PROBE_CONFIG"
                + " \"@$external/host/probe/Config\""),
            "the generated header declares the class's canonical identity");
        check(text.contains("extern const deal_host_field"
                + " deal_host_fields_probe_Config[];")
                && text.contains("extern const deal_host_class"
                + " deal_host_class_probe_Config;"),
            "the generated header declares the class metadata records");
        check(text.contains("static inline deal_status"
                + " deal_host_probe_Config_new(deal_value *out)"),
            "the generated header declares the class constructor wrapper");
        check(text.contains("static inline deal_status"
                + " deal_host_probe_Config_value_set(deal_value object,"
                + " deal_value value)")
                && text.contains("return deal_field_set(object, \"value\","
                + " value);"),
            "the generated header declares the typed field write wrapper over"
                + " the H2 accessor");
        check(text.contains("static inline deal_status"
                + " deal_host_probe_Config_value_get(deal_value object,"
                + " deal_value *out)"),
            "the generated header declares the typed field read wrapper");
        check(text.contains("extern const deal_host_export"
                + " deal_host_exports_probe[];"),
            "the generated header declares the declared-export metadata array");
    }

    /**
     * Criterion 1: a standalone strict-C11 consumer that includes only the
     * generated header compiles under the pinned flag list and can name every
     * declared record and wrapper.
     */
    private static void testStrictHeaderConsumer(Path scratch, Path output)
            throws IOException {
        System.out.println("-- the standalone strict-C11 header consumer --");
        Path source = scratch.resolve("header_consumer.c");
        Files.writeString(source, """
            #include "host/probe.h"

            /* The declared surface is nameable and the wrappers have the H2
             * status shape. */
            static deal_status consumer(deal_value object) {
              deal_value value;
              deal_status status = deal_host_probe_Config_new(&object);
              if (status != DEAL_OK) {
                return status;
              }
              status = deal_number(42.0, &value);
              if (status != DEAL_OK) {
                return status;
              }
              status = deal_host_probe_Config_value_set(object, value);
              if (status != DEAL_OK) {
                return status;
              }
              return deal_host_probe_Config_value_get(object, &value);
            }

            /* The declared metadata records and the registration symbol. */
            static const deal_host_module *module = &deal_host_module_probe;
            static const deal_host_export *exports = deal_host_exports_probe;
            static const deal_host_class *classes = deal_host_classes_probe;
            static const deal_host_field *fields =
                deal_host_fields_probe_Config;

            int main(void) {
              deal_value probe = {0u, 0u};
              int total = (int)(consumer(probe) == DEAL_OK);
              total += module != 0 ? 1 : 0;
              total += exports != 0 ? 1 : 0;
              total += classes != 0 ? 1 : 0;
              total += fields != 0 ? 1 : 0;
              total += DEAL_HOST_EXPORT_COUNT_PROBE;
              total += DEAL_HOST_CLASS_COUNT_PROBE;
              total += DEAL_HOST_FIELD_COUNT_PROBE_CONFIG;
              return total == 0 ? 1 : 0;
            }
            """, StandardCharsets.UTF_8);
        for (int level : LEVELS) {
            // The consumer is compiled, never linked: a link would need the
            // host unit and would prove the registration rather than the
            // declaration surface it consumes.
            NativeCompiler.Result compiled = NativeCompiler.compileOnly(output,
                level, source, scratch.resolve("header_consumer_o" + level
                    + ".o"));
            check(compiled.succeeded(), "-O" + level + " the standalone consumer"
                + " of the generated header compiles under the pinned flags: "
                + compiled.describe());
            checkEq("", compiled.stderr(), "-O" + level + " the consumer"
                + " compiles without diagnostics");
        }
    }

    /**
     * Criterion 2: the emitted module carries one declared-module record per
     * imported host declaration module, the origin-carrying load call inside the
     * unit's initialization, the import module record with its host-marked export
     * entries and the emitted-only module layout.
     */
    private static void testEmittedDeclaredRecords(Path output)
            throws IOException {
        System.out.println("-- the emitted declared records and load call"
            + " (H1/H7) --");
        String module = Files.readString(output.resolve("main.ll"),
            StandardCharsets.UTF_8);
        check(module.contains("@deal.host.0.declared = internal constant"
                + " %struct.deal_host_declared_module {"),
            "the emitted module declares one declared-module record");
        check(module.contains("@deal.host.0.declared")
                && module.contains("call %struct.deal_outcome"
                    + " @deal_host_load(%struct.deal_host_declared_module*"
                    + " @deal.host.0.declared, %struct.deal_origin*"),
            "the unit's initialization calls the load entry with the"
                + " importing statement's origin");
        // The declared-module record's identity members are the raw specifier
        // and the declaration module's own module-id path (ISSUE-0912's load
        // contract), never the same text twice.
        Matcher declarator = Pattern.compile(
            "(?m)^@deal\\.host\\.0\\.declared = internal constant"
                + " %struct\\.deal_host_declared_module \\{ i8\\* getelementptr"
                + " \\(\\[[0-9]+ x i8\\], \\[[0-9]+ x i8\\]\\*"
                + " (@deal\\.str\\.[0-9]+).*?i8\\* getelementptr"
                + " \\(\\[[0-9]+ x i8\\], \\[[0-9]+ x i8\\]\\*"
                + " (@deal\\.str\\.[0-9]+)").matcher(module);
        boolean declaredFound = declarator.find();
        check(declaredFound,
            "the declared-module record carries two interned identity members");
        String declaredSpecifier = declaredFound ? declarator.group(1) : "";
        String declaredModulePath = declaredFound ? declarator.group(2) : "";
        checkEq("host/probe", interned(module, declaredSpecifier),
            "the declared record's first identity member is the raw specifier");
        checkEq("$external/host/probe", interned(module, declaredModulePath),
            "the declared record's second identity member is the declaration"
                + " module's module-id path");
        // The import module record answers the raw specifier the driver and the
        // generated registry pair (H6).
        Matcher moduleRecord = Pattern.compile(
            "(?m)^@deal\\.host\\.0\\.module = internal global"
                + " %struct\\.deal_module \\{ i8\\* getelementptr"
                + " \\(\\[[0-9]+ x i8\\], \\[[0-9]+ x i8\\]\\*"
                + " (@deal\\.str\\.[0-9]+)").matcher(module);
        boolean moduleFound = moduleRecord.find();
        check(moduleFound, "the import module record is emitted");
        checkEq(declaredSpecifier, moduleFound ? moduleRecord.group(1) : "",
            "the import module record answers the raw specifier");
        check(module.contains("@deal.host.0.entries = internal global")
                && module.contains("i32 1, %struct.deal_fn* null }"),
            "the import's export entries carry the host marker");
        check(module.contains("@deal.host.0.module = internal global"
                + " %struct.deal_module {"),
            "the import owns one module record built from the binding");
        check(module.contains("call void @deal_host_class_access_register("),
            "the emitted class plans register the host access surface");
        check(module.contains("@deal.class.0.create")
                && module.contains("@deal.class.0.store"),
            "the emitted class plans carry the host allocation and store seams");
        check(!module.contains("call void @deal_host_invoke_sync")
                || module.contains("sret(%struct.deal_outcome_data)"),
            "the crossing entry is called through its published sret"
                + " convention");
        // The runtime defines no module layout: the opacity probe compiles
        // because the published headers never declare a member.
        checkEq(1, count(module, "@deal.host.0.declared = internal constant"),
            "the declared-module record is emitted once");
        // The declared export order is the declaration order.
        int sumPosition = module.indexOf("@deal.str.", module.indexOf(
            "@deal.host.0.entries"));
        check(sumPosition > 0, "the export entries carry interned names");
    }

    // =========================================================================
    // The executed crossings
    // =========================================================================

    /**
     * Criterion 4: the product executes the declared crossings at both levels —
     * scalar parameters and results, a host-built class instance, alias identity
     * inside a host-built typed array and a host-authored carried failure.
     */
    private static void testExecutedCrossings(Path scratch, Path project,
                                              Path output) throws Exception {
        System.out.println("-- the executed synchronous crossings"
            + " (both levels) --");
        for (int level : LEVELS) {
            NativeCompiler.Result run = build(project, level,
                REPO.resolve("test/native/probe.c"), scratch);
            checkEq(0, run.exitCode(), "-O" + level + " the registered host"
                + " product executes every crossing: " + run.describe());
            checkEq("", run.stdout(), "-O" + level + " the successful product"
                + " writes no terminal output: " + run.describe());
            checkEq("", run.stderr(), "-O" + level + " the successful product"
                + " writes no diagnostics: " + run.describe());
            // The same crossings under one full collection per safepoint: the
            // timing-only switch (M7) changes no observable behavior.
            NativeCompiler.Result stressed = NativeCompiler.execute(
                scratch.resolve("product-" + level + "-probe.c"), scratch,
                Map.of("DEAL_GC_STRESS", "1"));
            checkEq(0, stressed.exitCode(), "-O" + level + " the crossings"
                + " survive a full collection at every safepoint: "
                + stressed.describe());
            checkEq(run.stdout(), stressed.stdout(), "-O" + level + " the"
                + " crossings are byte-identical under DEAL_GC_STRESS");
            checkEq(run.stderr(), stressed.stderr(), "-O" + level + " the"
                + " stressed crossings write no diagnostics");
        }
        // The generated header is a published input of the same transaction.
        check(Files.isRegularFile(output.resolve("host/probe.h")),
            "the header survives the publish transaction");
    }

    /**
     * The V1.5 allocation surface of the issue: the declared result-only `void`
     * position normalizes an admitted `nothing` to undefined and rejects a
     * number at that position with the return boundary's own arm.
     */
    private static void testVoidResultArm(Path scratch, Path project)
            throws Exception {
        System.out.println("-- the void sync result normalization (V1.5) --");
        Path unit = Files.createDirectories(scratch.resolve("voidresult"))
            .resolve("probe.c");
        Files.writeString(unit, "#define DEAL_HOST_PROBE_CASE 11\n"
            + Files.readString(REPO.resolve("test/native/probe.c"),
                StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        for (int level : LEVELS) {
            NativeCompiler.Result run = build(project, level, unit, scratch);
            checkEq(1, run.exitCode(), "-O" + level + " a number at the"
                + " declared void position is rejected: " + run.describe());
            check(run.stdout().contains("DEAL_ERROR_CODE: E8010"),
                "-O" + level + " the void rejection renders E8010: "
                    + run.describe());
            check(run.stdout().contains("\"message\":\"return value 1 type"
                    + " mismatch: expected undefined\""),
                "-O" + level + " the void rejection names the undefined"
                    + " result position: " + run.describe());
            check(run.stdout().contains("\"expected\":\"void\"")
                    && run.stdout().contains("\"actual\":\"number\""),
                "-O" + level + " the void rejection projects void against"
                    + " number: " + run.describe());
            checkEq("", run.stderr(), "-O" + level + " the void rejection is"
                + " the typed-boundary arm, not an infrastructure fault: "
                + run.describe());
        }
    }

    /**
     * The V1.5 admission rule that the absence marker is presence metadata and
     * never data (H2, R2, R11): a host carrier built with {@code deal_absent()}
     * returned at a declared {@code unknown} position renders the declared
     * boundary arm instead of being admitted as {@code undefined}. The fixture
     * answers the marker from {@code echo}'s {@code unknown} result, so the
     * product stops at that crossing after every earlier check succeeded.
     */
    private static void testAbsentReturnArm(Path scratch, Path project)
            throws Exception {
        System.out.println("-- the absent carrier at a declared unknown position"
            + " (V1.5/H2) --");
        Path unit = Files.createDirectories(scratch.resolve("absentreturn"))
            .resolve("probe.c");
        Files.writeString(unit, "#define DEAL_HOST_PROBE_CASE 12\n"
            + Files.readString(REPO.resolve("test/native/probe.c"),
                StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        for (int level : LEVELS) {
            NativeCompiler.Result run = build(project, level, unit, scratch);
            checkEq(1, run.exitCode(), "-O" + level + " an absent carrier at a"
                + " declared unknown position is rejected: " + run.describe());
            check(run.stdout().contains("DEAL_ERROR_CODE: E8010"),
                "-O" + level + " the absent carrier renders E8010: "
                    + run.describe());
            check(run.stdout().contains("\"message\":\"return value 1 type"
                    + " mismatch: expected unknown\""),
                "-O" + level + " the absent carrier renders the declared"
                    + " position's own kind arm: " + run.describe());
            check(run.stdout().contains("\"expected\":\"unknown\"")
                    && run.stdout().contains("\"actual\":\"nil\""),
                "-O" + level + " the absent carrier projects the declared"
                    + " unknown position against the absence marker: "
                    + run.describe());
            checkEq("", run.stderr(), "-O" + level + " the absent-carrier"
                + " rejection is the typed arm, not an infrastructure fault: "
                + run.describe());
        }
    }

    /**
     * The absence marker inside a host-built class graph (H2/R11): a declared
     * field whose carrier is {@code deal_absent()} is rejected at that field's
     * own slot with the host cell composite arm, so the marker never
     * materializes as an {@code undefined} field value. The field is the
     * canonical both-member nullish slot ({@code ?!string}), which admits
     * {@code undefined}: admitting the marker there would be the defect.
     */
    private static void testAbsentFieldArm(Path scratch, Path project)
            throws Exception {
        System.out.println("-- the absent carrier in a host-built class field"
            + " (H2/R11) --");
        Path unit = Files.createDirectories(scratch.resolve("absentfield"))
            .resolve("probe.c");
        Files.writeString(unit, "#define DEAL_HOST_PROBE_CASE 13\n"
            + Files.readString(REPO.resolve("test/native/probe.c"),
                StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        for (int level : LEVELS) {
            NativeCompiler.Result run = build(project, level, unit, scratch);
            checkEq(1, run.exitCode(), "-O" + level + " an absent host class"
                + " field fails the crossing: " + run.describe());
            check(run.stdout().contains("DEAL_ERROR_CODE: E8010"),
                "-O" + level + " the absent field renders E8010: "
                    + run.describe());
            check(run.stdout().contains("\"message\":\"return value 1 type"
                    + " mismatch: expected string\""),
                "-O" + level + " the absent field renders the field's own"
                    + " declared slot arm: " + run.describe());
            check(run.stdout().contains("\"expected\":\"?!string\"")
                    && run.stdout().contains("\"actual\":\"nil\""),
                "-O" + level + " the absent field projects its declared"
                    + " both-member nullish slot against the absence marker: "
                    + run.describe());
            checkEq("", run.stderr(), "-O" + level + " the absent-field"
                + " rejection is the host cell arm, not an infrastructure"
                + " fault: " + run.describe());
        }
    }

    /**
     * The function-typed value positions of H4 and the remaining closed host
     * arms: each case compiles its own production project whose {@code main}
     * reaches one crossing that fails uncaught, so the terminal projection
     * publishes the arm's exact tuple (code, message, declared expected and
     * carried actual) at both optimization levels.
     */
    private static void testFunctionValueArms(Path scratch) throws Exception {
        System.out.println("-- the function-value and partial-Error arms (H4/H2)"
            + " --");
        Map<String, String[]> cases = new LinkedHashMap<>();
        // The declared result of the export is the function descriptor itself, so
        // a number at that position is the return boundary's kind arm.
        cases.put("kind", new String[] {
            "let bad: () => number = probe.wrongInnerKind();",
            "return value 1 type mismatch: expected function",
            "()->number", "number"});
        // A function value whose own signature differs from the declared
        // position is the carried-signature arm. The carrier is a DEAL function
        // value, so the shared host-carrier projection spells it `table` (the
        // typed boundary keeps `function`; H2's boundary-crossing verification
        // and the corpus's function-carrier host cell arms).
        cases.put("signature", new String[] {
            "let bad: () => number = probe.wrongInnerSignature();",
            "return value 1 type mismatch: function signature mismatch:"
                + " expected ()->number, got (number)->number",
            "()->number", "table"});
        // A host that applies a host-declared export value itself runs that
        // value's declared parameter boundary.
        cases.put("parameter", new String[] {
            "let count: number = probe.applyOne(probe.innerValue);",
            "parameter 1 type mismatch: expected number",
            "number", "string"});
        // A host-built Error without its required `message` renders the missing
        // field's own declared position, never an infrastructure fault.
        cases.put("error", new String[] {
            "let broken: Error = probe.makeBrokenError();",
            "return value 1 type mismatch: expected string",
            "string", "nil"});
        for (Map.Entry<String, String[]> arm : cases.entrySet()) {
            String[] facts = arm.getValue();
            Path root = scratch.resolve("arm-" + arm.getKey());
            Files.createDirectories(root.resolve("src"));
            Files.createDirectories(root.resolve("bindings"));
            Files.writeString(root.resolve("deal.json"), DEAL_JSON,
                StandardCharsets.UTF_8);
            Files.writeString(root.resolve("bindings/probe.d.deal"),
                DECLARATION, StandardCharsets.UTF_8);
            Files.writeString(root.resolve("src/main.deal"), """
                import * as probe from "host/probe";

                export function main(): null {
                  """ + facts[0] + """

                  return null;
                }
                """, StandardCharsets.UTF_8);
            compile(root);
            for (int level : LEVELS) {
                NativeCompiler.Result run = build(root, level,
                    REPO.resolve("test/native/probe.c"), scratch);
                checkEq(1, run.exitCode(), "-O" + level + " the "
                    + arm.getKey() + " arm stops the product: "
                    + run.describe());
                check(run.stdout().contains("DEAL_ERROR_CODE: E8010"),
                    "-O" + level + " the " + arm.getKey()
                        + " arm renders E8010: " + run.describe());
                check(run.stdout().contains("\"message\":\"" + facts[1]
                        + "\""),
                    "-O" + level + " the " + arm.getKey()
                        + " arm renders its own template: " + run.describe());
                check(run.stdout().contains("\"expected\":\"" + facts[2]
                        + "\"") && run.stdout().contains("\"actual\":\""
                        + facts[3] + "\""),
                    "-O" + level + " the " + arm.getKey()
                        + " arm projects its declared expected and carried"
                        + " actual: " + run.describe());
                checkEq("", run.stderr(), "-O" + level + " the "
                    + arm.getKey() + " arm is a typed arm, not an"
                    + " infrastructure fault: " + run.describe());
            }
        }
    }

    /**
     * The declared host parameter cells (H2 {@code HostCallSync}, H4): every
     * recorded parameter position is checked at the {@code HOST_PARAMETER}
     * boundary with its one-based index at the crossing's own origin, so an
     * argument whose refinement went stale between the read and the call
     * renders the shared composite arm there — the declared cell descriptor as
     * expected and the shared host-carrier token as actual — and never the
     * typed-boundary arm at the argument expression, and never a value the
     * declared position did not admit. The scalar and the function-typed
     * position both project the same carrier token.
     */
    private static void testHostParameterArm(Path scratch) throws Exception {
        System.out.println("-- the executed host parameter cells (H2/H4) --");
        Map<String, String[]> cases = new LinkedHashMap<>();
        // A cleared `number | null` refinement read at `probe.sum`'s first
        // declared position: the parameter cell owns the projection.
        cases.put("scalar", new String[] {
            "export function main(): null {\n"
                + "  let value: number | null = 1;\n"
                + "  let clear: () => void = function(): void { value = null; };\n"
                + "  if (value !== null) {\n"
                + "    clear();\n"
                + "    probe.sum(value, 2);\n"
                + "  }\n"
                + "  return null;\n"
                + "}\n",
            "parameter 1 type mismatch: expected number",
            "number", "8", "5"});
        // The same shape at a declared function-typed position: the cleared
        // refinement is the absence of the carrier the position declares.
        cases.put("function", new String[] {
            "export function main(): null {\n"
                + "  let callback: ((x: number) => number) | null ="
                + " function(x: number): number { return x; };\n"
                + "  let clear: () => void = function(): void { callback = null; };\n"
                + "  if (callback !== null) {\n"
                + "    clear();\n"
                + "    probe.applyOne(callback);\n"
                + "  }\n"
                + "  return null;\n"
                + "}\n",
            "parameter 1 type mismatch: expected function",
            "(number)->number", "8", "5"});
        for (Map.Entry<String, String[]> cell : cases.entrySet()) {
            String[] facts = cell.getValue();
            Path root = scratch.resolve("parameter-" + cell.getKey());
            Files.createDirectories(root.resolve("src"));
            Files.createDirectories(root.resolve("bindings"));
            Files.writeString(root.resolve("deal.json"), DEAL_JSON,
                StandardCharsets.UTF_8);
            Files.writeString(root.resolve("bindings/probe.d.deal"),
                DECLARATION, StandardCharsets.UTF_8);
            Files.writeString(root.resolve("src/main.deal"),
                "import * as probe from \"host/probe\";\n\n" + facts[0],
                StandardCharsets.UTF_8);
            compile(root);
            for (int level : LEVELS) {
                NativeCompiler.Result run = build(root, level,
                    REPO.resolve("test/native/probe.c"), scratch);
                checkEq(1, run.exitCode(), "-O" + level + " the "
                    + cell.getKey() + " parameter cell stops the crossing: "
                    + run.describe());
                check(run.stdout().contains("DEAL_ERROR_CODE: E8010"),
                    "-O" + level + " the " + cell.getKey()
                        + " parameter cell renders E8010: " + run.describe());
                check(run.stdout().contains("\"message\":\"" + facts[1]
                        + "\""),
                    "-O" + level + " the " + cell.getKey()
                        + " cell renders the composite parameter reason: "
                        + run.describe());
                check(run.stdout().contains("\"expected\":\"" + facts[2]
                        + "\"") && run.stdout().contains("\"actual\":\"table\""),
                    "-O" + level + " the " + cell.getKey()
                        + " cell projects the declared cell descriptor and the"
                        + " shared host-carrier token: " + run.describe());
                // The arm's origin is the crossing's own statement, never the
                // argument expression the stale read came from.
                check(run.stdout().contains("\"line\":" + facts[3]
                        + ",\"column\":" + facts[4] + ","),
                    "-O" + level + " the " + cell.getKey()
                        + " cell renders at the call origin: "
                        + run.describe());
                checkEq("", run.stderr(), "-O" + level + " the "
                    + cell.getKey() + " parameter cell is a typed arm, not an"
                    + " infrastructure fault: " + run.describe());
            }
        }
    }

    /**
     * Criterion 3: the closed H7 arms, each reached by an executed defective
     * host unit at the importing statement's origin.
     */
    private static void testHostLoadArms(Path scratch, Path project,
                                         Path output) throws Exception {
        System.out.println("-- the executed host-load arms (H7) --");
        // An omitted unit keeps no registry entry: the pinned absent arm.
        NativeCompiler.Result omitted = build(project, 0, null, scratch);
        checkEq(1, omitted.exitCode(), "an omitted host unit fails at module"
            + " initialization: " + omitted.describe());
        check(omitted.stdout().contains("DEAL_ERROR_CODE: E8011"),
            "the omitted unit renders E8011: " + omitted.describe());
        check(omitted.stdout().contains(
                "failed to load host module 'host/probe':"
                    + " no registered native implementation"),
            "the absent arm carries the pinned native reason text: "
                + omitted.describe());

        Map<String, String> expectations = new LinkedHashMap<>();
        expectations.put("1", "missing host export 'sum' in module"
            + " 'host/probe'");
        expectations.put("3", "host class 'Config' in module 'host/probe' has"
            + " invalid fields metadata");
        expectations.put("4", "host class 'Config' in module 'host/probe' has"
            + " identity mismatch: expected @$external/host/probe/Config, got"
            + " @$external/host/other/Config");
        for (Map.Entry<String, String> expectation : expectations.entrySet()) {
            Path unit = Files.createDirectories(
                scratch.resolve("case" + expectation.getKey())).resolve("probe.c");
            Files.writeString(unit, "#define DEAL_HOST_PROBE_CASE "
                + expectation.getKey() + "\n"
                + Files.readString(REPO.resolve("test/native/probe.c"),
                    StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            NativeCompiler.Result run = build(project, 0, unit, scratch);
            checkEq(1, run.exitCode(), "the defective unit (case "
                + expectation.getKey() + ") fails at initialization: "
                + run.describe());
            check(run.stdout().contains("DEAL_ERROR_CODE: E8011"),
                "the defective unit renders E8011: " + run.describe());
            check(run.stdout().contains(expectation.getValue()),
                "the defective unit (" + expectation.getKey() + ") renders the"
                    + " arm's own message: " + run.describe());
        }

        // A descriptor mismatch: the registered descriptor differs from the
        // declaration's canonical text.
        Path mismatch = Files.createDirectories(
            scratch.resolve("mismatch")).resolve("probe.c");
        Files.writeString(mismatch, Files.readString(
                REPO.resolve("test/native/probe.c"), StandardCharsets.UTF_8)
                .replace("\"(number,number)->number\"", "\"(number)->number\""),
            StandardCharsets.UTF_8);
        NativeCompiler.Result signature = build(project, 0, mismatch, scratch);
        checkEq(1, signature.exitCode(), "the signature mismatch fails at"
            + " initialization: " + signature.describe());
        check(signature.stdout().contains("host export 'sum' in module"
                + " 'host/probe' has signature mismatch"),
            "the signature arm renders the declared and registered"
                + " descriptors: " + signature.describe());

        // An extra export is ignored, so the same product still executes.
        Path extra = Files.createDirectories(
            scratch.resolve("extra")).resolve("probe.c");
        Files.writeString(extra, "#define DEAL_HOST_PROBE_CASE 2\n"
            + Files.readString(REPO.resolve("test/native/probe.c"),
                StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        NativeCompiler.Result ignored = build(project, 0, extra, scratch);
        checkEq(0, ignored.exitCode(), "an extra registered export is ignored: "
            + ignored.describe());
    }

    /**
     * Criterion 4 and H2/R11 materialization: a host-built class instance's
     * present field value is checked against its declared descriptor before it
     * reaches typed storage, so a required present-undefined and a wrong-kind
     * field select the crossing's host cell arm and neither becomes data.
     */
    private static void testFieldMaterializationArms(Path scratch,
                                                     Path project)
            throws Exception {
        System.out.println("-- the class-field materialization arms (H2/R11) --");
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("5", "a host-built class with a present undefined in its"
            + " required number field fails the crossing");
        cases.put("6", "a host-built class with a wrong-kind value in its"
            + " required number field fails the crossing");
        cases.put("10", "a host-built class with no required field member"
            + " fails the crossing");
        for (Map.Entry<String, String> expectation : cases.entrySet()) {
            Path unit = Files.createDirectories(
                scratch.resolve("field" + expectation.getKey()))
                .resolve("probe.c");
            Files.writeString(unit, "#define DEAL_HOST_PROBE_CASE "
                + expectation.getKey() + "\n"
                + Files.readString(REPO.resolve("test/native/probe.c"),
                    StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            for (int level : LEVELS) {
                NativeCompiler.Result run = build(project, level, unit, scratch);
                checkEq(1, run.exitCode(), "-O" + level + " "
                    + expectation.getValue() + ": " + run.describe());
                check(run.stdout().contains("DEAL_ERROR_CODE: E8010"),
                    "-O" + level + " the class-field rejection renders E8010:"
                        + " " + run.describe());
                check(run.stdout().contains(
                        "return value 1 type mismatch: expected number"),
                    "-O" + level + " the field's own descriptor-check reason is"
                        + " the host cell arm's inner reason: " + run.describe());
                checkEq("", run.stderr(), "-O" + level + " the rejected field"
                    + " reports no infrastructure fault: " + run.describe());
            }
        }
    }

    /**
     * Criterion 3 and H2's record/linkage rules: an ambiguous or
     * marker-inconsistent registration, and a registration record whose own C
     * shape is inconsistent (no module specifier, a count with no array, a
     * record specifier disagreeing with its registry key), are the
     * deterministic one-line protocol failure and exit 2, never a silently
     * accepted first match and never an invented class-metadata arm.
     */
    private static void testRegistrationProtocol(Path scratch, Path project)
            throws Exception {
        System.out.println("-- the registration-record protocol checks (H2) --");
        for (String probeCase : List.of("7", "8", "9", "14", "15", "16")) {
            Path unit = Files.createDirectories(scratch.resolve("protocol"
                + probeCase)).resolve("probe.c");
            Files.writeString(unit, "#define DEAL_HOST_PROBE_CASE " + probeCase
                + "\n" + Files.readString(REPO.resolve("test/native/probe.c"),
                    StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            for (int level : LEVELS) {
                NativeCompiler.Result run = build(project, level, unit, scratch);
                checkEq(2, run.exitCode(), "-O" + level + " the inconsistent"
                    + " registration (case " + probeCase + ") is the protocol"
                    + " failure: " + run.describe());
                checkEq("", run.stdout(), "-O" + level + " the protocol failure"
                    + " admits nothing on stdout (case " + probeCase + ")");
                check(run.stderr().startsWith("DEAL_HOST_PROTOCOL: "),
                    "-O" + level + " the protocol failure is the deterministic"
                        + " stderr line (case " + probeCase + "): "
                        + run.describe());
                checkEq(1, count(run.stderr(), "\n"), "-O" + level + " the"
                    + " protocol failure writes exactly one stderr line (case "
                    + probeCase + ")");
            }
        }
    }

    /**
     * H6 {@code ExportInvoke}: the export surface runs a host export at its
     * declared result position, so a non-void host export is callable through
     * {@code deal_export_lookup}/{@code deal_export_invoke} in a driver-shape
     * build and the declared return boundary admits its result.
     */
    private static void testExportInvokeDriver(Path scratch, Path project,
                                               Path output) throws Exception {
        System.out.println("-- the export surface's host invocation (H6) --");
        String driver = """
            #include "deal/native/entry.h"
            #include "deal/native/platform.h"

            #include <string.h>

            static int report(deal_outcome outcome) {
              const char *code = NULL;
              const char *message = NULL;
              if (!deal_error_code(outcome.error, &code) || code == NULL) {
                code = "";
              }
              if (!deal_error_message(outcome.error, &message)
                  || message == NULL) {
                message = "";
              }
              deal_platform_write_stderr("probe:error ", 12);
              deal_platform_write_stderr(code, strlen(code));
              deal_platform_write_stderr(" ", 1);
              deal_platform_write_stderr(message, strlen(message));
              deal_platform_write_stderr("\\n", 1);
              deal_platform_flush_stderr();
              return 1;
            }

            int main(void) {
              deal_platform_write_stdout("load:start\\n", 11);
              deal_platform_flush_stdout();
              deal_outcome started = deal_project_start();
              if (started.error != NULL) {
                return report(started);
              }
              deal_platform_write_stdout("load:done\\n", 10);
              deal_platform_flush_stdout();
              deal_module *module = deal_project_exports("host/probe");
              if (module == NULL) {
                return 2;
              }
              deal_export_entry *entry =
                  deal_export_lookup(module, "makeConfig");
              if (entry == NULL) {
                return 3;
              }
              deal_outcome invoked = deal_export_invoke(entry);
              if (invoked.error != NULL) {
                return report(invoked);
              }
              deal_platform_write_stdout("export:done\\n", 12);
              deal_platform_flush_stdout();
              return 0;
            }
            """;
        for (int level : LEVELS) {
            Path product = scratch.resolve("export-driver-o" + level);
            deleteRecursively(product);
            deal.toolchain.NativeToolchain.build(new deal.toolchain
                .NativeBuildRequest(output, product, level,
                    List.of(REPO.resolve("test/native/probe.c")), driver));
            NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
                Map.of());
            checkEq(0, run.exitCode(), "-O" + level + " the driver-shaped"
                + " export invocation of a non-void host export succeeds: "
                + run.describe());
            checkEq("load:start\nload:done\nexport:done\n", run.stdout(),
                "-O" + level + " the driver writes load:start before the module"
                    + " walk and the probe after it");
            checkEq("", run.stderr(), "-O" + level + " the host export crossing"
                + " reports no boundary failure: " + run.describe());
        }
    }

    /**
     * Criterion 5 and H4 `CALLBACK_INVOKE`, executed at both levels: the emitted
     * host-driven entry carries H2's `deal_host_result` shape and default
     * linkage under a deterministic C identifier, so the statically linked host
     * unit re-enters the recorded DEAL function through it. A DEAL failure
     * inside the re-entered code answers `DEAL_FAILURE_DEAL_ERROR` with the
     * runtime-issued token and the `DEAL_KIND_CLASS` carrier of that very
     * Error; the host relays the record unchanged (DEAL catches the identical
     * object), discards another token without propagation, and relays an Error
     * it retained in a later crossing through `deal_error_from_handle`.
     */
    private static void testCallbackReentry(Path scratch) throws Exception {
        System.out.println("-- the host-driven re-entry entry (H4/H2) --");
        Path root = scratch.resolve("callback");
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("bindings"));
        Files.writeString(root.resolve("bindings/probe.d.deal"),
            CALLBACK_DECLARATION, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/main.deal"), CALLBACK_APP,
            StandardCharsets.UTF_8);
        Path out = publishCallbackProject(root, java.util.Set.of("handler"));
        if (out == null) {
            check(false, "the callback fixture compiles and publishes");
            return;
        }
        String module = Files.readString(out.resolve("main.ll"),
            StandardCharsets.UTF_8);
        Matcher entry = Pattern.compile(
            "(?m)^define i32 @(deal_callback_[0-9]+_[0-9]+)\\(").matcher(module);
        boolean defined = entry.find();
        check(defined, "the emitted module defines one host-driven re-entry"
            + " entry with default linkage");
        if (!defined) {
            return;
        }
        String symbol = entry.group(1);
        check(module.contains("call i32 @deal_host_callback_invoke("),
            "the re-entry entry answers H2's deal_host_result through the"
                + " runtime's re-entry seam");
        check(!module.contains("define internal i32 @" + symbol),
            "the re-entry entry is reachable under default linkage");
        Path unit = Files.createDirectories(scratch.resolve("callback-unit"))
            .resolve("probe.c");
        Files.writeString(unit, callbackHostUnit(symbol),
            StandardCharsets.UTF_8);
        for (int level : LEVELS) {
            Path product = scratch.resolve("callback-product-o" + level);
            deleteRecursively(product);
            deal.toolchain.NativeToolchain.build(new deal.toolchain
                .NativeBuildRequest(out, product, level, List.of(unit), null));
            NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
                Map.of());
            checkEq(0, run.exitCode(), "-O" + level + " the host-driven re-entry"
                + " relays, discards and re-relays the identical Error: "
                + run.describe());
            checkEq("", run.stdout(), "-O" + level + " the re-entry product"
                + " writes no terminal output: " + run.describe());
            checkEq("", run.stderr(), "-O" + level + " the re-entry product"
                + " reports no infrastructure fault: " + run.describe());
            // The same relay/discard/retained-relay case under one full
            // collection per safepoint: the timing-only switch (M7) changes
            // no observable behavior.
            NativeCompiler.Result stressed = NativeCompiler.execute(product,
                scratch, Map.of("DEAL_GC_STRESS", "1"));
            checkEq(0, stressed.exitCode(), "-O" + level + " the re-entry"
                + " failures survive a full collection at every safepoint: "
                + stressed.describe());
            checkEq(run.stdout(), stressed.stdout(), "-O" + level + " the"
                + " re-entry product is byte-identical under DEAL_GC_STRESS");
            checkEq("", stressed.stderr(), "-O" + level + " the stressed"
                + " re-entry product writes no diagnostics");
        }
    }

    /**
     * H2/H3/R9: a host-driven re-entry's DEAL-origin failure crosses the host
     * and comes back as the very same Error, and an embedding driver projects
     * that Error through the R9 accessors. The case asserts the relayed Error's
     * origin (source file, line, column), its innermost-first frame count and
     * labels and its absent cause, the retained-Error relay in a later crossing
     * (identity across crossings), the discard path, and a host-authored
     * failure keeping every DEAL-owned field absent. The whole case repeats
     * under {@code DEAL_GC_STRESS=1}.
     */
    private static void testCallbackFailureProjection(Path scratch)
            throws Exception {
        System.out.println("-- the driver projection of the relayed failure"
            + " (H4/H2/R9) --");
        Path root = scratch.resolve("driver-callback");
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("bindings"));
        Files.writeString(root.resolve("bindings/probe.d.deal"),
            DRIVER_CALLBACK_DECLARATION, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/main.deal"), DRIVER_CALLBACK_APP,
            StandardCharsets.UTF_8);
        Path out = publishCallbackProject(root, java.util.Set.of("thrower"));
        if (out == null) {
            check(false, "the driver callback fixture compiles and publishes");
            return;
        }
        String module = Files.readString(out.resolve("main.ll"),
            StandardCharsets.UTF_8);
        Matcher entry = Pattern.compile(
            "(?m)^define i32 @(deal_callback_[0-9]+_[0-9]+)\\(").matcher(module);
        boolean defined = entry.find();
        check(defined, "the driver fixture emits one host-driven re-entry"
            + " entry");
        if (!defined) {
            return;
        }
        String symbol = entry.group(1);
        Matcher label = Pattern.compile(
            "\"([^\"\\\\]*#thrower@F[0-9]+)\\\\00\"").matcher(module);
        boolean labelled = label.find();
        check(labelled, "the re-entered function carries its emitted frame"
            + " label");
        if (!labelled) {
            return;
        }
        String frame = label.group(1);
        String sourceFile = root.getFileName() + "/src/main.deal";
        Path unit = Files.createDirectories(
            scratch.resolve("driver-callback-unit")).resolve("probe.c");
        Files.writeString(unit, driverCallbackHostUnit(symbol),
            StandardCharsets.UTF_8);
        String expected = driverProjectionExpectation(sourceFile, frame);
        for (int level : LEVELS) {
            Path product = scratch.resolve("driver-projection-o" + level);
            deleteRecursively(product);
            deal.toolchain.NativeToolchain.build(new deal.toolchain
                .NativeBuildRequest(out, product, level, List.of(unit),
                    DRIVER_PROJECTION_SOURCE));
            NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
                Map.of());
            checkEq(0, run.exitCode(), "-O" + level + " the driver projects"
                + " the relayed, retained and discarded failures: "
                + run.describe());
            checkEq(expected, run.stdout(), "-O" + level + " the relayed"
                + " Error keeps its fields, origin, frames and absent cause"
                + " across the host and a later crossing: " + run.describe());
            checkEq("", run.stderr(), "-O" + level + " the projection"
                + " writes no infrastructure fault: " + run.describe());
            NativeCompiler.Result stressed = NativeCompiler.execute(product,
                scratch, Map.of("DEAL_GC_STRESS", "1"));
            checkEq(0, stressed.exitCode(), "-O" + level + " the projected"
                + " failure survives a full collection at every safepoint: "
                + stressed.describe());
            checkEq(expected, stressed.stdout(), "-O" + level + " the"
                + " projected failure is byte-identical under DEAL_GC_STRESS");
            checkEq("", stressed.stderr(), "-O" + level + " the stressed"
                + " projection writes no infrastructure fault");
        }
        // The unconsumed token is the protocol failure before the return value
        // is checked, and the timing-only stress switch changes nothing.
        for (int level : LEVELS) {
            Path product = scratch.resolve("driver-swallow-o" + level);
            deleteRecursively(product);
            deal.toolchain.NativeToolchain.build(new deal.toolchain
                .NativeBuildRequest(out, product, level, List.of(unit),
                    DRIVER_SWALLOW_SOURCE));
            for (Map<String, String> environment : List.of(
                    Map.<String, String>of(),
                    Map.of("DEAL_GC_STRESS", "1"))) {
                NativeCompiler.Result run = NativeCompiler.execute(product,
                    scratch, environment);
                String scope = environment.isEmpty() ? "" : " under"
                    + " DEAL_GC_STRESS";
                checkEq(2, run.exitCode(), "-O" + level + " an unconsumed"
                    + " re-entry token is the protocol failure" + scope + ": "
                    + run.describe());
                checkEq("load:start\nload:done\n", run.stdout(), "-O" + level
                    + " the unconsumed token admits no value" + scope);
                checkEq("DEAL_HOST_PROTOCOL: a DEAL failure token was neither"
                        + " relayed nor discarded\n", run.stderr(), "-O" + level
                    + " the unconsumed token writes one deterministic stderr"
                    + " line" + scope);
            }
        }
    }

    /**
     * The expected driver projection of the relayed failure: field order and
     * exact text are the R9 accessor contract, and the frame label is the
     * emitted one.
     */
    private static String driverProjectionExpectation(String sourceFile,
                                                      String frame) {
        return ("""
            load:start
            load:done
            relay:code=E_CALLBACK_DRIVER
            relay:message=the driver callback threw
            relay:file=%s
            relay:line=4
            relay:column=21
            relay:frames=1
            relay:frame0=%s
            relay:cause=absent
            relay:expected=absent
            relay:actual=absent
            retain:carried
            later:code=E_CALLBACK_DRIVER
            later:message=the driver callback threw
            later:file=%s
            later:line=4
            later:column=21
            later:frames=1
            later:frame0=%s
            later:cause=absent
            later:expected=absent
            later:actual=absent
            later:identity=1
            discard:ok
            hosttext:code=E_HOST_TEXT
            hosttext:message=the driver host refused
            hosttext:file=absent
            hosttext:frames=0
            hosttext:cause=absent
            done
            """).formatted(sourceFile, frame, sourceFile, frame);
    }

    /**
     * The driver fixture's host unit: every export drives one crossing over the
     * recorded re-entry and inspects H2's relay record before relaying,
     * discarding, retaining or swallowing its token.
     */
    private static String driverCallbackHostUnit(String entry) {
        return """
            #include "host/probe.h"

            #include <string.h>

            extern deal_status %s(deal_host_result *out, const deal_value *args,
                                  size_t argc);

            static deal_handle retained = 0;

            static deal_host_result ok_number(double value) {
              deal_host_result result;
              result.failed = 0;
              result.failure.kind = DEAL_FAILURE_HOST_TEXT;
              result.failure.code = NULL;
              result.failure.message = NULL;
              result.failure.expected = NULL;
              result.failure.actual = NULL;
              result.failure.error = 0;
              if (deal_number(value, &result.value) != DEAL_OK) {
                result.failed = 1;
                result.failure.code = "E_HOST";
                result.failure.message = "observation construction";
              }
              return result;
            }

            static deal_host_result host_failure(const char *message) {
              deal_host_result result;
              result.failed = 1;
              result.failure.kind = DEAL_FAILURE_HOST_TEXT;
              result.failure.code = "E_HOST_TEXT";
              result.failure.message = message;
              result.failure.expected = NULL;
              result.failure.actual = NULL;
              result.failure.error = 0;
              result.value.slot = 0;
              result.value.generation = 0;
              return result;
            }

            static deal_status inspect(const deal_host_result *record) {
              if (record->failed == 0) {
                return DEAL_STATUS_KIND;
              }
              if (record->failure.kind != DEAL_FAILURE_DEAL_ERROR) {
                return DEAL_STATUS_KIND;
              }
              if (record->failure.error == 0) {
                return DEAL_STATUS_PROTOCOL;
              }
              if (record->failure.code != NULL
                  || record->failure.message != NULL) {
                return DEAL_STATUS_PROTOCOL;
              }
              deal_value_kind kind = DEAL_KIND_NULL;
              if (deal_kind_of(record->value, &kind) != DEAL_OK
                  || kind != DEAL_KIND_CLASS) {
                return DEAL_STATUS_KIND;
              }
              const char *identity = NULL;
              if (deal_class_identity(record->value, &identity) != DEAL_OK
                  || identity == NULL || strcmp(identity, "Error") != 0) {
                return DEAL_STATUS_KIND;
              }
              deal_value code;
              const void *bytes = NULL;
              size_t length = 0;
              if (deal_field_get(record->value, "code", &code) != DEAL_OK
                  || deal_as_string(code, &bytes, &length) != DEAL_OK
                  || length != strlen("E_CALLBACK_DRIVER")
                  || memcmp(bytes, "E_CALLBACK_DRIVER", length) != 0) {
                return DEAL_STATUS_KIND;
              }
              return DEAL_OK;
            }

            static deal_status reenter(deal_host_result *record) {
              return %s(record, NULL, 0u);
            }

            static deal_host_result relay_impl(const deal_value *args,
                                               size_t argc) {
              (void)args;
              (void)argc;
              deal_host_result record;
              if (reenter(&record) != DEAL_OK || inspect(&record) != DEAL_OK) {
                return host_failure("the re-entry record");
              }
              return record;
            }

            static deal_host_result discard_impl(const deal_value *args,
                                                 size_t argc) {
              (void)args;
              (void)argc;
              deal_host_result record;
              if (reenter(&record) != DEAL_OK || inspect(&record) != DEAL_OK) {
                return host_failure("the re-entry record");
              }
              if (deal_failure_discard(record.failure.error) != DEAL_OK) {
                return host_failure("the discard status");
              }
              return ok_number(7.0);
            }

            static deal_host_result retain_impl(const deal_value *args,
                                                size_t argc) {
              (void)args;
              (void)argc;
              deal_host_result record;
              if (reenter(&record) != DEAL_OK || inspect(&record) != DEAL_OK) {
                return host_failure("the re-entry record");
              }
              if (deal_retain(record.value, &retained) != DEAL_OK
                  || retained == 0) {
                return host_failure("the Error retain");
              }
              /* Relayed unchanged: the runtime consumes the token and re-admits
               * the identical Error, which the handle keeps alive for the
               * later crossing. */
              return record;
            }

            static deal_host_result relay_later_impl(const deal_value *args,
                                                     size_t argc) {
              (void)args;
              (void)argc;
              deal_host_failure failure;
              deal_value carrier;
              if (retained == 0 || deal_error_from_handle(retained, &failure)
                      != DEAL_OK) {
                return host_failure("the retained failure token");
              }
              if (deal_handle_value(retained, "Error", &carrier) != DEAL_OK) {
                return host_failure("the retained Error carrier");
              }
              deal_host_result record;
              memset(&record, 0, sizeof record);
              record.failed = 1;
              record.value = carrier;
              record.failure = failure;
              return record;
            }

            static deal_host_result swallow_impl(const deal_value *args,
                                                 size_t argc) {
              (void)args;
              (void)argc;
              deal_host_result record;
              if (reenter(&record) != DEAL_OK) {
                return host_failure("the re-entry status");
              }
              return ok_number(9.0);
            }

            static deal_host_result host_text_impl(const deal_value *args,
                                                   size_t argc) {
              (void)args;
              (void)argc;
              return host_failure("the driver host refused");
            }

            static const deal_host_export exports[] = {
                {"reenterRelay", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0,
                 relay_impl, NULL, NULL},
                {"reenterDiscard", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0,
                 discard_impl, NULL, NULL},
                {"reenterRetain", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0,
                 retain_impl, NULL, NULL},
                {"relayRetained", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0,
                 relay_later_impl, NULL, NULL},
                {"swallow", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0,
                 swallow_impl, NULL, NULL},
                {"hostText", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0,
                 host_text_impl, NULL, NULL},
            };

            const deal_host_module deal_host_module_probe = {
                DEAL_HOST_SPECIFIER_PROBE, exports,
                sizeof exports / sizeof exports[0], NULL, 0u, NULL};
            """.formatted(entry, entry);
    }

    /** The embedding driver of the relayed-failure projection (R9 accessors). */
    private static final String DRIVER_PROJECTION_SOURCE = """
        #include "deal/native/entry.h"
        #include "deal/native/error.h"
        #include "deal/native/platform.h"

        #include <string.h>

        static void say(const char *text) {
          deal_platform_write_stdout(text, strlen(text));
        }

        static void say_number(int value) {
          char buffer[16];
          int length = 0;
          if (value < 0) {
            say("-");
            value = -value;
          }
          if (value == 0) {
            say("0");
            return;
          }
          while (value > 0) {
            buffer[length] = (char)('0' + value % 10);
            length += 1;
            value /= 10;
          }
          char out[16];
          for (int index = 0; index < length; index += 1) {
            out[index] = buffer[length - 1 - index];
          }
          out[length] = 0;
          say(out);
        }

        static void text_line(const char *label, deal_error *error,
                              int (*accessor)(deal_error *, const char **)) {
          const char *text = NULL;
          say(label);
          if (error != NULL && accessor(error, &text) && text != NULL) {
            say(text);
          } else {
            say("absent");
          }
          say("\\n");
        }

        static void int_line(const char *label, deal_error *error,
                             int (*accessor)(deal_error *, int32_t *)) {
          int32_t value = 0;
          say(label);
          if (error != NULL && accessor(error, &value)) {
            say_number((int)value);
          } else {
            say("absent");
          }
          say("\\n");
        }

        static void frame_line(const char *label, deal_error *error) {
          const char *frame = NULL;
          say(label);
          if (error != NULL && deal_error_frame(error, 0, &frame)
              && frame != NULL) {
            say(frame);
          } else {
            say("absent");
          }
          say("\\n");
        }

        static void observe(const char *prefix, deal_error *error) {
          char label[64];
          size_t length = strlen(prefix);
          memcpy(label, prefix, length);
          memcpy(label + length, ":code=", 7);
          label[length + 7] = 0;
          text_line(label, error, deal_error_code);
          memcpy(label + length, ":message=", 10);
          label[length + 10] = 0;
          text_line(label, error, deal_error_message);
          memcpy(label + length, ":file=", 7);
          label[length + 7] = 0;
          text_line(label, error, deal_error_source_file);
          memcpy(label + length, ":line=", 7);
          label[length + 7] = 0;
          int_line(label, error, deal_error_line);
          memcpy(label + length, ":column=", 9);
          label[length + 9] = 0;
          int_line(label, error, deal_error_column);
          memcpy(label + length, ":frames=", 9);
          label[length + 9] = 0;
          int_line(label, error, deal_error_frame_count);
          memcpy(label + length, ":frame0=", 9);
          label[length + 9] = 0;
          frame_line(label, error);
          memcpy(label + length, ":cause=", 8);
          label[length + 8] = 0;
          text_line(label, error, deal_error_cause_code);
          memcpy(label + length, ":expected=", 11);
          label[length + 11] = 0;
          text_line(label, error, deal_error_expected);
          memcpy(label + length, ":actual=", 9);
          label[length + 9] = 0;
          text_line(label, error, deal_error_actual);
        }

        static int invoke(deal_module *module, const char *name,
                          deal_error **out) {
          deal_export_entry *entry = deal_export_lookup(module, name);
          if (entry == NULL) {
            return 0;
          }
          deal_outcome outcome = deal_export_invoke(entry);
          *out = outcome.error;
          return 1;
        }

        int main(void) {
          say("load:start\\n");
          deal_outcome started = deal_project_start();
          if (started.error != NULL) {
            say("start:failed\\n");
            return 1;
          }
          say("load:done\\n");
          deal_module *module = deal_project_exports("host/probe");
          if (module == NULL) {
            say("module:absent\\n");
            return 1;
          }
          deal_error *relayed = NULL;
          if (!invoke(module, "reenterRelay", &relayed) || relayed == NULL) {
            say("relay:absent\\n");
            return 1;
          }
          observe("relay", relayed);
          deal_error *retained = NULL;
          if (!invoke(module, "reenterRetain", &retained)
              || retained == NULL) {
            say("retain:failed\\n");
            return 1;
          }
          say("retain:carried\\n");
          deal_error *later = NULL;
          if (!invoke(module, "relayRetained", &later) || later == NULL) {
            say("later:absent\\n");
            return 1;
          }
          observe("later", later);
          say(later == retained ? "later:identity=1\\n"
                                : "later:identity=0\\n");
          deal_error *discarded = NULL;
          if (!invoke(module, "reenterDiscard", &discarded)
              || discarded != NULL) {
            say("discard:failed\\n");
            return 1;
          }
          say("discard:ok\\n");
          deal_error *hosted = NULL;
          if (!invoke(module, "hostText", &hosted) || hosted == NULL) {
            say("hosttext:absent\\n");
            return 1;
          }
          text_line("hosttext:code=", hosted, deal_error_code);
          text_line("hosttext:message=", hosted, deal_error_message);
          text_line("hosttext:file=", hosted, deal_error_source_file);
          int_line("hosttext:frames=", hosted, deal_error_frame_count);
          text_line("hosttext:cause=", hosted, deal_error_cause_code);
          say("done\\n");
          deal_platform_flush_stdout();
          return 0;
        }
        """;

    /** The driver that leaves the re-entry token unconsumed (H2). */
    private static final String DRIVER_SWALLOW_SOURCE = """
        #include "deal/native/entry.h"
        #include "deal/native/platform.h"

        #include <string.h>

        static void say(const char *text) {
          deal_platform_write_stdout(text, strlen(text));
          deal_platform_flush_stdout();
        }

        int main(void) {
          say("load:start\\n");
          deal_outcome started = deal_project_start();
          if (started.error != NULL) {
            say("start:failed\\n");
            return 1;
          }
          say("load:done\\n");
          deal_module *module = deal_project_exports("host/probe");
          if (module == NULL) {
            say("module:absent\\n");
            return 1;
          }
          deal_export_entry *entry = deal_export_lookup(module, "swallow");
          if (entry == NULL) {
            say("entry:absent\\n");
            return 1;
          }
          deal_outcome invoked = deal_export_invoke(entry);
          say(invoked.error == NULL ? "invoke:ok\\n" : "invoke:failed\\n");
          return 0;
        }
        """;

    /**
     * H6 {@code process_entry_and_export_surface} and the issue's producer
     * clause: an import's own {@code deal_module} record is published only
     * after the importing unit's initialization succeeds, so a failed
     * initialization leaves no visible or invocable host export surface. The
     * two-import fixture supplies one unit, the second import fails the start,
     * and the driver observes that the first import stays unpublished.
     */
    private static void testFailedInitializationGate(Path scratch)
            throws Exception {
        System.out.println("-- the failed-initialization export gate (H6) --");
        Path root = scratch.resolve("gate");
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("bindings"));
        Files.writeString(root.resolve("deal.json"), """
            {"languageVersion": "1.5", "backend": "llvm",
             "moduleRoots": ["src"], "output": "out",
             "externals": {"host/pr": {"declaration": "bindings/pr.d.deal"},
                           "host/pr2": {"declaration": "bindings/pr2.d.deal"}}}
            """, StandardCharsets.UTF_8);
        for (String stem : List.of("pr", "pr2")) {
            Files.writeString(root.resolve("bindings/" + stem + ".d.deal"),
                "export function observe(): number;\n", StandardCharsets.UTF_8);
        }
        Files.writeString(root.resolve("src/main.deal"), """
            import * as pr from "host/pr";
            import * as pr2 from "host/pr2";

            export function main(): null {
              let first: number = pr.observe();
              let second: number = pr2.observe();
              if (first !== second) {
                throw { code: "TEST_FAIL", message: "the host observations" };
              }
              return null;
            }
            """, StandardCharsets.UTF_8);
        Path output = root.resolve("out");
        int status = deal.Main.run(new String[] {"compile",
            root.resolve("src/main.deal").toString(),
            "--backend", "llvm", "--output", output.toString()});
        checkEq(0, status, "the two-import fixture compiles");
        Path unit = Files.createDirectories(scratch.resolve("gate-unit"))
            .resolve("pr.c");
        Files.writeString(unit, """
            #include "host/pr.h"

            static deal_host_result observe_impl(const deal_value *args,
                                                 size_t argc) {
              (void)args;
              (void)argc;
              deal_host_result result;
              result.failed = 0;
              result.failure.kind = DEAL_FAILURE_HOST_TEXT;
              result.failure.code = NULL;
              result.failure.message = NULL;
              result.failure.expected = NULL;
              result.failure.actual = NULL;
              result.failure.error = 0;
              if (deal_number(1.0, &result.value) != DEAL_OK) {
                result.failed = 1;
                result.failure.code = "E_HOST";
                result.failure.message = "observation construction";
              }
              return result;
            }

            static const deal_host_export exports[] = {
                {"observe", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0,
                 observe_impl, NULL, NULL},
            };

            const deal_host_module deal_host_module_pr = {
                DEAL_HOST_SPECIFIER_PR, exports,
                sizeof exports / sizeof exports[0], NULL, 0u, NULL};
            """, StandardCharsets.UTF_8);
        String driver = """
            #include "deal/native/entry.h"
            #include "deal/native/platform.h"

            #include <string.h>

            static void say(const char *text) {
              deal_platform_write_stdout(text, strlen(text));
              deal_platform_flush_stdout();
            }

            int main(void) {
              say("load:start\\n");
              deal_outcome started = deal_project_start();
              say("load:done\\n");
              deal_module *module = deal_project_exports("host/pr");
              say(module == NULL ? "pr:absent\\n" : "pr:visible\\n");
              if (module != NULL) {
                deal_export_entry *entry =
                    deal_export_lookup(module, "observe");
                say(entry == NULL ? "entry:absent\\n" : "entry:visible\\n");
                if (entry != NULL) {
                  deal_outcome invoked = deal_export_invoke(entry);
                  say(invoked.error == NULL ? "invoke:ok\\n" : "invoke:failed\\n");
                }
              }
              if (started.error != NULL) {
                const char *code = NULL;
                if (!deal_error_code(started.error, &code) || code == NULL) {
                  code = "";
                }
                say("start:failed ");
                say(code);
                say("\\n");
                return 1;
              }
              say("start:ok\\n");
              return 0;
            }
            """;
        for (int level : LEVELS) {
            Path product = scratch.resolve("gate-product-o" + level);
            deleteRecursively(product);
            deal.toolchain.NativeToolchain.build(new deal.toolchain
                .NativeBuildRequest(output, product, level, List.of(unit),
                    driver));
            NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
                Map.of());
            checkEq(1, run.exitCode(), "-O" + level + " the absent second unit"
                + " fails the project start: " + run.describe());
            checkEq("load:start\nload:done\npr:absent\nstart:failed E8011\n",
                run.stdout(), "-O" + level + " a failed initialization leaves"
                    + " the loaded import's export surface unpublished");
            checkEq("", run.stderr(), "-O" + level + " the failed start reports"
                + " no infrastructure fault: " + run.describe());
        }
    }

    // =========================================================================
    // The value surface, handles and the failure channel (H2/H3, R11)
    // =========================================================================

    /**
     * Criteria 4 and 5 at the runtime surface: a strict-C11 driver against the
     * published tree exercises construction, the crossing, the handle statuses
     * and the DEAL-origin failure relay and discard.
     */
    private static void testValueSurfaceAndHandles(Path scratch, Path output)
            throws IOException {
        System.out.println("-- the value surface, handles and the failure"
            + " channel (H2/H3/R11) --");
        Path driver = REPO.resolve("test/native/host_value_driver.c");
        Path moduleStub = REPO.resolve("test/native/module_stub.c");
        Map<String, String> successes = new LinkedHashMap<>();
        successes.put("0", "host value ok\n");
        successes.put("1", "host value ok\n");
        // The relay case transports one DEAL Error and admits it unchanged.
        successes.put("2", "host value ok\n");
        successes.put("3", "host value ok\n");
        // The function-valued positions of the surface: a retained function value
        // re-validated against its own canonical descriptor, the closed `kind`
        // status for a mismatched signature and kind, the checked apply, and a
        // typed array of function elements materializing and accepting a view
        // store of a declared function element (case 7).
        successes.put("7", "host value ok\n");
        for (Map.Entry<String, String> expectation : successes.entrySet()) {
            Path source = scratch.resolve("host_value_case"
                + expectation.getKey() + ".c");
            Files.writeString(source, "#define DEAL_HOST_VALUE_CASE "
                + expectation.getKey() + "\n" + Files.readString(driver,
                    StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            for (int level : LEVELS) {
                Path product = scratch.resolve("host_value_case"
                    + expectation.getKey() + "_o" + level);
                // The driver defines the registry itself, so this link names
                // the runtime units directly instead of the shared harness set.
                List<Path> caseSources = new ArrayList<>();
                caseSources.add(source);
                for (Path unit : NativeCompiler.runtimeTranslationUnits(output)) {
                    if (!unit.getFileName().toString().equals("main.c")) {
                        caseSources.add(unit);
                    }
                }
                caseSources.add(moduleStub);
                NativeCompiler.Result link = NativeCompiler.link(output, level,
                    caseSources, product);
                check(link.succeeded(), "-O" + level + " the host value case "
                    + expectation.getKey() + " builds against the published"
                    + " tree: " + link.describe());
                if (!link.succeeded()) {
                    continue;
                }
                NativeCompiler.Result run = NativeCompiler.execute(product,
                    scratch, Map.of());
                checkEq(0, run.exitCode(), "-O" + level + " the host value case "
                    + expectation.getKey() + " reports its exact success line: "
                    + run.describe());
                checkEq(expectation.getValue(), run.stdout(), "-O" + level
                    + " the host value case " + expectation.getKey()
                    + " observations");
                checkEq("", run.stderr(), "-O" + level + " the host value case "
                    + expectation.getKey() + " keeps stderr empty");
                NativeCompiler.Result stressed = NativeCompiler.execute(
                    product, scratch, Map.of("DEAL_GC_STRESS", "1"));
                checkEq(0, stressed.exitCode(), "-O" + level + " the host"
                    + " value case " + expectation.getKey() + " survives a"
                    + " full collection at every safepoint: "
                    + stressed.describe());
                checkEq(expectation.getValue(), stressed.stdout(), "-O" + level
                    + " the host value case " + expectation.getKey()
                    + " is byte-identical under DEAL_GC_STRESS");
                checkEq("", stressed.stderr(), "-O" + level + " the stressed"
                    + " host value case " + expectation.getKey()
                    + " keeps stderr empty");
            }
        }
        // The unconsumed-token rule: one deterministic stderr line, exit 2, and
        // the crossing never returns the host's own value (H2).
        Path unconsumed = scratch.resolve("host_value_case4.c");
        Files.writeString(unconsumed, "#define DEAL_HOST_VALUE_CASE 4\n"
            + Files.readString(driver, StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);
        for (int level : LEVELS) {
            Path product = scratch.resolve("host_value_case4_o" + level);
            List<Path> caseSources = new ArrayList<>();
            caseSources.add(unconsumed);
            for (Path unit : NativeCompiler.runtimeTranslationUnits(output)) {
                if (!unit.getFileName().toString().equals("main.c")) {
                    caseSources.add(unit);
                }
            }
            caseSources.add(moduleStub);
            NativeCompiler.Result link = NativeCompiler.link(output, level,
                caseSources, product);
            check(link.succeeded(), "-O" + level + " the unconsumed-token case"
                + " builds: " + link.describe());
            if (!link.succeeded()) {
                continue;
            }
            NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
                Map.of());
            checkEq(2, run.exitCode(), "-O" + level + " an unconsumed DEAL"
                + " failure token exits 2 before the return value is checked: "
                + run.describe());
            checkEq("", run.stdout(), "-O" + level + " the unconsumed-token case"
                + " writes no stdout");
            checkEq("DEAL_HOST_PROTOCOL: a DEAL failure token was neither"
                    + " relayed nor discarded\n", run.stderr(),
                "-O" + level + " the unconsumed-token case writes exactly one"
                    + " deterministic stderr line");
            NativeCompiler.Result stressed = NativeCompiler.execute(product,
                scratch, Map.of("DEAL_GC_STRESS", "1"));
            checkEq(2, stressed.exitCode(), "-O" + level + " the unconsumed"
                + " token stays the protocol failure under DEAL_GC_STRESS: "
                + stressed.describe());
            checkEq("", stressed.stdout(), "-O" + level + " the stressed"
                + " unconsumed-token case writes no stdout");
            checkEq(run.stderr(), stressed.stderr(), "-O" + level + " the"
                + " unconsumed-token case is byte-identical under"
                + " DEAL_GC_STRESS");
        }
    }

    /**
     * Criterion 4's declared slots and R10: a host array carrier whose element
     * region cannot exist is the deterministic resource fault — one stderr line
     * and exit 2 before anything is published — never a short block carrying a
     * fabricated element count (what the unchecked slot product produced, after
     * which a slot store wrote outside the block).
     */
    private static void testArrayCarrierResourceFault(Path scratch, Path output)
            throws IOException {
        System.out.println("-- the host array carrier's checked size (R10) --");
        Path driver = REPO.resolve("test/native/host_value_driver.c");
        Path moduleStub = REPO.resolve("test/native/module_stub.c");
        for (String valueCase : List.of("5", "6")) {
            Path source = scratch.resolve("host_value_case" + valueCase
                + ".c");
            Files.writeString(source, "#define DEAL_HOST_VALUE_CASE "
                + valueCase + "\n" + Files.readString(driver,
                    StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            for (int level : LEVELS) {
                Path product = scratch.resolve("host_value_case" + valueCase
                    + "_o" + level);
                List<Path> caseSources = new ArrayList<>();
                caseSources.add(source);
                for (Path unit : NativeCompiler.runtimeTranslationUnits(
                        output)) {
                    if (!unit.getFileName().toString().equals("main.c")) {
                        caseSources.add(unit);
                    }
                }
                caseSources.add(moduleStub);
                NativeCompiler.Result link = NativeCompiler.link(output, level,
                    caseSources, product);
                check(link.succeeded(), "-O" + level + " the array carrier case "
                    + valueCase + " builds against the published tree: "
                    + link.describe());
                if (!link.succeeded()) {
                    continue;
                }
                NativeCompiler.Result run = NativeCompiler.execute(product,
                    scratch, Map.of());
                checkEq(2, run.exitCode(), "-O" + level + " the impossible"
                    + " array length (case " + valueCase + ") is the"
                    + " deterministic resource fault: " + run.describe());
                checkEq("", run.stdout(), "-O" + level + " the impossible"
                    + " array length (case " + valueCase + ") admits nothing"
                    + " and reports no DEAL arm");
                checkEq("DEAL_RUNTIME_FAULT: size arithmetic: multiplication"
                        + " overflow\n", run.stderr(), "-O" + level + " the"
                    + " impossible array length (case " + valueCase + ") is"
                    + " the checked-size fault");
                checkEq(1, count(run.stderr(), "\n"), "-O" + level + " the"
                    + " resource fault (case " + valueCase + ") writes exactly"
                    + " one stderr line");
                NativeCompiler.Result stressed = NativeCompiler.execute(
                    product, scratch, Map.of("DEAL_GC_STRESS", "1"));
                checkEq(2, stressed.exitCode(), "-O" + level + " the resource"
                    + " fault (case " + valueCase + ") is unchanged under"
                    + " DEAL_GC_STRESS: " + stressed.describe());
                checkEq("", stressed.stdout(), "-O" + level + " the stressed"
                    + " resource fault (case " + valueCase + ") admits"
                    + " nothing");
                checkEq(run.stderr(), stressed.stderr(), "-O" + level + " the"
                    + " resource-fault case " + valueCase + " is"
                    + " byte-identical under DEAL_GC_STRESS");
            }
        }
    }

    /**
     * The declared function slot at a host view store (H4/R11, R4's closed
     * boundary rule). A host-declared class whose one field is a function type
     * crosses as a host-built carrier, materializes with its function value and
     * DEAL applies it; the host then stores into a view of that DEAL-held
     * instance, so the store validates against the slot's declared descriptor —
     * a canonical function descriptor, which belongs to the
     * {@code FUNCTION_SIGNATURE} boundary — commits when it is admitted, and
     * answers the closed {@code kind} status for a wrong-kind and a
     * wrong-signature value without changing the slot. A check against that
     * descriptor at the typed boundary would fault the process instead, so the
     * executed product plus the emitted layout map are the evidence; the emitted
     * class layout also traces the function-typed slot as a managed reference, so
     * the stored function value survives every collection (C3/M1/M2).
     */
    private static void testFunctionSlotViewStore(Path scratch) throws Exception {
        System.out.println("-- the declared function slot of a host view store"
            + " (H4/R11) --");
        Path root = scratch.resolve("slot-project");
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("bindings"));
        Files.writeString(root.resolve("deal.json"), SLOT_JSON,
            StandardCharsets.UTF_8);
        Files.writeString(root.resolve("bindings/slot.d.deal"),
            SLOT_DECLARATION, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/main.deal"), SLOT_APP,
            StandardCharsets.UTF_8);
        Path output = root.resolve("out");
        deleteRecursively(output);
        int status = deal.Main.run(new String[] {"compile",
            root.resolve("src/main.deal").toString(),
            "--backend", "llvm", "--output", output.toString()});
        checkEq(0, status, "the function-slot project compiles");
        if (status != 0) {
            return;
        }
        String module = Files.readString(output.resolve("main.ll"),
            StandardCharsets.UTF_8);
        check(module.contains(".layout.refs = private unnamed_addr constant"
                + " [1 x i32] [i32 8]"),
            "the emitted class layout maps the function-typed slot as a managed"
                + " reference");
        Path hostUnit = REPO.resolve("test/native/slot.c");
        for (int level : LEVELS) {
            Path product = scratch.resolve("slot-product-" + level);
            deleteRecursively(product);
            try {
                deal.toolchain.NativeToolchain.build(new deal.toolchain
                    .NativeBuildRequest(output, product, level,
                        List.of(hostUnit), null));
            } catch (deal.toolchain.NativeBuildException failure) {
                check(false, "-O" + level + " the function-slot product"
                    + " builds: " + failure.getMessage());
                continue;
            }
            NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
                Map.of());
            checkEq(0, run.exitCode(), "-O" + level + " the function-slot"
                + " product executes the declared function slot: "
                + run.describe());
            checkEq("", run.stdout(), "-O" + level + " the function-slot"
                + " product writes no terminal output: " + run.describe());
            checkEq("", run.stderr(), "-O" + level + " the function-slot"
                + " product writes no diagnostics: " + run.describe());
            NativeCompiler.Result stressed = NativeCompiler.execute(product,
                scratch, Map.of("DEAL_GC_STRESS", "1"));
            checkEq(0, stressed.exitCode(), "-O" + level + " the stored"
                + " function value survives a full collection at every"
                + " safepoint: " + stressed.describe());
            checkEq("", stressed.stdout(), "-O" + level + " the stressed"
                + " function-slot product writes no terminal output");
            checkEq("", stressed.stderr(), "-O" + level + " the stressed"
                + " function-slot product writes no diagnostics");
        }
    }

    // =========================================================================
    // Shared harness
    // =========================================================================

    /**
     * The one production emission of a callback fixture with the given recorded
     * callback exports (H4): the production pipeline today records no callback
     * exports, so the lowering input is supplied here directly through
     * `ProductionProjectEmission.run`. Answers the publication root, or null
     * when the compile, the checked facts or the emission failed.
     */
    private static Path publishCallbackProject(Path root,
                                               java.util.Set<String> callbacks)
            throws IOException {
        Path out = root.resolve("out");
        Map<String, String> externals = new LinkedHashMap<>();
        externals.put("host/probe",
            root.resolve("bindings/probe.d.deal").toAbsolutePath().toString());
        deal.semantic.CompilerInvocation invocation =
            deal.semantic.CompilerProfileProvider.resolve(
                deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
                deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
        deal.module.CompilationOrchestrator orchestrator =
            new deal.module.CompilationOrchestrator(
                root.resolve("src/main.deal").toAbsolutePath(), out,
                false, false, false, false, deal.codegen.Backend.LLVM,
                externals, List.of(root.resolve("src").toAbsolutePath()),
                null, null, invocation);
        boolean compiled = orchestrator.compile();
        check(compiled, "the callback fixture compiles: "
            + orchestrator.diagnostics());
        if (!compiled) {
            return null;
        }
        deal.semantic.CheckedProjectBuildResult built =
            orchestrator.checkedProject();
        deal.semantic.RequirementManifestResult manifests =
            orchestrator.requirementManifests();
        if (built == null || built.input() == null || built.index() == null
                || built.hasErrors() || manifests == null
                || manifests.manifests() == null
                || orchestrator.hostDeclarationSurface() == null) {
            check(false, "the callback fixture builds its checked facts");
            return null;
        }
        Map<deal.semantic.ir.ModuleId, deal.identity.CanonicalModuleIdentity>
            identities = new LinkedHashMap<>();
        for (deal.semantic.ir.ModuleId declaration
                : orchestrator.hostDeclarationSurface().moduleIds()) {
            identities.put(declaration,
                new deal.identity.CanonicalModuleIdentity.ExternalModule(
                    declaration.path().replace('/', '.')));
        }
        deal.publication.PublicationStager stager =
            deal.publication.PublicationStager.forRoot(out);
        deal.module.ProductionProjectEmission.Result emitted;
        try {
            emitted = deal.module.ProductionProjectEmission.run(invocation,
                built.input(), built.index(), manifests.manifests(),
                orchestrator.hostDeclarationSurface(), identities,
                deal.checker.BuiltinErrorDeclaration.synthesized(
                    built.input().modules().get(0).ast().span()),
                callbacks, deal.codegen.Backend.LLVM, false,
                deal.distribution.DistributionHome.forManifestDirectory(
                    root.resolve("src").toString()), stager);
            check(emitted.emitted(), "the callback-aware emission publishes: "
                + emitted.diagnostics());
            if (!emitted.emitted()) {
                return null;
            }
            stager.publish();
        } finally {
            stager.discard();
        }
        return out;
    }

    /**
     * The text of one interned string constant of the emitted module: the `c`
     * literal body of the `@deal.str.<n>` definition, without its terminator.
     */
    private static String interned(String module, String symbol) {
        if (symbol == null || symbol.isEmpty()) {
            return null;
        }
        Matcher match = Pattern.compile(Pattern.quote(symbol)
            + " = private unnamed_addr constant \\[[0-9]+ x i8\\]"
            + " c\"([^\"]*)\\\\00\"").matcher(module);
        return match.find() ? match.group(1) : null;
    }

    /**
     * One production native build and run of the fixture (D3/D4/D5): the
     * published project module, the closed runtime set, the generated registry
     * and the supplied host unit at the pinned flags.
     */
    private static NativeCompiler.Result build(Path project, int level,
                                               Path hostUnit, Path scratch)
            throws Exception {
        Path product = scratch.resolve("product-" + level + "-"
            + (hostUnit == null ? "none"
                : hostUnit.getFileName().toString()));
        deleteRecursively(product);
        try {
            deal.toolchain.NativeToolchain.build(new deal.toolchain
                .NativeBuildRequest(project.resolve("out"), product, level,
                    hostUnit == null ? List.of() : List.of(hostUnit), null));
        } catch (deal.toolchain.NativeBuildException failure) {
            throw new IllegalStateException("the production toolchain seam"
                + " rejects the fixture at -O" + level + ": "
                + failure.getMessage(), failure);
        }
        return NativeCompiler.execute(product, scratch, Map.of());
    }

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
