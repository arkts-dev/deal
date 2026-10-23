package deal.codegen.js;

import deal.codegen.SharedEmissionFailure;
import deal.identity.CanonicalClassIdentity;
import deal.identity.CanonicalModuleIdentity;
import deal.semantic.ClassRegistrationSeeds;
import deal.semantic.DescriptorService;
import deal.semantic.HostDeclarationSurface;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ClassLayout;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.RuntimeDescriptor;
import deal.types.Type;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The one production projection of the renderer's structural declaration
 * surface (design source {@code wiki:javascript-semantic-ir-emission},
 * "declaration_projection"): the shared declaration facts
 * ({@link HostDeclarationSurface}) plus the class registration seeds
 * ({@link ClassRegistrationSeeds}) become one
 * {@link RuntimeDeclarationSurface} carrying structural identities,
 * descriptors, closed enums, names, ordering and flags only.
 *
 * <p>The projection is total over the shared facts and fails closed on a
 * fact the shared producers already reject: a declaration module with
 * classes but no resolvable canonical module identity, or a declared class
 * without its registration seed. Both are producer defects; the failure
 * names the offending module and class so the production owner can project
 * it. The projection is invoked exactly once per production emission over
 * the complete project, before the renderer runs.</p>
 */
public final class RuntimeDeclarationProjection {

    private RuntimeDeclarationProjection() {
        // Static surface only; no instances and no state.
    }

    /**
     * Projects the complete structural declaration surface.
     *
     * @param surface    the compilation's declaration facts; non-null
     * @param identities the canonical module identity of every declaration
     *                   module of the surface; non-null
     * @param seeds      the class registration seeds of the same project;
     *                   non-null
     * @return the immutable structural declaration surface, declaration
     *         module order preserved
     */
    public static RuntimeDeclarationSurface project(
            HostDeclarationSurface surface,
            Map<ModuleId, CanonicalModuleIdentity> identities,
            ClassRegistrationSeeds seeds) {
        Objects.requireNonNull(surface, "surface must not be null");
        Objects.requireNonNull(identities, "identities must not be null");
        Objects.requireNonNull(seeds, "seeds must not be null");

        Map<ModuleId, RuntimeDeclarationSurface.RuntimeDeclarationModule> modules =
            new LinkedHashMap<>();
        for (Map.Entry<ModuleId, HostDeclarationSurface.DeclarationFacts> entry
                : surface.modules().entrySet()) {
            ModuleId moduleId = entry.getKey();
            HostDeclarationSurface.DeclarationFacts facts = entry.getValue();
            CanonicalModuleIdentity identity = identities.get(moduleId);

            // Declared exports: ascending Java String name order (the
            // pinned v1.4 declared-export order), each an exact structural
            // descriptor of the resolved declared type.
            Map<String, RuntimeDescriptor> exports = new LinkedHashMap<>();
            for (String name : new TreeMap<>(facts.exports()).keySet()) {
                Type declaredType = facts.exports().get(name);
                RuntimeDescriptor descriptor;
                try {
                    descriptor = DescriptorService.describe(declaredType);
                } catch (DescriptorService.Defect defect) {
                    throw new SharedEmissionFailure(
                        "declared export '" + name + "' of declaration module '"
                            + moduleId.path() + "' has no structural runtime"
                            + " descriptor: " + defect.getMessage(), null);
                }
                exports.put(name, descriptor);
            }

            Map<String, RuntimeDeclarationSurface.RuntimeDeclaredClass> classes =
                new LinkedHashMap<>();
            for (HostDeclarationSurface.DeclaredClass declaredClass
                    : facts.classes().values()) {
                if (identity == null) {
                    throw new SharedEmissionFailure(
                        "declared class '" + declaredClass.name()
                            + "' of declaration module '" + moduleId.path()
                            + "' has no resolvable canonical module identity:"
                            + " the structural declaration surface cannot name"
                            + " its class identity (producer defect)", null);
                }
                ClassId classId = classIdOf(identity, declaredClass.name());
                ClassRegistrationSeeds.ClassRegistration registration =
                    seeds.registrationFor(classId);
                if (registration == null) {
                    throw new SharedEmissionFailure(
                        "declared class '" + declaredClass.name() + "' of"
                            + " declaration module '" + moduleId.path()
                            + "' carries no class registration seed under "
                            + classId + ": the structural declaration surface"
                            + " projects from the shared seeds (producer"
                            + " defect)", firstFieldRange(declaredClass));
                }
                ClassLayout layout = registration.layout();
                List<HostDeclarationSurface.DeclaredField> declaredFields =
                    declaredClass.fields();
                if (declaredFields.size() != layout.fields().size()) {
                    throw new SharedEmissionFailure(
                        "declared class '" + declaredClass.name() + "' of"
                            + " declaration module '" + moduleId.path()
                            + "' declares " + declaredFields.size()
                            + " field(s) but its registration seed carries "
                            + layout.fields().size() + ": the structural"
                            + " declaration surface needs one seed field per"
                            + " declared field in declaration order (producer"
                            + " defect)", firstFieldRange(declaredClass));
                }
                List<RuntimeDeclarationSurface.RuntimeDeclaredField> fields =
                    new java.util.ArrayList<>(declaredFields.size());
                for (int i = 0; i < declaredFields.size(); i++) {
                    HostDeclarationSurface.DeclaredField declaredField =
                        declaredFields.get(i);
                    ClassLayout.FieldLayout seedField = layout.fields().get(i);
                    if (!declaredField.declaration().name()
                            .equals(seedField.name())) {
                        throw new SharedEmissionFailure(
                            "declared class '" + declaredClass.name()
                                + "' of declaration module '"
                                + moduleId.path() + "' carries seed field '"
                                + seedField.name() + "' at position " + i
                                + ", not the declaration surface's '"
                                + declaredField.declaration().name()
                                + "' (declaration order; producer defect)",
                            declaredField.declaration().span().range());
                    }
                    fields.add(new RuntimeDeclarationSurface
                        .RuntimeDeclaredField(seedField.name(),
                            seedField.descriptor(), seedField.required()));
                }
                classes.put(declaredClass.name(),
                    new RuntimeDeclarationSurface.RuntimeDeclaredClass(
                        classId, fields));
            }

            modules.put(moduleId,
                new RuntimeDeclarationSurface.RuntimeDeclarationModule(
                    RuntimeDeclarationSurface.DeclarationKind.of(facts.kind()),
                    exports, classes));
        }
        return new RuntimeDeclarationSurface(modules);
    }

    /**
     * The class identity of one declared class: the compilation's
     * module-path classification projected by
     * {@link DescriptorService#semanticModulePath} — the same rule the
     * class registration seeds use, never a dotted-path reconstruction.
     */
    private static ClassId classIdOf(CanonicalModuleIdentity moduleIdentity,
                                     String className) {
        return new ClassId(DescriptorService.semanticModulePath(
            new CanonicalClassIdentity(moduleIdentity, className)), className);
    }

    /** The first field declaration's range of one declared class, or null. */
    private static deal.diagnostics.DiagnosticRange firstFieldRange(
            HostDeclarationSurface.DeclaredClass declaredClass) {
        if (declaredClass.fields().isEmpty()) {
            return null;
        }
        return declaredClass.fields().get(0).declaration().span().range();
    }
}
