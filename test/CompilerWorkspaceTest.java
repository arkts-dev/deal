package deal.test;

import deal.compiler.CompilerProtocol;
import deal.compiler.CompilerProtocolJson;
import deal.compiler.DealCompilerWorkspace;

import java.util.List;
import java.util.Map;

/** Stateless compiler protocol and atomic semantic edit tests. */
public final class CompilerWorkspaceTest {
    private CompilerWorkspaceTest() {}

    public static void main(String[] args) {
        deterministicInspectionAndRelationships();
        staleDigestRejectsWithoutMutation();
        functionReplacementIsAtomic();
        completeFunctionCannotMasqueradeAsBody();
        blockReplacementUsesRevisionScopedIdentity();
        schemaChangeRequiresStateReset();
        capabilitiesChangeAtomicallyWithProgramState();
        invalidCapabilitiesRemainRepairable();
        declarationRepairUsesExactOperation();
        duplicateClassDoesNotRejectCapabilities();
        protocolJsonIsDeterministicAndUnicodeSafe();
        protocolJsonSupportsDesugaredRecordShape();
        declarationsCanBeAddedAndRemovedAtomically();
        addingMultipleDeclarationsIsRejectedAtomically();
        annotatedHandlerCountsAsOneDeclaration();
        declarationReplacementIsAtomicAndDoesNotConsumeItsNeighbor();
        semanticQueriesExposeScopedOperations();
        checkedChangesRequireQueriedTargetFingerprint();
        inspectChangeBuildsCompilerOwnedDependencyCone();
        repairWorkspacePreservesAndPatchesSlots();
        repairWorkspaceRejectsCanonicalNoOpPatch();
        repairWorkspaceCanDropAnIndependentRejectedDeclaration();
        dependentRepairSlotsCommitAsOneGroup();
        dependentValidationRejectsOnlyTheFaultyHandler();
        frameworkDiagnosticKeepsRelatedActionStaged();
        fullCandidateDiagnosticsOwnDependentRepairSlots();
        numericStringDiagnosticPublishesRepairContract();
        syntaxDiagnosticsCarryCandidateEvidence();
        System.out.println("CompilerWorkspaceTest: all tests passed");
    }

    private static void syntaxDiagnosticsCarryCandidateEvidence() {
        String source = "// unicode \uD83D\uDE00\r\nexport function broken(): int { if (true false) { return 1; } return 0; }\r\n";
        var diagnostic = DealCompilerWorkspace.inspect(source, "app.deal").diagnostics().stream()
                .filter(value -> value.code().equals("E1015")).findFirst().orElseThrow();
        check(diagnostic.context() != null && diagnostic.context().excerpt().contains("true false"),
                "syntax diagnostics must show the rejected source");
        check(diagnostic.context().sourceDigest().equals(DealCompilerWorkspace.digest(source)),
                "evidence must identify the exact candidate");
        check(diagnostic.context().firstLine() == 1 && diagnostic.range().startLine() == 2,
                "CRLF lines must remain aligned");
        check(diagnostic.notes().stream().anyMatch(note -> note.message().contains("Expected token")
                        && note.message().contains("false")), "expected/found token note must survive protocol conversion");
        String longSource = "\uD83D\uDE00".repeat(600);
        var clipped = new CompilerProtocol.StructuredDiagnostic("TEST", "error", "test",
                new CompilerProtocol.SourceRange("app.deal", 1, 590, 1, 591), diagnostic.ownerId(),
                "", "", List.of(), List.of(), "").withSourceContext(longSource).context();
        check(clipped.truncated() && clipped.excerpt().codePointCount(0, clipped.excerpt().length()) == 512,
                "bounded evidence must not split supplementary Unicode characters");
    }

    private static void numericStringDiagnosticPublishesRepairContract() {
        String source = "export class AppState { label: string = \"\"; count: int = 0; }\n"
                + "export function initialState(): AppState { "
                + "return {label: \"Count: \" + 1, count: 1}; }\n";
        var diagnostic = DealCompilerWorkspace.inspect(source, "app.deal").diagnostics().stream()
                .filter(value -> value.code().equals("E3010"))
                .findFirst().orElseThrow();
        check(diagnostic.expected().contains("no implicit coercion"),
                "numeric/string '+' must publish a machine-readable repair constraint: " + diagnostic);
    }

    private static void capabilitiesChangeAtomicallyWithProgramState() {
        String source = source();
        var inspection = DealCompilerWorkspace.inspect(source, "app.deal");
        var moduleSlice = DealCompilerWorkspace.queryModule(source, "app.deal");
        var descriptor = moduleSlice.allowedOperations().stream()
                .filter(value -> value.operation().equals(DealCompilerWorkspace.SET_CAPABILITIES))
                .findFirst().orElseThrow();
        var changed = DealCompilerWorkspace.applyChecked(
                source,
                "app.deal",
                new CompilerProtocol.ChangeSetPrecondition(
                        inspection.sourceDigest(),
                        Map.of(inspection.moduleId().value(), descriptor.targetFingerprint())),
                List.of(new DealCompilerWorkspace.SetCapabilities(
                        inspection.moduleId(), List.of("pointer", "clock.frame", "clock.frame"))));
        check(changed.accepted(), "capability replacement must be a checked compiler operation");
        check(changed.inspection().appInterface().capabilities().equals(List.of("clock.frame", "pointer")),
                "capabilities must be unique and canonical: " + changed.inspection().appInterface().capabilities());
        check(changed.source().startsWith("// generated-capability: clock.frame\n"
                        + "// generated-capability: pointer\n"),
                "compiler must own the source projection of capabilities");

        var invalid = DealCompilerWorkspace.apply(
                changed.source(), "app.deal", changed.sourceDigest(),
                List.of(new DealCompilerWorkspace.SetCapabilities(
                        changed.inspection().moduleId(), List.of("Clock Frame"))));
        check(!invalid.accepted() && invalid.source().equals(changed.source())
                        && invalid.diagnostics().get(0).code().equals("CP1030"),
                "invalid capability replacement must roll back atomically");
    }

