package deal.codegen;

import java.nio.file.Path;

/**
 * The shared project-relative path pair of one source-map sidecar: the
 * {@code source} and {@code generated} strings passed to
 * {@link SourceMapGenerator#toJson}.
 *
 * <p>Every production backend that stages {@code .deal.map.json} sidecars
 * consumes this one normalization: the single production emission resolves
 * every module's pair — LuaJIT and JVM from their one shared project
 * artifact, JavaScript from its one artifact per implementation unit — so a
 * sidecar's spelling never depends on which backend produced it. The project
 * root is inferred as {@code outputRoot/../..} (for a typical
 * {@code build/<target>} output directory) when the relativization stays
 * inside the project; otherwise each path keeps its fallback spelling — the
 * raw source path and the output-root-relative generated path.</p>
 */
public final class SourceMapPaths {

    private SourceMapPaths() {
        // Static helper only; no instances.
    }

    /**
     * The {@code [source, generated]} display pair of one sidecar: element 0
     * is {@link #sourceDisplay} and element 1 is {@link #generatedDisplay}.
     *
     * @param sourcePath    the DEAL source file path (as compiled)
     * @param outputRoot    the publication root (the live output root, never
     *                      the staging tree)
     * @param generatedPath the emitted artifact path under the output root
     */
    public static String[] projectRelative(String sourcePath, Path outputRoot,
                                           Path generatedPath) {
        return new String[] {
            sourceDisplay(sourcePath, outputRoot),
            generatedDisplay(outputRoot, generatedPath),
        };
    }

    /**
     * The source path relativized against the inferred project root when the
     * relativization stays inside the project, else the raw source path.
     */
    public static String sourceDisplay(String sourcePath, Path outputRoot) {
        try {
            Path projectRoot = projectRoot(outputRoot);
            Path srcRel = projectRoot.relativize(
                Path.of(sourcePath).toAbsolutePath());
            if (!srcRel.startsWith("..")) {
                return srcRel.toString();
            }
        } catch (IllegalArgumentException e) {
            // Keep the fallback spelling if relativization fails.
        }
        return sourcePath;
    }

    /**
     * The generated artifact path relativized against the inferred project
     * root when the relativization stays inside the project, else the
     * output-root-relative generated path.
     */
    public static String generatedDisplay(Path outputRoot, Path generatedPath) {
        String fallback = outputRoot.relativize(generatedPath).toString();
        try {
            Path genRel = projectRoot(outputRoot).relativize(
                generatedPath.toAbsolutePath());
            if (!genRel.startsWith("..")) {
                return genRel.toString();
            }
        } catch (IllegalArgumentException e) {
            // Keep the fallback spelling if relativization fails.
        }
        return fallback;
    }

    private static Path projectRoot(Path outputRoot) {
        return outputRoot.toAbsolutePath().normalize()
            .resolve("..").resolve("..").normalize();
    }
}
