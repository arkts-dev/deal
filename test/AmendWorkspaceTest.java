package deal.test;

import deal.amend.CompilerProtocol;
import deal.amend.CompilerProtocolJson;
import deal.amend.AmendWorkspace;

import java.util.List;
import java.util.Map;

/** Stateless compiler protocol and atomic semantic edit tests. */
public final class AmendWorkspaceTest {
    private AmendWorkspaceTest() {}

    public static void main(String[] args) {
        var baseline = AmendWorkspace.inspect(source(), "app.deal");
        deterministicInspectionAndRelationships();
        staleDigestRejectsWithoutMutation(baseline);
        functionReplacementIsAtomic(baseline);
        completeFunctionCannotMasqueradeAsBody(baseline);
        blockReplacementUsesRevisionScopedIdentity(baseline);
        schemaChangeRequiresStateReset(baseline);
        capabilitiesChangeAtomicallyWithProgramState(baseline);
        invalidCapabilitiesRemainRepairable(baseline);
        declarationRepairUsesExactOperation();
        duplicateClassDoesNotRejectCapabilities();
        protocolJsonIsDeterministicAndUnicodeSafe(baseline);
        protocolJsonSupportsDesugaredRecordShape();
        declarationsCanBeAddedAndRemovedAtomically(baseline);
        addingMultipleDeclarationsIsRejectedAtomically(baseline);
        annotatedHandlerCountsAsOneDeclaration(baseline);
        declarationReplacementIsAtomicAndDoesNotConsumeItsNeighbor();
        semanticQueriesExposeScopedOperations(baseline);
        checkedChangesRequireQueriedTargetFingerprint(baseline);
        inspectChangeBuildsCompilerOwnedDependencyCone(baseline);
        repairWorkspacePreservesAndPatchesSlots(baseline);
        repairWorkspaceRejectsCanonicalNoOpPatch();
        repairWorkspaceCanDropAnIndependentRejectedDeclaration();
        dependentRepairSlotsCommitAsOneGroup(baseline);
        dependentValidationRejectsOnlyTheFaultyHandler(baseline);
        frameworkDiagnosticKeepsRelatedActionStaged(baseline);
        fullCandidateDiagnosticsOwnDependentRepairSlots();
        numericStringDiagnosticPublishesRepairContract();
        syntaxDiagnosticsCarryCandidateEvidence();
        syntaxRecoveryCannotAuthorizeMissingDependencies();
        malformedListsRemainStagedUntilRechecked();
        actionabilityFactsAndRepairScope();
        smallestEditableBlockIsNotSemanticOwner();
        System.out.println("AmendWorkspaceTest: all tests passed");
    }

    private static void smallestEditableBlockIsNotSemanticOwner() {
        String clean = "export function f():number{if(true){if(true){let x:number=1;}}return 7;}";
        String invalid = clean.replace("x:number=1", "x:number=\"bad\"");
        var inspection = AmendWorkspace.inspect(invalid, "probe.deal");
        var diagnostic = inspection.diagnostics().stream().filter(d -> d.code().equals("E3001")).findFirst().orElseThrow();
        var function = inspection.symbols().stream().filter(s -> s.kind().equals("function")).findFirst().orElseThrow();
        var inner = inspection.nodes().stream().filter(n -> n.kind().equals("block"))
                .min(java.util.Comparator.comparingInt(n -> n.range().scalarLength())).orElseThrow();
        check(diagnostic.ownerId().equals(function.id())
                        && diagnostic.repairScopes().equals(List.of(new CompilerProtocol.RepairScope(
                                AmendWorkspace.REPLACE_BLOCK_BODY, inner.id()))),
                "nested failure keeps semantic declaration owner and smallest valid editable block");
        var base = AmendWorkspace.inspect(clean, "probe.deal");
        var target = base.nodes().stream().filter(n -> n.kind().equals("block"))
                .min(java.util.Comparator.comparingInt(n -> n.range().scalarLength())).orElseThrow();
        var change = AmendWorkspace.inspectChange(clean, "probe.deal", base.sourceDigest(),
                List.of(target.id()), List.of(AmendWorkspace.REPLACE_BLOCK_BODY));
        var staged = AmendWorkspace.stageChange(clean, "probe.deal",
                new CompilerProtocol.ChangeSetPrecondition(base.sourceDigest(), Map.of(target.id().value(), target.fingerprint())),
                change, List.of(new AmendWorkspace.ReplaceBlockBody(target.id(), "let x:number=\"bad\";")));
        check(!staged.accepted() && staged.source().equals(clean), "invalid nested block stays unpublished");
        var repaired = AmendWorkspace.patchRepairWorkspace(clean, "probe.deal", staged.workspace(),
                List.of(new CompilerProtocol.SlotPatch("R1", Map.of("body", "let x:number=2;"))));
        check(repaired.accepted() && repaired.source().contains("return 7;")
                        && AmendWorkspace.inspect(repaired.source(), "probe.deal").diagnostics().isEmpty(),
                "smallest-block staged repair rechecks while retaining unrelated body code: " + repaired.diagnostics());
    }

