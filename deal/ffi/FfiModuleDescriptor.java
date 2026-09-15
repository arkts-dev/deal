package deal.ffi;

import deal.module.SemanticModuleIdentity;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The single immutable source-ordered C FFI module descriptor published
 * by {@link FfiDeclarationValidator} after semantic/graph success
 * (design source {@code deal-v1.2-directives-and-c-ffi-declarations}
 * D4):
 *
 * <pre>
 * FfiModuleDescriptor(moduleKey, semanticModuleIdentity,
 *   canonicalExternalModuleIdentity, nativeLibrary,
 *   classes: ordered List&lt;FfiClassDescriptor&gt;,
 *   functions: ordered List&lt;FfiFunctionDescriptor&gt;,
 *   runtimeDefaultPlans, canonicalPlanContent, planDigest)
 * </pre>
 *
 * <ul>
 *   <li>{@code moduleKey} — the pinned {@code "ffi:" +
 *       canonicalExternalModuleIdentity} runtime key; opaque to the
 *       runtime and never parsed.</li>
 *   <li>{@code semanticModuleIdentity} — the declaring module's private
 *       semantic identity (deployment identity + canonical source URI);
 *       compiler-internal only.</li>
 *   <li>{@code canonicalExternalModuleIdentity} — the externals
 *       descriptor namespace text {@code @$external/&lt;rawImportSpecifier&gt;},
 *       the module-level identity whose class atoms share the prefix.</li>
 *   <li>{@code nativeLibraryKind}/{@code nativeLibraryLoaderText} — the
 *       manifest-classified library reference (D5), or {@code null} when
 *       the external entry carries none (a later-runtime
 *       {@code FFI_LIBRARY_LOAD} case; never evaluated here).</li>
 *   <li>{@code classes}/{@code functions} — the validated rows in source
 *       declaration order, frozen.</li>
 *   <li>{@code runtimeDefaultPlans} — the immutable plans keyed by class
 *       identity text (C_STRUCT classes only).</li>
 *   <li>{@code canonicalPlanContent} — the complete canonical
 *       serialization of the descriptor (functions, classes, plans,
 *       native library) produced by {@link FfiContentSerializer};
 *       identity equality compares this full content.</li>
 *   <li>{@code planDigest} — SHA-256 over {@code canonicalPlanContent};
 *       an index only, never consulted for equality.</li>
 * </ul>
 *
 * <p>Immutable and deterministic for identical inputs; any
 * behavior-bearing content change (a signature, a field, a default, a
 * provider, the library reference, the module identity) changes the
 * canonical content and therefore the identity.</p>
 */
public record FfiModuleDescriptor(
    String moduleKey,
    SemanticModuleIdentity semanticModuleIdentity,
    String canonicalExternalModuleIdentity,
    String nativeLibraryKind,
    String nativeLibraryLoaderText,
    List<FfiClassDescriptor> classes,
    List<FfiFunctionDescriptor> functions,
    Map<String, FfiCompilerClassDefaultPlan> runtimeDefaultPlans,
    String canonicalPlanContent,
    String planDigest) {

    public FfiModuleDescriptor {
        Objects.requireNonNull(moduleKey, "moduleKey");
        Objects.requireNonNull(semanticModuleIdentity, "semanticModuleIdentity");
        Objects.requireNonNull(canonicalExternalModuleIdentity,
            "canonicalExternalModuleIdentity");
        Objects.requireNonNull(canonicalPlanContent, "canonicalPlanContent");
        Objects.requireNonNull(planDigest, "planDigest");
        classes = List.copyOf(Objects.requireNonNull(classes, "classes"));
        functions = List.copyOf(Objects.requireNonNull(functions, "functions"));
        runtimeDefaultPlans = Map.copyOf(Objects.requireNonNull(
            runtimeDefaultPlans, "runtimeDefaultPlans"));
        if ((nativeLibraryKind == null) != (nativeLibraryLoaderText == null)) {
            throw new IllegalArgumentException(
                "nativeLibraryKind and nativeLibraryLoaderText must both be"
                    + " present or both absent");
        }
    }

    /**
     * The pinned module-key projection: {@code "ffi:"} + the canonical
     * external module identity text (the runtime treats the key as
     * opaque text).
     */
    public static String moduleKeyOf(String canonicalExternalModuleIdentity) {
        Objects.requireNonNull(canonicalExternalModuleIdentity,
            "canonicalExternalModuleIdentity");
        return "ffi:" + canonicalExternalModuleIdentity;
    }
}
