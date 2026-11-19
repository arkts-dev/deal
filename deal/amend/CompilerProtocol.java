package deal.amend;

import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.DiagnosticFacts;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Transport-neutral records shared by DEAL compiler frontends. */
public final class CompilerProtocol {
    public static final String VERSION = "compiler-protocol-v3";

    private CompilerProtocol() {}

    public record SemanticId(String value) {
        public SemanticId {
            Objects.requireNonNull(value, "value");
            if (value.isBlank()) throw new IllegalArgumentException("Semantic id cannot be blank");
        }
    }

    public record RevisionRef(String sourceDigest) {
        public RevisionRef {
            Objects.requireNonNull(sourceDigest, "sourceDigest");
        }
    }

    public record OperationDescriptor(String operation, SemanticId targetId, String targetFingerprint) {
        public OperationDescriptor {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(targetId, "targetId");
            Objects.requireNonNull(targetFingerprint, "targetFingerprint");
        }
    }

    public record RepairScope(String operation, SemanticId ownerId) {
        public RepairScope {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(ownerId, "ownerId");
        }
    }

    public record DependencyGroup(
            String groupId,
            List<String> dependsOn,
            String status) {
        public DependencyGroup {
            Objects.requireNonNull(groupId, "groupId");
            dependsOn = List.copyOf(dependsOn);
            Objects.requireNonNull(status, "status");
        }
    }

