package deal.module;

import deal.ast.*;
import deal.diagnostics.CompilerDiagnostic;
import deal.lexer.*;
import deal.parser.*;
import deal.types.Type;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Discovers stdlib modules by scanning {@code std/} for {@code .d.deal} files,
 * filtering to the 6 modules listed in the spec (§Standard library declarations):
 * {@code std/console}, {@code std/string}, {@code std/table}, {@code std/json},
 * {@code std/math}, {@code std/time}.
 *
 * <p>Modules present on disk but absent from the spec ({@code std/coroutine},
 * {@code std/io}) are excluded from discovery.
 *
 * <p>Results are cached for the lifetime of the JVM (test-friendly).
 *
 * <p>Usage:
 * <pre>{@code
 * Map<String, Map<String, Type>> exports = StdlibModuleResolver.stdlibExports();
 * }</pre>
 */
public final class StdlibModuleResolver {

    private StdlibModuleResolver() {}

    /**
     * The 6 spec-listed stdlib modules, in declaration order.
     * This is the authoritative filter — only these modules are valid stdlib.
     */
    public static final List<String> SPEC_STDLIB_MODULES = List.of(
        "std/console", "std/string", "std/table", "std/json", "std/math", "std/time"
    );

    /** Cached exports: module path → (export name → type). */
    private static volatile Map<String, Map<String, Type>> cachedExports;

    /**
     * Returns the export map for all spec-listed stdlib modules.
     * The result is cached after the first call.
     *
     * @return unmodifiable map from module path to export-name-to-type map
     * @throws RuntimeException if a spec-listed .d.deal file is missing or
     *         fails to parse (this indicates a broken installation)
     */
    public static Map<String, Map<String, Type>> stdlibExports() {
        if (cachedExports != null) return cachedExports;

        synchronized (StdlibModuleResolver.class) {
            if (cachedExports != null) return cachedExports;
            cachedExports = buildStdlibExports();
            return cachedExports;
        }
    }

    /**
     * Returns the 6 spec-listed module paths.
     */
    public static List<String> specStdlibModules() {
        return SPEC_STDLIB_MODULES;
    }

    /**
     * Returns true if the given module path is one of the 6 spec-listed stdlib modules.
     */
    public static boolean isSpecStdlibModule(String modulePath) {
        return SPEC_STDLIB_MODULES.contains(modulePath);
    }

    /**
     * Scans the filesystem for all .d.deal files under std/ and returns their
     * module paths (e.g. {@code "std/console"}). This includes non-spec modules
     * like {@code std/io} and {@code std/coroutine}.
     */
    public static List<String> discoverAllDeclFiles() {
        List<String> result = new ArrayList<>();
        Path stdDir = Path.of("std");
        if (!Files.isDirectory(stdDir)) return result;

        try (var stream = Files.list(stdDir)) {
            stream.filter(p -> p.toString().endsWith(".d.deal"))
                  .sorted()
                  .forEach(p -> {
                      String name = p.getFileName().toString();
                      // Remove .d.deal suffix
                      String base = name.substring(0, name.length() - ".d.deal".length());
                      result.add("std/" + base);
                  });
        } catch (IOException ignored) {
            // If we can't list, return empty
        }
        return result;
    }

    // =========================================================================
    // Internal: parse .d.deal files and extract exports
    // =========================================================================

    private static Map<String, Map<String, Type>> buildStdlibExports() {
        Map<String, Map<String, Type>> all = new LinkedHashMap<>();

        for (String modulePath : SPEC_STDLIB_MODULES) {
            // Path: std/console → std/console.d.deal
            String fileName = modulePath + ".d.deal";
            Path file = Path.of(fileName);

            if (!Files.exists(file)) {
                // Try without the std/ prefix? The module path already has it.
                throw new RuntimeException(
                    "Stdlib declaration file not found: " + fileName
                    + " (module: " + modulePath + ")");
            }

            try {
                Map<String, Type> exports = parseAndExtract(file, modulePath);
                all.put(modulePath, Collections.unmodifiableMap(exports));
            } catch (IOException e) {
                throw new RuntimeException(
                    "Failed to read stdlib declaration file: " + fileName, e);
            }
        }

        return Collections.unmodifiableMap(all);
    }

    /**
     * Parses a .d.deal file and extracts its export signatures.
     */
    private static Map<String, Type> parseAndExtract(Path file, String modulePath)
            throws IOException {
        String source = Files.readString(file);
        String filename = file.toString();
        boolean isDecl = true;

        LexResult lex = new Lexer(source, filename).tokenize();
        if (lex.hasErrors()) {
            throw new RuntimeException("Lex errors in " + filename + ": "
                + lex.diagnostics());
        }

        Parser parser = new Parser(lex.tokens(), filename);
        ParseResult parseResult = parser.parse();
        if (parseResult.hasErrors()) {
            throw new RuntimeException("Parse errors in " + filename + ": "
                + parseResult.diagnostics());
        }

        ExportExtractor extractor = new ExportExtractor(modulePath, isDecl);
        Map<String, Type> exports = extractor.extract(parseResult.program());

        if (!extractor.diagnostics().isEmpty()) {
            // Log but don't fail — E7001 on .d.deal files with executable
            // statements is a problem but not fatal for export extraction
            for (CompilerDiagnostic d : extractor.diagnostics()) {
                System.err.println("  " + d);
            }
        }

        return exports;
    }
}