    private static void duplicateClassDoesNotRejectCapabilities() {
        String source = "export class Value { count: int = 0; }\n";
        var inspection = DealCompilerWorkspace.inspect(source, "app.deal");
        var change = DealCompilerWorkspace.inspectChange(source, "app.deal", inspection.sourceDigest(),
                List.of(inspection.moduleId()),
                List.of(DealCompilerWorkspace.ADD_DECLARATION, DealCompilerWorkspace.SET_CAPABILITIES));
        var precondition = new CompilerProtocol.ChangeSetPrecondition(inspection.sourceDigest(),
                Map.of(inspection.moduleId().value(), inspection.sourceDigest()));
        var staged = DealCompilerWorkspace.stageChange(source, "app.deal", precondition, change,
                List.of(new DealCompilerWorkspace.AddDeclaration(inspection.moduleId(),
                                "export class Value { count: int = 1; }"),
                        new DealCompilerWorkspace.SetCapabilities(inspection.moduleId(), List.of("clock.frame"))));
        var rejected = staged.workspace().slots().stream()
                .filter(slot -> slot.status() == CompilerProtocol.RepairSlotStatus.REJECTED).toList();
        check(rejected.size() == 1 && rejected.get(0).operation().equals(DealCompilerWorkspace.ADD_DECLARATION),
                "class diagnostic must target its declaration, not unrelated module capabilities: " + rejected);
    }

    private static void declarationRepairUsesExactOperation() {
        for (boolean reverse : List.of(false, true)) {
            for (boolean syntaxFailure : List.of(false, true)) {
                String source = "export class State { paused: boolean = false; }\n";
                String action = "export class Input { delta: int = 0; }";
                String valid = "export function read(state: State, action: Input): int { if (!state.paused) { return 1; } return 0; }";
                String bad = syntaxFailure ? valid.replace("!state.paused", "state.paused == false")
                        : valid + "\nexport class Extra { value: int = 0; }";
                var base = DealCompilerWorkspace.inspect(source, "app.deal");
                var inspected = DealCompilerWorkspace.inspectChange(source, "app.deal", base.sourceDigest(),
                        List.of(base.moduleId()), List.of(DealCompilerWorkspace.ADD_DECLARATION));
                var precondition = new CompilerProtocol.ChangeSetPrecondition(base.sourceDigest(),
                        Map.of(base.moduleId().value(), base.sourceDigest()));
                var goodOp = new DealCompilerWorkspace.AddDeclaration(base.moduleId(), action);
                var badOp = new DealCompilerWorkspace.AddDeclaration(base.moduleId(), bad);
                var staged = DealCompilerWorkspace.stageChange(source, "app.deal", precondition, inspected,
                        reverse ? List.of(badOp, goodOp) : List.of(goodOp, badOp));
                int badIndex = reverse ? 0 : 1;
                var rejected = staged.workspace().slots().stream()
                        .filter(slot -> slot.status() == CompilerProtocol.RepairSlotStatus.REJECTED).toList();
                check(rejected.size() == 1 && rejected.get(0).slotId().equals("R" + (badIndex + 1)),
                        "only the failing operation is editable regardless of addition order: " + rejected);
                check(staged.diagnostics().stream().allMatch(d -> d.operationIndex() == badIndex),
                        "diagnostics must retain their transaction-local operation identity");
                if (syntaxFailure) {
                    check(rejected.get(0).diagnostics().stream().anyMatch(d -> d.code().equals("E1015")
                                    && d.context().excerpt().contains("state.paused == false")
                                    && d.notes().stream().anyMatch(n -> n.message().contains("EQ '=='"))),
                            "parser token evidence must survive declaration validation and slot routing");
                }
                var untouched = staged.workspace().slots().get(reverse ? 1 : 0);
                var repaired = DealCompilerWorkspace.patchRepairWorkspace(source, "app.deal", staged.workspace(),
                        List.of(new CompilerProtocol.SlotPatch(rejected.get(0).slotId(), Map.of("declaration", valid))));
                check(repaired.accepted(), "correcting only the failed declaration must commit: " + repaired.diagnostics());
                check(repaired.workspace().slots().get(reverse ? 1 : 0).payloadFingerprint()
                                .equals(untouched.payloadFingerprint()), "accepted sibling payload must not change");
                check(staged.source().equals(source), "a rejected batch must preserve the committed source");
            }
        }
    }

    private static void invalidCapabilitiesRemainRepairable() {
        String source = source();
        var inspection = DealCompilerWorkspace.inspect(source, "app.deal");
        var change = DealCompilerWorkspace.inspectChange(source, "app.deal", inspection.sourceDigest(),
                List.of(inspection.moduleId()), List.of(DealCompilerWorkspace.SET_CAPABILITIES));
        var precondition = new CompilerProtocol.ChangeSetPrecondition(inspection.sourceDigest(),
                Map.of(inspection.moduleId().value(), inspection.sourceDigest()));
        var staged = DealCompilerWorkspace.stageChange(source, "app.deal", precondition, change,
                List.of(new DealCompilerWorkspace.SetCapabilities(inspection.moduleId(), List.of("invalid value"))));
        check(!staged.accepted() && staged.source().equals(source), "invalid capabilities must stay staged without a throw");
        var rejected = DealCompilerWorkspace.patchRepairWorkspace(source, "app.deal", staged.workspace(),
                List.of(new CompilerProtocol.SlotPatch("R1", Map.of("capabilities", "clock.frame, invalid"))));
        check(!rejected.accepted() && rejected.source().equals(source)
                        && rejected.diagnostics().stream().anyMatch(value -> value.code().equals("CP1030")),
                "invalid capability repair must return diagnostics and preserve committed source");
        var repaired = DealCompilerWorkspace.patchRepairWorkspace(source, "app.deal", rejected.workspace(),
                List.of(new CompilerProtocol.SlotPatch("R1", Map.of("capabilities", "clock.frame"))));
        check(repaired.accepted(), "a valid capability patch must recover the same workspace");
    }

