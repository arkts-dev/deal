package deal;

import deal.module.CompilationOrchestrator;
import deal.module.DealConfig;
import deal.lexer.Diagnostic;

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
 * deal compile <entry.deal> [--output <dir>] [--verbose]
 * }</pre>
 *
 * <p>Options:
 * <ul>
 *   <li>{@code compile <entry.deal>} — compile a DEAL project (required)</li>
 *   <li>{@code --output <dir>} / {@code -o <dir>} — output directory (default: ./build/lua)</li>
 *   <li>{@code --verbose} / {@code -v} — verbose output with per-module timing</li>
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
        boolean verbose = false;

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
                case "--verbose", "-v" -> verbose = true;
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

        // Try to load deal.json from the entry file's directory
        Path projectDir = entryFile.getParent();
        DealConfig config = null;
        try {
            config = DealConfig.load(projectDir);
        } catch (IllegalArgumentException e) {
            System.err.println("deal: " + e.getMessage());
            return 1;
        }

        // Determine output directory
        if (outputDir == null) {
            if (config != null && config.output() != null) {
                outputDir = projectDir.resolve(config.output()).normalize();
            } else {
                outputDir = Path.of("build/lua").toAbsolutePath().normalize();
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

        // Determine stdlib directory
        Path stdlibDir = null;
        // Try project-local std/ first
        Path localStd = projectDir.resolve("std");
        if (Files.isDirectory(localStd)) {
            stdlibDir = localStd;
        }
        // Also try relative to current working dir
        Path cwdStd = Path.of("std");
        if (stdlibDir == null && Files.isDirectory(cwdStd)) {
            stdlibDir = cwdStd.toAbsolutePath();
        }

        if (verbose) {
            System.out.println("Entry: " + entryFile);
            System.out.println("Output: " + outputDir);
            System.out.println("Module roots: " + moduleRoots);
            System.out.println("Stdlib dir: " + (stdlibDir != null ? stdlibDir : "none"));
        }

        // Run compilation
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entryFile, outputDir, verbose, config, moduleRoots, stdlibDir);

        boolean success = orchestrator.compile();

        if (!success) {
            return 1;
        }

        return 0;
    }

    private static void printUsage() {
        System.err.println("Usage: deal compile <entry.deal> [--output <dir>] [--verbose]");
        System.err.println();
        System.err.println("Options:");
        System.err.println("  --output, -o <dir>   Output directory (default: ./build/lua)");
        System.err.println("  --verbose, -v        Verbose output with per-module timing");
    }
}
