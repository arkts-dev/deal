package deal.compiler;

import deal.semantic.ir.CanonicalJson;
import java.util.*;
import static deal.compiler.CompilerProtocolJson.*;

/** Ephemeral constructor transaction. Existing sibling calls and transaction arguments are immutable. */
public final class ConstructionRepairWorkspace {
    private CanonicalJson.Obj envelope;
    private DealConstruction.Failure failure;

    public ConstructionRepairWorkspace(CanonicalJson.Obj envelope, DealConstruction.Failure failure) {
        this.envelope = envelope;
        reject(failure);
    }

    public CanonicalJson.Obj envelope() { return envelope; }

    public void reject(DealConstruction.Failure next) {
        if (next.ownerId == null)
            throw next;
        failure = next;
    }

    private List<CanonicalJson.Obj> calls() {
        return requireArray(field(envelope, "calls"), "calls").items().stream()
                .map(c -> requireObject(c, "call")).toList();
    }

    public Map<String, Object> snapshot() {
        var all = new LinkedHashMap<String, CanonicalJson.Obj>();
        for (var call : calls()) all.put(stringField(call, "id"), call);
        var dependencies = new LinkedHashSet<String>();
        collect(all.get(failure.ownerId), all, dependencies);
        return Map.of("target", failure.ownerId, "diagnostic", failure.getMessage(),
                "call", all.containsKey(failure.ownerId) ? all.get(failure.ownerId) : Map.of("id", failure.ownerId, "missing", true),
                "dependencies", dependencies.stream().filter(id -> !id.equals(failure.ownerId)).map(all::get).toList(),
                "preserved", all.keySet().stream().filter(id -> !dependencies.contains(id)).toList());
    }

    private static void collect(CanonicalJson.Value value, Map<String, CanonicalJson.Obj> all, Set<String> visited) {
        if (value instanceof CanonicalJson.Str s && all.containsKey(s.value()) && visited.add(s.value())) collect(all.get(s.value()), all, visited);
        else if (value instanceof CanonicalJson.Obj o) for (var entry : o.entries()) collect(entry.value(), all, visited);
        else if (value instanceof CanonicalJson.Arr a) for (var item : a.items()) collect(item, all, visited);
    }

    public void patch(CanonicalJson.Arr replacements) {
        if (replacements.items().isEmpty() || replacements.items().size() > 8)
            throw new IllegalArgumentException("Replace the rejected call plus at most seven new dependencies");
        var all = new LinkedHashMap<String, CanonicalJson.Obj>();
        for (var call : calls()) all.put(stringField(call, "id"), call);
        var seen = new HashSet<String>();
        for (var raw : replacements.items()) {
            var call = requireObject(raw, "call");
            String id = stringField(call, "id");
            if (!seen.add(id) || all.containsKey(id) && !id.equals(failure.ownerId))
                throw new IllegalArgumentException("Cannot change preserved constructor call: " + id);
            all.put(id, call);
        }
        if (!seen.contains(failure.ownerId)) throw new IllegalArgumentException("Patch must replace " + failure.ownerId);
        if (all.size() > 512) throw new IllegalArgumentException("CC1001: construction batch exceeds 512 calls");
        envelope = CanonicalJson.obj(envelope.entries().stream().map(e -> e.key().equals("calls")
                ? CanonicalJson.e("calls", CanonicalJson.arr(new ArrayList<CanonicalJson.Value>(all.values()))) : e).toList());
    }

    public void validatePatch(CanonicalJson.Arr replacements) {
        new ConstructionRepairWorkspace(envelope, failure).patch(replacements);
    }
}
