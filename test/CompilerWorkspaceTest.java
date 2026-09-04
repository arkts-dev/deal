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
        protocolJsonIsDeterministicAndUnicodeSafe();
        protocolJsonSupportsDesugaredRecordShape();
        declarationsCanBeAddedAndRemovedAtomically();
        addingMultipleDeclarationsIsRejectedAtomically();
        annotatedHandlerCountsAsOneDeclaration();
        declarationReplacementIsAtomicAndDoesNotConsumeItsNeighbor();
        semanticQueriesExposeScopedOperations();
        checkedChangesRequireQueriedTargetFingerprint();
        System.out.println("CompilerWorkspaceTest: all tests passed");
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
        check(rejected.diagnostics().stream().allMatch(value -> value.ownerId().equals(body.id())),
                "repair must be scoped to rejected node");

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
        check(moduleSlice.allowedOperations().size() == 1
                        && moduleSlice.allowedOperations().get(0).operation()
                                .equals(DealCompilerWorkspace.ADD_DECLARATION),
                "module query must authorize only declaration insertion");
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
