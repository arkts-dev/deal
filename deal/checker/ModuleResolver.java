package deal.checker;

import java.util.Map;
import java.util.Set;
import deal.ast.TypeNode;
import deal.identity.CanonicalModuleIdentity;
import deal.types.Type;

/**
 * Interface for module resolution. The type checker delegates import
 * resolution to the module system through this interface.
 *
 * <p>For unit testing, a simple stub implementation can be provided.
 * The production implementation lives in the module system.</p>
 */
public interface ModuleResolver {

    /**
     * Resolve a module path and return its exported symbols.
     *
     */
    Map<String, Type> resolveModule(String modulePath, String importingModule,
                                     Set<String> modulesInProgress)
        throws ModuleNotFoundException;
    /**
     * Resolves the imported module's declared class symbols by the raw
     * import specifier (the exact declaring occurrence route): the
     * alias's module supplies its own class declarations, so a
     * same-spelled carrier of one identity (a harness-shared pair) never
     * substitutes for the declaration. The default returns an empty map
     * (unsupported by non-production resolvers); callers keep the
     * identity-keyed route then.
     */
    default Map<String, Symbol.ClassSymbol> resolveModuleClassSymbols(
            String modulePath, String importingModule,
            Set<String> modulesInProgress)
            throws ModuleNotFoundException {
        return Map.of();
    }

    /**
     * Resolves the imported module's declared function symbols by
     * declared name (the exact callee-declaration route): a call
     * argument's construction context resolves the callee parameter's
     * own declared class annotation in the callee's declaring module, so a
     * same-spelled carrier of one identity never substitutes for the
     * declaration. The default returns an empty map (unsupported by
     * non-production resolvers); callers keep the identity-keyed route
     * then.
     */
    default Map<String, Symbol.FunctionSymbol> resolveModuleFunctionSymbols(
            String modulePath, String importingModule,
            Set<String> modulesInProgress)
            throws ModuleNotFoundException {
        return Map.of();
    }

    /**
     * Resolves the exact declared class symbol one class-typed annotation
     * names in the supplied declaring module's own scope, or {@code null}:
     * the declared-symbol counterpart of
     * {@link #resolveTypeNodeInModule}. The annotation's own declared
     * symbol — never an identity-keyed or textual lookup, which a
     * harness-shared identity cannot answer — selects the declaration. The
     * default returns {@code null} (unsupported by non-production
     * resolvers); callers keep the identity-keyed route then.
     */
    default Symbol.ClassSymbol resolveTypeNodeClassSymbolInModule(
            TypeNode typeNode, String modulePath, String importingModule)
        throws ModuleNotFoundException {
        return null; // unsupported by default
    }

    /**
     * Resolve a class symbol from an imported module.
     *
     * <p>Used by the type checker and code generator to look up class field
     * information for imported classes that are not in the local scope.</p>
     *
     */
    Symbol.ClassSymbol resolveClassSymbol(String className, String modulePath,
                                           String importingModule)
        throws ModuleNotFoundException;

    /**
     * Resolves a class symbol of an imported module by the declaring
     * module's canonical module identity (the v1.2 identity-carriage
     * routing — descriptor-identity-propagation D1): imported classes
     * carry the declaring source's identity, and the resolver routes on
     * it.  The default returns {@code null} (unsupported); the
     * production {@code ModuleResolverImpl} and identity-aware harnesses
     * route by the module-identity classification.
     *
     */
    default Symbol.ClassSymbol resolveClassSymbol(String className,
            CanonicalModuleIdentity declaringModule, String importingModule)
            throws ModuleNotFoundException {
        return null; // unsupported by default
    }

    /**
     * Checks whether a function is exported from the module with the
     * given canonical module identity.  The default returns
     * {@code false} (unsupported); identity-aware resolvers route by
     * the module-identity classification.
     *
     */
    default boolean isFunctionExportedFromModule(
            CanonicalModuleIdentity declaringModule, String functionName,
            String importingModule) throws ModuleNotFoundException {
        return false; // unsupported by default
    }

    /**
     * Resolves a field {@link TypeNode} against the scope of the module
     * identified by {@code modulePath} (the owning module of a class
     * declaration).
     *
     * <p>Used by the type checker to resolve field type annotations of a
     * cross-module {@link Symbol.ClassSymbol} against the declaring
     * module's scope, so that bare class names inside the field type
     * (e.g. {@code Address[]}) resolve against the owning module's
     * declarations rather than the importing module's.</p>
     *
     * <p>The default implementation returns {@code null} (unsupported);
     * callers must fall back to their local resolution when {@code null}
     * is returned. Existing implementations stay source-compatible.</p>
     *
     */
    default Type resolveTypeNodeInModule(TypeNode typeNode, String modulePath,
                                         String importingModule)
            throws ModuleNotFoundException {
        return null; // unsupported by default
    }

    /** Exception thrown when a module cannot be found. */
    final class ModuleNotFoundException extends Exception {
        private static final long serialVersionUID = 1L;

        public ModuleNotFoundException(String message) {
            super(message);
        }
    }

}
