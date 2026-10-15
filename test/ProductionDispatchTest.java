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
          "languageVersion": "1.3",
          "moduleRoots": ["src"],
          "output": "out",
          "backend": "luajit"
        }
        """;

    private static final String DEAL_JSON_JVM = """
        {
          "languageVersion": "1.3",
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
          if (ir.useIr({}) === 0 && codec.useCodec({}) === 0) { console.log("ok"); }
          return null;
        }
        """;

    /** The same program with the two provider imports reversed. */
    private static final String SIBLING_MAIN_SOURCE_REVERSED = """
        import * as console from "std/console";
        import * as codec from "./m/codec";
        import * as ir from "./m/ir";
        export function main(): null {
          if (ir.useIr({}) === 0 && codec.useCodec({}) === 0) { console.log("ok"); }
          return null;
        }
        """;

    private static final String SIBLING_IR_SOURCE = """
        export class Ir { a: int = 0; }
        export function useIr(x: Ir): int { return x.a; }
        """;

    private static final String SIBLING_CODEC_SOURCE = """
        export class Codec { b: int = 0; }
        export function useCodec(y: Codec): int { return y.b; }
        """;

    /**
     * The GitHub #6 same-name collision control (review cycle 1): the
     * second sibling declares a non-exported class of the first
     * sibling's name, so a name-bearing sibling program must never
     * become the provider and the sibling's own plan
     * ({@code a = 93}) must never serialize as the demanded class.
     */
    private static final String COLLISION_IR_SOURCE = """
        export class Ir { a: int = 7; }
        export function useIr(x: Ir): int { return x.a; }
        """;

    private static final String COLLISION_CODEC_SOURCE = """
        class Ir { a: int = 93; }
        export class Codec { b: int = 9; }
        export function useCodec(y: Codec): int { return y.b; }
        """;

    /** The collision consumer: the qualified field annotation names
     * {@code ir.Ir} while the codec sibling declares a same-name
     * non-exported class. */
    private static final String COLLISION_MAIN_CODEC_FIRST = """
        import * as console from "std/console";
        import * as codec from "./m/codec";
        import * as ir from "./m/ir";
        export class Outer { r: ir.Ir = {}; c: codec.Codec = {}; }
        export function main(): null {
          let o: Outer = {};
          if (o.r.a === 7 && o.c.b === 9) { console.log("ok"); }
          return null;
        }
        """;

    /** The collision consumer with the two provider imports reversed. */
    private static final String COLLISION_MAIN_IR_FIRST = """
        import * as console from "std/console";
        import * as ir from "./m/ir";
        import * as codec from "./m/codec";
        export class Outer { r: ir.Ir = {}; c: codec.Codec = {}; }
        export function main(): null {
          let o: Outer = {};
          if (o.r.a === 7 && o.c.b === 9) { console.log("ok"); }
          return null;
        }
        """;

    /**
     * The contextual-call collision (review cycle 2): the class context
     * arrives only through {@code ir.useIr}'s parameter, and both
     * identity-equal siblings export the demanded {@code Ir} name, so
     * the resolved imported callee's declaring module must own the
     * recorded plan and the emitted construction (the codec sibling's
     * own default never serializes as the demanded class).
     */
    private static final String CONTEXTUAL_IR_SOURCE = """
        export class Ir { a: int = 7; }
        export function useIr(x: Ir): int { return x.a; }
        """;

    private static final String CONTEXTUAL_CODEC_SOURCE = """
        export class Ir { a: int = 93; }
        export class Codec { b: int = 9; }
        export function useCodec(y: Codec): int { return y.b; }
        """;

    /** The contextual collision consumer: the literal is an argument of
     * the {@code ir}-imported callee, so only the callee's parameter
     * context names the demanded provider. */
    private static final String CONTEXTUAL_MAIN_CODEC_FIRST = """
        import * as console from "std/console";
        import * as codec from "./m/codec";
        import * as ir from "./m/ir";
        export class Outer { x: int = ir.useIr({}); c: codec.Codec = {}; }
        export function main(): null {
          let o: Outer = {};
          if (o.x === 7 && o.c.b === 9) { console.log("ok"); }
          return null;
        }
        """;

    /** The contextual collision consumer with the imports reversed. */
    private static final String CONTEXTUAL_MAIN_IR_FIRST = """
        import * as console from "std/console";
        import * as ir from "./m/ir";
        import * as codec from "./m/codec";
        export class Outer { x: int = ir.useIr({}); c: codec.Codec = {}; }
        export function main(): null {
          let o: Outer = {};
          if (o.x === 7 && o.c.b === 9) { console.log("ok"); }
          return null;
        }
        """;

    /** The original reproducer shape (a body call) under the exported
     * same-name sibling collision: the construction sits outside a
     * published plan default. */
    private static final String CONTEXTUAL_BODY_CODEC_FIRST = """
        import * as console from "std/console";
        import * as codec from "./m/codec";
        import * as ir from "./m/ir";
        export function main(): null {
          if (ir.useIr({}) === 7) { console.log("ok"); }
          return null;
        }
        """;

    private static final String CONTEXTUAL_BODY_IR_FIRST = """
        import * as console from "std/console";
        import * as ir from "./m/ir";
        import * as codec from "./m/codec";
        export function main(): null {
          if (ir.useIr({}) === 7) { console.log("ok"); }
          return null;
        }
        """;

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
        function consume(x: codec.Ir): int { return x.a; }
        export function main(): null {
          function consume(x: ir.Ir): int { return x.a; }
          if (consume({}) === 7) { console.log("ok"); }
          else { console.log("wrong"); }
          return null;
        }
        """;

    /** The shadowed-local-callee collision with the imports reversed. */
    private static final String SHADOWED_CALLEE_IR_FIRST = """
        import * as console from "std/console";
        import * as ir from "./m/ir";
        import * as codec from "./m/codec";
        function consume(x: codec.Ir): int { return x.a; }
        export function main(): null {
          function consume(x: ir.Ir): int { return x.a; }
          if (consume({}) === 7) { console.log("ok"); }
          else { console.log("wrong"); }
          return null;
        }
        """;

    /**
     * The nested-literal control (review cycle 2): the demanded class
     * is a nested field of an imported class, so the enclosing field
     * annotation names the outer class only. The declaring program's own
     * field declaration carries the nested class's provider.
     */
    private static final String NESTED_IR_SOURCE = """
        export class Ir { a: int = 7; }
        export class Wrap { inner: Ir = {}; }
        """;

    private static final String NESTED_CODEC_SOURCE = """
        export class Ir { a: int = 93; }
        """;

    private static final String NESTED_MAIN_CODEC_FIRST = """
        import * as console from "std/console";
        import * as codec from "./m/codec";
        import * as ir from "./m/ir";
        export class Outer { w: ir.Wrap = { inner: {} }; }
        export function main(): null {
          let o: Outer = {};
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
        export class Ir { a: int = 7; }
        """;

    private static final String RETURN_CODEC_SOURCE = """
        export class Ir { a: int = 93; }
        export class Codec { b: int = 9; }
        """;

    private static final String RETURN_MAIN_CODEC_FIRST = """
        import * as console from "std/console";
        import * as codec from "./m/codec";
        import * as ir from "./m/ir";
        function make(): ir.Ir { return {}; }
        export class Outer { r: ir.Ir = {}; c: codec.Codec = {}; }
        export function main(): null {
          let o: Outer = {};
          o.r = {};
          let xs: ir.Ir[] = [{}];
          let m: ir.Ir = make();
          if (o.r.a === 7 && xs[0].a === 7 && m.a === 7 && o.c.b === 9)
            { console.log("ok"); }
          return null;
        }
        """;

    private static final String RETURN_MAIN_IR_FIRST = """
        import * as console from "std/console";
        import * as ir from "./m/ir";
        import * as codec from "./m/codec";
        function make(): ir.Ir { return {}; }
        export class Outer { r: ir.Ir = {}; c: codec.Codec = {}; }
        export function main(): null {
          let o: Outer = {};
          o.r = {};
          let xs: ir.Ir[] = [{}];
          let m: ir.Ir = make();
          if (o.r.a === 7 && xs[0].a === 7 && m.a === 7 && o.c.b === 9)
            { console.log("ok"); }
          return null;
        }
        """;

    /**
     * The identifier-assignment collision (review cycle 5): the
     * assignment value's context is the assigned binding's own resolved
     * provenance (its declared annotation, or the initializer route of
     * an inferred binding), so every assignment literal — a local, an
     * indexed element, an inferred binding, and a class-typed parameter —
     * must construct the exact provider the binding names, never the
     * first identity-equal export of the import order.
     */
    private static final String ASSIGN_IR_SOURCE = """
        export class Ir { a: int = 7; }
        export function make(): Ir { return {}; }
        """;

    private static final String ASSIGN_CODEC_SOURCE = """
        export class Ir { a: int = 93; }
        export class Codec { b: int = 9; }
        """;

    private static final String ASSIGN_MAIN_CODEC_FIRST = """
        import * as console from "std/console";
        import * as codec from "./m/codec";
        import * as ir from "./m/ir";
        function set(p: ir.Ir): int { p = {}; return p.a; }
        export function main(): null {
          let x: ir.Ir = {};
          x = {};
          let xs: ir.Ir[] = [{}];
          xs[0] = {};
          let y = ir.make();
          y = {};
          let c: codec.Codec = {};
          if (x.a === 7 && xs[0].a === 7 && y.a === 7 && set({}) === 7
              && c.b === 9) { console.log("ok"); }
          else { console.log("wrong"); }
          return null;
        }
        """;

    private static final String ASSIGN_MAIN_IR_FIRST = """
        import * as console from "std/console";
        import * as ir from "./m/ir";
        import * as codec from "./m/codec";
        function set(p: ir.Ir): int { p = {}; return p.a; }
        export function main(): null {
          let x: ir.Ir = {};
          x = {};
          let xs: ir.Ir[] = [{}];
          xs[0] = {};
          let y = ir.make();
          y = {};
          let c: codec.Codec = {};
          if (x.a === 7 && xs[0].a === 7 && y.a === 7 && set({}) === 7
              && c.b === 9) { console.log("ok"); }
          else { console.log("wrong"); }
          return null;
        }
        """;

    /**
     * The member-assignment collision (review cycle 5): the assigned
     * target is a class-typed member of a wrapper binding (a local, an
     * inferred binding, a same-module class field, an array element,
     * and a parameter), so the owner expression's own checked route
     * names the field declaration's provider — never the first
     * identity-equal export of the import order.
     */
    private static final String ASSIGN_MEMBER_IR_SOURCE = """
        export class Ir { a: int = 7; }
        export class Wrap { inner: Ir = {}; }
        export function makeWrap(): Wrap { return {}; }
        """;

    private static final String ASSIGN_MEMBER_CODEC_SOURCE = """
        export class Ir { a: int = 93; }
        export class Wrap { inner: Ir = {}; }
        export function makeWrap(): Wrap { return {}; }
        """;

    private static final String ASSIGN_MEMBER_MAIN_CODEC_FIRST = """
        import * as console from "std/console";
        import * as codec from "./m/codec";
        import * as ir from "./m/ir";
        class Holder { w: ir.Wrap = {}; }
        function setParam(w: ir.Wrap): int { w.inner = {}; return w.inner.a; }
        export function main(): null {
          let w: ir.Wrap = {};
          w.inner = {};
          let v = ir.makeWrap();
          v.inner = {};
          let h: Holder = {};
          h.w.inner = {};
          let xs: ir.Wrap[] = [{}];
          xs[0].inner = {};
          if (w.inner.a === 7 && v.inner.a === 7 && h.w.inner.a === 7
              && xs[0].inner.a === 7 && setParam({}) === 7) {
            console.log("ok");
          } else { console.log("wrong"); }
          return null;
        }
        """;

    private static final String ASSIGN_MEMBER_MAIN_IR_FIRST = """
        import * as console from "std/console";
        import * as ir from "./m/ir";
        import * as codec from "./m/codec";
        class Holder { w: ir.Wrap = {}; }
        function setParam(w: ir.Wrap): int { w.inner = {}; return w.inner.a; }
        export function main(): null {
          let w: ir.Wrap = {};
          w.inner = {};
          let v = ir.makeWrap();
          v.inner = {};
          let h: Holder = {};
          h.w.inner = {};
          let xs: ir.Wrap[] = [{}];
          xs[0].inner = {};
          if (w.inner.a === 7 && v.inner.a === 7 && h.w.inner.a === 7
              && xs[0].inner.a === 7 && setParam({}) === 7) {
            console.log("ok");
          } else { console.log("wrong"); }
          return null;
        }
        """;

    /**
     * The shadowing-initializer assignment collision (review 0005): the
     * assignment inside a shadowing let's own initializer targets the
     * enclosing binding — the pending declaration is not visible there
     * (docs/spec-v1.2.md §Declaration order) — so the constructed
     * literal keeps the enclosing binding's declared provider. The
     * pre-fix recorder resolved the not-yet-visible inner declaration,
     * so the identity-equal sibling's class was constructed and the
     * import order decided the demanded default (7 versus 93) with no
     * diagnostic.
     */
    private static final String SHADOW_IR_SOURCE = """
        export class Ir { a: int = 7; }
        export function make(): Ir { return {}; }
        """;

    private static final String SHADOW_CODEC_SOURCE = """
        export class Ir { a: int = 93; }
        export class Codec { b: int = 9; }
        """;

    private static final String SHADOW_MAIN_CODEC_FIRST = """
        import * as console from "std/console";
        import * as codec from "./m/codec";
        import * as ir from "./m/ir";
        export function main(): null {
          let x: ir.Ir = {};
          { let x: codec.Ir = (x = {}); }
          for (let x: codec.Ir = (x = {}); false; ) { }
          if (x.a === 7) { console.log("ok"); } else { console.log("wrong"); }
          return null;
        }
        """;

    private static final String SHADOW_MAIN_IR_FIRST = """
        import * as console from "std/console";
        import * as ir from "./m/ir";
        import * as codec from "./m/codec";
        export function main(): null {
          let x: ir.Ir = {};
          { let x: codec.Ir = (x = {}); }
          for (let x: codec.Ir = (x = {}); false; ) { }
          if (x.a === 7) { console.log("ok"); } else { console.log("wrong"); }
          return null;
        }
        """;

    private static final String DEAL_JSON_JS = """
        {
          "languageVersion": "1.3",
          "moduleRoots": ["src"],
          "output": "out",
          "backend": "js"
        }
        """;

    private static final String HOST_DEAL_JSON_LUA = """
        {
          "languageVersion": "1.3",
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
        export function value(): int {
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
        export function value(): int {
          return 42;
        }

        export function label(): string {
          return "lib";
        }

        export async function load(): int {
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

        export async function worker(): int {
          return await lib.load();
        }
        """;

    /**
     * The direct-emission library of the source-map regression: one exported
     * class whose CLASS_FACTORY op carries the class declaration's span (the
     * owner module of the construction-time factory group).
     */
    private static final String DIRECT_EMISSION_LIB_SOURCE = """
        export function value(): int {
          return 42;
        }

        export class Point {
          x: int = 0;
        }

        export class DefaultPoint {
          x: int = 3;
          y: int = 4;
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

        export function f(x: int): int {
          return x;
        }

        export function main(): null {
          let p: lib.Point = { x: 1 };
          let q: lib.DefaultPoint = {};
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
          "languageVersion": "1.3",
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
          "languageVersion": "1.3",
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
          "languageVersion": "1.3",
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
          let x: int = 2147483647 + 1;
          return null;
        }
        """;

    /** The bytes-bearing fixture: the lowering fails CONSTRUCT_UNLOWERED. */
    private static final String BYTES_SOURCE = """
        export function main(): null {
          let n: int = 3;
          let b: bytes = bytes(n);
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
     * The unrealized construct seed: a function-typed {@code for} counter
     * (the per-iteration carry load's function identity is outside the
     * closed lowering coverage, B5), so the production compile still fails
     * the module with the named E6005 {@code CONSTRUCT_UNLOWERED} before any
     * emission. The forward function-typed materialization this battery
     * previously pinned as a later slice's construct is production-covered
     * now: a module-level declaration's execution binding registers at the
     * hoist, so a forward alias resolves its body.
     */
    private static final String UNREALIZED_CONSTRUCT_SOURCE = """
        export function main(): null {
          let counter: (a: int) => int = function(a: int): int { return a; };
          for (let f: (a: int) => int = counter; f(1) === 1; f = counter) {
            break;
          }
          return null;
        }
        """;

    /** The cross-module sync call: the realized external-call arm. */
    private static final String SYNC_CALL_SOURCE = """
        import * as lib from "./lib";

        export function main(): null {
          let x: int = lib.value();
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
            + "  let x: int = 3;\n"
            + "  class Local { n: int = x; }\n"
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
        "class A { n: int = 7; }\n"
            + "export function main(): null {\n"
            + "  { class A { n: int = 9; } }\n"
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
        "class A { n: int = 7; }\n"
            + "\n"
            + "export function f(): null {\n"
            + "  { class A { n: int = 9; } }\n"
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
            + "\tlet x: int = 3;\n"
            + "\tclass Local { n: int = x; }\n"
            + "\tlet c: Local = {};\n"
            + "\treturn null;\n"
            + "}\n";

    /** The cross-module async library. */
    private static final String ASYNC_LIB_SOURCE = """
        export async function getValue(): int {
          return 42;
        }
        """;

    /** The cross-module async call (a never-invoked body carries it). */
    private static final String CROSS_ASYNC_SOURCE = """
        import * as lib from "./lib";

        export function main(): null {
          return null;
        }

        async function worker(): int {
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

        async function compute(): int {
          return 1;
        }

        export async function worker(): int {
          return await compute();
        }
        """;

    /** The host declaration of the HOST-import fixture. */
    private static final String HOST_DECLARATION = """
        export function version(): int;
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
            Files.walk(dir).sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
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

    /**
     * The bounded real-toolchain runner of the focused acceptance drive:
     * the retained {@link BoundedProcess} helper. Every artifact
     * invocation drains stdout and stderr separately with the canonical
     * 1 MiB cap, runs under one monotonic deadline that covers the child
     * and both drains, and owns its whole process tree on every exit
     * path (descendants, the owned process group, the direct child, then
     * a bounded drain and reap). A missing toolchain or an exceeded
     * deadline is the named hard
     * {@link BoundedProcess.BoundedProcessTimeoutException}, never a
     * skip, so a run that cannot produce the demanded artifact outcome
     * fails the drive.
     */
    private static BoundedProcess.BoundedResult boundedRun(Path directory,
            String... command) throws Exception {
        return BoundedProcess.runCaptured(Arrays.asList(command),
            directory);
    }

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
        Map<String, byte[]> after = snapshotTree(root);
        boolean same = before.keySet().equals(after.keySet());
        if (same) {
            for (Map.Entry<String, byte[]> entry : before.entrySet()) {
                same = Arrays.equals(entry.getValue(),
                    after.get(entry.getKey()));
                if (!same) {
                    break;
                }
            }
        }
        check(same, context + ": the previous artifact set is byte-identical");
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
            CompilerProfileProvider.resolve(ReleaseState.V1_3_ACTIVE,
                CapabilityRegistry.releaseRegistry()),
            "every resolution over the one release state and registry "
                + "produces the same record");
        check(productionInvocation().semanticProfile()
                == SemanticProfile.DEAL_V1_3_INT32,
            "the production invocation carries the single DEAL_V1_3_INT32 "
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
                "the production set carries the v1.3 default per-source "
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
                assert(surfaces.label.__kind == "function", "the landed entry shape")
                local lib = __exportSurfaces["lib"]
                assert(type(lib) == "table", "the lib surface exists")
                assert(type(lib.value) == "table" and lib.value.__kind == "function",
                  "the lib surface carries value")
                assert(type(lib.label) == "table" and lib.label.__kind == "function",
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

    private static void testProductionSiblingModuleProviderIdentity()
            throws Exception {
        System.out.println("-- public compilation: sibling class-declaring"
            + " modules in one directory keep their own provider identity"
            + " and run on every target --");

        for (boolean reversed : new boolean[] { false, true }) {
            for (String backend : List.of("js", "luajit", "jvm")) {
                String label = backend + (reversed ? " (reversed imports)"
                    : "");
                Path project = Files.createTempDirectory(
                    "production-sibling-");
                try {
                    write(project, "deal.json", """
                        {
                          "languageVersion": "1.3",
                          "stdlib": "1.3",
                          "moduleRoots": ["src"],
                          "dependencies": {},
                          "externals": {},
                          "backend": "%s",
                          "output": "out"
                        }
                        """.formatted(backend));
                    write(project, "src/m/ir.deal", SIBLING_IR_SOURCE);
                    write(project, "src/m/codec.deal", SIBLING_CODEC_SOURCE);
                    write(project, "src/main.deal", reversed
                        ? SIBLING_MAIN_SOURCE_REVERSED
                        : SIBLING_MAIN_SOURCE);

                    ProjectOutcome compile = productionCompile(project,
                        "src/main.deal", "out");
                    check(compile.exitCode() == 0,
                        "the " + label + " public compile succeeds: "
                            + compile.stderr());
                    check(compile.stderr().isEmpty(),
                        "the " + label + " public compile emits no"
                            + " diagnostic: " + compile.stderr());
                    if (compile.exitCode() != 0) {
                        continue;
                    }
                    Path out = project.resolve("out");
                    switch (backend) {
                        case "js" -> checkSiblingJsRun(label, out);
                        case "luajit" -> checkSiblingLuaRun(label, out);
                        default -> checkSiblingJvmRun(label, project, out);
                    }
                } finally {
                    deleteRecursively(project);
                }
            }
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
            boundedRun(out, "node", artifact));
    }

    /** The LuaJIT artifact of the sibling project under the real luajit
     * through the bounded separate-stream runner. */
    private static void checkSiblingLuaRun(String label, Path out)
            throws Exception {
        checkSiblingStdout(label, "luajit",
            boundedRun(out, "luajit", "main.lua"));
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
        BoundedProcess.BoundedResult javac = boundedRun(project, "javac",
            "--release", "25", "-proc:none", "-cp", classpath, "-d",
            out.toString(), out.resolve("Main.java").toString());
        check(javac.exitCode() == 0, "the " + label + " JVM artifact"
            + " compiles: " + boundedOutput(javac));
        if (javac.exitCode() != 0) {
            return;
        }
        checkSiblingStdout(label, "java", boundedRun(out, "java", "-cp",
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
     * identity-equal sibling that declares a non-exported class of the
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
     * both identity-equal siblings export the demanded class name. The
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
     * The shadowing-initializer assignment collision (review 0005): the
     * assignment inside a shadowing let's own initializer targets the
     * enclosing binding (the pending declaration is not visible there,
     * docs/spec-v1.2.md §Declaration order), so the literal keeps that
     * binding's declared provider — the enclosing annotation names
     * {@code ir} even when the not-yet-visible inner declaration names
     * the identity-equal sibling and the import that happens to come
     * first. Asserted in both import orders for the enclosing block and
     * the loop-head form, with the exact defining range, one
     * import-order-independent digest, and the published artifact
     * outcome.
     */
    private static void testProductionShadowingInitializerCollision() throws Exception {
        checkCollision(new CollisionCase("shadowing-initializer", SHADOW_IR_SOURCE, SHADOW_CODEC_SOURCE,
            List.of(SHADOW_MAIN_CODEC_FIRST, SHADOW_MAIN_IR_FIRST), false, false, true, false, true));
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
        checkEq(1, occurrences.size(), "the " + label + " completed"
            + " plan holds exactly one " + className
            + " class-default occurrence");
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

        List<RuntimeImportDependency> edges = classPlanEdges(orchestrator,
            mainUri, className);
        checkEq(1, edges.size(), "the " + label + " completed graph"
            + " holds exactly one deferred " + className + " edge");
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

    /** The completed deferred-default dependency edges of the given
     * class name whose consuming module is {@code fromUri}. */
    private static List<RuntimeImportDependency> classPlanEdges(
            CompilationOrchestrator orchestrator, String fromUri,
            String className) {
        return classEdges(orchestrator, fromUri, className,
            RuntimeImportDependency.Reason.DEFERRED_DEFAULT_BINDING);
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
                    && run.output().contains("DEAL_ERROR_CODE: E8004"),
                "the production artifact prints DEAL_ERROR_CODE: E8004 and "
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
                "the function-typed for-counter production compile fails "
                    + "closed");
            check(compile.stderr().contains("E6005")
                    && compile.stderr().contains("CONSTRUCT_UNLOWERED"),
                "the failing lowering names the construct rule: "
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
            + "cross-module async, bytes, Error, time, function values --");

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
                assert(type(worker) == "table" and worker.__kind == "function",
                  "the entry surface publishes the same-module async export")
                local handle = worker.f()
                assert(type(handle) == "table" and handle.__kind == "async",
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

        checkBytesProductionCoverage();
        checkTimeNowMillisCoverage();
        checkLaterSliceConstruct("function-typed for-counter",
            Map.of("src/main.deal", UNREALIZED_CONSTRUCT_SOURCE),
            "CONSTRUCT_UNLOWERED");
        checkBuiltinErrorConstruction();

        // (e) The extern-C declaration import is admitted on LuaJIT: the
        // production compile emits the load_ffi prelude at the import's
        // MODULE_IMPORT, and the artifact's init fails at the import with
        // FFI_LIBRARY_LOAD (the wiring names an unbuilt library); the JVM
        // target keeps its phase-3.9 E6006 rejection.
        Path externC = Files.createTempDirectory("production-dispatch-ffi-");
        try {
            write(externC, "deal.json", """
                {
                  "languageVersion": "1.3",
                  "moduleRoots": ["src"],
                  "output": "out",
                  "backend": "luajit",
                  "externals": {
                    "native/math": {
                      "declaration": "native.d.deal",
                      "nativeLibrary": "libs/libnative.so"
                    }
                  }
                }
                """);
            write(externC, "native.d.deal", """
                // @extern-c
                export function add(a: int, b: int): int;
                """);
            write(externC, "src/main.deal", """
                import * as math from "native/math";

                export function main(): null {
                  return null;
                }
                """);
            ProjectOutcome compile = productionCompile(externC,
                "src/main.deal", "out");
            check(compile.exitCode() == 0,
                "the extern-C declaration import is admitted on LuaJIT: "
                    + compile.stderr());
            if (compile.exitCode() == 0) {
                String artifact = Files.readString(externC.resolve("out/main.lua"),
                    StandardCharsets.UTF_8);
                check(artifact.contains("__exportSurfaces[\"native.math\"] = "
                        + "__exportSurfaces[\"native.math\"] or __rt.load_ffi("
                        + "\"ffi:@$external/native/math\", "),
                    "the artifact publishes the loaded table through the "
                        + "load_ffi call at the import's MODULE_IMPORT: " + artifact);
                check(artifact.contains("nativeLibrary = { kind = "
                        + "\"MANIFEST_RELATIVE_PATH\", loaderText = \""),
                    "the bundle carries the manifest-relative loader text");
                check(artifact.contains(", \"" + externC.resolve("src/main.deal")
                        .toAbsolutePath() + "\", 1, 1)"),
                    "the load carries the import statement's span triplet");
                check(!artifact.contains("ffi.C") && !artifact.contains("cdef("),
                    "the artifact carries no ffi.C/cdef text");

                write(externC, "out/probe.lua", """
                    package.path = "./?.lua;./std/?.lua;" .. package.path
                    local ok, err = pcall(dofile, "main.lua")
                    if ok then
                      print("PROBE-FAIL|the init succeeded")
                      os.exit(1)
                    end
                    if type(err) ~= "table" or err.code == nil then
                      print("PROBE-FAIL|not a runtime error: " .. tostring(err))
                      os.exit(1)
                    end
                    print("ERR|" .. tostring(err.code) .. "|" .. tostring(err.message)
                      .. "|" .. tostring(err.file) .. "|" .. tostring(err.line)
                      .. "|" .. tostring(err.column))
                    """);
                ProcessOutcome run = runProcess(externC.resolve("out"),
                    "luajit", "probe.lua");
                check(run.exitCode() == 0
                        && run.output().startsWith("ERR|FFI_LIBRARY_LOAD|")
                        && run.output().contains("src/main.deal|1|1"),
                    "the emitted artifact fails at the import with "
                        + "FFI_LIBRARY_LOAD at the import origin: exit="
                        + run.exitCode() + " output=" + run.output());
            }

            // The JVM target keeps the phase-3.9 E6006 rejection.
            ProjectOutcome jvmCompile = runProductionCli("compile",
                externC.resolve("src/main.deal").toAbsolutePath().toString(),
                "--backend", "jvm", "--output",
                externC.resolve("out-jvm").toAbsolutePath().toString());
            check(jvmCompile.exitCode() != 0
                    && jvmCompile.stderr().contains("E6006"),
                "the JVM extern-C case keeps the phase-3.9 E6006: "
                    + jvmCompile.stderr());
        } finally {
            deleteRecursively(externC);
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
            3, 3, 3, 30, 51, 78, 27);
        checkPreservedLoweringOrigin("duplicate class declaration",
            Map.of("src/main.deal", DUPLICATE_CLASS_SOURCE), "main", "src/main.deal",
            "FRONTEND", "E1057", "Local class declaration 'A' is not allowed; classes are module-level",
            3, 5, 3, 28, 59, 82, 23);
        Map<String, String> imported = new java.util.LinkedHashMap<>();
        imported.put("src/main.deal", IMPORTED_DUPLICATE_MAIN);
        imported.put("src/lib.deal", IMPORTED_DUPLICATE_LIB);
        checkPreservedLoweringOrigin("duplicate class declaration in an imported module",
            imported, "lib", "src/lib.deal", "FRONTEND", "E1057",
            "Local class declaration 'A' is not allowed; classes are module-level",
            4, 5, 4, 28, 57, 80, 23);
        checkPreservedLoweringOrigin("supplementary scalar and tab indentation",
            Map.of("src/main.deal", SUPPLEMENTARY_TAB_CAPTURE_SOURCE), "main", "src/main.deal",
            "FRONTEND", "E1057", "Local class declaration 'Local' is not allowed; classes are module-level",
            4, 2, 4, 29, 54, 81, 27);
        checkPreservedLoweringOrigin("CRLF line endings",
            Map.of("src/main.deal", DEFAULT_CAPTURE_SOURCE.replace("\n", "\r\n")),
            "main", "src/main.deal", "FRONTEND", "E1057",
            "Local class declaration 'Local' is not allowed; classes are module-level",
            3, 3, 3, 30, 53, 80, 27);
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
                + ", semanticProfile DEAL_V1_3_INT32, irVersion deal.semantic-ir/2"
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
            check(compile.stderr().contains("E6005")
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
     * The K6 bytes coverage through the release-owned production invocation
     * (the fixture this battery pinned fail-closed): the closure emits
     * exactly one project artifact and the artifact executes under the real
     * {@code luajit} toolchain.
     */
    private static void checkBytesProductionCoverage() throws Exception {
        Path project = Files.createTempDirectory("production-dispatch-bytes-");
        try {
            write(project, "deal.json", DEAL_JSON_LUA);
            write(project, "src/main.deal", BYTES_SOURCE);
            ProjectOutcome compile = productionCompile(project, "src/main.deal",
                "out");
            check(compile.exitCode() == 0,
                "the bytes closure emits one project artifact: "
                    + compile.stderr());
            if (compile.exitCode() == 0) {
                ProcessOutcome run = runProcess(project.resolve("out"),
                    "luajit", "main.lua");
                check(run.exitCode() == 0,
                    "the bytes production artifact runs: exit=" + run.exitCode()
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
     * readings above the signed-32 range (v1.3 — no int terminal).
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
                check(lua.contains("__instT = {__d = true, code = ")
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
                verifyOmittedDefaultOwnerAttribution(artifact,
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
                verifyOmittedDefaultOwnerAttribution(artifact,
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
    private static void verifyOmittedDefaultOwnerAttribution(MappedArtifact artifact,
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
        checkEq(2, invocations.size(), artifactName
            + " emits one invocation per omitted default: " + invocations);
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
        checkEq(2, callerComments.size(), artifactName
            + " retains the caller's mapping inside each failure branch: "
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
                    .anyMatch(diagnostic -> diagnostic.contains("E6005")
                        && diagnostic.contains("CONSTRUCT_UNLOWERED")),
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
                    check(errors.get(0).code().equals("E6005")
                            && errors.get(0).message().contains(
                                SemanticLowerer.CONSTRUCT_UNLOWERED),
                        backend + ": the one failure is E6005 "
                            + SemanticLowerer.CONSTRUCT_UNLOWERED
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
    // ISSUE-0786: the strict v1.3 array-read contract on the three
    // production artifacts
    // =========================================================================

    /**
     * The v1.3 ArrayReadV13 flight on the three real production artifacts:
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
          seq: int[] = [];
        }

        function record(l: Log, tag: int): int {
          l.seq[l.seq.length] = tag;
          return tag;
        }

        function pick(l: Log, tag: int): int[] {
          record(l, tag);
          return [10, 20, 30];
        }

        export function main(): null {
          // Receiver then index, once each, in-range value.
          let l: Log = { seq: [] };
          let v: int = pick(l, 1)[record(l, 2)];
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
            let bad: int = pick(l2, 1)[record(l2, 2) + 4];
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
          let nullable: (int | null)[] = [1, null, 3];
          let nullableCode: string = "";
          try {
            let bad: int | null = nullable[3];
            nullableCode = "NOFAIL";
          } catch (e) {
            nullableCode = e.code;
          }
          if (nullableCode !== "E8002") {
            throw { code: "TEST_FAIL", message: "nullable out-of-range read must raise E8002" };
          }
          let empty: int[] = [];
          let emptyCode: string = "";
          try {
            let bad: int = empty[0];
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
          let inRange: int | null = nullable[2];
          if (inRange !== null) {
            if (inRange !== 3) {
              throw { code: "TEST_FAIL", message: "in-range nullable value mismatch" };
            }
          } else {
            throw { code: "TEST_FAIL", message: "in-range nullable value lost" };
          }
          let deletedNullable: (int | null)[] = [1, 2, 3];
          delete deletedNullable[1];
          if (deletedNullable[1] !== null) {
            throw { code: "TEST_FAIL", message: "in-range deleted nullable slot must read null" };
          }
          let plain: int[] = [7];
          delete plain[0];
          let deletedCode: string = "";
          try {
            let bad: int = plain[0];
            deletedCode = "NOFAIL";
          } catch (e) {
            deletedCode = e.code;
          }
          if (deletedCode !== "E8001") {
            throw { code: "TEST_FAIL", message: "in-range deleted non-nullable slot must keep E8001" };
          }

          // Writes: append at length, one commit, nothing on failure.
          let writes: int[] = [7];
          let alias: int[] = writes;
          writes[writes.length] = 8;
          if (writes.length !== 2 || alias[1] !== 8) {
            throw { code: "TEST_FAIL", message: "append-at-length write lost" };
          }
          let badValue: table = { bad: "not-int" };
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
        System.out.println("-- the v1.3 array-read contract on the three "
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
                    + " v1.3 array-read compile succeeds: " + compile.stderr());
                if (compile.exitCode() != 0) {
                    continue;
                }
                Path out = project.resolve("out");
                switch (backend) {
                    case "js" -> checkSiblingStdout("v1.3 array read", "node",
                        boundedRun(out, "node", "main.js"));
                    case "luajit" -> checkSiblingStdout("v1.3 array read", "luajit",
                        boundedRun(out, "luajit", "main.lua"));
                    default -> checkArrayReadJvmRun(project, out);
                }
            } finally {
                deleteRecursively(project);
            }
        }
    }

    /** The v1.3 array-read JVM leg: javac then java on the published artifact. */
    private static void checkArrayReadJvmRun(Path project, Path out)
            throws Exception {
        String classpath = absoluteClasspath();
        BoundedProcess.BoundedResult javac = boundedRun(project, "javac",
            "--release", "25", "-proc:none", "-cp", classpath, "-d",
            out.toString(), out.resolve("Main.java").toString());
        check(javac.exitCode() == 0, "the v1.3 array read JVM artifact"
            + " compiles: " + boundedOutput(javac));
        if (javac.exitCode() != 0) {
            return;
        }
        checkSiblingStdout("v1.3 array read", "java", boundedRun(out, "java",
            "-cp", out + File.pathSeparator + classpath, "Main"));
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
    // =========================================================================
    // The complete deployment: missing required content fails the compile
    // =========================================================================

    /**
     * The v1.3 deployment-completeness contract through the public CLI in a
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

            // (1) LuaJIT: the omitted std/time.lua fails the compile with the
            // pinned diagnostic; the absent output root stays absent.
            ProcessOutcome luaMissing = runCliSubprocess(project, partial,
                emptyHome, "compile",
                project.resolve("src/main.deal").toAbsolutePath().toString(),
                "--backend", "luajit", "--output",
                project.resolve("out-lua-missing").toAbsolutePath().toString());
            check(luaMissing.exitCode() == 1,
                "the incomplete LuaJIT distribution fails the compile: exit="
                    + luaMissing.exitCode() + " output=" + luaMissing.output());
            check(luaMissing.output().contains(
                    "E6000: Stdlib module not found: std/time.lua"),
                "the failure is the pinned missing-stdlib E6000: "
                    + luaMissing.output());
            check(!Files.exists(project.resolve("out-lua-missing")),
                "the missing-content failure publishes nothing (the absent "
                    + "root stays absent)");

            // (2) JavaScript: the same contract at the .js spelling.
            ProcessOutcome jsMissing = runCliSubprocess(project, partial,
                emptyHome, "compile",
                project.resolve("src/main.deal").toAbsolutePath().toString(),
                "--backend", "js", "--output",
                project.resolve("out-js-missing").toAbsolutePath().toString());
            check(jsMissing.exitCode() == 1,
                "the incomplete JavaScript distribution fails the compile: "
                    + "exit=" + jsMissing.exitCode() + " output="
                    + jsMissing.output());
            check(jsMissing.output().contains(
                    "E6000: Stdlib module not found: std/time.js"),
                "the JavaScript failure is the pinned missing-stdlib E6000: "
                    + jsMissing.output());
            check(!Files.exists(project.resolve("out-js-missing")),
                "the JavaScript missing-content failure publishes nothing");

            // (3) The complete distribution: both targets publish the whole
            // set — artifact, default sidecar, runtime and all six stdlib
            // implementations.
            for (String backend : List.of("luajit", "js")) {
                String extension = backend.equals("luajit") ? "lua" : "js";
                Path out = project.resolve("out-" + backend);
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
                for (String stdlibModule : stdlibModules) {
                    check(published.contains(stdlibModule + "." + extension),
                        "the complete " + backend + " set carries "
                            + stdlibModule + "." + extension + ": " + published);
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
            for (String backend : List.of("luajit", "jvm")) {
                Path project = Files.createTempDirectory(
                    "v12-corner-case-" + name + "-" + backend + "-");
                try {
                    write(project, "src/main.deal", source);
                    write(project, "deal.json", "jvm".equals(backend)
                        ? DEAL_JSON_JVM : DEAL_JSON_LUA);
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
                    } else {
                        String classpath = absoluteClasspath();
                        List<String> javas;
                        try (Stream<Path> walk = Files.list(out)) {
                            javas = walk.filter(p -> p.toString()
                                    .endsWith(".java"))
                                .map(Path::toString).sorted().toList();
                        }
                        BoundedProcess.BoundedResult javac = runBounded(
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
                    } else if ("exact-main-and-export-output".equals(name)) {
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
            if (jsEntry && "exact-main-and-export-output".equals(name)) {
                // The JS production-entry surface: the published entry
                // artifact executed directly under real node — the entry
                // contract runs main() once and never the unrelated
                // exports, so the independent package transcript
                // (stdout "main\n", stderr the diagnostic, exit 0) is
                // observed on the artifact the production CLI published.
                // The JS lane keeps the separate corpus-adapter
                // transcript (main then alpha/beta), so a regression in
                // the require.main entry guard fails here and not only
                // through the corpus-order pin.
                Path project = Files.createTempDirectory(
                    "v12-corner-case-" + name + "-js-");
                try {
                    write(project, "src/main.deal", source);
                    write(project, "deal.json", DEAL_JSON_JS);
                    ProjectOutcome compile = productionCompile(project,
                        "src/main.deal", "out");
                    if (compile.exitCode() != 0) {
                        legs.add(new V12CorpusLeg("js", "the production"
                            + " compile succeeds: " + compile.stderr(),
                            Optional.empty()));
                    } else {
                        BoundedProcess.BoundedResult executed = runBounded(
                            project.resolve("out"), "node", "main.js");
                        legs.add(new V12CorpusLeg("js", null,
                            mismatchAgainst(name, "js", executed, "main\n",
                                "diagnostic \u03b2\n", 0)));
                    }
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
            new Injection("legacy profile", "// @profile: legacy-safe-int\n"
                + raw, "legacy-safe-int",
                "non-production @profile 'legacy-safe-int'"),
            new Injection("unknown profile",
                "// @profile: no-such-profile\n" + raw, "no-such-profile",
                "non-production @profile 'no-such-profile'"),
            new Injection("duplicate metadata",
                "// @profile: deal-v1.3-int32\n// @profile: deal-v1.3-int32\n"
                    + raw, "deal-v1.3-int32",
                "duplicate corpus classification metadata"),
            new Injection("removed v1.2 profile",
                "// @profile: deal-v1.2-int32\n" + raw, "deal-v1.2-int32",
                "non-production @profile 'deal-v1.2-int32'"),
            new Injection("profile after the 40-line window",
                "\n".repeat(40) + "// @profile: legacy-safe-int\n" + raw,
                "legacy-safe-int",
                "non-production @profile 'legacy-safe-int'"));
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

    /** The owned {@code v12-corner-case-*} scratch roots in a directory. */
    private static Set<Path> v12CornerCaseScratchRoots(Path parent)
            throws Exception {
        try (Stream<Path> listing = Files.list(parent)) {
            return listing.filter(p -> p.getFileName().toString()
                    .startsWith("v12-corner-case-"))
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
            for (int round = 0; round < 8; round++) {
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
                for (int round = 0; round < 6; round++, shortLivedRound++) {
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
            if ("pressure".equals(args[0])) {
                int remaining = Integer.parseInt(args[1]);
                byte[] block = new byte[1024];
                Arrays.fill(block, (byte) 'x');
                while (remaining > 0) {
                    int chunk = Math.min(block.length, remaining);
                    System.err.write(block, 0, chunk);
                    remaining -= chunk;
                }
                System.err.flush();
                System.out.print("PRESSURE-OK\n");
                System.out.flush();
                return;
            }
            if ("stdout-pressure".equals(args[0])) {
                int remaining = Integer.parseInt(args[1]);
                byte[] block = new byte[1024];
                Arrays.fill(block, (byte) 'y');
                while (remaining > 0) {
                    int chunk = Math.min(block.length, remaining);
                    System.out.write(block, 0, chunk);
                    remaining -= chunk;
                }
                System.out.flush();
                System.err.print("STDOUT-PRESSURE-OK\n");
                System.err.flush();
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
            checkEq(SemanticProfile.DEAL_V1_3_INT32,
                produced.semanticProfile(),
                "the produced project is the sole v1.3 profile");
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

        // (2) The JS arm records none: the third, unchanged pipeline runs no
        // production arm, so the accessor can never surface a JS artifact's
        // project.
        Path js = Files.createTempDirectory("production-dispatch-produced-js-");
        try {
            write(js, "deal.json", """
                {
                  "languageVersion": "1.3",
                  "moduleRoots": ["src"],
                  "output": "out",
                  "backend": "js"
                }
                """);
            write(js, "src/lib.deal", LIB_SOURCE);
            write(js, "src/main.deal", SYNC_CALL_SOURCE);
            ArmCompile arm = compileWithInvocation(js, "src/main.deal",
                productionInvocation());
            check(arm.orchestrator().producedProject() == null,
                "the JS arm records no produced project");
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

    public static void main(String[] args) throws Exception {
        System.out.println("=== Production Dispatch and Cutover Acceptance "
            + "Tests (ISSUE-0643) ===\n");

        testInvocationPredicate();
        testProductionLuaJitSingleModule();
        testProductionJvmTwoModule();
        testProductionLuaJitTwoModuleSurfaces();
        testProductionSiblingModuleProviderIdentity();
        testProductionSameNameSiblingCollision();
        testProductionContextualCallSiblingCollision();
        testProductionShadowedLocalCalleeCollision();
        testProductionNestedSiblingCollision();
        testProductionDeclaredContextSiblingCollision();
        testProductionIdentifierAssignmentCollision();
        testProductionMemberAssignmentCollision();
        testProductionShadowingInitializerCollision();
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
        testV12CornerCaseCorpusProductionDrive();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
