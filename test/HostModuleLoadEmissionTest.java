package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmBackend;
import deal.codegen.jvm.JvmSemanticEmitter;
import deal.codegen.lua.LuaSemanticEmitter;
import deal.identity.CanonicalModuleIdentity;
import deal.module.CompilationOrchestrator;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CheckedProjectInput;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.DescriptorService;
import deal.semantic.HostDeclarationSurface;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticOracle;
import deal.semantic.SemanticRequirementManifest;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.ActualKind;
import deal.semantic.ir.BoundaryContext;
import deal.semantic.ir.BoundaryExecutor;
import deal.semantic.ir.BoundaryFailure;
import deal.semantic.ir.BoundaryOutcome;
import deal.semantic.ir.BoundaryValueView;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ClassLayout;
import deal.semantic.ir.ConstructionProvider;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.FailureArmId;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ModuleImportKind;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.UnicodeScalars;
import deal.test.conformance.JvmCompilation;
import deal.types.Type;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HostModuleLoadEmissionTest {

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
    // Fixtures
    // =========================================================================

    private static final String PROBE_SPECIFIER = "host/abi_probe";
    private static final String CFG_SPECIFIER = "host/cfg";
    private static final String ORDER_SPECIFIER = "host/order";
    private static final ClassId ORDER_CONFIG =
        new ClassId("$external/host/order", "Config");

    /** Declared optional fields are accepted independently of any host defaults export. */
    private static final String ORDER_DECLARATION_SOURCE = """
        export class Config {
          port: number;
          fallback?: number;
          peers?: number[];
        }

        export function ping(): string;
        """;

    /** The construction literal with the provided field order substituted. */
    private static final String ORDER_APP_TEMPLATE = """
        import * as cfg from "host/order";

        export function main(): null {
          let c: cfg.Config = {%s};
          if (!has(c.fallback) || !has(c.peers)) { throw {code: "presence", message: "missing"}; }
          let fallback: number | null = c.fallback;
          if (fallback !== null) {
            if (fallback !== 3) { throw {code: "value", message: "fallback"}; }
          } else { throw {code: "value", message: "null fallback"}; }
          return null;
        }
        """;

    /** Any legacy default-hook lookup must fail rather than silently provide authority. */
    private static final String ORDER_LUA = """
        return setmetatable({
          ping = function() return "pong" end,
          Config = { [__dealCarrierKeys.kind] = "class", [__dealCarrierKeys.classname] = "@$external/host/order/Config" },
        }, {__index = function(_, key)
          if key == "Config_defaults" then error("retired defaults lookup") end
        end})
        """;

    private static final String MISSING_SPECIFIER = "host/missing_export";
    private static final String ARRAY_SPECIFIER = "host/array_probe";
    private static final String ERROR_SPECIFIER = "host/error_probe";

    /** The host ABI declaration: scalar, array, function, and async positions. */
    private static final String ABI_DECLARATION_SOURCE = """
        export function ping(): string;
        export function echoInt(v: number): number;
        export function badReturn(): number;
        export function join(sep: string, parts: string[]): string;
        export function apply(f: (x: number) => number, v: number): number;
        export async function fetchValue(): string;
        export function nullable(v: string | null): string | null;
        """;

    /** The declared-class declaration (the synthesized record and array carriers). */
    private static final String CFG_DECLARATION_SOURCE = """
        export class Endpoint {
          path: string;
        }

        export class ServerConfig {
          port: number;
          endpoint: Endpoint;
          tags?: string[];
          note?: string | null;
        }

        export function describe(s: ServerConfig): string;
        """;

    private static final String MISSING_DECLARATION_SOURCE = """
        export function missing(): string;
        export function ping(): string;
        """;

    private static final String MISSING_APP_SOURCE = """
        // spec: Modules, declarations, standard library, and host ABI
        // description: A declared host export missing from the raw implementation is a load-time error (E8011).
        // expected: runtime-error E8011
        // features: modules, host-abi, runtime-errors

        import * as host from "host/missing_export";

        export function test_host_missing_export(): string {
          return "unreachable";
        }

        export function main(): null {
          return null;
        }
        """;

    /** The ABI-importing application (no host call: the call arms are a later slice). */
    private static final String ABI_APP_SOURCE = """
        import * as probe from "host/abi_probe";
        import * as cfg from "host/cfg";

        export function main(): null {
          return null;
        }
        """;

    /**
     * The declared-array declaration (ISSUE-0765): a declared
     * {@code string[]} parameter and return, so the emitted wrapper's
     * declared element-shape carriers are driven directly.
     */
    private static final String ARRAY_DECLARATION_SOURCE = """
        export function echoArray(parts: string[]): string[];
        export function badArray(): string[];
        """;

    /** The declared-array-importing application (the wrappers are driven directly). */
    private static final String ARRAY_APP_SOURCE = """
        import * as arr from "host/array_probe";

        export function main(): null {
          return null;
        }
        """;

    private static final String ARRAY_JAVA_HOST = """
        final class HostArray_probe {
          public static Object echoArray($DealRt.__StringArray parts) { return parts; }
          public static Object badArray() {
            return new $DealRt.__StringArray(
                new String[] { "ok", loneSurrogate() });
          }
          private static String loneSurrogate() {
            return new String(new char[] { (char) 0xD800 });
          }
        }
        """;

    /** The two-alias import of one host module (one load, one surface value). */
    private static final String TWO_ALIAS_APP_SOURCE = """
        import * as first from "host/abi_probe";
        import * as second from "host/abi_probe";
        import * as cfg from "host/cfg";

        export function main(): null {
          return null;
        }
        """;

    /**
     * The host-read fixture (ISSUE-0797): two source aliases of one host
     * module, both read (one direct call each), so the two occurrences and
     * their read registrations are exercised together.
     */
    private static final String HOST_READ_APP_SOURCE = """
        import * as first from "host/abi_probe";
        import * as second from "host/abi_probe";

        export function main(): null {
          let a: number = first.echoInt(1);
          let b: number = second.echoInt(2);
          return null;
        }
        """;

    private static final String MISSING_DECLARATION_APP_SOURCE = MISSING_APP_SOURCE;

    /**
     * The table-parameter host declaration (ISSUE-0830): the declared
     * parameter is the table value, so a DEAL function value must take the
     * closed host parameter cell before the raw host is called.
     */
    private static final String TABLE_PARAM_SPECIFIER = "host/table_param";
    private static final String TABLE_PARAM_DECLARATION_SOURCE = """
        export function consume(t: table): string;
        """;

    /** The function value held in a table and read at the declared table cell. */
    private static final String TABLE_PARAM_APP_SOURCE = """
        import * as host from "host/table_param";

        export function test_function_value_at_table_param(): null {
          let holder: table = {};
          holder.f = function(): number { return 1; };
          let out: string = host.consume(holder.f);
          return null;
        }

        export function main(): null {
          return null;
        }
        """;

    /** The deployed host: reaching it prints, so a crossing is observable. */
    private static final String TABLE_PARAM_LUA = """
        return {
          consume = function(t) print("HOST-CALLED") return "ADMITTED" end,
        }
        """;

    /** The deployed LuaJIT host implementation of host/abi_probe. */
    private static final String PROBE_LUA = """
        _HOST_PROBE_LOADS = (_HOST_PROBE_LOADS or 0) + 1
        return {
          ping = function() return "pong" end,
          echoInt = function(v) return v end,
          badReturn = function() return "junk" end,
          join = function(sep, parts) return table.concat(parts, sep) end,
          apply = function(f, v) return f(v) + 100 end,
          fetchValue = function()
            local rt = require("deal.runtime")
            return rt.async_start(function() return "fetched" end)
          end,
          nullable = function(v) return v end,
        }
        """;

    private static final String CFG_LUA = """
        _HOST_CFG_LOADS = (_HOST_CFG_LOADS or 0) + 1
        local rt = require("deal.runtime")
        return {
          Endpoint = { [__dealCarrierKeys.kind] = "class", [__dealCarrierKeys.classname] = "@$external/host/cfg/Endpoint" },
          Endpoint_defaults = { path = "/" },
          ServerConfig = { [__dealCarrierKeys.kind] = "class", [__dealCarrierKeys.classname] = "@$external/host/cfg/ServerConfig" },
          ServerConfig_defaults = { port = 8080, endpoint = rt.__MISSING, tags = rt.__MISSING, note = rt.__MISSING },
          describe = function(s) return s.endpoint.path .. ":" .. tostring(s.port) end,
        }
        """;

    /** The raw implementation of host/missing_export (omits the declared export). */
    private static final String MISSING_LUA = """
        return {
          ping = function() return "pong" end,
        }
        """;

    /**
     * The declared Error host surface (ISSUE-0787 DescriptorV13 host
     * control): the canonical builtin Error atom as a declared parameter
     * and return in both directions, a recursive Error field of a declared
     * host class, and the superseded identity aliases a host could spell.
     */
    private static final String ERROR_DECLARATION_SOURCE = """
        export function echo(e: Error): Error;
        export function make(): Error;
        export function aliased(): Error;
        export function builtinAliased(): Error;
        export class Holder {
          e: Error;
        }
        export function wrap(e: Error): Holder;
        export function observe(v: Holder): Error;
        """;

    /** The imported Error-host drive: the round-trip summary and the aliases. */
    private static final String ERROR_APP_SOURCE = """
        import * as err from "host/error_probe";

        export function drive(): string {
          let seed: Error = { code: "E_H", message: "hm" };
          let echoed: Error = err.echo(seed);
          let made: Error = err.make();
          let holder: err.Holder = err.wrap(seed);
          let nested: Error = err.observe(holder);
          return echoed.code + ":" + echoed.message + ":" + made.code + ":"
            + made.message + ":" + nested.code + ":" + nested.message;
        }

        export function aliasRejected(): Error {
          return err.aliased();
        }

        export function builtinAliasRejected(): Error {
          return err.builtinAliased();
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The deployed LuaJIT host implementation of host/error_probe: every
     * crossing position admits the loaded matcher's runtime Error instance
     * shape only (the declared code/message fields), transforms the value
     * so the DEAL side observes the host's own input, and returns the
     * runtime representation — the two superseded v1.2 identity aliases
     * included, so the wrapper's canonical matcher must refuse them.
     */
    private static final String ERROR_LUA = """
        local function runtimeError(e)
          if type(e) ~= "table" or e[__dealCarrierKeys.kind] ~= "class"
              or e[__dealCarrierKeys.classname] ~= "Error" then
            return nil
          end
          return e
        end
        return {
          echo = function(e)
            if runtimeError(e) == nil then
              error("host echo: the parameter is not the runtime Error instance", 0)
            end
            return { [__dealCarrierKeys.kind] = "class", [__dealCarrierKeys.classname] = "Error",
                     code = "R:" .. e.code, message = e.message }
          end,
          make = function()
            return { [__dealCarrierKeys.kind] = "class", [__dealCarrierKeys.classname] = "Error",
                     code = "E_M", message = "mm" }
          end,
          aliased = function()
            return { [__dealCarrierKeys.kind] = "class", [__dealCarrierKeys.classname] = "@/Error",
                     code = "E_A", message = "am" }
          end,
          builtinAliased = function()
            return { [__dealCarrierKeys.kind] = "class", [__dealCarrierKeys.classname] = "@$builtin/Error",
                     code = "E_B", message = "bm" }
          end,
          Holder = { [__dealCarrierKeys.kind] = "class",
                     [__dealCarrierKeys.classname] = "@$external/host/error_probe/Holder" },
          Holder_defaults = { e = nil },
          wrap = function(e)
            if runtimeError(e) == nil then
              error("host wrap: the parameter is not the runtime Error instance", 0)
            end
            return { [__dealCarrierKeys.kind] = "class",
                     [__dealCarrierKeys.classname] = "@$external/host/error_probe/Holder",
                     e = { [__dealCarrierKeys.kind] = "class", [__dealCarrierKeys.classname] = "Error",
                           code = "W:" .. e.code, message = e.message } }
          end,
          observe = function(v)
            if type(v) ~= "table" or v[__dealCarrierKeys.kind] ~= "class"
                or v[__dealCarrierKeys.classname] ~= "@$external/host/error_probe/Holder" then
              error("host observe: the parameter is not the runtime Holder "
                .. "instance", 0)
            end
            local inner = v.e
            if runtimeError(inner) == nil then
              error("host observe: the nested field is not the runtime Error "
                .. "instance", 0)
            end
            return { [__dealCarrierKeys.kind] = "class", [__dealCarrierKeys.classname] = "Error",
                     code = "O:" .. inner.code, message = inner.message }
          end,
        }
        """;

    // =========================================================================
    // The host-carrier declaration metadata fixture (ISSUE-0849)
    // =========================================================================

    /**
     * The host-carrier metadata project's HOST declaration: a class with a
     * nested class field, an optional array field, an optional nullable
     * field, and a field of a class declared by a declaration module that
     * only this declaration file imports, a class with a declared
     * function-typed field, a synchronous declared host function, and an
     * asynchronous declared host function.
     */
    private static final String CARRIER_DECLARATION_SOURCE = """
        import * as base from "host/base";

        export class Endpoint {
          path: string;
        }

        export class ServerConfig {
          port: number;
          endpoint: Endpoint;
          tags?: string[];
          note?: string | null;
          root: base.Base;
        }

        export class Bundle {
          label: string;
          action: (x: number) => number;
        }

        export function describe(s: ServerConfig): string;
        export async function fetchConfig(id: number): ServerConfig;
        export function apply(f: (x: number) => number, v: number): number;
        """;

    /**
     * The host-carrier metadata project's declaration module that only the
     * other {@code .d.deal} imports (finding 1's shape): no HOST import op
     * of any lowered unit names it, so its class identity is reachable only
     * through the compile's class-registration seeds.
     */
    private static final String CARRIER_BASE_DECLARATION_SOURCE = """
        export class Base {
          id: number;
          run: (x: string, y: string) => boolean;
        }

        export function baseOnly(x: string, y: string, z: string): boolean;
        """;
    /** A second typed host declaration with a scalar field layout. */
    private static final String CARRIER_NATIVE_DECLARATION_SOURCE = """
        export class Pair {
          left: number;
          right: number;
        }

        export function probe(): number;
        """;

    /** The host-carrier metadata project's own module: a DEAL class and an exported function value. */
    private static final String CARRIER_UTIL_SOURCE = """
        export class Point {
          x: number;
          y: number;
        }

        export function bump(x: number): number {
          return x + 1;
        }
        """;

    /**
     * The host-carrier metadata project's application: a DEAL class with a
     * function-typed field, a chunk-created function value, an imported
     * function value, literals of the declared host, host, and DEAL
     * classes, and an imported stdlib module whose catalog rows are
     * surface-populated but never read (finding 2's shape).
     */
    private static final String CARRIER_APP_SOURCE = """
        import * as cfg from "host/carrier";
        import * as native from "native/sample";
        import * as util from "./util";
        import * as json from "std/json";

        export class Widget {
          name: string;
          run: (x: number) => number;
        }

        export function main(): null {
          let step: (x: number) => number = function(x: number): number { return x + 1; };
          let accept: (x: string, y: string) => boolean =
            function(x: string, y: string): boolean { return true; };
          let widget: Widget = { name: "w", run: step };
          let config: cfg.ServerConfig = { port: 1, endpoint: { path: "/" },
            root: { id: 2, run: accept } };
          let point: util.Point = { x: 1, y: 2 };
          let pair: native.Pair = { left: 4, right: 9.5 };
          let bundle: cfg.Bundle = { label: "b", action: step };
          let bumped: number = util.bump(1);
          return null;
        }
        """;

    /**
     * The host-free control project: no HOST import and no host cell
     * boundary, so the artifact binds no host runtime and carries none of
     * the host-carrier prelude names.
     */
    private static final String HOST_FREE_APP_SOURCE = """
        export class Point {
          x: number;
          y: number;
        }

        export function main(): null {
          let point: Point = { x: 1, y: 2 };
          return null;
        }
        """;

    private static final String CARRIER_METADATA_DEAL_JSON = """
        {"languageVersion": "1.3", "backend": "luajit",
         "moduleRoots": ["src"], "output": "out",
         "externals": {
           "host/carrier": {"declaration": "bindings/carrier.d.deal"},
           "host/base": {"declaration": "bindings/base.d.deal"},
           "native/sample": {"declaration": "bindings/sample.d.deal"}}}
        """;

    private record Fixture(
        Path root,
        CheckedProjectInput checkedProject,
        ProjectInterfaceIndex index,
        List<SemanticRequirementManifest> manifests,
        HostDeclarationSurface surface,
        Map<ModuleId, CanonicalModuleIdentity> declarationIdentities,
        Map<String, String> externals) {
    }

    private record Baseline(
        SemanticLowerer.ProjectLoweringResult result,
        HostDeclarationSurface surface,
        String lua,
        JvmSemanticEmitter.EmissionResult jvm,
        Path jvmClasses) {
    }

    private static Baseline prepare(Fixture fixture, boolean emitJvm,
            List<Path> roots) {
        roots.add(fixture.root());
        SemanticLowerer.ProjectLoweringResult result = lower(fixture);
        if (result.project() == null) {
            return new Baseline(result, fixture.surface(), null, null, null);
        }
        ExecutableLoweredProject project = result.project();
        String lua = LuaSemanticEmitter.emitProductionProject(project,
            result.tables(), result.registries(), fixture.surface());
        JvmSemanticEmitter.EmissionResult jvm = emitJvm
            ? JvmSemanticEmitter.emitProductionProject(project, result.tables(),
                result.registries(), JvmBackend.classNameFor(project.entryModule().path()),
                fixture.surface())
            : null;
        return new Baseline(result, fixture.surface(), lua, jvm,
            fixture.root().resolve("jvm-classes"));
    }

    private static CompilerInvocation harnessInvocation() {
        return CompilerProfileProvider.resolve(
            deal.semantic.ir.ReleaseState.V1_3_ACTIVE,
            deal.semantic.CapabilityRegistry.releaseRegistry());
    }

    /** The release-owned production invocation (the lowering profile). */
    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
            deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
    }

    private static Fixture compileProject(Map<String, String> sources,
            Map<String, String> externals) throws Exception {
        Path root = Files.createTempDirectory("host-load-fixture");
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
            false, false, false, false, Backend.LUAJIT, resolvedExternals,
            List.of(src.toAbsolutePath()), null, null, harnessInvocation());
        boolean compiled = orchestrator.compile();
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        if (!compiled || built == null || built.input() == null || built.index() == null
                || built.hasErrors() || manifests == null || manifests.manifests() == null
                || orchestrator.hostDeclarationSurface() == null) {
            String detail = built == null ? "no checked project"
                : String.valueOf(built.diagnostics());
            deleteRecursively(root);
            throw new IllegalStateException("the fixture project did not build: "
                + detail + " / " + orchestrator.diagnostics());
        }
        HostDeclarationSurface surface = orchestrator.hostDeclarationSurface();

        Map<ModuleId, CanonicalModuleIdentity> identities = new LinkedHashMap<>();
        for (ModuleId declarationModule : surface.moduleIds()) {
            String specifier = null;
            for (Map.Entry<String, String> external : externals.entrySet()) {
                if (external.getKey().replace('/', '.').equals(declarationModule.path())
                        || external.getKey().equals(declarationModule.path())) {
                    specifier = external.getKey();
                    break;
                }
            }
            identities.put(declarationModule, specifier == null
                ? IdentityTestFixtures.moduleIdentityOf(declarationModule.path())
                : new CanonicalModuleIdentity.ExternalModule(specifier));
        }
        return new Fixture(root, built.input(), built.index(), manifests.manifests(),
            surface, identities, externals);
    }

    private static Fixture abiFixture(String appSource) throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("src/abi_probe.d.deal", ABI_DECLARATION_SOURCE);
        sources.put("src/cfg.d.deal", CFG_DECLARATION_SOURCE);
        sources.put("src/app.deal", appSource);
        Map<String, String> externals = new LinkedHashMap<>();
        externals.put(PROBE_SPECIFIER, "src/abi_probe.d.deal");
        externals.put(CFG_SPECIFIER, "src/cfg.d.deal");
        return compileProject(sources, externals);
    }

    private static Fixture missingFixture() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("src/missing_export.d.deal", MISSING_DECLARATION_SOURCE);
        sources.put("src/app.deal", MISSING_DECLARATION_APP_SOURCE);
        return compileProject(sources,
            Map.of(MISSING_SPECIFIER, "src/missing_export.d.deal"));
    }

    // =========================================================================
    // The corpus host-fixture declarations the JVM scope must carry
    // (ISSUE-0857): every on-disk host implementation compiles unchanged
    // against the synthesized $DealRt scope, so the declarations whose
    // records those implementations name are imported here.
    // =========================================================================

    /** The corpus host-fixture stems whose implementations name records. */
    private static final List<String> CARRIER_HOST_STEMS = List.of(
        "carrierasync", "carriertable", "carrierarity", "carrieruntyped",
        "carrierclass");

    /**
     * The corpus host-fixture declaration source with its classification
     * headers stripped (the fixture files are harness companions, not
     * discovered corpus cases).
     */
    private static String corpusHostDeclaration(String stem) throws Exception {
        StringBuilder stripped = new StringBuilder();
        for (String line : Files.readString(Path.of("test", "conformance",
                "host-fixtures", stem + ".d.deal"), StandardCharsets.UTF_8)
                .split("\n", -1)) {
            if (line.startsWith("// @")) {
                continue;
            }
            stripped.append(line).append('\n');
        }
        return stripped.toString();
    }

    /**
     * The JVM host-scope project: the ABI declaration, the declared-class
     * declaration, and every corpus host-fixture declaration the deployed
     * implementations name, imported by the application module so the
     * synthesized records exist in the emitted scope.
     */
    private static Fixture carrierHostFixture() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("src/abi_probe.d.deal", ABI_DECLARATION_SOURCE);
        sources.put("src/cfg.d.deal", CFG_DECLARATION_SOURCE);
        Map<String, String> externals = new LinkedHashMap<>();
        externals.put(PROBE_SPECIFIER, "src/abi_probe.d.deal");
        externals.put(CFG_SPECIFIER, "src/cfg.d.deal");
        StringBuilder app = new StringBuilder();
        app.append("import * as probe from \"host/abi_probe\";\n");
        app.append("import * as cfg from \"host/cfg\";\n");
        for (String stem : CARRIER_HOST_STEMS) {
            sources.put("src/" + stem + ".d.deal", corpusHostDeclaration(stem));
            // The corpus lane materializes every host declaration with the
            // dotted class-identity key first, so the corpus host
            // implementations name their instances and function wrappers
            // with the dotted external identity. The scope project imports
            // the same declarations under the same identity form, or the
            // deployed implementations would compile against a different
            // identity text than the one the corpus lane runs them with.
            externals.put("host." + stem, "src/" + stem + ".d.deal");
            externals.put("host/" + stem, "src/" + stem + ".d.deal");
            app.append("import * as ").append(stem).append(" from \"host/")
                .append(stem).append("\";\n");
        }
        app.append("\nexport function main(): null {\n  return null;\n}\n");
        sources.put("src/app.deal", app.toString());
        return compileProject(sources, externals);
    }

    private static Fixture arrayFixture() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("src/array_probe.d.deal", ARRAY_DECLARATION_SOURCE);
        sources.put("src/app.deal", ARRAY_APP_SOURCE);
        return compileProject(sources,
            Map.of(ARRAY_SPECIFIER, "src/array_probe.d.deal"));
    }

    private static Fixture tableParamFixture() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("src/table_param.d.deal", TABLE_PARAM_DECLARATION_SOURCE);
        sources.put("src/app.deal", TABLE_PARAM_APP_SOURCE);
        return compileProject(sources,
            Map.of(TABLE_PARAM_SPECIFIER, "src/table_param.d.deal"));
    }

    private static Fixture errorFixture() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("src/error_probe.d.deal", ERROR_DECLARATION_SOURCE);
        sources.put("src/app.deal", ERROR_APP_SOURCE);
        return compileProject(sources,
            Map.of(ERROR_SPECIFIER, "src/error_probe.d.deal"));
    }

    /**
     * Compiles the host-carrier metadata project through the public
     * {@code deal compile} and returns its published output root, or
     * {@code null} when the compile fails.
     */
    private static Path publishCarrierMetadataProject(Path root,
            String appSource) throws Exception {
        writeFileIn(root, "deal.json", CARRIER_METADATA_DEAL_JSON);
        writeFileIn(root, "bindings/carrier.d.deal",
            CARRIER_DECLARATION_SOURCE);
        writeFileIn(root, "bindings/base.d.deal",
            CARRIER_BASE_DECLARATION_SOURCE);
        writeFileIn(root, "bindings/sample.d.deal",
            CARRIER_NATIVE_DECLARATION_SOURCE);
        writeFileIn(root, "src/util.deal", CARRIER_UTIL_SOURCE);
        writeFileIn(root, "src/app.deal", appSource);
        int exit = deal.Main.run(new String[] {"compile",
            root.resolve("src/app.deal").toAbsolutePath().toString()});
        checkEq(0, exit, "the host-carrier metadata project compiles through"
            + " the public deal compile");
        return exit == 0 ? root.resolve("out") : null;
    }

    private static SemanticLowerer.ProjectLoweringResult lower(Fixture fixture) {
        return SemanticLowerer.lowerProject(productionInvocation(),
            fixture.checkedProject(), fixture.index(), fixture.manifests(),
            fixture.surface(), fixture.declarationIdentities(),
            BuiltinErrorDeclaration.synthesized(
                fixture.checkedProject().modules().get(0).ast().span()),
            Set.of());
    }

    private static ModuleId hostImportModule(ExecutableLoweredProject project,
            String rawSpecifier) {
        return ((KindPayload.ModuleImportPayload) hostImportOp(project, rawSpecifier)
            .payload()).resolvedModule();
    }

    private static SemanticOp hostImportOp(ExecutableLoweredProject project,
            String rawSpecifier) {
        for (LoweredModuleUnit unit : project.modules().values()) {
            for (SemanticOp op : unit.ops()) {
                if (op.kind() != SemanticOpKind.MODULE_IMPORT) {
                    continue;
                }
                KindPayload.ModuleImportPayload payload =
                    (KindPayload.ModuleImportPayload) op.payload();
                if (payload.kind() == ModuleImportKind.HOST
                        && payload.rawSpecifier().equals(rawSpecifier)) {
                    return op;
                }
            }
        }
        throw new IllegalStateException("no host import of '" + rawSpecifier + "'");
    }

    /** The emitted declared-map literal of one host import (declaration order). */
    private static String declaredMapLiteral(HostDeclarationSurface surface,
            ModuleId module) {
        HostDeclarationSurface.DeclarationFacts facts = surface.require(module);
        StringBuilder map = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Type> export : facts.exports().entrySet()) {
            if (!first) {
                map.append(", ");
            }
            first = false;
            map.append("[\"").append(export.getKey()).append("\"] = \"")
                .append(DescriptorService.describe(export.getValue())
                    .canonicalSpecText())
                .append("\"");
        }
        return map.append("}").toString();
    }

    // =========================================================================
    // 1. The LuaJIT load: the artifact text
    // =========================================================================

    /**
     * The occurrence-owned HOST read correlation (ISSUE-0797): every
     * function-typed host import read's recorded binding names the alias
     * cell of the MODULE_IMPORT occurrence that materialized it, and that
     * occurrence resolves the same host module. Two source aliases of one
     * host module produce two occurrences with two distinct cells, so a
     * module/name lookup could never select between them.
     */
    private static void testHostReadCorrelation(Baseline fixture) {
        System.out.println("-- the occurrence-owned HOST read correlation: each "
            + "read's binding names its own import occurrence's alias cell --");
        check(fixture.result().project() != null,
            "the host-read fixture lowers: " + fixture.result().diagnostics());
        if (fixture.result().project() == null) {
            return;
        }
        java.util.Set<deal.semantic.ir.BindingId> readCells =
            new java.util.LinkedHashSet<>();
        int reads = 0;
        for (LoweredModuleUnit unit : fixture.result().project().modules().values()) {
            for (SemanticOp op : unit.ops()) {
                if (op.kind() != SemanticOpKind.EXPORT_READ
                        || !(op.payload()
                            instanceof KindPayload.ExportReadPayload read)
                        || !(op.result()
                            instanceof deal.semantic.ir.ValueId valueId)) {
                    continue;
                }
                deal.semantic.ir.FunctionExecutionBinding binding =
                    unit.functionBindings().get(
                        new deal.semantic.ir.FunctionAllocationIdentity(valueId.id()));
                if (!(binding instanceof deal.semantic.ir.FunctionExecutionBinding
                        .HostFunction host)) {
                    continue;
                }
                reads++;
                check(host.importCell() != null,
                    "the host read of '" + read.name() + "' records its owning "
                        + "import-occurrence cell");
                if (host.importCell() != null) {
                    readCells.add(host.importCell());
                }
                int occurrences = 0;
                for (SemanticOp importOp : unit.ops()) {
                    if (importOp.kind() == SemanticOpKind.MODULE_IMPORT
                            && importOp.payload() instanceof
                                KindPayload.ModuleImportPayload importPayload
                            && importPayload.aliasCells().contains(host.importCell())
                            && importPayload.resolvedModule().equals(read.module())) {
                        occurrences++;
                    }
                }
                checkEq(1, occurrences, "exactly one MODULE_IMPORT completion of the "
                    + "unit initializes the read's occurrence cell and resolves "
                    + read.module().path());
            }
        }
        checkEq(2, reads, "the host-read fixture carries one registered read per "
            + "source alias");
        checkEq(2, readCells.size(), "the two source aliases' reads carry two distinct "
            + "occurrence cells");
    }

    /** Source ordering and structural acceptance survive removal of host defaults. */
    private static void testOrderedExtraKeyScan(List<Path> roots) throws Exception {
        System.out.println("-- HOST construction accepts declared optional fields in both source orders --");
        expectOrderedExtraKeyScan(roots, "port: 2, fallback: 3, peers: []", "fallback");
        expectOrderedExtraKeyScan(roots, "port: 2, peers: [], fallback: 3", "peers");
    }

    private static void expectOrderedExtraKeyScan(List<Path> roots, String provided,
            String firstExtra) throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("src/order.d.deal", ORDER_DECLARATION_SOURCE);
        sources.put("src/app.deal", ORDER_APP_TEMPLATE.formatted(provided));
        Fixture fixture = compileProject(sources,
            Map.of(ORDER_SPECIFIER, "src/order.d.deal"));
        Baseline baseline = prepare(fixture, false, roots);
        if (baseline.result().project() == null) {
            fail("the host-order fixture '" + provided + "' lowers: "
                + baseline.result().diagnostics());
            return;
        }
        ExecutableLoweredProject project = baseline.result().project();
        SemanticOp construction = orderedConstruction(project);
        var payload = (KindPayload.ClassNewPayload) construction.payload();
        checkEq(List.of("port", firstExtra, firstExtra.equals("fallback") ? "peers" : "fallback"),
            payload.providedFields().stream().map(KindPayload.ProvidedField::name).toList(),
            "provided values preserve each source ordering");
        checkEq(List.of("port", "fallback", "peers"),
            payload.fieldBoundaries().stream().map(KindPayload.FieldBoundary::field).toList(),
            "validation follows declaration order regardless of provided order");
        SemanticOracle.HostResponder responder = new SemanticOracle.HostResponder() {};
        Map<ClassId, ClassLayout> declarationLayouts = new LinkedHashMap<>();
        for (ClassId classId : baseline.result().seeds().classIds()) {
            declarationLayouts.put(classId,
                baseline.result().seeds().registrationFor(classId).layout());
        }
        SemanticRuntimeModel.ConsumerRun oracleRun =
            SemanticOracle.executeProjectInits(project, baseline.result().tables(),
                baseline.result().registries(), responder, declarationLayouts);
        check(oracleRun.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
            "Oracle accepts declared optional values without host defaults: " + oracleRun.terminal());

        // The real LuaJIT artifact: the same tuple through the published chunk.
        Path workspace = Files.createTempDirectory("host-order-lua");
        try {
            Path artifact = workspace.resolve("project.lua");
            Files.writeString(artifact, baseline.lua(), StandardCharsets.UTF_8);
            deployRuntime(workspace);
            writeFileIn(workspace, "host/order.lua", ORDER_LUA);
            Path probe = workspace.resolve("probe.lua");
            Files.writeString(probe, orderedExtraKeyProbe(artifact),
                StandardCharsets.UTF_8);
            ProcessOutcome run = runProcess(List.of("luajit",
                probe.toAbsolutePath().toString()), workspace, true);
            check(run.exitCode() == 0 && run.stdout().contains("PROBE-OK"),
                "the LuaJIT artifact names the first provided-source extra field '"
                    + firstExtra + "' for '" + provided + "': " + run.output());
        } finally {
            deleteRecursively(workspace);
        }
    }

    /** The lowered {@code CLASS_NEW(HOST_DECLARATION)} of the ordered-extra-key fixture. */
    private static SemanticOp orderedConstruction(ExecutableLoweredProject project) {
        for (LoweredModuleUnit unit : project.modules().values()) {
            for (SemanticOp op : unit.ops()) {
                if (op.kind() == SemanticOpKind.CLASS_NEW
                        && op.payload() instanceof KindPayload.ClassNewPayload payload
                        && payload.constructionProvider() == ConstructionProvider.HOST_DECLARATION) {
                    return op;
                }
            }
        }
        throw new IllegalStateException("the fixture carries no"
            + " CLASS_NEW(HOST_DECLARATION) construction");
    }

    /** Execute the constructed-field checks with poisoned legacy metadata. */
    private static String orderedExtraKeyProbe(Path artifact) {
        return """
            local function fail(m) print("PROBE-FAIL: " .. m) os.exit(1) end
            dofile("%s")
            local ok, err = __dealMain()
            if not ok then fail("construction failed: " .. tostring(err and err.m)) end
            print("PROBE-OK")
            """.formatted(artifact.toAbsolutePath().toString());
    }

    private static void testLuaArtifactText(Baseline fixture, Baseline aliases)
            throws Exception {
        System.out.println("-- the LuaJIT load: the inline load with the declared map "
            + "and the import origin --");
        {
            SemanticLowerer.ProjectLoweringResult result = fixture.result();
            check(result.project() != null,
                "the host-importing fixture lowers: " + result.diagnostics());
            if (result.project() == null) {
                return;
            }
            ExecutableLoweredProject project = result.project();
            String lua = fixture.lua();

            for (String specifier : List.of(PROBE_SPECIFIER, CFG_SPECIFIER)) {
                SemanticOp op = hostImportOp(project, specifier);
                ModuleId module = hostImportModule(project, specifier);
                String key = luaString(module.path());
                String load = "__exportSurfaces[" + key + "] = __exportSurfaces[" + key
                    + "] or __rt.load_host(" + luaString(specifier) + ", "
                    + declaredMapLiteral(fixture.surface(), module) + ", "
                    + luaString(op.origin().sourceId()) + ", "
                    + op.origin().span().startLine() + ", "
                    + op.origin().span().startColumn() + ")";
                check(lua.contains(load), specifier + ": guarded load with declared ABI and origin");
                check(lua.indexOf(load) > lua.indexOf("__dealMain = function()"),
                    specifier + ": load inside init walk");
            }

            // Exactly one load statement per import op; two aliases of one
            // module share the registry key and the `or` guard.
            {
                SemanticLowerer.ProjectLoweringResult aliasResult = aliases.result();
                if (aliasResult.project() == null) {
                    fail("the two-alias fixture lowers: " + aliasResult.diagnostics());
                    return;
                }
                ExecutableLoweredProject aliasProject = aliasResult.project();
                String aliasLua = aliases.lua();
                ModuleId aliasModule = hostImportModule(aliasProject, PROBE_SPECIFIER);
                checkEq(2, countOccurrences(aliasLua,
                        "__rt.load_host(\"" + PROBE_SPECIFIER + "\""),
                    "one guarded load statement per import op");
                checkEq(2, countOccurrences(aliasLua,
                        "__exportSurfaces[\"" + aliasModule.path() + "\"] = "
                            + "__exportSurfaces[\"" + aliasModule.path() + "\"] or"),
                    "both aliases write the one module-keyed registry entry through "
                        + "the identity guard");

                // The occurrence-owned HOST import instance (ISSUE-0797):
                // the two source aliases of one host module are two
                // occurrences with their own alias cells, so a module/name
                // lookup could never select between them.
                java.util.Set<deal.semantic.ir.BindingId> aliasCells =
                    new java.util.LinkedHashSet<>();
                int aliasImports = 0;
                for (LoweredModuleUnit unit : aliasProject.modules().values()) {
                    for (SemanticOp op : unit.ops()) {
                        if (op.kind() == SemanticOpKind.MODULE_IMPORT
                                && op.payload() instanceof
                                    KindPayload.ModuleImportPayload importPayload
                                && importPayload.resolvedModule().equals(aliasModule)) {
                            aliasImports++;
                            aliasCells.addAll(importPayload.aliasCells());
                        }
                    }
                }
                checkEq(2, aliasImports, "the two source aliases are two MODULE_IMPORT "
                    + "occurrences of one host module");
                checkEq(2, aliasCells.size(), "each source import occurrence owns its "
                    + "own alias cell");
            }

            checkEq(lua, LuaSemanticEmitter.emitProductionProject(project,
                    result.tables(), result.registries(), fixture.surface()),
                "the repeated production emission is byte-identical");
        }
    }

    // =========================================================================
    // 1b. The host-carrier declaration metadata (ISSUE-0849)
    // =========================================================================

    /**
     * The host-carrier declaration metadata on a published artifact
     * (ISSUE-0849): a host-binding production chunk (at least one HOST
     * import, compiled through the public {@code deal compile}) carries
     * exactly three fields of its one factory store, once per chunk — the
     * canonical class field-descriptor map for every HOST-kind declared
     * class, every DEAL class of the
     * compile (declaration order, the builtin {@code Error} identity never a
     * key), the class-kind map for exactly those identities, and the
     * canonical function-signature shape map with the declared
     * parameter-entry cells.
     */
    private static void testLuaHostCarrierMetadata(List<Path> roots)
            throws Exception {
        System.out.println("-- the host-carrier declaration metadata: the three"
            + " factory-store fields of a published host-binding artifact --");
        Path root = Files.createTempDirectory("host-carrier-metadata");
        roots.add(root);
        Path out = publishCarrierMetadataProject(root, CARRIER_APP_SOURCE);
        if (out == null) {
            return;
        }
        Path artifact = out.resolve("app.lua");
        check(Files.isRegularFile(artifact), "the published artifact set carries"
            + " the entry artifact: " + artifact);
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        String lua = Files.readString(artifact, StandardCharsets.UTF_8);

        checkEq(1, countOccurrences(lua, "__factories.__hostClassFields = "),
            "the chunk emits exactly one class field-descriptor map");
        checkEq(1, countOccurrences(lua, "__factories.__hostClassKinds = "),
            "the chunk emits exactly one class-kind map");
        checkEq(1, countOccurrences(lua, "__factories.__hostFnShapes = "),
            "the chunk emits exactly one function-shape map");

        // The class field map: declaration order, HOST classes from the
        // declaration surface, host from the declared ClassLayout, DEAL
        // classes from the compile's class layouts. The field descriptors
        // are the canonical texts (a nested class field is the class
        // identity, an optional array is [?], an optional nullable is ?).
        // The Base entry is the declaration module that only the carrier
        // declaration imports: no HOST import op names it, and its identity
        // still comes from the compile's class-registration seeds.
        String expectedFields = "__factories.__hostClassFields = {"
            + "[\"@$external/host/carrier/Endpoint\"] = {[\"path\"] = \"string\"}, "
            + "[\"@$external/host/carrier/ServerConfig\"] = {"
            + "[\"port\"] = \"number\", "
            + "[\"endpoint\"] = \"@$external/host/carrier/Endpoint\", "
            + "[\"tags\"] = \"[string]\", [\"note\"] = \"?string\", "
            + "[\"root\"] = \"@$external/host/base/Base\"}, "
            + "[\"@$external/host/carrier/Bundle\"] = {"
            + "[\"label\"] = \"string\", [\"action\"] = \"(number)->number\"}, "
            + "[\"@$external/native/sample/Pair\"] = {"
            + "[\"left\"] = \"number\", [\"right\"] = \"number\"}, "
            + "[\"@$external/host/base/Base\"] = {"
            + "[\"id\"] = \"number\", "
            + "[\"run\"] = \"(string,string)->boolean\"}, "
            + "[\"@src/util.deal/Point\"] = {"
            + "[\"x\"] = \"number\", [\"y\"] = \"number\"}, "
            + "[\"@src/app.deal/Widget\"] = {"
            + "[\"name\"] = \"string\", [\"run\"] = \"(number)->number\"}}";
        check(lua.contains(expectedFields), "the class field map carries every"
            + " declared class in declaration order: " + expectedFields);

        String expectedKinds = "__factories.__hostClassKinds = {"
            + "[\"@$external/host/carrier/Endpoint\"] = \"host\", "
            + "[\"@$external/host/carrier/ServerConfig\"] = \"host\", "
            + "[\"@$external/host/carrier/Bundle\"] = \"host\", "
            + "[\"@$external/native/sample/Pair\"] = \"host\", "
            + "[\"@$external/host/base/Base\"] = \"host\", "
            + "[\"@src/util.deal/Point\"] = \"deal\", "
            + "[\"@src/app.deal/Widget\"] = \"deal\"}";
        check(lua.contains(expectedKinds), "the class-kind map classifies"
            + " exactly the emitted classes: " + expectedKinds);
        check(!lua.contains("[\"Error\"] = "),
            "the builtin Error identity is never a metadata key");

        // The shape map: the declared host functions, the declared
        // function-typed fields, the class-layout function field, the
        // chunk-created function value, the imported function value, the
        // function-typed field of the declaration module that only the
        // carrier declaration imports, and the catalog rows of the imported
        // stdlib module (whose __stdlibEntry carriers the surface
        // population emits), in the hostParamCellsOf literal shape.
        for (String shape : List.of(
                "[\"(@$external/host/carrier/ServerConfig)->string\"] = "
                    + "{ret = \"string\", cells = "
                    + "{{\"@$external/host/carrier/ServerConfig\", \"class\", "
                    + "\"@$external/host/carrier/ServerConfig\"}}}",
                "[\"async(number)->@$external/host/carrier/ServerConfig\"] = "
                    + "{ret = \"@$external/host/carrier/ServerConfig\", "
                    + "cells = {{\"number\", \"number\", \"number\"}}}",
                "[\"((number)->number,number)->number\"] = {ret = \"number\", cells = "
                    + "{{\"function(number;number)\", \"function\", \"(number)->number\"}, "
                    + "{\"number\", \"number\", \"number\"}}}",
                "[\"(number)->number\"] = {ret = \"number\", "
                    + "cells = {{\"number\", \"number\", \"number\"}}}",
                "[\"(string,string)->boolean\"] = {ret = \"boolean\", "
                    + "cells = {{\"string\", \"string\", \"string\"}, "
                    + "{\"string\", \"string\", \"string\"}}}",
                "[\"(string,string,string)->boolean\"] = "
                    + "{ret = \"boolean\", cells = "
                    + "{{\"string\", \"string\", \"string\"}, "
                    + "{\"string\", \"string\", \"string\"}, "
                    + "{\"string\", \"string\", \"string\"}}}",
                "[\"(string)->table\"] = {ret = \"table\", "
                    + "cells = {{\"string\", \"string\", \"string\"}}}",
                "[\"(table)->string\"] = {ret = \"string\", "
                    + "cells = {{\"table\", \"table\", \"table\"}}}")) {
            check(lua.contains(shape), "the function-shape map carries the"
                + " declared shape " + shape);
        }

        // The assignments precede every crossing helper and every crossing
        // site (the module-init walk that emits the loads and calls).
        int metadataAt = lua.indexOf("__factories.__hostFnShapes = ");
        check(metadataAt > 0, "the function-shape map is emitted");
        check(metadataAt < lua.indexOf("__factories.__hostNumProject = function"),
            "the metadata precedes the host-crossing prelude helpers");
        check(metadataAt < lua.indexOf("__dealMain = function()"),
            "the metadata precedes every crossing site");

        // The repeated production compile of the same project carries
        // byte-identical metadata text.
        Path secondRoot = Files.createTempDirectory("host-carrier-metadata-2");
        roots.add(secondRoot);
        Path secondOut = publishCarrierMetadataProject(secondRoot,
            CARRIER_APP_SOURCE);
        if (secondOut != null) {
            checkEq(carrierMetadataText(lua),
                carrierMetadataText(Files.readString(
                    secondOut.resolve("app.lua"), StandardCharsets.UTF_8)),
                "the repeated production compile emits byte-identical"
                    + " host-carrier metadata");
        }
    }

    /** The three host-carrier metadata assignment lines of one artifact. */
    private static String carrierMetadataText(String lua) {
        StringBuilder text = new StringBuilder();
        for (String line : lua.split("\n", -1)) {
            if (line.startsWith("__factories.__hostClassFields = ")
                    || line.startsWith("__factories.__hostClassKinds = ")
                    || line.startsWith("__factories.__hostFnShapes = ")) {
                text.append(line).append("\n");
            }
        }
        return text.toString();
    }

    /**
     * The host-free control (ISSUE-0849): a chunk with no HOST import and no
     * host cell boundary binds no host runtime and carries none of the five
     * host-carrier prelude names.
     */
    private static void testLuaHostCarrierMetadataAbsent(List<Path> roots)
            throws Exception {
        System.out.println("-- the host-free control: no host-carrier prelude name"
            + " on a chunk that binds no host runtime --");
        Path root = Files.createTempDirectory("host-carrier-free");
        roots.add(root);
        Path out = publishCarrierMetadataProject(root, HOST_FREE_APP_SOURCE);
        if (out == null) {
            return;
        }
        Path artifact = out.resolve("app.lua");
        check(Files.isRegularFile(artifact), "the host-free artifact exists: "
            + artifact);
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        String lua = Files.readString(artifact, StandardCharsets.UTF_8);
        for (String name : List.of("__hostClassFields", "__hostClassKinds",
                "__hostFnShapes", "__hostCarrierOut", "__hostCarrierIn",
                "__hostClassDefaultsIn")) {
            check(!lua.contains(name), "the host-free chunk carries no "
                + name);
        }
    }

    /**
     * The host-carrier bridge probe body (ISSUE-0850): appended to the
     * published host-binding chunk after its export-surface return, so the
     * chunk's own locals (the factory store, the sentinels, the deployed
     * runtime binding) are in scope. The body is one function so the main
     * chunk's local budget is untouched, and it drives both directions of
     * the emitted pair directly.
     */
    private static final String HOST_CARRIER_BRIDGE_PROBE = """
    local function __bridgeProbe()
    local K = __dealCarrierKeys
    local function say(label, value) print(label .. "|" .. tostring(value)) end

    -- ---- DEAL -> host ----
    local pointerOutToken = {}
    local point = {[K.c] = true, [K.id] = "@src/util.deal/Point",
      [K.f] = {x = 1, y = 2}, [K.p] = {x = true, y = true},
      [K.ptr] = pointerOutToken}
    local pointOut = __factories.__hostCarrierOut("@src/util.deal/Point", point, {})
    say("out.point.kind", pointOut[K.kind])
    say("out.point.identity", pointOut[K.classname])
    say("out.point.x", pointOut.x)
    say("out.point.y", pointOut.y)
    say("out.point.no-carrier-fields", pointOut[K.f] == nil)
    say("out.point.source-unchanged", point[K.c] == true and point[K.f].x == 1)
    say("out.point.pointer", pointOut[K.ptr] == pointerOutToken)
    say("out.point.untyped-identity",
      __factories.__hostCarrierOut(nil, point, {})[K.classname])
    say("out.point.declared-identity",
      __factories.__hostCarrierOut("@$external/host/carrier/Endpoint", point, {})[K.classname])

    local unknownCarrier = {[K.c] = true,
      [K.id] = "@$external/host/other/Unknown", [K.f] = {v = 1}, [K.p] = {v = true}}
    local unknownCarrierOut = __factories.__hostCarrierOut(nil, unknownCarrier, {})
    say("out.unknown.identity", unknownCarrierOut[K.classname])
    say("out.unknown.chunk-tag", unknownCarrierOut[K.c])
    say("out.unknown.field", unknownCarrierOut.v)

    local pair = {[K.c] = true, [K.id] = "@$external/native/sample/Pair",
      [K.f] = {left = 4, right = 9.5}, [K.p] = {left = true, right = true}}
    local pairOut = __factories.__hostCarrierOut(nil, pair, {})
    say("out.pair.kind", pairOut[K.kind])
    say("out.pair.identity", pairOut[K.classname])
    say("out.pair.left", pairOut.left)
    say("out.pair.right", pairOut.right)

    local errorCarrier = {[K.d] = true, code = "E_X", m = "boom"}
    local errorOut = __factories.__hostCarrierOut(nil, errorCarrier, {})
    say("out.error.kind", errorOut[K.kind])
    say("out.error.identity", errorOut[K.classname])
    say("out.error.code", errorOut.code)
    say("out.error.message", errorOut.message)
    say("out.error.no-carrier", errorOut[K.d] == nil)

    local fnCarrier = {[K.fn] = function(x) return x + 1 end,
      [K.sig] = "function(number;number)", [K.csig] = "(number)->number"}
    local fnOut = __factories.__hostCarrierOut(nil, fnCarrier, {})
    say("out.fn.kind", fnOut[K.kind])
    say("out.fn.sig", fnOut.sig)
    say("out.fn.f-type", type(fnOut.f))
    say("out.fn.result", fnOut.f(41))
    local okReject, reject = pcall(fnOut.f, "bad")
    say("out.fn.reject", tostring(okReject) .. "|" .. tostring(reject.code)
      .. "|" .. tostring(reject.m))
    local okShape, shapeErr = pcall(function()
      __factories.__hostCarrierOut(nil, {[K.fn] = function() end,
        [K.sig] = "function(boolean;boolean)", [K.csig] = "(boolean)->boolean"}, {})
    end)
    say("out.fn.no-shape", tostring(okShape) .. "|" .. tostring(type(shapeErr)))
    say("out.fn.no-shape-names-signature",
      type(shapeErr) == "string" and string.find(shapeErr, "(boolean)->boolean", 1, true) ~= nil)
    say("out.fn.no-shape-producer",
      type(shapeErr) == "string" and string.find(shapeErr, "producer coverage defect", 1, true) ~= nil)
    local wrapper = {[K.kind] = "function", sig = "(number)->number", f = function() return 1 end}
    say("out.fn.wrapper-identity",
      __factories.__hostCarrierOut(nil, wrapper, {}) == wrapper)
    local raw = function() return 2 end
    say("out.fn.raw-identity", __factories.__hostCarrierOut(nil, raw, {}) == raw)

    local dual = {[K.kind] = "class", [K.classname] = "@src/util.deal/Point",
      [K.c] = true, [K.id] = "@src/util.deal/Point", x = 1, y = 2}
    say("out.dual.identity", __factories.__hostCarrierOut(nil, dual, {}) == dual)
    local dualAction = {[K.fn] = function(x) return x + 1 end,
      [K.sig] = "function(number;number)", [K.csig] = "(number)->number"}
    local dualBundle = {[K.kind] = "class",
      [K.classname] = "@$external/host/carrier/Bundle",
      [K.c] = true, [K.id] = "@$external/host/carrier/Bundle",
      label = "b", action = dualAction}
    local dualOut = __factories.__hostCarrierOut(nil, dualBundle, {})
    say("out.dual.copy", dualOut ~= dualBundle)
    say("out.dual.copy-kind", dualOut[K.kind])
    say("out.dual.copy-chunk-tag", dualOut[K.c])
    say("out.dual.copy-id", dualOut[K.id])
    say("out.dual.copy-action-kind", dualOut.action[K.kind])
    say("out.dual.copy-action-result", dualOut.action.f(2))
    say("out.dual.source-action-carrier", dualBundle.action[K.csig] == "(number)->number"
      and dualBundle.action[K.kind] == nil)

    local hostOrigin = {[K.kind] = "class",
      [K.classname] = "@$external/host/carrier/Endpoint", path = "/e"}
    say("out.host-origin.identity",
      __factories.__hostCarrierOut(nil, hostOrigin, {}) == hostOrigin)
    say("out.host-origin.no-chunk-tag", hostOrigin[K.c] == nil)
    local hostNumericArray = {[K.a] = true, [K.n] = 1,
      {[K.jn] = true, k = "number", d = 3.5}}
    local hostNumericInstance = {[K.kind] = "class",
      [K.classname] = "@$external/host/carrier/ServerConfig", tags = hostNumericArray}
    say("out.host-origin.numeric-identity",
      __factories.__hostCarrierOut(nil, hostNumericInstance, {}) == hostNumericInstance)
    say("out.host-origin.numeric", hostNumericArray[1])

    local numberCarrier = {[K.jn] = true, k = "number", d = 1.5}
    local sharedArray = {[K.a] = true, [K.n] = 2, 1, numberCarrier}
    say("out.array.identity",
      __factories.__hostCarrierOut("[number]", sharedArray, {}) == sharedArray)
    say("out.array.numeric", sharedArray[2])
    say("out.array.mark", sharedArray[K.nK][2] == true)
    local endpointCarrier = {[K.c] = true,
      [K.id] = "@$external/host/carrier/Endpoint", [K.f] = {path = "/a"},
      [K.p] = {path = true}}
    local classArray = {[K.a] = true, [K.n] = 1, endpointCarrier}
    local classArrayOut = __factories.__hostCarrierOut(
      "[@$external/host/carrier/Endpoint]", classArray, {})
    say("out.array.copy", classArrayOut ~= classArray)
    say("out.array.copy-mark", classArrayOut[K.a] == true)
    say("out.array.copy-length", classArrayOut[K.n])
    say("out.array.copy-element-kind", classArrayOut[1][K.kind])
    say("out.array.copy-element-path", classArrayOut[1].path)
    say("out.array.source-carrier", classArray[1] == endpointCarrier)

    local sharedTable = {[K.t] = true, [K.keys] = {k = true}, [K.order] = {"k"}, k = "v"}
    say("out.table.identity",
      __factories.__hostCarrierOut("table", sharedTable, {}) == sharedTable)
    local memberTable = {[K.t] = true, [K.keys] = {k = true}, [K.order] = {"k"},
      k = endpointCarrier}
    local memberTableOut = __factories.__hostCarrierOut("table", memberTable, {})
    say("out.table.copy", memberTableOut ~= memberTable)
    say("out.table.copy-mark", memberTableOut[K.t] == true)
    say("out.table.copy-keys", memberTableOut[K.keys].k == true)
    say("out.table.copy-order", memberTableOut[K.order][1])
    say("out.table.copy-member-kind", memberTableOut.k[K.kind])
    say("out.table.source-member-carrier", memberTable.k == endpointCarrier)
    local fnMemberTable = {[K.t] = true, [K.keys] = {a = true, b = true},
      [K.order] = {"a", "b"}, a = fnCarrier, b = 1}
    local fnMemberTableOut = __factories.__hostCarrierOut("table", fnMemberTable, {})
    say("out.table.fn-member",
      fnMemberTableOut.a[K.kind] .. "|" .. tostring(fnMemberTableOut.a.f(1)))
    local plainNumericArray = {[K.a] = true, [K.n] = 1,
      {[K.jn] = true, k = "number", d = 4.5}}
    local plainNumericHolder = {xs = plainNumericArray}
    say("out.plain.identity",
      __factories.__hostCarrierOut(nil, plainNumericHolder, {}) == plainNumericHolder)
    say("out.plain.numeric", plainNumericArray[1])
    local aliasTableOut = __factories.__hostCarrierOut(nil, {first = point, second = point}, {})
    say("out.alias.same", aliasTableOut.first == aliasTableOut.second)

    -- Review cycle 1, finding 1: a class value's own fields take the landed
    -- in-place numeric projection — directly, inside a field's array/table
    -- carrier, and through a nested class value — while the instance keeps
    -- its identity, its tags, and its fields.
    local dualNumeric = {[K.kind] = "class",
      [K.classname] = "@$external/host/other/Unknown",
      [K.c] = true, [K.id] = "@$external/host/other/Unknown",
      port = 1,
      tags = {[K.a] = true, [K.n] = 2, "t",
        {[K.jn] = true, k = "number", d = 7.5}},
      root = {[K.t] = true, [K.keys] = {id = true}, [K.order] = {"id"},
        id = {[K.jn] = true, k = "number", d = 2.5}}}
    say("out.dual.numeric-identity",
      __factories.__hostCarrierOut(nil, dualNumeric, {}) == dualNumeric)
    say("out.dual.numeric-array-element", dualNumeric.tags[2])
    say("out.dual.numeric-array-mark", dualNumeric.tags[K.nK][2] == true)
    say("out.dual.numeric-table-member", dualNumeric.root.id)
    local dualNested = {[K.kind] = "class",
      [K.classname] = "@$external/host/other/Unknown",
      [K.c] = true, [K.id] = "@$external/host/other/Unknown",
      inner = {[K.kind] = "class",
        [K.classname] = "@$external/host/other/Unknown",
        [K.c] = true, [K.id] = "@$external/host/other/Unknown",
        xs = {[K.a] = true, [K.n] = 1,
          {[K.jn] = true, k = "number", d = 4.5}}}}
    say("out.dual.nested-identity",
      __factories.__hostCarrierOut(nil, dualNested, {}) == dualNested)
    say("out.dual.nested-numeric", dualNested.inner.xs[1])

    -- Review cycle 1, finding 3: the crossing memo maps a source object to
    -- its target representation; the numeric walk keeps its own seen set,
    -- so an aliased container is never replaced by a walk marker.
    local aliasNumericArray = {[K.a] = true, [K.n] = 1,
      {[K.jn] = true, k = "number", d = 3.5}}
    local hostOriginAlias = {[K.kind] = "class",
      [K.classname] = "@$external/host/other/Unknown", xs = aliasNumericArray}
    local aliasCarrierTable = {[K.t] = true,
      [K.keys] = {h = true, b = true, k = true},
      [K.order] = {"h", "b", "k"},
      h = hostOriginAlias, b = aliasNumericArray, k = endpointCarrier}
    local aliasCarrierOut = __factories.__hostCarrierOut("table",
      aliasCarrierTable, {})
    say("out.memo.alias-kind", type(aliasCarrierOut.b))
    say("out.memo.alias-element", aliasCarrierOut.b[1])
    say("out.memo.alias-shared", aliasCarrierOut.b == aliasCarrierOut.h.xs)

    -- Review cycle 1, finding 2: a copied table keeps its recorded
    -- presence — a present-null member is an ordered key with a nil slot,
    -- never a dropped key.
    local nullMemberTable = {[K.t] = true,
      [K.keys] = {a = true, b = true}, [K.order] = {"a", "b"},
      a = endpointCarrier}
    local nullMemberOut = __factories.__hostCarrierOut("table", nullMemberTable, {})
    say("out.table.copy-null-present", nullMemberOut[K.keys].b == true)
    say("out.table.copy-null-order", #nullMemberOut[K.order])
    say("out.table.copy-null-order-key", nullMemberOut[K.order][2])
    say("out.table.copy-null-absent-value", nullMemberOut.b == nil)

    -- Review cycle 1, finding 4: a marker-created table's presence/order
    -- and numeric maps are its own mutable state, so the fallback copy
    -- carries fresh ones and the source never sees the copy's writes.
    local markerTable = {[K.keys] = {a = true, b = true},
      [K.order] = {"a", "b"}, [K.nK] = {a = true}, a = endpointCarrier}
    local markerOut = __factories.__hostCarrierOut(nil, markerTable, {})
    say("out.marker.copy", markerOut ~= markerTable)
    say("out.marker.shares-keys", markerOut[K.keys] == markerTable[K.keys])
    say("out.marker.shares-order", markerOut[K.order] == markerTable[K.order])
    say("out.marker.shares-marks", markerOut[K.nK] == markerTable[K.nK])
    say("out.marker.copied-keys",
      markerOut[K.keys].a == true and markerOut[K.keys].b == true)
    say("out.marker.copied-order", #markerOut[K.order])
    markerOut[K.order][2] = "Z"
    say("out.marker.source-order", markerTable[K.order][2])
    say("out.marker.member-kind", markerOut.a[K.kind])

    -- ---- host -> DEAL ----
    local hostEndpoint = {[K.kind] = "class",
      [K.classname] = "@$external/host/carrier/Endpoint", path = "/e"}
    local hostCfg = {[K.kind] = "class",
      [K.classname] = "@$external/host/carrier/ServerConfig",
      port = 1, endpoint = hostEndpoint, note = __rt.__NULL}
    local pointerToken = {}
    hostCfg[K.ptr] = pointerToken
    local cfgIn = __factories.__hostCarrierIn(
      "@$external/host/carrier/ServerConfig", hostCfg, {})
    say("in.cfg.chunk-tag", cfgIn[K.c])
    say("in.cfg.kind-tag", cfgIn[K.kind])
    say("in.cfg.classname", cfgIn[K.classname])
    say("in.cfg.id", cfgIn[K.id])
    say("in.cfg.port", cfgIn.port)
    say("in.cfg.endpoint-chunk-tag", cfgIn.endpoint[K.c])
    say("in.cfg.endpoint-path", cfgIn.endpoint.path)
    say("in.cfg.note-null", cfgIn.note == __NULL)
    say("in.cfg.tags-absent", cfgIn.tags == nil)
    say("in.cfg.pointer", cfgIn[K.ptr] == pointerToken)
    say("in.host.not-mutated", hostCfg[K.c] == nil and hostCfg.port == 1)
    say("in.host.nested-not-mutated", hostEndpoint[K.c] == nil)

    local hostPoint = {[K.kind] = "class", [K.classname] = "@src/util.deal/Point", x = 3, y = 4}
    local pointIn = __factories.__hostCarrierIn(nil, hostPoint, {})
    say("in.point.chunk-tag", pointIn[K.c])
    say("in.point.id", pointIn[K.id])
    say("in.point.field-x", pointIn[K.f].x)
    say("in.point.present-x", pointIn[K.p].x)
    say("in.point.top-level-x", pointIn.x)

    local hostPair = {[K.kind] = "class",
      [K.classname] = "@$external/native/sample/Pair", left = 7, right = 1.5}
    local pairIn = __factories.__hostCarrierIn(nil, hostPair, {})
    say("in.pair.chunk-tag", pairIn[K.c])
    say("in.pair.field-left", pairIn.left)

    local hostUnknown = {[K.kind] = "class",
      [K.classname] = "@$external/host/other/Thing", v = 1}
    local unknownIn = __factories.__hostCarrierIn(nil, hostUnknown, {})
    say("in.unknown.chunk-tag", unknownIn[K.c])
    say("in.unknown.id", unknownIn[K.id])
    say("in.unknown.field", unknownIn.v)

    local hostError = {[K.kind] = "class", [K.classname] = "Error",
      code = "E_Y", message = "bad"}
    local errorIn = __factories.__hostCarrierIn(nil, hostError, {})
    say("in.error.carrier", errorIn[K.d])
    say("in.error.code", errorIn.code)
    say("in.error.m", errorIn.m)

    local dense = {10, 20, 30}
    local denseIn = __factories.__hostCarrierIn("[number]", dense, {})
    say("in.array.dense-mark", denseIn[K.a])
    say("in.array.dense-length", denseIn[K.n])
    say("in.array.dense-element", denseIn[2])
    say("in.array.dense-marks", type(denseIn[K.nK]))
    local sparse = {[1] = 1, [3] = 3}
    say("in.array.sparse-identity", __factories.__hostCarrierIn("[number]", sparse, {}) == sparse)
    local mapTable = {a = 1}
    say("in.array.map-identity", __factories.__hostCarrierIn("[number]", mapTable, {}) == mapTable)
    local classElementsIn = __factories.__hostCarrierIn(
      "[@$external/host/carrier/Endpoint]", {hostEndpoint}, {})
    say("in.array.classes-mark", classElementsIn[K.a])
    say("in.array.classes-element-chunk-tag", classElementsIn[1][K.c])
    say("in.array.classes-element-path", classElementsIn[1].path)

    local plainTable = {a = 1, b = {c = 2}}
    say("in.table.identity", __factories.__hostCarrierIn("table", plainTable, {}) == plainTable)
    local classMemberTable = {member = hostEndpoint}
    local classMemberIn = __factories.__hostCarrierIn("table", classMemberTable, {})
    say("in.table.copy", classMemberIn ~= classMemberTable)
    say("in.table.member-chunk-tag", classMemberIn.member[K.c])
    say("in.table.source-member-not-mutated", classMemberTable.member == hostEndpoint)
    local nestedTableIn = __factories.__hostCarrierIn(nil, {x = {inner = {1, 2}}}, {})
    say("in.table.nested-not-array", nestedTableIn.x[K.a] == nil)
    local wrapperTable = {f = wrapper}
    say("in.table.wrapper-identity",
      __factories.__hostCarrierIn("table", wrapperTable, {}) == wrapperTable)

    local aliasHost = {first = hostEndpoint, second = hostEndpoint}
    local aliasIn = __factories.__hostCarrierIn("table", aliasHost, {})
    say("in.alias.same", aliasIn.first == aliasIn.second)
    local aliasArrayIn = __factories.__hostCarrierIn(
      "[@$external/host/carrier/Endpoint]", {hostEndpoint, hostEndpoint}, {})
    say("in.alias.array-same", aliasArrayIn[1] == aliasArrayIn[2])
    -- Review cycle 1, finding 4: the inbound fallback copy carries fresh
    -- presence/order and numeric maps too, so a DEAL-side write to the copy
    -- never reaches the host table.
    local hostMarker = {member = hostEndpoint, [K.keys] = {member = true},
      [K.order] = {"member"}, [K.nK] = {}}
    local hostMarkerIn = __factories.__hostCarrierIn("table", hostMarker, {})
    say("in.marker.copy", hostMarkerIn ~= hostMarker)
    say("in.marker.shares-keys", hostMarkerIn[K.keys] == hostMarker[K.keys])
    say("in.marker.shares-order", hostMarkerIn[K.order] == hostMarker[K.order])
    say("in.marker.shares-marks", hostMarkerIn[K.nK] == hostMarker[K.nK])
    say("in.marker.member-chunk-tag", hostMarkerIn.member[K.c])
    say("in.marker.source-not-mutated", hostMarker.member == hostEndpoint)
    hostMarkerIn[K.order][1] = "Z"
    say("in.marker.source-order", hostMarker[K.order][1])
    local nullablePointIn = __factories.__hostCarrierIn("?@src/util.deal/Point", hostPoint, {})
    say("in.nullable-class.chunk-tag", nullablePointIn[K.c])

    local pointOut1 = __factories.__hostCarrierOut(nil, point, {})
    say("out.idempotent", __factories.__hostCarrierOut(nil, pointOut1, {}) == pointOut1)
    local pointIn1 = __factories.__hostCarrierIn(nil, hostPoint, {})
    say("in.idempotent", __factories.__hostCarrierIn(nil, pointIn1, {}) == pointIn1)
    say("in.fn.carrier-identity",
      __factories.__hostCarrierIn(nil, fnCarrier, {}) == fnCarrier)
    say("in.array.carrier-identity",
      __factories.__hostCarrierIn("[number]", sharedArray, {}) == sharedArray)

    local cycleOut = {[K.t] = true, [K.keys] = {}, [K.order] = {}}
    cycleOut.self = cycleOut
    say("out.cycle", __factories.__hostCarrierOut(nil, cycleOut, {}) == cycleOut)
    local cycleIn = {[K.kind] = "class", [K.classname] = "@$external/host/other/Cycle"}
    cycleIn.self = cycleIn
    local cycleInResult = __factories.__hostCarrierIn(nil, cycleIn, {})
    say("in.cycle", cycleInResult.self == cycleInResult)

    say("out.nil-null", __factories.__hostCarrierOut(nil, nil, {}) == __rt.__NULL)
    say("out.null-null", __factories.__hostCarrierOut(nil, __NULL, {}) == __rt.__NULL)
    say("out.missing-missing", __factories.__hostCarrierOut(nil, __MISSING, {}) == __MISSING)
    say("out.jn", __factories.__hostCarrierOut(nil, {[K.jn] = true, k = "number", d = 2.5}, {}))
    say("in.null-null", __factories.__hostCarrierIn(nil, __rt.__NULL, {}) == __NULL)
    say("in.missing-missing", __factories.__hostCarrierIn(nil, __MISSING, {}) == __MISSING)
    local inboundJn = {[K.jn] = true, k = "number", d = 2.5}
    say("in.jn-identity", __factories.__hostCarrierIn(nil, inboundJn, {}) == inboundJn)
    say("in.number", __factories.__hostCarrierIn(nil, 5, {}))
    say("in.string", __factories.__hostCarrierIn(nil, "s", {}))
    end
    __bridgeProbe()
    """;

    /**
     * The exact row transcript of the host-carrier bridge probe: both
     * directions of the pair (class identity and top-level fields, the
     * DEAL carrier path, the Error mapping, the shared and copied
     * array/table carriers with their marks, the dense/sparse inbound
     * arrays, the alias memo, idempotency, cycle termination, and the
     * null/absent/numeric-variant rules) plus the projected function
     * value's wrapper shape, declared parameter entry, and the producer
     * diagnostic of an undeclared signature.
     */
    private static final String HOST_CARRIER_BRIDGE_TRANSCRIPT = """
        out.point.kind|class
        out.point.identity|@src/util.deal/Point
        out.point.x|1
        out.point.y|2
        out.point.no-carrier-fields|true
        out.point.source-unchanged|true
        out.point.pointer|true
        out.point.untyped-identity|@src/util.deal/Point
        out.point.declared-identity|@$external/host/carrier/Endpoint
        out.unknown.identity|@$external/host/other/Unknown
        out.unknown.chunk-tag|true
        out.unknown.field|1
        out.pair.kind|class
        out.pair.identity|@$external/native/sample/Pair
        out.pair.left|4
        out.pair.right|9.5
        out.error.kind|class
        out.error.identity|Error
        out.error.code|E_X
        out.error.message|boom
        out.error.no-carrier|true
        out.fn.kind|function
        out.fn.sig|(number)->number
        out.fn.f-type|function
        out.fn.result|42
        out.fn.reject|false|E8001|expected number
        out.fn.no-shape|false|string
        out.fn.no-shape-names-signature|true
        out.fn.no-shape-producer|true
        out.fn.wrapper-identity|true
        out.fn.raw-identity|true
        out.dual.identity|true
        out.dual.copy|true
        out.dual.copy-kind|class
        out.dual.copy-chunk-tag|true
        out.dual.copy-id|@$external/host/carrier/Bundle
        out.dual.copy-action-kind|function
        out.dual.copy-action-result|3
        out.dual.source-action-carrier|true
        out.host-origin.identity|true
        out.host-origin.no-chunk-tag|true
        out.host-origin.numeric-identity|true
        out.host-origin.numeric|3.5
        out.array.identity|true
        out.array.numeric|1.5
        out.array.mark|true
        out.array.copy|true
        out.array.copy-mark|true
        out.array.copy-length|1
        out.array.copy-element-kind|class
        out.array.copy-element-path|/a
        out.array.source-carrier|true
        out.table.identity|true
        out.table.copy|true
        out.table.copy-mark|true
        out.table.copy-keys|true
        out.table.copy-order|k
        out.table.copy-member-kind|class
        out.table.source-member-carrier|true
        out.table.fn-member|function|2
        out.plain.identity|true
        out.plain.numeric|4.5
        out.alias.same|true
        out.dual.numeric-identity|true
        out.dual.numeric-array-element|7.5
        out.dual.numeric-array-mark|true
        out.dual.numeric-table-member|2.5
        out.dual.nested-identity|true
        out.dual.nested-numeric|4.5
        out.memo.alias-kind|table
        out.memo.alias-element|3.5
        out.memo.alias-shared|true
        out.table.copy-null-present|true
        out.table.copy-null-order|2
        out.table.copy-null-order-key|b
        out.table.copy-null-absent-value|true
        out.marker.copy|true
        out.marker.shares-keys|false
        out.marker.shares-order|false
        out.marker.shares-marks|false
        out.marker.copied-keys|true
        out.marker.copied-order|2
        out.marker.source-order|b
        out.marker.member-kind|class
        in.cfg.chunk-tag|true
        in.cfg.kind-tag|class
        in.cfg.classname|@$external/host/carrier/ServerConfig
        in.cfg.id|@$external/host/carrier/ServerConfig
        in.cfg.port|1
        in.cfg.endpoint-chunk-tag|true
        in.cfg.endpoint-path|/e
        in.cfg.note-null|true
        in.cfg.tags-absent|true
        in.cfg.pointer|true
        in.host.not-mutated|true
        in.host.nested-not-mutated|true
        in.point.chunk-tag|true
        in.point.id|@src/util.deal/Point
        in.point.field-x|3
        in.point.present-x|true
        in.point.top-level-x|nil
        in.pair.chunk-tag|true
        in.pair.field-left|7
        in.unknown.chunk-tag|true
        in.unknown.id|@$external/host/other/Thing
        in.unknown.field|1
        in.error.carrier|true
        in.error.code|E_Y
        in.error.m|bad
        in.array.dense-mark|true
        in.array.dense-length|3
        in.array.dense-element|20
        in.array.dense-marks|table
        in.array.sparse-identity|true
        in.array.map-identity|true
        in.array.classes-mark|true
        in.array.classes-element-chunk-tag|true
        in.array.classes-element-path|/e
        in.table.identity|true
        in.table.copy|true
        in.table.member-chunk-tag|true
        in.table.source-member-not-mutated|true
        in.table.nested-not-array|true
        in.table.wrapper-identity|true
        in.alias.same|true
        in.alias.array-same|true
        in.marker.copy|true
        in.marker.shares-keys|false
        in.marker.shares-order|false
        in.marker.shares-marks|false
        in.marker.member-chunk-tag|true
        in.marker.source-not-mutated|true
        in.marker.source-order|member
        in.nullable-class.chunk-tag|true
        out.idempotent|true
        in.idempotent|true
        in.fn.carrier-identity|true
        in.array.carrier-identity|true
        out.cycle|true
        in.cycle|true
        out.nil-null|true
        out.null-null|true
        out.missing-missing|true
        out.jn|2.5
        in.null-null|true
        in.missing-missing|true
        in.jn-identity|true
        in.number|5
        in.string|s
        """;

    /**
     * The host-carrier bridge pair (ISSUE-0850) on the published LuaJIT
     * artifact: exactly one {@code __factories.__hostCarrierOut} and one
     * {@code __factories.__hostCarrierIn} in the host-binding prelude,
     * emitted after the declaration metadata and the projected function
     * adapter they compose and before every crossing site, and then driven
     * directly under real {@code luajit}. The probe rows pin both directions
     * of the pair: the class identity and top-level fields, the DEAL and
     * host carrier path, the builtin {@code Error} mapping, the shared
     * and copied array/table carriers with their marks, the dense and
     * sparse inbound arrays, the per-crossing alias memo, idempotency,
     * cycle termination, the null/absent/numeric-variant rules, the
     * no-mutation post-state, and the projected function value's documented wrapper
     * shape, declared parameter entry, and producer diagnostic.
     */
    private static void testLuaHostCarrierBridge(List<Path> roots)
            throws Exception {
        System.out.println("-- the host-carrier bridge pair: the emitted"
            + " __hostCarrierOut/__hostCarrierIn under real luajit --");
        Path root = Files.createTempDirectory("host-carrier-bridge");
        roots.add(root);
        Path out = publishCarrierMetadataProject(root, CARRIER_APP_SOURCE);
        if (out == null) {
            return;
        }
        Path artifact = out.resolve("app.lua");
        check(Files.isRegularFile(artifact), "the published bridge artifact"
            + " set carries the entry artifact: " + artifact);
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        String lua = Files.readString(artifact, StandardCharsets.UTF_8);
        checkEq(1, countOccurrences(lua,
                "__factories.__hostCarrierOut = function"),
            "the chunk emits exactly one outbound host-carrier bridge");
        checkEq(1, countOccurrences(lua,
                "__factories.__hostCarrierIn = function"),
            "the chunk emits exactly one inbound host-carrier bridge");
        int outAt = lua.indexOf("__factories.__hostCarrierOut = function");
        int inAt = lua.indexOf("__factories.__hostCarrierIn = function");
        check(outAt > lua.indexOf("__factories.__hostFnShapes = "),
            "the bridge follows the host-carrier declaration metadata");
        check(outAt > lua.indexOf("local function __hostFnArg"),
            "the outbound bridge is emitted where the projected function"
                + " adapter it delegates to is in scope");
        check(inAt > outAt && inAt < lua.indexOf("__dealMain = function()"),
            "the bridge pair precedes every crossing site");
        String tail = "return __exportSurfaces[\"app\"]";
        check(lua.stripTrailing().endsWith(tail),
            "the published artifact tail is the export-surface return");
        int tailAt = lua.lastIndexOf(tail);
        if (tailAt <= 0) {
            return;
        }
        writeFileIn(out, "bridge_probe.lua",
            lua.substring(0, tailAt) + HOST_CARRIER_BRIDGE_PROBE);
        ProcessOutcome run = runProcess(List.of("luajit", "bridge_probe.lua"),
            out, true);
        check(run.exitCode() == 0 && run.stderr().isEmpty()
                && run.stdout().equals(HOST_CARRIER_BRIDGE_TRANSCRIPT),
            "the emitted host-carrier bridge pair preserves the declared"
                + " class/function/container values in both directions:"
                + " exit=" + run.exitCode()
                + " stdout=" + escaped(run.stdout())
                + " stderr=" + escaped(run.stderr()));
    }

    /**
     * The table-declared host parameter at a contextual-read function
     * value (ISSUE-0830): the emitted artifact's loaded matcher must keep
     * the function carrier's own token, so the call fails with the closed
     * E8010 host parameter cell whose inner reason is the table kind arm
     * and the host module is never invoked.
     */
    private static void testLuaHostTableParameterFunctionCarrier(
            Baseline fixture) throws Exception {
        System.out.println("-- the table-declared host parameter at a function"
            + " value: the closed E8010 cell on the LuaJIT artifact --");
        Path workspace = Files.createTempDirectory("host-table-param-lua");
        try {
            SemanticLowerer.ProjectLoweringResult result = fixture.result();
            if (result.project() == null) {
                fail("the table-parameter fixture lowers: "
                    + result.diagnostics());
                return;
            }
            Path artifact = workspace.resolve("project.lua");
            Files.writeString(artifact, fixture.lua(), StandardCharsets.UTF_8);
            deployRuntime(workspace);
            writeFileIn(workspace, "host/table_param.lua", TABLE_PARAM_LUA);
            Path probe = workspace.resolve("probe.lua");
            Files.writeString(probe, """
                local function fail(m) print("PROBE-FAIL: " .. m) os.exit(1) end
                dofile("%s")
                local ok, err = __dealMain()
                if not ok then fail("init: " .. tostring(err)) end
                local surface = __exportSurfaces["app"]
                local entry = surface["test_function_value_at_table_param"]
                if entry == nil then fail("missing export") end
                local crossed, e = pcall(entry.f)
                if crossed then
                  fail("the function value crossed the table-declared host parameter")
                end
                if type(e) ~= "table" or e[__dealCarrierKeys.d] ~= true then
                  fail("not a DEAL error: " .. tostring(e))
                end
                if e.code ~= "E8010" then fail("code: " .. tostring(e.code)) end
                if e.m ~= "parameter 1 type mismatch: expected table" then
                  fail("message: " .. tostring(e.m))
                end
                if e.e ~= "table" then fail("expected: " .. tostring(e.e)) end
                -- The host parameter cell's own actual projection is the
                -- carrier-kind token (every table-shaped carrier projects
                -- table), exactly the JVM host cell's __hostKind value.
                if e.a ~= "table" then fail("actual: " .. tostring(e.a)) end
                print("PROBE-OK")
                """.formatted(artifact.toAbsolutePath().toString()),
                StandardCharsets.UTF_8);
            ProcessOutcome run = runProcess(List.of("luajit",
                probe.toAbsolutePath().toString()), workspace, true);
            check(run.exitCode() == 0 && run.stdout().contains("PROBE-OK"),
                "the function value at the table-declared host parameter takes"
                    + " the E8010 cell: exit=" + run.exitCode()
                    + " stdout=" + escaped(run.stdout())
                    + " stderr=" + escaped(run.stderr()));
            check(!run.stdout().contains("HOST-CALLED"),
                "the raw host is never reached with the function carrier: "
                    + escaped(run.stdout()));
        } finally {
            deleteRecursively(workspace);
        }
    }

    // =========================================================================
    // 2a. The decoded numeric-carrier host crossing (ISSUE-0836)
    // =========================================================================

    /**
     * The declared numeric positions of the carrier-projection fixture: a
     * synchronous and an asynchronous {@code number[]} parameter, a
     * {@code (int | null)[]}, and a declared host class with an {@code int}
     * and a {@code number} field.
     */
    private static final String NUMERIC_DECLARATION_SOURCE = """
        export class Cfg {
          count: number;
          ratio: number;
        }

        export async function absorbAsync(nums: number[]): string;
        export function echoNumbers(nums: number[]): number[];
        export function observeNumbers(nums: number[]): string;
        export function observeInts(ns: (number | null)[]): string;
        export function describeCfg(cfg: Cfg): string;
        """;

    /**
     * The decoded-content program: every declared numeric host position is
     * filled from a {@code std/json} decode graph, whose numeric leaves are
     * the value model's private int/number variant carriers (the JSON
     * classifier deliberately preserves the variant), and the echoed array
     * re-encodes through the shared walker.
     */
    private static final String NUMERIC_APP_SOURCE = """
        import * as json from "std/json";
        import * as host from "host/num_probe";

        export function main(): null {
          return null;
        }

        export function run(): string {
          let numsDoc: table = json.parse("{\\"ms\\":[1e17,1.5]}");
          let ms: number[] = numsDoc.ms;
          let echo: number[] = host.echoNumbers(ms);
          let box: table = { xs: echo };

          let intsDoc: table = json.parse("{\\"ns\\":[1,null,3]}");
          let ns: (number | null)[] = intsDoc.ns;

          let cfgDoc: table = json.parse("{\\"count\\":7,\\"ratio\\":2.5}");
          let count: int = cfgDoc.count;
          let ratio: number = cfgDoc.ratio;
          let cfg: host.Cfg = { count: count, ratio: ratio };

          return json.stringify(box) + "\\n"
            + host.observeNumbers(ms) + "\\n"
            + host.observeInts(ns) + "\\n"
            + host.describeCfg(cfg);
        }

        export async function runAsync(): string {
          let doc: table = json.parse("{\\\"asms\\\":[1e17,1.5]}");
          let xs: number[] = doc.asms;
          return await host.absorbAsync(xs);
        }
        """;

    /**
     * The deployed raw host: it reports exactly what it observes at each
     * declared position — the element kinds of the {@code number[]} it was
     * handed, the language null of the {@code (int | null)[]} slot, and the
     * kinds and values of the declared class's numeric fields — and echoes
     * the decoded array back so the DEAL side re-encodes it.
     */
    private static final String NUMERIC_HOST_LUA = """
        local rt = require("deal.runtime")

        return {
          Cfg = { [__dealCarrierKeys.kind] = "class",
                  [__dealCarrierKeys.classname] = "@$external/host/num_probe/Cfg" },
          Cfg_defaults = { count = 0, ratio = 0.0 },

          echoNumbers = function(nums)
            return nums
          end,

          observeNumbers = function(nums)
            return "nums=n:" .. tostring(#nums)
              .. ":k1=" .. type(nums[1]) .. ":k2=" .. type(nums[2])
          end,

          observeInts = function(ns)
            local second = ns[2]
            local secondText
            if second == rt.__NULL then
              secondText = "null"
            else
              secondText = type(second)
            end
            return "ints=n:" .. tostring(#ns)
              .. ":k1=" .. type(ns[1]) .. ":v1=" .. tostring(ns[1])
              .. ":k2=" .. secondText
              .. ":k3=" .. type(ns[3]) .. ":v3=" .. tostring(ns[3])
          end,

          describeCfg = function(cfg)
            return "cfg=count:" .. type(cfg.count) .. ":" .. tostring(cfg.count)
              .. ":ratio:" .. type(cfg.ratio) .. ":" .. tostring(cfg.ratio)
          end,

          absorbAsync = function(nums)
            local kinds = {}
            for i = 1, #nums do kinds[i] = type(nums[i]) end
            return rt.async_start(function()
              return "async=n:" .. tostring(#nums) .. ":k1=" .. kinds[1]
                .. ":k2=" .. kinds[2]
            end)
          end,
        }
        """;

    private static final String LUA_RUN_PROBE = """
        local function fail(m) print("PROBE-FAIL: " .. m) os.exit(1) end
        dofile("app.lua")
        local ok, err = __dealMain()
        if not ok then fail("init: " .. tostring(err)) end
        local surface = __exportSurfaces["app"]
        local entry = surface["run"]
        if entry == nil then fail("missing run export") end
        local ok2, res = pcall(entry.f)
        if not ok2 then
          local e = res
          fail("run: " .. tostring(e.code) .. "|" .. tostring(e.m)
            .. "|" .. tostring(e.e) .. "|" .. tostring(e.a))
        end
        """;

    private static final String NUMERIC_TRANSCRIPT = String.join("\n",
        "TRANSCRIPT-BEGIN",
        "{\"xs\":[1.0E17,1.5]}",
        "nums=n:2:k1=number:k2=number",
        "ints=n:3:k1=number:v1=1:k2=null:k3=number:v3=3",
        "cfg=count:number:7:ratio:number:2.5",
        "TRANSCRIPT-END",
        "");

    /**
     * The dynamic-table host declaration (ISSUE-0695 acceptance
     * remediation): one host module whose real Lua implementation returns a
     * plain JSON-shaped Lua table, returns the shipped {@code std/json}
     * decode graph, echoes a DEAL table, and reads a decoded object member
     * with raw Lua arithmetic.
     */
    private static final String TABLE_PROBE_DECLARATION_SOURCE = """
        export function returnPlain(): table;
        export function returnParsed(): table;
        export function returnParsedVariant(): table;
        export async function returnAsyncPlain(): table;
        export function observeMember(o: table): string;
        export function stringifyCarrier(t: table): string;
        export function echo(t: table): table;
        """;

    /**
     * The dynamic-table drive: every path crosses a plain Lua host table in
     * one direction or the other. The decoded document carries a present
     * null, a number-variant leaf and a decoded array, so the emitted
     * stringify, the shipped module the raw host calls, and the echoed
     * carrier must all agree with the compiled walk.
     */
    private static final String TABLE_PROBE_APP_SOURCE = """
        import * as json from "std/json";
        import * as host from "host/tab_probe";

        export function main(): null {
          return null;
        }

        export function run(): string {
          let plain: table = host.returnPlain();
          let decoded: table = json.parse("{\\"n\\":null,\\"v\\":0.1,\\"xs\\":[1,0.1,-2147483649]}");
          let echoed: table = host.echo(decoded);
          let member: string = host.observeMember(json.parse("{\\"o\\":{\\"v\\":41}}").o);
          return json.stringify(plain) + "\\n"
            + json.stringify(decoded) + "\\n"
            + host.stringifyCarrier(decoded) + "\\n"
            + json.stringify(echoed) + "\\n"
            + member + "\\n"
            + json.stringify(host.returnParsed()) + "\\n"
            + json.stringify(host.returnParsedVariant());
        }

        export async function runAsync(): string {
          let t: table = await host.returnAsyncPlain();
          return json.stringify(t);
        }
        """;

    /**
     * The deployed raw host: it returns plain Lua tables (the LuaJIT table
     * representation), returns the deployed {@code std/json} decode graph,
     * echoes the carrier it was handed, reads a decoded object member as a
     * number, and stringifies the DEAL carrier with the shipped module.
     */
    private static final String TABLE_PROBE_HOST_LUA = """
        local rt = require("deal.runtime")
        local json = require("std.json")

        return {
          returnPlain = function()
            return { xs = { 1, 2 } }
          end,

          returnParsed = function()
            return json.parse.f("{\\"n\\":null}")
          end,

          returnParsedVariant = function()
            return json.parse.f("{\\"v\\":0.1}")
          end,

          returnAsyncPlain = function()
            return rt.async_start(function()
              return { xs = { 1, 2 } }
            end)
          end,

          observeMember = function(o)
            return "v=" .. tostring(o.v + 1)
          end,

          stringifyCarrier = function(t)
            return json.stringify.f(t)
          end,

          echo = function(t)
            return t
          end,
        }
        """;

    /** The transcript the dynamic-table crossing must produce. */
    private static final String TABLE_PROBE_TRANSCRIPT = String.join("\n",
        "TRANSCRIPT-BEGIN",
        "{\"xs\":[1,2]}",
        "{\"n\":null,\"v\":0.1,\"xs\":[1.0,0.1,-2.147483649E9]}",
        "{\"n\":null,\"v\":0.1,\"xs\":[1.0,0.1,-2.147483649E9]}",
        "{\"n\":null,\"v\":0.1,\"xs\":[1.0,0.1,-2.147483649E9]}",
        "v=42",
        "{\"n\":null}",
        "{\"v\":0.1}",
        "TRANSCRIPT-END",
        "");

    /**
     * The LuaJIT host projection is carrier-complete for the value model's
     * numeric-variant carriers (ISSUE-0836): a decoded {@code number[]},
     * {@code (int | null)[]} and a decoded numeric class field cross a real
     * host parameter as the declared DEAL values — the raw host observes
     * numbers, never the runtime's internal carrier tables — the deployed
     * matcher admits them at the declared element/field descriptors, the
     * array keeps its identity so the echoed decoded array re-encodes its
     * own int/number variants ({@code 1.0E17}, {@code 1.5}), the async
     * argument crossing hands the same declared elements to the host and
     * its completion settles on the async export, and the published
     * production artifact runs to completion.
     */
    private static void testLuaHostDecodedNumericCarrier() throws Exception {
        System.out.println("-- the decoded numeric-carrier host crossing: the"
            + " published LuaJIT artifact under real luajit --");
        Path root = Files.createTempDirectory("host-numeric-carrier-lua");
        try {
            Path out = publishLuaHost(root, "num_probe", NUMERIC_DECLARATION_SOURCE,
                NUMERIC_APP_SOURCE, NUMERIC_HOST_LUA, "decoded-numeric-carrier");
            if (out == null) {
                return;
            }
            writeFileIn(out, "probe.lua", LUA_RUN_PROBE + """
                local asyncEntry = surface["runAsync"]
                if asyncEntry == nil then fail("missing runAsync export") end
                local okA, handle = pcall(asyncEntry.f)
                if not okA then fail("runAsync: " .. tostring(handle)) end
                if type(handle) ~= "table"
                    or handle[__dealCarrierKeys.kind] ~= "async" then
                  fail("runAsync did not return an async operation")
                end
                if handle.__result ~= "async=n:2:k1=number:k2=number" then
                  fail("runAsync completion: " .. tostring(handle.__result))
                end
                print("TRANSCRIPT-BEGIN")
                print(res)
                print("TRANSCRIPT-END")
                """);
            ProcessOutcome run = runProcess(List.of("luajit", "probe.lua"), out, true);
            check(run.exitCode() == 0 && run.stdout().equals(NUMERIC_TRANSCRIPT)
                    && run.stderr().isEmpty(),
                "a declared host parameter of a decoded numeric array and a"
                    + " decoded numeric class field hands the raw host the"
                    + " declared DEAL values, and the echoed decoded array"
                    + " keeps its own number/number variants: exit="
                    + run.exitCode() + " stdout=" + escaped(run.stdout())
                    + " stderr=" + escaped(run.stderr()));
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * The dynamic-table host crossing (ISSUE-0695 acceptance remediation):
     * a plain Lua table the real host returns at a declared {@code table}
     * position is admitted by the ordinary DEAL boundaries — the plain-table
     * JSON-shaped inference (nested arrays, recorded marks and a present-null
     * member included) and the TABLE_KEYS string-key fallback — instead of
     * rejecting it with the false E8001; the host that stringifies a DEAL
     * carrier with the deployed {@code std/json} module observes the same
     * text as the compiled walk (the present-null member and the recorded
     * number variant included); and a decoded object member crossing to the
     * raw host is the declared number, so the host's own arithmetic works
     * instead of raising the internal-carrier raw Lua error. The async
     * completion crossing admits and encodes a plain table the same way.
     */
    private static void testLuaHostTableProjection() throws Exception {
        System.out.println("-- the dynamic-table host crossing: the published"
            + " LuaJIT artifact under real luajit --");
        Path root = Files.createTempDirectory("host-table-lua");
        try {
            Path out = publishLuaHost(root, "tab_probe", TABLE_PROBE_DECLARATION_SOURCE,
                TABLE_PROBE_APP_SOURCE, TABLE_PROBE_HOST_LUA, "dynamic-table");
            if (out == null) {
                return;
            }
            writeFileIn(out, "probe.lua", LUA_RUN_PROBE + """
                local asyncEntry = surface["runAsync"]
                if asyncEntry == nil then fail("missing runAsync export") end
                local okA, handle = pcall(asyncEntry.f)
                if not okA then fail("runAsync: " .. tostring(handle)) end
                if type(handle) ~= "table"
                    or handle[__dealCarrierKeys.kind] ~= "async" then
                  fail("runAsync did not return an async operation")
                end
                if handle.__result ~= "{\\"xs\\":[1,2]}" then
                  fail("runAsync completion: " .. tostring(handle.__result))
                end
                print("TRANSCRIPT-BEGIN")
                print(res)
                print("TRANSCRIPT-END")
                """);
            ProcessOutcome run = runProcess(List.of("luajit", "probe.lua"), out, true);
            check(run.exitCode() == 0 && run.stdout().equals(TABLE_PROBE_TRANSCRIPT)
                    && run.stderr().isEmpty(),
                "a plain host table is admitted by the DEAL JSON and table"
                    + " boundaries at the host return, the host stringifies the"
                    + " DEAL carrier with the deployed std/json module to the"
                    + " same text, and a decoded object member crosses as its"
                    + " number: exit="
                    + run.exitCode() + " stdout=" + escaped(run.stdout())
                    + " stderr=" + escaped(run.stderr()));
        } finally {
            deleteRecursively(root);
        }
    }

    // =========================================================================
    // 2. The host-crossed table value (ISSUE-0842)
    // =========================================================================

    /**
     * The declared table positions of the host-crossed table value: a
     * {@code table} return, a {@code table} parameter (the host observes
     * the DEAL value it was handed), a declared function position whose
     * host-driven invocation passes a plain Lua table back into DEAL, a
     * self-referential host table (the walk's closed cycle arm), and the
     * three table forms {@code json.stringify} classifies — the pure
     * string-keyed object, the pure positive-integer-keyed array, and the
     * mixed-key table only a non-DEAL host table can carry.
     */
    private static final String HOST_TABLE_DECLARATION_SOURCE = """
        export function makeTable(): table;
        export function makeNested(): table;
        export function makeCycle(): table;
        export function makeArray(): table;
        export function makeMixed(): table;
        export function describe(t: table): string;
        export function withTable(f: (t: table) => string): string;
        """;

    /**
     * The DEAL side of the host-crossed table value: {@code table.keys}
     * and {@code json.stringify} consume the plain table a host hands
     * over — the return value, the callback argument, a nested member and
     * a table the DEAL side wrote after the crossing (the first write's
     * recorded order beside the host's own keys) — a self-referential
     * host table renders the closed cycle arm, and the shared table-form
     * rule admits the pure array form while a mixed-key host table renders
     * the closed {@code JSON_STRINGIFY_UNSUPPORTED} tuple instead of
     * coerced or duplicated member names (JsonEncodeV13).
     */
    private static final String HOST_TABLE_APP_SOURCE = """
        import * as host from "host/table_probe";
        import * as tables from "std/table";
        import * as json from "std/json";

        function joined(keys: string[]): string {
          let out: string = "";
          let i: number = 0;
          while (i < keys.length) {
            if (i > 0) {
              out = out + ",";
            }
            out = out + keys[i];
            i = i + 1;
          }
          return out;
        }

        function accept(t: table): string {
          return "cb:" + joined(tables.keys(t)) + ":" + json.stringify(t);
        }

        function closedOutcome(t: table): string {
          let outcome: string = "no-failure";
          try {
            outcome = "OK:" + json.stringify(t);
          } catch (e) {
            outcome = e.code + "|" + e.message;
          }
          return outcome;
        }

        export function run(): string {
          let t: table = host.makeTable();
          let first: string = "keys=" + joined(tables.keys(t))
            + " json=" + json.stringify(t)
            + " host=" + host.describe(t);

          let nested: table = host.makeNested();
          let second: string = "nested=" + json.stringify(host.makeNested())
            + " keys=" + joined(tables.keys(nested));

          t.extra = 9;
          let third: string = "written=" + joined(tables.keys(t))
            + ":" + json.stringify(t)
            + " host=" + host.describe(t);

          let fourth: string = "callback=" + host.withTable(accept);

          let cycleOk: boolean = false;
          try {
            let encoded: string = json.stringify(host.makeCycle());
            cycleOk = encoded === "";
          } catch (e) {
            cycleOk = e.code === "E8001"
              && e.message === "cyclic value cannot be encoded as JSON";
          }
          if (!cycleOk) {
            throw { code: "TEST_FAIL", message: "host table cycle arm" };
          }

          let fifth: string = "array=" + closedOutcome(host.makeArray());
          // The shared table-form rule: a mixed-key host table is not
          // JSON-shaped content, so the walk renders the closed
          // JSON_STRINGIFY_UNSUPPORTED tuple instead of a coerced numeric
          // member name (and never the duplicate name [1] beside "1").
          let sixth: string = "mixed=" + closedOutcome(host.makeMixed());

          return first + "\\n" + second + "\\n" + third + "\\n" + fourth
            + "\\n" + fifth + "\\n" + sixth;
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The deployed raw host: it returns plain Lua tables (the LuaJIT value
     * mapping's table value) and reports the declared values it observes
     * at the table parameter, so the DEAL side's writes stay visible to
     * the host and the host's keys stay visible to DEAL.
     */
    private static final String HOST_TABLE_LUA = """
        return {
          makeTable = function() return { a = 1 } end,
          makeNested = function() return { inner = "x" } end,
          makeCycle = function()
            local t = {}
            t.self = t
            return t
          end,
          makeArray = function() return { "a", "b" } end,
          makeMixed = function()
            return { [1] = "a", ["1"] = "b", name = "test" }
          end,
          describe = function(t)
            return "a=" .. tostring(t.a) .. " extra=" .. tostring(t.extra)
          end,
          withTable = function(f) return f({ cb = 2 }) end,
        }
        """;

    /** The transcript the host-crossed table value must publish. */
    private static final String HOST_TABLE_TRANSCRIPT = String.join("\n",
        "TRANSCRIPT-BEGIN",
        "keys=a json={\"a\":1} host=a=1 extra=nil",
        "nested={\"inner\":\"x\"} keys=inner",
        "written=extra,a:{\"extra\":9.0,\"a\":1} host=a=1 extra=9",
        "callback=cb:cb:{\"cb\":2}",
        "array=OK:[\"a\",\"b\"]",
        "mixed=E8001|unsupported type for JSON encoding: table",
        "TRANSCRIPT-END",
        "");

    /**
     * A {@code table} value crossing from a host into DEAL is the DEAL
     * table value (the LuaJIT value mapping's ``table`` = Lua table): the
     * declared table return, the declared table parameter and the
     * host-driven callback argument all cross as the plain Lua table the
     * host built, and {@code std/table.keys} and {@code std/json.stringify}
     * consume it — no raw Lua length-of-nil failure and no false
     * {@code JSON_STRINGIFY_UNSUPPORTED} — on the published production
     * artifact under real luajit. The same walk admits a host table's pure
     * array form and rejects its mixed-key form with the closed
     * {@code JSON_STRINGIFY_UNSUPPORTED} tuple — the shared table-form rule
     * the shipped std/json realization applies too — so no member name is
     * ever coerced from a key the DEAL table grammar cannot carry.
     */
    private static void testLuaHostTableValue() throws Exception {
        System.out.println("-- the host-crossed table value: the published "
            + "LuaJIT artifact under real luajit --");
        Path root = Files.createTempDirectory("host-table-lua");
        try {
            Path out = publishLuaHost(root, "table_probe", HOST_TABLE_DECLARATION_SOURCE,
                HOST_TABLE_APP_SOURCE, HOST_TABLE_LUA, "host-table");
            if (out == null) {
                return;
            }
            writeFileIn(out, "probe.lua", LUA_RUN_PROBE + """
                print("TRANSCRIPT-BEGIN")
                print(res)
                print("TRANSCRIPT-END")
                """);
            ProcessOutcome run = runProcess(List.of("luajit", "probe.lua"),
                out, true);
            check(run.exitCode() == 0 && run.stdout().equals(HOST_TABLE_TRANSCRIPT)
                    && run.stderr().isEmpty(),
                "a host table return, table parameter and callback argument are"
                    + " the DEAL table value at the table.keys and"
                    + " json.stringify surface, and a self-referential host"
                    + " table takes the closed cycle arm: exit="
                    + run.exitCode() + " stdout=" + escaped(run.stdout())
                    + " stderr=" + escaped(run.stderr()));
        } finally {
            deleteRecursively(root);
        }
    }

    // =========================================================================
    // 2a. The host array crossings (ISSUE-0695 acceptance remediation)
    // =========================================================================

    /**
     * The declared host array positions: an async operation completing with
     * a declared {@code int[]} value, a nested {@code int[][]} completion
     * whose two slots are the host's one inner table (the reference-identity
     * alias), and a declared function position whose host-driven invocation
     * passes a declared {@code int[]} argument into the DEAL callback.
     */
    private static final String ARRAY_CROSSING_DECLARATION_SOURCE = """
        export async function produce(): number[];
        export async function produceNested(): number[][];
        export function consume(f: (xs: number[]) => number): number;
        """;

    /**
     * The DEAL side of the host array crossings: the async completion value
     * and the host-passed callback argument are read as the DEAL array value
     * — the exact length and the exact elements at every one-based slot, and
     * the nested completion's preserved alias — so an unprojected plain host
     * table (nil length, raw Lua index failure) fails the drive instead of
     * producing a value.
     */
    private static final String ARRAY_CROSSING_APP_SOURCE = """
        import * as host from "host/arr_probe";

        function sum(xs: number[]): number {
          let total: number = 0;
          let i: number = 0;
          while (i < xs.length) {
            total = total + xs[i];
            i = i + 1;
          }
          return total;
        }

        function asyncTotal(xs: number[]): number {
          if (xs.length !== 3) {
            throw { code: "TEST_FAIL", message: "async completion length" };
          }
          if (xs[0] !== 10 || xs[1] !== 20 || xs[2] !== 30) {
            throw { code: "TEST_FAIL", message: "async completion elements" };
          }
          return sum(xs);
        }

        function callbackTotal(xs: number[]): number {
          if (xs.length !== 3) {
            throw { code: "TEST_FAIL", message: "callback argument length" };
          }
          if (xs[0] !== 1 || xs[1] !== 2 || xs[2] !== 3) {
            throw { code: "TEST_FAIL", message: "callback argument elements" };
          }
          return sum(xs);
        }

        export function runCallback(): number {
          return host.consume(callbackTotal);
        }

        export async function runAsync(): number {
          let xs: number[] = await host.produce();
          return asyncTotal(xs);
        }

        export async function runNested(): number {
          let xs: number[][] = await host.produceNested();
          if (xs.length !== 2) {
            throw { code: "TEST_FAIL", message: "nested completion length" };
          }
          if (xs[0] !== xs[1]) {
            throw { code: "TEST_FAIL", message: "nested completion alias" };
          }
          if (xs[0].length !== 2 || xs[0][0] !== 7 || xs[0][1] !== 8) {
            throw { code: "TEST_FAIL", message: "nested completion elements" };
          }
          return xs[0][0] + xs[1][1];
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The deployed raw host: it completes a real async operation with the
     * LuaJIT value mapping's {@code T[]} (a plain Lua table with 1-based
     * contiguous storage), aliases one inner table in two slots, and invokes
     * the DEAL callback it was handed with a plain {@code {1,2,3}} table.
     */
    private static final String ARRAY_CROSSING_LUA = """
        local rt = require("deal.runtime")
        local shared = { 7, 8 }
        return {
          produce = function()
            return rt.async_start(function() return { 10, 20, 30 } end)
          end,
          produceNested = function()
            return rt.async_start(function() return { shared, shared } end)
          end,
          consume = function(f) return f({ 1, 2, 3 }) end,
        }
        """;

    /** The exact results the two host array crossings must produce. */
    private static final String ARRAY_CROSSING_TRANSCRIPT = String.join("\n",
        "TRANSCRIPT-BEGIN",
        "callback=6",
        "async=60",
        "nested=15",
        "TRANSCRIPT-END",
        "");

    /**
     * A host {@code T[]} value is the DEAL array value at every host-to-DEAL
     * crossing: the async-function completion value and the function-parameter
     * entry of a DEAL callback the host invokes both take the
     * identity-preserving array projection the synchronous host return runs
     * (nested arrays, aliases and null elements preserved), so the body reads
     * the exact length and the exact elements — never a nil length or a raw
     * Lua index failure — on the published production artifact under real
     * luajit.
     */
    private static void testLuaHostArrayCrossings() throws Exception {
        System.out.println("-- the host array crossings: the published LuaJIT"
            + " artifact under real luajit --");
        Path root = Files.createTempDirectory("host-array-lua");
        try {
            Path out = publishLuaHost(root, "arr_probe", ARRAY_CROSSING_DECLARATION_SOURCE,
                ARRAY_CROSSING_APP_SOURCE, ARRAY_CROSSING_LUA, "host-array-crossing");
            if (out == null) {
                return;
            }
            writeFileIn(out, "probe.lua", """
                local function fail(m) print("PROBE-FAIL: " .. m) os.exit(1) end
                dofile("app.lua")
                local ok, err = __dealMain()
                if not ok then fail("init: " .. tostring(err)) end
                local surface = __exportSurfaces["app"]
                local callback = surface["runCallback"]
                if callback == nil then fail("missing runCallback export") end
                local okC, resC = pcall(callback.f)
                if not okC then
                  fail("runCallback: " .. tostring(resC.code) .. "|"
                    .. tostring(resC.m) .. "|" .. tostring(resC.e) .. "|"
                    .. tostring(resC.a) .. "|" .. tostring(resC.o))
                end
                if resC ~= 6 then
                  fail("callback argument: " .. tostring(resC))
                end
                local async = surface["runAsync"]
                if async == nil then fail("missing runAsync export") end
                local okA, handleA = pcall(async.f)
                if not okA then
                  fail("runAsync: " .. tostring(handleA.code) .. "|"
                    .. tostring(handleA.m) .. "|" .. tostring(handleA.e) .. "|"
                    .. tostring(handleA.a) .. "|" .. tostring(handleA.o))
                end
                if type(handleA) ~= "table"
                    or handleA[__dealCarrierKeys.kind] ~= "async" then
                  fail("runAsync did not return an async operation")
                end
                local nested = surface["runNested"]
                if nested == nil then fail("missing runNested export") end
                local okN, handleN = pcall(nested.f)
                if not okN then
                  fail("runNested: " .. tostring(handleN.code) .. "|"
                    .. tostring(handleN.m) .. "|" .. tostring(handleN.e) .. "|"
                    .. tostring(handleN.a) .. "|" .. tostring(handleN.o))
                end
                if type(handleN) ~= "table"
                    or handleN[__dealCarrierKeys.kind] ~= "async" then
                  fail("runNested did not return an async operation")
                end
                print("TRANSCRIPT-BEGIN")
                print("callback=" .. tostring(resC))
                print("async=" .. tostring(handleA.__result))
                print("nested=" .. tostring(handleN.__result))
                print("TRANSCRIPT-END")
                """);
            ProcessOutcome run = runProcess(List.of("luajit", "probe.lua"),
                out, true);
            check(run.exitCode() == 0
                    && run.stdout().equals(ARRAY_CROSSING_TRANSCRIPT)
                    && run.stderr().isEmpty(),
                "a host async completion value and a host-passed callback"
                    + " argument of a declared array type are the DEAL array"
                    + " value (exact length, exact elements, nested alias"
                    + " preserved): exit=" + run.exitCode() + " stdout="
                    + escaped(run.stdout()) + " stderr="
                    + escaped(run.stderr()));
        } finally {
            deleteRecursively(root);
        }
    }

    // =========================================================================
    // 2b. The host array crossing failures (ISSUE-0695 acceptance remediation)
    // =========================================================================

    /**
     * The declared host positions of the crossing negatives: async
     * completions and callback arguments outside the declared {@code int[]}
     * value mapping (a map table, a sparse table and a numeric carrier), a
     * callback invocation that omits a declared parameter, a callback with a
     * declared nullable parameter (the omitted position's null mapping), and
     * a callback whose function-typed parameter receives a raw Lua function
     * instead of the DEAL function value wrapper.
     */
    private static final String ARRAY_FAILURE_DECLARATION_SOURCE = """
        export async function produceMap(): number[];
        export async function produceSparse(): number[];
        export async function produceNum(): number[];
        export function consumeMap(f: (xs: number[]) => number): number;
        export function consumeSparse(f: (xs: number[]) => number): number;
        export function consumeNum(f: (xs: number[]) => number): number;
        export function consumeMissing(f: (x: number) => number): number;
        export function consumeNullable(f: (x: number | null) => number): number;
        export function consumeRawFn(f: (g: (x: number) => number) => number): number;
        """;

    /**
     * The DEAL side of the crossing negatives: the callback bodies read a
     * parameter the host did not supply and a function parameter the host
     * supplied raw, so an omitted parameter-entry cell leaves a body-local
     * read of nil and a raw host function reaches an internal index of the
     * function value instead of the closed typed-boundary failure.
     */
    private static final String ARRAY_FAILURE_APP_SOURCE = """
        import * as host from "host/arr_probe";

        function sum(xs: number[]): number {
          let total: number = 0;
          let i: number = 0;
          while (i < xs.length) {
            total = total + xs[i];
            i = i + 1;
          }
          return total;
        }

        function callbackTotal(xs: number[]): number {
          return sum(xs);
        }

        function increment(x: number): number {
          let y: number = x + 1;
          return y;
        }

        function nullableJoin(x: number | null): number {
          if (x === null) {
            return 7;
          } else {
            return x;
          }
        }

        function outer(g: (x: number) => number): number {
          return g(5);
        }

        export function runMap(): number { return host.consumeMap(callbackTotal); }
        export function runSparse(): number { return host.consumeSparse(callbackTotal); }
        export function runNum(): number { return host.consumeNum(callbackTotal); }
        export function runMissing(): number { return host.consumeMissing(increment); }
        export function runNullable(): number { return host.consumeNullable(nullableJoin); }
        export function runRawFn(): number { return host.consumeRawFn(outer); }

        export async function runAsyncMap(): number {
          let xs: number[] = await host.produceMap();
          return sum(xs);
        }

        export async function runAsyncSparse(): number {
          let xs: number[] = await host.produceSparse();
          return sum(xs);
        }

        export async function runAsyncNum(): number {
          let xs: number[] = await host.produceNum();
          return sum(xs);
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The deployed raw host: it completes a real async operation with a map
     * table, a sparse table and a number, and invokes the DEAL callback with
     * the same invalid shapes, with no arguments, and with a raw Lua
     * function at the declared function position.
     */
    private static final String ARRAY_FAILURE_LUA = """
        local rt = require("deal.runtime")
        return {
          produceMap = function()
            return rt.async_start(function() return { foo = "bar" } end)
          end,
          produceSparse = function()
            return rt.async_start(function() return { [1] = 7, [3] = 9 } end)
          end,
          produceNum = function()
            return rt.async_start(function() return 42 end)
          end,
          consumeMap = function(f) return f({ foo = "bar" }) end,
          consumeSparse = function(f) return f({ [1] = 7, [3] = 9 }) end,
          consumeNum = function(f) return f(42) end,
          consumeMissing = function(f) return f() end,
          consumeNullable = function(f) return f() end,
          consumeRawFn = function(f) return f(function(x) return x end) end,
        }
        """;

    /** The exact closed failures the crossing negatives must publish. */
    private static final String ARRAY_FAILURE_TRANSCRIPT = String.join("\n",
        "TRANSCRIPT-BEGIN",
        "runMap=E8001|expected array|array|table",
        "runSparse=E8001|expected array|array|table",
        "runNum=E8001|expected array|array|number",
        "runMissing=E8001|expected number|number|null",
        "runNullable=ok:7",
        "runRawFn=E8001|expected function|function|function",
        "runAsyncMap=E8001|expected array|array|table",
        "runAsyncSparse=E8001|expected array|array|table",
        "runAsyncNum=E8001|expected array|array|number",
        "TRANSCRIPT-END",
        "");

    /**
     * A host value outside the declared {@code T[]} mapping fails closed at
     * the crossing that receives it: the async completion value and the
     * callback argument take the position's typed-boundary cell (the map
     * table and the sparse table are never emptied or truncated, the numeric
     * carrier is never an empty array), an omitted callback parameter runs
     * its parameter-entry cell at every declared position (a non-nullable
     * position fails closed and a nullable position follows the null
     * mapping), and a raw Lua function at a declared function position takes
     * the closed kind row instead of an internal index failure — on the
     * published production artifact under real luajit.
     */
    private static void testLuaHostArrayCrossingFailures() throws Exception {
        System.out.println("-- the host array crossing failures: the published"
            + " LuaJIT artifact under real luajit --");
        Path root = Files.createTempDirectory("host-array-failure-lua");
        try {
            Path out = publishLuaHost(root, "arr_probe", ARRAY_FAILURE_DECLARATION_SOURCE,
                ARRAY_FAILURE_APP_SOURCE, ARRAY_FAILURE_LUA, "host-array-crossing-failure");
            if (out == null) {
                return;
            }
            writeFileIn(out, "probe.lua", """
                local function fail(m) print("PROBE-FAIL: " .. m) os.exit(1) end
                local function result(name, entry)
                  if entry == nil then fail("missing " .. name .. " export") end
                  local ok, value = pcall(entry.f)
                  if ok then return "ok:" .. tostring(value) end
                  if type(value) ~= "table" then
                    return "raw:" .. tostring(value)
                  end
                  return tostring(value.code) .. "|" .. tostring(value.m) .. "|"
                    .. tostring(value.e) .. "|" .. tostring(value.a)
                end
                dofile("app.lua")
                local ok, err = __dealMain()
                if not ok then fail("init: " .. tostring(err)) end
                local surface = __exportSurfaces["app"]
                print("TRANSCRIPT-BEGIN")
                local names = { "runMap", "runSparse", "runNum", "runMissing",
                  "runNullable", "runRawFn", "runAsyncMap", "runAsyncSparse",
                  "runAsyncNum" }
                for _, name in ipairs(names) do
                  print(name .. "=" .. result(name, surface[name]))
                end
                print("TRANSCRIPT-END")
                """);
            ProcessOutcome run = runProcess(List.of("luajit", "probe.lua"),
                out, true);
            check(run.exitCode() == 0
                    && run.stdout().equals(ARRAY_FAILURE_TRANSCRIPT)
                    && run.stderr().isEmpty(),
                "a host map table, a sparse table and a numeric carrier fail"
                    + " closed at the async-completion and callback-parameter"
                    + " crossings, an omitted callback argument runs its"
                    + " parameter-entry cell (nullable positions take the null"
                    + " mapping), and a raw host function takes the closed"
                    + " kind row: exit=" + run.exitCode() + " stdout="
                    + escaped(run.stdout()) + " stderr="
                    + escaped(run.stderr()));
        } finally {
            deleteRecursively(root);
        }
    }

    // =========================================================================
    // 2b. The host-class construction defaults (ISSUE-0855)
    // =========================================================================

    /** Declared layouts for provided arrays, nested classes, nulls and functions. */
    private static final String CLASS_DEFAULTS_DECLARATION_SOURCE = """
        export class Endpoint {
          path: string;
        }

        export class ServerConfig {
          port: number;
          endpoint: Endpoint;
          tags?: string[];
          note?: string | null;
          memo?: string;
          fallback?: Endpoint;
        }

        export class Bundle {
          label: string;
          action?: (x: number) => number;
        }

        export class OptionalOnly {
          flag?: string;
        }

        export function inspect(): string;

        export function tagsCarrier(s: ServerConfig): string;
        """;

    /**
     * Provided values retain their carriers and identity across host crossings.
     * Omitted optionals stay absent; independent literals do not share arrays.
     * Unrelated legacy host data must remain unread and unchanged.
     */
    private static final String CLASS_DEFAULTS_APP_SOURCE = """
        import * as cfg from "host/defaults_probe";

        function pathOf(e: cfg.Endpoint): string {
          return e.path;
        }

        export function run(): string {
          let a: cfg.ServerConfig = { port: 1, endpoint: { path: "/x" },
            tags: ["a", "b"], note: null, fallback: { path: "/fb" } };
          let tags: string[] | null = a.tags;
          if (tags !== null) {
            if (tags.length !== 2) {
              throw { code: "TEST_FAIL", message: "default tags length" };
            }
            if (tags[0] !== "a") {
              throw { code: "TEST_FAIL", message: "default tags first" };
            }
            if (tags[1] !== "b") {
              throw { code: "TEST_FAIL", message: "default tags second" };
            }
          } else {
            throw { code: "TEST_FAIL", message: "default tags absent" };
          }
          let carrier: string = cfg.tagsCarrier(a);
          if (carrier !== "true:2:nil:0:a:b") {
            throw { code: "TEST_FAIL", message: "default tags carrier: " + carrier };
          }
          if (tags !== null) {
            tags[0] = "mutated";
          }
          if (!has(a.note)) {
            throw { code: "TEST_FAIL", message: "present null default lost" };
          }
          if (a.note !== null) {
            throw { code: "TEST_FAIL", message: "present null default value" };
          }
          if (has(a.memo)) {
            throw { code: "TEST_FAIL", message: "absent default fabricated" };
          }
          if (a.memo !== null) {
            throw { code: "TEST_FAIL", message: "absent default read" };
          }
          let fallback: cfg.Endpoint | null = a.fallback;
          if (fallback !== null) {
            if (pathOf(fallback) !== "/fb") {
              throw { code: "TEST_FAIL", message: "nested class default value" };
            }
          } else {
            throw { code: "TEST_FAIL", message: "nested class default lost" };
          }
          let b: cfg.ServerConfig = { port: 2, endpoint: { path: "/y" }, tags: ["z"] };
          let bTags: string[] | null = b.tags;
          if (bTags !== null) {
            if (bTags.length !== 1) {
              throw { code: "TEST_FAIL", message: "provided tags length" };
            }
            if (bTags[0] !== "z") {
              throw { code: "TEST_FAIL", message: "provided tags element" };
            }
          } else {
            throw { code: "TEST_FAIL", message: "provided tags lost" };
          }
          let omitted: cfg.ServerConfig = { port: 0, endpoint: { path: "/o" } };
          if (has(omitted.tags) || has(omitted.note) || has(omitted.fallback)) {
            throw { code: "TEST_FAIL", message: "omitted optional fabricated" };
          }
          let c: cfg.ServerConfig = { port: 3, endpoint: { path: "/z" }, tags: ["a", "b"] };
          let cTags: string[] | null = c.tags;
          if (cTags !== null) {
            if (cTags.length !== 2) {
              throw { code: "TEST_FAIL", message: "second default tags length" };
            }
            if (cTags[0] !== "a") {
              throw { code: "TEST_FAIL", message: "second default tags first" };
            }
            if (cTags[1] !== "b") {
              throw { code: "TEST_FAIL", message: "second default tags second" };
            }
          } else {
            throw { code: "TEST_FAIL", message: "second default tags lost" };
          }
          let bundle: cfg.Bundle = { label: "b", action: function(x: number): number { return x + 1; } };
          let action: ((x: number) => number) | null = bundle.action;
          if (action !== null) {
            let r: number = action(41);
            if (r !== 42) {
              throw { code: "TEST_FAIL", message: "function default result" };
            }
          } else {
            throw { code: "TEST_FAIL", message: "function default lost" };
          }
          let state: string = cfg.inspect();
          if (state !== "a:b:nil:nil:class:/default:true:true:7080:function:42") {
            throw { code: "TEST_FAIL", message: "host defaults mutated: " + state };
          }
          return "DEFAULTS-OK";
        }

        export function badDefaults(): string {
          let e: cfg.OptionalOnly = {};
          if (has(e.flag)) {
            throw { code: "TEST_FAIL", message: "omitted flag fabricated" };
          }
          return "constructed";
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The deployed host module: its {@code ServerConfig_defaults} carries
     * the optional {@code tags} array, the nested {@code endpoint} and
     * {@code fallback} class instances, the present null {@code note}, and
     * the host's {@code __MISSING} {@code memo}; {@code Bundle_defaults}
     * carries the documented function wrapper. {@code inspect} reads the
     * module's own defaults objects after every construction, so an
     * in-place projection shows up as a changed element, an added carrier
     * mark, a converted class object, or a replaced sentinel.
     */
    private static final String CLASS_DEFAULTS_LUA = """
        local rt = require("deal.runtime")
        local K = __dealCarrierKeys
        local defaults = {
          port = 7080,
          endpoint = { [K.kind] = "class",
                       [K.classname] = "@$external/host/defaults_probe/Endpoint",
                       path = "/default" },
          tags = { "a", "b" },
          note = rt.__NULL,
          memo = rt.__MISSING,
          fallback = { [K.kind] = "class",
                       [K.classname] = "@$external/host/defaults_probe/Endpoint",
                       path = "/fb" },
        }
        local bundleDefaults = {
          label = "bundle",
          action = { [K.kind] = "function", sig = "(number)->number",
                     f = function(x) return x + 1 end },
        }
        return {
          Endpoint = { [K.kind] = "class",
                       [K.classname] = "@$external/host/defaults_probe/Endpoint" },
          Endpoint_defaults = { path = "/" },
          ServerConfig = { [K.kind] = "class",
                           [K.classname] = "@$external/host/defaults_probe/ServerConfig" },
          ServerConfig_defaults = defaults,
          Bundle = { [K.kind] = "class",
                     [K.classname] = "@$external/host/defaults_probe/Bundle" },
          Bundle_defaults = bundleDefaults,
          OptionalOnly = { [K.kind] = "class",
                           [K.classname] = "@$external/host/defaults_probe/OptionalOnly" },
          OptionalOnly_defaults = { flag = "f" },
          tagsCarrier = function(s)
            local t = s.tags
            local marks = t[K.nK]
            local count = 0
            if type(marks) == "table" then
              for _ in pairs(marks) do count = count + 1 end
            end
            return tostring(t[K.a]) .. ":" .. tostring(t[K.n]) .. ":"
              .. type(marks) .. ":" .. tostring(count) .. ":"
              .. tostring(t[1]) .. ":" .. tostring(t[2])
          end,
          inspect = function()
            return tostring(defaults.tags[1]) .. ":" .. tostring(defaults.tags[2])
              .. ":" .. tostring(defaults.tags[K.a]) .. ":" .. tostring(defaults.tags[K.n])
              .. ":" .. tostring(defaults.endpoint[K.kind]) .. ":" .. defaults.endpoint.path
              .. ":" .. tostring(defaults.note == rt.__NULL)
              .. ":" .. tostring(defaults.memo == rt.__MISSING)
              .. ":" .. tostring(defaults.port)
              .. ":" .. tostring(bundleDefaults.action[K.kind])
              .. ":" .. tostring(bundleDefaults.action.f(41))
          end,
        }
        """;

    /** The exact transcript of the construction-defaults drive. */
    private static final String CLASS_DEFAULTS_TRANSCRIPT = String.join("\n",
        "TRANSCRIPT-BEGIN",
        "DEFAULTS-OK",
        "constructed",
        "TRANSCRIPT-END",
        "");

    /** Real structural construction and host carrier crossings, without default hooks. */
    private static void testLuaHostClassConstructionDefaults() throws Exception {
        System.out.println("-- structural host-class construction: the "
            + "published LuaJIT artifact under real luajit --");
        Path root = Files.createTempDirectory("host-class-defaults-lua");
        try {
            Path out = publishLuaHost(root, "defaults_probe",
                CLASS_DEFAULTS_DECLARATION_SOURCE, CLASS_DEFAULTS_APP_SOURCE,
                CLASS_DEFAULTS_LUA, "host-class-construction-defaults");
            if (out == null) {
                return;
            }
            String lua = Files.readString(out.resolve("app.lua"),
                StandardCharsets.UTF_8);
            for (String identity : List.of("ServerConfig", "Endpoint",
                    "Bundle", "OptionalOnly")) {
                check(!lua.contains(identity + "_defaults"),
                    "construction never reads the retired hook for " + identity);
            }
            check(!lua.contains("__hostClassDefaultsIn"),
                "no retired defaults projection helper is emitted");
            check(lua.contains("__factories.__hostCarrierIn(childDesc, val, memo)"),
                "provided host crossings retain their declared carrier bridge");
            writeFileIn(out, "probe.lua", """
                local function fail(m) print("PROBE-FAIL: " .. m) os.exit(1) end
                dofile("app.lua")
                local ok, err = __dealMain()
                if not ok then
                  fail("init: " .. tostring(err and err.code) .. "|"
                    .. tostring(err and err.m))
                end
                local surface = __exportSurfaces["app"]
                local ok2, res = pcall(surface["run"].f)
                if not ok2 then
                  fail("run: " .. tostring(res and res.code) .. "|"
                    .. tostring(res and res.m) .. "|" .. tostring(res and res.e)
                    .. "|" .. tostring(res and res.a))
                end
                print("TRANSCRIPT-BEGIN")
                print(tostring(res))
                local hostSurface = __exportSurfaces["host.defaults_probe"]
                if type(hostSurface) ~= "table" then fail("no host surface") end
                hostSurface["OptionalOnly_defaults"] = 42
                local ok3, res3 = pcall(surface["badDefaults"].f)
                if not ok3 then fail("structural construction failed") end
                print(tostring(res3))
                print("TRANSCRIPT-END")
                """);
            ProcessOutcome run = runProcess(List.of("luajit", "probe.lua"),
                out, true);
            check(run.exitCode() == 0
                    && run.stdout().equals(CLASS_DEFAULTS_TRANSCRIPT)
                    && run.stderr().isEmpty(),
                "provided arrays, nested identities, present null, absent optionals"
                    + " and callable fields survive host crossings; retired host data"
                    + " is unchanged and arbitrary retired metadata is ignored: exit=" + run.exitCode() + " stdout="
                    + escaped(run.stdout()) + " stderr="
                    + escaped(run.stderr()));
        } finally {
            deleteRecursively(root);
        }
    }

    // =========================================================================
    // 2c. The synchronous host class crossings (ISSUE-0851)
    // =========================================================================

    /**
     * The declared synchronous class positions of the carrier drive: a class
     * parameter (declared and dynamically invoked), a nested class field and
     * an optional array/nullable field, a class return, a class-element
     * array return, a class-field array return, and the host return
     * negatives (a non-class value, a foreign identity, a non-class array
     * element).
     */
    private static final String SYNC_CLASS_DECLARATION_SOURCE = """
        export class Endpoint {
          path: string;
        }

        export class ServerConfig {
          port: number;
          endpoint: Endpoint;
          tags?: string[];
          note?: string | null;
        }

        export class Farm {
          name: string;
          servers: ServerConfig[];
          primary: Endpoint;
        }

        export class Bundle {
          label: string;
          action: (x: number) => number;
        }

        export function describe(s: ServerConfig): string;
        export function describePair(a: ServerConfig, b: ServerConfig): string;
        export function make(): ServerConfig;
        export function makeAll(): ServerConfig[];
        export function makeFarm(): Farm;
        export function describeBundle(b: Bundle, c: Bundle): string;
        export function inspectHost(): string;
        export function badClass(): ServerConfig;
        export function foreignClass(): ServerConfig;
        export function badArray(): ServerConfig[];
        """;

    /**
     * The DEAL side of the synchronous class crossings: a declared class
     * parameter (populated, and one omitting both optional fields so the
     * host observes the absent state at the parameter), an indirect and a
     * dynamic call with two aliased class arguments, a class return read at
     * its top-level declared fields (the nested class field, the absent
     * optional and the present null included), a class-element array return
     * and a class's array field whose two slots name one host instance, the
     * identity checks of the two class references, and the host's own
     * unmutated-object report.
     */
    private static final String SYNC_CLASS_APP_SOURCE = """
        import * as host from "host/probe";

        function aliasText(same: boolean): string {
          if (same) {
            return "same";
          }
          return "different";
        }

        function check(condition: boolean, message: string): string {
          if (!condition) {
            throw { code: "TEST_FAIL", message: message };
          }
          return "ok";
        }

        function callPair(
            f: (a: host.ServerConfig, b: host.ServerConfig) => string,
            c: host.ServerConfig): string {
          return f(c, c);
        }

        function bump(x: number): number {
          let y: number = x + 1;
          return y;
        }

        export function run(): string {
          let cfg: host.ServerConfig = {
            port: 9090,
            endpoint: { path: "/api" },
            tags: ["dev", "prod"],
            note: null,
          };
          let declared: string = host.describe(cfg);
          // Both optional fields omitted: the host observes the absent
          // top-level state at the declared class parameter (no fabricated
          // present null).
          let sparse: host.ServerConfig = {
            port: 4040,
            endpoint: { path: "/opt" },
          };
          let sparseText: string = host.describe(sparse);
          let pair: (a: host.ServerConfig, b: host.ServerConfig) => string =
            host.describePair;
          let indirect: string = pair(cfg, cfg);
          let dynamic: string = callPair(host.describePair, cfg);

          let made: host.ServerConfig = host.make();
          let tagsAbsent: boolean = made.tags === null;
          let noteNull: boolean = made.note === null;
          let madeCheck: string = check(
            made.port === 7
              && made.endpoint.path === "/made"
              && tagsAbsent
              && noteNull,
            "host class return fields");

          let all: host.ServerConfig[] = host.makeAll();
          let allCheck: string = check(
            all.length === 2
              && all[0] === all[1]
              && all[0].port === 11
              && all[0].endpoint.path === "/one",
            "class-element array return");

          let farm: host.Farm = host.makeFarm();
          let farmCheck: string = check(
            farm.name === "farm"
              && farm.servers.length === 2
              && farm.servers[0] === farm.servers[1]
              && farm.servers[0].port === 21
              && farm.servers[0].endpoint.path === "/a"
              && farm.primary.path === "/primary",
            "class-field array return");

          // A host class's function-typed field carrying a DEAL function
          // value: the class value needs a host-facing copy, so two aliased
          // arguments prove the per-call crossing memo (one copy, one host
          // object) and the function value arrives as the documented
          // wrapper the host invokes.
          let bundle: host.Bundle = { label: "b", action: bump };
          let bundleText: string = host.describeBundle(bundle, bundle);

          let inspect: string = host.inspectHost();

          return declared + "\\n"
            + sparseText + "\\n"
            + "indirect=" + indirect + "\\n"
            + "dynamic=" + dynamic + "\\n"
            + "made=" + madeCheck + "\\n"
            + "all=" + aliasText(all[0] === all[1]) + ":" + allCheck + "\\n"
            + "farm=" + aliasText(farm.servers[0] === farm.servers[1]) + ":"
            + farmCheck + "\\n" + bundleText + "\\n" + inspect;
        }

        export function badClass(): string {
          let made: host.ServerConfig = host.badClass();
          return "unreachable";
        }

        export function foreignClass(): string {
          let made: host.ServerConfig = host.foreignClass();
          return "unreachable";
        }

        export function badArray(): string {
          let made: host.ServerConfig[] = host.badArray();
          return "unreachable";
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The deployed raw host: every class value is the documented LuaJIT
     * mapping (a runtime class instance: __kind/__classname with top-level
     * declared fields), each declared class parameter is read at those
     * top-level fields (nested class, array, absent optional, present
     * null), and the returns name one host instance twice (the alias and
     * the unmutated-object report).
     */
    private static final String SYNC_CLASS_LUA = """
        local rt = require("deal.runtime")
        local K = __dealCarrierKeys

        local SC = "@$external/host/probe/ServerConfig"
        local EP = "@$external/host/probe/Endpoint"
        local FM = "@$external/host/probe/Farm"
        local BUNDLE = "@$external/host/probe/Bundle"

        local lastMade = nil
        local lastMadeEndpoint = nil
        local lastAll = nil
        local lastFarm = nil
        local lastFarmServers = nil

        local function endpoint(path)
          return { [K.kind] = "class", [K.classname] = EP, path = path }
        end

        local function config(port, path, tags, note)
          local c = { [K.kind] = "class", [K.classname] = SC,
                      port = port, endpoint = endpoint(path) }
          if tags ~= nil then c.tags = tags end
          if note ~= nil then c.note = note end
          return c
        end

        local function field(value, name)
          local v = value[name]
          if v == rt.__NULL then return "null" end
          return v
        end

        local M = {}

        M.Endpoint = { [K.kind] = "class", [K.classname] = EP }
        M.Endpoint_defaults = { path = "/" }
        M.ServerConfig = { [K.kind] = "class", [K.classname] = SC }
        M.ServerConfig_defaults = {
          port = 8080,
          endpoint = rt.__MISSING,
          tags = rt.__MISSING,
          note = rt.__MISSING,
        }
        M.Farm = { [K.kind] = "class", [K.classname] = FM }
        M.Farm_defaults = { name = "", servers = rt.__MISSING,
                            primary = rt.__MISSING }

        function M.describe(s)
          local tags = field(s, "tags")
          local tagText = "absent"
          if tags == "null" then
            tagText = "null"
          elseif type(tags) == "table" then
            tagText = tostring(#tags) .. ":" .. tostring(tags[1])
          end
          return "p=" .. tostring(s.port)
            .. ":ep=" .. tostring(s.endpoint.path)
            .. ":tags=" .. tagText
            .. ":note=" .. tostring(field(s, "note"))
        end

        function M.describePair(a, b)
          return "pair=" .. tostring(rawequal(a, b))
        end

        M.Bundle = { [K.kind] = "class", [K.classname] = BUNDLE }
        M.Bundle_defaults = { label = "", action = rt.__MISSING }

        function M.describeBundle(b, c)
          local action = b.action
          local result = "none"
          if type(action) == "table"
              and action[K.kind] == "function" then
            result = tostring(action.f(41))
          end
          return "bundle=" .. tostring(b.label)
            .. ":alias=" .. tostring(rawequal(b, c))
            .. ":action=" .. result
        end

        function M.make()
          lastMade = config(7, "/made", nil, rt.__NULL)
          lastMadeEndpoint = lastMade.endpoint
          return lastMade
        end

        function M.makeAll()
          local one = config(11, "/one", { "a" }, rt.__NULL)
          lastAll = { one, one }
          return lastAll
        end

        function M.makeFarm()
          local a = config(21, "/a", { "x", "y" }, rt.__NULL)
          lastFarmServers = { a, a }
          lastFarm = { [K.kind] = "class", [K.classname] = FM,
                       name = "farm", servers = lastFarmServers,
                       primary = endpoint("/primary") }
          return lastFarm
        end

        -- The host's own objects after the crossings: no chunk carrier mark
        -- was written onto them and the host's own aliases are untouched.
        function M.inspectHost()
          local marks = false
          if lastMade[K.c] ~= nil or lastMade[K.id] ~= nil
              or lastMade[K.f] ~= nil or lastMade[K.p] ~= nil then
            marks = true
          end
          if lastFarm[K.c] ~= nil or lastFarm[K.f] ~= nil
              or lastFarm[K.p] ~= nil then
            marks = true
          end
          return "inspect=made:" .. tostring(lastMade[K.kind]) .. ":"
            .. tostring(lastMade.port) .. ":" .. lastMade.endpoint.path
            .. ":ep=" .. tostring(rawequal(lastMade.endpoint, lastMadeEndpoint))
            .. ":all=" .. tostring(rawequal(lastAll[1], lastAll[2]))
            .. ":farm=" .. tostring(lastFarm[K.kind]) .. ":"
            .. tostring(lastFarm.name)
            .. ":servers=" .. tostring(rawequal(lastFarmServers[1],
              lastFarmServers[2]))
            .. ":marks=" .. tostring(marks)
        end

        function M.badClass()
          return "junk"
        end

        function M.foreignClass()
          return endpoint("/foreign")
        end

        function M.badArray()
          return { { port = 1 } }
        end

        return M
        """;

    /** The exact transcript the synchronous class crossings must publish. */
    private static final String SYNC_CLASS_TRANSCRIPT = String.join("\n",
        "TRANSCRIPT-BEGIN",
        "p=9090:ep=/api:tags=2:dev:note=null",
        "p=4040:ep=/opt:tags=absent:note=nil",
        "indirect=pair=true",
        "dynamic=pair=true",
        "made=ok",
        "all=same:ok",
        "farm=same:ok",
        "bundle=b:alias=true:action=42",
        "inspect=made:class:7:/made:ep=true:all=true:farm=class:farm:servers=true:marks=false",
        "badClass=E8010|return value 1 type mismatch: expected class instance",
        "foreignClass=E8010|return value 1 type mismatch: expected instance of "
            + "@$external/host/probe/ServerConfig, got "
            + "@$external/host/probe/Endpoint",
        "badArray=E8010|return value 1 type mismatch: array element 1 type mismatch",
        "TRANSCRIPT-END",
        "");

    /**
     * The synchronous host class crossings on the published LuaJIT artifact
     * (ISSUE-0851): a populated and a sparse declared class parameter (both
     * optional fields omitted, so the host observes the absent state) and an
     * indirect and a dynamic call with two aliased class arguments hand the
     * host the declared top-level fields; a class return, a class-element
     * array return and a class's array field arrive on the DEAL side as the
     * declared values (nested class field, array field, absent optional,
     * present null, exact length/elements, and two slots naming one host
     * instance as one DEAL value) while the host's own object stays
     * unmutated; and the host return negatives keep their exact landed
     * tuples.
     */
    private static void testLuaHostClassCrossings() throws Exception {
        System.out.println("-- the synchronous host class crossings: the published"
            + " LuaJIT artifact under real luajit --");
        Path root = Files.createTempDirectory("host-class-lua");
        try {
            Path out = publishLuaHost(root, "probe", SYNC_CLASS_DECLARATION_SOURCE,
                SYNC_CLASS_APP_SOURCE, SYNC_CLASS_LUA, "host-class-crossing");
            if (out == null) {
                return;
            }
            writeFileIn(out, "probe.lua", """
                local function fail(m) print("PROBE-FAIL: " .. m) os.exit(1) end
                local function result(name, entry)
                  if entry == nil then fail("missing " .. name .. " export") end
                  local ok, value = pcall(entry.f)
                  if ok then return tostring(value) end
                  if type(value) ~= "table" then
                    return "raw:" .. tostring(value)
                  end
                  return tostring(value.code) .. "|" .. tostring(value.m)
                end
                dofile("app.lua")
                local ok, err = __dealMain()
                if not ok then fail("init: " .. tostring(err)) end
                local surface = __exportSurfaces["app"]
                print("TRANSCRIPT-BEGIN")
                print(result("run", surface["run"]))
                print("badClass=" .. result("badClass", surface["badClass"]))
                print("foreignClass="
                  .. result("foreignClass", surface["foreignClass"]))
                print("badArray=" .. result("badArray", surface["badArray"]))
                print("TRANSCRIPT-END")
                """);
            // The emitted sites (structure): the declared-position
            // projection keeps its one operand shape and delegates every
            // non-function position to the bridge's outbound direction; the
            // return cell and the wrapper projection delegate to the
            // inbound direction; and no synchronous return site reaches the
            // retired chunk class carrier helper (the FFI construction keeps
            // its own call).
            String lua = Files.readString(out.resolve("app.lua"),
                StandardCharsets.UTF_8);
            int projectArgAt = lua.indexOf(
                "local function __hostProjectArg(desc, inner, index, v, origin, cells)");
            check(projectArgAt > 0,
                "the declared-position projection keeps its emitted operand shape");
            check(projectArgAt > 0
                    && lua.substring(projectArgAt, lua.indexOf("\nend", projectArgAt))
                        .contains("__factories.__hostCarrierOut(desc, v, memo)"),
                "the declared-position projection delegates every non-function"
                    + " position to the bridge's outbound direction");
            int returnCellAt = lua.indexOf(
                "local function __hostReturnCell(desc, v, origin, nothing)");
            check(returnCellAt > 0
                    && lua.substring(returnCellAt, lua.indexOf("\nend", returnCellAt))
                        .contains("__factories.__hostCarrierIn(desc, checked, memo)"),
                "the host return cell delegates to the bridge's inbound direction");
            int dealProjectAt = lua.indexOf(
                "local function __hostDealProject(desc, v, origin)");
            check(dealProjectAt > 0
                    && lua.substring(dealProjectAt, lua.indexOf("\nend", dealProjectAt))
                        .contains("__factories.__hostCarrierIn(desc, v, {})"),
                "the wrapper projection delegates to the bridge's inbound"
                    + " direction");
            checkEq(1, countOccurrences(lua, "__hostDealProject("),
                "no synchronous crossing composes the retired chunk class"
                    + " carrier (only the helper declaration remains)");
            ProcessOutcome run = runProcess(List.of("luajit", "probe.lua"),
                out, true);
            check(run.exitCode() == 0
                    && run.stdout().equals(SYNC_CLASS_TRANSCRIPT)
                    && run.stderr().isEmpty(),
                "a synchronous class parameter, a class return, a"
                    + " class-element array return and a class's array field"
                    + " carry the declared values in both directions (aliases,"
                    + " absent/present-null and the unmutated host object"
                    + " preserved) and the host return negatives keep their"
                    + " exact tuples: exit=" + run.exitCode() + " stdout="
                    + escaped(run.stdout()) + " stderr="
                    + escaped(run.stderr()));
        } finally {
            deleteRecursively(root);
        }
    }

    // =========================================================================
    // 2c-bis. The asynchronous host-start parameter crossings (ISSUE-0852)
    // =========================================================================

    /**
     * The declared asynchronous host positions of the async-start crossing
     * drive: a class parameter carrying a nested class field, an optional
     * array and a present null plus a declared function parameter, a class
     * parameter pair for the alias assertion, and a class whose
     * function-typed field forces a host-facing copy.
     */
    private static final String ASYNC_START_DECLARATION_SOURCE = """
        export class Endpoint {
          path: string;
        }

        export class ServerConfig {
          port: number;
          endpoint: Endpoint;
          tags?: string[];
          note?: string | null;
        }

        export class Bundle {
          label: string;
          action: (x: number) => number;
        }

        export async function describeAsync(s: ServerConfig, g: (x: number) => number): string;
        export async function describeBundlesAsync(a: Bundle, b: Bundle): string;
        """;

    /**
     * The DEAL side of the asynchronous host-start crossings: the declared
     * async host call, the runtime-resolved async host call (the callee
     * value's execution class is selected at execution), and the
     * arity-extended adapter-over-async host call once per nested-source
     * shape (a statically fixed host source and a runtime-resolved source),
     * where the extra target parameter is evaluated at the outer adapter
     * start and never forwarded. The last crossing passes two aliased class
     * arguments whose function-typed field needs a host-facing copy, so the
     * one crossing memo makes them one host object.
     */
    private static final String ASYNC_START_APP_SOURCE = """
        import * as host from "host/async_probe";

        class Marker {
          count: number;
        }

        function bump(x: number): number {
          let y: number = x + 1;
          return y;
        }

        function check(condition: boolean, message: string): string {
          if (!condition) {
            throw { code: "TEST_FAIL", message: message };
          }
          return "ok";
        }

        function pick(): async (s: host.ServerConfig, g: (x: number) => number) => string {
          return host.describeAsync;
        }

        export async function run(): string {
          let cfg: host.ServerConfig = {
            port: 9090,
            endpoint: { path: "/api" },
            tags: ["dev"],
            note: null,
          };
          let cb: (x: number) => number = bump;
          // The declared async host call (the statically resolved host
          // start under RUN).
          let direct: string = await host.describeAsync(cfg, cb);
          // The runtime-resolved async host call: the callee value's
          // execution class resolves to the loaded host surface entry at
          // execution (the dynamic HOST arm under RUN).
          let dyn: async (s: host.ServerConfig, g: (x: number) => number) => string =
            pick();
          let dynamicText: string = await dyn(cfg, cb);
          let marker: Marker = { count: 0 };
          let extra = function(): number {
            marker.count = marker.count + 1;
            return 1;
          };
          // The arity-extended adapter-over-async host call, statically fixed
          // nested source: the source binding is never assigned, so the
          // adapted declaration records the value it was created over and
          // the nested source start is the emitted static host call.
          let valueSrc: async (s: host.ServerConfig, g: (x: number) => number) => string =
            host.describeAsync;
          let fixed: async (s: host.ServerConfig, g: (x: number) => number,
                            extra: number) => string = valueSrc;
          let fixedText: string = await fixed(cfg, cb, extra());
          // The arity-extended adapter-over-async host call over an assigned
          // source binding: the reassignment defeats the source binding's
          // immutability proof (the shared-cell source mode) and a read of
          // that binding observes the current value at every invocation, so
          // the source's execution class is selected at execution (the
          // runtime-resolved nested start, the dynamic HOST arm under
          // ELIDED_BY_ADAPTER).
          let src: async (s: host.ServerConfig, g: (x: number) => number) => string =
            host.describeAsync;
          src = src;
          let sharedFixed: async (s: host.ServerConfig, g: (x: number) => number,
                                  extra: number) => string = src;
          let sharedFixedText: string = await sharedFixed(cfg, cb, extra());
          // The arity-extended adapter-over-async host call, runtime-resolved
          // nested source: the materialized source value's execution class is
          // selected at execution (the dynamic HOST arm under
          // ELIDED_BY_ADAPTER).
          let dynSrc: async (s: host.ServerConfig, g: (x: number) => number) => string =
            pick();
          let dynFixed: async (s: host.ServerConfig, g: (x: number) => number,
                               extra: number) => string = dynSrc;
          let dynText: string = await dynFixed(cfg, cb, extra());
          // Two aliased class arguments whose function-typed field needs a
          // host-facing copy: the one crossing memo makes them one object.
          let bundle: host.Bundle = { label: "b", action: bump };
          let pairText: string = await host.describeBundlesAsync(bundle, bundle);
          return "direct=" + direct + "\\n"
            + "dynamic=" + dynamicText + "\\n"
            + "fixed=" + fixedText + "\\n"
            + "sharedFixed=" + sharedFixedText + "\\n"
            + "dynFixed=" + dynText + "\\n"
            + "pair=" + pairText + "\\n"
            + "same=" + check(fixedText === direct, "fixed result")
            + ":" + check(sharedFixedText === direct, "shared-cell source result")
            + ":" + check(dynText === direct, "runtime-resolved result")
            + ":" + check(dynamicText === direct, "dynamic result")
            + ":extra=" + check(marker.count === 3, "extra evaluation");
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The deployed raw host: every class parameter is read at the documented
     * top-level class fields, every function parameter is invoked with the
     * declared argument (and with a value outside the declared parameter
     * type, proving the parameter-entry cell ran), and each async operation
     * reports the exact argument count it received — the arity-extended
     * calls must never forward their extra target parameter.
     */
    private static final String ASYNC_START_LUA = """
        local rt = require("deal.runtime")
        local K = __dealCarrierKeys

        local SC = "@$external/host/async_probe/ServerConfig"
        local EP = "@$external/host/async_probe/Endpoint"
        local BUNDLE = "@$external/host/async_probe/Bundle"

        local M = {}

        M.Endpoint = { [K.kind] = "class", [K.classname] = EP }
        M.Endpoint_defaults = { path = "/" }
        M.ServerConfig = { [K.kind] = "class", [K.classname] = SC }
        M.ServerConfig_defaults = { port = 0, endpoint = rt.__MISSING,
                                    tags = rt.__MISSING, note = rt.__MISSING }
        M.Bundle = { [K.kind] = "class", [K.classname] = BUNDLE }
        M.Bundle_defaults = { label = "", action = rt.__MISSING }

        local function describe(n, s, g)
          local tags = s.tags
          local tagsText = "absent"
          if tags == rt.__NULL then
            tagsText = "null"
          elseif type(tags) == "table" then
            tagsText = tostring(#tags) .. ":" .. tostring(tags[1])
          end
          local note = s.note
          local noteText = "nil"
          if note == rt.__NULL then
            noteText = "null"
          elseif note ~= nil then
            noteText = tostring(note)
          end
          local gText = type(g)
          if type(g) == "function" then
            local ok, value = pcall(g, 41)
            local okBad, bad = pcall(g, "x")
            local badText = "none"
            if not okBad and type(bad) == "table" then
              badText = tostring(bad.code) .. "|" .. tostring(bad.m)
            end
            gText = tostring(ok) .. ":" .. tostring(value) .. ":" .. badText
          end
          return "n=" .. tostring(n) .. ":port=" .. type(s.port) .. ":"
            .. tostring(s.port) .. ":ep=" .. tostring(s.endpoint.path)
            .. ":tags=" .. tagsText .. ":note=" .. noteText .. ":g=" .. gText
        end

        function M.describeAsync(...)
          local n = select("#", ...)
          local s, g = ...
          return rt.async_start(function()
            return "SC|" .. describe(n, s, g)
          end)
        end

        function M.describeBundlesAsync(...)
          local n = select("#", ...)
          local a, b = ...
          local actionText = type(a.action)
          if type(a.action) == "table" and a.action[K.kind] == "function" then
            local ok, value = pcall(a.action.f, 41)
            actionText = tostring(ok) .. ":" .. tostring(value)
          end
          return rt.async_start(function()
            return "B|n=" .. tostring(n) .. ":alias=" .. tostring(rawequal(a, b))
              .. ":kind=" .. tostring(a[K.kind]) .. ":label=" .. tostring(a.label)
              .. ":action=" .. actionText
          end)
        end

        return M
        """;

    /** The exact transcript the asynchronous host-start crossings publish. */
    private static final String ASYNC_START_TRANSCRIPT = String.join("\n",
        "TRANSCRIPT-BEGIN",
        "direct=SC|n=2:port=number:9090:ep=/api:tags=1:dev:note=null:g=true:42:"
            + "E8001|expected number",
        "dynamic=SC|n=2:port=number:9090:ep=/api:tags=1:dev:note=null:g=true:42:"
            + "E8001|expected number",
        "fixed=SC|n=2:port=number:9090:ep=/api:tags=1:dev:note=null:g=true:42:"
            + "E8001|expected number",
        "sharedFixed=SC|n=2:port=number:9090:ep=/api:tags=1:dev:note=null:g=true:"
            + "42:E8001|expected number",
        "dynFixed=SC|n=2:port=number:9090:ep=/api:tags=1:dev:note=null:g=true:42:"
            + "E8001|expected number",
        "pair=B|n=2:alias=true:kind=class:label=b:action=true:42",
        "same=ok:ok:ok:ok:extra=ok",
        "TRANSCRIPT-END",
        "");

    /**
     * The asynchronous host-start parameter crossings on the published
     * LuaJIT artifact (ISSUE-0852): the declared host start and the
     * runtime-resolved host start hand the raw host the declared class value
     * (nested class field, array field and present null at their top-level
     * declared fields) and the declared function value (the documented
     * wrapper the loaded surface adapts to the adapter closure, which the
     * host invokes with the declared result and whose parameter-entry cell
     * rejects an out-of-type argument); the arity-extended
     * adapter-over-async host calls project and forward exactly the leading
     * target parameters at every nested-source shape — the never-assigned
     * source binding's statically fixed nested start, the assigned source
     * binding's runtime-resolved nested start, and the materialized
     * (call-result) source's runtime-resolved nested start — the extra target
     * parameter is evaluated in DEAL and never forwarded, and every call
     * returns the declared result; and two aliased class arguments of one
     * start cross as one host object. The emitted artifact keeps the
     * declared-position projection as slot rewrites before the host call,
     * passes only the one-based carrier slots and the literal span triplet,
     * and no longer references the retired one-value argument projection.
     */
    private static void testLuaHostAsyncStartCrossings() throws Exception {
        System.out.println("-- the asynchronous host-start parameter crossings:"
            + " the published LuaJIT artifact under real luajit --");
        Path root = Files.createTempDirectory("host-async-start-lua");
        try {
            Path out = publishLuaHost(root, "async_probe",
                ASYNC_START_DECLARATION_SOURCE, ASYNC_START_APP_SOURCE,
                ASYNC_START_LUA, "async-start-crossing");
            if (out == null) {
                return;
            }
            writeFileIn(out, "probe.lua", """
                local function fail(m) print("PROBE-FAIL: " .. m) os.exit(1) end
                dofile("app.lua")
                local ok, err = __dealMain()
                if not ok then fail("init: " .. tostring(err)) end
                local surface = __exportSurfaces["app"]
                local entry = surface["run"]
                if entry == nil then fail("missing run export") end
                local okA, handle = pcall(entry.f)
                if not okA then fail("run: " .. tostring(handle)) end
                if type(handle) ~= "table"
                    or handle[__dealCarrierKeys.kind] ~= "async" then
                  fail("run did not return an async operation")
                end
                print("TRANSCRIPT-BEGIN")
                print(tostring(handle.__result))
                print("TRANSCRIPT-END")
                """);
            String lua = Files.readString(out.resolve("app.lua"),
                StandardCharsets.UTF_8);
            List<String> lines = lua.lines().toList();
            check(!lua.contains("__hostAsyncArg"),
                "the emitted artifact no longer references the retired"
                    + " one-value async argument projection");
            // The declared host start and the statically fixed nested
            // adapter start: the same converged host call shape, the
            // projection slot rewrites before the call, and only the
            // one-based carrier slots plus the literal span triplet in the
            // argument list.
            List<String> staticCalls = new ArrayList<>();
            for (String line : lines) {
                if (line.contains("pcall((__exportSurfaces["
                        + "\"host.async_probe\"][\"describeAsync\"]).f,")) {
                    staticCalls.add(line);
                }
            }
            checkEq(2, staticCalls.size(),
                "the declared host start and the statically fixed nested"
                    + " adapter start (the never-assigned source binding) are"
                    + " the emitted static host async call sites");
            for (String call : staticCalls) {
                checkHostAsyncCallShape(lines, call);
            }
            // The runtime-resolved host starts' dynamic HOST arm: the same
            // projection and the same call shape for the callee read that is
            // only known at execution, for the arity-extended adapter over an
            // assigned source binding, and for the arity-extended adapter
            // over a materialized (call-result) source.
            List<String> dynamicCalls = new ArrayList<>();
            for (String line : lines) {
                if (line.contains("pcall(__dynC.f,")) {
                    dynamicCalls.add(line);
                }
            }
            checkEq(3, dynamicCalls.size(),
                "the runtime-resolved host starts emit the dynamic HOST arm"
                    + " (the callee read, the assigned-source adapter and the"
                    + " materialized-source adapter)");
            for (String call : dynamicCalls) {
                checkHostAsyncCallShape(lines, call);
            }
            // The arity-extended calls: each outer adapter start records the
            // three target parameters, while every nested host start carries
            // and forwards exactly the leading two.
            checkEq(3, countOccurrences(lua, ".__argn = 3"),
                "every arity-extended adapter start records the three target"
                    + " arguments");
            ProcessOutcome run = runProcess(List.of("luajit", "probe.lua"),
                out, true);
            check(run.exitCode() == 0
                    && run.stdout().equals(ASYNC_START_TRANSCRIPT)
                    && run.stderr().isEmpty(),
                "the declared, the runtime-resolved and the three"
                    + " arity-extended asynchronous host crossings preserve the"
                    + " declared"
                    + " class and function arguments (the extra target"
                    + " argument evaluated in DEAL and never forwarded) and"
                    + " two aliased class arguments cross as one host object:"
                    + " exit=" + run.exitCode() + " stdout="
                    + escaped(run.stdout()) + " stderr="
                    + escaped(run.stderr()));
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * One emitted host async call line: the trailing argument list holds
     * exactly the one-based carrier slots {@code 1..argn} of one
     * {@code S.__sa<op>} table plus the literal span triplet, and the
     * declared-position projection slot rewrites for those positions precede
     * the call.
     */
    private static void checkHostAsyncCallShape(List<String> lines, String call) {
        String calleeSuffix = ".f,";
        int at = call.indexOf(calleeSuffix + " ");
        check(at >= 0, "the host async call carries the loaded entry callee: "
            + call);
        if (at < 0) {
            return;
        }
        String tail = call.substring(at + calleeSuffix.length() + 1);
        check(tail.endsWith(")"), "the host async call closes its argument"
            + " list: " + call);
        if (!tail.endsWith(")")) {
            return;
        }
        String[] tokens = tail.substring(0, tail.length() - 1).split(", ");
        checkEq(5, tokens.length, "the host async call passes the one-based"
            + " carrier slots and the literal span triplet only: " + call);
        if (tokens.length != 5) {
            return;
        }
        String carrier = tokens[0].substring(0, tokens[0].lastIndexOf('['));
        checkEq(carrier + "[1]", tokens[0],
            "the first host async argument is the carrier's first slot: " + call);
        checkEq(carrier + "[2]", tokens[1],
            "the second host async argument is the carrier's second slot: "
                + call);
        check(tokens[2].startsWith("\"") && tokens[2].endsWith("\"")
                && tokens[3].chars().allMatch(Character::isDigit)
                && tokens[4].chars().allMatch(Character::isDigit),
            "the trailing host async arguments are the literal span triplet: "
                + call);
        int callIndex = lines.indexOf(call);
        int first = indexOfLineStartingWith(lines, carrier + "[1] = __hostProjectArg(");
        int second = indexOfLineStartingWith(lines, carrier + "[2] = __hostProjectArg(");
        check(first >= 0 && first < callIndex,
            "the first argument's declared-position projection precedes the"
                + " host async call: " + call);
        check(second > first && second < callIndex,
            "the second argument's declared-position projection precedes the"
                + " host async call: " + call);
        int construction = indexOfLineStartingWith(lines, carrier + " = {");
        check(construction >= 0 && construction < callIndex,
            "the carrier table is built before the host async call: " + call);
        if (construction >= 0) {
            String built = lines.get(construction).trim();
            String operands = built.substring(
                built.indexOf('{') + 1, built.length() - 1);
            String[] slots = operands.isEmpty() ? new String[0]
                : operands.split(", ");
            if (slots.length > 0) {
                // The nested adapter start (ELIDED_BY_ADAPTER): the carrier
                // holds exactly the leading completed operand values.
                checkEq(2, slots.length, "the nested host async start carries"
                    + " exactly the leading two completed arguments: " + call);
                for (String slot : slots) {
                    check(slot.startsWith("S."), "the nested host async start"
                        + " carries the completed argument values: " + call);
                }
            } else {
                // The declared host start (RUN): the carrier starts empty and
                // every declared position stores its admitted value.
                check(indexOfLineStartingWith(lines,
                        carrier + "[1] = __chkB") >= 0
                    && indexOfLineStartingWith(lines,
                        carrier + "[2] = __chkB") >= 0
                    && indexOfLineStartingWith(lines,
                        carrier + "[3] = __chkB") < 0,
                    "the declared host async start admits and stores exactly"
                        + " its two declared parameters: " + call);
            }
        }
        check(indexOfLineStartingWith(lines, carrier + ".__argn = 2") >= 0,
            "the carrier records exactly the two completed host arguments: "
                + call);
        check(indexOfLineStartingWith(lines, carrier + ".__argn = 3") < 0,
            "the host async call never forwards the extra target parameter: "
                + call);
    }

    /** The first line whose trimmed text starts with the given prefix. */
    private static int indexOfLineStartingWith(List<String> lines, String prefix) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    /** The first line containing the given text. */
    private static int indexOfLineContaining(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    // =========================================================================
    // 2d. The host async completion crossing (ISSUE-0853)
    // =========================================================================

    /**
     * The declared async completion positions (ISSUE-0853): a host class
     * with a nested class field, a second field naming the same host
     * instance (the crossing's alias), an array field, a
     * declared-{@code number} array field, an absent optional and a present
     * null; a class-element array; a table carrying a class member and a
     * host-provided function member; a plain table; a numeric completion;
     * and a function completion.
     */
    private static final String COMPLETION_DECLARATION_SOURCE = """
        export class Endpoint {
          path: string;
        }

        export class ServerConfig {
          port: number;
          endpoint: Endpoint;
          backup: Endpoint;
          tags?: string[];
          label?: string;
          note?: string | null;
          scores: number[];
        }

        export async function make(): ServerConfig;
        export async function makeAll(): Endpoint[];
        export async function makeBundle(): table;
        export async function makePlain(): table;
        export async function makeNumber(): number;
        export async function makeCallback(): (x: number) => number;
        export async function makeBadArray(): number[];
        export async function makeNull(): string | null;
        export function checkClean(): string;
        export function inspectPlain(): string;
        export function inspectBundle(): string;
        export function checkCallback(): string;
        export function bag(): table;
        """;

    /**
     * The DEAL side of the host async completion crossing: every completion
     * value is read back at its declared type after {@code await} — the
     * nested class field, the aliased second field (one DEAL value), the
     * tags array and the absent optional, the present null, a
     * class-element array with its two alias slots, a table carrying a
     * class member and a host-provided function member the DEAL side
     * invokes, a plain table the DEAL side writes to (the host observes the
     * write), the numeric completion, and the function completion.
     */
    private static final String COMPLETION_APP_SOURCE = """
        import * as host from "host/complete_probe";
        import * as json from "std/json";

        function joined(xs: string[]): string {
          let out: string = "";
          let i: number = 0;
          while (i < xs.length) {
            if (i > 0) {
              out = out + ",";
            }
            out = out + xs[i];
            i = i + 1;
          }
          return out;
        }

        export async function runClass(): string {
          let s: host.ServerConfig = await host.make();
          let bag: table = host.bag();
          bag.cfg = s;
          bag.scores = s.scores;
          if (s.endpoint !== s.backup) {
            throw { code: "TEST_FAIL", message: "completion field alias" };
          }
          let tags: string[] | null = s.tags;
          let tagText: string = "absent";
          if (tags !== null) {
            tagText = joined(tags);
          }
          let label: string | null = s.label;
          if (label !== null) {
            throw { code: "TEST_FAIL", message: "absent optional" };
          }
          let note: string | null = s.note;
          let noteText: string = "absent";
          if (note === null) {
            noteText = "null";
          }
          let header: string = json.stringify({ port: s.port });
          return header + ":" + s.endpoint.path + ":" + tagText + ":"
            + noteText + ":host=" + host.checkClean();
        }

        export async function runAll(): string {
          let xs: host.Endpoint[] = await host.makeAll();
          if (xs.length !== 2) {
            throw { code: "TEST_FAIL", message: "class array length" };
          }
          if (xs[0] !== xs[1]) {
            throw { code: "TEST_FAIL", message: "class array alias" };
          }
          return json.stringify({ length: xs.length, path: xs[0].path });
        }

        export async function runBundle(): string {
          let t: table = await host.makeBundle();
          let endpoint: host.Endpoint = t.endpoint;
          let alias: host.Endpoint = t.alias;
          if (endpoint !== alias) {
            throw { code: "TEST_FAIL", message: "table copy alias" };
          }
          let bump: (x: number) => number = t.bump;
          let value: number = bump(41);
          return endpoint.path + ":" + json.stringify({ value: value })
            + ":host=" + host.inspectBundle();
        }

        export async function runPlain(): string {
          let t: table = await host.makePlain();
          t.extra = 9;
          return host.inspectPlain();
        }

        export async function runNumber(): string {
          let n: number = await host.makeNumber();
          return json.stringify({ ms: n });
        }

        export async function runCallback(): string {
          let g: (x: number) => number = await host.makeCallback();
          let value: number = g(41);
          return json.stringify({ value: value })
            + ":host=" + host.checkCallback();
        }

        export function peek(): table {
          return host.bag();
        }

        export async function runBad(): string {
          let xs: number[] = await host.makeBadArray();
          return "unreachable";
        }

        export async function runNull(): string {
          let v: string | null = await host.makeNull();
          if (v !== null) {
            throw { code: "TEST_FAIL", message: "null completion" };
          }
          return "null";
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The deployed raw host for the async completion crossing: every
     * declared async function returns a real async operation whose
     * completion is the documented LuaJIT value — a {@code rt.class_}
     * instance for the declared host class (nested class field, the same
     * instance in a second field, a plain tags table, an absent optional
     * and the runtime null sentinel as the present null), a plain Lua table
     * with one-based contiguous storage of the one shared class instance,
     * a table carrying a class member and the documented function wrapper,
     * a plain table, a number outside the signed32 range, and the function
     * wrapper itself. The reporting entries let the DEAL side assert that
     * the host's own objects were never mutated and that the plain table
     * kept its shared identity.
     */
    private static final String COMPLETION_HOST_LUA = """
        local rt = require("deal.runtime")
        local K = rt.__keys

        local endpoint = {[K.kind]="class", [K.classname]="@$external/host/complete_probe/Endpoint", path="/e"}
        local tags = { "a", "b" }
        local scores = { 1.5, 2 }
        local cfg = {[K.kind]="class", [K.classname]="@$external/host/complete_probe/ServerConfig",
          port=7, endpoint=endpoint, backup=endpoint, tags=tags, note=rt.__NULL, scores=scores}

        local shared = {[K.kind]="class", [K.classname]="@$external/host/complete_probe/Endpoint", path="/x"}

        local wrapper = { [K.kind] = "function", sig = "(number)->number",
          f = function(x) return x + 1 end }
        local bundle = { endpoint = endpoint, alias = endpoint,
          bump = wrapper }
        local plain = { a = 1 }
        local bag = {}

        return {
          Endpoint = { [K.kind] = "class",
            [K.classname] = "@$external/host/complete_probe/Endpoint" },
          Endpoint_defaults = { path = "/" },
          ServerConfig = { [K.kind] = "class",
            [K.classname] = "@$external/host/complete_probe/ServerConfig" },
          ServerConfig_defaults = { port = 0, endpoint = rt.__MISSING,
            backup = rt.__MISSING, tags = rt.__MISSING, label = rt.__MISSING,
            note = rt.__MISSING, scores = rt.__MISSING },

          make = function()
            return rt.async_start(function() return cfg end)
          end,
          makeAll = function()
            return rt.async_start(function() return { shared, shared } end)
          end,
          makeBundle = function()
            return rt.async_start(function() return bundle end)
          end,
          makePlain = function()
            return rt.async_start(function() return plain end)
          end,
          makeNumber = function()
            return rt.async_start(function() return 1.0e17 end)
          end,
          makeCallback = function()
            return rt.async_start(function() return wrapper end)
          end,
          makeBadArray = function()
            return rt.async_start(function() return { "x" } end)
          end,
          makeNull = function()
            return rt.async_start(function() return rt.__NULL end)
          end,

          checkClean = function()
            return tostring(cfg[K.c] == nil and cfg[K.id] == nil
                and cfg.label == nil and endpoint[K.c] == nil
                and tags[K.a] == nil and scores[K.a] == nil)
              .. "/" .. tostring(cfg.port)
              .. "/" .. tostring(cfg.note == rt.__NULL)
              .. "/" .. tostring(cfg.tags == tags)
          end,
          inspectPlain = function()
            return tostring(plain.a) .. "/" .. tostring(plain.extra)
          end,
          inspectBundle = function()
            return tostring(bundle.endpoint == endpoint) .. "/"
              .. tostring(bundle.extra) .. "/"
              .. tostring(bundle.bump == wrapper)
          end,
          checkCallback = function()
            return tostring(wrapper[K.c] == nil and wrapper[K.fn] == nil
              and wrapper[K.kind] == "function"
              and wrapper.sig == "(number)->number")
          end,
          bag = function() return bag end,
        }
        """;

    /** The exact results the host async completion crossing must publish. */
    private static final String COMPLETION_TRANSCRIPT = String.join("\n",
        "TRANSCRIPT-BEGIN",
        "class={\"port\":7.0}:/e:a,b:null:host=true/7/true/true",
        "array={\"length\":2.0,\"path\":\"/x\"}",
        "table=/e:{\"value\":42.0}:host=true/nil/true",
        "plain=1/9",
        "number={\"ms\":1.0E17}",
        "callback={\"value\":42.0}:host=true",
        "raw.chunk-tag=true",
        "raw.chunk-id=true",
        "raw.kind-tag=true",
        "raw.endpoint-tag=true",
        "raw.label-absent=true",
        "raw.tags-length=2",
        "raw.note-null=true",
        "scores.mark=true",
        "scores.length=2",
        "scores.elements=1.5,2",
        "scores.marks=table",
        "bad=E8003|array element 1 type mismatch|number|string",
        "null=null",
        "TRANSCRIPT-END",
        "");

    /**
     * The host async completion crossing (ISSUE-0853) on the published
     * LuaJIT artifact under real {@code luajit}: the completion value is
     * projected by the host-carrier bridge pair with the completion
     * descriptor and one memo per await, after the numeric-variant
     * materialization and before the single {@code ASYNC_COMPLETION}
     * cell, so a declared host class completion is the dual-tagged DEAL
     * instance (nested class field, aliased second field, array field,
     * absent optional, present null, per-slot marks, the host object never
     * mutated), a class-element array completion keeps its exact elements,
     * length and alias identity, a table completion keeps its identity
     * where no member requires re-representation and takes one memoized
     * copy — class member bridged, function member kept as the wrapper —
     * otherwise, a numeric completion materializes its variant, and a
     * function completion keeps the documented wrapper.
     */
    private static void testLuaHostAsyncCompletion() throws Exception {
        System.out.println("-- the host async completion crossing: the"
            + " published LuaJIT artifact under real luajit --");
        Path root = Files.createTempDirectory("host-completion-lua");
        try {
            Path out = publishLuaHost(root, "complete_probe",
                COMPLETION_DECLARATION_SOURCE, COMPLETION_APP_SOURCE,
                COMPLETION_HOST_LUA, "host-async-completion");
            if (out == null) {
                return;
            }
            String lua = Files.readString(out.resolve("app.lua"),
                StandardCharsets.UTF_8);
            String numVar = "  __cA = __hostNumVar(__cA)\n";
            String carrierIn = "  __cA = __factories.__hostCarrierIn(";
            int site = 0;
            int sites = 0;
            while ((site = lua.indexOf(numVar, site)) >= 0) {
                sites++;
                int bridgeLineEnd = lua.indexOf("\n", site + numVar.length());
                String bridgeLine = bridgeLineEnd < 0 ? ""
                    : lua.substring(site + numVar.length(), bridgeLineEnd + 1);
                check(bridgeLine.startsWith(carrierIn)
                        && bridgeLine.endsWith(", __cA, {})\n"),
                    "the host completion crossing runs __hostNumVar first and"
                        + " __hostCarrierIn with the completion descriptor and"
                        + " one memo per await immediately after: "
                        + lua.substring(site, Math.min(lua.length(),
                            site + 200)));
                site += numVar.length();
            }
            checkEq(8, sites, "every HOST_OPERATION await runs the"
                + " numeric-variant materialization and then the host-carrier"
                + " bridge");
            for (String descriptor : List.of(
                    "\"@$external/host/complete_probe/ServerConfig\"",
                    "\"[@$external/host/complete_probe/Endpoint]\"",
                    "\"table\"", "\"number\"", "\"(number)->number\"",
                    "\"[number]\"", "\"?string\"")) {
                check(lua.contains(carrierIn + descriptor + ", __cA, {})"),
                    "the completion crossing of " + descriptor
                        + " passes its declared descriptor to the bridge");
            }
            check(lua.contains("{__hostAtom(__tA.value)}"),
                "the crossing's START atom still atomizes the raw completion"
                    + " value, never the projected one");
            check(!lua.contains("__hostToDealArray({}, \""),
                "the retired one-value async completion array projection is"
                    + " gone from every await site");
            writeFileIn(out, "probe.lua", """
                local function fail(m) print("PROBE-FAIL: " .. m) os.exit(1) end
                dofile("app.lua")
                local ok, err = __dealMain()
                if not ok then
                  if type(err) == "table" then
                    fail("init: " .. tostring(err.code) .. "|"
                      .. tostring(err.m) .. "|" .. tostring(err.o) .. "|"
                      .. tostring(err.e) .. "|" .. tostring(err.a))
                  end
                  fail("init: " .. tostring(err))
                end
                local surface = __exportSurfaces["app"]
                local rt = require("deal.runtime")
                local K = __dealCarrierKeys
                local function result(name)
                  local entry = surface[name]
                  if entry == nil then fail("missing " .. name .. " export") end
                  local okc, handle = pcall(entry.f)
                  if not okc then
                    fail(name .. ": " .. tostring(handle.code) .. "|"
                      .. tostring(handle.m) .. "|" .. tostring(handle.e) .. "|"
                      .. tostring(handle.a) .. "|" .. tostring(handle.o))
                  end
                  if type(handle) ~= "table"
                      or handle[K.kind] ~= "async" then
                    fail(name .. " did not return an async operation")
                  end
                  return handle.__result
                end
                print("TRANSCRIPT-BEGIN")
                print("class=" .. tostring(result("runClass")))
                print("array=" .. tostring(result("runAll")))
                print("table=" .. tostring(result("runBundle")))
                print("plain=" .. tostring(result("runPlain")))
                print("number=" .. tostring(result("runNumber")))
                print("callback=" .. tostring(result("runCallback")))
                local function failed(name)
                  local entry = surface[name]
                  if entry == nil then fail("missing " .. name .. " export") end
                  local okc, value = pcall(entry.f)
                  if okc then return "ok:" .. tostring(value) end
                  if type(value) ~= "table" then
                    return "raw:" .. tostring(value)
                  end
                  return tostring(value.code) .. "|" .. tostring(value.m) .. "|"
                    .. tostring(value.e) .. "|" .. tostring(value.a)
                end
                local function call(name)
                  local entry = surface[name]
                  if entry == nil then fail("missing " .. name .. " export") end
                  local okc, value = pcall(entry.f)
                  if not okc then
                    fail(name .. ": " .. tostring(value.code) .. "|"
                      .. tostring(value.m) .. "|" .. tostring(value.e) .. "|"
                      .. tostring(value.a) .. "|" .. tostring(value.o))
                  end
                  return value
                end
                local bag = call("peek")
                local raw = bag.cfg
                print("raw.chunk-tag=" .. tostring(raw[K.c] == true))
                print("raw.chunk-id=" .. tostring(raw[K.id]
                  == "@$external/host/complete_probe/ServerConfig"))
                print("raw.kind-tag=" .. tostring(raw[K.kind] == "class"
                  and raw[K.classname]
                    == "@$external/host/complete_probe/ServerConfig"))
                print("raw.endpoint-tag=" .. tostring(raw.endpoint[K.c] == true
                  and raw.endpoint.path == "/e"))
                print("raw.label-absent=" .. tostring(raw.label == nil))
                print("raw.tags-length=" .. tostring(raw.tags[K.n]))
                print("raw.note-null="
                  .. tostring(raw.note ~= nil and raw.note == rt.__NULL))
                local scores = bag.scores
                print("scores.mark=" .. tostring(scores[K.a] == true))
                print("scores.length=" .. tostring(scores[K.n]))
                print("scores.elements=" .. tostring(scores[1]) .. ","
                  .. tostring(scores[2]))
                print("scores.marks=" .. tostring(type(scores[K.nK])))
                print("bad=" .. failed("runBad"))
                print("null=" .. tostring(result("runNull")))
                print("TRANSCRIPT-END")
                """);
            ProcessOutcome run = runProcess(List.of("luajit", "probe.lua"),
                out, true);
            check(run.exitCode() == 0
                    && run.stdout().equals(COMPLETION_TRANSCRIPT)
                    && run.stderr().isEmpty(),
                "a host async completion of a declared class is the"
                    + " dual-tagged DEAL instance (nested field, aliases,"
                    + " array field, absent optional and present null), a"
                    + " class-element array completion keeps its elements,"
                    + " length and alias identity, a table completion keeps"
                    + " its identity or takes one memoized copy with its"
                    + " class member bridged and its function member kept as"
                    + " the wrapper, a numeric completion materializes its"
                    + " variant, and a function completion keeps the"
                    + " documented wrapper: exit=" + run.exitCode()
                    + " stdout=" + escaped(run.stdout()) + " stderr="
                    + escaped(run.stderr()));
        } finally {
            deleteRecursively(root);
        }
    }

    // =========================================================================
    // 2e. The projected function adapter (ISSUE-0854)
    //
    // The one projected function adapter of the host-binding prelude: the
    // host-driven callback parameter and return crossings and the
    // documented function value at a declared function position, an
    // untyped table member and a host class's function-typed field.
    // =========================================================================

    /**
     * The declared host module of the projected-function-adapter drive: a
     * host class with a nested class field, an optional array and a present
     * nullable field, a host class with a function-typed field, the
     * callback position, the declared function position, and a declared
     * function return.
     */
    private static final String FN_ADAPTER_DECLARATION_SOURCE = """
        export class Endpoint {
          path: string;
        }

        export class ServerConfig {
          port: number;
          endpoint: Endpoint;
          tags?: string[];
          note?: string | null;
        }

        export class Bundle {
          label: string;
          action: (x: number) => number;
        }

        export function consume(f: (s: ServerConfig) => ServerConfig): string;
        export function consumeTable(f: () => table): string;
        export function consumeBundle(f: (b: Bundle) => Bundle): string;
        export function consumeBundleValue(f: () => Bundle): string;
        export function apply(f: (x: number) => number, v: number): number;
        export function probeValue(f: (x: number) => number): string;
        export function bind(): (x: number) => number;
        export function mismatch(): (x: number) => number;
        export function consumePair(f: (a: ServerConfig, b: ServerConfig) => boolean): string;
        """;

    /**
     * The DEAL side of the adapter drive. {@code tag} reads the declared
     * fields of the host's own class instance at the callback's declared
     * class parameter and returns a fresh declared instance; {@code fresh}
     * returns a table whose member carries a DEAL function value;
     * {@code relabel} invokes the host-provided function value the host
     * class's function-typed field carries and returns a declared instance
     * whose own function-typed field carries a DEAL function value;
     * {@code runBundleValue} keeps that instance across the crossing and
     * asserts the body's value still holds the DEAL function value (the
     * identity comparison against the same DEAL function value) and is
     * still invocable. The declared function position, the host-provided
     * function value at a declared function return, and the landed
     * wrong-signature refusal complete the drive.
     */
    private static final String FN_ADAPTER_APP_SOURCE = """
        import * as host from "host/fn_probe";

        function tag(s: host.ServerConfig): host.ServerConfig {
          if (s.port !== 8080) {
            throw { code: "TEST_FAIL", message: "callback port" };
          }
          if (s.endpoint.path !== "/e") {
            throw { code: "TEST_FAIL", message: "callback endpoint" };
          }
          let tags: string[] | null = s.tags;
          if (tags === null) {
            throw { code: "TEST_FAIL", message: "callback tags absent" };
          } else {
            if (tags.length !== 2) {
              throw { code: "TEST_FAIL", message: "callback tags length" };
            }
            if (tags[0] !== "a" || tags[1] !== "b") {
              throw { code: "TEST_FAIL", message: "callback tag values" };
            }
          }
          if (s.note === null) {
            return { port: 9090, endpoint: { path: "/back" }, tags: ["r1", "r2"],
              note: null };
          } else {
            throw { code: "TEST_FAIL", message: "callback note" };
          }
        }

        function scale(x: number): number {
          let y: number = x * 2;
          return y;
        }

        function fresh(): table {
          let t: table = { step: scale };
          return t;
        }

        function relabel(b: host.Bundle): host.Bundle {
          if (b.label !== "given") {
            throw { code: "TEST_FAIL", message: "callback bundle label" };
          }
          let value: number = b.action(21);
          if (value !== 43) {
            throw { code: "TEST_FAIL", message: "callback host function value" };
          }
          return { label: "back", action: scale };
        }

        export function runConsume(): string {
          return host.consume(tag);
        }

        export function runTable(): string {
          return host.consumeTable(fresh);
        }

        export function runBundle(): string {
          return host.consumeBundle(relabel);
        }

        export function runBundleValue(): string {
          let shared: host.Bundle = { label: "shared", action: scale };
          let maker: () => host.Bundle = function(): host.Bundle { return shared; };
          let crossed: string = host.consumeBundleValue(maker);
          let kept: string = "no";
          if (shared.action === scale) {
            kept = "yes";
          }
          let again: number = shared.action(3);
          let invoked: string = "no";
          if (again === 6) {
            invoked = "yes";
          }
          return crossed + " kept=" + kept + " again=" + invoked;
        }

        export function runApply(): string {
          let result: number = host.apply(scale, 41);
          let verdict: string = "no";
          if (result === 182) {
            verdict = "yes";
          }
          return "apply=" + verdict;
        }

        export function runProbeValue(): string {
          return host.probeValue(scale);
        }

        export function runBind(): string {
          let bound: (x: number) => number = host.bind();
          let result: number = bound(4);
          let verdict: string = "no";
          if (result === 8) {
            verdict = "yes";
          }
          return "bind=" + verdict;
        }

        export function runMismatch(): string {
          try {
            let wrong: (x: number) => number = host.mismatch();
            let ignored: number = wrong(1);
            return "mismatch=admitted";
          } catch (e) {
            return "mismatch=" + e.code + "|" + e.message;
          }
        }

        function same(a: host.ServerConfig, b: host.ServerConfig): boolean {
          if (a.port !== 8080) {
            throw { code: "TEST_FAIL", message: "pair port" };
          }
          if (b.endpoint.path !== "/e") {
            throw { code: "TEST_FAIL", message: "pair endpoint" };
          }
          return a === b;
        }

        export function runPair(): string {
          return host.consumePair(same);
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The deployed raw host module. It builds its class instances through
     * the shipped runtime's own constructor (so every argument the DEAL
     * callback receives is a host-origin runtime instance), hands a
     * host-provided function value at a declared function position and at
     * a host class's function-typed field, invokes every callback it is
     * handed, and reports the observed shape, the declared fields, the
     * wrapper's invocation results, and the closed tuples. The reported
     * origin is normalized to the project-relative source id so the
     * transcript does not depend on the temporary root.
     */
    private static final String FN_ADAPTER_LUA = """
        local rt = require("deal.runtime")
        local K = __dealCarrierKeys
        local SPEC = "@$external/host/fn_probe/"

        local function origin(o)
          return (tostring(o):gsub("^.*/src/", "src/"))
        end

        local function tuple(e)
          if type(e) ~= "table" then return "raw:" .. type(e) end
          return tostring(e.code) .. "|" .. tostring(e.m) .. "|" .. tostring(e.e)
            .. "|" .. tostring(e.a) .. "|" .. origin(e.o)
        end

        local function endpoint(path)
          return {[K.kind]="class", [K.classname]=SPEC .. "Endpoint", path=path}
        end

        local function config(port, path, first, second, note)
          return {[K.kind]="class", [K.classname]=SPEC .. "ServerConfig",
            port=port, endpoint=endpoint(path), tags={first,second}, note=note}
        end

        local function wrapper(f)
          return {[K.kind] = "function", sig = "(number)->number", f = f}
        end

        local function bundle(label, action)
          return {[K.kind]="class", [K.classname]=SPEC .. "Bundle", label=label, action=action}
        end

        local function accepted(fn)
          if type(fn) ~= "table" then return "raw:" .. type(fn) end
          local ok, res = pcall(fn.f, 41)
          local okBad, bad = pcall(fn.f, "bad")
          return "kind=" .. tostring(fn[K.kind]) .. " sig=" .. tostring(fn.sig)
            .. " f=" .. type(fn.f) .. " res=" .. tostring(ok and res or "err")
            .. " bad=" .. tostring(okBad and "admitted" or tuple(bad))
        end

        return {
          Endpoint = {[K.kind] = "class", [K.classname] = SPEC .. "Endpoint"},
          Endpoint_defaults = {path = "/"},
          ServerConfig = {[K.kind] = "class",
            [K.classname] = SPEC .. "ServerConfig"},
          ServerConfig_defaults = {port = 8080, endpoint = rt.__MISSING,
            tags = rt.__MISSING, note = rt.__MISSING},
          Bundle = {[K.kind] = "class", [K.classname] = SPEC .. "Bundle"},
          Bundle_defaults = {label = "", action = rt.__MISSING},
          consume = function(f)
            local given = config(8080, "/e", "a", "b", rt.__NULL)
            local ok, res = pcall(f, given)
            if not ok then return "consume=err:" .. tuple(res) end
            local tags = res.tags
            local tagsOk = type(tags) == "table" and tags[1] == "r1"
              and tags[2] == "r2"
            -- The host's own instance is never mutated by the inbound
            -- projection: it keeps its runtime class shape and no chunk tag.
            local untouched = given[K.c] == nil and given[K.kind] == "class"
              and given.port == 8080
            local out = "consume=" .. tostring(res.port) .. ","
              .. tostring(res.endpoint.path) .. ",tags=" .. tostring(tagsOk)
              .. ",tags-n=" .. tostring(#tags)
              .. ",note=" .. tostring(res.note == rt.__NULL)
              .. ",top=" .. tostring(res[K.kind]) .. ","
              .. tostring(res[K.classname])
              .. ",given=" .. tostring(untouched)
            local okWrong, wrong = pcall(f, 42)
            return out .. " wrong=" .. (okWrong and "admitted" or tuple(wrong))
          end,
          consumeTable = function(f)
            local ok, res = pcall(f)
            if not ok then return "table=err:" .. tuple(res) end
            return "table=" .. accepted(res.step)
          end,
          consumeBundle = function(f)
            local ok, res = pcall(f,
              bundle("given", wrapper(function(x) return x + 22 end)))
            if not ok then return "bundle=err:" .. tuple(res) end
            return "bundle=" .. tostring(res.label) .. "," .. accepted(res.action)
              .. ",top=" .. tostring(res[K.kind]) .. ","
              .. tostring(res[K.classname])
          end,
          consumeBundleValue = function(f)
            local ok, res = pcall(f)
            if not ok then return "value=err:" .. tuple(res) end
            return "value=" .. tostring(res.label) .. "," .. accepted(res.action)
              .. ",top=" .. tostring(res[K.kind]) .. ","
              .. tostring(res[K.classname])
          end,
          consumePair = function(f)
            local shared = config(8080, "/e", "a", "b", rt.__NULL)
            local ok, res = pcall(f, shared, shared)
            if not ok then return "pair=err:" .. tuple(res) end
            return "pair=" .. tostring(res)
          end,
          apply = function(f, v)
            return f(v) + 100
          end,
          probeValue = function(f)
            local ok, res = pcall(f, 41)
            local okBad, bad = pcall(f, "bad")
            return "f=" .. type(f) .. " res=" .. tostring(ok and res or "err")
              .. " bad=" .. tostring(okBad and "admitted" or tuple(bad))
          end,
          bind = function()
            return wrapper(function(x) return x * 2 end)
          end,
          mismatch = function()
            return {[K.kind] = "function", sig = "(string)->number",
              f = function(x) return 1 end}
          end,
        }
        """;

    /**
     * The exact transcript of the projected-function-adapter drive: the
     * declared class parameter and return through the one adapter, the
     * untyped table member's documented wrapper, the host class's
     * function-typed field in both directions, the declared function
     * position's cells and return projection (the raw host's arithmetic over
     * the projected int), the host-provided function value invoked from
     * DEAL, the landed wrong-signature refusal, and the closed
     * parameter-entry tuples — both at the host call site's own origin and
     * at an untyped member position.
     */
    private static final String FN_ADAPTER_TRANSCRIPT = """
        TRANSCRIPT-BEGIN
        runConsume=consume=9090,/back,tags=true,tags-n=2,note=true,top=class,@$external/host/fn_probe/ServerConfig,given=true wrong=E8001|expected class instance|class|number|src/app.deal:51:10
        runTable=table=kind=function sig=(number)->number f=function res=82 bad=E8001|expected number|number|string|nil
        runBundle=bundle=back,kind=function sig=(number)->number f=function res=82 bad=E8001|expected number|number|string|nil,top=class,@$external/host/fn_probe/Bundle
        runBundleValue=value=shared,kind=function sig=(number)->number f=function res=82 bad=E8001|expected number|number|string|nil,top=class,@$external/host/fn_probe/Bundle kept=yes again=yes
        runApply=apply=yes
        runProbeValue=f=function res=82 bad=E8001|expected number|number|string|src/app.deal:88:10
        runBind=bind=yes
        runMismatch=mismatch=E8010|return value 1 type mismatch: function signature mismatch: expected (number)->number, got (string)->number
        runPair=pair=true
        TRANSCRIPT-END
        """;

    /**
     * The projected function adapter on the published LuaJIT artifact
     * (ISSUE-0854): exactly one {@code __hostFnArg} in the host-binding
     * prelude, reached from the declared function position's projection and
     * from the host-carrier bridge's outbound function branch (no second
     * adapter), whose wrapper runs the bridge's inbound projection at the
     * cell's canonical descriptor with one memo per invocation and the
     * declared parameter-entry cell at every recorded position, and whose
     * return takes the bridge's outbound projection at the declared return
     * descriptor of its own {@code __hostFnShapes} entry. Then the drive
     * executes the whole flow under real {@code luajit}: a host class
     * instance at a declared callback parameter reaches the DEAL body as the
     * dual-tagged instance with its exact top-level fields, the body's
     * declared class return reaches the host with the exact top-level
     * declared fields, a DEAL function value crosses at a declared function
     * position, at an untyped table member and at a host class's
     * function-typed field as the one documented wrapper with the same
     * cells and return projection, a host-provided function value at a
     * declared function position and at a class field stays invocable from
     * DEAL, and the closed tuples keep their landed code/text/actual/origin.
     */
    private static void testLuaHostFunctionAdapter() throws Exception {
        System.out.println("-- the projected function adapter: the published"
            + " LuaJIT artifact under real luajit --");
        Path root = Files.createTempDirectory("host-fn-adapter-lua");
        try {
            Path out = publishLuaHost(root, "fn_probe",
                FN_ADAPTER_DECLARATION_SOURCE, FN_ADAPTER_APP_SOURCE,
                FN_ADAPTER_LUA, "host-fn-adapter");
            if (out == null) {
                return;
            }
            String lua = Files.readString(out.resolve("app.lua"),
                StandardCharsets.UTF_8);
            checkEq(1, countOccurrences(lua, "local function __hostFnArg("),
                "the chunk emits exactly one projected function adapter");
            checkEq(1, countOccurrences(lua,
                    "{[__dealCarrierKeys.kind] = \"function\", sig = inner,"),
                "the adapter builds the documented wrapper at one site");
            checkEq(1, countOccurrences(lua,
                    "return __hostFnArg(desc, inner, cells, origin, v)"),
                "the declared function position delegates to the one adapter");
            checkEq(1, countOccurrences(lua,
                    "local out = __hostFnArg(sig, sig, shape.cells, nil, v)"),
                "the bridge's outbound function branch delegates to the same"
                    + " adapter");
            check(!lua.contains("__hostToDealArray"),
                "the retired one-value array projection is gone from the"
                    + " prelude: the adapter is the one conversion site and"
                    + " every array position takes the bridge");
            check(lua.contains("local shape = __factories.__hostFnShapes[sig]")
                    && lua.contains("local ret = nil")
                    && lua.contains("__factories.__hostCarrierOut(ret,")
                    && lua.contains("value = __factories.__hostCarrierIn(cell[3],"
                        + " args[i], memo)"),
                "the adapter reads its declared return descriptor from the"
                    + " emitted shapes and bridges each parameter cell's value"
                    + " with one memo");
            check(lua.contains("[\"(@$external/host/fn_probe/ServerConfig)->"
                    + "@$external/host/fn_probe/ServerConfig\"] = {ret = "
                    + "\"@$external/host/fn_probe/ServerConfig\""),
                "the callback's shape entry carries the declared class return"
                    + " descriptor");
            check(lua.contains("[\"(number)->number\"] = {ret = \"number\", cells = "
                    + "{{\"number\", \"number\", \"number\"}}}"),
                "the function-typed field and the function values take the"
                    + " declared parameter-entry cells");
            writeFileIn(out, "probe.lua", """
                local function fail(m) print("PROBE-FAIL: " .. m) os.exit(1) end
                dofile("app.lua")
                local ok, err = __dealMain()
                if not ok then fail("init: " .. tostring(err)) end
                local surface = __exportSurfaces["app"]
                print("TRANSCRIPT-BEGIN")
                local names = { "runConsume", "runTable", "runBundle",
                  "runBundleValue", "runApply", "runProbeValue", "runBind",
                  "runMismatch", "runPair" }
                for _, name in ipairs(names) do
                  local entry = surface[name]
                  if entry == nil then fail("missing " .. name) end
                  local ok2, res = pcall(entry.f)
                  if not ok2 then
                    fail(name .. ": " .. tostring(res.code) .. "|"
                      .. tostring(res.m) .. "|" .. tostring(res.e) .. "|"
                      .. tostring(res.a) .. "|" .. tostring(res.o))
                  end
                  print(name .. "=" .. res)
                end
                print("TRANSCRIPT-END")
                """);
            ProcessOutcome run = runProcess(List.of("luajit", "probe.lua"),
                out, true);
            check(run.exitCode() == 0 && run.stderr().isEmpty()
                    && run.stdout().equals(FN_ADAPTER_TRANSCRIPT),
                "the one projected function adapter preserves the declared"
                    + " class and function values in both directions at the"
                    + " declared function position, the untyped table member,"
                    + " and the host class's function-typed field: exit="
                    + run.exitCode() + " stdout=" + escaped(run.stdout())
                    + " stderr=" + escaped(run.stderr()));
        } finally {
            deleteRecursively(root);
        }
    }

    // =========================================================================
    // 2e2. The untyped-position carrier crossings (ISSUE-0856)
    //
    // The cross-cutting untyped positions of the host-carrier projection:
    // `table`-typed host returns, parameters and host-class fields carrying
    // class and host-provided function members; DEAL-declared class,
    // function and Error values inside table and array members in both
    // directions; a DEAL function value at a host class's function-typed
    // field and at a table member; host-provided function values invoked
    // from DEAL; and the D4 shared/copy rule, aliases, null/absent states
    // and numeric variants.
    // =========================================================================

    /**
     * The declared host module of the untyped-position drive: a host class
     * with two numeric fields, a host class with a function-typed field and
     * a {@code table}-typed field, and the declared {@code table} return,
     * parameter and echo positions. No declared position carries the
     * imported {@code (int) => int} function type, so the DEAL function
     * value's wrapper can only be sourced from the emitted
     * {@code __hostFnShapes} metadata.
     */
    private static final String UNTYPED_DECLARATION_SOURCE = """
        export class Cfg {
          count: number;
          ratio: number;
        }

        export class Bundle {
          label: string;
          action: (s: string) => string;
          extras: table;
        }

        export function makeTable(): table;
        export function consumeTable(t: table): string;
        export function echoTable(t: table): table;
        export function makeBundle(): Bundle;
        export function consumeBundle(b: Bundle): string;
        export function makeBadBundle(): Bundle;
        export function makePlainTable(): table;
        export function observePlain(t: table): string;
        export function makeClassyTable(): table;
        export function observeClassy(t: table): string;
        export function makeDenseTable(): table;
        export function observeDense(items: table): string;
        export function makeNullTable(): table;
        export function observeNull(t: table): string;
        export function observeMade(): string;
        export function makeBadValues(): table;
        """;

    /** The second module of the drive: a DEAL function value whose signature
     * is not a declared position anywhere in the host declaration. */
    private static final String UNTYPED_UTIL_SOURCE = """
        export function bump(x: number): number {
          return x + 1;
        }
        """;

    /**
     * The DEAL side of the untyped-position drive. {@code runConsumeTable}
     * fills one {@code table} parameter with a host class member, a
     * host-provided function member, a DEAL-declared class member twice plus
     * once in a nested array, a DEAL function value and a builtin
     * {@code Error} value twice; {@code runEcho} receives the host's echo of
     * that container and asserts the round-trip values at their declared
     * types; the remaining functions pin the D4 shared/copy rule, the
     * host-provided function values invoked from DEAL, the declared
     * signature refusals, and null/absent/numeric preservation.
     */
    private static final String UNTYPED_APP_SOURCE = """
        import * as host from "host/untyped_probe";
        import * as util from "./util";
        import * as json from "std/json";

        class Point {
          x: number;
          y: number;
        }

        function scale(s: string): string {
          return s + "!";
        }

        export function runMakeTable(): string {
          let t: table = host.makeTable();
          let cfg: host.Cfg = t.cfg;
          let fn: (n: number) => number = t.fn;
          let r: number = fn(41);
          let ok: string = "no";
          if (cfg.count === 7 && cfg.ratio === 2.5 && r === 42) {
            ok = "yes";
          }
          return "makeTable=" + ok + "," + host.observeMade();
        }

        export function runConsumeTable(): string {
          let made: table = host.makeTable();
          let cfg: host.Cfg = made.cfg;
          let hfn: (n: number) => number = made.fn;
          let r: number = hfn(41);
          let p: Point = { x: 3, y: 4 };
          let e: Error = { code: "E_X", message: "boom" };
          let t: table = { cfg: cfg, hfn: hfn, fn: util.bump, pt: p,
            other: p, xs: [p], err: e, errs: [e] };
          let ok: string = "no";
          if (r === 42 && cfg.count === 7) {
            ok = "yes";
          }
          return host.consumeTable(t) + ",invoked=" + ok;
        }

        export function runEcho(): string {
          let p: Point = { x: 3, y: 4 };
          let e: Error = { code: "E_X", message: "boom" };
          let t: table = { pt: p, other: p, xs: [p], fn: util.bump,
            err: e, errs: [e] };
          let echoed: table = host.echoTable(t);
          let a: Point = echoed.pt;
          let b: Point = echoed.other;
          let xs: Point[] = echoed.xs;
          let c: Point = xs[0];
          let fn: (n: number) => number = echoed.fn;
          let r: number = fn(41);
          let err: Error = echoed.err;
          let errs: Error[] = echoed.errs;
          let errInArray: Error = errs[0];
          let alias: string = "no";
          if (a === b && a === c && a.x === 3 && a.y === 4) {
            alias = "yes";
          }
          let invoke: string = "no";
          if (r === 42) {
            invoke = "yes";
          }
          let errok: string = "no";
          if (err === errInArray
              && err.code === "E_X" && err.message === "boom"
              && errInArray.code === "E_X"
              && errInArray.message === "boom") {
            errok = "yes";
          }
          return "echo=alias:" + alias + ",fn:" + invoke + ",err:" + errok;
        }

        export function runBundle(): string {
          let made: table = host.makeTable();
          let cfg: host.Cfg = made.cfg;
          let hfn: (n: number) => number = made.fn;
          let r: number = hfn(41);
          let p: Point = { x: 5, y: 6 };
          let b: host.Bundle = { label: "box", action: scale,
            extras: { cfg: cfg, hfn: hfn, pt: p } };
          let ok: string = "no";
          if (r === 42) {
            ok = "yes";
          }
          return host.consumeBundle(b) + ",invoked=" + ok;
        }

        export function runFieldCall(): string {
          let b: host.Bundle = host.makeBundle();
          let action: (s: string) => string = b.action;
          let v: string = action("ab");
          let ok: string = "no";
          if (v === "ab?") {
            ok = "yes";
          }
          return "field=" + ok;
        }

        export function runWrongField(): string {
          let b: host.Bundle = host.makeBadBundle();
          try {
            let action: (s: string) => string = b.action;
            let v: string = action("x");
            return "wrongField=admitted";
          } catch (e) {
            return "wrongField=" + e.code + "|" + e.message;
          }
        }

        export function runWrongMember(): string {
          let t: table = host.makeTable();
          try {
            let fn: (s: string) => number = t.fn;
            let r: number = fn("x");
            return "wrongMember=admitted";
          } catch (e) {
            return "wrongMember=" + e.code + "|" + e.message;
          }
        }

        export function runShare(): string {
          let t: table = host.makePlainTable();
          t.extra = 9;
          return host.observePlain(t);
        }

        export function runCopy(): string {
          let t: table = host.makeClassyTable();
          let nested: table = t.nested;
          let same: table = t.again;
          let memo: string = "no";
          if (nested === same) {
            memo = "yes";
          }
          nested.marker = 3;
          return host.observeClassy(t) + ",memo=" + memo;
        }

        export function runDense(): string {
          let t: table = host.makeDenseTable();
          let items: table = t.items;
          return host.observeDense(items);
        }

        export function runNull(): string {
          let t: table = host.makeNullTable();
          let note: string | null = t.note;
          let ratio: number = t.ratio;
          let missing: string | null = t.missing;
          let ok: string = "no";
          if (note === null && missing === null && ratio === 2.5) {
            ok = "yes";
          }
          return "null=" + ok + "," + host.observeNull(t);
        }

        export function runNumeric(): string {
          let decoded: table = json.parse("{\\"a\\":1,\\"b\\":0.1,\\"n\\":null}");
          let t: table = { doc: decoded };
          let echoed: table = host.echoTable(t);
          let doc: table = echoed.doc;
          return "numeric=" + json.stringify(doc);
        }

        export function runWrongRawFunction(): string {
          let t: table = host.makeBadValues();
          try {
            let fn: (n: number) => number = t.raw;
            let r: number = fn(41);
            return "rawFunction=admitted";
          } catch (e) {
            return "rawFunction=" + e.code + "|" + e.message;
          }
        }

        export function runWrongClassValue(): string {
          let t: table = host.makeTable();
          try {
            let c: host.Cfg = t.fn;
            return "wrongClass=admitted";
          } catch (e) {
            return "wrongClass=" + e.code + "|" + e.message;
          }
        }

        export function runWrongElement(): string {
          let p: Point = { x: 1, y: 2 };
          let t: table = { xs: [p] };
          let echoed: table = host.echoTable(t);
          try {
            let xs: host.Cfg[] = echoed.xs;
            return "wrongElement=admitted";
          } catch (e) {
            return "wrongElement=" + e.code + "|" + e.message;
          }
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The deployed raw host module. It observes every crossing through the
     * shipped runtime's {@code typed_kind} and private key set, keeps its
     * own objects so it can report that the inbound projection never
     * mutated them, invokes the DEAL and host-provided function values with
     * the exact declared arguments, and reports the closed tuples with
     * their origin normalized to the project-relative source id, so the
     * transcript does not depend on the temporary root.
     */
    private static final String UNTYPED_HOST_LUA = """
        local rt = require("deal.runtime")
        local K = rt.__keys
        local SPEC = "@$external/host/untyped_probe/"

        local function origin(o)
          return (tostring(o):gsub("^.*/src/", "src/"))
        end

        local function tuple(e)
          if type(e) ~= "table" then return "raw:" .. type(e) end
          return tostring(e.code) .. "|" .. tostring(e.m) .. "|"
            .. tostring(e.e) .. "|" .. tostring(e.a) .. "|" .. origin(e.o)
        end

        local function wrapper(sig, f)
          return {[K.kind] = "function", sig = sig, f = f}
        end

        local function cfg(count, ratio)
          return {[K.kind]="class", [K.classname]=SPEC .. "Cfg", count=count, ratio=ratio}
        end

        local keptPlain
        local keptClassy
        local keptCfg

        local function objectShape(v)
          if type(v) ~= "table" then return "none" end
          return "kind=" .. tostring(v[K.kind]) .. " typed="
            .. tostring(rt.typed_kind(v)) .. " ctag="
            .. tostring(rawget(v, K.c))
        end

        local function errShape(v)
          if type(v) ~= "table" then return "none" end
          return "kind=" .. tostring(v[K.kind]) .. " typed="
            .. tostring(rt.typed_kind(v)) .. " code=" .. tostring(v.code)
            .. " message=" .. tostring(v.message) .. " dmark="
            .. tostring(rawget(v, K.d))
        end

        local function accepted(fn, arg, badArg)
          if type(fn) ~= "table" then return "raw:" .. type(fn) end
          local ok, res = pcall(fn.f, arg)
          local okBad, bad = pcall(fn.f, badArg)
          return "kind=" .. tostring(fn[K.kind]) .. " typed="
            .. tostring(rt.typed_kind(fn)) .. " sig=" .. tostring(fn.sig)
            .. " f=" .. type(fn.f) .. " fnmark="
            .. tostring(rawget(fn, K.fn))
            .. " res=" .. tostring(ok and res or "err")
            .. " bad=" .. tostring(okBad and "admitted" or tuple(bad))
        end

        local function hostAccepted(fn, arg)
          if type(fn) ~= "table" then return "raw:" .. type(fn) end
          local ok, res = pcall(fn.f, arg)
          return "kind=" .. tostring(fn[K.kind]) .. " typed="
            .. tostring(rt.typed_kind(fn)) .. " sig=" .. tostring(fn.sig)
            .. " f=" .. type(fn.f) .. " fnmark="
            .. tostring(rawget(fn, K.fn))
            .. " res=" .. tostring(ok and res or "err")
        end

        return {
          Cfg = {[K.kind] = "class", [K.classname] = SPEC .. "Cfg"},
          Cfg_defaults = {count = 0, ratio = 0.0},
          Bundle = {[K.kind] = "class", [K.classname] = SPEC .. "Bundle"},
          Bundle_defaults = {label = "", action = rt.__MISSING,
            extras = rt.__MISSING},

          makeTable = function()
            keptCfg = cfg(7, 2.5)
            return {cfg = keptCfg,
              fn = wrapper("(number)->number", function(x) return x + 1 end)}
          end,

          observeMade = function()
            return "made=" .. objectShape(keptCfg) .. ",count="
              .. tostring(keptCfg.count) .. ",ratio="
              .. tostring(keptCfg.ratio)
          end,

          consumeTable = function(t)
            local ptShape = "none"
            if type(t.pt) == "table" then
              ptShape = "x=" .. tostring(t.pt.x) .. " y="
                .. tostring(t.pt.y) .. " " .. objectShape(t.pt)
            end
            local ptAlias = rawequal(t.pt, t.other)
            local arrayAlias = false
            if type(t.xs) == "table" then
              arrayAlias = rawequal(t.xs[1], t.pt)
            end
            local errAlias = rawequal(t.err, t.errs[1])
            return "consume=cfg:" .. objectShape(t.cfg)
              .. ",pt:" .. ptShape
              .. ",alias=" .. tostring(ptAlias) .. ","
              .. tostring(arrayAlias)
              .. ",fn:" .. accepted(t.fn, 41, "bad")
              .. ",hfn:" .. hostAccepted(t.hfn, 41)
              .. ",err:" .. errShape(t.err) .. ",errs:"
              .. errShape(t.errs[1])
              .. ",erralias=" .. tostring(errAlias)
          end,

          echoTable = function(t)
            return t
          end,

          makeBundle = function()
            local extras = {fn = wrapper("(number)->number",
              function(x) return x * 2 end)}
            return {[K.kind]="class", [K.classname]=SPEC .. "Bundle", label="made",
              action=wrapper("(string)->string", function(s) return s .. "?" end), extras=extras}
          end,

          consumeBundle = function(b)
            local ex = b.extras
            local cfgShape = "none"
            local ptShape = "none"
            local hfnText = "none"
            if type(ex) == "table" then
              if type(ex.cfg) == "table" then
                cfgShape = "count=" .. tostring(ex.cfg.count) .. " ratio="
                  .. tostring(ex.cfg.ratio) .. " " .. objectShape(ex.cfg)
              end
              if type(ex.pt) == "table" then
                ptShape = "x=" .. tostring(ex.pt.x) .. " y="
                  .. tostring(ex.pt.y) .. " " .. objectShape(ex.pt)
              end
              hfnText = hostAccepted(ex.hfn, 41)
            end
            return "bundle=" .. tostring(b.label)
              .. ",action:" .. accepted(b.action, "ab", 41)
              .. ",extras.cfg:" .. cfgShape
              .. ",extras.pt:" .. ptShape
              .. ",extras.hfn:" .. hfnText
          end,

          makeBadBundle = function()
            return {[K.kind]="class", [K.classname]=SPEC .. "Bundle", label="bad",
              action=wrapper("(number)->number", function(x) return x + 1 end), extras={}}
          end,

          makePlainTable = function()
            local t = {n = 1, fn = wrapper("(number)->number",
              function(x) return x + 1 end)}
            keptPlain = t
            return t
          end,

          observePlain = function(t)
            return "plain=extra:" .. tostring(t.extra) .. ",shared:"
              .. tostring(rawequal(t, keptPlain)) .. ",hostextra:"
              .. tostring(keptPlain.extra)
          end,

          makeClassyTable = function()
            local nested = {cfg = cfg(2, 1.5)}
            local t = {nested = nested, again = nested}
            keptClassy = t
            return t
          end,

          observeClassy = function(t)
            return "classy=marker:" .. tostring(t.nested.marker)
              .. ",hostmarker:" .. tostring(keptClassy.nested.marker)
              .. ",copy:" .. tostring(not rawequal(t, keptClassy))
          end,

          makeDenseTable = function()
            return {items = {1, 2, 3}}
          end,

          observeDense = function(items)
            return "dense=n:" .. tostring(#items) .. ",array:"
              .. tostring(items[K.a] ~= nil) .. ",v1:"
              .. tostring(items[1])
          end,

          makeBadValues = function()
            return {raw = function(x) return x end}
          end,

          makeNullTable = function()
            return {note = rt.__NULL, ratio = 2.5}
          end,

          observeNull = function(t)
            return "null=note:" .. tostring(t.note == rt.__NULL)
              .. ",missing:" .. tostring(t.missing == nil)
              .. ",ratio:" .. type(t.ratio) .. ":"
              .. tostring(t.ratio)
          end,
        }
        """;

    /**
     * The exact transcript of the untyped-position drive: the host-observed
     * class/function/Error shapes through {@code typed_kind}, the alias
     * identity of the class and Error members on both sides, the declared
     * parameter-entry cells of the DEAL function values, the wrapper-sourced
     * imported signature, the D4 shared/copy rule (identity, memoized copy,
     * unmutated host object, nested descent, dense table not reclassified),
     * the declared signature refusals, and null/absent/numeric
     * preservation. The host origin is normalized to the project-relative
     * source id.
     */
    private static final String UNTYPED_TRANSCRIPT = """
        TRANSCRIPT-BEGIN
        runMakeTable=makeTable=yes,made=kind=class typed=@$external/host/untyped_probe/Cfg ctag=nil,count=7,ratio=2.5
        runConsumeTable=consume=cfg:kind=class typed=@$external/host/untyped_probe/Cfg ctag=true,pt:x=3 y=4 kind=class typed=@src/app.deal/Point ctag=nil,alias=true,true,fn:kind=function typed=function sig=(number)->number f=function fnmark=nil res=42 bad=E8001|expected number|number|string|nil,hfn:kind=function typed=function sig=(number)->number f=function fnmark=nil res=42,err:kind=class typed=Error code=E_X message=boom dmark=nil,errs:kind=class typed=Error code=E_X message=boom dmark=nil,erralias=true,invoked=yes
        runEcho=echo=alias:yes,fn:yes,err:yes
        runBundle=bundle=box,action:kind=function typed=function sig=(string)->string f=function fnmark=nil res=ab! bad=E8001|expected string|string|number|nil,extras.cfg:count=7 ratio=2.5 kind=class typed=@$external/host/untyped_probe/Cfg ctag=true,extras.pt:x=5 y=6 kind=class typed=@src/app.deal/Point ctag=nil,extras.hfn:kind=function typed=function sig=(number)->number f=function fnmark=nil res=42,invoked=yes
        runFieldCall=field=yes
        runWrongField=wrongField=E8010|function signature mismatch: expected (string)->string, got (number)->number
        runWrongMember=wrongMember=E8010|function signature mismatch: expected (string)->number, got (number)->number
        runShare=plain=extra:9,shared:true,hostextra:9
        runCopy=classy=marker:3,hostmarker:nil,copy:true,memo=yes
        runDense=dense=n:3,array:false,v1:1
        runNull=null=yes,null=note:true,missing:true,ratio:number:2.5
        runNumeric=numeric={"a":1.0,"b":0.1,"n":null}
        runWrongRawFunction=rawFunction=E8001|expected function
        runWrongClassValue=wrongClass=E8001|expected class instance
        runWrongElement=wrongElement=E8003|array element 1 type mismatch
        TRANSCRIPT-END
        """;

    /**
     * The untyped-position host-carrier crossings on the published LuaJIT
     * artifact (ISSUE-0856): a {@code table} host return, parameter and
     * host-class field carry class and host-provided function members; a
     * DEAL-declared class value appears twice in a table member and once in
     * a nested array member and the host observes one object for all three
     * positions; the host echoes the container and DEAL reads both aliases
     * and the array element as one declared class value; a DEAL function
     * value whose signature is not a declared position crosses only through
     * the emitted {@code __hostFnShapes} entry, the host invokes it and its
     * parameter-entry cell rejects an argument outside the declared type;
     * a builtin {@code Error} value in a table member and an array member
     * crosses as the runtime Error instance and round-trips to the
     * {@code __d} carrier; a DEAL function value at the host class's
     * function-typed field and at a table member reaches the host as the
     * documented wrapper; host-provided function values stay invocable from
     * DEAL and a mismatched declared signature fails closed; and the D4
     * shared/copy rule, aliases, null/absent states and numeric variants
     * hold observably — all under real {@code luajit} with empty stderr.
     */
    private static void testLuaHostUntypedCarrierCrossings() throws Exception {
        System.out.println("-- the untyped-position host-carrier crossings:"
            + " the published LuaJIT artifact under real luajit --");
        Path root = Files.createTempDirectory("host-untyped-lua");
        try {
            Path out = publishLuaHost(root, "untyped_probe",
                UNTYPED_DECLARATION_SOURCE, UNTYPED_APP_SOURCE,
                UNTYPED_HOST_LUA, "host-untyped-carrier",
                Map.of("src/util.deal", UNTYPED_UTIL_SOURCE));
            if (out == null) {
                return;
            }
            String lua = Files.readString(out.resolve("app.lua"),
                StandardCharsets.UTF_8);
            check(lua.contains("[\"(number)->number\"] = {ret = \"number\","
                    + " cells = {{\"number\", \"number\", \"number\"}}}"),
                "the imported DEAL function value's otherwise-undeclared"
                    + " signature is emitted into __hostFnShapes");
            check(lua.contains("__factories.__hostCarrierOut = function")
                    && lua.contains("__factories.__hostCarrierIn = function"),
                "the published prelude carries the one host-carrier bridge"
                    + " pair");
            checkEq(2, countOccurrences(lua,
                    "if v[__dealCarrierKeys.a] ~= nil then"),
                "both bridge directions walk an array carrier so a host"
                    + " representation inside a member is re-represented");
            writeFileIn(out, "probe.lua", """
                local function fail(m)
                  print("PROBE-FAIL: " .. m)
                  os.exit(1)
                end
                dofile("app.lua")
                local ok, err = __dealMain()
                if not ok then fail("init: " .. tostring(err)) end
                local surface = __exportSurfaces["app"]
                print("TRANSCRIPT-BEGIN")
                local names = { "runMakeTable", "runConsumeTable", "runEcho",
                  "runBundle", "runFieldCall", "runWrongField",
                  "runWrongMember", "runShare", "runCopy", "runDense",
                  "runNull", "runNumeric", "runWrongRawFunction",
                  "runWrongClassValue", "runWrongElement" }
                for _, name in ipairs(names) do
                  local entry = surface[name]
                  if entry == nil then fail("missing " .. name) end
                  local ok2, res = pcall(entry.f)
                  if not ok2 then
                    fail(name .. ": " .. tostring(res.code) .. "|"
                      .. tostring(res.m) .. "|" .. tostring(res.e) .. "|"
                      .. tostring(res.a) .. "|" .. tostring(res.o))
                  end
                  print(name .. "=" .. res)
                end
                print("TRANSCRIPT-END")
                """);
            ProcessOutcome run = runProcess(List.of("luajit", "probe.lua"),
                out, true);
            check(run.exitCode() == 0 && run.stderr().isEmpty()
                    && run.stdout().equals(UNTYPED_TRANSCRIPT),
                "the untyped-position crossings preserve the declared class,"
                    + " function and Error values in both directions, keep"
                    + " the D4 shared/copy rule, aliases, null/absent states"
                    + " and numeric variants, and keep the landed tuples:"
                    + " exit=" + run.exitCode() + " stdout="
                    + escaped(run.stdout()) + " stderr="
                    + escaped(run.stderr()));
        } finally {
            deleteRecursively(root);
        }
    }

    // =========================================================================
    // 2f. The LuaJIT load under real luajit
    // =========================================================================

    private static void testLuaExecution(Baseline fixture, Baseline missing,
            Baseline cfgFixture, Baseline tableParam) throws Exception {
        System.out.println("-- the LuaJIT load under real luajit: one load per "
            + "module, one shared surface value, the pinned E8011 --");
        Path workspace = Files.createTempDirectory("host-load-lua");
        try {
            SemanticLowerer.ProjectLoweringResult result = fixture.result();
            if (result.project() == null) {
                fail("the two-alias fixture lowers: " + result.diagnostics());
                return;
            }
            ExecutableLoweredProject project = result.project();
            ModuleId probeModule = hostImportModule(project, PROBE_SPECIFIER);
            ModuleId cfgModule = hostImportModule(project, CFG_SPECIFIER);
            Path artifact = workspace.resolve("project.lua");
            Files.writeString(artifact, fixture.lua(), StandardCharsets.UTF_8);
            deployRuntime(workspace);
            writeFileIn(workspace, "host/abi_probe.lua", PROBE_LUA);
            writeFileIn(workspace, "host/cfg.lua", CFG_LUA);

            Path probe = workspace.resolve("probe.lua");
            Files.writeString(probe, luaLoadProbe(artifact, probeModule, cfgModule),
                StandardCharsets.UTF_8);
            ProcessOutcome run = runProcess(List.of("luajit",
                probe.toAbsolutePath().toString()), workspace, true);
            check(run.exitCode() == 0 && run.stdout().contains("PROBE-OK"),
                "the loaded surface is the import's namespace value, one load "
                    + "happens per module, and the second drive keeps the surface "
                    + "identity: exit=" + run.exitCode() + " stdout="
                    + escaped(run.stdout()) + " stderr=" + escaped(run.stderr()));
        } finally {
            deleteRecursively(workspace);
        }

        // ISSUE-0830: a contextual-read function value at a table-declared
        // host parameter takes the closed host parameter cell on the
        // published LuaJIT artifact. The emitted chunk's function carrier
        // ([fn]/sig/csig/fid, no [kind]) is a function value: the loaded
        // matcher keeps its own function token, so the raw host is never
        // reached and the host never observes the internal carrier table.
        testLuaHostTableParameterFunctionCarrier(tableParam);

        // The pinned E8011 at the import origin (6:1, the corpus pin): the
        // closed HOST_LOAD_MISSING_EXPORT arm, no expected/actual field.
        String missingOrigin = importOrigin(missing, MISSING_SPECIFIER);
        checkLuaArmSeed(missing, "host/missing_export.lua", MISSING_LUA,
            "missing host export 'missing' in module 'host/missing_export'",
            missingOrigin, "missing-export-at-6:1");
        checkLuaArmSeed(missing, "host/missing_export.lua",
            "error(\"host module unavailable\", 0)\n",
            armMessage(FailureArmId.HOST_LOAD_FAILED, Map.of("module", MISSING_SPECIFIER,
                "reason", "host module unavailable")), missingOrigin,
            "require-failure");
        checkLuaArmSeed(missing, "host/missing_export.lua",
            "return { ping = function() return \"pong\" end,\n"
                + "  missing = { [__dealCarrierKeys.kind] = \"function\", sig = \"(number)->string\", "
                + "f = function() end } }\n",
            armMessage(FailureArmId.HOST_LOAD_SIGNATURE_MISMATCH, Map.of("name", "missing",
                "module", MISSING_SPECIFIER, "expected", "()->string",
                "actual", "(number)->string")), missingOrigin,
            "prewrapped-signature-mismatch");
        checkLuaArmSeed(missing, "host/missing_export.lua", "return 42\n",
            armMessage(FailureArmId.HOST_LOAD_NOT_A_MODULE, Map.of("module", MISSING_SPECIFIER)),
            missingOrigin, "non-table-module-result");
        // The unmapped runtime shape defects stay runtime-side E8011
        // failures (no declared arm composes their text).
        checkLuaSeed(missing, "host/missing_export.lua",
            "return { ping = function() return \"pong\" end,\n"
                + "  missing = { [__dealCarrierKeys.kind] = \"function\", sig = \"()->string\", f = 42 } }\n",
            "host export 'missing' in module 'host/missing_export' has non-function .f",
            -1, -1, "prewrapped-non-function-f");
        checkLuaSeed(missing, "host/missing_export.lua",
            "return { ping = function() return \"pong\" end, missing = 42 }\n",
            "host export 'missing' in module 'host/missing_export' is not a function",
            -1, -1, "non-function-export-shape");

        String cfgOrigin = importOrigin(cfgFixture, CFG_SPECIFIER);
        checkLuaArmSeed(cfgFixture, "host/cfg.lua",
            CFG_LUA.replace("@$external/host/cfg/ServerConfig",
                "@$external/host/cfg/Other"),
            armMessage(FailureArmId.HOST_LOAD_IDENTITY_MISMATCH, Map.of("name", "ServerConfig",
                "module", CFG_SPECIFIER,
                "expected", "@$external/host/cfg/ServerConfig",
                "actual", "@$external/host/cfg/Other")),
            cfgOrigin, "cfg-identity-mismatch");
        String fieldsMessage = invalidMetadata("Endpoint", CFG_SPECIFIER,
            "fields").message();
        checkLuaSuccessfulLoadSeed(cfgFixture,
            CFG_LUA.replace("ServerConfig_defaults", "ServerConfig_other"),
            "cfg-without-retired-defaults");
        checkLuaSuccessfulLoadSeed(cfgFixture,
            CFG_LUA.replace("ServerConfig_defaults = { port = 8080,",
                "ServerConfig_defaults = 42, ServerConfig_ignored = {"),
            "cfg-ignores-retired-defaults-shape");
        checkLuaSeed(cfgFixture, "host/cfg.lua",
            CFG_LUA.replace(
                "ServerConfig = { [__dealCarrierKeys.kind] = \"class\", [__dealCarrierKeys.classname] = "
                    + "\"@$external/host/cfg/ServerConfig\" }",
                "ServerConfig = 42"),
            "host export 'ServerConfig' in module 'host/cfg' is not a class meta "
                + "table",
            -1, -1, "cfg-invalid-class-shape");
        checkLuaArmSeed(cfgFixture, "host/cfg.lua",
            CFG_LUA.replace("describe = function(s)",
                "Endpoint_fields = 42, describe = function(s)"),
            fieldsMessage, cfgOrigin, "cfg-non-table-fields");
    }

    /**
     * The LuaJIT host-Error crossing (ISSUE-0787 DescriptorV13, the
     * canonical Error descriptor's host boundary): one DEAL Error value
     * crosses the declared host parameter and return cells in both
     * directions — the loaded matcher's runtime Error instance carrying
     * the declared code/message fields on the host side and the chunk's
     * own Error carrier on the DEAL side, recursively through a declared
     * host class's Error field — while the superseded v1.2 identity
     * aliases fail with the pinned E8010 and no fallback identity.
     */
    private static void testLuaErrorCrossing(Baseline fixture) throws Exception {
        System.out.println("-- the LuaJIT host Error crossing: the canonical Error "
            + "atom round-trips through the declared host cells and the "
            + "superseded aliases are refused --");
        Path workspace = Files.createTempDirectory("host-error-lua");
        try {
            SemanticLowerer.ProjectLoweringResult result = fixture.result();
            if (result.project() == null) {
                fail("the host-Error fixture lowers: " + result.diagnostics());
                return;
            }
            Path artifact = workspace.resolve("project.lua");
            Files.writeString(artifact, fixture.lua(), StandardCharsets.UTF_8);
            deployRuntime(workspace);
            writeFileIn(workspace, "host/error_probe.lua", ERROR_LUA);
            Path probe = workspace.resolve("probe.lua");
            Files.writeString(probe, luaErrorProbe(artifact), StandardCharsets.UTF_8);
            ProcessOutcome run = runProcess(List.of("luajit",
                probe.toAbsolutePath().toString()), workspace, true);
            check(run.exitCode() == 0 && run.stdout().contains("PROBE-OK"),
                "the Error host crossing round-trips through the declared cells "
                    + "and the aliases fail with the pinned E8010: exit="
                    + run.exitCode() + " stdout=" + escaped(run.stdout())
                    + " stderr=" + escaped(run.stderr()));
        } finally {
            deleteRecursively(workspace);
        }
    }

    /**
     * The LuaJIT host-Error probe: the round-trip summary (the host's own
     * transformed values read back through the DEAL Error carrier's fields),
     * the canonical-shaped superseded alias's exact E8010 refusal, and the
     * $builtin-namespace spelling's fail-closed non-admission.
     */
    private static String luaErrorProbe(Path artifact) {
        return """
            local function fail(message)
              print("PROBE-FAIL: " .. message)
              os.exit(1)
            end
            dofile(%s)
            local ok, err = __dealMain()
            if not ok then
              fail("the init walk failed: "
                .. tostring(type(err) == "table" and err.m or err))
            end
            local surface = __exportSurfaces["app"]
            local summary = surface["drive"].f()
            if summary ~= "R:E_H:hm:E_M:mm:O:W:E_H:hm" then
              fail("the Error round-trip summary: " .. tostring(summary))
            end
            local function rejected(name, alias)
              local ok2, e = pcall(surface[name].f)
              if ok2 then
                fail(name .. ": the superseded alias " .. alias .. " was admitted")
              end
              if type(e) ~= "table" or e[__dealCarrierKeys.d] ~= true then
                fail(name .. ": the failure is not the DEAL Error carrier: "
                  .. tostring(e))
              end
              if e.code ~= "E8010" then
                fail(name .. ": code " .. tostring(e.code))
              end
              if e.e ~= "Error" then
                fail(name .. ": expected " .. tostring(e.e))
              end
              if e.m ~= "return value 1 type mismatch: expected instance of "
                  .. "Error, got " .. alias then
                fail(name .. ": message " .. tostring(e.m))
              end
            end
            rejected("aliasRejected", "@/Error")
            -- The $builtin namespace is no canonical class atom at all,
            -- so the crossing fails closed and never admits the value as
            -- an Error (no fallback identity).
            local builtinOk, builtinErr =
              pcall(surface["builtinAliasRejected"].f)
            if builtinOk then
              fail("builtinAliasRejected: the superseded alias was admitted")
            end
            if not string.find(tostring(builtinErr), "@$builtin/Error", 1, true) then
              fail("builtinAliasRejected: the refusal does not name the carried "
                .. "alias: " .. tostring(builtinErr))
            end
            print("PROBE-OK")
            """.formatted(luaString(artifact.toAbsolutePath().toString()));
    }

    /**
     * The canonical {@code HOST_LOAD_INVALID_METADATA} render of one
     * declared class's metadata defect (the authority every production
     * consumer must equal).
     */
    private static BoundaryFailure invalidMetadata(String name, String module,
            String kind) {
        return FailureContractRegistry.render(
            FailureArmId.HOST_LOAD_INVALID_METADATA,
            Map.of("name", name, "module", module, "defaults|fields", kind),
            null, null, null);
    }

    /**
     * The canonical message of one declared {@code HOST_LOAD} arm render
     * (the authority every production load path must publish).
     */
    private static String armMessage(FailureArmId arm, Map<String, String> values) {
        return FailureContractRegistry.render(arm, values, null, null, null).message();
    }

    /** The import-statement origin text of one host import of a fixture. */
    private static String importOrigin(Baseline fixture, String rawSpecifier) {
        SemanticLowerer.ProjectLoweringResult result = fixture.result();
        if (result.project() == null) {
            throw new IllegalStateException("the fixture does not lower: "
                + result.diagnostics());
        }
        SemanticOp importOp = hostImportOp(result.project(), rawSpecifier);
        return importOp.origin().sourceId() + ":"
            + importOp.origin().span().startLine() + ":"
            + importOp.origin().span().startColumn();
    }

    private static void checkLuaSuccessfulLoadSeed(Baseline fixture, String rawLua,
            String label) throws Exception {
        checkLuaSeed(fixture, "host/cfg.lua", rawLua, artifact -> """
            dofile("%s")
            local ok, err = __dealMain()
            if not ok then error(tostring(err and err.m)) end
            print("PROBE-OK")
            """.formatted(artifact.toAbsolutePath().toString()), label);
    }

    private static void checkLuaArmSeed(Baseline fixture, String hostFile,
            String rawLua, String expectedMessage, String origin, String label)
            throws Exception {
        checkLuaSeed(fixture, hostFile, rawLua,
            artifact -> luaArmFailureProbe(artifact, expectedMessage, origin), label);
    }

    /** The LuaJIT arm-shaped failure probe: the closed carrier's full tuple. */
    private static String luaArmFailureProbe(Path artifact, String message,
            String origin) {
        return """
            local function fail(m) print("PROBE-FAIL: " .. m) os.exit(1) end
            dofile("%s")
            local ok, err = __dealMain()
            if ok then fail("the init succeeded") end
            if type(err) ~= "table" or err[__dealCarrierKeys.d] ~= true or err.code ~= "E8011" then
              fail("code: " .. tostring(type(err) == "table" and err.code))
            end
            if err.m ~= %s then fail("message: " .. tostring(err.m)) end
            if err.o ~= %s then fail("origin: " .. tostring(err.o)) end
            if err.e ~= nil then fail("expected: " .. tostring(err.e)) end
            if err.a ~= nil then fail("actual: " .. tostring(err.a)) end
            print("PROBE-OK")
            """.formatted(artifact.toAbsolutePath().toString(), luaString(message),
                luaString(origin));
    }

    private static void checkLuaSeed(Baseline fixture, String hostFile,
            String rawLua, String expectedMessage, int line, int column,
            String label) throws Exception {
        checkLuaSeed(fixture, hostFile, rawLua,
            artifact -> luaFailureProbe(artifact, expectedMessage, line, column), label);
    }

    private static void checkLuaSeed(Baseline fixture, String hostFile, String rawLua,
            java.util.function.Function<Path, String> driver, String label) throws Exception {
        Path workspace = Files.createTempDirectory("host-load-lua-seed");
        try {
            SemanticLowerer.ProjectLoweringResult result = fixture.result();
            if (result.project() == null) {
                fail("the seed fixture '" + label + "' lowers: " + result.diagnostics());
                return;
            }
            Path artifact = workspace.resolve("project.lua");
            Files.writeString(artifact, fixture.lua(), StandardCharsets.UTF_8);
            deployRuntime(workspace);
            writeFileIn(workspace, hostFile, rawLua);
            Path probe = workspace.resolve("probe.lua");
            Files.writeString(probe, driver.apply(artifact), StandardCharsets.UTF_8);
            ProcessOutcome run = runProcess(List.of("luajit",
                probe.toAbsolutePath().toString()), workspace, true);
            check(run.exitCode() == 0 && run.stdout().contains("PROBE-OK"),
                "the host load seed '" + label + "' executes its expected outcome: "
                    + "exit=" + run.exitCode() + " stdout=" + escaped(run.stdout())
                    + " stderr=" + escaped(run.stderr()));
        } finally {
            deleteRecursively(workspace);
        }
    }

    private static Fixture cfgOnlyFixture() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("src/cfg.d.deal", CFG_DECLARATION_SOURCE);
        sources.put("src/app.deal", """
            import * as cfg from "host/cfg";

            export function main(): null {
              return null;
            }
            """);
        return compileProject(sources, Map.of(CFG_SPECIFIER, "src/cfg.d.deal"));
    }

    /** The LuaJIT probe: one load per module, one surface value, its entries. */
    private static String luaLoadProbe(Path artifact, ModuleId probeModule,
            ModuleId cfgModule) {
        return """
            local function fail(message)
              print("PROBE-FAIL: " .. message)
              os.exit(1)
            end
            local rt = require("deal.runtime")
            local loads = {}
            local total = 0
            local realLoad = rt.load_host
            rt.load_host = function(module_path, ...)
              total = total + 1
              loads[module_path] = (loads[module_path] or 0) + 1
              return realLoad(module_path, ...)
            end
            local entry = dofile("%s")
            local ok, err = __dealMain()
            if not ok then
              fail("init failed: type=" .. type(err) .. " value=" .. tostring(err)
                .. " message=" .. tostring(type(err) == "table" and err.message)
                .. " file=" .. tostring(type(err) == "table" and err.file)
                .. " line=" .. tostring(type(err) == "table" and err.line))
            end
            if total ~= 2 then fail("load_host calls: " .. total) end
            if loads["host/abi_probe"] ~= 1 then
              fail("host/abi_probe loads: " .. tostring(loads["host/abi_probe"]))
            end
            if loads["host/cfg"] ~= 1 then
              fail("host/cfg loads: " .. tostring(loads["host/cfg"]))
            end
            if (_HOST_PROBE_LOADS or 0) ~= 1 then
              fail("host module side effects: " .. tostring(_HOST_PROBE_LOADS))
            end
            if (_HOST_CFG_LOADS or 0) ~= 1 then
              fail("host cfg side effects: " .. tostring(_HOST_CFG_LOADS))
            end
            local surface = __exportSurfaces["%s"]
            if type(surface) ~= "table" then fail("no probe surface") end
            local ping = surface["ping"]
            if type(ping) ~= "table" or ping[__dealCarrierKeys.kind] ~= "function" then
              fail("the loaded entry is not the wrapped function table")
            end
            if type(ping.f) ~= "function" then fail("the loaded entry has no callable") end
            if ping.sig ~= "()->string" then fail("the loaded wrapper sig: " .. tostring(ping.sig)) end
            if ping.f() ~= "pong" then fail("the loaded wrapper call") end
            local cfgSurface = __exportSurfaces["%s"]
            if type(cfgSurface) ~= "table" then fail("no cfg surface") end
            if cfgSurface["ServerConfig_defaults"] ~= nil then
              fail("retired defaults metadata was copied through")
            end
            local before = surface
            __moduleStates = {}
            local ok2 = __dealMain()
            if not ok2 then fail("the second drive failed") end
            if total ~= 2 then
              fail("second-drive load_host calls: " .. total)
            end
            if loads["host/abi_probe"] ~= 1 then
              fail("second-drive host/abi_probe loads")
            end
            if __exportSurfaces["%s"] ~= before then fail("surface identity changed") end
            if entry ~= __exportSurfaces["app"] then fail("the chunk return surface") end
            print("PROBE-OK")
            """.formatted(artifact.toAbsolutePath().toString(),
                probeModule.path(), cfgModule.path(), probeModule.path());
    }

    /** The LuaJIT failure probe: the init fails with the pinned E8011. */
    private static String luaFailureProbe(Path artifact, String message,
            int line, int column) {
        StringBuilder lua = new StringBuilder();
        lua.append("local function fail(m) print(\"PROBE-FAIL: \" .. m) os.exit(1) end\n");
        lua.append("dofile(\"").append(artifact.toAbsolutePath().toString())
            .append("\")\n");
        lua.append("local ok, err = __dealMain()\n");
        lua.append("if ok then fail(\"the init succeeded\") end\n");
        lua.append("if type(err) ~= \"table\" or err.code ~= \"E8011\" then fail(\"code: \" .. tostring(err and err.code)) end\n");
        lua.append("if err.message ~= ").append(luaString(message))
            .append(" then fail(\"message: \" .. tostring(err.message)) end\n");
        if (line > 0) {
            lua.append("if err.line ~= ").append(line)
                .append(" then fail(\"line: \" .. tostring(err.line)) end\n");
            lua.append("if err.column ~= ").append(column)
                .append(" then fail(\"column: \" .. tostring(err.column)) end\n");
        }
        lua.append("print(\"PROBE-OK\")\n");
        return lua.toString();
    }

    // =========================================================================
    // 3. The JVM host ABI emission surface: the artifact text
    // =========================================================================

    private static void testJvmArtifactText(Baseline fixture) throws Exception {
        System.out.println("-- the JVM artifact: the module-keyed load entry, the "
            + "declared parameter classes, the defaults capture, the wrapper cells, "
            + "and the $DealRt scope --");
        {
            SemanticLowerer.ProjectLoweringResult result = fixture.result();
            if (result.project() == null) {
                fail("the host-importing fixture lowers: " + result.diagnostics());
                return;
            }
            ExecutableLoweredProject project = result.project();
            String className = JvmBackend.classNameFor(project.entryModule().path());
            JvmSemanticEmitter.EmissionResult emission = fixture.jvm();
            String source = emission.source();

            ModuleId probeModule = hostImportModule(project, PROBE_SPECIFIER);
            String probeKey = JvmBackend.escapedIdentifier(probeModule.path());

            check(source.contains("static boolean __hostLoaded$" + probeKey + ";"),
                "the load entry is module-keyed and idempotent (the loaded flag)");
            checkEq(1, countOccurrences(source,
                    "static void __hostLoad$" + probeKey
                        + "(java.lang.String oFile, int oLine, int oCol) {"),
                "exactly one load entry per host module");
            check(source.contains("__h = java.lang.Class.forName(\"HostAbi_probe\");"),
                "the implementation class resolves through the classNameFor(rawSpecifier) "
                    + "derivation");
            check(source.contains("__h = java.lang.Class.forName(\"HostCfg\");"),
                "the second host module resolves its own implementation class");

            check(source.contains("\"nullable\", \"(?string)->?string\", "
                    + "new java.lang.Class[]{ java.lang.String.class }"),
                "nullable ABI uses the boxed carrier");
            check(source.contains("throw JvmRuntime.arm("
                    + "deal.semantic.ir.FailureArmId.HOST_SYNC_RETURN_NOTHING, "
                    + "java.util.Map.of(\"expected\", \"number\"), oFile + \":\" + oLine "
                    + "+ \":\" + oCol, \"number\", \"nothing\")"),
                "absent sync result renders the return-nothing arm");
            check(source.contains(
                    "throw __hostFail(JvmRuntime.kindReason(d), d, v, oFile, oLine, oCol);"),
                "unknown descriptors use the validated kind helper");
            check(source.contains("JvmRuntime.arm("
                    + "deal.semantic.ir.FailureArmId.ASYNC_SHAPE,"
                    + " java.util.Map.of(\"actual\", __hostKind(__r))"),
                "the declared async export renders the async-shape arm");
            check(source.contains("if (__r == JvmRuntime.MISSING) {\n      throw "
                    + "JvmRuntime.arm("
                    + "deal.semantic.ir.FailureArmId.ASYNC_SHAPE,"
                    + " java.util.Map.of(\"actual\", \"nothing\"), oFile + \":\""
                    + " + oLine + \":\" + oCol, \"async operation\","
                    + " \"nothing\");")
                    && source.contains("if (!(__r instanceof "
                        + "java.util.concurrent.CompletableFuture)) {"),
                "the declared async export's zero-result presence rule renders "
                    + "the fixed nothing actual before the shape check (never "
                    + "the present language-null carrier's table token)");
            check(source.contains("if (m.getReturnType() == "
                    + "java.lang.Void.TYPE) {\n"
                    + "        m.invoke(null, args);\n"
                    + "        return JvmRuntime.MISSING;")
                    && source.contains("      return m.invoke(null, args);\n"),
                "the reflective invocation preserves the result's presence: the "
                    + "declared void shape yields the absent marker while a "
                    + "returned value — a present null included — is returned "
                    + "unchanged");

            checkEq(source, JvmSemanticEmitter.emitProductionProject(project,
                    result.tables(), result.registries(), className,
                    fixture.surface()).source(),
                "the repeated production emission is byte-identical");
        }
    }

    // =========================================================================
    // 4. The JVM artifact under javac and java: the deployed host fixtures
    //    compile, the wrappers run the pinned cells
    // =========================================================================

    /**
     * The JVM artifact and every on-disk corpus host implementation compile
     * under {@code javac --release 25 -proc:none} and the emitted wrapper
     * cells run. The scope project imports the declarations the
     * implementations name (the ABI and declared-class declarations plus the
     * host-carrier declarations), so a corpus implementation is compiled
     * unchanged against the records and carriers it declares.
     */
    private static void testJvmToolchain(Baseline fixture) throws Exception {
        System.out.println("-- the JVM artifact under javac + java: the deployed host "
            + "fixtures compile and the wrapper cells run --");
        Path workspace = Files.createTempDirectory("host-load-jvm");
        try {
            SemanticLowerer.ProjectLoweringResult result = fixture.result();
            if (result.project() == null) {
                fail("the host-importing fixture lowers: " + result.diagnostics());
                return;
            }
            ExecutableLoweredProject project = result.project();
            ModuleId probeModule = hostImportModule(project, PROBE_SPECIFIER);
            ModuleId cfgModule = hostImportModule(project, CFG_SPECIFIER);
            String probeKey = JvmBackend.escapedIdentifier(probeModule.path());
            String cfgKey = JvmBackend.escapedIdentifier(cfgModule.path());

            // The deployed host implementations of the fixture declarations.
            Files.writeString(workspace.resolve("HostAbi_probe.java"), HOST_ABI_JAVA,
                StandardCharsets.UTF_8);
            // The deployed corpus host implementations: they must compile
            // unchanged against the synthesized scope. The JVM lane deploys
            // each <name>.java under its classNameFor("host/<name>") name
            // (the public classes' file-name rule).
            List<String> corpusHostSources = new ArrayList<>();
            Path corpusHost = Path.of("test", "conformance", "host-fixtures");
            try (var listing = Files.list(corpusHost)) {
                for (Path file : listing.sorted().toList()) {
                    String fileName = file.getFileName().toString();
                    if (!fileName.endsWith(".java")) {
                        continue;
                    }
                    String stem = fileName.substring(0, fileName.length() - ".java".length());
                    String deployed = JvmBackend.classNameFor("host/" + stem) + ".java";
                    Files.copy(file, workspace.resolve(deployed));
                    corpusHostSources.add(deployed);
                }
            }

            // The probe: the load entry's bindings, its idempotence, the
            // wrapper cells, and the declared class-identity cell (the
            // foreign identity's closed E8010 composite).
            HostDeclarationSurface.DeclarationFacts cfgFacts =
                fixture.surface().require(cfgModule);
            String serverDescriptor = DescriptorService.describe(
                cfgFacts.exports().get("ServerConfig")).canonicalSpecText();
            String endpointDescriptor = DescriptorService.describe(
                cfgFacts.exports().get("Endpoint")).canonicalSpecText();
            Files.writeString(workspace.resolve("HostModuleLoadProbe.java"),
                jvmProbe(probeKey, cfgKey, serverDescriptor, endpointDescriptor),
                StandardCharsets.UTF_8);

            List<String> sources = new ArrayList<>(List.of("HostAbi_probe.java"));
            sources.addAll(corpusHostSources);
            runJvmProbe(fixture, workspace, sources, "HostModuleLoadProbe", "deployed host ABI");
        } finally {
            deleteRecursively(workspace);
        }
    }

    private static void testJvmDeclaredArrayElementProjection(Baseline fixture)
            throws Exception {
        System.out.println("-- the JVM host wrappers: a declared array carrier's "
            + "elements are the declared element cells --");
        // The oracle's own cell projection of the same values: the element
        // arm as the host return cell's inner reason (the canonical tuple
        // the emitted wrapper must publish).
        BoundaryOutcome outcome = BoundaryExecutor.check(
            FailurePolicyId.HOST_SYNC_RETURN,
            RuntimeDescriptor.parseCanonicalText("[string]"),
            BoundaryValueView.ofArray(BoundaryValueView.of(ActualKind.STRING),
                BoundaryValueView.ofInvalidUnicode(
                    UnicodeScalars.Defect.SURROGATE)),
            BoundaryContext.none());
        if (!(outcome instanceof BoundaryOutcome.Fail fail)) {
            fail("the oracle's declared-array return cell rejects a failing element: "
                + outcome);
            return;
        }
        BoundaryFailure expected = fail.failure();
        checkEq("E8010", expected.code().name(),
            "the canonical element-failure code");
        checkEq("return value 1 type mismatch: array element 2 type mismatch",
            expected.message(), "the canonical host return composite");
        checkEq("[string]", expected.expected(),
            "the host return cell keeps the declared element-shape descriptor");
        checkEq("table", expected.actual(),
            "the host return cell projects the carrier kind of the declared "
                + "carrier");
        Path workspace = Files.createTempDirectory("host-load-jvm-array");
        try {
            SemanticLowerer.ProjectLoweringResult result = fixture.result();
            if (result.project() == null) {
                fail("the declared-array fixture lowers: " + result.diagnostics());
                return;
            }
            ExecutableLoweredProject project = result.project();
            Files.writeString(workspace.resolve("HostArray_probe.java"),
                ARRAY_JAVA_HOST, StandardCharsets.UTF_8);
            ModuleId arrayModule = hostImportModule(project, ARRAY_SPECIFIER);
            String key = JvmBackend.escapedIdentifier(arrayModule.path());
            Files.writeString(workspace.resolve("HostArrayProbeMain.java"),
                arrayProbeSource(key), StandardCharsets.UTF_8);
            runJvmProbe(fixture, workspace, new ArrayList<>(List.of(
                "HostArray_probe.java")), "HostArrayProbeMain", "declared array cells");
        } finally {
            deleteRecursively(workspace);
        }
    }

    private static String arrayProbeSource(String key) {
        return """
            final class HostArrayProbeMain {
              private static int passed = 0;
              private static int failed = 0;

              private static void check(boolean condition, String message) {
                if (condition) { passed++; }
                else { failed++; System.err.println("FAIL: " + message); }
              }

              private static void expect(String code, String message, String expected,
                  String actual, Runnable action) {
                try {
                  action.run();
                  failed++;
                  System.err.println("FAIL: no error for " + message);
                } catch (deal.codegen.jvm.JvmRuntime.DealError error) {
                  check(code.equals(error.code), "code " + error.code + " for " + message);
                  check(message.equals(error.msg),
                      "message '" + error.msg + "' for " + message);
                  check(expected.equals(error.expected),
                      "expected '" + error.expected + "' for " + message);
                  check(actual.equals(error.actual),
                      "actual '" + error.actual + "' for " + message);
                }
              }

              public static void main(String[] args) {
                App.dealMain();
                // The valid declared carrier crosses both wrapper cells and
                // its element lands in the production array.
                deal.codegen.jvm.JvmRuntime.Array good =
                    (deal.codegen.jvm.JvmRuntime.Array) App.__host$%s$echoArray(
                        new $DealRt.__StringArray(new String[] { "ok" }),
                        "probe.deal", 1, 1);
                check("ok".equals(good.elements.get(0)),
                    "the valid declared array element is projected: " + good.elements);
                // The failing element of the declared parameter carrier is
                // the element cell, projected through the host parameter cell.
                expect("E8010",
                    "parameter 1 type mismatch: array element 2 type mismatch",
                    "[string]", "table", () -> App.__host$%s$echoArray(
                        new $DealRt.__StringArray(
                            new String[] { "ok", loneSurrogate() }),
                        "probe.deal", 1, 1));
                // The same element failure on the declared return carrier is
                // projected through the host return cell.
                expect("E8010",
                    "return value 1 type mismatch: array element 2 type mismatch",
                    "[string]", "table", () -> App.__host$%s$badArray(
                        "probe.deal", 2, 1));
                if (failed != 0) {
                  throw new IllegalStateException("FAIL: " + failed
                      + " declared-array probe failures");
                }
                System.out.println("PROBE-OK " + passed);
              }

              private static String loneSurrogate() {
                return new String(new char[] { (char) 0xD800 });
              }
            }
            """.formatted(key, key, key);
    }

    /** The deployed JVM host implementation of the ABI declaration. */
    private static final String HOST_ABI_JAVA = """
        import java.util.concurrent.CompletableFuture;

        final class HostAbi_probe {
          public static Object ping() { return "pong"; }
          public static Object echoInt(double v) { return Double.valueOf(v); }
          public static Object badReturn() { return "junk"; }
          public static Object join(String sep, $DealRt.__StringArray parts) {
            return String.join(sep, parts.data);
          }
          public static Object apply($DealRt.Fn1_N_R_N f, double v) {
            return Double.valueOf(f.invoke(v) + 100);
          }
          public static Object fetchValue() {
            return CompletableFuture.completedFuture("fetched");
          }
          public static Object nullable(String v) { return v; }
        }
        """;

    /** The JVM probe: the load entry's bindings, its idempotence, the cells. */
    private static String jvmProbe(String probeKey, String cfgKey,
                                   String serverDescriptor, String endpointDescriptor) {
        return PROBE_JAVA.replace("$PROBE$", probeKey).replace("$CFG$", cfgKey)
            .replace("$SERVER_DESC$", serverDescriptor)
            .replace("$ENDPOINT_DESC$", endpointDescriptor);
    }

    private static final String PROBE_JAVA = """
            final class HostModuleLoadProbe {
              private static int passed = 0;

              private static void check(boolean condition, String message) {
                if (!condition) { throw new IllegalStateException("FAIL: " + message); }
                passed++;
              }

              private static void expect(String code, String message,
                  Runnable action) {
                try {
                  action.run();
                  throw new IllegalStateException("FAIL: no error for " + message);
                } catch (deal.codegen.jvm.JvmRuntime.DealError error) {
                  check(code.equals(error.code),
                      "code " + error.code + " for " + message);
                  check(message.equals(error.msg),
                      "message '" + error.msg + "' for " + message);
                }
              }

              public static void main(String[] args) {
                App.dealMain();
                check(App.__hostLoaded$$PROBE$, "the load entry ran in the walk");
                check(App.__hostM$$PROBE$$ping != null, "the ping binding exists");
                check(App.__hostM$$PROBE$$echoInt != null, "the echoInt binding exists");
                check(App.__hostM$$PROBE$$join != null, "the join binding exists");
                check(App.__hostM$$PROBE$$apply != null, "the apply binding exists");
                check(App.__hostM$$PROBE$$fetchValue != null, "the async binding exists");
                for (java.lang.reflect.Field field : App.class.getDeclaredFields()) {
                  check(!field.getName().startsWith("__hostD$"),
                      "no retired defaults capture exists");
                }
                java.lang.reflect.Method before = App.__hostM$$PROBE$$ping;
                App.__hostLoad$$PROBE$("probe.deal", 6, 1);
                check(App.__hostM$$PROBE$$ping == before,
                    "the second load entry call binds nothing new");

                // The declared parameter and return cells.
                // The call arms project the production carrier onto the
                // declared host carrier at the boundary cell (a later slice);
                // this drive passes the declared carrier directly.
                Object echo = App.__host$$PROBE$$echoInt(Integer.valueOf(7),
                    "probe.deal", 3, 1);
                check(Double.valueOf(7).equals(echo), "the number roundtrip: " + echo);
                Object joined = App.__host$$PROBE$$join("-",
                    new $DealRt.__StringArray(new String[] {"a", "b"}),
                    "probe.deal", 4, 1);
                check("a-b".equals(joined), "the array carrier roundtrip: " + joined);
                Object applied = App.__host$$PROBE$$apply(new $DealRt.Fn1_N_R_N() {
                    double invoke(double p0) { return p0 + 1; }
                  }, Integer.valueOf(7), "probe.deal", 5, 1);
                check(Double.valueOf(108).equals(applied),
                    "the function carrier reaches the host bridge: " + applied);

                // The pinned E8010 parameter cells.
                expect("E8010", "parameter 2 type mismatch: expected array",
                    () -> App.__host$$PROBE$$join("x", Double.valueOf(1.0),
                        "probe.deal", 7, 1));
                expect("E8010", "parameter 1 type mismatch: expected function",
                    () -> App.__host$$PROBE$$apply(Double.valueOf(1.0),
                        Integer.valueOf(1), "probe.deal", 8, 1));

                // The pinned E8010 return cell (the host returns junk for number).
                expect("E8010", "return value 1 type mismatch: expected number",
                    () -> App.__host$$PROBE$$badReturn("probe.deal", 9, 1));

                // The declared class-identity cell: a foreign identity renders
                // the closed E8010 host composite with the identity inner
                // reason (the host inner-reason pass-through) — never a
                // top-level E8001.
                expect("E8010", "parameter 1 type mismatch: expected instance of "
                    + "$SERVER_DESC$" + ", got $ENDPOINT_DESC$",
                    () -> App.__host$$CFG$$describe(new Foreign(), "probe.deal", 11, 1));
                expect("E8010", "parameter 1 type mismatch: expected class instance",
                    () -> App.__host$$CFG$$describe("junk", "probe.deal", 12, 1));

                System.out.println("PROBE-OK " + passed);
              }

              /** A class value of another declared identity. */
              static final class Foreign implements deal.codegen.jvm.JvmRuntime.ClassInstance {
                public String classIdText() { return "$ENDPOINT_DESC$"; }
                public boolean isPresent(String field) { return false; }
                public Object read(String field) {
                  return deal.codegen.jvm.JvmRuntime.MISSING;
                }
                public void write(String field, Object value) { }
                public void delete(String field) { }
              }
            }
            """;

    // =========================================================================
    // 5. The JVM pinned E8011 at the import origin
    // =========================================================================

    private static void testJvmMissingExport(Baseline fixture, Baseline cfgFixture)
            throws Exception {
        System.out.println("-- the JVM load entry raises the pinned E8011 at the "
            + "import origin --");
        {
            checkJvmLoadFailure(fixture, "HostMissing_export.java", """
                final class HostMissing_export {
                  public static Object ping() { return "pong"; }
                }
                """, "E8011",
                "missing host export 'missing' in module 'host/missing_export'",
                importOrigin(fixture, MISSING_SPECIFIER), "missing-export-at-6:1");
        }

        // The failed-load analogue on the JVM target: a host module with no
        // deployed implementation class renders the closed HOST_LOAD_FAILED
        // arm — module and reason, no expected/actual field — at the import
        // statement's origin, never the emitter's own composed
        // "not found (class ...)" text.
        {
            String reason = "java.lang.ClassNotFoundException: HostMissing_export";
            checkJvmLoadFailure(fixture, null, null, "E8011",
                "failed to load host module 'host/missing_export': " + reason,
                importOrigin(fixture, MISSING_SPECIFIER), "missing-host-class");
        }

        // Unrelated host export validation still runs when retired defaults
        // metadata is absent or has an arbitrary shape.
        String cfgOrigin = importOrigin(cfgFixture, CFG_SPECIFIER);
        checkJvmLoadFailure(cfgFixture, "HostCfg.java", """
            final class HostCfg {
              public static final java.util.Map<String, Object> Endpoint_defaults =
                  java.util.Map.of("path", "/");
              public static Object describe(Object s) { return "cfg"; }
            }
            """, "E8011",
            "missing host export 'describe' in module 'host/cfg'",
            cfgOrigin, "missing-export-without-defaults");
        checkJvmLoadFailure(cfgFixture, "HostCfg.java", """
            final class HostCfg {
              public static final java.util.Map<String, Object> Endpoint_defaults =
                  java.util.Map.of("path", "/");
              public static final Object ServerConfig_defaults = 42;
              public static Object describe(Object s) { return "cfg"; }
            }
            """, "E8011",
            "missing host export 'describe' in module 'host/cfg'",
            cfgOrigin, "missing-export-with-arbitrary-retired-defaults");
    }

    private static void checkJvmLoadFailure(Baseline fixture, String hostFileName,
            String hostSource, String expectedCode, String expectedMessage,
            String expectedOrigin, String label) throws Exception {
        Path workspace = Files.createTempDirectory("host-load-jvm-seed");
        try {
            SemanticLowerer.ProjectLoweringResult result = fixture.result();
            if (result.project() == null) {
                fail("the seed fixture '" + label + "' lowers: " + result.diagnostics());
                return;
            }
            List<String> compiled = new ArrayList<>();
            if (hostFileName != null) {
                Files.writeString(workspace.resolve(hostFileName), hostSource,
                    StandardCharsets.UTF_8);
                compiled.add(hostFileName);
            }
            Files.writeString(workspace.resolve("HostLoadFailureProbe.java"),
                jvmFailureProbe(expectedCode, expectedMessage, expectedOrigin),
                StandardCharsets.UTF_8);
            runJvmProbe(fixture, workspace, compiled, "HostLoadFailureProbe", label);
        } finally {
            deleteRecursively(workspace);
        }
    }

    /** The JVM load-failure probe of one pinned E8011. */
    private static String jvmFailureProbe(String expectedCode,
            String expectedMessage, String expectedOrigin) {
        String originCheck = expectedOrigin == null ? ""
            : "      if (!" + quoted(expectedOrigin)
                + ".equals(error.origin)) { throw new IllegalStateException("
                + "\"FAIL: origin \" + error.origin); }\n";
        // Every HOST_LOAD arm this probe drives (a missing export, an
        // absent host module class, and the class-metadata defects)
        // declares no expected/actual field: the probe asserts the complete
        // canonical tuple (a fabricated field fails by name), so the
        // production load path can never publish one for these arms.
        return """
            final class HostLoadFailureProbe {
              public static void main(String[] args) {
                try {
                  App.dealMain();
                  throw new IllegalStateException("FAIL: the init succeeded");
                } catch (deal.codegen.jvm.JvmRuntime.DealError error) {
                  if (!$CODE$.equals(error.code)) {
                    throw new IllegalStateException("FAIL: code " + error.code);
                  }
                  if (!$MESSAGE$.equals(error.msg)) {
                    throw new IllegalStateException("FAIL: message " + error.msg);
                  }
                  if (error.expected != null || error.actual != null) {
                    throw new IllegalStateException("FAIL: fabricated expected/actual "
                        + error.expected + "/" + error.actual);
                  }
            $ORIGIN$      System.out.println("PROBE-OK " + error.origin);
                }
              }
            }
            """.replace("$CODE$", quoted(expectedCode))
            .replace("$MESSAGE$", quoted(expectedMessage))
            .replace("$ORIGIN$", originCheck);
    }

    /** One Java string literal of the given text. */
    private static String quoted(String text) {
        StringBuilder literal = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> literal.append("\\\"");
                case '\\' -> literal.append("\\\\");
                default -> literal.append(c);
            }
        }
        return literal.append('"').toString();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Path publishLuaHost(Path root, String module, String declaration,
            String app, String host, String label) throws Exception {
        return publishLuaHost(root, module, declaration, app, host, label,
            Map.of());
    }

    private static Path publishLuaHost(Path root, String module, String declaration,
            String app, String host, String label,
            Map<String, String> extraSources) throws Exception {
        writeFileIn(root, "deal.json", """
            {"languageVersion": "1.3", "backend": "luajit",
             "moduleRoots": ["src"], "output": "out",
             "externals": {"host/%s":
               {"declaration": "bindings/%s.d.deal"}}}
            """.formatted(module, module));
        writeFileIn(root, "bindings/" + module + ".d.deal", declaration);
        writeFileIn(root, "src/app.deal", app);
        for (Map.Entry<String, String> source : extraSources.entrySet()) {
            writeFileIn(root, source.getKey(), source.getValue());
        }
        int exit = deal.Main.run(new String[] {"compile",
            root.resolve("src/app.deal").toAbsolutePath().toString()});
        checkEq(0, exit, "the " + label + " project compiles through the public deal compile");
        if (exit != 0) {
            return null;
        }
        Path out = root.resolve("out");
        check(Files.isRegularFile(out.resolve("app.lua")),
            "the published artifact set carries the entry artifact: " + out);
        writeFileIn(out, "host/" + module + ".lua", host);
        return out;
    }

    private static void deployRuntime(Path workspace) throws Exception {
        Path runtime = Path.of("deal", "runtime.lua");
        Path runtimeTarget = workspace.resolve("deal").resolve("runtime.lua");
        Files.createDirectories(runtimeTarget.getParent());
        Files.copy(runtime, runtimeTarget);
        Path stdTarget = workspace.resolve("std");
        Files.createDirectories(stdTarget);
        try (var listing = Files.list(Path.of("std"))) {
            for (Path file : listing.sorted().toList()) {
                if (file.getFileName().toString().endsWith(".lua")) {
                    Files.copy(file, stdTarget.resolve(file.getFileName()));
                }
            }
        }
        check(Files.isRegularFile(runtimeTarget)
                && Files.isRegularFile(stdTarget.resolve("console.lua")),
            "the workspace carries the runtime and stdlib deployment copies");
    }

    record ProcessOutcome(int exitCode, String stdout, String stderr) {

        String output() {
            return "stdout=" + stdout.replace("\n", "\\n")
                + " stderr=" + stderr.replace("\n", "\\n");
        }
    }

    static ProcessOutcome runProcess(List<String> command, Path workDir,
            boolean deferMain) throws Exception {
        Path stderrFile = Files.createTempFile(workDir, "stderr", ".txt");
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(workDir.toFile());
            if (deferMain) {
                builder.environment().put("DEAL_DEFER_MAIN", "1");
            }
            Process process = builder.redirectError(stderrFile.toFile()).start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            return new ProcessOutcome(exit, stdout, Files.readString(stderrFile, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(stderrFile);
        }
    }

    private static void runJvmProbe(Baseline fixture, Path workspace, List<String> sources,
            String template, String label) throws Exception {
        Path prepared = fixture.jvmClasses();
        if (!Files.isRegularFile(prepared.resolve(fixture.jvm().className() + ".class"))) {
            Path source = prepared.getParent().resolve(fixture.jvm().className() + ".java");
            Files.writeString(source, fixture.jvm().source(), StandardCharsets.UTF_8);
            Files.createDirectories(prepared);
            JvmCompilation.Result compiled = JvmCompilation.compile(List.of("javac",
                "--release", "25", "-proc:none", "-cp", absoluteClasspath(),
                "-d", prepared.toString(), source.toString()), workspace);
            checkEq(0, compiled.exitCode(), label + ": prepared artifact: " + compiled.stderr());
            if (compiled.exitCode() != 0) {
                throw new AssertionError(compiled.stderr());
            }
        }
        String driver = template + "_" + java.util.UUID.randomUUID().toString().replace("-", "");
        Path source = workspace.resolve(template + ".java");
        Files.writeString(workspace.resolve(driver + ".java"),
            Files.readString(source).replace(template, driver), StandardCharsets.UTF_8);
        sources.add(driver + ".java");
        Path classes = Files.createDirectories(workspace.resolve("classes"));
        String classpath = prepared + java.io.File.pathSeparator + absoluteClasspath();
        List<String> command = new ArrayList<>(List.of("javac", "--release", "25",
            "-proc:none", "-cp", classpath, "-d", classes.toString()));
        command.addAll(sources);
        JvmCompilation.Result compiled = JvmCompilation.compile(command, workspace);
        checkEq(0, compiled.exitCode(), label + ": real javac: " + compiled.stderr());
        if (compiled.exitCode() == 0) {
            ProcessOutcome run = runProcess(List.of("java", "-cp",
                classes + java.io.File.pathSeparator + classpath, driver), workspace, false);
            check(run.exitCode() == 0 && run.stdout().contains("PROBE-OK")
                    && run.stderr().isEmpty(), label + ": real java: " + run.output());
        }
    }

    static String absoluteClasspath() {
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

    private static String luaString(String value) {
        StringBuilder text = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> text.append("\\\"");
                case '\\' -> text.append("\\\\");
                case '\n' -> text.append("\\n");
                default -> text.append(c);
            }
        }
        return text.append('"').toString();
    }

    private static String escaped(String text) {
        return text.replace("\n", "\\n");
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

    static void writeFileIn(Path root, String relative, String content)
            throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    static void deleteRecursively(Path path) {
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
        System.out.println("=== Host Module Load / JVM Host ABI Surface Tests "
            + "(ISSUE-0650) ===\n");
        List<Path> roots = new ArrayList<>();
        try {
            testOrderedExtraKeyScan(roots);
            Baseline abi = prepare(abiFixture(ABI_APP_SOURCE), true, roots);
            Baseline aliases = prepare(abiFixture(TWO_ALIAS_APP_SOURCE), false, roots);
            Baseline hostReads = prepare(abiFixture(HOST_READ_APP_SOURCE), false, roots);
            Baseline missing = prepare(missingFixture(), true, roots);
            Baseline cfg = prepare(cfgOnlyFixture(), true, roots);
            Baseline array = prepare(arrayFixture(), true, roots);
            Baseline errors = prepare(errorFixture(), false, roots);
            Baseline tableParam = prepare(tableParamFixture(), false, roots);
            Baseline carrierHost = prepare(carrierHostFixture(), true, roots);
            testLuaArtifactText(abi, aliases);
            testLuaHostCarrierMetadata(roots);
            testLuaHostCarrierMetadataAbsent(roots);
            testLuaHostCarrierBridge(roots);
            testHostReadCorrelation(hostReads);
            testLuaHostDecodedNumericCarrier();
            testLuaHostTableValue();
            testLuaHostTableProjection();
            testLuaHostArrayCrossings();
            testLuaHostArrayCrossingFailures();
            testLuaHostClassConstructionDefaults();
            testLuaHostClassCrossings();
            testLuaHostAsyncCompletion();
            testLuaHostAsyncStartCrossings();
            testLuaHostFunctionAdapter();
            testLuaHostUntypedCarrierCrossings();
            testLuaExecution(aliases, missing, cfg, tableParam);
            testLuaErrorCrossing(errors);
            testJvmArtifactText(abi);
            testJvmToolchain(carrierHost);
            testJvmMissingExport(missing, cfg);
            testJvmDeclaredArrayElementProjection(array);
        } finally {
            for (Path root : roots) {
                deleteRecursively(root);
            }
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== Host Module Load / JVM Host ABI Surface Tests Passed ===");
    }
}
