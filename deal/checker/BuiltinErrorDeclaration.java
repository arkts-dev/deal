package deal.checker;

import deal.ast.ClassField;
import deal.ast.NamedType;
import deal.ast.Span;
import deal.identity.CanonicalClassIdentity;
import deal.types.Type;

import java.util.List;
import java.util.Objects;

public record BuiltinErrorDeclaration(
        CanonicalClassIdentity identity,
        List<Field> fields) {

    public BuiltinErrorDeclaration {
        Objects.requireNonNull(identity, "identity must not be null");
        fields = List.copyOf(
            Objects.requireNonNull(fields, "fields must not be null"));
    }

    /**
     * One declared field of the builtin {@code Error} class: the
     * declaration AST record (name, optional and nullable) plus the resolved
     * declared {@link Type}. Both fields require supplied values.
     *
     */
    public record Field(ClassField declaration, Type type) {

        public Field {
            Objects.requireNonNull(declaration,
                "declaration must not be null");
            Objects.requireNonNull(type, "type must not be null");
        }

        /** The field name exactly as declared. */
        public String name() {
            return declaration.name();
        }

        /**
         * Whether the field is required-present (the declaration's
         * optional-negation) — every builtin {@code Error} field is.
         */
        public boolean required() {
            return !declaration.optional();
        }
    }

    /**
     * The synthesized builtin {@code Error} class declaration with its
     * AST field records at the given span: {@code name} and
     * {@code message}, both required-present {@code string} fields. The declared
     * {@code string} spelling and the resolved {@link Type.String} fact
     * are authored here, together, exactly once.
     *
     */
    public static BuiltinErrorDeclaration synthesized(Span span) {
        Objects.requireNonNull(span, "span must not be null");
        return new BuiltinErrorDeclaration(
            NameResolver.intrinsicErrorIdentity(),
            List.of(
                new Field(new ClassField(span, "name", false, false,
                    new NamedType(span, "string")),
                    Type.String.INSTANCE),
                new Field(new ClassField(span, "message", false, false,
                    new NamedType(span, "string")),
                    Type.String.INSTANCE)));
    }
}
