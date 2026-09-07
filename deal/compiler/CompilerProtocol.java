package deal.compiler;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Transport-neutral records shared by DEAL compiler frontends. */
public final class CompilerProtocol {
    public static final String VERSION = "compiler-protocol-v2";
    public static final String AGENT_SURFACE_VERSION = "agent-surface-v3";

    private CompilerProtocol() {}

    public record SourceRange(
            String file,
            int startLine,
            int startColumn,
            int endLine,
            int endColumn) {
        public SourceRange {
            Objects.requireNonNull(file, "file");
        }
    }

    public record SemanticId(String value) {
        public SemanticId {
            Objects.requireNonNull(value, "value");
            if (value.isBlank()) throw new IllegalArgumentException("Semantic id cannot be blank");
        }
    }

    public record ProtocolHandshake(
            String protocolVersion,
            String languageVersion,
            List<String> features) {
        public ProtocolHandshake {
            Objects.requireNonNull(protocolVersion, "protocolVersion");
            Objects.requireNonNull(languageVersion, "languageVersion");
            features = List.copyOf(features);
        }
    }

    public record RevisionRef(String protocolVersion, String sourceDigest) {
        public RevisionRef {
            Objects.requireNonNull(protocolVersion, "protocolVersion");
            Objects.requireNonNull(sourceDigest, "sourceDigest");
        }
    }

    public record OperationDescriptor(
            String operation,
            SemanticId targetId,
            String targetKind,
            String targetFingerprint,
            List<String> requiredFields) {
        public OperationDescriptor {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(targetId, "targetId");
            Objects.requireNonNull(targetKind, "targetKind");
            Objects.requireNonNull(targetFingerprint, "targetFingerprint");
            requiredFields = List.copyOf(requiredFields);
        }
    }

    public record RepairScope(String operation, SemanticId ownerId) {
        public RepairScope {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(ownerId, "ownerId");
        }
    }

    public record DependencyMember(
            SemanticId id,
            String kind,
            String exposure,
            String fingerprint) {
        public DependencyMember {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(exposure, "exposure");
            Objects.requireNonNull(fingerprint, "fingerprint");
        }
    }

