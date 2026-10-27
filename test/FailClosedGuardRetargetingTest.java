package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.module.CheckoutResources;
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
import deal.semantic.HostDeclarationSurface;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticOracle;
import deal.semantic.SemanticRequirementManifest;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.SharedStdlibSemantics;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.ReleaseState;
import deal.test.conformance.JvmCompilation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

public class FailClosedGuardRetargetingTest {

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

    // =========================================================================
    // Fixtures
    // =========================================================================


    private static final ModuleId HOST_HELPER = new ModuleId("host.helper");

    /**
     * The cross-module-callee fixture of the undeclared-external-entry
     * seed (R8 candidate (c)): the caller's call records the callee export
     * fact; removing the callee module from the closure removes the
     * recorded {@code EXTERNAL_ENTRY}.
     */
    private static final String CALLEE_LIB_SOURCE = """
        export function add(a: number, b: number): number {
          return a + b;
        }
        """;

    private static final String CROSS_MODULE_CALL_SOURCE = """
        import * as lib from "./lib";

        export function main(): null {
          let x: number = lib.add(1, 2);
          return null;
        }
        """;

    /** The imported-class owner of the RETAINED_ABI_DEFERRED seed. */
    private static final String UTIL_SOURCE = """
        export class Point {
          x: number;
          y: number;
        }
        """;

    /**
     * The class literal whose owner the hand-built inconsistent fact
     * removes from the closure, the index, and the manifests (R8 candidate
     * (d): a class literal whose owner is neither lowered nor
     * declaration-typed).
     */
    private static final String IMPORTED_LITERAL_SOURCE = """
        import * as u from "util";

        export function main(): null {
          let p: u.Point = {x: 5, y: 1};
          return null;
        }
        """;


    /** The extern-C provider's host declaration. */
    private static final String HOST_PROVIDER_DECLARATION = """
        export function ping(): number;
        """;



    private record Fixture(
        Path root,
        Path sourceRoot,
        CheckedProjectInput checkedProject,
        ProjectInterfaceIndex index,
        List<SemanticRequirementManifest> manifests,
        HostDeclarationSurface surface,
        Map<ModuleId, CanonicalModuleIdentity> declarationIdentities,
        CheckoutResources checkoutResources) {
    }

    private static CompilerInvocation harnessInvocation() {
        return CompilerProfileProvider.resolve(ReleaseState.V1_5_ACTIVE,
            CapabilityRegistry.releaseRegistry());
    }

    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    /**
     * Compiles one fixture project through the frontend with the harness
     * invocation (P10 item 3: the fixture inputs stay buildable while the
     * unit under test runs the release-owned production invocation).
     */
    private static Fixture compileFixture(Map<String, String> sources,
            Map<String, String> externals) throws Exception {
        Path root = Files.createTempDirectory("guard-retarget-fixture");
        Path src = root.resolve("src");
        Files.createDirectories(src);
        for (Map.Entry<String, String> source : sources.entrySet()) {
            writeFileIn(root, source.getKey(), source.getValue());
        }
        Map<String, String> resolvedExternals = new LinkedHashMap<>();
        for (Map.Entry<String, String> external : externals.entrySet()) {
            resolvedExternals.put(external.getKey(),
                root.resolve(external.getValue()).toAbsolutePath().toString());
        }
        Path entry = src.resolve("main.deal").toAbsolutePath();
        Path output = root.resolve("out").toAbsolutePath();
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(entry,
            output, false, false, false, false, Backend.LUAJIT, resolvedExternals,
            List.of(src.toAbsolutePath()), null, null, harnessInvocation());
        boolean compiled = orchestrator.compile();
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        if (!compiled || built == null || built.input() == null
                || built.index() == null || built.hasErrors() || manifests == null
                || manifests.manifests() == null
                || orchestrator.hostDeclarationSurface() == null) {
            String detail = built == null ? "no checked project"
                : String.valueOf(built.diagnostics());
            deleteRecursively(root);
            throw new IllegalStateException("the guard fixture did not build: "
                + detail + " / " + orchestrator.diagnostics());
        }

        Map<ModuleId, CanonicalModuleIdentity> identities = new LinkedHashMap<>();
        for (ModuleId declarationModule
                : orchestrator.hostDeclarationSurface().moduleIds()) {
            String specifier = null;
            for (String candidate : resolvedExternals.keySet()) {
                if (candidate.replace('/', '.').equals(declarationModule.path())) {
                    specifier = candidate;
                    break;
                }
            }
            identities.put(declarationModule, specifier == null
                ? IdentityTestFixtures.moduleIdentityOf(declarationModule.path())
                : new CanonicalModuleIdentity.ExternalModule(specifier));
        }
        return new Fixture(root, src, built.input(), built.index(),
            manifests.manifests(), orchestrator.hostDeclarationSurface(),
            identities,
            CheckoutResources.forManifestDirectory(src.toString()));
    }

    private static Fixture importedLiteralFixture() throws Exception {
        return compileFixture(new LinkedHashMap<>(Map.of(
                "src/util.deal", UTIL_SOURCE,
                "src/main.deal", IMPORTED_LITERAL_SOURCE)), Map.of());
    }

    private static Fixture crossModuleCallFixture() throws Exception {
        return compileFixture(new LinkedHashMap<>(Map.of(
                "src/lib.deal", CALLEE_LIB_SOURCE,
                "src/main.deal", CROSS_MODULE_CALL_SOURCE)), Map.of());
    }

    // =========================================================================
    // The unit driver
    // =========================================================================

    private static ProductionProjectEmission.Result emit(Fixture fixture,
            CheckedProjectInput project, ProjectInterfaceIndex index,
            List<SemanticRequirementManifest> manifests,
            PublicationStager stager, Backend backend) throws Exception {
        return ProductionProjectEmission.run(productionInvocation(), project,
            index, manifests, fixture.surface(), fixture.declarationIdentities(),
            BuiltinErrorDeclaration.synthesized(
                project.modules().get(0).ast().span()),
            java.util.Set.of(), backend, false, fixture.checkoutResources(), stager);
    }

    /** The two production targets the non-FFI negative seeds cover. */
    private static final List<Backend> NEGATIVE_TARGETS =
        List.of(Backend.LUAJIT, Backend.JVM);

    // =========================================================================
    // 2. One live negative seed per guard (+ the atomic-staging property)
    // =========================================================================

    /** One hand-built inconsistent fact: the closure without one owner. */
    private record StrippedClosure(CheckedProjectInput project,
        ProjectInterfaceIndex index, List<SemanticRequirementManifest> manifests) {
    }

