package deal.test;

import deal.ast.ClassDeclaration;
import deal.ast.ExportDeclaration;
import deal.ast.StatementNode;
import deal.codegen.Backend;
import deal.codegen.SourceMapGenerator;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.DiagnosticStructuredOutput;
import deal.diagnostics.RangeOrigin;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.module.CompilationOrchestrator;
import deal.module.DefaultResourceOccurrence;
import deal.module.DefaultSerializerModuleInput;
import deal.module.PlannedDefaultClass;
import deal.module.ProductionProjectEmission;
import deal.module.RuntimeImportDependency;
import deal.module.RuntimeResourceReference;
import deal.module.StdlibModuleResolver;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.project.CliOverrides;
import deal.project.ProjectLocator;
import deal.publication.PublicationStager;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.SemanticLowerer;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.test.conformance.ProducedProjectIdentityAudit;
import deal.test.conformance.JvmCompilation;
import deal.test.conformance.SidecarExpectations;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public class ProductionDispatchTest {

    /**
     * This JVM's own {@code v12-corner-case-*} scratch-root marker. The
     * raw-profile control observes the production scratch roots the drive
     * allocates; without a JVM-private marker a concurrent test process in
     * another checkout (whose roots share {@code java.io.tmpdir}) would be
     * observed as this process's allocation and make the before/after
     * snapshot racy.
     */
    private static final String V12_SCRATCH_TOKEN =
        java.util.UUID.randomUUID().toString().replace("-", "");

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

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    // =========================================================================
    // Fixture helpers
    // =========================================================================

    private static final String DEAL_JSON_LUA = """
        {
          "languageVersion": "1.4",
          "moduleRoots": ["src"],
          "output": "out",
          "backend": "luajit"
        }
        """;

    private static final String DEAL_JSON_JVM = """
        {
          "languageVersion": "1.4",
          "moduleRoots": ["src"],
          "output": "out",
          "backend": "jvm"
        }
        """;

    /**
     * The GitHub #6 sibling-provider reproducer (the exact reported
     * program): two class-declaring modules in one directory, imported
     * and constructed in {@code main}.
     */
    private static final String SIBLING_MAIN_SOURCE = """
        import * as console from "std/console";
        import * as ir from "./m/ir";
        import * as codec from "./m/codec";
        export function main(): null {
          if (ir.useIr({a: 0}) === 0 && codec.useCodec({b: 0}) === 0) { console.log("ok"); }
          return null;
        }
        """;

    /** The same program with the two provider imports reversed. */
    private static final String SIBLING_MAIN_SOURCE_REVERSED =
        reverseSiblingImports(SIBLING_MAIN_SOURCE);

    private static final String SIBLING_IR_SOURCE = """
        export class Ir { a: number; }
        export function useIr(x: Ir): number { return x.a; }
        """;

    private static final String SIBLING_CODEC_SOURCE = """
        export class Codec { b: number; }
        export function useCodec(y: Codec): number { return y.b; }
        """;

    /**
     * The GitHub #6 same-name collision control (review cycle 1): the
     * second sibling declares a non-exported class of the first
     * sibling's name, so a name-bearing sibling program must never
     * become the provider and the sibling's own plan
     * ({@code a = 93}) must never serialize as the demanded class.
     */
    private static final String COLLISION_IR_SOURCE = """
        export class Ir { a: number; }
        export function useIr(x: Ir): number { return x.a; }
        """;

    private static final String COLLISION_CODEC_SOURCE = """
        class Ir { a: number; }
        export class Codec { b: number; }
        export function useCodec(y: Codec): number { return y.b; }
        """;

    /** The collision consumer: the qualified field annotation names
     * {@code ir.Ir} while the codec sibling declares a same-name
     * non-exported class. */
    private static final String COLLISION_MAIN_CODEC_FIRST = """
        import * as console from "std/console";
        import * as codec from "./m/codec";
        import * as ir from "./m/ir";
        export class Outer { r: ir.Ir; c: codec.Codec; }
        export function main(): null {
          let o: Outer = {r: {a: 7}, c: {b: 9}};
          if (o.r.a === 7 && o.c.b === 9) { console.log("ok"); }
          return null;
        }
        """;

    /** The collision consumer with the two provider imports reversed. */
    private static final String COLLISION_MAIN_IR_FIRST =
        reverseSiblingImports(COLLISION_MAIN_CODEC_FIRST);

    /**
     * The contextual-call collision (review cycle 2): the class context
     * arrives only through {@code ir.useIr}'s parameter, and both
     * same-named siblings export the demanded {@code Ir} name, so
     * the resolved imported callee's declaring module must own the
     * recorded plan and the emitted construction (the codec sibling's
     * own default never serializes as the demanded class).
     */
    private static final String CONTEXTUAL_IR_SOURCE = """
        export class Ir { a: number; }
        export function useIr(x: Ir): number { return x.a; }
        """;

    private static final String CONTEXTUAL_CODEC_SOURCE = """
        export class Ir { a: number; }
        export class Codec { b: number; }
        export function useCodec(y: Codec): number { return y.b; }
        """;

    /** The contextual collision consumer: the literal is an argument of
     * the {@code ir}-imported callee, so only the callee's parameter
     * context names the demanded provider. */
    private static final String CONTEXTUAL_MAIN_CODEC_FIRST = """
        import * as console from "std/console";
        import * as codec from "./m/codec";
        import * as ir from "./m/ir";
        export class Outer { x: number; c: codec.Codec; }
        export function main(): null {
          let o: Outer = {x: ir.useIr({a: 7}), c: {b: 9}};
          if (o.x === 7 && o.c.b === 9) { console.log("ok"); }
          return null;
        }
        """;

    /** The contextual collision consumer with the imports reversed. */
    private static final String CONTEXTUAL_MAIN_IR_FIRST =
        reverseSiblingImports(CONTEXTUAL_MAIN_CODEC_FIRST);

    /** The original reproducer shape (a body call) under the exported
     * same-name sibling collision: the construction sits outside a
     * published plan default. */
    private static final String CONTEXTUAL_BODY_CODEC_FIRST = """
        import * as console from "std/console";
        import * as codec from "./m/codec";
        import * as ir from "./m/ir";
        export function main(): null {
          if (ir.useIr({a: 7}) === 7) { console.log("ok"); }
          return null;
        }
        """;

    private static final String CONTEXTUAL_BODY_IR_FIRST =
        reverseSiblingImports(CONTEXTUAL_BODY_CODEC_FIRST);

    /**
     * The callee-parameter and declared-return provider fixture beside a
     * same-identity local declaration (review cycle 5, finding 1): the
     * entry declares its own {@code Ir} (default 5) in the provider's
     * directory, so the import's {@code useIr} parameter and {@code g}'s
     * parameter and {@code make}'s declared return all name an
     * same-named sibling: those literals construct the imported
     * declaration's 7 while the explicitly annotated local literal stays
     * the entry's own 5.
     */
    private static String localProviderBody(String firstImport,
            String secondImport) {
        return "import * as console from \"std/console\";\n"
            + firstImport + secondImport
            + "\n"
            + "class Ir { a: number; }\n"
            + "\n"
            + "function g(x: ir.Ir): number { return x.a; }\n"
            + "function make(): ir.Ir { return {a: 7}; }\n"
            + "\n"
            + "export function main(): null {\n"
            + "  let n: number = ir.useIr({a: 7});\n"
            + "  let m: number = g({a: 7});\n"
            + "  let r: ir.Ir = make();\n"
            + "  let local: Ir = {a: 5};\n"
            + "  if (n === 7 && m === 7 && r.a === 7 && local.a === 5) {\n"
            + "    console.log(\"ok\");\n"
            + "  } else {\n"
            + "    console.log(\"wrong\");\n"
            + "  }\n"
            + "  return null;\n"
            + "}\n";
    }

    /**
     * The shadowed-local-callee collision (review cycle 4): a nested
     * function declaration shadows a same-named module-level function
     * whose parameter names the codec sibling, while the nested
     * declaration's parameter names the demanded ir sibling. The call
     * constructs the nested declaration's class (default 7), never the
     * top-level declaration's sibling (93).
     */
    private static final String SHADOWED_CALLEE_CODEC_FIRST = """
        import * as console from "std/console";
        import * as codec from "./m/codec";
        import * as ir from "./m/ir";
        function consume(x: codec.Ir): number { return x.a; }
        export function main(): null {
          function consume(x: ir.Ir): number { return x.a; }
          if (consume({a: 7}) === 7) { console.log("ok"); }
          else { console.log("wrong"); }
          return null;
        }
        """;

    /** The shadowed-local-callee collision with the imports reversed. */
    private static final String SHADOWED_CALLEE_IR_FIRST =
        reverseSiblingImports(SHADOWED_CALLEE_CODEC_FIRST);

    /**
     * The nested-literal control (review cycle 2): the demanded class
     * is a nested field of an imported class, so the enclosing field
     * annotation names the outer class only. The declaring program's own
     * field declaration carries the nested class's provider.
     */
    private static final String NESTED_IR_SOURCE = """
        export class Ir { a: number; }
        export class Wrap { inner: Ir; }
        """;

    private static final String NESTED_CODEC_SOURCE = """
        export class Ir { a: number; }
        """;

    private static final String NESTED_MAIN_CODEC_FIRST = """
        import * as console from "std/console";
        import * as codec from "./m/codec";
        import * as ir from "./m/ir";
        export class Outer { w: ir.Wrap; }
        export function main(): null {
          let o: Outer = {w: {inner: {a: 7}}};
          if (o.w.inner.a === 7) { console.log("ok"); }
          return null;
        }
        """;

    /**
     * The declared-context collision (review cycle 3): the demand
     * arrives through a declared return type, an array element
     * annotation, and a member-assignment target type — never through
     * an enclosing field annotation or a callee parameter. Every one of
     * the literals must bind the provider its declaration names, in
     * both import orders, and the published artifact must construct the
     * demanded default (7), never the sibling's (93).
     */
    private static final String RETURN_IR_SOURCE = """
        export class Ir { a: number; }
        """;

    private static final String RETURN_CODEC_SOURCE = """
        export class Ir { a: number; }
        export class Codec { b: number; }
        """;

    private static final String RETURN_MAIN_CODEC_FIRST = """
        import * as console from "std/console";
        import * as codec from "./m/codec";
        import * as ir from "./m/ir";
        function make(): ir.Ir { return {a: 7}; }
        export class Outer { r: ir.Ir; c: codec.Codec; }
        export function main(): null {
          let o: Outer = {r: {a: 7}, c: {b: 9}};
          o.r = {a: 7};
          let xs: ir.Ir[] = [{a: 7}];
          let m: ir.Ir = make();
          if (o.r.a === 7 && xs[0].a === 7 && m.a === 7 && o.c.b === 9)
            { console.log("ok"); }
          return null;
        }
        """;

    private static final String RETURN_MAIN_IR_FIRST =
        reverseSiblingImports(RETURN_MAIN_CODEC_FIRST);

    /**
     * The identifier-assignment collision (review cycle 5): the
     * assignment value's context is the assigned binding's own resolved
     * provenance (its declared annotation, or the initializer route of
     * an inferred binding), so every assignment literal — a local, an
     * indexed element, an inferred binding, and a class-typed parameter —
     * must construct the exact provider the binding names, never the
     * first same-named export of the import order.
     */
    private static final String ASSIGN_IR_SOURCE = """
        export class Ir { a: number; }
        export function make(): Ir { return {a: 7}; }
        """;

    private static final String ASSIGN_CODEC_SOURCE = """
        export class Ir { a: number; }
        export class Codec { b: number; }
        """;

    private static final String ASSIGN_MAIN_CODEC_FIRST = """
        import * as console from "std/console";
        import * as codec from "./m/codec";
        import * as ir from "./m/ir";
        function set(p: ir.Ir): number { p = {a: 7}; return p.a; }
        export function main(): null {
          let x: ir.Ir = {a: 7};
          x = {a: 7};
          let xs: ir.Ir[] = [{a: 7}];
          xs[0] = {a: 7};
          let y = ir.make();
          y = {a: 7};
          let c: codec.Codec = {b: 9};
          if (x.a === 7 && xs[0].a === 7 && y.a === 7 && set({a: 7}) === 7
              && c.b === 9) { console.log("ok"); }
          else { console.log("wrong"); }
          return null;
        }
        """;

    private static final String ASSIGN_MAIN_IR_FIRST =
        reverseSiblingImports(ASSIGN_MAIN_CODEC_FIRST);

    /**
     * The member-assignment collision (review cycle 5): the assigned
     * target is a class-typed member of a wrapper binding (a local, an
     * inferred binding, a same-module class field, an array element,
     * and a parameter), so the owner expression's own checked route
     * names the field declaration's provider — never the first
     * same-named export of the import order.
     */
    private static final String ASSIGN_MEMBER_IR_SOURCE = """
        export class Ir { a: number; }
        export class Wrap { inner: Ir; }
        export function makeWrap(): Wrap { return {inner: {a: 7}}; }
        """;

    private static final String ASSIGN_MEMBER_CODEC_SOURCE = """
        export class Ir { a: number; }
        export class Wrap { inner: Ir; }
        export function makeWrap(): Wrap { return {inner: {a: 93}}; }
        """;

    private static final String ASSIGN_MEMBER_MAIN_CODEC_FIRST = """
        import * as console from "std/console";
        import * as codec from "./m/codec";
        import * as ir from "./m/ir";
        class Holder { w: ir.Wrap; }
        function setParam(w: ir.Wrap): number { w.inner = {a: 7}; return w.inner.a; }
        export function main(): null {
          let w: ir.Wrap = {inner: {a: 7}};
          w.inner = {a: 7};
          let v = ir.makeWrap();
          v.inner = {a: 7};
          let h: Holder = {w: {inner: {a: 7}}};
          h.w.inner = {a: 7};
          let xs: ir.Wrap[] = [{inner: {a: 7}}];
          xs[0].inner = {a: 7};
          if (w.inner.a === 7 && v.inner.a === 7 && h.w.inner.a === 7
              && xs[0].inner.a === 7 && setParam({inner: {a: 7}}) === 7) {
            console.log("ok");
          } else { console.log("wrong"); }
          return null;
        }
        """;

    private static final String ASSIGN_MEMBER_MAIN_IR_FIRST =
        reverseSiblingImports(ASSIGN_MEMBER_MAIN_CODEC_FIRST);

    /**
     * The shadowing-initializer assignment collision (review 0005): the
     * assignment inside a shadowing let's own initializer targets the
     * enclosing binding — the pending declaration is not visible there
     * (docs/spec-v1.4.md §Name resolution — Declaration order) — so the constructed
     * literal keeps the enclosing binding's declared provider. The
     * pre-fix recorder resolved the not-yet-visible inner declaration,
     * so the same-named sibling's class was constructed and the
     * import order decided the demanded default (7 versus 93) with no
     * diagnostic.
     */
    private static final String SHADOW_IR_SOURCE = """
        export class Ir { a: number; }
        export function make(): Ir { return {a: 7}; }
        """;

    private static final String SHADOW_CODEC_SOURCE = """
        export class Ir { a: number; }
        export class Codec { b: number; }
        """;

    private static final String SHADOW_MAIN_CODEC_FIRST = "import * as console from \"std/console\";\nimport * as codec from \"./m/codec\";\nimport * as ir from \"./m/ir\";\nexport function main(): null {\n  let x: ir.Ir = {a: 7};\n  { let x: codec.Ir = (x = {a: 7}); }\n  { let x: codec.Ir = (x = {a: 7}); while (false) { } }\n  if (x.a === 7) { console.log(\"ok\"); } else { console.log(\"wrong\"); }\n  return null;\n}\n";

    private static final String SHADOW_MAIN_IR_FIRST =
        reverseSiblingImports(SHADOW_MAIN_CODEC_FIRST);

    private static String reverseSiblingImports(String source) {
        String ir = "import * as ir from \"./m/ir\";\n";
        String codec = "import * as codec from \"./m/codec\";\n";
        if (source.contains(ir + codec)) {
            return source.replace(ir + codec, codec + ir);
        }
        if (source.contains(codec + ir)) {
            return source.replace(codec + ir, ir + codec);
        }
        throw new IllegalArgumentException("missing adjacent sibling imports");
    }

    private static final String DEAL_JSON_JS = """
        {
          "languageVersion": "1.4",
          "moduleRoots": ["src"],
          "output": "out",
          "backend": "js"
        }
        """;

    private static final String HOST_DEAL_JSON_LUA = """
        {
          "languageVersion": "1.4",
          "moduleRoots": ["src"],
          "output": "out",
          "backend": "luajit",
          "externals": {
            "host/cfg": { "declaration": "cfg.d.deal" }
          }
        }
        """;

    /** The covered library module: no call, no later-slice construct. */
    private static final String LIB_SOURCE = """
        export function value(): number {
          return 42;
        }

        export function label(): string {
          return "lib";
        }
        """;

    /** The covered two-module entry: a COMPILED import, no call. */
    private static final String APP_SOURCE = """
        import * as lib from "./lib";
        import * as console from "std/console";

        export function main(): null {
          console.log("PROBE|MAIN-ONCE");
          return null;
        }

        export function label(): string {
          return "app";
        }
        """;

    /**
     * The source-map library module: the sync exports plus one async export,
     * so the produced artifact carries an async-entry definition owned by
     * this module (the source-map regression pins its preceding comment and
     * sidecar mapping).
     */
    private static final String MAP_LIB_SOURCE = """
        export function value(): number {
          return 42;
        }

        export function label(): string {
          return "lib";
        }

        export async function load(): number {
          return 7;
        }
        """;

    /**
     * The source-map entry module: the probe main plus a cross-module async
     * export, so both constituent modules own an async-entry definition.
     */
    private static final String MAP_APP_SOURCE = """
        import * as lib from "./lib";
        import * as console from "std/console";

        export function main(): null {
          console.log("PROBE|MAIN-ONCE");
          return null;
        }

        export function label(): string {
          return "app";
        }

        export async function worker(): number {
          return await lib.load();
        }
        """;

    /**
     * The direct-emission library of the source-map regression: one exported
     * class whose CLASS_FACTORY op carries the class declaration's span (the
     * owner module of the construction-time factory group).
     */
    private static final String DIRECT_EMISSION_LIB_SOURCE = """
        export function value(): number {
          return 42;
        }

        export class Point {
          x: number;
        }

        export class DefaultPoint {
          x: number;
          y: number;
        }
        """;

    /**
     * The direct-emission entry of the source-map regression: one
     * parameterized export (its parameter BINDING_ALLOC is emitted directly
     * in the function factory, never through emitOp) and one imported-class
     * construction (the owner-side factory group emitted directly in the
     * caller's body).
     */
    private static final String DIRECT_EMISSION_APP_SOURCE = """
        import * as lib from "./lib";
        import * as console from "std/console";

        export function f(x: number): number {
          return x;
        }

        export function main(): null {
          let p: lib.Point = { x: 1 };
          let q: lib.DefaultPoint = {x: 3, y: 4};
          console.log("PROBE|DIRECT-MAIN");
          console.log("PROBE|OMITTED-DEFAULTS");
          return null;
        }
        """;

    /**
     * The source-map unsafe-path fixture manifest: the module root carries an
     * embedded line terminator (the JSON escape the parser resolves to a real
     * LF in the directory name), so the source display name of the emitted
     * source comment is not a single comment line unless it is encoded.
     */
    private static final String DEAL_JSON_NEWLINE_ROOT = """
        {
          "languageVersion": "1.4",
          "moduleRoots": ["src\\nline"],
          "output": "out",
          "backend": "luajit"
        }
        """;

    /**
     * The source-map unsafe-path fixture manifest: the module root carries a
     * literal backslash-u000a sequence, the Java Unicode-escape hazard (the
     * Java lexer translates the escape before it recognizes the comment).
     */
    private static final String DEAL_JSON_UNICODE_ROOT = """
        {
          "languageVersion": "1.4",
          "moduleRoots": ["src\\\\u000aline"],
          "output": "out",
          "backend": "luajit"
        }
        """;

    /**
     * The source-map control-character fixture manifest: the module root
     * carries accepted C0 controls other than the LF/CR/TAB the serializer
     * always escaped (BEL, VT, FF, US), spelled as JSON {@code \\uXXXX}
     * escapes so the strict manifest parser resolves the real directory name.
     */
    private static final String DEAL_JSON_CONTROL_ROOT = """
        {
          "languageVersion": "1.4",
          "moduleRoots": ["src\\u0007\\u000b\\u000c\\u001fline"],
          "output": "out",
          "backend": "luajit"
        }
        """;

    /**
     * The control-character module root name: four accepted C0 controls the
     * serializer wrote into the sidecar raw before the strict-JSON escape
     * (BEL, VT, FF, US), around ordinary text.
     */
    private static final String CONTROL_ROOT_NAME = "src"
        + (char) 0x07 + (char) 0x0b + (char) 0x0c + (char) 0x1f + "line";

    /**
     * The control-character output directory name: BS and ESC in the
     * generated path (the output root sits two levels below the project
     * root, so the generated path is the project-relative spelling).
     */
    private static final String CONTROL_OUTPUT_NAME = "out"
        + (char) 0x08 + (char) 0x1b + "dir";

    /** The unsafe-path fixture body: a trivial entry that returns null. */
    private static final String TRIVIAL_MAIN_SOURCE = """
        export function main(): null {
          return null;
        }
        """;

    /** The overflow fixture: the production DEAL_ERROR_CODE terminal. */
    private static final String OVERFLOW_SOURCE = """
        export function main(): null {
          let values: number[] = []; let x: number = values[0];
          return null;
        }
        """;

    /** The array fixture exercises production container lowering. */
    private static final String ARRAY_SOURCE = """
        export function main(): null {
          let n: number = 3;
          let b: number[] = [n];
          return null;
        }
        """;

    private static final String ERROR_SOURCE = """
        export function main(): null {
          let e: Error = { code: "E1", message: "m" };
          return null;
        }
        """;

    private static final String TIME_SOURCE = """
        import * as time from "std/time";
        import * as lib from "./lib";

        export function main(): null {
          let direct: number = time.nowMillis();
          if (direct < 2147483648.0) { throw { code: "TEST_FAIL", message: "direct time.nowMillis not above the signed-32 range" }; }
          let stored: () => number = time.nowMillis;
          let adapted: number = stored();
          if (adapted <= 0.0) { throw { code: "TEST_FAIL", message: "stored time.nowMillis not positive" }; }
          let imported: () => number = lib.getNow();
          let cross: number = imported();
          if (cross <= 0.0) { throw { code: "TEST_FAIL", message: "imported time.nowMillis not positive" }; }
          if (cross < 2147483648.0) { throw { code: "TEST_FAIL", message: "imported time.nowMillis not above the signed-32 range" }; }
          return null;
        }
        """;

    /** The cross-module {@code nowMillis} provider of the time fixture. */
    private static final String TIME_LIB_SOURCE = """
        import * as time from "std/time";

        export function getNow(): () => number {
          return time.nowMillis;
        }
        """;

    /**
     * The unlowered-construct seed: a for-let update that is not the
     * assignment chain the update block lowers ({@code ForUpdate ::=
     * LeftHandSideExpression '=' AssignmentExpression}), so the production
     * compile still fails the module with the named E6005
     * {@code CONSTRUCT_UNLOWERED} before any emission. The function-typed
     * for-counter this battery previously pinned as a later slice's
     * construct is production-covered now: the counter's declaration takes
     * the shared declaration-initializer production and its carry loads
     * resolve the current binding.
     */
    private static final String UNREALIZED_CONSTRUCT_SOURCE = "export function main(): null { let x: number = true; return null; }";

    /** The cross-module sync call: the realized external-call arm. */
    private static final String SYNC_CALL_SOURCE = """
        import * as lib from "./lib";

        export function main(): null {
          let x: number = lib.value();
          return null;
        }
        """;

    /**
     * The CLASS_DEFAULT_CAPTURE seed (ISSUE-0750): the local class's
     * default references the enclosing function's {@code x}, which the
     * detached CLASS_DEFAULT block cannot capture; the lowering fails
     * closed at that reference (line 3, column 26 = the {@code x} of
     * {@code n: int = x;}).
     */
    private static final String DEFAULT_CAPTURE_SOURCE =
        "export function main(): null {\n"
            + "  let x: number = 3;\n"
            + "  class Local { n: number; }\n"
            + "  let c: Local = {};\n"
            + "  return null;\n"
            + "}\n";

    /**
     * The duplicate-class CONSTRUCT_UNLOWERED seed (ISSUE-0750): the inner
     * block's {@code A} collides with the module-level layout (the checker
     * admits the shadowing declaration), so the lowering fails closed at
     * the duplicate declaration (line 3, columns 5-27).
     */
    private static final String DUPLICATE_CLASS_SOURCE =
        "class A { n: number; }\n"
            + "export function main(): null {\n"
            + "  { class A { n: number; } }\n"
            + "  return null;\n"
            + "}\n";

    /**
     * The imported-module duplicate-class seed (ISSUE-0750 nested
     * specificity): the entry module only imports the implementation
     * module whose inner block redeclares its own module-level class.
     */
    private static final String IMPORTED_DUPLICATE_MAIN =
        "import * as lib from \"./lib\";\n"
            + "\n"
            + "export function main(): null {\n"
            + "  return null;\n"
            + "}\n";

    private static final String IMPORTED_DUPLICATE_LIB =
        "class A { n: number; }\n"
            + "\n"
            + "export function f(): null {\n"
            + "  { class A { n: number; } }\n"
            + "  return null;\n"
            + "}\n";

    /**
     * The supplementary-scalar/tab capture seed (ISSUE-0750 coordinate
     * variants): a leading comment carrying one supplementary scalar and
     * tab indentation, so the scalar cursor and the reported offsets
     * differ from the plain fixture.
     */
    private static final String SUPPLEMENTARY_TAB_CAPTURE_SOURCE =
        "// " + new String(Character.toChars(0x1F600)) + "\n"
            + "export function main(): null {\n"
            + "\tlet x: number = 3;\n"
            + "\tclass Local { n: number; }\n"
            + "\tlet c: Local = {};\n"
            + "\treturn null;\n"
            + "}\n";

    /** The cross-module async library. */
    private static final String ASYNC_LIB_SOURCE = """
        export async function getValue(): number {
          return 42;
        }
        """;

    /** The cross-module async call (a never-invoked body carries it). */
    private static final String CROSS_ASYNC_SOURCE = """
        import * as lib from "./lib";

        export function main(): null {
          return null;
        }

        async function worker(): number {
          return await lib.getValue();
        }
        """;

    /**
     * The same-module async call: accepted, emitted, and executable. The
     * awaiting call sits in an exported zero-arity async function, so a
     * runner can invoke it through the artifact's published entry surface
     * and actually execute the ASYNC_START(DEAL_BODY)/AWAIT path.
     */
    private static final String SAME_ASYNC_SOURCE = """
        export function main(): null {
          return null;
        }

        async function compute(): number {
          return 1;
        }

        export async function worker(): number {
          return await compute();
        }
        """;

    /** The host declaration of the HOST-import fixture. */
    private static final String HOST_DECLARATION = """
        export function version(): number;
        """;

    /** The host-import fixture: the import is never called. */
    private static final String HOST_IMPORT_SOURCE = """
        import * as cfg from "host/cfg";

        export function main(): null {
          return null;
        }
        """;

    private static void write(Path root, String relative, String content)
            throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void deleteRecursively(Path dir) {
        try {
            if (dir == null || !Files.exists(dir)) {
                return;
            }
            try (Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        } catch (Exception ignored) {
        }
    }

    private record ProjectOutcome(int exitCode, String stderr) {
    }

    /** Runs the release-owned production CLI in-process. */
    private static ProjectOutcome runProductionCli(String... args)
            throws Exception {
        return runCli(() -> deal.Main.run(args));
    }

    /** Runs the test-scope harness compile entry in-process. */
    private static ProjectOutcome runHarnessCli(String... args)
            throws Exception {
        return runCli(() -> HarnessCompileEntry.run(args));
    }

    private interface CliCall {
        int run() throws Exception;
    }

    private static ProjectOutcome runCli(CliCall call) throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        int exitCode;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exitCode = call.run();
            System.err.flush();
        } finally {
            System.setErr(originalErr);
        }
        return new ProjectOutcome(exitCode,
            err.toString(StandardCharsets.UTF_8).trim());
    }

    private record ProcessOutcome(int exitCode, String output) {
    }

    private static ProcessOutcome compileJvm(Path directory, String... command)
            throws Exception {
        JvmCompilation.Result result = JvmCompilation.compile(List.of(command), directory);
        return new ProcessOutcome(result.exitCode(), result.stdout() + result.stderr());
    }

    private static BoundedProcess.BoundedResult compileBoundedJvm(Path directory,
            String... command) throws Exception {
        JvmCompilation.Result result;
        try {
            result = JvmCompilation.compile(List.of(command), directory,
                BoundedProcess.SUBPROCESS_BUDGET_MS);
        } catch (IOException failure) {
            if (failure.getMessage() != null && failure.getMessage().equals(
                    "javac compilation exceeded " + BoundedProcess.SUBPROCESS_BUDGET_MS + "ms")) {
                BoundedProcess.BoundedProcessTimeoutException timeout =
                    new BoundedProcess.BoundedProcessTimeoutException("BOUNDED_PROCESS_TIMEOUT javac");
                timeout.initCause(failure);
                throw timeout;
            }
            throw failure;
        }
        byte[] stdout = result.stdout().getBytes(StandardCharsets.UTF_8);
        byte[] stderr = result.stderr().getBytes(StandardCharsets.UTF_8);
        return new BoundedProcess.BoundedResult(result.exitCode(),
            boundedCompilerStream(stdout), boundedCompilerStream(stderr),
            stdout.length > BoundedProcess.STREAM_CAP_BYTES,
            stderr.length > BoundedProcess.STREAM_CAP_BYTES);
    }

    private static String boundedCompilerStream(byte[] bytes) {
        return bytes.length <= BoundedProcess.STREAM_CAP_BYTES
            ? new String(bytes, StandardCharsets.UTF_8)
            : new String(bytes, 0, BoundedProcess.STREAM_CAP_BYTES, StandardCharsets.UTF_8)
                + BoundedProcess.TRUNCATION_MARKER;
    }

    private static ProcessOutcome runProcess(Path directory, String... command)
            throws Exception {
        BoundedProcess.BoundedResult result = BoundedProcess.runCaptured(
            List.of(command), directory);
        return new ProcessOutcome(result.exitCode(),
            result.stdout() + result.stderr());
    }

    /**
     * One orchestrator compile of the given project through the compile's
     * own manifest with an explicit invocation (the dispatch seam).
     */
    private record ArmCompile(CompilationOrchestrator orchestrator,
                              boolean success) {
    }

    private static ArmCompile compileWithInvocation(Path project, String entry,
            CompilerInvocation invocation) throws Exception {
        return compileWithInvocation(project, entry, invocation, false);
    }

    private static ArmCompile compileWithInvocation(Path project, String entry,
            CompilerInvocation invocation, boolean dumpIr) throws Exception {
        Path entryFile = project.resolve(entry).toAbsolutePath().normalize();
        ProjectLocator.LocateResult located = ProjectLocator.locate(
            entryFile.toString(), new CliOverrides(null, null));
        if (located.context() == null) {
            throw new IllegalStateException("locate failed: " + located);
        }
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            located.context(), entryFile, false, dumpIr, false, false, null,
            invocation);
        return new ArmCompile(orchestrator, orchestrator.compile());
    }

    private static ProjectOutcome productionCompile(Path project, String entry,
            String output) throws Exception {
        return runProductionCli("compile",
            project.resolve(entry).toAbsolutePath().toString(),
            "--output", project.resolve(output).toAbsolutePath().toString());
    }

    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    // =========================================================================
    // The bounded real-toolchain runner (the retained release contract)
    // =========================================================================

    /** The one-line stream transcript of a bounded invocation. */
    private static String boundedOutput(BoundedProcess.BoundedResult run) {
        return "stdout=" + run.stdout().replace("\n", "\\n")
            + " stderr=" + run.stderr().replace("\n", "\\n");
    }

    /**
     * The running distribution's classpath entries resolved against the
     * test process's working directory: the emitted-artifact toolchain
     * runs in an isolated workspace, so a relative entry would not
     * resolve there.
     */
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

    private static List<String> artifactFiles(Path root) throws Exception {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                .map(path -> root.relativize(path).toString()
                    .replace(File.separatorChar, '/'))
                .sorted().toList();
        }
    }

    /** Byte-for-byte snapshot of one artifact tree. */
    private static Map<String, byte[]> snapshotTree(Path root) throws Exception {
        Map<String, byte[]> snapshot = new java.util.LinkedHashMap<>();
        for (String relative : artifactFiles(root)) {
            snapshot.put(relative, Files.readAllBytes(root.resolve(relative)));
        }
        return snapshot;
    }

    private static void checkTreeIdentical(Map<String, byte[]> before,
            Path root, String context) throws Exception {
        check(treeEquals(before, snapshotTree(root)),
            context + ": the previous artifact set is byte-identical");
    }

    // =========================================================================
    // 1. The production-invocation predicate
    // =========================================================================

    private static void testInvocationPredicate() {
        System.out.println("-- the production-invocation predicate: record "
            + "identity, never a purpose-only test --");

        check(CompilationOrchestrator.isProductionInvocation(
                productionInvocation()),
            "the release-owned production record is the production invocation");
        checkEq(productionInvocation(),
            CompilerProfileProvider.resolve(
                ReleaseConfiguration.CURRENT_RELEASE_STATE,
                ReleaseConfiguration.releaseCapabilityRegistry()),
            "the predicate's reference record is the release-state resolution");

        checkEq(productionInvocation(),
            ConformanceHarnessMetadata.invocation(),
            "the harness metadata resolves the one release-owned production "
                + "invocation (no purpose or profile selection)");
        checkEq(productionInvocation(),
            CompilerProfileProvider.resolve(ReleaseState.V1_4_ACTIVE,
                CapabilityRegistry.releaseRegistry()),
            "every resolution over the one release state and registry "
                + "produces the same record");
        check(productionInvocation().semanticProfile()
                == SemanticProfile.DEAL_V1_4_BINARY64,
            "the production invocation carries the single DEAL_V1_4_BINARY64 "
                + "profile");
    }

    // =========================================================================
    // 2. One production compile: one artifact, no route plan, real toolchain
    // =========================================================================

    private static void testProductionLuaJitSingleModule() throws Exception {
        System.out.println("-- LuaJIT production compile: one project artifact, "
            + "one emission, no route plan --");

        Path project = Files.createTempDirectory("production-dispatch-lua-");
        try {
            write(project, "deal.json", DEAL_JSON_LUA);
            write(project, "src/main.deal", """
                import * as console from "std/console";

                export function main(): null {
                  console.log("PROBE|MAIN-ONCE");
                  return null;
                }

                export function label(): string {
                  return "single";
                }
                """);

            ArmCompile arm = compileWithInvocation(project, "src/main.deal",
                productionInvocation());
            check(arm.success(), "the production LuaJIT compile succeeds: "
                + arm.orchestrator().diagnostics());
            if (!arm.success()) {
                return;
            }
            CompilationOrchestrator orchestrator = arm.orchestrator();
            checkEq(1, orchestrator.semanticEmissionCount(),
                "the production arm records exactly one project emission");

            Path out = project.resolve("out");
            List<String> artifacts = artifactFiles(out);
            check(artifacts.contains("main.lua"),
                "the one project artifact is named for the entry module: "
                    + artifacts);
            check(!artifacts.contains("lib.lua"),
                "the published set carries no per-module sibling: "
                    + artifacts);
            check(artifacts.contains("main.deal.map.json"),
                "the production set carries the v1.4 default per-source "
                    + "source-map sidecar: " + artifacts);

            ProcessOutcome run = runProcess(out, "luajit", "main.lua");
            check(run.exitCode() == 0,
                "the production LuaJIT artifact runs clean: exit="
                    + run.exitCode() + " output=" + run.output());
            checkEq(1, countOccurrences(run.output(), "PROBE|MAIN-ONCE"),
                "the entry main runs exactly once per chunk execution");

            // Byte-identical repeated compiles.
            Map<String, byte[]> first = snapshotTree(out);
            ProjectOutcome repeat = productionCompile(project, "src/main.deal",
                "out-repeat");
            check(repeat.exitCode() == 0, "the repeated compile succeeds: "
                + repeat.stderr());
            Map<String, byte[]> second = snapshotTree(
                project.resolve("out-repeat"));
            checkEq(first.keySet(), second.keySet(),
                "the repeated compile publishes the same artifact set");
            for (String relative : first.keySet()) {
                boolean equal = relative.endsWith(".deal.map.json")
                    ? normalizedSidecarText(first.get(relative)).equals(
                        normalizedSidecarText(second.get(relative)))
                    : Arrays.equals(first.get(relative), second.get(relative));
                check(equal, "the repeated compile is byte-identical for "
                    + relative + (relative.endsWith(".deal.map.json")
                        ? " (apart from its own generated path spelling)" : ""));
            }
        } finally {
            deleteRecursively(project);
        }
    }

    private static void testProductionJvmTwoModule() throws Exception {
        System.out.println("-- JVM production compile: one project artifact, "
            + "javac --release 25 -proc:none plus java --");

        Path project = Files.createTempDirectory("production-dispatch-jvm-");
        try {
            write(project, "deal.json", DEAL_JSON_JVM);
            write(project, "src/lib.deal", LIB_SOURCE);
            write(project, "src/main.deal", APP_SOURCE);

            ArmCompile arm = compileWithInvocation(project, "src/main.deal",
                productionInvocation());
            check(arm.success(), "the production JVM compile succeeds: "
                + arm.orchestrator().diagnostics());
            if (!arm.success()) {
                return;
            }
            CompilationOrchestrator orchestrator = arm.orchestrator();
            checkEq(1, orchestrator.semanticEmissionCount(),
                "the production arm records exactly one project emission");

            Path out = project.resolve("out");
            List<String> artifacts = artifactFiles(out);
            check(artifacts.equals(List.of("Main.java", "lib.deal.map.json",
                    "main.deal.map.json")),
                "the one project artifact is the entry class source plus the "
                    + "default per-source sidecars: " + artifacts);

            // The export surfaces of both modules are in the artifact, keyed
            // by the dotted module path.
            String source = Files.readString(out.resolve("Main.java"));
            check(source.contains("\"lib\"") && source.contains("\"main\""),
                "the artifact carries one export surface per module keyed by "
                    + "module identity");
            check(source.contains("public static void main(String[] args)"),
                "the project class publishes public static void main");
            check(source.contains("public final class Main"),
                "the project class is one public final class");

            String buildCp = absoluteClasspath();
            ProcessOutcome javac = runProcess(project, "javac", "--release",
                "25", "-proc:none", "-cp", buildCp, "-d", out.toString(),
                out.resolve("Main.java").toString());
            check(javac.exitCode() == 0,
                "the production JVM artifact compiles: " + javac.output());
            if (javac.exitCode() != 0) {
                return;
            }
            ProcessOutcome run = runProcess(out, "java", "-cp",
                out + File.pathSeparator + buildCp, "Main");
            check(run.exitCode() == 0,
                "the production JVM artifact runs clean: exit="
                    + run.exitCode() + " output=" + run.output());
            checkEq(1, countOccurrences(run.output(), "PROBE|MAIN-ONCE"),
                "the entry main runs exactly once under java");
        } finally {
            deleteRecursively(project);
        }
    }

    private static void testProductionLuaJitTwoModuleSurfaces()
            throws Exception {
        System.out.println("-- LuaJIT two-module realizable fixture: one "
            + "artifact per target and the executed export surfaces --");

        Path project = Files.createTempDirectory("production-dispatch-surf-");
        try {
            write(project, "deal.json", DEAL_JSON_LUA);
            write(project, "src/lib.deal", LIB_SOURCE);
            write(project, "src/main.deal", APP_SOURCE);

            ArmCompile arm = compileWithInvocation(project, "src/main.deal",
                productionInvocation());
            check(arm.success(), "the two-module production compile succeeds: "
                + arm.orchestrator().diagnostics());
            if (!arm.success()) {
                return;
            }
            CompilationOrchestrator orchestrator = arm.orchestrator();
            checkEq(1, orchestrator.semanticEmissionCount(),
                "the two-module compile records one project emission");
            List<String> artifacts = artifactFiles(project.resolve("out"));
            check(artifacts.contains("main.lua")
                    && !artifacts.contains("lib.lua"),
                "the two-module compile publishes one artifact named for the "
                    + "entry module: " + artifacts);

            // The executed surfaces: the entry surface is returned by the
            // chunk; a runner probe asserts both per-module surfaces and
            // their declaration-order entries after execution.
            write(project, "probe.lua", """
                local surfaces = dofile("out/main.lua")
                assert(type(surfaces) == "table", "the chunk returns the entry surface")
                assert(type(surfaces.label) == "table", "the entry surface carries label")
                assert(surfaces.label[__dealCarrierKeys.kind] == "function", "the landed entry shape")
                local lib = __exportSurfaces["lib"]
                assert(type(lib) == "table", "the lib surface exists")
                assert(type(lib.value) == "table" and lib.value[__dealCarrierKeys.kind] == "function",
                  "the lib surface carries value")
                assert(type(lib.label) == "table" and lib.label[__dealCarrierKeys.kind] == "function",
                  "the lib surface carries label")
                return surfaces
                """);
            ProcessOutcome probe = runProcess(project, "luajit", "probe.lua");
            check(probe.exitCode() == 0,
                "the executed entry surface carries its declared exports: "
                    + probe.output());
            ProcessOutcome run = runProcess(project.resolve("out"), "luajit",
                "main.lua");
            checkEq(1, countOccurrences(run.output(), "PROBE|MAIN-ONCE"),
                "the two-module entry main runs exactly once");
        } finally {
            deleteRecursively(project);
        }
    }


    /**
     * The same-name sibling pair on the shared-IR production lanes
     * (ISSUE-0809): {@code src/m/ir.deal} and {@code src/m/codec.deal}
     * both export {@code Ir} with different defaults, so one canonical
     * class identity covers two declarations. The recorded contextual
     * provider selects the exact declaring provider, so the released
     * LuaJIT and JVM artifacts construct the demanded default (7, never
     * the sibling's 93) and print {@code ok} with an empty stderr and
     * exit 0.
     */
    private static void testProductionSameNameSiblingSharedIr()
            throws Exception {
        System.out.println("-- public compilation: same-name sibling class"
            + " declarations on the shared-IR production lanes --");
        for (boolean codecFirst : new boolean[] { true, false }) {
            checkPublicProject("same-name sibling"
                    + (codecFirst ? " (codec first)" : " (ir first)"),
                "production-same-name-",
                Map.of("src/m/ir.deal", CONTEXTUAL_IR_SOURCE,
                    "src/m/codec.deal", CONTEXTUAL_CODEC_SOURCE,
                    "src/main.deal", codecFirst
                        ? CONTEXTUAL_BODY_CODEC_FIRST : CONTEXTUAL_BODY_IR_FIRST),
                List.of("luajit", "jvm"));
        }
    }

    /**
     * The standalone callee-parameter and declared-return provider lanes
     * (review cycle 5, finding 1): with the entry's own same-identity
     * {@code Ir} declaration present, the imported parameter/return
     * contexts must still construct the imported declaration (7) on the
     * real LuaJIT and JVM production lanes, in either import order, and
     * the explicitly annotated local literal must stay the entry's own
     * declaration (5).
     */
    private static void testProductionLocalDeclarationBesideCalleeProvider()
            throws Exception {
        System.out.println("-- public compilation: the imported callee-parameter "
            + "and declared-return providers beside a same-identity local "
            + "declaration on the shared-IR production lanes --");
        for (boolean codecFirst : new boolean[] { true, false }) {
            checkPublicProject("local-beside-provider"
                    + (codecFirst ? " (codec first)" : " (ir first)"),
                "production-local-provider-",
                Map.of("src/ir.deal", CONTEXTUAL_IR_SOURCE,
                    "src/codec.deal", CONTEXTUAL_CODEC_SOURCE,
                    "src/main.deal", codecFirst
                        ? localProviderBody(
                            "import * as codec from \"./codec\";\n",
                            "import * as ir from \"./ir\";\n")
                        : localProviderBody(
                            "import * as ir from \"./ir\";\n",
                            "import * as codec from \"./codec\";\n")),
                List.of("luajit", "jvm"));
        }
    }

    /**
     * The call-result receiver and function-typed callee fixture (review
     * cycle 1 findings 1 and 2): the callee's declared return carries the
     * provider, so the chained read, the inferred binding's read/write
     * and the function-typed argument literal all select that declaration
     * (7 and the written 11), never the sibling's 93.
     */
    private static final String CALL_RECEIVER_IR_SOURCE = """
        export class Ir { a: number; nick?: string; }
        export function make(): Ir { return { a: 7 }; }
        export function useIr(x: Ir): number { return x.a; }
        """;

    private static final String CALL_RECEIVER_CODEC_SOURCE = """
        export class Ir { a: number; nick: number; }
        """;

    private static String callReceiverBody(String firstImport,
            String secondImport) {
        return "import * as console from \"std/console\";\n"
            + firstImport + secondImport
            + "\n"
            + "function wrap(): ir.Ir { return { a: 7 }; }\n"
            + "\n"
            + "export function main(): null {\n"
            + "  let v: number = ir.make().a;\n"
            + "  let f: (x: ir.Ir) => number = ir.useIr;\n"
            + "  let n: number = f({a: 7});\n"
            + "  let y = ir.make();\n"
            + "  y.a = 11;\n"
            + "  let w: number = y.a;\n"
            + "  let q: number = wrap().a;\n"
            + "  let z = ir.make();\n"
            + "  let had: boolean = has(z.nick);\n"
            + "  delete z.nick;\n"
            + "  if (v === 7 && n === 7 && w === 11 && q === 7 && !had) {\n"
            + "    console.log(\"ok\");\n"
            + "  } else {\n"
            + "    console.log(\"wrong\");\n"
            + "  }\n"
            + "  return null;\n"
            + "}\n";
    }

    /**
     * The call-result receiver lanes (review cycle 1 findings 1 and 2):
     * a receiver whose type is the callee's declared return, an inferred
     * binding of it, and a function-typed callee's argument literal all
     * select the declared provider's declaration beside a same-identity
     * sibling on the real LuaJIT and JVM production lanes, in either
     * import order, printing {@code ok} with an empty stderr and exit 0.
     */
    private static void testProductionCallReceiverProviderBesideSameIdentityLocal()
            throws Exception {
        System.out.println("-- public compilation: the call-result receiver "
            + "and function-typed callee providers beside a same-identity "
            + "sibling on the shared-IR production lanes --");
        for (boolean codecFirst : new boolean[] { true, false }) {
            checkPublicProject("call-receiver provider"
                    + (codecFirst ? " (codec first)" : " (ir first)"),
                "production-call-receiver-",
                Map.of("src/m/ir.deal", CALL_RECEIVER_IR_SOURCE,
                    "src/m/codec.deal", CALL_RECEIVER_CODEC_SOURCE,
                    "src/main.deal", codecFirst
                        ? callReceiverBody(
                            "import * as codec from \"./m/codec\";\n",
                            "import * as ir from \"./m/ir\";\n")
                        : callReceiverBody(
                            "import * as ir from \"./m/ir\";\n",
                            "import * as codec from \"./m/codec\";\n")),
                List.of("luajit", "jvm"));
        }
    }

    /**
     * The class-field nested literal (finding 1): the enclosing class's
     * field declares the same-named sibling provider, and the entry
     * provides a nested literal whose expectation names that field's
     * declaration.
     */
    private static final String NESTED_FIELD_PROVIDER_IR_SOURCE = """
        import * as codec from "./codec";
        export class Ir { inner: codec.Ir; a: number; }
        """;

    private static final String NESTED_FIELD_PROVIDER_CODEC_SOURCE = """
        export class Ir { b: number; }
        """;

    private static final String NESTED_FIELD_PROVIDER_MAIN_SOURCE = """
        import * as console from "std/console";
        import * as ir from "./m/ir";
        export function main(): null {
          let x: ir.Ir = { inner: {b: 93}, a: 7 };
          if (x.inner.b === 93 && x.a === 7) { console.log("ok"); }
          return null;
        }
        """;

    /**
     * The transitive provider literal (finding 2): the entry imports
     * only the intermediate module, whose field declaration names the
     * same-named sibling provider's class in the same directory.
     */
    private static final String TRANSITIVE_PROVIDER_IR_SOURCE = """
        import * as codec from "./codec";
        export class Part { z: string; }
        export class Outer { inner: codec.Part; }
        """;

    private static final String TRANSITIVE_PROVIDER_CODEC_SOURCE = """
        export class Part { b: number; }
        """;

    private static final String TRANSITIVE_PROVIDER_MAIN_SOURCE = """
        import * as console from "std/console";
        import * as ir from "./m/ir";
        export function main(): null {
          let x: ir.Outer = { inner: {b: 93} };
          if (x.inner.b === 93) { console.log("ok"); }
          return null;
        }
        """;

    /**
     * The class-field and transitive provider lanes (review cycle 2
     * findings 1 and 2): the field's declared provider owns the nested
     * literal, and a declaration reached only through an imported
     * declaration resolves through the whole-project provider index —
     * never a same-spelled declaration of one identity. Both run through
     * the release-owned production invocation
     * on the real LuaJIT and JVM lanes, printing {@code ok} with an empty
     * stderr and exit 0.
     */
    private static void testProductionNestedFieldAndTransitiveProviders()
            throws Exception {
        System.out.println("-- public compilation: the class-field and "
            + "transitive same-identity providers own their nested literals on "
            + "the shared-IR production lanes --");
        checkPublicProject("class-field provider", "production-nested-provider-",
            Map.of("src/m/ir.deal", NESTED_FIELD_PROVIDER_IR_SOURCE,
                "src/m/codec.deal", NESTED_FIELD_PROVIDER_CODEC_SOURCE,
                "src/main.deal", NESTED_FIELD_PROVIDER_MAIN_SOURCE),
            List.of("luajit", "jvm"));
        checkPublicProject("transitive provider", "production-nested-provider-",
            Map.of("src/m/ir.deal", TRANSITIVE_PROVIDER_IR_SOURCE,
                "src/m/codec.deal", TRANSITIVE_PROVIDER_CODEC_SOURCE,
                "src/main.deal", TRANSITIVE_PROVIDER_MAIN_SOURCE),
            List.of("luajit", "jvm"));
    }

    /**
     * The plan-imported/non-plan-local same-identity JSON fixture (review
     * cycle 4, finding 1): the imported sibling is the only plan-bearing
     * ({@code @jsonable}) declaration while the entry's own same-identity
     * sibling generates none, so the emitted plan table carries the
     * imported declaration's layout and the nested JSON round trip walks
     * its fields. The construction and the nested link must agree on the
     * imported declaration under the real backends.
     */
    private static final String PLAN_IMPORTED_OTHER_SOURCE = """
        // @jsonable
        export class Point { a: number; }
        """;

    private static final String PLAN_IMPORTED_MAIN_SOURCE = """
        import * as console from "std/console";
        import * as other from "./other";

        export class Point { nick: string; }

        // @jsonable
        export class Wrap { p: other.Point; }

        export function main(): null {
          let w: Wrap = {p: {a: 7}};
          let s: string = Wrap$toJson(w);
          if (s !== "{\\\"p\\\":{\\\"a\\\":7.0}}") {
            throw { code: "WRONG", message: s };
          }
          console.log("ok");
          return null;
        }
        """;

    /**
     * The local-plan mirror (review cycle 2): the entry's own declaration
     * is the only plan-bearing declaration while the imported sibling
     * generates none, so the local declaration owns the plan and the
     * nested round trip — the production lanes must keep both directions
     * exact.
     */
    private static final String PLAN_LOCAL_OTHER_SOURCE = """
        export class Point { nick: string; }
        """;

    private static final String PLAN_LOCAL_MAIN_SOURCE = """
        import * as console from "std/console";
        import * as other from "./other";

        // @jsonable
        export class Point { nick: string; }

        // @jsonable
        export class Wrap { p: Point; }

        export function main(): null {
          let w: Wrap = {p: {nick: "l"}};
          let s: string = Wrap$toJson(w);
          if (s !== "{\\\"p\\\":{\\\"nick\\\":\\\"l\\\"}}") {
            throw { code: "WRONG", message: s };
          }
          console.log("ok");
          return null;
        }
        """;

    /**
     * The plan-imported/non-plan-local same-identity JSON link and its
     * local-plan mirror on the shared-IR production lanes (review cycle
     * 4, finding 1): the generated plan table carries the unique
     * plan-bearing declaration's layout under the canonical identity in
     * both directions, so the nested construction, plan walk and emitted
     * artifact agree and the real LuaJIT and JVM artifacts print
     * {@code ok} with an empty stderr and exit 0.
     */
    private static void testProductionPlanOwnerJsonLinks()
            throws Exception {
        System.out.println("-- public compilation: the plan-imported/"
            + "non-plan-local and local-plan same-identity JSON links"
            + " execute the exact plan-bearing declaration on every target --");
        for (boolean importedPlan : new boolean[] { true, false }) {
            checkPublicProject(importedPlan ? "imported plan" : "local plan",
                "production-plan-owner-",
                Map.of("src/other.deal", importedPlan
                        ? PLAN_IMPORTED_OTHER_SOURCE : PLAN_LOCAL_OTHER_SOURCE,
                    "src/main.deal", importedPlan
                        ? PLAN_IMPORTED_MAIN_SOURCE : PLAN_LOCAL_MAIN_SOURCE),
                List.of("js", "luajit", "jvm"));
        }
    }

    /**
     * The same-identity sibling declarations with different fields and no
     * generated JSON helper (review cycle 2, finding 1): the entry's own
     * declaration and the imported sibling's declaration share the
     * canonical identity, so every construction site must use its own
     * declaration's field set and default owner. The local construction
     * fills the entry's {@code nick} with its own default, the
     * shared-factory construction runs the imported declaration's {@code a}
     * default, and an explicitly provided field skips its local default.
     */
    private static final String LAYOUT_SIBLING_OTHER_SOURCE = """
        export class Point { a: number; }
        """;

    private static final String LAYOUT_SIBLING_MAIN_SOURCE = """
        import * as console from "std/console";
        import * as other from "./other";

        export class Point { nick: string; }

        export function main(): null {
          let local: Point = {nick: "local"};
          let imported: other.Point = {a: 7};
          let provided: Point = { nick: "given" };
          if (local.nick !== "local") {
            throw { code: "WRONG", message: "local" };
          }
          if (imported.a !== 7) {
            throw { code: "WRONG", message: "imported" };
          }
          if (provided.nick !== "given") {
            throw { code: "WRONG", message: "provided" };
          }
          console.log("ok");
          return null;
        }
        """;

    /**
     * The same-identity different-field non-JSON control on the JS
     * production lane (review cycle 2, finding 1): each declaration's
     * construction renders its own layout (the emitted per-module
     * constructor plans stay per declaration), the shared factory transfer
     * runs the imported owner's default, and the real Node artifact
     * prints {@code ok} with an empty stderr and exit 0.
     */
    private static void testProductionSameIdentitySiblingLayouts()
            throws Exception {
        System.out.println("-- public compilation: same-identity sibling"
            + " declarations with different fields keep their own layouts on"
            + " the JavaScript production lane --");
        Path project = Files.createTempDirectory("production-sibling-layout-");
        try {
            write(project, "deal.json", """
                {
                  "languageVersion": "1.4",
                  "stdlib": "1.4",
                  "moduleRoots": ["src"],
                  "dependencies": {},
                  "externals": {},
                  "backend": "js",
                  "output": "out"
                }
                """);
            write(project, "src/other.deal", LAYOUT_SIBLING_OTHER_SOURCE);
            write(project, "src/main.deal", LAYOUT_SIBLING_MAIN_SOURCE);

            ProjectOutcome compile = productionCompile(project,
                "src/main.deal", "out");
            check(compile.exitCode() == 0,
                "the same-identity different-field JS compile succeeds: "
                    + compile.stderr());
            check(compile.stderr().isEmpty(),
                "the same-identity different-field JS compile emits no"
                    + " diagnostic: " + compile.stderr());
            if (compile.exitCode() != 0) {
                return;
            }
            Path out = project.resolve("out");
            String mainJs = Files.readString(out.resolve("main.js"));
            String otherJs = Files.readString(out.resolve("other.js"));
            check(mainJs.contains("const $pPoint = [{ name: \"nick\""),
                "the entry's constructor plan carries its own declaration's"
                    + " field: " + mainJs);
            check(otherJs.contains("const $pPoint = [{ name: \"a\""),
                "the imported sibling's constructor plan carries its own"
                    + " declaration's field: " + otherJs);
            check(!mainJs.contains("const $pPoint = [{ name: \"a\""),
                "the entry's constructor plan is never the sibling's"
                    + " flattened layout: " + mainJs);
            checkSiblingJsRun("same-identity different-field", out);
        } finally {
            deleteRecursively(project);
        }
    }

    /**
     * The declaration-file class as a {@code @jsonable} field type (review
     * cycle 4, findings 1-2): the checker admits it — the visible declared
     * {@code C$fromJson}/{@code C$toJson} are the jsonability fact (spec
     * §JSON serialization) — but a declaration module carries no
     * implementation lowering, so the class has no plan, factory or default
     * evaluator and no production consumer realizes a nested
     * declaration-class walk. The shared JSON layout validation therefore
     * keeps the link fail closed on every backend: exactly one E6005
     * {@code JSON_LAYOUT_COHERENCE}, no project emission and no published
     * artifact. The shape never compiles on one lane and reaches a runtime
     * walk as an infrastructure exception on another, and no nested
     * declaration descriptor is emitted without its declared defaults.
     */
    private static final String DECLARED_JSON_MODEL_DECLARATION = """
        export class Model { n: number; }
        export function Model$fromJson(s: string): Model | null;
        export function Model$toJson(v: Model): string;
        """;

    private static final String DECLARED_JSON_MODEL_MAIN = """
        import * as console from "std/console";
        import * as M from "model";

        // @jsonable
        export class Holder { m: M.Model | null; }

        export function main(): null {
          let h: Holder | null = Holder$fromJson("{\\\"m\\\":{}}");
          if (h !== null) {
            console.log(Holder$toJson(h));
          }
          return null;
        }
        """;

    private static void testProductionDeclarationClassJsonFieldFailsClosed()
            throws Exception {
        System.out.println("-- declaration-file class as a @jsonable field"
            + " type: the shared JSON layout validation fails closed on every"
            + " production lane --");
        for (String backend : List.of("js", "luajit", "jvm")) {
            Path project = Files.createTempDirectory(
                "production-declared-json-");
            try {
                write(project, "deal.json", """
                    {
                      "languageVersion": "1.4",
                      "stdlib": "1.4",
                      "moduleRoots": ["src"],
                      "dependencies": {},
                      "externals": {"model": {"declaration": "bindings/model.d.deal"}},
                      "backend": "%s",
                      "output": "out"
                    }
                    """.formatted(backend));
                write(project, "bindings/model.d.deal",
                    DECLARED_JSON_MODEL_DECLARATION);
                write(project, "src/main.deal", DECLARED_JSON_MODEL_MAIN);
                Path out = project.resolve("out");

                ArmCompile arm = compileWithInvocation(project,
                    "src/main.deal", productionInvocation());
                check(!arm.success(), backend + ": the declaration-class JSON"
                    + " project fails closed");
                List<CompilerDiagnostic> errors = arm.orchestrator()
                    .diagnostics().stream()
                    .filter(d -> "error".equals(d.severity())).toList();
                checkEq(1, errors.size(), backend + ": exactly one error"
                    + " diagnostic: " + arm.orchestrator().diagnostics());
                if (errors.size() == 1) {
                    check(errors.get(0).code().equals("E6005")
                            && errors.get(0).message().contains(
                                "references imported nested class "
                                    + "@$external/model/Model without"
                                    + " shared-factory facts"),
                        backend + ": the one failure is the pinned E6005"
                            + " JSON_LAYOUT_COHERENCE nested"
                            + " declaration-class rule: "
                            + errors.get(0).message());
                }
                checkEq(0, arm.orchestrator().semanticEmissionCount(),
                    backend + ": the rejected compile records no project"
                        + " emission");
                checkEq(List.of(), artifactFiles(out), backend
                    + ": the rejected compile publishes no artifact");
                if ("js".equals(backend)) {
                    // The public CLI carries the same failure, status and
                    // absent artifact set.
                    ProjectOutcome compile = productionCompile(project,
                        "src/main.deal", "out");
                    checkEq(1, compile.exitCode(), "js: the public compile"
                        + " rejects the declaration-class JSON field");
                    check(compile.stderr().contains("E6005")
                            && compile.stderr().contains(
                                "without shared-factory facts"),
                        "js: the public diagnostic carries the E6005 nested"
                            + " declaration-class rule: " + compile.stderr());
                    checkEq(List.of(), artifactFiles(out), "js: the public"
                        + " rejection publishes no artifact");
                }
            } finally {
                deleteRecursively(project);
            }
        }
    }

    private static void testProductionSiblingModuleProviderIdentity()
            throws Exception {
        System.out.println("-- public compilation: sibling class-declaring"
            + " modules in one directory keep their own provider identity"
            + " and run on every target --");

        for (boolean reversed : new boolean[] { false, true }) {
            checkPublicProject("sibling" + (reversed ? " (reversed imports)" : ""),
                "production-sibling-",
                Map.of("src/m/ir.deal", SIBLING_IR_SOURCE,
                    "src/m/codec.deal", SIBLING_CODEC_SOURCE,
                    "src/main.deal", reversed
                        ? SIBLING_MAIN_SOURCE_REVERSED : SIBLING_MAIN_SOURCE),
                List.of("js", "luajit", "jvm"));
        }
    }

    private static void checkPublicProject(String name, String prefix,
            Map<String, String> sources, List<String> backends) throws Exception {
        Path project = Files.createTempDirectory(prefix);
        try {
            for (Map.Entry<String, String> source : sources.entrySet()) {
                write(project, source.getKey(), source.getValue());
            }
            for (String backend : backends) {
                String label = name + " " + backend;
                write(project, "deal.json", """
                    {
                      "languageVersion": "1.4",
                      "stdlib": "1.4",
                      "moduleRoots": ["src"],
                      "dependencies": {},
                      "externals": {},
                      "backend": "%s",
                      "output": "out"
                    }
                    """.formatted(backend));
                Path out = project.resolve("out");
                try {
                    ProjectOutcome compile = productionCompile(project,
                        "src/main.deal", "out");
                    check(compile.exitCode() == 0,
                        "the " + label + " public compile succeeds: " + compile.stderr());
                    check(compile.stderr().isEmpty(),
                        "the " + label + " public compile emits no diagnostic: "
                            + compile.stderr());
                    if (compile.exitCode() != 0) {
                        continue;
                    }
                    switch (backend) {
                        case "js" -> checkSiblingJsRun(label, out);
                        case "luajit" -> checkSiblingLuaRun(label, out);
                        case "jvm" -> checkSiblingJvmRun(label, project, out);
                        default -> throw new IllegalArgumentException("unknown backend: " + backend);
                    }
                } finally {
                    deleteRecursively(out);
                }
            }
        } finally {
            deleteRecursively(project);
        }
    }

    /** The JS artifact of the sibling project under the real node. */
    private static void checkSiblingJsRun(String label, Path out)
            throws Exception {
        checkJsArtifactRun(label, out, "main.js");
    }

    /**
     * One named JS entry artifact under the real node through the bounded
     * separate-stream runner: the focused acceptance drive requires node,
     * never a skip, and the artifact must print exactly {@code ok} with an
     * empty stderr and exit 0.
     */
    private static void checkJsArtifactRun(String label, Path out,
            String artifact) throws Exception {
        checkSiblingStdout(label, "node",
            runBounded(out, "node", artifact));
    }

    /** The LuaJIT artifact of the sibling project under the real luajit
     * through the bounded separate-stream runner. */
    private static void checkSiblingLuaRun(String label, Path out)
            throws Exception {
        checkSiblingStdout(label, "luajit",
            runBounded(out, "luajit", "main.lua"));
    }

    /**
     * The JVM artifact of the sibling project: javac --release 25 then
     * java, the pinned two-step of this suite. Both steps run through the
     * bounded separate-stream runner against the running distribution's
     * absolute classpath.
     */
    private static void checkSiblingJvmRun(String label, Path project,
            Path out) throws Exception {
        String classpath = absoluteClasspath();
        BoundedProcess.BoundedResult javac = compileBoundedJvm(project, "javac",
            "--release", "25", "-proc:none", "-cp", out + File.pathSeparator + classpath,
            "-d", out.toString(), out.resolve("Main.java").toString());
        check(javac.exitCode() == 0, "the " + label + " JVM artifact"
            + " compiles: " + boundedOutput(javac));
        if (javac.exitCode() != 0) {
            return;
        }
        checkSiblingStdout(label, "java", runBounded(out, "java", "-cp",
            out + File.pathSeparator + classpath, "Main"));
    }

    /** The sibling project's pinned observable: stdout "ok", exit 0, and
     * an empty stderr (separate streams, no merged-output masking). */
    private static void checkSiblingStdout(String label, String tool,
            BoundedProcess.BoundedResult run) {
        check(run.exitCode() == 0, "the " + label + " artifact runs clean"
            + " under " + tool + ": exit=" + run.exitCode() + " output="
            + boundedOutput(run));
        check("ok".equals(run.stdout().trim()),
            "the " + label + " artifact prints 'ok' under " + tool
                + ", got '" + run.stdout() + "'");
        check(run.stderr().isEmpty(), "the " + label + " artifact runs"
            + " with empty stderr under " + tool + ", got '"
            + run.stderr() + "'");
    }

    /**
     * The GitHub #6 same-name sibling collision (review cycle 1): an
     * same-named sibling that declares a non-exported class of the
     * demanded name must never become the provider. Both import orders
     * compile through the release-owned production invocation in one
     * project (one deployment identity, so the provider digests are
     * comparable), and the completed plan occurrence and deferred
     * dependency edge must carry the declaring module, its exact
     * declaration range, and one import-order-independent provider
     * digest — never the private sibling's plan.
     */
    private record CollisionCase(String name, String irSource, String codecSource,
            List<String> entries, boolean plan, boolean codecPlan, boolean runtime,
            boolean wrap, boolean execute) {
    }

    private static Map<String, String> checkCollision(CollisionCase scenario)
            throws Exception {
        Path root = Files.createTempDirectory("production-" + scenario.name() + "-");
        try {
            return checkCollision(scenario, root);
        } finally {
            deleteRecursively(root);
        }
    }

    private static Map<String, String> checkCollision(CollisionCase scenario, Path root)
            throws Exception {
        Map<String, String> firstDigests = new LinkedHashMap<>();
        write(root, "deal.json", DEAL_JSON_JS);
        write(root, "src/m/ir.deal", scenario.irSource());
        write(root, "src/m/codec.deal", scenario.codecSource());
        for (int i = 0; i < scenario.entries().size(); i++) {
            String stem = i == 0 ? "main" : "main_ir_first";
            String entry = "src/" + stem + ".deal";
            String label = scenario.name() + (i == 0 ? " codec-first" : " ir-first");
            write(root, entry, scenario.entries().get(i));
            ArmCompile compile = compileWithInvocation(root, entry, productionInvocation());
            check(compile.success(), label + " compiles: " + compile.orchestrator().diagnostics());
            if (!compile.success()) {
                continue;
            }
            CompilationOrchestrator orchestrator = compile.orchestrator();
            String mainUri = serializerModuleUri(orchestrator, "/" + entry);
            String irUri = serializerModuleUri(orchestrator, "/src/m/ir.deal");
            String codecUri = serializerModuleUri(orchestrator, "/src/m/codec.deal");
            check(mainUri != null && irUri != null && codecUri != null,
                label + " exposes every module identity");
            if (mainUri == null || irUri == null || codecUri == null) {
                continue;
            }
            DiagnosticRange ir = classDeclarationRange(scenario.irSource(),
                irUri.substring("file://".length()), "Ir");
            DiagnosticRange sibling = classDeclarationRange(scenario.codecSource(),
                codecUri.substring("file://".length()), "Ir");
            check(ir != null && sibling != null, label + " resolves both sibling ranges");
            if (ir == null || sibling == null) {
                continue;
            }
            Map<String, String> digests = new LinkedHashMap<>();
            if (scenario.plan()) {
                digests.put("Ir plan", checkClassDefaultResource(orchestrator, label,
                    mainUri, irUri, "ir", "Ir", ir, sibling));
            }
            if (scenario.codecPlan()) {
                DiagnosticRange codec = classDeclarationRange(scenario.codecSource(),
                    codecUri.substring("file://".length()), "Codec");
                check(codec != null, label + " resolves Codec range");
                digests.put("Codec plan", checkClassDefaultResource(orchestrator, label,
                    mainUri, codecUri, "codec", "Codec", codec, sibling));
            }
            if (scenario.runtime()) {
                String digest = checkCollisionRuntimeEdge(orchestrator, label, mainUri,
                    irUri, "Ir", ir, sibling);
                digests.put("Ir runtime", digest);
                if (scenario.plan()) {
                    checkEq(digests.get("Ir plan"), digest,
                        label + " runtime and plan name the same provider digest");
                }
            }
            if (scenario.wrap()) {
                DiagnosticRange wrap = classDeclarationRange(scenario.irSource(),
                    irUri.substring("file://".length()), "Wrap");
                check(wrap != null, label + " resolves Wrap range");
                digests.put("Wrap runtime", checkCollisionRuntimeEdge(orchestrator,
                    label, mainUri, irUri, "Wrap", wrap, null));
            }
            if (i == 0) {
                firstDigests.putAll(digests);
            } else {
                checkEq(firstDigests, digests, label + " provider digests are import-order independent");
            }
            if (scenario.execute()) {
                checkJsArtifactRun(label, root.resolve("out"), stem + ".js");
            }
        }
        return firstDigests;
    }

    private static String checkCollisionRuntimeEdge(CompilationOrchestrator orchestrator,
            String label, String mainUri, String providerUri, String className,
            DiagnosticRange declaration, DiagnosticRange forbidden) {
        List<RuntimeImportDependency> edges = classEdges(orchestrator, mainUri,
            className, RuntimeImportDependency.Reason.RUNTIME_USE);
        checkEq(1, edges.size(), label + " exactly one ordinary " + className + " edge");
        if (edges.size() != 1) {
            return null;
        }
        RuntimeImportDependency edge = edges.get(0);
        checkEq(providerUri, edge.toSemanticModuleIdentity().canonicalResolvedSourceUri(),
            label + " " + className + " declaring provider");
        checkEq("ir", edge.importAlias(), label + " " + className + " spelled alias");
        DiagnosticRange actual = edge.semanticResourceIdentity()
            .lexicalDeclarationIdentity().sourceScalarRange();
        checkEq(declaration, actual, label + " " + className + " exact declaration range");
        check(!actual.equals(forbidden), label + " " + className + " excludes sibling");
        check(edge.providerContractDigest() != null && !edge.providerContractDigest().isEmpty(),
            label + " " + className + " nonempty provider digest");
        return edge.providerContractDigest();
    }

    private static void testProductionSameNameSiblingCollision() throws Exception {
        checkCollision(new CollisionCase("same-name", COLLISION_IR_SOURCE, COLLISION_CODEC_SOURCE,
            List.of(COLLISION_MAIN_CODEC_FIRST, COLLISION_MAIN_IR_FIRST), true, true, false, false, false));
    }

    /**
     * The contextual-call collision (review cycle 2): the class context
     * arrives only through the resolved imported callee's parameter, and
     * both same-named siblings export the demanded class name. The
     * recorded occurrence and deferred edge must name the callee's
     * declaring module, its exact declaration range, and the class's own
     * plan digest — in both import orders, and the published artifact
     * must construct that provider's class (the demanded default 7 is
     * observable on stdout, never the sibling's 93).
     */
    private static void testProductionContextualCallSiblingCollision() throws Exception {
        Path root = Files.createTempDirectory("production-contextual-");
        try {
            Map<String, String> plan = checkCollision(new CollisionCase("contextual",
                CONTEXTUAL_IR_SOURCE, CONTEXTUAL_CODEC_SOURCE,
                List.of(CONTEXTUAL_MAIN_CODEC_FIRST, CONTEXTUAL_MAIN_IR_FIRST),
                true, true, false, false, true), root);
            Map<String, String> body = checkCollision(new CollisionCase("contextual-body",
                CONTEXTUAL_IR_SOURCE, CONTEXTUAL_CODEC_SOURCE,
                List.of(CONTEXTUAL_BODY_CODEC_FIRST, CONTEXTUAL_BODY_IR_FIRST),
                false, false, true, false, true), root);
            checkEq(plan.get("Ir plan"), body.get("Ir runtime"),
                "contextual body and plan use the same provider digest");
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * The shadowed-local-callee collision (review cycle 4): a nested
     * function declaration shadows a same-named module-level function,
     * so the resolved callee's own declaration — never the same-named
     * top-level declaration — owns the literal's parameter context. The
     * completed ordinary walk and the published artifact must follow
     * the nested declaration's provider in both import orders.
     */
    private static void testProductionShadowedLocalCalleeCollision() throws Exception {
        checkCollision(new CollisionCase("shadowed-callee", CONTEXTUAL_IR_SOURCE,
            CONTEXTUAL_CODEC_SOURCE, List.of(SHADOWED_CALLEE_CODEC_FIRST, SHADOWED_CALLEE_IR_FIRST),
            false, false, true, false, true));
    }

    /**
     * The nested-literal control (review cycle 2): the demanded class
     * arrives as a nested field literal of an imported class, so the
     * enclosing field annotation names the outer class only and the
     * declaring program's own field declaration is the nested class's
     * provider. The completed plan, its digest, and the published
     * artifact's construction must all name that provider.
     */
    private static void testProductionNestedSiblingCollision() throws Exception {
        checkCollision(new CollisionCase("nested", NESTED_IR_SOURCE, NESTED_CODEC_SOURCE,
            List.of(NESTED_MAIN_CODEC_FIRST), true, false, false, false, true));
    }

    /**
     * The declared-context collision (review cycle 3): the demand
     * arrives through a declared return type, an array element
     * annotation, and a member-assignment target type. The completed
     * plan occurrence and deferred edge must name the provider the
     * declaration names — one import-order-independent digest — and the
     * published artifact must print the demanded default (7).
     */
    private static void testProductionDeclaredContextSiblingCollision() throws Exception {
        checkCollision(new CollisionCase("declared-context", RETURN_IR_SOURCE, RETURN_CODEC_SOURCE,
            List.of(RETURN_MAIN_CODEC_FIRST, RETURN_MAIN_IR_FIRST), true, true, true, false, true));
    }

    /**
     * The identifier-assignment collision (review cycle 5): an
     * assignment value whose class context is the assigned binding's own
     * declared annotation must construct the provider that annotation
     * names. Both import orders compile through the release-owned
     * production invocation; the ordinary walk records exactly one
     * defining-provider edge (never the sibling), the provider digest is
     * import-order independent, and the published artifact prints the
     * demanded default value.
     */
    private static void testProductionIdentifierAssignmentCollision() throws Exception {
        checkCollision(new CollisionCase("identifier-assignment", ASSIGN_IR_SOURCE, ASSIGN_CODEC_SOURCE,
            List.of(ASSIGN_MAIN_CODEC_FIRST, ASSIGN_MAIN_IR_FIRST), false, false, true, false, true));
    }

    private static void testProductionMemberAssignmentCollision() throws Exception {
        checkCollision(new CollisionCase("member-assignment", ASSIGN_MEMBER_IR_SOURCE,
            ASSIGN_MEMBER_CODEC_SOURCE, List.of(ASSIGN_MEMBER_MAIN_CODEC_FIRST, ASSIGN_MEMBER_MAIN_IR_FIRST),
            false, false, true, true, true));
    }

    /**
     * The shadowing-initializer crossing (review 0005; ISSUE-0809 review
     * cycle 2): the assignment inside a shadowing let's own initializer
     * targets the enclosing binding (the pending declaration is not
     * visible there, docs/spec-v1.4.md §Name resolution — Declaration
     * order), so the constructed literal carries that binding's declared
     * provider — the enclosing annotation names {@code ir} even when the
     * not-yet-visible inner declaration names the same-named sibling
     * and the import that happens to come first. The inner declaration of
     * another provider never retags that value: both import orders are
     * refused with E3019 at the shadowing initializers and nothing is
     * published, so the import order can never decide the demanded
     * default.
     */
    private static void testProductionShadowingInitializerCrossingRejected()
            throws Exception {
        Path root = Files.createTempDirectory("production-shadowing-initializer-");
        try {
            write(root, "deal.json", DEAL_JSON_JS);
            write(root, "src/m/ir.deal", SHADOW_IR_SOURCE);
            write(root, "src/m/codec.deal", SHADOW_CODEC_SOURCE);
            String[] entries = { "src/main.deal", "src/main_ir_first.deal" };
            String[] sources = { SHADOW_MAIN_CODEC_FIRST, SHADOW_MAIN_IR_FIRST };
            for (int i = 0; i < entries.length; i++) {
                String label = i == 0 ? "codec-first" : "ir-first";
                write(root, entries[i], sources[i]);
                ArmCompile compile = compileWithInvocation(root, entries[i],
                    productionInvocation());
                check(!compile.success(),
                    "the shadowing same-identity crossing is refused (" + label + ")");
                List<CompilerDiagnostic> diagnostics =
                    compile.orchestrator().diagnostics();
                long crossings = diagnostics.stream()
                    .filter(d -> "E3019".equals(d.code())).count();
                checkEq(2L, crossings,
                    "the block initializer and the loop-head initializer are "
                        + "named as crossings (" + label + "): " + diagnostics);
                check(diagnostics.stream().noneMatch(d -> "E6005".equals(d.code())),
                    "the crossing is a frontend rejection, never a lowering "
                        + "fail-close (" + label + "): " + diagnostics);
                check(!Files.exists(root.resolve("out")),
                    "the refused crossing publishes no artifact (" + label + ")");
            }
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * Asserts the completed graph facts of one demanded imported class:
     * exactly one class-default-plan occurrence and exactly one deferred
     * dependency edge, both on the declaring module's URI, the spelled
     * import alias, and the exact defining declaration range; the
     * forbidden (private same-name sibling) declaration is never bound.
     * Returns the edge's provider digest, or null when the shape is
     * wrong.
     */
    private static String checkClassDefaultResource(
            CompilationOrchestrator orchestrator, String label,
            String mainUri, String providerUri, String alias,
            String className, DiagnosticRange declaration,
            DiagnosticRange forbiddenDeclaration) {
        List<DefaultResourceOccurrence> occurrences =
            classPlanOccurrences(orchestrator, mainUri, className);
        checkEq(0, occurrences.size(), "the " + label + " structural declaration"
            + " owns no initializer occurrences for " + className);
        if (occurrences.size() == 1) {
            DefaultResourceOccurrence occurrence = occurrences.get(0);
            checkEq(providerUri, occurrence.toSemanticModuleIdentity()
                    .canonicalResolvedSourceUri(), "the " + label + " "
                + className + " occurrence targets the declaring module");
            checkEq(alias, occurrence.importAlias(), "the " + label + " "
                + className + " occurrence keeps the spelled alias");
            checkEq(declaration, occurrence.semanticResourceIdentity()
                    .lexicalDeclarationIdentity().sourceScalarRange(),
                "the " + label + " " + className + " occurrence carries"
                    + " the exact defining declaration range");
        }

        checkEq(0, orchestrator.completedDefaultPlans().values().stream()
                .flatMap(List::stream)
                .mapToInt(plan -> plan.plan().runtimeDependencies().size()).sum(),
            label + " has no deferred initializer edge for " + className);
        List<RuntimeImportDependency> edges = classEdges(orchestrator,
            mainUri, className, RuntimeImportDependency.Reason.RUNTIME_USE);
        checkEq(1, edges.size(), "the " + label + " completed graph"
            + " holds exactly one ordinary " + className + " edge");
        if (edges.size() != 1) {
            return null;
        }
        RuntimeImportDependency edge = edges.get(0);
        checkEq(providerUri, edge.toSemanticModuleIdentity()
                .canonicalResolvedSourceUri(), "the " + label + " "
            + className + " edge targets the declaring module");
        checkEq(alias, edge.importAlias(), "the " + label + " "
            + className + " edge keeps the spelled alias");
        checkEq(declaration, edge.semanticResourceIdentity()
                .lexicalDeclarationIdentity().sourceScalarRange(),
            "the " + label + " " + className + " edge carries the exact"
                + " defining declaration range");
        check(edge.providerContractDigest() != null
                && !edge.providerContractDigest().isEmpty(), "the "
            + label + " " + className + " edge carries its provider"
            + " digest");
        check(edges.stream().noneMatch(e -> e.semanticResourceIdentity()
                .lexicalDeclarationIdentity().sourceScalarRange()
                .equals(forbiddenDeclaration)), "the " + label + " "
            + className + " edge never binds the forbidden same-name"
            + " sibling declaration");
        return edge.providerContractDigest();
    }

    /** The class-default-plan occurrences of the given class name whose
     * consuming module is {@code fromUri}, in plan order. */
    private static List<DefaultResourceOccurrence> classPlanOccurrences(
            CompilationOrchestrator orchestrator, String fromUri,
            String className) {
        List<DefaultResourceOccurrence> matches = new ArrayList<>();
        for (List<PlannedDefaultClass> plans
                : orchestrator.completedDefaultPlans().values()) {
            for (PlannedDefaultClass planned : plans) {
                for (DefaultResourceOccurrence occurrence
                        : planned.occurrences()) {
                    if (occurrence.kind()
                            == RuntimeResourceReference.Kind
                                .IMPORTED_CLASS_DEFAULT_PLAN
                            && occurrence.fromSemanticModuleIdentity()
                                .canonicalResolvedSourceUri()
                                .equals(fromUri)
                            && occurrence.semanticResourceIdentity()
                                .lexicalDeclarationIdentity()
                                .declaredName().equals(className)) {
                        matches.add(occurrence);
                    }
                }
            }
        }
        return matches;
    }

    /** The completed dependency edges of the given class name and reason
     * whose consuming module is {@code fromUri}. */
    private static List<RuntimeImportDependency> classEdges(
            CompilationOrchestrator orchestrator, String fromUri,
            String className,
            RuntimeImportDependency.Reason reason) {
        List<RuntimeImportDependency> matches = new ArrayList<>();
        for (RuntimeImportDependency edge
                : orchestrator.runtimeDependencies()) {
            if (edge.reason() == reason
                    && edge.fromSemanticModuleIdentity()
                        .canonicalResolvedSourceUri().equals(fromUri)
                    && edge.semanticResourceIdentity()
                        .lexicalDeclarationIdentity().declaredName()
                        .equals(className)) {
                matches.add(edge);
            }
        }
        return matches;
    }

    /** The semantic module URI of the compiled input whose source path
     * ends with the given suffix, or null. */
    private static String serializerModuleUri(
            CompilationOrchestrator orchestrator, String suffix) {
        for (DefaultSerializerModuleInput input
                : orchestrator.defaultSerializerModuleInputs().values()) {
            if (input.sourcePath().endsWith(suffix)) {
                return input.location().semanticModuleIdentity()
                    .canonicalResolvedSourceUri();
            }
        }
        return null;
    }

    /** The complete source range of one top-level class declaration of
     * the given source text, in the file string the compiler uses. */
    private static DiagnosticRange classDeclarationRange(String source,
            String fileName, String className) {
        LexResult lex = new Lexer(source, fileName).tokenize();
        if (lex.hasErrors()) {
            return null;
        }
        ParseResult parse = new Parser(lex.tokens(), fileName,
            lex.directiveEvents()).parse();
        if (parse.hasErrors()) {
            return null;
        }
        for (StatementNode stmt : parse.program().statements()) {
            ClassDeclaration cd = null;
            if (stmt instanceof ClassDeclaration c) {
                cd = c;
            } else if (stmt instanceof ExportDeclaration ed
                    && ed.declaration() instanceof ClassDeclaration c) {
                cd = c;
            }
            if (cd != null && cd.name().equals(className)) {
                return cd.span().range();
            }
        }
        return null;
    }

    private static void testConversionOverflowTerminal() throws Exception {
        System.out.println("-- the production conversion-overflow fixture: the "
            + "DEAL_ERROR_CODE terminal --");

        Path project = Files.createTempDirectory("production-dispatch-ovf-");
        try {
            write(project, "deal.json", DEAL_JSON_LUA);
            write(project, "src/main.deal", OVERFLOW_SOURCE);
            ProjectOutcome compile = productionCompile(project, "src/main.deal",
                "out");
            check(compile.exitCode() == 0,
                "the overflow fixture compiles: " + compile.stderr());
            if (compile.exitCode() != 0) {
                return;
            }
            ProcessOutcome run = runProcess(project.resolve("out"), "luajit",
                "main.lua");
            check(run.exitCode() == 1
                    && run.output().contains("DEAL_ERROR_CODE: E8002"),
                "the production artifact prints DEAL_ERROR_CODE: E8002 and "
                    + "exits 1: exit=" + run.exitCode() + " output="
                    + run.output());
        } finally {
            deleteRecursively(project);
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    // =========================================================================
    // 3. Atomic failure through the dispatch
    // =========================================================================

    private static void testAtomicFailureThroughDispatch(Path sync,
            ArmCompile syncCompile) throws Exception {
        System.out.println("-- atomic failure: a failing lowering stages nothing "
            + "and preserves the previous set; the cross-module sync call "
            + "emits --");

        Path project = Files.createTempDirectory("production-dispatch-atomic-");
        try {
            Path out = project.resolve("out");
            write(project, "deal.json", DEAL_JSON_LUA);

            write(project, "src/main.deal", UNREALIZED_CONSTRUCT_SOURCE);
            // A previous artifact set in the live output root.
            write(out, "main.lua", "-- previous artifact\n");
            write(out, "deal/runtime.lua", "-- previous runtime\n");
            Map<String, byte[]> before = snapshotTree(out);
            ProjectOutcome compile = productionCompile(project, "src/main.deal",
                "out");
            check(compile.exitCode() != 0,
                "the unlowered for-let update shape production compile fails "
                    + "closed");
            check(compile.stderr().contains("E3001")
                    && compile.stderr().contains("Cannot assign"),
                "the rejected input names the type error: "
                    + compile.stderr());
            checkTreeIdentical(before, out,
                "the failing lowering preserves the previous set");

            Path syncOut = sync.resolve("out");
            check(syncCompile.success(),
                "the cross-module sync call compiles: "
                    + syncCompile.orchestrator().diagnostics());
            check(syncCompile.orchestrator().diagnostics().stream()
                    .noneMatch(d -> "E6005".equals(d.code())),
                "the cross-module sync call reports no emitter gap: "
                    + syncCompile.orchestrator().diagnostics());
            List<String> artifacts = artifactFiles(syncOut);
            check(artifacts.contains("main.lua") && !artifacts.contains("lib.lua"),
                "the emit publishes the one project artifact and no "
                    + "per-module sibling: " + artifacts);
            if (syncCompile.success()) {
                ProcessOutcome syncRun = runProcess(syncOut, "luajit", "main.lua");
                check(syncRun.exitCode() == 0,
                    "the published project artifact executes: "
                        + syncRun.output());
            }
        } finally {
            deleteRecursively(project);
        }
    }

    // =========================================================================
    // 4. The fail-closed families and the accepted closures
    // =========================================================================

    private static void testFailClosedFamilies() throws Exception {
        System.out.println("-- accept-and-emit families: HOST import, "
            + "cross-module async, arrays, Error, time, function values --");

        Path host = Files.createTempDirectory("production-dispatch-host-");
        try {
            write(host, "deal.json", HOST_DEAL_JSON_LUA);
            write(host, "cfg.d.deal", HOST_DECLARATION);
            write(host, "src/main.deal", HOST_IMPORT_SOURCE);
            ArmCompile arm = compileWithInvocation(host, "src/main.deal",
                productionInvocation());
            check(arm.success(),
                "the HOST-importing closure compiles: "
                    + arm.orchestrator().diagnostics());
            if (arm.success()) {
                checkEq(1, arm.orchestrator().semanticEmissionCount(),
                    "the HOST import records one project emission");
                List<String> artifacts = artifactFiles(host.resolve("out"));
                check(artifacts.contains("main.lua")
                        && !artifacts.contains("cfg.lua"),
                    "the HOST import publishes the one project artifact and "
                        + "no per-module sibling: " + artifacts);
                String chunk = Files.readString(host.resolve("out/main.lua"),
                    StandardCharsets.UTF_8);
                check(chunk.contains("__exportSurfaces[\"host.cfg\"] = "
                        + "__exportSurfaces[\"host.cfg\"] or "
                        + "__rt.load_host(\"host/cfg\", {")
                        && chunk.contains("[\"version\"] = "),
                    "the artifact carries the host load with the emitted "
                        + "declared map");
            }
        } finally {
            deleteRecursively(host);
        }

        Path async = Files.createTempDirectory("production-dispatch-async-");
        try {
            write(async, "deal.json", DEAL_JSON_LUA);
            write(async, "src/lib.deal", ASYNC_LIB_SOURCE);
            write(async, "src/main.deal", CROSS_ASYNC_SOURCE);
            ArmCompile arm = compileWithInvocation(async, "src/main.deal",
                productionInvocation());
            check(arm.success(),
                "the cross-module async closure compiles: "
                    + arm.orchestrator().diagnostics());
            if (arm.success()) {
                checkEq(1, arm.orchestrator().semanticEmissionCount(),
                    "the cross-module async call records one project emission");
                List<String> artifacts = artifactFiles(async.resolve("out"));
                check(artifacts.contains("main.lua")
                        && !artifacts.contains("lib.lua"),
                    "the cross-module async call publishes the one project "
                        + "artifact and no per-module sibling: " + artifacts);
                String chunk = Files.readString(async.resolve("out/main.lua"),
                    StandardCharsets.UTF_8);
                check(!chunk.contains("EXTERNAL_ASYNC_CALL")
                        && !chunk.contains("SharedM"),
                    "the artifact carries no superseded guard token and no "
                        + "per-module async-entry reference");
                ProcessOutcome run = runProcess(async.resolve("out"), "luajit",
                    "main.lua");
                check(run.exitCode() == 0,
                    "the cross-module async artifact executes: "
                        + run.output());
            }
        } finally {
            deleteRecursively(async);
        }

        // (c) The same-module async call emits and executes on both
        // targets. The awaiting call is reachable through the exported
        // zero-arity async function's published surface, so the runner's
        // invocation really runs the ASYNC_START(DEAL_BODY)/AWAIT path
        // (never merely compiling a never-invoked body).
        Path same = Files.createTempDirectory("production-dispatch-sameasync-");
        try {
            write(same, "deal.json", DEAL_JSON_LUA);
            write(same, "src/main.deal", SAME_ASYNC_SOURCE);

            // LuaJIT: the entry chunk executes under luajit and the
            // runner invokes the exported async function.
            ProjectOutcome luaCompile = productionCompile(same, "src/main.deal",
                "out");
            check(luaCompile.exitCode() == 0,
                "the same-module async closure emits: " + luaCompile.stderr());
            if (luaCompile.exitCode() != 0) {
                return;
            }
            write(same, "deal/runtime.lua",
                Files.readString(Path.of("deal/runtime.lua"), StandardCharsets.UTF_8));
            write(same, "probe.lua", """
                local surfaces = dofile("out/main.lua")
                assert(type(surfaces) == "table",
                  "the chunk returns the entry surface")
                local worker = surfaces.worker
                assert(type(worker) == "table" and worker[__dealCarrierKeys.kind] == "function",
                  "the entry surface publishes the same-module async export")
                local handle = worker.f()
                assert(type(handle) == "table" and handle[__dealCarrierKeys.kind] == "async",
                  "the published async export produces an async operation")
                require("deal.runtime").async_step(handle)
                local completion = handle.__result
                assert(handle.__done and completion == 1,
                  "the awaiting call completes with 1, got "
                    .. tostring(completion))
                """);
            ProcessOutcome probe = runProcess(same, "luajit", "probe.lua");
            check(probe.exitCode() == 0,
                "the same-module await path executes under luajit: "
                    + probe.output());

            // JVM: the artifact compiles with javac --release 25
            // -proc:none and the runner executes the exported async
            // function through the artifact's published surface.
            Path jvmOut = same.resolve("out-jvm");
            ProjectOutcome jvmCompile = runProductionCli("compile",
                same.resolve("src/main.deal").toAbsolutePath().toString(),
                "--backend", "jvm", "--output",
                jvmOut.toAbsolutePath().toString());
            check(jvmCompile.exitCode() == 0,
                "the same-module async JVM closure emits: "
                    + jvmCompile.stderr());
            if (jvmCompile.exitCode() != 0) {
                return;
            }
            String driver = "AsyncWorkerRunner_"
                + java.util.UUID.randomUUID().toString().replace("-", "");
            write(jvmOut, driver + ".java", """
                import deal.codegen.jvm.JvmRuntime;

                public final class %s {
                  public static void main(String[] args) {
                    Main.main(new String[0]);
                    JvmRuntime.Table surface = Main.EXPORT_SURFACES.get("main");
                    JvmRuntime.FunctionValue worker =
                        (JvmRuntime.FunctionValue) surface.read("worker");
                    Object completion = worker.fn.invoke(new Object[0]);
                    System.out.println("PROBE|ASYNC-RESULT|" + completion);
                  }
                }
                """.formatted(driver));
            String buildCp = absoluteClasspath();
            ProcessOutcome javac = compileJvm(same, "javac", "--release", "25",
                "-proc:none", "-cp", buildCp, "-d", jvmOut.toString(),
                jvmOut.resolve("Main.java").toString(),
                jvmOut.resolve(driver + ".java").toString());
            check(javac.exitCode() == 0,
                "the same-module async JVM artifact compiles: "
                    + javac.output());
            if (javac.exitCode() != 0) {
                return;
            }
            ProcessOutcome jvmRun = runProcess(jvmOut, "java", "-cp",
                jvmOut + File.pathSeparator + buildCp, driver);
            check(jvmRun.exitCode() == 0
                    && jvmRun.output().contains("PROBE|ASYNC-RESULT|1"),
                "the same-module await path executes under java: exit="
                    + jvmRun.exitCode() + " output=" + jvmRun.output());
        } finally {
            deleteRecursively(same);
        }

        checkArrayProductionCoverage();
        checkTimeNowMillisCoverage();
        checkLaterSliceConstruct("invalid typed declaration",
            Map.of("src/main.deal", UNREALIZED_CONSTRUCT_SOURCE),
            "Cannot assign");
        checkBuiltinErrorConstruction();

        Path removed = Files.createTempDirectory("production-dispatch-native-rejected-");
        try {
            write(removed, "deal.json", "{\"languageVersion\":\"1.4\",\"moduleRoots\":[\"src\"],\"output\":\"out\"}");
            write(removed, "src/main.deal", "// @extern-c\nexport function main(): null { return null; }\n");
            for (String backend : List.of("js", "luajit", "jvm")) {
                ProjectOutcome rejected = runProductionCli("compile",
                    removed.resolve("src/main.deal").toAbsolutePath().toString(),
                    "--backend", backend, "--output",
                    removed.resolve("out-" + backend).toAbsolutePath().toString());
                check(rejected.exitCode() != 0 && rejected.stderr().contains("E1044"),
                    backend + ": removed directive rejects at the frontend: " + rejected.stderr());
                check(!Files.exists(removed.resolve("out-" + backend)),
                    backend + ": removed directive publishes no artifact");
            }
        } finally {
            deleteRecursively(removed);
        }

    }

    /**
     * The preserved-origin public-compilation drives (ISSUE-0750): the
     * fail-closed lowering defects whose producer identifies the offending
     * construct — the default-block capture reference, the duplicate class
     * declaration, an imported implementation module, and the scalar/CRLF
     * coordinate variants — carry that construct's own exact file/span in
     * the one E6005, with the complete registry-owned message, the
     * SOURCE origin, the empty notes, and the fail-closed staging behavior
     * unchanged. Every case runs on both shared production targets, drives
     * the release-owned CLI with {@code --diagnostics-json}, pins the
     * complete human line and the complete structured range, repeats the
     * report for byte-identical determinism, and proves the previous
     * artifact set is byte-identical. The genuinely originless control is
     * the profile-guard row ({@link #checkLegacyFailClosedRow}).
     */
    private static void testPreservedLoweringOrigins() throws Exception {
        System.out.println("-- preserved lowering origins: the offending "
            + "construct's exact span, the unchanged message, the "
            + "fail-closed compile on both targets --");

        // The historical local-class seeds remain distinct frontend negatives.
        checkPreservedLoweringOrigin("default-block capture",
            Map.of("src/main.deal", DEFAULT_CAPTURE_SOURCE), "main", "src/main.deal",
            "FRONTEND", "E1057", "Local class declaration 'Local' is not allowed; classes are module-level",
            3, 3, 3, 29, 54, 80, 26);
        checkPreservedLoweringOrigin("duplicate class declaration",
            Map.of("src/main.deal", DUPLICATE_CLASS_SOURCE), "main", "src/main.deal",
            "FRONTEND", "E1057", "Local class declaration 'A' is not allowed; classes are module-level",
            3, 5, 3, 27, 58, 80, 22);
        Map<String, String> imported = new java.util.LinkedHashMap<>();
        imported.put("src/main.deal", IMPORTED_DUPLICATE_MAIN);
        imported.put("src/lib.deal", IMPORTED_DUPLICATE_LIB);
        checkPreservedLoweringOrigin("duplicate class declaration in an imported module",
            imported, "lib", "src/lib.deal", "FRONTEND", "E1057",
            "Local class declaration 'A' is not allowed; classes are module-level",
            4, 5, 4, 27, 56, 78, 22);
        checkPreservedLoweringOrigin("supplementary scalar and tab indentation",
            Map.of("src/main.deal", SUPPLEMENTARY_TAB_CAPTURE_SOURCE), "main", "src/main.deal",
            "FRONTEND", "E1057", "Local class declaration 'Local' is not allowed; classes are module-level",
            4, 2, 4, 28, 57, 83, 26);
        checkPreservedLoweringOrigin("CRLF line endings",
            Map.of("src/main.deal", DEFAULT_CAPTURE_SOURCE.replace("\n", "\r\n")),
            "main", "src/main.deal", "FRONTEND", "E1057",
            "Local class declaration 'Local' is not allowed; classes are module-level",
            3, 3, 3, 29, 56, 82, 26);
    }

    /**
     * One preserved-origin drive on both shared production targets: the
     * dispatch compile and the release-owned CLI compile of the fixture
     * fail closed with exactly one E6005 whose complete message and range
     * are the pinned offending construct's own SOURCE span, the structured
     * document agrees exactly, repeated reports are byte-identical, no
     * project is produced, and the previous artifact set is unchanged.
     */
    private static void checkPreservedLoweringOrigin(String name,
            Map<String, String> sources, String offendingModule,
            String offendingRelative, String capability, String rule,
            String originText, int startLine, int startColumn, int endLine,
            int endColumn, int startOffset, int endOffset, int scalarLength)
            throws Exception {
        Path project = Files.createTempDirectory("production-dispatch-origin-");
        try {
            for (Map.Entry<String, String> source : sources.entrySet()) {
                write(project, source.getKey(), source.getValue());
            }
            checkPreservedLoweringOriginOnTarget(project, "luajit", DEAL_JSON_LUA,
                name, offendingModule, offendingRelative, capability, rule, originText,
                startLine, startColumn, endLine, endColumn, startOffset, endOffset,
                scalarLength);
            checkPreservedLoweringOriginOnTarget(project, "jvm", DEAL_JSON_JVM,
                name, offendingModule, offendingRelative, capability, rule, originText,
                startLine, startColumn, endLine, endColumn, startOffset, endOffset,
                scalarLength);
        } finally {
            deleteRecursively(project);
        }
    }

    private static void checkPreservedLoweringOriginOnTarget(Path project,
            String target, String manifest, String name, String offendingModule,
            String offendingRelative, String capability, String rule, String originText,
            int startLine, int startColumn, int endLine, int endColumn,
            int startOffset, int endOffset, int scalarLength) throws Exception {
        {
            String context = name + " [" + target + "]";
            write(project, "deal.json", manifest);
            Path entry = project.resolve("src/main.deal").toAbsolutePath()
                .normalize();
            Path offending = project.resolve(offendingRelative).toAbsolutePath()
                .normalize();
            Path out = project.resolve("out");
            String expectedCode = "FRONTEND".equals(capability) ? rule : "E6005";
            String expectedMessage = "FRONTEND".equals(capability) ? originText : "Common semantic lowering failed: module '"
                + offendingModule + "', capability " + capability
                + ", validatorRule " + rule
                + ", semanticProfile DEAL_V1_4_BINARY64, irVersion deal.semantic-ir/2"
                + ", origin " + originText;
            String humanLine = offending + ":" + startLine + ":" + startColumn
                + "-" + endLine + ":" + endColumn + ": ERROR " + expectedCode + ": "
                + expectedMessage + " [span " + scalarLength + "]";

            // A previous artifact set in the live output root.
            write(out, "main.lua", "-- previous artifact\n");
            write(out, "keep.txt", "prior-publication\n");
            Map<String, byte[]> before = snapshotTree(out);

            // The dispatch arm: one E6005 with the offending construct's own
            // range, the complete message, and no produced project.
            ArmCompile arm = compileWithInvocation(project, "src/main.deal",
                productionInvocation());
            check(!arm.success(), context + ": the compile fails closed");
            List<CompilerDiagnostic> errors = arm.orchestrator().diagnostics()
                .stream().filter(d -> "error".equals(d.severity())).toList();
            checkEq(1, errors.size(), context + ": exactly one error diagnostic: "
                + arm.orchestrator().diagnostics());
            if (errors.size() == 1) {
                CompilerDiagnostic diagnostic = errors.get(0);
                checkEq(expectedCode, diagnostic.code(),
                    context + ": the failure is E6005");
                checkEq(expectedMessage, diagnostic.message(),
                    context + ": the complete pinned E6005 message");
                checkEq(offending.toString(), diagnostic.range().file(),
                    context + ": the E6005 range names the offending file");
                checkEq(startLine, diagnostic.range().startLine(),
                    context + ": the E6005 range starts at the offending line");
                checkEq(startColumn, diagnostic.range().startColumn(),
                    context + ": the E6005 range starts at the offending column");
                checkEq(endLine, diagnostic.range().endLine(),
                    context + ": the E6005 range ends on the offending line");
                checkEq(endColumn, diagnostic.range().endColumn(),
                    context + ": the E6005 range ends after the offending construct");
                checkEq(startOffset, diagnostic.range().startScalarOffset(),
                    context + ": the E6005 range starts at the offending offset");
                checkEq(endOffset, diagnostic.range().endScalarOffset(),
                    context + ": the E6005 range ends at the offending offset");
                checkEq(scalarLength, diagnostic.range().scalarLength(),
                    context + ": the E6005 range covers exactly the offending "
                        + "construct");
                check(diagnostic.range().origin() == RangeOrigin.SOURCE,
                    context + ": the E6005 range is SOURCE, got "
                        + diagnostic.range().origin());
                check(diagnostic.notes().isEmpty(),
                    context + ": the well-formed origin adds no note: "
                        + diagnostic.notes());
                checkEq(0, arm.orchestrator().semanticEmissionCount(),
                    context + ": the fail-closed lowering produces no project");
            }
            checkTreeIdentical(before, out,
                context + ": the dispatch failure preserves the previous set");

            // The release-owned CLI with structured diagnostics: the same
            // code, message, and exact range on both rendering surfaces.
            Path diagnosticsJson = project.resolve("diagnostics.json");
            Files.deleteIfExists(diagnosticsJson);
            ProjectOutcome cli = runProductionCli("compile", entry.toString(),
                "--backend", target, "--output", out.toAbsolutePath().toString(),
                "--diagnostics-json", diagnosticsJson.toAbsolutePath().toString());
            check(cli.exitCode() != 0,
                context + ": the release-owned CLI fails closed");
            check(cli.stderr().contains(humanLine),
                context + ": the human line is the pinned complete diagnostic: "
                    + cli.stderr());
            String structured = Files.readString(diagnosticsJson);
            checkStructuredOrigin(context, structured, expectedCode, expectedMessage, offending,
                startLine, startColumn, endLine, endColumn, startOffset,
                endOffset, scalarLength);
            checkEq(DiagnosticStructuredOutput.toJson(errors), structured,
                context + ": the structured document is exactly the error's "
                    + "structured diagnostic");
            checkTreeIdentical(before, out,
                context + ": the CLI failure preserves the previous set");

            // Repeated compilation of the unchanged project is
            // byte-identical on both surfaces.
            Files.deleteIfExists(diagnosticsJson);
            ProjectOutcome repeat = runProductionCli("compile", entry.toString(),
                "--backend", target, "--output", out.toAbsolutePath().toString(),
                "--diagnostics-json", diagnosticsJson.toAbsolutePath().toString());
            checkEq(cli.stderr(), repeat.stderr(),
                context + ": repeated CLI reports are byte-identical");
            checkEq(structured, Files.readString(diagnosticsJson),
                context + ": repeated structured reports are byte-identical");
            checkTreeIdentical(before, out,
                context + ": the repeated failure preserves the previous set");
        }
    }

    /**
     * The structured-document pins: version 1, one error diagnostic with
     * the complete message and the exact SOURCE range, and no notes.
     */
    private static void checkStructuredOrigin(String context, String structured,
            String expectedCode, String expectedMessage, Path offending, int startLine, int startColumn,
            int endLine, int endColumn, int startOffset, int endOffset,
            int scalarLength) {
        CanonicalJson.Value root = CanonicalJson.parse(structured);
        CanonicalJson.Obj rootObj = (CanonicalJson.Obj) root;
        checkEq(1, jsonInt(jsonField(rootObj, "version")),
            context + ": the structured document version");
        CanonicalJson.Arr diagnostics =
            (CanonicalJson.Arr) jsonField(rootObj, "diagnostics");
        checkEq(1, diagnostics.items().size(),
            context + ": exactly one structured diagnostic");
        if (diagnostics.items().size() != 1) {
            return;
        }
        CanonicalJson.Obj diagnostic = (CanonicalJson.Obj) diagnostics.items().get(0);
        checkEq(expectedCode, jsonString(jsonField(diagnostic, "code")),
            context + ": the structured code");
        checkEq("error", jsonString(jsonField(diagnostic, "severity")),
            context + ": the structured severity");
        checkEq(expectedMessage, jsonString(jsonField(diagnostic, "message")),
            context + ": the structured complete message");
        CanonicalJson.Obj range = (CanonicalJson.Obj) jsonField(diagnostic, "range");
        checkEq(offending.toString(), jsonString(jsonField(range, "file")),
            context + ": the structured range file");
        checkEq(startLine, jsonInt(jsonField(range, "startLine")),
            context + ": the structured range start line");
        checkEq(startColumn, jsonInt(jsonField(range, "startColumn")),
            context + ": the structured range start column");
        checkEq(endLine, jsonInt(jsonField(range, "endLine")),
            context + ": the structured range end line");
        checkEq(endColumn, jsonInt(jsonField(range, "endColumn")),
            context + ": the structured range end column");
        checkEq(startOffset, jsonInt(jsonField(range, "startScalarOffset")),
            context + ": the structured range start offset");
        checkEq(endOffset, jsonInt(jsonField(range, "endScalarOffset")),
            context + ": the structured range end offset");
        checkEq(scalarLength, jsonInt(jsonField(range, "scalarLength")),
            context + ": the structured range scalar length");
        checkEq("SOURCE", jsonString(jsonField(range, "origin")),
            context + ": the structured range origin");
        CanonicalJson.Arr notes = (CanonicalJson.Arr) jsonField(diagnostic, "notes");
        checkEq(0, notes.items().size(),
            context + ": the structured diagnostic carries no note");
    }

    private static CanonicalJson.Value jsonField(CanonicalJson.Obj obj, String key) {
        for (CanonicalJson.Entry entry : obj.entries()) {
            if (entry.key().equals(key)) {
                return entry.value();
            }
        }
        fail("the structured document misses field \"" + key + "\"");
        return CanonicalJson.nullValue();
    }

    private static String jsonString(CanonicalJson.Value value) {
        return value instanceof CanonicalJson.Str str ? str.value() : "<not a string>";
    }

    private static int jsonInt(CanonicalJson.Value value) {
        return value instanceof CanonicalJson.Int i ? i.value() : Integer.MIN_VALUE;
    }

    private static void checkLaterSliceConstruct(String name,
            Map<String, String> sources, String expectedRule) throws Exception {
        Path project = Files.createTempDirectory("production-dispatch-slice-");
        try {
            write(project, "deal.json", DEAL_JSON_LUA);
            for (Map.Entry<String, String> source : sources.entrySet()) {
                write(project, source.getKey(), source.getValue());
            }
            ProjectOutcome compile = productionCompile(project, "src/main.deal",
                "out");
            check(compile.exitCode() != 0,
                name + ": the later-slice construct fails closed");
            check(compile.stderr().contains("E3001")
                    && compile.stderr().contains(expectedRule),
                name + ": the failure names " + expectedRule + ": "
                    + compile.stderr());
            check(!Files.exists(project.resolve("out")),
                name + ": the failure stages no artifact");
        } finally {
            deleteRecursively(project);
        }
    }

    /**
     * The container coverage through the release-owned production invocation
     * (the fixture this battery pinned fail-closed): the closure emits
     * exactly one project artifact and the artifact executes under the real
     * {@code luajit} toolchain.
     */
    private static void checkArrayProductionCoverage() throws Exception {
        Path project = Files.createTempDirectory("production-dispatch-array-");
        try {
            write(project, "deal.json", DEAL_JSON_LUA);
            write(project, "src/main.deal", ARRAY_SOURCE);
            ProjectOutcome compile = productionCompile(project, "src/main.deal",
                "out");
            check(compile.exitCode() == 0,
                "the array closure emits one project artifact: "
                    + compile.stderr());
            if (compile.exitCode() == 0) {
                ProcessOutcome run = runProcess(project.resolve("out"),
                    "luajit", "main.lua");
                check(run.exitCode() == 0,
                    "the array production artifact runs: exit=" + run.exitCode()
                        + " output=" + run.output());
            }
        } finally {
            deleteRecursively(project);
        }
    }

    /**
     * The K7 {@code time.nowMillis} coverage through the release-owned
     * production invocation: the closure emits exactly one project
     * artifact per target and both artifacts execute under their real
     * toolchains with the declared {@code number} return crossing
     * unchanged — the direct call, the stored/adapted function value, and
     * the cross-module import all carry contemporary epoch-millisecond
     * readings above the signed-32 range (v1.4 — no int terminal).
     */
    private static void checkTimeNowMillisCoverage() throws Exception {
        Path project = Files.createTempDirectory("production-dispatch-time-");
        try {
            write(project, "deal.json", DEAL_JSON_LUA);
            write(project, "src/main.deal", TIME_SOURCE);
            write(project, "src/lib.deal", TIME_LIB_SOURCE);
            ProjectOutcome luaCompile = productionCompile(project, "src/main.deal",
                "out");
            check(luaCompile.exitCode() == 0,
                "the time.nowMillis closure emits one project artifact: "
                    + luaCompile.stderr());
            if (luaCompile.exitCode() == 0) {
                ProcessOutcome run = runProcess(project.resolve("out"),
                    "luajit", "main.lua");
                check(run.exitCode() == 0 && run.output().isEmpty(),
                    "the LuaJIT artifact crosses the number return unchanged: exit="
                        + run.exitCode() + " output=" + run.output());
            }

            Path jvmOut = project.resolve("out-jvm");
            ProjectOutcome jvmCompile = runProductionCli("compile",
                project.resolve("src/main.deal").toAbsolutePath().toString(),
                "--backend", "jvm", "--output",
                jvmOut.toAbsolutePath().toString());
            check(jvmCompile.exitCode() == 0,
                "the time.nowMillis JVM closure emits one project artifact: "
                    + jvmCompile.stderr());
            if (jvmCompile.exitCode() != 0) {
                return;
            }
            String buildCp = absoluteClasspath();
            ProcessOutcome javac = compileJvm(project, "javac", "--release", "25",
                "-proc:none", "-cp", buildCp, "-d", jvmOut.toString(),
                jvmOut.resolve("Main.java").toString());
            check(javac.exitCode() == 0,
                "the JVM time.nowMillis artifact compiles: " + javac.output());
            if (javac.exitCode() != 0) {
                return;
            }
            ProcessOutcome javaRun = runProcess(jvmOut, "java", "-cp",
                jvmOut + File.pathSeparator + buildCp, "Main");
            check(javaRun.exitCode() == 0 && javaRun.output().isEmpty(),
                "the JVM artifact crosses the number return unchanged: exit="
                    + javaRun.exitCode() + " output=" + javaRun.output());
        } finally {
            deleteRecursively(project);
        }
    }

    private static void checkBuiltinErrorConstruction() throws Exception {
        Path project = Files.createTempDirectory("production-dispatch-error-");
        try {
            write(project, "deal.json", DEAL_JSON_LUA);
            write(project, "src/main.deal", ERROR_SOURCE);
            ProjectOutcome luaCompile = productionCompile(project, "src/main.deal",
                "out");
            check(luaCompile.exitCode() == 0,
                "the builtin Error closure emits one project artifact: "
                    + luaCompile.stderr());
            if (luaCompile.exitCode() == 0) {
                String lua = Files.readString(project.resolve("out/main.lua"));
                check(lua.contains("__instT = {[__dealCarrierKeys.d] = true, code = ")
                        && lua.contains(", m = ")
                        && lua.contains("\"E1\"")
                        && lua.contains("\"m\""),
                    "the LuaJIT artifact carries the canonical err carrier built "
                        + "from the provided fields: " + lua);
                ProcessOutcome run = runProcess(project.resolve("out"),
                    "luajit", "main.lua");
                check(run.exitCode() == 0 && run.output().isEmpty(),
                    "the LuaJIT builtin Error artifact runs clean: exit="
                        + run.exitCode() + " output=" + run.output());
            }

            Path jvmOut = project.resolve("out-jvm");
            ProjectOutcome jvmCompile = runProductionCli("compile",
                project.resolve("src/main.deal").toAbsolutePath().toString(),
                "--backend", "jvm", "--output",
                jvmOut.toAbsolutePath().toString());
            check(jvmCompile.exitCode() == 0,
                "the builtin Error JVM closure emits one project artifact: "
                    + jvmCompile.stderr());
            if (jvmCompile.exitCode() != 0) {
                return;
            }
            String java = Files.readString(jvmOut.resolve("Main.java"));
            check(java.contains("new JvmRuntime.ErrorValue("),
                "the JVM artifact publishes the canonical ErrorValue carrier: "
                    + java);
            String buildCp = absoluteClasspath();
            ProcessOutcome javac = compileJvm(project, "javac", "--release", "25",
                "-proc:none", "-cp", buildCp, "-d", jvmOut.toString(),
                jvmOut.resolve("Main.java").toString());
            check(javac.exitCode() == 0,
                "the JVM builtin Error artifact compiles: " + javac.output());
            if (javac.exitCode() != 0) {
                return;
            }
            ProcessOutcome javaRun = runProcess(jvmOut, "java", "-cp",
                jvmOut + File.pathSeparator + buildCp, "Main");
            check(javaRun.exitCode() == 0 && javaRun.output().isEmpty(),
                "the JVM builtin Error artifact runs clean: exit="
                    + javaRun.exitCode() + " output=" + javaRun.output());
        } finally {
            deleteRecursively(project);
        }
    }

    // =========================================================================
    // 5. The source-map emission through the public CLI
    // =========================================================================

    private static void testSourceMapDisposition() throws Exception {
        System.out.println("-- source map: an explicit --source-map publishes the "
            + "artifact with source comments and one .deal.map.json per module "
            + "with real coordinates; a failing recompile preserves the "
            + "published artifact and maps byte-for-byte; --dump-ir emits the "
            + "same set without a warning --");

        Path project = Files.createTempDirectory("production-dispatch-map-");
        try {
            write(project, "deal.json", DEAL_JSON_JVM);
            write(project, "src/lib.deal", MAP_LIB_SOURCE);
            write(project, "src/main.deal", MAP_APP_SOURCE);

            // (1) The explicit --source-map: one sidecar per constituent
            // module mapping the one shared artifact, real generated/source
            // coordinates, no warning, and the commented artifact still
            // compiles and runs.
            Path jvmOut = project.resolve("build/out");
            ProjectOutcome explicit = runProductionCli("compile",
                project.resolve("src/main.deal").toAbsolutePath().toString(),
                "--backend", "jvm", "--source-map", "--output",
                jvmOut.toAbsolutePath().toString());
            check(explicit.exitCode() == 0,
                "the explicit --source-map production compile succeeds: "
                    + explicit.stderr());
            check(!explicit.stderr().contains("Warning:"),
                "the explicit --source-map compile prints no warning: "
                    + explicit.stderr());
            List<String> published = artifactFiles(jvmOut);
            check(published.contains("Main.java"),
                "the explicit --source-map compile publishes its project "
                    + "artifact: " + published);
            check(published.contains("main.deal.map.json")
                    && published.contains("lib.deal.map.json"),
                "the explicit --source-map compile publishes one sidecar per "
                    + "constituent module: " + published);
            verifyPublishedSidecar(project, jvmOut, "main.deal.map.json",
                "src/main.deal", "build/out/Main.java", "Main.java", "// ");
            verifyPublishedSidecar(project, jvmOut, "lib.deal.map.json",
                "src/lib.deal", "build/out/Main.java", "Main.java", "// ");
            verifyAsyncEntryComments(project, jvmOut, "Main.java", "// ",
                "public static Object ae", List.of("src/lib.deal",
                    "src/main.deal"));

            String classpath = absoluteClasspath();
            ProcessOutcome javac = compileJvm(project, "javac", "--release", "25",
                "-proc:none", "-cp", classpath, "-d", jvmOut.toString(),
                jvmOut.resolve("Main.java").toString());
            check(javac.exitCode() == 0,
                "the source-commented JVM artifact compiles: " + javac.output());
            if (javac.exitCode() == 0) {
                ProcessOutcome run = runProcess(jvmOut, "java", "-cp",
                    jvmOut + File.pathSeparator + classpath, "Main");
                check(run.exitCode() == 0
                        && countOccurrences(run.output(), "PROBE|MAIN-ONCE") == 1,
                    "the source-commented JVM artifact runs the entry once: exit="
                        + run.exitCode() + " output=" + run.output());
            }

            // (2) Rollback: a failing recompile into the same output root
            // leaves the published artifact and both sidecars byte-identical.
            Map<String, byte[]> publishedSet = snapshotTree(jvmOut);
            write(project, "src/lib.deal", "export function value(:\n");
            ProjectOutcome failing = runProductionCli("compile",
                project.resolve("src/main.deal").toAbsolutePath().toString(),
                "--backend", "jvm", "--source-map", "--output",
                jvmOut.toAbsolutePath().toString());
            check(failing.exitCode() != 0,
                "the failing --source-map recompile fails: " + failing.stderr());
            checkTreeIdentical(publishedSet, jvmOut,
                "the failing --source-map recompile preserves the published "
                    + "artifact and both sidecars");
            write(project, "src/lib.deal", MAP_LIB_SOURCE);

            // (3) --dump-ir emits the default sidecar set together with the
            // requested dumps, without a warning.
            Path dumpOut = project.resolve("build/dump");
            ProjectOutcome dumpIr = runProductionCli("compile",
                project.resolve("src/main.deal").toAbsolutePath().toString(),
                "--backend", "jvm", "--dump-ir", "--output",
                dumpOut.toAbsolutePath().toString());
            check(dumpIr.exitCode() == 0,
                "the --dump-ir production compile succeeds: " + dumpIr.stderr());
            check(!dumpIr.stderr().contains("Warning:"),
                "a --dump-ir compile prints no warning: " + dumpIr.stderr());
            List<String> dumped = artifactFiles(dumpOut);
            check(dumped.contains("main.deal.map.json")
                    && dumped.contains("lib.deal.map.json"),
                "the --dump-ir compile emits the default sidecar set: "
                    + dumped);

            // (4) The LuaJIT target: the chunk carries the source comments and
            // the two sidecars map the same shared artifact; the commented
            // chunk still runs under luajit.
            Path luaOut = project.resolve("build/lua");
            ProjectOutcome lua = runProductionCli("compile",
                project.resolve("src/main.deal").toAbsolutePath().toString(),
                "--backend", "luajit", "--source-map", "--output",
                luaOut.toAbsolutePath().toString());
            check(lua.exitCode() == 0,
                "the LuaJIT --source-map compile succeeds: " + lua.stderr());
            check(!lua.stderr().contains("Warning:"),
                "the LuaJIT --source-map compile prints no warning: "
                    + lua.stderr());
            List<String> luaPublished = artifactFiles(luaOut);
            check(luaPublished.contains("main.lua")
                    && luaPublished.contains("main.deal.map.json")
                    && luaPublished.contains("lib.deal.map.json"),
                "the LuaJIT --source-map compile publishes the chunk and both "
                    + "sidecars: " + luaPublished);
            verifyPublishedSidecar(project, luaOut, "main.deal.map.json",
                "src/main.deal", "build/lua/main.lua", "main.lua", "-- ");
            verifyPublishedSidecar(project, luaOut, "lib.deal.map.json",
                "src/lib.deal", "build/lua/main.lua", "main.lua", "-- ");
            verifyAsyncEntryComments(project, luaOut, "main.lua", "-- ",
                "__asyncEntries[", List.of("src/lib.deal", "src/main.deal"));
            ProcessOutcome luaRun = runProcess(luaOut, "luajit", "main.lua");
            check(luaRun.exitCode() == 0
                    && countOccurrences(luaRun.output(), "PROBE|MAIN-ONCE") == 1,
                "the source-commented LuaJIT chunk runs the entry once: exit="
                    + luaRun.exitCode() + " output=" + luaRun.output());
        } finally {
            deleteRecursively(project);
        }
    }

    /**
     * Verifies one published {@code .deal.map.json} sidecar: the pinned
     * fields, at least one mapping, and every mapping against the real
     * artifact line (the mapped generated line is exactly the source comment
     * carrying the mapped source coordinates under the sidecar's source name)
     * and the real source file line range.
     */
    private static void verifyPublishedSidecar(Path project, Path outputRoot,
            String sidecarName, String expectedSource, String expectedGenerated,
            String artifactName, String commentMarker) throws Exception {
        Path sidecar = outputRoot.resolve(sidecarName);
        check(Files.isRegularFile(sidecar),
            "the sidecar is published: " + sidecarName);
        if (!Files.isRegularFile(sidecar)) {
            return;
        }
        String json = Files.readString(sidecar);
        check(json.contains("\"version\": 1"),
            sidecarName + " carries the pinned version: " + json);
        check(json.contains("\"source\": \"" + expectedSource + "\""),
            sidecarName + " names its constituent source file: " + json);
        check(json.contains("\"generated\": \"" + expectedGenerated + "\""),
            sidecarName + " maps the one shared artifact: " + json);

        List<int[]> mappings = parseSidecarMappings(json);
        check(!mappings.isEmpty(),
            sidecarName + " carries real mappings: " + json);
        List<String> artifactLines = Files.readString(
            outputRoot.resolve(artifactName)).lines().toList();
        List<String> sourceLines = Files.readString(
            project.resolve(expectedSource)).lines().toList();
        for (int[] mapping : mappings) {
            int generatedLine = mapping[0];
            int sourceLine = mapping[2];
            int sourceColumn = mapping[3];
            check(generatedLine >= 1 && generatedLine <= artifactLines.size(),
                sidecarName + " maps a real generated line: " + generatedLine);
            check(sourceLine >= 1 && sourceLine <= sourceLines.size(),
                sidecarName + " maps a real source line: " + sourceLine);
            if (generatedLine >= 1 && generatedLine <= artifactLines.size()) {
                String artifactLine = artifactLines.get(generatedLine - 1);
                checkEq(commentMarker + expectedSource + ":" + sourceLine + ":"
                        + sourceColumn,
                    artifactLine.strip(),
                    sidecarName + " maps the source comment at its generated "
                        + "line");
                checkEq(artifactLine.indexOf(commentMarker) + 1, mapping[1],
                    sidecarName + " maps the comment start column");
            }
        }
    }

    /** The mapping tuples of one sidecar JSON, in recorded order. */
    private static List<int[]> parseSidecarMappings(String json) {
        List<int[]> mappings = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
            "\\{ \"generatedLine\": ([0-9]+), \"generatedColumn\": ([0-9]+), "
                + "\"sourceLine\": ([0-9]+), \"sourceColumn\": ([0-9]+) \\}")
            .matcher(json);
        while (matcher.find()) {
            mappings.add(new int[] {
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                Integer.parseInt(matcher.group(4)),
            });
        }
        return mappings;
    }

    /**
     * Verifies the async-entry definitions of one produced artifact: every
     * {@code definitionPrefix} line is preceded by the entry op's own source
     * comment, that comment names an expected owning module, and the comment
     * line is exactly one mapping of the owning module's sidecar (the entry
     * boundary the JVM arm already recorded). The comment's source
     * coordinates must land on a real line of the module's source file.
     */
    private static void verifyAsyncEntryComments(Path project, Path outputRoot,
            String artifactName, String commentMarker, String definitionPrefix,
            List<String> expectedOwners) throws Exception {
        List<String> lines = Files.readString(outputRoot.resolve(artifactName))
            .lines().toList();
        List<Integer> entryLines = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).strip().startsWith(definitionPrefix)) {
                entryLines.add(i + 1);
            }
        }
        checkEq(expectedOwners.size(), entryLines.size(), artifactName
            + " carries one async-entry definition per constituent module: "
            + entryLines);
        List<String> owners = new ArrayList<>();
        for (int entryLine : entryLines) {
            check(entryLine >= 2, artifactName + " async entry at line "
                + entryLine + " has a preceding line");
            if (entryLine < 2) {
                continue;
            }
            String commentLine = lines.get(entryLine - 2);
            String comment = commentLine.strip();
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(commentMarker)
                    + "(.+):([0-9]+):([0-9]+)$").matcher(comment);
            check(matcher.matches(), artifactName + " async entry at line "
                + entryLine + " is preceded by its source comment: "
                + comment);
            if (!matcher.matches()) {
                continue;
            }
            String owner = matcher.group(1);
            owners.add(owner);
            int sourceLine = Integer.parseInt(matcher.group(2));
            int sourceColumn = Integer.parseInt(matcher.group(3));
            String[] sourceLines = Files.readString(project.resolve(owner))
                .lines().toArray(String[]::new);
            check(sourceLine >= 1 && sourceLine <= sourceLines.length
                    && !sourceLines[sourceLine - 1].isBlank(),
                artifactName + " maps a real source line of " + owner
                    + " (line " + sourceLine + ")");
            String sidecarName = owner.substring(owner.lastIndexOf('/') + 1)
                + ".map.json";
            List<int[]> mappings = parseSidecarMappings(
                Files.readString(outputRoot.resolve(sidecarName)));
            int commentColumn = commentLine.indexOf(commentMarker) + 1;
            boolean mapped = false;
            for (int[] mapping : mappings) {
                mapped = mapping[0] == entryLine - 1
                    && mapping[1] == commentColumn
                    && mapping[2] == sourceLine
                    && mapping[3] == sourceColumn;
                if (mapped) {
                    break;
                }
            }
            check(mapped, artifactName + " async entry at line " + entryLine
                + " is mapped by " + sidecarName + " at its comment line");
        }
        check(owners.containsAll(expectedOwners)
                && owners.size() == expectedOwners.size(),
            artifactName + " async entries cover every constituent module: "
                + owners);
    }

    /**
     * The unsafe-path-text regression of the source-map comments: a module
     * root carrying an embedded line terminator and one carrying a literal
     * backslash-u000a sequence (the Java Unicode-escape hazard) must still
     * yield artifacts that compile and run on both targets under
     * {@code --source-map}. The comment carries the encoded single-line
     * spelling of the source path; the sidecar keeps the actual path.
     */
    private static void testSourceMapUnsafeSourcePathText() throws Exception {
        System.out.println("-- source map: a module root carrying a line "
            + "terminator or a literal backslash-u000a sequence still yields "
            + "source-commented artifacts that compile and run on both "
            + "targets --");

        // (1) The embedded line terminator in the module root name.
        Path newlineProject = Files.createTempDirectory(
            "production-dispatch-map-nl-");
        try {
            Path newlineRoot = Path.of("src\nline");
            write(newlineProject, "deal.json", DEAL_JSON_NEWLINE_ROOT);
            write(newlineProject, newlineRoot.resolve("main.deal").toString(),
                TRIVIAL_MAIN_SOURCE);
            verifyUnsafePathArtifacts(newlineProject, newlineRoot,
                "src\\nline/main.deal");
        } finally {
            deleteRecursively(newlineProject);
        }

        // (2) The literal backslash-u000a sequence in the module root name.
        Path unicodeProject = Files.createTempDirectory(
            "production-dispatch-map-uni-");
        try {
            Path unicodeRoot = Path.of("src\\u000aline");
            write(unicodeProject, "deal.json", DEAL_JSON_UNICODE_ROOT);
            write(unicodeProject, unicodeRoot.resolve("main.deal").toString(),
                TRIVIAL_MAIN_SOURCE);
            verifyUnsafePathArtifacts(unicodeProject, unicodeRoot,
                "src\\\\u000aline/main.deal");
        } finally {
            deleteRecursively(unicodeProject);
        }
    }

    /**
     * Compiles one unsafe-path project through the public CLI on both targets
     * with {@code --source-map} and verifies the emitted artifacts: the source
     * comment carries the encoded single-line spelling of the module's source
     * path, the sidecar keeps the actual path, and the commented artifact
     * compiles and runs on its target.
     */
    private static void verifyUnsafePathArtifacts(Path project, Path moduleRoot,
            String expectedCommentPath) throws Exception {
        String entry = project.resolve(moduleRoot).resolve("main.deal")
            .toAbsolutePath().toString();
        String actualSourcePath = moduleRoot.toString()
            .replace(File.separatorChar, '/') + "/main.deal";

        Path jvmOut = project.resolve("build/jvm");
        ProjectOutcome jvmCompile = runProductionCli("compile", entry,
            "--backend", "jvm", "--source-map", "--output",
            jvmOut.toAbsolutePath().toString());
        check(jvmCompile.exitCode() == 0,
            "the JVM unsafe-path compile succeeds: " + jvmCompile.stderr());
        if (jvmCompile.exitCode() == 0) {
            String java = Files.readString(jvmOut.resolve("Main.java"));
            check(java.lines().anyMatch(line -> line.contains(
                    "// " + expectedCommentPath + ":1:1")),
                "the JVM comment encodes the unsafe source path on one line: "
                    + expectedCommentPath);
            checkEq(actualSourcePath, jsonStringField(Files.readString(
                    jvmOut.resolve("main.deal.map.json")), "source"),
                "the JVM sidecar keeps the actual source path");
            String classpath = absoluteClasspath();
            ProcessOutcome javac = compileJvm(project, "javac", "--release",
                "25", "-proc:none", "-cp", classpath, "-d",
                jvmOut.toString(), jvmOut.resolve("Main.java").toString());
            check(javac.exitCode() == 0,
                "the JVM unsafe-path artifact compiles: " + javac.output());
            if (javac.exitCode() == 0) {
                ProcessOutcome run = runProcess(jvmOut, "java", "-cp",
                    jvmOut + File.pathSeparator + classpath, "Main");
                checkEq(0, run.exitCode(),
                    "the JVM unsafe-path artifact runs: " + run.output());
            }
        }

        Path luaOut = project.resolve("build/lua");
        ProjectOutcome luaCompile = runProductionCli("compile", entry,
            "--backend", "luajit", "--source-map", "--output",
            luaOut.toAbsolutePath().toString());
        check(luaCompile.exitCode() == 0,
            "the LuaJIT unsafe-path compile succeeds: " + luaCompile.stderr());
        if (luaCompile.exitCode() == 0) {
            String lua = Files.readString(luaOut.resolve("main.lua"));
            check(lua.lines().anyMatch(line -> line.contains(
                    "-- " + expectedCommentPath + ":1:1")),
                "the LuaJIT comment encodes the unsafe source path on one "
                    + "line: " + expectedCommentPath);
            checkEq(actualSourcePath, jsonStringField(Files.readString(
                    luaOut.resolve("main.deal.map.json")), "source"),
                "the LuaJIT sidecar keeps the actual source path");
            ProcessOutcome run = runProcess(luaOut, "luajit", "main.lua");
            checkEq(0, run.exitCode(),
                "the LuaJIT unsafe-path artifact runs: " + run.output());
        }
    }

    /**
     * The unescaped value of one top-level string field of a sidecar JSON
     * document, or {@code null} when the field is absent.
     */
    private static String jsonStringField(String json, String field) {
        int key = json.indexOf("\"" + field + "\": \"");
        if (key < 0) {
            return null;
        }
        StringBuilder value = new StringBuilder();
        for (int i = key + field.length() + 5; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                return value.toString();
            }
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                switch (next) {
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    default -> value.append(next);
                }
            } else {
                value.append(c);
            }
        }
        return null;
    }

    /**
     * The control-character path regression of the source-map sidecars: an
     * accepted module root and an accepted output directory carrying C0
     * controls other than the LF/CR/TAB the serializer always escaped must
     * still yield sidecars that strict JSON consumers parse, with the decoded
     * source and generated paths exactly the actual ones, on both targets,
     * and the commented artifacts must still compile and run.
     */
    private static void testSourceMapControlCharacterPaths() throws Exception {
        System.out.println("-- source map: accepted C0 controls in the source "
            + "and generated paths still yield strict-JSON sidecars with the "
            + "actual decoded paths, and runnable artifacts on both "
            + "targets --");

        verifySerializerEscapesEveryControl();

        Path project = Files.createTempDirectory(
            "production-dispatch-map-ctl-");
        try {
            Path moduleRoot = Path.of(CONTROL_ROOT_NAME);
            write(project, "deal.json", DEAL_JSON_CONTROL_ROOT);
            write(project, moduleRoot.resolve("main.deal").toString(),
                TRIVIAL_MAIN_SOURCE);
            String entry = project.resolve(moduleRoot).resolve("main.deal")
                .toAbsolutePath().toString();
            String actualSource = moduleRoot.toString()
                .replace(File.separatorChar, '/') + "/main.deal";

            // (1) The JVM target: the sidecar is strict JSON with the actual
            // paths, and the commented class compiles and runs.
            Path jvmOut = project.resolve(
                Path.of("build", CONTROL_OUTPUT_NAME));
            ProjectOutcome jvmCompile = runProductionCli("compile", entry,
                "--backend", "jvm", "--source-map", "--output",
                jvmOut.toAbsolutePath().toString());
            check(jvmCompile.exitCode() == 0,
                "the JVM control-path compile succeeds: "
                    + jvmCompile.stderr());
            if (jvmCompile.exitCode() == 0) {
                verifyStrictSidecarPaths(jvmOut, "main.deal.map.json",
                    actualSource, Path.of("build", CONTROL_OUTPUT_NAME)
                        .toString().replace(File.separatorChar, '/')
                        + "/Main.java");
                String classpath = absoluteClasspath();
                ProcessOutcome javac = compileJvm(project, "javac", "--release",
                    "25", "-proc:none", "-cp", classpath, "-d",
                    jvmOut.toString(), jvmOut.resolve("Main.java").toString());
                check(javac.exitCode() == 0,
                    "the JVM control-path artifact compiles: " + javac.output());
                if (javac.exitCode() == 0) {
                    ProcessOutcome run = runProcess(jvmOut, "java", "-cp",
                        jvmOut + File.pathSeparator + classpath, "Main");
                    checkEq(0, run.exitCode(),
                        "the JVM control-path artifact runs: " + run.output());
                }
            }

            // (2) The LuaJIT target: the same strict sidecar and a chunk that
            // runs under the real interpreter.
            Path luaOut = project.resolve(
                Path.of("build", CONTROL_OUTPUT_NAME + "-lua"));
            ProjectOutcome luaCompile = runProductionCli("compile", entry,
                "--backend", "luajit", "--source-map", "--output",
                luaOut.toAbsolutePath().toString());
            check(luaCompile.exitCode() == 0,
                "the LuaJIT control-path compile succeeds: "
                    + luaCompile.stderr());
            if (luaCompile.exitCode() == 0) {
                verifyStrictSidecarPaths(luaOut, "main.deal.map.json",
                    actualSource, Path.of("build",
                        CONTROL_OUTPUT_NAME + "-lua")
                        .toString().replace(File.separatorChar, '/')
                        + "/main.lua");
                ProcessOutcome run = runProcess(luaOut, "luajit", "main.lua");
                checkEq(0, run.exitCode(),
                    "the LuaJIT control-path artifact runs: " + run.output());
            }
        } finally {
            deleteRecursively(project);
        }
    }

    /**
     * The direct-emission-group regression of the source maps: the parameter
     * transfer of a parameterized export and the owner-side class-factory
     * group are emitted directly (never through {@code emitOp}), so each must
     * open its own mapped group — the parameter's own IR span, the owning
     * module's class declaration span, and the caller's span again when the
     * caller's statements resume — on both production targets, with the
     * artifacts still runnable.
     */
    private static void testSourceMapDirectEmissionGroups() throws Exception {
        System.out.println("-- source map: the directly emitted parameter "
            + "transfer and the owner-side class-factory group carry their "
            + "own source comments and sidecar mappings on both targets --");

        Path project = Files.createTempDirectory(
            "production-dispatch-map-direct-");
        try {
            write(project, "deal.json", DEAL_JSON_JVM);
            write(project, "src/lib.deal", DIRECT_EMISSION_LIB_SOURCE);
            write(project, "src/main.deal", DIRECT_EMISSION_APP_SOURCE);
            String entry = project.resolve("src/main.deal").toAbsolutePath()
                .toString();

            // (1) The JVM target: the directly emitted groups carry their
            // comments and mappings, and the commented class compiles and
            // runs the entry.
            Path jvmOut = project.resolve("build/jvm");
            ProjectOutcome jvmCompile = runProductionCli("compile", entry,
                "--backend", "jvm", "--source-map", "--output",
                jvmOut.toAbsolutePath().toString());
            check(jvmCompile.exitCode() == 0,
                "the direct-emission JVM compile succeeds: "
                    + jvmCompile.stderr());
            if (jvmCompile.exitCode() == 0) {
                MappedArtifact artifact = prepareMappedArtifact(project, jvmOut,
                    "Main.java");
                verifyDirectEmissionGroups(artifact, "Main.java",
                    "// ", "b\\d+g0 = args\\[0\\];",
                    "String __prevMod_\\d+ = MODULE;", "MODULE = __prevMod_\\d+;");
                verifyOmittedConstructionProviderAttribution(artifact,
                    "Main.java", "// ", "String __prevMod_\\d+ = MODULE;",
                    "JvmRuntime\\.setModule\\(MODULE\\);",
                    "\\w+ = default\\d+\\(\\);",
                    "\\w+ __tf_\\d+_\\d+ = new \\w+\\(\\);", "throw __e;");
                String classpath = absoluteClasspath();
                ProcessOutcome javac = compileJvm(project, "javac", "--release",
                    "25", "-proc:none", "-cp", classpath, "-d",
                    jvmOut.toString(), jvmOut.resolve("Main.java").toString());
                check(javac.exitCode() == 0,
                    "the direct-emission JVM artifact compiles: "
                        + javac.output());
                if (javac.exitCode() == 0) {
                    ProcessOutcome run = runProcess(jvmOut, "java", "-cp",
                        jvmOut + File.pathSeparator + classpath, "Main");
                    check(run.exitCode() == 0
                            && countOccurrences(run.output(),
                                "PROBE|DIRECT-MAIN") == 1,
                        "the direct-emission JVM artifact runs the entry once: "
                            + "exit=" + run.exitCode() + " output="
                            + run.output());
                    check(run.exitCode() == 0
                            && countOccurrences(run.output(),
                                "PROBE|OMITTED-DEFAULTS") == 1,
                        "the omitted-default JVM artifact evaluates both "
                            + "defaults and runs the entry once: exit="
                            + run.exitCode() + " output=" + run.output());
                }
            }

            // (2) The LuaJIT target: the same groups carry the same
            // comments and mappings, and the chunk runs under the real
            // interpreter.
            Path luaOut = project.resolve("build/lua");
            ProjectOutcome luaCompile = runProductionCli("compile", entry,
                "--backend", "luajit", "--source-map", "--output",
                luaOut.toAbsolutePath().toString());
            check(luaCompile.exitCode() == 0,
                "the direct-emission LuaJIT compile succeeds: "
                    + luaCompile.stderr());
            if (luaCompile.exitCode() == 0) {
                MappedArtifact artifact = prepareMappedArtifact(project, luaOut,
                    "main.lua");
                verifyDirectEmissionGroups(artifact, "main.lua", "-- ",
                    "S\\.b\\d+g0 = __args\\[1\\]",
                    "__modStack\\[#__modStack \\+ 1\\] = __module",
                    "__modStack\\[#__modStack\\] = nil");
                verifyOmittedConstructionProviderAttribution(artifact,
                    "main.lua", "-- ",
                    "__modStack\\[#__modStack \\+ 1\\] = __module",
                    "__modStack\\[#__modStack\\] = nil",
                    "__okT, __resT = pcall\\(__factories\\.D\\d+\\)",
                    "__instT = \\{\\}",
                    "__ev\\(\"main#\\d+\", \"FAILURE\", \"CLASS_NEW\".*");
                ProcessOutcome run = runProcess(luaOut, "luajit", "main.lua");
                check(run.exitCode() == 0
                        && countOccurrences(run.output(),
                            "PROBE|DIRECT-MAIN") == 1,
                    "the direct-emission LuaJIT chunk runs the entry once: "
                        + "exit=" + run.exitCode() + " output=" + run.output());
                check(run.exitCode() == 0
                        && countOccurrences(run.output(),
                            "PROBE|OMITTED-DEFAULTS") == 1,
                    "the omitted-default LuaJIT chunk evaluates both "
                        + "defaults and runs the entry once: exit="
                        + run.exitCode() + " output=" + run.output());
            }
        } finally {
            deleteRecursively(project);
        }
    }

    /**
     * Verifies the directly emitted statement groups of one produced
     * artifact: the parameter transfer of the parameterized export is
     * immediately preceded by the parameter's own source comment and mapped
     * at exactly that generated position by the entry module's sidecar; the
     * owner-side factory group is immediately preceded by the owning module's
     * class declaration comment and mapped there by the owning module's
     * sidecar; and the first source comment after the group's module restore
     * names the caller's own construction span and is mapped by the entry
     * module's sidecar.
     */
    private record MappedArtifact(List<String> lines, List<String> appLines,
            List<String> libLines, Map<String, List<int[]>> mappings) {
    }

    private static MappedArtifact prepareMappedArtifact(Path project,
            Path outputRoot, String artifactName) throws Exception {
        return new MappedArtifact(
            Files.readString(outputRoot.resolve(artifactName)).lines().toList(),
            Files.readString(project.resolve("src/main.deal")).lines().toList(),
            Files.readString(project.resolve("src/lib.deal")).lines().toList(),
            Map.of("main.deal.map.json", parseSidecarMappings(Files.readString(
                    outputRoot.resolve("main.deal.map.json"))),
                "lib.deal.map.json", parseSidecarMappings(Files.readString(
                    outputRoot.resolve("lib.deal.map.json")))));
    }

    private static void verifyDirectEmissionGroups(MappedArtifact artifact,
            String artifactName, String commentMarker, String parameterPattern,
            String factoryGroupStart, String factoryGroupEnd) throws Exception {
        List<String> lines = artifact.lines();
        List<String> appLines = artifact.appLines();
        List<String> libLines = artifact.libLines();

        // The expected source positions, read from the fixture sources.
        int parameterLine = lineContaining(appLines, "export function f(") + 1;
        int parameterColumn = appLines.get(parameterLine - 1).indexOf("x:") + 1;
        int classLine = lineContaining(libLines, "export class Point") + 1;
        int classColumn = libLines.get(classLine - 1).indexOf("class") + 1;
        int constructLine = lineContaining(appLines, "let p: lib.Point") + 1;
        int constructColumn = appLines.get(constructLine - 1).indexOf("{ x:") + 1;

        // (1) The directly emitted parameter transfer.
        List<Integer> parameterGroups = matchingLines(lines, parameterPattern);
        checkEq(1, parameterGroups.size(), artifactName
            + " carries exactly one directly emitted parameter transfer: "
            + parameterGroups);
        if (parameterGroups.size() == 1) {
            int statementLine = parameterGroups.get(0);
            String comment = lines.get(statementLine - 1);
            checkEq(commentMarker + "src/main.deal:" + parameterLine + ":"
                    + parameterColumn,
                comment.strip(),
                artifactName + " precedes the parameter transfer with the "
                    + "parameter's own source comment");
            // The comment directly precedes the statement, so the
            // statement's 0-based index is the comment's 1-based line.
            checkSidecarMapping(artifact, "main.deal.map.json", statementLine,
                comment, commentMarker, parameterLine, parameterColumn,
                artifactName + " parameter transfer");
        }

        // (2) The owner-side class-factory group.
        List<Integer> allFactoryStarts = matchingLines(lines, factoryGroupStart);
        checkEq(2, allFactoryStarts.size(), artifactName
            + " carries one factory group per distinct construction site");
        List<Integer> factoryStarts = allFactoryStarts
            .stream().filter(index -> index > 0 && lines.get(index - 1).strip()
                .equals(commentMarker + "src/lib.deal:" + classLine + ":"
                    + classColumn)).toList();
        checkEq(1, factoryStarts.size(), artifactName
            + " carries exactly one owner-side factory group: " + factoryStarts);
        if (factoryStarts.size() != 1) {
            return;
        }
        int factoryStart = factoryStarts.get(0);
        String factoryComment = lines.get(factoryStart - 1);
        checkEq(commentMarker + "src/lib.deal:" + classLine + ":" + classColumn,
            factoryComment.strip(),
            artifactName + " precedes the owner-side factory group with the "
                + "class declaration comment of the owning module");
        // The factory comment directly precedes the group's first statement
        // (the 0-based statement index is the comment's 1-based line).
        checkSidecarMapping(artifact, "lib.deal.map.json", factoryStart,
            factoryComment, commentMarker, classLine, classColumn,
            artifactName + " owner-side factory group");

        // (3) The caller's attribution resumes after the group's module
        // restore: the first source comment after the restore names the
        // caller's own construction span and the entry sidecar maps it.
        int nextFactory = allFactoryStarts.stream()
            .filter(index -> index > factoryStart).findFirst().orElse(lines.size());
        int factoryEnd = -1;
        for (int candidate : matchingLines(lines, factoryGroupEnd)) {
            if (candidate > factoryStart && candidate < nextFactory) {
                factoryEnd = candidate;
            }
        }
        check(factoryEnd > factoryStart, artifactName
            + " restores the caller's module after the owner-side factory "
            + "group");
        if (factoryEnd <= factoryStart) {
            return;
        }
        int callerComment = -1;
        for (int i = factoryEnd + 1; i < lines.size(); i++) {
            if (lines.get(i).strip().startsWith(commentMarker)) {
                callerComment = i;
                break;
            }
        }
        check(callerComment > factoryEnd, artifactName
            + " emits a source comment after the owner-side factory group");
        if (callerComment <= factoryEnd) {
            return;
        }
        String callerLine = lines.get(callerComment);
        checkEq(commentMarker + "src/main.deal:" + constructLine + ":"
                + constructColumn,
            callerLine.strip(),
            artifactName + " restores the caller's own source comment when "
                + "the caller's statements resume");
        checkSidecarMapping(artifact, "main.deal.map.json", callerComment + 1,
            callerLine, commentMarker, constructLine, constructColumn,
            artifactName + " caller restore");
    }

    /**
     * Verifies the omitted-default factory groups of one produced artifact:
     * each omitted default invocation, the owner-side transfer construction,
     * and the caller's final continuation carry the source comment and
     * sidecar mapping of their own source group; each emitted failure branch
     * keeps the caller's mapping on the caller's own terminal.
     */
    private static void verifyOmittedConstructionProviderAttribution(MappedArtifact artifact,
            String artifactName, String commentMarker,
            String factoryGroupStart, String factoryGroupEnd,
            String defaultInvocation, String transferConstruction,
            String callerTerminal) throws Exception {
        List<String> lines = artifact.lines();
        List<String> appLines = artifact.appLines();
        List<String> libLines = artifact.libLines();
        int classLine = lineContaining(libLines, "export class DefaultPoint") + 1;
        int classColumn = libLines.get(classLine - 1).indexOf("class") + 1;
        int constructLine = lineContaining(appLines, "let q: lib.DefaultPoint") + 1;
        int constructColumn = appLines.get(constructLine - 1).indexOf("{") + 1;
        String ownerComment = commentMarker + "src/lib.deal:" + classLine + ":"
            + classColumn;
        String callerComment = commentMarker + "src/main.deal:" + constructLine
            + ":" + constructColumn;

        // The one owner-side factory group of the fixture, closed by its
        // success-path module restore (the first restore after the transfer
        // construction; the failure branches restore inside the group).
        List<Integer> groupStarts = matchingLines(lines, factoryGroupStart)
            .stream().filter(index -> index > 0
                && lines.get(index - 1).strip().equals(ownerComment)).toList();
        checkEq(1, groupStarts.size(), artifactName
            + " carries exactly one owner-side factory group: " + groupStarts);
        if (groupStarts.size() != 1) {
            return;
        }
        int groupStart = groupStarts.get(0);

        // (1) Owner-side resumption: every omitted default invocation and
        // the owner-side transfer construction follow the owning module's
        // factory comment and are mapped by the owning module's sidecar at
        // exactly that generated position.
        List<Integer> invocations = new ArrayList<>();
        for (int candidate : matchingLines(lines, defaultInvocation)) {
            if (candidate > groupStart) {
                invocations.add(candidate);
            }
        }
        checkEq(0, invocations.size(), artifactName
            + " emits no retired initializer invocation: " + invocations);
        int transfer = -1;
        for (int i = (invocations.isEmpty()
                ? groupStart : invocations.get(invocations.size() - 1));
                i < lines.size(); i++) {
            if (lines.get(i).strip().matches(transferConstruction)) {
                transfer = i + 1;
                break;
            }
        }
        check(transfer > 0, artifactName
            + " emits the owner-side transfer construction after the last "
            + "omitted default: " + transfer);
        if (transfer <= 0) {
            return;
        }
        int groupEnd = -1;
        for (int candidate : matchingLines(lines, factoryGroupEnd)) {
            if (candidate > transfer) {
                groupEnd = candidate;
                break;
            }
        }
        check(groupEnd > transfer, artifactName
            + " closes the owner-side factory group on the success path: "
            + groupEnd);
        if (groupEnd <= transfer) {
            return;
        }
        List<Integer> ownerStatements = new ArrayList<>(invocations);
        ownerStatements.add(transfer);
        for (int statement : ownerStatements) {
            int comment = nearestPrecedingComment(lines, statement,
                commentMarker);
            check(comment > 0, artifactName + " precedes generated line "
                + statement + " with a source comment: " + comment);
            if (comment <= 0) {
                continue;
            }
            checkEq(ownerComment, lines.get(comment - 1).strip(),
                artifactName + " attributes generated line " + statement
                    + " to the owning module's factory group");
            checkSidecarMapping(artifact, "lib.deal.map.json", comment,
                lines.get(comment - 1), commentMarker, classLine, classColumn,
                artifactName + " owner-side resumption at generated line "
                    + statement);
        }

        // (2) Caller retention: each emitted failure branch keeps the
        // caller's mapping on the caller's own terminal.
        List<Integer> callerComments = new ArrayList<>();
        for (int index = groupStart; index < groupEnd - 1; index++) {
            if (lines.get(index).strip().equals(callerComment)) {
                callerComments.add(index + 1);
            }
        }
        checkEq(0, callerComments.size(), artifactName
            + " emits no retired initializer-failure branch: "
            + callerComments);
        for (int comment : callerComments) {
            boolean terminal = comment < lines.size()
                && lines.get(comment).strip().matches(callerTerminal);
            check(terminal, artifactName + " keeps the caller mapping on the "
                + "caller's own failure terminal after line " + comment + ": "
                + (comment < lines.size() ? lines.get(comment).strip() : ""));
        }

        // (3) Caller continuation: the first source comment after the
        // group's success-path restore names the caller's own construction
        // span and the entry sidecar maps it.
        int continuation = -1;
        for (int i = groupEnd; i < lines.size(); i++) {
            if (lines.get(i).strip().startsWith(commentMarker)) {
                continuation = i + 1;
                break;
            }
        }
        check(continuation > 0, artifactName
            + " emits a source comment after the owner-side factory group");
        if (continuation > 0) {
            checkEq(callerComment, lines.get(continuation - 1).strip(),
                artifactName + " restores the caller's own source comment "
                    + "when the caller's statements resume");
            checkSidecarMapping(artifact, "main.deal.map.json", continuation,
                lines.get(continuation - 1), commentMarker, constructLine,
                constructColumn, artifactName + " caller continuation");
        }
    }

    /**
     * The line number (1-based) of the nearest source comment at or before
     * the given generated statement line, or {@code -1}.
     */
    private static int nearestPrecedingComment(List<String> lines,
            int statementLine, String commentMarker) {
        for (int i = statementLine - 2; i >= 0; i--) {
            if (lines.get(i).strip().startsWith(commentMarker)) {
                return i + 1;
            }
        }
        return -1;
    }

    /** The 0-based index of the first line containing the text, or -1. */
    private static int lineContaining(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    /** The 0-based indexes of the lines whose stripped text matches the pattern. */
    private static List<Integer> matchingLines(List<String> lines, String pattern) {
        java.util.regex.Pattern compiled = java.util.regex.Pattern.compile(pattern);
        List<Integer> matched = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (compiled.matcher(lines.get(i).strip()).matches()) {
                matched.add(i);
            }
        }
        return matched;
    }

    /**
     * Verifies that the sidecar maps the comment line's own generated
     * position (its 1-based line and its marker column) to the expected
     * source position.
     */
    private static void checkSidecarMapping(MappedArtifact artifact, String sidecarName,
            int commentGeneratedLine, String commentLine, String commentMarker,
            int sourceLine, int sourceColumn, String context) {
        List<int[]> mappings = artifact.mappings().get(sidecarName);
        int generatedColumn = commentLine.indexOf(commentMarker) + 1;
        boolean mapped = false;
        for (int[] mapping : mappings) {
            mapped = mapping[0] == commentGeneratedLine
                && mapping[1] == generatedColumn
                && mapping[2] == sourceLine
                && mapping[3] == sourceColumn;
            if (mapped) {
                break;
            }
        }
        check(mapped, context + ": " + sidecarName + " maps generated line "
            + commentGeneratedLine + " column " + generatedColumn + " to "
            + "source " + sourceLine + ":" + sourceColumn);
    }

    /**
     * The exhaustive serializer check: a path carrying every C0 control
     * character (U+0000..U+001F, not only the LF/CR/TAB short escapes) must
     * serialize into a sidecar the strict canonical JSON transport parses,
     * with the decoded value equal to the path text.
     */
    private static void verifySerializerEscapesEveryControl() {
        SourceMapGenerator generator = new SourceMapGenerator();
        generator.emitStatement(1, 1, 1, 1);
        StringBuilder source = new StringBuilder("src");
        for (char control = 0; control < 0x20; control++) {
            source.append(control);
        }
        source.append("main.deal");
        String json = generator.toJson(source.toString(),
            "build/out/Main.java");
        Map<String, CanonicalJson.Value> fields = strictSidecarFields(json,
            "the every-C0-control serializer probe");
        if (fields == null) {
            return;
        }
        checkEq(source.toString(), canonicalScalar(fields.get("source")),
            "the serializer escapes every C0 control with the decoded value "
                + "preserved");
    }

    /**
     * Strictly parses one published sidecar through the canonical JSON
     * transport — the repository's strict reader, which rejects a raw control
     * character inside a string, an invalid escape, and malformed syntax —
     * and verifies the pinned version and the decoded source/generated paths
     * against the actual ones.
     */
    private static void verifyStrictSidecarPaths(Path outputRoot,
            String sidecarName, String expectedSource, String expectedGenerated)
            throws Exception {
        Path sidecar = outputRoot.resolve(sidecarName);
        check(Files.isRegularFile(sidecar),
            "the control-path sidecar is published: " + sidecarName);
        if (!Files.isRegularFile(sidecar)) {
            return;
        }
        Map<String, CanonicalJson.Value> fields = strictSidecarFields(
            Files.readString(sidecar, StandardCharsets.UTF_8), sidecarName);
        if (fields == null) {
            return;
        }
        checkEq(1, canonicalScalar(fields.get("version")),
            sidecarName + " carries the pinned version");
        checkEq(expectedSource, canonicalScalar(fields.get("source")),
            sidecarName + " decodes the actual source path");
        checkEq(expectedGenerated, canonicalScalar(fields.get("generated")),
            sidecarName + " decodes the actual generated path");
        check(fields.get("mappings") instanceof CanonicalJson.Arr,
            sidecarName + " carries its mappings array");
    }

    /**
     * The top-level fields of one strictly parsed sidecar, or {@code null}
     * after failing the check when the document is not strict JSON.
     */
    private static Map<String, CanonicalJson.Value> strictSidecarFields(
            String json, String context) {
        CanonicalJson.Obj root;
        try {
            root = (CanonicalJson.Obj) CanonicalJson.parse(json);
        } catch (RuntimeException malformed) {
            fail(context + " is not strict JSON: " + malformed);
            return null;
        }
        Map<String, CanonicalJson.Value> fields = new java.util.LinkedHashMap<>();
        for (CanonicalJson.Entry entry : root.entries()) {
            fields.put(entry.key(), entry.value());
        }
        return fields;
    }

    /** The Java scalar of one canonical JSON value (containers stay as-is). */
    private static Object canonicalScalar(CanonicalJson.Value value) {
        if (value instanceof CanonicalJson.Int integer) {
            return integer.value();
        }
        if (value instanceof CanonicalJson.Str string) {
            return string.value();
        }
        if (value instanceof CanonicalJson.Num number) {
            return number.value();
        }
        if (value instanceof CanonicalJson.Bool bool) {
            return bool.value();
        }
        return null;
    }

    // =========================================================================
    // 6. The single-arm dispatch: every recorded invocation runs the one
    //    production arm; a legacy profile fails closed at the lowering
    // =========================================================================

    private static void testSingleArmDispatch(Path project,
            ArmCompile reference) throws Exception {
        System.out.println("-- dispatch: every recorded invocation runs the one "
            + "production arm; the legacy profile fails closed at the "
            + "lowering --");

        {
            check(reference.success(), "the release-owned compile succeeds: "
                + reference.orchestrator().diagnostics());
            if (!reference.success()) {
                return;
            }
            Map<String, byte[]> referenceSet =
                snapshotTree(project.resolve("out"));
            check(referenceSet.containsKey("main.lua")
                    && !referenceSet.containsKey("lib.lua"),
                "the release-owned compile publishes the one project artifact: "
                    + referenceSet.keySet());

        }

        // A fail-closed input keeps the release-owned diagnostics under a
        // delivery-equivalent record: the E6005 text carries the profile
        // (identical for both records), so code and message match exactly.
        Path fail = Files.createTempDirectory("production-dispatch-diag-");
        try {
            write(fail, "deal.json", DEAL_JSON_LUA);
            write(fail, "src/main.deal", UNREALIZED_CONSTRUCT_SOURCE);
            ArmCompile releaseFail = compileWithInvocation(fail,
                "src/main.deal", productionInvocation());
            ArmCompile shadowFail = compileWithInvocation(fail,
                "src/main.deal", productionInvocation());
            check(!releaseFail.success() && !shadowFail.success(),
                "the fail-closed input fails under both records: "
                    + releaseFail.orchestrator().diagnostics() + " / "
                    + shadowFail.orchestrator().diagnostics());
            checkEq(diagnosticTexts(releaseFail.orchestrator()),
                diagnosticTexts(shadowFail.orchestrator()),
                "the delivery-equivalent record reports the release-owned "
                    + "diagnostics for the fail-closed input");
            check(diagnosticTexts(shadowFail.orchestrator()).stream()
                    .anyMatch(diagnostic -> diagnostic.contains("E3001")
                        && diagnostic.contains("Cannot assign")),
                "the shared diagnostics name the fail-closed construct: "
                    + diagnosticTexts(shadowFail.orchestrator()));
        } finally {
            deleteRecursively(fail);
        }

        // The surviving harness mirror records its invocation but selects no
        // arm: its result equals the release-owned compile for the same
        // input, artifact for artifact.
        Path mirror = Files.createTempDirectory("production-dispatch-mirror-");
        try {
            write(mirror, "deal.json", DEAL_JSON_LUA);
            write(mirror, "src/lib.deal", LIB_SOURCE);
            write(mirror, "src/main.deal", APP_SOURCE);
            ProjectOutcome harness = runHarnessCli("compile",
                mirror.resolve("src/main.deal").toAbsolutePath().toString(),
                "--output", mirror.resolve("out-harness").toAbsolutePath()
                    .toString());
            ProjectOutcome release = productionCompile(mirror, "src/main.deal",
                "out-release");
            check(harness.exitCode() == 0 && release.exitCode() == 0,
                "the harness mirror and the release-owned compile both "
                    + "succeed: harness=" + harness.exitCode() + " release="
                    + release.exitCode() + " stderr=" + harness.stderr());
            check(treeEqualsApartFromSidecarPaths(
                    snapshotTree(mirror.resolve("out-harness")),
                    snapshotTree(mirror.resolve("out-release"))),
                "the harness mirror publishes the release-owned artifact set "
                    + "byte-for-byte (each sidecar keeps its own "
                    + "output-root-relative generated path spelling)");
        } finally {
            deleteRecursively(mirror);
        }
    }

    private static List<String> diagnosticTexts(
            CompilationOrchestrator orchestrator) {
        return orchestrator.diagnostics().stream()
            .map(d -> d.code() + "|" + d.severity() + "|" + d.message())
            .toList();
    }

    /**
     * The dumps-enabled fail-closed rejection (both targets): the accepted
     * compile stages its generated IR dump with the artifact set after
     * the one production arm accepted it, while a checker-valid input
     * whose lowering fails closed stages nothing at all — no staging
     * transaction starts, so a staging fault installed at staging start
     * cannot preempt the one E6005 CONSTRUCT_UNLOWERED.
     */
    private static void testFailClosedStagesNothingWithDumps()
            throws Exception {
        System.out.println("-- fail-closed rejection with --dump-ir: the "
            + "accepted dump stages after acceptance; the rejection stages "
            + "nothing and no staging fault can preempt it --");

        for (String backend : List.of("luajit", "jvm")) {
            Path project = Files.createTempDirectory(
                "production-dispatch-dump-");
            try {
                write(project, "deal.json",
                    "jvm".equals(backend) ? DEAL_JSON_JVM : DEAL_JSON_LUA);
                write(project, "src/main.deal",
                    "export function main(): null { return null; }\n");
                Path out = project.resolve("out");

                // Accepted control: the release-owned compile with
                // --dump-ir generates the dump in its existing phase and
                // stages it in the accepted compile's one transaction.
                int[] stagingStarts = {0};
                PublicationStager.installPublishFault(step -> {
                    if (PublicationStager.FAULT_STEP_STAGING_BEGAN.equals(
                            step)) {
                        stagingStarts[0]++;
                    }
                });
                ArmCompile accepted;
                try {
                    accepted = compileWithInvocation(project, "src/main.deal",
                        productionInvocation(), true);
                } finally {
                    PublicationStager.clearPublishFault();
                }
                checkEq(1, stagingStarts[0], backend
                    + ": the accepted compile stages exactly one staging "
                    + "transaction (dump and artifact)");
                check(accepted.success() && artifactFiles(out)
                        .contains("main.ir.txt"), backend
                    + ": the accepted compile publishes the generated IR "
                    + "dump: " + accepted.orchestrator().diagnostics());

                // The fail-closed rejection with --dump-ir: exactly one
                // E6005, zero staging starts (the pending dump is
                // discarded), and the prior live set byte-identical. The
                // staging fault installed at staging start never fires, so
                // it cannot preempt the fail-closed diagnostic with a
                // publish I/O error.
                write(project, "src/main.deal", UNREALIZED_CONSTRUCT_SOURCE);
                Map<String, byte[]> before = snapshotTree(out);
                stagingStarts[0] = 0;
                PublicationStager.installPublishFault(step -> {
                    if (PublicationStager.FAULT_STEP_STAGING_BEGAN.equals(
                            step)) {
                        stagingStarts[0]++;
                        throw new IOException(
                            "injected staging failure (fail-closed probe)");
                    }
                });
                ArmCompile rejected;
                try {
                    rejected = compileWithInvocation(project, "src/main.deal",
                        productionInvocation(), true);
                } finally {
                    PublicationStager.clearPublishFault();
                }
                check(!rejected.success(), backend
                    + ": the dumps-enabled fail-closed input is rejected");
                List<CompilerDiagnostic> errors =
                    rejected.orchestrator().diagnostics().stream()
                        .filter(d -> "error".equals(d.severity())).toList();
                checkEq(1, errors.size(), backend
                    + ": exactly one error diagnostic (the staging fault "
                    + "cannot preempt it): "
                    + rejected.orchestrator().diagnostics());
                if (errors.size() == 1) {
                    check(errors.get(0).code().equals("E3001")
                            && errors.get(0).message().contains("Cannot assign"),
                        backend + ": the one failure is E3001 Cannot assign"
                            + ": " + errors.get(0).message());
                }
                checkEq(0, rejected.orchestrator().semanticEmissionCount(),
                    backend + ": the fail-closed lowering records no project "
                        + "emission");
                checkEq(0, stagingStarts[0], backend
                    + ": the rejected compile starts no staging transaction");
                checkTreeIdentical(before, out, backend
                    + ": the rejected compile leaves the prior artifact set "
                    + "byte-identical");
                checkEq(List.of(), stageResidue(out), backend
                    + ": the rejected compile leaves no stage or retired "
                    + "residue");
            } finally {
                deleteRecursively(project);
            }
        }
    }

    /** The stage/retired siblings beside one live output root. */
    private static List<String> stageResidue(Path root) throws Exception {
        Path normalized = root.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return List.of();
        }
        String name = normalized.getFileName().toString();
        String stagePrefix = name + PublicationStager.STAGE_TREE_MARKER;
        String retiredPrefix = name + PublicationStager.RETIRED_TREE_MARKER;
        try (Stream<Path> entries = Files.list(parent)) {
            return entries.map(path -> path.getFileName().toString())
                .filter(entry -> entry.startsWith(stagePrefix)
                    || entry.startsWith(retiredPrefix))
                .sorted().toList();
        }
    }

    private static boolean treeEquals(Map<String, byte[]> left,
            Map<String, byte[]> right) {
        if (!left.keySet().equals(right.keySet())) {
            return false;
        }
        for (Map.Entry<String, byte[]> entry : left.entrySet()) {
            if (!Arrays.equals(entry.getValue(), right.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether two published trees are byte-identical apart from each
     * sidecar's {@code generated} field: a sidecar names its own
     * output-root-relative generated artifact (the pinned project-relative
     * spelling), so two compiles of one project into differently named roots
     * have path-bearing sidecar documents and byte-equal artifacts.
     */
    private static boolean treeEqualsApartFromSidecarPaths(
            Map<String, byte[]> left, Map<String, byte[]> right) {
        if (!left.keySet().equals(right.keySet())) {
            return false;
        }
        for (Map.Entry<String, byte[]> entry : left.entrySet()) {
            byte[] other = right.get(entry.getKey());
            if (entry.getKey().endsWith(".deal.map.json")) {
                if (!normalizedSidecarText(entry.getValue())
                        .equals(normalizedSidecarText(other))) {
                    return false;
                }
            } else if (!Arrays.equals(entry.getValue(), other)) {
                return false;
            }
        }
        return true;
    }

    /** One sidecar document with its generated-path field text normalized. */
    private static String normalizedSidecarText(byte[] json) {
        return new String(json, StandardCharsets.UTF_8).replaceAll(
            "\"generated\": \"[^\"]*\"",
            "\"generated\": \"<artifact>\"");
    }


    // =========================================================================
    // ISSUE-0786: the strict v1.4 array-read contract on the three
    // production artifacts
    // =========================================================================

    /**
     * The v1.4 ArrayReadV13 flight on the three real production artifacts:
     * one module asserts, under its own TEST_FAIL guards, receiver-then-index
     * single evaluation, the E8002 bounds gate at the read expression with
     * the prior receiver/index effects retained, the empty and
     * nullable-element bounds cases, the in-range null/missing typed results,
     * and the unchanged append-at-length write with commit-nothing-on-failure.
     * The compiled artifact must print exactly {@code ok} with empty stderr
     * and exit 0 under node, luajit, and java, so a regression in any
     * consumer fails its own assertion there, never a skipped leg.
     */
    private static final String ARRAY_READ_V13_SOURCE = """
        import * as c from "std/console";

        class Log {
          seq: number[];
        }

        function record(l: Log, tag: number): number {
          l.seq[l.seq.length] = tag;
          return tag;
        }

        function pick(l: Log, tag: number): number[] {
          record(l, tag);
          return [10.0, 20, 30];
        }

        export function main(): null {
          // Receiver then index, once each, in-range value.
          let l: Log = { seq: [] };
          let v: number = pick(l, 1)[record(l, 2)];
          if (l.seq.length !== 2) {
            throw { code: "TEST_FAIL", message: "read must evaluate receiver and index once each" };
          }
          if (l.seq[0] !== 1 || l.seq[1] !== 2) {
            throw { code: "TEST_FAIL", message: "read order must be receiver then index" };
          }
          if (v !== 30) {
            throw { code: "TEST_FAIL", message: "in-range read value mismatch" };
          }

          // Bounds failure retains the prior operand effects.
          let l2: Log = { seq: [] };
          let boundsCode: string = "";
          try {
            let bad: number = pick(l2, 1)[record(l2, 2) + 4];
            boundsCode = "NOFAIL";
          } catch (e) {
            boundsCode = e.code;
          }
          if (boundsCode !== "E8002") {
            throw { code: "TEST_FAIL", message: "out-of-range read must raise E8002" };
          }
          if (l2.seq.length !== 2 || l2.seq[0] !== 1 || l2.seq[1] !== 2) {
            throw { code: "TEST_FAIL", message: "bounds failure must retain prior effects" };
          }

          // Every element descriptor: nullable and empty arrays.
          let nullable: (number | null)[] = [1.0, null, 3];
          let nullableCode: string = "";
          try {
            let bad: number | null = nullable[3];
            nullableCode = "NOFAIL";
          } catch (e) {
            nullableCode = e.code;
          }
          if (nullableCode !== "E8002") {
            throw { code: "TEST_FAIL", message: "nullable out-of-range read must raise E8002" };
          }
          let empty: number[] = [];
          let emptyCode: string = "";
          try {
            let bad: number = empty[0];
            emptyCode = "NOFAIL";
          } catch (e) {
            emptyCode = e.code;
          }
          if (emptyCode !== "E8002") {
            throw { code: "TEST_FAIL", message: "empty-array read must raise E8002" };
          }

          // In-range null and deleted slots keep their typed results.
          if (nullable[1] !== null) {
            throw { code: "TEST_FAIL", message: "in-range null element must stay null" };
          }
          let inRange: number | null = nullable[2];
          if (inRange !== null) {
            if (inRange !== 3) {
              throw { code: "TEST_FAIL", message: "in-range nullable value mismatch" };
            }
          } else {
            throw { code: "TEST_FAIL", message: "in-range nullable value lost" };
          }
          let deletedNullable: (number | null)[] = [1.0, 2, 3];
          delete deletedNullable[1];
          if (deletedNullable[1] !== null) {
            throw { code: "TEST_FAIL", message: "in-range deleted nullable slot must read null" };
          }
          let plain: number[] = [7];
          delete plain[0];
          let deletedCode: string = "";
          try {
            let bad: number = plain[0];
            deletedCode = "NOFAIL";
          } catch (e) {
            deletedCode = e.code;
          }
          if (deletedCode !== "E8001") {
            throw { code: "TEST_FAIL", message: "in-range deleted non-nullable slot must keep E8001" };
          }

          // Writes: append at length, one commit, nothing on failure.
          let writes: number[] = [7];
          let alias: number[] = writes;
          writes[writes.length] = 8;
          if (writes.length !== 2 || alias[1] !== 8) {
            throw { code: "TEST_FAIL", message: "append-at-length write lost" };
          }
          let badValue: table = { bad: "not-number" };
          let writeCode: string = "";
          try {
            writes[0] = badValue.bad;
            writeCode = "NOFAIL";
          } catch (e) {
            writeCode = e.code;
          }
          if (writeCode !== "E8001") {
            throw { code: "TEST_FAIL", message: "failed element write must raise E8001" };
          }
          if (writes[0] !== 7 || writes.length !== 2) {
            throw { code: "TEST_FAIL", message: "failed write must commit nothing" };
          }

          c.log("ok");
          return null;
        }
        """;

    private static void testArrayReadV13ProductionDrive() throws Exception {
        System.out.println("-- the v1.4 array-read contract on the three "
            + "production artifacts --");
        for (String backend : List.of("js", "luajit", "jvm")) {
            Path project = Files.createTempDirectory(
                "array-read-v13-" + backend + "-");
            try {
                write(project, "deal.json", switch (backend) {
                    case "js" -> DEAL_JSON_JS;
                    case "luajit" -> DEAL_JSON_LUA;
                    default -> DEAL_JSON_JVM;
                });
                write(project, "src/main.deal", ARRAY_READ_V13_SOURCE);
                ProjectOutcome compile = productionCompile(project, "src/main.deal",
                    "out");
                check(compile.exitCode() == 0, "the " + backend
                    + " v1.4 array-read compile succeeds: " + compile.stderr());
                if (compile.exitCode() != 0) {
                    continue;
                }
                Path out = project.resolve("out");
                switch (backend) {
                    case "js" -> checkSiblingJsRun("v1.4 array read", out);
                    case "luajit" -> checkSiblingLuaRun("v1.4 array read", out);
                    default -> checkSiblingJvmRun("v1.4 array read", project, out);
                }
            } finally {
                deleteRecursively(project);
            }
        }
    }

    // =========================================================================
    // The std/json decode carriers at the declared (table) parameter (v1.4)
    // =========================================================================

    /**
     * The four carrier exports of the tuple drive. The entry module keeps
     * its {@code (): null} {@code main}, so each carrier is driven through
     * an ordinary zero-arity export exactly like the production lane
     * protocol drives a fixture's own export. The last two carriers are
     * class instances — a declared class and the builtin {@code Error} —
     * carried through a table slot so the contextual read supplies the
     * runtime {@code table} value the declared std/json parameter rejects.
     */
    private static final String JSON_CARRIER_TUPLE_V13_SOURCE = """
        import * as json from "std/json";

        class Box {
          label: string;
        }

        export function arrayCarrier(): string {
          let doc: table = json.parse("{\\"xs\\":[1.0,2.0]}");
          return json.stringify(doc.xs);
        }

        export function nullCarrier(): string {
          let doc: table = json.parse("{\\"n\\":null}");
          return json.stringify(doc.n);
        }

        export function classCarrier(): string {
          let holder: table = {};
          let box: Box = {label: "b"};
          holder.box = box;
          return json.stringify(holder.box);
        }

        export function errorCarrier(): string {
          let holder: table = {};
          let failure: Error = {code: "", message: ""};
          holder.failure = failure;
          return json.stringify(holder.failure);
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The compiled contextual-read call form at the declared std/json
     * {@code table} parameter, pinned field-exact on all three production
     * lanes from one project source: code, message, expected, actual, and
     * the failing contextual read's own source position, through a real
     * execution of each published artifact (node, luajit, java). Every
     * lane records both cells — the contextual read's own {@code table}
     * boundary and the declared {@code STDLIB_PARAMETER} cell of the
     * {@code std/json} entry — and the read cell fires first at the read
     * expression (the pinned position is the argument expression's own
     * start, never the enclosing call's), so the lanes must agree byte for
     * byte on the same tuple.
     *
     * <p>The two class instances project their carried canonical class
     * atom — the shared {@code FailureProjections.typedBoundaryToken} for
     * {@code ActualKind.CLASS}: the declared class projects
     * {@code @<configuredRoot>/<ClassName>} (the configured root text and
     * the file-stem-free relative module components) and the builtin
     * {@code Error} instance its {@code Error} atom. {@code class} stays
     * the expected token of a class descriptor and never becomes an
     * actual, on any lane.</p>
     *
     * <p>The cross-lane outcome agreement over the same carriers is
     * registered as the corpus fixture
     * {@code backend-runtime/stdlib/json/json-stringify-contextual-read-carrier}
     * driven through this suite's boundary-corpus leg and the JS corpus
     * lane.</p>
     */
    private static void testStdlibJsonCarrierBoundaryV13ProductionDrive()
            throws Exception {
        System.out.println("-- the declared std/json table parameter at a"
            + " contextual read used as a call argument: the carrier tuple"
            + " on the three production lanes --");
        Path project = Files.createTempDirectory("json-carrier-tuple-");
        try {
            write(project, "src/main.deal", JSON_CARRIER_TUPLE_V13_SOURCE);
            String sourceFile = project.resolve("src/main.deal")
                .toAbsolutePath().normalize().toString();
            // The pinned tuple: the failing contextual read's own argument
            // position, and the carried canonical class atom for the two
            // class instances (the declared class and the builtin Error).
            int[] arrayAt = argumentPositionOf(JSON_CARRIER_TUPLE_V13_SOURCE,
                "json.stringify(doc.xs)", "doc.xs");
            int[] nullAt = argumentPositionOf(JSON_CARRIER_TUPLE_V13_SOURCE,
                "json.stringify(doc.n)", "doc.n");
            int[] classAt = argumentPositionOf(JSON_CARRIER_TUPLE_V13_SOURCE,
                "json.stringify(holder.box)", "holder.box");
            int[] errorAt = argumentPositionOf(JSON_CARRIER_TUPLE_V13_SOURCE,
                "json.stringify(holder.failure)", "holder.failure");
            String expected = String.join("\n",
                "arrayCarrier|E8001|expected table|table|array|" + sourceFile
                    + ":" + arrayAt[0] + ":" + arrayAt[1],
                "nullCarrier|E8001|expected table|table|null|" + sourceFile
                    + ":" + nullAt[0] + ":" + nullAt[1],
                "classCarrier|E8001|expected table|table|@src/main.deal/Box|" + sourceFile
                    + ":" + classAt[0] + ":" + classAt[1],
                "errorCarrier|E8001|expected table|table|Error|" + sourceFile
                    + ":" + errorAt[0] + ":" + errorAt[1],
                "");
            for (String backend : List.of("js", "luajit", "jvm")) {
                write(project, "deal.json", switch (backend) {
                    case "js" -> DEAL_JSON_JS;
                    case "luajit" -> DEAL_JSON_LUA;
                    default -> DEAL_JSON_JVM;
                });
                String outName = "out-" + backend;
                ProjectOutcome compile = productionCompile(project,
                    "src/main.deal", outName);
                check(compile.exitCode() == 0, "the " + backend
                    + " std/json carrier-tuple compile succeeds: "
                    + compile.stderr());
                if (compile.exitCode() != 0) {
                    continue;
                }
                BoundedProcess.BoundedResult run = carrierTupleRun(project,
                    backend, project.resolve(outName));
                checkEqualCapture("the " + backend + " std/json carrier"
                    + " tuple", backend, run, 0, expected);
            }
        } finally {
            deleteRecursively(project);
        }
    }

    /**
     * One published carrier-tuple artifact under the real toolchain of its
     * backend: each of the four carriers is driven once (the production
     * lane protocol's zero-arity export drive) and the caught runtime error
     * prints its exact tuple as {@code name|code|message|expected|actual|
     * origin}. All three probes report the origin as the one
     * {@code file:line:column} spelling the LuaJIT/JVM artifacts already
     * publish, so one expected transcript pins all lanes.
     */
    private static BoundedProcess.BoundedResult carrierTupleRun(Path project,
            String backend, Path out) throws Exception {
        List<String> carriers = List.of("arrayCarrier", "nullCarrier",
            "classCarrier", "errorCarrier");
        if ("js".equals(backend)) {
            write(out, "tuple.js", """
                "use strict";
                const $rt = require("./deal/runtime");
                const $entry = require("./main");
                for (const $name of process.argv.slice(2)) {
                  try {
                    const $value = $entry[$name].$f();
                    console.log($name + "|admitted|" + $value);
                  } catch ($e) {
                    const $err = $rt.reifyError($e);
                    console.log($name + "|" + $err.code + "|" + $err.message
                      + "|" + $err.expected + "|" + $err.actual + "|"
                      + $err.file + ":" + $err.line + ":" + $err.column);
                  }
                }
                """);
            List<String> command = new ArrayList<>(List.of("node", "tuple.js"));
            command.addAll(carriers);
            return runBounded(out, command.toArray(String[]::new));
        }
        if ("luajit".equals(backend)) {
            Path probe = project.resolve("carrier-tuple.lua");
            Files.writeString(probe, """
                local surface = dofile(arg[1])
                local ok, err = __dealMain()
                if not ok then error(err, 0) end
                for i = 2, #arg do
                  local name = arg[i]
                  local okX, errX = pcall(surface[name].f)
                  if okX then
                    print(name .. '|admitted|' .. tostring(errX))
                  else
                    if type(errX) ~= 'table' or not errX[__dealCarrierKeys.d] then
                      error(errX, 0)
                    end
                    print(name .. '|' .. errX.code .. '|' .. errX.m .. '|'
                      .. (errX.e or '') .. '|' .. (errX.a or '') .. '|'
                      .. tostring(errX.o))
                  end
                end
                """);
            List<String> command = new java.util.ArrayList<>(List.of(
                "luajit", probe.toString(), out.resolve("main.lua").toString()));
            command.addAll(carriers);
            return runBounded(project, command.toArray(String[]::new));
        }
        String classpath = absoluteClasspath();
        Path artifact = out.resolve("Main.java");
        check(Files.isRegularFile(artifact), "the production CLI stages the"
            + " JVM artifact " + artifact);
        if (!Files.isRegularFile(artifact)) {
            return runBounded(project, "java", "-version");
        }
        BoundedProcess.BoundedResult javac = compileBoundedJvm(project,
            javacCommand(classpath, out, List.of(artifact.toString())));
        check(javac.exitCode() == 0, "the emitted JVM carrier-tuple"
            + " artifacts accept the probe: " + javac.stderr());
        if (javac.exitCode() != 0) {
            return javac;
        }
        List<String> command = new java.util.ArrayList<>(List.of("java", "-cp",
            out + File.pathSeparator + classpath,
            CarrierTupleChild.class.getName(), "Main", "main"));
        command.addAll(carriers);
        return runBounded(out, command.toArray(String[]::new));
    }

    /**
     * The JVM leg's probe: entry {@code main} once, then each named
     * zero-arity export, printing the caught {@code DealError}'s tuple in
     * the same {@code name|code|message|expected|actual|origin} shape the
     * node and luajit probes print.
     */
    public static final class CarrierTupleChild {
        public static void main(String[] args) throws Throwable {
            java.lang.invoke.MethodHandles.publicLookup().findStatic(
                Class.forName(args[0]), "dealMain",
                java.lang.invoke.MethodType.methodType(void.class)).invokeExact();
            deal.codegen.jvm.JvmRuntime.Table surface =
                deal.codegen.jvm.JvmRuntime.exportSurface(args[1]);
            for (int i = 2; i < args.length; i++) {
                String name = args[i];
                try {
                    ((deal.codegen.jvm.JvmRuntime.FunctionValue)
                        surface.read(name)).fn.invoke(new Object[0]);
                    System.out.println(name + "|admitted|");
                } catch (deal.codegen.jvm.JvmRuntime.DealError error) {
                    System.out.println(name + "|" + error.code + "|"
                        + error.msg + "|"
                        + (error.expected == null ? "" : error.expected)
                        + "|" + (error.actual == null ? "" : error.actual)
                        + "|" + error.origin);
                }
            }
        }
    }

    // =========================================================================
    // The closed SQRT_NEGATIVE arm at math.sqrt(-1.0) (v1.4)
    // =========================================================================

    /**
     * The closed {@code SQRT_NEGATIVE} arm at {@code math.sqrt(-1.0)} on
     * the three production lanes: the fixture
     * {@code backend-runtime/stdlib/math/sqrt-negative} is compiled through
     * the release-owned production CLI for each backend and its published
     * artifact is executed under the real toolchain (entry {@code main}
     * once, then the fixture's own zero-arity export).
     *
     * <p>The fixture's Structured Expectation Sidecar is the pin: code,
     * message, the failing call expression's own source coordinate and the
     * canonical hex-float actual. The sidecar pins no {@code expected}
     * member — the closed arm has none — so the drive additionally asserts
     * that absence structurally (each probe prints a distinct
     * {@code <absent>} marker for an absent field, never an empty text): a
     * lane that re-introduces an expected token is a named failure on that
     * lane. The JS realization of {@code std/math} and the LuaJIT
     * realization of {@code std/math} publish the same tuple their compiled
     * lanes do, so the three observations agree byte for byte.</p>
     */
    private static void testMathSqrtNegativeArmThreeLanes() throws Exception {
        System.out.println("-- the closed SQRT_NEGATIVE arm at math.sqrt(-1.0)"
            + " on LuaJIT, the JVM and the JS lane --");
        String locator = "backend-runtime/stdlib/math/sqrt-negative.deal";
        String export = "test_math_sqrt_negative";
        Path fixture = REPO_ROOT.resolve("test/conformance").resolve(locator);
        String raw = Files.readString(fixture);
        String source = ConformanceHarnessMetadata.productionCorpusSource(
            raw, locator);
        int strippedHeaders = raw.split("\n", -1).length
            - source.split("\n", -1).length;
        deal.test.conformance.SidecarExpectations.StructuredExpectationSidecar
            sidecar = deal.test.conformance.SidecarExpectations
                .StructuredExpectationSidecar.parse(Files.readString(
                    fixture.resolveSibling("sqrt-negative.expect.json")));
        String entryRel = "conformance/" + locator + "/main.deal";
        // The dotted module key of the materialized entry (the JVM
        // class-name and export-surface identity); its '.' separators are
        // also the published artifact's directory separators, so the
        // corpus directory's own dot splits the artifact path exactly as
        // the emitter splits the module identity
        // ({@code boundaryCorpusArtifactPath}).
        String moduleKey = entryRel.substring("conformance/".length(),
            entryRel.length() - ".deal".length()).replace('/', '.');
        for (String backend : List.of("luajit", "jvm", "js")) {
            deal.test.conformance.SidecarExpectations.RuntimeExpectation leg =
                sidecar.expectationFor(backend);
            check(leg instanceof deal.test.conformance.SidecarExpectations
                .RuntimeExpectation.Executed, locator + " [" + backend
                + "]: the sidecar pins an executed outcome");
            if (!(leg instanceof deal.test.conformance.SidecarExpectations
                    .RuntimeExpectation.Executed executed)) {
                continue;
            }
            checkEq("runtime-error", executed.mode(), locator + " ["
                + backend + "]: the pinned mode");
            deal.test.conformance.SidecarExpectations.ErrorExpectation pin =
                executed.error();
            check(pin != null, locator + " [" + backend
                + "]: the runtime-error pin carries its error");
            if (pin == null) {
                continue;
            }
            check(pin.expected().isEmpty(), locator + " [" + backend
                + "]: the closed arm pins no expected field");
            check(pin.actual().isPresent(), locator + " [" + backend
                + "]: the closed arm pins its actual spelling");
            Path project = Files.createTempDirectory(
                "sqrt-negative-arm-" + backend + "-");
            try {
                write(project, entryRel, source);
                // The fixture is materialized under the configured root
                // {@code conformance} at the corpus-relative path the
                // sidecar pins, so the artifact's own source coordinate and
                // the class identity are the published fixture's.
                write(project, "deal.json", "{\n  \"languageVersion\": \"1.4\","
                    + "\n  \"moduleRoots\": [\"conformance\"],"
                    + "\n  \"output\": \"out\","
                    + "\n  \"backend\": \"" + backend + "\"\n}\n");
                ProjectOutcome compile = productionCompile(project, entryRel,
                    "out");
                check(compile.exitCode() == 0, locator + " [" + backend
                    + "]: the production compile succeeds: "
                    + compile.stderr());
                if (compile.exitCode() != 0) {
                    continue;
                }
                String origin = project.resolve(entryRel).toAbsolutePath()
                    .normalize().toString() + ":"
                    + (pin.line() - strippedHeaders) + ":" + pin.column();
                String expected = "sqrt|" + pin.code() + "|" + pin.message()
                    + "|<absent>|" + pin.actual().orElse("") + "|" + origin
                    + "\n";
                BoundedProcess.BoundedResult run = sqrtArmRun(project,
                    backend, project.resolve("out"), moduleKey, export);
                checkEqualCapture(locator, backend, run, 1, expected);
            } finally {
                deleteRecursively(project);
            }
        }
    }

    /**
     * One published sqrt-arm artifact under the real toolchain of its
     * backend: entry {@code main} once, then the fixture's own zero-arity
     * export, and the caught runtime error's tuple printed as
     * {@code sqrt|code|message|expected|actual|origin}. An absent expected
     * or actual prints the distinct {@code <absent>} marker, so the closed
     * arm's missing member is asserted structurally rather than through an
     * empty text.
     */
    private static BoundedProcess.BoundedResult sqrtArmRun(Path project,
            String backend, Path out, String moduleKey, String export)
            throws Exception {
        if ("js".equals(backend)) {
            write(out, "sqrt-tuple.js", """
                "use strict";
                const $rt = require(process.argv[2] + "/deal/runtime");
                const $entry = require(process.argv[2] + "/" + process.argv[3]);
                const $absent = ($v) => $v === $rt.undefined ? "<absent>" : String($v);
                try {
                  $entry.main.$f();
                  $entry[process.argv[4]].$f();
                } catch ($e) {
                  const $err = $rt.reifyError($e);
                  console.log("sqrt|" + $err.code + "|" + $err.message + "|"
                    + $absent($err.expected) + "|" + $absent($err.actual) + "|"
                    + $err.file + ":" + $err.line + ":" + $err.column);
                  process.exitCode = 1;
                }
                """);
            return runBounded(out, "node", "sqrt-tuple.js",
                out.toAbsolutePath().toString(),
                boundaryCorpusArtifactPath(moduleKey), export);
        }
        if ("luajit".equals(backend)) {
            Path artifact = out.resolve(
                boundaryCorpusArtifactPath(moduleKey) + ".lua");
            check(Files.isRegularFile(artifact), "the production CLI stages"
                + " the Lua artifact " + artifact);
            if (!Files.isRegularFile(artifact)) {
                return runBounded(project, "luajit", "-v");
            }
            Path probe = project.resolve("sqrt-tuple.lua");
            Files.writeString(probe, """
                local surface = dofile(arg[1])
                local ok, err = __dealMain()
                if not ok then error(err, 0) end
                local okX, errX = pcall(surface[arg[2]].f)
                if okX then
                  print('sqrt|admitted|' .. tostring(errX))
                else
                  if type(errX) ~= 'table' or not errX[__dealCarrierKeys.d] then
                    error(errX, 0)
                  end
                  print('sqrt|' .. errX.code .. '|' .. errX.m .. '|'
                    .. (errX.e or '<absent>') .. '|' .. (errX.a or '<absent>')
                    .. '|' .. tostring(errX.o))
                  os.exit(1)
                end
                """);
            // The deployed artifact set is the execution root, so the
            // chunk resolves the published runtime next to it (the
            // distribution's own deployment layout), never the checkout's.
            return runBounded(out, "luajit",
                probe.toAbsolutePath().toString(),
                artifact.toAbsolutePath().toString(), export);
        }
        String className = deal.codegen.jvm.JvmNames.classNameFor(moduleKey);
        Path artifact = out.resolve(className + ".java");
        check(Files.isRegularFile(artifact), "the production CLI stages the"
            + " JVM artifact " + artifact);
        if (!Files.isRegularFile(artifact)) {
            return runBounded(project, "java", "-version");
        }
        String classpath = absoluteClasspath();
        BoundedProcess.BoundedResult javac = compileBoundedJvm(project,
            javacCommand(classpath, out, List.of(artifact.toString())));
        check(javac.exitCode() == 0, "the emitted JVM sqrt-arm artifacts"
            + " accept the probe: " + javac.stderr());
        if (javac.exitCode() != 0) {
            return javac;
        }
        return runBounded(project, "java", "-cp",
            out + File.pathSeparator + classpath,
            SqrtArmTupleChild.class.getName(), className, moduleKey, export);
    }

    /**
     * The published artifact's path under one output root for a dotted
     * module key: the module identity's '.' separators are the artifact's
     * directory separators, so a corpus directory whose own name carries a
     * dot (every fixture directory is named after its {@code .deal} file)
     * splits exactly as the emitter split the identity.
     */
    private static String boundaryCorpusArtifactPath(String moduleKey) {
        return moduleKey.replace('.', '/');
    }

    /**
     * The JVM leg's probe of the closed {@code SQRT_NEGATIVE} tuple: entry
     * {@code main} once, then the fixture's zero-arity export, printing the
     * caught {@code DealError}'s tuple in the same
     * {@code sqrt|code|message|expected|actual|origin} shape the node and
     * luajit probes print (an absent field prints {@code <absent>}).
     */
    public static final class SqrtArmTupleChild {
        public static void main(String[] args) throws Throwable {
            try {
                java.lang.invoke.MethodHandles.publicLookup().findStatic(
                    Class.forName(args[0]), "dealMain",
                    java.lang.invoke.MethodType.methodType(void.class))
                    .invokeExact();
                ((deal.codegen.jvm.JvmRuntime.FunctionValue)
                    deal.codegen.jvm.JvmRuntime.exportSurface(args[1])
                        .read(args[2])).fn.invoke(new Object[0]);
            } catch (deal.codegen.jvm.JvmRuntime.DealError error) {
                System.out.println("sqrt|" + error.code + "|" + error.msg
                    + "|" + (error.expected == null ? "<absent>" : error.expected)
                    + "|" + (error.actual == null ? "<absent>" : error.actual)
                    + "|" + error.origin);
                System.exit(1);
            }
        }
    }

    /**
     * The one-based line and column of the first occurrence of
     * {@code needle} in {@code source}: the drive's own derivation of the
     * failing call expression's reported position (never a hand-counted
     * literal).
     */
    private static int[] sourcePositionOf(String source, String needle) {
        int index = source.indexOf(needle);
        if (index < 0) {
            throw new IllegalStateException("the fixture source carries no '"
                + needle + "' occurrence");
        }
        int line = 1;
        int column = 1;
        for (int i = 0; i < index; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new int[] { line, column };
    }

    /**
     * The one-based line and column of one argument expression inside a
     * call expression: {@code callText} locates the call in
     * {@code source}, and {@code argumentText} is the argument's spelling
     * within that call text.
     */
    private static int[] argumentPositionOf(String source, String callText,
                                            String argumentText) {
        int[] call = sourcePositionOf(source, callText);
        int offset = callText.indexOf(argumentText);
        if (offset < 0) {
            throw new IllegalStateException("the call text '" + callText
                + "' carries no '" + argumentText + "' argument");
        }
        return new int[] { call[0], call[1] + offset };
    }

    // =========================================================================
    // ISSUE-0791: the boundary fixtures whose pin lives in a driven export
    // =========================================================================

    /**
     * The registered runtime-corpus fixtures whose expectation the JS
     * corpus lane cannot assert as a structured verdict: the lane's
     * runtime-error branch checks the captured diagnostic code only, and
     * its runtime-ok branch drives the fixture's exports but a fixture
     * whose assertion body sits in a non-{@code main} export still needs
     * the LuaJIT/JVM legs executed for real. This drive compiles each
     * fixture through the release-owned production CLI, executes the
     * published artifact under the real {@code luajit}/{@code java}
     * toolchain — entry {@code main} once, then the fixture's own
     * zero-arity export (the production lane protocol), so the assertion
     * body actually runs on both targets — and compares the observation
     * against the fixture's Structured Expectation Sidecar: the exact
     * pinned error tuple (code, message, source coordinate, expected,
     * actual) and the exact pinned runtime-ok transcript. A divergent
     * result is a named failure, never an unexecuted pin.
     */
    private static void testV13BoundaryCorpusProductionDrive()
            throws Exception {
        System.out.println("-- the runtime-corpus boundary fixtures through"
            + " the release-owned production CLI on LuaJIT and JVM");
        Path drivers = Files.createTempDirectory("boundary-drivers-");
        try {
            write(drivers, "probe.lua", """
                local surface = dofile(arg[1])
                local ok, err = __dealMain()
                if ok then ok, err = pcall(surface[arg[2]].f) end
                if not ok then
                  if type(err) ~= 'table' or not err[__dealCarrierKeys.d] then error(err, 0) end
                  print('ERR:' .. err.code .. '|' .. err.m .. '|' .. err.o
                    .. '|' .. (err.e or '') .. '|' .. (err.a or ''))
                  os.exit(1)
                end
                """);
            driveBoundaryCorpusFixture("stdlib/table/keys-nonstring-exclusion",
                "test_table_keys_decimal_string_names", false, drivers);
            // ISSUE-0860: a table with a present-null member reports every
            // present string key on both retained production targets too,
            // not only on the JS lane.
            driveBoundaryCorpusFixture("stdlib/table/keys-string-inclusion",
                "test_table_keys_string_inclusion", false, drivers);
            // ISSUE-0860: fromJson parses an object on both retained
            // production targets: an array root of any length, the empty
            // array included, returns language null.
            driveBoundaryCorpusFixture(
                "jsonable/jsonable-fromjson-top-level-scalar",
                "test_fromJson_empty_documents", false, drivers);
            // ISSUE-0860: the nested class arm has the same object-only
            // shape: a nested "[]" — a class field and a class-array
            // element — returns null on both retained targets.
            driveBoundaryCorpusFixture(
                "jsonable/jsonable-fromjson-nested-empty-array",
                "test_nested_empty_array_rejected", false, drivers);
            driveBoundaryCorpusFixture(
                "class-runtime-errors/dynamic-bad-class-array-element-e8001",
                "test_dynamic_bad_class_array_element", true, drivers);
            // The declared (string)->table exit's decode-carrier
            // rejection: a top-level JSON array and null render the
            // shared expected table tuple on the release-owned
            // production CLI artifacts under the real tools.
            driveBoundaryCorpusFixture(
                "stdlib/json/json-parse-top-level-array-table-error",
                "test_json_parse_top_level_array", true, drivers);
            driveBoundaryCorpusFixture(
                "stdlib/json/json-parse-top-level-null-table-error",
                "test_json_parse_top_level_null", true, drivers);
            // The declared (table) parameter of a contextual table read
            // used as a std/json.stringify argument ("Reading table
            // fields" context 4): the two decode carriers and a class
            // instance take the shared expected-table boundary on the
            // release-owned production CLI artifacts under both real
            // tools.
            driveBoundaryCorpusFixture(
                "stdlib/json/json-stringify-contextual-read-carrier",
                "test_json_stringify_contextual_read_carrier", false,
                drivers);
            // ISSUE-0830: private carrier identity on the published
            // artifacts. A table may carry any string key: the
            // carrier-vocabulary table keeps its keys end to end, the
            // forged class/array/function carriers are rejected at
            // every declared carrier boundary, the language null is never
            // a table or an array, and std/json.parse's declared
            // (string)->table exit refuses a scalar root with the shared
            // expected-table tuple.
            driveBoundaryCorpusFixture(
                "runtime/table-carrier-keys-are-data",
                "test_table_carrier_keys_are_data", false, drivers);
            driveBoundaryCorpusFixture(
                "runtime/forged-carrier-identities-rejected",
                "test_forged_carrier_identities", false, drivers);
            driveBoundaryCorpusFixture(
                "runtime/null-is-not-a-table-or-array",
                "test_null_is_not_a_container", false, drivers);
            driveBoundaryCorpusFixture(
                "stdlib/json/json-parse-top-level-scalar-table-error",
                "test_json_parse_top_level_scalar", true, drivers);
        } finally {
            deleteRecursively(drivers);
        }
    }

    /**
     * One boundary fixture on both retained production targets. The
     * sidecar is the pin: its per-backend executed expectation selects the
     * compared outcome shape (the exact transcript bytes and exit code, or
     * the exact diagnostic tuple), so the drive asserts the published
     * sidecar and never a captured value. The fixture's original
     * classification metadata is validated against the production profile
     * before anything is stripped, materialized or compiled (CD3).
     */
    private static void driveBoundaryCorpusFixture(String corpusRel,
            String export, boolean failure, Path drivers) throws Exception {
        Path fixture = REPO_ROOT.resolve("test/conformance/backend-runtime")
            .resolve(corpusRel + ".deal");
        String locator = "backend-runtime/" + corpusRel + ".deal";
        String raw = Files.readString(fixture);
        String source = ConformanceHarnessMetadata.productionCorpusSource(
            raw, locator);
        int strippedHeaders = raw.split("\n", -1).length
            - source.split("\n", -1).length;
        deal.test.conformance.SidecarExpectations.StructuredExpectationSidecar
            sidecar = deal.test.conformance.SidecarExpectations
                .StructuredExpectationSidecar.parse(Files.readString(
                    fixture.resolveSibling(fixture.getFileName().toString()
                        .replace(".deal", ".expect.json"))));
        // The fixture is materialized under the configured root
        // {@code conformance} at the corpus-relative path the sidecar pins
        // (the canonical class identity is
        // {@code @<root>/<relative path>/<Class>}), so the pinned
        // descriptor text and source coordinate are the published
        // artifact's own.
        String entryRel = "conformance/" + locator + "/main.deal";
        String moduleKey = entryRel.substring("conformance/".length(),
            entryRel.length() - ".deal".length()).replace('/', '.');
        for (String backend : List.of("luajit", "jvm")) {
            deal.test.conformance.SidecarExpectations.RuntimeExpectation leg =
                sidecar.expectationFor(backend);
            check(leg instanceof
                    deal.test.conformance.SidecarExpectations
                        .RuntimeExpectation.Executed,
                locator + " [" + backend + "]: the sidecar pins an executed"
                    + " outcome");
            if (!(leg instanceof deal.test.conformance.SidecarExpectations
                    .RuntimeExpectation.Executed executed)) {
                continue;
            }
            checkEq(failure ? "runtime-error" : "runtime-ok", executed.mode(),
                locator + " [" + backend + "]: the pinned mode");
            Path project = Files.createTempDirectory(
                "boundary-corpus-" + export + "-" + backend + "-");
            try {
                write(project, entryRel, source);
                write(project, "deal.json", "{\n  \"languageVersion\": \"1.4\","
                    + "\n  \"moduleRoots\": [\"conformance\"],"
                    + "\n  \"output\": \"out\","
                    + "\n  \"backend\": \"" + backend + "\"\n}\n");
                ProjectOutcome compile = productionCompile(project, entryRel,
                    "out");
                checkEq(0, compile.exitCode(), locator + " [" + backend
                    + "]: the production compile succeeds: "
                    + compile.stderr());
                if (compile.exitCode() != 0) {
                    continue;
                }
                String expected = "";
                if (failure) {
                    deal.test.conformance.SidecarExpectations.ErrorExpectation
                        pin = executed.error();
                    check(pin != null, locator + " [" + backend
                        + "]: the runtime-error pin carries its error");
                    if (pin == null) {
                        continue;
                    }
                    expected = "ERR:" + pin.code() + "|"
                        + pin.message() + "|"
                        + project.resolve(entryRel).toAbsolutePath()
                        + ":" + (pin.line() - strippedHeaders) + ":"
                        + pin.column() + "|"
                        + corpusAtom(pin.expected().orElse(""), locator)
                        + "|" + pin.actual().orElse("") + "\n";
                } else {
                    check(executed.error() == null, locator + " [" + backend
                        + "]: a runtime-ok pin carries no error");
                    checkEq("", new String(executed.stdout(),
                        StandardCharsets.UTF_8), locator + " [" + backend
                        + "]: the pinned runtime-ok stdout is empty");
                    checkEq("", new String(executed.stderr(),
                        StandardCharsets.UTF_8), locator + " [" + backend
                        + "]: the pinned runtime-ok stderr is empty");
                    checkEq(0, executed.exitCode(), locator + " [" + backend
                        + "]: the pinned runtime-ok exit code");
                }
                BoundedProcess.BoundedResult run = boundaryArtifactRun(
                    project, backend, moduleKey, export, drivers);
                checkEqualCapture(locator, backend, run, failure ? 1 : 0, expected);
            } finally {
                deleteRecursively(project);
            }
        }
    }

    /**
     * One published boundary artifact under the real toolchain, driving
     * entry {@code main} once and then the fixture's own zero-arity export
     * through the production entry surface (the {@code DEAL_DEFER_MAIN}
     * chunk and the JVM export surface), so the fixture body actually runs
     * on the artifact the production CLI published. A failing export
     * prints the pinned tuple framing {@code ERR:} and exits 1; a clean
     * export produces no output at all, so the runtime-ok comparison stays
     * byte-exact against the fixture's own transcript.
     */
    public static final class BoundaryCorpusChild {
        public static void main(String[] args) throws Throwable {
            try {
                java.lang.invoke.MethodHandles.publicLookup().findStatic(
                    Class.forName(args[0]), "dealMain",
                    java.lang.invoke.MethodType.methodType(void.class)).invokeExact();
                ((deal.codegen.jvm.JvmRuntime.FunctionValue)
                    deal.codegen.jvm.JvmRuntime.exportSurface(args[1]).read(args[2]))
                    .fn.invoke(new Object[0]);
            } catch (deal.codegen.jvm.JvmRuntime.DealError error) {
                System.out.println("ERR:" + error.code + "|" + error.msg + "|"
                    + error.origin + "|" + (error.expected == null ? "" : error.expected)
                    + "|" + (error.actual == null ? "" : error.actual));
                System.exit(1);
            }
        }
    }

    /**
     * Projects one sidecar-pinned class atom onto the materialized entry
     * module: the fixture's own declarations are materialized at
     * {@code <locator>/main.deal}, and the class identity names the
     * defining file, so the pinned corpus-coordinate atom
     * ({@code @conformance/<locator>/<Class>}) is the entry file's own
     * projected identity ({@code @conformance/<locator>/main.deal/<Class>}).
     * Companion atoms already spell their corpus source path and are
     * untouched.
     */
    private static String corpusAtom(String atom, String locator) {
        String corpusPrefix = "@conformance/" + locator + "/";
        return atom.startsWith(corpusPrefix)
            ? "@conformance/" + locator + "/main.deal/"
                + atom.substring(corpusPrefix.length())
            : atom;
    }

    private static BoundedProcess.BoundedResult boundaryArtifactRun(
            Path project, String backend, String moduleKey, String export,
            Path drivers) throws Exception {
        Path out = project.resolve("out");
        if ("luajit".equals(backend)) {
            // The emitted chunk path is the canonical module path with its
            // separators re-materialized (the shared artifact naming).
            Path artifact = out.resolve(moduleKey.replace('.', '/')
                + ".lua");
            check(Files.isRegularFile(artifact), "the production CLI stages"
                + " the Lua artifact " + artifact);
            if (!Files.isRegularFile(artifact)) {
                return runBounded(project, "luajit", artifact.toString());
            }
            return runBounded(REPO_ROOT, "luajit",
                drivers.resolve("probe.lua").toString(),
                artifact.toAbsolutePath().toString(), export);
        }
        String className = deal.codegen.jvm.JvmNames.classNameFor(moduleKey);
        Path artifact = out.resolve(className + ".java");
        check(Files.isRegularFile(artifact), "the production CLI stages the"
            + " JVM artifact " + artifact);
        if (!Files.isRegularFile(artifact)) {
            return runBounded(project, "java", "-version");
        }
        String classpath = absoluteClasspath();
        BoundedProcess.BoundedResult javac = compileBoundedJvm(project,
            javacCommand(classpath, out, List.of(artifact.toString())));
        check(javac.exitCode() == 0, "the emitted JVM artifacts accept the"
            + " probe: " + javac.stderr());
        if (javac.exitCode() != 0) {
            return javac;
        }
        return runBounded(project, "java", "-cp",
            out + File.pathSeparator + classpath, BoundaryCorpusChild.class.getName(),
            className, moduleKey, export);
    }

    /**
     * The exact artifact comparison of one boundary leg: the pinned exit
     * status, the exact stdout bytes and an empty stderr. A truncated or
     * over-limit capture is refused before any comparison — a retained
     * prefix is never a transcript.
     */
    private static void checkEqualCapture(String locator, String backend,
            BoundedProcess.BoundedResult run, int exitCode,
            String expectedStdout) {
        Optional<String> refusal = transcriptRefusal(run);
        check(refusal.isEmpty(), locator + " [" + backend + "]: "
            + refusal.orElse(""));
        if (refusal.isPresent()) {
            return;
        }
        checkEq(exitCode, run.exitCode(), locator + " [" + backend
            + "]: the artifact exit status: " + boundedOutput(run));
        checkEq(expectedStdout, run.stdout(), locator + " [" + backend
            + "]: the artifact stdout is pin-exact: " + boundedOutput(run));
        checkEq("", run.stderr(), locator + " [" + backend
            + "]: the artifact stderr is empty: " + boundedOutput(run));
    }

    // =========================================================================
    // The complete deployment: missing required content fails the compile
    // =========================================================================

    /**
     * The v1.4 deployment-completeness contract through the public CLI in a
     * child JVM whose distribution surface is exactly the assembled classpath
     * resource root (the process CWD is the project, so the checkout dev
     * fallback cannot repair it): a distribution that provides the runtime but
     * not a spec-listed stdlib implementation is missing required content — the
     * compile fails with the pinned E6000 and the absent output root stays
     * absent — while the complete surface publishes the artifact, its default
     * source-map sidecar, the runtime and all six stdlib copies on both
     * targets.
     */
    private static void testMissingDeploymentContentFails() throws Exception {
        System.out.println("-- deployment completeness: a spec-listed stdlib "
            + "implementation the resolved distribution surface does not provide "
            + "fails the compile with the pinned E6000 and publishes nothing on "
            + "both targets; the complete surface publishes the artifact, its "
            + "default sidecar, the runtime and all six stdlib copies --");

        Path project = Files.createTempDirectory("production-dispatch-deploy-");
        Path partial = Files.createTempDirectory(
            "production-dispatch-deploy-partial-");
        Path complete = Files.createTempDirectory(
            "production-dispatch-deploy-complete-");
        Path emptyHome = Files.createTempDirectory(
            "production-dispatch-deploy-home-");
        try {
            write(project, "deal.json", DEAL_JSON_LUA);
            write(project, "src/main.deal", TRIVIAL_MAIN_SOURCE);
            List<String> stdlibModules = StdlibModuleResolver.SPEC_STDLIB_MODULES;
            for (String stdlibModule : stdlibModules) {
                for (String extension : List.of("lua", "js")) {
                    String relative = stdlibModule + "." + extension;
                    copyRepoFile(Path.of(relative), complete.resolve(relative));
                    if (!relative.equals("std/time.lua")
                            && !relative.equals("std/time.js")) {
                        copyRepoFile(Path.of(relative),
                            partial.resolve(relative));
                    }
                }
            }
            for (String runtime : List.of("deal/runtime.lua", "deal/runtime.js")) {
                copyRepoFile(Path.of(runtime), complete.resolve(runtime));
                copyRepoFile(Path.of(runtime), partial.resolve(runtime));
            }

            for (String backend : List.of("luajit", "js")) {
                String extension = backend.equals("luajit") ? "lua" : "js";
                Path missingOut = project.resolve("out-" + extension + "-missing");
                ProcessOutcome missing = runCliSubprocess(project, partial,
                    emptyHome, "compile",
                    project.resolve("src/main.deal").toAbsolutePath().toString(),
                    "--backend", backend, "--output", missingOut.toString());
                checkEq(1, missing.exitCode(), "the incomplete " + backend
                    + " distribution fails the compile: " + missing.output());
                check(missing.output().contains(
                        "E6000: Stdlib module not found: std/time." + extension),
                    backend + ": the pinned missing-stdlib E6000: " + missing.output());
                check(!Files.exists(missingOut), backend
                    + ": missing content publishes nothing (the absent root stays absent)");
                Path out = project.resolve("build/" + backend);
                ProcessOutcome compiled = runCliSubprocess(project, complete,
                    emptyHome, "compile",
                    project.resolve("src/main.deal").toAbsolutePath().toString(),
                    "--backend", backend, "--output",
                    out.toAbsolutePath().toString());
                check(compiled.exitCode() == 0,
                    "the complete " + backend + " distribution compiles: exit="
                        + compiled.exitCode() + " output=" + compiled.output());
                if (compiled.exitCode() != 0) {
                    continue;
                }
                List<String> published = artifactFiles(out);
                check(published.contains("main." + extension)
                        && published.contains("main.deal.map.json")
                        && published.contains("deal/runtime." + extension),
                    "the " + backend + " artifact set carries the artifact, its "
                        + "default sidecar and the runtime: " + published);
                List<String> deploymentCopies = new ArrayList<>(
                    List.of("deal/runtime." + extension));
                for (String stdlibModule : stdlibModules) {
                    deploymentCopies.add(stdlibModule + "." + extension);
                }
                for (String relative : deploymentCopies) {
                    check(Arrays.equals(
                            Files.readAllBytes(complete.resolve(relative)),
                            Files.readAllBytes(out.resolve(relative))),
                        "the published " + relative + " bytes are the deployed "
                            + "distribution bytes");
                }
                if (backend.equals("luajit")) {
                    verifyPublishedSidecar(project, out, "main.deal.map.json",
                        "src/main.deal", "build/luajit/main.lua", "main.lua",
                        "-- ");
                } else {
                    String json = Files.readString(
                        out.resolve("main.deal.map.json"));
                    check(json.contains("\"version\": 1")
                            && json.contains("\"source\": \"src/main.deal\"")
                            && json.contains(
                                "\"generated\": \"build/js/main.js\""),
                        "the default JavaScript sidecar keeps the spec "
                            + "version and the project-relative paths: " + json);
                }
            }
        } finally {
            deleteRecursively(project);
            deleteRecursively(partial);
            deleteRecursively(complete);
            deleteRecursively(emptyHome);
        }
    }

    /**
     * Runs the public CLI in a child JVM whose classpath is the compiler's
     * own classpath plus the given distribution resource root, in the given
     * working directory, with {@code DEAL_HOME} pinned to an empty directory:
     * the child's resolvable distribution surface is exactly the resource
     * root (no checkout fallback, no inherited home).
     */
    private static ProcessOutcome runCliSubprocess(Path workingDir,
            Path resources, Path dealHome, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("java", "-ea", "-cp",
            absoluteClasspath() + File.pathSeparator + resources));
        command.add("deal.Main");
        command.addAll(List.of(args));
        BoundedProcess.BoundedResult result = BoundedProcess.runCaptured(
            command, workingDir, Map.of("DEAL_HOME", dealHome.toString()));
        return new ProcessOutcome(result.exitCode(),
            result.stdout() + result.stderr());
    }

    /** Copies one checkout resource into an assembled distribution root. */
    private static void copyRepoFile(Path relative, Path target)
            throws IOException {
        Files.createDirectories(target.getParent());
        Files.copy(relative, target,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    // =========================================================================
    // ISSUE-0756: the v1.2 corner-case corpus production drive
    // =========================================================================

    /**
     * The registered v1.2 corner-case corpus under
     * {@code test/conformance/backend-runtime/v12-snippets}. Each fixture
     * carries its Structured Expectation Sidecar with the three-backend
     * uniform runtime-ok expectation; this drive executes the LuaJIT and
     * JVM legs through the release-owned production CLI on the real
     * {@code luajit}/{@code java} processes and compares the exact
     * transcript bytes and exit code through the landed comparator, so a
     * LuaJIT/JVM semantic divergence is a named failure rather than an
     * unexecuted pin. The discovered fixture set is the membership
     * authority: the drive pins no fixed inventory count. Each fixture's
     * original classification metadata is validated against the production
     * profile before anything is stripped, materialized or compiled (CD3):
     * a nonproduction, unknown, duplicate or late declaration is the named
     * {@link ConformanceHarnessMetadata#HARNESS_DEFECT} outcome naming the
     * fixture and the profile with no backend leg — no compile, no pass and
     * no skip. Every child runs through the landed shared bounded-process
     * helper ({@code deal.test.BoundedProcess}): each stream is capped at
     * 1 MiB and drained concurrently to EOF, and a truncated or
     * over-limit observation is refused before any comparison instead
     * of being read as a transcript.
     */
    private static void testV12CornerCaseCorpusProductionDrive()
            throws Exception {
        System.out.println("-- the v1.2 corner-case corpus through the"
            + " release-owned production CLI on LuaJIT and JVM");
        List<V12CorpusRun> runs = runV12CornerCaseCorpus(
            Path.of("test/conformance/backend-runtime/v12-snippets"),
            "backend-runtime/v12-snippets", true);
        check(!runs.isEmpty(), "the corpus discovery finds the registered"
            + " v1.2 corner-case fixtures");
        for (V12CorpusRun run : runs) {
            check(run.defect() == null, run.locator() + ": the raw"
                + " classification metadata is the production profile before"
                + " any compile: "
                + (run.defect() == null ? "accepted" : run.defect()));
            for (V12CorpusLeg leg : run.legs()) {
                check(leg.ok(), run.name() + " [" + leg.backend() + "]: "
                    + leg.detail());
            }
        }
        testV12CornerCaseCorpusRawProfileControls();
    }

    /**
     * One fixture's run through the corner-case production drive: the CD3
     * gate-input refusal — the named {@code HARNESS_DEFECT} naming the
     * fixture and the profile — before any compilation, or the per-backend
     * legs of the accepted fixture.
     */
    private record V12CorpusRun(String name, String locator, String defect,
                                List<V12CorpusLeg> legs) {
    }

    /**
     * One compared backend leg of one accepted fixture: the named
     * infrastructure failure (the production compile, javac, or the shared
     * bounded capture), or the exact transcript comparison's mismatch.
     */
    private record V12CorpusLeg(String backend, String failure,
                                Optional<String> mismatch) {

        boolean ok() {
            return failure == null && mismatch.isEmpty();
        }

        String detail() {
            return failure != null ? failure
                : mismatch.isPresent() ? mismatch.get()
                    : "the pinned transcript matches";
        }
    }

    /**
     * The actual drive consumer: it validates each discovered fixture's
     * original classification metadata against the production profile (CD3)
     * before anything is stripped or compiled, then executes the LuaJIT and
     * JVM legs — and, when {@code jsEntry} is set, the JS production-entry
     * leg of the entry-contract subject — through the release-owned
     * production CLI. A refused declaration never allocates a scratch root,
     * produces no leg, and can therefore neither compile nor pass nor be
     * skipped: it is reported by its locator and its named defect.
     */
    private static List<V12CorpusRun> runV12CornerCaseCorpus(Path snippets,
            String corpusDir, boolean jsEntry) throws Exception {
        List<Path> fixtures;
        try (Stream<Path> walk = Files.walk(snippets)) {
            fixtures = walk.filter(p -> p.toString().endsWith(".deal"))
                .sorted().toList();
        }
        List<V12CorpusRun> runs = new ArrayList<>();
        for (Path fixture : fixtures) {
            String raw = Files.readString(fixture);
            String fileName = fixture.getFileName().toString();
            String name = fileName.replace(".deal", "");
            String locator = corpusDir + "/" + fileName;
            String source;
            try {
                source = ConformanceHarnessMetadata.productionCorpusSource(
                    raw, locator);
            } catch (ConformanceHarnessMetadata.HarnessInputDefectException
                    defect) {
                // CD3: a nonproduction or malformed declaration is gate
                // input error HARNESS_DEFECT naming fixture/profile with no
                // compile, no pass and no skip.
                runs.add(new V12CorpusRun(name, locator, defect.getMessage(),
                    List.of()));
                continue;
            }
            if (raw.contains("// @expected: compile-error E1037")) {
                List<V12CorpusLeg> rejectedLegs = new ArrayList<>();
                for (String backend : List.of("luajit", "jvm")) {
                    Path project = Files.createTempDirectory("removed-power-" + backend);
                    try {
                        write(project, "src/main.deal", source);
                        write(project, "deal.json", "jvm".equals(backend) ? DEAL_JSON_JVM : DEAL_JSON_LUA);
                        ArmCompile rejected = compileWithInvocation(project, "src/main.deal", productionInvocation());
                        List<CompilerDiagnostic> errors = rejected.orchestrator().diagnostics().stream()
                            .filter(d -> "error".equals(d.severity())).toList();
                        boolean correct = !rejected.success() && !errors.isEmpty()
                            && "E1037".equals(errors.get(0).code())
                            && "Expected expression".equals(errors.get(0).message())
                            && errors.get(0).range().startLine() == 4
                            && errors.get(0).range().startColumn() == 8
                            && rejected.orchestrator().semanticEmissionCount() == 0
                            && !Files.exists(project.resolve("out"));
                        rejectedLegs.add(new V12CorpusLeg(backend, correct ? null
                            : "removed exponentiation must reject before publication: " + errors,
                            Optional.empty()));
                    } finally { deleteRecursively(project); }
                }
                runs.add(new V12CorpusRun(name, locator, null, List.copyOf(rejectedLegs)));
                continue;
            }
            deal.test.conformance.SidecarExpectations
                    .StructuredExpectationSidecar sidecar =
                deal.test.conformance.SidecarExpectations
                    .StructuredExpectationSidecar.parse(Files.readString(
                        fixture.resolveSibling(name + ".expect.json")));
            List<V12CorpusLeg> legs = new ArrayList<>();
            boolean entryContract = "exact-main-and-export-output".equals(name);
            for (String backend : jsEntry && entryContract
                    ? List.of("luajit", "jvm", "js") : List.of("luajit", "jvm")) {
                Path project = Files.createTempDirectory(
                    "v12-corner-case-" + name + "-" + backend + "-"
                        + V12_SCRATCH_TOKEN + "-");
                try {
                    write(project, "src/main.deal", source);
                    write(project, "deal.json", switch (backend) {
                        case "js" -> DEAL_JSON_JS;
                        case "jvm" -> DEAL_JSON_JVM;
                        default -> DEAL_JSON_LUA;
                    });
                    ProjectOutcome compile = productionCompile(project,
                        "src/main.deal", "out");
                    if (compile.exitCode() != 0) {
                        legs.add(new V12CorpusLeg(backend,
                            "the production compile succeeds: "
                                + compile.stderr(), Optional.empty()));
                        continue;
                    }
                    Path out = project.resolve("out");
                    BoundedProcess.BoundedResult executed;
                    if ("luajit".equals(backend)) {
                        // The deployed artifact resolves the shared runtime
                        // and stdlib from the checkout root, the same way
                        // every retained real-luajit test executes it.
                        executed = runBounded(REPO_ROOT, "luajit",
                            out.resolve("main.lua").toString());
                    } else if ("js".equals(backend)) {
                        executed = runBounded(out, "node", "main.js");
                    } else {
                        String classpath = absoluteClasspath();
                        List<String> javas;
                        try (Stream<Path> walk = Files.list(out)) {
                            javas = walk.filter(p -> p.toString()
                                    .endsWith(".java"))
                                .map(Path::toString).sorted().toList();
                        }
                        BoundedProcess.BoundedResult javac = compileBoundedJvm(
                            project, javacCommand(classpath, out, javas));
                        Optional<String> javacRefusal =
                            transcriptRefusal(javac);
                        if (javacRefusal.isPresent()) {
                            legs.add(new V12CorpusLeg(backend,
                                "the javac capture stays inside the shared"
                                    + " per-stream bound: "
                                    + javacRefusal.get(), Optional.empty()));
                            continue;
                        }
                        if (javac.exitCode() != 0) {
                            legs.add(new V12CorpusLeg(backend,
                                "javac accepts the emitted artifacts: "
                                    + javac.stderr(), Optional.empty()));
                            continue;
                        }
                        executed = runBounded(project, "java", "-cp",
                            out + File.pathSeparator + classpath, "Main");
                    }
                    // The production-entry leg: the entry contract runs
                    // main() once and never the unrelated exports, so
                    // exact-main-and-export-output keeps its independent
                    // production transcript (main once; alpha/beta never
                    // execute) while every other fixture's sidecar pins
                    // the same transcript on all three surfaces.
                    Optional<String> mismatch;
                    Optional<String> refused = transcriptRefusal(executed);
                    if (refused.isPresent()) {
                        mismatch = refused;
                    } else if (entryContract) {
                        mismatch = mismatchAgainst(name, backend,
                            executed, "main\n", "diagnostic \u03b2\n", 0);
                    } else {
                        mismatch = deal.test.conformance
                            .StructuredExpectationComparator
                            .compare(backend, sidecar.expectationFor(backend),
                                new deal.test.conformance.LaneExecution.Executed(
                                    executed.stdout()
                                        .getBytes(StandardCharsets.UTF_8),
                                    executed.stderr()
                                        .getBytes(StandardCharsets.UTF_8),
                                    executed.exitCode()))
                            .map(deal.test.conformance.GateMismatch::message);
                    }
                    legs.add(new V12CorpusLeg(backend, null, mismatch));
                } finally {
                    deleteRecursively(project);
                }
            }
            runs.add(new V12CorpusRun(name, locator, null, legs));
        }
        return runs;
    }

    /**
     * The gate-input controls of the corner-case production drive (CD3),
     * through the actual drive consumer: a scratch copy of a real fixture
     * whose raw classification metadata declares a nonproduction, unknown,
     * duplicate or late (beyond the 40-line metadata window) profile must
     * come back as the named {@code HARNESS_DEFECT} naming the fixture and
     * the profile, with no backend leg and no owned scratch root — no
     * compile, no pass and no skip — while the pristine copy of the same
     * fixture stays accepted and keeps its two complete production legs.
     */
    private static void testV12CornerCaseCorpusRawProfileControls()
            throws Exception {
        System.out.println("-- the corner-case gate-input controls: a"
            + " nonproduction, unknown, duplicate or late declaration is"
            + " refused before any compile --");
        String fileName = "short-circuit-abrupt.deal";
        String name = fileName.replace(".deal", "");
        Path source = Path.of("test/conformance/backend-runtime",
            "v12-snippets").resolve(fileName);
        String raw = Files.readString(source);
        String sidecar = Files.readString(
            source.resolveSibling(name + ".expect.json"));
        record Injection(String label, String raw, String profile,
                         String kind) {
        }
        List<Injection> injections = List.of(
            new Injection("legacy profile", "// @profile: legacy-safe-number\n"
                + raw, "legacy-safe-number",
                "non-production @profile 'legacy-safe-number'"),
            new Injection("unknown profile",
                "// @profile: no-such-profile\n" + raw, "no-such-profile",
                "non-production @profile 'no-such-profile'"),
            new Injection("duplicate metadata",
                "// @profile: deal-v1.4-int32\n// @profile: deal-v1.4-int32\n"
                    + raw, "deal-v1.4-int32",
                "duplicate corpus classification metadata"),
            new Injection("removed v1.2 profile",
                "// @profile: deal-v1.2-int32\n" + raw, "deal-v1.2-int32",
                "non-production @profile 'deal-v1.2-int32'"),
            new Injection("profile after the 40-line window",
                "\n".repeat(40) + "// @profile: legacy-safe-number\n" + raw,
                "legacy-safe-number",
                "non-production @profile 'legacy-safe-number'"));
        Path root = Files.createTempDirectory("v12-profile-control-");
        try {
            Path snippets = root.resolve("v12-snippets");
            Files.createDirectories(snippets);
            Files.writeString(snippets.resolve(name + ".expect.json"),
                sidecar, StandardCharsets.UTF_8);
            Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
            for (Injection injection : injections) {
                Files.writeString(snippets.resolve(fileName),
                    injection.raw(), StandardCharsets.UTF_8);
                Set<Path> before = v12CornerCaseScratchRoots(tmp);
                List<V12CorpusRun> runs = runV12CornerCaseCorpus(snippets,
                    "v12-snippets", false);
                checkEq(1, runs.size(), "the " + injection.label()
                    + " scratch corpus carries the one fixture");
                V12CorpusRun run = runs.get(0);
                check(run.defect() != null
                        && run.defect().contains(
                            ConformanceHarnessMetadata.HARNESS_DEFECT)
                        && run.defect().contains(fileName)
                        && run.defect().contains(injection.profile())
                        && run.defect().contains(injection.kind()),
                    "the " + injection.label() + " declaration is the named "
                        + ConformanceHarnessMetadata.HARNESS_DEFECT
                        + " naming the fixture and the profile: "
                        + run.defect());
                check(run.legs().isEmpty(), "the " + injection.label()
                    + " declaration records no backend leg: no compile, no"
                    + " pass and no skip");
                checkEq(before, v12CornerCaseScratchRoots(tmp), "the "
                    + injection.label() + " declaration allocates no"
                    + " production scratch root");
            }
            // The pristine copy through the same consumer: the acceptance
            // control that the refusal above is the declaration and not the
            // scratch path.
            Files.writeString(snippets.resolve(fileName), raw,
                StandardCharsets.UTF_8);
            List<V12CorpusRun> accepted = runV12CornerCaseCorpus(snippets,
                "v12-snippets", false);
            checkEq(1, accepted.size(), "the pristine scratch corpus carries"
                + " the one fixture");
            V12CorpusRun run = accepted.get(0);
            check(run.defect() == null, "the pristine declaration is accepted"
                + " before any compile: "
                + (run.defect() == null ? "accepted" : run.defect()));
            checkEq(2, run.legs().size(), "the pristine fixture keeps its"
                + " LuaJIT and JVM production legs");
            for (V12CorpusLeg leg : run.legs()) {
                check(leg.ok(), "the pristine fixture [" + leg.backend()
                    + "]: " + leg.detail());
            }
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * This JVM's owned {@code v12-corner-case-*} scratch roots in a
     * directory. The name carries {@link #V12_SCRATCH_TOKEN}, so a
     * concurrent test process in another checkout (whose roots share
     * {@code java.io.tmpdir}) is never mistaken for an allocation of this
     * run.
     */
    private static Set<Path> v12CornerCaseScratchRoots(Path parent)
            throws Exception {
        String marker = "-" + V12_SCRATCH_TOKEN + "-";
        try (Stream<Path> listing = Files.list(parent)) {
            return listing.filter(p -> {
                    String entry = p.getFileName().toString();
                    return entry.startsWith("v12-corner-case-")
                        && entry.contains(marker);
                })
                .collect(java.util.stream.Collectors.toSet());
        }
    }

    /**
     * The capture controls of the corner-case production drive, all through
     * the landed shared bounded-process helper
     * ({@code deal.test.BoundedProcess}) and its per-stream bound: the
     * boundary matrix at the 1 MiB cap in both stream positions — the
     * cap-minus-one and the exact-cap floods complete byte-exactly with no
     * truncation flag, and the cap-plus-one flood is retained only as the
     * bounded prefix plus the helper's truncation marker and must be
     * refused by name before any transcript comparison, even though its
     * retained streams still equal the expected bytes. The pressure
     * child's flood far beyond the pipe
     * capacity proves the concurrent capped drainage: a sequential
     * read-to-EOF shape would deadlock on the full stderr pipe. The
     * deadline is applied while the child runs, so a sleeping child ends
     * with the helper's named hard timeout instead of hanging the suite. A
     * child whose surviving descendant keeps the inherited output write
     * ends open past the deadline is the incomplete-drainage shape: the
     * helper's named hard timeout is the outcome (never a partial
     * transcript), and the owned release kills the pipe-holding descendant
     * in both shapes — the descendant the bounded wait can observe and the
     * fast shell child that exits before any observation — and ends both
     * capture readers before returning. A short-lived descendant that
     * writes and closes before the deadline is instead a complete capture:
     * the helper's kernel-EOF transport reads the descendant's later
     * output, so only the full transcript is accepted and the prefix-only
     * observation is rejected; the shape is repeated to demonstrate it is
     * not a timing-dependent prefix.
     */
    private static void testV12SplitCaptureControls() throws Exception {
        System.out.println("-- the split-capture controls: the shared"
            + " per-stream bound, the bounded deadline, and the incomplete"
            + " drainage");
        Path scratch = Files.createTempDirectory("v12-split-capture-");
        try {
            int cap = BoundedProcess.STREAM_CAP_BYTES;
            for (boolean stdout : List.of(false, true)) {
                String mode = stdout ? "stdout-pressure" : "pressure";
                String marker = stdout ? "STDOUT-PRESSURE-OK\n" : "PRESSURE-OK\n";
                byte value = (byte) (stdout ? 'y' : 'x');
                for (int bytes : List.of(cap - 1, cap, cap + 1)) {
                    String label = mode + " " + bytes;
                    BoundedProcess.BoundedResult run = runBounded(120_000L, scratch,
                        "java", "-cp", absoluteClasspath(), SplitCaptureChild.class.getName(),
                        mode, Integer.toString(bytes));
                    String flood = stdout ? run.stdout() : run.stderr();
                    boolean overflow = bytes > cap;
                    checkEq(0, run.exitCode(), label + " exits without pipe deadlock");
                    checkEq(marker, stdout ? run.stderr() : run.stdout(), label + " separate stream");
                    checkEq(stdout && overflow, run.stdoutTruncated(), label + " stdout truncation");
                    checkEq(!stdout && overflow, run.stderrTruncated(), label + " stderr truncation");
                    checkEq(overflow ? cap + BoundedProcess.TRUNCATION_MARKER.length() : bytes,
                        byteLength(flood), label + " captured length");
                    check(allBytes(overflow ? flood.substring(0, cap) : flood, value),
                        label + " every retained flood byte survives");
                    Optional<String> refusal = transcriptRefusal(run);
                    Optional<String> match = mismatchAgainst(label, "luajit", run,
                        run.stdout(), run.stderr(), 0);
                    if (overflow) {
                        check(flood.endsWith(BoundedProcess.TRUNCATION_MARKER), label + " truncation marker");
                        check(refusal.isPresent() && refusal.get().contains("truncated")
                            && refusal.get().contains(Integer.toString(cap)), label + " named refusal");
                        check(match.isPresent() && match.get().contains("truncated"),
                            label + " refuses even exactly matching retained prefix");
                    } else {
                        check(refusal.isEmpty() && match.isEmpty(), label + " exact transcript accepted");
                        if (bytes == cap) {
                            check(mismatchAgainst(label, "luajit", run,
                                run.stdout() + (stdout ? "y" : ""), run.stderr() + (stdout ? "" : "x"),
                                0).isPresent(), label + " extra expected byte refused");
                            check(mismatchAgainst(label, "luajit", run, run.stdout(), run.stderr(),
                                1).isPresent(), label + " wrong expected exit refused");
                        }
                    }
                }
            }

            // The deadline control: the helper's one deadline covers the
            // child wait and both streams, so the sleeping child is
            // terminated and reaped with the named hard token instead of
            // hanging the suite.
            long sleepStart = System.nanoTime();
            boolean sleepTimedOut = false;
            try {
                runBounded(2_000L, scratch, "java", "-cp",
                    absoluteClasspath(),
                    SplitCaptureChild.class.getName(), "sleep", "60000");
            } catch (BoundedProcess.BoundedProcessTimeoutException timeout) {
                sleepTimedOut = true;
                checkEq("BOUNDED_PROCESS_TIMEOUT java", timeout.getMessage(),
                    "the sleeping child ends with the shared helper's named"
                        + " timeout token");
            }
            long sleepMillis =
                (System.nanoTime() - sleepStart) / 1_000_000L;
            check(sleepTimedOut, "the sleeping child is a hard named"
                + " timeout, never a hang");
            check(sleepMillis < 30_000L, "the timeout path returns promptly"
                + " after the deadline: " + sleepMillis + " ms");
            check(liveCaptureReaders().isEmpty(), "no capture reader"
                + " survives the timed-out child's release: "
                + liveCaptureReaders());

            // The inherited-pipe/late-output control: the direct child
            // prints its marker, leaves a descendant holding the stdout
            // pipe for a minute (far past the control's deadline), records
            // the descendant's pid, and exits 0. Its exit cannot make the
            // capture complete — the readers cannot reach EOF inside the
            // deadline — so the run must end with the helper's named hard
            // timeout, never with the successful prefix as a transcript.
            // The owned release must terminate the recorded descendant and
            // end both capture readers before returning.
            Path latePid = scratch.resolve("late-descendant.pid");
            long lateStart = System.nanoTime();
            boolean lateRefused = false;
            try {
                runBounded(3_000L, scratch, "java", "-cp",
                    absoluteClasspath(),
                    SplitCaptureChild.class.getName(), "late", "60000",
                    latePid.toString());
            } catch (BoundedProcess.BoundedProcessTimeoutException timeout) {
                lateRefused = true;
                check(timeout.getMessage() != null
                        && timeout.getMessage()
                            .startsWith("BOUNDED_PROCESS_TIMEOUT"),
                    "the incomplete drainage names the shared helper's hard"
                        + " timeout: " + timeout.getMessage());
            }
            long lateMillis = (System.nanoTime() - lateStart) / 1_000_000L;
            check(lateRefused, "a child whose descendant keeps the stdout"
                + " pipe open past the deadline is a non-passing"
                + " incomplete capture, never a partial transcript");
            check(lateMillis < 30_000L, "the late control returns inside a"
                + " bounded wall window: " + lateMillis + " ms");
            long lateDescendant = readPid(latePid);
            check(lateDescendant > 0 && deadWithin(lateDescendant, 5_000L),
                "the pipe-holding descendant is terminated by the owned"
                    + " release: pid=" + lateDescendant + " alive="
                    + processAlive(lateDescendant));
            check(liveCaptureReaders().isEmpty(), "no capture reader"
                + " survives the incomplete-drainage release: "
                + liveCaptureReaders());

            // The immediate-parent-exit shape, repeated: the direct child
            // is a fast shell that starts a pipe-holding descendant and
            // exits before the bounded wait can observe the tree, so only
            // the owned process group keeps the descendant addressable and
            // only the readers' kernel EOF — never the direct child's exit
            // — can decide that the capture is complete. With a descendant
            // that outlives the deadline, each round must end with the
            // named hard timeout, never with the successful prefix as a
            // transcript: the shape is repeated so a timing-dependent
            // pipe-EOF outcome cannot pass as a transcript. The release
            // must kill the descendant and end both readers inside the
            // bounded window.
            for (int round = 0; round < 2; round++) {
                Path shellPid = scratch.resolve(
                    "late-shell-descendant-" + round + ".pid");
                long shellStart = System.nanoTime();
                boolean shellRefused = false;
                try {
                    runBounded(3_000L, scratch, "sh", "-c",
                        "(sleep 10; echo LATE_NOISE) & echo $! > \"$1\";"
                            + " echo EXPECTED",
                        "v12-late-shell", shellPid.toString());
                } catch (BoundedProcess.BoundedProcessTimeoutException timeout) {
                    shellRefused = true;
                }
                long shellMillis =
                    (System.nanoTime() - shellStart) / 1_000_000L;
                check(shellRefused, "the immediate-parent-exit child is a"
                    + " non-passing incomplete capture, never a partial"
                    + " transcript (round " + round + ")");
                long shellDescendant = readPid(shellPid);
                check(shellDescendant > 0
                        && deadWithin(shellDescendant, 5_000L),
                    "the unsampled pipe-holding descendant is terminated"
                        + " by the owned process group (round " + round
                        + "): pid=" + shellDescendant
                        + " alive=" + processAlive(shellDescendant));
                check(liveCaptureReaders().isEmpty(), "no capture reader"
                    + " survives the immediate-parent-exit release (round "
                    + round + "): " + liveCaptureReaders());
                check(shellMillis < 7_000L, "the immediate-parent-exit"
                    + " release returns inside a bounded wall window"
                    + " (round " + round + "): " + shellMillis + " ms");
            }

            // The short-lived pipe-holder shape, repeated: the direct child
            // prints its marker and exits while a descendant still holds
            // the inherited write ends, and only the descendant's later
            // output closes them. The kernel-EOF capture must return the
            // complete transcript — never the prefix-only result a
            // direct-child pipe reclamation would produce — so the exact
            // comparison accepts the full transcript and rejects the prefix
            // expectation. The review's just-after-exit write and a later
            // short-lived write are both covered, and the shape is repeated
            // so a timing-dependent prefix-only observation cannot pass as a
            // capture.
            int shortLivedRound = 0;
            for (String delay : List.of("0.001", "0.05")) {
                for (int round = 0; round < 2; round++, shortLivedRound++) {
                    BoundedProcess.BoundedResult shortLived;
                    try {
                        shortLived = runBounded(10_000L, scratch, "sh", "-c",
                            "(sleep " + delay + "; echo LATE_NOISE) & echo EXPECTED",
                            "v12-short-lived");
                    } catch (BoundedProcess.BoundedProcessTimeoutException timeout) {
                        check(false, "the short-lived pipe-holder (" + delay
                            + " s) must complete inside its deadline, never"
                            + " return an incomplete infrastructure result"
                            + " (round " + shortLivedRound + "): "
                            + timeout.getMessage());
                        continue;
                    }
                    checkEq("EXPECTED\nLATE_NOISE\n", shortLived.stdout(),
                        "the short-lived pipe-holder's descendant output is"
                            + " captured completely, never the successful prefix"
                            + " (" + delay + " s, round " + shortLivedRound
                            + ")");
                    checkEq("", shortLived.stderr(), "the short-lived"
                        + " pipe-holder keeps stderr its own empty stream"
                        + " (" + delay + " s, round " + shortLivedRound + ")");
                    checkEq(0, shortLived.exitCode(), "the short-lived"
                        + " pipe-holder preserves the child's exit status"
                        + " (" + delay + " s, round " + shortLivedRound + ")");
                    check(!shortLived.stdoutTruncated()
                            && !shortLived.stderrTruncated(), "the short-lived"
                        + " pipe-holder stays inside the shared per-stream bound"
                        + " (" + delay + " s, round " + shortLivedRound + ")");
                    Optional<String> completeAccepted = mismatchAgainst(
                        "short-lived-descendant", "luajit", shortLived,
                        "EXPECTED\nLATE_NOISE\n", "", 0);
                    check(completeAccepted.isEmpty(), "the complete"
                        + " short-lived transcript is accepted by the exact"
                        + " comparison (" + delay + " s, round "
                        + shortLivedRound + "): "
                        + completeAccepted.orElse("match"));
                    Optional<String> prefixRefused = mismatchAgainst(
                        "short-lived-descendant", "luajit", shortLived,
                        "EXPECTED\n", "", 0);
                    check(prefixRefused.isPresent(), "the prefix-only"
                        + " expectation is rejected once the descendant output"
                        + " is captured (" + delay + " s, round "
                        + shortLivedRound + ")");
                }
            }
        } finally {
            deleteRecursively(scratch);
        }
    }

    /** Reads one recorded control-child pid; 0 when unreadable. */
    private static long readPid(Path pidFile) {
        try {
            return Long.parseLong(Files.readString(pidFile,
                StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException unreadable) {
            return 0L;
        }
    }

    /** Whether one recorded pid is still a live process. */
    private static boolean processAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive)
            .orElse(false);
    }

    /** Waits a bounded window for one recorded pid to disappear. */
    private static boolean deadWithin(long pid, long budgetMillis) {
        long deadlineNanos = System.nanoTime()
            + java.util.concurrent.TimeUnit.MILLISECONDS
                .toNanos(budgetMillis);
        while (processAlive(pid) && System.nanoTime() < deadlineNanos) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interruptedWait) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return !processAlive(pid);
    }

    /**
     * The live readers of the shared bounded-process capture, by their
     * fixed thread names. The helper joins both drains to EOF on every
     * path, so a returning observation never leaves one running.
     */
    private static List<String> liveCaptureReaders() {
        List<String> live = new java.util.ArrayList<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive()
                    && ("bounded-process-stdout".equals(thread.getName())
                        || "bounded-process-stderr".equals(
                            thread.getName()))) {
                live.add(thread.getName());
            }
        }
        return live;
    }

    /**
     * The split-capture control child: {@code pressure <bytes>} floods
     * stderr with the given byte count and then writes one stdout marker;
     * {@code stdout-pressure <bytes>} floods stdout with the given byte
     * count and then writes one stderr marker (the stdout-position twin of
     * the boundary matrix);
     * {@code sleep <millis>} stays alive without any output;
     * {@code late <millis> [pid-file]} writes its stdout marker, leaves a
     * descendant (the same class, {@code late-sleep <millis>}) holding the
     * inherited stdout pipe for the given time, records the descendant's
     * pid when a file is given, and exits — the incomplete-drainage shape
     * of the split-capture controls. It is a harness child of the
     * controls, never a product surface.
     */
    public static final class SplitCaptureChild {

        public static void main(String[] args) throws Exception {
            if ("pressure".equals(args[0]) || "stdout-pressure".equals(args[0])) {
                boolean stdout = "stdout-pressure".equals(args[0]);
                PrintStream flood = stdout ? System.out : System.err;
                PrintStream marker = stdout ? System.err : System.out;
                int remaining = Integer.parseInt(args[1]);
                byte[] block = new byte[1024];
                Arrays.fill(block, (byte) (stdout ? 'y' : 'x'));
                while (remaining > 0) {
                    int chunk = Math.min(block.length, remaining);
                    flood.write(block, 0, chunk);
                    remaining -= chunk;
                }
                flood.flush();
                marker.print(stdout ? "STDOUT-PRESSURE-OK\n" : "PRESSURE-OK\n");
                marker.flush();
                return;
            }
            if ("late".equals(args[0])) {
                System.out.print("EXPECTED\n");
                System.out.flush();
                ProcessBuilder descendant = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java")
                        .toString(),
                    "-cp", System.getProperty("java.class.path"),
                    SplitCaptureChild.class.getName(), "late-sleep", args[1]);
                // The descendant inherits this JVM's stdio — the pipe the
                // harness drains — and outlives this direct child.
                descendant.inheritIO();
                Process spawned = descendant.start();
                if (args.length > 2) {
                    Files.writeString(Path.of(args[2]),
                        Long.toString(spawned.pid()) + "\n",
                        StandardCharsets.UTF_8);
                }
                return;
            }
            Thread.sleep(Long.parseLong(args[1]));
            if ("late-sleep".equals(args[0])) {
                System.out.print("LATE_NOISE\n");
                System.out.flush();
            }
        }
    }

    /** The checkout root every retained real-process drive runs from. */
    private static final Path REPO_ROOT = Path.of("").toAbsolutePath()
        .normalize();

    /** Javac over the emitted artifacts with the process classpath. */
    private static String[] javacCommand(String classpath, Path out,
            List<String> javas) {
        List<String> command = new java.util.ArrayList<>();
        command.add("javac");
        command.add("-encoding");
        command.add("UTF-8");
        command.add("-cp");
        command.add(out + File.pathSeparator + classpath);
        command.add("-d");
        command.add(out.toString());
        command.addAll(javas);
        return command.toArray(String[]::new);
    }

    /**
     * One production child through the landed shared bounded-process
     * helper's canonical budget — the same entry point every release-run
     * child site uses: the per-stream 1 MiB cap, both streams drained
     * concurrently to EOF, one deadline covering the child and both
     * streams, the owned process-group release on the non-normal paths, and
     * the named hard timeout token. The helper's streams stay separate for
     * the exact transcript comparisons.
     */
    private static BoundedProcess.BoundedResult runBounded(Path directory,
            String... command) throws Exception {
        return BoundedProcess.runCaptured(List.of(command), directory);
    }

    /**
     * The control-child variant of the same helper with a reduced explicit
     * budget (the harness checks bound their own children); the drive
     * itself always uses the canonical default above.
     */
    private static BoundedProcess.BoundedResult runBounded(long budgetMillis,
            Path directory, String... command) throws Exception {
        return BoundedProcess.runCaptured(List.of(command), directory,
            Map.of(), budgetMillis);
    }

    /**
     * The named refusal of one captured observation that must never be
     * compared as a transcript: a stream that passed the shared helper's
     * 1 MiB bound is retained with the helper's truncation marker for
     * diagnostics only, and the exact comparison refuses it — a retained
     * prefix or marker is never a successful exact transcript.
     */
    private static Optional<String> transcriptRefusal(
            BoundedProcess.BoundedResult captured) {
        if (!captured.stdoutTruncated() && !captured.stderrTruncated()) {
            return Optional.empty();
        }
        return Optional.of("the captured stream passed the shared helper's "
            + BoundedProcess.STREAM_CAP_BYTES + "-byte bound (stdout "
            + captured.stdoutTruncated() + ", stderr "
            + captured.stderrTruncated() + "): a truncated observation is"
            + " never compared as a transcript");
    }

    /** The byte length of one captured stream. */
    private static int byteLength(String captured) {
        return captured.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Whether every byte of one capture equals the expected filler byte. */
    private static boolean allBytes(String captured, byte expected) {
        for (byte actual : captured.getBytes(StandardCharsets.UTF_8)) {
            if (actual != expected) {
                return false;
            }
        }
        return true;
    }

    /** The exact expected transcript of one production-entry execution. */
    private static Optional<String> mismatchAgainst(String name,
            String backend, BoundedProcess.BoundedResult executed,
            String expectedStdout, String expectedStderr, int expectedExit) {
        Optional<String> refused = transcriptRefusal(executed);
        if (refused.isPresent()) {
            return Optional.of("the bounded run is not a complete capture"
                + " (" + name + "/" + backend + "): " + refused.get());
        }
        if (executed.exitCode() != expectedExit) {
            return Optional.of("exitCode must be " + expectedExit + ", got "
                + executed.exitCode());
        }
        if (!expectedStdout.equals(executed.stdout())) {
            return Optional.of("stdout must be the production entry"
                + " transcript (" + name + "/" + backend + ")");
        }
        if (!expectedStderr.equals(executed.stderr())) {
            return Optional.of("stderr must be the production entry"
                + " diagnostic (" + name + "/" + backend + ")");
        }
        return Optional.empty();
    }

    private static void testProducedProjectAccessor(Path project,
            ArmCompile production) throws Exception {
        System.out.println("-- the produced-project accessor: the production "
            + "arm exposes the project it emitted; the reference is "
            + "read-only and recorded at most once --");

        // (1) A successful production compile exposes its own project: the
        // entry artifact is the project's entry artifact and the identity
        // guard passes it (the audit's subject is non-empty).
        ExecutableLoweredProject produced = null;
        {
            check(production.success(), "the production compile succeeds: "
                + production.orchestrator().diagnostics());
            if (!production.success()) {
                return;
            }
            produced = production.orchestrator().producedProject();
            check(produced != null, "the production compile exposes the "
                + "project it lowered and emitted");
            if (produced == null) {
                return;
            }
            checkEq(SemanticProfile.DEAL_V1_4_BINARY64,
                produced.semanticProfile(),
                "the produced project is the sole v1.4 profile");
            check(produced.modules().containsKey(produced.entryModule()),
                "the produced project carries its entry module");
            check(containsReturnOp(produced),
                "the produced project carries RETURN ops (the identity "
                    + "audit's subject is non-empty)");
            String artifactRelativePath = produced.entryModule().path()
                .replace('.', '/') + ".lua";
            Path artifact = project.resolve("out")
                .resolve(artifactRelativePath);
            check(Files.isRegularFile(artifact),
                "the one staged artifact is the produced project's entry "
                    + "artifact: " + artifact);
            if (Files.isRegularFile(artifact)) {
                String chunk = Files.readString(artifact,
                    StandardCharsets.UTF_8);
                check(chunk.contains("__exportSurfaces[\""
                        + produced.entryModule().path() + "\"]"),
                    "the staged artifact carries the produced project's "
                        + "entry surface");
            }
            List<ProducedProjectIdentityAudit.Violation> violations =
                ProducedProjectIdentityAudit.audit(produced);
            check(violations.isEmpty(),
                "the produced project passes the dangling-identity audit: "
                    + violations);
        }

        // (2) The JS arm is the same one production arm: the public
        // Backend.JS dispatch runs the release-owned project emission, so
        // the compile records the one project it lowered and emitted and
        // stages the semantic renderer's per-module artifact set. No
        // alternate AST emitter exists and no arm re-lowers.
        Path js = Files.createTempDirectory("production-dispatch-produced-js-");
        try {
            write(js, "deal.json", """
                {
                  "languageVersion": "1.4",
                  "moduleRoots": ["src"],
                  "output": "out",
                  "backend": "js"
                }
                """);
            write(js, "src/lib.deal", LIB_SOURCE);
            write(js, "src/main.deal", SYNC_CALL_SOURCE);
            ArmCompile arm = compileWithInvocation(js, "src/main.deal",
                productionInvocation());
            check(arm.success(), "the JS production compile succeeds: "
                + arm.orchestrator().diagnostics());
            ExecutableLoweredProject jsProduced =
                arm.orchestrator().producedProject();
            check(jsProduced != null, "the JS arm records the project it "
                + "lowered and emitted");
            checkEq(1, arm.orchestrator().semanticEmissionCount(),
                "the JS arm runs the one production arm exactly once");
            if (jsProduced != null) {
                Path jsArtifact = js.resolve("out").resolve(
                    jsProduced.entryModule().path().replace('.', '/')
                        + ".js");
                check(Files.isRegularFile(jsArtifact),
                    "the JS arm stages the produced project's entry "
                        + "artifact: " + jsArtifact);
                if (Files.isRegularFile(jsArtifact)) {
                    String jsSource = Files.readString(jsArtifact,
                        StandardCharsets.UTF_8);
                    check(jsSource.contains("$rt.initModule(")
                            && jsSource.contains("$rt.publishModule("),
                        "the staged JS artifact is the semantic renderer's "
                            + "module artifact");
                }
                List<ProducedProjectIdentityAudit.Violation> jsViolations =
                    ProducedProjectIdentityAudit.audit(jsProduced);
                check(jsViolations.isEmpty(),
                    "the JS produced project passes the dangling-identity "
                        + "audit: " + jsViolations);
            }
        } finally {
            deleteRecursively(js);
        }

        // (3) A compile that fails before phase 4 records none: there is no
        // project to expose, so the accessor can never surface a
        // half-lowered or rejected project.
        CompilerDiagnostic failedDiagnostic = null;
        Path broken = Files.createTempDirectory(
            "production-dispatch-produced-broken-");
        try {
            write(broken, "deal.json", DEAL_JSON_LUA);
            write(broken, "src/main.deal",
                "export function main(: null { return null }\n");
            ArmCompile arm = compileWithInvocation(broken, "src/main.deal",
                productionInvocation());
            check(!arm.success(), "the parse-error fixture fails the "
                + "compile");
            check(arm.orchestrator().producedProject() == null,
                "a compile that runs no production arm records no project");
            checkEq(0, arm.orchestrator().semanticEmissionCount(),
                "no project emission is recorded for the failing compile");
            failedDiagnostic = arm.orchestrator().diagnostics().stream()
                .findFirst().orElse(null);
        } finally {
            deleteRecursively(broken);
        }

        // (4) The result contract's fail-closed halves: a failed run
        // carrying a produced project and an emitted run carrying none are
        // both refused, so the accessor can never be observed for a run that
        // produced nothing.
        if (produced != null && failedDiagnostic != null) {
            boolean failedRefused = false;
            try {
                new ProductionProjectEmission.Result(
                    ProductionProjectEmission.Outcome.FAILED,
                    List.of(failedDiagnostic), produced, null);
            } catch (IllegalArgumentException expected) {
                failedRefused = true;
            }
            check(failedRefused, "a failed production run refuses a "
                + "produced-project reference");
        }
        boolean emittedRefused = false;
        try {
            new ProductionProjectEmission.Result(
                ProductionProjectEmission.Outcome.EMITTED,
                List.of(), null, "main.lua");
        } catch (IllegalArgumentException expected) {
            emittedRefused = true;
        }
        check(emittedRefused, "an emitted production run refuses a missing "
            + "produced-project reference");
    }

    /** Whether the project closure carries at least one RETURN op. */
    private static boolean containsReturnOp(ExecutableLoweredProject project) {
        return project.modules().values().stream().anyMatch(unit ->
            unit.ops().stream().anyMatch(op ->
                op.kind() == SemanticOpKind.RETURN));
    }

    // =========================================================================
    // Main
    // =========================================================================

    // =========================================================================
    // The retained corner-case subjects (ISSUE-0815 subjects 1-11)
    // =========================================================================

    /** The four-module diamond's entry (subject 4). */
    private static final String DIAMOND_MAIN = """
        import * as c from "std/console";
        import * as left from "./left";
        import * as right from "./right";
        export function main(): null {
          if(left.read() !== 7 || right.read() !== 7) {throw {code:"",message:"diamond value"};}
          c.log("entry-main"); return null;
        }
        """;

    /** The diamond's left arm: constructs the shared seed class. */
    private static final String DIAMOND_LEFT = """
        import * as c from "std/console";
        import * as seed from "./seed";
        function value(): number {c.log("left-default"); return 0;}
        export class Node {value:number;}
        export function read(): number {let x:seed.Seed={value: seed.value()}; return x.value;}
        export function main(): null {c.log("forbidden-left-main"); return null;}
        """;

    /** The diamond's right arm: constructs the shared seed class. */
    private static final String DIAMOND_RIGHT = DIAMOND_LEFT.replace("left", "right");

    /** The diamond's seed: the default evaluated once per construction. */
    private static final String DIAMOND_SEED = """
        import * as c from "std/console";
        export function value(): number {c.log("seed-default"); return 7;}
        export class Seed {value:number;}
        export function main(): null {c.log("forbidden-seed-main"); return null;}
        """;

    /**
     * The retained four-module diamond (ISSUE-0815 subject 4) on all
     * three production backends: the selected entry is {@code main.deal}
     * only, and the published artifact prints exactly the two seed
     * defaults (one per construction, never at class registration) and
     * the entry marker — no companion main, no left/right default.
     */
    private static void testRetainedCornerCaseDiamond() throws Exception {
        System.out.println("-- the retained four-module diamond selects the"
            + " entry and runs no companion main on every target --");
        for (String backend : List.of("luajit", "jvm", "js")) {
            Path project = Files.createTempDirectory("production-diamond-");
            try {
                write(project, "deal.json", retainedManifest(backend));
                write(project, "src/main.deal", DIAMOND_MAIN);
                write(project, "src/left.deal", DIAMOND_LEFT);
                write(project, "src/right.deal", DIAMOND_RIGHT);
                write(project, "src/seed.deal", DIAMOND_SEED);
                if (compileRetainedSubject(project, backend + ": diamond", true)) {
                    checkRetainedSubjectRun(project, backend, "diamond",
                        "seed-default\nseed-default\nentry-main\n");
                }
            } finally {
                deleteRecursively(project);
            }
        }
    }

    /** The copied local-class original (subject 3, exact raw body). */
    private static final String LOCAL_CLASS_ORIGINAL = """
        import * as c from "std/console";
        function shared(xs: number[]): () => number[] {return function(): number[] {return xs;};}
        export function main(): null {
         let original: number[] = [1]; let provider: () => number[] = shared(original); let calls: number = 0;
         function provide(): number[] {calls = calls + 1; return provider();}
         class Box {xs: number[];}
         let a: Box = {}; let b: Box = {};
         a.xs[0] = 9;
         if(calls !== 2 || a.xs !== original || b.xs !== original || b.xs[0] !== 9) {throw {message:"call default copied"};}
         c.log("default-call-shared-reference"); return null;
        }
        """;

    /** Subject 9 (exact raw body). */
    private static final String TABLE_CONDITION_CONTEXT_REJECT =
        "export function f(): boolean {let t: table = {flag:true};"
            + " while(t.flag) {return true;} return false;}\n";

    /** Subject 10 (exact raw body). */
    private static final String TABLE_LOGICAL_CONTEXT_REJECT =
        "export function f(): boolean {let t: table = {flag:true};"
            + " return t.flag && true;}\n";

    /** Subject 11 (exact raw body). */
    private static final String TRY_RETURN_CATCH_FALLTHROUGH_REJECT =
        "export function f(): number {try {return 1;} catch(e) {} return 2;}\n";

    /** The quiet entry importing one raw negative subject. */
    private static final String QUIET_SUBJECT_ENTRY = """
        import * as s from "./subject";
        export function main(): null {return null;}
        """;

    /** The legal throwing-try/fallthrough-catch control (subject 11's). */
    private static final String LEGAL_THROWING_TRY_CONTROL = """
        import * as c from "std/console";
        export function f(): null {try {throw {code:"",message:"x"};} catch(e) {} return null;}
        export function main(): null {f(); c.log("legal-throwing-try"); return null;}
        """;

    /**
     * The nested-literal table read of the closed direct contextual-read
     * admission (docs/spec-v1.3.md §Reading table fields): an array
     * literal element is not the position's own expression.
     */
    private static final String NESTED_ARRAY_LITERAL_READ_REJECT =
        "export function f(): number {let t: table = {n:1};"
            + " let xs: number[] = [t.n]; return xs[0];}\n";

    /** The same admission's class literal field value. */
    private static final String NESTED_CLASS_LITERAL_READ_REJECT =
        "class Box {n: number;}\n"
            + "export function f(): number {let t: table = {n:1};"
            + " let b: Box = {n:t.n}; return b.n;}\n";

    /** The same admission's template interpolation part. */
    private static final String NESTED_TEMPLATE_READ_REJECT =
        "export function f(): string {let t: table = {s:\"a\"};"
            + " let v: string = `v${t.s}`; return v;}\n";

    /** The computed form of the nested array literal element. */
    private static final String NESTED_COMPUTED_LITERAL_READ_REJECT =
        "export function f(): number {let t: table = {n:1};"
            + " let xs: number[] = [t[\"n\"]]; return xs[0];}\n";

    /** The computed form of the class literal field value. */
    private static final String NESTED_COMPUTED_CLASS_READ_REJECT =
        "class Box {n: number;}\n"
            + "export function f(): number {let t: table = {n:1};"
            + " let b: Box = {n:t[\"n\"]}; return b.n;}\n";

    /** The computed form of the template interpolation part. */
    private static final String NESTED_COMPUTED_TEMPLATE_READ_REJECT =
        "export function f(): string {let t: table = {s:\"a\"};"
            + " let v: string = `v${t[\"s\"]}`; return v;}\n";

    /**
     * The legal control of the same admission: the five direct positions
     * (parenthesized initializer and computed direct read included), a
     * typed intermediate inside array/class literals, contextual class and
     * array construction, and an ordinary template interpolation of a
     * statically typed string.
     */
    private static final String LEGAL_NESTED_LITERAL_DIRECT_READS = """
        import * as c from "std/console";
        class Box {n: number;}
        function take(n: number): number {return n;}
        function readN(t: table): number {return t.n;}
        export function main(): null {
         let t: table = {n: 1, flag: true, s: "a"};
         let n: number = (t.n);
         let computed: number = t["n"];
         n = t.n;
         n = take(t.n) + n;
         if (t.flag) {n = n + 1;}
         n = n + readN(t);
         let xs: number[] = [n];
         let b: Box = {n: n};
         let bs: Box[] = [{n: n}];
         let v: string = t.s;
         let s: string = `v${v}`;
         if (computed !== 1 || xs[0] !== 4 || b.n !== 4 || bs[0].n !== 4 || s !== "va") {
          throw {code: "TEST_FAIL", message: "direct read admission"};
         }
         c.log("nested-literal-direct-reads");
         return null;
        }
        """;

    /**
     * The direct contextual table-read admission (docs/spec-v1.3.md
     * §Reading table fields) on all three production backends: a table
     * read nested in an array literal element, a class literal field
     * value or a template interpolation part is E3003 at the read — the
     * dotted and computed forms alike — and the compile fails closed
     * before any publication. The legal control measures every admitted
     * direct position (parenthesized initializer, computed direct read, a
     * typed intermediate inside array/class literals, contextual class
     * and array construction, and an ordinary template interpolation of a
     * static string) on every target.
     */
    private static void testV13NestedLiteralReadAdmission() throws Exception {
        System.out.println("-- the closed direct contextual-read admission"
            + " rejects nested literal reads on every target --");
        for (String backend : List.of("luajit", "jvm", "js")) {
            checkCornerCaseReject(backend, "nested-array-literal-read",
                Map.of("src/main.deal", QUIET_SUBJECT_ENTRY,
                    "src/subject.deal", NESTED_ARRAY_LITERAL_READ_REJECT),
                "E3003", "src/subject.deal", 1, 72, 1, 75, null);
            checkCornerCaseReject(backend, "nested-class-literal-read",
                Map.of("src/main.deal", QUIET_SUBJECT_ENTRY,
                    "src/subject.deal", NESTED_CLASS_LITERAL_READ_REJECT),
                "E3003", "src/subject.deal", 2, 68, 2, 71, null);
            checkCornerCaseReject(backend, "nested-template-read",
                Map.of("src/main.deal", QUIET_SUBJECT_ENTRY,
                    "src/subject.deal", NESTED_TEMPLATE_READ_REJECT),
                "E3003", "src/subject.deal", 1, 74, 1, 77, null);
            checkCornerCaseReject(backend, "nested-computed-literal-read",
                Map.of("src/main.deal", QUIET_SUBJECT_ENTRY,
                    "src/subject.deal", NESTED_COMPUTED_LITERAL_READ_REJECT),
                "E3003", "src/subject.deal", 1, 72, 1, 78, null);
            checkCornerCaseReject(backend, "nested-computed-class-read",
                Map.of("src/main.deal", QUIET_SUBJECT_ENTRY,
                    "src/subject.deal", NESTED_COMPUTED_CLASS_READ_REJECT),
                "E3003", "src/subject.deal", 2, 68, 2, 74, null);
            checkCornerCaseReject(backend, "nested-computed-template-read",
                Map.of("src/main.deal", QUIET_SUBJECT_ENTRY,
                    "src/subject.deal", NESTED_COMPUTED_TEMPLATE_READ_REJECT),
                "E3003", "src/subject.deal", 1, 74, 1, 80, null);

            // The legal control compiles and completes normally on every
            // target.
            Path legal = Files.createTempDirectory(
                "production-legal-nested-literal-reads-");
            try {
                write(legal, "deal.json", retainedManifest(backend));
                write(legal, "src/main.deal",
                    LEGAL_NESTED_LITERAL_DIRECT_READS);
                if (compileRetainedSubject(legal,
                        backend + ": legal nested-literal direct reads",
                        true)) {
                    checkRetainedSubjectRun(legal, backend,
                        "legal nested-literal direct reads",
                        "nested-literal-direct-reads\n");
                }
            } finally {
                deleteRecursively(legal);
            }
        }
    }

    /**
     * The retained corner-case negatives (ISSUE-0815 subjects 3 and
     * 9-11) on all three production backends: each raw subject remains an
     * imported file with unchanged origins, fails closed before any
     * publication with the required code and exact range, and leaves a
     * previously published artifact set byte-identical. The legal
     * throwing-try/fallthrough-catch control compiles and completes
     * normally on every target.
     */
    private static void testRetainedCornerCaseNegatives() throws Exception {
        System.out.println("-- the retained corner-case negatives reject"
            + " before publication on every target --");
        for (String backend : List.of("luajit", "jvm", "js")) {
            checkCornerCaseReject(backend, "local-class original",
                Map.of("src/main.deal", LOCAL_CLASS_ORIGINAL),
                "E1057", "src/main.deal", 6, 2, 6, 27,
                "classes are module-level");
            checkCornerCaseReject(backend, "table-condition-context-reject",
                Map.of("src/main.deal", QUIET_SUBJECT_ENTRY,
                    "src/subject.deal", TABLE_CONDITION_CONTEXT_REJECT),
                "E3003", "src/subject.deal", 1, 65, 1, 71, null);
            checkCornerCaseReject(backend, "table-logical-context-reject",
                Map.of("src/main.deal", QUIET_SUBJECT_ENTRY,
                    "src/subject.deal", TABLE_LOGICAL_CONTEXT_REJECT),
                "E3003", "src/subject.deal", 1, 66, 1, 72, null);
            checkCornerCaseReject(backend,
                "try-return-catch-fallthrough-reject",
                Map.of("src/main.deal", QUIET_SUBJECT_ENTRY,
                    "src/subject.deal", TRY_RETURN_CATCH_FALLTHROUGH_REJECT),
                "E5002", "src/subject.deal", 1, 55, 1, 57, null);

            // The legal control completes normally on every target.
            Path legal = Files.createTempDirectory("production-legal-try-");
            try {
                write(legal, "deal.json", retainedManifest(backend));
                write(legal, "src/main.deal", LEGAL_THROWING_TRY_CONTROL);
                if (compileRetainedSubject(legal, backend + ": legal throwing-try", false)) {
                    checkRetainedSubjectRun(legal, backend, "legal throwing-try",
                        "legal-throwing-try\n");
                }
            } finally {
                deleteRecursively(legal);
            }
        }
    }

    /**
     * One negative subject on one backend: the compile of the raw imported
     * subject fails closed with the required code at the exact range, and
     * neither a fresh output root nor a previously published artifact set
     * changes.
     */
    private static void checkCornerCaseReject(String backend, String name,
            Map<String, String> sources, String code, String offendingRelative,
            int startLine, int startColumn, int endLine, int endColumn,
            String detailToken) throws Exception {
        Path project = Files.createTempDirectory(
            "production-corner-reject-" + name + "-");
        try {
            for (Map.Entry<String, String> source : sources.entrySet()) {
                write(project, source.getKey(), source.getValue());
            }
            write(project, "deal.json", retainedManifest(backend));
            Path out = project.resolve("out");
            write(out, "main.prior", "-- previously published artifact\n");
            Map<String, byte[]> before = snapshotTree(out);
            ArmCompile arm = compileWithInvocation(project, "src/main.deal",
                productionInvocation());
            String context = name + " [" + backend + "]";
            check(!arm.success(), context + ": the compile fails closed");
            List<CompilerDiagnostic> errors = arm.orchestrator().diagnostics()
                .stream().filter(d -> "error".equals(d.severity())).toList();
            checkEq(1, errors.size(), context
                + ": exactly one error diagnostic: "
                + arm.orchestrator().diagnostics());
            if (errors.size() == 1) {
                CompilerDiagnostic diagnostic = errors.get(0);
                checkEq(code, diagnostic.code(), context + ": the code");
                if (detailToken != null) {
                    check(diagnostic.message().contains(detailToken), context
                        + ": the message names " + detailToken + ": "
                        + diagnostic.message());
                }
                String file = diagnostic.range() == null ? ""
                    : diagnostic.range().file();
                check(file.replace('\\', '/').endsWith("/"
                        + offendingRelative), context + ": the range names "
                        + offendingRelative + ": " + file);
                if (diagnostic.range() != null) {
                    checkEq(startLine, diagnostic.range().startLine(),
                        context + ": the range start line");
                    checkEq(startColumn, diagnostic.range().startColumn(),
                        context + ": the range start column");
                    checkEq(endLine, diagnostic.range().endLine(),
                        context + ": the range end line");
                    checkEq(endColumn, diagnostic.range().endColumn(),
                        context + ": the range end column");
                }
            }
            checkTreeIdentical(before, out,
                context + ": admission publishes nothing");
        } finally {
            deleteRecursively(project);
        }
    }

    /**
     * The legal module-level default-call witness (ISSUE-0815 subject 3's
     * replacement) on the two shared production targets: the corpus
     * fixture with its host fixture (one retained runtime array and an
     * invocation-local omission counter) publishes through the release
     * owned CLI, executes under the real toolchain and prints exactly its
     * line with empty stderr and status 0. The witness itself asserts the
     * registration/order/reference/alias/closure/failure properties; the
     * drive proves the three-backend publication path.
     */
    private static void testRetainedDefaultCallWitnessBothTargets()
            throws Exception {
        System.out.println("-- the legal module-level default-call witness on"
            + " LuaJIT and JVM --");
        Path corpus = Path.of("test/conformance");
        String witnessPath = "backend-runtime/defaults/default-call-shared-reference.deal";
        String declarationPath = "host-fixtures/retained_default.d.deal";
        String witness = ConformanceHarnessMetadata.productionCorpusSource(
            Files.readString(corpus.resolve(witnessPath)), witnessPath);
        String declaration = ConformanceHarnessMetadata.productionCorpusSource(
            Files.readString(corpus.resolve(declarationPath)), declarationPath);
        String hostLua = Files.readString(corpus.resolve("host-fixtures/retained_default.lua"));
        String hostJava = Files.readString(corpus.resolve("host-fixtures/retained_default.java"));
        for (String backend : List.of("luajit", "jvm")) {
            Path project = Files.createTempDirectory(
                "production-default-witness-");
            try {
                write(project, "src/main.deal", witness);
                write(project, "bindings/retained_default.d.deal",
                    declaration);
                write(project, "deal.json", """
                    {
                      "languageVersion": "1.4",
                      "moduleRoots": ["src"],
                      "output": "out",
                      "backend": "%s",
                      "externals": {
                        "host/retained_default": { "declaration": "bindings/retained_default.d.deal" }
                      }
                    }
                    """.formatted(backend));
                if (!compileRetainedSubject(project, backend + ": default-call witness", true)) {
                    continue;
                }
                if ("luajit".equals(backend)) {
                    write(project, "out/host/retained_default.lua", hostLua);
                    checkRetainedSubjectRun(project, backend, "default-call witness",
                        "default-call-shared-reference\n");
                } else {
                    write(project, "HostRetained_default.java", hostJava);
                    checkRetainedSubjectRun(project, backend, "default-call witness",
                        "default-call-shared-reference\n",
                        project.resolve("HostRetained_default.java"));
                }
            } finally {
                deleteRecursively(project);
            }
        }
    }

    /**
     * ISSUE-0810: the per-construction default invocation carries
     * invocation-local attempt scratch. A default that re-enters its own
     * class's construction (through a module-level helper) must keep the
     * enclosing attempt's operand slots: the outer attempt's first operand
     * (the host counter read before the nested attempt) stays {@code 0}, so
     * the outer default composes {@code 0 * 1000 + 1100}. A clobbered
     * artifact composes the nested attempt's counter read and throws the
     * named tripwire. The two-required-default subjects pin the other half:
     * each attempt's produced default values are its own, so a nested
     * attempt (completing or failing) never replaces an enclosing attempt's
     * earlier default value — in the same-module LOCAL arm and in the
     * imported SHARED_FACTORY transfer alike. Every subject publishes
     * through the release-owned CLI and executes under the real toolchains
     * with the exact success transcript on LuaJIT, the JVM and the retained
     * JS lane.
     */
    private static void testDefaultReentrantAttemptScratchBothTargets()
            throws Exception {
        System.out.println("-- the re-entrant default's invocation-local attempt"
            + " scratch on LuaJIT, the JVM and the JS lane --");
        Map<String, String> hostSources = new LinkedHashMap<>();
        Path corpus = Path.of("test/conformance/host-fixtures");
        for (String backend : List.of("luajit", "jvm", "js")) {
            String extension = switch (backend) {
                case "luajit" -> "lua";
                case "jvm" -> "java";
                default -> "js";
            };
            hostSources.put(backend, Files.readString(
                corpus.resolve("retained_default." + extension)));
        }
        runReentrantDefaultSubject(hostSources, "re-entrant provided expression", """
            import * as console from "std/console";
            import * as state from "host/retained_default";

            class A { x: number; }
            function make(): A { return {x: combine(state.calls(), build())}; }

            function combine(a: number, b: number): number {
              return a * 1000 + b;
            }

            function build(): number {
              if (state.calls() >= 1) {
                return 100;
              }
              state.provideFirst();
              let inner: A = make();
              return inner.x;
            }

            export function main(): null {
              let a: A = make();
              if (a.x !== 1100) {
                throw { code: "OPERAND_OVERWRITE", message: "a nested attempt overwrote the enclosing operands" };
              }
              console.log("ok");
              return null;
            }
            """, Map.of());
        runReentrantDefaultSubject(hostSources, "failed nested attempt", """
            import * as console from "std/console";
            import * as state from "host/retained_default";

            class A { x: number; }
            function make(): A { return {x: combine(state.calls(), build())}; }

            function combine(a: number, b: number): number {
              return a * 1000 + b;
            }

            function build(): number {
              if (state.calls() >= 1) {
                throw { code: "INNER_FAIL", message: "inner attempt fails" };
              }
              state.provideFirst();
              try {
                let inner: A = make();
              } catch (e) {
              }
              return 5;
            }

            export function main(): null {
              let a: A = make();
              if (a.x !== 5) {
                throw { code: "OPERAND_OVERWRITE", message: "a failed nested attempt overwrote the enclosing operands" };
              }
              console.log("ok");
              return null;
            }
            """, Map.of());
        runReentrantDefaultSubject(hostSources, "two required provided fields", """
            import * as console from "std/console";
            import * as state from "host/retained_default";

            class A { x: number; y: number; }
            function make(): A { return {x: state.calls(), y: build()}; }

            function build(): number {
              if (state.calls() >= 1) {
                return 100;
              }
              state.provideFirst();
              let inner: A = make();
              return 7;
            }

            export function main(): null {
              let a: A = make();
              if (a.x !== 0) {
                throw { code: "ATTEMPT_VALUE_OVERWRITE", message: "a nested attempt overwrote the enclosing attempt's first default value" };
              }
              if (a.y !== 7) {
                throw { code: "ATTEMPT_VALUE_LOST", message: "the enclosing attempt's second default value is wrong" };
              }
              console.log("ok");
              return null;
            }
            """, Map.of());
        runReentrantDefaultSubject(hostSources, "two required provided fields, failed nested attempt", """
            import * as console from "std/console";
            import * as state from "host/retained_default";

            class A { x: number; y: number; }
            function make(): A { return {x: state.calls(), y: build()}; }

            function build(): number {
              if (state.calls() >= 1) {
                throw { code: "INNER_FAIL", message: "the nested attempt fails" };
              }
              state.provideFirst();
              try {
                let inner: A = make();
              } catch (e) {
              }
              return 7;
            }

            export function main(): null {
              let a: A = make();
              if (a.x !== 0) {
                throw { code: "ATTEMPT_VALUE_OVERWRITE", message: "a failed nested attempt overwrote the enclosing attempt's first default value" };
              }
              if (a.y !== 7) {
                throw { code: "ATTEMPT_VALUE_LOST", message: "the enclosing attempt's second default value is wrong" };
              }
              console.log("ok");
              return null;
            }
            """, Map.of());
        runReentrantDefaultSubject(hostSources, "imported class, two required defaults", """
            import * as console from "std/console";
            import * as lib from "lib";

            export function main(): null {
              let a: lib.A = lib.make();
              if (a.x !== 0) {
                throw { code: "ATTEMPT_VALUE_OVERWRITE", message: "a nested attempt overwrote the transferred provided value" };
              }
              if (a.y !== 7) {
                throw { code: "ATTEMPT_VALUE_LOST", message: "the transferred second default value is wrong" };
              }
              console.log("ok");
              return null;
            }
            """, Map.of("src/lib.deal", """
            import * as state from "host/retained_default";

            export class A { x: number; y: number; }
            export function make(): A { return {x: state.calls(), y: build()}; }

            function build(): number {
              if (state.calls() >= 1) {
                return 100;
              }
              state.provideFirst();
              let inner: A = make();
              return 7;
            }
            """));
        runReentrantDefaultSubject(hostSources, "imported class, failed nested attempt", """
            import * as console from "std/console";
            import * as lib from "lib";

            export function main(): null {
              let a: lib.A = lib.make();
              if (a.x !== 0) {
                throw { code: "ATTEMPT_VALUE_OVERWRITE", message: "a failed nested attempt overwrote the transferred provided value" };
              }
              if (a.y !== 7) {
                throw { code: "ATTEMPT_VALUE_LOST", message: "the transferred second default value is wrong" };
              }
              console.log("ok");
              return null;
            }
            """, Map.of("src/lib.deal", """
            import * as state from "host/retained_default";

            export class A { x: number; y: number; }
            export function make(): A { return {x: state.calls(), y: build()}; }

            function build(): number {
              if (state.calls() >= 1) {
                throw { code: "INNER_FAIL", message: "the nested attempt fails" };
              }
              state.provideFirst();
              try {
                let inner: A = make();
              } catch (e) {
              }
              return 7;
            }
            """));
    }

    /**
     * One re-entrant-default subject published by the release-owned CLI and
     * executed under the real toolchains of all three lanes: the exact
     * success transcript pins that a nested attempt (failing or completing)
     * never overwrites the enclosing attempt's operands or its produced
     * default values. {@code extraSources} carries the provider module of an
     * imported-class subject.
     */
    private static void runReentrantDefaultSubject(Map<String, String> hostSources,
            String label, String subject, Map<String, String> extraSources) throws Exception {
        String declaration = """
            export function retained(): number[];
            export function provideFirst(): number[];
            export function bump(): null;
            export function calls(): number;
            """;
        Path project = Files.createTempDirectory("production-default-reentrancy-");
        try {
            write(project, "src/main.deal", subject);
            for (Map.Entry<String, String> extra : extraSources.entrySet()) {
                write(project, extra.getKey(), extra.getValue());
            }
            write(project, "bindings/retained_default.d.deal", declaration);
            for (String backend : List.of("luajit", "jvm", "js")) {
                Path out = project.resolve("out");
                try {
                    write(project, "deal.json", """
                        {
                          "languageVersion": "1.4",
                          "moduleRoots": ["src"],
                          "output": "out",
                          "backend": "%s",
                          "externals": {
                            "host/retained_default": { "declaration": "bindings/retained_default.d.deal" }
                          }
                        }
                        """.formatted(backend));
                    if (!compileRetainedSubject(project, backend + ": " + label, true)) {
                        continue;
                    }
                    if ("jvm".equals(backend)) {
                        write(project, "HostRetained_default.java", hostSources.get(backend));
                        checkRetainedSubjectRun(project, backend, label, "ok\n",
                            project.resolve("HostRetained_default.java"));
                    } else {
                        write(out, "host/retained_default."
                            + ("luajit".equals(backend) ? "lua" : "js"), hostSources.get(backend));
                        checkRetainedSubjectRun(project, backend, label, "ok\n");
                    }
                } finally {
                    deleteRecursively(out);
                    Files.deleteIfExists(project.resolve("HostRetained_default.java"));
                }
            }
        } finally {
            deleteRecursively(project);
        }
    }

    private static String retainedManifest(String backend) {
        return switch (backend) {
            case "luajit" -> DEAL_JSON_LUA;
            case "jvm" -> DEAL_JSON_JVM;
            case "js" -> DEAL_JSON_JS;
            default -> throw new IllegalArgumentException("unknown backend: " + backend);
        };
    }

    private static boolean compileRetainedSubject(Path project, String context,
            boolean quiet) throws Exception {
        ProjectOutcome compile = productionCompile(project, "src/main.deal", "out");
        checkEq(0, compile.exitCode(), context + ": compiles: " + compile.stderr());
        if (quiet) {
            checkEq("", compile.stderr(), context + ": compile emits no diagnostic");
        }
        return compile.exitCode() == 0;
    }

    private static void checkRetainedSubjectRun(Path project, String backend,
            String name, String stdout, Path... hostSources) throws Exception {
        Path out = project.resolve("out");
        String context = backend + ": " + name;
        BoundedProcess.BoundedResult run;
        if ("jvm".equals(backend)) {
            String classpath = absoluteClasspath();
            List<String> sources = new ArrayList<>(List.of(out.resolve("Main.java").toString()));
            for (Path host : hostSources) {
                sources.add(host.toString());
            }
            List<String> command = new ArrayList<>(List.of("javac", "--release", "25",
                "-proc:none", "-cp", out + File.pathSeparator + classpath, "-d", out.toString()));
            command.addAll(sources);
            BoundedProcess.BoundedResult javac = compileBoundedJvm(project, command.toArray(String[]::new));
            checkEq(0, javac.exitCode(), context + ": artifact compiles: " + boundedOutput(javac));
            if (javac.exitCode() != 0) {
                return;
            }
            run = runBounded(out, "java", "-cp", out + File.pathSeparator + classpath, "Main");
        } else {
            run = "luajit".equals(backend) ? runBounded(out, "luajit", "main.lua")
                : runBounded(out, "node", "main.js");
        }
        checkEq(0, run.exitCode(), context + ": exits 0: " + boundedOutput(run));
        checkEq(stdout, run.stdout(), context + ": exact stdout");
        checkEq("", run.stderr(), context + ": empty stderr");
    }

    // =========================================================================
    // The current function-value call selection (assigned function bindings)
    // =========================================================================

    /**
     * The published corpus subjects of the current-value call selection: the
     * same-module capture/loop/branch subject, the imported-value subject,
     * and the host-origin subject. Each entry names the fixture, its
     * companion source modules, and — for the host subject — the host
     * declaration fixture.
     */
    private record FunctionBindingSubject(String label, String fixture,
            Map<String, String> companions, String hostDeclarationFixture) {
    }

    private static final List<FunctionBindingSubject> FUNCTION_BINDING_SUBJECTS =
        List.of(
            new FunctionBindingSubject("capture and loop",
                "backend-runtime/functions/function-value-binding-reassignment.deal",
                Map.of(), null),
            new FunctionBindingSubject("imported value",
                "backend-runtime/closures/imported-function-value-reassignment.deal",
                Map.of("src/reassignment_value_lib.deal",
                    "backend-runtime/closures/reassignment_value_lib.deal"), null),
            new FunctionBindingSubject("host origin",
                "backend-runtime/host-abi/host-function-value-binding.deal",
                Map.of(), "host-fixtures/function_binding.d.deal"));

    /**
     * The function-valued for-let counter subject (ISSUE-0864): the
     * declaration initializer, the update's reassignment, an omitted update
     * with a body assignment, the arity-adapted initializer, the captured
     * per-iteration value and a recursive function value, each bounded by a
     * visit guard so a stale callee or a lost carry fails the exact count
     * instead of running away. The observed transcript is the single
     * {@code ok} line: every row throws its own {@code TEST_FAIL} error
     * when the counter's value or call target is wrong.
     */
    private static final String FOR_LET_FUNCTION_COUNTER_SOURCE = "import * as console from \"std/console\";\n\nfunction one(): number { return 1; }\nfunction two(): number { return 2; }\n\nfunction fib(n: number): number {\n  if (n < 2) { return n; }\n  return fib(n - 1) + fib(n - 2);\n}\n\nexport function main(): null {\n  let visits: number = 0;\n  { let v14Loop2: () => number = one; while (v14Loop2() === 1) { let f: () => number = v14Loop2; {\n    visits = visits + 1;\n    if (visits > 4) { break; }\n  } v14Loop2 = f; v14Loop2 = two; } }\n  if (visits !== 1) {\n    throw { code: \"TEST_FAIL\", message: \"the update did not select two\" };\n  }\n\n  let bodyVisits: number = 0;\n  { let v14Loop3: () => number = one; while (v14Loop3() === 1) { let f: () => number = v14Loop3; {\n    bodyVisits = bodyVisits + 1;\n    f = two;\n    if (bodyVisits > 4) { break; }\n  } v14Loop3 = f;  } }\n  if (bodyVisits !== 1) {\n    throw { code: \"TEST_FAIL\", message: \"the body assignment did not reach the test\" };\n  }\n\n  let adapted: number = 0;\n  { let v14Loop4: (x: number) => number = one; while (v14Loop4(0) === 1) { let f: (x: number) => number = v14Loop4; {\n    adapted = adapted + 1;\n    if (adapted > 4) { break; }\n  } v14Loop4 = f; v14Loop4 = two; } }\n  if (adapted !== 1) {\n    throw { code: \"TEST_FAIL\", message: \"the adapted counter visited wrongly\" };\n  }\n\n  let captured: number = 0;\n  { let v14Loop5: () => number = one; while (captured < 2) { let f: () => number = v14Loop5; {\n    if (f() !== (captured + 1)) {\n      throw { code: \"TEST_FAIL\", message: \"the per-iteration value is stale\" };\n    }\n    captured = captured + 1;\n  } v14Loop5 = f; v14Loop5 = two; } }\n  if (captured !== 2) {\n    throw { code: \"TEST_FAIL\", message: \"the per-iteration rows did not finish\" };\n  }\n\n  let recursive: number = 0;\n  { let v14Loop6: (n: number) => number = fib; while (v14Loop6(5) === 5) { let f: (n: number) => number = v14Loop6; {\n    recursive = recursive + 1;\n    if (recursive > 4) { break; }\n  } v14Loop6 = f; v14Loop6 = one; } }\n  if (recursive !== 1) {\n    throw { code: \"TEST_FAIL\", message: \"the recursive counter visited wrongly\" };\n  }\n\n  console.log(\"ok\");\n  return null;\n}\n";

    /**
     * The function-valued for-let counter on every production lane: the
     * subject compiles clean through the release-owned CLI and its generated
     * Java artifact compiles with {@code javac --release 25 -proc:none}, and
     * each lane executes the artifact under its real toolchain with the one
     * pinned {@code ok} line, an empty stderr and exit 0 — so a stale
     * callee (a read of the declaration's initializer instead of the
     * binding) or a lost carry changes the observed counts and fails the
     * row.
     */
    private static void testForLetFunctionCounterThreeLanes() throws Exception {
        System.out.println("-- the function-valued for-let counter on LuaJIT, the "
            + "JVM and the JS lane (clean public compile, generated Java "
            + "compile, executed counts) --");
        checkPublicProject("function-valued for-let counter",
            "production-for-let-function-counter-",
            Map.of("src/main.deal", FOR_LET_FUNCTION_COUNTER_SOURCE),
            List.of("js", "luajit", "jvm"));
    }

    /**
     * The current function-value call selection on the published artifacts of
     * all three lanes: the three corpus subjects are published through the
     * release-owned CLI for each backend and executed under the real
     * toolchain, and every observation is compared with the subject's own
     * sidecar pin — so one authority fixes the expected values, the exact
     * {@code json.stringify} transcript, the exit code and the empty stderr.
     * A function-typed binding assigned anywhere in its declaring body is
     * read by binding: the closure body, the repeated loop condition, the
     * post-branch call, the imported-value read and the host-origin read all
     * dispatch on the current value, while the never-assigned, recursive,
     * mutually recursive and arity-extended controls keep their statically
     * resolved callees.
     */
    private static void testFunctionBindingCurrentValueThreeLanes() throws Exception {
        System.out.println("-- the current function binding after reassignment on "
            + "LuaJIT, the JVM and the JS lane (the published subjects executed "
            + "under the real toolchains, pinned by their own sidecars) --");
        Path corpus = Path.of("test", "conformance");
        Map<String, String> hostSources = new LinkedHashMap<>();
        for (String backend : List.of("luajit", "jvm", "js")) {
            String extension = switch (backend) {
                case "luajit" -> "lua";
                case "jvm" -> "java";
                default -> "js";
            };
            hostSources.put(backend, Files.readString(
                corpus.resolve("host-fixtures/function_binding." + extension)));
        }
        for (FunctionBindingSubject subject : FUNCTION_BINDING_SUBJECTS) {
            String fixturePath = subject.fixture();
            String source = ConformanceHarnessMetadata.productionCorpusSource(
                Files.readString(corpus.resolve(fixturePath)), fixturePath);
            String sidecar = Files.readString(corpus.resolve(
                fixturePath.substring(0, fixturePath.length() - ".deal".length())
                    + ".expect.json"));
            SidecarExpectations.StructuredExpectationSidecar pinned =
                SidecarExpectations.StructuredExpectationSidecar.parse(sidecar);
            for (String backend : List.of("luajit", "jvm", "js")) {
                SidecarExpectations.RuntimeExpectation expectation =
                    pinned.expectationFor(backend);
                if (!(expectation
                        instanceof SidecarExpectations.RuntimeExpectation.Executed
                            executed)) {
                    fail(backend + ": " + subject.label()
                        + ": the subject's sidecar pins no executed expectation");
                    continue;
                }
                String hostDeclaration = subject.hostDeclarationFixture() == null
                    ? null : ConformanceHarnessMetadata.productionCorpusSource(
                        Files.readString(corpus.resolve(
                            subject.hostDeclarationFixture())),
                        subject.hostDeclarationFixture());
                Path project = Files.createTempDirectory(
                    "production-function-binding-");
                try {
                    write(project, "src/main.deal", source);
                    for (Map.Entry<String, String> companion
                            : subject.companions().entrySet()) {
                        String companionPath = companion.getValue();
                        write(project, companion.getKey(),
                            ConformanceHarnessMetadata.productionCorpusSource(
                                Files.readString(corpus.resolve(companionPath)),
                                companionPath));
                    }
                    String externals = hostDeclaration == null ? ""
                        : """
                          ,
                              "externals": {
                                "host/function_binding": { "declaration": "bindings/function_binding.d.deal" }
                              }
                          """;
                    if (hostDeclaration != null) {
                        write(project, "bindings/function_binding.d.deal",
                            hostDeclaration);
                    }
                    write(project, "deal.json", """
                        {
                          "languageVersion": "1.4",
                          "moduleRoots": ["src"],
                          "output": "out",
                          "backend": "%s"%s
                        }
                        """.formatted(backend, externals));
                    if (!compileRetainedSubject(project,
                            backend + ": function binding (" + subject.label() + ")",
                            true)) {
                        continue;
                    }
                    String expectedStdout = new String(executed.stdout(),
                        StandardCharsets.UTF_8);
                    if (hostDeclaration != null) {
                        switch (backend) {
                            case "luajit" -> write(project,
                                "out/host/function_binding.lua",
                                hostSources.get(backend));
                            case "js" -> write(project,
                                "out/host/function_binding.js",
                                hostSources.get(backend));
                            default -> write(project, "HostFunction_binding.java",
                                hostSources.get(backend));
                        }
                    }
                    Path hostSource = hostDeclaration != null
                            && "jvm".equals(backend)
                        ? project.resolve("HostFunction_binding.java") : null;
                    checkRetainedSubjectRun(project, backend,
                        "function binding (" + subject.label() + ")",
                        expectedStdout,
                        hostSource == null ? new Path[0] : new Path[] {hostSource});
                } finally {
                    deleteRecursively(project);
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Production Dispatch and Cutover Acceptance "
            + "Tests (ISSUE-0643) ===\n");

        testInvocationPredicate();
        testProductionLuaJitSingleModule();
        testProductionJvmTwoModule();
        testProductionLuaJitTwoModuleSurfaces();
        testProductionSiblingModuleProviderIdentity();
        testProductionSameNameSiblingSharedIr();
        testProductionLocalDeclarationBesideCalleeProvider();
        testProductionCallReceiverProviderBesideSameIdentityLocal();
        testProductionNestedFieldAndTransitiveProviders();
        testProductionPlanOwnerJsonLinks();
        testProductionSameIdentitySiblingLayouts();
        testProductionDeclarationClassJsonFieldFailsClosed();
        testProductionSameNameSiblingCollision();
        testProductionContextualCallSiblingCollision();
        testProductionShadowedLocalCalleeCollision();
        testProductionNestedSiblingCollision();
        testProductionDeclaredContextSiblingCollision();
        testProductionIdentifierAssignmentCollision();
        testProductionMemberAssignmentCollision();
        testProductionShadowingInitializerCrossingRejected();
        testConversionOverflowTerminal();
        Path sync = Files.createTempDirectory("production-dispatch-sync-");
        try {
            write(sync, "deal.json", DEAL_JSON_LUA);
            write(sync, "src/lib.deal", LIB_SOURCE);
            write(sync, "src/main.deal", SYNC_CALL_SOURCE);
            ArmCompile compiled = compileWithInvocation(sync, "src/main.deal",
                productionInvocation());
            testAtomicFailureThroughDispatch(sync, compiled);
            testProducedProjectAccessor(sync, compiled);
            testSingleArmDispatch(sync, compiled);
        } finally {
            deleteRecursively(sync);
        }
        testPreservedLoweringOrigins();
        testFailClosedFamilies();
        testSourceMapDisposition();
        testMissingDeploymentContentFails();
        testSourceMapUnsafeSourcePathText();
        testSourceMapControlCharacterPaths();
        testSourceMapDirectEmissionGroups();
        testFailClosedStagesNothingWithDumps();
        testV12SplitCaptureControls();
        testArrayReadV13ProductionDrive();
        testStdlibJsonCarrierBoundaryV13ProductionDrive();
        testMathSqrtNegativeArmThreeLanes();
        testV12CornerCaseCorpusProductionDrive();
        testV13BoundaryCorpusProductionDrive();
        testRetainedDefaultCallWitnessBothTargets();
        testDefaultReentrantAttemptScratchBothTargets();
        testRetainedCornerCaseDiamond();
        testRetainedCornerCaseNegatives();
        testV13NestedLiteralReadAdmission();
        testFunctionBindingCurrentValueThreeLanes();
        testForLetFunctionCounterThreeLanes();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
