package deal.test;

import deal.diagnostics.CompilerDiagnostic;
import deal.module.CompilationOrchestrator;
import deal.project.CliOverrides;
import deal.project.ProjectLocator;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ISSUE-0716: the production LuaJIT chunk stores its function factories,
 * detached class-default functions, and adapter thunk re-executors as
 * fields of one chunk-level local table ({@code __factories}), never as
 * one pre-declared chunk-level local per factory.
 *
 * <p>LuaJIT bounds one function at 200 local variables. The chunk's shared
 * prelude, hoisted scratch temps, and runtime helpers already consume the
 * larger part of that budget, so a program with enough functions, class
 * defaults, or thunk re-executors crosses the limit as soon as the
 * emitter pre-declares one local per factory — the unchanged skill example
 * ({@code skills/write-deal/examples/src/main.deal}) already does. The
 * bounded store keeps the chunk's local count independent of the factory
 * count while the field assignment stays the declaration form, so a body
 * emitted before a later factory's assignment still resolves the field at
 * call time (one captured table, never a nil global and never one upvalue
 * per factory).</p>
 *
 * <ol>
 *   <li>the growing-factory fixture (300 exported functions, a calling
 *       group, one default producer, and a 150-field defaulted class —
 *       more than 450 factories, far beyond the 200-local budget)
 *       compiles through the release-owned production invocation, carries
 *       no per-factory chunk local, and executes under real
 *       {@code luajit} with the fixture marker and exit 0;</li>
 *   <li>the unchanged skill example compiles through the production
 *       invocation and executes under real {@code luajit} with exit 0 and
 *       no output (its own checks throw on mismatch).</li>
 * </ol>
 *
 * <p>Both probes fail on the pre-fix emitter, whose factory
 * pre-declaration line crosses LuaJIT's 200-local limit at load time
 * ({@code main function has more than 200 local variables}).</p>
 */
public class LuaJitChunkLocalBoundTest {

    /** LuaJIT's per-function local-variable limit. */
    private static final int LUAJIT_LOCAL_LIMIT = 200;

    /** Exported zero-capture functions of the growing fixture. */
    private static final int GROWING_FUNCTIONS = 300;

    /** Calling groups of the growing fixture (one function each). */
    private static final int GROUP_COUNT = 10;

    /** Members each calling group invokes. */
    private static final int GROUP_MEMBERS = 10;

    /** Defaulted fields of the growing fixture's wide class. */
    private static final int DEFAULTED_FIELDS = 150;

    /** The marker the growing fixture prints once its sums hold. */
    private static final String FIXTURE_MARKER = "GROWING-FACTORY-OK";

    private static final String DEAL_JSON = """
        {
          "languageVersion": "1.2",
          "moduleRoots": ["src"],
          "output": "out",
          "backend": "luajit",
          "stdlib": "1.2"
        }
        """;

    private static final Path SKILL_EXAMPLE_ROOT =
        Path.of("skills", "write-deal", "examples");

    private static int passed = 0;
    private static int failed = 0;

    /** One factory field of the bounded store: {@code __factories.F|D|T<id>}. */
    private static final Pattern FACTORY_ENTRY =
        Pattern.compile("__factories\\.([FDT][0-9]+)");

    /** The field-assignment declaration form of one factory. */
    private static final Pattern FACTORY_ASSIGNMENT =
        Pattern.compile("(?m)^__factories\\.[FDT][0-9]+ = function");

    /** A forbidden per-factory chunk local ({@code local F…}/D…/T…}). */
    private static final Pattern PER_FACTORY_LOCAL =
        Pattern.compile("(?m)^local (function )?[FDT][0-9]+");

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
    // 1. The growing-factory regression under real luajit
    // =========================================================================