    private static void actionabilityFactsAndRepairScope() {
        String source = "// supplementary 😀\r\nexport function broken(x:Missing):number{return 1;}";
        var inspection = AmendWorkspace.inspect(source, "probe.deal");
        var diagnostics = inspection.diagnostics().stream().filter(d -> d.code().equals("E3004")).toList();
        check(diagnostics.size() == 1, "exact repeated unknown-type diagnostic must appear once: " + diagnostics);
        var diagnostic = diagnostics.get(0);
        check(diagnostic.range().origin() == deal.diagnostics.RangeOrigin.SOURCE
                        && diagnostic.range().startScalarOffset() == source.indexOf("Missing")
                        && diagnostic.range().scalarLength() == 7,
                "amendment retains the complete UTF-16 source range, including CRLF and supplementary units");
        check(diagnostic.repairScopes().equals(List.of(new CompilerProtocol.RepairScope(
                        AmendWorkspace.REPLACE_DECLARATION, diagnostic.ownerId()))),
                "signature errors require declaration repair, not body repair: " + diagnostic);
        String clean = "export function broken(x:number):number{return x;}";
        var base = AmendWorkspace.inspect(clean, "probe.deal");
        var target = base.symbols().stream().filter(s -> s.kind().equals("function")).findFirst().orElseThrow();
        var change = AmendWorkspace.inspectChange(clean, "probe.deal", base.sourceDigest(),
                List.of(target.id()), List.of(AmendWorkspace.REPLACE_DECLARATION));
        var staged = AmendWorkspace.stageChange(clean, "probe.deal",
                new CompilerProtocol.ChangeSetPrecondition(base.sourceDigest(),
                        Map.of(target.id().value(), target.fingerprint())), change,
                List.of(new AmendWorkspace.ReplaceDeclaration(target.id(),
                        "export function broken(x:Missing):number{return 1;}")));
        check(!staged.accepted() && staged.source().equals(clean), "invalid signature stays an unpublished candidate");
        var repaired = AmendWorkspace.patchRepairWorkspace(clean, "probe.deal", staged.workspace(),
                List.of(new CompilerProtocol.SlotPatch("R1", Map.of("declaration", clean))));
        check(!repaired.accepted() && repaired.diagnostics().stream().anyMatch(d -> d.code().equals("CP1029")),
                "a no-op restoration cannot masquerade as source-changing repair");
        repaired = AmendWorkspace.patchRepairWorkspace(clean, "probe.deal", staged.workspace(),
                List.of(new CompilerProtocol.SlotPatch("R1", Map.of("declaration",
                        "export function broken(x:number):number{return x + 1;}"))));
        check(repaired.accepted() && AmendWorkspace.inspect(repaired.source(), "probe.deal").diagnostics().isEmpty(),
                "advertised declaration repair must actually recheck successfully: " + repaired.diagnostics());
        var body = AmendWorkspace.inspect("export function f():number{let x:number=\"bad\";return 1;}", "probe.deal");
        var bodyDiagnostic = body.diagnostics().stream().filter(d -> d.code().equals("E3001")).findFirst().orElseThrow();
        check(bodyDiagnostic.repairScopes().equals(List.of(new CompilerProtocol.RepairScope(
                        AmendWorkspace.REPLACE_FUNCTION_BODY, bodyDiagnostic.ownerId()))),
                "body-owned errors keep body-only repair");
        var copied = bodyDiagnostic.withOperationIndex(2).withMissingSymbols(diagnostic.missingSymbols());
        check(copied.facts().equals(bodyDiagnostic.facts()) && copied.range().equals(bodyDiagnostic.range())
                        && copied.operationIndex() == 2, "operation attribution and missing facts preserve producer data");
        var two = AmendWorkspace.inspect("export function f(x:Missing,y:Missing):number{return 1;}", "probe.deal");
        check(two.diagnostics().stream().filter(d -> d.code().equals("E3004")).count() == 2,
                "independent occurrences must not be collapsed");
        var mismatch = AmendWorkspace.inspect("let x:number=\"bad\";", "probe.deal").diagnostics().stream()
                .filter(d -> d.code().equals("E3001")).findFirst().orElseThrow();
        check(mismatch.facts().expected().equals("number") && mismatch.facts().actual().equals("string"),
                "amendment forwards producer types rather than parsing prose");
        String json = CompilerProtocolJson.encode(mismatch);
        check(json.contains("\"facts\"") && json.contains("\"startScalarOffset\""), "current protocol exports facts and ranges");
        String operatorBase = "// 😀\r\nexport function run():number{return 1+2;}";
        var operatorInspection = AmendWorkspace.inspect(operatorBase, "probe.deal");
        var operatorBody = operatorInspection.nodes().stream().filter(n -> n.kind().equals("function-body"))
                .findFirst().orElseThrow();
        var rejectedOperator = AmendWorkspace.applyChecked(operatorBase, "probe.deal",
                new CompilerProtocol.ChangeSetPrecondition(operatorInspection.sourceDigest(),
                        Map.of(operatorBody.id().value(), operatorBody.fingerprint())),
                List.of(new AmendWorkspace.ReplaceFunctionBody(operatorBody.id(), "return 1+\"bad\";")));
        var operatorDiagnostic = rejectedOperator.diagnostics().stream().filter(d -> d.code().equals("E3010"))
                .findFirst().orElseThrow();
        var operator = operatorDiagnostic.facts().operator();
        check(!rejectedOperator.accepted() && rejectedOperator.source().equals(operatorBase)
                        && operator.token().equals("+") && operator.operands().get(1).type().equals("string")
                        && operator.operands().get(1).range().startScalarOffset()
                            == operatorDiagnostic.range().startScalarOffset() + 2
                        && operator.operands().get(1).range().file().equals("probe.deal")
                        && operator.operands().get(1).range().startLine() == operatorDiagnostic.range().startLine()
                        && operator.operands().get(1).range().scalarLength() == 5
                        && operatorDiagnostic.withOperationIndex(0).facts().operator().equals(operator),
                "rejected candidate keeps operator evidence in full candidate coordinates without publication: " + operatorDiagnostic);
        var repairedOperator = AmendWorkspace.applyChecked(operatorBase, "probe.deal",
                new CompilerProtocol.ChangeSetPrecondition(operatorInspection.sourceDigest(),
                        Map.of(operatorBody.id().value(), operatorBody.fingerprint())),
                List.of(new AmendWorkspace.ReplaceFunctionBody(operatorBody.id(), "return 1+7;")));
        check(repairedOperator.accepted()
                        && operator.operands().get(1).range().startScalarOffset() == repairedOperator.source().indexOf("1+7") + 2
                        && AmendWorkspace.inspect(repairedOperator.source(), "probe.deal").diagnostics().isEmpty(),
                "chosen data operand repair publishes only after clean recheck");
        String constraintBase = "export function run(value:number[],other:string[]):void{}";
        var constraintInspection = AmendWorkspace.inspect(constraintBase, "probe.deal");
        var constraintBody = constraintInspection.nodes().stream().filter(n -> n.kind().equals("function-body"))
                .findFirst().orElseThrow();
        var constraintPrecondition = new CompilerProtocol.ChangeSetPrecondition(constraintInspection.sourceDigest(),
                Map.of(constraintBody.id().value(), constraintBody.fingerprint()));
        var rejectedConstraint = AmendWorkspace.applyChecked(constraintBase, "probe.deal", constraintPrecondition,
                List.of(new AmendWorkspace.ReplaceFunctionBody(constraintBody.id(), "value=other;")));
        var constraintDiagnostic = rejectedConstraint.diagnostics().stream().filter(d -> d.code().equals("E3001"))
                .findFirst().orElseThrow();
        var constraint = constraintDiagnostic.facts().mismatch();
        check(!rejectedConstraint.accepted() && rejectedConstraint.source().equals(constraintBase)
                        && constraint.path().getFirst().kind() == deal.diagnostics.DiagnosticFacts.TypeComponentKind.ARRAY_ELEMENT
                        && constraint.expected().equals("number") && constraint.actual().equals("string")
                        && constraintDiagnostic.withOperationIndex(0).facts().mismatch().equals(constraint),
                "rejected amendment carries the actual nested constraint without publication or invented attribution");
        var repairedConstraint = AmendWorkspace.applyChecked(constraintBase, "probe.deal", constraintPrecondition,
                List.of(new AmendWorkspace.ReplaceFunctionBody(constraintBody.id(), "value=[7];")));
        check(repairedConstraint.accepted() && AmendWorkspace.inspect(repairedConstraint.source(), "probe.deal").diagnostics().isEmpty(),
                "explicitly chosen compatible operand repair rechecks before publication");
        String calls = "// 😀\r\nfunction f(value:number):void{} export function run():void{f(\"bad\");}";
        var callDiagnostic = AmendWorkspace.inspect(calls, "probe.deal").diagnostics().stream()
                .filter(d -> d.code().equals("E5001")).findFirst().orElseThrow();
        var call = callDiagnostic.facts().call();
        check(call.parameterName().equals("value") && call.parameterRange().startScalarOffset() == calls.indexOf("number")
                        && call.declarationRange().startScalarOffset() == calls.indexOf("function f")
                        && callDiagnostic.withOperationIndex(3).facts().equals(callDiagnostic.facts()),
                "amendment and operation-index copies preserve exact call evidence after UTF-16/CRLF text");
        String callJson = CompilerProtocolJson.encode(callDiagnostic);
        check(callJson.contains("\"declarationRange\"") && callJson.contains("\"parameterName\":\"value\""),
                "current protocol carries typed call declaration and parameter evidence");
        String fragmentBase = "// 😀\r\nfunction f(value:number):void{} export function run():void{}";
        var fragmentInspection = AmendWorkspace.inspect(fragmentBase, "probe.deal");
        var run = fragmentInspection.symbols().stream().filter(s -> s.name().equals("run")).findFirst().orElseThrow();
        var runBody = fragmentInspection.nodes().stream()
                .filter(n -> n.kind().equals("function-body") && n.ownerId().equals(run.id())).findFirst().orElseThrow();
        var rejectedCall = AmendWorkspace.applyChecked(fragmentBase, "probe.deal",
                new CompilerProtocol.ChangeSetPrecondition(fragmentInspection.sourceDigest(), Map.of(runBody.id().value(), runBody.fingerprint())),
                List.of(new AmendWorkspace.ReplaceFunctionBody(runBody.id(), "f(\"bad\");")));
        var candidateCall = rejectedCall.diagnostics().stream().filter(d -> d.code().equals("E5001")).findFirst()
                .orElseThrow(() -> new AssertionError("Expected candidate call mismatch: " + rejectedCall.diagnostics()));
        check(!rejectedCall.accepted() && rejectedCall.source().equals(fragmentBase)
                        && candidateCall.facts().call().equals(call)
                        && candidateCall.withOperationIndex(0).facts().equals(callDiagnostic.facts()),
                "full candidate checking and operation-attribution copies preserve exact callee facts");
    }

