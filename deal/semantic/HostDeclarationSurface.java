package deal.semantic;

import deal.ast.ClassField;
import deal.diagnostics.CompilerDiagnostic;
import deal.semantic.ir.ModuleId;
import deal.types.Type;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record HostDeclarationSurface(
        Map<ModuleId, DeclarationFacts> modules) {

    public HostDeclarationSurface {
        Objects.requireNonNull(modules, "modules must not be null");
        Map<ModuleId, DeclarationFacts> frozen = new LinkedHashMap<>();
        for (Map.Entry<ModuleId, DeclarationFacts> entry
                : modules.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "module id must not be null");
            Objects.requireNonNull(entry.getValue(),
                "declaration facts must not be null");
            frozen.put(entry.getKey(), entry.getValue());
        }
        modules = Collections.unmodifiableMap(frozen);
    }

    public enum DeclarationKind {

        /**
         * A host declaration module: its declared classes carry the
         * loaded host module's {@code <C>_defaults} entry.
         */
        HOST,

        /**
         * An extern-C declaration module (the metadata phase classifies
         * it through {@code @extern-c}): its declared classes carry the
         * loaded module's validated {@code <C>_plan} entry.
         */
        EXTERN_C
    }

    /**
     * The declaration facts of one declaration module: its identity, its
     * declaration kind, its declared export names with their checked
     * {@link Type}s in declaration order, and its declared classes with
     * their per-class declaration kind and fields in declaration order.
     *
     */
    public record DeclarationFacts(
            ModuleId moduleId,
            DeclarationKind kind,
            Map<String, Type> exports,
            Map<String, DeclaredClass> classes) {

        public DeclarationFacts {
            Objects.requireNonNull(moduleId, "moduleId must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(exports, "exports must not be null");
            Objects.requireNonNull(classes, "classes must not be null");
            // Insertion-order-preserving frozen copies: the declaration
            // order is part of the pinned fact source.
            exports = Collections.unmodifiableMap(new LinkedHashMap<>(exports));
            Map<String, DeclaredClass> frozenClasses = new LinkedHashMap<>();
            for (Map.Entry<String, DeclaredClass> entry
                    : classes.entrySet()) {
                Objects.requireNonNull(entry.getKey(),
                    "class name must not be null");
                DeclaredClass declaredClass =
                    Objects.requireNonNull(entry.getValue(),
                        "declared class must not be null");
                if (declaredClass.kind() != kind) {
                    throw new IllegalArgumentException(
                        "declared class '" + declaredClass.name()
                            + "' of declaration module '" + moduleId.path()
                            + "' carries kind " + declaredClass.kind()
                            + " but its module carries " + kind
                            + ": one declaration kind per module");
                }
                frozenClasses.put(entry.getKey(), declaredClass);
            }
            classes = Collections.unmodifiableMap(frozenClasses);
        }

        /** The declared class of one class name, or null when absent. */
        public DeclaredClass declaredClass(String name) {
            return classes.get(name);
        }
    }

    /**
     * One declared class of a declaration module: the class name, its
     * declaration kind, and its fields in declaration order.
     *
     */
    public record DeclaredClass(
            String name,
            DeclarationKind kind,
            List<DeclaredField> fields) {

        public DeclaredClass {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            fields = List.copyOf(
                Objects.requireNonNull(fields, "fields must not be null"));
            if (name.isEmpty()) {
                throw new IllegalArgumentException("class name must not be empty");
            }
        }
    }

    /**
     * One declared class field: the declaration AST's {@link ClassField}
     * record plus the resolved declared {@link Type} — the same fact pair
     * the retained host consumers read, so the host projection stays
     * byte-identical.
     *
     */
    public record DeclaredField(ClassField declaration, Type type) {

        public DeclaredField {
            Objects.requireNonNull(declaration,
                "declaration must not be null");
            Objects.requireNonNull(type, "type must not be null");
        }
    }

    /**
     * The outcome of one {@link #produce(List)} call: on success exactly
     * one immutable surface and no diagnostics; on a declared class
     * field whose resolved type has no runtime representation, both no
     * surface and the first E6005 produced through the descriptor path.
     *
     */
    public record Production(
            HostDeclarationSurface surface,
            List<CompilerDiagnostic> diagnostics) {

        public Production {
            Objects.requireNonNull(diagnostics,
                "diagnostics must not be null");
            diagnostics = List.copyOf(diagnostics);
            if (surface != null && !diagnostics.isEmpty()) {
                throw new IllegalArgumentException(
                    "a successful production carries no diagnostics");
            }
            if (surface == null && diagnostics.isEmpty()) {
                throw new IllegalArgumentException(
                    "a failed production carries at least one diagnostic");
            }
        }

        /** Whether production failed (at least one E6005 diagnostic). */
        public boolean hasErrors() {
            return !diagnostics.isEmpty();
        }
    }

    /**
     * The single production path of the declaration surface: freezes the
     * given per-declaration-module facts into one immutable surface or,
     * for the first declared class field whose resolved type has no
     * runtime representation, returns the first E6005 with no surface.
     *
     * <p>Every module contributes exactly one entry; a duplicate module
     * identity is a producer defect rejected at construction (never a
     * silently overwritten entry). The representability check runs over
     * every declared class field in declaration order through
     * {@link DescriptorService#describe} — the existing descriptor path
     * — so the surface can never carry an unrepresentable field type.</p>
     *
     */
    public static Production produce(List<DeclarationFacts> facts) {
        Objects.requireNonNull(facts, "facts must not be null");
        Map<ModuleId, DeclarationFacts> modules = new LinkedHashMap<>();
        for (DeclarationFacts module : facts) {
            Objects.requireNonNull(module, "declaration facts must not be null");
            if (modules.putIfAbsent(module.moduleId(), module) != null) {
                throw new IllegalArgumentException(
                    "duplicate declaration module '" + module.moduleId().path()
                        + "': exactly one entry per declaration module");
            }
            for (DeclaredClass declaredClass : module.classes().values()) {
                for (DeclaredField field : declaredClass.fields()) {
                    try {
                        DescriptorService.describe(field.type());
                    } catch (DescriptorService.Defect defect) {
                        return new Production(null, List.of(
                            DescriptorService.e6005(module.moduleId(),
                                new DescriptorService.Defect(
                                    "declared class field '"
                                        + declaredClass.name() + "."
                                        + field.declaration().name()
                                        + "' of declaration module '"
                                        + module.moduleId().path() + "': "
                                        + defect.getMessage()))));
                    }
                }
            }
        }
        return new Production(new HostDeclarationSurface(modules), List.of());
    }

    /**
     * The declaration facts of one module identity; an absent identity is
     * a producer defect (every declaration module of the compilation is
     * covered by construction, so a missing entry can never be a
     * legitimate state and is never silently defaulted).
     *
     */
    public DeclarationFacts require(ModuleId moduleId) {
        Objects.requireNonNull(moduleId, "moduleId must not be null");
        DeclarationFacts found = modules.get(moduleId);
        if (found == null) {
            throw new IllegalStateException(
                "declaration module '" + moduleId.path()
                    + "' has no declaration-surface entry: the surface covers"
                    + " every host and extern-C declaration module of the"
                    + " compilation (producer defect)");
        }
        return found;
    }

    /** The declaration module identities in the surface's visit order. */
    public List<ModuleId> moduleIds() {
        return List.copyOf(modules.keySet());
    }
}
