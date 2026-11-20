package deal.diagnostics;

import java.util.List;
import java.util.Objects;

/** Immutable observations retained by a diagnostic, never a request to repeat the failed operation. */
public final class DiagnosticDetails<T> {
    public static final int PAGE_LIMIT = 32;
    public record Request(String inventoryId, int offset, int limit) {
        public Request {
            Objects.requireNonNull(inventoryId, "inventoryId");
            if (offset < 0 || limit < 1 || limit > PAGE_LIMIT)
                throw new IllegalArgumentException("Detail page offset/limit out of range");
        }
    }
    public record Page<T>(String inventoryId, int offset, int total, List<T> items, Integer nextOffset) {
        public Page { items = List.copyOf(items); }
    }
    public record Descriptor(String inventoryId, int total, int pageLimit) {}

    private final List<T> items;
    private final String inventoryId;

    private DiagnosticDetails(List<T> items, java.util.function.Function<T, List<String>> fields) {
        this.items = List.copyOf(items);
        StringBuilder framed = new StringBuilder();
        for (T item : this.items) {
            List<String> parts = fields.apply(item);
            framed.append(parts.size()).append(':');
            for (String part : parts) {
                if (part == null) framed.append("-1:");
                else framed.append(part.length()).append(':').append(part);
            }
        }
        inventoryId = DiagnosticOccurrence.sourceRevision("diagnostic-details-v1", framed.toString());
    }
    public static DiagnosticDetails<String> strings(List<String> observations) {
        return new DiagnosticDetails<>(observations, List::of);
    }
    public static DiagnosticDetails<DiagnosticFacts.CycleEdge> edges(List<DiagnosticFacts.CycleEdge> observations) {
        return new DiagnosticDetails<>(observations, edge -> {
            var fields = new java.util.ArrayList<String>();
            fields.add(edge.from()); fields.add(edge.to());
            appendRange(fields, edge.importRange());
            return fields;
        });
    }
    public static DiagnosticDetails<DiagnosticFacts.NameCandidate> names(List<DiagnosticFacts.NameCandidate> observations) {
        return new DiagnosticDetails<>(observations, name -> {
            var fields = new java.util.ArrayList<String>();
            fields.add(name.name()); fields.add(name.kind()); fields.add(name.type());
            fields.add(Integer.toString(name.distance())); fields.add(Integer.toString(name.scopeDepth()));
            appendRange(fields, name.declarationRange());
            return fields;
        });
    }
    public static DiagnosticDetails<DiagnosticFacts.NameObservation> lookups(List<DiagnosticFacts.NameObservation> observations) {
        return new DiagnosticDetails<>(observations, name -> {
            var fields = new java.util.ArrayList<String>();
            fields.add(name.name()); fields.add(name.kind()); fields.add(name.status().name());
            appendRange(fields, name.declarationRange());
            return fields;
        });
    }
    private static void appendRange(List<String> fields, DiagnosticRange range) {
        if (range == null) { fields.add(null); return; }
        fields.add(range.file()); fields.add(range.origin() == null ? null : range.origin().name());
        fields.add(Integer.toString(range.startLine())); fields.add(Integer.toString(range.startColumn()));
        fields.add(Integer.toString(range.endLine())); fields.add(Integer.toString(range.endColumn()));
        fields.add(Integer.toString(range.startScalarOffset())); fields.add(Integer.toString(range.endScalarOffset()));
        fields.add(Integer.toString(range.scalarLength()));
    }
    public Descriptor descriptor() { return new Descriptor(inventoryId, items.size(), PAGE_LIMIT); }
    public Page<T> page(Request request) {
        Objects.requireNonNull(request, "request");
        if (!inventoryId.equals(request.inventoryId()))
            throw new IllegalArgumentException("Detail inventory changed; inspect the current diagnostic");
        if (request.offset() > items.size()) throw new IllegalArgumentException("Detail page offset out of range");
        int end = request.offset() + Math.min(request.limit(), items.size() - request.offset());
        return new Page<>(inventoryId, request.offset(), items.size(), items.subList(request.offset(), end),
            end < items.size() ? end : null);
    }
    @Override public boolean equals(Object other) {
        return other instanceof DiagnosticDetails<?> details && inventoryId.equals(details.inventoryId) && items.equals(details.items);
    }
    @Override public int hashCode() { return 31 * inventoryId.hashCode() + items.hashCode(); }
    @Override public String toString() { return descriptor().toString(); }
}
