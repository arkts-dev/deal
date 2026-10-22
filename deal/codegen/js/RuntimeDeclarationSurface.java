package deal.codegen.js;

import deal.semantic.HostDeclarationSurface;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.RuntimeDescriptor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The projected structural declaration surface of the JavaScript semantic-IR
 * renderer (design source {@code wiki:javascript-semantic-ir-emission},
 * "declaration_projection" and "host_export_order"):
 *
 * <pre>{@code
 * RuntimeDeclarationSurface { modules: ordered Map<ModuleId, RuntimeDeclarationModule> }
 * RuntimeDeclarationModule {
 *   kind: HOST,
 *   exports: ordered Map<String, RuntimeDescriptor>,   // ascending Java String order
 *   classes: ordered Map<String, RuntimeDeclaredClass>
 * }
 * RuntimeDeclaredClass { classId: ClassId, fields: ordered List<RuntimeDeclaredField> }
 * RuntimeDeclaredField {
 *   name: String, descriptor: RuntimeDescriptor,
 *   required: boolean, hasDefault: boolean
 * }
 * }</pre>
 *
 * <p>The record is pure structural data: module identities, runtime
 * descriptors, closed enums, names, declaration order, and flags only. No
 * AST node, checker {@code Type}/{@code Symbol}, frontend identity map or
 * default-expression AST occurs anywhere in the seam. The surface is
 * projected once by {@link RuntimeDeclarationProjection} from the shared
 * declaration facts and class registration seeds and is validated against
 * the complete project before rendering; the renderer selects nothing and
 * rediscovers no path or name.</p>
 *
 * <p>{@code exports} carries the v1.3 pinned declared-export order — the
 * ascending Java String order (the one name-sorted export
 * selection) — while class fields keep their declaration order and
 * HOST occurrences keep source order (the latter is not part of this
 * structural surface).</p>
 *
 */
public record RuntimeDeclarationSurface(
        Map<ModuleId, RuntimeDeclarationModule> modules) {

    public RuntimeDeclarationSurface {
        Objects.requireNonNull(modules, "modules must not be null");
        Map<ModuleId, RuntimeDeclarationModule> frozen = new LinkedHashMap<>();
        for (Map.Entry<ModuleId, RuntimeDeclarationModule> entry
                : modules.entrySet()) {
            Objects.requireNonNull(entry.getKey(),
                "module id must not be null");
            Objects.requireNonNull(entry.getValue(),
                "declaration module must not be null");
            frozen.put(entry.getKey(), entry.getValue());
        }
        modules = Collections.unmodifiableMap(frozen);
    }

    /** The declaration kind of one declaration module (the closed pair). */
    public enum DeclarationKind {

        /** A host declaration module. */
        HOST;
        /** The projection of one shared declaration-surface kind. */
        static DeclarationKind of(HostDeclarationSurface.DeclarationKind kind) {
            Objects.requireNonNull(kind, "kind must not be null");
            return HOST;
        }
    }

    /** One declaration module's structural projection. */
    public record RuntimeDeclarationModule(
            DeclarationKind kind,
            Map<String, RuntimeDescriptor> exports,
            Map<String, RuntimeDeclaredClass> classes) {

        public RuntimeDeclarationModule {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(exports, "exports must not be null");
            Objects.requireNonNull(classes, "classes must not be null");
            Map<String, RuntimeDescriptor> frozenExports = new LinkedHashMap<>();
            for (Map.Entry<String, RuntimeDescriptor> entry
                    : exports.entrySet()) {
                frozenExports.put(Objects.requireNonNull(entry.getKey(),
                        "export name must not be null"),
                    Objects.requireNonNull(entry.getValue(),
                        "export descriptor must not be null"));
            }
            exports = Collections.unmodifiableMap(frozenExports);
            Map<String, RuntimeDeclaredClass> frozenClasses = new LinkedHashMap<>();
            for (Map.Entry<String, RuntimeDeclaredClass> entry
                    : classes.entrySet()) {
                frozenClasses.put(Objects.requireNonNull(entry.getKey(),
                        "class name must not be null"),
                    Objects.requireNonNull(entry.getValue(),
                        "declared class must not be null"));
            }
            classes = Collections.unmodifiableMap(frozenClasses);
        }
    }

    /** One declared class's structural projection (declaration order fields). */
    public record RuntimeDeclaredClass(
            ClassId classId,
            List<RuntimeDeclaredField> fields) {

        public RuntimeDeclaredClass {
            Objects.requireNonNull(classId, "classId must not be null");
            Objects.requireNonNull(fields, "fields must not be null");
            fields = List.copyOf(fields);
        }
    }

    /**
     * One declared class field's structural projection: the field name, its
     * structural runtime descriptor, and the required/has-default flags. The
     * optional project is exactly {@code !required}; nullable lives in the
     * descriptor.
     */
    public record RuntimeDeclaredField(
            String name,
            RuntimeDescriptor descriptor,
            boolean required,
            boolean hasDefault) {

        public RuntimeDeclaredField {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(descriptor, "descriptor must not be null");
        }
    }
}
