package deal.test;

import deal.diagnostics.CompilerDiagnostic;
import deal.module.CompilationOrchestrator;
import deal.project.CliOverrides;
import deal.project.ProjectLocator;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.test.HostModuleLoadEmissionTest.ProcessOutcome;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static deal.test.HostModuleLoadEmissionTest.deleteRecursively;
import static deal.test.HostModuleLoadEmissionTest.writeFileIn;

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

    private static final int PROBE_LOCALS = 12;

    private static final String DEAL_JSON = """
        {
          "languageVersion": "1.5",
          "moduleRoots": ["src"],
          "output": "out",
          "backend": "luajit",
          "stdlib": "1.5"
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
            writeFileIn(root, "deal.json", DEAL_JSON);
            writeFileIn(root, "src/main.deal", growingFactorySource());
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

            ProcessOutcome run = HostModuleLoadEmissionTest.runProcess(List.of("luajit", "main.lua"), out, false);
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

    private static String growingFactorySource() {
        StringBuilder source = new StringBuilder();
        source.append("import * as console from \"std/console\";\n\n");
        for (int i = 0; i < GROWING_FUNCTIONS; i++) {
            source.append("export function m").append(i)
                .append("(): number {\n  return 1;\n}\n\n");
        }
        for (int i = 0; i < GROUP_COUNT; i++) {
            source.append("export function g").append(i).append("(): number {\n  return ");
            for (int j = 0; j < GROUP_MEMBERS; j++) {
                if (j > 0) {
                    source.append(" + ");
                }
                source.append("m").append(j).append("()");
            }
            source.append(";\n}\n\n");
        }
        source.append("export function makeDefault(): number {\n  return 3;\n}\n\n");
        source.append("export class Wide {\n");
        for (int i = 0; i < DEFAULTED_FIELDS; i++) {
            source.append("  a").append(i).append(": number;\n");
        }
        source.append("}\n\n");
        int expected = GROUP_COUNT * GROUP_MEMBERS + DEFAULTED_FIELDS * 3;
        source.append("export function main(): null {\n");
        source.append("  let total: number = ");
        for (int i = 0; i < GROUP_COUNT; i++) {
            if (i > 0) {
                source.append(" + ");
            }
            source.append("g").append(i).append("()");
        }
        source.append(";\n");
        source.append("  let w: Wide = {");
        for (int i = 0; i < DEFAULTED_FIELDS; i++) {
            if (i > 0) source.append(", ");
            source.append("a").append(i).append(": makeDefault()");
        }
        source.append("};\n");
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

    private static void testSkillExampleEntry(Path root) throws Exception {
        System.out.println("-- the unchanged skill example: the production "
            + "compile and the silent real luajit run --");
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

        ProcessOutcome run = HostModuleLoadEmissionTest.runProcess(List.of("luajit", "main.lua"), out, false);
        checkEq(0, run.exitCode(),
            "the unchanged skill example executes under real luajit: "
                + run.output());
        checkEq("", run.stdout(),
            "the skill example completes silently on stdout");
        checkEq("", run.stderr(),
            "the skill example completes silently on stderr");
    }

    // =========================================================================
    // 3. The prelude's main-chunk local budget
    // =========================================================================

    private static void testPreludeChunkLocalBudget(Path root) throws Exception {
        System.out.println("-- the prelude's main-chunk local budget: the "
            + "production chunk and the appended probe body --");
        writeFileIn(root, "budget-probe.lua", """
            local text = io.open("out/main.lua"):read("*a")
            text = text:gsub("%%s*$", "")
            local tail = 'return __exportSurfaces["main"]'
            assert(text:sub(-#tail) == tail,
              "the artifact tail is the surface return")
            local extra = {}
            for i = 1, %d do
              extra[i] = "local __budget" .. i .. " = i"
            end
            local body = text:sub(1, #text - #tail)
              .. table.concat(extra, "\\n") .. "\\n" .. tail
            local chunk, err = load(body, "budget-probe")
            if chunk == nil then
              print("BUDGET-FAIL|" .. tostring(err))
            else
              print("BUDGET-OK")
            end
            """.formatted(PROBE_LOCALS));
        ProcessOutcome run = HostModuleLoadEmissionTest.runProcess(
            List.of("luajit", "budget-probe.lua"), root, false);
        checkEq(0, run.exitCode(),
            "the local-budget probe runs under real luajit: " + run.output());
        check(run.stdout().contains("BUDGET-OK"),
            "the production chunk keeps room for the appended probe body "
                + "of " + PROBE_LOCALS + " locals: "
                + run.stdout().replace("\n", "\\n"));
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

    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

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

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== LuaJIT Chunk Local Bound Test (ISSUE-0716) ===\n");
        testGrowingFactoryChunk();
        Path skillRoot = Files.createTempDirectory("deal-skill-entry-");
        try {
            testSkillExampleEntry(skillRoot);
            testPreludeChunkLocalBudget(skillRoot);
        } finally {
            deleteRecursively(skillRoot);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== LuaJIT Chunk Local Bound Test Passed ===");
    }
}
