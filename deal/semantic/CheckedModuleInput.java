package deal.semantic;

import deal.ast.ProgramNode;
import deal.checker.CheckResult;
import deal.semantic.ir.ExportInterface;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ResolvedImport;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One implementation module of a {@link CheckedProjectInput} (parent
 * canonical surfaces; foundation F2):
 *
 * <pre>{@code
 * CheckedModuleInput {
 *   moduleId, sourceId, sourcePath, ast, checks: CheckResult,
 *   imports: [ResolvedImport], exports: [ExportInterface],
 *   kind: IMPLEMENTATION | DECLARATION
 * }
 * }</pre>
 *
 * <p>Immutable. Every record this epic produces carries
 * {@code kind = IMPLEMENTATION} with {@code checks} present by
 * construction (the parent-pinned {@code DECLARATION} input kind has no
 * producer under the current orchestrator classification), and the
 * compact constructor enforces that invariant: an {@code IMPLEMENTATION}
 * entry without {@code checks} — or a {@code DECLARATION} entry with one —
 * is a producer defect rejected at construction, never a legitimate
 * state. The shared {@code ast}/{@code checks} references are read-only
 * inputs for the later lowering epics; the builder never mutates them.</p>
 *
 * @param moduleId   the dotted module path; non-null
 * @param sourceId   the resolved source file path (stable source identity); non-null
 * @param sourcePath the resolved source file; non-null
 * @param ast        the parsed program AST (read-only, shared); non-null
 * @param checks     the module's checked facts; non-null for IMPLEMENTATION
 * @param imports    the resolved imports in source order; non-null
 * @param exports    the export entries in declaration order; non-null
 * @param kind       the closed input kind; non-null
 */
public record CheckedModuleInput(
    ModuleId moduleId,
    String sourceId,
    Path sourcePath,
    ProgramNode ast,
    CheckResult checks,
    List<ResolvedImport> imports,
    List<ExportInterface> exports,
    CheckedModuleKind kind
) {

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
    }
}
