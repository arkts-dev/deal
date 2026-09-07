package deal.compiler;

import deal.semantic.ir.CanonicalJson;
import java.util.*;
import static deal.compiler.CompilerProtocolJson.*;

/** Ephemeral constructor transaction. Independent calls and transaction arguments are immutable. */
public final class ConstructionRepairWorkspace {
    private CanonicalJson.Obj envelope;
    private DealConstruction.Failure failure;
    private int unchangedAttempts;

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

    private Set<String> repairRegion(Map<String, CanonicalJson.Obj> all) {
        var region = new LinkedHashSet<String>();
        region.add(failure.ownerId);
        boolean changed;
        do {
            changed = false;
            for (var entry : all.entrySet()) {
                if (!region.contains(entry.getKey()) && region.stream().anyMatch(id -> references(entry.getValue(), id))) {
                    region.add(entry.getKey());
                    changed = true;
                }
            }
        } while (changed);
        return region;
    }

    public Map<String, Object> snapshot() {
        var all = new LinkedHashMap<String, CanonicalJson.Obj>();
        for (var call : calls()) all.put(stringField(call, "id"), call);
        var dependencies = new LinkedHashSet<String>();
        collectDirect(all.get(failure.ownerId), all, dependencies);
        var region = repairRegion(all);
        return Map.of("target", failure.ownerId, "diagnostic", failure.getMessage(),
                "progress", unchangedAttempts == 0 ? "Replace the rejected operand; preserve all unrelated calls."
                        : "NO_PROGRESS: the last " + unchangedAttempts + " repair attempts repeated the identical rejected calls. Do not resend the current call unchanged. Follow the diagnostic to change the offending operand.",
                "call", all.containsKey(failure.ownerId) ? all.get(failure.ownerId) : Map.of("id", failure.ownerId, "missing", true),
                "dependencies", dependencies.stream().filter(id -> !id.equals(failure.ownerId)).map(all::get).toList(),
                "consumers", all.values().stream().filter(call -> !stringField(call, "id").equals(failure.ownerId))
                        .filter(call -> region.contains(stringField(call, "id"))).toList(),
                "editable", region.stream().toList(),
                "preserved", all.keySet().stream().filter(id -> !region.contains(id)).toList());
    }

    private static boolean references(CanonicalJson.Value value, String id) {
        if (value instanceof CanonicalJson.Str s) return s.value().equals(id);
        if (value instanceof CanonicalJson.Obj o) return o.entries().stream()
                .filter(e -> !e.key().equals("id")).anyMatch(e -> references(e.value(), id));
        if (value instanceof CanonicalJson.Arr a) return a.items().stream().anyMatch(v -> references(v, id));
        return false;
    }

    private static void collectDirect(CanonicalJson.Value value, Map<String, CanonicalJson.Obj> all, Set<String> visited) {
        if (value instanceof CanonicalJson.Str s && all.containsKey(s.value())) visited.add(s.value());
        else if (value instanceof CanonicalJson.Obj o) for (var entry : o.entries()) collectDirect(entry.value(), all, visited);
        else if (value instanceof CanonicalJson.Arr a) for (var item : a.items()) collectDirect(item, all, visited);
    }

    public void patch(CanonicalJson.Arr replacements) {
        if (replacements.items().isEmpty() || replacements.items().size() > 512)
            throw new IllegalArgumentException("Repair batch must contain 1..512 calls");
        var all = new LinkedHashMap<String, CanonicalJson.Obj>();
        for (var call : calls()) all.put(stringField(call, "id"), call);
        var region = repairRegion(all);
        var seen = new HashSet<String>();
        int changedCalls = 0;
        for (var raw : replacements.items()) {
            var call = requireObject(raw, "call");
            String id = stringField(call, "id");
            if (!seen.add(id)) throw new IllegalArgumentException("Duplicate constructor call: " + id);
            if (all.containsKey(id) && !region.contains(id)
                    && !encode(all.get(id)).equals(encode(call)))
                throw new IllegalArgumentException("Cannot change preserved constructor call: " + id);
            if (!all.containsKey(id) || !encode(all.get(id)).equals(encode(call))) changedCalls++;
            all.put(id, call);
        }
        if (!seen.contains(failure.ownerId)) throw new IllegalArgumentException("Patch must replace " + failure.ownerId);
        if (changedCalls > 16) throw new IllegalArgumentException("Repair at most sixteen NEW or CHANGED calls; omit unrelated work");
        if (all.size() > 512) throw new IllegalArgumentException("CC1001: construction batch exceeds 512 calls");
        var candidate = CanonicalJson.obj(envelope.entries().stream().map(e -> e.key().equals("calls")
                ? CanonicalJson.e("calls", CanonicalJson.arr(new ArrayList<CanonicalJson.Value>(all.values()))) : e).toList());
        unchangedAttempts = encode(candidate).equals(encode(envelope)) ? unchangedAttempts + 1 : 0;
        envelope = candidate;
    }

    public void validatePatch(CanonicalJson.Arr replacements) {
        new ConstructionRepairWorkspace(envelope, failure).patch(replacements);
    }
}
