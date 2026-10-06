package deal.semantic.ir;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ClassFactoryRegistry(Map<ClassFactoryId, OpId> factories) {

    public ClassFactoryRegistry {
        Objects.requireNonNull(factories, "factories must not be null");
        Map<ClassFactoryId, OpId> copied = new LinkedHashMap<>();
        for (Map.Entry<ClassFactoryId, OpId> entry : factories.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "factories keys must not be null");
            Objects.requireNonNull(entry.getValue(), "factories values must not be null");
            copied.put(entry.getKey(), entry.getValue());
        }
        factories = Collections.unmodifiableMap(copied);
    }

    /**
     * The registered {@code CLASS_FACTORY} op id of the given
     * construction entry, or {@code null} when the id has no factory
     * (a non-exported class is never registered).
     *
     */
    public OpId factoryFor(ClassFactoryId constructionEntry) {
        return factories.get(Objects.requireNonNull(constructionEntry,
            "constructionEntry must not be null"));
    }

    /**
     * The owner's registered {@code CLASS_FACTORY} op of a
     * {@code CLASS_NEW(SHARED_FACTORY)} construction, resolved from the
     * delivered per-module registries of one executable closure: the
     * owner module is the unique module whose registry binds the
     * construction entry — the same binding the lowering's in-project
     * shared-factory facts carry ({@code SharedFactoryFacts.factoryOpId})
     * — and the bound op id carries that module.
     *
     * <p><b>The class descriptor namespace is never the owner.</b>
     * {@code ClassId.modulePath()} is the configured module-root text plus
     * the class file's relative directory components ({@code @src/Address}
     * for a class of the module {@code owner} under the root {@code src}),
     * not the module identity the closure's units and registries are keyed
     * by. Every {@code SHARED_FACTORY} execution consumer resolves its
     * owner through this method (the one delivered fact channel), never
     * from the class identity text.</p>
     *
     */
    public static OpId ownerFactoryOp(
            Map<ModuleId, ClassFactoryRegistry> registries,
            ClassFactoryId classFactoryRef) {
        Objects.requireNonNull(registries, "registries must not be null");
        if (classFactoryRef == null) {
            throw new IllegalStateException("a SHARED_FACTORY construction"
                + " carrying a null classFactoryRef is a producer defect");
        }
        ModuleId owner = null;
        OpId factoryOpId = null;
        for (Map.Entry<ModuleId, ClassFactoryRegistry> entry : registries.entrySet()) {
            OpId candidate = entry.getValue().factoryFor(classFactoryRef);
            if (candidate == null) {
                continue;
            }
            if (factoryOpId != null) {
                throw new IllegalStateException("constructionEntry " + classFactoryRef
                    + " is registered by modules " + owner + " and " + entry.getKey()
                    + ": one construction entry has exactly one owner (producer defect)");
            }
            owner = entry.getKey();
            factoryOpId = candidate;
        }
        if (factoryOpId != null && !factoryOpId.module().equals(owner)) {
            throw new IllegalStateException("constructionEntry " + classFactoryRef
                + " is registered under module " + owner + " but binds op "
                + factoryOpId + ": a module's registry binds a factory op of that"
                + " module (producer defect)");
        }
        return factoryOpId;
    }
}
