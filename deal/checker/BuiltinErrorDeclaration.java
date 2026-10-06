package deal.checker;

import deal.ast.ClassField;
import deal.ast.LiteralExpr;
import deal.ast.LiteralValue;
import deal.ast.NamedType;
import deal.ast.Span;
import deal.identity.CanonicalClassIdentity;
import deal.types.Type;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
     * declaration AST record (name, optional, nullable, default-expression
     * presence — the constant empty-string default) plus the resolved
     * declared {@link Type}.
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
     * AST field records at the given span: {@code code} and
     * {@code message}, both non-optional {@code string} fields whose
     * default expression is the constant empty string. The declared
     * {@code string} spelling and the resolved {@link Type.String} fact
     * are authored here, together, exactly once.
     *
     */
    public static BuiltinErrorDeclaration synthesized(Span span) {
        Objects.requireNonNull(span, "span must not be null");
        LiteralExpr emptyString = new LiteralExpr(span,
            new LiteralValue.StringLiteral(""));
        return new BuiltinErrorDeclaration(
            NameResolver.intrinsicErrorIdentity(),
            List.of(
                new Field(new ClassField(span, "code", false, false,
                    new NamedType(span, "string"), Optional.of(emptyString)),
                    Type.String.INSTANCE),
                new Field(new ClassField(span, "message", false, false,
                    new NamedType(span, "string"), Optional.of(emptyString)),
                    Type.String.INSTANCE)));
    }
}