    private static void repairWorkspaceCanDropAnIndependentRejectedDeclaration() {
        String source = "export class AppState { title: string = \"\"; }\n"
                + "export function initialState(): AppState { return {title: \"Ready\"}; }\n";
        var inspection = DealCompilerWorkspace.inspect(source, "app.deal");
        var moduleOperation = DealCompilerWorkspace.queryModule(source, "app.deal").allowedOperations().stream()
                .filter(value -> value.operation().equals(DealCompilerWorkspace.ADD_DECLARATION))
                .findFirst().orElseThrow();
        var precondition = new CompilerProtocol.ChangeSetPrecondition(
                inspection.sourceDigest(), Map.of(
                        inspection.moduleId().value(), moduleOperation.targetFingerprint()));
        var operations = List.<DealCompilerWorkspace.Operation>of(
                new DealCompilerWorkspace.AddDeclaration(
                        inspection.moduleId(), "export function placeholder(state: AppState): void { return state; }"),
                new DealCompilerWorkspace.AddDeclaration(
                        inspection.moduleId(), "export class Marker { id: int = 0; }"));
        var changeInspection = DealCompilerWorkspace.inspectChange(
                source, "app.deal", inspection.sourceDigest(), List.of(inspection.moduleId()),
                List.of(DealCompilerWorkspace.ADD_DECLARATION));
        var staged = DealCompilerWorkspace.stageChange(
                source, "app.deal", precondition, changeInspection, operations);
        check(!staged.accepted(), "invalid optional declaration must open repair workspace");
        check(staged.workspace().slots().get(0).status() == CompilerProtocol.RepairSlotStatus.REJECTED
                        && staged.workspace().slots().get(1).status() == CompilerProtocol.RepairSlotStatus.STAGED,
                "independent valid declaration must remain staged: " + staged.workspace().slots());
        var dropped = DealCompilerWorkspace.patchRepairWorkspace(
                source, "app.deal", staged.workspace(), List.of(CompilerProtocol.SlotPatch.drop("R1")));
        check(dropped.accepted(), "dropping an independent rejected declaration must commit staged siblings");
        check(!dropped.source().contains("placeholder") && dropped.source().contains("class Marker"),
                "drop must remove only the rejected operation");
    }

    private static void repairWorkspaceRejectsCanonicalNoOpPatch() {
        String seed = "export class AppState { count: int = 0; }\n"
                + "export class IncrementAction {}\n"
                + "export function initialState(): AppState { return {count: 0}; }\n"
                + "export function update(state: AppState, action: IncrementAction): AppState { return state; }\n";
        var seedInspection = DealCompilerWorkspace.inspect(seed, "app.deal");
        var seedUpdate = seedInspection.symbols().stream()
                .filter(value -> value.name().equals("update")).findFirst().orElseThrow();
        var seedBody = seedInspection.nodes().stream()
                .filter(value -> value.ownerId().equals(seedUpdate.id()) && value.kind().equals("function-body"))
                .findFirst().orElseThrow();
        String source = DealCompilerWorkspace.apply(
                seed, "app.deal", seedInspection.sourceDigest(),
                List.of(new DealCompilerWorkspace.ReplaceFunctionBody(seedBody.id(), "return state;"))).source();
        var inspection = DealCompilerWorkspace.inspect(source, "app.deal");
        var update = inspection.symbols().stream()
                .filter(value -> value.name().equals("update")).findFirst().orElseThrow();
        var body = inspection.nodes().stream()
                .filter(value -> value.ownerId().equals(update.id()) && value.kind().equals("function-body"))
                .findFirst().orElseThrow();
        var descriptor = DealCompilerWorkspace.queryNode(source, "app.deal", body.id())
                .allowedOperations().get(0);
        var precondition = new CompilerProtocol.ChangeSetPrecondition(
                inspection.sourceDigest(), Map.of(body.id().value(), descriptor.targetFingerprint()));
        var changeInspection = DealCompilerWorkspace.inspectChange(
                source, "app.deal", inspection.sourceDigest(), List.of(body.id()),
                List.of(DealCompilerWorkspace.REPLACE_FUNCTION_BODY));
        var staged = DealCompilerWorkspace.stageChange(
                source, "app.deal", precondition, changeInspection,
                List.of(new DealCompilerWorkspace.ReplaceFunctionBody(body.id(), "return missing;")));
        check(!staged.accepted(), "invalid body must create a repair workspace");
        String rejectedSlot = staged.workspace().slots().stream()
                .filter(value -> value.status() == CompilerProtocol.RepairSlotStatus.REJECTED)
                .map(CompilerProtocol.RepairSlot::slotId).findFirst().orElseThrow();
        var repaired = DealCompilerWorkspace.patchRepairWorkspace(
                source, "app.deal", staged.workspace(), List.of(new CompilerProtocol.SlotPatch(
                        rejectedSlot, Map.of("body", "return state;"))));
        check(!repaired.accepted(), "repair that restores the unchanged canonical source must not commit: "
                + repaired.source().replace("\n", "\\n"));
        check(repaired.diagnostics().stream().anyMatch(value -> value.code().equals("CP1029")),
                "canonical no-op repair must publish stable CP1029");
        check(repaired.workspace().slots().stream().anyMatch(value ->
                        value.slotId().equals(rejectedSlot)
                                && value.status() == CompilerProtocol.RepairSlotStatus.REJECTED),
                "the no-op repair slot must remain writable");
    }