    /**
     * Removes one module from the closure, the interface index, and the
     * manifests (the hand-built inconsistent fact of R8 candidates (c)
     * and (d)): the checker-resolved facts stay, the lowered/declared
     * owner does not.
     */
    private static StrippedClosure stripModule(Fixture fixture, ModuleId owner) {
        List<CheckedModuleInput> modules = new ArrayList<>();
        for (CheckedModuleInput module : fixture.checkedProject().modules()) {
            if (!module.moduleId().equals(owner)) {
                modules.add(module);
            }
        }
        Map<ModuleId, ExternalModuleInterface> indexModules = new LinkedHashMap<>();
        for (Map.Entry<ModuleId, ExternalModuleInterface> entry
                : fixture.index().modules().entrySet()) {
            if (!entry.getKey().equals(owner)) {
                indexModules.put(entry.getKey(), entry.getValue());
            }
        }
        List<SemanticRequirementManifest> manifests = new ArrayList<>();
        for (SemanticRequirementManifest manifest : fixture.manifests()) {
            if (!manifest.moduleId().equals(owner)) {
                manifests.add(manifest);
            }
        }
        CompilerInvocation invocation = productionInvocation();
        return new StrippedClosure(
            new CheckedProjectInput(invocation,
                fixture.checkedProject().entryModule(), modules,
                invocation.releaseStateHash()),
            new ProjectInterfaceIndex(ProjectInterfaceIndex.FORMAT_VERSION,
                indexModules),
            manifests);
    }

    private static void testConstructUnloweredSeed() throws Exception {
        System.out.println("-- R8(c): the cross-module callee whose module is "
            + "neither lowered nor recorded fails CONSTRUCT_UNLOWERED, "
            + "stages nothing, and preserves the previous artifact set on "
            + "both production targets --");
        Fixture fixture = crossModuleCallFixture();
        try {
            StrippedClosure stripped = stripModule(fixture, new ModuleId("lib"));
            for (Backend backend : NEGATIVE_TARGETS) {
                String target = backend.name();
                // The control: the intact fixture emits, so the stripped fact
                // is the seed's cause.
                PublicationStager intactStager = PublicationStager.forRoot(
                    fixture.root().resolve("out-intact-" + target));
                try {
                    ProductionProjectEmission.Result intact = emit(fixture,
                        fixture.checkedProject(), fixture.index(),
                        fixture.manifests(),
                        intactStager, backend);
                    check(intact.emitted(),
                        target + ": the intact cross-module call emits (the "
                            + "seed's control): " + intact.diagnostics());
                } finally {
                    intactStager.discard();
                }
                Path out = fixture.root().resolve("out-arm-" + target);
                Map<String, String> before = seedPreviousArtifactSet(out);
                PublicationStager stager = PublicationStager.forRoot(out);
                ProductionProjectEmission.Result result;
                try {
                    result = emit(fixture, stripped.project(), stripped.index(),
                        stripped.manifests(),  stager,
                        backend);
                    check(stager.stagedSet().relativePaths().isEmpty(),
                        target + ": the undeclared-external-entry seed stages "
                            + "nothing");
                } finally {
                    stager.discard();
                }
                check(!result.emitted(),
                    target + ": the undeclared-external-entry seed fails "
                        + "closed");
                checkEq(1, result.diagnostics().size(),
                    target + ": exactly one diagnostic: " + result.diagnostics());
                String message = result.firstDiagnostic() == null ? ""
                    : result.firstDiagnostic().message();
                check("E6005".equals(result.firstDiagnostic() == null ? ""
                        : result.firstDiagnostic().code())
                        && message.contains("validatorRule CONSTRUCT_UNLOWERED")
                        && (message.contains("no recorded EXTERNAL_ENTRY")
                            || message.contains("no callee-route record")),
                    target + ": the seed names E6005 CONSTRUCT_UNLOWERED with "
                        + "the missing callee fact: " + result.diagnostics());
                checkEq(before, snapshotTree(out),
                    target + ": the failing lowering leaves the previous "
                        + "artifact set byte-identical");
            }
        } finally {
            deleteRecursively(fixture.root());
        }
    }

    private static void testRetainedAbiDeferredSeed() throws Exception {
        System.out.println("-- R8(d): a hand-built inconsistent fact (the class "
            + "literal whose owner is neither lowered nor declaration-typed) "
            + "fails RETAINED_ABI_DEFERRED, stages nothing, and preserves the "
            + "previous artifact set on both production targets --");
        Fixture fixture = importedLiteralFixture();
        try {
            // The hand-built inconsistency: the owner module leaves the
            // closure, the interface index, and the manifests, so the
            // checker-resolved class id has no lowered implementation and
            // no declaration-typed owner.
            StrippedClosure stripped = stripModule(fixture, new ModuleId("util"));
            for (Backend backend : NEGATIVE_TARGETS) {
                String target = backend.name();
                // The control: the intact in-project class literal emits, so
                // the stripped owner is the seed's cause.
                PublicationStager intactStager = PublicationStager.forRoot(
                    fixture.root().resolve("out-intact-" + target));
                try {
                    ProductionProjectEmission.Result intact = emit(fixture,
                        fixture.checkedProject(), fixture.index(),
                        fixture.manifests(),
                        intactStager, backend);
                    check(intact.emitted(),
                        target + ": the intact imported-class literal emits "
                            + "(the seed's control): " + intact.diagnostics());
                } finally {
                    intactStager.discard();
                }
                Path out = fixture.root().resolve("out-arm-" + target);
                Map<String, String> before = seedPreviousArtifactSet(out);
                PublicationStager stager = PublicationStager.forRoot(out);
                ProductionProjectEmission.Result result;
                try {
                    result = emit(fixture, stripped.project(), stripped.index(),
                        stripped.manifests(),  stager,
                        backend);
                    check(stager.stagedSet().relativePaths().isEmpty(),
                        target + ": the RETAINED_ABI_DEFERRED seed stages "
                            + "nothing");
                } finally {
                    stager.discard();
                }
                check(!result.emitted(),
                    target + ": the RETAINED_ABI_DEFERRED seed fails closed");
                checkEq(1, result.diagnostics().size(),
                    target + ": exactly one diagnostic: " + result.diagnostics());
                check(result.firstDiagnostic() != null
                        && "E6005".equals(result.firstDiagnostic().code())
                        && result.firstDiagnostic().message()
                            .contains("validatorRule RETAINED_ABI_DEFERRED"),
                    target + ": the seed names E6005 RETAINED_ABI_DEFERRED: "
                        + result.diagnostics());
                checkEq(before, snapshotTree(out),
                    target + ": the failing lowering leaves the previous "
                        + "artifact set byte-identical");
            }
        } finally {
            deleteRecursively(fixture.root());
        }
    }

    // =========================================================================
    // 3. The realized-construct probes (both production targets)
    // =========================================================================

    private static final String MANIFEST = """
        {
          "languageVersion": "1.5",
          "moduleRoots": ["src"],
          "output": "out",
          "backend": "luajit"
        }
        """;

    /**
     * The residual carrier probe (R4 mechanism 1): the self-recursive
     * function declaration lives <em>inside</em> the executing body — the
     * residual local-declaration mechanism — and the probe asserts its
     * terminal result and call count on both production targets (the
     * captured counter proves the local body executed once per call).
     */
    private static final String RESIDUAL_PROBE = """
        import * as console from "std/console";

        export function main(): null {
          let calls: number = 0;
          function down(n: number): number {
            calls = calls + 1;
            if (n === 0) {
              return 0;
            }
            return down(n - 1);
          }
          let r: number = down(4);
          if (r === 0 && calls === 5) {
            console.log("PROBE|RESIDUAL|ok");
          } else {
            console.log("PROBE|RESIDUAL|bad");
          }
          return null;
        }
        """;

