package deal.module;

import deal.semantic.ir.CanonicalJson;

import java.util.Objects;

/**
 * The inline nesting payload of a {@link CanonicalStatement} (the
 * serializer-owned canonical statement grammar, ISSUE-0542, design
 * source {@code provider-versioned-default-plans} D9):
 * {@code FUNCTION_DECLARATION} statements inline the nested function's
 * full {@link CanonicalFunctionSemantics}; {@code CLASS_DECLARATION}
 * statements inline the nested class's full canonical default-plan
 * content ({@code provider-versioned-default-plans} D6
 * {@code canonicalPlanContent} projection) — syntactic nesting only,
 * never through reference indirection.</p>
 */
public sealed interface CanonicalNested
    permits CanonicalNested.FunctionSemantics, CanonicalNested.PlanContent {

    /**
     * The inline nested function semantics of a
     * {@code FUNCTION_DECLARATION} statement.
     *
     * @param functionSemantics the nested function's full canonical
     *                          semantics
     */
    record FunctionSemantics(CanonicalFunctionSemantics functionSemantics)
            implements CanonicalNested {

        public FunctionSemantics {
            Objects.requireNonNull(functionSemantics,
                "functionSemantics");
        }

        @Override
        public CanonicalJson.Value canonicalJson() {
            return CanonicalJson.obj(CanonicalJson.e("function",
                functionSemantics.canonicalJson()));
        }
    }

    /**
     * The inline canonical default-plan content of a
     * {@code CLASS_DECLARATION} statement.
     *
     * @param canonicalPlanContent the nested class's full canonical
     *                             plan-content text (the D6
     *                             {@code canonicalPlanContent}
     *                             projection, byte-exact)
     */
    record PlanContent(String canonicalPlanContent) implements CanonicalNested {

        public PlanContent {
            Objects.requireNonNull(canonicalPlanContent,
                "canonicalPlanContent");
        }

        @Override
        public CanonicalJson.Value canonicalJson() {
            return CanonicalJson.obj(CanonicalJson.e("planContent",
                CanonicalJson.str(canonicalPlanContent)));
        }
    }

    /**
     * The nested payload's canonical JSON value.
     *
     * @return the canonical value, never null
     */
    CanonicalJson.Value canonicalJson();
}
