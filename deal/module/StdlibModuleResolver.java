package deal.module;

import deal.ast.*;
import deal.diagnostics.CompilerDiagnostic;
import deal.lexer.*;
import deal.parser.*;
import deal.types.Type;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class StdlibModuleResolver {

    private StdlibModuleResolver() {}

    /**
     * The 6 spec-listed stdlib modules, in declaration order.
     * This is the authoritative filter — only these modules are valid stdlib.
     */
    public static final List<String> SPEC_STDLIB_MODULES = List.of(
        "std/console", "std/string", "std/table", "std/json", "std/math", "std/time"
    );

    /**
     * The exported map of one surface resolution, cached per resolved
     * surface path text (absent surfaces cache as the {@code "absent"}
     * key). Synchronized so parallel conformance workers share one
     * build per surface.
     */
    private static final Map<String, Map<String, Map<String, Type>>> CACHE =
        new HashMap<>();

    /** The cache key for an absent surface (its map is always empty). */
    private static final String ABSENT_SURFACE_KEY = "<absent>";

    /**
     * Returns the export map for all spec-listed stdlib modules of the
     * pinned surface directory given by {@code surfacePath}, cached per
     * surface. A null surface path — or a surface that is not a
     * directory — is an absent surface and yields the empty map (the
     * authoritative missing-surface failure is E2003 at the import span
     * from {@link SourceModuleResolver}).
     *
     */
    public static Map<String, Map<String, Type>> stdlibExports(
            String surfacePath) {
        String key = surfaceKey(surfacePath);
        synchronized (StdlibModuleResolver.class) {
            Map<String, Map<String, Type>> cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
            Map<String, Map<String, Type>> built = buildStdlibExports(
                surfacePath);
            CACHE.put(key, built);
            return built;
        }
    }

    public static List<String> specStdlibModules() {
        return SPEC_STDLIB_MODULES;
    }

    public static boolean isSpecStdlibModule(String modulePath) {
        return SPEC_STDLIB_MODULES.contains(modulePath);
    }

    /**
     * Scans a surface directory for all .d.deal files and returns their
     * module paths (e.g. {@code "std/console"}). This includes non-spec
     * modules like {@code std/io} and {@code std/coroutine}. An absent
     * or unlistable surface yields an empty list.
     */
    public static List<String> discoverAllDeclFiles(String surfacePath) {
        List<String> result = new ArrayList<>();
        if (surfacePath == null) return result;
        Path stdDir = Path.of(surfacePath);
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
    // Surface resolution
    // =========================================================================

    /**
     * The per-surface cache key: the normalized surface path text, or
     * the pinned absent-surface key for a null/non-directory surface.
     */
    private static String surfaceKey(String surfacePath) {
        if (surfacePath == null) {
            return ABSENT_SURFACE_KEY;
        }
        Path surface = Path.of(surfacePath).toAbsolutePath().normalize();
        if (!Files.isDirectory(surface)) {
            return ABSENT_SURFACE_KEY;
        }
        return surface.toString();
    }

    // =========================================================================
    // Internal: parse .d.deal files under one surface and extract exports
    // =========================================================================

    private static Map<String, Map<String, Type>> buildStdlibExports(
            String surfacePath) {
        Map<String, Map<String, Type>> all = new LinkedHashMap<>();
        if (surfacePath == null) {
            return Collections.unmodifiableMap(all);
        }
        Path surface = Path.of(surfacePath).toAbsolutePath().normalize();
        if (!Files.isDirectory(surface)) {
            return Collections.unmodifiableMap(all);
        }

        for (String modulePath : SPEC_STDLIB_MODULES) {
            // Path: <surface>/console.d.deal for the module std/console.
            String name = modulePath.substring("std/".length());
            Path file = surface.resolve(name + ".d.deal");

            if (!Files.isRegularFile(file)) {
                // A surface missing a spec-listed file contributes
                // nothing: the authoritative failure is E2003 at the
                // import span from SourceModuleResolver (never a
                // RuntimeException "broken installation" path).
                continue;
            }

            try {
                Map<String, Type> exports = parseAndExtract(
                    Files.readString(file), file.toString(), modulePath);
                all.put(modulePath, Collections.unmodifiableMap(exports));
            } catch (IOException e) {
                // Unreadable declaration file: same omission semantics.
                continue;
            } catch (RuntimeException e) {
                // A declaration that cannot be parsed contributes
                // nothing here; the production path surfaces its own
                // diagnostics during module discovery/checking.
                continue;
            }
        }

        return Collections.unmodifiableMap(all);
    }

    /**
     * Parses one .d.deal declaration source text and extracts its export
     * signatures (resource-aware: the source is already decoded text, so
     * a declaration read from a classpath resource parses exactly like
     * one read from a file).
     */
    private static Map<String, Type> parseAndExtract(String source,
            String filename, String modulePath) {
        boolean isDecl = true;

        LexResult lex = new Lexer(source, filename).tokenize();
        if (lex.hasErrors()) {
            throw new RuntimeException("Lex errors in " + filename + ": "
                + lex.diagnostics());
        }

        Parser parser = new Parser(lex.tokens(), filename,
            lex.directiveEvents());
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
