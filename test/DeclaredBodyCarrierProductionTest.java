package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.jvm.JvmNames;
import deal.codegen.jvm.JvmRuntime;
import deal.diagnostics.CompilerDiagnostic;
import deal.module.CompilationOrchestrator;
import deal.project.CliOverrides;
import deal.project.ProjectLocator;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ControlFlowValidator;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.AsyncLinkKind;
import deal.semantic.ir.AsyncStartSource;
import deal.semantic.ir.AsyncTokenId;
import deal.semantic.ir.AsyncTokenOwner;
import deal.semantic.ir.BindingGeneration;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.CallMode;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.ExternalAsyncLink;
import deal.semantic.ir.ExternalExecutionOwner;
import deal.semantic.ir.FunctionAllocationIdentity;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredFunction;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.ParameterBoundaryMode;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticIrDumper;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;
import deal.test.conformance.JvmCompilation;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

public class DeclaredBodyCarrierProductionTest {
    private static int passed;
    private static int failed;
    private static final String RESULT_MARKER = "CARRIER_RESULT:";
    private static final String RETAINED_MARKER = "RETAINED:";
    private static final Set<String> RECURSION_CASES = Set.of(
        "direct-accum", "alias-accum", "direct-accum-fail", "alias-accum-fail", "seq-await");
    private static final String DEAL_JSON =
        "{\"languageVersion\":\"1.3\",\"moduleRoots\":[\"src\"],"
            + "\"output\":\"out\",\"backend\":\"luajit\"}\n";

    private record FailurePin(String code, String anchor) {}
    private record GroupPin(List<Boolean> memberCalled, boolean implicitReturns,
                            boolean aliasInvocation) {}
    private record Case(String name, String source, List<String> effects,
                        String asyncExport, String resultAtom, boolean recursive,
                        int minCarrierInvocations, int minEntryInvocations,
                        Map<String, String> extraSources, boolean arityExtensionAdapter,
                        GroupPin group, Boolean selfCaptures, FailurePin failure,
                        boolean stagedSync) {
        Case {
            effects = List.copyOf(effects);
            extraSources = Map.copyOf(extraSources);
        }
    }

    private static Case body(String name, String source, List<String> effects,
                             String export, String result, int carriers, int entries,
                             boolean recursive, boolean adapter,
                             Map<String, String> companions) {
        return new Case(name, source, effects, export, result, recursive, carriers,
            entries, companions, adapter, null, null, null, export == null);
    }

    private static Case body(String name, String source, List<String> effects,
                             String export, String result, int carriers, int entries,
                             boolean recursive, boolean adapter) {
        return body(name, source, effects, export, result, carriers, entries,
            recursive, adapter, Map.of());
    }

    private static Case group(String name, String source, List<String> effects,
                              boolean recursive, List<Boolean> called, boolean implicit,
                              boolean alias, FailurePin failure, String export,
                              String result, boolean stagedSync) {
        return new Case(name, source, effects, export, result, recursive, 0, 0,
            Map.of(), false, new GroupPin(called, implicit, alias), null, failure,
            stagedSync);
    }

    private static Case group(String name, String source, List<String> effects,
                              boolean recursive, List<Boolean> called, boolean implicit,
                              boolean alias) {
        return group(name, source, effects, recursive, called, implicit, alias,
            null, null, "null", false);
    }

    private static Case self(String name, String source, List<String> effects,
                             String export, String result, boolean captures) {
        return new Case(name, source, effects, export, result, true, 0, 0,
            Map.of(), false, null, captures, null, export == null);
    }

