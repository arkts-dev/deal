package deal.test;

import deal.codegen.Backend;
import deal.module.CompilationOrchestrator;
import deal.module.DealConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * JavaScript end-to-end gate (ISSUE-0194, js-backend-conformance-e2e D3).
 *
 * <p>Runs the 15-sample roster under {@code test/e2e/js/*} through the
 * real production pipeline: each sample is a plain v1.2 project
 * ({@code main.deal} exporting non-async {@code main(): null}, optional
 * imported modules, a {@code deal.json} declaring {@code languageVersion}
 * {@code "1.2"} and {@code backend} {@code "js"}; no metadata comments —
 * the E1044 rule). For every sample the runner:
 *
 * <ol>
 *   <li>copies the sample into a fresh temp project directory (sample
 *       sources are read-only);</li>
 *   <li>loads the production {@code deal.json} manifest and resolves the
 *       selected backend from it — the manifest backend must be
 *       {@code "js"} ({@link Backend#JS});</li>
 *   <li>runs the production pipeline —
 *       {@link CompilationOrchestrator} with {@link Backend#JS}, the
 *       loaded config, the project root as the single module root, and
 *       the repository root as the stdlib directory — asserting compile
 *       success, zero diagnostics, and the emitted {@code .js} artifacts
 *       plus {@code deal/runtime.js} (and the deployed {@code std/*.js}
 *       for the stdlib sample);</li>
 *   <li>executes {@code node <output>/main.js} as a subprocess and
 *       asserts the registered stdout substring(s) and exit code (the
 *       runtime-error sample asserts {@code DEAL_ERROR_CODE: E8001} on
 *       stderr and exit 1);</li>
 *   <li>for the async sample, additionally requires the emitted module
 *       from a runner script and invokes the production host ABI
 *       {@code $rt.invokeAsyncExport(entry, "oracle", "null")},
 *       asserting the {@code { $ok: true, $value: null }} completion
 *       signal (a host-failure or DEAL-error signal is a hard failure,
 *       never a skip);</li>
 *   <li>deletes the temp project afterwards.</li>
 * </ol>
 *
 * <p>Expected outputs live in the {@link #SAMPLES} registry because the
 * sample sources carry no metadata (E1044). No sample asserts wall-clock
 * time: the std/time sample asserts a structural invariant only. The
 * gate fails hard when {@code node} is unavailable — the epic's
 * acceptance is 15 samples under the environment's Node.js.</p>
 */
public final class JsE2eTest {

    private JsE2eTest() { }

    /** One registered e2e sample and its assertions. */
    private record Sample(
        String name,
        List<String> expectedStdout,
        int expectedExitCode,
        String expectedStderr,
        String asyncExport,
        String asyncCompletion) {

        Sample(String name, List<String> expectedStdout, int expectedExitCode,
                String expectedStderr) {
            this(name, expectedStdout, expectedExitCode, expectedStderr,
                null, null);
        }

        Sample(String name, List<String> expectedStdout, int expectedExitCode,
                String expectedStderr, String asyncExport,
                String asyncCompletion) {
            this.name = name;
            this.expectedStdout = List.copyOf(expectedStdout);
            this.expectedExitCode = expectedExitCode;
            this.expectedStderr = expectedStderr;
            this.asyncExport = asyncExport;
            this.asyncCompletion = asyncCompletion;
        }
    }

    /**
     * The 15-sample roster (js-backend-conformance-e2e D3), with the
     * registered expectations.
     */
    private static final List<Sample> SAMPLES = List.of(
        new Sample("hello-literals",
            List.of("hello literals: int number boolean null string array class function table"),
            0, null),
        new Sample("int-arithmetic",
            List.of("int arithmetic ok: floored number remainder -5.0 % 2.0 == 1.0"),
            0, null),
        new Sample("control-flow",
            List.of("control flow ok"), 0, null),
        new Sample("for-of",
            List.of("for-of ok"), 0, null),
        new Sample("template-literals",
            List.of("template literals ok"), 0, null),
        new Sample("functions-closures",
            List.of("functions closures ok"), 0, null),
        new Sample("arrays",
            List.of("arrays ok"), 0, null),
        new Sample("classes-optionals",
            List.of("classes optionals ok"), 0, null),
        new Sample("nullable-flow",
            List.of("nullable flow ok"), 0, null),
        new Sample("tables",
            List.of("tables ok"), 0, null),
        new Sample("errors",
            List.of("errors ok"), 0, null),
        new Sample("errors-uncaught",
            List.of("about to fail"), 1, "DEAL_ERROR_CODE: E8001"),
        new Sample("async-await",
            List.of("async sample main ran"), 0, null,
            "oracle", "oracle completion: null"),
        new Sample("modules",
            List.of("modules ok: main invoked"), 0, null),
        new Sample("stdlib",
            List.of("console as a value line",
                "time structural invariant ok", "stdlib ok"), 0, null));

    private static final long SUBPROCESS_TIMEOUT_SECONDS = 120;

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    /**
     * The stdlib modules the orchestrator deploys (the six spec modules);
     * the stdlib sample asserts their presence in the output root.
     */
    private static final List<String> SPEC_STDLIB_MODULES = List.of(
        "console", "string", "table", "json", "math", "time");

    public static void main(String[] args) throws Exception {
        System.out.println("=== JS E2E Gate: " + SAMPLES.size()
            + " samples through the production pipeline ===");
        checkNodeAvailable();
        checkRosterShape();
        Path repoRoot = Path.of("").toAbsolutePath().normalize();
        for (Sample sample : SAMPLES) {
            runSample(sample, repoRoot);
        }
        System.out.println();
        System.out.println("=== JS E2E Summary ===");
        System.out.println("Samples: total " + SAMPLES.size() + ", passed "
            + passed + ", failed " + failures.size());
        for (String failure : failures) {
            System.out.println("  FAIL: " + failure);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    /** Fails the gate when {@code node} is unavailable (D3, hard). */
    private static void checkNodeAvailable() throws Exception {
        Process probe = new ProcessBuilder("node", "--version")
            .redirectErrorStream(true).start();
        if (!probe.waitFor(30, TimeUnit.SECONDS) || probe.exitValue() != 0) {
            System.err.println("FAIL: node is not available; the JS e2e gate"
                + " requires the environment's Node.js");
            System.exit(1);
        }
    }

    /**
     * Roster-shape guard: the on-disk sample directories and the
     * registry must name exactly the same sample set. A sample present
     * on disk but absent from the registry would silently not run; a
     * registered sample without a directory is a hard failure too.
     */
    private static void checkRosterShape() throws IOException {
        Path e2eRoot = Path.of("test", "e2e", "js").toAbsolutePath()
            .normalize();
        List<String> onDisk = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(e2eRoot, 1)) {
            walk.filter(Files::isDirectory)
                .filter(p -> !p.equals(e2eRoot))
                .forEach(p -> onDisk.add(p.getFileName().toString()));
        }
        List<String> registered = new ArrayList<>();
        for (Sample sample : SAMPLES) {
            registered.add(sample.name());
        }
        Collections.sort(onDisk);
        Collections.sort(registered);
        if (!onDisk.equals(registered)) {
            System.out.println("  FAIL: roster mismatch: on-disk " + onDisk
                + " vs registered " + registered);
            System.exit(1);
        }
    }

    /** Runs one sample end to end and records the outcome. */
    private static void runSample(Sample sample, Path repoRoot) throws Exception {
        Path sampleDir = Path.of("test", "e2e", "js", sample.name())
            .toAbsolutePath().normalize();
        Path projectDir = null;
        try {
            if (!Files.isDirectory(sampleDir)) {
                fail(sample, "sample directory missing: " + sampleDir);
                return;
            }

            // Step 1: copy the sample into a fresh temp project (the
            // sample sources stay read-only).
            projectDir = Files.createTempDirectory("deal_js_e2e_");
            copySampleInto(sampleDir, projectDir);

            // Step 2: the production manifest drives the backend.
            DealConfig.DealConfigParseResult configResult =
                DealConfig.load(projectDir);
            DealConfig config = configResult.config();
            if (config == null || !configResult.diagnostics().isEmpty()) {
                fail(sample, "deal.json did not load cleanly: "
                    + configResult.diagnostics());
                return;
            }
            if (!"1.2".equals(config.languageVersion())) {
                fail(sample, "deal.json languageVersion must be \"1.2\", got "
                    + config.languageVersion());
                return;
            }
            Backend backend = Backend.fromCliName(config.backend())
                .orElse(null);
            if (backend != Backend.JS) {
                fail(sample, "deal.json backend must be \"js\", got "
                    + config.backend());
                return;
            }

            // Step 3: the production pipeline (selected-entry gate,
            // manifest, module discovery, phase-4 JS codegen and the
            // runtime/stdlib deployment copies all run).
            Path entryFile = projectDir.resolve("main.deal");
            Path outputRoot = projectDir.resolve("build/js");
            CompilationOrchestrator orchestrator =
                new CompilationOrchestrator(entryFile, outputRoot, false,
                    false, false, backend, config, List.of(projectDir),
                    repoRoot);
            boolean compileOk;
            try {
                compileOk = orchestrator.compile();
            } catch (IOException e) {
                fail(sample, "orchestrator threw: " + e);
                return;
            }
            if (!compileOk) {
                fail(sample, "compile failed: "
                    + orchestrator.diagnostics());
                return;
            }
            if (!orchestrator.diagnostics().isEmpty()) {
                fail(sample, "compile succeeded with diagnostics: "
                    + orchestrator.diagnostics());
                return;
            }

            // Step 3a: artifact-presence assertions.
            Path mainArtifact = outputRoot.resolve("main.js");
            if (!Files.isRegularFile(mainArtifact)) {
                fail(sample, "missing emitted entry artifact: " + mainArtifact);
                return;
            }
            Path runtimeArtifact = outputRoot.resolve("deal/runtime.js");
            if (!Files.isRegularFile(runtimeArtifact)) {
                fail(sample, "missing deployed runtime: " + runtimeArtifact);
                return;
            }
            if ("stdlib".equals(sample.name())) {
                for (String module : SPEC_STDLIB_MODULES) {
                    Path stdArtifact = outputRoot.resolve(
                        "std/" + module + ".js");
                    if (!Files.isRegularFile(stdArtifact)) {
                        fail(sample, "missing deployed stdlib module: "
                            + stdArtifact);
                        return;
                    }
                }
            }
            if ("modules".equals(sample.name())) {
                Path libArtifact = outputRoot.resolve("lib.js");
                if (!Files.isRegularFile(libArtifact)) {
                    fail(sample, "missing imported-module artifact: "
                        + libArtifact);
                    return;
                }
            }

            // Step 4: execute the entry module under node.
            SubprocessResult entryRun = runNode(projectDir,
                List.of(mainArtifact.toString()));
            if (entryRun.exitCode != sample.expectedExitCode()) {
                fail(sample, "entry exit code mismatch: expected "
                    + sample.expectedExitCode() + ", got "
                    + entryRun.exitCode + "\nstdout: " + entryRun.stdout
                    + "\nstderr: " + entryRun.stderr);
                return;
            }
            for (String expected : sample.expectedStdout()) {
                if (!entryRun.stdout.contains(expected)) {
                    fail(sample, "entry stdout missing expected substring '"
                        + expected + "':\n" + entryRun.stdout);
                    return;
                }
            }
            if (sample.expectedStderr() != null
                    && !entryRun.stderr.contains(sample.expectedStderr())) {
                fail(sample, "entry stderr missing expected substring '"
                    + sample.expectedStderr() + "':\n" + entryRun.stderr);
                return;
            }

            // Step 5: the async sample's completion is asserted through
            // the production invoker $rt.invokeAsyncExport — the runner
            // requires the emitted entry and asserts the
            // { $ok: true, $value: null } signal (a host-failure or
            // DEAL-error signal is a hard failure). The invoker's main
            // invocation must also print the pinned main line exactly
            // once in the runner process (the D2 exactly-once contract).
            if (sample.asyncExport() != null) {
                Path runner = projectDir.resolve("async_export_runner.js");
                Files.writeString(runner, ASYNC_RUNNER_SOURCE,
                    StandardCharsets.UTF_8);
                SubprocessResult asyncRun = runNode(projectDir,
                    List.of(runner.toString(), mainArtifact.toString(),
                        sample.asyncExport()));
                if (asyncRun.exitCode != 0) {
                    fail(sample, "async-export invocation failed (exit "
                        + asyncRun.exitCode + "):\nstdout: "
                        + asyncRun.stdout + "\nstderr: " + asyncRun.stderr);
                    return;
                }
                if (!asyncRun.stdout.contains(sample.asyncCompletion())) {
                    fail(sample, "async-export completion mismatch: expected"
                        + " substring '" + sample.asyncCompletion()
                        + "':\n" + asyncRun.stdout);
                    return;
                }
                int mainRuns = countOccurrences(asyncRun.stdout,
                    "async sample main ran");
                if (mainRuns != 1) {
                    fail(sample, "the production invoker must run main "
                        + "exactly once in the runner process, got "
                        + mainRuns + ":\n" + asyncRun.stdout);
                    return;
                }
            }

            passed++;
            System.out.println("  [" + sample.name() + "] OK");
        } finally {
            // Step 6: temp projects are deleted.
            if (projectDir != null) {
                deleteRecursively(projectDir);
            }
        }
    }

    /**
     * The runner-side production async-export invocation (D5): the
     * runner requires the emitted entry artifact and the deployed
     * runtime and calls the production host ABI
     * {@code $rt.invokeAsyncExport(entry, "oracle", "null")}, asserting
     * the {@code { $ok: true, $value: null }} completion signal. Any
     * other signal — a host failure, a reified DEAL error, or an
     * unexpected throw from the invoker itself — is a hard failure.
     */
    private static final String ASYNC_RUNNER_SOURCE = String.join("\n",
        "\"use strict\";",
        "const path = require(\"path\");",
        "const $rt = require(path.join(path.dirname(process.argv[2]),",
        "  \"deal\", \"runtime\"));",
        "const m = require(process.argv[2]);",
        "const exportName = process.argv[3];",
        "$rt.invokeAsyncExport(m, exportName, \"null\").then(",
        "  (r) => {",
        "    if (r.$ok !== true || r.$value !== null) {",
        "      process.stderr.write(\"ASYNC_EXPORT_MISMATCH: \" + JSON.stringify(r) + \"\\n\");",
        "      process.exit(1);",
        "    }",
        "    process.stdout.write(\"oracle completion: \" + r.$value + \"\\n\");",
        "  },",
        "  (err) => {",
        "    process.stderr.write(\"INVOKER_THREW: \" + err + \"\\n\");",
        "    process.exit(1);",
        "  }",
        ");",
        "");

    /** One bounded subprocess run under node with captured streams. */
    private record SubprocessResult(int exitCode, String stdout,
                                    String stderr) { }

    private static SubprocessResult runNode(Path directory,
                                            List<String> command)
            throws Exception {
        List<String> full = new ArrayList<>();
        full.add("node");
        full.addAll(command);
        Process process = new ProcessBuilder(full)
            .directory(directory.toFile())
            .redirectErrorStream(false)
            .start();
        if (!process.waitFor(SUBPROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return new SubprocessResult(-1, "", "node subprocess timed out");
        }
        String stdout = readAll(process.getInputStream());
        String stderr = readAll(process.getErrorStream());
        return new SubprocessResult(process.exitValue(), stdout, stderr);
    }

    /** Counts non-overlapping occurrences of {@code needle} in
     * {@code haystack}. */
    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static String readAll(InputStream stream) throws IOException {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Copies every regular file of the sample directory (read-only). */
    private static void copySampleInto(Path sampleDir, Path projectDir)
            throws IOException {
        try (Stream<Path> walk = Files.walk(sampleDir)) {
            List<Path> files = walk
                .filter(Files::isRegularFile)
                .sorted(Comparator.comparing(Path::toString))
                .toList();
            for (Path file : files) {
                Path rel = sampleDir.relativize(file);
                Path dest = projectDir.resolve(rel);
                Files.createDirectories(dest.getParent());
                Files.copy(file, dest, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup; the temp root is reclaimed by
                    // the OS even when a deletion fails.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }

    private static void fail(Sample sample, String detail) {
        failures.add(sample.name() + ": " + detail);
        System.out.println("  [" + sample.name() + "] FAIL: " + detail);
    }
}
