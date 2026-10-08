package deal.semantic;

import deal.semantic.ir.AdaptSourceRef;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.CaptureMode;
import deal.semantic.ir.ExternalExecutionOwner;
import deal.semantic.ir.FunctionAllocationIdentity;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticOpKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class FunctionBindingRegistry {

    /** The registrations in insertion order (registration order). */
    private final Map<FunctionAllocationIdentity, FunctionExecutionBinding> registrations =
        new LinkedHashMap<>();

    /** The materialization classifications in insertion order, keyed by identity. */
    private final Map<FunctionAllocationIdentity, FunctionValueMaterialization>
        materializations = new LinkedHashMap<>();

    /**
     * The closed materialization-source classification of a producing
     * site: a host-module export read ({@code HOST_EXPORT}), a
     * cross-module import read ({@code EXTERNAL_IMPORT}), or a
     * host-materialized value produced at a {@code HOST_TO_DEAL}
     * boundary crossing ({@code HOST_VALUE}).
     */
    public enum FunctionValueMaterializationSource {

        /** A host-module export materialized by a member/export read. */
        HOST_EXPORT,

        /** A cross-module import materialized by a member/export read. */
        EXTERNAL_IMPORT,

        /** A host-materialized function value produced at a host crossing. */
        HOST_VALUE
    }

    /**
     * The producer-fact classification of one host/external
     * function-value materialization site (the fact the shape-map
     * child's import-read arm consumes): the produced allocation
     * identity, the closed source kind, the owning module, the export
     * name (absent for {@code HOST_VALUE} — a host-materialized value
     * has no export name, its correlation id is the materializing
     * boundary op), the checked function descriptor, the producing op
     * kind ({@code MEMBER_READ}/{@code EXPORT_READ}/{@code BOUNDARY}),
     * and the materializing boundary op id ({@code HOST_VALUE} only —
     * the correlation id the conformance page's
     * {@code HostFunctionValueRef {materializingBoundaryOpId}}
     * resolves).
     */
    public record FunctionValueMaterialization(
        FunctionAllocationIdentity identity,
        FunctionValueMaterializationSource source,
        ModuleId moduleId,
        String exportName,
        RuntimeDescriptor.Func descriptor,
        SemanticOpKind producingOpKind,
        OpId materializingBoundaryOpId
    ) {

        public FunctionValueMaterialization {
            Objects.requireNonNull(identity, "identity must not be null");
            Objects.requireNonNull(source, "source must not be null");
            Objects.requireNonNull(moduleId, "moduleId must not be null");
            Objects.requireNonNull(descriptor, "descriptor must not be null");
            Objects.requireNonNull(producingOpKind, "producingOpKind must not be null");
            if (source == FunctionValueMaterializationSource.HOST_VALUE) {
                Objects.requireNonNull(materializingBoundaryOpId,
                    "a HOST_VALUE materialization carries its materializing boundary op id "
                        + "(the correlation id); it must not be null");
                if (exportName != null) {
                    throw new IllegalArgumentException(
                        "a HOST_VALUE materialization carries no export name; got '"
                            + exportName + "'");
                }
                if (producingOpKind != SemanticOpKind.BOUNDARY) {
                    throw new IllegalArgumentException(
                        "a HOST_VALUE materialization's producing op kind is BOUNDARY; got "
                            + producingOpKind);
                }
            } else {
                if (materializingBoundaryOpId != null) {
                    throw new IllegalArgumentException(
                        "only a HOST_VALUE materialization carries a materializing boundary "
                            + "op id; got " + materializingBoundaryOpId + " for " + source);
                }
                Objects.requireNonNull(exportName,
                    "an import materialization carries its export name; it must not be null");
                if (producingOpKind != SemanticOpKind.MEMBER_READ
                        && producingOpKind != SemanticOpKind.EXPORT_READ) {
                    throw new IllegalArgumentException(
                        "an import materialization's producing op kind is MEMBER_READ or "
                            + "EXPORT_READ; got " + producingOpKind);
                }
            }
        }
    }

    /**
     * The checker-fact record of an imported host/external function
     * value (B5's "registered where the import member read materializes
     * the value"): exactly one of the host module id (the import
     * resolves to a host declaration module) or the imported module id
     * (the import resolves to a cross-module implementation), the export
     * name, and the checked descriptor of the export. The seam accepts
     * these facts at the IR level and validates them against the pinned
     * payload record; the descriptor must be function-typed — the seam
     * is the function-value materialization seam, and a
     * function-typed materialization whose producer facts do not pin a
     * function descriptor fails explicitly (never silently skipped).
     */
    public record FunctionValueImportFacts(
        ModuleId hostModuleId,
        ModuleId importedModuleId,
        String exportName,
        RuntimeDescriptor descriptor
    ) {

        public FunctionValueImportFacts {
            if ((hostModuleId == null) == (importedModuleId == null)) {
                throw new IllegalArgumentException(
                    "exactly one of hostModuleId/importedModuleId must be non-null "
                        + "(a function-typed materialization without its producer facts is "
                        + "not silently skipped — the seam fails explicitly)");
            }
            Objects.requireNonNull(exportName, "exportName must not be null");
            Objects.requireNonNull(descriptor, "descriptor must not be null");
        }

        /** The facts' owning module id (host or imported, whichever is present). */
        public ModuleId moduleId() {
            return hostModuleId != null ? hostModuleId : importedModuleId;
        }
    }

    /**
     * Registers the {@code LoweredBody} binding of one
     * {@code CLOSURE_NEW} producing allocation (B5): the closure's
     * allocation identity is the {@code CLOSURE_NEW} result identity.
     *
     */
    public void registerClosure(FunctionAllocationIdentity identity, FunctionId functionId,
                                BlockId bodyBlock) {
        register(identity, new FunctionExecutionBinding.LoweredBody(
            Objects.requireNonNull(functionId, "functionId must not be null"),
            Objects.requireNonNull(bodyBlock, "bodyBlock must not be null")));
    }

    /**
     * Registers the {@code LoweredBody} binding of one
     * {@code RECURSIVE_GROUP_INIT} member (B4/B5): one
     * {@code LoweredBody} per member, keyed by the member's pre-assigned
     * allocation identity (the identity the group op's publication phase
     * writes into the member binding cell).
     *
     */
    public void registerGroupMember(FunctionAllocationIdentity identity, FunctionId functionId,
                                    BlockId bodyBlock) {
        register(identity, new FunctionExecutionBinding.LoweredBody(
            Objects.requireNonNull(functionId, "functionId must not be null"),
            Objects.requireNonNull(bodyBlock, "bodyBlock must not be null")));
    }

    /**
     * Registers the {@code AdapterBinding} of one {@code FUNCTION_ADAPT}
     * producing allocation (B5): the registration function is consumed
     * at the adapter-creation site — the shape-map child's production —
     * with the adapter op's identity, the closed capture mode, the
     * closed source reference, and the source/target signatures the
     * adapter was created for.
     *
     */
    public void registerAdapter(FunctionAllocationIdentity identity, OpId adaptOpId,
                                CaptureMode captureMode, AdaptSourceRef sourceRef,
                                RuntimeDescriptor.Func sourceSignature,
                                RuntimeDescriptor.Func targetSignature) {
        register(identity, new FunctionExecutionBinding.AdapterBinding(
            Objects.requireNonNull(adaptOpId, "adaptOpId must not be null"),
            Objects.requireNonNull(captureMode, "captureMode must not be null"),
            Objects.requireNonNull(sourceRef, "sourceRef must not be null"),
            Objects.requireNonNull(sourceSignature, "sourceSignature must not be null"),
            Objects.requireNonNull(targetSignature, "targetSignature must not be null")));
    }

    /**
     * The host/external import materialization seam (B5's "registered
     * where the import member read materializes the value"): accepts,
     * at the IR level, a member-read/export-read op that materializes a
     * function-typed host or external import value — its pinned payload
     * record ({@code KindPayload.MemberReadPayload {table, key}} /
     * {@code KindPayload.ExportReadPayload {module, name, descriptor,
     * value}}), its produced {@code FunctionAllocationIdentity}, and the
     * checker facts — and (a) classifies the producing site as a
     * host/external function-value materialization (the producer fact
     * the shape-map child's import-read arm consumes) and (b) registers
     * exactly one binding keyed by the produced allocation identity:
     * {@code HostFunction} for a host-module export, or
     * {@code ExternalFunction} for a cross-module import with the
     * {@code executionOwner} read from the already-built
     * {@link ModuleRoutePlan} record for the callee module (shared
     * callee → {@code SHARED_BODY}; retained-ABI callee →
     * {@code RETAINED_ABI}).
     *
     * <p>Fail closed: a payload record that is not a member-read/export-
     * read payload, facts without their producer module id, a
     * non-function-typed fact descriptor, a payload/facts mismatch (the
     * member-read key or the export-read module/name/descriptor
     * disagreeing with the facts), or a cross-module import whose callee
     * has no route record in the supplied plan raises an explicit
     * producer-defect failure — the seam never silently skips a
     * registration.</p>
     *
     */
    public FunctionValueMaterialization registerHostOrExternalImport(
            FunctionAllocationIdentity identity, KindPayload producerPayload,
            FunctionValueImportFacts facts, ModuleRoutePlan plan) {
        return registerHostOrExternalImportWithRoutes(identity, producerPayload, facts,
            plan == null ? Map.of() : plan.entries());
    }

    /**
     * The route-map variant of the host/external import materialization
     * seam (the E7 lowering surface): identical classification and
     * registration with the {@code executionOwner} resolved from the
     * supplied callee-route map — one route record per callee module
     * (shared callee → {@code SHARED_BODY}; retained-ABI callee →
     * {@code RETAINED_ABI}).
     *
     */
    public FunctionValueMaterialization registerHostOrExternalImportWithRoutes(
            FunctionAllocationIdentity identity, KindPayload producerPayload,
            FunctionValueImportFacts facts, Map<ModuleId, ModuleRoute> calleeRoutes) {
        Objects.requireNonNull(calleeRoutes, "calleeRoutes must not be null");
        registerHostOrExternalImportCore(identity, producerPayload, facts, calleeRoutes);
        return materializations.get(Objects.requireNonNull(identity,
            "identity must not be null"));
    }

    /** The shared registration core over the callee-route facts. */
    private void registerHostOrExternalImportCore(
            FunctionAllocationIdentity identity, KindPayload producerPayload,
            FunctionValueImportFacts facts, Map<ModuleId, ModuleRoute> calleeRoutes) {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(producerPayload, "producerPayload must not be null");
        Objects.requireNonNull(facts, "facts must not be null — a function-typed "
            + "materialization without its producer facts is a producer defect and the "
            + "seam fails explicitly instead of silently skipping the registration");
        if (!(facts.descriptor() instanceof RuntimeDescriptor.Func funcDescriptor)) {
            throw new IllegalStateException("the seam registers function-value "
                + "materializations only: the producer facts name a non-function "
                + "descriptor " + facts.descriptor().canonicalSpecText() + " for export '"
                + facts.exportName() + "' — a function-typed materialization whose "
                + "producer facts do not pin a function descriptor fails explicitly "
                + "(never silently skipped)");
        }
        SemanticOpKind producingKind;
        switch (producerPayload) {
            case KindPayload.MemberReadPayload member -> {
                producingKind = SemanticOpKind.MEMBER_READ;
                if (!member.key().equals(facts.exportName())) {
                    throw new IllegalStateException("the member-read producer record names "
                        + "key '" + member.key() + "' but the checker facts name export '"
                        + facts.exportName() + "' — mismatched producer facts "
                        + "(producer defect)");
                }
            }
            case KindPayload.ExportReadPayload export -> {
                producingKind = SemanticOpKind.EXPORT_READ;
                if (!export.module().equals(facts.moduleId())) {
                    throw new IllegalStateException("the export-read producer record names "
                        + "module '" + export.module() + "' but the checker facts name "
                        + "module '" + facts.moduleId() + "' — mismatched producer facts "
                        + "(producer defect)");
                }
                if (!export.name().equals(facts.exportName())) {
                    throw new IllegalStateException("the export-read producer record names "
                        + "export '" + export.name() + "' but the checker facts name "
                        + "export '" + facts.exportName() + "' — mismatched producer facts "
                        + "(producer defect)");
                }
                if (!export.descriptor().equals(facts.descriptor())) {
                    throw new IllegalStateException("the export-read producer record "
                        + "carries descriptor " + export.descriptor().canonicalSpecText()
                        + " but the checker facts name "
                        + facts.descriptor().canonicalSpecText() + " — mismatched producer "
                        + "facts (producer defect)");
                }
            }
            default -> throw new IllegalArgumentException("the seam accepts member-read/"
                + "export-read producer records only; got "
                + producerPayload.getClass().getSimpleName() + " (this child produces no "
                + "member-read/export-read op path and no other materialization path)");
        }
        FunctionValueMaterialization classification;
        if (facts.hostModuleId() != null) {
            register(identity, new FunctionExecutionBinding.HostFunction(
                facts.hostModuleId(), facts.exportName(), funcDescriptor));
            classification = new FunctionValueMaterialization(identity,
                FunctionValueMaterializationSource.HOST_EXPORT, facts.hostModuleId(),
                facts.exportName(), funcDescriptor, producingKind, null);
        } else {
            ModuleRoute calleeRoute = calleeRoutes.get(facts.importedModuleId());
            if (calleeRoute == null) {
                throw new IllegalStateException("the cross-module import of '"
                    + facts.importedModuleId() + "'." + facts.exportName()
                    + " has no route record in the supplied ModuleRoutePlan — route "
                    + "resolution is the modules epic's and the seam consumes the resolved "
                    + "plan record; a missing callee entry is a producer defect");
            }
            ExternalExecutionOwner executionOwner = switch (calleeRoute) {
                case SHARED -> ExternalExecutionOwner.SHARED_BODY;
                case LEGACY -> ExternalExecutionOwner.RETAINED_ABI;
            };
            register(identity, new FunctionExecutionBinding.ExternalFunction(
                facts.importedModuleId(), facts.exportName(), funcDescriptor,
                executionOwner));
            classification = new FunctionValueMaterialization(identity,
                FunctionValueMaterializationSource.EXTERNAL_IMPORT, facts.importedModuleId(),
                facts.exportName(), funcDescriptor, producingKind, null);
        }
        materializations.put(identity, classification);
    }

    /**
     * The {@code HostFunctionValue} registration seam (B5's obligation
     * at the producing host crossing; the crossing's execution is E7's
     * and the boundary op's production is E4's): accepts, at the IR
     * level, the facts of a {@code HOST_TO_DEAL} boundary op whose
     * checked descriptor is a function type — the pinned
     * {@code KindPayload.BoundaryPayload} record (constructed as the
     * pinned schema shape, not produced or executed by this child), the
     * boundary op's {@code OpId}, the produced allocation identity, the
     * host module id, and the function descriptor — and registers
     * {@code HostFunctionValue {hostModuleId,
     * materializingBoundaryOpId, descriptor}} keyed by the produced
     * allocation identity. {@code materializingBoundaryOpId} is the
     * correlation id the conformance page's
     * {@code HostFunctionValueRef {materializingBoundaryOpId}}
     * resolves.
     *
     * <p>Fail closed: a boundary payload whose kind is not
     * {@code HOST_TO_DEAL}, whose descriptor is not function-typed, or
     * whose input value is not the produced allocation identity raises
     * an explicit producer-defect failure — the seam never silently
     * skips a registration.</p>
     *
     */
    public FunctionValueMaterialization registerHostFunctionValue(
            FunctionAllocationIdentity identity, KindPayload.BoundaryPayload boundaryPayload,
            OpId materializingBoundaryOpId, ModuleId hostModuleId) {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(boundaryPayload, "boundaryPayload must not be null");
        Objects.requireNonNull(materializingBoundaryOpId,
            "materializingBoundaryOpId must not be null");
        Objects.requireNonNull(hostModuleId, "hostModuleId must not be null");
        if (boundaryPayload.kind() != BoundaryKind.HOST_TO_DEAL) {
            throw new IllegalStateException("the HostFunctionValue seam accepts a "
                + "HOST_TO_DEAL boundary fact record only; got kind "
                + boundaryPayload.kind() + " — a host-materialized function value "
                + "registers at its producing host crossing and nowhere else "
                + "(producer defect)");
        }
        if (!(boundaryPayload.descriptor() instanceof RuntimeDescriptor.Func funcDescriptor)) {
            throw new IllegalStateException("the HostFunctionValue seam registers "
                + "function-typed host crossings only: the boundary fact record checks a "
                + "non-function descriptor " + boundaryPayload.descriptor().canonicalSpecText()
                + " — a function-typed materialization whose producer facts do not pin a "
                + "function descriptor fails explicitly (never silently skipped)");
        }
        if (boundaryPayload.input().id() != identity.id()) {
            throw new IllegalStateException("the HOST_TO_DEAL boundary fact record's input "
                + "value " + boundaryPayload.input() + " is not the produced allocation "
                + identity + " — the producing allocation identity is keyed by the crossing "
                + "value's id (producer defect)");
        }
        register(identity, new FunctionExecutionBinding.HostFunctionValue(hostModuleId,
            materializingBoundaryOpId, funcDescriptor));
        FunctionValueMaterialization classification = new FunctionValueMaterialization(
            identity, FunctionValueMaterializationSource.HOST_VALUE, hostModuleId, null,
            funcDescriptor, SemanticOpKind.BOUNDARY, materializingBoundaryOpId);
        materializations.put(identity, classification);
        return classification;
    }

    /**
     * Registers the {@code IntrinsicFunction} binding of one producer-less
     * seeded intrinsic ({@code int}/{@code number}/{@code bytes}): the
     * closed intrinsic kind and the intrinsic's declared signature, keyed
     * by the seeded function-value identity the intrinsic binding's single
     * {@code BINDING_INIT} carries as its init operand. No closed op
     * produces that identity: the seed's {@code BINDING_INIT} is the
     * key's producing position, admitted by the bindings production
     * validator's closed clause set ({@code REGISTRY_ONE_TO_ONE}); a
     * second registration for the same identity stays rejected.
     *
     */
    public void registerIntrinsic(FunctionAllocationIdentity identity, IntrinsicKind kind,
                                  RuntimeDescriptor.Func descriptor) {
        register(Objects.requireNonNull(identity, "identity must not be null"),
            new FunctionExecutionBinding.IntrinsicFunction(
                Objects.requireNonNull(kind, "kind must not be null"),
                Objects.requireNonNull(descriptor, "descriptor must not be null")));
    }

    /**
     * Registers the {@code DynamicFunctionValue} binding of one
     * function-typed materialization whose execution class is resolved
     * from the materialized carrier at execution: the producing op's
     * identity (the correlation id the schema validator's
     * R-FUNCTION-BINDING clause resolves to the named op, its result
     * identity, and its result descriptor) and the value's checked
     * function descriptor, keyed by the producing op's result allocation
     * identity. The record names no execution class (none is statically
     * known); the class is the runtime value's own producing registration
     * at execution. A second registration for the same identity stays
     * rejected at registration time.
     *
     */
    public void registerDynamicFunctionValue(FunctionAllocationIdentity identity,
                                             OpId materializingOpId,
                                             RuntimeDescriptor.Func descriptor) {
        register(Objects.requireNonNull(identity, "identity must not be null"),
            new FunctionExecutionBinding.DynamicFunctionValue(
                Objects.requireNonNull(materializingOpId,
                    "materializingOpId must not be null"),
                Objects.requireNonNull(descriptor, "descriptor must not be null")));
    }

    /**
     * The single registration core: exactly one
     * {@code FunctionExecutionBinding} per producing allocation. A
     * duplicate-key registration is rejected at registration time —
     * fail closed, never overwritten.
     */
    private void register(FunctionAllocationIdentity identity,
                          FunctionExecutionBinding binding) {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(binding, "binding must not be null");
        FunctionExecutionBinding previous = registrations.putIfAbsent(identity, binding);
        if (previous != null) {
            throw new IllegalStateException("duplicate function-binding registration for "
                + identity + " (producer defect; the closed registry holds exactly one "
                + "FunctionExecutionBinding per producing allocation)");
        }
    }

    /**
     * The immutable registration map in insertion order (registration
     * order — the unit's production order). The snapshot is
     * unmodifiable: the map is immutable after lowering, and repeated
     * lowering of the same checked module yields the same map with
     * identical iteration order.
     *
     */
    public Map<FunctionAllocationIdentity, FunctionExecutionBinding> bindings() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(registrations));
    }

    /**
     * The recorded materialization classifications in registration
     * order (the producer facts the shape-map child's import-read arm
     * consumes).
     *
     */
    public List<FunctionValueMaterialization> materializations() {
        return new ArrayList<>(materializations.values());
    }

    /**
     * The recorded materialization classification of one producing
     * allocation, if the seam registered one — the lookup the
     * shape-map child resolves a source expression's producing identity
     * through (identity preservation across loads, reads, argument
     * passing, and returns).
     *
     */
    public Optional<FunctionValueMaterialization> materializationOf(
            FunctionAllocationIdentity identity) {
        return Optional.ofNullable(materializations.get(
            Objects.requireNonNull(identity, "identity must not be null")));
    }

    /** The number of registered bindings. */
    public int size() {
        return registrations.size();
    }

    /** True iff the identity has exactly one registered binding. */
    public boolean containsKey(FunctionAllocationIdentity identity) {
        return registrations.containsKey(identity);
    }
}