    private static void syntaxRecoveryCannotAuthorizeMissingDependencies() {
        String malformed = "export function initial(): Entry { return { text: \\\"bad\\\" }; }\n"
                + "export class Entry { text: string; }";
        var rejected = AmendWorkspace.inspect(malformed, "app.deal");
        check(rejected.diagnostics().stream().anyMatch(d -> d.code().startsWith("E1")),
                "malformed candidate must retain syntax diagnostics");
        check(rejected.diagnostics().stream().allMatch(d -> d.missingSymbols().isEmpty()),
                "recovery AST cannot establish that a declaration is missing");
        var missing = AmendWorkspace.inspect(
                "export function initial(): Unknown { return {}; }", "app.deal");
        check(missing.diagnostics().stream().anyMatch(d -> !d.missingSymbols().isEmpty()),
                "a syntactically valid missing dependency still receives structured repair facts");
        var repaired = AmendWorkspace.inspect(
                "export function initial(): Entry { return { text: \"good\" }; }\n"
                        + "export class Entry { text: string; }", "app.deal");
        check(repaired.diagnostics().stream().noneMatch(d -> d.severity().equals("error")),
                "ordinary resolution resumes after syntax repair");
    }

    private static void malformedListsRemainStagedUntilRechecked() {
        String clean = "// 😀\r\nfunction take(a:number,b:number):number{return a+b;} export function run():number{return take(1,2);}";
        var base = AmendWorkspace.inspect(clean, "probe.deal");
        var run = base.symbols().stream().filter(s -> s.name().equals("run")).findFirst().orElseThrow();
        var body = base.nodes().stream().filter(n -> n.kind().equals("function-body") && n.ownerId().equals(run.id()))
                .findFirst().orElseThrow();
        var change = AmendWorkspace.inspectChange(clean, "probe.deal", base.sourceDigest(), List.of(body.id()),
                List.of(AmendWorkspace.REPLACE_FUNCTION_BODY));
        for (String invalid : List.of("return take(,Missing);", "let values:number[]=[,Missing];return 1;")) {
            var staged = AmendWorkspace.stageChange(clean, "probe.deal",
                    new CompilerProtocol.ChangeSetPrecondition(base.sourceDigest(), Map.of(body.id().value(), body.fingerprint())),
                    change, List.of(new AmendWorkspace.ReplaceFunctionBody(body.id(), invalid)));
            check(!staged.accepted() && staged.source().equals(clean)
                            && staged.diagnostics().stream().anyMatch(d -> d.facts().reason().equals("syntax.expression-required"))
                            && staged.diagnostics().stream().allMatch(d -> d.missingSymbols().isEmpty() && !d.code().equals("E2001")),
                    "recovered later list values never authorize resolution or candidate publication: " + staged.diagnostics());
            var repaired = AmendWorkspace.patchRepairWorkspace(clean, "probe.deal", staged.workspace(),
                    List.of(new CompilerProtocol.SlotPatch("R1", Map.of("body", "return take(3,4);"))));
            check(repaired.accepted() && repaired.source().contains("return a+b;")
                            && AmendWorkspace.inspect(repaired.source(), "probe.deal").diagnostics().isEmpty(),
                    "revision-pinned list repair rechecks before publication and preserves unrelated function");
        }
    }

    private static void syntaxDiagnosticsCarryCandidateEvidence() {
        String source = "// unicode \uD83D\uDE00\r\nexport function broken(): number { if (true false) { return 1; } return 0; }\r\n";
        var diagnostic = AmendWorkspace.inspect(source, "app.deal").diagnostics().stream()
                .filter(value -> value.code().equals("E1015")).findFirst().orElseThrow();
        check(diagnostic.range().startLine() == 2, "CRLF lines must remain aligned");
        check(diagnostic.range().startColumn() > 1, "token range must not collapse to a point");
    }

    private static void numericStringDiagnosticPublishesRepairContract() {
        String source = "export class AppState { label: string; count: number; }\n"
                + "export function initialState(): AppState { "
                + "return {label: \"Count: \" + 1, count: 1}; }\n";
        var diagnostic = AmendWorkspace.inspect(source, "app.deal").diagnostics().stream()
                .filter(value -> value.code().equals("E3010"))
                .findFirst().orElseThrow();
        check(diagnostic.expected().contains("no implicit coercion"),
                "numeric/string '+' must publish a machine-readable repair constraint: " + diagnostic);
    }

