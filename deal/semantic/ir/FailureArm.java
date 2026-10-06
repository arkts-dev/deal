package deal.semantic.ir;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One immutable entry of the closed failure-arm table (canonical
 * failure-projection authority P1): the projection authority every
 * consumer renders.
 *
 * <p>An arm carries the {@link FailurePolicyId} row it renders (code,
 * precedence, origin/cause/frame rules), its complete message template
 * with its named parameters, the closed source id of each parameter, its
 * expected-token source or absence, its actual projection or absence, its
 * origin convention, and its render scope. The arm's template is an
 * element of its row's template list, at the arm's
 * {@link #templateIndex()} position, and the row's list equals its
 * declared arms' templates in arm order (the registry's fail-closed
 * consistency invariant).</p>
 *
 */
public record FailureArm(
    FailureArmId id,
    FailurePolicyId policy,
    int templateIndex,
    String template,
    List<String> parameters,
    Map<String, ParameterSource> parameterSources,
    ExpectedSource expectedSource,
    String pinnedExpectedText,
    ActualProjection actualProjection,
    OriginConvention origin,
    RenderScope scope,
    deal.diagnostics.DiagnosticCode code
) {

    /** The arm's render scope (P1). */
    public enum RenderScope {
        /** The arm renders the complete tuple at a boundary/operation site. */
        TOP_LEVEL,
        /**
         * The arm renders a message text substituted into another arm's
         * {@code {inner}} parameter only; it publishes no tuple of its own.
         * A render at a boundary/operation site is a producer defect.
         */
        INNER_ONLY
    }

    /** The closed expected-token sources (P2). */
    public enum ExpectedSource {
        /** The arm declares no expected field. */
        NONE,
        /** The failing cell's descriptor text (the declared cell descriptor). */
        CELL_DESCRIPTOR,
        /** The typed-boundary kind token of the checked descriptor. */
        KIND_TOKEN,
        /** The array element descriptor text. */
        ELEMENT_DESCRIPTOR,
        /** The declared class atom. */
        CLASS_ATOM,
        /** The canonical signature text of the declared function descriptor. */
        SIGNATURE,
        /** The pinned fixed text of the arm's own row. */
        PINNED_TEXT,
        /** The fixed async-operation token. */
        ASYNC_OPERATION_TOKEN
    }

    /** The closed actual projections (P2). */
    public enum ActualProjection {
        /** The arm declares no actual field. */
        NONE,
        /** The typed-boundary projection (P2 item 1). */
        TYPED_BOUNDARY,
        /** The carrier-kind projection (P2 item 2). */
        CARRIER_KIND,
        /** The completion variant of the typed-boundary projection (P2 item 4). */
        COMPLETION,
        /** The fixed {@code nothing} token. */
        NOTHING_TOKEN,
        /** The carried canonical class atom. */
        CARRIED_CLASS_ATOM,
        /**
         * The failing value's canonical text (the {@code SQRT_NEGATIVE}
         * arm's pinned hex-float actual projection).
         */
        CANONICAL_VALUE_TEXT,
        /** The carried canonical signature text. */
        CARRIED_SIGNATURE,
        /**
         * The {@code @jsonable} {@code C$toJson} walk's projection binding:
         * declared here, bound by the sibling that realizes the family. A
         * production consumer rendering the arm is a producer defect.
         */
        SIBLING_OWNED
    }

    /** The closed parameter sources of the arm templates (P2). */
    public enum ParameterSource {
        /** The typed-boundary kind text of the checked descriptor. */
        KIND_TEXT,
        /** The failing cell's descriptor text. */
        CELL_DESCRIPTOR,
        /** The array element descriptor text. */
        ELEMENT_DESCRIPTOR,
        /** The declared canonical signature text. */
        DECLARED_SIGNATURE,
        /** The carried canonical signature text. */
        CARRIED_SIGNATURE,
        /** The declared class atom. */
        CLASS_ATOM,
        /** The carried class atom. */
        CARRIED_CLASS_ATOM,
        /** The carried canonical class atom or the typed-boundary kind token. */
        CARRIED_OR_TYPED_KIND,
        /** The sibling-owned walk projection's actual text. */
        SIBLING_OWNED_ACTUAL,
        /** The one-based parameter index. */
        PARAMETER_INDEX,
        /** The host inner-reason render's text. */
        HOST_INNER_REASON,
        /** The typed-boundary actual token. */
        TYPED_BOUNDARY_ACTUAL,
        /** The carrier-kind actual token. */
        CARRIER_KIND_ACTUAL,
        /** The array element's one-based index. */
        ONE_BASED_INDEX,
        /** The host module name. */
        MODULE,
        /** The host-load reason text. */
        REASON,
        /** The host export or class name. */
        NAME,
        /** The class field name. */
        FIELD,
        /** The class identity atom. */
        CLASS_ID,
        /** The host-load expected text. */
        EXPECTED,
        /** The host-load actual text. */
        ACTUAL,
        /** The JSON field path. */
        FIELD_PATH,
        /** The one-based JSON parse byte offset. */
        ONE_BASED_BYTE_OFFSET
    }

    /** The closed origin conventions (P3). */
    public enum OriginConvention {
        /** The failing op's own {@code SourceOrigin} (the closed per-cell table). */
        BOUNDARY_CELL,
        /** The callee's declared parameter type-annotation span, in the callee's file. */
        DECLARED_PARAMETER,
        /** The invoking call expression. */
        CALL_EXPRESSION,
        /** The await expression. */
        AWAIT_EXPRESSION,
        /** The operation expression (arithmetic/int32). */
        OPERATION_EXPRESSION,
        /** The declared binding's declaration. */
        DECLARED_BINDING,
        /** The returned expression in the returning body. */
        RETURNED_EXPRESSION,
        /** The read expression. */
        READ_EXPRESSION,
        /** The index expression (the bytes cells' bounds check). */
        INDEX_EXPRESSION,
        /** The assignment expression. */
        ASSIGNMENT_EXPRESSION,
        /** The delete-target expression. */
        DELETE_TARGET,
        /** The class literal expression. */
        CLASS_LITERAL,
        /** The import statement. */
        IMPORT_STATEMENT,
        /** The for-of statement span (the op's own origin). */
        FOR_OF_STATEMENT,
        /** The exporting/invoking unit's program span. */
        PROGRAM_SPAN,
        /**
         * The {@code @jsonable} helper cells: sibling-owned until the
         * family is realized.
         */
        SIBLING_OWNED
    }

    public FailureArm {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(template, "template must not be null");
        Objects.requireNonNull(parameters, "parameters must not be null");
        Objects.requireNonNull(parameterSources, "parameterSources must not be null");
        Objects.requireNonNull(expectedSource, "expectedSource must not be null");
        Objects.requireNonNull(actualProjection, "actualProjection must not be null");
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        if (templateIndex < 0) {
            throw new IllegalArgumentException("templateIndex must be non-negative");
        }
        if (expectedSource == ExpectedSource.PINNED_TEXT) {
            Objects.requireNonNull(pinnedExpectedText,
                "a PINNED_TEXT expected source carries its pinned text");
        } else if (pinnedExpectedText != null) {
            throw new IllegalArgumentException("arm " + id + " declares a pinned expected "
                + "text with expected source " + expectedSource);
        }
        parameters = List.copyOf(parameters);
        parameterSources = Map.copyOf(parameterSources);
        if (!parameterSources.keySet().equals(new java.util.LinkedHashSet<>(parameters))) {
            throw new IllegalArgumentException("arm " + id + " declares parameter sources "
                + parameterSources.keySet() + " for parameters " + parameters);
        }
    }

    /** True iff the arm's projection binding is sibling-owned. */
    public boolean isSiblingOwned() {
        return actualProjection == ActualProjection.SIBLING_OWNED;
    }

    /** True iff the arm is rendered only as another arm's inner reason. */
    public boolean isInnerOnly() {
        return scope == RenderScope.INNER_ONLY;
    }

    /** True iff the arm declares an expected field. */
    public boolean declaresExpected() {
        return expectedSource != ExpectedSource.NONE;
    }

    /** True iff the arm declares an actual field. */
    public boolean declaresActual() {
        return actualProjection != ActualProjection.NONE;
    }
}
