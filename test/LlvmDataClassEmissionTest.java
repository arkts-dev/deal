package deal.test;

import deal.codegen.llvm.LlvmTypeMapper;
import deal.diagnostics.CompilerDiagnostic;
import deal.module.CompilationOrchestrator;
import deal.project.CliOverrides;
import deal.project.ProjectContext;
import deal.project.ProjectLocator;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The string, container, class and {@code @jsonable} emission of the native
 * backend and the executed evidence that belongs to it
 * ({@code llvm-native-codegen-cfg-ssa} C3/C4/C6/C8/C9, {@code OpRendering},
 * {@code DiagnosticOriginProjection}, {@code CallFrameContract};
 * {@code llvm-native-runtime-value-model} R2/R4/R5/R6/R8/R9; task "strings,
 * containers, classes, {@code @jsonable}, and the executed Error-routing/IEEE/
 * selector evidence").
 *
 * <p>Every case compiles a real project through the production resolution and
 * emission path and then either inspects the <em>published</em> module of that
 * compile or builds and runs the product at both optimization levels:</p>
 *
 * <ol>
 *   <li>every produced op family renders its own runtime entry or static data:
 *       string literals and templates, array and record construction, reads,
 *       writes and deletes, the discrete-position validation, the presence
 *       metadata of an optional class field, the class construction and field
 *       ops, the generated JSON walks and their plan records, the emitted
 *       layout table and the descriptor atoms;</li>
 *   <li>the emitted class instance record carries the class atom, one presence
 *       byte per optional field and one typed slot per declared field, and the
 *       emitted layout metadata is installed before any unit initialization;</li>
 *   <li>two compiles of one project are byte-identical (C9);</li>
 *   <li>the executed fixtures observe their outcomes through the pinned
 *       channel: every case encodes its result as a distinguishing Error code
 *       and the fixture ends with the {@code DONE} marker whose message carries
 *       the observed values, so an empty program cannot satisfy a case and a
 *       wrong rendering changes the code or the message;</li>
 *   <li>the boundary tuple, the frame snapshot and the DEAL-origin failure are
 *       projected through a driver build whose {@code main} calls
 *       {@code deal_project_start()} then {@code deal_project_run_main()} and
 *       prints the R9 accessors' values — the expected values are the registry's
 *       own template and the recorded origin, never a copy of an observed
 *       run;</li>
 *   <li>every executed case repeats under {@code DEAL_GC_STRESS=1} with exactly
 *       the same output, so survival and reclamation change timing only.</li>
 * </ol>
 */
public final class LlvmDataClassEmissionTest {

    private static final Path REPO = NativeCompiler.repository();

    /**
     * The nesting depth of the executed deep-document case (R6: the traversal
     * has no fixed nesting cap, so the case is deeper than any plausible
     * recursive encoder could survive on the default stack).
     */
    private static final int JSON_DEEP_DEPTH = 200;

    // =========================================================================
    // The fixtures
    // =========================================================================

    /**
     * The executed containers fixture (R2): string scalars and templates, the
     * typed array element access with its exact length, append-at-length and
     * negative zero, the discrete-position arms (out of range, fractional and
     * NaN), the {@code FOR_EACH} live length and live contents (an append and an
     * unvisited replacement) with the original iterable kept across a binding
     * reassignment, the record own-key/absent-read/delete rules, the reference
     * and string selector operands (equality, ordering and the inclusive
     * {@code <=}/{@code >=} predicates over prefix, BMP, astral and equal
     * operands), and the class field operations.
     */
    private static String containersFixture() {
        return """
            class Point {
              x: number;
              label?: string | null;
            }

            function report(code: string, text: string): void {
              throw { code: code, message: `${text}` };
            }

            export function main(): null {
              let greeting: string = "ab";
              let joined: string = `${greeting}-${greeting}!`;
              if (joined !== "ab-ab!") { report("S1", joined); }

              let scalars: number = 0;
              let rebuilt: string = "";
              for (let character of "héllo") {
                rebuilt = `${rebuilt}${character}`;
                scalars = scalars + 1;
              }
              if (scalars !== 5) { report("S2", "scalar count"); }
              if (rebuilt !== "héllo") { report("S3", rebuilt); }

              let numbers: number[] = [1, 2, 3];
              if (numbers.length !== 3) { report("A1", "length"); }
              numbers[0] = numbers[1] + 1;
              if (numbers[0] !== 3) { report("A2", "write"); }
              numbers[3] = 9;
              if (numbers.length !== 4) { report("A3", "append length"); }
              let total: number = 0;
              for (let value of numbers) { total = total + value; }
              if (total !== 17) { report("A4", "for-of total"); }

              let live: number[] = [1, 2];
              let seen: number = 0;
              for (let value of live) {
                seen = seen + value;
                if (value === 1) { live[live.length] = 3; }
              }
              if (seen !== 6) { report("A5", "live for-of"); }
              let retained: number[] = [1, 2];
              let sum: number = 0;
              for (let value of retained) { sum = sum + value; retained = [9]; }
              if (sum !== 3) { report("A6", "original iterable"); }

              let bounds: boolean = false;
              try {
                let small: number[] = [1];
                let index: number = 5;
                let value: number = small[index];
                if (value !== 0) { report("A7", "bounds read"); }
              } catch (e) {
                if (e instanceof Error) {
                  if (e.code !== "E8002") { report("A8", e.code); }
                  bounds = true;
                }
              }
              if (!bounds) { report("A9", "bounds arm"); }

              let fractional: boolean = false;
              try {
                let measured: number[] = [1, 2];
                let position: number = 1.5;
                let picked: number = measured[position];
                if (picked !== 0) { report("A11", "fractional accepted"); }
              } catch (e) {
                if (e instanceof Error) {
                  if (e.code !== "E8002") { report("A12", e.code); }
                  fractional = true;
                }
              }
              if (!fractional) { report("A13", "fractional arm"); }

              let unordered: boolean = false;
              let noValue: number = 0.0;
              let notANumber: number = noValue / noValue;
              try {
                let measured: number[] = [1, 2];
                let picked: number = measured[notANumber];
                if (picked !== 0) { report("A14", "NaN accepted"); }
              } catch (e) {
                if (e instanceof Error) {
                  if (e.code !== "E8002") { report("A15", e.code); }
                  unordered = true;
                }
              }
              if (!unordered) { report("A16", "NaN arm"); }

              let replaced: number[] = [1, 2];
              let unvisited: number = 0;
              for (let value of replaced) {
                unvisited = unvisited + value;
                if (value === 1) { replaced[1] = 10; }
              }
              if (unvisited !== 11) { report("A17", "unvisited replacement"); }

              let zeros: number[] = [7];
              zeros[-0.0] = 8;
              if (zeros[0] !== 8) { report("A10", "negative zero"); }

              let record: Record<string, unknown> = { first: 1, second: null };
              if (!Object.hasOwn(record, "first")) { report("R1", "own key"); }
              let key: string = "third";
              record[key] = undefined;
              if (!Object.hasOwn(record, key)) { report("R2", "present undefined"); }
              delete record[key];
              if (Object.hasOwn(record, key)) { report("R3", "deleted key"); }
              let read = record[key];
              if (read !== undefined) { report("R4", "absent read"); }

              let mixed: (number | null)[] = [1, null, 3];
              let second = mixed[1];
              if (second !== null) { report("N1", "null element"); }
              mixed[2] = null;
              if (!(mixed[2] === null)) { report("N2", "null write"); }

              let points: Point[] = [{ x: 1 }, { x: 2 }];
              let coordinates: number = 0;
              for (let point of points) { coordinates = coordinates + point.x; }
              if (coordinates !== 3) { report("C1", "class for-of"); }

              let point: Point = { x: 5, label: "hi" };
              if (!Object.hasOwn(point, "label")) { report("C2", "optional present"); }
              delete point.label;
              if (Object.hasOwn(point, "label")) { report("C3", "optional deleted"); }
              let deletedLabel: string | null | undefined = point.label;
              if (!(deletedLabel === undefined)) { report("C7", "absent read"); }
              if (!Object.hasOwn(point, "x")) { report("C4", "required present"); }
              point.label = null;
              if (!Object.hasOwn(point, "label")) { report("C5", "present null"); }
              let label: string | null | undefined = point.label;
              if (!(label === null)) { report("C6", "null field read"); }

              let forward: number[] = [1];
              let backward: number[] = [1];
              if (!(forward !== backward)) { report("F1", "distinct identity"); }
              let alias: unknown = forward;
              let sibling: unknown = forward;
              let other: unknown = backward;
              if (alias !== sibling) { report("F2", "alias identity"); }
              if (!(alias !== other)) { report("F3", "unknown identity"); }
              let sameText: string = "ab";
              if (!(sameText === "ab")) { report("F4", "string equality"); }
              if (!("a" < "ab")) { report("F5", "string prefix"); }
              if (!("é" > "e")) { report("F6", "BMP scalar order"); }
              if (!("𝄞" > "é")) { report("F7", "astral scalar order"); }
              if ("abc" === "abd") { report("F8", "string inequality"); }
              // STRING_LE / STRING_GE: the inclusive predicates must agree
              // with the strict ones and with `deal_text_compare`'s scalar
              // order over the prefix, BMP and astral operands above.
              if (!("ab" <= "ab")) { report("F9", "string <= equal"); }
              if (!("ab" >= "ab")) { report("F10", "string >= equal"); }
              if ("ab" <= "a") { report("F11", "string <= longer"); }
              if (!("a" <= "ab")) { report("F12", "string <= prefix"); }
              if (!("𝄞" >= "é")) { report("F13", "string >= astral"); }
              if ("é" >= "𝄞") { report("F14", "string <= bmp"); }

              throw { code: "DONE", message: `${rebuilt}` };
            }
            """;
    }

    /**
     * The executed class fixture: three side-effecting supplied fields that
     * prove the left-to-right evaluation order and the once-only rule, the
     * required/optional/extra field rules through the frontend, optional and
     * nullish presence, nested classes, class arrays, {@code typeof}/
     * {@code instanceof}/{@code as} through the shared arms, raw record reads
     * retaining {@code unknown}, and the persistent element descriptor of an
     * empty array.
     */
    private static String classesFixture() {
        return """
            class Address {
              city: string;
              zip: number;
            }

            class User {
              name: string;
              address: Address;
              tags: string[];
              nick?: string;
            }

            class Side {
              first: string;
              second: string;
              third: string;
            }

            function supplied(into: number[], value: number): string {
              into[into.length] = value;
              if (value === 1) { return "one"; }
              if (value === 2) { return "two"; }
              return "three";
            }

            function report(code: string, text: string): void {
              throw { code: code, message: `${text}` };
            }

            export function main(): null {
              let user: User = {
                name: "Ada",
                address: { city: "London", zip: 12345 },
                tags: ["admin", "beta"],
              };
              if (user.name !== "Ada") { report("C1", "name"); }
              if (user.address.city !== "London") { report("C2", "city"); }
              if (user.address.zip !== 12345) { report("C3", "zip"); }
              if (user.tags.length !== 2) { report("C4", "tags"); }
              if (user.tags[1] !== "beta") { report("C5", "tag"); }
              if (Object.hasOwn(user, "nick")) { report("C6", "nickabsent"); }
              user.nick = "b";
              if (!Object.hasOwn(user, "nick")) { report("C7", "nickpresent"); }
              let nick: string | undefined = user.nick;
              let nickText: string = nick as string;
              if (nickText !== "b") { report("C8", nickText); }

              let users: User[] = [user];
              if (users.length !== 1) { report("C9", "users"); }
              if (users[0].name !== "Ada") { report("C10", "user name"); }

              let untyped: unknown = user;
              if (typeof untyped !== "object") { report("T1", "typeof class"); }
              if (!(untyped instanceof User)) { report("T2", "instanceof User"); }
              if (untyped instanceof Address) { report("T3", "instanceof other"); }
              let narrowed: User = untyped as User;
              if (narrowed.name !== "Ada") { report("T4", "assertion"); }

              let listed: unknown = ["x"];
              if (typeof listed !== "object") { report("T5", "typeof array"); }
              if (listed instanceof User) { report("T6", "array is instance"); }
              let strings: string[] = listed as string[];
              if (strings.length !== 1) { report("T7", "array assertion"); }

              let record: Record<string, unknown> = { name: "Ada", age: 36 };
              let key: string = "age";
              let raw = record[key];
              let age: number = raw as number;
              if (age !== 36) { report("R1", "record number"); }
              record[key] = undefined;
              if (!Object.hasOwn(record, key)) { report("R2", "present undefined"); }
              delete record[key];
              if (Object.hasOwn(record, key)) { report("R3", "deleted"); }

              let empty: string[] = [];
              let descriptor: unknown = empty;
              let rejected: boolean = false;
              try {
                let incompatible: number[] = descriptor as number[];
                if (incompatible.length !== 0) { report("A1", "length"); }
              } catch (e) {
                if (e instanceof Error) {
                  if (e.code !== "E8001") { report("A2", e.code); }
                  rejected = true;
                }
              }
              if (!rejected) { report("A3", "incompatible empty array accepted"); }

              let order: number[] = [];
              let sided: Side = {
                first: supplied(order, 1),
                second: supplied(order, 2),
                third: supplied(order, 3),
              };
              if (order.length !== 3) { report("S1", "once-only supplied fields"); }
              if (order[0] !== 1) { report("S2", "first supplied field"); }
              if (order[1] !== 2) { report("S3", "second supplied field"); }
              if (order[2] !== 3) { report("S4", "third supplied field"); }
              if (sided.first !== "one") { report("S5", sided.first); }
              if (sided.second !== "two") { report("S6", sided.second); }
              if (sided.third !== "three") { report("S7", sided.third); }

              throw { code: "DONE", message: `${user.name}|${user.address.city}|${nickText}` };
            }
            """;
    }

    /**
     * The executed {@code @jsonable} fixture (R6): decode and encode in
     * declaration order, nested class plans, arrays, records, optional absence
     * preserved, missing/extra/duplicate/type failures returning the language
     * null, binary64 number text, the required-undefined asymmetry, the
     * decoded {@code T[]} fields as values of their declared type (every
     * element storage kind read back after the decode), one deep nested
     * document without a nesting cap, and the path-local cycle arm.
     */
    private static String jsonFixture() {
        String deepDocument = "[".repeat(JSON_DEEP_DEPTH) + "1"
            + "]".repeat(JSON_DEEP_DEPTH);
        String deepEncoded = "[".repeat(JSON_DEEP_DEPTH) + "1.0"
            + "]".repeat(JSON_DEEP_DEPTH);
        return """
            // @jsonable
            export class Inner {
              value: number;
            }

            // @jsonable
            export class Outer {
              title: string;
              inner: Inner;
              items: number[];
              maybe: string | undefined;
              optionalNull: string | null;
              extra?: string;
              dynamic: Record<string, unknown>;
            }

            // @jsonable
            export class Undefined {
              name: string;
              maybe: string | undefined;
            }

            // @jsonable
            export class Arrays {
              numbers: number[];
              flags: boolean[];
              texts: string[];
              nested: string[][];
              mixed: (number | null)[];
              options: (boolean | null)[];
              inner: Inner[];
              rows: Record<string, unknown>[];
            }

            function report(code: string, text: string): void {
              throw { code: code, message: `${text}` };
            }

            export function main(): null {
              let source = "{\\"title\\":\\"t\\",\\"inner\\":{\\"value\\":2},\\"items\\":[1,2],\\"maybe\\":\\"m\\",\\"optionalNull\\":null,\\"dynamic\\":{\\"k\\":true}}";
              let decoded = Outer$fromJson(source);
              if (decoded === null) { report("J1", "decode"); }
              let encoded = Outer$toJson(decoded as Outer);
              if (encoded !== "{\\"title\\":\\"t\\",\\"inner\\":{\\"value\\":2.0},\\"items\\":[1.0,2.0],\\"maybe\\":\\"m\\",\\"optionalNull\\":null,\\"dynamic\\":{\\"k\\":true}}") {
                report("J2", encoded);
              }

              let missingInner = Outer$fromJson("{\\"title\\":\\"t\\",\\"items\\":[],\\"maybe\\":\\"m\\",\\"optionalNull\\":null,\\"dynamic\\":{}}");
              if (missingInner !== null) { report("J3", "missing required field"); }
              let extraKey = Outer$fromJson("{\\"title\\":\\"t\\",\\"inner\\":{\\"value\\":1},\\"items\\":[],\\"maybe\\":\\"m\\",\\"optionalNull\\":null,\\"dynamic\\":{},\\"zz\\":1}");
              if (extraKey !== null) { report("J4", "extra key"); }
              let duplicate = Outer$fromJson("{\\"title\\":\\"t\\",\\"inner\\":{\\"value\\":1,\\"value\\":2},\\"items\\":[],\\"maybe\\":\\"m\\",\\"optionalNull\\":null,\\"dynamic\\":{}}");
              if (duplicate !== null) { report("J5", "nested duplicate"); }
              let wrongInner = Outer$fromJson("{\\"title\\":\\"t\\",\\"inner\\":{\\"value\\":\\"x\\"},\\"items\\":[],\\"maybe\\":\\"m\\",\\"optionalNull\\":null,\\"dynamic\\":{}}");
              if (wrongInner !== null) { report("J6", "wrong field type"); }
              let wrongItem = Outer$fromJson("{\\"title\\":\\"t\\",\\"inner\\":{\\"value\\":1},\\"items\\":[1,\\"x\\"],\\"maybe\\":\\"m\\",\\"optionalNull\\":null,\\"dynamic\\":{}}");
              if (wrongItem !== null) { report("J7", "wrong array element"); }
              let malformed = Outer$fromJson("{");
              if (malformed !== null) { report("J8", "malformed document"); }

              let requiredUndefined = Undefined$fromJson("{\\"name\\":\\"a\\"}");
              if (requiredUndefined !== null) { report("J9", "required undefined accepted"); }
              let present = Undefined$fromJson("{\\"name\\":\\"a\\",\\"maybe\\":\\"m\\"}");
              if (present === null) { report("J10", "decode"); }
              let presentText = Undefined$toJson(present as Undefined);
              if (presentText !== "{\\"name\\":\\"a\\",\\"maybe\\":\\"m\\"}") {
                report("J11", presentText);
              }

              let deepSource = "{\\"title\\":\\"t\\",\\"inner\\":{\\"value\\":1},\\"items\\":[],\\"maybe\\":\\"m\\",\\"optionalNull\\":null,\\"dynamic\\":{\\"k\\":DEEP_DOCUMENT}}";
              let deepExpected = "{\\"title\\":\\"t\\",\\"inner\\":{\\"value\\":1.0},\\"items\\":[],\\"maybe\\":\\"m\\",\\"optionalNull\\":null,\\"dynamic\\":{\\"k\\":DEEP_ENCODED}}";
              let deepDecoded = Outer$fromJson(deepSource);
              if (deepDecoded === null) { report("J12", "deep decode"); }
              let deepText = Outer$toJson(deepDecoded as Outer);
              if (deepText !== deepExpected) { report("J13", deepText); }

              let loop: Record<string, unknown> = { name: "a" };
              loop["self"] = loop;
              let cyclic: Outer = {
                title: "t",
                inner: { value: 1 },
                items: [],
                maybe: "m",
                optionalNull: null,
                dynamic: loop,
              };
              let cycleArm: boolean = false;
              try {
                let cyclicText = Outer$toJson(cyclic);
                if (cyclicText !== "cycles encode") { report("J14", cyclicText); }
              } catch (e) {
                if (e instanceof Error) {
                  if (e.code !== "E8001") { report("J15", e.code); }
                  if (e.message !== "cyclic value cannot be encoded as JSON") {
                    report("J16", e.message);
                  }
                  cycleArm = true;
                }
              }
              if (!cycleArm) { report("J17", "cycle arm"); }

              let pairSource = "{\\"numbers\\":[1,2],\\"flags\\":[true,false],\\"texts\\":[\\"a\\",\\"b\\"],\\"nested\\":[[\\"a\\"],[\\"b\\",\\"c\\"]],\\"mixed\\":[1,null,3],\\"options\\":[true,null],\\"inner\\":[{\\"value\\":4}],\\"rows\\":[{\\"a\\":1}]}";
              let pairDecoded = Arrays$fromJson(pairSource);
              if (pairDecoded === null) { report("J18", "array decode"); }
              let pair = pairDecoded as Arrays;
              if (pair.numbers.length !== 2) { report("J19", "numbers length"); }
              if (pair.numbers[0] !== 1) { report("J20", "numbers first"); }
              if (pair.numbers[1] !== 2) { report("J21", "numbers second"); }
              if (pair.flags[0] !== true) { report("J22", "flags first"); }
              if (pair.flags[1] !== false) { report("J23", "flags second"); }
              if (pair.texts[0] !== "a") { report("J24", "texts first"); }
              if (pair.texts[1] !== "b") { report("J25", "texts second"); }
              if (pair.nested[1].length !== 2) { report("J26", "nested length"); }
              if (pair.nested[1][0] !== "b") { report("J27", "nested first"); }
              if (pair.nested[1][1] !== "c") { report("J28", "nested second"); }
              let mixedFirst: number = pair.mixed[0] as number;
              if (mixedFirst !== 1) { report("J29", "nullish number first"); }
              let mixedSecond = pair.mixed[1];
              if (!(mixedSecond === null)) { report("J30", "nullish number null"); }
              let mixedThird: number = pair.mixed[2] as number;
              if (mixedThird !== 3) { report("J31", "nullish number third"); }
              let option = pair.options[1];
              if (!(option === null)) { report("J36", "nullish boolean null"); }
              let optionFirst: boolean = pair.options[0] as boolean;
              if (optionFirst !== true) { report("J37", "nullish boolean first"); }
              if (pair.inner[0].value !== 4) { report("J34", "class element"); }
              let rowKey: string = "a";
              let rowValue = pair.rows[0][rowKey];
              let rowNumber: number = rowValue as number;
              if (rowNumber !== 1) { report("J35", "record element"); }
              let elementSum: number = 0;
              for (let value of pair.numbers) { elementSum = elementSum + value; }
              if (elementSum !== 3) { report("J32", "decoded for-of"); }
              let pairText = Arrays$toJson(pair);
              if (pairText !== "{\\"numbers\\":[1.0,2.0],\\"flags\\":[true,false],\\"texts\\":[\\"a\\",\\"b\\"],\\"nested\\":[[\\"a\\"],[\\"b\\",\\"c\\"]],\\"mixed\\":[1.0,null,3.0],\\"options\\":[true,null],\\"inner\\":[{\\"value\\":4.0}],\\"rows\\":[{\\"a\\":1.0}]}") {
                report("J33", pairText);
              }

              throw { code: "DONE", message: `${encoded}|${presentText}` };
            }
            """.replace("DEEP_ENCODED", deepEncoded)
                .replace("DEEP_DOCUMENT", deepDocument);
    }

    /**
     * The executed persistent-datum fixture (C3, M1/M2): one class whose
     * {@code unknown} field holds a string, an array and a class instance, each
     * read back after the growth policy has started and completed collection
     * cycles, and its later iterations store a freshly allocated array into the
     * existing instance while those cycles run. A reference map that names the
     * datum's tag word instead of its own {@code reference} member makes the
     * collector read that word as a heap address, so this fixture crashes or
     * loses a reachable referent here instead of answering the stored value;
     * the insertion barrier of the same store site is asserted structurally
     * (the emitted call sites and their declared entries), because the
     * allocate-black policy and the atomic-phase root rescan keep a rooted
     * fresh referent alive even without it.
     */
    private static String holdersFixture() {
        return """
            class Holder {
              data: unknown;
              text: string;
              count: number;
            }

            class Inner {
              name: string;
            }

            function makeInner(): Inner {
              return { name: "inner" };
            }

            function report(code: string, text: string): void {
              throw { code: code, message: `${text}` };
            }

            function churn(): string {
              let sink: string = "";
              let round: number = 0.0;
              while (round < 4000.0) {
                sink = `${sink}x`;
                if (round > 64.0) { sink = ""; }
                round = round + 1.0;
              }
              return sink;
            }

            export function main(): null {
              let holder: Holder = { data: "start", text: "text", count: 1 };
              let pressure: string = churn();
              let stored: string = holder.data as string;
              if (stored !== "start") { report("G1", stored); }
              if (holder.text !== "text") { report("G2", holder.text); }
              if (holder.count !== 1) { report("G3", "count"); }

              let round: number = 0.0;
              while (round < 4000.0) {
                holder.data = [round, round + 1.0];
                holder.text = "text";
                holder.count = round;
                round = round + 1.0;
              }
              let last: number[] = holder.data as number[];
              if (last.length !== 2) { report("G4", "array length"); }
              if (last[0] !== 3999.0) { report("G5", "last first"); }
              if (last[1] !== 4000.0) { report("G6", "last second"); }
              if (holder.count !== 3999.0) { report("G7", "count"); }
              if (holder.text !== "text") { report("G8", holder.text); }

              holder.data = makeInner();
              let again: string = churn();
              let made: Inner = holder.data as Inner;
              if (made.name !== "inner") { report("G9", made.name); }
              if (again !== pressure) { report("G10", "churn"); }

              throw { code: "DONE", message: `${stored}|${made.name}` };
            }
            """;
    }

    /**
     * The executed {@code null}/{@code undefined} fixture (C3, R2, R6): every
     * declared position of those two types as a value of its own type — an
     * optional {@code null} field reading absent as {@code undefined} and
     * present as null, required {@code null}/{@code undefined} fields,
     * present-undefined optional metadata, {@code null[]}, {@code null[][]} and
     * {@code (null|undefined)[]} literals with reads, writes, appends and a
     * {@code FOR_EACH}, the canonical {@code null|undefined} variable and the
     * parameter/return positions, and the generated walks over a decoded and a
     * constructed {@code @jsonable} instance carrying those fields.
     */
    private static String nullishFixture() {
        return """
            class Box {
              n?: null;
              value: null;
              omitted: undefined;
            }

            class Mixed {
              s?: null | undefined;
            }

            // @jsonable
            export class NullableDoc {
              values: (null | undefined)[];
              nulls: null[];
              rows: null[][];
              optional: null;
            }

            // @jsonable
            export class UndefinedDoc {
              name: string;
              omitted: undefined;
            }

            function pick(flag: boolean): null | undefined {
              if (flag) { return null; }
              return undefined;
            }

            function echo(value: null | undefined): null | undefined {
              return value;
            }

            function report(code: string, text: string): void {
              throw { code: code, message: `${text}` };
            }

            export function main(): null {
              let absent: Box = { value: null, omitted: undefined };
              if (Object.hasOwn(absent, "n")) { report("D1", "absent own"); }
              let readAbsent = absent.n;
              if (!(readAbsent === undefined)) { report("D2", "absent read"); }
              let present: Box = { n: null, value: null, omitted: undefined };
              if (!Object.hasOwn(present, "n")) { report("D3", "present own"); }
              let readPresent = present.n;
              if (!(readPresent === null)) { report("D4", "present read"); }
              if (!(present.value === null)) { report("D5", "required null"); }
              if (!(present.omitted === undefined)) { report("D6", "required undefined"); }

              let missing: Mixed = {};
              if (Object.hasOwn(missing, "s")) { report("D7", "mixed absent own"); }
              let missingRead = missing.s;
              if (!(missingRead === undefined)) { report("D8", "mixed absent read"); }
              let nullMixed: Mixed = { s: null };
              if (!Object.hasOwn(nullMixed, "s")) { report("D9", "mixed null own"); }
              if (!(nullMixed.s === null)) { report("D10", "mixed null read"); }
              let undefinedMixed: Mixed = { s: undefined };
              if (!Object.hasOwn(undefinedMixed, "s")) { report("D11", "mixed undefined own"); }
              if (!(undefinedMixed.s === undefined)) { report("D12", "mixed undefined read"); }

              let nulls: null[] = [null, null];
              if (nulls.length !== 2) { report("D13", "null length"); }
              if (!(nulls[0] === null)) { report("D14", "null element"); }
              nulls[2] = null;
              if (nulls.length !== 3) { report("D15", "null append"); }
              let counted: number = 0;
              for (let value of nulls) {
                if (!(value === null)) { report("D16", "null iteration"); }
                counted = counted + 1;
              }
              if (counted !== 3) { report("D17", "null count"); }

              let rows: null[][] = [nulls];
              if (rows.length !== 1) { report("D18", "rows length"); }
              if (rows[0].length !== 3) { report("D19", "rows inner length"); }
              if (!(rows[0][0] === null)) { report("D20", "rows element"); }

              let states: (null | undefined)[] = [null, undefined];
              if (states.length !== 2) { report("D21", "states length"); }
              if (!(states[0] === null)) { report("D22", "states null"); }
              if (!(states[1] === undefined)) { report("D23", "states undefined"); }
              states[2] = undefined;
              if (!(states[2] === undefined)) { report("D24", "states append"); }
              let seen: number = 0;
              for (let state of states) {
                if (state === null) { seen = seen + 1; }
              }
              if (seen !== 1) { report("D25", "states traversal"); }

              let declared: null | undefined = undefined;
              if (!(declared === undefined)) { report("D26", "declared undefined"); }
              declared = null;
              if (!(declared === null)) { report("D27", "declared null"); }

              let pickedNull = pick(true);
              if (!(pickedNull === null)) { report("D28", "return null"); }
              let pickedUndefined = pick(false);
              if (!(pickedUndefined === undefined)) { report("D29", "return undefined"); }
              let echoed = echo(undefined);
              if (!(echoed === undefined)) { report("D30", "parameter undefined"); }
              let echoedNull = echo(null);
              if (!(echoedNull === null)) { report("D31", "parameter null"); }

              let docSource = "{\\"values\\":[null],\\"nulls\\":[null,null],\\"rows\\":[[null]],\\"optional\\":null}";
              let decoded = NullableDoc$fromJson(docSource);
              if (decoded === null) { report("D32", "nullable decode"); }
              let doc = decoded as NullableDoc;
              if (doc.values.length !== 1) { report("D33", "doc values"); }
              if (!(doc.values[0] === null)) { report("D34", "doc value null"); }
              if (doc.nulls.length !== 2) { report("D35", "doc nulls"); }
              if (!(doc.nulls[1] === null)) { report("D36", "doc null element"); }
              if (doc.rows[0].length !== 1) { report("D37", "doc rows"); }
              if (!(doc.optional === null)) { report("D38", "doc optional"); }
              let docText = NullableDoc$toJson(doc);
              if (docText !== docSource) { report("D39", docText); }
              let built: NullableDoc = {
                values: [null, undefined],
                nulls: [null],
                rows: [[null, null]],
                optional: null,
              };
              let builtText = NullableDoc$toJson(built);
              if (builtText !== "{\\"values\\":[null,null],\\"nulls\\":[null],\\"rows\\":[[null,null]],\\"optional\\":null}") {
                report("D40", builtText);
              }
              let wrongElement = NullableDoc$fromJson("{\\"values\\":[null,1],\\"nulls\\":[],\\"rows\\":[],\\"optional\\":null}");
              if (wrongElement !== null) { report("D41", "wrong element"); }
              let missingRequired = NullableDoc$fromJson("{\\"values\\":[],\\"nulls\\":[],\\"rows\\":[]}");
              if (missingRequired !== null) { report("D42", "missing required"); }

              let undefinedDoc: UndefinedDoc = { name: "u", omitted: undefined };
              let undefinedText = UndefinedDoc$toJson(undefinedDoc);
              if (undefinedText !== "{\\"name\\":\\"u\\"}") { report("D43", undefinedText); }
              let requiredUndefined = UndefinedDoc$fromJson("{\\"name\\":\\"u\\"}");
              if (requiredUndefined !== null) { report("D44", "required undefined"); }
              let nullUndefined = UndefinedDoc$fromJson("{\\"name\\":\\"u\\",\\"omitted\\":null}");
              if (nullUndefined !== null) { report("D45", "null for undefined"); }

              throw { code: "DONE", message: `${docText}|${builtText}|${undefinedText}` };
            }
            """;
    }

    /**
     * The executed optional-{@code unknown} field fixture (C3, R2, R4):
     * {@code unknown} absorbs the nullish members, so an optional field of that
     * declared type records the persistent-datum result type rather than a
     * {@code Nullable}. Absence must still project to {@code undefined} without
     * erasing the presence bit, and every permitted present value (a string, an
     * array, {@code null} and present-{@code undefined}) must read back as the
     * same data at both optimization levels and under collector stress.
     */
    private static String optionalUnknownFixture() {
        return """
            class Carrier {
              data?: unknown;
              text: string;
            }

            function makeList(): number[] {
              return [1.0, 2.0];
            }

            function report(code: string, text: string): void {
              throw { code: code, message: `${text}` };
            }

            export function main(): null {
              let carrier: Carrier = { text: "t" };
              if (Object.hasOwn(carrier, "data")) { report("U1", "absent own"); }
              let absent = carrier.data;
              if (!(absent === undefined)) { report("U2", "absent read"); }
              if (carrier.text !== "t") { report("U3", carrier.text); }

              carrier.data = "value";
              if (!Object.hasOwn(carrier, "data")) { report("U4", "string own"); }
              let text = carrier.data as string;
              if (text !== "value") { report("U5", text); }

              carrier.data = makeList();
              let list = carrier.data as number[];
              if (list.length !== 2) { report("U6", "array length"); }
              if (list[0] !== 1.0) { report("U7", "array first"); }
              if (list[1] !== 2.0) { report("U8", "array second"); }

              carrier.data = null;
              if (!(carrier.data === null)) { report("U9", "null read"); }

              carrier.data = undefined;
              if (!Object.hasOwn(carrier, "data")) { report("U10", "undefined own"); }
              if (!(carrier.data === undefined)) { report("U11", "undefined read"); }

              throw { code: "DONE", message: `${text}|${carrier.text}` };
            }
            """;
    }

    /**
     * The executed narrowing fixture (R4, docs/spec-v1.5.md "Narrowing and
     * checked assertions"): a refined identifier read of every staged base kind
     * — {@code ?number}, {@code ?number|undefined}, {@code ?boolean},
     * {@code ?string}, {@code ?Class}, {@code ?T[]} — is consumable inside the
     * narrowing block, in a parameter position, and for the declared member the
     * branch excludes, while the un-refined binding read stays the nullish
     * value. An {@code unknown} binding narrowed by {@code instanceof} to the
     * builtin Error is the same surface.
     */
    private static String narrowingFixture() {
        return """
            class Point {
              x: number;
            }

            class NotFound {
              reason: string;
            }

            function report(code: string): void {
              throw { code: code, message: "narrowing" };
            }

            function pick(value: number | undefined): number {
              if (value === undefined) {
                return -1;
              } else {
                let narrowed: number = value;
                return narrowed;
              }
            }

            function classify(candidate: unknown): string {
              let result: string = "other";
              if (candidate instanceof Error) {
                result = candidate.code;
              } else {
                if (candidate instanceof NotFound) {
                  result = candidate.reason;
                }
              }
              return result;
            }

            export function main(): null {
              let n: number | null = 41;
              let total: number = 0;
              if (n === null) { total = total + 100; } else { total = total + n; }
              if (total !== 41) { report("K1"); }

              let u: number | undefined = 1;
              if (u === undefined) { total = total + 100; } else { total = total + u; }
              if (total !== 42) { report("K2"); }

              let flag: boolean | null = true;
              let bits: number = 0;
              if (flag === null) { bits = 7; } else { if (flag) { bits = 1; } }
              if (bits !== 1) { report("K3"); }

              let text: string | undefined = "ab";
              let seen: number = 0;
              if (text === undefined) { seen = 7; } else { if (text === "ab") { seen = 2; } }
              if (seen !== 2) { report("K4"); }

              let point: Point | null = { x: 5 };
              let px: number = 0;
              if (point === null) { px = 7; } else { px = point.x; }
              if (px !== 5) { report("K5"); }

              let list: number[] | null = [3, 4];
              let len: number = 0;
              if (list === null) { len = 7; } else { len = list.length; }
              if (len !== 2) { report("K6"); }

              let walked: number = 0;
              let maybe: number | null = 9;
              if (maybe !== null) { walked = pick(maybe); }
              if (walked !== 9) { report("K7"); }

              let none: number | null = null;
              let after: number = 0;
              if (none === null) { after = 1; } else { after = none; }
              if (after !== 1) { report("K8"); }

              let mixed: (number | null)[] = [7, null];
              let counted: number = 0;
              for (let item of mixed) {
                if (item === null) {
                  counted = counted + 100;
                } else {
                  counted = counted + item;
                }
              }
              if (counted !== 107) { report("K9"); }

              let gone: number | undefined = undefined;
              let kept: number = 0;
              if (gone === undefined) { kept = 2; } else { kept = gone; }
              if (kept !== 2) { report("K10"); }

              let saved: unknown = null;
              try {
                throw { code: "K11", message: "narrowed" };
              } catch (caught) {
                if (caught instanceof Error) { saved = caught; }
              }
              if (classify(saved) !== "K11") { report("K12"); }
              let missing: unknown = null;
              if (classify(missing) !== "other") { report("K13"); }

              throw { code: "DONE", message: "narrowing" };
            }
            """;
    }

    /**
     * The executed Error-routing fixture (C4, R2, R5): THROW/TRY_CATCH/
     * rethrow/DISCARD identity, the caught boundary tuple's code, prior effects,
     * the successful-assignment result, and root restoration across a loop with
     * a nested call.
     */
    private static String routingFixture() {
        return """
            class ErrorBag {
              data: unknown;
              failure: Error;
            }

            function report(code: string, text: string): void {
              throw { code: code, message: `${text}` };
            }

            function leaf(depth: number): number {
              let small: number[] = [1];
              let index: number = depth + 5;
              let value: number = small[index];
              return value;
            }

            function middle(): number {
              return leaf(0);
            }

            export function main(): null {
              let constructed: Error = { code: "E5", message: "constructed" };
              let marks: number[] = [];
              marks[0] = 1;
              let assigned: number = marks[0] = 7;
              if (assigned !== 7) { report("E1", "assignment result"); }
              if (marks[0] !== 7) { report("E2", "assignment storage"); }

              let restored: number = 0;
              let rounds: number[] = [1, 2, 3];
              for (let round of rounds) {
                try {
                  let value: number = middle();
                  restored = restored + value;
                } catch (e) {
                  if (e instanceof Error) {
                    if (e.code !== "E8002") { report("E3", e.code); }
                    if (e.message !== "array index out of bounds") {
                      report("E4", e.message);
                    }
                    restored = restored + 1;
                  }
                }
              }
              if (restored !== 3) { report("E5", "root restoration"); }
              if (marks[0] !== 7) { report("E6", "prior effects"); }

              try {
                try {
                  throw { code: "E1", message: "m" };
                } catch (e) {
                  if (e instanceof Error) {
                    e.code = "E2";
                    throw e;
                  }
                }
              } catch (f) {
                if (f instanceof Error) {
                  if (f.code !== "E2") { report("E7", f.code); }
                  if (f.message !== "m") { report("E8", f.message); }
                }
              }

              try {
                throw { code: "D1", message: "discarded" };
              } catch (e) {
                let discarded = e;
              }

              // C3: the builtin Error instance is its own `deal_error*`
              // record, so every route that puts a statically Error-typed
              // value into an `unknown` position publishes that pointer — a
              // local, a class field, an array element, `typeof`, and the
              // canonical catch-then-narrow-then-save pattern.
              let boxed: unknown = constructed;
              if (typeof boxed !== "object") { report("E9", "typeof"); }
              if (!(boxed instanceof Error)) { report("E10", "instanceof"); }
              let back: Error = boxed as Error;
              if (back.code !== "E5") { report("E11", back.code); }
              if (back.message !== "constructed") { report("E12", back.message); }
              if (typeof constructed !== "object") { report("E13", "typeof"); }

              let carrier: ErrorBag = { data: constructed, failure: { code: "E6", message: "field" } };
              let fromField: Error = carrier.data as Error;
              if (fromField.code !== "E5") { report("E14", fromField.code); }
              if (carrier.failure.code !== "E6") { report("E15", carrier.failure.code); }
              carrier.data = constructed;
              if (!(carrier.data instanceof Error)) { report("E16", "field"); }

              let elements: unknown[] = [constructed];
              let fromElement: Error = elements[0] as Error;
              if (fromElement.code !== "E5") { report("E17", fromElement.code); }

              let saved: unknown = null;
              try {
                throw { code: "E7", message: "saved" };
              } catch (caught) {
                if (caught instanceof Error) {
                  saved = caught;
                }
              }
              if (!(saved instanceof Error)) { report("E18", "saved"); }
              let relayed: Error = saved as Error;
              if (relayed.code !== "E7") { report("E19", relayed.code); }

              throw { code: "DONE", message: "routing" };
            }
            """;
    }

    /**
     * The executed IEEE and unary-selector matrix (C6): non-trapping binary64
     * arithmetic ({@code +}, {@code -}, {@code *}, {@code /}), {@code NaN !==
     * NaN}, {@code !(NaN === NaN)}, every ordered predicate against NaN
     * ({@code <} {@code <=} {@code >} {@code >=}) plus the finite {@code >=}
     * results that separate it from {@code >}, signed zero distinguished by its
     * reciprocal sign, literal rounding, the {@code BOOL_NOT} arm, and
     * {@code NUMBER_NEG} on {@code NaN}, both zeros and both infinities.
     */
    private static String ieeeFixture() {
        return """
            function report(code: string, text: string): void {
              throw { code: code, message: `${text}` };
            }

            export function main(): null {
              let zero: number = 0.0;
              let nan: number = zero / zero;
              let positiveInfinity: number = 1.0 / zero;
              let negativeInfinity: number = -1.0 / zero;

              if (!(nan !== nan)) { report("I1", "NaN !=="); }
              if (nan === nan) { report("I2", "NaN ==="); }
              if (!(positiveInfinity > 0.0)) { report("I3", "positive infinity"); }
              if (!(negativeInfinity < 0.0)) { report("I4", "negative infinity"); }
              if (positiveInfinity === negativeInfinity) {
                report("I5", "infinities equal");
              }
              let less: boolean = nan < 1.0;
              if (less) { report("I6", "NaN <"); }
              let greater: boolean = nan > 1.0;
              if (greater) { report("I7", "NaN >"); }
              let atMost: boolean = nan <= 1.0;
              if (atMost) { report("I8", "NaN <="); }
              let atLeast: boolean = nan >= 1.0;
              if (atLeast) { report("I9", "NaN >="); }
              let atLeastItself: boolean = nan >= nan;
              if (atLeastItself) { report("I10", "NaN >= NaN"); }
              // The ordered `>=` predicate itself: true for equal finite
              // operands and false below the bound, so it is neither the
              // strict `>` arm nor an unconditional true.
              let atLeastEqual: boolean = 5.0 >= 5.0;
              if (!atLeastEqual) { report("I11", "number >= equal"); }
              let aboveBound: boolean = 5.25 >= 5.0;
              if (!aboveBound) { report("I12", "number >= above"); }
              let belowBound: boolean = 4.75 >= 5.0;
              if (belowBound) { report("I13", "number >= below"); }

              let negativeZero: number = -zero;
              if (!(negativeZero === zero)) {
                report("I14", "signed zero equality");
              }
              let reciprocal: number = 1.0 / negativeZero;
              if (!(reciprocal < 0.0)) { report("I15", "signed zero sign"); }
              let positiveReciprocal: number = 1.0 / zero;
              if (!(positiveReciprocal > 0.0)) { report("I16", "zero sign"); }

              let sum: number = 0.1 + 0.2;
              if (sum === 0.3) { report("I17", "literal rounding"); }
              if (sum !== 0.30000000000000004) {
                report("I18", "binary64 sum");
              }

              let negated: number = -nan;
              if (negated === negated) { report("I19", "negated NaN"); }
              let flag: boolean = true;
              let flipped: boolean = !flag;
              if (flipped) { report("I20", "boolean not"); }
              let twice: boolean = !(!flag);
              if (!twice) { report("I21", "double not"); }

              // NUMBER_NEG on both infinities: the result must be the opposite
              // infinity and its sign must survive into the reciprocal.
              let negatedInfinity: number = -positiveInfinity;
              if (!(negatedInfinity === negativeInfinity)) {
                report("I22", "negated infinity");
              }
              let negatedNegativeInfinity: number = -negativeInfinity;
              if (!(negatedNegativeInfinity === positiveInfinity)) {
                report("I23", "negated -infinity");
              }
              let negatedInfinitySign: number = 1.0 / (1.0 / negatedInfinity);
              if (!(negatedInfinitySign < 0.0)) {
                report("I24", "negated infinity sign");
              }
              let negatedNegativeSign: number =
                1.0 / (1.0 / negatedNegativeInfinity);
              if (!(negatedNegativeSign > 0.0)) {
                report("I25", "negated -infinity sign");
              }

              // NUMBER_SUB / NUMBER_MUL: one finite pair plus one pair whose
              // result is NaN only when the operand plumbing is unconditional.
              let difference: number = 5.5 - 2.25;
              if (difference !== 3.25) { report("I26", "subtraction"); }
              let product: number = 2.5 * 4.0;
              if (product !== 10.0) { report("I27", "multiplication"); }
              let infiniteDifference: number =
                positiveInfinity - positiveInfinity;
              if (infiniteDifference === infiniteDifference) {
                report("I28", "infinity minus infinity");
              }
              let infiniteProduct: number = positiveInfinity * zero;
              if (infiniteProduct === infiniteProduct) {
                report("I29", "infinity times zero");
              }

              throw { code: "DONE", message: "ieee" };
            }
            """;
    }

    /** The driver fixture: one assertion failure three activations deep. */
    private static String accessorFixture() {
        return """
            function inner(mixed: unknown): number {
              return mixed as number;
            }

            function middle(mixed: unknown): number {
              return inner(mixed);
            }

            export function main(): null {
              let text: string = "not a number";
              let value: number = middle(text);
              return null;
            }
            """;
    }

    /** The driver unit: the R9 accessors over the carried outcome. */
    private static String driverSource() {
        return """
            #include "deal/native/entry.h"
            #include <stdio.h>

            static const char *text_of(int present, const char *text) {
                return present ? text : "-";
            }

            int main(void) {
                deal_outcome started = deal_project_start();
                if (started.error != NULL) {
                    printf("start=failed\\n");
                    return 3;
                }
                deal_outcome outcome = deal_project_run_main();
                if (outcome.error == NULL) {
                    printf("ok\\n");
                    return 0;
                }
                const char *code = NULL;
                const char *message = NULL;
                const char *file = NULL;
                const char *expected = NULL;
                const char *actual = NULL;
                int32_t line = 0;
                int32_t column = 0;
                int32_t frames = 0;
                printf("code=%s\\n", text_of(deal_error_code(outcome.error, &code), code));
                printf("message=%s\\n",
                    text_of(deal_error_message(outcome.error, &message), message));
                printf("file=%s\\n",
                    text_of(deal_error_source_file(outcome.error, &file), file));
                printf("line=%d\\n",
                    deal_error_line(outcome.error, &line) ? (int)line : -1);
                printf("column=%d\\n",
                    deal_error_column(outcome.error, &column) ? (int)column : -1);
                printf("expected=%s\\n",
                    text_of(deal_error_expected(outcome.error, &expected), expected));
                printf("actual=%s\\n",
                    text_of(deal_error_actual(outcome.error, &actual), actual));
                printf("frames=%d\\n", deal_error_frame_count(outcome.error, &frames)
                    ? (int)frames : -1);
                for (int32_t index = 0; index < frames; index += 1) {
                    const char *label = NULL;
                    if (deal_error_frame(outcome.error, index, &label)) {
                        printf("frame%d=%s\\n", (int)index, label);
                    }
                }
                return 0;
            }
            """;
    }

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("=== LLVM Data/Class Emission Tests (ISSUE-0910) ===\n");
        Path scratch = NativeCompiler.executableScratch("llvm-data-class");
        try {
            Path containers = compileProject(scratch, "containers",
                containersFixture());
            String module = Files.readString(containers.resolve("main.ll"),
                StandardCharsets.UTF_8);
            testRenderedFamilies(module);
            testClassLayoutMetadata(module);
            testDatumReferenceMap(scratch);
            testSingletonSlotStorage(scratch);
            testErrorValueRepresentation(scratch);
            testDeterminism(scratch);
            testExecutedFixtures(scratch);
            testAccessorProjection(scratch);
        } finally {
            deleteRecursively(scratch);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== LLVM Data/Class Emission Tests Passed ===");
    }

    // =========================================================================
    // 1. Every produced op family renders its own entry
    // =========================================================================

    private static void testRenderedFamilies(String module) {
        System.out.println("-- every produced family renders --");
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a string literal", "@deal_text_new(");
        expected.put("a string template", "@deal_text_concat(");
        expected.put("a typed array construction",
            "@deal_container_array_new_typed(");
        expected.put("an array element append", "@deal_container_array_append(");
        expected.put("a record construction", "@deal_container_table_new(");
        expected.put("a record key write", "@deal_container_table_write(");
        expected.put("a record key delete", "@deal_container_table_delete(");
        expected.put("a record key presence test", "@deal_container_table_has(");
        expected.put("the discrete-position validation",
            "@deal_container_position(");
        expected.put("the read element access", "@deal_container_array_element(");
        expected.put("the array length projection",
            "@deal_container_array_count(");
        expected.put("the per-layout descriptor atoms", "@deal_descriptor_intern(");
        expected.put("the persistent element descriptor",
            "@deal_descriptor_reference(");
        expected.put("the emitted layout table", "@deal_layout_install(");
        expected.put("the class construction validation",
            "@deal_class_construct(");
        expected.put("the instance allocation", "@deal_alloc(");
        expected.put("the builtin Error construction", "@deal_error_create_strings(");
        expected.put("the array position arm", "@deal_error_array_position(");
        expected.put("a class field store barrier", "call void @deal_store_barrier(");
        expected.put("the emitted class plans", "deal_class_plan_lookup");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            check(module.contains(entry.getValue()), "the module renders "
                + entry.getKey() + " (" + entry.getValue() + ")");
        }
        for (String option : List.of("i32 " + LlvmTypeMapper.PRESENTATION_DATUM
                + ", i32*", "%struct.deal_class_field", "%struct.deal_layout",
                "%struct.deal_json_class_plan")) {
            check(module.contains(option), "the module carries " + option);
        }
        // A process entry and a runtime-owned symbol stay out of the module.
        check(!Pattern.compile("(?m)^define [^\\n]* @main\\(").matcher(module)
                .find(), "the emitted module defines no process entry");
        check(!Pattern.compile("(?m)^define [^\\n]* @deal_project_main\\(")
                .matcher(module).find(),
            "the emitted module defines no runtime terminal");
    }

    /**
     * The emitted per-layout class metadata: the typed instance record (class
     * atom, optional-field presence bytes and one typed slot per declared
     * field), the layout record's ascending aligned reference offsets inside
     * the payload, and the declaration-order field table the runtime reads.
     */
    private static void testClassLayoutMetadata(String module) {
        System.out.println("-- the emitted class layout metadata --");
        Matcher records = Pattern.compile(
                "(?m)^(%struct\\.deal_class\\.[^ ]+) = type \\{ ([^}]*) \\}$")
            .matcher(module);
        int classes = 0;
        while (records.find()) {
            classes++;
            List<String> members = splitFields(records.group(2));
            check(members.size() >= 2, "the class record " + records.group(1)
                + " carries the class atom and its slots");
            checkEq("i8*", members.get(0).strip(),
                "the class record starts with the class atom pointer");
            check(members.get(1).strip().equals("i8"),
                "the class record carries one presence byte per optional field");
        }
        checkEq(1, classes, "every declared class carries one emitted"
            + " instance record");
        MatchResult layout = offsets(module, 0);
        check(layout.size() > 0, "the emitted layout metadata declares a payload"
            + " size");
        for (int offset : layout.references()) {
            check(offset % 8 == 0, "the reference offset " + offset + " is"
                + " 8-aligned");
            check(offset + 8 <= layout.size(), "the reference offset " + offset
                + " lies inside the " + layout.size() + " byte payload");
        }
        for (int index = 1; index < layout.references().size(); index++) {
            check(layout.references().get(index)
                    > layout.references().get(index - 1),
                "the reference map is ascending");
        }
        // The emitted start function initializes the class metadata before any
        // unit, and that initialization installs the layout table before it
        // fills the declared-field entries with their interned atoms (M1, R4).
        String start = functionOf(module, "deal_project_start");
        int plans = start.indexOf("call void @deal_class_plans_init()");
        int initialize = start.indexOf("call %struct.deal_outcome"
            + " @deal_module_init_");
        check(plans > 0 && initialize > plans, "the emitted class metadata is"
            + " initialized before any unit initialization");
        String plansInit = functionOf(module, "deal_class_plans_init");
        int install = plansInit.indexOf("call void @deal_layout_install(");
        int atom = plansInit.indexOf("call %struct.deal_descriptor*"
            + " @deal_descriptor_intern(");
        check(install > 0, "the emitted start installs its layout table");
        check(atom > install, "the descriptor atoms are interned after the"
            + " layout table is installed");
        check(module.contains("%struct.deal_class_field")
                && module.contains("%struct.deal_class_layout")
                && module.contains("%struct.deal_json_class_plan"),
            "the published class and plan records are the ones the runtime"
                + " declares");

    }

    /**
     * The emitted storage of a declared {@code null}/{@code undefined}
     * position (C3): one reference slot holding the distinct runtime singleton,
     * in a class instance and in an array, so the declared type is a value of
     * its own type everywhere it is emitted or decoded.
     */
    private static void testSingletonSlotStorage(Path scratch) throws Exception {
        System.out.println("-- the declared null/undefined slot storage (C3) --");
        Path published = compileProject(scratch, "nullish-structure",
            nullishFixture());
        String module = Files.readString(published.resolve("main.ll"),
            StandardCharsets.UTF_8);
        // Box { n?: null; value: null; omitted: undefined }: the class atom,
        // one presence byte for the optional field, and one singleton pointer
        // slot per declared field.
        check(module.contains("%struct.deal_class._src_main_deal_Box.")
                && module.contains(" = type { i8*, i8, i8*, i8*, i8* }"),
            "a declared null/undefined field occupies one singleton pointer"
                + " slot beside the presence byte");
        MatchResult box = offsets(module, 0);
        checkEq(40, box.size(), "the singleton field slots are pointer-sized");
        checkEq(List.of(16, 24, 32), box.references(), "every null/undefined"
            + " field slot is one traced reference slot after the presence"
            + " byte (the runtime never traces a singleton as heap storage)");
        // The canonical `null|undefined` optional field is one singleton slot
        // as well, never presence metadata standing in for a value.
        check(module.contains("%struct.deal_class._src_main_deal_Mixed.")
                && module.contains(" = type { i8*, i8, i8* }"),
            "a `null|undefined` field is one singleton pointer slot");
        // A null/undefined array element is the published reference element
        // storage, the same layout a managed-reference element uses, so the
        // emitted element access and the runtime JSON rebuild agree.
        String reference = "@deal_container_array_new_typed(i32 "
            + publishedElementLayout("DEAL_LAYOUT_ELEMENT_REFERENCE") + ",";
        check(count(module, reference) >= 4, "the null, null[][] and"
            + " (null|undefined)[] constructions record the published"
            + " reference element storage (" + reference + "): "
            + count(module, reference) + " call sites");
    }

    /**
     * The builtin {@code Error} value's representation (C3): the instance is
     * the runtime's own {@code deal_error*} record, so a class field declared
     * {@code Error} is one {@code deal_error*} slot and every route that boxes
     * a statically Error-typed value into an {@code unknown} position — a
     * variable declaration, a class literal field, a field write, an array
     * element and the {@code typeof} projection — publishes that same pointer
     * as the datum's managed payload. The module defines no per-layout record
     * for the builtin, so a layout-typed pointer here would be unparsable.
     */
    private static void testErrorValueRepresentation(Path scratch) throws Exception {
        System.out.println("-- the builtin Error value representation (C3) --");
        Path published = compileProject(scratch, "error-representation", """
            class Bag {
              data: unknown;
              failure: Error;
            }

            export function main(): null {
              let constructed: Error = { code: "C1", message: "m" };
              let boxed: unknown = constructed;
              let bag: Bag = { data: boxed, failure: constructed };
              let elements: unknown[] = [constructed];
              let kind: string = typeof constructed;
              return null;
            }
            """);
        String module = Files.readString(published.resolve("main.ll"),
            StandardCharsets.UTF_8);
        check(!module.contains("%struct.deal_class.Error."),
            "the emitted module defines no per-layout record for the builtin"
                + " Error (its representation is the runtime record)");
        check(module.contains("%struct.deal_class._src_main_deal_Bag.")
                && module.contains(" = type { i8*, %struct.deal_data,"
                    + " %struct.deal_error* }"),
            "an Error field is one `deal_error*` slot beside the datum slot");
        check(count(module, "bitcast %struct.deal_error* ") >= 5,
            "every boxing route publishes the Error record pointer as the"
                + " datum payload: " + count(module, "bitcast %struct.deal_error* ")
                + " bitcasts");
    }

    /** One published element-layout identity of {@code deal/native/memory.h}. */
    private static int publishedElementLayout(String name) throws IOException {
        String header = Files.readString(REPO.resolve("deal/native/memory.h"),
            StandardCharsets.UTF_8);
        Matcher macro = Pattern.compile("#define\\s+" + name + "\\s+(\\d+)")
            .matcher(header);
        if (!macro.find()) {
            throw new IllegalStateException(
                "the published header declares no element layout " + name);
        }
        return Integer.parseInt(macro.group(1));
    }

    /**
     * The emitted reference map of a persistent-datum slot (C3, M1/M2): the
     * {@code unknown} field's entry names the datum's own {@code reference}
     * member — its slot offset plus {@code offsetof(deal_data, reference)} as
     * the pinned toolchain computes it from the published record — never the
     * tag word the slot starts with, and every store into such a slot and into
     * a managed pointer slot runs its insertion barrier.
     */
    private static void testDatumReferenceMap(Path scratch) throws Exception {
        System.out.println("-- the persistent-datum reference map (C3, M1/M2) --");
        Path published = compileProject(scratch, "holders-structure",
            holdersFixture());
        String module = Files.readString(published.resolve("main.ll"),
            StandardCharsets.UTF_8);
        List<List<String>> records = classRecords(module);
        int datumClass = -1;
        for (int index = 0; index < records.size(); index++) {
            if (records.get(index).stream().anyMatch(member ->
                    member.strip().equals("%struct.deal_data"))) {
                checkEq(-1, datumClass, "exactly one declared class holds a"
                    + " persistent-datum slot");
                datumClass = index;
            }
        }
        check(datumClass >= 0, "a declared class carries the persistent-datum"
            + " slot of the fixture");
        if (datumClass < 0) {
            return;
        }
        // The declared fields after the class atom and the presence bytes; the
        // fixture declares none optional, so every member is one field slot.
        List<String> members = records.get(datumClass).subList(1,
            records.get(datumClass).size());
        checkEq(3, members.size(), "the fixture class emits one slot per"
            + " declared field");
        List<Integer> slots = new ArrayList<>();
        int offset = 8;                    // the class atom pointer
        for (String member : members) {
            offset = roundUp(offset,
                LlvmTypeMapper.alignmentOfIrType(member));
            slots.add(offset);
            offset += LlvmTypeMapper.sizeOfIrType(member);
        }
        int referenceOffset = publishedDatumReferenceOffset(scratch, published);
        // C3's datum record is `{ tag, reserved, number, reference }`, so the
        // managed member follows one u32 pair and one binary64 value.
        checkEq(16, referenceOffset, "the published datum carries its"
            + " reference member after tag, reserved and number");
        checkEq(List.of(slots.get(0) + referenceOffset, slots.get(1)),
            offsets(module, datumClass).references(),
            "the datum slot's reference-map entry names the datum's own"
                + " reference member, never its tag word");
        check(offsets(module, datumClass).references().get(0) != slots.get(0),
            "the datum slot's reference entry is not the slot start");
        // The allocation of a class instance stores through the same two
        // entries: the tag-aware barrier for the datum slot and the pointer
        // barrier for the managed slot, at construction and at every later
        // field write (M2).
        check(count(module, "call void @deal_store_barrier_data(") >= 2,
            "every store into a persistent-datum slot runs the tag-aware"
                + " insertion barrier");
        check(count(module, "call void @deal_store_barrier(") >= 2,
            "every store into a managed pointer slot runs the insertion"
                + " barrier");
        check(module.contains("declare void @deal_store_barrier(i8*, i8*)")
                && module.contains(
                    "declare void @deal_store_barrier_data(i8*, %struct.deal_data*)"),
            "the emitted module declares the published barrier entries");
    }

    /**
     * {@code offsetof(deal_data, reference)} as the pinned toolchain computes
     * it from the published header: a standalone probe built and run through
     * the same seam as every product.
     */
    private static int publishedDatumReferenceOffset(Path scratch,
                                                     Path published)
            throws Exception {
        Path source = scratch.resolve("deal_data_offset.c");
        Files.writeString(source, """
            #include "deal/native/deal_rt.h"
            #include <stddef.h>
            #include <stdio.h>

            int main(void) {
                printf("%zu\\n", offsetof(deal_data, reference));
                return 0;
            }
            """, StandardCharsets.UTF_8);
        Path product = scratch.resolve("deal_data_offset");
        NativeCompiler.Result build = NativeCompiler.link(published, 0,
            List.of(source), product);
        check(build.succeeded(), "the published datum layout probe builds: "
            + build.describe());
        if (!build.succeeded()) {
            return -1;
        }
        NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
            Map.of());
        checkEq(0, run.exitCode(), "the published datum layout probe runs: "
            + run.describe());
        return Integer.parseInt(run.stdout().strip());
    }

    /** Every emitted class instance record body, in canonical class order. */
    private static List<List<String>> classRecords(String module) {
        Matcher records = Pattern.compile(
                "(?m)^%struct\\.deal_class\\.[^ ]+ = type \\{ ([^}]*) \\}$")
            .matcher(module);
        List<List<String>> bodies = new ArrayList<>();
        while (records.find()) {
            bodies.add(splitFields(records.group(1)));
        }
        return List.copyOf(bodies);
    }

    private static int roundUp(int value, int alignment) {
        int remainder = value % alignment;
        return remainder == 0 ? value : value + (alignment - remainder);
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

    /** The payload size and the reference offsets of one emitted layout. */
    private record MatchResult(int size, List<Integer> references) {
    }

    private static MatchResult offsets(String module, int index) {
        Matcher layout = Pattern.compile(
                "@deal\\.class\\." + index + "\\.layout = internal constant"
                    + " %struct\\.deal_layout \\{ i32 \\d+, i32 0, i64 (\\d+),")
            .matcher(module);
        if (!layout.find()) {
            return new MatchResult(0, List.of());
        }
        int size = Integer.parseInt(layout.group(1));
        Matcher references = Pattern.compile(
                "@deal\\.class\\." + index
                    + "\\.layout\\.refs = private unnamed_addr"
                    + " constant \\[\\d+ x i32\\] \\[([^\\]]*)\\]")
            .matcher(module);
        List<Integer> offsets = new ArrayList<>();
        if (references.find()) {
            for (String value : references.group(1).split(",")) {
                String trimmed = value.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                offsets.add(Integer.parseInt(trimmed.substring(
                    trimmed.lastIndexOf(' ') + 1)));
            }
        }
        return new MatchResult(size, List.copyOf(offsets));
    }

    private static List<String> splitFields(String body) {
        List<String> fields = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char character : body.toCharArray()) {
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
            }
            if (character == ',' && depth == 0) {
                fields.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(character);
        }
        if (!current.toString().isBlank()) {
            fields.add(current.toString());
        }
        return fields;
    }

    // =========================================================================
    // 2. Determinism (C9)
    // =========================================================================

    private static void testDeterminism(Path scratch) throws Exception {
        System.out.println("-- two compiles are byte-identical --");
        Path first = compileProject(scratch, "determinism-a",
            containersFixture());
        Path second = compileProject(scratch, "determinism-b",
            containersFixture());
        check(java.util.Arrays.equals(
                Files.readAllBytes(first.resolve("main.ll")),
                Files.readAllBytes(second.resolve("main.ll"))),
            "the same project emits the same module bytes");
    }

    // =========================================================================
    // 3. The executed fixtures
    // =========================================================================

    private static void testExecutedFixtures(Path scratch) throws Exception {
        System.out.println("-- the executed fixtures (both levels) --");
        Map<String, String> fixtures = new LinkedHashMap<>();
        fixtures.put("containers", containersFixture());
        fixtures.put("classes", classesFixture());
        fixtures.put("json", jsonFixture());
        fixtures.put("routing", routingFixture());
        fixtures.put("ieee", ieeeFixture());
        fixtures.put("holders", holdersFixture());
        fixtures.put("nullish", nullishFixture());
        fixtures.put("optional-unknown", optionalUnknownFixture());
        fixtures.put("narrowing", narrowingFixture());
        Path jsonPublished = compileProject(scratch, "json-structure",
            jsonFixture());
        String jsonModule = Files.readString(jsonPublished.resolve("main.ll"),
            StandardCharsets.UTF_8);
        check(jsonModule.contains("@deal_json_from_class("),
            "the generated fromJson walk calls the runtime JSON group");
        check(jsonModule.contains("@deal_json_to_class("),
            "the generated toJson walk calls the runtime JSON group");
        check(jsonModule.contains("@deal_class_plan_lookup")
                && jsonModule.contains("bitcast (%struct.deal_json_class_plan*"
                    + " (i8*, i8*)*"),
            "the plan lookup crosses the published function-pointer type");
        check(jsonModule.contains("store i32 " + LlvmTypeMapper.PRESENTATION_DATUM)
                && jsonModule.contains("%struct.deal_check_input* %value"),
            "a class plan's read seam publishes the closed check presentation");
        for (String plan : List.of("deal.class.0.plan", "deal.class.1.plan",
                "deal.class.2.plan")) {
            check(jsonModule.contains("@" + plan + " = internal constant"
                    + " %struct.deal_json_class_plan"),
                "the class plan " + plan + " is one static plan record");
        }
        Map<String, String> messages = new LinkedHashMap<>();
        messages.put("containers", "héllo");
        messages.put("classes", "Ada|London|b");
        messages.put("json", "{\"title\":\"t\",\"inner\":{\"value\":2.0},"
            + "\"items\":[1.0,2.0],\"maybe\":\"m\",\"optionalNull\":null,"
            + "\"dynamic\":{\"k\":true}}|{\"name\":\"a\",\"maybe\":\"m\"}");
        messages.put("routing", "routing");
        messages.put("ieee", "ieee");
        messages.put("holders", "start|inner");
        messages.put("nullish", "{\"values\":[null],\"nulls\":[null,null],"
            + "\"rows\":[[null]],\"optional\":null}|{\"values\":[null,null],"
            + "\"nulls\":[null],\"rows\":[[null,null]],\"optional\":null}"
            + "|{\"name\":\"u\"}");
        messages.put("optional-unknown", "value|t");
        messages.put("narrowing", "narrowing");
        for (Map.Entry<String, String> fixture : fixtures.entrySet()) {
            Path published = compileProject(scratch, fixture.getKey(),
                fixture.getValue());
            for (int level : new int[] {0, 2}) {
                NativeCompiler.Result run = runProduct(scratch, published, level,
                    fixture.getKey());
                checkEq(1, run.exitCode(), "-O" + level + " the "
                    + fixture.getKey() + " fixture reaches its DONE marker: "
                    + run.describe());
                check(run.stdout().contains("DEAL_ERROR_CODE: DONE"),
                    "-O" + level + " the " + fixture.getKey()
                        + " fixture executed every case: " + run.describe());
                check(run.stdout().contains("\"message\":\""
                        + snapshotMessage(messages.get(fixture.getKey()))
                        + "\""),
                    "-O" + level + " the " + fixture.getKey()
                        + " fixture observed the pinned values: "
                        + run.describe());
                // The same case under one full collection per safepoint: the
                // timing-only switch changes no output (M7).
                NativeCompiler.Result stressed = NativeCompiler.execute(
                    productPath(scratch, fixture.getKey() + "-" + level),
                    scratch, Map.of("DEAL_GC_STRESS", "1"));
                checkEq(run.exitCode(), stressed.exitCode(), "-O" + level
                    + " the " + fixture.getKey() + " fixture survives a full"
                    + " collection at every safepoint");
                checkEq(run.stdout(), stressed.stdout(), "-O" + level + " the "
                    + fixture.getKey() + " fixture is byte-identical under"
                    + " DEAL_GC_STRESS");
            }
        }
    }

    // =========================================================================
    // 4. The driver projection of the boundary tuple and the frames
    // =========================================================================

    private static void testAccessorProjection(Path scratch) throws Exception {
        System.out.println("-- the driver build projects the carried outcome --");
        Path published = compileProject(scratch, "accessors",
            accessorFixture());
        Path driver = scratch.resolve("driver.c");
        Files.writeString(driver, driverSource(), StandardCharsets.UTF_8);
        for (int level : new int[] {0, 2}) {
            Path product = scratch.resolve("driver-" + level);
            List<Path> sources = new ArrayList<>();
            sources.add(published.resolve("main.ll"));
            for (Path unit : NativeCompiler.runtimeTranslationUnits(published)) {
                if (!unit.getFileName().toString().equals("main.c")) {
                    sources.add(unit);
                }
            }
            sources.add(driver);
            NativeCompiler.Result link = NativeCompiler.link(published, level,
                sources, product);
            check(link.succeeded(), "-O" + level + " the driver build links"
                + " against the published headers: " + link.describe());
            if (!link.succeeded()) {
                continue;
            }
            NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
                Map.of());
            // The expected values are the registry's own arm template and the
            // recorded origin of the assertion, never a copy of a run.
            for (String line : List.of(
                    "code=E8001",
                    "message=expected number",
                    "file=src/main.deal",
                    "line=2",
                    "column=10",
                    "expected=number",
                    "actual=string",
                    "frames=3",
                    "frame0=main#inner@F2",
                    "frame1=main#middle@F10",
                    "frame2=main#main@F18")) {
                check(run.stdout().contains(line + "\n"),
                    "-O" + level + " the driver projects '" + line + "': "
                        + run.describe());
            }
        }
    }

    // =========================================================================
    // The production compile, build and run path
    // =========================================================================

    private record CompileOutcome(boolean success,
                                  List<CompilerDiagnostic> diagnostics) {
    }

    private static Path productPath(Path scratch, String name) {
        return scratch.resolve("products").resolve(name).resolve("product");
    }

    private static Path compileProject(Path scratch, String name, String source)
            throws Exception {
        Path root = scratch.resolve(name);
        writeProject(root, source);
        CompileOutcome outcome = compile(root);
        if (!outcome.success()) {
            throw new IllegalStateException("the fixture '" + name
                + "' failed to compile: " + outcome.diagnostics());
        }
        return root.resolve("build/llvm");
    }

    private static void writeProject(Path root, String source) throws IOException {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("deal.json"),
            "{\"languageVersion\": \"1.5\", \"moduleRoots\": [\"src\"],"
                + " \"backend\": \"llvm\"}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/main.deal"), source,
            StandardCharsets.UTF_8);
    }

    private static CompileOutcome compile(Path root) throws IOException {
        Orc orc = new Orc();
        boolean success = orc.run(root.resolve("src/main.deal"),
            root.resolve("build/llvm"));
        return new CompileOutcome(success, orc.diagnostics);
    }

    /**
     * Builds one published fixture through the production toolchain seam
     * (D3/D4/D5) and runs its product: the durable path is the caller's own,
     * outside the publication root, exactly as {@code deal native build} places
     * it.
     */
    private static NativeCompiler.Result runProduct(Path scratch, Path published,
                                                    int level, String name)
            throws Exception {
        Path product = productPath(scratch, name + "-" + level);
        Files.createDirectories(product.getParent());
        try {
            deal.toolchain.NativeToolchain.build(new deal.toolchain.NativeBuildRequest(
                published, product, level, List.of(), null));
        } catch (deal.toolchain.NativeBuildException failure) {
            throw new IllegalStateException("the production toolchain seam rejects"
                + " the " + name + " build at -O" + level + ": "
                + failure.getMessage(), failure);
        }
        check(Files.isExecutable(product), "the " + name + " product at -O" + level
            + " is a durable executable outside the publication root");
        return NativeCompiler.execute(product, scratch, Map.of());
    }

    /** One production compile through the CLI's own resolution and arm. */
    private static final class Orc {
        private final List<CompilerDiagnostic> diagnostics = new ArrayList<>();

        private boolean run(Path entry, Path output) throws IOException {
            ProjectLocator.LocateResult located = ProjectLocator.locate(
                entry.toAbsolutePath().toString(),
                new CliOverrides(null, output.toAbsolutePath().toString()));
            if (located.context() == null) {
                return false;
            }
            ProjectContext context = located.context();
            CompilerInvocation invocation = CompilerProfileProvider.resolve(
                ReleaseConfiguration.CURRENT_RELEASE_STATE,
                ReleaseConfiguration.releaseCapabilityRegistry());
            CompilationOrchestrator orchestrator = new CompilationOrchestrator(
                context, entry.toAbsolutePath().normalize(), false, false,
                false, false, null, invocation);
            PrintStream out = System.out;
            PrintStream err = System.err;
            ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
            ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
            boolean success;
            try {
                System.setOut(new PrintStream(capturedOut, true,
                    StandardCharsets.UTF_8));
                System.setErr(new PrintStream(capturedErr, true,
                    StandardCharsets.UTF_8));
                success = orchestrator.compile();
            } finally {
                System.setOut(out);
                System.setErr(err);
            }
            diagnostics.addAll(orchestrator.diagnostics());
            return success;
        }
    }

    // =========================================================================
    // Small helpers
    // =========================================================================

    /** The exact {@code define} block of one emitted function name. */
    private static String functionOf(String module, String name) {
        Matcher definition = Pattern.compile("(?ms)^define [^\n]* @"
            + Pattern.quote(name) + "\\(.*?^\\}$").matcher(module);
        if (!definition.find()) {
            throw new IllegalStateException(
                "the module defines no function " + name);
        }
        return definition.group();
    }

    /** One message as the canonical snapshot escapes it (RFC 8259). */
    private static String snapshotMessage(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void check(boolean condition, String description) {
        if (condition) {
            passed++;
            return;
        }
        failed++;
        System.out.println("FAIL: " + description);
    }

    private static void checkEq(Object expected, Object actual,
                                String description) {
        if (expected == null ? actual == null : expected.equals(actual)) {
            passed++;
            return;
        }
        failed++;
        System.out.println("FAIL: " + description + " (expected " + expected
            + ", got " + actual + ")");
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(java.util.Comparator.reverseOrder())
                .toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }
}
