package deal.toolchain;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The one product-owned, JDK-only toolchain seam of the native backend
 * ({@code llvm-native-backend-integration} D3/D4, {@code NativeBuild} and
 * {@code ToolchainPreflight}; {@code llvm-native-host-abi-and-async} H1/H2/H6).
 *
 * <p>This class owns, once:</p>
 *
 * <ul>
 *   <li>the pinned flag list ({@link #PINNED_FLAGS}) and the accepted compiler
 *       window (LLVM major 14-16, {@link #MINIMUM_MAJOR} /
 *       {@link #MAXIMUM_MAJOR});</li>
 *   <li>the two build shapes: {@link NativeBuildRequest#productShape()} links
 *       {@code deal/native/main.c} as the process entry and writes the product
 *       to the caller's path, while {@link NativeBuildRequest#driverShape()}
 *       omits exactly that unit, writes the request's driver text to
 *       {@code <scratch>/driver.c} and links that unit as the process
 *       entry;</li>
 *   <li>the generated {@code <scratch>/hosts_registry.c} unit in both registry
 *       shapes ({@link HostsRegistry}): one entry per published host
 *       declaration header whose stem a supplied unit matches, or H2's
 *       count-zero sentinel;</li>
 *   <li>the fail-closed preflight, one named
 *       {@link NativeBuildException.Kind} per condition;</li>
 *   <li>the transactional product lifetime: the link writes
 *       {@code <productPath>.partial} beside the product, and one same-directory
 *       rename replaces the product only after a successful link. No failure
 *       path truncates, rewrites or deletes the product, and no build
 *       diagnostic names the staging path: the fixed suffix is internal and
 *       never appears in a diagnostic ({@link #withoutStagingPath}).</li>
 * </ul>
 *
 * <p>Nothing here publishes: the seam reads the live publication root and never
 * writes it (the stager remains its only writer), and the build scratch
 * {@code <java.io.tmpdir>/deal-native-<pid>-<nonce>/} holds generated
 * translation units only — never a product. The scratch directory and any
 * partial product are removed on success, failure and interruption.</p>
 *
 * <p>Package spelling: the native contract pages name this seam
 * {@code deal.native.NativeToolchain}, but {@code native} is a reserved Java
 * keyword, so no Java package can carry it. The seam lives in
 * {@code deal.toolchain} beside {@link NativeRuntime}; the class names, the
 * request and plan shapes and every consumer relationship are the contract,
 * not the package spelling.</p>
 */
public final class NativeToolchain {

    /** The emitted module's pinned target triple ({@code D4}). */
    public static final String TARGET_TRIPLE = "x86_64-pc-linux-gnu";

    /** The accepted compiler major window's lower bound ({@code D4}). */
    public static final int MINIMUM_MAJOR = 14;

    /** The accepted compiler major window's upper bound ({@code D4}). */
    public static final int MAXIMUM_MAJOR = 16;

    /**
     * The accepted compiler spellings, most specific first: the pinned
     * {@code clang}, then the versioned spellings of the same accepted window.
     */
    public static final List<String> COMPILER_CANDIDATES =
        List.of("clang", "clang-16", "clang-15", "clang-14");

    /**
     * The one pinned flag list ({@code D4}), verbatim and in order. Both build
     * shapes pass it through unchanged; the publication-root include path, the
     * optimization level, the translation units and the output path follow it.
     */
    public static final List<String> PINNED_FLAGS = List.of(
        "--target=" + TARGET_TRIPLE,
        "-std=c11",
        "-Wall", "-Werror", "-Wextra", "-pedantic",
        "-g",
        "-ffp-contract=off",
        "-fno-fast-math",
        "-fdebug-compilation-dir=.",
        "-Wl,--build-id=none");

    /** The build scratch directory's name prefix. */
    public static final String SCRATCH_PREFIX = "deal-native-";

    /** The generated driver translation unit's file name. */
    public static final String DRIVER_UNIT_NAME = "driver.c";

    /** The fixed pre-rename suffix of the product ({@code D4}). */
    public static final String PARTIAL_SUFFIX = ".partial";

    /** The bounded build deadline of one toolchain invocation. */
    public static final long BUILD_DEADLINE_SECONDS = 300L;

    /** The retained prefix of one captured stream. */
    private static final int STREAM_CAP_BYTES = 1 << 20;

    /** The detail bound of the first carried compiler diagnostic. */
    private static final int DIAGNOSTIC_CAP_CHARS = 2000;

    private NativeToolchain() {
        // Static seam only; no instances.
    }

    // =========================================================================
    // Planning: preflight plus the one argument vector
    // =========================================================================

    /**
     * Preflights the request and composes its one build plan.
     *
     * @throws NativeBuildException the one named preflight condition
     */
    public static NativeBuildPlan plan(NativeBuildRequest request)
            throws NativeBuildException {
        return plan(request, Map.of());
    }

    /**
     * Preflights the request with the given child-environment overrides and
     * composes its one build plan. The overrides are applied on top of this
     * process's environment (a {@code PATH} override therefore selects the
     * compiler the preflight resolves and the build invokes).
     */
    public static NativeBuildPlan plan(NativeBuildRequest request,
            Map<String, String> environment) throws NativeBuildException {
        Objects.requireNonNull(request, "request must not be null");
        Map<String, String> overrides = environment == null
            ? Map.of() : Map.copyOf(environment);
        Path root = request.publicationRoot();
        Path product = request.productPath();

        // 1. The caller-owned product never resolves inside the publication
        //    root: the stager remains its only writer (D4).
        if (product.startsWith(root)) {
            throw new NativeBuildException(
                NativeBuildException.Kind.PRODUCT_INSIDE_PUBLICATION,
                "'" + product + "' resolves inside the publication root '"
                    + root + "'");
        }

        // 2. The published input set: the one project module and the closed
        //    D6 runtime deployment set.
        if (!Files.isDirectory(root)) {
            throw new NativeBuildException(
                NativeBuildException.Kind.BUILD_INPUTS_INCOMPLETE,
                "the publication root '" + root + "' is not a directory");
        }
        Path module = uniquePublishedModule(root);
        for (String relativeName : NativeRuntime.FILES) {
            if (!Files.isRegularFile(root.resolve(relativeName))) {
                throw new NativeBuildException(
                    NativeBuildException.Kind.RUNTIME_MISSING, relativeName);
            }
        }

        // 3. The pinned compiler and its accepted major window.
        Path compiler = resolveCompiler(overrides);
        requireAcceptedMajor(compiler, overrides);

        // 4. The published IR module parses under the same compiler.
        requireParsableModule(compiler, module, overrides);

        // 5. The host declaration headers and the supplied host units.
        Map<String, HostsRegistry.Entry> declarations =
            publishedHostDeclarations(root);
        List<Path> hostUnits = request.hostUnits();
        requireMatchedHostUnits(hostUnits, declarations);

        // 6. The generated translation units and the exact argument vector.
        Path scratch = scratchDirectory();
        Path registryUnit = scratch.resolve(HostsRegistry.UNIT_NAME);
        Path driverUnit = request.driverShape()
            ? scratch.resolve(DRIVER_UNIT_NAME) : null;
        String registrySource = HostsRegistry.render(
            registryEntries(declarations, hostUnits));
        List<Path> runtimeUnits = runtimeTranslationUnits(root,
            request.productShape());
        List<String> command = argumentVector(compiler, root,
            request.optLevel(), module, runtimeUnits, hostUnits, driverUnit,
            registryUnit, partialPathOf(product));
        return new NativeBuildPlan(request, overrides, scratch, registryUnit,
            driverUnit, registrySource, request.driverSource(), module,
            runtimeUnits, hostUnits, command);
    }

    /** The one argument vector of a planned build ({@code D4}, verbatim order). */
    private static List<String> argumentVector(Path compiler, Path root,
            int optLevel, Path module, List<Path> runtimeUnits,
            List<Path> hostUnits, Path driverUnit, Path registryUnit,
            Path partial) {
        List<String> command = new ArrayList<>();
        command.add(compiler.toString());
        command.addAll(PINNED_FLAGS);
        command.add("-I" + root);
        command.add("-O" + optLevel);
        command.add(module.toString());
        for (Path unit : runtimeUnits) {
            command.add(unit.toString());
        }
        for (Path unit : hostUnits) {
            command.add(unit.toString());
        }
        if (driverUnit != null) {
            command.add(driverUnit.toString());
        }
        command.add(registryUnit.toString());
        command.add("-o");
        command.add(partial.toString());
        return List.copyOf(command);
    }

    // =========================================================================
    // Building: the transaction
    // =========================================================================

    /** Plans and builds one request. */
    public static NativeBuildResult build(NativeBuildRequest request)
            throws NativeBuildException {
        return build(plan(request));
    }

    /** Plans and builds one request with child-environment overrides. */
    public static NativeBuildResult build(NativeBuildRequest request,
            Map<String, String> environment) throws NativeBuildException {
        return build(plan(request, environment));
    }

    /**
     * Materializes one plan and links its product.
     *
     * <p>The generated units are written into the build scratch, the pinned
     * argument vector runs verbatim, and only a successful link renames
     * {@code <productPath>.partial} onto {@code productPath} (one
     * same-directory rename). The scratch directory and a failed build's
     * partial file are removed on every path; the product is never truncated,
     * rewritten or deleted by a failure, and an earlier product stays
     * byte-identical, executable and runnable.</p>
     *
     * @return the product path and the exact argument vector that produced it
     * @throws NativeBuildException the named link or tool failure
     */
    public static NativeBuildResult build(NativeBuildPlan plan)
            throws NativeBuildException {
        Objects.requireNonNull(plan, "plan must not be null");
        Path product = plan.productPath();
        Path partial = plan.partialPath();
        try {
            Files.createDirectories(plan.scratchDirectory());
            if (plan.driverUnit() != null) {
                Files.writeString(plan.driverUnit(), plan.driverSource(),
                    StandardCharsets.UTF_8);
            }
            Files.writeString(plan.registryUnit(), plan.registrySource(),
                StandardCharsets.UTF_8);
            Files.createDirectories(product.getParent());
            // A stale partial from an interrupted earlier build is this
            // build's own file: never the product. A staging path that
            // cannot be cleared is reported at the product path, never at
            // the fixed suffix (D4).
            clearStagingPath(partial, product);
            Observation observation = runTool(plan.command(),
                plan.environment(), null);
            if (observation.exitCode() != 0) {
                throw new NativeBuildException(
                    NativeBuildException.Kind.LINK_FAILED,
                    withoutStagingPath(firstDiagnostic(observation), partial,
                        product));
            }
            if (!Files.isRegularFile(partial)) {
                throw new NativeBuildException(
                    NativeBuildException.Kind.LINK_FAILED,
                    "the toolchain produced no output");
            }
            moveIntoPlace(partial, product);
            return new NativeBuildResult(product, plan.command(),
                plan.registrySource(), plan.driverSource());
        } catch (IOException failure) {
            throw new NativeBuildException(
                NativeBuildException.Kind.IO_FAILURE,
                withoutStagingPath(failure.toString(), partial, product));
        } finally {
            // One cleanup owner for both the owned scratch and this build's
            // partial: a renamed product is already gone from the partial
            // path, so only a failed or interrupted build leaves one.
            deleteQuietly(partial);
            deleteTreeQuietly(plan.scratchDirectory());
        }
    }

    /** The fixed {@code <productPath>.partial} path of one product. */
    public static Path partialPathOf(Path productPath) {
        Path name = productPath.toAbsolutePath().normalize().getFileName();
        return productPath.toAbsolutePath().normalize()
            .resolveSibling(name + PARTIAL_SUFFIX);
    }

    /**
     * Clears this build's pre-rename staging path, reporting a path the
     * filesystem refuses at the product path it stands for (D4).
     */
    private static void clearStagingPath(Path partial, Path product)
            throws NativeBuildException {
        try {
            Files.deleteIfExists(partial);
        } catch (IOException failure) {
            throw new NativeBuildException(
                NativeBuildException.Kind.IO_FAILURE,
                "cannot clear the staging path of '" + product + "': "
                    + (Files.isDirectory(partial)
                        ? "a non-empty directory occupies it"
                        : reasonOf(failure, partial, product)));
        }
    }

    /** One same-directory rename; the product is replaced exactly once. */
    private static void moveIntoPlace(Path partial, Path product)
            throws NativeBuildException {
        try {
            Files.move(partial, product, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new NativeBuildException(
                NativeBuildException.Kind.IO_FAILURE,
                "the product's parent directory cannot be replaced atomically: "
                    + withoutStagingPath(unsupported.getMessage(), partial,
                        product));
        } catch (IOException failure) {
            throw new NativeBuildException(
                NativeBuildException.Kind.IO_FAILURE,
                "cannot replace '" + product + "': "
                    + reasonOf(failure, partial, product));
        }
    }

    /**
     * One build detail text with the seam's pre-rename staging path rendered
     * as the caller's product path ({@code D4}).
     *
     * <p>The fixed {@code .partial} suffix is an internal pre-rename detail: it
     * names no file the caller created, it is reused on repeated builds, and it
     * never appears in a diagnostic. A tool whose own message names the staging
     * path (the linker refusing its output file, a rename or unlink fault) is
     * therefore reported at the product path it was replacing, and any residual
     * spelling of the suffix is dropped. A product the caller itself named with
     * that suffix keeps its own name.</p>
     */
    static String withoutStagingPath(String text, Path partial, Path product) {
        if (text == null) {
            return "";
        }
        String sanitized = text;
        if (partial != null && product != null) {
            sanitized = sanitized.replace(partial.toString(),
                product.toString());
            String stagingName = partial.getFileName().toString();
            sanitized = sanitized.replace(stagingName,
                product.getFileName().toString());
            if (product.getFileName().toString().endsWith(PARTIAL_SUFFIX)) {
                return sanitized;
            }
        }
        return sanitized.replace(PARTIAL_SUFFIX, "");
    }

    /**
     * The reason of one filesystem fault, without its staging-path framing:
     * {@code FileSystemException}'s own reason where the platform states one,
     * otherwise the sanitized rendering of the failure.
     */
    private static String reasonOf(IOException failure, Path partial,
            Path product) {
        if (failure instanceof java.nio.file.FileSystemException filesystem) {
            String reason = filesystem.getReason();
            if (reason != null && !reason.isBlank()) {
                return withoutStagingPath(reason, partial, product);
            }
        }
        return withoutStagingPath(failure.toString(), partial, product);
    }

    // =========================================================================
    // Preflight: the pinned compiler
    // =========================================================================

    /** Resolves the pinned compiler through the effective {@code PATH}. */
    private static Path resolveCompiler(Map<String, String> environment)
            throws NativeBuildException {
        String pathValue = environment.containsKey("PATH")
            ? environment.get("PATH") : System.getenv("PATH");
        if (pathValue != null) {
            for (String candidate : COMPILER_CANDIDATES) {
                for (String directory : pathValue.split(
                        java.util.regex.Pattern.quote(
                            File.pathSeparator))) {
                    if (directory.isEmpty()) {
                        continue;
                    }
                    Path executable = Path.of(directory, candidate);
                    if (Files.isRegularFile(executable)
                            && Files.isExecutable(executable)) {
                        return executable.toAbsolutePath().normalize();
                    }
                }
            }
        }
        throw new NativeBuildException(
            NativeBuildException.Kind.TOOLCHAIN_MISSING,
            "none of " + String.join(", ", COMPILER_CANDIDATES)
                + " resolves on PATH");
    }

    /** Requires a compiler whose major is inside the accepted window. */
    private static void requireAcceptedMajor(Path compiler,
            Map<String, String> environment) throws NativeBuildException {
        Observation version = runTool(List.of(compiler.toString(),
            "--version"), environment, null);
        if (version.exitCode() != 0) {
            throw new NativeBuildException(
                NativeBuildException.Kind.TOOLCHAIN_UNVERSIONED,
                "'" + compiler + " --version' exited " + version.exitCode());
        }
        int major = versionMajor(version.stdout());
        if (major < 0) {
            throw new NativeBuildException(
                NativeBuildException.Kind.TOOLCHAIN_UNVERSIONED,
                "'" + compiler + " --version' states no version major ("
                    + firstLine(version.stdout()) + ")");
        }
        if (major < MINIMUM_MAJOR || major > MAXIMUM_MAJOR) {
            throw new NativeBuildException(
                NativeBuildException.Kind.TOOLCHAIN_UNSUPPORTED,
                "'" + compiler + "' states major " + major
                    + ", outside the accepted " + MINIMUM_MAJOR + "-"
                    + MAXIMUM_MAJOR + " window");
        }
    }

    /**
     * The version major of one {@code clang --version} text, or {@code -1} when
     * the text states none.
     */
    static int versionMajor(String versionText) {
        if (versionText == null) {
            return -1;
        }
        String[] tokens = versionText.trim().split("\\s+");
        for (int index = 0; index + 1 < tokens.length; index += 1) {
            if (!tokens[index].equals("version")) {
                continue;
            }
            String number = tokens[index + 1];
            int end = 0;
            while (end < number.length()
                    && Character.isDigit(number.charAt(end))) {
                end += 1;
            }
            return end == 0 ? -1 : Integer.parseInt(number.substring(0, end));
        }
        return -1;
    }

    /**
     * Requires the published module to parse under the pinned compiler. The
     * probe assembles the module and discards the object through
     * {@code -o /dev/null}, so the pinned toolchain's own typed-pointer IR
     * parser decides and the preflight creates no file at all
     * ({@code ToolchainPreflight}: temporary probes are removed; otherwise the
     * preflight has no side effects).
     */
    private static void requireParsableModule(Path compiler, Path module,
            Map<String, String> environment) throws NativeBuildException {
        Observation probe = runTool(List.of(compiler.toString(),
            "--target=" + TARGET_TRIPLE, "-x", "ir", "-c",
            module.toString(), "-o", "/dev/null"), environment, null);
        if (probe.exitCode() != 0) {
            throw new NativeBuildException(
                NativeBuildException.Kind.IR_UNPARSABLE,
                module.getFileName() + ": " + firstDiagnostic(probe));
        }
    }

    // =========================================================================
    // Preflight: the published input set
    // =========================================================================

    /** The one published project module ({@code <entry module path>.ll}). */
    private static Path uniquePublishedModule(Path root)
            throws NativeBuildException {
        List<Path> modules = publishedFiles(root, ".ll");
        if (modules.isEmpty()) {
            throw new NativeBuildException(
                NativeBuildException.Kind.BUILD_INPUTS_INCOMPLETE,
                "no published LLVM module under '" + root + "'");
        }
        if (modules.size() > 1) {
            throw new NativeBuildException(
                NativeBuildException.Kind.BUILD_INPUTS_INCOMPLETE,
                "more than one published LLVM module under '" + root + "'");
        }
        return modules.get(0);
    }

    /**
     * The published files of one suffix outside {@code deal/native/}, in
     * deterministic publication-relative path order.
     */
    private static List<Path> publishedFiles(Path root, String suffix)
            throws NativeBuildException {
        Path runtimeDirectory = root.resolve(NativeRuntime.RUNTIME_DIRECTORY);
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(suffix))
                .filter(path -> !path.startsWith(runtimeDirectory))
                .filter(path -> !root.relativize(path).toString()
                    .startsWith(NativeRuntime.RUNTIME_DIRECTORY))
                .forEach(files::add);
        } catch (IOException failure) {
            throw new NativeBuildException(
                NativeBuildException.Kind.IO_FAILURE, failure.toString());
        }
        files.sort(Comparator.comparing(path -> relativePath(root, path)));
        return List.copyOf(files);
    }

    /**
     * The published host declaration headers keyed by stem, in deterministic
     * publication-relative path order. Two headers that share one stem are the
     * named ambiguity failure: a supplied unit's stem must identify exactly one
     * imported host declaration module.
     */
    private static Map<String, HostsRegistry.Entry> publishedHostDeclarations(
            Path root) throws NativeBuildException {
        Map<String, HostsRegistry.Entry> declarations = new LinkedHashMap<>();
        for (Path header : publishedFiles(root, ".h")) {
            String headerPath = relativePath(root, header);
            String fileName = header.getFileName().toString();
            String stem = stemOf(fileName);
            String specifier = headerPath.substring(0,
                headerPath.length() - ".h".length());
            HostsRegistry.Entry previous = declarations.put(stem,
                new HostsRegistry.Entry(specifier, headerPath, stem));
            if (previous != null) {
                throw new NativeBuildException(
                    NativeBuildException.Kind.HOST_DECLARATION_AMBIGUOUS,
                    "'" + previous.headerPath() + "' and '" + headerPath
                        + "' share the stem '" + stem + "'");
            }
        }
        return declarations;
    }

    // =========================================================================
    // Preflight: the host unit stems
    // =========================================================================

    /**
     * Requires every supplied host unit's stem to match exactly one published
     * host declaration header and every stem to be supplied at most once.
     */
    private static void requireMatchedHostUnits(List<Path> hostUnits,
            Map<String, HostsRegistry.Entry> declarations)
            throws NativeBuildException {
        Map<String, Path> byStem = new LinkedHashMap<>();
        for (Path unit : hostUnits) {
            String stem = stemOf(unit.getFileName().toString());
            Path previous = byStem.put(stem, unit);
            if (previous != null) {
                throw new NativeBuildException(
                    NativeBuildException.Kind.HOST_UNIT_DUPLICATE,
                    "two units share the stem '" + stem + "'");
            }
            if (!declarations.containsKey(stem)) {
                throw new NativeBuildException(
                    NativeBuildException.Kind.HOST_UNIT_UNMATCHED,
                    "'" + unit + "' matches no imported host declaration"
                        + " module");
            }
        }
    }

    /**
     * The registry entries of one build: one per published host declaration
     * header whose stem a supplied unit matches, in the headers'
     * deterministic order. A declared and imported host module with no
     * supplied unit keeps no entry (H1) and therefore fails at
     * initialization, never at linkage.
     */
    private static List<HostsRegistry.Entry> registryEntries(
            Map<String, HostsRegistry.Entry> declarations,
            List<Path> hostUnits) {
        java.util.Set<String> supplied = new java.util.LinkedHashSet<>();
        for (Path unit : hostUnits) {
            supplied.add(stemOf(unit.getFileName().toString()));
        }
        List<HostsRegistry.Entry> entries = new ArrayList<>();
        for (HostsRegistry.Entry declaration : declarations.values()) {
            if (supplied.contains(declaration.stem())) {
                entries.add(declaration);
            }
        }
        return List.copyOf(entries);
    }

    /** The runtime translation units of one shape, in the closed D6 order. */
    private static List<Path> runtimeTranslationUnits(Path root,
            boolean productShape) {
        List<Path> units = new ArrayList<>();
        for (String relativeName : NativeRuntime.TRANSLATION_UNITS) {
            if (!productShape && NativeRuntime.isProcessEntry(relativeName)) {
                continue;
            }
            units.add(root.resolve(relativeName));
        }
        return List.copyOf(units);
    }

    /**
     * The build scratch directory's path
     * ({@code <java.io.tmpdir>/deal-native-<pid>-<nonce>/}). The plan allocates
     * the path only; {@link #build(NativeBuildPlan)} creates and removes it.
     */
    public static Path scratchDirectory() {
        String temporary = System.getProperty("java.io.tmpdir");
        Path base = Path.of(temporary == null ? "." : temporary)
            .toAbsolutePath().normalize();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        return base.resolve(SCRATCH_PREFIX
            + ProcessHandle.current().pid() + "-" + nonce);
    }

    /** The file stem of one file name: its text before the last dot. */
    static String stemOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    private static String relativePath(Path root, Path file) {
        return root.relativize(file).toString().replace(File.separatorChar, '/');
    }

    // =========================================================================
    // The bounded toolchain invocation
    // =========================================================================

    /** One finished child observation. */
    record Observation(int exitCode, String stdout, String stderr) {

        /** The first non-empty stderr line, or the exit status. */
        String firstDiagnostic() {
            return NativeToolchain.firstDiagnostic(this);
        }
    }

    /** Runs one tool with the request's environment overrides, bounded. */
    private static Observation runTool(List<String> command,
            Map<String, String> environment, Path workingDirectory)
            throws NativeBuildException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        builder.environment().putAll(environment);
        Process process;
        try {
            process = builder.start();
        } catch (IOException failure) {
            throw new NativeBuildException(
                NativeBuildException.Kind.TOOL_FAILURE,
                "cannot start '" + command.get(0) + "': " + failure);
        }
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread stdoutDrain = drainInto(process.getInputStream(), stdout);
        Thread stderrDrain = drainInto(process.getErrorStream(), stderr);
        boolean finished;
        try {
            finished = process.waitFor(BUILD_DEADLINE_SECONDS,
                TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            joinQuietly(stdoutDrain);
            joinQuietly(stderrDrain);
            Thread.currentThread().interrupt();
            throw new NativeBuildException(
                NativeBuildException.Kind.TOOL_FAILURE,
                "interrupted while waiting for '" + command.get(0) + "'");
        }
        if (!finished) {
            process.destroyForcibly();
            joinQuietly(stdoutDrain);
            joinQuietly(stderrDrain);
            throw new NativeBuildException(
                NativeBuildException.Kind.TOOL_FAILURE,
                "'" + command.get(0) + "' exceeded the "
                    + BUILD_DEADLINE_SECONDS + " s build deadline");
        }
        joinQuietly(stdoutDrain);
        joinQuietly(stderrDrain);
        return new Observation(process.exitValue(), stdout.toString(),
            stderr.toString());
    }

    /** Drains one child stream concurrently, retaining its capped prefix. */
    private static Thread drainInto(InputStream stream, StringBuilder sink) {
        Thread drain = new Thread(() -> {
            byte[] buffer = new byte[8192];
            int retained = 0;
            try (InputStream source = stream) {
                int read;
                while ((read = source.read(buffer)) >= 0) {
                    if (retained >= STREAM_CAP_BYTES) {
                        continue;
                    }
                    int take = Math.min(read, STREAM_CAP_BYTES - retained);
                    sink.append(new String(buffer, 0, take,
                        StandardCharsets.UTF_8));
                    retained += take;
                }
            } catch (IOException ignored) {
                // The child's stream ended abnormally; the exit status and
                // the captured prefix remain the observation.
            }
        }, "deal-native-drain");
        drain.setDaemon(true);
        drain.start();
        return drain;
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(30));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** The first non-empty diagnostic line of one observation. */
    private static String firstDiagnostic(Observation observation) {
        String diagnostic = firstLine(observation.stderr());
        if (diagnostic.isEmpty()) {
            diagnostic = "exited " + observation.exitCode();
        }
        return diagnostic.length() <= DIAGNOSTIC_CAP_CHARS
            ? diagnostic
            : diagnostic.substring(0, DIAGNOSTIC_CAP_CHARS) + "...";
    }

    /** The first non-blank line of one text, stripped of surrounding space. */
    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                return line.trim();
            }
        }
        return "";
    }

    // =========================================================================
    // Filesystem helpers
    // =========================================================================

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The cleanup path never masks the build's own outcome.
        }
    }

    /** Removes an owned build scratch tree; the product never lives there. */
    private static void deleteTreeQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder())
                .toList();
            for (Path path : paths) {
                deleteQuietly(path);
            }
        } catch (IOException ignored) {
            // See deleteQuietly.
        }
    }
}
