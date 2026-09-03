package deal.ffi;

import java.util.Map;
import java.util.Objects;

/**
 * The complete validated-and-generated extern-C module inputs of one
 * compilation (ISSUE-0162 boundary): the immutable
 * {@link FfiModuleDescriptor}, the generated {@link FfiCdefBundle}, the
 * retained per-class plan records, and the forward bindings — the exact
 * loader inputs the later emission child serializes into the
 * {@code __rt.load_ffi(moduleKey, cdefBundle, plans, bindings, ...)}
 * call site.
 *
 * <p>Nothing in this record invokes {@code ffi.cdef}, opens a library,
 * resolves a symbol, or performs an ABI call; no evaluator is invoked
 * during or after generation.</p>
 */
public record FfiGeneratedModule(
    String modulePath,
    FfiModuleDescriptor descriptor,
    FfiCdefBundle cdefBundle,
    Map<String, FfiCompilerClassDefaultPlan> plans,
    FfiForwardBindings bindings) {

    public FfiGeneratedModule {
        Objects.requireNonNull(modulePath, "modulePath");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(cdefBundle, "cdefBundle");
        Objects.requireNonNull(bindings, "bindings");
        plans = Map.copyOf(Objects.requireNonNull(plans, "plans"));
        if (!descriptor.moduleKey().equals(bindings.moduleKey())) {
            throw new IllegalArgumentException(
                "descriptor and bindings module keys must match");
        }
    }
}
