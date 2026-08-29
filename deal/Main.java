package deal;

import deal.codegen.Backend;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticFormatter;
import deal.diagnostics.DiagnosticStructuredOutput;
import deal.module.CompilationOrchestrator;
import deal.project.CliOverrides;
import deal.project.ProjectContext;
import deal.project.ProjectLocator;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * CLI entry point for the DEAL compiler.
 *
 * <p>Usage:
 * <pre>{@code
 * deal compile <entry.deal> [--output <dir>] [--backend <lua|luajit|jvm>] [--verbose] [--dump-ir] [--source-map] [--diagnostics-json <path>]
 * }</pre>
 *
 * <p>Options:
 * <ul>
 *   <li>{@code compile <entry.deal>} — compile a DEAL project (required)</li>
 *   <li>{@code --output <dir>} / {@code -o <dir>} — output directory
 *       (CWD-relative; overrides the manifest {@code output}; the default
 *       is {@code <manifestDirectory>/build/lua} or {@code build/jvm}
 *       per the effective backend)</li>
 *   <li>{@code --backend <name>} — code-generation backend: the CLI
 *       aliases {@code lua}/{@code luajit} (default) or {@code jvm}.
 *       A {@code deal.json} {@code "backend"} field
 *       ({@code "luajit"} | {@code "jvm"}) is used when the flag is
 *       absent.</li>
 *   <li>{@code --verbose} / {@code -v} — verbose output with per-module timing</li>
 *   <li>{@code --dump-ir} — produce IR dump files at {@code <outputDir>/<module-path>.ir.txt}</li>
 *   <li>{@code --source-map} — produce source map sidecar files ({@code .deal.map.json})</li>
 *   <li>{@code --diagnostics-json <path>} — write the structured diagnostics
 *       document (version 1) to {@code <path>} for manifest-configuration
 *       failures and for every compilation (successful or failed); a
 *       write failure is a deterministic I/O diagnostic on stderr with
 *       exit 1</li>
 * </ul>
 *
 * <p><b>Exact-v1.2 project location (ISSUE-0269 migration, design
 * source {@code strict-project-context-resolution-identity} D1/D7,
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D10/D11).</b> The
 * CLI consumes {@link ProjectLocator#locate(String, CliOverrides)}: one
 * ancestor {@code deal.json} with {@code languageVersion: "1.2"} governs
 * the graph, the strict manifest is read/decoded/parsed before any
 * override is consulted, roots/output/externals/stdlib surface come from
 * the published immutable {@link ProjectContext}, and no implicit
 * entry-directory root or stdlib heuristic exists here. A manifest
 * failure is one E2010 printed through the canonical formatter (with the
 * structured document honored when {@code --diagnostics-json} is set);
 * a malformed entry or malformed CLI override is a
 * {@code CliDiagnostic} (exit 1); a post-validation write failure is a
 * deterministic compiler I/O diagnostic (exit 1), never E2010 and never
 * a raw exception. Output directories are created only in the write
 * phase.</p>
 */
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
                        System.err.println("deal: --backend requires a backend name (lua|luajit|jvm)");
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

        // Exact-v1.2 location (D1): entry validation, ancestor-manifest
        // discovery, strict UTF-8 read/decode, strict parse, override
        // validation, root/externals conversion, effective backend and
        // output, the pinned stdlib surface, and the deployment identity.
        // The CLI overrides are the raw strings — the locator owns their
        // validation (a valid alias lua|luajit|jvm overrides the manifest
        // backend; the trimmed CLI output overrides the manifest output;
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
                + (context.stdlibSurfacePath() != null
                    ? context.stdlibSurfacePath() : "none"));
            if (dumpIr) {
                System.out.println("IR dump: enabled");
            }
            if (sourceMap) {
                System.out.println("Source maps: enabled");
            }
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
            context, entryFile, verbose, dumpIr, dumpIr || sourceMap,
            sourceMap, diagnosticsJsonPath, invocation);

        boolean success;
        try {
            success = orchestrator.compile();
        } catch (IOException e) {
            // Post-validation write failure (D3/D11): a deterministic
            // compiler I/O diagnostic with exit 1 — never E2010, never a
            // raw path exception. The output directory is created only in
            // the write phase; a permission/device failure there surfaces
            // here.
            System.err.println("deal: cannot write output to '"
                + outputDir + "': " + e.getMessage());
            return 1;
        }

        if (!success) {
            return 1;
        }

        return 0;
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
        System.err.println("Usage: deal compile <entry.deal> [--output <dir>] [--backend <lua|luajit|jvm>] [--verbose] [--dump-ir] [--source-map] [--diagnostics-json <path>]");
        System.err.println();
        System.err.println("Options:");
        System.err.println("  --output, -o <dir>   Output directory (CWD-relative; default: build/lua or build/jvm per the effective backend)");
        System.err.println("  --backend <name>     Code-generation backend: lua/luajit (default) or jvm");
        System.err.println("  --verbose, -v        Verbose output with per-module timing");
        System.err.println("  --dump-ir            Produce IR dump files at <outputDir>/<module-path>.ir.txt");
        System.err.println("  --source-map         Produce source map sidecar files (.deal.map.json)");
        System.err.println("  --diagnostics-json <path>  Write the structured diagnostics document (version 1)");
    }
}
