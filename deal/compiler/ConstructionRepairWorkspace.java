package deal.compiler;

import deal.semantic.ir.CanonicalJson;
import java.util.*;
import static deal.compiler.CompilerProtocolJson.*;

/** Ephemeral constructor transaction. Independent calls and transaction arguments are immutable. */
public final class ConstructionRepairWorkspace {
    public record StagedCalls(CanonicalJson.Arr calls, String digest, List<Map<String, Object>> summaries) {}
    public record StagedCallGrant(String digest, String target, CanonicalJson.Obj call,
            DealConstruction.Kind kind, List<String> dependencies, List<String> consumers) {}

    public static StagedCallGrant inspectStagedCall(CanonicalJson.Arr calls, String digest, String target, DealConstruction frontend) {
        if (!callsDigest(calls).equals(digest)) throw new IllegalArgumentException("Stale staged inspection digest");
        var nodes = calls.items().stream().map(value -> requireObject(value, "call")).toList();
        var call = nodes.stream().filter(node -> stringField(node, "id").equals(target)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown staged target"));
        var info = frontend.inspectDependencies(call);
        return new StagedCallGrant(digest, target, call, info.resultKind(), List.copyOf(info.handles()),
                nodes.stream().filter(node -> frontend.inspectDependencies(node).handles().contains(target))
                        .map(node -> stringField(node, "id")).toList());
    }

    public static StagedCalls replaceStagedCall(CanonicalJson.Arr calls, StagedCallGrant grant,
            CanonicalJson.Obj replacement, DealConstruction frontend) {
        var current = inspectStagedCall(calls, grant.digest(), grant.target(), frontend);
        if (!encode(current.call()).equals(encode(grant.call())) || current.kind() != grant.kind())
            throw new IllegalArgumentException("Staged inspection grant does not match current target");
        if (!stringField(replacement, "id").equals(grant.target())) throw new IllegalArgumentException("Replacement must retain inspected handle");
        if (frontend.inspectDependencies(replacement).resultKind() != current.kind())
            throw new IllegalArgumentException("Replacement must retain staged result kind " + current.kind());
        var candidate = CanonicalJson.arr(calls.items().stream().map(raw ->
                stringField(requireObject(raw, "call"), "id").equals(grant.target()) ? (CanonicalJson.Value) replacement : raw).toList());
        var empty = CanonicalJson.arr(List.of());
        return stageCalls(empty, callsDigest(empty), candidate, frontend);
    }

    public static String callsDigest(CanonicalJson.Arr calls) {
        return DealCompilerWorkspace.digest(encode(calls));
    }

    /** Stages constructor syntax, not a semantically checked program. Publication still requires full compilation. */
    public static StagedCalls stageCalls(CanonicalJson.Arr existing, String baseDigest,
            CanonicalJson.Arr additions, DealConstruction frontend) {
        if (!callsDigest(existing).equals(baseDigest)) throw new IllegalArgumentException("Stale constructor staging digest");
        if (additions.items().isEmpty())
            throw new IllegalArgumentException("Constructor staging requires at least one call");
        if (existing.items().size() + additions.items().size() > 512)
            throw new IllegalArgumentException("Construction transaction exceeds 512 calls");
        var candidate = new ArrayList<CanonicalJson.Value>(existing.items());
        candidate.addAll(additions.items());
        var ids = new LinkedHashSet<String>();
        var summaries = new ArrayList<Map<String, Object>>();
        for (var raw : candidate) {
            var call = requireObject(raw, "construction call");
            String id = stringField(call, "id");
            DealConstruction.validateHandle(id);
            if (!ids.add(id)) {
                var previous = candidate.stream().map(value -> requireObject(value, "call"))
                        .filter(value -> stringField(value, "id").equals(id)).findFirst().orElseThrow();
                throw new DealConstruction.Failure("CC1002", id, "Duplicate staged constructor handle: " + id,
                        Map.of("existingCall", encode(previous), "incomingCall", encode(call)));
            }
            var dependencies = frontend.inspectDependencies(call);
            var summary = new LinkedHashMap<String, Object>();
            summary.put("id", id);
            summary.put("op", stringField(call, "op"));
            summary.put("kind", dependencies.resultKind().name());
            summary.put("dependencies", List.copyOf(dependencies.handles()));
            for (var entry : call.entries()) if (Set.of("name", "fields", "parameters", "returns").contains(entry.key()))
                summary.put(entry.key(), entry.value());
            summaries.add(Collections.unmodifiableMap(summary));
        }
        var staged = CanonicalJson.arr(candidate);
        return new StagedCalls(staged, callsDigest(staged), List.copyOf(summaries));
    }

    private CanonicalJson.Obj envelope;
    private DealConstruction.Failure failure;
    private int unchangedAttempts;
    private final Set<String> rejectedCandidates = new LinkedHashSet<>();
    private boolean repeatedCandidate;
    private final DealConstruction constructor;

    public ConstructionRepairWorkspace(CanonicalJson.Obj envelope, DealConstruction.Failure failure) {
        this(envelope, failure, new DealConstruction());
    }

    public ConstructionRepairWorkspace(CanonicalJson.Obj envelope, DealConstruction.Failure failure, DealConstruction constructor) {
        this.envelope = envelope;
        this.constructor = Objects.requireNonNull(constructor);
        reject(failure);
    }

    public CanonicalJson.Obj envelope() { return envelope; }

    /** Paths are supplied by the transport schema, not discovered in source text. */
    public static void validateRoots(CanonicalJson.Obj envelope, List<List<Object>> paths) {
        validateRoots(envelope, paths, Map.of());
    }

    public static void validateRoots(CanonicalJson.Obj envelope, List<List<Object>> paths, Map<List<Object>, DealConstruction.Kind> kinds) {
        validateRoots(envelope, paths, kinds, null);
    }

    public static void validateRoots(CanonicalJson.Obj envelope, List<List<Object>> paths,
            Map<List<Object>, DealConstruction.Kind> kinds, DealConstruction frontend) {
        var ids = requireArray(field(envelope, "calls"), "calls").items().stream()
                .map(c -> stringField(requireObject(c, "call"), "id")).collect(java.util.stream.Collectors.toSet());
        var args = field(envelope, "arguments");
        var missing = new ArrayList<Map<String, Object>>();
        for (var path : paths) {
            var value = (CanonicalJson.Str) at(args, path, 0);
            var expected = kinds.get(path);
            DealConstruction.Kind actual = null;
            if (frontend != null && expected != null && ids.contains(value.value())) {
                var call = requireArray(field(envelope, "calls"), "calls").items().stream()
                        .map(raw -> requireObject(raw, "call"))
                        .filter(c -> stringField(c, "id").equals(value.value())).findFirst().orElseThrow();
                actual = frontend.inspectDependencies(call).resultKind();
            }
            boolean incompatible = actual != null && expected != null && actual != expected
                    && !(expected == DealConstruction.Kind.BLOCK
                    && (actual == DealConstruction.Kind.STATEMENT || actual == DealConstruction.Kind.UI));
            if (!ids.contains(value.value()) || incompatible) {
                var reference = new LinkedHashMap<String, Object>();
                reference.put("path", path); reference.put("handle", value.value());
                if (kinds.containsKey(path)) reference.put("expectedKind", kinds.get(path).name());
                if (incompatible) reference.put("actualKind", actual.name());
                missing.add(reference);
            }
        }
        if (!missing.isEmpty()) throw new DealConstruction.Failure("CC1004", (String) missing.getFirst().get("handle"),
                "Unknown or incompatible transaction result handles. Rebind to a compatible existing result, or construct the missing result and its dependencies. Existing independent calls remain immutable.",
                Map.of("rootReferences", encode(missing)));
    }

    public boolean hasRootRepairs() { return failure.facts.containsKey("rootReferences"); }

    private List<String> rootHandles(CanonicalJson.Value raw) {
        var root = requireObject(raw, "root");
        var expected = root.entries().stream().filter(e -> e.key().equals("expectedKind"))
                .map(e -> DealConstruction.Kind.valueOf(((CanonicalJson.Str) e.value()).value())).findFirst();
        return calls().stream().filter(c -> {
            if (expected.isEmpty()) return true;
            var actual = constructor.inspectDependencies(c).resultKind();
            return actual == expected.get() || expected.get() == DealConstruction.Kind.BLOCK
                    && (actual == DealConstruction.Kind.STATEMENT || actual == DealConstruction.Kind.UI);
        }).map(c -> stringField(c, "id")).toList();
    }

    public boolean hasRootRebindings() {
        return requireArray(decode(failure.facts.get("rootReferences")), "roots").items().stream()
                .anyMatch(root -> !rootHandles(root).isEmpty());
    }

    public Map<String, Object> rootRepairContract() {
        var roots = requireArray(decode(failure.facts.get("rootReferences")), "roots").items();
        return Map.of("ticket", candidateFingerprint(envelope), "roots", java.util.stream.IntStream.range(0, roots.size())
                .mapToObj(i -> Map.of("target", "A" + (i + 1), "reference", roots.get(i), "compatibleHandles", rootHandles(roots.get(i)))).toList(),
                "available", calls().stream().map(c -> {
                    var summary = new LinkedHashMap<String, Object>();
                    summary.put("id", stringField(c, "id")); summary.put("op", stringField(c, "op"));
                    summary.put("kind", constructor.inspectDependencies(c).resultKind().name());
                    for (var entry : c.entries()) if (Set.of("name", "fields", "parameters", "returns").contains(entry.key()))
                        summary.put(entry.key(), entry.value());
                    return summary;
                }).toList());
    }

    public Map<String, Object> rootRepairSchema() {
        var roots = requireArray(decode(failure.facts.get("rootReferences")), "roots").items();
        var alternatives = java.util.stream.IntStream.range(0, roots.size()).filter(i -> !rootHandles(roots.get(i)).isEmpty())
                .mapToObj(i -> DealConstruction.objectSchema(Map.of(
                    "target", Map.of("type", "string", "const", "A" + (i + 1)),
                    "handle", Map.of("type", "string", "enum", rootHandles(roots.get(i)))))).toList();
        if (alternatives.isEmpty()) throw new IllegalStateException("No compatible root rebindings; construct the missing dependency");
        var item = Map.of("anyOf", alternatives);
        return DealConstruction.objectSchema(Map.of("ticket", Map.of("type", "string", "const", candidateFingerprint(envelope)),
                "replacements", Map.of("type", "array", "minItems", 1, "maxItems", roots.size(), "items", item)));
    }

    public void validateRootTicket(CanonicalJson.Obj request) {
        if (!candidateFingerprint(envelope).equals(stringField(request, "ticket"))) throw new IllegalArgumentException("Stale root repair ticket");
    }

    public void patchRoots(CanonicalJson.Obj request) {
        validateRootTicket(request);
        var replacements = requireArray(field(request, "replacements"), "replacements");
        var roots = requireArray(decode(failure.facts.get("rootReferences")), "roots").items();
        var ids = calls().stream().map(c -> stringField(c, "id")).collect(java.util.stream.Collectors.toSet());
        var targets = new HashSet<String>();
        CanonicalJson.Value args = field(envelope, "arguments");
        if (replacements.items().isEmpty()) throw new IllegalArgumentException("Root repair must change a reference");
        for (var raw : replacements.items()) {
            var item = requireObject(raw, "replacement");
            String target = stringField(item, "target"), handle = stringField(item, "handle");
            if (!targets.add(target) || !ids.contains(handle)) throw new IllegalArgumentException("Root repair outside grant");
            int index = -1;
            for (int i = 0; i < roots.size(); i++) if (target.equals("A" + (i + 1))) index = i;
            if (index < 0) throw new IllegalArgumentException("Unknown root target");
            if (!rootHandles(roots.get(index)).contains(handle)) throw new IllegalArgumentException("Incompatible root result kind");
            var path = requireArray(field(requireObject(roots.get(index), "root"), "path"), "path").items().stream()
                    .map(v -> v instanceof CanonicalJson.Str s ? (Object) s.value() : (Object) (int) ((CanonicalJson.Int) v).value()).toList();
            args = replaceAt(args, path, 0, CanonicalJson.str(handle));
        }
        var changed = args;
        envelope = CanonicalJson.obj(envelope.entries().stream().map(e -> e.key().equals("arguments") ? CanonicalJson.e("arguments", changed) : e).toList());
    }

    private static CanonicalJson.Value at(CanonicalJson.Value value, List<Object> path, int offset) {
        if (offset == path.size()) return value;
        Object key = path.get(offset);
        return at(key instanceof String s ? field(requireObject(value, "path"), s) : requireArray(value, "path").items().get((Integer) key), path, offset + 1);
    }

    private static CanonicalJson.Value replaceAt(CanonicalJson.Value value, List<Object> path, int offset, CanonicalJson.Value replacement) {
        if (offset == path.size()) return replacement;
        Object key = path.get(offset);
        if (key instanceof String s) return CanonicalJson.obj(requireObject(value, "path").entries().stream()
                .map(e -> e.key().equals(s) ? CanonicalJson.e(s, replaceAt(e.value(), path, offset + 1, replacement)) : e).toList());
        var items = new ArrayList<>(requireArray(value, "path").items());
        int index = (Integer) key;
        items.set(index, replaceAt(items.get(index), path, offset + 1, replacement));
        return CanonicalJson.arr(items);
    }

    public void reject(DealConstruction.Failure next) {
        if (next.ownerId == null)
            throw next;
        failure = next;
        repeatedCandidate = !rejectedCandidates.add(candidateFingerprint(envelope));
    }

    public boolean repeatedCandidate() { return repeatedCandidate; }

    private static String candidateFingerprint(CanonicalJson.Obj envelope) {
        var orderedCalls = requireArray(field(envelope, "calls"), "calls").items().stream()
                .map(call -> requireObject(call, "call"))
                .sorted(Comparator.comparing(call -> stringField(call, "id"))).toList();
        var canonical = CanonicalJson.obj(envelope.entries().stream().map(e -> e.key().equals("calls")
                ? CanonicalJson.e("calls", CanonicalJson.arr(new ArrayList<CanonicalJson.Value>(orderedCalls))) : e).toList());
        return DealCompilerWorkspace.digest(encode(canonical));
    }

    private List<CanonicalJson.Obj> calls() {
        return requireArray(field(envelope, "calls"), "calls").items().stream()
                .map(c -> requireObject(c, "call")).toList();
    }

    private Set<String> repairRegion(Map<String, CanonicalJson.Obj> all) {
        var region = new LinkedHashSet<String>();
        region.add(failure.ownerId);
        if ("owners".equals(failure.facts.get("repairScope"))) {
            for (var value : requireArray(decode(failure.facts.get("repairOwners")), "repairOwners").items()) {
                String id = ((CanonicalJson.Str) value).value();
                if (!all.containsKey(id)) throw new IllegalArgumentException("Unknown compiler-issued repair owner");
                region.add(id);
            }
            return region;
        }
        if ("owner".equals(failure.facts.get("repairScope"))) return region;
        boolean changed;
        do {
            changed = false;
            for (var entry : all.entrySet()) {
                if (!region.contains(entry.getKey()) && constructor.inspectDependencies(entry.getValue()).handles().stream().anyMatch(region::contains)) {
                    region.add(entry.getKey());
                    changed = true;
                }
            }
        } while (changed);
        return region;
    }

    public boolean hasBlockOperandRepair() {
        return "BLOCK".equals(failure.facts.get("expectedKind")) && calls().stream().anyMatch(call ->
                stringField(call, "id").equals(failure.ownerId) && stringField(call, "op").equals("block"));
    }

    public Map<String, Object> blockOperandRepairSchema() {
        if (!hasBlockOperandRepair()) throw new IllegalStateException("No block operand repair grant");
        var handles = blockOperandHandles();
        Map<String, Object> item = handles.isEmpty() ? Map.of("type", "string") : Map.of("type", "string", "enum", handles);
        return DealConstruction.objectSchema(Map.of("ticket", Map.of("type", "string", "const", candidateFingerprint(envelope)),
                "statements", Map.of("type", "array", "items", item, "maxItems", handles.isEmpty() ? 0 : 512)));
    }

    private List<String> blockOperandHandles() {
        var graph = new LinkedHashMap<String, DealConstruction.CallDependencies>();
        for (var call : calls()) graph.put(stringField(call, "id"), constructor.inspectDependencies(call));
        return graph.entrySet().stream().filter(entry -> {
            var kind = entry.getValue().resultKind();
            if (kind != DealConstruction.Kind.STATEMENT && kind != DealConstruction.Kind.BLOCK) return false;
            var pending = new ArrayDeque<String>();
            var visited = new HashSet<String>();
            pending.add(entry.getKey());
            while (!pending.isEmpty()) {
                String id = pending.removeFirst();
                if (id.equals(failure.ownerId)) return false;
                if (visited.add(id) && graph.containsKey(id)) pending.addAll(graph.get(id).handles());
            }
            return true;
        }).map(Map.Entry::getKey).toList();
    }

    public void patchBlockOperands(CanonicalJson.Obj arguments) {
        if (!hasBlockOperandRepair() || !candidateFingerprint(envelope).equals(stringField(arguments, "ticket")))
            throw new IllegalArgumentException("Stale or unavailable block operand grant");
        var statements = requireArray(field(arguments, "statements"), "statements");
        var all = new LinkedHashMap<String, CanonicalJson.Obj>();
        for (var call : calls()) all.put(stringField(call, "id"), call);
        var allowed = new HashSet<>(blockOperandHandles());
        for (var item : statements.items()) {
            if (!(item instanceof CanonicalJson.Str handle) || !allowed.contains(handle.value()))
                throw new IllegalArgumentException("Block operand must be an issued statement handle");
        }
        var owner = all.get(failure.ownerId);
        var replacement = CanonicalJson.obj(owner.entries().stream().map(e -> e.key().equals("statements")
                ? CanonicalJson.e("statements", statements) : e).toList());
        patch(CanonicalJson.arr(List.of(replacement)));
    }

    public Map<String, Object> snapshot() {
        var all = new LinkedHashMap<String, CanonicalJson.Obj>();
        for (var call : calls()) all.put(stringField(call, "id"), call);
        var dependencies = new LinkedHashSet<String>();
        if (all.containsKey(failure.ownerId)) constructor.inspectDependencies(all.get(failure.ownerId)).handles().stream()
                .filter(all::containsKey).forEach(dependencies::add);
        var region = repairRegion(all);
        return Map.of("target", failure.ownerId, "diagnostic", failure.getMessage(),
                "diagnosticFacts", Map.of("code", failure.code, "facts", failure.facts,
                        "valueAlternatives", valueAlternatives(all)),
                "progress", unchangedAttempts == 0 ? "Replace the rejected operand; preserve all unrelated calls."
                        : "NO_PROGRESS: the last " + unchangedAttempts + " repair attempts repeated the identical rejected calls. Do not resend the current call unchanged. Follow the diagnostic to change the offending operand.",
                "call", all.containsKey(failure.ownerId) ? all.get(failure.ownerId) : Map.of("id", failure.ownerId, "missing", true),
                "dependencies", dependencies.stream().filter(id -> !id.equals(failure.ownerId)).map(all::get).toList(),
                "consumers", all.values().stream().filter(call -> !stringField(call, "id").equals(failure.ownerId))
                        .filter(call -> region.contains(stringField(call, "id"))).toList(),
                "editable", region.stream().toList(),
                "preserved", all.keySet().stream().filter(id -> !region.contains(id)).toList());
    }

    private List<Map<String, Object>> valueAlternatives(Map<String, CanonicalJson.Obj> all) {
        if (!"VALUE".equals(failure.facts.get("expectedKind"))) return List.of();
        String rejected = failure.facts.getOrDefault("handle", failure.facts.getOrDefault("missingHandle", ""));
        return all.values().stream().filter(call -> stringField(call, "op").equals("local"))
                .filter(call -> stringField(call, "id").equals(rejected) || stringField(call, "name").equals(rejected))
                .map(call -> Map.<String, Object>of("operand", Map.of("path", List.of(stringField(call, "name"))),
                        "declarationHandle", stringField(call, "id"),
                        "condition", "The local declaration must precede this use in the same lexical scope. Preserve its statement handle in the block."))
                .toList();
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
        int changeLimit = Math.max(16, region.size());
        if (changedCalls > changeLimit) throw new IllegalArgumentException(
                "Repair at most " + changeLimit + " NEW or CHANGED calls; omit unrelated work");
        if (all.size() > 512) throw new IllegalArgumentException("CC1001: construction batch exceeds 512 calls");
        var candidate = CanonicalJson.obj(envelope.entries().stream().map(e -> e.key().equals("calls")
                ? CanonicalJson.e("calls", CanonicalJson.arr(new ArrayList<CanonicalJson.Value>(all.values()))) : e).toList());
        unchangedAttempts = encode(candidate).equals(encode(envelope)) ? unchangedAttempts + 1 : 0;
        envelope = candidate;
    }

    public void validatePatch(CanonicalJson.Arr replacements) {
        new ConstructionRepairWorkspace(envelope, failure, constructor).patch(replacements);
    }
}
