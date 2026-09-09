package deal.module;

import deal.ast.LiteralValue;
import deal.ast.StatementNode;
import deal.diagnostics.DiagnosticRange;
import deal.types.Type;

import java.util.List;
import java.util.Objects;

/**
 * One node of the planner-owned structured typed evaluator IR
 * (ISSUE-0541; the planner-owned content of the {@link TypedEvaluatorIr}
 * seam, {@code provider-versioned-default-plans} D4/D6): the root of a
 * default's IR is this record, and the canonical serializer epic
 * (ISSUE-0542) serializes the tree into
 * {@link ResolvedDefaultExpression#canonicalSemanticContent()}.
 *
 * <p>Nodes cover the complete checked expression construct set — the
 * fourteen {@code deal.ast} expression kinds — plus the statement
 * grammar of function-expression and function-declaration bodies nested
 * inside a default (the {@code provider-versioned-default-plans} D9
 * closed statement set). Every node carries:</p>
 * <ul>
 *   <li>{@code kind} — the closed {@link NodeKind}.</li>
 *   <li>{@code operatorKind} — the {@code BinaryOp}/{@code UnaryOp}
 *       name for binary/unary nodes, {@code null} elsewhere.</li>
 *   <li>{@code resultType} — the node's resolved (checked) type;
 *       {@code null} for statement nodes (statements carry no type).</li>
 *   <li>{@code contextualTypes} — the ordered expected-type contexts
 *       that applied where the planner can derive them from the checked
 *       facts: the field type on the root, the constructed class type on
 *       contextual class literals, parameter types on call arguments,
 *       the target type on assignment values, and the resolved type on
 *       table member reads. Contexts the checked facts do not record are
 *       never invented.</li>
 *   <li>{@code children} — ordered children: evaluation order for
 *       expressions, source order for statement blocks, the fixed roles
 *       for control-flow statements.</li>
 *   <li>{@code literalValue} — the exact lossless scalar for literal
 *       nodes ({@link LiteralValue}), {@code null} elsewhere; never
 *       folded, truncated, or reformatted.</li>
 *   <li>{@code target} — the resolved target of identifier, member
 *       access, call, contextual class-literal, and declaration nodes
 *       ({@link Target}), {@code null} when the node has no named
 *       target.</li>
 *   <li>{@code declaredName} — the declared binding name for
 *       {@code VARIABLE_DECLARATION}, {@code FUNCTION_DECLARATION},
 *       {@code CLASS_DECLARATION}, and {@code FOR_OF} nodes,
 *       {@code null} elsewhere.</li>
 *   <li>{@code statement} — the original statement AST node for
 *       statement kinds (the serializer's recovery surface, e.g. the
 *       nested {@link deal.ast.ClassDeclaration}), {@code null} for
 *       expression nodes.</li>
 *   <li>{@code span} — the construct's complete scalar source range
 *       through the complete-range carrier.</li>
 * </ul>
 *
 * <p><b>Await discipline ({@code provider-versioned-default-plans}
 * D2):</b> an {@code AWAIT} node appears outside a nested function
 * expression or function declaration body only when the checker
 * accepted the program and the planner's E3020 gate did not fire — the
 * gate rejects await at evaluator scope before any plan is published,
 * so evaluator-scope canonical content never contains an
 * {@code AWAIT} node.</p>
 *
 * <p>Immutable and deterministic; no address, timestamp, ordinal, or
 * process state enters any component.</p>
 *
 * @param kind             the closed node kind
 * @param operatorKind     the binary/unary operator name, or null
 * @param resultType       the resolved checked type, or null for
 *                         statements
 * @param contextualTypes  the ordered derivable expected-type contexts
 * @param children         the ordered child nodes
 * @param literalValue     the exact literal scalar, or null
 * @param target           the resolved target, or null
 * @param declaredName     the declared binding name, or null
 * @param statement        the statement AST node, or null
 * @param span             the construct's complete scalar range
 */
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
     * @param kind               the closed target kind
     * @param name               the binding or resource name
     * @param resourceIdentity   the private semantic resource identity
     *                           for resource targets, null otherwise
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