    private static final List<Case> CASES = List.of(
        body("acyclic-sibling", """
            import * as console from "std/console"
            export function main(): null {
              let c: () => int = make(10);
              let v: int = c();
              if (v === 10) {
                console.log("declared-body-sibling-ok");
              } else {
                console.log("declared-body-sibling-bad");
              }
              return null;
            }
            function make(base: int): () => int {
              function target(): int { return base; }
              function caller(): int { return target(); }
              return caller;
            }
            """, List.of("declared-body-sibling-ok"), null, "null", 1, 0, false, false),
        body("two-escaped-instances", """
            import * as console from "std/console"
            export function main(): null {
              let a: () => int = make(10);
              let b: () => int = make(20);
              let av: int = a();
              if (av === 10) {
                console.log("declared-body-instance-a-ok");
              } else {
                console.log("declared-body-instance-a-bad");
              }
              let bv: int = b();
              if (bv === 20) {
                console.log("declared-body-instance-b-ok");
              } else {
                console.log("declared-body-instance-b-bad");
              }
              return null;
            }
            function make(base: int): () => int {
              function target(): int { return base; }
              function caller(): int { return target(); }
              return caller;
            }
            """, List.of("declared-body-instance-a-ok", "declared-body-instance-b-ok"),
            null, "null", 1, 0, false, false),
        body("acyclic-sibling-async", """
            import * as console from "std/console"
            export async function drive(): int {
              let c: async () => int = make(10);
              let v: int = await c();
              if (v === 10) {
                console.log("declared-body-sibling-async-ok");
              } else {
                console.log("declared-body-sibling-async-bad");
              }
              return v;
            }
            export function main(): null { return null; }
            function make(base: int): async () => int {
              async function target(): int { return base; }
              async function caller(): int { return await target(); }
              return caller;
            }
            """, List.of("declared-body-sibling-async-ok"), "drive", "int:10",
            1, 0, false, false),
        body("two-escaped-instances-async", """
            import * as console from "std/console"
            export async function drive(): int {
              let a: async () => int = make(10);
              let b: async () => int = make(20);
              let av: int = await a();
              if (av === 10) {
                console.log("declared-body-async-instance-a-ok");
              } else {
                console.log("declared-body-async-instance-a-bad");
              }
              let bv: int = await b();
              if (bv === 20) {
                console.log("declared-body-async-instance-b-ok");
              } else {
                console.log("declared-body-async-instance-b-bad");
              }
              return av + bv;
            }
            export function main(): null { return null; }
            function make(base: int): async () => int {
              async function target(): int { return base; }
              async function caller(): int { return await target(); }
              return caller;
            }
            """, List.of("declared-body-async-instance-a-ok",
                "declared-body-async-instance-b-ok"), "drive", "int:30", 1, 0, false, false),
        body("forward-alias", """
            import * as console from "std/console"
            export function main(): null {
              let v: int = g();
              if (v === 5) {
                console.log("declared-body-forward-alias-ok");
              } else {
                console.log("declared-body-forward-alias-bad");
              }
              return null;
            }
            function g(): int {
              let alias: () => int = f;
              return alias();
            }
            function f(): int { return helper(); }
            function helper(): int { return 5; }
            """, List.of("declared-body-forward-alias-ok"), null, "null", 0, 0, false, false),
        body("forward-alias-async", """
            import * as console from "std/console"
            export async function drive(): int {
              let v: int = await g();
              if (v === 5) {
                console.log("declared-body-forward-alias-async-ok");
              } else {
                console.log("declared-body-forward-alias-async-bad");
              }
              return v;
            }
            export function main(): null { return null; }
            async function g(): int {
              let alias: async () => int = f;
              return await alias();
            }
            async function f(): int { return await helper(); }
            async function helper(): int { return 5; }
            """, List.of("declared-body-forward-alias-async-ok"), "drive", "int:5",
            0, 0, false, false),
        body("forward-alias-exported", """
            import * as console from "std/console"
            export function main(): null {
              let v: int = g();
              if (v === 5) {
                console.log("declared-body-forward-alias-exported-ok");
              } else {
                console.log("declared-body-forward-alias-exported-bad");
              }
              return null;
            }
            function g(): int {
              let alias: (n: int) => int = f;
              return alias(5);
            }
            export function f(n: int): int { return n; }
            """, List.of("declared-body-forward-alias-exported-ok"), null, "null",
            0, 1, false, false),
        body("forward-alias-exported-async", """
            import * as console from "std/console"
            export async function drive(): int {
              let v: int = await g();
              if (v === 5) {
                console.log("declared-body-forward-alias-exported-async-ok");
              } else {
                console.log("declared-body-forward-alias-exported-async-bad");
              }
              return v;
            }
            export function main(): null { return null; }
            async function g(): int {
              let alias: async () => int = f;
              return await alias();
            }
            async function helper(): int { return 5; }
            export async function f(): int { return await helper(); }
            """, List.of("declared-body-forward-alias-exported-async-ok"), "drive", "int:5",
            0, 1, false, false),
        body("forward-alias-exported-reverse", """
            import * as console from "std/console"
            export function f(n: int): int { return n; }
            export function main(): null {
              let v: int = g();
              if (v === 5) {
                console.log("declared-body-forward-alias-exported-reverse-ok");
              } else {
                console.log("declared-body-forward-alias-exported-reverse-bad");
              }
              return null;
            }
            function g(): int {
              let alias: (n: int) => int = f;
              return alias(5);
            }
            """, List.of("declared-body-forward-alias-exported-reverse-ok"), null, "null",
            0, 1, false, false),
        body("forward-alias-exported-async-reverse", """
            import * as console from "std/console"
            export async function f(): int { return await helper(); }
            async function helper(): int { return 5; }
            async function g(): int {
              let alias: async () => int = f;
              return await alias();
            }
            export function main(): null { return null; }
            export async function drive(): int {
              let v: int = await g();
              if (v === 5) {
                console.log("declared-body-forward-alias-exported-async-reverse-ok");
              } else {
                console.log("declared-body-forward-alias-exported-async-reverse-bad");
              }
              return v;
            }
            """, List.of("declared-body-forward-alias-exported-async-reverse-ok"),
            "drive", "int:5", 0, 1, false, false),
        body("exported-direct-await-async", """
            import * as console from "std/console"
            export async function drive(): int {
              let v: int = await f();
              if (v === 5) {
                console.log("declared-body-exported-direct-await-ok");
              } else {
                console.log("declared-body-exported-direct-await-bad");
              }
              return v;
            }
            export function main(): null { return null; }
            export async function f(): int { return 5; }
            """, List.of("declared-body-exported-direct-await-ok"), "drive", "int:5",
            0, 1, false, false),
        body("forward-alias-exported-async-recursive", """
            import * as console from "std/console"
            export async function drive(): int {
              let alias: async (n: int) => int = f;
              let v: int = await alias(1);
              if (v === 1) {
                console.log("declared-body-recursive-alias-ok");
              } else {
                console.log("declared-body-recursive-alias-bad");
              }
              return v;
            }
            export function main(): null { return null; }
            export async function f(n: int): int {
              let result: int = 0;
              if (n > 0) {
                let nested: int = await f(n - 1);
                result = nested + 1;
              }
              return result;
            }
            """, List.of("declared-body-recursive-alias-ok"), "drive", "int:1",
            0, 2, true, false),
        body("exported-direct-await-async-recursive", """
            import * as console from "std/console"
            export async function drive(): int {
              let v: int = await f(1);
              if (v === 1) {
                console.log("declared-body-direct-recursive-ok");
              } else {
                console.log("declared-body-direct-recursive-bad");
              }
              return v;
            }
            export function main(): null { return null; }
            export async function f(n: int): int {
              let result: int = 0;
              if (n > 0) {
                let nested: int = await f(n - 1);
                result = nested + 1;
              }
              return result;
            }
            """, List.of("declared-body-direct-recursive-ok"), "drive", "int:1",
            0, 2, true, false),
        body("exported-recursive-enclosing-state", """
            import * as console from "std/console"
            export async function drive(): int {
              let v: int = await f(3);
              if (v === 12) {
                console.log("declared-body-enclosing-state-ok");
              } else {
                console.log("declared-body-enclosing-state-bad");
              }
              return v;
            }
            export function main(): null { return null; }
            export async function f(n: int): int {
              let step: int = n;
              if (n <= 0) { return 0; }
              let nested: int = await f(n - 1);
              return nested + n + step;
            }
            """, List.of("declared-body-enclosing-state-ok"), "drive", "int:12",
            0, 2, true, false),
        body("forward-alias-exported-async-deep", """
            import * as console from "std/console"
            export async function drive(): int {
              let alias: async (n: int) => int = f;
              let v: int = await alias(3);
              if (v === 3) {
                console.log("declared-body-recursive-deep-ok");
              } else {
                console.log("declared-body-recursive-deep-bad");
              }
              return v;
            }
            export function main(): null { return null; }
            export async function f(n: int): int {
              let result: int = 0;
              if (n > 0) {
                let nested: int = await f(n - 1);
                result = nested + 1;
              }
              return result;
            }
            """, List.of("declared-body-recursive-deep-ok"), "drive", "int:3",
            0, 2, true, false),
        body("module-group-forward-alias", """
            import * as console from "std/console"
            export function main(): null {
              if (f(1) === 42) {
                console.log("declared-body-module-group-forward-alias-ok");
              } else {
                console.log("declared-body-module-group-forward-alias-bad");
              }
              return null;
            }
            function f(n: int): int {
              let alias: (n: int) => int = h;
              if (n <= 0) { return alias(0); }
              return g(n - 1);
            }
            function g(n: int): int { return f(n); }
            function h(n: int): int {
              if (n <= 0) { return 42; }
              return j(n - 1);
            }
            function j(n: int): int { return h(n); }
            """, List.of("declared-body-module-group-forward-alias-ok"), null, "null",
            1, 0, true, false),
        body("module-group-forward-carrier-alias", """
            import * as console from "std/console"
            export function main(): null {
              let drive: (n: int) => int = f;
              if (drive(1) === 42) {
                console.log("declared-body-module-group-forward-carrier-alias-ok");
              } else {
                console.log("declared-body-module-group-forward-carrier-alias-bad");
              }
              return null;
            }
            function f(n: int): int {
              let alias: (n: int) => int = h;
              if (n <= 0) { return alias(0); }
              return g(n - 1);
            }
            function g(n: int): int { return f(n); }
            function h(n: int): int {
              if (n <= 0) { return 42; }
              return j(n - 1);
            }
            function j(n: int): int { return h(n); }
            """, List.of("declared-body-module-group-forward-carrier-alias-ok"),
            null, "null", 2, 0, true, false),
        body("module-group-reverse-alias", """
            import * as console from "std/console"
            export function main(): null {
              if (f(1) === 42) {
                console.log("declared-body-module-group-reverse-alias-ok");
              } else {
                console.log("declared-body-module-group-reverse-alias-bad");
              }
              return null;
            }
            function h(n: int): int {
              if (n <= 0) { return 42; }
              return j(n - 1);
            }
            function j(n: int): int { return h(n); }
            function f(n: int): int {
              let alias: (n: int) => int = h;
              if (n <= 0) { return alias(0); }
              return g(n - 1);
            }
            function g(n: int): int { return f(n); }
            """, List.of("declared-body-module-group-reverse-alias-ok"), null, "null",
            1, 0, true, false),
        body("module-group-forward-alias-async", """
            import * as console from "std/console"
            export async function drive(): int {
              let v: int = await f(1);
              if (v === 42) {
                console.log("declared-body-module-group-forward-alias-async-ok");
              } else {
                console.log("declared-body-module-group-forward-alias-async-bad");
              }
              return v;
            }
            export function main(): null { return null; }
            async function f(n: int): int {
              let alias: async (n: int) => int = h;
              if (n <= 0) { return await alias(0); }
              return await g(n - 1);
            }
            async function g(n: int): int { return await f(n); }
            async function h(n: int): int {
              if (n <= 0) { return 42; }
              return await j(n - 1);
            }
            async function j(n: int): int { return await h(n); }
            """, List.of("declared-body-module-group-forward-alias-async-ok"),
            "drive", "int:42", 1, 0, true, false),
        body("module-group-reverse-alias-async", """
            import * as console from "std/console"
            export async function drive(): int {
              let v: int = await f(1);
              if (v === 42) {
                console.log("declared-body-module-group-reverse-alias-async-ok");
              } else {
                console.log("declared-body-module-group-reverse-alias-async-bad");
              }
              return v;
            }
            export function main(): null { return null; }
            async function h(n: int): int {
              if (n <= 0) { return 42; }
              return await j(n - 1);
            }
            async function j(n: int): int { return await h(n); }
            async function f(n: int): int {
              let alias: async (n: int) => int = h;
              if (n <= 0) { return await alias(0); }
              return await g(n - 1);
            }
            async function g(n: int): int { return await f(n); }
            """, List.of("declared-body-module-group-reverse-alias-async-ok"),
            "drive", "int:42", 1, 0, true, false),
        body("forward-alias-exported-adapter", """
            import * as console from "std/console"
            export function main(): null {
              let alias: (n: int, extra: int) => int = f;
              if (alias(1, 7) === 10) {
                console.log("declared-body-forward-alias-exported-adapter-ok");
              } else {
                console.log("declared-body-forward-alias-exported-adapter-bad");
              }
              return null;
            }
            export function f(n: int): int { return n * 10; }
            """, List.of("declared-body-forward-alias-exported-adapter-ok"),
            null, "null", 0, 0, false, true),
        body("forward-alias-exported-adapter-reverse", """
            import * as console from "std/console"
            export function f(n: int): int { return n * 10; }
            export function main(): null {
              let alias: (n: int, extra: int) => int = f;
              if (alias(1, 7) === 10) {
                console.log("declared-body-forward-alias-exported-adapter-reverse-ok");
              } else {
                console.log("declared-body-forward-alias-exported-adapter-reverse-bad");
              }
              return null;
            }
            """, List.of("declared-body-forward-alias-exported-adapter-reverse-ok"),
            null, "null", 0, 0, false, true),
        body("forward-alias-exported-adapter-async", """
            import * as console from "std/console"
            export async function drive(): int {
              let alias: async (n: int, extra: int) => int = f;
              let v: int = await alias(1, 7);
              if (v === 10) {
                console.log("declared-body-forward-alias-exported-adapter-async-ok");
              } else {
                console.log("declared-body-forward-alias-exported-adapter-async-bad");
              }
              return v;
            }
            export function main(): null { return null; }
            export async function f(n: int): int { return n * 10; }
            """, List.of("declared-body-forward-alias-exported-adapter-async-ok"),
            "drive", "int:10", 0, 1, false, true),
        body("forward-alias-exported-adapter-async-reverse", """
            import * as console from "std/console"
            export async function f(n: int): int { return n * 10; }
            export async function drive(): int {
              let alias: async (n: int, extra: int) => int = f;
              let v: int = await alias(1, 7);
              if (v === 10) {
                console.log("declared-body-forward-alias-exported-adapter-async-reverse-ok");
              } else {
                console.log("declared-body-forward-alias-exported-adapter-async-reverse-bad");
              }
              return v;
            }
            export function main(): null { return null; }
            """, List.of("declared-body-forward-alias-exported-adapter-async-reverse-ok"),
            "drive", "int:10", 0, 1, false, true),
        body("adapter-over-async-external", """
            import * as console from "std/console"
            import * as Lib from "./adapter_lib"
            export async function drive(): int {
              let src: async (x: int) => int = Lib.plus;
              let f: async (a: int, b: int) => int = src;
              let v: int = await f(1, 2);
              if (v === 2) {
                console.log("declared-body-adapter-external-ok");
              } else {
                console.log("declared-body-adapter-external-bad");
              }
              return v;
            }
            export function main(): null { return null; }
            """, List.of("declared-body-adapter-external-ok"), "drive", "int:2",
            0, 0, false, false, Map.of("adapter_lib.deal", """
                export async function plus(x: int): int { return x + 1; }
                """)),
        body("escaped-nested-source-adapter-async", """
            import * as console from "std/console"
            export async function drive(): int {
              let c: async () => int = make(10);
              let v: int = await c();
              if (v === 10) {
                console.log("declared-body-nested-source-adapter-ok");
              } else {
                console.log("declared-body-nested-source-adapter-bad");
              }
              return v;
            }
            export function main(): null { return null; }
            function make(base: int): async () => int {
              async function target(): int { return base; }
              async function caller(): int {
                let wide: async (extra: int) => int = target;
                return await wide(0);
              }
              return caller;
            }
            """, List.of("declared-body-nested-source-adapter-ok"), "drive", "int:10",
            1, 0, false, false),
        body("two-escaped-nested-source-adapters-async", """
            import * as console from "std/console"
            export async function drive(): int {
              let a: async () => int = make(10);
              let b: async () => int = make(20);
              let av: int = await a();
              if (av === 10) {
                console.log("declared-body-nested-adapter-instance-a-ok");
              } else {
                console.log("declared-body-nested-adapter-instance-a-bad");
              }
              let bv: int = await b();
              if (bv === 20) {
                console.log("declared-body-nested-adapter-instance-b-ok");
              } else {
                console.log("declared-body-nested-adapter-instance-b-bad");
              }
              return av + bv;
            }
            export function main(): null { return null; }
            function make(base: int): async () => int {
              async function target(): int { return base; }
              async function caller(): int {
                let wide: async (extra: int) => int = target;
                return await wide(0);
              }
              return caller;
            }
            """, List.of("declared-body-nested-adapter-instance-a-ok",
                "declared-body-nested-adapter-instance-b-ok"), "drive", "int:30",
            1, 0, false, false),
        group("called-explicit", """
            import * as console from "std/console"
            export function main(): null {
              function f(n: int): int {
                if (n <= 0) { return 0; }
                return n + g(n - 1);
              }
              function g(n: int): int {
                if (n <= 0) { return 0; }
                return n + f(n - 1);
              }
              if (f(4) === 10) {
                console.log("nested-group-called-explicit-ok");
              } else {
                console.log("nested-group-called-explicit-bad");
              }
              return null;
            }
            """, List.of("nested-group-called-explicit-ok"), true,
            List.of(true, true), false, false),
        group("called-implicit", """
            import * as console from "std/console"
            export function main(): null {
              function f(n: int): null {
                if (n > 0) { g(n - 1); }
              }
              function g(n: int): null {
                if (n > 1) { f(n - 1); }
              }
              f(4);
              console.log("nested-group-called-implicit-ok");
              return null;
            }
            """, List.of("nested-group-called-implicit-ok"), true,
            List.of(true, true), true, false),
        group("called-value-reference", """
            import * as console from "std/console"
            export function main(): null {
              function f(n: int): int {
                if (n <= 0) { return 0; }
                return n + g(n - 1);
              }
              function g(n: int): int {
                let fRef: (n: int) => int = f;
                return n;
              }
              if (f(4) === 7) {
                console.log("nested-group-called-value-reference-ok");
              } else {
                console.log("nested-group-called-value-reference-bad");
              }
              return null;
            }
            """, List.of("nested-group-called-value-reference-ok"), false,
            List.of(true, true), false, false),
        group("called-alias", """
            import * as console from "std/console"
            export function main(): null {
              function f(n: int): int {
                if (n <= 0) { return 0; }
                return n + g(n - 1);
              }
              function g(n: int): int {
                let fRef: (n: int) => int = f;
                return fRef(n);
              }
              if (f(4) === 10) {
                console.log("nested-group-alias-ok");
              } else {
                console.log("nested-group-alias-bad");
              }
              return null;
            }
            """, List.of("nested-group-alias-ok"), true,
            List.of(true, true), false, true),
        group("called-alias-captured", """
            import * as console from "std/console"
            export function main(): null {
              let base: int = 10;
              function f(n: int): int {
                if (n <= 0) { return base; }
                return g(n - 1);
              }
              function g(n: int): int {
                let fRef: (n: int) => int = f;
                return fRef(n);
              }
              if (f(3) === 10) {
                console.log("nested-group-alias-captured-ok");
              } else {
                console.log("nested-group-alias-captured-bad");
              }
              return null;
            }
            """, List.of("nested-group-alias-captured-ok"), true,
            List.of(true, true), false, true),
        group("called-alias-later", """
            import * as console from "std/console"
            export function main(): null {
              function g(n: int): int {
                let fRef: (n: int) => int = f;
                return fRef(n);
              }
              function f(n: int): int {
                if (n <= 0) { return 0; }
                return n + g(n - 1);
              }
              if (f(4) === 10) {
                console.log("nested-group-alias-later-ok");
              } else {
                console.log("nested-group-alias-later-bad");
              }
              return null;
            }
            """, List.of("nested-group-alias-later-ok"), true,
            List.of(true, true), false, true),
        group("module-group-alias", """
            import * as console from "std/console"
            function f(n: int): int {
              if (n <= 0) { return 0; }
              return n + g(n - 1);
            }
            function g(n: int): int {
              let fRef: (n: int) => int = f;
              return fRef(n);
            }
            export function main(): null {
              if (f(4) === 10) {
                console.log("module-group-alias-ok");
              } else {
                console.log("module-group-alias-bad");
              }
              return null;
            }
            """, List.of("module-group-alias-ok"), true,
            List.of(true, true), false, true),
        group("never-called-explicit-null", """
            export function main(): null {
              function f(): null {
                let gRef: () => null = g;
                return null;
              }
              function g(): null {
                let fRef: () => null = f;
                return null;
              }
              return null;
            }
            """, List.of(), false, List.of(false, false), false, false),
        group("never-called-explicit-int", """
            export function main(): null {
              function f(): int {
                let gRef: () => int = g;
                return 0;
              }
              function g(): int {
                let fRef: () => int = f;
                return 1;
              }
              return null;
            }
            """, List.of(), false, List.of(false, false), false, false),
        group("never-called-implicit", """
            export function main(): null {
              function f(): null { let gRef: () => null = g; }
              function g(): null { let fRef: () => null = f; }
              return null;
            }
            """, List.of(), false, List.of(false, false), true, false),
        group("captured-called", """
            import * as console from "std/console"
            export function main(): null {
              let base: int = 10;
              function f(n: int): int {
                if (n <= 0) { return base; }
                return g(n - 1);
              }
              function g(n: int): int { return f(n); }
              if (f(3) === 10) {
                console.log("nested-group-captured-ok");
              } else {
                console.log("nested-group-captured-bad");
              }
              return null;
            }
            """, List.of("nested-group-captured-ok"), true,
            List.of(true, true), false, false),
        group("nested-inside-function", """
            import * as console from "std/console"
            export function main(): null {
              function outer(): int {
                function f(n: int): int {
                  if (n <= 0) { return 0; }
                  return g(n - 1);
                }
                function g(n: int): int { return f(n); }
                return f(2);
              }
              if (outer() === 0) {
                console.log("nested-group-inside-function-ok");
              } else {
                console.log("nested-group-inside-function-bad");
              }
              return null;
            }
            """, List.of("nested-group-inside-function-ok"), true,
            List.of(true, true), false, false),
        group("escaped-two-groups", """
            import * as console from "std/console"
            export function main(): null {
              let a: (n: int) => int = make(10);
              let b: (n: int) => int = make(20);
              if (a(3) === 10) {
                console.log("escaped-group-a-ok");
              } else {
                console.log("escaped-group-a-bad");
              }
              if (b(3) === 20) {
                console.log("escaped-group-b-ok");
              } else {
                console.log("escaped-group-b-bad");
              }
              return null;
            }
            function make(base: int): (n: int) => int {
              function f(n: int): int {
                if (n <= 0) { return base; }
                return g(n - 1);
              }
              function g(n: int): int { return f(n); }
              return f;
            }
            """, List.of("escaped-group-a-ok", "escaped-group-b-ok"), true,
            List.of(true, true), false, false),
        group("escaped-two-groups-recursion", """
            import * as console from "std/console"
            export function main(): null {
              let a: (n: int) => int = make(10);
              let b: (n: int) => int = make(20);
              if (a(3) === 17) {
                console.log("escaped-group-recursion-a-ok");
              } else {
                console.log("escaped-group-recursion-a-bad");
              }
              if (b(3) === 27) {
                console.log("escaped-group-recursion-b-ok");
              } else {
                console.log("escaped-group-recursion-b-bad");
              }
              return null;
            }
            function make(base: int): (n: int) => int {
              function f(n: int): int {
                if (n <= 0) { return base; }
                let m: int = n + 1;
                g(n - 1);
                return base + n + m;
              }
              function g(n: int): int { return f(n); }
              return f;
            }
            """, List.of("escaped-group-recursion-a-ok", "escaped-group-recursion-b-ok"),
            true, List.of(true, true), false, false, null, null, "null", true),
        group("escaped-two-groups-alias-recursion", """
            import * as console from "std/console"
            export function main(): null {
              let a: (n: int) => int = make(10);
              let b: (n: int) => int = make(20);
              if (a(3) === 17) {
                console.log("escaped-group-alias-recursion-a-ok");
              } else {
                console.log("escaped-group-alias-recursion-a-bad");
              }
              if (b(3) === 27) {
                console.log("escaped-group-alias-recursion-b-ok");
              } else {
                console.log("escaped-group-alias-recursion-b-bad");
              }
              return null;
            }
            function make(base: int): (n: int) => int {
              function f(n: int): int {
                if (n <= 0) { return base; }
                let m: int = n + 1;
                let cell: () => int = function(): int { return n + m; };
                let sibling: (n: int) => int = g;
                sibling(n - 1);
                return base + n + m;
              }
              function g(n: int): int { return f(n); }
              return f;
            }
            """, List.of("escaped-group-alias-recursion-a-ok",
                "escaped-group-alias-recursion-b-ok"), true, List.of(true, true),
            false, true, null, null, "null", true),
        group("param-origin-contextual", """
            import * as console from "std/console"
            export function main(): null {
              function f(n: int): int {
                let gRef: (n: int) => int = g;
                return n;
              }
              function g(n: int): int {
                let fRef: (n: int) => int = f;
                return n;
              }
              let t: table = { x: "wrong" };
              f(t.x);
              return null;
            }
            """, List.of(), false, List.of(true, false), false, false,
            new FailurePin("E8001", "function f(n: "), null, "null", false),
        group("async-escaped-two-groups", """
            import * as console from "std/console"
            export async function drive(): int {
              let a: async (n: int) => int = make(10);
              let b: async (n: int) => int = make(20);
              let av: int = await a(3);
              if (av === 10) {
                console.log("async-escaped-group-a-ok");
              } else {
                console.log("async-escaped-group-a-bad");
              }
              let bv: int = await b(3);
              if (bv === 20) {
                console.log("async-escaped-group-b-ok");
              } else {
                console.log("async-escaped-group-b-bad");
              }
              return av + bv;
            }
            function make(base: int): async (n: int) => int {
              async function f(n: int): int {
                if (n <= 0) { return base; }
                return await g(n - 1);
              }
              async function g(n: int): int { return await f(n); }
              return f;
            }
            export function main(): null { return null; }
            """, List.of("async-escaped-group-a-ok", "async-escaped-group-b-ok"),
            true, List.of(true, true), false, false, null, "drive", "int:30", false),
        group("async-param-origin", """
            export async function drive(): int {
              let bad: table = { x: "wrong" };
              let a: async (n: int) => int = make(10, bad);
              return await a(3);
            }
            function make(base: int, bad: table): async (n: int) => int {
              async function f(n: int): int {
                if (n <= 0) { return base; }
                return await g(bad.x);
              }
              async function g(n: int): int { return await f(n); }
              return f;
            }
            export function main(): null { return null; }
            """, List.of(), true, List.of(true, true), false, false,
            new FailurePin("E8001", "async function g(n: "), "drive", "null", false),
        group("async-escaped-two-groups-alias", """
            import * as console from "std/console"
            export async function drive(): int {
              let a: async (n: int) => int = make(10);
              let b: async (n: int) => int = make(20);
              let av: int = await a(3);
              if (av === 10) {
                console.log("async-escaped-group-alias-a-ok");
              } else {
                console.log("async-escaped-group-alias-a-bad");
              }
              let bv: int = await b(3);
              if (bv === 20) {
                console.log("async-escaped-group-alias-b-ok");
              } else {
                console.log("async-escaped-group-alias-b-bad");
              }
              return av + bv;
            }
            function make(base: int): async (n: int) => int {
              async function f(n: int): int {
                if (n <= 0) { return base; }
                let sibling: async (n: int) => int = g;
                return await sibling(n - 1);
              }
              async function g(n: int): int { return await f(n); }
              return f;
            }
            export function main(): null { return null; }
            """, List.of("async-escaped-group-alias-a-ok", "async-escaped-group-alias-b-ok"),
            true, List.of(true, true), false, false, null, "drive", "int:30", false),
        group("async-escaped-two-groups-alias-later", """
            import * as console from "std/console"
            export async function drive(): int {
              let a: async (n: int) => int = make(10);
              let b: async (n: int) => int = make(20);
              let av: int = await a(3);
              if (av === 10) {
                console.log("async-escaped-group-alias-later-a-ok");
              } else {
                console.log("async-escaped-group-alias-later-a-bad");
              }
              let bv: int = await b(3);
              if (bv === 20) {
                console.log("async-escaped-group-alias-later-b-ok");
              } else {
                console.log("async-escaped-group-alias-later-b-bad");
              }
              return av + bv;
            }
            function make(base: int): async (n: int) => int {
              async function g(n: int): int { return await f(n); }
              async function f(n: int): int {
                if (n <= 0) { return base; }
                let sibling: async (n: int) => int = g;
                return await sibling(n - 1);
              }
              return f;
            }
            export function main(): null { return null; }
            """, List.of("async-escaped-group-alias-later-a-ok",
                "async-escaped-group-alias-later-b-ok"), true, List.of(true, true),
            false, false, null, "drive", "int:30", false),
        group("async-escaped-two-groups-param", """
            import * as console from "std/console"
            export async function drive(): int {
              let a: async (n: int) => int = make(10);
              let b: async (n: int) => int = make(20);
              let av: int = await a(3);
              if (av === 13) {
                console.log("async-escaped-group-param-a-ok");
              } else {
                console.log("async-escaped-group-param-a-bad");
              }
              let bv: int = await b(3);
              if (bv === 23) {
                console.log("async-escaped-group-param-b-ok");
              } else {
                console.log("async-escaped-group-param-b-bad");
              }
              return av + bv;
            }
            function make(base: int): async (n: int) => int {
              async function f(n: int): int {
                if (n <= 0) { return base; }
                await g(n - 1);
                return base + n;
              }
              async function g(n: int): int { return await f(n); }
              return f;
            }
            export function main(): null { return null; }
            """, List.of("async-escaped-group-param-a-ok", "async-escaped-group-param-b-ok"),
            true, List.of(true, true), false, false, null, "drive", "int:36", false),
        group("async-escaped-two-groups-alias-param", """
            import * as console from "std/console"
            export async function drive(): int {
              let a: async (n: int) => int = make(10);
              let b: async (n: int) => int = make(20);
              let av: int = await a(3);
              if (av === 13) {
                console.log("async-escaped-group-alias-param-a-ok");
              } else {
                console.log("async-escaped-group-alias-param-a-bad");
              }
              let bv: int = await b(3);
              if (bv === 23) {
                console.log("async-escaped-group-alias-param-b-ok");
              } else {
                console.log("async-escaped-group-alias-param-b-bad");
              }
              return av + bv;
            }
            function make(base: int): async (n: int) => int {
              async function f(n: int): int {
                if (n <= 0) { return base; }
                let sibling: async (n: int) => int = g;
                await sibling(n - 1);
                return base + n;
              }
              async function g(n: int): int { return await f(n); }
              return f;
            }
            export function main(): null { return null; }
            """, List.of("async-escaped-group-alias-param-a-ok",
                "async-escaped-group-alias-param-b-ok"), true, List.of(true, true),
            false, false, null, "drive", "int:36", false),
        group("async-escaped-two-groups-captured", """
            import * as console from "std/console"
            export async function drive(): int {
              let a: async (n: int) => int = make(10);
              let b: async (n: int) => int = make(20);
              let av: int = await a(3);
              if (av === 16) {
                console.log("async-escaped-group-captured-a-ok");
              } else {
                console.log("async-escaped-group-captured-a-bad");
              }
              let bv: int = await b(3);
              if (bv === 26) {
                console.log("async-escaped-group-captured-b-ok");
              } else {
                console.log("async-escaped-group-captured-b-bad");
              }
              return av + bv;
            }
            function make(base: int): async (n: int) => int {
              async function f(n: int): int {
                if (n <= 0) { return base; }
                let m: int = n;
                let get: () => int = function(): int { return n + m; };
                await g(n - 1);
                return base + n + m;
              }
              async function g(n: int): int { return await f(n); }
              return f;
            }
            export function main(): null { return null; }
            """, List.of("async-escaped-group-captured-a-ok",
                "async-escaped-group-captured-b-ok"), true, List.of(true, true),
            false, false, null, "drive", "int:42", false),
        group("async-escaped-two-groups-alias-captured", """
            import * as console from "std/console"
            export async function drive(): int {
              let a: async (n: int) => int = make(10);
              let b: async (n: int) => int = make(20);
              let av: int = await a(3);
              if (av === 16) {
                console.log("async-escaped-group-alias-captured-a-ok");
              } else {
                console.log("async-escaped-group-alias-captured-a-bad");
              }
              let bv: int = await b(3);
              if (bv === 26) {
                console.log("async-escaped-group-alias-captured-b-ok");
              } else {
                console.log("async-escaped-group-alias-captured-b-bad");
              }
              return av + bv;
            }
            function make(base: int): async (n: int) => int {
              async function f(n: int): int {
                if (n <= 0) { return base; }
                let m: int = n;
                let get: () => int = function(): int { return n + m; };
                let sibling: async (n: int) => int = g;
                await sibling(n - 1);
                return base + n + m;
              }
              async function g(n: int): int { return await f(n); }
              return f;
            }
            export function main(): null { return null; }
            """, List.of("async-escaped-group-alias-captured-a-ok",
                "async-escaped-group-alias-captured-b-ok"), true, List.of(true, true),
            false, false, null, "drive", "int:42", false),
        self("nested-sync", """
            import * as console from "std/console"
            export function main(): null {
              let base: int = 5;
              function f(n: int): int {
                if (n <= 0) { return base; }
                let again: (n: int) => int = f;
                return n + again(n - 1);
              }
              if (f(4) === 15) {
                console.log("self-alias-nested-sync-ok");
              } else {
                console.log("self-alias-nested-sync-bad");
              }
              return null;
            }
            """, List.of("self-alias-nested-sync-ok"), null, "null", true),
        self("module-sync", """
            import * as console from "std/console"
            function f(n: int): int {
              if (n <= 0) { return 5; }
              let again: (n: int) => int = f;
              return n + again(n - 1);
            }
            export function main(): null {
              if (f(4) === 15) {
                console.log("self-alias-module-sync-ok");
              } else {
                console.log("self-alias-module-sync-bad");
              }
              return null;
            }
            """, List.of("self-alias-module-sync-ok"), null, "null", false),
        self("nested-async", """
            import * as console from "std/console"
            export async function drive(): int {
              let base: int = 5;
              async function f(n: int): int {
                if (n <= 0) {
                  console.log("self-alias-nested-async-leaf");
                  return base;
                }
                let again: async (n: int) => int = f;
                return await again(n - 1);
              }
              let v: int = await f(4);
              if (v === 5) {
                console.log("self-alias-nested-async-ok");
              } else {
                console.log("self-alias-nested-async-bad");
              }
              return v;
            }
            export function main(): null { return null; }
            """, List.of("self-alias-nested-async-leaf", "self-alias-nested-async-ok"),
            "drive", "int:5", true),
        self("module-async", """
            import * as console from "std/console";
            async function f(n: int): int {
              if (n <= 0) {
                console.log("self-alias-module-async-leaf");
                return 5;
              }
              let again: async (n: int) => int = f;
              return await again(n - 1);
            }
            export async function drive(): int {
              let v: int = await f(4);
              if (v === 5) {
                console.log("self-alias-module-async-ok");
              } else {
                console.log("self-alias-module-async-bad");
              }
              return v;
            }
            export function main(): null { return null; }
            """, List.of("self-alias-module-async-leaf", "self-alias-module-async-ok"),
            "drive", "int:5", false),
        self("direct-accum", """
            import * as console from "std/console";
            async function f(n: int): int {
              if (n <= 0) { return 0; }
              let v: int = await f(n - 1);
              return n + v;
            }
            export async function drive(): int {
              let v: int = await f(3);
              if (v === 6) {
                console.log("async-recursion-direct-ok");
              } else {
                console.log("async-recursion-direct-bad");
              }
              return v;
            }
            export function main(): null { return null; }
            """, List.of("async-recursion-direct-ok"), "drive", "int:6", false),
        self("alias-accum", """
            import * as console from "std/console";
            async function f(n: int): int {
              if (n <= 0) { return 0; }
              let again: async (n: int) => int = f;
              let v: int = await again(n - 1);
              return n + v;
            }
            export async function drive(): int {
              let v: int = await f(3);
              if (v === 6) {
                console.log("async-recursion-alias-ok");
              } else {
                console.log("async-recursion-alias-bad");
              }
              return v;
            }
            export function main(): null { return null; }
            """, List.of("async-recursion-alias-ok"), "drive", "int:6", false),
        self("direct-accum-fail", """
            import * as console from "std/console";
            async function f(n: int): int {
              if (n <= 0) { return 0; }
              let v: int = await f(n - 1);
              let q: int = 1 / (n - 2);
              return n + v + q;
            }
            export async function drive(): int {
              try {
                let v: int = await f(3);
                console.log("async-recursion-direct-fail-bad");
                return v;
              } catch (e) {
                console.log("async-recursion-direct-fail:" + e.code);
                return 1;
              }
            }
            export function main(): null { return null; }
            """, List.of("async-recursion-direct-fail:E8005"), "drive", "int:1", false),
        self("alias-accum-fail", """
            import * as console from "std/console";
            async function f(n: int): int {
              if (n <= 0) { return 0; }
              let again: async (n: int) => int = f;
              let v: int = await again(n - 1);
              let q: int = 1 / (n - 2);
              return n + v + q;
            }
            export async function drive(): int {
              try {
                let v: int = await f(3);
                console.log("async-recursion-alias-fail-bad");
                return v;
              } catch (e) {
                console.log("async-recursion-alias-fail:" + e.code);
                return 1;
              }
            }
            export function main(): null { return null; }
            """, List.of("async-recursion-alias-fail:E8005"), "drive", "int:1", false),
        self("seq-await", """
            import * as console from "std/console";
            async function step(n: int): int { return n; }
            async function fail(n: int): int {
              let q: int = 1 / (n - 1);
              return q;
            }
            export async function drive(): int {
              let i: int = 0;
              let total: int = 0;
              while (i < 100) {
                total = total + await step(i);
                i = i + 1;
              }
              try {
                let bad: int = await fail(1);
                console.log("seq-await-fail-bad");
              } catch (e) {
                console.log("seq-await-fail:" + e.code);
              }
              return total;
            }
            export function main(): null { return null; }
            """, List.of("seq-await-fail:E8005"), "drive", "int:4950", false));