    private static void fullCandidateDiagnosticsOwnDependentRepairSlots() {
        String source = "export class AppState { title: string = \"\"; }\n"
                + "export function initialState(): AppState { return {title: \"\"}; }\n";
        var inspection = DealCompilerWorkspace.inspect(source, "app.deal");
        var appState = inspection.symbols().stream()
                .filter(value -> value.name().equals("AppState")).findFirst().orElseThrow();
        var initialState = inspection.symbols().stream()
                .filter(value -> value.name().equals("initialState")).findFirst().orElseThrow();
        var initialBody = inspection.nodes().stream()
                .filter(value -> value.ownerId().equals(initialState.id()) && value.kind().equals("function-body"))
                .findFirst().orElseThrow();
        var moduleOperation = DealCompilerWorkspace.queryModule(source, "app.deal").allowedOperations().stream()
                .filter(value -> value.operation().equals(DealCompilerWorkspace.ADD_DECLARATION))
                .findFirst().orElseThrow();
        var stateOperation = DealCompilerWorkspace.querySymbol(source, "app.deal", appState.id()).allowedOperations().stream()
                .filter(value -> value.operation().equals(DealCompilerWorkspace.REPLACE_DECLARATION))
                .findFirst().orElseThrow();
        var initialOperation = DealCompilerWorkspace.queryNode(source, "app.deal", initialBody.id()).allowedOperations().stream()
                .filter(value -> value.operation().equals(DealCompilerWorkspace.REPLACE_FUNCTION_BODY))
                .findFirst().orElseThrow();
        var precondition = new CompilerProtocol.ChangeSetPrecondition(
                inspection.sourceDigest(), Map.of(
                        inspection.moduleId().value(), moduleOperation.targetFingerprint(),
                        appState.id().value(), stateOperation.targetFingerprint(),
                        initialBody.id().value(), initialOperation.targetFingerprint()));
        var operations = List.<DealCompilerWorkspace.Operation>of(
                new DealCompilerWorkspace.AddDeclaration(
                        inspection.moduleId(), "export class ChartBar { id: int = 0; value: int = 0; }"),
                new DealCompilerWorkspace.ReplaceDeclaration(
                        appState.id(), "export class ChartBar { id: int = 0; value: int = 0; }"),
                new DealCompilerWorkspace.ReplaceFunctionBody(initialBody.id(), "return {items: []};"));
        var changeInspection = DealCompilerWorkspace.inspectChange(
                source, "app.deal", inspection.sourceDigest(),
                List.of(inspection.moduleId(), appState.id(), initialBody.id()),
                List.of(DealCompilerWorkspace.ADD_DECLARATION, DealCompilerWorkspace.REPLACE_DECLARATION,
                        DealCompilerWorkspace.REPLACE_FUNCTION_BODY));
        var staged = DealCompilerWorkspace.stageChange(
                source, "app.deal", precondition, changeInspection, operations);
        var rejected = staged.workspace().slots().stream()
                .filter(value -> value.status() == CompilerProtocol.RepairSlotStatus.REJECTED).toList();
        check(rejected.size() == 1 && rejected.get(0).operation().equals(DealCompilerWorkspace.REPLACE_DECLARATION),
                "full-candidate diagnostics must select the owned slot even when dependent operations fail alone: "
                        + staged.workspace().slots());
        var repaired = DealCompilerWorkspace.patchRepairWorkspace(
                source, "app.deal", staged.workspace(), List.of(new CompilerProtocol.SlotPatch(
                        rejected.get(0).slotId(), Map.of(
                                "declaration", "export class AppState { items: ChartBar[] = []; }"))));
        check(repaired.accepted(), "repairing the compiler-owned slot must commit dependent staged operations: "
                + repaired.diagnostics());
    }

    private static void dependentRepairSlotsCommitAsOneGroup() {
        String source = source();
        var inspection = DealCompilerWorkspace.inspect(source, "app.deal");
        var descriptor = DealCompilerWorkspace.queryModule(source, "app.deal")
                .allowedOperations().get(0);
        var precondition = new CompilerProtocol.ChangeSetPrecondition(
                inspection.sourceDigest(), Map.of(
                        inspection.moduleId().value(), descriptor.targetFingerprint()));
        var changeInspection = DealCompilerWorkspace.inspectChange(
                source, "app.deal", inspection.sourceDigest(), List.of(inspection.moduleId()),
                List.of(DealCompilerWorkspace.ADD_DECLARATION));
        var staged = DealCompilerWorkspace.stageChange(
                source, "app.deal", precondition, changeInspection, List.of(
                        new DealCompilerWorkspace.AddDeclaration(
                                inspection.moduleId(), "export class RunAction {}"),
                        new DealCompilerWorkspace.AddDeclaration(
                                inspection.moduleId(),
                                "export function run(state: AppState, action: RunAction): AppState { return missing; }")));
        check(!staged.accepted(), "an invalid handler must reject its dependent declaration group");
        check(staged.workspace().groups().size() == 2,
                "independent declarations must not be grouped merely because both target the module");
        var rejected = staged.workspace().slots().stream()
                .filter(value -> value.status() == CompilerProtocol.RepairSlotStatus.REJECTED)
                .findFirst().orElseThrow();
        var rejectedGroup = staged.workspace().groups().stream()
                .filter(value -> value.groupId().equals(rejected.dependencyGroupId()))
                .findFirst().orElseThrow();
        check(rejectedGroup.dependsOn().size() == 1,
                "the handler repair group must explicitly depend on its action declaration group");
        var repaired = DealCompilerWorkspace.patchRepairWorkspace(
                source, "app.deal", staged.workspace(), List.of(new CompilerProtocol.SlotPatch(
                        rejected.slotId(), Map.of("declaration",
                                "export function run(state: AppState, action: RunAction): AppState { return state; }"))));
        check(repaired.accepted(), "dependent slots must compile together after a narrow patch");
        check(repaired.source().contains("class RunAction") && repaired.source().contains("function run"),
                "the repaired group must retain both declarations");
    }

    private static void dependentValidationRejectsOnlyTheFaultyHandler() {
        String source = source();
        var inspection = DealCompilerWorkspace.inspect(source, "app.deal");
        var descriptor = DealCompilerWorkspace.queryModule(source, "app.deal")
                .allowedOperations().get(0);
        var precondition = new CompilerProtocol.ChangeSetPrecondition(
                inspection.sourceDigest(), Map.of(
                        inspection.moduleId().value(), descriptor.targetFingerprint()));
        var changeInspection = DealCompilerWorkspace.inspectChange(
                source, "app.deal", inspection.sourceDigest(), List.of(inspection.moduleId()),
                List.of(DealCompilerWorkspace.ADD_DECLARATION));
        var staged = DealCompilerWorkspace.stageChange(
                source, "app.deal", precondition, changeInspection, List.of(
                        new DealCompilerWorkspace.AddDeclaration(
                                inspection.moduleId(), "export class FirstAction {}"),
                        new DealCompilerWorkspace.AddDeclaration(
                                inspection.moduleId(),
                                "export function first(state: AppState, action: FirstAction): AppState { return state; }"),
                        new DealCompilerWorkspace.AddDeclaration(
                                inspection.moduleId(), "export class SecondAction {}"),
                        new DealCompilerWorkspace.AddDeclaration(
                                inspection.moduleId(),
                                "export function second(state: AppState, action: SecondAction): AppState { return missing; }")));
        var rejected = staged.workspace().slots().stream()
                .filter(value -> value.status() == CompilerProtocol.RepairSlotStatus.REJECTED).toList();
        check(rejected.size() == 1 && rejected.get(0).payload().get("declaration").contains("function second"),
                "dependency-aware validation must expose only the actually invalid handler: "
                        + staged.workspace().slots());
        check(staged.workspace().slots().stream()
                        .filter(value -> value.payload().get("declaration").contains("function first"))
                        .allMatch(value -> value.status() == CompilerProtocol.RepairSlotStatus.STAGED),
                "a valid handler must stay staged with its required action declaration");
    }

