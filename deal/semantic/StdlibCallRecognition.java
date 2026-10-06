package deal.semantic;

import deal.ast.ExpressionNode;
import deal.ast.IdentifierExpr;
import deal.ast.MemberAccessExpr;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.semantic.ir.ExternalModuleKind;
import deal.semantic.ir.ResolvedImport;
import deal.semantic.StdlibFunctionCatalog.Entry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class StdlibCallRecognition {

    private StdlibCallRecognition() {
        // Static surface only; no instances and no state.
    }

    /**
     * The closed recognition predicate (D1): the catalog entry for a
     * callee recognized as a stdlib call from checked facts only, or
     * {@code Optional.empty()} — "not a stdlib call" — for every other
     * shape.
     *
     */
    public static Optional<Entry> recognize(ExpressionNode callee, SymbolTable checkerScope,
                                            List<ResolvedImport> imports) {
        Objects.requireNonNull(callee, "callee must not be null");
        Objects.requireNonNull(imports, "imports must not be null");
        if (!(callee instanceof MemberAccessExpr access)) {
            return Optional.empty();
        }
        if (!(access.object() instanceof IdentifierExpr identifier)) {
            return Optional.empty();
        }
        if (checkerScope == null) {
            return Optional.empty();
        }
        Symbol symbol = checkerScope.resolve(identifier.name());
        if (!(symbol instanceof Symbol.ModuleSymbol moduleSymbol)) {
            return Optional.empty();
        }
        ResolvedImport importFact = null;
        for (ResolvedImport candidate : imports) {
            if (candidate.alias().equals(moduleSymbol.name())) {
                importFact = candidate;
                break;
            }
        }
        if (importFact == null) {
            return Optional.empty();
        }
        if (importFact.kind() != ExternalModuleKind.STDLIB) {
            return Optional.empty();
        }
        return StdlibFunctionCatalog.lookup(
            importFact.resolvedModuleId().path(), access.field());
    }
}