    private static void capabilitiesChangeAtomicallyWithProgramState(CompilerProtocol.Inspection baseline) {
        String source = source();
        var inspection = baseline;
        var moduleSlice = AmendWorkspace.queryModule(source, "app.deal");
        var descriptor = moduleSlice.allowedOperations().stream()
                .filter(value -> value.operation().equals(AmendWorkspace.SET_CAPABILITIES))
                .findFirst().orElseThrow();
        var changed = AmendWorkspace.applyChecked(
                source,
                "app.deal",
                new CompilerProtocol.ChangeSetPrecondition(
                        inspection.sourceDigest(),
                        Map.of(inspection.moduleId().value(), descriptor.targetFingerprint())),
                List.of(new AmendWorkspace.SetCapabilities(
                        inspection.moduleId(), List.of("pointer", "clock.frame", "clock.frame"))));
        check(changed.accepted(), "capability replacement must be a checked compiler operation");
        check(changed.inspection().appInterface().capabilities().equals(List.of("clock.frame", "pointer")),
                "capabilities must be unique and canonical: " + changed.inspection().appInterface().capabilities());
        check(changed.source().startsWith("// generated-capability: clock.frame\n"
                        + "// generated-capability: pointer\n"),
                "compiler must own the source projection of capabilities");

        var invalid = AmendWorkspace.apply(
                changed.source(), "app.deal", changed.sourceDigest(),
                List.of(new AmendWorkspace.SetCapabilities(
                        changed.inspection().moduleId(), List.of("Clock Frame"))));
        check(!invalid.accepted() && invalid.source().equals(changed.source())
                        && invalid.diagnostics().get(0).code().equals("CP1030"),
                "invalid capability replacement must roll back atomically");
    }

    private static void duplicateClassDoesNotRejectCapabilities() {
        String source = "export class Value { count: number; }\n";
        var inspection = AmendWorkspace.inspect(source, "app.deal");
        var change = AmendWorkspace.inspectChange(source, "app.deal", inspection.sourceDigest(),
                List.of(inspection.moduleId()),
                List.of(AmendWorkspace.ADD_DECLARATION, AmendWorkspace.SET_CAPABILITIES));
        var precondition = new CompilerProtocol.ChangeSetPrecondition(inspection.sourceDigest(),
                Map.of(inspection.moduleId().value(), inspection.sourceDigest()));
        var staged = AmendWorkspace.stageChange(source, "app.deal", precondition, change,
                List.of(new AmendWorkspace.AddDeclaration(inspection.moduleId(),
                                "export class Value { count: number; }"),
                        new AmendWorkspace.SetCapabilities(inspection.moduleId(), List.of("clock.frame"))));
        var rejected = staged.workspace().slots().stream()
                .filter(slot -> slot.status() == CompilerProtocol.RepairSlotStatus.REJECTED).toList();
        check(rejected.size() == 1 && rejected.get(0).operation().equals(AmendWorkspace.ADD_DECLARATION),
                "class diagnostic must target its declaration, not unrelated module capabilities: " + rejected);
    }

    private static void declarationRepairUsesExactOperation() {
        String source = "export class State { paused: boolean; }\n";
        var base = AmendWorkspace.inspect(source, "app.deal");
        var inspected = AmendWorkspace.inspectChange(source, "app.deal", base.sourceDigest(),
                List.of(base.moduleId()), List.of(AmendWorkspace.ADD_DECLARATION));
        for (boolean reverse : List.of(false, true)) {
            for (boolean syntaxFailure : List.of(false, true)) {
                String action = "export class Input { delta: number; }";
                String valid = "export function read(state: State, action: Input): number { if (!state.paused) { return 1; } return 0; }";
                String bad = syntaxFailure ? valid.replace("!state.paused", "state.paused == false")
                        : valid + "\nexport class Extra { value: number; }";
                var precondition = new CompilerProtocol.ChangeSetPrecondition(base.sourceDigest(),
                        Map.of(base.moduleId().value(), base.sourceDigest()));
                var goodOp = new AmendWorkspace.AddDeclaration(base.moduleId(), action);
                var badOp = new AmendWorkspace.AddDeclaration(base.moduleId(), bad);
                var staged = AmendWorkspace.stageChange(source, "app.deal", precondition, inspected,
                        reverse ? List.of(badOp, goodOp) : List.of(goodOp, badOp));
                int badIndex = reverse ? 0 : 1;
                var rejected = staged.workspace().slots().stream()
                        .filter(slot -> slot.status() == CompilerProtocol.RepairSlotStatus.REJECTED).toList();
                check(rejected.size() == 1 && rejected.get(0).slotId().equals("R" + (badIndex + 1)),
                        "only the failing operation is editable regardless of addition order: " + rejected);
                check(staged.diagnostics().stream().allMatch(d -> d.operationIndex() == badIndex),
                        "diagnostics must retain their transaction-local operation identity");
                if (syntaxFailure) {
                    check(rejected.get(0).diagnostics().stream().anyMatch(d -> d.code().equals("E1015")),
                            "the syntax failure must reach the failing slot only");
                }
                var untouched = staged.workspace().slots().get(reverse ? 1 : 0);
                var repaired = AmendWorkspace.patchRepairWorkspace(source, "app.deal", staged.workspace(),
                        List.of(new CompilerProtocol.SlotPatch(rejected.get(0).slotId(), Map.of("declaration", valid))));
                check(repaired.accepted(), "correcting only the failed declaration must commit: " + repaired.diagnostics());
                check(repaired.workspace().slots().get(reverse ? 1 : 0).payloadFingerprint()
                                .equals(untouched.payloadFingerprint()), "accepted sibling payload must not change");
                check(staged.source().equals(source), "a rejected batch must preserve the committed source");
            }
        }
    }

    private static void invalidCapabilitiesRemainRepairable(CompilerProtocol.Inspection baseline) {
        String source = source();
        var inspection = baseline;
        var change = AmendWorkspace.inspectChange(source, "app.deal", inspection.sourceDigest(),
                List.of(inspection.moduleId()), List.of(AmendWorkspace.SET_CAPABILITIES));
        var precondition = new CompilerProtocol.ChangeSetPrecondition(inspection.sourceDigest(),
                Map.of(inspection.moduleId().value(), inspection.sourceDigest()));
        var staged = AmendWorkspace.stageChange(source, "app.deal", precondition, change,
                List.of(new AmendWorkspace.SetCapabilities(inspection.moduleId(), List.of("invalid value"))));
        check(!staged.accepted() && staged.source().equals(source), "invalid capabilities must stay staged without a throw");
        var rejected = AmendWorkspace.patchRepairWorkspace(source, "app.deal", staged.workspace(),
                List.of(new CompilerProtocol.SlotPatch("R1", Map.of("capabilities", "clock.frame, invalid"))));
        check(!rejected.accepted() && rejected.source().equals(source)
                        && rejected.diagnostics().stream().anyMatch(value -> value.code().equals("CP1030")),
                "invalid capability repair must return diagnostics and preserve committed source");
        var repaired = AmendWorkspace.patchRepairWorkspace(source, "app.deal", rejected.workspace(),
                List.of(new CompilerProtocol.SlotPatch("R1", Map.of("capabilities", "clock.frame"))));
        check(repaired.accepted(), "a valid capability patch must recover the same workspace");
    }

