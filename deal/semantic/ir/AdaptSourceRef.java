package deal.semantic.ir;

import java.util.Objects;

/** A function adapter retains its already-evaluated source value. */
public sealed interface AdaptSourceRef permits AdaptSourceRef.Value {
    record Value(ValueId value) implements AdaptSourceRef {
        public Value {
            Objects.requireNonNull(value, "value must not be null");
        }
    }
}