    private record Lowered(ExecutableLoweredProject project,
                           Map<ModuleId, StructuredBodyTable> tables,
                           Map<ModuleId, ClassFactoryRegistry> registries,
                           LoweredModuleUnit unit, StructuredBodyTable table) {}

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static void runCase(Case testCase) throws Exception {
        System.out.println("-- " + testCase.name() + " --");
        Path project = Files.createTempDirectory("body-carrier-" + testCase.name() + "-");
        try {
            Path src = Files.createDirectories(project.resolve("src"));
            Path entry = src.resolve("main.deal");
            Files.writeString(entry, testCase.source(), StandardCharsets.UTF_8);
            for (var extra : testCase.extraSources().entrySet()) {
                Path sibling = src.resolve(extra.getKey());
                Files.createDirectories(sibling.getParent());
                Files.writeString(sibling, extra.getValue(), StandardCharsets.UTF_8);
            }
            Files.writeString(project.resolve("deal.json"), DEAL_JSON, StandardCharsets.UTF_8);
            CompilationOrchestrator lua = compileLane(testCase, project, entry, "luajit");
            CompilationOrchestrator jvm = compileLane(testCase, project, entry, "jvm");
            Lowered lowered = lower(testCase, lua);
            if (lowered == null) {
                return;
            }
            Path out = project.resolve("out-jvm");
            Path artifact = out.resolve(JvmNames.classNameFor("main") + ".java");
            Path probe = testCase.asyncExport() != null && testCase.failure() == null
                ? prepareAsyncProbe(testCase, out, jvm) : null;
            compileStagedJvm(testCase, out, artifact, probe);
            if (testCase.extraSources().isEmpty()) {
                differential(testCase, lowered, entry, project.resolve("matrix"));
            }
            if (testCase.failure() == null
                    && (testCase.asyncExport() != null || testCase.stagedSync())) {
                driveProduction(testCase, project, lowered, probe);
            }
            if ("seq-await".equals(testCase.name())) {
                checkOracleActivationRelease(testCase, lowered);
            }
        } finally {
            deleteRecursively(project);
        }
    }

