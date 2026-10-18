package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmBackend;
import deal.codegen.jvm.JvmSemanticEmitter;
import deal.codegen.lua.LuaSemanticEmitter;
import deal.ffi.FfiGeneratedModule;
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
import deal.semantic.ir.DefaultOwner;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.FailureArmId;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ModuleImportKind;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.UnicodeScalars;
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

    /**
     * The HOST construction's accepted-key ordering fixture: two declared
     * optional fields ({@code fallback}, {@code peers}) are absent from
     * the loaded {@code Config_defaults} map, so a literal providing both
     * supplies two names outside the accepted-key authority.
     */
    private static final String ORDER_DECLARATION_SOURCE = """
        export class Config {
          port: int;
          fallback?: int;
          peers?: int[];
        }

        export function ping(): string;
        """;

    /** The construction literal with the provided field order substituted. */
    private static final String ORDER_APP_TEMPLATE = """
        import * as cfg from "host/order";

        export function main(): null {
          let c: cfg.Config = {%s};
          return null;
        }
        """;

    /** The deployed host implementation: {@code Config_defaults} carries only {@code port}. */
    private static final String ORDER_LUA = """
        return {
          ping = function() return "pong" end,
          Config = { [__dealCarrierKeys.kind] = "class", [__dealCarrierKeys.classname] = "@$external/host/order/Config" },
          Config_defaults = { port = 1 },
        }
        """;

    private static final String MISSING_SPECIFIER = "host/missing_export";
    private static final String ARRAY_SPECIFIER = "host/array_probe";
    private static final String ERROR_SPECIFIER = "host/error_probe";

    /** The host ABI declaration: scalar, array, function, bytes, and async positions. */
    private static final String ABI_DECLARATION_SOURCE = """
        export function ping(): string;
        export function echoInt(v: int): int;
        export function badReturn(): int;
        export function join(sep: string, parts: string[]): string;
        export function apply(f: (x: int) => int, v: int): int;
        export async function fetchValue(): string;
        export function echoBytes(b: bytes): bytes;
        export function nullable(v: string | null): string | null;
        """;

    /** The declared-class declaration (the synthesized record and array carriers). */
    private static final String CFG_DECLARATION_SOURCE = """
        export class Endpoint {
          path: string;
        }

        export class ServerConfig {
          port: int;
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
          let a: int = first.echoInt(1);
          let b: int = second.echoInt(2);
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
          holder.f = function(): int { return 1; };
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
          echoBytes = function(b) return b end,
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

    private record Fixture(
        Path root,
        CheckedProjectInput checkedProject,
        ProjectInterfaceIndex index,
        List<SemanticRequirementManifest> manifests,
        HostDeclarationSurface surface,
        Map<ModuleId, FfiGeneratedModule> externCModules,
        Map<ModuleId, CanonicalModuleIdentity> declarationIdentities,
        Map<String, String> externals) {
    }

    private record Baseline(
        SemanticLowerer.ProjectLoweringResult result,
        HostDeclarationSurface surface,
        String lua,
        JvmSemanticEmitter.EmissionResult jvm) {
    }

    private static Baseline prepare(Fixture fixture, boolean emitJvm,
            List<Path> roots) {
        roots.add(fixture.root());
        SemanticLowerer.ProjectLoweringResult result = lower(fixture);
        if (result.project() == null) {
            return new Baseline(result, fixture.surface(), null, null);
        }
        ExecutableLoweredProject project = result.project();
        String lua = LuaSemanticEmitter.emitProductionProject(project,
            result.tables(), result.registries(), fixture.surface());
        JvmSemanticEmitter.EmissionResult jvm = emitJvm
            ? JvmSemanticEmitter.emitProductionProject(project, result.tables(),
                result.registries(), JvmBackend.classNameFor(project.entryModule().path()),
                fixture.surface())
            : null;
        return new Baseline(result, fixture.surface(), lua, jvm);
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
        Map<ModuleId, FfiGeneratedModule> externCModules = new LinkedHashMap<>();
        for (Map.Entry<String, FfiGeneratedModule> generated
                : orchestrator.ffiGenerations().entrySet()) {
            externCModules.put(new ModuleId(generated.getKey()), generated.getValue());
        }
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
            surface, externCModules, identities, externals);
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

    private static SemanticLowerer.ProjectLoweringResult lower(Fixture fixture) {
        return SemanticLowerer.lowerProject(productionInvocation(),
            fixture.checkedProject(), fixture.index(), fixture.manifests(),
            fixture.surface(), fixture.declarationIdentities(),
            fixture.externCModules(),
            BuiltinErrorDeclaration.synthesized(
                fixture.checkedProject().modules().get(0).ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                IntrinsicKind.BYTES_NEW),
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

    /**
     * The HOST construction's deterministic extra-key scan: the loaded
     * {@code <C>_defaults} key membership is the accepted-key authority,
     * and the first provided-source name outside it fails with the exact
     * E8007 tuple the oracle renders — before any field boundary check, in
     * the provided order, never the runtime table's hash order. Two
     * declared optional fields ({@code fallback}, {@code peers}) are
     * absent from the loaded map, so the two provided orders name two
     * different first-extra fields and a hash-order scan cannot satisfy
     * both.
     */
    private static void testOrderedExtraKeyScan(List<Path> roots) throws Exception {
        System.out.println("-- the HOST construction's extra-key scan: the first "
            + "provided-source name absent from the loaded <C>_defaults fails "
            + "with the oracle's exact E8007 tuple, in provided order --");
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
        String message = "extra field '" + firstExtra
            + "' in class '@$external/host/order/Config'";
        String origin = construction.origin().sourceId() + ":"
            + construction.origin().span().startLine() + ":"
            + construction.origin().span().startColumn();

        // The oracle: the same provided-source scan over the same loaded map.
        SemanticOracle.HostResponder responder = new SemanticOracle.HostResponder() {
            @Override
            public Map<String, SemanticOracle.Value> loadedClassDefaults(
                    ClassId classId) {
                if (!ORDER_CONFIG.equals(classId)) {
                    return null;
                }
                Map<String, SemanticOracle.Value> defaults = new LinkedHashMap<>();
                defaults.put("port", new SemanticOracle.Value.IntValue(1));
                return defaults;
            }
        };
        Map<ClassId, ClassLayout> declarationLayouts = new LinkedHashMap<>();
        for (ClassId classId : baseline.result().seeds().classIds()) {
            declarationLayouts.put(classId,
                baseline.result().seeds().registrationFor(classId).layout());
        }
        SemanticRuntimeModel.ConsumerRun oracleRun =
            SemanticOracle.executeProjectInits(project, baseline.result().tables(),
                baseline.result().registries(), responder, declarationLayouts);
        if (oracleRun.terminal()
                instanceof SemanticRuntimeModel.Terminal.DealFailure failure) {
            checkEq("E8007", failure.error().code(),
                "the oracle names the pinned E8007 for '" + provided + "'");
            checkEq(message, failure.error().message(),
                "the oracle reports the first provided-source extra field for '"
                    + provided + "'");
            checkEq(origin, failure.error().origin(),
                "the oracle carries the literal origin for '" + provided + "'");
        } else {
            fail("the oracle does not fail the construction for '" + provided
                + "': " + oracleRun.terminal());
        }

        // The real LuaJIT artifact: the same tuple through the published chunk.
        Path workspace = Files.createTempDirectory("host-order-lua");
        try {
            Path artifact = workspace.resolve("project.lua");
            Files.writeString(artifact, baseline.lua(), StandardCharsets.UTF_8);
            deployRuntime(workspace);
            writeFileIn(workspace, "host/order.lua", ORDER_LUA);
            Path probe = workspace.resolve("probe.lua");
            Files.writeString(probe, orderedExtraKeyProbe(artifact, message, origin),
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

    /** The lowered {@code CLASS_NEW(HOST_DEFAULTS)} of the ordered-extra-key fixture. */
    private static SemanticOp orderedConstruction(ExecutableLoweredProject project) {
        for (LoweredModuleUnit unit : project.modules().values()) {
            for (SemanticOp op : unit.ops()) {
                if (op.kind() == SemanticOpKind.CLASS_NEW
                        && op.payload() instanceof KindPayload.ClassNewPayload payload
                        && payload.defaultOwner() == DefaultOwner.HOST_DEFAULTS) {
                    return op;
                }
            }
        }
        throw new IllegalStateException("the fixture carries no"
            + " CLASS_NEW(HOST_DEFAULTS) construction");
    }

    /** The LuaJIT probe: the construction fails with the pinned E8007 tuple. */
    private static String orderedExtraKeyProbe(Path artifact, String message,
            String origin) {
        return """
            local function fail(m) print("PROBE-FAIL: " .. m) os.exit(1) end
            dofile("%s")
            local ok, err = __dealMain()
            if ok then fail("the construction succeeded") end
            if type(err) ~= "table" or err[__dealCarrierKeys.d] ~= true then
              fail("not a DEAL failure: " .. tostring(type(err)))
            end
            if err.code ~= "E8007" then fail("code: " .. tostring(err.code)) end
            if err.m ~= %s then fail("message: " .. tostring(err.m)) end
            if err.o ~= %s then fail("origin: " .. tostring(err.o)) end
            print("PROBE-OK")
            """.formatted(artifact.toAbsolutePath().toString(), luaString(message),
                luaString(origin));
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
          count: int;
          ratio: number;
        }

        export async function absorbAsync(nums: number[]): string;
        export function echoNumbers(nums: number[]): number[];
        export function observeNumbers(nums: number[]): string;
        export function observeInts(ns: (int | null)[]): string;
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
          let ns: (int | null)[] = intsDoc.ns;

          let cfgDoc: table = json.parse("{\\"count\\":7,\\"ratio\\":2.5}");
          let cfg: host.Cfg = { count: cfgDoc.count, ratio: cfgDoc.ratio };

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

    /** The transcript the raw host must observe (ISSUE-0836). */
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
        "{\"n\":null,\"v\":0.1,\"xs\":[1,0.1,-2.147483649E9]}",
        "{\"n\":null,\"v\":0.1,\"xs\":[1,0.1,-2.147483649E9]}",
        "{\"n\":null,\"v\":0.1,\"xs\":[1,0.1,-2.147483649E9]}",
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
            writeFileIn(root, "deal.json", """
                {"languageVersion": "1.3", "backend": "luajit",
                 "moduleRoots": ["src"], "output": "out",
                 "externals": {"host/num_probe":
                   {"declaration": "bindings/num_probe.d.deal"}}}
                """);
            writeFileIn(root, "bindings/num_probe.d.deal",
                NUMERIC_DECLARATION_SOURCE);
            writeFileIn(root, "src/app.deal", NUMERIC_APP_SOURCE);
            Path entry = root.resolve("src/app.deal").toAbsolutePath();
            int exit = deal.Main.run(new String[] {"compile", entry.toString()});
            check(exit == 0, "the decoded-numeric-carrier project compiles"
                + " through the public deal compile: exit=" + exit);
            if (exit != 0) {
                return;
            }
            Path out = root.resolve("out");
            Path artifact = out.resolve("app.lua");
            check(Files.isRegularFile(artifact),
                "the published artifact set carries the entry artifact: "
                    + artifact);
            writeFileIn(out, "host/num_probe.lua", NUMERIC_HOST_LUA);
            writeFileIn(out, "probe.lua", """
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
                    + " keeps its own int/number variants: exit="
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
            writeFileIn(root, "deal.json", """
                {"languageVersion": "1.3", "backend": "luajit",
                 "moduleRoots": ["src"], "output": "out",
                 "externals": {"host/tab_probe":
                   {"declaration": "bindings/tab_probe.d.deal"}}}
                """);
            writeFileIn(root, "bindings/tab_probe.d.deal",
                TABLE_PROBE_DECLARATION_SOURCE);
            writeFileIn(root, "src/app.deal", TABLE_PROBE_APP_SOURCE);
            Path entry = root.resolve("src/app.deal").toAbsolutePath();
            int exit = deal.Main.run(new String[] {"compile", entry.toString()});
            check(exit == 0, "the dynamic-table project compiles through the"
                + " public deal compile: exit=" + exit);
            if (exit != 0) {
                return;
            }
            Path out = root.resolve("out");
            check(Files.isRegularFile(out.resolve("app.lua")),
                "the published artifact set carries the entry artifact");
            writeFileIn(out, "host/tab_probe.lua", TABLE_PROBE_HOST_LUA);
            writeFileIn(out, "probe.lua", """
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
     * host-driven invocation passes a plain Lua table back into DEAL, and
     * a self-referential host table (the walk's closed cycle arm).
     */
    private static final String HOST_TABLE_DECLARATION_SOURCE = """
        export function makeTable(): table;
        export function makeNested(): table;
        export function makeCycle(): table;
        export function describe(t: table): string;
        export function withTable(f: (t: table) => string): string;
        """;

    /**
     * The DEAL side of the host-crossed table value: {@code table.keys}
     * and {@code json.stringify} consume the plain table a host hands
     * over — the return value, the callback argument, a nested member and
     * a table the DEAL side wrote after the crossing (the first write's
     * recorded order beside the host's own keys) — and a
     * self-referential host table renders the closed cycle arm.
     */
    private static final String HOST_TABLE_APP_SOURCE = """
        import * as host from "host/table_probe";
        import * as tables from "std/table";
        import * as json from "std/json";

        function joined(keys: string[]): string {
          let out: string = "";
          let i: int = 0;
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

          return first + "\\n" + second + "\\n" + third + "\\n" + fourth;
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
        "written=extra,a:{\"extra\":9,\"a\":1} host=a=1 extra=9",
        "callback=cb:cb:{\"cb\":2}",
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
     * artifact under real luajit.
     */
    private static void testLuaHostTableValue() throws Exception {
        System.out.println("-- the host-crossed table value: the published "
            + "LuaJIT artifact under real luajit --");
        Path root = Files.createTempDirectory("host-table-lua");
        try {
            writeFileIn(root, "deal.json", """
                {"languageVersion": "1.3", "backend": "luajit",
                 "moduleRoots": ["src"], "output": "out",
                 "externals": {"host/table_probe":
                   {"declaration": "bindings/table_probe.d.deal"}}}
                """);
            writeFileIn(root, "bindings/table_probe.d.deal",
                HOST_TABLE_DECLARATION_SOURCE);
            writeFileIn(root, "src/app.deal", HOST_TABLE_APP_SOURCE);
            Path entry = root.resolve("src/app.deal").toAbsolutePath();
            int exit = deal.Main.run(new String[] {"compile", entry.toString()});
            check(exit == 0, "the host-table project compiles through the"
                + " public deal compile: exit=" + exit);
            if (exit != 0) {
                return;
            }
            Path out = root.resolve("out");
            Path artifact = out.resolve("app.lua");
            check(Files.isRegularFile(artifact),
                "the published artifact set carries the entry artifact: "
                    + artifact);
            writeFileIn(out, "host/table_probe.lua", HOST_TABLE_LUA);
            writeFileIn(out, "probe.lua", """
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
    // 2b. The LuaJIT load under real luajit
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
                + "  missing = { [__dealCarrierKeys.kind] = \"function\", sig = \"(int)->string\", "
                + "f = function() end } }\n",
            armMessage(FailureArmId.HOST_LOAD_SIGNATURE_MISMATCH, Map.of("name", "missing",
                "module", MISSING_SPECIFIER, "expected", "()->string",
                "actual", "(int)->string")), missingOrigin,
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
        String defaultsMessage = invalidMetadata("ServerConfig", CFG_SPECIFIER,
            "defaults").message();
        String fieldsMessage = invalidMetadata("Endpoint", CFG_SPECIFIER,
            "fields").message();
        checkLuaArmSeed(cfgFixture, "host/cfg.lua",
            CFG_LUA.replace("ServerConfig_defaults", "ServerConfig_other"),
            defaultsMessage, cfgOrigin, "cfg-missing-defaults");
        checkLuaArmSeed(cfgFixture, "host/cfg.lua",
            CFG_LUA.replace("ServerConfig_defaults = { port = 8080,",
                "ServerConfig_defaults = 42, ServerConfig_ignored = {"),
            defaultsMessage, cfgOrigin, "cfg-non-table-defaults");
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
                "the negative seed '" + label + "' fails with the pinned E8011: "
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
            if type(cfgSurface["ServerConfig_defaults"]) ~= "table" then
              fail("the loaded <C>_defaults copy-through")
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
                    + "java.util.Map.of(\"expected\", \"int\"), oFile + \":\" + oLine "
                    + "+ \":\" + oCol, \"int\", \"nothing\")"),
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
            String className = JvmBackend.classNameFor(project.entryModule().path());
            JvmSemanticEmitter.EmissionResult emission = fixture.jvm();
            Path source = workspace.resolve(className + ".java");
            Files.writeString(source, emission.source(), StandardCharsets.UTF_8);

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

            List<String> sources = new ArrayList<>(List.of(source.toString(), "HostAbi_probe.java"));
            sources.addAll(corpusHostSources);
            runJvmProbe(workspace, sources, "HostModuleLoadProbe", "deployed host ABI");
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
            String className = JvmBackend.classNameFor(project.entryModule().path());
            JvmSemanticEmitter.EmissionResult emission = fixture.jvm();
            Files.writeString(workspace.resolve(className + ".java"),
                emission.source(), StandardCharsets.UTF_8);
            Files.writeString(workspace.resolve("HostArray_probe.java"),
                ARRAY_JAVA_HOST, StandardCharsets.UTF_8);
            ModuleId arrayModule = hostImportModule(project, ARRAY_SPECIFIER);
            String key = JvmBackend.escapedIdentifier(arrayModule.path());
            Files.writeString(workspace.resolve("HostArrayProbeMain.java"),
                arrayProbeSource(key), StandardCharsets.UTF_8);
            runJvmProbe(workspace, new ArrayList<>(List.of(className + ".java",
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
          public static Object echoInt(int v) { return Integer.valueOf(v); }
          public static Object badReturn() { return "junk"; }
          public static Object join(String sep, $DealRt.__StringArray parts) {
            return String.join(sep, parts.data);
          }
          public static Object apply($DealRt.Fn1_I_R_I f, int v) {
            return Integer.valueOf(f.invoke(v) + 100);
          }
          public static Object fetchValue() {
            return CompletableFuture.completedFuture("fetched");
          }
          public static Object echoBytes($DealRt.Bytes b) { return b; }
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
                check(App.__hostM$$PROBE$$echoBytes != null, "the bytes binding exists");
                check(App.__hostD$$CFG$$ServerConfig != null,
                    "the defaults capture exists");
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
                check(Integer.valueOf(7).equals(echo), "the int roundtrip: " + echo);
                Object joined = App.__host$$PROBE$$join("-",
                    new $DealRt.__StringArray(new String[] {"a", "b"}),
                    "probe.deal", 4, 1);
                check("a-b".equals(joined), "the array carrier roundtrip: " + joined);
                // The production-carrier bytes admission (K6 item 13 / H7):
                // the DEAL-side call passes the production
                // JvmRuntime.BytesValue before the host-facing projection, so
                // the emitted parameter cell admits it, the host-facing
                // crossing projects it onto the deployed $DealRt.Bytes view,
                // and the out-and-back buffer stays one buffer.
                deal.codegen.jvm.JvmRuntime.BytesValue buffer =
                    new deal.codegen.jvm.JvmRuntime.BytesValue(
                        new byte[] {1, 2, 3});
                Object bytesEcho = App.__host$$PROBE$$echoBytes(buffer,
                    "probe.deal", 6, 1);
                check(bytesEcho == buffer,
                    "the production bytes carrier roundtrips as one buffer: "
                        + bytesEcho);
                check(java.util.Arrays.equals(buffer.data, new byte[] {1, 2, 3}),
                    "the bytes roundtrip leaves the buffer contents intact");
                Object applied = App.__host$$PROBE$$apply(new $DealRt.Fn1_I_R_I() {
                    int invoke(int p0) { return p0 + 1; }
                  }, Integer.valueOf(7), "probe.deal", 5, 1);
                check(Integer.valueOf(108).equals(applied),
                    "the function carrier reaches the host bridge: " + applied);

                // The pinned E8010 parameter cells.
                expect("E8010", "parameter 1 type mismatch: expected bytes",
                    () -> App.__host$$PROBE$$echoBytes(Double.valueOf(1.0),
                        "probe.deal", 6, 1));
                expect("E8010", "parameter 2 type mismatch: expected array",
                    () -> App.__host$$PROBE$$join("x", Double.valueOf(1.0),
                        "probe.deal", 7, 1));
                expect("E8010", "parameter 1 type mismatch: expected function",
                    () -> App.__host$$PROBE$$apply(Double.valueOf(1.0),
                        Integer.valueOf(1), "probe.deal", 8, 1));

                // The pinned E8010 return cell (the host returns junk for int).
                expect("E8010", "return value 1 type mismatch: expected int",
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

        // The declared-class seeds: a host class without its mandatory
        // <C>_defaults field and a host class whose <C>_defaults is not a
        // map both render the closed HOST_LOAD_INVALID_METADATA arm — the
        // authority's message at the import statement's origin, no
        // expected/actual fields (ISSUE-0769).
        String cfgOrigin = importOrigin(cfgFixture, CFG_SPECIFIER);
        checkJvmLoadFailure(cfgFixture, "HostCfg.java", """
            final class HostCfg {
              public static final java.util.Map<String, Object> Endpoint_defaults =
                  java.util.Map.of("path", "/");
              public static Object describe(Object s) { return "cfg"; }
            }
            """, "E8011",
            invalidMetadata("ServerConfig", CFG_SPECIFIER, "defaults").message(),
            cfgOrigin, "missing-defaults");
        checkJvmLoadFailure(cfgFixture, "HostCfg.java", """
            final class HostCfg {
              public static final java.util.Map<String, Object> Endpoint_defaults =
                  java.util.Map.of("path", "/");
              public static final Object ServerConfig_defaults = 42;
              public static Object describe(Object s) { return "cfg"; }
            }
            """, "E8011",
            invalidMetadata("ServerConfig", CFG_SPECIFIER, "defaults").message(),
            cfgOrigin, "wrong-kind-defaults");
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
            ExecutableLoweredProject project = result.project();
            String className = JvmBackend.classNameFor(project.entryModule().path());
            JvmSemanticEmitter.EmissionResult emission = fixture.jvm();
            Path source = workspace.resolve(className + ".java");
            Files.writeString(source, emission.source(), StandardCharsets.UTF_8);
            List<String> compiled = new ArrayList<>();
            compiled.add(source.toAbsolutePath().toString());
            if (hostFileName != null) {
                Files.writeString(workspace.resolve(hostFileName), hostSource,
                    StandardCharsets.UTF_8);
                compiled.add(hostFileName);
            }
            Files.writeString(workspace.resolve("HostLoadFailureProbe.java"),
                jvmFailureProbe(expectedCode, expectedMessage, expectedOrigin),
                StandardCharsets.UTF_8);
            runJvmProbe(workspace, compiled, "HostLoadFailureProbe", label);
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

    private static void runJvmProbe(Path workspace, List<String> sources,
            String template, String label) throws Exception {
        String driver = template + "_" + java.util.UUID.randomUUID().toString().replace("-", "");
        Path source = workspace.resolve(template + ".java");
        Files.writeString(workspace.resolve(driver + ".java"),
            Files.readString(source).replace(template, driver), StandardCharsets.UTF_8);
        sources.add(driver + ".java");
        Path classes = Files.createDirectories(workspace.resolve("classes"));
        List<String> command = new ArrayList<>(List.of("javac", "--release", "25",
            "-proc:none", "-cp", absoluteClasspath(), "-d", classes.toString()));
        command.addAll(sources);
        ProcessOutcome compiled = runProcess(command, workspace, false);
        check(compiled.exitCode() == 0, label + ": real javac: " + compiled.output());
        if (compiled.exitCode() == 0) {
            ProcessOutcome run = runProcess(List.of("java", "-cp",
                classes + java.io.File.pathSeparator + absoluteClasspath(), driver), workspace, false);
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
            testLuaArtifactText(abi, aliases);
            testHostReadCorrelation(hostReads);
            testLuaHostDecodedNumericCarrier();
            testLuaHostTableValue();
            testLuaHostTableProjection();
            testLuaExecution(aliases, missing, cfg, tableParam);
            testLuaErrorCrossing(errors);
            testJvmArtifactText(abi);
            testJvmToolchain(abi);
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
