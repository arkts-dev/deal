package deal.semantic;

import deal.identity.CanonicalClassIdentity;
import deal.identity.CanonicalModuleIdentity;

import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticRange;
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
     * closed, never an invented descriptor), and so does a builtin class
     * other than the intrinsic {@code Error} (the builtin module declares
     * only {@code Error}; that class's canonical text is exactly
     * {@code Error}).
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
            case Type.Error ignored -> throw new Defect(
                "Type.Error reached a descriptor-production position: the internal checker "
                    + "sentinel has no RuntimeDescriptor member in deal.semantic-ir/2 and is "
                    + "excluded from common units");
            case Type.Class cls -> describeClass(cls);
            case Type.Array array -> new RuntimeDescriptor.Array(describe(array.element()));
            case Type.Nullable nullable ->
                new RuntimeDescriptor.Nullable(describe(nullable.inner()));
            case Type.Func func -> new RuntimeDescriptor.Func(
                func.paramTypes().stream().map(DescriptorService::describe).toList(),
                describe(func.returnType()), func.isAsync());
        };
    }

    /**
     * The class arm: the carried identity's canonical module path plus the
     * class name, projecting to the builtin {@code Error} atom for the
     * intrinsic builtin identity. A builtin class other than
     * {@code Error} is a malformed internal fact: it raises the named
     * {@link Defect} (converted by the unit-production seam into E6005),
     * never an invented descriptor.
     */
    private static RuntimeDescriptor.Class describeClass(Type.Class cls) {
        if (cls.identity().moduleIdentity()
                    instanceof CanonicalModuleIdentity.BuiltinModule
                && !"Error".equals(cls.name())) {
            throw new Defect(
                "builtin class '" + cls.name() + "' has no canonical descriptor: "
                    + "the builtin module declares only the intrinsic 'Error' class "
                    + "(internal invariant violation)");
        }
        return new RuntimeDescriptor.Class(
            new ClassId(semanticModulePath(cls.identity()), cls.name()));
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
        return e6005(module, defect, null);
    }

    /**
     * The same conversion seam for a failure whose producer identified the
     * offending declaration or field (ISSUE-0750 D3): the detail, rule,
     * and message are exactly the two-argument surface's; only the carried
     * range differs. A {@code null} range keeps the canonical empty-file
     * synthetic projection.
     *
     */
    public static CompilerDiagnostic e6005(ModuleId module, Defect defect,
                                           DiagnosticRange offendingRange) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(defect, "defect must not be null");
        LoweringFailureDetail detail = new LoweringFailureDetail(
            module.path(),
            SemanticCapability.DESCRIPTORS,
            DESCRIPTOR_UNREPRESENTABLE,
            SemanticProfile.DEAL_V1_3_INT32,
            LoweredModuleUnit.FORMAT_VERSION,
            "DescriptorService " + DESCRIPTOR_UNREPRESENTABLE
                + " (" + defect.getMessage() + ")");
        return FailureContractRegistry.e6005(detail, offendingRange);
    }
}