    private static void repairWorkspaceCanDropAnIndependentRejectedDeclaration() {
        String source = "export class AppState { title: string; }\n"
                + "export function initialState(): AppState { return {title: \"Ready\"}; }\n";
        var inspection = AmendWorkspace.inspect(source, "app.deal");
        var moduleOperation = AmendWorkspace.queryModule(source, "app.deal").allowedOperations().stream()
                .filter(value -> value.operation().equals(AmendWorkspace.ADD_DECLARATION))
                .findFirst().orElseThrow();
        var precondition = new CompilerProtocol.ChangeSetPrecondition(
                inspection.sourceDigest(), Map.of(
                        inspection.moduleId().value(), moduleOperation.targetFingerprint()));
        var operations = List.<AmendWorkspace.Operation>of(
                new AmendWorkspace.AddDeclaration(
                        inspection.moduleId(), "export function placeholder(state: AppState): void { return state; }"),
                new AmendWorkspace.AddDeclaration(
                        inspection.moduleId(), "export class Marker { id: number; }"));
        var changeInspection = AmendWorkspace.inspectChange(
                source, "app.deal", inspection.sourceDigest(), List.of(inspection.moduleId()),
                List.of(AmendWorkspace.ADD_DECLARATION));
        var staged = AmendWorkspace.stageChange(
                source, "app.deal", precondition, changeInspection, operations);
        check(!staged.accepted(), "invalid optional declaration must open repair workspace");
        check(staged.workspace().slots().get(0).status() == CompilerProtocol.RepairSlotStatus.REJECTED
                        && staged.workspace().slots().get(1).status() == CompilerProtocol.RepairSlotStatus.STAGED,
                "independent valid declaration must remain staged: " + staged.workspace().slots());
        var dropped = AmendWorkspace.patchRepairWorkspace(
                source, "app.deal", staged.workspace(), List.of(CompilerProtocol.SlotPatch.drop("R1")));
        check(dropped.accepted(), "dropping an independent rejected declaration must commit staged siblings");
        check(!dropped.source().contains("placeholder") && dropped.source().contains("class Marker"),
                "drop must remove only the rejected operation");
    }

