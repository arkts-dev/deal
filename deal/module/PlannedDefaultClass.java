package deal.module;

import deal.ast.ClassDeclaration;

import java.util.List;
import java.util.Objects;

public record PlannedDefaultClass(
    CompilerClassDefaultPlan plan,
    ClassDeclaration declaration,
    List<DefaultResourceOccurrence> occurrences
) {

    public PlannedDefaultClass {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(declaration, "declaration");
        occurrences = List.copyOf(Objects.requireNonNull(occurrences,
            "occurrences"));
    }
}
