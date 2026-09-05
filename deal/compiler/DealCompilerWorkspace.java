package deal.compiler;

import deal.ast.ArrayType;
import deal.ast.AwaitExpression;
import deal.ast.BinaryExpr;
import deal.ast.Block;
import deal.ast.CallExpr;
import deal.ast.ClassDeclaration;
import deal.ast.ClassField;
import deal.ast.DeleteStatement;
import deal.ast.Either;
import deal.ast.ExportDeclaration;
import deal.ast.ExpressionNode;
import deal.ast.ExpressionStatement;
import deal.ast.ForInit;
import deal.ast.ForOfStatement;
import deal.ast.ForStatement;
import deal.ast.FunctionDeclaration;
import deal.ast.FunctionExpr;
import deal.ast.HasExpr;
import deal.ast.IdentifierExpr;
import deal.ast.IfStatement;
import deal.ast.IndexExpr;
import deal.ast.MemberAccessExpr;
import deal.ast.NamedType;
import deal.ast.NullableType;
import deal.ast.ObjectLiteralExpr;
import deal.ast.Parameter;
import deal.ast.ProgramNode;
import deal.ast.Property;
import deal.ast.QualifiedType;
import deal.ast.ReturnStatement;
import deal.ast.Span;
import deal.ast.StatementNode;
import deal.ast.TemplateLiteralExpr;
import deal.ast.ThrowStatement;
import deal.ast.TokenType;
import deal.ast.TryStatement;
import deal.ast.TypeNode;
import deal.ast.UnaryExpr;
import deal.ast.VariableDeclaration;
import deal.ast.WhileStatement;
import deal.ast.AssignmentExpr;
import deal.ast.ArrayLiteralExpr;
import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.NameResolver;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.diagnostics.CompilerDiagnostic;
import deal.compiler.CompilerProtocol.AppInterfaceSnapshot;
import deal.compiler.CompilerProtocol.ChangeSetPrecondition;
import deal.compiler.CompilerProtocol.ChangeResult;
import deal.compiler.CompilerProtocol.ChangeInspection;
import deal.compiler.CompilerProtocol.DependencyCone;
import deal.compiler.CompilerProtocol.DependencyEdge;
import deal.compiler.CompilerProtocol.DependencyGroup;
import deal.compiler.CompilerProtocol.DependencyMember;
import deal.compiler.CompilerProtocol.FieldSnapshot;
import deal.compiler.CompilerProtocol.ImpactReport;
import deal.compiler.CompilerProtocol.Inspection;
import deal.compiler.CompilerProtocol.NodeSnapshot;
import deal.compiler.CompilerProtocol.OperationDescriptor;
import deal.compiler.CompilerProtocol.ProtocolHandshake;
import deal.compiler.CompilerProtocol.RepairScope;
import deal.compiler.CompilerProtocol.RepairSlot;
import deal.compiler.CompilerProtocol.RepairSlotStatus;
import deal.compiler.CompilerProtocol.RepairWorkspaceResult;
import deal.compiler.CompilerProtocol.RepairWorkspaceSnapshot;
import deal.compiler.CompilerProtocol.SemanticId;
import deal.compiler.CompilerProtocol.SemanticSlice;
import deal.compiler.CompilerProtocol.RevisionRef;
import deal.compiler.CompilerProtocol.SourceRange;
import deal.compiler.CompilerProtocol.StructuredDiagnostic;
import deal.compiler.CompilerProtocol.SlotPatch;
import deal.compiler.CompilerProtocol.SymbolSnapshot;
import deal.compiler.CompilerProtocol.TypeSnapshot;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.types.Type;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stateless semantic inspection and checked source edits for one DEAL module. */
public final class DealCompilerWorkspace {
    @FunctionalInterface
    public interface CandidateValidator {
        List<StructuredDiagnostic> validate(
                String candidateSource,
                Inspection candidateInspection,
                List<? extends Operation> operations);
    }

    private static final CandidateValidator NO_ADDITIONAL_VALIDATION =
            (candidateSource, candidateInspection, operations) -> List.of();
    public static final String REPLACE_FUNCTION_BODY = "replaceFunctionBody";
    public static final String REPLACE_BLOCK_BODY = "replaceBlockBody";
    public static final String ADD_DECLARATION = "addDeclaration";
    public static final String REMOVE_DECLARATION = "removeDeclaration";
    public static final String REPLACE_DECLARATION = "replaceDeclaration";
    private static final List<String> ALLOWED_OPERATIONS = List.of(
            ADD_DECLARATION,
            REMOVE_DECLARATION,
            REPLACE_DECLARATION,
            REPLACE_FUNCTION_BODY,
            REPLACE_BLOCK_BODY);
    private static final Pattern CAPABILITY = Pattern.compile(
            "(?m)^\\s*//\\s*generated-capability:\\s*([a-z][a-z0-9.]*)\\s*$");
    private static final Pattern COMPLETE_FUNCTION_DECLARATION = Pattern.compile(
            "(?s)^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*\\(.*\\}\\s*$");

    private DealCompilerWorkspace() {}

    public static ProtocolHandshake handshake() {
        return new ProtocolHandshake(
                CompilerProtocol.VERSION,
                "DEAL 1.2",
                List.of(
                        "semantic-inspection",
                        "semantic-slices",
                        "fingerprint-preconditions",
                        "atomic-change-sets",
                        "app-interface-fingerprints",
                        "dependency-cones",
                        "repair-workspace-slots"));
    }

    @FunctionalInterface
    public interface SourceAdapter {
        SourceAdapter IDENTITY = source -> source;

        /** Returns a parser projection with identical line and Unicode-scalar geometry. */
        String parserSource(String source);
    }

    public sealed interface Operation permits AddDeclaration, RemoveDeclaration, ReplaceDeclaration, ReplaceFunctionBody, ReplaceBlockBody {
        SemanticId targetId();
    }

    public record AddDeclaration(SemanticId targetId, String declaration) implements Operation {}

    public record RemoveDeclaration(SemanticId targetId) implements Operation {}

    public record ReplaceDeclaration(SemanticId targetId, String declaration) implements Operation {}

    public record ReplaceFunctionBody(SemanticId targetId, String body) implements Operation {}

    public record ReplaceBlockBody(SemanticId targetId, String body) implements Operation {}

    public static ChangeInspection inspectChange(
            String source,
            String modulePath,
            String baseDigest,
            List<SemanticId> anchors,
            List<String> requestedOperations) {
        return inspectChange(source, modulePath, baseDigest, anchors, requestedOperations,
                rejectingResolver(), SourceAdapter.IDENTITY);
    }

    public static ChangeInspection inspectChange(
            String source,
            String modulePath,
            String baseDigest,
            List<SemanticId> anchors,
            List<String> requestedOperations,
            ModuleResolver resolver,
            SourceAdapter adapter) {
        Analysis analysis = analyze(source, modulePath, resolver, adapter);
        if (!analysis.inspection().sourceDigest().equals(baseDigest)) {
            StructuredDiagnostic diagnostic = diagnostic(
                    "CP1001", "Stale source digest; inspect the current source before editing",
                    analysis.moduleId(), null, analysis.inspection().sourceDigest(), baseDigest,
                    List.of(), "inspectCanonicalApp");
            DependencyCone empty = new DependencyCone(digest("empty"), List.of(), List.of(), List.of());
            return new ChangeInspection(revision(analysis), digest(baseDigest + "\u0000stale"),
                    empty, List.of(), List.of(), List.of(diagnostic));
        }
        LinkedHashSet<SemanticId> selected = new LinkedHashSet<>(anchors);
        if (selected.isEmpty()) selected.add(analysis.moduleId());
        List<SemanticSlice> slices = new ArrayList<>();
        LinkedHashMap<SemanticId, DependencyMember> members = new LinkedHashMap<>();
        LinkedHashSet<DependencyEdge> edges = new LinkedHashSet<>();
        LinkedHashMap<String, OperationDescriptor> operations = new LinkedHashMap<>();
        for (SemanticId anchor : selected) {
            Target target = analysis.targets().get(anchor);
            if (target == null) {
                StructuredDiagnostic diagnostic = diagnostic(
                        "CP1020", "Unknown change anchor", anchor, null,
                        "target issued for " + baseDigest, "missing", List.of(), "inspectCanonicalApp");
                DependencyCone empty = new DependencyCone(digest("empty"), List.copyOf(selected), List.of(), List.of());
                return new ChangeInspection(revision(analysis), digest(baseDigest + "\u0000unknown"),
                        empty, List.of(), List.of(), List.of(diagnostic));
            }
            SemanticSlice slice = target.kind().equals("module")
                    ? queryModule(source, modulePath, resolver, adapter)
                    : target.kind().equals("declaration")
                            ? querySymbol(source, modulePath, anchor, resolver, adapter)
                            : queryNode(source, modulePath, anchor, resolver, adapter);
            slices.add(slice);
            members.put(anchor, new DependencyMember(
                    anchor, target.kind(), "EDIT_BODY", targetFingerprint(analysis, anchor)));
            slice.allowedOperations().stream()
                    .filter(value -> requestedOperations.isEmpty() || requestedOperations.contains(value.operation()))
                    .forEach(value -> operations.put(value.operation() + "\u0000" + value.targetId().value(), value));
            SymbolSnapshot owner = target.kind().equals("declaration")
                    ? analysis.symbolsById().get(anchor)
                    : analysis.symbolsById().get(target.ownerId());
            if (owner == null) continue;
            for (SemanticId callee : owner.callees()) {
                addDependencyMember(analysis, members, callee, "SIGNATURE_ONLY");
                edges.add(new DependencyEdge(owner.id(), callee, "CALLS"));
            }
            for (SemanticId reference : owner.references()) {
                addDependencyMember(analysis, members, reference, "SIGNATURE_ONLY");
                edges.add(new DependencyEdge(owner.id(), reference, "REFERENCES"));
            }
            for (SemanticId caller : owner.callers()) {
                addDependencyMember(analysis, members, caller, "IMPACT_ONLY");
                edges.add(new DependencyEdge(caller, owner.id(), "CALLS"));
            }
        }
        List<DependencyMember> memberList = List.copyOf(members.values());
        List<DependencyEdge> edgeList = List.copyOf(edges);
        String coneFingerprint = digest(CompilerProtocolJson.encode(List.of(
                List.copyOf(selected), memberList, edgeList)));
        DependencyCone cone = new DependencyCone(
                coneFingerprint, List.copyOf(selected), memberList, edgeList);
        String inspectionDigest = digest(CompilerProtocolJson.encode(List.of(
                baseDigest, coneFingerprint, List.copyOf(operations.values()))));
        return new ChangeInspection(revision(analysis), inspectionDigest, cone, slices,
                List.copyOf(operations.values()), List.of());
    }