    private static final String CAPTURE_PROBE = "import * as console from \"std/console\";\n\nexport function main(): null {\n  let fs: (() => number)[] = [function(): number { return -1; }, function(): number { return -1; }, function(): number { return -1; }, function(): number { return -1; }];\n  { let v14Loop0: number = 0; while (v14Loop0 < 4) { let i: number = v14Loop0; {\n    fs[i] = function(): number { return i; };\n  } v14Loop0 = i; v14Loop0 = v14Loop0 + 1; } }\n  let total: number = fs[0]() + fs[1]() + fs[2]() + fs[3]();\n  if (total === 6) {\n    console.log(\"PROBE|CAPTURE|ok\");\n  } else {\n    console.log(\"PROBE|CAPTURE|bad\");\n  }\n  return null;\n}\n";

    private static final String TRANSFER_PROBE = "import * as console from \"std/console\";\n\nexport function main(): null {\n  let vals: number[] = [1, 2, 3, 4, 5];\n  let sum: number = 0;\n  for (let x of vals) {\n    if (x === 2) {\n      continue;\n    }\n    if (x === 4) {\n      break;\n    }\n    sum = sum + x;\n  }\n  let found: number = 0;\n  { let v14Loop1: number = 0; while (v14Loop1 < 5) { let i: number = v14Loop1; {\n    try {\n      if (i === 3) {\n        break;\n      }\n    } catch (e) {\n      found = -1;\n    }\n    found = found + 1;\n  } v14Loop1 = i; v14Loop1 = v14Loop1 + 1; } }\n  if (sum === 4 && found === 3) {\n    console.log(\"PROBE|TRANSFER|ok\");\n  } else {\n    console.log(\"PROBE|TRANSFER|bad\");\n  }\n  return null;\n}\n";

    private static void testRealizedConstructProbes() throws Exception {
        System.out.println("-- the realized construct families compile through "
            + "the release-owned production invocation and execute under both "
            + "real toolchains with their oracle-agreed transcripts and no "
            + "guard outcome --");
        probe("array value semantics", """
            import * as console from "std/console";
            export function main(): null {
              let b: number[] = [0, 0, 0, 0];
              b[0] = 7;
              b[3] = 250;
              let n: number = b[0] + b[3] + b.length;
              if (n === 261) { console.log("PROBE|ARRAY|ok"); }
              else { console.log("PROBE|ARRAY|bad"); }
              return null;
            }
            """, "PROBE|ARRAY|ok");
        probe("same-module @jsonable public CLI", """
            import * as console from "std/console";
            // @jsonable
            export class Person { name: string; score: number; }
            export function main(): null {
              let p: Person = { name: "Carol", score: 7 };
              let s: string = Person$toJson(p);
              let q: Person | null = Person$fromJson(s);
              if (q !== null) {
                if (q.name === "Carol" && q.score === 7) {
                  console.log("PROBE|JSONABLE|ok");
                } else { console.log("PROBE|JSONABLE|bad"); }
              } else { console.log("PROBE|JSONABLE|bad"); }
              return null;
            }
            """, "PROBE|JSONABLE|ok");
        probeSources("cross-module @jsonable public CLI", Map.of(
            "src/lib.deal", """
                // @jsonable
                export class Widget { label: string; count: number; }
                """,
            "src/main.deal", """
                import * as console from "std/console";
                import * as Lib from "./lib";
                export function main(): null {
                  let w: Lib.Widget = { label: "a", count: 1 };
                  let s: string = Lib.Widget$toJson(w);
                  let q: Lib.Widget | null = Lib.Widget$fromJson(s);
                  if (q !== null) {
                    if (q.label === "a" && q.count === 1) {
                      console.log("PROBE|JSONABLE-XMOD|ok");
                    } else { console.log("PROBE|JSONABLE-XMOD|bad"); }
                  } else { console.log("PROBE|JSONABLE-XMOD|bad"); }
                  return null;
                }
                """), "PROBE|JSONABLE-XMOD|ok");
        probe("the residual carrier shape (a local self-recursive declared "
            + "body)", RESIDUAL_PROBE, "PROBE|RESIDUAL|ok");
        probe("the per-iteration closure capture", CAPTURE_PROBE,
            "PROBE|CAPTURE|ok");
        probe("the loop-target transfer protocol", TRANSFER_PROBE,
            "PROBE|TRANSFER|ok");
    }

    private static void probe(String name, String source, String expectedLine)
            throws Exception {
        probeSources(name, Map.of("src/main.deal", source), expectedLine);
    }

    private static void probeSources(String name, Map<String, String> sources,
            String expectedLine) throws Exception {
        Path root = Files.createTempDirectory("guard-retarget-probe-");
        try {
            writeFileIn(root, "deal.json", MANIFEST);
            for (Map.Entry<String, String> source : sources.entrySet()) {
                writeFileIn(root, source.getKey(), source.getValue());
            }
            String entry = root.resolve("src/main.deal").toAbsolutePath().toString();
            String expectedStdout = expectedLine + "\n";

            // The oracle leg: the same sources, lowered by the release-owned
            // production invocation and executed by the semantic oracle, so
            // each artifact transcript below is compared against an
            // independent execution (terminal, console effects).
            OracleTranscript oracle = runOracle(name, root);
            if (oracle != null) {
                checkEq(expectedStdout, oracle.stdout(),
                    name + ": the oracle console transcript is the pinned "
                        + "probe line");
                checkEq("", oracle.stderr(),
                    name + ": the oracle records no stderr console effect");
            }

            // The decoy entrypoint of the classpath collision control: a
            // second class named Main that must lose against the generated
            // artifact whenever the generated classes come first.
            Path decoyClasses = compileDecoyMain(name, root);

            ProjectOutcome luaCompile = runProductionCli("compile", entry,
                "--backend", "lua", "--output",
                root.resolve("out-lua").toAbsolutePath().toString());
            checkEq(0, luaCompile.exitCode(), name + ": the Lua CLI compile succeeds: "
                + luaCompile.stderr());
            check(!luaCompile.stderr().contains("E6005")
                    && !luaCompile.stderr().contains("CONSTRUCT_UNLOWERED")
                    && !luaCompile.stderr().contains("RETAINED_ABI_DEFERRED")
                    && !luaCompile.stderr().contains("SHARED_EMITTER_COVERAGE"),
                name + ": the Lua CLI reports no guard outcome");
            if (luaCompile.exitCode() == 0) {
                ProcessOutcome run = runProcess(
                    List.of("luajit", "main.lua"), root.resolve("out-lua"));
                assertArtifactOutcome(name + " (LuaJIT)", run, oracle,
                    expectedStdout);
            }

            Path jvmOut = root.resolve("out-jvm");
            ProjectOutcome jvmCompile = runProductionCli("compile", entry,
                "--backend", "jvm", "--output", jvmOut.toAbsolutePath().toString());
            check(jvmCompile.exitCode() == 0,
                name + ": the JVM production compile succeeds: "
                    + jvmCompile.stderr());
            check(!jvmCompile.stderr().contains("E6005"),
                name + ": the JVM realized construct reports no guard outcome: "
                    + jvmCompile.stderr());
            if (jvmCompile.exitCode() != 0) {
                return;
            }
            String classpath = absoluteClasspath();
            ProcessOutcome javac = runProcess(List.of("javac", "--release", "25",
                "-proc:none", "-cp", classpath, "-d", jvmOut.toString(),
                jvmOut.resolve("Main.java").toString()), root);
            checkEq(0, javac.exitCode(),
                name + ": the JVM artifact compiles (JLS 14.21 valid): "
                    + javac.output());
            if (javac.exitCode() != 0) {
                return;
            }
            // The generated classes precede every inherited classpath entry
            // (test/AGENTS.md), so the just-generated Main is selected.
            ProcessOutcome javaRun = runProcess(List.of("java", "-cp",
                jvmOut + File.pathSeparator + decoyClasses + File.pathSeparator
                    + classpath, "Main"), root);
            assertArtifactOutcome(name + " (JVM)", javaRun, oracle, expectedStdout);
            // The collision control is live: with the decoy first, the decoy
            // class is selected instead of the generated artifact, so the
            // assertion above is not vacuous.
            ProcessOutcome decoyFirst = runProcess(List.of("java", "-cp",
                decoyClasses + File.pathSeparator + jvmOut + File.pathSeparator
                    + classpath, "Main"), root);
            checkEq(0, decoyFirst.exitCode(),
                name + ": the collision control runs: " + decoyFirst.output());
            checkEq(DECOY_LINE + "\n", decoyFirst.stdout(),
                name + ": the collision control proves a preceding Main "
                    + "shadows the generated entrypoint");
            check(!outcomeMatches(decoyFirst, oracle, expectedStdout),
                name + ": the shadowed decoy fails the outcome comparison");
        } finally {
            deleteRecursively(root);
        }
    }

