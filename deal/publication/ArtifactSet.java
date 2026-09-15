package deal.publication;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The immutable modeled output set of one compilation (design source
 * {@code whole-project-artifact-publication} D1): exactly the artifacts
 * of the current compilation — module artifacts per backend, the
 * runtime library copy, the stdlib copies, the IR dumps (when enabled),
 * and the source-map sidecars (when enabled) — each under its final
 * relative path.
 *
 * <p>The set is immutable and insertion-ordered: construction copies
 * every input artifact and rejects a duplicate relative path, so
 * exactly one artifact exists per path (the staging contract's
 * "every generated artifact — and only those — staged under its final
 * relative path" model). {@link PublicationStager} publishes the set it
 * exposes through {@link PublicationStager#stagedSet()} and
 * {@link PublicationStager#publishedSet()}.</p>
 */
public final class ArtifactSet {

    private final List<Artifact> artifacts;
    private final Map<String, Artifact> byPath;

    /**
     * Builds the set from the given artifacts in insertion order.
     *
     * @param artifacts the artifacts (never null, no null elements)
     * @throws IllegalArgumentException when two artifacts carry the same
     *                                  relative path
     */
    public ArtifactSet(List<Artifact> artifacts) {
        Objects.requireNonNull(artifacts, "artifacts");
        List<Artifact> copy = new ArrayList<>(artifacts.size());
        Map<String, Artifact> index = new LinkedHashMap<>();
        for (Artifact artifact : artifacts) {
            Objects.requireNonNull(artifact, "artifact");
            Artifact previous =
                index.putIfAbsent(artifact.relativePath(), artifact);
            if (previous != null) {
                throw new IllegalArgumentException(
                    "duplicate artifact relative path: "
                        + artifact.relativePath());
            }
            copy.add(artifact);
        }
        this.artifacts = List.copyOf(copy);
        this.byPath = Collections.unmodifiableMap(index);
    }

    /**
     * Builds the set from the given artifacts, in argument order.
     */
    public static ArtifactSet of(Artifact... artifacts) {
        return new ArtifactSet(List.of(artifacts));
    }

    /**
     * The artifacts in insertion order (unmodifiable; each element
     * immutable per {@link Artifact}).
     */
    public List<Artifact> artifacts() {
        return artifacts;
    }

    /**
     * The one artifact staged under the given relative path, when
     * present.
     */
    public Optional<Artifact> artifact(String relativePath) {
        return Optional.ofNullable(byPath.get(relativePath));
    }

    /**
     * The set's relative paths in insertion order (unmodifiable).
     */
    public Set<String> relativePaths() {
        return byPath.keySet();
    }

    /** The number of artifacts in the set. */
    public int size() {
        return artifacts.size();
    }

    /** True when the set carries no artifact. */
    public boolean isEmpty() {
        return artifacts.isEmpty();
    }
}