    public record ChangeInspection(
            RevisionRef revision,
            String inspectionDigest,
            List<OperationDescriptor> allowedOperations,
            List<StructuredDiagnostic> diagnostics) {
        public ChangeInspection {
            Objects.requireNonNull(revision, "revision");
            Objects.requireNonNull(inspectionDigest, "inspectionDigest");
            allowedOperations = List.copyOf(allowedOperations);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public enum RepairSlotStatus {
        SEALED,
        STAGED,
        REJECTED,
        BLOCKED,
        COMMIT_READY
    }

    public record RepairSlot(
            String slotId,
            String operation,
            SemanticId targetId,
            String targetFingerprint,
            Map<String, String> payload,
            String payloadFingerprint,
            RepairSlotStatus status,
            String dependencyGroupId,
            List<StructuredDiagnostic> diagnostics) {
        public RepairSlot {
            Objects.requireNonNull(slotId, "slotId");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(targetId, "targetId");
            Objects.requireNonNull(targetFingerprint, "targetFingerprint");
            payload = Map.copyOf(payload);
            Objects.requireNonNull(payloadFingerprint, "payloadFingerprint");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(dependencyGroupId, "dependencyGroupId");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record RepairWorkspaceSnapshot(
            String workspaceId,
            String workspaceDigest,
            RevisionRef baseRevision,
            String inspectionDigest,
            ChangeSetPrecondition precondition,
            List<RepairSlot> slots,
            List<DependencyGroup> groups,
            int repairRound) {
        public RepairWorkspaceSnapshot {
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(workspaceDigest, "workspaceDigest");
            Objects.requireNonNull(baseRevision, "baseRevision");
            Objects.requireNonNull(inspectionDigest, "inspectionDigest");
            Objects.requireNonNull(precondition, "precondition");
            slots = List.copyOf(slots);
            groups = List.copyOf(groups);
        }
    }

    public record SlotPatch(String slotId, Map<String, String> payload, boolean drop) {
        public SlotPatch {
            Objects.requireNonNull(slotId, "slotId");
            payload = Map.copyOf(payload);
        }

        public SlotPatch(String slotId, Map<String, String> payload) {
            this(slotId, payload, false);
        }

        public static SlotPatch drop(String slotId) {
            return new SlotPatch(slotId, Map.of(), true);
        }
    }

    public record RepairWorkspaceResult(
            boolean accepted,
            String source,
            String sourceDigest,
            RepairWorkspaceSnapshot workspace,
            Object change,
            List<StructuredDiagnostic> diagnostics) {
        public RepairWorkspaceResult {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(sourceDigest, "sourceDigest");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public enum ContextKind { MODULE, SYMBOL, NODE }

    /** Exact-revision read request; grants no edit or replacement choice. */
    public record ContextRequest(RevisionRef revision, ContextKind kind, SemanticId targetId,
                                 String targetFingerprint) {
        public ContextRequest {
            Objects.requireNonNull(revision, "revision");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(targetId, "targetId");
            Objects.requireNonNull(targetFingerprint, "targetFingerprint");
        }
    }

    public record ContextResult(boolean accepted, RevisionRef revision, SemanticSlice slice,
                                List<StructuredDiagnostic> diagnostics) {
        public ContextResult {
            Objects.requireNonNull(revision, "revision");
            diagnostics = List.copyOf(diagnostics);
            if (accepted != (slice != null)) throw new IllegalArgumentException("Context result has no accepted slice");
        }
    }

    public record StructuredDiagnostic(
            String code,
            String severity,
            String message,
            DiagnosticRange range,
            SemanticId ownerId,
            DiagnosticFacts facts,
            List<SemanticId> relatedIds,
            List<RepairScope> repairScopes,
            ContextRequest contextRequest,
            List<deal.diagnostics.DiagnosticNote> notes,
            Integer operationIndex,
            List<deal.diagnostics.CompilerDiagnostic.MissingSymbol> missingSymbols,
            deal.diagnostics.DiagnosticExcerpt excerpt,
            deal.diagnostics.DiagnosticOccurrence occurrence) {
        public StructuredDiagnostic(String code, String severity, String message, DiagnosticRange range,
                                    SemanticId ownerId, DiagnosticFacts facts, List<SemanticId> relatedIds,
                                    List<RepairScope> repairScopes, ContextRequest contextRequest,
                                    List<deal.diagnostics.DiagnosticNote> notes, Integer operationIndex,
                                    List<deal.diagnostics.CompilerDiagnostic.MissingSymbol> missingSymbols,
                                    deal.diagnostics.DiagnosticExcerpt excerpt) {
            this(code, severity, message, range, ownerId, facts, relatedIds, repairScopes, contextRequest,
                    notes, operationIndex, missingSymbols, excerpt, null);
        }

        public StructuredDiagnostic withOccurrence(deal.diagnostics.DiagnosticOccurrence value) {
            return new StructuredDiagnostic(code, severity, message, range, ownerId, facts,
                    relatedIds, repairScopes, contextRequest, notes, operationIndex, missingSymbols, excerpt, value);
        }

        public StructuredDiagnostic(String code, String severity, String message, DiagnosticRange range,
                                    SemanticId ownerId, DiagnosticFacts facts, List<SemanticId> relatedIds,
                                    List<RepairScope> repairScopes, ContextRequest contextRequest,
                                    List<deal.diagnostics.DiagnosticNote> notes, Integer operationIndex,
                                    List<deal.diagnostics.CompilerDiagnostic.MissingSymbol> missingSymbols) {
            this(code, severity, message, range, ownerId, facts, relatedIds, repairScopes, contextRequest,
                    notes, operationIndex, missingSymbols, null);
        }

        public StructuredDiagnostic withExcerpt(deal.diagnostics.DiagnosticExcerpt value) {
            return new StructuredDiagnostic(code, severity, message, range, ownerId, facts,
                    relatedIds, repairScopes, contextRequest, notes, operationIndex, missingSymbols, value, occurrence);
        }

        public StructuredDiagnostic(String code, String severity, String message, DiagnosticRange range,
                                    SemanticId ownerId, String expected, String actual,
                                    List<SemanticId> relatedIds, List<RepairScope> repairScopes, ContextRequest contextRequest,
                                    List<deal.diagnostics.DiagnosticNote> notes, Integer operationIndex) {
            this(code, severity, message, range, ownerId,
                    new DiagnosticFacts(code, "repair", expected, actual, List.of()), relatedIds, repairScopes,
                    contextRequest, notes, operationIndex, List.of());
        }
        public StructuredDiagnostic withMissingSymbols(List<deal.diagnostics.CompilerDiagnostic.MissingSymbol> facts) {
            return new StructuredDiagnostic(code, severity, message, range, ownerId, this.facts,
                    relatedIds, repairScopes, contextRequest, notes, operationIndex, facts, excerpt, occurrence);
        }
        public StructuredDiagnostic(String code, String severity, String message, DiagnosticRange range,
                                    SemanticId ownerId, String expected, String actual,
                                    List<SemanticId> relatedIds, List<RepairScope> repairScopes, ContextRequest contextRequest,
                                    List<deal.diagnostics.DiagnosticNote> notes) {
            this(code, severity, message, range, ownerId, expected, actual, relatedIds, repairScopes,
                    contextRequest, notes, (Integer) null);
        }

        public StructuredDiagnostic withContextRequest(ContextRequest request) {
            return new StructuredDiagnostic(code, severity, message, range, ownerId, facts,
                    relatedIds, repairScopes, request, notes, operationIndex, missingSymbols, excerpt, occurrence);
        }

        /** Explicit language/core protocol registrations only; no classification by code prefix. */
        public deal.diagnostics.DiagnosticClassification classification() {
            return RepairDiagnosticRegistry.coreClassification(code);
        }

        public deal.diagnostics.DiagnosticEditOffer editOffer() {
            return deal.diagnostics.DiagnosticEditOffer.forDiagnostic(facts, occurrence, range);
        }

        public String expected() { return facts.expected(); }
        public String actual() { return facts.actual(); }

        public StructuredDiagnostic withFacts(DiagnosticFacts value) {
            return new StructuredDiagnostic(code, severity, message, range, ownerId, value,
                    relatedIds, repairScopes, contextRequest, notes, operationIndex, missingSymbols, excerpt, occurrence);
        }

        /** Index in this response's ChangeSet only; never a persisted semantic identity. */
        public StructuredDiagnostic withOperationIndex(int index) {
            return new StructuredDiagnostic(code, severity, message, range, ownerId, facts,
                    relatedIds, repairScopes, contextRequest, notes, index, missingSymbols, excerpt, occurrence);
        }
        public StructuredDiagnostic(String code, String severity, String message, DiagnosticRange range,
                                    SemanticId ownerId, String expected, String actual,
                                    List<SemanticId> relatedIds, List<RepairScope> repairScopes, ContextRequest contextRequest) {
            this(code, severity, message, range, ownerId, expected, actual, relatedIds, repairScopes,
                    contextRequest, List.of(), null);
        }

        public StructuredDiagnostic {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(facts, "facts");
            relatedIds = List.copyOf(relatedIds);
            repairScopes = List.copyOf(repairScopes);
            notes = List.copyOf(notes);
            missingSymbols = List.copyOf(missingSymbols);
        }

    }

    public record FieldSnapshot(String name, String type, boolean optional, boolean array) {}

    public record TypeSnapshot(SemanticId id, String name, List<FieldSnapshot> fields) {
        public TypeSnapshot {
            fields = List.copyOf(fields);
        }
    }

    public record AppInterfaceSnapshot(
            String version,
            String fingerprint,
            String rootState,
            List<TypeSnapshot> types,
            List<TypeSnapshot> actions,
            List<String> capabilities) {
        public AppInterfaceSnapshot {
            types = List.copyOf(types);
            actions = List.copyOf(actions);
            capabilities = List.copyOf(capabilities);
        }
    }

    public record SymbolSnapshot(
            SemanticId id,
            String kind,
            String name,
            DiagnosticRange range,
            String description,
            String fingerprint,
            List<SemanticId> callers,
            List<SemanticId> callees) {
        public SymbolSnapshot {
            callers = List.copyOf(callers);
            callees = List.copyOf(callees);
        }
    }

    public record NodeSnapshot(
            SemanticId id,
            SemanticId ownerId,
            String kind,
            DiagnosticRange range,
            String fingerprint) {}

    public record SemanticSlice(
            RevisionRef revision,
            SemanticId ownerId,
            String kind,
            String source,
            List<SymbolSnapshot> symbols,
            List<NodeSnapshot> nodes,
            List<OperationDescriptor> allowedOperations) {
        public SemanticSlice {
            Objects.requireNonNull(revision, "revision");
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(source, "source");
            symbols = List.copyOf(symbols);
            nodes = List.copyOf(nodes);
            allowedOperations = List.copyOf(allowedOperations);
        }
    }

    public record ChangeSetPrecondition(
            String baseDigest,
            Map<String, String> expectedTargetFingerprints) {
        public ChangeSetPrecondition {
            Objects.requireNonNull(baseDigest, "baseDigest");
            expectedTargetFingerprints = Map.copyOf(expectedTargetFingerprints);
        }
    }

    public record Inspection(
                        String sourceDigest,
            SemanticId moduleId,
            List<SymbolSnapshot> symbols,
            List<NodeSnapshot> nodes,
            AppInterfaceSnapshot appInterface,
            List<String> allowedOperations,
            List<StructuredDiagnostic> diagnostics) {
        public Inspection {
            symbols = List.copyOf(symbols);
            nodes = List.copyOf(nodes);
            allowedOperations = List.copyOf(allowedOperations);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record ChangeResult(
            boolean accepted,
            String source,
            String sourceDigest,
            Inspection inspection,
            List<StructuredDiagnostic> diagnostics) {
        public ChangeResult {
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
