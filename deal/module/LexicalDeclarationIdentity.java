package deal.module;

import deal.diagnostics.DiagnosticRange;

import java.util.Objects;

/**
 * The parent-pinned lexical declaration identity shape (design source
 * {@code deal-v1.2-int32-and-bytes-architecture} D5):
 * {@code LexicalDeclarationIdentity(declarationKind, declaredName,
 * enclosingLexicalDeclarationPath, sourceScalarRange)}.
 *
 * <ul>
 *   <li>{@code declarationKind} — {@link DeclarationKind#FUNCTION} or
 *       {@link DeclarationKind#CLASS}.</li>
 *   <li>{@code declaredName} — the declared identifier text.</li>
 *   <li>{@code enclosingLexicalDeclarationPath} — the enclosing lexical
 *       declaration path of the declaration (the module-qualified dotted
 *       path of the enclosing declaration context; the exact content
 *       semantics are owned by the default-plan and resource-planner
 *       epics — this carrier pins the field and its determinism).</li>
 *   <li>{@code sourceScalarRange} — the declaration's complete half-open
 *       decoded-Unicode-scalar source range through the complete-range
 *       carrier.</li>
 * </ul>
 *
 * <p>The record is immutable; deterministic identical inputs reproduce
 * equal values, and no address, timestamp, ordinal, or process state
 * enters any component. The identity is compiler-internal: it never
 * appears in runtime descriptors, diagnostic type names, public export
 * keys, or source-language values.</p>
 *
 * @param declarationKind               the declaration kind
 * @param declaredName                  the declared name
 * @param enclosingLexicalDeclarationPath the enclosing lexical
 *                                       declaration path
 * @param sourceScalarRange             the complete source scalar range
 */
public record LexicalDeclarationIdentity(DeclarationKind declarationKind,
                                         String declaredName,
                                         String enclosingLexicalDeclarationPath,
                                         DiagnosticRange sourceScalarRange) {

    /**
     * The lexical declaration kinds of the resource-identity carriers.
     */
    public enum DeclarationKind {
        /** A function declaration. */
        FUNCTION,
        /** A class declaration. */
        CLASS
    }

    public LexicalDeclarationIdentity {
        Objects.requireNonNull(declarationKind, "declarationKind");
        Objects.requireNonNull(declaredName, "declaredName");
        Objects.requireNonNull(enclosingLexicalDeclarationPath,
            "enclosingLexicalDeclarationPath");
        Objects.requireNonNull(sourceScalarRange, "sourceScalarRange");
    }
}
