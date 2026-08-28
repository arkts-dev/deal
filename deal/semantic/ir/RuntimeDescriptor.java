package deal.semantic.ir;

import java.util.List;
import java.util.Objects;

/**
 * The sealed structural runtime descriptor of {@code deal.semantic-ir/1}
 * (parent D6; schema S2/S3).
 *
 * <p>Closed hierarchy — exactly the variants below and no others:
 * {@code null}, {@code boolean}, signed32 {@code int}, {@code number},
 * {@code string}, {@code table}, {@code class(ClassId)}, {@code array},
 * {@code nullable}, and sync/async {@code function}. Structural equality is
 * authoritative. {@code int} is signed 32-bit: no safe-range (±2^53−1)
 * representation exists anywhere in the schema. Descriptor
 * <em>production</em> from checked {@code Type}s is the
 * {@code DescriptorService}'s (ISSUE-0233) and is excluded here; this
 * hierarchy is the sealed data type the service produces into.</p>
 *
 * <p>Canonical spec text (parent D6, {@code docs/spec-v1.2.md:2231-2283}):
 * {@code null}, {@code boolean}, {@code int}, {@code number},
 * {@code string}, {@code table}; {@code ClassDescriptor} =
 * {@code @modulePath/ClassName} (target class names never appear);
 * {@code ArrayDescriptor} = {@code "[" RuntimeTypeDescriptor "]"};
 * {@code NullableDescriptor} = {@code "?" RuntimeTypeDescriptor} with
 * inner never {@code null} and never another nullable;
 * {@code FunctionDescriptor} = {@code AsyncMarker? "(" ParamDescriptorList?
 * ")" "->" RuntimeTypeDescriptor} with {@code ","}-joined parameter texts
 * and no spaces. Examples: {@code [int]}, {@code ?string},
 * {@code ?[@src/app/User]}, {@code (int,string)->boolean},
 * {@code ()->null}, {@code async(int)->string}.</p>
 */
public sealed interface RuntimeDescriptor extends OpResultType
    permits RuntimeDescriptor.Null,
            RuntimeDescriptor.Boolean,
            RuntimeDescriptor.Int,
            RuntimeDescriptor.Number,
            RuntimeDescriptor.String,
            RuntimeDescriptor.Table,
            RuntimeDescriptor.Class,
            RuntimeDescriptor.Array,
            RuntimeDescriptor.Nullable,
            RuntimeDescriptor.Func {

    /** The canonical spec text of this descriptor (parent D6 grammar). */
    java.lang.String canonicalSpecText();

    /** The {@code null} descriptor; canonical text {@code "null"}. */
    enum Null implements RuntimeDescriptor {
        INSTANCE;

        @Override
        public java.lang.String canonicalSpecText() {
            return "null";
        }
    }

    /** The {@code boolean} descriptor; canonical text {@code "boolean"}. */
    enum Boolean implements RuntimeDescriptor {
        INSTANCE;

        @Override
        public java.lang.String canonicalSpecText() {
            return "boolean";
        }
    }

    /**
     * The signed32 {@code int} descriptor; canonical text {@code "int"}.
     *
     * <p>Integer descriptor values are signed 32-bit
     * ({@code [-2147483648, 2147483647]}); every integer descriptor path
     * delegates to signed32 and can produce E8004 after kind/integrality
     * validation. No safe-range representation exists.</p>
     */
    enum Int implements RuntimeDescriptor {
        INSTANCE;

        @Override
        public java.lang.String canonicalSpecText() {
            return "int";
        }
    }

    /** The {@code number} descriptor; canonical text {@code "number"}. */
    enum Number implements RuntimeDescriptor {
        INSTANCE;

        @Override
        public java.lang.String canonicalSpecText() {
            return "number";
        }
    }

    /** The {@code string} descriptor; canonical text {@code "string"}. */
    enum String implements RuntimeDescriptor {
        INSTANCE;

        @Override
        public java.lang.String canonicalSpecText() {
            return "string";
        }
    }

    /** The {@code table} descriptor; canonical text {@code "table"}. */
    enum Table implements RuntimeDescriptor {
        INSTANCE;

        @Override
        public java.lang.String canonicalSpecText() {
            return "table";
        }
    }

    /**
     * A nominal class descriptor; canonical text {@code @modulePath/ClassName}.
     *
     * @param classId the closed class identity; non-null
     */
    record Class(ClassId classId) implements RuntimeDescriptor {

        public Class {
            Objects.requireNonNull(classId, "classId must not be null");
        }

        @Override
        public java.lang.String canonicalSpecText() {
            return classId.text();
        }
    }

    /**
     * An array descriptor; canonical text {@code "[" element "]"}. Function
     * and nullable elements keep the spec bracket form (no legacy
     * {@code T[]}/{@code T|null} spellings exist in the schema).
     *
     * @param element the element descriptor; non-null
     */
    record Array(RuntimeDescriptor element) implements RuntimeDescriptor {

        public Array {
            Objects.requireNonNull(element, "element must not be null");
        }

        @Override
        public java.lang.String canonicalSpecText() {
            return "[" + element.canonicalSpecText() + "]";
        }
    }

    /**
     * A nullable descriptor; canonical text {@code "?" inner}.
     *
     * <p>Invariants enforced at construction (the pinned spec constraint):
     * the inner descriptor must not be {@code null} and must not be
     * another nullable (flatten at construction).</p>
     *
     * @param inner the inner descriptor; non-null, not {@code Null}, not nullable
     */
    record Nullable(RuntimeDescriptor inner) implements RuntimeDescriptor {

        public Nullable {
            Objects.requireNonNull(inner, "inner must not be null");
            if (inner instanceof Null) {
                throw new IllegalArgumentException(
                    "Nullable inner must not be null; use Null.INSTANCE directly");
            }
            if (inner instanceof Nullable) {
                throw new IllegalArgumentException(
                    "Nullable inner must not be another Nullable; flatten at construction");
            }
        }

        @Override
        public java.lang.String canonicalSpecText() {
            return "?" + inner.canonicalSpecText();
        }
    }

    /**
     * A function descriptor; canonical text
     * {@code (p1,p2)->r} (sync) or {@code async(p1,p2)->r} (async), with
     * parameter texts joined by {@code ","} and no spaces.
     *
     * @param paramTypes the parameter descriptors in order; non-null
     * @param returnType the return descriptor; non-null
     * @param isAsync    whether the function is async
     */
    record Func(List<RuntimeDescriptor> paramTypes, RuntimeDescriptor returnType, boolean isAsync)
        implements RuntimeDescriptor {

        public Func(List<RuntimeDescriptor> paramTypes, RuntimeDescriptor returnType, boolean isAsync) {
            this.paramTypes = List.copyOf(paramTypes);
            this.returnType = Objects.requireNonNull(returnType, "returnType must not be null");
            this.isAsync = isAsync;
        }

        /** Convenience constructor: sync function ({@code isAsync = false}). */
        public Func(List<RuntimeDescriptor> paramTypes, RuntimeDescriptor returnType) {
            this(paramTypes, returnType, false);
        }

        @Override
        public java.lang.String canonicalSpecText() {
            StringBuilder sb = new StringBuilder();
            if (isAsync) {
                sb.append("async");
            }
            sb.append("(");
            for (int i = 0; i < paramTypes.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(paramTypes.get(i).canonicalSpecText());
            }
            sb.append(")->").append(returnType.canonicalSpecText());
            return sb.toString();
        }
    }
}
