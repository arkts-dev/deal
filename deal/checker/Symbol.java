package deal.checker;

import deal.ast.ClassField;
import deal.ast.Span;
import deal.ast.TypeNode;
import deal.identity.CanonicalClassIdentity;
import deal.types.Type;

import java.util.List;
import java.util.Map;

/**
 * Sealed hierarchy for symbols stored in the {@link SymbolTable}.
 * Each variant carries the information needed for name resolution and type checking.
 */
public sealed interface Symbol
    permits Symbol.VariableSymbol,
            Symbol.FunctionSymbol,
            Symbol.ClassSymbol,
            Symbol.ModuleSymbol,
            Symbol.IntrinsicSymbol {

    String name();

    /**
     * A local variable or parameter, carrying its declared type and —
     * for a class-typed binding — the exact {@link ClassSymbol} its
     * annotation resolved to. Two same-named class declarations of one
     * canonical identity (a same-module nested-shadowing pair, or a
     * harness-synthesized carrier) stay distinct declarations; the
     * assignment gate uses the recorded declaration to reject a value
     * that crosses them (ISSUE-0717 defect snippet 47).
     *
     * <p>{@code declaredType} is the declaration's own annotation node
     * (never a reconstructed spelling): a qualified class annotation is
     * the exact defining provider's spelling, so an assignment value
     * constructed for this binding keeps that provider's declaration
     * even when a same-named sibling module declares the same class name
     * (GitHub #6).</p>
     */
    record VariableSymbol(String name, Type type, boolean isParameter,
                          ClassSymbol declaredClass, TypeNode declaredType)
            implements Symbol {
        /** The declaration-provenance-free symbol (intrinsics, inference, tests). */
        public VariableSymbol(String name, Type type, boolean isParameter) {
            this(name, type, isParameter, null, null);
        }
    }

    /**
     * A function declaration, carrying its function type and — for a
     * function the frontend walked — its declared parameter type
     * annotations and the module path declaring it. A call argument's
     * construction context resolves the exact declaring provider of a
     * class-typed parameter through the parameter's own declared
     * annotation and the callee's declaring module: never an identity-first
     * match or a first alias. Synthetic symbols (intrinsics, generated
     * helpers, retained harnesses) carry no declaration surface and keep
     * the identity-keyed route.
     */
    record FunctionSymbol(String name, Type.Func funcType,
                          List<TypeNode> paramTypeNodes,
                          String declaringModulePath) implements Symbol {
        public FunctionSymbol {
            if (paramTypeNodes != null) {
                paramTypeNodes = List.copyOf(paramTypeNodes);
            }
        }

        /** The declaration-surface-free symbol (synthetic helpers, retained harnesses). */
        public FunctionSymbol(String name, Type.Func funcType) {
            this(name, funcType, null, null);
        }
    }

    /**
     * A class declaration, carrying its fields, the canonical class
     * identity resolved by the module-identity layer
     * ({@code descriptor-identity-propagation} D1: declared classes
     * carry their resolved identity; the intrinsic Error class carries
     * the {@code BuiltinModule} synthesis), and the module path it
     * belongs to — the private deployment wiring key used for
     * cross-module routing only, never for descriptor text.
     */
    record ClassSymbol(String name, List<ClassField> fields, String modulePath,
                       CanonicalClassIdentity identity) implements Symbol {}

    /**
     * A module imported via {@code import * as Name from "path"}.
     * Includes the import's source span for source-order collision detection
     * (E2006 vs E2007).
     *
     * <p>{@code classSymbols} carries the imported module's own declared
     * class symbols by declared name: a qualified annotation's exact
     * declaration — never an identity-first match or a first alias —
     * resolves through the import's own symbol surface. An empty map
     * (declaration modules, unsupported resolvers) keeps the
     * identity-keyed route.</p>
     *
     * <p>{@code functionSymbols} carries the imported module's own
     * declared function symbols the same way: a call argument's
     * construction context resolves the callee parameter's exact
     * declaring class provider through the parameter's own annotation
     * in the callee's declaring module.</p>
     */
    record ModuleSymbol(String name, Map<String, Type> exports, Span importSpan,
                        Map<String, ClassSymbol> classSymbols,
                        Map<String, FunctionSymbol> functionSymbols)
            implements Symbol {
        public ModuleSymbol {
            classSymbols = classSymbols == null ? Map.of() : classSymbols;
            functionSymbols = functionSymbols == null ? Map.of() : functionSymbols;
        }

        /** The declaration-surface-free import symbol (retained constructors). */
        public ModuleSymbol(String name, Map<String, Type> exports, Span importSpan) {
            this(name, exports, importSpan, Map.of(), Map.of());
        }

        /** The function-surface-free import symbol (retained constructors). */
        public ModuleSymbol(String name, Map<String, Type> exports, Span importSpan,
                            Map<String, ClassSymbol> classSymbols) {
            this(name, exports, importSpan, classSymbols, Map.of());
        }
    }

    /**
     * A built-in / intrinsic function or value (e.g. {@code int}, {@code number}, {@code has}).
     * The intrinsic knows how to type-check call expressions through
     * {@link IntrinsicResolver}.
     */
    record IntrinsicSymbol(String name, Type type, IntrinsicResolver resolver) implements Symbol {}
}
