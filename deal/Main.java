package deal;

import deal.codegen.Backend;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticFormatter;
import deal.diagnostics.DiagnosticStructuredOutput;
import deal.distribution.DistributionHome;
import deal.module.CompilationOrchestrator;
import deal.project.CliOverrides;
import deal.project.ProjectContext;
import deal.project.ProjectLocator;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.toolchain.NativeBuildException;
import deal.toolchain.NativeBuildPlan;
import deal.toolchain.NativeBuildRequest;
import deal.toolchain.NativeBuildResult;
import deal.toolchain.NativeToolchain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class Main {

    private Main() {}

    public static void main(String[] args) {
        try {
            int exitCode = run(args);
            System.exit(exitCode);
        } catch (Exception e) {
            System.err.println("deal: internal error: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(2);
        }
    }

    public static int run(String[] args) throws IOException {
        if (args.length == 0) {
            printUsage();
            return 1;
        }

        String command = args[0];
        if (command.equals("native")) {
            return runNative(Arrays.copyOfRange(args, 1, args.length));
        }
        if (!command.equals("compile")) {
            System.err.println("deal: unknown command '" + command + "'");
            printUsage();
            return 1;
        }

        // Parse options
        String[] remaining = Arrays.copyOfRange(args, 1, args.length);
        String entryPath = null;
        String outputOverride = null;
        String backendName = null;
        boolean verbose = false;
        boolean dumpIr = false;
        boolean sourceMap = false;
        Path diagnosticsJsonPath = null;

        int i = 0;
        while (i < remaining.length) {
            String arg = remaining[i];
            switch (arg) {
                case "--output", "-o" -> {
                    if (i + 1 >= remaining.length) {
                        System.err.println("deal: --output requires a directory argument");
                        return 1;
                    }
                    outputOverride = remaining[++i];
                }
                case "--backend" -> {
                    if (i + 1 >= remaining.length) {
                        System.err.println("deal: --backend requires a backend name (lua|luajit|jvm|js|llvm)");
                        return 1;
                    }
                    backendName = remaining[++i];
                }
                case "--diagnostics-json" -> {
                    if (i + 1 >= remaining.length) {
                        System.err.println("deal: --diagnostics-json requires a path argument");
                        return 1;
                    }
                    diagnosticsJsonPath = Path.of(remaining[++i]);
                }
                case "--verbose", "-v" -> verbose = true;
                case "--dump-ir" -> dumpIr = true;
                case "--source-map" -> sourceMap = true;
                default -> {
                    if (arg.startsWith("-")) {
                        System.err.println("deal: unknown option '" + arg + "'");
                        return 1;
                    }
                    if (entryPath != null) {
                        System.err.println("deal: multiple entry files specified");
                        return 1;
                    }
                    entryPath = arg;
                }
            }
            i++;
        }

        if (entryPath == null) {
            System.err.println("deal: missing entry file");
            printUsage();
            return 1;
        }

        // Exact-v1.5 location (D1): entry validation, ancestor-manifest
        // discovery, strict UTF-8 read/decode, strict parse, override
        // validation, root/externals conversion, effective backend and
        // output, the pinned stdlib surface, and the deployment identity.
        // The CLI overrides are the raw strings — the locator owns their
        // validation (a valid alias lua|luajit|jvm|js|llvm overrides the
        // manifest backend; the trimmed CLI output overrides the manifest
        // output;
        // an invalid override is a CliDiagnostic and publishes no
        // context; an override can never bypass a malformed manifest).
        ProjectLocator.LocateResult located = ProjectLocator.locate(entryPath,
            new CliOverrides(backendName, outputOverride));

        if (located.e2010() != null) {
            CompilerDiagnostic e2010 = located.e2010();
            System.err.println(DiagnosticFormatter.format(e2010));
            return writeDiagnosticsJson(List.of(e2010), diagnosticsJsonPath);
        }
        if (located.cliDiagnostic() != null) {
            System.err.println(located.cliDiagnostic().message());
            return 1;
        }

        ProjectContext context = located.context();
        Backend backend = Backend.fromCliName(context.backend()).orElseThrow();
        Path entryFile = Path.of(entryPath).toAbsolutePath().normalize();
        Path outputDir = Path.of(context.outputPath().absoluteNormalizedPath());

        if (verbose) {
            System.out.println("Entry: " + entryFile);
            System.out.println("Backend: " + backend.cliName());
            System.out.println("Output: " + outputDir);
            System.out.println("Module roots: " + context.configuredModuleRoots().stream()
                .map(r -> r.configuredText() + " -> " + r.absoluteNormalizedPath())
                .toList());
            System.out.println("Stdlib surface: "
                + describeStdlibSurface(context));
            if (dumpIr) {
                System.out.println("IR dump: enabled");
            }
            // The v1.5 default debugging artifacts: source comments and
            // per-source map sidecars are emitted by every successful
            // compile, and the accepted --source-map spelling selects
            // nothing.
            System.out.println("Source maps: enabled");
        }

        // Release-owned invocation resolution (A2/F1/F7): every compile
        // carries exactly one PUBLIC_BUILD invocation whose profile
        // derives from ReleaseConfiguration.CURRENT_RELEASE_STATE (the
        // single release-owned selection point) and whose derived
        // release-state hash is recorded on the invocation. The CLI gains
        // no profile or purpose option — source and CLI cannot select
        // profile, purpose, or the hash.
        CompilerInvocation invocation = CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());

        // Run compilation. The context is the single configuration
        // authority: the backend, the output root (created only in the
        // orchestrator's write phase), the module roots, the externals
        // declarations, the stdlib surface, and the deployment identity.
        // The orchestrator writes the structured diagnostics document for
        // every compilation — successful or failed — when
        // --diagnostics-json is set; manifest-configuration failures are
        // written by this CLI directly above.
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            context, entryFile, verbose, dumpIr,
            sourceMap, sourceMap, diagnosticsJsonPath, invocation);

        boolean success;
        try {
            success = orchestrator.compile();
        } catch (IOException e) {
            // I/O failure escaping the compile (D3/D11): a deterministic
            // compiler I/O diagnostic with exit 1 — never E2010, never a
            // raw path exception. Write-phase failures surface inside the
            // orchestrator as the pinned publish diagnostic
            // (whole-project-artifact-publication D4); this catch covers
            // the remaining I/O escapes (e.g. an unreadable source).
            System.err.println("deal: cannot write output to '"
                + outputDir + "': " + e.getMessage());
            return 1;
        }

        if (!success) {
            return 1;
        }

        return 0;
    }

    // =========================================================================
    // deal native build|run (llvm-native-backend-integration D5)
    // =========================================================================

    /**
     * The native workflow verb's two operations (D5): {@code build} resolves
     * the same project context as {@code compile}, compiles with
     * {@link Backend#LLVM} through the existing transactional publication into
     * the publication root, then links the published set through
     * {@link NativeToolchain} in the product build shape into
     * {@code <productDir>/native/<entry-stem>}; {@code run} builds and then
     * executes that product with inherited stdio, returning its exit status.
     *
     * <p>The publication root is the manifest/CLI {@code --output} result
     * (default {@code <manifestDirectory>/build/llvm}); the product root is the
     * caller's {@code --product} result (default
     * {@code <manifestDirectory>/build/native}). A plain {@code deal compile}
     * still executes no tool.</p>
     */
    private static int runNative(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("deal: native requires an operation (build|run)");
            printNativeUsage();
            return 1;
        }
        String operation = args[0];
        if (!operation.equals("build") && !operation.equals("run")) {
            System.err.println("deal: unknown native operation '" + operation
                + "'");
            printNativeUsage();
            return 1;
        }

        String entryPath = null;
        String outputOverride = null;
        String productOverride = null;
        int optLevel = 2;
        boolean verbose = false;
        List<Path> hostUnits = new ArrayList<>();

        int i = 1;
        while (i < args.length) {
            String arg = args[i];
            switch (arg) {
                case "--output", "-o" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("deal: --output requires a directory"
                            + " argument");
                        return 1;
                    }
                    outputOverride = args[++i];
                }
                case "--product" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("deal: --product requires a directory"
                            + " argument");
                        return 1;
                    }
                    productOverride = args[++i];
                }
                case "--opt-level" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("deal: --opt-level requires 0 or 2");
                        return 1;
                    }
                    String level = args[++i];
                    if (!level.equals("0") && !level.equals("2")) {
                        System.err.println("deal: --opt-level accepts only 0 or"
                            + " 2; got '" + level + "'");
                        return 1;
                    }
                    optLevel = Integer.parseInt(level);
                }
                case "--host" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("deal: --host requires a C source"
                            + " argument");
                        return 1;
                    }
                    hostUnits.add(Path.of(args[++i]));
                }
                case "--verbose", "-v" -> verbose = true;
                default -> {
                    if (arg.startsWith("-")) {
                        System.err.println("deal: unknown option '" + arg + "'");
                        return 1;
                    }
                    if (entryPath != null) {
                        System.err.println("deal: multiple entry files"
                            + " specified");
                        return 1;
                    }
                    entryPath = arg;
                }
            }
            i++;
        }

        if (entryPath == null) {
            System.err.println("deal: missing entry file");
            printNativeUsage();
            return 1;
        }

        // The same project resolution as `deal compile`, with the native
        // backend selected and its default output (D1/D5). The context stays
        // the single configuration authority; the CLI overrides are the raw
        // strings and the locator owns their validation.
        ProjectLocator.LocateResult located = ProjectLocator.locate(entryPath,
            new CliOverrides(Backend.LLVM.cliName(), outputOverride));
        if (located.e2010() != null) {
            System.err.println(DiagnosticFormatter.format(located.e2010()));
            return 1;
        }
        if (located.cliDiagnostic() != null) {
            System.err.println(located.cliDiagnostic().message());
            return 1;
        }

        ProjectContext context = located.context();
        Path entryFile = Path.of(entryPath).toAbsolutePath().normalize();
        Path outputDir = Path.of(context.outputPath().absoluteNormalizedPath());

        if (verbose) {
            System.out.println("Entry: " + entryFile);
            System.out.println("Backend: " + Backend.LLVM.cliName());
            System.out.println("Output: " + outputDir);
            System.out.println("Native product: " + productPath(context,
                entryFile, productOverride));
            System.out.println("Stdlib surface: "
                + describeStdlibSurface(context));
        }

        CompilerInvocation invocation = CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            context, entryFile, verbose, false, false, false, null, invocation);
        boolean compiled;
        try {
            compiled = orchestrator.compile();
        } catch (IOException e) {
            System.err.println("deal: cannot write output to '"
                + outputDir + "': " + e.getMessage());
            return 1;
        }
        if (!compiled) {
            return 1;
        }

        NativeBuildRequest request = new NativeBuildRequest(outputDir,
            productPath(context, entryFile, productOverride), optLevel,
            hostUnits, null);
        NativeBuildResult product;
        try {
            NativeBuildPlan plan = NativeToolchain.plan(request);
            if (verbose) {
                System.out.println("Clang: "
                    + String.join(" ", plan.command()));
            }
            product = NativeToolchain.build(plan);
        } catch (NativeBuildException failure) {
            System.err.println(failure.diagnostic());
            return 1;
        }

        if (verbose) {
            System.out.println("Product: " + product.productPath());
        }
        if (operation.equals("run")) {
            return runProduct(product.productPath());
        }
        return 0;
    }

    /**
     * The caller-owned product path of one native workflow request (D4/D5):
     * {@code <productRoot>/native/<entry-stem>}, where {@code <productRoot>} is
     * the {@code --product} directory or the manifest-relative default
     * {@code <manifestDirectory>/build/native}.
     */
    private static Path productPath(ProjectContext context, Path entryFile,
            String productOverride) {
        Path productRoot = productOverride == null
            ? Path.of(context.manifestDirectory()).toAbsolutePath()
                .normalize().resolve("build/native")
            : Path.of(productOverride).toAbsolutePath().normalize();
        return productRoot.resolve("native").resolve(entryStem(entryFile));
    }

    /** The entry module's file stem ({@code main.deal} -> {@code main}). */
    private static String entryStem(Path entryFile) {
        String name = entryFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    /**
     * Executes one built product with inherited stdio and returns its exit
     * status unchanged (D5).
     */
    private static int runProduct(Path product) {
        ProcessBuilder builder = new ProcessBuilder(product.toString())
            .inheritIO();
        try {
            return builder.start().waitFor();
        } catch (IOException failure) {
            System.err.println("deal: cannot run native product '" + product
                + "': " + failure.getMessage());
            return 1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            System.err.println("deal: interrupted while running native product '"
                + product + "'");
            return 1;
        }
    }

    /** Report the resolver's selected stdlib tier and path. */
    private static String describeStdlibSurface(ProjectContext context) {
        return DistributionHome.forManifestDirectory(context.manifestDirectory())
            .resolveStdlibSurface()
            .map(surface -> surface.tier() + " " + surface.pathText())
            .orElse("none");
    }

    /**
     * Writes the structured diagnostics document for the manifest
     * configuration failure (D8, parent D11): a write failure is a
     * deterministic compiler I/O diagnostic on stderr with exit 1 — no
     * raw path exception escapes.
     */
    private static int writeDiagnosticsJson(List<CompilerDiagnostic> diagnostics,
                                            Path diagnosticsJsonPath) {
        if (diagnosticsJsonPath == null) {
            return 1;
        }
        try {
            Files.writeString(diagnosticsJsonPath,
                DiagnosticStructuredOutput.toJson(diagnostics));
        } catch (IOException e) {
            System.err.println("deal: cannot write diagnostics JSON to '"
                + diagnosticsJsonPath + "': " + e.getMessage());
        }
        return 1;
    }

    private static void printUsage() {
        System.err.println("Usage: deal compile <entry.deal> [--output <dir>] [--backend <lua|luajit|jvm|js|llvm>] [--verbose] [--dump-ir] [--source-map] [--diagnostics-json <path>]");
        System.err.println("       deal native <build|run> <entry.deal> [--output <artifactsDir>] [--product <productDir>] [--opt-level 0|2] [--host <file.c>]... [--verbose]");
        System.err.println();
        System.err.println("Options:");
        System.err.println("  --output, -o <dir>   Output directory (CWD-relative; default: build/lua, build/jvm, build/js, or build/llvm per the effective backend)");
        System.err.println("  --backend <name>     Code-generation backend: lua/luajit (default), jvm, js, or llvm");
        System.err.println("  --verbose, -v        Verbose output with per-module timing");
        System.err.println("  --dump-ir            Produce IR dump files at <outputDir>/<module-path>.ir.txt");
        System.err.println("  --source-map         Accepted (source comments and .deal.map.json sidecars are the default)");
        System.err.println("  --diagnostics-json <path>  Write the structured diagnostics document (version 1)");
        System.err.println();
        System.err.println("The native verb compiles with the llvm backend and links the published set");
        System.err.println("through the pinned clang window (LLVM 14-16); `deal native run` executes the");
        System.err.println("product with inherited stdio and returns its exit status.");
    }

    private static void printNativeUsage() {
        System.err.println("Usage: deal native build <entry.deal> [--output <artifactsDir>] [--product <productDir>] [--opt-level 0|2] [--host <file.c>]... [--verbose]");
        System.err.println("       deal native run <entry.deal> [--output <artifactsDir>] [--product <productDir>] [--opt-level 0|2] [--host <file.c>]... [--verbose]");
        System.err.println();
        System.err.println("Options:");
        System.err.println("  --output, -o <dir>   Publication root (default: build/llvm under the manifest directory)");
        System.err.println("  --product <dir>      Product root (default: build/native under the manifest directory); the");
        System.err.println("                       product is <productRoot>/native/<entry-stem>");
        System.err.println("  --opt-level <level>  Optimization level: 0 or 2 (default: 2)");
        System.err.println("  --host <file.c>      A host implementation unit (repeatable)");
        System.err.println("  --verbose, -v        Print the exact clang argument vector");
    }
}
