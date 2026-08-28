package deal.descriptors;

import java.util.List;
import java.util.Objects;

/**
 * Immutable abstract syntax of one canonical runtime type descriptor
 * (DEAL v1.2).  Closed family — exactly the five atoms below; no other
 * atom shape exists:
 *
 * <ul>
 *   <li>{@link PrimitiveAtom} — one of {@code null}, {@code boolean},
 *       {@code int}, {@code number}, {@code string}, {@code bytes},
 *       {@code table}.</li>
 *   <li>{@link ClassAtom} — a text-opaque class descriptor atom whose
 *       {@linkplain ClassAtom#fullDescriptorText() full text} includes the
 *       leading {@code @} byte-for-byte as consumed.  No root/path
 *       boundary is ever inferred; equality is byte-for-byte text
 *       equality and the only structural validation the parser applies is
 *       the pinned final-component class-name shape.</li>
 *   <li>{@link ArrayAtom} — {@code [D]}.</li>
 *   <li>{@link NullableAtom} — {@code ?D}.</li>
 *   <li>{@link FunctionAtom} — the exact sync/async marker, the ordered
 *       parameter atoms, and the return atom.</li>
 * </ul>
 *
 * <p>All atoms are deeply immutable.  Records provide structural
 * equality; for {@link ClassAtom} that equality is the byte-for-byte
 * full-text comparison pinned by the descriptor contract.</p>
 */
public sealed interface DescriptorAst extends DescriptorParseResult
    permits DescriptorAst.PrimitiveAtom,
           DescriptorAst.ClassAtom,
           DescriptorAst.ArrayAtom,
           DescriptorAst.NullableAtom,
           DescriptorAst.FunctionAtom {

    /**
     * A primitive descriptor atom.  The canonical names are
     * {@code null}, {@code boolean}, {@code int}, {@code number},
     * {@code string}, {@code bytes}, and {@code table}; the pinned
     * {@code bytes} name is {@link CanonicalRuntimeTypeDescriptor#BYTES_DESCRIPTOR}.
     */
    record PrimitiveAtom(String name) implements DescriptorAst {
        public PrimitiveAtom {
            Objects.requireNonNull(name, "name must not be null");
        }
    }

    /**
     * A text-opaque class descriptor atom.  {@code fullDescriptorText} is
     * the complete class descriptor text including the leading {@code @},
     * byte-for-byte as consumed by the strict parser.  Atoms are never
     * split into root/path/class-name structure beyond the parser's
     * final-component class-name validation.
     */
    record ClassAtom(String fullDescriptorText) implements DescriptorAst {
        public ClassAtom {
            Objects.requireNonNull(fullDescriptorText, "fullDescriptorText must not be null");
        }
    }

    /** An array descriptor atom {@code [D]}. */
    record ArrayAtom(DescriptorAst element) implements DescriptorAst {
        public ArrayAtom {
            Objects.requireNonNull(element, "element must not be null");
        }
    }

    /** A nullable descriptor atom {@code ?D}. */
    record NullableAtom(DescriptorAst inner) implements DescriptorAst {
        public NullableAtom {
            Objects.requireNonNull(inner, "inner must not be null");
        }
    }

    /**
     * A function descriptor atom {@code async? (D, ...) -> D} carrying the
     * exact sync/async marker, the ordered parameter atoms, and the return
     * atom.  The parameter list is defensively copied and immutable.
     */
    record FunctionAtom(boolean isAsync, List<DescriptorAst> params, DescriptorAst returnType)
            implements DescriptorAst {
        public FunctionAtom {
            Objects.requireNonNull(params, "params must not be null");
            params = List.copyOf(params);
            Objects.requireNonNull(returnType, "returnType must not be null");
        }
    }
}
