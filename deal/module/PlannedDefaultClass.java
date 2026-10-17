package deal.module;

import deal.ast.ClassDeclaration;
import deal.diagnostics.DiagnosticRange;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record PlannedDefaultClass(
    CompilerClassDefaultPlan plan,
    ClassDeclaration declaration,
    List<DefaultResourceOccurrence> occurrences,
    Map<DiagnosticRange, String> classLiteralProviders
) {

    public PlannedDefaultClass {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(declaration, "declaration");
        occurrences = List.copyOf(Objects.requireNonNull(occurrences,
            "occurrences"));
        classLiteralProviders = Map.copyOf(Objects.requireNonNull(
            classLiteralProviders, "classLiteralProviders"));
    }

    /**
     * The planned class without recorded contextual class-literal
     * provider aliases (the retained harness constructors).
     */
    public PlannedDefaultClass(CompilerClassDefaultPlan plan,
            ClassDeclaration declaration,
            List<DefaultResourceOccurrence> occurrences) {
        this(plan, declaration, occurrences, Map.of());
    }
}