    /** One oracle execution's console transcripts, by channel. */
    private record OracleTranscript(String stdout, String stderr) {
    }

    /**
     * The oracle leg of one probe: the same sources compile through the
     * frontend under the release-owned production invocation, lower with
     * zero diagnostics, and execute in the semantic oracle; the run must
     * reach the null success terminal. Returns the console transcripts, or
     * {@code null} when the closure itself did not build.
     */
    private static OracleTranscript runOracle(String label, Path root)
            throws Exception {
        Path src = root.resolve("src");
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            src.resolve("main.deal").toAbsolutePath(),
            root.resolve("out-oracle"),
            false, false, false, false, Backend.LUAJIT, Map.of(),
            List.of(src.toAbsolutePath()),
            Path.of("std").toAbsolutePath().normalize(),
            null, productionInvocation());
        boolean compiled = orchestrator.compile();
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        HostDeclarationSurface surface = orchestrator.hostDeclarationSurface();
        check(compiled && built != null && built.input() != null
                && built.index() != null && !built.hasErrors()
                && manifests != null && manifests.manifests() != null
                && surface != null,
            label + ": the oracle closure compiles: "
                + (built == null ? String.valueOf(orchestrator.diagnostics())
                    : String.valueOf(built.diagnostics())));
        if (!compiled || built == null || built.input() == null
                || built.index() == null || built.hasErrors()
                || manifests == null || manifests.manifests() == null
                || surface == null) {
            return null;
        }
        SemanticLowerer.ProjectLoweringResult lowered = SemanticLowerer.lowerProject(
            productionInvocation(), built.input(), built.index(),
            manifests.manifests(), surface, Map.of(), BuiltinErrorDeclaration.synthesized(
                built.input().modules().get(0).ast().span()),
            Set.of());
        check(lowered.project() != null,
            label + ": the oracle closure lowers with zero diagnostics: "
                + lowered.diagnostics());
        if (lowered.project() == null) {
            return null;
        }
        SemanticRuntimeModel.ConsumerRun run = SemanticOracle.executeProjectInits(
            lowered.project(), lowered.tables(), lowered.registries(), null);
        boolean success = run.terminal()
                instanceof SemanticRuntimeModel.Terminal.Success terminal
            && "null".equals(terminal.resultAtom());
        check(success, label + ": the oracle runs the probe entry to the null "
            + "terminal: " + run.terminal());
        return new OracleTranscript(
            consoleTranscript(run, SharedStdlibSemantics.Channel.STDOUT),
            consoleTranscript(run, SharedStdlibSemantics.Channel.STDERR));
    }

    /** The ordered console-effect transcript of one channel, line-per-effect. */
    private static String consoleTranscript(SemanticRuntimeModel.ConsumerRun run,
            SharedStdlibSemantics.Channel channel) {
        StringBuilder transcript = new StringBuilder();
        for (SemanticRuntimeModel.EffectEvent effect : run.effects()) {
            if (effect.kind() == SemanticRuntimeModel.EffectEvent.Kind.CONSOLE_WRITE
                    && effect.channel() == channel) {
                transcript.append(effect.text()).append('\n');
            }
        }
        return transcript.toString();
    }

    /** The decoy entrypoint line of the classpath collision control. */
    private static final String DECOY_LINE = "PROBE|DECOY|wrong-entrypoint";

    /** Compiles the decoy Main of the entrypoint collision control. */
    private static Path compileDecoyMain(String label, Path root) throws Exception {
        Path decoySource = root.resolve("decoy-src/Main.java");
        writeFileIn(root, "decoy-src/Main.java",
            "public final class Main {\n"
                + "  public static void main(String[] args) {\n"
                + "    System.out.println(\"" + DECOY_LINE + "\");\n"
                + "  }\n"
                + "}\n");
        Path decoyClasses = root.resolve("decoy-classes");
        Files.createDirectories(decoyClasses);
        ProcessOutcome decoyCompile = runProcess(List.of("javac", "--release", "25",
            "-proc:none", "-d", decoyClasses.toString(),
            decoySource.toString()), root);
        checkEq(0, decoyCompile.exitCode(),
            label + ": the collision-control decoy Main compiles: "
                + decoyCompile.output());
        return decoyClasses;
    }

    /**
     * The one artifact-outcome assertion: the status is zero, stdout equals
     * the oracle console transcript (or the pinned line when the oracle leg
     * failed), and stderr equals the oracle stderr transcript — asserted
     * separately, so an unexpected stderr transcript fails independently of
     * stdout.
     */
    private static void assertArtifactOutcome(String label, ProcessOutcome outcome,
            OracleTranscript oracle, String expectedStdout) {
        String expectedOut = oracle == null ? expectedStdout : oracle.stdout();
        String expectedErr = oracle == null ? "" : oracle.stderr();
        checkEq(0, outcome.exitCode(),
            label + ": the artifact exits 0: " + outcome.output());
        checkEq(expectedOut, outcome.stdout(),
            label + ": the artifact stdout equals the oracle console transcript");
        checkEq(expectedErr, outcome.stderr(),
            label + ": the artifact stderr equals the oracle stderr transcript");
        check(outcomeMatches(outcome, oracle, expectedStdout),
            label + ": the artifact outcome matches (status 0, oracle "
                + "transcript, empty stderr): " + outcome.output());
    }

    /** The single outcome comparison behind the artifact assertions and the
     * negative controls: status zero, the oracle transcript (or the pinned
     * line when the oracle leg failed), and an empty stderr. */
    private static boolean outcomeMatches(ProcessOutcome outcome,
            OracleTranscript oracle, String expectedStdout) {
        String expectedOut = oracle == null ? expectedStdout : oracle.stdout();
        String expectedErr = oracle == null ? "" : oracle.stderr();
        return outcome.exitCode() == 0
            && outcome.stdout().equals(expectedOut)
            && outcome.stderr().equals(expectedErr);
    }

    /**
     * The bounded-subprocess contract's focused checks: a hung child fails
     * hard with the named token after the deadline (never a skip), a
     * child writing past the cap on both streams completes with the retained
     * prefix, the truncation marker, and the flags set, and a completed
     * child whose capture did not reach a clean EOF is refused by name in
     * either stream position (its retained prefix is preserved for
     * diagnostics but never read as a complete transcript).
     */
    private static void testBoundedProcessContract() throws Exception {
        System.out.println("-- the bounded test-process helper: a hung child "
            + "fails hard with BOUNDED_PROCESS_TIMEOUT and a flooded child is "
            + "capped with the truncation marker --");

        long before = System.nanoTime();
        boolean timedOut = false;
        try {
            BoundedProcess.runCaptured(List.of("luajit", "-e",
                "while true do end"), null, Map.of(), 1_000L);
        } catch (BoundedProcess.BoundedProcessTimeoutException timeout) {
            timedOut = true;
            checkEq("BOUNDED_PROCESS_TIMEOUT luajit", timeout.getMessage(),
                "the timeout path names the child and the hard token");
        }
        long elapsedMs = (System.nanoTime() - before) / 1_000_000L;
        check(timedOut, "the hung child is killed at the deadline, not "
            + "skipped");
        check(elapsedMs < 60_000L, "the timeout path returns promptly after "
            + "the deadline: " + elapsedMs + " ms");

        // A direct child that exits while a descendant keeps its inherited
        // output write ends open must not bypass the bound: the helper owns
        // the child's process group, kills the surviving member, and reports
        // the named timeout instead of waiting for the descendant.
        long descendantBefore = System.nanoTime();
        boolean descendantTimedOut = false;
        try {
            BoundedProcess.runCaptured(List.of("sh", "-c",
                "sleep 90 & sleep 0.02; exit 0"), null, Map.of(), 1_000L);
        } catch (BoundedProcess.BoundedProcessTimeoutException timeout) {
            descendantTimedOut = true;
            checkEq("BOUNDED_PROCESS_TIMEOUT sh", timeout.getMessage(),
                "the descendant-holding-pipe path names the child and the "
                    + "hard token");
        }
        long descendantElapsedMs =
            (System.nanoTime() - descendantBefore) / 1_000_000L;
        check(descendantTimedOut,
            "a child that exits while a descendant holds the output write "
                + "ends still ends with the named timeout, not success");
        check(descendantElapsedMs < 30_000L,
            "the descendant path returns promptly after terminating the "
                + "owned process group: " + descendantElapsedMs + " ms");

        // A short-lived pipe-holding descendant must be drained, never
        // lost: the direct child exits before the descendant writes, and
        // the capture's kernel EOF arrives only once the descendant closes
        // the inherited write ends. Both a just-after-exit write (the
        // review's shape) and a later short-lived write are covered; the
        // shapes are repeated so a timing-dependent prefix-only result (the
        // JVM's pipe reclamation dropping the descendant's later output)
        // cannot pass as a capture. A named non-passing result is a failed
        // control, never the successful prefix.
        int shortLivedRound = 0;
        for (String delay : List.of("0.001", "0.05")) {
            for (int round = 0; round < 4; round++, shortLivedRound++) {
                BoundedProcess.BoundedResult shortLived;
                try {
                    shortLived = BoundedProcess.runCaptured(List.of("sh", "-c",
                        "(sleep " + delay + "; echo LATE_NOISE) & echo EXPECTED"),
                        null, Map.of(), 10_000L);
                } catch (BoundedProcess.BoundedProcessTimeoutException timeout) {
                    check(false, "the short-lived pipe-holder (" + delay + " s)"
                        + " must be a complete capture, not an incomplete"
                        + " infrastructure result (round " + shortLivedRound
                        + "): " + timeout.getMessage());
                    continue;
                }
                checkEq("EXPECTED\nLATE_NOISE\n", shortLived.stdout(),
                    "the short-lived pipe-holder's descendant output is"
                        + " captured completely, never the prefix (" + delay
                        + " s, round " + shortLivedRound + ")");
                check(shortLived.exitCode() == 0 && shortLived.stderr().isEmpty()
                        && !shortLived.stdoutTruncated()
                        && !shortLived.stderrTruncated(),
                    "the short-lived pipe-holder is an untruncated capture"
                        + " with its own exit status and empty stderr ("
                        + delay + " s, round " + shortLivedRound + ")");
            }
        }

        BoundedProcess.BoundedResult flood = BoundedProcess.runCaptured(
            List.of("luajit", "-e",
                "io.write(string.rep('x', 2 * 1024 * 1024)); "
                    + "io.stderr:write(string.rep('y', 2 * 1024 * 1024))"));
        checkEq(0, flood.exitCode(),
            "the flooding child completes (the capped drains never deadlock "
                + "a full pipe): " + flood.stderr());
        check(flood.stdoutTruncated() && flood.stderrTruncated(),
            "both streams are flagged truncated past the cap");
        checkEq(BoundedProcess.STREAM_CAP_BYTES,
            flood.stdout().length() - BoundedProcess.TRUNCATION_MARKER.length(),
            "the retained stdout is exactly the 1 MiB cap");
        checkEq(BoundedProcess.STREAM_CAP_BYTES,
            flood.stderr().length() - BoundedProcess.TRUNCATION_MARKER.length(),
            "the retained stderr is exactly the 1 MiB cap");
        check(flood.stdout().endsWith(BoundedProcess.TRUNCATION_MARKER)
                && flood.stderr().endsWith(BoundedProcess.TRUNCATION_MARKER),
            "both streams carry the launcher's truncation marker at the "
                + "cut point");

        // Capture honesty (the reproduced defect): a reader failure is
        // recorded, and a completed child whose capture did not reach a
        // clean EOF is refused by name even when the retained prefix already
        // matches the expected probe transcript. Both stream positions are
        // controlled, plus the clean-EOF control.
        String probePrefixText = "PROBE|ARRAY|ok\n";
        byte[] probePrefix = probePrefixText.getBytes(StandardCharsets.UTF_8);
        BoundedProcess.Drain failedBeforeOutput = new BoundedProcess.Drain(
            new FailingStream(new byte[0], "reader-failed-before-output"));
        failedBeforeOutput.run();
        check(failedBeforeOutput.failure() != null
                && !failedBeforeOutput.eofReached()
                && failedBeforeOutput.text().isEmpty(),
            "a stream failing before output records the read failure, no "
                + "EOF, and no transcript: " + failedBeforeOutput.failure());
        BoundedProcess.Drain failedAfterPrefix = new BoundedProcess.Drain(
            new FailingStream(probePrefix, "reader-failed-after-prefix"));
        failedAfterPrefix.run();
        check(probePrefixText.equals(failedAfterPrefix.text())
                && failedAfterPrefix.failure() != null
                && !failedAfterPrefix.eofReached(),
            "a stream failing after the expected probe prefix keeps the "
                + "matching transcript but still records the failure and no "
                + "EOF: " + failedAfterPrefix.failure());
        BoundedProcess.Drain cleanEof = new BoundedProcess.Drain(
            new ByteArrayInputStream(probePrefix));
        cleanEof.run();
        check(cleanEof.failure() == null && cleanEof.eofReached()
                && probePrefixText.equals(cleanEof.text())
                && !cleanEof.truncated(),
            "the clean-EOF control reaches EOF with no read failure");
        for (BoundedProcess.Drain failing
                : List.of(failedBeforeOutput, failedAfterPrefix)) {
            AssertionError stdoutRefusal =
                BoundedProcess.captureFailure("probe", failing, cleanEof);
            check(stdoutRefusal != null && stdoutRefusal.getMessage() != null
                    && stdoutRefusal.getMessage()
                        .startsWith("BOUNDED_PROCESS_DRAIN_FAILURE probe")
                    && stdoutRefusal.getMessage().contains("stdout")
                    && stdoutRefusal.getMessage()
                        .contains(failing.failure().getMessage()),
                "a stdout capture failure is refused by name: " + stdoutRefusal);
            AssertionError stderrRefusal =
                BoundedProcess.captureFailure("probe", cleanEof, failing);
            check(stderrRefusal != null && stderrRefusal.getMessage() != null
                    && stderrRefusal.getMessage()
                        .startsWith("BOUNDED_PROCESS_DRAIN_FAILURE probe")
                    && stderrRefusal.getMessage().contains("stderr")
                    && stderrRefusal.getMessage()
                        .contains(failing.failure().getMessage()),
                "a stderr capture failure is refused by name: " + stderrRefusal);
        }
        check(BoundedProcess.captureFailure("probe", cleanEof, cleanEof) == null,
            "the clean-EOF control is not refused (both streams complete)");
        for (int size : List.of(BoundedProcess.STREAM_CAP_BYTES - 1,
                BoundedProcess.STREAM_CAP_BYTES, BoundedProcess.STREAM_CAP_BYTES + 1)) {
            BoundedProcess.Drain drain = new BoundedProcess.Drain(
                new ByteArrayInputStream(new byte[size]));
            drain.run();
            boolean truncated = size > BoundedProcess.STREAM_CAP_BYTES;
            check(drain.eofReached() && drain.failure() == null
                    && drain.truncated() == truncated,
                "the shared drain honors the exact cap boundary: " + size);
            checkEq(Math.min(size, BoundedProcess.STREAM_CAP_BYTES)
                    + (truncated ? BoundedProcess.TRUNCATION_MARKER.length() : 0),
                drain.text().length(), "the retained boundary transcript length");
        }
        BoundedProcess.Drain unfinished = new BoundedProcess.Drain(
            new ByteArrayInputStream(new byte[0]));
        check(BoundedProcess.captureFailure("probe", unfinished, cleanEof) != null
                && BoundedProcess.captureFailure("probe", cleanEof, unfinished) != null,
            "a capture without EOF is refused in either stream position");
        testBoundedProcessCancellation();
    }

    private static void testBoundedProcessCancellation() throws Exception {
        Path root = Files.createTempDirectory("bounded controls with spaces ");
        List<Thread> workers = new ArrayList<>();
        Throwable[] observed = new Throwable[2];
        try {
            for (int i = 0; i < observed.length; i++) {
                int index = i;
                Path directory = root.resolve("worker " + i);
                Files.createDirectories(directory);
                Thread worker = new Thread(() -> {
                    try {
                        BoundedProcess.runCaptured(List.of("sh", "-c",
                            "echo $$ > child.pid; sleep 30 & echo $! > descendant.pid; wait"),
                            directory, Map.of(), 5_000L);
                        observed[index] = new AssertionError("cancelled call returned");
                    } catch (Throwable failure) {
                        observed[index] = failure;
                    }
                });
                workers.add(worker);
                worker.start();
            }
            long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            for (int i = 0; i < workers.size(); i++) {
                Path pid = root.resolve("worker " + i).resolve("descendant.pid");
                while ((!Files.isRegularFile(pid) || Files.size(pid) == 0)
                        && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                check(Files.isRegularFile(pid) && Files.size(pid) > 0,
                    "the cancellation control launched its descendant " + i);
            }
            workers.forEach(Thread::interrupt);
            for (int i = 0; i < workers.size(); i++) {
                workers.get(i).join(30_000L);
                check(!workers.get(i).isAlive()
                        && observed[i] instanceof InterruptedException,
                    "concurrent cancellation propagates after teardown: " + observed[i]);
                for (String file : List.of("child.pid", "descendant.pid")) {
                    Path pidFile = root.resolve("worker " + i).resolve(file);
                    if (Files.isRegularFile(pidFile)) {
                        long pid = Long.parseLong(Files.readString(pidFile).trim());
                        check(ProcessHandle.of(pid).map(handle -> !handle.isAlive())
                            .orElse(true), "cancelled process is gone: " + file + " " + pid);
                    }
                }
            }
            check(Thread.getAllStackTraces().keySet().stream().noneMatch(thread ->
                    thread.isAlive() && (thread.getName().equals("bounded-process-stdout")
                        || thread.getName().equals("bounded-process-stderr"))),
                "no bounded capture reader survives concurrent cancellation");
            BoundedProcess.BoundedResult next = BoundedProcess.runCaptured(
                List.of("sh", "-c", "printf recovered; exit 7"), root);
            check(next.exitCode() == 7 && next.stdout().equals("recovered")
                    && next.stderr().isEmpty(),
                "a real invocation after cancellation retains status and transcript");
        } finally {
            for (Thread worker : workers) {
                if (worker.isAlive()) {
                    worker.interrupt();
                    worker.join(30_000L);
                }
            }
            deleteRecursively(root);
        }
    }

    private static void testJvmCompilationContract() throws Exception {
        Path root = Files.createTempDirectory("guard-javac-contract-");
        String className = "GuardJavac" + java.util.UUID.randomUUID()
            .toString().replace("-", "");
        try {
            Path first = root.resolve("first");
            Path second = root.resolve("second");
            writeFileIn(first, "src/" + className + ".java",
                "public final class " + className + " {"
                    + " static int value = 17;"
                    + " public static void main(String[] args) {"
                    + " System.out.println(value); } }");
            writeFileIn(second, "src/" + className + ".java",
                "public final class " + className + " {"
                    + " static String value() { return \"second\"; }"
                    + " public static void main(String[] args) {"
                    + " System.out.println(value()); } }");
            assertJvmCompilation("first incompatible source",
                compileJvmProbe(first, className, 30_000L));
            List<ProcessHandle> workers = jvmCompilationWorkers();
            checkEq(1, workers.size(), "one real javac worker is running");
            assertJvmCompilation("second incompatible source",
                compileJvmProbe(second, className, 30_000L));
            checkEq(workers.stream().map(ProcessHandle::pid).toList(),
                jvmCompilationWorkers().stream().map(ProcessHandle::pid).toList(),
                "both incompatible sources compile through the same worker");
            assertJvmEntrypoint("first isolated output", first, className, "17");
            assertJvmEntrypoint("second isolated output", second, className,
                "second");

            Path invalid = root.resolve("invalid");
            writeFileIn(invalid, "src/" + className + ".java",
                "public final class " + className + " {"
                    + " public static void main(String[] args) {"
                    + " System.out.println(missingValue); } }");
            JvmCompilation.Result rejected = compileJvmProbe(invalid, className,
                30_000L);
            checkEq(1, rejected.exitCode(), "invalid Java source fails compilation");
            check(rejected.stderr().contains("missingValue"),
                "invalid Java source returns its actual compiler diagnostic: "
                    + rejected.stderr());
            check(!Files.exists(invalid.resolve("classes/" + className + ".class")),
                "invalid Java source yields no class");
            writeFileIn(invalid, "src/" + className + ".java",
                "public final class " + className + " {"
                    + " public static void main(String[] args) {"
                    + " System.out.println(\"valid-after-error\"); } }");
            assertJvmCompilation("valid compilation after invalid source",
                compileJvmProbe(invalid, className, 30_000L));
            assertJvmEntrypoint("valid compilation after invalid source", invalid,
                className, "valid-after-error");

            testJvmCompilationDependencies(root, first, second, className);
            testJvmCompilationJarOverwrite(root, first, second, className);
            testJvmCompilationTimeout(root.resolve("timeout"), className);
            testJvmCompilationInterruption(root.resolve("interrupted"), className);
        } finally {
            deleteRecursively(root);
        }
    }

    private static void testJvmCompilationDependencies(Path root, Path first,
            Path second, String className) throws Exception {
        String consumer = className + "Consumer";
        String source = "public final class " + consumer + " {"
            + " public static void main(String[] args) {"
            + " System.out.println(" + className + ".value); } }";
        Path caller = root.resolve("dependency-caller");
        writeFileIn(caller, "src/" + consumer + ".java", source);
        assertJvmCompilation("explicit first dependency classpath",
            compileJvmProbe(caller, consumer, 30_000L, first.resolve("classes")));
        assertJvmEntrypoint("explicit first dependency classpath", caller,
            consumer, "17", first.resolve("classes"));

        Path missing = root.resolve("dependency-missing");
        writeFileIn(missing, "src/" + consumer + ".java", source);
        JvmCompilation.Result inherited = compileJvmProbe(missing, consumer, 30_000L);
        checkEq(1, inherited.exitCode(), "no -cp does not inherit the previous classpath");
        check(inherited.stderr().contains(className),
            "no -cp reports the absent dependency: " + inherited.stderr());
        check(!Files.exists(missing.resolve("classes/" + consumer + ".class")),
            "no -cp emits no consumer class when its dependency is absent");

        JvmCompilation.Result changed = compileJvmProbe(caller, consumer,
            30_000L, second.resolve("classes"));
        checkEq(1, changed.exitCode(), "changed -cp rejects the old dependency API");
        check(changed.stderr().contains("value"),
            "changed -cp reports the missing field: " + changed.stderr());
        writeFileIn(caller, "src/" + consumer + ".java", source.replace(
            className + ".value", className + ".value()"));
        assertJvmCompilation("explicit second dependency classpath",
            compileJvmProbe(caller, consumer, 30_000L, second.resolve("classes")));
        assertJvmEntrypoint("explicit second dependency classpath", caller,
            consumer, "second", second.resolve("classes"));
    }

    private static void testJvmCompilationJarOverwrite(Path root, Path first,
            Path second, String className) throws Exception {
        String dependency = className + "Dependency";
        String consumer = className + "JarConsumer";
        Path jar = root.resolve("dependency.jar");
        Path caller = root.resolve("jar-caller");
        writeFileIn(caller, "src/" + consumer + ".java",
            "public final class " + consumer + " {"
                + " public static void main(String[] args) {"
                + " System.out.println(" + dependency + ".VALUE); } }");
        List<Long> workers = jvmCompilationWorkers().stream()
            .map(ProcessHandle::pid).toList();
        byte[] previous = null;
        for (int version = 0; version < 2; version++) {
            Path artifacts = version == 0 ? first : second;
            String value = version == 0 ? "jar-first" : "jar-second";
            writeFileIn(artifacts, "src/" + dependency + ".java",
                "public final class " + dependency + " {"
                    + " public static final String VALUE = \"" + value + "\"; }");
            assertJvmCompilation(value + " dependency",
                compileJvmProbe(artifacts, dependency, 30_000L));
            ProcessOutcome packed = runProcess(List.of("jar", "--create", "--file",
                jar.toString(), "-C", artifacts.resolve("classes").toString(),
                dependency + ".class"), root);
            checkEq(0, packed.exitCode(), value + ": real jar creation succeeds: "
                + packed.output());
            byte[] current = Files.readAllBytes(jar);
            if (previous != null) {
                check(!Arrays.equals(previous, current),
                    "the dependency JAR bytes change at the exact same path");
            }
            previous = current;
            assertJvmCompilation(value + " consumer",
                compileJvmProbe(caller, consumer, 30_000L, jar));
            assertJvmEntrypoint(value + " inlined dependency constant", caller,
                consumer, value);
        }
        checkEq(workers, jvmCompilationWorkers().stream()
            .map(ProcessHandle::pid).toList(),
            "same-path JAR replacement is observed without restarting the worker");
    }

    private static void testJvmCompilationTimeout(Path root, String className)
            throws Exception {
        writeJvmCompilationWorkload(root, className, "valid-after-timeout");
        List<ProcessHandle> workers = jvmCompilationWorkers();
        checkEq(1, workers.size(), "timeout starts with a live javac worker");
        long before = System.nanoTime();
        boolean timedOut = false;
        try {
            compileJvmProbe(root, className, 1L);
        } catch (IOException failure) {
            timedOut = true;
            check(failure.getCause() instanceof java.util.concurrent.TimeoutException,
                "the 1ms deadline throws IOException caused by timeout: " + failure);
            checkEq("javac compilation exceeded 1ms", failure.getMessage(),
                "the timeout names the actual compilation deadline");
        }
        long elapsedMs = (System.nanoTime() - before) / 1_000_000L;
        check(timedOut, "the real compiler workload exceeds the 1ms deadline");
        check(elapsedMs < 15_000L,
            "timeout cleanup returns within its upper margin: " + elapsedMs + " ms");
        for (ProcessHandle worker : workers) {
            check(!worker.isAlive(), "the timed-out javac worker is killed");
            worker.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
            check(ProcessHandle.current().children()
                    .noneMatch(child -> child.pid() == worker.pid()),
                "the timed-out javac worker is reaped");
        }
        check(jvmCompilationWorkers().isEmpty(),
            "timeout leaves no live javac worker");
        assertJvmCompilation("valid compilation after timeout",
            compileJvmProbe(root, className, 30_000L));
        check(jvmCompilationWorkers().stream().noneMatch(worker -> workers.stream()
                .anyMatch(previous -> previous.pid() == worker.pid())),
            "compilation after timeout starts a fresh worker");
        assertJvmEntrypoint("valid compilation after timeout", root, className,
            "valid-after-timeout");
    }

    private static void testJvmCompilationInterruption(Path root, String className)
            throws Exception {
        writeJvmCompilationWorkload(root, className, "valid-after-interruption");
        long before = System.nanoTime();
        boolean interrupted = false;
        try {
            Thread.currentThread().interrupt();
            try {
                compileJvmProbe(root, className, 30_000L);
            } catch (InterruptedException failure) {
                interrupted = true;
                check(Thread.currentThread().isInterrupted(),
                    "compilation preserves the interrupted caller's flag");
            }
            check(interrupted, "a pre-interrupted caller gets InterruptedException");
            check((System.nanoTime() - before) / 1_000_000L < 15_000L,
                "interruption cleanup returns within its upper margin");
        } finally {
            Thread.interrupted();
        }
        check(!Thread.currentThread().isInterrupted(),
            "the interruption regression clears its flag in finally");
        assertJvmCompilation("valid compilation after interruption",
            compileJvmProbe(root, className, 30_000L));
        assertJvmEntrypoint("valid compilation after interruption", root, className,
            "valid-after-interruption");
    }

    private static void writeJvmCompilationWorkload(Path root, String className,
            String line) throws Exception {
        StringBuilder source = new StringBuilder("public final class ")
            .append(className).append(" {");
        for (int i = 0; i < 1_000; i++) {
            source.append(" static int value").append(i)
                .append("(int n) { return n + ").append(i).append("; }");
        }
        source.append(" public static void main(String[] args) {")
            .append(" System.out.println(\"").append(line).append("\"); } }");
        writeFileIn(root, "src/" + className + ".java", source.toString());
    }

    private static JvmCompilation.Result compileJvmProbe(Path root,
            String className, long timeoutMillis, Path... dependencies) throws Exception {
        Files.createDirectories(root.resolve("classes"));
        List<String> command = new ArrayList<>(List.of("javac", "--release", "25",
            "-proc:none", "-d", "classes"));
        if (dependencies.length > 0) {
            command.addAll(List.of("-cp", Arrays.stream(dependencies)
                .map(path -> path.toAbsolutePath().normalize().toString())
                .collect(java.util.stream.Collectors.joining(File.pathSeparator))));
        }
        command.add("src/" + className + ".java");
        return JvmCompilation.compile(command, root, timeoutMillis);
    }

    private static void assertJvmCompilation(String label,
            JvmCompilation.Result result) {
        checkEq(0, result.exitCode(), label + ": compilation succeeds: "
            + result.stderr());
        checkEq("", result.stdout(), label + ": compilation has no stdout");
        checkEq("", result.stderr(), label + ": compilation has no diagnostics");
    }

    private static void assertJvmEntrypoint(String label, Path root,
            String className, String line, Path... dependencies) throws Exception {
        Path classes = root.resolve("classes");
        check(Files.isRegularFile(classes.resolve(className + ".class")),
            label + ": relative -d selects the requested output directory");
        String classpath = Stream.concat(Stream.of(classes), Arrays.stream(dependencies))
            .map(path -> path.toAbsolutePath().normalize().toString())
            .collect(java.util.stream.Collectors.joining(File.pathSeparator));
        BoundedProcess.BoundedResult result = BoundedProcess.runCaptured(
            List.of("java", "-cp", classpath + File.pathSeparator
                + absoluteClasspath(), className), root, Map.of(), 15_000L);
        checkEq(0, result.exitCode(), label + ": actual entrypoint exits zero: "
            + result.stderr());
        checkEq(line + "\n", result.stdout(),
            label + ": actual entrypoint emits its distinct value");
        checkEq("", result.stderr(), label + ": actual entrypoint has no stderr");
    }

    private static List<ProcessHandle> jvmCompilationWorkers() {
        try (Stream<ProcessHandle> children = ProcessHandle.current().children()) {
            return children.filter(child -> child.info().arguments()
                .map(arguments -> Arrays.asList(arguments)
                    .contains(JvmCompilation.class.getName()))
                .orElse(false)).toList();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Pre-populates the live output root with a previous artifact set. */
    private static Map<String, String> seedPreviousArtifactSet(Path out)
            throws Exception {
        writeFileIn(out, "main.lua", "-- previous artifact\n");
        writeFileIn(out, "deal/runtime.lua", "-- previous runtime\n");
        Map<String, String> before = snapshotTree(out);
        check(!before.isEmpty(), "the previous artifact set is seeded");
        return before;
    }

    /** The published/previous artifact set of one tree: path → content. */
    private static Map<String, String> snapshotTree(Path root) throws Exception {
        Map<String, String> snapshot = new TreeMap<>();
        if (!Files.exists(root)) {
            return snapshot;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.sorted().toList()) {
                if (Files.isRegularFile(file)) {
                    snapshot.put(root.relativize(file).toString().replace('\\', '/'),
                        Arrays.toString(Files.readAllBytes(file)));
                }
            }
        }
        return snapshot;
    }

    private record ProcessOutcome(int exitCode, String stdout, String stderr) {

        String output() {
            return "stdout=" + stdout.replace("\n", "\\n")
                + " stderr=" + stderr.replace("\n", "\\n");
        }
    }

    /**
     * A stream that emits a fixed prefix and then throws instead of EOF
     * (the focused control of the drain's clean-EOF contract): the retained
     * prefix of the second shape is byte-exact for the probe transcript, so
     * only the recorded reader failure can refuse it.
     */
    private static final class FailingStream extends InputStream {
        private final byte[] prefix;
        private final String reason;
        private int index;

        FailingStream(byte[] prefix, String reason) {
            this.prefix = prefix;
            this.reason = reason;
        }

        @Override
        public int read() throws IOException {
            if (index < prefix.length) {
                return prefix[index++] & 0xff;
            }
            throw new IOException(reason);
        }
    }

    private record ProjectOutcome(int exitCode, String stderr) {
    }

    /** Runs the release-owned production CLI in-process. */
    private static ProjectOutcome runProductionCli(String... args)
            throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        int exitCode;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exitCode = deal.Main.run(args);
            System.err.flush();
        } finally {
            System.setErr(originalErr);
        }
        return new ProjectOutcome(exitCode,
            err.toString(StandardCharsets.UTF_8).trim());
    }

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

    private static ProcessOutcome runProcess(List<String> command, Path workDir)
            throws Exception {
        BoundedProcess.BoundedResult result = BoundedProcess.runCaptured(
            command, workDir);
        return new ProcessOutcome(result.exitCode(), result.stdout(),
            result.stderr());
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
                try (Stream<Path> walk = Files.walk(path)) {
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
        System.out.println("=== Fail-Closed Guard Retargeting Tests "
            + "(ISSUE-0702) ===\n");
        testConstructUnloweredSeed();
        testRetainedAbiDeferredSeed();

        testRealizedConstructProbes();
        testBoundedProcessContract();
        testJvmCompilationContract();
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== Fail-Closed Guard Retargeting Tests Passed ===");
    }
}
