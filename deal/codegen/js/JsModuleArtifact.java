package deal.codegen.js;

import deal.codegen.SourceMapGenerator;
import deal.semantic.ir.ModuleId;

import java.util.List;
import java.util.Objects;

/**
 * One rendered implementation-module artifact of the JavaScript
 * semantic-IR emitter (design source {@code wiki:javascript-semantic-ir-emission},
 * "renderer_boundary"): the recorded module identity, its stager-relative
 * CommonJS artifact path, the complete source text, and — with effective
 * maps — the ordered coordinate-only mapping list of the module's own
 * artifact.
 *
 * <p>{@code mappings} is {@code null} when maps are ineffective and an
 * ordered list, possibly empty, otherwise: this module's own share of the
 * emission's single {@link deal.codegen.SourceMapRecorder}, recorded at the
 * mapped groups' generated positions during emission. The list carries
 * coordinates only: the production owner calculates the source/generated
 * display strings from the checked source facts, the live output root and
 * {@link deal.codegen.SourceMapPaths}, records them through the existing
 * {@link SourceMapGenerator}, and serializes the one version-1 sidecar of
 * this module.</p>
 *
 */
public record JsModuleArtifact(
        ModuleId moduleId,
        String artifactRelativePath,
        String source,
        List<SourceMapGenerator.Mapping> mappings) {

    public JsModuleArtifact {
        Objects.requireNonNull(moduleId, "moduleId must not be null");
        Objects.requireNonNull(artifactRelativePath,
            "artifactRelativePath must not be null");
        Objects.requireNonNull(source, "source must not be null");
        if (mappings != null) {
            mappings = List.copyOf(mappings);
        }
        JsProjectEmission.requireStagerRelativePath(artifactRelativePath);
    }

    /** Whether this artifact carries an effective mapping list. */
    public boolean hasMappings() {
        return mappings != null;
    }
}
