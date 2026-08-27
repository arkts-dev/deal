package deal.test;

import deal.codegen.Backend;
import deal.module.CompilationOrchestrator;
import deal.module.DealConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * JavaScript backend unit gate (ISSUE-0194 gate wiring,
 * js-backend-conformance-e2e D2 orchestrator-level surface).
 *
 * <p>Pins the selection seam and the deployment behavior the e2e gate
 * builds on: {@link Backend#JS} selection through the CLI/manifest name
 * {@code "js"} (case-insensitive), {@code deal.json} {@code "backend":
 * "js"} acceptance with the v1.2 {@code languageVersion}, the CLI
 * default output {@code build/js}, the orchestrator's runtime/stdlib
 * deployment copies (including {@code std/console.js}), and the
 * no-partial-artifact contract on backend rejection (a host-ABI import
 * raises E6000 and the rejected module writes no artifact). The
 * emission-pin and node-semantic surfaces live in the sibling issue's
 * slice; this class keeps the {@code ./run_tests.sh} gate green without
 * duplicating them.</p>
 */
public final class JsBackendTest {

    private JsBackendTest() { }

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        System.out.println("=== JS Backend Unit Gate ===");
        testBackendSelectionNames();
        testDealJsonBackendAcceptance();
        testCliDefaultOutputBuildJs();
        testOrchestratorDeploymentCopies();
        testNoPartialArtifactOnRejection();
        System.out.println();
        System.out.println("=== JS Backend Unit Summary ===");
        System.out.println("Tests: total " + (passed + failures.size())
            + ", passed " + passed + ", failed " + failures.size());
        for (String failure : failures) {
            System.out.println("  FAIL: " + failure);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    /** Backend.JS joins the single selection seam under the name "js". */
    private static void testBackendSelectionNames() {
        check(Backend.fromCliName("js").orElse(null) == Backend.JS,
            "\"js\" selects Backend.JS");
        check(Backend.fromCliName("JS").orElse(null) == Backend.JS,
            "backend names are case-insensitive (\"JS\" selects Backend.JS)");
        check(Backend.fromCliName(" Js ").orElse(null) == Backend.JS,
            "backend names are trimmed (\" Js \" selects Backend.JS)");
        check(Backend.JS.cliName().equals("js"),
            "Backend.JS.cliName() is \"js\"");
        check(Backend.fromCliName("jvm").orElse(null) == Backend.JVM,
            "\"jvm\" still selects Backend.JVM");
        check(Backend.fromCliName("luajit").orElse(null) == Backend.LUAJIT,
            "\"luajit\" still selects Backend.LUAJIT");
        check(Backend.fromCliName("python").isEmpty(),
            "unknown backend names are rejected");
    }

    /** deal.json "backend": "js" is accepted with languageVersion 1.2. */
    private static void testDealJsonBackendAcceptance() throws Exception {
        Path projectDir = null;
        try {
            projectDir = Files.createTempDirectory("deal_js_backend_test_");
            Files.writeString(projectDir.resolve("deal.json"),
                "{\n  \"languageVersion\": \"1.2\",\n  \"backend\": \"js\"\n}\n",
                StandardCharsets.UTF_8);
            DealConfig.DealConfigParseResult result =
                DealConfig.load(projectDir);
            check(result.config() != null,
                "deal.json with \"backend\": \"js\" loads a config");
            check(result.diagnostics().isEmpty(),
                "deal.json with \"backend\": \"js\" has zero diagnostics: "
                    + result.diagnostics());
            if (result.config() != null) {
                check("js".equals(result.config().backend()),
                    "loaded config backend is \"js\"");
                check("1.2".equals(result.config().languageVersion()),
                    "loaded config languageVersion is \"1.2\"");
                check(Backend.fromCliName(result.config().backend())
                        .orElse(null) == Backend.JS,
                    "the manifest backend resolves to Backend.JS");
            }

            // An unknown backend stays a configuration error.
            Files.writeString(projectDir.resolve("deal.json"),
                "{\n  \"languageVersion\": \"1.2\",\n"
                    + "  \"backend\": \"python\"\n}\n",
                StandardCharsets.UTF_8);
            DealConfig.DealConfigParseResult badResult =
                DealConfig.load(projectDir);
            check(badResult.config() == null
                    || !badResult.diagnostics().isEmpty(),
                "an unknown manifest backend is rejected with diagnostics: "
                    + badResult.diagnostics());
        } finally {
            deleteRecursively(projectDir);
        }
    }

    /**
     * The CLI's default output for the JS backend is {@code build/js}
     * (a real {@code deal.Main} subprocess run from a temp project whose
     * manifest selects {@code "js"} — no {@code --backend}, no
     * {@code --output} flag).
     */
    private static void testCliDefaultOutputBuildJs() throws Exception {
        Path projectDir = null;
        try {
            projectDir = Files.createTempDirectory("deal_js_cli_test_");
            Files.writeString(projectDir.resolve("deal.json"),
                "{\n  \"languageVersion\": \"1.2\",\n  \"backend\": \"js\"\n}\n",
                StandardCharsets.UTF_8);
            Files.writeString(projectDir.resolve("main.deal"),
                "import * as c from \"std/console\";\n\n"
                    + "export function main(): null {\n"
                    + "  c.log(\"cli default output\");\n"
                    + "  return null;\n"
                    + "}\n",
                StandardCharsets.UTF_8);
            // The project-local std/ directory (the .d.deal declarations)
            // and deal/runtime.js keep the subprocess self-contained
            // regardless of CWD (the orchestrator resolves the runtime
            // file CWD-relative when no classpath resource exists).
            Path localStd = projectDir.resolve("std");
            Path localDeal = projectDir.resolve("deal");
            Files.createDirectories(localDeal);
            Files.copy(Path.of("deal/runtime.js").toAbsolutePath(),
                localDeal.resolve("runtime.js"));
            Files.createDirectories(localStd);
            Path repoStd = Path.of("std").toAbsolutePath().normalize();
            try (Stream<Path> walk = Files.walk(repoStd, 1)) {
                for (Path file : walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".d.deal")
                            || p.toString().endsWith(".js"))
                        .sorted(Comparator.comparing(Path::toString))
                        .toList()) {
                    Files.copy(file, localStd.resolve(file.getFileName()));
                }
            }

            Path buildDir = Path.of("build").toAbsolutePath().normalize();
            Process process = new ProcessBuilder(
                "java", "-ea", "-cp", buildDir.toString(), "deal.Main",
                "compile", "main.deal")
                .directory(projectDir.toFile())
                .redirectErrorStream(true)
                .start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(),
                    StandardCharsets.UTF_8);
            }
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                check(false, "CLI subprocess timed out");
                return;
            }
            check(process.exitValue() == 0,
                "CLI compile exits 0 with the manifest-selected JS backend: "
                    + output);
            check(Files.isRegularFile(projectDir.resolve("build/js/main.js")),
                "default JS output build/js holds the entry artifact: "
                    + output);
            check(Files.isRegularFile(
                    projectDir.resolve("build/js/deal/runtime.js")),
                "default JS output build/js holds deal/runtime.js");
            check(Files.isRegularFile(
                    projectDir.resolve("build/js/std/console.js")),
                "default JS output build/js holds std/console.js");
        } finally {
            deleteRecursively(projectDir);
        }
    }

    /**
     * The orchestrator deploys deal/runtime.js and the six spec
     * std/*.js modules (including std/console.js) next to the emitted
     * artifacts.
     */
    private static void testOrchestratorDeploymentCopies() throws Exception {
        Path projectDir = null;
        try {
            projectDir = Files.createTempDirectory("deal_js_deploy_test_");
            Files.writeString(projectDir.resolve("deal.json"),
                "{\n  \"languageVersion\": \"1.2\",\n  \"backend\": \"js\"\n}\n",
                StandardCharsets.UTF_8);
            Files.writeString(projectDir.resolve("main.deal"),
                "import * as c from \"std/console\";\n\n"
                    + "export function main(): null {\n"
                    + "  c.log(\"deploy\");\n"
                    + "  return null;\n"
                    + "}\n",
                StandardCharsets.UTF_8);
            DealConfig.DealConfigParseResult configResult =
                DealConfig.load(projectDir);
            Path outputRoot = projectDir.resolve("out");
            CompilationOrchestrator orchestrator =
                new CompilationOrchestrator(projectDir.resolve("main.deal"),
                    outputRoot, false, false, false, Backend.JS,
                    configResult.config(), List.of(projectDir),
                    Path.of("").toAbsolutePath().normalize());
            boolean ok = orchestrator.compile();
            check(ok, "JS orchestrator compile succeeds: "
                + orchestrator.diagnostics());
            check(Files.isRegularFile(outputRoot.resolve("main.js")),
                "the entry artifact is emitted");
            check(Files.isRegularFile(
                    outputRoot.resolve("deal/runtime.js")),
                "deal/runtime.js is deployed to the output");
            for (String module : List.of("console", "string", "table",
                    "json", "math", "time")) {
                check(Files.isRegularFile(outputRoot.resolve(
                        "std/" + module + ".js")),
                    "std/" + module + ".js is deployed to the output");
            }
        } finally {
            deleteRecursively(projectDir);
        }
    }

    /**
     * No-partial-artifact: a host-ABI import (a non-stdlib declaration
     * file) is rejected with E6000 and the rejected module writes no
     * artifact.
     */
    private static void testNoPartialArtifactOnRejection() throws Exception {
        Path projectDir = null;
        try {
            projectDir = Files.createTempDirectory("deal_js_reject_test_");
            Files.writeString(projectDir.resolve("deal.json"),
                "{\n  \"languageVersion\": \"1.2\",\n  \"backend\": \"js\"\n}\n",
                StandardCharsets.UTF_8);
            Files.writeString(projectDir.resolve("host.d.deal"),
                "export function hostFn(x: int): int;\n",
                StandardCharsets.UTF_8);
            Files.writeString(projectDir.resolve("main.deal"),
                "import * as host from \"./host\";\n\n"
                    + "export function main(): null {\n"
                    + "  let v: int = host.hostFn(1);\n"
                    + "  return null;\n"
                    + "}\n",
                StandardCharsets.UTF_8);
            DealConfig.DealConfigParseResult configResult =
                DealConfig.load(projectDir);
            Path outputRoot = projectDir.resolve("out");
            CompilationOrchestrator orchestrator =
                new CompilationOrchestrator(projectDir.resolve("main.deal"),
                    outputRoot, false, false, false, Backend.JS,
                    configResult.config(), List.of(projectDir),
                    Path.of("").toAbsolutePath().normalize());
            boolean ok = orchestrator.compile();
            check(!ok, "a host-ABI import fails the JS compilation");
            check(orchestrator.diagnostics().stream()
                    .anyMatch(d -> "E6000".equals(d.code())),
                "the rejection is E6000: " + orchestrator.diagnostics());
            check(orchestrator.diagnostics().stream()
                    .noneMatch(d -> "E6003".equals(d.code())),
                "the host-ABI rejection is E6000, not E6003: "
                    + orchestrator.diagnostics());
            check(!Files.exists(outputRoot.resolve("main.js")),
                "no entry artifact is written for the rejected module");
            check(!Files.exists(outputRoot.resolve("host.js")),
                "no artifact is written for the declaration file");
        } finally {
            deleteRecursively(projectDir);
        }
    }

    private static void check(boolean condition, String description) {
        if (condition) {
            passed++;
            System.out.println("  OK: " + description);
        } else {
            failures.add(description);
            System.out.println("  FAIL: " + description);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }
}
