package deal.semantic;

import deal.identity.CanonicalClassIdentity;
import deal.identity.CanonicalModuleIdentity;

import deal.diagnostics.CompilerDiagnostic;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.LoweringFailureDetail;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticProfile;
import deal.types.Type;

import java.util.Objects;

public final class DescriptorService {

    /**
     * The fact-defect identifier carried in the {@code validatorRule}
     * field of the E6005 diagnostic for an unrepresentable type
     * ({@link Type.Error}).
     */
    public static final String DESCRIPTOR_UNREPRESENTABLE = "DESCRIPTOR_UNREPRESENTABLE";

    private DescriptorService() {
        // Static surface only; no instances and no state.
    }

    /**
     * A descriptor-production fact defect: {@link Type.Error} reached a
     * descriptor-production position. The unit-production seam owns the
     * conversion into E6005 ({@code DESCRIPTOR_UNREPRESENTABLE}); this
     * exception is internal control flow, never a crash and never a
     * rendered fallback.
     */
    public static final class Defect extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public Defect(String message) {
            super(message);
        }
    }

    /**
     * Produces the structural runtime descriptor of a checked
     * {@link Type} — the only {@code Type}→{@link RuntimeDescriptor}
     * producer for common units. Total and deterministic over the eleven
     * supported variants; {@link Type.Error} raises {@link Defect} (fail
     * closed, never an invented descriptor).
     *
     */
    public static RuntimeDescriptor describe(Type type) {
        Objects.requireNonNull(type, "type must not be null");
        return switch (type) {
            case Type.Null ignored -> RuntimeDescriptor.Null.INSTANCE;
            case Type.Boolean ignored -> RuntimeDescriptor.Boolean.INSTANCE;
            case Type.Int ignored -> RuntimeDescriptor.Int.INSTANCE;
            case Type.Number ignored -> RuntimeDescriptor.Number.INSTANCE;
            case Type.String ignored -> RuntimeDescriptor.String.INSTANCE;
            case Type.Table ignored -> RuntimeDescriptor.Table.INSTANCE;
            case Type.Bytes ignored -> RuntimeDescriptor.Bytes.INSTANCE;
            case Type.Error ignored -> throw new Defect(
                "Type.Error reached a descriptor-production position: the internal checker "
                    + "sentinel has no RuntimeDescriptor member in deal.semantic-ir/1 and is "
                    + "excluded from common units");
            case Type.Class cls -> new RuntimeDescriptor.Class(
                new ClassId(semanticModulePath(cls.identity()),
                    cls.name()));
            case Type.Array array -> new RuntimeDescriptor.Array(describe(array.element()));
            case Type.Nullable nullable ->
                new RuntimeDescriptor.Nullable(describe(nullable.inner()));
            case Type.Func func -> new RuntimeDescriptor.Func(
                func.paramTypes().stream().map(DescriptorService::describe).toList(),
                describe(func.returnType()), func.isAsync());
        };
    }

    public static String semanticModulePath(CanonicalClassIdentity identity) {
        return switch (identity.moduleIdentity()) {
            case CanonicalModuleIdentity.BuiltinModule ignored -> "";
            case CanonicalModuleIdentity.ExternalModule external ->
                "$external/" + external.rawImportSpecifier();
            case CanonicalModuleIdentity.ProjectModule project -> {
                StringBuilder sb = new StringBuilder(
                    project.projectIdentity().configuredRootText());
                for (String component
                        : project.projectIdentity().relativeModuleComponents()) {
                    sb.append('/').append(component);
                }
                yield sb.toString();
            }
        };
    }

    /**
     * The pinned conversion seam (D1): converts a {@link Defect} raised
     * by {@link #describe(Type)} into the canonical E6005 diagnostic
     * through the failure contract registry — the only sanctioned E6005
     * construction surface. No {@code RuntimeDescriptor} is constructed
     * on this path.
     *
     */
    public static CompilerDiagnostic e6005(ModuleId module, Defect defect) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(defect, "defect must not be null");
        LoweringFailureDetail detail = new LoweringFailureDetail(
            module.path(),
            SemanticCapability.DESCRIPTORS,
            DESCRIPTOR_UNREPRESENTABLE,
            SemanticProfile.DEAL_V1_2_INT32,
            LoweredModuleUnit.FORMAT_VERSION,
            "DescriptorService " + DESCRIPTOR_UNREPRESENTABLE
                + " (" + defect.getMessage() + ")");
        return FailureContractRegistry.e6005(detail);
    }
}
