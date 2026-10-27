package deal.test;

import deal.Main;
import deal.codegen.Backend;
import deal.project.CliOverrides;
import deal.project.OutputConfigResolver;
import deal.project.ProjectLocator;
import deal.project.StrictManifestParser;
import deal.project.StrictManifestParser.StrictManifestParseResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The native backend's selection channels
 * ({@code llvm-native-backend-integration} D1, task "every backend
 * resolution/alias/default-output/diagnostic channel").
 *
 * <p>Each of the four named channel changes is exercised on its own and then
 * end to end through {@link Main#run(String[])} — the public compile entry:
 * {@code Backend.fromCliName}, {@code ProjectLocator.CLI_BACKEND_ALIASES}
 * with its rejection text, {@code ProjectConfigValidator}'s manifest
 * acceptance, {@code OutputConfigResolver.selectBackend} with the
 * {@code BACKEND_LLVM}/{@code DEFAULT_OUTPUT_LLVM} pair — plus the
 * orchestrator's backend mapping, the usage text, the verbose
 * {@code Backend: llvm} line and the shared frontend diagnostics.</p>
 */
public final class LlvmBackendSelectionTest {

    private static final Path REPO = NativeCompiler.repository();

    private static final String APP_SOURCE = """
        export function main(): null {
          return null;
        }
        """;

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("=== LLVM Backend Selection Tests (ISSUE-0903 D1) ===\n");
        testBackendNameAuthority();
        testCliAliasAndRejectionText();
        testManifestAcceptance();
        testDefaultOutputChannel();
        testUsageText();
        testEndToEndCliCompile();
        testVerboseAndDumpIr();
        testFrontendErrorsAndUnknownBackends();
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== LLVM Backend Selection Tests Passed ===");
    }

    // =========================================================================
    // 1. Backend.fromCliName
    // =========================================================================

    private static void testBackendNameAuthority() {
        System.out.println("-- Backend.fromCliName accepts llvm --");
        checkEq(Backend.LLVM, Backend.fromCliName("llvm").orElse(null),
            "the canonical name maps to Backend.LLVM");
        checkEq(Backend.LLVM, Backend.fromCliName("LLVM").orElse(null),
            "the uppercase spelling maps to Backend.LLVM");
        checkEq(Backend.LLVM, Backend.fromCliName(" Llvm ").orElse(null),
            "the trimmed mixed-case spelling maps to Backend.LLVM");
        checkEq("llvm", Backend.LLVM.cliName(),
            "the canonical CLI name is llvm");
        checkEq(null, Backend.fromCliName("wasm").orElse(null),
            "an unknown name resolves to empty");
        checkEq(4, Backend.values().length,
            "the closed backend set is luajit, jvm, js and llvm");
        for (Backend backend : Backend.values()) {
            checkEq(backend, Backend.fromCliName(backend.cliName())
                    .orElse(null),
                "every backend's own CLI name round-trips: " + backend);
        }
    }

    // =========================================================================
    // 2. ProjectLocator aliases and rejection text
    // =========================================================================

    private static void testCliAliasAndRejectionText() throws IOException {
        System.out.println("-- the CLI alias set and its rejection text --");
        check(ProjectLocator.CLI_BACKEND_ALIASES.contains("llvm"),
            "llvm joins the single CLI alias set");
        checkEq(Set.of("lua", "luajit", "jvm", "js", "llvm"),
            ProjectLocator.CLI_BACKEND_ALIASES,
            "the alias set is exactly the four legacy aliases plus llvm");
        Path root = tempProject();
        try {
            ProjectLocator.LocateResult rejected = ProjectLocator.locate(
                root.resolve("src/main.deal").toString(),
                new CliOverrides("wasm", null));
            check(rejected.cliDiagnostic() != null,
                "an unknown alias is a CLI diagnostic");
            check(rejected.cliDiagnostic().message().contains(
                    "supported aliases: lua, luajit, jvm, js, llvm"),
                "the rejection text enumerates llvm: "
                    + rejected.cliDiagnostic().message());
            ProjectLocator.LocateResult accepted = ProjectLocator.locate(
                root.resolve("src/main.deal").toString(),
                new CliOverrides(" LLVM ", null));
            checkEq("llvm", accepted.context() == null ? null
                    : accepted.context().backend(),
                "the CLI alias llvm (trimmed, case-folded) selects the backend");
        } finally {
            deleteRecursively(root);
        }
    }

    // =========================================================================
    // 3. The manifest validator
    // =========================================================================

    private static void testManifestAcceptance() throws IOException {
        System.out.println("-- the manifest validator accepts llvm --");
        Path root = tempProject();
        try {
            writeManifest(root, "\"llvm\"");
            StrictManifestParseResult accepted = StrictManifestParser.parse(
                root.resolve("deal.json").toString(),
                Files.readString(root.resolve("deal.json"),
                    StandardCharsets.UTF_8));
            check(accepted.failure() == null,
                "the manifest backend llvm validates: " + accepted.failure());
            checkEq("llvm", accepted.manifest() == null ? null
                    : accepted.manifest().backend(),
                "the validated manifest carries the llvm backend");
            writeManifest(root, "\"wasm\"");
            StrictManifestParseResult rejected = StrictManifestParser.parse(
                root.resolve("deal.json").toString(),
                Files.readString(root.resolve("deal.json"),
                    StandardCharsets.UTF_8));
            check(rejected.failure() != null,
                "an unknown manifest backend is rejected");
            check(rejected.failure().message().contains(
                    "Supported backends: 'luajit', 'jvm', 'js', 'llvm'"),
                "the manifest rejection text enumerates llvm: "
                    + rejected.failure().message());
        } finally {
            deleteRecursively(root);
        }
    }

    // =========================================================================
    // 4. The default output channel
    // =========================================================================

    private static void testDefaultOutputChannel() {
        System.out.println("-- the default output channel selects build/llvm --");
        Path manifestDirectory = REPO.resolve("build/llvm-selection-defaults")
            .toAbsolutePath().normalize();
        OutputConfigResolver.Resolution manifestDefault =
            OutputConfigResolver.resolve("llvm", null, null, null,
                manifestDirectory, REPO);
        checkEq("llvm", manifestDefault.effectiveBackend(),
            "the effective backend of a manifest llvm project is llvm");
        checkEq("build/llvm", manifestDefault.output().decodedText(),
            "the manifest default output text is build/llvm");
        checkEq(manifestDirectory.resolve("build/llvm").toString(),
            manifestDefault.output().absoluteNormalizedPath(),
            "the manifest default output resolves under the manifest directory");
        OutputConfigResolver.Resolution cliDefault =
            OutputConfigResolver.resolve("luajit", null, "llvm", null,
                manifestDirectory, REPO);
        checkEq("llvm", cliDefault.effectiveBackend(),
            "the CLI alias overrides the manifest backend");
        checkEq("build/llvm", cliDefault.output().decodedText(),
            "the CLI alias also selects the llvm default output");
        OutputConfigResolver.Resolution explicit =
            OutputConfigResolver.resolve("llvm", null, null, "out/custom",
                manifestDirectory, REPO);
        checkEq("out/custom", explicit.output().decodedText(),
            "an explicit output still wins over the llvm default");
        for (String backend : List.of("luajit", "jvm", "js")) {
            check(!OutputConfigResolver.resolve(backend, null, null, null,
                    manifestDirectory, REPO).output().decodedText()
                    .equals("build/llvm"),
                "the " + backend + " default output is unchanged");
        }
    }

    // =========================================================================
    // 5. The usage text
    // =========================================================================

    private static void testUsageText() throws IOException {
        System.out.println("-- the usage text enumerates llvm --");
        Captured captured = capture(() -> Main.run(new String[0]));
        checkEq(1, captured.exitCode(), "an empty command line fails");
        check(captured.stderr().contains("[--backend <lua|luajit|jvm|js|llvm>]"),
            "the usage line enumerates llvm: " + captured.stderr());
        check(captured.stderr().contains("or build/llvm"),
            "the usage line names the llvm default output");
        check(captured.stderr().contains("js, or llvm"),
            "the backend option text enumerates llvm");
        Captured missingArgument = capture(() -> Main.run(
            new String[] {"compile", "main.deal", "--backend"}));
        check(missingArgument.stderr().contains(
                "(lua|luajit|jvm|js|llvm)"),
            "the missing-backend-argument text enumerates llvm: "
                + missingArgument.stderr());
    }

    // =========================================================================
    // 6. End to end through the public compile entry
    // =========================================================================

    private static void testEndToEndCliCompile() throws IOException {
        System.out.println("-- deal compile --backend llvm publishes the set --");
        Path root = tempProject();
        try {
            Captured cli = capture(() -> Main.run(new String[] {
                "compile", root.resolve("src/main.deal").toString(),
                "--backend", "llvm"}));
            checkEq(0, cli.exitCode(), "the CLI compile succeeds: "
                + cli.stdout() + cli.stderr());
            Path output = root.resolve("build/llvm");
            check(Files.isRegularFile(output.resolve("main.ll")),
                "the CLI compile publishes the module at the default output");
            check(Files.isRegularFile(
                    output.resolve("deal/native/deal_rt.h")),
                "the CLI compile publishes the runtime deployment set");
            check(!Files.exists(root.resolve("build/lua")),
                "no other backend's output is created");
            check(!Files.exists(output.resolve("main.lua")),
                "the llvm output carries no Lua artifact");
            // The manifest channel selects the same arm.
            writeManifest(root, "\"llvm\"");
            Path manifestOutput = root.resolve("build/llvm-manifest");
            Captured manifest = capture(() -> Main.run(new String[] {
                "compile", root.resolve("src/main.deal").toString(),
                "--output", manifestOutput.toString()}));
            checkEq(0, manifest.exitCode(),
                "a manifest llvm project compiles: " + manifest.stderr());
            check(Files.isRegularFile(manifestOutput.resolve("main.ll")),
                "the manifest-selected arm publishes the module");
            // The alias case and whitespace variants reach the same arm.
            for (String alias : List.of("llvm", "LLVM", "Llvm")) {
                Path aliasOutput = root.resolve("build/alias-" + alias);
                Captured aliasRun = capture(() -> Main.run(new String[] {
                    "compile", root.resolve("src/main.deal").toString(),
                    "--backend", alias, "--output", aliasOutput.toString()}));
                checkEq(0, aliasRun.exitCode(),
                    "the alias '" + alias + "' selects the llvm arm: "
                        + aliasRun.stderr());
                check(Files.isRegularFile(aliasOutput.resolve("main.ll")),
                    "the alias '" + alias + "' publishes the module");
            }
        } finally {
            deleteRecursively(root);
        }
    }

    // =========================================================================
    // 7. The verbose line and the preserved dump-ir
    // =========================================================================

    private static void testVerboseAndDumpIr() throws IOException {
        System.out.println("-- the verbose Backend line and --dump-ir --");
        Path root = tempProject();
        try {
            Captured verbose = capture(() -> Main.run(new String[] {
                "compile", root.resolve("src/main.deal").toString(),
                "--backend", "llvm", "--verbose", "--dump-ir",
                "--output", root.resolve("build/verbose").toString()}));
            checkEq(0, verbose.exitCode(), "the verbose compile succeeds: "
                + verbose.stderr());
            check(verbose.stdout().contains("Backend: llvm"),
                "the verbose line reports Backend: llvm: " + verbose.stdout());
            check(verbose.stdout().contains(
                    root.resolve("build/verbose").toString()),
                "the verbose line reports the resolved output");
            check(Files.isRegularFile(
                    root.resolve("build/verbose/main.ir.txt")),
                "--dump-ir still publishes the frontend IR dump");
            check(Files.isRegularFile(
                    root.resolve("build/verbose/main.ll")),
                "the module is published beside the dump");
        } finally {
            deleteRecursively(root);
        }
    }

    // =========================================================================
    // 8. Frontend diagnostics and unknown backends
    // =========================================================================

    private static void testFrontendErrorsAndUnknownBackends() throws IOException {
        System.out.println("-- frontend diagnostics and unknown backends --");
        Path root = tempProject();
        try {
            Files.writeString(root.resolve("src/main.deal"),
                "export function main(): null {\n  let x: number = ;\n"
                    + "  return null;\n}\n", StandardCharsets.UTF_8);
            Path output = root.resolve("build/llvm");
            Captured broken = capture(() -> Main.run(new String[] {
                "compile", root.resolve("src/main.deal").toString(),
                "--backend", "llvm", "--output", output.toString()}));
            checkEq(1, broken.exitCode(), "a frontend error fails the compile");
            check(broken.stderr().contains("E10"),
                "the shared frontend diagnostic is preserved: "
                    + broken.stderr());
            check(!broken.stderr().contains("E6005"),
                "no emitter rejection replaces the frontend diagnostic");
            check(!Files.exists(output),
                "a frontend error publishes nothing");
            Captured unknown = capture(() -> Main.run(new String[] {
                "compile", root.resolve("src/main.deal").toString(),
                "--backend", "wasm"}));
            checkEq(1, unknown.exitCode(), "an unknown backend fails");
            check(unknown.stderr().contains(
                    "supported aliases: lua, luajit, jvm, js, llvm"),
                "the CLI rejection text enumerates llvm: " + unknown.stderr());
        } finally {
            deleteRecursively(root);
        }
    }

    // =========================================================================
    // Fixtures and helpers
    // =========================================================================

    private static Path tempProject() {
        try {
            Path root = Files.createTempDirectory(
                REPO.resolve("build"), "llvm-selection-");
            Files.createDirectories(root.resolve("src"));
            Files.writeString(root.resolve("src/main.deal"), APP_SOURCE,
                StandardCharsets.UTF_8);
            writeManifest(root, null);
            return root;
        } catch (IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    private static void writeManifest(Path root, String backend) throws IOException {
        StringBuilder manifest = new StringBuilder(
            "{\"languageVersion\": \"1.5\", \"moduleRoots\": [\"src\"]");
        if (backend != null) {
            manifest.append(", \"backend\": ").append(backend);
        }
        manifest.append('}');
        Files.writeString(root.resolve("deal.json"), manifest.toString(),
            StandardCharsets.UTF_8);
    }

    /** One captured CLI invocation. */
    private record Captured(int exitCode, String stdout, String stderr) {
    }

    /** The one CLI call the capture wrapper invokes. */
    private interface CliCall {
        int run() throws IOException;
    }

    private static Captured capture(CliCall call) throws IOException {
        PrintStream stdout = System.out;
        PrintStream stderr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int exitCode = call.run();
            return new Captured(exitCode, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(stdout);
            System.setErr(stderr);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

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
}
