package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmBackend;
import deal.codegen.jvm.JvmSemanticEmitter;
import deal.codegen.lua.LuaSemanticEmitter;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.distribution.DistributionHome;
import deal.module.CompilationOrchestrator;
import deal.module.ProductionProjectEmission;
import deal.project.ProjectContext;
import deal.project.ProjectLocator;
import deal.publication.PublicationStager;
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
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.CallMode;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.ExportInterface;
import deal.semantic.ir.FunctionAllocationIdentity;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredFunction;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;
import deal.test.conformance.ErrorSnapshot;
import deal.test.conformance.JvmCompilation;
import deal.test.conformance.SidecarExpectations;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * ISSUE-0698: the {@code @jsonable} helper family's production drive
 * ({@code dispatched-corpus-production-realization} R3 and the helper
 * contract; ISSUE-0698's criteria).
 *
 * <p>One drive compiles every fixture of the family through the
 * release-owned production invocation on LuaJIT and JVM and executes each
 * published artifact under its real toolchain: the sidecar-bearing fixtures
 * of {@code backend-runtime/jsonable/**} (the current corpus discovery, not
 * a frozen inventory), the two structural fixtures
 * ({@code lua-abi/jsonable-helper-name-near-collision},
 * {@code lua-abi-structural/jsonable-helper-export-keys}), and the
 * family's pinned compile-error fixture
 * ({@code jsonable-minimal-table-nested-array-access}, E3001 at the
 * frontend — no sidecar). The {@code runtime-ok} fixtures must publish
 * exit 0 with empty transcripts; the pinned {@code runtime-error} fixture
 * ({@code jsonable-tojson-rejects-cyclic-table}) must reproduce its
 * sidecar's {@code code}, {@code message}, span group, and
 * {@code expected}/{@code actual} byte-exact, with the materialized line
 * rebased to the raw corpus coordinate.</p>
 *
 * <p>The drive also pins the helper surface in the produced unit: exactly
 * one closure identity and one {@code LoweredBody} binding per class
 * helper, one {@code EXPORT_PUBLISH} and one recorded
 * {@code EXTERNAL_ENTRY} per exported helper, the same-module call that
 * resolves the declaration's generated closure identity through the
 * landed {@code LoweredBody} call shape, and the cross-module call bound
 * through {@code EXPORT_READ} plus the closed {@code ExternalFunction}
 * shape to the callee's recorded entry. A declared class that is not
 * {@code @jsonable} gains no helper, and a user identifier near a helper
 * name changes no outcome.</p>
 *
 * <p>The three-consumer regressions close the walk's remaining arms: a
 * function-parameter invocation of a helper ({@code f(w)} inside the
 * callee body) must render the pinned JSON failure at the {@code f(w)}
 * call expression in the oracle, the LuaJIT artifact, and the JVM
 * artifact alike; a class instance inside an array nested in a table
 * field must be rejected by all three consumers (the table-content walk
 * stays JSON-shaped); a nested class's table field must spell its keys in
 * first-insertion order and its int/number leaves by their own variant
 * (the exact text compared on all three consumers); the recursive
 * nested-class walk has no fixed depth cap (511 and 1200 chain links both
 * succeed with the exact text; there is no
 * container's token and path); the oracle's array executor view must stay
 * live behind a class field's in-place element replacement, append, and
 * deletion (exact text for replacement and append, outcome parity for the
 * deleted missing slot); a committed field write on a class instance held
 * as another class's declared field or as an array element must be
 * serialized by the parent's helper call as the committed state (exact
 * text on all three consumers, including a second parent alias of the same
 * child identity); a null element in {@code (int | null)[]} must
 * roundtrip on all three consumers while the same document stays rejected
 * by {@code int[]}; a read-derived numeric variant carrier must
 * serialize at a declared {@code number} field and array element with its
 * own variant's spelling, and the same carrier produced by an omitted
 * nested-class default must conform at its declared {@code number},
 * {@code number | null}, and {@code number[]} positions (with its
 * explicit-value control). The pinned-failure leg's comparison is itself
 * covered by negative controls: unexpected process stdout, an incorrect
 * process exit status, or wrong stderr is rejected, and the exact-text
 * artifact leg is only credited when the process completes its tested body
 * with exit 0, empty transcripts, no probe defect, and no failure transport
 * (the nonzero-without-transport control included), and the accepted-depth
 * artifact leg consumes that same completed-process credit — a nonzero exit
 * without a transport file can never be credited as a completed deep
 * encode.</p>
 *
 * <p>The combined dependency step runs the oracle over every fixture of
 * the family through the same one-lowering closure (the runtime-ok
 * success or the pinned error tuple at the raw corpus coordinates), and
 * captures the cross-module helper call and the pinned helper JSON
 * failure from the lowered project through {@link SemanticOracle}, so the
 * comparison fails if the canonical failure projection authority is
 * broken. A fixture's {@code main} runs through the oracle's main leg (the
 * fixture itself lowered as the entry, so its own entry delegation invokes
 * {@code main} exactly once — the artifact probes' sequence) before the
 * export leg's ordered non-main export loop, and a main-only control
 * proves the leg captures a deliberate main failure. The inconsistent-fact
 * negatives cover the missing generated helper export and the missing
 * recorded callee entry: the hand-built checked-project fact fails the
 * real production arm closed with one E6005 through the producer guard and
 * stages nothing on both targets, a checker-valid cross-module call to an
 * entry-delegation-owned export fails the arm closed the same way, and the
 * same helper facts at the lowered-IR level (a removed
 * {@code EXPORT_PUBLISH} publication and a removed recorded
 * {@code EXTERNAL_ENTRY}) are rejected by both production emitters with
 * the named producer defect and stage nothing.</p>
 */
public class JsonableHelperProductionDriveTest {

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
    // The family
    // =========================================================================

    private static final Path CONFORMANCE = Path.of("test", "conformance");
    private static final String FAMILY_DIR = "backend-runtime/jsonable";
    private static final String NEAR_COLLISION =
        "backend-runtime/lua-abi/jsonable-helper-name-near-collision";
    private static final String EXPORT_KEYS =
        "backend-runtime/lua-abi-structural/jsonable-helper-export-keys";
    private static final String COMPILE_REJECT =
        FAMILY_DIR + "/jsonable-minimal-table-nested-array-access";

    /**
     * The driven family: the fixtures of {@code backend-runtime/jsonable}
     * discovered from the current corpus revision — every {@code .deal} file
     * that carries a sidecar. The discovery is the corpus rule, never a
     * frozen inventory: an owner-authorized fixture addition or cut changes
     * the driven set without editing this drive. Companions (relative
     * imports, no sidecar of their own) and the sidecar-free compile-reject
     * fixture are excluded by the sidecar rule and stay covered by their own
     * focused checks below.
     */
    private static List<String> discoveredFamilyFixtures() throws Exception {
        List<String> fixtures = new ArrayList<>();
        try (Stream<Path> entries = Files.list(CONFORMANCE.resolve(FAMILY_DIR))) {
            for (Path file : entries.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".deal")) {
                    continue;
                }
                String stem = name.substring(0, name.length() - ".deal".length());
                if (Files.isRegularFile(CONFORMANCE.resolve(
                        FAMILY_DIR + "/" + stem + ".expect.json"))) {
                    fixtures.add(stem);
                }
            }
        }
        return fixtures;
    }

    /** The pinned runtime-error fixture (the toJson call origin pin). */
    private static final String CYCLIC_FIXTURE =
        FAMILY_DIR + "/jsonable-tojson-rejects-cyclic-table";

    /** The cross-module helper call fixture. */
    private static final String CROSS_MODULE_FIXTURE = FAMILY_DIR + "/jsonable-cross-module";

    private enum Target {
        LUAJIT("luajit"),
        JVM("jvm");

        private final String laneName;

        Target(String laneName) {
            this.laneName = laneName;
        }

        String laneName() {
            return laneName;
        }
    }

    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    // =========================================================================
    // 1. The corpus inventory
    // =========================================================================

    private static void testCorpusInventory(List<String> discovered) {
        check(!discovered.isEmpty(),
            "the jsonable directory discovers at least one driven fixture");
        check(discovered.contains("jsonable-tojson-rejects-cyclic-table"),
            CYCLIC_FIXTURE + " is discovered as a driven fixture");
        check(discovered.contains("jsonable-cross-module"),
            CROSS_MODULE_FIXTURE + " is discovered as a driven fixture");
    }

    // =========================================================================
    // 2. The lane-equivalent materialization
    // =========================================================================

    private record Materialized(String corpusPath, String source,
                                int headerLinesStripped) {
    }

    private record Project(Path root, Path srcRoot, Path entryFile, String modulePath,
                           Map<String, Materialized> modules, Path outputRoot) {

        Materialized moduleOf(String capturedFile) {
            if (capturedFile == null) {
                return null;
            }
            Materialized direct = modules.get(capturedFile);
            if (direct != null) {
                return direct;
            }
            return modules.get(Path.of(capturedFile).toAbsolutePath().normalize()
                .toString());
        }
    }

    /**
     * Materializes one fixture as the lane does: the fixture is the entry
     * module, its transitively imported relative companions sit at the
     * entry-directory-relative layout, the classification headers are
     * stripped (the deployment map's rebase delta), and one generated
     * {@code deal.json} wires the module root, the output, and the target
     * backend. The family imports no host or extern-C declarations, so the
     * externals surface is empty.
     */
    private static Project materialize(String fixtureRel, Target target)
            throws Exception {
        return materialize(fixtureRel, target, Map.of());
    }

    /**
     * The overridable materialization core: {@code rawOverrides} replaces
     * one corpus path's raw bytes (the raw-profile control's injection
     * point) while the disk layout and metadata handling stay the
     * production ones. The raw classification metadata of every module is
     * validated before that module's bytes are written, parsed or
     * compiled, and a rejected input removes the partially created scratch
     * root before the defect propagates.
     */
    private static Project materialize(String fixtureRel, Target target,
            Map<String, String> rawOverrides) throws Exception {
        Path root = Files.createTempDirectory("jsonable-prod-drive-");
        try {
            return materializeInto(root, fixtureRel, target, rawOverrides);
        } catch (Exception failure) {
            deleteRecursively(root);
            throw failure;
        }
    }

    private static Project materializeInto(Path root, String fixtureRel,
            Target target, Map<String, String> rawOverrides) throws Exception {
        Path srcRoot = root.resolve("src");
        Files.createDirectories(srcRoot);
        String corpusDir = fixtureRel.substring(0,
            fixtureRel.lastIndexOf('/') + 1);
        Path entryCorpus = CONFORMANCE.resolve(fixtureRel + ".deal")
            .toAbsolutePath().normalize();
        Path entryDir = entryCorpus.getParent();
        Map<String, Materialized> modules = new LinkedHashMap<>();
        for (String corpusPath : relativeClosureOf(corpusDir, fixtureRel)) {
            Path corpusFile = CONFORMANCE.resolve(corpusPath);
            String raw = rawOverrides.getOrDefault(corpusPath,
                Files.readString(corpusFile, StandardCharsets.UTF_8));
            Materialized materialized = materializedModule(corpusPath, raw);
            String stripped = materialized.source();
            String fileName = corpusFile.getFileName().toString();
            Path targetPath = srcRoot.resolve(fileName);
            Files.writeString(targetPath, stripped, StandardCharsets.UTF_8);
            modules.put(targetPath.toAbsolutePath().normalize().toString(),
                materialized);
        }
        String entryStem = moduleNameOf(entryCorpus.getFileName().toString());
        String dealJson = "{\n  \"languageVersion\": \"1.5\",\n"
            + "  \"moduleRoots\": [\"src\"],\n"
            + "  \"output\": \"out\",\n"
            + "  \"backend\": \"" + (target == Target.JVM ? "jvm" : "luajit")
            + "\"\n}\n";
        Files.writeString(root.resolve("deal.json"), dealJson);
        return new Project(root, srcRoot, srcRoot.resolve(entryStem + ".deal"),
            entryStem, modules, root.resolve("out"));
    }

    /**
     * One corpus module's materialization: the original classification
     * metadata is validated against the production profile (CD3) before
     * the headers are stripped, so a non-production or malformed
     * declaration is a named gate-input defect raised before any export
     * parsing, materialization or compilation — never a compile, pass or
     * skip under a metadata-selected invocation.
     */
    private static Materialized materializedModule(String corpusPath,
            String raw) {
        String stripped = ConformanceHarnessMetadata
            .productionCorpusSource(raw, corpusPath);
        int headers = raw.split("\n", -1).length - stripped.split("\n", -1).length;
        return new Materialized(corpusPath, stripped, headers);
    }

    /**
     * The fixture's transitively imported relative companion closure: the
     * corpus-relative {@code .deal} paths, dependency-first.
     */
    private static List<String> relativeClosureOf(String corpusDir, String rootCorpusPath)
            throws Exception {
        List<String> ordered = new ArrayList<>();
        collectRelativeClosure(corpusDir, rootCorpusPath + ".deal", ordered,
            new LinkedHashSet<>());
        return ordered;
    }

    private static void collectRelativeClosure(String corpusDir, String corpusPath,
            List<String> ordered, Set<String> seen) throws Exception {
        if (!seen.add(corpusPath)) {
            return;
        }
        String stripped = ConformanceHarnessMetadata.productionCorpusSource(
            Files.readString(CONFORMANCE.resolve(corpusPath), StandardCharsets.UTF_8),
            corpusPath);
        for (String importPath : relativeImportPaths(stripped)) {
            String candidate = corpusDir + importPath + ".deal";
            if (Files.isRegularFile(CONFORMANCE.resolve(candidate))) {
                collectRelativeClosure(corpusDir, candidate, ordered, seen);
            }
        }
        ordered.add(corpusPath);
    }

    private static List<String> relativeImportPaths(String source) {
        List<String> paths = new ArrayList<>();
        Matcher matcher = Pattern
            .compile("^import \\* as \\w+ from \"\\./([^\"]+)\";$", Pattern.MULTILINE)
            .matcher(source);
        while (matcher.find()) {
            paths.add(matcher.group(1));
        }
        return paths;
    }

    private static String moduleNameOf(String fileName) {
        if (fileName.endsWith(".deal")) {
            return fileName.substring(0, fileName.length() - ".deal".length());
        }
        return fileName;
    }

    // =========================================================================
    // 3. The production compile and the deferred-entry drive
    // =========================================================================

    private record Compilation(boolean compiled, List<String> diagnostics,
                               CheckedProjectBuildResult built,
                               RequirementManifestResult manifests,
                               HostDeclarationSurface surface,
                               ExecutableLoweredProject producedProject) {
    }

    private static Compilation compileObservation(Project project, String fixtureRel,
            Target target) throws Exception {
        ProjectLocator.LocateResult located = ProjectLocator.locate(
            project.entryFile().toString(), new deal.project.CliOverrides(target.laneName(),
                project.outputRoot().toString()));
        if (located.context() == null) {
            check(false, fixtureRel + " [" + target.laneName()
                + "]: the generated deal.json locates strictly");
            return new Compilation(false, List.of(), null, null, null, null);
        }
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            located.context(), project.entryFile(), false, false, false, false,
            null, productionInvocation());
        boolean compiled = orchestrator.compile();
        List<String> diagnostics = new ArrayList<>();
        for (CompilerDiagnostic diagnostic : orchestrator.diagnostics()) {
            if ("error".equals(diagnostic.severity())) {
                diagnostics.add(diagnostic.code() + " " + diagnostic.message());
            }
        }
        return new Compilation(compiled, List.copyOf(diagnostics),
            orchestrator.checkedProject(), orchestrator.requirementManifests(),
            orchestrator.hostDeclarationSurface(), orchestrator.producedProject());
    }

    private static Compilation emitChecked(Project project, Compilation preparation,
            Target target) throws Exception {
        if (!preparation.compiled()) {
            return preparation;
        }
        CheckedProjectBuildResult built = preparation.built();
        try (PublicationStager stager = PublicationStager.forRoot(project.outputRoot())) {
            ProductionProjectEmission.Result result = ProductionProjectEmission.run(
                productionInvocation(), built.input(), built.index(),
                preparation.manifests().manifests(), preparation.surface(), Map.of(),
                BuiltinErrorDeclaration.synthesized(
                    built.input().modules().get(0).ast().span()),
                java.util.Set.of(), target == Target.JVM ? Backend.JVM : Backend.LUAJIT, false,
                DistributionHome.forManifestDirectory(project.root().toString()), stager);
            if (result.emitted()) {
                stager.publish();
            }
            return new Compilation(result.emitted(), result.diagnostics().stream()
                .filter(d -> "error".equals(d.severity()))
                .map(d -> d.code() + " " + d.message()).toList(), built,
                preparation.manifests(), preparation.surface(), result.producedProject());
        }
    }

    private static Compilation fixtureCompilation(Compilation observation,
            ModuleId fixture) {
        if (!observation.compiled()) {
            return observation;
        }
        CheckedProjectBuildResult built = observation.built();
        ModuleId driver = built.input().entryModule();
        List<CheckedModuleInput> modules = built.input().modules().stream()
            .filter(module -> !module.moduleId().equals(driver)).toList();
        Map<ModuleId, deal.semantic.ir.ExternalModuleInterface> interfaces =
            new LinkedHashMap<>(built.index().modules());
        interfaces.remove(driver);
        CheckedProjectInput input = new CheckedProjectInput(built.input().invocation(),
            fixture, modules, built.input().releaseStateHash());
        CheckedProjectBuildResult fixtureBuilt = new CheckedProjectBuildResult(input,
            new ProjectInterfaceIndex(built.index().formatVersion(), interfaces), List.of());
        RequirementManifestResult manifests = new RequirementManifestResult(
            observation.manifests().manifests().stream()
                .filter(manifest -> !manifest.moduleId().equals(driver)).toList(), List.of());
        return new Compilation(true, List.of(), fixtureBuilt, manifests,
            observation.surface(), null);
    }

    private static Project withOutput(Project project, String directory) {
        return new Project(project.root(), project.srcRoot(), project.entryFile(),
            project.modulePath(), project.modules(), project.root().resolve(directory));
    }

    private static boolean compile(Project project, String fixtureRel, Target target,
            List<String> diagnosticsOut) throws Exception {
        Compilation observation = compileObservation(project, fixtureRel, target);
        diagnosticsOut.addAll(observation.diagnostics());
        return observation.compiled();
    }

    private static Path artifactOf(Project project, Target target) {
        String name = target == Target.JVM
            ? JvmBackend.classNameFor(project.modulePath()) + ".java"
            : project.modulePath() + ".lua";
        return project.outputRoot().resolve(name);
    }

    private record Execution(int exitCode, String stdout, String stderr,
                             Capture capture, boolean probeDefect) {
    }

    private static final long BOUNDED_PROCESS_BUDGET_MS = BoundedProcess.SUBPROCESS_BUDGET_MS;

    private record ProcessOutcome(int exitCode, String stdout, String stderr) {
    }

    private static ProcessOutcome runProcess(Path directory, Map<String, String> env,
            long budgetMs, String... command) throws Exception {
        if ("javac".equals(command[0])) {
            JvmCompilation.Result compile = JvmCompilation.compile(List.of(command),
                directory, budgetMs);
            return new ProcessOutcome(compile.exitCode(), compile.stdout(), compile.stderr());
        }
        BoundedProcess.BoundedResult result = BoundedProcess.runCaptured(
            List.of(command), directory, env, budgetMs);
        if (result.stdoutTruncated() || result.stderrTruncated()) {
            throw new AssertionError("truncated jsonable transcript: " + command[0]);
        }
        return new ProcessOutcome(result.exitCode(), result.stdout(), result.stderr());
    }

    private record Export(String name, int index) {
    }

    private record Capture(String code, String message, String sourceFile,
                           Integer line, Integer column, String expected, String actual) {
    }

    /** The ordered zero-arity exports of the entry module. */
    private static List<Export> exportsOf(String source) {
        List<Export> exports = new ArrayList<>();
        Matcher matcher = Pattern
            .compile("^export function (\\w+)\\(\\)", Pattern.MULTILINE)
            .matcher(source);
        int index = 0;
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!name.contains("$")) {
                exports.add(new Export(name, index++));
            }
        }
        return exports;
    }

    private static final String TRANSPORT_FILE = "__probe_transport.txt";

    private static Execution executeLua(Project project, List<Export> exports)
            throws Exception {
        Path out = project.outputRoot();
        Path artifact = artifactOf(project, Target.LUAJIT);
        Path transport = out.resolve(TRANSPORT_FILE);
        Files.deleteIfExists(transport);
        StringBuilder probe = new StringBuilder();
        probe.append("local function __esc(s)\n");
        probe.append("  s = tostring(s)\n");
        probe.append("  s = string.gsub(s, \"\\\\\", \"\\\\\\\\\")\n");
        probe.append("  s = string.gsub(s, \"\\n\", \"\\\\n\")\n");
        probe.append("  return s\nend\n");
        probe.append("local function __transport(fields)\n");
        probe.append("  local f = io.open(\"").append(transport).append("\", \"w\")\n");
        probe.append("  if not f then os.exit(3) end\n");
        probe.append("  for _, kv in ipairs(fields) do\n");
        probe.append("    f:write(kv[1], \"\\t\", __esc(kv[2] or \"\"), \"\\n\")\n");
        probe.append("  end\n  f:close()\nend\n");
        probe.append("local function __fail(err)\n");
        probe.append("  if type(err) ~= \"table\" then\n");
        probe.append("    __transport({ {\"code\", \"NON_DEAL\"},"
            + " {\"message\", tostring(err)} })\n  else\n");
        probe.append("    local file, line, column = err.file, err.line, err.column\n");
        probe.append("    if err.o ~= nil then\n");
        probe.append("      file, line, column = tostring(err.o)"
            + ":match(\"^(.*):(%d+):(%d+)$\")\n    end\n");
        probe.append("    __transport({ {\"code\", err.code},"
            + " {\"message\", err.message or err.m}, {\"file\", file},"
            + " {\"line\", line}, {\"column\", column}, {\"expected\", err.e},"
            + " {\"actual\", err.a} })\n  end\n");
        probe.append("  os.exit(1)\nend\n");
        probe.append("local function __defect(detail)\n");
        probe.append("  __transport({ {\"code\", \"PROBE_DEFECT\"},"
            + " {\"message\", detail} })\n  os.exit(1)\nend\n");
        probe.append("dofile(\"").append(artifact.toAbsolutePath().normalize())
            .append("\")\n");
        probe.append("local __ok, __err = __dealMain()\n");
        probe.append("if not __ok then __fail(__err) end\n");
        for (Export export : exports) {
            if ("main".equals(export.name())) {
                continue;
            }
            probe.append("do local __v = __exportSurfaces[\"")
                .append(project.modulePath()).append("\"][\"")
                .append(export.name()).append("\"]\n");
            probe.append("if type(__v) ~= \"table\" or type(__v.f) ~= \"function\" then "
                + "__defect(\"the entry surface publishes no function for ")
                .append(export.name()).append("\") end\n");
            probe.append("local __okS, __errS = pcall(__v.f)\n");
            probe.append("if not __okS then __fail(__errS) end end\n");
        }
        probe.append("os.exit(0)\n");
        Path probeFile = out.resolve("__probe.lua");
        Files.writeString(probeFile, probe.toString(), StandardCharsets.UTF_8);
        ProcessOutcome outcome = runProcess(out, Map.of("DEAL_DEFER_MAIN", "1"),
            BOUNDED_PROCESS_BUDGET_MS, "luajit", probeFile.toString());
        Capture capture = readTransport(transport);
        boolean defect = capture != null && "PROBE_DEFECT".equals(capture.code());
        return new Execution(outcome.exitCode(), outcome.stdout(), outcome.stderr(),
            capture, defect);
    }

    private static Execution executeJvm(Project project, List<Export> exports)
            throws Exception {
        Path out = project.outputRoot();
        Path artifact = artifactOf(project, Target.JVM);
        Path transport = out.resolve(TRANSPORT_FILE);
        Files.deleteIfExists(transport);
        String className = JvmBackend.classNameFor(project.modulePath());
        Path classes = out.resolve("classes");
        Files.createDirectories(classes);
        String classpath = absoluteClasspath();
        String probeName = "JsonableArtifactProbe_"
            + java.util.UUID.randomUUID().toString().replace("-", "");
        StringBuilder probe = new StringBuilder();
        probe.append("public final class ").append(probeName).append(" {\n");
        probe.append("  static final String TRANSPORT = \"")
            .append(transport.toString().replace("\\", "\\\\")).append("\";\n");
        probe.append("  public static void main(String[] args) {\n");
        probe.append("    try {\n");
        probe.append("      ").append(className).append(".dealMain();\n");
        for (Export export : exports) {
            if ("main".equals(export.name())) {
                continue;
            }
            probe.append("      {\n");
            probe.append("        Object v = deal.codegen.jvm.JvmRuntime")
                .append(".exportSurface(\"").append(project.modulePath())
                .append("\").read(\"").append(export.name()).append("\");\n");
            probe.append("        if (!(v instanceof deal.codegen.jvm")
                .append(".JvmRuntime.FunctionValue)) {\n");
            probe.append("          transport(new IllegalStateException(\"PROBE_DEFECT: "
                + "the entry surface publishes no function for ")
                .append(export.name()).append("\"));\n");
            probe.append("          return;\n        }\n");
            probe.append("        ((deal.codegen.jvm.JvmRuntime.FunctionValue) v)")
                .append(".fn.invoke(new Object[]{});\n      }\n");
        }
        probe.append("      System.exit(0);\n");
        probe.append("    } catch (Throwable error) {\n      transport(error);\n    }\n");
        probe.append("  }\n");
        probe.append("  static void transport(Throwable error) {\n");
        probe.append("    Throwable e = error;\n");
        probe.append("    while (e != null && !(e instanceof deal.codegen.jvm")
            .append(".JvmRuntime.DealError) && e.getCause() != null) {\n");
        probe.append("      e = e.getCause();\n    }\n");
        probe.append("    StringBuilder out = new StringBuilder();\n");
        probe.append("    if (e instanceof deal.codegen.jvm.JvmRuntime.DealError d) {\n");
        probe.append("      append(out, \"code\", d.code);\n");
        probe.append("      append(out, \"message\", d.msg);\n");
        probe.append("      append(out, \"origin\", d.origin);\n");
        probe.append("      append(out, \"expected\", d.expected);\n");
        probe.append("      append(out, \"actual\", d.actual);\n");
        probe.append("    } else {\n");
        probe.append("      append(out, \"code\", \"NON_DEAL\");\n");
        probe.append("      append(out, \"message\", String.valueOf(error));\n    }\n");
        probe.append("    try {\n");
        probe.append("      java.nio.file.Files.writeString(java.nio.file.Path.of("
            + "TRANSPORT), out.toString());\n");
        probe.append("    } catch (java.io.IOException ignored) { }\n");
        probe.append("    System.exit(1);\n  }\n");
        probe.append("  static void append(StringBuilder out, String key, String value) {\n");
        probe.append("    if (value == null) { return; }\n");
        probe.append("    String escaped = value.replace(\"\\\\\", \"\\\\\\\\\")"
            + ".replace(\"\\n\", \"\\\\n\").replace(\"\\t\", \"\\\\t\");\n");
        probe.append("    out.append(key).append('\\t').append(escaped).append('\\n');\n");
        probe.append("  }\n}\n");
        Path probeFile = out.resolve(probeName + ".java");
        Files.writeString(probeFile, probe.toString(), StandardCharsets.UTF_8);
        ProcessOutcome compile = runProcess(out, Map.of(), BOUNDED_PROCESS_BUDGET_MS,
            "javac", "--release", "25", "-proc:none", "-cp", classpath, "-d",
            classes.toString(), artifact.toString(), probeFile.toString());
        String compileOut = compile.stdout() + compile.stderr();
        int compileExit = compile.exitCode();
        checkEq(0, compileExit, project.modulePath() + " [jvm]: the emitted "
            + "production artifact compiles with javac --release 25 -proc:none: "
            + compileOut);
        if (compileExit != 0) {
            return new Execution(-1, "", compileOut, null, false);
        }
        ProcessOutcome outcome = runProcess(out, Map.of(), BOUNDED_PROCESS_BUDGET_MS,
            "java", "-cp", classes + File.pathSeparator + classpath,
            probeName);
        Capture capture = readTransport(transport);
        boolean defect = capture != null && "PROBE_DEFECT".equals(capture.code());
        return new Execution(outcome.exitCode(), outcome.stdout(), outcome.stderr(),
            capture, defect);
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

    private static Capture readTransport(Path transport) throws Exception {
        if (!Files.isRegularFile(transport)) {
            return null;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : Files.readAllLines(transport, StandardCharsets.UTF_8)) {
            int tab = line.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            fields.put(line.substring(0, tab), unescape(line.substring(tab + 1)));
        }
        String file = null;
        String origin = fields.get("origin");
        if (origin != null && origin.contains(":")) {
            int lastColon = origin.lastIndexOf(':');
            int prevColon = origin.lastIndexOf(':', lastColon - 1);
            if (prevColon > 0) {
                file = origin.substring(0, prevColon);
                fields.putIfAbsent("line", origin.substring(prevColon + 1, lastColon));
                fields.putIfAbsent("column", origin.substring(lastColon + 1));
            }
        } else {
            file = fields.get("file");
        }
        return new Capture(fields.get("code"), fields.get("message"), file,
            parseInt(fields.get("line")), parseInt(fields.get("column")),
            emptyToNull(fields.get("expected")), emptyToNull(fields.get("actual")));
    }

    private static Integer parseInt(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String emptyToNull(String text) {
        return text == null || text.isEmpty() ? null : text;
    }

    private static String unescape(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(++i);
                out.append(switch (next) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case '\\' -> '\\';
                    default -> next;
                });
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // =========================================================================
    // 4. The measured record and the sidecar comparison
    // =========================================================================

    private static final Map<String, Map<Target, String>> RECORD =
        new LinkedHashMap<>();

    private record CorpusObservation(Project project, Compilation compilation,
                                     Compilation luaCompilation,
                                     Lowered lowered, Lowered oracleExports,
                                     List<Export> exports,
                                     Optional<SidecarExpectations.RuntimeExpectation.Executed> pinned) {
    }

    private static void testProductionDrive(List<String> discovered,
            Map<String, CorpusObservation> observations) throws Exception {
        System.out.println("-- the @jsonable helper family production drive: "
            + discovered.size() + " discovered fixtures on both targets --");
        List<String> driven = new ArrayList<>(discovered);
        driven.add(NEAR_COLLISION);
        driven.add(EXPORT_KEYS);
        for (String fixture : driven) {
            String fixtureRel = fixture.contains("/") ? fixture
                : FAMILY_DIR + "/" + fixture;
            Project luaProject = materialize(fixtureRel, Target.LUAJIT);
            Project jvmProject = withOutput(luaProject, "out-jvm");
            try {
                Optional<SidecarExpectations.RuntimeExpectation.Executed> pinned =
                    pinnedOf(fixtureRel);
                List<Export> exports = List.copyOf(exportsOf(
                    Files.readString(jvmProject.entryFile(), StandardCharsets.UTF_8)));
                Map<Target, String> legs = new LinkedHashMap<>();
                Path driver = luaProject.srcRoot().resolve("__oracle_drive.deal");
                Files.writeString(driver, oracleDriver(luaProject, exports),
                    StandardCharsets.UTF_8);
                Project oracleProject = new Project(luaProject.root(), luaProject.srcRoot(),
                    driver, "__oracle_drive", luaProject.modules(),
                    luaProject.root().resolve("out-oracle"));
                Compilation oracleCompilation = compileObservation(oracleProject, fixtureRel,
                    Target.LUAJIT);
                Lowered oracleExports = lower(oracleCompilation, fixtureRel + " (oracle exports)");
                Compilation preparation = fixtureCompilation(oracleCompilation,
                    new ModuleId(luaProject.modulePath()));
                Compilation luaCompilation = emitChecked(luaProject, preparation, Target.LUAJIT);
                legs.put(Target.LUAJIT, driveLeg(fixtureRel, luaProject, Target.LUAJIT,
                    luaCompilation, exports, pinned));
                Compilation jvmCompilation = emitChecked(jvmProject, preparation,
                    Target.JVM);
                observations.put(fixtureRel,
                    new CorpusObservation(jvmProject, jvmCompilation, luaCompilation, null,
                        oracleExports, exports, pinned));
                legs.put(Target.JVM, driveLeg(fixtureRel, jvmProject, Target.JVM,
                    jvmCompilation, exports, pinned));
                RECORD.put(fixtureRel, legs);
            } finally {
                if (!observations.containsKey(fixtureRel)) {
                    deleteRecursively(luaProject.root());
                }
            }
        }
        // The family's pinned compile-error fixture: the frontend rejects
        // assigning an unknown Record read to an array with E3001 on both targets and nothing
        // publishes (the sidecar-free disposition of the corpus).
        Project rejectProject = materialize(COMPILE_REJECT, Target.LUAJIT);
        try {
            List<String> diagnostics = new ArrayList<>();
            boolean compiled = compile(rejectProject, COMPILE_REJECT, Target.LUAJIT,
                diagnostics);
            check(!compiled, COMPILE_REJECT + ": the frontend rejects the dynamic "
                + "table index");
            check(diagnostics.stream().anyMatch(d -> d.startsWith("E3001 ")),
                COMPILE_REJECT + ": the pinned unknown-assignment E3001 diagnostic: " + diagnostics);
            check(!Files.exists(artifactOf(rejectProject, Target.LUAJIT)),
                COMPILE_REJECT + ": no artifact publishes for the compile reject");
        } finally {
            deleteRecursively(rejectProject.root());
        }
    }

    private static String driveLeg(String fixtureRel, Project project, Target target,
            Compilation observation, List<Export> exports,
            Optional<SidecarExpectations.RuntimeExpectation.Executed> pinned)
            throws Exception {
        List<String> diagnostics = observation.diagnostics();
        boolean compiled = observation.compiled();
        for (String diagnostic : diagnostics) {
            check(!diagnostic.startsWith("E6005 "), fixtureRel + " ["
                + target.laneName() + "]: zero E6005 over the in-scope fixture: "
                + diagnostic);
        }
        check(compiled, fixtureRel + " [" + target.laneName()
            + "]: the fixture compiles through the release-owned production "
            + "invocation: " + diagnostics);
        Path artifact = artifactOf(project, target);
        check(Files.isRegularFile(artifact), fixtureRel + " ["
            + target.laneName() + "]: the artifact publishes at " + artifact);
        if (!compiled || !Files.isRegularFile(artifact)) {
            return "compile-failure";
        }
        Execution execution = target == Target.LUAJIT
            ? executeLua(project, exports) : executeJvm(project, exports);
        check(!execution.probeDefect(), fixtureRel + " [" + target.laneName()
            + "]: the artifact drive reaches every export: "
            + (execution.capture() == null ? "" : execution.capture().message()));
        Capture capture = normalize(project, execution.capture());
        if (pinned.isEmpty()) {
            checkEq("", execution.stderr(), fixtureRel + " [" + target.laneName()
                + "]: the artifact run emits no stderr");
            if (capture == null) {
                checkEq(0, execution.exitCode(), fixtureRel + " ["
                    + target.laneName() + "]: the runtime-ok artifact run exits 0");
            }
            return capture == null ? "ok" : "capture:" + capture;
        }
        SidecarExpectations.RuntimeExpectation.Executed expectation = pinned.get();
        if (!expectation.isRuntimeError()) {
            List<String> mismatches = runtimeOkMismatches(expectation, execution,
                capture);
            for (String mismatch : mismatches) {
                check(false, fixtureRel + " [" + target.laneName()
                    + "]: " + mismatch);
            }
            return mismatches.isEmpty() ? "ok" : "runtime-ok-mismatch";
        }
        SidecarExpectations.ErrorExpectation row = expectation.error();
        List<String> mismatches = runtimeErrorSidecarMismatches(expectation, row,
            execution.exitCode(), execution.stdout(), execution.stderr(), capture);
        for (String mismatch : mismatches) {
            check(false, fixtureRel + " [" + target.laneName() + "]: " + mismatch);
        }
        return mismatches.isEmpty() ? "pin-exact" : "pin-mismatch";
    }

    /**
     * The runtime-ok acceptance's ordered mismatch descriptions: empty
     * exactly when the observation reproduces the sidecar's pinned
     * runtime-ok leg — the exact exit status, the complete stdout and
     * stderr transcripts, and no raised failure or missed export. Every
     * mismatch is a fatal verdict of this leg (the caller reports each
     * one); the verdict is a pure function of the observation, so a
     * failure in any other fixture/target cell never suppresses this
     * cell's enforcement (there is no deficit inventory and no
     * load-bearing construct state here).
     */
    private static List<String> runtimeOkMismatches(
            SidecarExpectations.RuntimeExpectation.Executed expectation,
            Execution execution, Capture capture) {
        List<String> mismatches = new ArrayList<>();
        if (execution.probeDefect()) {
            mismatches.add("the runtime-ok artifact drive misses an export "
                + "(the probe defect: " + (capture == null ? ""
                    : capture.message()) + ")");
        } else if (capture != null) {
            mismatches.add("the runtime-ok artifact raises a failure (capture "
                + capture + ")");
        }
        addMismatch(mismatches, "exit code", expectation.exitCode(),
            execution.exitCode());
        addMismatch(mismatches, "stdout",
            new String(expectation.stdout(), StandardCharsets.UTF_8),
            execution.stdout());
        addMismatch(mismatches, "stderr",
            new String(expectation.stderr(), StandardCharsets.UTF_8),
            execution.stderr());
        return mismatches;
    }

    /**
     * The pinned-failure leg's sidecar comparison: the ordered mismatch
     * descriptions (empty exactly when the leg reproduces the sidecar's
     * byte-exact transcript — the normalized capture fields, the complete
     * stdout transcript, the process exit code, and the process stderr). The
     * sidecar's stdout, exit code, and stderr are part of the byte-exact
     * transcript contract, so a matching capture accompanied by unexpected
     * stdout, an incorrect process status, or wrong stderr is a mismatch,
     * never a pass. The stdout transcript is the captured process stdout
     * concatenated with the framing regenerated from the normalized capture
     * — the landed lane's assembly — so arbitrary extra output cannot hide
     * behind an otherwise matching capture.
     */
    private static List<String> runtimeErrorSidecarMismatches(
            SidecarExpectations.RuntimeExpectation.Executed expectation,
            SidecarExpectations.ErrorExpectation row, int exitCode, String stdout,
            String stderr, Capture capture) {
        List<String> mismatches = new ArrayList<>();
        if (capture == null || capture.code() == null) {
            mismatches.add("the pinned failure is not captured (exit " + exitCode
                + "; stderr " + stderr + ")");
            return mismatches;
        }
        addMismatch(mismatches, "code", row.code(), capture.code());
        addMismatch(mismatches, "message", row.message(), capture.message());
        addMismatch(mismatches, "span file (raw corpus coordinates)",
            row.sourceFile(), capture.sourceFile());
        addMismatch(mismatches, "raw corpus line", row.line(), capture.line());
        addMismatch(mismatches, "column", row.column(), capture.column());
        addMismatch(mismatches, "expected field", row.expected().orElse(null),
            capture.expected());
        addMismatch(mismatches, "actual field", row.actual().orElse(null),
            capture.actual());
        addMismatch(mismatches, "exit code", expectation.exitCode(), exitCode);
        addMismatch(mismatches, "stderr",
            new String(expectation.stderr(), StandardCharsets.UTF_8), stderr);
        String framed = ErrorSnapshot.CODE_LINE_PREFIX + capture.code() + "\n"
            + ErrorSnapshot.SNAPSHOT_LINE_PREFIX
            + ErrorSnapshot.canonicalJson(new SidecarExpectations.ErrorExpectation(
                capture.code(), capture.message(),
                row.pinsSpan() ? capture.sourceFile() : null,
                row.pinsSpan() ? capture.line() : null,
                row.pinsSpan() ? capture.column() : null,
                row.expected().isPresent() ? Optional.ofNullable(capture.expected())
                    : Optional.empty(),
                row.actual().isPresent() ? Optional.ofNullable(capture.actual())
                    : Optional.empty(),
                Optional.empty(), Optional.empty()))
            + "\n";
        addMismatch(mismatches, "framed stdout",
            new String(expectation.stdout(), StandardCharsets.UTF_8),
            stdout + framed);
        return mismatches;
    }

    private static void addMismatch(List<String> mismatches, String field,
            Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            mismatches.add("the " + field + " mismatch (expected " + expected
                + ", got " + actual + ")");
        }
    }

    private static Optional<SidecarExpectations.RuntimeExpectation.Executed> pinnedOf(
            String fixtureRel) throws Exception {
        Path sidecar = CONFORMANCE.resolve(fixtureRel + ".expect.json");
        if (!Files.isRegularFile(sidecar)) {
            return Optional.empty();
        }
        SidecarExpectations.StructuredExpectationSidecar parsed =
            SidecarExpectations.StructuredExpectationSidecar.parse(
                Files.readString(sidecar, StandardCharsets.UTF_8));
        SidecarExpectations.RuntimeExpectation expectation =
            parsed.expectationFor("luajit");
        if (expectation instanceof SidecarExpectations.RuntimeExpectation.Executed
                executed) {
            return Optional.of(executed);
        }
        return Optional.empty();
    }

    private static Capture normalize(Project project, Capture capture) {
        if (capture == null) {
            return null;
        }
        Materialized module = project.moduleOf(capture.sourceFile());
        return new Capture(capture.code(), capture.message(),
            module == null ? capture.sourceFile() : module.corpusPath(),
            capture.line() == null || module == null ? capture.line()
                : capture.line() + module.headerLinesStripped(),
            capture.column(), capture.expected(), capture.actual());
    }

    // =========================================================================
    // 5. The oracle leg (the combined dependency step)
    // =========================================================================

    private record Lowered(ExecutableLoweredProject project,
                           Map<ModuleId, StructuredBodyTable> tables,
                           Map<ModuleId, deal.semantic.ir.ClassFactoryRegistry> registries,
                           ModuleId entryModule) {
    }

    private static Lowered lower(Project project, String label) throws Exception {
        return lower(project, project.entryFile(), label);
    }

    private static Lowered lower(Project project, Path entryFile, String label)
            throws Exception {
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile.toAbsolutePath(), project.root().resolve("out-oracle"),
            false, false, false, false, Backend.LUAJIT, Map.of(),
            List.of(project.srcRoot()), Path.of("std").toAbsolutePath().normalize(),
            null, productionInvocation());
        boolean compiled = orchestrator.compile();
        return lower(new Compilation(compiled, List.of(), orchestrator.checkedProject(),
            orchestrator.requirementManifests(), orchestrator.hostDeclarationSurface(),
            orchestrator.producedProject()),
            label);
    }

    private static Lowered lower(Compilation observation, String label) {
        boolean compiled = observation.compiled();
        CheckedProjectBuildResult built = observation.built();
        RequirementManifestResult manifests = observation.manifests();
        HostDeclarationSurface surface = observation.surface();
        check(compiled && built != null && built.input() != null && built.index() != null
                && !built.hasErrors() && manifests != null
                && manifests.manifests() != null && surface != null,
            label + ": the oracle closure compiles: "
                + (built == null ? "no checked project" : built.diagnostics()));
        if (!compiled || built == null || built.input() == null || built.index() == null
                || built.hasErrors() || manifests == null
                || manifests.manifests() == null || surface == null) {
            return null;
        }
        SemanticLowerer.ProjectLoweringResult result = SemanticLowerer.lowerProject(
            productionInvocation(), built.input(), built.index(),
            manifests.manifests(), surface, Map.of(), BuiltinErrorDeclaration.synthesized(
                built.input().modules().get(0).ast().span()),
            Set.of());
        check(result.project() != null, label + ": the oracle closure lowers with "
            + "zero diagnostics: " + result.diagnostics());
        if (result.project() == null) {
            return null;
        }
        return new Lowered(result.project(), result.tables(), result.registries(),
            result.project().entryModule());
    }

    /**
     * The oracle driver entry for the ordered zero-arity export loop: the
     * fixture is a dependency here, and its non-{@code main} exports run
     * exactly as the artifact probes' ordered loop runs them. The fixture's
     * {@code main} is not called here (a cross-module {@code main} call is
     * no valid invocation shape: the declaring module records no
     * {@code EXTERNAL_ENTRY} for it — the entry delegation owns its single
     * invocation); the main leg below lowers the fixture itself as the
     * entry so the entry delegation runs it exactly once.
     */
    private static String oracleDriver(Project project, List<Export> exports) {
        String fixtureStem = moduleNameOf(
            project.entryFile().getFileName().toString());
        StringBuilder source = new StringBuilder();
        source.append("import * as fx from \"./").append(fixtureStem)
            .append("\";\n\n");
        source.append("export function main(): null {\n");
        for (Export export : exports) {
            if ("main".equals(export.name())) {
                continue;
            }
            source.append("  fx.").append(export.name()).append("();\n");
        }
        return source.append("  return null;\n}\n").toString();
    }

    /**
     * The oracle's main leg: the fixture itself is the entry module of the
     * one-lowering closure, so its own entry delegation invokes its declared
     * {@code main} exactly once — the exact sequence the artifact probes run
     * first. Returns the failure, or {@code null} on success.
     */
    private static SemanticRuntimeModel.ErrorSnapshot oracleMainFailure(Lowered lowered) {
        if (lowered == null) {
            return null;
        }
        SemanticRuntimeModel.ConsumerRun run = SemanticOracle.executeProjectInits(
            lowered.project(), lowered.tables(), lowered.registries(),
            new SemanticOracle.HostResponder() { });
        if (run.terminal() instanceof SemanticRuntimeModel.Terminal.Success) {
            return null;
        }
        return ((SemanticRuntimeModel.Terminal.DealFailure) run.terminal()).error();
    }

    private static String oracleOutcome(Project project,
            SemanticRuntimeModel.ConsumerRun run) {
        if (run.terminal() instanceof SemanticRuntimeModel.Terminal.Success) {
            return "ok";
        }
        return oracleOutcomeOfFailure(project,
            ((SemanticRuntimeModel.Terminal.DealFailure) run.terminal()).error());
    }

    /** The one outcome spelling of one oracle failure snapshot. */
    private static String oracleOutcomeOfFailure(Project project,
            SemanticRuntimeModel.ErrorSnapshot error) {
        String file = null;
        Integer line = null;
        Integer column = null;
        if (error.origin() != null && error.origin().contains(":")) {
            int lastColon = error.origin().lastIndexOf(':');
            int prevColon = error.origin().lastIndexOf(':', lastColon - 1);
            if (prevColon > 0) {
                file = error.origin().substring(0, prevColon);
                line = Integer.valueOf(error.origin()
                    .substring(prevColon + 1, lastColon));
                column = Integer.valueOf(error.origin().substring(lastColon + 1));
            }
        }
        Materialized module = project.moduleOf(file);
        return "code=" + error.code() + "|message=" + error.message() + "|file="
            + (module == null ? file : module.corpusPath()) + "|line="
            + (line == null || module == null ? line
                : line + module.headerLinesStripped())
            + "|column=" + column + "|expected=" + error.expected()
            + "|actual=" + error.actual();
    }

    /**
     * The dependency step: the oracle executes every fixture of the family
     * through the same one-lowering closure the production legs used, and
     * reproduces the sidecar-pinned outcome (the runtime-ok success or the
     * pinned error tuple at the raw corpus coordinates). The two named
     * dependency fixtures keep their explicit assertions: the cross-module
     * helper call succeeds, and the pinned helper JSON failure (the
     * canonical failure projection authority's cycle arm) renders the
     * pinned tuple at the invoking call expression.
     */
    private static void testOracleAgreement(List<String> discovered,
            Map<String, CorpusObservation> observations) throws Exception {
        System.out.println("-- the combined dependency step: the oracle over every "
            + "family fixture (each fixture's main through its own entry "
            + "delegation, the cross-module helper call, and the pinned helper "
            + "JSON failure included) --");
        List<String> driven = new ArrayList<>(discovered);
        driven.add(NEAR_COLLISION);
        driven.add(EXPORT_KEYS);
        for (String fixture : driven) {
            String fixtureRel = fixture.contains("/") ? fixture
                : FAMILY_DIR + "/" + fixture;
            CorpusObservation observation = observations.get(fixtureRel);
            Project project = observation.project();
            try {
                List<Export> exports = observation.exports();
                // The main leg: the fixture is the entry module of its own
                // one-lowering closure, so the entry delegation invokes its
                // declared main exactly once — the artifact probes' first
                // step. A main-leg failure dominates (the probes never reach
                // the export loop after a failing main).
                Lowered mainLeg = lower(observation.compilation(),
                    fixtureRel + " (oracle main)");
                observations.put(fixtureRel, new CorpusObservation(project,
                    observation.compilation(), observation.luaCompilation(), mainLeg,
                    observation.oracleExports(), exports,
                    observation.pinned()));
                SemanticRuntimeModel.ErrorSnapshot mainFailure =
                    oracleMainFailure(mainLeg);
                String mainOutcome = mainFailure == null ? "ok"
                    : oracleOutcomeOfFailure(project, mainFailure);
                Lowered lowered = observation.oracleExports();
                if (lowered == null) {
                    continue;
                }
                String exportOutcome = oracleOutcome(project,
                    SemanticOracle.executeProjectInits(lowered.project(),
                        lowered.tables(), lowered.registries(),
                        new SemanticOracle.HostResponder() { }));
                String outcome = "ok".equals(mainOutcome) ? exportOutcome
                    : mainOutcome;
                System.out.println("   oracle " + fixtureRel + ": main "
                    + mainOutcome + "; exports " + exportOutcome);
                Optional<SidecarExpectations.RuntimeExpectation.Executed> pinned =
                    observation.pinned();
                if (pinned.isPresent()) {
                    if (pinned.get().isRuntimeError()) {
                        checkEq(pinnedOracleOutcome(pinned.get().error()), outcome,
                            fixtureRel + ": the oracle reproduces the pinned tuple at "
                                + "the raw corpus coordinates");
                    } else {
                        checkEq("ok", outcome, fixtureRel + ": the oracle executes "
                            + "the fixture with the pinned runtime-ok outcome");
                    }
                }
                if (CYCLIC_FIXTURE.equals(fixtureRel)) {
                    check(outcome.contains("message=cyclic value cannot be encoded as JSON"),
                        "the oracle's helper JSON failure renders the pinned cycle "
                            + "text: " + outcome);
                    check(outcome.contains("code=E8001"),
                        "the oracle's helper JSON failure carries E8001: " + outcome);
                    check(outcome.contains("expected=null") && outcome.contains(
                        "actual=null"),
                        "the cycle arm carries no expected/actual: " + outcome);
                    String captured = RECORD.get(fixtureRel).get(Target.LUAJIT);
                    check(captured != null && captured.startsWith("pin-exact"),
                        "the LuaJIT leg reproduces the pinned tuple: " + captured);
                } else if (CROSS_MODULE_FIXTURE.equals(fixtureRel)) {
                    checkEq("ok", outcome,
                        "the cross-module helper call executes through the oracle: "
                            + outcome);
                }
            } finally {
                Files.deleteIfExists(project.srcRoot().resolve("__oracle_drive.deal"));
            }
        }
    }

    /** The pinned sidecar error row in {@link #oracleOutcome}'s spelling. */
    private static String pinnedOracleOutcome(
            SidecarExpectations.ErrorExpectation row) {
        return "code=" + row.code() + "|message=" + row.message()
            + "|file=" + row.sourceFile() + "|line=" + row.line()
            + "|column=" + row.column() + "|expected=" + row.expected().orElse(null)
            + "|actual=" + row.actual().orElse(null);
    }

    // =========================================================================
    // 6. The helper surface in the produced unit
    // =========================================================================

    private static void testHelperSurface(Lowered crossModule, Lowered sameModule) {
        System.out.println("-- the helper surface: one identity and one entry per "
            + "helper export, the same-module and cross-module call shapes --");
        {
            Lowered lowered = crossModule;
            if (lowered == null) {
                return;
            }
            ExecutableLoweredProject unit = lowered.project();
            ModuleId entry = lowered.entryModule();
            ModuleId lib = new ModuleId("jsonable_lib");
            LoweredModuleUnit entryUnit = unit.modules().get(entry);
            LoweredModuleUnit libUnit = unit.modules().get(lib);
            check(entryUnit != null && libUnit != null,
                "the cross-module closure carries both units");
            if (entryUnit == null || libUnit == null) {
                return;
            }
            // The declaring unit: exactly one CLOSURE_NEW per generated
            // helper signature, exactly one LoweredBody binding per closure
            // identity, and exactly one publication and entry per helper
            // export.
            Map<String, ValueId> closuresByHelper = new LinkedHashMap<>();
            for (SemanticOp op : libUnit.ops()) {
                if (op.kind() != SemanticOpKind.CLOSURE_NEW) {
                    continue;
                }
                KindPayload.ClosureNewPayload closure =
                    (KindPayload.ClosureNewPayload) op.payload();
                String text = closure.signature().canonicalSpecText();
                String helper = text.contains("?@") ? "Widget$fromJson" : "Widget$toJson";
                check(closuresByHelper.put(helper, (ValueId) op.result()) == null,
                    "exactly one " + helper + " closure identity per class");
                check(libUnit.functions().containsKey(closure.function()),
                    "the " + helper + " closure carries its LoweredFunction record");
                check(libUnit.functionBindings().get(new FunctionAllocationIdentity(
                        ((ValueId) op.result()).id()))
                        instanceof FunctionExecutionBinding.LoweredBody binding
                        && binding.functionId().equals(closure.function())
                        && binding.blockId().equals(closure.binding().blockId()),
                    "the " + helper + " closure identity registers exactly one "
                        + "LoweredBody binding (the landed call shape)");
            }
            checkEq(2, closuresByHelper.size(),
                "the declaring unit carries exactly one closure per class helper");
            int publications = 0;
            int entries = 0;
            Map<String, OpId> entryOf = new LinkedHashMap<>();
            Map<String, FunctionId> entryFunctionOf = new LinkedHashMap<>();
            for (SemanticOp op : libUnit.ops()) {
                if (op.kind() == SemanticOpKind.EXPORT_PUBLISH
                        && op.payload() instanceof KindPayload.ExportPublishPayload p
                        && (p.name().equals("Widget$fromJson")
                            || p.name().equals("Widget$toJson"))) {
                    publications++;
                }
                if (op.kind() == SemanticOpKind.EXTERNAL_ENTRY
                        && op.payload() instanceof KindPayload.ExternalEntryPayload p
                        && (p.exportName().equals("Widget$fromJson")
                            || p.exportName().equals("Widget$toJson"))) {
                    entries++;
                    entryOf.put(p.exportName(), op.opId());
                    entryFunctionOf.put(p.exportName(), p.function());
                }
            }
            checkEq(2, publications, "exactly one EXPORT_PUBLISH per helper export");
            checkEq(2, entries, "exactly one EXTERNAL_ENTRY per helper export");
            check(libUnit.moduleInit() != null, "the declaring unit carries its "
                + "module-init walk (the publication site)");
            for (Map.Entry<String, OpId> entryRow : entryOf.entrySet()) {
                FunctionId function = entryFunctionOf.get(entryRow.getKey());
                check(libUnit.functions().containsKey(function),
                    "the recorded entry of " + entryRow.getKey()
                        + " resolves the helper's own lowered body");
                check(libUnit.functions().get(function).descriptor()
                        .canonicalSpecText().contains("Widget"),
                    "the recorded entry of " + entryRow.getKey()
                        + " names the class's generated body");
            }
            // The cross-module call: one EXPORT_READ over the callee module
            // and one CALL(EXTERNAL) over the closed ExternalFunction
            // SHARED_BODY binding whose recorded entry is the callee's and
            // whose entry function is the helper's own lowered body.
            boolean exportRead = false;
            boolean externalCall = false;
            for (SemanticOp op : entryUnit.ops()) {
                if (op.kind() == SemanticOpKind.EXPORT_READ
                        && op.payload() instanceof KindPayload.ExportReadPayload p
                        && p.module().equals(lib)
                        && (p.name().equals("Widget$fromJson")
                            || p.name().equals("Widget$toJson"))) {
                    exportRead = true;
                }
                if (op.kind() == SemanticOpKind.CALL
                        && op.payload() instanceof KindPayload.CallPayload call
                        && call.callee() instanceof KindPayload.CallCallee.Static s
                        && s.binding() instanceof FunctionExecutionBinding.ExternalFunction
                            external
                        && external.moduleId().equals(lib)
                        && external.executionOwner()
                            == deal.semantic.ir.ExternalExecutionOwner.SHARED_BODY) {
                    externalCall = true;
                    OpId recorded = entryOf.get(external.exportName());
                    check(recorded != null, "the cross-module call names a recorded "
                        + "helper entry");
                    check(call.externalEntryRef() != null && recorded != null
                            && call.externalEntryRef().equals(recorded),
                        "the cross-module call binds the callee's recorded entry");
                }
            }
            check(exportRead, "the cross-module helper read lowers as an EXPORT_READ");
            check(externalCall, "the cross-module helper call binds the closed "
                + "ExternalFunction(SHARED_BODY) shape to the callee's recorded entry");
        }
        // The same-module call shape (R3(b)): a same-module reference
        // resolves the class's generated closure identity (the
        // declaration-position CLOSURE_NEW result) and calls it through the
        // landed LoweredBody call shape — one recorded EXTERNAL_ENTRY per
        // helper export remains for the cross-module callers, and no
        // same-module call resolves the ExternalFunction shape.
        {
            Lowered sameLowered = sameModule;
            if (sameLowered == null) {
                return;
            }
            LoweredModuleUnit roundtrip = sameLowered.project().modules()
                .get(sameLowered.entryModule());
            // The E7 terminals (the recorded entries) emit after the walk,
            // so the entries are collected first and the call sites checked
            // after.
            Map<String, FunctionId> entryFunctionOf = new LinkedHashMap<>();
            Map<FunctionId, OpId> entryReturnBoundaryByFunction = new LinkedHashMap<>();
            Map<FunctionId, ValueId> closureIdentityOf = new LinkedHashMap<>();
            for (SemanticOp op : roundtrip.ops()) {
                if (op.kind() == SemanticOpKind.EXTERNAL_ENTRY
                        && op.payload() instanceof KindPayload.ExternalEntryPayload p
                        && p.exportName().startsWith("Person$")) {
                    entryFunctionOf.put(p.exportName(), p.function());
                    entryReturnBoundaryByFunction.put(p.function(),
                        p.returnBoundaryOpId());
                }
                if (op.kind() == SemanticOpKind.CLOSURE_NEW
                        && op.payload() instanceof KindPayload.ClosureNewPayload closure
                        && op.result() instanceof ValueId identity) {
                    closureIdentityOf.put(closure.function(), identity);
                }
            }
            java.util.Set<ValueId> loadedIdentities = new LinkedHashSet<>();
            for (SemanticOp op : roundtrip.ops()) {
                if (op.kind() == SemanticOpKind.BINDING_LOAD
                        && op.result() instanceof ValueId loaded) {
                    loadedIdentities.add(loaded);
                }
            }
            int directHelperCalls = 0;
            int sameModuleExternalCalls = 0;
            for (SemanticOp op : roundtrip.ops()) {
                if (op.kind() != SemanticOpKind.CALL
                        || !(op.payload() instanceof KindPayload.CallPayload call)
                        || !(call.callee() instanceof KindPayload.CallCallee.Static s)) {
                    continue;
                }
                if (s.binding() instanceof FunctionExecutionBinding.LoweredBody body
                        && entryFunctionOf.containsValue(body.functionId())) {
                    directHelperCalls++;
                    LoweredFunction helper = roundtrip.functions().get(body.functionId());
                    check(helper != null, "the same-module call resolves the class's "
                        + "generated lowered body");
                    checkEq(CallMode.DIRECT, call.mode(),
                        "the same-module helper call is a direct body call");
                    check(helper != null && helper.body().equals(body.blockId())
                            && helper.body().equals(call.bodyBlock()),
                        "the same-module call names the declaration's body block");
                    ValueId identity = closureIdentityOf.get(body.functionId());
                    check(identity != null && loadedIdentities.contains(identity),
                        "the same-module callee load publishes the declaration's "
                            + "generated closure identity " + identity);
                    check(helper != null && helper.descriptor().canonicalSpecText()
                            .contains("Person"),
                        "the same-module helper body is the class's generated body");
                    checkEq(entryReturnBoundaryByFunction.get(body.functionId()),
                        call.returnBoundaryOpId(),
                        "the same-module call shares the helper's single return cell");
                }
                if (s.binding() instanceof FunctionExecutionBinding.ExternalFunction external
                        && external.moduleId().equals(sameLowered.entryModule())
                        && external.executionOwner()
                            == deal.semantic.ir.ExternalExecutionOwner.SHARED_BODY) {
                    sameModuleExternalCalls++;
                }
            }
            checkEq(2, entryFunctionOf.size(),
                "exactly one recorded entry per same-module helper export");
            checkEq(2, directHelperCalls, "the same-module helper calls are direct "
                + "LoweredBody calls to the declaration's closure (got "
                + directHelperCalls + ")");
            checkEq(0, sameModuleExternalCalls, "no same-module call resolves the "
                + "ExternalFunction shape (the entry stays the cross-module record)");
        }
    }

    // =========================================================================
    // 7. The negatives
    // =========================================================================

    private static void testNegatives() throws Exception {
        System.out.println("-- the negatives: the near-collision identifier, the "
            + "non-@jsonable class, and the E2004 frontend missing-helper "
            + "reference --");
        // The near-collision fixture: a local identifier `User_fromJson`
        // next to the helper `User$fromJson` changes no outcome (its leg
        // already reproduced the pinned runtime-ok sidecar byte-exact).
        String leg = RECORD.get(NEAR_COLLISION).get(Target.LUAJIT);
        checkEq("ok", leg, "the near-collision identifier changes no outcome");
        // A declared class that is not @jsonable gains no helper: the
        // non-jsonable fixture lowers no JSON closure and no JSON op.
        Path root = Files.createTempDirectory("jsonable-negative-");
        try {
            Path src = root.resolve("src");
            Files.createDirectories(src);
            Files.writeString(src.resolve("plain.deal"),
                "export class Plain {\n  name: string;\n}\n\n"
                + "export function main(): null {\n  return null;\n}\n",
                StandardCharsets.UTF_8);
            Files.writeString(root.resolve("deal.json"),
                "{\n  \"languageVersion\": \"1.5\",\n  \"moduleRoots\": [\"src\"],\n"
                    + "  \"output\": \"out\",\n  \"backend\": \"luajit\"\n}\n",
                StandardCharsets.UTF_8);
            Project project = new Project(root, src, src.resolve("plain.deal"), "plain",
                Map.of(), root.resolve("out"));
            Lowered lowered = lower(project, "non-jsonable negative");
            if (lowered != null) {
                for (LoweredModuleUnit moduleUnit : lowered.project().modules().values()) {
                    for (SemanticOp op : moduleUnit.ops()) {
                        check(op.kind() != SemanticOpKind.JSON_FROM_CLASS
                                && op.kind() != SemanticOpKind.JSON_TO_CLASS,
                            "a non-@jsonable class lowers no JSON helper op (got "
                                + op.kind() + ")");
                    }
                }
            }
        } finally {
            deleteRecursively(root);
        }
        // A helper reference on a non-@jsonable imported class fails closed
        // and stages nothing: the checker's declared-export fact is the
        // resolution authority, so no lowering arm fabricates a helper.
        Path missing = Files.createTempDirectory("jsonable-missing-export-");
        try {
            Path src = missing.resolve("src");
            Files.createDirectories(src);
            Files.writeString(src.resolve("plain_lib.deal"),
                "export class Plain {\n  name: string;\n}\n",
                StandardCharsets.UTF_8);
            Files.writeString(src.resolve("app.deal"),
                "import * as Lib from \"./plain_lib\";\n\n"
                    + "export function test_missing_helper(): null {\n"
                    + "  let s: string = Lib.Plain$toJson({ name: \"x\" });\n"
                    + "  return null;\n}\n\n"
                    + "export function main(): null {\n  return null;\n}\n",
                StandardCharsets.UTF_8);
            Files.writeString(missing.resolve("deal.json"),
                "{\n  \"languageVersion\": \"1.5\",\n  \"moduleRoots\": [\"src\"],\n"
                    + "  \"output\": \"out\",\n  \"backend\": \"luajit\"\n}\n",
                StandardCharsets.UTF_8);
            Project project = new Project(missing, src, src.resolve("app.deal"), "app",
                Map.of(), missing.resolve("out"));
            List<String> diagnostics = new ArrayList<>();
            boolean compiled = compile(project, "missing-helper-export", Target.LUAJIT,
                diagnostics);
            check(!compiled, "a helper reference on a non-@jsonable class fails closed: "
                + diagnostics);
            check(diagnostics.stream().anyMatch(d -> d.startsWith("E2004 ")),
                "the missing-helper reference fails closed with the checker's "
                    + "missing-export rule (E2004): " + diagnostics);
            check(!Files.exists(artifactOf(project, Target.LUAJIT)),
                "nothing stages for the missing-helper reference");
        } finally {
            deleteRecursively(missing);
        }
    }

    /**
     * The declaring module's checked export list without one export name:
     * the hand-built inconsistent "missing helper export" fact (no
     * checker-valid program produces it — the checker's synthetic export
     * is the generated helper's authority).
     */
    private static CheckedProjectInput withoutExport(CheckedProjectInput project,
            ModuleId module, String export) {
        List<CheckedModuleInput> modules = new ArrayList<>();
        for (CheckedModuleInput entry : project.modules()) {
            if (!entry.moduleId().equals(module)) {
                modules.add(entry);
                continue;
            }
            List<ExportInterface> exports = new ArrayList<>();
            for (ExportInterface candidate : entry.exports()) {
                if (!candidate.name().equals(export)) {
                    exports.add(candidate);
                }
            }
            modules.add(new CheckedModuleInput(entry.moduleId(), entry.sourceId(),
                entry.sourcePath(), entry.ast(), entry.checks(), entry.imports(),
                exports, entry.kind()));
        }
        return new CheckedProjectInput(project.invocation(), project.entryModule(),
            modules, project.releaseStateHash());
    }

    /**
     * The lowered project with one module's matching ops removed from the
     * produced-op list <em>and</em> from its block-membership table: the
     * hand-built inconsistent fact an "otherwise valid generated helper"
     * with a missing export or a missing recorded callee entry is (no
     * checker-valid program produces it, and the removal is complete — the
     * block walk never resolves a removed id).
     */
    private static Lowered withoutUnitOps(Lowered lowered, ModuleId module,
            java.util.function.Predicate<SemanticOp> remove) {
        LoweredModuleUnit unit = lowered.project().modules().get(module);
        List<SemanticOp> ops = new ArrayList<>();
        List<OpId> removed = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            if (remove.test(op)) {
                removed.add(op.opId());
            } else {
                ops.add(op);
            }
        }
        LoweredModuleUnit replaced = new LoweredModuleUnit(unit.formatVersion(),
            unit.semanticProfile(), unit.moduleId(), unit.interfaceHash(),
            unit.loweringContextHash(), unit.requiredCapabilities(),
            unit.constructCoverage(), unit.classLayouts(), unit.functions(),
            unit.moduleInit(), unit.exportPlan(), unit.functionBindings(), ops,
            unit.bodyTable(), unit.classFactories(), unit.classBindingFacts());
        Map<ModuleId, LoweredModuleUnit> modules = new LinkedHashMap<>();
        for (Map.Entry<ModuleId, LoweredModuleUnit> entry
                : lowered.project().modules().entrySet()) {
            modules.put(entry.getKey(),
                entry.getKey().equals(module) ? replaced : entry.getValue());
        }
        ExecutableLoweredProject project = new ExecutableLoweredProject(
            lowered.project().semanticProfile(), lowered.project().interfaceIndex(),
            modules, lowered.project().entryModule(),
            lowered.project().classSeeds());
        StructuredBodyTable table = lowered.tables().get(module);
        Map<BlockId, List<OpId>> blockOps = new LinkedHashMap<>();
        for (Map.Entry<BlockId, List<OpId>> entry : table.blockOps().entrySet()) {
            List<OpId> kept = new ArrayList<>();
            for (OpId id : entry.getValue()) {
                if (!removed.contains(id)) {
                    kept.add(id);
                }
            }
            blockOps.put(entry.getKey(), kept);
        }
        Map<OpId, BlockId> opBlocks = new LinkedHashMap<>(table.opBlocks());
        for (OpId id : removed) {
            opBlocks.remove(id);
        }
        Map<ModuleId, StructuredBodyTable> tables = new LinkedHashMap<>(lowered.tables());
        tables.put(module, new StructuredBodyTable(blockOps, opBlocks));
        return new Lowered(project, tables, lowered.registries(),
            lowered.entryModule());
    }

    /**
     * Both production emitters fail closed on one hand-built inconsistent
     * helper fact with a named producer defect: exactly the
     * {@code IllegalStateException} the production arm maps to its
     * registered {@code SHARED_EMITTER_COVERAGE} rule, carrying the
     * identified offending operation's own exact source range (ISSUE-0750
     * D4), leaving the staged set empty.
     */
    private static void assertEmitterRejectsMissingFact(String what,
            Lowered lowered, HostDeclarationSurface surface,
            String... requiredDetails) throws Exception {
        for (Target target : Target.values()) {
            Path out = Files.createTempDirectory("jsonable-missing-fact-");
            PublicationStager stager = PublicationStager.forRoot(out);
            String rejection = null;
            DiagnosticRange carried = null;
            try {
                if (target == Target.LUAJIT) {
                    LuaSemanticEmitter.emitProductionProject(lowered.project(),
                        lowered.tables(), lowered.registries(), surface);
                } else {
                    JvmSemanticEmitter.emitProductionProject(lowered.project(),
                        lowered.tables(), lowered.registries(), "Probe", surface);
                }
            } catch (IllegalStateException guard) {
                rejection = guard.getMessage();
                // The source-bearing shared emitter signal (ISSUE-0750 D4)
                // carries the explicit offending op's own copied range; an
                // ordinary genuinely originless refusal carries none.
                carried = guard instanceof deal.semantic.ir.DiagnosticOriginCarrier carrier
                    ? carrier.diagnosticRange() : null;
            }
            boolean names = rejection != null;
            for (String detail : requiredDetails) {
                names = names && rejection.contains(detail);
            }
            check(names, what + " [" + target.laneName() + "]: the emitter fails "
                + "closed with the named producer defect (" + rejection + ")");
            check(carried != null && carried.origin() == RangeOrigin.SOURCE
                    && carried.scalarLength() > 0,
                what + " [" + target.laneName() + "]: the identified refusal "
                    + "carries a real SOURCE range: " + carried);
            // The carried range is the own copied span of the operation the
            // unchanged message identifies (never the canonical synthetic
            // anchor, an enclosing construct, or a last-visited fallback).
            boolean ownRange = false;
            if (rejection != null && carried != null) {
                for (Map.Entry<ModuleId, LoweredModuleUnit> module
                        : lowered.project().modules().entrySet()) {
                    for (SemanticOp op : module.getValue().ops()) {
                        if (rejection.contains(op.opId().toString())
                                && carried.equals(op.origin().span().range())) {
                            ownRange = true;
                            break;
                        }
                    }
                }
            }
            check(ownRange, what + " [" + target.laneName() + "]: the carried "
                + "range is the own range of the op the unchanged message "
                + "identifies: " + carried);
            check(stager.stagedSet().relativePaths().isEmpty(),
                what + " [" + target.laneName() + "]: the fail-closed emission "
                    + "stages nothing");
            deleteRecursively(out);
        }
    }

    /**
     * The inconsistent-fact negatives (the task criterion's "a missing
     * helper export or a missing callee entry fails closed with the
     * guard's rule identifier and stages nothing"): the otherwise valid
     * cross-module project's generated {@code Widget$fromJson} with its
     * export removed (a hand-built checked-project fact) fails the real
     * production arm closed with one E6005 and an empty staged set on both
     * targets; the missing recorded callee entry fails the real production
     * arm closed the same way through a checker-valid cross-module call to
     * an export the declaring module records no entry for (the entry
     * delegation owns it); and at the lowered-IR mechanism level the same
     * helper facts — a missing {@code EXPORT_PUBLISH} publication and a
     * missing recorded {@code EXTERNAL_ENTRY} — are rejected by both
     * production emitters with the named producer defect and stage nothing.
     */
    private static void testMissingHelperFactsFailClosed(CorpusObservation observation)
            throws Exception {
        System.out.println("-- the inconsistent-fact negatives: a missing generated "
            + "helper export and a missing recorded callee entry fail closed "
            + "through the producer guard and stage nothing --");
        checkEq("SHARED_EMITTER_COVERAGE",
            ProductionProjectEmission.SHARED_EMITTER_COVERAGE,
            "the registered emitter-coverage rule id an emitter rejection maps to");
        Project project = observation.project();
        {
            boolean compiled = observation.compilation().compiled();
            CheckedProjectBuildResult built = observation.compilation().built();
            RequirementManifestResult manifests = observation.compilation().manifests();
            HostDeclarationSurface surface = observation.compilation().surface();
            check(compiled && built != null && built.input() != null
                    && built.index() != null && manifests != null
                    && manifests.manifests() != null && surface != null,
                "the negatives' checked project builds: "
                    + (built == null ? "no checked project" : built.diagnostics()));
            if (!compiled || built == null || built.input() == null
                    || built.index() == null || manifests == null
                    || manifests.manifests() == null || surface == null) {
                return;
            }
            // (a) The missing helper export through the real production arm:
            // the declaring module's checked export list lacks
            // Widget$fromJson while the cross-module caller still reads it.
            ModuleId lib = new ModuleId("jsonable_lib");
            CheckedProjectInput missingExport = withoutExport(built.input(), lib,
                "Widget$fromJson");
            for (Target target : Target.values()) {
                Path out = project.root().resolve("out-missing-export-"
                    + target.laneName());
                PublicationStager stager = PublicationStager.forRoot(out);
                ProductionProjectEmission.Result result;
                List<String> staged;
                try {
                    result = ProductionProjectEmission.run(productionInvocation(),
                        missingExport, built.index(), manifests.manifests(), surface,
                        Map.of(), BuiltinErrorDeclaration.synthesized(
                            built.input().modules().get(0).ast().span()),
                        java.util.Set.of(), target == Target.JVM ? Backend.JVM : Backend.LUAJIT, false,
                        DistributionHome.forManifestDirectory(
                            project.root().toString()), stager);
                    staged = new ArrayList<>(stager.stagedSet().relativePaths());
                } finally {
                    stager.discard();
                }
                CompilerDiagnostic first = result.firstDiagnostic();
                check(!result.emitted(), "the missing helper export fails the "
                    + target.laneName() + " production arm closed");
                checkEq("E6005", first == null ? null : first.code(),
                    "the missing helper export merges exactly one E6005 ["
                        + target.laneName() + "]");
                check(first != null && first.message().contains("CONSTRUCT_UNLOWERED")
                        && first.message().contains("Widget$fromJson")
                        && first.message().contains("no recorded EXTERNAL_ENTRY"),
                    "the missing helper export fails through the CONSTRUCT_UNLOWERED "
                        + "guard naming the helper's absent entry ["
                        + target.laneName() + "]: " + (first == null ? null
                            : first.message()));
                check(staged.isEmpty(), "the missing helper export stages nothing ["
                    + target.laneName() + "] (staged " + staged + ")");
            }
            // (b) The missing recorded callee entry through the real
            // production arm: the callee-side counterpart of (a). The
            // declaring module publishes an export whose invocation the
            // module records no EXTERNAL_ENTRY for — the generated
            // helpers' own entry mechanism consumes exactly this relation
            // (the reference's externalEntryRef and the emitter's
            // resolveExternalEntry), and the entry function's
            // entry-delegation-owned export is the checker-valid shape of
            // the fact.
            for (Target target : Target.values()) {
                Path entryRoot = Files.createTempDirectory(
                    "jsonable-missing-entry-");
                try {
                    Path src = entryRoot.resolve("src");
                    Files.createDirectories(src);
                    Files.writeString(src.resolve("lib.deal"),
                        "export function main(): null {\n  return null;\n}\n",
                        StandardCharsets.UTF_8);
                    Files.writeString(src.resolve("app.deal"),
                        "import * as Lib from \"./lib\";\n\n"
                            + "export function test_cross_module_main(): null {\n"
                            + "  Lib.main();\n"
                            + "  return null;\n}\n\n"
                            + "export function main(): null {\n  return null;\n}\n",
                        StandardCharsets.UTF_8);
                    Files.writeString(entryRoot.resolve("deal.json"),
                        "{\n  \"languageVersion\": \"1.5\",\n"
                            + "  \"moduleRoots\": [\"src\"],\n"
                            + "  \"output\": \"out\",\n"
                            + "  \"backend\": \""
                            + (target == Target.JVM ? "jvm" : "luajit")
                            + "\"\n}\n",
                        StandardCharsets.UTF_8);
                    Project entryProject = new Project(entryRoot, src,
                        src.resolve("app.deal"), "app", Map.of(),
                        entryRoot.resolve("out"));
                    List<String> diagnostics = new ArrayList<>();
                    boolean entryCompiled = compile(entryProject,
                        "missing-callee-entry", target, diagnostics);
                    check(!entryCompiled, "the missing recorded callee entry fails the "
                        + target.laneName() + " production arm closed: "
                        + diagnostics);
                    check(diagnostics.stream().anyMatch(d -> d.startsWith("E6005 ")
                            && d.contains("CONSTRUCT_UNLOWERED")
                            && d.contains("no recorded EXTERNAL_ENTRY")),
                        "the missing recorded callee entry fails through the "
                            + "CONSTRUCT_UNLOWERED guard naming the absent entry ["
                            + target.laneName() + "]: " + diagnostics);
                    check(!Files.exists(artifactOf(entryProject, target)),
                        "the missing recorded callee entry stages nothing ["
                            + target.laneName() + "]");
                } finally {
                    deleteRecursively(entryRoot);
                }
            }
            // (c) The same facts at the lowered-IR mechanism level: the
            // otherwise valid project's generated helper loses its
            // EXPORT_PUBLISH publication (a missing helper export), and,
            // separately, its recorded EXTERNAL_ENTRY (a missing callee
            // entry while the export stays published).
            Lowered lowered = observation.lowered();
            if (lowered == null) {
                return;
            }
            // The hand-built facts are reachable in the retained project:
            // the entry module reads the helper export the missing-export
            // fact retargets, and it really calls the callee entry the
            // missing-entry fact removes. Each emitter refusal therefore
            // carries the own range of the read/call operation its
            // unchanged message identifies (ISSUE-0750 D4).
            boolean readsHelperExport = false;
            boolean callsRemovedEntry = false;
            SemanticOp removedEntry = null;
            for (SemanticOp op : lowered.project().modules().get(lib).ops()) {
                if (op.kind() == SemanticOpKind.EXTERNAL_ENTRY
                        && op.payload().toString().contains("Widget$fromJson")) {
                    removedEntry = op;
                }
            }
            for (SemanticOp op : lowered.project()
                    .modules().get(lowered.entryModule()).ops()) {
                if (op.kind() == SemanticOpKind.EXPORT_READ
                        && op.payload() instanceof KindPayload.ExportReadPayload read
                        && read.module().equals(lib)
                        && read.name().equals("Widget$fromJson")) {
                    readsHelperExport = true;
                }
                if (op.kind() == SemanticOpKind.CALL
                        && removedEntry != null
                        && op.payload() instanceof KindPayload.CallPayload call
                        && removedEntry.opId().equals(call.externalEntryRef())) {
                    callsRemovedEntry = true;
                }
            }
            check(readsHelperExport,
                "the entry module records the helper export read the missing "
                    + "export fact retargets");
            check(callsRemovedEntry,
                "the entry module records the cross-module call the missing "
                    + "callee-entry fact retargets");
            assertEmitterRejectsMissingFact("the missing helper export",
                withoutUnitOps(lowered, lib,
                    op -> op.kind() == SemanticOpKind.EXPORT_PUBLISH
                        && op.payload().toString().contains("Widget$fromJson")),
                surface, "Widget$fromJson", "never publishes");
            assertEmitterRejectsMissingFact("the missing recorded callee entry",
                withoutUnitOps(lowered, lib,
                    op -> op.kind() == SemanticOpKind.EXTERNAL_ENTRY
                        && op.payload().toString().contains("Widget$fromJson")),
                surface, "externalEntryRef",
                "does not resolve to a recorded EXTERNAL_ENTRY");
        }
    }

    // =========================================================================
    // 7b. The three-consumer walk regressions
    // =========================================================================

    /** One synthetic lane-equivalent project of an inline source. */
    private static Project syntheticProject(String moduleName, String source,
            Target target) throws Exception {
        Path root = Files.createTempDirectory("jsonable-synthetic-");
        Path srcRoot = root.resolve("src");
        Files.createDirectories(srcRoot);
        Path entryFile = srcRoot.resolve(moduleName + ".deal");
        Files.writeString(entryFile, source, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.5\",\n"
                + "  \"moduleRoots\": [\"src\"],\n  \"output\": \"out\",\n"
                + "  \"backend\": \"" + (target == Target.JVM ? "jvm" : "luajit")
                + "\"\n}\n", StandardCharsets.UTF_8);
        Materialized materialized = new Materialized(moduleName + ".deal", source, 0);
        Map<String, Materialized> modules = new LinkedHashMap<>();
        modules.put(entryFile.toAbsolutePath().normalize().toString(), materialized);
        modules.put(moduleName + ".deal", materialized);
        return new Project(root, srcRoot, entryFile, moduleName, modules,
            root.resolve("out"));
    }

    private record PreparedSynthetic(Project project, Compilation compilation,
                                     Lowered oracle) implements AutoCloseable {
        @Override
        public void close() throws java.io.IOException {
            deleteRecursively(project.root());
        }
    }

    private static PreparedSynthetic prepareSynthetic(String label, String source,
            List<Export> exports) throws Exception {
        return prepareSynthetic(label, source, exports, Map.of());
    }

    private static PreparedSynthetic prepareSynthetic(String label, String source,
            List<Export> exports, Map<String, String> companions) throws Exception {
        Project project = syntheticProject("app", source, Target.LUAJIT);
        try {
            for (Map.Entry<String, String> companion : companions.entrySet()) {
                Files.writeString(project.srcRoot().resolve(companion.getKey() + ".deal"),
                    companion.getValue(), StandardCharsets.UTF_8);
            }
            Path driver = project.srcRoot().resolve("__oracle_drive.deal");
            Files.writeString(driver, oracleDriver(project, exports), StandardCharsets.UTF_8);
            Project oracleProject = new Project(project.root(), project.srcRoot(), driver,
                "__oracle_drive", project.modules(), project.root().resolve("out-oracle"));
            Compilation observation = compileObservation(oracleProject, label, Target.LUAJIT);
            return new PreparedSynthetic(project,
                fixtureCompilation(observation, new ModuleId(project.modulePath())),
                lower(observation, label + " oracle"));
        } catch (Exception failure) {
            deleteRecursively(project.root());
            throw failure;
        }
    }

    private static Lowered driveHelperFailure(String label, String source, String call,
            String message, String actual) throws Exception {
        List<Export> exports = exportsOf(source);
        String position = lineOf(source, call) + ":" + columnOf(source, call);
        try (PreparedSynthetic prepared = prepareSynthetic(label, source, exports)) {
            Lowered lowered = prepared.oracle();
            SemanticRuntimeModel.ErrorSnapshot oracle = oracleMainFailure(lowered);
            check(oracle != null && oracle.code().equals("E8001")
                    && oracle.message().equals(message)
                    && java.util.Objects.equals(actual, oracle.actual())
                    && oracle.expected() == null && position.equals(originPosition(oracle.origin())),
                label + ": literal oracle failure tuple and call origin: " + oracle);
            for (Target target : Target.values()) {
                Project project = withOutput(prepared.project(), "out-" + target.laneName());
                Compilation compilation = emitChecked(project, prepared.compilation(), target);
                check(compilation.compiled(), label + " [" + target.laneName()
                    + "]: production emission: " + compilation.diagnostics());
                if (!compilation.compiled()) {
                    continue;
                }
                Execution execution = target == Target.LUAJIT
                    ? executeLua(project, exports) : executeJvm(project, exports);
                Capture capture = execution.capture();
                check(execution.exitCode() == 1 && execution.stdout().isEmpty()
                        && execution.stderr().isEmpty() && !execution.probeDefect()
                        && capture != null && capture.code().equals("E8001")
                        && capture.message().equals(message) && capture.expected() == null
                        && java.util.Objects.equals(actual, capture.actual())
                        && position.equals(capture.line() + ":" + capture.column())
                        && capture.sourceFile() != null && capture.sourceFile().endsWith("app.deal"),
                    label + " [" + target.laneName() + "]: exact completed failure: " + execution);
            }
            return lowered;
        }
    }

    private static int lineOf(String source, String needle) {
        return source.substring(0, source.indexOf(needle)).split("\n", -1).length;
    }

    /** The 1-based column of the first occurrence of a token in a source. */
    private static int columnOf(String source, String needle) {
        int index = source.indexOf(needle);
        return index - source.lastIndexOf('\n', index - 1);
    }

    /** The {@code line:column} suffix of a {@code file:line:column} origin. */
    private static String originPosition(String origin) {
        if (origin == null) {
            return null;
        }
        int lastColon = origin.lastIndexOf(':');
        int prevColon = lastColon < 0 ? -1 : origin.lastIndexOf(':', lastColon - 1);
        if (prevColon < 0) {
            return null;
        }
        return origin.substring(prevColon + 1);
    }

    /**
     * The function-parameter helper invocation (the three-consumer
     * regression): a helper passed to a function-typed parameter and
     * invoked dynamically ({@code f(w)}) renders its pinned JSON failure
     * at the {@code f(w)} call expression in the oracle, the LuaJIT
     * artifact, and the JVM artifact alike — never at an enclosing static
     * call or the generated declaration anchor.
     */
    private static void testDynamicHelperOrigin() throws Exception {
        System.out.println("-- the function-parameter helper invocation: the f(w) "
            + "origin on all three consumers --");
        String source = """
            // @jsonable
            export class Wrapper {
              data: Record<string, unknown>;
            }

            function apply(f: (w: Wrapper) => string, w: Wrapper): string {
              return f(w);
            }

            export function test_dynamic_helper_origin(): string {
              let w: Wrapper = { data: {} };
              w.data.self = w.data;
              return apply(Wrapper$toJson, w);
            }

            export function main(): null {
              return null;
            }
            """;
        Lowered lowered = driveHelperFailure("dynamic-helper-origin", source, "f(w)",
            "cyclic value cannot be encoded as JSON", null);
        check(lowered != null && lowered.project().modules().values().stream()
            .flatMap(unit -> unit.ops().stream()).anyMatch(op ->
                op.kind() == SemanticOpKind.CALL
                    && op.payload() instanceof KindPayload.CallPayload call
                    && call.callee() instanceof KindPayload.CallCallee.Dynamic
                    && call.dynamicReturnBoundary() != null),
            "the f(w) call lowers as a dynamic DEAL-body invocation");
    }

    /**
     * The first-class helper binding (the three-consumer regression): each
     * generated helper stored in a function-typed binding and invoked
     * through that binding is a same-module reference, so the checker-valid
     * project lowers with zero E6005 and the declaration's real helper
     * result on all three consumers — the binding-carried call resolves the
     * declaration's generated {@code LoweredBody} and its single return
     * cell while the helper's one reserved {@code EXTERNAL_ENTRY} stays the
     * cross-module record.
     */
    private static void testHelperFunctionBindingInvocation() throws Exception {
        System.out.println("-- the first-class helper binding: each generated helper "
            + "stored in a function-typed binding and invoked through it "
            + "(zero E6005, the real result on all three consumers) --");
        String source = """
            // @jsonable
            export class Box {
              x: number;
            }

            export function test_helper_binding(): null {
              let b: Box = { x: 2 };
              let f: (v: Box) => string = Box$toJson;
              let json: string = f(b);
              let expected: string = "{\\"x\\":2.0}";
              if (json !== expected) {
                throw { code: "MISMATCH", message: json };
              }
              let g: (s: string) => Box | null = Box$fromJson;
              let parsed: Box | null = g(expected);
              let reparsed: string = "";
              if (parsed === null) {
                throw { code: "MISMATCH", message: "fromJson binding returned null" };
              } else {
                reparsed = f(parsed);
              }
              if (reparsed !== expected) {
                throw { code: "MISMATCH", message: reparsed };
              }
              return null;
            }

            export function main(): null {
              return null;
            }
            """;
        Compilation compilation = driveExactHelperProgram("helper-function-binding", source);
        // The reserved shape survives the carrier-carried call: each
        // helper keeps exactly one CLOSURE_NEW and one reserved
        // EXTERNAL_ENTRY, and every binding-invoked helper CALL resolves
        // that declaration's LoweredBody, its body block, and its single
        // return cell without replacing the reserved shape (no
        // same-module CALL(EXTERNAL) and no second entry).
        {
            ExecutableLoweredProject produced = compilation == null ? null
                : compilation.producedProject();
            check(produced != null, "helper binding IR: the production compile exposes its project");
            if (produced == null) {
                return;
            }
            LoweredModuleUnit unit = produced.modules().get(produced.entryModule());
            Map<FunctionId, OpId> entryReturnBoundary = new LinkedHashMap<>();
            for (SemanticOp op : unit.ops()) {
                if (op.kind() == SemanticOpKind.EXTERNAL_ENTRY
                        && op.payload() instanceof KindPayload.ExternalEntryPayload entry
                        && entry.exportName().startsWith("Box$")) {
                    entryReturnBoundary.put(entry.function(), entry.returnBoundaryOpId());
                }
            }
            checkEq(2, entryReturnBoundary.size(),
                "exactly one reserved EXTERNAL_ENTRY per helper export");
            Map<FunctionId, ValueId> closureIdentity = new LinkedHashMap<>();
            for (SemanticOp op : unit.ops()) {
                if (op.kind() == SemanticOpKind.CLOSURE_NEW
                        && op.payload() instanceof KindPayload.ClosureNewPayload closure
                        && op.result() instanceof ValueId identity
                        && entryReturnBoundary.containsKey(closure.function())) {
                    closureIdentity.put(closure.function(), identity);
                }
            }
            checkEq(2, closureIdentity.size(),
                "exactly one generated closure per helper");
            Set<ValueId> loadedIdentities = new LinkedHashSet<>();
            for (SemanticOp op : unit.ops()) {
                if (op.kind() == SemanticOpKind.BINDING_LOAD
                        && op.result() instanceof ValueId loaded) {
                    loadedIdentities.add(loaded);
                }
            }
            for (Map.Entry<FunctionId, ValueId> closure : closureIdentity.entrySet()) {
                check(loadedIdentities.contains(closure.getValue()),
                    "the helper binding load publishes the declaration's closure "
                        + "identity " + closure.getValue());
            }
            int bindingHelperCalls = 0;
            int externalHelperCalls = 0;
            for (SemanticOp op : unit.ops()) {
                if (op.kind() != SemanticOpKind.CALL
                        || !(op.payload() instanceof KindPayload.CallPayload call)) {
                    continue;
                }
                if (call.callee() instanceof KindPayload.CallCallee.Static staticCallee
                        && staticCallee.binding()
                            instanceof FunctionExecutionBinding.LoweredBody body
                        && entryReturnBoundary.containsKey(body.functionId())) {
                    bindingHelperCalls++;
                    LoweredFunction helper = unit.functions().get(body.functionId());
                    check(helper != null && helper.body().equals(call.bodyBlock()),
                        "the binding-invoked helper call names the declaration's "
                            + "body block");
                    checkEq(entryReturnBoundary.get(body.functionId()),
                        call.returnBoundaryOpId(),
                        "the binding-invoked helper call shares the helper's single "
                            + "return cell");
                }
                if (call.callee() instanceof KindPayload.CallCallee.Static staticCallee
                        && staticCallee.binding()
                            instanceof FunctionExecutionBinding.ExternalFunction external
                        && external.moduleId().equals(produced.entryModule())
                        && external.exportName().startsWith("Box$")) {
                    externalHelperCalls++;
                }
            }
            checkEq(3, bindingHelperCalls, "every helper binding invocation resolves "
                + "the declaration's LoweredBody (got " + bindingHelperCalls + ")");
            checkEq(0, externalHelperCalls, "no same-module helper call resolves the "
                + "ExternalFunction shape (the entry stays the cross-module record)");
        }
    }

    /**
     * Nested class instances in Record content use ordinary JSON encoding
     * on the Oracle and every real backend.
     */
    private static void testTableClassInstanceEncoding() throws Exception {
        System.out.println("-- nested Record class content encodes on every consumer --");
        String source = """
            // @jsonable
            export class Child {
              value: number;
            }

            // @jsonable
            export class Wrapper {
              data: Record<string, unknown>;
            }

            export function test_table_class_instance(): string {
              let child: Child = { value: 7 };
              let w: Wrapper = { data: {} };
              w.data.children = [child];
              return Wrapper$toJson(w);
            }

            export function main(): null {
              return null;
            }
            """;
        driveThreeConsumerParity("table-class-instance", source);
    }

    // =========================================================================
    // 7c. The nested-class table-content text regressions
    // =========================================================================

    /** The DEAL string literal spelling of one text. */
    private static String dealStringLiteral(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
            + "\"";
    }

    /**
     * The exact helper-text drive: the DEAL body compares the helper's
     * returned text against the exact expected string and throws the actual
     * text on any difference, so every consumer that completes proves the
     * exact string value — the oracle, the LuaJIT artifact, and the JVM
     * artifact alike. The artifact legs are only credited with the exact
     * text when the process actually completes its tested body: exit 0, both
     * transcripts empty, no probe defect, and no failure transport
     * ({@link #exactHelperArtifactMismatches}).
     */
    private static Compilation driveExactHelperProgram(String label, String source)
            throws Exception {
        return driveExactHelperProgram(label, source, Map.of());
    }

    private static Compilation driveExactHelperProgram(String label, String source,
            Map<String, String> companions) throws Exception {
        List<Export> exports = exportsOf(source);
        try (PreparedSynthetic prepared = prepareSynthetic(label, source, exports, companions)) {
            Project project = prepared.project();
            SemanticRuntimeModel.ErrorSnapshot oracle = oracleMainFailure(prepared.oracle());
            Compilation preparation = prepared.compilation();
            check(oracle == null, label + ": the oracle produces the exact "
                + "expected helper text (outcome "
                + (oracle == null ? "success"
                    : oracle.code() + " " + oracle.message())
                + ")");
            Compilation jvmCompilation = null;
            for (Target target : Target.values()) {
                Project artifactProject = target == Target.LUAJIT ? project
                    : withOutput(project, "out-jvm");
                Compilation compilation = emitChecked(artifactProject, preparation, target);
                if (target == Target.JVM) {
                    jvmCompilation = compilation;
                }
                check(compilation.compiled(), label + " [" + target.laneName()
                    + "]: the project compiles through the production invocation: "
                    + compilation.diagnostics());
                if (!compilation.compiled()) {
                    continue;
                }
                Execution execution = target == Target.LUAJIT
                    ? executeLua(artifactProject, exports)
                    : executeJvm(artifactProject, exports);
                for (String mismatch : exactHelperArtifactMismatches(execution)) {
                    check(false, label + " [" + target.laneName() + "]: " + mismatch);
                }
            }
            return jvmCompilation;
        }
    }

    private static String combineExactHelperSources(String... sources) {
        StringBuilder combined = new StringBuilder();
        for (int index = 0; index < sources.length; index++) {
            String source = sources[index];
            source = source.substring(0, source.lastIndexOf("export function main()"));
            Matcher classes = Pattern.compile("export class (\\w+)").matcher(source);
            Map<String, String> names = new LinkedHashMap<>();
            while (classes.find()) {
                String name = classes.group(1);
                names.put(name, "Scenario" + index + "_" + name);
            }
            Matcher identifiers = Pattern.compile(
                "\"(?:\\\\.|[^\"\\\\])*\"|//[^\\n]*|\\b[A-Za-z_][A-Za-z_0-9]*\\b")
                .matcher(source);
            StringBuilder renamed = new StringBuilder();
            while (identifiers.find()) {
                identifiers.appendReplacement(renamed, Matcher.quoteReplacement(
                    names.getOrDefault(identifiers.group(), identifiers.group())));
            }
            identifiers.appendTail(renamed);
            combined.append(renamed).append('\n');
        }
        return combined.append("export function main(): null {\n  return null;\n}\n")
            .toString();
    }

    /**
     * The exact-text artifact leg's completed-process contract: the process
     * exits 0, both transcripts stay empty, the probe reaches every export
     * (no {@code PROBE_DEFECT}), and no failure transport was written. A
     * nonzero status or a stray transcript is a mismatch, never a credited
     * exact text: {@code executeLua}/{@code executeJvm} return a null
     * capture whenever the transport file is absent, which a process that
     * dies before writing it (or before running the tested body) would
     * otherwise pass.
     */
    private static List<String> exactHelperArtifactMismatches(
            Execution execution) {
        List<String> mismatches = new ArrayList<>();
        if (execution.exitCode() != 0) {
            mismatches.add("the artifact exits nonzero (exit "
                + execution.exitCode() + "; stderr " + execution.stderr() + ")");
        }
        if (!execution.stdout().isEmpty()) {
            mismatches.add("the artifact run emits unexpected stdout ("
                + execution.stdout() + ")");
        }
        if (!execution.stderr().isEmpty()) {
            mismatches.add("the artifact run emits unexpected stderr ("
                + execution.stderr() + ")");
        }
        if (execution.probeDefect()) {
            mismatches.add("the artifact drive misses an export ("
                + (execution.capture() == null ? ""
                    : execution.capture().message()) + ")");
        }
        if (execution.capture() != null) {
            mismatches.add("the artifact raises a failure instead of the exact "
                + "expected helper text (" + execution.capture().code() + " "
                + execution.capture().message() + ")");
        }
        return mismatches;
    }

    /**
     * The valid-depth artifact leg's completed-process credit: the same
     * comparison the exact-text drives use, so the deep chain is credited
     * only when the fixture process exits 0 with
     * empty transcripts, no probe defect, and no failure transport. A
     * nonzero exit without a written transport file — a toolchain that dies
     * before running the tested body — is a mismatch, never a completed
     * encode, so it can never be credited as the accepted-depth success.
     */
    private static List<String> validDepthArtifactMismatches(Execution execution) {
        return exactHelperArtifactMismatches(execution);
    }

    /** The shared nested-class fixture: {@code Wrapper.child.data} is the table field. */
    private static String nestedTableFixtureSource(String function) {
        return "// @jsonable\nexport class Child {\n  data: Record<string, unknown>;\n}\n\n"
            + "// @jsonable\nexport class Wrapper {\n  child: Child;\n}\n\n"
            + function
            + "\nexport function main(): null {\n  return null;\n}\n";
    }

    /**
     * The first-insertion-order regression (the three-consumer exact text):
     * a nested class's table field spells its keys in first-insertion order,
     * never in sorted order, so the same object value yields the same string
     * on the oracle and both production artifacts.
     */
    private static String nestedTableInsertionOrderFunction() {
        System.out.println("-- the nested-class table field's first-insertion order: "
            + "the exact three-consumer text --");
        String expected = "{\"child\":{\"data\":{\"z\":\"first\","
            + "\"a\":\"second\"}}}";
        String function = "export function test_nested_table_order(): null {\n"
            + "  let w: Wrapper = { child: { data: {} } };\n"
            + "  w.child.data.z = \"first\";\n"
            + "  w.child.data.a = \"second\";\n"
            + "  let json: string = Wrapper$toJson(w);\n"
            + "  let expected: string = " + dealStringLiteral(expected) + ";\n"
            + "  if (json !== expected) {\n"
            + "    throw { code: \"MISMATCH\", message: json };\n"
            + "  }\n"
            + "  return null;\n}\n";
        return function;
    }

    /**
     * The numeric-variant regression (the three-consumer exact text): an
     * int-variant leaf inside a nested class's table field and array spells
     * its integer text and a number-variant leaf its closed decimal text on
     * every consumer — the shared carrier's {@code __nK} marks and
     * {@code __jn} variant carriers (and the JVM's boxed variants) decide
     * the spelling, never the walk's own default.
     */
    private static void testNestedTableNumericVariants() throws Exception {
        System.out.println("-- the nested-class table field's numeric variants: "
            + "number and number leaves in tables and arrays, exact text on all "
            + "three consumers --");
        String expected = "{\"child\":{\"data\":{\"i\":3.0,\"n\":2.5,"
            + "\"ai\":[7.0],\"an\":[1.5],\"ri\":1.0,\"rn\":6.5}}}";
        String function = "export function test_nested_table_numbers(): null {\n"
            + "  let w: Wrapper = { child: { data: {} } };\n"
            + "  w.child.data.i = 3;\n"
            + "  w.child.data.n = 2.5;\n"
            + "  w.child.data.ai = [7];\n"
            + "  w.child.data.an = [1.5];\n"
            // The read-side variant carriers: an int-variant slot read at a
            // number position and a number-variant slot read at a number
            // position both travel as carriers and must keep their own
            // variant's spelling.
            + "  let t: Record<string, unknown> = { x: 1 };\n"
            + "  let n: number = t.x as number;\n"
            + "  w.child.data.ri = n;\n"
            + "  let nums: number[] = [6.5];\n"
            + "  let fromNum: number = nums[0];\n"
            + "  w.child.data.rn = fromNum;\n"
            + "  let json: string = Wrapper$toJson(w);\n"
            + "  let expected: string = " + dealStringLiteral(expected) + ";\n"
            + "  if (json !== expected) {\n"
            + "    throw { code: \"MISMATCH\", message: json };\n"
            + "  }\n"
            + "  return null;\n}\n";
        driveExactHelperProgram("nested-table-order-and-numbers",
            nestedTableFixtureSource(nestedTableInsertionOrderFunction() + "\n" + function));
    }

    /**
     * The nested-class deep fixture: {@code Wrapper.child.data} is the head
     * of a chain of {@code links} links (one table per link). The valid form
     * compares the helper text against the exact expected string; the
     * ({@code expectedText == null}) form returns the helper text directly
     * (defensive — the walk never raises for a deep finite chain).
     */
    private static String nestedClassDepthSource(int links, String expectedText) {
        StringBuilder body = new StringBuilder();
        body.append("  let w: Wrapper = { child: { data: {} } };\n");
        body.append("  let cur: Record<string, unknown> = w.child.data;\n");
        body.append("  let i: number = 0; while (i < ").append(links)
            .append(") {\n");
        body.append("    cur = linkAppend(cur); i = i + 1;\n  }\n");
        if (expectedText != null) {
            body.append("  let json: string = Wrapper$toJson(w);\n");
            body.append("  let expected: string = ")
                .append(dealStringLiteral(expectedText)).append(";\n");
            body.append("  if (json !== expected) {\n");
            body.append("    throw { code: \"MISMATCH\", message: json };\n  }\n");
            body.append("  return null;\n");
        } else {
            body.append("  return Wrapper$toJson(w);\n");
        }
        return "// @jsonable\nexport class Child {\n  data: Record<string, unknown>;\n}\n\n"
            + "// @jsonable\nexport class Wrapper {\n  child: Child;\n}\n\n"
            + "export function linkAppend(cur: Record<string, unknown>): Record<string, unknown> {\n"
            + "  let n: Record<string, unknown> = {};\n"
            + "  cur.next = n;\n"
            + "  return n;\n"
            + "}\n\n"
            + "export function test_nested_class_depth(): "
            + (expectedText != null ? "null" : "string") + " {\n"
            + body
            + "}\n\nexport function main(): null {\n  return null;\n}\n";
    }

    /** The exact JSON text of a {@code Wrapper.child.data} chain of {@code links} links. */
    private static String nestedChainText(int links) {
        return "{\"child\":{\"data\":" + "{\"next\":".repeat(links) + "{}"
            + "}".repeat(links) + "}}";
    }

    /**
     * No fixed JSON walk depth bound in the recursive nested-class path (the
     * three-consumer boundary): the 511-link chain (512 containers) and the
     * 1200-link chain (1201 containers) both complete with the exact text on
     * the oracle and both production artifacts. A finite chain is never
     * reported as a JSON value failure for its depth.
     */
    private static void testNestedClassDepthBound() throws Exception {
        System.out.println("-- the nested-class walk has no fixed depth bound: "
            + "511 and 1200 links succeed on all three consumers --");
        driveExactHelperProgram("nested-class-depth-ok",
            nestedClassDepthSource(511, nestedChainText(511)));
        driveExactHelperProgram("nested-class-depth-1200-ok",
            nestedClassDepthSource(1200, nestedChainText(1200)));
    }

    // =========================================================================
    // 7d. The oracle's live carrier views and the helper carrier regressions
    // =========================================================================

    /**
     * The three-consumer outcome parity of one inline project: the oracle's
     * success or failure tuple is the reference and every artifact must
     * agree — success where the oracle succeeds, the identical code and
     * message where the oracle fails. The exact-text drive proves a success
     * outcome's text; this parity drive proves the consumers agree on the
     * outcome itself (a deleted array element's admission or rejection).
     */
    private static void driveThreeConsumerParity(String label, String source)
            throws Exception {
        List<Export> exports = exportsOf(source);
        try (PreparedSynthetic prepared = prepareSynthetic(label, source, exports)) {
            SemanticRuntimeModel.ErrorSnapshot oracle = oracleMainFailure(prepared.oracle());
            String reference = oracle == null ? "success"
                : oracle.code() + " " + oracle.message();
            System.out.println("   " + label + ": the oracle reference outcome is "
                + reference);
            for (Target target : Target.values()) {
                Project project = withOutput(prepared.project(), "out-" + target.laneName());
                Compilation compilation = emitChecked(project, prepared.compilation(), target);
                List<String> diagnostics = compilation.diagnostics();
                boolean compiled = compilation.compiled();
                check(compiled, label + " [" + target.laneName()
                    + "]: the project compiles through the production invocation: "
                    + diagnostics);
                if (!compiled) {
                    continue;
                }
                Execution execution = target == Target.LUAJIT
                    ? executeLua(project, exports) : executeJvm(project, exports);
                Capture capture = execution.capture();
                if (oracle == null) {
                    for (String mismatch : exactHelperArtifactMismatches(
                            execution)) {
                        check(false, label + " [" + target.laneName()
                            + "]: the artifact agrees with the oracle's success: "
                            + mismatch);
                    }
                } else {
                    check(capture != null, label + " [" + target.laneName()
                        + "]: the artifact agrees with the oracle's failure (exit "
                        + execution.exitCode() + "; stderr " + execution.stderr()
                        + ")");
                    if (capture != null) {
                        checkEq(oracle.code(), capture.code(), label + " ["
                            + target.laneName() + "]: the failure code");
                        checkEq(oracle.message(), capture.message(), label + " ["
                            + target.laneName() + "]: the failure message");
                    }
                }
            }
        }
    }

    /**
     * The oracle's live array conversion view (the three-consumer
     * regression): a class field's array mutated in place by an element
     * replacement and an append is what the helper serializes on the
     * oracle, the LuaJIT artifact, and the JVM artifact alike — the live
     * executor view reads the carrier's current elements, so it never
     * serves the stale pre-commit contents.
     */
    private static String arrayExecutorViewSyncSource() {
        System.out.println("-- the oracle's live array view: replacement and "
            + "append serialize on all three consumers --");
        String source = "// @jsonable\nexport class Box {\n  xs: number[];\n}\n\n"
            + "// @jsonable\nexport class Wrapper {\n  data: Record<string, unknown>;\n}\n\n"
            + "export function test_array_mutation(): null {\n"
            + "  let b: Box = { xs: [1] };\n"
            + "  b.xs[0] = 2;\n"
            + "  b.xs[1] = 3;\n"
            // The second instance's field holds the same array identity;
            // mutating it through the alias must be visible through the
            // first instance's view (the live view is identity-keyed).
            + "  let c: Box = { xs: b.xs };\n"
            + "  c.xs[0] = 9;\n"
            + "  let json: string = Box$toJson(b);\n"
            + "  let expected: string = " + dealStringLiteral("{\"xs\":[9.0,3.0]}")
            + ";\n"
            + "  if (json !== expected) {\n"
            + "    throw { code: \"MISMATCH\", message: json };\n"
            + "  }\n"
            + "  return null;\n"
            + "}\n\n"
            // The array nested in a table field: the live table view
            // reads the array carrier's current contents, so the same
            // in-place commit is what the descriptor-free table walk
            // serializes.
            + "export function test_table_nested_array(): null {\n"
            + "  let w: Wrapper = { data: { xs: [1] } };\n"
            + "  let arr: number[] = w.data.xs as number[];\n"
            + "  arr[0] = 2;\n"
            + "  arr[1] = 3;\n"
            + "  let json: string = Wrapper$toJson(w);\n"
            + "  let expected: string = "
            + dealStringLiteral("{\"data\":{\"xs\":[2.0,3.0]}}") + ";\n"
            + "  if (json !== expected) {\n"
            + "    throw { code: \"MISMATCH\", message: json };\n"
            + "  }\n"
            + "  return null;\n"
            + "}\n\n"
            + "export function main(): null {\n  return null;\n}\n";
        return source;
    }

    /**
     * The oracle's live view on deletion (the three-consumer agreement):
     * an element deleted from a class field's array is observed by the
     * helper walk on the oracle and both artifacts with the same outcome —
     * the live view reads the deleted slot as the missing element, never
     * the stale pre-delete value.
     */
    private static void testArrayExecutorViewDeleteParity() throws Exception {
        System.out.println("-- the oracle's live array view: a deleted element "
            + "agrees on all three consumers --");
        String source = "// @jsonable\nexport class Box {\n  xs: number[];\n}\n\n"
            + "export function test_array_delete(): string {\n"
            + "  let b: Box = { xs: [1.0, 2] };\n"
            + "  b.xs[0] = 9;\n"
            + "  return Box$toJson(b);\n"
            + "}\n\n"
            + "export function main(): null {\n  return null;\n}\n";
        driveThreeConsumerParity("array-executor-view-delete", source);
        // The same missing-slot semantics in the descriptor-free table
        // walk (an array nested in a table field): the deleted slot is the
        // internal missing element on every consumer, never a serialized
        // null or the stale pre-delete value.
        String tableSource = "// @jsonable\nexport class Wrapper {\n"
            + "  data: Record<string, unknown>;\n}\n\n"
            + "export function test_table_array_delete(): string {\n"
            + "  let w: Wrapper = { data: { xs: [1.0, 2] } };\n"
            + "  let arr: number[] = w.data.xs as number[];\n"
            + "  arr[0] = 9;\n"
            + "  return Wrapper$toJson(w);\n"
            + "}\n\n"
            + "export function main(): null {\n  return null;\n}\n";
        driveThreeConsumerParity("table-array-view-delete", tableSource);
    }

    /**
     * The exact-text artifact leg's negative controls: the comparator (the
     * control) accepts a clean zero-status, empty-transcript run and rejects
     * a nonzero execution without a transport file — the case that only
     * {@code capture == null} previously credited as the exact text — plus
     * unexpected stdout, unexpected stderr, a probe defect, and a written
     * failure transport.
     */
    private static void testExactHelperArtifactNegativeControls() {
        System.out.println("-- the exact-text artifact leg's negative controls: a "
            + "nonzero execution without a transport file is rejected --");
        Execution clean = new Execution(0, "", "", null, false);
        check(exactHelperArtifactMismatches(clean).isEmpty(),
            "the comparator accepts a completed exact-text run (the control)");
        Execution nonzeroNoTransport = new Execution(1, "", "", null, false);
        List<String> nonzero = exactHelperArtifactMismatches(
            nonzeroNoTransport);
        check(nonzero.stream().anyMatch(m -> m.contains("exits nonzero")),
            "the comparator rejects a nonzero execution without a transport "
                + "file: " + nonzero);
        Execution strayStdout = new Execution(0, "noise\n", "", null, false);
        check(exactHelperArtifactMismatches(strayStdout).stream()
                .anyMatch(m -> m.contains("unexpected stdout")),
            "the comparator rejects unexpected stdout: "
                + exactHelperArtifactMismatches(strayStdout));
        Execution strayStderr = new Execution(0, "", "boom\n", null, false);
        check(exactHelperArtifactMismatches(strayStderr).stream()
                .anyMatch(m -> m.contains("unexpected stderr")),
            "the comparator rejects unexpected stderr: "
                + exactHelperArtifactMismatches(strayStderr));
        Capture defect = new Capture("PROBE_DEFECT", "no export", null, null, null,
            null, null);
        Execution probeDefect = new Execution(1, "", "", defect, true);
        check(exactHelperArtifactMismatches(probeDefect).stream()
                .anyMatch(m -> m.contains("misses an export")),
            "the comparator rejects a probe defect: "
                + exactHelperArtifactMismatches(probeDefect));
        Capture failure = new Capture("E8001", "boom", null, null, null, null, null);
        Execution captured = new Execution(1, "", "", failure, false);
        check(exactHelperArtifactMismatches(captured).stream()
                .anyMatch(m -> m.contains("raises a failure")),
            "the comparator rejects a written failure transport: "
                + exactHelperArtifactMismatches(captured));
    }

    /**
     * The runtime-ok acceptance's negative controls (the wrong-observation
     * policy): the comparator accepts the pin-exact observation (the
     * control) and rejects an incorrect exit status, an unexpected stdout
     * or stderr transcript, a raised failure capture, and a missed export —
     * each a fatal mismatch of the cell that produced the observation. The
     * verdict is a pure function of the observation (there is no deficit
     * inventory and no other cell's construct state in it), so a construct
     * deficit in another fixture or target can never excuse an unrelated
     * runtime-ok divergence.
     */
    private static void testRuntimeOkNegativeControls() {
        System.out.println("-- the runtime-ok acceptance's negative controls: a "
            + "wrong observation is fatal independently of any other cell --");
        SidecarExpectations.RuntimeExpectation.Executed pin =
            new SidecarExpectations.RuntimeExpectation.Executed("runtime-ok",
                "pinned\n".getBytes(StandardCharsets.UTF_8),
                "".getBytes(StandardCharsets.UTF_8), 0, null);
        Execution exact = new Execution(0, "pinned\n", "", null, false);
        check(runtimeOkMismatches(pin, exact, null).isEmpty(),
            "the comparator accepts the pin-exact runtime-ok observation "
                + "(the control)");
        for (int exit : new int[] { 1, 2 }) {
            Execution wrongExit = new Execution(exit, "pinned\n", "", null, false);
            check(runtimeOkMismatches(pin, wrongExit, null).stream()
                    .anyMatch(m -> m.contains("exit code")),
                "the comparator rejects exit " + exit + ": "
                    + runtimeOkMismatches(pin, wrongExit, null));
        }
        Execution extraStdout = new Execution(0, "pinned\nnoise\n", "", null, false);
        check(runtimeOkMismatches(pin, extraStdout, null).stream()
                .anyMatch(m -> m.contains("stdout")),
            "the comparator rejects unexpected stdout: "
                + runtimeOkMismatches(pin, extraStdout, null));
        Execution extraStderr = new Execution(0, "pinned\n", "boom\n", null, false);
        check(runtimeOkMismatches(pin, extraStderr, null).stream()
                .anyMatch(m -> m.contains("stderr")),
            "the comparator rejects unexpected stderr: "
                + runtimeOkMismatches(pin, extraStderr, null));
        Capture raised = new Capture("E8001", "boom", null, null, null, null, null);
        Execution captured = new Execution(1, "pinned\n", "", raised, false);
        check(runtimeOkMismatches(pin, captured, raised).stream()
                .anyMatch(m -> m.contains("raises a failure")),
            "the comparator rejects a raised failure capture: "
                + runtimeOkMismatches(pin, captured, raised));
        Capture defect = new Capture("PROBE_DEFECT", "no export", null, null,
            null, null, null);
        Execution missed = new Execution(1, "pinned\n", "", defect, true);
        check(runtimeOkMismatches(pin, missed, defect).stream()
                .anyMatch(m -> m.contains("misses an export")),
            "the comparator rejects a missed export: "
                + runtimeOkMismatches(pin, missed, defect));
    }

    /**
     * The raw-profile negative control (the CD3 gate-input contract):
     * the drive's materialization validates the original fixture metadata
     * before any export parsing, materialization or compilation. A real
     * discovered fixture's raw source carrying a prepended legacy
     * {@code // @profile: legacy-safe-int} declaration, an unknown profile
     * value, and duplicate metadata are each rejected as a named
     * HARNESS_DEFECT naming the fixture and the profile for both
     * production targets; the rejected module's bytes are never written,
     * parsed or compiled (the validation precedes each write, the scratch
     * root is removed before the defect propagates), while the pristine
     * corpus source materializes as the acceptance control.
     */
    private static void testRawProfileNegativeControl() throws Exception {
        System.out.println("-- the raw-profile negative control: a nonproduction "
            + "or malformed corpus declaration is rejected before compilation --");
        String fixtureRel = FAMILY_DIR + "/jsonable-roundtrip";
        String fixturePath = fixtureRel + ".deal";
        String raw = Files.readString(CONFORMANCE.resolve(fixturePath),
            StandardCharsets.UTF_8);
        Materialized accepted = materializedModule(fixturePath, raw);
        check(accepted.source() != null
                && !accepted.source().contains("@profile:"),
            "the pristine corpus declaration materializes (the control)");
        Map<String, String> injections = new LinkedHashMap<>();
        injections.put("legacy profile", "// @profile: legacy-safe-number\n" + raw);
        injections.put("unknown profile", "// @profile: no-such-profile\n" + raw);
        injections.put("duplicate metadata",
            "// @profile: deal-v1.4-int32\n// @profile: deal-v1.4-int32\n" + raw);
        injections.put("removed v1.2 profile",
            "// @profile: deal-v1.2-int32\n" + raw);
        // The declaration after the 40-line metadata window and the
        // cross-boundary duplicate: the stripper removes profile lines
        // throughout the input, so the validation must observe every
        // declaration the stripper would drop — otherwise the input would
        // compile as a production cell with the declaration silently gone.
        injections.put("legacy profile on line 41",
            profilePadding(40) + "// @profile: legacy-safe-number\n" + raw);
        injections.put("legacy profile on line 40",
            profilePadding(39) + "// @profile: legacy-safe-number\n" + raw);
        injections.put("unknown profile on line 41",
            profilePadding(40) + "// @profile: no-such-profile\n" + raw);
        injections.put("empty profile on line 41",
            profilePadding(40) + "// @profile:\n" + raw);
        injections.put("cross-boundary duplicate",
            "// @profile: deal-v1.4-int32\n" + profilePadding(39)
                + "// @profile: legacy-safe-number\n" + raw);
        // The canonical metadata reader (windowProfileDeclarations)
        // segments source lines on \R, not only on CR/LF: a declaration
        // after VT, FF, NEL, LS or PS is recognized by that reader, so the
        // whole-source validation must recognize it too — otherwise the
        // declared nonproduction profile is visible to discovery while
        // the production materialization compiles the cell as if no
        // metadata existed.
        String[][] separators = {
            {"VT (U+000B)", "\u000B"}, {"FF (U+000C)", "\u000C"},
            {"NEL (U+0085)", "\u0085"}, {"LS (U+2028)", "\u2028"},
            {"PS (U+2029)", "\u2029"},
        };
        for (String[] separator : separators) {
            String name = separator[0];
            String terminator = separator[1];
            injections.put("legacy profile after " + name,
                "// padding" + terminator + "// @profile: legacy-safe-number\n"
                    + raw);
            injections.put("unknown profile after " + name,
                "// padding" + terminator + "// @profile: no-such-profile\n"
                    + raw);
            injections.put("duplicate metadata after " + name,
                "// @profile: deal-v1.4-int32\n// padding" + terminator
                    + "// @profile: deal-v1.4-int32\n" + raw);
            check(ConformanceHarnessMetadata.windowProfileDeclarations(
                    "// padding" + terminator
                        + "// @profile: legacy-safe-number\n" + raw, fixturePath)
                    .contains("legacy-safe-number"),
                "the canonical metadata reader recognizes the legacy "
                    + "declaration after " + name + " (the premise of the "
                    + "separator control)");
        }
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        Set<Path> before = new LinkedHashSet<>();
        try (var listing = Files.list(tmp)) {
            listing.filter(p -> p.getFileName().toString()
                    .startsWith("jsonable-prod-drive-"))
                .forEach(before::add);
        }
        for (Map.Entry<String, String> injection : injections.entrySet()) {
            for (Target target : Target.values()) {
                try {
                    Project rejected = materialize(fixtureRel, target,
                        Map.of(fixturePath, injection.getValue()));
                    deleteRecursively(rejected.root());
                    check(false, "the " + injection.getKey()
                        + " declaration is rejected before any compile ["
                        + target.laneName() + "]");
                } catch (ConformanceHarnessMetadata.HarnessInputDefectException
                        defect) {
                    check(defect.getMessage().contains(
                            ConformanceHarnessMetadata.HARNESS_DEFECT)
                            && defect.getMessage().contains(fixturePath),
                        "the " + injection.getKey() + " declaration is a named "
                            + ConformanceHarnessMetadata.HARNESS_DEFECT
                            + " naming the fixture [" + target.laneName() + "]: "
                            + defect.getMessage());
                }
            }
        }
        Set<Path> leftovers = new LinkedHashSet<>();
        try (var listing = Files.list(tmp)) {
            listing.filter(p -> p.getFileName().toString()
                    .startsWith("jsonable-prod-drive-"))
                .filter(p -> !before.contains(p))
                .forEach(leftovers::add);
        }
        check(leftovers.isEmpty(), "the rejected materializations remove every "
            + "scratch root before the defect propagates: " + leftovers);
    }

    /** {@code lines} comment lines that carry no classification prefix. */
    private static String profilePadding(int lines) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            out.append("// padding line ").append(i).append('\n');
        }
        return out.toString();
    }

    /**
     * The valid-depth artifact leg's negative control: the credit the
     * accepted-depth leg consumes (the same method the leg calls)
     * accepts only a completed zero-status, empty-transcript encode and
     * rejects an execution that exits nonzero without writing a transport
     * file — the exact case the leg's former {@code capture == null} check
     * credited as the accepted-depth success. The rejection control uses
     * the exit status observed in the depth-leg interception reproduction
     * (a wrapper that dies before the transport file is written).
     */
    private static void testDepthArtifactCreditNegativeControl() {
        System.out.println("-- the valid-depth artifact leg's negative control: a "
            + "nonzero execution without a transport file is rejected --");
        Execution completed = new Execution(0, "", "", null, false);
        check(validDepthArtifactMismatches(completed).isEmpty(),
            "the depth-leg credit accepts a completed deep encode "
                + "(the control)");
        Execution nonzeroNoTransport = new Execution(17, "", "", null, false);
        List<String> mismatches = validDepthArtifactMismatches(nonzeroNoTransport);
        check(mismatches.stream().anyMatch(m -> m.contains("exits nonzero")),
            "the depth-leg credit rejects a nonzero execution without a "
                + "transport file (exit 17): " + mismatches);
    }

    /**
     * The committed nested-class view (the three-consumer regression): a
     * field write on a class instance held as another class's declared
     * field is observable through the parent's helper call on the oracle,
     * the LuaJIT artifact, and the JVM artifact alike — the live executor
     * class view of the child reads the current committed field states, so
     * the parent view that embeds it never serializes the stale pre-commit
     * state. The alias function proves the same commit through two parents
     * that hold the same child identity. The intervening-commit function
     * (Box also declares {@code y: int}) proves the later child commit is
     * still observed after an unrelated parent-field commit: the parent's
     * committed state must hold the child's live view identity, never a
     * snapshot frozen when the parent wrote {@code y}.
     */
    private static String nestedClassFieldViewSyncSource() {
        System.out.println("-- the committed nested-class view: a field write "
            + "through the parent serializes on all three consumers --");
        String expected = dealStringLiteral("{\"child\":{\"x\":2.0},\"y\":0}");
        String source = "// @jsonable\nexport class Child {\n  x: number;\n}\n\n"
            + "// @jsonable\nexport class Box {\n  child: Child;\n"
            + "  y: number;\n}\n\n"
            + "export function test_nested_class_intervening_commit(): null {\n"
            + "  let c: Child = { x: 1 };\n"
            + "  let b: Box = { child: c, y: 0 };\n"
            + "  c.x = 2;\n"
            + "  b.y = 1;\n"
            + "  c.x = 3;\n"
            + "  let json: string = Box$toJson(b);\n"
            + "  let expected: string = "
            + dealStringLiteral("{\"child\":{\"x\":3.0},\"y\":1.0}") + ";\n"
            + "  if (json !== expected) {\n"
            + "    throw { code: \"MISMATCH\", message: json };\n"
            + "  }\n"
            + "  return null;\n"
            + "}\n\n"
            + "export function test_nested_class_mutation(): null {\n"
            + "  let b: Box = { child: { x: 1 }, y: 0 };\n"
            + "  b.child.x = 2;\n"
            + "  let json: string = Box$toJson(b);\n"
            + "  let expected: string = " + expected + ";\n"
            + "  if (json !== expected) {\n"
            + "    throw { code: \"MISMATCH\", message: json };\n"
            + "  }\n"
            + "  return null;\n"
            + "}\n\n"
            + "export function test_nested_class_alias_mutation(): null {\n"
            + "  let c: Child = { x: 1 };\n"
            + "  let p: Box = { child: c, y: 0 };\n"
            + "  let q: Box = { child: c, y: 0 };\n"
            + "  p.child.x = 2;\n"
            + "  let expected: string = " + expected + ";\n"
            + "  let jsonP: string = Box$toJson(p);\n"
            + "  if (jsonP !== expected) {\n"
            + "    throw { code: \"MISMATCH\", message: jsonP };\n"
            + "  }\n"
            + "  let jsonQ: string = Box$toJson(q);\n"
            + "  if (jsonQ !== expected) {\n"
            + "    throw { code: \"MISMATCH\", message: jsonQ };\n"
            + "  }\n"
            + "  return null;\n"
            + "}\n\n"
            + "export function main(): null {\n  return null;\n}\n";
        return source;
    }

    /**
     * The array-held committed class view (the three-consumer regression):
     * a field write on a separately constructed class instance held in a
     * declared {@code Child[]} is observable through the parent's helper
     * call on the oracle, the LuaJIT artifact, and the JVM artifact alike —
     * the array's live view reads the one instance's current committed
     * field states. The intervening-commit function (Box also declares
     * {@code y: int}) proves the later array-element commit is still
     * observed after an unrelated parent-field commit.
     */
    private static String arrayHeldClassViewSyncSource() {
        System.out.println("-- the committed array-held class view: a field write "
            + "through b.children[0].x serializes on all three consumers --");
        String source = "// @jsonable\nexport class Child {\n  x: number;\n}\n\n"
            + "// @jsonable\nexport class Box {\n  children: Child[];\n"
            + "  y: number;\n}\n\n"
            + "export function test_array_held_intervening_commit(): null {\n"
            + "  let c: Child = { x: 1 };\n"
            + "  let b: Box = { children: [c], y: 0 };\n"
            + "  b.children[0].x = 2;\n"
            + "  b.y = 1;\n"
            + "  b.children[0].x = 3;\n"
            + "  let json: string = Box$toJson(b);\n"
            + "  let expected: string = "
            + dealStringLiteral("{\"children\":[{\"x\":3.0}],\"y\":1.0}") + ";\n"
            + "  if (json !== expected) {\n"
            + "    throw { code: \"MISMATCH\", message: json };\n"
            + "  }\n"
            + "  return null;\n"
            + "}\n\n"
            + "export function test_array_held_class_mutation(): null {\n"
            + "  let c: Child = { x: 1 };\n"
            + "  let b: Box = { children: [c], y: 0 };\n"
            + "  b.children[0].x = 2;\n"
            + "  let json: string = Box$toJson(b);\n"
            + "  let expected: string = "
            + dealStringLiteral("{\"children\":[{\"x\":2.0}],\"y\":0}") + ";\n"
            + "  if (json !== expected) {\n"
            + "    throw { code: \"MISMATCH\", message: json };\n"
            + "  }\n"
            + "  return null;\n"
            + "}\n\n"
            + "export function main(): null {\n  return null;\n}\n";
        return source;
    }

    /**
     * The nullable-array element admission (the three-consumer regression):
     * a null element is admitted exactly where the element descriptor admits
     * it — {@code (int | null)[]} roundtrips the null element on the oracle,
     * the LuaJIT artifact, and the JVM artifact, while a non-nullable
     * {@code int[]} rejects the same document (language null) on all three.
     */
    private static String nullableArrayElementsSource() {
        System.out.println("-- the nullable-array element admission: the element "
            + "descriptor decides a null element on all three consumers --");
        String source = "// @jsonable\nexport class Box {\n"
            + "  xs: (number | null)[];\n}\n\n"
            + "// @jsonable\nexport class StrictBox {\n"
            + "  xs: number[];\n}\n\n"
            + "export function test_nullable_array_roundtrip(): null {\n"
            + "  let maybe: Box | null = Box$fromJson(\"{\\\"xs\\\":[1.0,null]}\");\n"
            + "  if (maybe !== null) {\n"
            + "    let b: Box = maybe;\n"
            + "    let json: string = Box$toJson(b);\n"
            + "    let expected: string = "
            + dealStringLiteral("{\"xs\":[1.0,null]}") + ";\n"
            + "    if (json !== expected) {\n"
            + "      throw { code: \"MISMATCH\", message: json };\n"
            + "    }\n"
            + "    return null;\n"
            + "  }\n"
            + "  throw { code: \"MISMATCH\", message: \"the valid nullable "
            + "array document was rejected\" };\n"
            + "}\n\n"
            + "export function test_strict_array_rejected(): null {\n"
            + "  let maybe: StrictBox | null = StrictBox$fromJson("
            + "\"{\\\"xs\\\":[1.0,null]}\");\n"
            + "  if (maybe !== null) {\n"
            + "    throw { code: \"MISMATCH\", message: \"the non-nullable "
            + "element was admitted\" };\n"
            + "  }\n"
            + "  return null;\n"
            + "}\n\n"
            + "export function main(): null {\n  return null;\n}\n";
        return source;
    }

    /**
     * The read-derived numeric carrier at declared positions (the
     * three-consumer regression): a numeric value read from a container slot
     * travels as the read-side variant carrier ({@code __jn}), and a declared
     * {@code number} field or array element admits it with its own variant's
     * spelling — never a rejection of the carrier representation.
     */
    private static String readDerivedNumericCarriersSource() {
        System.out.println("-- read-derived numeric carriers in declared fields and "
            + "array elements: the own-variant spelling on all three consumers --");
        String source = "// @jsonable\nexport class Child {\n"
            + "  n: number;\n"
            + "  xs: number[];\n}\n\n"
            + "// @jsonable\nexport class Box {\n"
            + "  child: Child;\n}\n\n"
            + "export function test_read_derived_numbers(): null {\n"
            + "  let t: Record<string, unknown> = { i: 1, n: 2.5 };\n"
            + "  let i: number = t.i as number;\n"
            + "  let n: number = t.n as number;\n"
            + "  let b: Box = { child: { n: i, xs: [i, n] } };\n"
            + "  let json: string = Box$toJson(b);\n"
            + "  let expected: string = "
            + dealStringLiteral("{\"child\":{\"n\":1.0,\"xs\":[1.0,2.5]}}")
            + ";\n"
            + "  if (json !== expected) {\n"
            + "    throw { code: \"MISMATCH\", message: json };\n"
            + "  }\n"
            + "  return null;\n"
            + "}\n\n"
            + "export function main(): null {\n  return null;\n}\n";
        return source;
    }

    /**
     * The declared-array numeric variant (the three-consumer regression): an
     * array admitted through a dynamic table read keeps each slot's own
     * recorded numeric variant — the array carrier's {@code __nK} mark — and
     * the declared {@code number[]} walk spells an int-variant slot with its
     * integer text and a number-variant slot with the closed decimal
     * spelling, exactly the oracle's and the shared JVM runtime's Long/Double
     * split, never the declared descriptor's text. The nullable element
     * position and the nested array position keep the same rule through
     * their recursion. The controls pin both variants in one comparison: the
     * all-number array keeps the decimal spelling ([1.0, 1.5]) and the mixed
     * array built through an index write spells each slot by its own variant
     * ([2.5, 2]); the int-variant slot is the reported LuaJIT defect.
     */
    private static void testDeclaredArrayNumericVariants() throws Exception {
        System.out.println("-- the declared-array numeric variant: the slot's "
            + "own recorded variant spells the number[] elements on all three "
            + "consumers --");
        // The reported defect: an int-variant array admitted through a
        // dynamic table read spells its int slots with the integer text at
        // the declared number[] walk (and through the nullable and nested
        // array positions), never the closed decimal spelling.
        String deficient = "// @jsonable\nexport class Box {\n"
            + "  xs: number[];\n"
            + "  ns: (number | null)[];\n"
            + "  grid: number[][];\n}\n\n"
            + "export function test_int_variant_elements(): null {\n"
            + "  let t: Record<string, unknown> = { xs: [1.0, 2] };\n"
            + "  let xs: number[] = t.xs as number[];\n"
            + "  let b: Box = { xs: xs, ns: [], grid: [] };\n"
            + "  let json: string = Box$toJson(b);\n"
            + "  let expected: string = "
            + dealStringLiteral("{\"xs\":[1.0,2.0],\"ns\":[],\"grid\":[]}") + ";\n"
            + "  if (json !== expected) {\n"
            + "    throw { code: \"MISMATCH\", message: json };\n"
            + "  }\n"
            + "  return null;\n"
            + "}\n\n"
            + "export function test_nullable_position_elements(): null {\n"
            + "  let seed: (number | null)[] = [1.0, 2];\n"
            + "  let t: Record<string, unknown> = { ns: seed };\n"
            + "  let ns: (number | null)[] = t.ns as (number | null)[];\n"
            + "  ns[1] = null;\n"
            + "  let b: Box = { xs: [], ns: ns, grid: [] };\n"
            + "  let json: string = Box$toJson(b);\n"
            + "  let expected: string = "
            + dealStringLiteral("{\"xs\":[],\"ns\":[1.0,null],\"grid\":[]}") + ";\n"
            + "  if (json !== expected) {\n"
            + "    throw { code: \"MISMATCH\", message: json };\n"
            + "  }\n"
            + "  return null;\n"
            + "}\n\n"
            + "export function test_nested_array_position_elements(): null {\n"
            + "  let t: Record<string, unknown> = { grid: [[1], [2]] };\n"
            + "  let grid: number[][] = t.grid as number[][];\n"
            + "  grid[1] = [2.5];\n"
            + "  let b: Box = { xs: [], ns: [], grid: grid };\n"
            + "  let json: string = Box$toJson(b);\n"
            + "  let expected: string = "
            + dealStringLiteral("{\"xs\":[],\"ns\":[],\"grid\":[[1.0],[2.5]]}")
            + ";\n"
            + "  if (json !== expected) {\n"
            + "    throw { code: \"MISMATCH\", message: json };\n"
            + "  }\n"
            + "  return null;\n"
            + "}\n\n"
            + "export function main(): null {\n  return null;\n}\n";

        // The controls: a marked number slot keeps the closed decimal
        // spelling (the all-number literal), and one mixed array built
        // through an index write that marks only the number slot spells the
        // marked slot decimally and the int slot as its integer text.
        String controls = "// @jsonable\nexport class Box {\n"
            + "  xs: number[];\n}\n\n"
            + "export function test_number_variant_elements(): null {\n"
            + "  let t: Record<string, unknown> = { xs: [1.0, 1.5] };\n"
            + "  let xs: number[] = t.xs as number[];\n"
            + "  let b: Box = { xs: xs };\n"
            + "  let json: string = Box$toJson(b);\n"
            + "  let expected: string = "
            + dealStringLiteral("{\"xs\":[1.0,1.5]}") + ";\n"
            + "  if (json !== expected) {\n"
            + "    throw { code: \"MISMATCH\", message: json };\n"
            + "  }\n"
            + "  return null;\n"
            + "}\n\n"
            + "export function test_mixed_variant_elements(): null {\n"
            + "  let t: Record<string, unknown> = { xs: [1.0, 2] };\n"
            + "  let xs: number[] = t.xs as number[];\n"
            + "  xs[0] = 2.5;\n"
            + "  let b: Box = { xs: xs };\n"
            + "  let json: string = Box$toJson(b);\n"
            + "  let expected: string = "
            + dealStringLiteral("{\"xs\":[2.5,2.0]}") + ";\n"
            + "  if (json !== expected) {\n"
            + "    throw { code: \"MISMATCH\", message: json };\n"
            + "  }\n"
            + "  return null;\n"
            + "}\n\n"
            + "export function main(): null {\n  return null;\n}\n";
        driveExactHelperProgram("declared-array-numeric-variants-and-controls",
            combineExactHelperSources(deficient, controls, readDerivedNumericCarriersSource()));
    }

    /**
     * The omitted nested-class numeric default (the three-consumer
     * regression): the nested class's decode runs the declared CLASS_DEFAULT
     * children, and a default that produces the read-side numeric variant
     * carrier must conform at its declared {@code number} position (and at
     * the recursive {@code number | null} and {@code number[]} positions)
     * exactly as the oracle's and the JVM's own variants do — the document
     * is admitted and the instance serializes with the carrier's own
     * variant's spelling. The explicit-value control supplies the same
     * values in the document, so the two legs prove the default path and the
     * provided path independently; a LuaJIT rejection of the carrier is the
     * reported defect.
     */
    private static void testNestedClassDefaultNumericCarrier() throws Exception {
        System.out.println("-- the omitted nested-class numeric default: the "
            + "read-derived carrier conforms at its declared positions on all "
            + "three consumers, with the explicit-value control --");
        String expected = "{\"child\":{\"n\":2.5,\"o\":2.5,\"xs\":[2.5]}}";
        String source = "// @jsonable\nexport class Child {\n"
            + "  n: number;\n"
            + "  o: number | null;\n"
            + "  xs: number[];\n}\n\n"
            + "// @jsonable\nexport class Box {\n"
            + "  child: Child;\n}\n\n"
            + "function getNum(): number {\n"
            + "  let t: Record<string, unknown> = { n: 2.5 };\n"
            + "  return t.n as number;\n}\n\n"
            + "function getNums(): number[] {\n"
            + "  let t: Record<string, unknown> = { n: 2.5 };\n"
            + "  let n: number = t.n as number;\n"
            + "  return [n];\n}\n\n"
            + "export function test_provided_numeric_carriers(): null {\n"
            + "  let box: Box | null = {child: {n: getNum(), o: getNum(), xs: getNums()}};\n"
            + "  if (box !== null) {\n"
            + "    let json: string = Box$toJson(box);\n"
            + "    let expected: string = " + dealStringLiteral(expected) + ";\n"
            + "    if (json !== expected) {\n"
            + "      throw { code: \"MISMATCH\", message: json };\n"
            + "    }\n"
            + "    return null;\n"
            + "  }\n"
            + "  throw { code: \"MISMATCH\", message: \"the provided numeric carrier was rejected\" };\n}\n\n"
            + "export function test_missing_nested_field(): null {\n"
            + "  let missing: Box | null = Box$fromJson(\"{\\\"child\\\":{}}\");\n"
            + "  if (missing !== null) {throw {code:\"MISMATCH\",message:\"missing nested fields accepted\"};}\n"
            + "  return null;\n}\n\n"
            + "export function test_explicit_value_control(): null {\n"
            + "  let box: Box | null = Box$fromJson("
            + dealStringLiteral(expected) + ");\n"
            + "  if (box !== null) {\n"
            + "    let json: string = Box$toJson(box);\n"
            + "    let expected: string = " + dealStringLiteral(expected) + ";\n"
            + "    if (json !== expected) {\n"
            + "      throw { code: \"MISMATCH\", message: json };\n"
            + "    }\n"
            + "    return null;\n"
            + "  }\n"
            + "  throw { code: \"MISMATCH\", message: \"the explicit valid "
            + "document was rejected\" };\n}\n\n"
            + "export function main(): null {\n  return null;\n}\n";
        driveExactHelperProgram("nested-default-numeric-carrier-and-nullable-arrays",
            combineExactHelperSources(source, nullableArrayElementsSource()));
    }

    /**
     * The oracle main coverage (the family drive's two legs): the main leg
     * lowers the fixture itself as the entry module, so its own entry
     * delegation invokes the declared {@code main} exactly once — a
     * main-only fixture's deliberate failure is captured, never skipped;
     * the export leg's driver invokes only the non-main exports (a
     * cross-module {@code main} call is no valid shape: the declaring
     * module records no {@code EXTERNAL_ENTRY} for it).
     */
    private static void testOracleDriverMainCoverage() throws Exception {
        System.out.println("-- the oracle's main coverage: a main-only fixture's "
            + "deliberate failure is captured by the main leg exactly once --");
        String source = "export function main(): null {\n"
            + "  throw { code: \"MAIN_REACHED\", message: \"ran\" };\n"
            + "}\n";
        List<Export> exports = exportsOf(source);
        checkEq(List.of("main"),
            exports.stream().map(Export::name).toList(),
            "the main-only control fixture carries exactly its main export");
        Project project = syntheticProject("app", source, Target.JVM);
        try {
            // The main leg: the fixture is the entry of its own closure and
            // its entry delegation carries the single ENTRY_INVOKE op (main's
            // one call site), so main runs exactly once.
            Lowered mainLeg = lower(project, "main-only control");
            if (mainLeg == null) {
                return;
            }
            int entryInvokes = 0;
            for (SemanticOp op : mainLeg.project().modules()
                    .get(mainLeg.entryModule()).ops()) {
                if (op.kind() == SemanticOpKind.ENTRY_INVOKE) {
                    entryInvokes++;
                }
            }
            checkEq(1, entryInvokes, "the main leg's entry delegation is the "
                + "fixture main's single invocation (one ENTRY_INVOKE op)");
            SemanticRuntimeModel.ErrorSnapshot mainFailure = oracleMainFailure(mainLeg);
            check(mainFailure != null, "the main-only fixture's deliberate failure "
                + "is captured by the oracle's main leg");
            if (mainFailure != null) {
                checkEq("MAIN_REACHED", mainFailure.code(), "the captured main "
                    + "failure's code");
                checkEq("ran", mainFailure.message(), "the captured main failure's "
                    + "message");
            }
            // The export leg: the driver never invokes the fixture's main.
            String driver = oracleDriver(project, exports);
            int driverMainCalls = driver.split(java.util.regex.Pattern
                .quote("fx.main()"), -1).length - 1;
            checkEq(0, driverMainCalls, "the export leg's driver invokes no "
                + "fixture main (" + driver + ")");
        } finally {
            deleteRecursively(project.root());
        }
    }

    /**
     * The pinned-failure leg's negative controls: the sidecar comparison
     * accepts the sidecar's own pin (the control) and rejects unexpected
     * process stdout, an incorrect process exit status, incorrect process
     * stderr, and a missing capture — the process-level pins the
     * runtime-error branch previously never compared.
     */
    private static void testRuntimeErrorSidecarNegativeControls(
            Optional<SidecarExpectations.RuntimeExpectation.Executed> pinned) {
        System.out.println("-- the pinned-failure leg's negative controls: the "
            + "process stdout, exit code, and stderr are part of the byte-exact "
            + "pin --");
        check(pinned.isPresent(), CYCLIC_FIXTURE + ": the pinned runtime-error "
            + "sidecar parses");
        if (pinned.isEmpty()) {
            return;
        }
        SidecarExpectations.RuntimeExpectation.Executed expectation = pinned.get();
        check(expectation.isRuntimeError(), CYCLIC_FIXTURE + ": the sidecar pins a "
            + "runtime error");
        SidecarExpectations.ErrorExpectation row = expectation.error();
        String stderr = new String(expectation.stderr(), StandardCharsets.UTF_8);
        Capture matching = new Capture(row.code(), row.message(), row.sourceFile(),
            row.line(), row.column(), row.expected().orElse(null),
            row.actual().orElse(null));
        String cleanStdout = new String(expectation.stdout(), StandardCharsets.UTF_8);
        List<String> accepted = runtimeErrorSidecarMismatches(expectation, row,
            expectation.exitCode(), "", stderr, matching);
        check(accepted.isEmpty(), "the comparator accepts the sidecar's own pin "
            + "(the control): " + accepted);
        List<String> extraStdout = runtimeErrorSidecarMismatches(expectation, row,
            expectation.exitCode(), "unexpected output\n", stderr, matching);
        check(extraStdout.stream().anyMatch(m -> m.contains("framed stdout")),
            "the comparator rejects unexpected process stdout: " + extraStdout);
        List<String> wrongExit = runtimeErrorSidecarMismatches(expectation, row,
            expectation.exitCode() + 1, "", stderr, matching);
        check(wrongExit.stream().anyMatch(m -> m.contains("exit code")),
            "the comparator rejects an incorrect process exit status: " + wrongExit);
        List<String> wrongStderr = runtimeErrorSidecarMismatches(expectation, row,
            expectation.exitCode(), "", "boom", matching);
        check(wrongStderr.stream().anyMatch(m -> m.contains("stderr")),
            "the comparator rejects incorrect process stderr: " + wrongStderr);
        List<String> noCapture = runtimeErrorSidecarMismatches(expectation, row,
            expectation.exitCode(), "", stderr, null);
        check(!noCapture.isEmpty(),
            "the comparator rejects a missing capture: " + noCapture);
        check(cleanStdout.length() > 0,
            "the pinned cyclic fixture carries a non-empty stdout transcript");
    }

    // =========================================================================
    // 8. Determinism and the verbatim export key
    // =========================================================================

    /**
     * Repeated lowering and emission of the same fixture are byte-identical
     * (two compiles of one lane-equivalent project emit the identical
     * artifact for each target), and the emitted helper publication carries
     * the DEAL helper name verbatim as its key on both targets (the
     * {@code $}-only-in-quoted-keys invariant: the Lua surface keys the
     * helper by a quoted string, the JVM surface by the same string).
     */
    private static void testDeterminismAndExportKey(
            Map<String, CorpusObservation> observations) throws Exception {
        System.out.println("-- determinism and the verbatim export key --");
        List<String> fixtures = List.of(
            FAMILY_DIR + "/jsonable-helper-exports",
            FAMILY_DIR + "/jsonable-cross-module-nested-class-array");
        List<String> keys = List.of("User$fromJson", "Parent$fromJson");
        for (int index = 0; index < fixtures.size(); index++) {
            String fixtureRel = fixtures.get(index);
            String helper = keys.get(index);
            for (Target target : Target.values()) {
                CorpusObservation observation = observations.get(fixtureRel);
                Project project = target == Target.JVM ? observation.project()
                    : withOutput(observation.project(), "out");
                {
                    Compilation firstCompilation = target == Target.JVM
                        ? observation.compilation() : observation.luaCompilation();
                    List<String> firstDiagnostics = firstCompilation.diagnostics();
                    List<String> secondDiagnostics = new ArrayList<>();
                    boolean firstCompiled = firstCompilation.compiled();
                    String first = firstCompiled
                        ? Files.readString(artifactOf(project, target)) : null;
                    Project repeat = new Project(project.root(), project.srcRoot(),
                        project.entryFile(), project.modulePath(), project.modules(),
                        project.root().resolve("out-repeat"));
                    boolean secondCompiled = compile(repeat, fixtureRel, target,
                        secondDiagnostics);
                    String second = secondCompiled
                        ? Files.readString(artifactOf(repeat, target)) : null;
                    check(firstCompiled && secondCompiled, fixtureRel + " ["
                        + target.laneName() + "]: both drives compile: "
                        + firstDiagnostics + " " + secondDiagnostics);
                    if (firstCompiled && secondCompiled) {
                        checkEq(first, second, fixtureRel + " ["
                            + target.laneName() + "]: repeated lowering and "
                            + "emission are byte-identical");
                        checkEq(firstDiagnostics, secondDiagnostics, fixtureRel
                            + " [" + target.laneName() + "]: repeated lowering "
                            + "carries no diagnostic");
                    }
                    if (first == null) {
                        continue;
                    }
                    // The publication's key is the DEAL helper name verbatim:
                    // the emitted Lua surface keys it as a quoted string, the
                    // emitted JVM surface writes the same string.
                    String publication = target == Target.LUAJIT
                        ? "[\"" + helper + "\"] = {[__dealCarrierKeys.kind] = \"function\""
                        : ".write(\"" + helper + "\", ";
                    check(first.contains(publication), fixtureRel + " ["
                        + target.laneName() + "]: the emitted helper publication "
                        + "keys the DEAL name verbatim (" + publication + ")");
                }
            }
        }
    }

    private static void deleteRecursively(Path dir) throws java.io.IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> discovered = List.copyOf(discoveredFamilyFixtures());
        Map<String, CorpusObservation> observations = new LinkedHashMap<>();
        Optional<SidecarExpectations.RuntimeExpectation.Executed> cyclicPin;
        try {
            testCorpusInventory(discovered);
            testProductionDrive(discovered, observations);
            testOracleAgreement(discovered, observations);
            testHelperSurface(observations.get(CROSS_MODULE_FIXTURE).lowered(),
                observations.get(FAMILY_DIR + "/jsonable-roundtrip").lowered());
            testNegatives();
            testMissingHelperFactsFailClosed(observations.get(CROSS_MODULE_FIXTURE));
            cyclicPin = observations.get(CYCLIC_FIXTURE).pinned();
            testDeterminismAndExportKey(observations);
        } finally {
            for (CorpusObservation observation : observations.values()) {
                deleteRecursively(observation.project().root());
            }
        }
        testDynamicHelperOrigin();
        testHelperFunctionBindingInvocation();
        testTableClassInstanceEncoding();
        driveExactHelperProgram("cross-owner-json-presence", """
            import * as Owner from "./owner";
            function nameDefault(): string { return "anon"; }
            function ageDefault(): number { return 0; }
            function homeDefault(): Owner.Address { return {city: "seed", zip: 10115}; }
            // @jsonable
            export class Person {
              name: string;
              age: number;
              home: Owner.Address;
            }
            export function test_default_order(): null {
              let supplied: Person = {name: nameDefault(), age: 42, home: homeDefault()};
              let p: Person | null = Person$fromJson(Person$toJson(supplied));
              if (p === null) { throw {code: "MISMATCH", message: "partial rejected"}; }
              else {
                if (Person$toJson(p) !== "{\\"name\\":\\"anon\\",\\"age\\":42.0,\\"home\\":{\\"city\\":\\"seed\\",\\"zip\\":10115.0}}") {
                  throw {code: "MISMATCH", message: "partial/default order"};
                }
              }
              let nested: Person | null = Person$fromJson("{\\"name\\":\\"anon\\",\\"age\\":0,\\"home\\":{}}");
              if (nested !== null) { throw {code: "MISMATCH", message: "nested omission accepted"}; }
              let missing: Person | null = Person$fromJson("{\\"age\\":42}");
              if (missing !== null) { throw {code: "MISMATCH", message: "outer omission accepted"}; }
              let bad: Person | null = Person$fromJson("{\\"name\\":\\"anon\\",\\"age\\":\\"bad\\",\\"home\\":{\\"city\\":\\"seed\\",\\"zip\\":10115}}");
              if (bad !== null) {
                throw {code: "MISMATCH", message: "failure ran defaults"};
              }
              return null;
            }
            export function main(): null { return null; }
            """, Map.of("owner", """
                // @jsonable
                export class Address { city: string; zip: number; }
                """));
        testNestedTableNumericVariants();
        testNestedClassDepthBound();
        testDepthArtifactCreditNegativeControl();
        driveExactHelperProgram("committed-array-and-class-views",
            combineExactHelperSources(arrayExecutorViewSyncSource(),
                nestedClassFieldViewSyncSource(), arrayHeldClassViewSyncSource()));
        testArrayExecutorViewDeleteParity();
        testDeclaredArrayNumericVariants();
        testNestedClassDefaultNumericCarrier();
        testOracleDriverMainCoverage();
        testRuntimeErrorSidecarNegativeControls(cyclicPin);
        testExactHelperArtifactNegativeControls();
        testRuntimeOkNegativeControls();
        testRawProfileNegativeControl();
        System.out.println("");
        System.out.println("Jsonable helper production drive: " + passed
            + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