    private static CompilationOrchestrator compileLane(Case testCase, Path project,
                                                       Path entry, String lane) throws Exception {
        Path out = project.resolve("out-" + lane);
        var located = ProjectLocator.locate(entry.toString(), new CliOverrides(lane, out.toString()));
        check(located.context() != null, testCase.name() + " [" + lane + "]: located project");
        if (located.context() == null) {
            return null;
        }
        var invocation = CompilerProfileProvider.resolve(ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
        var compiler = new CompilationOrchestrator(located.context(), entry,
            false, false, false, false, null, invocation);
        boolean compiled = compiler.compile();
        var errors = compiler.diagnostics().stream()
            .filter(diagnostic -> "error".equals(diagnostic.severity())).toList();
        check(compiled && errors.isEmpty(), testCase.name() + " [" + lane + "]: compile: " + errors);
        check(compiler.semanticEmissionCount() == 1,
            testCase.name() + " [" + lane + "]: one project artifact");
        check(Files.isRegularFile(out.resolve("luajit".equals(lane) ? "main.lua"
            : JvmNames.classNameFor("main") + ".java")), testCase.name() + " [" + lane + "]: staged");
        return compiler;
    }

    private static Lowered lower(Case testCase, CompilationOrchestrator compiler) {
        if (compiler == null) {
            return null;
        }
        CheckedProjectBuildResult built = compiler.checkedProject();
        RequirementManifestResult manifests = compiler.requirementManifests();
        check(built != null && built.input() != null && built.index() != null && !built.hasErrors()
                && manifests != null && manifests.manifests() != null,
            testCase.name() + ": checked closure and manifests");
        if (built == null || built.input() == null || built.index() == null || built.hasErrors()
                || manifests == null || manifests.manifests() == null) {
            return null;
        }
        ExecutableLoweredProject produced = compiler.producedProject();
        check(produced != null && compiler.hostDeclarationSurface().moduleIds().isEmpty(),
            testCase.name() + ": production lowered closure without host seeds");
        if (produced == null) {
            return null;
        }
        var result = SemanticLowerer.lowerProject(compiler.invocation(), built.input(), built.index(),
            manifests.manifests(), compiler.hostDeclarationSurface(), Map.of(), Map.of(),
            BuiltinErrorDeclaration.synthesized(built.input().modules().stream()
                .filter(module -> module.moduleId().equals(built.input().entryModule()))
                .findFirst().orElseThrow().ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT, IntrinsicKind.BYTES_NEW),
            Set.of());
        check(result.project() != null && result.diagnostics().isEmpty(),
            testCase.name() + ": independent lowering: " + result.diagnostics());
        if (result.project() == null) {
            return null;
        }
        boolean same = produced.entryModule().equals(result.project().entryModule())
            && produced.modules().keySet().equals(result.project().modules().keySet());
        for (var module : produced.modules().entrySet()) {
            LoweredModuleUnit independent = result.project().modules().get(module.getKey());
            same &= independent != null && SemanticIrDumper.dumpModuleText(module.getValue())
                .equals(SemanticIrDumper.dumpModuleText(independent));
        }
        check(same, testCase.name() + ": deterministic independent project lowering");
        if (!same) {
            return null;
        }
        LoweredModuleUnit unit = produced.modules().get(produced.entryModule());
        StructuredBodyTable table = result.tables().get(produced.entryModule());
        check(unit != null && table != null, testCase.name() + ": entry unit and body table");
        if (unit == null || table == null) {
            return null;
        }
        inspect(testCase, unit, table);
        return new Lowered(produced, result.tables(), result.registries(), unit, table);
    }

    private static FunctionExecutionBinding resolve(LoweredModuleUnit unit,
                                                    KindPayload.CallCallee callee) {
        return switch (callee) {
            case KindPayload.CallCallee.Static stat -> stat.binding();
            case KindPayload.CallCallee.Indirect indirect -> unit.functionBindings().get(
                new FunctionAllocationIdentity(indirect.callee().id()));
            case KindPayload.CallCallee.Dynamic ignored -> null;
        };
    }

    private static KindPayload.CallCallee callee(SemanticOp op) {
        return switch (op.payload()) {
            case KindPayload.CallPayload call -> call.callee();
            case KindPayload.AsyncStartPayload start -> start.callee();
            default -> null;
        };
    }

    private static OpId returnCell(SemanticOp op) {
        return switch (op.payload()) {
            case KindPayload.CallPayload call -> call.returnBoundaryOpId();
            case KindPayload.AsyncStartPayload start -> start.returnBoundaryOpId();
            default -> null;
        };
    }

    private static OpId entryRef(SemanticOp op) {
        return switch (op.payload()) {
            case KindPayload.CallPayload call -> call.externalEntryRef();
            case KindPayload.AsyncStartPayload start -> start.externalAsyncLink() == null ? null
                : new OpId(start.externalAsyncLink().calleeModuleId(),
                    start.externalAsyncLink().calleeTokenId().tokenId());
            default -> null;
        };
    }

    private static void inspect(Case testCase, LoweredModuleUnit unit, StructuredBodyTable table) {
        check(ControlFlowValidator.validate(unit, table).isEmpty(),
            testCase.name() + ": control-flow/RETURN block ownership admission");
        Map<OpId, SemanticOp> ops = new LinkedHashMap<>();
        Map<FunctionId, List<SemanticOp>> returns = new LinkedHashMap<>();
        Map<FunctionId, Integer> closures = new LinkedHashMap<>();
        Map<Long, SemanticOp> closureValues = new LinkedHashMap<>();
        Map<BindingGeneration, KindPayload.BindingAllocPayload> allocations = new LinkedHashMap<>();
        Map<String, SemanticOp> entries = new LinkedHashMap<>();
        Set<FunctionId> groupMembers = new LinkedHashSet<>();
        Set<FunctionId> delegated = new LinkedHashSet<>();
        List<SemanticOp> groups = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            ops.put(op.opId(), op);
            switch (op.payload()) {
                case KindPayload.ReturnPayload returned -> returns.computeIfAbsent(
                    returned.function(), ignored -> new ArrayList<>()).add(op);
                case KindPayload.ClosureNewPayload closure -> {
                    closures.merge(closure.function(), 1, Integer::sum);
                    if (op.result() instanceof ValueId value) {
                        closureValues.put(value.id(), op);
                        check(closure.binding().equals(unit.functionBindings().get(
                            new FunctionAllocationIdentity(value.id()))),
                            testCase.name() + ": closure registration " + value);
                    }
                }
                case KindPayload.RecursiveGroupInitPayload group -> {
                    groups.add(op);
                    groupMembers.addAll(group.functions());
                }
                case KindPayload.BindingAllocPayload alloc -> allocations.put(
                    new BindingGeneration(alloc.binding(), alloc.generation()), alloc);
                case KindPayload.ExternalEntryPayload entry -> entries.put(entry.exportName(), op);
                case KindPayload.EntryInvokePayload entry -> delegated.add(entry.mainFunction());
                default -> {}
            }
        }
        for (var closure : closures.entrySet()) {
            check(closure.getValue() == 1 && unit.functions().containsKey(closure.getKey()),
                testCase.name() + ": exactly-once declared closure " + closure.getKey());
        }
        for (FunctionId function : unit.functions().keySet()) {
            List<SemanticOp> owned = returns.getOrDefault(function, List.of());
            check(!owned.isEmpty(), testCase.name() + ": function owns RETURN " + function);
            Set<OpId> cells = new LinkedHashSet<>();
            for (SemanticOp op : owned) {
                var returned = (KindPayload.ReturnPayload) op.payload();
                cells.add(returned.returnBoundaryOpId());
                check(ops.containsKey(returned.returnBoundaryOpId()) && table.opBlocks().containsKey(op.opId()),
                    testCase.name() + ": RETURN cell/block " + op.opId());
            }
            check(cells.size() == 1, testCase.name() + ": single body return cell " + function);
        }
        int carrierCalls = 0;
        int entryCalls = 0;
        int awaitedBodies = 0;
        int selfCalls = 0;
        for (SemanticOp op : unit.ops()) {
            KindPayload.CallCallee targetCallee = callee(op);
            if (targetCallee == null) {
                continue;
            }
            FunctionExecutionBinding binding = resolve(unit, targetCallee);
            if (binding instanceof FunctionExecutionBinding.ExternalFunction external
                    && external.executionOwner() == ExternalExecutionOwner.SHARED_BODY
                    && external.moduleId().equals(unit.moduleId())) {
                entryCalls++;
                SemanticOp entry = entries.get(external.exportName());
                check(entry != null && entry.opId().equals(entryRef(op)),
                    testCase.name() + ": reserved exported-entry realization " + op.opId());
                if (entry != null) {
                    var payload = (KindPayload.ExternalEntryPayload) entry.payload();
                    check(payload.async() == (op.kind() == SemanticOpKind.ASYNC_START)
                            && unit.functions().containsKey(payload.function()),
                        testCase.name() + ": entry body/mode " + op.opId());
                    for (SemanticOp returnedOp : returns.getOrDefault(payload.function(), List.of())) {
                        var returned = (KindPayload.ReturnPayload) returnedOp.payload();
                        check(entry.opId().equals(returned.enclosingInvocationOpId())
                                && payload.returnBoundaryOpId().equals(returned.returnBoundaryOpId()),
                            testCase.name() + ": entry-owned RETURN " + returnedOp.opId());
                    }
                }
            }
            if (!(binding instanceof FunctionExecutionBinding.LoweredBody body)) {
                continue;
            }
            LoweredFunction function = unit.functions().get(body.functionId());
            List<SemanticOp> owned = returns.getOrDefault(body.functionId(), List.of());
            check(function != null && !owned.isEmpty() && owned.stream().allMatch(returned ->
                ((KindPayload.ReturnPayload) returned.payload()).returnBoundaryOpId().equals(returnCell(op))),
                testCase.name() + ": invocation uses declaring body's return cell " + op.opId());
            if (targetCallee instanceof KindPayload.CallCallee.Indirect indirect) {
                carrierCalls++;
                check(closureValues.containsKey(indirect.callee().id()) || groupMembers.contains(body.functionId()),
                    testCase.name() + ": published carrier identity " + op.opId());
                if (testCase.selfCaptures() != null && !RECURSION_CASES.contains(testCase.name())) {
                    SemanticOp closure = closureValues.get(indirect.callee().id());
                    check(closure != null && closures.getOrDefault(body.functionId(), 0) == 1
                            && (!testCase.selfCaptures() || function != null && !function.captures().isEmpty()),
                        testCase.name() + ": registered self-alias capture carrier " + op.opId());
                    if (owned.stream().anyMatch(returned -> ((KindPayload.ReturnPayload) returned.payload())
                            .enclosingInvocationOpId().equals(op.opId()))) {
                        selfCalls++;
                    }
                    if (op.payload() instanceof KindPayload.CallPayload call) {
                        check(call.mode() == CallMode.INDIRECT
                                && (call.bodyBlock() == null || call.bodyBlock().equals(body.blockId())),
                            testCase.name() + ": self-alias indirect body " + op.opId());
                    }
                }
            } else if (function != null && !function.captures().isEmpty()
                    && !delegated.contains(body.functionId())) {
                boolean stable = function.captures().stream().allMatch(capture -> {
                    var alloc = allocations.get(capture);
                    return alloc != null && alloc.scope().equals(unit.moduleInit().initBlock());
                });
                check(stable, testCase.name() + ": no creation-site capture reconstruction " + op.opId());
            }
            if (op.kind() == SemanticOpKind.ASYNC_START) {
                awaitedBodies++;
                check(op.result() instanceof AsyncTokenId.Canonical token
                        && token.owner() == AsyncTokenOwner.DEAL_BODY_TASK,
                    testCase.name() + ": canonical body task identity " + op.opId());
                if (RECURSION_CASES.contains(testCase.name())) {
                    check(closures.getOrDefault(body.functionId(), 0) == 1,
                        testCase.name() + ": one recursive task body closure " + op.opId());
                }
            }
        }
        check(carrierCalls >= testCase.minCarrierInvocations()
                && entryCalls >= testCase.minEntryInvocations(),
            testCase.name() + ": required carrier/entry invocations " + carrierCalls + "/" + entryCalls);
        if (testCase.selfCaptures() != null) {
            check(RECURSION_CASES.contains(testCase.name()) ? awaitedBodies >= 2 : selfCalls >= 1,
                testCase.name() + ": recursive/self-alias invocation exercised");
        }
        if (testCase.group() != null) {
            checkGroup(testCase, unit, table, groups, ops, returns);
        }
        checkAdapters(testCase, unit, ops, returns);
    }

