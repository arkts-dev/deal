package deal.semantic.ir;

import java.util.Objects;

/**
 * A stable class identity with the canonical text
 * {@code @modulePath/ClassName} (parent D4; canonical surfaces).
 *
 * <p>Immutable value record. The canonical text matches the
 * {@code ClassDescriptor} grammar ({@code "@" ModuleRoot "/"
 * ModuleRelativePath "/" ClassName}, parent D6): the relative module
 * path runs from the configured root to the defining file, the file name
 * included, so a class named {@code User} declared in
 * {@code src/app/user.deal} has the text {@code @src/app/user.deal/User}.
 * Two same-named sibling files therefore never share a class identity. The builtin {@code Error} class is the one
 * builtin nominal class (spec: user code must not declare a class named
 * {@code Error}); its module path is empty and its canonical v1.3 text is
 * exactly {@code Error} (see {@link #ERROR}) — the one class atom without
 * the leading {@code @}. The superseded v1.2 spelling {@code @/Error} is
 * never produced and never decodes. Target class names never appear in
 * canonical descriptor text.</p>
 *
 * <p>The empty module path is admissible only for the builtin
 * {@code Error} class: every other empty-module-path class is malformed
 * internal data and is rejected at construction rather than silently
 * carrying ambiguous text.</p>
 *
 */
public record ClassId(String modulePath, String name) implements SemanticId {

    /** The pinned class name of the builtin {@code Error} class. */
    private static final String ERROR_NAME = "Error";

    /** The builtin {@code Error} class identity (canonical text {@code Error}). */
    public static final ClassId ERROR = new ClassId("", ERROR_NAME);

    public ClassId {
        Objects.requireNonNull(modulePath, "modulePath must not be null");
        Objects.requireNonNull(name, "name must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("class name must not be empty");
        }
        if (modulePath.isEmpty() && !ERROR_NAME.equals(name)) {
            throw new IllegalArgumentException("an empty module path is the"
                + " builtin module, whose only class is 'Error': got '" + name + "'");
        }
    }

    /**
     * The canonical class-descriptor text: {@code Error} for the builtin
     * class (empty module path), {@code @modulePath/ClassName} otherwise.
     */
    public String text() {
        if (modulePath.isEmpty()) {
            return name;
        }
        return "@" + modulePath + "/" + name;
    }

    @Override
    public String toString() {
        return text();
    }
}
