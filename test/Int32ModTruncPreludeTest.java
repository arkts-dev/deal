package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmBackend;
import deal.codegen.jvm.JvmRuntime;
import deal.diagnostics.CompilerDiagnostic;
import deal.module.CompilationOrchestrator;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticOracle;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.BinarySelector;
import deal.semantic.ir.BoundaryFailure;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.FailureArmId;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.StructuredBodyTable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class Int32ModTruncPreludeTest {

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
        check(Objects.equals(expected, actual), message + " (expected " + expected
            + ", got " + actual + ")");
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    private static final Path CORPUS = Path.of("test", "conformance",
        "backend-runtime");

    /** The named fixture of this issue and its sidecar. */
    private static final String FIXTURE = "arithmetic/int32-div-rem-boundaries";
    private static final String FIXTURE_SIDECAR = FIXTURE + ".expect.json";

    private static final List<String> GUARD_RULES = List.of("CONSTRUCT_UNLOWERED",
        "RETAINED_ABI_DEFERRED", "SHARED_EMITTER_COVERAGE");

    /** The prelude probe's entry module path. */
    private static final String PROBE_MODULE = "main";

    /**
     * One sign/boundary case: the exported probe, its two int operands,
     * and the pinned truncated remainder (the oracle's and the JVM
     * runtime's row).
     */
    private record Case(String export, long dividend, long divisor, long pinned) {
    }

    private static final List<Case> CASES = List.of(
        new Case("mod_neg_pos", -5, 2, -1),
        new Case("mod_pos_neg", 5, -2, 1),
        new Case("mod_neg_neg", -5, -2, -1),
        new Case("mod_pos_pos", 5, 2, 1),
        new Case("mod_min_neg_one", -2147483648L, -1, 0));

    /**
     * The five-case probe module: one exported zero-arity function per
     * case (each computing {@code dividend % divisor} over bindings, so
     * the case reaches the emitted {@code __arith} arm at run time), plus
     * an entry {@code main} that asserts every pinned remainder — a
     * mismatch fails the chunk-load drive of both artifacts.
     */
    private static final String MOD_PROBE_SOURCE = """
        export function main(): null {
          if (mod_neg_pos() !== -1) {
            throw { code: "TEST_FAIL", message: "-5 % 2 is not -1" };
          }
          if (mod_pos_neg() !== 1) {
            throw { code: "TEST_FAIL", message: "5 % -2 is not 1" };
          }
          if (mod_neg_neg() !== -1) {
            throw { code: "TEST_FAIL", message: "-5 % -2 is not -1" };
          }
          if (mod_pos_pos() !== 1) {
            throw { code: "TEST_FAIL", message: "5 % 2 is not 1" };
          }
          if (mod_min_neg_one() !== 0) {
            throw { code: "TEST_FAIL", message: "INT_MIN % -1 is not 0" };
          }
          return null;
        }

        export function mod_neg_pos(): int {
          let dividend: int = -5;
          let divisor: int = 2;
          return dividend % divisor;
        }

        export function mod_pos_neg(): int {
          let dividend: int = 5;
          let divisor: int = -2;
          return dividend % divisor;
        }

        export function mod_neg_neg(): int {
          let dividend: int = -5;
          let divisor: int = -2;
          return dividend % divisor;
        }

        export function mod_pos_pos(): int {
          let dividend: int = 5;
          let divisor: int = 2;
          return dividend % divisor;
        }

        export function mod_min_neg_one(): int {
          let dividend: int = -2147483648;
          let divisor: int = -1;
          return dividend % divisor;
        }
        """;

    /** The one `%` expression of one probe case function. */
    private static final String PROBE_EXPRESSION = "dividend % divisor";

    /**
     * The zero-divisor probe module: its entry {@code main} evaluates
     * {@code dividend % divisor} with a run-time zero divisor (a call
     * result, never a literal the frontend could flag), so the failure is
     * the emitted arm's own {@code INT32_DIVISION_BY_ZERO} projection.
     */
    private static final String ZERO_PROBE_SOURCE = """
        export function main(): null {
          let dividend: int = 7;
          let divisor: int = zeroDivisor();
          let remainder: int = dividend % divisor;
          return null;
        }

        function zeroDivisor(): int {
          return 0;
        }
        """;

    // =========================================================================
    // The production invocation and the compile/probe helpers
    // =========================================================================

    /** The release-owned production invocation (C8's record). */
    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    /** The fixture source with its classification headers stripped, verbatim. */
    private static String fixtureSource(String relativePath) throws Exception {
        return ConformanceHarnessMetadata.stripClassificationHeaders(
            Files.readString(CORPUS.resolve(relativePath + ".deal"),
                StandardCharsets.UTF_8));
    }

    /** One materialized single-module project. */
    private record Project(Path root, Path src, Path entry, Path out,
                           String modulePath) {
    }

    private static Project writeProject(String tag, String modulePath, String source)
            throws Exception {
        Path root = Files.createTempDirectory("int32-mod-" + tag + "-");
        writeFile(root, "src/" + modulePath + ".deal", source);
        Path src = root.resolve("src");
        return new Project(root, src, src.resolve(modulePath + ".deal"),
            root.resolve("out"), modulePath);
    }

    /**
     * One production compile of one project through the release-owned
     * invocation (the phase-4 production arm, no route plan).
     */
    private static CompilationOrchestrator productionCompile(Project project,
            Backend backend) throws Exception {
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            project.entry().toAbsolutePath(), project.out(), false, false, false,
            false, backend, null, List.of(project.src().toAbsolutePath()), null, null,
            productionInvocation());
        orchestrator.compile();
        return orchestrator;
    }

    /** The zero-diagnostic, one-project-artifact, no-retained-emission assertions. */
    private static void assertProductionEmission(CompilationOrchestrator orchestrator,
            String what) {
        check(orchestrator.diagnostics().isEmpty(), what + ": the compile reports "
            + "zero diagnostics: " + orchestrator.diagnostics());
        for (CompilerDiagnostic diagnostic : orchestrator.diagnostics()) {
            for (String rule : GUARD_RULES) {
                check(!diagnostic.message().contains(rule), what + ": zero E6005 "
                    + rule + " over the compile (" + diagnostic.message() + ")");
            }
        }
        checkEq(1, orchestrator.semanticEmissionCount(), what + ": the production "
            + "arm emits exactly one project artifact");
        checkEq(0, orchestrator.retainedEmissionCount(), what + ": the production "
            + "arm performs no retained emission");
    }

    /** One lowered project: the oracle's inputs. */
    private record Lowered(ExecutableLoweredProject project,
                           Map<ModuleId, StructuredBodyTable> tables,
                           Map<ModuleId, ClassFactoryRegistry> registries,
                           LoweredModuleUnit entryUnit) {
    }

    /** The one project lowering over a compiled project's declared inputs. */
    private static Lowered lower(CompilationOrchestrator orchestrator, String what) {
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        if (built == null || built.input() == null || built.index() == null
                || manifests == null || manifests.manifests() == null
                || orchestrator.hostDeclarationSurface() == null) {
            fail(what + ": the production compile publishes the lowering inputs");
            return null;
        }
        SemanticLowerer.ProjectLoweringResult result = SemanticLowerer.lowerProject(
            orchestrator.invocation(), built.input(), built.index(),
            manifests.manifests(), orchestrator.hostDeclarationSurface(), Map.of(),
            Map.of(), BuiltinErrorDeclaration.synthesized(
                built.input().modules().get(0).ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT),
            Set.of());
        check(result.project() != null && !result.hasErrors(), what + ": the one "
            + "project lowering reports zero diagnostics: " + result.diagnostics());
        if (result.project() == null) {
            return null;
        }
        return new Lowered(result.project(), result.tables(), result.registries(),
            result.project().modules().get(result.project().entryModule()));
    }

    private static SemanticRuntimeModel.ConsumerRun oracleRun(Lowered lowered) {
        return SemanticOracle.executeProjectInits(lowered.project(), lowered.tables(),
            lowered.registries(), null);
    }

    private record ProcessOutcome(int exitCode, String stdout, String stderr) {

        String output() {
            return stdout + stderr;
        }
    }

    private static ProcessOutcome runProcess(List<String> command, Path workDir)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workDir.toFile());
        Process process = builder.start();
        String stdout = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(),
            StandardCharsets.UTF_8);
        return new ProcessOutcome(process.waitFor(), stdout, stderr);
    }

    private static ProcessOutcome runLuaProbe(Project project, String driverName,
            String driver, boolean deferMain) throws Exception {
        writeFile(project.out(), driverName, driver);
        ProcessBuilder builder = new ProcessBuilder("luajit", driverName);
        builder.directory(project.out().toFile());
        if (deferMain) {
            builder.environment().put("DEAL_DEFER_MAIN", "1");
        }
        Process process = builder.start();
        String stdout = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(),
            StandardCharsets.UTF_8);
        return new ProcessOutcome(process.waitFor(), stdout, stderr);
    }

    /** Compiles and runs one Java probe against one emitted production class. */
    private static ProcessOutcome runJvmProbe(Path out, Path classes, String probeName,
            String probe, String... sources) throws Exception {
        Files.createDirectories(classes);
        writeFile(out, probeName + ".java", probe);
        List<String> command = new ArrayList<>(List.of("javac", "--release", "25",
            "-proc:none", "-cp", absoluteClasspath(), "-d", classes.toString()));
        for (String source : sources) {
            command.add(source);
        }
        command.add(out.resolve(probeName + ".java").toAbsolutePath().toString());
        ProcessOutcome javac = runProcess(command, out);
        checkEq(0, javac.exitCode(), "the emitted production class and "
            + probeName + " compile with javac --release 25 -proc:none: "
            + javac.output());
        if (javac.exitCode() != 0) {
            return javac;
        }
        return runProcess(List.of("java", "-cp",
            absoluteClasspath() + File.pathSeparator + classes, probeName), out);
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

    private static void writeFile(Path root, String relative, String content)
            throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path dir) {
        try {
            if (dir == null || !Files.exists(dir)) {
                return;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (Exception ignored) {
            // Best-effort temp cleanup only; never part of a test result.
        }
    }

    /** One `name=value` line per driven export. */
    private static Map<String, Long> parseValues(String output, String what) {
        Map<String, Long> values = new LinkedHashMap<>();
        for (String line : output.split("\n", -1)) {
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            try {
                values.put(line.substring(0, equals),
                    Long.valueOf(line.substring(equals + 1).trim()));
            } catch (NumberFormatException ignored) {
                // not a probe row
            }
        }
        check(!values.isEmpty(), what + ": the probe prints its rows: " + output);
        return values;
    }

    /** One `ERR|code|message|origin` row. */
    private static String[] parseErrorRow(String output, String what) {
        for (String line : output.split("\n", -1)) {
            if (line.startsWith("ERR|")) {
                String[] parts = line.split("\\|", -1);
                checkEq(4, parts.length, what + ": the probe row carries code, "
                    + "message, and origin: " + line);
                return parts;
            }
        }
        return null;
    }

    // =========================================================================
    // 1. The emitted prelude's arm
    // =========================================================================

    /** The emitted `INT32_MOD_TRUNC` arm of one production chunk. */
    private static String modArm(String chunk) {
        int start = chunk.indexOf("if selector == \"INT32_MOD_TRUNC\" then");
        check(start >= 0, "the emitted prelude carries the INT32_MOD_TRUNC arm");
        int end = chunk.indexOf("if selector == \"INT32_POW\" then", start);
        check(end > start, "the emitted prelude's INT32_MOD_TRUNC arm is closed");
        return start < 0 || end <= start ? null : chunk.substring(start, end);
    }

    /** The emitted `INT32_DIV_TRUNC` arm of one production chunk. */
    private static String divArm(String chunk) {
        int start = chunk.indexOf("if selector == \"INT32_DIV_TRUNC\" then");
        check(start >= 0, "the emitted prelude carries the INT32_DIV_TRUNC arm");
        int end = chunk.indexOf("if selector == \"INT32_MOD_TRUNC\" then", start);
        check(end > start, "the emitted prelude's INT32_DIV_TRUNC arm is closed");
        return start < 0 || end <= start ? null : chunk.substring(start, end);
    }

    private static void testEmittedArm(String chunk) {
        System.out.println("-- the emitted prelude's INT32_MOD_TRUNC arm --");
        String mod = modArm(chunk);
        String div = divArm(chunk);
        if (mod == null || div == null) {
            return;
        }
        check(mod.contains("local q = l / r"), "the emitted arm computes the "
            + "quotient");
        check(mod.contains("local t = math.floor(math.abs(q))"), "the emitted arm "
            + "truncates the quotient toward zero");
        check(mod.contains("if q < 0 then t = -t end"), "the emitted arm carries "
            + "the sign of the truncation");
        check(mod.contains("return rng(l - t * r)"), "the emitted arm computes the "
            + "remainder from the truncated quotient");
        check(!mod.contains("l % r"), "the emitted arm never uses Lua's floor %: "
            + mod);
        check(mod.contains("if r == 0 then"), "the emitted arm keeps the "
            + "zero-divisor check");
        check(mod.contains("__arm(\"INT32_DIVISION_BY_ZERO\", nil, origin, nil, nil)"),
            "the emitted arm keeps the landed INT32_DIVISION_BY_ZERO projection");
        check(mod.contains("error(e, 0)"), "the emitted arm raises the projection");
        for (String line : List.of("local q = l / r",
                "local t = math.floor(math.abs(q))", "if q < 0 then t = -t end")) {
            check(div.contains(line), "the landed INT32_DIV_TRUNC arm carries the "
                + "same truncation line '" + line + "'");
        }
    }

    // =========================================================================
    // 2. The five sign/boundary cases through the three consumers
    // =========================================================================

    /** The 1-based source line of one probe case function's `%` expression. */
    private static int caseLine(String source, String export) {
        int function = source.indexOf("function " + export + "(");
        check(function >= 0, "the probe module declares " + export);
        int expression = source.indexOf(PROBE_EXPRESSION, function);
        check(expression > function, "the probe module's " + export + " computes "
            + PROBE_EXPRESSION);
        return 1 + (int) source.substring(0, expression).chars()
            .filter(c -> c == '\n').count();
    }

    /** The 1-based {line, column} of the probe module's `%` expression start. */
    private static int[] expressionPosition(String source) {
        int start = source.indexOf(PROBE_EXPRESSION);
        check(start >= 0, "the probe module computes " + PROBE_EXPRESSION);
        int line = 1;
        int lastNewline = -1;
        for (int i = 0; i < start; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lastNewline = i;
            }
        }
        return new int[] {line, start - lastNewline};
    }

    private static String probeNames() {
        StringBuilder names = new StringBuilder("{");
        for (Case probeCase : CASES) {
            names.append('"').append(probeCase.export()).append("\", ");
        }
        return names.append('}').toString();
    }

    private static String luaCaseDriver(String artifact) {
        return "local surfaces = dofile(\"" + artifact + "\")\n"
            + "assert(type(surfaces) == \"table\",\n"
            + "  \"the production chunk returns the entry surface\")\n"
            + "local names = " + probeNames() + "\n"
            + "for i = 1, #names do\n"
            + "  local entry = surfaces[names[i]]\n"
            + "  assert(type(entry) == \"table\" and type(entry.f) == \"function\",\n"
            + "    \"the entry surface publishes \" .. names[i])\n"
            + "  print(names[i] .. \"=\" .. tostring(entry.f()))\n"
            + "end\n";
    }

    private static String jvmCaseDriver(String className) {
        return """
            public final class Int32ModCaseProbe {
              public static void main(String[] args) {
                %s.main(new String[0]);
                System.out.println("mod_neg_pos=" + invoke("mod_neg_pos"));
                System.out.println("mod_pos_neg=" + invoke("mod_pos_neg"));
                System.out.println("mod_neg_neg=" + invoke("mod_neg_neg"));
                System.out.println("mod_pos_pos=" + invoke("mod_pos_pos"));
                System.out.println("mod_min_neg_one=" + invoke("mod_min_neg_one"));
              }

              private static Object invoke(String name) {
                Object entry = deal.codegen.jvm.JvmRuntime
                    .exportSurface("main").read(name);
                if (!(entry instanceof deal.codegen.jvm.JvmRuntime.FunctionValue fn)) {
                  throw new IllegalStateException(
                      "the entry surface publishes no function for " + name);
                }
                return fn.fn.invoke(new Object[0]);
              }
            }
            """.formatted(className);
    }

    private static void testFiveCases() throws Exception {
        System.out.println("-- the five sign/boundary remainders through the "
            + "oracle, the LuaJIT artifact, and the JVM artifact --");
        Project project = writeProject("cases", PROBE_MODULE, MOD_PROBE_SOURCE);
        try {
            // The reference: the JVM runtime's own INT32_MOD_TRUNC row.
            Map<String, Long> reference = new LinkedHashMap<>();
            for (Case probeCase : CASES) {
                Object value = JvmRuntime.arith("INT32_MOD_TRUNC",
                    Long.valueOf(probeCase.dividend()),
                    Long.valueOf(probeCase.divisor()), "probe-op", "probe-digest",
                    "probe-parent", "reference.deal:1:1");
                check(value instanceof Long, "JvmRuntime.arith("
                    + probeCase.dividend() + ", " + probeCase.divisor() + ") "
                    + "publishes an int: " + value);
                reference.put(probeCase.export(), (Long) value);
                checkEq(probeCase.pinned(), (Long) value, "JvmRuntime.arith("
                    + probeCase.dividend() + ", " + probeCase.divisor() + ")");
            }

            // The oracle: one lowered project, the entry main's five checks.
            CompilationOrchestrator oracle = productionCompile(project, Backend.LUAJIT);
            assertProductionEmission(oracle, "the five-case probe (oracle lowering)");
            Lowered lowered = lower(oracle, "the five-case probe");
            if (lowered != null) {
                SemanticRuntimeModel.ConsumerRun run = oracleRun(lowered);
                check(run.terminal()
                        instanceof SemanticRuntimeModel.Terminal.Success,
                    "the oracle completes the five-case probe with a success "
                        + "terminal: " + run.terminal());
                Map<Integer, String> outputs = modOutputs(run, lowered);
                for (Case probeCase : CASES) {
                    int line = caseLine(MOD_PROBE_SOURCE, probeCase.export());
                    String atom = outputs.get(line);
                    check(atom != null, "the oracle executes the "
                        + probeCase.export() + " remainder at line " + line + ": "
                        + outputs.keySet());
                    checkEq(SemanticRuntimeModel.intAtom(
                            reference.get(probeCase.export())), atom,
                        "the oracle's " + probeCase.export() + " (" + probeCase.dividend()
                            + " % " + probeCase.divisor() + ")");
                }
            }

            // The emitted LuaJIT artifact, under real luajit.
            Project luaProject = writeProject("cases-lua", PROBE_MODULE,
                MOD_PROBE_SOURCE);
            try {
                CompilationOrchestrator lua = productionCompile(luaProject,
                    Backend.LUAJIT);
                assertProductionEmission(lua, "the five-case probe (LuaJIT)");
                ProcessOutcome run = runLuaProbe(luaProject, "int32_mod_probe.lua",
                    luaCaseDriver(PROBE_MODULE + ".lua"), false);
                checkEq(0, run.exitCode(), "the emitted LuaJIT artifact executes "
                    + "the five-case probe under luajit: " + run.output());
                assertValues(parseValues(run.stdout(), "luajit"), reference,
                    "the emitted LuaJIT artifact");
            } finally {
                deleteRecursively(luaProject.root());
            }

            // The emitted JVM artifact, under javac plus java.
            Project jvmProject = writeProject("cases-jvm", PROBE_MODULE,
                MOD_PROBE_SOURCE);
            try {
                CompilationOrchestrator jvm = productionCompile(jvmProject,
                    Backend.JVM);
                assertProductionEmission(jvm, "the five-case probe (JVM)");
                String className = JvmBackend.classNameFor(PROBE_MODULE);
                Path artifact = jvmProject.out().resolve(className + ".java");
                check(Files.exists(artifact), "the JVM artifact is the one project "
                    + "class: " + artifact);
                ProcessOutcome run = runJvmProbe(jvmProject.out(),
                    jvmProject.root().resolve("classes"), "Int32ModCaseProbe",
                    jvmCaseDriver(className), artifact.toAbsolutePath().toString());
                checkEq(0, run.exitCode(), "the emitted JVM artifact executes the "
                    + "five-case probe under java: " + run.output());
                assertValues(parseValues(run.stdout(), "java"), reference,
                    "the emitted JVM artifact");
            } finally {
                deleteRecursively(jvmProject.root());
            }
        } finally {
            deleteRecursively(project.root());
        }
    }

    /** The oracle's truncated remainders, keyed by the operation's source line. */
    private static Map<Integer, String> modOutputs(
            SemanticRuntimeModel.ConsumerRun run, Lowered lowered) {
        Map<OpId, SemanticOp> ops = new LinkedHashMap<>();
        for (SemanticOp op : lowered.entryUnit().ops()) {
            ops.put(op.opId(), op);
        }
        Map<Integer, String> outputs = new LinkedHashMap<>();
        for (SemanticRuntimeModel.TraceEvent event : run.trace()) {
            if (event.phase() != SemanticRuntimeModel.Phase.SUCCESS
                    || event.kind() != SemanticOpKind.BINARY) {
                continue;
            }
            SemanticOp op = ops.get(event.op());
            if (op == null || !(op.payload() instanceof KindPayload.BinaryPayload payload)
                    || payload.selector() != BinarySelector.INT32_MOD_TRUNC) {
                continue;
            }
            if (op.origin().span() == null) {
                continue;
            }
            outputs.put(op.origin().span().startLine(), event.output());
        }
        return outputs;
    }

    /** One consumed value set against the JVM runtime's reference row. */
    private static void assertValues(Map<String, Long> values,
            Map<String, Long> reference, String consumer) {
        for (Case probeCase : CASES) {
            checkEq(reference.get(probeCase.export()),
                values.get(probeCase.export()), consumer + ": " + probeCase.export()
                    + " (" + probeCase.dividend() + " % " + probeCase.divisor()
                    + ") equals the JVM runtime's truncated remainder");
            checkEq(probeCase.pinned(), values.get(probeCase.export()), consumer
                + ": " + probeCase.export() + " equals its pinned remainder");
        }
        checkEq(CASES.size(), values.size(), consumer + ": the probe prints exactly "
            + "the five cases");
    }

    // =========================================================================
    // 3. The zero-divisor arm
    // =========================================================================

    private static void testZeroDivisor() throws Exception {
        System.out.println("-- the unchanged INT32_DIVISION_BY_ZERO projection --");
        Project project = writeProject("zero", PROBE_MODULE, ZERO_PROBE_SOURCE);
        try {
            int[] position = expressionPosition(ZERO_PROBE_SOURCE);
            String expectedOrigin = "src/" + PROBE_MODULE + ".deal:"
                + position[0] + ":" + position[1];

            // The oracle's snapshot.
            CompilationOrchestrator oracle = productionCompile(project, Backend.LUAJIT);
            assertProductionEmission(oracle, "the zero-divisor probe (oracle lowering)");
            String oracleCode = null;
            String oracleMessage = null;
            String oracleOrigin = null;
            Lowered lowered = lower(oracle, "the zero-divisor probe");
            if (lowered != null) {
                SemanticRuntimeModel.ConsumerRun run = oracleRun(lowered);
                check(run.terminal()
                        instanceof SemanticRuntimeModel.Terminal.DealFailure,
                    "the oracle's zero-divisor run fails with the arm's projection: "
                        + run.terminal());
                if (run.terminal()
                        instanceof SemanticRuntimeModel.Terminal.DealFailure failure) {
                    oracleCode = failure.error().code();
                    oracleMessage = failure.error().message();
                    oracleOrigin = failure.error().origin();
                }
            }
            checkEq("E8005", oracleCode, "the oracle's zero-divisor code");
            checkEq("integer division by zero", oracleMessage,
                "the oracle's zero-divisor message");
            checkEq(expectedOrigin, originTail(oracleOrigin),
                "the oracle's zero-divisor operation origin");
            assertZeroDivisorArm(oracleCode, oracleMessage);

            // The JVM runtime's own projection at the oracle's operation origin.
            if (oracleOrigin != null) {
                JvmRuntime.setTraceEnabled(false);
                try {
                    JvmRuntime.arith("INT32_MOD_TRUNC", Long.valueOf(7),
                        Long.valueOf(0), "probe-op", "probe-digest", "probe-parent",
                        oracleOrigin);
                    fail("JvmRuntime.arith(7 % 0) raises the division-by-zero arm");
                } catch (JvmRuntime.DealError error) {
                    checkEq(oracleCode, error.code, "the JVM runtime's code equals "
                        + "the oracle's");
                    checkEq(oracleMessage, error.msg, "the JVM runtime's message "
                        + "equals the oracle's");
                    checkEq(oracleOrigin, error.origin, "the JVM runtime's origin "
                        + "equals the oracle's");
                } finally {
                    JvmRuntime.setTraceEnabled(true);
                }
            }

            // The emitted LuaJIT artifact, under real luajit (the deferred entry
            // returns the failure table, so the projection's fields are readable).
            Project luaProject = writeProject("zero-lua", PROBE_MODULE,
                ZERO_PROBE_SOURCE);
            try {
                CompilationOrchestrator lua = productionCompile(luaProject,
                    Backend.LUAJIT);
                assertProductionEmission(lua, "the zero-divisor probe (LuaJIT)");
                ProcessOutcome run = runLuaProbe(luaProject, "int32_mod_zero.lua",
                    luaZeroDriver(PROBE_MODULE + ".lua"), true);
                checkEq(0, run.exitCode(), "the zero-divisor LuaJIT probe runs: "
                    + run.output());
                String[] row = parseErrorRow(run.stdout(), "luajit");
                check(row != null, "the emitted LuaJIT artifact raises the E8005 "
                    + "projection: " + run.output());
                if (row != null) {
                    checkEq(oracleCode, row[1], "the LuaJIT artifact's code equals "
                        + "the oracle's");
                    checkEq(oracleMessage, row[2], "the LuaJIT artifact's message "
                        + "equals the oracle's");
                    checkEq(originTail(oracleOrigin), originTail(row[3]),
                        "the LuaJIT artifact's origin equals the oracle's (the "
                            + "module-relative operation origin)");
                    checkEq(expectedOrigin, originTail(row[3]),
                        "the LuaJIT artifact's origin is the operation origin");
                }
            } finally {
                deleteRecursively(luaProject.root());
            }

            // The emitted JVM artifact, under javac plus java.
            Project jvmProject = writeProject("zero-jvm", PROBE_MODULE,
                ZERO_PROBE_SOURCE);
            try {
                CompilationOrchestrator jvm = productionCompile(jvmProject,
                    Backend.JVM);
                assertProductionEmission(jvm, "the zero-divisor probe (JVM)");
                String className = JvmBackend.classNameFor(PROBE_MODULE);
                Path artifact = jvmProject.out().resolve(className + ".java");
                check(Files.exists(artifact), "the JVM artifact is the one project "
                    + "class: " + artifact);
                ProcessOutcome run = runJvmProbe(jvmProject.out(),
                    jvmProject.root().resolve("classes"), "Int32ModZeroProbe",
                    jvmZeroDriver(className), artifact.toAbsolutePath().toString());
                checkEq(0, run.exitCode(), "the zero-divisor JVM probe runs: "
                    + run.output());
                String[] row = parseErrorRow(run.stdout(), "java");
                check(row != null, "the emitted JVM artifact raises the E8005 "
                    + "projection: " + run.output());
                if (row != null) {
                    checkEq(oracleCode, row[1], "the JVM artifact's code equals "
                        + "the oracle's");
                    checkEq(oracleMessage, row[2], "the JVM artifact's message "
                        + "equals the oracle's");
                    checkEq(originTail(oracleOrigin), originTail(row[3]),
                        "the JVM artifact's origin equals the oracle's (the "
                            + "module-relative operation origin)");
                    checkEq(expectedOrigin, originTail(row[3]),
                        "the JVM artifact's origin is the operation origin");
                }
            } finally {
                deleteRecursively(jvmProject.root());
            }
        } finally {
            deleteRecursively(project.root());
        }
    }

    /** The module-relative tail of one `file:line:column` origin (the lane form). */
    private static String originTail(String origin) {
        if (origin == null) {
            return null;
        }
        int marker = origin.lastIndexOf("src/");
        return marker < 0 ? origin : origin.substring(marker);
    }

    /** The closed arm's own tuple (the single projection source). */
    private static void assertZeroDivisorArm(String code, String message) {
        BoundaryFailure arm = FailureContractRegistry.render(
            FailureArmId.INT32_DIVISION_BY_ZERO, Map.of(), null, null, null);
        checkEq(arm.code().name(), code, "the closed arm's code is the consumer's "
            + "code");
        checkEq(arm.message(), message, "the closed arm's message is the consumer's "
            + "message");
        checkEq("E8005", code, "the pinned E8005 code");
        checkEq("integer division by zero", message, "the pinned E8005 message");
    }

    private static String luaZeroDriver(String artifact) {
        return "local ok, err = pcall(dofile, \"" + artifact + "\")\n"
            + "if not ok then print(\"LOAD|fail|\" .. tostring(err)) os.exit(3) end\n"
            + "local mainOk, mainErr = __dealMain()\n"
            + "if mainOk then print(\"MAIN_OK\") os.exit(0) end\n"
            + "local code = type(mainErr) == \"table\" and mainErr.code or \"NON_DEAL\"\n"
            + "local message = type(mainErr) == \"table\"\n"
            + "  and (mainErr.m or mainErr.message) or tostring(mainErr)\n"
            + "local origin = type(mainErr) == \"table\"\n"
            + "  and (mainErr.o or \"nil\") or \"nil\"\n"
            + "print(\"ERR|\" .. tostring(code) .. \"|\" .. tostring(message)\n"
            + "  .. \"|\" .. tostring(origin))\n";
    }

    private static String jvmZeroDriver(String className) {
        return """
            public final class Int32ModZeroProbe {
              public static void main(String[] args) {
                try {
                  %s.dealMain();
                  System.out.println("MAIN_OK");
                } catch (deal.codegen.jvm.JvmRuntime.DealError error) {
                  System.out.println("ERR|" + error.code + "|" + error.msg
                      + "|" + error.origin);
                }
              }
            }
            """.formatted(className);
    }

    // =========================================================================
    // 4. The named fixture through the production invocation on both targets
    // =========================================================================

    private static void testCorpusFixture() throws Exception {
        System.out.println("-- " + FIXTURE + " through the release-owned "
            + "production invocation on LuaJIT and JVM --");
        String sidecar = Files.readString(CORPUS.resolve(FIXTURE_SIDECAR),
            StandardCharsets.UTF_8);
        check(sidecar.contains("\"mode\": \"runtime-ok\""), FIXTURE + ": the sidecar "
            + "pins the runtime-ok mode");
        checkEq(0, sidecarExitCode(sidecar), FIXTURE + ": the sidecar pins exit 0");
        checkEq("", sidecarTranscript(sidecar, "stdout"), FIXTURE + ": the sidecar "
            + "pins an empty stdout");
        checkEq("", sidecarTranscript(sidecar, "stderr"), FIXTURE + ": the sidecar "
            + "pins an empty stderr");
        String source = fixtureSource(FIXTURE);

        // LuaJIT: one project artifact plus the unchanged runtime/stdlib copies.
        Project luaProject = writeProject("fixture-lua", FIXTURE, source);
        try {
            CompilationOrchestrator lua = productionCompile(luaProject, Backend.LUAJIT);
            assertProductionEmission(lua, FIXTURE + " (LuaJIT)");
            Path artifact = luaProject.out().resolve(FIXTURE + ".lua");
            check(Files.exists(artifact), FIXTURE + " (LuaJIT): the one project "
                + "artifact is staged at the entry module path");
            checkEq(1, projectArtifacts(luaProject.out(), ".lua"), FIXTURE
                + " (LuaJIT): exactly one project artifact is staged beside the "
                + "deployed runtime and stdlib copies");
            testEmittedArm(Files.readString(artifact, StandardCharsets.UTF_8)
                .replace("\r\n", "\n"));
            ProcessOutcome run = runLuaProbe(luaProject, "int32_mod_fixture.lua",
                luaFixtureDriver(FIXTURE + ".lua"), false);
            checkEq(0, run.exitCode(), FIXTURE + " (LuaJIT): the emitted production "
                + "artifact executes with the pinned runtime-ok exit code: "
                + run.output());
            checkEq("", run.stdout(), FIXTURE + " (LuaJIT): the pinned empty stdout");
            checkEq("", run.stderr(), FIXTURE + " (LuaJIT): the pinned empty stderr");
            Lowered lowered = lower(lua, FIXTURE);
            if (lowered != null) {
                SemanticRuntimeModel.ConsumerRun oracle = oracleRun(lowered);
                check(oracle.terminal()
                        instanceof SemanticRuntimeModel.Terminal.Success,
                    FIXTURE + ": the oracle agrees with the pinned runtime-ok "
                        + "outcome: " + oracle.terminal());
            }
        } finally {
            deleteRecursively(luaProject.root());
        }

        // JVM: the one project class.
        Project jvmProject = writeProject("fixture-jvm", FIXTURE, source);
        try {
            CompilationOrchestrator jvm = productionCompile(jvmProject, Backend.JVM);
            assertProductionEmission(jvm, FIXTURE + " (JVM)");
            String className = JvmBackend.classNameFor(FIXTURE);
            Path artifact = jvmProject.out().resolve(className + ".java");
            check(Files.exists(artifact), FIXTURE + " (JVM): the one project class "
                + "is staged");
            checkEq(1, projectArtifacts(jvmProject.out(), ".java"), FIXTURE
                + " (JVM): exactly one project artifact is staged");
            Path classes = jvmProject.root().resolve("classes");
            Files.createDirectories(classes);
            ProcessOutcome javac = runProcess(List.of("javac", "--release", "25",
                "-proc:none", "-cp", absoluteClasspath(), "-d", classes.toString(),
                artifact.toAbsolutePath().toString()), jvmProject.out());
            checkEq(0, javac.exitCode(), FIXTURE + " (JVM): the emitted production "
                + "artifact compiles with javac --release 25 -proc:none: "
                + javac.output());
            if (javac.exitCode() == 0) {
                ProcessOutcome run = runProcess(List.of("java", "-cp",
                    absoluteClasspath() + File.pathSeparator + classes, className),
                    jvmProject.out());
                checkEq(0, run.exitCode(), FIXTURE + " (JVM): the emitted production "
                    + "artifact executes with the pinned runtime-ok exit code: "
                    + run.output());
                checkEq("", run.stdout(), FIXTURE + " (JVM): the pinned empty "
                    + "stdout");
                checkEq("", run.stderr(), FIXTURE + " (JVM): the pinned empty "
                    + "stderr");
            }
        } finally {
            deleteRecursively(jvmProject.root());
        }
    }

    /**
     * The project artifacts of one published output root: the staged files
     * with the extension, excluding the deployed {@code deal/} runtime and
     * the {@code std/} stdlib copies.
     */
    private static int projectArtifacts(Path out, String extension) {
        if (!Files.isDirectory(out)) {
            return -1;
        }
        int count = 0;
        try (Stream<Path> walk = Files.walk(out)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String relative = out.relativize(file).toString().replace('\\', '/');
                if (!relative.endsWith(extension)) {
                    continue;
                }
                if (relative.startsWith("deal/") || relative.startsWith("std/")) {
                    continue;
                }
                count++;
            }
        } catch (Exception e) {
            return -1;
        }
        return count;
    }

    /**
     * The LuaJIT fixture driver: the chunk-load module walk and entry
     * {@code main} (the lane's invocation contract — the fixture's own
     * guards are the pinned outcome), then the entry surface.
     */
    private static String luaFixtureDriver(String artifact) {
        return "local surfaces = dofile(\"" + artifact + "\")\n"
            + "assert(type(surfaces) == \"table\",\n"
            + "  \"the production chunk returns the entry surface\")\n";
    }

    private static int sidecarExitCode(String sidecar) {
        Matcher matcher = Pattern.compile("\"exitCode\":\\s*(-?\\d+)").matcher(sidecar);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MIN_VALUE;
    }

    private static String sidecarTranscript(String sidecar, String channel) {
        Matcher matcher = Pattern.compile("\"" + channel
            + "\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(sidecar);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).replace("\\n", "\n").replace("\\\"", "\"");
    }

    public static void main(String[] args) throws Exception {
        testFiveCases();
        testZeroDivisor();
        testCorpusFixture();
        System.out.println();
        System.out.println("int32 truncated remainder: " + passed + " passed, "
            + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
