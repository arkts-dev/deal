package deal.compiler;

import deal.compiler.CompilerProtocol.*;
import deal.diagnostics.CompilerDiagnostic.MissingSymbol;
import java.util.*;

/** Deterministic grants over an ephemeral workspace; never interprets diagnostic prose. */
public final class RepairWorkspaceProtocol {
    public static final String VERSION = "repair-workspace-v2";
    private RepairWorkspaceProtocol() {}
    public enum Disposition { LOCAL, DEPENDENCY_GROUP, EXPANSION_REQUIRED, UNSUPPORTED }
    public record Obligation(String id, MissingSymbol missing, List<String> ownerSlots) {
        public Obligation { ownerSlots = List.copyOf(ownerSlots); }
    }
    public record Expansion(String id, List<String> slots, List<String> obligations) {
        public Expansion { slots = List.copyOf(slots); obligations = List.copyOf(obligations); }
    }
    public record Offer(String version, String workspaceDigest, Disposition disposition,
                        List<String> slots, List<Obligation> obligations, List<Expansion> expansions,
                        List<StructuredDiagnostic> diagnostics, String reason) {
        public Offer {
            slots = List.copyOf(slots); obligations = List.copyOf(obligations);
            expansions = List.copyOf(expansions); diagnostics = List.copyOf(diagnostics);
        }
    }
    public record Grant(String version, String workspaceDigest, List<String> slots,
                        List<String> obligations, List<String> expansions, String digest) {
        public Grant {
            slots = List.copyOf(slots); obligations = List.copyOf(obligations); expansions = List.copyOf(expansions);
        }
    }
    public record Dependency(String obligation, String declaration) {}

    public static Offer inspectRepair(RepairWorkspaceSnapshot workspace) {
        return inspectRepair(workspace, RepairDiagnosticRegistry.core());
    }