    public record DependencyEdge(SemanticId from, SemanticId to, String kind) {
        public DependencyEdge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(kind, "kind");
        }
    }

    public record DependencyCone(
            String fingerprint,
            List<SemanticId> anchors,
            List<DependencyMember> members,
            List<DependencyEdge> edges) {
        public DependencyCone {
            Objects.requireNonNull(fingerprint, "fingerprint");
            anchors = List.copyOf(anchors);
            members = List.copyOf(members);
            edges = List.copyOf(edges);
        }
    }

    public record ChangeInspection(
            RevisionRef revision,
            String inspectionDigest,
            DependencyCone dependencyCone,
            List<SemanticSlice> editSlices,
            List<OperationDescriptor> allowedOperations,
            List<StructuredDiagnostic> diagnostics) {
        public ChangeInspection {
            Objects.requireNonNull(revision, "revision");
            Objects.requireNonNull(inspectionDigest, "inspectionDigest");
            Objects.requireNonNull(dependencyCone, "dependencyCone");
            editSlices = List.copyOf(editSlices);
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

    public record DependencyGroup(
            String groupId,
            List<String> slotIds,
            List<String> dependsOn,
            String status) {
        public DependencyGroup {
            Objects.requireNonNull(groupId, "groupId");
            slotIds = List.copyOf(slotIds);
            dependsOn = List.copyOf(dependsOn);
            Objects.requireNonNull(status, "status");
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

    public record DiagnosticContext(String version, String sourceDigest, int firstLine,
                                    String excerpt, boolean truncated) {}

    public record StructuredDiagnostic(
            String code,
            String severity,
            String message,
            SourceRange range,
            SemanticId ownerId,
            String expected,
            String actual,
            List<SemanticId> relatedIds,
            List<RepairScope> repairScopes,
            String contextQuery,
            DiagnosticContext context,
            List<deal.diagnostics.DiagnosticNote> notes,
            Integer operationIndex) {
        public StructuredDiagnostic(String code, String severity, String message, SourceRange range,
                                    SemanticId ownerId, String expected, String actual,
                                    List<SemanticId> relatedIds, List<RepairScope> repairScopes, String contextQuery,
                                    DiagnosticContext context, List<deal.diagnostics.DiagnosticNote> notes) {
            this(code, severity, message, range, ownerId, expected, actual, relatedIds, repairScopes,
                    contextQuery, context, notes, null);
        }

        /** Index in this response's ChangeSet only; never a persisted semantic identity. */
        public StructuredDiagnostic withOperationIndex(int index) {
            return new StructuredDiagnostic(code, severity, message, range, ownerId, expected, actual,
                    relatedIds, repairScopes, contextQuery, context, notes, index);
        }
        public StructuredDiagnostic(String code, String severity, String message, SourceRange range,
                                    SemanticId ownerId, String expected, String actual,
                                    List<SemanticId> relatedIds, List<RepairScope> repairScopes, String contextQuery) {
            this(code, severity, message, range, ownerId, expected, actual, relatedIds, repairScopes,
                    contextQuery, null, List.of());
        }

        public StructuredDiagnostic {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(message, "message");
            relatedIds = List.copyOf(relatedIds);
            repairScopes = List.copyOf(repairScopes);
            notes = List.copyOf(notes);
        }

        /** Candidate-owned evidence; coordinates remain in the original file, not the edited body. */
        public StructuredDiagnostic withSourceContext(String source) {
            if (range == null || range.startLine() < 1) return this;
            String[] lines = source.split("\\r\\n|\\r|\\n", -1);
            if (range.startLine() > lines.length) return this;
            int first = Math.max(1, range.startLine() - 1);
            int last = Math.min(lines.length, range.startLine() + 1);
            boolean truncated = range.endLine() > last;
            StringBuilder excerpt = new StringBuilder();
            for (int line = first; line <= last; line++) {
                if (line > first) excerpt.append('\n');
                String text = lines[line - 1];
                int scalars = text.codePointCount(0, text.length());
                if (scalars > 512) {
                    text = text.substring(0, text.offsetByCodePoints(0, 512));
                    truncated = true;
                }
                excerpt.append(text);
            }
            return new StructuredDiagnostic(code, severity, message, range, ownerId, expected, actual,
                    relatedIds, repairScopes, contextQuery,
                    new DiagnosticContext("diagnostic-context-v1", DealCompilerWorkspace.digest(source),
                            first, excerpt.toString(), truncated), notes, operationIndex);
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
            String rootSchemaFingerprint,
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
            SourceRange range,
            String signature,
            String fingerprint,
            List<SemanticId> callers,
            List<SemanticId> callees,
            List<SemanticId> references) {
        public SymbolSnapshot {
            callers = List.copyOf(callers);
            callees = List.copyOf(callees);
            references = List.copyOf(references);
        }
    }

    public record NodeSnapshot(
            SemanticId id,
            SemanticId ownerId,
            String kind,
            SourceRange range,
            String fingerprint) {}

    public record SemanticSlice(
            RevisionRef revision,
            SemanticId ownerId,
            String kind,
            String source,
            List<SymbolSnapshot> symbols,
            List<NodeSnapshot> nodes,
            List<SemanticId> dependencies,
            List<OperationDescriptor> allowedOperations) {
        public SemanticSlice {
            Objects.requireNonNull(revision, "revision");
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(source, "source");
            symbols = List.copyOf(symbols);
            nodes = List.copyOf(nodes);
            dependencies = List.copyOf(dependencies);
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

    public record RepairContract(
            SemanticId ownerId,
            SemanticSlice minimumContext,
            List<OperationDescriptor> allowedOperations,
            String rejectedCandidateFingerprint) {
        public RepairContract {
            Objects.requireNonNull(ownerId, "ownerId");
            allowedOperations = List.copyOf(allowedOperations);
            Objects.requireNonNull(rejectedCandidateFingerprint, "rejectedCandidateFingerprint");
        }
    }

    public record ImpactReport(
            List<SemanticId> changedSymbols,
            List<SemanticId> changedNodes,
            List<SemanticId> affectedCallers,
            List<SemanticId> affectedHandlers,
            boolean interfaceChanged,
            boolean rootSchemaChanged,
            boolean stateResetRequired,
            Map<String, String> fingerprints) {
        public ImpactReport {
            changedSymbols = List.copyOf(changedSymbols);
            changedNodes = List.copyOf(changedNodes);
            affectedCallers = List.copyOf(affectedCallers);
            affectedHandlers = List.copyOf(affectedHandlers);
            fingerprints = Map.copyOf(fingerprints);
        }
    }

    public record Inspection(
            String protocolVersion,
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
            ImpactReport impact,
            List<StructuredDiagnostic> diagnostics) {
        public ChangeResult {
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
