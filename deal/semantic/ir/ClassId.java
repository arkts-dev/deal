package deal.semantic.ir;

import java.util.Objects;

/**
 * A stable class identity with the canonical text
 * {@code @modulePath/ClassName} (parent D4; canonical surfaces).
 *
 * <p>Immutable value record. The canonical text matches the
 * {@code ClassDescriptor} grammar ({@code "@" ModuleRoot "/"
 * ModuleRelativePath "/" ClassName}, parent D6): for a class declared in
 * module {@code src/app} named {@code User} the text is
 * {@code @src/app/User}. The builtin {@code Error} class is the one
 * builtin nominal class (spec: user code must not declare a class named
 * {@code Error}); its module path is empty, so its canonical text is
 * {@code @/Error} (see {@link #ERROR}). Target class names never appear in
 * canonical descriptor text.</p>
 *
 * @param modulePath the dotted module path of the declaring module
 *                   (empty for the builtin {@code Error} class)
 * @param name       the class name; non-null and non-empty
 */
public record ClassId(String modulePath, String name) implements SemanticId {

    /** The builtin {@code Error} class identity ({@code @/Error}). */
    public static final ClassId ERROR = new ClassId("", "Error");

    public ClassId {
        Objects.requireNonNull(modulePath, "modulePath must not be null");
        Objects.requireNonNull(name, "name must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("class name must not be empty");
        }
    }

    /** The canonical {@code @modulePath/ClassName} descriptor text. */
    public String text() {
        return "@" + modulePath + "/" + name;
    }

    @Override
    public String toString() {
        return text();
    }
}
