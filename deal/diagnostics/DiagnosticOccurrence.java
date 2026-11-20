package deal.diagnostics;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** A diagnostic occurrence in an exact producer-owned source snapshot, not a semantic/edit identity. */
public record DiagnosticOccurrence(String sourceRevision, String occurrenceId, String rootCauseId,
                                   SnapshotKind snapshotKind) {
    public enum SnapshotKind { CURRENT_SOURCE, REJECTED_CANDIDATE, REJECTED_PAYLOAD }

    public DiagnosticOccurrence(String sourceRevision, String occurrenceId, String rootCauseId) {
        this(sourceRevision, occurrenceId, rootCauseId, SnapshotKind.CURRENT_SOURCE);
    }

    public DiagnosticOccurrence withSnapshotKind(SnapshotKind kind) {
        return new DiagnosticOccurrence(sourceRevision, occurrenceId, rootCauseId, kind);
    }

    public DiagnosticOccurrence {
        Objects.requireNonNull(sourceRevision, "sourceRevision");
        Objects.requireNonNull(occurrenceId, "occurrenceId");
        Objects.requireNonNull(snapshotKind, "snapshotKind");
    }

    /** Exact repeats share an occurrence; no inferred causal grouping from codes, ranges or prose. */
    public static DiagnosticOccurrence fromSource(String file, String source, CompilerDiagnostic diagnostic) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(diagnostic, "diagnostic");
        if (!file.equals(diagnostic.file())) return null;
        String revision = sourceRevision(file, source);
        String carrier = DiagnosticStructuredOutput.toJson(List.of(diagnostic.withOccurrence(null)));
        var cause = diagnostic.facts().cause();
        String root = null;
        if (cause != null && file.equals(cause.range().file()) && cause.range().origin() == RangeOrigin.SOURCE) {
            var range = cause.range();
            root = "cause:" + digest(revision, cause.reason(), cause.operation(),
                Integer.toString(range.startScalarOffset()), Integer.toString(range.endScalarOffset()));
        }
        return new DiagnosticOccurrence(revision, "diagnostic:" + digest(revision, carrier), root);
    }

    public static String sourceRevision(String file, String source) {
        return digest(Objects.requireNonNull(file, "file"), Objects.requireNonNull(source, "source"));
    }

    /** Length-framed UTF-16 units preserve isolated surrogates as well as valid supplementary pairs. */
    private static String digest(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                int length = part.length();
                digest.update((byte) (length >>> 24));
                digest.update((byte) (length >>> 16));
                digest.update((byte) (length >>> 8));
                digest.update((byte) length);
                for (int index = 0; index < length; index++) {
                    char unit = part.charAt(index);
                    digest.update((byte) (unit >>> 8));
                    digest.update((byte) unit);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