    private static void checkGroup(Case testCase, LoweredModuleUnit unit, StructuredBodyTable table,
                                   List<SemanticOp> groups, Map<OpId, SemanticOp> ops,
                                   Map<FunctionId, List<SemanticOp>> returns) {
        check(groups.size() == 1, testCase.name() + ": one group publication");
        if (groups.size() != 1) {
            return;
        }
        SemanticOp groupOp = groups.getFirst();
        var group = (KindPayload.RecursiveGroupInitPayload) groupOp.payload();
        check(group.functions().size() == 2 && group.bindings().size() == 2
                && unit.functions().keySet().stream().anyMatch(id -> !group.functions().contains(id)),
            testCase.name() + ": two published members and enclosing body");
        for (int i = 0; i < group.functions().size(); i++) {
            FunctionId member = group.functions().get(i);
            boolean called = testCase.group().memberCalled().get(i);
            for (SemanticOp op : returns.getOrDefault(member, List.of())) {
                var returned = (KindPayload.ReturnPayload) op.payload();
                check(op.origin().kind() == (testCase.group().implicitReturns()
                        ? SourceOriginKind.SYNTHETIC : SourceOriginKind.USER),
                    testCase.name() + ": implicit/explicit member RETURN origin " + op.opId());
                SemanticOp invocation = ops.get(returned.enclosingInvocationOpId());
                FunctionExecutionBinding binding = invocation == null || callee(invocation) == null
                    ? null : resolve(unit, callee(invocation));
                check(called ? binding instanceof FunctionExecutionBinding.LoweredBody body
                        && body.functionId().equals(member)
                        : returned.enclosingInvocationOpId().equals(groupOp.opId()),
                    testCase.name() + ": called/never-called member invocation ownership " + member);
            }
        }
        BlockId groupBlock = table.opBlocks().get(groupOp.opId());
        boolean nested = groupBlock != null && !groupBlock.equals(unit.moduleInit().initBlock());
        int memberCalls = 0;
        int aliases = 0;
        for (SemanticOp op : unit.ops()) {
            if (callee(op) == null || !(resolve(unit, callee(op))
                    instanceof FunctionExecutionBinding.LoweredBody body)
                    || !group.functions().contains(body.functionId())) {
                continue;
            }
            memberCalls++;
            LoweredFunction target = unit.functions().get(body.functionId());
            if (nested && target != null && !target.captures().isEmpty()) {
                check(callee(op) instanceof KindPayload.CallCallee.Indirect,
                    testCase.name() + ": captured member uses published carrier " + op.opId());
            }
            if (testCase.group().aliasInvocation() && op.kind() == SemanticOpKind.CALL
                    && callee(op) instanceof KindPayload.CallCallee.Indirect) {
                aliases++;
                check(target != null && !target.captures().isEmpty(),
                    testCase.name() + ": sibling-alias captures " + op.opId());
            }
        }
        check(!nested || memberCalls > 0 || testCase.group().memberCalled().stream().noneMatch(Boolean::booleanValue),
            testCase.name() + ": member references materialized");
        check(!testCase.group().aliasInvocation() || aliases > 0,
            testCase.name() + ": value-carried sibling alias materialized");
    }

