package deal.semantic;

import java.util.Arrays;
import java.util.Objects;

/**
 * One named artifact payload of a staged publication set (foundation F6;
 * parent "Shared module emission and publication" contract):
 * {@code StagedArtifact{name, bytes}}.
 *
 * <p>{@code name} is the artifact's relative path inside the staging
 * tree — the stable on-disk identity a {@code TargetModuleAbi}
 * {@code loadKey} resolves against at validation time. The compact
 * constructor enforces the closed path grammar: a non-empty relative
 * path of non-empty {@code .}/{@code ..}-free segments joined by forward
 * slashes, never absolute, never a drive letter, and never containing a
 * backslash — a staged artifact can therefore never escape the staging
 * tree through path traversal, and an out-of-grammar name is a producer
 * defect rejected at construction, never silently rewritten.</p>
 *
 * <p>The record is immutable: the byte payload is defensively copied on
 * construction and on every access, so no consumer can mutate a staged
 * artifact after staging.</p>
 *
 * @param name  the artifact's relative path inside the staging tree; non-null
 * @param bytes the artifact content; non-null
 */
public record StagedArtifact(String name, byte[] bytes) {

    public StagedArtifact {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(bytes, "bytes must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("artifact name must not be empty");
        }
        if (name.startsWith("/") || name.startsWith("\\") || name.contains("\\")
                || (name.length() >= 2 && name.charAt(1) == ':')) {
            throw new IllegalArgumentException(
                "artifact name must be a relative forward-slash path, got \"" + name + "\"");
        }
        for (String segment : name.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(
                    "artifact name segments must be non-empty and free of \".\"/\"..\", got \""
                        + name + "\"");
            }
        }
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StagedArtifact that
            && name.equals(that.name)
            && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return 31 * name.hashCode() + Arrays.hashCode(bytes);
    }
}
