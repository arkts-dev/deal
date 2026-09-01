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
 * Discovers stdlib modules under a pinned stdlib surface directory by
 * reading the 6 spec-listed {@code .d.deal} declaration files
 * ({@code <surface>/<module>.d.deal}) — the modules listed in the spec
 * (§Standard library declarations): {@code std/console},
 * {@code std/string}, {@code std/table}, {@code std/json},
 * {@code std/math}, {@code std/time}.
 *
 * <p><b>Surface-rooted reads (ISSUE-0269 migration, design source
 * {@code strict-project-context-resolution-identity} D5/D6 + Failure and
 * operations).</b> Export extraction is re-rooted from the legacy
 * CWD-relative std-directory and file-name reads to the resolved
 * {@code ProjectContext.stdlibSurfacePath} surface, so
 * typing exports and source resolution agree on one surface: a caller
 * passes the pinned surface directory path, and this class reads exactly
 * {@code <surface>/<module>.d.deal} for the six spec-listed modules.
 * A missing surface — or a surface missing a spec-listed file — is
 * <b>not</b> a {@code RuntimeException} "broken installation" path here:
 * the authoritative failure for a missing surface or missing spec-listed
 * file is E2003 at the import span from {@link SourceModuleResolver}
 * (T6's resolution), and this helper simply omits the module from the
 * returned export map (a missing file contributes nothing because no
 * import can resolve to it). The 6-module filter is preserved and stays
 * the single authority ({@link #SPEC_STDLIB_MODULES}).
 *
 * <p>Modules present on disk but absent from the spec ({@code
 * std/coroutine}, {@code std/io}) are excluded from discovery.</p>
 *
 * <p>Results are cached per surface path for the lifetime of the JVM
 * (test-friendly).</p>
 *
 * <p>Usage:
 * <pre>{@code
 * Map<String, Map<String, Type>> exports =
 *     StdlibModuleResolver.stdlibExports(surfacePath);
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
     * @param surfacePath the resolved stdlib surface directory path text
     *                    ({@code null} = absent surface)
     * @return unmodifiable map from module path to export-name-to-type
     *         map for every spec-listed module whose declaration file
     *         exists and parses under the surface (never null; empty
     *         for an absent surface)
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
                Map<String, Type> exports = parseAndExtract(file, modulePath);
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
