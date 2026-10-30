package deal.test;

import deal.distribution.DistributionHome;
import deal.toolchain.NativeRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The pinned native toolchain and the published-runtime deployment path used by
 * the C11 runtime suites (ISSUE-0902).
 *
 * <p>Every driver these suites build compiles the <em>published</em> runtime
 * tree: {@link #deployDeploymentSet(Path)} resolves the closed ordered set of
 * {@link NativeRuntime#FILES} through
 * {@link DistributionHome#resolveRuntimeSource(String)} and writes it at the
 * pinned relative paths, exactly as the LLVM arm's staging step does, and the
 * suites then link against that tree rather than a hand-assembled directory. A
 * missing, renamed or mismatched deployment file therefore fails the build, not
 * only a byte comparison.</p>
 *
 * <p>{@link #flags(Path, int)} is the pinned toolchain flag list of
 * {@code llvm-native-backend-integration} D4 minus the seam paths this task does
 * not own yet (the emitted module and the scratch registry/driver units, which
 * the suites supply as committed fixtures). The accepted compiler window is
 * LLVM major 14-16 (D4): {@code clang} is preferred and a versioned
 * {@code clang-16}/{@code clang-15}/{@code clang-14} is accepted from the same
 * {@code PATH} so the pinned window, not one binary spelling, is the contract.
 * An absent compiler or an unsupported major is the named failure of the gate;
 * it is never a skip.</p>
 */
public final class NativeCompiler {

    /** The pinned target triple (D4), the production seam's own value. */
    public static final String TARGET_TRIPLE =
        deal.toolchain.NativeToolchain.TARGET_TRIPLE;

    /** The accepted clang/LLVM major window (D4), from the production seam. */
    public static final int MINIMUM_MAJOR =
        deal.toolchain.NativeToolchain.MINIMUM_MAJOR;
    public static final int MAXIMUM_MAJOR =
        deal.toolchain.NativeToolchain.MAXIMUM_MAJOR;

    /** The accepted compiler spellings, most specific first (D4). */
    private static final List<String> CANDIDATES =
        deal.toolchain.NativeToolchain.COMPILER_CANDIDATES;

    /** The repositories' published-runtime deployment root. */
    public static final String RUNTIME_PREFIX = NativeRuntime.RUNTIME_DIRECTORY;

    private NativeCompiler() {
        // Static helper.
    }

    /** One finished child process observation. */
    public record Result(int exitCode, String stdout, String stderr) {

        /** True when the child exited zero. */
        public boolean succeeded() {
            return exitCode == 0;
        }

        /** A bounded description of the observation for a failure message. */
        public String describe() {
            return "exit " + exitCode + ", stdout \"" + truncate(stdout)
                + "\", stderr \"" + truncate(stderr) + "\"";
        }

        private static String truncate(String text) {
            String single = text.replace("\n", "\\n");
            return single.length() <= 400 ? single : single.substring(0, 400) + "...";
        }
    }

    // =========================================================================
    // The compiler
    // =========================================================================

    /** The resolved pinned compiler: its path and its version major. */
    public record Compiler(String path, int major) { }

    private static Compiler compiler;

    /**
     * Resolves the pinned compiler once per JVM. A missing compiler or an
     * unsupported major fails with the named reason; the gate never skips.
     */
    public static synchronized Compiler compiler() {
        if (compiler != null) {
            return compiler;
        }
        List<String> rejected = new ArrayList<>();
        for (String candidate : CANDIDATES) {
            Path path = onPath(candidate);
            if (path == null) {
                continue;
            }
            Result version = run(List.of(path.toString(), "--version"), null);
            if (!version.succeeded()) {
                rejected.add(candidate + " (--version failed)");
                continue;
            }
            int major = parseMajor(version.stdout());
            if (major < MINIMUM_MAJOR || major > MAXIMUM_MAJOR) {
                rejected.add(candidate + " (major " + major + ")");
                continue;
            }
            compiler = new Compiler(path.toString(), major);
            return compiler;
        }
        throw new IllegalStateException("TOOL_MISSING: no clang in the pinned "
            + "major window " + MINIMUM_MAJOR + "-" + MAXIMUM_MAJOR
            + " is available on PATH"
            + (rejected.isEmpty() ? "" : " (rejected: " + rejected + ")"));
    }

    private static int parseMajor(String versionText) {
        // "Debian clang version 14.0.6" / "clang version 16.0.6 (...)"
        String[] tokens = versionText.trim().split("\\s+");
        for (int index = 0; index < tokens.length; index += 1) {
            if (tokens[index].equals("version") && index + 1 < tokens.length) {
                String number = tokens[index + 1];
                int end = 0;
                while (end < number.length() && Character.isDigit(number.charAt(end))) {
                    end += 1;
                }
                if (end == 0) {
                    return -1;
                }
                return Integer.parseInt(number.substring(0, end));
            }
        }
        return -1;
    }

    private static Path onPath(String tool) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String directory : path.split(java.io.File.pathSeparator)) {
            if (directory.isEmpty()) {
                continue;
            }
            Path candidate = Path.of(directory, tool);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    // =========================================================================
    // The pinned flag list
    // =========================================================================

    /**
     * The pinned D4 flag list for one build, taken verbatim from the production
     * seam's one authority ({@link deal.toolchain.NativeToolchain#PINNED_FLAGS})
     * so the suites and the seam can never drift: everything down to the
     * optimization level, with the published-root include path. The link-only
     * {@code -Wl,--build-id=none} is present exactly for a linking request.
     */
    public static List<String> flags(Path publishedRoot, int optimizationLevel,
                                     boolean link) {
        if (optimizationLevel != 0 && optimizationLevel != 2) {
            throw new IllegalArgumentException(
                "unsupported optimization level: " + optimizationLevel);
        }
        List<String> flags = new ArrayList<>(
            deal.toolchain.NativeToolchain.PINNED_FLAGS);
        if (!link) {
            flags.remove("-Wl,--build-id=none");
        }
        flags.add("-I" + publishedRoot.toAbsolutePath().normalize());
        flags.add("-O" + optimizationLevel);
        return flags;
    }

    // =========================================================================
    // The deployment path
    // =========================================================================

    /**
     * Stages the published runtime tree under {@code root} by resolving every
     * file of {@link NativeRuntime#FILES} through the production resolution
     * order and writing it at its pinned relative path.
     *
     * @return the number of deployment files written
     */
    public static int deployDeploymentSet(Path root) throws IOException {
        DistributionHome home = DistributionHome.forManifestDirectory(
            root.toAbsolutePath().normalize().toString());
        List<NativeRuntime.DeploymentFile> files = NativeRuntime.resolve(home);
        int written = 0;
        for (NativeRuntime.DeploymentFile file : files) {
            Path target = root.resolve(file.relativeName());
            Files.createDirectories(target.getParent());
            try (InputStream stream = file.source().open()) {
                Files.write(target, stream.readAllBytes());
            }
            written += 1;
        }
        return written;
    }

    /**
     * A fresh executable scratch directory inside the checkout's {@code build}
     * tree. Built products must be executable, and the harness's temporary
     * directory is not guaranteed to be an executable filesystem, so the suites
     * never execute a product from it. The caller removes the directory.
     */
    public static Path executableScratch(String purpose) throws IOException {
        Path parent = repository().resolve("build");
        Files.createDirectories(parent);
        return Files.createTempDirectory(parent, purpose + "-");
    }

    /** The repository root ({@code cwd} of every suite invocation). */
    public static Path repository() {
        return Path.of("").toAbsolutePath().normalize();
    }

    /** The committed native fixture directory. */
    public static Path fixtures() {
        return repository().resolve("test/native");
    }

    // =========================================================================
    // Builds
    // =========================================================================

    /** Compiles and links the named sources into {@code product}. */
    public static Result link(Path publishedRoot, int optimizationLevel,
                              List<Path> sources, Path product) {
        return link(publishedRoot, optimizationLevel, sources, List.of(), product);
    }

    /** Compiles and links the named sources with extra flags (fixture cases). */
    public static Result link(Path publishedRoot, int optimizationLevel,
                              List<Path> sources, List<String> extraFlags,
                              Path product) {
        List<String> command = new ArrayList<>();
        command.add(compiler().path());
        command.addAll(flags(publishedRoot, optimizationLevel, true));
        command.addAll(extraFlags);
        for (Path source : NativeRuntimeObjects.prepare(publishedRoot,
                optimizationLevel, sources, extraFlags)) {
            command.add(source.toString());
        }
        command.add("-o");
        command.add(product.toString());
        return run(command, null);
    }

    /** Production preflight/publication with reusable runtime preparation for emitter tests.
     * Dedicated toolchain tests call NativeToolchain.build directly (cold).
     */
    public static deal.toolchain.NativeBuildResult build(
            deal.toolchain.NativeBuildRequest request)
            throws deal.toolchain.NativeBuildException {
        var plan = deal.toolchain.NativeToolchain.plan(request);
        List<Path> objects = NativeRuntimeObjects.prepare(request.publicationRoot(),
            request.optLevel(), plan.runtimeUnits(), List.of());
        List<String> command = new ArrayList<>(plan.command());
        for (int index = 0; index < plan.runtimeUnits().size(); index++) {
            String source = plan.runtimeUnits().get(index).toString();
            String object = objects.get(index).toString();
            command.replaceAll(arg -> arg.equals(source) ? object : arg);
        }
        return deal.toolchain.NativeToolchain.build(new deal.toolchain.NativeBuildPlan(
            plan.request(), plan.environment(), plan.scratchDirectory(),
            plan.registryUnit(), plan.driverUnit(), plan.registrySource(),
            plan.driverSource(), plan.entryModule(), objects, plan.hostUnits(), command));
    }

    /** Compiles one source to an object (no link), returning the observation. */
    public static Result compileOnly(Path publishedRoot, int optimizationLevel,
                                     Path source, Path object) {
        return compileOnly(publishedRoot, optimizationLevel, source, List.of(),
            object);
    }

    /** Compiles one source to an object with extra flags (fixture cases). */
    public static Result compileOnly(Path publishedRoot, int optimizationLevel,
                                     Path source, List<String> extraFlags,
                                     Path object) {
        List<String> command = new ArrayList<>();
        command.add(compiler().path());
        command.addAll(flags(publishedRoot, optimizationLevel, false));
        command.addAll(extraFlags);
        command.add("-c");
        command.add(source.toString());
        command.add("-o");
        command.add(object.toString());
        return run(command, null);
    }

    /** Syntax-checks the named sources with the full warning set, no output. */
    public static Result syntaxOnly(Path publishedRoot, int optimizationLevel,
                                    List<Path> sources) {
        List<String> command = new ArrayList<>();
        command.add(compiler().path());
        command.addAll(flags(publishedRoot, optimizationLevel, false));
        command.add("-fsyntax-only");
        for (Path source : sources) {
            command.add(source.toString());
        }
        return run(command, null);
    }

    /** Every published runtime translation unit, in the pinned order. */
    public static List<Path> runtimeTranslationUnits(Path publishedRoot) {
        List<Path> units = new ArrayList<>();
        for (String relativeName : NativeRuntime.TRANSLATION_UNITS) {
            units.add(publishedRoot.resolve(relativeName));
        }
        return units;
    }

    /**
     * The product shape (D4): the module fixture, the registry unit, every
     * published runtime translation unit including {@code main.c}.
     */
    public static List<Path> productSources(Path publishedRoot,
                                            List<Path> fixtureSources) {
        List<Path> sources = new ArrayList<>(fixtureSources);
        sources.addAll(runtimeTranslationUnits(publishedRoot));
        return sources;
    }

    /**
     * The driver shape (D4): the same set without the process-entry unit, plus
     * the caller's driver translation unit.
     */
    public static List<Path> driverSources(Path publishedRoot,
                                           List<Path> fixtureSources,
                                           Path driverSource) {
        List<Path> sources = new ArrayList<>(fixtureSources);
        for (Path unit : runtimeTranslationUnits(publishedRoot)) {
            if (!unit.getFileName().toString().equals("main.c")) {
                sources.add(unit);
            }
        }
        sources.add(driverSource);
        return sources;
    }

    // =========================================================================
    // Execution
    // =========================================================================

    /** Runs the product with inherited stdio captured and the named environment. */
    public static Result execute(Path product, Path workingDirectory,
                                 java.util.Map<String, String> environment) {
        return run(List.of(product.toAbsolutePath().toString()),
            workingDirectory, environment);
    }

    /** Runs one command with a clean environment overlay (null = inherited). */
    public static Result run(List<String> command, Path workingDirectory) {
        return run(command, workingDirectory, null);
    }

    /**
     * Runs one command through the harness's bounded subprocess surface: the
     * capture, the cap and the deadline belong to {@link BoundedProcess}, so no
     * child this suite spawns can wedge the gate.
     */
    public static Result run(List<String> command, Path workingDirectory,
                             java.util.Map<String, String> environment) {
        try {
            BoundedProcess.BoundedResult result = BoundedProcess.runCaptured(
                command, workingDirectory,
                environment == null ? java.util.Map.of() : environment);
            if (result.stdoutTruncated() || result.stderrTruncated()) {
                throw new AssertionError("a bounded child exceeded the capture "
                    + "cap: " + String.join(" ", command));
            }
            return new Result(result.exitCode(), result.stdout(),
                result.stderr());
        } catch (IOException error) {
            throw new UncheckedIOException(
                "cannot run " + String.join(" ", command), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running "
                + String.join(" ", command), error);
        }
    }

    // =========================================================================
    // The supplementary sanitizer legs (physical memory safety)
    // =========================================================================

    /**
     * The AddressSanitizer and UndefinedBehaviorSanitizer flag list of every
     * supplementary physical-memory-safety leg. The published runtime and the
     * driver are compiled into one instrumented product at the caller's
     * optimization level, so an out-of-bounds access, a stale read into a
     * reclaimed arena slot the collector poisoned, or an undefined operation is a
     * reported finding instead of a silent success. {@code -fno-sanitize-recover}
     * turns every report into a nonzero exit.
     */
    public static final List<String> SANITIZER_FLAGS = List.of(
        "-fsanitize=address,undefined",
        "-fno-sanitize-recover=all",
        "-fno-omit-frame-pointer",
        "-g");

    /**
     * The environment of one sanctioned sanitizer run. Leak detection is off
     * because the runtime deliberately keeps process-lifetime tables (the
     * layout registry, the root-source table, the numeric locale) that are
     * reachable and never released; a finding aborts with the pinned status.
     */
    public static java.util.Map<String, String> sanitizerEnvironment() {
        return java.util.Map.of(
            "ASAN_OPTIONS", "detect_leaks=0:abort_on_error=1:exitcode=66",
            "UBSAN_OPTIONS", "halt_on_error=1:exitcode=66:print_stacktrace=1");
    }

    /** One sanitized observation and whether the child had to be relaunched. */
    public record SanitizedRun(Result result, boolean relaunched) { }

    /**
     * Runs one instrumented product, relaunching it with address-space
     * randomization disabled for that child only when the sanitizer runtime
     * could not map its shadow memory at all. A host whose randomization entropy
     * puts the mapping out of reach kills the process before its workload: that
     * startup failure is retried, while a genuine finding — a report on stderr or
     * a workload that reached its own assertions — is returned as it is and never
     * retried away.
     */
    public static SanitizedRun runSanitized(Path product, Path workingDirectory,
                                            java.util.Map<String, String> environment) {
        Result run = run(List.of(product.toAbsolutePath().toString()),
            workingDirectory, environment);
        if (!sanitizerStartupFailure(run)) {
            return new SanitizedRun(run, false);
        }
        List<String> withoutRandomization = disableAslrCommand(product);
        if (withoutRandomization == null) {
            return new SanitizedRun(run, false);
        }
        boolean relaunched = false;
        for (int attempt = 0; attempt < SANITIZER_RELAUNCH_ATTEMPTS; attempt += 1) {
            run = run(withoutRandomization, workingDirectory, environment);
            relaunched = true;
            if (!sanitizerStartupFailure(run)) {
                break;
            }
        }
        return new SanitizedRun(run, relaunched);
    }

    /** The bounded relaunches of one instrumented product on this host. */
    public static final int SANITIZER_RELAUNCH_ATTEMPTS = 3;

    /**
     * True when the process could not start the sanitizer runtime at all, as
     * opposed to a sanitizer finding about the code under test. A genuine report
     * names itself on stderr ({@code ERROR: AddressSanitizer} for a
     * use-after-poison or an out-of-bounds access, {@code WARNING:
     * ThreadSanitizer} for a data race, {@code runtime error:} for an undefined
     * operation), and a workload that reached its own assertions writes stdout;
     * neither is ever mistaken for a startup failure, so no finding is retried
     * away. The runtimes' own fatal startup lines — a shadow mapping or a memory
     * mapping out of reach of this host's address-space layout — are not findings.
     */
    public static boolean sanitizerStartupFailure(Result run) {
        if (run.exitCode() == 0) {
            return false;
        }
        String stderr = run.stderr();
        if (stderr.contains("ERROR: AddressSanitizer")
                || stderr.contains("WARNING: ThreadSanitizer")
                || stderr.contains("runtime error:")) {
            return false;
        }
        return run.stdout().isEmpty();
    }

    /**
     * The instrumented product relaunched with {@code ADDR_NO_RANDOMIZE} for that
     * child only (through {@code setarch -R}), or null when the host has no
     * {@code setarch}.
     */
    public static List<String> disableAslrCommand(Path product) {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String directory : path.split(java.io.File.pathSeparator)) {
                if (directory.isEmpty()) {
                    continue;
                }
                Path candidate = Path.of(directory, "setarch");
                if (Files.isRegularFile(candidate)
                        && Files.isExecutable(candidate)) {
                    return List.of(candidate.toString(), "-R",
                        product.toAbsolutePath().toString());
                }
            }
        }
        return null;
    }

    // =========================================================================
    // Small shared utilities
    // =========================================================================

    /** The files of one directory tree, relative and sorted, for set checks. */
    public static List<String> filesUnder(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                .map(path -> root.relativize(path).toString().replace('\\', '/'))
                .sorted(Comparator.naturalOrder())
                .toList();
        }
    }

    /** Asserts two byte arrays are equal, naming the first differing offset. */
    public static void requireSameBytes(byte[] expected, byte[] actual,
                                        String what) {
        if (Arrays.equals(expected, actual)) {
            return;
        }
        int limit = Math.min(expected.length, actual.length);
        int offset = 0;
        while (offset < limit && expected[offset] == actual[offset]) {
            offset += 1;
        }
        throw new AssertionError(what + ": bytes differ at offset " + offset
            + " (expected " + expected.length + " bytes, got " + actual.length
            + ")");
    }
}
