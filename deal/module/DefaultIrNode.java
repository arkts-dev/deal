package deal.module;

import deal.ast.ExpressionNode;
import deal.ast.LiteralValue;
import deal.ast.StatementNode;
import deal.diagnostics.DiagnosticRange;
import deal.types.Type;

import java.util.List;
import java.util.Objects;

public record DefaultIrNode(
    NodeKind kind,
    String operatorKind,
    Type resultType,
    List<Type> contextualTypes,
    List<DefaultIrNode> children,
    LiteralValue literalValue,
    Target target,
    String declaredName,
    StatementNode statement,
    ExpressionNode expression,
    DiagnosticRange span
) implements TypedEvaluatorIr {

    public DefaultIrNode {
        Objects.requireNonNull(kind, "kind");
        contextualTypes = List.copyOf(Objects.requireNonNull(
            contextualTypes, "contextualTypes"));
        children = List.copyOf(Objects.requireNonNull(children, "children"));
        Objects.requireNonNull(span, "span");
    }

    /**
     * The closed node-kind set: the fourteen {@code deal.ast}
     * expression kinds plus the {@code provider-versioned-default-plans}
     * D9 statement kinds that can appear inside a default's nested
     * function bodies.
     */
    public enum NodeKind {
        // Expression kinds (the fourteen deal.ast construct set).
        ARRAY_LITERAL,
        ASSIGNMENT,
        AWAIT,
        BINARY,
        CALL,
        FUNCTION_EXPRESSION,
        HAS,
        IDENTIFIER,
        INDEX,
        LITERAL,
        MEMBER_ACCESS,
        OBJECT_LITERAL,
        TEMPLATE_LITERAL,
        UNARY,
        // Statement kinds (the D9 closed body statement set).
        BLOCK,
        BREAK,
        CLASS_DECLARATION,
        CONTINUE,
        DELETE,
        EXPRESSION_STATEMENT,
        FOR,
        FOR_OF,
        FUNCTION_DECLARATION,
        IF,
        RETURN,
        THROW,
        TRY,
        VARIABLE_DECLARATION,
        WHILE
    }

    /**
     * The resolved target of a node: a local/lexical binding marker, a
     * same-module or imported resource identity, an intrinsic, or an
     * import-alias module marker. Never source names beyond the binding
     * name: imported resources carry their
     * {@link SemanticResourceIdentity}.
     *
     */
    public record Target(TargetKind kind, String name,
                         SemanticResourceIdentity resourceIdentity) {

        public Target {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(name, "name");
        }

        /**
         * The local/lexical-binding marker factory (variables and
         * parameters; the binding name only — never source state).
         */
        public static Target localBinding(String name) {
            return new Target(TargetKind.LOCAL_BINDING, name, null);
        }

        /**
         * The intrinsic marker factory ({@code int}/{@code number}/
         * {@code bytes}/{@code has}/{@code Error}).
         */
        public static Target intrinsic(String name) {
            return new Target(TargetKind.INTRINSIC, name, null);
        }

        /** The import-alias module marker factory. */
        public static Target moduleAlias(String name) {
            return new Target(TargetKind.MODULE_ALIAS, name, null);
        }

        /**
         * The same-module resource factory (a function or class
         * declared in the consuming module; identity-only target).
         */
        public static Target sameModuleResource(String name,
                SemanticResourceIdentity resourceIdentity) {
            Objects.requireNonNull(resourceIdentity, "resourceIdentity");
            return new Target(TargetKind.SAME_MODULE_RESOURCE, name,
                resourceIdentity);
        }

        /**
         * The imported resource factory (a function wrapper or class
         * default plan of an imported module).
         */
        public static Target importedResource(String name,
                SemanticResourceIdentity resourceIdentity) {
            Objects.requireNonNull(resourceIdentity, "resourceIdentity");
            return new Target(TargetKind.IMPORTED_RESOURCE, name,
                resourceIdentity);
        }
    }

    /**
     * The closed target-kind set.
     */
    public enum TargetKind {
        /** A local variable or parameter binding. */
        LOCAL_BINDING,
        /** A function or class declared in the consuming module. */
        SAME_MODULE_RESOURCE,
        /** A resource of an imported module. */
        IMPORTED_RESOURCE,
        /** A compiler intrinsic ({@code int}, {@code number}, ...). */
        INTRINSIC,
        /** An import alias (module marker). */
        MODULE_ALIAS
    }
}
