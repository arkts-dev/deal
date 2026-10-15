package deal.semantic.ir;

import java.util.List;
import java.util.Objects;

/**
 * One raw adapter source reference at a registry-binding adapter position
 * (the {@code sourceRef} member of the complete {@code adapter} binding
 * variant). The wire discriminator stays a raw string: the three
 * recognized shapes are decoded into their structured members, and an
 * unknown discriminator — an open value in the closed
 * {@code AdaptSourceRef} position — keeps its exact decoded object,
 * members included, so the responsible {@code R-ENUM} rule reports it
 * instead of the decoder failing or dropping content.
 *
 * <ul>
 *   <li>{@link SharedCell} ({@code sharedCell}) — the binding and its
 *       pinned generation.</li>
 *   <li>{@link Thunk} ({@code thunk}) — the thunk block and its ordered
 *       generation-pinned captures.</li>
 *   <li>{@link Value} ({@code value}) — the retained value identity.</li>
 *   <li>{@link Unknown} — any other discriminator with its complete
 *       decoded member set.</li>
 * </ul>
 *
 * <p>Recognized shapes keep their exact member sets (enforced at decode);
 * the typed producer surface is closed and never produces an
 * {@link Unknown}.</p>
 *
 */
public sealed interface RawSourceRef
    permits RawSourceRef.SharedCell, RawSourceRef.Thunk, RawSourceRef.Value,
        RawSourceRef.Unknown {

    /** The wire discriminator carried by this source reference. */
    String type();

    /** SHARED_CELL: load the binding's current value per invocation. */
    record SharedCell(BindingId binding, long generation) implements RawSourceRef {

        public SharedCell {
            Objects.requireNonNull(binding, "binding must not be null");
            if (generation < 0) {
                throw new IllegalArgumentException("generation must be >= 0, got " + generation);
            }
        }

        @Override
        public String type() {
            return "sharedCell";
        }
    }

    /** REEVALUATE_THUNK: re-execute the recorded thunk block per invocation. */
    record Thunk(BlockId blockId, List<BindingGeneration> capturedBindings)
        implements RawSourceRef {

        public Thunk {
            Objects.requireNonNull(blockId, "blockId must not be null");
            capturedBindings = List.copyOf(
                Objects.requireNonNull(capturedBindings, "capturedBindings must not be null"));
        }

        @Override
        public String type() {
            return "thunk";
        }
    }

    /** VALUE: retain the materialized function value identity. */
    record Value(ValueId value) implements RawSourceRef {

        public Value {
            Objects.requireNonNull(value, "value must not be null");
        }

        @Override
        public String type() {
            return "value";
        }
    }

    /**
     * An unrecognized discriminator: the raw type string plus the exact
     * decoded object (every member retained) for the closed-position rule
     * and for byte-stable reserialization.
     */
    record Unknown(String type, CanonicalJson.Obj json) implements RawSourceRef {

        public Unknown {
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(json, "json must not be null");
        }
    }
}
