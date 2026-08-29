package deal.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The immutable staged artifact set a publication invocation wrote into
 * its staging tree (foundation F6): the validator-side view of the
 * on-disk tree, keyed by artifact name.
 *
 * <p>The set is the exact write record of {@link ProjectArtifactStager}:
 * every artifact the stager wrote into the staging tree is present under
 * its final relative path, in write (module, then artifact) order.
 * {@code TargetAbiValidator} resolves each {@code TargetModuleAbi}
 * {@code loadKey} against this set — a {@code loadKey} that does not name
 * a staged artifact is the incomplete-record class
 * {@code ABI_LOAD_KEY_UNRESOLVED}. Duplicate names are a producer defect
 * and fail closed at construction: two artifacts can never silently
 * overwrite each other in the published set.</p>
 */
public final class StagedArtifactSet {

    private final Map<String, StagedArtifact> artifacts;

    /**
     * Builds the set from the staged artifacts in write order.
     *
     * @param artifacts the staged artifacts; non-null
     * @throws IllegalArgumentException when two artifacts carry the same
     *         name (fail closed — the published set must have exactly one
     *         artifact per name)
     */
    public StagedArtifactSet(List<StagedArtifact> artifacts) {
        Objects.requireNonNull(artifacts, "artifacts must not be null");
        Map<String, StagedArtifact> byName = new LinkedHashMap<>();
        for (StagedArtifact artifact : artifacts) {
            Objects.requireNonNull(artifact, "artifacts entries must not be null");
            if (byName.putIfAbsent(artifact.name(), artifact) != null) {
                throw new IllegalArgumentException(
                    "duplicate staged artifact name \"" + artifact.name() + "\"");
            }
        }
        this.artifacts = Collections.unmodifiableMap(byName);
    }

    /** The empty staged set (no artifacts staged). */
    public static StagedArtifactSet empty() {
        return new StagedArtifactSet(List.of());
    }

    /**
     * The staged artifacts keyed by name in write order (unmodifiable).
     */
    public Map<String, StagedArtifact> artifacts() {
        return artifacts;
    }

    /**
     * The staged artifacts in write order (unmodifiable).
     */
    public List<StagedArtifact> inWriteOrder() {
        return new ArrayList<>(artifacts.values());
    }

    /**
     * The artifact names in write order (unmodifiable).
     */
    public Set<String> names() {
        return new LinkedHashSet<>(artifacts.keySet());
    }

    /**
     * Whether an artifact with the given name was staged — the
     * {@code loadKey} resolution gate.
     *
     * @param name the artifact name; non-null
     * @return true iff the set contains that artifact
     */
    public boolean contains(String name) {
        return artifacts.containsKey(name);
    }

    /** The number of staged artifacts. */
    public int size() {
        return artifacts.size();
    }
}
