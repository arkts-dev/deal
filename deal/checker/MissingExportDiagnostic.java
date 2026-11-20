package deal.checker;

import deal.ast.Span;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.DiagnosticFacts;

import java.util.List;

/** One producer for value and annotation lookup failures on an observed module surface. */
final class MissingExportDiagnostic {
    private MissingExportDiagnostic() {}

    static CompilerDiagnostic create(Symbol.ModuleSymbol module, String requested, String operation, Span use) {
        var all = module.exports().keySet().stream().sorted().toList();
        var available = all.subList(0, Math.min(32, all.size()));
        var importRange = module.importSpan() == null ? null : module.importSpan().range();
        var diagnostic = CompilerDiagnostic.error(DiagnosticCode.E2004,
                "Export '" + requested + "' not found in module '" + module.name()
                        + "'. Available: " + String.join(", ", module.exports().keySet()), use)
                .withFacts(new DiagnosticFacts("module.export-missing", operation,
                        "exported member of " + module.name(), requested, List.of(
                                "Inspect the imported module and choose the intended existing export or revise its declaration; listed names do not establish type, call or construction compatibility."))
                        .withExports(new DiagnosticFacts.ExportContext(module.name(), requested, available,
                                module.exports().size(), module.exports().size() > available.size(), importRange,
                                deal.diagnostics.DiagnosticDetails.strings(all))));
        return importRange == null ? diagnostic : diagnostic.withNote("Module imported here", importRange);
    }
}
