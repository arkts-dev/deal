package deal.test;

import deal.ast.ClassField;
import deal.ast.ExpressionNode;
import deal.ast.ObjectLiteralExpr;
import deal.checker.BuiltinErrorDeclaration;
import deal.checker.CheckResult;
import deal.codegen.Backend;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.identity.CanonicalModuleIdentity;
import deal.module.CompilationOrchestrator;
import deal.module.RuntimeImportDependency;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CheckedModuleInput;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CheckedProjectInput;
import deal.semantic.ClassConstructionValidator;
import deal.semantic.ClassRegistrationSeeds;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.HostDeclarationSurface;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticOracle;
import deal.semantic.SemanticRequirementManifest;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.AdaptSourceRef;
import deal.semantic.ir.BindingGeneration;
import deal.semantic.ir.LoweredFunction;
import deal.semantic.ir.OperationContractSnapshot;
import deal.semantic.ir.RawOp;
import deal.semantic.ir.RawBinding;
import deal.semantic.ir.RawOrigin;
import deal.semantic.ir.AddressChainProtocol;
import deal.semantic.ir.AnchorId;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BindingCellKind;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.ClassFactoryId;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.ClassBindingFacts;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ClassInterface;
import deal.semantic.ir.ClassLayout;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.ConstructionProvider;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.ExportPlan;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.ExportInterface;
import deal.semantic.ir.ExternalModuleKind;
import deal.semantic.ir.FieldInterface;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FunctionAllocationIdentity;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.LoweringContextHash;
import deal.semantic.ir.LoweringFailureDetail;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ModuleImportKind;
import deal.semantic.ir.ModuleInitPlan;
import deal.semantic.ir.NamespaceRegistration;
import deal.semantic.ir.NamespaceRegistrations;
import deal.semantic.ir.OpId;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.RawUnit;
import deal.semantic.ir.RawUnitMaterializer;
import deal.semantic.ir.ResolvedImport;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticIdAllocator;
import deal.semantic.ir.SemanticIrDumper;
import deal.semantic.ir.SemanticIrTextDecodeException;
import deal.semantic.ir.SemanticIrValidator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.SharedFactoryFacts;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;
import deal.types.Type;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

public class ProjectLoweringTest {

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