    private static void checkAdapters(Case testCase, LoweredModuleUnit unit,
                                      Map<OpId, SemanticOp> ops,
                                      Map<FunctionId, List<SemanticOp>> returns) {
        int adapters = 0;
        for (SemanticOp outer : unit.ops()) {
            if (callee(outer) == null || !(resolve(unit, callee(outer))
                    instanceof FunctionExecutionBinding.AdapterBinding adapter)) {
                continue;
            }
            boolean async = outer.payload() instanceof KindPayload.AsyncStartPayload start
                && start.parameterBoundaryMode() == ParameterBoundaryMode.RUN;
            boolean arity = adapter.sourceSignature().paramTypes().size()
                < adapter.targetSignature().paramTypes().size();
            if (testCase.arityExtensionAdapter() && arity) {
                adapters++;
                List<OpId> boundaries = outer.payload() instanceof KindPayload.CallPayload call
                    ? call.parameterBoundaryOpIds()
                    : ((KindPayload.AsyncStartPayload) outer.payload()).parameterBoundaryOpIds();
                List<RuntimeDescriptor> params = adapter.targetSignature().paramTypes();
                check(boundaries.size() == params.size(), testCase.name() + ": adapter target arity");
                for (int i = 0; i < boundaries.size(); i++) {
                    SemanticOp boundary = ops.get(boundaries.get(i));
                    check(boundary != null && boundary.payload() instanceof KindPayload.BoundaryPayload cell
                            && cell.kind() == BoundaryKind.FUNCTION_PARAMETER && i < params.size()
                            && params.get(i).equals(cell.descriptor()),
                        testCase.name() + ": adapter target parameter cell " + boundaries.get(i));
                }
                if (outer.payload() instanceof KindPayload.CallPayload call) {
                    SemanticOp cell = ops.get(call.returnBoundaryOpId());
                    check(call.mode() == CallMode.INDIRECT && call.externalEntryRef() == null
                            && call.bodyBlock() == null && cell != null
                            && cell.payload() instanceof KindPayload.BoundaryPayload boundary
                            && boundary.kind() == BoundaryKind.EXTERNAL_RETURN
                            && unit.ops().stream().anyMatch(op -> op.payload()
                                instanceof KindPayload.ExternalEntryPayload entry
                                && entry.returnBoundaryOpId().equals(call.returnBoundaryOpId())),
                        testCase.name() + ": sync adapter reserved entry return protocol");
                }
            }
            if (!async) {
                continue;
            }
            SemanticOp nested = unit.ops().stream().filter(op -> op.kind() == SemanticOpKind.ASYNC_START
                && outer.opId().equals(op.origin().parentOpId())).findFirst().orElse(null);
            check(nested != null, testCase.name() + ": nested adapter source start " + outer.opId());
            if (nested == null) {
                continue;
            }
            var inner = (KindPayload.AsyncStartPayload) nested.payload();
            FunctionExecutionBinding source = resolve(unit, inner.callee());
            if (source instanceof FunctionExecutionBinding.LoweredBody body) {
                check(inner.callee() instanceof KindPayload.CallCallee.Indirect
                        && inner.parameterBoundaryMode() == ParameterBoundaryMode.ELIDED_BY_ADAPTER
                        && inner.parameterBoundaryOpIds().isEmpty()
                        && returns.getOrDefault(body.functionId(), List.of()).stream().anyMatch(op ->
                            ((KindPayload.ReturnPayload) op.payload()).returnBoundaryOpId()
                                .equals(inner.returnBoundaryOpId()))
                        && nested.result() instanceof AsyncTokenId.Canonical token
                        && token.owner() == AsyncTokenOwner.DEAL_BODY_TASK,
                    testCase.name() + ": loaded nested source carrier/body task/return cell");
            }
            if (testCase.arityExtensionAdapter() && arity) {
                check(returnCell(outer) == null && inner.source() == AsyncStartSource.EXTERNAL
                        && inner.parameterBoundaryMode() == ParameterBoundaryMode.ELIDED_BY_ADAPTER
                        && inner.parameterBoundaryOpIds().isEmpty() && inner.returnBoundaryOpId() == null
                        && nested.operands().equals(outer.operands().subList(0,
                            adapter.sourceSignature().paramTypes().size()))
                        && source instanceof FunctionExecutionBinding.ExternalFunction external
                        && external.executionOwner() == ExternalExecutionOwner.SHARED_BODY
                        && external.moduleId().equals(unit.moduleId()),
                    testCase.name() + ": nested exported adapter leading-argument entry protocol");
                ExternalAsyncLink link = inner.externalAsyncLink();
                SemanticOp entry = link == null ? null : ops.get(new OpId(unit.moduleId(), link.calleeTokenId().tokenId()));
                check(link != null && link.calleeModuleId().equals(unit.moduleId())
                        && link.calleeTokenId() instanceof AsyncTokenId.Canonical token
                        && token.owner() == AsyncTokenOwner.DEAL_BODY_TASK && entry != null
                        && entry.payload() instanceof KindPayload.ExternalEntryPayload payload && payload.async()
                        && outer.result() instanceof AsyncTokenId.Alias alias
                        && alias.linkKind() == AsyncLinkKind.ADAPTER_INNER && alias.referent().equals(nested.result()),
                    testCase.name() + ": adapter activation-bound token links reserved entry");
            }
        }
        check(!testCase.arityExtensionAdapter() || adapters == 1,
            testCase.name() + ": exactly one arity-extension adapter invocation");
    }

