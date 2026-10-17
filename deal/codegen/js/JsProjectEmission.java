package deal.codegen.js;

import deal.semantic.ir.ModuleId;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The successful result of one {@code JsSemanticEmitter.emitProductionProject}
 * call (design source {@code wiki:javascript-semantic-ir-emission},
 * "renderer_boundary"): exactly one artifact per implementation unit of the
 * lowered project closure, in project dependency order, plus the entry
 * artifact's relative path.
 *
 * <p>The result contains content and mappings only. It is not publish
 * success: the production owner stages every module and serializes every
 * sidecar inside the existing exclusive-root transaction. Each artifact's
 * ordered coordinate list is that module's own share of the emission's
 * single source-map recorder, so the owner's one version-1
 * {@code <module>.deal.map.json} sidecar per implementation artifact is
 * assembled from {@link deal.codegen.SourceMapPaths}' live-root projection
 * of the module's own source and artifact and the existing
 * {@link deal.codegen.SourceMapGenerator} serializer — never from a stage
 * tree or from generated text.</p>
 *
 */
public record JsProjectEmission(
        String entryArtifactRelativePath,
        List<JsModuleArtifact> modules) {

    public JsProjectEmission {
        Objects.requireNonNull(entryArtifactRelativePath,
            "entryArtifactRelativePath must not be null");
        requireStagerRelativePath(entryArtifactRelativePath);
        Objects.requireNonNull(modules, "modules must not be null");
        modules = List.copyOf(modules);
        if (modules.isEmpty()) {
            throw new IllegalArgumentException(
                "a successful project emission carries exactly one artifact"
                    + " per implementation unit; the closure is never empty");
        }
        Set<String> paths = new HashSet<>();
        Set<ModuleId> moduleIds = new HashSet<>();
        boolean entryPresent = false;
        for (JsModuleArtifact artifact : modules) {
            if (!paths.add(artifact.artifactRelativePath())) {
                throw new IllegalArgumentException("two artifacts share the"
                    + " stager-relative path '" + artifact.artifactRelativePath()
                    + "': artifact paths are unique");
            }
            if (!moduleIds.add(artifact.moduleId())) {
                throw new IllegalArgumentException("two artifacts share the"
                    + " module identity " + artifact.moduleId()
                    + ": one artifact per implementation unit");
            }
            if (artifact.artifactRelativePath()
                    .equals(entryArtifactRelativePath)) {
                entryPresent = true;
            }
        }
        if (!entryPresent) {
            throw new IllegalArgumentException("the entry artifact path '"
                + entryArtifactRelativePath + "' names no member of the"
                + " emitted module set: the entry path names the entry"
                + " module's corresponding member");
        }
    }

    /**
     * Rejects a path that is not a valid stager-relative artifact path: an
     * absolute path, a path escaping the output root, or an empty/blank
     * path. The renderer produces module ids' own {@code .replace('.', '/')
     * + ".js"} spelling (or an equivalent canonical spelling), never an
     * escaping path.
     */
    static void requireStagerRelativePath(String path) {
        Objects.requireNonNull(path, "path must not be null");
        if (path.isEmpty()) {
            throw new IllegalArgumentException(
                "a stager-relative artifact path must not be empty");
        }
        if (path.startsWith("/") || path.startsWith("\\")
                || path.contains("\\") || path.contains("\0")) {
            throw new IllegalArgumentException("the artifact path '" + path
                + "' is not a stager-relative path: a relative '/' separated"
                + " spelling inside the output root is required");
        }
        if (path.endsWith("/")) {
            throw new IllegalArgumentException("the artifact path '" + path
                + "' names a directory, not an artifact");
        }
        if (path.length() >= 2 && path.charAt(1) == ':') {
            throw new IllegalArgumentException("the artifact path '" + path
                + "' carries a drive-letter prefix and is not a"
                + " stager-relative path");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("the artifact path '" + path
                    + "' carries the invalid segment '" + segment
                    + "': artifact paths are canonical relative paths inside"
                    + " the output root");
            }
        }
    }
}