    private static void checkEq(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual),
            message + " (expected " + expected + ", got " + actual + ")");
    }

    /**
     * Asserts that the action rejects with an
     * {@link IllegalArgumentException} — the producer-defect contract of
     * the lowering-result boundaries.
     */
    private static void expectIllegalArgument(Runnable action, String message) {
        try {
            action.run();
            fail(message + " (expected IllegalArgumentException, got no failure)");
        } catch (IllegalArgumentException expected) {
            passed++;
        } catch (RuntimeException other) {
            fail(message + " (expected IllegalArgumentException, got "
                + other.getClass().getSimpleName() + ": " + other.getMessage()
                + ")");
        }
    }

    // =========================================================================
    // Fixtures
    // =========================================================================

    private static final ModuleId UTIL = new ModuleId("util");
    private static final ModuleId APP = new ModuleId("app");
    private static final ModuleId HOST_CFG = new ModuleId("host.cfg");
    private static final CanonicalModuleIdentity HOST_CFG_IDENTITY =
        new CanonicalModuleIdentity.ExternalModule("host/cfg");
    private static final String HOST_CFG_SPECIFIER = "host/cfg";
    private static final ClassId ENDPOINT = new ClassId("$external/host/cfg",
        "Endpoint");
    private static final ClassId HOST_HIDDEN = new ClassId("$external/host/cfg",
        "Hidden");
    private static final ClassId BUILTIN_ERROR = ClassId.ERROR;

    private static final String UTIL_SOURCE = """
        export function add(a: number, b: number): number {
          return a + b;
        }

        export function twice(x: number): number {
          return x * 2;
        }

        // A module-level recursive group (the recursive-group arm's closed
        // member-body shape: value references only, never invoked from
        // source).
        function left(): null {
          let rightRef: () => null = right;
          return null;
        }
        function right(): null {
          let leftRef: () => null = left;
          return null;
        }
        """;

    private static final String HOST_DECLARATION = """
        export class Endpoint {
          path: string;
          port?: number;
        }

        export function version(): number;
        """;

    /**
     * The host declaration of the ISSUE-0810 re-entrant-default subject:
     * the host counter and the retained array provider the existing
     * corpus host fixture also implements.
     */
    private static final String RETAINED_DEFAULT_DECLARATION = """
        export function retained(): number[];
        export function provideFirst(): number[];
        export function bump(): null;
        export function calls(): number;
        """;

    /**
     * The ISSUE-0810 re-entrant-default subject: the class default's first
     * operand (the host counter read before the nested attempt) must keep
     * its own value when the nested construction inside {@code build}
     * re-enters the same default block. The correct composition is
     * {@code 0 * 1000 + 1100}; an overwritten operand composes the nested
     * attempt's counter read ({@code 1 * 1000 + 1100}) and throws.
     */
    private static final String DEFAULT_REENTRANCY_APP_SOURCE = """
        import * as console from "std/console";
        import * as state from "host/retained_default";

        class A {
          x: number;
        }

        function combine(a: number, b: number): number {
          return a * 1000 + b;
        }

        function build(): number {
          if (state.calls() >= 1) {
            return 100;
          }
          state.provideFirst();
          let inner: A = {x: combine(state.calls(), build())};
          return inner.x;
        }

        export function main(): null {
          let a: A = {x: combine(state.calls(), build())};
          if (a.x !== 1100) {
            throw { code: "OPERAND_OVERWRITE", message: "a nested attempt overwrote the enclosing operands" };
          }
          console.log("ok");
          return null;
        }
        """;

    private static final String DEFAULT_REENTRANCY_FAILURE_APP_SOURCE =
        failedNestedDefault(DEFAULT_REENTRANCY_APP_SOURCE, "inner attempt fails")
            .replace("return inner.x;", "return 5;")
            .replace("a.x !== 1100", "a.x !== 5")
            .replace("a nested attempt overwrote", "a failed nested attempt overwrote");

    /**
     * The ISSUE-0810 two-required-default subject: the class declares two
     * omitted required defaults and the second one constructs the class
     * again, so the nested attempt re-enters both default blocks. The
     * enclosing attempt's first default value ({@code 0}, the counter read
     * before the nested attempt) must survive the nested attempt, and the
     * enclosing attempt's second default still produces {@code 7}. A shared
     * default-result slot carries the nested attempt's {@code 1} into the
     * enclosing construction and fails the first tripwire.
     */
    private static final String DEFAULT_TWO_DEFAULTS_APP_SOURCE = """
        import * as console from "std/console";
        import * as state from "host/retained_default";

        class A {
          x: number;
          y: number;
        }

        function build(): number {
          if (state.calls() >= 1) {
            return 100;
          }
          state.provideFirst();
          let inner: A = {x: state.calls(), y: build()};
          return 7;
        }

        export function main(): null {
          let a: A = {x: state.calls(), y: build()};
          if (a.x !== 0) {
            throw { code: "ATTEMPT_VALUE_OVERWRITE", message: "a nested attempt overwrote the enclosing attempt's first default value" };
          }
          if (a.y !== 7) {
            throw { code: "ATTEMPT_VALUE_LOST", message: "the enclosing attempt's second default value is wrong" };
          }
          console.log("ok");
          return null;
        }
        """;

    private static final String DEFAULT_TWO_DEFAULTS_FAILURE_APP_SOURCE =
        failedNestedDefault(DEFAULT_TWO_DEFAULTS_APP_SOURCE, "the nested attempt fails")
            .replace("a nested attempt overwrote", "a failed nested attempt overwrote");

    /**
     * The provider module of the ISSUE-0810 imported-class subject: the
     * two-required-default class is imported by the consumer, so the
     * consumer's construction runs the owner's defaults-only transfer
     * (SHARED_FACTORY). The nested construction inside the second default is
     * the owner's own LOCAL construction of the same class, so both attempts
     * run the owner's same default blocks.
     */
    private static final String IMPORTED_DEFAULT_TWO_DEFAULTS_LIB_SOURCE = """
        import * as state from "host/retained_default";

        export class A {
          x: number;
          y: number;
        }

        export function build(): number {
          if (state.calls() >= 1) {
            return 100;
          }
          state.provideFirst();
          let inner: A = {x: state.calls(), y: build()};
          return 7;
        }
        """;

    private static final String IMPORTED_DEFAULT_TWO_DEFAULTS_FAILURE_LIB_SOURCE =
        failedNestedDefault(IMPORTED_DEFAULT_TWO_DEFAULTS_LIB_SOURCE,
            "the nested attempt fails");

    private static String failedNestedDefault(String source, String message) {
        return source.replace("return 100;",
                "throw { code: \"INNER_FAIL\", message: \"" + message + "\" };")
            .replace("  let inner: A = {x: combine(state.calls(), build())};",
                "  try {\n    let inner: A = {x: combine(state.calls(), build())};\n  } catch (e) {\n  }")
            .replace("  let inner: A = {x: state.calls(), y: build()};",
                "  try {\n    let inner: A = {x: state.calls(), y: build()};\n  } catch (e) {\n  }");
    }

    /** The consumer of the imported two-required-default class. */
    private static final String IMPORTED_DEFAULT_TWO_DEFAULTS_APP_SOURCE = """
        import * as console from "std/console";
        import * as lib from "lib";
        import * as state from "host/retained_default";

        export function main(): null {
          let a: lib.A = {x: state.calls(), y: lib.build()};
          if (a.x !== 0) {
            throw { code: "ATTEMPT_VALUE_OVERWRITE", message: "a nested attempt overwrote the transferred default value" };
          }
          if (a.y !== 7) {
            throw { code: "ATTEMPT_VALUE_LOST", message: "the transferred second default value is wrong" };
          }
          console.log("ok");
          return null;
        }
        """;

    /** The main fixture: source calls, an adapter, cross-module calls, and a host import. */
    private static final String APP_SOURCE = """
        import * as u from "util";
        import * as cfg from "host/cfg";

        function inc(x: number): number {
          return x + 1;
        }

        function helper(x: number): number {
          return u.add(x, 1);
        }

        export function main(): null {
          let f: (x: number) => number = inc;
          let h: (a: number, b: number) => number = f;
          let total: number = helper(2);
          let extended: number = h(3, 9);
          let doubled: number = u.twice(3);
          return null;
        }
        """;

    /** The host-declaration-class construction (the fail-closed acceptance). */
    private static final String HOST_CLASS_APP_SOURCE = """
        import * as u from "util";
        import * as cfg from "host/cfg";

        export function main(): null {
          let e: cfg.Endpoint = {path: "p"};
          return null;
        }
        """;

    /**
     * The host declaration with a class-typed field: {@code Endpoint.peer}
     * names the exported declaration class {@code Hidden} through its exact
     * carried class identity, so the seed descriptor's external spelling is
     * the only fact that distinguishes it from a same-rendering alternate.
     */
    private static final String HOST_CLASS_REFERENCE_DECLARATION = """
        export class Hidden {
          secret: string;
        }

        export class Endpoint {
          path: string;
          peer: Hidden;
        }

        export function version(): number;
        """;

    /**
     * The class-reference construction: only {@code Hidden} is constructed, so
     * the mutated {@code Endpoint.peer} seed reference has no referring
     * {@code CLASS_NEW} and only the seed/interface coherence rule can reject
     * it (the reviewer's exact reproducer).
     */
    private static final String HOST_CLASS_REFERENCE_APP_SOURCE = """
        import * as cfg from "host/cfg";

        export function main(): null {
          let h: cfg.Hidden = {secret: "s"};
          return null;
        }
        """;

    /** The builtin-Error construction (the production-outcome acceptance). */
    private static final String ERROR_APP_SOURCE = """
        import * as cfg from "host/cfg";

        export function main(): null {
          let e: Error = {code: "E1", message: "boom"};
          return null;
        }
        """;

    /**
     * The in-project class arm: an implementation module declares an
     * exported class and a local class and constructs both, so the unified
     * walk's class-declaration arm, its class-literal arm, and the exported
     * variant's {@code CLASS_FACTORY} run in one session with the call and
     * closure arms.
     */
    private static final String CLASS_UTIL_SOURCE = """
        export class Point {
          x: number;
          y: number;
        }

        class Hidden {
          label: string;
        }

        export function makePoint(x: number): Point {
          let p: Point = {x: x, y: 1};
          let h: Hidden = {label: "local"};
          return p;
        }
        """;

    private static final String CLASS_APP_SOURCE = """
        import * as u from "util";

        export function main(): null {
          let p: u.Point = u.makePoint(4);
          return null;
        }
        """;

    private static final String IMPORTED_CLASS_UTIL_SOURCE = """
        export class Point {
          x: number;
          y: number;
        }

        export class Segment {
          length: number;
        }

        export function makePoint(x: number): Point {
          return {x: x, y: 1};
        }
        """;

    private static final String IMPORTED_CLASS_APP_SOURCE = """
        import * as u from "util";

        export function main(): null {
          let p: u.Point = {x: 5, y: 1};
          let s: u.Segment = {length: 3};
          let q: u.Point = u.makePoint(7);
          return null;
        }
        """;


    /**
     * The same-spelled provider pair of distinct module identities (the
     * ISSUE-0809 acceptance): two modules declare a class named
     * {@code Point} with different declared fields; the consuming module
     * imports both and constructs, reads, writes and deletes through the
     * exact provider each spelling names.
     */
    private static final String SAME_SPELLED_ALPHA_SOURCE = """
        // @jsonable
        export class Point {
          x: number;
          nick?: string;
        }
        """;

    private static final String SAME_SPELLED_BETA_SOURCE = """
        // @jsonable
        export class Point {
          y: string;
        }
        """;

    /**
     * The consumer of the same-spelled pair: the construction, field and
     * generated JSON links of both distinct providers (the nested
     * class-valued {@code Wrap} fields) resolve the exact declared
     * provider.
     */
    private static final String SAME_SPELLED_APP_SOURCE = """
        import * as alpha from "./alpha/util";
        import * as beta from "./beta/util";

        // @jsonable
        export class Wrap {
          a: alpha.Point;
          b: beta.Point;
        }

        export function main(): null {
          let a: alpha.Point = { x: 1 };
          a.x = 5;
          a.nick = "n";
          let hadNick: boolean = has(a.nick);
          delete a.nick;
          if (a.x !== 5) { throw { code: "WRONG_X", message: "x" }; }
          if (!hadNick || has(a.nick)) {
            throw { code: "WRONG_PRESENCE", message: "nick" };
          }
          let b: beta.Point = { y: "s" };
          let xs: beta.Point[] = [{ y: "a" }];
          if (xs[0].y !== "a") { throw { code: "WRONG_ARRAY", message: "array" }; }
          let n: alpha.Point | null = { x: 2 };
          if (n === null) { throw { code: "WRONG_NULLABLE", message: "n" }; }
          let w: Wrap = {a: {x: 1}, b: {y: "s"}};
          let json: string = Wrap$toJson(w);
          if (json !== "{\\\"a\\\":{\\\"x\\\":1.0},\\\"b\\\":{\\\"y\\\":\\\"s\\\"}}") {
            throw { code: "WRONG_JSON", message: json };
          }
          let back: Wrap | null = Wrap$fromJson(json);
          if (back !== null) {
            if (back.a.x !== 1 || back.b.y !== "s") {
              throw { code: "WRONG_JSON_FIELDS", message: "back" };
            }
          } else {
            throw { code: "WRONG_JSON_NULL", message: "back" };
          }
          return null;
        }
        """;

    /**
     * Two same-named sibling declarations ({@code src/m/ir.deal} and
     * {@code src/m/codec.deal} both export {@code Ir} with different
     * defaults) carry distinct canonical identities, so the recorded
     * contextual declaration — never a name-first match — must select the
     * exact declaration of every construction.
     */
    private static final String SIBLING_IR_SOURCE = """
        export class Ir { a: number; }
        export function useIr(x: Ir): number { return x.a; }
        """;

    private static final String SIBLING_CODEC_SOURCE = """
        export class Ir { a: number; }
        export class Codec { b: number; }
        export function useCodec(y: Codec): number { return y.b; }
        """;

    private static final String SIBLING_APP_SOURCE = """
        import * as ir from "./m/ir";
        import * as codec from "./m/codec";

        class Holder {
          inner: ir.Ir;
          items: ir.Ir[];
        }

        export function main(): null {
          let direct: ir.Ir = {a: 7};
          if (direct.a !== 7) { throw { code: "WRONG_DIRECT", message: "a" }; }
          if (ir.useIr({a: 7}) !== 7) { throw { code: "WRONG_CALL", message: "a" }; }
          let h: Holder = {inner: {a: 7}, items: [{a: 7}]};
          if (h.inner.a !== 7) { throw { code: "WRONG_NESTED", message: "a" }; }
          if (h.items[0].a !== 7) { throw { code: "WRONG_ARRAY", message: "a" }; }
          let xs: ir.Ir[] = [{a: 7}];
          if (xs[0].a !== 7) { throw { code: "WRONG_LITERAL_ARRAY", message: "a" }; }
          return null;
        }
        """;

    /**
     * The same-named sibling pair's field operations: the {@code ir}
     * sibling's optional field and the {@code codec} sibling's required
     * field, plus a same-named local spelling in the consumer's own
     * directory ({@code src/other.deal} and the consumer's {@code Point}
     * are two identities): every field read, write and delete selects the
     * exact declaring provider.
     */
    private static final String FIELD_SIBLING_IR_SOURCE = """
        export class Ir { a: number; nick?: string; }
        """;

    private static final String FIELD_SIBLING_CODEC_SOURCE = """
        export class Ir { nick: number; }
        """;

    private static final String FIELD_SIBLING_OTHER_SOURCE = """
        export class Point { nick: number; }
        """;

    private static final String FIELD_SIBLING_APP_SOURCE = """
        import * as ir from "./m/ir";
        import * as codec from "./m/codec";
        import * as other from "./other";

        class Point { nick: string; }

        export function main(): null {
          let x: ir.Ir = {a: 7};
          x.a = 5;
          if (x.a !== 5) { throw { code: "WRONG_IR_READ", message: "a" }; }
          let had: boolean = has(x.nick);
          delete x.nick;
          if (had || has(x.nick)) { throw { code: "WRONG_IR_DELETE", message: "nick" }; }
          let y: other.Point = {nick: 1};
          y.nick = 2;
          if (y.nick !== 2) { throw { code: "WRONG_OTHER", message: "nick" }; }
          let p: Point = {nick: ""};
          if (p.nick !== "") { throw { code: "WRONG_LOCAL", message: "nick" }; }
          return null;
        }
        """;

    /**
     * The same-named sibling field fixture with the provider imports
     * reversed: the field links must not depend on the import order.
     */
    private static final String FIELD_SIBLING_APP_REVERSED_SOURCE =
        reverseProviderImports(FIELD_SIBLING_APP_SOURCE);

    /**
     * The same-named sibling JSON fixture: two same-named sibling jsonable
     * declarations and a consumer whose jsonable class carries a
     * class-valued field of the sibling identity. The generated JSON plan
     * table is keyed by the canonical class identity, so the nested link
     * cannot select the exact declaration and fails closed.
     */
    private static final String JSON_SIBLING_IR_SOURCE = """
        // @jsonable
        export class Ir { a: number; }
        """;

    private static final String JSON_SIBLING_CODEC_SOURCE = """
        // @jsonable
        export class Ir { a: string; }
        """;

    private static final String JSON_SIBLING_APP_SOURCE = """
        import * as ir from "./m/ir";
        import * as codec from "./m/codec";

        // @jsonable
        export class Wrap { item: ir.Ir; }

        export function main(): null {
          let w: Wrap = {item: {a: 7}};
          let s: string = Wrap$toJson(w);
          if (s === "") { throw { code: "WRONG", message: "s" }; }
          return null;
        }
        """;

    private static final String JSON_SIBLING_APP_REVERSED_SOURCE =
        reverseProviderImports(JSON_SIBLING_APP_SOURCE);

    /**
     * The single-plan same-named JSON fixture: the imported sibling
     * declares no {@code @jsonable} plan, so exactly one plan-bearing
     * declaration carries the canonical identity and the nested link is
     * exact (a same-named declaration without a generated plan
     * registers none and cannot collide with it).
     */
    private static final String SINGLE_PLAN_OTHER_SOURCE = """
        export class Point { nick: string; }
        """;

    private static final String SINGLE_PLAN_APP_SOURCE = """
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
          return null;
        }
        """;

    /**
     * The plan-imported/non-plan-local same-named fixture (review
     * cycle 4, finding 1): the imported sibling carries the only
     * plan-bearing ({@code @jsonable}) declaration while the unit-local
     * sibling declares no generated plan, so the generated plan table
     * carries the imported declaration's layout and the nested link
     * resolves it exactly — the local sibling's own layout never masks it.
     */
    private static final String PLAN_IMPORTED_OTHER_SOURCE = """
        // @jsonable
        export class Point { a: number; }
        """;

    /**
     * An earlier same-named non-plan sibling in the emission order (the
     * nested decode's factory-resolution probe): the oracle's nested
     * default filling must resolve the plan owner's CLASS_FACTORY, never
     * this sibling's.
     */
    private static final String PLAN_IMPORTED_CODEC_SOURCE = """
        export class Point { nick: string; }
        """;

    private static final String PLAN_IMPORTED_APP_SOURCE = """
        import * as codec from "./codec";
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
          let w2: Wrap | null = Wrap$fromJson("{\\\"p\\\":{\\\"a\\\":7}}");
          if (w2 !== null) {
            if (w2.p.a !== 7) { throw { code: "WRONG_A", message: "a" }; }
          } else {
            throw { code: "WRONG_NULL", message: "null" };
          }
          return null;
        }
        """;

    private static final String PLAN_IMPORTED_APP_NONEXPORTED_SOURCE =
        PLAN_IMPORTED_APP_SOURCE.replace("export class Point", "class Point");

    /**
     * The annotated-local + imported-callee-argument fixture (review
     * cycle 2, finding 1): the consumer declares its own {@code Ir} in the
     * sibling directory, so the annotation's construction context is a
     * same-module declaration while the literal is an argument of the
     * imported {@code ir.id}. The checker laid both literals out by the
     * consumer's own {@code Ir}; the per-construction default must be that
     * declaration's 5 — never the imported sibling's 7 or 93.
     */
    private static final String LOCAL_ATTRIBUTION_IR_SOURCE = """
        export class Ir { a: number; }
        export function id(x: Ir): Ir { return x; }
        """;

    private static final String LOCAL_ATTRIBUTION_CODEC_SOURCE = """
        export class Ir { a: number; }
        export function id(x: Ir): Ir { return x; }
        """;

    private static final String LOCAL_ATTRIBUTION_APP_SOURCE = """
        import * as ir from "./ir";
        import * as codec from "./codec";

        class Ir { a: number; }

        function id(x: Ir): Ir { return x; }

        class Holder { f: Ir; }

        export function main(): null {
          let p: Ir = id({a: 5});
          let q: Holder = {f: id({a: 5})};
          if (p.a !== 5) { throw { code: "WRONG_P", message: "p.a" }; }
          if (q.f.a !== 5) { throw { code: "WRONG_F", message: "q.f.a" }; }
          return null;
        }
        """;

    private static final String LOCAL_ATTRIBUTION_APP_REVERSED_SOURCE =
        reverseProviderImports(LOCAL_ATTRIBUTION_APP_SOURCE);

    /**
     * The annotated-import construction fixture: the annotation's
     * qualified spelling names {@code codec}'s declaration while the
     * callee parameter names the same declaration (a crossing callee
     * would be refused by the checker). The checker's recorded
     * construction context is the annotation's declaration, so the
     * construction must use codec's 93 — never the sibling ir's 7.
     */
    private static final String IMPORTED_CONTEXT_PROBE_SOURCE = """
        import * as ir from "./ir";
        import * as codec from "./codec";

        export function main(): null {
          let p: codec.Ir = codec.id({a: 93});
          if (p.a !== 93) { throw { code: "WRONG_CODEC", message: "p.a" }; }
          return null;
        }
        """;

    /**
     * The callee-parameter / declared-return context providers (review
     * cycle 5 finding 1): the imported implementation module declares
     * {@code Ir} with the default 7 and a function whose parameter and
     * return annotations name it.
     */
    private static final String PARAM_RETURN_PROVIDER_IR_SOURCE = """
        export class Ir { a: number; }
        export function useIr(x: Ir): number { return x.a; }
        """;

    /** A second same-named sibling provider (the differing default 93). */
    private static final String PARAM_RETURN_PROVIDER_CODEC_SOURCE = """
        export class Ir { a: number; }
        """;

    /**
     * The call-result receiver fixture (review cycle 1 findings 1 and 2):
     * the provider declares {@code Ir} with the default 7 (and the same
     * field name as the sibling, so only the exact provider's per-
     * construction default proves the selection), a factory function, and
     * an entry whose parameter names it.
     */
    private static final String CALL_RECEIVER_IR_SOURCE = """
        export class Ir { a: number; nick?: string; }
        export function make(): Ir { return { a: 7 }; }
        export function useIr(x: Ir): number { return x.a; }
        """;

    /** The same-named sibling of the call-receiver provider (93). */
    private static final String CALL_RECEIVER_CODEC_SOURCE = """
        export class Ir { a: number; nick: number; }
        """;

    /**
     * The call-result receiver consumer: the callee's declared return
     * carries the provider, so the chained read, the local wrapper's
     * call-result read, the inferred binding's read/write, the has/delete
     * decision and the function-typed argument literal all select that
     * declaration (7 and the written 11), never the sibling's 93.
     */
    private static final String CALL_RECEIVER_APP_SOURCE = """
        import * as ir from "./m/ir";
        import * as codec from "./m/codec";

        function wrap(): ir.Ir { return { a: 7 }; }

        export function main(): null {
          let v: number = ir.make().a;
          let f: (x: ir.Ir) => number = ir.useIr;
          let n: number = f({a: 7});
          let y = ir.make();
          y.a = 11;
          let w: number = y.a;
          let q: number = wrap().a;
          let z = ir.make();
          let had: boolean = has(z.nick);
          delete z.nick;
          if (v !== 7 || n !== 7 || w !== 11 || q !== 7 || had) {
            throw { code: "WRONG", message: "call receiver" };
          }
          return null;
        }
        """;

    private static final String CALL_RECEIVER_APP_REVERSED_SOURCE =
        reverseProviderImports(CALL_RECEIVER_APP_SOURCE);

    private static String reverseProviderImports(String source) {
        int first = source.indexOf('\n') + 1;
        int second = source.indexOf('\n', first) + 1;
        return source.substring(first, second) + source.substring(0, first)
            + source.substring(second);
    }

    /**
     * The standalone callee-parameter and declared-return fixture beside a
     * same-named local declaration: {@code ir.useIr({})} and
     * {@code g({})} construct the imported {@code ir.Ir} (7), never the
     * local declaration, and {@code make(): ir.Ir { return {}; }} returns
     * the imported layout. {@code localFieldsOnly} switches the local
     * declaration between differing fields and a differing default.
     */
    private static String paramReturnProviderAppSource(boolean reversed,
            boolean localFieldsOnly) {
        return (reversed
                ? "import * as codec from \"./codec\";\n"
                    + "import * as ir from \"./ir\";\n"
                : "import * as ir from \"./ir\";\n"
                    + "import * as codec from \"./codec\";\n")
            + "\n"
            + (localFieldsOnly
                ? "class Ir { b: string; }\n"
                : "class Ir { a: number; }\n")
            + "\n"
            + "function g(x: ir.Ir): number { return x.a; }\n"
            + "function make(): ir.Ir { return {a: 7}; }\n"
            + "\n"
            + "export function main(): null {\n"
            + "  let n: number = ir.useIr({a: 7});\n"
            + "  if (n !== 7) { throw { code: \"WRONG_PARAM\", message: \"n\" }; }\n"
            + "  let m: number = g({a: 7});\n"
            + "  if (m !== 7) { throw { code: \"WRONG_LOCAL_PARAM\", message: \"m\" }; }\n"
            + "  let r: ir.Ir = make();\n"
            + "  if (r.a !== 7) { throw { code: \"WRONG_RETURN\", message: \"r\" }; }\n"
            + (localFieldsOnly
                ? "  return null;\n"
                : "  let local: Ir = {a: 5};\n"
                    + "  if (local.a !== 5) { throw { code: \"WRONG_LOCAL\","
                    + " message: \"local\" }; }\n"
                    + "  return null;\n")
            + "}\n";
    }

    /**
     * The malformed-provider negative fixture: the entry writes a field
     * of an imported class whose implementation owner is removed from the
     * lowering closure (the checker-valid source stays).
     */
    private static final String IMPORTED_FIELD_WRITE_APP_SOURCE = """
        import * as u from "util";

        export function poke(p: u.Point): null {
          p.x = 1;
          return null;
        }

        export function main(): null {
          return null;
        }
        """;

    private static final ClassId SEGMENT =
        new ClassId("src/util.deal", "Segment");

    /**
     * The binding/class common-admission fixture: a module-level class with
     * generated JSON defaults, an ordinary closure with two generation-pinned
     * captures, a host function value materialized at its producing crossing,
     * and parameter/import initializations.
     */
    private static final String BINDING_CLASS_UTIL_SOURCE = """
        // @jsonable
        export class Point {
          x: number;
          label: string;
          note?: string;
        }

        export function makeCounter(start: number, step: number): () => number {
          let n: number = start;
          function bump(): number {
            n = n + step;
            return n;
          }
          return bump;
        }
        """;

    /** The host declaration of the binding/class fixture: a function-valued return. */
    private static final String BINDING_CLASS_DECLARATION = """
        export function pick(): (x: number) => number;
        """;

    private static final String BINDING_CLASS_APP_SOURCE = """
        import * as u from "util";
        import * as cfg from "host/cfg";

        function twice(x: number): number {
          return x + x;
        }

        export function main(): null {
          let p: u.Point = {x: 5, label: "p"};
          let c: () => number = u.makeCounter(1, 2);
          let a: (x: number, y: number) => number = twice;
          let h: (x: number) => number = cfg.pick();
          let n: number = c();
          return null;
        }
        """;

    private static final ModuleId MID = new ModuleId("mid");

    private static final String OWNERSHIP_UTIL_SOURCE = """
        export class Point {
          x: number;
          y: number;
        }

        export function makeCounter(start: number): () => number {
          let n: number = start;
          function bump(): number {
            n = n + 1;
            return n;
          }
          return bump;
        }
        """;

    private static final String OWNERSHIP_MID_SOURCE = """
        export function answer(): number {
          return 42;
        }
        """;

    private static final String OWNERSHIP_APP_SOURCE = """
        import * as u from "util";
        import * as m from "mid";

        export function main(): null {
          let p: u.Point = {x: 4, y: 1};
          let c: () => number = u.makeCounter(1);
          let v: number = m.answer();
          return null;
        }
        """;

    // The compiler's canonical module path of a root-contained module is
    // the configured root text plus the source's relative path with the
    // defining file as its final component, so both classes of the
    // fixture — declared in `src/util.deal` — carry `@src/util.deal/<Name>`:
    // the identity the checker, the declaration surface, the checked
    // project's interface index, and the legacy emission all produce.
    private static final ClassId POINT =
        new ClassId("src/util.deal", "Point");
    private static final ClassId HIDDEN =
        new ClassId("src/util.deal", "Hidden");








    // =========================================================================
    // The real-project harness (the production frontend + the project entry)
    // =========================================================================

    private record RealProject(
        Path root,
        CompilationOrchestrator orchestrator,
        CheckedProjectInput checkedProject,
        ProjectInterfaceIndex index,
        List<SemanticRequirementManifest> manifests,
        HostDeclarationSurface surface,
        Map<ModuleId, CanonicalModuleIdentity> declarationIdentities) {
    }

    private static RealProject compileProject(String appSource) throws Exception {
        return compileProject(appSource, Map.of(), Map.of());
    }

    /**
     * The generalized fixture harness: {@code extraSources} writes further
     * (or replacement) module files relative to the project root and
     * {@code extraExternals} adds externals declarations
     * (raw specifier &rarr; project-relative declaration path).
     */
    private static RealProject compileProject(String appSource,
            Map<String, String> extraSources,
            Map<String, String> extraExternals) throws Exception {
        return compileProject("src/app.deal", appSource, extraSources,
            extraExternals);
    }

    /**
     * The harness with an explicit entry path: a fixture whose consumer
     * must live beside its sibling providers (the identity names the
     * defining file, so the directory holds the sibling declarations the
     * probe crosses) writes the entry at that path and keeps its module
     * roots at {@code src}.
     */
    private static RealProject compileProject(String entryRelativePath,
            String appSource,
            Map<String, String> extraSources,
            Map<String, String> extraExternals) throws Exception {
        Path proj = Files.createTempDirectory("project-lowering");
        writeFileIn(proj, "src/cfg.d.deal", HOST_DECLARATION);
        writeFileIn(proj, "src/util.deal", UTIL_SOURCE);
        writeFileIn(proj, entryRelativePath, appSource);
        for (Map.Entry<String, String> extra : extraSources.entrySet()) {
            writeFileIn(proj, extra.getKey(), extra.getValue());
        }
        Path entry = proj.resolve(entryRelativePath).toAbsolutePath();
        Path output = proj.resolve("out").toAbsolutePath();
        Map<String, String> externals = new LinkedHashMap<>();
        externals.put(HOST_CFG_SPECIFIER,
            proj.resolve("src/cfg.d.deal").toAbsolutePath().toString());
        for (Map.Entry<String, String> extra : extraExternals.entrySet()) {
            externals.put(extra.getKey(),
                proj.resolve(extra.getValue()).toAbsolutePath().toString());
        }

        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entry, output, false, false, false, false, Backend.LUAJIT, externals,
            List.of(proj.resolve("src").toAbsolutePath()),
            Path.of(".").toAbsolutePath().normalize(), null,
            ConformanceHarnessMetadata.invocation());
        boolean compiled = orchestrator.compile();
        check(compiled, "the fixture project compiles through the production "
            + "pipeline: " + orchestrator.diagnostics());
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        check(built != null && !built.hasErrors() && built.input() != null
                && built.index() != null,
            "the production checked-project builder produced the closure and the "
                + "index: " + (built == null ? "null" : built.diagnostics()));
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        if (built == null || built.hasErrors() || built.input() == null
                || built.index() == null || manifests == null
                || manifests.manifests() == null) {
            deleteRecursively(proj);
            throw new IllegalStateException("the fixture project did not build");
        }

        Map<ModuleId, CanonicalModuleIdentity> identities = new LinkedHashMap<>();
        for (ModuleId declarationModule : orchestrator.hostDeclarationSurface()
                .moduleIds()) {
            // An externals-listed declaration module's public identity is
            // the externals map key exactly as written (the module-identity
            // layer's pinned rule); a project-classified declaration module
            // carries the standalone identity of its dotted module path.
            String specifier = null;
            for (String candidate : externals.keySet()) {
                if (candidate.replace('/', '.').equals(declarationModule.path())) {
                    specifier = candidate;
                    break;
                }
            }
            identities.put(declarationModule, specifier == null
                ? IdentityTestFixtures.moduleIdentityOf(declarationModule.path())
                : new CanonicalModuleIdentity.ExternalModule(specifier));
        }
        return new RealProject(proj, orchestrator, built.input(), built.index(),
            manifests.manifests(), orchestrator.hostDeclarationSurface(),
            identities);
    }

    private record LoweredProject(RealProject project,
                                  SemanticLowerer.ProjectLoweringResult result) {
    }

    private static SemanticIrValidator.ComparisonFacts comparisonFacts(RealProject project) {
        return new SemanticIrValidator.ComparisonFacts(project.index().interfaceIndexDigest(),
            SemanticProfile.DEAL_V1_3_INT32, invocation().capabilityRegistryHash());
    }

    private static CompilerInvocation invocation() {
        return CompilerProfileProvider.resolve(ReleaseState.V1_3_ACTIVE,
            CapabilityRegistry.releaseRegistry());
    }

    /** One {@link SemanticLowerer#lowerProject} call over the real project. */
    private static SemanticLowerer.ProjectLoweringResult lower(
            RealProject project, CompilerInvocation invocation) {
        return SemanticLowerer.lowerProject(invocation, project.checkedProject(),
            project.index(), project.manifests(), project.surface(),
            project.declarationIdentities(), BuiltinErrorDeclaration.synthesized(
                project.checkedProject().modules().get(0).ast().span()),
            Set.of());
    }

    // =========================================================================
    // 1. The one project lowering and determinism
    // =========================================================================

    private static void testOneProjectLoweringAndDeterminism(
            LoweredProject baseline) throws Exception {
        System.out.println("-- the one project lowering: closure, entry, one "
            + "allocator, byte-identical repeats --");
        RealProject project = baseline.project();
        {
            checkEq(List.of(UTIL, APP),
                project.checkedProject().modules().stream()
                    .map(CheckedModuleInput::moduleId).toList(),
                "the checked closure carries the implementation modules in "
                    + "dependency order");

            SemanticLowerer.ProjectLoweringResult first = baseline.result();
            SemanticLowerer.ProjectLoweringResult second = lower(project, invocation());
            check(!first.hasErrors() && first.project() != null,
                "the project lowers to exactly one project: " + first.diagnostics());
            check(second.project() != null, "the repeated lowering lowers too");
            if (first.project() == null || second.project() == null) {
                return;
            }
            ExecutableLoweredProject lowered = first.project();
            checkEq(SemanticProfile.DEAL_V1_3_INT32, lowered.semanticProfile(),
                "the project carries the DEAL_V1_3_INT32 profile");
            checkEq(List.of(UTIL, APP), List.copyOf(lowered.modules().keySet()),
                "the project's module insertion order is the dependency order");
            checkEq(APP, lowered.entryModule(), "the entry module is present");
            check(lowered.modules().containsKey(lowered.entryModule()),
                "the entry module is a member of the closure");

            // One table and one registry per module; nothing else escapes.
            checkEq(Set.of(UTIL, APP), first.tables().keySet(),
                "the result carries one block-membership table per module");
            checkEq(Set.of(UTIL, APP), first.registries().keySet(),
                "the result carries one class-factory registry per module");

            // Globally unique ids from one allocator.
            Set<Long> opIds = new LinkedHashSet<>();
            int ops = 0;
            for (LoweredModuleUnit unit : lowered.modules().values()) {
                for (SemanticOp op : unit.ops()) {
                    ops++;
                    check(op.opId().module().equals(unit.moduleId()),
                        "every op id carries its producing module");
                    if (!opIds.add(op.opId().id())) {
                        fail("the op id " + op.opId() + " is issued twice");
                    }
                }
            }
            check(ops > 0 && opIds.size() == ops,
                "the ids are issued once per compilation and globally unique (" + ops
                    + " ops, " + opIds.size() + " distinct ids)");

            // Byte-identical repeated project dumps.
            SemanticIrDumper.ProjectDump firstDump = SemanticIrDumper.dumpProject(lowered);
            SemanticIrDumper.ProjectDump secondDump =
                SemanticIrDumper.dumpProject(second.project());
            check(java.util.Arrays.equals(firstDump.manifest(), secondDump.manifest()),
                "the repeated project manifests are byte-identical");
            check(firstDump.modules().size() == secondDump.modules().size(),
                "both dumps carry every module");
            for (int i = 0; i < firstDump.modules().size(); i++) {
                check(firstDump.modules().get(i).moduleId()
                        .equals(secondDump.modules().get(i).moduleId())
                        && java.util.Arrays.equals(
                            firstDump.modules().get(i).bytes(),
                            secondDump.modules().get(i).bytes()),
                    "the repeated dump of module "
                        + firstDump.modules().get(i).moduleId() + " is byte-identical");
            }
        }
    }

    // =========================================================================
    // 2. The seeds, the intrinsic bindings, and the namespace registrations
    // =========================================================================

    private static void testSeedsIntrinsicsAndNamespaces(LoweredProject baseline)
            throws Exception {
        System.out.println("-- the seeds, the intrinsic bindings, and the "
            + "namespace registrations --");
        RealProject project = baseline.project();
        {
            SemanticLowerer.ProjectLoweringResult result = baseline.result();
            if (result.project() == null) {
                fail("the project lowers: " + result.diagnostics());
                return;
            }
            // The class registration seeds: the compiler-owned builtin
            // Error plus one layout per declared class of every declaration
            // module.
            check(result.seeds() != null, "the result carries the class "
                + "registration seeds");
            if (result.seeds() == null) {
                return;
            }
            ClassLayout errorLayout = result.seeds().registrationFor(BUILTIN_ERROR)
                == null ? null : result.seeds().registrationFor(BUILTIN_ERROR).layout();
            check(errorLayout != null
                    && result.seeds().registrationFor(BUILTIN_ERROR).owner()
                        == ConstructionProvider.BUILTIN_ERROR,
                "the seeds carry the compiler-owned builtin Error layout under "
                    + "BUILTIN_ERROR");
            checkEq(2, result.seeds().registrations().size(),
                "the seeds carry exactly one layout per declared class of every "
                    + "declaration module plus the builtin Error entry");
            // The seeds are the layout resolution context, never merged into
            // a unit's own declared layouts.
            for (LoweredModuleUnit unit : result.project().modules().values()) {
                check(unit.classLayouts().isEmpty(),
                    "module " + unit.moduleId() + " carries no seeded layout in its "
                        + "own classLayouts map (the seeds are never merged into it)");
            }
            if (errorLayout != null) {
                checkEq(ClassLayout.BUILTIN_ERROR, errorLayout,
                    "the seeds' builtin Error registration is exactly the "
                        + "compiler-owned ClassLayout.BUILTIN_ERROR constant (the "
                        + "layout is identical in every unit's context)");
                checkEq(List.of("code", "message"),
                    errorLayout.fields().stream().map(ClassLayout.FieldLayout::name)
                        .toList(),
                    "the builtin Error layout carries code/message in declaration "
                        + "order");
                check(errorLayout.fields().stream()
                        .allMatch(field -> field.required()
                            && field.constructionProvider() == ConstructionProvider.BUILTIN_ERROR),
                    "both builtin Error fields are required-present under "
                        + "BUILTIN_ERROR");
            }
            ClassLayout endpointLayout = result.seeds().registrationFor(ENDPOINT)
                == null ? null : result.seeds().registrationFor(ENDPOINT).layout();
            check(endpointLayout != null
                    && result.seeds().registrationFor(ENDPOINT).owner()
                        == ConstructionProvider.HOST_DECLARATION,
                "the declared host class carries its layout under HOST_DECLARATION "
                    + "(the seed proves the declaration identity classification)");
            if (endpointLayout != null) {
                checkEq(List.of("path", "port"),
                    endpointLayout.fields().stream().map(ClassLayout.FieldLayout::name)
                        .toList(),
                    "the declared host class fields keep declaration order");
                check(endpointLayout.fields().get(0).required()
                        && !endpointLayout.fields().get(1).required(),
                    "required-present is the declaration's optional-negation");
            }

            // Every executable binding has an ordinary production producer.
            for (LoweredModuleUnit unit : result.project().modules().values()) {
                for (SemanticOp init : unit.ops()) {
                    if (init.kind() != SemanticOpKind.BINDING_INIT) continue;
                    for (ValueId operand : init.operands()) {
                        check(unit.ops().stream().anyMatch(op -> operand.equals(op.result())),
                            "binding initialization has an actual producing operation");
                    }
                }
                // The dump carries the registration: the closed shape tag and
                // the alias cells of the module's completions.
                String dump = new String(
                    SemanticIrDumper.dumpModule(unit), StandardCharsets.UTF_8);
                check(!dump.contains("intrinsicFunction"),
                    "the dump of module " + unit.moduleId()
                        + " has no retired intrinsicFunction binding shape");
                if (unit.ops().stream().anyMatch(op ->
                        op.kind() == SemanticOpKind.MODULE_IMPORT)) {
                    check(dump.contains("aliasCells"),
                        "the dump of module " + unit.moduleId()
                            + " carries the MODULE_IMPORT aliasCells list");
                }
            }

            // The namespace registrations: exactly one entry per distinct
            // imported module of the closure.
            check(result.namespaces() != null,
                "the result carries the namespace registrations");
            if (result.namespaces() == null) {
                return;
            }
            Map<ModuleId, BindingId> dumpedCells = new LinkedHashMap<>();
            Map<ModuleId, Integer> dumpedCompletions = new LinkedHashMap<>();
            for (LoweredModuleUnit unit : result.project().modules().values()) {
                for (SemanticOp op : unit.ops()) {
                    if (op.kind() != SemanticOpKind.MODULE_IMPORT
                            || !(op.payload()
                                instanceof KindPayload.ModuleImportPayload payload)) {
                        continue;
                    }
                    dumpedCompletions.merge(payload.resolvedModule(), 1,
                        Integer::sum);
                    check(payload.aliasCells().size() == 1,
                        "each import completion names its one alias cell; got "
                            + payload.aliasCells());
                    if (payload.aliasCells().size() == 1) {
                        dumpedCells.put(payload.resolvedModule(),
                            payload.aliasCells().get(0));
                    }
                }
            }
            checkEq(Set.of(UTIL, HOST_CFG), result.namespaces().registrations().keySet(),
                "exactly one registration exists per distinct imported module");
            NamespaceRegistration cfg = result.namespaces()
                .registrationFor(HOST_CFG);
            check(cfg != null && cfg.kind() == deal.semantic.ir.ModuleImportKind.HOST,
                "the host import's registration carries the HOST kind");
            if (cfg != null) {
                checkEq(List.of(dumpedCells.get(HOST_CFG)), cfg.aliasCells(),
                    "the entry's alias cells equal the cells its completion names");
            }
            NamespaceRegistration compiledModule = result.namespaces()
                .registrationFor(UTIL);
            check(compiledModule != null
                    && compiledModule.kind()
                        == deal.semantic.ir.ModuleImportKind.COMPILED,
                "the compiled implementation import's registration carries the "
                    + "COMPILED kind");
            if (compiledModule != null) {
                checkEq(List.of(dumpedCells.get(UTIL)), compiledModule.aliasCells(),
                    "the compiled import's cells equal the cells its completion "
                        + "names");
            }
            // The dumped MODULE_IMPORT payload names the same cells.
            List<BindingId> dumpedHostCells = new ArrayList<>();
            for (LoweredModuleUnit unit : result.project().modules().values()) {
                for (SemanticOp op : unit.ops()) {
                    if (op.kind() == SemanticOpKind.MODULE_IMPORT
                            && op.payload()
                                instanceof KindPayload.ModuleImportPayload payload
                            && payload.resolvedModule().equals(HOST_CFG)) {
                        dumpedHostCells.addAll(payload.aliasCells());
                    }
                }
            }
            checkEq(cfg == null ? List.of("missing") : cfg.aliasCells(),
                dumpedHostCells,
                "the dumped MODULE_IMPORT payloads carry the registered cells");
        }
    }

    private static void testUnitProjectCompanionOwnership() throws Exception {
        System.out.println("-- the complete unit/project companion ownership: the "
            + "unit owns bodyTable/classFactories/classBindingFacts, "
            + "the project owns classSeeds, and the result accessors are those "
            + "exact records --");
        RealProject project = compileProject(OWNERSHIP_APP_SOURCE,
            Map.of("src/util.deal", OWNERSHIP_UTIL_SOURCE,
                "src/mid.deal", OWNERSHIP_MID_SOURCE), Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(project, invocation());
            check(!result.hasErrors() && result.project() != null,
                "the ownership fixture lowers: " + result.diagnostics());
            if (result.project() == null) {
                return;
            }
            ExecutableLoweredProject lowered = result.project();
            check(lowered.classSeeds() != null,
                "the executable project owns its class registration seeds");
            check(lowered.classSeeds() == result.seeds(),
                "result.seeds() is exactly project.classSeeds(), never a detached "
                    + "copy");
            checkEq(Set.of(UTIL, MID, APP), result.tables().keySet(),
                "the result carries one body table per closure module");
            checkEq(Set.of(UTIL, MID, APP), result.registries().keySet(),
                "the result carries one class-factory registry per closure module");

            boolean sawClassModule = false;
            boolean sawPlainModule = false;
            boolean sawImportingModule = false;
            for (LoweredModuleUnit unit : lowered.modules().values()) {
                ModuleId module = unit.moduleId();
                StructuredBodyTable body = unit.bodyTable();
                ClassFactoryRegistry factories = unit.classFactories();
                ClassBindingFacts bindingFacts = unit.classBindingFacts();
                check(body != null && factories != null
                        && bindingFacts != null,
                    "module " + module + " carries every mandatory companion as a "
                        + "present record, never null");
                if (body == null || factories == null
                        || bindingFacts == null) {
                    continue;
                }
                // The retained result accessors are the unit's own records.
                check(result.tableOf(module) == body
                        && result.tables().get(module) == body,
                    "result.tableOf(" + module + ") is the unit's own bodyTable, "
                        + "not a detached copy");
                check(result.registryOf(module) == factories
                        && result.registries().get(module) == factories,
                    "result.registryOf(" + module + ") is the unit's own "
                        + "classFactories, not a detached copy");
                // Both membership directions are the exact inverses over the
                // unit's own operations.
                for (Map.Entry<BlockId, List<OpId>> entry : body.blockOps().entrySet()) {
                    for (OpId member : entry.getValue()) {
                        checkEq(entry.getKey(), body.opBlocks().get(member),
                            "the block table's opBlocks is the exact inverse of "
                                + "blockOps at " + member + " of " + module);
                    }
                }
                for (Map.Entry<OpId, BlockId> entry : body.opBlocks().entrySet()) {
                    check(body.blockOps().getOrDefault(entry.getValue(), List.of())
                            .contains(entry.getKey()),
                        "the block table's blockOps lists " + entry.getKey()
                            + " of " + module);
                }
                // Every registered factory names a produced CLASS_FACTORY of
                // this unit.
                for (Map.Entry<ClassFactoryId, OpId> entry
                        : factories.factories().entrySet()) {
                    SemanticOp factory = opOf(unit, entry.getValue());
                    check(factory != null
                            && factory.kind() == SemanticOpKind.CLASS_FACTORY
                            && factory.opId().module().equals(module),
                        "the registry entry " + entry.getKey() + " of " + module
                            + " names this unit's CLASS_FACTORY op");
                }
                // Every parameter/import fact names a produced BINDING_ALLOC
                // of this unit.
                Set<BindingId> allocated = new LinkedHashSet<>();
                for (SemanticOp op : unit.ops()) {
                    if (op.payload() instanceof KindPayload.BindingAllocPayload alloc) {
                        allocated.add(alloc.binding());
                    }
                }
                for (BindingId parameter : bindingFacts.parameters()) {
                    check(allocated.contains(parameter),
                        "the parameter fact " + parameter + " of " + module
                            + " names a produced BINDING_ALLOC");
                }
                for (BindingId alias : bindingFacts.importAliases()) {
                    check(allocated.contains(alias),
                        "the import-alias fact " + alias + " of " + module
                            + " names a produced BINDING_ALLOC");
                }

                if (module.equals(UTIL)) {
                    sawClassModule = true;
                    check(unit.classLayouts().containsKey(POINT),
                        "the class module's own layout is in its classLayouts");
                    check(unit.classLayouts().get(POINT).classId().equals(POINT),
                        "the own layout's classId agrees with its registry key");
                    check(!factories.factories().isEmpty(),
                        "the class module's registry carries the exported "
                            + "CLASS_FACTORY registration");
                    check(lowered.classSeeds().registrationFor(POINT) == null,
                        "an in-project class is owned by the unit's own classLayouts, "
                            + "never merged into the declaration seeds");
                    check(lowered.classSeeds().registrationFor(BUILTIN_ERROR) != null,
                        "the project's class seeds carry the compiler-owned builtin "
                            + "Error registration");
                    check((Object) unit.classFactories()
                            != (Object) lowered.classSeeds(),
                        "the unit registry and the project seeds stay separate "
                            + "records");
                }
                if (module.equals(MID)) {
                    sawPlainModule = true;
                    // A module without class content and without parameters or
                    // imports carries present-empty companions.
                    check(unit.classLayouts().isEmpty(),
                        "the plain module carries no own class layout");
                    check(factories.factories().isEmpty(),
                        "the plain module carries an empty class-factory "
                            + "registry");
                    checkEq(ClassBindingFacts.empty(), bindingFacts,
                        "the plain module carries empty parameter/import facts");
                }
                if (module.equals(APP)) {
                    sawImportingModule = true;
                    check(unit.classLayouts().isEmpty(),
                        "the importing module owns no local class layout");
                    check(factories.factories().isEmpty(),
                        "the importing module's registry is present and empty");
                    check(!bindingFacts.importAliases().isEmpty(),
                        "the importing module's import-alias facts are recorded "
                            + "in the unit");
                    check(!unit.classLayouts().containsKey(POINT),
                        "the imported class's seed layout never merges into the "
                            + "consumer's classLayouts");
                }
            }
            check(sawClassModule && sawPlainModule && sawImportingModule,
                "the closure carries the class, plain and importing modules");
            testResultBoundaryViewOwnership(lowered, result.namespaces());
            testCompleteUnitTransport(project, lowered);
        } finally {
            deleteRecursively(project.root());
        }
    }

    private static void testResultBoundaryViewOwnership(
            ExecutableLoweredProject lowered,
            NamespaceRegistrations namespaces) {
        System.out.println("-- the lowering-result boundary: missing, extra and "
            + "differing companion views reject, content-equal detached views are "
            + "replaced by the project's owned records, and the failure path "
            + "carries no companions --");
        Map<ModuleId, LoweredModuleUnit> modules = lowered.modules();
        ModuleId classModule = null;
        for (ModuleId module : modules.keySet()) {
            classModule = module;
            break;
        }
        LoweredModuleUnit unit = modules.get(classModule);
        StructuredBodyTable ownedTable = unit.bodyTable();
        ClassFactoryRegistry ownedRegistry = unit.classFactories();
        check(!ownedTable.blockOps().isEmpty(),
            "the boundary fixture's first module owns a non-empty body table");
        check(!ownedRegistry.factories().isEmpty(),
            "the boundary fixture's first module owns a non-empty factory "
                + "registry");
        check(!lowered.classSeeds().registrations().isEmpty(),
            "the boundary fixture's project owns its class registration seeds");

        // (1) The exact owned views and content-equal detached copies are
        // accepted; the retained accessors are the project's own records.
        Map<ModuleId, StructuredBodyTable> copiedTables = new LinkedHashMap<>();
        Map<ModuleId, ClassFactoryRegistry> copiedRegistries = new LinkedHashMap<>();
        for (Map.Entry<ModuleId, LoweredModuleUnit> entry : modules.entrySet()) {
            StructuredBodyTable body = entry.getValue().bodyTable();
            ClassFactoryRegistry registry = entry.getValue().classFactories();
            copiedTables.put(entry.getKey(),
                new StructuredBodyTable(body.blockOps(), body.opBlocks()));
            copiedRegistries.put(entry.getKey(),
                new ClassFactoryRegistry(registry.factories()));
        }
        SemanticLowerer.ProjectLoweringResult canonical =
            new SemanticLowerer.ProjectLoweringResult(lowered, copiedTables,
                copiedRegistries,
                new ClassRegistrationSeeds(lowered.classSeeds().registrations()),
                namespaces, List.of());
        for (Map.Entry<ModuleId, LoweredModuleUnit> entry : modules.entrySet()) {
            check(canonical.tables().get(entry.getKey())
                    == entry.getValue().bodyTable(),
                "the result table of " + entry.getKey() + " is the unit's owned "
                    + "bodyTable even when a content-equal copy was supplied");
            check(canonical.registries().get(entry.getKey())
                    == entry.getValue().classFactories(),
                "the result registry of " + entry.getKey() + " is the unit's "
                    + "owned classFactories even when a content-equal copy was "
                    + "supplied");
        }
        check(canonical.seeds() == lowered.classSeeds(),
            "the result seeds are the project's owned classSeeds even when a "
                + "content-equal copy was supplied");

        for (String defect : List.of("missing", "extra", "differing")) {
            Map<ModuleId, StructuredBodyTable> tables = new LinkedHashMap<>(copiedTables);
            Map<ModuleId, ClassFactoryRegistry> registries = new LinkedHashMap<>(copiedRegistries);
            switch (defect) {
                case "missing" -> {
                    tables.remove(classModule);
                    registries.remove(classModule);
                }
                case "extra" -> {
                    tables.put(new ModuleId("extra"), new StructuredBodyTable(Map.of(), Map.of()));
                    registries.put(new ModuleId("extra"), new ClassFactoryRegistry(Map.of()));
                }
                case "differing" -> {
                    tables.put(classModule, new StructuredBodyTable(Map.of(), ownedTable.opBlocks()));
                    registries.put(classModule, new ClassFactoryRegistry(Map.of()));
                }
            }
            expectIllegalArgument(() -> new SemanticLowerer.ProjectLoweringResult(
                    lowered, tables, copiedRegistries, lowered.classSeeds(), namespaces, List.of()),
                defect + " table view rejects");
            expectIllegalArgument(() -> new SemanticLowerer.ProjectLoweringResult(
                    lowered, copiedTables, registries, lowered.classSeeds(), namespaces, List.of()),
                defect + " registry view rejects");
        }

        // (4) Missing and differing seed views reject.
        expectIllegalArgument(() -> new SemanticLowerer.ProjectLoweringResult(
                lowered, copiedTables, copiedRegistries, null, namespaces,
                List.of()),
            "a result without the project's seeds rejects");
        expectIllegalArgument(() -> new SemanticLowerer.ProjectLoweringResult(
                lowered, copiedTables, copiedRegistries,
                new ClassRegistrationSeeds(Map.of()), namespaces, List.of()),
            "a seed view differing from the project's classSeeds rejects");

        // (5) A successful result carrying diagnostics rejects.
        CompilerDiagnostic diagnostic = FailureContractRegistry.e6005(
            new LoweringFailureDetail(APP.path(),
                SemanticCapability.FOUNDATION_VALUES, "RESULT_BOUNDARY_TEST",
                SemanticProfile.DEAL_V1_3_INT32, LoweredModuleUnit.FORMAT_VERSION,
                "ProjectLoweringTest"));
        expectIllegalArgument(() -> new SemanticLowerer.ProjectLoweringResult(
                lowered, copiedTables, copiedRegistries, lowered.classSeeds(),
                namespaces, List.of(diagnostic)),
            "a successful project lowering carrying diagnostics rejects");

        // (6) The failure path still carries exactly the first E6005 and no
        // project, tables, registries, seeds or namespace registrations.
        SemanticLowerer.ProjectLoweringResult failure =
            new SemanticLowerer.ProjectLoweringResult(null, Map.of(), Map.of(),
                null, null, List.of(diagnostic));
        check(failure.hasErrors() && failure.project() == null
                && failure.tables().isEmpty() && failure.registries().isEmpty()
                && failure.seeds() == null && failure.namespaces() == null,
            "a failed project lowering exposes no project, tables, registries, "
                + "seeds or namespace registrations");
        expectIllegalArgument(() -> new SemanticLowerer.ProjectLoweringResult(null,
                copiedTables, Map.of(), null, null, List.of(diagnostic)),
            "a failed project lowering carrying tables rejects");
        expectIllegalArgument(() -> new SemanticLowerer.ProjectLoweringResult(null,
                Map.of(), copiedRegistries, null, null, List.of(diagnostic)),
            "a failed project lowering carrying registries rejects");
        expectIllegalArgument(() -> new SemanticLowerer.ProjectLoweringResult(null,
                Map.of(), Map.of(), lowered.classSeeds(), null,
                List.of(diagnostic)),
            "a failed project lowering carrying seeds rejects");
        expectIllegalArgument(() -> new SemanticLowerer.ProjectLoweringResult(null,
                Map.of(), Map.of(), null, namespaces, List.of(diagnostic)),
            "a failed project lowering carrying namespace registrations "
                + "rejects");
        expectIllegalArgument(() -> new SemanticLowerer.ProjectLoweringResult(null,
                Map.of(), Map.of(), null, null, List.of()),
            "a failed project lowering without a diagnostic rejects");

        // (7) The single-module result exposes the unit's own table.
        expectIllegalArgument(() -> new SemanticLowerer.LoweringResult(unit,
                new StructuredBodyTable(Map.of(), ownedTable.opBlocks()), List.of()),
            "a single-module result table disagreeing with the unit rejects");
        SemanticLowerer.LoweringResult single = new SemanticLowerer.LoweringResult(
            unit, new StructuredBodyTable(ownedTable.blockOps(),
                ownedTable.opBlocks()), List.of());
        check(single.table() == ownedTable,
            "the single-module result table is the unit's owned bodyTable even "
                + "when a content-equal copy was supplied");
        expectIllegalArgument(() -> new SemanticLowerer.LoweringResult(unit, null,
                List.of()),
            "a single-module result without the unit's table rejects");
    }

    /**
     * The namespace-recording failure origins (ISSUE-0750 D3): every
     * completion-specific recording rejection carries the offending
     * {@code MODULE_IMPORT} operation's own exact source range — a
     * conflict detected on a second completion carries that second
     * completion's range, never the first naming op's — while the
     * aggregate rejection that names only an uncompleted alias allocation
     * (no produced completion exists to identify) keeps the canonical
     * empty-file synthetic range. Messages, predicates, and first-failure
     * order are unchanged.
     */
    private static void testNamespaceRegistrationFailureOrigins(
            LoweredProject baseline) throws Exception {
        System.out.println("-- the namespace-registration failure origins: the "
            + "offending MODULE_IMPORT completion's own range, and the canonical "
            + "synthetic range of the aggregate uncompleted-alias failure --");
        RealProject project = baseline.project();
        SemanticLowerer.ProjectLoweringResult result = baseline.result();
        if (result.project() == null) {
            fail("the project lowers: " + result.diagnostics());
            return;
        }
        LoweredModuleUnit app = result.project().modules().get(APP);
        SemanticOp utilImport = app == null ? null : moduleImportOf(app, UTIL);
        check(app != null && utilImport != null,
            "the entry unit records the MODULE_IMPORT completion of util");
        if (app == null || utilImport == null) {
            return;
        }
        String appFile = project.root().resolve("src/app.deal").toAbsolutePath()
            .normalize().toString();
        DiagnosticRange completionRange = utilImport.origin().span().range();
        check(completionRange.origin() == RangeOrigin.SOURCE
                && completionRange.scalarLength() > 0,
            "the offending completion carries a real SOURCE span: "
                + completionRange);

        // (a) The kind mismatch: the completion's own exact SOURCE range.
        Optional<CompilerDiagnostic> mismatchFailure =
            new NamespaceRegistrations.Recorder(Map.of(UTIL, ModuleImportKind.HOST))
                .record(app);
        check(mismatchFailure.isPresent(),
            "the pre-registered HOST entry kind rejects the COMPILED completion");
        if (mismatchFailure.isPresent()) {
            CompilerDiagnostic diagnostic = mismatchFailure.get();
            checkEq("E6005", diagnostic.code(),
                "the kind mismatch is one E6005");
            checkEq("Common semantic lowering failed: module 'app', capability"
                    + " MODULES, validatorRule NAMESPACE_REGISTRATION,"
                    + " semanticProfile DEAL_V1_3_INT32, irVersion"
                    + " deal.semantic-ir/2, origin NamespaceRegistrations record"
                    + " (the MODULE_IMPORT completion " + utilImport.opId()
                    + " of util carries the kind COMPILED but the module's"
                    + " registration entry kind is HOST (a completion's"
                    + " ModuleImportKind equals its entry's kind))",
                diagnostic.message(),
                "the kind-mismatch message is the unchanged pinned text");
            checkEq(completionRange, diagnostic.range(),
                "the kind-mismatch diagnostic carries the offending completion's"
                    + " own exact range");
            checkEq(appFile, diagnostic.range().file(),
                "the kind-mismatch range names the offending file");
            checkEq(1, diagnostic.range().startLine(),
                "the kind-mismatch range starts at the import statement's line");
            checkEq(1, diagnostic.range().startColumn(),
                "the kind-mismatch range starts at the import statement's column");
            check(!diagnostic.range().isCanonicalSynthetic(),
                "the kind-mismatch range is never the canonical synthetic anchor");
            check(diagnostic.notes().isEmpty(),
                "the well-formed completion origin adds no note: "
                    + diagnostic.notes());
        }

        // (b) A conflict detected on a second completion: the detecting
        // completion's own range, never the first naming op's.
        SourceSpan doctoredSpan = new SourceSpan(appFile, 9, 4, 9, 10, 100, 106);
        SourceOrigin doctoredOrigin = new SourceOrigin(
            utilImport.origin().sourceId(), doctoredSpan, SourceOriginKind.USER,
            new AnchorId(9999), utilImport.origin().parentOpId());
        SemanticOp duplicated = new SemanticOp(
            new OpId(APP, 999999), utilImport.kind(), doctoredOrigin,
            utilImport.result(), utilImport.resultType(), utilImport.operands(),
            utilImport.operandTypes(), utilImport.payload(),
            utilImport.failurePolicy(), utilImport.contract());
        List<SemanticOp> doubled = new ArrayList<>(app.ops());
        doubled.add(duplicated);
        Optional<CompilerDiagnostic> duplicateFailure =
            new NamespaceRegistrations.Recorder(Map.of()).record(
                withOps(app, doubled));
        check(duplicateFailure.isPresent(),
            "the second naming of the same alias cell is rejected");
        if (duplicateFailure.isPresent()) {
            CompilerDiagnostic diagnostic = duplicateFailure.get();
            checkEq("E6005", diagnostic.code(),
                "the duplicate naming is one E6005");
            check(diagnostic.message().contains("the cell "
                    + ((KindPayload.ModuleImportPayload) utilImport.payload())
                        .aliasCells().get(0)
                    + " is named by two MODULE_IMPORT completions")
                    && diagnostic.message().contains(
                        "NamespaceRegistrations record"),
                "the duplicate-naming message is the unchanged text: "
                    + diagnostic.message());
            checkEq(doctoredSpan.range(), diagnostic.range(),
                "the duplicate-naming diagnostic carries the detecting"
                    + " completion's own exact range");
            check(!diagnostic.range().equals(completionRange),
                "the duplicate-naming range is never the first naming op's range");
            checkEq(9, diagnostic.range().startLine(),
                "the duplicate-naming range starts at the detecting completion's"
                    + " line");
            checkEq(4, diagnostic.range().startColumn(),
                "the duplicate-naming range starts at the detecting completion's"
                    + " column");
            checkEq(11, diagnostic.range().endColumn(),
                "the duplicate-naming range keeps the half-open end conversion");
            checkEq(100, diagnostic.range().startScalarOffset(),
                "the duplicate-naming range carries the recorded start offset");
            checkEq(106, diagnostic.range().endScalarOffset(),
                "the duplicate-naming range carries the recorded end offset");
            checkEq(6, diagnostic.range().scalarLength(),
                "the duplicate-naming range covers the recorded span");
            check(diagnostic.range().origin() == RangeOrigin.SOURCE,
                "the duplicate-naming range is SOURCE, got "
                    + diagnostic.range().origin());
        }

        // (c) The aggregate failure: the alias allocation the unit leaves
        // uncompleted has no produced completion to identify, so the
        // canonical empty-file synthetic range stays.
        List<SemanticOp> withoutUtil = new ArrayList<>();
        for (SemanticOp op : app.ops()) {
            if (!op.opId().equals(utilImport.opId())) {
                withoutUtil.add(op);
            }
        }
        Optional<CompilerDiagnostic> uncompletedFailure =
            new NamespaceRegistrations.Recorder(Map.of()).record(
                withOps(app, withoutUtil));
        check(uncompletedFailure.isPresent(),
            "the uncompleted alias allocation is rejected");
        if (uncompletedFailure.isPresent()) {
            CompilerDiagnostic diagnostic = uncompletedFailure.get();
            checkEq("E6005", diagnostic.code(),
                "the uncompleted alias is one E6005");
            check(diagnostic.message().contains(
                    "is named by no MODULE_IMPORT completion"),
                "the uncompleted-alias message is the unchanged aggregate text: "
                    + diagnostic.message());
            check(diagnostic.range().isCanonicalSynthetic(),
                "the aggregate failure keeps the canonical synthetic range, got "
                    + diagnostic.range());
            checkEq("", diagnostic.range().file(),
                "the aggregate failure borrows no source file");
            check(diagnostic.range().origin() == RangeOrigin.SYNTHETIC,
                "the aggregate failure stays SYNTHETIC, got "
                    + diagnostic.range().origin());
            checkEq(0, diagnostic.range().scalarLength(),
                "the aggregate failure keeps the zero-length anchor");
            check(diagnostic.notes().isEmpty(),
                "the originless aggregate adds no note: " + diagnostic.notes());
        }
    }

    /** The unit's completion of the given resolved module, or null. */
    private static SemanticOp moduleImportOf(LoweredModuleUnit unit,
            ModuleId module) {
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.MODULE_IMPORT
                    && op.payload() instanceof KindPayload.ModuleImportPayload payload
                    && payload.resolvedModule().equals(module)) {
                return op;
            }
        }
        return null;
    }

    // =========================================================================
    // 3. The composed chain over the unified units
    // =========================================================================

    private static void testComposedChainOverUnifiedUnits(LoweredProject baseline)
            throws Exception {
        System.out.println("-- the composed chain accepts the E7-armed unified "
            + "units --");
        RealProject project = baseline.project();
        {
            SemanticLowerer.ProjectLoweringResult result = baseline.result();
            if (result.project() == null) {
                fail("the project lowers: " + result.diagnostics());
                return;
            }
            SemanticIrValidator.ComparisonFacts facts =
                comparisonFacts(project);
            check(SemanticIrValidator.validate(result.project(), facts).isEmpty(),
                "the project passes the project-form closed gate");
            for (LoweredModuleUnit unit : result.project().modules().values()) {
                StructuredBodyTable table = result.tableOf(unit.moduleId());
                check(table != null, "the module's produced table is carried");
                // The closed 14 rules and the composed correlations of the
                // unit's own facts; the standalone entrypoint additionally
                // rejects a unit that requires the project's provider context
                // (the required foreign links of a cross-module unit).
                check(SemanticIrValidator.validateUnitHead(unit, facts).isEmpty(),
                    "module " + unit.moduleId() + " passes the closed 14 rules");
                check(AddressChainProtocol.validate(unit).isEmpty(),
                    "module " + unit.moduleId()
                        + " passes the address-chain protocol");
                check(deal.semantic.ControlFlowValidator.validate(unit, table)
                        .isEmpty(),
                    "module " + unit.moduleId() + " passes the control-flow "
                        + "validator");
                check(deal.semantic.ClassConstructionValidator.validate(unit,
                        table, result.registryOf(unit.moduleId()),
                        project.index().modules().get(unit.moduleId()),
                        Map.of()).isEmpty(),
                    "module " + unit.moduleId() + " passes the class-construction "
                        + "validator");
                // The unified session really carried the E7 arms: the
                // cross-module call produced the caller's EXTERNAL_ENTRY
                // reference and the callee's entry.
                if (unit.moduleId().equals(APP)) {
                    check(unit.ops().stream().anyMatch(op ->
                            op.kind() == SemanticOpKind.CALL
                                && op.payload() instanceof KindPayload.CallPayload
                                    call && call.externalEntryRef() != null),
                        "the entry module's cross-module call carries its "
                            + "externalEntryRef");
                }
                if (unit.moduleId().equals(UTIL)) {
                    check(unit.ops().stream().anyMatch(op ->
                            op.kind() == SemanticOpKind.EXTERNAL_ENTRY),
                        "the callee module records its EXTERNAL_ENTRY");
                    // The recursive-group arm is active in the same session:
                    // exactly one RECURSIVE_GROUP_INIT with both members.
                    List<SemanticOp> groups = new ArrayList<>();
                    for (SemanticOp op : unit.ops()) {
                        if (op.kind() == SemanticOpKind.RECURSIVE_GROUP_INIT) {
                            groups.add(op);
                        }
                    }
                    check(groups.size() == 1,
                        "the unified session produced exactly one "
                            + "RECURSIVE_GROUP_INIT; got " + groups.size());
                    if (groups.size() == 1
                            && groups.get(0).payload()
                                instanceof KindPayload.RecursiveGroupInitPayload group) {
                        checkEq(2, group.functions().size(),
                            "the group op publishes both members");
                    }
                }
                if (unit.moduleId().equals(APP)) {
                    // The adapter arm is active in the same session.
                    check(unit.ops().stream().anyMatch(op ->
                            op.kind() == SemanticOpKind.FUNCTION_ADAPT),
                        "the unified session produced the adapter");
                }
            }
        }
    }

    // =========================================================================
    // 4. The unified walk's class arm (in-project classes)
    // =========================================================================

    private static void testInProjectClassArm() throws Exception {
        System.out.println("-- the unified walk's class arm: an in-project class "
            + "declared and constructed in one session --");
        RealProject project = compileProject(CLASS_APP_SOURCE,
            Map.of("src/util.deal", CLASS_UTIL_SOURCE), Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(project, invocation());
            check(!result.hasErrors() && result.project() != null,
                "the class-declaring project lowers through the composed chain: "
                    + result.diagnostics());
            if (result.project() == null) {
                return;
            }
            LoweredModuleUnit util = result.project().modules().get(UTIL);
            check(util != null, "the class-declaring module is in the closure");
            if (util == null) {
                return;
            }
            // The class-declaration arm produced the unit's own layouts (the
            // exported class and the local one); the seeds stay out of them.
            ClassLayout pointLayout = util.classLayouts().get(POINT);
            ClassLayout hiddenLayout = util.classLayouts().get(HIDDEN);
            check(pointLayout != null && hiddenLayout != null,
                "the unified walk produced the unit's own classLayouts entries: "
                    + util.classLayouts().keySet());
            check(!util.classLayouts().containsKey(ENDPOINT)
                    && !util.classLayouts().containsKey(BUILTIN_ERROR),
                "the class registration seeds stay out of the unit's own "
                    + "classLayouts map");
            if (pointLayout == null || hiddenLayout == null) {
                return;
            }
            checkEq(List.of("x", "y"),
                pointLayout.fields().stream().map(ClassLayout.FieldLayout::name)
                    .toList(),
                "the exported class layout carries its fields in declaration order");
            check(pointLayout.fields().stream().allMatch(field -> field.required()
                    && field.constructionProvider() == ConstructionProvider.LOCAL),
                "the in-project class fields carry the LOCAL owner member");
            checkEq(List.of("label"),
                hiddenLayout.fields().stream().map(ClassLayout.FieldLayout::name)
                    .toList(),
                "the local class layout carries its field in declaration order");

            // The class-literal arm in the same session: one LOCAL CLASS_NEW
            // per construction, with the provided field as a literal boundary
            // and the omitted defaulted field as a default boundary.
            List<SemanticOp> constructions = new ArrayList<>();
            for (SemanticOp op : util.ops()) {
                if (op.kind() == SemanticOpKind.CLASS_NEW) {
                    constructions.add(op);
                }
            }
            checkEq(2, constructions.size(),
                "the unified walk produced one CLASS_NEW per class literal");
            boolean sawPoint = false;
            boolean sawHidden = false;
            for (SemanticOp op : constructions) {
                KindPayload.ClassNewPayload payload =
                    (KindPayload.ClassNewPayload) op.payload();
                check(payload.constructionProvider() == ConstructionProvider.LOCAL
                        && payload.classFactoryRef() == null,
                    "the same-module literal carries LOCAL with no factory ref");
                if (payload.classId().equals(POINT)) {
                    sawPoint = true;
                    checkEq(pointLayout, payload.layout(),
                        "the exported class literal carries the unit's own layout");
                    checkEq(List.of("x", "y"),
                        payload.fieldBoundaries().stream()
                            .map(KindPayload.FieldBoundary::field).toList(),
                        "the construction boundaries run in declaration order");
                    check(payload.fieldBoundaries().get(0).kind()
                            == BoundaryKind.CLASS_LITERAL_FIELD
                            && payload.fieldBoundaries().get(1).kind()
                                == BoundaryKind.CLASS_LITERAL_FIELD,
                        "both provided fields carry literal boundaries");
                }
                if (payload.classId().equals(HIDDEN)) {
                    sawHidden = true;
                    checkEq(hiddenLayout, payload.layout(),
                        "the local class literal carries the unit's own layout");
                    check(payload.fieldBoundaries().size() == 1
                            && payload.fieldBoundaries().get(0).kind()
                                == BoundaryKind.CLASS_LITERAL_FIELD,
                        "the fully provided local literal carries no default child");
                }
            }
            check(sawPoint && sawHidden,
                "both the exported and the local class literal lowered CLASS_NEW");

            // The exported variant's CLASS_FACTORY registration; the local
            // class never gets one.
            ExternalModuleInterface utilInterface =
                project.index().modules().get(UTIL);
            ClassInterface pointInterface = null;
            if (utilInterface != null) {
                for (ClassInterface candidate : utilInterface.classes()) {
                    if (candidate.classId().equals(POINT)) {
                        pointInterface = candidate;
                    }
                }
            }
            check(pointInterface != null,
                "the exported class carries its interface entry");
            ClassFactoryRegistry produced = result.registryOf(UTIL);
            check(produced != null,
                "the class-declaring module carries its class-factory registry");
            ClassFactoryRegistry registry = produced == null
                ? new ClassFactoryRegistry(Map.of()) : produced;
            if (pointInterface != null) {
                ClassFactoryId entry = pointInterface.constructionEntry();
                OpId factoryOpId = registry.factoryFor(entry);
                check(factoryOpId != null,
                    "the exported class's CLASS_FACTORY is registered under its "
                        + "constructionEntry");
                SemanticOp factory = null;
                for (SemanticOp op : util.ops()) {
                    if (op.opId().equals(factoryOpId)) {
                        factory = op;
                    }
                }
                check(factory != null
                        && factory.kind() == SemanticOpKind.CLASS_FACTORY
                        && factory.payload()
                            instanceof KindPayload.ClassFactoryPayload factoryPayload
                        && factoryPayload.classId().equals(POINT),
                    "the registered id names the exported class's CLASS_FACTORY op");
            }
            boolean hiddenFactory = false;
            for (SemanticOp op : util.ops()) {
                if (op.kind() == SemanticOpKind.CLASS_FACTORY
                        && op.payload()
                            instanceof KindPayload.ClassFactoryPayload factory
                        && factory.classId().equals(HIDDEN)) {
                    hiddenFactory = true;
                }
            }
            check(!hiddenFactory,
                "the local class never gets a CLASS_FACTORY (a non-exported "
                    + "class carries no factory id)");

            // The composed chain re-runs over the class-carrying unit.
            SemanticIrValidator.ComparisonFacts facts =
                comparisonFacts(project);
            check(SemanticIrValidator.validate(util, facts).isEmpty(),
                "the class-carrying unit passes the closed 14 rules");
            check(AddressChainProtocol.validate(util).isEmpty(),
                "the class-carrying unit passes the address-chain protocol");
            check(deal.semantic.ControlFlowValidator.validate(util,
                    result.tableOf(UTIL)).isEmpty(),
                "the class-carrying unit passes the control-flow validator");
            check(ClassConstructionValidator.validate(util, result.tableOf(UTIL),
                    registry, utilInterface,
                    Map.of()).isEmpty(),
                "the class-carrying unit passes the class-construction validator "
                    + "(the composed chain's class arm)");
        } finally {
            deleteRecursively(project.root());
        }
    }

    /**
     * ISSUE-0810: the per-construction default invocation carries
     * invocation-local attempt scratch. A default that re-enters its own
     * class's construction (through a module-level helper) must not
     * overwrite the enclosing attempt's operand slots: the outer
     * attempt's first operand (the host counter read before the nested
     * attempt) keeps its own value, so the outer default composes
     * {@code 0 * 1000 + 1100} and the probe succeeds. A clobbered attempt
     * composes the nested attempt's counter read and fails the probe with
     * the named tripwire, so the same subject pins the Oracle and both
     * production emitters. The two-required-default subjects pin the other
     * half: each attempt's produced default values are its own, so a nested
     * attempt (completing or failing) never replaces an enclosing attempt's
     * earlier default value — in the same-module LOCAL arm and in the
     * imported SHARED_FACTORY transfer alike.
     */
    private static void testDefaultReentrantAttemptScratch() throws Exception {
        System.out.println("-- the per-construction default invocation's "
            + "invocation-local attempt scratch: a nested construction inside a "
            + "default keeps the enclosing attempt's operands --");
        executeReentrantDefaultOracle(DEFAULT_REENTRANCY_APP_SOURCE,
            "the nested construction completes", Map.of());
        executeReentrantDefaultOracle(DEFAULT_REENTRANCY_FAILURE_APP_SOURCE,
            "the failed nested attempt restores the enclosing operands", Map.of());
        executeReentrantDefaultOracle(DEFAULT_TWO_DEFAULTS_APP_SOURCE,
            "the nested construction keeps the enclosing attempt's produced "
                + "default values", Map.of());
        executeReentrantDefaultOracle(DEFAULT_TWO_DEFAULTS_FAILURE_APP_SOURCE,
            "the failed nested attempt keeps the enclosing attempt's produced "
                + "default values", Map.of());
        executeReentrantDefaultOracle(IMPORTED_DEFAULT_TWO_DEFAULTS_APP_SOURCE,
            "the imported class's transfer keeps the enclosing attempt's "
                + "produced default values",
            Map.of("src/lib.deal", IMPORTED_DEFAULT_TWO_DEFAULTS_LIB_SOURCE));
        executeReentrantDefaultOracle(IMPORTED_DEFAULT_TWO_DEFAULTS_APP_SOURCE,
            "the imported class's transfer keeps the enclosing attempt's "
                + "produced default values after a failed nested attempt",
            Map.of("src/lib.deal",
                IMPORTED_DEFAULT_TWO_DEFAULTS_FAILURE_LIB_SOURCE));
    }

    /**
     * One re-entrant-default subject through the Oracle: the host responder
     * counts every {@code provideFirst} omission and answers {@code calls()}
     * with that counter, so the subject's exact tripwire observes whether a
     * nested attempt overwrote the enclosing attempt's operand slot or one of
     * its produced default values. {@code extraSources} carries the provider
     * module of an imported-class subject.
     */
    private static void executeReentrantDefaultOracle(String source, String what,
            Map<String, String> extraSources) throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("src/retained_default.d.deal", RETAINED_DEFAULT_DECLARATION);
        sources.putAll(extraSources);
        RealProject project = compileProject(source, sources,
            Map.of("host/retained_default", "src/retained_default.d.deal"));
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(project, invocation());
            check(!result.hasErrors() && result.project() != null,
                "the re-entrant-default subject lowers through the one project "
                    + "lowering: " + result.diagnostics());
            if (result.project() == null) {
                return;
            }
            int[] calls = {0};
            SemanticRuntimeModel.ConsumerRun run =
                SemanticOracle.executeProjectInits(result.project(), result.tables(),
                    result.registries(), new SemanticOracle.HostResponder() {
                        @Override
                        public SyncOutcome call(ModuleId module, String export,
                                RuntimeDescriptor.Func descriptor,
                                List<SemanticOracle.Value> args) {
                            if (!"host.retained_default".equals(module.path())) {
                                return new SyncOutcome.Thrown("E9001",
                                    "unknown host module " + module.path());
                            }
                            return switch (export) {
                                case "calls" -> new SyncOutcome.Returned(
                                    new SemanticOracle.Value.NumValue(calls[0]));
                                case "provideFirst" -> {
                                    calls[0]++;
                                    yield new SyncOutcome.Returned(
                                        new SemanticOracle.Value.ArrayValue(
                                            List.of(), RuntimeDescriptor.Number.INSTANCE,
                                            false));
                                }
                                default -> new SyncOutcome.Thrown("E9001",
                                    "unknown host export " + export);
                            };
                        }
                    });
            check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                "the oracle " + what + ": " + run.comparisonReport());
            List<String> consoleWrites = new ArrayList<>();
            for (SemanticRuntimeModel.EffectEvent effect : run.effects()) {
                if (effect.kind()
                        == SemanticRuntimeModel.EffectEvent.Kind.CONSOLE_WRITE) {
                    consoleWrites.add(effect.text());
                }
            }
            checkEq(List.of("ok"), consoleWrites,
                "the oracle's probe reaches the console write exactly once: "
                    + run.comparisonReport());
        } finally {
            deleteRecursively(project.root());
        }
    }

    private static void testImportedInProjectClassResolution(LoweredProject imported)
            throws Exception {
        System.out.println("-- the in-project imported-class construction: "
            + "CLASS_NEW(SHARED_FACTORY) with the owner's factory reference --");
        RealProject project = imported.project();
        {
            SemanticLowerer.ProjectLoweringResult result = imported.result();
            check(!result.hasErrors() && result.project() != null,
                "the imported in-project class literal lowers through the one "
                    + "project entry: " + result.diagnostics());
            boolean retainedAbiDeferred = false;
            for (CompilerDiagnostic diagnostic : result.diagnostics()) {
                if (diagnostic.message().contains("RETAINED_ABI_DEFERRED")) {
                    retainedAbiDeferred = true;
                }
            }
            check(!retainedAbiDeferred,
                "the checker-valid in-project input reports zero "
                    + "RETAINED_ABI_DEFERRED");
            if (result.project() == null) {
                return;
            }
            LoweredModuleUnit util = result.project().modules().get(UTIL);
            LoweredModuleUnit app = result.project().modules().get(APP);
            check(util != null && app != null,
                "both the owner and the dependent module are in the closure");
            if (util == null || app == null) {
                return;
            }
            // The in-project records of the owner: the interface entry from
            // the project index, the owner unit's layout, the owner
            // registry's factory op id for the interface constructionEntry,
            // and the factory op's result ValueId.
            ExternalModuleInterface utilInterface = project.index().modules().get(UTIL);
            ClassInterface pointEntry = null;
            if (utilInterface != null) {
                for (ClassInterface candidate : utilInterface.classes()) {
                    if (candidate.classId().equals(POINT)) {
                        pointEntry = candidate;
                    }
                }
            }
            ClassLayout ownerLayout = util.classLayouts().get(POINT);
            ClassFactoryRegistry ownerRegistry = result.registryOf(UTIL);
            check(pointEntry != null && ownerLayout != null && ownerRegistry != null,
                "the owner's interface entry, layout, and registry resolve");
            if (pointEntry == null || ownerLayout == null || ownerRegistry == null) {
                return;
            }
            OpId factoryOpId = ownerRegistry.factoryFor(pointEntry.constructionEntry());
            SemanticOp factoryOp = factoryOpId == null ? null : opOf(util, factoryOpId);
            check(factoryOpId != null && factoryOp != null
                    && factoryOp.kind() == SemanticOpKind.CLASS_FACTORY
                    && factoryOp.result() instanceof ValueId,
                "the owner's CLASS_FACTORY is registered under the interface "
                    + "constructionEntry with a result value");
            if (factoryOpId == null || factoryOp == null
                    || !(factoryOp.result() instanceof ValueId factoryResult)) {
                return;
            }
            SharedFactoryFacts facts = new SharedFactoryFacts(POINT, pointEntry,
                ownerLayout, factoryOpId, factoryResult);

            // The dependent module's imported literals: one CLASS_NEW per
            // constructed imported class, each with constructionProvider
            // SHARED_FACTORY, the owner's constructionEntry as the factory
            // reference, an empty CLASS_DEFAULT child list, the provided
            // fields, and the declaration-order boundary list.
            Map<ClassId, KindPayload.ClassNewPayload> constructions =
                new LinkedHashMap<>();
            for (SemanticOp op : app.ops()) {
                if (op.kind() == SemanticOpKind.CLASS_NEW
                        && op.payload() instanceof KindPayload.ClassNewPayload candidate) {
                    constructions.put(candidate.classId(), candidate);
                }
            }
            checkEq(Set.of(POINT, SEGMENT), constructions.keySet(),
                "the dependent module lowers one CLASS_NEW per constructed imported "
                    + "in-project class");
            KindPayload.ClassNewPayload payload = constructions.get(POINT);
            check(payload != null, "the imported Point literal lowered CLASS_NEW");
            if (payload == null) {
                return;
            }
            checkEq(ConstructionProvider.SHARED_FACTORY, payload.constructionProvider(),
                "the imported literal carries constructionProvider SHARED_FACTORY");
            checkEq(facts.interfaceEntry().constructionEntry(),
                payload.classFactoryRef(),
                "the literal carries the owner's interface constructionEntry as its "
                    + "factory reference");
            checkEq(ownerLayout, payload.layout(),
                "the payload layout is the owner unit's layout, resolved through the "
                    + "in-project interface facts");
            checkEq(List.of("x", "y"), payload.providedFields().stream()
                    .map(KindPayload.ProvidedField::name).toList(),
                "the provided fields stay in literal order");
            checkEq(List.of("x", "y"), payload.fieldBoundaries().stream()
                    .map(KindPayload.FieldBoundary::field).toList(),
                "the field boundaries run in declaration order");
            checkEq(List.of(BoundaryKind.CLASS_LITERAL_FIELD,
                    BoundaryKind.CLASS_LITERAL_FIELD),
                payload.fieldBoundaries().stream()
                    .map(KindPayload.FieldBoundary::kind).toList(),
                "both provided fields carry literal boundaries");
            SemanticOp defaultBoundary = opOf(app,
                payload.fieldBoundaries().get(1).boundaryOpId());
            check(defaultBoundary != null
                    && defaultBoundary.payload()
                        instanceof KindPayload.BoundaryPayload boundary
                    && boundary.input().equals(payload.providedFields().get(1).valueOpId()),
                "the second boundary uses the second provided value, not provider metadata");

            // Repeats are deterministic: the same accumulation and walk
            // produce the byte-identical dependent unit.
            SemanticLowerer.ProjectLoweringResult repeat = lower(project, invocation());
            check(repeat.project() != null
                    && java.util.Arrays.equals(
                        SemanticIrDumper.dumpModule(app),
                        SemanticIrDumper.dumpModule(
                            repeat.project().modules().get(APP))),
                "the repeated lowering of the imported in-project class project is "
                    + "byte-identical");

            // Every exported class of the lowered owner accumulates its own
            // fact (the loop, not one fixture class): the second class
            // constructs SHARED_FACTORY with its own constructionEntry and
            // its own owner layout.
            ClassInterface segmentEntry = null;
            if (utilInterface != null) {
                for (ClassInterface candidate : utilInterface.classes()) {
                    if (candidate.classId().equals(SEGMENT)) {
                        segmentEntry = candidate;
                    }
                }
            }
            ClassLayout segmentLayout = util.classLayouts().get(SEGMENT);
            OpId segmentFactoryOpId = segmentEntry == null ? null
                : ownerRegistry.factoryFor(segmentEntry.constructionEntry());
            SemanticOp segmentFactoryOp = segmentFactoryOpId == null ? null
                : opOf(util, segmentFactoryOpId);
            KindPayload.ClassNewPayload segment = constructions.get(SEGMENT);
            check(segment != null && segmentEntry != null && segmentLayout != null
                    && segment.constructionProvider() == ConstructionProvider.SHARED_FACTORY
                    && segment.classFactoryRef().equals(
                        segmentEntry.constructionEntry())
                    && segment.layout().equals(segmentLayout)
                    && segmentFactoryOp != null
                    && segmentFactoryOp.kind() == SemanticOpKind.CLASS_FACTORY,
                "every exported class of the lowered owner accumulates its own fact "
                    + "(the second class constructs SHARED_FACTORY with its own "
                    + "constructionEntry and layout)");

            // The composed chain re-runs over the dependent unit with the
            // in-project facts of both classes (the class-construction arm's
            // imported path).
            Map<ClassId, SharedFactoryFacts> inProjectFacts = new LinkedHashMap<>();
            inProjectFacts.put(POINT, facts);
            if (segmentEntry != null && segmentLayout != null
                    && segmentFactoryOpId != null && segmentFactoryOp != null
                    && segmentFactoryOp.result() instanceof ValueId segmentResult) {
                inProjectFacts.put(SEGMENT, new SharedFactoryFacts(SEGMENT,
                    segmentEntry, segmentLayout, segmentFactoryOpId, segmentResult));
            }
            check(ClassConstructionValidator.validate(app, result.tableOf(APP),
                    result.registryOf(APP), project.index().modules().get(APP),
                    inProjectFacts).isEmpty(),
                "the dependent unit passes the class-construction validator with the "
                    + "in-project facts");

            // The combined behavior: the dependent module's cross-module call
            // resolves through the accumulated EXTERNAL_ENTRY records.
            KindPayload.CallPayload externalCall = null;
            for (SemanticOp op : app.ops()) {
                if (op.kind() == SemanticOpKind.CALL
                        && op.payload() instanceof KindPayload.CallPayload call
                        && call.callee()
                            instanceof KindPayload.CallCallee.Static staticCallee
                        && staticCallee.binding()
                            instanceof FunctionExecutionBinding.ExternalFunction external
                        && external.moduleId().equals(UTIL)) {
                    externalCall = call;
                    check(external.executionOwner()
                            == deal.semantic.ir.ExternalExecutionOwner.SHARED_BODY,
                        "the cross-module callee is a SHARED_BODY external (no "
                            + "retained-ABI owner enters the project lowering)");
                }
            }
            check(externalCall != null,
                "the dependent module's cross-module call carries the in-project "
                    + "external binding");
            if (externalCall != null) {
                OpId entryRef = externalCall.externalEntryRef();
                SemanticOp entry = entryRef == null ? null : opOf(util, entryRef);
                check(entry != null
                        && entry.kind() == SemanticOpKind.EXTERNAL_ENTRY
                        && entry.payload()
                            instanceof KindPayload.ExternalEntryPayload entryPayload
                        && entryPayload.exportName().equals("makePoint"),
                    "the call's externalEntryRef names the owner's recorded "
                        + "EXTERNAL_ENTRY (the accumulated in-project record)");
            }
        }
    }


    private static void testSameSpelledProviderResolution() throws Exception {
        System.out.println("-- same-spelled classes of distinct providers: the "
            + "construction, field, default and JSON links resolve the exact "
            + "declared provider --");
        RealProject project = compileProject(SAME_SPELLED_APP_SOURCE,
            Map.of("src/alpha/util.deal", SAME_SPELLED_ALPHA_SOURCE,
                "src/beta/util.deal", SAME_SPELLED_BETA_SOURCE), Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(project, invocation());
            check(!result.hasErrors() && result.project() != null,
                "the same-spelled provider project lowers through the one project "
                    + "entry: " + result.diagnostics());
            if (result.project() == null) {
                return;
            }
            ExecutableLoweredProject lowered = result.project();
            ModuleId alpha = new ModuleId("alpha.util");
            ModuleId beta = new ModuleId("beta.util");
            ClassId alphaPoint = new ClassId("src/alpha/util.deal", "Point");
            ClassId betaPoint = new ClassId("src/beta/util.deal", "Point");
            LoweredModuleUnit alphaUnit = lowered.modules().get(alpha);
            LoweredModuleUnit betaUnit = lowered.modules().get(beta);
            LoweredModuleUnit app = lowered.modules().get(APP);
            check(alphaUnit != null && betaUnit != null && app != null,
                "the closure carries both providers and the consumer");
            if (alphaUnit == null || betaUnit == null || app == null) {
                return;
            }
            check(alphaUnit.classLayouts().containsKey(alphaPoint)
                    && betaUnit.classLayouts().containsKey(betaPoint)
                    && !alphaPoint.equals(betaPoint),
                "the two providers keep distinct layouts under distinct class ids");
            ClassFactoryId alphaEntry = factoryEntryOf(alphaUnit, alphaPoint);
            ClassFactoryId betaEntry = factoryEntryOf(betaUnit, betaPoint);
            check(alphaEntry != null && betaEntry != null
                    && !alphaEntry.equals(betaEntry),
                "the two providers register distinct construction entries");

            Map<ClassId, KindPayload.ClassNewPayload> constructions =
                new LinkedHashMap<>();
            for (SemanticOp op : app.ops()) {
                if (op.kind() == SemanticOpKind.CLASS_NEW
                        && op.payload() instanceof KindPayload.ClassNewPayload payload) {
                    constructions.put(payload.classId(), payload);
                }
            }
            KindPayload.ClassNewPayload alphaLiteral = constructions.get(alphaPoint);
            KindPayload.ClassNewPayload betaLiteral = constructions.get(betaPoint);
            check(alphaLiteral != null && betaLiteral != null,
                "the consumer lowers one CLASS_NEW per distinct provider");
            if (alphaLiteral == null || betaLiteral == null) {
                return;
            }
            checkEq(ConstructionProvider.SHARED_FACTORY, alphaLiteral.constructionProvider(),
                "the imported alpha literal carries SHARED_FACTORY");
            checkEq(alphaEntry, alphaLiteral.classFactoryRef(),
                "the alpha literal references the alpha provider's construction entry");
            checkEq(ConstructionProvider.SHARED_FACTORY, betaLiteral.constructionProvider(),
                "the imported beta literal carries SHARED_FACTORY");
            checkEq(betaEntry, betaLiteral.classFactoryRef(),
                "the beta literal references the beta provider's construction entry");
            checkEq(alphaUnit.classLayouts().get(alphaPoint), alphaLiteral.layout(),
                "the alpha literal carries the alpha provider's layout");
            checkEq(betaUnit.classLayouts().get(betaPoint), betaLiteral.layout(),
                "the beta literal carries the beta provider's layout");

            // The field operations select the declaring field's descriptor
            // from the provider layout.
            List<SemanticOp> writes = new ArrayList<>();
            List<SemanticOp> deletes = new ArrayList<>();
            for (SemanticOp op : app.ops()) {
                if (op.kind() == SemanticOpKind.FIELD_WRITE) {
                    writes.add(op);
                }
                if (op.kind() == SemanticOpKind.FIELD_DELETE) {
                    deletes.add(op);
                }
            }
            checkEq(2, writes.size(), "the consumer lowers two FIELD_WRITEs");
            checkEq(1, deletes.size(), "the consumer lowers one FIELD_DELETE");
            for (SemanticOp write : writes) {
                KindPayload.FieldWritePayload payload =
                    (KindPayload.FieldWritePayload) write.payload();
                checkEq(alphaPoint, payload.classId(),
                    "every FIELD_WRITE names the alpha provider's class");
                checkEq("src/alpha/util.deal", payload.classId().modulePath(),
                    "the field write's class id carries the provider's module"
                        + " path (the defining file included)");
            }
            if (!deletes.isEmpty()
                    && deletes.get(0).payload()
                        instanceof KindPayload.FieldDeletePayload deletePayload) {
                checkEq("nick", deletePayload.field(),
                    "the FIELD_DELETE names the optional provider field");
            }

            // The lowered project admits on both project surfaces and its
            // own records reassemble from the dump.
            SemanticIrValidator.ComparisonFacts facts =
                comparisonFacts(project);
            String text = assertProjectAdmission(lowered, facts,
                "the same-spelled provider project passes the project surfaces");
            assertReassembly(text, SemanticIrDumper.dumpProject(lowered),
                "the same-spelled provider project reassembles exactly");

            // The Oracle executes the probe: the per-construction defaults,
            // the provider field write and delete, the array and nullable
            // constructions all hold, so the terminal is the probe's success.
            SemanticRuntimeModel.ConsumerRun run =
                SemanticOracle.executeProjectInits(lowered, result.tables(),
                    result.registries(), null);
            check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                "the oracle completes the same-spelled provider probe: "
                    + run.comparisonReport());
        } finally {
            deleteRecursively(project.root());
        }
    }

    private static void testSameNameSiblingProviderResolution() throws Exception {
        System.out.println("-- same-named sibling providers of one canonical "
            + "identity: the recorded contextual provider selects the exact "
            + "declaring declaration --");
        RealProject project = compileProject(SIBLING_APP_SOURCE,
            Map.of("src/m/ir.deal", SIBLING_IR_SOURCE,
                "src/m/codec.deal", SIBLING_CODEC_SOURCE), Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(project, invocation());
            check(!result.hasErrors() && result.project() != null,
                "the same-named sibling project lowers through the one project "
                    + "entry: " + result.diagnostics());
            if (result.project() == null) {
                return;
            }
            ExecutableLoweredProject lowered = result.project();
            ModuleId ir = new ModuleId("m.ir");
            ModuleId codec = new ModuleId("m.codec");
            LoweredModuleUnit irUnit = lowered.modules().get(ir);
            LoweredModuleUnit codecUnit = lowered.modules().get(codec);
            LoweredModuleUnit app = lowered.modules().get(APP);
            check(irUnit != null && codecUnit != null && app != null,
                "the closure carries both siblings and the consumer");
            if (irUnit == null || codecUnit == null || app == null) {
                return;
            }
            ClassId irId = new ClassId("src/m/ir.deal", "Ir");
            ClassId codecId = new ClassId("src/m/codec.deal", "Ir");
            ClassFactoryId irEntry = factoryEntryOf(irUnit, irId);
            ClassFactoryId codecEntry = factoryEntryOf(codecUnit, codecId);
            check(irEntry != null && codecEntry != null
                    && !irEntry.equals(codecEntry),
                "the sibling declarations carry distinct construction entries");
            check(lowered.classSeeds().registrationFor(irId) == null,
                "the implementation sibling pair acquires no declaration seed");

            Map<ClassFactoryId, Integer> constructionCounts = new LinkedHashMap<>();
            int irConstructions = 0;
            for (SemanticOp op : app.ops()) {
                if (op.kind() == SemanticOpKind.CLASS_NEW
                        && op.payload() instanceof KindPayload.ClassNewPayload payload
                        && payload.classId().equals(irId)) {
                    irConstructions++;
                    checkEq(ConstructionProvider.SHARED_FACTORY, payload.constructionProvider(),
                        "every sibling construction transfers to the provider factory");
                    if (payload.classFactoryRef() != null) {
                        constructionCounts.merge(payload.classFactoryRef(), 1,
                            Integer::sum);
                    }
                }
            }
            checkEq(Set.of(irEntry), constructionCounts.keySet(),
                "every app construction references the recorded ir sibling's "
                    + "construction entry (never the codec sibling's)");
            checkEq(5, irConstructions,
                "all five recorded contexts construct the ir sibling "
                    + "(literal, call argument, nested field default, nested array "
                    + "default, literal array)");

            SemanticIrValidator.ComparisonFacts facts =
                comparisonFacts(project);
            String text = assertProjectAdmission(lowered, facts,
                "the same-named sibling project passes the project surfaces");
            assertReassembly(text, SemanticIrDumper.dumpProject(lowered),
                "the same-named sibling project reassembles exactly");

            SemanticRuntimeModel.ConsumerRun run =
                SemanticOracle.executeProjectInits(lowered, result.tables(),
                    result.registries(), null);
            check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                "the oracle completes the sibling probe (the demanded default 7, "
                    + "never 93): " + run.comparisonReport());
        } finally {
            deleteRecursively(project.root());
        }
    }

    /**
     * The same-named declaration crossing: two files in one directory
     * declare the same class name as two distinct declarations (the
     * canonical identity names the defining file), so the imported
     * declaration's value never retags to the consumer's same-named
     * declaration. The production pipeline refuses the project at the
     * frontend with E3019 and publishes nothing — never an artifact whose
     * field layout belongs to the other declaration.
     */
    private static void testSameIdentityProviderCrossingRejected() throws Exception {
        System.out.println("-- a same-named declaration crossing is a frontend "
            + "E3019 and publishes no project --");
        Path proj = Files.createTempDirectory("project-lowering-crossing");
        try {
            writeFileIn(proj, "src/other.deal", """
                export class Point { nick: number; }
                """);
            writeFileIn(proj, "src/app.deal", """
                import * as other from "./other";

                class Point { nick: string; }

                export function main(): null {
                  let y: other.Point = {nick: 1};
                  let p: Point = y;
                  let s: string = p.nick;
                  return null;
                }
                """);
            assertCrossingRejected(proj,
                "the identifier-assignment crossing");
        } finally {
            deleteRecursively(proj);
        }

        // The nested-call literal beside a same-named local declaration
        // (ISSUE-0809 review cycle 2 finding 1): the argument's own
        // expectation names the callee parameter's provider, so the literal
        // is laid out by the imported declaration and is never captured by
        // the enclosing annotation's same-named local declaration. The
        // legal shape lowers and executes the imported provider's
        // construction (previously the literal was attributed to the local
        // declaration and the sibling-parameter call was refused).
        RealProject nestedProject = compileProject(NESTED_CALL_APP_SOURCE,
            Map.of("src/ir.deal", NESTED_CALL_IR_SOURCE), Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result =
                lower(nestedProject, invocation());
            check(!result.hasErrors() && result.project() != null,
                "the nested-call literal beside a same-named local "
                    + "declaration lowers: " + result.diagnostics());
            if (result.project() == null) {
                return;
            }
            ExecutableLoweredProject lowered = result.project();
            LoweredModuleUnit irUnit = lowered.modules()
                .get(new ModuleId("ir"));
            LoweredModuleUnit app = lowered.modules().get(APP);
            check(irUnit != null && app != null,
                "the nested-call closure carries the provider and the consumer");
            if (irUnit == null || app == null) {
                return;
            }
            ClassId irId = new ClassId("src/ir.deal", "Ir");
            ClassId localId = new ClassId("src/app.deal", "Ir");
            ClassFactoryId irEntry = factoryEntryOf(irUnit, irId);
            check(irEntry != null,
                "the nested-call provider registers its construction entry");
            if (irEntry == null) {
                return;
            }
            int shared = 0;
            int local = 0;
            for (SemanticOp op : app.ops()) {
                if (op.kind() != SemanticOpKind.CLASS_NEW
                        || !(op.payload()
                            instanceof KindPayload.ClassNewPayload payload)) {
                    continue;
                }
                if (payload.classId().equals(irId)) {
                    checkEq(ConstructionProvider.SHARED_FACTORY, payload.constructionProvider(),
                        "every imported declaration literal transfers to its"
                            + " provider factory");
                    shared++;
                    checkEq(irEntry, payload.classFactoryRef(),
                        "the nested-call argument literal references the "
                            + "callee parameter provider's entry");
                    checkEq(irUnit.classLayouts().get(irId), payload.layout(),
                        "the nested-call argument literal carries the "
                            + "provider's layout");
                } else if (payload.classId().equals(localId)) {
                    local++;
                    checkEq(ConstructionProvider.LOCAL, payload.constructionProvider(),
                        "the declared-return literal stays LOCAL");
                    checkEq(app.classLayouts().get(localId), payload.layout(),
                        "the declared-return literal carries the unit's own"
                            + " declaration layout");
                }
            }
            checkEq(2, shared,
                "both nested-call argument literals construct the imported "
                    + "provider (never the consumer's same-named sibling"
                    + " declaration)");
            checkEq(1, local,
                "the local declared-return literal constructs the unit's own "
                    + "declaration");
            SemanticRuntimeModel.ConsumerRun run =
                SemanticOracle.executeProjectInits(lowered, result.tables(),
                    result.registries(), null);
            check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                "the oracle executes the nested-call program: "
                    + run.comparisonReport());
        } finally {
            deleteRecursively(nestedProject.root());
        }
    }

    /**
     * The unrepresentable defining-file name (ISSUE-0847 review cycle 1):
     * the canonical identity names the defining file, so a file whose name
     * is not representable in the descriptor form ({@code a@b.deal}: the
     * {@code @} is a descriptor metacharacter) can carry no public class
     * identity. The required-identity gate refuses it with E2010 at the
     * class name span on every backend — never a backend-dependent raw
     * exception at descriptor emission, and never a published artifact.
     */
    private static void testUnrepresentableDefiningFileNameRejected()
            throws Exception {
        System.out.println("-- an unrepresentable defining-file name is E2010 "
            + "on every backend and publishes no project --");
        Path proj = Files.createTempDirectory("project-lowering-unrep");
        try {
            writeFileIn(proj, "src/a@b.deal", """
                export class Item { value: number; }
                export function make(): Item { return {value: 1}; }
                """);
            writeFileIn(proj, "src/app.deal", """
                import * as a from "./a@b";

                export function main(): null {
                  let x: a.Item = a.make();
                  return null;
                }
                """);
            for (Backend backend : List.of(Backend.LUAJIT, Backend.JVM,
                    Backend.JS)) {
                assertUnrepresentableDefiningFileRejected(proj, backend);
            }
        } finally {
            deleteRecursively(proj);
        }
    }

    /**
     * The nested-call literal fixture (ISSUE-0809 review cycle 2 finding
     * 1): the enclosing annotation names the local declaration while the
     * callee parameter names the same-named imported one; the
     * argument literal must be laid out by the parameter's declaration.
     */
    private static final String NESTED_CALL_IR_SOURCE = """
        export class Ir { a: number; }
        export function g(x: Ir): Ir { return x; }
        """;

    private static final String NESTED_CALL_APP_SOURCE = """
        import * as ir from "./ir";

        class Ir { b: string; }

        function f(x: ir.Ir): Ir { return {b: "local"}; }

        export function main(): null {
          let p: Ir = f(ir.g({a: 7}));
          let n: number = ir.g({a: 7}).a;
          if (n !== 7) { throw { code: "WRONG_N", message: "n" }; }
          if (p.b !== "local") { throw { code: "WRONG_B", message: "b" }; }
          return null;
        }
        """;

    /**
     * Compiles the project at its {@code src/app.deal} entry through the
     * production pipeline and asserts the same-named declaration crossing
     * is refused with E3019 and no partial or published view.
     */
    private static void assertCrossingRejected(Path proj, String label)
            throws Exception {
        Path entry = proj.resolve("src/app.deal").toAbsolutePath();
        Path output = proj.resolve("out").toAbsolutePath();
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entry, output, false, false, false, false, Backend.LUAJIT,
            Map.of(), List.of(proj.resolve("src").toAbsolutePath()),
            Path.of(".").toAbsolutePath().normalize(), null,
            ConformanceHarnessMetadata.invocation());
        check(!orchestrator.compile(),
            label + " is refused, never published: "
                + orchestrator.diagnostics());
        check(orchestrator.diagnostics().stream()
                .anyMatch(d -> "E3019".equals(d.code())),
            "the refused project names " + label + " E3019: "
                + orchestrator.diagnostics());
        check(orchestrator.checkedProject() == null,
            "the refused project publishes no partial checked project ("
                + label + ")");
        check(!Files.exists(output) || isEmptyTree(output),
            "the refused project publishes no output artifact (" + label
                + "): " + output);
    }

    /**
     * Asserts one backend refuses the fixture's unrepresentable defining
     * file name: E2010 during the required-identity gate, the offending
     * file-name component named in the diagnostic, no partially checked
     * project and no published artifact.
     */
    private static void assertUnrepresentableDefiningFileRejected(Path proj,
            Backend backend) throws Exception {
        Path entry = proj.resolve("src/app.deal").toAbsolutePath();
        Path output = proj.resolve("out-" + backend.name().toLowerCase())
            .toAbsolutePath();
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entry, output, false, false, false, false, backend,
            Map.of(), List.of(proj.resolve("src").toAbsolutePath()),
            Path.of(".").toAbsolutePath().normalize(), null,
            ConformanceHarnessMetadata.invocation());
        check(!orchestrator.compile(),
            "the unrepresentable defining-file name is refused on " + backend
                + ": " + orchestrator.diagnostics());
        check(orchestrator.diagnostics().stream()
                .anyMatch(d -> "E2010".equals(d.code())
                    && d.message().contains("'a@b.deal'")),
            "the refusal names the defining-file component on " + backend
                + ": " + orchestrator.diagnostics());
        check(orchestrator.checkedProject() == null,
            "the refused project publishes no partial checked project on "
                + backend);
        check(!Files.exists(output) || isEmptyTree(output),
            "the refused project publishes no output artifact on " + backend
                + ": " + output);
    }

    /** True when the tree contains no regular file and no directory entry. */
    private static boolean isEmptyTree(Path root) {
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (var entries = Files.list(root)) {
            return entries.findAny().isEmpty();
        } catch (java.io.IOException defect) {
            return false;
        }
    }

    /**
     * The nested-field provider fixture (ISSUE-0809 review cycle 2
     * finding 1): the enclosing class's field names the same-named
     * sibling provider, so the nested literal must be laid out by the
     * field's declaration, never by the enclosing literal's contextual
     * declaration.
     */
    private static final String NESTED_FIELD_IR_SOURCE = """
        import * as codec from "./codec";
        export class Ir { inner: codec.Ir; a: number; }
        """;

    private static final String NESTED_FIELD_CODEC_SOURCE = """
        export class Ir { b: number; }
        """;

    private static final String NESTED_FIELD_APP_SOURCE = """
        import * as ir from "./m/ir";
        export function main(): null {
          let x: ir.Ir = { inner: {b: 93}, a: 7 };
          let b: number = x.inner.b;
          if (b !== 93) { throw { code: "WRONG_B", message: "b" }; }
          let a: number = x.a;
          if (a !== 7) { throw { code: "WRONG_A", message: "a" }; }
          return null;
        }
        """;

    /**
     * The nested class-literal provider (ISSUE-0809 review cycle 2
     * finding 1): the enclosing class's field declared as the identity-
     * equal sibling owns the nested literal, so the nested construction
     * (and its per-construction default) comes from that declaration even
     * when the enclosing construction's contextual declaration shares the
     * canonical identity. The enclosing literal keeps its own provider.
     */
    private static void testNestedFieldProviderBesideSameIdentityLocal()
            throws Exception {
        System.out.println("-- the class-typed field's declaring provider owns its "
            + "nested literal beside a same-named sibling --");
        RealProject project = compileProject(NESTED_FIELD_APP_SOURCE,
            Map.of("src/m/ir.deal", NESTED_FIELD_IR_SOURCE,
                "src/m/codec.deal", NESTED_FIELD_CODEC_SOURCE),
            Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result =
                lower(project, invocation());
            check(!result.hasErrors() && result.project() != null,
                "the nested-field provider project lowers: "
                    + result.diagnostics());
            if (result.project() == null) {
                return;
            }
            ExecutableLoweredProject lowered = result.project();
            LoweredModuleUnit irUnit = lowered.modules().get(new ModuleId("m.ir"));
            LoweredModuleUnit codecUnit = lowered.modules()
                .get(new ModuleId("m.codec"));
            LoweredModuleUnit app = lowered.modules().get(APP);
            check(irUnit != null && codecUnit != null && app != null,
                "the nested-field closure carries both providers and the "
                    + "consumer");
            if (irUnit == null || codecUnit == null || app == null) {
                return;
            }
            ClassId irId = new ClassId("src/m/ir.deal", "Ir");
            ClassId codecId = new ClassId("src/m/codec.deal", "Ir");
            ClassFactoryId irEntry = factoryEntryOf(irUnit, irId);
            ClassFactoryId codecEntry = factoryEntryOf(codecUnit, codecId);
            check(irEntry != null && codecEntry != null
                    && !irEntry.equals(codecEntry),
                "both same-declaration entries stay distinct");
            if (irEntry == null || codecEntry == null) {
                return;
            }
            int irConstructions = 0;
            int codecConstructions = 0;
            for (SemanticOp op : app.ops()) {
                if (op.kind() != SemanticOpKind.CLASS_NEW
                        || !(op.payload()
                            instanceof KindPayload.ClassNewPayload payload)) {
                    continue;
                }
                if (payload.classId().equals(irId)) {
                    irConstructions++;
                    checkEq(irEntry, payload.classFactoryRef(),
                        "the enclosing literal transfers to the ir provider");
                    checkEq(irUnit.classLayouts().get(irId), payload.layout(),
                        "the enclosing literal carries the ir provider's layout");
                } else if (payload.classId().equals(codecId)) {
                    codecConstructions++;
                    checkEq(codecEntry, payload.classFactoryRef(),
                        "the nested literal transfers to the codec provider");
                    checkEq(codecUnit.classLayouts().get(codecId), payload.layout(),
                        "the nested literal carries the codec provider's layout");
                }
            }
            checkEq(1, irConstructions,
                "the enclosing construction references the ir provider");
            checkEq(1, codecConstructions,
                "the nested field literal references the codec provider (never "
                    + "the enclosing contextual declaration)");
            SemanticIrValidator.ComparisonFacts facts =
                comparisonFacts(project);
            assertProjectAdmission(lowered, facts,
                "the nested-field provider project passes the project surfaces");
            SemanticRuntimeModel.ConsumerRun run =
                SemanticOracle.executeProjectInits(lowered, result.tables(),
                    result.registries(), null);
            check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                "the oracle executes the nested-field provider project: "
                    + run.comparisonReport());
        } finally {
            deleteRecursively(project.root());
        }
    }

    /**
     * The transitive-provider fixture (ISSUE-0809 review cycle 2 finding
     * 2): the consumer imports only the intermediate module, whose field
     * declaration names the same-named sibling provider's class; both
     * same-directory modules declare {@code Part}.
     */
    private static final String TRANSITIVE_IR_SOURCE = """
        import * as codec from "./codec";
        export class Part { z: string; }
        export class Outer { inner: codec.Part; }
        """;

    private static final String TRANSITIVE_CODEC_SOURCE = """
        export class Part { b: number; }
        """;

    private static final String TRANSITIVE_APP_SOURCE = """
        import * as ir from "./m/ir";
        export function main(): null {
          let x: ir.Outer = { inner: {b: 93} };
          let b: number = x.inner.b;
          if (b !== 93) { throw { code: "WRONG_B", message: "b" }; }
          return null;
        }
        """;

    /**
     * The transitive provider literal (ISSUE-0809 review cycle 2 finding
     * 2): the consumer's nested literal is attributed to the sibling
     * declaration the imported field annotation names, whose snapshot the
     * whole-project provider index carries — never to a same-named import
     * of another declaration. The construction, the recorded plan
     * occurrence and the runtime edge all name the exact declaration, and
     * the artifact executes its default.
     */
    private static void testTransitiveProviderLiteralBesideSameIdentitySibling()
            throws Exception {
        System.out.println("-- a transitive provider literal resolves the "
            + "declaring module's exact declaration, never a same-named "
            + "sibling import --");
        RealProject project = compileProject(TRANSITIVE_APP_SOURCE,
            Map.of("src/m/ir.deal", TRANSITIVE_IR_SOURCE,
                "src/m/codec.deal", TRANSITIVE_CODEC_SOURCE),
            Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result =
                lower(project, invocation());
            check(!result.hasErrors() && result.project() != null,
                "the transitive provider project lowers: "
                    + result.diagnostics());
            if (result.project() == null) {
                return;
            }
            ExecutableLoweredProject lowered = result.project();
            ModuleId codecModule = new ModuleId("m.codec");
            LoweredModuleUnit codecUnit = lowered.modules().get(codecModule);
            LoweredModuleUnit app = lowered.modules().get(APP);
            check(codecUnit != null && app != null,
                "the transitive closure carries the provider and the "
                    + "consumer");
            if (codecUnit == null || app == null) {
                return;
            }
            ClassId partId = new ClassId("src/m/codec.deal", "Part");
            ClassFactoryId codecEntry = factoryEntryOf(codecUnit, partId);
            check(codecEntry != null,
                "the transitive provider registers its construction entry");
            if (codecEntry == null) {
                return;
            }
            int codecConstructions = 0;
            for (SemanticOp op : app.ops()) {
                if (op.kind() == SemanticOpKind.CLASS_NEW
                        && op.payload()
                            instanceof KindPayload.ClassNewPayload payload
                        && payload.classId().equals(partId)
                        && codecEntry.equals(payload.classFactoryRef())) {
                    codecConstructions++;
                    checkEq(codecUnit.classLayouts().get(partId), payload.layout(),
                        "the transitive literal carries the codec provider's "
                            + "layout");
                }
            }
            checkEq(1, codecConstructions,
                "the consumer's nested literal references the transitive "
                    + "provider's entry");
            List<RuntimeImportDependency> partEdges = new ArrayList<>();
            List<RuntimeImportDependency> appPartEdges = new ArrayList<>();
            for (RuntimeImportDependency edge
                    : project.orchestrator().runtimeDependencies()) {
                if (!edge.semanticResourceIdentity()
                        .lexicalDeclarationIdentity().declaredName()
                        .equals("Part")) {
                    continue;
                }
                partEdges.add(edge);
                // Every Part edge names the codec declaration the field
                // annotation resolved — never the same-named sibling
                // import of the same canonical module identity.
                check(edge.toSemanticModuleIdentity()
                        .canonicalResolvedSourceUri().contains("/m/codec.deal"),
                    "every Part runtime edge names the codec provider: "
                        + edge.toSemanticModuleIdentity()
                            .canonicalResolvedSourceUri());
                if (edge.fromSemanticModuleIdentity()
                        .canonicalResolvedSourceUri().contains("/src/app.deal")) {
                    appPartEdges.add(edge);
                }
            }
            checkEq(1, appPartEdges.size(),
                "the consumer records exactly one Part runtime edge: "
                    + appPartEdges);
            if (appPartEdges.size() == 1) {
                checkEq(RuntimeImportDependency.Reason.RUNTIME_USE,
                    appPartEdges.get(0).reason(),
                    "the consumer's Part edge is the ordinary runtime use");
            }
            SemanticIrValidator.ComparisonFacts facts =
                comparisonFacts(project);
            assertProjectAdmission(lowered, facts,
                "the transitive provider project passes the project surfaces");
            SemanticRuntimeModel.ConsumerRun run =
                SemanticOracle.executeProjectInits(lowered, result.tables(),
                    result.registries(), null);
            check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                "the oracle executes the transitive provider project: "
                    + run.comparisonReport());
        } finally {
            deleteRecursively(project.root());
        }
    }

    private static void testSameIdentityFieldOperationResolution() throws Exception {
        System.out.println("-- same-named sibling field operations: the exact "
            + "declaring provider owns every field read/write/delete, independent "
            + "of the import order --");
        for (boolean reversed : new boolean[] { false, true }) {
            String label = reversed ? "reversed imports" : "ir imported first";
            RealProject project = compileProject(reversed
                    ? FIELD_SIBLING_APP_REVERSED_SOURCE
                    : FIELD_SIBLING_APP_SOURCE,
                Map.of("src/m/ir.deal", FIELD_SIBLING_IR_SOURCE,
                    "src/m/codec.deal", FIELD_SIBLING_CODEC_SOURCE,
                    "src/other.deal", FIELD_SIBLING_OTHER_SOURCE),
                Map.of());
            try {
                SemanticLowerer.ProjectLoweringResult result = lower(project, invocation());
                check(!result.hasErrors() && result.project() != null,
                    "the " + label + " same-named field project lowers through "
                        + "the one project entry: " + result.diagnostics());
                if (result.project() == null) {
                    continue;
                }
                ExecutableLoweredProject lowered = result.project();
                ModuleId ir = new ModuleId("m.ir");
                ModuleId codec = new ModuleId("m.codec");
                ModuleId other = new ModuleId("other");
                LoweredModuleUnit irUnit = lowered.modules().get(ir);
                LoweredModuleUnit codecUnit = lowered.modules().get(codec);
                LoweredModuleUnit otherUnit = lowered.modules().get(other);
                LoweredModuleUnit app = lowered.modules().get(APP);
                check(irUnit != null && codecUnit != null && otherUnit != null
                        && app != null,
                    "the " + label + " closure carries both siblings, the "
                        + "same-named declarations and the consumer");
                if (irUnit == null || codecUnit == null || otherUnit == null
                        || app == null) {
                    continue;
                }
                ClassId irId = new ClassId("src/m/ir.deal", "Ir");
                ClassId codecId = new ClassId("src/m/codec.deal", "Ir");
                ClassId pointId = new ClassId("src/other.deal", "Point");
                ClassId otherPointId = pointId;
                ClassId localPointId = new ClassId("src/app.deal", "Point");
                ClassFactoryId irEntry = factoryEntryOf(irUnit, irId);
                ClassFactoryId codecEntry = factoryEntryOf(codecUnit, codecId);
                ClassFactoryId otherEntry = factoryEntryOf(otherUnit, pointId);
                check(irEntry != null && codecEntry != null && !irEntry.equals(codecEntry)
                        && otherEntry != null,
                    "the " + label + " sibling and same-named declarations carry "
                        + "their own construction entries");
                if (irEntry == null || otherEntry == null) {
                    continue;
                }

                List<KindPayload.FieldReadPayload> irReads = new ArrayList<>();
                List<KindPayload.FieldReadPayload> pointReads = new ArrayList<>();
                List<KindPayload.FieldWritePayload> irWrites = new ArrayList<>();
                List<KindPayload.FieldWritePayload> pointWrites = new ArrayList<>();
                List<KindPayload.FieldDeletePayload> irDeletes = new ArrayList<>();
                for (SemanticOp op : app.ops()) {
                    switch (op.payload()) {
                        case KindPayload.FieldReadPayload read -> {
                            if (read.classId().equals(irId)) {
                                irReads.add(read);
                            } else if (read.classId().equals(pointId)
                                    || read.classId().equals(localPointId)) {
                                pointReads.add(read);
                            }
                        }
                        case KindPayload.FieldWritePayload write -> {
                            if (write.classId().equals(irId)) {
                                irWrites.add(write);
                            } else if (write.classId().equals(pointId)
                                    || write.classId().equals(localPointId)) {
                                pointWrites.add(write);
                            }
                        }
                        case KindPayload.FieldDeletePayload delete -> {
                            if (delete.classId().equals(irId)) {
                                irDeletes.add(delete);
                            }
                        }
                        default -> {
                            // Not a field operation.
                        }
                    }
                }
                checkEq(1, irReads.size(), "the " + label + " ir read lowers one "
                    + "FIELD_READ");
                checkEq(1, irWrites.size(), "the " + label + " ir write lowers one "
                    + "FIELD_WRITE");
                checkEq(1, irDeletes.size(), "the " + label + " ir delete lowers one "
                    + "FIELD_DELETE");
                for (KindPayload.FieldReadPayload read : irReads) {
                    checkEq(null, read.classFactoryRef(),
                        "the " + label + " ir field read resolves the imported"
                            + " declaration by its exact identity (one"
                            + " declaration of the identity: no provider"
                            + " reference is needed)");
                }
                for (KindPayload.FieldWritePayload write : irWrites) {
                    checkEq(null, write.classFactoryRef(),
                        "the " + label + " ir field write resolves the imported"
                            + " declaration's exact identity, never the"
                            + " same-named sibling's");
                }
                for (KindPayload.FieldDeletePayload delete : irDeletes) {
                    checkEq(null, delete.classFactoryRef(),
                        "the " + label + " ir field delete resolves the"
                            + " imported declaration's exact identity");
                }
                checkEq(2, pointReads.size(), "the " + label + " same-named "
                    + "spelling lowers two FIELD_READs");
                long importedPointReads = pointReads.stream()
                    .filter(read -> read.classId().equals(otherPointId))
                    .count();
                long localPointReads = pointReads.stream()
                    .filter(read -> read.classId().equals(localPointId))
                    .count();
                checkEq(1L, importedPointReads, "the " + label + " imported "
                    + "read names the imported declaration's exact identity");
                checkEq(1L, localPointReads, "the " + label + " unit-local "
                    + "declaration's own read keeps the unit's layout");
                checkEq(1, pointWrites.size(), "the " + label + " imported "
                    + "same-named write lowers one FIELD_WRITE");
                for (KindPayload.FieldWritePayload write : pointWrites) {
                    checkEq(otherPointId, write.classId(),
                        "the " + label + " imported write names the imported"
                            + " declaration's exact identity, never the"
                            + " unit-local declaration's");
                    checkEq(null, write.classFactoryRef(),
                        "the " + label + " imported write needs no provider"
                            + " reference for a unique identity");
                }

                SemanticIrValidator.ComparisonFacts facts =
                    comparisonFacts(project);
                String text = assertProjectAdmission(lowered, facts,
                    "the " + label + " same-named field project passes the project surfaces");
                assertReassembly(text, SemanticIrDumper.dumpProject(lowered),
                    "the " + label + " same-named field project reassembles exactly");

                SemanticRuntimeModel.ConsumerRun run =
                    SemanticOracle.executeProjectInits(lowered, result.tables(),
                        result.registries(), null);
                check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                    "the " + label + " oracle completes the same-named field "
                        + "probe (the demanded 5 and 2, the deleted optional field): "
                        + run.comparisonReport());
            } finally {
                deleteRecursively(project.root());
            }
        }
    }

    /**
     * The exact-identity field operation needs no recorded declaration: the
     * class identity names the defining file, so removing the checker's
     * per-expression class declarations cannot make two declarations
     * collide. The operations keep their exact class ids and resolve the
     * declaring unit's layout, and the probe still executes.
     */
    private static void testExactIdentityFieldOperationNeedsNoDeclaration()
            throws Exception {
        System.out.println("-- a field operation resolves its exact class "
            + "identity without a recorded receiver declaration --");
        RealProject project = compileProject(FIELD_SIBLING_APP_SOURCE,
            Map.of("src/m/ir.deal", FIELD_SIBLING_IR_SOURCE,
                "src/m/codec.deal", FIELD_SIBLING_CODEC_SOURCE,
                "src/other.deal", FIELD_SIBLING_OTHER_SOURCE),
            Map.of());
        try {
            // The same real project with the checker's exact receiver
            // declarations removed: the field operations keep their exact
            // class identities. (The literals' own recorded construction
            // contexts stay: this control isolates the field-operation
            // path.)
            List<CheckedModuleInput> modules = new ArrayList<>();
            for (CheckedModuleInput module : project.checkedProject().modules()) {
                CheckResult checks = module.checks();
                Map<ExpressionNode, deal.checker.Symbol.ClassSymbol> literalFacts =
                    new LinkedHashMap<>();
                for (Map.Entry<ExpressionNode, deal.checker.Symbol.ClassSymbol> entry
                        : checks.exactClassDeclarations().entrySet()) {
                    if (entry.getKey() instanceof ObjectLiteralExpr) {
                        literalFacts.put(entry.getKey(), entry.getValue());
                    }
                }
                CheckResult stripped = new CheckResult(checks.typeMap(),
                    checks.symbolTable(), checks.scopeMap(), checks.diagnostics(),
                    checks.classScopes(), checks.constructedClassDecls(),
                    literalFacts);
                modules.add(new CheckedModuleInput(module.moduleId(), module.sourceId(),
                    module.sourcePath(), module.ast(), stripped, module.imports(),
                    module.exports(), module.kind(), module.classLiteralProviders()));
            }
            CompilerInvocation invocation = invocation();
            CheckedProjectInput stripped = new CheckedProjectInput(invocation,
                project.checkedProject().entryModule(), modules,
                invocation.releaseStateHash());
            SemanticLowerer.ProjectLoweringResult result =
                SemanticLowerer.lowerProject(invocation, stripped, project.index(),
                    project.manifests(), project.surface(),
                    project.declarationIdentities(), BuiltinErrorDeclaration.synthesized(
                        modules.get(0).ast().span()),
                    Set.of());
            check(!result.hasErrors() && result.project() != null,
                "the exact-identity field operation needs no recorded receiver "
                    + "declaration: " + result.diagnostics());
            if (result.project() != null) {
                LoweredModuleUnit app = result.project().modules().get(APP);
                ClassId irId = new ClassId("src/m/ir.deal", "Ir");
                ClassId localPointId = new ClassId("src/app.deal", "Point");
                ClassId otherPointId = new ClassId("src/other.deal", "Point");
                int irOps = 0;
                int pointOps = 0;
                if (app != null) {
                    for (SemanticOp op : app.ops()) {
                        ClassId opClass = switch (op.payload()) {
                            case KindPayload.FieldReadPayload read -> read.classId();
                            case KindPayload.FieldWritePayload write -> write.classId();
                            case KindPayload.FieldDeletePayload delete ->
                                delete.classId();
                            default -> null;
                        };
                        if (irId.equals(opClass)) {
                            irOps++;
                        } else if (localPointId.equals(opClass)
                                || otherPointId.equals(opClass)) {
                            pointOps++;
                        }
                    }
                }
                checkEq(3, irOps, "the ir receiver's read, write and delete keep "
                    + "the exact declaring identity");
                checkEq(3, pointOps, "the two same-named Point declarations keep "
                    + "their own exact identities on every field operation");
                SemanticRuntimeModel.ConsumerRun run =
                    SemanticOracle.executeProjectInits(result.project(),
                        result.tables(), result.registries(), null);
                check(run.terminal()
                        instanceof SemanticRuntimeModel.Terminal.Success,
                    "the oracle completes the exact-identity field probe: "
                        + run.comparisonReport());
            }
        } finally {
            deleteRecursively(project.root());
        }
    }

    /**
     * The same-named sibling JSON nested link resolves by exact identity:
     * the plan-bearing declaration of the consumer's field type is the
     * declaring file's own class identity, so the generated plan key names
     * exactly one declaration in both import orders and the round trip
     * executes its layout (no identity collision exists to fail closed).
     */
    private static void testJsonNestedLinkResolvesByExactIdentity() throws Exception {
        System.out.println("-- the same-named sibling JSON nested link resolves "
            + "the exact declaring identity in both import orders --");
        for (boolean reversed : new boolean[] { false, true }) {
            String label = reversed ? "reversed imports" : "ir imported first";
            RealProject project = compileProject(reversed
                    ? JSON_SIBLING_APP_REVERSED_SOURCE
                    : JSON_SIBLING_APP_SOURCE,
                Map.of("src/m/ir.deal", JSON_SIBLING_IR_SOURCE,
                    "src/m/codec.deal", JSON_SIBLING_CODEC_SOURCE), Map.of());
            try {
                SemanticLowerer.ProjectLoweringResult result =
                    lower(project, invocation());
                check(!result.hasErrors() && result.project() != null,
                    "the " + label + " JSON nested link project lowers through "
                        + "the one project entry: " + result.diagnostics());
                if (result.project() == null) {
                    continue;
                }
                ClassId irId = new ClassId("src/m/ir.deal", "Ir");
                ClassId codecId = new ClassId("src/m/codec.deal", "Ir");
                check(!irId.equals(codecId),
                    "the " + label + " same-named declarations own distinct"
                        + " exact identities");
                SemanticRuntimeModel.ConsumerRun run =
                    SemanticOracle.executeProjectInits(result.project(),
                        result.tables(), result.registries(), null);
                check(run.terminal()
                        instanceof SemanticRuntimeModel.Terminal.Success,
                    "the " + label + " oracle completes the JSON round trip"
                        + " through the declaring identity: "
                        + run.comparisonReport());
            } finally {
                deleteRecursively(project.root());
            }
        }
    }

    /**
     * The single-plan same-named JSON link (review cycle 2, finding 2):
     * a same-named declaration without a generated plan registers none,
     * so the nested link resolves the one plan-bearing declaration exactly
     * and the project compiles and runs the round trip.
     */
    private static void testSinglePlanJsonNestedLinkResolves() throws Exception {
        System.out.println("-- the single-plan same-named JSON nested link "
            + "resolves: an identity with one plan-bearing declaration keeps "
            + "its exact nested link --");
        RealProject project = compileProject(SINGLE_PLAN_APP_SOURCE,
            Map.of("src/other.deal", SINGLE_PLAN_OTHER_SOURCE), Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(project, invocation());
            check(!result.hasErrors() && result.project() != null,
                "the single-plan project lowers through the one project entry: "
                    + result.diagnostics());
            if (result.project() == null) {
                return;
            }
            ExecutableLoweredProject lowered = result.project();
            SemanticIrValidator.ComparisonFacts facts =
                comparisonFacts(project);
            assertProjectAdmission(lowered, facts,
                "the single-plan project passes the project surfaces");
            SemanticRuntimeModel.ConsumerRun run =
                SemanticOracle.executeProjectInits(lowered, result.tables(),
                    result.registries(), null);
            check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                "the oracle completes the single-plan JSON round trip (the one "
                    + "plan-bearing declaration's own plan): "
                    + run.comparisonReport());
        } finally {
            deleteRecursively(project.root());
        }
    }

    /**
     * The plan-imported/non-plan-local same-named JSON link (review
     * cycle 4, finding 1): the imported sibling carries the only
     * plan-bearing declaration, so the generated plan table carries the
     * imported declaration's layout (the unique plan-bearing declaration
     * wins over the emission order's local sibling) and the nested link
     * resolves it exactly — the construction, the nested link and the
     * oracle walk agree with no wrong-sibling plan. Both the exported and
     * the non-exported local sibling shapes admit.
     */
    private static void testPlanImportedBesideNonPlanLocal() throws Exception {
        System.out.println("-- the plan-imported/non-plan-local same-named JSON "
            + "link resolves the imported plan-bearing declaration and "
            + "executes --");
        for (boolean exported : new boolean[] { true, false }) {
            String label = exported ? "local exported" : "local non-exported";
            RealProject project = compileProject(
                exported ? PLAN_IMPORTED_APP_SOURCE
                    : PLAN_IMPORTED_APP_NONEXPORTED_SOURCE,
                Map.of("src/other.deal", PLAN_IMPORTED_OTHER_SOURCE,
                    "src/codec.deal", PLAN_IMPORTED_CODEC_SOURCE), Map.of());
            try {
                SemanticLowerer.ProjectLoweringResult result =
                    lower(project, invocation());
                check(!result.hasErrors() && result.project() != null,
                    "the " + label + " plan-imported project lowers through the "
                        + "one project entry: " + result.diagnostics());
                if (result.project() == null) {
                    return;
                }
                ExecutableLoweredProject lowered = result.project();
                LoweredModuleUnit appUnit = lowered.modules().get(APP);
                ClassId point = new ClassId("src/app.deal", "Point");
                check(appUnit != null && appUnit.classLayouts().containsKey(point),
                    "the consumer unit declares the same-named local sibling "
                        + "(" + label + ")");
                if (appUnit != null) {
                    checkEq("nick",
                        appUnit.classLayouts().get(point).fields().get(0).name(),
                        "the consumer unit's own layout of the identity stays the "
                            + "local declaration's (" + label + ")");
                    KindPayload.ClassNewPayload literal = null;
                    for (SemanticOp op : appUnit.ops()) {
                        if (op.kind() == SemanticOpKind.CLASS_NEW
                                && op.payload()
                                    instanceof KindPayload.ClassNewPayload payload
                                && payload.classId()
                                    .equals(new ClassId("src/other.deal", "Point"))) {
                            literal = payload;
                        }
                    }
                    check(literal != null, "the Wrap.p default lowers one "
                        + "CLASS_NEW of the identity (" + label + ")");
                    if (literal != null) {
                        checkEq(ConstructionProvider.SHARED_FACTORY, literal.constructionProvider(),
                            "the default literal constructs the imported "
                                + "plan-bearing declaration (" + label + ")");
                        checkEq("a", literal.layout().fields().get(0).name(),
                            "the construction carries the imported plan-bearing "
                                + "declaration's layout (" + label + ")");
                    }
                }
                SemanticIrValidator.ComparisonFacts facts =
                    comparisonFacts(project);
                assertProjectAdmission(lowered, facts,
                    "the " + label + " plan-imported project passes the project surfaces");
                SemanticRuntimeModel.ConsumerRun run =
                    SemanticOracle.executeProjectInits(lowered, result.tables(),
                        result.registries(), null);
                check(run.terminal()
                        instanceof SemanticRuntimeModel.Terminal.Success,
                    "the oracle executes the nested JSON round trip through the "
                        + "imported plan-bearing declaration (" + label + "): "
                        + run.comparisonReport());
            } finally {
                deleteRecursively(project.root());
            }
        }
    }

    /**
     * The annotated-local + imported-callee-argument shape (review cycle 2,
     * finding 1): the checker's recorded construction declaration is the
     * consumer's own {@code Ir} even though the literal is an argument of
     * the imported {@code ir.id}, so the literal keeps the same-module
     * resource and per-construction default (5), independent of the import
     * order.
     */
    private static void testLocalDeclarationOwnsLiteralBesideImportedSiblings()
            throws Exception {
        System.out.println("-- the annotated-local construction shape: the "
            + "checker's recorded construction declaration owns the "
            + "literal's plan and per-construction default beside imported "
            + "same-named siblings --");
        for (boolean reversed : new boolean[] { false, true }) {
            String label = reversed ? "reversed imports" : "ir imported first";
            RealProject project = compileProject("src/m/app.deal",
                reversed ? LOCAL_ATTRIBUTION_APP_REVERSED_SOURCE
                    : LOCAL_ATTRIBUTION_APP_SOURCE,
                Map.of("src/m/ir.deal", LOCAL_ATTRIBUTION_IR_SOURCE,
                    "src/m/codec.deal", LOCAL_ATTRIBUTION_CODEC_SOURCE),
                Map.of());
            try {
                // The checker laid both literals out by the consumer's own
                // Ir (the annotation is the construction context), so the
                // recorder records no imported provider alias for them.
                for (CheckedModuleInput module : project.checkedProject().modules()) {
                    if (module.moduleId().path().equals("m.app")) {
                        check(module.classLiteralProviders().isEmpty(),
                            "the locally constructed literals record no imported "
                                + "provider alias (" + label + "): "
                                + module.classLiteralProviders());
                    }
                }
                SemanticLowerer.ProjectLoweringResult result =
                    lower(project, invocation());
                check(!result.hasErrors() && result.project() != null,
                    "the annotated-local project lowers through the one project "
                        + "entry (" + label + "): " + result.diagnostics());
                if (result.project() == null) {
                    return;
                }
                ExecutableLoweredProject lowered = result.project();
                ModuleId appModule = new ModuleId("m.app");
                LoweredModuleUnit app = lowered.modules().get(appModule);
                check(app != null, "the consumer unit is lowered (" + label + ")");
                if (app == null) {
                    return;
                }
                ClassId localIr = new ClassId("src/m/app.deal", "Ir");
                int constructions = 0;
                for (SemanticOp op : app.ops()) {
                    if (op.kind() != SemanticOpKind.CLASS_NEW
                            || !(op.payload()
                                instanceof KindPayload.ClassNewPayload payload)
                            || !payload.classId().equals(localIr)) {
                        continue;
                    }
                    constructions++;
                    checkEq(ConstructionProvider.LOCAL, payload.constructionProvider(),
                        "the local Ir construction carries the unit's own defaults ("
                            + label + ")");
                    checkEq(null, payload.classFactoryRef(),
                        "the local Ir construction references no imported provider "
                            + "(" + label + ")");
                    checkEq(app.classLayouts().get(localIr), payload.layout(),
                        "the local Ir construction carries the unit's own layout ("
                            + label + ")");
                }
                checkEq(2, constructions,
                    "both recorded literals construct the local Ir (the variable "
                        + "initializer and the field default) (" + label + ")");
                SemanticIrValidator.ComparisonFacts facts =
                    comparisonFacts(project);
                String text = assertProjectAdmission(lowered, facts,
                    "the annotated-local project passes the project surfaces (" + label + ")");
                assertReassembly(text, SemanticIrDumper.dumpProject(lowered),
                    "the annotated-local project reassembles exactly (" + label + ")");
                SemanticRuntimeModel.ConsumerRun run =
                    SemanticOracle.executeProjectInits(lowered, result.tables(),
                        result.registries(), null);
                check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                    "the oracle completes the probe with the local default 5, never "
                        + "the sibling's 7 or 93 (" + label + "): "
                        + run.comparisonReport());
            } finally {
                deleteRecursively(project.root());
            }
        }
    }

    /**
     * The annotated-import + imported-callee-argument shape: the
     * checker's recorded construction context is the annotation's
     * imported declaration, so the literal's resources and plan are its
     * own and the callee's parameter provider never captures the
     * construction.
     */
    private static void testImportedContextBesideCalleeArgumentProvider()
            throws Exception {
        System.out.println("-- the annotated-import construction shape: the "
            + "checker's recorded construction context owns the "
            + "literal's provider and per-construction default --");
        for (boolean reversed : new boolean[] { false, true }) {
            String label = reversed ? "reversed imports" : "ir imported first";
            RealProject project = compileProject("src/m/probe.deal",
                IMPORTED_CONTEXT_PROBE_SOURCE,
                Map.of("src/m/ir.deal", LOCAL_ATTRIBUTION_IR_SOURCE,
                    "src/m/codec.deal", LOCAL_ATTRIBUTION_CODEC_SOURCE),
                Map.of());
            try {
                // The literal is an argument of codec.id and the
                // annotation names codec.Ir: the recorder records codec
                // as the construction provider, never the sibling ir.
                for (CheckedModuleInput module : project.checkedProject().modules()) {
                    if (module.moduleId().path().equals("m.probe")) {
                        checkEq(1, module.classLiteralProviders().size(),
                            "the contextually declared literal records exactly one "
                                + "provider alias (" + label + ")");
                        checkEq("codec", module.classLiteralProviders().values()
                                .iterator().next(),
                            "the recorded provider alias is the annotation's own "
                                + "declaration, never the callee's parameter provider ("
                                + label + ")");
                    }
                }
                SemanticLowerer.ProjectLoweringResult result =
                    lower(project, invocation());
                check(!result.hasErrors() && result.project() != null,
                    "the annotated-import project lowers through the one project "
                        + "entry (" + label + "): " + result.diagnostics());
                if (result.project() == null) {
                    return;
                }
                ExecutableLoweredProject lowered = result.project();
                ModuleId probe = new ModuleId("m.probe");
                ModuleId codec = new ModuleId("m.codec");
                LoweredModuleUnit probeUnit = lowered.modules().get(probe);
                LoweredModuleUnit codecUnit = lowered.modules().get(codec);
                check(probeUnit != null && codecUnit != null,
                    "the closure carries the probe and its providers (" + label + ")");
                if (probeUnit == null || codecUnit == null) {
                    return;
                }
                ClassId irId = new ClassId("src/m/codec.deal", "Ir");
                ClassFactoryId codecEntry = factoryEntryOf(codecUnit, irId);
                boolean constructionSeen = false;
                for (SemanticOp op : probeUnit.ops()) {
                    if (op.kind() != SemanticOpKind.CLASS_NEW
                            || !(op.payload()
                                instanceof KindPayload.ClassNewPayload payload)
                            || !payload.classId().equals(irId)) {
                        continue;
                    }
                    constructionSeen = true;
                    checkEq(ConstructionProvider.SHARED_FACTORY, payload.constructionProvider(),
                        "the contextually declared construction transfers to the "
                            + "annotation provider's factory (" + label + ")");
                    checkEq(codecEntry, payload.classFactoryRef(),
                        "the construction references codec's construction entry, "
                            + "never the sibling ir entry (" + label + ")");
                }
                check(constructionSeen,
                    "the probe carries the contextually declared construction ("
                        + label + ")");
                SemanticRuntimeModel.ConsumerRun run =
                    SemanticOracle.executeProjectInits(lowered, result.tables(),
                        result.registries(), null);
                check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                    "the oracle completes the probe with the annotation's 93, never "
                        + "the sibling's 7 (" + label + "): "
                        + run.comparisonReport());
            } finally {
                deleteRecursively(project.root());
            }
        }
    }

    /**
     * The standalone callee-parameter and declared-return construction
     * contexts (review cycle 5 finding 1): a literal in an imported
     * function's parameter or declared-return position is attributed to
     * that annotation's own declared provider — never the consumer's
     * same-named local declaration — in either import order, for both
     * a differing field set and a differing per-construction default.
     */
    private static void testCalleeParameterAndReturnProviderBesideSameIdentityLocal()
            throws Exception {
        System.out.println("-- the imported callee-parameter and declared-return "
            + "contexts own their literals' construction beside a same-named "
            + "local declaration --");
        for (boolean reversed : new boolean[] { false, true }) {
            for (boolean localFieldsOnly : new boolean[] { false, true }) {
                String label = (reversed ? "reversed imports" : "ir imported first")
                    + (localFieldsOnly ? ", differing fields" : ", differing defaults");
                RealProject project = compileProject("src/m/app.deal",
                    paramReturnProviderAppSource(reversed, localFieldsOnly),
                    Map.of("src/m/ir.deal", PARAM_RETURN_PROVIDER_IR_SOURCE,
                        "src/m/codec.deal", PARAM_RETURN_PROVIDER_CODEC_SOURCE),
                    Map.of());
                try {
                    // The checker's recorded construction contexts name the
                    // imported provider for exactly the three imported
                    // literals (never the local declaration).
                    for (CheckedModuleInput module : project.checkedProject().modules()) {
                        if (!module.moduleId().path().equals("m.app")) {
                            continue;
                        }
                        checkEq(3,
                            module.classLiteralProviders().size(),
                            "the three imported construction literals record their "
                                + "context provider (the local literal records none) ("
                                + label + ")");
                        int irAliases = 0;
                        for (String alias : module.classLiteralProviders().values()) {
                            if (alias.equals("ir")) {
                                irAliases++;
                            }
                        }
                        checkEq(3, irAliases,
                            "the parameter and return literals record the ir "
                                + "provider, never the local declaration or codec ("
                                + label + "): " + module.classLiteralProviders());
                    }
                    SemanticLowerer.ProjectLoweringResult result =
                        lower(project, invocation());
                    check(!result.hasErrors() && result.project() != null,
                        "the parameter/return provider project lowers (" + label
                            + "): " + result.diagnostics());
                    if (result.project() == null) {
                        return;
                    }
                    ExecutableLoweredProject lowered = result.project();
                    ModuleId irModule = new ModuleId("m.ir");
                    ModuleId appModule = new ModuleId("m.app");
                    LoweredModuleUnit irUnit = lowered.modules().get(irModule);
                    LoweredModuleUnit app = lowered.modules().get(appModule);
                    check(irUnit != null && app != null,
                        "the closure carries the ir provider and the consumer ("
                            + label + ")");
                    if (irUnit == null || app == null) {
                        return;
                    }
                    ClassId irId = new ClassId("src/m/ir.deal", "Ir");
                    ClassFactoryId irEntry = factoryEntryOf(irUnit, irId);
                    check(irEntry != null,
                        "the ir provider registers its construction entry (" + label + ")");
                    if (irEntry == null) {
                        return;
                    }
                    ClassId localIr = new ClassId("src/m/app.deal", "Ir");
                    int shared = 0;
                    int local = 0;
                    for (SemanticOp op : app.ops()) {
                        if (op.kind() != SemanticOpKind.CLASS_NEW
                                || !(op.payload()
                                    instanceof KindPayload.ClassNewPayload payload)) {
                            continue;
                        }
                        if (payload.classId().equals(irId)) {
                            shared++;
                            checkEq(ConstructionProvider.SHARED_FACTORY,
                                payload.constructionProvider(),
                                "the imported construction transfers to the ir"
                                    + " provider (" + label + ")");
                            checkEq(irEntry, payload.classFactoryRef(),
                                "the imported construction references the ir "
                                    + "provider's entry (" + label + ")");
                            checkEq(irUnit.classLayouts().get(irId), payload.layout(),
                                "the imported construction carries the ir "
                                    + "provider's layout (" + label + ")");
                        } else if (payload.classId().equals(localIr)) {
                            local++;
                            checkEq(ConstructionProvider.LOCAL, payload.constructionProvider(),
                                "the local construction stays LOCAL (" + label + ")");
                            checkEq(null, payload.classFactoryRef(),
                                "the local construction references no provider ("
                                    + label + ")");
                            checkEq(app.classLayouts().get(localIr), payload.layout(),
                                "the local construction carries the unit's own "
                                    + "layout (" + label + ")");
                        }
                    }
                    checkEq(3, shared,
                        "the parameter, g-parameter and return literals construct "
                            + "the imported provider (" + label + ")");
                    checkEq(localFieldsOnly ? 0 : 1, local,
                        "the explicitly annotated local literal constructs the unit's "
                            + "own declaration (" + label + ")");
                    SemanticIrValidator.ComparisonFacts facts =
                        comparisonFacts(project);
                    assertProjectAdmission(lowered, facts,
                        "the project passes the project surfaces (" + label + ")");
                    SemanticRuntimeModel.ConsumerRun run =
                        SemanticOracle.executeProjectInits(lowered, result.tables(),
                            result.registries(), null);
                    check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                        "the oracle executes the imported provider's constructions "
                            + "(" + label + "): " + run.comparisonReport());
                } finally {
                    deleteRecursively(project.root());
                }
            }
        }
    }

    /**
     * The function-typed callee's parameter provider (review cycle 1
     * finding 2): a function-typed value carries no declared parameter
     * annotation, but its bound parameter carrier names the defining
     * provider, so an argument literal is laid out by that declaration —
     * never the consumer's same-named local declaration, whose
     * per-construction default would otherwise capture it. The same shape
     * without the collision keeps the identity-keyed provider route.
     */
    private static void testFunctionTypedCalleeParameterProviderBesideSameIdentityLocal()
            throws Exception {
        System.out.println("-- the function-typed callee parameter carrier owns "
            + "its argument literal's construction beside a same-named "
            + "local declaration --");
        String app = "import * as ir from \"./ir\";\n\n"
            + "class Ir { a: number; }\n\n"
            + "export function main(): null {\n"
            + "  let f: (x: ir.Ir) => number = ir.useIr;\n"
            + "  let n: number = f({a: 7});\n"
            + "  if (n !== 7) { throw { code: \"WRONG_N\", message: \"n\" }; }\n"
            + "  return null;\n"
            + "}\n";
        RealProject project = compileProject(app,
            Map.of("src/ir.deal", PARAM_RETURN_PROVIDER_IR_SOURCE), Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result =
                lower(project, invocation());
            check(!result.hasErrors() && result.project() != null,
                "the function-typed callee project lowers: "
                    + result.diagnostics());
            if (result.project() == null) {
                return;
            }
            LoweredModuleUnit appUnit = result.project().modules().get(APP);
            LoweredModuleUnit irUnit = result.project().modules()
                .get(new ModuleId("ir"));
            check(appUnit != null && irUnit != null,
                "the closure carries the consumer and the parameter provider");
            if (appUnit == null || irUnit == null) {
                return;
            }
            ClassId irId = new ClassId("src/ir.deal", "Ir");
            ClassFactoryId irEntry = factoryEntryOf(irUnit, irId);
            int shared = 0;
            int local = 0;
            for (SemanticOp op : appUnit.ops()) {
                if (op.kind() != SemanticOpKind.CLASS_NEW
                        || !(op.payload()
                            instanceof KindPayload.ClassNewPayload payload)
                        || !payload.classId().equals(irId)) {
                    continue;
                }
                if (payload.constructionProvider() == ConstructionProvider.SHARED_FACTORY) {
                    shared++;
                    checkEq(irEntry, payload.classFactoryRef(),
                        "the function-typed callee's argument literal references "
                            + "the parameter provider's entry");
                    checkEq(irUnit.classLayouts().get(irId), payload.layout(),
                        "the function-typed callee's argument literal carries "
                            + "the parameter provider's layout");
                } else {
                    local++;
                }
            }
            checkEq(1, shared,
                "the argument literal constructs the parameter carrier's "
                    + "declaration");
            checkEq(0, local,
                "the unit-local same-named declaration never constructs the "
                    + "argument literal");
            SemanticRuntimeModel.ConsumerRun run =
                SemanticOracle.executeProjectInits(result.project(),
                    result.tables(), result.registries(), null);
            check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                "the oracle executes the parameter provider's 7, never the "
                    + "local declaration's 5: " + run.comparisonReport());
        } finally {
            deleteRecursively(project.root());
        }
        // The control: the same shape without the same-named local
        // declaration resolves the sole declaration (the parameter
        // carrier's provider) and executes its default.
        RealProject control = compileProject(
            app.replace("class Ir { a: number = 5; }\n\n", ""),
            Map.of("src/ir.deal", PARAM_RETURN_PROVIDER_IR_SOURCE), Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result =
                lower(control, invocation());
            check(!result.hasErrors() && result.project() != null,
                "the single-declaration control lowers: " + result.diagnostics());
            if (result.project() == null) {
                return;
            }
            LoweredModuleUnit appUnit = result.project().modules().get(APP);
            LoweredModuleUnit irUnit = result.project().modules()
                .get(new ModuleId("ir"));
            check(appUnit != null && irUnit != null,
                "the control carries the consumer and the single provider");
            if (appUnit == null || irUnit == null) {
                return;
            }
            ClassId irId = new ClassId("src/ir.deal", "Ir");
            ClassFactoryId irEntry = factoryEntryOf(irUnit, irId);
            int shared = 0;
            for (SemanticOp op : appUnit.ops()) {
                if (op.kind() == SemanticOpKind.CLASS_NEW
                        && op.payload()
                            instanceof KindPayload.ClassNewPayload payload
                        && payload.classId().equals(irId)
                        && payload.constructionProvider() == ConstructionProvider.SHARED_FACTORY) {
                    shared++;
                    checkEq(irEntry, payload.classFactoryRef(),
                        "the control's construction references the sole "
                            + "provider's entry");
                }
            }
            checkEq(1, shared,
                "the control's argument literal resolves the sole declaration");
            SemanticRuntimeModel.ConsumerRun run =
                SemanticOracle.executeProjectInits(result.project(),
                    result.tables(), result.registries(), null);
            check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                "the control's oracle executes with the provider's 7: "
                    + run.comparisonReport());
        } finally {
            deleteRecursively(control.root());
        }
    }

    /**
     * The call-result receiver (review cycle 1 findings 1 and 2): a
     * receiver whose type is the callee's declared return carries the
     * declaring provider, so the field read/write/delete and the has
     * check resolve that declaration's layout — never the same-named
     * sibling's — and the identity-keyed lowering fail-close is not
     * reached for a legal program.
     */
    private static void testCallReceiverProviderBesideSameIdentityLocal()
            throws Exception {
        System.out.println("-- the call-result receiver's declared return "
            + "provider owns its field read/write/delete and has check beside a "
            + "same-named sibling, independent of the import order --");
        for (boolean reversed : new boolean[] { false, true }) {
            String label = reversed ? "reversed imports" : "ir imported first";
            RealProject project = compileProject("src/app.deal",
                reversed ? CALL_RECEIVER_APP_REVERSED_SOURCE
                    : CALL_RECEIVER_APP_SOURCE,
                Map.of("src/m/ir.deal", CALL_RECEIVER_IR_SOURCE,
                    "src/m/codec.deal", CALL_RECEIVER_CODEC_SOURCE),
                Map.of());
            try {
                SemanticLowerer.ProjectLoweringResult result =
                    lower(project, invocation());
                check(!result.hasErrors() && result.project() != null,
                    "the call-result receiver project lowers (" + label + "): "
                        + result.diagnostics());
                if (result.project() == null) {
                    continue;
                }
                ExecutableLoweredProject lowered = result.project();
                ModuleId ir = new ModuleId("m.ir");
                LoweredModuleUnit irUnit = lowered.modules().get(ir);
                LoweredModuleUnit app = lowered.modules().get(APP);
                check(irUnit != null && app != null,
                    "the " + label + " closure carries the provider and the "
                        + "consumer");
                if (irUnit == null || app == null) {
                    continue;
                }
                ClassId irId = new ClassId("src/m/ir.deal", "Ir");
                ClassFactoryId irEntry = factoryEntryOf(irUnit, irId);
                check(irEntry != null,
                    "the " + label + " provider registers its construction "
                        + "entry");
                if (irEntry == null) {
                    continue;
                }
                int reads = 0;
                int writes = 0;
                int deletes = 0;
                int shared = 0;
                for (SemanticOp op : app.ops()) {
                    switch (op.payload()) {
                        case KindPayload.FieldReadPayload read -> {
                            if (read.classId().equals(irId)) {
                                reads++;
                                checkEq(null, read.classFactoryRef(),
                                    "the " + label + " call-result read names "
                                        + "the declared return declaration's"
                                        + " exact identity");
                            }
                        }
                        case KindPayload.FieldWritePayload write -> {
                            if (write.classId().equals(irId)) {
                                writes++;
                                checkEq(null, write.classFactoryRef(),
                                    "the " + label + " call-result write names "
                                        + "the declared return declaration's"
                                        + " exact identity");
                            }
                        }
                        case KindPayload.FieldDeletePayload delete -> {
                            if (delete.classId().equals(irId)) {
                                deletes++;
                                checkEq(null, delete.classFactoryRef(),
                                    "the " + label + " call-result delete names "
                                        + "the declared return declaration's"
                                        + " exact identity");
                            }
                        }
                        case KindPayload.ClassNewPayload ctor -> {
                            if (ctor.classId().equals(irId)
                                    && ctor.constructionProvider()
                                        == ConstructionProvider.SHARED_FACTORY) {
                                shared++;
                                checkEq(irEntry, ctor.classFactoryRef(),
                                    "the " + label + " argument literal names "
                                        + "the parameter provider's entry");
                            }
                        }
                        default -> {
                            // Not a class field operation.
                        }
                    }
                }
                checkEq(3, reads,
                    "the " + label + " call-result, chained and inferred-binding "
                        + "reads lower three FIELD_READs");
                checkEq(1, writes,
                    "the " + label + " call-result write lowers one FIELD_WRITE");
                checkEq(1, deletes,
                    "the " + label + " call-result delete lowers one FIELD_DELETE");
                checkEq(2, shared,
                    "the " + label + " function-typed argument literal and the "
                        + "declared-return literal both construct the provider's "
                        + "declaration");
                SemanticIrValidator.ComparisonFacts facts =
                    comparisonFacts(project);
                assertProjectAdmission(lowered, facts,
                    "the " + label + " call-result receiver project passes the project surfaces");
                SemanticRuntimeModel.ConsumerRun run =
                    SemanticOracle.executeProjectInits(lowered, result.tables(),
                        result.registries(), null);
                check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                    "the " + label + " oracle executes the provider's 7 and "
                        + "the written 11: " + run.comparisonReport());
            } finally {
                deleteRecursively(project.root());
            }
        }
    }

    private static void testImportedFieldWriteProviderDeferral() throws Exception {
        System.out.println("-- the malformed-provider negative: a field write "
            + "whose imported owner carries no facts fails closed with a named "
            + "E6005 and no partial project --");
        RealProject project = compileProject(IMPORTED_FIELD_WRITE_APP_SOURCE,
            Map.of("src/util.deal", IMPORTED_CLASS_UTIL_SOURCE), Map.of());
        try {
            // The same real project with the owner module removed from the
            // closure and the index: the checked type remains, but no
            // lowered provider layout covers the imported class.
            List<CheckedModuleInput> modules = new ArrayList<>();
            for (CheckedModuleInput module : project.checkedProject().modules()) {
                if (!module.moduleId().equals(UTIL)) {
                    modules.add(module);
                }
            }
            Map<ModuleId, ExternalModuleInterface> indexModules = new LinkedHashMap<>();
            for (Map.Entry<ModuleId, ExternalModuleInterface> entry
                    : project.index().modules().entrySet()) {
                if (!entry.getKey().equals(UTIL)) {
                    indexModules.put(entry.getKey(), entry.getValue());
                }
            }
            ProjectInterfaceIndex strippedIndex = new ProjectInterfaceIndex(
                ProjectInterfaceIndex.FORMAT_VERSION, indexModules);
            List<SemanticRequirementManifest> manifests = new ArrayList<>();
            for (SemanticRequirementManifest manifest : project.manifests()) {
                if (!manifest.moduleId().equals(UTIL)) {
                    manifests.add(manifest);
                }
            }
            CompilerInvocation invocation = invocation();
            CheckedProjectInput stripped = new CheckedProjectInput(invocation,
                project.checkedProject().entryModule(), modules,
                invocation.releaseStateHash());
            SemanticLowerer.ProjectLoweringResult result =
                SemanticLowerer.lowerProject(invocation, stripped, strippedIndex,
                    manifests, project.surface(), project.declarationIdentities(),
                    BuiltinErrorDeclaration.synthesized(
                        modules.get(0).ast().span()),
                    Set.of());
            check(result.hasErrors() && result.project() == null,
                "the stripped provider produces no project: "
                    + result.diagnostics());
            check(result.tables().isEmpty() && result.registries().isEmpty()
                    && result.seeds() == null && result.namespaces() == null,
                "the stripped provider produces no tables, no registries, no "
                    + "seeds, and no registrations");
            if (!result.diagnostics().isEmpty()) {
                CompilerDiagnostic diagnostic = result.diagnostics().get(0);
                check("E6005".equals(diagnostic.code())
                        && diagnostic.message().contains("CONSTRUCT_UNLOWERED"),
                    "the first E6005 is the named CONSTRUCT_UNLOWERED class-fact "
                        + "failure; got " + diagnostic.message());
                check(diagnostic.range() != null
                        && diagnostic.range().file() != null
                        && diagnostic.range().file().endsWith("app.deal")
                        && diagnostic.range().startLine() == 4,
                    "the failure keeps the field write's own source origin; got "
                        + diagnostic.range());
            }
        } finally {
            deleteRecursively(project.root());
        }
    }

    /**
     * The construction entry the unit's factory registry binds to the
     * declared class of one class id, or null.
     */
    private static ClassFactoryId factoryEntryOf(LoweredModuleUnit unit,
                                                 ClassId classId) {
        for (Map.Entry<ClassFactoryId, OpId> binding
                : unit.classFactories().factories().entrySet()) {
            SemanticOp factory = opOf(unit, binding.getValue());
            if (factory != null
                    && factory.payload() instanceof KindPayload.ClassFactoryPayload payload
                    && payload.classId().equals(classId)) {
                return binding.getKey();
            }
        }
        return null;
    }

    private static void testInconsistentFactSeed(LoweredProject imported)
            throws Exception {
        System.out.println("-- the inconsistent-fact negative: an owner outside the "
            + "closure and outside the declaration set still defers --");
        RealProject project = imported.project();
        {
            // The same real project with the owner module removed from the
            // closure and the index: the checker-resolved class identity
            // stays, but no lowered implementation module, no declaration
            // surface entry, and no registration seed covers it — the
            // inconsistent-fact case the deferral remains for.
            List<CheckedModuleInput> modules = new ArrayList<>();
            for (CheckedModuleInput module : project.checkedProject().modules()) {
                if (!module.moduleId().equals(UTIL)) {
                    modules.add(module);
                }
            }
            Map<ModuleId, ExternalModuleInterface> indexModules = new LinkedHashMap<>();
            for (Map.Entry<ModuleId, ExternalModuleInterface> entry
                    : project.index().modules().entrySet()) {
                if (!entry.getKey().equals(UTIL)) {
                    indexModules.put(entry.getKey(), entry.getValue());
                }
            }
            ProjectInterfaceIndex strippedIndex = new ProjectInterfaceIndex(
                ProjectInterfaceIndex.FORMAT_VERSION, indexModules);
            List<SemanticRequirementManifest> manifests = new ArrayList<>();
            for (SemanticRequirementManifest manifest : project.manifests()) {
                if (!manifest.moduleId().equals(UTIL)) {
                    manifests.add(manifest);
                }
            }
            CompilerInvocation invocation = invocation();
            CheckedProjectInput stripped = new CheckedProjectInput(invocation,
                project.checkedProject().entryModule(), modules,
                invocation.releaseStateHash());
            SemanticLowerer.ProjectLoweringResult result =
                SemanticLowerer.lowerProject(invocation, stripped, strippedIndex,
                    manifests, project.surface(), project.declarationIdentities(),
                    BuiltinErrorDeclaration.synthesized(
                        modules.get(0).ast().span()),
                    Set.of());
            check(result.hasErrors() && result.project() == null,
                "the inconsistent-fact seed produces no project: "
                    + result.diagnostics());
            check(result.tables().isEmpty() && result.registries().isEmpty()
                    && result.seeds() == null && result.namespaces() == null,
                "the inconsistent-fact seed produces no tables, no registries, no "
                    + "seeds, and no registrations");
            if (!result.diagnostics().isEmpty()) {
                CompilerDiagnostic diagnostic = result.diagnostics().get(0);
                check("E6005".equals(diagnostic.code())
                        && diagnostic.message().contains("RETAINED_ABI_DEFERRED"),
                    "the inconsistent-fact seed returns the first E6005 "
                        + "RETAINED_ABI_DEFERRED; got " + diagnostic.message());
            }
        }
    }

    // =========================================================================
    // 5. The declaration-class construction acceptance
    // =========================================================================

    private static void testDeclarationClassConstruction() throws Exception {
        System.out.println("-- a host declaration class literal resolves to the "
            + "seed and lowers CLASS_NEW(HOST_DECLARATION) through the one project "
            + "entry --");
        RealProject project = compileProject(HOST_CLASS_APP_SOURCE, Map.of(),
            Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(project, invocation());
            check(!result.hasErrors() && result.project() != null,
                "the host declaration class literal lowers through the one project "
                    + "entry with zero diagnostics: " + result.diagnostics());
            boolean deferred = false;
            for (CompilerDiagnostic diagnostic : result.diagnostics()) {
                if (diagnostic.message().contains("RETAINED_ABI_DEFERRED")) {
                    deferred = true;
                }
            }
            check(!deferred,
                "checker-valid host-class input reports zero RETAINED_ABI_DEFERRED");
            if (result.project() == null) {
                return;
            }
            check(result.seeds().registrationFor(ENDPOINT) != null
                    && result.seeds().registrationFor(ENDPOINT).owner()
                        == ConstructionProvider.HOST_DECLARATION,
                "the declared host class resolves in the seeds under HOST_DECLARATION");
            LoweredModuleUnit app = result.project().modules().get(APP);
            check(app != null, "the app unit is in the closure");
            if (app == null) {
                return;
            }
            SemanticOp classNew = null;
            for (SemanticOp op : app.ops()) {
                if (op.kind() == SemanticOpKind.CLASS_NEW) {
                    classNew = op;
                }
            }
            check(classNew != null, "the unit carries the host CLASS_NEW");
            if (classNew == null) {
                return;
            }
            KindPayload.ClassNewPayload payload =
                (KindPayload.ClassNewPayload) classNew.payload();
            checkEq(ENDPOINT, payload.classId(),
                "the CLASS_NEW classId is the declared host class identity");
            checkEq(ConstructionProvider.HOST_DECLARATION, payload.constructionProvider(),
                "the CLASS_NEW constructionProvider is HOST_DECLARATION");
            check(payload.classFactoryRef() == null,
                "the host construction carries the null factory ref");
            checkEq(result.seeds().registrationFor(ENDPOINT).layout(),
                payload.layout(),
                "the payload's layout is the registered declaration layout");
            checkEq(List.of("path"),
                payload.fieldBoundaries().stream()
                    .map(KindPayload.FieldBoundary::field).toList(),
                "the field boundaries are the provided fields in literal order");
            check(payload.fieldBoundaries().stream().allMatch(entry ->
                    entry.kind() == BoundaryKind.CLASS_LITERAL_FIELD),
                "every field boundary is a CLASS_LITERAL_FIELD (the loaded "
                    + "defaults are data, never in-project default expressions)");
            check(!payload.layout().fields().isEmpty()
                    && payload.layout().fields().stream().allMatch(field ->
                        field.constructionProvider() == ConstructionProvider.HOST_DECLARATION),
                "the registered declaration layout carries the HOST_DECLARATION "
                    + "field owner");

            // The composed per-unit chain re-run with the seeds: the unified
            // unit passes every validator (the class arm included).
            Optional<CompilerDiagnostic> failure =
                SemanticLowerer.validateProjectUnit(app, result.tableOf(APP),
                    comparisonFacts(project),
                    app.classBindingFacts(),
                    result.registryOf(APP),
                    project.index().modules().get(APP), Map.of(),
                    result.seeds().registrations());
            check(failure.isEmpty(),
                "the composed chain accepts the host construction unit: "
                    + failure.map(CompilerDiagnostic::message).orElse(""));

            // ISSUE-0821: the host declaration construction requires the
            // compilation's class registration seeds — the standalone
            // surfaces return E6005 at the referring CLASS_NEW instead of
            // inventing the provider context, and the complete project
            // supplies exactly that context.
            SemanticIrValidator.ComparisonFacts standaloneFacts =
                comparisonFacts(project);
            Optional<CompilerDiagnostic> standalone =
                SemanticIrValidator.validate(app, standaloneFacts);
            assertMutationRule("the standalone host construction", standalone,
                ClassConstructionValidator.CONSTRUCTION_COHERENCE,
                "no provider context", "constructionProvider HOST_DECLARATION");
            check(standalone.isPresent() && java.util.Objects.equals(
                    RawOrigin.of(classNew.origin()).diagnosticRange(),
                    standalone.get().range()),
                "the standalone rejection carries the referring CLASS_NEW's own "
                    + "origin");
            check(SemanticIrValidator.validate(result.project(), standaloneFacts)
                    .isEmpty(),
                "the complete project admits the host construction");

            // A supplied seed view that differs from the admitted registration
            // rejects through E6005 before any success (the class owner's
            // fail-closed seed fact), never by fabricating a registration.
            Optional<CompilerDiagnostic> seedView =
                SemanticLowerer.validateProjectUnit(app, app.bodyTable(),
                    standaloneFacts, app.classBindingFacts(), result.registryOf(APP),
                    project.index().modules().get(APP), Map.of(),
                    Map.of());
            check(seedView.isPresent()
                    && seedView.get().message().contains(
                        ClassConstructionValidator.CONSTRUCTION_COHERENCE),
                "a differing supplied seed view rejects through E6005: "
                    + seedView.map(CompilerDiagnostic::message).orElse("admitted"));
            testProjectSeedModuleJoinAndAdmission(new LoweredProject(project, result));
        } finally {
            deleteRecursively(project.root());
        }
    }

    private static void testBuiltinErrorConstruction() throws Exception {
        System.out.println("-- a builtin Error literal constructs through the one "
            + "project entry: CLASS_NEW(BUILTIN_ERROR) over the compiler-owned "
            + "layout --");
        RealProject project = compileProject(ERROR_APP_SOURCE, Map.of(), Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(project, invocation());
            check(!result.hasErrors() && result.project() != null,
                "the builtin Error literal lowers through the one project entry "
                    + "with zero diagnostics: " + result.diagnostics());
            boolean deferred = false;
            for (CompilerDiagnostic diagnostic : result.diagnostics()) {
                if (diagnostic.message().contains("RETAINED_ABI_DEFERRED")) {
                    deferred = true;
                }
            }
            check(!deferred,
                "checker-valid builtin Error input reports zero "
                    + "RETAINED_ABI_DEFERRED");
            if (result.project() == null) {
                return;
            }
            LoweredModuleUnit app = result.project().modules().get(APP);
            check(app != null, "the app unit is in the closure");
            if (app == null) {
                return;
            }
            SemanticOp classNew = null;
            for (SemanticOp op : app.ops()) {
                if (op.kind() == SemanticOpKind.CLASS_NEW) {
                    classNew = op;
                }
            }
            check(classNew != null, "the unit carries the Error CLASS_NEW");
            if (classNew == null) {
                return;
            }
            checkEq(ClassId.ERROR, ((KindPayload.ClassNewPayload) classNew.payload())
                    .classId(), "the CLASS_NEW classId is the builtin Error class");
            KindPayload.ClassNewPayload payload =
                (KindPayload.ClassNewPayload) classNew.payload();
            checkEq(ConstructionProvider.BUILTIN_ERROR, payload.constructionProvider(),
                "the CLASS_NEW constructionProvider is BUILTIN_ERROR");
            check(payload.classFactoryRef() == null,
                "the builtin Error construction carries the null factory ref");
            checkEq(ClassLayout.BUILTIN_ERROR, payload.layout(),
                "the payload's layout is the compiler-owned builtin Error layout");
            checkEq(List.of("code", "message"),
                payload.providedFields().stream()
                    .map(KindPayload.ProvidedField::name).toList(),
                "the provided fields keep literal order (code, message)");
            checkEq(List.of("code", "message"),
                payload.fieldBoundaries().stream()
                    .map(KindPayload.FieldBoundary::field).toList(),
                "the field boundaries are the declared order (code, message)");
            check(payload.fieldBoundaries().stream().allMatch(entry ->
                    entry.kind() == BoundaryKind.CLASS_LITERAL_FIELD),
                "every field boundary is a CLASS_LITERAL_FIELD (no "
                    + "CLASS_DEFAULT_FIELD: the builtin defaults are compiler "
                    + "constants)");

            // The composed per-unit chain re-run: the unified unit passes
            // every validator with the project's own factory records and the
            // in-project facts (the class arm included).
            Optional<CompilerDiagnostic> failure =
                SemanticLowerer.validateProjectUnit(app, result.tableOf(APP),
                    comparisonFacts(project),
                    app.classBindingFacts(),
                    result.registryOf(APP),
                    project.index().modules().get(APP), Map.of());
            check(failure.isEmpty(),
                "the composed chain accepts the builtin Error unit: "
                    + failure.map(CompilerDiagnostic::message).orElse(""));

            // ISSUE-0821: the builtin Error owner is compiler-owned, so the
            // standalone surface admits the unit without any provider context
            // (the boundary of the required-link rejection).
            check(SemanticIrValidator.validate(app,
                    comparisonFacts(project)).isEmpty(),
                "the builtin Error construction needs no provider context and "
                    + "admits standalone");
        } finally {
            deleteRecursively(project.root());
        }
    }

    private static void assertFailsClosed(String appSource,
            Map<String, String> extraSources, Map<String, String> extraExternals,
            String what, String expectedClassText) throws Exception {
        RealProject project = compileProject(appSource, extraSources, extraExternals);
        try {
            checkFailsClosed(lower(project, invocation()), what, expectedClassText);
        } finally {
            deleteRecursively(project.root());
        }
    }

    /**
     * The four fail-closed assertions of a seeded-owner construction (the
     * intended intermediate state): the first E6005 from the
     * class-construction validator, capability {@code CLASSES}, naming the
     * seeded class identity, with no project, no tables, no registries, no
     * seeds, and no namespace registrations.
     */
    private static void checkFailsClosed(SemanticLowerer.ProjectLoweringResult result,
            String what, String expectedClassText) {
        check(result.hasErrors() && result.project() == null,
            what + " fails closed with no project: " + result.diagnostics());
        check(result.tables().isEmpty() && result.registries().isEmpty()
                && result.seeds() == null && result.namespaces() == null,
            what + " fails closed with no tables, no registries, no seeds, and "
                + "no registrations");
        if (result.diagnostics().isEmpty()) {
            return;
        }
        CompilerDiagnostic diagnostic = result.diagnostics().get(0);
        check("E6005".equals(diagnostic.code()),
            what + " fails with E6005; got " + diagnostic.code());
        check(diagnostic.message().contains(
                ClassConstructionValidator.CONSTRUCTION_COHERENCE),
            what + " fails at the class-construction validator; got "
                + diagnostic.message());
        check(diagnostic.message().contains("capability CLASSES"),
            what + " carries capability CLASSES; got " + diagnostic.message());
        if (expectedClassText != null) {
            check(diagnostic.message().contains(expectedClassText),
                what + " names the seeded class identity " + expectedClassText
                    + " (the seed lookup resolved it); got "
                    + diagnostic.message());
        }
    }

    // =========================================================================
    // 6. The negative seeds
    // =========================================================================

    private static void testCorruptedUnitSeed(LoweredProject baseline)
            throws Exception {
        System.out.println("-- the corrupted-unit negative: a wrong boundary "
            + "triple fails the unit chain --");
        RealProject project = baseline.project();
        {
            SemanticLowerer.ProjectLoweringResult result = baseline.result();
            if (result.project() == null) {
                fail("the project lowers: " + result.diagnostics());
                return;
            }
            LoweredModuleUnit app = result.project().modules().get(APP);
            LoweredModuleUnit corrupted = corruptReturnBoundary(app);
            check(corrupted != null, "the corrupted unit is built (a RETURN names "
                + "an unknown return boundary)");
            if (corrupted == null) {
                return;
            }
            Optional<CompilerDiagnostic> failure =
                SemanticLowerer.validateProjectUnit(corrupted,
                    result.tableOf(APP),
                    comparisonFacts(project),
                    corrupted.classBindingFacts(),
                    new ClassFactoryRegistry(Map.of()),
                    project.index().modules().get(APP), Map.of());
            check(failure.isPresent(),
                "the corrupted unit fails the composed chain");
            if (failure.isEmpty()) {
                return;
            }
            check("E6005".equals(failure.get().code())
                    && failure.get().message().contains(
                        SemanticIrValidator.R_BOUNDARY_TRIPLE),
                "the corrupted unit returns the first E6005 R-BOUNDARY-TRIPLE; got "
                    + failure.get().message());
        }
    }

    /**
     * Rebuilds a lowered unit with the {@code RETURN}'s return boundary op
     * dropped from the produced op list — the dangling return identity of
     * the corrupted-unit negative (a hand-built closed-rule violation; the
     * op payloads stay untouched, so the op contract digests stay valid and
     * the closed gate is the rejecting member).
     */
    private static LoweredModuleUnit corruptReturnBoundary(LoweredModuleUnit unit) {
        List<SemanticOp> ops = new ArrayList<>(unit.ops());
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.RETURN
                    && op.payload() instanceof KindPayload.ReturnPayload payload) {
                List<SemanticOp> corrupted = new ArrayList<>();
                boolean dropped = false;
                for (SemanticOp candidate : ops) {
                    if (!dropped
                            && candidate.opId().equals(payload.returnBoundaryOpId())) {
                        dropped = true;
                        continue;
                    }
                    corrupted.add(candidate);
                }
                if (!dropped) {
                    return null;
                }
                return withOps(unit, corrupted);
            }
        }
        return null;
    }

    /** One lowered unit with the given op list (the corruption surface). */
    private static LoweredModuleUnit withOps(LoweredModuleUnit unit,
                                             List<SemanticOp> ops) {
        return mutateUnit(unit, copy -> copy.ops = new ArrayList<>(ops));
    }

    private static void testMissingModuleSeed(LoweredProject baseline)
            throws Exception {
        System.out.println("-- the missing-module negative: a reference outside "
            + "the closure fails the project-form R-EXTERNAL-ENTRY arm --");
        RealProject project = baseline.project();
        {
            SemanticLowerer.ProjectLoweringResult result = baseline.result();
            if (result.project() == null) {
                fail("the project lowers: " + result.diagnostics());
                return;
            }
            Map<ModuleId, LoweredModuleUnit> shortened = new LinkedHashMap<>();
            for (Map.Entry<ModuleId, LoweredModuleUnit> entry
                    : result.project().modules().entrySet()) {
                if (!entry.getKey().equals(UTIL)) {
                    shortened.put(entry.getKey(), entry.getValue());
                }
            }
            ExecutableLoweredProject missing = new ExecutableLoweredProject(
                SemanticProfile.DEAL_V1_3_INT32, project.index(), shortened, APP,
                result.project().classSeeds());
            Optional<CompilerDiagnostic> failure = SemanticIrValidator.validate(
                missing, comparisonFacts(project));
            check(failure.isPresent(),
                "the project with the missing callee module fails the project-form "
                    + "gate");
            if (failure.isEmpty()) {
                return;
            }
            check("E6005".equals(failure.get().code())
                    && failure.get().message().contains(
                        SemanticIrValidator.R_IDENTITY_LINK)
                    && failure.get().message().contains("implementation provider")
                    && failure.get().message().contains("carries no unit for it"),
                "the missing module returns the first E6005 R-IDENTITY-LINK naming "
                    + "the absent required implementation provider; got "
                    + failure.get().message());
        }
    }

    private static void testUnresolvedAliasSeed(LoweredProject baseline)
            throws Exception {
        System.out.println("-- the unresolved-alias negative: an alias without a "
            + "resolved import fact fails the import arm --");
        RealProject project = baseline.project();
        {
            List<CheckedModuleInput> modules = new ArrayList<>();
            for (CheckedModuleInput module : project.checkedProject().modules()) {
                modules.add(module.moduleId().equals(APP)
                    ? new CheckedModuleInput(module.moduleId(), module.sourceId(),
                        module.sourcePath(), module.ast(), module.checks(),
                        List.of(), module.exports(), module.kind())
                    : module);
            }
            CompilerInvocation invocation = invocation();
            CheckedProjectInput stripped = new CheckedProjectInput(invocation,
                project.checkedProject().entryModule(), modules,
                invocation.releaseStateHash());
            SemanticLowerer.ProjectLoweringResult result = SemanticLowerer.lowerProject(
                invocation, stripped, project.index(), project.manifests(),
                project.surface(), project.declarationIdentities(),
                BuiltinErrorDeclaration.synthesized(
                    project.checkedProject().modules().get(0).ast().span()),
                Set.of());
            check(result.hasErrors() && result.project() == null,
                "the unresolved alias produces no project");
            check(result.tables().isEmpty() && result.registries().isEmpty()
                    && result.seeds() == null && result.namespaces() == null,
                "the unresolved alias produces no tables, no registries, no seeds, "
                    + "and no registrations");
            if (!result.diagnostics().isEmpty()) {
                CompilerDiagnostic diagnostic = result.diagnostics().get(0);
                check("E6005".equals(diagnostic.code())
                        && diagnostic.message().contains(
                            SemanticLowerer.CONSTRUCT_UNLOWERED)
                        && diagnostic.message().contains(
                            "without a resolved import fact"),
                    "the unresolved alias fails the import arm with "
                        + "CONSTRUCT_UNLOWERED; got " + diagnostic.message());
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void writeFileIn(Path root, String relative, String content)
            throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path path) {
        if (path == null) {
            return;
        }
        try {
            if (Files.exists(path)) {
                try (var walk = Files.walk(path)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(entry -> {
                        try {
                            Files.deleteIfExists(entry);
                        } catch (java.io.IOException ignored) {
                            // best effort
                        }
                    });
                }
            }
        } catch (java.io.IOException ignored) {
            // best effort
        }
    }

    /** The unit's op named by the id, or {@code null}. */
    private static SemanticOp opOf(LoweredModuleUnit unit, OpId opId) {
        for (SemanticOp op : unit.ops()) {
            if (op.opId().equals(opId)) {
                return op;
            }
        }
        return null;
    }

    private static RawUnit assertUnitTransport(LoweredModuleUnit unit, String text) {
        RawUnit reparsed = ContractSnapshotCanonicalizer.parseUnit(
            (CanonicalJson.Obj) CanonicalJson.parse(text));
        assertUnitFacts(unit, reparsed);
        checkEq(text, CanonicalJson.serializeText(ContractSnapshotCanonicalizer.toJson(reparsed)),
            unit.moduleId() + ": canonical text is byte-stable");
        return reparsed;
    }

    private static void assertUnitFacts(LoweredModuleUnit unit, RawUnit reparsed) {
        String what = unit.moduleId() + ": ";
        checkEq(RawUnit.fromTyped(unit), reparsed, what + "every unit fact round-trips");
        checkEq(unit.bodyTable(), reparsed.bodyTable(), what + "both membership directions survive");
        checkEq(unit.classFactories(), reparsed.classFactories(), what + "factory registry survives");
        checkEq(unit.classBindingFacts(), reparsed.classBindingFacts(),
            what + "parameter/import facts survive");
        checkEq(unit, RawUnitMaterializer.materialize(reparsed),
            what + "transport materializes to the exact typed unit");
    }

    private static void assertStandaloneAdmission(LoweredModuleUnit unit, String text,
            RawUnit reparsed, SemanticIrValidator.ComparisonFacts facts) {
        boolean needsProvider = requiresProviderContext(reparsed);
        checkEq(needsProvider, SemanticIrValidator.validate(unit, facts).isPresent(),
            unit.moduleId() + ": standalone typed admission requires exactly the provider context");
        checkEq(needsProvider, SemanticIrValidator.validateText(text, facts).isPresent(),
            unit.moduleId() + ": standalone text admission requires exactly the provider context");
    }

    private static String assertProjectAdmission(ExecutableLoweredProject lowered,
            SemanticIrValidator.ComparisonFacts facts, String what) {
        check(SemanticIrValidator.validate(lowered, facts).isEmpty(), what + " (typed)");
        String text = SemanticIrValidator.toProjectText(lowered);
        check(SemanticIrValidator.validateText(text, facts).isEmpty(), what + " (text)");
        return text;
    }

    private static void assertReassembly(String text, SemanticIrDumper.ProjectDump dump,
            String what) {
        checkEq(ContractSnapshotCanonicalizer.parseProject(objOf(
                text.getBytes(StandardCharsets.UTF_8))),
            SemanticIrDumper.reassembleProject(dump.manifest(), documentsOf(dump)), what);
    }

    private static ContractSnapshotCanonicalizer.ParsedProject assertProjectTransport(
            ExecutableLoweredProject lowered, SemanticIrValidator.ComparisonFacts facts) {
        String text = SemanticIrValidator.toProjectText(lowered);
        ContractSnapshotCanonicalizer.ParsedProject parsed =
            ContractSnapshotCanonicalizer.parseProject((CanonicalJson.Obj) CanonicalJson.parse(text));
        checkEq(lowered.interfaceIndex(), parsed.interfaceIndex(), "project transport keeps the index");
        checkEq(lowered.classSeeds(), parsed.classSeeds(), "project transport keeps the seeds");
        check(SemanticIrValidator.validate(lowered, facts).isEmpty(), "typed project admits");
        check(SemanticIrValidator.validateText(text, facts).isEmpty(), "text project admits");
        return parsed;
    }

    private static void testCompleteUnitTransport(RealProject project,
            ExecutableLoweredProject lowered) {
        SemanticIrValidator.ComparisonFacts facts = comparisonFacts(project);
        boolean sawCaptures = false;
        boolean sawImportAliases = false;
        boolean sawClassLayout = false;
        for (LoweredModuleUnit unit : lowered.modules().values()) {
            String text = SemanticIrValidator.toUnitText(unit);
            checkEq(SemanticIrDumper.dumpModuleText(unit), text,
                unit.moduleId() + ": dump uses canonical unit mapping");
            RawUnit reparsed = assertUnitTransport(unit, text);
            assertStandaloneAdmission(unit, text, reparsed, facts);
            sawCaptures |= reparsed.functions().stream().anyMatch(f -> !f.captures().isEmpty());
            sawClassLayout |= !reparsed.classLayouts().isEmpty();
            sawImportAliases |= !reparsed.classBindingFacts().importAliases().isEmpty();
            for (RawUnit.RawClassLayout entry : reparsed.classLayouts()) {
                checkEq(entry.key(), entry.layout().classId(),
                    unit.moduleId() + ": layout key matches contained identity");
            }
        }
        check(sawClassLayout && sawCaptures && sawImportAliases,
            "real transport exercises layouts, closure generations and import aliases");
    }

    /**
     * ISSUE-0821: the standalone surfaces never approve a required foreign
     * link, the complete project admission resolves exactly those links
     * from the supplied closure — the shared-factory join names the
     * provider's owning module and operation tag, never the class-id
     * namespace — the composed production admission consumes exactly the
     * unit's owned companion records, and the project framing admission
     * validates the module keys, index coverage and classification and the
     * dependency order.
     */
    private static void testStandaloneProviderContextAndExactViews(
            LoweredProject imported, LoweredProject baseline) throws Exception {
        System.out.println("-- standalone vs complete admission: required foreign links, "
            + "the provider join and the exact companion views (ISSUE-0821) --");
        RealProject project = imported.project();
        ExecutableLoweredProject lowered = imported.result().project();
        check(lowered != null,
            "the imported-class fixture lowers: " + imported.result().diagnostics());
        if (lowered == null) {
            return;
        }
        SemanticIrValidator.ComparisonFacts facts = comparisonFacts(project);
        LoweredModuleUnit util = lowered.modules().get(UTIL);
        LoweredModuleUnit app = lowered.modules().get(APP);
        check(util != null && app != null,
            "the closure carries the class provider and the constructing entry module");
        if (util == null || app == null) {
            return;
        }

        // (1) The provider module's own facts are locally complete: it
        // carries no foreign link, so the standalone surfaces admit it.
        check(SemanticIrValidator.validate(util, facts).isEmpty(),
            "the class provider unit carries no foreign link and admits standalone");
        check(SemanticIrValidator.validateText(
                SemanticIrValidator.toUnitText(util), facts).isEmpty(),
            "the class provider unit text carries no foreign link");

        // (2) The consumer's cross-module call refers to the provider
        // module's recorded EXTERNAL_ENTRY: standalone admission returns
        // E6005 at the referring CALL (its own retained origin), never a
        // fabricated provider; the imported construction's owner facts are
        // the same missing provider context.
        boolean sharedConstruction = false;
        SemanticOp foreignCall = null;
        for (SemanticOp op : app.ops()) {
            if (op.kind() == SemanticOpKind.CLASS_NEW
                    && op.payload() instanceof KindPayload.ClassNewPayload payload
                    && payload.constructionProvider() == ConstructionProvider.SHARED_FACTORY) {
                sharedConstruction = true;
            }
            if (op.kind() == SemanticOpKind.CALL
                    && op.payload() instanceof KindPayload.CallPayload call
                    && call.externalEntryRef() != null
                    && call.externalEntryRef().module().equals(UTIL)) {
                foreignCall = op;
            }
        }
        check(sharedConstruction,
            "the consumer carries the imported CLASS_NEW(SHARED_FACTORY)");
        check(foreignCall != null,
            "the consumer carries the cross-module CALL and its externalEntryRef");
        if (foreignCall != null) {
            Optional<CompilerDiagnostic> typed = SemanticIrValidator.validate(app, facts);
            Optional<CompilerDiagnostic> text = SemanticIrValidator.validateText(
                SemanticIrValidator.toUnitText(app), facts);
            assertMutationRule("the standalone foreign provider link", typed,
                SemanticIrValidator.R_EXTERNAL_ENTRY, "externalEntryRef",
                "no implementation closure");
            check(text.isPresent() && typed.isPresent()
                    && text.get().message().equals(typed.get().message())
                    && text.get().range().equals(typed.get().range()),
                "the standalone unit surfaces report the identical first failure "
                    + "and range for the required foreign link");
            check(typed.isPresent() && java.util.Objects.equals(
                    RawOrigin.of(foreignCall.origin()).diagnosticRange(),
                    typed.get().range()),
                "the standalone rejection carries the referring CALL's own origin");
        }

        // (3) The complete project supplies the missing context: exactly those
        // links resolve and both project surfaces admit.
        check(SemanticIrValidator.validate(lowered, facts).isEmpty(),
            "the complete project admits the imported construction (typed)");
        check(SemanticIrValidator.validateText(
                SemanticIrValidator.toProjectText(lowered), facts).isEmpty(),
            "the complete project admits the imported construction (text)");

        // (4) The exact provider join: the registry's owning module and the
        // factory operation's module tag — the class-id namespace is @src,
        // never the owning module 'util'.
        ExternalModuleInterface utilInterface = project.index().modules().get(UTIL);
        ClassInterface pointEntry = null;
        for (ClassInterface candidate : utilInterface.classes()) {
            if (candidate.classId().equals(POINT)) {
                pointEntry = candidate;
            }
        }
        check(pointEntry != null, "the provider's interface entry carries the class");
        if (pointEntry != null) {
            OpId joined = ClassFactoryRegistry.ownerFactoryOp(
                imported.result().registries(), pointEntry.constructionEntry());
            check(joined != null && joined.module().equals(UTIL),
                "the shared-factory join names the owning module through the "
                    + "registry and the operation module tag, never the class-id "
                    + "namespace " + POINT.modulePath());
            SemanticOp factoryOp = opOf(util, joined);
            check(factoryOp != null && factoryOp.kind() == SemanticOpKind.CLASS_FACTORY
                    && factoryOp.result() instanceof ValueId,
                "the join names the owner's actual CLASS_FACTORY result");
        }

        // (5) The baseline consumer refers to another module's external
        // function: the standalone binding surface rejects it, the complete
        // project resolves the callee's recorded EXTERNAL_ENTRY.
        ExecutableLoweredProject baselineLowered = baseline.result().project();
        check(baselineLowered != null, "the baseline project lowers: "
            + baseline.result().diagnostics());
        if (baselineLowered == null) {
            return;
        }
        SemanticIrValidator.ComparisonFacts baselineFacts =
            new SemanticIrValidator.ComparisonFacts(
                baseline.project().index().interfaceIndexDigest(),
                SemanticProfile.DEAL_V1_3_INT32, invocation().capabilityRegistryHash());
        LoweredModuleUnit baselineApp = baselineLowered.modules().get(APP);
        check(baselineApp != null, "the baseline entry module is in the closure");
        if (baselineApp == null) {
            return;
        }
        assertMutationRule("the standalone cross-module binding",
            SemanticIrValidator.validate(baselineApp, baselineFacts),
            SemanticIrValidator.R_EXTERNAL_ENTRY, "outside the unit",
            "no implementation closure");
        check(SemanticIrValidator.validate(baselineLowered, baselineFacts).isEmpty(),
            "the complete project resolves the cross-module binding");

        // (6) The composed production admission consumes exactly the unit's
        // owned companion records: a differing supplied view rejects through
        // E6005 before any success, and content-equal detached copies are
        // accepted (the owned records are consumed, never the copies).
        ExternalModuleInterface appInterface =
            baseline.project().index().modules().get(APP);
        check(appInterface != null, "the entry module carries its index entry");
        Map<ClassId, SharedFactoryFacts> noSharedFactories = Map.of();
        Map<ClassId, ClassRegistrationSeeds.ClassRegistration> seeds =
            baselineLowered.classSeeds().registrations();
        check(SemanticLowerer.validateProjectUnit(baselineApp,
                baselineApp.bodyTable(), baselineFacts,
                baselineApp.classBindingFacts(), baselineApp.classFactories(),
                appInterface, noSharedFactories, seeds)
                .isEmpty(),
            "the exact owned companion views admit through the composed production "
                + "admission");
        check(SemanticLowerer.validateProjectUnit(baselineApp,
                new StructuredBodyTable(baselineApp.bodyTable().blockOps(),
                    baselineApp.bodyTable().opBlocks()),
                baselineFacts,
                new ClassBindingFacts(baselineApp.classBindingFacts().parameters(),
                    baselineApp.classBindingFacts().importAliases()),
                new ClassFactoryRegistry(baselineApp.classFactories().factories()),
                appInterface, noSharedFactories, seeds).isEmpty(),
            "content-equal detached companion views admit (the unit's owned records "
                + "are consumed)");

        check(!baselineApp.bodyTable().blockOps().isEmpty(),
            "the view fixture carries a non-empty body table");
        Set<BindingId> parameters = new LinkedHashSet<>(baselineApp.classBindingFacts().parameters());
        parameters.add(new BindingId(9_000_001));
        Map<ClassFactoryId, OpId> factories = new LinkedHashMap<>(baselineApp.classFactories().factories());
        factories.put(new ClassFactoryId(9_000_001), new OpId(APP, 9_000_001));
        record ViewCase(String name, StructuredBodyTable table, ClassBindingFacts bindings,
                        ClassFactoryRegistry factories) { }
        for (ViewCase row : List.of(
                new ViewCase("body table", new StructuredBodyTable(Map.of(), Map.of()),
                    baselineApp.classBindingFacts(), baselineApp.classFactories()),
                new ViewCase("binding facts", baselineApp.bodyTable(),
                    new ClassBindingFacts(parameters, baselineApp.classBindingFacts().importAliases()),
                    baselineApp.classFactories()),
                new ViewCase("class-factory registry", baselineApp.bodyTable(),
                    baselineApp.classBindingFacts(), new ClassFactoryRegistry(factories)))) {
            Optional<CompilerDiagnostic> mismatch = SemanticLowerer.validateProjectUnit(baselineApp,
                row.table(), baselineFacts, row.bindings(), row.factories(), appInterface, noSharedFactories, seeds);
            expectProjectAdmissionFailure(mismatch, SemanticLowerer.PROJECT_VIEW_MISMATCH, row.name(),
                "a differing supplied " + row.name() + " view rejects through E6005");
        }

        // (7) The project framing admission: a module-map key that disagrees
        // with the contained unit's identity, a closure module without its
        // index entry, a misclassified provider, an inverted dependency order
        // and a foreign carried profile all reject; the coherent
        // dependency-ordered project (with its repeated references to the
        // one provider) admits.
        Map<ModuleId, LoweredModuleUnit> renamedKeys = new LinkedHashMap<>();
        renamedKeys.put(new ModuleId("util.alias"), lowered.modules().get(UTIL));
        renamedKeys.put(APP, lowered.modules().get(APP));
        Map<ModuleId, LoweredModuleUnit> reordered = new LinkedHashMap<>();
        reordered.put(APP, lowered.modules().get(APP));
        reordered.put(UTIL, lowered.modules().get(UTIL));
        record FramingCase(String detail, ProjectInterfaceIndex index,
                           Map<ModuleId, LoweredModuleUnit> modules) { }
        for (FramingCase row : List.of(
                new FramingCase("module-map key", lowered.interfaceIndex(), renamedKeys),
                new FramingCase("no module silently disappears",
                    withoutIndexModule(lowered.interfaceIndex(), UTIL), lowered.modules()),
                new FramingCase("classified",
                    withImportKind(lowered.interfaceIndex(), APP, "u", ExternalModuleKind.HOST), lowered.modules()),
                new FramingCase("a closure unit is an implementation provider",
                    withIndexKind(lowered.interfaceIndex(), UTIL, ExternalModuleKind.HOST), lowered.modules()),
                new FramingCase("dependency order", lowered.interfaceIndex(), reordered))) {
            expectProjectAdmissionFailure(SemanticIrValidator.validate(
                    new ExecutableLoweredProject(lowered.semanticProfile(), row.index(),
                        row.modules(), lowered.entryModule(), lowered.classSeeds()), facts),
                SemanticIrValidator.R_IDENTITY_LINK, row.detail(),
                "the typed project rejects invalid framing: " + row.detail());
        }

        CanonicalJson.Obj projectObj = (CanonicalJson.Obj) CanonicalJson.parse(
            SemanticIrValidator.toProjectText(lowered));
        String foreignProfile = CanonicalJson.serializeText(withEntry(projectObj,
            "semanticProfile", CanonicalJson.str("DEAL_V1_2_INT32")));
        expectProjectAdmissionFailure(SemanticIrValidator.validateText(foreignProfile, facts),
            SemanticIrValidator.R_PROFILE, "project semanticProfile",
            "the project text rejects a carried profile that is not the supplied one");
    }


    /**
     * The complete project transport, the interface index and the class
     * seeds (ISSUE-0818): the project text round-trips the exact project —
     * the {@code deal.semantic-ir/2} envelope, the profile, the entry
     * module, the complete {@code deal.semantic-interface/1} index, the
     * class registration seeds and every dependency-ordered unit through
     * the complete unit mapping — and a dumped manifest plus its ordered
     * module documents reassembles to that same project. Missing, extra,
     * duplicate, misordered and mismatched document sets reject before any
     * project record exists, and so does a manifest whose class
     * registration seeds contradict its own interface index (the two are
     * two representations of one declaration surface, never independent
     * class authorities).
     */
    private static void testCompleteProjectTransportAndDumpReassembly(LoweredProject baseline)
            throws Exception {
        System.out.println("-- the complete /2 project transport, the interface "
            + "index and the seeds, and the checked dump reassembly --");
        SemanticLowerer.ProjectLoweringResult result = baseline.result();
        ExecutableLoweredProject lowered = result.project();
        check(lowered != null,
            "the project-transport fixture lowers: " + result.diagnostics());
        if (lowered == null) {
            return;
        }
        SemanticIrValidator.ComparisonFacts facts = comparisonFacts(baseline.project());

        // ----- the project text: the complete framing and the unit mapping -----
        String text = SemanticIrValidator.toProjectText(lowered);
        check(text.contains("\"formatVersion\":\"deal.semantic-ir/2\""),
            "the project text carries the deal.semantic-ir/2 envelope");
        check(text.contains("\"entryModule\":\"app\""),
            "the project entry module stays a plain string");
        check(text.contains("\"interfaceIndex\":{\"formatVersion\":"
                + "\"deal.semantic-interface/1\""),
            "the project text carries the interface index under its unchanged format");
        check(text.contains("\"classSeeds\":[{\"key\":"),
            "the project text carries the class registration seeds");
        check(text.contains("\"semanticProfile\":\"DEAL_V1_3_INT32\""),
            "the project text carries the owner profile explicitly");
        check(!text.contains("DEAL_V1_2_INT32") && !text.contains("LEGACY_SAFE_INT"),
            "no removed profile value appears in the project transport");
        for (LoweredModuleUnit carried : lowered.modules().values()) {
            checkEq(SemanticProfile.DEAL_V1_3_INT32, carried.semanticProfile(),
                "unit " + carried.moduleId() + " carries the owner profile");
            check(SemanticIrValidator.toUnitText(carried)
                    .contains("\"semanticProfile\":\"DEAL_V1_3_INT32\""),
                "unit " + carried.moduleId() + " transports the owner profile");
        }
        ContractSnapshotCanonicalizer.ParsedProject parsed =
            ContractSnapshotCanonicalizer.parseProject(
                (CanonicalJson.Obj) CanonicalJson.parse(text));
        checkEq("deal.semantic-ir/2", parsed.formatVersion(),
            "the parsed project keeps the /2 envelope");
        checkEq("DEAL_V1_3_INT32", parsed.semanticProfile(),
            "the parsed project keeps the explicit semantic profile");
        checkEq("app", parsed.entryModule(),
            "the parsed project keeps the entry module");
        checkEq(List.of("util", "app"), parsed.modulePaths(),
            "the parsed project keeps the dependency-ordered module paths");
        checkEq(lowered.interfaceIndex(), parsed.interfaceIndex(),
            "the interface index inverse retains every module/interface/import/class/field "
                + "fact");
        checkEq(lowered.classSeeds(), parsed.classSeeds(),
            "the class seeds inverse keeps the registration keys, layouts and owners");
        checkEq(baseline.project().index().interfaceIndexDigest(),
            parsed.interfaceIndex().interfaceIndexDigest(),
            "the reparsed index keeps its digest");
        for (Map.Entry<ModuleId, ExternalModuleInterface> entry
                : parsed.interfaceIndex().modules().entrySet()) {
            checkEq(entry.getKey(), entry.getValue().moduleId(),
                "the index inverse keeps the " + entry.getKey() + " entry identity");
        }
        ExternalModuleInterface appEntry = parsed.interfaceIndex().modules().get(APP);
        check(appEntry != null, "the index inverse carries the entry module entry");
        if (appEntry != null) {
            checkEq(List.of("u", "cfg"),
                appEntry.imports().stream().map(ResolvedImport::alias).toList(),
                "the index inverse keeps the resolved import aliases in source order");
            checkEq(List.of(ExternalModuleKind.IMPLEMENTATION, ExternalModuleKind.HOST),
                appEntry.imports().stream().map(ResolvedImport::kind).toList(),
                "the index inverse keeps the resolved import kinds");
            checkEq(List.of("main"),
                appEntry.exports().stream().map(ExportInterface::name).toList(),
                "the index inverse keeps the export entries");
        }
        ExternalModuleInterface hostEntry = parsed.interfaceIndex().modules().get(HOST_CFG);
        check(hostEntry != null && hostEntry.kind() == ExternalModuleKind.HOST,
            "the index inverse keeps the host declaration entry");
        ClassInterface endpoint = hostEntry == null || hostEntry.classes().isEmpty()
            ? null : hostEntry.classes().get(0);
        check(endpoint != null
                && endpoint.classId().equals(new ClassId(HOST_CFG.path(), "Endpoint")),
            "the index inverse keeps the declared class identity");
        if (endpoint != null) {
            checkEq(List.of("path", "port"),
                endpoint.fields().stream().map(FieldInterface::name).toList(),
                "the index inverse keeps the declared field order");
            check(endpoint.fields().get(1).optional() && !endpoint.fields().get(1).nullable(),
                "the index inverse keeps the declared optional/nullable field facts");
        }
        ClassRegistrationSeeds.ClassRegistration endpointSeed =
            parsed.classSeeds().registrationFor(ENDPOINT);
        check(endpointSeed != null && endpointSeed.owner() == ConstructionProvider.HOST_DECLARATION,
            "the seeds inverse keeps the declared class registration and its owner");
        ClassRegistrationSeeds.ClassRegistration errorSeed =
            parsed.classSeeds().registrationFor(BUILTIN_ERROR);
        check(errorSeed != null && errorSeed.owner() == ConstructionProvider.BUILTIN_ERROR
                && errorSeed.layout().equals(ClassLayout.BUILTIN_ERROR),
            "the seeds inverse keeps the compiler-owned builtin Error registration");
        List<LoweredModuleUnit> units = List.copyOf(lowered.modules().values());
        checkEq(units.size(), parsed.modules().size(),
            "every project module is carried");
        for (int i = 0; i < Math.min(units.size(), parsed.modules().size()); i++) {
            assertUnitFacts(units.get(i), parsed.modules().get(i));
        }
        checkEq(text, CanonicalJson.serializeText(
                ContractSnapshotCanonicalizer.toJson(parsed)),
            "the reparsed project re-serializes byte-identically");
        Optional<CompilerDiagnostic> readmitted =
            SemanticIrValidator.validateText(text, facts);
        check(readmitted.isEmpty(),
            "the project text re-admits through the text surface: "
                + readmitted.map(CompilerDiagnostic::message).orElse(""));

        // ----- the dump manifest and the ordered module documents -----
        SemanticIrDumper.ProjectDump dump = SemanticIrDumper.dumpProject(lowered);
        SemanticLowerer.ProjectLoweringResult rebuilt = lower(baseline.project(), invocation());
        check(rebuilt.project() != null, "the dump determinism fixture lowers independently");
        if (rebuilt.project() == null) {
            return;
        }
        SemanticIrDumper.ProjectDump repeated = SemanticIrDumper.dumpProject(rebuilt.project());
        check(java.util.Arrays.equals(dump.manifest(), repeated.manifest()),
            "repeated project manifests are byte-identical");
        checkEq(dump.modules().size(), repeated.modules().size(),
            "both repeats carry every module document");
        for (int i = 0; i < Math.min(dump.modules().size(), repeated.modules().size()); i++) {
            check(java.util.Arrays.equals(dump.modules().get(i).bytes(),
                    repeated.modules().get(i).bytes()),
                "the repeated dump of module " + dump.modules().get(i).moduleId()
                    + " is byte-identical");
        }
        String manifestText = dump.manifestText();
        check(manifestText.contains("\"formatVersion\":\"deal.semantic-ir/2\"")
                && manifestText.contains("\"entryModule\":\"app\"")
                && manifestText.contains("\"semanticProfile\":\"DEAL_V1_3_INT32\""),
            "the manifest carries the project's formatVersion, entryModule and profile");
        check(manifestText.contains("\"interfaceIndex\":{\"formatVersion\":"
                + "\"deal.semantic-interface/1\"")
                && manifestText.contains("\"classSeeds\":[{\"key\":"),
            "the manifest carries the complete interface index and the class seeds");
        check(manifestText.contains("\"modules\":[\"util\",\"app\"]"),
            "the manifest modules field stays the ordered module-path list");
        check(!manifestText.contains("DEAL_V1_2_INT32")
                && !manifestText.contains("LEGACY_SAFE_INT"),
            "no removed profile value appears in the dump manifest");
        for (SemanticIrDumper.ModuleDump module : dump.modules()) {
            check(!module.text().contains("DEAL_V1_2_INT32")
                    && !module.text().contains("LEGACY_SAFE_INT"),
                "no removed profile value appears in the dump of module "
                    + module.moduleId());
            check(module.text().contains("\"semanticProfile\":\"DEAL_V1_3_INT32\""),
                "the dump of module " + module.moduleId()
                    + " carries the owner profile explicitly");
        }
        CanonicalJson.Obj manifest = objOf(dump.manifest());
        for (String unitFact : List.of("bodyTable", "classLayouts", "functions", "ops",
                "classFactories", "jsonDefaults", "classBindingFacts", "moduleInit",
                "exportPlan", "functionBindings")) {
            check(at(manifest, unitFact) == null,
                "the manifest carries no unit fact (" + unitFact + ")");
        }
        ContractSnapshotCanonicalizer.ParsedProject reassembled =
            SemanticIrDumper.reassembleProject(dump);
        checkEq(parsed, reassembled,
            "the manifest plus the ordered module documents reassemble to the exact "
                + "project (modules and order, entry, profile, index, seeds and units)");
        // The reassembled document set admits as one project: the manifest and
        // the module records are not independent authorities, and only their
        // agreement exposes a project.
        Map<ModuleId, LoweredModuleUnit> reassembledModules = new LinkedHashMap<>();
        for (RawUnit raw : reassembled.modules()) {
            reassembledModules.put(new ModuleId(raw.modulePath()),
                RawUnitMaterializer.materialize(raw));
        }
        Optional<CompilerDiagnostic> reassembledAdmission = SemanticIrValidator.validate(
            new ExecutableLoweredProject(SemanticProfile.DEAL_V1_3_INT32,
                reassembled.interfaceIndex(), reassembledModules,
                new ModuleId(reassembled.entryModule()), reassembled.classSeeds()), facts);
        check(reassembledAdmission.isEmpty(),
            "the reassembled dump documents admit as one project: "
                + reassembledAdmission.map(CompilerDiagnostic::message).orElse(""));
        checkEq(lowered.semanticProfile().name(), reassembled.semanticProfile(),
            "the reassembled project carries the typed project's profile");
        checkEq(lowered.entryModule().path(), reassembled.entryModule(),
            "the reassembled project carries the typed project's entry module");
        checkEq(lowered.interfaceIndex(), reassembled.interfaceIndex(),
            "the reassembled interface index is the typed project's index");
        checkEq(lowered.classSeeds(), reassembled.classSeeds(),
            "the reassembled class seeds are the typed project's seeds");
        checkEq(List.of("util", "app"), reassembled.modulePaths(),
            "the reassembled module order is the dependency order");
        List<byte[]> documents = documentsOf(dump);
        for (int i = 0; i < Math.min(units.size(), reassembled.modules().size()); i++) {
            assertUnitFacts(units.get(i), reassembled.modules().get(i));
        }

        // The written dump (existing file naming and LF framing) reassembles too.
        Path dumpRoot = Files.createTempDirectory("semantic-project-dump");
        try {
            SemanticIrDumper.dumpProjectTo(dumpRoot, lowered);
            byte[] manifestFile = Files.readAllBytes(
                dumpRoot.resolve(SemanticIrDumper.MANIFEST_FILE_NAME));
            check(java.util.Arrays.equals(withLf(dump.manifest()), manifestFile),
                "the written manifest is the canonical document plus exactly one LF");
            List<byte[]> fileDocuments = new ArrayList<>();
            for (ModuleId module : lowered.modules().keySet()) {
                fileDocuments.add(Files.readAllBytes(dumpRoot
                    .resolve(SemanticIrDumper.MODULES_DIR_NAME)
                    .resolve(SemanticIrDumper.moduleFileName(module))));
            }
            checkEq(parsed, SemanticIrDumper.reassembleProject(manifestFile, fileDocuments),
                "the written dump reassembles to the exact project");
        } finally {
            deleteRecursively(dumpRoot);
        }

        // ----- the rejected document sets start no admission -----
        List<byte[]> missing = new ArrayList<>(documents);
        missing.remove(0);
        expectReassemblyFailure(dump.manifest(), missing,
            "a missing module document rejects");
        List<byte[]> extra = new ArrayList<>(documents);
        extra.add(documents.get(0));
        expectReassemblyFailure(dump.manifest(), extra,
            "an extra module document rejects");
        List<byte[]> duplicated = new ArrayList<>();
        duplicated.add(documents.get(0));
        duplicated.add(documents.get(0));
        expectReassemblyFailure(dump.manifest(), duplicated,
            "a duplicated module document rejects");
        List<byte[]> misordered = new ArrayList<>(documents);
        java.util.Collections.reverse(misordered);
        expectReassemblyFailure(dump.manifest(), misordered,
            "misordered module documents reject");
        List<byte[]> foreign = new ArrayList<>(documents);
        foreign.set(foreign.size() - 1, withModulePath(
            documents.get(foreign.size() - 1), "foreign"));
        expectReassemblyFailure(dump.manifest(), foreign,
            "a document carrying a foreign module identity rejects");
        expectReassemblyFailure(manifestWithModulePaths(dump.manifest(), "util", "utilx"),
            documents, "a manifest module path that no document carries rejects");
        expectReassemblyFailure(manifestWithModulePaths(dump.manifest(), "util", "util"),
            documents, "a duplicate manifest module path rejects");
        expectReassemblyFailure(manifestWithModulePaths(dump.manifest(), "util"),
            documents, "a manifest module list shorter than the documents rejects");
        expectReassemblyFailure(manifestWithoutModule(dump.manifest(), "app"), documents,
            "a manifest without its entry module rejects");
        expectReassemblyFailure(manifestWithDuplicatedIndexModule(dump.manifest()), documents,
            "a duplicate interface index module identity rejects");
        expectReassemblyFailure(manifestWithDuplicatedSeed(dump.manifest()), documents,
            "a duplicate class-seed registration key rejects");
        expectReassemblyFailure(manifestWithMutatedIndexModule(dump.manifest()), documents,
            "a manifest index that no longer agrees with the documents rejects");
        expectReassemblyFailure(manifestWithSeedOwner(dump.manifest(), "Endpoint", "LOCAL"),
            documents,
            "a manifest seed whose owner disagrees with its field owners rejects");
        expectReassemblyFailure(manifestWithSeedOwner(dump.manifest(), "Endpoint",
            "NOT_AN_OWNER"), documents,
            "a manifest seed carrying an unknown owner rejects");
        expectReassemblyFailure(manifestWithSeedFieldDescriptor(dump.manifest(),
            "Endpoint", "path", "number"), documents,
            "a manifest seed whose field descriptor contradicts the interface's "
                + "declared type rejects");
        expectReassemblyFailure(manifestWithSeedFieldDescriptor(dump.manifest(),
            "Endpoint", "path", "?number"), documents,
            "a manifest seed whose nullable field descriptor contradicts the "
                + "interface's declared type rejects");
        expectReassemblyFailure(manifestWithSeedFieldDescriptor(dump.manifest(),
            "Endpoint", "port", "string"), documents,
            "a manifest seed field descriptor mutation on a non-defaulted field "
                + "rejects");
        for (String truncated : List.of("(", "(number")) {
            expectReassemblyFailure(manifestWithSeedFieldDescriptor(dump.manifest(),
                "Endpoint", "path", truncated), documents,
                "a truncated seed descriptor \"" + truncated + "\" rejects as a "
                    + "transport decode failure, never an unchecked parser failure");
        }
        expectReassemblyFailure(manifestWithSeedKey(dump.manifest(), "Endpoint",
            "EndpointElsewhere"), documents,
            "a manifest seed whose registration key disagrees with its contained "
                + "layout identity rejects");
        expectReassemblyFailure(manifestWithoutSeed(dump.manifest(), "Endpoint"),
            documents,
            "a manifest that drops an exported declaration class's seed rejects");
        expectReassemblyFailure(withNewEntry(dump.manifest(), "bodyTable",
            CanonicalJson.obj()), documents,
            "a manifest that gains a unit fact rejects");
        expectReassemblyFailure(dump.manifest(), List.of(),
            "an empty document list rejects");
    }

    /**
     * The project-level seed/module join and the common admission of the
     * transported class facts (ISSUE-0818 review): the class registration
     * seeds and the module documents are records of one declaration/
     * construction surface, so a manifest whose registration owner is
     * internally consistent (the owner member and every field owner agree)
     * yet contradicts the referring {@code CLASS_NEW}'s default owner is
     * rejected at checked reassembly; the same disagreement and the seed
     * field-descriptor disagreement reject on both project admission
     * surfaces; a mutated carried interface index rejects through the
     * carried index digest. Each rejected case yields no project record
     * and, on admission, exactly the first responsible E6005.
     */
    private static void testProjectSeedModuleJoinAndAdmission(LoweredProject fixture) {
        System.out.println("-- the project seed/module join: the checked reassembly "
            + "and the typed/text admission of the carried class facts --");
        RealProject project = fixture.project();
        {
            SemanticLowerer.ProjectLoweringResult result = fixture.result();
            check(!result.hasErrors() && result.project() != null,
                "the host-class transport project lowers: " + result.diagnostics());
            if (result.project() == null) {
                return;
            }
            ExecutableLoweredProject lowered = result.project();
            SemanticIrValidator.ComparisonFacts facts =
                comparisonFacts(project);

            // The unmutated project admits on both surfaces.
            String text = assertProjectAdmission(lowered, facts,
                "the host-class project passes the project surfaces");

            // (1) Checked reassembly: the internally consistent seed-owner
            // mutation (the registration owner and every field owner) passes
            // the index/seed rule, yet the module document's referring
            // CLASS_NEW still carries HOST_DECLARATION, so the join rejects.
            SemanticIrDumper.ProjectDump dump = SemanticIrDumper.dumpProject(lowered);
            List<byte[]> documents = documentsOf(dump);
            check(ClassConstructionValidator.checkProjectSeedCoherence(
                    lowered.interfaceIndex(), lowered.classSeeds()).isEmpty(),
                "the unmutated index and seeds cohere under the index/seed rule");
            expectReassemblyFailure(manifestWithSeedOwnerAndFieldOwners(dump.manifest(),
                "Endpoint", "BUILTIN_ERROR"), documents,
                "a manifest whose seed registration owner and field owners are both "
                    + "mutated to another declaration owner rejects against the "
                    + "module document's referring CLASS_NEW");

            // (2) Text admission: the same internally consistent mutation and
            // the seed field-descriptor mutation are rejected by the class
            // validation owner's rule, never silently admitted. The composed
            // per-module class owner runs before the project-level seed joins,
            // so the first failure is its resolved-layout disagreement with the
            // referring CLASS_NEW; the seed joins still reject the same records
            // at checked reassembly (assertion (1)).
            expectProjectAdmissionFailure(SemanticIrValidator.validateText(
                    asText(manifestWithSeedOwnerAndFieldOwners(
                        text.getBytes(StandardCharsets.UTF_8), "Endpoint", "BUILTIN_ERROR")),
                    facts),
                ClassConstructionValidator.CONSTRUCTION_COHERENCE,
                "carries a layout that differs from the resolved layout",
                "the text project surface rejects the seed owner that contradicts "
                    + "the module's referring CLASS_NEW");
            expectProjectAdmissionFailure(SemanticIrValidator.validateText(
                    asText(manifestWithSeedFieldDescriptor(
                        text.getBytes(StandardCharsets.UTF_8), "Endpoint", "path", "number")),
                    facts),
                ClassConstructionValidator.CONSTRUCTION_COHERENCE,
                "carries a layout that differs from the resolved layout",
                "the text project surface rejects the manifest seed descriptor that "
                    + "contradicts the interface's declared field type");

            // (3) Typed admission: a mutated seed set for the same project
            // (the registration owner and every field owner) is rejected by
            // the same rule over the module's referring class fact.
            ExecutableLoweredProject ownerMutated = new ExecutableLoweredProject(
                lowered.semanticProfile(), lowered.interfaceIndex(), lowered.modules(),
                lowered.entryModule(),
                withSeedOwner(lowered.classSeeds(), ENDPOINT, ConstructionProvider.BUILTIN_ERROR));
            expectProjectAdmissionFailure(SemanticIrValidator.validate(ownerMutated, facts),
                ClassConstructionValidator.CONSTRUCTION_COHERENCE,
                "carries a layout that differs from the resolved layout",
                "the typed project surface rejects the seed owner that contradicts "
                    + "the module's referring CLASS_NEW");

            // (4) The carried interface index is consumed on both surfaces:
            // a mutation changes its digest, so the carried digest no longer
            // equals the invocation's supplied digest.
            ProjectInterfaceIndex renamedIndex = withIndexExportType(
                lowered.interfaceIndex(),
                lowered.interfaceIndex().modules().keySet().iterator().next(),
                "(i32) => i32");
            check(!renamedIndex.interfaceIndexDigest()
                    .equals(lowered.interfaceIndex().interfaceIndexDigest()),
                "the mutated index carries another digest");
            expectProjectAdmissionFailure(SemanticIrValidator.validateText(
                    asText(manifestWithIndexExportType(
                        text.getBytes(StandardCharsets.UTF_8))),
                    facts),
                SemanticIrValidator.R_PROFILE, "interface index digest",
                "the text project surface rejects a mutated carried interface index");
            expectProjectAdmissionFailure(SemanticIrValidator.validate(
                    new ExecutableLoweredProject(lowered.semanticProfile(), renamedIndex,
                        lowered.modules(), lowered.entryModule(), lowered.classSeeds()),
                    facts),
                SemanticIrValidator.R_PROFILE, "interface index digest",
                "the typed project surface rejects a mutated carried interface index");
        }
    }

    /**
     * The project seed nominal class reference (ISSUE-0818 review): a seed
     * field descriptor names a declaration class through its exact carried
     * class identity. Moving only the external spelling of the reference
     * (the dotted rendering stays identical) must not admit an unregistered
     * class: the class validation owner resolves the reference through the
     * carried registration at checked reassembly and on both project
     * admission surfaces. A malformed seed descriptor text is a transport
     * decode failure there as well, never an unchecked parser crash.
     */
    private static void testProjectSeedNominalClassReference() throws Exception {
        System.out.println("-- the project seed nominal class reference: the exact "
            + "carried identity, never the dotted rendering --");
        RealProject project = compileProject(HOST_CLASS_REFERENCE_APP_SOURCE,
            Map.of("src/cfg.d.deal", HOST_CLASS_REFERENCE_DECLARATION), Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(project, invocation());
            check(!result.hasErrors() && result.project() != null,
                "the class-reference transport project lowers: " + result.diagnostics());
            if (result.project() == null) {
                return;
            }
            ExecutableLoweredProject lowered = result.project();
            SemanticIrValidator.ComparisonFacts facts =
                comparisonFacts(project);
            String text = assertProjectAdmission(lowered, facts,
                "the class-reference project passes the project surfaces");
            check(lowered.classSeeds().registrationFor(HOST_HIDDEN) != null,
                "the declaration class Hidden carries its registration seed");
            ClassRegistrationSeeds.ClassRegistration endpoint =
                lowered.classSeeds().registrationFor(ENDPOINT);
            check(endpoint != null && endpoint.layout().fields().stream()
                    .anyMatch(field -> new RuntimeDescriptor.Class(HOST_HIDDEN)
                        .equals(field.descriptor())),
                "the Endpoint registration carries the class-typed peer descriptor "
                    + "'" + HOST_HIDDEN.text() + "'");

            SemanticIrDumper.ProjectDump dump = SemanticIrDumper.dumpProject(lowered);
            List<byte[]> documents = documentsOf(dump);
            checkEq(ContractSnapshotCanonicalizer.parseProject(
                    objOf(text.getBytes(StandardCharsets.UTF_8))),
                SemanticIrDumper.reassembleProject(dump.manifest(), documents),
                "the class-reference manifest and its documents reassemble to the "
                    + "exact project");

            // The alternate external spelling renders the interface's declared
            // dotted text but names no carried registration: identity is the
            // exact carried registration, never the rendering.
            String alternate = "$external/host.cfg/Hidden";
            expectReassemblyFailure(manifestWithSeedFieldDescriptor(dump.manifest(),
                "Endpoint", "peer", "@" + alternate), documents,
                "a manifest seed field reference to an unregistered external "
                    + "spelling of the declared class rejects");
            expectProjectAdmissionFailure(SemanticIrValidator.validateText(
                    asText(manifestWithSeedFieldDescriptor(
                        text.getBytes(StandardCharsets.UTF_8), "Endpoint", "peer",
                        "@" + alternate)),
                    facts),
                ClassConstructionValidator.CONSTRUCTION_COHERENCE, "peer",
                "the text project surface rejects the unregistered external spelling "
                    + "of the declared class reference");
            expectProjectAdmissionFailure(SemanticIrValidator.validate(
                    new ExecutableLoweredProject(lowered.semanticProfile(),
                        lowered.interfaceIndex(), lowered.modules(),
                        lowered.entryModule(), withSeedFieldDescriptor(
                            lowered.classSeeds(), ENDPOINT, "peer",
                            new RuntimeDescriptor.Class(new ClassId(alternate,
                                "Hidden")))),
                    facts),
                ClassConstructionValidator.CONSTRUCTION_COHERENCE, "peer",
                "the typed project surface rejects the unregistered external spelling "
                    + "of the declared class reference");

            // A truncated function descriptor is a transport decode failure on
            // the reassembly and text admission surfaces alike.
            for (String truncated : List.of("(", "(number")) {
                expectReassemblyFailure(manifestWithSeedFieldDescriptor(dump.manifest(),
                    "Endpoint", "peer", truncated), documents,
                    "a truncated seed descriptor \"" + truncated + "\" rejects at "
                        + "reassembly as a transport decode failure");
                try {
                    SemanticIrValidator.validateText(asText(manifestWithSeedFieldDescriptor(
                        text.getBytes(StandardCharsets.UTF_8), "Endpoint", "peer",
                        truncated)), facts);
                    fail("a truncated seed descriptor \"" + truncated + "\" is a "
                        + "decode failure on the validation surface, never an "
                        + "unchecked parser failure");
                } catch (SemanticIrTextDecodeException expected) {
                    passed++;
                }
            }
        } finally {
            deleteRecursively(project.root());
        }
    }

    /** Imported implementation classes require interfaces, not declaration seeds. */
    private static void testSeedImplementationReferenceAndPrivateFieldRead() throws Exception {
        RealProject imported = compileProject("""
            import * as cfg from "host/cfg";
            export function main(): null { return null; }
            """, Map.of("src/util.deal", "export class Point { x: number; }",
                "src/cfg.d.deal", """
                    import * as u from "util";
                    export class Endpoint { point: u.Point; }
                    """), Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(imported, invocation());
            check(!result.hasErrors() && result.project() != null,
                "an imported implementation-class declaration field lowers: " + result.diagnostics());
            if (result.project() != null) {
                ExecutableLoweredProject project = result.project();
                check(project.classSeeds().registrationFor(POINT) == null,
                    "implementation Point does not acquire a declaration seed");
                SemanticIrValidator.ComparisonFacts facts = new SemanticIrValidator.ComparisonFacts(
                    imported.index().interfaceIndexDigest(), project.semanticProfile(),
                    invocation().capabilityRegistryHash());
                String text = assertProjectAdmission(project, facts,
                    "imported implementation-class field admits");
                assertReassembly(text, SemanticIrDumper.dumpProject(project),
                    "imported implementation-class declaration field reassembles exactly");
            }
        } finally { deleteRecursively(imported.root()); }

        for (boolean optional : List.of(false, true)) {
            RealProject hidden = compileProject("""
                import * as cfg from "host/cfg";
                export function main(): null {
                  let h = cfg.get();
                """ + (optional ? "let s: string | null = h.secret;" : "let s: string = h.secret;")
                    + " return null; }", Map.of("src/cfg.d.deal",
                        "class Hidden { secret" + (optional ? "?" : "")
                            + ": string; } export function get(): Hidden;"), Map.of());
            try {
                SemanticLowerer.ProjectLoweringResult result = lower(hidden, invocation());
                check(!result.hasErrors() && result.project() != null,
                    "private declaration field-read fixture lowers: " + result.diagnostics());
                if (result.project() == null) { continue; }
                ExecutableLoweredProject project = result.project();
                SemanticIrValidator.ComparisonFacts facts = new SemanticIrValidator.ComparisonFacts(
                    hidden.index().interfaceIndexDigest(), project.semanticProfile(),
                    invocation().capabilityRegistryHash());
                String text = assertProjectAdmission(project, facts,
                    "unchanged private declaration read admits, optional=" + optional);
                SemanticIrDumper.ProjectDump dump = SemanticIrDumper.dumpProject(project);
                assertReassembly(text, dump, "unchanged private declaration read reassembles");
                expectReassemblyFailure(manifestWithSeedFieldDescriptor(dump.manifest(),
                    "Hidden", "secret", "number"), documentsOf(dump),
                    "private seed/read disagreement rejects without CLASS_NEW");
                expectProjectAdmissionFailure(SemanticIrValidator.validateText(asText(
                    manifestWithSeedFieldDescriptor(text.getBytes(StandardCharsets.UTF_8),
                        "Hidden", "secret", "number")), facts),
                    ClassConstructionValidator.FIELD_OPERATION_SHAPE, "Hidden.secret",
                    "text admission rejects private seed/read disagreement");
                expectProjectAdmissionFailure(SemanticIrValidator.validate(
                    new ExecutableLoweredProject(project.semanticProfile(), project.interfaceIndex(),
                        project.modules(), project.entryModule(), withSeedFieldDescriptor(
                            project.classSeeds(), HOST_HIDDEN, "secret", RuntimeDescriptor.Number.INSTANCE)), facts),
                    ClassConstructionValidator.FIELD_OPERATION_SHAPE, "Hidden.secret",
                    "typed admission rejects private seed/read disagreement");
            } finally { deleteRecursively(hidden.root()); }
        }
    }

    /**
     * The class registration seeds with one field's descriptor replaced: the
     * mutated registration stays representable (one owner per class), so the
     * descriptor's exact declared-type rendering is the only disagreement.
     */
    private static ClassRegistrationSeeds withSeedFieldDescriptor(
            ClassRegistrationSeeds seeds, ClassId classId, String fieldName,
            RuntimeDescriptor descriptor) {
        Map<ClassId, ClassRegistrationSeeds.ClassRegistration> registrations =
            new LinkedHashMap<>(seeds.registrations());
        ClassRegistrationSeeds.ClassRegistration registration =
            registrations.get(classId);
        List<ClassLayout.FieldLayout> fields = new ArrayList<>();
        for (ClassLayout.FieldLayout field : registration.layout().fields()) {
            fields.add(field.name().equals(fieldName)
                ? new ClassLayout.FieldLayout(field.name(), descriptor, field.required(),
                    field.constructionProvider())
                : field);
        }
        registrations.put(classId, new ClassRegistrationSeeds.ClassRegistration(
            new ClassLayout(registration.layout().classId(), fields),
            registration.owner()));
        return new ClassRegistrationSeeds(registrations);
    }

    /** The canonical document's UTF-8 text (the admission surfaces consume text). */
    private static String asText(byte[] canonicalJson) {
        return new String(canonicalJson, StandardCharsets.UTF_8);
    }

    /**
     * Asserts that one project admission rejects with exactly the expected
     * first E6005 rule and detail: a rejected project exposes no project
     * record and starts no admission of a companion.
     */
    private static void expectProjectAdmissionFailure(
            Optional<CompilerDiagnostic> failure, String rule, String contains,
            String message) {
        check(failure.isPresent(), message + " (the admission returned a project)");
        if (failure.isEmpty()) {
            return;
        }
        CompilerDiagnostic diagnostic = failure.get();
        check("E6005".equals(diagnostic.code())
                && diagnostic.message().contains(rule)
                && diagnostic.message().contains(contains),
            message + ": expected E6005 " + rule + " naming \"" + contains
                + "\"; got " + diagnostic.message());
    }

    /**
     * The class registration seeds with one registration's owner member and
     * every field owner replaced: a registration fact stays one owner per
     * class, so the mutation is internally consistent and reaches the
     * seed/module join.
     */
    private static ClassRegistrationSeeds withSeedOwner(ClassRegistrationSeeds seeds,
            ClassId classId, ConstructionProvider owner) {
        Map<ClassId, ClassRegistrationSeeds.ClassRegistration> registrations =
            new LinkedHashMap<>(seeds.registrations());
        ClassRegistrationSeeds.ClassRegistration registration =
            registrations.get(classId);
        List<ClassLayout.FieldLayout> fields = new ArrayList<>();
        for (ClassLayout.FieldLayout field : registration.layout().fields()) {
            fields.add(new ClassLayout.FieldLayout(field.name(), field.descriptor(),
                field.required(), owner));
        }
        registrations.put(classId, new ClassRegistrationSeeds.ClassRegistration(
            new ClassLayout(registration.layout().classId(), fields), owner));
        return new ClassRegistrationSeeds(registrations);
    }

    /**
     * The interface index with one module entry's export list rewritten:
     * only a declared-type text changes, so the digest moves while every
     * module identity, kind and resolved import of the framing stays
     * structurally coherent (the carried-digest comparison is then the
     * first failure).
     */
    private static ProjectInterfaceIndex withIndexExportType(
            ProjectInterfaceIndex index, ModuleId moduleId, String declaredType) {
        return mutateIndexModule(index, moduleId, original -> {
            List<ExportInterface> exports = new ArrayList<>(original.exports());
            if (!exports.isEmpty()) {
                exports.set(0, new ExportInterface(exports.get(0).name(), declaredType));
            }
            return new ExternalModuleInterface(original.moduleId(), original.kind(),
                original.imports(), exports, original.classes(), original.initialization());
        });
    }

    private static ProjectInterfaceIndex mutateIndexModule(ProjectInterfaceIndex index,
            ModuleId moduleId, UnaryOperator<ExternalModuleInterface> mutation) {
        Map<ModuleId, ExternalModuleInterface> modules = new LinkedHashMap<>(index.modules());
        modules.put(moduleId, mutation.apply(modules.get(moduleId)));
        return new ProjectInterfaceIndex(ProjectInterfaceIndex.FORMAT_VERSION, modules);
    }

    /** The interface index without one module's entry. */
    private static ProjectInterfaceIndex withoutIndexModule(
            ProjectInterfaceIndex index, ModuleId moduleId) {
        Map<ModuleId, ExternalModuleInterface> modules = new LinkedHashMap<>(index.modules());
        modules.remove(moduleId);
        return new ProjectInterfaceIndex(ProjectInterfaceIndex.FORMAT_VERSION, modules);
    }

    /** The interface index with one module's named import reclassified. */
    private static ProjectInterfaceIndex withImportKind(ProjectInterfaceIndex index,
            ModuleId moduleId, String alias, ExternalModuleKind kind) {
        return mutateIndexModule(index, moduleId, original -> {
            List<ResolvedImport> imports = original.imports().stream()
                .map(importFact -> importFact.alias().equals(alias)
                    ? new ResolvedImport(importFact.alias(), importFact.modulePath(),
                        importFact.resolvedModuleId(), kind) : importFact).toList();
            return new ExternalModuleInterface(original.moduleId(), original.kind(),
                imports, original.exports(), original.classes(), original.initialization());
        });
    }

    /** The interface index with one module entry's kind reclassified. */
    private static ProjectInterfaceIndex withIndexKind(ProjectInterfaceIndex index,
            ModuleId moduleId, ExternalModuleKind kind) {
        return mutateIndexModule(index, moduleId, original -> new ExternalModuleInterface(
            original.moduleId(), kind, original.imports(), original.exports(),
            original.classes(), original.initialization()));
    }

    /**
     * Whether one complete raw unit carries a fact that requires the
     * complete project closure: a SHARED_BODY external function of another
     * module, a CALL externalEntryRef naming another module, or a
     * {@code CLASS_NEW} under an imported/declared default owner. The
     * standalone surfaces never admit such content; the complete project
     * surfaces resolve exactly these links from the supplied closure.
     */
    private static boolean requiresProviderContext(RawUnit unit) {
        for (RawBinding binding : unit.bindings()) {
            if ("externalFunction".equals(binding.shape())
                    && "SHARED_BODY".equals(binding.executionOwner())
                    && binding.modulePath() != null
                    && !unit.modulePath().equals(binding.modulePath())) {
                return true;
            }
        }
        for (RawOp op : unit.ops()) {
            if (SemanticOpKind.CALL.name().equals(op.kind())) {
                CanonicalJson.Value ref = payloadField(op.payload(), "externalEntryRef");
                if (ref instanceof CanonicalJson.Obj refObj) {
                    CanonicalJson.Value module = payloadField(refObj, "module");
                    if (module instanceof CanonicalJson.Obj moduleObj
                            && !unit.modulePath().equals(
                                modulePathOf(moduleObj))) {
                        return true;
                    }
                }
            }
            if (SemanticOpKind.CLASS_NEW.name().equals(op.kind())) {
                CanonicalJson.Value owner = payloadField(op.payload(), "constructionProvider");
                if (owner instanceof CanonicalJson.Str str
                        && (ConstructionProvider.SHARED_FACTORY.name().equals(str.value())
                            || ConstructionProvider.HOST_DECLARATION.name().equals(str.value())
                            || ConstructionProvider.BUILTIN_ERROR.name().equals(str.value()))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** One payload member of a raw operation payload, or null. */
    private static CanonicalJson.Value payloadField(CanonicalJson.Obj payload, String key) {
        return at(payload, key);
    }

    /** The module path of a canonical module-identity object, or null. */
    private static String modulePathOf(CanonicalJson.Obj moduleId) {
        return at(moduleId, "path") instanceof CanonicalJson.Str str ? str.value() : null;
    }

    // ---- manifest/document mutation helpers (checked reassembly negatives) ----

    private static CanonicalJson.Obj objOf(byte[] canonicalJson) {
        return (CanonicalJson.Obj) CanonicalJson.parse(canonicalJson);
    }

    private static CanonicalJson.Value at(CanonicalJson.Obj obj, String key) {
        for (CanonicalJson.Entry entry : obj.entries()) {
            if (entry.key().equals(key)) {
                return entry.value();
            }
        }
        return null;
    }

    private static CanonicalJson.Obj withEntry(CanonicalJson.Obj obj, String key,
            CanonicalJson.Value value) {
        List<CanonicalJson.Entry> entries = new ArrayList<>();
        for (CanonicalJson.Entry entry : obj.entries()) {
            entries.add(entry.key().equals(key) ? CanonicalJson.e(key, value) : entry);
        }
        return CanonicalJson.obj(entries);
    }

    /** The ordered module documents of a dump, as the reassembly consumes them. */
    private static List<byte[]> documentsOf(SemanticIrDumper.ProjectDump dump) {
        List<byte[]> documents = new ArrayList<>();
        for (SemanticIrDumper.ModuleDump module : dump.modules()) {
            documents.add(module.bytes());
        }
        return documents;
    }

    /** One module document whose carried module identity is renamed. */
    private static byte[] withModulePath(byte[] document, String modulePath) {
        return CanonicalJson.serializeBytes(withEntry(objOf(document), "moduleId",
            CanonicalJson.str(modulePath)));
    }

    /** The manifest with its ordered module-path list replaced. */
    private static byte[] manifestWithModulePaths(byte[] manifest, String... paths) {
        CanonicalJson.Obj root = objOf(manifest);
        List<CanonicalJson.Value> items = new ArrayList<>();
        for (String path : paths) {
            items.add(CanonicalJson.str(path));
        }
        return CanonicalJson.serializeBytes(
            withEntry(root, "modules", CanonicalJson.arr(items)));
    }

    /** The manifest with the first index module entry repeated (a duplicate identity). */
    private static byte[] manifestWithDuplicatedIndexModule(byte[] manifest) {
        return mutateJsonArray(manifest, entries -> entries.add(entries.get(0)),
            "interfaceIndex", "modules");
    }

    /** The manifest with its first class-seed row repeated (a duplicate registration key). */
    private static byte[] manifestWithDuplicatedSeed(byte[] manifest) {
        return mutateJsonArray(manifest, rows -> rows.add(rows.get(0)), "classSeeds");
    }

    /** The manifest with one module path removed from its ordered list. */
    private static byte[] manifestWithoutModule(byte[] manifest, String modulePath) {
        return mutateJsonArray(manifest,
            items -> items.removeIf(item -> ((CanonicalJson.Str) item).value().equals(modulePath)),
            "modules");
    }

    private static byte[] mutateJsonArray(byte[] document,
            Consumer<List<CanonicalJson.Value>> mutation, String... path) {
        return CanonicalJson.serializeBytes(mutateJsonPath(objOf(document), 0, path, value -> {
            List<CanonicalJson.Value> items = new ArrayList<>(((CanonicalJson.Arr) value).items());
            mutation.accept(items);
            return CanonicalJson.arr(items);
        }));
    }

    private static CanonicalJson.Value mutateJsonPath(CanonicalJson.Value value, int depth,
            String[] path, UnaryOperator<CanonicalJson.Value> mutation) {
        if (depth == path.length) {
            return mutation.apply(value);
        }
        CanonicalJson.Obj obj = (CanonicalJson.Obj) value;
        return withEntry(obj, path[depth], mutateJsonPath(at(obj, path[depth]),
            depth + 1, path, mutation));
    }

    /** The manifest with one module entry's first export declared type replaced. */
    private static byte[] manifestWithIndexExportType(byte[] manifest) {
        return mutateJsonArray(manifest, entries -> {
            CanonicalJson.Obj entry = (CanonicalJson.Obj) entries.get(0);
            List<CanonicalJson.Value> exports = new ArrayList<>(
                ((CanonicalJson.Arr) at(entry, "exports")).items());
            if (!exports.isEmpty()) {
                exports.set(0, withEntry((CanonicalJson.Obj) exports.get(0),
                    "declaredType", CanonicalJson.str("(i32) => i32")));
                entries.set(0, withEntry(entry, "exports", CanonicalJson.arr(exports)));
            }
        }, "interfaceIndex", "modules");
    }

    /** The manifest with the first index entry's module identity renamed. */
    private static byte[] manifestWithMutatedIndexModule(byte[] manifest) {
        return mutateJsonArray(manifest, entries -> {
            CanonicalJson.Obj first = (CanonicalJson.Obj) entries.get(0);
            CanonicalJson.Obj moduleId = (CanonicalJson.Obj) at(first, "moduleId");
            entries.set(0, withEntry(first, "moduleId", withEntry(moduleId, "path",
                CanonicalJson.str(((CanonicalJson.Str) at(moduleId, "path")).value() + "x"))));
        }, "interfaceIndex", "modules");
    }

    /** The manifest with the named class seed's owner member replaced. */
    private static byte[] manifestWithSeedOwner(byte[] manifest, String className,
            String owner) {
        return mutateSeed(manifest, className,
            row -> withEntry(row, "owner", CanonicalJson.str(owner)));
    }

    private static byte[] mutateSeed(byte[] manifest, String className,
            UnaryOperator<CanonicalJson.Obj> mutation) {
        return mutateJsonArray(manifest, rows -> {
            rows.replaceAll(item -> {
                CanonicalJson.Obj row = (CanonicalJson.Obj) item;
                CanonicalJson.Obj key = (CanonicalJson.Obj) at(row, "key");
                return className.equals(((CanonicalJson.Str) at(key, "name")).value())
                    ? mutation.apply(row) : row;
            });
            rows.removeIf(java.util.Objects::isNull);
        }, "classSeeds");
    }

    private static CanonicalJson.Obj mutateSeedFields(CanonicalJson.Obj row,
            UnaryOperator<CanonicalJson.Obj> mutation) {
        CanonicalJson.Obj layout = (CanonicalJson.Obj) at(row, "layout");
        CanonicalJson.Arr fields = (CanonicalJson.Arr) at(layout, "fields");
        List<CanonicalJson.Value> updated = new ArrayList<>();
        for (CanonicalJson.Value field : fields.items()) {
            updated.add(mutation.apply((CanonicalJson.Obj) field));
        }
        return withEntry(row, "layout", withEntry(layout, "fields", CanonicalJson.arr(updated)));
    }

    /**
     * The manifest/project with the named class seed's registration owner
     * member and every field owner replaced: the mutated registration stays
     * one owner per class (a representable registration), so the seed/index
     * rule cannot see the disagreement and only the module join can.
     */
    private static byte[] manifestWithSeedOwnerAndFieldOwners(byte[] manifest,
            String className, String owner) {
        return mutateSeed(manifest, className, row -> mutateSeedFields(
            withEntry(row, "owner", CanonicalJson.str(owner)),
            field -> withEntry(field, "constructionProvider", CanonicalJson.str(owner))));
    }

    /** The manifest with one seed field's canonical descriptor text replaced. */
    private static byte[] manifestWithSeedFieldDescriptor(byte[] manifest,
            String className, String fieldName, String descriptor) {
        return mutateSeed(manifest, className, row -> mutateSeedFields(row,
            field -> fieldName.equals(((CanonicalJson.Str) at(field, "name")).value())
                ? withEntry(field, "descriptor", CanonicalJson.str(descriptor)) : field));
    }

    /** The manifest with one seed's registration key renamed. */
    private static byte[] manifestWithSeedKey(byte[] manifest, String className,
            String newName) {
        return mutateSeed(manifest, className, row -> withEntry(row, "key",
            withEntry((CanonicalJson.Obj) at(row, "key"), "name", CanonicalJson.str(newName))));
    }

    /** The manifest with the named class's registration removed. */
    private static byte[] manifestWithoutSeed(byte[] manifest, String className) {
        return mutateSeed(manifest, className, row -> null);
    }

    /** The canonical document with one unknown framing member added. */
    private static byte[] withNewEntry(byte[] canonicalJson, String key,
            CanonicalJson.Value value) {
        CanonicalJson.Obj obj = objOf(canonicalJson);
        List<CanonicalJson.Entry> entries = new ArrayList<>(obj.entries());
        entries.add(CanonicalJson.e(key, value));
        return CanonicalJson.serializeBytes(CanonicalJson.obj(entries));
    }

    /** The canonical document terminated by exactly one LF (the file framing). */
    private static byte[] withLf(byte[] bytes) {
        byte[] result = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, result, 0, bytes.length);
        result[bytes.length] = '\n';
        return result;
    }

    /**
     * Asserts that the reassembly rejects a manifest/document pair as a
     * transport decode failure: the framing precedes admission, so a
     * rejected document set yields no project record and starts no
     * admission.
     */
    private static void expectReassemblyFailure(byte[] manifest, List<byte[]> documents,
            String message) {
        try {
            SemanticIrDumper.reassembleProject(manifest, documents);
            fail(message + " (the reassembly returned a project)");
        } catch (SemanticIrTextDecodeException expected) {
            passed++;
        } catch (RuntimeException other) {
            fail(message + " (expected SemanticIrTextDecodeException, got "
                + other.getClass().getSimpleName() + ": " + other.getMessage() + ")");
        }
    }

    /**
     * The combined common-admission dependency check (ISSUE-0819, T1-T4):
     * the real project is lowered once (T1), every dependency-ordered unit
     * and the project travel typed -> raw -> text -> raw (T2/T3), the
     * unchanged content admits on both surfaces, and one module's membership
     * mutation then rejects with the identical first E6005 on the typed unit,
     * the unit text, the typed project, the project text and the composed
     * production admission, while the dumped module text (T4) still shows the
     * mutation.
     */
    private static void testCommonAdmissionMembershipParity(LoweredProject baseline) {
        System.out.println("-- the common admission over the real transported project: "
            + "typed/text membership parity (ISSUE-0819) --");
        SemanticLowerer.ProjectLoweringResult result = baseline.result();
        ExecutableLoweredProject lowered = result.project();
        check(lowered != null, "the common-admission fixture lowers: "
            + result.diagnostics());
        if (lowered == null) {
            return;
        }
        SemanticIrValidator.ComparisonFacts facts = comparisonFacts(baseline.project());

        // T1/T2: the dependency-ordered units travel typed -> raw -> text -> raw
        // and the unchanged content admits on both surfaces.
        for (LoweredModuleUnit unit : lowered.modules().values()) {
            String text = SemanticIrValidator.toUnitText(unit);
            assertStandaloneAdmission(unit, text, assertUnitTransport(unit, text), facts);
        }
        // T3: the project transport keeps the complete project and admits on
        // both surfaces.
        assertProjectTransport(lowered, facts);

        // The membership mutation: the entry module loses one inverse row
        // while its block list keeps the op.
        LoweredModuleUnit targetUnit = lowered.modules().get(APP);
        check(targetUnit != null, "the entry module is in the closure");
        if (targetUnit == null) {
            return;
        }
        OpId last = null;
        for (OpId op : targetUnit.bodyTable().opBlocks().keySet()) {
            last = op;
        }
        final OpId removed = last;
        check(removed != null, "the entry module carries membership rows");
        if (removed == null) {
            return;
        }
        Map<OpId, BlockId> inverse = new LinkedHashMap<>(targetUnit.bodyTable().opBlocks());
        inverse.remove(removed);
        StructuredBodyTable mutatedTable = new StructuredBodyTable(
            targetUnit.bodyTable().blockOps(), inverse);
        LoweredModuleUnit mutated = mutateUnit(targetUnit, copy -> copy.body = mutatedTable);

        String mutatedText = SemanticIrValidator.toUnitText(mutated);
        RawUnit mutatedReparsed = ContractSnapshotCanonicalizer.parseUnit(
            (CanonicalJson.Obj) CanonicalJson.parse(mutatedText));
        checkEq(RawUnit.fromTyped(mutated), mutatedReparsed,
            "the membership mutation survives typed -> raw -> text -> raw unchanged");
        check(!mutatedReparsed.bodyTable().opBlocks().containsKey(removed)
                && mutatedReparsed.bodyTable().blockOps().values().stream()
                    .anyMatch(ids -> ids.contains(removed)),
            "the transported mutation keeps the block row and drops the inverse row");

        expectBindingGraphMutationRejects("a missing inverse membership row", lowered, facts,
            mutated, "CONTROL_BLOCK_TREE", "no inverse membership entry");

        // T4: the module dump still shows the mutation, and the unmutated dump
        // differs from it.
        String dumped = SemanticIrDumper.dumpModuleText(mutated);
        RawUnit dumpedUnit = ContractSnapshotCanonicalizer.parseUnit(
            (CanonicalJson.Obj) CanonicalJson.parse(dumped));
        check(!dumpedUnit.bodyTable().opBlocks().containsKey(removed)
                && dumpedUnit.bodyTable().blockOps().values().stream()
                    .anyMatch(ids -> ids.contains(removed)),
            "the dumped module text still shows the membership mutation");
        check(!dumped.equals(SemanticIrDumper.dumpModuleText(targetUnit)),
            "the dumped module text differs from the unmutated dump");
    }

    /**
     * The combined common-admission dependency check (ISSUE-0820, T1-T5):
     * the real project lowers once (T1) with every complete binding variant,
     * a module-level class with generated JSON defaults, an imported class
     * factory, an ordinary closure with two generation-pinned captures and
     * parameter/import initializations; every unit and the project travel
     * typed -> raw -> text -> raw (T2/T3) unchanged and admit on both
     * surfaces with the result views equal to the owned facts; then one-record
     * binding and class mutations reject identically on both surfaces with no
     * partial project, and remain visible in the transported text (T4/T5).
     */
    private static void testCommonAdmissionBindingAndClassParity() throws Exception {
        System.out.println("-- the common admission over the real transported project: "
            + "functions, captures, bindings and class construction parity (ISSUE-0820) --");
        RealProject project = compileProject(BINDING_CLASS_APP_SOURCE,
            Map.of("src/util.deal", BINDING_CLASS_UTIL_SOURCE,
                "src/cfg.d.deal", BINDING_CLASS_DECLARATION), Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(project, invocation());
            check(!result.hasErrors() && result.project() != null,
                "the binding/class fixture lowers: " + result.diagnostics());
            if (result.project() == null) {
                return;
            }
            ExecutableLoweredProject lowered = result.project();
            SemanticIrValidator.ComparisonFacts facts = comparisonFacts(project);
            LoweredModuleUnit util = lowered.modules().get(UTIL);
            LoweredModuleUnit app = lowered.modules().get(APP);
            check(util != null && app != null,
                "the closure carries the class module and the constructing entry module");
            if (util == null || app == null) {
                return;
            }

            // T1/T2: the dependency-ordered units travel typed -> raw -> text ->
            // raw and the unchanged content admits on both surfaces; the
            // transported graph carries every complete binding variant.
            Set<String> variants = new LinkedHashSet<>();
            LoweredFunction twoCapture = null;
            for (LoweredModuleUnit unit : lowered.modules().values()) {
                String transport = SemanticIrValidator.toUnitText(unit);
                assertStandaloneAdmission(unit, transport, assertUnitTransport(unit, transport), facts);
                for (FunctionExecutionBinding binding : unit.functionBindings().values()) {
                    variants.add(bindingName(binding));
                }
                for (LoweredFunction function : unit.functions().values()) {
                    if (function.captures().size() > 1) {
                        twoCapture = function;
                    }
                }
            }
            checkEq(Set.of("loweredBody", "adapter", "hostFunction", "hostFunctionValue",
                    "externalFunction", "dynamicFunctionValue"),
                variants, "the transported project carries every complete binding variant");
            check(twoCapture != null, "the fixture carries the two-capture ordinary closure");
            if (twoCapture == null) {
                return;
            }
            checkEq(closureCaptures(util, twoCapture.functionId()), twoCapture.captures(),
                "the closure payload and the function record carry the same ordered "
                    + "generation-pinned captures");
            check(util.classFactories().factories().size() == 1
                    && util.ops().stream().filter(op -> op.kind() == SemanticOpKind.JSON_FROM_CLASS).count() == 1,
                "the class module carries its factory registry and structural JSON operation");
            boolean sharedFactory = false;
            for (SemanticOp op : app.ops()) {
                if (op.payload() instanceof KindPayload.ClassNewPayload newPayload
                        && newPayload.constructionProvider() == ConstructionProvider.SHARED_FACTORY) {
                    sharedFactory = true;
                }
            }
            check(sharedFactory, "the imported class factory construction is transported");
            check(!util.classBindingFacts().parameters().isEmpty()
                    && !app.classBindingFacts().importAliases().isEmpty(),
                "the parameter and import-alias initialization facts are transported");

            // T3: the project transport keeps the complete project and admits
            // on both surfaces; the result views are the owned records.
            assertProjectTransport(lowered, facts);
            check(lowered.classSeeds() == result.seeds(),
                "result.seeds() is exactly project.classSeeds()");
            for (LoweredModuleUnit unit : lowered.modules().values()) {
                check(result.tableOf(unit.moduleId()) == unit.bodyTable()
                        && result.registryOf(unit.moduleId()) == unit.classFactories(),
                    unit.moduleId() + ": the result views are the unit's own records");
            }

            // Diagnostic-only metadata never becomes a rejection: a unit
            // text whose operation origin carries an absent source id, an
            // unusable position and an open kind still admits, and the
            // materialized admission does not throw.
            {
                String doctored = withFirstOpOrigin(SemanticIrValidator.toUnitText(util),
                    CanonicalJson.obj(
                        CanonicalJson.e("sourceId", CanonicalJson.nullValue()),
                        CanonicalJson.e("span", CanonicalJson.obj(
                            CanonicalJson.e("file", CanonicalJson.str("doctored.deal")),
                            CanonicalJson.e("startLine", CanonicalJson.intValue(0)),
                            CanonicalJson.e("startColumn", CanonicalJson.intValue(0)),
                            CanonicalJson.e("endLine", CanonicalJson.intValue(0)),
                            CanonicalJson.e("endColumn", CanonicalJson.intValue(0)),
                            CanonicalJson.e("startScalarOffset",
                                CanonicalJson.intValue(-1)),
                            CanonicalJson.e("endScalarOffset",
                                CanonicalJson.intValue(-1)))),
                        CanonicalJson.e("kind", CanonicalJson.str("NOT_A_KIND")),
                        CanonicalJson.e("anchorId", CanonicalJson.obj(
                            CanonicalJson.e("id", CanonicalJson.intValue(7)),
                            CanonicalJson.e("type", CanonicalJson.str("anchor")))),
                        CanonicalJson.e("parentOpId", CanonicalJson.nullValue())));
                Optional<CompilerDiagnostic> admitted;
                try {
                    admitted = SemanticIrValidator.validateText(doctored, facts);
                } catch (RuntimeException e) {
                    check(false, "a doctored diagnostic-only origin must not change "
                        + "admission or throw: " + e);
                    admitted = null;
                }
                check(admitted != null && admitted.isEmpty(),
                    "a doctored diagnostic-only origin leaves admission unchanged: "
                        + (admitted == null ? "threw"
                            : admitted.map(CompilerDiagnostic::message).orElse("admitted")));
            }

            // Fail closed: a decodable unit text whose payload loses a
            // mandatory recognized position passes the transport framing and
            // rejects as the closed-position E6005 of the materialized
            // admission, never as an unchecked failure.
            {
                String utilText = SemanticIrValidator.toUnitText(util);
                String dropped = withoutPayloadKey(utilText, "mutable");
                check(!dropped.equals(utilText),
                    "the dropped payload position is a real text mutation");
                Optional<CompilerDiagnostic> failure = null;
                try {
                    failure = SemanticIrValidator.validateText(dropped, facts);
                } catch (RuntimeException e) {
                    check(false, "a decodable dropped payload position must fail closed, "
                        + "never throw: " + e);
                }
                assertMutationRule("a dropped payload position", failure, "R-ENUM",
                    "payload position", "is absent");
            }

            List<BindingGeneration> captures = twoCapture.captures();
            BindingGeneration first = captures.get(0);
            List<BindingGeneration> changed = new ArrayList<>(captures);
            changed.set(0, new BindingGeneration(first.binding(), first.generation() + 1));
            List<BindingGeneration> duplicated = new ArrayList<>(captures);
            duplicated.add(first);
            record CaptureCase(String name, List<BindingGeneration> values,
                               boolean payloadToo, String detail) { }
            for (CaptureCase row : List.of(
                    new CaptureCase("a dropped capture", List.of(first), true, "outside the captures list"),
                    new CaptureCase("a duplicated capture binding", duplicated, true, "more than once"),
                    new CaptureCase("a changed capture generation", changed, false, "publishes captures"),
                    new CaptureCase("a reordered capture list",
                        List.of(captures.get(1), first), false, "publishes captures"),
                    new CaptureCase("a stale capture generation", changed, true, "names generation"))) {
                LoweredModuleUnit mutated = withFunction(util, twoCapture, row.values());
                if (row.payloadToo()) {
                    mutated = withClosureCaptures(mutated, twoCapture.functionId(), row.values());
                }
                expectBindingGraphMutationRejects(row.name(), lowered, facts,
                    mutated, "CAPTURE_RESOLUTION", row.detail());
            }
            // Mutation (5): break the function body link (the record's body
            // block differs from the closure's registered body).
            {
                BlockId otherBody = otherBlockOf(util, twoCapture.body());
                LoweredFunction function = twoCapture;
                LoweredModuleUnit mutated = mutateUnit(util, copy -> copy.functions.put(
                    function.functionId(), new LoweredFunction(function.functionId(),
                        function.descriptor(), function.captures(), otherBody, function.sourceName())));
                // The control owner runs before the bindings owner and owns the
                // detached RETURN whose recorded function no longer has that
                // body block.
                expectBindingGraphMutationRejects("a retargeted function body link",
                    lowered, facts, mutated, "CONTROL_EXIT", "names function");
            }
            // Mutation (6): retarget the loweredBody registration's block
            // (the registry entry alone).
            {
                FunctionAllocationIdentity key = allocationOf(
                    util, twoCapture.functionId());
                FunctionExecutionBinding.LoweredBody body =
                    (FunctionExecutionBinding.LoweredBody) util.functionBindings().get(key);
                LoweredModuleUnit mutated = mutateUnit(util, copy -> copy.bindings.put(key,
                    new FunctionExecutionBinding.LoweredBody(body.functionId(),
                        otherBlockOf(util, body.blockId()))));
                expectBindingGraphMutationRejects("a retargeted loweredBody block",
                    lowered, facts, mutated, "REGISTRY_ONE_TO_ONE",
                    "does not match its LoweredBody payload");
            }
            FunctionAllocationIdentity adapterId = adapterKey(app);
            FunctionExecutionBinding.AdapterBinding adapter =
                (FunctionExecutionBinding.AdapterBinding) app.functionBindings().get(adapterId);
            AdaptSourceRef.SharedCell shared = (AdaptSourceRef.SharedCell) adapter.sourceRef();
            record AdapterCase(String name, AdaptSourceRef source,
                               RuntimeDescriptor.Func target, String detail) { }
            for (AdapterCase row : List.of(
                    new AdapterCase("signature", adapter.sourceRef(),
                        new RuntimeDescriptor.Func(Collections.nCopies(3, RuntimeDescriptor.Number.INSTANCE),
                            RuntimeDescriptor.Number.INSTANCE), "registration carries signatures"),
                    new AdapterCase("sourceRef",
                        new AdaptSourceRef.SharedCell(shared.binding(), shared.generation() + 1),
                        adapter.targetSignature(), "registration carries mode"))) {
                LoweredModuleUnit mutated = mutateUnit(app, copy -> copy.bindings.put(adapterId,
                    new FunctionExecutionBinding.AdapterBinding(adapter.adaptOpId(),
                        adapter.captureMode(), row.source(), adapter.sourceSignature(), row.target())));
                expectBindingGraphMutationRejects("a retargeted adapter " + row.name(),
                    lowered, facts, mutated, "REGISTRY_ONE_TO_ONE", row.detail());
            }
            // Mutation (9): mis-resolve the host-function-value materializing
            // boundary op (the registry alone).
            {
                FunctionAllocationIdentity key = hostValueKey(app);
                FunctionExecutionBinding.HostFunctionValue value =
                    (FunctionExecutionBinding.HostFunctionValue)
                        app.functionBindings().get(key);
                OpId notACrossing = firstConstOp(app);
                check(notACrossing != null, "the fixture carries a non-crossing op");
                if (notACrossing != null) {
                    LoweredModuleUnit mutated = mutateUnit(app, copy -> copy.bindings.put(key,
                        new FunctionExecutionBinding.HostFunctionValue(
                            value.hostModuleId(), notACrossing, value.descriptor())));
                    expectBindingGraphMutationRejects(
                        "a mis-resolved materializing boundary op", lowered, facts,
                        mutated, "R-FUNCTION-BINDING", "does not resolve");
                }
            }
            BindingId local = null;
            for (BindingGeneration capture : captures) {
                if (!util.classBindingFacts().parameters().contains(capture.binding())
                        && !util.classBindingFacts().importAliases().contains(capture.binding())) {
                    local = capture.binding();
                }
            }
            check(local != null, "the fixture carries a captured local declaration cell");
            record ParameterCase(String name, BindingId binding, String detail) { }
            for (ParameterCase row : List.of(
                    new ParameterCase("a contradicted parameter initialization fact", local, "targets a parameter cell"),
                    new ParameterCase("a parameter fact naming no allocation", new BindingId(9001),
                        "names no producing allocation"))) {
                if (row.binding() != null) {
                    Set<BindingId> parameters = new LinkedHashSet<>(util.classBindingFacts().parameters());
                    parameters.add(row.binding());
                    LoweredModuleUnit mutated = mutateUnit(util, copy -> copy.bindingFacts =
                        new ClassBindingFacts(parameters, util.classBindingFacts().importAliases()));
                    expectBindingGraphMutationRejects(row.name(), lowered, facts, mutated,
                        "BINDING_INIT_ONCE", row.detail());
                }
            }
            // Mutation (11): retarget a factory registration to a non-factory
            // op (the class facts need the carried interface context, so the
            // project surfaces reject).
            {
                ClassFactoryId entry = util.classFactories().factories().keySet()
                    .iterator().next();
                OpId classDefault = null;
                for (SemanticOp op : util.ops()) {
                    if (op.kind() == SemanticOpKind.JSON_FROM_CLASS) {
                        classDefault = op.opId();
                    }
                }
                check(classDefault != null, "the fixture carries a non-factory JSON op");
                if (classDefault != null) {
                    Map<ClassFactoryId, OpId> factories = new LinkedHashMap<>(
                        util.classFactories().factories());
                    factories.put(entry, classDefault);
                    LoweredModuleUnit mutated = mutateUnit(util,
                        copy -> copy.factories = new ClassFactoryRegistry(factories));
                    expectClassGraphMutationRejects("a retargeted factory registration",
                        lowered, facts, mutated, "FACTORY_COHERENCE", "not CLASS_FACTORY");
                }
            }
            // Mutation (12a): reorder the CLASS_FACTORY payload's default
            // children with a recomputed snapshot digest: a recomputed digest
            // is no proof that the factory link is valid.
            {
                ClassFactoryId entry = util.classFactories().factories().keySet()
                    .iterator().next();
                OpId factoryOpId = util.classFactories().factoryFor(entry);
                SemanticOp factoryOp = opOf(util, factoryOpId);
                KindPayload.ClassFactoryPayload factoryPayload =
                    (KindPayload.ClassFactoryPayload) factoryOp.payload();
                LoweredModuleUnit mutated = replaceOpWith(util, factoryOp,
                    rebuildOp(factoryOp, new KindPayload.ClassFactoryPayload(
                        new ClassId(util.moduleId().path(), "Absent"), factoryPayload.callerOpRef())));
                SemanticOp rebuilt = opOf(mutated, factoryOpId);
                checkEq(ContractSnapshotCanonicalizer.digest(rebuilt.contract()),
                    rebuilt.contract().canonicalDigest(),
                    "the retargeted factory op carries its recomputed snapshot digest");
                expectClassGraphMutationRejects("a factory names an undeclared class",
                    lowered, facts, mutated, "FACTORY_COHERENCE", "classId");
            }
            // Mutation (13): make the carried constructionEntry disagree with
            // the registry (the class facts).
            {
                ClassFactoryId entry = util.classFactories().factories().keySet()
                    .iterator().next();
                Map<ClassFactoryId, OpId> factories = new LinkedHashMap<>(
                    util.classFactories().factories());
                OpId factoryOp = factories.remove(entry);
                factories.put(new ClassFactoryId(entry.id() + 100), factoryOp);
                LoweredModuleUnit mutated = mutateUnit(util,
                    copy -> copy.factories = new ClassFactoryRegistry(factories));
                expectClassGraphMutationRejects(
                    "a constructionEntry that disagrees with the registry", lowered, facts,
                    mutated, "FACTORY_COHERENCE",
                    "no factory registered under constructionEntry");
            }
            // Mutation (14): the builtin Error registration contradicts the
            // 0787-owned compiler descriptor/layout contract (a project-level
            // carried record: no equivalent unit mutation exists).
            {
                Map<ClassId, ClassRegistrationSeeds.ClassRegistration> registrations =
                    new LinkedHashMap<>(lowered.classSeeds().registrations());
                registrations.put(ClassId.ERROR,
                    new ClassRegistrationSeeds.ClassRegistration(
                        new ClassLayout(ClassId.ERROR, List.of(
                            new ClassLayout.FieldLayout("code",
                                RuntimeDescriptor.String.INSTANCE, true,
                                ConstructionProvider.BUILTIN_ERROR))),
                        ConstructionProvider.BUILTIN_ERROR));
                ExecutableLoweredProject mutatedProject = new ExecutableLoweredProject(
                    lowered.semanticProfile(), lowered.interfaceIndex(),
                    lowered.modules(), lowered.entryModule(),
                    new ClassRegistrationSeeds(registrations));
                Optional<CompilerDiagnostic> typedProject =
                    SemanticIrValidator.validate(mutatedProject, facts);
                Optional<CompilerDiagnostic> textProject = SemanticIrValidator.validateText(
                    SemanticIrValidator.toProjectText(mutatedProject), facts);
                assertMutationRule("the builtin Error contract (typed project)",
                    typedProject, "CONSTRUCTION_COHERENCE", "compiler-owned");
                assertMutationRule("the builtin Error contract (project text)",
                    textProject, "CONSTRUCTION_COHERENCE", "compiler-owned");
                if (typedProject.isPresent() && textProject.isPresent()) {
                    checkEq(typedProject.get().message(), textProject.get().message(),
                        "the project surfaces report the identical builtin Error failure");
                }
            }
        } finally {
            deleteRecursively(project.root());
        }
    }

    /** The unit text with its first operation's origin replaced. */
    private static String withFirstOpOrigin(String unitText, CanonicalJson.Obj origin) {
        return asText(mutateJsonArray(unitText.getBytes(StandardCharsets.UTF_8),
            items -> items.set(0, withEntry((CanonicalJson.Obj) items.get(0), "origin", origin)),
            "ops"));
    }

    /** The unit rebuilt with one operation record replaced. */
    private static LoweredModuleUnit replaceOpWith(LoweredModuleUnit unit,
            SemanticOp target, SemanticOp replacement) {
        List<SemanticOp> ops = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            ops.add(op == target ? replacement : op);
        }
        return withOps(unit, ops);
    }

    /** The unit text with one payload member removed from its BINDING_ALLOC op. */
    private static String withoutPayloadKey(String unitText, String key) {
        return asText(mutateJsonArray(unitText.getBytes(StandardCharsets.UTF_8), items -> {
            for (int i = 0; i < items.size(); i++) {
                CanonicalJson.Obj op = (CanonicalJson.Obj) items.get(i);
                if ("BINDING_ALLOC".equals(((CanonicalJson.Str) at(op, "kind")).value())) {
                    CanonicalJson.Obj payload = (CanonicalJson.Obj) at(op, "payload");
                    List<CanonicalJson.Entry> entries = new ArrayList<>(payload.entries());
                    entries.removeIf(entry -> entry.key().equals(key));
                    items.set(i, withEntry(op, "payload", CanonicalJson.obj(entries)));
                    break;
                }
            }
        }, "ops"));
    }

    /** The binding variant name of one complete registration. */
    private static String bindingName(FunctionExecutionBinding binding) {
        return switch (binding) {
            case FunctionExecutionBinding.LoweredBody ignored -> "loweredBody";
            case FunctionExecutionBinding.AdapterBinding ignored -> "adapter";
            case FunctionExecutionBinding.HostFunction ignored -> "hostFunction";
            case FunctionExecutionBinding.HostFunctionValue ignored -> "hostFunctionValue";
            case FunctionExecutionBinding.ExternalFunction ignored -> "externalFunction";
            case FunctionExecutionBinding.DynamicFunctionValue ignored -> "dynamicFunctionValue";
        };
    }

    /** The closure payload's captures for one function id. */
    private static List<BindingGeneration> closureCaptures(LoweredModuleUnit unit,
            FunctionId functionId) {
        for (SemanticOp op : unit.ops()) {
            if (op.payload() instanceof KindPayload.ClosureNewPayload closure
                    && closure.function().equals(functionId)) {
                return closure.captures();
            }
        }
        return null;
    }

    /** The unit rebuilt with one function record replaced. */
    private static LoweredModuleUnit withFunction(LoweredModuleUnit unit,
            LoweredFunction function, List<BindingGeneration> captures) {
        return mutateUnit(unit, copy -> copy.functions.put(function.functionId(),
            new LoweredFunction(function.functionId(), function.descriptor(), captures,
                function.body(), function.sourceName())));
    }

    /** The unit rebuilt with one closure payload's captures replaced. */
    private static LoweredModuleUnit withClosureCaptures(LoweredModuleUnit unit,
            FunctionId functionId, List<BindingGeneration> captures) {
        List<SemanticOp> ops = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            if (op.payload() instanceof KindPayload.ClosureNewPayload closure
                    && closure.function().equals(functionId)) {
                ops.add(rebuildOp(op, new KindPayload.ClosureNewPayload(closure.function(),
                    closure.signature(), captures, closure.binding())));
            } else {
                ops.add(op);
            }
        }
        return withOps(unit, ops);
    }

    /** The same op with another payload and its recomputed contract. */
    private static SemanticOp rebuildOp(SemanticOp op, KindPayload payload) {
        OperationContractSnapshot draft = new OperationContractSnapshot(
            OperationContractSnapshot.VERSION, op.kind(), op.resultType(),
            op.operandTypes(), payload instanceof KindPayload.SelectorCarrying carrying
                ? carrying.selector() : null,
            payload, op.failurePolicy(), op.contract().referencedSemanticIds(), "draft");
        OperationContractSnapshot contract = new OperationContractSnapshot(
            OperationContractSnapshot.VERSION, op.kind(), op.resultType(),
            op.operandTypes(), draft.selector(), payload, op.failurePolicy(),
            op.contract().referencedSemanticIds(),
            ContractSnapshotCanonicalizer.digest(draft));
        return new SemanticOp(op.opId(), op.kind(), op.origin(), op.result(),
            op.resultType(), op.operands(), op.operandTypes(), payload, op.failurePolicy(),
            contract);
    }

    private static final class UnitMutation {
        final Map<FunctionId, LoweredFunction> functions;
        final Map<FunctionAllocationIdentity, FunctionExecutionBinding> bindings;
        List<SemanticOp> ops;
        StructuredBodyTable body;
        ClassFactoryRegistry factories;
        ClassBindingFacts bindingFacts;

        UnitMutation(LoweredModuleUnit unit) {
            functions = new LinkedHashMap<>(unit.functions());
            bindings = new LinkedHashMap<>(unit.functionBindings());
            ops = new ArrayList<>(unit.ops());
            body = unit.bodyTable();
            factories = unit.classFactories();
            bindingFacts = unit.classBindingFacts();
        }
    }

    private static LoweredModuleUnit mutateUnit(LoweredModuleUnit unit,
            Consumer<UnitMutation> mutation) {
        UnitMutation copy = new UnitMutation(unit);
        mutation.accept(copy);
        return new LoweredModuleUnit(unit.formatVersion(), unit.semanticProfile(),
            unit.moduleId(), unit.interfaceHash(), unit.loweringContextHash(),
            unit.requiredCapabilities(), unit.constructCoverage(), unit.classLayouts(),
            copy.functions, unit.moduleInit(), unit.exportPlan(), copy.bindings,
            copy.ops, copy.body, copy.factories, copy.bindingFacts);
    }

    /** Another block of the unit's membership table. */
    private static BlockId otherBlockOf(LoweredModuleUnit unit, BlockId block) {
        for (BlockId candidate : unit.bodyTable().blockOps().keySet()) {
            if (!candidate.equals(block)) {
                return candidate;
            }
        }
        return block;
    }

    /** The allocation identity of one registered function id. */
    private static FunctionAllocationIdentity allocationOf(LoweredModuleUnit unit,
            FunctionId functionId) {
        for (Map.Entry<FunctionAllocationIdentity, FunctionExecutionBinding> entry
                : unit.functionBindings().entrySet()) {
            if (entry.getValue() instanceof FunctionExecutionBinding.LoweredBody body
                    && body.functionId().equals(functionId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** The adapter registration's allocation identity. */
    private static FunctionAllocationIdentity adapterKey(LoweredModuleUnit unit) {
        for (Map.Entry<FunctionAllocationIdentity, FunctionExecutionBinding> entry
                : unit.functionBindings().entrySet()) {
            if (entry.getValue() instanceof FunctionExecutionBinding.AdapterBinding) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** The host-function-value registration's allocation identity. */
    private static FunctionAllocationIdentity hostValueKey(LoweredModuleUnit unit) {
        for (Map.Entry<FunctionAllocationIdentity, FunctionExecutionBinding> entry
                : unit.functionBindings().entrySet()) {
            if (entry.getValue() instanceof FunctionExecutionBinding.HostFunctionValue) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** The first CONST op's id. */
    private static OpId firstConstOp(LoweredModuleUnit unit) {
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.CONST) {
                return op.opId();
            }
        }
        return null;
    }

    /**
     * One binding mutation: the mutation survives typed -> raw -> text -> raw,
     * the typed and text unit admissions, the typed and text project
     * admissions and the composed production admission all report the same
     * first E6005 (rule, message and range), and the mutation stays visible in
     * the transported text; no project or partial companion is exposed.
     */
    private static void expectBindingGraphMutationRejects(String what,
            ExecutableLoweredProject lowered, SemanticIrValidator.ComparisonFacts facts,
            LoweredModuleUnit mutated, String rule, String... contains) {
        expectGraphMutationRejects(what, lowered, facts, mutated, false, rule, contains);
    }

    /**
     * One class-fact mutation: a standalone unit carries no provider context,
     * so its applicable predicates admit; the typed and text project
     * admissions and the composed production admission reject with the same
     * first E6005, and the mutation stays visible in the transported text.
     */
    private static void expectClassGraphMutationRejects(String what,
            ExecutableLoweredProject lowered, SemanticIrValidator.ComparisonFacts facts,
            LoweredModuleUnit mutated, String rule, String... contains) {
        expectGraphMutationRejects(what, lowered, facts, mutated, true, rule, contains);
    }

    private static void expectGraphMutationRejects(String what,
            ExecutableLoweredProject lowered, SemanticIrValidator.ComparisonFacts facts,
            LoweredModuleUnit mutated, boolean standaloneAdmits, String rule, String... contains) {
        String text = SemanticIrValidator.toUnitText(mutated);
        RawUnit reparsed = assertUnitTransport(mutated, text);
        check(!text.equals(SemanticIrValidator.toUnitText(
                lowered.modules().get(mutated.moduleId()))),
            what + ": mutation stays visible in transported text");
        for (RawOp op : reparsed.ops()) {
            checkEq(op.canonicalDigest(),
                ContractSnapshotCanonicalizer.recomputeSnapshotDigest(op.snapshot()),
                what + ": op " + op.opId() + " keeps a valid contract digest");
        }
        Optional<CompilerDiagnostic> typedUnit = SemanticIrValidator.validate(mutated, facts);
        Optional<CompilerDiagnostic> textUnit = SemanticIrValidator.validateText(text, facts);
        Optional<CompilerDiagnostic> typedProject = projectAdmission(lowered, mutated, facts);
        Optional<CompilerDiagnostic> textProject = SemanticIrValidator.validateText(
            SemanticIrValidator.toProjectText(mutatedProject(lowered, mutated)), facts);
        if (standaloneAdmits) {
            check(typedUnit.isEmpty(), what + ": standalone typed predicates pass without provider context");
            check(textUnit.isEmpty(), what + ": standalone text predicates pass without provider context");
        } else {
            assertMutationRule(what + " (typed unit)", typedUnit, rule, contains);
            assertMutationRule(what + " (unit text)", textUnit, rule, contains);
            assertSameFailure(what + " unit surfaces", typedUnit, textUnit);
            assertSameFailure(what + " unit/project surfaces", typedUnit, typedProject);
        }
        assertMutationRule(what + " (typed project)", typedProject, rule, contains);
        assertMutationRule(what + " (project text)", textProject, rule, contains);
        assertSameFailure(what + " project surfaces", typedProject, textProject);
        Optional<CompilerDiagnostic> composed = SemanticLowerer.validateProjectUnit(mutated,
            mutated.bodyTable(), facts, mutated.classBindingFacts(), mutated.classFactories(),
            lowered.interfaceIndex().modules().get(mutated.moduleId()),
            Map.of(), lowered.classSeeds().registrations());
        assertSameFailure(what + " composed production admission", typedProject, composed);
    }

    private static void assertSameFailure(String what, Optional<CompilerDiagnostic> expected,
            Optional<CompilerDiagnostic> actual) {
        check(expected.isPresent() && actual.isPresent(), what + ": both reject");
        if (expected.isPresent() && actual.isPresent()) {
            checkEq(expected.get().code(), actual.get().code(), what + ": same code");
            checkEq(expected.get().message(), actual.get().message(), what + ": identical first failure");
            checkEq(expected.get().range(), actual.get().range(), what + ": identical origin range");
        }
    }

    private static ExecutableLoweredProject mutatedProject(ExecutableLoweredProject lowered,
            LoweredModuleUnit mutated) {
        Map<ModuleId, LoweredModuleUnit> modules = new LinkedHashMap<>(lowered.modules());
        modules.put(mutated.moduleId(), mutated);
        return new ExecutableLoweredProject(lowered.semanticProfile(),
            lowered.interfaceIndex(), modules, lowered.entryModule(), lowered.classSeeds());
    }

    private static Optional<CompilerDiagnostic> projectAdmission(
            ExecutableLoweredProject lowered, LoweredModuleUnit mutated,
            SemanticIrValidator.ComparisonFacts facts) {
        return SemanticIrValidator.validate(mutatedProject(lowered, mutated), facts);
    }

    private static void assertMutationRule(String what,
            Optional<CompilerDiagnostic> failure, String rule, String... contains) {
        check(failure.isPresent(), what + " is rejected with E6005");
        if (failure.isEmpty()) {
            return;
        }
        CompilerDiagnostic diagnostic = failure.get();
        check("E6005".equals(diagnostic.code()),
            what + " carries the canonical E6005 code");
        check(diagnostic.message().contains(rule)
                && diagnostic.message().contains("validatorRule " + rule + ","),
            what + " is named as the validator rule " + rule + "; got "
                + diagnostic.message());
        for (String fragment : contains) {
            check(diagnostic.message().contains(fragment),
                what + " names \"" + fragment + "\"; got " + diagnostic.message());
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Project Lowering Entry Tests (ISSUE-0634) ===\n");
        RealProject project = null;
        RealProject importedProject = null;
        try {
            project = compileProject(APP_SOURCE);
            LoweredProject baseline = new LoweredProject(project,
                lower(project, invocation()));
            testOneProjectLoweringAndDeterminism(baseline);
            testSeedsIntrinsicsAndNamespaces(baseline);
            testUnitProjectCompanionOwnership();
            testNamespaceRegistrationFailureOrigins(baseline);
            testComposedChainOverUnifiedUnits(baseline);
            testCompleteProjectTransportAndDumpReassembly(baseline);
            testCommonAdmissionMembershipParity(baseline);
            testCommonAdmissionBindingAndClassParity();
            testInProjectClassArm();
            testDefaultReentrantAttemptScratch();
            importedProject = compileProject(IMPORTED_CLASS_APP_SOURCE,
                Map.of("src/util.deal", IMPORTED_CLASS_UTIL_SOURCE), Map.of());
            LoweredProject imported = new LoweredProject(importedProject,
                lower(importedProject, invocation()));
            testImportedInProjectClassResolution(imported);
            testStandaloneProviderContextAndExactViews(imported, baseline);
            testInconsistentFactSeed(imported);
            testDeclarationClassConstruction();
            testBuiltinErrorConstruction();

            testProjectSeedNominalClassReference();
            testSeedImplementationReferenceAndPrivateFieldRead();
            testSameSpelledProviderResolution();
            testSameNameSiblingProviderResolution();
            testSameIdentityProviderCrossingRejected();
            testUnrepresentableDefiningFileNameRejected();
            testNestedFieldProviderBesideSameIdentityLocal();
            testTransitiveProviderLiteralBesideSameIdentitySibling();
            testSameIdentityFieldOperationResolution();
            testExactIdentityFieldOperationNeedsNoDeclaration();
            testJsonNestedLinkResolvesByExactIdentity();
            testSinglePlanJsonNestedLinkResolves();
            testPlanImportedBesideNonPlanLocal();
            testLocalDeclarationOwnsLiteralBesideImportedSiblings();
            testImportedContextBesideCalleeArgumentProvider();
            testCalleeParameterAndReturnProviderBesideSameIdentityLocal();
            testFunctionTypedCalleeParameterProviderBesideSameIdentityLocal();
            testCallReceiverProviderBesideSameIdentityLocal();
            testImportedFieldWriteProviderDeferral();
            testCorruptedUnitSeed(baseline);
            testMissingModuleSeed(baseline);
            testUnresolvedAliasSeed(baseline);
        } finally {
            deleteRecursively(importedProject == null ? null : importedProject.root());
            deleteRecursively(project == null ? null : project.root());
        }
        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
