package deal.publication;

import java.util.Objects;

/**
 * One artifact of a compilation's published output set (design source
 * {@code whole-project-artifact-publication} D1): its final relative
 * path under the publication root plus its content bytes.
 *
 * <p>The relative path obeys the canonical grammar shared by
 * {@link PublicationStager#stage}: a {@code '/'}-separated relative
 * path with non-empty segments, no leading or trailing separator, and
 * no {@code "."}/{@code ".."} segments — the closed shape guarantees
 * the staged target can never escape the staging tree. The content is
 * defensively copied on construction and on every access, so an
 * artifact is immutable once built (the {@link ArtifactSet}
 * immutability contract).</p>
 *
 * @param relativePath the final {@code '/'}-separated relative path
 *                     under the publication root (canonical grammar)
 * @param content      the artifact bytes (defensively copied; never
 *                     null)
 */
public record Artifact(String relativePath, byte[] content) {

    public Artifact {
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(content, "content");
        validateRelativePath(relativePath);
        content = content.clone();
    }

    /**
     * Validates one artifact relative path against the canonical
     * grammar: non-empty, {@code '/'}-separated, no leading or trailing
     * separator, and no empty, {@code "."}, or {@code ".."} segment.
     *
     * @param relativePath the candidate relative path
     * @throws IllegalArgumentException when the path violates the grammar
     */
    public static void validateRelativePath(String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath");
        if (relativePath.isEmpty()) {
            throw new IllegalArgumentException(
                "artifact relative path is empty");
        }
        if (relativePath.startsWith("/") || relativePath.endsWith("/")) {
            throw new IllegalArgumentException(
                "artifact relative path must not start or end with '/': \""
                    + relativePath + "\"");
        }
        for (String segment : relativePath.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment)
                    || "..".equals(segment)) {
                throw new IllegalArgumentException(
                    "artifact relative path carries an empty, '.', or '..'"
                        + " segment: \"" + relativePath + "\"");
            }
        }
    }

    /**
     * The content accessor: a fresh defensive copy per call (the
     * immutability contract — a caller mutating the returned array
     * never mutates this artifact or the set that carries it).
     */
    @Override
    public byte[] content() {
        return content.clone();
    }
}
