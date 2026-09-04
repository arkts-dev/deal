package deal.compiler;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Transport-neutral records shared by DEAL compiler frontends. */
public final class CompilerProtocol {
    public static final String VERSION = "compiler-protocol-v2";
    public static final String AGENT_SURFACE_VERSION = "agent-surface-v2";

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
            String contextQuery) {
        public StructuredDiagnostic {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(message, "message");
            relatedIds = List.copyOf(relatedIds);
            repairScopes = List.copyOf(repairScopes);
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
