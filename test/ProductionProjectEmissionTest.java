package deal.test;

import deal.ast.ClassDeclaration;
import deal.ast.ExportDeclaration;
import deal.ast.ForInit;
import deal.ast.ForStatement;
import deal.ast.FunctionDeclaration;
import deal.ast.StatementNode;
import deal.ast.VariableDeclaration;
import deal.checker.BuiltinErrorDeclaration;
import deal.checker.CheckResult;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.codegen.Backend;
import deal.codegen.SharedEmissionFailure;
import deal.codegen.SourceMapPaths;
import deal.codegen.SourceMapRecorder;
import deal.codegen.js.JsEmissionInput;
import deal.codegen.js.JsSemanticEmitter;
import deal.codegen.js.JsSourceFile;
import deal.codegen.js.RuntimeDeclarationProjection;
import deal.codegen.js.RuntimeDeclarationSurface;
import deal.codegen.jvm.JvmSemanticEmitter;
import deal.codegen.lua.FfiEmissionInput;
import deal.codegen.lua.LuaSemanticEmitter;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.distribution.DistributionHome;
import deal.ffi.FfiForwardBindings;
import deal.ffi.FfiGeneratedModule;
import deal.ffi.FfiImportedFunctionReference;
import deal.identity.CanonicalModuleIdentity;
import deal.module.CompilationOrchestrator;
import deal.module.ProductionProjectEmission;
import deal.publication.PublicationStager;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CheckedModuleInput;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CheckedProjectInput;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ContainerPayloadDescriptors;
import deal.semantic.HostDeclarationSurface;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticRequirementManifest;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.AnchorId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.ClassFactoryId;
import deal.semantic.ir.DefaultOwner;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.ExternalAsyncLink;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ModuleImportKind;
import deal.semantic.ir.OpId;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;
import deal.semantic.ir.ReleaseState;
import deal.test.conformance.JvmCompilation;
import deal.types.Type;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ProductionProjectEmissionTest {

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

    // =========================================================================
    // Fixtures (compiled for their checked-project facts; the phase-4 arm
    // is not the fixture compile's subject, because this unit drives
    // ProductionProjectEmission directly)
    // =========================================================================

    static final String SHADOWING_SOURCE = """
        export function main(): null {
          let x: int = 3;
          {
            let x: int = x + 1;
            if (x !== 4) { throw { code: "SHADOW_FAIL", message: "shadowing initializer" }; }
          }
          if (x !== 3) { throw { code: "SHADOW_FAIL", message: "block outer restored" }; }
          if (true) {
            let x: int = 9;
            if (x !== 9) { throw { code: "SHADOW_FAIL", message: "if-body shadow" }; }
          }
          if (x !== 3) { throw { code: "SHADOW_FAIL", message: "if-body outer restored" }; }
          while (false) {
            let x: int = 11;
            if (x !== 11) { throw { code: "SHADOW_FAIL", message: "loop-body shadow" }; }
          }
          if (x !== 3) { throw { code: "SHADOW_FAIL", message: "loop-body outer restored" }; }
          let sum: int = 0;
          let values: int[] = [1, 2];
          for (let v: int of values) {
            let before: int = v;
            let v: int = 10;
            sum = sum + before + v;
          }
          if (sum !== 23) { throw { code: "SHADOW_FAIL", message: "for-of shadow" }; }
          let h: int = 3;
          let hsum: int = 0;
          for (let h: int = h + 1; hsum < 9;) { hsum = hsum + h; }
          if (hsum !== 12) { throw { code: "SHADOW_FAIL", message: "for-head initializer" }; }
          if (h !== 3) { throw { code: "SHADOW_FAIL", message: "for-head outer restored" }; }
          let nl: string | null = "ab";
          if (nl !== null) {
            for (let nl: int = 0; false;) {}
            let nlText: string = nl;
            if (nlText !== "ab") { throw { code: "SHADOW_FAIL", message: "for-head shadow restored" }; }
          }
          let nb: string | null = "cd";
          if (nb !== null) {
            { let nb: int = 0; if (true) {} else {} }
            let nbText: string = nb;
            if (nbText !== "cd") { throw { code: "SHADOW_FAIL", message: "branch shadow restored" }; }
          }
          try {
            throw { code: "SHADOW_X", message: "catch shadow" };
          } catch (e) {
            let e: Error = e;
            if (e.code !== "SHADOW_X") { throw { code: "SHADOW_FAIL", message: "catch shadow" }; }
          }
          return null;
        }
        """;

    static final String DYNAMIC_TABLE_SOURCE = """
        export function main(): null {
          let t: table = { x: 7 };
          let k: string = "x";
          let n: int = t[k];
          if (n !== 7) { throw { code: "DYN_FAIL", message: "variable key" }; }
          let u: table = {};
          let mk: () => string = function(): string { return "y"; };
          u[mk()] = 9;
          let m: int = u[mk()];
          if (m !== 9) { throw { code: "DYN_FAIL", message: "effectful key" }; }
          let q: table = {};
          let miss: int | null = q[k];
          if (miss !== null) { throw { code: "DYN_FAIL", message: "missing" }; }
          let v: table = { s: 1 };
          let nn: int | null = v["s"];
          let got: int = 0;
          if (nn !== null) { got = nn; }
          if (got !== 1) { throw { code: "DYN_FAIL", message: "nullable" }; }
          let order: int = 0;
          let receiver: () => table = function(): table { order = order * 10 + 1; return u; };
          let key: () => string = function(): string { order = order * 10 + 2; return "y"; };
          let value: () => int = function(): int { order = order * 10 + 3; return 11; };
          receiver()[key()] = value();
          if (order !== 123) { throw { code: "DYN_FAIL", message: "write evaluation order" }; }
          order = 0;
          let ordered: int = receiver()[key()];
          if (order !== 12 || ordered !== 11) { throw { code: "DYN_FAIL", message: "read evaluation order" }; }
          return null;
        }
        """;

    private static final ModuleId LIB = new ModuleId("lib");
    private static final ModuleId HOST_CFG = new ModuleId("host.cfg");
    private static final String HOST_CFG_SPECIFIER = "host/cfg";

    /** The extern-C declaration module of the admission probe. */
    private static final ModuleId NATIVE_MATH = new ModuleId("native.math");
    private static final String EXTERN_C_SPECIFIER = "native/math";

    private static final String LIB_SOURCE = """
        export function add(a: int, b: int): int {
          return a + b;
        }

        export function twice(x: int): int {
          return x * 2;
        }
        """;

    /** The covered two-module fixture: a COMPILED import, no call. */
    private static final String APP_SOURCE = """
        import * as lib from "./lib";

        export function main(): null {
          return null;
        }

        export function label(): string {
          return "app";
        }
        """;

    /** The one-main probe: the entry main publishes one effect byte line. */
    private static final String PROBE_APP_SOURCE = """
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
     * The two STDLIB imports of the catalog-miss probe: their distinct
     * spans prove the guard carries the span of the import that recorded
     * the failing catalog entry, never another matching import's.
     */
    private static final String TWO_STDLIB_APP_SOURCE = """
        import * as console from "std/console";
        import * as str from "std/string";

        export function main(): null {
          console.log("PROBE|STDLIB-IMPORT");
          return null;
        }
        """;

    /**
     * The declaration-origin fixture: one plain function {@code f} whose
     * checked signature the declaration-origin probe corrupts, plus the
     * production entry.
     */
    private static final String DECLARATION_ORIGIN_APP_SOURCE = """
        export function main(): null {
          return null;
        }

        function f(): int {
          return 41;
        }
        """;

    /**
     * The generated-helper-origin fixture: one {@code @jsonable} class
     * whose synthetic {@code Widget$fromJson}/{@code Widget$toJson}
     * checked signatures the declaration-origin probe corrupts, plus the
     * production entry.
     */
    private static final String GENERATED_HELPER_APP_SOURCE = """
        // @jsonable
        export class Widget { n: int = 1; }
        export function main(): null { return null; }
        """;

    /**
     * The for-let counter-origin fixture: the loop counter's declared
     * type annotation is the declaration-owned descriptor subject the
     * counter-origin probe corrupts, plus the production entry.
     */
    private static final String FOR_LET_COUNTER_APP_SOURCE = """
        export function main(): null {
          for (let i: int = 0; i < 2; i = i + 1) { }
          return null;
        }
        """;

    /**
     * The bytes-bearing fixture: the lowering fails CONSTRUCT_UNLOWERED.
     */
    private static final String BYTES_APP_SOURCE = """
        export function main(): null {
          let n: int = 3;
          let b: bytes = bytes(n);
          return null;
        }
        """;

    /** The cross-module sync call: the realized external-call arm. */
    private static final String SYNC_CALL_APP_SOURCE = """
        import * as lib from "./lib";

        export function main(): null {
          let x: int = lib.add(1, 2);
          return null;
        }
        """;

    /** The HOST declaration: two function exports in declaration order. */
    private static final String HOST_DECLARATION = """
        export function version(): int;
        export function describe(s: string): string;
        """;

    /** The host-import fixture: the import is never called or constructed. */
    private static final String HOST_APP_SOURCE = """
        import * as cfg from "host/cfg";

        export function main(): null {
          return null;
        }
        """;

    /**
     * The exported class with one field default: the CLASS_DEFAULT op the
     * rejected-op emission boundaries identify.
     */
    private static final String CLASS_DEFAULT_APP_SOURCE = """
        export function main(): null {
          return null;
        }

        export class Point {
          x: int = 42;
        }
        """;

    /** The extern-C declaration: the narrowed guard's remaining shape. */
    private static final String EXTERN_C_DECLARATION = """
        // @extern-c
        export function nativeAdd(a: int, b: int): int;
        """;

    /** The extern-C import fixture: the import is never called. */
    private static final String EXTERN_C_APP_SOURCE = """
        import * as native from "native/math";

        export function main(): null {
            return null;
        }
        """;

    /** The async library of the cross-module async fixture. */
    private static final String ASYNC_LIB_SOURCE = """
        export async function getValue(): int {
          return 42;
        }
        """;

    /**
     * The cross-module async call: the exported worker really executes the
     * caller's alias-token linkage (the callee is a non-entry module).
     */
    private static final String CROSS_ASYNC_APP_SOURCE = """
        import * as lib from "./lib";

        export function main(): null {
          return null;
        }

        export async function worker(): int {
          return await lib.getValue();
        }
        """;

    /**
     * The same-module async call: accepted, emitted, and executable. The
     * awaiting call sits in an exported zero-arity async function so the
     * runner can invoke it through the artifact's published surface and
     * really execute the ASYNC_START(DEAL_BODY)/AWAIT path.
     */
    private static final String SAME_ASYNC_APP_SOURCE = """
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

    private record Fixture(
        Path root,
        CheckedProjectInput checkedProject,
        ProjectInterfaceIndex index,
        List<SemanticRequirementManifest> manifests,
        HostDeclarationSurface surface,
        Map<ModuleId, FfiGeneratedModule> externCModules,
        Map<ModuleId, CanonicalModuleIdentity> declarationIdentities,
        DistributionHome distributionHome) {
    }

    /**
     * The recorded invocation of the fixture compile: the record selects
     * no arm (phase 4 always runs the one production arm), and the fixture
     * compile only contributes the checked-project facts this unit drives
     * ProductionProjectEmission with.
     */
    private static CompilerInvocation fixtureInvocation() {
        return CompilerProfileProvider.resolve(ReleaseState.V1_3_ACTIVE,
            CapabilityRegistry.releaseRegistry());
    }

    /** The release-owned production invocation this unit is driven with. */
    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    private static Fixture compileProject(Map<String, String> sources,
            Map<String, String> externals) throws Exception {
        return compileProject(sources, externals, Backend.LUAJIT, fixtureInvocation());
    }

    private static Fixture compileProject(Map<String, String> sources,
            Map<String, String> externals, Backend backend, CompilerInvocation invocation) throws Exception {
        Path root = Files.createTempDirectory("production-arm-fixture");
        Path src = root.resolve("src");
        for (Map.Entry<String, String> source : sources.entrySet()) {
            writeFileIn(root, source.getKey(), source.getValue());
        }
        Map<String, String> resolvedExternals = new LinkedHashMap<>();
        for (Map.Entry<String, String> external : externals.entrySet()) {
            resolvedExternals.put(external.getKey(),
                root.resolve(external.getValue()).toAbsolutePath().toString());
        }
        Path entry = src.resolve("app.deal").toAbsolutePath();
        Path output = root.resolve("out").toAbsolutePath();
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(entry, output,
            false, false, false, false, backend, resolvedExternals,
            List.of(src.toAbsolutePath()), null, null, invocation);
        boolean compiled = orchestrator.compile();
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        // The fixture compile's subject here is the pre-phase-4 fact set:
        // the checked project, the manifests, and the declaration surface.
        // A construct outside the landed lowering coverage fails the fixture
        // compile closed in phase 4 with its named E6005, which is the very
        // outcome this unit drives directly below.
        boolean fixtureFacts = built != null && built.input() != null
                && built.index() != null && !built.hasErrors()
                && manifests != null && manifests.manifests() != null
                && orchestrator.hostDeclarationSurface() != null;
        boolean honestFailClosed = compiled || orchestrator.diagnostics().stream()
            .allMatch(d -> "E6005".equals(d.code()));
        if (!fixtureFacts || !honestFailClosed) {
            String detail = built == null ? "no checked project"
                : String.valueOf(built.diagnostics());
            deleteRecursively(root);
            throw new IllegalStateException("the fixture project did not build: " + detail
                + " / " + orchestrator.diagnostics());
        }
        HostDeclarationSurface surface = orchestrator.hostDeclarationSurface();
        Map<ModuleId, FfiGeneratedModule> externCModules = new LinkedHashMap<>();
        for (Map.Entry<String, FfiGeneratedModule> generated
                : orchestrator.ffiGenerations().entrySet()) {
            externCModules.put(new ModuleId(generated.getKey()), generated.getValue());
        }
        Map<ModuleId, CanonicalModuleIdentity> identities = new LinkedHashMap<>();
        for (ModuleId declarationModule : surface.moduleIds()) {
            String specifier = null;
            for (Map.Entry<String, String> external : externals.entrySet()) {
                if (external.getKey().replace('/', '.').equals(declarationModule.path())) {
                    specifier = external.getKey();
                    break;
                }
            }
            identities.put(declarationModule, specifier == null
                ? IdentityTestFixtures.moduleIdentityOf(declarationModule.path())
                : new CanonicalModuleIdentity.ExternalModule(specifier));
        }
        return new Fixture(root, built.input(), built.index(), manifests.manifests(),
            surface, externCModules, identities,
            DistributionHome.forManifestDirectory(src.toString()));
    }

    private static SymbolTable copyScope(SymbolTable scope,
            Map<SymbolTable, SymbolTable> copies) {
        if (scope == null) {
            return null;
        }
        SymbolTable copy = copies.get(scope);
        if (copy == null) {
            copy = new SymbolTable(copyScope(scope.parent(), copies));
            copies.put(scope, copy);
            scope.symbols().forEach(copy::define);
        }
        return copy;
    }

    private static Fixture freshCheckedFacts(Fixture fixture) {
        List<CheckedModuleInput> modules = new ArrayList<>();
        for (CheckedModuleInput module : fixture.checkedProject().modules()) {
            CheckResult checks = module.checks();
            Map<SymbolTable, SymbolTable> copies = new java.util.IdentityHashMap<>();
            SymbolTable root = copyScope(checks.symbolTable(), copies);
            Map<StatementNode, SymbolTable> scopes = new java.util.IdentityHashMap<>();
            checks.scopeMap().forEach((node, scope) ->
                scopes.put(node, copyScope(scope, copies)));
            Map<ClassDeclaration, SymbolTable> classes = new java.util.IdentityHashMap<>();
            checks.classScopes().forEach((node, scope) ->
                classes.put(node, copyScope(scope, copies)));
            CheckResult fresh = new CheckResult(
                new java.util.IdentityHashMap<>(checks.typeMap()), root, scopes,
                checks.diagnostics(), classes,
                new java.util.IdentityHashMap<>(checks.constructedClassDecls()));
            modules.add(new CheckedModuleInput(module.moduleId(), module.sourceId(),
                module.sourcePath(), module.ast(), fresh, module.imports(),
                module.exports(), module.kind()));
        }
        CheckedProjectInput checked = fixture.checkedProject();
        return new Fixture(fixture.root(), new CheckedProjectInput(checked.invocation(),
            checked.entryModule(), modules, checked.releaseStateHash()), fixture.index(),
            fixture.manifests(), fixture.surface(), fixture.externCModules(),
            fixture.declarationIdentities(), fixture.distributionHome());
    }

    private static Fixture twoModuleFixture(String appSource) throws Exception {
        return compileProject(new LinkedHashMap<>(Map.of(
                "src/lib.deal", LIB_SOURCE,
                "src/app.deal", appSource)), Map.of());
    }

    private static Fixture bytesFixture() throws Exception {
        return compileProject(new LinkedHashMap<>(Map.of(
                "src/app.deal", BYTES_APP_SOURCE)), Map.of());
    }

    /** The fail-closed source: a nested function declaration (CALLS family). */
    private static final String NESTED_DECLARATION_APP_SOURCE = """
        export function main(): null {
          function inner(): int { return 1; }
          let x: int = inner();
          return null;
        }
        """;

    /**
     * The still-fail-closed source: an arity-extended adapter call whose
     * source is a non-identifier expression — the call-result
     * REEVALUATE_THUNK source (the function-typed-value child's ISSUE-0531
     * residue) — so the production lowering fails the module with the named
     * E6005 CONSTRUCT_UNLOWERED rule before any emission. The forward
     * binding-reference adapter source this fixture previously named is
     * production-covered now: a module-level declaration's execution
     * binding registers at the hoist, so the adapter's single proved
     * {@code BINDING_LOAD} resolves a statically fixed source identity.
     */
    private static final String ADAPTER_SOURCE_APP_SOURCE = """
        export function main(): null {
          let f: (a: int, b: int) => int = make();
          f(1, 2);
          return null;
        }

        function make(): (a: int) => int {
          return function(a: int): int { return a; };
        }
        """;

    private static Fixture adapterSourceFixture() throws Exception {
        return compileProject(new LinkedHashMap<>(Map.of(
                "src/app.deal", ADAPTER_SOURCE_APP_SOURCE)), Map.of());
    }

    private static Fixture nestedDeclarationFixture() throws Exception {
        return compileProject(new LinkedHashMap<>(Map.of(
                "src/app.deal", NESTED_DECLARATION_APP_SOURCE)), Map.of());
    }

    private static Fixture asyncFixture(String libSource, String appSource)
            throws Exception {
        return compileProject(new LinkedHashMap<>(Map.of(
                "src/lib.deal", libSource,
                "src/app.deal", appSource)), Map.of());
    }

    private static Fixture hostFixture() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("src/cfg.d.deal", HOST_DECLARATION);
        sources.put("src/app.deal", HOST_APP_SOURCE);
        return compileProject(sources,
            Map.of(HOST_CFG_SPECIFIER, "src/cfg.d.deal"));
    }

    private static Fixture externCFixture() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("src/native.d.deal", EXTERN_C_DECLARATION);
        sources.put("src/app.deal", EXTERN_C_APP_SOURCE);
        return compileProject(sources,
            Map.of(EXTERN_C_SPECIFIER, "src/native.d.deal"));
    }

    /**
     * The JavaScript production drive's class/HOST/call/async/stdlib
     * fixture: one host declaration module carrying a declared class and two
     * declared functions, one implementation library carrying an exported
     * {@code @jsonable} class and a sync and an async export, and the entry
     * module that reads the host occurrence's typed surface, constructs both
     * the host-declared and the imported class, calls across the module
     * boundary, and awaits the cross-module async export.
     */
    private static final String JS_HOST_DECLARATION = """
        export class Endpoint {
          path: string = "";
          port?: int;
        }

        export function alpha(): int;
        export function describe(s: string): string;
        """;

    private static final String JS_LIB_SOURCE = """
        // @jsonable
        export class Point {
          x: int = 0;
          y: int = 0;
        }

        export function add(a: int, b: int): int {
          return a + b;
        }

        export async function getValue(): int {
          return 42;
        }
        """;

    private static final String JS_APP_SOURCE = """
        import * as lib from "./lib";
        import * as cfg from "host/cfg";
        import * as cfg2 from "host/cfg";
        import * as console from "std/console";

        export function main(): null {
          if (cfg.alpha === cfg2.alpha) {
            throw { code: "JS_PROBE", message: "one typed surface per import" };
          }
          if (cfg.alpha() !== cfg2.alpha()) {
            throw { code: "JS_PROBE", message: "same host entry" };
          }
          let e: cfg.Endpoint = {};
          if (e.path !== "/") {
            throw { code: "JS_PROBE", message: "host class default" };
          }
          if (cfg.describe("probe") !== "/probe") {
            throw { code: "JS_PROBE", message: "host call" };
          }
          let p: lib.Point = { x: cfg.alpha() };
          let total: int = lib.add(p.x, p.y);
          if (total !== 7) {
            throw { code: "JS_PROBE", message: "cross-module call" };
          }
          if (lib.Point$toJson({ x: 3, y: 4 }) !== "{\\"x\\":3,\\"y\\":4}") {
            throw { code: "JS_PROBE", message: "json helper" };
          }
          console.log("PROBE|JS|MAIN");
          return null;
        }

        export async function worker(): int {
          return await lib.getValue();
        }
        """;

    private static Fixture jsFixture() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("src/cfg.d.deal", JS_HOST_DECLARATION);
        sources.put("src/lib.deal", JS_LIB_SOURCE);
        sources.put("src/app.deal", JS_APP_SOURCE);
        return compileProject(sources,
            Map.of(HOST_CFG_SPECIFIER, "src/cfg.d.deal"));
    }

    /** The emitted declared-map literal of one host import (declaration order). */
    private static String declaredMapLiteral(HostDeclarationSurface surface,
            ModuleId module) {
        HostDeclarationSurface.DeclarationFacts facts = surface.require(module);
        StringBuilder map = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, deal.types.Type> export : facts.exports().entrySet()) {
            if (!first) {
                map.append(", ");
            }
            first = false;
            map.append("[\"").append(export.getKey()).append("\"] = \"")
                .append(deal.semantic.DescriptorService.describe(export.getValue())
                    .canonicalSpecText())
                .append("\"");
        }
        return map.append("}").toString();
    }

    // =========================================================================
    // The unit driver
    // =========================================================================

    /** The one project lowering of the fixture over the production inputs. */
    private static SemanticLowerer.ProjectLoweringResult lower(Fixture fixture)
            throws Exception {
        return SemanticLowerer.lowerProject(productionInvocation(),
            fixture.checkedProject(), fixture.index(), fixture.manifests(),
            fixture.surface(), fixture.declarationIdentities(), fixture.externCModules(),
            BuiltinErrorDeclaration.synthesized(
                fixture.checkedProject().modules().get(0).ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                IntrinsicKind.BYTES_NEW),
            Set.of());
    }

    private static ProductionProjectEmission.Result emit(Fixture fixture,
            Backend backend, PublicationStager stager, boolean sourceMapExplicit)
            throws Exception {
        return ProductionProjectEmission.run(productionInvocation(),
            fixture.checkedProject(), fixture.index(), fixture.manifests(),
            fixture.surface(), fixture.declarationIdentities(),
            fixture.externCModules(),
            fixture.distributionHome().manifestDirectoryText(),
            BuiltinErrorDeclaration.synthesized(
                fixture.checkedProject().modules().get(0).ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                IntrinsicKind.BYTES_NEW),
            Set.of(), backend, sourceMapExplicit, fixture.distributionHome(), stager);
    }

    private record Emission(ProductionProjectEmission.Result result, String stderr) {
    }

    private static Emission emitCapturingStderr(Fixture fixture, Backend backend,
            PublicationStager stager, boolean sourceMapExplicit) throws Exception {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        ProductionProjectEmission.Result result;
        try {
            result = emit(fixture, backend, stager, sourceMapExplicit);
        } finally {
            System.setErr(originalErr);
        }
        return new Emission(result, buffer.toString(StandardCharsets.UTF_8));
    }

    // =========================================================================
    // 1. The unit: one production source unit, one public static entry
    // =========================================================================

    // =========================================================================
    // 1. The JS production seam: one lowering, one rendering, the complete
    //    transactional set
    // =========================================================================

    /**
     * The JavaScript production seam drive (design source
     * {@code wiki:javascript-semantic-ir-production-migration},
     * "production_owner" and "artifact_layout"): one lowering and one
     * rendering invocation return every implementation module's CommonJS
     * artifact, the production owner stages the complete source/map/runtime/
     * stdlib set in the existing transaction, the exposed produced project is
     * the run's own closure, repeated staging is byte-identical, publication
     * replaces the whole set (stale files included), and the real staged
     * artifacts execute under Node — the entry's direct-run main and the
     * exported async worker over the cross-module and host boundaries.
     */
    private static void testJsProjectEmission(Fixture fixture, boolean node)
            throws Exception {
        System.out.println("-- the JS arm: one lowering and one rendering return"
            + " every implementation artifact; the complete set stages and"
            + " publishes atomically and the artifacts execute under Node --");
        Path out = fixture.root().resolve("js-out");
        String endpointClassId =
            RuntimeDeclarationProjection.project(fixture.surface(),
                fixture.declarationIdentities(), lower(fixture).seeds())
                .modules().values().iterator().next()
                .classes().get("Endpoint").classId().text();

        ProductionProjectEmission.Result result;
        Map<String, String> firstSet;
        Sidecar appMap;
        Sidecar libMap;
        String appSource;
        String libSource;
        PublicationStager stager = PublicationStager.forRoot(out);
        try {
            Emission emission = emitCapturingStderr(fixture, Backend.JS, stager,
                false);
            result = emission.result();
            check(result.emitted(),
                "the JS production run emits: " + result.diagnostics());
            checkEq("", emission.stderr(),
                "the JS production run prints no LuaJIT/JVM warning");
            checkEq("app.js", result.artifactRelativePath(),
                "Result.artifactRelativePath names the entry module's own"
                    + " artifact, not the whole set");

            // The complete staged set: one artifact per implementation unit
            // at its own CommonJS path, that module's own version-1 sidecar,
            // and the complete runtime/stdlib deployment copies.
            checkEq(Set.of("app.js", "lib.js", "app.deal.map.json",
                    "lib.deal.map.json", "deal/runtime.js", "std/console.js",
                    "std/string.js", "std/table.js", "std/json.js",
                    "std/math.js", "std/time.js"),
                Set.copyOf(stager.stagedSet().relativePaths()),
                "the staged set is the complete artifact/map/runtime/stdlib"
                    + " set: " + stager.stagedSet().relativePaths());

            // The exposed produced project is this run's own closure: every
            // one of its implementation units owns exactly one staged
            // artifact, and no artifact exists outside that closure.
            ExecutableLoweredProject produced = result.producedProject();
            check(produced != null, "the JS run exposes its produced project");
            if (produced == null) {
                return;
            }
            checkEq(new ModuleId("app"), produced.entryModule(),
                "the produced project's entry module is the entry artifact's"
                    + " module");
            checkEq(Set.of(new ModuleId("app"), new ModuleId("lib")),
                produced.modules().keySet(),
                "the produced project is the emitted closure");
            Set<String> closureArtifacts = new java.util.TreeSet<>();
            for (ModuleId module : produced.modules().keySet()) {
                closureArtifacts.add(
                    module.path().replace('.', '/') + ".js");
            }
            Set<String> stagedArtifacts = new java.util.TreeSet<>();
            for (String path : stager.stagedSet().relativePaths()) {
                if (path.endsWith(".js") && !path.startsWith("std/")
                        && !path.equals("deal/runtime.js")) {
                    stagedArtifacts.add(path);
                }
            }
            checkEq(closureArtifacts, stagedArtifacts,
                "the staged artifacts are exactly the produced project's"
                    + " implementation units (the run's actual once-lowered"
                    + " project, never a test lowering)");
            ExecutableLoweredProject testLowering = lower(fixture).project();
            check(produced != testLowering,
                "the exposed project is the run's own instance, not the"
                    + " test's separate lowering");
            checkEq(testLowering, produced,
                "the exposed project is the same once-lowered closure content");
            appSource = new String(stager.stagedSet().artifact("app.js")
                .orElseThrow().content(), StandardCharsets.UTF_8);
            libSource = new String(stager.stagedSet().artifact("lib.js")
                .orElseThrow().content(), StandardCharsets.UTF_8);
            for (SemanticOp op
                    : produced.modules().get(produced.entryModule()).ops()) {
                if (op.payload()
                        instanceof KindPayload.ExportPublishPayload publish) {
                    check(appSource.contains(publish.name()),
                        "the entry artifact publishes the produced project's"
                            + " export '" + publish.name() + "': " + appSource);
                }
            }

            // One version-1 sidecar per artifact: its source string is the
            // module's own display name and its generated string names that
            // module's own artifact (never another module's, never a
            // private-stage path).
            appMap = parseSidecar(new String(stager.stagedSet()
                .artifact("app.deal.map.json").orElseThrow().content(),
                StandardCharsets.UTF_8));
            libMap = parseSidecar(new String(stager.stagedSet()
                .artifact("lib.deal.map.json").orElseThrow().content(),
                StandardCharsets.UTF_8));
            check(appMap.generated().endsWith("/js-out/app.js"),
                "the entry sidecar names the entry artifact: "
                    + appMap.generated());
            check(libMap.generated().endsWith("/js-out/lib.js"),
                "the library sidecar names the library artifact: "
                    + libMap.generated());
            verifySidecarCoordinates(appMap, appSource.lines().toList(),
                JS_APP_SOURCE, "// ", "src/app.deal");
            verifySidecarCoordinates(libMap, libSource.lines().toList(),
                JS_LIB_SOURCE, "// ", "src/lib.deal");
            checkEq(countSourceComments(appSource, "// "),
                appMap.mappings().size(),
                "every recorded entry mapping is one emitted source comment of"
                    + " the entry artifact");
            checkEq(countSourceComments(libSource, "// "),
                libMap.mappings().size(),
                "every recorded library mapping is one emitted source comment"
                    + " of the library artifact");
            firstSet = snapshotOf(stager);
        } finally {
            stager.discard();
        }

        // Independent byte determinism: one more run of the same project
        // into the same live root stages byte-identical content.
        {
            PublicationStager repeat = PublicationStager.forRoot(out);
            try {
                ProductionProjectEmission.Result second =
                    emit(fixture, Backend.JS, repeat, false);
                check(second.emitted(),
                    "the repeated JS run emits: " + second.diagnostics());
                checkEq(firstSet, snapshotOf(repeat),
                    "repeated staging of one project is byte-identical");
                repeat.publish();
            } finally {
                repeat.discard();
            }
        }
        checkEq(firstSet, snapshotTree(out),
            "the published live set equals the staged set byte-for-byte");

        if (!node) {
            System.out.println("  SKIP: the JS production drive needs node");
            return;
        }

        // The host implementation is host-owned: the compile copies no host
        // file, and the published artifact requires the deployed relative
        // specifier (never an ambient NODE_PATH/node_modules resolution).
        writeFileIn(out, "host/cfg.js", """
            "use strict";
            const $rt = require("../deal/runtime");
            globalThis.__hostInit = (globalThis.__hostInit || 0) + 1;
            module.exports = {
              Endpoint: { $kind: "class", $classname: "%s" },
              Endpoint_defaults: { path: "/", port: $rt.MISSING },
              alpha: function () { return 7; },
              describe: function (s) { return "/" + s; },
            };
            """.formatted(endpointClassId));
        check(appSource.contains("$require(\"./host/cfg\")"),
            "the entry artifact requires the deployed relative host"
                + " specifier: " + appSource);
        ProcessOutcome entry = runProcess(List.of("node", "app.js"), out);
        checkEq(0, entry.exitCode(),
            "the published JS entry runs: " + entry.output());
        checkEq("PROBE|JS|MAIN\n", entry.stdout(),
            "the direct-run main ran once and printed its effect");
        checkEq("", entry.stderr(),
            "the published JS entry prints no trace protocol on stderr");

        writeFileIn(out, "driver.js", """
            const app = require("./app.js");
            console.log("PROBE|INIT|" + globalThis.__hostInit);
            app.worker.$f().then((value) => {
              console.log("PROBE|ASYNC|" + value);
            }).catch((e) => {
              console.log("ERR " + (e && e.message));
            });
            """);
        ProcessOutcome async = runProcess(List.of("node", "driver.js"), out);
        checkEq(0, async.exitCode(),
            "the exported async worker runs under Node: " + async.output());
        checkEq("PROBE|INIT|1\nPROBE|ASYNC|42\n", async.stdout(),
            "the raw host file initialized once across both source imports and"
                + " the exported async worker awaited the cross-module async"
                + " export");
        checkEq("", async.stderr(),
            "the async invocation prints no trace protocol on stderr");

        // Whole-set replacement: one more publication removes the stale
        // files a previous set (or a host deployment) left in the live root.
        writeFileIn(out, "obsolete.js", "// stale artifact\n");
        writeFileIn(out, "std/obsolete.js", "// stale deployment copy\n");
        PublicationStager repeat = PublicationStager.forRoot(out);
        try {
            ProductionProjectEmission.Result third =
                emit(fixture, Backend.JS, repeat, false);
            check(third.emitted(),
                "the repeated publication emits: " + third.diagnostics());
            repeat.publish();
        } finally {
            repeat.discard();
        }
        check(!Files.exists(out.resolve("obsolete.js"))
                && !Files.exists(out.resolve("std/obsolete.js")),
            "the repeated publication removes the stale files");
        checkEq(firstSet, snapshotTree(out),
            "the repeated publication leaves exactly the new artifact set");
    }

    /**
     * The direct-run failure presentation of the published JS artifacts: an
     * uncaught DEAL error keeps the located {@code DEAL_ERROR_CODE} line on
     * stderr and exit status 1 (the entry/failure ABI the renderer emits),
     * with an empty stdout and no trace protocol.
     */
    private static void testJsEntryFailureStatus(boolean node) throws Exception {
        System.out.println("-- the published JS entry keeps the located"
            + " DEAL_ERROR_CODE stderr line and status 1 on an uncaught"
            + " failure --");
        Fixture fixture = compileProject(new LinkedHashMap<>(Map.of(
            "src/app.deal", """
                export function main(): null {
                  let x: int = 2147483647 + 1;
                  return null;
                }
                """)), Map.of());
        try {
            Path out = fixture.root().resolve("js-failing-out");
            PublicationStager stager = PublicationStager.forRoot(out);
            try {
                ProductionProjectEmission.Result result =
                    emit(fixture, Backend.JS, stager, false);
                check(result.emitted(),
                    "the failing-main fixture emits: " + result.diagnostics());
                stager.publish();
            } finally {
                stager.discard();
            }
            if (!node) {
                System.out.println("  SKIP: the JS entry-status drive needs node");
                return;
            }
            ProcessOutcome run = runProcess(List.of("node", "app.js"), out);
            checkEq(1, run.exitCode(),
                "the uncaught main failure exits 1: " + run.output());
            checkEq("", run.stdout(),
                "the failure prints nothing on stdout");
            check(run.stderr().startsWith(
                    "DEAL_ERROR_CODE: E8004 int out of safe range at "),
                "the stderr line keeps the located DEAL_ERROR_CODE"
                    + " presentation: " + run.stderr());
            checkEq(1L, run.stderr().lines().count(),
                "the failure prints the one presentation line: " + run.stderr());
        } finally {
            deleteRecursively(fixture.root());
        }
    }

    /** The occurrences of one needle in a text. */
    private static int countOfText(String text, String needle) {
        int count = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + 1);
        }
        return count;
    }

    /** The staged tree of one stager as the published-bytes snapshot. */
    private static Map<String, String> snapshotOf(PublicationStager stager)
            throws Exception {
        Map<String, String> snapshot = new java.util.TreeMap<>();
        for (String path : stager.stagedSet().relativePaths()) {
            snapshot.put(path, java.util.Arrays.toString(stager.stagedSet()
                .artifact(path).orElseThrow().content()));
        }
        return snapshot;
    }

    /**
     * The preserved {@code FFI_UNSUPPORTED_BACKEND} arm of the JS production
     * unit: a resolved extern-C declaration import rejects before the one
     * project lowering and before any emission, with exactly the unchanged
     * E6006 text at the importing declaration's own statement range, and
     * nothing is staged or published.
     */
    private static void testJsExternCRejection(Fixture externC) throws Exception {
        System.out.println("-- the JS arm rejects a resolved extern-C import"
            + " before lowering with the exact E6006 and publishes nothing --");
        Path out = externC.root().resolve("js-extern-out");
        writeFileIn(out, "app.js", "// previous artifact\n");
        writeFileIn(out, "deal/runtime.js", "// previous runtime\n");
        Map<String, String> before = snapshotTree(out);
        PublicationStager stager = PublicationStager.forRoot(out);
        ProductionProjectEmission.Result result;
        try {
            result = emit(externC, Backend.JS, stager, false);
            check(!result.emitted(),
                "the extern-C import fails the JS production arm");
            checkEq(1, result.diagnostics().size(),
                "the rejection is one diagnostic: " + result.diagnostics());
            CompilerDiagnostic rejection = result.firstDiagnostic();
            checkEq("E6006", rejection == null ? null : rejection.code(),
                "the rejection keeps the E6006 FFI_UNSUPPORTED_BACKEND code");
            checkEq(ProductionProjectEmission.JS_EXTERN_C_REJECTION,
                rejection == null ? null : rejection.message(),
                "the rejection keeps the exact preserved text");
            checkEq("JavaScript backend: @extern-c imports are not supported"
                    + " (FFI_UNSUPPORTED_BACKEND, ISSUE-0169 skeleton)",
                rejection == null ? null : rejection.message(),
                "the exact E6006 message text");
            check(rejection != null && rejection.range().startLine() == 1
                    && rejection.range().startColumn() == 1
                    && rejection.range().file().endsWith("/src/app.deal"),
                "the rejection sits at the import statement's own range, got "
                    + (rejection == null ? "none" : rejection.range()));
            checkEq(1L, result.diagnostics().stream()
                    .filter(d -> "error".equals(d.severity())).count(),
                "nothing but the one rejection is produced");
            check(stager.stagedSet().relativePaths().isEmpty(),
                "the rejection stages no artifact and no map: "
                    + stager.stagedSet().relativePaths());
        } finally {
            stager.discard();
        }
        checkEq(before, snapshotTree(out),
            "the rejection leaves the previous published set byte-identical");
    }

    /**
     * The JS arm's fail-closed contract: the structural declaration
     * projection and the renderer share the one E6005
     * {@code SHARED_EMITTER_COVERAGE} projection, an emitter rejection that
     * identified its offending operation keeps that operation's own range,
     * and an invalid or incomplete structural input yields one E6005 with no
     * artifact. The seam carries no AST or checker object.
     */
    private static void testJsFailClosedSeam(Fixture fixture) throws Exception {
        System.out.println("-- the JS arm fails closed through the one E6005"
            + " projection and receives no AST/checker object --");

        // (1) The absent declaration-surface entry: the JS renderer rejects
        // the HOST MODULE_IMPORT with the source-bearing shared signal
        // carrying the import's own range, and the arm's own projection
        // merges it into the unchanged SHARED_EMITTER_COVERAGE E6005.
        SemanticLowerer.ProjectLoweringResult lowering = lower(fixture);
        check(!lowering.hasErrors(),
            "the JS fixture lowers: " + lowering.diagnostics());
        if (!lowering.hasErrors()) {
            LoweredModuleUnit entry =
                lowering.project().modules().get(lowering.project().entryModule());
            SemanticOp hostImport = null;
            for (SemanticOp op : entry.ops()) {
                if (op.kind() == SemanticOpKind.MODULE_IMPORT
                        && op.payload()
                            instanceof KindPayload.ModuleImportPayload payload
                        && payload.resolvedModule().equals(HOST_CFG)) {
                    // The first source occurrence is the one the renderer's
                    // absent-surface lookup reports.
                    hostImport = op;
                    break;
                }
            }
            check(hostImport != null,
                "the JS fixture lowers the HOST MODULE_IMPORT");
            if (hostImport != null) {
                DiagnosticRange expected = hostImport.origin().span().range();
                SharedEmissionFailure rejection = null;
                try {
                    Map<ModuleId, JsSourceFile> sourceFiles =
                        new LinkedHashMap<>();
                    for (CheckedModuleInput module
                            : fixture.checkedProject().modules()) {
                        sourceFiles.put(module.moduleId(), new JsSourceFile(
                            module.sourceId(), module.sourceId()));
                    }
                    JsSemanticEmitter.emitProductionProject(new JsEmissionInput(
                        lowering.project(), lowering.tables(),
                        lowering.registries(), lowering.seeds(),
                        lowering.namespaces(),
                        new RuntimeDeclarationSurface(Map.of()), sourceFiles,
                        false));
                } catch (SharedEmissionFailure failure) {
                    rejection = failure;
                }
                check(rejection != null,
                    "the absent declaration-surface entry fails the JS"
                        + " renderer closed");
                if (rejection != null) {
                    checkEq(expected, rejection.diagnosticRange(),
                        "the JS rejection carries the offending HOST"
                            + " MODULE_IMPORT's own exact range");
                    check(rejection.getMessage().contains(HOST_CFG.path())
                            && rejection.getMessage().contains(
                                "structural declaration surface does not"
                                    + " carry"),
                        "the JS rejection names the absent declaration module"
                            + " and the structural surface: "
                            + rejection.getMessage());
                    // The arm's own projection of the JS renderer's real
                    // rejection: one E6005 SHARED_EMITTER_COVERAGE that
                    // keeps the complete emitter message and the identifying
                    // import's own range.
                    assertProjectedEmitterOrigin(
                        "the JS absent declaration-surface entry", expected,
                        rejection.getMessage(), lowering, rejection);
                }
            }
        }

        // (2) An incomplete structural companion at the production arm: the
        // canonical declaration module identity of a declared class is
        // absent, so the projection fails closed with exactly one E6005 and
        // nothing is staged or published.
        Path out = fixture.root().resolve("js-fail-closed-out");
        writeFileIn(out, "app.js", "// previous artifact\n");
        Map<String, String> before = snapshotTree(out);
        PublicationStager stager = PublicationStager.forRoot(out);
        ProductionProjectEmission.Result result;
        try {
            result = ProductionProjectEmission.run(productionInvocation(),
                fixture.checkedProject(), fixture.index(), fixture.manifests(),
                fixture.surface(), Map.of(),
                fixture.externCModules(),
                fixture.distributionHome().manifestDirectoryText(),
                BuiltinErrorDeclaration.synthesized(
                    fixture.checkedProject().modules().get(0).ast().span()),
                List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                    IntrinsicKind.BYTES_NEW),
                Set.of(), Backend.JS, false, fixture.distributionHome(), stager);
            check(!result.emitted(),
                "the incomplete structural companion fails the JS arm closed");
            checkEq(1, result.diagnostics().size(),
                "exactly one E6005: " + result.diagnostics());
            CompilerDiagnostic failure = result.firstDiagnostic();
            checkEq("E6005", failure == null ? null : failure.code(),
                "the fail-closed result is E6005");
            check(failure != null && failure.message().contains(
                    "declaration module 'host.cfg'"),
                "the fail-closed result names the inconsistent declaration"
                    + " fact: " + failure);
            check(stager.stagedSet().relativePaths().isEmpty(),
                "the fail-closed run stages no artifact: "
                    + stager.stagedSet().relativePaths());
        } finally {
            stager.discard();
        }
        checkEq(before, snapshotTree(out),
            "the fail-closed run leaves the previous set byte-identical");

        // (3) The seam and the arm carry no AST/checker object: the rendering
        // input's components are semantic/structural only, and the arm
        // projects the structural declaration surface instead of forwarding
        // producers or routing through the AST emitter.
        for (RecordComponent component
                : JsEmissionInput.class.getRecordComponents()) {
            String type = component.getType().getName();
            check(type.startsWith("deal.semantic.ir.")
                    || type.startsWith("deal.semantic.")
                    || type.startsWith("deal.codegen.js.")
                    || type.equals("boolean")
                    || type.startsWith("java.util."),
                "the rendering input's " + component.getName()
                    + " carries no frontend object (" + type + ")");
        }
        String armSource = Files.readString(
            Path.of("deal", "module", "ProductionProjectEmission.java"),
            StandardCharsets.UTF_8);
        check(!armSource.contains("JsBackend"),
            "the production arm routes no JavaScript through the AST emitter");
        check(armSource.contains("JsSemanticEmitter.emitProductionProject"),
            "the production arm calls the one semantic-IR renderer");
        check(armSource.contains("RuntimeDeclarationProjection.project"),
            "the production arm projects the structural declaration surface");
        check(armSource.contains("new JsEmissionInput("),
            "the production arm builds the canonical rendering input");
        check(!armSource.contains("CheckResult")
                && !armSource.contains("ProgramNode"),
            "the production arm holds no checker or program object");
    }

    /** Whether the real Node binary is available for the artifact drives. */
    private static boolean nodeAvailable() {
        try {
            Process node = new ProcessBuilder("node", "--version")
                .redirectErrorStream(true).start();
            node.getInputStream().readAllBytes();
            return node.waitFor() == 0;
        } catch (java.io.IOException | InterruptedException ignored) {
            return false;
        }
    }

    /**
     * The typed-AST IR dump of a JS compile stages only with the accepted
     * emission (the immediate JS dump staging is retired): the accepted
     * compile starts exactly one staging transaction and publishes its dump
     * next to the artifact set, while a JS-backend rejection discards the
     * held dump — it is never the first staging operation, so it can neither
     * pre-empt the backend diagnostic nor reach the live root, whose
     * previous set stays byte-identical.
     */
    private static void testJsDumpDeferral() throws Exception {
        System.out.println("-- the JS IR dump defers until the emission"
            + " accepts the compile: an accepted compile stages one"
            + " transaction, a rejection starts none --");
        Path project = Files.createTempDirectory("production-js-dump-");
        try {
            writeFileIn(project, "src/app.deal",
                "export function main(): null { return null; }\n");
            Path out = project.resolve("out");
            Path entry = project.resolve("src/app.deal").toAbsolutePath();

            // The accepted control: one staging transaction carries the
            // artifact set and the deferred typed-AST dump.
            int[] stagingStarts = {0};
            PublicationStager.installPublishFault(step -> {
                if (PublicationStager.FAULT_STEP_STAGING_BEGAN.equals(step)) {
                    stagingStarts[0]++;
                }
            });
            CompilationOrchestrator accepted;
            try {
                accepted = compileJs(project, entry, out, true, Map.of());
            } finally {
                PublicationStager.clearPublishFault();
            }
            check(accepted.compile(),
                "the accepted JS compile with --dump-ir succeeds");
            checkEq(1, stagingStarts[0],
                "the accepted JS compile starts exactly one staging"
                    + " transaction (artifact set and dump)");
            check(Files.isRegularFile(out.resolve("app.js")),
                "the accepted JS compile publishes its artifact");
            check(Files.isRegularFile(out.resolve("app.ir.txt"))
                    && Files.readString(out.resolve("app.ir.txt"),
                        StandardCharsets.UTF_8).contains("main"),
                "the accepted JS compile publishes the typed-AST IR dump");

            // The JS-backend rejection with --dump-ir: the extern-C import
            // rejects in phase 4, the held dump is discarded with the stage
            // tree, and the prior live set stays untouched.
            writeFileIn(project, "src/math.d.deal", """
                // @extern-c
                export function add(a: int, b: int): int;
                """);
            writeFileIn(project, "src/app.deal", """
                import * as math from "native/math";
                export function main(): null { return null; }
                """);
            // The previous live set carries an older dump: a rejected
            // compile publishes no dump, so that byte survives untouched.
            writeFileIn(out, "app.ir.txt", "previous dump\n");
            Map<String, String> before = snapshotTree(out);
            stagingStarts[0] = 0;
            CompilationOrchestrator rejected;
            try {
                // A staging fault at the first staging operation: with the
                // retired immediate JS dump staging the phase-1/3 dump write
                // would be that first operation and would abort the compile
                // before the backend rejection. Deferring the dump makes the
                // first staging operation a phase-4 deployment copy, so the
                // E6006 rejection is already recorded when the fault fires.
                PublicationStager.installPublishFault(step -> {
                    if (PublicationStager.FAULT_STEP_STAGING_BEGAN.equals(
                            step)) {
                        stagingStarts[0]++;
                        throw new java.io.IOException(
                            "injected staging failure (JS dump probe)");
                    }
                });
                rejected = compileJs(project, entry, out, true,
                    Map.of("native/math",
                        project.resolve("src/math.d.deal").toString()));
            } finally {
                PublicationStager.clearPublishFault();
            }
            check(!rejected.compile(),
                "the extern-C-importing JS compile is rejected");
            List<CompilerDiagnostic> rejectedErrors =
                rejected.diagnostics().stream()
                    .filter(d -> "error".equals(d.severity())).toList();
            checkEq(1, rejectedErrors.size(),
                "the rejection is the one E6006 (the dump never staged ahead"
                    + " of it): " + rejected.diagnostics());
            checkEq("E6006",
                rejectedErrors.isEmpty() ? null : rejectedErrors.get(0).code(),
                "the dump-deferral rejection keeps the E6006 backend"
                    + " rejection");
            checkEq("previous dump\n",
                Files.readString(out.resolve("app.ir.txt"),
                    StandardCharsets.UTF_8),
                "the rejected JS compile publishes no IR dump");
            checkEq(before, snapshotTree(out),
                "the rejected JS compile leaves the previous set"
                    + " byte-identical (no dump, no artifact)");
        } finally {
            deleteRecursively(project);
        }
    }

    /**
     * The project-local stdlib override of the JS arm: the deployment copy is
     * the project's own {@code <manifestDir>/std} implementation, exactly the
     * one {@link DistributionHome} precedence every backend consumes — the
     * JavaScript arm neither re-derives nor prefers the distribution copy.
     */
    private static void testJsStdlibOverride() throws Exception {
        System.out.println("-- the JS arm stages the project-local stdlib"
            + " override through the one distribution precedence --");
        Fixture fixture = jsFixture();
        try {
            String override = """
                "use strict";
                module.exports = { tag: "PROJECT-OVERRIDE" };
                """;
            writeFileIn(fixture.root(), "src/std/math.js", override);
            PublicationStager stager =
                PublicationStager.forRoot(fixture.root().resolve("js-override-out"));
            try {
                ProductionProjectEmission.Result result =
                    emit(fixture, Backend.JS, stager, false);
                check(result.emitted(),
                    "the override project emits: " + result.diagnostics());
                checkEq(override, new String(stager.stagedSet()
                        .artifact("std/math.js").orElseThrow().content(),
                        StandardCharsets.UTF_8),
                    "the staged stdlib copy is the project-local override");
                check(stager.stagedSet().artifact("std/console.js").isPresent(),
                    "the untouched stdlib implementations keep their"
                        + " distribution copies");
            } finally {
                stager.discard();
            }
        } finally {
            deleteRecursively(fixture.root());
        }
    }

    /**
     * The missing-runtime negative of the JS arm (the preserved E6000): the
     * resolution is observed in an isolated process whose working directory
     * and distribution surface carry no runtime, so the checkout development
     * fallback cannot satisfy the copy. The pinned text and its exact
     * missing-anchor note are unchanged, and nothing is published.
     */
    private static void testJsRuntimeMissing() throws Exception {
        System.out.println("-- the JS arm's missing runtime keeps the pinned"
            + " E6000 text and note and publishes nothing --");
        Path root = Files.createTempDirectory("js-runtime-missing-");
        try {
            writeFileIn(root, "src/app.deal", """
                export class Box { v: int = 1; }

                export function main(): null {
                  let b: Box = {};
                  return null;
                }
                """);
            // Every stdlib implementation is deployed; only the runtime is
            // missing, so the runtime copy is the one required resource the
            // resolved surface does not provide.
            Files.createDirectories(root.resolve("std"));
            for (String module : List.of("console", "string", "table", "json",
                    "math", "time")) {
                Files.copy(Path.of("std", module + ".js"),
                    root.resolve("std").resolve(module + ".js"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Path armOut = root.resolve("arm-out");
            ProcessOutcome probe = runProcess(List.of("java", "-ea", "-cp",
                absoluteClasspath(), "deal.test.ProductionArmJsProbe",
                root.toString(), root.resolve("fixture-out").toString(),
                armOut.toString()), root);
            checkEq(0, probe.exitCode(),
                "the missing-runtime probe runs: " + probe.output());
            checkEq("RESULT failed", probe.stdout().lines()
                    .filter(line -> line.startsWith("RESULT"))
                    .findFirst().orElse(null),
                "the JS arm fails without the runtime");
            checkEq("DIAG E6000|error|Runtime library not found:"
                    + " deal/runtime.js|missing anchor: runtime library path"
                    + " 'deal/runtime.js'",
                probe.stdout().lines()
                    .filter(line -> line.startsWith("DIAG"))
                    .findFirst().orElse(null),
                "the pinned E6000 text and its exact missing-anchor note");
            check(probe.stdout().lines().noneMatch(
                    line -> line.equals("PUBLISHED")),
                "the missing runtime publishes nothing: " + probe.stdout());
            check(!Files.exists(armOut),
                "the missing runtime leaves the live output root absent");
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * One release-owned JS compile of one project with the dump flag; the
     * orchestrator is returned so its diagnostics and published set are the
     * subject (the compile result alone hides which diagnostic won).
     */
    private static CompilationOrchestrator compileJs(Path project, Path entry,
            Path out, boolean dumpIr, Map<String, String> externals)
            throws Exception {
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entry, out, false, dumpIr, false, false, Backend.JS, externals,
            List.of(project.resolve("src").toAbsolutePath()),
            Path.of(".").toAbsolutePath().normalize(), null,
            productionInvocation());
        orchestrator.compile();
        return orchestrator;
    }

    // =========================================================================
    // 2. One lowering, one emission, one staged project artifact per target
    // =========================================================================

    private record DefaultLuaRun(String source, String stderr, Set<String> paths) {
    }

    private static DefaultLuaRun testOneArtifactPerTarget(Fixture fixture) throws Exception {
        System.out.println("-- one staged project artifact per target, one sidecar "
            + "per constituent source, the LuaJIT deployment copies, "
            + "byte-identical repeats --");
        Path luaOut = fixture.root().resolve("lua-out");
        Path jvmOut = fixture.root().resolve("jvm-out");
        Path luaOutRepeat = fixture.root().resolve("lua-out-repeat");
        DefaultLuaRun defaultRun;
        {
            // LuaJIT: the chunk named for the entry module, the v1.3 default
            // source-map sidecar of every constituent source, and the
            // complete runtime/stdlib deployment copies.
            PublicationStager luaStager = PublicationStager.forRoot(luaOut);
            ProductionProjectEmission.Result luaResult;
            String firstChunk;
            try {
                Emission emission = emitCapturingStderr(fixture, Backend.LUAJIT,
                    luaStager, false);
                luaResult = emission.result();
                check(luaResult.emitted(),
                    "the LuaJIT production run emits: " + luaResult.diagnostics());
                checkEq("app.lua", luaResult.artifactRelativePath(),
                    "the LuaJIT artifact is the entry module's chunk");
                check(luaStager.stagedSet().artifact("app.lua").isPresent(),
                    "exactly one staged artifact at the pinned entry path");
                check(luaStager.stagedSet().artifact("lib.lua").isEmpty(),
                    "no per-module sibling artifact stages");
                check(luaStager.stagedSet().artifact("deal/runtime.lua").isPresent(),
                    "the complete LuaJIT runtime deployment copy stages");
                check(luaStager.stagedSet().artifact("std/console.lua").isPresent(),
                    "the complete stdlib deployment copies stage");
                check(luaStager.stagedSet().artifact("app.deal.map.json").isPresent()
                        && luaStager.stagedSet()
                            .artifact("lib.deal.map.json").isPresent(),
                    "one default source-map sidecar stages per constituent source");
                checkEq(luaStager.stagedSet().size(),
                    3 + luaStager.stagedSet().relativePaths().stream()
                        .filter(path -> path.startsWith("std/")
                            || path.equals("deal/runtime.lua")).toList().size(),
                    "the LuaJIT staged set is the project artifact, its two "
                        + "sidecars, and the deployment copies");
                defaultRun = new DefaultLuaRun(new String(luaStager.stagedSet()
                    .artifact("app.lua").orElseThrow().content(),
                    StandardCharsets.UTF_8), emission.stderr(),
                    Set.copyOf(luaStager.stagedSet().relativePaths()));
                firstChunk = defaultRun.source();
                luaStager.publish();
            } finally {
                luaStager.discard();
            }

            // JVM: one class source plus its per-source sidecars; no
            // deployment copy on this target.
            PublicationStager jvmStager = PublicationStager.forRoot(jvmOut);
            ProductionProjectEmission.Result jvmResult;
            try {
                jvmResult = emit(fixture, Backend.JVM, jvmStager, false);
                check(jvmResult.emitted(),
                    "the JVM production run emits: " + jvmResult.diagnostics());
                checkEq("App.java", jvmResult.artifactRelativePath(),
                    "the JVM artifact is the entry class named by classNameFor");
                checkEq(Set.of("App.java", "app.deal.map.json",
                        "lib.deal.map.json"),
                    jvmStager.stagedSet().relativePaths(),
                    "the JVM stages the one entry class and one sidecar per "
                        + "constituent source, nothing else");
                jvmStager.publish();
            } finally {
                jvmStager.discard();
            }

            // Byte-identical repeated staging.
            PublicationStager repeatStager =
                PublicationStager.forRoot(luaOutRepeat);
            try {
                ProductionProjectEmission.Result repeat =
                    emit(fixture, Backend.LUAJIT, repeatStager, false);
                check(repeat.emitted(),
                    "the repeated LuaJIT run emits: " + repeat.diagnostics());
                String secondChunk = new String(repeatStager.stagedSet()
                    .artifact("app.lua").orElseThrow().content(),
                    StandardCharsets.UTF_8);
                checkEq(firstChunk, secondChunk,
                    "repeated compiles are byte-identical");
            } finally {
                repeatStager.discard();
            }
        }
        return defaultRun;
    }

    private static void testOneMainProbe() throws Exception {
        System.out.println("-- the staged chunk delegates the entry main exactly "
            + "once --");
        Fixture fixture = twoModuleFixture(PROBE_APP_SOURCE);
        Path out = fixture.root().resolve("out-arm");
        try {
            PublicationStager stager = PublicationStager.forRoot(out);
            try {
                ProductionProjectEmission.Result result =
                    emit(fixture, Backend.LUAJIT, stager, false);
                check(result.emitted(),
                    "the probe fixture emits: " + result.diagnostics());
                stager.publish();
            } finally {
                stager.discard();
            }
            check(Files.isRegularFile(out.resolve("app.lua")),
                "the published chunk exists at the pinned path");
            ProcessOutcome run = runProcess(List.of("luajit", "app.lua"), out);
            checkEq(0, run.exitCode(), "the published chunk exits 0: " + run.output());
            checkEq("PROBE|MAIN-ONCE\n", run.stdout(),
                "the entry main ran exactly once");
            checkEq("", run.stderr(),
                "the production chunk publishes no trace protocol on stderr");
        } finally {
            deleteRecursively(fixture.root());
        }
    }

    // =========================================================================
    // 3. The real toolchains
    // =========================================================================

    private static void testJvmToolchain(Fixture fixture) throws Exception {
        System.out.println("-- the staged JVM artifact compiles with javac --release "
            + "25 -proc:none and runs under java --");
        Path out = fixture.root().resolve("jvm-out");
        Path classes = fixture.root().resolve("classes");
        {
            Path source = out.resolve("App.java");
            check(Files.isRegularFile(source),
                "the published JVM artifact exists at the pinned path");
            Files.createDirectories(classes);
            String classpath = absoluteClasspath();
            ProcessOutcome javacRun = runProcess(List.of("javac", "--release", "25",
                "-proc:none", "-cp", classpath, "-d", classes.toString(),
                source.toAbsolutePath().toString()), fixture.root());
            checkEq(0, javacRun.exitCode(),
                "the published JVM artifact compiles: " + javacRun.output());
            if (javacRun.exitCode() != 0) {
                return;
            }
            ProcessOutcome run = runProcess(List.of("java", "-cp",
                classes + java.io.File.pathSeparator + classpath, "App"),
                fixture.root());
            checkEq(0, run.exitCode(), "the published JVM artifact runs: "
                + run.output());
            checkEq("", run.stdout(),
                "the successful JVM production run prints nothing on stdout");
            check(!run.stderr().contains("R|"),
                "the executed JVM artifact emits no trace line");
        }
    }

    // =========================================================================
    // 4. Atomic failure: a failing lowering; the realized cross-module sync
    //    call emits and stages
    // =========================================================================

    private static String stagedLua(Fixture fixture, Path out, String label) throws Exception {
        PublicationStager stager = PublicationStager.forRoot(out);
        try {
            ProductionProjectEmission.Result result = emit(fixture, Backend.LUAJIT, stager, false);
            check(result.emitted(), label + " emits: " + result.diagnostics());
            check(result.diagnostics().isEmpty(), label + " has no diagnostics: " + result.diagnostics());
            checkEq("app.lua", result.artifactRelativePath(), label + " entry artifact");
            var artifact = stager.stagedSet().artifact("app.lua");
            check(artifact.isPresent(), label + " artifact staged before publication");
            return artifact.isPresent() ? new String(artifact.orElseThrow().content(), StandardCharsets.UTF_8) : "";
        } finally {
            stager.discard();
        }
    }

    private static void testAtomicFailures(Fixture failing) throws Exception {
        System.out.println("-- a failing lowering stages nothing and preserves the "
            + "previous artifact set; the cross-module sync call emits and "
            + "stages --");

        for (String label : List.of("bytes", "nested-declaration")) {
            Fixture fixture = label.equals("bytes") ? bytesFixture() : nestedDeclarationFixture();
            try {
                stagedLua(fixture, fixture.root().resolve("out-arm"), label);
            } finally {
                deleteRecursively(fixture.root());
            }
        }

        Path out = failing.root().resolve("out-arm");
        {
            writeFileIn(out, "app.lua", "-- previous artifact\n");
            writeFileIn(out, "lib.lua", "-- previous sibling\n");
            writeFileIn(out, "deal/runtime.lua", "-- previous runtime\n");
            Map<String, String> before = snapshotTree(out);
            PublicationStager stager = PublicationStager.forRoot(out);
            ProductionProjectEmission.Result result;
            try {
                result = emit(failing, Backend.LUAJIT, stager, false);
            } finally {
                stager.discard();
            }
            check(!result.emitted(), "the fail-closed run emits nothing");
            check(result.firstDiagnostic() != null
                    && "E6005".equals(result.firstDiagnostic().code()),
                "the failing lowering merges the first E6005: "
                    + result.diagnostics());
            check(result.firstDiagnostic() != null
                    && result.firstDiagnostic().message().contains("CONSTRUCT_UNLOWERED"),
                "the adapter-source construct is the named fail-closed rule: "
                    + result.diagnostics());
            checkEq(before, snapshotTree(out),
                "the failing lowering leaves the previous artifact set byte-identical");
        }

        Fixture sync = twoModuleFixture(SYNC_CALL_APP_SOURCE);
        Path syncOut = sync.root().resolve("out-arm");
        try {
            writeFileIn(syncOut, "app.lua", "-- previous artifact\n");
            writeFileIn(syncOut, "deal/runtime.lua", "-- previous runtime\n");
            String chunk = stagedLua(sync, syncOut, "cross-module sync call");
            check(chunk.contains("__module = \"lib\"")
                && chunk.contains("__modStack[#__modStack + 1] = __module")
                && chunk.contains("pcall(__factories.F") && !chunk.contains("SharedM"),
                "staged call uses the callee module context without per-module artifact");
            check(chunk.contains("__svStack"), "staged call preserves private callee state");
        } finally {
            deleteRecursively(sync.root());
        }
    }

    // =========================================================================
    // 5. The pre-emission closure guard
    // =========================================================================

    private static void testHostImportGuard(Fixture fixture, Fixture externC)
            throws Exception {
        System.out.println("-- the HOST-declaration import emits its declared-map "
            + "load and stages; the extern-C declaration import emits its "
            + "load_ffi prelude --");

        // The HOST-declaration-kind import: realized by the host load of
        // the module init walk (H1), so the production run stages its one
        // project artifact and no guard fires.
        Path out = fixture.root().resolve("out-arm");
        {
            String chunk = stagedLua(fixture, out, "HOST-declaration import");
            HostDeclarationSurface.DeclarationFacts facts =
                fixture.surface().require(HOST_CFG);
            checkEq(HostDeclarationSurface.DeclarationKind.HOST, facts.kind(),
                "the fixture's declaration module is HOST-kind");
            String loadPrefix = "__exportSurfaces[\"" + HOST_CFG.path()
                + "\"] = __exportSurfaces[\"" + HOST_CFG.path()
                + "\"] or __rt.load_host(\"" + HOST_CFG_SPECIFIER + "\", ";
            check(chunk.contains(loadPrefix
                    + declaredMapLiteral(fixture.surface(), HOST_CFG) + ", "),
                "the emitted artifact carries the load of " + HOST_CFG_SPECIFIER
                    + " with the declared map in declaration order");
            checkEq(1, countOccurrences(chunk,
                    "__rt.load_host(\"" + HOST_CFG_SPECIFIER + "\""),
                "the host module loads exactly once for the one import op");
            check(chunk.indexOf("__dealMain = function()")
                    < chunk.indexOf("__rt.load_host(\"" + HOST_CFG_SPECIFIER),
                "the load runs inline in the module init walk");
            check(!chunk.contains(ProductionProjectEmission.HOST_MODULE_IMPORT),
                "the emitted chunk carries no guard token");
        }

        Path externCOut = externC.root().resolve("out-arm");
        {
            checkEq(HostDeclarationSurface.DeclarationKind.EXTERN_C,
                externC.surface().require(NATIVE_MATH).kind(),
                "the probe's declaration module is EXTERN_C-kind");
            String chunk = stagedLua(externC, externCOut, "extern-C declaration import");
            check(chunk.contains("__exportSurfaces[\"" + NATIVE_MATH.path()
                    + "\"] = __exportSurfaces[\"" + NATIVE_MATH.path()
                    + "\"] or __rt.load_ffi(\"ffi:@$external/"
                    + EXTERN_C_SPECIFIER + "\", "),
                "the emitted artifact carries the load_ffi prelude with the "
                    + "metadata-provided module key: " + chunk);
            check(chunk.contains(", \""
                    + externC.root().resolve("src/app.deal").toAbsolutePath()
                    + "\", 1, 1)"),
                "the load carries the import statement's span triplet: "
                    + chunk);
            check(!chunk.contains(ProductionProjectEmission.HOST_MODULE_IMPORT),
                "the emitted chunk carries no guard token");
            check(!chunk.contains("ffi.C") && !chunk.contains("cdef("),
                "the emitted chunk carries no ffi.C/cdef text");
        }
    }

    /**
     * The preserved emission origin (ISSUE-0750): an extern-C declaration
     * whose carried forward bindings name a provider module the artifact
     * does not publish fails the emission closed with one E6005
     * {@code SHARED_EMITTER_COVERAGE} {@code FFI_PROVIDER_GAP} whose range
     * is the extern-C import statement's own SOURCE span — the identifiable
     * offending origin of the emission failure, never the empty-file
     * synthetic anchor — and stages nothing.
     */
    private static void testFfiProviderGapOrigin(Fixture externC) throws Exception {
        System.out.println("-- the emission provider gap carries the "
            + "extern-C import statement's own origin --");

        {
            FfiGeneratedModule original =
                externC.externCModules().get(NATIVE_MATH);
            check(original != null,
                "the extern-C fixture carries its generated module");
            if (original == null) {
                return;
            }
            FfiForwardBindings bindings = original.bindings();
            FfiImportedFunctionReference gap = new FfiImportedFunctionReference(
                "gap", "gapExport", "gap.provider", "() -> int",
                "gap-digest", bindings.importedFunctions().size(),
                DiagnosticRange.synthetic("gap.provider.d.deal"));
            FfiForwardBindings doctoredBindings = new FfiForwardBindings(
                bindings.moduleKey(), bindings.state(), bindings.cells(),
                java.util.stream.Stream.concat(
                        bindings.importedFunctions().stream(),
                        java.util.stream.Stream.of(gap)).toList(),
                bindings.importedClassPlans());
            FfiGeneratedModule doctored = new FfiGeneratedModule(
                original.modulePath(), original.descriptor(),
                original.cdefBundle(), original.plans(), doctoredBindings);
            Map<ModuleId, FfiGeneratedModule> doctoredModules =
                new LinkedHashMap<>(externC.externCModules());
            doctoredModules.put(NATIVE_MATH, doctored);
            Fixture doctoredFixture = new Fixture(externC.root(),
                externC.checkedProject(), externC.index(), externC.manifests(),
                externC.surface(), doctoredModules,
                externC.declarationIdentities(), externC.distributionHome());

            Path out = externC.root().resolve("out-provider-gap");
            PublicationStager stager = PublicationStager.forRoot(out);
            try {
                ProductionProjectEmission.Result result =
                    emit(doctoredFixture, Backend.LUAJIT, stager, false);
                check(!result.emitted(),
                    "the provider gap fails the emission closed");
                checkEq(1, result.diagnostics().size(),
                    "exactly one E6005 diagnostic: " + result.diagnostics());
                if (result.diagnostics().size() == 1) {
                    CompilerDiagnostic diagnostic = result.firstDiagnostic();
                    checkEq("E6005", diagnostic.code(),
                        "the provider gap is E6005");
                    check(diagnostic.message().contains(
                            ProductionProjectEmission.SHARED_EMITTER_COVERAGE)
                            && diagnostic.message().contains(
                                ProductionProjectEmission.FFI_PROVIDER_GAP)
                            && diagnostic.message().contains(
                                "'" + EXTERN_C_SPECIFIER + "'")
                            && diagnostic.message().contains(
                                "gap.provider"),
                        "the E6005 message is the unchanged provider-gap text: "
                            + diagnostic.message());
                    check(diagnostic.range().origin() == RangeOrigin.SOURCE,
                        "the E6005 range is SOURCE, got "
                            + diagnostic.range().origin());
                    String entry = externC.root().resolve("src/app.deal")
                        .toAbsolutePath().normalize().toString();
                    checkEq(entry, diagnostic.range().file(),
                        "the E6005 range names the consuming entry file");
                    checkEq(1, diagnostic.range().startLine(),
                        "the E6005 range starts at the import statement's line");
                    checkEq(1, diagnostic.range().startColumn(),
                        "the E6005 range starts at the import statement's "
                            + "column");
                    // The frontend's import-declaration span runs to the
                    // token after the statement (the import carries no
                    // trailing semicolon); the range preserves that
                    // recorded span exactly.
                    checkEq(3, diagnostic.range().endLine(),
                        "the E6005 range ends at the recorded import span end");
                    checkEq(7, diagnostic.range().endColumn(),
                        "the E6005 range ends at the recorded import span end "
                            + "column");
                    checkEq(46, diagnostic.range().scalarLength(),
                        "the E6005 range covers the recorded import span, got "
                            + diagnostic.range());
                }
                check(stager.stagedSet().relativePaths().isEmpty(),
                    "the provider gap stages nothing: "
                        + stager.stagedSet().relativePaths());
            } finally {
                stager.discard();
            }
        }
    }

    /**
     * The rejected-op emission origins (ISSUE-0750 D4): both shared
     * production emitters refuse an explicitly rejected op and a detached
     * body with the {@link SharedEmissionFailure} signal carrying that
     * op's own copied origin — the production arm then projects exactly
     * that range through the unchanged SHARED_EMITTER_COVERAGE
     * classification. A detached default body with no membership row and a
     * CLASS_DEFAULT op reached by the block walk are the explicit op and
     * detached-body rejection boundaries; the ordinary originless
     * IllegalStateException surface stays unchanged.
     */
    private static void testRejectedOpEmissionOrigins() throws Exception {
        System.out.println("-- rejected-op emission origins: both shared targets carry "
            + "the rejected op's own origin at the op and detached-body boundaries --");

        Fixture fixture = compileProject(
            Map.of("src/app.deal", CLASS_DEFAULT_APP_SOURCE), Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult lowering = lower(fixture);
            check(!lowering.hasErrors(),
                "the exported-class fixture lowers: " + lowering.diagnostics());
            if (lowering.hasErrors()) {
                return;
            }
            ExecutableLoweredProject project = lowering.project();
            LoweredModuleUnit entry = project.modules().get(project.entryModule());
            SemanticOp classDefault = null;
            for (SemanticOp op : entry.ops()) {
                if (op.kind() == SemanticOpKind.CLASS_DEFAULT) {
                    classDefault = op;
                    break;
                }
            }
            check(classDefault != null,
                "the lowered entry module carries the CLASS_DEFAULT op");
            if (classDefault == null) {
                return;
            }
            DiagnosticRange expected = classDefault.origin().span().range();
            check(expected.origin() == RangeOrigin.SOURCE
                    && expected.scalarLength() > 0,
                "the rejected op's own origin is a real SOURCE span: " + expected);
            StructuredBodyTable table = lowering.tables().get(entry.moduleId());
            BlockId initBlock = entry.moduleInit().initBlock();

            // (a) The explicit op rejection: the CLASS_DEFAULT op is reached
            // by the block walk (a doctored membership row) and the emitOp
            // dispatch refuses it.
            Map<BlockId, List<OpId>> walkedRows = new LinkedHashMap<>();
            for (Map.Entry<BlockId, List<OpId>> row : table.blockOps().entrySet()) {
                List<OpId> ops = new ArrayList<>(row.getValue());
                if (row.getKey().equals(initBlock)) {
                    ops.add(classDefault.opId());
                }
                walkedRows.put(row.getKey(), ops);
            }
            StructuredBodyTable walked =
                new StructuredBodyTable(walkedRows, table.opBlocks());

            // (b) The detached-body rejection: the class-default block's
            // membership row is absent, so the detached default function
            // cannot be emitted.
            BlockId defaultBlock =
                ((KindPayload.ClassDefaultPayload) classDefault.payload())
                    .defaultBlock();
            Map<BlockId, List<OpId>> detachedRows =
                new LinkedHashMap<>(table.blockOps());
            detachedRows.remove(defaultBlock);
            StructuredBodyTable detached =
                new StructuredBodyTable(detachedRows, table.opBlocks());

            for (String target : List.of("LuaJIT", "JVM")) {
                SharedEmissionFailure opRejection = emitFailure(target, project,
                    Map.of(entry.moduleId(), walked), lowering, fixture);
                check(opRejection != null,
                    target + ": the walked CLASS_DEFAULT op is rejected");
                if (opRejection != null) {
                    checkEq(expected, opRejection.diagnosticRange(),
                        target + ": the op rejection carries the rejected op's own "
                            + "exact range");
                    check(opRejection.getMessage().contains(
                            "a CLASS_DEFAULT executes only under"),
                        target + ": the op rejection message is the unchanged text: "
                            + opRejection.getMessage());
                }

                SharedEmissionFailure detachedRejection = emitFailure(target, project,
                    Map.of(entry.moduleId(), detached), lowering, fixture);
                check(detachedRejection != null,
                    target + ": the missing default-block membership rejects the "
                        + "detached body");
                if (detachedRejection != null) {
                    checkEq(expected, detachedRejection.diagnosticRange(),
                        target + ": the detached-body rejection carries the "
                            + "CLASS_DEFAULT op's own exact range");
                    check(detachedRejection.getMessage().contains(
                            "has no membership row"),
                        target + ": the detached-body message is the unchanged text: "
                            + detachedRejection.getMessage());
                }
            }

            // The ordinary genuinely originless emitter exception surface is
            // unchanged: it implements no origin carrier.
            IllegalStateException originless =
                new IllegalStateException("an ordinary emitter refusal");
            check(!(originless instanceof deal.semantic.ir.DiagnosticOriginCarrier),
                "an ordinary IllegalStateException carries no origin (the canonical "
                    + "synthetic projection is unchanged)");
        } finally {
            deleteRecursively(fixture.root());
        }
    }

    /** The imported-class fixture: a SHARED_FACTORY CLASS_NEW and class field reads. */
    private static final String IMPORTED_CLASS_LIB_SOURCE = """
        export class Point {
          x: int = 0;
          y: int = 0;
        }
        """;

    private static final String IMPORTED_CLASS_APP_SOURCE = """
        import * as Lib from "./lib";

        export function main(): null {
          let p: Lib.Point = { x: 1, y: 2 };
          let s: int = p.x + p.y;
          return null;
        }
        """;

    /**
     * The remaining identified shared emission guards keep the explicit
     * offending operation's own exact range (ISSUE-0750 D4): the
     * CLASS_NEW factory lookup, the missing field boundary child, the
     * unresolved async external-entry link, the duplicate async entry, and
     * the JVM declaration-kind mismatch. Each seed is the closed fact no
     * checker-valid program produces (a direct corrupted-IR boundary),
     * driven through both shared targets where the guard is shared.
     */
    private static void testIdentifiedSharedGuardOrigins(Fixture crossAsync,
            SemanticLowerer.ProjectLoweringResult asyncLowering, Fixture host,
            SemanticLowerer.ProjectLoweringResult hostLowering) throws Exception {
        System.out.println("-- the remaining identified shared emission guards "
            + "carry the offending op's own exact range --");

        // (a)/(b) The imported-class fixture: the SHARED_FACTORY CLASS_NEW
        // whose factory reference resolves in no registry, and the
        // FIELD_READ whose required boundary child is absent.
        Fixture importedClass = compileProject(new LinkedHashMap<>(Map.of(
                "src/lib.deal", IMPORTED_CLASS_LIB_SOURCE,
                "src/app.deal", IMPORTED_CLASS_APP_SOURCE)), Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(importedClass);
            check(!result.hasErrors(),
                "the imported-class fixture lowers: " + result.diagnostics());
            if (!result.hasErrors()) {
                LoweredModuleUnit entry = result.project()
                    .modules().get(result.project().entryModule());
                SemanticOp sharedFactory = null;
                SemanticOp fieldRead = null;
                for (SemanticOp op : entry.ops()) {
                    if (op.kind() == SemanticOpKind.CLASS_NEW
                            && op.payload()
                                instanceof KindPayload.ClassNewPayload payload
                            && payload.defaultOwner() == DefaultOwner.SHARED_FACTORY) {
                        sharedFactory = op;
                    }
                    if (op.kind() == SemanticOpKind.FIELD_READ && fieldRead == null) {
                        fieldRead = op;
                    }
                }
                check(sharedFactory != null,
                    "the imported-class fixture lowers the SHARED_FACTORY CLASS_NEW");
                check(fieldRead != null,
                    "the imported-class fixture lowers the FIELD_READ");
                if (sharedFactory != null) {
                    KindPayload.ClassNewPayload payload =
                        (KindPayload.ClassNewPayload) sharedFactory.payload();
                    ClassFactoryId bogus = new ClassFactoryId(Long.MAX_VALUE);
                    KindPayload.ClassNewPayload replaced =
                        new KindPayload.ClassNewPayload(payload.classId(),
                            payload.layout(), payload.providedFields(),
                            DefaultOwner.SHARED_FACTORY, payload.classDefaultOpIds(),
                            bogus, payload.fieldBoundaries());
                    assertSharedGuardOrigin(
                        "the unregistered class-factory reference", sharedFactory,
                        "CLASS_NEW " + sharedFactory.opId() + " classFactoryRef "
                            + bogus + " resolves in no module's"
                            + " ClassFactoryRegistry in the closure (producer defect)",
                        replaceUnitOp(result.project(), entry, sharedFactory, replaced),
                        result, importedClass);
                }
                if (fieldRead != null) {
                    assertFieldBoundaryGuardOrigin(fieldRead, result, importedClass);
                }
            }
        } finally {
            deleteRecursively(importedClass.root());
        }

        // (c) The cross-module async fixture: the ASYNC_START(EXTERNAL)
        // whose recorded link names a module outside the closure. The
        // async external-entry guard identifies the start op and carries
        // its own exact range.
        {
            SemanticLowerer.ProjectLoweringResult result = asyncLowering;
            check(!result.hasErrors(),
                "the cross-module async fixture lowers: " + result.diagnostics());
            if (!result.hasErrors()) {
                LoweredModuleUnit entry = result.project()
                    .modules().get(result.project().entryModule());
                SemanticOp asyncStart = null;
                for (SemanticOp op : entry.ops()) {
                    if (op.kind() == SemanticOpKind.ASYNC_START
                            && op.payload()
                                instanceof KindPayload.AsyncStartPayload payload
                            && payload.externalAsyncLink() != null) {
                        asyncStart = op;
                    }
                }
                check(asyncStart != null,
                    "the cross-module async fixture lowers the ASYNC_START(EXTERNAL)");
                if (asyncStart != null) {
                    KindPayload.AsyncStartPayload payload =
                        (KindPayload.AsyncStartPayload) asyncStart.payload();
                    ExternalAsyncLink bogus = new ExternalAsyncLink(
                        new ModuleId("outside"),
                        payload.externalAsyncLink().exportName(),
                        payload.externalAsyncLink().calleeTokenId());
                    KindPayload.AsyncStartPayload replaced =
                        new KindPayload.AsyncStartPayload(payload.callee(),
                            payload.source(), payload.parameterBoundaryMode(),
                            payload.parameterBoundaryOpIds(),
                            payload.completionDescriptor(),
                            payload.returnBoundaryOpId(),
                            payload.hostOperationLabel(), bogus);
                    assertSharedGuardOrigin(
                        "the async link naming a module outside the closure",
                        asyncStart,
                        "ASYNC_START " + asyncStart.opId()
                            + " resolves the external callee module outside"
                            + " outside the session's closure (producer defect)",
                        replaceUnitOp(result.project(), entry, asyncStart, replaced),
                        result, crossAsync);
                }
            }
        }

        // (d) The entry module's duplicated async EXTERNAL_ENTRY: the
        // second recorded entry (a distinct origin) is the operation whose
        // processing detects the collision, so the guard carries its own
        // exact range on both targets.
        {
            SemanticLowerer.ProjectLoweringResult result = asyncLowering;
            check(!result.hasErrors(),
                "the duplicate-async-entry fixture lowers: " + result.diagnostics());
            if (!result.hasErrors()) {
                LoweredModuleUnit entry = result.project()
                    .modules().get(result.project().entryModule());
                SemanticOp asyncEntry = null;
                for (SemanticOp op : entry.ops()) {
                    if (op.kind() == SemanticOpKind.EXTERNAL_ENTRY
                            && op.payload()
                                instanceof KindPayload.ExternalEntryPayload payload
                            && payload.async()) {
                        asyncEntry = op;
                    }
                }
                check(asyncEntry != null,
                    "the async fixture's entry module records the async entry");
                if (asyncEntry != null) {
                    String exportName = ((KindPayload.ExternalEntryPayload)
                        asyncEntry.payload()).exportName();
                    SourceOrigin duplicateOrigin = new SourceOrigin(
                        "duplicate.deal",
                        new SourceSpan("duplicate.deal", 2, 3, 2, 8, 30, 35),
                        SourceOriginKind.USER, new AnchorId(777), null);
                    SemanticOp duplicate = new SemanticOp(
                        new OpId(entry.moduleId(), 900001L), asyncEntry.kind(),
                        duplicateOrigin, new ValueId(900001L), asyncEntry.resultType(),
                        asyncEntry.operands(), asyncEntry.operandTypes(),
                        asyncEntry.payload(), asyncEntry.failurePolicy(),
                        asyncEntry.contract());
                    List<SemanticOp> ops = new ArrayList<>(entry.ops());
                    ops.add(duplicate);
                    Map<ModuleId, LoweredModuleUnit> modules =
                        new LinkedHashMap<>(result.project().modules());
                    modules.put(entry.moduleId(), withUnitOps(entry, ops));
                    ExecutableLoweredProject duplicated =
                        new ExecutableLoweredProject(
                            result.project().semanticProfile(),
                            result.project().interfaceIndex(), modules,
                            result.project().entryModule(),
                            result.project().classSeeds());
                    assertSharedGuardOrigin("the duplicated async entry", duplicate,
                        "the exported declaration '" + entry.moduleId().path()
                            + "#" + exportName + " records two async"
                            + " EXTERNAL_ENTRY ops (producer defect)",
                        duplicated, result, crossAsync);
                }
            }
        }

        // (e) The JVM declaration-kind mismatch: the entry's HOST import is
        // described by a declaration surface of the extern-C kind.
        {
            SemanticLowerer.ProjectLoweringResult result = hostLowering;
            check(!result.hasErrors(),
                "the host-import fixture lowers: " + result.diagnostics());
            if (!result.hasErrors()) {
                LoweredModuleUnit entry = result.project()
                    .modules().get(result.project().entryModule());
                SemanticOp hostImport = null;
                for (SemanticOp op : entry.ops()) {
                    if (op.kind() == SemanticOpKind.MODULE_IMPORT
                            && op.payload()
                                instanceof KindPayload.ModuleImportPayload payload
                            && payload.resolvedModule().equals(HOST_CFG)) {
                        hostImport = op;
                    }
                }
                check(hostImport != null,
                    "the host fixture lowers the HOST MODULE_IMPORT");
                if (hostImport != null) {
                    HostDeclarationSurface.DeclarationFacts facts =
                        host.surface().require(HOST_CFG);
                    Map<ModuleId, HostDeclarationSurface.DeclarationFacts> modules =
                        new LinkedHashMap<>(host.surface().modules());
                    modules.put(HOST_CFG,
                        new HostDeclarationSurface.DeclarationFacts(HOST_CFG,
                            HostDeclarationSurface.DeclarationKind.EXTERN_C,
                            facts.exports(), facts.classes()));
                    HostDeclarationSurface doctored =
                        new HostDeclarationSurface(modules);
                    DiagnosticRange expected = hostImport.origin().span().range();
                    check(expected.origin() == RangeOrigin.SOURCE
                            && expected.scalarLength() > 0,
                        "the HOST MODULE_IMPORT carries a real SOURCE span: "
                            + expected);
                    try {
                        JvmSemanticEmitter.emitProductionProject(result.project(),
                            result.tables(), result.registries(), "FixtureProbe",
                            doctored);
                        fail("the JVM emitter rejects the declaration-kind "
                            + "mismatch");
                    } catch (SharedEmissionFailure rejection) {
                        checkEq("the declaration module '" + HOST_CFG.path()
                                + "' of MODULE_IMPORT " + hostImport.opId()
                                + " is kind "
                                + HostDeclarationSurface.DeclarationKind.EXTERN_C
                                + ": the extern-C declaration load is the FFI"
                                + " child's (fail-closed remnant)",
                            rejection.getMessage(),
                            "the declaration-kind rejection message is the"
                                + " unchanged text");
                        checkEq(expected, rejection.diagnosticRange(),
                            "the declaration-kind rejection carries the HOST"
                                + " MODULE_IMPORT's own exact range");
                    } catch (IllegalStateException other) {
                        fail("the declaration-kind rejection is the"
                            + " source-bearing signal: " + other);
                    }
                }
            }
        }
    }

    @FunctionalInterface
    private interface EmitterDrive {
        void emit() throws Exception;
    }

    /**
     * The absent declaration-surface entry (ISSUE-0750 D4): both emitter
     * boundaries that resolve an import's declaration module hold the
     * identifying MODULE_IMPORT operation, so the fail-closed lookup throws
     * the source-bearing shared signal carrying that import's own exact
     * range — and the production arm's own projection merges it into the
     * unchanged {@code SHARED_EMITTER_COVERAGE} E6005 — instead of the
     * canonical empty-file synthetic anchor. A genuinely originless emitter
     * refusal keeps the canonical synthetic projection.
     */
    private static void testAbsentDeclarationSurfaceEntries(Fixture host,
            SemanticLowerer.ProjectLoweringResult hostLowering, Fixture externC,
            SemanticLowerer.ProjectLoweringResult externLowering) throws Exception {
        System.out.println("-- an absent declaration-surface entry carries the "
            + "identifying MODULE_IMPORT's own exact range on both targets --");

        {
            SemanticLowerer.ProjectLoweringResult result = hostLowering;
            check(!result.hasErrors(),
                "the host-import fixture lowers: " + result.diagnostics());
            if (!result.hasErrors()) {
                LoweredModuleUnit entry = result.project()
                    .modules().get(result.project().entryModule());
                SemanticOp hostImport = null;
                for (SemanticOp op : entry.ops()) {
                    if (op.kind() == SemanticOpKind.MODULE_IMPORT
                            && op.payload()
                                instanceof KindPayload.ModuleImportPayload payload
                            && payload.resolvedModule().equals(HOST_CFG)) {
                        hostImport = op;
                    }
                }
                check(hostImport != null,
                    "the host fixture lowers the HOST MODULE_IMPORT");
                if (hostImport != null) {
                    DiagnosticRange expected = hostImport.origin().span().range();
                    check(expected.origin() == RangeOrigin.SOURCE
                            && expected.scalarLength() > 0,
                        "the HOST MODULE_IMPORT carries a real SOURCE span: "
                            + expected);
                    String expectedMessage =
                        "declaration module '" + HOST_CFG.path()
                            + "' has no declaration-surface entry: the surface covers"
                            + " every host and extern-C declaration module of the"
                            + " compilation (producer defect)";
                    HostDeclarationSurface empty = new HostDeclarationSurface(Map.of());

                    SharedEmissionFailure luaRejection = assertAbsentEntryRejection(
                        "LuaJIT", "the absent declaration-surface entry", expected,
                        expectedMessage, () ->
                            LuaSemanticEmitter.emitProductionProject(result.project(),
                                result.tables(), result.registries(), empty, null));
                    assertAbsentEntryRejection("JVM",
                        "the absent declaration-surface entry", expected,
                        expectedMessage, () ->
                            JvmSemanticEmitter.emitProductionProject(result.project(),
                                result.tables(), result.registries(), "FixtureProbe",
                                empty));
                    if (luaRejection != null) {
                        assertProjectedEmitterOrigin("the absent declaration-surface "
                            + "entry", expected, expectedMessage, result,
                            luaRejection);
                    }
                }
            }
        }

        // The FFI emission input's absent generated-module entry: the same
        // import boundary identifies the extern-C MODULE_IMPORT whose own
        // range must survive the fail-closed lookup.
        {
            SemanticLowerer.ProjectLoweringResult result = externLowering;
            check(!result.hasErrors(),
                "the extern-C fixture lowers: " + result.diagnostics());
            if (!result.hasErrors()) {
                LoweredModuleUnit entry = result.project()
                    .modules().get(result.project().entryModule());
                SemanticOp nativeImport = null;
                for (SemanticOp op : entry.ops()) {
                    if (op.kind() == SemanticOpKind.MODULE_IMPORT
                            && op.payload()
                                instanceof KindPayload.ModuleImportPayload payload
                            && payload.resolvedModule().equals(NATIVE_MATH)) {
                        nativeImport = op;
                    }
                }
                check(nativeImport != null,
                    "the extern-C fixture lowers the extern-C MODULE_IMPORT");
                if (nativeImport != null) {
                    DiagnosticRange expected = nativeImport.origin().span().range();
                    check(expected.origin() == RangeOrigin.SOURCE
                            && expected.scalarLength() > 0,
                        "the extern-C MODULE_IMPORT carries a real SOURCE span: "
                            + expected);
                    String expectedMessage =
                        "the extern-C declaration module '" + NATIVE_MATH.path()
                            + "' carries no generated-module entry in the FFI"
                            + " emission input: the input covers exactly the"
                            + " compile's validated extern-C metadata (a producer"
                            + " defect, never a silently omitted load)";
                    SharedEmissionFailure rejection = assertAbsentEntryRejection(
                        "LuaJIT", "the absent FFI generated-module entry", expected,
                        expectedMessage, () ->
                            LuaSemanticEmitter.emitProductionProject(result.project(),
                                result.tables(), result.registries(),
                                externC.surface(), new FfiEmissionInput(Map.of(),
                                    "manifests")));
                    if (rejection != null) {
                        assertProjectedEmitterOrigin("the absent FFI generated-module "
                            + "entry", expected, expectedMessage, result, rejection);
                    }
                }
            }
        }
    }

    /**
     * One emitter drive of an absent lookup entry: the rejection must be the
     * source-bearing shared signal with the unchanged complete message and
     * the identifying import operation's own exact range.
     */
    private static SharedEmissionFailure assertAbsentEntryRejection(String target,
            String what, DiagnosticRange expected, String expectedMessage,
            EmitterDrive drive) throws Exception {
        SharedEmissionFailure rejection = assertEmitterRejection(target, what, expected, expectedMessage, drive);
        if (rejection != null) {
            check(rejection.getCause() instanceof IllegalStateException
                && expectedMessage.equals(rejection.getCause().getMessage()),
                what + " [" + target + "]: unchanged lookup cause: " + rejection.getCause());
        }
        return rejection;
    }

    /**
     * The production arm's own projection of one source-bearing emitter
     * rejection (the real private {@code sharedEmitterCoverage} step of
     * {@link ProductionProjectEmission#run}, invoked so the regression
     * exercises the production projection instead of re-implementing it),
     * plus the genuinely originless control.
     */
    private static void assertProjectedEmitterOrigin(String what,
            DiagnosticRange expected, String expectedMessage,
            SemanticLowerer.ProjectLoweringResult lowering,
            SharedEmissionFailure rejection) throws Exception {
        String entryPath = lowering.project().entryModule().path();
        CompilerDiagnostic projected = projectEmitterRejection(entryPath, rejection);
        checkEq("E6005", projected.code(),
            what + ": the projected emitter failure is E6005");
        check(projected.message().contains(
                ProductionProjectEmission.SHARED_EMITTER_COVERAGE),
            what + ": the projected failure keeps the SHARED_EMITTER_COVERAGE"
                + " classification: " + projected.message());
        check(projected.message().contains(expectedMessage),
            what + ": the projected E6005 keeps the complete emitter message");
        checkEq(expected, projected.range(),
            what + ": the projected E6005 carries the identifying import's own"
                + " exact range");
        check(projected.range().origin() == RangeOrigin.SOURCE,
            what + ": the projected range is SOURCE, got "
                + projected.range().origin());
        check(projected.notes().isEmpty(),
            what + ": the well-formed carried origin adds no note: "
                + projected.notes());

        CompilerDiagnostic originless = projectEmitterRejection(entryPath,
            new IllegalStateException("an ordinary emitter refusal"));
        check(originless.range().isCanonicalSynthetic(),
            what + ": an ordinary emitter refusal keeps the canonical synthetic"
                + " projection, got " + originless.range());
        checkEq("", originless.range().file(),
            what + ": the originless projection borrows no source file");
    }

    /** The production arm's own projection of one emitter rejection. */
    private static CompilerDiagnostic projectEmitterRejection(String entryModulePath,
            IllegalStateException rejection) throws Exception {
        Method projection = ProductionProjectEmission.class.getDeclaredMethod(
            "sharedEmitterCoverage", String.class, IllegalStateException.class,
            CompilerInvocation.class);
        projection.setAccessible(true);
        return (CompilerDiagnostic) projection.invoke(null, entryModulePath,
            rejection, productionInvocation());
    }

    /**
     * The empty-catalog STDLIB import (ISSUE-0750 D4): the recorded
     * import entry keeps the identifying {@code MODULE_IMPORT} operation,
     * so the fail-closed catalog miss at both emitter boundaries throws
     * the source-bearing shared signal carrying that import's own exact
     * range — and the production arm's own projection merges it into the
     * unchanged {@code SHARED_EMITTER_COVERAGE} E6005 — instead of the
     * canonical empty-file synthetic anchor. The carried range is the
     * span of the import that recorded the failing catalog entry (never
     * another matching import's); a known-file offsetless import keeps
     * its supplied file, and a genuinely originless emitter refusal keeps
     * the canonical synthetic projection.
     */
    private static void testStdlibCatalogMissOrigin() throws Exception {
        System.out.println("-- the empty-catalog STDLIB import guard carries the "
            + "recorded MODULE_IMPORT's own exact range on both targets --");

        Fixture fixture = twoModuleFixture(TWO_STDLIB_APP_SOURCE);
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(fixture);
            check(!result.hasErrors(),
                "the two-stdlib fixture lowers: " + result.diagnostics());
            if (result.hasErrors()) {
                return;
            }
            LoweredModuleUnit entry = result.project()
                .modules().get(result.project().entryModule());
            SemanticOp consoleImport = null;
            SemanticOp stringImport = null;
            for (SemanticOp op : entry.ops()) {
                if (op.kind() != SemanticOpKind.MODULE_IMPORT
                        || !(op.payload()
                            instanceof KindPayload.ModuleImportPayload payload)
                        || payload.kind() != ModuleImportKind.STDLIB) {
                    continue;
                }
                if (payload.resolvedModule().path().equals("std.console")) {
                    consoleImport = op;
                } else if (payload.resolvedModule().path().equals("std.string")) {
                    stringImport = op;
                }
            }
            check(consoleImport != null && stringImport != null,
                "the fixture lowers both STDLIB MODULE_IMPORTs");
            if (consoleImport == null || stringImport == null) {
                return;
            }
            KindPayload.ModuleImportPayload payload =
                (KindPayload.ModuleImportPayload) stringImport.payload();
            ModuleId missing = new ModuleId("std/missing");
            KindPayload.ModuleImportPayload doctoredPayload =
                new KindPayload.ModuleImportPayload(payload.rawSpecifier(),
                    missing, payload.kind(), payload.aliasCells());
            ExecutableLoweredProject doctored = replaceUnitOp(result.project(),
                entry, stringImport, doctoredPayload);
            DiagnosticRange expected = stringImport.origin().span().range();
            DiagnosticRange first = consoleImport.origin().span().range();
            check(expected.origin() == RangeOrigin.SOURCE
                    && expected.scalarLength() > 0,
                "the doctored STDLIB MODULE_IMPORT carries a real SOURCE span: "
                    + expected);
            check(!expected.equals(first),
                "the two STDLIB imports carry distinct spans: " + expected
                    + " vs " + first);

            String expectedMessage = "the STDLIB import of module '"
                + missing.path() + "' resolves no closed catalog row (the catalog"
                + " is the resolution authority — a producer defect)";
            SharedEmissionFailure luaRejection =
                assertStdlibCatalogMissRejection("LuaJIT", expected,
                    expectedMessage, () ->
                        LuaSemanticEmitter.emitProductionProject(doctored,
                            result.tables(), result.registries(),
                            fixture.surface(), null));
            SharedEmissionFailure jvmRejection =
                assertStdlibCatalogMissRejection("JVM", expected, expectedMessage,
                    () -> JvmSemanticEmitter.emitProductionProject(doctored,
                        result.tables(), result.registries(), "FixtureProbe",
                        fixture.surface()));
            if (luaRejection != null) {
                assertProjectedEmitterOrigin("the empty-catalog STDLIB import",
                    expected, expectedMessage, result, luaRejection);
            }
            if (jvmRejection != null) {
                assertProjectedEmitterOrigin("the empty-catalog STDLIB import",
                    expected, expectedMessage, result, jvmRejection);
            }

            // The known-file offsetless import: the synthetic conversion
            // keeps the supplied file and the production projection does
            // too — never the canonical empty-file anchor.
            SourceOrigin offsetlessOrigin = new SourceOrigin("offsetless.deal",
                new SourceSpan("offsetless.deal", 3, 4, 3, 9),
                SourceOriginKind.USER, new AnchorId(4242), null);
            ExecutableLoweredProject offsetless = replaceUnitOp(result.project(),
                entry, stringImport, doctoredPayload, offsetlessOrigin);
            DiagnosticRange offsetlessExpected = offsetlessOrigin.span().range();
            check(offsetlessExpected.origin() == RangeOrigin.SYNTHETIC
                    && offsetlessExpected.file().equals("offsetless.deal")
                    && offsetlessExpected.scalarLength() == 0,
                "the offsetless import keeps its known file under the synthetic"
                    + " conversion: " + offsetlessExpected);
            SharedEmissionFailure offsetlessLua =
                assertStdlibCatalogMissRejection("LuaJIT", offsetlessExpected,
                    expectedMessage, () ->
                        LuaSemanticEmitter.emitProductionProject(offsetless,
                            result.tables(), result.registries(),
                            fixture.surface(), null));
            SharedEmissionFailure offsetlessJvm =
                assertStdlibCatalogMissRejection("JVM", offsetlessExpected,
                    expectedMessage, () ->
                        JvmSemanticEmitter.emitProductionProject(offsetless,
                            result.tables(), result.registries(), "FixtureProbe",
                            fixture.surface()));
            if (offsetlessLua != null) {
                assertProjectedOffsetlessImport(offsetlessExpected, result,
                    offsetlessLua);
            }
            if (offsetlessJvm != null) {
                assertProjectedOffsetlessImport(offsetlessExpected, result,
                    offsetlessJvm);
            }
        } finally {
            deleteRecursively(fixture.root());
        }
    }

    /**
     * ISSUE-0750: a declaration-owned descriptor failure through the real
     * {@code lowerProject} entry — the corrupted checked function
     * signature (the closed fact no checker-valid program produces) fails
     * the project lowering closed with no project, and the shared E6005
     * carries the function declaration's own exact range.
     */
    private static void assertDescriptorRejection(String label,
            SemanticLowerer.ProjectLoweringResult result, DiagnosticRange expected) {
        check(result.project() == null && !result.diagnostics().isEmpty()
            && result.tables().isEmpty() && result.registries().isEmpty(),
            label + " fails closed with no project, tables, or registries");
        check(expected.origin() == RangeOrigin.SOURCE && expected.file().endsWith("src/app.deal")
            && expected.scalarLength() > 0, label + " real declaration SOURCE span");
        if (!result.diagnostics().isEmpty()) {
            CompilerDiagnostic diagnostic = result.diagnostics().get(0);
            checkEq("E6005", diagnostic.code(), label + " diagnostic code");
            check(diagnostic.message().contains(ContainerPayloadDescriptors.DESCRIPTOR_UNREPRESENTABLE),
                label + " unchanged descriptor failure: " + diagnostic.message());
            checkEq(expected, diagnostic.range(), label + " exact declaration-owned range");
        }
    }

    private static void testDeclarationSignatureOrigin() throws Exception {
        System.out.println("-- the corrupted declared function signature keeps "
            + "the declaration's own range through lowerProject --");

        Fixture fixture = compileProject(new LinkedHashMap<>(Map.of(
            "src/app.deal", DECLARATION_ORIGIN_APP_SOURCE)), Map.of());
        try {
            FunctionDeclaration target = null;
            SymbolTable table = null;
            for (CheckedModuleInput module : fixture.checkedProject().modules()) {
                for (StatementNode statement : module.ast().statements()) {
                    if (statement instanceof FunctionDeclaration function
                            && function.name().equals("f")) {
                        target = function;
                        table = module.checks().symbolTable();
                    }
                }
            }
            check(target != null && table != null,
                "the fixture carries the checked function declaration f");
            if (target == null || table == null) {
                return;
            }
            table.remove("f");
            table.define("f", new Symbol.FunctionSymbol("f",
                new Type.Func(List.of(), Type.Error.INSTANCE, false)));

            assertDescriptorRejection("corrupted function signature", lower(fixture), target.span().range());
        } finally {
            deleteRecursively(fixture.root());
        }
    }

    /**
     * ISSUE-0750: the generated @jsonable helper signatures are
     * declaration-owned through the real {@code lowerProject} entry too:
     * a corrupted checked {@code Widget$fromJson}/{@code Widget$toJson}
     * signature fails the project lowering closed with no project, and
     * the shared E6005 carries the owning class declaration's own exact
     * range for both generated signatures.
     */
    private static void testGeneratedHelperDescriptorOrigins() throws Exception {
        System.out.println("-- the corrupted generated @jsonable helper signatures keep "
            + "the class declaration's own range through lowerProject --");

        Fixture prepared = compileProject(Map.of(
            "src/app.deal", GENERATED_HELPER_APP_SOURCE), Map.of());
        try {
            for (String helper : List.of("Widget$fromJson", "Widget$toJson")) {
                Fixture fixture = freshCheckedFacts(prepared);
                ClassDeclaration target = null;
                SymbolTable table = null;
                for (CheckedModuleInput module : fixture.checkedProject().modules()) {
                    for (StatementNode statement : module.ast().statements()) {
                        StatementNode declaration =
                            statement instanceof ExportDeclaration export
                                ? export.declaration() : statement;
                        if (declaration instanceof ClassDeclaration classDeclaration
                                && classDeclaration.isJsonable()) {
                            target = classDeclaration;
                            table = module.checks().symbolTable();
                        }
                    }
                }
                check(target != null && table != null,
                    "the fixture carries the checked @jsonable class Widget");
                if (target == null || table == null) {
                    continue;
                }
                table.remove(helper);
                table.define(helper, new Symbol.FunctionSymbol(helper,
                    new Type.Func(List.of(Type.String.INSTANCE), Type.Error.INSTANCE,
                        false)));

                SemanticLowerer.ProjectLoweringResult result = lower(fixture);
                assertDescriptorRejection(helper, result, target.span().range());
                if (result.diagnostics().isEmpty()) {
                    continue;
                }
                DiagnosticRange range = result.diagnostics().get(0).range();
                check(range.origin() == RangeOrigin.SOURCE
                        && range.file().endsWith("src/app.deal")
                        && range.startLine() == 2 && range.startColumn() == 8
                        && range.endLine() == 2 && range.endColumn() == 36
                        && range.startScalarOffset() == 20
                        && range.endScalarOffset() == 48
                        && range.scalarLength() == 28,
                    "the corrupted " + helper + " failure carries the class "
                        + "declaration's own exact SOURCE range 2:8-2:36 [20,48) "
                        + "length 28; got " + range);
                check(range.equals(target.span().range()),
                    "the " + helper + " range is exactly the class declaration's own "
                        + "converted range; got " + range);
            }
        } finally {
            deleteRecursively(prepared.root());
        }
    }

    /**
     * ISSUE-0750: the for-let counter descriptor derivation is
     * declaration-owned through the real {@code lowerProject} entry too:
     * a corrupted checked counter type fails the project lowering closed
     * with no project, and the shared E6005 carries the counter's own
     * declared type annotation's exact range instead of the canonical
     * empty-file synthetic anchor.
     */
    private static void testForLetCounterDescriptorOrigin() throws Exception {
        System.out.println("-- the corrupted for-let counter descriptor keeps the "
            + "counter's own declared-type range through lowerProject --");

        Fixture fixture = compileProject(new LinkedHashMap<>(Map.of(
            "src/app.deal", FOR_LET_COUNTER_APP_SOURCE)), Map.of());
        try {
            ForStatement target = null;
            SymbolTable scope = null;
            for (CheckedModuleInput module : fixture.checkedProject().modules()) {
                for (StatementNode statement : module.ast().statements()) {
                    StatementNode declaration = statement instanceof ExportDeclaration export
                        ? export.declaration() : statement;
                    if (!(declaration instanceof FunctionDeclaration function)) {
                        continue;
                    }
                    for (StatementNode bodyStatement : function.body().statements()) {
                        if (bodyStatement instanceof ForStatement forStatement) {
                            target = forStatement;
                            scope = module.checks().scopeMap().get(forStatement);
                        }
                    }
                }
            }
            check(target != null && scope != null && scope.containsLocally("i"),
                "the fixture carries the checked for-let counter and its loop scope");
            if (target == null || scope == null || !scope.containsLocally("i")) {
                return;
            }
            scope.remove("i");
            scope.define("i", new Symbol.VariableSymbol("i", Type.Error.INSTANCE, false));
            VariableDeclaration counter = ((ForInit.VarDecl) target.init().get()).decl();
            check(counter.typeAnnotation().isPresent(),
                "the fixture's counter carries its declared type annotation");
            if (counter.typeAnnotation().isEmpty()) {
                return;
            }

            SemanticLowerer.ProjectLoweringResult result = lower(fixture);
            DiagnosticRange expected = counter.typeAnnotation().get().span().range();
            assertDescriptorRejection("corrupted for-let counter", result, expected);
            check(expected.origin() == RangeOrigin.SOURCE
                    && expected.file().endsWith("src/app.deal")
                    && expected.startLine() == 2 && expected.startColumn() == 15
                    && expected.endLine() == 2 && expected.endColumn() == 18
                    && expected.startScalarOffset() == 45
                    && expected.endScalarOffset() == 48
                    && expected.scalarLength() == 3,
                "the counter declares a real SOURCE annotation span 2:15-2:18 "
                    + "[45,48) length 3: " + expected);

        } finally {
            deleteRecursively(fixture.root());
        }
    }

    /**
     * The production arm's own projection of one known-file offsetless
     * rejection: the synthetic shape keeps the supplied file and never
     * borrows the canonical empty one.
     */
    private static void assertProjectedOffsetlessImport(DiagnosticRange expected,
            SemanticLowerer.ProjectLoweringResult lowering,
            SharedEmissionFailure rejection) throws Exception {
        CompilerDiagnostic projected = projectEmitterRejection(
            lowering.project().entryModule().path(), rejection);
        checkEq("E6005", projected.code(),
            "the offsetless import projection is E6005");
        checkEq(expected, projected.range(),
            "the offsetless import projection keeps the known file's synthetic"
                + " range");
        checkEq(expected.file(), projected.range().file(),
            "the offsetless import projection never borrows the empty file");
        check(projected.range().origin() == RangeOrigin.SYNTHETIC,
            "the offsetless import projection stays SYNTHETIC, got "
                + projected.range().origin());
    }

    /**
     * One emitter drive of the empty-catalog STDLIB import: the rejection
     * must be the source-bearing shared signal with the unchanged complete
     * message and the identifying {@code MODULE_IMPORT}'s own exact
     * range.
     */
    private static SharedEmissionFailure assertStdlibCatalogMissRejection(
            String target, DiagnosticRange expected, String expectedMessage,
            EmitterDrive drive) throws Exception {
        return assertEmitterRejection(target, "empty-catalog STDLIB import", expected, expectedMessage, drive);
    }

    private static SharedEmissionFailure assertEmitterRejection(String target, String label,
            DiagnosticRange expected, String expectedMessage, EmitterDrive drive) throws Exception {
        try {
            drive.emit();
            fail(label + " [" + target + "]: emitter must reject");
        } catch (SharedEmissionFailure rejection) {
            checkEq(expectedMessage, rejection.getMessage(), label + " [" + target + "]: complete message");
            checkEq(expected, rejection.diagnosticRange(), label + " [" + target + "]: exact range");
            return rejection;
        } catch (IllegalStateException other) {
            fail(label + " [" + target + "]: rejection must carry origin: " + other);
        }
        return null;
    }

    /**
     * Both shared targets reject the hand-built corrupted fact with the
     * unchanged complete message and the offending op's own exact range.
     */
    private static void assertSharedGuardOrigin(String what, SemanticOp offending,
            String expectedMessage, ExecutableLoweredProject project,
            SemanticLowerer.ProjectLoweringResult lowering, Fixture fixture)
            throws Exception {
        DiagnosticRange expected = offending.origin().span().range();
        check(expected.origin() == RangeOrigin.SOURCE && expected.scalarLength() > 0,
            what + ": the offending op carries a real SOURCE span: " + expected);
        for (String target : List.of("LuaJIT", "JVM")) {
            assertEmitterRejection(target, what, expected, expectedMessage, () -> {
                if ("JVM".equals(target)) {
                    JvmSemanticEmitter.emitProductionProject(project, lowering.tables(),
                        lowering.registries(), "FixtureProbe", fixture.surface());
                } else {
                    LuaSemanticEmitter.emitProductionProject(project, lowering.tables(),
                        lowering.registries(), fixture.surface(), null);
                }
            });
        }
    }

    /**
     * The FIELD_READ whose required boundary child is removed from the
     * unit: the boundary-child lookup identifies the read op and carries
     * its own exact range on both targets.
     */
    private static void assertFieldBoundaryGuardOrigin(SemanticOp fieldRead,
            SemanticLowerer.ProjectLoweringResult lowering, Fixture fixture)
            throws Exception {
        LoweredModuleUnit unit = lowering.project()
            .modules().get(lowering.project().entryModule());
        List<SemanticOp> ops = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            boolean childOfRead = op.kind() == SemanticOpKind.BOUNDARY
                && op.origin() != null
                && fieldRead.opId().equals(op.origin().parentOpId());
            if (!childOfRead) {
                ops.add(op);
            }
        }
        LoweredModuleUnit stripped = withUnitOps(unit, ops);
        Map<ModuleId, LoweredModuleUnit> modules =
            new LinkedHashMap<>(lowering.project().modules());
        modules.put(unit.moduleId(), stripped);
        ExecutableLoweredProject project = new ExecutableLoweredProject(
            lowering.project().semanticProfile(),
            lowering.project().interfaceIndex(), modules,
            lowering.project().entryModule(),
            lowering.project().classSeeds());
        String expectedMessage = fieldRead.kind() + " " + fieldRead.opId()
            + " has no UNTYPED_CLASS_INPUT boundary child: the pinned field-op"
            + " shape carries it parented to the op (producer defect)";
        assertSharedGuardOrigin("the missing field boundary child", fieldRead,
            expectedMessage, project, lowering, fixture);
    }

    /** The project with one unit op rebuilt on a replaced payload. */
    private static ExecutableLoweredProject replaceUnitOp(
            ExecutableLoweredProject project, LoweredModuleUnit unit,
            SemanticOp op, KindPayload replacedPayload) {
        return replaceUnitOp(project, unit, op, replacedPayload, op.origin());
    }

    /**
     * The project with one unit op rebuilt on a replaced payload and a
     * replaced origin (the origin-transport control drives).
     */
    private static ExecutableLoweredProject replaceUnitOp(
            ExecutableLoweredProject project, LoweredModuleUnit unit,
            SemanticOp op, KindPayload replacedPayload,
            SourceOrigin replacedOrigin) {
        deal.semantic.ir.OperationContractSnapshot old = op.contract();
        deal.semantic.ir.OperationContractSnapshot rewired =
            new deal.semantic.ir.OperationContractSnapshot(old.version(),
                old.opKind(), old.resultType(), old.operandTypes(), old.selector(),
                replacedPayload, old.failurePolicy(), old.referencedSemanticIds(),
                old.canonicalDigest());
        List<SemanticOp> ops = new ArrayList<>();
        for (SemanticOp candidate : unit.ops()) {
            if (candidate.opId().equals(op.opId())) {
                ops.add(new SemanticOp(candidate.opId(), candidate.kind(),
                    replacedOrigin, candidate.result(), candidate.resultType(),
                    candidate.operands(), candidate.operandTypes(), replacedPayload,
                    candidate.failurePolicy(), rewired));
            } else {
                ops.add(candidate);
            }
        }
        Map<ModuleId, LoweredModuleUnit> modules =
            new LinkedHashMap<>(project.modules());
        modules.put(unit.moduleId(), withUnitOps(unit, ops));
        return new ExecutableLoweredProject(project.semanticProfile(),
            project.interfaceIndex(), modules, project.entryModule(),
            project.classSeeds());
    }

    private static LoweredModuleUnit withUnitOps(LoweredModuleUnit unit,
            List<SemanticOp> ops) {
        return new LoweredModuleUnit(unit.formatVersion(), unit.semanticProfile(),
            unit.moduleId(), unit.interfaceHash(), unit.loweringContextHash(),
            unit.requiredCapabilities(), unit.constructCoverage(),
            unit.classLayouts(), unit.functions(), unit.moduleInit(),
            unit.exportPlan(), unit.functionBindings(), ops, unit.bodyTable(),
            unit.classFactories(), unit.jsonDefaults(), unit.classBindingFacts());
    }

    /** One emitter rejection of the doctored membership table, or null on no throw. */
    private static SharedEmissionFailure emitFailure(String target,
            ExecutableLoweredProject project,
            Map<ModuleId, StructuredBodyTable> tables,
            SemanticLowerer.ProjectLoweringResult lowering, Fixture fixture)
            throws Exception {
        try {
            if ("JVM".equals(target)) {
                JvmSemanticEmitter.emitProductionProject(project, tables,
                    lowering.registries(), "FixtureProbe", fixture.surface());
            } else {
                LuaSemanticEmitter.emitProductionProject(project, tables,
                    lowering.registries(), fixture.surface(), null);
            }
            return null;
        } catch (SharedEmissionFailure rejection) {
            return rejection;
        }
    }

    private static void testCrossModuleAsyncEmission(Fixture cross, Fixture same)
            throws Exception {
        checkAsyncEmission(cross, "cross-module", 42);
        checkAsyncEmission(same, "same-module", 1);
    }

    private static void checkAsyncEmission(Fixture fixture, String label, int expected)
            throws Exception {
        for (Backend backend : List.of(Backend.LUAJIT, Backend.JVM)) {
            String artifact = backend == Backend.LUAJIT ? "app.lua" : "App.java";
            Path out = fixture.root().resolve(backend == Backend.LUAJIT ? "out-luajit" : "out-jvm");
            PublicationStager stager = PublicationStager.forRoot(out);
            try {
                ProductionProjectEmission.Result result = emit(fixture, backend, stager, false);
                check(result.emitted(), label + " " + backend + " emits: " + result.diagnostics());
                checkEq(artifact, result.artifactRelativePath(), label + " entry artifact");
                checkEq(1L, stager.stagedSet().relativePaths().stream()
                    .filter(path -> path.endsWith(backend == Backend.LUAJIT ? ".lua" : ".java")
                        && !path.startsWith("std/") && !path.equals("deal/runtime.lua")).count(),
                    label + " one staged project artifact");
                if (!result.emitted()) {
                    continue;
                }
                stager.publish();
            } finally {
                stager.discard();
            }
            check(Files.isRegularFile(out.resolve(artifact)), label + " published artifact exists");
            String source = Files.readString(out.resolve(artifact), StandardCharsets.UTF_8);
            check(!source.contains("EXTERNAL_ASYNC_CALL") && !source.contains("SharedM"),
                label + " uses project-local async entries");
            ProcessOutcome run;
            if (backend == Backend.LUAJIT) {
                if (label.equals("cross-module")) {
                    check(source.contains("__asyncEntries[\"" + LIB.path() + "#getValue\"]"),
                        label + " includes callee async export entry");
                }
                writeFileIn(fixture.root(), "deal/runtime.lua",
                    Files.readString(Path.of("deal/runtime.lua"), StandardCharsets.UTF_8));
                writeFileIn(fixture.root(), "probe.lua", """
                    local surfaces = dofile("out-luajit/app.lua")
                    assert(type(surfaces) == "table", "entry surface")
                    local worker = surfaces.worker
                    assert(type(worker) == "table" and worker.__kind == "function", "async export")
                    local handle = worker.f()
                    assert(type(handle) == "table" and handle.__kind == "async", "async operation")
                    require("deal.runtime").async_step(handle)
                    assert(handle.__done and handle.__result == %d, "await completion")
                    print("PROBE|ASYNC-RESULT|" .. tostring(handle.__result))
                    """.formatted(expected));
                run = runProcess(List.of("luajit", "probe.lua"), fixture.root());
            } else {
                String driver = "AsyncWorkerRunner_"
                    + java.util.UUID.randomUUID().toString().replace("-", "");
                writeFileIn(out, driver + ".java", """
                    import deal.codegen.jvm.JvmRuntime;
                    public final class %s {
                      public static void main(String[] args) {
                        App.main(new String[0]);
                        JvmRuntime.Table surface = App.EXPORT_SURFACES.get("app");
                        JvmRuntime.FunctionValue worker = (JvmRuntime.FunctionValue) surface.read("worker");
                        System.out.println("PROBE|ASYNC-RESULT|" + worker.fn.invoke(new Object[0]));
                      }
                    }
                    """.formatted(driver));
                Path classes = fixture.root().resolve("async-classes");
                Files.createDirectories(classes);
                String classpath = absoluteClasspath();
                ProcessOutcome compiled = compileJvm(List.of("javac", "--release", "25", "-proc:none",
                    "-cp", classpath, "-d", classes.toString(), out.resolve(artifact).toString(),
                    out.resolve(driver + ".java").toString()), fixture.root());
                checkEq(0, compiled.exitCode(), label + " artifact compiles: " + compiled.output());
                if (compiled.exitCode() != 0) {
                    continue;
                }
                run = runProcess(List.of("java", "-cp", classes + java.io.File.pathSeparator + classpath,
                    driver), fixture.root());
            }
            checkEq(0, run.exitCode(), label + " " + backend + " runs: " + run.output());
            checkEq("PROBE|ASYNC-RESULT|" + expected + "\n", run.stdout(), label + " await result");
            checkEq("", run.stderr(), label + " no trace output");
        }
    }

    // =========================================================================
    // 6. The source-map emission: real coordinates, explicit flag, rollback
    // =========================================================================

    private static void testSourceMapEmission(Fixture fixture, Fixture failing,
            DefaultLuaRun defaultRun) throws Exception {
        System.out.println("-- the default compile emits source comments and one "
            + "sidecar per constituent module with real generated/source "
            + "coordinates on both targets, the accepted --source-map spelling "
            + "is identical, and a failing run preserves the previous set "
            + "byte-for-byte --");

        // (1) LuaJIT: the accepted --source-map spelling emits the v1.3
        // default debugging artifacts — the artifact's source comments and
        // each constituent module's sidecar mapping agree with the real
        // artifact and source coordinates, and no warning text is printed.
        Path out = fixture.root().resolve("source-map-out");
        String lua;
        Sidecar appMap;
        Sidecar libMap;
        {
            PublicationStager stager = PublicationStager.forRoot(out);
            try {
                String stderr = emitCapturingStderr(fixture, Backend.LUAJIT,
                    stager, true).stderr();
                checkEq("", stderr,
                    "the accepted --source-map run prints no warning");
                check(stager.stagedSet().artifact("app.lua").isPresent(),
                    "the source-map run stages the entry module's chunk");
                check(stager.stagedSet().artifact("app.deal.map.json").isPresent(),
                    "the entry module's sidecar stages with the chunk");
                check(stager.stagedSet().artifact("lib.deal.map.json").isPresent(),
                    "the constituent library module's sidecar stages too");
                lua = new String(stager.stagedSet().artifact("app.lua")
                    .orElseThrow().content(), StandardCharsets.UTF_8);
                appMap = parseSidecar(new String(stager.stagedSet()
                    .artifact("app.deal.map.json").orElseThrow().content(),
                    StandardCharsets.UTF_8));
                libMap = parseSidecar(new String(stager.stagedSet()
                    .artifact("lib.deal.map.json").orElseThrow().content(),
                    StandardCharsets.UTF_8));
                stager.publish();
            } finally {
                stager.discard();
            }
        }
        List<String> luaLines = lua.lines().toList();
        check(appMap.generated().endsWith("/source-map-out/app.lua"),
            "the entry sidecar's generated path is the shared artifact: "
                + appMap.generated());
        check(libMap.generated().equals(appMap.generated()),
            "every constituent sidecar maps the same shared artifact: "
                + libMap.generated());
        verifySidecarCoordinates(appMap, luaLines, APP_SOURCE, "-- ",
            "src/app.deal");
        verifySidecarCoordinates(libMap, luaLines, LIB_SOURCE, "-- ",
            "src/lib.deal");
        check(hasMappingAtSourceLine(appMap, 4),
            "the entry sidecar maps the main body's return null");
        check(hasMappingAtSourceLine(appMap, 8),
            "the entry sidecar maps the label body's return");
        check(hasMappingAtSourceLine(libMap, 2),
            "the library sidecar maps add's return a + b");
        check(hasMappingAtSourceLine(libMap, 6),
            "the library sidecar maps twice's return x * 2");
        checkEq(countSourceComments(lua, "-- "),
            appMap.mappings().size() + libMap.mappings().size(),
            "every recorded mapping is one emitted source comment of the chunk");

        // (1b) The cross-module call: the caller's sidecar maps only the
        // caller's own source text — the recorded initiating call site for
        // the emitted foreign annotation checks — while the provider's
        // parameter annotations map in the provider's own sidecar. The
        // per-row comment/coordinate agreement of
        // verifySidecarCoordinates rejects a foreign-coordinate row.
        Fixture cross = twoModuleFixture(SYNC_CALL_APP_SOURCE);
        try {
            Path crossOut = cross.root().resolve("cross-map-out");
            PublicationStager crossStager = PublicationStager.forRoot(crossOut);
            String chunk;
            Sidecar crossAppMap;
            Sidecar crossLibMap;
            try {
                ProductionProjectEmission.Result crossResult =
                    emit(cross, Backend.LUAJIT, crossStager, false);
                check(crossResult.emitted(),
                    "the cross-module call fixture emits: "
                        + crossResult.diagnostics());
                chunk = new String(crossStager.stagedSet()
                    .artifact("app.lua").orElseThrow().content(),
                    StandardCharsets.UTF_8);
                crossAppMap = parseSidecar(new String(crossStager.stagedSet()
                    .artifact("app.deal.map.json").orElseThrow().content(),
                    StandardCharsets.UTF_8));
                crossLibMap = parseSidecar(new String(crossStager.stagedSet()
                    .artifact("lib.deal.map.json").orElseThrow().content(),
                    StandardCharsets.UTF_8));
            } finally {
                crossStager.discard();
            }
            verifySidecarCoordinates(crossAppMap, chunk.lines().toList(),
                SYNC_CALL_APP_SOURCE, "-- ", "src/app.deal");
            verifySidecarCoordinates(crossLibMap, chunk.lines().toList(),
                LIB_SOURCE, "-- ", "src/lib.deal");
            check(hasMappingAtSourceLine(crossAppMap, 4),
                "the caller's sidecar maps the initiating call expression's line");
            check(crossLibMap.mappings().stream().anyMatch(mapping ->
                    mapping.sourceLine() == 1 && mapping.sourceColumn() == 21),
                "the provider's sidecar maps its own parameter annotation: "
                    + crossLibMap.mappings());
        } finally {
            deleteRecursively(cross.root());
        }

        // (2) The default compile (no explicit spelling) publishes the same
        // commented artifact, runtime and stdlib bytes and its own per-source
        // sidecars with the same real coordinates: the accepted --source-map
        // request selects nothing, so there is no no-map compile left.
        Path defaultOut = fixture.root().resolve("lua-out");
        checkEq("", defaultRun.stderr(),
            "the default compile prints no warning");
        check(defaultRun.paths().containsAll(Set.of("app.lua",
                "app.deal.map.json", "lib.deal.map.json",
                "deal/runtime.lua", "std/console.lua")),
            "the default compile stages the chunk, one sidecar per "
                + "constituent source, the runtime and the stdlib copies: "
                + defaultRun.paths());
        check(countSourceComments(defaultRun.source(), "-- ") > 0,
            "the default compile's artifact carries the source comments");
        checkEq(snapshotWithoutMapSidecars(defaultOut),
            snapshotWithoutMapSidecars(out),
            "the default and explicitly requested compiles publish identical "
                + "artifact, runtime and stdlib bytes");
        Sidecar defaultAppMap = parseSidecar(Files.readString(
            defaultOut.resolve("app.deal.map.json"), StandardCharsets.UTF_8));
        Sidecar defaultLibMap = parseSidecar(Files.readString(
            defaultOut.resolve("lib.deal.map.json"), StandardCharsets.UTF_8));
        checkEq(appMap.mappings(), defaultAppMap.mappings(),
            "the default entry sidecar carries the same mappings as the "
                + "explicitly requested one");
        checkEq(libMap.mappings(), defaultLibMap.mappings(),
            "the default library sidecar carries the same mappings as the "
                + "explicitly requested one");

        // (3) JVM: the class source carries the same source comments and the
        // two sidecars map its real generated coordinates.
        Path jvmOut = fixture.root().resolve("jvm-map-out");
        String javaSource;
        Sidecar jvmAppMap;
        Sidecar jvmLibMap;
        {
            PublicationStager stager = PublicationStager.forRoot(jvmOut);
            try {
                String stderr = emitCapturingStderr(fixture, Backend.JVM,
                    stager, true).stderr();
                checkEq("", stderr,
                    "the accepted --source-map JVM run prints no warning");
                check(stager.stagedSet().artifact("App.java").isPresent(),
                    "the JVM source-map run stages the one entry class");
                javaSource = new String(stager.stagedSet().artifact("App.java")
                    .orElseThrow().content(), StandardCharsets.UTF_8);
                jvmAppMap = parseSidecar(new String(stager.stagedSet()
                    .artifact("app.deal.map.json").orElseThrow().content(),
                    StandardCharsets.UTF_8));
                jvmLibMap = parseSidecar(new String(stager.stagedSet()
                    .artifact("lib.deal.map.json").orElseThrow().content(),
                    StandardCharsets.UTF_8));
            } finally {
                stager.discard();
            }
        }
        List<String> javaLines = javaSource.lines().toList();
        verifySidecarCoordinates(jvmAppMap, javaLines, APP_SOURCE, "// ",
            "src/app.deal");
        verifySidecarCoordinates(jvmLibMap, javaLines, LIB_SOURCE, "// ",
            "src/lib.deal");
        checkEq(countSourceComments(javaSource, "// "),
            jvmAppMap.mappings().size() + jvmLibMap.mappings().size(),
            "every recorded mapping is one emitted source comment of the class");

        // (4) Rollback: a failing run into a root holding a previously
        // published set (artifact and maps) leaves that set byte-identical.
        Path rollbackOut = fixture.root().resolve("rollback-out");
        writeFileIn(rollbackOut, "app.lua", "-- previous artifact\n");
        writeFileIn(rollbackOut, "app.deal.map.json", "-- previous map\n");
        writeFileIn(rollbackOut, "lib.deal.map.json", "-- previous map\n");
        writeFileIn(rollbackOut, "deal/runtime.lua", "-- previous runtime\n");
        Map<String, String> before = snapshotTree(rollbackOut);
        {
            PublicationStager stager = PublicationStager.forRoot(rollbackOut);
            ProductionProjectEmission.Result result;
            try {
                result = emit(failing, Backend.LUAJIT, stager, true);
                check(!result.emitted(),
                    "the failing source-map run emits nothing");
                check(stager.stagedSet().relativePaths().isEmpty()
                        || stager.stagedSet().relativePaths().stream()
                            .noneMatch(path -> path.endsWith(".deal.map.json")),
                    "the failing source-map run stages no sidecar");
            } finally {
                stager.discard();
            }
            checkEq(before, snapshotTree(rollbackOut),
                "the failing source-map run preserves the previous set "
                    + "(artifact and maps) byte-identical");
        }
    }

    /** One parsed source-map sidecar (the spec's pinned document shape). */
    private record Sidecar(String source, String generated,
                           List<MapEntry> mappings) {
    }

    /** One mapping entry of a sidecar. */
    private record MapEntry(int generatedLine, int generatedColumn,
                            int sourceLine, int sourceColumn) {
    }

    /**
     * Parses one {@code .deal.map.json} document through the strict canonical
     * JSON transport and verifies its pinned fields (version, source,
     * generated, mapping shape).
     */
    private static Sidecar parseSidecar(String json) {
        CanonicalJson.Obj root;
        try {
            root = (CanonicalJson.Obj) CanonicalJson.parse(json);
        } catch (RuntimeException malformed) {
            fail("the sidecar is not valid JSON: " + malformed);
            return new Sidecar("", "", List.of());
        }
        Map<String, CanonicalJson.Value> fields = new LinkedHashMap<>();
        for (CanonicalJson.Entry entry : root.entries()) {
            fields.put(entry.key(), entry.value());
        }
        checkEq(1, ((CanonicalJson.Int) fields.get("version")).value(),
            "the sidecar version is the pinned 1");
        String source = ((CanonicalJson.Str) fields.get("source")).value();
        String generated = ((CanonicalJson.Str) fields.get("generated")).value();
        List<MapEntry> mappings = new ArrayList<>();
        for (CanonicalJson.Value item
                : ((CanonicalJson.Arr) fields.get("mappings")).items()) {
            Map<String, CanonicalJson.Value> entryFields = new LinkedHashMap<>();
            for (CanonicalJson.Entry entry
                    : ((CanonicalJson.Obj) item).entries()) {
                entryFields.put(entry.key(), entry.value());
            }
            mappings.add(new MapEntry(
                ((CanonicalJson.Int) entryFields.get("generatedLine")).value(),
                ((CanonicalJson.Int) entryFields.get("generatedColumn")).value(),
                ((CanonicalJson.Int) entryFields.get("sourceLine")).value(),
                ((CanonicalJson.Int) entryFields.get("sourceColumn")).value()));
        }
        return new Sidecar(source, generated, mappings);
    }

    /** Whether one sidecar records a mapping at the given source line. */
    private static boolean hasMappingAtSourceLine(Sidecar sidecar,
            int sourceLine) {
        return sidecar.mappings().stream()
            .anyMatch(mapping -> mapping.sourceLine() == sourceLine);
    }

    /**
     * Verifies one sidecar against the real artifact and the module's real
     * source file: every mapped position is inside both texts, and the mapped
     * artifact line is exactly the source comment carrying the mapped source
     * coordinates under the module's one display name.
     */
    private static void verifySidecarCoordinates(Sidecar sidecar,
            List<String> artifactLines, String sourceText, String commentMarker,
            String expectedSourceSuffix) {
        check(sidecar.source().endsWith(expectedSourceSuffix),
            "the sidecar names the constituent source file: " + sidecar.source());
        check(!sidecar.mappings().isEmpty(),
            "the sidecar of " + sidecar.source() + " carries mappings");
        List<String> sourceLines = sourceText.lines().toList();
        for (MapEntry mapping : sidecar.mappings()) {
            check(mapping.generatedLine() >= 1
                    && mapping.generatedLine() <= artifactLines.size(),
                "the mapped generated line is inside the artifact: " + mapping);
            check(mapping.sourceLine() >= 1
                    && mapping.sourceLine() <= sourceLines.size(),
                "the mapped source line addresses the real source file: "
                    + mapping);
            if (mapping.sourceLine() >= 1
                    && mapping.sourceLine() <= sourceLines.size()) {
                int lineLength = sourceLines.get(mapping.sourceLine() - 1).length();
                check(mapping.sourceColumn() >= 1
                        && mapping.sourceColumn() <= lineLength + 1,
                    "the mapped source column addresses real source text: "
                        + mapping);
            }
            if (mapping.generatedLine() >= 1
                    && mapping.generatedLine() <= artifactLines.size()) {
                String generatedLine = artifactLines.get(
                    mapping.generatedLine() - 1);
                checkEq(commentMarker + sidecar.source() + ":"
                        + mapping.sourceLine() + ":" + mapping.sourceColumn(),
                    generatedLine.strip(),
                    "the mapped artifact line is the mapping's source comment");
                checkEq(generatedLine.indexOf(commentMarker) + 1,
                    mapping.generatedColumn(),
                    "the mapped generated column addresses the comment start");
            }
        }
    }

    /** The emitted source comments of one artifact text. */
    private static int countSourceComments(String artifact, String commentMarker) {
        int count = 0;
        for (String line : artifact.lines().toList()) {
            String stripped = line.strip();
            if (stripped.startsWith(commentMarker)
                    && stripped.matches(".*/[^ ]*\\.deal:[0-9]+:[0-9]+")) {
                count++;
            }
        }
        return count;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** The published/previous artifact set of one tree: path → bytes. */
    private static Map<String, String> snapshotTree(Path root) throws Exception {
        Map<String, String> snapshot = new java.util.TreeMap<>();
        if (!Files.exists(root)) {
            return snapshot;
        }
        try (var walk = Files.walk(root)) {
            for (Path file : walk.sorted().toList()) {
                if (Files.isRegularFile(file)) {
                    snapshot.put(root.relativize(file).toString().replace('\\', '/'),
                        java.util.Arrays.toString(Files.readAllBytes(file)));
                }
            }
        }
        return snapshot;
    }

    /**
     * The published tree's artifact bytes without the sidecar JSON: a
     * sidecar embeds its own output-root-relative generated path (the
     * pinned project-relative spelling), so two compiles of the same
     * project into differently named roots have byte-equal artifacts but
     * path-bearing sidecar documents.
     */
    private static Map<String, String> snapshotWithoutMapSidecars(Path root)
            throws Exception {
        Map<String, String> snapshot = snapshotTree(root);
        snapshot.keySet().removeIf(path -> path.endsWith(".deal.map.json"));
        return snapshot;
    }

    private static final String EMITTER_LIB = """
        export class Counter { n: int = 0; }
        export function add(a: int, b: int): int { return a + b; }
        export function twice(x: int): int { return x * 2; }
        """;
    private static final String EMITTER_APP = """
        import * as lib from "./lib";
        export function main(): null { return null; }
        export function label(): string { return "app"; }
        """;
    private static final String CONSOLE_EMITTER_APP = """
        import * as lib from "./lib";
        import * as console from "std/console";
        export function main(): null { console.log("PROBE|MAIN-ONCE"); return null; }
        export function label(): string { return "app"; }
        """;

    private record PublishedEmitter(Fixture fixture, SemanticLowerer.ProjectLoweringResult lowering,
            Backend backend, Path out, String source) implements AutoCloseable {
        public void close() { deleteRecursively(fixture.root()); }
    }

    /**
     * The v1.3 default source-map recorder of one published emission: the
     * same per-module display names the production arm builds
     * ({@code ProductionProjectEmission#sourceMapRecorder}), so a direct
     * public-emitter call reproduces the published artifact's source
     * comments byte-exactly.
     */
    private static SourceMapRecorder productionMapRecorder(PublishedEmitter published) {
        Map<ModuleId, String> displayNames = new LinkedHashMap<>();
        for (CheckedModuleInput module
                : published.fixture().checkedProject().modules()) {
            displayNames.put(module.moduleId(), SourceMapPaths.sourceDisplay(
                module.sourceId(), published.out()));
        }
        return new SourceMapRecorder(displayNames);
    }

    private static PublishedEmitter publishedEmitter(Backend backend, String lib, String app) throws Exception {
        Fixture fixture = compileProject(Map.of("src/lib.deal", lib, "src/app.deal", app),
            Map.of(), backend, productionInvocation());
        try {
            var lowering = lower(fixture);
            check(lowering.project() != null && lowering.diagnostics().isEmpty(),
                backend + " emitter fixture lowers: " + lowering.diagnostics());
            if (lowering.project() == null) {
                throw new AssertionError("emitter fixture lowering failed");
            }
            Path out = fixture.root().resolve("out");
            String source = Files.readString(out.resolve(backend == Backend.JVM ? "App.java" : "app.lua"));
            return new PublishedEmitter(fixture, lowering, backend, out, source);
        } catch (Throwable failure) {
            deleteRecursively(fixture.root());
            throw failure;
        }
    }

    private static ProcessOutcome runPublished(PublishedEmitter fixture, String probe) throws Exception {
        if (fixture.backend() == Backend.LUAJIT) {
            if (probe == null) {
                return runProcess(List.of("luajit", "app.lua"), fixture.out());
            }
            writeFileIn(fixture.out(), "probe.lua", probe);
            return runProcess(List.of("luajit", "probe.lua"), fixture.out());
        }
        return runJavaEmission(fixture.out(), "App", fixture.source(), probe);
    }

    private static ProcessOutcome runJavaEmission(Path out, String className, String source,
            String probe) throws Exception {
        Path classes = Files.createTempDirectory(out, "classes-");
        writeFileIn(out, className + ".java", source);
        List<String> command = new ArrayList<>(List.of("javac", "--release", "25", "-proc:none",
            "-sourcepath", "", "-cp", absoluteClasspath(), "-d", classes.toString(),
            out.resolve(className + ".java").toString()));
        String driver = "ProductionEmitterProbe_" + java.util.UUID.randomUUID().toString().replace("-", "");
        if (probe != null) {
            writeFileIn(out, driver + ".java", "import deal.codegen.jvm.JvmRuntime;\npublic class "
                + driver + " {\n" + probe + "\n}\n");
            command.add(out.resolve(driver + ".java").toString());
        }
        ProcessOutcome compiled = compileJvm(command, out);
        checkEq(0, compiled.exitCode(), "production emitter javac: " + compiled.output());
        if (compiled.exitCode() != 0) {
            throw new AssertionError(compiled.output());
        }
        return runProcess(List.of("java", "-ea", "-cp",
            classes + java.io.File.pathSeparator + absoluteClasspath(), probe == null ? className : driver), out);
    }

    private static void assertEmitterRun(String label, ProcessOutcome run, int exit, String stdout) {
        checkEq(exit, run.exitCode(), label + " exit: " + run.output());
        checkEq(stdout, run.stdout(), label + " stdout");
        checkEq("", run.stderr(), label + " stderr");
    }

    private static void testProductionEmitterLanes() throws Exception {
        for (Backend backend : List.of(Backend.LUAJIT, Backend.JVM)) {
            try (PublishedEmitter quiet = publishedEmitter(backend, EMITTER_LIB, EMITTER_APP)) {
                assertEmitterRun(backend + " quiet artifact", runPublished(quiet, null), 0, "");
                testEmitterContracts(quiet);
                assertEmitterRun(backend + " export surfaces", runPublished(quiet, surfaceProbe(quiet)), 0, "PROBE-OK\n");
                if (backend == Backend.JVM) {
                    var module = JvmSemanticEmitter.emitProductionModule(
                        quiet.lowering().project().modules().get(LIB), quiet.lowering().tables().get(LIB), false, "Library");
                    checkEq("Library", module.className(), "non-entry public module API class name");
                    check(!module.source().contains("$asyncExportHost"), "non-entry module has no async host reference");
                    check(module.source().contains("System.out.println(\"DEAL_ERROR_CODE: \" + e.code);"),
                        "non-entry module retains error terminal");
                    assertEmitterRun("non-entry direct module API", runJavaEmission(quiet.out(),
                        module.className(), module.source(), null), 0, "");
                }
            }
            for (String source : List.of(SHADOWING_SOURCE, DYNAMIC_TABLE_SOURCE)) {
                try (PublishedEmitter fixture = publishedEmitter(backend, EMITTER_LIB, source)) {
                    assertEmitterRun(backend + " binding/evaluation regression", runPublished(fixture, null), 0, "");
                }
            }
            try (PublishedEmitter overflow = publishedEmitter(backend, EMITTER_LIB,
                    "export function main(): null { let x: int = 2147483647 + 1; return null; }")) {
                assertEmitterRun(backend + " overflow terminal", runPublished(overflow, null), 1, "DEAL_ERROR_CODE: E8004\n");
            }
            try (PublishedEmitter host = publishedEmitter(backend,
                    ErrorRethrowTransportProductionTest.HOST_ERROR_LIB_SOURCE,
                    ErrorRethrowTransportProductionTest.HOST_ERROR_APP_SOURCE)) {
                ProcessOutcome run = runPublished(host, hostProbe(host));
                checkEq(0, run.exitCode(), backend + " host probe exit: " + run.output());
                int before = ErrorRethrowTransportProductionTest.productionHostFailures();
                ErrorRethrowTransportProductionTest.assertProductionHostTransport(run.stdout(), run.stderr(), backend == Backend.JVM);
                checkEq(before, ErrorRethrowTransportProductionTest.productionHostFailures(), backend + " complete host observations");
            }
        }
        try (PublishedEmitter console = publishedEmitter(Backend.LUAJIT, EMITTER_LIB, CONSOLE_EMITTER_APP)) {
            testEmitterContracts(console);
            assertEmitterRun("console main once", runPublished(console, null), 0, "PROBE|MAIN-ONCE\n");
        }
        String libMain = """
            import * as console from "std/console";
            export function add(a: int, b: int): int { return a + b; }
            export function main(): null { console.log("PROBE|LIB-MAIN"); return null; }
            """;
        try (PublishedEmitter nonentry = publishedEmitter(Backend.LUAJIT, libMain, CONSOLE_EMITTER_APP)) {
            for (ModuleId module : List.of(LIB, new ModuleId("app"))) {
                checkEq(1L, nonentry.lowering().project().modules().get(module).ops().stream()
                    .filter(op -> op.kind() == SemanticOpKind.ENTRY_INVOKE).count(), "nonvacuous main delegation " + module);
            }
            checkEq(1, countOccurrences(nonentry.source(), "\"START\", \"ENTRY_INVOKE\""), "only entry delegation emitted");
            assertEmitterRun("non-entry main skipped", runPublished(nonentry, null), 0, "PROBE|MAIN-ONCE\n");
        }
        StringBuilder growth = new StringBuilder();
        for (int i = 0; i < 201; i++) {
            growth.append("export function f").append(String.format("%03d", i)).append("(): null { return null; }\n");
        }
        growth.append("export function main(): null { f000(); f100(); f200(); return null; }");
        try (PublishedEmitter fixture = publishedEmitter(Backend.LUAJIT, EMITTER_LIB, growth.toString())) {
            assertEmitterRun("201-export chunk loads", runPublished(fixture, null), 0, "");
        }
        for (boolean ffi : List.of(false, true)) {
            try {
                if (ffi) {
                    LuaSemanticEmitter.emitProductionProject(null, Map.of(), Map.of(), new HostDeclarationSurface(Map.of()),
                        new FfiEmissionInput(Map.of(), ""));
                } else {
                    LuaSemanticEmitter.emitProductionProject(null, Map.of(), Map.of(), new HostDeclarationSurface(Map.of()));
                }
                fail("null project rejected by Lua production API, FFI=" + ffi);
            } catch (NullPointerException expected) {
                passed++;
            }
        }
    }

    private static void testEmitterContracts(PublishedEmitter fixture) throws Exception {
        var result = fixture.lowering();
        var project = result.project();
        boolean jvm = fixture.backend() == Backend.JVM;
        String source = fixture.source();
        ModuleId app = new ModuleId("app");
        checkEq(List.of(LIB, app), List.copyOf(project.modules().keySet()), "closure dependency order");
        checkEq(app, project.entryModule(), "project entry identity");
        String repeated = jvm ? JvmSemanticEmitter.emitProductionProject(project, result.tables(), result.registries(),
            "App", fixture.fixture().surface(), productionMapRecorder(fixture)).source()
            : LuaSemanticEmitter.emitProductionProject(project,
                result.tables(), result.registries(), fixture.fixture().surface(),
                (FfiEmissionInput) null, productionMapRecorder(fixture));
        checkEq(source, repeated, "production API repeats published artifact byte-identically");
        int walks = source.indexOf(jvm ? "    MODULE = \"lib\";" : "__dealMain = function()");
        int libWalk = source.indexOf(jvm ? "    MODULE = \"lib\";" : "__module = \"lib\"", walks);
        int appWalk = source.indexOf(jvm ? "    MODULE = \"app\";" : "__module = \"app\"", walks);
        check(walks >= 0 && libWalk >= walks && appWalk > libWalk, "emitted dependency walk order");
        int store = jvm ? -1 : source.indexOf("local __factories = {}");
        if (!jvm) {
            check(store >= 0 && store < walks, "bounded factory store before walks");
            check(source.contains("__traceMode = false") && source.contains("__ev = function() end")
                && !source.contains("io.stderr:write(\"R|"), "Lua production trace disabled");
            check(source.contains("error(__mainErr, 0)"), "Lua non-DEAL terminal rethrows");
            check(LuaSemanticEmitter.emitProject(project, result.tables(), result.registries())
                .contains("io.stderr:write(\"R|success|null\\n\")"), "Lua trace entry remains distinct");
            for (boolean tables : List.of(false, true)) {
                try {
                    LuaSemanticEmitter.emitProductionProject(project, tables ? null : result.tables(),
                        tables ? result.registries() : null, fixture.fixture().surface());
                    fail("Lua null tables/registries rejected");
                } catch (NullPointerException expected) {
                    passed++;
                }
            }
        } else {
            check(source.contains("EXPORT_SURFACES = JvmRuntime.EXPORT_SURFACES;"), "runtime registry forwarding");
            check(source.contains("JvmRuntime.setTraceEnabled(false);") && !source.contains("setTraceEnabled(true)")
                && !source.contains("R|") && !source.contains("DEAL_ERROR_SNAPSHOT"), "JVM production trace disabled");
            var trace = JvmSemanticEmitter.emitProject(project, result.tables(), result.registries());
            check(trace.className().startsWith("SharedM") && trace.source().contains("R|"), "JVM trace entry remains distinct");
            var custom = JvmSemanticEmitter.emitProductionProject(project, result.tables(), result.registries(),
                "CustomApp", fixture.fixture().surface());
            checkEq("CustomApp", custom.className(), "custom class name forwarding");
            check(custom.source().contains("public final class CustomApp {"), "custom class declaration");
            var module = JvmSemanticEmitter.emitProductionModule(project.modules().get(app), result.tables().get(app), true, "AppModule");
            checkEq("AppModule", module.className(), "entry module API name");
            check(module.source().contains("JvmRuntime.setTraceEnabled(false);"), "entry module API production mode");
        }
        int publication = -1;
        int defaults = 0;
        for (var unit : project.modules().values()) {
            for (var function : unit.functions().values()) {
                String factory = jvm ? "static JvmRuntime.FunctionValue F" + function.functionId().id() + "("
                    : "__factories.F" + function.functionId().id() + " = function(";
                int at = source.indexOf(factory);
                check(jvm ? at >= 0 : at > store && at < walks, "closure factory " + function.functionId());
            }
            for (SemanticOp op : unit.ops()) {
                if (op.payload() instanceof KindPayload.ExportPublishPayload export) {
                    long id = export.value().id();
                    String write = jvm ? "exportSurface(\"" + unit.moduleId().path() + "\").write(\"" + export.name() + "\", v" + id + ");"
                        : "__exportSurfaces[\"" + unit.moduleId().path() + "\"][\"" + export.name()
                            + "\"] = {__kind = \"function\", sig = \"" + export.descriptor().canonicalSpecText()
                            + "\", f = __unfn(S.v" + id + "), __val = S.v" + id + "}";
                    int at = source.indexOf(write);
                    check(at > publication && source.indexOf(write, at + 1) < 0, "unique ordered publication " + export.name());
                    publication = at;
                }
                if (!jvm && op.kind() == SemanticOpKind.CLASS_DEFAULT) {
                    defaults++;
                    int at = source.indexOf("__factories.D" + op.opId().id() + " = function()");
                    check(at > store && at < walks, "detached default before init");
                }
                if (op.kind() == SemanticOpKind.ENTRY_INVOKE) {
                    checkEq(1L, unit.ops().stream().filter(call -> call.kind() == SemanticOpKind.CALL
                        && op.opId().equals(call.origin().parentOpId())).count(), "one CALL delegated per entry");
                }
            }
        }
        if (!jvm) { check(defaults > 0, "detached default coverage nonvacuous"); }
        checkEq(1L, project.modules().get(app).ops().stream().filter(op -> op.kind() == SemanticOpKind.ENTRY_INVOKE).count(), "one entry main");
        checkEq(0L, project.modules().get(LIB).ops().stream().filter(op -> op.kind() == SemanticOpKind.ENTRY_INVOKE).count(), "quiet library has no main");
    }

    private static String surfaceProbe(PublishedEmitter fixture) {
        boolean jvm = fixture.backend() == Backend.JVM;
        StringBuilder probe = new StringBuilder(jvm ? """
            public static void main(String[] args) {
              App.main(new String[0]);
              var surfaces = App.EXPORT_SURFACES;
              assert surfaces == JvmRuntime.EXPORT_SURFACES;
              assert new java.util.ArrayList<>(surfaces.keySet()).equals(java.util.List.of("lib", "app"));
              assert !JvmRuntime.moduleInitNeeded("lib") && !JvmRuntime.moduleInitNeeded("app");
            """ : """
            local returned = dofile("app.lua")
            assert(returned == __exportSurfaces.app)
            local count = 0
            for _ in pairs(__exportSurfaces) do count = count + 1 end
            assert(count == 2)
            """);
        for (var unit : fixture.lowering().project().modules().values()) {
            List<KindPayload.ExportPublishPayload> exports = unit.ops().stream()
                .filter(op -> op.kind() == SemanticOpKind.EXPORT_PUBLISH)
                .map(op -> (KindPayload.ExportPublishPayload) op.payload()).toList();
            String name = unit.moduleId().path();
            if (jvm) {
                String names = exports.stream().map(export -> "\"" + export.name() + "\"")
                    .collect(java.util.stream.Collectors.joining(","));
                probe.append("assert new java.util.ArrayList<>(surfaces.get(\"").append(name)
                    .append("\").entries.keySet()).equals(java.util.List.of(").append(names).append("));\n");
            } else {
                probe.append("local count = 0\nfor _ in pairs(__exportSurfaces.").append(name)
                    .append(") do count = count + 1 end\nassert(count == ").append(exports.size()).append(")\n");
            }
            for (var export : exports) {
                String access = jvm ? "surfaces.get(\"" + name + "\").read(\"" + export.name() + "\")"
                    : "__exportSurfaces." + name + "." + export.name();
                String spec = export.descriptor().canonicalSpecText();
                if (jvm) {
                    probe.append("assert ").append(access).append(" instanceof JvmRuntime.FunctionValue;\nassert ((JvmRuntime.FunctionValue)")
                        .append(access).append(").spec.equals(\"").append(spec).append("\");\n");
                } else {
                    probe.append("assert(type(").append(access).append(") == 'table' and ").append(access)
                        .append(".__kind == 'function' and ").append(access).append(".sig == '")
                        .append(spec).append("')\n");
                    probe.append("assert(type(").append(access).append(".f) == 'function')\n");
                }
            }
        }
        probe.append(jvm ? """
              assert java.util.Objects.equals(5L, ((JvmRuntime.FunctionValue) surfaces.get("lib").read("add")).fn.invoke(new Object[]{2L, 3L}));
              assert java.util.Objects.equals(8L, ((JvmRuntime.FunctionValue) surfaces.get("lib").read("twice")).fn.invoke(new Object[]{4L}));
              assert java.util.Objects.equals("app", ((JvmRuntime.FunctionValue) surfaces.get("app").read("label")).fn.invoke(new Object[0]));
              App.dealMain();
              assert surfaces == App.EXPORT_SURFACES && surfaces.size() == 2;
              assert !JvmRuntime.moduleInitNeeded("lib") && !JvmRuntime.moduleInitNeeded("app");
              assert surfaces.containsKey("lib") && surfaces.containsKey("app");
              System.out.println("PROBE-OK");
            }
            """ : "assert(__exportSurfaces.lib.add.f(2, 3) == 5)\nprint('PROBE-OK')\n");
        return probe.toString();
    }

    private static String hostProbe(PublishedEmitter fixture) {
        if (fixture.backend() == Backend.LUAJIT) {
            return """
                local rt = require("deal.runtime")
                local s = dofile("app.lua")
                local function field(v) return v == nil and "-" or tostring(v) end
                local function cause(e) return type(e) == "table" and e.cause and field(e.cause.code) or "-" end
                local function report(prefix, ok, e)
                  if ok then print(prefix .. "none|" .. tostring(e))
                  elseif type(e) == "table" then
                    print(prefix .. field(e.code) .. "|" .. field(e.m) .. "|" .. field(e.o)
                      .. "|" .. field(e.e) .. "|" .. field(e.a) .. "|" .. field(e.f) .. "|" .. cause(e))
                  else print(prefix .. "raw|" .. tostring(e)) end
                end
                for _, name in ipairs({"run", "reraise", "leafFail", "swallow", "arun", "areraise", "overflow", "replaced", "mutate"}) do
                  local ok, e = pcall(s[name].f)
                  if ok and type(e) == "table" and e.__kind == "async" then ok, e = pcall(rt.async_step, e) end
                  report("HOST-ERR|" .. name .. "|", ok, e)
                end
                local step
                step = {__kind = "function", sig = "(int)->int", f = function(n) return s.recur.f(n, step) end}
                report("HOST-REENTRY|recur|", pcall(s.recur.f, 2, step))
                print("HOST-EMPTY|recur|" .. ((__frames == nil or next(__frames) == nil) and "-" or table.concat(__frames, ",")))
                local ok, e = pcall(s.leafFail.f)
                print("HOST-CLEAN|leafFail|" .. (not ok and type(e) == "table" and field(e.f) or "none"))
                ok, e = pcall(s.mutate.f)
                if not ok and type(e) == "table" and e.cause then
                  local c = e.cause
                  print("HOST-CAUSE|mutate|" .. field(e.code) .. "|" .. field(e.m) .. "|" .. field(c.code)
                    .. "|" .. field(c.m) .. "|" .. field(c.o) .. "|" .. field(c.e) .. "|" .. field(c.a)
                    .. "|" .. field(c.f) .. "|" .. cause(c))
                else print("HOST-CAUSE|mutate|none") end
                ok, e = pcall(s.alias.f)
                print("HOST-ID|alias|" .. (ok and type(e) == "boolean" and tostring(e) or "missing"))
                """;
        }
        var unit = fixture.lowering().project().modules().get(new ModuleId("app"));
        List<Long> async = new ArrayList<>();
        for (String name : List.of("arun", "areraise")) {
            var entries = unit.ops().stream().filter(op -> op.payload() instanceof KindPayload.ExternalEntryPayload entry
                && entry.async() && entry.exportName().equals(name)).toList();
            checkEq(1, entries.size(), "host async entry " + name);
            if (entries.size() != 1) { throw new AssertionError("missing host async entry " + name); }
            async.add(entries.get(0).opId().id());
        }
        return """
            static JvmRuntime.Table surface;
            static Object invoke(String name, Object... args) { return ((JvmRuntime.FunctionValue) surface.read(name)).fn.invoke(args); }
            static String field(String v) { return v == null ? "-" : v; }
            static JvmRuntime.DealError capture(Runnable action) {
              try { action.run(); } catch (JvmRuntime.DealError error) {
                return error;
              }
              throw new AssertionError("expected host failure");
            }
            static String fields(JvmRuntime.DealError e) {
              return e.code + "|" + e.msg + "|" + field(e.origin) + "|" + field(e.expected) + "|"
                + field(e.actual) + "|" + field(e.frames) + "|" + (e.cause == null ? "-" : e.cause.code);
            }
            static void drive(String name, long id) {
              JvmRuntime.DealError e = capture(() -> {
                try { App.class.getMethod("ae" + id, String.class, boolean.class, Object[].class).invoke(null, "-", true, new Object[0]); }
                catch (java.lang.reflect.InvocationTargetException error) {
                  if (error.getCause() instanceof JvmRuntime.DealError deal) throw deal;
                  throw new AssertionError(error.getCause());
                }
                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
              });
              System.out.println("HOST-ERR|" + name + "|" + fields(e));
            }
            public static void main(String[] args) {
              App.main(new String[0]);
              surface = App.EXPORT_SURFACES.get("app");
              for (String name : new String[]{"run", "reraise", "leafFail", "swallow", "overflow", "replaced", "mutate"}) {
                try { System.out.println("HOST-ERR|" + name + "|none|" + invoke(name)); }
                catch (JvmRuntime.DealError error) { System.out.println("HOST-ERR|" + name + "|" + fields(error)); }
              }
              drive("arun", %dL);
              drive("areraise", %dL);
              System.out.println("HOST-ID|alias|" + invoke("alias"));
              JvmRuntime.DealError original = capture(() -> invoke("run"));
              var value = original.reify();
              var echo = capture(() -> invoke("echo", value));
              System.out.println("HOST-ID|echo|" + (echo == original) + "|" + (echo.reify() == value)
                + "|" + echo.code + "|" + echo.origin + "|" + (echo.cause == null ? "-" : echo.cause.code));
              JvmRuntime.FunctionValue[] step = new JvmRuntime.FunctionValue[1];
              step[0] = new JvmRuntime.FunctionValue(a -> invoke("recur", a[0], step[0]), "(int)->int", "(int)->int", null);
              System.out.println("HOST-REENTRY|recur|" + fields(capture(() -> invoke("recur", 2L, step[0]))));
              System.out.println("HOST-EMPTY|recur|" + JvmRuntime.framesText());
              System.out.println("HOST-CLEAN|leafFail|" + field(capture(() -> invoke("leafFail")).frames));
              var mutated = capture(() -> invoke("mutate"));
              var cause = mutated.cause;
              System.out.println("HOST-CAUSE|mutate|" + mutated.code + "|" + mutated.msg + "|"
                + (cause == null ? "none" : fields(cause)));
            }
            """.formatted(async.get(0), async.get(1));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + 1);
        }
        return count;
    }

    private record ProcessOutcome(int exitCode, String stdout, String stderr) {

        String output() {
            return "stdout=" + stdout.replace("\n", "\\n")
                + " stderr=" + stderr.replace("\n", "\\n");
        }
    }

    private static String absoluteClasspath() {
        StringBuilder resolved = new StringBuilder();
        for (String entry : System.getProperty("java.class.path", "")
                .split(java.io.File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            if (resolved.length() > 0) {
                resolved.append(java.io.File.pathSeparator);
            }
            resolved.append(Path.of(entry).toAbsolutePath().normalize());
        }
        return resolved.toString();
    }

    private static ProcessOutcome compileJvm(List<String> command, Path workDir)
            throws Exception {
        JvmCompilation.Result result = JvmCompilation.compile(command, workDir);
        return new ProcessOutcome(result.exitCode(), result.stdout(), result.stderr());
    }

    private static ProcessOutcome runProcess(List<String> command, Path workDir)
            throws Exception {
        BoundedProcess.BoundedResult result = BoundedProcess.runCaptured(
            command, workDir);
        check(!result.stdoutTruncated() && !result.stderrTruncated(), "complete production process capture");
        return new ProcessOutcome(result.exitCode(), result.stdout(), result.stderr());
    }

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

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Production Project Emission Tests (ISSUE-0642) ===\n");
        Fixture fixture = null;
        Fixture cross = null;
        Fixture same = null;
        Fixture failing = null;
        Fixture host = null;
        Fixture externC = null;
        Fixture js = null;
        try {
            boolean node = nodeAvailable();
            System.out.println(node
                ? "-- node available: the real JS artifact drives run --"
                : "-- node NOT available: the real JS artifact drives are"
                    + " skipped --");
            fixture = twoModuleFixture(APP_SOURCE);
            js = jsFixture();
            testJsProjectEmission(js, node);
            testJsEntryFailureStatus(node);
            DefaultLuaRun defaultRun = testOneArtifactPerTarget(fixture);
            testOneMainProbe();
            testJvmToolchain(fixture);
            failing = adapterSourceFixture();
            testAtomicFailures(failing);
            host = hostFixture();
            externC = externCFixture();
            cross = asyncFixture(ASYNC_LIB_SOURCE, CROSS_ASYNC_APP_SOURCE);
            SemanticLowerer.ProjectLoweringResult hostLowering = lower(host);
            SemanticLowerer.ProjectLoweringResult externLowering = lower(externC);
            SemanticLowerer.ProjectLoweringResult asyncLowering = lower(cross);
            testHostImportGuard(host, externC);
            testFfiProviderGapOrigin(externC);
            testRejectedOpEmissionOrigins();
            testIdentifiedSharedGuardOrigins(cross, asyncLowering, host, hostLowering);
            testAbsentDeclarationSurfaceEntries(host, hostLowering, externC, externLowering);
            testStdlibCatalogMissOrigin();
            testDeclarationSignatureOrigin();
            testGeneratedHelperDescriptorOrigins();
            testForLetCounterDescriptorOrigin();
            same = asyncFixture(ASYNC_LIB_SOURCE, SAME_ASYNC_APP_SOURCE);
            testCrossModuleAsyncEmission(cross, same);
            testSourceMapEmission(fixture, failing, defaultRun);
            testProductionEmitterLanes();
            testJsExternCRejection(externC);
            testJsFailClosedSeam(js);
            testJsDumpDeferral();
            testJsRuntimeMissing();
            testJsStdlibOverride();
        } finally {
            deleteRecursively(js == null ? null : js.root());
            deleteRecursively(externC == null ? null : externC.root());
            deleteRecursively(host == null ? null : host.root());
            deleteRecursively(failing == null ? null : failing.root());
            deleteRecursively(same == null ? null : same.root());
            deleteRecursively(cross == null ? null : cross.root());
            deleteRecursively(fixture == null ? null : fixture.root());
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== Production Project Emission Tests Passed ===");
    }
}