    private static void frameworkDiagnosticKeepsRelatedActionStaged() {
        String source = source();
        var inspection = DealCompilerWorkspace.inspect(source, "app.deal");
        var descriptor = DealCompilerWorkspace.queryModule(source, "app.deal")
                .allowedOperations().get(0);
        var precondition = new CompilerProtocol.ChangeSetPrecondition(
                inspection.sourceDigest(), Map.of(
                        inspection.moduleId().value(), descriptor.targetFingerprint()));
        String action = "export class DoneAction { setId: int = 0; }";
        String handler = "export function done(state: AppState, action: DoneAction): AppState { return state; }";
        var operations = List.<DealCompilerWorkspace.Operation>of(
                new DealCompilerWorkspace.AddDeclaration(inspection.moduleId(), action),
                new DealCompilerWorkspace.AddDeclaration(inspection.moduleId(), handler));
        var changeInspection = DealCompilerWorkspace.inspectChange(
                source, "app.deal", inspection.sourceDigest(), List.of(inspection.moduleId()),
                List.of(DealCompilerWorkspace.ADD_DECLARATION));
        var actionId = DealCompilerWorkspace.declarationSemanticId(action, "app.deal");
        var handlerId = DealCompilerWorkspace.declarationSemanticId(handler, "app.deal");
        var staged = DealCompilerWorkspace.stageChange(
                source, "app.deal", precondition, changeInspection, operations,
                (candidateSource, candidateInspection, candidateOperations) -> List.of(
                        new CompilerProtocol.StructuredDiagnostic(
                                "UI2050", "error", "borrowed state mutation", null, handlerId,
                                "immutable borrowed state", "write through alias", List.of(actionId),
                                List.of(new CompilerProtocol.RepairScope(
                                        DealCompilerWorkspace.ADD_DECLARATION, handlerId)),
                                "queryDealSymbol(" + handlerId.value() + ")")));
        var rejected = staged.workspace().slots().stream()
                .filter(value -> value.status() == CompilerProtocol.RepairSlotStatus.REJECTED).toList();
        check(rejected.size() == 1 && rejected.get(0).payload().get("declaration").contains("function done"),
                "a framework diagnostic must reject its owning handler, not its related action type: "
                        + staged.workspace().slots());
        check(staged.workspace().slots().get(0).status() == CompilerProtocol.RepairSlotStatus.STAGED,
                "the related action declaration must stay staged");
    }

    private static void inspectChangeBuildsCompilerOwnedDependencyCone() {
        String source = source();
        var inspection = DealCompilerWorkspace.inspect(source, "app.deal");
        var update = inspection.symbols().stream()
                .filter(value -> value.name().equals("update")).findFirst().orElseThrow();
        var increment = inspection.symbols().stream()
                .filter(value -> value.name().equals("increment")).findFirst().orElseThrow();
        var change = DealCompilerWorkspace.inspectChange(
                source, "app.deal", inspection.sourceDigest(), List.of(update.id()),
                List.of(DealCompilerWorkspace.REPLACE_DECLARATION));
        check(change.diagnostics().isEmpty(), "change inspection must accept a current semantic anchor");
        check(change.dependencyCone().members().stream().anyMatch(value ->
                        value.id().equals(update.id()) && value.exposure().equals("EDIT_BODY")),
                "the selected symbol must be editable");
        check(change.dependencyCone().members().stream().anyMatch(value ->
                        value.id().equals(increment.id()) && value.exposure().equals("SIGNATURE_ONLY")),
                "the compiler must publish the selected symbol's callee dependency");
        check(change.dependencyCone().edges().stream().anyMatch(value ->
                        value.from().equals(update.id()) && value.to().equals(increment.id())
                                && value.kind().equals("CALLS")),
                "the dependency cone must retain a typed call edge");
    }

