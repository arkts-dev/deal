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
     * annotation resolved to. Two same-named class declarations in one
     * module share the canonical identity (module + name) but stay
     * distinct declarations; the assignment gate uses the recorded
     * declaration to reject a value that crosses them (ISSUE-0717
     * defect snippet 47).
     *
     * <p>{@code declaredType} is the declaration's own annotation node
     * (never a reconstructed spelling): a qualified class annotation is
     * the exact defining provider's spelling, so an assignment value
     * constructed for this binding keeps that provider's declaration
     * even when an identity-equal sibling module declares the same
     * class name (GitHub #6).</p>
     */
    record VariableSymbol(String name, Type type, boolean isParameter,
                          ClassSymbol declaredClass, TypeNode declaredType)
            implements Symbol {
        /** The declaration-provenance-free symbol (intrinsics, inference, tests). */
        public VariableSymbol(String name, Type type, boolean isParameter) {
            this(name, type, isParameter, null, null);
        }
    }

    /** A function declaration, carrying its function type. */
    record FunctionSymbol(String name, Type.Func funcType) implements Symbol {}

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
     */
    record ModuleSymbol(String name, Map<String, Type> exports, Span importSpan) implements Symbol {}

    /**
     * A built-in / intrinsic function or value (e.g. {@code int}, {@code number}, {@code has}).
     * The intrinsic knows how to type-check call expressions through
     * {@link IntrinsicResolver}.
     */
    record IntrinsicSymbol(String name, Type type, IntrinsicResolver resolver) implements Symbol {}
}
