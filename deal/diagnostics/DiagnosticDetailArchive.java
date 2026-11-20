package deal.diagnostics;

import deal.amend.CompilerProtocolJson;
import deal.semantic.ir.CanonicalJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persisted producer observations for later bounded reads; never recompiles or probes inputs. */
public final class DiagnosticDetailArchive {
    private DiagnosticDetailArchive() {}
    public record Archive(int version, List<Inventory> inventories) {
        public Archive { inventories = List.copyOf(inventories); }
    }
    public record Inventory(DiagnosticDetails.Descriptor descriptor, List<DiagnosticDetails.Page<?>> pages) {
        public Inventory { pages = List.copyOf(pages); }
    }

    public static List<DiagnosticDetails<?>> inventories(List<DiagnosticFacts> facts) {
        Map<String, DiagnosticDetails<?>> retained = new LinkedHashMap<>();
        for (var fact : facts) {
            if (fact.names() != null) {
                add(retained, fact.names().details()); add(retained, fact.names().observationDetails());
            }
            if (fact.exports() != null) add(retained, fact.exports().details());
            if (fact.resolution() != null) add(retained, fact.resolution().details());
            if (fact.cycle() != null) add(retained, fact.cycle().details());
        }
        return List.copyOf(retained.values());
    }
    private static void add(Map<String, DiagnosticDetails<?>> retained, DiagnosticDetails<?> details) {
        if (details != null) retained.putIfAbsent(details.descriptor().inventoryId(), details);
    }
    public static DiagnosticDetails.Page<?> page(List<DiagnosticFacts> facts, DiagnosticDetails.Request request) {
        for (var details : inventories(facts))
            if (details.descriptor().inventoryId().equals(request.inventoryId())) return details.page(request);
        throw new IllegalArgumentException("Detail inventory unavailable; inspect the matching diagnostic report");
    }
    public static String encode(List<DiagnosticFacts> facts) {
        var inventories = new ArrayList<Inventory>();
        for (var details : inventories(facts)) {
            var pages = new ArrayList<DiagnosticDetails.Page<?>>();
            int offset = 0;
            do {
                var page = details.page(new DiagnosticDetails.Request(details.descriptor().inventoryId(), offset,
                        DiagnosticDetails.PAGE_LIMIT));
                pages.add(page);
                if (page.nextOffset() == null) break;
                offset = page.nextOffset();
            } while (true);
            inventories.add(new Inventory(details.descriptor(), pages));
        }
        return CompilerProtocolJson.encode(new Archive(1, inventories));
    }

    /** Re-page only stored observations, retaining their typed JSON representation and exact order. */
    public static CanonicalJson.Value page(String archive, DiagnosticDetails.Request request) {
        var root = CompilerProtocolJson.requireObject(CompilerProtocolJson.decode(archive), "Detail archive");
        if (CompilerProtocolJson.intField(root, "version") != 1)
            throw new IllegalArgumentException("Unsupported diagnostic detail archive version");
        for (var value : CompilerProtocolJson.requireArray(CompilerProtocolJson.field(root, "inventories"), "Inventories").items()) {
            var inventory = CompilerProtocolJson.requireObject(value, "Inventory");
            var descriptor = CompilerProtocolJson.requireObject(CompilerProtocolJson.field(inventory, "descriptor"), "Descriptor");
            if (!CompilerProtocolJson.stringField(descriptor, "inventoryId").equals(request.inventoryId())) continue;
            int total = CompilerProtocolJson.intField(descriptor, "total");
            if (total < 0 || request.offset() > total) throw new IllegalArgumentException("Detail page offset out of range");
            int end = request.offset() + Math.min(request.limit(), total - request.offset());
            var items = new ArrayList<CanonicalJson.Value>();
            int observed = 0;
            for (var rawPage : CompilerProtocolJson.requireArray(CompilerProtocolJson.field(inventory, "pages"), "Pages").items()) {
                var page = CompilerProtocolJson.requireObject(rawPage, "Page");
                if (!CompilerProtocolJson.stringField(page, "inventoryId").equals(request.inventoryId())
                        || CompilerProtocolJson.intField(page, "offset") != observed
                        || CompilerProtocolJson.intField(page, "total") != total)
                    throw new IllegalArgumentException("Inconsistent diagnostic detail archive");
                var stored = CompilerProtocolJson.requireArray(CompilerProtocolJson.field(page, "items"), "Items").items();
                if (stored.size() > DiagnosticDetails.PAGE_LIMIT || stored.size() > total - observed)
                    throw new IllegalArgumentException("Inconsistent diagnostic detail archive");
                if (stored.isEmpty() && total != 0)
                    throw new IllegalArgumentException("Inconsistent diagnostic detail archive");
                for (var item : stored) {
                    if (observed >= request.offset() && observed < end) items.add(item);
                    observed++;
                }
                var next = CompilerProtocolJson.field(page, "nextOffset");
                var expectedNext = observed < total ? CanonicalJson.intValue(observed) : CanonicalJson.nullValue();
                if (!next.equals(expectedNext)) throw new IllegalArgumentException("Inconsistent diagnostic detail continuation");
            }
            if (observed != total) throw new IllegalArgumentException("Incomplete diagnostic detail archive");
            return CompilerProtocolJson.toValue(new DiagnosticDetails.Page<>(request.inventoryId(), request.offset(), total,
                    items, end < total ? end : null));
        }
        throw new IllegalArgumentException("Detail inventory unavailable; inspect the matching diagnostic report");
    }
}