    private static void testGrowingFactoryChunk() throws Exception {
        System.out.println("-- the growing-factory chunk: the production "
            + "compile, the bounded store, and the real luajit run --");
        Path root = Files.createTempDirectory("deal-chunk-bound-");
        try {
            write(root, "deal.json", DEAL_JSON);
            write(root, "src/main.deal", growingFactorySource());
            Path entry = root.resolve("src/main.deal").toAbsolutePath();
            Path out = root.resolve("out").toAbsolutePath();
            List<String> diagnostics = new ArrayList<>();
            check(productionCompile(entry, out, diagnostics),
                "the growing-factory fixture compiles through the release-owned "
                    + "production invocation: " + diagnostics);

            Path artifact = out.resolve("main.lua");
            check(Files.isRegularFile(artifact),
                "the production compile stages the entry chunk main.lua");
            String lua = Files.readString(artifact, StandardCharsets.UTF_8);
            Set<String> entries = factoryEntries(lua);
            check(entries.size() > LUAJIT_LOCAL_LIMIT,
                "the fixture carries more factories than LuaJIT's per-function "
                    + "local limit would admit as chunk locals: " + entries.size());

            check(!PER_FACTORY_LOCAL.matcher(lua).find(),
                "the chunk declares no per-factory chunk-level local");
            Matcher assignments = FACTORY_ASSIGNMENT.matcher(lua);
            int assignmentCount = 0;
            while (assignments.find()) {
                assignmentCount++;
            }
            checkEq(entries.size(), assignmentCount,
                "every factory is declared as a field assignment of the bounded "
                    + "store (" + assignmentCount + " assignments for "
                    + entries.size() + " entries)");
            int storeAt = lua.indexOf("local __factories = {}");
            int firstFieldAt = lua.indexOf("__factories.");
            check(storeAt >= 0,
                "the chunk declares the one bounded factory store");
            check(storeAt >= 0 && firstFieldAt > storeAt,
                "the store declaration precedes the factory assignments and "
                    + "references");

            ProcessOutcome run = runProcess(out, List.of("luajit", "main.lua"));
            checkEq(0, run.exitCode(),
                "the growing-factory chunk executes under real luajit: "
                    + run.output());
            check(run.stdout().contains(FIXTURE_MARKER),
                "the growing-factory chunk publishes its own marker: "
                    + run.stdout().replace("\n", "\\n"));
            checkEq("", run.stderr(),
                "the executed chunk writes nothing to stderr");
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * The growing fixture: 300 exported zero-capture functions, ten group
     * functions each calling ten of them, one default producer, and one
     * exported class with 150 defaulted fields. The groups and the wide
     * class keep the executed path honest (the closure sum and the
     * default-produced field sum) while the factory count exceeds the
     * 200-local budget.
     */
    private static String growingFactorySource() {
        StringBuilder source = new StringBuilder();
        source.append("import * as console from \"std/console\";\n\n");
        for (int i = 0; i < GROWING_FUNCTIONS; i++) {
            source.append("export function m").append(i)
                .append("(): int {\n  return 1;\n}\n\n");
        }
        for (int i = 0; i < GROUP_COUNT; i++) {
            source.append("export function g").append(i).append("(): int {\n  return ");
            for (int j = 0; j < GROUP_MEMBERS; j++) {
                if (j > 0) {
                    source.append(" + ");
                }
                source.append("m").append(j).append("()");
            }
            source.append(";\n}\n\n");
        }
        source.append("export function makeDefault(): int {\n  return 3;\n}\n\n");
        source.append("export class Wide {\n");
        for (int i = 0; i < DEFAULTED_FIELDS; i++) {
            source.append("  a").append(i).append(": int = makeDefault();\n");
        }
        source.append("}\n\n");
        int expected = GROUP_COUNT * GROUP_MEMBERS + DEFAULTED_FIELDS * 3;
        source.append("export function main(): null {\n");
        source.append("  let total: int = ");
        for (int i = 0; i < GROUP_COUNT; i++) {
            if (i > 0) {
                source.append(" + ");
            }
            source.append("g").append(i).append("()");
        }
        source.append(";\n");
        source.append("  let w: Wide = {};\n");
        source.append("  total = total");
        for (int i = 0; i < DEFAULTED_FIELDS; i++) {
            source.append(" + w.a").append(i);
        }
        source.append(";\n");
        source.append("  if (total !== ").append(expected).append(") {\n");
        source.append("    throw { code: \"TEST_FAIL\", message: \"sum mismatch\" };\n");
        source.append("  }\n");
        source.append("  console.log(\"").append(FIXTURE_MARKER).append("\");\n");
        source.append("  return null;\n}\n");
        return source.toString();
    }

    // =========================================================================
    // 2. The unchanged skill example under real luajit
    // =========================================================================

    private static void testSkillExampleEntry() throws Exception {
        System.out.println("-- the unchanged skill example: the production "
            + "compile and the silent real luajit run --");
        Path root = Files.createTempDirectory("deal-skill-entry-");
        try {
            Path entry = SKILL_EXAMPLE_ROOT.resolve("src/main.deal").toAbsolutePath();
            check(Files.isRegularFile(entry),
                "the skill example entry is present: " + entry);
            Path out = root.resolve("out").toAbsolutePath();
            List<String> diagnostics = new ArrayList<>();
            check(productionCompile(entry, out, diagnostics),
                "the unchanged skill example compiles through the release-owned "
                    + "production invocation: " + diagnostics);

            Path artifact = out.resolve("main.lua");
            check(Files.isRegularFile(artifact),
                "the skill example stages the entry chunk main.lua");
            String lua = Files.readString(artifact, StandardCharsets.UTF_8);
            check(!PER_FACTORY_LOCAL.matcher(lua).find(),
                "the skill example chunk declares no per-factory chunk-level "
                    + "local");

            ProcessOutcome run = runProcess(out, List.of("luajit", "main.lua"));
            checkEq(0, run.exitCode(),
                "the unchanged skill example executes under real luajit: "
                    + run.output());
            checkEq("", run.stdout(),
                "the skill example completes silently on stdout");
            checkEq("", run.stderr(),
                "the skill example completes silently on stderr");
        } finally {
            deleteRecursively(root);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** The distinct factory field names the chunk carries. */
    private static Set<String> factoryEntries(String lua) {
        Set<String> entries = new HashSet<>();
        Matcher matcher = FACTORY_ENTRY.matcher(lua);
        while (matcher.find()) {
            entries.add(matcher.group(1));
        }
        return entries;
    }

    /** The release-owned production invocation (the epic's production record). */
    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    /**
     * Compiles one entry through the production path: strict locate, the
     * manifest-owned context, and the orchestrator's real compile. The
     * override pins the output to a fresh directory so no probe writes
     * into the checkout.
     */
    private static boolean productionCompile(Path entry, Path output,
                                             List<String> diagnostics) {
        ProjectLocator.LocateResult located = ProjectLocator.locate(
            entry.toString(), new CliOverrides(null, output.toString()));
        if (located.context() == null) {
            diagnostics.add(located.e2010() != null
                ? located.e2010().message()
                : String.valueOf(located.cliDiagnostic()));
            return false;
        }
        try {
            CompilationOrchestrator orchestrator = new CompilationOrchestrator(
                located.context(), entry, false, false, false, false, null,
                productionInvocation());
            boolean compiled = orchestrator.compile();
            for (CompilerDiagnostic diagnostic : orchestrator.diagnostics()) {
                if ("error".equals(diagnostic.severity())) {
                    diagnostics.add(diagnostic.code() + " "
                        + diagnostic.message());
                }
            }
            return compiled;
        } catch (Exception e) {
            diagnostics.add(e.toString());
            return false;
        }
    }

    private record ProcessOutcome(int exitCode, String stdout, String stderr) {
        String output() {
            return "exit " + exitCode + ", stdout '"
                + stdout.replace("\n", "\\n") + "', stderr '"
                + stderr.replace("\n", "\\n") + "'";
        }
    }

    private static ProcessOutcome runProcess(Path workDir, List<String> command)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workDir.toFile());
        Process process = builder.start();
        String stdout = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exit = process.waitFor();
        return new ProcessOutcome(exit, stdout, stderr);
    }

    private static void write(Path root, String relative, String content)
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
        System.out.println("=== LuaJIT Chunk Local Bound Test (ISSUE-0716) ===\n");
        testGrowingFactoryChunk();
        testSkillExampleEntry();
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== LuaJIT Chunk Local Bound Test Passed ===");
    }
}