    private static void repairWorkspacePreservesAndPatchesSlots() {
        String source = source();
        var inspection = DealCompilerWorkspace.inspect(source, "app.deal");
        var update = inspection.symbols().stream()
                .filter(value -> value.name().equals("update")).findFirst().orElseThrow();
        var updateBody = inspection.nodes().stream()
                .filter(value -> value.ownerId().equals(update.id()) && value.kind().equals("function-body"))
                .findFirst().orElseThrow();
        var initial = inspection.symbols().stream()
                .filter(value -> value.name().equals("initialState")).findFirst().orElseThrow();
        var initialBody = inspection.nodes().stream()
                .filter(value -> value.ownerId().equals(initial.id()) && value.kind().equals("function-body"))
                .findFirst().orElseThrow();
        var updateDescriptor = DealCompilerWorkspace.queryNode(source, "app.deal", updateBody.id())
                .allowedOperations().get(0);
        var initialDescriptor = DealCompilerWorkspace.queryNode(source, "app.deal", initialBody.id())
                .allowedOperations().get(0);
        var precondition = new CompilerProtocol.ChangeSetPrecondition(
                inspection.sourceDigest(), Map.of(
                        updateBody.id().value(), updateDescriptor.targetFingerprint(),
                        initialBody.id().value(), initialDescriptor.targetFingerprint()));
        var changeInspection = DealCompilerWorkspace.inspectChange(
                source, "app.deal", inspection.sourceDigest(), List.of(updateBody.id(), initialBody.id()),
                List.of(DealCompilerWorkspace.REPLACE_FUNCTION_BODY));
        var staged = DealCompilerWorkspace.stageChange(
                source, "app.deal", precondition, changeInspection, List.of(
                        new DealCompilerWorkspace.ReplaceFunctionBody(updateBody.id(), "return missing;"),
                        new DealCompilerWorkspace.ReplaceFunctionBody(initialBody.id(),
                                "return {count: 7};")));
        check(!staged.accepted(), "one invalid slot must keep the candidate staged");
        var rejected = staged.workspace().slots().stream()
                .filter(value -> value.status() == CompilerProtocol.RepairSlotStatus.REJECTED)
                .findFirst().orElseThrow();
        var preserved = staged.workspace().slots().stream()
                .filter(value -> !value.slotId().equals(rejected.slotId())).findFirst().orElseThrow();
        check(preserved.status() == CompilerProtocol.RepairSlotStatus.STAGED,
                "an independent valid sibling must be staged and unavailable to repair: "
                        + staged.workspace().slots());
        String preservedPayload = preserved.payload().get("body");
        var snapshot = staged.workspace();
        var tampered = new CompilerProtocol.RepairWorkspaceSnapshot(
                snapshot.workspaceId(), "tampered", snapshot.baseRevision(), snapshot.inspectionDigest(),
                snapshot.precondition(), snapshot.slots(), snapshot.groups(), snapshot.repairRound());
        var rejectedTamper = DealCompilerWorkspace.patchRepairWorkspace(
                source, "app.deal", tampered, List.of(new CompilerProtocol.SlotPatch(
                        rejected.slotId(), Map.of("body", "return increment(state);"))));
        check(rejectedTamper.diagnostics().get(0).code().equals("CP1022"),
                "a tampered stateless workspace must reject before applying a patch");
        var repaired = DealCompilerWorkspace.patchRepairWorkspace(
                source, "app.deal", staged.workspace(), List.of(new CompilerProtocol.SlotPatch(
                        rejected.slotId(), Map.of("body", "return increment(state);"))));
        check(repaired.accepted(), "patching only the rejected slot must commit the full candidate: "
                + repaired.diagnostics());
        check(repaired.source().contains("return {count: 7};"),
                "the sealed sibling payload must reach committed source");
        check(repaired.workspace().slots().stream()
                        .filter(value -> value.slotId().equals(preserved.slotId()))
                        .allMatch(value -> value.payload().get("body").equals(preservedPayload)),
                "repair must preserve the sibling payload byte-for-byte");
    }

    private static void declarationReplacementIsAtomicAndDoesNotConsumeItsNeighbor() {
        String source = "export class AppState { title: string = \"\"; }\n"
                + "export function initialState(): AppState { return {title: \"\"}; }\n";
        var inspection = DealCompilerWorkspace.inspect(source, "app.deal");
        var state = inspection.symbols().stream()
                .filter(value -> value.name().equals("AppState")).findFirst().orElseThrow();
        var slice = DealCompilerWorkspace.querySymbol(source, "app.deal", state.id());
        var descriptor = slice.allowedOperations().stream()
                .filter(value -> value.operation().equals(DealCompilerWorkspace.REPLACE_DECLARATION))
                .findFirst().orElseThrow();
        var result = DealCompilerWorkspace.applyChecked(
                source,
                "app.deal",
                new CompilerProtocol.ChangeSetPrecondition(
                        inspection.sourceDigest(), Map.of(state.id().value(), descriptor.targetFingerprint())),
                List.of(new DealCompilerWorkspace.ReplaceDeclaration(
                        state.id(), "export class AppState { count: int = 0; }")));
        check(!result.accepted(), "incompatible neighboring function must reject the full transaction");
        check(result.source().equals(source), "rejected declaration replacement must roll back exactly");

        var paired = DealCompilerWorkspace.apply(
                source,
                "app.deal",
                inspection.sourceDigest(),
                List.of(
                        new DealCompilerWorkspace.ReplaceDeclaration(
                                state.id(), "export class AppState { count: int = 0; }"),
                        new DealCompilerWorkspace.ReplaceFunctionBody(
                                inspection.nodes().get(0).id(), "return {count: 0};")));
        check(paired.accepted(), "compatible declaration and body replacement must commit: " + paired.diagnostics());
        check(paired.source().contains("export function initialState"),
                "replacing one declaration must not consume the next export token");
    }

    private static void deterministicInspectionAndRelationships() {
        String source = source();
        var first = DealCompilerWorkspace.inspect(source, "app.deal");
        var second = DealCompilerWorkspace.inspect(source, "app.deal");
        check(first.sourceDigest().equals(second.sourceDigest()), "digest must be deterministic");
        check(first.symbols().equals(second.symbols()), "symbols must be deterministic");
        var increment = first.symbols().stream().filter(value -> value.name().equals("increment")).findFirst().orElseThrow();
        var update = first.symbols().stream().filter(value -> value.name().equals("update")).findFirst().orElseThrow();
        check(update.callees().contains(increment.id()), "update must call increment");
        check(increment.callers().contains(update.id()), "increment must report update caller");
        check(first.appInterface() != null, "generated app interface must be extracted upstream");
    }

    private static void staleDigestRejectsWithoutMutation() {
        String source = source();
        var inspection = DealCompilerWorkspace.inspect(source, "app.deal");
        var functionBody = inspection.nodes().stream()
                .filter(value -> value.kind().equals("function-body"))
                .findFirst().orElseThrow();
        var result = DealCompilerWorkspace.apply(
                source,
                "app.deal",
                "stale",
                List.of(new DealCompilerWorkspace.ReplaceFunctionBody(functionBody.id(), "return 7;")));
        check(!result.accepted(), "stale digest must reject");
        check(result.source().equals(source), "stale edit must preserve source");
        check(result.diagnostics().get(0).code().equals("CP1001"), "stale edit must be structured");
    }