    public static Inspection inspect(String source, String modulePath) {
        return inspect(source, modulePath, rejectingResolver(), SourceAdapter.IDENTITY);
    }

    public static Inspection inspect(String source, String modulePath, ModuleResolver resolver) {
        return inspect(source, modulePath, resolver, SourceAdapter.IDENTITY);
    }

    public static Inspection inspect(String source, String modulePath, SourceAdapter adapter) {
        return inspect(source, modulePath, rejectingResolver(), adapter);
    }

    public static Inspection inspect(
            String source, String modulePath, ModuleResolver resolver, SourceAdapter adapter) {
        return analyze(source, modulePath, resolver, adapter).inspection();
    }

    public static SemanticSlice querySymbol(String source, String modulePath, SemanticId symbolId) {
        return querySymbol(source, modulePath, symbolId, rejectingResolver(), SourceAdapter.IDENTITY);
    }

    public static SemanticSlice queryModule(String source, String modulePath) {
        return queryModule(source, modulePath, rejectingResolver(), SourceAdapter.IDENTITY);
    }

    public static SemanticSlice queryModule(
            String source,
            String modulePath,
            ModuleResolver resolver,
            SourceAdapter adapter) {
        Analysis analysis = analyze(source, modulePath, resolver, adapter);
        Target target = analysis.targets().get(analysis.moduleId());
        return new SemanticSlice(
                revision(analysis),
                analysis.moduleId(),
                "module",
                "",
                analysis.inspection().symbols(),
                List.of(),
                List.of(),
                List.of(descriptor(analysis, target)));
    }

    public static SemanticSlice querySymbol(
            String source,
            String modulePath,
            SemanticId symbolId,
            ModuleResolver resolver,
            SourceAdapter adapter) {
        Analysis analysis = analyze(source, modulePath, resolver, adapter);
        SymbolSnapshot symbol = analysis.symbolsById().get(symbolId);
        Target target = analysis.targets().get(symbolId);
        if (symbol == null || target == null || !target.kind().equals("declaration")) {
            throw new IllegalArgumentException("Unknown DEAL symbol " + symbolId.value());
        }
        List<NodeSnapshot> ownedNodes = analysis.inspection().nodes().stream()
                .filter(value -> value.ownerId().equals(symbolId))
                .toList();
        Set<SemanticId> dependencies = new LinkedHashSet<>();
        dependencies.addAll(symbol.callers());
        dependencies.addAll(symbol.callees());
        dependencies.addAll(symbol.references());
        return new SemanticSlice(
                revision(analysis),
                symbolId,
                "symbol",
                analysis.source().substring(target.start(), target.end()),
                List.of(symbol),
                ownedNodes,
                List.copyOf(dependencies),
                descriptorsFor(analysis, symbolId, ownedNodes));
    }

    public static SemanticSlice queryNode(String source, String modulePath, SemanticId nodeId) {
        return queryNode(source, modulePath, nodeId, rejectingResolver(), SourceAdapter.IDENTITY);
    }

    public static SemanticSlice queryNode(
            String source,
            String modulePath,
            SemanticId nodeId,
            ModuleResolver resolver,
            SourceAdapter adapter) {
        Analysis analysis = analyze(source, modulePath, resolver, adapter);
        Target target = analysis.targets().get(nodeId);
        NodeSnapshot node = analysis.inspection().nodes().stream()
                .filter(value -> value.id().equals(nodeId))
                .findFirst()
                .orElse(null);
        if (target == null || node == null) {
            throw new IllegalArgumentException("Unknown DEAL node " + nodeId.value());
        }
        SymbolSnapshot owner = analysis.symbolsById().get(node.ownerId());
        List<SemanticId> dependencies = owner == null
                ? List.of()
                : unique(owner.callers(), owner.callees(), owner.references());
        return new SemanticSlice(
                revision(analysis),
                nodeId,
                node.kind(),
                analysis.source().substring(target.start(), target.end()),
                owner == null ? List.of() : List.of(owner),
                List.of(node),
                dependencies,
                List.of(descriptor(analysis, target)));
    }

    public static ChangeResult applyChecked(
            String source,
            String modulePath,
            ChangeSetPrecondition precondition,
            List<? extends Operation> operations) {
        return applyChecked(
                source, modulePath, precondition, operations, rejectingResolver(), SourceAdapter.IDENTITY);
    }

    public static ChangeResult applyChecked(
            String source,
            String modulePath,
            ChangeSetPrecondition precondition,
            List<? extends Operation> operations,
            ModuleResolver resolver,
            SourceAdapter adapter) {
        Objects.requireNonNull(precondition, "precondition");
        Analysis base = analyze(source, modulePath, resolver, adapter);
        if (!base.inspection().sourceDigest().equals(precondition.baseDigest())) {
            return rejected(base, diagnostic(
                    "CP1001", "Stale source digest; inspect the current source before editing",
                    base.moduleId(), null, base.inspection().sourceDigest(), precondition.baseDigest(),
                    List.of(), "inspectCanonicalApp"));
        }
        for (Operation operation : operations) {
            String expected = precondition.expectedTargetFingerprints().get(operation.targetId().value());
            if (expected == null) {
                return rejected(base, diagnostic(
                        "CP1010", "Missing target fingerprint precondition",
                        operation.targetId(), null, targetFingerprint(base, operation.targetId()), "missing",
                        List.of(), "queryDealNode"));
            }
            String actual = targetFingerprint(base, operation.targetId());
            if (!expected.equals(actual)) {
                return rejected(base, diagnostic(
                        "CP1011", "Stale target fingerprint; query the target again before editing",
                        operation.targetId(), null, actual, expected, List.of(), "queryDealNode"));
            }
        }
        return apply(source, modulePath, precondition.baseDigest(), operations, resolver, adapter);
    }

    private static ChangeResult applyCheckedAndValidate(
            String source,
            String modulePath,
            ChangeSetPrecondition precondition,
            List<? extends Operation> operations,
            ModuleResolver resolver,
            SourceAdapter adapter,
            CandidateValidator validator) {
        ChangeResult changed = applyChecked(
                source, modulePath, precondition, operations, resolver, adapter);
        if (!changed.accepted()) return changed;
        List<StructuredDiagnostic> diagnostics = List.copyOf(
                validator.validate(changed.source(), changed.inspection(), operations));
        if (diagnostics.isEmpty()) return changed;
        Analysis base = analyze(source, modulePath, resolver, adapter);
        return new ChangeResult(
                false,
                source,
                base.inspection().sourceDigest(),
                base.inspection(),
                changed.impact(),
                diagnostics);
    }

    public static RepairWorkspaceResult stageChange(
            String source,
            String modulePath,
            ChangeSetPrecondition precondition,
            ChangeInspection changeInspection,
            List<? extends Operation> operations) {
        return stageChange(source, modulePath, precondition, changeInspection, operations,
                rejectingResolver(), SourceAdapter.IDENTITY, NO_ADDITIONAL_VALIDATION);
    }

    public static RepairWorkspaceResult stageChange(
            String source,
            String modulePath,
            ChangeSetPrecondition precondition,
            ChangeInspection changeInspection,
            List<? extends Operation> operations,
            CandidateValidator validator) {
        return stageChange(source, modulePath, precondition, changeInspection, operations,
                rejectingResolver(), SourceAdapter.IDENTITY, validator);
    }

    public static RepairWorkspaceResult stageChange(
            String source,
            String modulePath,
            ChangeSetPrecondition precondition,
            ChangeInspection changeInspection,
            List<? extends Operation> operations,
            ModuleResolver resolver,
            SourceAdapter adapter) {
        return stageChange(source, modulePath, precondition, changeInspection, operations,
                resolver, adapter, NO_ADDITIONAL_VALIDATION);
    }

    public static RepairWorkspaceResult stageChange(
            String source,
            String modulePath,
            ChangeSetPrecondition precondition,
            ChangeInspection changeInspection,
            List<? extends Operation> operations,
            ModuleResolver resolver,
            SourceAdapter adapter,
            CandidateValidator validator) {
        Objects.requireNonNull(changeInspection, "changeInspection");
        Objects.requireNonNull(validator, "validator");
        ChangeResult change = applyCheckedAndValidate(
                source, modulePath, precondition, operations, resolver, adapter, validator);
        if (change.accepted()) {
            RepairWorkspaceSnapshot workspace = workspace(
                    source, modulePath, precondition, changeInspection, operations, change, 0,
                    List.of(), resolver, adapter, validator);
            return new RepairWorkspaceResult(
                    true, change.source(), change.sourceDigest(), workspace, change, List.of());
        }
        RepairWorkspaceSnapshot workspace = workspace(
                source, modulePath, precondition, changeInspection, operations, change, 0,
                List.of(), resolver, adapter, validator);
        return new RepairWorkspaceResult(
                false, source, digest(source), workspace, change, change.diagnostics());
    }

