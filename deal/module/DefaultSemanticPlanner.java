package deal.module;

import deal.ast.ClassDeclaration;
import deal.ast.ClassField;
import deal.checker.SymbolTable;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.identity.CanonicalClassIdentity;
import deal.types.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Structural class planning in declaration order; planning executes no source. */
public final class DefaultSemanticPlanner {
    private DefaultSemanticPlanner() {}

    public record Result(List<CompilerDiagnostic> diagnostics,
                         List<PlannedDefaultClass> plannedClasses) {
        public Result {
            diagnostics = List.copyOf(diagnostics);
            plannedClasses = List.copyOf(plannedClasses);
        }
        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(d -> "error".equals(d.severity()));
        }
    }

    public static Result plan(DefaultPlanModuleInput input, ModuleIdentityAssembly identities) {
        return plan(input, identities, Map.of());
    }

    public static Result plan(DefaultPlanModuleInput input, ModuleIdentityAssembly identities,
                              Map<String, DefaultPlanImport> providers) {
        Objects.requireNonNull(input);
        Objects.requireNonNull(identities);
        Objects.requireNonNull(providers);
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        List<PlannedDefaultClass> classes = new ArrayList<>();
        for (Map.Entry<ClassDeclaration, SymbolTable> declaration
                : input.checkResult().classScopes().entrySet()) {
            ClassDeclaration cd = declaration.getKey();
            var identity = identities.requireClassIdentity(input.location(), cd.name(), cd.span());
            if (identity instanceof ModuleIdentityAssembly.ClassIdentityResult.Failure failure) {
                diagnostics.add(failure.diagnostic());
                continue;
            }
            CanonicalClassIdentity classId =
                ((ModuleIdentityAssembly.ClassIdentityResult.Identity) identity).identity();
            input.nameResolver().setCurrentScope(declaration.getValue());
            List<CompilerClassDefaultEntry> fields = new ArrayList<>();
            boolean failed = false;
            for (ClassField field : cd.fields()) {
                Type type = input.nameResolver().resolveTypeNode(field.type());
                if (type == null || type == Type.Error.INSTANCE) {
                    diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E6005,
                        "class planning failed: unresolved field '" + field.name() + "'",
                        field.span().range()));
                    failed = true;
                    continue;
                }
                fields.add(new CompilerClassDefaultEntry(field.name(), type,
                    input.descriptors().encode(type), field.optional()));
            }
            if (!failed) {
                classes.add(new PlannedDefaultClass(new CompilerClassDefaultPlan(classId,
                    input.location().semanticModuleIdentity(), fields, Set.of()), cd,
                    List.of(), Map.of()));
            }
        }
        return new Result(diagnostics, diagnostics.isEmpty() ? classes : List.of());
    }
}
