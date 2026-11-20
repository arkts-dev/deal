package deal.diagnostics;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact source texts consumed by one compilation, not a claim that discovery completed. */
public record DiagnosticSourceBatch(String batchRevision, List<Source> sources) {
    public record Source(String file, String sourceRevision, DiagnosticOccurrence.SnapshotKind snapshotKind) {
        public Source {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(sourceRevision, "sourceRevision");
            Objects.requireNonNull(snapshotKind, "snapshotKind");
        }
    }

    public DiagnosticSourceBatch {
        sources = List.copyOf(sources);
        if (!sources.equals(sources.stream().sorted(Comparator.comparing(Source::file)).toList())
                || sources.stream().map(Source::file).distinct().count() != sources.size())
            throw new IllegalArgumentException("Source batch requires unique files in canonical order");
        if (!revision(sources).equals(batchRevision))
            throw new IllegalArgumentException("Source batch revision differs from its snapshots");
    }

    public static DiagnosticSourceBatch observed(Map<String, String> texts) {
        var sources = texts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new Source(entry.getKey(),
                        DiagnosticOccurrence.sourceRevision(entry.getKey(), entry.getValue()),
                        DiagnosticOccurrence.SnapshotKind.CURRENT_SOURCE)).toList();
        return new DiagnosticSourceBatch(revision(sources), sources);
    }

    private static String revision(List<Source> sources) {
        StringBuilder framed = new StringBuilder();
        for (Source source : sources) {
            for (String part : List.of(source.file(), source.sourceRevision(), source.snapshotKind().name()))
                framed.append(part.length()).append(':').append(part);
        }
        return DiagnosticOccurrence.sourceRevision("diagnostic-source-batch-v1", framed.toString());
    }
}