    public static Offer inspectRepair(RepairWorkspaceSnapshot workspace, RepairDiagnosticRegistry registry) {
        requireWorkspace(workspace);
        var groups = new LinkedHashMap<String, DependencyGroup>();
        workspace.groups().forEach(group -> groups.put(group.groupId(), group));
        var needingRepair = workspace.slots().stream()
                .filter(slot -> slot.status() == RepairSlotStatus.REJECTED || slot.status() == RepairSlotStatus.BLOCKED)
                .map(RepairSlot::dependencyGroupId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        String selected = needingRepair.stream().filter(id -> {
            var group = groups.get(id);
            return group != null && group.dependsOn().stream().noneMatch(needingRepair::contains);
        }).findFirst().orElse(null);
        if (selected == null) return unsupported(workspace, "No repairable dependency group; candidate is complete or the dependency graph is inconsistent");
        var active = workspace.slots().stream().filter(slot -> slot.dependencyGroupId().equals(selected)).toList();
        var writable = active.stream().filter(slot -> slot.status() == RepairSlotStatus.REJECTED
                        || slot.status() == RepairSlotStatus.BLOCKED).map(RepairSlot::slotId).toList();
        if (writable.isEmpty()) return unsupported(workspace, "Selected group has no writable slots");
        var missing = new LinkedHashMap<MissingSymbol, List<String>>();
        active.forEach(slot -> slot.diagnostics().forEach(d -> d.missingSymbols().forEach(fact -> {
            if (fact.kind().equals("TYPE") || fact.kind().equals("FUNCTION"))
                missing.computeIfAbsent(fact, ignored -> new ArrayList<>()).add(slot.slotId());
        })));
        var obligations = new ArrayList<Obligation>();
        missing.forEach((fact, owners) -> obligations.add(new Obligation("D" + (obligations.size() + 1), fact,
                owners.stream().distinct().sorted().toList())));
        for (var slot : active) {
            for (var diagnostic : slot.diagnostics()) {
                if (!registry.contains(diagnostic.code()))
                    return unsupported(workspace, "Unregistered repair diagnostic: " + diagnostic.code());
                boolean dependencyRepair = diagnostic.missingSymbols().stream()
                        .anyMatch(fact -> missing.containsKey(fact));
                boolean localRepair = diagnostic.repairScopes().stream().anyMatch(scope ->
                        active.stream().anyMatch(target -> target.operation().equals(scope.operation())
                                && target.targetId().equals(scope.ownerId())));
                if (!dependencyRepair && !localRepair)
                    return unsupported(workspace, "No compiler-issued repair operation for diagnostic " + diagnostic.code());
            }
        }
        var expansions = new ArrayList<Expansion>();
        for (var obligation : obligations)
            expansions.add(new Expansion("add:" + obligation.id(), List.of(), List.of(obligation.id())));
        // Only graph-related slots can be reopened. Reopening never changes their payload itself.
        Set<String> providers = new LinkedHashSet<>(List.of(selected));
        Set<String> consumers = new LinkedHashSet<>(List.of(selected));
        boolean changed;
        do {
            changed = false;
            for (var group : workspace.groups()) {
                if (providers.contains(group.groupId())) changed |= providers.addAll(group.dependsOn());
                if (group.dependsOn().stream().anyMatch(consumers::contains)) changed |= consumers.add(group.groupId());
            }
        } while (changed);
        Set<String> related = new LinkedHashSet<>(providers);
        related.addAll(consumers);
        var reopen = workspace.slots().stream().filter(slot -> related.contains(slot.dependencyGroupId())
                && !writable.contains(slot.slotId()) && slot.status() != RepairSlotStatus.COMMIT_READY)
                .map(RepairSlot::slotId).sorted().toList();
        if (!reopen.isEmpty()) expansions.add(new Expansion("related", reopen, List.of()));
        var diagnostics = active.stream().flatMap(slot -> slot.diagnostics().stream()).distinct().toList();
        Disposition disposition = !obligations.isEmpty() ? Disposition.EXPANSION_REQUIRED
                : writable.size() > 1 ? Disposition.DEPENDENCY_GROUP : Disposition.LOCAL;
        return new Offer(VERSION, workspace.workspaceDigest(), disposition, writable, obligations, expansions, diagnostics, "");
    }

    public static Grant expandRepairScope(RepairWorkspaceSnapshot workspace, String expectedDigest, List<String> selections) {
        return expandRepairScope(workspace, expectedDigest, selections, RepairDiagnosticRegistry.core());
    }

    public static Grant expandRepairScope(RepairWorkspaceSnapshot workspace, String expectedDigest, List<String> selections,
                                         RepairDiagnosticRegistry registry) {
        Offer offer = inspectRepair(workspace, registry);
        if (!offer.workspaceDigest().equals(expectedDigest)) throw new IllegalArgumentException("Stale repair workspace digest");
        if (offer.disposition() == Disposition.UNSUPPORTED) throw new IllegalArgumentException(offer.reason());
        var slots = new TreeSet<>(offer.slots());
        var obligations = new TreeSet<String>();
        var chosen = new TreeSet<String>();
        for (String id : selections) {
            if (!chosen.add(id)) throw new IllegalArgumentException("Duplicate repair expansion: " + id);
            Expansion expansion = offer.expansions().stream().filter(item -> item.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Repair expansion is not granted: " + id));
            slots.addAll(expansion.slots()); obligations.addAll(expansion.obligations());
        }
        String digest = DealCompilerWorkspace.digest(CompilerProtocolJson.encode(List.of(
                VERSION, expectedDigest, List.copyOf(slots), List.copyOf(obligations), List.copyOf(chosen))));
        return new Grant(VERSION, expectedDigest, List.copyOf(slots), List.copyOf(obligations), List.copyOf(chosen), digest);
    }

    public static void validateGrant(RepairWorkspaceSnapshot workspace, Grant grant) {
        validateGrant(workspace, grant, RepairDiagnosticRegistry.core());
    }

    public static void validateGrant(RepairWorkspaceSnapshot workspace, Grant grant, RepairDiagnosticRegistry registry) {
        Grant expected = expandRepairScope(workspace, grant.workspaceDigest(), grant.expansions(), registry);
        if (!expected.equals(grant)) throw new IllegalArgumentException("Repair grant was altered");
    }

    public static List<DealCompilerWorkspace.AddDeclaration> validateDependencies(
            RepairWorkspaceSnapshot workspace, Grant grant, List<Dependency> additions, String modulePath) {
        return validateDependencies(workspace, grant, additions, modulePath, RepairDiagnosticRegistry.core());
    }

    public static List<DealCompilerWorkspace.AddDeclaration> validateDependencies(
            RepairWorkspaceSnapshot workspace, Grant grant, List<Dependency> additions, String modulePath,
            RepairDiagnosticRegistry registry) {
        validateGrant(workspace, grant, registry);
        Offer offer = inspectRepair(workspace, registry);
        var seen = new HashSet<String>();
        var result = new ArrayList<DealCompilerWorkspace.AddDeclaration>();
        for (var addition : additions) {
            if (!grant.obligations().contains(addition.obligation()) || !seen.add(addition.obligation()))
                throw new IllegalArgumentException("Dependency obligation is not granted or is duplicated");
            var obligation = offer.obligations().stream().filter(item -> item.id().equals(addition.obligation())).findFirst().orElseThrow();
            DealCompilerWorkspace.validateDependencyDeclaration(addition.declaration(), modulePath, obligation.missing());
            result.add(new DealCompilerWorkspace.AddDeclaration(new SemanticId("deal:module:" + modulePath), addition.declaration()));
        }
        return result;
    }

    private static void requireWorkspace(RepairWorkspaceSnapshot workspace) {
        if (!DealCompilerWorkspace.validRepairWorkspace(workspace)) throw new IllegalArgumentException("Invalid repair workspace digest");
    }
    private static Offer unsupported(RepairWorkspaceSnapshot workspace, String reason) {
        return new Offer(VERSION, workspace.workspaceDigest(), Disposition.UNSUPPORTED,
                List.of(), List.of(), List.of(), List.of(), reason);
    }
}
