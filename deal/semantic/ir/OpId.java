package deal.semantic.ir;

import java.util.Objects;

/**
 * A stable operation identity carrying its module (parent D4).
 *
 * <p>Immutable value record. Because every {@code OpId} carries its
 * module, cross-unit {@code parentOpId} references (an
 * {@code EXTERNAL_ENTRY} or {@code CLASS_FACTORY} recording its triggering
 * caller op) are unambiguous without any project-wide lookup table.</p>
 *
 * @param module the module the op belongs to; non-null
 * @param id     the numeric identity, unique within the project; non-negative
 */
public record OpId(ModuleId module, long id) implements SemanticId {

    public OpId {
        Objects.requireNonNull(module, "module must not be null");
        if (id < 0) {
            throw new IllegalArgumentException("id must be >= 0, got " + id);
        }
    }

    @Override
    public String toString() {
        return "OpId(" + module + "#" + id + ")";
    }
}
