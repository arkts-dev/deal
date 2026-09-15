package deal.test;

import deal.compiler.*;
import deal.compiler.CompilerProtocol.*;
import java.util.*;

public final class RepairWorkspaceV2Test {
    private static final String MODULE = "app.deal";
    private static final String BASE = "export function main(): int { return 0; }\n";
    public static void main(String[] args) {
        missingTypeHasExactGrant();
        missingFunctionHasArity();
        unsupportedPreconditionDoesNotOfferPatch();
        dependenciesUseSyntaxAndScopes();
        dependentProviderCanBeReopened();
        System.out.println("RepairWorkspaceV2Test: all tests passed");
    }
    private static RepairWorkspaceSnapshot stage(String source, List<DealCompilerWorkspace.Operation> operations) {
        var inspection = DealCompilerWorkspace.inspectChange(source, MODULE, DealCompilerWorkspace.digest(source),
                operations.stream().map(DealCompilerWorkspace.Operation::targetId).distinct().toList(),
                List.of(DealCompilerWorkspace.ADD_DECLARATION, DealCompilerWorkspace.REPLACE_FUNCTION_BODY));
        var fingerprints = new LinkedHashMap<String, String>();
        inspection.allowedOperations().forEach(op -> fingerprints.put(op.targetId().value(), op.targetFingerprint()));
        var result = DealCompilerWorkspace.stageChange(source, MODULE,
                new ChangeSetPrecondition(DealCompilerWorkspace.digest(source), fingerprints), inspection, operations);
        check(!result.accepted(), "fixture must require repair");
        return result.workspace();
    }
    private static void missingTypeHasExactGrant() {
        var module = DealCompilerWorkspace.inspect(BASE, MODULE).moduleId();
        var workspace = stage(BASE, List.of(
                new DealCompilerWorkspace.AddDeclaration(module, "export class Holder { value: Leaf = {value:1}; }"),
                new DealCompilerWorkspace.AddDeclaration(module, "export class Unrelated { value: int = 7; }")));
        String snapshot = CompilerProtocolJson.encode(workspace);
        var offer = RepairWorkspaceProtocol.inspectRepair(workspace);
        check(offer.disposition() == RepairWorkspaceProtocol.Disposition.EXPANSION_REQUIRED, "missing type needs expansion: " + CompilerProtocolJson.encode(offer));
        check(offer.obligations().getFirst().missing().name().equals("Leaf"), "AST-owned type name survives diagnostics");
        var grant = RepairWorkspaceProtocol.expandRepairScope(workspace, workspace.workspaceDigest(), List.of("add:D1"));
        check(snapshot.equals(CompilerProtocolJson.encode(workspace)), "expansion cannot change candidate");
        check(grant.equals(RepairWorkspaceProtocol.expandRepairScope(workspace, workspace.workspaceDigest(), List.of("add:D1"))),
                "grant is deterministic");
        var forged = new RepairWorkspaceProtocol.Grant(grant.version(), grant.workspaceDigest(), List.of("R1", "R2"),
                grant.obligations(), grant.expansions(), grant.digest());
        check(!apply(workspace, forged, List.of(), "export class Leaf { value: int = 0; }").accepted(),
                "tampered grant cannot widen write access");
        boolean stale = false;
        try { RepairWorkspaceProtocol.expandRepairScope(workspace, "stale", List.of("add:D1")); }
        catch (IllegalArgumentException expected) { stale = true; }
        check(stale, "stale expansion rejected");
        var wrong = apply(workspace, grant, List.of(), "export class Other { value: int = 0; }");
        check(!wrong.accepted() && wrong.source().equals(BASE) && wrong.workspace().equals(workspace), "unrelated addition rolls back");
        var illegal = apply(workspace, grant, List.of(new SlotPatch("R2", Map.of("declaration",
                "export class Unrelated { value: int = 99; }"))), "export class Leaf { value: int = 0; }");
        check(!illegal.accepted() && illegal.source().equals(BASE), "independent slot is not writable");
        var repaired = apply(workspace, grant, List.of(), "export class Leaf { value: int = 0; }");
        check(repaired.accepted(), "exact missing declaration repairs candidate: " + repaired.diagnostics());
        check(repaired.workspace().slots().get(1).payloadFingerprint().equals(workspace.slots().get(1).payloadFingerprint()),
                "unrelated slot fingerprint preserved");
        boolean old = false;
        try { RepairWorkspaceProtocol.validateGrant(repaired.workspace(), grant); }
        catch (IllegalArgumentException expected) { old = true; }
        check(old, "grant expires on workspace revision");
    }
    private static void unsupportedPreconditionDoesNotOfferPatch() {
        var symbol = DealCompilerWorkspace.inspect(BASE, MODULE).symbols().stream()
                .filter(s -> s.name().equals("main")).findFirst().orElseThrow();
        var workspace = stage(BASE, List.of(new DealCompilerWorkspace.ReplaceFunctionBody(symbol.id(), "return 1;")));
        var offer = RepairWorkspaceProtocol.inspectRepair(workspace);
        check(offer.disposition() == RepairWorkspaceProtocol.Disposition.UNSUPPORTED,
                "invalid handle must not masquerade as a locally repairable body");
        check(offer.slots().isEmpty() && !offer.reason().isBlank(), "unsupported carries reason, not writable slots");
    }
    private static void dependenciesUseSyntaxAndScopes() {
        var module = DealCompilerWorkspace.inspect(BASE, MODULE).moduleId();
        var workspace = stage(BASE, List.of(
                new DealCompilerWorkspace.AddDeclaration(module, "export function helper(): int { return missing; }"),
                new DealCompilerWorkspace.AddDeclaration(module, "export function label(): string { return \"helper\"; /* helper */ }"),
                new DealCompilerWorkspace.AddDeclaration(module, "export function local(helper: int): int { return helper; }"),
                new DealCompilerWorkspace.AddDeclaration(module, "export function consumer(): int { return helper(); }")));
        String provider = workspace.slots().getFirst().dependencyGroupId();
        for (int index : List.of(1, 2)) {
            var slot = workspace.slots().get(index);
            var group = workspace.groups().stream().filter(g -> g.groupId().equals(slot.dependencyGroupId())).findFirst().orElseThrow();
            check(!group.dependsOn().contains(provider), "literals, comments and shadowed parameters must not create dependencies");
        }
        var consumer = workspace.slots().get(3);
        check(workspace.groups().stream().filter(g -> g.groupId().equals(consumer.dependencyGroupId()))
                .anyMatch(g -> g.dependsOn().contains(provider)), "real callee creates dependency");
    }
    private static void dependentProviderCanBeReopened() {
        var module = DealCompilerWorkspace.inspect(BASE, MODULE).moduleId();
        var workspace = stage(BASE, List.of(
                new DealCompilerWorkspace.AddDeclaration(module, "export function helper(): int { return 1; }"),
                new DealCompilerWorkspace.AddDeclaration(module, "export function label(): string { return helper(); }"),
                new DealCompilerWorkspace.AddDeclaration(module, "export class Independent { value: int = 3; }")));
        var initial = RepairWorkspaceProtocol.expandRepairScope(workspace, workspace.workspaceDigest(), List.of());
        var retry = DealCompilerWorkspace.applyRepairTransaction(BASE, MODULE, workspace, initial,
                List.of(new SlotPatch("R2", Map.of("declaration", "export function label(): string { return helper() + 2; }"))), List.of());
        check(!retry.accepted(), "invalid group remains staged");
        workspace = retry.workspace();
        check(workspace.slots().getFirst().status() == RepairSlotStatus.SEALED, "valid dependency survives failed repair as sealed");
        var grant = RepairWorkspaceProtocol.expandRepairScope(workspace, workspace.workspaceDigest(), List.of("related"));
        check(grant.slots().contains("R1") && !grant.slots().contains("R3"), "reopening must include provider, exclude independent declaration");
        var fixed = DealCompilerWorkspace.applyRepairTransaction(BASE, MODULE, workspace, grant, List.of(
                new SlotPatch("R1", Map.of("declaration", "export function helper(): string { return \"Ready\"; }")),
                new SlotPatch("R2", Map.of("declaration", "export function label(): string { return helper(); }"))), List.of());
        check(fixed.accepted(), "dependent signature and use repair together: " + fixed.diagnostics());
        check(fixed.workspace().slots().get(2).payloadFingerprint().equals(workspace.slots().get(2).payloadFingerprint()),
                "unrelated fingerprint survives scope expansion");
    }
    private static void missingFunctionHasArity() {
        var main = DealCompilerWorkspace.inspect(BASE, MODULE).nodes().stream()
                .filter(s -> s.kind().equals("function-body")).findFirst().orElseThrow();
        var workspace = stage(BASE, List.of(new DealCompilerWorkspace.ReplaceFunctionBody(main.id(), "return helper(2);")));
        var offer = RepairWorkspaceProtocol.inspectRepair(workspace);
        check(!offer.obligations().isEmpty(), "missing function evidence: " + CompilerProtocolJson.encode(workspace));
        check(offer.obligations().getFirst().missing().constraints().equals(List.of("arity=1")), "callee arity is structural evidence");
        var grant = RepairWorkspaceProtocol.expandRepairScope(workspace, workspace.workspaceDigest(), List.of("add:D1"));
        check(!apply(workspace, grant, List.of(), "export function helper(): int { return 1; }").accepted(), "wrong arity rejected");
        var result = apply(workspace, grant, List.of(), "export function helper(value: int): int { return value; }");
        check(result.accepted(), "new function repairs existing body: " + result.diagnostics());
    }
    private static RepairWorkspaceResult apply(RepairWorkspaceSnapshot workspace, RepairWorkspaceProtocol.Grant grant,
            List<SlotPatch> patches, String declaration) {
        return DealCompilerWorkspace.applyRepairTransaction(BASE, MODULE, workspace, grant, patches,
                List.of(new RepairWorkspaceProtocol.Dependency("D1", declaration)));
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
