package deal;

import deal.codegen.Backend;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticFormatter;
import deal.diagnostics.DiagnosticStructuredOutput;
import deal.module.CompilationOrchestrator;
import deal.module.DealConfig;
import deal.module.DealConfig.DealConfigParseResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CLI entry point for the DEAL compiler.
 *
 * <p>Usage:
 * <pre>{@code
 * deal compile <entry.deal> [--output <dir>] [--backend <lua|jvm|js>] [--verbose] [--dump-ir] [--source-map] [--diagnostics-json <path>]
 * }</pre>
 *
 * <p>Options:
 * <ul>
 *   <li>{@code compile <entry.deal>} — compile a DEAL project (required)</li>
 *   <li>{@code --output <dir>} / {@code -o <dir>} — output directory
 *       (default: ./build/lua, ./build/jvm with {@code --backend jvm},
 *       or ./build/js with {@code --backend js})</li>
 *   <li>{@code --backend <name>} — code-generation backend, {@code lua}/{@code luajit}
 *       (default), {@code jvm} (ISSUE-0091), or {@code js}. A {@code deal.json}
 *       {@code "backend"} field is used when the flag is absent.</li>
 *   <li>{@code --verbose} / {@code -v} — verbose output with per-module timing</li>
 *   <li>{@code --dump-ir} — produce IR dump files at {@code <outputDir>/<module-path>.ir.txt}</li>
 *   <li>{@code --source-map} — produce source map sidecar files ({@code .deal.map.json})</li>
 *   <li>{@code --diagnostics-json <path>} — write the structured diagnostics
 *       document (version 1) to {@code <path>} for manifest-configuration
 *       failures (the compilation path arrives with the orchestrator
 *       migration); a write failure is a deterministic I/O diagnostic on
 *       stderr with exit 1</li>
 * </ul>
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
        Path outputDir = null;
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
                    outputDir = Path.of(remaining[++i]).toAbsolutePath();
                }
                case "--backend" -> {
                    if (i + 1 >= remaining.length) {
                        System.err.println("deal: --backend requires a backend name (lua|jvm|js)");
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

        Path entryFile = Path.of(entryPath).toAbsolutePath().normalize();
        if (!Files.exists(entryFile)) {
            System.err.println("deal: entry file not found: " + entryFile);
            return 1;
        }

        // Try to load deal.json from the entry file's directory. An absent
        // manifest keeps today's null-config default path; invalid content
        // is exactly one ranged E2012 printed through the canonical
        // formatter with exit 1 (D10). Filesystem read failures still
        // throw IOException (explicit exclusion until ISSUE-0154).
        Path projectDir = entryFile.getParent();
        DealConfig config;
        DealConfigParseResult configResult = DealConfig.load(projectDir);
        config = configResult.config();
        if (!configResult.diagnostics().isEmpty()) {
            for (CompilerDiagnostic diagnostic : configResult.diagnostics()) {
                System.err.println(DiagnosticFormatter.format(diagnostic));
            }
            if (diagnosticsJsonPath != null) {
                try {
                    Files.writeString(diagnosticsJsonPath,
                        DiagnosticStructuredOutput.toJson(
                            configResult.diagnostics()));
                } catch (IOException e) {
                    // Deterministic I/O diagnostic: no raw path exception
                    // escapes (D8, parent D11).
                    System.err.println("deal: cannot write diagnostics JSON to '"
                        + diagnosticsJsonPath + "': " + e.getMessage());
                    return 1;
                }
            }
            return 1;
        }

        // Resolve the backend: CLI flag wins, then deal.json, then LuaJIT
        // (the default — the CLI and every pre-ISSUE-0091 path select it).
        Backend backend;
        if (backendName != null) {
            backend = Backend.fromCliName(backendName).orElse(null);
            if (backend == null) {
                System.err.println("deal: unknown backend '" + backendName
                    + "'. Supported backends: lua, luajit, jvm, js");
                return 1;
            }
        } else if (config != null && config.backend() != null) {
            // DealConfig already validated the manifest value.
            backend = Backend.fromCliName(config.backend()).orElseThrow();
        } else {
            backend = Backend.LUAJIT;
        }

        // Determine output directory
        if (outputDir == null) {
            if (config != null && config.output() != null) {
                outputDir = projectDir.resolve(config.output()).normalize();
            } else {
                String defaultDir = switch (backend) {
                    case JVM -> "build/jvm";
                    case JS -> "build/js";
                    case LUAJIT -> "build/lua";
                };
                outputDir = Path.of(defaultDir).toAbsolutePath().normalize();
            }
        }

        // Determine module roots
        List<Path> moduleRoots = new ArrayList<>();
        if (config != null && config.moduleRoots() != null) {
            for (String root : config.moduleRoots()) {
                moduleRoots.add(projectDir.resolve(root).normalize());
            }
        }
        // Always include the project directory
        if (!moduleRoots.contains(projectDir)) {
            moduleRoots.add(projectDir);
        }

        // Determine stdlib directory (the parent of std/ — Orchestrator prepends "std/" itself)
        Path stdlibDir = null;
        // Try project-local std/ first
        Path localStd = projectDir.resolve("std");
        if (Files.isDirectory(localStd)) {
            stdlibDir = projectDir;
        }
        // Also try relative to current working dir
        if (stdlibDir == null && Files.isDirectory(Path.of("std"))) {
            stdlibDir = Path.of("").toAbsolutePath();
        }

        if (verbose) {
            System.out.println("Entry: " + entryFile);
            System.out.println("Backend: " + backend.cliName());
            System.out.println("Output: " + outputDir);
            System.out.println("Module roots: " + moduleRoots);
            System.out.println("Stdlib dir: " + (stdlibDir != null ? stdlibDir : "none"));
            if (dumpIr) {
                System.out.println("IR dump: enabled");
            }
            if (sourceMap) {
                System.out.println("Source maps: enabled");
            }
        }

        // Run compilation
        // When --dump-ir is passed, also enable source maps since they
        // are part of IR hardening. When --source-map is explicitly passed,
        // enable source maps without enabling IR dumps.
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, verbose, dumpIr, dumpIr || sourceMap, sourceMap,
            backend, config, moduleRoots, stdlibDir);

        boolean success = orchestrator.compile();

        if (!success) {
            return 1;
        }

        return 0;
    }

    private static void printUsage() {
        System.err.println("Usage: deal compile <entry.deal> [--output <dir>] [--backend <lua|jvm|js>] [--verbose] [--dump-ir] [--source-map] [--diagnostics-json <path>]");
        System.err.println();
        System.err.println("Options:");
        System.err.println("  --output, -o <dir>   Output directory (default: ./build/lua, ./build/jvm with --backend jvm, or ./build/js with --backend js)");
        System.err.println("  --backend <name>     Code-generation backend: lua/luajit (default), jvm, or js");
        System.err.println("  --verbose, -v        Verbose output with per-module timing");
        System.err.println("  --dump-ir            Produce IR dump files at <outputDir>/<module-path>.ir.txt");
        System.err.println("  --source-map         Produce source map sidecar files (.deal.map.json)");
        System.err.println("  --diagnostics-json <path>  Write the structured diagnostics document (version 1)");
    }
}