    private static void differential(Case testCase, Lowered lowered, Path entry, Path workspace)
            throws Exception {
        Files.createDirectories(workspace);
        String origin = testCase.failure() == null ? null
            : originTextAt(testCase.source(), testCase.failure().anchor(), entry);
        check(testCase.failure() == null || origin != null, testCase.name() + ": failure annotation anchor");
        var expectation = testCase.failure() == null
            ? SemanticDifferentialHarness.Expectation.success(testCase.name(), testCase.effects(), testCase.resultAtom())
            : SemanticDifferentialHarness.Expectation.failure(testCase.name(), testCase.effects(), testCase.failure().code(), origin);
        var verdict = testCase.asyncExport() == null
            ? SemanticDifferentialHarness.runProject(lowered.project(), lowered.tables(), lowered.registries(), expectation, workspace)
            : SemanticDifferentialHarness.runAsyncEntry(lowered.project(), lowered.tables(), testCase.asyncExport(),
                List.of(), expectation, workspace, null);
        check(verdict.runs().size() == 3, testCase.name() + ": three consumers: " + verdict.failures());
        var unexplained = verdict.failures().stream().filter(failure -> !testCase.recursive()
            || !(failure.contains("starts again before its previous terminal")
                || failure.contains("terminates without an open START"))).toList();
        check(unexplained.isEmpty() && (testCase.recursive() || verdict.pass()),
            testCase.name() + ": pinned terminal/effects and event-for-event parity: " + unexplained);
        for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
            check(run.effects().stream().map(SemanticRuntimeModel.EffectEvent::text).toList().equals(testCase.effects()),
                testCase.name() + " [" + run.consumer() + "]: effects " + run.effects());
            if (testCase.failure() == null) {
                check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success success
                        && testCase.resultAtom().equals(success.resultAtom()),
                    testCase.name() + " [" + run.consumer() + "]: terminal " + run.terminal());
            } else {
                check(run.terminal() instanceof SemanticRuntimeModel.Terminal.DealFailure failure
                        && testCase.failure().code().equals(failure.error().code())
                        && origin != null && origin.equals(failure.error().origin())
                        && "expected int".equals(failure.error().message())
                        && "int".equals(failure.error().expected()) && "string".equals(failure.error().actual()),
                    testCase.name() + " [" + run.consumer() + "]: exact annotation-origin failure tuple " + run.terminal());
            }
        }
    }

    private static String originTextAt(String source, String anchor, Path entry) {
        int start = source.indexOf(anchor);
        if (start < 0) {
            return null;
        }
        int line = 1;
        int column = 1;
        for (int i = 0; i < start + anchor.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return entry.toAbsolutePath().normalize() + ":" + line + ":" + column;
    }

    private static Long asyncEntryIdOf(LoweredModuleUnit unit, String export) {
        for (SemanticOp op : unit.ops()) {
            if (op.payload() instanceof KindPayload.ExternalEntryPayload entry
                    && entry.async() && export.equals(entry.exportName())) {
                return op.opId().id();
            }
        }
        return null;
    }

    private static Path prepareAsyncProbe(Case testCase, Path out, CompilationOrchestrator compiler)
            throws Exception {
        var project = compiler == null ? null : compiler.producedProject();
        Long id = project == null ? null : asyncEntryIdOf(project.modules().get(project.entryModule()), testCase.asyncExport());
        check(id != null, testCase.name() + ": production async entry");
        if (id == null) {
            return null;
        }
        String name = "CarrierProbe_" + UUID.randomUUID().toString().replace("-", "");
        Path probe = out.resolve(name + ".java");
        String release = "seq-await".equals(testCase.name()) ? """
                java.lang.reflect.Field tasks = Class.forName("deal.codegen.jvm.JvmRuntime").getDeclaredField("TASKS");
                tasks.setAccessible(true);
                System.out.println("RETAINED:" + ((java.util.Map<?, ?>) tasks.get(null)).size());
            """ : "";
        Files.writeString(probe, "public final class " + name + " {\n"
            + "  public static void main(String[] args) throws Exception {\n"
            + "    Main.dealMain();\n"
            + "    Object result = Main.ae" + id + "(\"-\", true, new Object[]{});\n"
            + "    System.out.println(\"" + RESULT_MARKER + "\" + result);\n"
            + release + "    System.exit(0);\n  }\n}\n", StandardCharsets.UTF_8);
        return probe;
    }

    private static void compileStagedJvm(Case testCase, Path out, Path artifact, Path probe)
            throws Exception {
        Path classes = Files.createDirectories(out.resolve("classes"));
        List<String> command = new ArrayList<>(List.of("javac", "--release", "25", "-proc:none",
            "-encoding", "UTF-8", "-sourcepath", "", "-cp", classes + File.pathSeparator + absoluteClasspath(),
            "-d", classes.toString(), artifact.toString()));
        if (probe != null) {
            command.add(probe.toString());
        }
        var result = JvmCompilation.compile(command, out);
        check(result.exitCode() == 0, testCase.name() + ": staged JVM compilation " + result.stderr());
    }

    private static void driveProduction(Case testCase, Path project, Lowered lowered, Path javaProbe)
            throws Exception {
        boolean async = testCase.asyncExport() != null;
        boolean release = "seq-await".equals(testCase.name());
        List<String> expected = new ArrayList<>(testCase.effects());
        if (async) {
            String atom = testCase.resultAtom();
            expected.add(RESULT_MARKER + atom.substring(atom.indexOf(':') + 1));
        }
        Path luaOut = project.resolve("out-luajit");
        String luaName = "main.lua";
        if (async) {
            luaName = "__carrier_probe.lua";
            String surface = lowered.project().entryModule().path() + "#" + testCase.asyncExport();
            Files.writeString(luaOut.resolve(luaName),
                "dofile(\"main.lua\")\nlocal ok, err = __dealMain()\nif not ok then error(err, 0) end\n"
                + "local okA, result = pcall(__asyncEntries[\"" + luaString(surface) + "\"], \"-\", true)\n"
                + "if not okA then error(result, 0) end\nprint(\"" + RESULT_MARKER + "\" .. tostring(result))\n"
                + (release ? "local retained = 0\nfor id in pairs(__tasks) do retained = retained + 1 end\n"
                    + "print(\"" + RETAINED_MARKER + "\" .. tostring(retained))\n" : ""), StandardCharsets.UTF_8);
        }
        ProcessBuilder lua = new ProcessBuilder("luajit", luaName).directory(luaOut.toFile());
        if (async) {
            lua.environment().put("DEAL_DEFER_MAIN", "1");
        }
        checkProductionOutput(testCase, "luajit", runProcess(lua), expected, release);
        Path jvmOut = project.resolve("out-jvm");
        Path classes = jvmOut.resolve("classes");
        String name = async && javaProbe != null ? javaProbe.getFileName().toString().replace(".java", "")
            : JvmNames.classNameFor("main");
        check(!async || javaProbe != null, testCase.name() + ": compiled async probe selected");
        check(Files.isRegularFile(classes.resolve(name + ".class")), testCase.name() + ": staged JVM driver class");
        ProcessBuilder java = new ProcessBuilder("java", "-cp", classes + File.pathSeparator + absoluteClasspath(), name)
            .directory(jvmOut.toFile());
        checkProductionOutput(testCase, "jvm", runProcess(java), expected, release);
    }

    private static void checkProductionOutput(Case testCase, String lane, ProcessOutput run,
                                              List<String> expected, boolean release) {
        check(run.exit() == 0 && run.stderr().isEmpty(), testCase.name() + " [" + lane + "]: staged drive " + run);
        List<String> actual = new ArrayList<>(run.stdout());
        if (release) {
            Integer retained = null;
            if (!actual.isEmpty() && actual.getLast().startsWith(RETAINED_MARKER)) {
                retained = Integer.valueOf(actual.removeLast().substring(RETAINED_MARKER.length()));
            }
            check(retained != null && retained <= 2,
                testCase.name() + " [" + lane + "]: consumed success/failure task records retained=" + retained);
        }
        check(actual.equals(expected), testCase.name() + " [" + lane + "]: pinned staged effects/result " + actual);
    }

    private static void checkOracleActivationRelease(Case testCase, Lowered lowered) throws Exception {
        SemanticOp entry = lowered.unit().ops().stream().filter(op -> op.payload()
            instanceof KindPayload.ExternalEntryPayload payload && payload.async()
            && testCase.asyncExport().equals(payload.exportName())).findFirst().orElseThrow();
        Class<?> execution = Class.forName("deal.semantic.SemanticOracle$Execution");
        var constructor = execution.getDeclaredConstructor(ExecutableLoweredProject.class, Map.class,
            Class.forName("deal.semantic.SemanticOracle$HostResponder"));
        constructor.setAccessible(true);
        Object state = constructor.newInstance(lowered.project(), lowered.tables(), null);
        var init = execution.getDeclaredMethod("runModuleInit", LoweredModuleUnit.class);
        init.setAccessible(true);
        init.invoke(state, lowered.unit());
        var drive = execution.getDeclaredMethod("executeAsyncEntry", SemanticOp.class, OpId.class, List.class, long.class);
        drive.setAccessible(true);
        drive.invoke(state, entry, null, List.of(), entry.opId().id());
        var drain = execution.getDeclaredMethod("drainReadyTasks");
        drain.setAccessible(true);
        drain.invoke(state);
        var tasks = execution.getDeclaredField("tasks");
        tasks.setAccessible(true);
        int retained = ((Map<?, ?>) tasks.get(state)).size();
        check(retained <= 2, testCase.name() + " [oracle]: consumed success/failure activation release retained=" + retained);
    }

    private static void checkFrozenRuntimeActivationRelease() throws Exception {
        var tasksField = JvmRuntime.class.getDeclaredField("TASKS");
        tasksField.setAccessible(true);
        Map<?, ?> tasks = (Map<?, ?>) tasksField.get(null);
        var slotsField = JvmRuntime.class.getDeclaredField("ENTRY_INSTANCES");
        slotsField.setAccessible(true);
        Map<?, ?> slots = (Map<?, ?>) slotsField.get(null);
        long token = 762_000_001L;
        int iterations = 10_000;
        for (boolean failing : List.of(false, true)) {
            int before = tasks.size();
            int matched = 0;
            for (int i = 0; i < iterations; i++) {
                final int value = i;
                JvmRuntime.startBodyTaskInstance(token, "DEAL_BODY_TASK", () -> {
                    if (failing) {
                        throw new JvmRuntime.DealError("E8005", "integer division by zero", "-", null, null, "", null);
                    }
                    return value;
                });
                try {
                    Object result = JvmRuntime.awaitEntryTask(token, false, "release-probe");
                    if (!failing && Integer.valueOf(value).equals(result)) {
                        matched++;
                    }
                } catch (JvmRuntime.DealError error) {
                    if (failing && "E8005".equals(error.code)) {
                        matched++;
                    }
                }
            }
            check(matched == iterations, "frozen runtime consumes own " + (failing ? "failure" : "success") + " completions");
            check(tasks.size() - before <= 2, "frozen runtime releases consumed activations; delta=" + (tasks.size() - before));
        }
        Object slot = slots.get(token);
        check(slot == null || ((java.util.Collection<?>) slot).isEmpty(), "frozen runtime residual activation slot=" + slot);
    }

    private record ProcessOutput(int exit, List<String> stdout, List<String> stderr) {}

    private static ProcessOutput runProcess(ProcessBuilder builder) throws Exception {
        var result = BoundedProcess.runCaptured(builder.command(), builder.directory().toPath(), builder.environment());
        if (result.stdoutTruncated() || result.stderrTruncated()) {
            throw new AssertionError("runtime process output truncated: " + builder.command());
        }
        return new ProcessOutput(result.exitCode(), result.stdout().lines().filter(line -> !line.isEmpty()).toList(),
            result.stderr().lines().filter(line -> !line.isEmpty()).toList());
    }

    private static String luaString(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String absoluteClasspath() {
        return Stream.of(System.getProperty("java.class.path", "").split(File.pathSeparator))
            .filter(entry -> !entry.isEmpty()).map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
            .collect(java.util.stream.Collectors.joining(File.pathSeparator));
    }

    private static void deleteRecursively(Path root) throws Exception {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        check(CASES.size() == 60 && CASES.stream().map(Case::name).distinct().count() == 60,
            "60 distinct declared-body, nested-group and self-alias cases");
        for (Case testCase : CASES) {
            if (args.length == 0 || List.of(args).contains(testCase.name())) {
                runCase(testCase);
            }
        }
        checkFrozenRuntimeActivationRelease();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
