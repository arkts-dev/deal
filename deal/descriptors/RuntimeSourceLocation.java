package deal.descriptors;

import java.util.Objects;

/**
 * The optional source location a runtime check failure carries: the
 * {@code (file, line, column)} triple the pinned check table names
 * (design source {@code canonical-type-system-and-runtime-descriptors}
 * D4 — {@code RuntimeTypeMatcher.check(descriptor, value, range)} with
 * {@code RuntimeSourceLocation? range}).
 *
 * <p>The location as a whole is optional at the check surface: a
 * {@code null} range means the failure carries no location.  When a
 * location is present, all three fields are set — a non-null file and
 * 1-based line/column numbers, matching the runtime libraries'
 * error-location convention (an absent field there is a nil Lua field;
 * here the whole record is absent).</p>
 *
 * <p>Immutable and thread-safe; carries no checking or formatting
 * behavior — it is a plain value carrier.</p>
 */
public record RuntimeSourceLocation(String file, int line, int column) {

    public RuntimeSourceLocation {
        Objects.requireNonNull(file, "file must not be null");
        if (line < 1) {
            throw new IllegalArgumentException("line must be >= 1: " + line);
        }
        if (column < 1) {
            throw new IllegalArgumentException("column must be >= 1: " + column);
        }
    }
}
