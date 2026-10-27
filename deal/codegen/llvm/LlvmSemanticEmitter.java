package deal.codegen.llvm;

import deal.codegen.SharedEmissionFailure;
import deal.semantic.ir.ModuleId;

/**
 * The public production emitter of the native backend
 * ({@code llvm-native-codegen-cfg-ssa} C1/C9, {@code EmitNativeProject}).
 *
 * <p>{@link #emitProductionProject(LlvmEmissionInput)} consumes exactly the
 * validated lowered project and its admitted companions, walks the project in
 * dependency order and returns the one deterministic textual LLVM module
 * ({@code llvm-native-backend-integration} D2). It stages nothing — the
 * caller's {@code PublicationStager} owns placement — and it executes no
 * external tool (D3).</p>
 *
 * <p>An operation, payload, selector, boundary or export the staged native
 * skeleton cannot render raises {@link SharedEmissionFailure} carrying that
 * fact's own copied origin, which the production arm turns into exactly one
 * E6005 {@code SHARED_EMITTER_COVERAGE}; nothing is published and no fallback
 * route exists (C9).</p>
 */
public final class LlvmSemanticEmitter {

    private LlvmSemanticEmitter() {
        // Static entry only; no instances.
    }

    /**
     * Renders the project's one textual LLVM module.
     *
     * @param input the canonical rendering input; non-null
     * @return the module's publication-relative path and exact text
     * @throws SharedEmissionFailure on a fact outside the staged skeleton
     */
    public static LlvmProjectEmission emitProductionProject(
            LlvmEmissionInput input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        LlvmSession session = new LlvmSession(input);
        LlvmModule module = new LlvmModule(session);
        ModuleId entry = input.project().entryModule();
        String artifactRelativePath = entry.path().replace('.', '/') + ".ll";
        return new LlvmProjectEmission(artifactRelativePath, module.render());
    }

    /**
     * The artifact path of one project's module
     * ({@code <entry module path>.ll}, D2): the single path the production
     * arm stages.
     */
    public static String artifactRelativePath(ModuleId entryModule) {
        return entryModule.path().replace('.', '/') + ".ll";
    }
}
