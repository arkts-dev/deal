package deal.ffi;

import java.util.Map;
import java.util.Objects;

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
