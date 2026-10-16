package deal.semantic;

import deal.ast.ProgramNode;
import deal.checker.CheckResult;
import deal.diagnostics.DiagnosticRange;
import deal.semantic.ir.ExportInterface;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ResolvedImport;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CheckedModuleInput(
    ModuleId moduleId,
    String sourceId,
    Path sourcePath,
    ProgramNode ast,
    CheckResult checks,
    List<ResolvedImport> imports,
    List<ExportInterface> exports,
    CheckedModuleKind kind,
    Map<DiagnosticRange, String> classLiteralProviders
) {

    /**
     * The input without the recorded class-literal provider aliases (the
     * retained harness constructors): no contextual imported-class
     * literal is recorded, so the one lowering resolves class literals
     * by the checked identity route exactly as before.
     */
    public CheckedModuleInput(ModuleId moduleId, String sourceId, Path sourcePath,
            ProgramNode ast, CheckResult checks, List<ResolvedImport> imports,
            List<ExportInterface> exports, CheckedModuleKind kind) {
        this(moduleId, sourceId, sourcePath, ast, checks, imports, exports, kind,
            Map.of());
    }

    public CheckedModuleInput {
        Objects.requireNonNull(moduleId, "moduleId must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(sourcePath, "sourcePath must not be null");
        Objects.requireNonNull(ast, "ast must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        if (kind == CheckedModuleKind.IMPLEMENTATION) {
            Objects.requireNonNull(checks,
                "an IMPLEMENTATION input entry must carry its CheckResult (producer defect)");
        } else {
            if (checks != null) {
                throw new IllegalArgumentException(
                    "the parent-pinned DECLARATION input kind has no producer: a DECLARATION "
                        + "entry must carry no CheckResult");
            }
        }
        imports = List.copyOf(imports);
        exports = List.copyOf(exports);
        classLiteralProviders = Map.copyOf(classLiteralProviders);
    }
}