    public static RepairWorkspaceResult patchRepairWorkspace(
            String source,
            String modulePath,
            RepairWorkspaceSnapshot workspace,
            List<SlotPatch> patches) {
        return patchRepairWorkspace(source, modulePath, workspace, patches,
                rejectingResolver(), SourceAdapter.IDENTITY, NO_ADDITIONAL_VALIDATION);
    }

    public static RepairWorkspaceResult patchRepairWorkspace(
            String source,
            String modulePath,
            RepairWorkspaceSnapshot workspace,
            List<SlotPatch> patches,
            ModuleResolver resolver,
            SourceAdapter adapter) {
        return patchRepairWorkspace(source, modulePath, workspace, patches,
                resolver, adapter, NO_ADDITIONAL_VALIDATION);
    }

    public static RepairWorkspaceResult patchRepairWorkspace(
            String source,
            String modulePath,
            RepairWorkspaceSnapshot workspace,
            List<SlotPatch> patches,
            ModuleResolver resolver,
            SourceAdapter adapter,
            CandidateValidator validator) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(validator, "validator");
        if (!digest(source).equals(workspace.baseRevision().sourceDigest())) {
            return rejectedWorkspace(source, workspace, "CP1021", "Repair workspace base source is stale");
        }
        if (!workspaceDigest(workspace).equals(workspace.workspaceDigest())) {
            return rejectedWorkspace(source, workspace, "CP1022", "Repair workspace digest is invalid");
        }
        Map<String, SlotPatch> bySlot = new LinkedHashMap<>();
        for (SlotPatch patch : patches) {
            if (bySlot.put(patch.slotId(), patch) != null) {
                return rejectedWorkspace(source, workspace, "CP1023", "Repair slot was patched more than once");
            }
        }
        List<Operation> operations = new ArrayList<>();
        for (RepairSlot slot : workspace.slots()) {
            SlotPatch patch = bySlot.remove(slot.slotId());
            if (patch != null && slot.status() != RepairSlotStatus.REJECTED) {
                return rejectedWorkspace(source, workspace, "CP1024", "Only rejected repair slots are writable");
            }
            if (patch != null && patch.drop()) {
                if (!slot.operation().equals(ADD_DECLARATION)) {
                    return rejectedWorkspace(source, workspace, "CP1027",
                            "Only a rejected addDeclaration slot may be dropped");
                }
                if (!patch.payload().isEmpty()) {
                    return rejectedWorkspace(source, workspace, "CP1028",
                            "A dropped repair slot must not contain a replacement payload");
                }
                continue;
            }
            Map<String, String> payload = new LinkedHashMap<>(slot.payload());
            if (patch != null) {
                if (!payload.keySet().equals(patch.payload().keySet())) {
                    return rejectedWorkspace(source, workspace, "CP1025", "Repair patch fields do not match the slot contract");
                }
                payload.putAll(patch.payload());
            }
            operations.add(operation(slot.operation(), slot.targetId(), payload));
        }
        if (!bySlot.isEmpty()) {
            return rejectedWorkspace(source, workspace, "CP1026", "Unknown repair slot " + bySlot.keySet().iterator().next());
        }
        ChangeInspection inspection = inspectChange(
                source, modulePath, workspace.baseRevision().sourceDigest(),
                workspace.slots().stream().map(RepairSlot::targetId).distinct().toList(),
                workspace.slots().stream().map(RepairSlot::operation).distinct().toList(), resolver, adapter);
        ChangeResult change = applyCheckedAndValidate(
                source, modulePath, workspace.precondition(), operations, resolver, adapter, validator);
        RepairWorkspaceSnapshot next = workspace(
                source, modulePath, workspace.precondition(), inspection, operations, change,
                workspace.repairRound() + 1, workspace.slots(), resolver, adapter, validator);
        return new RepairWorkspaceResult(
                change.accepted(), change.accepted() ? change.source() : source,
                change.accepted() ? change.sourceDigest() : digest(source),
                next, change, change.diagnostics());
    }

    public static ChangeResult apply(
            String source,
            String modulePath,
            String baseDigest,
            List<? extends Operation> operations) {
        return apply(source, modulePath, baseDigest, operations, rejectingResolver());
    }

    private static void addDependencyMember(
            Analysis analysis,
            Map<SemanticId, DependencyMember> members,
            SemanticId id,
            String exposure) {
        SymbolSnapshot symbol = analysis.symbolsById().get(id);
        if (symbol == null) return;
        DependencyMember existing = members.get(id);
        if (existing != null && exposureRank(existing.exposure()) >= exposureRank(exposure)) return;
        members.put(id, new DependencyMember(id, symbol.kind(), exposure, symbol.fingerprint()));
    }

    private static int exposureRank(String value) {
        return switch (value) {
            case "EDIT_BODY" -> 3;
            case "SIGNATURE_ONLY" -> 2;
            default -> 1;
        };
    }

    private static RepairWorkspaceSnapshot workspace(
            String source,
            String modulePath,
            ChangeSetPrecondition precondition,
            ChangeInspection inspection,
            List<? extends Operation> operations,
            ChangeResult change,
            int round,
            List<RepairSlot> previousSlots,
            ModuleResolver resolver,
            SourceAdapter adapter,
            CandidateValidator validator) {
        List<SemanticId> produced = operations.stream()
                .map(operation -> operation instanceof AddDeclaration value
                        ? declarationSemanticId(value.declaration(), modulePath) : null)
                .toList();
        List<Set<Integer>> dependencies = operationDependencies(operations, produced);
        SccResult dependencyGroups = stronglyConnectedComponents(dependencies);
        int[] groupIndexes = dependencyGroups.groupByNode();
        List<StructuredDiagnostic> diagnostics = change.diagnostics();
        List<ChangeResult> isolated = new ArrayList<>();
        for (int index = 0; index < operations.size(); index++) {
            Set<Integer> closure = dependencyClosure(index, dependencies);
            List<Operation> candidate = new ArrayList<>();
            for (int operationIndex = 0; operationIndex < operations.size(); operationIndex++) {
                if (closure.contains(operationIndex)) candidate.add(operations.get(operationIndex));
            }
            isolated.add(applyChecked(
                    source, modulePath, precondition, candidate, resolver, adapter));
        }
        List<StructuredDiagnostic> operationContractDiagnostics = diagnostics.stream()
                .filter(DealCompilerWorkspace::isOperationContractDiagnostic).toList();
        Set<Integer> directlyRejected = rejectedSlots(operations, operationContractDiagnostics);
        if (operationContractDiagnostics.isEmpty()) {
            directlyRejected = directlyOwnedSlots(operations, diagnostics, modulePath);
            if (directlyRejected.isEmpty()) {
                for (int index = 0; index < isolated.size(); index++) {
                    if (!isolated.get(index).accepted()) directlyRejected.add(index);
                }
            }
            if (directlyRejected.isEmpty()) directlyRejected.addAll(rejectedSlots(operations, diagnostics));
        }
        List<RepairSlot> slots = new ArrayList<>();
        for (int index = 0; index < operations.size(); index++) {
            int slotIndex = index;
            Operation operation = operations.get(index);
            String slotId = "R" + (index + 1);
            String groupId = "G" + (groupIndexes[index] + 1);
            Map<String, String> payload = payload(operation);
            List<StructuredDiagnostic> candidateDiagnostics = diagnostics.stream()
                    .filter(value -> diagnosticMatches(operation, value)).toList();
            List<StructuredDiagnostic> owned = !candidateDiagnostics.isEmpty()
                    ? candidateDiagnostics
                    : isolated.get(index).accepted() ? List.of() : isolated.get(index).diagnostics();
            boolean rejected = directlyRejected.contains(index);
            boolean blocked = !rejected && directlyRejected.stream().anyMatch(other ->
                    groupIndexes[other] == groupIndexes[slotIndex]
                            || groupDependsOn(groupIndexes[slotIndex], groupIndexes[other],
                                    dependencyGroups.groupDependencies()));
            RepairSlotStatus status = change.accepted()
                    ? RepairSlotStatus.COMMIT_READY
                    : rejected ? RepairSlotStatus.REJECTED
                    : blocked ? RepairSlotStatus.BLOCKED
                    : previouslyStaged(previousSlots, slotId, payload)
                            ? RepairSlotStatus.SEALED : RepairSlotStatus.STAGED;
            String targetFingerprint = precondition.expectedTargetFingerprints()
                    .getOrDefault(operation.targetId().value(), "");
            slots.add(new RepairSlot(
                    slotId, operationName(operation), operation.targetId(), targetFingerprint,
                    payload, digest(CompilerProtocolJson.encode(payload)), status, groupId, owned));
        }
        List<DependencyGroup> groups = new ArrayList<>();
        int groupCount = java.util.Arrays.stream(groupIndexes).max().orElse(-1) + 1;
        for (int group = 0; group < groupCount; group++) {
            int selectedGroup = group;
            List<RepairSlot> members = slots.stream()
                    .filter(value -> value.dependencyGroupId().equals("G" + (selectedGroup + 1))).toList();
            String status = members.stream().anyMatch(value -> value.status() == RepairSlotStatus.REJECTED)
                    ? "REPAIR_REQUIRED"
                    : members.stream().anyMatch(value -> value.status() == RepairSlotStatus.BLOCKED)
                            ? "BLOCKED"
                    : members.stream().allMatch(value -> value.status() == RepairSlotStatus.COMMIT_READY)
                            ? "COMMIT_READY"
                    : members.stream().anyMatch(value -> value.status() == RepairSlotStatus.STAGED)
                            ? "STAGED" : "SEALED";
            groups.add(new DependencyGroup(
                    "G" + (group + 1), members.stream().map(RepairSlot::slotId).toList(),
                    dependencyGroups.groupDependencies().get(group).stream()
                            .sorted().map(value -> "G" + (value + 1)).toList(),
                    status));
        }
        String workspaceId = digest(precondition.baseDigest() + "\u0000" + inspection.inspectionDigest()
                + "\u0000" + CompilerProtocolJson.encode(operations.stream().map(DealCompilerWorkspace::payload).toList()));
        RepairWorkspaceSnapshot draft = new RepairWorkspaceSnapshot(
                workspaceId, "", new RevisionRef(CompilerProtocol.VERSION, digest(source)),
                inspection.inspectionDigest(), precondition, slots, groups, round);
        return new RepairWorkspaceSnapshot(
                workspaceId, workspaceDigest(draft), draft.baseRevision(), draft.inspectionDigest(),
                draft.precondition(), draft.slots(), draft.groups(), draft.repairRound());
    }

    private static Set<Integer> rejectedSlots(
            List<? extends Operation> operations, List<StructuredDiagnostic> diagnostics) {
        Set<Integer> result = new LinkedHashSet<>();
        for (int index = 0; index < operations.size(); index++) {
            Operation operation = operations.get(index);
            if (diagnostics.stream().anyMatch(value -> diagnosticMatches(operation, value))) result.add(index);
        }
        if (result.isEmpty() && !diagnostics.isEmpty()) result.add(0);
        return result;
    }

    private static Set<Integer> directlyOwnedSlots(
            List<? extends Operation> operations,
            List<StructuredDiagnostic> diagnostics,
            String modulePath) {
        Set<Integer> result = new LinkedHashSet<>();
        for (StructuredDiagnostic diagnostic : diagnostics) {
            for (int index = 0; index < operations.size(); index++) {
                Operation operation = operations.get(index);
                SemanticId produced = operation instanceof AddDeclaration value
                        ? declarationSemanticId(value.declaration(), modulePath) : null;
                boolean ownsDiagnostic = operation instanceof AddDeclaration
                        ? produced != null && !produced.equals(operation.targetId())
                                && produced.equals(diagnostic.ownerId())
                        : operation.targetId().equals(diagnostic.ownerId());
                if (ownsDiagnostic) result.add(index);
            }
        }
        if (!result.isEmpty()) return result;
        for (StructuredDiagnostic diagnostic : diagnostics) {
            for (int index = 0; index < operations.size(); index++) {
                Operation operation = operations.get(index);
                SemanticId produced = operation instanceof AddDeclaration value
                        ? declarationSemanticId(value.declaration(), modulePath) : null;
                boolean ownsRepairScope = diagnostic.repairScopes().stream().anyMatch(scope ->
                        (operation instanceof AddDeclaration
                                ? produced != null && !produced.equals(operation.targetId())
                                        && scope.ownerId().equals(produced)
                                : scope.ownerId().equals(operation.targetId()))
                                && scope.operation().equals(operationName(operation)));
                if (ownsRepairScope) result.add(index);
            }
        }
        return result;
    }

    private static boolean isOperationContractDiagnostic(StructuredDiagnostic diagnostic) {
        return switch (diagnostic.code()) {
            case "CP1004", "CP1005", "CP1007", "CP1012", "CP1013" -> true;
            default -> false;
        };
    }

    private static boolean previouslyStaged(
            List<RepairSlot> previousSlots,
            String slotId,
            Map<String, String> payload) {
        String fingerprint = digest(CompilerProtocolJson.encode(payload));
        return previousSlots.stream().anyMatch(previous ->
                previous.slotId().equals(slotId)
                        && previous.payloadFingerprint().equals(fingerprint)
                        && (previous.status() == RepairSlotStatus.STAGED
                                || previous.status() == RepairSlotStatus.SEALED));
    }

    private static boolean diagnosticMatches(Operation operation, StructuredDiagnostic diagnostic) {
        if (operation.targetId().equals(diagnostic.ownerId())) return true;
        if (diagnostic.relatedIds().contains(operation.targetId())) return true;
        if (operation instanceof AddDeclaration value) {
            SemanticId produced = declarationSemanticId(value.declaration(), "/generated/app.deal");
            if (produced != null && (produced.equals(diagnostic.ownerId())
                    || diagnostic.relatedIds().contains(produced))) return true;
            if (produced != null && diagnostic.repairScopes().stream()
                    .anyMatch(scope -> scope.ownerId().equals(produced)
                            && scope.operation().equals(ADD_DECLARATION))) return true;
        }
        return diagnostic.repairScopes().stream().anyMatch(scope ->
                scope.ownerId().equals(operation.targetId())
                        && scope.operation().equals(operationName(operation)));
    }

    /** Returns the compiler-owned identity produced by one complete declaration, if valid. */
    public static SemanticId declarationSemanticId(String declaration, String modulePath) {
        String identity = singleDeclarationIdentity(declaration, modulePath);
        return identity.equals("invalid-or-multiple-declarations")
                        || identity.equals("unsupported-declaration")
                ? null : new SemanticId(identity);
    }

    private static List<Set<Integer>> operationDependencies(
            List<? extends Operation> operations,
            List<SemanticId> produced) {
        List<Set<Integer>> result = new ArrayList<>();
        for (int index = 0; index < operations.size(); index++) result.add(new LinkedHashSet<>());
        for (int consumer = 0; consumer < operations.size(); consumer++) {
            Operation operation = operations.get(consumer);
            String source = String.join("\n", payload(operation).values());
            for (int provider = 0; provider < produced.size(); provider++) {
                SemanticId id = produced.get(provider);
                if (consumer == provider || id == null) continue;
                String name = id.value().substring(id.value().lastIndexOf(':') + 1);
                if (containsIdentifier(source, name)) result.get(consumer).add(provider);
            }
            for (int other = 0; other < operations.size(); other++) {
                if (consumer == other || operation instanceof AddDeclaration
                        || operations.get(other) instanceof AddDeclaration) continue;
                if (operation.targetId().equals(operations.get(other).targetId())) {
                    result.get(consumer).add(other);
                }
            }
        }
        return result;
    }

    private static Set<Integer> dependencyClosure(int operation, List<Set<Integer>> dependencies) {
        Set<Integer> result = new LinkedHashSet<>();
        java.util.ArrayDeque<Integer> pending = new java.util.ArrayDeque<>();
        pending.add(operation);
        while (!pending.isEmpty()) {
            int current = pending.removeFirst();
            if (!result.add(current)) continue;
            pending.addAll(dependencies.get(current));
        }
        return result;
    }

    private static SccResult stronglyConnectedComponents(List<Set<Integer>> dependencies) {
        int size = dependencies.size();
        int[] index = new int[size];
        int[] low = new int[size];
        int[] groupByNode = new int[size];
        boolean[] onStack = new boolean[size];
        java.util.Arrays.fill(index, -1);
        java.util.Arrays.fill(groupByNode, -1);
        java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
        int[] nextIndex = {0};
        int[] nextGroup = {0};
        for (int node = 0; node < size; node++) {
            if (index[node] < 0) strongConnect(
                    node, dependencies, index, low, groupByNode, onStack, stack, nextIndex, nextGroup);
        }
        List<Set<Integer>> groupDependencies = new ArrayList<>();
        for (int group = 0; group < nextGroup[0]; group++) groupDependencies.add(new LinkedHashSet<>());
        for (int node = 0; node < size; node++) {
            for (int dependency : dependencies.get(node)) {
                int from = groupByNode[node];
                int to = groupByNode[dependency];
                if (from != to) groupDependencies.get(from).add(to);
            }
        }
        return new SccResult(groupByNode, groupDependencies);
    }

    private static void strongConnect(
            int node,
            List<Set<Integer>> dependencies,
            int[] index,
            int[] low,
            int[] groupByNode,
            boolean[] onStack,
            java.util.ArrayDeque<Integer> stack,
            int[] nextIndex,
            int[] nextGroup) {
        index[node] = nextIndex[0];
        low[node] = nextIndex[0]++;
        stack.push(node);
        onStack[node] = true;
        for (int dependency : dependencies.get(node)) {
            if (index[dependency] < 0) {
                strongConnect(dependency, dependencies, index, low, groupByNode,
                        onStack, stack, nextIndex, nextGroup);
                low[node] = Math.min(low[node], low[dependency]);
            } else if (onStack[dependency]) {
                low[node] = Math.min(low[node], index[dependency]);
            }
        }
        if (low[node] != index[node]) return;
        while (true) {
            int member = stack.pop();
            onStack[member] = false;
            groupByNode[member] = nextGroup[0];
            if (member == node) break;
        }
        nextGroup[0]++;
    }

    private static boolean groupDependsOn(
            int group,
            int target,
            List<Set<Integer>> dependencies) {
        if (group == target) return true;
        Set<Integer> visited = new LinkedHashSet<>();
        java.util.ArrayDeque<Integer> pending = new java.util.ArrayDeque<>(dependencies.get(group));
        while (!pending.isEmpty()) {
            int current = pending.removeFirst();
            if (!visited.add(current)) continue;
            if (current == target) return true;
            pending.addAll(dependencies.get(current));
        }
        return false;
    }

    private record SccResult(int[] groupByNode, List<Set<Integer>> groupDependencies) {}

    private static boolean containsIdentifier(String source, String identifier) {
        return new Lexer(source, "/generated/repair-slot.deal").tokenize().tokens().stream()
                .anyMatch(token -> token.type() == TokenType.IDENTIFIER && token.lexeme().equals(identifier));
    }

    private static Map<String, String> payload(Operation operation) {
        return switch (operation) {
            case AddDeclaration value -> Map.of("declaration", value.declaration());
            case ReplaceDeclaration value -> Map.of("declaration", value.declaration());
            case ReplaceFunctionBody value -> Map.of("body", value.body());
            case ReplaceBlockBody value -> Map.of("body", value.body());
            case RemoveDeclaration ignored -> Map.of();
        };
    }

    private static Operation operation(String name, SemanticId target, Map<String, String> payload) {
        return switch (name) {
            case ADD_DECLARATION -> new AddDeclaration(target, payload.get("declaration"));
            case REMOVE_DECLARATION -> new RemoveDeclaration(target);
            case REPLACE_DECLARATION -> new ReplaceDeclaration(target, payload.get("declaration"));
            case REPLACE_FUNCTION_BODY -> new ReplaceFunctionBody(target, payload.get("body"));
            case REPLACE_BLOCK_BODY -> new ReplaceBlockBody(target, payload.get("body"));
            default -> throw new IllegalArgumentException("Unknown DEAL repair operation " + name);
        };
    }

    private static String workspaceDigest(RepairWorkspaceSnapshot workspace) {
        return digest(CompilerProtocolJson.encode(List.of(
                workspace.workspaceId(), workspace.baseRevision(), workspace.inspectionDigest(),
                workspace.precondition(), workspace.slots(), workspace.groups(), workspace.repairRound())));
    }

    private static RepairWorkspaceResult rejectedWorkspace(
            String source, RepairWorkspaceSnapshot workspace, String code, String message) {
        StructuredDiagnostic diagnostic = new StructuredDiagnostic(
                code, "error", message, null, workspace.slots().isEmpty()
                        ? new SemanticId("deal:module:app.deal") : workspace.slots().get(0).targetId(),
                "valid repair workspace", "invalid repair request", List.of(), List.of(), "inspectChange");
        return new RepairWorkspaceResult(false, source, digest(source), workspace, null, List.of(diagnostic));
    }

    public static ChangeResult apply(
            String source,
            String modulePath,
            String baseDigest,
            List<? extends Operation> operations,
            ModuleResolver resolver) {
        return apply(source, modulePath, baseDigest, operations, resolver, SourceAdapter.IDENTITY);
    }

    public static ChangeResult apply(
            String source,
            String modulePath,
            String baseDigest,
            List<? extends Operation> operations,
            SourceAdapter adapter) {
        return apply(source, modulePath, baseDigest, operations, rejectingResolver(), adapter);
    }

    public static ChangeResult apply(
            String source,
            String modulePath,
            String baseDigest,
            List<? extends Operation> operations,
            ModuleResolver resolver,
            SourceAdapter adapter) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(modulePath, "modulePath");
        Objects.requireNonNull(baseDigest, "baseDigest");
        List<? extends Operation> requestedOperations = List.copyOf(operations);
        if (requestedOperations.isEmpty()) throw new IllegalArgumentException("A change requires at least one operation");

        Analysis base = analyze(source, modulePath, resolver, adapter);
        if (!base.inspection().sourceDigest().equals(baseDigest)) {
            return rejected(base, diagnostic(
                    "CP1001",
                    "Stale source digest; inspect the current source before editing",
                    base.moduleId(),
                    null,
                    base.inspection().sourceDigest(),
                    baseDigest,
                    List.of(),
                    "inspectCanonicalApp"));
        }
        if (hasErrors(base.inspection().diagnostics())) {
            return rejected(base, diagnostic(
                    "CP1002",
                    "Base source is not compiler-clean",
                    base.moduleId(),
                    null,
                    "valid base source",
                    "compiler diagnostics",
                    List.of(),
                    "inspectCanonicalApp"));
        }

        List<Replacement> replacements = new ArrayList<>();
        Set<SemanticId> changedSymbols = new LinkedHashSet<>();
        Set<SemanticId> changedNodes = new LinkedHashSet<>();
        for (Operation operation : requestedOperations) {
            Target target = base.targets().get(operation.targetId());
            if (target == null) {
                return rejected(base, diagnostic(
                        "CP1003",
                        "Unknown or stale semantic target " + operation.targetId().value(),
                        operation.targetId(),
                        null,
                        "target issued for " + baseDigest,
                        "missing target",
                        List.of(),
                        "inspectDealSymbol"));
            }
            switch (operation) {
                case AddDeclaration value -> {
                    if (!target.kind().equals("module")) {
                        return wrongKind(base, operation, target, "module");
                    }
                    if (value.declaration() == null || value.declaration().isBlank()) {
                        return nullSource(base, operation, target, "DEAL declaration");
                    }
                    String addedIdentity = singleDeclarationIdentity(value.declaration(), modulePath);
                    if (addedIdentity.equals("invalid-or-multiple-declarations")
                            || addedIdentity.equals("unsupported-declaration")) {
                        return rejected(base, diagnostic(
                                "CP1013",
                                "addDeclaration accepts exactly one complete top-level class or function",
                                target.id(), target.range(), "one class or function declaration",
                                addedIdentity,
                                List.of(new RepairScope(ADD_DECLARATION, target.id())),
                                "queryDealModule"));
                    }
                    String separator = source.isEmpty() || source.endsWith("\n") ? "" : "\n";
                    replacements.add(new Replacement(
                            target.end(), target.end(), separator + value.declaration().strip() + "\n", false));
                }
                case RemoveDeclaration ignored -> {
                    if (!target.kind().equals("declaration")) {
                        return wrongKind(base, operation, target, "declaration");
                    }
                    replacements.add(new Replacement(target.start(), target.end(), "", false));
                    changedSymbols.add(target.ownerId());
                }
                case ReplaceDeclaration value -> {
                    if (!target.kind().equals("declaration")) {
                        return wrongKind(base, operation, target, "declaration");
                    }
                    if (value.declaration() == null || value.declaration().isBlank()) {
                        return nullSource(base, operation, target, "DEAL declaration");
                    }
                    String replacementIdentity = singleDeclarationIdentity(
                            value.declaration(), modulePath);
                    if (!target.id().value().equals(replacementIdentity)) {
                        return rejected(base, diagnostic(
                                "CP1012",
                                "Declaration replacement must contain exactly one declaration with the same identity",
                                target.id(), target.range(), target.id().value(), replacementIdentity,
                                List.of(new RepairScope(REPLACE_DECLARATION, target.id())),
                                "queryDealSymbol"));
                    }
                    replacements.add(new Replacement(
                            target.start(), target.end(), value.declaration().strip(), false));
                    changedSymbols.add(target.ownerId());
                }
                case ReplaceFunctionBody value -> {
                    if (!target.kind().equals("function-body")) {
                        return wrongKind(base, operation, target, "function-body");
                    }
                    if (value.body() == null) return nullSource(base, operation, target, "DEAL statements");
                    if (looksLikeCompleteFunctionDeclaration(value.body())) {
                        return rejected(base, bodyStatementsOnly(operation, target));
                    }
                    replacements.add(new Replacement(
                            target.contentStart(), target.contentEnd(), value.body().trim(), true));
                    changedSymbols.add(target.ownerId());
                    changedNodes.add(target.id());
                }
                case ReplaceBlockBody value -> {
                    if (!target.kind().equals("block")) {
                        return wrongKind(base, operation, target, "block");
                    }
                    if (value.body() == null) return nullSource(base, operation, target, "DEAL statements");
                    if (looksLikeCompleteFunctionDeclaration(value.body())) {
                        return rejected(base, bodyStatementsOnly(operation, target));
                    }
                    replacements.add(new Replacement(
                            target.contentStart(), target.contentEnd(), value.body().trim(), true));
                    changedSymbols.add(target.ownerId());
                    changedNodes.add(target.id());
                }
            }
        }
        replacements.sort(Comparator.comparingInt(Replacement::start));
        for (int index = 1; index < replacements.size(); index++) {
            if (replacements.get(index - 1).end() > replacements.get(index).start()) {
                return rejected(base, diagnostic(
                        "CP1006",
                        "One transaction cannot replace overlapping semantic nodes",
                        base.moduleId(),
                        null,
                        "non-overlapping operations",
                        "overlapping ranges",
                        List.of(),
                        "inspectDealSymbol"));
            }
        }

        String candidate = applyReplacements(source, replacements);
        Analysis checked = analyze(candidate, modulePath, resolver, adapter);
        if (hasErrors(checked.inspection().diagnostics())) {
            List<StructuredDiagnostic> scoped = checked.inspection().diagnostics().stream()
                    .map(value -> scopeDiagnostic(value, requestedOperations, base))
                    .toList();
            return new ChangeResult(
                    false,
                    source,
                    base.inspection().sourceDigest(),
                    base.inspection(),
                    emptyImpact(base),
                    scoped);
        }

        Set<SemanticId> beforeSymbols = new LinkedHashSet<>(base.symbolsById().keySet());
        Set<SemanticId> afterSymbols = new LinkedHashSet<>(checked.symbolsById().keySet());
        Set<SemanticId> structuralChanges = new LinkedHashSet<>(beforeSymbols);
        structuralChanges.addAll(afterSymbols);
        Set<SemanticId> commonSymbols = new LinkedHashSet<>(beforeSymbols);
        commonSymbols.retainAll(afterSymbols);
        structuralChanges.removeAll(commonSymbols);
        changedSymbols.addAll(structuralChanges);

        Set<SemanticId> affectedCallers = new LinkedHashSet<>();
        for (SemanticId changed : changedSymbols) {
            checked.symbolsById().getOrDefault(changed, emptySymbol(changed)).callers()
                    .forEach(affectedCallers::add);
        }
        boolean interfaceChanged = !fingerprint(base.inspection().appInterface())
                .equals(fingerprint(checked.inspection().appInterface()));
        boolean schemaChanged = !rootSchemaFingerprint(base.inspection())
                .equals(rootSchemaFingerprint(checked.inspection()));
        ImpactReport impact = new ImpactReport(
                List.copyOf(changedSymbols),
                List.copyOf(changedNodes),
                List.copyOf(affectedCallers),
                changedSymbols.stream().filter(id -> id.value().contains("function")).toList(),
                interfaceChanged,
                schemaChanged,
                schemaChanged,
                Map.of(
                        "beforeSource", base.inspection().sourceDigest(),
                        "afterSource", checked.inspection().sourceDigest(),
                        "beforeInterface", fingerprint(base.inspection().appInterface()),
                        "afterInterface", fingerprint(checked.inspection().appInterface())));
        return new ChangeResult(
                true,
                candidate,
                checked.inspection().sourceDigest(),
                checked.inspection(),
                impact,
                List.of());
    }

    public static String digest(String source) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Analysis analyze(
            String source, String modulePath, ModuleResolver resolver, SourceAdapter adapter) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(modulePath, "modulePath");
        Objects.requireNonNull(adapter, "adapter");
        String parserSource = Objects.requireNonNull(adapter.parserSource(source), "adapter parser source");
        requireSameGeometry(source, parserSource);
        String sourceDigest = digest(source);
        SemanticId moduleId = new SemanticId("deal:module:" + modulePath);
        LexResult lexed = new Lexer(parserSource, modulePath).tokenize();
        ParseResult parsed = new Parser(lexed.tokens(), modulePath).parse();
        List<CompilerDiagnostic> rawDiagnostics = new ArrayList<>(lexed.diagnostics());
        rawDiagnostics.addAll(parsed.diagnostics());
        NameResolver names = new NameResolver(modulePath, resolver);
        SymbolTable symbols = names.resolve(parsed.program());
        rawDiagnostics.addAll(names.diagnostics());
        if (rawDiagnostics.stream().noneMatch(value -> value.severity().equals("error"))) {
            CheckResult checked = TypeChecker.check(modulePath, symbols, names, parsed.program());
            rawDiagnostics.addAll(checked.diagnostics());
        }

        SourceIndex index = new SourceIndex(source);
        Map<String, FunctionInfo> functionsByName = new LinkedHashMap<>();
        Map<String, ClassInfo> classesByName = new LinkedHashMap<>();
        for (StatementNode statement : parsed.program().statements()) {
            boolean exported = statement instanceof ExportDeclaration;
            StatementNode declaration = exported ? ((ExportDeclaration) statement).declaration() : statement;
            Span declarationSpan = declarationSpan(statement, declaration);
            if (declaration instanceof FunctionDeclaration function) {
                SemanticId id = symbolId(modulePath, "function", function.name());
                functionsByName.put(function.name(), new FunctionInfo(id, function, declarationSpan, exported));
            } else if (declaration instanceof ClassDeclaration value) {
                SemanticId id = symbolId(modulePath, "class", value.name());
                classesByName.put(value.name(), new ClassInfo(id, value, declarationSpan, exported));
            }
        }

        Map<SemanticId, Target> targets = new LinkedHashMap<>();
        targets.put(moduleId, new Target(
                moduleId, moduleId, "module", null, 0, source.length(), source.length(), source.length()));
        for (ClassInfo info : classesByName.values()) {
            int start = index.offset(info.declarationSpan().startLine(), info.declarationSpan().startColumn());
            int end = index.offsetAfter(info.declarationSpan().endLine(), info.declarationSpan().endColumn());
            targets.put(info.id(), new Target(
                    info.id(), info.id(), "declaration", range(info.declarationSpan()), start, end, start, end));
        }
        for (FunctionInfo info : functionsByName.values()) {
            int start = index.offset(info.declarationSpan().startLine(), info.declarationSpan().startColumn());
            int end = index.offsetAfter(info.declarationSpan().endLine(), info.declarationSpan().endColumn());
            targets.put(info.id(), new Target(
                    info.id(), info.id(), "declaration", range(info.declarationSpan()), start, end, start, end));
        }
        List<NodeSnapshot> nodes = new ArrayList<>();
        Map<SemanticId, Set<SemanticId>> callees = new LinkedHashMap<>();
        for (FunctionInfo info : functionsByName.values()) {
            Set<String> calledNames = new LinkedHashSet<>();
            collectCalls(info.function().body(), calledNames);
            Set<SemanticId> calledIds = new LinkedHashSet<>();
            calledNames.forEach(name -> Optional.ofNullable(functionsByName.get(name))
                    .map(FunctionInfo::id).ifPresent(calledIds::add));
            callees.put(info.id(), calledIds);
            collectBlocks(info, info.function().body(), "body", sourceDigest, index, targets, nodes, true);
        }
        Map<SemanticId, Set<SemanticId>> callers = new LinkedHashMap<>();
        callees.forEach((caller, values) -> values.forEach(callee ->
                callers.computeIfAbsent(callee, ignored -> new LinkedHashSet<>()).add(caller)));

        List<SymbolSnapshot> symbolSnapshots = new ArrayList<>();
        for (ClassInfo info : classesByName.values()) {
            String text = index.slice(info.declarationSpan());
            symbolSnapshots.add(new SymbolSnapshot(
                    info.id(), "class", info.value().name(), range(info.declarationSpan()),
                    firstLine(text), digest(text), List.of(), List.of(), List.of()));
        }
        for (FunctionInfo info : functionsByName.values()) {
            String text = index.slice(info.declarationSpan());
            symbolSnapshots.add(new SymbolSnapshot(
                    info.id(), "function", info.function().name(), range(info.declarationSpan()),
                    signature(index, info.function()), digest(text),
                    List.copyOf(callers.getOrDefault(info.id(), Set.of())),
                    List.copyOf(callees.getOrDefault(info.id(), Set.of())),
                    List.copyOf(callees.getOrDefault(info.id(), Set.of()))));
        }
        symbolSnapshots.sort(Comparator.comparing(value -> value.id().value()));
        Map<SemanticId, SymbolSnapshot> symbolsById = new LinkedHashMap<>();
        symbolSnapshots.forEach(value -> symbolsById.put(value.id(), value));

        AppInterfaceSnapshot appInterface = appInterface(
                source,
                modulePath,
                functionsByName,
                classesByName,
                index);
        List<StructuredDiagnostic> diagnostics = rawDiagnostics.stream()
                .map(value -> diagnostic(value, moduleId, functionsByName.values()))
                .toList();
        Inspection inspection = new Inspection(
                CompilerProtocol.VERSION,
                sourceDigest,
                moduleId,
                symbolSnapshots,
                nodes,
                appInterface,
                ALLOWED_OPERATIONS,
                diagnostics);
        return new Analysis(source, inspection, moduleId, targets, symbolsById);
    }

    private static void requireSameGeometry(String source, String parserSource) {
        String[] canonicalLines = source.split("\\n", -1);
        String[] parserLines = parserSource.split("\\n", -1);
        if (canonicalLines.length != parserLines.length) {
            throw new IllegalArgumentException("Source adapter must preserve line count");
        }
        for (int index = 0; index < canonicalLines.length; index++) {
            if (canonicalLines[index].codePointCount(0, canonicalLines[index].length())
                    != parserLines[index].codePointCount(0, parserLines[index].length())) {
                throw new IllegalArgumentException(
                        "Source adapter must preserve Unicode-scalar columns on line " + (index + 1));
            }
        }
    }

    private static void collectBlocks(
            FunctionInfo owner,
            Block block,
            String path,
            String sourceDigest,
            SourceIndex index,
            Map<SemanticId, Target> targets,
            List<NodeSnapshot> nodes,
            boolean functionBody) {
        SemanticId id = nodeId(sourceDigest, owner.id(), path);
        int start = index.offset(block.span().startLine(), block.span().startColumn()) + 1;
        int end = index.offsetAfter(block.span().endLine(), block.span().endColumn()) - 1;
        String kind = functionBody ? "function-body" : "block";
        Target target = new Target(id, owner.id(), kind, range(block.span()), start - 1, end + 1, start, end);
        targets.put(id, target);
        nodes.add(new NodeSnapshot(id, owner.id(), kind, range(block.span()), digest(index.slice(block.span()))));
        int child = 0;
        for (StatementNode statement : block.statements()) {
            collectNestedBlocks(owner, statement, path + "/" + child++, sourceDigest, index, targets, nodes);
        }
    }

    private static void collectNestedBlocks(
            FunctionInfo owner,
            StatementNode statement,
            String path,
            String sourceDigest,
            SourceIndex index,
            Map<SemanticId, Target> targets,
            List<NodeSnapshot> nodes) {
        switch (statement) {
            case Block value -> collectBlocks(owner, value, path, sourceDigest, index, targets, nodes, false);
            case IfStatement value -> {
                collectBlocks(owner, value.thenBlock(), path + "/then", sourceDigest, index, targets, nodes, false);
                value.elseBranch().ifPresent(branch -> {
                    if (branch instanceof Either.Left<IfStatement, Block> left) {
                        collectNestedBlocks(owner, left.value(), path + "/else-if", sourceDigest, index, targets, nodes);
                    } else if (branch instanceof Either.Right<IfStatement, Block> right) {
                        collectBlocks(owner, right.value(), path + "/else", sourceDigest, index, targets, nodes, false);
                    }
                });
            }
            case WhileStatement value -> collectBlocks(owner, value.body(), path + "/while", sourceDigest, index, targets, nodes, false);
            case ForStatement value -> collectBlocks(owner, value.body(), path + "/for", sourceDigest, index, targets, nodes, false);
            case ForOfStatement value -> collectBlocks(owner, value.body(), path + "/for-of", sourceDigest, index, targets, nodes, false);
            case TryStatement value -> {
                collectBlocks(owner, value.tryBlock(), path + "/try", sourceDigest, index, targets, nodes, false);
                collectBlocks(owner, value.catchBlock(), path + "/catch", sourceDigest, index, targets, nodes, false);
            }
            default -> { }
        }
    }

    private static void collectCalls(Block block, Set<String> names) {
        block.statements().forEach(statement -> collectCalls(statement, names));
    }

    private static void collectCalls(StatementNode statement, Set<String> names) {
        switch (statement) {
            case Block value -> collectCalls(value, names);
            case VariableDeclaration value -> collectCalls(value.initializer(), names);
            case ReturnStatement value -> value.expr().ifPresent(expression -> collectCalls(expression, names));
            case ExpressionStatement value -> collectCalls(value.expr(), names);
            case IfStatement value -> {
                collectCalls(value.condition(), names);
                collectCalls(value.thenBlock(), names);
                value.elseBranch().ifPresent(branch -> {
                    if (branch instanceof Either.Left<IfStatement, Block> left) collectCalls(left.value(), names);
                    if (branch instanceof Either.Right<IfStatement, Block> right) collectCalls(right.value(), names);
                });
            }
            case WhileStatement value -> { collectCalls(value.condition(), names); collectCalls(value.body(), names); }
            case ForStatement value -> {
                value.init().ifPresent(initial -> {
                    if (initial instanceof ForInit.VarDecl variable) collectCalls(variable.decl().initializer(), names);
                    if (initial instanceof ForInit.AssignExpr assignment) collectCalls(assignment.expr(), names);
                });
                value.condition().ifPresent(expression -> collectCalls(expression, names));
                value.update().ifPresent(expression -> collectCalls(expression, names));
                collectCalls(value.body(), names);
            }
            case ForOfStatement value -> { collectCalls(value.iterable(), names); collectCalls(value.body(), names); }
            case DeleteStatement value -> collectCalls(value.target(), names);
            case ThrowStatement value -> collectCalls(value.expr(), names);
            case TryStatement value -> { collectCalls(value.tryBlock(), names); collectCalls(value.catchBlock(), names); }
            default -> { }
        }
    }

    private static void collectCalls(ExpressionNode expression, Set<String> names) {
        switch (expression) {
            case CallExpr value -> {
                if (value.callee() instanceof IdentifierExpr identifier) names.add(identifier.name());
                collectCalls(value.callee(), names);
                value.args().forEach(argument -> collectCalls(argument, names));
            }
            case BinaryExpr value -> { collectCalls(value.left(), names); collectCalls(value.right(), names); }
            case UnaryExpr value -> collectCalls(value.expr(), names);
            case MemberAccessExpr value -> collectCalls(value.object(), names);
            case IndexExpr value -> { collectCalls(value.array(), names); collectCalls(value.index(), names); }
            case ArrayLiteralExpr value -> value.elements().forEach(element -> collectCalls(element, names));
            case ObjectLiteralExpr value -> value.properties().stream().map(Property::value).forEach(item -> collectCalls(item, names));
            case FunctionExpr value -> collectCalls(value.body(), names);
            case HasExpr value -> collectCalls(value.object(), names);
            case AssignmentExpr value -> { collectCalls(value.target(), names); collectCalls(value.value(), names); }
            case TemplateLiteralExpr value -> value.parts().forEach(item -> collectCalls(item, names));
            case AwaitExpression value -> collectCalls(value.callee(), names);
            default -> { }
        }
    }

    private static AppInterfaceSnapshot appInterface(
            String source,
            String modulePath,
            Map<String, FunctionInfo> functions,
            Map<String, ClassInfo> classes,
            SourceIndex index) {
        FunctionInfo initial = functions.get("initialState");
        if (initial == null || initial.function().isExternal()) return null;
        String root = typeName(initial.function().returnType());
        ClassInfo rootClass = classes.get(root);
        if (rootClass == null) return null;
        Set<String> actionNames = new LinkedHashSet<>();
        for (FunctionInfo info : functions.values()) {
            FunctionDeclaration function = info.function();
            if (function.params().size() != 2) continue;
            if (!typeName(function.params().get(0).type()).equals(root)) continue;
            if (!typeName(function.returnType()).equals(root)) continue;
            actionNames.add(typeName(function.params().get(1).type()));
        }
        List<TypeSnapshot> types = classes.values().stream()
                .filter(info -> !actionNames.contains(info.value().name()))
                .map(info -> typeSnapshot(modulePath, info, index))
                .toList();
        List<TypeSnapshot> actions = actionNames.stream()
                .map(classes::get)
                .filter(Objects::nonNull)
                .map(info -> typeSnapshot(modulePath, info, index))
                .toList();
        List<String> capabilities = new ArrayList<>();
        Matcher matcher = CAPABILITY.matcher(source);
        while (matcher.find()) capabilities.add(matcher.group(1));
        String schema = classes.get(root).value().fields().stream()
                .map(field -> field.name() + ":" + typeName(field.type()) + ":" + field.optional())
                .reduce("", (left, right) -> left + "|" + right);
        String rootFingerprint = digest(schema);
        String interfaceFingerprint = digest(root + "|" + types + "|" + actions + "|" + capabilities);
        return new AppInterfaceSnapshot(
                "app-interface-v1",
                interfaceFingerprint,
                root,
                rootFingerprint,
                types,
                actions,
                capabilities);
    }

    private static TypeSnapshot typeSnapshot(String modulePath, ClassInfo info, SourceIndex index) {
        List<FieldSnapshot> fields = info.value().fields().stream()
                .map(field -> new FieldSnapshot(
                        field.name(),
                        typeName(field.type()),
                        field.optional(),
                        field.type() instanceof ArrayType))
                .toList();
        return new TypeSnapshot(info.id(), info.value().name(), fields);
    }

    private static String typeName(TypeNode type) {
        return switch (type) {
            case NamedType value -> value.name();
            case QualifiedType value -> value.moduleName() + "." + value.typeName();
            case ArrayType value -> typeName(value.elementType()) + "[]";
            case NullableType value -> typeName(value.innerType()) + "?";
            default -> type.toString();
        };
    }

    private static StructuredDiagnostic diagnostic(
            CompilerDiagnostic value,
            SemanticId moduleId,
            Iterable<FunctionInfo> functions) {
        FunctionInfo owner = null;
        for (FunctionInfo function : functions) {
            if (contains(function.declarationSpan(), value.line(), value.column())) {
                owner = function;
                break;
            }
        }
        SemanticId ownerId = owner == null ? moduleId : owner.id();
        List<RepairScope> scopes = owner == null
                ? List.of()
                : List.of(new RepairScope(REPLACE_FUNCTION_BODY, owner.id()));
        var compilerRange = value.range();
        String expected = value.code().equals("E3010")
                ? "Both '+' operands must be numeric, or both must be string; DEAL has no implicit coercion"
                : "";
        return new StructuredDiagnostic(
                value.code(),
                value.severity(),
                value.message(),
                new SourceRange(
                        compilerRange.file(),
                        compilerRange.startLine(),
                        compilerRange.startColumn(),
                        compilerRange.endLine(),
                        compilerRange.endColumn()),
                ownerId,
                expected,
                "",
                List.of(),
                scopes,
                owner == null ? "inspectCanonicalApp" : "queryDealSymbol(" + owner.id().value() + ")");
    }

    private static StructuredDiagnostic diagnostic(
            String code,
            String message,
            SemanticId owner,
            SourceRange range,
            String expected,
            String actual,
            List<RepairScope> scopes,
            String contextQuery) {
        return new StructuredDiagnostic(
                code, "error", message, range, owner, expected, actual,
                List.of(), scopes, contextQuery);
    }

    private static StructuredDiagnostic scopeDiagnostic(
            StructuredDiagnostic diagnostic,
            List<? extends Operation> operations,
            Analysis base) {
        List<Operation> ownedOperations = operations.stream()
                .filter(operation -> operationOwner(operation, base).equals(diagnostic.ownerId()))
                .map(Operation.class::cast)
                .toList();
        List<RepairScope> scopes = ownedOperations.stream()
                .map(operation -> new RepairScope(operationName(operation), operation.targetId()))
                .toList();
        return new StructuredDiagnostic(
                diagnostic.code(), diagnostic.severity(), diagnostic.message(), diagnostic.range(),
                diagnostic.ownerId(), diagnostic.expected(), diagnostic.actual(),
                ownedOperations.stream().map(Operation::targetId).toList(), scopes,
                diagnostic.contextQuery());
    }

    private static SemanticId operationOwner(Operation operation, Analysis base) {
        if (operation instanceof AddDeclaration value) {
            String modulePrefix = "deal:module:";
            String modulePath = base.moduleId().value().startsWith(modulePrefix)
                    ? base.moduleId().value().substring(modulePrefix.length())
                    : base.moduleId().value();
            SemanticId produced = declarationSemanticId(value.declaration(), modulePath);
            return produced == null ? base.moduleId() : produced;
        }
        Target target = base.targets().get(operation.targetId());
        return target == null ? operation.targetId() : target.ownerId();
    }

    private static ChangeResult rejected(Analysis base, StructuredDiagnostic diagnostic) {
        return new ChangeResult(
                false,
                base.source(),
                base.inspection().sourceDigest(),
                base.inspection(),
                emptyImpact(base),
                List.of(diagnostic));
    }

    private static ChangeResult wrongKind(
            Analysis base, Operation operation, Target target, String expectedKind) {
        return rejected(base, diagnostic(
                "CP1005",
                "Operation does not match semantic target kind " + target.kind(),
                operation.targetId(),
                target.range(),
                expectedKind,
                target.kind(),
                List.of(),
                "inspectDealSymbol"));
    }

    private static ChangeResult nullSource(
            Analysis base, Operation operation, Target target, String expected) {
        return rejected(base, diagnostic(
                "CP1004",
                "Operation source cannot be null or blank",
                operation.targetId(),
                target.range(),
                expected,
                "null or blank",
                List.of(new RepairScope(operationName(operation), operation.targetId())),
                "queryDealNode"));
    }

    private static boolean looksLikeCompleteFunctionDeclaration(String source) {
        return COMPLETE_FUNCTION_DECLARATION.matcher(source).matches();
    }

    private static StructuredDiagnostic bodyStatementsOnly(Operation operation, Target target) {
        return diagnostic(
                "CP1007",
                "Body replacement accepts only statements inside the existing body; omit the function declaration and outer braces",
                operation.targetId(),
                target.range(),
                "statement list, for example: return state;",
                "complete function declaration",
                List.of(new RepairScope(operationName(operation), operation.targetId())),
                "queryDealNode(" + operation.targetId().value() + ")");
    }

    private static ImpactReport emptyImpact(Analysis base) {
        return new ImpactReport(
                List.of(), List.of(), List.of(), List.of(), false, false, false,
                Map.of("source", base.inspection().sourceDigest()));
    }

    private static String applyReplacements(String source, List<Replacement> replacements) {
        StringBuilder result = new StringBuilder(source);
        for (int index = replacements.size() - 1; index >= 0; index--) {
            Replacement replacement = replacements.get(index);
            String value = replacement.blockBody()
                    ? formatBody(replacement.source(), source, replacement.start())
                    : replacement.source();
            result.replace(replacement.start(), replacement.end(), value);
        }
        return result.toString();
    }

    private static String formatBody(String body, String source, int offset) {
        if (body.isBlank()) return "";
        int lineStart = source.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        String indentation = source.substring(lineStart, offset).replaceAll("[^ \\t]", "");
        String child = indentation + "  ";
        String normalized = body.lines().map(String::stripTrailing).reduce((a, b) -> a + "\n" + b).orElse("");
        return "\n" + normalized.lines().map(line -> child + line.stripLeading()).reduce((a, b) -> a + "\n" + b).orElse("") + "\n" + indentation;
    }

    private static String signature(SourceIndex index, FunctionDeclaration function) {
        int start = index.offset(function.span().startLine(), function.span().startColumn());
        int body = index.offset(function.body().span().startLine(), function.body().span().startColumn());
        return index.source().substring(start, body).trim();
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return (newline < 0 ? text : text.substring(0, newline)).trim();
    }

    private static String fingerprint(AppInterfaceSnapshot value) {
        return value == null ? "" : value.fingerprint();
    }

    private static String rootSchemaFingerprint(Inspection value) {
        return value.appInterface() == null ? "" : value.appInterface().rootSchemaFingerprint();
    }

    private static boolean contains(Span span, int line, int column) {
        boolean afterStart = line > span.startLine()
                || line == span.startLine() && column >= span.startColumn();
        boolean beforeEnd = line < span.endLine()
                || line == span.endLine() && column <= span.endColumn();
        return afterStart && beforeEnd;
    }

    private static boolean hasErrors(List<StructuredDiagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(value -> value.severity().equals("error"));
    }

    private static SourceRange range(Span value) {
        return new SourceRange(
                value.file(), value.startLine(), value.startColumn(), value.endLine(), value.endColumn());
    }

    private static Span declarationSpan(StatementNode statement, StatementNode declaration) {
        if (!(statement instanceof ExportDeclaration)) return statement.span();
        Span outer = statement.span();
        Span inner = declaration.span();
        return new Span(
                outer.file(), outer.startLine(), outer.startColumn(),
                inner.endLine(), inner.endColumn(),
                outer.startScalarOffset(), inner.endScalarOffset());
    }

    private static String singleDeclarationIdentity(String source, String modulePath) {
        String identitySource = source.replaceAll(
                "(?m)^\\s*//\\s*@[^\\r\\n]*(?:\\R|$)", "");
        LexResult lexed = new Lexer(identitySource, modulePath).tokenize();
        ParseResult parsed = new Parser(lexed.tokens(), modulePath).parse();
        if (lexed.diagnostics().stream().anyMatch(value -> value.severity().equals("error"))
                || parsed.diagnostics().stream().anyMatch(value -> value.severity().equals("error"))
                || parsed.program().statements().size() != 1) {
            return "invalid-or-multiple-declarations";
        }
        StatementNode statement = parsed.program().statements().get(0);
        StatementNode declaration = statement instanceof ExportDeclaration exported
                ? exported.declaration() : statement;
        if (declaration instanceof ClassDeclaration value) {
            return symbolId(modulePath, "class", value.name()).value();
        }
        if (declaration instanceof FunctionDeclaration value) {
            return symbolId(modulePath, "function", value.name()).value();
        }
        return "unsupported-declaration";
    }

    private static SemanticId symbolId(String modulePath, String kind, String name) {
        return new SemanticId("deal:" + modulePath + ":" + kind + ":" + name);
    }

    private static SemanticId nodeId(String sourceDigest, SemanticId owner, String path) {
        return new SemanticId("deal-node:" + digest(sourceDigest + "\u0000" + owner.value() + "\u0000" + path).substring(0, 24));
    }

    private static String operationName(Operation operation) {
        return switch (operation) {
            case AddDeclaration ignored -> ADD_DECLARATION;
            case RemoveDeclaration ignored -> REMOVE_DECLARATION;
            case ReplaceDeclaration ignored -> REPLACE_DECLARATION;
            case ReplaceFunctionBody ignored -> REPLACE_FUNCTION_BODY;
            case ReplaceBlockBody ignored -> REPLACE_BLOCK_BODY;
        };
    }

    private static RevisionRef revision(Analysis analysis) {
        return new RevisionRef(CompilerProtocol.VERSION, analysis.inspection().sourceDigest());
    }

    @SafeVarargs
    private static List<SemanticId> unique(List<SemanticId>... values) {
        Set<SemanticId> result = new LinkedHashSet<>();
        for (List<SemanticId> value : values) result.addAll(value);
        return List.copyOf(result);
    }

    private static List<OperationDescriptor> descriptorsFor(
            Analysis analysis, SemanticId symbolId, List<NodeSnapshot> nodes) {
        List<OperationDescriptor> result = new ArrayList<>();
        Target declaration = analysis.targets().get(symbolId);
        if (declaration != null) {
            result.add(descriptor(analysis, declaration));
            result.add(new OperationDescriptor(
                    REPLACE_DECLARATION, declaration.id(), declaration.kind(),
                    targetFingerprint(analysis, declaration.id()), List.of("declaration")));
        }
        for (NodeSnapshot node : nodes) {
            Target target = analysis.targets().get(node.id());
            if (target != null) result.add(descriptor(analysis, target));
        }
        return List.copyOf(result);
    }

    private static OperationDescriptor descriptor(Analysis analysis, Target target) {
        if (target == null) throw new IllegalArgumentException("Missing semantic target");
        return switch (target.kind()) {
            case "module" -> new OperationDescriptor(
                    ADD_DECLARATION, target.id(), target.kind(), targetFingerprint(analysis, target.id()),
                    List.of("declaration"));
            case "declaration" -> new OperationDescriptor(
                    REMOVE_DECLARATION, target.id(), target.kind(), targetFingerprint(analysis, target.id()),
                    List.of());
            case "function-body" -> new OperationDescriptor(
                    REPLACE_FUNCTION_BODY, target.id(), target.kind(), targetFingerprint(analysis, target.id()),
                    List.of("body"));
            case "block" -> new OperationDescriptor(
                    REPLACE_BLOCK_BODY, target.id(), target.kind(), targetFingerprint(analysis, target.id()),
                    List.of("body"));
            default -> throw new IllegalArgumentException("Unsupported semantic target kind " + target.kind());
        };
    }

    private static String targetFingerprint(Analysis analysis, SemanticId targetId) {
        if (targetId.equals(analysis.moduleId())) return analysis.inspection().sourceDigest();
        SymbolSnapshot symbol = analysis.symbolsById().get(targetId);
        if (symbol != null) return symbol.fingerprint();
        return analysis.inspection().nodes().stream()
                .filter(value -> value.id().equals(targetId))
                .map(NodeSnapshot::fingerprint)
                .findFirst()
                .orElse("");
    }

    private static SymbolSnapshot emptySymbol(SemanticId id) {
        return new SymbolSnapshot(id, "unknown", "", null, "", "", List.of(), List.of(), List.of());
    }

    private static ModuleResolver rejectingResolver() {
        return new ModuleResolver() {
            @Override
            public Map<String, Type> resolveModule(String modulePath, String importingModule, Set<String> inProgress)
                    throws ModuleNotFoundException {
                throw new ModuleNotFoundException("Module not available in standalone workspace: " + modulePath);
            }

            @Override
            public Symbol.ClassSymbol resolveClassSymbol(String className, String modulePath, String importingModule)
                    throws ModuleNotFoundException {
                throw new ModuleNotFoundException("Module not available in standalone workspace: " + modulePath);
            }
        };
    }

    private record FunctionInfo(
            SemanticId id,
            FunctionDeclaration function,
            Span declarationSpan,
            boolean exported) {}

    private record ClassInfo(
            SemanticId id,
            ClassDeclaration value,
            Span declarationSpan,
            boolean exported) {}

    private record Target(
            SemanticId id,
            SemanticId ownerId,
            String kind,
            SourceRange range,
            int start,
            int end,
            int contentStart,
            int contentEnd) {}

    private record Replacement(int start, int end, String source, boolean blockBody) {}

    private record Analysis(
            String source,
            Inspection inspection,
            SemanticId moduleId,
            Map<SemanticId, Target> targets,
            Map<SemanticId, SymbolSnapshot> symbolsById) {}

    private static final class SourceIndex {
        private final String source;
        private final int[] lineOffsets;

        private SourceIndex(String source) {
            this.source = source;
            List<Integer> offsets = new ArrayList<>();
            offsets.add(0);
            for (int index = 0; index < source.length(); index++) {
                if (source.charAt(index) == '\n') offsets.add(index + 1);
            }
            lineOffsets = offsets.stream().mapToInt(Integer::intValue).toArray();
        }

        private String source() { return source; }

        private int offset(int line, int column) {
            return lineOffsets[Math.max(0, line - 1)] + Math.max(0, column - 1);
        }

        private int offsetAfter(int line, int column) {
            return Math.min(source.length(), offset(line, column) + 1);
        }

        private String slice(Span span) {
            return source.substring(offset(span.startLine(), span.startColumn()), offsetAfter(span.endLine(), span.endColumn()));
        }
    }
}
