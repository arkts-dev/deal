package deal.semantic.ir;

import java.util.Objects;

/**
 * A stable module identity: the dotted module path (parent D4).
 *
 * <p>Immutable value record. The path is the compiler-resolved dotted
 * module path (e.g. {@code "a.b"}, {@code "std/time"}), never a file
 * location or an AST node. Identity is the path string; two
 * {@code ModuleId}s are equal iff their paths are equal.</p>
 *
 * @param path the dotted module path; non-null and non-empty
 */
public record ModuleId(String path) implements SemanticId {

    public ModuleId {
        Objects.requireNonNull(path, "path must not be null");
        if (path.isEmpty()) {
            throw new IllegalArgumentException("module path must not be empty");
        }
    }

    @Override
    public String toString() {
        return path;
    }
}
