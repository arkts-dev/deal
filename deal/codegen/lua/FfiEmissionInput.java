package deal.codegen.lua;

import deal.ffi.FfiGeneratedModule;
import deal.semantic.ir.ModuleId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record FfiEmissionInput(
        Map<ModuleId, FfiGeneratedModule> generatedModules,
        String manifestDirectory) {

    public FfiEmissionInput {
        Objects.requireNonNull(generatedModules,
            "generatedModules must not be null");
        Map<ModuleId, FfiGeneratedModule> frozen = new LinkedHashMap<>();
        for (Map.Entry<ModuleId, FfiGeneratedModule> entry
                : generatedModules.entrySet()) {
            frozen.put(Objects.requireNonNull(entry.getKey(),
                    "a generated-module key must not be null"),
                Objects.requireNonNull(entry.getValue(),
                    "a generated module must not be null"));
        }
        generatedModules = Collections.unmodifiableMap(frozen);
        Objects.requireNonNull(manifestDirectory,
            "manifestDirectory must not be null");
    }

    /**
     * The generated metadata of one extern-C declaration module. An
     * absent entry is the fail-closed presence rule of the emission
     * input (a session carrying the input never silently omits an
     * extern-C import's load), so it throws instead of returning null:
     * the production arm maps the throw to E6005
     * {@code SHARED_EMITTER_COVERAGE} at the import origin and stages
     * nothing.
     *
     */
    public FfiGeneratedModule require(ModuleId moduleId) {
        Objects.requireNonNull(moduleId, "moduleId must not be null");
        FfiGeneratedModule found = generatedModules.get(moduleId);
        if (found == null) {
            throw new IllegalStateException(
                "the extern-C declaration module '" + moduleId.path()
                    + "' carries no generated-module entry in the FFI"
                    + " emission input: the input covers exactly the"
                    + " compile's validated extern-C metadata (a producer"
                    + " defect, never a silently omitted load)");
        }
        return found;
    }
}
