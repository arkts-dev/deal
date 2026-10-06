package deal.module;

import deal.semantic.ir.CanonicalJson;

import java.util.Objects;

public sealed interface CanonicalNested
    permits CanonicalNested.FunctionSemantics, CanonicalNested.PlanContent {

    /**
     * The inline nested function semantics of a
     * {@code FUNCTION_DECLARATION} statement.
     *
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
     */
    CanonicalJson.Value canonicalJson();
}