    private static void functionReplacementIsAtomic() {
        String source = source();
        var inspection = DealCompilerWorkspace.inspect(source, "app.deal");
        var update = inspection.symbols().stream().filter(value -> value.name().equals("update")).findFirst().orElseThrow();
        var body = inspection.nodes().stream()
                .filter(value -> value.ownerId().equals(update.id()) && value.kind().equals("function-body"))
                .findFirst().orElseThrow();

        var rejected = DealCompilerWorkspace.apply(
                source,
                "app.deal",
                inspection.sourceDigest(),
                List.of(new DealCompilerWorkspace.ReplaceFunctionBody(body.id(), "return missing;")));
        check(!rejected.accepted(), "invalid replacement must reject");
        check(rejected.source().equals(source), "invalid replacement must roll back");
        check(rejected.diagnostics().stream().allMatch(value ->
                        value.ownerId().equals(update.id())
                                && value.repairScopes().contains(new CompilerProtocol.RepairScope(
                                        DealCompilerWorkspace.REPLACE_FUNCTION_BODY, body.id()))),
                "diagnostic ownership must retain the symbol while repair remains scoped to the rejected node");

        var accepted = DealCompilerWorkspace.apply(
                source,
                "app.deal",
                inspection.sourceDigest(),
                List.of(new DealCompilerWorkspace.ReplaceFunctionBody(body.id(), "return increment(state);")));
        check(accepted.accepted(), "valid replacement must commit");
        check(!accepted.sourceDigest().equals(inspection.sourceDigest()), "accepted source gets a new revision");
        check(accepted.impact().changedSymbols().equals(List.of(update.id())), "impact identifies exact symbol");
    }

    private static void completeFunctionCannotMasqueradeAsBody() {
        String source = source();
        var inspection = DealCompilerWorkspace.inspect(source, "app.deal");
        var update = inspection.symbols().stream().filter(value -> value.name().equals("update")).findFirst().orElseThrow();
        var body = inspection.nodes().stream()
                .filter(value -> value.ownerId().equals(update.id()) && value.kind().equals("function-body"))
                .findFirst().orElseThrow();
        var rejected = DealCompilerWorkspace.apply(
                source,
                "app.deal",
                inspection.sourceDigest(),
                List.of(new DealCompilerWorkspace.ReplaceFunctionBody(
                        body.id(),
                        "export function update(state: AppState, action: IncrementAction): AppState { return state; }")));
        check(!rejected.accepted(), "complete declaration must not be accepted as a function body");
        check(rejected.source().equals(source), "mis-shaped body edit must be atomic");
        check(rejected.diagnostics().get(0).code().equals("CP1007"),
                "mis-shaped body edit must have a stable diagnostic");
        check(rejected.diagnostics().get(0).repairScopes().get(0).ownerId().equals(body.id()),
                "repair must remain scoped to the requested body");
    }

    private static void blockReplacementUsesRevisionScopedIdentity() {
        String source = source();
        var inspection = DealCompilerWorkspace.inspect(source, "app.deal");
        var nested = inspection.nodes().stream().filter(value -> value.kind().equals("block")).findFirst().orElseThrow();
        var accepted = DealCompilerWorkspace.apply(
                source,
                "app.deal",
                inspection.sourceDigest(),
                List.of(new DealCompilerWorkspace.ReplaceBlockBody(nested.id(), "return increment(state);")));
        check(accepted.accepted(), "nested block replacement must compile");
        var staleNode = DealCompilerWorkspace.apply(
                accepted.source(),
                "app.deal",
                accepted.sourceDigest(),
                List.of(new DealCompilerWorkspace.ReplaceBlockBody(nested.id(), "return state;")));
        check(!staleNode.accepted(), "node id from previous revision must reject");
        check(staleNode.diagnostics().get(0).code().equals("CP1003"), "stale node has stable diagnostic");
    }

    private static void schemaChangeRequiresStateReset() {
        String before = source();
        String after = before.replace("count: int = 0;", "count: int = 0;\n  label: string = \"\";");
        var beforeInspection = DealCompilerWorkspace.inspect(before, "app.deal");
        var afterInspection = DealCompilerWorkspace.inspect(after, "app.deal");
        check(!beforeInspection.appInterface().rootSchemaFingerprint()
                .equals(afterInspection.appInterface().rootSchemaFingerprint()), "schema fingerprint must change");
    }

    private static void protocolJsonIsDeterministicAndUnicodeSafe() {
        var inspection = DealCompilerWorkspace.inspect(source(), "app.deal");
        String first = CompilerProtocolJson.encode(inspection);
        String second = CompilerProtocolJson.encode(inspection);
        check(first.equals(second), "protocol JSON must be byte deterministic");
        check(CompilerProtocolJson.decode(first) != null, "protocol JSON must round trip");
        check(CompilerProtocolJson.encode(Map.of("status", "Готово")).contains("Готово"),
                "protocol JSON must preserve Unicode scalars");
    }

    private static void protocolJsonSupportsDesugaredRecordShape() {
        String encoded = CompilerProtocolJson.encode(new DesugaredRecordShape("ok", 7));
        check(encoded.equals("{\"count\":7,\"status\":\"ok\"}"),
                "desugared records must use deterministic named accessors: " + encoded);
    }

    public static final class DesugaredRecordShape {
        private final String status;
        private final int count;

        private DesugaredRecordShape(String status, int count) {
            this.status = status;
            this.count = count;
        }

        public String status() { return status; }

        public int count() { return count; }
    }