    private static void repairWorkspaceRejectsCanonicalNoOpPatch() {
        String seed = "export class AppState { count: number; }\n"
                + "export class IncrementAction {}\n"
                + "export function initialState(): AppState { return {count: 0}; }\n"
                + "export function update(state: AppState, action: IncrementAction): AppState { return state; }\n";
        var seedInspection = AmendWorkspace.inspect(seed, "app.deal");
        var seedUpdate = seedInspection.symbols().stream()
                .filter(value -> value.name().equals("update")).findFirst().orElseThrow();
        var seedBody = seedInspection.nodes().stream()
                .filter(value -> value.ownerId().equals(seedUpdate.id()) && value.kind().equals("function-body"))
                .findFirst().orElseThrow();
        String source = AmendWorkspace.apply(
                seed, "app.deal", seedInspection.sourceDigest(),
                List.of(new AmendWorkspace.ReplaceFunctionBody(seedBody.id(), "return state;"))).source();
        var inspection = AmendWorkspace.inspect(source, "app.deal");
        var update = inspection.symbols().stream()
                .filter(value -> value.name().equals("update")).findFirst().orElseThrow();
        var body = inspection.nodes().stream()
                .filter(value -> value.ownerId().equals(update.id()) && value.kind().equals("function-body"))
                .findFirst().orElseThrow();
        var descriptor = AmendWorkspace.queryNode(source, "app.deal", body.id())
                .allowedOperations().get(0);
        var precondition = new CompilerProtocol.ChangeSetPrecondition(
                inspection.sourceDigest(), Map.of(body.id().value(), descriptor.targetFingerprint()));
        var changeInspection = AmendWorkspace.inspectChange(
                source, "app.deal", inspection.sourceDigest(), List.of(body.id()),
                List.of(AmendWorkspace.REPLACE_FUNCTION_BODY));
        var staged = AmendWorkspace.stageChange(
                source, "app.deal", precondition, changeInspection,
                List.of(new AmendWorkspace.ReplaceFunctionBody(body.id(), "return missing;")));
        check(!staged.accepted(), "invalid body must create a repair workspace");
        String rejectedSlot = staged.workspace().slots().stream()
                .filter(value -> value.status() == CompilerProtocol.RepairSlotStatus.REJECTED)
                .map(CompilerProtocol.RepairSlot::slotId).findFirst().orElseThrow();
        var repaired = AmendWorkspace.patchRepairWorkspace(
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
        String source = "export class AppState { title: string; }\n"
                + "export function initialState(): AppState { return {title: \"\"}; }\n";
        var inspection = AmendWorkspace.inspect(source, "app.deal");
        var appState = inspection.symbols().stream()
                .filter(value -> value.name().equals("AppState")).findFirst().orElseThrow();
        var initialState = inspection.symbols().stream()
                .filter(value -> value.name().equals("initialState")).findFirst().orElseThrow();
        var initialBody = inspection.nodes().stream()
                .filter(value -> value.ownerId().equals(initialState.id()) && value.kind().equals("function-body"))
                .findFirst().orElseThrow();
        var moduleOperation = AmendWorkspace.queryModule(source, "app.deal").allowedOperations().stream()
                .filter(value -> value.operation().equals(AmendWorkspace.ADD_DECLARATION))
                .findFirst().orElseThrow();
        var stateOperation = AmendWorkspace.querySymbol(source, "app.deal", appState.id()).allowedOperations().stream()
                .filter(value -> value.operation().equals(AmendWorkspace.REPLACE_DECLARATION))
                .findFirst().orElseThrow();
        var initialOperation = AmendWorkspace.queryNode(source, "app.deal", initialBody.id()).allowedOperations().stream()
                .filter(value -> value.operation().equals(AmendWorkspace.REPLACE_FUNCTION_BODY))
                .findFirst().orElseThrow();
        var precondition = new CompilerProtocol.ChangeSetPrecondition(
                inspection.sourceDigest(), Map.of(
                        inspection.moduleId().value(), moduleOperation.targetFingerprint(),
                        appState.id().value(), stateOperation.targetFingerprint(),
                        initialBody.id().value(), initialOperation.targetFingerprint()));
        var operations = List.<AmendWorkspace.Operation>of(
                new AmendWorkspace.AddDeclaration(
                        inspection.moduleId(), "export class ChartBar { id: number; value: number; }"),
                new AmendWorkspace.ReplaceDeclaration(
                        appState.id(), "export class ChartBar { id: number; value: number; }"),
                new AmendWorkspace.ReplaceFunctionBody(initialBody.id(), "return {items: []};"));
        var changeInspection = AmendWorkspace.inspectChange(
                source, "app.deal", inspection.sourceDigest(),
                List.of(inspection.moduleId(), appState.id(), initialBody.id()),
                List.of(AmendWorkspace.ADD_DECLARATION, AmendWorkspace.REPLACE_DECLARATION,
                        AmendWorkspace.REPLACE_FUNCTION_BODY));
        var staged = AmendWorkspace.stageChange(
                source, "app.deal", precondition, changeInspection, operations);
        var rejected = staged.workspace().slots().stream()
                .filter(value -> value.status() == CompilerProtocol.RepairSlotStatus.REJECTED).toList();
        check(rejected.size() == 1 && rejected.get(0).operation().equals(AmendWorkspace.REPLACE_DECLARATION),
                "full-candidate diagnostics must select the owned slot even when dependent operations fail alone: "
                        + staged.workspace().slots());
        var repaired = AmendWorkspace.patchRepairWorkspace(
                source, "app.deal", staged.workspace(), List.of(new CompilerProtocol.SlotPatch(
                        rejected.get(0).slotId(), Map.of(
                                "declaration", "export class AppState { items: ChartBar[]; }"))));
        check(repaired.accepted(), "repairing the compiler-owned slot must commit dependent staged operations: "
                + repaired.diagnostics());
    }

    private static void dependentRepairSlotsCommitAsOneGroup(CompilerProtocol.Inspection baseline) {
        String source = source();
        var inspection = baseline;
        var descriptor = AmendWorkspace.queryModule(source, "app.deal")
                .allowedOperations().get(0);
        var precondition = new CompilerProtocol.ChangeSetPrecondition(
                inspection.sourceDigest(), Map.of(
                        inspection.moduleId().value(), descriptor.targetFingerprint()));
        var changeInspection = AmendWorkspace.inspectChange(
                source, "app.deal", inspection.sourceDigest(), List.of(inspection.moduleId()),
                List.of(AmendWorkspace.ADD_DECLARATION));
        var staged = AmendWorkspace.stageChange(
                source, "app.deal", precondition, changeInspection, List.of(
                        new AmendWorkspace.AddDeclaration(
                                inspection.moduleId(), "export class RunAction {}"),
                        new AmendWorkspace.AddDeclaration(
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
        var repaired = AmendWorkspace.patchRepairWorkspace(
                source, "app.deal", staged.workspace(), List.of(new CompilerProtocol.SlotPatch(
                        rejected.slotId(), Map.of("declaration",
                                "export function run(state: AppState, action: RunAction): AppState { return state; }"))));
        check(repaired.accepted(), "dependent slots must compile together after a narrow patch");
        check(repaired.source().contains("class RunAction") && repaired.source().contains("function run"),
                "the repaired group must retain both declarations");
    }

    private static void dependentValidationRejectsOnlyTheFaultyHandler(CompilerProtocol.Inspection baseline) {
        String source = source();
        var inspection = baseline;
        var descriptor = AmendWorkspace.queryModule(source, "app.deal")
                .allowedOperations().get(0);
        var precondition = new CompilerProtocol.ChangeSetPrecondition(
                inspection.sourceDigest(), Map.of(
                        inspection.moduleId().value(), descriptor.targetFingerprint()));
        var changeInspection = AmendWorkspace.inspectChange(
                source, "app.deal", inspection.sourceDigest(), List.of(inspection.moduleId()),
                List.of(AmendWorkspace.ADD_DECLARATION));
        var staged = AmendWorkspace.stageChange(
                source, "app.deal", precondition, changeInspection, List.of(
                        new AmendWorkspace.AddDeclaration(
                                inspection.moduleId(), "export class FirstAction {}"),
                        new AmendWorkspace.AddDeclaration(
                                inspection.moduleId(),
                                "export function first(state: AppState, action: FirstAction): AppState { return state; }"),
                        new AmendWorkspace.AddDeclaration(
                                inspection.moduleId(), "export class SecondAction {}"),
                        new AmendWorkspace.AddDeclaration(
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

    private static void frameworkDiagnosticKeepsRelatedActionStaged(CompilerProtocol.Inspection baseline) {
        String source = source();
        var inspection = baseline;
        var descriptor = AmendWorkspace.queryModule(source, "app.deal")
                .allowedOperations().get(0);
        var precondition = new CompilerProtocol.ChangeSetPrecondition(
                inspection.sourceDigest(), Map.of(
                        inspection.moduleId().value(), descriptor.targetFingerprint()));
        String action = "export class DoneAction { setId: number; }";
        String handler = "export function done(state: AppState, action: DoneAction): AppState { return state; }";
        var operations = List.<AmendWorkspace.Operation>of(
                new AmendWorkspace.AddDeclaration(inspection.moduleId(), action),
                new AmendWorkspace.AddDeclaration(inspection.moduleId(), handler));
        var changeInspection = AmendWorkspace.inspectChange(
                source, "app.deal", inspection.sourceDigest(), List.of(inspection.moduleId()),
                List.of(AmendWorkspace.ADD_DECLARATION));
        var actionId = AmendWorkspace.declarationSemanticId(action, "app.deal");
        var handlerId = AmendWorkspace.declarationSemanticId(handler, "app.deal");
        var staged = AmendWorkspace.stageChange(
                source, "app.deal", precondition, changeInspection, operations,
                (candidateSource, candidateInspection, candidateOperations) -> List.of(
                        new CompilerProtocol.StructuredDiagnostic(
                                "UI2050", "error", "borrowed state mutation", null, handlerId,
                                "immutable borrowed state", "write through alias", List.of(actionId),
                                List.of(new CompilerProtocol.RepairScope(
                                        AmendWorkspace.ADD_DECLARATION, handlerId)),
                                "queryDealSymbol(" + handlerId.value() + ")")));
        var rejected = staged.workspace().slots().stream()
                .filter(value -> value.status() == CompilerProtocol.RepairSlotStatus.REJECTED).toList();
        check(rejected.size() == 1 && rejected.get(0).payload().get("declaration").contains("function done"),
                "a framework diagnostic must reject its owning handler, not its related action type: "
                        + staged.workspace().slots());
        check(staged.workspace().slots().get(0).status() == CompilerProtocol.RepairSlotStatus.STAGED,
                "the related action declaration must stay staged");
    }

    private static void inspectChangeBuildsCompilerOwnedDependencyCone(CompilerProtocol.Inspection baseline) {
        String source = source();
        var inspection = baseline;
        var update = inspection.symbols().stream()
                .filter(value -> value.name().equals("update")).findFirst().orElseThrow();
        var increment = inspection.symbols().stream()
                .filter(value -> value.name().equals("increment")).findFirst().orElseThrow();
        var change = AmendWorkspace.inspectChange(
                source, "app.deal", inspection.sourceDigest(), List.of(update.id()),
                List.of(AmendWorkspace.REPLACE_DECLARATION));
        check(change.diagnostics().isEmpty(), "change inspection must accept a current semantic anchor");
    }

    private static void repairWorkspacePreservesAndPatchesSlots(CompilerProtocol.Inspection baseline) {
        String source = source();
        var inspection = baseline;
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
        var updateDescriptor = AmendWorkspace.queryNode(source, "app.deal", updateBody.id())
                .allowedOperations().get(0);
        var initialDescriptor = AmendWorkspace.queryNode(source, "app.deal", initialBody.id())
                .allowedOperations().get(0);
        var precondition = new CompilerProtocol.ChangeSetPrecondition(
                inspection.sourceDigest(), Map.of(
                        updateBody.id().value(), updateDescriptor.targetFingerprint(),
                        initialBody.id().value(), initialDescriptor.targetFingerprint()));
        var changeInspection = AmendWorkspace.inspectChange(
                source, "app.deal", inspection.sourceDigest(), List.of(updateBody.id(), initialBody.id()),
                List.of(AmendWorkspace.REPLACE_FUNCTION_BODY));
        var staged = AmendWorkspace.stageChange(
                source, "app.deal", precondition, changeInspection, List.of(
                        new AmendWorkspace.ReplaceFunctionBody(updateBody.id(), "return missing;"),
                        new AmendWorkspace.ReplaceFunctionBody(initialBody.id(),
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
        var rejectedTamper = AmendWorkspace.patchRepairWorkspace(
                source, "app.deal", tampered, List.of(new CompilerProtocol.SlotPatch(
                        rejected.slotId(), Map.of("body", "return increment(state);"))));
        check(rejectedTamper.diagnostics().get(0).code().equals("CP1022"),
                "a tampered stateless workspace must reject before applying a patch");
        var repaired = AmendWorkspace.patchRepairWorkspace(
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
        String source = "export class AppState { title: string; }\n"
                + "export function initialState(): AppState { return {title: \"\"}; }\n";
        var inspection = AmendWorkspace.inspect(source, "app.deal");
        var state = inspection.symbols().stream()
                .filter(value -> value.name().equals("AppState")).findFirst().orElseThrow();
        var slice = AmendWorkspace.querySymbol(source, "app.deal", state.id());
        var descriptor = slice.allowedOperations().stream()
                .filter(value -> value.operation().equals(AmendWorkspace.REPLACE_DECLARATION))
                .findFirst().orElseThrow();
        var result = AmendWorkspace.applyChecked(
                source,
                "app.deal",
                new CompilerProtocol.ChangeSetPrecondition(
                        inspection.sourceDigest(), Map.of(state.id().value(), descriptor.targetFingerprint())),
                List.of(new AmendWorkspace.ReplaceDeclaration(
                        state.id(), "export class AppState { count: number; }")));
        check(!result.accepted(), "incompatible neighboring function must reject the full transaction");
        check(result.source().equals(source), "rejected declaration replacement must roll back exactly");

        var paired = AmendWorkspace.apply(
                source,
                "app.deal",
                inspection.sourceDigest(),
                List.of(
                        new AmendWorkspace.ReplaceDeclaration(
                                state.id(), "export class AppState { count: number; }"),
                        new AmendWorkspace.ReplaceFunctionBody(
                                inspection.nodes().get(0).id(), "return {count: 0};")));
        check(paired.accepted(), "compatible declaration and body replacement must commit: " + paired.diagnostics());
        check(paired.source().contains("export function initialState"),
                "replacing one declaration must not consume the next export token");
    }

    private static void deterministicInspectionAndRelationships() {
        String source = source();
        var first = AmendWorkspace.inspect(source, "app.deal");
        var second = AmendWorkspace.inspect(source, "app.deal");
        check(first.sourceDigest().equals(second.sourceDigest()), "digest must be deterministic");
        check(first.symbols().equals(second.symbols()), "symbols must be deterministic");
        var increment = first.symbols().stream().filter(value -> value.name().equals("increment")).findFirst().orElseThrow();
        var update = first.symbols().stream().filter(value -> value.name().equals("update")).findFirst().orElseThrow();
        check(update.callees().contains(increment.id()), "update must call increment");
        check(increment.callers().contains(update.id()), "increment must report update caller");
        check(first.appInterface() != null, "generated app interface must be extracted upstream");
    }

    private static void staleDigestRejectsWithoutMutation(CompilerProtocol.Inspection baseline) {
        String source = source();
        var inspection = baseline;
        var functionBody = inspection.nodes().stream()
                .filter(value -> value.kind().equals("function-body"))
                .findFirst().orElseThrow();
        var result = AmendWorkspace.apply(
                source,
                "app.deal",
                "stale",
                List.of(new AmendWorkspace.ReplaceFunctionBody(functionBody.id(), "return 7;")));
        check(!result.accepted(), "stale digest must reject");
        check(result.source().equals(source), "stale edit must preserve source");
        check(result.diagnostics().get(0).code().equals("CP1001"), "stale edit must be structured");
    }

    private static void functionReplacementIsAtomic(CompilerProtocol.Inspection baseline) {
        String source = source();
        var inspection = baseline;
        var update = inspection.symbols().stream().filter(value -> value.name().equals("update")).findFirst().orElseThrow();
        var body = inspection.nodes().stream()
                .filter(value -> value.ownerId().equals(update.id()) && value.kind().equals("function-body"))
                .findFirst().orElseThrow();

        var rejected = AmendWorkspace.apply(
                source,
                "app.deal",
                inspection.sourceDigest(),
                List.of(new AmendWorkspace.ReplaceFunctionBody(body.id(), "return missing;")));
        check(!rejected.accepted(), "invalid replacement must reject");
        check(rejected.source().equals(source), "invalid replacement must roll back");
        check(rejected.diagnostics().stream().allMatch(value ->
                        value.ownerId().equals(update.id())
                                && value.repairScopes().contains(new CompilerProtocol.RepairScope(
                                        AmendWorkspace.REPLACE_FUNCTION_BODY, body.id()))),
                "diagnostic ownership must retain the symbol while repair remains scoped to the rejected node");

        var accepted = AmendWorkspace.apply(
                source,
                "app.deal",
                inspection.sourceDigest(),
                List.of(new AmendWorkspace.ReplaceFunctionBody(body.id(), "return increment(state);")));
        check(accepted.accepted(), "valid replacement must commit");
        check(!accepted.sourceDigest().equals(inspection.sourceDigest()), "accepted source gets a new revision");
    }

    private static void completeFunctionCannotMasqueradeAsBody(CompilerProtocol.Inspection baseline) {
        String source = source();
        var inspection = baseline;
        var update = inspection.symbols().stream().filter(value -> value.name().equals("update")).findFirst().orElseThrow();
        var body = inspection.nodes().stream()
                .filter(value -> value.ownerId().equals(update.id()) && value.kind().equals("function-body"))
                .findFirst().orElseThrow();
        var rejected = AmendWorkspace.apply(
                source,
                "app.deal",
                inspection.sourceDigest(),
                List.of(new AmendWorkspace.ReplaceFunctionBody(
                        body.id(),
                        "export function update(state: AppState, action: IncrementAction): AppState { return state; }")));
        check(!rejected.accepted(), "complete declaration must not be accepted as a function body");
        check(rejected.source().equals(source), "mis-shaped body edit must be atomic");
        check(rejected.diagnostics().get(0).code().equals("CP1007"),
                "mis-shaped body edit must have a stable diagnostic");
        check(rejected.diagnostics().get(0).repairScopes().get(0).ownerId().equals(body.id()),
                "repair must remain scoped to the requested body");
    }

    private static void blockReplacementUsesRevisionScopedIdentity(CompilerProtocol.Inspection baseline) {
        String source = source();
        var inspection = baseline;
        var nested = inspection.nodes().stream().filter(value -> value.kind().equals("block")).findFirst().orElseThrow();
        var accepted = AmendWorkspace.apply(
                source,
                "app.deal",
                inspection.sourceDigest(),
                List.of(new AmendWorkspace.ReplaceBlockBody(nested.id(), "return increment(state);")));
        check(accepted.accepted(), "nested block replacement must compile");
        var staleNode = AmendWorkspace.apply(
                accepted.source(),
                "app.deal",
                accepted.sourceDigest(),
                List.of(new AmendWorkspace.ReplaceBlockBody(nested.id(), "return state;")));
        check(!staleNode.accepted(), "node id from previous revision must reject");
        check(staleNode.diagnostics().get(0).code().equals("CP1003"), "stale node has stable diagnostic");
    }

    private static void schemaChangeRequiresStateReset(CompilerProtocol.Inspection baseline) {
        String before = source();
        String after = before.replace("count: number;", "count: number;\n  label?: string;");
        var beforeInspection = baseline;
        var afterInspection = AmendWorkspace.inspect(after, "app.deal");
        check(!beforeInspection.appInterface().fingerprint()
                .equals(afterInspection.appInterface().fingerprint()), "interface fingerprint must change");
    }

    private static void protocolJsonIsDeterministicAndUnicodeSafe(CompilerProtocol.Inspection baseline) {
        var inspection = baseline;
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

    private static void declarationsCanBeAddedAndRemovedAtomically(CompilerProtocol.Inspection baseline) {
        String source = source();
        var inspection = baseline;
        var added = AmendWorkspace.apply(
                source,
                "app.deal",
                inspection.sourceDigest(),
                List.of(new AmendWorkspace.AddDeclaration(
                        inspection.moduleId(),
                        "function scoreBonus(value: number): number { return value + 10; }")));
        check(added.accepted(), "valid declaration must be added: " + added.diagnostics());
        var bonus = added.inspection().symbols().stream()
                .filter(value -> value.name().equals("scoreBonus")).findFirst().orElseThrow();

        var invalid = AmendWorkspace.apply(
                source,
                "app.deal",
                inspection.sourceDigest(),
                List.of(new AmendWorkspace.AddDeclaration(
                        inspection.moduleId(),
                        "function broken(value: number): number { return missing; }")));
        check(!invalid.accepted() && invalid.source().equals(source), "invalid declaration must roll back");

        var removed = AmendWorkspace.apply(
                added.source(),
                "app.deal",
                added.sourceDigest(),
                List.of(new AmendWorkspace.RemoveDeclaration(bonus.id())));
        check(removed.accepted(), "unreferenced declaration must be removable: " + removed.diagnostics());
        check(removed.inspection().symbols().stream().noneMatch(value -> value.name().equals("scoreBonus")),
                "removed declaration must disappear from inspection");
    }

    private static void addingMultipleDeclarationsIsRejectedAtomically(CompilerProtocol.Inspection baseline) {
        String source = source();
        var inspection = baseline;
        var result = AmendWorkspace.apply(
                source,
                "app.deal",
                inspection.sourceDigest(),
                List.of(new AmendWorkspace.AddDeclaration(
                        inspection.moduleId(),
                        "export class FirstAction {}\nexport class SecondAction {}")));
        check(!result.accepted(), "one add operation must not smuggle multiple declarations");
        check(result.source().equals(source), "multi-declaration add rejection must preserve source");
        check(result.diagnostics().stream().anyMatch(value -> value.code().equals("CP1013")),
                "multi-declaration add must expose stable CP1013");
    }

    private static void annotatedHandlerCountsAsOneDeclaration(CompilerProtocol.Inspection baseline) {
        String source = source();
        var inspection = baseline;
        var result = AmendWorkspace.apply(
                source,
                "app.deal",
                inspection.sourceDigest(),
                List.of(new AmendWorkspace.AddDeclaration(
                        inspection.moduleId(),
                        "// @ui-update\nexport function secondUpdate(state: AppState, action: IncrementAction): AppState { return state; }")));
        check(result.diagnostics().stream().noneMatch(value -> value.code().equals("CP1013")),
                "a framework directive plus its declaration must pass declaration cardinality");
    }

    private static void semanticQueriesExposeScopedOperations(CompilerProtocol.Inspection baseline) {
        String source = source();
        var inspection = baseline;
        var update = inspection.symbols().stream()
                .filter(value -> value.name().equals("update"))
                .findFirst().orElseThrow();
        var symbolSlice = AmendWorkspace.querySymbol(source, "app.deal", update.id());
        check(symbolSlice.revision().sourceDigest().equals(inspection.sourceDigest()),
                "query must bind its context to the current source revision");
        check(symbolSlice.source().contains("function update"), "symbol query must return only owned source");
        check(symbolSlice.allowedOperations().stream()
                        .anyMatch(value -> value.operation().equals(AmendWorkspace.REPLACE_FUNCTION_BODY)),
                "function query must expose its compiler-owned body operation");
        check(symbolSlice.allowedOperations().stream()
                        .noneMatch(value -> value.operation().equals(AmendWorkspace.ADD_DECLARATION)),
                "symbol query must not authorize unrelated module edits");

        var moduleSlice = AmendWorkspace.queryModule(source, "app.deal");
        check(moduleSlice.source().isEmpty(), "module query must not disclose the complete source");
        check(moduleSlice.allowedOperations().stream().map(CompilerProtocol.OperationDescriptor::operation)
                        .collect(java.util.stream.Collectors.toSet())
                        .equals(java.util.Set.of(
                                AmendWorkspace.ADD_DECLARATION,
                                AmendWorkspace.SET_CAPABILITIES)),
                "module query must authorize declaration insertion and capability replacement");
    }

    private static void checkedChangesRequireQueriedTargetFingerprint(CompilerProtocol.Inspection baseline) {
        String source = source();
        var inspection = baseline;
        var update = inspection.symbols().stream()
                .filter(value -> value.name().equals("update"))
                .findFirst().orElseThrow();
        var body = inspection.nodes().stream()
                .filter(value -> value.ownerId().equals(update.id()) && value.kind().equals("function-body"))
                .findFirst().orElseThrow();
        var slice = AmendWorkspace.queryNode(source, "app.deal", body.id());
        var descriptor = slice.allowedOperations().get(0);
        var operation = new AmendWorkspace.ReplaceFunctionBody(body.id(), "return increment(state);");

        var missing = AmendWorkspace.applyChecked(
                source,
                "app.deal",
                new CompilerProtocol.ChangeSetPrecondition(inspection.sourceDigest(), Map.of()),
                List.of(operation));
        check(!missing.accepted() && missing.diagnostics().get(0).code().equals("CP1010"),
                "unqueried targets must not be writable");

        var stale = AmendWorkspace.applyChecked(
                source,
                "app.deal",
                new CompilerProtocol.ChangeSetPrecondition(
                        inspection.sourceDigest(), Map.of(body.id().value(), "stale")),
                List.of(operation));
        check(!stale.accepted() && stale.diagnostics().get(0).code().equals("CP1011"),
                "stale target fingerprints must reject atomically");

        var accepted = AmendWorkspace.applyChecked(
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
                  count: number;
                }
                export class IncrementAction {
                  amount: number;
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
