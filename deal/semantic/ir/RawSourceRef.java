package deal.semantic.ir;

import java.util.Objects;

/** An evaluated adapter source, or an unknown wire shape retained for R-ENUM. */
public sealed interface RawSourceRef permits RawSourceRef.Value, RawSourceRef.Unknown {
    String type();

    record Value(ValueId value) implements RawSourceRef {
        public Value {
            Objects.requireNonNull(value, "value must not be null");
        }
        @Override public String type() { return "value"; }
    }

    /** Preserve unknown discriminators and members until semantic admission. */
    record Unknown(String type, CanonicalJson.Obj json) implements RawSourceRef {
        public Unknown {
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(json, "json must not be null");
        }
    }
}