    private static void declarationsCanBeAddedAndRemovedAtomically() {
        String source = source();
        var inspection = DealCompilerWorkspace.inspect(source, "app.deal");
        var added = DealCompilerWorkspace.apply(
                source,
                "app.deal",
                inspection.sourceDigest(),
                List.of(new DealCompilerWorkspace.AddDeclaration(
                        inspection.moduleId(),
                        "function scoreBonus(value: int): int { return value + 10; }")));
        check(added.accepted(), "valid declaration must be added: " + added.diagnostics());
        var bonus = added.inspection().symbols().stream()
                .filter(value -> value.name().equals("scoreBonus")).findFirst().orElseThrow();
        check(added.impact().changedSymbols().contains(bonus.id()), "addition must appear in impact report");

        var invalid = DealCompilerWorkspace.apply(
                source,
                "app.deal",
                inspection.sourceDigest(),
                List.of(new DealCompilerWorkspace.AddDeclaration(
                        inspection.moduleId(),
                        "function broken(value: int): int { return missing; }")));
        check(!invalid.accepted() && invalid.source().equals(source), "invalid declaration must roll back");

        var removed = DealCompilerWorkspace.apply(
                added.source(),
                "app.deal",
                added.sourceDigest(),
                List.of(new DealCompilerWorkspace.RemoveDeclaration(bonus.id())));
        check(removed.accepted(), "unreferenced declaration must be removable: " + removed.diagnostics());
        check(removed.inspection().symbols().stream().noneMatch(value -> value.name().equals("scoreBonus")),
                "removed declaration must disappear from inspection");
    }

    private static void addingMultipleDeclarationsIsRejectedAtomically() {
        String source = source();
        var inspection = DealCompilerWorkspace.inspect(source, "app.deal");
        var result = DealCompilerWorkspace.apply(
                source,
                "app.deal",
                inspection.sourceDigest(),
                List.of(new DealCompilerWorkspace.AddDeclaration(
                        inspection.moduleId(),
                        "export class FirstAction {}\nexport class SecondAction {}")));
        check(!result.accepted(), "one add operation must not smuggle multiple declarations");
        check(result.source().equals(source), "multi-declaration add rejection must preserve source");
        check(result.diagnostics().stream().anyMatch(value -> value.code().equals("CP1013")),
                "multi-declaration add must expose stable CP1013");
    }

    private static void annotatedHandlerCountsAsOneDeclaration() {
        String source = source();
        var inspection = DealCompilerWorkspace.inspect(source, "app.deal");
        var result = DealCompilerWorkspace.apply(
                source,
                "app.deal",
                inspection.sourceDigest(),
                List.of(new DealCompilerWorkspace.AddDeclaration(
                        inspection.moduleId(),
                        "// @ui-update\nexport function secondUpdate(state: AppState, action: IncrementAction): AppState { return state; }")));
        check(result.diagnostics().stream().noneMatch(value -> value.code().equals("CP1013")),
                "a framework directive plus its declaration must pass declaration cardinality");
    }

    private static void semanticQueriesExposeScopedOperations() {
        String source = source();
        var inspection = DealCompilerWorkspace.inspect(source, "app.deal");
        var update = inspection.symbols().stream()
                .filter(value -> value.name().equals("update"))
                .findFirst().orElseThrow();
        var symbolSlice = DealCompilerWorkspace.querySymbol(source, "app.deal", update.id());
        check(symbolSlice.revision().sourceDigest().equals(inspection.sourceDigest()),
                "query must bind its context to the current source revision");
        check(symbolSlice.source().contains("function update"), "symbol query must return only owned source");
        check(symbolSlice.allowedOperations().stream()
                        .anyMatch(value -> value.operation().equals(DealCompilerWorkspace.REPLACE_FUNCTION_BODY)),
                "function query must expose its compiler-owned body operation");
        check(symbolSlice.allowedOperations().stream()
                        .noneMatch(value -> value.operation().equals(DealCompilerWorkspace.ADD_DECLARATION)),
                "symbol query must not authorize unrelated module edits");

        var moduleSlice = DealCompilerWorkspace.queryModule(source, "app.deal");
        check(moduleSlice.source().isEmpty(), "module query must not disclose the complete source");
        check(moduleSlice.allowedOperations().stream().map(CompilerProtocol.OperationDescriptor::operation)
                        .collect(java.util.stream.Collectors.toSet())
                        .equals(java.util.Set.of(
                                DealCompilerWorkspace.ADD_DECLARATION,
                                DealCompilerWorkspace.SET_CAPABILITIES)),
                "module query must authorize declaration insertion and capability replacement");
    }

    private static void checkedChangesRequireQueriedTargetFingerprint() {
        String source = source();
        var inspection = DealCompilerWorkspace.inspect(source, "app.deal");
        var update = inspection.symbols().stream()
                .filter(value -> value.name().equals("update"))
                .findFirst().orElseThrow();
        var body = inspection.nodes().stream()
                .filter(value -> value.ownerId().equals(update.id()) && value.kind().equals("function-body"))
                .findFirst().orElseThrow();
        var slice = DealCompilerWorkspace.queryNode(source, "app.deal", body.id());
        var descriptor = slice.allowedOperations().get(0);
        var operation = new DealCompilerWorkspace.ReplaceFunctionBody(body.id(), "return increment(state);");

        var missing = DealCompilerWorkspace.applyChecked(
                source,
                "app.deal",
                new CompilerProtocol.ChangeSetPrecondition(inspection.sourceDigest(), Map.of()),
                List.of(operation));
        check(!missing.accepted() && missing.diagnostics().get(0).code().equals("CP1010"),
                "unqueried targets must not be writable");

        var stale = DealCompilerWorkspace.applyChecked(
                source,
                "app.deal",
                new CompilerProtocol.ChangeSetPrecondition(
                        inspection.sourceDigest(), Map.of(body.id().value(), "stale")),
                List.of(operation));
        check(!stale.accepted() && stale.diagnostics().get(0).code().equals("CP1011"),
                "stale target fingerprints must reject atomically");

        var accepted = DealCompilerWorkspace.applyChecked(
                source,
                "app.deal",
                new CompilerProtocol.ChangeSetPrecondition(
                        inspection.sourceDigest(),
                        Map.of(body.id().value(), descriptor.targetFingerprint())),
                List.of(operation));
        check(accepted.accepted(), "queried current target must be writable: " + accepted.diagnostics());
    }

    private static String source() {
        return """
                export class AppState {
                  count: int = 0;
                }
                export class IncrementAction {
                  amount: int = 1;
                }
                export function initialState(): AppState {
                  return {count: 0};
                }
                function increment(state: AppState): AppState {
                  return {count: state.count + 1};
                }
                export function update(state: AppState, action: IncrementAction): AppState {
                  if (action.amount > 0) {
                    return increment(state);
                  }
                  return state;
                }
                """;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
